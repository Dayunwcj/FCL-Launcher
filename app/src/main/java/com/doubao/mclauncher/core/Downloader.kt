package com.doubao.mclauncher.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ============================================================
 * 下载模块：OkHttp + 协程，带进度回调
 * ============================================================
 *
 * 设计要点：
 * 1. 先写 .part 临时文件，校验 SHA1 通过后再改名 —— 断网/杀进程不会留下坏文件；
 * 2. 目标文件已存在且 SHA1 一致就跳过 —— 重复安装版本不会重复下载几个 G；
 * 3. 用 Semaphore 控制并发（默认 6），MC 依赖动辄上百个 jar，串行要下十分钟；
 * 4. 进度是“全局聚合”的：所有并发任务共用一个 AtomicLong，
 *    所以 UI 上的进度条是整体的，不会来回跳。
 */
class Downloader(private val client: OkHttpClient = Http.client) {

    /** 一个下载任务：从哪下、存到哪、期望的 sha1（官方 json 里一般都有） */
    data class Item(
        val url: String,
        val dest: File,
        val sha1: String? = null,
        val size: Long = -1L,        // 期望大小，仅用于进度条预估
        // 是否走 BMCLAPI 镜像。少数接口镜像路径不稳（比如资源索引），
        // 需要能直连官方地址重试。
        val useMirror: Boolean = true
    )

    /** 进度快照，直接推给 UI */
    data class Progress(
        val doneBytes: Long,
        val totalBytes: Long,
        val doneFiles: Int,
        val totalFiles: Int,
        val current: String
    ) {
        /** 0f~1f；总量未知时返回 -1 让 UI 显示“不确定进度条” */
        val fraction: Float
            get() = if (totalBytes <= 0L) -1f
            else (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

        /** 人类可读：12.3 MB / 480 MB */
        fun text(): String = "${fmt(doneBytes)} / ${fmt(totalBytes)}  ($doneFiles/$totalFiles)"
    }

    /**
     * 批量下载。
     * @return 每个任务落盘后的 File
     */
    suspend fun downloadAll(
        items: List<Item>,
        concurrency: Int = 6,
        onProgress: (Progress) -> Unit = {}
    ): List<File> = coroutineScope {
        val totalBytes = items.sumOf { if (it.size > 0) it.size else 0L }
        val doneBytes = AtomicLong(0)
        val doneFiles = AtomicInteger(0)
        val totalFiles = items.size
        val semaphore = Semaphore(concurrency)

        items.map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    // ---- 已经下过且校验通过：直接算完成 ----
                    if (item.dest.isFile && item.dest.length() > 0 &&
                        (item.sha1 == null || sha1Of(item.dest) == item.sha1)
                    ) {
                        doneBytes.addAndGet(item.dest.length())
                        val d = doneFiles.incrementAndGet()
                        onProgress(Progress(doneBytes.get(), totalBytes, d, totalFiles, item.dest.name))
                        return@withPermit item.dest
                    }

                    // ---- 真正下载 ----
                    val file1 = downloadOne(item) { delta ->
                        val now = doneBytes.addAndGet(delta)
                        onProgress(
                            Progress(now, totalBytes, doneFiles.get(), totalFiles, item.dest.name)
                        )
                    }
                    doneFiles.incrementAndGet()
                    file1
                }
            }
        }.awaitAll()
    }

    /**
     * 单文件下载。onBytes 回调的是“本次新增的字节数”，方便上层做全局累加。
     */
    suspend fun downloadOne(item: Item, onBytes: (Long) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val dest = item.dest
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")

            val request = Request.Builder()
                .url(if (item.useMirror) Api.mirror(item.url) else item.url)   // 统一改写镜像
                .header("User-Agent", Api.UA)
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}: ${item.url}")
                val body = resp.body ?: throw IOException("响应体为空: ${item.url}")

                var lastReport = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            lastReport += n
                            onBytes(n.toLong())
                        }
                    }
                }
            }

            // ---- 校验：有 sha1 就一定校验，防 CDN 返回错误页 ----
            if (item.sha1 != null && !item.sha1.equals(sha1Of(tmp), ignoreCase = true)) {
                tmp.delete()
                throw IOException("SHA1 校验失败，文件已删除，请重试: ${item.url}")
            }

            // ---- 原子替换：先删旧文件再改名，跨分区时退化成复制 ----
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest
        }

    companion object {
        private const val TAG = "Downloader"

        /** 计算文件 SHA1，流式读，避免大 jar 爆内存 */
        fun sha1Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** 字节数格式化 */
        fun fmt(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / 1073741824.0)
            bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
            bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }

        /** 日志辅助，避免到处写 Log */
        fun log(msg: String) = Log.i(TAG, msg)
    }
}
