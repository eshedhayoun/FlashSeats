# End-to-End Modular Monolith Architecture & UX Flow

> **Architecture Overview:** A resilient, high-concurrency event ticketing engine designed as a **Modular Monolith**. It combines synchronized in-memory domain facades, Redis-backed rate limiting and inventory allocation, an SSE-driven Virtual Waiting Room, Transactional Outbox pattern, and asynchronous message-driven fulfillment.

---

## 1. System Architecture Overview

### Diagram

```
                                  +---------------------------------------+
                                  |            CLIENT / BROWSER           |
                                  +---------------------------------------+
                                    |         |          |          |
                                    | HTTP    | SSE      | HTTP     | HTTP
                                    v         v          v          v
                                 +------------------------------------+
                                 |          `bot` FILTER              |
                                 |  (Bucket4j / reCAPTCHA v3 / IP)    |
                                 +------------------------------------+
                                                  |
           +--------------------------------------+--------------------------------------+
           |                                      |                                      |
           v                                      v                                      v
+--------------------+                 +--------------------+                 +--------------------+
|     `catalog`      |                 |      `queue`       |                 |       `hold`       |
|  Event Metadata    |                 | Virtual Wait Room  |                 | Seat Reservation   |
+--------------------+                 +--------------------+                 +--------------------+
| DB: PostgreSQL     |                 | DB: None           |                 | DB: PostgreSQL     |
| RAM: Redis Caching |                 | RAM: Redis ZSET    |                 | RAM: Redis Stock   |
+--------------------+                 +--------------------+                 +--------------------+
                                                  |                                      |
                                                  +-------------------+------------------+
                                                                      |
                                                                      v
                                                           +--------------------+
                                                           |      `payment`     |
                                                           |   Stripe Gateway   |
                                                           +--------------------+
                                                           | DB: PostgreSQL     |
                                                           | RAM: Redis Lock    |
                                                           +--------------------+
                                                                      |
                                                                      v
                                                           +--------------------+
                                                           |      `order`       |
                                                           | Order & Outbox     |
                                                           +--------------------+
                                                           | DB: PostgreSQL     |
                                                           +--------------------+
                                                                      |
                                                                      v (Async Message)
                                                           +--------------------+
                                                           |   `notification`   |
                                                           | PDF & Email Queue  |
                                                           +--------------------+
                                                           | DB: PostgreSQL     |
                                                           | Broker: RabbitMQ   |
                                                           +--------------------+
```

### Module Infrastructure Summary

| Module | Functional Focus | Database Layer | Cache / In-Memory / Broker Layer |
| :--- | :--- | :--- | :--- |
| **`bot`** | Security, IP rate limiting & reCAPTCHA v3 score verification | *None* | Redis (`Bucket4j` token buckets) |
| **`catalog`** | Event metadata, ticket tiers, baseline inventory | PostgreSQL (`events`, `ticket_tiers`) | Redis RAM Counters (`catalog:stock:{eventId}:{tierId}`) |
| **`queue`** | Virtual waiting room state & position management | *None* | Redis Sorted Sets (`queue:waiting:{eventId}`), HMAC Pass Tokens |
| **`hold`** | Seat reservation locks, stock decrement, expiration tracking | PostgreSQL (`ticket_holds`) | Redis Hashes (`hold:{holdToken}`), Keyspace Expiration Listeners |
| **`payment`** | Gateway processing & idempotency control | PostgreSQL (`payments`) | Redis Locks (`payment:idempotency:{idempotencyKey}`) |
| **`order`** | Core purchase finalization & Transactional Outbox | PostgreSQL (`orders`, `order_items`, `outbox_events`) | In-Process Event Publisher / Synchronous Facades |
| **`notification`** | Ticket PDF generation & email delivery | PostgreSQL (`notification_logs`) | RabbitMQ (`order.events.exchange`), SMTP Server |

---

## 2. Step-by-Step User Experience (UX) Flow

### Step 0: Pre-Sale Setup & Inventory Warming *(Admin / System Phase)*
* **Event Creation:** An administrator creates an event and configures ticket tiers (price, total capacity) in the `catalog` module (persisted to `events` and `ticket_tiers` PostgreSQL tables).
* **Pre-Warming Trigger Options:** Inventory pre-warming is executed via one of two mechanisms:
  * **Automated:** Scheduled background job running automatically 15 minutes prior to `sale_start_time`.
  * **Manual / Emergency:** On-demand administrator override via `POST /api/v1/admin/events/{eventId}/prewarm`.
