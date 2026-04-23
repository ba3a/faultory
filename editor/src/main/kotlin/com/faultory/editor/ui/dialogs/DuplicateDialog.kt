package com.faultory.editor.ui.dialogs

import com.badlogic.gdx.scenes.scene2d.Stage
import com.faultory.editor.model.DuplicateResult
import com.faultory.editor.model.Duplicator
import com.faultory.editor.ui.tree.AssetSelection

object DuplicateDialog {
    fun open(
        stage: Stage,
        selection: AssetSelection,
        duplicator: Duplicator,
        onSuccess: (AssetSelection) -> Unit,
    ) {
        val (title, prompt, initial) = labelsFor(selection)
        NewIdDialog(
            title = title,
            prompt = prompt,
            initialValue = initial,
            onConfirm = { newId ->
                when (val result = duplicator.duplicate(selection, newId)) {
                    is DuplicateResult.Success -> onSuccess(result.newSelection)
                    is DuplicateResult.Collision ->
                        ConfirmDialog.info(stage, "Duplicate failed", result.message)
                    is DuplicateResult.NotFound ->
                        ConfirmDialog.info(stage, "Duplicate failed", result.message)
                    is DuplicateResult.InvalidId ->
                        ConfirmDialog.info(stage, "Duplicate failed", result.message)
                }
            },
        ).showOn(stage)
    }

    private fun labelsFor(selection: AssetSelection): Triple<String, String, String> {
        return when (selection) {
            is AssetSelection.Product -> Triple(
                "Duplicate Product",
                "New product id:",
                suggest(selection.id),
            )
            is AssetSelection.Worker -> Triple(
                "Duplicate Worker",
                "New worker id:",
                suggest(selection.id),
            )
            is AssetSelection.Machine -> Triple(
                "Duplicate Machine",
                "New machine id:",
                suggest(selection.id),
            )
            is AssetSelection.Level -> Triple(
                "Duplicate Level",
                "New level id:",
                suggest(selection.id),
            )
            is AssetSelection.Blueprint -> {
                val baseName = selection.shopAssetPath.substringAfterLast('/').substringBeforeLast('.')
                Triple(
                    "Duplicate Blueprint",
                    "New blueprint id:",
                    suggest(baseName),
                )
            }
        }
    }

    private fun suggest(id: String): String = if (id.isBlank()) "copy" else "$id-copy"
}
