package com.doubao.mclauncher.launch

import android.content.Context
import com.doubao.mclauncher.core.Paths
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * ============================================================
 * JreLocator —— 找 JRE（Java 运行时）
 * ============================================================
 *
 * APK 里是没法带 JRE 的（几百 MB，且 Google Play 限制 200MB），
 * 所以启动器实际的做法是「第一次运行时把 JRE 解压到私有目录」。
 *
 * 本类支持三种来源，按优先级从高到低：
 *
 *  1) 我们自己装好的：/sdcard/games/Minecraft/runtimes/<大版本>/
 *  2) 用户手动放的同一个目录（比如从 PojavLauncher 的 APK 里解出来的 jre）
 *  3) App 私有目录：/data/data/<包名>/files/runtimes/<大版本>/
 *
 * 说明：直接读 PojavLauncher 的 /data/data/net.kdt.pojavlaunch/ 是读不到的
 * （Android 沙箱隔离），除非你用 sharedUserId 并和它同签名，所以别指望“借用”。
 * 现实做法见 README 的「方案 B」：把 JRE 解出来自己用。
 */
object JreLocator {

    /** 判断一个目录是不是完整的 JRE（至少有 libjvm.so 和 lib/ 目录） */
    fun isValidJre(dir: File?): Boolean {
        if (dir == null || !dir.isDirectory) return false
        return findLibJvm(dir) != null
    }

    /** 找 libjvm.so，返回它的父父目录（即 JRE 根）用不到，这里只判断存在性 */
    fun findLibJvm(jreDir: File): File? {
        val candidates = listOf(
            "lib/server/libjvm.so",
            "lib/aarch64/server/libjvm.so",
            "lib/arm64/server/libjvm.so",
            "lib/arm/server/libjvm.so",
            "jre/lib/server/libjvm.so",
            "jre/lib/aarch64/server/libjvm.so",
            "libjvm.so"
        )
        for (c in candidates) {
            val f = File(jreDir, c)
            if (f.isFile) return f
        }
        // 兜底：深度不超过 3 层地找一遍（有些 JRE 打包结构很怪）
        return walk(jreDir, 0)
    }

    private fun walk(dir: File, depth: Int): File? {
        if (depth > 3) return null
        val children = dir.listFiles() ?: return null
        for (f in children) {
            if (f.isFile && f.name == "libjvm.so") return f
        }
        for (f in children) {
            if (f.isDirectory) {
                walk(f, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /**
     * 按 Java 大版本号找可用的 JRE。
     * @param major 版本 json 里的 javaVersion.majorVersion（8 / 17 / 21）
     */
    fun find(context: Context, major: Int): File? {
        val candidates = ArrayList<File>()

        // 1) 外部存储里的 runtimes 目录（我们安装的 / 用户放的）
        candidates += Paths.runtime(major)
        // 2) runtimes 下名字里带版本号的所有子目录，例如 "17" "jre17" "17-arm64"
        Paths.runtimes().listFiles()?.forEach { d ->
            if (d.isDirectory && d.name.contains(major.toString())) candidates += d
        }
        // 3) App 私有目录（JreInstaller 默认解压到这里）
        candidates += FclCompat.fclJreDir(context, major)
        candidates += File(context.filesDir, "runtimes/$major")

        for (c in candidates) {
            if (isValidJre(c)) return c
        }
        return null
    }

    /** 列出所有可用 JRE（设置页展示） */
    fun listAll(context: Context): List<File> {
        val roots = listOf(Paths.runtimes(), File(context.filesDir, "runtimes"))
        val out = ArrayList<File>()
        for (r in roots) {
            r.listFiles()?.forEach { if (isValidJre(it)) out += it }
        }
        return out
    }

    /**
     * 从 zip 安装 JRE（Pojav 的 JRE 一般是 tar.xz，见 JreInstaller；
     * 这里处理“别人给的 zip 版 JRE”）。
     */
    fun installFromZip(context: Context, zipFile: File, major: Int): File {
        val target = File(context.filesDir, "runtimes/$major")
        target.mkdirs()
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(target, entry.name)
                // 防目录穿越（zip slip）
                if (!outFile.canonicalPath.startsWith(target.canonicalPath)) {
                    entry = zis.nextEntry; continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return target
    }
}
