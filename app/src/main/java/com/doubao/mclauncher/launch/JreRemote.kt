package com.doubao.mclauncher.launch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.doubao.mclauncher.core.Downloader
import java.io.File

/**
 * ============================================================
 * JreRemote —— PojavLauncher 预编译 JRE 的直链清单 + 下载安装
 * ============================================================
 *
 * 重要：**只用方案 B（交给 Pojav 启动）的话，这一页根本不用装 JRE**。
 * JRE 只在你要走方案 A（自己 dlopen libjvm 起 JVM）或方案 C（fork Pojav）时才需要。
 *
 * 下面的直链是我从 PojavLauncherTeam/android-openjdk-build-multiarch 的
 * releases 页里实际抓出来的（不是猜的），实测 302 → 200，21MB：
 *
 *   tag: jre17-ca01427  →  jre17-arm64-20220817-release.tar.xz   （21,057,916 字节）
 *   tag: jre8-40df388   →  jre8-arm64-20220811-release.tar.xz
 *
 * 注意两个限制（别踩）：
 *   1. 这个仓库只提供 **arm64 / arm / x86 / x86_64** 的 JRE8 和 JRE17，**没有 Java 21**。
 *      MC 1.20.5 及以后要求 Java 21，这类版本只能走方案 B（Pojav 自己的 JRE 管理）。
 *   2. GitHub 在国内可能很慢/连不上，所以同时提供了「从文件导入」的入口，
 *      你也自己下好 tar.xz 用文件选择器导入。
 */
object JreRemote {

    /** 一个可下载的 JRE 构建 */
    data class Build(
        val major: Int,
        val label: String,
        val url: String,
        val sizeBytes: Long,
        val fileName: String
    )

    /**
     * 内置清单（直链，不需要走 GitHub API，避免 API 限流/被墙）。
     * 只列 arm64——绝大多数安卓手机是这个架构，x86 模拟器需要自己换链接。
     */
    val CATALOG: List<Build> = listOf(
        Build(
            major = 17,
            label = "Java 17（arm64）· 适用 MC 1.17 ~ 1.20.4",
            url = "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ca01427/jre17-arm64-20220817-release.tar.xz",
            sizeBytes = 21_057_916L,
            fileName = "jre17-arm64.tar.xz"
        ),
        Build(
            major = 8,
            label = "Java 8（arm64）· 适用 MC 1.12.2 及更早",
            url = "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre8-40df388/jre8-arm64-20220811-release.tar.xz",
            sizeBytes = 0L,   // 大小未知，进度条会退化成不确定态
            fileName = "jre8-arm64.tar.xz"
        )
    )

    /**
     * 下载 + 解压 + 安装到 <filesDir>/runtimes/<major>/。
     * 注意这里要联网，调用方放到 IO 协程里。
     */
    suspend fun install(
        context: Context,
        build: Build,
        onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> },
        onLog: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, build.fileName)
        onLog("开始下载 ${build.label}")
        onLog("地址: ${build.url}")

        // 复用全局 Downloader：临时文件 + 失败重试的骨架都在里面
        Downloader().downloadOne(
            Downloader.Item(
                url = build.url,
                dest = cache,
                sha1 = null,                    // GitHub 没给校验值，只能信 content-length
                size = build.sizeBytes,
                useMirror = false               // GitHub 不走 BMCLAPI，原样请求
            )
        ) { delta ->
            // 这里给的是"本次新增字节"，用文件当前大小当累计值
            val total = if (build.sizeBytes > 0) build.sizeBytes else 0L
            onProgress(
                if (total > 0) (cache.length().toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f,
                cache.length(),
                total
            )
        }

        onLog("下载完成 ${Downloader.fmt(cache.length())}，开始解压")
        val dir = JreInstaller.installTarXz(context, cache, build.major, onLog)
        onLog("JRE 安装完成: ${dir.absolutePath}")
        // 解压完就删掉压缩包，手机空间寸土寸金
        cache.delete()
        dir
    }

    /** 允许用户手填一个 JRE tar.xz 直链（换镜像/换架构时用） */
    suspend fun installFromUrl(
        context: Context,
        url: String,
        major: Int,
        onLog: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "jre-manual-$major.tar.xz")
        Downloader().downloadOne(
            Downloader.Item(url, cache, null, -1L, useMirror = false)
        ) { }
        onLog("下载完成，开始解压")
        JreInstaller.installTarXz(context, cache, major, onLog)
    }
}
