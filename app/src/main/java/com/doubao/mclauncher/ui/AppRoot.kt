package com.doubao.mclauncher.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doubao.mclauncher.core.ExportManager
import com.doubao.mclauncher.core.Paths
import com.doubao.mclauncher.core.RemoteVersion
import com.doubao.mclauncher.launch.JreLocator
import com.doubao.mclauncher.launch.JreRemote
import com.doubao.mclauncher.launch.PojavBridge
import com.doubao.mclauncher.mod.Modrinth
import com.doubao.mclauncher.mod.ModsManager
import kotlin.math.roundToInt

/**
 * ============================================================
 * 所有界面的根容器
 * ============================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: LauncherViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 正在安装/下载的版本选择对话框
    var pendingVersion by remember { mutableStateOf<RemoteVersion?>(null) }
    // 启动设置对话框（要启动哪个版本）
    var launchingVersion by remember { mutableStateOf<String?>(null) }
    // 「从文件导入 JRE」时等用户选大版本
    var pendingImportMajor by remember { mutableStateOf<Int?>(null) }

    // 手动导入 JRE：用户选好文件后按大版本号解压
    val importJreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val major = pendingImportMajor
        if (uri != null && major != null) {
            vm.installJreFromUri(uri, major)
        }
        pendingImportMajor = null
    }

    // 从 Pojav 切回来时刷新状态（Pojav 里可能改过目录 / 下过 JRE）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 旧版存储权限请求器（API 24~28）
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refreshLocal() }

    fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：跳到「所有文件访问权限」页面
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (e: Throwable) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.screen) {
                            Screen.HOME -> "MC 启动器"
                            Screen.VERSIONS -> "选择版本"
                            Screen.JRE -> "Java 运行时"
                            Screen.MODS -> "模组仓库（Modrinth）"
                        }
                    )
                },
                navigationIcon = {
                    if (state.screen != Screen.HOME) {
                        IconButton(onClick = { vm.setScreen(Screen.HOME) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.loadVersions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.screen) {
                Screen.HOME -> HomeScreen(
                    state = state,
                    onOpenVersions = { vm.setScreen(Screen.VERSIONS) },
                    onOpenJre = { vm.setScreen(Screen.JRE) },
                    onOpenMods = { vm.setScreen(Screen.MODS) },
                    onRequestStorage = ::requestStorage,
                    onLaunch = { launchingVersion = it },
                    onExport = { vm.exportToPojav() },
                    onOpenPojav = { vm.openPojav() },
                    onCopyPath = { copyToClipboard(context, PojavBridge.gameDirPath()) },
                    onRefresh = { vm.refreshLocal() }
                )

                Screen.VERSIONS -> VersionListScreen(
                    state = state,
                    onPick = { pendingVersion = it },
                    onRetry = { vm.loadVersions() }
                )

                Screen.MODS -> ModsScreen(
                    state = state,
                    onQueryChange = { vm.setModQuery(it) },
                    onSearch = { vm.searchMods() },
                    onGameVersionChange = { vm.setModGameVersion(it) },
                    onLoaderChange = { vm.setModLoader(it) },
                    onInstall = { vm.installMod(it) },
                    onDelete = { vm.deleteMod(it) },
                    onToggle = { vm.toggleMod(it) },
                    onRefreshMods = { vm.refreshMods() }
                )

                Screen.JRE -> JreScreen(
                    state = state,
                    onInstall = { vm.installJre(it) },
                    onImportFile = { major ->
                        // 记下要装的大版本，然后弹系统文件选择器
                        pendingImportMajor = major
                        importJreLauncher.launch(arrayOf("*/*"))
                    },
                    onInstallFromUrl = { url, major -> vm.installJreFromUrl(url, major) }
                )
            }

            // 安装进度遮罩（覆盖全屏，防止用户中途点别处）
            if (state.busy) {
                InstallOverlay(
                    stage = state.stage,
                    progress = state.progress,
                    logs = state.logs,
                    onCancel = { vm.cancelTask() }
                )
            }
        }

        // message 提示（用最简单的方式，避免引入 SnackbarHost 复杂度）
        state.message?.let { msg ->
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                    Text(msg, Modifier.padding(12.dp), color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }

    // ---------- 选择加载器 ----------
    pendingVersion?.let { version ->
        AlertDialog(
            onDismissRequest = { pendingVersion = null },
            title = { Text("安装 ${version.id}") },
            text = {
                Column {
                    Text("选择要安装的加载器：", fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.install(version.id, LoaderType.VANILLA); pendingVersion = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("原版 Vanilla（最快，先验证能启动）") }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.install(version.id, LoaderType.FORGE); pendingVersion = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Forge（会自动跑官方安装器）") }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.install(version.id, LoaderType.FABRIC); pendingVersion = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Fabric（秒装，需先装原版）") }
                    if (state.jreMajors.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "提示：装 Forge / Fabric 需要 JRE，当前一个都没检测到",
                            color = Color(0xFFFFA000), fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingVersion = null }) { Text("取消") } }
        )
    }

    // ---------- 启动设置 ----------
    launchingVersion?.let { versionId ->
        LaunchConfigDialog(
            versionId = versionId,
            onDismiss = { launchingVersion = null },
            onStart = { name, mem ->
                vm.launch(versionId, name, mem)
                launchingVersion = null
            }
        )
    }
}

