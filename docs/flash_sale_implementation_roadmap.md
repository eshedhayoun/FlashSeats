# High-Concurrency Flash-Sale Engine: Progressive Implementation Roadmap

> **Architectural Strategy:** Staging your implementation into progressive layers is the smartest approach for a system of this complexity. Attempting to build distributed queueing, atomic locking, rate-limiting, and async workers all at once makes debugging nearly impossible, as a failure in one layer masks issues in another.
> 
> By building from the inside out, you establish a working, testable application at every stage. Here is a 4-phase roadmap designed to take you from a basic functional app to a stress-tested, high-concurrency engine.

---

## Roadmap Summary Matrix

| Phase | Objective | Core Components | Validation Metric |
| :--- | :--- | :--- | :--- |
| **Phase 1: Core Skeleton (MVP)** | End-to-end single-user transactional flow | Catalog, PostgreSQL Hold, Mock Payment, Orders | Successful manual API/UI checkout |
| **Phase 2: Flash-Sale Engine** | Memory inventory control & queue management | Redis Lua/Redisson, ZSET Queue, SSE Updates | Zero overbooking under race conditions |
| **Phase 3: Security & Stripe** | Bot defense & resilient payment integration | Bucket4j, reCAPTCHA v3, Stripe Webhooks, Resilience4j | Rate limits enforced & payment idempotency |
| **Phase 4: Scale & Stress Test** | Decoupled async processing & cluster load | Outbox Pattern, RabbitMQ, PDFBox, Nginx, k6 | 10k users / 500 tickets with 0 overbooking |

---

## Phase 1: The Core Transactional Skeleton (MVP)

### Objective
Verify that a single user can browse an event, reserve a seat, complete a mock purchase, and persist an order successfully.

### What to Build
* **Basic Catalog Module:** Hardcoded or simple PostgreSQL read endpoints for event details and ticket tiers.
* **Basic Hold Module:** Simple synchronous seat locking directly in PostgreSQL or basic Redis keys (no complex TTL expiry listeners yet).
* **Mock Payment Module:** A basic stub endpoint that immediately returns a `200 OK` success response.
* **Basic Order Module:** Simple PostgreSQL `@Transactional` order creation that writes a confirmed record.

### What to Skip for Now
> ⚠️ **Out of Scope:** Queueing, bot filtering, async PDF generation, RabbitMQ, and load balancing.

### Validation Check
- [ ] You can manually run through the complete purchasing flow via API or simple UI without errors.

---

## Phase 2: High Concurrency & Inventory Control (The Flash-Sale Engine)

### Objective
Solve the hardest technical challenge—preventing overbooking and managing traffic surges in memory.

### What to Build
* **Advanced Hold Module:** 
  * Atomic Redis stock counters (`DECRBY`) and custom Lua scripts.
  * Redisson distributed locks for synchronization.
  * Redis TTL keyspace notifications to auto-return abandoned tickets to stock after 5 minutes.
* **Queue Module:** 
  * Redis Sorted Sets (`ZSET`) for line management.
  * Cryptographic pass token generation for checkout authorization.
  * Server-Sent Events (SSE) streaming to push real-time queue position updates to the client.

### Validation Check
- [ ] You can simulate two parallel requests trying to claim the exact same last remaining ticket, and **exactly one succeeds while the other is rejected cleanly**.

---

## Phase 3: Security & External Integrations

### Objective
Protect the system from automated abuse and handle real-world payment edge cases safely.

### What to Build
* **Bot Module:** 
  * In-memory and Redis token-bucket rate limiting via **Bucket4j**.
  * **Google reCAPTCHA v3** score verification on high-impact endpoints.
* **Full Payment Module:** 
  * Integration with **Stripe SDK** (test mode).
  * Stripe webhooks processing for async state confirmation.
  * Client-side payment idempotency keys to prevent double-charging.
  * **Resilience4j** retry and fallback policies for fault tolerance.

### Validation Check
- [ ] Automated scripts or spammed requests get choked at the gate by rate limiters.
- [ ] Legitimate payments survive network drops or mid-checkout tab closures via webhook reconciliation.

---

## Phase 4: Async Fulfillment, Infrastructure Scaling & Stress Testing

### Objective
Decouple heavy background jobs, scale horizontally, and prove stability under load.

### What to Build
* **Notification Module:** 
  * Implementing the **Transactional Outbox Pattern** in PostgreSQL.
  * **RabbitMQ** event consumers for async decoupling.
  * Dynamic PDF ticket rendering via **Apache PDFBox**.
  * SMTP email delivery via **Mailpit**.
* **Docker Compose & Nginx:** 
  * Containerizing the application services.
  * Configuring **Nginx** load balancer to balance traffic across 3 Spring Boot replicas.
  * Sharing central state across Redis and PostgreSQL.
* **Demo Load Harness:** 
  * Running **k6** load testing scripts firing thousands of simulated users at Nginx.
  * Verifying zero overbooking and monitoring CPU/RAM usage.

### Validation Check
- [ ] Load balancer distributes traffic evenly across replicas.
- [ ] **10,000 simulated users** buy out a stock of **500 tickets** with **zero overbooking**.
- [ ] **500 PDF emails** land cleanly in Mailpit background queues.

---

## Architecture Lifecycle Flow

```text
[ Phase 1: MVP Flow ]
Client ---> Controller ---> PostgreSQL (Synchronous Transaction)

[ Phase 2: High Concurrency Engine ]
Client ---> Redis ZSET Queue ---> SSE Position Stream ---> Lua Script / Redisson Lock ---> Redis Inventory

[ Phase 3: Protected Payment Gateway ]
Client ---> Bucket4j Rate Limiter ---> reCAPTCHA v3 ---> Stripe API ---> Webhook Reconciliation

[ Phase 4: Distributed Scaled Engine ]
Client ---> Nginx LB ---> [3x Spring Boot Replicas] ---> DB Outbox ---> RabbitMQ ---> PDF Generator / Mailpit
```
