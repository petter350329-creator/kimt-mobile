package kr.re.kimt.mobile

// ================================================================
//  Updater — 앱 안에서 새 버전 받기 (2026-11-23)
//
//  · GitHub Releases 의 최신 빌드 번호(build-N)와 이 앱의 빌드 번호를 비교한다.
//  · 새 것이 있으면 "업데이트할까요?" → 앱이 APK 를 받아서 설치 화면을 띄운다.
//  · 안드로이드는 앱이 몰래 설치하는 것을 막아 두었다 → 마지막에 [설치] 한 번은
//    사람이 눌러야 한다 (플레이스토어 앱이 아니면 어쩔 수 없는 부분).
//  · 처음 한 번은 "이 출처의 앱 설치 허용" 을 켜야 한다 → 설정 화면으로 데려간다.
//  · 화면(일정·폴더·업로드)은 원래 PC 의 Suite 가 내려주므로 이 업데이트 없이도
//    바로 바뀐다. 이건 '앱 껍데기'(스캐너·QR·전화 연결 등)가 바뀔 때만 필요하다.
// ================================================================

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class Updater(private val act: Activity) {

    companion object {
        const val RELEASE_API = "https://api.github.com/repos/petter350329-creator/kimt-mobile/releases/latest"
        const val ACTION_STATUS = "kr.re.kimt.mobile.INSTALL_STATUS"
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    }

    private val prefs = act.getSharedPreferences("kimt", Context.MODE_PRIVATE)
    private var busy = false

    fun currentBuild(): Long = try {
        val pi = act.packageManager.getPackageInfo(act.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
    } catch (e: Exception) {
        0L
    }

    /** 앱을 열 때 — 6시간에 한 번만 조용히 확인 */
    fun checkQuietly() {
        val last = prefs.getLong("upd_checked", 0L)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        check(manual = false)
    }

    /** manual = 설정에서 [업데이트 확인] 을 누른 경우 (최신이어도 알려준다) */
    fun check(manual: Boolean) {
        if (busy) return
        busy = true
        Thread {
            var latest = 0L
            var apkUrl = ""
            var err = ""
            try {
                val c = URL(RELEASE_API).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "kimt-mobile")
                val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                c.disconnect()
                val j = JSONObject(text)
                latest = Regex("(\\d+)").find(j.optString("tag_name"))?.value?.toLongOrNull() ?: 0L
                val assets = j.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                prefs.edit().putLong("upd_checked", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                err = e.message ?: "연결 실패"
            }
            act.runOnUiThread {
                busy = false
                val cur = currentBuild()
                when {
                    err.isNotEmpty() -> if (manual) toast("업데이트 확인 실패: $err")
                    latest > cur && apkUrl.isNotEmpty() -> ask(latest, apkUrl)
                    manual -> toast("최신 버전이에요 (빌드 $cur)")
                }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()

    private fun ask(latest: Long, apkUrl: String) {
        if (act.isFinishing) return
        AlertDialog.Builder(act)
            .setTitle("새 버전이 있어요")
            .setMessage("KIMT 모바일 빌드 $latest (지금 ${currentBuild()})\n받아서 설치할까요? 설치 화면에서 [업데이트] 를 한 번 눌러 주세요.")
            .setPositiveButton("업데이트") { _, _ -> start(apkUrl) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun start(apkUrl: String) {
        // 처음 한 번 — "이 출처의 앱 설치 허용"
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("설치 허용이 필요해요 (처음 한 번)")
                .setMessage("다음 화면에서 'KIMT 모바일' 의 [이 출처 허용] 을 켜고 돌아와서, 업데이트를 다시 눌러 주세요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    try {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + act.packageName)))
                    } catch (e: Exception) {
                        toast("설정을 열지 못했어요: ${e.message}")
                    }
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        val label = android.widget.TextView(act).apply {
            text = "받는 중… 0%"
            textSize = 16f
            setPadding(60, 40, 60, 20)
        }
        val dlg = AlertDialog.Builder(act).setTitle("업데이트 받는 중").setView(label)
            .setCancelable(false).show()
        Thread {
            try {
                val c = URL(apkUrl).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.setRequestProperty("User-Agent", "kimt-mobile")
                val total = c.contentLengthLong
                val pi = act.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (total > 0) params.setSize(total)
                val sid = pi.createSession(params)
                val session = pi.openSession(sid)
                c.inputStream.use { input ->
                    session.openWrite("kimt-mobile.apk", 0, if (total > 0) total else -1).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    act.runOnUiThread { label.text = "받는 중… $pct%" }
                                }
                            }
                        }
                        session.fsync(out)
                    }
                }
                c.disconnect()
                val intent = Intent(act, act.javaClass).setAction(ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getActivity(act, 4401, intent, flags)
                session.commit(pending.intentSender)
                session.close()
                act.runOnUiThread { dlg.dismiss() }
            } catch (e: Exception) {
                act.runOnUiThread {
                    dlg.dismiss()
                    toast("업데이트 받기 실패: ${e.message}")
                }
            }
        }.start()
    }

    /** 설치기가 돌려준 결과 (MainActivity.onNewIntent 에서 넘겨준다) */
    fun onStatus(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    try {
                        act.startActivity(confirm)
                    } catch (e: Exception) {
                        toast("설치 화면을 열지 못했어요: ${e.message}")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> toast("업데이트 완료")
            PackageInstaller.STATUS_FAILURE_ABORTED -> toast("업데이트를 취소했어요")
            else -> toast("업데이트 실패: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""))
        }
    }
}
