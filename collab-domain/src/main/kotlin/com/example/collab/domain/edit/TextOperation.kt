package com.example.collab.domain.edit

/**
 * 협업 텍스트 편집의 원자 연산(Operational Transform 대상).
 *
 * 문서를 하나의 문자열로 보고, 위치 기반 insert/delete 두 종류만 다룬다(plain text 범위).
 * 모든 position 은 적용 시점 문서의 0-based 문자 인덱스다.
 *
 * 정합성 규칙(구현/테스트가 지켜야 할 계약):
 *  - apply(doc, op) 는 op 가 가리키는 위치가 문서 길이 범위를 벗어나면 [IllegalOperationException].
 *  - [OperationalTransform.transform] 은 동시 두 연산 a,b 에 대해
 *    apply(apply(doc,a), transform(b against a)) == apply(apply(doc,b), transform(a against b)) (수렴, TP1)을 만족해야 한다.
 */
sealed interface TextOperation {
    val position: Int

    /** position 위치에 text 를 삽입 */
    data class Insert(override val position: Int, val text: String) : TextOperation {
        init { require(position >= 0) { "position must be >= 0" }; require(text.isNotEmpty()) { "insert text must be non-empty" } }
        val length: Int get() = text.length
    }

    /** position 부터 length 글자를 삭제 */
    data class Delete(override val position: Int, val length: Int) : TextOperation {
        init { require(position >= 0) { "position must be >= 0" }; require(length > 0) { "delete length must be > 0" } }
        val endExclusive: Int get() = position + length
    }

    /**
     * 여러 원자 연산을 "이 순서 그대로 차례로" 적용하는 합성 연산.
     *
     * transform 결과 단일 insert/delete 로 표현할 수 없는 경계 케이스
     * (동시 delete 범위 내부로 insert 가 들어와 delete 가 split 되는 경우, ADR-0003)에서만 생성된다.
     * ops 안의 각 연산의 position 은 "직전 연산까지 적용된 문서" 기준이다.
     * 빈 Composite 는 no-op 으로 허용한다.
     *
     * position 은 sealed 계약상 필요하지만 Composite 자체엔 의미가 없으므로 0 으로 고정한다.
     */
    data class Composite(val ops: List<TextOperation>) : TextOperation {
        override val position: Int get() = 0
    }
}

class IllegalOperationException(message: String) : RuntimeException(message)
