#!/usr/bin/env bash
# Shared helpers of the experiment scripts. Source it: . "$(dirname "$0")/lib/common.sh"
#
# Environment variables (all optional):
#   EXP_PORT          application port during the experiment (default 18080, so it does not clash with one on 8080)
#   EXP_ACCOUNT       account in the X-Account-Id header (default BRK-1)
#   EXP_CHAT_TIMEOUT  timeout of one HTTP request, s (default 300: a local model answers in tens of seconds)
#   PROVIDERS         providers for live runs, space-separated (default "claude ollama")
#   SKIP_BUILD=1      do not rebuild the jar if it already exists
#   SKIP_TESTS=1      skip mvn test in the experiment
#   SKIP_LIVE=1       skip the live part (starting the application and sending requests)
# Provider settings (LLM_PROVIDER, OLLAMA_MODEL, CLAUDE_MODEL, ANTHROPIC_API_KEY) come from .env;
# a variable already set in the environment wins over the value in .env.

set -euo pipefail

EXP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="$(cd "$EXP_DIR/.." && pwd)"
PORT="${EXP_PORT:-18080}"
BASE_URL="http://localhost:$PORT"
ACCOUNT="${EXP_ACCOUNT:-BRK-1}"
CHAT_TIMEOUT="${EXP_CHAT_TIMEOUT:-300}"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"

APP_PID=""
APP_LOG=""
JAR=""
FAILED=0
RESULTS_DIR=""
REPORT=""
HTTP_STATUS=""
HTTP_BODY=""
RESP="null"
TRACE="{}"
CHAT_STATUS=""
CHAT_MS=0

# ---------------------------------------------------------------------------------------------------------------
# Environment and report

# .env is read line by line: KEY=VALUE; comments and blank lines are skipped, variables already set are kept.
load_env() {
  local env_file="$PROJECT_DIR/.env" line key value
  [ -f "$env_file" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in '' | '#'*) continue ;; esac
    key="${line%%=*}"
    value="${line#*=}"
    key="$(printf '%s' "$key" | tr -d '[:space:]')"
    case "$key" in '' | *[!A-Za-z0-9_]*) continue ;; esac
    value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
    if [ -z "${!key+x}" ]; then export "$key=$value"; fi
  done <"$env_file"
}

require_tools() {
  local tool
  for tool in "$@"; do
    command -v "$tool" >/dev/null 2>&1 || { echo "$tool not found: install it and try again." >&2; exit 2; }
  done
}

# init_results <slug> <title>: the results directory and report.md
init_results() {
  RESULTS_DIR="$EXP_DIR/results/$1-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$RESULTS_DIR"
  REPORT="$RESULTS_DIR/report.md"
  printf '# %s\n\nRun: %s\n' "$2" "$(date '+%Y-%m-%d %H:%M:%S')" >"$REPORT"
  echo "Results: $RESULTS_DIR"
}

log() { printf '%s\n' "$*" | tee -a "$REPORT"; }
section() { log ""; log "## $*"; log ""; }

# assert <description> <command...>: a checklist item in the report; FAILED grows on failure
assert() {
  local desc="$1"; shift
  if "$@"; then
    log "- [x] $desc"
  else
    log "- [ ] **FAIL** $desc"
    FAILED=$((FAILED + 1))
  fi
}

# json_true <json> <jq expression>: whether the expression holds for the document
json_true() { jq -e "$2" >/dev/null 2>&1 <<<"$1"; }

finish() {
  log ""
  if [ "$FAILED" -eq 0 ]; then log "Result: all checks passed."; else log "Result: $FAILED checks failed."; fi
  log "Report: $REPORT"
  stop_app
  [ "$FAILED" -eq 0 ]
}

now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time() * 1000'; }

# ---------------------------------------------------------------------------------------------------------------
# Tests

