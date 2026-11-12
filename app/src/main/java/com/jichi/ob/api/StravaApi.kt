package com.jichi.ob.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Strava 网页端上传接口（零依赖 HttpURLConnection，不引入 OkHttp）
 *
 * 协议三步（2026-11 端点形态实测，未登录态：/upload/select 301 → 登录页）：
 *  1) GET  /upload/select          抓 <meta name="csrf-token" content="...">；3xx 一律判"登录失效"
 *  2) POST /upload/files           multipart：files[] + _method=POST + authenticity_token，带 Cookie/移动端UA/X-Requested-With/Referer → 返回 upload id
 *  3) GET  /upload/progress.json?ids[]=<id>   每 2s 轮询，最多 12 次；complete/done/success 成功，error 取文案
 *
 * 结果三态：success / pending（已上传未确认，不得压成 success）/ skipped。
 */
class StravaApi(
    /** 端点根地址：生产固定 strava.com；JVM 单测可注入本地假服务器，不改变生产行为 */
    private val baseUrl: String = DEFAULT_BASE
) {

    /** 兼容既有 `$BASE` 拼接写法的只读视图 */
    private val BASE: String get() = baseUrl

    companion object {
        private const val TAG = "StravaApi"
        const val LOGIN_URL = "https://www.strava.com/login"
        const val DASHBOARD_URL = "https://www.strava.com/dashboard"
        const val DEFAULT_BASE = "https://www.strava.com"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        const val DEFAULT_POLL_INTERVAL_MS = 2000L
        const val DEFAULT_MAX_POLL = 12
    }

    /** 上传结果：pending 必须单列，调用方不得当作 success */
    data class Result(
        val success: Boolean,
        val pending: Boolean = false,
        val skipped: Boolean = false,
        val message: String = ""
    )

    class NotLoggedInException(msg: String) : Exception(msg)
    class RateLimitedException(msg: String) : Exception(msg)

    private val csrfRegex1 = Regex("<meta[^>]+name=\"csrf-token\"[^>]+content=\"([^\"]+)\"")
    private val csrfRegex2 = Regex("<meta[^>]+content=\"([^\"]+)\"[^>]+name=\"csrf-token\"")

    /** 文件指纹（MD5:size），用于跨次去重 */
    fun fingerprint(data: ByteArray): String {
        val md5 = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
        return "$md5:${data.size}"
    }

    /** 会话自检结果：给「测试会话」按钮直接展示，带 HTTP 码与判定明细 */
    data class SessionCheck(val ok: Boolean, val httpCode: Int, val detail: String)

    /** 会话有效性（明细版）：/upload/select 返回 200 才算有效；3xx/401/403 一律判失效 */
    fun checkSession(cookie: String): SessionCheck {
        if (cookie.isBlank()) {
            return SessionCheck(false, 0, "⚠ 未登录：本地没有 Strava Cookie，请先点「登录 Strava」")
        }
        return try {
            val conn = open("$BASE/upload/select", cookie)
            try {
                conn.requestMethod = "GET"
                val code = conn.responseCode
                Log.d(TAG, "checkSession HTTP $code")
                val detail = when {
                    code == 200 -> "✅ 会话有效（HTTP 200，/upload/select 正常）"
                    code in 300..399 -> "❌ 会话已失效（HTTP $code → 跳转登录页），请重新登录"
                    code == 401 || code == 403 -> "❌ 会话已失效（HTTP $code），请重新登录"
                    code == 429 -> "⚠ 被限流（HTTP 429），稍后再试"
                    else -> "⚠ 异常响应（HTTP $code）"
                }
                SessionCheck(code == 200, code, detail)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkSession: ${e.message}")
            SessionCheck(false, -1, "❌ 请求失败：${e.message}")
        }
    }

    /** 会话有效性（布尔版，供既有调用方使用） */
    fun hasSession(cookie: String): Boolean = checkSession(cookie).ok

    private fun open(url: String, cookie: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", MOBILE_UA)
        if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        return conn
    }

    private fun fetchCsrf(cookie: String): String {
        val conn = open("$BASE/upload/select", cookie)
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9")
            val code = conn.responseCode
            if (code in 300..399) throw NotLoggedInException("Strava 登录已失效(HTTP $code)，请重新登录")
            if (code == 401 || code == 403) throw NotLoggedInException("Strava 登录已失效(HTTP $code)，请重新登录")
            if (code == 429) throw RateLimitedException("Strava 请求过于频繁(429)，请稍后再试")
            if (code != 200) throw Exception("Strava /upload/select 返回 HTTP $code")
            val html = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val token = (csrfRegex1.find(html) ?: csrfRegex2.find(html))?.groupValues?.get(1)
                ?: throw Exception("Strava 页面未找到 csrf-token")
            return token
        } finally {
            conn.disconnect()
        }
    }

    private fun postFiles(cookie: String, csrf: String, data: ByteArray, fileName: String): String {
        val boundary = "----WawajiaoBoundary" + System.currentTimeMillis()
        val conn = open("$BASE/upload/files", cookie)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("X-CSRF-Token", csrf)
            conn.setRequestProperty("Referer", "$BASE/upload/select")
            conn.setRequestProperty("Origin", BASE)
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")

            val out = conn.outputStream
            fun field(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                out.write((value + "\r\n").toByteArray())
            }
            field("_method", "POST")
            field("authenticity_token", csrf)

            val isFit = fileName.endsWith(".fit", ignoreCase = true)
            val mime = if (isFit) "application/octet-stream" else "application/gpx+xml"
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"files[]\"; filename=\"$fileName\"\r\n".toByteArray())
            out.write("Content-Type: $mime\r\n\r\n".toByteArray())
            out.write(data)
            out.write("\r\n".toByteArray())
            out.write("--$boundary--\r\n".toByteArray())
            out.flush()
            out.close()

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code in 300..399 || code == 401 || code == 403) {
                throw NotLoggedInException("Strava 会话失效(HTTP $code)，请重新登录")
            }
            if (code == 429) throw RateLimitedException("Strava 上传被限流(429)，请稍后再试")
            if (code !in 200..299) throw Exception("Strava 上传失败(HTTP $code): ${body.take(120)}")

            // 真机实测：POST /upload/files 成功时返回的是 JSON **数组**
            //   [{"id":22126204676,"id_str":"22126204676","progress":0,"workflow":"new","error":null}]
            // 旧实现按对象解析，遇到数组直接抛异常被 catch 掉，于是误报「未返回 upload id」。
            val json = parseUploadJson(body)
            val err = cleanNullField(json.optString("error", ""))
            if (err.isNotEmpty()) throw Exception("Strava 上传失败: $err")
            val id = json.optString("id", "")
                .ifEmpty { json.optString("id_str", "") }
                .ifEmpty { json.optJSONObject("data")?.optString("id", "") ?: "" }
                .ifEmpty { json.optJSONObject("upload")?.optString("id", "") ?: "" }
                .ifEmpty {
                    // 兜底：极端形态变化时直接从原文抓第一个 id（不依赖 JSON 结构）
                    Regex("\"id(_str)?\"\\s*:\\s*\"?(\\d+)").find(body)
                        ?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: ""
                }
            if (id.isEmpty()) throw Exception("Strava 未返回 upload id: ${body.take(160)}")
            Log.d(TAG, "postFiles ok, id=$id")
            return id
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 容忍三种返回形态：对象 `{...}`、数组 `[{...}]`（真机实测形态）、以及带包装的对象 `{data:{...}}`。
     * 数组时取第一个元素；解析失败返回空对象，由调用方按「未返回 upload id」报错。
     */
    private fun parseUploadJson(body: String): JSONObject {
        val t = body.trim()
        return try {
            when {
                t.startsWith("[") -> JSONArray(t).optJSONObject(0) ?: JSONObject()
                t.startsWith("{") -> JSONObject(t)
                else -> JSONObject()
            }
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /**
     * 清洗 JSON 空值：Android 版 org.json 对 `"error": null` 的 optString 会返回字面量 `"null"`，
     * 而纯 JVM 版（单测用的 org.json:json）返回空串——不统一就会「单测通过、真机误判失败」。
     */
    private fun cleanNullField(s: String): String {
        val t = s.trim()
        return if (t.equals("null", ignoreCase = true)) "" else t
    }

    private fun poll(cookie: String, id: String, intervalMs: Long, maxTimes: Int): Result {
        var lastStatus = "unknown"
        var lastProgress = 0.0
        for (attempt in 1..maxTimes) {
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            val conn = open("$BASE/upload/progress.json?ids[]=$id", cookie)
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Referer", "$BASE/upload/select")
                val code = conn.responseCode
                if (code in 300..399 || code == 401 || code == 403) {
                    throw NotLoggedInException("Strava 会话失效(HTTP $code)，请重新登录")
                }
                if (code == 429) throw RateLimitedException("Strava 轮询被限流(429)")
                if (code != 200) throw Exception("Strava progress 返回 HTTP $code")
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val arr = try {
                    JSONArray(body)
                } catch (_: Exception) {
                    JSONArray().put(JSONObject(body))
                }
                val item = if (arr.length() > 0) arr.optJSONObject(0) else null
                val status = (item?.optString("status", "") ?: "").lowercase()
                lastStatus = status.ifEmpty { "processing" }
                lastProgress = item?.optDouble("progress", 0.0) ?: 0.0
                // 真机形态不带 status 字段，错误信息放在 error 里（null 表示无错）
                val errText = cleanNullField(item?.optString("error", "") ?: "")
                Log.d(TAG, "poll $attempt/$maxTimes status=$status progress=$lastProgress error=$errText")
                when {
                    status in listOf("error", "failed", "invalid") || errText.isNotEmpty() ->
                        return Result(false, message = "Strava 处理失败: ${errText.ifEmpty { status }}")
                    status in listOf("complete", "completed", "done", "success") || lastProgress >= 1.0 ->
                        return Result(true, message = "Strava 上传完成")
                    status == "duplicate" -> return Result(false, skipped = true, message = "Strava 已存在该活动")
                }
            } finally {
                conn.disconnect()
            }
        }
        // 轮询上限内未出结论：保留 pending，调用方不写已同步记忆，下次重试
        return Result(false, pending = true, message = "Strava 已上传待处理(status=$lastStatus)")
    }

    /**
     * 上传一条活动文件到 Strava。
     * @param cookie   Strava 域 Cookie（CookieManager.getCookie("https://www.strava.com") 落库值）
     * @param data     FIT 或 GPX 原始字节
     * @param fileName 目标文件名（决定 multipart 里的 Content-Type）
     */
    suspend fun upload(
        cookie: String,
        data: ByteArray,
        fileName: String,
        intervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        maxTimes: Int = DEFAULT_MAX_POLL,
        /** 三阶段进度回调（CSRF → 上传 → 轮询），供设置页「试传一条」逐行显示；生产调用不传即无副作用 */
        onStep: ((String) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        if (cookie.isBlank()) {
            onStep?.invoke("✗ 未登录：本地没有 Strava Cookie")
            return@withContext Result(false, message = "Strava 未登录，请先登录")
        }
        try {
            onStep?.invoke("① GET /upload/select 取 CSRF token …")
            val csrf = fetchCsrf(cookie)
            onStep?.invoke("① ✅ 取到 CSRF：${csrf.take(12)}…（${csrf.length} 字符）")
            onStep?.invoke("② POST /upload/files 上传 ${fileName}（${data.size} 字节）…")
            val id = postFiles(cookie, csrf, data, fileName)
            onStep?.invoke("② ✅ 上传受理，upload id=$id")
            onStep?.invoke("③ GET /upload/progress.json 轮询（间隔 ${intervalMs.coerceAtLeast(500L)}ms，最多 ${maxTimes.coerceIn(1, 30)} 次）…")
            val r = poll(cookie, id, intervalMs.coerceAtLeast(500L), maxTimes.coerceIn(1, 30))
            onStep?.invoke(
                when {
                    r.success -> "③ ✅ 处理完成：${r.message}"
                    r.pending -> "③ ⏳ 待确认（已上传未入库，稍后到 Strava 网页端核对，不要重传）：${r.message}"
                    r.skipped -> "③ ⏭ 跳过：${r.message}"
                    else -> "③ ❌ 失败：${r.message}"
                }
            )
            r
        } catch (e: NotLoggedInException) {
            Log.w(TAG, "upload: ${e.message}")
            onStep?.invoke("✗ 会话失效：${e.message}")
            Result(false, message = e.message ?: "Strava 登录已失效，请重新登录")
        } catch (e: RateLimitedException) {
            Log.w(TAG, "upload: ${e.message}")
            onStep?.invoke("✗ 被限流：${e.message}")
            Result(false, message = e.message ?: "Strava 请求过于频繁，请稍后再试")
        } catch (e: Exception) {
            Log.e(TAG, "upload error", e)
            onStep?.invoke("✗ 异常：${e.message}")
            Result(false, message = "Strava 上传失败: ${e.message}")
        }
    }
}
