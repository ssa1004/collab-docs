package com.example.collab.application.port.out

/**
 * 원본 문서 blob 저장 out port. 어댑터: S3/DynamoDB(prod) / in-memory(dev).
 * 큰 스냅샷/첨부 등 본문 외 바이너리 보관용.
 */
interface BlobStorePort {
    fun put(key: String, bytes: ByteArray)
    fun get(key: String): ByteArray?
    fun delete(key: String)
}
