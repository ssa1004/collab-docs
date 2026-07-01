package com.example.collab.application.port.out

/**
 * 원본 문서 blob 저장 out port. 어댑터: S3/DynamoDB(prod) / in-memory(dev).
 * 큰 스냅샷/첨부 등 본문 외 바이너리 보관용. (현재 소비하는 use case 는 없음 — 향후 기능용으로
 * 포트/어댑터 교체 지점만 먼저 마련해 둔 시임.)
 */
interface BlobStorePort {
    fun put(key: String, bytes: ByteArray)
    fun get(key: String): ByteArray?
    fun delete(key: String)
}
