package com.doubao.mclauncher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.doubao.mclauncher.core.Downloader
import com.doubao.mclauncher.core.ExportManager
import com.doubao.mclauncher.core.GameInstaller
import com.doubao.mclauncher.core.IOException
import com.doubao.mclauncher.core.LoaderInstaller
import com.doubao.mclauncher.core.Paths
import com.doubao.mclauncher.core.RemoteVersion
import com.doubao.mclauncher.core.VersionRepository
import com.doubao.mclauncher.launch.GameLauncher
import com.doubao.mclauncher.launch.JreInstaller
import com.doubao.mclauncher.launch.JreLocator
import com.doubao.mclauncher.launch.JreRemote
import com.doubao.mclauncher.launch.LaunchService
import com.doubao.mclauncher.launch.PojavBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.doubao.mclauncher.core.McVersion
import com.doubao.mclauncher.core.VersionResolver
import com.doubao.mclauncher.launch.FclCompat
import com.doubao.mclauncher.mod.Modrinth
import com.doubao.mclauncher.mod.ModsManager
import java.io.File

/** 界面状态机 */
enum class Screen { HOME, VERSIONS, JRE, MODS }

/** 加载器类型 */
enum class LoaderType { VANILLA, FORGE, FABRIC }

data class UiState(
    val screen: Screen = Screen.HOME,
    val versionsLoading: Boolean = false,
    val versions: List<RemoteVersion> = emptyList(),
    val versionError: String? = null,
    val installed: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val jreMajors: List<Int> = emptyList(),
    val hasStorage: Boolean = false,
    val freeMb: Long = -1L,
    val busy: Boolean = false,
    val stage: String = "",
    val progress: Float = -1f,
    val logs: List<String> = emptyList(),
    val message: String? = null,
    val running: Boolean = false,
    val pojavInstalled: Boolean = false,
    // ---- 导出到 Pojav 相关 ----
    val exportChecks: List<ExportManager.Check> = emptyList(),
    val exporting: Boolean = false,
    val exportGuidePath: String? = null,
    // ---- JRE 安装页相关 ----
    val jreBusy: Boolean = false,
    val jreProgress: Float = -1f,
    val jreStage: String = "",
    // ---- 模组页相关 ----
    val modQuery: String = "",
    val modGameVersion: String = "1.20.1",
    val modLoader: String = "fabric",
    val modResults: List<Modrinth.Project> = emptyList(),
    val modsLoading: Boolean = false,
    val modError: String? = null,
    val installedMods: List<File> = emptyList(),
    val modBusy: Boolean = false,
    val modStage: String = "",
    val modProgress: Float = -1f,
    val modMessage: String? = null
)

/**
 * ============================================================
 * LauncherViewModel —— 所有耗时操作都在这，UI 只读状态
 * ============================================================
 */
