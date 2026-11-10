package com.doubao.mclauncher.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * ============================================================
 * 原版（Vanilla）安装器：把一个版本“装到能启动”
 * ============================================================
 *
 * 一次完整安装包含 5 步，代码顺序就是执行顺序：
 *   1. 取版本 json 并落盘
 *   2. 下 client.jar
 *   3. 下 libraries（几百个 jar，并发下）
 *   4. 下 log4j 配置（否则启动一堆红色警告）
 *   5. 下 assets（资源索引 + 几千个 object，可选，但没资源进游戏是紫黑块）
 */
class GameInstaller(private val downloader: Downloader = Downloader()) {

    data class Result(
        val version: McVersion,
        val clientJar: File,
        val libraryJars: List<File>,
        val assetsDir: File
    )

    /**
     * @param versionId       例如 "1.20.1"
     * @param downloadAssets  是否下载资源文件（关掉可以快速验证启动链路）
     * @param onStage         阶段文案 + 该阶段内的进度（0~1）
     * @param onLog           日志行，直接打到 UI 的滚动日志区
     */
    suspend fun install(
        versionId: String,
        downloadAssets: Boolean = true,
        onStage: (String, Float) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {

        Paths.ensure(
            Paths.root(), Paths.versions(), Paths.versionDir(versionId),
            Paths.libraries(), Paths.assets(), Paths.assetIndexes(), Paths.assetObjects(),
            Paths.natives(versionId), Paths.logs()
        )

        // ========== 1. 版本 json ==========
        onStage("获取版本信息 $versionId", 0f)
        val json = VersionRepository.fetchVersionJson(versionId)
        val version = McVersion(json)
        val jsonFile = Paths.versionJson(versionId)
        jsonFile.writeText(json.toString(2))
        onLog("版本 json 已保存: ${jsonFile.absolutePath}")
        onLog("mainClass = ${version.mainClass}, 需要 Java ${version.javaMajorVersion}")

        // ========== 2. client.jar ==========
        onStage("下载客户端 client.jar", 0f)
        val jarUrl = version.clientJarUrl ?: Api.clientJar(versionId)
        val clientJar = downloader.downloadOne(
            Downloader.Item(jarUrl, Paths.clientJar(versionId), version.clientJarSha1, version.clientJarSize)
        ) { }
        onLog("client.jar 完成: ${Downloader.fmt(clientJar.length())}")

        // ========== 3. libraries ==========
        val libItems = mutableListOf<Downloader.Item>()
        var skippedNative = 0

        for (lib in version.libraries) {
            // 主 jar 一律要（即使是 native 条目里的主 jar，也可能被其他库依赖）
            lib.artifact?.let { art ->
                if (art.path.isNotEmpty() && art.url.isNotEmpty()) {
                    libItems += Downloader.Item(
                        url = art.url,
                        dest = File(Paths.libraries(), art.path),
                        sha1 = art.sha1,
                        size = art.size
                    )
                }
            }

            // 原生库（.so）：Android 上不能直接用 Mojang 发的 linux 版，
            // 这里先跳过，交给 Pojav 的 LWJGL 原生库接管（详见 README 的“坑”章节）
            for (n in lib.nativesClassifiers) {
                skippedNative++
                onLog("跳过 Mojang 原生库（Android 需用 Pojav 版）: ${n.path}")
            }
        }

        onStage("下载依赖库（${libItems.size} 个文件）", 0f)
        downloader.downloadAll(libItems, concurrency = 6) { p ->
            onStage("下载依赖库 ${p.doneFiles}/${p.totalFiles}", p.fraction.coerceAtLeast(0f))
        }
        onLog("依赖库下载完成")

        // ========== 4. log4j 配置 ==========
        version.loggingFileUrl?.let { url ->
            onStage("下载日志配置", 0f)
            val dest = File(Paths.assets(), "log_configs/${version.loggingFileId ?: "client.xml"}")
            try {
                downloader.downloadOne(Downloader.Item(url, dest, null, -1)) { }
                onLog("日志配置已下载: ${dest.name}")
            } catch (e: Throwable) {
                onLog("日志配置下载失败（不影响启动）: ${e.message}")
            }
        }

        // ========== 5. assets ==========
        if (downloadAssets) {
            installAssets(version, onStage, onLog)
        } else {
            onLog("已跳过资源下载（游戏内会缺少音效/语言/贴图）")
        }

        // ========== 6. 老版本 natives 解压 ==========
        // 1.12 及以前把 .so/.dll 塞在 natives-linux.jar 里，需要解到 natives 目录。
        // 现代版本（1.13+）把 natives 当普通 library 放在 classpath，不用解。
        extractLegacyNatives(version, onLog)

        if (skippedNative > 0) {
            onLog("提示：本次跳过 $skippedNative 个 Mojang linux 原生库，请确认已配置 Pojav 的 LWJGL 原生库")
        }

        onStage("安装完成", 1f)
        Result(version, clientJar, libItems.map { it.dest }, Paths.assets())
    }

    /**
     * 只补缺 —— 启动前的自愈。
     *
     * 场景：老安装包可能只下了 client.jar（或下载中断），进游戏就报
     * NoClassDefFoundError: joptsimple/OptionSpec 这类错（classpath 里只有 client.jar）。
     * 这里读**本地**版本 json，把缺失的 client.jar 与 libraries 补下来，不碰 assets、不请求版本列表。
     *
     * @return 实际补的文件数（0 表示本来就是齐的）
     */
    suspend fun repairMissing(versionId: String, onLog: (String) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val jsonFile = Paths.versionJson(versionId)
        if (!jsonFile.isFile) return@withContext 0
        val version = McVersion(JSONObject(jsonFile.readText()))

        val items = ArrayList<Downloader.Item>()

        // 1) client.jar
        val jar = Paths.clientJar(versionId)
        if (!jar.isFile) {
            version.clientJarUrl?.let { items += Downloader.Item(it, jar, version.clientJarSha1, version.clientJarSize) }
        }

        // 2) libraries：只补缺失的
        for (lib in version.libraries) {
            val art = lib.artifact ?: continue
            if (art.path.isEmpty() || art.url.isEmpty()) continue
            val dest = File(Paths.libraries(), art.path)
            if (!dest.isFile) items += Downloader.Item(art.url, dest, art.sha1, art.size)
        }

        if (items.isEmpty()) return@withContext 0
        onLog("检测到 ${items.size} 个依赖文件缺失，自动补齐…")
        downloader.downloadAll(items, concurrency = 6) { p ->
            onLog("补齐依赖 ${p.doneFiles}/${p.totalFiles}")
        }
        onLog("依赖补齐完成（${items.size} 个文件）")
        items.size
    }

    /**
     * 下载资源索引 + 所有 object。
     *
     * assets 的 url 规律：
     *   官方：https://resources.download.minecraft.net/<hash 前2位>/<hash>
     *   镜像：https://bmclapi2.bangbang93.com/assets/<hash 前2位>/<hash>
     * 文件落盘：assets/objects/<hash 前2位>/<hash>（没有扩展名，MC 靠 index 查表）
     */
    private suspend fun installAssets(
        version: McVersion,
        onStage: (String, Float) -> Unit,
        onLog: (String) -> Unit
    ) {
        val indexName = version.assetsIndexName.ifEmpty { "legacy" }
        val indexUrl = version.assetIndexUrl ?: "https://piston-meta.mojang.com/v1/packages/${version.assetIndexSha1 ?: ""}/$indexName.json"

        onStage("下载资源索引 $indexName", 0f)
        val indexFile = File(Paths.assetIndexes(), "$indexName.json")

        // 资源索引在 BMCLAPI 上的路径不太稳（我实测过 /v1/packages/... 时通时不通，
        // 而 /version/{id}/assetIndex 会错误地返回 client.jar），
        // 所以策略是：先试镜像，解析不出来就直连官方重下一次。
        var indexJson: JSONObject? = null
        for (attempt in 1..2) {
            val useMirror = attempt == 1
            try {
                downloader.downloadOne(
                    Downloader.Item(
                        url = indexUrl,
                        dest = indexFile,
                        sha1 = version.assetIndexSha1,
                        size = -1L,
                        useMirror = useMirror
                    )
                ) { }
                indexJson = JSONObject(indexFile.readText())
                break
            } catch (e: Throwable) {
                onLog("资源索引第 $attempt 次获取失败（${if (useMirror) "镜像" else "官方"}）: ${e.message}")
                indexFile.delete()
            }
        }
        val index = indexJson ?: throw IOException("资源索引下载失败，无法继续下载 assets")
        val objects = index.optJSONObject("objects")
        if (objects == null) {
            onLog("资源索引为空，跳过资源下载")
            return
        }

        val items = ArrayList<Downloader.Item>(objects.length())
        val keys = objects.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val o = objects.optJSONObject(key) ?: continue
            val hash = o.optString("hash")
            if (hash.length < 4) continue
            val sub = hash.substring(0, 2)
            val dest = File(Paths.assetObjects(), "$sub/$hash")
            // 已存在就交给 Downloader 的跳过逻辑处理，这里不预判，省得读两次磁盘
            items += Downloader.Item(
                url = "https://resources.download.minecraft.net/$sub/$hash",
                dest = dest,
                sha1 = hash,
                size = o.optLong("size", -1L)
            )
        }

        onLog("资源文件共 ${items.size} 个，开始下载（首次约几百 MB）")
        onStage("下载资源文件", 0f)
        downloader.downloadAll(items, concurrency = 8) { p ->
            onStage("下载资源 ${p.doneFiles}/${p.totalFiles}", p.fraction.coerceAtLeast(0f))
        }

        // legacy 资源（1.6 及以前）还要把 objects 复制成 assets/virtual/legacy 的真实文件名
        if (index.optBoolean("virtual", false) || indexName == "legacy") {
            onLog("该版本使用 legacy 资源，需要在启动时映射虚拟目录（框架暂未实现，见 README）")
        }
    }

    /**
     * 老版本 natives 解压：扫 libraries 里所有 natives 相关 jar，
     * 把里面的 .so 解到 natives/<version>/，启动时通过 -Djava.library.path 指过去。
     */
    private fun extractLegacyNatives(version: McVersion, onLog: (String) -> Unit) {
        val nativeJars = version.libraries
            .flatMap { it.nativesClassifiers }
            .map { File(Paths.libraries(), it.path) }
            .filter { it.isFile }

        if (nativeJars.isEmpty()) return

        val outDir = Paths.natives(version.id)
        outDir.mkdirs()
        for (jar in nativeJars) {
            try {
                ZipFile(jar).use { zip ->
                    for (entry in zip.entries()) {
                        val name = entry.name
                        if (name.contains('/') || name.startsWith("META-INF")) continue
                        if (!name.endsWith(".so")) continue
                        val out = File(outDir, name)
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                onLog("已解压原生库: ${jar.name}")
            } catch (e: Throwable) {
                Log.w("GameInstaller", "解压失败 ${jar.name}", e)
                onLog("解压失败 ${jar.name}: ${e.message}")
            }
        }
    }
}
