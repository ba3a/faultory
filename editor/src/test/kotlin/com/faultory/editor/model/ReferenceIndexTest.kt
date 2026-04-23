package com.faultory.editor.model

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.WorkerRole
import com.faultory.editor.repository.AssetRepository
import com.faultory.editor.ui.tree.AssetSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferenceIndexTest {

    private lateinit var tempRoot: Path
    private lateinit var repository: AssetRepository
    private lateinit var index: ReferenceIndex

    @BeforeTest
    fun setUp() {
        tempRoot = copyFixturesToTempDir()
        repository = AssetRepository(tempRoot)
        index = ReferenceIndex(repository)
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `product referenced from machine producerProfile and worker acceptedProductIds`() {
        val worker = repository.shopCatalog.workers.single()
        val newProfiles = worker.roleProfiles.map { profile ->
            if (profile.role == WorkerRole.PRODUCER_OPERATOR) {
                profile.copy(acceptedProductIds = listOf("ceramic-mug"))
            } else profile
        }
        repository.shopCatalog = repository.shopCatalog.copy(
            workers = listOf(worker.copy(roleProfiles = newProfiles)),
        )

        val refs = index.findReferencesTo(AssetSelection.Product("ceramic-mug"))

        val sources = refs.map { it.source }
        assertTrue(AssetSelection.Machine("bench-assembler") in sources)
        assertTrue(AssetSelection.Worker("line-inspector") in sources)
    }

    @Test
    fun `product in machine productIds is reported`() {
        val machine = repository.shopCatalog.machines.single()
        repository.shopCatalog = repository.shopCatalog.copy(
            machines = listOf(machine.copy(productIds = listOf("ceramic-mug"))),
        )

        val refs = index.findReferencesTo(AssetSelection.Product("ceramic-mug"))

        assertTrue(refs.any { it.field == "productIds[0]" && it.source == AssetSelection.Machine("bench-assembler") })
    }

    @Test
    fun `worker is referenced from machine operator list and level`() {
        val refs = index.findReferencesTo(AssetSelection.Worker("line-inspector"))

        val sources = refs.map { it.source }
        assertTrue(AssetSelection.Machine("bench-assembler") in sources)
        assertTrue(AssetSelection.Level("tutorial-shop") in sources)
    }

    @Test
    fun `worker upgrade tree reference is reported`() {
        val worker = repository.shopCatalog.workers.single()
        val advanced = worker.copy(id = "senior-inspector")
        repository.shopCatalog = repository.shopCatalog.copy(
            workers = listOf(
                worker.copy(upgradeTree = BinaryUpgradeTree(leftUpgradeId = "senior-inspector")),
                advanced,
            ),
        )

        val refs = index.findReferencesTo(AssetSelection.Worker("senior-inspector"))

        assertTrue(refs.any {
            it.source == AssetSelection.Worker("line-inspector") &&
                it.field == "upgradeTree.leftUpgradeId"
        })
    }

    @Test
    fun `machine is referenced from level availableMachineIds and blueprint slot`() {
        val machine = repository.shopCatalog.machines.single()
        val (path, blueprint) = repository.blueprints.entries.single()
        repository.blueprints[path] = blueprint.copy(
            machineSlots = listOf(
                com.faultory.core.shop.MachineSlot(
                    id = "slot-1",
                    type = machine.type,
                    installedMachineId = machine.id,
                    x = 0f,
                    y = 0f,
                ),
            ),
        )

        val refs = index.findReferencesTo(AssetSelection.Machine(machine.id))

        val sources = refs.map { it.source }
        assertTrue(AssetSelection.Level("tutorial-shop") in sources)
        assertTrue(AssetSelection.Blueprint(path) in sources)
    }

    @Test
    fun `machine upgrade tree reference is reported`() {
        val machine = repository.shopCatalog.machines.single()
        val mk2 = machine.copy(id = "bench-assembler-mk2")
        repository.shopCatalog = repository.shopCatalog.copy(
            machines = listOf(
                machine.copy(upgradeTree = BinaryUpgradeTree(rightUpgradeId = "bench-assembler-mk2")),
                mk2,
            ),
        )

        val refs = index.findReferencesTo(AssetSelection.Machine("bench-assembler-mk2"))

        assertTrue(refs.any {
            it.source == AssetSelection.Machine("bench-assembler") &&
                it.field == "upgradeTree.rightUpgradeId"
        })
    }

    @Test
    fun `level recommendedNextLevelId is reported`() {
        val level = repository.levelCatalog.levels.single()
        val follow = level.copy(id = "follow-up", recommendedNextLevelId = null)
        repository.levelCatalog = repository.levelCatalog.copy(
            levels = listOf(level.copy(recommendedNextLevelId = "follow-up"), follow),
        )

        val refs = index.findReferencesTo(AssetSelection.Level("follow-up"))

        assertTrue(refs.any {
            it.source == AssetSelection.Level("tutorial-shop") &&
                it.field == "recommendedNextLevelId"
        })
    }

    @Test
    fun `blueprint is referenced from level shopAssetPath`() {
        val (path, _) = repository.blueprints.entries.single()

        val refs = index.findReferencesTo(AssetSelection.Blueprint(path))

        assertTrue(refs.any {
            it.source == AssetSelection.Level("tutorial-shop") && it.field == "shopAssetPath"
        })
    }

    @Test
    fun `returns empty list when target is unused`() {
        val refs = index.findReferencesTo(AssetSelection.Product("nonexistent-product"))
        assertEquals(emptyList(), refs)
    }

    private fun fixtureRoot(): Path {
        val url = ReferenceIndexTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("reference-index-")
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val rel = source.relativize(src)
                val target = dest.resolve(rel.toString())
                if (Files.isDirectory(src)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return dest
    }
}
