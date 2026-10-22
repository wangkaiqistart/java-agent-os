#!/usr/bin/env bash
# 本地与 CI 共用的门禁；任何缺失工具或扫描错误均以非零状态退出。
set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-}" in
  quality)
    exec mvn -B clean verify -Pstatic-analysis
    ;;
  dependencies)
    scan_profiles=security
    if [[ -n "${NVD_API_KEY:-}" ]]; then
      scan_profiles=security,nvd-auth
    fi
    if [[ -x "$PWD/.tools/dotnet/dotnet" ]]; then
      export PATH="$PWD/.tools/dotnet:$PATH"
    fi
    exec mvn -B package org.owasp:dependency-check-maven:aggregate -P"$scan_profiles"
    ;;
  secrets)
    if [[ -n "${GITLEAKS_BIN:-}" ]]; then
      scanner="$GITLEAKS_BIN"
    elif [[ -x .tools/gitleaks ]]; then
      scanner="$PWD/.tools/gitleaks"
    elif command -v gitleaks >/dev/null 2>&1; then
      scanner="$(command -v gitleaks)"
    else
      echo '缺少 Gitleaks，请先执行 bash scripts/install-gitleaks.sh。' >&2
      exit 127
    fi
    "$scanner" version
    result=0
    "$scanner" git . --timeout 300 --redact --report-format json --report-path gitleaks-report-history.json || result=$?
    "$scanner" dir . --timeout 300 --redact --report-format json --report-path gitleaks-report-worktree.json || result=$?
    exit "$result"
    ;;
  *)
    echo '用法：bash scripts/check.sh {quality|secrets|dependencies}' >&2
    exit 2
    ;;
esac
