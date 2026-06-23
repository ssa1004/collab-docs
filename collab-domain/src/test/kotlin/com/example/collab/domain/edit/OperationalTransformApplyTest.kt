package com.example.collab.domain.edit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OperationalTransformApplyTest {

    @Test
    fun `apply insert in the middle`() {
        assertEquals("abXcd", OperationalTransform.apply("abcd", TextOperation.Insert(2, "X")))
    }

    @Test
    fun `apply insert at end (position == length) is allowed`() {
        assertEquals("abcdX", OperationalTransform.apply("abcd", TextOperation.Insert(4, "X")))
    }

    @Test
    fun `apply delete range`() {
        assertEquals("ad", OperationalTransform.apply("abcd", TextOperation.Delete(1, 2)))
    }

    @Test
    fun `insert beyond length throws`() {
        assertFailsWith<IllegalOperationException> {
            OperationalTransform.apply("abc", TextOperation.Insert(5, "X"))
        }
    }

    @Test
    fun `delete past end throws`() {
        assertFailsWith<IllegalOperationException> {
            OperationalTransform.apply("abc", TextOperation.Delete(2, 5))
        }
    }

    @Test
    fun `empty composite is a no-op`() {
        assertEquals("abc", OperationalTransform.apply("abc", TextOperation.Composite(emptyList())))
    }

    @Test
    fun `composite applies children in order`() {
        // back delete then front delete (delVsIns split 패턴)
        val composite = TextOperation.Composite(
            listOf(TextOperation.Delete(3, 2), TextOperation.Delete(1, 1)),
        )
        // "ABXCDE" -> 뒤[3,5)="CD" 삭제 -> "ABXE" -> 앞[1,2)="B" 삭제 -> "AXE"
        assertEquals("AXE", OperationalTransform.apply("ABXCDE", composite))
    }
}
