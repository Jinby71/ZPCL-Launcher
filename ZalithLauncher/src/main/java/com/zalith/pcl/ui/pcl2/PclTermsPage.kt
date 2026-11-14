/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 上游版权：Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 * 本文件自 2026 年起被修改；修改声明与名称/标识的处理见仓库根目录 NOTICE.md。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。分发时请保留本声明、上游版权与 LICENSE。
 */

package com.zalith.pcl.ui.pcl2

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zalith.pcl.R
import com.zalith.pcl.setting.launcherMMKV
import com.zalith.pcl.ui.pcl2.components.PclButton
import com.zalith.pcl.ui.pcl2.components.PclColorState
import com.zalith.pcl.ui.pcl2.components.PclText
import com.zalith.pcl.ui.pcl2.theme.PclDimens
import com.zalith.pcl.ui.pcl2.theme.PclPalettes
import com.zalith.pcl.ui.pcl2.theme.PclTheme
import com.zalith.pcl.ui.pcl2.theme.pclPalette
import kotlinx.coroutines.delay

/** 条款版本号：改条款内容时把这里的数字加一，用户会重新看到一次。 */
private const val TERMS_VERSION = 1
private const val TERMS_KEY = "terms_accepted_version"

/** 必须停留的最短时间（秒）。 */
private const val MIN_READ_SECONDS = 10

/** 已同意的条款版本；没同意过返回 0。 */
fun pclTermsAcceptedVersion(): Int = runCatching {
    launcherMMKV().getInt(TERMS_KEY, 0)
}.getOrDefault(0)

/** 当前版本的条款是否已经同意过。 */
fun pclTermsAccepted(): Boolean = pclTermsAcceptedVersion() >= TERMS_VERSION

private fun markTermsAccepted() {
    runCatching { launcherMMKV().putInt(TERMS_KEY, TERMS_VERSION) }
}

/**
 * 首次启动的条款确认页。
 *
 * 三个硬性条件全部满足才允许点「同意」：
 *  1. 正文**滚动到最底部**（内容不足一屏时直接算已到底）；
 *  2. 从打开本页起**停留满 10 秒**；
 *  3. 用户主动点击按钮（不自动跳转）。
 *
 * 未同意前返回键被拦截，只能用底部「退出」离开 —— 避免「随手按返回」跳过条款。
 */
@Composable
fun PclTermsScreen(onAccept: () -> Unit) {
    PclTheme(palette = PclPalettes.blue) {
        PclTermsContent(onAccept = onAccept)
    }
}

