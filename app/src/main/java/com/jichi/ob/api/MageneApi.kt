package com.jichi.ob.api
 
import android.util.Log
import com.jichi.ob.model.ActivityRecord
import com.jichi.ob.model.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
 
/**
 * 迈金/顽鹿 OTM API（2026-08-11 实测跑通）
 *
 * 登录: POST /api/login {"account":手机号,"password":MD5(密码)} → data[0].token (JWT裸用)
 * 列表: POST /api/otm/ride_record/list {"page":n,"limit":20} + Authorization: token
 * 详情: GET /api/otm/ride_record/analysis/{id} → data.ridingRecord.durl (七牛签名直链)
 * 下载: GET durl → FIT文件
 * 刷新: POST /api/token {"token":refresh_token,"from":"web","to":"web"}
 */
class MageneApi {
 
    /** 迈金云端无此记录的FIT文件（2025-10后新记录未上传七牛） */
    class NoFileException(msg: String) : Exception(msg)

    /** 迈金FIT下载结果: fromFitContent=true表示来自fit_content接口(GCJ-02需转换), false表示来自七牛云直链(WGS84无需转换) */
    data class MageneFitResult(val data: ByteArray, val fromFitContent: Boolean)
 
    companion object {
        private const val TAG = "MageneApi"
        const val LOGIN_URL = "https://otm.onelap.cn/login"
        private const val BASE = "https://otm.onelap.cn"
        private const val PER_PAGE = 20
        /** 单轮 OTM 预导入窗口固定 20 条（服务端行为） */
        private const val OTM_IMPORT_WINDOW = 20
        /** 预导入窗口被填满时最多再追问的轮数 */
        private const val OTM_IMPORT_MAX_ROUNDS = 3
        /** 主动刷新阈值：JWT 剩余有效期 < 1h 就先刷新 */
        private const val PROACTIVE_REFRESH_SEC = 3600L
    }
 
    /** 迈金触发风控（服务端限流/异常检测）：应中止本轮、不重试、不阻断其他平台 */
    class MageneRiskControlException(msg: String) : Exception(msg)

    /** 一轮同步内 OTM 预导入只跑一次；401 重试时调用方应 new 新实例以重置该标志 */
    @Volatile
    private var otmImportDone = false

    fun resetOtmImportFlag() { otmImportDone = false }

    private fun looksLikeRiskControl(raw: String): Boolean {
        val s = raw.lowercase()
        return s.contains("风控") || s.contains("频繁") || s.contains("risk") ||
            s.contains("too many") || s.contains("操作过快") || s.contains("校验")
    }

