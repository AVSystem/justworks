package com.avsystem.justworks.gradle

import com.avsystem.justworks.core.gen.ApiResponseGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Gradle task that generates shared types (HttpError, Success) once
 * to a fixed output directory shared across all spec configurations.
 */
@CacheableTask
abstract class JustworksSharedTypesTask : DefaultTask() {
    /** Output directory for shared type files. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        val files = listOf(
            ApiResponseGenerator.generateHttpError(),
            ApiResponseGenerator.generateHttpSuccess(),
        )
        files.forEach { it.writeTo(outDir) }

        logger.lifecycle("Generated ${files.size} shared type files")
    }
}
