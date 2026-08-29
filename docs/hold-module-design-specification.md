# Module Design Specification: Hold Module (`com.app.hold`)

**Document Version:** 1.0.0  
**Status:** Approved for Implementation  
**Architecture Phase:** Phase 1 (MVP Foundation) & Phase 2 (Concurrency & Distributed Upgrade)  
**Target Platform:** Java 21 / Spring Boot 3.x / PostgreSQL / Redis  

---

## 1. Module Overview & Scope

### 1.1 Core Responsibility
The **Hold Module** (`com.app.hold`) is responsible for managing temporary, time-bound ticket reservations (seat holds) for users who have successfully progressed through the queue system. It acts as an isolation barrier between real-time inventory demand and permanent order fulfillment.

Key responsibilities include:
* **Exclusive Inventory Reservation:** Atomically reserving requested ticket quantities for a strict, non-extendable duration (default: **300 seconds / 5 minutes**).
* **Checkout Guarantee:** Guaranteeing that reserved stock cannot be claimed by other concurrent buyers during the active hold window.
* **Automated Stock Restoration:** Instantly returning held inventory back to the available pool upon hold expiration, manual release, or transaction failure.
* **Lifecycle Audit Ledger:** Maintaining a durable historical log of all hold attempts, state transitions, and expirations for reporting and reconciliation.

```
                  +-----------------------------------+
                  |        Queue Pass Token          |
                  +-----------------------------------+
                                    |
                                    v
+-----------------------------------------------------------------------+
|                             HOLD MODULE                               |
|                                                                       |
|   +------------------+     +------------------+    +--------------+   |
|   | Atomic Reservation| --> | TTL Timer (300s) | ->| Audit Ledger |   |
|   +------------------+     +------------------+    +--------------+   |
+-----------------------------------------------------------------------+
            |                               |
            v                               v
+-----------------------+       +-----------------------+
|  Active Reservation   |       |  Auto-Restoration     |
| (Proceed to Checkout) |       | (Expired / Canceled)  |
+-----------------------+       +-----------------------+
```

### 1.2 Domain Positioning Across System Phases
* **Phase 1 (MVP Foundation):** Synchronous inventory verification, PostgreSQL database-backed reservation logs, basic Spring event notifications, and fixed-TTL timer tracking.
* **Phase 2 (Concurrency Upgrade):** High-throughput microsecond Redis atomic holds, distributed lock coordination (Redisson), Redis Keyspace Notifications for asynchronous stock restoration, and background reconciliation sweepers.

### 1.3 Explicit Boundary Limits
To maintain clear domain separation and prevent monolithic coupling, the **Hold Module** is strictly prohibited from executing the following responsibilities:

| Domain Area | Owning Module | Strictly Forbidden Action in Hold Module |
| :--- | :--- | :--- |
| **Payment Processing** | `com.app.payment` | Processing credit card charges, interacting with payment gateways, or handling refunds. |
| **Pricing & Metadata** | `com.app.catalog` | Calculating tier prices, applying discounts, dynamic pricing rules, or modifying venue seating maps. |
| **Queue Management** | `com.app.queue` | Validating queue positions, line priority rankings, or issuing queue pass tokens. |
| **Order Creation** | `com.app.order` | Generating invoices, persisting permanent order records, or managing shipping/digital ticket delivery. |

---

## 2. Package Structure & Code Layout

The module follows standard domain-driven package organization rooted under `com.app.hold`:

