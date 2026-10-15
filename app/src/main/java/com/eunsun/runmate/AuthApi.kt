package com.eunsun.runmate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class AuthUser(val id: String, val userId: String, val nickname: String)
internal data class AuthSession(val accessToken: String, val refreshToken: String, val user: AuthUser)

internal object AuthApi {
    private const val BASE = "http://192.168.0.22:8080/api/auth"
    @Volatile private var accessToken: String? = null
    @Volatile private var appContext: Context? = null

    fun bearerToken(): String? = accessToken

    fun setSession(context: Context, session: AuthSession) {
        appContext = context.applicationContext
        RefreshTokenStore.write(context, session.refreshToken)
        accessToken = session.accessToken
    }

    suspend fun signup(nickname: String, userId: String, password: String) {
        request("POST", "/signup", JSONObject().put("nickname", nickname).put("userId", userId).put("password", password))
    }

    suspend fun login(context: Context, userId: String, password: String): AuthSession {
        val session = parseSession(request("POST", "/login", JSONObject().put("userId", userId).put("password", password)))
        setSession(context, session)
        return session
    }

    suspend fun restore(context: Context): AuthSession? {
        appContext = context.applicationContext
        val refreshToken = RefreshTokenStore.read(context) ?: return null
        return try {
            parseSession(request("POST", "/refresh", JSONObject().put("refreshToken", refreshToken))).also { setSession(context, it) }
        } catch (error: Exception) {
            if (error is AuthRequestException && error.statusCode == 401) RefreshTokenStore.clear(context)
            accessToken = null
            null
        }
    }

    suspend fun refreshAccessToken(): Boolean = appContext?.let { restore(it) != null } ?: false

    suspend fun logout(context: Context) {
        val refreshToken = RefreshTokenStore.read(context)
        if (!refreshToken.isNullOrBlank()) runCatching { request("POST", "/logout", JSONObject().put("refreshToken", refreshToken)) }
        RefreshTokenStore.clear(context)
        accessToken = null
    }

    private fun parseSession(body: String): AuthSession {
        val json = JSONObject(body)
        val user = json.getJSONObject("user")
        return AuthSession(json.getString("accessToken"), json.getString("refreshToken"), AuthUser(user.getString("id"), user.getString("userId"), user.getString("nickname")))
    }

    private suspend fun request(method: String, path: String, payload: JSONObject): String = withContext(Dispatchers.IO) {
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message").takeIf(String::isNotBlank) }.getOrNull()
                throw AuthRequestException(code, message ?: when (code) {
                    401 -> "아이디 또는 비밀번호를 확인해주세요."
                    409 -> "이미 사용 중인 아이디입니다."
                    else -> "인증 서버 오류 ($code)"
                })
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}

internal class AuthRequestException(val statusCode: Int, message: String) : IOException(message)

