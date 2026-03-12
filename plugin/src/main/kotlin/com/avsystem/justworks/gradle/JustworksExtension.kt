package com.avsystem.justworks.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * DSL extension for configuring the Justworks OpenAPI code generator.
 *
 * Supports multiple named specs, each with independent configuration.
 *
 * Usage in `build.gradle.kts`:
 * ```kotlin
 * justworks {
 *     specs {
 *         register("petstore") {
 *             specFile.set(file("api/petstore.yaml"))
 *             packageName.set("com.example.petstore")
 *         }
 *         register("payments") {
 *             specFile.set(file("api/payments.yaml"))
 *             packageName.set("com.example.payments")
 *         }
 *     }
 * }
 * ```
 *
 * Each registered spec generates to an isolated output directory
 * and has its own generation task: `justworksGenerate<Name>`.
 */
abstract class JustworksExtension
    @Inject
    constructor(objects: ObjectFactory) {
        /**
         * Container of named OpenAPI spec configurations.
         *
         * Each spec is independently configured and generates to its own
         * build/generated/justworks/{specName}/ directory.
         */
        val specs: NamedDomainObjectContainer<JustworksSpecConfiguration> =
            objects.domainObjectContainer(JustworksSpecConfiguration::class.java)
    }
