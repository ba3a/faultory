package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Rectangle
import com.faultory.core.config.GameConfig
import com.faultory.core.i18n.Messages
import com.faultory.core.i18n.UiMessageKey

enum class CompletionAction {
    REPLAY_LEVEL,
    NEXT_LEVEL,
    BACK_TO_LEVEL_SELECTION
}

data class CompletionButton(
    val action: CompletionAction,
    val label: String,
    val bounds: Rectangle
)

object CompletionModalLayout {
    fun bounds(): Rectangle {
        return Rectangle(
            (GameConfig.virtualWidth - GameConfig.completionModalWidth) / 2f,
            (GameConfig.virtualHeight - GameConfig.completionModalHeight) / 2f,
            GameConfig.completionModalWidth,
            GameConfig.completionModalHeight
        )
    }

    fun buttons(hasNextLevel: Boolean): List<CompletionButton> {
        val bounds = bounds()
        val actions = buildList {
            add(CompletionAction.REPLAY_LEVEL)
            if (hasNextLevel) {
                add(CompletionAction.NEXT_LEVEL)
            }
            add(CompletionAction.BACK_TO_LEVEL_SELECTION)
        }
        val totalWidth = actions.size * GameConfig.completionModalButtonWidth +
            (actions.size - 1) * GameConfig.completionModalButtonGap
        val startX = bounds.x + (bounds.width - totalWidth) / 2f
        val y = bounds.y + GameConfig.completionModalPadding
        return actions.mapIndexed { index, action ->
            CompletionButton(
                action = action,
                label = when (action) {
                    CompletionAction.REPLAY_LEVEL -> Messages.text(UiMessageKey.COMPLETION_REPLAY)
                    CompletionAction.NEXT_LEVEL -> Messages.text(UiMessageKey.COMPLETION_NEXT)
                    CompletionAction.BACK_TO_LEVEL_SELECTION -> Messages.text(UiMessageKey.COMPLETION_BACK)
                },
                bounds = Rectangle(
                    startX + index * (
                        GameConfig.completionModalButtonWidth +
                            GameConfig.completionModalButtonGap
                        ),
                    y,
                    GameConfig.completionModalButtonWidth,
                    GameConfig.completionModalButtonHeight
                )
            )
        }
    }
}
