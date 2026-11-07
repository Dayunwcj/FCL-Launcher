package com.doubao.mclauncher.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.doubao.mclauncher.launch.JavaRunner
import com.doubao.mclauncher.launch.JreLocator
import org.json.JSONArray
import java.io.File

/**
 * ============================================================
 * 模组加载器安装：Forge / Fabric
 * ============================================================
 *
 * 核心思路：**不要把安装逻辑自己写一遍**，而是下载官方 installer jar，
 * 用我们自己的 JVM 去跑它的 main（命令行模式）。这样：
 *
 *   - 版本 json 合并、libraries 下载、processor 执行、版本重命名
 *     全部由官方安装器完成，兼容性最好；
 *   - 我们只负责：查版本列表 -> 下 jar -> 调 JavaRunner -> 校验产物。
 *
 * Forge 的 installer 会自己在 <gameDir>/versions/<forge版本>/ 下生成版本目录，
 * Fabric 也一样（fabric-loader-x.y.z-<mc版本>），之后我们的启动流程
 * 只要把版本 id 换成这个新 id 就能直接启动。
 */
object LoaderInstaller {

    private const val TAG = "LoaderInstaller"

    /** Forge 构建信息 */
    data class ForgeBuild(
        val build: Int,          // BMCLAPI 用的构建号（下载安装包要用它）
        val version: String,     // 完整版本号，如 47.2.0
        val mcVersion: String
    ) {
        /** 安装后生成的版本目录名 */
        val fullId: String get() = "$mcVersion-forge-$version"
    }

    // ================================================================
    // 1. Forge
    // ================================================================

