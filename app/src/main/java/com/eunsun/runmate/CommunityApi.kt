package com.eunsun.runmate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object CommunityApi {
    private const val BASE = "http://192.168.0.22:8080/api/community"

    suspend fun list(category: String? = null, title: String? = null): List<CommunityPost> {
        val query = buildList {
            if (!category.isNullOrBlank() && category != "전체") add("category=" + java.net.URLEncoder.encode(category, "UTF-8"))
            if (!title.isNullOrBlank()) add("title=" + java.net.URLEncoder.encode(title, "UTF-8"))
        }.joinToString("&").let { if (it.isBlank()) "" else "?$it" }
        val body = request("GET", "/posts$query") { it }
        val array = org.json.JSONArray(body)
        val posts = mutableListOf<CommunityPost>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val id = item.getLong("id").toInt()
            val commentBody = request("GET", "/posts/$id/comments") { it }
            val commentArray = org.json.JSONArray(commentBody)
            val comments = (0 until commentArray.length()).map { index ->
                val comment = commentArray.getJSONObject(index)
                CommunityComment(comment.getString("author"), comment.getString("content"))
            }
            val mediaArray = item.optJSONArray("attachments") ?: org.json.JSONArray()
            val media = (0 until mediaArray.length()).map { index ->
                val file = mediaArray.getJSONObject(index)
                CommunityAttachmentInfo(file.getLong("id"), file.getString("filename"), file.getString("contentType"))
            }
            posts += CommunityPost(
                id, item.getString("author"), item.getString("category"), item.getString("content"), "",
                item.getInt("likes"), isLiked = item.getBoolean("liked"), comments = comments,
                title = item.optString("title", ""), attachments = media,
                hasPhoto = media.isNotEmpty()
            )
        }
        return posts
    }

    suspend fun create(context: Context, category: String, title: String, content: String, attachments: List<CommunityAttachment>) = withContext(Dispatchers.IO) {
        fun upload(): HttpURLConnection {
            val boundary = "RunMate-${System.currentTimeMillis()}"
            val connection = (URL(BASE + "/posts/with-media").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${AuthApi.bearerToken().orEmpty()}")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            connection.outputStream.buffered().use { output ->
                fun field(name: String, value: String) {
                    output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
                }
                field("category", category)
                field("title", title)
                field("content", content)
                var totalBytes = 0L
                attachments.forEach { attachment ->
                    val data = context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
                        ?: error("첨부 파일을 읽을 수 없습니다: ${attachment.name}")
                    require(data.size <= 25 * 1024 * 1024) { "파일 하나는 25MB 이하로 첨부해주세요." }
                    totalBytes += data.size
                    require(totalBytes <= 100 * 1024 * 1024) { "첨부 파일 전체 크기는 100MB 이하로 첨부해주세요." }
                    val safeName = attachment.name.replace("\"", "_").replace("\r", "_").replace("\n", "_")
                    output.write("--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"$safeName\"\r\nContent-Type: ${attachment.mimeType}\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.write(data)
                    output.write("\r\n".toByteArray())
                }
                output.write("--$boundary--\r\n".toByteArray())
            }
            return connection
        }
        var connection = upload()
        try {
            var code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED && AuthApi.refreshAccessToken()) {
                connection.disconnect()
                connection = upload()
                code = connection.responseCode
            }
            if (code !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("커뮤니티 요청 실패 ($code): $message")
            }
        } finally {
            connection.disconnect()
        }
    }
    suspend fun update(id: Int, category: String, title: String, content: String) = request("PUT", "/posts/$id", JSONObject().put("category", category).put("title", title).put("content", content)) { Unit }
    suspend fun delete(id: Int) = request("DELETE", "/posts/$id") { Unit }
    suspend fun like(id: Int) = request("POST", "/posts/$id/likes", JSONObject()) { Unit }
    suspend fun comment(id: Int, content: String) = request("POST", "/posts/$id/comments", JSONObject().put("content", content)) { Unit }

    private suspend fun <T> request(method: String, path: String, json: JSONObject? = null, read: (String) -> T): T = withContext(Dispatchers.IO) {
        fun connect(): HttpURLConnection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 8000
            AuthApi.bearerToken()?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        fun writeBody(connection: HttpURLConnection) {
            if (json != null) connection.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        }
        var connection = connect()
        try {
            writeBody(connection)
            var code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED && AuthApi.refreshAccessToken()) {
                connection.disconnect()
                connection = connect()
                writeBody(connection)
                code = connection.responseCode
            }
            if (code !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("커뮤니티 요청 실패 ($code): $message")
            }
            val responseBody = if (code == HttpURLConnection.HTTP_NO_CONTENT) "" else connection.inputStream.bufferedReader().use { it.readText() }
            read(responseBody)
        } finally {
            connection.disconnect()
        }
    }}

