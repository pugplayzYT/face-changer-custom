package com.pugplayz.facechanger

enum class TrackingMode { FACE, HAND, BODY }
enum class DetailLevel { LOW, MEDIUM, HIGH }

data class Point3(val x: Float, val y: Float, val z: Float = 0f)
data class TrackingFrame(
    val mode: TrackingMode,
    val groups: List<List<Point3>>,
    val timestampMs: Long
)

data class FilterApp(
    val id: String,
    val name: String,
    val description: String,
    val mode: TrackingMode,
    val detail: DetailLevel,
    val code: String,
    val builtIn: Boolean = false
)

data class ScriptInput(
    val name: String,
    val label: String,
    val type: InputType,
    val defaultValue: String,
    val min: Double? = null,
    val max: Double? = null
)

enum class InputType { NUMBER, TEXT }
