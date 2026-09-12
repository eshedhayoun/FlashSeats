# Module: `shared` — the kernel

> **Describes what is built.** The rules that govern it are
> [`../05-global-standards.md`](../05-global-standards.md) §8.

**Package:** `com.flashseats.shared` · **Storage:** none · **Depends on:** nothing

---

## 1. Scope

A Spring Modulith **open module**: every other module may depend on it without producing a boundary
violation.

```java
@ApplicationModule(type = Type.OPEN)
package com.flashseats.shared;
```

### Why it is required, not convenient

Error codes, session identity and token signing are needed everywhere and owned by no one. Without a
declared open module there are only two outcomes and both are bad: duplicate the types in seven
places, or let modules depend on each other to borrow them — which `ApplicationModules.verify()`
rejects.

The first-pass docs had seven modules each inventing their own error shapes with no shared contract,
so no client could reliably switch on them. That is the gap this closes.

---

## 2. What it holds

| Area | Provides |
| :--- | :--- |
| `error` | the canonical `ErrorCode` enum (standards §2), the `ProblemDetail` factory, the base exception that carries a code, and the single `@RestControllerAdvice` |
| `identity` | the `SessionId` value type over a verified `fsid`, and the argument resolver that is the **only** way identity enters a handler |
| `security` | signed-token minting and verification — HMAC-SHA256 with a **length-prefixed `kind`**, so a token of one kind never verifies as another (ADR-039) |
| `time` | the injectable `Clock` every timer flows from, and a shared expiry type |
| `web` | the trace-id filter that puts a correlation id on every `ProblemDetail` |

There is no `money` package and no `AmountCents` type — amounts are `long` cents plus a currency
string on the records that carry them. Earlier drafts specified both; neither was built, and nothing
needs them.

---

## 3. One advice, not seven — *ADR-033*

There is a **single** `@RestControllerAdvice`, reached through a shared exception base type. Earlier
drafts specified one advice per module; that was superseded, because per-module advices multiply the
ways a failure can answer without a registry `code`.

Two rules keep it honest:

**It must name Spring's own binding exceptions before its catch-all.**
`ExceptionHandlerExceptionResolver` runs *before* `DefaultHandlerExceptionResolver`, so an advice
catching `Exception` **owns them** — and a missing query parameter answers `500 INTERNAL_ERROR` with
no `code` at all (ADR-041).

**It cannot reach the filter chain.** Spring Security throws before `DispatcherServlet`, so 401 and
403 are written by a dedicated entry point and access-denied handler — which also keeps the RFC 7235
`WWW-Authenticate` challenge that replacing the entry point would otherwise drop (ADR-048).

All errors are RFC 7807 `ProblemDetail` with a `code` from the §2 registry. There is no
`ApiResponse<T>` envelope — it fights HTTP and breaks status codes and caching.

---

## 4. What may never live here

- Any entity, repository, or table
- Any business rule, calculation, or policy
- Any DTO shared between exactly two modules — that belongs in the callee's `facade` package
- Anything that would make two modules change together

**The test:** if adding something here means two modules must be redeployed in lockstep for a
business change, it does not belong. A kernel that accumulates behaviour stops being a kernel and
becomes a distributed monolith inside a modular one.

**The PDF renderer is the one planned addition** (ADR-050): it is a pure function from a payload to
bytes, which is exactly what the kernel is for, and putting it here lets `order` serve a ticket
download without creating the first synchronous edge into `notification`. **Specified, not built.**

---

## 5. Known gaps

| Gap | Detail |
| :--- | :--- |
| **Identity is split with `bot`** | The cookie is minted and signed by a filter in `bot`, using `flashseats.bot.session-secret`; the type and resolver are here, coupled by a request-attribute string constant. The filter belongs here. Specified, not built |

---

## 6. Transactions, idempotency, metrics

None. `shared` performs no I/O and holds no state. It defines the `ErrorCode` that every module's
error-rate metric is keyed by (standards §9).
