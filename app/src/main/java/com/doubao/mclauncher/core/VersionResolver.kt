package com.doubao.mclauncher.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ============================================================
 * 版本继承解析（inheritsFrom）
 * ============================================================
 *
 * 这是 Forge / Fabric 能启动的关键，很多人第一次写启动器就卡在这。
 *
 * Forge 安装后生成的 versions/1.20.1-forge-47.2.0/1.20.1-forge-47.2.0.json
 * 里其实只有：
 *   { "id": "...", "inheritsFrom": "1.20.1", "mainClass": "cpw.mods.bootstraplauncher...",
 *     "libraries": [ 只有 forge 自己那几个 ], "arguments": { "game": [...], "jvm": [...] } }
 *
 * 也就是说：client.jar、上千个原版 libraries、assets 索引，**全都不在里面**，
 * 必须顺着 inheritsFrom 找到父版本，把父版本的这些东西合并进来。
 *
 * 合并规则（和官方启动器一致）：
 *   libraries  -> 父在前、子在後（后面的同名库覆盖前面，所以子版本优先）
 *   arguments  -> jvm / game 两个数组分别拼接，还是子版本在后
 *   其他字段   -> 子有就用子的，子没有保留父的（比如 assets、downloads.client）
 */
object VersionResolver {

    private const val TAG = "VersionResolver"

    /** 解析结果：合并后的版本对象 + 客户端 jar 实际在哪个版本的目录里 */
    data class Resolved(
        val version: McVersion,
        val clientJarVersion: String,   // Forge 版本自己没有 client.jar，要用父版本的
        val vanillaId: String           // 最顶层原版 id（assets 也在这里）
    )

    /**
     * 从本地 versions 目录解析（启动时用）。
     * @throws IOException 版本没安装 / json 缺失
     */
    fun resolveLocal(id: String, depth: Int = 0): Resolved {
        if (depth > 4) throw IOException("inheritsFrom 层级过深，疑似循环引用: $id")

        val f = Paths.versionJson(id)
        if (!f.isFile) throw IOException("版本未安装: ${f.absolutePath}")

        val child = JSONObject(f.readText())
        val parentId = child.optString("inheritsFrom").ifEmpty { null }

        // 没有父版本 = 这就是原版
        if (parentId == null) {
            return Resolved(McVersion(child), clientJarVersion = id, vanillaId = id)
        }

        val parent = try {
            resolveLocal(parentId, depth + 1)
        } catch (e: Throwable) {
            Log.w(TAG, "父版本 $parentId 解析失败", e)
            throw IOException("依赖的父版本 $parentId 未安装，请先装原版 $parentId")
        }

        val merged = merge(parent.version.raw, child)
        return Resolved(
            version = McVersion(merged),
            clientJarVersion = parent.clientJarVersion,
            vanillaId = parent.vanillaId
        )
    }

    /** 深度合并两个版本 json */
    fun merge(parent: JSONObject, child: JSONObject): JSONObject {
        // 深拷贝父对象，避免污染缓存（JSONObject 的 toString/构造是最省事的深拷贝）
        val out = JSONObject(parent.toString())

        val keys = child.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (key) {
                // ---- libraries 拼接 ----
                "libraries" -> {
                    out.put("libraries", concatArrays(parent.optJSONArray("libraries"), child.optJSONArray("libraries")))
                }

                // ---- arguments 分 jvm / game 拼接 ----
                "arguments" -> {
                    val pArgs = parent.optJSONObject("arguments")
                    val cArgs = child.optJSONObject("arguments")
                    val merged = JSONObject()
                    if (pArgs != null) {
                        val k = pArgs.keys()
                        while (k.hasNext()) {
                            val a = k.next()
                            merged.put(a, pArgs.opt(a))
                        }
                    }
                    if (cArgs != null) {
                        val k = cArgs.keys()
                        while (k.hasNext()) {
                            val a = k.next()
                            merged.put(
                                a,
                                concatArrays(merged.optJSONArray(a), cArgs.optJSONArray(a))
                            )
                        }
                    }
                    out.put("arguments", merged)
                }

                // ---- 其他字段子覆盖父 ----
                else -> out.put(key, child.get(key))
            }
        }
        return out
    }

    private fun concatArrays(a: JSONArray?, b: JSONArray?): JSONArray {
        val out = JSONArray()
        if (a != null) for (i in 0 until a.length()) out.put(a.opt(i))
        if (b != null) for (i in 0 until b.length()) out.put(b.opt(i))
        return out
    }

    /** 本地所有可启动版本（含加载器版本），按修改时间排序 */
    fun listPlayable(): List<String> {
        val dir = Paths.versions()
        return dir.listFiles()
            ?.filter { it.isDirectory && File(it, "${it.name}.json").isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            ?: emptyList()
    }
}
