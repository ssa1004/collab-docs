package com.example.collab.domain.document

import com.example.collab.domain.edit.IllegalOperationException
import com.example.collab.domain.edit.TextOperation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentTest {

    private fun doc(content: String = "hello", version: Int = 0) =
        Document.create(DocumentId("d1"), UserId("u1"), "title", content).copy(version = version)

    @Test
    fun `applyEdit applies op and increments version`() {
        val d = doc("hello", version = 3)
        val edited = d.applyEdit(TextOperation.Insert(5, " world"))
        assertEquals("hello world", edited.content)
        assertEquals(4, edited.version)
    }

    @Test
    fun `applyEdit delete increments version`() {
        val d = doc("hello", version = 0)
        val edited = d.applyEdit(TextOperation.Delete(0, 1))
        assertEquals("ello", edited.content)
        assertEquals(1, edited.version)
    }

    @Test
    fun `applyEdit out of range throws and does not mutate original`() {
        val d = doc("hi", version = 0)
        assertFailsWith<IllegalOperationException> { d.applyEdit(TextOperation.Insert(10, "x")) }
        assertEquals("hi", d.content) // 원본 불변
        assertEquals(0, d.version)
    }

    @Test
    fun `create starts at version 0`() {
        assertEquals(0, Document.create(DocumentId("d"), UserId("u"), "t").version)
    }
}
