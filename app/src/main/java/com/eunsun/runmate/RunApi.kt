package com.eunsun.runmate

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class RoutePoint(val latitude: Double, val longitude: Double, val timestamp: Long)
internal data class RunRecord(
    val id: Long,
    val distanceKm: Double,
    val durationSeconds: Long,
    val paceSecondsPerKm: Int,
    val calories: Int,
    val startedAt: String,
    val points: List<RoutePoint> = emptyList()
) {
    val distanceLabel get() = String.format("%.2fkm", distanceKm)
    val durationLabel get() = "${durationSeconds / 60}분 ${durationSeconds % 60}초"
    val paceLabel get() = if (paceSecondsPerKm <= 0) "--'--\"" else "%d'%02d\"".format(paceSecondsPerKm / 60, paceSecondsPerKm % 60)
}

internal object RunApi {
    private const val BASE = "http://192.168.0.22:8080/api/runs"

    suspend fun list(): List<RunRecord> = request("GET", null) { body ->
        val array = JSONArray(body)
        (0 until array.length()).map { i -> array.getJSONObject(i).toRecord() }
    }

    suspend fun create(distanceKm: Double, durationSeconds: Long, calories: Int, startedAt: String, points: List<RoutePoint>): RunRecord =
        request("POST", JSONObject().apply {
            put("distanceKm", distanceKm); put("durationSeconds", durationSeconds); put("calories", calories); put("startedAt", startedAt)
            put("points", JSONArray().apply { points.forEach { p -> put(JSONObject().put("latitude", p.latitude).put("longitude", p.longitude).put("timestamp", p.timestamp)) } })
        }) { JSONObject(it).toRecord() }

    private fun JSONObject.toRecord() = RunRecord(
        getLong("id"), getDouble("distanceKm"), getLong("durationSeconds"), getInt("paceSecondsPerKm"), getInt("calories"), optString("startedAt"),
        (optJSONArray("points") ?: JSONArray()).let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let { RoutePoint(it.getDouble("latitude"), it.getDouble("longitude"), it.optLong("timestamp")) } } }
    )

    private suspend fun <T> request(method: String, payload: JSONObject?, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        fun connect() = (URL(BASE).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 10000; readTimeout = 10000
            AuthApi.bearerToken()?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (payload != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
        }
        var c = connect()
        try {
            if (payload != null) c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            var code = c.responseCode
            if (code == 401 && AuthApi.refreshAccessToken()) {
                c.disconnect(); c = connect()
                if (payload != null) c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                code = c.responseCode
            }
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "러닝 기록 요청 실패 ($code): $body" }
            parse(body)
        } finally { c.disconnect() }
    }
}

internal fun distanceMeters(a: Location, b: Location): Float = a.distanceTo(b)
