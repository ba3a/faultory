package com.faultory.editor.validation

import com.faultory.core.content.ProductDefinition
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

class ProductValidatorTest {

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
    fun `valid product produces no issues`() {
        val issues = validate(AssetSelection.Product("ceramic-mug"))
        assertTrue(issues.isEmpty(), "expected no issues but got $issues")
    }

    @Test
    fun `unknown selection id produces no issues`() {
        val issues = validate(AssetSelection.Product("does-not-exist"))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `blank id is an error`() {
        replaceSingleProduct { it.copy(id = "") }

        val issues = validate(AssetSelection.Product(""))

        assertEquals(
            listOf(ValidationIssue(Severity.ERROR, "Product id must not be blank", fieldName = "id")),
            issues,
        )
    }

    @Test
    fun `duplicate id is an error`() {
        val original = repository.shopCatalog.products.single()
        repository.shopCatalog = repository.shopCatalog.copy(
            products = listOf(original, original.copy(displayName = "Second Mug")),
        )

        val issues = validate(AssetSelection.Product(original.id))

        assertEquals(1, issues.size)
        assertEquals(Severity.ERROR, issues.single().severity)
        assertEquals("id", issues.single().fieldName)
        assertTrue(issues.single().message.contains("Duplicate"))
    }

    @Test
    fun `negative saleValue is an error`() {
        replaceSingleProduct { it.copy(saleValue = -1) }

        val issues = validate(AssetSelection.Product("ceramic-mug"))

        assertEquals(
            listOf(
                ValidationIssue(
                    Severity.ERROR,
                    "saleValue must be non-negative",
                    fieldName = "saleValue",
                ),
            ),
            issues,
        )
    }

    @Test
    fun `zero saleValue is accepted`() {
        replaceSingleProduct { it.copy(saleValue = 0) }

        val issues = validate(AssetSelection.Product("ceramic-mug"))

        assertTrue(issues.isEmpty())
    }

    private fun validate(selection: AssetSelection.Product): List<ValidationIssue> {
        return ProductValidator.validate(selection, ValidationContext(repository, selection))
    }

    private fun replaceSingleProduct(mutator: (ProductDefinition) -> ProductDefinition) {
        val product = repository.shopCatalog.products.single()
        repository.shopCatalog = repository.shopCatalog.copy(products = listOf(mutator(product)))
    }

    private fun fixtureRoot(): Path {
        val url = ProductValidatorTest::class.java.classLoader.getResource("assets")
            ?: error("fixture 'assets/' not found on test classpath")
        return Paths.get(url.toURI())
    }

    private fun copyFixturesToTempDir(): Path {
        val source = fixtureRoot()
        val dest = createTempDirectory("product-validator-")
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
