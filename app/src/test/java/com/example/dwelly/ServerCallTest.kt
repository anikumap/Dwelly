package com.example.dwelly

import com.example.dwelly.data.CONNECTION_ERROR
import com.example.dwelly.data.ServerError
import com.example.dwelly.data.serverCall
import com.example.dwelly.model.DwellyError
import io.github.jan.supabase.exceptions.HttpRequestException
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCallTest {
    @Test
    fun connectionFailureShowsAPlainMessageWithoutHeaders() = runBlocking {
        val leaky = "Request failed\nHeaders: [Authorization=[Bearer secret-token], apikey=[secret-key]]"
        val error = serverCall<Unit> { throw HttpRequestException(leaky, HttpRequestBuilder()) }.exceptionOrNull()

        assertTrue(error is ServerError)
        assertEquals(CONNECTION_ERROR, error?.message)
        assertFalse(error?.message.orEmpty().contains("secret"))
    }

    @Test
    fun specRepliesPassThrough() = runBlocking {
        val error = serverCall<Unit> { throw DwellyError.ListingUnavailable() }.exceptionOrNull()
        assertTrue(error is DwellyError.ListingUnavailable)
    }
}
