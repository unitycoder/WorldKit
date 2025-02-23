package wk.utils

import wk.api.ByteArrayMatrix
import wk.api.Matrix
import java.awt.image.BufferedImage
import java.io.*
import java.lang.Math.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.*
import javax.imageio.ImageIO

class TerrainAmplificationDictionary(val maskSize: Int, val offset: Int, val low: RcMatrix, val high2: RcMatrix, val high4: RcMatrix, val high8: RcMatrix? = null) {

    companion object {
        fun read(file: File) = DataInputStream(GZIPInputStream(file.inputStream().buffered()).buffered()).use { read(it) }

        fun read(input: DataInputStream): TerrainAmplificationDictionary {
            val maskSize = input.readInt()
            val offset = input.readInt()
            val low = RcMatrix.read(input)
            val high2 = RcMatrix.read(input)
            val high4 = RcMatrix.read(input)
            val has8 = input.readBoolean()
            val high8 = if (has8) {
                RcMatrix.read(input)
            } else {
                null
            }
            return TerrainAmplificationDictionary(maskSize, offset, low, high2, high4, high8)
        }
    }

    fun write(file: File) = DataOutputStream(GZIPOutputStream(file.outputStream().buffered()).buffered()).use { write(it) }

    fun write(output: DataOutputStream) {
        output.writeInt(maskSize)
        output.writeInt(offset)
        low.write(output)
        high2.write(output)
        high4.write(output)
        output.writeBoolean(high8 != null)
        high8?.write(output)
    }
}

object TerrainAmplification {

    @JvmStatic
    fun main(vararg args: String) {
        val outputTerrainFile = File(args[2])
        val factor = args[3].toInt()
        val dictionaryFiles = args.drop(4).map { File(it) }
        val (maskSize, offset, dictionaries) = loadDictionaries(factor, dictionaryFiles)
        val input = imageToMatrix(ImageIO.read(File(args[0])))
        val inputIndex = dilateMask(imageToMask(ImageIO.read(File(args[1]))), maskSize * 2)
        val dictionaryHeight = (input.rows + maskSize) / offset
        val dictionaryWidth = (input.columns + maskSize) / offset
        val inputIndexMask = inputIndexToIndexMask(maskSize, offset, dictionaryWidth, dictionaryHeight, inputIndex, dictionaries.size)

        val (output, min, scale) = amplify(factor, input, inputIndexMask, maskSize, offset, dictionaries)

        writeOutput(output, outputTerrainFile.parentFile, outputTerrainFile.name, input.columns * factor,input.rows * factor, maskSize * factor, min, scale)
    }

    private inline fun <T> time(operation: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val stop = System.nanoTime()
        println("$operation: ${((stop - start)/1000000)/1000.0f}s")
        return result
    }

    fun amplify(factor :Int, input: RcMatrix, inputIndexMask: IntArray, maskSize: Int, offset: Int, dictionaries: List<Pair<RcMatrix, RcMatrix>>): Triple<RcMatrix, Float, Float> {
        val dilated = time("dilateTerrain") { dilateTerrain(input, maskSize * 2) }
        val mask = time("buildMask") { buildMask(maskSize) }
        val maskSizeHigh = maskSize * factor
        val maskHigh = time("buildMaskHigh") { buildMask(maskSizeHigh) }
        val offsetSynthesisHigh = offset * factor
        val divisorMask = time("buildDivisorMask") { buildDivisorMask(maskHigh, offsetSynthesisHigh) }
        val usefulIndices = time("buildMaskUsefulIndices") { buildMaskUsefulIndices(mask) }

        val (coefficients, means) = time("optimization") {
             optimizeTerrainWithDictionary(dictionaries.map { it.first }, inputIndexMask, maskSize, mask, offset, dilated, usefulIndices)
        }

        return time("synthesis") {
            synthesize(dilated, maskSize, offset, coefficients, dictionaries.map { it.second }, inputIndexMask, input, factor, maskSizeHigh, offsetSynthesisHigh, means, maskHigh, divisorMask)
        }
    }