    /** 从 JWT payload 取 uid（拿不到返回 null，预导出发空串，服务端按当前会话推断） */
    fun parseUidFromJwt(token: String): String? {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val b64 = parts[1].replace('-', '+').replace('_', '/')
            val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
            val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            val payload = JSONObject(decoded)
            val uid = payload.optString("uid", "")
            uid.ifEmpty { payload.optString("userId", "") }.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "parseUidFromJwt: ${e.message}")
            null
        }
    }

    /** JWT 剩余有效期 < 1h 时建议先刷新（与启动检测同构） */
    fun shouldProactivelyRefresh(token: String): Boolean {
        val remain = getJwtExpRemainingSec(token) ?: return false
        return remain in 0..PROACTIVE_REFRESH_SEC
    }

    /**
     * OTM 侧预导入：必须在 ride_record/list 之前调用。
     * ride_record/list 只能看到"已导入"的记录，刚骑完的新记录不导入在 OTM 里就不存在。
     * 接口：POST /api/otm/ride_record/capability_list，头 Authorization: <裸 token>（不套 Bearer）。
     * 单轮窗口固定 20 条；inserted == total 说明窗口被填满、可能还有更早记录，最多再追 3 轮。
     */
    suspend fun importLatestRecords(token: String): Int = withContext(Dispatchers.IO) {
        if (otmImportDone) return@withContext 0
        otmImportDone = true
        val uid = parseUidFromJwt(token) ?: ""
        var totalInserted = 0
        var round = 0
        while (round <= OTM_IMPORT_MAX_ROUNDS) {
            val body = JSONObject().apply { put("uid", uid) }
            val req = Request.Builder()
                .url("$BASE/api/otm/ride_record/capability_list")
                .addHeader("Authorization", token)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                .addHeader("Origin", BASE)
                .addHeader("Referer", "$BASE/calendar")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            val raw = resp.body?.string() ?: ""
            if (resp.code == 401) throw Exception("迈金登录已过期，请重新登录")
            if (resp.code == 403) throw Exception("迈金拒绝访问(403，非登录态问题): ${raw.take(100)}")
            if (looksLikeRiskControl(raw)) throw MageneRiskControlException("capability_list 命中风控: ${raw.take(120)}")
            val json = JSONObject(raw)
            val code = json.optInt("code", -1)
            if (code == 401) throw Exception("迈金登录已过期，请重新登录")
            if (code == 403) throw Exception("迈金拒绝访问(403，非登录态问题): ${raw.take(100)}")
            if (looksLikeRiskControl(raw)) throw MageneRiskControlException("capability_list 命中风控: ${raw.take(120)}")
            if (code != 200) {
                Log.w(TAG, "importLatestRecords 业务失败 code=$code: ${raw.take(120)}")
                break
            }
            val sync = json.optJSONObject("data")?.optJSONObject("sync")
            val total = sync?.optInt("total", 0) ?: 0
            val inserted = sync?.optInt("inserted", 0) ?: 0
            totalInserted += inserted
            Log.d(TAG, "importLatestRecords round=$round total=$total inserted=$inserted")
            // inserted==0 或 inserted<total → 说明不再有更早记录待导入
            if (inserted == 0 || inserted < total) break
            // 窗口被填满（inserted==total==OTM_IMPORT_WINDOW 级）→ 追问更早记录，最多 3 轮
            round++
            if (round > OTM_IMPORT_MAX_ROUNDS) break
            kotlinx.coroutines.delay(200)
        }
        totalInserted
    }

    /**
     * 刷新 token：返回 (新accessToken, 新refreshToken?)。新 refreshToken 为空时返回 null，
     * 调用方必须保留旧值，不得写成空串。校验 code == 200。
     */
    suspend fun refreshTokenWithRefresh(refreshToken: String): Pair<String, String?>? =
        withContext(Dispatchers.IO) {
            if (refreshToken.isEmpty()) return@withContext null
            try {
                val body = JSONObject().apply {
                    put("token", refreshToken)
                    put("from", "web")
                    put("to", "web")
                }
                val req = Request.Builder()
                    .url("$BASE/api/token")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                    .addHeader("Origin", BASE)
                    .addHeader("Referer", "$BASE/calendar")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                val resp = client.newCall(req).execute()
                val raw = resp.body?.string() ?: ""
                if (resp.code != 200) { Log.w(TAG, "refreshToken HTTP ${resp.code}"); return@withContext null }
                if (looksLikeRiskControl(raw)) throw MageneRiskControlException("token 刷新命中风控: ${raw.take(120)}")
                val json = JSONObject(raw)
                if (json.optInt("code", -1) != 200) { Log.w(TAG, "refreshToken code!=200"); return@withContext null }
                val data = json.optJSONObject("data") ?: return@withContext null
                val tok = data.optString("token", "")
                if (tok.isEmpty()) return@withContext null
                val newRefresh = data.optString("refresh_token", "").ifEmpty { null }
                Log.d(TAG, "refreshToken ok, newRefresh=${newRefresh != null}")
                Pair(tok, newRefresh)
            } catch (e: MageneRiskControlException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "refreshTokenWithRefresh error", e)
                null
            }
        }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
 
    private val JSON = "application/json".toMediaType()
 
    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
 
    /**
     * 账号密码直接登录（密码MD5）
     * @return Pair(token, refreshToken)
     */
    suspend fun login(account: String, password: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("account", account)
                put("password", md5Hex(password))
            }
            val req = Request.Builder()
                .url("$BASE/api/login")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                .addHeader("Origin", BASE)
                .addHeader("Referer", "$BASE/login")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: throw Exception("登录空响应")
            val json = JSONObject(respBody)
            val code = json.optInt("code", -1)
            if (code != 200) throw Exception("迈金登录失败: ${json.optString("error", respBody.take(100))}")
            val arr = json.optJSONArray("data") ?: throw Exception("登录响应无data")
            var token = ""
            var refresh = ""
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.has("token")) {
                    token = item.optString("token", "")
                    refresh = item.optString("refresh_token", "")
                    break
                }
            }
            if (token.isEmpty()) throw Exception("登录响应无token")
            Log.d(TAG, "login ok, token len=${token.length}")
            Pair(token, refresh)
        }
 
    /**
     * token刷新
     */
    suspend fun refreshToken(refreshToken: String): String? {
        return refreshTokenWithRefresh(refreshToken)?.first
    }

    @Deprecated("旧实现保留，见 refreshTokenWithRefresh")
    private suspend fun refreshTokenLegacy(refreshToken: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("token", refreshToken)
                    put("from", "web")
                    put("to", "web")
                }
                val req = Request.Builder()
                    .url("$BASE/api/token")
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                val resp = client.newCall(req).execute()
                val json = JSONObject(resp.body?.string() ?: "")
                val data = json.optJSONObject("data")
                val tok = data?.optString("token", "") ?: ""
                if (tok.isNotEmpty()) tok else null
            } catch (e: Exception) {
                Log.e(TAG, "refreshToken error", e)
                null
            }
        }
 
    /**
     * 获取活动列表（自动翻页，服务端分页）
     * @param skip 跳过前skip条
     * @param limit 需要的条数
     */
    suspend fun getUsername(token: String): String? = withContext(Dispatchers.IO) {
        // 1) 优先解析JWT payload（迈金登录token是JWT，内含用户信息）
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payloadB64 = parts[1].replace('-', '+').replace('_', '/')
                val padded = payloadB64 + "=".repeat((4 - payloadB64.length % 4) % 4)
                val decoded = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val payload = JSONObject(decoded)
                // v7.6.9: 校验JWT过期时间(exp, Unix秒) —— 迈金token是JWT但getUsername之前不校验exp，
                // 导致token已过期仍显示"✅ 登录有效"，同步时才401。exp存在且已过期→判定失效，走刷新。
                val exp = payload.optLong("exp", 0L)
                if (exp > 0) {
                    val nowSec = System.currentTimeMillis() / 1000
                    if (nowSec >= exp) {
                        Log.w(TAG, "getUsername: JWT已过期(exp=$exp, now=$nowSec)")
                        return@withContext null
                    }
                }
                val name = payload.optString("nickname")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("userName")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("name")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("username")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("account")?.takeIf { it.isNotEmpty() }
                    ?: payload.optString("mobile")?.takeIf { it.isNotEmpty() }
                    ?: "用户${payload.optString("uid").take(6)}"
                return@withContext name
            }
        } catch (e: Exception) { Log.w(TAG, "JWT解析失败: ${e.message}") }
        // 2) 非JWT 或 JWT解析异常 → 用列表接口校验（v7.6.8: 改用确定可用的列表接口而非user/info——
        //    /api/otm/ride_record/list 与 getActivities 同款、验证可用，code==200 即 token 有效）
        try {
            val body = JSONObject().apply {
                put("page", 1)
                put("limit", 1)
            }
            val req = Request.Builder()
                .url("$BASE/api/otm/ride_record/list")
                .addHeader("Authorization", token)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                .addHeader("Origin", BASE)
                .addHeader("Referer", "$BASE/calendar")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            val json = try { JSONObject(respBody) } catch (e: Exception) { return@withContext null }
            if (resp.code == 200 && json.optInt("code", -1) == 200) "迈金用户" else null
        } catch (e2: Exception) { Log.w(TAG, "getUsername API: ${e2.message}"); null }
    }

    /**
     * v7.6.9: JWT剩余有效期（秒）。<0表示已过期；null表示无法解析（视为无exp信息）
     */
    fun getJwtExpRemainingSec(token: String): Long? {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payloadB64 = parts[1].replace('-', '+').replace('_', '/')
            val padded = payloadB64 + "=".repeat((4 - payloadB64.length % 4) % 4)
            val decoded = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val exp = JSONObject(decoded).optLong("exp", 0L)
            if (exp <= 0) null else exp - System.currentTimeMillis() / 1000
        } catch (e: Exception) {
            Log.w(TAG, "getJwtExpRemainingSec: ${e.message}")
            null
        }
    }

    suspend fun getActivities(token: String, skip: Int, limit: Int): List<ActivityRecord> =
        withContext(Dispatchers.IO) {
            // 批次A：拉 ride_record/list 之前必须先触发 OTM 侧预导入
            if (!otmImportDone) {
                try {
                    importLatestRecords(token)
                } catch (e: MageneRiskControlException) {
                    throw Exception("迈金触发风控，本平台本轮已中止，请稍后再试")
                } catch (e: Exception) {
                    Log.w(TAG, "预导入跳过（不阻断同步）: ${e.message}")
                }
            }
            val result = mutableListOf<ActivityRecord>()
            // 服务端分页: page从 skip/PER_PAGE + 1 开始
            var page = skip / PER_PAGE + 1
            var skipLeft = skip % PER_PAGE  // 首页内需要跳过的条数
            while (result.size < limit) {
                val body = JSONObject().apply {
                    put("page", page)
                    put("limit", PER_PAGE)
                }
                val req = Request.Builder()
                    .url("$BASE/api/otm/ride_record/list")
                    .addHeader("Authorization", token)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                    .addHeader("Origin", BASE)
                    .addHeader("Referer", "$BASE/calendar")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                val resp = client.newCall(req).execute()
                val respBody = resp.body?.string() ?: throw Exception("列表空响应")
                if (resp.code == 401) throw Exception("迈金登录已过期，请重新登录")
                if (resp.code == 403) throw Exception("迈金拒绝访问(403，非登录态问题): ${respBody.take(100)}")
                val json = JSONObject(respBody)
                val code = json.optInt("code", -1)
                if (code == 401) throw Exception("迈金登录已过期，请重新登录")
                if (code == 403) throw Exception("迈金拒绝访问(403，非登录态问题): ${respBody.take(100)}")
                if (code != 200) throw Exception("迈金列表错误: ${json.optString("error", respBody.take(100))}")
 
                val data = json.optJSONObject("data") ?: break
                val rows = data.optJSONArray("list")
                if (rows == null || rows.length() == 0) break
 
                for (i in 0 until rows.length()) {
                    if (skipLeft > 0) { skipLeft--; continue }
                    val item = rows.getJSONObject(i)
                    val id = item.optString("id", "")
                    if (id.isEmpty()) continue
                    // v9.1.1: 迈金 start_riding_time 为北京时间字符串，optLong 解析返回 0 →
                    // 从字符串解析 startMs（对齐开发版 v8.9.10），否则时间排序/日期检索失效
                    val startTimeRaw = item.optString("start_riding_time", "")
                    val startRaw = item.optLong("start_riding_time", 0L)
                    var startMs = if (startRaw > 1_000_000_000L) (if (startRaw > 1_000_000_000_000L) startRaw else startRaw * 1000L) else 0L
                    if (startMs == 0L && startTimeRaw.isNotBlank()) {
                        try {
                            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                            startMs = sdf.parse(startTimeRaw)?.time ?: 0L
                        } catch (_: Exception) {}
                    }
                    // v9.1.1: 迈金API返回UTC时间字符串"2026-09-23 09:49:33"，FileNameGenerator按本地时区解析会差8小时，
                    // 统一转成北京时间字符串（对齐开发版 v8.9.5）
                    val startTime = normalizeToBeijing(startMs, startTimeRaw)
                    var name = item.optString("name", "")
                    if (name == "null") name = ""
                    val title = name.ifEmpty {
                        val d = startTime.take(10)
                        if (d.isNotEmpty()) "$d 骑行" else "骑行 ${id.take(8)}"
                    }
                    result.add(
                        ActivityRecord(
                            id = id,
                            title = title,
                            startTime = startTime,
                            distance = item.optDouble("distance_km", 0.0),
                            duration = item.optInt("time_seconds", 0),
                            source = DataSource.MAGENE,
                            startTimeMs = startMs
                        )
                    )
                    if (result.size >= limit) break
                }
 
                val pagination = data.optJSONObject("pagination")
                val hasMore = pagination?.optBoolean("has_more", false) ?: false
                if (!hasMore || rows.length() < PER_PAGE) break
                page++
                kotlinx.coroutines.delay(150)
            }
            Log.d(TAG, "getActivities: ${result.size} records")
            result
        }

    /** v9.1.1: 把迈金API返回的时间统一转成北京时间字符串"yyyy-MM-dd HH:mm:ss"（对齐开发版 v8.9.5）。
     *  迈金API start_riding_time 返回北京时间字符串，直接格式化，不要再 +8h。 */
    private fun normalizeToBeijing(utcMs: Long, rawStr: String): String {
        if (utcMs > 0) {
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                sdf.format(Date(utcMs))
            } catch (_: Exception) { rawStr }
        }
        return rawStr
    }
 
    /**
     * 下载FIT（2026-08-12 修复版）
     * 策略: 详情接口取 durl+fitUrl
     *   ① durl七牛直链（老格式记录可用）
     *   ② 回退 fit_content/{base64(fitUrl)}（官方网页端同款接口，新geo/格式记录必用）
     */
    suspend fun downloadFit(token: String, recordId: String): MageneFitResult =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$BASE/api/otm/ride_record/analysis/$recordId")
                .addHeader("Authorization", token)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                .addHeader("Origin", BASE)
                .addHeader("Referer", "$BASE/calendar")
                .get().build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: throw Exception("详情空响应")
            if (resp.code == 401) throw Exception("迈金登录已过期，请重新登录")
            val json = JSONObject(respBody)
            if (json.optInt("code", -1) != 200) throw Exception("迈金详情错误: ${respBody.take(100)}")
            val riding = json.optJSONObject("data")?.optJSONObject("ridingRecord")
            val durl = riding?.optString("durl", "") ?: ""
            val fitUrl = riding?.optString("fitUrl", "") ?: ""
 
            // ① durl直链（快，但新格式记录404）
            if (durl.isNotEmpty()) {
                try {
                    val dlReq = Request.Builder()
                        .url(durl)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                        .get().build()
                    val dlResp = client.newCall(dlReq).execute()
                    if (dlResp.code == 200) {
                        val bytes = dlResp.body?.bytes()
                        if (bytes != null && isFit(bytes)) {
                            Log.d(TAG, "FIT via durl(七牛云WGS84): ${bytes.size} bytes (id=$recordId)")
                            return@withContext MageneFitResult(bytes, false)
                        }
                    }
                    Log.d(TAG, "durl不可用(HTTP ${dlResp.code})，回退fit_content")
                } catch (e: Exception) {
                    Log.w(TAG, "durl异常: ${e.message}，回退fit_content")
                }
            }
 
            // ② fit_content/{base64(fitUrl)} —— 官方网页端同款，全格式通用
            if (fitUrl.isEmpty()) throw NoFileException("该记录无FIT文件(fitUrl为空，可能是虚拟路线)")
            val b64 = Base64.encodeToString(fitUrl.toByteArray(), Base64.NO_WRAP)
            val fcReq = Request.Builder()
                .url("$BASE/api/otm/ride_record/analysis/fit_content/$b64")
                .addHeader("Authorization", token)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                .addHeader("Origin", BASE)
                .addHeader("Referer", "$BASE/calendar")
                .get().build()
            val fcResp = client.newCall(fcReq).execute()
            val fcBytes = fcResp.body?.bytes() ?: throw Exception("fit_content空响应")
            if (fcResp.code != 200 || !isFit(fcBytes)) {
                throw NoFileException("FIT获取失败(fit_content HTTP ${fcResp.code}, size=${fcBytes.size})")
            }
            Log.d(TAG, "FIT via fit_content(GCJ-02): ${fcBytes.size} bytes (id=$recordId)")
            MageneFitResult(fcBytes, true)
        }
 
    private fun isFit(bytes: ByteArray): Boolean =
        bytes.size >= 14 && bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte()
}
 

