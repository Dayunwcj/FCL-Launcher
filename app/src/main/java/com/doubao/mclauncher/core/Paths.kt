package com.doubao.mclauncher.core

import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * ============================================================
 * 路径管理：所有目录都在这里集中定义，别在别处硬编码字符串
 * ============================================================
 *
 * 需求要求存到 /sdcard/games/Minecraft/，最终磁盘布局和专业启动器一致：
 *
 * /sdcard/games/Minecraft/
 * ├── versions/
 * │   └── 1.20.1/
 * │       ├── 1.20.1.json     版本描述文件（从 BMCLAPI 下的）
 * │       └── 1.20.1.jar      客户端主程序
 * ├── libraries/              所有依赖 jar（按 maven 目录结构摆放）
 * ├── assets/
 * │   ├── indexes/1.20.1.json 资源索引
 * │   └── objects/ab/cdef...  资源实体（按 hash 前两位分目录）
 * ├── natives/                从 jar 里解出来的 .so（老版本需要）
 * ├── runtimes/               我们放进去的 JRE
 * │   └── 17/
 * ├── logs/                   游戏日志
 * └── options.txt / 存档...
 */
object Paths {

    /** 外部存储根：/storage/emulated/0 */
    private val externalRoot: File
        get() = Environment.getExternalStorageDirectory()

    /** 用户要求的根目录：/sdcard/games/Minecraft */
    fun root(): File = File(externalRoot, "games/Minecraft")

    fun versions(): File = File(root(), "versions")
    fun versionDir(id: String): File = File(versions(), id)

    /** 版本描述 json 的落盘位置（官方命名习惯，文件名 = 版本号） */
    fun versionJson(id: String): File = File(versionDir(id), "$id.json")

    /** 客户端主程序 jar */
    fun clientJar(id: String): File = File(versionDir(id), "$id.jar")

    fun libraries(): File = File(root(), "libraries")
    fun assets(): File = File(root(), "assets")
    fun assetIndexes(): File = File(assets(), "indexes")
    fun assetObjects(): File = File(assets(), "objects")

    /** 老版本（1.13 以前）需要把 natives 解压出来 */
    fun natives(id: String): File = File(root(), "natives/$id")

    /** 我们安装的 JRE（每个大版本一个目录，例如 8 / 17 / 21） */
    fun runtimes(): File = File(root(), "runtimes")
    fun runtime(majorVersion: Int): File = File(runtimes(), majorVersion.toString())

    fun logs(): File = File(root(), "logs")

    /** 确保目录存在，返回自身方便链式调用 */
    fun ensure(vararg dirs: File): Unit {
        dirs.forEach { if (!it.exists()) it.mkdirs() }
    }

    /** 剩余空间（MB），启动前检查用，下载 500MB 资源没空间会失败 */
    fun freeSpaceMb(): Long {
        return try {
            val stat = StatFs(root().absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong / 1024 / 1024
        } catch (e: Throwable) {
            -1L
        }
    }

    /**
     * 是否拿到了写 /sdcard 的权限。
     * - API 24~28：WRITE_EXTERNAL_STORAGE 运行时权限
     * - API 30+：MANAGE_EXTERNAL_STORAGE（所有文件访问）
     * - API 29：可以用 requestLegacyExternalStorage 兜底，这里统一按“能建目录就算有”
     */
    fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // 有些 ROM 上用户给了权限但 isExternalStorageManager 仍返回 false，
                // 所以再实测一次能不能写
                return canWriteProbe()
            }
        }
        return canWriteProbe()
    }

    private fun canWriteProbe(): Boolean {
        return try {
            val probe = File(root(), ".write_test")
            probe.parentFile?.mkdirs()
            probe.writeText("ok")
            probe.delete()
            true
        } catch (e: Throwable) {
            false
        }
    }
}