```
com.app.hold
├── config
│   ├── HoldProperties.java            # Configuration properties (TTL, sweep intervals)
│   └── RedisKeyspaceConfig.java       # Redis listener and container configuration
├── controller
│   └── HoldController.java            # REST Endpoints for holds management
├── dto
│   ├── request
│   │   └── CreateHoldRequestDTO.java  # Inbound reservation payload
│   ├── response
│   │   ├── HoldResponseDTO.java       # API response with token & remaining TTL
│   │   └── HoldStatusDTO.java         # Public verification payload
│   └── internal
│       └── HoldSummaryDTO.java        # Cross-module data transfer object
├── event
│   ├── TicketHeldEvent.java           # Internal Spring domain event (Hold Created)
│   ├── TicketHoldExpiredEvent.java    # Internal Spring domain event (Hold Expired)
│   ├── TicketHoldConsumedEvent.java   # Internal Spring domain event (Hold Consumed)
│   └── TicketHoldReleasedEvent.java   # Internal Spring domain event (Hold Canceled)
├── exception
│   ├── HoldException.java             # Base domain exception
│   ├── HoldNotFoundException.java     # 404 Exception
│   ├── HoldExpiredException.java      # 410 Exception
│   └── InsufficientStockException.java # 409 Exception
├── facade
│   ├── HoldFacade.java                # Inter-module interface exposed to order/payment
│   └── impl
│       └── HoldFacadeImpl.java        # Secure implementation of HoldFacade
├── model
│   ├── HoldStatus.java                # Enum: ACTIVE, CONSUMED, EXPIRED, RELEASED
│   └── TicketHoldEntity.java          # JPA Entity for ticket_holds table
├── repository
│   ├── TicketHoldJpaRepository.java   # PostgreSQL spring data repository
│   └── HoldRedisRepository.java       # Redis Template operations & Lua scripts
└── service
    ├── HoldService.java               # Main business interface
    ├── impl
    │   └── HoldServiceImpl.java       # Business logic implementation
    ├── RedisKeyspaceListener.java     # Listener for expired Redis keys
    └── HoldReconciliationSweeper.java # Scheduled fallback job for orphaned holds
```

---

## 3. Data Storage & Schema Design

### 3.1 PostgreSQL Tables (Relational Audit Ledger)

The `ticket_holds` table serves as an append-friendly relational ledger tracking every reservation request and its terminal outcome.

#### Table Definition: `ticket_holds`

```sql
CREATE TABLE ticket_holds (
    id                  BIGSERIAL PRIMARY KEY,
    hold_token          VARCHAR(64) NOT NULL UNIQUE,
    user_session_id     VARCHAR(255) NOT NULL,
    event_id            BIGINT NOT NULL,
    tier_id             BIGINT NOT NULL,
    quantity            INT NOT NULL CHECK (quantity > 0),
    status              VARCHAR(32) NOT NULL,
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance optimization
CREATE UNIQUE INDEX idx_ticket_holds_token ON ticket_holds(hold_token);
CREATE INDEX idx_ticket_holds_session ON ticket_holds(user_session_id);
CREATE INDEX idx_ticket_holds_event_tier ON ticket_holds(event_id, tier_id);
CREATE INDEX idx_ticket_holds_status_expires ON ticket_holds(status, expires_at) 
    WHERE status = 'ACTIVE';
```

#### Field Specifications

| Column Name | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | Primary Key, Auto-increment | Unique internal surrogate identifier. |
| `hold_token` | `VARCHAR(64)` | Unique, Not Null, Indexed | Cryptographically unique UUID string returned to caller as proof of reservation. |
| `user_session_id` | `VARCHAR(255)` | Not Null, Indexed | User session or guest identifier associated with the queue pass. |
| `event_id` | `BIGINT` | Not Null | Target event database identifier. |
| `tier_id` | `BIGINT` | Not Null | Target ticket tier database identifier (e.g., VIP, General Admission). |
| `quantity` | `INT` | Not Null, `> 0` | Number of tickets reserved under this specific hold. |
| `status` | `VARCHAR(32)` | Not Null | Reservation state: `ACTIVE`, `CONSUMED`, `EXPIRED`, `RELEASED`. |
| `expires_at` | `TIMESTAMPTZ` | Not Null | Target expiration timestamp (Created Time + 300 Seconds). |
| `created_at` | `TIMESTAMPTZ` | Not Null | Initial UTC creation timestamp. |
| `updated_at` | `TIMESTAMPTZ` | Not Null | Last state modification UTC timestamp. |

---

### 3.2 Redis In-Memory Architecture & Design

Redis serves as the primary, high-throughput reservation engine capable of executing microsecond stock decrements without hitting disk-bound relational storage during peak demand sales.

#### Key Naming Scheme & Data Types

##### 1. Hold Record Key
* **Pattern:** `hold:{holdToken}`
* **Data Type:** `Hash`
* **TTL Policy:** **300 seconds (5 minutes)**. Hard expiration enforced by Redis TTL engine.
* **Hash Fields:**
  * `userSessionId`: `string`
  * `eventId`: `string`
  * `tierId`: `string`
  * `quantity`: `string` (integer representation)
  * `status`: `string` (`ACTIVE`, `CONSUMED`, `RELEASED`)
  * `expiresAt`: `string` (ISO-8601 string)

