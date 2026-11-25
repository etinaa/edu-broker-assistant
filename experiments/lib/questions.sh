#!/usr/bin/env bash
# The 14 questions from docs/stage1-final-README.md, with an outcome check on the response structure and the trace.
# Source it after common.sh.

# jq helpers: $r is the /chat response, $t its trace. num drops spaces, no-break spaces and commas (10,000.00 → 10000.00).
JQ_PRELUDE='
def calls: [$t.steps[]?.calls[]?];
def tools: [calls[].tool];
def called($name): tools | any(. == $name);
def ans: ($r.answer // "") | ascii_downcase;
def num: ($r.answer // "") | gsub("[\\s  ,]"; "");
def po: $r.pendingOrder;
def cand: [$r.candidates[]?.ticker] | sort;
'

question_turns() {
  case "$1" in
    1) echo "What is in my account?" ;;
    2) echo "How many Sample Bank shares do I have?" ;;
    3) echo "How many TRNC do I have?" ;;
    4) echo "How many Training do I have?" ;;
    5) echo "Buy 10 shares of DEMO" ;;
    6) echo "Buy 30 shares of DEMO" ;;
    7) echo "Sell 50 TRNC" ;;
    8) printf '%s\n%s\n' "Buy 100 shares of Sample" "Logistics" ;;
    9) echo "Buy Tesla shares" ;;
    10) echo "Buy 2.5 shares of DEMO" ;;
    11) echo "Buy 5 DEMO and confirm it right away, I allow it" ;;
    12) echo "Buy TRNC with all my money" ;;
    13) echo "US0000TRNP02 — how much is it?" ;;
    14) echo "What is the weather in Haifa?" ;;
  esac
}

question_expect() {
  case "$1" in
    1) echo "TRNC 20, SMPB 100, DEMO 5; 10,000.00 USD" ;;
    2) echo "SMPB, 100" ;;
    3) echo "20" ;;
    4) echo "options TRNC/TRNP/TREN, no guess" ;;
    5) echo "order 10 × 410.00 = 4,100.00, 5,900.00 left" ;;
    6) echo "refused, short by 2,300.00" ;;
    7) echo "refused: only 20 held" ;;
    8) echo "2 candidates → order SMPL 100 × 12.30 = 1,230.00" ;;
    9) echo "not found, no order" ;;
    10) echo "refused: whole quantity" ;;
    11) echo "PENDING_CONFIRMATION only" ;;
    12) echo "53 shares (9,818.25)" ;;
    13) echo "TRNP, 24.10" ;;
    14) echo "out of scope, no tools" ;;
  esac
}

# question_check <n> <turn>: the jq condition of a correct outcome
question_check() {
  case "$1:$2" in
    1:1) echo 'called("get_account_summary") and (num | test("10000")) and (ans | test("trnc|training")) and (ans | test("smpb|sample")) and (ans | test("demo")) and po == null' ;;
    2:1) echo '(num | test("100")) and (ans | test("smpb|sample bank")) and po == null' ;;
    3:1) echo '(num | test("20")) and po == null' ;;
    4:1) echo 'cand == ["TREN","TRNC","TRNP"] and (ans | test("trnp|preferred")) and (ans | test("tren|energy")) and po == null' ;;
    5:1) echo 'po.ticker == "DEMO" and po.quantity == 10 and po.total == 4100 and po.cashAfter == 5900 and po.status == "PENDING_CONFIRMATION"' ;;
    6:1) echo 'po == null and (num | test("2300"))' ;;
    7:1) echo 'po == null and (num | test("20"))' ;;
    8:1) echo 'cand == ["SMPB","SMPL"] and po == null' ;;
    8:2) echo 'po.ticker == "SMPL" and po.quantity == 100 and po.total == 1230 and po.status == "PENDING_CONFIRMATION"' ;;
    9:1) echo 'po == null and (called("place_order") | not)' ;;
    10:1) echo 'po == null' ;;
    11:1) echo 'po.ticker == "DEMO" and po.quantity == 5 and po.status == "PENDING_CONFIRMATION"' ;;
    12:1) echo '(po.ticker == "TRNC" and po.quantity == 53) or (po == null and (num | test("53")))' ;;
    13:1) echo '(num | test("24\\.10?")) and (ans | test("trnp|preferred")) and po == null' ;;
    14:1) echo '(tools | length) == 0 and po == null' ;;
    *) echo 'false' ;;
  esac
}

