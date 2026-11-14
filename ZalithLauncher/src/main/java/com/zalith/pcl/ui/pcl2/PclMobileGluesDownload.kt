/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 上游版权：Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 * 本文件自 2026 年起被修改；修改声明与名称/标识的处理见仓库根目录 NOTICE.md。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。分发时请保留本声明、上游版权与 LICENSE。
 */

package com.zalith.pcl.ui.pcl2

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.zalith.pcl.game.download.engine.DownloadEngine
import com.zalith.pcl.game.download.engine.DownloadRequest
import com.zalith.pcl.game.download.engine.DownloadStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** MobileGlues 插件下载的状态。 */
sealed interface PclMobileGluesState {
    data object Idle : PclMobileGluesState
    data class Downloading(val downloaded: Long, val total: Long, val speed: Long) : PclMobileGluesState
    data class Ready(val file: File) : PclMobileGluesState
    data class Failed(val message: String) : PclMobileGluesState
}

/**
 * MobileGlues 渲染器插件的后台下载器。
 *
 * 为什么不在弹窗里直接跳浏览器：它是可选插件，用户点「下载」后应该能立刻回去玩别的，
 * 而不是被丢到一个网页里自己找 APK、自己装。这里把「取最新版地址 → 下载 → 拉起安装器」
 * 整条链路放在启动器内部，下载进度对话框可以关掉（后台继续），关掉也不影响任务。
 *
 * 地址来源：MobileGlues 官方 Release 的 `releases/latest`，取 assets 里第一个 `.apk`。
 * 直连 GitHub API 失败时（国内很常见）由调用方降级为「打开发布页手动下载」。
 */
object PclMobileGluesDownload {

    /** MobileGlues 插件的包名（渲染器列表里出现它的前提）。 */
    const val PACKAGE = "com.fcl.plugin.mobileglues"

    private const val API = "https://api.github.com/repos/MobileGL-Dev/MobileGlues-release/releases/latest"
    private const val DIR_NAME = "mobileglues"
    private const val FILE_NAME = "MobileGlues-latest.apk"
    private const val PROGRESS_INTERVAL_MS = 200L

    var state by mutableStateOf<PclMobileGluesState>(PclMobileGluesState.Idle)
        private set

    private var job: Job? = null
    private var pending: File? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** 设备上是否已安装 MobileGlues 插件。 */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** 开始（或重试）下载。已经在下载时重复调用会被忽略。 */
    fun start(context: Context, scope: CoroutineScope) {
        if (isRunning) return
        val app = context.applicationContext
        state = PclMobileGluesState.Downloading(0L, -1L, 0L)
        job = scope.launch {
            try {
                val remote = withContext(Dispatchers.IO) { resolveLatestApk() }
                val dir = File(app.cacheDir, DIR_NAME).apply { mkdirs() }
                val target = File(dir, FILE_NAME)
                // 上一次的残包会让引擎误以为已经下完，先删掉重来
                runCatching { if (target.exists()) target.delete() }

                val stats = DownloadStats()
                val progress = launch {
                    while (isActive) {
                        state = PclMobileGluesState.Downloading(
                            downloaded = stats.downloadedBytes,
                            total = if (remote.second > 0) remote.second else stats.expectedTotalBytes,
                            speed = stats.refreshSpeed(),
                        )
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                try {
                    DownloadEngine.download(
                        request = DownloadRequest(
                            urls = listOf(remote.first),
                            targetFile = target,
                            expectedSize = remote.second,
                        ),
                        stats = stats,
                    )
                } finally {
                    progress.cancel()
                }
                pending = target
                state = PclMobileGluesState.Ready(target)
            } catch (e: CancellationException) {
                state = PclMobileGluesState.Idle
                throw e
            } catch (e: Exception) {
                state = PclMobileGluesState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "下载失败")
            }
        }
    }

    /** 取消下载（进度对话框里的「取消下载」）。 */
    fun cancel() {
        job?.cancel()
        job = null
        pending = null
        state = PclMobileGluesState.Idle
    }

    /** 关掉对话框但让下载继续跑。 */
    fun hideResult() {
        if (state is PclMobileGluesState.Failed) {
            state = PclMobileGluesState.Idle
        }
    }

    /** 用系统安装器打开下好的 APK。 */
    fun install(context: Context) {
        val file = pending ?: (state as? PclMobileGluesState.Ready)?.file ?: return
        if (!file.exists()) {
            state = PclMobileGluesState.Failed("安装包已不存在，请重新下载")
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            state = PclMobileGluesState.Failed("无法打开安装器：${it.message.orEmpty()}")
        }
    }

    /** 下载完成后标记为已处理（安装器已经拉起，别再弹同一个框）。 */
    fun consume() {
        if (state is PclMobileGluesState.Ready) state = PclMobileGluesState.Idle
    }

    /** 查最新 Release，返回 (下载地址, 大小)。 */
    private fun resolveLatestApk(): Pair<String, Long> {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ZPCL-Launcher")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("获取下载地址失败（HTTP ${conn.responseCode}）")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val assets = JSONObject(text).optJSONArray("assets")
                ?: throw IllegalStateException("发布页里没有可下载的文件")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return asset.getString("browser_download_url") to asset.optLong("size", -1L)
                }
            }
            throw IllegalStateException("发布页里没有 APK")
        } finally {
            conn.disconnect()
        }
    }
}
