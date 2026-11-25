# Getting ready to run

How to bring up `edu-broker-assistant` on a clean machine: what to install, which variables and properties
to fill in, and how to check that everything works. What the project does is described in
[`docs/stage1-final-README.md`](../docs/stage1-final-README.md).

## 1. What to install

| What | Version | Why | Check |
|---|---|---|---|
| JDK | 25 or newer | `java.version` in `pom.xml` is 25 | `java -version` |
| Maven | 3.9 or newer | build, tests, `spring-boot:run` | `mvn -v` |
| Ollama | recent | local model, the default provider | `ollama --version` |
| An Ollama model | with tool support | `qwen2.5:14b` by default | `ollama list` |
| Anthropic API key | — | only for the `claude` provider | — |

Maven downloads the other dependencies (Spring Boot 4.0, Jackson, Lombok) on the first build.

For Claude you need an API key from [console.anthropic.com](https://console.anthropic.com); Ollama is then not
needed.

## 2. Installing Ollama

Skip this section if you only use Claude.

**macOS**

```bash
brew install ollama
brew services start ollama      # runs the server in the background, also after a reboot
```

Or download the app from [ollama.com/download](https://ollama.com/download), move it to Applications and start
it: the server runs while the app is in the menu bar.

**Linux**

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

The script installs Ollama as a systemd service and starts it. Check it with `systemctl status ollama`.

**Windows**

Download and run the installer from [ollama.com/download](https://ollama.com/download). Ollama starts in the
background and shows an icon in the system tray.

**Without a service** (any OS), start the server in a separate terminal and keep it open:

```bash
ollama serve
```

**Check that the server is up.** It must listen on `http://localhost:11434`, the address in `application.yml`:

```bash
curl -s localhost:11434/api/version     # {"version":"..."}
```

**Pull a model** once (several GB, takes a while):

```bash
ollama pull qwen2.5:14b        # the default model from application.yml
# or a smaller model with tool support, for example:
ollama pull qwen3.5:9b
```

The model must support tool calls: `ollama show <model>` must list `tools` under Capabilities. A 14B model
needs about 10 GB of free memory; on a machine with less, take a 7–9B model and set `OLLAMA_MODEL`.

```bash
ollama list                     # the pulled models
ollama show qwen2.5:14b         # Capabilities: completion, tools
```

## 3. Launch settings in `.env`

The code does not change: the provider and the models are chosen with environment variables. Keep them in a
`.env` file in the module root. It is in `.gitignore` and never reaches the repository; the template is
`.env.example`:

```bash
cp .env.example .env
```

| Variable | Template value | What it is |
|---|---|---|
| `LLM_PROVIDER` | `ollama` | `ollama` or `claude`; any other value stops the startup |
| `OLLAMA_MODEL` | `qwen2.5:14b` | the Ollama model; must be pulled and support tools |
| `CLAUDE_MODEL` | `claude-haiku-4-5` | the Claude model |
| `ANTHROPIC_API_KEY` | empty | **required** with `LLM_PROVIDER=claude`, otherwise the application does not start |

All variables are optional except the key for Claude: without them the defaults from `application.yml` apply
(Ollama with `qwen2.5:14b`).

Spring does not read `.env` by itself: load it into the environment before starting (see section 5).

## 4. Properties in `application.yml`

`src/main/resources/application.yml` already holds working values; you do not need to change it for the first
run. What can be tuned:

**`llm` — provider and models**

| Property | Value | What it is |
|---|---|---|
| `llm.provider` | `${LLM_PROVIDER:ollama}` | `ollama` or `claude`; any other value fails at startup |
| `llm.claude.model` | `${CLAUDE_MODEL:claude-haiku-4-5}` | the Claude model |
| `llm.claude.api-key` | `${ANTHROPIC_API_KEY:}` | the key; never write it into the file, only through the variable |
| `llm.claude.max-tokens` | `1024` | the limit on the model's reply length |
| `llm.claude.input-price-per-million` / `output-price-per-million` | `1.00` / `5.00` | USD per million tokens, for the cost in traces; check them against Anthropic's pricing for the chosen model |
| `llm.ollama.base-url` | `http://localhost:11434` | the Ollama address |
| `llm.ollama.model` | `${OLLAMA_MODEL:qwen2.5:14b}` | the Ollama model |
| `llm.ollama.num-ctx` | `16384` | the context window: the session history and the tool definitions must fit in it |

**`agent` — the agent loop and sessions**

| Property | Value | What it is |
|---|---|---|
| `agent.max-steps` | `6` | at most this many model requests per message; must be > 0 |
| `agent.temperature` | `0.0` | the model temperature |
| `agent.max-history-messages` | `40` | the session history length; the oldest exchanges are dropped whole |
| `agent.max-sessions` | `1000` | sessions kept in memory; the least recently used one is evicted |
| `agent.max-sessions-per-account` | `20` | sessions one account keeps; its own least recently used one is evicted, never another account's or a busy one |
| `agent.max-traces` | `1000` | traces kept in memory; the oldest one is evicted |
| `agent.session-busy-timeout` | `2s` | how long a message waits for another message of the same session before 409 |

**`broker` — order rules**

| Property | Value | What it is |
|---|---|---|
| `broker.order-ttl` | `5m` | an order not confirmed within this time becomes `EXPIRED` |
| `broker.max-price-move-percent` | `2` | how far the price may move against the client before confirm; more is a `PRICE_CHANGED` refusal |
| `broker.max-orders` | `10000` | orders kept in memory; above it the oldest finished or expired ones are removed, pending ones are kept |

Debugging: `logging.level.llm-payload: DEBUG` prints the raw requests to and responses from the models. They contain the whole conversation and the client's cash and positions, so enable it only locally.

Any property can be overridden without editing the file: with an argument,
`mvn spring-boot:run -Dspring-boot.run.arguments=--agent.max-steps=4`, or with an environment variable (also
in `.env`) whose name has dots replaced by `_` and dashes removed: `AGENT_MAXSTEPS=4`, `BROKER_ORDERTTL=10m`.

## 5. Running

From the `edu-broker-assistant` module directory:

```bash
set -a; . ./.env; set +a        # load the settings from .env into the environment
mvn spring-boot:run
```

Without `.env` the defaults apply, and a variable can also be set for a single run:

```bash
mvn spring-boot:run                                 # Ollama, qwen2.5:14b
OLLAMA_MODEL=qwen3.5:9b mvn spring-boot:run         # Ollama, another model
LLM_PROVIDER=claude mvn spring-boot:run             # Claude; ANTHROPIC_API_KEY must be in the environment
```

The application listens on `http://localhost:8080`. At startup the log shows the chosen provider:

```
LLM provider: ollama/qwen2.5:14b at http://localhost:11434
LLM provider: claude/claude-haiku-4-5
```

**IntelliJ IDEA:** open `pom.xml` as a project, set the Project SDK to Java 25+ and run
`BrokerAssistantApplication`. Set the variables from `.env` in Run Configuration → Environment variables, or
point the EnvFile plugin at `.env`.

## 6. Checking

The tests need neither Ollama nor a Claude key: a scripted model replaces the provider in them.

```bash
mvn test
```

A live check. Every request carries the `X-Account-Id` header: the server picks the account, the model never
sees it. Demo accounts: `BRK-1` (10,000.00 USD, TRNC 20, SMPB 100, DEMO 5) and `BRK-2` (100.00 USD, SMPL 10).

```bash
# health
curl -s localhost:8080/actuator/health

# the account, without the model
curl -s localhost:8080/accounts/BRK-1 -H 'X-Account-Id: BRK-1'

# a message to the assistant
curl -s localhost:8080/chat -H 'X-Account-Id: BRK-1' -H 'Content-Type: application/json' \
  -d '{"sessionId":"s1","message":"Buy 10 shares of DEMO"}'

# the order from the response (pendingOrder.orderId) is executed only like this:
curl -s -X POST localhost:8080/orders/ORD-1/confirm -H 'X-Account-Id: BRK-1'

# the agent's steps, by traceId from the response
curl -s localhost:8080/traces/TR-1 -H 'X-Account-Id: BRK-1'
```

All endpoints:

| Method | Path | What it does |
|---|---|---|
| `POST` | `/chat` | `{sessionId, message}` → `{answer, pendingOrder, candidates, limitReached, traceId}` |
| `DELETE` | `/chat/{sessionId}` | start the conversation over |
| `GET` | `/accounts/{accountId}` | your own account; another one is 404 |
| `GET` | `/securities/search?query=...` | search for a security without the model |
| `GET` | `/orders/{id}` | an order and its status |
| `POST` | `/orders/{id}/confirm` | execute the order (cash, position, price and lifetime are checked again) |
| `POST` | `/orders/{id}/reject` | reject the order |
| `GET` | `/traces/{id}` | tool calls, tokens, cost, latency |

`sessionId` is up to 64 characters: Latin letters, digits, `-` and `_`. All data lives in memory and is reset
on restart.

## 7. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `llm.provider=claude, but ANTHROPIC_API_KEY is not set` | the key is not in the environment: fill it in `.env` and run `set -a; . ./.env; set +a` before starting, or set it in the Run Configuration |
| `Failed to bind properties under 'llm.provider'` | a typo in `LLM_PROVIDER`: only `ollama` and `claude` are allowed |
| `/chat` answers 502 | the provider is unavailable: Ollama is not running (`curl localhost:11434/api/version`), the model is not pulled (`ollama list`), or the Claude key is wrong or rate-limited; the application log has the details |
| `model "..." not found` in the log | the model from `OLLAMA_MODEL` is not pulled: `ollama pull <model>` |
| The model does not call tools or makes things up | the model has no tool support or is too small; use `qwen2.5:14b`, `qwen3.5:9b` or Claude |
| Ollama takes tens of seconds per answer | normal for a local model (about 10–30 s per message on a laptop) |
| `/chat` answers 409 "still being processed" | the previous message of this session is still being answered; wait for it or use another `sessionId` |
| `/chat` answers 400 | no `X-Account-Id` header, an empty message or an invalid `sessionId` |
| `Port 8080 was already in use` | another instance is running: stop it or start with `SERVER_PORT=8081` |
