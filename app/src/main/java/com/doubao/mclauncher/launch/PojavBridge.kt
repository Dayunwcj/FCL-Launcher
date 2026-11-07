package com.doubao.mclauncher.launch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.doubao.mclauncher.core.Paths

/**
 * ============================================================
 * PojavBridge —— 方案 B 的核心：把"下载好的 .minecraft"交给 PojavLauncher 跑
 * ============================================================
 *
 * 为什么选这条路线（而不是自己在 App 里起 JVM）：
 *   1. 渲染层是 Pojav 攒了五年的东西（GL4ES / VirGL / Zink / 输入映射 / 触摸转鼠标），
 *      自己从零接 liblwjgl64.so 基本就是黑屏；
 *   2. Pojav 支持自定义游戏目录，我们只要把目录结构做成标准 .minecraft，
 *      它就能直接列出我们的版本；
 *   3. 不用碰 GPLv3，也不用打包几百 MB 的 JRE。
 *
 * 分工：
 *   本 App = 下载器 + 版本管理器 + 完整性自检
 *   Pojav   = JRE + 渲染 + 启动
 *
 * 注意：Pojav **没有** 对外开放"指定游戏目录"的 Intent API，
 * 所以"一键导出"能自动做的只有这些：
 *   ① 自检文件是否齐全（缺什么直接告诉你）
 *   ② 在 /sdcard/games/Minecraft/ 下生成一份图文说明
 *   ③ 把路径复制到剪贴板
 *   ④ 直接拉起 Pojav，让用户把目录粘进设置里
 * 剩下"在 Pojav 里选目录"这一步必须人工点，这是 Pojav 的限制，不是我们偷懒。
 */
object PojavBridge {

    private const val TAG = "PojavBridge"

    /** PojavLauncher 正式版包名（debug 版是 .debug） */
    const val PKG_RELEASE = "net.kdt.pojavlaunch"
    const val PKG_DEBUG = "net.kdt.pojavlaunch.debug"

    const val DOWNLOAD_PAGE = "https://github.com/PojavLauncherTeam/PojavLauncher/releases"

    /** 我们准备的游戏目录（要给用户在 Pojav 里选的就是这个） */
    fun gameDirPath(): String = Paths.root().absolutePath

    /** 检测是否安装（Manifest 里已经声明了 <queries>，Android 11+ 才查得到） */
    fun isInstalled(ctx: Context): Boolean = installedPackage(ctx) != null

    fun installedPackage(ctx: Context): String? {
        return try {
            ctx.packageManager.getPackageInfo(PKG_RELEASE, 0)
            PKG_RELEASE
        } catch (e: PackageManager.NameNotFoundException) {
            try {
                ctx.packageManager.getPackageInfo(PKG_DEBUG, 0)
                PKG_DEBUG
            } catch (e2: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    /**
     * 拉起 PojavLauncher。
     * @return false = 没装（调用方应该引导去下载页）
     */
    fun launch(ctx: Context): Boolean {
        val pkg = installedPackage(ctx) ?: return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "拉起 Pojav 失败", e)
            false
        }
    }

    /** 没装 Pojav 时，打开 GitHub Releases 下载页 */
    fun openDownloadPage(ctx: Context): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: Throwable) {
        false
    }
}
