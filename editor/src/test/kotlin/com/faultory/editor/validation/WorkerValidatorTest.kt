package com.faultory.editor.validation

import com.faultory.core.content.BinaryUpgradeTree
import com.faultory.core.content.WorkerProfile
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

class WorkerValidatorTest {

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
    fun `valid worker produces no issues`() {
        val issues = validate(AssetSelection.Worker("line-inspector"))
        assertTrue(issues.isEmpty(), "expected no issues but got $issues")
    }

    @Test
    fun `unknown selection id produces no issues`() {
        val issues = validate(AssetSelection.Worker("does-not-exist"))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `blank id is an error`() {
        replaceSingleWorker { it.copy(id = "") }
        val issues = validate(AssetSelection.Worker(""))
        assertTrue(issues.any { it.fieldName == "id" && it.severity == Severity.ERROR })
    }

    @Test
    fun `duplicate id is an error`() {
        val original = repository.shopCatalog.workers.single()
        repository.shopCatalog = repository.shopCatalog.copy(
            workers = listOf(original, original.copy(displayName = "Second Inspector")),
        )

        val issues = validate(AssetSelection.Worker(original.id))

        assertEquals(1, issues.size)
        assertEquals("id", issues.single().fieldName)
        assertTrue(issues.single().message.contains("Duplicate"))
    }

    @Test
    fun `negative hireCost is an error`() {
        replaceSingleWorker { it.copy(hireCost = -1) }
        val issues = validate(AssetSelection.Worker("line-inspector"))
        assertTrue(issues.any { it.fieldName == "hireCost" })
    }

    @Test
    fun `zero walkSpeed is an error`() {
        replaceSingleWorker { it.copy(walkSpeed = 0f) }
        val issues = validate(AssetSelection.Worker("line-inspector"))
        assertTrue(issues.any { it.fieldName == "walkSpeed" })
    }

    @Test
    fun `negative walkSpeed is an error`() {
        replaceSingleWorker { it.copy(walkSpeed = -1f) }
        val issues = validate(AssetSelection.Worker("line-inspector"))
        assertTrue(issues.any { it.fieldName == "walkSpeed" })
    }

    @Test
    fun `role profile probabilities out of range are errors`() {
        replaceSingleWorker { worker ->
            worker.copy(
                roleProfiles = worker.roleProfiles.map { profile ->
                    profile.copy(
                        defectChance = 1.5f,
                        sabotageChance = -0.1f,
                        detectionAccuracy = 2f,
                        falsePositiveChance = 1.1f,
                    )
                },
            )
        }

        val issues = validate(AssetSelection.Worker("line-inspector"))

        val fieldNames = issues.map { it.fieldName }.toSet()
        assertTrue("roleProfiles[0].defectChance" in fieldNames)
        assertTrue("roleProfiles[0].sabotageChance" in fieldNames)
        assertTrue("roleProfiles[0].detectionAccuracy" in fieldNames)
        assertTrue("roleProfiles[0].falsePositiveChance" in fieldNames)
    }

    @Test
    fun `role profile boundary values are accepted`() {
        replaceSingleWorker { worker ->
            worker.copy(
                roleProfiles = worker.roleProfiles.map { profile ->
                    profile.copy(
                        defectChance = 0f,
                        sabotageChance = 1f,
                        detectionAccuracy = 0f,
                        falsePositiveChance = 1f,
                    )
                },
            )
        }

        val issues = validate(AssetSelection.Worker("line-inspector"))

        assertTrue(
            issues.none { it.fieldName?.startsWith("roleProfiles") == true },
            "unexpected role-profile issues: $issues",
        )
    }

    @Test
    fun `unresolved upgrade ids are errors`() {
        replaceSingleWorker {
            it.copy(upgradeTree = BinaryUpgradeTree(leftUpgradeId = "missing-left", rightUpgradeId = "missing-right"))
        }

        val issues = validate(AssetSelection.Worker("line-inspector"))

        val fieldNames = issues.map { it.fieldName }.toSet()
        assertTrue("upgradeTree.leftUpgradeId" in fieldNames)
        assertTrue("upgradeTree.rightUpgradeId" in fieldNames)
    }

    @Test
    fun `resolved upgrade ids produce no issues`() {
        val original = repository.shopCatalog.workers.single()
        val upgraded = original.copy(id = "senior-inspector")
        repository.shopCatalog = repository.shopCatalog.copy(
            workers = listOf(
                original.copy(upgradeTree = BinaryUpgradeTree(leftUpgradeId = "senior-inspector")),
                upgraded,
            ),
        )

        val issues = validate(AssetSelection.Worker(original.id))

        assertTrue(
            issues.none { it.fieldName?.startsWith("upgradeTree") == true },
            "unexpected upgrade issues: $issues",
        )
    }

    private fun validate(selection: AssetSelection.Worker): List<ValidationIssue> {
        return WorkerValidator.validate(selection, ValidationContext(repository, selection))
    }

    private fun replaceSingleWorker(mutator: (WorkerProfile) -> WorkerProfile) {
        val worker = repository.shopCatalog.workers.single()
        repository.shopCatalog = repository.shopCatalog.copy(workers = listOf(mutator(worker)))
    }

    private fun fixtureRoot(): Path {
        val url = WorkerValidatorTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("worker-validator-")
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
