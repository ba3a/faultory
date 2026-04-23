package com.faultory.editor.validation

import com.faultory.core.content.MachineType
import com.faultory.core.shop.BeltNode
import com.faultory.core.shop.ConveyorBelt
import com.faultory.core.shop.MachineSlot
import com.faultory.core.shop.ShopBlueprint
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
import kotlin.test.assertTrue

class BlueprintValidatorTest {

    private lateinit var tempRoot: Path
    private lateinit var repository: AssetRepository
    private val blueprintKey = "shops/tutorial.json"

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
    fun `valid blueprint produces no issues`() {
        val issues = validate(AssetSelection.Blueprint(blueprintKey))
        assertTrue(issues.isEmpty(), "expected no issues but got $issues")
    }

    @Test
    fun `unknown blueprint path produces no issues`() {
        val issues = validate(AssetSelection.Blueprint("shops/does-not-exist.json"))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `duplicate machine slot positions are errors`() {
        mutateBlueprint {
            it.copy(
                machineSlots = listOf(
                    MachineSlot(id = "a", type = MachineType.PRODUCER, x = 100f, y = 200f),
                    MachineSlot(id = "b", type = MachineType.PRODUCER, x = 100f, y = 200f),
                ),
            )
        }

        val issues = validate(AssetSelection.Blueprint(blueprintKey))

        assertTrue(issues.any { it.fieldName == "machineSlots[1]" })
    }

    @Test
    fun `distinct machine slot positions are accepted`() {
        mutateBlueprint {
            it.copy(
                machineSlots = listOf(
                    MachineSlot(id = "a", type = MachineType.PRODUCER, x = 100f, y = 200f),
                    MachineSlot(id = "b", type = MachineType.PRODUCER, x = 100f, y = 300f),
                ),
            )
        }

        val issues = validate(AssetSelection.Blueprint(blueprintKey))

        assertTrue(issues.none { it.fieldName?.startsWith("machineSlots") == true })
    }

    @Test
    fun `belt with fewer than two checkpoints is an error`() {
        mutateBlueprint {
            it.copy(conveyorBelts = listOf(ConveyorBelt(id = "stub", checkpoints = listOf(BeltNode(0f, 0f)))))
        }

        val issues = validate(AssetSelection.Blueprint(blueprintKey))

        assertTrue(issues.any { it.fieldName == "conveyorBelts[0].checkpoints" })
    }

    @Test
    fun `belt with zero-length segment is an error`() {
        mutateBlueprint {
            it.copy(
                conveyorBelts = listOf(
                    ConveyorBelt(
                        id = "degenerate",
                        checkpoints = listOf(
                            BeltNode(0f, 0f),
                            BeltNode(0f, 0f),
                        ),
                    ),
                ),
            )
        }

        val issues = validate(AssetSelection.Blueprint(blueprintKey))

        assertTrue(issues.any { it.fieldName == "conveyorBelts[0].checkpoints[1]" })
    }

    @Test
    fun `belt with distinct checkpoints is accepted`() {
        mutateBlueprint {
            it.copy(
                conveyorBelts = listOf(
                    ConveyorBelt(
                        id = "good",
                        checkpoints = listOf(
                            BeltNode(0f, 0f),
                            BeltNode(10f, 0f),
                            BeltNode(10f, 10f),
                        ),
                    ),
                ),
            )
        }

        val issues = validate(AssetSelection.Blueprint(blueprintKey))

        assertTrue(issues.none { it.fieldName?.startsWith("conveyorBelts") == true })
    }

    private fun validate(selection: AssetSelection.Blueprint): List<ValidationIssue> {
        return BlueprintValidator.validate(selection, ValidationContext(repository, selection))
    }

    private fun mutateBlueprint(mutator: (ShopBlueprint) -> ShopBlueprint) {
        val current = repository.blueprints.getValue(blueprintKey)
        repository.blueprints[blueprintKey] = mutator(current)
    }

    private fun fixtureRoot(): Path {
        val url = BlueprintValidatorTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("blueprint-validator-")
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
