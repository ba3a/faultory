package com.faultory.core.graphics

object SkinActions {
    const val IDLE = "idle"
    const val WALK = "walk"
    const val WORKING = "working"
    const val INSPECT = "inspect"
    /** A machine that cannot start the next item because its output queue is full. */
    const val BLOCKED = "blocked"
    const val BELT_ENTER = "belt_enter"
    const val BELT_RIDE = "belt_ride"
    const val BELT_EXIT = "belt_exit"
    /** Alert locomotion: a guard closing on a saboteur rather than walking its beat. */
    const val PURSUE = "pursue"

    /** The involuntary phases a unit plays out after slipping, and the deliberate destroy pose. */
    const val FALL = "fall"
    const val LIE = "lie"
    const val STAND_UP = "stand_up"
    const val DESTROY = "destroy"
}
