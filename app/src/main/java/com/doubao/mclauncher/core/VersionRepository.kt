package com.doubao.mclauncher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ============================================================
 * 版本仓库：负责跟 BMCLAPI 打交道，把“远程信息”取回来
 * ============================================================
 */
object VersionRepository {

    /**
     * 拉取完整版本列表。
     *
     * 接口：GET https://bmclapi2.bangbang93.com/mc/game/version_manifest.json
     * （一开始 wiki 上写的 /minecraft/version 现在已经 404 了，实测只有这个能通，
     *  它会 302 到国内 CDN，OkHttp 要开 followRedirects，Http.client 里已经开了）
     *
     * 返回 900 多个版本，UI 里自己过滤/搜索。
     */
    suspend fun fetchVersionList(): List<RemoteVersion> = withContext(Dispatchers.IO) {
        val text = Http.text(Api.VERSION_MANIFEST)
        val json = JSONObject(text)
        val arr = json.getJSONArray("versions")
        val list = ArrayList<RemoteVersion>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list += RemoteVersion(
                id = o.getString("id"),
                type = o.optString("type", "release"),
                url = o.optString("url"),
                releaseTime = o.optString("releaseTime").replace("T", " ").take(16),
                sha1 = o.optString("sha1").ifEmpty { null }
            )
        }
        list
    }

    /** 最新正式版 / 最新快照，首页展示用 */
    suspend fun latest(): Pair<String, String> = withContext(Dispatchers.IO) {
        val json = JSONObject(Http.text(Api.VERSION_MANIFEST)).getJSONObject("latest")
        json.optString("release") to json.optString("snapshot")
    }

    /**
     * 取某个版本的 json。
     * 用 BMCLAPI 的 /version/{id}/json，比走 manifest 里的官方 url 再改写更直接。
     */
    suspend fun fetchVersionJson(id: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(Http.text(Api.versionJson(id)))
    }
}
