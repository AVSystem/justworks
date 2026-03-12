package com.avsystem.justworks.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension

/**
 * Gradle plugin that registers the Justworks OpenAPI code generator.
 *
 * Applies the `justworks` DSL extension with multi-spec support,
 * registers per-spec generation tasks dynamically, wires generated sources
 * into Kotlin source sets, and hooks `compileKotlin` to depend on code generation.
 */
class JustworksPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // 1. Create extension
        val extension = project.extensions.create("justworks", JustworksExtension::class.java)

        // 2. Register shared types task (HttpError, Success — generated once)
        val sharedTypesTask =
            project.tasks.register("justworksSharedTypes", JustworksSharedTypesTask::class.java) { task ->
                task.outputDir.set(project.layout.buildDirectory.dir("generated/justworks/shared/kotlin"))
            }

        // 3. Register aggregate task
        val generateAllTask =
            project.tasks.register("justworksGenerateAll") { task ->
                task.group = "code generation"
                task.description = "Generate Kotlin clients from all configured OpenAPI specs"
            }

        // 4. Dynamic per-spec task registration
        extension.specs.all { spec ->
            // Validate spec name (alphanumeric, starts with letter)
            if (!spec.name.matches(Regex("[a-zA-Z][a-zA-Z0-9]*"))) {
                throw GradleException(
                    "Invalid spec name '${spec.name}': must start with a letter and contain only letters and numbers",
                )
            }

            // Compute task name: justworksGenerate<Name>
            val taskName = "justworksGenerate${spec.name.replaceFirstChar { it.uppercase() }}"

            // Register per-spec generate task
            val specTask =
                project.tasks.register(taskName, JustworksGenerateTask::class.java) { task ->
                    task.dependsOn(sharedTypesTask)
                    task.specFile.set(spec.specFile)
                    task.packageName.set(spec.packageName)
                    task.apiPackage.set(spec.apiPackage.orElse(spec.packageName.map { "$it.api" }))
                    task.modelPackage.set(spec.modelPackage.orElse(spec.packageName.map { "$it.model" }))
                    task.outputDir.set(project.layout.buildDirectory.dir("generated/justworks/${spec.name}"))
                    task.group = "code generation"
                    task.description = "Generate Kotlin client from '${spec.name}' OpenAPI spec"
                }

            // Wire spec task into aggregate task
            generateAllTask.configure { it.dependsOn(specTask) }
        }

        // 5. Wire generated sources into Kotlin source sets lazily
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            val sourceSets = project.extensions.getByType(JavaPluginExtension::class.java).sourceSets

            sourceSets.named("main") { sourceSet ->
                // Wire shared types output
                sourceSet.java.srcDir(sharedTypesTask.flatMap { it.outputDir })

                // Wire each spec's output directory
                extension.specs.all { spec ->
                    val taskName = "justworksGenerate${spec.name.replaceFirstChar { it.uppercase() }}"
                    val specTask = project.tasks.named(taskName, JustworksGenerateTask::class.java)
                    sourceSet.java.srcDir(specTask.flatMap { it.outputDir })
                }
            }

            // Hook compileKotlin to depend on generateAllTask
            project.tasks.named("compileKotlin") { task ->
                task.dependsOn(generateAllTask)
            }
        }

        // 6. Configuration-time validation
        project.afterEvaluate {
            if (extension.specs.isEmpty()) {
                project.logger.warn("justworks: no specs configured in justworks.specs { } block")
            }

            extension.specs.all { spec ->
                if (!spec.specFile.isPresent) {
                    throw GradleException("Spec '${spec.name}': specFile is required but not set")
                }
                if (!spec.packageName.isPresent) {
                    throw GradleException("Spec '${spec.name}': packageName is required but not set")
                }
            }
        }
    }
}
