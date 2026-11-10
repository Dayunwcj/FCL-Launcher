package com.doubao.mclauncher.backend

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.doubao.mclauncher.core.GameInstaller
import com.doubao.mclauncher.core.Paths
import com.doubao.mclauncher.core.VersionResolver
import com.doubao.mclauncher.launch.LaunchArgs
import com.mio.data.Renderer
import com.tungsten.fcl.activity.JVMActivity
import com.tungsten.fcl.control.MenuType
import com.tungsten.fcl.util.RuntimeUtils
import com.tungsten.fclauncher.FCLauncher
import com.tungsten.fclauncher.FCLConfig
import com.tungsten.fclauncher.bridge.FCLBridge
import com.tungsten.fclauncher.utils.FCLPath
import org.lwjgl.glfw.CallbackBridge
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * ============================================================
 * FclLauncher —— 我们前端与 FCL 后端的唯一接口
 * ============================================================
 *
 * 这个文件**只存在于合并后的工程里**（由 scripts/build-fcl-apk.sh 拷进 FCL 模块），
 * 因为它 import 了 com.tungsten.* 和 org.lwjgl.glfw.*，独立工程里没有这些类。
 *
 * 调用链（全部复用 FCL 现成代码，一行 C 都没自己写）：
 *
 *   launch()
 *     ├─ 算 classpath（FCL 自带 LWJGL 打头，游戏自己的 lwjgl 剔掉）
 *     ├─ 用 LaunchArgs 拼 FCL 格式参数（含占位符 ${natives_directory} 等）
 *     ├─ FCLConfig(...)                     ← FCL 的配置对象
 *     ├─ FCLauncher.launchMinecraft(config) ← FCL 的入口，返回 FCLBridge
 *     ├─ JVMActivity.setFCLBridge(bridge, MenuType.GAME)
 *     └─ startActivity(JVMActivity)         ← FCL 现成的游戏画面 Activity（Surface + 输入桥 + 菜单）
 *
 * FCL 在这条链路里替我们做的事：
 *   · dlopen jre/bin/libjli.so、libjvm.so（setUpJavaRuntime）
 *   · dlopen 渲染库并设 POJAV_RENDERER / LIBGL_* 等环境变量（addRendererEnv）
 *   · 拼 LD_LIBRARY_PATH 并替换 ${natives_directory}/${gl_lib_name}/${egl_lib_name}（rebaseArgs）
 *   · 调 VMLauncher.launchJVM → JNI_CreateJavaVM（jre_launcher.c）
 *   · 把 Surface 交给 egl_bridge.c，把触屏/按键交给 input_bridge_v3.c
 */
object FclLauncher {

    private const val TAG = "FclLauncher"

    /** 渲染器切换口：默认 GL4ES（最稳），另外两个预留 */
    private val RENDERER_GL4ES = Renderer(
        "Holy-GL4ES", "GL4ES",
        "libgl4es_114.so", "libEGL.so",
        "", null, null,
        Renderer.ID_GL4ES, "", ""
    )

    private val RENDERER_VGPU = Renderer(
        "VGPU", "VGPU",
        "libvgpu.so", "libEGL.so",
        "", null, null,
        Renderer.ID_VGPU, "", ""
    )

    private val RENDERER_ZINK = Renderer(
        "Zink", "Zink",
        "libglxshim.so", "libEGL_mesa.so",
        "", null, null,
        Renderer.ID_ZINK, "", ""
    )

    fun rendererOf(key: String): Renderer = when (key.lowercase()) {
        "vgpu" -> RENDERER_VGPU
        "zink" -> RENDERER_ZINK
        else -> RENDERER_GL4ES
    }

    /**
     * 启动游戏。**不要在主线程等它**——它只是把线程起起来、把 Activity 拉起来就返回。
     *
     * @param rendererKey "gl4es"(默认) / "vgpu" / "zink"
     * @return null = 已成功拉起；非 null = 失败原因（直接显示给用户）
     */
    @JvmStatic
    fun launch(
        ctx: Context,
        versionId: String,
        playerName: String,
        memoryMb: Int,
        rendererKey: String = "gl4es"
    ): String? {
        return try {
            doLaunch(ctx, versionId, playerName, memoryMb, rendererKey)
            null
        } catch (e: Throwable) {
            Log.e(TAG, "launch failed", e)
            describeThrowable(e)
        }
    }

