package com.doubao.mclauncher

import com.doubao.mclauncher.backend.FclBootstrap
import com.doubao.mclauncher.launch.BundledJre
import com.doubao.mclauncher.launch.FclCompat
import com.doubao.mclauncher.launch.GameLauncher
import com.doubao.mclauncher.launch.JreInstaller
import com.doubao.mclauncher.core.Paths

/**
 * 自定义 Application：
 * 1. 把 Context 注入 GameLauncher（启动时需要读包信息、filesDir）
 * 2. 初始化目录结构
 */
class App : com.tungsten.fcl.FCLApp() {

    override fun onCreate() {
        super.onCreate()
        GameLauncher.appContext = this
        // 把 FCL 的路径表 / 运行时资源准备好（app_runtime 的 lwjgl、jna）
        FclBootstrap.init(this)
        Paths.ensure(Paths.root(), Paths.versions(), Paths.libraries(), Paths.assets(), Paths.logs())
        JreInstaller.ensureDir(this)

        // FCL 的 CallbackBridge 静态初始化里有 Choreographer.getInstance()（必须有 Looper）。
        // 启动是在后台线程做的，所以必须现在（主线程）先把这个类初始化好，否则启动时必报
        // ExceptionInInitializerError: null。
        try {
            Class.forName("org.lwjgl.glfw.CallbackBridge", true, classLoader)
            android.util.Log.i("App", "CallbackBridge 已在主线程完成初始化")
        } catch (t: Throwable) {
            android.util.Log.w("App", "预热 CallbackBridge 失败: ${t.javaClass.simpleName} ${t.message}")
        }

        // 首次启动就在后台把内置的 Java 17 解好，
        // 这样用户点「启动」时不用等解压，也不用联网下载（全程离线）
        Thread({
            try {
                if (!FclCompat.isFclJreInstalled(this, 17) && BundledJre.isBundled(this, 17)) {
                    BundledJre.install(this, 17) { android.util.Log.i("App", it) }
                }
            } catch (e: Throwable) {
                android.util.Log.w("App", "预解压内置 JRE 失败: ${e.message}")
            }
        }, "jre-prewarm").start()
    }
}
