package com.avsystem.justworks.gradle

import com.avsystem.justworks.core.gen.ApiClientBaseGenerator
import com.avsystem.justworks.core.gen.ClientGenerator
import com.avsystem.justworks.core.gen.ModelGenerator
import com.avsystem.justworks.core.parser.ParseResult
import com.avsystem.justworks.core.parser.SpecParser
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Gradle task that generates Kotlin source files from an OpenAPI spec.
 *
 * Parses the OpenAPI spec via [SpecParser], feeds the result to [ModelGenerator],
 * [ClientGenerator], and [ApiClientBaseGenerator], then writes the generated
 * Kotlin source files to the output directory.
 */
@CacheableTask
abstract class JustworksGenerateTask : DefaultTask() {
    /** Path to the OpenAPI spec file. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specFile: RegularFileProperty

    /** Base package name for generated code. */
    @get:Input
    abstract val packageName: Property<String>

    /** Package for generated API client classes. */
    @get:Input
    abstract val apiPackage: Property<String>

    /** Package for generated model/data classes. */
    @get:Input
    abstract val modelPackage: Property<String>

    /** Output directory for generated Kotlin source files. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val outDir = outputDir.get().asFile

        // Clean output directory before generation to prevent stale files
        if (outDir.exists()) {
            outDir.deleteRecursively()
        }
        outDir.mkdirs()

        val specFile = specFile.get().asFile
        val result = SpecParser.parse(specFile)

        when (result) {
            is ParseResult.Failure -> {
                throw GradleException(
                    "Failed to parse spec (task: $name): ${specFile.name}:\n${result.errors.joinToString("\n")}",
                )
            }

            is ParseResult.Success -> {
                val apiSpec = result.apiSpec

                val modelFiles = ModelGenerator(modelPackage.get()).generate(apiSpec)
                modelFiles.forEach { it.writeTo(outDir) }

                val hasPolymorphicTypes = apiSpec.schemas.any {
                    !it.oneOf.isNullOrEmpty() || !it.anyOf.isNullOrEmpty()
                }

                val clientFiles = ClientGenerator(apiPackage.get(), modelPackage.get())
                    .generate(apiSpec, hasPolymorphicTypes)
                clientFiles.forEach { it.writeTo(outDir) }

                // Generate ApiClientBase when spec has endpoints
                if (apiSpec.endpoints.isNotEmpty()) {
                    ApiClientBaseGenerator.generate().writeTo(outDir)
                }

                logger.lifecycle(
                    "Generated ${modelFiles.size} model files, ${clientFiles.size} client files from ${specFile.name}",
                )
            }
        }
    }
}
