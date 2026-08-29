# High-Level System Architecture & Design Specification
## High-Concurrency Flash-Sale Ticketing Engine

---

## 1. System Infrastructure & Deployment Blueprint

The system is designed as a stateless **Modular Monolith** running inside Docker containers, scaled horizontally to handle extreme traffic spikes without overbooking or server crashes.

```
              [ Concurrent Users / Flash-Sale Traffic ]
                                  │
                                  ▼
                  [ Nginx Load Balancer (Docker) ]
                                  │
       ┌──────────────────────────┼──────────────────────────┐
       ▼                          ▼                          ▼
[ App Instance 1 (Spring) ] [ App Instance 2 (Spring) ] [ App Instance 3 (Spring) ]
│                          │                          │
└──────────────────────────┼──────────────────────────┘
│
┌────────────────────────────┼────────────────────────────┐
▼                            ▼                            ▼
[ Redis 7 Cluster ]          [ PostgreSQL 16 ]               [ RabbitMQ ]
(Stock, Queue, Holds)     (Orders, Outbox Ledger)        (Async PDF & Email)
```

### Infrastructure Core Components
* **Nginx Load Balancer:** Runs in Docker Compose as the entry gateway. It uses a round-robin / least-connections algorithm to distribute incoming HTTP and Server-Sent Event (SSE) requests across multiple running Spring Boot replicas.
* **Spring Boot Container Replicas:** Multiple identical, stateless instances of the Java 21 backend application. Because state is stored centrally in Redis and PostgreSQL, any instance can process any user request.
* **Flash-Sale Simulation & Testing Suite (Demo Harness):** A containerized load-testing framework using **k6** (or **Locust**). It allows you to fire 10,000+ virtual users simultaneously at the Nginx gateway in a demo environment to simulate queueing, stock exhaustion, payment processing, and confirm that exactly zero overbooking occurs under load.

---

## 2. Comprehensive Module Specifications

The backend code is divided into seven domain-isolated packages (`com.app.<module>`). Each module has a single core responsibility and explicit technology tools.

### 2.1 `bot` Module (`com.app.bot`)
* **Why We Need It:** To protect downstream services and databases from being overwhelmed by DDoS attacks, malicious scrapers, and automated purchasing scripts before business logic is touched.
* **Where It Fits:** First line of defense at the HTTP request layer.
* **Key Tools:** Google reCAPTCHA v3 API, Bucket4j, Redis 7 (for distributed rate-limit counters).

### 2.2 `catalog` Module (`com.app.catalog`)
* **Why We Need It:** To serve high-volume event metadata reads fast and initialize ticket availability counters in RAM before the flash-sale launch.
* **Where It Fits:** Pre-sale browsing and initial inventory synchronization.
* **Key Tools:** Spring Data JPA, PostgreSQL 16, Redis 7 (In-Memory Atomic Counters).

### 2.3 `queue` Module (`com.app.queue`)
* **Why We Need It:** To control incoming user volume so the checkout system receives traffic only at a rate it can safely process.
* **Where It Fits:** Triggers as soon as a user clicks "Join Sale".
* **Key Tools:** Redis Sorted Sets (`ZSET`), Server-Sent Events (SSE) via Spring WebFlux / SseEmitter, HMAC-SHA256 (for signing queue pass tokens).

### 2.4 `hold` Module (`com.app.hold`)
* **Why We Need It:** To grant exclusive, 5-minute temporary seat reservations to users leaving the queue, ensuring two people cannot buy the same ticket.
* **Where It Fits:** Activates after a user exits the waiting room line position #1.
* **Key Tools:** Redisson (Distributed Locks), Redis 7 (Keyspace Notifications & TTL Expiry), Lua Scripts.

### 2.5 `payment` Module (`com.app.payment`)
* **Why We Need It:** To handle external payment processing safely, manage payment gateway errors, prevent double-charging via idempotency, and communicate transaction state back to the order engine.
* **Where It Fits:** Sits between ticket selection (`hold`) and final database persistence (`order`).
* **Key Tools:** Stripe Java SDK (Test Mode / Mock Payment Intents), Stripe Webhooks, Resilience4j (Circuit Breakers & Retries).

### 2.6 `order` Module (`com.app.order`)
* **Why We Need It:** To execute ACID-compliant database writes, record sales ledger history permanently, and guarantee that background fulfillment jobs are never lost.
* **Where It Fits:** Converts verified payment authorizations and active ticket holds into confirmed database orders.
* **Key Tools:** PostgreSQL 16, Spring `@Transactional`, Transactional Outbox Event Pattern.

### 2.7 `notification` Module (`com.app.notification`)
* **Why We Need It:** To generate PDF tickets and send confirmation emails asynchronously without delaying the user's checkout response time.
* **Where It Fits:** Operates entirely in the background after the user sees the checkout success screen.
* **Key Tools:** RabbitMQ, Apache PDFBox, Spring Boot Email / JavaMailSender, Mailpit (Local SMTP testing server).

---

## 3. End-to-End User Journey & System State Flow

```
[ User Browser ]
│
├─► 1. Browse Catalog ──────────► [ catalog ] (Fetch event info & tiers)
│
├─► 2. Join Flash Sale ─────────► [ bot ] (Captcha & Rate limit check)
│                                     │
│                                     ▼
│                                 [ queue ] (Assigned Redis ZSET rank & SSE connection)
│                                     │
├─► 3. Turn Arrives (Rank #1) ◄───────┘ (Receives signed queue pass token)
│
├─► 4. Select Ticket ───────────► [ hold ] (Redisson atomic stock decrement & 300s TTL key)
│
├─► 5. Submit Payment ──────────► [ payment ] (Stripe Test charge + Idempotency token)
│                                     │
│                                     ▼ (Payment Success)
│                                 [ order ] (PostgreSQL ACID transaction & Outbox write)
│                                     │
└─► 6. View Confirmation ◄────────────┘ (Fast HTTP 200 response to user UI)
│
▼ (Async Background Worker)
[ notification ] (RabbitMQ consume -> PDFBox -> Email)
```

---

## 4. Architectural Strategies & Edge-Case Safeguards

* **Nginx Load Balancing:** Distributes HTTP traffic evenly across multiple stateless backend instances while passing client IP headers for rate limiting.
* **Atomic Redis Inventory Counters:** Eliminates overbooking by executing microsecond memory decrements before any SQL database connection is opened.
* **Virtual Threads (Java 21 Project Loom):** Allows hundreds of thousands of concurrent web requests to run efficiently on minimal server hardware without blocking OS threads.
* **Payment Idempotency Keys:** Prevents duplicate charges by attaching unique client tokens to every payment request processed by the gateway.
* **Stripe Webhook Reconciliation:** Handles delayed asynchronous payment updates to mark orders as failed or completed even if the user closes their browser mid-checkout.
* **Automated Hold Expiration Listener:** Captures expired Redis keys to automatically return abandoned tickets back to active stock without manual database cleanups.
* **Transactional Outbox Pattern:** Combines order database writes and event publishing into a single atomic SQL transaction to prevent lost emails.
* **Asynchronous PDF Generation:** Isolates heavy document rendering in RabbitMQ worker queues so checkout API responses stay under 200 milliseconds.
* **Containerized Load-Test Harness:** Uses k6 scripts in Docker Compose to flood the Nginx load balancer with thousands of virtual buyers to verify system stability and zero overbooking in real time.