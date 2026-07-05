package com.ai.notes.data.ai

import java.net.SocketTimeoutException
import java.net.UnknownHostException

object ErrorMapper {
    fun mapHttpCode(code: Int): AppError = when (code) {
        401 -> AppError.InvalidApiKey
        400 -> AppError.InvalidRequest
        429 -> AppError.RateLimited
        in 500..599 -> AppError.ServerError
        else -> AppError.UnknownNetwork
    }

    fun mapThrowable(t: Throwable): AppError = when (t) {
        is UnknownHostException -> AppError.NoInternet
        is SocketTimeoutException -> AppError.Timeout
        else -> AppError.UnknownNetwork
    }
}
