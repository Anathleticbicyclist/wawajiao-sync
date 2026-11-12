package com.jichi.ob.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets

/**
 * StravaApi 协议层 JVM 单元测试。
 *
 * 假 Strava 只用 java.net.ServerSocket + java.net.Socket 手写极简 HTTP/1.1 服务
 * （AGP JVM 单测编译以 android.jar 为 bootclasspath，其中没有 com.sun.net.httpserver，
 *  故不能使用 JDK 的 HttpServer；ServerSocket/Socket 在 android.jar 中存在）。
 * 绑 127.0.0.1:0 随机端口，每个用例独立实例，用完 stop 释放端口，不留常驻监听。
 *
 * 覆盖：
 *  - 三步协议（取 CSRF → multipart 上传 → 轮询进度）
 *  - multipart 段名 / 字段值 / 请求头（Cookie、X-CSRF-Token、X-Requested-With 等）
 *  - 轮询三态：complete 成功 / error 失败取文案 / processing 轮询上限 pending / duplicate 跳过
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

    /**
     * 极简 HTTP/1.1 假服务器：
     *  - 后台 accept 线程 daemon；每个连接独立 daemon 线程处理；
     *  - 按 Content-Length 读满请求体（multipart 包体较大，不能只读行）；
     *  - 响应固定 `Content-Length` + `Connection: close`，不用 chunked。
     */
    private class FakeStrava(
        var selectStatus: Int = 200,
        var csrf: String = "FAKE_CSRF",
        var progressBody: String = """[{"status":"complete"}]"""
    ) {
        private val requestsLock = Any()
        val requests = mutableListOf<RecordedRequest>()

        private val serverSocket =
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        @Volatile private var running = true
        private val acceptThread = Thread({ acceptLoop() }, "fake-strava-accept").apply {
            isDaemon = true
            start()
        }

        val port: Int get() = serverSocket.localPort
        val baseUrl: String get() = "http://127.0.0.1:$port"

        private fun acceptLoop() {
            while (running) {
                val client = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    break
                }
                Thread({ handleClient(client) }, "fake-strava-conn").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        private fun handleClient(socket: Socket) {
            socket.use {
                val bis = BufferedInputStream(socket.getInputStream())
                // 1) 请求行：METHOD PATH HTTP/1.1
                val requestLine = readLine(bis) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]

                // 2) 请求头，读到空行
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(bis) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim()] =
                            line.substring(idx + 1).trim()
                    }
                }

                // 3) 按 Content-Length 读满请求体
                val bodyLen = headers["Content-Length"]?.toIntOrNull() ?: 0
                val body = if (bodyLen > 0) {
                    val buf = ByteArray(bodyLen)
                    var off = 0
                    while (off < bodyLen) {
                        val n = bis.read(buf, off, bodyLen - off)
                        if (n == -1) break
                        off += n
                    }
                    buf.toString(StandardCharsets.UTF_8)
                } else ""

                synchronized(requestsLock) {
                    requests.add(RecordedRequest(method, path, headers, body))
                }

                // 4) 按路径回预设响应
                val (code, respBody, contentType) = when {
                    path.startsWith("/upload/select") -> {
                        if (selectStatus in 300..399) {
                            Triple(selectStatus, "", "text/plain; charset=utf-8")
                        } else {
                            val html = """<html><head><meta name="csrf-token" content="$csrf"></head></html>"""
                            Triple(200, html, "text/html; charset=utf-8")
                        }
                    }
                    // 真机实测形态：POST /upload/files 成功返回 JSON 数组（不是对象），
                    // 且 id 是超出 Int 范围的数字。旧解析按对象处理会误报「未返回 upload id」。
                    path.startsWith("/upload/files") ->
                        Triple(
                            200,
                            """[{"id":22126204676,"id_str":"22126204676","name":null,"progress":0,"workflow":"new","start_date":null,"error":null}]""",
                            "application/json"
                        )
                    path.startsWith("/upload/progress.json") ->
                        Triple(200, progressBody, "application/json")
                    else -> Triple(404, "not found", "text/plain")
                }

                val head = (
                    "HTTP/1.1 $code ${reasonPhrase(code)}\r\n" +
                        "Content-Length: ${respBody.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(StandardCharsets.UTF_8)
                val out = socket.getOutputStream()
                out.write(head)
                out.write(respBody.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        }

        /** 从 BufferedInputStream 读一行（\r\n 或 \n），EOF 返回 null */
        private fun readLine(bis: BufferedInputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val c = bis.read()
                if (c == -1) {
                    return if (buf.size() == 0) null
                    else buf.toString(StandardCharsets.UTF_8)
                }
                if (c == '\r'.code) {
                    val n = bis.read()
                    if (n == '\n'.code) break
                    buf.write(c)
                    if (n != -1) buf.write(n)
                } else {
                    buf.write(c)
                }
            }
            return buf.toString(StandardCharsets.UTF_8)
        }

        private fun reasonPhrase(code: Int): String = when (code) {
            200 -> "OK"
            302 -> "Found"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }

        fun stop() {
            running = false
            try { serverSocket.close() } catch (_: Exception) {}
            acceptThread.join(500)
        }
    }

    private fun apiOf(fake: FakeStrava) = StravaApi(fake.baseUrl)

    private val fitBytes = "FAKE-FIT-BYTES-1234567890".toByteArray(StandardCharsets.UTF_8)

    // ---------------------------------------------------------------------
    // 1) 成功路径：三阶段都发生，multipart 段名 / 字段值 / 请求头断言到位
    // ---------------------------------------------------------------------

    @Test
    fun `upload 成功路径 三阶段齐全 multipart 与请求头断言到位`() = runBlocking {
        // 真机形态：POST /upload/files 与 progress.json 响应都带 "error":null，
        // 即便如此 upload() 仍必须 success=true（拦住 "null" 字面量被误判成失败的回归）。
        val fake = FakeStrava(progressBody = """[{"status":"complete","error":null}]""")
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
            val selects = fake.requests.filter { it.path.startsWith("/upload/select") }
            val posts = fake.requests.filter { it.path.startsWith("/upload/files") }
            val polls = fake.requests.filter { it.path.startsWith("/upload/progress.json") }
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
            // 注：JDK HttpURLConnection 传输层会剥离 Origin 头（实测 Cookie/Referer/X-* 均可达，
            // 仅 Origin 不到达），故 JVM 单测无法观测 Origin；生产 Android 端是否携带需真机验证。

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

            // 真机原样：POST /upload/files 成功响应是 JSON 数组
            //   [{"id":22126204676,"id_str":"22126204676",...,"error":null}]
            // 这里断言数组里的 id 被 parseUploadJson 正确取出（旧对象解析实现会误报「未返回 id」）。
            assertTrue(
                "应从数组形态 POST 响应中拿到 upload id 22126204676，步骤日志: $steps",
                steps.any { it.contains("22126204676") }
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
    fun `轮询 complete 但带 error 文案 仍判失败并带文案`() = runBlocking {
        val fake = FakeStrava(
            progressBody = """[{"status":"complete","error":"gps gap"}]"""
        )
        try {
            val result = apiOf(fake).upload(
                cookie = "session=abc",
                data = fitBytes,
                fileName = "xxx.fit",
                intervalMs = 500,
                maxTimes = 3
            )
            assertFalse("带 error 文案不能判成成功", result.success)
            assertFalse(result.pending)
            assertFalse(result.skipped)
            assertTrue(
                "message 必须包含 error 文案 gps gap，实际: ${result.message}",
                result.message.contains("gps gap")
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
            val polls = fake.requests.filter { it.path.startsWith("/upload/progress.json") }
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
            val r = result!!
            assertFalse(r.success)
            assertFalse(r.pending)
            assertTrue(
                "message 应含「登录」或「失效」字样，实际: ${r.message}",
                r.message.contains("登录") || r.message.contains("失效")
            )
            // 302 发生在取 CSRF 阶段，不应再发上传 / 轮询请求
            assertEquals(0, fake.requests.count { it.path.startsWith("/upload/files") })
            assertEquals(0, fake.requests.count { it.path.startsWith("/upload/progress.json") })
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
