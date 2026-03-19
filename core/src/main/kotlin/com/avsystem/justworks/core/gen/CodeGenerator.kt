package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import java.io.File

/**
 * Facade that orchestrates model and client code generation,
 * writing the produced files to the given output directory.
 */
object CodeGenerator {
    data class Result(val modelFiles: Int, val clientFiles: Int)

    fun generate(
        spec: ApiSpec,
        modelPackage: String,
        apiPackage: String,
        outputDir: File
    ): Result {
        val modelFiles = ModelGenerator(modelPackage).generate(spec)
        modelFiles.forEach { it.writeTo(outputDir) }

        val hasPolymorphicTypes = modelFiles.any { it.name == "SerializersModule" }

        val clientFiles = ClientGenerator(apiPackage, modelPackage).generate(spec, hasPolymorphicTypes)
        clientFiles.forEach { it.writeTo(outputDir) }

        return Result(modelFiles.size, clientFiles.size)
    }

    fun generateSharedTypes(outputDir: File): Int {
        val files = ApiResponseGenerator.generate()
        files.forEach { it.writeTo(outputDir) }
        return files.size
    }
}
