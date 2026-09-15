#!/usr/bin/env bash
# ============================================================================
# Mirage-Android 一键构建: systemd-nspawn 沙箱内完成 Rust 交叉编译 + Gradle APK
#
# 流程:
#   1. 宿主: cargo-ndk 交叉编译 mirage-jni (arm64-v8a + x86_64) → jniLibs
#   2. 宿主: 把 android 源码 bind 进 android-builder 容器
#   3. 容器内: gradle assemble{Debug,Release} → APK 输出到宿主
#
# 用法:
#   scripts/build-android.sh          # 完整构建 (debug, 仅供本机开发)
#   scripts/build-android.sh native   # 只构建 Rust 原生库
#   scripts/build-android.sh apk      # 只构建 APK (用已有 jniLibs)
#
#   VARIANT=release scripts/build-android.sh    # 分发构建 (R8 + 正式签名)
#
# ⚠ debug 产物带 debuggable 标志、无 R8 混淆、且由 AOSP 公开调试密钥签名,
#   并内含未鉴权的本机调试接口。任何交付给他人的包必须用 VARIANT=release。
#   release 签名材料来自 android/keystore.properties 或 MIRAGE_KEYSTORE_* 环境变量
#   (均不入版本控制); 缺失时产出未签名 APK 并告警, 不会回落到调试密钥。
# ============================================================================
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
CONTAINER="android-builder"
NDK="/opt/android-sdk/ndk/26.3.11579264"
CARGO="${CARGO:-cargo}"
VARIANT="${VARIANT:-debug}"
case "$VARIANT" in
    debug|release) ;;
    *) echo "!! VARIANT 只能是 debug 或 release (当前: $VARIANT)" >&2; exit 1 ;;
esac

cmd="${1:-all}"

build_native() {
    echo "==> [1/2] 交叉编译 mirage-jni (arm64-v8a) ..."
    export ANDROID_NDK_HOME="$NDK"
    export PATH="/opt/android-toolchain/bin:$PATH"
    local out="$HERE/android/app/src/main/jniLibs"
    rm -rf "$out"; mkdir -p "$out"

    cd "$HERE/native/mirage-jni"
    # 用宿主 rustup + NDK 直接构建 (16KB 页对齐 link-arg 由 .cargo/config.toml 统一声明)
    for target in aarch64-linux-android; do
        local abi="arm64-v8a"
        echo "  -- $target ($abi) [16KB Page Aligned]"
        cargo build --release --target "$target" --lib
        mkdir -p "$out/$abi"
        cp "$HERE/native/mirage-jni/target/$target/release/libmirage_jni.so" "$out/$abi/"
    done
    echo "  ✓ jniLibs:"
    find "$out" -name "*.so" -exec ls -lh {} \; | awk '{print "    " $NF " (" $5 ")"}'
}

