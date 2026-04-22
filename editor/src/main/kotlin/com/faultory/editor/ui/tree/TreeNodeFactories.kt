package com.faultory.editor.ui.tree

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.LevelCatalog
import com.faultory.core.content.ShopCatalog

object TreeNodeFactories {
    fun buildItemsNode(catalog: ShopCatalog): AssetTreeNode {
        val itemsNode = AssetTreeNode(label = "Items", selection = null)
        itemsNode.add(buildProductsNode(catalog))
        itemsNode.add(buildWorkersNode(catalog))
        itemsNode.add(buildMachinesNode(catalog))
        return itemsNode
    }

    fun buildScenesNode(levelCatalog: LevelCatalog): AssetTreeNode {
        val scenesNode = AssetTreeNode(label = "Scenes", selection = null)
        levelCatalog.levels
            .sortedBy { it.id }
            .forEach { level ->
                val levelNode = AssetTreeNode(
                    label = "${level.id} — ${level.displayName}",
                    selection = AssetSelection.Level(level.id),
                )
                levelNode.add(
                    AssetTreeNode(
                        label = "Blueprint: ${level.shopAssetPath}",
                        selection = AssetSelection.Blueprint(level.shopAssetPath),
                    )
                )
                scenesNode.add(levelNode)
            }
        return scenesNode
    }

    private fun buildProductsNode(catalog: ShopCatalog): AssetTreeNode {
        val node = AssetTreeNode(label = "Products", selection = null)
        catalog.products
            .sortedBy { it.id }
            .forEach { product ->
                node.add(
                    AssetTreeNode(
                        label = product.id,
                        selection = AssetSelection.Product(product.id),
                    )
                )
            }
        return node
    }

    private fun buildWorkersNode(catalog: ShopCatalog): AssetTreeNode {
        val node = AssetTreeNode(label = "Workers", selection = null)
        catalog.workers
            .sortedBy { it.id }
            .forEach { worker ->
                val workerNode = AssetTreeNode(
                    label = worker.id,
                    selection = AssetSelection.Worker(worker.id),
                )
                addUpgradeLinks(workerNode, worker.upgradeTree) { upgradeId ->
                    AssetSelection.Worker(upgradeId)
                }
                node.add(workerNode)
            }
        return node
    }

    private fun buildMachinesNode(catalog: ShopCatalog): AssetTreeNode {
        val node = AssetTreeNode(label = "Machines", selection = null)
        catalog.machines
            .sortedBy { it.id }
            .forEach { machine ->
                val machineNode = AssetTreeNode(
                    label = machine.id,
                    selection = AssetSelection.Machine(machine.id),
                )
                addUpgradeLinks(machineNode, machine.upgradeTree) { upgradeId ->
                    AssetSelection.Machine(upgradeId)
                }
                node.add(machineNode)
            }
        return node
    }

    private fun addUpgradeLinks(
        parent: AssetTreeNode,
        upgradeTree: BinaryUpgradeTree?,
        toSelection: (String) -> AssetSelection,
    ) {
        if (upgradeTree == null) return
        upgradeTree.upgradeIds().forEach { upgradeId ->
            parent.add(
                AssetTreeNode(
                    label = "↪ $upgradeId",
                    selection = toSelection(upgradeId),
                    isLink = true,
                )
            )
        }
    }
}
