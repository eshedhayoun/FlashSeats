# Module Design Specification: bot Module

## 1. Module Overview & Scope

* **Core Responsibility:** Serves as the system's first line of defense against automated abuse. Evaluates incoming request traffic, enforces IP-based and session-based rate limits, validates client-side Google reCAPTCHA v3 risk scores, and automatically blocks malicious scrapers, DDoS flooding, and purchasing scripts before business logic or inventory state is touched.
* **Domain Position:** Phase 3 (Security & Payment Integration). Protects all Phase 1 and Phase 2 endpoints (catalog, queue, hold, order) from high-frequency script attacks.
* **Explicit Boundary Limits:** Strictly forbidden from checking inventory stock levels (owned by catalog/hold), issuing queue line passes (owned by queue), handling payment transactions (owned by payment), or persisting order database records (owned by order).

---

## 2. Package Structure & Code Layout

The module follows standard package structuring under `com.app.bot`:

```
com.app.bot
├── controller    # REST controllers for admin security management and interceptor hooks
├── service       # Business logic for Bucket4j token-bucket, reCAPTCHA v3, IP risk scoring, and blocking
├── facade        # Public Java interface (BotFacade) and implementation (BotFacadeImpl)
├── repository    # Database persistence for IP rules and threat audit logs in PostgreSQL
├── model         # Internal PostgreSQL relational entities for security rules, IP scores, and audit logs
├── dto           # Immutable DTOs for captcha verification payloads, IP rules, and threat summaries
└── event         # Domain events published when automated attacks or traffic spikes are detected
```

### Component Breakdown

* **`controller`**: Contains REST controllers for admin security management (viewing threat logs, manually blacklisting/whitelisting IP addresses) and interceptor hooks for incoming HTTP request filtering.
* **`service`**: Contains business logic for Bucket4j token-bucket calculations, Google reCAPTCHA v3 API verification, IP risk score evaluation, and automated IP blocking.
* **`facade`**: Defines the public Java interface (`BotFacade`) and implementation (`BotFacadeImpl`) exposed exclusively to other backend modules or web filters.
* **`repository`**: Handles database persistence for IP blacklists, whitelists, and threat audit logs in PostgreSQL.
* **`model`**: Internal PostgreSQL relational entities for security rules, IP reputation scores, and threat audit logs.
* **`dto`**: Immutable data transfer objects for captcha verification payloads, IP rule configurations, and security threat summaries.
* **`event`**: Domain events published when automated attacks or abnormal traffic spikes are detected.

---

## 3. Data Storage & Schema Design

### PostgreSQL Tables (Relational Audit Ledger)

#### Table: `ip_rules`

| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | Primary Key, Auto-increment | Unique internal identifier. |
| `ip_address` | `VARCHAR(45)` | Unique, Not Null, Indexed | IPv4 or IPv6 address. |
| `rule_type` | `VARCHAR(32)` | Not Null | Rule classification (`BLACKLIST`, `WHITELIST`). |
| `reason` | `VARCHAR(255)` | Not Null | Explanation for rule creation (e.g., `RATE_LIMIT_EXCEEDED`, `LOW_RECAPTCHA_SCORE`, `MANUAL_ADMIN_BLOCK`). |
| `expires_at` | `TIMESTAMP WITH TIME ZONE` | Nullable | Expiration time for temporary blocks (`null` for permanent bans). |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | Not Null | Record creation timestamp. |

#### Table: `bot_audit_logs`

| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | Primary Key, Auto-increment | Unique audit record identifier. |
| `ip_address` | `VARCHAR(45)` | Not Null, Indexed | Request origin IP address. |
| `user_session_id` | `VARCHAR(255)` | Nullable | Guest session identifier. |
| `endpoint_path` | `VARCHAR(255)` | Not Null | Targeted API path (e.g., `/api/v1/holds`). |
| `recaptcha_score` | `FLOAT` | Nullable | Score returned by Google reCAPTCHA v3 (0.0 to 1.0). |
| `action_taken` | `VARCHAR(32)` | Not Null | Action applied (`ALLOWED`, `CHALLENGED`, `BLOCKED`). |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | Not Null | Incident logging timestamp. |

---

### Redis In-Memory Architecture & Design

Redis is used in the `bot` module to evaluate request velocity in sub-milliseconds without touching SQL databases.

#### 1. Key Naming Scheme & Data Types