    /**
     * 把异常摊开写成人能看懂的样子：类名 + 消息 + 前几帧 + cause 链。
     * ExceptionInInitializerError 的 message 常常是 null，只有栈帧能说明是哪个类挂了。
     */
    private fun describeThrowable(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 4) {
            if (depth > 0) sb.append("由 ").append(t.javaClass.name).append(" 引起: ")
            else sb.append(t.javaClass.name).append(": ")
            sb.append(t.message ?: "(无消息，静态初始化失败)").append('\n')
            t.stackTrace.take(5).forEach { sb.append("    at ").append(it.toString()).append('\n') }
            t = t.cause
            depth++
        }
        return sb.toString().trim()
    }

    private fun doLaunch(
        ctx: Context,
        versionId: String,
        playerName: String,
        memoryMb: Int,
        rendererKey: String
    ) {
        // ---------- 0. FCL 的路径/资源必须先就位 ----------
        // 这里必须**同步**把 assets/app_runtime 里的 LWJGL、JNA、cacio 拷到 app_runtime，
        // 因为 classpath 里要放 FCL 的 LWJGL jar。之前是后台线程拷的，
        // 抢在它前面启动就会 classpath 里一个 lwjgl 都没有，进游戏直接 NoClassDefFoundError。
        FclBootstrap.ensureSync(ctx) { Log.i(TAG, it) }

        val resolved = VersionResolver.resolveLocal(versionId)
        val version = resolved.version

        // ---------- 0.5 依赖自愈：classpath 缺文件就先补 ----------
        // 老版本安装包可能只下了 client.jar，日志里就是只有一行 -Djava.class.path=<client.jar>，
        // 然后 Error: Unable to initialize main class ... NoClassDefFoundError: joptsimple/OptionSpec。
        // 这里读本地版本 json 把缺的 libraries 补上，省得用户重装版本。
        val missing = version.classpathEntries.count { !File(Paths.libraries(), it).isFile }
        if (missing > 0 || !Paths.clientJar(resolved.clientJarVersion).isFile) {
            Log.i(TAG, "缺失 $missing 个依赖库，开始补齐")
            val repaired = runBlocking { GameInstaller().repairMissing(versionId) { Log.i(TAG, it) } }
            Log.i(TAG, "补齐完成: $repaired 个文件")
        }
        val gameDir = Paths.root().apply { mkdirs() }

        // ---------- 1. JRE：必须是 FCL 认的目录 app_runtime/java/jre<N> ----------
        val major = version.javaMajorVersion
        val jreDir = File(FCLPath.RUNTIME_DIR, "java/jre$major")
        if (!jreDir.isDirectory) {
            throw IllegalStateException(
                "缺少 Java $major 运行时。FCL 只认这个目录：${jreDir.absolutePath}\n" +
                    "把 Pojav 的 jre$major 解压到那里（或用 FCL 自带的 JRE 下载）。"
            )
        }

        // ---------- 2. LWJGL：用 FCL 自带的那套（版本 json 里的要剔掉） ----------
        val lwjglVersion = pickLwjglVersion()
        val lwjglDir = File(FCLPath.LWJGL_DIR, lwjglVersion)
        if (!lwjglDir.isDirectory) {
            throw IllegalStateException("LWJGL $lwjglVersion 还没装好：${lwjglDir.absolutePath}（资源拷贝失败？）")
        }

        val classpath = ArrayList<File>()
        // FCL 的 lwjgl jar 排最前面（含 lwjgl.jar / lwjgl-glfw.jar / lwjgl-openal.jar ...）
        // 递归找：FCL 的资源有时会被拷成多一层目录，listFiles 会漏掉
        lwjglDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jar") }
            .sortedBy { it.name }
            .forEach { classpath += it }

        for (path in version.classpathEntries) {
            // ① 游戏自带的 LWJGL 与 FCL 的冲突（两边都提供 org.lwjgl.*），必须让位
            if (path.startsWith("org/lwjgl/")) continue
            // ② LWJGL2 时代的输入/直播库在 Android 上没有意义
            if (path.startsWith("net/java/jinput") || path.startsWith("tv/twitch")) continue
            val f = File(Paths.libraries(), path)
            if (f.isFile) classpath += f else Log.w(TAG, "依赖缺失，跳过: $path")
        }
        classpath += Paths.clientJar(resolved.clientJarVersion)

        // 硬校验：classpath 只有 client.jar 的话进游戏必报 NoClassDefFoundError，
        // 与其让 JVM 输出一行天书，不如在这里把原因说清楚
        if (classpath.size <= 1) {
            throw IllegalStateException(
                "依赖库没下全：classpath 只有 ${classpath.size} 项。\n" +
                    "已尝试自动补齐但仍缺失，请在版本卡片上重新安装该版本。\n" +
                    "libraries 目录：${Paths.libraries().absolutePath}"
            )
        }

        // FCL 的 wrapper jar（assets 里带的，负责 add-opens / authlib 注入等杂活）
        val wrapper = File(FCLPath.MIO_LAUNCH_WRAPPER)
        if (wrapper.isFile) {
            classpath += wrapper
        } else {
            Log.w(TAG, "MioLaunchWrapper 缺失，直接以 ${version.mainClass} 为主类启动")
        }

        // ---------- 3. 参数：转成 FCL 认的格式 ----------
        val nativesDir = Paths.natives(versionId).apply { mkdirs() }
        val logConfig = version.loggingFileId?.let { File(Paths.assets(), "log_configs/$it") }

        val jvmArgs = LaunchArgs.buildFclJvmArgs(
            version = version,
            classpath = classpath,
            maxMemoryMb = memoryMb,
            minMemoryMb = (memoryMb / 4).coerceAtLeast(512),
            logConfigFile = logConfig
        )

        val gameArgs = LaunchArgs.buildFclGameArgs(
            version = version,
            playerName = playerName,
            uuid = LaunchArgs.offlineUuid(playerName),
            accessToken = "0",
            gameDir = gameDir,
            assetsDir = Paths.assets(),
            classpath = classpath,
            nativesDir = nativesDir,
            versionType = version.type
        ) + listOf("--width", "1280", "--height", "720")

        // "mio.Wrapper" 后面跟真正的 mainClass —— 这是 FCL 的约定，不能反
        val fullArgs = if (wrapper.isFile) {
            LaunchArgs.buildFclCommand(jvmArgs, version.mainClass, gameArgs)
        } else {
            // 没 wrapper 时退化：直接 mainClass + 游戏参数
            (jvmArgs + listOf(version.mainClass) + gameArgs).toTypedArray()
        }

        // ---------- 4. FCL 配置 ----------
        val config = FCLConfig(
            ctx,                                    // context
            FCLPath.LOG_DIR,                        // logDir：日志落 /sdcard/FCL/log/latest_game.log
            jreDir.absolutePath,                    // javaPath：JRE 根目录（里面有 bin/java、lib/、release）
            gameDir.absolutePath,                   // workingDir：游戏工作目录
            rendererOf(rendererKey),                // renderer：GL4ES / VGPU / ZINK
            fullArgs                                // args：**不含** java 可执行文件，FCLauncher 自己补
        )
        config.lwjglVersion = lwjglVersion

        // 模组加载器只要告诉 FCL 有没有，它会给原生层设 INST_XXX 环境变量
        val id = versionId.lowercase()
        config.installedModLoaders = FCLConfig.InstalledModLoaders(
            installForge = id.contains("forge") && !id.contains("neoforge"),
            installCleanroom = id.contains("cleanroom"),
            installNeoForge = id.contains("neoforge"),
            installOptiFine = id.contains("optifine"),
            installLiteLoader = id.contains("liteloader"),
            installFabric = id.contains("fabric"),
            installQuilt = id.contains("quilt")
        )

        // ---------- 5. 拉起 ----------
        Log.i(TAG, "启动 $versionId，JRE=$jreDir，渲染器=$rendererKey，classpath ${classpath.size} 项")
        if (classpath.size <= 1) {
            Log.w(TAG, "classpath 只有 ${classpath.size} 项，几乎肯定起不来（依赖库没下全）")
        }

        // 1.13+ 的版本用「输入栈队列」，旧版用即时投递；FCL 自己也是这么判的。
        // 注意：CallbackBridge 的静态初始化里有 Choreographer.getInstance()，必须在有 Looper 的线程上先完成，
        // 否则这里是 ExceptionInInitializerError。App.onCreate 已经在主线程预热过这个类，这里再加一层保险。
        try {
            CallbackBridge.nativeSetUseInputStackQueue(version.arguments != null)
        } catch (e: Throwable) {
            Log.w(TAG, "设置输入队列标记失败（不影响启动）: ${e.javaClass.simpleName}")
        }

        val bridge: FCLBridge = FCLauncher.launchMinecraft(config)
        Log.i(TAG, "FCLBridge 已创建，转到主线程拉起 JVMActivity")

        // FCL 自己的流程里这一步都在 Activity（主线程）里做：
        // setFCLBridge 会给 CallbackBridge 塞 bridge 引用，startActivity 也需要主线程的上下文，
        // 所以这里统一 post 到主线程，别在工作线程硬来。
        Handler(Looper.getMainLooper()).post {
            try {
                bridge.setScaleFactor(1.0)
                bridge.setController("Default")
                bridge.setGameDir(gameDir.absolutePath)
                bridge.setJava("Java $major")

                JVMActivity.setFCLBridge(bridge, MenuType.GAME)

                val intent = Intent(ctx, JVMActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.putExtra("controller", "Default")
                intent.putExtra("TERRACOTTA_PLAYER", playerName)
                ctx.startActivity(intent)
            } catch (t: Throwable) {
                Log.e(TAG, "拉起 JVMActivity 失败", t)
            }
        }
    }

    /**
     * 选 LWJGL 版本：FCL 资源里同时装了 3.3.3 和 3.4.1。
     * 默认 3.3.3（覆盖面最广），只有 3.3.3 缺失时才用 3.4.1。
     */
    private fun pickLwjglVersion(): String {
        val v333 = File(FCLPath.LWJGL_DIR, "3.3.3")
        if (v333.isDirectory) return "3.3.3"
        val v341 = File(FCLPath.LWJGL_DIR, "3.4.1")
        if (v341.isDirectory) return "3.4.1"
        return "3.3.3"
    }
}