    /**
     * 查某个 MC 版本可用的 Forge 构建。
     * 接口：GET https://bmclapi2.bangbang93.com/forge/minecraft/1.20.1
     * 返回数组，每项有 build / version / mcversion / files[]
     */
    suspend fun listForge(mcVersion: String): List<ForgeBuild> = withContext(Dispatchers.IO) {
        val text = Http.text(Api.forgeList(mcVersion))
        val arr = JSONArray(text)
        val out = ArrayList<ForgeBuild>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val build = o.optInt("build", -1)
            val ver = o.optString("version")
            if (build <= 0 || ver.isEmpty()) continue
            out += ForgeBuild(build, ver, o.optString("mcversion", mcVersion))
        }
        // build 号越大越新，倒序排，UI 上第一个就是最新
        out.sortedByDescending { it.build }
    }

    /**
     * 安装 Forge：
     *   1) 从 BMCLAPI 下 installer jar（实测 1.20.1 的 47.0.1 是 5.8MB）
     *   2) 用 JRE 跑 `--installClient <gameDir>`
     *   3) 检查 versions/<fullId>/<fullId>.json 是否生成
     *
     * @return 安装后的版本 id（可直接拿去启动）
     */
    suspend fun installForge(
        jreDir: File,
        mcVersion: String,
        build: ForgeBuild,
        onLog: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        require(JreLocator.isValidJre(jreDir)) { "JRE 无效，无法运行 Forge 安装器" }

        val installerDir = File(Paths.root(), "installers")
        installerDir.mkdirs()
        val jar = File(installerDir, "forge-$mcVersion-${build.version}-installer.jar")

        onLog("下载 Forge 安装器: ${build.fullId}")
        Downloader().downloadOne(Downloader.Item(Api.forgeInstaller(build.build), jar, null, -1)) { }

        onLog("运行 Forge 安装器（这一步会下几百 MB 依赖，可能要几分钟）")
        // 注意：不同 Forge 大版本的命令行参数略有差异，
        // 1.13+ 统一支持 --installClient <dir>
        val res = JavaRunner.runJar(
            jreDir = jreDir,
            jar = jar,
            args = listOf("--installClient", Paths.root().absolutePath),
            jvmOptions = listOf("-Duser.home=${Paths.root().absolutePath}")
        )
        if (res.exitCode != 0) {
            throw IOException("Forge 安装器退出码 ${res.exitCode}: ${res.error ?: "未知错误"}")
        }

        // 安装器生成的目录名可能是 1.20.1-forge-47.2.0
        val expected = Paths.versionDir(build.fullId)
        if (!File(expected, "${build.fullId}.json").isFile) {
            // 兜底：扫一遍 versions 找最新的 forge 目录
            val found = Paths.versions().listFiles()
                ?.filter { it.isDirectory && it.name.contains("forge", true) }
                ?.maxByOrNull { it.lastModified() }
            onLog("版本目录不符合预期，使用扫描结果: ${found?.name}")
            return@withContext found?.name
                ?: throw IOException("Forge 安装失败：versions 目录下没找到 forge 版本")
        }
        onLog("Forge 安装成功: ${build.fullId}")
        build.fullId
    }

    // ================================================================
    // 2. Fabric
    // ================================================================

    /**
     * 查 Fabric loader 版本列表。
     *
     * 注意：BMCLAPI 只镜像了 maven 的 jar 文件，maven-metadata.xml 会 404，
     * 所以这里直连官方 maven（国内一般也能访问，慢的话在 README 里换源）。
     */
    suspend fun listFabricLoaders(): List<String> = withContext(Dispatchers.IO) {
        val xml = Http.text("${Api.FABRIC_MAVEN}/net/fabricmc/fabric-loader/maven-metadata.xml", useMirror = false)
        Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1] }.toList()
            .sortedByDescending { it }
    }

    /** 取最新版 Fabric 安装器 jar 的版本号 */
    private suspend fun latestInstallerVersion(): String = withContext(Dispatchers.IO) {
        val xml = Http.text("${Api.FABRIC_MAVEN}/net/fabricmc/fabric-installer/maven-metadata.xml", useMirror = false)
        Regex("<release>([^<]+)</release>").find(xml)?.groupValues?.get(1)
            ?: Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1] }.last()
    }

    /**
     * 安装 Fabric：
     *   1) 下 fabric-installer.jar
     *   2) 跑 `client -dir <gameDir> -mcversion <mc> -loader <ver>`
     *
     * Fabric 安装器不需要下游戏本体，它只是合并出一个新的版本 json，
     * 所以很快（秒级），前提是原版已经装好了。
     */
    suspend fun installFabric(
        jreDir: File,
        mcVersion: String,
        loaderVersion: String,
        onLog: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        require(JreLocator.isValidJre(jreDir)) { "JRE 无效，无法运行 Fabric 安装器" }

        val installerVer = latestInstallerVersion()
        onLog("使用 Fabric 安装器 $installerVer")

        val installerDir = File(Paths.root(), "installers")
        installerDir.mkdirs()
        val jar = File(installerDir, "fabric-installer-$installerVer.jar")
        Downloader().downloadOne(
            Downloader.Item(
                "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$installerVer/fabric-installer-$installerVer.jar",
                jar, null, -1
            )
        ) { }

        onLog("运行 Fabric 安装器: loader=$loaderVersion, mc=$mcVersion")
        val res = JavaRunner.runJar(
            jreDir = jreDir,
            jar = jar,
            args = listOf(
                "client",
                "-dir", Paths.root().absolutePath,
                "-mcversion", mcVersion,
                "-loader", loaderVersion,
                "-noprofile"          // 不生成启动器 profile json，我们用自己的
            ),
            jvmOptions = listOf("-Duser.home=${Paths.root().absolutePath}")
        )
        if (res.exitCode != 0) {
            throw IOException("Fabric 安装器退出码 ${res.exitCode}: ${res.error ?: "未知错误"}")
        }

        val id = "fabric-loader-$loaderVersion-$mcVersion"
        if (!File(Paths.versionDir(id), "$id.json").isFile) {
            throw IOException("Fabric 安装失败：没生成 versions/$id/$id.json")
        }
        onLog("Fabric 安装成功: $id")
        id
    }

    /** 已安装的加载器版本（扫 versions 目录，名字带 forge/fabric 的） */
    fun installedLoaders(): List<String> {
        return Paths.versions().listFiles()
            ?.filter { it.isDirectory && File(it, "${it.name}.json").isFile }
            ?.filter { it.name.contains("forge", true) || it.name.contains("fabric", true) }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()
    }

    private fun log(msg: String) = Log.i(TAG, msg)
}
