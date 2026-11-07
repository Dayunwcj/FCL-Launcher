package com.doubao.mclauncher.launch

import com.doubao.mclauncher.core.McVersion
import com.doubao.mclauncher.core.Paths
import com.doubao.mclauncher.core.Rules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * ============================================================
 * 启动参数组装：这是整个启动器里最容易踩坑的地方
 * ============================================================
 *
 * MC 的启动参数分两段：
 *   JVM 参数  —— 内存、classpath、native 路径、log4j 配置
 *   游戏参数  —— 用户名、UUID、资源目录、版本名、窗口大小
 *
 * 参数来源有两套，必须都支持：
 *   老版本（<=1.12）：version json 里只有一个 minecraftArguments 字符串
 *   新版本（>=1.13）：arguments { jvm: [...], game: [...] }，元素里还可能夹 rules
 */
object LaunchArgs {

    /**
     * 组装 JVM 参数。
     * 注意：classpath 用 -Djava.class.path 传，不用 -cp，
     * 因为原生层只接收 "-Dxxx" 形式的 option，处理起来干净。
     */
    fun buildJvmOptions(
        version: McVersion,
        jreDir: File,
        nativesDir: File,
        classpath: List<File>,
        maxMemoryMb: Int,
        minMemoryMb: Int,
        logConfigFile: File?,
        extraLibDirs: List<File> = emptyList(),
        extra: List<String> = emptyList()
    ): List<String> {
        val options = ArrayList<String>()

        // ---- 内存：-Xmx 最重要，给手机留够系统内存，别直接拉满 ----
        options += "-Xmx${maxMemoryMb}M"
        options += "-Xms${minMemoryMb}M"

        // ---- classpath：用 File.pathSeparator（Android 上是 ":"） ----
        options += "-Djava.class.path=" + classpath.joinToString(File.pathSeparator) { it.absolutePath }

        // ---- 原生库目录：LWJGL 的 .so 靠它找 ----
        // Android 上除了版本自带的 natives，还要把 Pojav 的 LWJGL 原生库目录加进来
        // （比如 /sdcard/games/Minecraft/lwjgl/），否则 org.lwjgl 找不到实现类。
        val libDirs = (listOf(nativesDir) + extraLibDirs)
            .joinToString(File.pathSeparator) { it.absolutePath }
        options += "-Djava.library.path=$libDirs"
        // LWJGL2（1.12 及以前）额外认这个属性
        options += "-Dorg.lwjgl.librarypath=$libDirs"

        // ---- 启动器身份，部分 mod 会读 ----
        options += "-Dminecraft.launcher.brand=MCLauncher"
        options += "-Dminecraft.launcher.version=1.0"
        options += "-Dminecraft.launcher.brand.version=1.0"

        // ---- 时区/编码，避免中文路径乱码 ----
        options += "-Dfile.encoding=UTF-8"
        options += "-Duser.timezone=Asia/Shanghai"

        // ---- log4j 配置（1.12+），不配会刷一堆 "Could not find log4j2.xml" ----
        if (logConfigFile != null && logConfigFile.isFile) {
            options += "-Dlog4j.configurationFile=${logConfigFile.absolutePath}"
        }

        // ---- 从版本 json 里再补一些官方要求的 jvm 参数（-XX 之类） ----
        val fromJson = expandFromJson(version, "jvm")
        for (arg in fromJson) {
            // 我们自己已经处理过的参数就跳过，避免重复/冲突
            if (arg.startsWith("-cp") || arg.contains("\${classpath}")) continue
            if (arg.startsWith("-Xmx") || arg.startsWith("-Xms")) continue
            if (arg.startsWith("-Djava.class.path")) continue
            if (arg.startsWith("-Djava.library.path")) continue
            options += arg
        }

        options += extra
        return options
    }