##### 2. Inventory Counter Key Interaction
* **Pattern:** `catalog:stock:{eventId}:{tierId}`
* **Data Type:** `String` (Atomic Integer owned by `catalog`, mutated atomically by `hold`).

---

### 3.3 Atomic Hold Creation & Stock Decrement Algorithm

To eliminate race conditions and overbooking when thousands of requests target the same ticket tier, the hold module uses atomic Lua scripting in Redis.

```
Client Request (Hold N Tickets)
           |
           v
+-------------------------------------------------------------------+
| Executing Redis Lua Script                                        |
| 1. Read catalog:stock:{eventId}:{tierId}                          |
| 2. Check: Is (Stock - N) >= 0 ?                                   |
+-------------------------------------------------------------------+
           |                                       |
     [ YES | >= 0 ]                          [ NO  | < 0 ]
           v                                       v
+----------------------------------+   +----------------------------+
| 1. Decrement Stock by N          |   | 1. Abort Transaction       |
| 2. Create Hash hold:{holdToken}  |   | 2. Return Error Code:      |
| 3. Set Hash TTL = 300 Seconds    |   |    INSUFFICIENT_STOCK      |
| 4. Return Success                |   +----------------------------+
+----------------------------------+               |
           |                                       v
           v                           Throw InsufficientStockException
Persist Async Audit Entry in Postgres           (HTTP 409 Conflict)
```

#### Atomic Lua Script (`hold_reserve.lua`)
```lua
local stockKey = KEYS[1]
local holdKey = KEYS[2]
local quantity = tonumber(ARGV[1])
local holdTTL = tonumber(ARGV[2])
local userSessionId = ARGV[3]
local eventId = ARGV[4]
local tierId = ARGV[5]
local expiresAt = ARGV[6]

local currentStock = tonumber(redis.call('GET', stockKey) or "0")

if currentStock < quantity then
    return -1 -- Code -1: Insufficient Stock
end

redis.call('DECRBY', stockKey, quantity)
redis.call('HSET', holdKey, 
    'userSessionId', userSessionId,
    'eventId', eventId,
    'tierId', tierId,
    'quantity', tostring(quantity),
    'status', 'ACTIVE',
    'expiresAt', expiresAt
)
redis.call('EXPIRE', holdKey, holdTTL)

return 1 -- Code 1: Hold Successful
```

---

### 3.4 Automated Expiration & Restoration Workflow

When a user abandons their reservation, tickets must return to available stock immediately upon expiration.

1. **Expiration Trigger:** The Redis key `hold:{holdToken}` reaches 300s TTL and expires.
2. **Keyspace Notification:** Redis publishes an expired event over `__keyevent@0__:expired`.
3. **Notification Interception:** `RedisKeyspaceListener` intercepts the expired event key `hold:{holdToken}`.
4. **Stock Restoration:** The listener reads the shadow backup record or extracts metadata, issuing an atomic `INCRBY catalog:stock:{eventId}:{tierId} {quantity}`.
5. **Database Audit Update:** The system asynchronously updates PostgreSQL `ticket_holds` setting `status = 'EXPIRED'`.
6. **Domain Event Fired:** Triggers internal `TicketHoldExpiredEvent`.

---

### 3.5 Ownership & Interaction Boundaries

```
+------------------+          Holds Verification / Consume          +------------------+
|   order Module   | ---------------------------------------------> |   hold Module    |
+------------------+                                                +------------------+
         |                                                                    |
         | Finalizes Order                                                    | Manages TTL, Redis
         v                                                                    | Keys & Restoration
+------------------+             Atomic Stock Increment               +------------------+
|  catalog Module  | <----------------------------------------------- |   Redis Cache    |
+------------------+                                                +------------------+
```

* **`hold` Module Ownership:** Manages creation of hold keys, TTL timers, keyspace expiration listeners, token validation, and state transitions (`ACTIVE` -> `CONSUMED` / `RELEASED` / `EXPIRED`).
* **`order` Module Interaction:** During checkout, the `order` module calls `HoldFacade.consumeHold(holdToken)`. The hold state transitions to `CONSUMED`, and the Redis TTL key is deleted to prevent stock restoration.

---

## 4. Interfaces & Event Contracts

### 4.1 External REST Endpoints

All external HTTP endpoints are exposed under `/api/v1/holds` and require a valid Queue Pass Token passed via custom header `X-Queue-Pass-Token`.

