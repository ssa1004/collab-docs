package com.example.collab.adapter.out.blob

import com.example.collab.application.port.out.BlobStorePort
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI

/**
 * prod blob 어댑터: AWS SDK v2 S3Client 로 put/get/delete.
 * collab.blob=s3 일 때만 활성화된다(dev 는 InMemoryBlobAdapter — 키/네트워크 불필요).
 *
 * 설정:
 *  - collab.blob.s3.bucket    (필수)
 *  - collab.blob.s3.region    (기본 us-east-1)
 *  - collab.blob.s3.endpoint  (선택; MinIO/LocalStack 등 S3 호환 엔드포인트면 path-style 로 접근)
 * 자격증명은 기본 제공자 체인(env/프로파일/인스턴스 역할)에서 가져온다.
 */
@Component
@ConditionalOnProperty(name = ["collab.blob"], havingValue = "s3")
class S3BlobAdapter(
    @Value("\${collab.blob.s3.bucket}") private val bucket: String,
    @Value("\${collab.blob.s3.region:us-east-1}") region: String,
    @Value("\${collab.blob.s3.endpoint:}") endpoint: String,
) : BlobStorePort {

    private val log = LoggerFactory.getLogger(javaClass)

    private val s3: S3Client = S3Client.builder()
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .apply {
            if (endpoint.isNotBlank()) {
                endpointOverride(URI.create(endpoint))
                serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            }
        }
        .build()

    init {
        log.info("S3BlobAdapter active (collab.blob=s3, bucket={}).", bucket)
    }

    override fun put(key: String, bytes: ByteArray) {
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromBytes(bytes),
        )
    }

    override fun get(key: String): ByteArray? =
        try {
            s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()
        } catch (e: NoSuchKeyException) {
            null
        }

    override fun delete(key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
    }

    @PreDestroy
    private fun close() = s3.close()
}