    /**
     * 组装游戏参数（就是传给 main(String[]) 的内容）。
     */
    fun buildGameArgs(
        version: McVersion,
        playerName: String,
        uuid: String,
        accessToken: String,
        gameDir: File,
        assetsDir: File,
        versionType: String = "release",
        extraPlaceholders: Map<String, String> = emptyMap(),
        extra: List<String> = emptyList()
    ): List<String> {
        val placeholders = buildMap {
            put("auth_player_name", playerName)
            put("version_name", version.id)
            put("game_directory", gameDir.absolutePath)
            put("assets_root", assetsDir.absolutePath)
            put("assets_index_name", version.assetsIndexName)
            put("auth_uuid", uuid)
            put("auth_access_token", accessToken)
            put("auth_session", accessToken)
            put("user_type", "legacy")          // 离线/第三方登录都是 legacy
            put("version_type", versionType)
            put("user_properties", "{}")
            put("clientid", "")
            put("auth_xuid", "")
            put("natives_directory", Paths.natives(version.id).absolutePath)
            put("library_directory", Paths.libraries().absolutePath)
            put("classpath_separator", File.pathSeparator)
            put("launcher_name", "MCLauncher")
            put("launcher_version", "1.0")
            put("classpath", "")                // 占位，游戏参数里基本用不到
            // FCL 后端需要覆盖的（classpath / natives 用真实值）
            putAll(extraPlaceholders)
        }

        // ---- 新版本：arguments.game 数组 ----
        val newStyle = expandFromJson(version, "game", placeholders)
        if (newStyle.isNotEmpty()) return newStyle + extra

        // ---- 老版本：[{ "minecraftArguments": "--username ${auth_player_name} ..." }] ----
        val legacy = version.minecraftArguments
        if (!legacy.isNullOrEmpty()) {
            return legacy.split(" ")
                .filter { it.isNotBlank() }
                .map { substitute(it, placeholders) } + extra
        }

        // ---- 兜底：历史上就没有参数定义（极老的 alpha 版本） ----
        return listOf(
            "--username", playerName,
            "--session", accessToken,
            "--gameDir", gameDir.absolutePath,
            "--assetsDir", assetsDir.absolutePath
        ) + extra
    }

    /**
     * 解析 arguments 数组。
     * 元素有两种形态：
     *   "字符串"                                    直接加
     *   {"rules":[...],"value":"字符串或字符串数组"}  满足 rules 才加
     */
    private fun expandFromJson(
        version: McVersion,
        key: String,
        placeholders: Map<String, String> = emptyMap()
    ): List<String> {
        val argsObj: JSONObject = version.arguments ?: return emptyList()
        val arr: JSONArray = argsObj.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length() * 2)

        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is String -> out += substitute(item, placeholders)

