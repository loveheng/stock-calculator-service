#!/usr/bin/env bash
# Cloud Run data 副本部署（runbook: docs/deploy/cloud-run-data.md）
# 凭据全部取自项目根 .env（与主机 1 共用同一套）；4 个 Secret Manager secret
# 由本脚本自动创建/更新/授权，服务账号缺失时自动补建。
# 前提（一次性，runbook §2）：gcloud 安装、auth login、启用 run/secretmanager API。
#
# toolbox-script
# format: v1
# name: deploy-cloud-run
# summary: Cloud Run data 副本一键部署（secrets 同步 + 服务部署）
# trigger: manual
# platform: unix

set -euo pipefail

# ---- 部署配置（唯一必填：PROJECT_ID）----
PROJECT_ID="project-56325d20-bc30-4bb7-a73"
REGION="asia-east1"          # us-central1 更便宜；asia-east1 离大陆近
SERVICE="scs-data"
GHCR_IMAGE="ghcr.io/loveheng/stock-calculator-service-data"
IMAGE_TAG="8ac8b1f"
# 主机 1 公网 IP。注意：.env 的 RABBIT_HOST=lavinmq 只是主机 1 内部 Docker DNS，
# Cloud Run 上必须用公网地址；动态 IP 变动后改这里重跑。
RABBIT_PUBLIC_HOST="35.208.158.36"
RABBIT_PORT="5672"
ENV_FILE="$(cd "$(dirname "$0")/../.." && pwd)/.env"

envget() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r' || true; }

usage() {
  cat <<'EOF'
用法: bash scripts/agent-tools/deploy-cloud-run.sh [镜像tag，默认 8ac8b1f]
      bash scripts/agent-tools/deploy-cloud-run.sh --json    快速预检（不部署）
      bash scripts/agent-tools/deploy-cloud-run.sh --help
前提（一次性，runbook §2）：gcloud 安装、auth login、启用 run/secretmanager API
EOF
}

preflight() {
  [ -f "$ENV_FILE" ] || { MSG="缺 .env：$ENV_FILE"; return 1; }
  command -v gcloud >/dev/null 2>&1 || { MSG="gcloud 不在 PATH"; return 1; }
  missing=""
  [ -n "$(envget RABBIT_USER)" ]            || missing="$missing RABBIT_USER"
  [ -n "$(envget RABBIT_PASS)" ]            || missing="$missing RABBIT_PASS"
  [ -n "$(envget CLOUDFLARE_ACCOUNT_ID)" ]  || missing="$missing CLOUDFLARE_ACCOUNT_ID"
  [ -n "$(envget CLOUDFLARE_API_TOKEN)" ]   || missing="$missing CLOUDFLARE_API_TOKEN"
  [ -n "$(envget LLM_BASE_URL)" ]           || missing="$missing LLM_BASE_URL"
  [ -n "$(envget LLM_API_KEY)" ]            || missing="$missing LLM_API_KEY"
  [ -n "$(envget LLM_MODEL)" ]              || missing="$missing LLM_MODEL"
  case "$missing" in
    "") MSG="预检通过：.env 键齐全、gcloud 就绪（未执行部署本体）"
        return 0
        ;;
    *)  MSG=".env 缺少键：$missing"
        return 1
        ;;
  esac
}

put_secret() {
  if gcloud secrets describe "$1" --project "$PROJECT_ID" > /dev/null 2>&1; then
    cur=$(gcloud secrets versions access latest --secret "$1" --project "$PROJECT_ID" 2>/dev/null || printf 'unreadable')
    if [ "$cur" = "$2" ]; then
      echo "== secret $1 无变化，跳过"
    else
      printf '%s' "$2" | gcloud secrets versions add "$1" --data-file=- --project "$PROJECT_ID" --quiet
      echo "== secret $1 值有变化，已加新版本"
    fi
  else
    printf '%s' "$2" | gcloud secrets create "$1" --data-file=- --replication-policy=automatic --project "$PROJECT_ID"
    gcloud secrets add-iam-policy-binding "$1" \
      --member "serviceAccount:$RUN_SA" \
      --role roles/secretmanager.secretAccessor --project "$PROJECT_ID" --quiet
    echo "== secret $1 已创建并授权 scs-data-run"
  fi
}

