package com.jichi.ob.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * StravaApi 协议层 JVM 单元测试。
 *
 * 用 JDK 自带的 [HttpServer] 监听 127.0.0.1 随机端口充当假 Strava，逐个验证：
 *  - 三步协议（取 CSRF → multipart 上传 → 轮询进度）
 *  - multipart 段名 / 字段值 / 请求头（Cookie、X-CSRF-Token、X-Requested-With 等）
 *  - 轮询三态：complete 成功 / error 失败取文案 / processing 轮询上限 pending
 *  - 302 登录失效兜底；checkSession 200 / 302 / 空 cookie 三分支
 *  - fingerprint 的稳定性、差异性与 `md5:size` 格式
 *  - onStep 逐步回调确实出现 ①②③ 三个阶段标记
 *
 * 注意：poll 内部 Thread.sleep(intervalMs)，测试一律传 500ms（内部 coerceAtLeast(500)），避免拖慢。
 */
class StravaApiTest {

    /** 一次被假服务器收到的请求记录，用于断言方法、路径、请求头与原始 multipart 体 */
    private data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /** 假 Strava：只绑 127.0.0.1:0（随机端口），每个用例独立实例，用完 stop，不留常驻端口 */
    private class FakeStrava(
        var selectStatus: Int = 200,
        var csrf: String = "csrf-token-abc123XYZ",
        var progressBody: String = """[{"status":"complete"}]"""
    ) {
        val requests = mutableListOf<RecordedRequest>()
        private val server: HttpServer =
            HttpServer.create(InetAddress.getByName("127.0.0.1"), 0).apply {
                createContext("/upload/select") { handleSelect(it) }
                createContext("/upload/files") { handleFiles(it) }
                createContext("/upload/progress.json") { handleProgress(it) }
                executor = null
                start()
            }
        val port: Int get() = server.address.port
        val baseUrl: String get() = "http://127.0.0.1:$port"

        private fun record(ex: HttpExchange, body: String) {
            val headerMap = LinkedHashMap<String, String>()
            ex.requestHeaders.forEach { (k, v) -> headerMap[k] = v.joinToString(",") }
            requests.add(RecordedRequest(ex.requestMethod, ex.requestURI.rawPath, headerMap, body))
        }

        private fun readBody(ex: HttpExchange): String {
            val bytes = ex.requestBody.use { it.readBytes() }
            return bytes.toString(StandardCharsets.UTF_8)
        }

        private fun respond(ex: HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }

        private fun handleSelect(ex: HttpExchange) {
            record(ex, readBody(ex))
            if (selectStatus in 300..399) {
                ex.responseHeaders.set("Location", "/login")
                ex.sendResponseHeaders(selectStatus, -1)
                ex.close()
                return
            }
            val html = """<html><head><meta name="csrf-token" content="$csrf"></head></html>"""
            respond(ex, 200, html)
        }

        private fun handleFiles(ex: HttpExchange) {
            record(ex, readBody(ex))
            respond(ex, 200, """{"id":"up_123"}""")
        }

        private fun handleProgress(ex: HttpExchange) {
            record(ex, readBody(ex))
            respond(ex, 200, progressBody)
        }

        fun stop() = server.stop(0)
    }

    private fun apiOf(fake: FakeStrava) = StravaApi(fake.baseUrl)

    private val fitBytes = "FAKE-FIT-BYTES-1234567890".toByteArray(StandardCharsets.UTF_8)

    // ---------------------------------------------------------------------
    // 1) 成功路径：三阶段都发生，multipart 段名 / 字段值 / 请求头断言到位
    // ---------------------------------------------------------------------

