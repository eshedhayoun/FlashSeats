# Module Design Specification: `queue` Module

> **Domain Phase:** Phase 2 (High-Concurrency & Inventory Control)  
> **Package Namespace:** `com.app.queue`  
> **Primary Infrastructure:** Redis RAM (Ephemeral, In-Memory)

---

## 1. Module Overview & Scope

### Core Responsibility
The `queue` module manages the virtual waiting room and line positions during high-concurrency flash sales. It regulates traffic flow into downstream services by:
- Assigning queue positions using **Redis Sorted Sets (ZSET)** based on millisecond arrival timestamps.
- Streaming real-time line position updates directly to buyer browsers via **Server-Sent Events (SSE)**.
- Issuing cryptographically signed **HMAC-SHA256 queue pass tokens** when a user reaches position #1.

### Domain Position
Situated in **Phase 2 (High-Concurrency & Inventory Control)** of the system architecture. It acts as an upstream rate-limiting buffer to ensure downstream database loads and checkout microservices remain predictable and stable during extreme traffic spikes.

### Explicit Boundary Limits
To maintain high cohesion and clear architectural boundaries, the `queue` module is **strictly forbidden** from:
- **Checking Database Ticket Inventory:** Owned exclusively by the `catalog` module.
- **Managing 5-Minute Seat Reservations:** Owned exclusively by the `hold` module.
- **Processing Payments:** Owned exclusively by the `payment` module.
- **Creating Permanent Order Database Records:** Owned exclusively by the `order` module.

---

## 2. Package Structure & Code Layout

The module follows standard package structuring under `com.app.queue`:

```
com.app.queue
├── controller/       # REST controllers for joining queue, status checks, SSE streaming
├── service/          # Core business logic (rank calculations, promotions, HMAC generation)
├── facade/           # Public inter-module API (QueueFacade & QueueFacadeImpl)
├── repository/       # Redis ZSET queries, rank lookups, and key operations
├── model/            # Internal domain value objects (QueueEntry, ScoreMetrics, TokenPayload)
├── dto/              # Immutable Data Transfer Objects (QueueStatusResponse, LineEstimate)
└── event/            # Spring Domain Events (UserPromotedEvent)
```

### Component Details
* **`controller`**: Contains REST endpoints for joining the waiting room, checking line status, and establishing persistent SSE HTTP connections.
* **`service`**: Implements business logic for calculating queue ranks, batch promotion routines, and generating HMAC-SHA256 signed pass tokens.
* **`facade`**: Defines the public Java interface (`QueueFacade`) and implementation (`QueueFacadeImpl`) exposed exclusively to other backend modules (such as `hold`).
* **`repository`**: Manages Redis Sorted Set (`ZSET`) queries, rank lookups, and score insertions.
* **`model`**: Internal value objects representing queue entries, score metrics, and token payloads.
* **`dto`**: Immutable data transfer objects for queue status responses, line estimates, and token verification payloads.
* **`event`**: Domain events published when users are promoted to the front of the line or when the queue state shifts.

---

## 3. Data Storage & Schema Design

### PostgreSQL Tables
> **State Storage:** **None.**  
> The `queue` module stores zero state in PostgreSQL or relational SQL databases. The virtual waiting room is entirely ephemeral and operates exclusively in Redis RAM to achieve microsecond throughput during massive traffic spikes.

### Redis In-Memory Architecture & Design

Redis is used in the `queue` module to order incoming traffic by arrival timestamp and stream real-time position updates without touching SQL databases.

#### 1. Key Naming Scheme & Data Types

##### **Waiting Room Key**
* **Key Format:** `queue:waiting:{eventId}`
* **Data Type:** Sorted Set (`ZSET`)
* **Score:** Epoch timestamp in milliseconds when the user joined (`System.currentTimeMillis()`)
* **Member:** `userSessionId` string
* **TTL Policy:** Persists until the flash sale completes or is cleared by an administrator.

##### **Active Queue Pass Key**
* **Key Format:** `queue:pass:{userSessionId}`
* **Data Type:** String
* **Value:** Signed HMAC-SHA256 pass token containing `eventId`, `userSessionId`, and expiration timestamp.
* **TTL Policy:** 300 seconds (5 minutes). Hard expiration matching the `hold` module window.

---

#### 2. Line Placement & Rank Retrieval

1. **Line Placement:**  
   When a user clicks **"Join Sale"**, their `userSessionId` is inserted into `queue:waiting:{eventId}` using the current timestamp as the score (`ZADD`):
   ```redis
   ZADD queue:waiting:evt_109283 1700000000000 "sess_usr_883921"
   ```

2. **Rank Calculation:**  
   The user's position in line is retrieved using zero-based rank calculations (`ZRANK`):
   ```redis
   ZRANK queue:waiting:evt_109283 "sess_usr_883921"
   ```
   The returned index $+ 1$ represents their exact numerical position in line (e.g., `#450`).

---

#### 3. Real-Time Streaming via Server-Sent Events (SSE)

