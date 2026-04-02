package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.model.ApiSpec
import java.io.File
import kotlin.test.fail

abstract class SpecParserTestBase {
    protected fun loadResource(name: String): File {
        val url =
            javaClass.getResource("/$name")
                ?: fail("Test resource not found: $name")
        return File(url.toURI())
    }

    protected fun parseSpec(file: File): ApiSpec = when (val result = SpecParser.parse(file)) {
        is ParseResult.Success -> result.apiSpec
        is ParseResult.Failure -> fail("Expected success but got error: ${result.error}")
    }
}
