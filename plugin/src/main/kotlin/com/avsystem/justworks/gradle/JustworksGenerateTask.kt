package com.avsystem.justworks.gradle

import com.avsystem.justworks.core.gen.CodeGenerator
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
import java.io.File

/**
 * Gradle task that generates Kotlin source files from an OpenAPI spec.
 *
 * Parses the OpenAPI spec via [SpecParser], feeds the result to [CodeGenerator],
 * and writes the generated Kotlin source files to the output directory.
 */
@CacheableTask
abstract class JustworksGenerateTask : DefaultTask() {
    /** Path to the OpenAPI spec file. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specFile: RegularFileProperty

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
        val outDir = outputDir.get().asFile.recreateDirectory()

        val spec = specFile.get().asFile
        val result = SpecParser.parse(spec)
        result.warnings.forEach { logger.warn(it.message) }

        when (result) {
            is ParseResult.Failure -> {
                throw GradleException("Failed to parse spec (task: $name): ${spec.name}:\n${result.error}")
            }

            is ParseResult.Success -> {
                val (modelCount, clientCount) = CodeGenerator.generate(
                    spec = result.apiSpec,
                    modelPackage = modelPackage.get(),
                    apiPackage = apiPackage.get(),
                    outputDir = outDir,
                )

                logger.lifecycle(
                    "Generated $modelCount model files, $clientCount client files from ${spec.name}",
                )
            }
        }
    }
}

internal fun File.recreateDirectory(): File {
    if (exists()) deleteRecursively()
    mkdirs()
    return this
}