    fun loadDictionaries(factor: Int, dictionaryFiles: List<File>): Triple<Int, Int, List<Pair<RcMatrix, RcMatrix>>> {
        var first = true
        var maskSize = 0
        var offset = 0
        val dictionaries = dictionaryFiles.map {
            val dictionary = TerrainAmplificationDictionary.read(it)
            val dictionaryHigh = if (factor == 2) {
                dictionary.high2
            } else if (factor == 4) {
                dictionary.high4
            } else if (factor == 8 && dictionary.high8 != null) {
                dictionary.high8
            } else {
                throw RuntimeException("invalid factor for dictionary")
            }
            if (first) {
                first = false
                maskSize = dictionary.maskSize
                offset = dictionary.offset
            } else {
                if (maskSize != dictionary.maskSize || offset != dictionary.offset) {
                    throw RuntimeException("input dictionaries are not compatible")
                }
            }
            dictionary.low to dictionaryHigh
        }
        if (dictionaries.isEmpty()) {
            throw RuntimeException("must specify one or more dictionary files")
        }
        return Triple(maskSize, offset, dictionaries)
    }

    private fun synthesize(dilated: RcMatrix, maskSize: Int, offsetSynthesis: Int, coefficients: Coefficients, dictionaries: List<RcMatrix>, inputIndexMask: IntArray, inputTerrain: RcMatrix, factor: Int, maskSizeHigh: Int, offsetSynthesisHigh: Int, means: RcMatrix, maskHigh: RcMatrix, divisorMask: RcMatrix): Triple<RcMatrix, Float, Float> {
        val d1 = (dilated.rows - maskSize) / offsetSynthesis
        val d2 = (dilated.columns - maskSize) / offsetSynthesis
        val synthesized = RcMatrix(inputTerrain.rows * factor + 2 * maskSizeHigh, inputTerrain.columns * factor + 2 * maskSizeHigh)
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        val locks = Array(d1+1) { ReentrantLock() }
        (0 until d1).toList().parallelStream().map { i ->
            var minLocal = Float.MAX_VALUE
            var maxLocal = -Float.MAX_VALUE
            doWithLocks(locks[i], locks[i+1], 5) {
                val id2 = i * d2
                val iRange = i * offsetSynthesisHigh until i * offsetSynthesisHigh + maskSizeHigh
                for (j in 0 until d2) {
                    val jRange = j * offsetSynthesisHigh until j * offsetSynthesisHigh + maskSizeHigh
                    val coeffIndex2 = (id2 + j)
                    val dictionary = dictionaries[inputIndexMask[coeffIndex2]]
                    val mean = means[i, j]
                    iRange.forEachIndexed { rowIndex, row ->
                        val maskRow = row % offsetSynthesisHigh * offsetSynthesisHigh
                        val rowIndexOff = rowIndex * maskSizeHigh
                        val rowOff = row * synthesized.columns
                        jRange.forEachIndexed { colIndex, col ->
                            val maskVal = maskHigh[rowIndexOff + colIndex]
                            if (maskVal != 0.0f) {
                                val cur = synthesized[rowOff + col] + coefficients[coeffIndex2, (colIndex * maskSizeHigh + rowIndex), dictionary] + maskVal * mean * divisorMask[maskRow + col % offsetSynthesisHigh]
                                synthesized[rowOff + col] = cur
                                if (cur < minLocal) {
                                    minLocal = cur
                                }
                                if (cur > maxLocal) {
                                    maxLocal = cur
                                }
                            }
                        }
                    }
                }
            }
            minLocal to maxLocal
        }.forEach { (localMin, localMax) ->
            if (localMin < min) {
                min = localMin
            }
            if (localMax > max) {
                max = localMax
            }
        }
        return Triple(synthesized, min, 65535.0f / (max - min))
    }

    private fun buildDivisorMask(maskHigh: RcMatrix, offsetSynthesisHigh: Int): RcMatrix {
        val maskSizeHigh = maskHigh.rows
        val offsetsPerMask = Math.round(Math.ceil(maskSizeHigh / offsetSynthesisHigh.toDouble()).toFloat())
        val iterations = offsetsPerMask * 3
        val maskWidth = (iterations - 1) * offsetSynthesisHigh + maskSizeHigh
        val testDivisor = RcMatrix(maskWidth, maskWidth)
        for (i in 0 until iterations) {
            val rowRange = i * offsetSynthesisHigh until i * offsetSynthesisHigh + maskSizeHigh
            for (j in 0 until iterations) {
                val colRange = j * offsetSynthesisHigh until j * offsetSynthesisHigh + maskSizeHigh
                rowRange.forEachIndexed { rowIndex, row ->
                    val rowIndexOff = rowIndex * maskSizeHigh
                    val rowOff = row * maskWidth
                    colRange.forEachIndexed { colIndex, col ->
                        testDivisor[rowOff + col] += maskHigh[rowIndexOff + colIndex]
                    }
                }
            }
        }
        val divisorRange = offsetSynthesisHigh * offsetsPerMask until offsetSynthesisHigh * offsetsPerMask + offsetSynthesisHigh
        val divisorMask = testDivisor[divisorRange, divisorRange]
        for (i in 0 until divisorMask.size) {
            divisorMask[i] = 1.0f / divisorMask[i]
        }
        return divisorMask
    }

