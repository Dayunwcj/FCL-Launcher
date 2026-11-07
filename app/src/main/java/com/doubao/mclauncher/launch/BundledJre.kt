package com.doubao.mclauncher.launch

import android.content.Context
import android.util.Log
import java.io.File

/**
 * ============================================================
 * BundledJre —— APK 里自带的 Java 运行时（不依赖任何外部软件）
 * ============================================================
 *
 * 为什么要有这个：
 *   之前 JRE 是首次启动时从 GitHub 下 20 多 MB，网一卡就装不下来，
 *   而且用户会觉得"这 App 还得另外配环境"。
 *   现在把 Android 版的 JRE 直接打进 APK 的 assets，安装完就能用，全程不联网。
 *
 * 打包的文件来自 PojavLauncher 官方 APK 的 components（都是 Android aarch64 的真实运行时）：
 *   assets/jre/jre17/universal.tar.xz    Java 17.0.14 的平台无关部分（lib/modules、conf、legal）
 *   assets/jre/jre17/bin-arm64.tar.xz    Java 17.0.14 的 arm64 部分（bin/java、lib/server/libjvm.so）
 *   assets/jre/jre8/...                  同理，Java 8，给 1.12.2 这类老版本用
 *
 * 装的时候把两个包都解到同一个目录（FCL 认的 app_runtime/java/jre<N>），
 * 解完检查 release 文件和 lib/server/libjvm.so 是否都在，才算成功。
 */
object BundledJre {

    private const val TAG = "BundledJre"

    private fun versionDir(major: Int) = "jre/jre$major"

    /** 这个版本有没有内置包 */
    fun isBundled(ctx: Context, major: Int): Boolean {
        return try {
            val files = ctx.assets.list(versionDir(major)) ?: return false
            files.contains("universal.tar.xz") && files.contains("bin-arm64.tar.xz")
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 把内置 JRE 解到 FCL 认的目录。
     * @return 解压后的 JRE 根目录
     */
    fun install(ctx: Context, major: Int, onLog: (String) -> Unit = {}): File {
        onLog("使用 APK 内置的 Java $major（无需联网）")
        val target = FclCompat.fclJreDir(ctx, major)
        target.mkdirs()

        // 1) 平台无关部分
        val universal = copyAsset(ctx, "${versionDir(major)}/universal.tar.xz", File(ctx.cacheDir, "jre$major-universal.tar.xz"))
        JreInstaller.installTarXz(ctx, universal, major, onLog)
        universal.delete()

        // 2) arm64 二进制（bin/java、lib/server/libjvm.so）
        val bin = copyAsset(ctx, "${versionDir(major)}/bin-arm64.tar.xz", File(ctx.cacheDir, "jre$major-bin-arm64.tar.xz"))
        JreInstaller.installTarXz(ctx, bin, major, onLog)
        bin.delete()

        // 3) 验收：release + libjvm.so 必须存在，否则 FCL 的 dlopen 会失败
        val release = File(target, "release")
        val jvm = File(target, "lib/server/libjvm.so")
        if (!release.isFile || !jvm.isFile) {
            throw IllegalStateException(
                "内置 JRE 解压不完整（release=${release.isFile}, libjvm=${jvm.isFile}）：${target.absolutePath}"
            )
        }
        // 补一下可执行位（部分 ROM 上解压后会丢）
        runCatching { File(target, "bin/java").setExecutable(true, false) }
        onLog("Java $major 就绪: ${target.absolutePath}")
        Log.i(TAG, "bundled jre$major installed -> ${target.absolutePath}")
        return target
    }

    private fun copyAsset(ctx: Context, assetPath: String, dest: File): File {
        if (dest.exists()) dest.delete()
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        }
        return dest
    }
}
