package com.hamhuo.tplanner.syncv3

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 极简 HTTP 客户端接口(上传器/安装器只依赖它,可注入测试)。
 * 响应以原始字节承载(gzip 快照是二进制,不能经过 String);
 * 默认实现用 HttpURLConnection(零依赖);将来可无痛换成 OkHttp。
 */
interface SyncHttpClient {
    fun post(url: String, body: String, idempotencyKey: String, timeoutMs: Int = 15_000): SyncHttpResponse
    fun get(url: String, timeoutMs: Int = 30_000): SyncHttpResponse
}

data class SyncHttpResponse(
    val code: Int,
    val bytes: ByteArray,
) {
    val isOk: Boolean get() = code in 200..299

    fun bodyText(): String = bytes.toString(StandardCharsets.UTF_8)

    fun json(): JSONObject = JSONObject(bodyText())

    companion object {
        fun text(code: Int, body: String): SyncHttpResponse =
            SyncHttpResponse(code, body.toByteArray(StandardCharsets.UTF_8))
    }
}

class HttpUrlConnectionSyncHttpClient : SyncHttpClient {
    override fun post(url: String, body: String, idempotencyKey: String, timeoutMs: Int): SyncHttpResponse =
        request("POST", url, body.toByteArray(StandardCharsets.UTF_8), idempotencyKey, timeoutMs)

    override fun get(url: String, timeoutMs: Int): SyncHttpResponse =
        request("GET", url, null, null, timeoutMs)

    private fun request(method: String, url: String, body: ByteArray?, idempotencyKey: String?, timeoutMs: Int): SyncHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/json, */*")
            if (body != null) connection.setRequestProperty("Content-Type", "application/json")
            if (idempotencyKey != null) connection.setRequestProperty("Idempotency-Key", idempotencyKey)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val responseBytes = stream?.readBytes() ?: ByteArray(0)
            return SyncHttpResponse(code, responseBytes)
        } finally {
            connection.disconnect()
        }
    }
}