main() {
  case "$PROJECT_ID" in my-gcp-project) echo "先填配置块 PROJECT_ID" && exit 1 ;; esac
  [ -f "$ENV_FILE" ] || { echo "缺 .env：$ENV_FILE" && exit 1; }
  if [ "$#" -gt 0 ] && [ -n "$1" ]; then IMAGE_TAG="$1"; fi

  # ---- 读 .env（grep 取键，cut 取到行尾以兼容含 = 的值；去 \r 防 CRLF；无匹配返回空不报错）----
  RABBIT_USER=$(envget RABBIT_USER)
  RABBIT_PASS=$(envget RABBIT_PASS)
  CLOUDFLARE_ACCOUNT_ID=$(envget CLOUDFLARE_ACCOUNT_ID)
  CLOUDFLARE_API_TOKEN=$(envget CLOUDFLARE_API_TOKEN)
  LLM_BASE_URL=$(envget LLM_BASE_URL)
  LLM_API_KEY=$(envget LLM_API_KEY)
  LLM_MODEL=$(envget LLM_MODEL)

  # INGEST_SECRET 缺失时：优先恢复 .env 中注释掉的非空旧值（发送方不受影响），否则生成
  INGEST_SECRET=$(envget INGEST_SECRET)
  if [ -z "$INGEST_SECRET" ]; then
    if grep -qE '^#[[:space:]]*INGEST_SECRET=.+' "$ENV_FILE"; then
      sed -i 's/^#[[:space:]]*INGEST_SECRET=/INGEST_SECRET=/' "$ENV_FILE"
      INGEST_SECRET=$(envget INGEST_SECRET)
      echo "== .env 中注释掉的 INGEST_SECRET 已恢复启用（沿用旧值）"
    else
      INGEST_SECRET=$(openssl rand -hex 32 2>/dev/null) || INGEST_SECRET=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
      printf 'INGEST_SECRET=%s\n' "$INGEST_SECRET" >> "$ENV_FILE"
      echo "== .env 缺 INGEST_SECRET，已生成并追加（webhook 发送方需用同一值）"
    fi
  fi

  missing=""
  [ -n "$RABBIT_USER" ]            || missing="$missing RABBIT_USER"
  [ -n "$RABBIT_PASS" ]            || missing="$missing RABBIT_PASS"
  [ -n "$CLOUDFLARE_ACCOUNT_ID" ]  || missing="$missing CLOUDFLARE_ACCOUNT_ID"
  [ -n "$CLOUDFLARE_API_TOKEN" ]   || missing="$missing CLOUDFLARE_API_TOKEN"
  [ -n "$LLM_BASE_URL" ]           || missing="$missing LLM_BASE_URL"
  [ -n "$LLM_API_KEY" ]            || missing="$missing LLM_API_KEY"
  [ -n "$LLM_MODEL" ]              || missing="$missing LLM_MODEL"
  case "$missing" in "") ;; *) echo ".env 缺少键：$missing" && exit 1 ;; esac

  # ---- 0) 服务账号（缺则建；secretAccessor 授权在下方建 secret 时逐个授）----
  RUN_SA="scs-data-run@$PROJECT_ID.iam.gserviceaccount.com"
  if ! gcloud iam service-accounts describe "$RUN_SA" --project "$PROJECT_ID" > /dev/null 2>&1; then
    echo "== 创建服务账号 scs-data-run"
    gcloud iam service-accounts create scs-data-run \
      --display-name "scs data cloud-run runtime" --project "$PROJECT_ID"
  fi

  # ---- 1) Secrets：缺则创建+授权，值变则加新版本，值同则跳过 ----
  echo "== 同步 Secret Manager（4 个，来源 .env）"
  put_secret scs-data-rabbit-pass   "$RABBIT_PASS"
  put_secret scs-data-cf-api-token  "$CLOUDFLARE_API_TOKEN"
  put_secret scs-data-llm-api-key   "$LLM_API_KEY"
  put_secret scs-data-ingest-secret "$INGEST_SECRET"

  # ---- 2) 部署 Cloud Run（常驻副本，AMQP 后台消费者硬要求）----
  echo "== 部署 $SERVICE（$GHCR_IMAGE:$IMAGE_TAG，$REGION）"
  gcloud run deploy "$SERVICE" \
    --project "$PROJECT_ID" \
    --region "$REGION" \
    --image "$GHCR_IMAGE:$IMAGE_TAG" \
    --service-account "$RUN_SA" \
    --cpu 1 --memory 1Gi \
    --no-cpu-throttling \
    --min-instances 2 --max-instances 2 \
    --port 8080 \
    --allow-unauthenticated \
    --set-env-vars "RABBIT_HOST=$RABBIT_PUBLIC_HOST,RABBIT_PORT=$RABBIT_PORT,RABBIT_USER=$RABBIT_USER,CLOUDFLARE_ACCOUNT_ID=$CLOUDFLARE_ACCOUNT_ID,LLM_BASE_URL=$LLM_BASE_URL,LLM_MODEL=$LLM_MODEL" \
    --set-secrets "RABBIT_PASS=scs-data-rabbit-pass:latest,CLOUDFLARE_API_TOKEN=scs-data-cf-api-token:latest,LLM_API_KEY=scs-data-llm-api-key:latest,INGEST_SECRET=scs-data-ingest-secret:latest"

  echo
  echo "== 服务地址（ingest webhook 收口）："
  gcloud run services describe "$SERVICE" --project "$PROJECT_ID" --region "$REGION" \
    --format 'value(status.url)'
  echo "== 验收清单见 runbook §6"
}

MSG=""
case "${1-}" in
  --help|-h)
    usage
    ;;
  --json)
    if preflight; then
      printf '{"status":"OK","severity":"info","message":"%s"}\n' "$MSG"
      exit 0
    fi
    printf '{"status":"FAIL","severity":"warn","message":"%s"}\n' "$MSG"
    exit 1
    ;;
  "")
    main
    ;;
  *)
    main "$@"
    ;;
esac
