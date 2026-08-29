# Module Design Specification: Order Module

**System Component:** Core Order Management Service  
**Package Namespace:** `com.app.order`  
**Domain Phase:** Phase 1 (MVP Foundation)  
**Status:** Approved Specification  
**Last Updated:** August 2026  

---

## Executive Summary & Metadata

| Attribute | Details |
| :--- | :--- |
| **Module Name** | `order` |
| **Primary Responsibility** | Transactional authority for purchase completion and order ledger persistence |
| **Storage Engines** | PostgreSQL (Relational Transactional Ledger) |
| **Inter-Module Dependencies** | `hold` (via `HoldFacade`), `payment` (via internal service/facade) |
| **Downstream Consumers** | `notification` (via Transactional Outbox Pattern), `catalog` |
| **Consistency Model** | Strict ACID Transactional Consistency |

---

## Table of Contents

1. [Module Overview & Scope](#1-module-overview--scope)
   - [1.1 Core Responsibility](#11-core-responsibility)
   - [1.2 Domain Position](#12-domain-position)
   - [1.3 Explicit Boundary Limits](#13-explicit-boundary-limits)
2. [Package Structure & Code Layout](#2-package-structure--code-layout)
   - [2.1 Directory Layout](#21-directory-layout)
   - [2.2 Component Roles & Responsibilities](#22-component-roles--responsibilities)
3. [Data Storage & Schema Design](#3-data-storage--schema-design)
   - [3.1 Relational Database Schema (PostgreSQL)](#31-relational-database-schema-postgresql)
   - [3.2 DDL Schema Definitions](#32-ddl-schema-definitions)
   - [3.3 Redis In-Memory Architecture Policy](#33-redis-in-memory-architecture-policy)
4. [Interfaces & Event Contracts](#4-interfaces--event-contracts)
   - [4.1 External REST API Endpoints](#41-external-rest-api-endpoints)
   - [4.2 Internal Module Facade (`OrderFacade`)](#42-internal-module-facade-orderfacade)
   - [4.3 Domain Events & Transactional Outbox Pattern](#43-domain-events--transactional-outbox-pattern)
5. [Concurrency Control, Failure Modes & Edge Cases](#5-concurrency-control-failure-modes--edge-cases)
   - [5.1 End-to-End Checkout Workflow](#51-end-to-end-checkout-workflow)
   - [5.2 Transactional Atomicity & Boundary Guarantees](#52-transactional-atomicity--boundary-guarantees)
   - [5.3 Single-Use Hold & Stock Restoration Mechanics](#53-single-use-hold--stock-restoration-mechanics)
   - [5.4 Custom Domain Exceptions & HTTP Mapping](#54-custom-domain-exceptions--http-mapping)
   - [5.5 Edge Case Mitigation Matrix](#55-edge-case-mitigation-matrix)

---

## 1. Module Overview & Scope

### 1.1 Core Responsibility
The **Order Module** acts as the ultimate transactional authority for final purchase completion within the platform. It is responsible for converting active, validated temporary ticket holds into permanent, immutable database order ledgers within ACID-compliant SQL transactions. 

Specifically, the Order Module:
- Validates and consumes active hold tokens generated during the reservation phase.
- Records detailed order records and associated line items snapshotting tier prices and names.
- Coordinates synchronously with the payment module for transaction processing/verification.
- Writes structured event messages to a **Transactional Outbox** table within the same database transaction to guarantee reliable post-purchase async fulfillment (e.g., ticket rendering, email notifications).

### 1.2 Domain Position
- **Phase Context:** Phase 1 (MVP Foundation).
- **Lifecycle Role:** Completes the purchase lifecycle by persisting final orders and serving as the single source of truth for transaction status (`PENDING`, `CONFIRMED`, `FAILED`, `REFUNDED`).

### 1.3 Explicit Boundary Limits
To prevent domain coupling and maintain clean micro-architecture boundaries, the Order Module is **strictly forbidden** from:
- **Checking or modifying stock counts directly:** Inventory decrement checks and reservation management are exclusively owned by the `catalog` and `hold` modules.
- **Managing queue line positions:** Queue state and traffic shaping are exclusively owned by the `queue` module.
- **Generating PDF ticket assets or dispatching emails:** Document generation and communications are asynchronous downstream concerns owned by the `notification` module.
- **Directly communicating with payment gateways:** External payment gateway integrations (e.g., Stripe) are isolated behind the `payment` module facade.

```
+-----------------------------------------------------------------------------------+
|                                   ORDER MODULE                                    |
|                                                                                   |
|  +--------------------+     +---------------------+     +----------------------+  |
|  |  Order Controller  | --> |    Order Service    | --> | PostgreSQL Database  |  |
|  +--------------------+     +---------------------+     +----------------------+  |
+-----------|----------------------------|----------------------------|-------------+
            |                            |                            |
            v                            v                            v
   [ Client Requests ]          [ Facade Calls ]             [ Transactional Outbox ]
  - POST /checkout             - HoldFacade.consumeHold()     - outbox_events table
  - GET /{orderNumber}         - Payment Processing           - Picked up by Async Worker
```

---

## 2. Package Structure & Code Layout

### 2.1 Directory Layout
The module follows standard domain package structuring under `com.app.order`:

```
com.app.order
├── controller
│   └── OrderController.java          # REST endpoints for checkouts & confirmation lookups
├── service
│   ├── OrderService.java             # Core business logic interface
│   └── impl
│       └── OrderServiceImpl.java     # Orchestrates checkout, payment, outbox, and ACID tx
├── facade
│   ├── OrderFacade.java              # Internal Java interface exposed to other modules
│   └── impl
│       └── OrderFacadeImpl.java      # Implementation executing in-memory cross-module calls
├── repository
│   ├── OrderRepository.java          # Spring Data JPA repository for Order entities
│   ├── OrderItemRepository.java      # Spring Data JPA repository for OrderItem entities
│   └── OutboxEventRepository.java    # Spring Data JPA repository for OutboxEvent entities
├── model
│   ├── Order.java                    # JPA Entity mapped to `orders` table
│   ├── OrderItem.java                # JPA Entity mapped to `order_items` table
│   ├── OutboxEvent.java              # JPA Entity mapped to `outbox_events` table
│   └── OrderStatus.java              # Enum: PENDING, CONFIRMED, FAILED, REFUNDED
├── dto
│   ├── request
│   │   └── CheckoutRequestDTO.java   # Payload submitting holdToken & buyer info
│   ├── response
│   │   ├── OrderReceiptDTO.java      # Customer-facing post-purchase summary
│   │   └── OrderItemDTO.java         # Line item details inside receipt
│   └── internal
│       └── OrderSummaryDTO.java      # Immutable object for inter-module status checks
├── event
│   └── OrderConfirmedEvent.java      # Strongly-typed domain event contract
└── exception
    ├── InvalidHoldException.java     # Mapped to HTTP 409 Conflict
    ├── OrderNotFoundException.java   # Mapped to HTTP 404 Not Found
    └── PaymentFailedException.java   # Mapped to HTTP 402 Payment Required
```

### 2.2 Component Roles & Responsibilities

| Component | Responsibility |
| :--- | :--- |
| **`OrderController`** | Handles HTTP requests for checkout submissions and receipt retrieval. Validates request DTO constraints. |
| **`OrderServiceImpl`** | Implements transaction orchestration: calls `HoldFacade`, verifies payment, writes `Order`, `OrderItem`, and `OutboxEvent` within a single `@Transactional` boundary. |
| **`OrderFacadeImpl`** | Provides high-performance, synchronous in-memory methods for other backend modules requiring order status verification. |
| **Repositories** | Provides Spring Data JPA database abstractions for PostgreSQL table operations. |
| **Entities (`model`)** | Object-Relational Mapping (ORM) classes defining DB schemas, entity relationships, and constraints. |
| **DTOs** | Strongly typed, immutable data contracts for API boundaries and inter-module communication. |

---

## 3. Data Storage & Schema Design

### 3.1 Relational Database Schema (PostgreSQL)

The Order Module utilizes PostgreSQL to maintain an immutable audit trail of purchases and pending notification events.

```
       +------------------------------------+
       |               orders               |
       +------------------------------------+
       | PK  id                 BIGINT      |
       | U   order_number       VARCHAR(64) |
       |     user_session_id    VARCHAR(255)|
       |     user_email         VARCHAR(255)|
       |     total_amount_cents BIGINT      |
       |     status             VARCHAR(32) |
       |     created_at         TIMESTAMPTZ |
       |     updated_at         TIMESTAMPTZ |
       +------------------------------------+
                         | 1
                         |
                         | N
       +------------------------------------+
       |            order_items             |
       +------------------------------------+
       | PK  id                 BIGINT      |
       | FK  order_id           BIGINT      |
       |     event_id           BIGINT      |
       |     tier_id            BIGINT      |
       |     tier_name          VARCHAR(100)|
       |     quantity           INT         |
       |     unit_price_cents   BIGINT      |
       |     created_at         TIMESTAMPTZ |
       +------------------------------------+

       +------------------------------------+
       |           outbox_events            |
       +------------------------------------+
       | PK  id                 UUID        |
       |     aggregate_type     VARCHAR(64) |
       |     aggregate_id       VARCHAR(64) |
       |     event_type         VARCHAR(64) |
       |     payload            JSONB       |
       |     status             VARCHAR(32) |
       |     created_at         TIMESTAMPTZ |
       +------------------------------------+
```

#### Table: `orders`
Primary ledger table tracking high-level order state and buyer details.

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | `PRIMARY KEY`, Auto-increment | Internal database sequence identifier. |
| `order_number` | `VARCHAR(64)` | `UNIQUE`, `NOT NULL`, `INDEXED` | Human-readable public reference code (e.g., `TK-98213`). |
| `user_session_id` | `VARCHAR(255)` | `NOT NULL` | Guest/user session identifier linked to checkout. |
| `user_email` | `VARCHAR(255)` | `NOT NULL` | Buyer's validated email address for ticket delivery. |
| `total_amount_cents` | `BIGINT` | `NOT NULL` | Aggregate order cost in smallest currency unit (e.g., cents/pence). |
| `status` | `VARCHAR(32)` | `NOT NULL` | Order lifecycle state: `PENDING`, `CONFIRMED`, `FAILED`, `REFUNDED`. |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Timestamp of initial order placement. |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Timestamp of last state update. |

#### Table: `order_items`
Snapshot table storing historical details of tickets purchased in an order.

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | `PRIMARY KEY`, Auto-increment | Unique line item identifier. |
| `order_id` | `BIGINT` | `FOREIGN KEY` -> `orders(id)`, `NOT NULL` | Reference to parent order. |
| `event_id` | `BIGINT` | `NOT NULL` | Associated event identifier. |
| `tier_id` | `BIGINT` | `NOT NULL` | Associated ticket tier identifier. |
| `tier_name` | `VARCHAR(100)` | `NOT NULL` | Historic snapshot of tier name at time of purchase. |
| `quantity` | `INT` | `NOT NULL` | Number of tickets purchased in this line item. |
| `unit_price_cents` | `BIGINT` | `NOT NULL` | Historic snapshot of unit ticket price at time of purchase. |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Record creation timestamp. |

#### Table: `outbox_events`
Transactional outbox repository ensuring atomicity between database updates and asynchronous event dispatching.

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY` | Unique event message identifier. |
| `aggregate_type` | `VARCHAR(64)` | `NOT NULL` | Domain entity category (e.g., `ORDER`). |
| `aggregate_id` | `VARCHAR(64)` | `NOT NULL` | Identifier of affected entity (stores `order_number`). |
| `event_type` | `VARCHAR(64)` | `NOT NULL` | Specific event classification (e.g., `ORDER_CONFIRMED`). |
| `payload` | `JSONB` | `NOT NULL` | Full structured event metadata in JSON format. |
| `status` | `VARCHAR(32)` | `NOT NULL` | Outbox dispatch status: `PENDING`, `PROCESSED`, `FAILED`. |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Event creation timestamp. |

---

### 3.2 DDL Schema Definitions

```sql
-- Create orders table
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    order_number VARCHAR(64) NOT NULL UNIQUE,
    user_session_id VARCHAR(255) NOT NULL,
    user_email VARCHAR(255) NOT NULL,
    total_amount_cents BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_orders_order_number ON orders(order_number);
CREATE INDEX idx_orders_user_email ON orders(user_email);

-- Create order_items table
CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    event_id BIGINT NOT NULL,
    tier_id BIGINT NOT NULL,
    tier_name VARCHAR(100) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    unit_price_cents BIGINT NOT NULL CHECK (unit_price_cents >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_order_items_order_id ON order_items(order_id);

-- Create outbox_events table
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_outbox_events_status_created ON outbox_events(status, created_at);
```

---

### 3.3 Redis In-Memory Architecture Policy

The Order Module maintains a **zero-direct-ownership** policy regarding Redis storage:
- **No Direct Redis Keys:** To avoid state divergence and memory leaks, the Order Module does not directly read or write Redis keys.
- **Facade Abstraction:** Hold validation and state changes in Redis are strictly delegated to `HoldFacade.consumeHold(holdToken)` and `HoldFacade.releaseHold(holdToken)`.
- **Relational Integrity:** The order module relies entirely on PostgreSQL ACID transactions to guarantee that order persistence, snapshotting, and event outbox generation occur atomically.

---

## 4. Interfaces & Event Contracts

### 4.1 External REST API Endpoints

#### Summary Table

| Method | Endpoint Path | Description | Access Level | Request Body | Response Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/orders/checkout` | Validates hold token, processes payment, creates order & outbox records | Public | `CheckoutRequestDTO` | `201 Created` / Error |
| `GET` | `/api/v1/orders/{orderNumber}` | Retrieves permanent order confirmation receipt by order number | Public | None | `200 OK` / `404` |

#### Endpoint Specifications

##### 1. Checkout Submission
* **`POST /api/v1/orders/checkout`**
* **Request Payload (`CheckoutRequestDTO`):**

```json
{
  "holdToken": "hld_9f8b2c1a-4d3e-2f10-b98a-7c6d5e4f3a2b",
  "userSessionId": "sess_8812a34b5c",
  "userEmail": "customer@example.com",
  "paymentMethodDetails": {
    "paymentToken": "tok_visa_mock_12345"
  }
}
```

* **Success Response (`201 Created` - `OrderReceiptDTO`):**

```json
{
  "orderNumber": "TK-98213",
  "status": "CONFIRMED",
  "userEmail": "customer@example.com",
  "totalAmountCents": 15000,
  "createdAt": "2026-08-28T17:42:35Z",
  "items": [
    {
      "eventId": 101,
      "tierId": 501,
      "tierName": "VIP Admission",
      "quantity": 2,
      "unitPriceCents": 7500
    }
  ]
}
```

##### 2. Get Order Confirmation
* **`GET /api/v1/orders/TK-98213`**
* **Success Response (`200 OK` - `OrderReceiptDTO`):** Returns the exact same `OrderReceiptDTO` format shown above.

---

### 4.2 Internal Module Facade (`OrderFacade`)

Exposes synchronous Java methods for in-memory, zero-network-overhead cross-module service invocation.

```java
package com.app.order.facade;

import com.app.order.dto.internal.OrderSummaryDTO;

public interface OrderFacade {
    
    /**
     * Accepts an order number and returns an immutable order summary.
     * Used by internal services (e.g., support tools, analytics) to inspect order state.
     */
    OrderSummaryDTO getOrderSummary(String orderNumber);

    /**
     * Quick status evaluation check.
     * Returns true if the specified order exists and has status == CONFIRMED.
     */
    boolean isOrderConfirmed(String orderNumber);
}
```

#### DTO Definition (`OrderSummaryDTO`)

```java
package com.app.order.dto.internal;

import java.time.Instant;

public record OrderSummaryDTO(
    String orderNumber,
    String userEmail,
    String status,
    long totalAmountCents,
    int totalTicketCount,
    Instant createdAt
) {}
```

---

### 4.3 Domain Events & Transactional Outbox Pattern

#### Outbox Event Contract: `OrderConfirmedEvent`
When an order is successfully processed, an event payload is serialized to JSON and inserted into `outbox_events`.

```json
{
  "eventId": "evt_73a19e24-118c-4932-a50d-bc019283f123",
  "aggregateType": "ORDER",
  "aggregateId": "TK-98213",
  "eventType": "ORDER_CONFIRMED",
  "timestamp": "2026-08-28T17:42:35Z",
  "payload": {
    "orderNumber": "TK-98213",
    "userEmail": "customer@example.com",
    "eventId": 101,
    "tierName": "VIP Admission",
    "quantity": 2,
    "totalAmountCents": 15000,
    "confirmedAt": "2026-08-28T17:42:35Z"
  }
}
```

#### Outbox Lifecycle State Machine
1. **PENDING:** Inserted atomically into PostgreSQL within `@Transactional` checkout method.
2. **PROCESSED:** Picked up by an asynchronous outbox worker, dispatched to Kafka/RabbitMQ/Notification Module, and marked as `PROCESSED`.
3. **FAILED:** If delivery fails after max retries, flagged as `FAILED` for dead-letter handling.

---

## 5. Concurrency Control, Failure Modes & Edge Cases

### 5.1 End-to-End Checkout Workflow

```
Client             OrderController        OrderServiceImpl         HoldFacade            PaymentModule          PostgreSQL
  |                       |                      |                     |                      |                     |
  |-- POST /checkout ---->|                      |                     |                      |                     |
  |                       |-- checkout(dto) ---->|                     |                      |                     |
  |                       |                      |-- consumeHold() --->|                      |                     |
  |                       |                      |<-- Hold Valid ------|                      |                     |
  |                       |                      |                                            |                     |
  |                       |                      |-- Process Payment ------------------------>|                     |
  |                       |                      |<-- Payment Success ------------------------|                     |
  |                       |                      |                                                                  |
  |                       |                      |================ BEGIN SQL TRANSACTION ===========================|
  |                       |                      |-- INSERT INTO orders (status=CONFIRMED) ------------------------>|
  |                       |                      |-- INSERT INTO order_items -------------------------------------->|
  |                       |                      |-- INSERT INTO outbox_events (status=PENDING) -------------------->|
  |                       |                      |================ COMMIT SQL TRANSACTION =========================|
  |                       |                      |                                                                  |
  |<-- 201 Created -------|<-- OrderReceiptDTO --|                                                                  |
```

---

### 5.2 Transactional Atomicity & Boundary Guarantees
- **Atomic Spring `@Transactional` Scope:** The persistence of `orders`, `order_items`, and `outbox_events` takes place inside a single read-write Spring database transaction.
- **Rollback Guarantee:** If any step within the database transaction fails (e.g., SQL constraint violation, outbox write error), the entire database transaction rolls back automatically.
- **No Ghost Purchases:** Because the outbox message is stored in the same database engine as the order ledger, it is impossible for an order to be saved without an event being scheduled, or for an event to be scheduled for an uncommitted order.

---

### 5.3 Single-Use Hold & Stock Restoration Mechanics

```
                       +---------------------------+
                       | Incoming Checkout Request |
                       +---------------------------+
                                     |
                                     v
                       +---------------------------+
                       | HoldFacade.consumeHold()  |
                       +---------------------------+
                                 /       \
                       Success  /         \ Invalid / Expired / Used
                               v           v
           +-----------------------+   +-------------------------------+
           | Attempt Payment Exec  |   | Throw InvalidHoldException    |
           +-----------------------+   | Return HTTP 409 Conflict      |
                       /       \       +-------------------------------+
               Success/         \ Payment Failed / Declined
                     v           v
   +--------------------+     +--------------------------------+
   | Execute DB Commit  |     | HoldFacade.releaseHold()       |
   | (Order + Outbox)   |     | Restores Stock to Redis RAM    |
   +--------------------+     +--------------------------------+
                               |
                               v
                              +--------------------------------+
                              | Throw PaymentFailedException   |
                              | Return HTTP 402 Payment Req    |
                              +--------------------------------+
```

1. **Single-Use Hold Guarantee:**
   - Before executing payment or writing DB records, `HoldFacade.consumeHold(holdToken)` is invoked.
   - If the hold is missing, expired, or previously used by a duplicate/racing request, an `InvalidHoldException` is thrown, halting execution before payment or DB writes occur.
2. **Payment Failure Stock Restoration:**
   - If payment processing fails or gets declined by the gateway, the transaction aborts.
   - The service explicitly triggers `HoldFacade.releaseHold(holdToken)`, restoring the reserved ticket quantity back to Redis RAM immediately so another user can purchase it.

---

### 5.4 Custom Domain Exceptions & HTTP Mapping

| Custom Exception | Cause / Scenario | Mapped HTTP Status | API Error Code |
| :--- | :--- | :--- | :--- |
| **`InvalidHoldException`** | Token is expired, invalid, or already consumed | `409 Conflict` | `HOLD_EXPIRED_OR_INVALID` |
| **`PaymentFailedException`** | Card decline, insufficient funds, or gateway timeout | `402 Payment Required` | `PAYMENT_FAILED` |
| **`OrderNotFoundException`** | Querying `/orders/{orderNumber}` with invalid reference | `404 Not Found` | `ORDER_NOT_FOUND` |

---

### 5.5 Edge Case Mitigation Matrix

| Potential Failure Point | Edge Case Scenario | System Mitigation Strategy |
| :--- | :--- | :--- |
| **Concurrent Double Submission** | Client double-clicks submit, sending twin HTTP requests with same `holdToken`. | Atomic `HoldFacade.consumeHold()` operation guarantees only one request successfully consumes the hold token; second request immediately receives `409 Conflict`. |
| **Payment Success, DB Crash** | Gateway charges card, but DB crashes during `Order` insert. | Payment execution is wrapped in try-catch; if DB commit fails, an automatic compensating refund request is dispatched to `PaymentFacade`, and outbox event is rolled back. |
| **Async Notification Service Down** | Downstream email/PDF service is offline during checkout. | Unaffected. Order is persisted safely with `PENDING` outbox record. Outbox worker retries delivery once downstream notification service recovers. |
| **Stale Read during Lookup** | Client queries `GET /orders/{orderNumber}` immediately after creation. | Order number index on PostgreSQL ensures fast indexed retrieval (`<2ms`). Database read committed isolation ensures immediate availability post-commit. |
