package com.avsystem.justworks.gradle

import com.avsystem.justworks.core.gen.CodeGenerator
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
 * When [specFiles] are configured, the task performs a lightweight parse to extract
 * only security schemes (skipping full endpoint/schema extraction) and passes them
 * to ApiClientBase generation so the generated auth code reflects the spec's
 * security configuration.
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

        val allSchemes = specFiles.files.sortedBy { it.path }.flatMap { file ->
            when (val result = SpecParser.parseSecuritySchemes(file)) {
                is ParseResult.Success -> {
                    result.warnings.forEach { logger.warn(it.message) }
                    result.value
                }

                is ParseResult.Failure -> {
                    logger.warn("Failed to parse security schemes from '${file.name}': ${result.error}")
                    emptyList()
                }
            }
        }

        for ((name, schemes) in allSchemes.groupBy { it.name }) {
            if (schemes.size > 1) {
                val types = schemes.map { it::class.simpleName }
                logger.warn(
                    "Security scheme '$name' defined ${schemes.size} times with types $types — " +
                        "using first occurrence (${types.first()})",
                )
            }
        }

        val count = CodeGenerator.generateSharedTypes(outDir, allSchemes.distinctBy { it.name })
        logger.lifecycle("Generated $count shared type files")
    }
}
