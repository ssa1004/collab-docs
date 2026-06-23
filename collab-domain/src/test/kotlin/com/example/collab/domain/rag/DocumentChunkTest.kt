package com.example.collab.domain.rag

import com.example.collab.domain.document.DocumentId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentChunkTest {

    private val docId = DocumentId("d1")

    @Test
    fun `blank content yields no chunks`() {
        assertTrue(DocumentChunk.chunk(docId, "   ").isEmpty())
    }

    @Test
    fun `paragraphs become separate ordered chunks`() {
        val chunks = DocumentChunk.chunk(docId, "first para\n\nsecond para", targetSize = 1000)
        assertEquals(2, chunks.size)
        assertEquals("first para", chunks[0].text)
        assertEquals("second para", chunks[1].text)
        assertEquals(listOf(0, 1), chunks.map { it.ordinal })
    }

    @Test
    fun `long paragraph is split by target size`() {
        val long = "a".repeat(700)
        val chunks = DocumentChunk.chunk(docId, long, targetSize = 280)
        assertEquals(3, chunks.size) // 280 + 280 + 140
        assertEquals(280, chunks[0].text.length)
        assertEquals(140, chunks[2].text.length)
        assertEquals(long, chunks.joinToString("") { it.text })
    }
}
