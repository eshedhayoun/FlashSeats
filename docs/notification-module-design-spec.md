# Module Design Specification: Notification Module

## 1. Module Overview & Scope

* **Core Responsibility:** Asynchronously consumes finalized purchase events, generates downloadable PDF ticket documents using **Apache PDFBox**, renders HTML email templates using **Thymeleaf**, and sends email notifications to ticket buyers via SMTP (**Mailpit** in development/test environments).
* **Domain Position:** **Phase 4 (Asynchronous Processing & Ticket Fulfillment)**. Decouples heavy document rendering and external email delivery from the synchronous checkout path to maintain ultra-fast HTTP response times.
* **Explicit Boundary Limits:** Strictly forbidden from checking inventory capacity (owned by `catalog`/`hold`), altering order statuses directly (owned by `order`), validating payment cards (owned by `payment`), or enforcing user queue positions (owned by `queue`).

---

## 2. Package Structure & Code Layout

The module follows standard Spring Boot package structuring under `com.app.notification`:

```text
com.app.notification
├── controller   # REST controllers for administrative notification inspection and manual resend operations
├── service      # Business logic for Apache PDFBox ticket rendering, Thymeleaf HTML template compilation,
│                # SMTP email delivery, and RabbitMQ message consumption
├── facade       # Defines the public Java interface (NotificationFacade) and implementation (NotificationFacadeImpl)
│                # exposed exclusively to other backend modules
├── repository   # Database persistence interfaces for notification delivery audit logs in PostgreSQL
├── model        # PostgreSQL relational JPA entities representing email delivery attempts, log states, and failure history
├── dto          # Immutable DTOs for message consumer payloads, template rendering contexts, and delivery log summaries
└── event        # RabbitMQ listener components and Dead Letter Queue (DLQ) exception handlers
```

---

## 3. Data Storage & Schema Design

### PostgreSQL Tables (Relational Audit Ledger)

#### Table: `notification_logs`

| Column Name | Data Type | Constraints / Default | Description |
| :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | Primary Key, Auto-increment | Unique internal log identifier. |
| `order_number` | `VARCHAR(64)` | `NOT NULL`, Indexed | Reference string of the parent order. |
| `recipient_email` | `VARCHAR(255)` | `NOT NULL` | Target email address for ticket delivery. |
| `status` | `VARCHAR(32)` | `NOT NULL` | Delivery state (`PENDING`, `SENT`, `FAILED`, `DLQ`). |
| `retry_count` | `INT` | `NOT NULL`, Default: `0` | Number of processing attempts executed. |
| `failure_reason` | `VARCHAR(500)` | Nullable | Text exception details if delivery fails. |
| `sent_at` | `TIMESTAMP WITH TIME ZONE` | Nullable | Timestamp when the email was successfully accepted by the SMTP server. |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | `NOT NULL` | Log creation timestamp. |

---

### RabbitMQ Message Architecture & Queue Topology

Unlike synchronous modules using Redis RAM counters, the notification module uses RabbitMQ to achieve reliable, asynchronous message delivery with retry queues and dead-letter handling.

#### 1. Exchange & Queue Topology

* **Main Exchange:** `order.events.exchange`
  * **Type:** Topic Exchange
* **Notification Queue:** `notification.order-confirmed.queue`
  * **Routing Key:** `order.confirmed`
  * **Arguments:**
    * `x-dead-letter-exchange` $ightarrow$ `notification.dlx.exchange`
    * `x-dead-letter-routing-key` $ightarrow$ `notification.dead-letter`
* **Dead Letter Exchange (DLX):** `notification.dlx.exchange`
  * **Type:** Direct Exchange
* **Dead Letter Queue (DLQ):** `notification.order-confirmed.dlq`
  * **Purpose:** Stores failed messages after maximum retries for administrative inspection and manual replay.

#### 2. Message Consumption & PDF Generation Workflow

1. **Event Publishing:** The `order` module's Transactional Outbox processor publishes an `OrderConfirmedEvent` to `order.events.exchange` with routing key `order.confirmed`.
2. **Message Ingestion:** The notification consumer picks up the message payload containing order number, user email, tier details, and seat metadata.
3. **PDF Document Generation:** Apache PDFBox renders a PDF ticket containing order details and a barcode/QR code placeholder directly in memory.
4. **HTML Email Compilation:** Thymeleaf renders an HTML email body, attaching the generated PDF ticket.
5. **SMTP Delivery:** `JavaMailSender` transmits the email to Mailpit (or the target production SMTP host).
6. **Acknowledgment & Audit Update:** Upon successful transmission, the message is manually acknowledged (`basicAck`) to RabbitMQ, and the corresponding `notification_logs` record is updated to `SENT`.

---

## 4. Interfaces & Event Contracts

### External REST Endpoints

| Method | Endpoint Path | Description | Access Level |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/admin/notifications/resend/{orderNumber}` | Manually trigger ticket email re-generation and resend for an order. | Admin Only |
| `GET` | `/api/v1/admin/notifications/logs/{orderNumber}` | View delivery audit logs and retry history for a specific order. | Admin Only |

---

### Internal Module Facade (`NotificationFacade`)

Exposes synchronous Java methods for in-memory cross-module calls:

* **Manual Notification Re-trigger Method:**
  * **Input:** `orderNumber` (`String`)
  * **Behavior:** Bypasses RabbitMQ to immediately re-generate and send ticket emails synchronously for administrative customer support.
* **Delivery Status Query Method:**
  * **Input:** `orderNumber` (`String`)
  * **Returns:** An immutable `NotificationStatusDTO` indicating whether ticket emails have been successfully delivered.

---

### Async Message Contracts (RabbitMQ Consumer)

* **Consumer Listener:** `OrderConfirmedConsumer`
* **Subscribed Queue:** `notification.order-confirmed.queue`
* **Payload Example:**

```json
{
  "orderNumber": "TK-98213",
  "userEmail": "buyer@example.com",
  "totalAmountCents": 15000,
  "eventName": "Summer Fest",
  "tierName": "VIP",
  "quantity": 2,
  "confirmedAt": "2026-08-28T17:55:00Z"
}
```

---

## 5. Concurrency Control, Failure Modes & Edge Cases

### Idempotent Consumption
Before compiling a PDF or sending an email, the consumer checks `notification_logs` for `order_number`. If a record with status `SENT` already exists, the message is acknowledged and skipped instantly to avoid sending duplicate ticket emails to buyers.

### Manual Message Acknowledgments (MANUAL Ack Mode)
RabbitMQ messages are acknowledged only after the email has been handed off to the SMTP server and the database audit log is updated. If a worker container crashes during PDF rendering, RabbitMQ automatically re-delivers the message to another available worker node.

### Retry Policy & Exponential Backoff
If the SMTP server experiences a temporary outage, processing throws an exception. RabbitMQ retries up to 3 times with exponential backoff (e.g., 5s, 30s, 2m). If all retries fail, the message routes to `notification.order-confirmed.dlq` and updates the `notification_logs` status to `DLQ`.

### Custom Domain Exception Handling
* **PDF Compilation Failure:** Throws `PdfGenerationException` (Triggers retry pipeline).
* **SMTP Handshake Failure:** Throws `EmailDeliveryException` (Triggers retry pipeline).
* **Log Entity Not Found:** Throws `NotificationLogNotFoundException` (Mapped to HTTP `404 Not Found`).