| Method | Endpoint Path | Description | Access Level | Headers / Parameters | Success Status | Error Codes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`POST`** | `/api/v1/holds` | Reserve temporary tickets for a specific tier | Public (with Queue Pass Token) | Header: `X-Queue-Pass-Token`<br>Body: `CreateHoldRequestDTO` | `201 Created` | `400 Bad Request`<br>`401 Unauthorized`<br>`409 Conflict` |
| **`GET`** | `/api/v1/holds/{holdToken}` | Fetch current status and remaining TTL seconds of a hold | Public | Path: `holdToken` | `200 OK` | `404 Not Found`<br>`410 Gone` |
| **`DELETE`** | `/api/v1/holds/{holdToken}` | Manually cancel/release a hold before expiration | Public | Path: `holdToken`<br>Header: `X-Session-ID` | `200 OK` | `404 Not Found`<br>`400 Bad Request` |

#### Endpoint Details & Payloads

##### `POST /api/v1/holds`
* **Request Body (`CreateHoldRequestDTO`):**
  ```json
  {
    "eventId": 10024,
    "tierId": 501,
    "quantity": 2,
    "userSessionId": "sess_982347a28f9011"
  }
  ```
* **Response Body (`HoldResponseDTO`):**
  ```json
  {
    "holdToken": "hld_uuid_88f19203a11b42ef891",
    "status": "ACTIVE",
    "eventId": 10024,
    "tierId": 501,
    "quantity": 2,
    "expiresAt": "2026-08-28T17:44:39Z",
    "ttlRemainingSeconds": 300
  }
  ```

---

### 4.2 Internal Module Facade (`HoldFacade`)

Exposes synchronous Java interfaces for securely interacting with other backend modules (`com.app.order`, `com.app.payment`).

```java
package com.app.hold.facade;

import com.app.hold.dto.internal.HoldSummaryDTO;
import com.app.hold.exception.HoldExpiredException;
import com.app.hold.exception.HoldNotFoundException;

public interface HoldFacade {

    /**
     * Atomically validates and consumes an active hold during checkout.
     * Marks the hold status as CONSUMED, cancels the Redis TTL timer,
     * and returns the reservation details.
     *
     * @param holdToken Cryptographic hold UUID string
     * @return HoldSummaryDTO Details of the consumed hold
     * @throws HoldNotFoundException if holdToken does not exist
     * @throws HoldExpiredException if hold is past TTL or already consumed
     */
    HoldSummaryDTO validateAndConsumeHold(String holdToken);

    /**
     * Manually releases a hold and restores stock immediately.
     * Used by payment/order modules upon transaction rejection.
     *
     * @param holdToken Cryptographic hold UUID string
     * @param reason Internal release reason (e.g., PAYMENT_FAILED, USER_CANCELED)
     */
    void releaseHold(String holdToken, String reason);

    /**
     * Verifies whether a valid active hold exists for a given user session.
     *
     * @param holdToken Cryptographic hold UUID string
     * @param userSessionId Unique guest session ID
     * @return true if hold is ACTIVE and unexpired, false otherwise
     */
    boolean isHoldActiveForSession(String holdToken, String userSessionId);
}
```

---

### 4.3 Domain Events Contract

Domain events are published internally using Spring’s `ApplicationEventPublisher` (and mapped to Kafka topics in Phase 2).

#### 1. `TicketHeldEvent`
* **Trigger:** Published immediately after a successful ticket reservation.
* **Payload:**
  ```java
  public record TicketHeldEvent(
      String holdToken,
      String userSessionId,
      Long eventId,
      Long tierId,
      Integer quantity,
      Instant expiresAt,
      Instant timestamp
  ) {}
  ```
* **Purpose:** Notifies real-time analytics and session monitors that inventory is reserved.

#### 2. `TicketHoldExpiredEvent`
* **Trigger:** Fired by `RedisKeyspaceListener` or `HoldReconciliationSweeper` when a timer expires.
* **Payload:**
  ```java
  public record TicketHoldExpiredEvent(
      String holdToken,
      Long eventId,
      Long tierId,
      Integer quantityRestored,
      Instant timestamp
  ) {}
  ```
* **Purpose:** Triggers stock replenishment alerts and pushes web-socket status updates to UI clients.

---

## 5. Concurrency Control, Failure Modes & Edge Cases

### 5.1 Concurrency Matrix & Safeguards

