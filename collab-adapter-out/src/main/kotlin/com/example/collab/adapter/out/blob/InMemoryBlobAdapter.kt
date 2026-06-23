package com.example.collab.adapter.out.blob

import com.example.collab.application.port.out.BlobStorePort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 기본(zero-infra) blob 어댑터: ConcurrentHashMap 인메모리 저장.
 * collab.blob 가 없거나 'memory' 면 활성화(matchIfMissing=true).
 */
@Component
@ConditionalOnProperty(name = ["collab.blob"], havingValue = "memory", matchIfMissing = true)
class InMemoryBlobAdapter : BlobStorePort {
    private val store = ConcurrentHashMap<String, ByteArray>()
    override fun put(key: String, bytes: ByteArray) { store[key] = bytes.copyOf() }
    override fun get(key: String): ByteArray? = store[key]?.copyOf()
    override fun delete(key: String) { store.remove(key) }
}
