package com.example.collab.domain.edit

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OT 수렴(TP1) 속성 테스트.
 *
 * 랜덤 base 문자열 + 랜덤 동시 op 쌍 (a, b) 를 다수 생성하고,
 *   path1 = apply(apply(base, a), transform(b, a, false))
 *   path2 = apply(apply(base, b), transform(a, b, true))
 * 두 경로의 최종 문자열이 항상 동일함을 단언한다(서버 권위 OT 수렴).
 *
 * priority(우선권)는 "먼저 적용되는 쪽"에 줘야 결정론적이다:
 *  - path1: b 를 먼저 적용 → b 가 우선 → transform(a, b, aHasPriority=true)
 *  - path2: a 를 먼저 적용 → a 가 우선 → transform(b, a, aHasPriority=false)
 *  (한쪽이 true 면 반대쪽은 false 여야 tie-break 가 어긋나지 않음)
 */
class OperationalTransformConvergenceTest {

    private fun randomOp(rng: Random, base: String): TextOperation {
        val len = base.length
        // insert 50%, delete 50% (단, 빈 문자열이면 항상 insert)
        val doInsert = len == 0 || rng.nextBoolean()
        return if (doInsert) {
            val pos = rng.nextInt(0, len + 1)
            val text = randomText(rng, rng.nextInt(1, 4))
            TextOperation.Insert(pos, text)
        } else {
            val pos = rng.nextInt(0, len)               // 0..len-1
            val maxLen = len - pos
            val delLen = rng.nextInt(1, maxLen + 1)
            TextOperation.Delete(pos, delLen)
        }
    }

    private fun randomText(rng: Random, n: Int): String {
        val alphabet = "abcdefghij"  // 작은 알파벳이라 위치 충돌이 자주 일어나 경계 케이스가 잘 터진다
        return buildString { repeat(n) { append(alphabet[rng.nextInt(alphabet.length)]) } }
    }

    private fun randomBase(rng: Random): String = randomText(rng, rng.nextInt(0, 12))

    @Test
    fun `transform converges for many random concurrent op pairs (TP1)`() {
        val rng = Random(42) // 고정 seed → 재현 가능
        val trials = 20_000
        var insIns = 0; var insDel = 0; var delIns = 0; var delDel = 0
        var compositeProduced = 0

        repeat(trials) { t ->
            val base = randomBase(rng)
            val a = randomOp(rng, base)
            val b = randomOp(rng, base)

            // 케이스 분포 집계(경계 커버리지 확인용)
            when {
                a is TextOperation.Insert && b is TextOperation.Insert -> insIns++
                a is TextOperation.Insert && b is TextOperation.Delete -> insDel++
                a is TextOperation.Delete && b is TextOperation.Insert -> delIns++
                a is TextOperation.Delete && b is TextOperation.Delete -> delDel++
            }

            val aPrime = OperationalTransform.transform(a, b, aHasPriority = true)   // path2 에서 b 먼저
            val bPrime = OperationalTransform.transform(b, a, aHasPriority = false)  // path1 에서 a 먼저
            if (aPrime is TextOperation.Composite || bPrime is TextOperation.Composite) compositeProduced++

            val path1 = OperationalTransform.apply(OperationalTransform.apply(base, a), bPrime)
            val path2 = OperationalTransform.apply(OperationalTransform.apply(base, b), aPrime)

            assertEquals(
                path1, path2,
                "DIVERGED at trial $t\n base=\"$base\"\n a=$a\n b=$b\n a'=$aPrime\n b'=$bPrime\n path1=\"$path1\"\n path2=\"$path2\"",
            )
        }

        // 4 케이스가 모두 충분히 발생했는지(테스트가 의미 있게 커버하는지) 확인
        assertTrue(insIns > 100, "insert/insert coverage too low: $insIns")
        assertTrue(insDel > 100, "insert/delete coverage too low: $insDel")
        assertTrue(delIns > 100, "delete/insert coverage too low: $delIns")
        assertTrue(delDel > 100, "delete/delete coverage too low: $delDel")
        assertTrue(compositeProduced > 0, "edge rule (delete split → Composite) never exercised")
        println("OT convergence OK: $trials trials | ins/ins=$insIns ins/del=$insDel del/ins=$delIns del/del=$delDel | composite=$compositeProduced")
    }

    @Test
    fun `documented edge case insert inside concurrent delete range converges`() {
        // ADR-0003 예시: base="ABCDE", ins=Insert(2,"X"), del=Delete(1,3)
        val base = "ABCDE"
        val ins = TextOperation.Insert(2, "X")
        val del = TextOperation.Delete(1, 3)

        val insPrime = OperationalTransform.transform(ins, del, aHasPriority = true)
        val delPrime = OperationalTransform.transform(del, ins, aHasPriority = false)

        val pathDelFirst = OperationalTransform.apply(OperationalTransform.apply(base, del), insPrime)
        val pathInsFirst = OperationalTransform.apply(OperationalTransform.apply(base, ins), delPrime)

        assertEquals(pathDelFirst, pathInsFirst)
        assertEquals("AXE", pathDelFirst) // insert 가 살아남아 A 와 E 사이에 X
        // delete 쪽은 split 되어 Composite 가 나와야 한다
        assertTrue(delPrime is TextOperation.Composite, "delete vs inside-insert should split into Composite")
    }
}
