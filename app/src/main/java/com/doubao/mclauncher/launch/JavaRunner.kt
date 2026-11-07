package com.doubao.mclauncher.launch

import android.util.Log
import java.io.File
import java.util.jar.JarFile

/**
 * ============================================================
 * JavaRunner —— 用我们自己的 JVM 跑一个 jar（给 Forge/Fabric 安装器用）
 * ============================================================
 *
 * 为什么绕这么大一圈？
 * ------------------------------------------------------------------
 * Forge 和 Fabric 的安装器都是 Java 程序，官方只提供 .jar。
 * 传统启动器是 fork 一个 java -jar 子进程，但 Android 上 exec 不了，
 * 所以只能复用 NativeBridge：在进程内建 JVM，直接调 installer 的 main。
 *
 * 好处：安装逻辑完全交给官方安装器，version json 怎么改、libraries 怎么补、
 *      处理器（processor）怎么跑，全都它自己搞定，我们不用手搓，出错率最低。
 */
object JavaRunner {

    private const val TAG = "JavaRunner"

    data class RunResult(val exitCode: Int, val error: String?)

    /**
     * 执行 jar 的主类。
     * @param jvmOptions 额外 JVM 参数（内存、代理等）
     */
    fun runJar(
        jreDir: File,
        jar: File,
        args: List<String>,
        jvmOptions: List<String> = emptyList()
    ): RunResult {
        if (!JreLocator.isValidJre(jreDir)) {
            return RunResult(-1, "JRE 无效: ${jreDir.absolutePath}")
        }
        if (!NativeBridge.isAvailable()) {
            return RunResult(-1, NativeBridge.loadError)
        }
        if (!jar.isFile) {
            return RunResult(-1, "jar 不存在: ${jar.absolutePath}")
        }

        val mainClass = readMainClass(jar)
            ?: return RunResult(-1, "jar 的 MANIFEST 里没有 Main-Class: ${jar.name}")

        val options = ArrayList<String>()
        options += "-Xmx1024M"
        options += "-Djava.io.tmpdir=${jar.parentFile?.absolutePath}"
        options += "-Dfile.encoding=UTF-8"
        options += "-Dfml.ignoreInvalidMinecraftCertificates=true"
        options += "-Dfml.ignorePatchDiscrepancies=true"
        options += "-Djava.class.path=${jar.absolutePath}"
        options += jvmOptions

        Log.i(TAG, "运行 $mainClass，参数: $args")
        val code = NativeBridge.nativeLaunch(
            jreDir.absolutePath,
            options.toTypedArray(),
            mainClass,
            args.toTypedArray()
        )
        return RunResult(code, NativeBridge.lastError())
    }

    /** 读 jar 清单里的 Main-Class */
    fun readMainClass(jar: File): String? = try {
        JarFile(jar).use { jf ->
            jf.manifest?.mainAttributes?.getValue("Main-Class")
        }
    } catch (e: Throwable) {
        Log.w(TAG, "读取 ${jar.name} 的 MANIFEST 失败", e)
        null
    }
}