/**
 * ============================================================
 * FclBootstrap —— 第一次启动时把 FCL 的运行资源铺好
 * ============================================================
 *
 * FCL 正常流程是 SplashActivity 干这件事（RuntimeUtils.install 从 assets 拷到 app_runtime），
 * 我们自己的 MainActivity 当入口，所以这里补上那一步。已装过就秒过（isLatest 只比对版本号）。
 */
object FclBootstrap {

    @Volatile
    private var started = false

    @Volatile
    private var resourcesReady = false

    /**
     * 同步把 FCL 的运行资源铺好（LWJGL / JNA / cacio）。
     * 启动前必须调这个，否则 classpath 里没有 LWJGL。
     */
    @JvmStatic
    @Synchronized
    fun ensureSync(ctx: Context, onLog: (String) -> Unit = {}) {
        if (resourcesReady) return
        FCLPath.loadPaths(ctx)
        Paths.ensure(Paths.root(), Paths.versions(), Paths.libraries(), Paths.assets(), Paths.logs())
        // 注意：不要用 FCL 的 RuntimeUtils.install。
        // 它的 isLatest() 走 Class.getResourceAsStream("/assets/...")，在 APK 里读不到 assets，
        // 于是永远返回 true（"已是最新"），结果 LWJGL 目录一直空着，classpath 里一个 lwjgl jar 都没有。
        // 这里改成直接用 Context.getAssets() 递归拷贝。
        try {
            copyAssetTree(ctx, "app_runtime/lwjgl/3.3.3", File(FCLPath.LWJGL_DIR, "3.3.3"), onLog)
            copyAssetTree(ctx, "app_runtime/lwjgl/3.4.1", File(FCLPath.LWJGL_DIR, "3.4.1"), onLog)
            copyAssetTree(ctx, "app_runtime/caciocavallo", File(FCLPath.CACIOCAVALLO_8_DIR), onLog)
            copyAssetTree(ctx, "app_runtime/caciocavallo17", File(FCLPath.CACIOCAVALLO_17_DIR), onLog)
            copyJna(ctx, onLog)
            // FCL 的 wrapper jar（mio.Wrapper 主类在里面），缺了就只能直接跑 mainClass
            copyAssetFile(ctx, "game/MioLaunchWrapper.jar", File(FCLPath.MIO_LAUNCH_WRAPPER), onLog)
        } catch (e: Throwable) {
            Log.w("FclBootstrap", "铺 FCL 资源失败: ${e.javaClass.simpleName} ${e.message}")
        }
        // 兜底：version 文件可能在 jar 写完前就落盘了（安装被中断过），
        // 这时 isLatest 会误判为已装，结果是目录空着、classpath 里一个 lwjgl 都没有。
        val lwjgl333 = File(FCLPath.LWJGL_DIR, "3.3.3")
        val hasJar = lwjgl333.walkTopDown().any { it.isFile && it.name.endsWith(".jar") }
        if (!hasJar) {
            try {
                onLog("LWJGL 3.3.3 资源不完整，重新拷贝")
                copyAssetTree(ctx, "app_runtime/lwjgl/3.3.3", lwjgl333, onLog)
            } catch (e: Throwable) {
                Log.w("FclBootstrap", "重拷 LWJGL 失败: ${e.message}")
            }
        }
        resourcesReady = true
        onLog("FCL 运行资源已就绪")
    }

