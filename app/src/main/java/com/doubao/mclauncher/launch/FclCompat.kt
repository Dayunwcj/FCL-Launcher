package com.doubao.mclauncher.launch

import android.content.Context
import java.io.File

/**
 * ============================================================
 * FclCompat —— 和 FCL 后端的目录约定对齐
 * ============================================================
 *
 * FCL 只认这一个 JRE 位置（见 com/tungsten/fclauncher/utils/FCLPath.java）：
 *
 *     context.getDir("runtime", 0) + "/java/jre8|jre17|jre21|jre25"
 *     → /data/data/<包名>/app_runtime/java/jre17
 *
 * 里面必须是标准 JRE 结构：release / bin/java / lib/aarch64/server/libjvm.so。
 * Pojav 的 jre17-arm64 tar.xz 解压出来正是这个结构，整目录搬过去就行。
 */
object FclCompat {

    /** FCL 认的 JRE 目录 */
    fun fclJreDir(context: Context, major: Int): File =
        File(File(context.getDir("runtime", 0), "java"), "jre$major")

    /** FCL 认的 JRE 根（java 那一层） */
    fun fclJavaRoot(context: Context): File =
        File(context.getDir("runtime", 0), "java")

    fun isFclJreInstalled(context: Context, major: Int): Boolean {
        val dir = fclJreDir(context, major)
        return dir.isDirectory && File(dir, "release").isFile
    }
}
