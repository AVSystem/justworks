package com.avsystem.justworks.gradle

import org.gradle.api.Named
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configuration for a single OpenAPI spec in the Justworks multi-spec DSL.
 *
 * Each spec is independently configured with its own OpenAPI file path,
 * package structure, and generates to an isolated output directory.
 *
 * Usage in `build.gradle.kts`:
 * ```kotlin
 * justworks {
 *     specs {
 *         register("petstore") {
 *             specFile.set(file("api/petstore.yaml"))
 *             packageName.set("com.example.petstore")
 *         }
 *     }
 * }
 * ```
 *
 * This class implements [Named] to work with Gradle's [org.gradle.api.NamedDomainObjectContainer].
 * The name is injected via constructor and determines the task name and output directory.
 */
abstract class JustworksSpecConfiguration
    @Inject
    constructor(private val name: String) : Named {
        /** Path to the OpenAPI spec file (.yaml or .json). */
        abstract val specFile: RegularFileProperty

        /** Base package name for generated code (e.g., "com.example.api"). */
        abstract val packageName: Property<String>

        /**
         * Package for generated API client classes.
         * Defaults to `"$packageName.api"` if not set.
         */
        abstract val apiPackage: Property<String>

        /**
         * Package for generated model/data classes.
         * Defaults to `"$packageName.model"` if not set.
         */
        abstract val modelPackage: Property<String>

        override fun getName(): String = name
    }