    fun buildMaskUsefulIndices(mask: RcMatrix): IntArray {
        return mask.array.mapIndexed { i, d -> i to d }.filterNot { (_, d) -> d == 0.0f }.map { it.first }.toIntArray()
    }

    private fun <T> doWithLocks(lock1: ReentrantLock, lock2: ReentrantLock, timeout: Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS, block: () -> T): T {
        while (true) {
            try {
                if (lock1.tryLock(timeout, timeUnit)) {
                    if (lock2.tryLock(timeout, timeUnit)) {
                        break
                    } else {
                        lock1.unlock()
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            return block()
        } finally {
            lock2.unlock()
            lock1.unlock()
        }
    }

    private fun optimizeTerrainWithDictionary(dictionaries: List<RcMatrix>, inputIndexMask: IntArray, maskSize: Int, mask: RcMatrix, offset: Int, synthesized: RcMatrix, usefulIndices: IntArray): Pair<Coefficients, RcMatrix> {
        val dictionaryHeight = (synthesized.rows - maskSize) / offset
        val dictionaryWidth = (synthesized.columns - maskSize) / offset
        val maskMeans = buildMeans(maskSize, offset, dictionaryHeight, dictionaryWidth, synthesized)
        val baseAtoms = buildAtoms(mask, offset, dictionaryHeight, dictionaryWidth, maskMeans, synthesized)
        val coefficients = matching(dictionaries, inputIndexMask, baseAtoms, usefulIndices)
        return coefficients to maskMeans
    }

    fun buildMeans(maskSize: Int, offset: Int, dictionaryHeight: Int, dictionaryWidth: Int, terrain: RcMatrix): RcMatrix {
        val means = RcMatrix(dictionaryHeight, dictionaryWidth)
        (0 until dictionaryHeight).toList().parallelStream().forEach { i ->
            val iRange = i * offset until i * offset + maskSize
            for (j in 0 until dictionaryWidth) {
                means[i, j] = terrain.mean(iRange, j * offset until j * offset + maskSize)
            }
        }
        return means
    }

    private fun inputIndexToIndexMask(maskSize: Int, offset: Int, dictionaryWidth: Int, dictionaryHeight: Int, indices: Matrix<Byte>, dictionaryCount: Int): IntArray {
        val indexMask = IntArray(dictionaryHeight * dictionaryWidth)
        (0 until dictionaryHeight).toList().parallelStream().forEach { i ->
            val iRange = i * offset until i * offset + maskSize
            for (j in 0 until dictionaryWidth) {
                indexMask[i * dictionaryWidth + j] = indices.max(iRange, j * offset until j * offset + maskSize, dictionaryCount)
            }
        }
        return indexMask
    }

    private fun buildAtoms(mask: RcMatrix, offset: Int, dictionaryHeight: Int, dictionaryWidth: Int, means: RcMatrix, terrain: RcMatrix): RcMatrix {
        val maskSize = mask.columns
        val baseAtoms = RcMatrix(maskSize * maskSize, dictionaryHeight * dictionaryWidth)
        val cols = baseAtoms.columns
        val baseAtomArray = baseAtoms.array
        (0 until dictionaryHeight).toList().parallelStream().forEach { i ->
            val iRange = i * offset until i * offset + maskSize
            for (j in 0 until dictionaryWidth) {
                val mean = means[i, j]
                var t = 0
                for (col in j * offset until j * offset + maskSize) {
                    for (row in iRange) {
                        baseAtomArray[t * cols + i * dictionaryWidth + j] = (terrain[row, col] - mean) * mask[t]
                        t++
                    }
                }
            }
        }
        return baseAtoms
    }

    fun writeOutput(terrainToWrite: RcMatrix, outputDir: File, fileName: String, columns: Int = terrainToWrite.columns, rows: Int = terrainToWrite.rows, offset: Int = 0, min: Float = 0.0f, scale: Float = 1.0f) {
        val output = BufferedImage(columns, rows, BufferedImage.TYPE_USHORT_GRAY)
        val outputData = output.raster
        val colRange = offset until offset + columns
        for (row in offset until offset + rows) {
            val rowOff = row - offset
            for (column in colRange) {
                outputData.setSample(column - offset, rowOff, 0, (((terrainToWrite[row, column] - min) * scale).toInt()).coerceIn(0, 65535) and 0xFFFF)
            }
        }
        ImageIO.write(output, "png", File(outputDir, fileName))
    }

    fun downscaleImage(image: RcMatrix, factor: Int): RcMatrix {
        val scaledRows = image.rows / factor
        val scaledColumns = image.columns / factor
        val factor2 = factor * factor
        val exampleTerrainLow = RcMatrix(scaledRows, scaledColumns)
        for (row in 0 until scaledRows) {
            val rowStart = row * factor
            val rowEnd = rowStart + factor
            for (column in 0 until scaledColumns) {
                val columnStart = column * factor
                val columnEnd = columnStart + factor
                var sum = 0.0f
                for (rowIndex in rowStart until rowEnd) {
                    for (columnIndex in columnStart until columnEnd) {
                        sum += image[rowIndex, columnIndex]
                    }
                }
                exampleTerrainLow[row, column] = sum / factor2
            }
        }
        return exampleTerrainLow
    }

    fun imageToMask(image: BufferedImage): Matrix<Byte> {
        val mask = ByteArrayMatrix(image.height)
        val sourceData = image.raster
        for (row in 0 until image.height) {
            for (column in 0 until image.width) {
                mask[row, column] = (sourceData.getSample(column, row, 0) and 0xFF).toByte()
            }
        }
        return mask
    }

    fun imageToMatrix(image: BufferedImage): RcMatrix {
        val exampleTerrainHigh = RcMatrix(image.height, image.width)
        val sourceData = image.raster
        for (row in 0 until image.height) {
            for (column in 0 until image.width) {
                exampleTerrainHigh[row, column] = (sourceData.getSample(column, row, 0) and 0xFFFF) / 255.0f
            }
        }
        return exampleTerrainHigh
    }

    fun buildMask(size: Int): RcMatrix {
        val offset = 1.0f - (1.0f / size)
        val mask = RcMatrix(size, size)
        val radius = (size - 1) * 0.5f
        for (i in 0 until size) {
            for (j in 0 until size) {
                val x = (i - radius) / radius
                val y = (j - radius) / radius
                val maskValue = max(0.0f, 1 - offset * (x * x + y * y))
                mask[i, j] = maskValue * maskValue
            }
        }
        return mask
    }

    private fun dilateTerrain(terrain: RcMatrix, s: Int): RcMatrix {
        val t = RcMatrix(terrain.rows + s, terrain.columns + s)
        val radius = s / 2
        for (row in 0 until t.rows) {
            for (column in 0 until t.columns) {
                var r = row - radius
                var c = column - radius
                if (r < 0) {
                    r = 0
                } else if (r >= terrain.columns) {
                    r = terrain.columns - 1
                }
                if (c < 0) {
                    c = 0
                } else if (c >= terrain.rows) {
                    c = terrain.rows - 1
                }
                t[row, column] = terrain[r, c]
            }
        }
        return t
    }

    private fun dilateMask(mask: Matrix<Byte>, s: Int): Matrix<Byte> {
        val t = ByteArrayMatrix(mask.width + s)
        val radius = s / 2
        for (row in 0 until t.width) {
            for (column in 0 until t.width) {
                var r = row - radius
                var c = column - radius
                if (r < 0) {
                    r = 0
                } else if (r >= mask.width) {
                    r = mask.width - 1
                }
                if (c < 0) {
                    c = 0
                } else if (c >= mask.width) {
                    c = mask.width - 1
                }
                t[row, column] = mask[r, c]
            }
        }
        return t
    }

    private fun matTVecMaxAbs(a: RcMatrix, b: RcMatrix, row: Int, usefulIndices: IntArray): Pair<Int, Float> {
        val output = FloatArray(a.columns)
        val aArray = a.array
        val colRange = a.allColumns
        for (k in usefulIndices) {
            val kj = b[k, row]
            if (kj == 0.0f) {
                continue
            }
            val kOff = k * a.columns
            for (i in colRange) {
                output[i] += aArray[kOff + i] * kj
            }
        }
        var max = 0.0f
        var id = 0
        output.forEachIndexed { i, d ->
            if (d > max) {
                max = d
                id = i
            }
        }
        return id to max
    }

    fun matching(dictionary: RcMatrix, atoms: RcMatrix, usefulIndices: IntArray): Coefficients {
        val gamma = Coefficients(atoms.columns, dictionary.columns)
        val step = atoms.columns / 512
        val chunks = ArrayList<IntRange>()
        for (i in 0 until atoms.columns step step) {
            chunks.add(i..(i + step - 1).coerceAtMost(atoms.columns - 1))
        }

        chunks.parallelStream().forEach {
            for (signal in it) {
                val (pos, max) = matTVecMaxAbs(dictionary, atoms, signal, usefulIndices)
                if (max >= 0.0000001f) {
                    gamma[signal, pos] = max
                }
            }
        }
        return gamma
    }

    private fun matching(dictionaries: List<RcMatrix>, inputIndexMask: IntArray, atoms: RcMatrix, usefulIndices: IntArray): Coefficients {
        val gamma = Coefficients(atoms.columns, dictionaries.map { it.columns }.maxOrNull()!!)
        val step = atoms.columns / 512
        val chunks = ArrayList<IntRange>()
        for (i in 0 until atoms.columns step step) {
            chunks.add(i..(i + step - 1).coerceAtMost(atoms.columns - 1))
        }

        chunks.parallelStream().forEach {
            for (signal in it) {
                val (pos, max) = matTVecMaxAbs(dictionaries[inputIndexMask[signal]], atoms, signal, usefulIndices)
                if (max >= 0.0000001f) {
                    gamma[signal, pos] = max
                }
            }
        }
        return gamma
    }

    private fun Matrix<Byte>.max(rowRange: IntRange, columnRange: IntRange, cap: Int): Int {
        var max = 0
        var count = 0
        rowRange.forEach { row ->
            columnRange.forEach{ column ->
                val current = get(row, column).toInt()
                if (current in (max + 1)..(cap - 1)) {
                    max = current
                    count++
                }
            }
        }

        return if (count == 0) 0 else max
    }
}

class Coefficients(val rows: Int, val columns: Int) {

    private val kArray = IntArray(rows) { -1 }

    private val cArray = FloatArray(rows)

    val reverseIndex by lazy {
        val reverseIndex = List(columns) { ArrayList<Int>() }
        kArray.forEachIndexed { i, it ->
            reverseIndex[it].add(i)
        }
        reverseIndex
    }

    operator fun set(row: Int, column: Int, value: Float) {
        kArray[row] = column
        cArray[row] = value
    }

    operator fun get(row: Int, column: Int, other: RcMatrix): Float {
        val k = kArray[row]
        if (k == -1) {
            return 0.0f
        }
        return cArray[row] * other[k, column]
    }
}

class RcMatrix(val rows: Int, val columns: Int, array: FloatArray? = null, init: ((Int) -> Float)? = null){

    companion object {
        val ALL = -1..-1

        fun read(file: File) = DataInputStream(GZIPInputStream(file.inputStream().buffered()).buffered()).use { read(it) }

        fun read(input: DataInputStream): RcMatrix {
                val rows = input.readInt()
                val columns = input.readInt()
                return RcMatrix(rows, columns) {
                    input.readFloat()
                }
        }
    }

    val size = rows * columns

    val array = array ?: if (init != null) FloatArray(size, init) else FloatArray(size)

    val allRows = 0 until rows

    val allColumns = 0 until columns

    operator fun set(i: Int, value: Float) {
        array[i] = value
    }

    operator fun set(row: Int, column: Int, value: Float) = set(row * columns + column, value)

    operator fun set(rowRange: IntRange, columnRange: IntRange, values: RcMatrix) {
        (if (rowRange === ALL) allRows else rowRange).forEachIndexed { rowIndex, row ->
            (if (columnRange === ALL) allColumns else columnRange).forEachIndexed { columnIndex, column ->
                set(row, column, values[rowIndex, columnIndex])
            }
        }
    }

    operator fun set(rowRange: IntRange, column: Int, values: RcMatrix) = set(rowRange, column..column, values)

    operator fun set(row: Int, columnRange: IntRange, values: RcMatrix) = set(row..row, columnRange, values)

    operator fun get(i: Int) = array[i]

    operator fun get(row: Int, column: Int) = get(row * columns + column)

    operator fun get(rowRange: IntRange, columnRange: IntRange): RcMatrix {
        val actualRowRange = if (rowRange === ALL) allRows else rowRange
        val actualColumnRange = if (columnRange === ALL) allColumns else columnRange
        val output = RcMatrix(kotlin.math.max(0, actualRowRange.last - actualRowRange.first + 1), kotlin.math.max(0, actualColumnRange.last - actualColumnRange.first + 1))
        actualRowRange.forEachIndexed { rowIndex, row ->
            actualColumnRange.forEachIndexed { columnIndex, column ->
                output[rowIndex, columnIndex] = get(row, column)
            }
        }
        return output
    }

    operator fun get(rowRange: IntRange, columnRange: IntRange, alpha: Float): RcMatrix {
        val actualRowRange = if (rowRange === ALL) allRows else rowRange
        val actualColumnRange = if (columnRange === ALL) allColumns else columnRange
        val output = RcMatrix(kotlin.math.max(0, actualRowRange.last - actualRowRange.first + 1), kotlin.math.max(0, actualColumnRange.last - actualColumnRange.first + 1))
        actualRowRange.forEachIndexed { rowIndex, row ->
            actualColumnRange.forEachIndexed { columnIndex, column ->
                output[rowIndex, columnIndex] = get(row, column) * alpha
            }
        }
        return output
    }

    operator fun get(row: Int, columnRange: IntRange) = get(row..row, columnRange)

    operator fun get(rowRange: IntRange, column: Int) = get(rowRange, column..column)

    operator fun plus(other: RcMatrix): RcMatrix {
        val output = RcMatrix(rows, columns)
        for (i in 0 until size) {
            output[i] = get(i) + other[i]
        }
        return output
    }

    operator fun plus(scalar: Float): RcMatrix {
        val output = RcMatrix(rows, columns)
        for (i in 0 until size) {
            output[i] = get(i) + scalar
        }
        return output
    }

    operator fun minus(other: RcMatrix): RcMatrix {
        val output = RcMatrix(rows, columns)
        for (i in 0 until size) {
            output[i] = get(i) - other[i]
        }
        return output
    }

    operator fun minus(scalar: Float): RcMatrix {
        val output = RcMatrix(rows, columns)
        for (i in 0 until size) {
            output[i] = get(i) - scalar
        }
        return output
    }

    operator fun times(other: RcMatrix): RcMatrix {
        val output = RcMatrix(rows, columns)
        for (i in 0 until size) {
            output[i] = get(i) * other[i]
        }
        return output
    }

    operator fun times(scalar: Float): RcMatrix {
        val output = RcMatrix(rows, columns)
        for (i in 0 until size) {
            output[i] = get(i) * scalar
        }
        return output
    }

    operator fun div(other: RcMatrix): RcMatrix {
        val output = RcMatrix(rows, columns)
        for (i in 0 until size) {
            output[i] = get(i) / other[i]
        }
        return output
    }

    operator fun div(scalar: Float): RcMatrix {
        val output = RcMatrix(rows, columns)
        for (i in 0 until size) {
            output[i] = get(i) / scalar
        }
        return output
    }

    fun mean(rowRange: IntRange, columnRange: IntRange): Float {
        val actualRowRange = if (rowRange === ALL) allRows else rowRange
        val actualColumnRange = if (columnRange === ALL) allColumns else columnRange
        var sum = 0.0
        var count = 0
        actualRowRange.forEach { row ->
            actualColumnRange.forEach{ column ->
                sum += get(row, column)
                count++
            }
        }
        return if (count == 0) Float.NaN else (sum / count).toFloat()
    }

    fun sum(rowRange: IntRange, columnRange: IntRange): Float {
        val actualRowRange = if (rowRange === ALL) allRows else rowRange
        val actualColumnRange = if (columnRange === ALL) allColumns else columnRange
        var sum = 0.0
        actualRowRange.forEach { row ->
            actualColumnRange.forEach{ column ->
                sum += get(row, column)
            }
        }
        return sum.toFloat()
    }

    fun sum(row: Int, columnRange: IntRange): Float {
        val actualColumnRange = if (columnRange === ALL) allColumns else columnRange
        var sum = 0.0
        val offset = row * columns
        actualColumnRange.forEach{ column ->
            sum += get(offset + column)
        }
        return sum.toFloat()
    }

    fun sum(rowRange: IntRange, column: Int): Float {
        val actualRowRange = if (rowRange === ALL) allRows else rowRange
        var sum = 0.0
        actualRowRange.forEach { row ->
            sum += get(row, column)
        }
        return sum.toFloat()
    }

    fun mean(): Float = array.average().toFloat()

    fun min() = array.minOrNull() ?: Float.NaN

    fun max() = array.maxOrNull() ?: Float.NaN

    fun transpose(): RcMatrix {
        val output = RcMatrix(columns, rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                output[column, row] = get(row, column)
            }
        }
        return output
    }

    fun asyncTranspose(): RcMatrix {
        val output = RcMatrix(columns, rows)
        (0 until rows).toList().parallelStream().forEach { row ->
            (0 until columns).forEach { column ->
                output[column, row] = get(row, column)
            }
        }
        return output
    }

    fun norm(row: Int, columnRange: IntRange): Float {
        val actualColumnRange = if (columnRange === ALL) allColumns else columnRange
        var sum = 0.0
        for (column in actualColumnRange) {
            val value = get(row, column)
            sum += value * value
        }
        return sqrt(sum).toFloat()
    }

    fun norm(rowRange: IntRange, column: Int): Float {
        val actualRowRange = if (rowRange === ALL) allRows else rowRange
        var sum = 0.0
        for (row in actualRowRange) {
            val value = get(row, column)
            sum += value * value
        }
        return sqrt(sum).toFloat()
    }

    fun reshape(rows: Int, columns: Int): RcMatrix {
        val output = RcMatrix(rows, columns)
        for (row in 0 until rows) {
            val offset = row * columns
            for (column in 0 until columns) {
                val j = column * columns + row
                output[offset + column] = get(j % this.rows, j / this.rows)
            }
        }
        return output
    }

    fun matrixMultiply(other: RcMatrix): RcMatrix {
        if (columns != other.rows) {
            throw IllegalArgumentException("A.Columns: $columns did not match B.Rows ${other.rows}.")
        }
        val output = RcMatrix(rows, other.columns)
        for (i in 0 until rows) {
            for (k in 0 until columns) {
                val ik = get(i, k)
                if (ik == 0.0f) continue
                for (j in 0 until other.columns) {
                    output[i, j] = output[i, j] + ik * other[k, j]
                }
            }
        }
        return output
    }

    fun rotate90Clockwise() {
        if (rows != columns) {
            throw IllegalArgumentException("Rows must equal columns.")
        }
        val a = this
        for (i in 0 until rows / 2) {
            for (j in i until rows - i - 1) {
                val rowsM1 = rows - 1
                val rowsM1Mi = rowsM1 - i
                val rowsM1Mj = rowsM1 - j
                val temp = a[i, j]
                a[i, j] = a[rowsM1Mj, i]
                a[rowsM1Mj, i] = a[rowsM1Mi, rowsM1Mj]
                a[rowsM1Mi, rowsM1Mj] = a[j, rowsM1Mi]
                a[j, rowsM1Mi] = temp
            }
        }
    }

    fun asyncMatrixMultiply(other: RcMatrix): RcMatrix {
        if (columns != other.rows) {
            throw IllegalArgumentException("A.Columns: $columns did not match B.Rows ${other.rows}.")
        }
        val output = RcMatrix(rows, other.columns)
        (0 until rows).toList().parallelStream().forEach { i ->
            for (k in 0 until columns) {
                val ik = get(i, k)
                if (ik == 0.0f) continue
                for (j in 0 until other.columns) {
                    output[i, j] = output[i, j] + ik * other[k, j]
                }
            }
        }
        return output
    }

    fun write(file: File) = DataOutputStream(GZIPOutputStream(file.outputStream().buffered()).buffered()).use { write(it) }

    fun write(output: DataOutputStream) {
        output.writeInt(rows)
        output.writeInt(columns)
        array.forEach(output::writeFloat)
    }
}