* **RAM Stock Initialization:** The `catalog` module loads tier baseline counts from PostgreSQL and populates atomic Redis RAM counters (`catalog:stock:{eventId}:{tierId}`) using `SETNX` commands.
* **Readiness Broadcast:** `catalog` fires an in-process `EventPrewarmedEvent` to notify system monitors that inventory is active and ready in RAM.

---

### Step 1: Browsing & Discovery *(Pre-Queue Phase)*
* **Request Ingestion:** The user navigates to the event details page (`GET /api/v1/events/{eventId}`).
* **Security Gate:** The `bot` interceptor inspects the user's IP address and session state via `bot:rate:ip:{ipAddress}` in Redis using Bucket4j rate-limiting rules.
* **Metadata Fetch:** `catalog` serves event metadata, venue details, pricing tiers, and real-time availability status directly from Redis RAM or database read replicas with sub-millisecond response times (`<1ms`).

---

### Step 2: Entering the Virtual Waiting Room *(Queue Phase)*
* **Queue Request:** The user clicks **"Join Flash Sale"** (`POST /api/v1/queue/join`).
* **Bot Verification:** The request passes through `bot` where Google reCAPTCHA v3 validates the client token, requiring a score threshold of $\ge 0.5$.
* **Line Placement:** The `queue` module inserts `userSessionId` into a Redis Sorted Set (`queue:waiting:{eventId}`) using the current epoch timestamp as the score (`ZADD`).
* **SSE Stream Connection:** The user's browser opens a Server-Sent Events connection (`GET /api/v1/queue/stream`). The queue service periodically queries `ZRANK` and streams live queue position updates (e.g., `#450` $ightarrow$ `#120` $ightarrow$ `#1`) to the browser UI.
* **Pass Token Generation:** Upon reaching position `#1`, the queue service generates an HMAC-SHA256 signed pass token, stores `queue:pass:{userSessionId}` in Redis with a 5-minute TTL, and pushes the pass token to the user's browser via SSE to trigger an automatic redirect to the seat selection view.

---

### Step 3: Ticket Selection & Seat Holding *(Hold Phase)*
* **Reservation Request:** The user selects 2 tickets in the VIP Section and clicks **"Reserve"** (`POST /api/v1/holds`), passing the `queuePassToken`.
* **Pass Validation:** `hold` performs a synchronous in-memory call to `QueueFacade.verifyPassToken()`. If valid, execution proceeds.
* **Atomic Decrement:** `hold` issues an atomic decrement command against Redis key `catalog:stock:{eventId}:{tierId}`.
  * **Stock Available ($\ge 0$):** `hold` creates a Redis Hash `hold:{holdToken}` with a 300-second (5-minute) TTL, logs an `ACTIVE` hold entry in PostgreSQL table `ticket_holds`, and returns `holdToken` to the browser.
  * **Sold Out ($< 0$):** `hold` immediately increments the counter back by 2 and returns `HTTP 409 Conflict` (`INSUFFICIENT_STOCK`).
* **Expiration Handling (Edge Case):** If the user closes their browser and the 300-second TTL expires, a Redis Keyspace Expiration Listener inside `hold` intercepts the event, extracts `eventId`, `tierId`, and `quantity`, and atomically increments `catalog:stock:{eventId}:{tierId}` back in Redis RAM.

---