// ================================================================
// 首页
// ================================================================
@Composable
private fun HomeScreen(
    state: UiState,
    onOpenVersions: () -> Unit,
    onOpenJre: () -> Unit,
    onOpenMods: () -> Unit,
    onRequestStorage: () -> Unit,
    onLaunch: (String) -> Unit,
    onExport: () -> Unit,
    onOpenPojav: () -> Unit,
    onCopyPath: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- 存储卡片 ----
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("存储", fontWeight = FontWeight.Bold)
                Text(Paths.root().absolutePath, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "剩余空间: ${if (state.freeMb < 0) "未知" else "${state.freeMb} MB"}",
                    fontSize = 12.sp
                )
                Text(
                    "JRE: ${if (state.jreMajors.isEmpty()) "未安装" else state.jreMajors.joinToString("/") }",
                    fontSize = 12.sp
                )
                if (!state.hasStorage) {
                    Text("⚠ 未获得存储权限，无法写入 /sdcard", color = Color(0xFFFF6E40), fontSize = 12.sp)
                    Button(onClick = onRequestStorage) { Text("申请存储权限") }
                }
            }
        }

        // ---- 主操作 ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpenVersions, modifier = Modifier.weight(1f)) {
                Text("浏览版本库")
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                Text("刷新")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpenMods, modifier = Modifier.weight(1f)) {
                Text("模组仓库")
            }
            OutlinedButton(onClick = onOpenJre, modifier = Modifier.weight(1f)) {
                Text("Java 运行时")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Text("一键导出到 Pojav")
            }
        }

        // ---- 已安装版本 ----
        Text("已安装 (${state.installed.size})", fontWeight = FontWeight.Bold)
        if (state.installed.isEmpty()) {
            Text("还没有安装任何版本，点上面「浏览版本库」开始", fontSize = 13.sp, color = Color.Gray)
        } else {
            state.installed.forEach { id ->
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(id, fontWeight = FontWeight.Medium)
                            Text(
                                if (id.contains("forge", true) || id.contains("fabric", true)) "模组加载器"
                                else "原版",
                                fontSize = 11.sp, color = Color.Gray
                            )
                        }
                        IconButton(onClick = { onLaunch(id) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "启动", tint = Color(0xFF5B8C3E))
                        }
                    }
                }
            }
        }

        // ---- 方案 B：导出到 PojavLauncher ----
        PojavExportCard(
            state = state,
            onExport = onExport,
            onOpenPojav = onOpenPojav,
            onCopyPath = onCopyPath
        )

        // ---- 日志 ----
        if (state.logs.isNotEmpty()) {
            Text("运行日志", fontWeight = FontWeight.Bold)
            Card {
                Text(
                    state.logs.takeLast(30).joinToString("\n"),
                    Modifier.padding(10.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(60.dp))
    }
}

// ================================================================
// 版本列表页
// ================================================================
@Composable
private fun VersionListScreen(
    state: UiState,
    onPick: (RemoteVersion) -> Unit,
    onRetry: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var onlyRelease by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索版本号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            FilterChip(
                selected = onlyRelease,
                onClick = { onlyRelease = true },
                label = { Text("仅正式版") }
            )
            FilterChip(
                selected = !onlyRelease,
                onClick = { onlyRelease = false },
                label = { Text("全部（含快照）") }
            )
        }

        when {
            state.versionsLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.versionError != null -> Column(Modifier.padding(16.dp)) {
                Text(state.versionError, color = Color(0xFFFF6E40))
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetry) { Text("重试") }
            }

            else -> {
                val filtered = state.versions.filter {
                    (it.id.contains(query, true)) && (!onlyRelease || it.type == "release")
                }
                Text("共 ${filtered.size} 个版本", fontSize = 12.sp, color = Color.Gray)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { v ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(v.id, fontWeight = FontWeight.Medium)
                                    Text("${v.type}  ${v.releaseTime}", fontSize = 11.sp, color = Color.Gray)
                                }
                                if (state.installed.contains(v.id)) {
                                    Text("已安装", fontSize = 11.sp, color = Color(0xFF5B8C3E))
                                } else {
                                    TextButton(onClick = { onPick(v) }) { Text("安装") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================
// 安装进度遮罩
// ================================================================
@Composable
private fun InstallOverlay(
    stage: String,
    progress: Float,
    logs: List<String>,
    onCancel: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.fillMaxWidth().padding(24.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("安装中", fontWeight = FontWeight.Bold)
                Text(stage, fontSize = 13.sp)

                if (progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${(progress * 100).roundToInt()}%", fontSize = 12.sp)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                Text(
                    logs.takeLast(12).joinToString("\n"),
                    Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
        }
    }
}

// ================================================================
// 启动配置对话框
// ================================================================
@Composable
private fun LaunchConfigDialog(
    versionId: String,
    onDismiss: () -> Unit,
    onStart: (String, Int) -> Unit
) {
    var name by remember { mutableStateOf("Steve") }
    var memory by remember { mutableStateOf(2048f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启动 $versionId") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("玩家名（离线模式）") },
                    singleLine = true
                )
                Text("内存上限: ${memory.roundToInt()} MB", fontSize = 13.sp)
                Slider(
                    value = memory,
                    onValueChange = { memory = it },
                    valueRange = 512f..4096f,
                    steps = 6
                )
                Text(
                    "建议 2G 以内，手机内存小的给 1G 就够；太大容易被系统杀掉。",
                    fontSize = 11.sp, color = Color.Gray
                )
            }
        },
        confirmButton = { TextButton(onClick = { onStart(name, memory.roundToInt()) }) { Text("启动") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ================================================================
// 方案 B：导出到 PojavLauncher（含体检结果）
// ================================================================
@Composable
private fun PojavExportCard(
    state: UiState,
    onExport: () -> Unit,
    onOpenPojav: () -> Unit,
    onCopyPath: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.pojavInstalled) "运行后端：PojavLauncher（已安装）"
                else "运行后端：PojavLauncher（未安装）",
                fontWeight = FontWeight.Bold, color = Color.White
            )
            Text(
                "本 App 只负责下载和版本管理，JRE、渲染层、触屏映射都交给 Pojav。\n" +
                    "游戏目录：${PojavBridge.gameDirPath()}",
                fontSize = 12.sp, color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExport, enabled = !state.exporting) {
                    Text(if (state.exporting) "体检中…" else "一键导出")
                }
                OutlinedButton(onClick = onCopyPath) { Text("复制路径") }
                OutlinedButton(onClick = onOpenPojav) {
                    Text(if (state.pojavInstalled) "打开 Pojav" else "去下载")
                }
            }

            if (!state.pojavInstalled) {
                Text(
                    "点「一键导出」后会自动打开 GitHub 下载页；装完 Pojav 再回来导一次。",
                    fontSize = 11.sp, color = Color(0xFFFFE082)
                )
            }

            // ---- 体检结果 ----
            if (state.exportChecks.isNotEmpty()) {
                Text("体检结果", fontWeight = FontWeight.Medium, color = Color.White, fontSize = 13.sp)
                state.exportChecks.forEach { c ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(c.id, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(
                                c.summary,
                                fontSize = 12.sp,
                                color = if (c.ready) Color(0xFFB9F6CA) else Color(0xFFFFCC80)
                            )
                        }
                        c.problems.forEach { p ->
                            Text("· $p", fontSize = 11.sp, color = Color(0xFFFFCC80))
                        }
                    }
                }
                Text(
                    "说明文件已写到：${state.exportGuidePath ?: "（生成失败）"}",
                    fontSize = 10.sp, color = Color.White
                )
            }
        }
    }
}

