package com.example.collab.adapter.`in`.web

import com.example.collab.adapter.`in`.web.dto.SearchHitResponse
import com.example.collab.application.SearchDocumentsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 문서 검색 REST 컨트롤러. 요청자 소유 문서 범위에서 키워드 검색. */
@RestController
@RequestMapping("/api/search")
class SearchController(
    private val searchDocuments: SearchDocumentsService,
) {
    @GetMapping
    fun search(
        @RequestParam q: String,
        @RequestParam(defaultValue = "10") limit: Int,
    ): List<SearchHitResponse> =
        searchDocuments.search(q, CurrentUser.id(), limit).map { SearchHitResponse.of(it) }
}
