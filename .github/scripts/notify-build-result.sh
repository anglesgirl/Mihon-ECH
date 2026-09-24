#!/usr/bin/env bash
# Mihon 构建结果通知：成功/失败都通知，只引用当前运行的数据。
set -uo pipefail

REPO="${GITHUB_REPOSITORY:?}"
RUN_ID="${RUN_ID:?}"
SHA="${HEAD_SHA:?}"
STATUS="${BUILD_STATUS:-unknown}"
TG_TOKEN="${TELEGRAM_BOT_TOKEN:-}"
TG_CHAT="${TELEGRAM_CHAT_ID:-}"
RUN_URL="https://github.com/${REPO}/actions/runs/${RUN_ID}"
SHORT_SHA="${SHA:0:8}"

if [[ -z "$TG_TOKEN" || -z "$TG_CHAT" ]]; then
  echo "缺少 Telegram 通知凭证，跳过通知"
  exit 0
fi

APK_INFO="未生成"
ARTIFACTS=$(gh api "repos/${REPO}/actions/runs/${RUN_ID}/artifacts" --jq '.artifacts[] | select(.expired == false) | [.name, (.size_in_bytes|tostring)] | @tsv' 2>&1 || true)
if [[ -n "$ARTIFACTS" ]]; then
  APK_INFO=$(printf '%s\n' "$ARTIFACTS" | awk -F '\t' '/^arm64-v8a-/{printf "%s（%s 字节）", $1, $2; found=1; exit} END{if (!found) printf "当前运行已有 artifact（未找到 arm64 名称）"}')
fi

FAILED_STEP="无"
if [[ "$STATUS" != "success" ]]; then
  RAW=$(gh run view "$RUN_ID" --repo "$REPO" --json jobs \
    -q '[.jobs[].steps[] | select(.conclusion == "failure") | .name] | join(", ")' 2>&1 || true)
  case "$RAW" in
    *"HTTP "*|*"rror"*|*"not accessible"*) FAILED_STEP="步骤查询失败，请查看 Actions 日志" ;;
    *) FAILED_STEP="${RAW:-未获取到}" ;;
  esac
fi

if [[ "$STATUS" == "success" ]]; then
  TITLE="✅ Mihon 构建成功"
else
  TITLE="❌ Mihon 构建失败"
fi
TEXT=$(printf '%s\n提交：%s\nAPK：%s\n失败步骤：%s\n运行：%s' \
  "$TITLE" "$SHORT_SHA" "$APK_INFO" "$FAILED_STEP" "$RUN_URL")

RESP=$(curl -sS --fail-with-body --max-time 30 -X POST \
  "https://api.telegram.org/bot${TG_TOKEN}/sendMessage" \
  -d "chat_id=${TG_CHAT}" \
  --data-urlencode "text=${TEXT}" 2>&1)
CURL_STATUS=$?
if [[ $CURL_STATUS -ne 0 ]]; then
  echo "Telegram 通知请求失败：${RESP}"
  exit 1
fi
if ! printf '%s' "$RESP" | grep -q '"ok":true'; then
  echo "Telegram 未确认通知送达：${RESP}"
  exit 1
fi
echo "构建结果通知已送达"
