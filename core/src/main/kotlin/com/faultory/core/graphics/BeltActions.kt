package com.faultory.core.graphics

import com.faultory.core.shop.BeltTileShape

object BeltActions {
    const val START = "start"
    const val STRAIGHT = "straight"
    const val TURN_CW = "turn_cw"
    const val TURN_CCW = "turn_ccw"
    const val END = "end"

    fun actionFor(shape: BeltTileShape): String = when (shape) {
        BeltTileShape.START -> START
        BeltTileShape.STRAIGHT -> STRAIGHT
        BeltTileShape.TURN_CW -> TURN_CW
        BeltTileShape.TURN_CCW -> TURN_CCW
        BeltTileShape.END -> END
    }
}
