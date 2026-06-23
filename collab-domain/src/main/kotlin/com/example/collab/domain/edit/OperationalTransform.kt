package com.example.collab.domain.edit

/**
 * 서버 권위 Operational Transform 엔진 (plain text insert/delete).
 *
 * 동시 편집 정합성의 핵심. 프레임워크 의존성 없이 순수 함수로 구현하고
 * 단위/속성 테스트로 수렴(convergence)을 증명한다.
 *
 * 계약:
 *  1. apply(doc, op): op 를 문서에 적용한 새 문자열. 범위 위반 시 [IllegalOperationException].
 *  2. transform(a, b, aHasPriority): 같은 base 문서에서 동시에 만들어진 a, b 에 대해
 *     b 가 먼저 적용된 뒤 a 를 적용할 수 있도록 a 를 변환한 a' 를 돌려준다.
 *     수렴(TP1) 보장: apply(apply(doc, b), transform(a, b, true)) ==
 *                    apply(apply(doc, a), transform(b, a, false))
 *     (동위치 insert tie-break 는 aHasPriority 로 결정론 처리)
 *  3. insert 가 동시 delete 범위 "내부"에 들어오는 경계 케이스는 ADR-0003 규칙
 *     ("insert survive + delete split")을 양측이 동일하게 적용해 수렴한다.
 */
object OperationalTransform {

    /** op(Insert/Delete/Composite)를 문서에 적용한 새 문자열. */
    fun apply(document: String, op: TextOperation): String = when (op) {
        is TextOperation.Insert -> {
            if (op.position > document.length) throw IllegalOperationException("insert position ${op.position} > length ${document.length}")
            document.substring(0, op.position) + op.text + document.substring(op.position)
        }
        is TextOperation.Delete -> {
            if (op.endExclusive > document.length) throw IllegalOperationException("delete range ${op.position}..${op.endExclusive} exceeds length ${document.length}")
            document.substring(0, op.position) + document.substring(op.endExclusive)
        }
        // Composite: 각 조각을 "직전 조각까지 적용된 문서" 기준으로 순차 적용
        is TextOperation.Composite -> op.ops.fold(document) { acc, child -> apply(acc, child) }
    }

    /**
     * a 를 동시 연산 b 에 대해 inclusion-transform 한 a' 를 돌려준다.
     * 즉, b 가 먼저 적용된 문서 위에서 a 의 "원래 의도"를 보존하도록 a 의 위치/범위를 보정한다.
     *
     * @param aHasPriority 동위치(같은 position) insert tie-break 시 a 가 왼쪽(먼저)인지 여부.
     *        보통 서버가 먼저 커밋된 쪽(b)에 우선권을 주므로, 들어오는 op a 는 aHasPriority=false 로 변환된다.
     */
    fun transform(a: TextOperation, b: TextOperation, aHasPriority: Boolean): TextOperation = when {
        // Composite 가 끼면 각 원자 연산 단위로 재귀 처리
        a is TextOperation.Composite -> transformCompositeAgainst(a, b, aHasPriority)
        b is TextOperation.Composite -> transformAgainstComposite(a, b, aHasPriority)

        a is TextOperation.Insert && b is TextOperation.Insert -> insVsIns(a, b, aHasPriority)
        a is TextOperation.Insert && b is TextOperation.Delete -> insVsDel(a, b)
        a is TextOperation.Delete && b is TextOperation.Insert -> delVsIns(a, b)
        a is TextOperation.Delete && b is TextOperation.Delete -> delVsDel(a, b)
        else -> error("unreachable")
    }

    // ---- 4 케이스 ---------------------------------------------------------

    /**
     * Insert vs Insert.
     * - a.pos < b.pos: b 는 a 뒤쪽 → a 그대로.
     * - a.pos > b.pos: b 가 a 앞에 |b| 글자 끼움 → a 를 오른쪽으로 |b| 만큼 민다.
     * - a.pos == b.pos: tie-break. a 가 우선이면 그대로, 아니면 b 뒤로 민다(결정론).
     */
    private fun insVsIns(a: TextOperation.Insert, b: TextOperation.Insert, aHasPriority: Boolean): TextOperation {
        val shift = when {
            a.position < b.position -> 0
            a.position > b.position -> b.length
            else -> if (aHasPriority) 0 else b.length
        }
        return if (shift == 0) a else a.copy(position = a.position + shift)
    }

    /**
     * Insert(a) vs Delete(b).
     * - a.pos <= b.pos: a 는 삭제 시작 이전 → 그대로.
     * - a.pos >= b.end: a 는 삭제 구간 이후 → 왼쪽으로 |b| 만큼 당김.
     * - b.pos < a.pos < b.end: a 가 삭제 구간 "내부" → ADR-0003: 살아남되 삭제 시작점으로 클램프.
     */
    private fun insVsDel(a: TextOperation.Insert, b: TextOperation.Delete): TextOperation = when {
        a.position <= b.position -> a
        a.position >= b.endExclusive -> a.copy(position = a.position - b.length)
        else -> a.copy(position = b.position) // 내부 → delete 시작점으로 클램프 (survive)
    }

