package com.example.moniq

import com.example.moniq.network.OpenSubsonicApi
import com.example.moniq.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.math.BigInteger
import java.security.MessageDigest

class AuthRepository {
    /**
     * Attempts to authenticate against an OpenSubsonic server using the ping endpoint.
     * Always returns success, but logs/warns if credentials appear invalid.
     */
    fun login(
        host: String,
        username: String,
        password: String,
        legacy: Boolean,
        callback: (Boolean, String?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val base = normalizeBaseUrl(host)
                val retrofit = RetrofitClient.create(base)
                val api = retrofit.create(OpenSubsonicApi::class.java)
                val pwParam = if (legacy) password else md5(password)
                val resp = api.ping(username, pwParam)
                val body = resp.body() ?: ""

                val isHttpOk = resp.isSuccessful
                // Support both XML and JSON ping responses. JSON may contain "status":"ok".
                val hasOkStatus = body.contains("status=\"ok\"") || body.contains("status='ok'") || body.contains("\"status\":\"ok\"")

                withContext(Dispatchers.Main) {
                    if (isHttpOk && hasOkStatus) {
                        // Normal success: save session and return success
                        SessionManager.host = base
                        SessionManager.username = username
                        SessionManager.password = password
                        SessionManager.legacy = legacy
                        callback(true, null)
                    } else if (isHttpOk && !hasOkStatus) {
                        // HTTP 200 but server returned an error payload. Allow the user through
                        // but surface a warning message. Save the session so app can continue.
                        val message = parseErrorFromBody(body) ?: "Server returned an error"
                        SessionManager.host = base
                        SessionManager.username = username
                        SessionManager.password = password
                        SessionManager.legacy = legacy
                        callback(true, message)
                    } else {
                        // Non-2xx HTTP or other failure
                        val message = parseErrorFromBody(body) ?: "HTTP ${resp.code()}"
                        callback(false, message)
                    }
                }
            } catch (e: HttpException) {
                withContext(Dispatchers.Main) { callback(false, "HTTP error: ${e.code()}") }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { callback(false, t.message ?: "Unknown error") }
            }
        }
    }

    private fun normalizeBaseUrl(host: String): String {
        var h = host.trim()
        if (!h.startsWith("http://") && !h.startsWith("https://")) {
            h = "https://$h"
        }
        if (!h.endsWith("/")) h += "/"
        return h
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        val bigInt = BigInteger(1, digest)
        return String.format("%032x", bigInt)
    }

    private fun parseErrorFromBody(body: String): String? {
        // Try JSON first: look for "message" or "error" fields
        try {
            val jsonRegex = Regex("""\"message\"\s*:\s*\"([^\"]*)\"|\"error\"\s*:\s*\"([^\"]*)\"""")
            val jm = jsonRegex.find(body)
            if (jm != null) {
                return jm.groups[1]?.value ?: jm.groups[2]?.value
            }
        } catch (_: Exception) {}

        // Try simple XML attributes like message="..." or message='...'
        val regex = Regex("message=(?:\"([^\"]*)\"|'([^']*)')")
        val match = regex.find(body)
        if (match != null) {
            val g1 = match.groups[1]?.value
            val g2 = match.groups[2]?.value
            return g1 ?: g2
        }

        // Try <error> inner text in XML
        if (body.contains("status=\"failed\"") || body.contains("status='failed'")) {
            val inner = Regex("<error[^>]*>(.*?)</error>", RegexOption.DOT_MATCHES_ALL).find(body)
            if (inner != null) return inner.groups[1]?.value?.trim()
        }
        return null
    }
}