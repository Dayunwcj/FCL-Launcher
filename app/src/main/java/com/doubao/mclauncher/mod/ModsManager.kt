package com.doubao.mclauncher.mod

import android.util.Log
import com.doubao.mclauncher.core.Http
import com.doubao.mclauncher.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * ============================================================
 * ModsManager —— 模组的落地、枚举、删除
 * ============================================================
 *
 * 目录必须和 FCL 的 workingDir 对上：我们在 FclLauncher 里传的是
 * workingDir = /sdcard/games/Minecraft/，FCL 启动时就是往
 * <workingDir>/mods 里加载模组，所以这里也必须是同一个目录。
 */
object ModsManager {

    private const val TAG = "ModsManager"

    /** /sdcard/games/Minecraft/mods */
    fun modsDir(): File = File(Paths.root(), "mods").apply { if (!exists()) mkdirs() }

    /** 已安装的模组（.jar / .jar.disabled） */
    fun list(): List<File> =
        modsDir().listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".jar.disabled")) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /**
     * 下载一个模组文件到 mods/。
     * 返回落盘后的 File；文件名重复时覆盖（同名视为同一版本重装）。
     */
    suspend fun download(
        file: Modrinth.ModFile,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        modsDir().mkdirs()
        val target = File(modsDir(), sanitize(file.fileName))
        val tmp = File(modsDir(), target.name + ".part")
        if (tmp.exists()) tmp.delete()

        val req = Request.Builder()
            .url(file.url)
            .header("User-Agent", "MCLauncher/1.0 (Android)")
            .build()

        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("下载失败 HTTP ${resp.code}: ${file.url}")
            val body = resp.body ?: throw java.io.IOException("响应为空: ${file.url}")
            val total = if (file.size > 0) file.size else body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            onProgress((done.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    out.flush()
                }
            }
        }

        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // 跨文件系统等极端情况，退化成复制
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        Log.i(TAG, "模组已安装: ${target.absolutePath} (${target.length()} B)")
        onProgress(1f)
        target
    }

    /** 删除模组 */
    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /** 启用 / 禁用（改名加 .disabled，FCL 就不加载了） */
    fun toggle(file: File): File {
        return if (file.name.endsWith(".disabled")) {
            File(file.parentFile, file.name.removeSuffix(".disabled"))
        } else {
            File(file.parentFile, file.name + ".disabled")
        }.also { newFile ->
            if (file.renameTo(newFile)) Log.i(TAG, "模组状态切换: ${newFile.name}")
        }
    }

    /** 文件名里不能带的字符处理一下 */
    private fun sanitize(name: String): String {
        var n = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (n.isEmpty()) n = "mod-${System.currentTimeMillis()}.jar"
        if (!n.endsWith(".jar")) n += ".jar"
        return n
    }
}
