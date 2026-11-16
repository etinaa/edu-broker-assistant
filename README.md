# stage1-broker-assistant

Итоговый проект этапа 1: ассистент брокерского счёта на Spring Boot. Задания и сценарии —
[`docs/stage1-final-README.md`](../docs/stage1-final-README.md).

## Запуск

```bash
# Ollama (по умолчанию)
mvn spring-boot:run

# Claude
LLM_PROVIDER=claude ANTHROPIC_API_KEY=... mvn spring-boot:run

mvn test
```

В IDEA: открыть `pom.xml` как проект (или добавить модуль), SDK — Java 25, запустить
`BrokerAssistantApplication`. Переменные окружения — в Run Configuration. Ручные запросы — `requests.http`.

## Что уже есть в скелете

| Пакет | Готово | Твоё задание |
|---|---|---|
| `config` | `LlmProperties`, `AgentProperties`, выбор провайдера в `LlmConfig` | 2 — проверить переключение |
| `llm` | клиенты Claude и Ollama, `ToolConversation` с сессией (`addUserMessage`) — перенесено из `stage1-llm-basics` | — |
| `domain` | records `Account`, `Security`, `Order`, `SearchResult`, `OrderDecision` (sealed) | — |
| `broker` | данные (`DemoData`), хранилища в памяти, `quote`, `order` | **1** — `findSecurity`, `placeOrder`, `confirm`, `reject` + тесты |
| `agent` | `ToolLoop`, `GroundedExecutor`, определения инструментов, системный промпт, сессии, трассы | **3, 5** — выполнение инструментов; **4** — `AssistantService.chat`; **7** — подключить grounding |
| `web` | все эндпоинты, DTO, ProblemDetail-ошибки | — |

Невыполненные части бросают `UnsupportedOperationException("Задание N: …")` — API отвечает `501` с этим
текстом, так что сразу видно, что осталось.

## Что убрано при переносе из `stage1-llm-basics`

Streaming, structured outputs через `output_config`, `count_tokens`, управление моделями Ollama и печать
сырых ответов в консоль (теперь — `logging.level.ai.lab.broker.llm: DEBUG`). Streaming вернётся в задании 10.
`ContextGuard` не перенесён — лимит истории сессии решается в задании 4.
