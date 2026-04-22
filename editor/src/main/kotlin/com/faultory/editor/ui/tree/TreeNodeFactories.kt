package com.faultory.editor.ui.tree

import com.faultory.core.content.ShopCatalog

object TreeNodeFactories {
    fun buildItemsNode(catalog: ShopCatalog): AssetTreeNode {
        val itemsNode = AssetTreeNode(label = "Items", selection = null)
        itemsNode.add(buildProductsNode(catalog))
        itemsNode.add(buildWorkersNode(catalog))
        itemsNode.add(buildMachinesNode(catalog))
        return itemsNode
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
                node.add(
                    AssetTreeNode(
                        label = worker.id,
                        selection = AssetSelection.Worker(worker.id),
                    )
                )
            }
        return node
    }

    private fun buildMachinesNode(catalog: ShopCatalog): AssetTreeNode {
        val node = AssetTreeNode(label = "Machines", selection = null)
        catalog.machines
            .sortedBy { it.id }
            .forEach { machine ->
                node.add(
                    AssetTreeNode(
                        label = machine.id,
                        selection = AssetSelection.Machine(machine.id),
                    )
                )
            }
        return node
    }
}
