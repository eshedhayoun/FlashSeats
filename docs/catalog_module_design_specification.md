# Module Design Specification: Catalog Module

## 1. Module Overview & Scope

* **Core Responsibility:** Manages static event metadata (event titles, venue details, sale windows) and ticket section configurations (pricing, base capacities). Serves high-volume read queries for event details and initializes ticket availability in Redis memory before the flash-sale starts.
* **Domain Position:** Phase 1 (MVP Foundation). Provides the foundational metadata required by downstream modules (`hold`, `order`, `queue`).
* **Explicit Boundary Limits:** Strictly forbidden from processing cart holds, executing payments, managing virtual queues, or creating order database records.

---

## 2. Package Structure & Code Layout

The module follows standard package structuring under `com.app.catalog`:

* **`controller`**: Contains REST controllers for public event browsing and admin pre-warm operations.
* **`service`**: Contains business logic for metadata reads, updates, and Redis pre-warming workflows.
* **`facade`**: Defines the public Java interface (`CatalogFacade`) and implementation (`CatalogFacadeImpl`) exposed exclusively to other backend modules.
* **`repository`**: Spring Data JPA repositories managing access to PostgreSQL tables.
* **`model`**: Internal PostgreSQL relational entities representing events and ticket tiers.
* **`dto`**: Immutable data transfer objects for API responses and inter-module communication.
* **`event`**: Spring domain events published during state changes (such as inventory pre-warming completion).

---

## 3. Data Storage & Schema Design

### PostgreSQL Tables (Relational Storage)

#### Table: `events`
* `id` (`BIGINT`, Primary Key, Auto-increment): Unique identifier for the event.
* `title` (`VARCHAR(255)`, Not Null): Name of the event.
* `description` (`TEXT`, Nullable): Detailed event description.
* `venue_name` (`VARCHAR(255)`, Not Null): Physical location or stadium name.
* `sale_start_time` (`TIMESTAMP WITH TIME ZONE`, Not Null): Scheduled flash-sale launch timestamp.
* `sale_end_time` (`TIMESTAMP WITH TIME ZONE`, Not Null): Scheduled flash-sale ending timestamp.
* `status` (`VARCHAR(32)`, Not Null): Current status (`DRAFT`, `UPCOMING`, `ACTIVE`, `ENDED`).
* `created_at` (`TIMESTAMP WITH TIME ZONE`, Not Null): Record creation timestamp.

#### Table: `ticket_tiers`
* `id` (`BIGINT`, Primary Key, Auto-increment): Unique ticket tier identifier.
* `event_id` (`BIGINT`, Foreign Key referencing `events.id`, Not Null): Parent event reference.
* `tier_name` (`VARCHAR(100)`, Not Null): Name of section (e.g., VIP, Section A, General Admission).
* `price_cents` (`BIGINT`, Not Null): Ticket price represented in smallest currency unit.
* `total_capacity` (`INT`, Not Null): Total baseline quantity of tickets allocated.
* `created_at` (`TIMESTAMP WITH TIME ZONE`, Not Null): Record creation timestamp.

---

### Redis In-Memory Architecture & Design

Redis is used in the catalog module as an ultra-fast, in-memory caching and inventory counting layer to isolate PostgreSQL from high-concurrency read/write surges during flash sales.

1. **Key Naming Scheme & Data Types**
   * **Key Format:** `catalog:stock:{eventId}:{tierId}`
   * **Data Type:** String (stored as plain integer text to support atomic decrement operations).
   * **Value:** Non-negative integer representing remaining unsold tickets (e.g., `500`).
   * **TTL Policy:** No expiration set (`TTL = -1`) during an active event to prevent mid-sale cache evictions. Keys are manually cleaned up or archived post-event.

2. **Inventory Pre-Warming Workflow**
   * Before a flash sale goes live, an administrator triggers the pre-warm process via the catalog service.
   * The service queries PostgreSQL for all active `ticket_tiers` belonging to the target `event_id`.
   * For each tier, the service writes the `total_capacity` integer value into Redis using a **Set-If-Not-Exists** command (`SETNX`).
   * *Why `SETNX`?* It ensures that if an admin accidentally triggers pre-warming twice, live Redis stock counters currently being decremented by active buyers will not be overwritten or reset to initial capacity.

3. **High-Speed Read Path**
   * When users browse event pages before or during a sale, real-time ticket availability is fetched directly from Redis RAM in under 1 millisecond per request.
   * If a Redis key is missing (cache miss), the catalog service falls back to PostgreSQL `total_capacity`, populates Redis, and returns the result.

4. **Ownership & Write Boundaries**
   * **`catalog` Module Ownership:** Owns initialization (pre-warming), read-only reads for user displays, and post-sale reconciliation against PostgreSQL.
   * **`hold` Module Interaction:** The `hold` module is the only component allowed to decrement these counters during a purchase attempt using atomic Redis operations.

5. **Persistence & Failover Strategy**
   * Redis is configured with **AOF (Append Only File)** persistence enabled with a one-second sync policy (`everysec`).
   * If the Redis node restarts during a sale, it restores the live stock counters from disk without data loss, preventing inventory drift or overbooking.

---

## 4. Interfaces & Event Contracts

### External REST Endpoints

| Method | Endpoint Path | Description | Access Level |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/events` | List all active and upcoming events | Public |
| `GET` | `/api/v1/events/{eventId}` | Fetch full event details, venue info, and tier availability | Public |
| `POST` | `/api/v1/admin/events/{eventId}/prewarm` | Sync PostgreSQL capacity baselines into Redis RAM counters | Admin Only |

### Internal Module Facade (`CatalogFacade`)

Exposes synchronous Java methods for in-memory cross-module calls:
* **Tier Validation Method:** Accepts `eventId` and `tierId`. Returns a boolean indicating if the tier exists, belongs to the event, and is open for sales. Used by the `hold` module before reserving seats.
* **Tier Summary Extraction Method:** Accepts `eventId` and `tierId`. Returns an immutable `TierSummaryDTO` containing section name, price in cents, and event title. Used by the `order` module to populate permanent purchase records without querying catalog database tables directly.

### Domain Events

#### `EventPrewarmedEvent` (Internal Spring Event)
* **Trigger:** Published by the inventory pre-warmer service immediately after all ticket tier counters for an event are successfully initialized in Redis.
* **Payload Details:** Contains `eventId`, list of pre-warmed `tierIds`, total stock populated, and execution timestamp.
* **Purpose:** Allows internal monitoring systems or audit logs to verify readiness before opening the virtual waiting room.

---

## 5. Concurrency Control, Failure Modes & Edge Cases

* **Pre-Warm Race Conditions:** Prevented via Redis `SETNX` commands, guaranteeing that initialized live stock counters cannot be accidentally overwritten.
* **PostgreSQL Unavailability During Sale:** Once pre-warming is completed, event metadata and stock availability can be completely served from Redis RAM, allowing browse operations to continue smoothly even if PostgreSQL experiences temporary latency spikes.
* **Custom Domain Exception Handling:**
  * Missing Event $ightarrow$ Throws `EventNotFoundException` (Mapped to `HTTP 404 Not Found`).
  * Missing Tier $ightarrow$ Throws `TierNotFoundException` (Mapped to `HTTP 404 Not Found`).
  * Pre-Warm Failure $ightarrow$ Throws `PrewarmFailedException` (Mapped to `HTTP 500 Internal Server Error`).
