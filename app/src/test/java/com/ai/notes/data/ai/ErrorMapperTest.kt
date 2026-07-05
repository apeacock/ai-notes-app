package com.ai.notes.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException

class ErrorMapperTest {
    @Test
    fun `maps 401 to InvalidApiKey`() {
        assertEquals(AppError.InvalidApiKey, ErrorMapper.mapHttpCode(401))
    }

    @Test
    fun `maps 400 to InvalidRequest`() {
        assertEquals(AppError.InvalidRequest, ErrorMapper.mapHttpCode(400))
    }

    @Test
    fun `maps 429 to RateLimited`() {
        assertEquals(AppError.RateLimited, ErrorMapper.mapHttpCode(429))
    }

    @Test
    fun `maps 500 to ServerError`() {
        assertEquals(AppError.ServerError, ErrorMapper.mapHttpCode(500))
    }

    @Test
    fun `maps 503 to ServerError`() {
        assertEquals(AppError.ServerError, ErrorMapper.mapHttpCode(503))
    }

    @Test
    fun `maps unrecognized code to UnknownNetwork`() {
        assertEquals(AppError.UnknownNetwork, ErrorMapper.mapHttpCode(418))
    }

    @Test
    fun `maps UnknownHostException to NoInternet`() {
        assertEquals(AppError.NoInternet, ErrorMapper.mapThrowable(UnknownHostException()))
    }

    @Test
    fun `maps SocketTimeoutException to Timeout`() {
        assertEquals(AppError.Timeout, ErrorMapper.mapThrowable(SocketTimeoutException()))
    }

    @Test
    fun `maps generic IOException to UnknownNetwork`() {
        assertEquals(AppError.UnknownNetwork, ErrorMapper.mapThrowable(IOException("boom")))
    }

    @Test
    fun `maps unknown throwable to UnknownNetwork`() {
        assertTrue(ErrorMapper.mapThrowable(RuntimeException("boom")) is AppError.UnknownNetwork)
    }
}
