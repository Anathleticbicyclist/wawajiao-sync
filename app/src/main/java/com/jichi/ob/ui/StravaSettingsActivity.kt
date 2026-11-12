package com.jichi.ob.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.jichi.ob.R
import com.jichi.ob.api.StravaApi
import com.jichi.ob.util.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 哇哇叫 v1.0.0 · Strava 上传设置页
 *
 * 只做设置的读写与登录态展示，真正的上传在 UploadEngine 里完成。
 * 状态矩阵：未登录空态 / 已登录 / 检测中 / 有效 / 失效 / 限流 / 其他错误，全部就近展示在操作按钮上方。
 */
class StravaSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    private lateinit var groupEmpty: View
    private lateinit var groupLoggedIn: View
    private lateinit var tvAccount: TextView
    private lateinit var tvLastUpload: TextView
    private lateinit var tvSessionState: TextView
    private lateinit var progressSession: ProgressBar
    private lateinit var btnTestSession: MaterialButton
    private lateinit var chipPoll2: TextView
    private lateinit var chipPoll5: TextView
    private lateinit var chipRetry8: TextView
    private lateinit var chipRetry12: TextView
    private lateinit var chipRetry20: TextView
    private lateinit var switchDedup: MaterialSwitch
    private lateinit var tvDiagCookie: TextView
    private lateinit var tvDiagParams: TextView
    private lateinit var btnTestUpload: MaterialButton
    private lateinit var tvSelfTestLog: TextView

    /** 自检日志行（文案 + 颜色资源），最多保留最近 30 行，超出丢弃最旧 */
    private val selfTestLines = mutableListOf<Pair<String, Int>>()

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val sid = data.getStringExtra(LoginWebActivity.RESULT_SESSION_ID) ?: ""
            if (sid.length > 20) {
                prefs.saveStravaCookie(sid)
                showSessionState(getString(R.string.strava_session_valid), R.color.log_success)
            }
        }
        render()
    }

    /** 试传文件选择器：拿到 Uri 后按扩展名仅接受 .fit / .gpx */
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handlePickedTestFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strava_settings)
        prefs = PrefsManager(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        groupEmpty = findViewById(R.id.groupEmpty)
        groupLoggedIn = findViewById(R.id.groupLoggedIn)
        tvAccount = findViewById(R.id.tvAccount)
        tvLastUpload = findViewById(R.id.tvLastUpload)
        tvSessionState = findViewById(R.id.tvSessionState)
        progressSession = findViewById(R.id.progressSession)
        btnTestSession = findViewById(R.id.btnTestSession)
        chipPoll2 = findViewById(R.id.chipPoll2)
        chipPoll5 = findViewById(R.id.chipPoll5)
        chipRetry8 = findViewById(R.id.chipRetry8)
        chipRetry12 = findViewById(R.id.chipRetry12)
        chipRetry20 = findViewById(R.id.chipRetry20)
        switchDedup = findViewById(R.id.switchDedup)
        tvDiagCookie = findViewById(R.id.tvDiagCookie)
        tvDiagParams = findViewById(R.id.tvDiagParams)
        btnTestUpload = findViewById(R.id.btnTestUpload)
        tvSelfTestLog = findViewById(R.id.tvSelfTestLog)

        findViewById<MaterialButton>(R.id.btnLogin).setOnClickListener {
            loginLauncher.launch(Intent(this, LoginWebActivity::class.java)
                .putExtra(LoginWebActivity.EXTRA_LOGIN_TYPE, LoginWebActivity.TYPE_STRAVA)
                .putExtra(LoginWebActivity.EXTRA_URL, StravaApi.LOGIN_URL))
        }
        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener { confirmLogout() }
        btnTestSession.setOnClickListener { checkSession() }
        btnTestUpload.setOnClickListener { pickTestUploadFile() }
        chipPoll2.setOnClickListener { prefs.saveStravaPollInterval(2000L); render() }
        chipPoll5.setOnClickListener { prefs.saveStravaPollInterval(5000L); render() }
        chipRetry8.setOnClickListener { prefs.saveStravaMaxRetry(8); render() }
        chipRetry12.setOnClickListener { prefs.saveStravaMaxRetry(12); render() }
        chipRetry20.setOnClickListener { prefs.saveStravaMaxRetry(20); render() }
        switchDedup.setOnCheckedChangeListener { _, checked ->
            prefs.saveStravaDedupeEnabled(checked)
            render()
        }
        render()
    }

    private fun render() {
        val logged = prefs.isStravaLoggedIn()
        groupEmpty.visibility = if (logged) View.GONE else View.VISIBLE
        groupLoggedIn.visibility = if (logged) View.VISIBLE else View.GONE

        if (logged) {
            val name = prefs.getStravaAthleteName().orEmpty()
            tvAccount.text = if (name.isNotEmpty()) getString(R.string.strava_logged_in_as, name)
            else getString(R.string.strava_logged_in_as, "Strava 账号")
            val last = prefs.getStravaLastUploadTime()
            tvLastUpload.text = if (last > 0)
                "${getString(R.string.strava_last_upload_label)} · " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(last))
            else getString(R.string.strava_never_uploaded)
        }

        val interval = prefs.getStravaPollInterval()
        val retry = prefs.getStravaMaxRetry()
        chipPoll2.setBackgroundResource(if (interval == 2000L) R.drawable.bg_dark_chip_sel else R.drawable.bg_dark_chip_unsel)
        chipPoll2.setTextColor(getColor(if (interval == 2000L) R.color.primary_dark else R.color.text_primary))
        chipPoll5.setBackgroundResource(if (interval == 5000L) R.drawable.bg_dark_chip_sel else R.drawable.bg_dark_chip_unsel)
        chipPoll5.setTextColor(getColor(if (interval == 5000L) R.color.primary_dark else R.color.text_primary))
        chipRetry8.setBackgroundResource(if (retry == 8) R.drawable.bg_dark_chip_sel else R.drawable.bg_dark_chip_unsel)
        chipRetry8.setTextColor(getColor(if (retry == 8) R.color.primary_dark else R.color.text_primary))
        chipRetry12.setBackgroundResource(if (retry == 12) R.drawable.bg_dark_chip_sel else R.drawable.bg_dark_chip_unsel)
        chipRetry12.setTextColor(getColor(if (retry == 12) R.color.primary_dark else R.color.text_primary))
        chipRetry20.setBackgroundResource(if (retry == 20) R.drawable.bg_dark_chip_sel else R.drawable.bg_dark_chip_unsel)
        chipRetry20.setTextColor(getColor(if (retry == 20) R.color.primary_dark else R.color.text_primary))

        switchDedup.isChecked = prefs.isStravaDedupeEnabled()

        val cookie = prefs.getStravaCookie().orEmpty()
        tvDiagCookie.text = getString(R.string.strava_diag_cookie) + "：" +
            if (cookie.isEmpty()) getString(R.string.strava_diag_cookie_none)
            else getString(R.string.strava_diag_cookie_saved, cookie.length)
        tvDiagParams.text = getString(R.string.strava_diag_params, interval, retry) + " · " +
            if (prefs.isStravaDedupeEnabled()) getString(R.string.strava_dedup_on) else getString(R.string.strava_dedup_off)
    }

    private fun showSessionState(text: String, colorRes: Int) {
        tvSessionState.visibility = View.VISIBLE
        tvSessionState.text = text
        tvSessionState.setTextColor(getColor(colorRes))
    }

    /**
     * 会话自检：IO 线程跑 [StravaApi.checkSession]，结论按 有效=绿 / 失效=红 / 限流·异常=橙 追加到自检日志区。
     * 与旧版 hasSession 布尔版不同，这里直接展示带 HTTP 码的中文明细。
     */
    private fun checkSession() {
        val cookie = prefs.getStravaCookie().orEmpty()
        if (cookie.isBlank()) {
            appendSelfTest("⚠ 未登录：本地没有 Strava Cookie，请先点「登录 Strava」", R.color.log_warn)
            return
        }
        setSelfTestBusy(true)
        progressSession.visibility = View.VISIBLE
        Thread {
            val r = try {
                StravaApi().checkSession(cookie)
            } catch (e: Exception) {
                StravaApi.SessionCheck(false, -1, "❌ 请求失败：${e.message}")
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                progressSession.visibility = View.GONE
                val color = when {
                    r.ok -> R.color.green
                    r.httpCode == 429 -> R.color.log_warn
                    else -> R.color.log_error
                }
                appendSelfTest(r.detail, color)
                setSelfTestBusy(false)
            }
        }.start()
    }

    /** 试传入口：先校验本地 Cookie，再拉起系统文件选择器（仅接受 .fit / .gpx） */
    private fun pickTestUploadFile() {
        val cookie = prefs.getStravaCookie().orEmpty()
        if (cookie.isBlank()) {
            appendSelfTest("⚠ 未登录：本地没有 Strava Cookie，请先点「登录 Strava」", R.color.log_warn)
            return
        }
        filePicker.launch("*/*")
    }

    private fun handlePickedTestFile(uri: Uri) {
        val name = queryDisplayName(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext != "fit" && ext != "gpx") {
            appendSelfTest("⚠ 仅支持 .fit / .gpx 文件，当前选择：${name.ifBlank { "未知文件" }}", R.color.log_warn)
            return
        }
        setSelfTestBusy(true)
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }
            }
            if (data == null) {
                appendSelfTest("❌ 读取文件失败：$uri", R.color.log_error)
                setSelfTestBusy(false)
                return@launch
            }
            val fileName = name.ifBlank { "test_${System.currentTimeMillis()}.$ext" }
            appendSelfTest("📄 已选择 $fileName（${data.size} 字节）", R.color.text_secondary)
            try {
                // 生产链路对照：UploadEngine.kt uploadToStrava → stravaApi.upload（同一 suspend 函数）；
                // 本自检只走该上传函数，不写同步记忆、不走去重键。
                val r = StravaApi().upload(
                    cookie = prefs.getStravaCookie().orEmpty(),
                    data = data,
                    fileName = fileName,
                    onStep = { line -> runOnUiThread { appendSelfTest(line, R.color.text_secondary) } }
                )
                val (summary, color) = when {
                    r.success -> "✅ 上传成功" to R.color.green
                    r.pending -> "⏳ 待确认：已上传未入库，去 Strava 网页端核对，不要重传" to R.color.log_warn
                    r.skipped -> "⏭ 跳过：Strava 已有该活动" to R.color.text_secondary
                    else -> "❌ 失败：${r.message}" to R.color.log_error
                }
                appendSelfTest(summary, color)
            } catch (e: Exception) {
                appendSelfTest("❌ 异常：${e.message}", R.color.log_error)
            } finally {
                setSelfTestBusy(false)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 自检日志追加一行，最多保留最近 30 行；按行着色直接追加，由父级 NestedScrollView 滚动 */
    private fun appendSelfTest(line: String, colorRes: Int) {
        selfTestLines.add(line to colorRes)
        while (selfTestLines.size > 30) selfTestLines.removeAt(0)
        val sb = SpannableStringBuilder()
        selfTestLines.forEachIndexed { i, (text, color) ->
            if (i > 0) sb.append("\n")
            val span = SpannableString(text)
            span.setSpan(ForegroundColorSpan(getColor(color)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(span)
        }
        tvSelfTestLog.text = sb
    }

    private fun setSelfTestBusy(busy: Boolean) {
        btnTestSession.isEnabled = !busy
        btnTestUpload.isEnabled = !busy
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.strava_logout_title)
            .setMessage(R.string.strava_logout_msg)
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ ->
                prefs.clearStravaCookie()
                showSessionState(getString(R.string.strava_session_expired), R.color.log_normal)
                tvSessionState.visibility = View.GONE
                render()
            }
            .show()
    }
}
