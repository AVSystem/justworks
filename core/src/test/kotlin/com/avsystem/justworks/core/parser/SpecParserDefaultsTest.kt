package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.model.ApiSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    @Test
    fun `explicit null default is not honored and property stays nullable`() {
        val nullDefault = props.getValue("nullDefault")
        assertTrue(nullDefault.nullable, "explicit null default must not honor a value")
        assertEquals(null, nullDefault.defaultValue)
    }

    @Test
    fun `byte-array default makes property non-nullable and keeps the value`() {
        val secret = props.getValue("secret")
        assertFalse(secret.nullable, "property with a byte-array default must not be nullable")
        assertNotNull(secret.defaultValue, "byte-array default value must be retained")
    }

    @Test
    fun `unique array default is normalized to a plain List and property is non-nullable`() {
        val uniqueTags = props.getValue("uniqueTags")
        assertFalse(uniqueTags.nullable)
        assertEquals(listOf("X", "Y"), uniqueTags.defaultValue)
    }

    @Test
    fun `object reference default is normalized to a plain Map and property is non-nullable`() {
        val config = props.getValue("config")
        assertFalse(config.nullable, "property with an object default must not be nullable")
        assertEquals(
            mapOf("taskName" to null, "parameters" to emptyList<Any?>(), "isActive" to false),
            config.defaultValue,
        )
    }

    @Test
    fun `map-type default is not honored and property stays nullable`() {
        // A free-form map default is not honored: the property stays nullable, so the model
        // layer emits `= null` regardless of the retained raw default value.
        val rawMap = props.getValue("rawMap")
        assertTrue(rawMap.nullable, "free-form map default must not be honored")
    }

    @Test
    fun `array of object defaults is normalized to a List of Maps`() {
        val configs = props.getValue("configs")
        assertFalse(configs.nullable)
        assertEquals(
            listOf(
                mapOf("taskName" to "first", "isActive" to true),
                mapOf("taskName" to "second"),
            ),
            configs.defaultValue,
        )
    }

    @Test
    fun `object default preserves nested scalar values and types`() {
        val config = props.getValue("config")

        @Suppress("UNCHECKED_CAST")
        val map = config.defaultValue as Map<String, Any?>
        assertEquals(false, map["isActive"])
        assertTrue(map.containsKey("taskName"))
        assertEquals(null, map["taskName"])
    }
}
