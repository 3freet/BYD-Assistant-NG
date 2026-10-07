package com.bydassistantng.gemini

import com.bydassistantng.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ConnectionFailuresTest {
    private fun kind(t: Throwable?, http: Int? = null) = ConnectionFailures.classify(t, http)

    @Test
    fun aDeadHotspotIsNoNetwork() {
        assertEquals(FailureKind.NO_NETWORK, kind(UnknownHostException("generativelanguage.googleapis.com")))
        assertEquals(FailureKind.NO_NETWORK, kind(ConnectException("Failed to connect")))
        assertEquals(FailureKind.NO_NETWORK, kind(SocketTimeoutException("timeout")))
    }

    @Test
    fun theRawTextSeenOnTheCarIsNoNetwork() {
        // "Software caused connection abort" was the unreadable message shown when the phone's hotspot dropped.
        assertEquals(FailureKind.NO_NETWORK, kind(SocketException("Software caused connection abort")))
        assertEquals(FailureKind.NO_NETWORK, kind(IOException("Network is unreachable")))
        assertEquals(FailureKind.NO_NETWORK, kind(IOException("wrapper", SocketException("Connection reset"))))
    }

    @Test
    fun anAnswerFromTheServerIsNotNoNetwork() {
        assertEquals(FailureKind.API_KEY_REJECTED, kind(IOException("Expected HTTP 101"), http = 403))
        assertEquals(FailureKind.API_KEY_REJECTED, kind(IOException("Expected HTTP 101"), http = 400))
        assertEquals(FailureKind.BUSY, kind(IOException("Expected HTTP 101"), http = 429))
    }

    @Test
    fun unknownErrorsAreOther() {
        assertEquals(FailureKind.OTHER, kind(IllegalStateException("something odd")))
        assertEquals(FailureKind.OTHER, kind(null))
    }

    @Test
    fun serverClosesAreClassifiedByTheirReason() {
        assertEquals(FailureKind.API_KEY_REJECTED, ConnectionFailures.classifyClose(1008, "API key not valid"))
        assertEquals(FailureKind.BUSY, ConnectionFailures.classifyClose(1011, "RESOURCE_EXHAUSTED: quota"))
        assertEquals(FailureKind.SERVER_REJECTED, ConnectionFailures.classifyClose(1007, "Request contains an invalid argument."))
        assertEquals(FailureKind.OTHER, ConnectionFailures.classifyClose(1006, ""))
    }

    @Test
    fun everyFailureKindHasItsOwnMessageResource() {
        assertEquals(R.string.fail_no_network, UserMessages.forFailure(FailureKind.NO_NETWORK))
        assertEquals(R.string.fail_api_key, UserMessages.forFailure(FailureKind.API_KEY_REJECTED))
        assertEquals(R.string.fail_busy, UserMessages.forFailure(FailureKind.BUSY))
        // The two kinds the user can do nothing specific about share the generic message.
        assertEquals(R.string.fail_generic, UserMessages.forFailure(FailureKind.SERVER_REJECTED))
        assertEquals(R.string.fail_generic, UserMessages.forFailure(FailureKind.OTHER))
        assertTrue(FailureKind.entries.all { UserMessages.forFailure(it) != 0 })
    }
}
