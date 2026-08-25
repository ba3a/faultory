package com.faultory.core.config

/**
 * Runtime-toggleable development switches. Unlike [GameConfig] these are mutable, so they
 * live apart from it rather than turning that object's compile-time constants into vars.
 */
object DebugFlags {
    /** Forces every sprite layer to stand down so entities render as ShapeRenderer primitives. */
    var forceShapeRendering: Boolean =
        System.getProperty(FORCE_SHAPE_RENDERING_PROPERTY)?.toBooleanStrictOrNull() ?: false

    const val FORCE_SHAPE_RENDERING_PROPERTY = "faultory.debug.shapes"
}
