package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.model.ApiSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A property that declares a `default` is not optional-null: it must be parsed as a non-nullable
 * field carrying that default value. Covers scalar, enum, and array defaults; properties without a
 * default stay nullable. See [SpecParser.honorsDefault].
 */
class SpecParserDefaultsTest : SpecParserTestBase() {
    private val defaults: ApiSpec = parseSpec(loadResource("fixtures/property-defaults-spec.json"))

    private val props get() = defaults.schemas
        .first { it.name == "Defaults" }
        .properties
        .associateBy { it.name }

    @Test
    fun `scalar enum default makes property non-nullable and keeps the value`() {
        val mode = props.getValue("mode")
        assertFalse(mode.nullable, "property with a default must not be nullable")
        assertEquals("B", mode.defaultValue)
    }

    @Test
    fun `numeric default makes property non-nullable and keeps the value`() {
        val retries = props.getValue("retries")
        assertFalse(retries.nullable)
        assertEquals(3, retries.defaultValue)
    }

    @Test
    fun `array default is normalized to a plain List and property is non-nullable`() {
        val tags = props.getValue("tags")
        assertFalse(tags.nullable)
        assertEquals(listOf("X", "Y"), tags.defaultValue)
    }

    @Test
    fun `property without a default stays nullable`() {
        val note = props.getValue("note")
        assertTrue(note.nullable)
        assertEquals(null, note.defaultValue)
    }
}
