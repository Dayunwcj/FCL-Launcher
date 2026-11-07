package com.doubao.mclauncher.mod

import com.doubao.mclauncher.core.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * ============================================================
 * Modrinth 客户端 —— App 自己下模组就靠它
 * ============================================================
 *
 * 为什么选 Modrinth 而不是 CurseForge：
 *   · Modrinth 的 v2 API **完全开放、不要 API Key**，CurseForge 要申请 key 才能查；
 *   · 它按 game_version / loader 做了结构化过滤，正好对应我们要的“1.20.1 + fabric”；
 *   · 每个版本都直接给 file 直链，不用二跳。
 *
 * 用到的两个端点（都实测可用，不需要登录）：
 *   GET /v2/search?query=&facets=[["project_type:mod"],["versions:1.20.1"],["categories:fabric"]]
 *   GET /v2/project/{slug}/version?game_versions=["1.20.1"]&loaders=["fabric"]
 */
object Modrinth {

    private const val API = "https://api.modrinth.com/v2"

    /** 搜索结果里的一条模组 */
    data class Project(
        val slug: String,
        val title: String,
        val description: String,
        val iconUrl: String?,
        val downloads: Long,
        val categories: List<String>,
        val loaders: List<String>
    )

    /** 某个版本下的一个可下载文件 */
    data class ModFile(
        val url: String,
        val fileName: String,
        val size: Long
    )

    /** 一个具体版本（对应一次发布） */
    data class ModVersion(
        val id: String,
        val name: String,
        val versionNumber: String,
        val gameVersions: List<String>,
        val loaders: List<String>,
        val files: List<ModFile>,
        /** required 依赖（多是没有版本号的 Fabric API 之类） */
        val requiredProjects: List<String>
    ) {
        val primaryFile: ModFile? get() = files.firstOrNull()
    }

    /** 要装的东西：版本 + 具体文件（依赖递归展开后是一串） */
    data class Target(val version: ModVersion, val file: ModFile, val fromDependency: Boolean)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------
    // 搜索
    // ------------------------------------------------------------
    suspend fun search(
        query: String,
        gameVersion: String,
        loader: String,
        limit: Int = 20
    ): List<Project> {
        // facets 是 Modrinth 的过滤表达式，嵌套数组 = AND，内层多值 = OR
        val facets = buildString {
            append("[[\"project_type:mod\"]")
            if (gameVersion.isNotBlank()) append(",[\"versions:$gameVersion\"]")
            if (loader.isNotBlank()) append(",[\"categories:$loader\"]")
            append("]")
        }
        val url = "$API/search?query=${enc(query)}&limit=$limit&index=relevance&facets=${enc(facets)}"
        val body = Http.text(url, useMirror = false)
        val hits = JSONObject(body).optJSONArray("hits") ?: JSONArray()

        val out = ArrayList<Project>(hits.length())
        for (i in 0 until hits.length()) {
            val o = hits.optJSONObject(i) ?: continue
            out += Project(
                slug = o.optString("slug"),
                title = o.optString("title"),
                description = o.optString("description"),
                iconUrl = o.optString("icon_url").takeIf { it.isNotEmpty() },
                downloads = o.optLong("downloads"),
                categories = o.optJSONArray("categories")?.toStringList() ?: emptyList(),
                loaders = o.optJSONArray("versions")?.toStringList() ?: emptyList()
            )
        }
        return out
    }

    // ------------------------------------------------------------
    // 某模组在指定 游戏版本 + 加载器 下的可用版本
    // ------------------------------------------------------------
    suspend fun versions(slug: String, gameVersion: String, loader: String): List<ModVersion> {
        val gv = "[\"$gameVersion\"]"
        val ld = "[\"$loader\"]"
        val url = "$API/project/${enc(slug)}/version?game_versions=${enc(gv)}&loaders=${enc(ld)}"
        val arr = JSONArray(Http.text(url, useMirror = false))
        val out = ArrayList<ModVersion>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val files = ArrayList<ModFile>()
            o.optJSONArray("files")?.let { fa ->
                for (j in 0 until fa.length()) {
                    val f = fa.optJSONObject(j) ?: continue
                    val u = f.optString("url")
                    if (u.isEmpty()) continue
                    // 只装 jar；源码包 / 签名包不要
                    val name = f.optString("filename")
                    if (!name.endsWith(".jar")) continue
                    files += ModFile(u, name, f.optLong("size"))
                }
            }
            // primary 的排前面
            val deps = ArrayList<String>()
            o.optJSONArray("dependencies")?.let { da ->
                for (j in 0 until da.length()) {
                    val d = da.optJSONObject(j) ?: continue
                    if (d.optString("dependency_type") != "required") continue
                    val pid = d.optString("project_id")
                    if (pid.isNotEmpty()) deps += pid
                }
            }
            out += ModVersion(
                id = o.optString("id"),
                name = o.optString("name"),
                versionNumber = o.optString("version_number"),
                gameVersions = o.optJSONArray("game_versions")?.toStringList() ?: emptyList(),
                loaders = o.optJSONArray("loaders")?.toStringList() ?: emptyList(),
                files = files,
                requiredProjects = deps
            )
        }
        return out
    }

    /**
     * 把「要装的一个模组」展开成完整安装列表：主文件 + 所有 required 依赖（递归，最多 4 层）。
     *
     * 这一步很关键：只装主 jar 的话，绝大多数 Fabric 模组会因为缺 Fabric API 直接崩。
     */
    suspend fun resolve(
        slug: String,
        gameVersion: String,
        loader: String
    ): List<Target> {
        val list = ArrayList<Target>()
        val seenProject = HashSet<String>()      // 按 project id 去重（依赖用 id）
        val seenSlug = HashSet<String>()

        fun addOne(key: String, fromDep: Boolean): Boolean {
            if (key.isBlank() || !seenProject.add(key)) return false
            return true
        }

        val rootVersions = versions(slug, gameVersion, loader)
        val root = rootVersions.firstOrNull { it.primaryFile != null } ?: return emptyList()
        if (!addOne(slug, false)) return emptyList()
        seenSlug += slug
        root.primaryFile?.let { list += Target(root, it, fromDependency = false) }

        // 广度优先展开依赖
        var queue = root.requiredProjects.toList()
        var depth = 0
        while (queue.isNotEmpty() && depth < 4) {
            val next = ArrayList<String>()
            for (pid in queue) {
                if (!seenProject.add(pid)) continue
                val depVersions = versions(pid, gameVersion, loader)
                val dep = depVersions.firstOrNull { it.primaryFile != null } ?: continue
                dep.primaryFile?.let { list += Target(dep, it, fromDependency = true) }
                next += dep.requiredProjects
            }
            queue = next
            depth++
        }
        return list
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out += optString(i)
        return out
    }
}
