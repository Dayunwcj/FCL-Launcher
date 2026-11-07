package com.doubao.mclauncher.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================
 * MC 版本 json 的解析层
 * ============================================================
 *
 * Mojang 的版本 json 结构几十个版本下来变化很大：
 *   1.6.x 用 minecraftArguments 字符串
 *   1.13+ 改成 arguments { game: [...], jvm: [...] }
 *   1.19+ 的 arguments 里还会混入 {"rules":..., "value":[...]} 这种对象
 * 所以这里不搞强类型反序列化，全部保留 JSONObject，按需取值 + 规则判断。
 */

/** 版本清单里的一项（版本列表页展示用） */
data class RemoteVersion(
    val id: String,
    val type: String,          // release / snapshot / old_beta / old_alpha
    val url: String,           // 版本 json 的官方地址（下载时会改写镜像）
    val releaseTime: String,
    val sha1: String? = null
)

/** 某条 library 的下载信息 */
data class LibDownload(
    val path: String,          // maven 相对路径：org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar
    val url: String,
    val sha1: String? = null,
    val size: Long = -1L
)

/** 解析后的一条依赖 */
data class Library(
    val name: String,                  // group:artifact:version[:classifier]
    val artifact: LibDownload?,        // 主 jar，可能为 null（只有 natives 的条目）
    val nativesClassifiers: List<LibDownload>, // 平台原生库（Android 上要特殊处理）
    val extract: Boolean,              // 是否要解压（老版本 natives）
    val isNative: Boolean
)

/** 解析后的版本对象 */
class McVersion(val raw: JSONObject) {

    val id: String = raw.optString("id")
    val type: String = raw.optString("type", "release")
    val mainClass: String = raw.optString("mainClass")

    /** 1.6 时代的老字段 */
    val minecraftArguments: String? = raw.optString("minecraftArguments").takeIf { it.isNotEmpty() }

    /** 1.13+ 的新字段，原样留着给 LaunchArgs 解析 */
    val arguments: JSONObject? = raw.optJSONObject("arguments")

    val assetsIndexName: String = raw.optString("assetIndex").let {
        raw.optJSONObject("assetIndex")?.optString("id") ?: raw.optString("assets", "legacy")
    }

    val assetIndexUrl: String? = raw.optJSONObject("assetIndex")?.optString("url")
    val assetIndexSha1: String? = raw.optJSONObject("assetIndex")?.optString("sha1")

    /** 客户端主 jar 的下载信息 */
    val clientJarUrl: String? = raw.optJSONObject("downloads")?.optJSONObject("client")?.optString("url")
    val clientJarSha1: String? = raw.optJSONObject("downloads")?.optJSONObject("client")?.optString("sha1")
    val clientJarSize: Long = raw.optJSONObject("downloads")?.optJSONObject("client")?.optLong("size", -1L) ?: -1L

    /** 这个版本要求的 Java 大版本（1.17+ 是 16/17，1.20.5+ 是 21） */
    val javaMajorVersion: Int = raw.optJSONObject("javaVersion")?.optInt("majorVersion", 8) ?: 8

    /** 日志配置（log4j2 的 xml），1.12+ 有，不配的话启动会刷一屏警告 */
    val loggingFileId: String? =
        raw.optJSONObject("logging")?.optJSONObject("client")?.optJSONObject("file")?.optString("id")
    val loggingFileUrl: String? =
        raw.optJSONObject("logging")?.optJSONObject("client")?.optJSONObject("file")?.optString("url")
    val loggingArgument: String? =
        raw.optJSONObject("logging")?.optJSONObject("client")?.optString("argument")

    /** 解析好的依赖列表（已按当前平台规则过滤） */
    val libraries: List<Library> by lazy { parseLibraries(raw) }

    /** 需要进 classpath 的 jar 路径（相对 libraries 根，用 / 分隔） */
    val classpathEntries: List<String>
        get() = libraries.filter { !it.isNative }.mapNotNull { it.artifact?.path }