```
+---------------------------------------+-------------------------------------------------------------+
| Potential Race Condition / Edge Case  | Mitigation Mechanism                                        |
+---------------------------------------+-------------------------------------------------------------+
| Simultaneous multi-thousand requests  | Microsecond atomic decrements in Redis Lua script executed  |
| for remaining stock                   | before opening PostgreSQL database transactions.             |
+---------------------------------------+-------------------------------------------------------------+
| Double consumption of hold token      | Atomic Redis GET-AND-SET + PostgreSQL pessimistic locking   |
| by repeated checkout clicks           | enforcing single-use transition to CONSUMED state.          |
+---------------------------------------+-------------------------------------------------------------+
| Missed Redis Keyspace expiration event| Scheduled background sweeper (`HoldReconciliationSweeper`)  |
| due to network blip                   | scanning PostgreSQL for orphaned ACTIVE holds past TTL.      |
+---------------------------------------+-------------------------------------------------------------+
| Late checkout submission right on     | Monotonic clock comparisons in HoldFacade enforcing hard    |
| second 300 boundary                   | expiration refusal if current_time > expires_at.           |
+---------------------------------------+-------------------------------------------------------------+
```

### 5.2 Graceful Redis Listener Failures & Reconciliation Sweeper

Redis Keyspace events are delivered via a pub/sub model, which is non-durable (at-most-once delivery). If the application node crashes or experiences network degradation when an expiration fires, the notification might be lost.

#### Background Sweeper Implementation Strategy
A Spring `@Scheduled` task runs periodically (e.g., every 30 seconds):
1. Queries PostgreSQL: `SELECT * FROM ticket_holds WHERE status = 'ACTIVE' AND expires_at < NOW()`.
2. For each orphaned record:
   * Acquires a distributed lock (`RedissonLock:reconcile:{holdToken}`).
   * Executes atomic inventory restoration in Redis: `INCRBY catalog:stock:{eventId}:{tierId} {quantity}`.
   * Updates PostgreSQL status to `EXPIRED`.
   * Publishes `TicketHoldExpiredEvent`.

---

### 5.3 Custom Domain Exceptions & HTTP Mapping

The module defines domain-specific exceptions handled by a centralized `@RestControllerAdvice` (`HoldExceptionHandler`):

| Exception Class | Description | Mapped HTTP Status | Standard Response Message |
| :--- | :--- | :--- | :--- |
| `HoldNotFoundException` | Specified `holdToken` does not exist in Redis or DB | `404 Not Found` | `"The requested hold token was not found."` |
| `HoldExpiredException` | Reservation time has exceeded 300 seconds | `410 Gone` | `"Reservation has expired. Please re-enter the queue."` |
| `InsufficientStockException` | Requested quantity exceeds remaining stock | `409 Conflict` | `"Requested tickets are no longer available."` |
| `HoldAlreadyConsumedException`| Hold token was already converted into an order | `400 Bad Request` | `"Hold token has already been consumed."` |

---

## 6. End-to-End Sequence Workflows

### 6.1 Hold Creation Sequence

```
User Client           HoldController          HoldService          Redis Store         PostgreSQL
    |                       |                      |                    |                   |
    |-- POST /api/v1/holds->|                      |                    |                   |
    |   (with Pass Token)   |-- createHold() ----->|                    |                   |
    |                       |                      |-- Lua Script DECR->|                   |
    |                       |                      |   catalog:stock    |                   |
    |                       |                      |<-- Stock OK (1) ---|                   |
    |                       |                      |-- HSET hold:{tok}->|                   |
    |                       |                      |-- EXPIRE 300s ---->|                   |
    |                       |                      |                    |-- INSERT Hold --->|
    |                       |                      |                    |   (ACTIVE)        |
    |<-- 201 Created -------|<-- HoldResponseDTO --|                    |                   |
```

### 6.2 Expiration & Automated Inventory Restoration Sequence

```
Redis Engine               RedisKeyspaceListener           HoldService             PostgreSQL
     |                               |                          |                       |
     |-- Key 'hold:hld_123' Expired->|                          |                       |
     |                               |-- onMessage(key) ------->|                       |
     |                               |                          |-- INCRBY stock ------>|
     |                               |                          |   catalog:stock       |
     |                               |                          |-- UPDATE status ----->|
     |                               |                          |   to 'EXPIRED'        |
     |                               |                          |-- Publish Event ----->|
     |                               |                          |   TicketHoldExpired   |
```
