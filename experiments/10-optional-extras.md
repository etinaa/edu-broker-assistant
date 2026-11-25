# Experiment 10. Optional extras

**Task 10.** Three independent additions: a broker fee in the order total, "did you mean" hints for a search with a
typo, and `POST /chat/stream`, which sends the progress of a message as server-sent events.

## Questions

1. **Fee.** Do `pendingOrder`, the refusals and the executed order include the fee, and does the model's text
   carry the same numbers? On "Buy TRNC with all my money" the fee changes the answer (52, not 53): does the
   model take it into account, or does the code's refusal correct it again?
2. **Typos.** Does a typo give the user a choice ("Did you mean DEMO?") instead of a guess, and does the user's
   "Yes" then lead to an order?
3. **Streaming.** Do the events come in the right order and end with the same response `/chat` gives? How much
   earlier than the answer does the client see the first event?

## Hypothesis

- The fee is code, so the totals are right on both models; the model may leave the fee out of its text. With a
  2% fee 53 TRNC cost 10,014.62, so a model that divides the cash by the price orders 53, gets the code's refusal
  and then orders 52; that is the same argument for a `max_affordable_quantity` tool as in experiment 9.
- `AmbiguityGuard` and `GroundedExecutor` allow no order in the message that got the suggestions, so neither model
  orders DEMO on "Buy 10 DEMA"; after "Yes" the suggestion of the earlier message counts as seen and the order is
  created.
- The first event comes after the first model step, the response after the last one. On Claude the difference is a
  few seconds; on the local model it is tens of seconds, and that is where streaming matters.

## Setup

- **A. Fee.** The application starts with `broker.fee-percent=2` and `broker.min-fee=1` (`FEE_PERCENT`,
  `MIN_FEE`); without them there is no fee. Four questions, each in a new session, on one application, so the order
  matters: the refusal and the pending orders first, then the DEMO order is confirmed through
  `POST /orders/{id}/confirm` and the account is checked.
- **B. Typos.** A new application without the fee. First `GET /securities/search` without the model, then the
  questions; "Buy 10 DEMA" and "Yes" are two turns of one session.
- **C. Streaming.** The same application. `curl -N` reads `POST /chat/stream`; every line gets the time since the
  request. The same message then goes through `/chat` in another session to compare the order.
  `agent.session-busy-timeout=100ms`, so the second stream of a busy session gets its error at once.
- `agent.max-streams` (503 above the limit) is checked by `ChatStreamsTest` only: a live check would need two
  long streams at once.

## Running

```bash
experiments/10-optional-extras.sh
PROVIDERS=claude experiments/10-optional-extras.sh
FEE_PERCENT=0.1 MIN_FEE=1 PROVIDERS=ollama OLLAMA_MODEL=qwen3.5:9b experiments/10-optional-extras.sh
SKIP_LIVE=1 experiments/10-optional-extras.sh      # tests only
```

## Questions and checks

The numbers are for the default 2% and a 1 USD minimum.

| Part | Message | Automatic check |
|---|---|---|
| A | Buy 30 shares of DEMO | no order, the text has 2,546 (12,300.00 + fee 246.00 − 10,000.00) |
| A | Sell 10 TRNC | `pendingOrder` SELL TRNC 10, fee 37.05, total 1815.45, cashAfter 11815.45 |
| A | Buy TRNC with all my money | `pendingOrder` TRNC 52, total 9825.66, or no order and the text has 52 |
| A | Buy 10 shares of DEMO | `pendingOrder` DEMO 10, fee 82, total 4182, cashAfter 5818; after `/confirm` EXECUTED, `executedFee` 82, `executedTotal` 4182, cash 5,818.00, DEMO 15 |
| B | `GET /securities/search?query=Trainig` / `DEMA` / `Tesla` | NOT_FOUND with suggestions TREN, TRNC, TRNP / DEMO / none |
| B | How many Trainig do I have? | the three Training in `suggestions` or `candidates`, no order |
| B | Buy 10 DEMA → Yes | first no order and the text mentions DEMO; after "Yes" `pendingOrder` DEMO 10 |
| B | Buy Tesla shares | no order, no suggestions |
| C | Buy 10 shares of DEMO (stream) | HTTP 200 `text/event-stream`; only `tool_call` and `tool_result` before one final `response`; every `tool_call` has its `tool_result` in the same order; `tool_call` has only the tool and `tool_result` no result; the tools are those of the trace; `pendingOrder` DEMO 10, 4100 is the same as through `/chat` |
| C | Two streams of one session | the second: HTTP 200 and one `error` event, ProblemDetail 409; the first ends with `response` |
| C | Unknown account / empty `sessionId` | HTTP 404 / 400 before the stream |

For part A the table also shows the quantities the model passed to `place_order` (for example `53 → 52`: the code
refused the first one) and whether the fee from `pendingOrder` appears in the model's text.

## Success criteria

- The tests of all three parts pass.
- Part A: every total is the code's, with the fee; the account after `/confirm` paid the amount and the fee.
- Part B: no order on a guess in the message with the suggestions; an order after "Yes".
- Part C: all the event checks hold on both models; the first event comes earlier than the response.

## Results

Done: `TradeCost` and `broker.fee-percent`/`broker.min-fee`, `TypoSuggestions` with the rules in `AmbiguityGuard`
and `GroundedExecutor`, `POST /chat/stream` (`ChatStreams`, `ChatListener` in `ToolLoop`).

2026-11-25, `broker.fee-percent=2`, `broker.min-fee=1` (after a fix of two checks in the script, rerun on Claude):

| | A. Fee | B. Typos | C. Stream: first event / response / `/chat` |
|---|---|---|---|
| claude-haiku-4-5 | 4/4 | 4/4 | 1.1 s / 2.4 s / 2.9 s |
| ollama/qwen3.5:9b | 4/4 | 4/4 | 9.2 s / 21.6 s / 19.2 s |

- **The code computed the quantity again, through refusals.** On "Buy TRNC with all my money" Claude ordered 53,
  got the code's refusal (10,014.62 with the fee) and then ordered 52; Ollama went 54 → 53 → 52, six tool calls and
  108 s. Both ended at the right 52 only because the code checks the total with the fee. This confirms the case
  for `max_affordable_quantity` or an order "for an amount" from experiment 9.
- **The fee is not always in the text.** All totals in `pendingOrder` and the refusals included the fee on both
  models, and the account after `/confirm` paid 4,182.00. Claude named the fee in every answer; Ollama left it
  out on "Sell 10 TRNC" and on the TRNC purchase. The structure is right either way.
- **No order on a guess.** "Buy 10 DEMA" gave "Did you mean DEMO?" and no order on both models, "Yes" gave the
  order. On "How many Trainig do I have?" Claude searched for "Training" itself and got `CANDIDATES`; Ollama showed
  the suggestions. On "Buy Tesla shares" Ollama searched twice and read the account before answering; no order.
- **Streaming:** the events were in order on both models and the response matched `/chat`. The first event came
  after the first model step: 1.3 s before the response on Claude, 12 s on Ollama. A second stream of a busy
  session got an `error` event with 409; an unknown account and an empty `sessionId` were 404 and 400 before the
  stream.
