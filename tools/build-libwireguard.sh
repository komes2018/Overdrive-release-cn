#!/usr/bin/env bash
# Reproducibly build the Android/arm64 WireGuard client packaged as libwireguard.so.
#
# Same packaging trick as libtailscale.so: named .so so Android extracts it from
# jniLibs with executable permissions. The source is tools/wireguard (a small
# wrapper around wireguard-go with a gVisor netstack and a loopback SOCKS5
# server); dependency versions are pinned by tools/wireguard/go.sum.
set -euo pipefail

GO_TOOLCHAIN="${GO_TOOLCHAIN:-go1.26.2}"
NDK_VERSION="${NDK_VERSION:-26.1.10909125}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/tools/wireguard"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libwireguard.so"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for command in go upx; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "FATAL: required command not found: $command" >&2
        exit 1
    fi
done

# See build-libtailscale.sh: UPX rejects Go's Android PIE section table unless
# the NDK llvm-strip normalizes it first.
LLVM_STRIP="${LLVM_STRIP:-}"
if [ -z "$LLVM_STRIP" ]; then
    SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [ -z "$SDK_ROOT" ]; then
        echo "FATAL: set ANDROID_SDK_ROOT/ANDROID_HOME or LLVM_STRIP" >&2
        exit 1
    fi
    LLVM_STRIP="$(find \
        "$SDK_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt" \
        -name llvm-strip -print 2>/dev/null | head -1)"
fi
if [ -z "$LLVM_STRIP" ] || [ ! -x "$LLVM_STRIP" ]; then
    echo "FATAL: NDK $NDK_VERSION llvm-strip not found; set LLVM_STRIP explicitly" >&2
    exit 1
fi

GO_VERSION_OUTPUT="$(env GOTOOLCHAIN="$GO_TOOLCHAIN" go version)"
case "$GO_VERSION_OUTPUT" in
    *" $GO_TOOLCHAIN "*) ;;
    *)
        echo "FATAL: expected $GO_TOOLCHAIN, got: $GO_VERSION_OUTPUT" >&2
        exit 1
        ;;
esac

UPX_MAJOR="$(upx --version 2>/dev/null | head -1 | sed -E 's/[^0-9]*([0-9]+).*/\1/')"
if [ -z "$UPX_MAJOR" ] || [ "$UPX_MAJOR" -lt 5 ]; then
    echo "FATAL: need UPX >= 5 after llvm-strip normalization" >&2
    exit 1
fi

VERSION="$(git -C "$ROOT" log -1 --format=%h -- tools/wireguard 2>/dev/null)"
[ -n "$VERSION" ] || VERSION=dev

echo "==> testing (unit + end-to-end tunnel)"
(cd "$SRC" && env GOTOOLCHAIN="$GO_TOOLCHAIN" go test -count=1 ./...)

echo "==> building android/arm64 with $GO_TOOLCHAIN"
(
    cd "$SRC"
    env GOTOOLCHAIN="$GO_TOOLCHAIN" GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
        go build -trimpath -ldflags "-s -w -X main.version=$VERSION" \
        -o "$WORK/libwireguard.so" .
)

echo "==> normalizing ELF with NDK $NDK_VERSION llvm-strip"
"$LLVM_STRIP" --strip-all "$WORK/libwireguard.so"

echo "==> compressing with $(upx --version | head -1)"
upx --best -q "$WORK/libwireguard.so"
upx -t -q "$WORK/libwireguard.so"

install -m 0644 "$WORK/libwireguard.so" "$OUT"
echo "==> wrote $OUT ($(wc -c < "$OUT") bytes)"
shasum -a 256 "$OUT"
