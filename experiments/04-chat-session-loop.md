# Experiment 4. `/chat`, the session, the loop and the trace

**Task 4.** `AssistantService`: history per `sessionId` with a limit, `ToolLoop` with `agent.max-steps`, a trace for
every message, `candidates` from the `find_security` result.

## Questions

1. Does the session keep the context between messages (question 8: "Buy 100 shares of Sample" → "Logistics")?
2. Does the session forget everything after `DELETE /chat/{sessionId}`?
3. Is the answer honest when the steps run out, instead of a cut-off model text?
4. Do two parallel messages of one session stay apart?
5. Is a trace visible only to its own account?

## Hypothesis

The history lives on the server, and old exchanges are dropped whole (`agent.max-history-messages`). On
`limitReached` the code replaces the answer with "could not finish". Messages of one session are serialized by a
lock: the second waits `agent.session-busy-timeout` and gets 409.

## Setup

- Provider: `PROVIDER` (default `LLM_PROVIDER` from `.env`, otherwise ollama).
- Second run: `agent.max-steps=1` (the task needs ≥ 2 steps) and `agent.session-busy-timeout=100ms` (any model
  answer takes longer).

## Running

```bash
experiments/04-chat-session-loop.sh
PROVIDER=claude experiments/04-chat-session-loop.sh
```

## Steps

1. Tests: `AssistantServiceTest`, `SessionStoreTest`, `HistoryTest`, `TraceStoreTest`, `ToolLoopTest`,
   `TruncatedTurnTest`, and the session and limit scenarios of `ChatIntegrationTest`.
2. Question 8 in one session: the first turn → two candidates (SMPB, SMPL), the second → order SMPL 100 × 12.30.
3. "Buy 100 shares of Sample", `DELETE /chat/{id}`, then "Logistics" in the same session: there must be no order.
4. `GET /traces/{id}` with its own account — 200 with the model, steps, tokens and latency; with `BRK-2` — 404.
5. Restart with `max-steps=1`: "Buy 100 shares of Sample Logistics" → `limitReached: true`, no order.
6. Two simultaneous "What is in my account?" to one session → codes 200 and 409.

## Success criteria

Every checklist item in `report.md` is ticked; question 8 is PASS in the table.

## Results (2026-11-25, ollama/qwen3.5:9b)

All checks passed.

- **Question 8:** the candidates SMPB and SMPL, then "Logistics" → an order for 100 SMPL; 4 steps over two
  messages, ~29 s.
- **Reset:** after `DELETE /chat/{id}` (204) "Logistics" gave no order: the history was gone.
- **Trace:** the message, the model and steps with tokens, cost and latency; with account BRK-2 the same trace
  is 404.
- **Step limit** (`agent.max-steps=1`): `limitReached: true`, the answer "I could not finish this request within
  the step limit…", no order.
- **Busy session:** two parallel messages gave 200 and 409.
