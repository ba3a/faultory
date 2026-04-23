package com.faultory.editor.validation

import com.faultory.core.content.LevelDefinition
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

class LevelValidatorTest {

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
    fun `valid level produces no issues`() {
        val issues = validate(AssetSelection.Level("tutorial-shop"))
        assertTrue(issues.isEmpty(), "expected no issues but got $issues")
    }

    @Test
    fun `unknown selection id produces no issues`() {
        val issues = validate(AssetSelection.Level("does-not-exist"))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `duplicate id is an error`() {
        val original = repository.levelCatalog.levels.single()
        repository.levelCatalog = repository.levelCatalog.copy(
            levels = listOf(original, original.copy(displayName = "Second Run")),
        )

        val issues = validate(AssetSelection.Level(original.id))

        assertEquals(1, issues.size)
        assertEquals("id", issues.single().fieldName)
        assertTrue(issues.single().message.contains("Duplicate"))
    }

    @Test
    fun `unresolved worker ids are errors`() {
        replaceSingleLevel { it.copy(availableWorkerIds = listOf("missing-worker")) }
        val issues = validate(AssetSelection.Level("tutorial-shop"))
        assertTrue(issues.any { it.fieldName == "availableWorkerIds[0]" })
    }

    @Test
    fun `unresolved machine ids are errors`() {
        replaceSingleLevel { it.copy(availableMachineIds = listOf("missing-machine")) }
        val issues = validate(AssetSelection.Level("tutorial-shop"))
        assertTrue(issues.any { it.fieldName == "availableMachineIds[0]" })
    }

    @Test
    fun `missing shopAssetPath is an error`() {
        replaceSingleLevel { it.copy(shopAssetPath = "shops/missing.json") }
        val issues = validate(AssetSelection.Level("tutorial-shop"))
        assertTrue(issues.any { it.fieldName == "shopAssetPath" })
    }

    @Test
    fun `shopAssetPath pointing to a directory is an error`() {
        replaceSingleLevel { it.copy(shopAssetPath = "shops") }
        val issues = validate(AssetSelection.Level("tutorial-shop"))
        assertTrue(issues.any { it.fieldName == "shopAssetPath" })
    }

    private fun validate(selection: AssetSelection.Level): List<ValidationIssue> {
        return LevelValidator.validate(selection, ValidationContext(repository, selection))
    }

    private fun replaceSingleLevel(mutator: (LevelDefinition) -> LevelDefinition) {
        val level = repository.levelCatalog.levels.single()
        repository.levelCatalog = repository.levelCatalog.copy(levels = listOf(mutator(level)))
    }

    private fun fixtureRoot(): Path {
        val url = LevelValidatorTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("level-validator-")
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
