package com.example.collab.adapter.`in`.web

import com.example.collab.application.port.out.DocumentNotFoundException
import com.example.collab.domain.edit.IllegalOperationException
import com.example.collab.domain.sharing.AccessDeniedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/**
 * RFC7807 ProblemDetail 전역 에러 매핑.
 *
 *  - AccessDeniedException(도메인)           -> 403 Forbidden
 *  - DocumentNotFoundException               -> 404 Not Found
 *  - IllegalOperationException(OT 범위 위반)  -> 409 Conflict (동시성/상태 충돌)
 *  - IllegalArgumentException / 검증 실패      -> 400 Bad Request
 *  - 그 외                                    -> 500 Internal Server Error
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "Access Denied", ex.message, "access-denied").apply {
            setProperty("action", ex.action)
            setProperty("documentId", ex.documentId.value)
        }

    @ExceptionHandler(DocumentNotFoundException::class)
    fun handleNotFound(ex: DocumentNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "Document Not Found", ex.message, "document-not-found").apply {
            setProperty("documentId", ex.id.value)
        }

    @ExceptionHandler(IllegalOperationException::class)
    fun handleIllegalOperation(ex: IllegalOperationException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "Illegal Operation", ex.message, "illegal-operation")

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Invalid Request", ex.message, "invalid-request")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ProblemDetail {
        val fields = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        return problem(HttpStatus.BAD_REQUEST, "Validation Failed", "request body validation failed", "validation-failed")
            .apply { setProperty("fields", fields) }
    }

    @ExceptionHandler(Exception::class)
    fun handleOther(ex: Exception): ProblemDetail {
        log.error("unhandled exception", ex)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", ex.message, "internal-error")
    }

    private fun problem(status: HttpStatus, title: String, detail: String?, type: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: title).apply {
            this.title = title
            this.type = URI.create("urn:collab-docs:problem:$type")
        }
}
