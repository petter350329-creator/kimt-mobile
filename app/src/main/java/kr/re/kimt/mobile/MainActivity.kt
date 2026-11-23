package kr.re.kimt.mobile

// ================================================================
//  KIMT 모바일 — 회사 PC 의 KIMT Suite 화면(/m/)을 띄우는 안드로이드 앱
//
//  · 화면 자체는 Suite 가 보내주는 웹 화면이다 (일정·팀 동선·전화·지도).
//    그래서 화면을 고칠 때는 이 앱을 다시 깔 필요 없이 Suite 만 고치면 된다.
//  · 이 앱이 하는 일
//      1) 처음 한 번 QR 찍기 → 회사 PC 주소 기억 + 기기 등록 화면 열기
//      2) 로그인 토큰을 앱 저장소에 보관 (window.KIMTApp 다리)
//      3) 전화(tel:)·지도 앱(intent:, nmap:, geo:) 링크를 진짜 앱으로 넘기기
//      4) 연결이 안 되면 [다시 시도] / [QR 다시 등록] 화면
//  · 3단계(2026-11-20): KIMTApp.scan(json) → 문서 스캐너(테두리 자동) →
//    PC 에 이름을 물어(/api/m/upload/plan) 한 장씩 올린다(/api/m/upload).
//    진행은 window.kimtScanEvent({type:...}) 로 화면에 알린다.
// ================================================================

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : Activity() {

    private val green = Color.parseColor("#22A660")
    private val ink = Color.parseColor("#173326")
    private val sub = Color.parseColor("#5E7A6B")
    private val bg = Color.parseColor("#F1F8F4")

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private var pendingScan: JSONObject? = null
    private val reqScan = 7001

    private var server: String
        get() = prefs.getString("server", "") ?: ""
        set(v) { prefs.edit().putString("server", v).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("kimt", MODE_PRIVATE)
        root = FrameLayout(this)
        root.setBackgroundColor(bg)
        setContentView(root)

        // Android 15 부터는 화면이 상태바 밑까지 그려진다 → 그만큼 비워 둔다
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }

        if (server.isEmpty()) showStart(null) else showWeb("$server/m/")
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundBg(color: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun bigButton(text: String, primary: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(if (primary) Color.WHITE else green)
        background = roundBg(if (primary) green else Color.WHITE, if (primary) null else green)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
    }

    // ── 처음 화면 (등록 전) / 연결 실패 화면 ─────────────────────
    private fun showStart(error: String?) {
        destroyWeb()
        root.removeAllViews()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(48), dp(28), dp(28))
        }
        col.addView(TextView(this).apply {
            text = "KIMT 모바일"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
        })

        if (error == null) {
            col.addView(TextView(this).apply {
                text = "회사 PC 의 KIMT Suite 와 연결해요.\n\n" +
                    "① PC 에서 Suite → 설정 → 폰 접속 → [＋ 새 폰 등록]\n" +
                    "② 아래 [QR 찍어서 등록] 을 누르고 PC 화면의 QR 을 찍어요"
                textSize = 15f
                setTextColor(sub)
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(18), 0, dp(10))
            })
            col.addView(bigButton("📷  QR 찍어서 등록", true) { scanQr() })
            col.addView(bigButton("주소 직접 입력", false) { askAddress() })
        } else {
            col.addView(TextView(this).apply {
                text = "회사 PC 에 연결이 안 돼요\n\n" +
                    "· 회사 PC 가 켜져 있고 Suite 가 실행 중인지\n" +
                    "· (사내 주소를 쓰는 경우) 폰의 Tailscale 이 켜져 있는지 확인해 주세요\n\n" +
                    "주소: $server\n($error)"
                textSize = 15f
                setTextColor(sub)
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(18), 0, dp(10))
            })
            col.addView(bigButton("다시 시도", true) { showWeb("$server/m/") })
            col.addView(bigButton("QR 다시 찍어서 등록", false) { scanQr() })
            col.addView(bigButton("주소 직접 입력", false) { askAddress() })
        }
        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    // ── QR 찍기 (구글 플레이 서비스 스캐너 — 카메라 권한 없이 동작) ──
    private fun scanQr() {
        val opts = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { code -> handleQr(code.rawValue ?: "") }
            .addOnFailureListener { e ->
                Toast.makeText(this, "QR 스캐너를 열지 못했어요: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    //  Suite 의 등록 QR = http://<PC 주소>:8765/m/#pair=123456
    private fun handleQr(raw: String) {
        val u = Uri.parse(raw.trim())
        val ok = (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty() &&
            (u.path ?: "").startsWith("/m")
        if (!ok) {
            Toast.makeText(this, "KIMT Suite 의 등록 QR 이 아니에요", Toast.LENGTH_LONG).show()
            return
        }
        val port = if (u.port > 0) ":${u.port}" else ""
        server = "${u.scheme}://${u.host}$port"
        showWeb(raw.trim())
    }

    private fun askAddress() {
        val input = EditText(this).apply {
            hint = "예) https://kimt-pc.○○.ts.net"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Uri.parse(server).host ?: "")
        }
        val box = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("회사 PC 주소")
            .setMessage("Suite 설정 → 폰 접속의 외부 주소(https://…ts.net)를 넣어요. 등록 코드 6자리는 다음 화면에서 넣어요.")
            .setView(box)
            .setPositiveButton("연결") { _, _ ->
                var a = input.text.toString().trim().trimEnd('/')
                if (a.isEmpty()) return@setPositiveButton
                if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
                val u = Uri.parse(a)
                if (u.host.isNullOrEmpty()) return@setPositiveButton
                // https(외부 주소·Funnel)는 포트 없이, http(사내망·Tailscale IP)는 8765
                server = when {
                    u.port > 0 -> "${u.scheme}://${u.host}:${u.port}"
                    u.scheme == "https" -> "https://${u.host}"
                    else -> "${u.scheme}://${u.host}:8765"
                }
                showWeb("$server/m/")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── Suite 화면 ────────────────────────────────────────────────
    private fun destroyWeb() {
        web?.let { w ->
            root.removeView(w)
            w.stopLoading()
            w.destroy()
        }
        web = null
    }

    private fun showWeb(url: String) {
        destroyWeb()
        root.removeAllViews()
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.cacheMode = WebSettings.LOAD_DEFAULT
        w.settings.setSupportZoom(false)
        w.setBackgroundColor(bg)
        w.addJavascriptInterface(Bridge(), "KIMTApp")
        w.webChromeClient = WebChromeClient()
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleLink(request.url)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showStart(error.description?.toString() ?: "연결 실패")
            }
        }
        root.addView(w, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
        w.loadUrl(url)
    }

    //  true = 앱 밖으로 넘겼다 / false = 이 화면 안에서 연다
    private fun handleLink(uri: Uri): Boolean {
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme == "http" || scheme == "https") {
            if (uri.host == Uri.parse(server).host) return false   // Suite 화면 안에서
            return open(Intent(Intent.ACTION_VIEW, uri))            // 그 밖의 웹 → 브라우저
        }
        if (scheme == "intent") {
            val target = try {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } catch (e: Exception) {
                return true
            }
            target.addCategory(Intent.CATEGORY_BROWSABLE)
            target.component = null
            target.selector = null
            try {
                startActivity(target)
            } catch (e: ActivityNotFoundException) {
                // 지도 앱이 안 깔려 있으면 플레이스토어로
                val pkg = target.`package`
                if (!pkg.isNullOrEmpty()) {
                    open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                } else {
                    Toast.makeText(this, "열 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show()
                }
            }
            return true
        }
        // tel:, geo:, nmap:, kakaomap: 등
        return open(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun open(intent: Intent): Boolean {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "열 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    // ── 3단계: 서류 스캔 → 올리기 ────────────────────────────────
    private fun scanEvent(ev: JSONObject) {
        runOnUiThread {
            web?.evaluateJavascript("window.kimtScanEvent && window.kimtScanEvent($ev)", null)
        }
    }

    private fun startScan(json: String) {
        val job = try {
            JSONObject(json)
        } catch (e: Exception) {
            scanEvent(JSONObject().put("type", "error").put("message", "잘못된 스캔 요청"))
            return
        }
        pendingScan = job
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                try {
                    @Suppress("DEPRECATION")
                    startIntentSenderForResult(sender, reqScan, null, 0, 0, 0)
                } catch (e: Exception) {
                    pendingScan = null
                    scanEvent(JSONObject().put("type", "error").put("message", "스캐너를 열지 못했어요: ${e.message}"))
                }
            }
            .addOnFailureListener { e ->
                pendingScan = null
                scanEvent(JSONObject().put("type", "error").put("message", "스캐너를 열지 못했어요: ${e.message}"))
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqScan) return
        val job = pendingScan ?: return
        pendingScan = null
        val result = if (resultCode == RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(data) else null
        val pages = result?.pages?.map { it.imageUri } ?: emptyList()
        if (pages.isEmpty()) {
            scanEvent(JSONObject().put("type", "cancel"))
            return
        }
        Thread { uploadPages(job, pages) }.start()
    }

    private fun uploadPages(job: JSONObject, pages: List<Uri>) {
        val saved = JSONArray()
        val errors = JSONArray()
        try {
            val acc = job.optString("acc")
            val pick = job.optInt("pick", 0)
            scanEvent(JSONObject().put("type", "plan").put("count", pages.size))
            val planReq = JSONObject()
                .put("acc", acc).put("pick", pick).put("kind", job.optString("kind"))
                .put("count", pages.size).put("modality", job.optString("modality"))
                .put("opt", job.optString("opt")).put("custom", job.optString("custom"))
            val plan = postJson("$server/api/m/upload/plan", planReq)
            if (!plan.optBoolean("ok")) {
                scanEvent(JSONObject().put("type", "error").put("message", plan.optString("message", "이름을 정하지 못했어요")))
                return
            }
            val names = plan.getJSONArray("names")
            for (i in pages.indices) {
                val name = names.optString(i, "")
                scanEvent(JSONObject().put("type", "progress").put("i", i + 1).put("n", pages.size).put("name", name))
                val bytes = contentResolver.openInputStream(pages[i])?.use { it.readBytes() }
                if (bytes == null) {
                    errors.put("$name: 사진을 읽지 못했어요")
                    continue
                }
                val url = "$server/api/m/upload?acc=" + URLEncoder.encode(acc, "UTF-8") +
                    "&pick=$pick&name=" + URLEncoder.encode(name, "UTF-8")
                val res = postBytes(url, bytes)
                if (res.optBoolean("ok")) saved.put(res.optString("saved", name))
                else errors.put(name + ": " + res.optString("message", "실패"))
            }
            scanEvent(JSONObject().put("type", "done").put("saved", saved).put("errors", errors))
        } catch (e: Exception) {
            scanEvent(JSONObject().put("type", "error").put("saved", saved)
                .put("message", "올리다 끊겼어요: ${e.message}"))
        }
    }

    private fun openPost(url: String, contentType: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 90000
        c.doOutput = true
        c.setRequestProperty("Content-Type", contentType)
        c.setRequestProperty("X-KIMT-Token", prefs.getString("token", "") ?: "")
        return c
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val stream = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("message", "HTTP ${c.responseCode}")
        }
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val c = openPost(url, "application/json; charset=utf-8")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return readJson(c)
        } finally {
            c.disconnect()
        }
    }

    private fun postBytes(url: String, bytes: ByteArray): JSONObject {
        val c = openPost(url, "image/jpeg")
        try {
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            return readJson(c)
        } finally {
            c.disconnect()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }

    // ── Suite 화면(app.js)이 부르는 다리: window.KIMTApp ───────────
    inner class Bridge {
        @JavascriptInterface
        fun getToken(): String = prefs.getString("token", "") ?: ""

        @JavascriptInterface
        fun saveToken(t: String?) {
            prefs.edit().putString("token", t ?: "").apply()
        }

        @JavascriptInterface
        fun resetServer() {
            runOnUiThread {
                prefs.edit().remove("server").remove("token").apply()
                showStart(null)
            }
        }

        @JavascriptInterface
        fun scan(json: String) {
            runOnUiThread { startScan(json) }
        }

        @JavascriptInterface
        fun appVersion(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
