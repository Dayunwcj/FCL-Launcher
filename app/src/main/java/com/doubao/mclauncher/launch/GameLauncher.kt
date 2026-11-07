package com.doubao.mclauncher.launch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.doubao.mclauncher.core.McVersion
import com.doubao.mclauncher.core.Paths
import com.doubao.mclauncher.core.VersionRepository
import com.doubao.mclauncher.core.VersionResolver
import java.io.File

/**
 * ============================================================
 * GameLauncher —— 把 MC 真正拉起来
 * ============================================================
 *
 * 完整链路：
 *   1. 找 JRE（JreLocator）
 *   2. 读版本 json，算 classpath（libraries + client.jar）
 *   3. 用 LaunchArgs 拼 JVM 参数 + 游戏参数
 *   4. 调 NativeBridge 进 C 层创建 JVM（阻塞，直到游戏退出）
 *
 * ⚠️ 必须在子线程调用，JVM 创建 + 游戏主循环会一直占着这个线程。
 */
object GameLauncher {

    private const val TAG = "GameLauncher"

    /** 启动配置，UI 那边收集好后传进来 */
    data class Config(
        val versionId: String,
        val playerName: String,
        val maxMemoryMb: Int = 2048,
        val minMemoryMb: Int = 512,
        val width: Int = 854,
        val height: Int = 480,
        val extraJvmArgs: List<String> = emptyList(),
        val extraGameArgs: List<String> = emptyList()
    )

    data class LaunchResult(
        val exitCode: Int,
        val error: String?,
        val logFile: File?
    )

    /** 启动前的检查，返回 null 表示可以启动，否则返回错误原因 */
    fun preflight(config: Config): String? {
        val vJson = Paths.versionJson(config.versionId)
        if (!vJson.isFile) return "版本未安装：${config.versionId}（请先安装）"

        val resolved = try {
            VersionResolver.resolveLocal(config.versionId)
        } catch (e: Throwable) {
            return "版本解析失败: ${e.message}"
        }
        val version = resolved.version

        // 原版 jar 可能来自父版本（Forge/Fabric 版本自己没有 client.jar）
        val jar = Paths.clientJar(resolved.clientJarVersion)
        if (!jar.isFile) return "client.jar 缺失：${jar.absolutePath}"

        val jre = JreLocator.find(appContext ?: return "上下文未初始化", version.javaMajorVersion)
            ?: return "找不到 Java ${version.javaMajorVersion} 运行时，请先在设置里安装 JRE"

        if (config.playerName.isBlank()) return "用户名不能为空"

        // 内存别超过机型可用值太多，这里只做温和提示
        val freeMb = Paths.freeSpaceMb()
        if (freeMb in 0..200) return "存储空间不足（剩余 ${freeMb}MB）"
        return null
    }

    /** 由 Application 注入，避免到处传 Context */
    var appContext: Context? = null

