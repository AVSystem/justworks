package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.model.EnumBackingType
import com.avsystem.justworks.core.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SpecParserInlineEnumTest : SpecParserTestBase() {
    @Test
    fun `inline enum in array items is parsed as InlineEnum`() {
        val spec = parseSpec(loadResource("inline-enum-spec.yaml"))

        val schema = assertNotNull(
            spec.schemas.find { it.name == "ExecuteSpeedTestRequest" },
            "ExecuteSpeedTestRequest schema missing",
        )
        val measurements = assertNotNull(
            schema.properties.find { it.name == "measurements" },
            "measurements property missing",
        )

        val array = assertIs<TypeRef.Array>(measurements.type)
        val inlineEnum = assertIs<TypeRef.InlineEnum>(array.items)

        assertEquals(listOf("DownloadSpeed", "UploadSpeed"), inlineEnum.values)
        assertEquals(EnumBackingType.STRING, inlineEnum.backingType)
    }
}
