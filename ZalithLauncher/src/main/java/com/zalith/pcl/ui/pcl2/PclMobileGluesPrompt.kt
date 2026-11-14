/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 上游版权：Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 * 本文件自 2026 年起被修改；修改声明与名称/标识的处理见仓库根目录 NOTICE.md。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。分发时请保留本声明、上游版权与 LICENSE。
 */

/*
 * Zalith Launcher 2 — PCL2 UI port
 *
 * 首次启动（自动解压完成后）检查 MobileGlues 渲染器插件是否安装。
 * 它必须作为独立 APK 插件存在，启动器不能把渲染库直接编进来，所以这里只负责
 * 发现缺失并询问。用户可以选择「后台下载」，由启动器自己取最新版、下包并拉起安装器；
 * 也可以随时关掉进度框让它继续在后台跑。
 */

package com.zalith.pcl.ui.pcl2

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zalith.pcl.ui.pcl2.components.PclConfirmDialog

private const val MOBILE_GLUES_RELEASES =
    "https://github.com/MobileGL-Dev/MobileGlues-release/releases"

private fun isPackageInstalled(context: Context): Boolean =
    PclMobileGluesDownload.isInstalled(context)

private fun openReleasesPage(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(MOBILE_GLUES_RELEASES))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun formatMb(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

@Composable
fun PclMobileGluesPromptHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var promptVisible by remember { mutableStateOf(false) }
    var progressVisible by remember { mutableStateOf(false) }
    val state = PclMobileGluesDownload.state

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("pcl2.mobileglues", Context.MODE_PRIVATE)
        if (prefs.getBoolean("prompted", false)) return@LaunchedEffect
        if (isPackageInstalled(context)) return@LaunchedEffect
        promptVisible = true
    }

    fun markPrompted() {
        context.getSharedPreferences("pcl2.mobileglues", Context.MODE_PRIVATE)
            .edit().putBoolean("prompted", true).apply()
    }

    // 1) 首次提示
    if (promptVisible) {
        PclConfirmDialog(
            title = "未检测到 MobileGlues 渲染器",
            content = "MobileGlues 是独立的渲染器插件，需要单独安装。\n" +
                "可以直接在本启动器里后台下载，也可以稍后到发布页手动下载。",
            confirmText = "后台下载",
            dismissText = "暂不",
            onConfirm = {
                promptVisible = false
                markPrompted()
                progressVisible = true
                PclMobileGluesDownload.start(context, scope)
            },
            onDismiss = {
                promptVisible = false
                markPrompted()
            },
        )
    }

    // 2) 下载进度（可以关掉，「后台运行」不影响任务）
    (state as? PclMobileGluesState.Downloading)?.let { d ->
        if (progressVisible) {
            val total = d.total
            val percent = if (total > 0L) (d.downloaded * 100 / total).toInt() else -1
            val progressText = when {
                total > 0L -> "$percent%（${formatMb(d.downloaded)} / ${formatMb(total)}）"
                d.downloaded > 0L -> formatMb(d.downloaded)
                else -> "正在获取下载地址…"
            }
            PclConfirmDialog(
                title = "正在下载 MobileGlues",
                content = "$progressText\n\n可以关掉这个窗口，下载会在后台继续。",
                confirmText = "后台运行",
                dismissText = "取消下载",
                dismissOnOutsideClick = false,
                onConfirm = { progressVisible = false },
                onDismiss = {
                    progressVisible = false
                    PclMobileGluesDownload.cancel()
                },
            )
        }
    }

    // 3) 下载完成
    if (state is PclMobileGluesState.Ready) {
        PclConfirmDialog(
            title = "MobileGlues 已下载",
            content = "安装后可在「设置 → 控制设置 → 渲染器」中选择。是否现在安装？",
            confirmText = "安装",
            dismissText = "稍后",
            onConfirm = {
                PclMobileGluesDownload.install(context)
                PclMobileGluesDownload.consume()
            },
            onDismiss = { PclMobileGluesDownload.consume() },
        )
    }

    // 4) 下载失败
    (state as? PclMobileGluesState.Failed)?.let { f ->
        PclConfirmDialog(
            title = "下载失败",
            content = "${f.message}\n\n也可以在浏览器里手动下载安装。",
            confirmText = "重试",
            dismissText = "打开网页",
            danger = true,
            onConfirm = {
                progressVisible = true
                PclMobileGluesDownload.start(context, scope)
            },
            onDismiss = {
                openReleasesPage(context)
                PclMobileGluesDownload.hideResult()
            },
        )
    }
}
