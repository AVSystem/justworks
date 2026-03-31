package com.avsystem.justworks.core.gen.shared

import com.avsystem.justworks.core.gen.GENERATED_SERIALIZERS_MODULE
import com.avsystem.justworks.core.gen.ModelPackage
import com.avsystem.justworks.core.gen.POLYMORPHIC_FUN
import com.avsystem.justworks.core.gen.SERIALIZERS_MODULE
import com.avsystem.justworks.core.gen.SUBCLASS_FUN
import com.avsystem.justworks.core.gen.invoke
import com.avsystem.justworks.core.gen.model.ModelGenerator
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.PropertySpec
import kotlin.collections.iterator

/**
 * Generates a `SerializersModule` registration file for all polymorphic sealed hierarchies.
 *
 * Produces a top-level `val generatedSerializersModule: SerializersModule` property
 * that registers each sealed interface with its subclass variants.
 */
internal object SerializersModuleGenerator {
    const val FILE_NAME = "SerializersModule"

    /**
     * Generates a [com.squareup.kotlinpoet.FileSpec] containing the SerializersModule registration.
     * Returns null if the hierarchy has no sealed types to register.
     */

    context(hierarchy: ModelGenerator.HierarchyInfo, modelPackage: ModelPackage)
    fun generate(): FileSpec? {
        // anyOf hierarchies without a discriminator use JsonContentPolymorphicSerializer
        // with custom deserialization logic, so they don't need SerializersModule registration.
        val discriminatorHierarchies =
            hierarchy.sealedHierarchies.filterKeys { it !in hierarchy.anyOfWithoutDiscriminator }

        if (discriminatorHierarchies.isEmpty()) return null

        val code = CodeBlock.builder().beginControlFlow("%T", SERIALIZERS_MODULE)

        for ((parent, variants) in discriminatorHierarchies) {
            val parentClass = ClassName(modelPackage, parent)
            code.beginControlFlow("%M(%T::class)", POLYMORPHIC_FUN, parentClass)
            for (variant in variants) {
                val variantClass = parentClass.nestedClass(variant)
                code.addStatement("%M(%T::class)", SUBCLASS_FUN, variantClass)
            }
            code.endControlFlow()
        }

        code.endControlFlow()

        val prop =
            PropertySpec
                .builder(GENERATED_SERIALIZERS_MODULE, SERIALIZERS_MODULE)
                .initializer(code.build())
                .build()

        return FileSpec
            .builder(modelPackage.name, FILE_NAME)
            .addProperty(prop)
            .build()
    }
}
