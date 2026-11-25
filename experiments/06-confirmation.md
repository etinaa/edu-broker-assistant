# Experiment 6. Confirmation as a separate action

**Task 6.** `confirm` re-checks cash, position, price and lifetime: something may have changed between creating and
confirming the order.

## Questions and rules

| Case | Question | Rule |
|---|---|---|
| (a) | Two orders of 15 DEMO (6,150.00 each), each passed the check when created. Can both be confirmed? | No: cash is checked again at confirm. The first is EXECUTED, the second FAILED, short by 2,300.00 |
| (b) | The DEMO price changed after the order was created. At what price to execute? | At the current one. A move against the client of more than `broker.max-price-move-percent` (2%) → FAILED with `PRICE_CHANGED`, a new order is needed. Exactly at the limit, or in the client's favour, it is executed |
| (c) | How long does an order live? | `broker.order-ttl` (5m). An overdue order becomes EXPIRED when read or confirmed |
| (d) | Confirming the same order again? | Changes nothing, 409 |

## Setup

- Provider: `PROVIDER` (default from `.env`); the model is needed only to create orders through `/chat`.
- (c) is a separate run with `BROKER_ORDERTTL=20s` (`SHORT_TTL_SECONDS`), so there is no 5-minute wait.
- (b) cannot be reproduced live: the price cannot be changed over HTTP, only with `SecurityCatalog.updatePrice` in
  tests.

## Running

```bash
experiments/06-confirmation.sh
SKIP_LIVE=1 experiments/06-confirmation.sh          # tests only, no model
SHORT_TTL_SECONDS=10 experiments/06-confirmation.sh
```

## Steps

1. Tests for every case (`BrokerServiceTest`, `OrderControllerIntegrationTest`, `OrderControllerTest`), including
   parallel confirms.
2. (a) "Buy 15 shares of DEMO" twice in different sessions → `confirm` the first (EXECUTED, 3,850.00 left) →
   `confirm` the second (409 `INSUFFICIENT_FUNDS`, 2,300.00) → `GET` the second — FAILED.
3. (d) "Buy 1 share of DEMO" → `confirm` → `confirm` again (409, same cash) → `reject` of the executed order (409).
4. (c) Restart with a short TTL → an order → `GET` (PENDING) → wait → `GET` (EXPIRED) → `confirm`
   (409 `ORDER_EXPIRED`), cash 10,000.00.

## Success criteria

Every checklist item is ticked; the (b) tests pass.

## Results

Done (item 5 in `tasks.txt`): (a) only the first order is confirmed, the second FAILED short by 2,300.00;
(b) execution at the current price, a move against the client of more than 2% → `PRICE_CHANGED`, in the client's
favour — executed; (c) an overdue order is EXPIRED when read or confirmed; (d) a repeated confirm changes nothing.

2026-11-25, ollama/qwen3.5:9b: every live check of (a), (c) and (d) passed, before and after the
tool description change. The second 15 DEMO order failed with "needed 6150.00 USD, available 3850.00 USD, short by
2300.00 USD"; the order with a 20 s lifetime was EXPIRED on the next read and its confirm got `ORDER_EXPIRED`.
