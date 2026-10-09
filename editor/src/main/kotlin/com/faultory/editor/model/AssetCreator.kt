package com.faultory.editor.model

import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection

sealed class CreateResult {
    data class Success(val newSelection: AssetSelection) : CreateResult()
    data class Collision(val message: String) : CreateResult()
    data class InvalidId(val message: String) : CreateResult()
}

/**
 * Starts a new scenery entry from scratch - the one way to add the first terrain, tile, wall piece
 * or style to an empty catalog, which Duplicate cannot do. The entry starts from
 * [SceneryCollection.blank]; a tile or wall piece gets a skin named after it, so the artist's next
 * step is simply to upload art into its grid.
 */
class AssetCreator(
    private val repository: AssetRepository,
    private val onMutated: () -> Unit = {},
) {
    fun create(kind: SceneryKind, id: String): CreateResult {
        if (id.isBlank()) return CreateResult.InvalidId("New id must not be blank")
        val collection = kind.collection
        if (collection.contains(repository, id)) {
            return CreateResult.Collision("${kind.label.replaceFirstChar(Char::uppercase)} id '$id' already exists")
        }
        add(collection, id)
        onMutated()
        return CreateResult.Success(AssetSelection.Scenery(kind, id))
    }

    private fun <T : Any> add(collection: SceneryCollection<T>, id: String) {
        collection.add(repository, collection.blank(repository, id))
    }
}