* **IP Rate Limiter Key:** `bot:rate:ip:{ipAddress}`
  * **Data Type:** Hash / String (Managed via Bucket4j token-bucket algorithm).
  * **Value:** Remaining token count and last replenishment timestamp.
  * **TTL Policy:** 60 seconds (Rolling window).
* **Session Rate Limiter Key:** `bot:rate:session:{userSessionId}`
  * **Data Type:** Hash / String (Managed via Bucket4j).
  * **Value:** Remaining token count for the specific guest session.
  * **TTL Policy:** 60 seconds.
* **Temporary IP Block Flag:** `bot:block:{ipAddress}`
  * **Data Type:** String (Flag value: `"BLOCKED"`).
  * **TTL Policy:** 3600 seconds (1 hour temporary cooling-off block).

#### 2. High-Speed Rate Limiting Workflow

1. When a request arrives at the Nginx gateway or application filter, the system checks for `bot:block:{ipAddress}` in Redis RAM. If present, the request is instantly rejected (< 1 ms response).
2. If not blocked, Bucket4j consumes a token from `bot:rate:ip:{ipAddress}`.
3. If tokens are exhausted (e.g., more than 20 requests per second from a single IP), the request is rejected with **HTTP 429**, and repeated violations write a temporary 1-hour block key `bot:block:{ipAddress}` into Redis.

#### 3. Google reCAPTCHA v3 Verification

* For critical endpoints (like `POST /api/v1/queue/join` or `POST /api/v1/holds`), the frontend submits a reCAPTCHA token.
* The bot service validates the token asynchronously against Google's API.
* Scores below 0.5 indicate high bot likelihood and trigger request rejection or secondary challenge requirements.

---

## 4. Interfaces & Event Contracts

### External REST Endpoints

| Method | Endpoint Path | Description | Access Level |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/bot/verify-captcha` | Submit client reCAPTCHA token for score evaluation | Public |
| `GET` | `/api/v1/admin/bot/audit-logs` | Fetch bot threat audit logs and attack history | Admin Only |
| `POST` | `/api/v1/admin/bot/ip-rules` | Manually add an IP address to the blacklist or whitelist | Admin Only |
| `DELETE` | `/api/v1/admin/bot/ip-rules/{ipAddress}` | Remove an IP address from the rule ledger | Admin Only |

### Internal Module Facade (`BotFacade`)

Exposes synchronous Java methods for in-memory cross-module calls and request filters:

1. **Request Authorization Method:** Accepts `ipAddress`, `userSessionId`, and `endpointPath`. Evaluates current Bucket4j token levels and Redis block flags. Returns boolean `true` if authorized to proceed.
2. **Captcha Score Verification Method:** Accepts `recaptchaToken` and `actionName`. Returns a floating-point score (0.0 to 1.0) after validating with Google reCAPTCHA v3.
3. **Manual IP Ban Method:** Accepts `ipAddress`, `durationSeconds`, and `reason`. Immediately populates Redis block keys and persists an `ip_rules` record in PostgreSQL.

### Domain Events

* **`BotAttackDetectedEvent` (Internal Spring Event):**
  * **Trigger:** Published when an IP address triggers repeated rate limit breaches or yields consecutive reCAPTCHA scores below 0.2.
  * **Payload Details:** Contains `ipAddress`, `userSessionId`, `violationType`, `requestCount`, `timestamp`.
  * **Purpose:** Notifies system administrators, security monitors, or automated firewall integration scripts.

---

## 5. Concurrency Control, Failure Modes & Edge Cases

* **Sub-Millisecond Execution:** Rate-limit token consumption runs entirely in Redis RAM using microsecond atomic updates, ensuring zero latency impact on legitimate buyers.
* **Third-Party API Fallback (reCAPTCHA Timeout):** If Google's reCAPTCHA API experiences high latency or an outage, the bot module degrades gracefully: it logs a warning and relies on passive Bucket4j rate-limiting instead of blocking legitimate human buyers.
* **Distributed Rate Limiting:** Storing rate-limit buckets in Redis ensures that if a malicious bot spreads requests across multiple Spring Boot container replicas behind Nginx, its global request count is still tracked accurately.
* **Custom Domain Exception Handling:**
  * **Rate Limit Breached:** Throws `RateLimitExceededException` (Mapped to **HTTP 429 Too Many Requests**).
  * **Bot Suspect / Invalid Captcha:** Throws `BotVerificationFailedException` (Mapped to **HTTP 403 Forbidden**).
  * **Access Blacklisted:** Throws `IpAddressBlacklistedException` (Mapped to **HTTP 403 Forbidden**).
