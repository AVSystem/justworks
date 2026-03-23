package com.avsystem.justworks.gradle

import com.avsystem.justworks.core.gen.CodeGenerator
import com.avsystem.justworks.core.model.SecurityScheme
import com.avsystem.justworks.core.parser.ParseResult
import com.avsystem.justworks.core.parser.SpecParser
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Gradle task that generates shared types (HttpError, Success, ApiClientBase) once
 * to a fixed output directory shared across all spec configurations.
 *
 * When [specFiles] are configured, the task parses them to extract security schemes
 * and passes them to ApiClientBase generation so the generated auth code reflects
 * the spec's security configuration.
 */
@CacheableTask
abstract class JustworksSharedTypesTask : DefaultTask() {
    /** All configured spec files — used to extract security schemes. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specFiles: ConfigurableFileCollection

    /** Output directory for shared type files. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val outDir = outputDir.get().asFile.recreateDirectory()

        val securitySchemes = extractSecuritySchemes()

        val count = CodeGenerator.generateSharedTypes(outDir, securitySchemes.ifEmpty { null })

        logger.lifecycle("Generated $count shared type files")
    }

    private fun extractSecuritySchemes(): List<SecurityScheme> = specFiles.files
        .mapNotNull { file ->
            when (val result = SpecParser.parse(file)) {
                is ParseResult.Success -> result.apiSpec.securitySchemes
                is ParseResult.Failure -> {
                    logger.warn("Failed to parse spec '${file.name}': ${result.errors.joinToString()}")
                    null
                }
            }
        }
        .flatten()
        .distinctBy { it.name }
}