@Composable
private fun PclTermsContent(onAccept: () -> Unit) {
    val p = pclPalette
    val context = LocalContext.current
    val scroll = rememberScrollState()

    var startedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var now by remember { mutableLongStateOf(startedAt) }

    // 计时器：200ms 一跳，够刷新「还需 N 秒」，又不会每帧重组。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            now = SystemClock.elapsedRealtime()
        }
    }

    val waitedSeconds = ((now - startedAt) / 1000L).toInt()
    val remainingSeconds = (MIN_READ_SECONDS - waitedSeconds).coerceAtLeast(0)
    // 内容不足一屏时 maxValue 就是 0，此时视为「已读到结尾」。
    val readToEnd by remember {
        derivedStateOf { scroll.maxValue == 0 || scroll.value >= scroll.maxValue - 4 }
    }
    val canAccept = readToEnd && remainingSeconds == 0

    BackHandler(enabled = true) {
        // 未同意前不允许返回键退出；要离开请用底部「退出」按钮。
    }

    Column(modifier = Modifier.fillMaxSize().background(p.bg1)) {
        // ---- 标题栏：跟主界面同一套配方（三段渐变 + 应用图标），不再是纯色通栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PclDimens.titleBarHeight)
                .background(Brush.horizontalGradient(p.titleBarGradient)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    // 必须用 PNG 版 logo：`R.mipmap.ic_launcher` 在 Android 8+ 是
                    // `<adaptive-icon>` XML，Compose 的 `painterResource` 不认这种 drawable，
                    // 会抛 IllegalArgumentException（Only VectorDrawables and rasterized
                    // asset types are supported）—— 启动即崩就是它。
                    painter = painterResource(R.drawable.ic_launcher_logo),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                PclText(text = "ZPCL Launcher", color = Color.White, fontSize = 16, bold = true)
                PclText(
                    text = "使用条款与项目说明",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12,
                )
            }
        }

        // ---- 正文（唯一可滚动区域）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            PclSection("一、项目性质")
            PclBody(
                "本程序为 Zalith Launcher 2 的非官方修改版，依据 GNU 通用公共许可证第 3 版（GPLv3）发布。" +
                    "与 Zalith Launcher 官方及其作者无隶属关系，亦未获其背书。启动核心沿用 Zalith Launcher 2，" +
                    "界面版式参考 Plain Craft Launcher 2。本程序不含 Minecraft 游戏本体。",
            )

            PclSection("二、使用条件")
            PclBody("使用者须自行持有合法的 Minecraft 账号，并自行准备运行所需的存储空间与网络环境。")
            PclBody("下载功能依赖 Modrinth、CurseForge、BMCLAPI 等第三方服务；第三方服务不可用时相关功能受限。")

            PclSection("三、不提供的内容")
            PclBody("不提供游戏本体、账号、Mod、整合包及其他受版权保护的内容。")
            PclBody("不对第三方资源的可用性、兼容性与安全性作出保证，亦不提供账号找回、申诉或商业支持。")

            PclSection("四、数据与隐私")
            PclBody("设置、实例与账号凭据保存在本机应用私有目录，不上传至本项目作者。")
            PclBody("下载与更新请求直接发往对应第三方服务，其数据处理适用该服务方的隐私政策。")
            PclBody("卸载应用将清除本地设置与实例记录，需要保留的存档请自行备份。")

            PclSection("五、许可与源代码")
            PclBody(
                "本程序依据 GPLv3 发布，许可全文见「关于」页的「许可原文」。依据 GPLv3 第 6 条，" +
                    "与安装包对应的完整源代码可从「关于」页的「源代码与许可」取得。",
            )
            PclBody(
                "修改与再分发须继续遵循 GPLv3，并遵守上游依 GPLv3 第 7 条附加的名称与标注要求。",
            )

            PclSection("六、免责声明")
            PclBody(
                "本程序按「现状」提供，不附带任何明示或默示担保。在法律允许的最大范围内，" +
                    "作者与贡献者不对使用或无法使用本程序所造成的任何损失承担责任。",
            )
            PclBody("点击「同意」即表示已阅读并接受上述全部内容；如不同意，请退出并卸载本程序。")

            Spacer(modifier = Modifier.height(20.dp))
            PclText(text = "—— 以上为全部内容 ——", color = p.gray3, fontSize = 12)
            Spacer(modifier = Modifier.height(10.dp))
        }

        // ---- 底部操作区（固定在窗口底部，不随正文滚动）
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(p.gray5))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PclText(
                text = when {
                    remainingSeconds > 0 -> "请阅读条款，还需 $remainingSeconds 秒"
                    !readToEnd -> "请滚动到条款末尾"
                    else -> "已阅读完毕，可以继续"
                },
                color = if (canAccept) p.gray2 else p.gray3,
                fontSize = 12,
                modifier = Modifier.weight(1f),
            )
            PclButton(
                text = "退出",
                colorState = PclColorState.Red,
                onClick = { (context as? Activity)?.finish() },
            )
            PclButton(
                text = "我已阅读并同意",
                enabled = canAccept,
                onClick = {
                    markTermsAccepted()
                    onAccept()
                },
            )
        }
    }
}

@Composable
private fun PclSection(text: String) {
    val p = pclPalette
    Spacer(modifier = Modifier.height(14.dp))
    PclText(text = text, color = p.color2, fontSize = 14, bold = true)
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun PclBody(text: String) {
    val p = pclPalette
    // 条款正文里的 **加粗** 标记直接去掉，PclText 不支持富文本；保留纯文本即可。
    PclText(
        text = text.replace("**", ""),
        color = p.color1,
        fontSize = 13,
        modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
    )
}