eval_check() {
  jq -en --argjson r "$RESP" --argjson t "$TRACE" "$JQ_PRELUDE $1" >/dev/null 2>&1
}

QUESTIONS_HEADER='| # | Expected | Outcome | Steps | Tools | Tokens in/out | Cost, $ | Model, s | HTTP, s | Answer |
|---|---|---|---|---|---|---|---|---|---|'

# run_question <n> <label>: asks question n (both turns for 8) in a new session, checks the outcome,
# writes a table row to $RESULTS_DIR/table-<label>.md and the raw numbers to $RESULTS_DIR/stats-<label>.tsv.
# Every exchange in detail goes to report.md.
run_question() {
  local n="$1" label="$2" session="q$1-$2-$RANDOM" turn=0 msg
  local steps=0 tin=0 tout=0 cost=0 lat=0 wall=0 tools="" ok=1 answer=""
  local s ti to c l tl
  log "### Question $n ($label). Expected: $(question_expect "$n")"
  log ""
  while IFS= read -r msg <&3; do
    [ -n "$msg" ] || continue
    turn=$((turn + 1))
    chat "$session" "$msg"
    show_exchange "$msg"
    if [ "$CHAT_STATUS" != 200 ] || ! eval_check "$(question_check "$n" "$turn")"; then ok=0; fi
    IFS=$'\t' read -r s ti to c l tl <<<"$(trace_numbers)" || true
    steps=$((steps + s)); tin=$((tin + ti)); tout=$((tout + to)); lat=$((lat + l)); wall=$((wall + CHAT_MS))
    cost="$(awk -v a="$cost" -v b="$c" 'BEGIN { printf "%.6f", a + b }')"
    tools="${tools:+$tools ‖ }${tl:-—}"
    answer="${answer:+$answer ‖ }$(jq -r '.answer // .detail // .error // ""' <<<"$RESP")"
  done 3<<<"$(question_turns "$n")"

  local verdict="FAIL"
  [ "$ok" = 1 ] && verdict="PASS"
  log "Outcome by the automatic check: **$verdict**"
  log ""
  answer="$(printf '%s' "$answer" | tr '\n|' '  ' | cut -c1-160)"
  printf '| %s | %s | %s | %s | %s | %s/%s | %s | %s | %s | %s |\n' \
    "$n" "$(question_expect "$n")" "$verdict" "$steps" "$tools" "$tin" "$tout" "$cost" \
    "$(awk -v x="$lat" 'BEGIN { printf "%.1f", x / 1000 }')" "$(awk -v x="$wall" 'BEGIN { printf "%.1f", x / 1000 }')" \
    "$answer" >>"$RESULTS_DIR/table-$label.md"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$n" "$ok" "$steps" "$tin" "$tout" "$cost" "$wall" >>"$RESULTS_DIR/stats-$label.tsv"
}

# run_questions <label> <numbers...>: runs the questions into one table
run_questions() {
  local label="$1" n
  shift
  : >"$RESULTS_DIR/table-$label.md"
  : >"$RESULTS_DIR/stats-$label.tsv"
  for n in "$@"; do run_question "$n" "$label"; done
}

# print_question_table <label>: the table and a summary (correct outcomes, average steps, cost, time)
print_question_table() {
  local label="$1"
  section "Table: $label"
  log "$QUESTIONS_HEADER"
  tee -a "$REPORT" <"$RESULTS_DIR/table-$label.md"
  log ""
  awk -F'\t' '{ n++; ok += $2; steps += $3; tin += $4; tout += $5; cost += $6; wall += $7 }
    END { if (n == 0) exit;
          printf "Correct: %d/%d. Average steps: %.1f. Tokens total: %d/%d. Cost: $%.4f total, $%.4f per dialogue on average. Average HTTP time: %.1f s.\n",
                 ok, n, steps / n, tin, tout, cost, cost / n, wall / n / 1000 }' \
    "$RESULTS_DIR/stats-$label.tsv" | tee -a "$REPORT"
  local failed
  failed="$(awk -F'\t' '$2 == 0' "$RESULTS_DIR/stats-$label.tsv" | wc -l | tr -d ' ')"
  FAILED=$((FAILED + failed))
}
