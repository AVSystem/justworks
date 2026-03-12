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

    /** Generates the shared HttpError and Success type files into [outputDir]. */
    @TaskAction
    fun generate() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        val count = ApiResponseGenerator.generateTo(outDir)

        logger.lifecycle("Generated $count shared type files")
    }
}