    @JvmStatic
    fun init(ctx: Context) {
        if (started) return
        started = true
        // 建目录 + 初始化 FCLPath 的一堆静态字段
        FCLPath.loadPaths(ctx)
        Paths.ensure(Paths.root(), Paths.versions(), Paths.libraries(), Paths.assets(), Paths.logs())

        Thread({
            val pairs = listOf(
                File(FCLPath.LWJGL_DIR, "3.3.3").absolutePath to "/assets/app_runtime/lwjgl/3.3.3",
                File(FCLPath.LWJGL_DIR, "3.4.1").absolutePath to "/assets/app_runtime/lwjgl/3.4.1",
                FCLPath.JNA_PATH to "/assets/app_runtime/jna",
                FCLPath.CACIOCAVALLO_8_DIR to "/assets/app_runtime/caciocavallo",
                FCLPath.CACIOCAVALLO_17_DIR to "/assets/app_runtime/caciocavallo17"
            )
            for ((target, asset) in pairs) {
                try {
                    if (!RuntimeUtils.isLatest(target, asset)) {
                        Log.i("FclBootstrap", "安装 $asset → $target")
                        RuntimeUtils.install(ctx, target, asset)
                    }
                } catch (e: Throwable) {
                    Log.w("FclBootstrap", "安装 $asset 失败: ${e.message}")
                }
            }
        }, "fcl-bootstrap").start()
    }

