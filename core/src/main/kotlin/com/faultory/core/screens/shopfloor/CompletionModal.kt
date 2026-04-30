package com.faultory.core.screens.shopfloor

import com.badlogic.gdx.math.Rectangle
import com.faultory.core.i18n.MessageKey
import com.faultory.core.i18n.Messages

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
    val bounds: Rectangle = Rectangle(300f, 180f, 1000f, 480f)

    fun buttons(hasNextLevel: Boolean): List<CompletionButton> {
        val actions = buildList {
            add(CompletionAction.REPLAY_LEVEL)
            if (hasNextLevel) {
                add(CompletionAction.NEXT_LEVEL)
            }
            add(CompletionAction.BACK_TO_LEVEL_SELECTION)
        }
        val buttonWidth = 240f
        val buttonHeight = 52f
        val gap = 24f
        val totalWidth = actions.size * buttonWidth + (actions.size - 1) * gap
        val startX = bounds.x + (bounds.width - totalWidth) / 2f
        val y = bounds.y + 32f
        return actions.mapIndexed { index, action ->
            CompletionButton(
                action = action,
                label = when (action) {
                    CompletionAction.REPLAY_LEVEL -> Messages.text(MessageKey.COMPLETION_REPLAY)
                    CompletionAction.NEXT_LEVEL -> Messages.text(MessageKey.COMPLETION_NEXT)
                    CompletionAction.BACK_TO_LEVEL_SELECTION -> Messages.text(MessageKey.COMPLETION_BACK)
                },
                bounds = Rectangle(
                    startX + index * (buttonWidth + gap),
                    y,
                    buttonWidth,
                    buttonHeight
                )
            )
        }
    }
}
