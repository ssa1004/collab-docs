package com.example.collab.domain.edit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 4 케이스의 대표 시나리오를 직접 명세로 고정한다(속성 테스트 보완). */
class OperationalTransformCasesTest {

    @Test
    fun `insert vs insert - a after b shifts right`() {
        val a = TextOperation.Insert(5, "X")
        val b = TextOperation.Insert(2, "YY")
        assertEquals(TextOperation.Insert(7, "X"), OperationalTransform.transform(a, b, true))
    }

    @Test
    fun `insert vs insert - same position tie-break by priority`() {
        val a = TextOperation.Insert(3, "X")
        val b = TextOperation.Insert(3, "Y")
        assertEquals(TextOperation.Insert(3, "X"), OperationalTransform.transform(a, b, aHasPriority = true))
        assertEquals(TextOperation.Insert(4, "X"), OperationalTransform.transform(a, b, aHasPriority = false))
    }

    @Test
    fun `insert vs delete - insert after deleted range shifts left`() {
        val a = TextOperation.Insert(6, "X")
        val b = TextOperation.Delete(1, 3) // [1,4)
        assertEquals(TextOperation.Insert(3, "X"), OperationalTransform.transform(a, b, true))
    }

    @Test
    fun `insert vs delete - insert before deleted range unchanged`() {
        val a = TextOperation.Insert(0, "X")
        val b = TextOperation.Delete(2, 2)
        assertEquals(TextOperation.Insert(0, "X"), OperationalTransform.transform(a, b, true))
    }

    @Test
    fun `insert vs delete - insert inside deleted range clamps to delete start (survive)`() {
        val a = TextOperation.Insert(3, "X")
        val b = TextOperation.Delete(1, 4) // [1,5)
        assertEquals(TextOperation.Insert(1, "X"), OperationalTransform.transform(a, b, true))
    }

    @Test
    fun `delete vs insert - insert before range shifts delete right`() {
        val a = TextOperation.Delete(4, 2) // [4,6)
        val b = TextOperation.Insert(1, "YY")
        assertEquals(TextOperation.Delete(6, 2), OperationalTransform.transform(a, b, true))
    }

    @Test
    fun `delete vs insert - insert after range unchanged`() {
        val a = TextOperation.Delete(1, 2)
        val b = TextOperation.Insert(8, "Y")
        assertEquals(TextOperation.Delete(1, 2), OperationalTransform.transform(a, b, true))
    }

    @Test
    fun `delete vs delete - overlapping deletes subtract intersection`() {
        // a=[1,5) deletes 4 chars, b=[3,7) deletes overlap [3,5)=2 chars
        val a = TextOperation.Delete(1, 4)
        val b = TextOperation.Delete(3, 4)
        // a' should delete remaining 2 chars starting at 1 (nothing before aStart was deleted by b)
        assertEquals(TextOperation.Delete(1, 2), OperationalTransform.transform(a, b, true))
    }

    @Test
    fun `delete vs delete - a fully covered by b becomes no-op`() {
        val a = TextOperation.Delete(2, 2) // [2,4)
        val b = TextOperation.Delete(1, 5) // [1,6) covers a
        assertEquals(TextOperation.Composite(emptyList()), OperationalTransform.transform(a, b, true))
    }

    @Test
    fun `delete vs delete - b before a shifts a left`() {
        val a = TextOperation.Delete(5, 2) // [5,7)
        val b = TextOperation.Delete(1, 2) // [1,3) entirely before a
        assertEquals(TextOperation.Delete(3, 2), OperationalTransform.transform(a, b, true))
    }
}