# run_tests <name> <-Dtest pattern>: mvn test for the chosen classes or methods
run_tests() {
  if [ "${SKIP_TESTS:-0}" = 1 ]; then log "Tests \"$1\" skipped (SKIP_TESTS=1)."; return 0; fi
  local out
  out="$RESULTS_DIR/mvn-test-$(printf '%s' "$1" | tr -c 'A-Za-z0-9' '-' | cut -c1-40).log"
  log "Tests \"$1\": \`mvn test -Dtest='$2'\`"
  log ""
  if (cd "$PROJECT_DIR" && mvn -B test -Dtest="$2" -Dsurefire.failIfNoSpecifiedTests=false) >"$out" 2>&1; then
    grep -E 'Tests run: .* in ai\.lab' "$out" | sed -E 's/^\[[A-Z]+\] +/    /' | tee -a "$REPORT" || true
    log ""
    log "- [x] tests passed: $(grep -E '^\[[A-Z]+\] Tests run:' "$out" | tail -1 | sed -E 's/^\[[A-Z]+\] //')"
  else
    grep -E '\[ERROR\]' "$out" | head -20 | sed 's/^/    /' | tee -a "$REPORT" || true
    log "- [ ] **FAIL** tests failed, full log: $out"
    FAILED=$((FAILED + 1))
  fi
}

# ---------------------------------------------------------------------------------------------------------------
# Application

# build_app [project directory]: builds the jar (without tests) and puts its path into JAR
build_app() {
  local dir="${1:-$PROJECT_DIR}"
  if [ "${SKIP_BUILD:-0}" != 1 ] || ! ls "$dir"/target/edu-broker-assistant-*.jar >/dev/null 2>&1; then
    echo "Building the jar in $dir ..."
    (cd "$dir" && mvn -q -B -DskipTests package) || { echo "The build failed" >&2; exit 2; }
  fi
  JAR="$(ls "$dir"/target/edu-broker-assistant-*.jar | head -1)"
}

# start_app <label> [VAR=value ...]: starts the jar with extra variables and waits for /actuator/health.
# Returns 1 if the application exits before it is ready (experiments that expect a startup failure check this).
start_app() {
  local label="$1" i
  shift
  stop_app
  if curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1; then
    echo "Port $PORT is already taken by another application: stop it or set EXP_PORT." >&2
    exit 2
  fi
  APP_LOG="$RESULTS_DIR/app-$label.log"
  env "$@" java -jar "$JAR" --server.port="$PORT" >"$APP_LOG" 2>&1 </dev/null &
  APP_PID=$!
  for i in $(seq 1 180); do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      wait "$APP_PID" 2>/dev/null || true
      APP_PID=""
      return 1
    fi
    if curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1; then
      log "Application \"$label\" started: $(grep -m1 -o 'LLM provider: .*' "$APP_LOG" || echo 'no provider in the log')"
      return 0
    fi
    sleep 0.5
  done
  echo "The application did not come up within 90 s, log: $APP_LOG" >&2
  stop_app
  return 1
}

stop_app() {
  if [ -n "$APP_PID" ]; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
    APP_PID=""
  fi
}
trap stop_app EXIT
trap 'stop_app; exit 130' INT TERM

provider_available() {
  case "$1" in
    claude) [ -n "${ANTHROPIC_API_KEY:-}" ] ;;
    ollama) curl -sf "$OLLAMA_URL/api/version" >/dev/null 2>&1 ;;
    *) return 1 ;;
  esac
}

provider_model() {
  case "$1" in
    claude) echo "${CLAUDE_MODEL:-claude-haiku-4-5}" ;;
    ollama) echo "${OLLAMA_MODEL:-qwen2.5:14b}" ;;
  esac
}

# start_provider <provider> [VAR=value ...]: starts on the provider; 1 if it is unavailable or did not start
start_provider() {
  local provider="$1"
  shift
  if ! provider_available "$provider"; then
    case "$provider" in
      claude) log "Provider claude skipped: no ANTHROPIC_API_KEY (in the environment or .env)." ;;
      ollama) log "Provider ollama skipped: Ollama does not answer at $OLLAMA_URL (ollama serve)." ;;
      *) log "Unknown provider $provider." ;;
    esac
    return 1
  fi
  local model arg
  model="$(provider_model "$provider")"
  for arg in "$@"; do case "$arg" in OLLAMA_MODEL=*) model="${arg#OLLAMA_MODEL=}" ;; esac; done
  if [ "$provider" = ollama ] && ! curl -sf "$OLLAMA_URL/api/tags" \
      | jq -e --arg m "$model" '[.models[].name] | index($m) != null' >/dev/null 2>&1; then
    log "Provider ollama skipped: model $model is not pulled (ollama pull $model, or set OLLAMA_MODEL)."
    return 1
  fi
  start_app "$provider" LLM_PROVIDER="$provider" "$@" || { log "The application did not start on $provider, log: $APP_LOG"; return 1; }
}

