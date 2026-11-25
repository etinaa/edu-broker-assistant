# Experiments

One experiment per [task](../README.md#tasks) of the project: the description is
in `NN-*.md`, and `NN-*.sh` runs it from any directory.

| # | Task | Description | Script | Model needed |
|---|---|---|---|---|
| 1 | Skeleton and domain without an LLM | [01-domain-without-llm.md](01-domain-without-llm.md) | `01-domain-without-llm.sh` | none |
| 2 | Provider through configuration | [02-provider-switch.md](02-provider-switch.md) | `02-provider-switch.sh` | both |
| 3 | Read tools and search | [03-read-tools-search.md](03-read-tools-search.md) | `03-read-tools-search.sh` | both |
| 4 | `/chat`, session and loop | [04-chat-session-loop.md](04-chat-session-loop.md) | `04-chat-session-loop.sh` | one |
| 5 | `place_order` and refusals | [05-place-order-refusals.md](05-place-order-refusals.md) | `05-place-order-refusals.sh` | both |
| 6 | Confirmation as a separate action | [06-confirmation.md](06-confirmation.md) | `06-confirmation.sh` | one |
| 7 | Attempts to bypass confirmation | [07-bypass-confirmation.md](07-bypass-confirmation.md) | `07-bypass-confirmation.sh` | both |
| 8 | Deterministic agent tests | [08-deterministic-agent-tests.md](08-deterministic-agent-tests.md) | `08-deterministic-agent-tests.sh` | none |
| 9 | Running the set and comparing models | [09-model-comparison.md](09-model-comparison.md) | `09-model-comparison.sh` | both |
| 10 | Optional extras: broker fee, "did you mean", SSE | [10-optional-extras.md](10-optional-extras.md) | `10-optional-extras.sh` | both |

## Requirements

`java` 25+, `mvn`, `curl`, `jq`, `perl`. Live runs need Ollama with a pulled model and/or `ANTHROPIC_API_KEY`
(see [`SETUP.md`](../SETUP.md)). Settings come from `.env`; an environment variable wins over the value in `.env`.
An unavailable provider, or an Ollama model that is not pulled, is skipped with a note in the report.

## How a run works

- A script first runs its related tests (`mvn test -Dtest=...`), then builds the jar and starts the application
  on port `EXP_PORT` (default 18080, so it does not clash with one already running on 8080) with the variables it
  needs. The application is stopped after the run.
- The data lives in memory, so a clean state means a restart: every script and every model start anew, and every
  question goes to a new session.
- Results go to `experiments/results/<experiment>-<time>/`: `report.md` (checklist, exchanges, tables),
  `chats.jsonl` (raw `/chat` responses and traces), the application and `mvn test` logs. The directory is in
  `.gitignore`: logs and traces hold account data.
- Exit code 0 means all checks passed, 1 means some failed (they show as `FAIL` in `report.md`).
- The questions are in `lib/questions.sh`.

## Latest run (2026-11-25, claude-haiku-4-5 and ollama/qwen3.5:9b)

All ten scripts pass. One application fix came out of the runs: on "How many Training do I have?" the local
model answered from `get_account_summary` without searching, because that tool's description offered it for
"how many X do I have". The tool descriptions now send any security named by name through `find_security` first
(commit `3519032`); see experiments 3 and 9.

## Common variables

| Variable | Default | What it does |
|---|---|---|
| `PROVIDERS` | `claude ollama` | providers for the experiments on both models |
| `PROVIDER` | `LLM_PROVIDER` from `.env`, otherwise `ollama` | provider for the experiments on one model (4, 6) |
| `OLLAMA_MODEL` | `qwen2.5:14b` | the Ollama model; it must be pulled |
| `EXP_PORT` | `18080` | application port |
| `SKIP_TESTS=1` | — | do not run `mvn test` |
| `SKIP_LIVE=1` | — | tests only, no application and no model |
| `SKIP_BUILD=1` | — | do not rebuild the jar if it exists |
| `EXP_CHAT_TIMEOUT` | `300` | timeout of one request, s |

Shared code: `lib/common.sh` (starting the application, HTTP, the report) and `lib/questions.sh` (the 14 questions
with their checks).

```bash
experiments/08-deterministic-agent-tests.sh                    # fast, no model
OLLAMA_MODEL=qwen3.5:9b PROVIDER=ollama experiments/06-confirmation.sh
PROVIDERS=claude experiments/09-model-comparison.sh            # the whole set on Claude
```