    /** 递归把 assets 下的目录整棵拷到磁盘（已存在且大小一致的跳过） */
    private fun copyAssetTree(ctx: Context, assetDir: String, destDir: File, onLog: (String) -> Unit) {
        destDir.mkdirs()
        val children = ctx.assets.list(assetDir).orEmpty()
        if (children.isEmpty()) {
            // 叶子：assets.list 对文件返回空数组，这里当作"单个文件"处理
            if (assetDir.substringAfterLast('.', "").isNotEmpty()) {
                copyAssetFile(ctx, assetDir, destDir, onLog)
            }
            return
        }
        for (name in children) {
            val childAsset = "$assetDir/$name"
            val childDest = File(destDir, name)
            val grandchildren = ctx.assets.list(childAsset).orEmpty()
            if (grandchildren.isNotEmpty()) {
                copyAssetTree(ctx, childAsset, childDest, onLog)
            } else {
                copyAssetFile(ctx, childAsset, childDest, onLog)
            }
        }
    }

    private fun copyAssetFile(ctx: Context, assetPath: String, dest: File, onLog: (String) -> Unit) {
        dest.parentFile?.mkdirs()
        // APK 里的 jar 是被压缩过的，openFd 会失败；这里只要文件在、非空就当作已拷过
        if (dest.isFile && dest.length() > 0) return
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        }
        Log.i("FclBootstrap", "拷贝资产 $assetPath → ${dest.absolutePath}")
    }

    /** JNA：assets 按 <版本>/natives/<abi>/ 存，运行时要求 <版本>/libjnidispatch.so */
    private fun copyJna(ctx: Context, onLog: (String) -> Unit) {
        val rootAsset = "app_runtime/jna"
        val rootDest = File(FCLPath.JNA_PATH).apply { mkdirs() }
        val versions = ctx.assets.list(rootAsset).orEmpty()
        for (v in versions) {
            val vDir = File(rootDest, v).apply { mkdirs() }
            copyAssetTree(ctx, "$rootAsset/$v", vDir, onLog)
            val abi = "arm64-v8a"
            val soAsset = "$rootAsset/$v/natives/$abi/libjnidispatch.so"
            val soDest = File(vDir, "libjnidispatch.so")
            runCatching { copyAssetFile(ctx, soAsset, soDest, onLog) }
                .onFailure { Log.w("FclBootstrap", "拷贝 jna so 失败: ${it.message}") }
        }
    }
}
