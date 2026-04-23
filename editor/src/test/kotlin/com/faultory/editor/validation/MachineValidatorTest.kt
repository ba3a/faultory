package com.faultory.editor.validation

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.FaultyProductStrategy
import com.faultory.core.content.MachineSpec
import com.faultory.core.content.MachineType
import com.faultory.core.content.QaMachineProfile
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

class MachineValidatorTest {

    private lateinit var tempRoot: Path
    private lateinit var repository: AssetRepository

    @BeforeTest
    fun setUp() {
        tempRoot = copyFixturesToTempDir()
        repository = AssetRepository(tempRoot)
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `valid machine produces no issues`() {
        val issues = validate(AssetSelection.Machine("bench-assembler"))
        assertTrue(issues.isEmpty(), "expected no issues but got $issues")
    }

    @Test
    fun `unknown selection id produces no issues`() {
        val issues = validate(AssetSelection.Machine("does-not-exist"))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `duplicate id is an error`() {
        val original = repository.shopCatalog.machines.single()
        repository.shopCatalog = repository.shopCatalog.copy(
            machines = listOf(original, original.copy(displayName = "Second Bench")),
        )

        val issues = validate(AssetSelection.Machine(original.id))

        assertEquals(1, issues.size)
        assertEquals("id", issues.single().fieldName)
        assertTrue(issues.single().message.contains("Duplicate"))
    }

    @Test
    fun `negative installCost is an error`() {
        replaceSingleMachine { it.copy(installCost = -1) }
        val issues = validate(AssetSelection.Machine("bench-assembler"))
        assertTrue(issues.any { it.fieldName == "installCost" })
    }

    @Test
    fun `zero operationDurationSeconds is an error`() {
        replaceSingleMachine { it.copy(operationDurationSeconds = 0f) }
        val issues = validate(AssetSelection.Machine("bench-assembler"))
        assertTrue(issues.any { it.fieldName == "operationDurationSeconds" })
    }

    @Test
    fun `unresolved productIds are errors`() {
        replaceSingleMachine { it.copy(productIds = listOf("missing-product")) }
        val issues = validate(AssetSelection.Machine("bench-assembler"))
        assertTrue(issues.any { it.fieldName == "productIds[0]" })
    }

    @Test
    fun `unresolved minimumOperatorWorkerIds are errors`() {
        replaceSingleMachine { it.copy(minimumOperatorWorkerIds = listOf("missing-worker")) }
        val issues = validate(AssetSelection.Machine("bench-assembler"))
        assertTrue(issues.any { it.fieldName == "minimumOperatorWorkerIds[0]" })
    }

    @Test
    fun `PRODUCER without producerProfile is an error`() {
        replaceSingleMachine { it.copy(producerProfile = null) }
        val issues = validate(AssetSelection.Machine("bench-assembler"))
        assertTrue(issues.any { it.fieldName == "producerProfile" })
    }

    @Test
    fun `PRODUCER with qaProfile is an error`() {
        replaceSingleMachine {
            it.copy(
                qaProfile = QaMachineProfile(
                    inspectionDurationSeconds = 1f,
                    detectionAccuracy = 0.9f,
                    falsePositiveChance = 0.05f,
                    faultyProductStrategy = FaultyProductStrategy.DESTROY,
                ),
            )
        }
        val issues = validate(AssetSelection.Machine("bench-assembler"))
        assertTrue(issues.any { it.fieldName == "qaProfile" })
    }

    @Test
    fun `QA without qaProfile is an error`() {
        replaceSingleMachine {
            it.copy(type = MachineType.QA, producerProfile = null, qaProfile = null)
        }
        val issues = validate(AssetSelection.Machine("bench-assembler"))
        assertTrue(issues.any { it.fieldName == "qaProfile" })
    }

    @Test
    fun `QA with producerProfile is an error`() {
        replaceSingleMachine {
            it.copy(
                type = MachineType.QA,
                qaProfile = QaMachineProfile(
                    inspectionDurationSeconds = 1f,
                    detectionAccuracy = 0.9f,
                    falsePositiveChance = 0.05f,
                    faultyProductStrategy = FaultyProductStrategy.DESTROY,
                ),
            )
        }
        val issues = validate(AssetSelection.Machine("bench-assembler"))
        assertTrue(issues.any { it.fieldName == "producerProfile" })
    }

    @Test
    fun `unresolved upgrade ids are errors`() {
        replaceSingleMachine {
            it.copy(upgradeTree = BinaryUpgradeTree(leftUpgradeId = "missing-left", rightUpgradeId = "missing-right"))
        }
        val issues = validate(AssetSelection.Machine("bench-assembler"))
        val fieldNames = issues.map { it.fieldName }.toSet()
        assertTrue("upgradeTree.leftUpgradeId" in fieldNames)
        assertTrue("upgradeTree.rightUpgradeId" in fieldNames)
    }

    @Test
    fun `resolved upgrade id produces no upgrade issues`() {
        val original = repository.shopCatalog.machines.single()
        val upgraded = original.copy(id = "bench-assembler-mk2")
        repository.shopCatalog = repository.shopCatalog.copy(
            machines = listOf(
                original.copy(upgradeTree = BinaryUpgradeTree(leftUpgradeId = "bench-assembler-mk2")),
                upgraded,
            ),
        )

        val issues = validate(AssetSelection.Machine(original.id))

        assertTrue(issues.none { it.fieldName?.startsWith("upgradeTree") == true })
    }

    private fun validate(selection: AssetSelection.Machine): List<ValidationIssue> {
        return MachineValidator.validate(selection, ValidationContext(repository, selection))
    }

    private fun replaceSingleMachine(mutator: (MachineSpec) -> MachineSpec) {
        val machine = repository.shopCatalog.machines.single()
        repository.shopCatalog = repository.shopCatalog.copy(machines = listOf(mutator(machine)))
    }

    private fun fixtureRoot(): Path {
        val url = MachineValidatorTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("machine-validator-")
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