                is JSONObject -> {
                    // 不满足当前平台规则（比如 macOS 专用的 -XstartOnFirstThread）就跳过
                    if (!Rules.allows(item.optJSONArray("rules"))) continue
                    val value = item.opt("value")
                    when (value) {
                        is String -> out += substitute(value, placeholders)
                        is JSONArray -> for (j in 0 until value.length()) {
                            out += substitute(value.optString(j), placeholders)
                        }
                    }
                }
            }
        }
        return out
    }

    /** 把 ${xxx} 占位符替换成真实值；未知占位符原样留着，方便排查 */
    fun substitute(input: String, placeholders: Map<String, String>): String {
        var s = input
        for ((k, v) in placeholders) {
            s = s.replace("\${$k}", v)
        }
        return s
    }

    /**
     * 离线账号的 UUID：MC 服务端/客户端都用 "OfflinePlayer:<名字>" 的 MD5
     * 生成 version-3 UUID，这里必须一致，否则皮肤/存档里玩家身份对不上。
     */
    fun offlineUuid(name: String): String =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()).toString()

    // ============================================================
    // FCL 后端专用：参数格式转换
    // ============================================================
    //
    // FCL 的 FCLauncher 自己往 args[0] 塞 `<javaPath>/bin/java`，
    // 并且会替换三个占位符，所以我们这里**绝不能**写死真实路径：
    //   ${natives_directory} → FCL 拼好的 LD_LIBRARY_PATH（JRE lib + jniLibs + lwjgl natives + 系统库）
    //   ${gl_lib_name}       → 渲染器的 GL 库（GL4ES 时是 libgl4es_114.so）
    //   ${egl_lib_name}      → 渲染器的 EGL 库（GL4ES 时是 libEGL.so）
    //
    // 另外 FCL 约定：classpath 里要额外放它的 MioLaunchWrapper.jar，
    // 主类写成 "mio.Wrapper"，真正的 MC mainClass 作为它的第一个程序参数跟在后面。
    // 这个顺序不能反，FCLauncher 的日志逻辑就是靠 "mio.Wrapper" 这个字符串区分 JVM 参数和游戏参数的。

    /**
     * 生成 FCL 格式的 JVM 参数（**不含** java 可执行文件，FCLauncher 会补）。
     *
     * 与 [buildJvmOptions] 的区别：
     *   - 不再传真实 natives 路径，改传占位符交给 FCL 拼
     *   - 追加 LWJGL 认的 -Dorg.lwjgl.opengl.libname / egl.libname
     */
    fun buildFclJvmArgs(
        version: McVersion,
        classpath: List<File>,
        maxMemoryMb: Int,
        minMemoryMb: Int,
        logConfigFile: File? = null,
        extraJvmArgs: List<String> = emptyList()
    ): List<String> {
        val out = ArrayList<String>()

        out += "-Xmx${maxMemoryMb}M"
        out += "-Xms${minMemoryMb}M"

        // classpath：FCL 不会替换这个占位符，必须自己拼成绝对路径
        out += "-Djava.class.path=" + classpath.joinToString(File.pathSeparator) { it.absolutePath }

        // ↓ 这两个交给 FCL 替换
        out += "-Djava.library.path=\${natives_directory}"
        out += "-Dorg.lwjgl.opengl.libname=\${gl_lib_name}"
        out += "-Dorg.lwjgl.egl.libname=\${egl_lib_name}"

        // 编码/时区/启动器标识
        out += "-Dfile.encoding=UTF-8"
        out += "-Duser.timezone=Asia/Shanghai"
        out += "-Dminecraft.launcher.brand=DoubaoMC"
        out += "-Dminecraft.launcher.version=1.0"

        if (logConfigFile != null && logConfigFile.isFile) {
            out += "-Dlog4j.configurationFile=${logConfigFile.absolutePath}"
        }

        // 版本 json 里的 jvm 参数，剔除我们已经处理过的
        for (arg in expandFromJson(version, "jvm")) {
            if (arg.startsWith("-cp")) continue
            if (arg.contains("\${classpath}")) continue
            if (arg.startsWith("-Xmx") || arg.startsWith("-Xms")) continue
            if (arg.startsWith("-Djava.class.path")) continue
            if (arg.startsWith("-Djava.library.path")) continue
            // -XstartOnFirstThread 之类 macOS 专用参数 Rules 已经过滤过了
            out += arg
        }

        out += extraJvmArgs
        return out
    }

    /**
     * 拼出直接丢给 FCLauncher 的完整数组：
     *   [jvm 参数...] + "mio.Wrapper" + 真正的 mainClass + [游戏参数...]
     */
    fun buildFclCommand(
        jvmArgs: List<String>,
        mainClass: String,
        gameArgs: List<String>
    ): Array<String> {
        val all = ArrayList<String>(jvmArgs.size + gameArgs.size + 2)
        all += jvmArgs
        all += "mio.Wrapper"
        all += mainClass
        all += gameArgs
        return all.toTypedArray()
    }

    /**
     * 游戏参数里的 ${natives_directory} / ${classpath} 等占位符，
     * FCL 只在 JVM 参数段替换，游戏参数段必须我们自己填好。
     */
    fun buildFclGameArgs(
        version: McVersion,
        playerName: String,
        uuid: String,
        accessToken: String,
        gameDir: File,
        assetsDir: File,
        classpath: List<File>,
        nativesDir: File,
        versionType: String = "release",
        extra: List<String> = emptyList()
    ): List<String> = buildGameArgs(
        version = version,
        playerName = playerName,
        uuid = uuid,
        accessToken = accessToken,
        gameDir = gameDir,
        assetsDir = assetsDir,
        versionType = versionType,
        extraPlaceholders = mapOf(
            "classpath" to classpath.joinToString(File.pathSeparator) { it.absolutePath },
            "natives_directory" to nativesDir.absolutePath
        ),
        extra = extra
    )
}
