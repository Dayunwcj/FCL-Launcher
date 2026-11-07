package com.doubao.mclauncher

import com.doubao.mclauncher.backend.FclBootstrap
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
    }
}