class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private var installJob: Job? = null
    private var launchJob: Job? = null

    init {
        refreshLocal()
    }

    // ------------------------------------------------------------
    // 本地状态刷新：已安装版本 / JRE / 权限 / 空间
    // ------------------------------------------------------------
    fun refreshLocal() {
        val ctx = getApplication<Application>()
        // 立刻刷新不耗时的项，已安装版本列表要扫目录，放后台
        _state.value = _state.value.copy(
            jreMajors = JreInstaller.installedMajors(ctx),
            hasStorage = Paths.hasStoragePermission(),
            freeMb = Paths.freeSpaceMb(),
            pojavInstalled = GameLauncher.isPojavInstalled(ctx),
            loaders = LoaderInstaller.installedLoaders()
        )
        viewModelScope.launch(Dispatchers.IO) {
            val installed = GameLauncher.installedVersions()
            _state.value = _state.value.copy(installed = installed)
        }
    }

    fun setScreen(screen: Screen) {
        _state.value = _state.value.copy(screen = screen)
        // 进版本页时懒加载远程列表
        if (screen == Screen.VERSIONS && _state.value.versions.isEmpty()) {
            loadVersions()
        }
    }

    // ------------------------------------------------------------
    // 拉取版本列表
    // ------------------------------------------------------------
    fun loadVersions() {
        viewModelScope.launch {
            _state.value = _state.value.copy(versionsLoading = true, versionError = null)
            try {
                val list = VersionRepository.fetchVersionList()
                _state.value = _state.value.copy(versions = list, versionsLoading = false)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    versionsLoading = false,
                    versionError = "版本列表获取失败: ${e.message}"
                )
            }
        }
    }

    // ------------------------------------------------------------
    // 安装（原版 / Forge / Fabric）
    // ------------------------------------------------------------
    fun install(versionId: String, loader: LoaderType) {
        if (_state.value.busy) return
        installJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                stage = "准备中",
                progress = 0f,
                logs = emptyList(),
                message = null,
                screen = Screen.HOME
            )

            try {
                // ---- 前置：存储权限 & 空间 ----
                if (!Paths.hasStoragePermission()) {
                    throw IOException("没有存储权限，无法写入 ${Paths.root().absolutePath}")
                }
                if (Paths.freeSpaceMb() in 0..500) {
                    throw IOException("存储空间不足（剩余 ${Paths.freeSpaceMb()}MB），至少需要 500MB")
                }

                // ---- 1. 先装原版（加载器依赖原版 json 和 jar）----
                val installer = GameInstaller()
                val result = installer.install(
                    versionId = versionId,
                    downloadAssets = true,
                    onStage = { text, frac -> publish(text, frac) },
                    onLog = { line -> appendLog(line) }
                )
                appendLog("原版安装完成，Java ${result.version.javaMajorVersion}")

                // ---- 2. 找 JRE（加载器安装要用 Java 干跑安装器）----
                val ctx = getApplication<Application>()
                val jre = JreLocator.find(ctx, result.version.javaMajorVersion)
                if (jre == null && loader != LoaderType.VANILLA) {
                    throw IOException("未找到 Java ${result.version.javaMajorVersion} 运行时，无法安装 ${loader}")
                }

                // ---- 3. 装加载器 ----
                when (loader) {
                    LoaderType.VANILLA -> appendLog("无需安装加载器")

                    LoaderType.FORGE -> {
                        publish("查询 Forge 构建", 1f)
                        val builds = LoaderInstaller.listForge(versionId)
                        if (builds.isEmpty()) throw IOException("${versionId} 没有可用的 Forge 构建")
                        appendLog("找到 ${builds.size} 个 Forge 构建，选最新: ${builds.first().version}")
                        val id = LoaderInstaller.installForge(jre!!, versionId, builds.first()) { appendLog(it) }
                        appendLog("Forge 版本 id: $id")
                    }

                    LoaderType.FABRIC -> {
                        publish("查询 Fabric loader", 1f)
                        val loaders = LoaderInstaller.listFabricLoaders()
                        if (loaders.isEmpty()) throw IOException("Fabric loader 列表为空")
                        val id = LoaderInstaller.installFabric(jre!!, versionId, loaders.first()) { appendLog(it) }
                        appendLog("Fabric 版本 id: $id")
                    }
                }

                publish("完成", 1f)
                _state.value = _state.value.copy(busy = false, message = "安装完成：$versionId")
                refreshLocal()
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    busy = false,
                    message = "安装失败: ${e.message}"
                )
                appendLog("ERROR: ${e.message}")
            }
        }
    }

    /** 取消安装（下载协程会被一起取消） */
    fun cancelTask() {
        installJob?.cancel()
        _state.value = _state.value.copy(busy = false, message = "已取消")
    }

    // ------------------------------------------------------------
    // 启动游戏
    // ------------------------------------------------------------
    fun launch(versionId: String, playerName: String, memoryMb: Int) {
        if (_state.value.running) return
        val ctx = getApplication<Application>()

        launchJob = viewModelScope.launch {
            _state.value = _state.value.copy(running = true, logs = _state.value.logs)
            LaunchService.start(ctx)          // 前台服务，防止进程被回收

            val result = withContext(Dispatchers.IO) {
                try {
                    // ---- 优先走 FCL 后端（合并进 FCL 工程后才存在这个类）----
                    // 用反射调用，好处是：没合并时本工程照样能编（类不存在 → 反射失败 → 走旧路径），
                    // 合进 FCL 工程后自动切到真渲染后端，不用再改 UI 代码。
                    // ---- 一键启动：JRE 没装就自己下载装上，用户什么都不用管 ----
                    try {
                        val major = VersionResolver.resolveLocal(versionId).version.javaMajorVersion
                        if (!FclCompat.isFclJreInstalled(ctx, major)) {
                            val build = JreRemote.CATALOG.firstOrNull { it.major == major }
                            if (build != null) {
                                appendLog("未检测到 Java $major，自动下载并安装（仅首次，约 ${build.sizeBytes / 1024 / 1024} MB）…")
                                JreRemote.install(
                                    ctx, build,
                                    onProgress = { p, _, _ ->
                                        _state.value = _state.value.copy(progress = p, stage = "安装 Java $major")
                                    },
                                    onLog = { appendLog(it) }
                                )
                                appendLog("Java $major 安装完成，继续启动")
                                _state.value = _state.value.copy(jreMajors = JreInstaller.installedMajors(ctx))
                            } else {
                                appendLog("警告：没有 Java $major 的自动安装源，尝试直接启动")
                            }
                        }
                    } catch (e: Throwable) {
                        appendLog("自动安装 JRE 失败（继续尝试启动）: ${e.message}")
                    }

                    appendLog("尝试 FCL 后端 …")
                    val fcl = runCatching {
                        Class.forName("com.doubao.mclauncher.backend.FclLauncher")
                    }.getOrNull()
                    if (fcl != null) {
                        val m = fcl.getMethod(
                            "launch",
                            android.content.Context::class.java,
                            String::class.java, String::class.java,
                            Int::class.javaPrimitiveType, String::class.java
                        )
                        val err = m.invoke(
                            null, ctx, versionId, playerName.ifBlank { "Player" }, memoryMb, "gl4es"
                        ) as? String
                        appendLog(err ?: "FCL 后端已接管，游戏窗口即将出现")
                        // FCL 的 JVM 跑在自己的线程里，这里直接返回 0
                        GameLauncher.LaunchResult(0, err, null)
                    } else {
                        appendLog("未检测到 FCL 后端，回退到旧启动路径（该路径已废弃）")
                        GameLauncher.launch(
                            GameLauncher.Config(
                                versionId = versionId,
                                playerName = playerName.ifBlank { "Player" },
                                maxMemoryMb = memoryMb
                            )
                        ) { line -> appendLog(line) }
                    }
                } catch (e: Throwable) {
                    GameLauncher.LaunchResult(-1, e.message, null)
                }
            }

            LaunchService.stop(ctx)
            _state.value = _state.value.copy(
                running = false,
                message = if (result.exitCode == 0) "游戏已退出"
                else "启动失败: ${result.error ?: "code=${result.exitCode}"}"
            )
        }
    }

    fun tailGameLog() {
        appendLog(GameLauncher.tailLog(25))
    }

    // ------------------------------------------------------------
    // 打 PojavLauncher（方案 B）
    // ------------------------------------------------------------
    fun openPojav(): Boolean {
        val ctx = getApplication<Application>()
        val ok = PojavBridge.launch(ctx)
        if (!ok) {
            // 没装就直接把下载页打开，别让用户卡在这
            val opened = PojavBridge.openDownloadPage(ctx)
            _state.value = _state.value.copy(
                message = if (opened) "没装 PojavLauncher，已打开下载页（装完回来点导出）"
                else "没装 PojavLauncher，请手动去 GitHub Releases 下载"
            )
            return false
        }
        _state.value = _state.value.copy(
            message = "已打开 PojavLauncher，去「设置 → 游戏目录」选 ${PojavBridge.gameDirPath()}"
        )
        return true
    }

    /**
     * 一键导出：体检 → 写说明文件 → 拉起 Pojav。
     * 体检结果会留在 exportChecks 里给 UI 展示。
     */
    fun exportToPojav() {
        if (_state.value.exporting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(exporting = true, logs = emptyList())

            val checks = withContext(Dispatchers.IO) {
                try {
                    ExportManager.checkAll()
                } catch (e: Throwable) {
                    appendLog("体检失败: ${e.message}")
                    emptyList()
                }
            }
            appendLog("体检完成：${checks.count { it.ready }}/${checks.size} 个版本就绪")

            val guide = withContext(Dispatchers.IO) {
                try {
                    ExportManager.writeGuide(checks)
                } catch (e: Throwable) {
                    appendLog("写说明文件失败: ${e.message}")
                    null
                }
            }

            _state.value = _state.value.copy(
                exporting = false,
                exportChecks = checks,
                exportGuidePath = guide?.absolutePath,
                message = when {
                    checks.isEmpty() -> "还没有安装任何版本，先去装一个"
                    checks.none { it.ready } -> "没有就绪的版本，看下面的缺失清单补齐"
                    else -> "已导出 ${checks.count { it.ready }} 个版本，去 Pojav 里选目录"
                }
            )

            // 只有真的有就绪版本，才帮用户把 Pojav 拉起来
            if (checks.any { it.ready }) openPojav()
        }
    }

    /** 从 Pojav 切回来时刷新（Pojav 里可能刚下完它的 JRE / 改了目录） */
    fun onResume() {
        refreshLocal()
        // 如果之前做过导出，就顺手重新体检一次，保持状态新鲜
        if (_state.value.exportChecks.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val checks = try {
                    ExportManager.checkAll()
                } catch (e: Throwable) {
                    emptyList()
                }
                _state.value = _state.value.copy(exportChecks = checks)
            }
        }
    }

    // ------------------------------------------------------------
    // JRE 安装（方案 A / C 才需要；方案 B 不需要）
    // ------------------------------------------------------------
    fun installJre(build: JreRemote.Build) {
        if (_state.value.jreBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                jreBusy = true, jreProgress = -1f, jreStage = "准备下载", logs = emptyList()
            )
            try {
                JreRemote.install(
                    context = getApplication(),
                    build = build,
                    onProgress = { frac, done, total ->
                        _state.value = _state.value.copy(
                            jreProgress = frac,
                            jreStage = "下载中 ${Downloader.fmt(done)} / ${if (total > 0) Downloader.fmt(total) else "?"}"
                        )
                    },
                    onLog = { appendLog(it) }
                )
                _state.value = _state.value.copy(jreBusy = false, jreStage = "安装完成")
                refreshLocal()
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    jreBusy = false,
                    jreStage = "失败: ${e.message}",
                    message = "JRE 安装失败: ${e.message}"
                )
                appendLog("JRE 安装失败: ${e.message}")
            }
        }
    }

    /** 手动填一个 tar.xz 直链（GitHub 慢的时候换镜像用） */
    fun installJreFromUrl(url: String, major: Int) {
        if (_state.value.jreBusy || url.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(jreBusy = true, jreProgress = -1f, jreStage = "下载中")
            try {
                JreRemote.installFromUrl(getApplication(), url.trim(), major) { appendLog(it) }
                _state.value = _state.value.copy(jreBusy = false, jreStage = "安装完成")
                refreshLocal()
            } catch (e: Throwable) {
                _state.value = _state.value.copy(jreBusy = false, message = "JRE 安装失败: ${e.message}")
            }
        }
    }

    /** 从文件选择器拿到的 JRE 压缩包，装进私有 runtimes 目录 */

    // ------------------------------------------------------------
    // 模组：搜索 / 安装（含依赖递归）/ 删除
    // ------------------------------------------------------------
    fun setModQuery(q: String) { _state.value = _state.value.copy(modQuery = q) }
    fun setModGameVersion(v: String) { _state.value = _state.value.copy(modGameVersion = v) }
    fun setModLoader(v: String) { _state.value = _state.value.copy(modLoader = v) }

    /** 搜索模组：Modrinth 开放 API，不需要 key */
    fun searchMods(query: String = _state.value.modQuery) {
        val gv = _state.value.modGameVersion.trim()
        val loader = _state.value.modLoader
        if (gv.isEmpty()) {
            _state.value = _state.value.copy(modError = "先填游戏版本（例如 1.20.1）")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(modsLoading = true, modError = null, modQuery = query)
            try {
                val hits = withContext(Dispatchers.IO) { Modrinth.search(query, gv, loader) }
                _state.value = _state.value.copy(modsLoading = false, modResults = hits)
                if (hits.isEmpty()) {
                    _state.value = _state.value.copy(modError = "没搜到结果（$gv / $loader）")
                }
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    modsLoading = false,
                    modError = "搜索失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 安装一个模组：自动带上 required 依赖（Fabric API 这类），
     * 全部下载到 /sdcard/games/Minecraft/mods。
     */
    fun installMod(project: Modrinth.Project) {
        if (_state.value.modBusy) return
        val gv = _state.value.modGameVersion.trim()
        val loader = _state.value.modLoader
        viewModelScope.launch {
            _state.value = _state.value.copy(
                modBusy = true, modStage = "解析 ${project.title} 的依赖…", modProgress = -1f, modMessage = null
            )
            try {
                val targets = withContext(Dispatchers.IO) { Modrinth.resolve(project.slug, gv, loader) }
                if (targets.isEmpty()) {
                    _state.value = _state.value.copy(modBusy = false, modMessage = "这个模组没有适配 $gv / $loader 的版本")
                    return@launch
                }
                var idx = 0
                for (t in targets) {
                    idx++
                    _state.value = _state.value.copy(
                        modStage = "($idx/${targets.size}) 下载 ${t.file.fileName}"
                    )
                    withContext(Dispatchers.IO) {
                        ModsManager.download(t.file) { p ->
                            _state.value = _state.value.copy(modProgress = p)
                        }
                    }
                }
                val mods = ModsManager.list()
                _state.value = _state.value.copy(
                    modBusy = false,
                    modProgress = 1f,
                    installedMods = mods,
                    modMessage = "已安装 ${targets.size} 个文件（含依赖）到 ${ModsManager.modsDir().absolutePath}"
                )
            } catch (e: Throwable) {
                _state.value = _state.value.copy(modBusy = false, modMessage = "安装失败: ${e.message}")
            }
        }
    }

    fun deleteMod(file: File) {
        ModsManager.delete(file)
        _state.value = _state.value.copy(
            installedMods = ModsManager.list(),
            modMessage = "已删除 ${file.name}"
        )
    }

    fun toggleMod(file: File) {
        ModsManager.toggle(file)
        _state.value = _state.value.copy(installedMods = ModsManager.list())
    }

    fun refreshMods() {
        _state.value = _state.value.copy(installedMods = ModsManager.list())
    }

    fun installJreFromUri(uri: android.net.Uri, major: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val name = uri.lastPathSegment ?: "jre"
                val cache = File(ctx.cacheDir, name.substringAfterLast('/'))
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    cache.outputStream().use { input.copyTo(it) }
                } ?: throw IOException("无法读取所选文件")

                val dir = if (cache.name.endsWith(".zip")) {
                    JreInstaller.installZip(ctx, cache, major) { appendLog(it) }
                } else {
                    JreInstaller.installTarXz(ctx, cache, major) { appendLog(it) }
                }
                appendLog("JRE 已安装到: ${dir.absolutePath}")
                refreshLocal()
            } catch (e: Throwable) {
                appendLog("JRE 安装失败: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------
    private fun publish(stage: String, progress: Float) {
        _state.value = _state.value.copy(stage = stage, progress = progress)
    }

    private fun appendLog(line: String) {
        val old = _state.value.logs
        val newList = (old + line).takeLast(300)   // 只留最后 300 行，防内存涨
        _state.value = _state.value.copy(logs = newList)
    }
}