    @Test
    fun `upload 成功路径 三阶段齐全 multipart 与请求头断言到位`() = runBlocking {
        val fake = FakeStrava()
        val steps = mutableListOf<String>()
        try {
            val api = apiOf(fake)
            val result = api.upload(
                cookie = "session=abc123",
                data = fitBytes,
                fileName = "xxx.fit",
                intervalMs = 500,
                maxTimes = 3,
                onStep = { steps.add(it) }
            )

            // 最终结果：成功，不是 pending / skipped
            assertTrue("成功路径必须 success=true", result.success)
            assertFalse(result.pending)
            assertFalse(result.skipped)

            // 三阶段各发生一次：GET select、POST files、GET progress
            val selects = fake.requests.filter { it.path == "/upload/select" }
            val posts = fake.requests.filter { it.path == "/upload/files" }
            val polls = fake.requests.filter { it.path == "/upload/progress.json" }
            assertEquals("取 CSRF 只应请求一次", 1, selects.size)
            assertEquals("文件上传只应 POST 一次", 1, posts.size)
            assertEquals("成功路径轮询一次即结束", 1, polls.size)

            val post = posts.first()
            assertEquals("POST", post.method)

            // —— 请求头断言 ——
            val contentType = post.header("Content-Type")
            assertNotNull("必须带 Content-Type", contentType)
            assertTrue(
                "Content-Type 应以 multipart/form-data; boundary= 开头，实际: $contentType",
                contentType!!.startsWith("multipart/form-data; boundary=")
            )
            assertEquals("Cookie 必须原样带上", "session=abc123", post.header("Cookie"))
            assertEquals(
                "X-CSRF-Token 必须等于假页面里的 csrf",
                fake.csrf,
                post.header("X-CSRF-Token")
            )
            assertEquals("XMLHttpRequest", post.header("X-Requested-With"))
            assertTrue(
                "Referer 应指向 /upload/select",
                post.header("Referer")?.endsWith("/upload/select") == true
            )
            assertEquals("Origin 应为假服务器根地址", fake.baseUrl, post.header("Origin"))

            // —— 原始 multipart 体断言（对捕获 body 字符串做 contains） ——
            val body = post.body
            assertTrue("multipart 必须含 _method 段", body.contains("name=\"_method\""))
            assertTrue(
                "multipart 必须含 authenticity_token 段",
                body.contains("name=\"authenticity_token\"")
            )
            assertTrue(
                "multipart 必须含 files[]; filename=\"xxx.fit\"",
                body.contains("name=\"files[]\"; filename=\"xxx.fit\"")
            )
            assertTrue(
                "fit 文件的段 Content-Type 应为 application/octet-stream",
                body.contains("Content-Type: application/octet-stream")
            )

            // _method 的值必须是 POST（定位到该段，段头后空行 + 值 + 换行）
            val methodPart = body.split("--").first { it.contains("name=\"_method\"") }
            assertTrue(
                "_method 段的值应为 POST，实际段内容: $methodPart",
                methodPart.contains("\r\n\r\nPOST\r\n")
            )
            // authenticity_token 段的值必须等于 csrf
            val tokenPart = body.split("--").first { it.contains("name=\"authenticity_token\"") }
            assertTrue(
                "authenticity_token 段的值应为 csrf，实际段内容: $tokenPart",
                tokenPart.contains("\r\n\r\n${fake.csrf}\r\n")
            )
        } finally {
            fake.stop()
        }
    }

    // ---------------------------------------------------------------------
    // 2) 轮询三态：complete → success；error → 失败且带文案；一直 processing → pending
    // ---------------------------------------------------------------------

