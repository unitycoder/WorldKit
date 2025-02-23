import wk.api.*

var projectPath: String by ref()

val mapScale by cRef(MapScale.MapScale20K)
val baseNoiseSize by cRef(1024)
val maskWidth by cRef(7168)
val outputWidth by cRef(8192)

val ioPath by dir(::projectPath) { "$projectPath/io" }
val landShapesPath by dir(::ioPath) { "$ioPath/land-shapes" }
val basinShapesPath by dir(::landShapesPath) { "$landShapesPath/basin" }
val lakeShapesPath by dir(::landShapesPath) { "$landShapesPath/lake" }
val areaShapesPath by dir(::ioPath) { "$ioPath/area-shapes" }
val terrainProfilesPath by dir(::ioPath) { "$ioPath/terrain-profiles" }
val inputsPath by dir(::terrainProfilesPath) { "$ioPath/inputs" }
