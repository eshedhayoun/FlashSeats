# System Architecture Document
**High-Concurrency Flash-Sale Ticketing Platform | Technical Stack & Component Specification**

---

## 1. System Architecture Pattern

**Pattern: Modular Monolith**  
The backend is engineered as a single Spring Boot deployable artifact divided strictly into domain-isolated Java packages (e.g., `com.app.catalog`, `com.app.queue`, `com.app.order`). This architecture eliminates microservice network overhead and distributed deployment complexity while enforcing clean module boundaries, allowing individual domain packages to be extracted into independent microservices in the future if required.

---

## 2. Technology Stack & Infrastructure Matrix

| Technology / Tool | Where & Why We Need It (1 Sentence) |
| :--- | :--- |
| **Java 21** | Used across **all backend modules** to handle thousands of concurrent HTTP requests with minimal memory overhead through Virtual Threads (Project Loom). |
| **Spring Boot 3.x** | Serves as the foundational backend framework for **all backend modules** to manage REST APIs, dependency injection, and data integrations. |
| **React + TypeScript (Vite)** | Drives the **Frontend Application** to provide type-safe UI state management and rapid client-side development. |
| **MUI (Free Tier)** | Powers the **Frontend Application** to rapidly build responsive Material Design components like timers, dialogs, and progress bars without custom CSS. |
| **PostgreSQL 16** | Used in the `order` module as the primary ACID-compliant transactional database to permanently store finalized sales and outbox events. |
| **Redis 7** | Deployed across the `catalog`, `queue`, `hold`, and `bot` modules to manage microsecond in-memory operations like stock counters, waiting queues, expiring cart holds, and rate limits. |
| **RabbitMQ** | Sits between the `order` and `notification` modules to offload heavy asynchronous tasks like PDF generation and email sending away from the main web server threads. |
| **Docker Compose** | Utilized in the **Local Infrastructure Layer** to orchestrate isolated local development containers for PostgreSQL, Redis, RabbitMQ, and Mailpit with a single command. |
| **Redisson** | Integrated into the `catalog` and `hold` modules to provide thread-safe distributed locking and atomic operations when locking seats in Redis. |
| **Bucket4j** | Integrated into the `bot` module to implement in-memory token-bucket rate limiting that throttles excess traffic per IP and session. |
| **EventSource API** | Used in the **Frontend Application** to maintain persistent HTTP streaming connections with the `queue` module for real-time position updates. |
| **Apache PDFBox** | Integrated into the `notification` module to dynamically compile binary PDF ticket attachments upon successful order completion. |
| **Mailpit** | Runs in the **Local Development Environment** for the `notification` module to capture and display outgoing emails locally without needing external SMTP credentials. |
| **Google reCAPTCHA v3** | Operates between the **Frontend Application** and the `bot` module to evaluate user behavior scores and filter out automated scripts before queue entry. |

---

## 3. Core System Modules Overview

* **1. `bot` Module (`com.app.bot`) — Security & Gatekeeping**  
  Manages guest security without mandatory user registration. Issues `guest_session_id` cookies, verifies Google reCAPTCHA v3 confidence scores, and executes Bucket4j + Redis token-bucket rate limiting to drop malicious automated traffic at the gate.

* **2. `catalog` Module (`com.app.catalog`) — Events & Inventory Control**  
  Manages event metadata, ticket section configurations, and quick-pick availability options (`VIP`, `Section A`, `ANY`). Maintains atomic inventory counters in Redis RAM and answers real-time availability requests from the client UI.

* **3. `queue` Module (`com.app.queue`) — Virtual Waiting Room**  
  Buffers high-concurrency traffic during flash sales. Enqueues guest session tokens into Redis Sorted Sets, tracks live queue ordering, and streams real-time position updates to client browsers via persistent Server-Sent Events (SSE).

* **4. `hold` Module (`com.app.hold`) — Cart Locks & Expiration**  
  Handles temporary ticket reservations once a user reaches the front of the queue. Generates unique `hold_token` keys in Redis with 300-second Time-To-Live (TTL) locks and automatically returns expired tickets back to available stock if unredeemed.

* **5. `order` Module (`com.app.order`) — Transactional Persistence**  
  Executes persistent database operations and guarantees ACID compliance. Converts active Redis holds into `COMPLETED` order records in PostgreSQL and writes an `ORDER_CREATED` event into the outbox table inside a single SQL transaction.

* **6. `notification` Module (`com.app.notification`) — Async Delivery**  
  Processes non-blocking background fulfillment tasks. Polls the PostgreSQL outbox table, publishes events to RabbitMQ, consumes messages to generate PDF tickets via Apache PDFBox, and dispatches confirmation emails through the SMTP server.