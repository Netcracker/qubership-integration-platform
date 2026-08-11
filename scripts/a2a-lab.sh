#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
INFRASTRUCTURE_DIR="$REPOSITORY_DIR/infrastructure"
LAB_ENV_FILE="$REPOSITORY_DIR/tools/a2a-lab/.env"

COMPOSE_ARGS=(
  --project-directory "$INFRASTRUCTURE_DIR"
  --env-file "$LAB_ENV_FILE"
  -f "$INFRASTRUCTURE_DIR/docker-compose.yml"
  -f "$INFRASTRUCTURE_DIR/docker-compose.a2a-lab.yml"
)

if [[ -f "$INFRASTRUCTURE_DIR/docker-compose.env.local.yml" && -f "$INFRASTRUCTURE_DIR/.env.local" ]]; then
  COMPOSE_ARGS+=(-f "$INFRASTRUCTURE_DIR/docker-compose.env.local.yml")
fi

compose() {
  docker compose "${COMPOSE_ARGS[@]}" --profile ai --profile a2a-lab "$@"
}

ensure_env_file() {
  if [[ ! -f "$LAB_ENV_FILE" ]]; then
    cp "$REPOSITORY_DIR/tools/a2a-lab/.a2a-lab.env.example" "$LAB_ENV_FILE"
    echo "Created $LAB_ENV_FILE"
  fi
}

check_url() {
  local name="$1"
  local url="$2"

  if curl --fail --silent --show-error --max-time 5 "$url" >/dev/null; then
    printf '%-18s %s\n' "$name" "ready"
  else
    printf '%-18s %s\n' "$name" "not ready: $url"
    return 1
  fi
}

case "${1:-help}" in
  init)
    ensure_env_file
    echo "qip_top_level_agent needs LLM_API_KEY, LLM_BASE_URL, and LLM_CHAT_MODEL"
    echo "in $INFRASTRUCTURE_DIR/.env.local. Without them, use qip_direct_agent."
    ;;
  build)
    ensure_env_file
    compose build qip-adk-web qip-a2a-inspector
    ;;
  up)
    ensure_env_file
    compose up --detach --build qip-ai-service qip-adk-web qip-a2a-inspector
    echo "ADK Web:      http://localhost:8000"
    echo "A2A Inspector: http://localhost:8088"
    echo "Inspector agent URL inside Compose: http://qip-ai-service:8080"
    ;;
  restart-adk)
    ensure_env_file
    compose up --detach --build --no-deps --force-recreate qip-adk-web
    echo "ADK Web: http://localhost:8000"
    ;;
  check)
    check_url "AI service" "http://localhost:8094/q/health"
    check_url "Agent Card" "http://localhost:8094/.well-known/agent-card.json"
    check_url "ADK Web" "http://localhost:8000"
    check_url "A2A Inspector" "http://localhost:8088"
    ;;
  logs)
    compose logs --follow --tail 200 qip-ai-service qip-adk-web qip-a2a-inspector
    ;;
  stop)
    compose stop qip-adk-web qip-a2a-inspector
    ;;
  config)
    ensure_env_file
    compose config
    ;;
  *)
    echo "Usage: scripts/a2a-lab.sh {init|build|up|restart-adk|check|logs|stop|config}"
    exit 2
    ;;
esac
