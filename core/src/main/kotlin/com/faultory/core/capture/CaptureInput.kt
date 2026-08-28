package com.faultory.core.capture

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.faultory.core.screens.shopfloor.ChromeElement
import com.faultory.core.shop.systems.ChanceKind

/**
 * The capture-mode hotkeys, installed ahead of the game's own input processor so they work
 * regardless of what is selected or hovered. See the "Capture mode" section of CLAUDE.md for the
 * full keymap.
 */
class CaptureInput(
    private val session: CaptureSession,
    private val chanceOracle: ScriptedChanceOracle
) : InputAdapter() {
    override fun keyDown(keycode: Int): Boolean {
        val cuedKind = CUE_KEYS[keycode]
        if (cuedKind != null) {
            applyChanceKey(cuedKind)
            return true
        }
        return when (keycode) {
            Input.Keys.F1 -> {
                session.cyclePreset()
                true
            }

            Input.Keys.F2 -> {
                session.toggle(ChromeElement.DEBUG_OVERLAY)
                true
            }

            Input.Keys.F3 -> {
                session.toggle(ChromeElement.GRID_LINES)
                true
            }

            Input.Keys.F4 -> {
                session.toggle(ChromeElement.HUD_BAND)
                session.toggle(ChromeElement.BANK_PANEL)
                true
            }

            Input.Keys.F10 -> {
                session.isRecording = !session.isRecording
                true
            }

            Input.Keys.NUM_0 -> clearAllIfControlHeld()
            else -> false
        }
    }

    private fun clearAllIfControlHeld(): Boolean {
        if (!isControlHeld()) return false
        chanceOracle.clearAll()
        return true
    }

    private fun applyChanceKey(kind: ChanceKind) {
        when {
            isControlHeld() -> chanceOracle.forceStanding(kind, outcome = true)
            isShiftHeld() -> chanceOracle.cueNext(kind, outcome = false)
            else -> chanceOracle.cueNext(kind, outcome = true)
        }
    }

    private fun isControlHeld(): Boolean =
        Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)

    private fun isShiftHeld(): Boolean =
        Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)

    private companion object {
        private val CUE_KEYS: Map<Int, ChanceKind> = mapOf(
            Input.Keys.NUM_1 to ChanceKind.SABOTAGE,
            Input.Keys.NUM_2 to ChanceKind.PRODUCTION_DEFECT,
            Input.Keys.NUM_3 to ChanceKind.QA_DETECTION,
            Input.Keys.NUM_4 to ChanceKind.QA_FALSE_POSITIVE,
            Input.Keys.NUM_5 to ChanceKind.WORKER_SLIP,
            Input.Keys.NUM_6 to ChanceKind.CLEANER_SPAWN
        )
    }
}
