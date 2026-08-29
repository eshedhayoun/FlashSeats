# Module: `bot`

> **Status:** first-pass correction. Aligned to [`../00-architecture-decisions.md`](../00-architecture-decisions.md).
> A detailed second pass is planned before implementation.

**Package:** `com.flashseats.bot` · **Phase:** 3 · **Storage:** PostgreSQL + Redis

---

## 1. Scope

The first thing every request touches. Issues the signed session identity the rest of the system
treats as authoritative, enforces rate limits, verifies reCAPTCHA v3, and blocks abusive sources
before any business logic runs.

**Forbidden:** reading inventory, issuing queue passes, handling payments, writing orders.

**Design stance:** be aggressive against *sessions*, conservative against *IPs*. During a flash sale
the whole point is that thousands of legitimate humans arrive at once — many of them behind the same
carrier-grade NAT.

---

## 2. Package layout

```
com.flashseats.bot
├── config       BotFilterConfig, Bucket4jRedisConfig, SessionCookieProperties
├── controller   BotAdminController
├── filter       SessionIdentityFilter, RateLimitFilter      ← ordered before everything
├── service      RateLimitService, RecaptchaService, IpReputationService
├── facade       BotFacade + impl
├── repository   IpRuleRepository, BotAuditLogRepository
├── model        IpRule, BotAuditLog, RuleType, ActionTaken
├── dto          CaptchaVerdictDTO, IpRuleDTO, ThreatSummaryDTO
└── event        BotAttackDetectedEvent
```

---

## 3. Session identity (ADR-010)

```
Cookie: fsid=<base64url(uuid)>.<base64url(HMAC-SHA256(uuid, serverSecret))>
        HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=86400
```

`SessionIdentityFilter` issues one on first contact, verifies the HMAC in constant time on every
subsequent request, and exposes the verified id as request attribute `fsid`. A tampered cookie is
replaced with a fresh identity, not accepted.

**Nothing anywhere in the system may read `userSessionId` from a request body, query parameter, or
custom header.** v1 accepted it in `CreateHoldRequestDTO` and as `X-Session-ID` on hold cancellation
— queue position, pass validity and hold ownership all key off that value, so it was trivially
spoofable.

---

## 4. Rate limiting (ADR-011)

| Bucket | Key | Capacity | Refill | Role |
| :--- | :--- | :--- | :--- | :--- |
| Session | `bot:rate:session:{sid}` | 20 | 10/s | **primary** |
| IP | `bot:rate:ip:{ip}` | 300 | 150/s | coarse flood backstop |
| Join | `bot:join:{sid}:{eventId}` | 1 per event | — | one join per session |
| Join/IP | `bot:join:ip:{ip}` | 5 | 5/min | scripted-join brake |

Bucket4j is **Redis-backed in every phase** (`bucket4j_jdk17-lettuce`).

> v1 contradicted itself: `01-system-architecture.md` described Bucket4j as in-memory while the bot
> spec described Redis-backed counters. In-memory buckets across three replicas silently triple
> every configured limit.

**`GET /api/v1/queue/stream` is exempt from per-request accounting** — it is counted once at connect.
It is one long-lived connection, not a request stream, and charging it per frame would evict exactly
the users who are patiently waiting.

**Why the IP bucket is loose.** v1 specified 20 requests/second per IP. A corporate office or a
mobile carrier NAT presents thousands of legitimate buyers behind one address; that limit would have
blocked them all during precisely the traffic spike this system exists to serve. The IP bucket now
catches only genuine floods; the session bucket does the real work.

Escalation: repeated breaches → `bot:block:{ip}` EX 3600 → `403`, and an `ip_rules` row plus
`BotAttackDetectedEvent`.

---

## 5. reCAPTCHA v3

Applied to **`POST /api/v1/queue/join` only** — the one endpoint where a bot gains a durable
advantage.

```
verdict = GET bot:captcha:{sid}
if absent:
    score = Google siteverify(token, action)      -- 3s timeout
    SET bot:captcha:{sid} <score> EX 1800
reject if score < 0.5   → 403 BOT_VERIFICATION_FAILED
```

