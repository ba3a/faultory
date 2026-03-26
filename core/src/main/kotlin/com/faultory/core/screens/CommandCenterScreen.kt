package com.faultory.core.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import com.faultory.core.FaultoryGame
import com.faultory.core.config.GameConfig
import com.faultory.core.content.TowerCatalog
import com.faultory.core.save.GameSave
import com.faultory.core.systems.WaveDirector
import com.faultory.core.world.PathNode
import com.faultory.core.world.TowerDefenseWorld

class CommandCenterScreen(
    private val game: FaultoryGame,
    private val world: TowerDefenseWorld,
    private val saveSnapshot: GameSave,
    private val towerCatalog: TowerCatalog
) : ScreenAdapter() {
    private val viewport = FitViewport(GameConfig.virtualWidth, GameConfig.virtualHeight)
    private val waveDirector = WaveDirector()

    override fun show() {
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
    }

    override fun render(delta: Float) {
        world.update(delta)
        waveDirector.update(delta)

        ScreenUtils.clear(0.06f, 0.07f, 0.09f, 1f)
        viewport.apply()
        viewport.camera.update()

        val renderer = game.shapeRenderer
        renderer.projectionMatrix = viewport.camera.combined

        drawFilledLayer(renderer)
        drawLineLayer(renderer)
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    override fun dispose() {
        world.dispose()
    }

    private fun drawFilledLayer(renderer: ShapeRenderer) {
        val unlockedCount = saveSnapshot.player.unlockedTowerIds.size.coerceAtLeast(1)
        val towerSwatchWidth = 12f + (towerCatalog.towers.size * 6f)

        renderer.begin(ShapeRenderer.ShapeType.Filled)
        renderer.color = Color(0.11f, 0.16f, 0.18f, 1f)
        renderer.rect(0f, 0f, GameConfig.virtualWidth, GameConfig.virtualHeight)

        renderer.color = Color(0.14f, 0.19f, 0.22f, 1f)
        renderer.rect(32f, GameConfig.virtualHeight - 112f, 420f, 72f)

        renderer.color = if (waveDirector.isFirstWaveQueued) {
            Color(0.88f, 0.36f, 0.21f, 1f)
        } else {
            Color(0.40f, 0.47f, 0.52f, 1f)
        }
        renderer.rect(48f, GameConfig.virtualHeight - 96f, 168f, 40f)

        renderer.color = Color(0.78f, 0.74f, 0.38f, 1f)
        repeat(unlockedCount) { index ->
            renderer.rect(
                240f + index * (towerSwatchWidth + 8f),
                GameConfig.virtualHeight - 96f,
                towerSwatchWidth,
                40f
            )
        }

        renderer.color = Color(0.23f, 0.63f, 0.56f, 1f)
        for (pad in world.blueprint.towerPads) {
            renderer.circle(pad.x, pad.y, pad.size)
        }
        renderer.end()
    }

    private fun drawLineLayer(renderer: ShapeRenderer) {
        renderer.begin(ShapeRenderer.ShapeType.Line)
        renderer.color = Color(0.90f, 0.74f, 0.29f, 1f)
        drawPath(renderer)

        renderer.color = Color(0.33f, 0.44f, 0.47f, 1f)
        for (pad in world.blueprint.towerPads) {
            renderer.circle(pad.x, pad.y, pad.size + 6f)
        }
        renderer.end()
    }

    private fun drawPath(renderer: ShapeRenderer) {
        val nodes = world.blueprint.pathNodes
        for (index in 0 until nodes.lastIndex) {
            val current = nodes[index]
            val next = nodes[index + 1]
            renderer.line(current.x, current.y, next.x, next.y)
        }

        for (node in nodes) {
            drawNode(renderer, node)
        }
    }

    private fun drawNode(renderer: ShapeRenderer, node: PathNode) {
        renderer.circle(node.x, node.y, 12f)
    }
}
