#!/usr/bin/env bash
# 下载官方固定版本并核对官方 SHA-256 清单，不进行全局安装。
set -euo pipefail
cd "$(dirname "$0")/.."
version=8.30.1
case "$(uname -s)" in
  Darwin) platform=darwin ;;
  Linux) platform=linux ;;
  *) echo '暂仅支持 macOS/Linux。' >&2; exit 2 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) architecture=arm64 ;;
  x86_64) architecture=x64 ;;
  *) echo '暂仅支持 arm64/x64。' >&2; exit 2 ;;
esac
archive="gitleaks_${version}_${platform}_${architecture}.tar.gz"
base="https://github.com/gitleaks/gitleaks/releases/download/v${version}"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
curl --fail --location --connect-timeout 10 --max-time 120 "$base/$archive" --output "$stage/$archive"
curl --fail --location --connect-timeout 10 --max-time 60 "$base/gitleaks_${version}_checksums.txt" --output "$stage/checksums.txt"
expected="$(awk -v filename="$archive" '$2 == filename {print $1}' "$stage/checksums.txt")"
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo '官方校验清单缺少当前平台。' >&2; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$stage/$archive" | awk '{print $1}')"
else
  actual="$(shasum -a 256 "$stage/$archive" | awk '{print $1}')"
fi
[[ "$actual" == "$expected" ]] || { echo 'Gitleaks 校验失败。' >&2; exit 1; }
tar -xzf "$stage/$archive" -C "$stage" gitleaks
mkdir -p .tools
install -m 755 "$stage/gitleaks" .tools/gitleaks
.tools/gitleaks version