    /**
     * 真正启动。**阻塞调用**，请放到 IO 线程。
     */
    fun launch(config: Config, onLog: (String) -> Unit = {}): LaunchResult {
        val ctx = appContext ?: return LaunchResult(-1, "上下文未初始化", null)
        if (!NativeBridge.isAvailable()) {
            return LaunchResult(-1, NativeBridge.loadError, null)
        }

        // ---------- 1. 版本信息（含 inheritsFrom 继承解析） ----------
        val resolved = VersionResolver.resolveLocal(config.versionId)
        val version = resolved.version
        val jreDir = JreLocator.find(ctx, version.javaMajorVersion)
            ?: return LaunchResult(-1, "找不到 Java ${version.javaMajorVersion} 运行时", null)
        onLog("使用 JRE: ${jreDir.absolutePath}")
        onLog("版本 ${config.versionId} 继承自 ${resolved.vanillaId}，client.jar 取自 ${resolved.clientJarVersion}")

        // ---------- 2. classpath ----------
        val classpath = ArrayList<File>()
        for (path in version.classpathEntries) {
            val f = File(Paths.libraries(), path)
            if (f.isFile) classpath += f
            else Log.w(TAG, "依赖缺失，跳过: $path")
        }
        // client.jar 用父版本的（Forge 版本目录里没有 jar）
        classpath += Paths.clientJar(resolved.clientJarVersion)
        onLog("classpath 共 ${classpath.size} 个 jar")

        // ---------- 3. 参数 ----------
        val nativesDir = Paths.natives(config.versionId).apply { mkdirs() }
        val logConfig = version.loggingFileId?.let { File(Paths.assets(), "log_configs/$it") }

        // Pojav 的 LWJGL 原生库目录（如果用户放进去了就自动生效）
        val extraLibDirs = listOf(File(Paths.root(), "lwjgl"), File(jreDir, "lib"))
            .filter { it.isDirectory }

        val jvmOptions = LaunchArgs.buildJvmOptions(
            version = version,
            jreDir = jreDir,
            nativesDir = nativesDir,
            classpath = classpath,
            maxMemoryMb = config.maxMemoryMb,
            minMemoryMb = config.minMemoryMb,
            logConfigFile = logConfig,
            extraLibDirs = extraLibDirs,
            extra = config.extraJvmArgs
        )

        val uuid = LaunchArgs.offlineUuid(config.playerName)
        val gameArgs = LaunchArgs.buildGameArgs(
            version = version,
            playerName = config.playerName,
            uuid = uuid,
            accessToken = "0",                 // 离线模式随便填，服务端不开验证就行
            gameDir = Paths.root(),
            assetsDir = Paths.assets(),
            versionType = version.type
        ) + listOf("--width", config.width.toString(), "--height", config.height.toString())

        // ---------- 4. 调原生层 ----------
        Paths.logs().mkdirs()
        onLog("启动 ${version.id} (${version.mainClass})，内存 ${config.maxMemoryMb}MB")
        onLog("JVM 参数: ${jvmOptions.joinToString(" ")}")
        onLog("游戏参数: ${gameArgs.joinToString(" ")}")

        val code = NativeBridge.nativeLaunch(
            jreDir.absolutePath,
            jvmOptions.toTypedArray(),
            version.mainClass,
            (gameArgs + config.extraGameArgs).toTypedArray()
        )
        val err = NativeBridge.lastError()
        onLog("JVM 退出，code=$code ${err ?: ""}")

        return LaunchResult(code, err, File(Paths.logs(), "latest.log").takeIf { it.isFile })
    }

    /** 读取游戏日志最后 N 行给 UI 展示 */
    fun tailLog(lines: Int = 40): String {
        val f = File(Paths.logs(), "latest.log")
        if (!f.isFile) return "（还没有日志，启动一次游戏后这里会显示 logs/latest.log 的内容）"
        return try {
            f.readLines().takeLast(lines).joinToString("\n")
        } catch (e: Throwable) {
            "读取日志失败: ${e.message}"
        }
    }

    // ================================================================
    // 方案 B：直接借用已安装的 PojavLauncher 启动（最省事、一定能跑）
    // ================================================================

    /**
     * PojavLauncher 是否已安装。
     * 我们并不需要它「配合」，但如果你不想自己搞 JRE + 原生库，
     * 可以让本 App 只做下载器，把 .minecraft 目录指给 Pojav 用。
     */
    fun isPojavInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo("net.kdt.pojavlaunch", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 打开 PojavLauncher，让用户去它的「设置 -> 游戏目录」里
     * 选择 /sdcard/games/Minecraft/ —— 我们下载的版本它就能直接看到。
     *
     * 这是最稳的「能跑起来」方案：渲染层（GL4ES/VirGL）、JRE、输入映射
     * 全部由 Pojav 负责，我们只管文件和版本管理。
     */
    fun openPojavLauncher(ctx: Context): Boolean {
        if (!isPojavInstalled(ctx)) return false
        val intent = ctx.packageManager.getLaunchIntentForPackage("net.kdt.pojavlaunch")
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return true
    }

    /** 一次性把版本列表准备好（UI 用） */
    suspend fun installedVersions(): List<String> {
        return Paths.versions().listFiles()
            ?.filter { it.isDirectory && File(it, "${it.name}.json").isFile }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()
    }

    /** 供 UI 单独查询版本 json */
    suspend fun loadVersion(id: String): McVersion =
        McVersion(VersionRepository.fetchVersionJson(id))
}
