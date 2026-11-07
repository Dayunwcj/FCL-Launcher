#!/usr/bin/env bash
# ============================================================
# build-fcl-apk.sh —— 一键编出含 FCL 原生层的 debug APK
# ------------------------------------------------------------
# 两种用法：
#
#   ① 在 merge-工程/ 里直接跑（工程已经是合并好的）
#        cd merge-工程 && bash scripts/build-fcl-apk.sh
#
#   ② 从零合并 + 编译（给只有补丁和前端源码的场景）
#        bash scripts/build-fcl-apk.sh --merge /tmp/fcl-build
#        （会 git clone FCL 原仓库 → git apply 补丁 → 编译）
#
# 环境变量（不设就用 PATH 里的）：
#   JAVA_HOME     必须是 JDK 17
#   ANDROID_HOME  SDK 根目录（也可以只写 local.properties 的 sdk.dir）
#
# 编译命令固定为 :app:assembleDebug（:app 是主模块，:FCL 是它的后端库）
# ============================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ_ROOT="$(cd "$HERE/.." && pwd)"

log()  { printf '\033[36m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
die()  { printf '\033[31m[FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- 1. 合并模式：从 FCL 原仓库 + 补丁生成完整工程 ----------
if [ "${1:-}" = "--merge" ]; then
  DEST="${2:-/tmp/fcl-build}"
  PATCH_DIR="$PROJ_ROOT/../patches"
  [ -d "$PATCH_DIR" ] || PATCH_DIR="$PROJ_ROOT/patches"
  PATCH_FILE="$PATCH_DIR/0001-合并启动器前端到FCL.patch"
  [ -f "$PATCH_FILE" ] || die "找不到补丁：$PATCH_FILE"

  if [ ! -d "$DEST/.git" ]; then
    log "clone FCL 原仓库（约 700MB，只拉一层）→ $DEST"
    git clone --depth 1 https://github.com/FCL-Team/FoldCraftLauncher.git "$DEST"
  fi

  log "在 $DEST 上应用补丁（先做锚点校验）"
  ( cd "$DEST" && git apply --check --verbose "$PATCH_FILE" && git apply --3way "$PATCH_FILE" )
  log "合并完成：$DEST"
  PROJ_ROOT="$DEST"
fi

cd "$PROJ_ROOT"
[ -f settings.gradle.kts ] || die "$PROJ_ROOT 里没有 settings.gradle.kts，是不是路径给错了？"

# ---------- 2. 环境检查 ----------
if [ -n "${JAVA_HOME:-}" ]; then JAVA_BIN="$JAVA_HOME/bin/java"; else JAVA_BIN="java"; fi
"$JAVA_BIN" -version 2>&1 | head -1
JVER=$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
[ "$JVER" = "17" ] || die "需要 JDK 17（当前是 $JVER）。AGP 8.13 用 JDK 11 会直接配置失败"

if [ -n "${ANDROID_HOME:-}" ] && [ ! -f local.properties ]; then
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  log "已写 local.properties: sdk.dir=$ANDROID_HOME"
fi
[ -f local.properties ] || log "注意：没有 local.properties，Android Studio 里打开会自动生成"

# ---------- 3. NDK / CMake 检查 ----------
SDK_DIR="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | tr -d '\\' | head -1)}"
if [ -n "$SDK_DIR" ]; then
  [ -d "$SDK_DIR/ndk/27.0.12077973" ] || log "警告：没找到 NDK 27.0.12077973（$SDK_DIR/ndk），CMake 会失败。装：sdkmanager \"ndk;27.0.12077973\""
  [ -d "$SDK_DIR/cmake/3.22.1" ]       || log "警告：没找到 CMake 3.22.1。装：sdkmanager \"cmake;3.22.1\""
fi

# ---------- 4. 编译 ----------
GRADLE_BIN=""
if [ -x "$PROJ_ROOT/gradlew" ]; then GRADLE_BIN="./gradlew"; else GRADLE_BIN="gradle"; fi
log "开始编译：$GRADLE_BIN :app:assembleDebug"
$GRADLE_BIN :app:assembleDebug --no-daemon --stacktrace

APK=$(find "$PROJ_ROOT/app/build/outputs/apk/debug" -name "*.apk" 2>/dev/null | head -1)
[ -n "$APK" ] || die "编译结束但没找到 apk"
log "产物：$APK  ($(du -h "$APK" | cut -f1))"

# ---------- 5. 校验原生库真的进包了 ----------
log "校验原生 so（缺任何一个都说明 NDK 没编成功）"
MISSING=0
for so in libpojavexec.so libpojavexec_awt.so libfcl.so libandroidnsbypass.so libgl4es_114.so libbytehook.so libopenal.so; do
  if unzip -l "$APK" | grep -q "lib/arm64-v8a/$so"; then
    printf '  [OK]   lib/arm64-v8a/%s\n' "$so"
  else
    printf '  [MISS] lib/arm64-v8a/%s\n' "$so"; MISSING=1
  fi
done
[ "$MISSING" -eq 0 ] || die "有原生库没进包，见上面 MISS 项"

log "全部通过。安装：adb install -r \"$APK\""