    /**
     * Delete(a) vs Insert(b).
     * - b.pos >= a.end: insert 는 삭제 구간 이후 → a 그대로.
     * - b.pos <= a.pos: insert 가 삭제 구간 앞 → a 를 오른쪽으로 |b| 만큼 민다.
     * - a.pos < b.pos < a.end: insert 가 삭제 구간 "내부" → ADR-0003: a 는 끼어든 글자를 건너뛰며 split.
     *   앞 조각 [a.pos, b.pos), 뒤 조각 [b.pos+|b|, a.end+|b|). 인덱스 안정성을 위해 뒤 조각을 먼저 적용.
     */
    private fun delVsIns(a: TextOperation.Delete, b: TextOperation.Insert): TextOperation = when {
        b.position >= a.endExclusive -> a
        b.position <= a.position -> a.copy(position = a.position + b.length)
        else -> {
            val front = TextOperation.Delete(a.position, b.position - a.position)
            val back = TextOperation.Delete(b.position + b.length, a.endExclusive - b.position)
            TextOperation.Composite(listOf(back, front)) // 뒤 → 앞 순서로 적용해야 앞 조각 인덱스 불변
        }
    }

    /**
     * Delete(a) vs Delete(b). 두 삭제 구간이 겹칠 수 있으므로 교집합을 빼고 남은 부분만 a' 가 삭제한다.
     * b 가 먼저 적용되면 b 가 지운 부분은 이미 사라졌으니 a 는 그 부분을 또 지우면 안 된다.
     * 남은 a' 의 길이/위치를 구간 산술로 계산한다.
     */
    private fun delVsDel(a: TextOperation.Delete, b: TextOperation.Delete): TextOperation {
        val aStart = a.position; val aEnd = a.endExclusive
        val bStart = b.position; val bEnd = b.endExclusive

        // 겹침 구간
        val overlapStart = maxOf(aStart, bStart)
        val overlapEnd = minOf(aEnd, bEnd)
        val overlap = (overlapEnd - overlapStart).coerceAtLeast(0)

        // a 의 남은 삭제 길이
        val remaining = a.length - overlap
        if (remaining <= 0) return TextOperation.Composite(emptyList()) // a 가 b 에 완전히 포함 → no-op

        // 새 시작 위치: a 시작점 앞에서 b 가 지운 글자 수만큼 왼쪽으로 당긴다.
        // (b 가 a 시작점보다 앞에서 지운 부분 = [bStart, min(bEnd, aStart)) 의 길이)
        val deletedBeforeAStart = (minOf(bEnd, aStart) - bStart).coerceAtLeast(0)
        val newStart = aStart - deletedBeforeAStart
        return TextOperation.Delete(newStart, remaining)
    }

    // ---- Composite 처리 ---------------------------------------------------

    /**
     * a(Composite)를 b 에 대해 transform.
     * a.ops 를 순서대로 b 에 대해 변환하되, 앞 조각이 변환되며 b' 도 따라 변해야 하므로
     * "각 조각을 b 에 대해 transform" + "b 를 그 조각에 대해 transform" 을 누적한다.
     */
    private fun transformCompositeAgainst(a: TextOperation.Composite, b: TextOperation, aHasPriority: Boolean): TextOperation {
        var curB = b
        val out = ArrayList<TextOperation>(a.ops.size)
        for (child in a.ops) {
            out += transform(child, curB, aHasPriority)
            curB = transform(curB, child, !aHasPriority)
        }
        return flatten(TextOperation.Composite(out))
    }

    /** a 를 b(Composite)에 대해 transform: b.ops 를 차례로 누적 적용. */
    private fun transformAgainstComposite(a: TextOperation, b: TextOperation.Composite, aHasPriority: Boolean): TextOperation {
        var curA = a
        for (child in b.ops) {
            curA = transform(curA, child, aHasPriority)
        }
        return curA
    }

    /** 중첩 Composite 를 한 겹으로 펴고, 단일/빈 케이스를 단순화한다. */
    private fun flatten(c: TextOperation.Composite): TextOperation {
        val flat = ArrayList<TextOperation>()
        fun walk(op: TextOperation) {
            when (op) {
                is TextOperation.Composite -> op.ops.forEach(::walk)
                else -> flat += op
            }
        }
        c.ops.forEach(::walk)
        return when (flat.size) {
            0 -> TextOperation.Composite(emptyList())
            1 -> flat[0]
            else -> TextOperation.Composite(flat)
        }
    }
}