build_apk() {
    echo "==> [2/2] 沙箱内 Gradle 构建 APK ..."
    # 确认容器存在
    if [[ ! -d "/var/lib/machines/$CONTAINER" ]]; then
        echo "!! 容器 $CONTAINER 不存在, 请先运行 scripts/setup-sandbox.sh" >&2
        exit 1
    fi
    # 输出/缓存目录
    mkdir -p "$HERE/.build/out" "$HERE/.build/gradle-home"
    chown 1000:1000 "$HERE/.build/out" "$HERE/.build/gradle-home" 2>/dev/null || true

    local gradle_task apk_name
    if [[ "$VARIANT" == "release" ]]; then
        gradle_task="assembleRelease"
        # 无签名配置时 AGP 产出 app-release-unsigned.apk, 有则 app-release.apk
        apk_name="app-release.apk"
        if [[ -z "${MIRAGE_KEYSTORE_PATH:-}" && ! -f "$HERE/android/keystore.properties" ]]; then
            echo "  !! 未配置分发签名, 产物将是未签名 APK (不可直接安装/分发)" >&2
            apk_name="app-release-unsigned.apk"
        fi
    else
        gradle_task="assembleDebug"
        apk_name="app-debug.apk"
    fi
    echo "  -- 构建变体: $VARIANT ($gradle_task)"

    local ver="0.3.0"
    local timestamp
    timestamp=$(date +%Y%m%d_%H%M%S)
    local build_date
    build_date=$(date +%Y.%m.%d)
    local git_hash
    git_hash=$(git -C "$HERE" rev-parse --short HEAD 2>/dev/null || echo "dev")
    local git_count
    git_count=$(git -C "$HERE" rev-list --count HEAD 2>/dev/null || echo "5")
    local build_tag="Release #$git_count ($git_hash)"

    echo "  -- 构建元数据: v$ver · Code #$git_count · Build $build_date · Tag: $build_tag"

    # 容器内以 root 构建 (SDK bind 自宿主, 避免容器内再下载)
    systemd-nspawn -D "/var/lib/machines/$CONTAINER" --as-pid2 \
        --bind="$HERE/android:/workspace" \
        --bind="$HERE/.build/out:/output" \
        --bind="$HERE/.build/gradle-home:/root/.gradle" \
        --bind="/opt/android-sdk:/android-sdk" \
        /bin/bash -c "
            set -e
            export JAVA_HOME=/opt/jdk-17
            export ANDROID_HOME=/android-sdk
            export ANDROID_NDK_HOME=/android-sdk/ndk/26.3.11579264
            export GRADLE_USER_HOME=/root/.gradle
            export GRADLE_OPTS=\"-Dorg.gradle.native=false\"
            export PATH=/opt/jdk-17/bin:/opt/gradle-8.9/bin:\$PATH
            export MIRAGE_KEYSTORE_PATH=\"${MIRAGE_KEYSTORE_PATH:-}\"
            export MIRAGE_KEYSTORE_PASSWORD=\"${MIRAGE_KEYSTORE_PASSWORD:-}\"
            export MIRAGE_KEY_ALIAS=\"${MIRAGE_KEY_ALIAS:-}\"
            export MIRAGE_KEY_PASSWORD=\"${MIRAGE_KEY_PASSWORD:-}\"
            cd /workspace
            gradle clean $gradle_task -PbuildTime=\"$build_date\" -PbuildTag=\"$build_tag\" -PversionCode=$git_count -PversionName=\"$ver\" --no-daemon 2>&1 > /tmp/gradle_err.log || (cat /tmp/gradle_err.log | grep -B2 -A6 -iE \"e: file|error:|unresolved\" | head -40)
            cp app/build/outputs/apk/$VARIANT/$apk_name /output/latest-build.apk 2>/dev/null || true
        " 2>&1 | tail -25

    local raw_apk="$HERE/.build/out/latest-build.apk"
    if [[ -f "$raw_apk" ]]; then
        # 变体写进文件名: debug 包带 debuggable 标志与调试签名, 绝不能被误当成分发包
        local versioned_apk="$HERE/.build/out/mirage-v${ver}-${VARIANT}-${timestamp}.apk"
        local latest_apk="$HERE/.build/out/mirage-v${ver}-${VARIANT}.apk"
        local default_apk="$HERE/.build/out/app-${VARIANT}.apk"

        # 保留带时间戳与版本号的历史存档，不覆盖之前版本
        cp "$raw_apk" "$versioned_apk"
        cp "$raw_apk" "$latest_apk"
        mv "$raw_apk" "$default_apk"

        echo ""
        echo "  ✓ 本次编译产物: $versioned_apk ($(du -h "$versioned_apk" | cut -f1))"
        echo "  ✓ 最新指向软链: $default_apk"
        echo ""
        echo "==> [.build/out/] 历史已归档的所有版本 APK 列表:"
        ls -lh "$HERE/.build/out"/*.apk 2>/dev/null | awk '{print "    " $NF " (" $5 ", " $6 " " $7 " " $8 ")"}'
        echo ""
    else
        echo "!! APK 未生成 (见上方日志)" >&2
        exit 1
    fi
}

case "$cmd" in
    native) build_native ;;
    apk) build_apk ;;
    all) build_native; build_apk ;;
    *) echo "用法: $0 {all|native|apk}"; exit 1 ;;
esac