```
[ Browser Client ]  <--- SSE Stream (HTTP GET) ---  [ QueueController ]
        |                                                   |
        |  GET /api/v1/queue/stream                         | Poll ZRANK
        |-------------------------------------------------->|
        |                                                   v
        |  event: position-update                          [ Redis ZSET ]
        |  data: {"position": 450, "estWaitSeconds": 120}   | (ZRANK)
        |<--------------------------------------------------|
        |                                                   |
        |  event: position-update                            |
        |  data: {"position": 320, "estWaitSeconds": 85}    |
        |<--------------------------------------------------|
        |                                                   |
        |  event: queue-promoted                            |
        |  data: {"passToken": "eyJhbG..."}                  |
        |<--------------------------------------------------|
```

* When the user's browser opens an SSE connection (`GET /api/v1/queue/stream`), a background thread periodically polls the `ZRANK` for that `userSessionId`.
* As users ahead in line complete checkout or exit, position updates are pushed down the SSE connection to update the user UI live (e.g., `#450` $ightarrow$ `#320` $ightarrow$ `#1`).

---

#### 4. Batch Promotion & Cryptographic Pass Generation

1. **Batch Retrieval:** A scheduled background worker task pulls the top $N$ users from the front of the line (`ZRANGE 0 N`).
   ```redis
   ZRANGE queue:waiting:evt_109283 0 N
   ```
2. **Pass Generation:** For each promoted user, the service generates a cryptographically signed HMAC-SHA256 pass token.
3. **Pass Caching:** Stores the pass token in `queue:pass:{userSessionId}` with a 5-minute TTL:
   ```redis
   SET queue:pass:sess_usr_883921 "HMAC_PASS_TOKEN_STRING" EX 300
   ```
4. **Queue Eviction:** Removes the user from the `ZSET`.
5. **Client Notification:** The pass token is pushed over the SSE connection to trigger the user browser's automatic redirection to the ticket selection screen.

---

## 4. Interfaces & Event Contracts

### External REST Endpoints

| Method | Endpoint Path | Description | Access Level |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/queue/join` | Join the virtual waiting room for an active event | Public (Session Cookie Required) |
| `GET` | `/api/v1/queue/status` | Fetch current rank position and estimated wait time | Public |
| `GET` | `/api/v1/queue/stream` | Open persistent Server-Sent Events (SSE) stream for live updates | Public |

---

### Internal Module Facade (`QueueFacade`)

Exposes synchronous Java methods for in-memory cross-module calls:

```java
public interface QueueFacade {
    /**
     * Pass Token Verification Method: Accepts passToken, userSessionId, and eventId.
     * Validates the HMAC-SHA256 signature and checks if an active pass key exists in Redis.
     * Returns boolean true if valid. Used by the hold module to ensure a user cannot claim
     * seats without having legally passed through the waiting room.
     */
    boolean verifyPassToken(String passToken, String userSessionId, String eventId);

    /**
     * Pass Revocation Method: Accepts userSessionId.
     * Invalidates a pass key in Redis once a seat hold or checkout is completed.
     */
    void revokePassToken(String userSessionId);
}
```

* **Pass Token Verification Method:** Accepts `passToken`, `userSessionId`, and `eventId`. Validates the HMAC-SHA256 signature and checks if an active pass key exists in Redis. Returns boolean `true` if valid. Used by the `hold` module to ensure a user cannot claim seats without having legally passed through the waiting room.
* **Pass Revocation Method:** Accepts `userSessionId`. Invalidates a pass key in Redis once a seat hold or checkout is completed.

---

### Domain Events

#### `UserPromotedEvent` *(Internal Spring Event)*
* **Trigger:** Fired when users are promoted to the front of the line (position #1) and receive a queue pass token.
* **Payload Details:** Contains `userSessionId`, `eventId`, `passToken`, and `promotedAt`.
* **Purpose:** Notifies internal metrics tracking systems of throughput rates and queue exit velocities.

---

## 5. Concurrency Control, Failure Modes & Edge Cases

### Concurrency & Security Rules

* **Cryptographic Tamper Prevention:** Queue pass tokens are signed using a secret server key (HMAC-SHA256). Users cannot manually craft a fake pass token or alter another user's session token to skip the line.
* **Abrupt Browser Disconnects:** If a user closes their browser tab mid-queue, the SSE stream connection closes. If their rank reaches position #1, their issued 5-minute pass key eventually expires in Redis without consuming ticket stock.
* **Fairness Guarantee:** Score-based sorting using millisecond epoch timestamps guarantees strict First-In, First-Out (FIFO) ordering across all backend application replicas.

---

### Custom Domain Exception Handling

| Exception | HTTP Status Mapping | Description / Trigger |
| :--- | :--- | :--- |
| `InvalidQueueTokenException` | `403 Forbidden` | Thrown when a queue pass token signature is invalid or expired. |
| `NotInQueueException` | `404 Not Found` | Thrown when a queried session is not found in the waiting list. |
| `QueueUnavailableException` | `503 Service Unavailable` | Thrown when the queue infrastructure (Redis) is unreachable or overloaded. |

---
*End of Queue Module Specification.*
