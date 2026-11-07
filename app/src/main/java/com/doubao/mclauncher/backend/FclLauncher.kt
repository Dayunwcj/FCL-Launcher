package com.doubao.mclauncher.backend

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun doLaunch(
        ctx: Context,
        versionId: String,
        playerName: String,
        memoryMb: Int,
        rendererKey: String
    ) {
        // ---------- 0. FCL 的路径/资源必须先就位 ----------
        FclBootstrap.init(ctx)

        val resolved = VersionResolver.resolveLocal(versionId)
        val version = resolved.version
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
        lwjglDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }?.forEach { classpath += it }

        for (path in version.classpathEntries) {
            // ① 游戏自带的 LWJGL 与 FCL 的冲突（两边都提供 org.lwjgl.*），必须让位
            if (path.startsWith("org/lwjgl/")) continue
            // ② LWJGL2 时代的输入/直播库在 Android 上没有意义
            if (path.startsWith("net/java/jinput") || path.startsWith("tv/twitch")) continue
            val f = File(Paths.libraries(), path)
            if (f.isFile) classpath += f else Log.w(TAG, "依赖缺失，跳过: $path")
        }
        classpath += Paths.clientJar(resolved.clientJarVersion)

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

        // 1.13+ 的版本用「输入栈队列」，旧版用即时投递；FCL 自己也是这么判的
        CallbackBridge.nativeSetUseInputStackQueue(version.arguments != null)

        val bridge: FCLBridge = FCLauncher.launchMinecraft(config)
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
}
