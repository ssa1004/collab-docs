package com.example.collab.adapter.out.blob

import com.example.collab.application.port.out.BlobStorePort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * prod blob 어댑터 스켈레톤. collab.blob=s3 일 때만 활성화된다.
 *
 * 실제 구현은 AWS SDK v2(software.amazon.awssdk:s3) S3Client 로 put/get/delete 를 채우는 자리.
 * dev 무의존 부팅이 1순위라 의존성을 들이지 않고 자리만 잡아 둔다(미구현은 정직하게 실패).
 */
@Component
@ConditionalOnProperty(name = ["collab.blob"], havingValue = "s3")
class S3BlobAdapter : BlobStorePort {
    private val log = LoggerFactory.getLogger(javaClass)

    init { log.warn("S3BlobAdapter active (collab.blob=s3) — prod skeleton, wire AWS SDK v2 S3Client here.") }

    override fun put(key: String, bytes: ByteArray) =
        throw NotImplementedError("S3BlobAdapter.put: wire AWS SDK v2 S3Client (prod).")

    override fun get(key: String): ByteArray? =
        throw NotImplementedError("S3BlobAdapter.get: wire AWS SDK v2 S3Client (prod).")

    override fun delete(key: String) =
        throw NotImplementedError("S3BlobAdapter.delete: wire AWS SDK v2 S3Client (prod).")
}
