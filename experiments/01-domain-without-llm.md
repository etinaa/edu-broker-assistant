# Experiment 1. Skeleton and domain without an LLM

**Task 1.** Everything that decides what happens to money works and is tested without a model.

## Question

Can the broker rules (insufficient funds, selling more than held, quantity, double confirmation, security search)
be checked without a single LLM call?

## Hypothesis

Yes. `BrokerService` computes everything in `BigDecimal` and does not depend on `llm`, so unit tests and the HTTP
endpoints that need no model (`/accounts`, `/securities/search`, `/orders`) give the full picture of the rules.

## Setup

- No model needed: the application starts with `LLM_PROVIDER=ollama` but never contacts Ollama.
- Data: demo account `BRK-1` (10,000.00 USD; TRNC 20, SMPB 100, DEMO 5) and `BRK-2` (100.00 USD; SMPL 10).

## Running

```bash
experiments/01-domain-without-llm.sh
SKIP_LIVE=1 experiments/01-domain-without-llm.sh   # tests only
```

## Steps

1. Domain and rule tests: `BrokerServiceTest`, `AccountTest`, `OrderBookTest`, `SecurityCatalogTest`,
   `AccountRepositoryTest`, `BrokerPropertiesTest`.
2. HTTP layer tests without a model: `AccountControllerTest`, `OrderControllerTest`, `OrderControllerIntegrationTest`.
3. Start the jar and send:
   - `GET /accounts/BRK-1`: the initial account;
   - `GET /accounts/BRK-2` with the `BRK-1` header: 404, another account is not visible;
   - a request without `X-Account-Id`: 400;
   - `GET /securities/search` for `trnc`, `US0000TRNP02`, `Sample Bank`, `  sample   LOGISTICS ltd `, `Training`,
     `Tesla`, `TRN`;
   - `GET` and `confirm` of a missing order: 404.

## Success criteria

| Check | Expected |
|---|---|
| Insufficient funds | `INSUFFICIENT_FUNDS` refusal with needed / available / shortfall |
| Selling more than held | `INSUFFICIENT_POSITION` refusal |
| Quantity 0, −1, 2.5 | `INVALID_QUANTITY` refusal |
| Double confirm | the second changes nothing |
| Search | ticker/ISIN in any case → `EXACT`; one name match → `EXACT`; several → `CANDIDATES`; none → `NOT_FOUND` |
| Part of a ticker (`TRN`) | not an exact match |

## Limitation

An order cannot be created over HTTP without the model (there is no `POST /orders`), so `placeOrder`, `confirm`
and `reject` are checked by tests only.

## Results (2026-11-25)

The script report: `experiments/results/01-domain-without-llm-<time>/report.md`.

All checks passed: 119 domain tests, 32 HTTP tests, and every live check.