    @Test
    fun `轮询返回 complete 判定成功`() = runBlocking {
        val fake = FakeStrava(progressBody = """[{"status":"complete","progress":1.0}]""")
        try {
            val result = apiOf(fake).upload(
                cookie = "session=abc",
                data = fitBytes,
                fileName = "xxx.fit",
                intervalMs = 500,
                maxTimes = 3
            )
            assertTrue(result.success)
            assertFalse(result.pending)
            assertFalse(result.skipped)
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `轮询返回 error 判定失败并带上假服务器给的错误文案`() = runBlocking {
        val fake = FakeStrava(
            progressBody = """[{"status":"error","error":"cadence file corrupt"}]"""
        )
        try {
            val result = apiOf(fake).upload(
                cookie = "session=abc",
                data = fitBytes,
                fileName = "xxx.fit",
                intervalMs = 500,
                maxTimes = 3
            )
            assertFalse("error 不能判成成功", result.success)
            assertFalse(result.pending)
            assertFalse(result.skipped)
            assertTrue(
                "message 必须包含假服务器返回的 error 文案，实际: ${result.message}",
                result.message.contains("cadence file corrupt")
            )
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `轮询一直 processing 到上限 判定 pending 且不能算成功`() = runBlocking {
        val fake = FakeStrava(
            progressBody = """[{"status":"processing","progress":0.2}]"""
        )
        try {
            val result = apiOf(fake).upload(
                cookie = "session=abc",
                data = fitBytes,
                fileName = "xxx.fit",
                intervalMs = 500,
                maxTimes = 1
            )
            assertFalse("pending 绝不能判成成功", result.success)
            assertTrue("轮询上限内未出结论必须 pending=true", result.pending)
            assertFalse(result.skipped)

            // maxTimes=1 只应轮询一次
            val polls = fake.requests.filter { it.path == "/upload/progress.json" }
            assertEquals(1, polls.size)
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `轮询返回 duplicate 判定 skipped`() = runBlocking {
        val fake = FakeStrava(progressBody = """[{"status":"duplicate"}]""")
        try {
            val result = apiOf(fake).upload(
                cookie = "session=abc",
                data = fitBytes,
                fileName = "xxx.fit",
                intervalMs = 500,
                maxTimes = 3
            )
            assertFalse(result.success)
            assertFalse(result.pending)
            assertTrue("duplicate 必须标记 skipped", result.skipped)
        } finally {
            fake.stop()
        }
    }

    // ---------------------------------------------------------------------
    // 3) 登录失效：select 302 → 兜底 Result(false)，不向外抛异常，message 含登录/失效
    // ---------------------------------------------------------------------

    @Test
    fun `upload 遇到 302 登录失效 兜底返回失败且不抛异常`() = runBlocking {
        val fake = FakeStrava(selectStatus = 302)
        try {
            var result: StravaApi.Result? = null
            var thrown: Throwable? = null
            try {
                result = apiOf(fake).upload(
                    cookie = "session=abc",
                    data = fitBytes,
                    fileName = "xxx.fit",
                    intervalMs = 500,
                    maxTimes = 1
                )
            } catch (t: Throwable) {
                thrown = t
            }
            assertTrue("登录失效不应向外抛异常", thrown == null)
            assertNotNull(result)
            assertFalse(result!!.success)
            assertFalse(result.pending)
            assertTrue(
                "message 应含「登录」或「失效」字样，实际: ${result.message}",
                result.message.contains("登录") || result.message.contains("失效")
            )
            // 302 发生在取 CSRF 阶段，不应再发上传 / 轮询请求
            assertEquals(0, fake.requests.count { it.path == "/upload/files" })
            assertEquals(0, fake.requests.count { it.path == "/upload/progress.json" })
        } finally {
            fake.stop()
        }
    }

    // ---------------------------------------------------------------------
    // 4) checkSession：200 → ok；302 → 失效并带 httpCode；空 cookie → 不发请求
    // ---------------------------------------------------------------------

    @Test
    fun `checkSession 200 判定有效且 httpCode=200`() {
        val fake = FakeStrava(selectStatus = 200)
        try {
            val check = apiOf(fake).checkSession("session=abc")
            assertTrue(check.ok)
            assertEquals(200, check.httpCode)
            assertTrue(check.detail.isNotBlank())
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `checkSession 302 判定失效且 httpCode=302`() {
        val fake = FakeStrava(selectStatus = 302)
        try {
            val check = apiOf(fake).checkSession("session=abc")
            assertFalse(check.ok)
            assertEquals(302, check.httpCode)
            assertTrue(
                "detail 应说明失效原因，实际: ${check.detail}",
                check.detail.contains("失效") || check.detail.contains("302")
            )
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `checkSession 空 cookie 直接判失效且不发起请求`() {
        val fake = FakeStrava()
        try {
            val check = apiOf(fake).checkSession("   ")
            assertFalse(check.ok)
            assertEquals(0, check.httpCode)
            assertTrue(fake.requests.isEmpty())
        } finally {
            fake.stop()
        }
    }

    // ---------------------------------------------------------------------
    // 5) fingerprint：同字节一致、异字节不同，格式 md5:size
    // ---------------------------------------------------------------------

    @Test
    fun `fingerprint 同字节一致 异字节不同 格式为 md5冒号size`() {
        val api = StravaApi()
        val a1 = api.fingerprint("hello world".toByteArray())
        val a2 = api.fingerprint("hello world".toByteArray())
        val b1 = api.fingerprint("hello worle".toByteArray())

        assertEquals("相同字节指纹必须一致", a1, a2)
        assertNotEquals("不同字节指纹必须不同", a1, b1)
        assertTrue(
            "格式应为 32 位 md5:size，实际: $a1",
            Regex("^[0-9a-f]{32}:\\d+$").matches(a1)
        )
        assertEquals("hello world".length.toString(), a1.substringAfter(':'))
    }

    // ---------------------------------------------------------------------
    // 6) onStep：回调里真的出现 ①②③ 三个阶段标记
    // ---------------------------------------------------------------------

    @Test
    fun `onStep 回调依次出现 ① ② ③ 三个阶段标记`() = runBlocking {
        val fake = FakeStrava()
        val steps = mutableListOf<String>()
        try {
            val result = apiOf(fake).upload(
                cookie = "session=abc",
                data = fitBytes,
                fileName = "xxx.fit",
                intervalMs = 500,
                maxTimes = 1,
                onStep = { steps.add(it) }
            )
            assertTrue(result.success)
            val joined = steps.joinToString("\n")
            assertTrue("回调必须出现 ① 取 CSRF 阶段标记", steps.any { it.contains("①") })
            assertTrue("回调必须出现 ② 上传阶段标记", steps.any { it.contains("②") })
            assertTrue("回调必须出现 ③ 轮询阶段标记", steps.any { it.contains("③") })
            assertTrue("回调不应为空，实际:\n$joined", steps.isNotEmpty())
        } finally {
            fake.stop()
        }
    }
}
