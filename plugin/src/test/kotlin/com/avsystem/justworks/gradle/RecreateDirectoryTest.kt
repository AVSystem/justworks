package com.avsystem.justworks.gradle

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RecreateDirectoryTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `creates directory when it does not exist`() {
        val dir = tempDir.resolve("new-dir")
        assertFalse(dir.exists())

        val result = dir.recreateDirectory()

        assertTrue(dir.exists())
        assertTrue(dir.isDirectory)
        assertEquals(dir, result)
    }

    @Test
    fun `deletes existing contents and recreates empty directory`() {
        val dir = tempDir.resolve("existing-dir")
        dir.mkdirs()
        dir.resolve("file.txt").writeText("content")
        dir.resolve("subdir").mkdir()
        dir.resolve("subdir/nested.txt").writeText("nested")

        dir.recreateDirectory()

        assertTrue(dir.exists())
        assertTrue(dir.isDirectory)
        assertEquals(emptyList(), dir.listFiles()?.toList())
    }

    @Test
    fun `creates nested directory structure`() {
        val dir = tempDir.resolve("a/b/c")
        assertFalse(dir.exists())

        dir.recreateDirectory()

        assertTrue(dir.exists())
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `returns the same file instance`() {
        val dir = tempDir.resolve("dir")

        val result = dir.recreateDirectory()

        assertSame(result, dir)
    }
}
