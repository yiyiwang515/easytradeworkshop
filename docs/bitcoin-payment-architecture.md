# Bitcoin Payment Feature — Architecture & 100x Scaling Proposal

> Grounded in production telemetry pulled from **Bluebox** (`credit-card-order-service` and its
> dependencies, 24h + 7d windows, as of 2026-09-29). All baseline numbers below are measured,
> not assumed.

## 1. Production baseline (from Bluebox)

| Component | Current load (24h) | Resource posture | 100x projection |
|---|---|---|---|
| **credit-card-order-service** | 9,335 req/24h (~6.5/min). `GET /v1/orders/{accountId}/status/latest` = **97.2%** of all traffic | cpu limit **500m** (½ core), 650Mi mem; JVM ~2.2% CPU, heap 27/213MB | ~220% of one core → **must scale horizontally** |
| **feature-flag-service** | **184,303 calls/24h** (checked on ~every request, plus other services) | Single core @4.2% CPU | ~18.4M/24h (~213/sec) → ~**420% of a core** — **#1 bottleneck** |
| **mssql database** | 8,322 calls/24h, p50 1.7ms / p95 3.9ms / p99 7.3ms | No server-side headroom visibility | Latency fine; risk = **connection-pool sizing + lock contention** at ~9.6 writes/sec |
| **third-party-service** | 21 calls/24h | Single core @2.7% | ~2,100/24h — **negligible** |

### Components under the most stress at 100x (ranked)

1. **feature-flag-service** — highest absolute volume and largest projected overshoot. Every payment
   request currently makes a synchronous flag lookup. At 100x this is the first thing to fall over.
2. **credit-card-order-service** — the write/status path and the new bitcoin endpoints; capped at
   500m CPU with **no replicas and no HPA** today.
3. **mssql** — query latency has margin, but `DatabaseHelper` opens a **raw `DriverManager`
   connection per request with no pool**. At 100x that means thousands of new TCP+auth handshakes/sec
   and connection exhaustion long before query latency matters.
4. **third-party-service** — low risk from our side; its *real* backend capacity is not observable.

## 2. Proposed architecture for 100x

The goal is to add bitcoin payments **without** amplifying the existing bottlenecks. Design
principles: cache flag reads, pool DB connections, make the payment-provider call async/resilient,
and scale the service horizontally.

```
                        ┌─────────────────────────┐
  client ──▶ frontend ─▶│ credit-card-order-service│ (N replicas behind Service + HPA)
             reverse    │  ┌────────────────────┐  │
             proxy      │  │ BitcoinPaymentCtrl │  │
                        │  └─────────┬──────────┘  │
                        │            │             │
                        │   ┌────────▼─────────┐   │      ┌───────────────────────┐
                        │   │ BitcoinPayment   │───┼─────▶│ bitcoin payment gateway│ (external, async)
                        │   │ Service          │   │      │  quote + settle        │
                        │   └───┬──────────┬───┘   │      └───────────────────────┘
                        │       │          │       │
              cached flag reads │          │ pooled JDBC (HikariCP)
                        │       ▼          ▼       │
              ┌─────────▼──┐   ┌───────────▼────┐  │
              │ feature-   │   │ mssql          │  │
              │ flag-svc   │   │ (BitcoinPayments│ │
              │ (cached)   │   │  table)        │  │
              └────────────┘   └────────────────┘  │
                        └─────────────────────────┘
```

### 2.1 Relieve feature-flag-service (bottleneck #1)
- **Client-side caching** of flag values with a short TTL (e.g. 5–10s) inside each service, so
  184k→18.4M calls do **not** all hit feature-flag-service. This alone removes the projected 420%
  overshoot; flag reads become in-process for the vast majority of requests.
- OpenFeature supports provider-level caching; alternatively poll the flag on a schedule and read
  from a local `volatile` snapshot (the service already has a `WorkScheduler`).
- Give feature-flag-service its own HPA as a defense-in-depth measure.

### 2.2 Scale credit-card-order-service horizontally
- Add **`replicaCount`** and a **HorizontalPodAutoscaler** (target ~60% CPU) in Helm. The service is
  stateless (state lives in mssql), so replicas scale linearly.
- Raise the CPU limit off ½ core (e.g. request 250m / limit 1000m) so a single replica isn't
  throttled under burst.

### 2.3 Introduce a real DB connection pool (bottleneck #3)
- Replace per-request `DriverManager.getConnection(...)` with a **pooled `DataSource` (HikariCP)**,
  sized per replica (e.g. 10–20 connections). This is the single highest-leverage change for DB
  survivability at 100x. New bitcoin writes go through the same pool.
- Keep bitcoin-payment rows in a dedicated `BitcoinPayments` table with an index on `AccountId` and
  `OrderId` to avoid lock contention on the hot `CreditCardOrders`/`CreditCardOrderStatus` tables.

### 2.4 Make the payment-provider call resilient
- The external bitcoin gateway is the one dependency whose latency/availability we don't control.
  Wrap it with **timeouts, retries with backoff, and a circuit breaker**, and persist a
  `PENDING → CONFIRMED/FAILED` status so a slow provider never blocks a request thread (protecting
  the thread pool at 100x). The scheduler reconciles pending payments.

### 2.5 Observability (see §3)
- Add SLOs and alerts before ramping traffic; today a 100% failure went unalerted.

## 3. Existing issues to address ALONGSIDE this feature

These are pre-existing and were surfaced by Bluebox; shipping bitcoin payments on top of them is
risky:

1. **🔴 Active outage — divide-by-zero on `status/latest` (issue #21).** Still failing ~100% in
   production; the `credit_card_meltdown` flag is on and the fix (PR #1) is unmerged/undeployed.
   The bitcoin flow reuses the same service and status-polling pattern — **fix and deploy #21 first.**
2. **🟠 No connection pool.** `DatabaseHelper` opens a fresh `DriverManager` connection per request.
   Must be fixed for any 100x plan (see §2.3).
3. **🟠 No horizontal scaling.** No `replicaCount`/HPA; CPU capped at 500m. Must be fixed (§2.2).
4. **🟠 Synchronous flag lookup on every request.** Amplifies the feature-flag-service bottleneck
   (§2.1).
5. **🟡 No SLOs / alerting.** Bluebox found zero SLOs configured tenant-wide, and the 100% failure
   was not surfacing as a problem. Add SLOs + alerts before ramping.

## 4. What this PR implements now

This PR delivers the **application-layer feature** end-to-end, feature-flagged so it ships dark:

- `BitcoinPaymentController` — `POST /v1/orders/bitcoin-payments`, `GET /.../{paymentId}`,
  gated behind a new `bitcoin_payments` OpenFeature flag (default **off**).
- `BitcoinPaymentService` + `BitcoinPaymentGatewayClient` — resilient outbound call to the payment
  gateway with timeout handling and a `PENDING → CONFIRMED/FAILED` lifecycle.
- Model records following existing conventions (`BitcoinPaymentRequest`, `BitcoinPaymentResponse`,
  `BitcoinPaymentStatus`).
- Helm scaling scaffolding (`replicaCount` + HPA values) and documentation for the infra changes in
  §2, so the platform team can enable them.

The pooling migration (§2.3) is called out as a **follow-up** because it touches every DB call in
the service and deserves its own reviewed change; this PR keeps the bitcoin writes behind the flag
so nothing hits production until §1–§3 blockers are cleared.
