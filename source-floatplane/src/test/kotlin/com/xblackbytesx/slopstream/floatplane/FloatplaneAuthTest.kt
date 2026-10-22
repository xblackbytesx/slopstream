package com.xblackbytesx.slopstream.floatplane

import com.xblackbytesx.slopstream.core.SourceAuthException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FloatplaneAuthTest {
    private val server = MockWebServer()
    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun auth() = FloatplaneAuth(OkHttpClient(), "slopstream-test", server.url("/realms/fp").toString().trimEnd('/'), clock)

    @Test
    fun deviceLoginWaitsForApprovalAndBacksOffWhenAsked() = runTest {
        server.enqueue(json(200, """{"device_code":"dev","user_code":"ABCD-EFGH","verification_uri":"https://fp.example/link","verification_uri_complete":"https://fp.example/link?code=ABCD-EFGH","expires_in":600,"interval":5}"""))
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))
        server.enqueue(json(400, """{"error":"slow_down"}"""))
        server.enqueue(json(200, """{"access_token":"acc","refresh_token":"ref","expires_in":300}"""))

        val a = auth()
        val code = a.startDeviceLogin()
        assertEquals("ABCD-EFGH", code.userCode)
        assertEquals("https://fp.example/link?code=ABCD-EFGH", code.verificationUriComplete)

        val tokens = a.awaitDeviceLogin(code)
        assertEquals(FloatplaneAuth.Tokens("acc", "ref", now.epochSecond + 300), tokens)

        val start = server.takeRequest()
        assertEquals("/realms/fp/protocol/openid-connect/auth/device", start.url.encodedPath)
        val body = start.body!!.utf8()
        assertEquals(true, "client_id=slopstream-test" in body && "offline_access" in body)
        val poll = server.takeRequest()
        assertEquals(true, "device_code=dev" in poll.body!!.utf8())
    }

    @Test
    fun declinedAndUnknownClientAreAuthErrors() = runTest {
        server.enqueue(json(401, """{"error":"invalid_client","error_description":"Invalid client or Invalid client credentials"}"""))
        assertThrowsSuspend<SourceAuthException> { auth().startDeviceLogin() }

        server.enqueue(json(400, """{"error":"access_denied"}"""))
        val code = FloatplaneAuth.DeviceCode("dev", "X", "https://fp.example", null, 1, now.plusSeconds(60))
        assertThrowsSuspend<SourceAuthException> { auth().awaitDeviceLogin(code) }
    }

    @Test
    fun authorizerRefreshesNearExpiryAndKeepsTheRefreshToken() = runTest {
        server.enqueue(json(200, """{"access_token":"new","expires_in":300}"""))
        var stored = FloatplaneAuth.Tokens("old", "ref", now.epochSecond + 30)
        val authorizer = TokenAuthorizer(auth(), { stored }, { stored = it }, clock)

        val req = Request.Builder().url("https://www.floatplane.com/api/v3/user/self")
        authorizer.authorize(req)

        assertEquals("Bearer new", req.build().header("Authorization"))
        assertEquals(FloatplaneAuth.Tokens("new", "ref", now.epochSecond + 300), stored)
        assertEquals(true, "grant_type=refresh_token" in server.takeRequest().body!!.utf8())

        // Fresh enough: no second refresh.
        authorizer.authorize(Request.Builder().url("https://www.floatplane.com/x"))
        assertEquals(1, server.requestCount)
    }
}