// ================================================================
// JRE 安装页（方案 A / C 才需要）
// ================================================================
@Composable
private fun JreScreen(
    state: UiState,
    onInstall: (JreRemote.Build) -> Unit,
    onImportFile: (Int) -> Unit,
    onInstallFromUrl: (String, Int) -> Unit
) {
    val context = LocalContext.current
    var manualUrl by remember { mutableStateOf("") }
    var manualMajor by remember { mutableStateOf(17) }
    // 选大版本 → 再弹文件选择器
    var showImportDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- 提示：什么情况下才需要装 ----
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("这一页什么时候才用？", fontWeight = FontWeight.Bold)
                Text(
                    "只用 Pojav 启动（方案 B）→ **完全不用装**，Pojav 自己管 JRE。\n" +
                        "想让本 App 自己起 JVM（方案 A / fork Pojav 的 C）→ 才需要在这里装。",
                    fontSize = 12.sp
                )
            }
        }

        // ---- 已安装的 JRE ----
        Text("已安装 JRE（${state.jreMajors.size}）", fontWeight = FontWeight.Bold)
        val installed = JreLocator.listAll(context)
        if (installed.isEmpty()) {
            Text("一个都没有", fontSize = 13.sp, color = Color.Gray)
        } else {
            installed.forEach { dir ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(dir.name, fontWeight = FontWeight.Medium)
                        Text(dir.absolutePath, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        val lib = JreLocator.findLibJvm(dir)
                        Text(
                            "libjvm: ${lib?.absolutePath ?: "未找到（JRE 不完整）"}",
                            fontSize = 10.sp,
                            color = if (lib != null) Color(0xFF5B8C3E) else Color(0xFFFF6E40)
                        )
                    }
                }
            }
        }

        // ---- 在线下载（Pojav 预编译） ----
        Text("在线安装（Pojav 预编译包，直连 GitHub）", fontWeight = FontWeight.Bold)
        JreRemote.CATALOG.forEach { build ->
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(build.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (build.sizeBytes > 0) "约 ${build.sizeBytes / 1024 / 1024} MB · ${build.fileName}"
                        else build.fileName,
                        fontSize = 11.sp, color = Color.Gray
                    )
                    Button(
                        onClick = { onInstall(build) },
                        enabled = !state.jreBusy
                    ) { Text("下载并安装 Java ${build.major}") }
                }
            }
        }

        // ---- 进度 ----
        if (state.jreBusy) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.jreStage, fontSize = 12.sp)
                    if (state.jreProgress >= 0f) {
                        LinearProgressIndicator(
                            progress = { state.jreProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${(state.jreProgress * 100).roundToInt()}%", fontSize = 11.sp)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // ---- 手动导入 ----
        Text("从本地文件导入（GitHub 慢的时候用这个）", fontWeight = FontWeight.Bold)
        OutlinedButton(
            onClick = { showImportDialog = true },
            enabled = !state.jreBusy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("选择 .tar.xz / .zip 文件导入") }

        // ---- 手填直链 ----
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("手填 JRE 直链", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = manualUrl,
                    onValueChange = { manualUrl = it },
                    label = { Text("https://.../jre17-arm64.tar.xz") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(8, 17, 21).forEach { m ->
                        FilterChip(
                            selected = manualMajor == m,
                            onClick = { manualMajor = m },
                            label = { Text("Java $m") }
                        )
                    }
                }
                Button(
                    onClick = { onInstallFromUrl(manualUrl, manualMajor) },
                    enabled = !state.jreBusy && manualUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("下载并安装") }
            }
        }

        // ---- 日志 ----
        if (state.logs.isNotEmpty()) {
            Text("日志", fontWeight = FontWeight.Bold)
            Card {
                Text(
                    state.logs.takeLast(15).joinToString("\n"),
                    Modifier.padding(10.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
        }

        Text(
            "注：Pojav 的仓库只提供 Java 8 / 17 的 arm64 包，没有 21。\n" +
                "MC 1.20.5 以上要求 Java 21，这类版本请直接走方案 B（Pojav 启动）。",
            fontSize = 11.sp, color = Color.Gray
        )
        Spacer(Modifier.height(40.dp))
    }

    // 选大版本的对话框
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("选择 JRE 大版本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(8, 17, 21).forEach { m ->
                        Button(
                            onClick = {
                                showImportDialog = false
                                onImportFile(m)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Java $m") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } }
        )
    }
}

/** 复制文本到剪贴板（导出时把路径给用户，方便粘到 Pojav 的目录选择框） */
// ================================================================
// 模组仓库页（Modrinth 开放 API，无需 API Key）
// ================================================================
@Composable
private fun ModsScreen(
    state: UiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onGameVersionChange: (String) -> Unit,
    onLoaderChange: (String) -> Unit,
    onInstall: (Modrinth.Project) -> Unit,
    onDelete: (java.io.File) -> Unit,
    onToggle: (java.io.File) -> Unit,
    onRefreshMods: () -> Unit
) {
    val loaders = listOf("fabric", "forge", "quilt", "neoforge")

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // ---- 过滤条件 ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.modGameVersion,
                onValueChange = onGameVersionChange,
                label = { Text("游戏版本") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onSearch) { Text("搜索") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            loaders.forEach { l ->
                FilterChip(
                    selected = state.modLoader == l,
                    onClick = { onLoaderChange(l) },
                    label = { Text(l) }
                )
            }
        }
        OutlinedTextField(
            value = state.modQuery,
            onValueChange = onQueryChange,
            label = { Text("关键词，如 sodium / jei / fabric-api") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (state.modBusy) {
            Spacer(Modifier.height(8.dp))
            Text(state.modStage, fontSize = 12.sp)
            if (state.modProgress >= 0f) {
                LinearProgressIndicator(progress = { state.modProgress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        state.modMessage?.let {
            Text(it, fontSize = 12.sp, color = Color(0xFF5B8C3E))
        }
        state.modError?.let {
            Text(it, fontSize = 12.sp, color = Color(0xFFFF6E40))
        }

        // ---- 已装模组 ----
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("已装模组 (${state.installedMods.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefreshMods) { Text("刷新") }
        }
        Text(ModsManager.modsDir().absolutePath, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)

        if (state.installedMods.isNotEmpty()) {
            Card {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.installedMods.take(8).forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                f.name + if (f.name.endsWith(".disabled")) "（已禁用）" else "",
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { onToggle(f) }) {
                                Text(if (f.name.endsWith(".disabled")) "启用" else "禁用", fontSize = 11.sp)
                            }
                            TextButton(onClick = { onDelete(f) }) { Text("删除", fontSize = 11.sp, color = Color(0xFFD84315)) }
                        }
                    }
                    if (state.installedMods.size > 8) {
                        Text("…共 ${state.installedMods.size} 个", fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        }

        // ---- 搜索结果 ----
        if (state.modsLoading) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.modResults, key = { it.slug }) { p ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${p.downloads / 1000}K 下载", fontSize = 11.sp, color = Color.Gray)
                        }
                        Text(p.description, fontSize = 12.sp, maxLines = 3)
                        Text(
                            p.loaders.joinToString(" / ") + " · " + p.categories.take(4).joinToString(", "),
                            fontSize = 10.sp, color = Color.Gray
                        )
                        Row {
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { onInstall(p) }, enabled = !state.modBusy) {
                                Text(if (state.modBusy) "忙…" else "安装（自动带依赖）")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Minecraft 目录", text))
    } catch (e: Throwable) {
        // 剪贴板失败不影响主流程
    }
}