    companion object {

        /**
         * 把 libraries 数组解析成结构化对象，并做两件事：
         * 1. 按 rules 过滤掉别的平台的库（Android 我们按 linux 算）；
         * 2. 挑出当前平台的原生库（natives 字段 / downloads.classifiers）。
         */
        fun parseLibraries(raw: JSONObject): List<Library> {
            val arr = raw.optJSONArray("libraries") ?: return emptyList()
            val out = ArrayList<Library>(arr.length())

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue

                // ---- 规则过滤：不满足当前平台就整条跳过 ----
                if (!Rules.allows(obj.optJSONArray("rules"), Rules.OS_LINUX, Rules.ARCH_ARM64)) {
                    continue
                }

                val name = obj.optString("name")
                val downloads = obj.optJSONObject("downloads")
                val artifactObj = downloads?.optJSONObject("artifact")
                val mavenUrl = obj.optString("url").ifEmpty { "https://libraries.minecraft.net" }

                // 主 jar：有 downloads.artifact 就用它的 path/url，
                // 老版本只有 name + url 的，自己拼 maven 路径。
                val artifact = when {
                    artifactObj != null -> LibDownload(
                        path = artifactObj.optString("path"),
                        url = artifactObj.optString("url"),
                        sha1 = artifactObj.optString("sha1").ifEmpty { null },
                        size = artifactObj.optLong("size", -1L)
                    )

                    // 只有 name，没有 artifact 也没 natives（lwjgl 的 meta 包常见）
                    obj.has("natives").not() && name.isNotEmpty() -> LibDownload(
                        path = mavenPathOf(name),
                        url = "$mavenUrl/${mavenPathOf(name)}",
                        sha1 = null, size = -1L
                    )

                    else -> null
                }

                // ---- 原生库识别 ----
                // 老写法："natives": {"linux": "natives-linux"}
                // 新写法（lwjgl3）：downloads.classifiers["natives-linux"]
                val natives = ArrayList<LibDownload>()
                val nativesMap = obj.optJSONObject("natives")
                if (nativesMap != null) {
                    val classifier = nativesMap.optString("linux").ifEmpty { null }
                    if (classifier != null) {
                        val resolved = classifier.replace("\${arch}", if (Rules.ARCH_ARM64) "arm64" else "32")
                        val path = mavenPathOf("$name:$resolved")
                        val nObj = downloads?.optJSONObject("classifiers")?.optJSONObject(resolved)
                        natives += if (nObj != null) {
                            LibDownload(
                                path = nObj.optString("path"),
                                url = nObj.optString("url"),
                                sha1 = nObj.optString("sha1").ifEmpty { null },
                                size = nObj.optLong("size", -1L)
                            )
                        } else {
                            LibDownload(path, "$mavenUrl/$path", null, -1L)
                        }
                    }
                }
                val classifiers = downloads?.optJSONObject("classifiers")
                if (classifiers != null) {
                    val keys = classifiers.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.startsWith("natives-linux")) {
                            val o = classifiers.optJSONObject(key) ?: continue
                            natives += LibDownload(
                                path = o.optString("path"),
                                url = o.optString("url"),
                                sha1 = o.optString("sha1").ifEmpty { null },
                                size = o.optLong("size", -1L)
                            )
                        }
                    }
                }

                val extract = obj.optJSONObject("extract")?.optBoolean("exclude", false) ?: false

                // ---- 现代版本（1.13+）的原生库是「独立条目」，靠 classifier 表达：
                //      org.lwjgl:lwjgl:3.3.1:natives-linux
                //      这种条目只含 .so，不能进 classpath，Android 上也要换成 Pojav 的版本
                val classifier = name.split(":").getOrNull(3)
                val nativeByName = classifier != null && classifier.startsWith("natives-")

                out += Library(
                    name = name,
                    artifact = if (nativeByName) null else artifact,
                    nativesClassifiers = if (nativeByName) {
                        artifact?.let { listOf(it) } ?: natives
                    } else natives,
                    extract = extract,
                    isNative = nativeByName || (artifact == null && natives.isNotEmpty())
                )
            }
            return out
        }

        /**
         * "org.lwjgl:lwjgl:3.3.1"           -> org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar
         * "org.lwjgl:lwjgl:3.3.1:natives-linux" -> org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar
         */
        fun mavenPathOf(nameWithClassifier: String): String {
            val parts = nameWithClassifier.split(":")
            val group = parts[0].replace('.', '/')
            val artifact = parts[1]
            val version = parts.getOrElse(2) { "0" }
            val classifier = parts.getOrElse(3) { "" }
            val file = if (classifier.isEmpty()) "$artifact-$version.jar"
            else "$artifact-$version-$classifier.jar"
            return "$group/$artifact/$version/$file"
        }
    }
}

/**
 * ============================================================
 * rules 规则判断
 * ============================================================
 *
 * MC 的 rules 是“白名单 + 黑名单”叠加：
 *   - 数组整体为空 -> 允许
 *   - 从前往后遍历，action=allow 且匹配 -> allowed=true
 *   - action=disallow 且匹配 -> allowed=false
 * 匹配条件可以是 os.name / os.arch / features（features 我们一律当 false）
 */
object Rules {

    /** Android 上把系统当成 linux 处理（MC 官方没有 android 这个 os.name） */
    const val OS_LINUX = "linux"

    /** 我们的目标架构 */
    const val ARCH_ARM64 = true

    private const val OS_NAME = "linux"
    private const val OS_ARCH = "arm64"

    fun allows(rules: JSONArray?, osName: String = OS_NAME, arm64: Boolean = ARCH_ARM64): Boolean {
        // 没有 rules 字段 = 无条件允许
        if (rules == null || rules.length() == 0) return true

        var allowed = false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (!matches(rule, osName, arm64)) continue
            allowed = rule.optString("action") == "allow"
        }
        return allowed
    }

    private fun matches(rule: JSONObject, osName: String, arm64: Boolean): Boolean {
        // features 判断：is_demo_user 之类，我们永远不满足
        val features = rule.optJSONObject("features")
        if (features != null && features.length() > 0) return false

        val os = rule.optJSONObject("os") ?: return true   // 没写 os 就是全平台匹配

        val wantName = os.optString("name").ifEmpty { null }
        if (wantName != null && wantName != osName) return false

        val wantArch = os.optString("arch").ifEmpty { null }
        if (wantArch != null) {
            // json 里可能出现 x86 / arm64 / x86_64
            val actual = if (arm64) "arm64" else OS_ARCH
            if (wantArch != actual) return false
        }
        return true
    }
}
