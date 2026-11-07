package com.doubao.mclauncher.core

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * ============================================================
 * ExportManager —— 导出前自检 + 生成导入说明
 * ============================================================
 *
 * "一键导出"要解决的问题是：用户在 Pojav 里点启动，结果黑屏/闪退，
 * 却不知道是哪个文件没下完。所以导出前先做一次体检：
 *
 *   ✓ 版本 json 在（含 inheritsFrom 父版本链完整）
 *   ✓ client.jar 在（加载器版本要查父版本的 jar）
 *   ✓ 所有 libraries 都在（缺几个、缺哪个，列出来）
 *   ✓ 资源索引在，且抽样 60 个 assets 对象都在
 *
 * 检查完在游戏目录写一份《导入Pojav说明.txt》，用户照着一二三步点就行。
 */
object ExportManager {

    private const val TAG = "ExportManager"

    /** 单个版本的体检结果 */
    data class Check(
        val id: String,
        val ready: Boolean,
        val problems: List<String>,
        val isLoader: Boolean
    ) {
        /** 一行摘要，UI 直接用 */
        val summary: String
            get() = if (ready) "就绪 ✓" else "缺 ${problems.size} 项"
    }

    /** 体检单个版本 */
    fun check(id: String): Check {
        val problems = ArrayList<String>()
        var isLoader = false
        try {
            isLoader = id.contains("forge", true) || id.contains("fabric", true)

            // ---- 1. 版本 json + 继承链 ----
            val resolved = VersionResolver.resolveLocal(id)
            val version = resolved.version

            // ---- 2. 客户端 jar ----
            val jar = Paths.clientJar(resolved.clientJarVersion)
            if (!jar.isFile || jar.length() < 1024) {
                problems += "client.jar 缺失（应来自 ${resolved.clientJarVersion}）"
            }

            // ---- 3. 依赖库 ----
            var missingLibs = 0
            val missingNames = ArrayList<String>()
            for (path in version.classpathEntries) {
                val f = File(Paths.libraries(), path)
                if (!f.isFile || f.length() < 1024) {
                    missingLibs++
                    if (missingNames.size < 5) missingNames += path.substringAfterLast('/')
                }
            }
            if (missingLibs > 0) {
                problems += "依赖库缺 $missingLibs 个（${missingNames.joinToString("、")}${if (missingLibs > 5) "…" else ""}）"
            }

            // ---- 4. 资源索引 ----
            val indexName = version.assetsIndexName.ifEmpty { "legacy" }
            val indexFile = File(Paths.assetIndexes(), "$indexName.json")
            if (!indexFile.isFile) {
                problems += "资源索引缺失（assets/indexes/$indexName.json）"
            } else {
                // ---- 5. 抽样检查 assets 对象（全量查要读几千次磁盘，没必要） ----
                sampleMissing(indexFile)?.let { problems += it }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "体检失败: $id", e)
            problems += e.message ?: "未知错误"
        }
        return Check(id, problems.isEmpty(), problems, isLoader)
    }

    /** 体检所有已安装版本 */
    fun checkAll(): List<Check> = VersionResolver.listPlayable().map { check(it) }

    /**
     * 从资源索引里等距抽 60 个 object，看文件在不在。
     * @return null = 没发现问题；否则返回一句人话
     */
    private fun sampleMissing(indexFile: File): String? {
        return try {
            val json = JSONObject(indexFile.readText())
            val objects = json.optJSONObject("objects") ?: return null
            val keys = objects.keys()
            val all = ArrayList<String>(objects.length())
            while (keys.hasNext()) all += keys.next()
            if (all.isEmpty()) return null

            val sampleSize = minOf(60, all.size)
            val step = maxOf(1, all.size / sampleSize)
            var checked = 0
            var missing = 0
            var i = 0
            while (i < all.size && checked < sampleSize) {
                val key = all[i]
                val o = objects.optJSONObject(key)
                val hash = o?.optString("hash") ?: ""
                if (hash.length >= 4) {
                    checked++
                    val f = File(Paths.assetObjects(), "${hash.substring(0, 2)}/$hash")
                    if (!f.isFile) missing++
                }
                i += step
            }
            if (checked == 0 || missing == 0) null
            else "资源文件缺失（抽样 $checked 个，缺 $missing 个，建议重新安装并勾选下载资源）"
        } catch (e: Throwable) {
            Log.w(TAG, "抽样检查失败", e)
            null
        }
    }

    /**
     * 生成《导入Pojav说明.txt》，写完返回文件。
     * 用户从 Pojav 里直接看不到这个 txt，但用文件管理器能找到，也算留个凭证。
     */
    fun writeGuide(checks: List<Check>): File {
        val out = File(Paths.root(), "导入Pojav说明.txt")
        val dir = Paths.root().absolutePath
        val readyIds = checks.filter { it.ready }.map { it.id }
        val text = buildString {
            appendLine("=== 把本目录导入 PojavLauncher（方案 B）===")
            appendLine()
            appendLine("游戏目录：$dir")
            appendLine("（这个目录就是标准 .minecraft 结构：versions / libraries / assets）")
            appendLine()
            appendLine("步骤：")
            appendLine("1. 打开 PojavLauncher")
            appendLine("2. 进「设置 / Settings」，找到「游戏目录 / Game directory」")
            appendLine("3. 选择上面这个目录（如果选择器里看不到，先授予 Pojav 存储权限）")
            appendLine("4. 回到 Pojav 版本列表，就能看到下面这些版本，直接点启动")
            appendLine()
            appendLine("已就绪的版本（${readyIds.size} 个）：")
            if (readyIds.isEmpty()) appendLine("  （无，请先在 MC 启动器里装一个版本）")
            else readyIds.forEach { appendLine("  - $it") }
            appendLine()
            val bad = checks.filter { !it.ready }
            if (bad.isNotEmpty()) {
                appendLine("未就绪的版本（先回启动器补齐）：")
                bad.forEach { c ->
                    appendLine("  - ${c.id}")
                    c.problems.forEach { appendLine("      · $it") }
                }
                appendLine()
            }
            appendLine("提示：Pojav 自带的渲染层会处理 LWJGL 原生库，")
            appendLine("      我们这边不需要、也不应该给它塞 Mojang 的 linux natives。")
            appendLine()
            appendLine("生成时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        }
        out.writeText(text)
        return out
    }
}
