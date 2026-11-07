package com.doubao.mclauncher.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * ============================================================
 * Api + Http：BMCLAPI 地址表 + 统一的 OkHttp 客户端
 * ============================================================
 */

object Api {

    /** BMCLAPI 主站（实测可用，会自动 302 到国内 CDN） */
    const val BMCLAPI = "https://bmclapi2.bangbang93.com"

    /** 官方 Fabric maven（BMCLAPI 只镜像了 jar，maven-metadata.xml 会 404，所以这里用官方兜底） */
    const val FABRIC_MAVEN = "https://maven.fabricmc.net"

    /** 浏览器 UA，部分 CDN 会拦空 UA */
    const val UA = "MCLauncher/1.0 (Android)"

    /**
     * 把 Mojang 官方下载地址改写成 BMCLAPI 镜像地址。
     *
     * 这是 BMCLAPI 最核心的用法：版本 json 里所有 url 都是官方域名，
     * 我们不去改 json，而是在真正下载时把域名换掉，这样任何版本的 json
     * 都能直接走镜像，不用为每个版本写特殊逻辑。
     *
     * 对照表（BMCLAPI 官方文档 + 实测）：
     *   piston-meta.mojang.com   -> bmclapi2.bangbang93.com
     *   launchermeta.mojang.com  -> bmclapi2.bangbang93.com
     *   launcher.mojang.com      -> bmclapi2.bangbang93.com
     *   piston-data.mojang.com   -> bmclapi2.bangbang93.com
     *   libraries.minecraft.net  -> bmclapi2.bangbang93.com/maven
     *   resources.download.minecraft.net -> bmclapi2.bangbang93.com/assets
     *   maven.fabricmc.net       -> bmclapi2.bangbang93.com/maven
     *   files.minecraftforge.net/maven -> bmclapi2.bangbang93.com/maven
     */
    fun mirror(url: String): String {
        // 已经是 BMCLAPI 的地址就原样返回
        if (url.contains("bangbang93.com")) return url

        var u = url
        u = u.replace("https://piston-meta.mojang.com", BMCLAPI)
        u = u.replace("https://launchermeta.mojang.com", BMCLAPI)
        u = u.replace("https://launcher.mojang.com", BMCLAPI)
        u = u.replace("https://piston-data.mojang.com", BMCLAPI)
        u = u.replace("https://libraries.minecraft.net", "$BMCLAPI/maven")
        u = u.replace("https://resources.download.minecraft.net", "$BMCLAPI/assets")
        u = u.replace("https://files.minecraftforge.net/maven", "$BMCLAPI/maven")
        u = u.replace("https://maven.fabricmc.net", "$BMCLAPI/maven")
        return u
    }

    // ---- 具体接口（都实测过） ----

    /** 版本清单：注意是 /mc/game/version_manifest.json，不是 /minecraft/version（那个已 404） */
    val VERSION_MANIFEST = "$BMCLAPI/mc/game/version_manifest.json"

    /** 版本 json */
    fun versionJson(id: String) = "$BMCLAPI/version/$id/json"

    /** 客户端 jar（等价于官方 /version/{id}/client.jar） */
    fun clientJar(id: String) = "$BMCLAPI/version/$id/client"

    /** 资源索引压缩包（不常用，一般直接按 index json 逐个下 object） */
    fun assetsZip(id: String) = "$BMCLAPI/version/$id/assets"

    /** Forge 某个 MC 版本的构建列表 */
    fun forgeList(mcVersion: String) = "$BMCLAPI/forge/minecraft/$mcVersion"

    /** Forge 安装包（参数是上面列表里的 build 号，实测 390 秒下载 5.8MB 的 installer） */
    fun forgeInstaller(build: Int) = "$BMCLAPI/forge/download/$build"

    /** Fabric loader 版本列表（xml） */
    val FABRIC_LOADER_META = "$FABRIC_MAVEN/net/fabricmc/fabric-loader/maven-metadata.xml"

    /** Fabric 安装器 jar（用它自己生成 version json，最省事最不容易出错） */
    const val FABRIC_INSTALLER_GROUP = "net/fabricmc/fabric-installer"
}

object Http {

    private const val TAG = "Http"

    /**
     * 全局单例 OkHttpClient。
     * - 必须开 followRedirects：BMCLAPI 大量接口是 302 到 CDN
     * - 读超时给到 30s，下载大文件时 body 是流式的，不受这个限制
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 取纯文本（json / xml 都走这个）。
     * @param useMirror 是否把地址改写成 BMCLAPI。
     *   maven-metadata.xml 这类文件 BMCLAPI 没有镜像（实测 404），
     *   必须 useMirror = false 直连官方 maven。
     */
    suspend fun text(url: String, useMirror: Boolean = true): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(if (useMirror) Api.mirror(url) else url)
            .header("User-Agent", Api.UA)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}  ${url}")
            resp.body?.string() ?: throw IOException("响应为空: $url")
        }
    }

    /** 直接拿 url 是否存在（HEAD），用于探测镜像文件 */
    suspend fun exists(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(Api.mirror(url))
                .head()
                .header("User-Agent", Api.UA)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Throwable) {
            Log.w(TAG, "HEAD 失败: $url", e)
            false
        }
    }
}

/** Kotlin 标准库没有 IOException 的顶层引用，这里补一个别名，省去每次写 java.io.IOException */
typealias IOException = java.io.IOException
