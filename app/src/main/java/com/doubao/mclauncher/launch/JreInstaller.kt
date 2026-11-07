package com.doubao.mclauncher.launch

import android.content.Context
import android.util.Log
import com.doubao.mclauncher.core.Paths
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * ============================================================
 * JreInstaller —— 把 JRE 装进手机
 * ============================================================
 *
 * PojavLauncher 的 JRE 发布包是 .tar.xz（每个大版本一个），
 * 所以这里要能解 tar.xz，也要能解普通 zip。
 *
 * 建议的 JRE 来源（README 里有详细路径）：
 *   PojavLauncherTeam/android-openjdk-build-multiarch 的 Releases，
 *   下载 jre17-aarch64.tar.xz 这类文件，放进 App 的 assets 目录或者用系统文件选择器导入。
 */
object JreInstaller {

    private const val TAG = "JreInstaller"

    /**
     * 解压 .tar.xz 到 <context.filesDir>/runtimes/<major>/
     * @return 解压后的 JRE 根目录（若压缩包里有一层顶层目录，会自动往下钻一层）
     */
    fun installTarXz(context: Context, archive: File, major: Int, onLog: (String) -> Unit = {}): File {
        val target = FclCompat.fclJreDir(context, major)
        target.mkdirs()
        onLog("开始解压 JRE: ${archive.name} -> ${target.absolutePath}")

        XZInputStream(BufferedInputStream(FileInputStream(archive), 64 * 1024)).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val name = entry.name.trimStart('.', '/')
                    val out = File(target, name)
                    // 防目录穿越
                    if (!out.canonicalPath.startsWith(target.canonicalPath)) {
                        entry = tar.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                        // 保留可执行位（解压出来的部分文件需要）
                        try {
                            out.setExecutable(true, false)
                        } catch (e: Throwable) {
                            Log.w(TAG, "设置可执行位失败: ${out.name}")
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }

        // 有些压缩包顶层还有一层目录（如 jre17/），钻进去
        val inner = target.listFiles()?.firstOrNull { it.isDirectory && JreLocator.isValidJre(it) }
        val result = inner ?: target
        onLog("JRE 安装完成: ${result.absolutePath}（有效=${JreLocator.isValidJre(result)}）")
        return result
    }

    /** 解 zip 版 JRE */
    fun installZip(context: Context, archive: File, major: Int, onLog: (String) -> Unit = {}): File {
        val target = FclCompat.fclJreDir(context, major)
        target.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(target, entry.name)
                if (!out.canonicalPath.startsWith(target.canonicalPath)) {
                    entry = zis.nextEntry; continue
                }
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val inner = target.listFiles()?.firstOrNull { it.isDirectory && JreLocator.isValidJre(it) }
        return inner ?: target
    }

    /**
     * 从 App 的 assets 里装 JRE（适合自己把 jre.tar.xz 打进 APK 的场景）。
     * 注意：assets 里的文件不能直接当 File 用，必须先复制到缓存目录。
     */
    fun installFromAssets(context: Context, assetName: String, major: Int): File {
        val cache = File(context.cacheDir, "jre_$major.tar.xz")
        context.assets.open(assetName).use { input ->
            cache.outputStream().use { input.copyTo(it) }
        }
        return installTarXz(context, cache, major)
    }

    /** 检测 runtimes 目录里已经装了哪些版本 */
    fun installedMajors(context: Context): List<Int> =
        JreLocator.listAll(context).mapNotNull { it.name.toIntOrNull() }.sorted()

    /** 私有目录不可用时，回退到 /sdcard/games/Minecraft/runtimes 的空间统计 */
    fun runtimesDir(context: Context): File = File(context.filesDir, "runtimes")

    /** 初始化：确保 runtimes 目录存在 */
    fun ensureDir(context: Context) {
        runtimesDir(context).mkdirs()
        Paths.runtimes().mkdirs()
    }
}