Caching per session for 30 minutes matters: at 10,000 concurrent joins, verifying every request
would mean 10,000 outbound HTTPS calls to Google on the hottest path in the system.

**Fail-open on timeout or outage** — log, alarm, and rely on the rate limits. This is a deliberate
availability-over-security trade: blocking every buyer because a third party is slow is a worse
outcome than admitting some bots for the duration. Stated explicitly so it is a decision, not an
accident.

---

## 6. Schema

```sql
CREATE TABLE ip_rules (
    id         BIGSERIAL PRIMARY KEY,
    ip_address VARCHAR(45)  NOT NULL,
    rule_type  VARCHAR(32)  NOT NULL,     -- BLACKLIST | WHITELIST
    reason     VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ,               -- null ⇒ permanent
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (ip_address, rule_type)
);

CREATE TABLE bot_audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    ip_address      VARCHAR(45)  NOT NULL,
    user_session_id VARCHAR(255),
    endpoint_path   VARCHAR(255) NOT NULL,
    recaptcha_score REAL,
    action_taken    VARCHAR(32)  NOT NULL,   -- ALLOWED | THROTTLED | BLOCKED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_bot_audit_ip_time ON bot_audit_logs(ip_address, created_at DESC);
```

Audit rows are written **asynchronously** and only for `THROTTLED` / `BLOCKED` outcomes. Writing a
row per allowed request would put a PostgreSQL insert on the hot path of every request in the
system — v1 implied exactly that.

Retention: 30 days.

---

## 7. Interfaces

| Method | Path | Access |
| :--- | :--- | :--- |
| `GET` | `/api/v1/admin/bot/audit-logs` | admin |
| `POST` | `/api/v1/admin/bot/ip-rules` | admin |
| `DELETE` | `/api/v1/admin/bot/ip-rules/{ipAddress}` | admin |

`POST /api/v1/bot/verify-captcha` is **removed** — captcha verification happens inline during
`queue/join`, not as a separate client-callable step that could be replayed.

```java
public interface BotFacade {
    boolean          authorize(String ip, String sessionId, String endpointPath);
    CaptchaVerdictDTO verifyCaptcha(String recaptchaToken, String action, String sessionId);
    void             banIp(String ip, int durationSeconds, String reason);
}

public record CaptchaVerdictDTO(boolean passed, double score, boolean degraded) {}
```

`degraded = true` signals that Google was unreachable and the request was allowed through by the
fail-open policy — it feeds the alarm, so the trade-off is visible rather than silent.

**Event:** `BotAttackDetectedEvent(ip, sessionId, violationType, requestCount, at)`.

---

## 8. Edge cases

| Case | Handling |
| :--- | :--- |
| Legitimate spike behind one NAT | Session buckets absorb it; the loose IP bucket does not fire |
| Distributed bot across many IPs | Session buckets + captcha + join limits |
| Bot rotating session cookies | Each new `fsid` needs a fresh captcha pass; IP join limit applies |
| Forged `fsid` | HMAC fails → fresh identity issued, request continues |
| Google reCAPTCHA down | Fail open, `degraded = true`, alarm |
| Redis down | Fail open on rate limits, alarm. Availability over enforcement |
| SSE connection held open | Counted once at connect |
| Admin whitelist | Checked before every bucket (load tests, monitoring) |

**Exceptions:** `RateLimitExceeded` 429 (with `Retry-After`) · `BotVerificationFailed` 403 ·
`IpAddressBlacklisted` 403.

---

## 9. Changes from v1

1. Signed `fsid` cookie specified; body/header session ids banned system-wide (ADR-010).
2. Session bucket promoted to primary; IP bucket loosened from 20/s to 300 burst (ADR-011).
3. Bucket4j confirmed **Redis-backed**, resolving the in-memory contradiction (ADR-011).
4. SSE exempt from per-request accounting.
5. reCAPTCHA narrowed to `queue/join`, cached per session for 30 min.
6. Fail-open documented as a deliberate, alarmed trade rather than an implicit fallback.
7. Public `verify-captcha` endpoint removed.
8. Audit logging made async and restricted to non-`ALLOWED` outcomes.
9. `UNIQUE(ip_address, rule_type)` and a retention policy added.