### Step 4: Checkout & Payment Authorization *(Payment Phase)*
* **Payment Submission:** The user inputs payment credentials and clicks **"Pay Now"** (`POST /api/v1/payments/confirm`), supplying `holdToken` and a client-generated `idempotencyKey`.
* **Idempotency Guard:** `payment` executes `SETNX` on key `payment:idempotency:{idempotencyKey}` in Redis RAM to reject concurrent duplicate requests.
* **Gateway Call:** `payment` executes a tokenized charge through the Stripe Java SDK, wrapped with Resilience4j Circuit Breakers.
* **Handling Payment Failures & Edge Cases:**
  * **Card Decline / Invalid Input:** The transaction is logged as `FAILED`, but seat hold status remains `ACTIVE`. The user is retained on the payment screen to retry or switch payment methods using the existing `holdToken`.
  * **Timer Expiration Mid-Payment:** During active gateway authorization or 3D-Secure OTP verification, `payment` invokes `HoldFacade.extendHold()` to append a temporary grace period, preventing seat reclamation during processing.
  * **Network Disconnect Post-Charge:** If the client connection drops prior to HTTP response delivery, payment completion continues via Stripe. An asynchronous Stripe webhook (`payment_intent.succeeded`) delivered to `/api/v1/payments/webhook` finalizes the order and triggers fulfillment.
  * **Hard Abandonment / Manual Cancel:** If payment fails permanently or the user cancels, `payment` fires a `PaymentFailedEvent`, causing `hold` to release seats back to Redis RAM stock immediately.

---

### Step 5: Order Finalization *(Order Phase)*
* **Event Reception:** `order` catches `PaymentSucceededEvent` and initiates a `@Transactional` SQL block.
* **Hold Consumption:** `order` calls `HoldFacade.consumeHold(holdToken)`. `hold` updates PostgreSQL hold status to `CONSUMED` and deletes `hold:{holdToken}` in Redis to prevent auto-restoration.
* **Transactional Outbox Write:** Within the identical SQL transaction boundary, `order` performs two atomic writes:
  1. Inserts permanent purchase records into `orders` and `order_items` tables.
  2. Inserts an `OrderConfirmedEvent` JSON payload into `outbox_events` with status `PENDING`.
* **User Confirmation:** `order` returns `HTTP 200 OK` with the permanent order identifier (e.g., `TK-98213`) to the browser UI.

---

### Step 6: Async Ticket Delivery & Fulfillment *(Fulfillment Phase)*
* **Outbox Polling:** An asynchronous background worker scans `outbox_events` for `PENDING` records, publishes them to RabbitMQ exchange `order.events.exchange` with routing key `order.confirmed`, and updates outbox status to `PROCESSED`.
* **Queue Consumption:** The `notification` module's consumer (`OrderConfirmedConsumer`) pulls the message from `notification.order-confirmed.queue`.
* **Idempotency Check:** `notification` queries `notification_logs` table for `orderNumber`. If marked `SENT`, processing is safely skipped.
* **PDF Generation & Email Delivery:**
  * Apache PDFBox compiles a PDF ticket containing purchase details and barcode placeholders entirely in memory.
  * Thymeleaf renders the HTML email body with the PDF attached.
  * `JavaMailSender` transmits the email via SMTP (Mailpit).
* **Message Acknowledgment:** `notification` updates `notification_logs` status to `SENT` and issues a manual `basicAck` to RabbitMQ.
* **Dead Letter Queue (DLQ) Handling:** If SMTP delivery fails, RabbitMQ retries up to 3 times with exponential backoff. If all attempts fail, the message routes to `notification.order-confirmed.dlq` for administrator inspection and manual replay via `POST /api/v1/admin/notifications/resend/{orderNumber}`.

---

## 3. In-Memory Inter-Module Facade Interaction Matrix

| Calling Module | Called Facade | Target Module | Purpose & Execution Context |
| :--- | :--- | :--- | :--- |
| **`hold`** | `QueueFacade` | `queue` | Verify cryptographic HMAC-SHA256 queue pass token prior to seat reservation. |
| **`hold`** | `CatalogFacade` | `catalog` | Verify ticket tier existence, active status, and fetch section/pricing details. |
| **`order`** | `HoldFacade` | `hold` | Validate hold token, mark status as `CONSUMED`, and clear Redis TTL timer. |
| **`order`** | `CatalogFacade` | `catalog` | Retrieve historical snapshot of tier names and pricing for permanent purchase ledger. |
| **`payment`** | `HoldFacade` | `hold` | Append temporary grace TTL during active gateway processing, or immediately release seats to Redis RAM on hard failure. |
| **`admin` / `web filter`** | `BotFacade` | `bot` | Inspect rate-limit token buckets and verify Google reCAPTCHA v3 scores. |
| **`admin`** | `NotificationFacade` | `notification` | Manually re-trigger synchronous PDF rendering and email delivery for DLQ message replay. |
