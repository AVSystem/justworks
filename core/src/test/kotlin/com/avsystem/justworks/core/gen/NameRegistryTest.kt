package com.avsystem.justworks.core.gen

import kotlin.test.Test
import kotlin.test.assertEquals

class NameRegistryTest {
    @Test
    fun `register on empty registry returns desired name`() {
        val registry = NameRegistry()
        assertEquals("Foo", registry.register("Foo"))
    }

    @Test
    fun `register same name twice returns numeric suffix`() {
        val registry = NameRegistry()
        assertEquals("Foo", registry.register("Foo"))
        assertEquals("Foo2", registry.register("Foo"))
    }

    @Test
    fun `register same name three times returns incrementing suffixes`() {
        val registry = NameRegistry()
        assertEquals("Foo", registry.register("Foo"))
        assertEquals("Foo2", registry.register("Foo"))
        assertEquals("Foo3", registry.register("Foo"))
    }

    @Test
    fun `reserve then register returns Foo2`() {
        val registry = NameRegistry()
        registry.reserve("Foo")
        assertEquals("Foo2", registry.register("Foo"))
    }

    @Test
    fun `reserve Foo and Foo2 then register Foo returns Foo3`() {
        val registry = NameRegistry()
        registry.reserve("Foo")
        registry.reserve("Foo2")
        assertEquals("Foo3", registry.register("Foo"))
    }

    @Test
    fun `register after reserve for component schema collision`() {
        val registry = NameRegistry()
        registry.reserve("Pet")
        assertEquals("Pet2", registry.register("Pet"))
    }

    @Test
    fun `different names do not interfere`() {
        val registry = NameRegistry()
        assertEquals("Foo", registry.register("Foo"))
        assertEquals("Bar", registry.register("Bar"))
    }

    @Test
    fun `names differing only by case are treated as distinct`() {
        val registry = NameRegistry()
        assertEquals("Foo", registry.register("Foo"))
        assertEquals("foo", registry.register("foo"))
        assertEquals("FOO", registry.register("FOO"))
    }
}
