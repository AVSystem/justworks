package com.avsystem.justworks.core.gen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.PropertySpec
import java.io.File

/**
 * Generates a `SerializersModule` registration file for all polymorphic sealed hierarchies.
 *
 * Produces a top-level `val generatedSerializersModule: SerializersModule` property
 * that registers each sealed interface with its subclass variants.
 */
class SerializersModuleGenerator(private val modelPackage: String) {
    /**
     * Generates a [FileSpec] containing the SerializersModule registration.
     * Returns null if [sealedHierarchies] is empty (no polymorphic types to register).
     *
     * @param sealedHierarchies map of sealed parent name to list of variant schema names
     */
    fun generate(sealedHierarchies: Map<String, List<String>>): FileSpec? {
        if (sealedHierarchies.isEmpty()) return null

        val code = CodeBlock.builder().beginControlFlow("%T", SERIALIZERS_MODULE)

        for ((parent, variants) in sealedHierarchies) {
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
