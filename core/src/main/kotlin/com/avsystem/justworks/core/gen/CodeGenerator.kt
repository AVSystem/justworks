package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.client.ClientGenerator
import com.avsystem.justworks.core.gen.model.ModelGenerator
import com.avsystem.justworks.core.gen.shared.ApiClientBaseGenerator
import com.avsystem.justworks.core.gen.shared.ApiResponseGenerator
import com.avsystem.justworks.core.model.ApiSpec
import java.io.File

/**
 * Facade that orchestrates model and client code generation,
 * writing the produced files to the given output directory.
 */
internal object CodeGenerator {
    data class Result(val modelFiles: Int, val clientFiles: Int)

    fun generate(
        spec: ApiSpec,
        modelPackage: String,
        apiPackage: String,
        outputDir: File,
    ): Result = context(ModelPackage(modelPackage), ApiPackage(apiPackage)) {
        val modelRegistry = NameRegistry()
        val apiRegistry = NameRegistry()

        val (modelFiles, resolvedSpec) = ModelGenerator.generateWithResolvedSpec(spec, modelRegistry)

        modelFiles.forEach { it.writeTo(outputDir) }

        val hasPolymorphicTypes = modelFiles.any { it.name == SERIALIZERS_MODULE.simpleName }

        val clientFiles = ClientGenerator.generate(resolvedSpec, hasPolymorphicTypes, apiRegistry)

        clientFiles.forEach { it.writeTo(outputDir) }

        return Result(modelFiles.size, clientFiles.size)
    }

    fun generateSharedTypes(outputDir: File): Int {
        val files = ApiResponseGenerator.generate() + ApiClientBaseGenerator.generate()
        files.forEach { it.writeTo(outputDir) }
        return files.size
    }
}
