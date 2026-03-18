package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.ModelGenerator.HierarchyInfo
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.PropertySpec

/**
 * Generates a `SerializersModule` registration file for all polymorphic sealed hierarchies.
 *
 * Produces a top-level `val generatedSerializersModule: SerializersModule` property
 * that registers each sealed interface with its subclass variants.
 */
class SerializersModuleGenerator(private val modelPackage: String) {
    /**
     * Generates a [FileSpec] containing the SerializersModule registration.
     * Returns null if the hierarchy has no sealed types to register.
     */

    context(hierarchy: HierarchyInfo)
    fun generate(): FileSpec? {
        val discriminatorHierarchies =
            hierarchy.sealedHierarchies.filterKeys { it !in hierarchy.anyOfWithoutDiscriminator }

        if (discriminatorHierarchies.isEmpty()) return null

        val code = CodeBlock.builder().beginControlFlow("%T", SERIALIZERS_MODULE)

        for ((parent, variants) in discriminatorHierarchies) {
            val parentClass = ClassName(modelPackage, parent)
            code.beginControlFlow("%M(%T::class)", POLYMORPHIC_FUN, parentClass)
            for (variant in variants) {
                val variantClass = ClassName(modelPackage, variant)
                code.addStatement("%M(%T::class)", SUBCLASS_FUN, variantClass)
            }
            code.endControlFlow()
        }

        code.endControlFlow()

        val prop =
            PropertySpec
                .builder("generatedSerializersModule", SERIALIZERS_MODULE)
                .initializer(code.build())
                .build()

        return FileSpec
            .builder(modelPackage, "SerializersModule")
            .addProperty(prop)
            .build()
    }
}
