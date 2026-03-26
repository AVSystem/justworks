package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.parser.ParseResult
import com.avsystem.justworks.core.parser.SpecParser
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class CodeGeneratorTest {
    companion object {
        private val SPEC_FIXTURES = listOf(
            "/fixtures/platform-api.json",
            "/fixtures/analytics-api.json",
        )
    }

    @Test
    fun `generate produces model and client files for real-world specs`() {
        for (fixture in SPEC_FIXTURES) {
            val specUrl = javaClass.getResource(fixture)
                ?: fail("Spec fixture not found: $fixture")
            val specFile = File(specUrl.toURI())
            val spec = when (val result = SpecParser.parse(specFile)) {
                is ParseResult.Success -> result.apiSpec
                is ParseResult.Failure -> fail("Failed to parse $fixture: ${result.error}")
            }

            val outputDir = Files.createTempDirectory("codegen-test").toFile()
            try {
                val result = CodeGenerator.generate(
                    spec = spec,
                    modelPackage = "com.example.model",
                    apiPackage = "com.example.api",
                    outputDir = outputDir,
                )

                assertTrue(result.modelFiles > 0, "$fixture: should produce model files")
                if (spec.endpoints.isNotEmpty()) {
                    assertTrue(result.clientFiles > 0, "$fixture: should produce client files")
                }

                val generatedFiles = outputDir.walkTopDown().filter { it.isFile }.toList()
                assertTrue(generatedFiles.isNotEmpty(), "$fixture: output directory should contain files")
            } finally {
                outputDir.deleteRecursively()
            }
        }
    }
}