# ---------------------------------------------------------------------------------------------------------------
# HTTP

# api <METHOD> <path> [curl args...] → HTTP_STATUS, HTTP_BODY. The account header comes from ACCOUNT (empty: none).
api() {
  local method="$1" path="$2" out
  shift 2
  local args=(-sS --max-time "$CHAT_TIMEOUT" -X "$method" "$BASE_URL$path" -w $'\n%{http_code}')
  if [ -n "$ACCOUNT" ]; then args+=(-H "X-Account-Id: $ACCOUNT"); fi
  if ! out="$(curl "${args[@]}" "$@" 2>&1)"; then
    HTTP_STATUS=000
    HTTP_BODY="$out"
    return 0
  fi
  HTTP_STATUS="${out##*$'\n'}"
  HTTP_BODY="${out%$'\n'*}"
}

# chat <sessionId> <message> → CHAT_STATUS, RESP (the /chat response), TRACE (its trace), CHAT_MS (request time)
chat() {
  local body start tid
  body="$(jq -nc --arg s "$1" --arg m "$2" '{sessionId: $s, message: $m}')"
  start="$(now_ms)"
  api POST /chat -H 'Content-Type: application/json' -d "$body"
  CHAT_MS=$(($(now_ms) - start))
  CHAT_STATUS="$HTTP_STATUS"
  RESP="$HTTP_BODY"
  TRACE="{}"
  if [ "$CHAT_STATUS" = 200 ]; then
    tid="$(jq -r '.traceId // empty' <<<"$RESP" 2>/dev/null || true)"
    if [ -n "$tid" ]; then
      api GET "/traces/$tid"
      if [ "$HTTP_STATUS" = 200 ]; then TRACE="$HTTP_BODY"; fi
    fi
  else
    jq -e . >/dev/null 2>&1 <<<"$RESP" || RESP="$(jq -nc --arg e "$RESP" '{error: $e}')"
  fi
  jq -nc --arg session "$1" --arg message "$2" --arg status "$CHAT_STATUS" --argjson ms "$CHAT_MS" \
    --argjson response "$RESP" --argjson trace "$TRACE" \
    '{session: $session, message: $message, status: $status, ms: $ms, response: $response, trace: $trace}' \
    >>"$RESULTS_DIR/chats.jsonl"
}

# Steps, tokens, cost, model latency and the tool chain of the current TRACE (tab-separated)
trace_numbers() {
  jq -r '[(.steps // [] | length),
          ([.steps[]?.inputTokens] | add // 0),
          ([.steps[]?.outputTokens] | add // 0),
          ([.steps[]?.cost // "0" | tonumber] | add // 0),
          ([.steps[]?.latencyMs] | add // 0),
          ([.steps[]?.calls[]? | .tool + (if .error then "(error)" else "" end)] | join(" → "))]
         | @tsv' <<<"$TRACE"
}

# Prints an exchange: the message, the answer, the response structure, the tool calls
show_exchange() {
  local msg="$1"
  log "**Message:** $msg  "
  log "HTTP $CHAT_STATUS in $((CHAT_MS / 1000)).$(((CHAT_MS % 1000) / 100)) s"
  log ""
  log '```'
  jq -r '"answer: " + (.answer // .error // .detail // "—"),
         "pendingOrder: " + (if .pendingOrder then (.pendingOrder | "\(.orderId) \(.side) \(.ticker) \(.quantity) × \(.price) = \(.total), cashAfter \(.cashAfter), \(.status)") else "—" end),
         "candidates: " + ([.candidates[]?.ticker] | if length > 0 then join(", ") else "—" end),
         "suggestions: " + ([.suggestions[]?.ticker] | if length > 0 then join(", ") else "—" end),
         "limitReached: \(.limitReached // false), traceId: \(.traceId // "—")"' <<<"$RESP" | tee -a "$REPORT"
  jq -r '"trace: model \(.model // "—"), \(.steps // [] | length) steps",
         (.steps[]?.calls[]? | "  \(.tool) \(.arguments) → \(.result | .[0:300])\(if .error then " [error]" else "" end)")' \
    <<<"$TRACE" | tee -a "$REPORT"
  log '```'
  log ""
}

load_env
require_tools curl jq java mvn perl
