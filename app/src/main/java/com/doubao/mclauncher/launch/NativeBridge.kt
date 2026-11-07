package com.doubao.mclauncher.launch

/**
 * ============================================================
 * NativeBridge —— 【已废弃 / DEPRECATED】
 * ============================================================
 *
 * 这个类原来负责调 app/src/main/cpp/jvm_bootstrap.c 里自写的 JNI 启动层
 * （dlopen libjvm.so → JNI_CreateJavaVM → 反射调 main）。
 *
 * 为什么废弃：
 *   自写这一层只能把 JVM 拉起来，**给不了游戏渲染能力**。
 *   MC 需要的 liblwjgl64.so / libgl4es / EGL 上下文、SM 输入桥、AWT 兼容层，
 *   全都长在 PojavLauncher / FCL 的 jre_launcher.c + egl_bridge.c + input_bridge 里，
 *   自己从零补等于重写半个 Pojav，真机百分之百黑屏。
 *
 * 现在的启动后端：com.doubao.mclauncher.backend.FclLauncher
 *   （直接调 FCL 的 FCLauncher.launchMinecraft(FCLConfig)，复用它的
 *     libpojavexec.so + libfcl.so + GL4ES + 预编译 JRE）
 *
 * 保留这个空壳只是为了向后兼容旧调用点，任何调用都会立刻失败退。
 * ============================================================
 */
@Deprecated("启动后端已换成 FCL（com.doubao.mclauncher.backend.FclLauncher），此类不再提供原生能力")
object NativeBridge {

    /** 永远非 null：说明原生层已经不存在了 */
    val loadError: String? = "自写 JNI 启动层已废弃，请使用 FCL 后端（FclLauncher）"

    fun isAvailable(): Boolean = false

    fun lastError(): String? = loadError

    fun nativeLaunch(
        jreDir: String,
        jvmOptions: Array<String>,
        mainClass: String,
        appArgs: Array<String>
    ): Int = -1
}
