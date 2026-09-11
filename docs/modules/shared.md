# Module: `shared` — the kernel

> **New in the 2nd pass** ([ADR-021](../00-architecture-decisions.md)). The rules that govern it are
> [`../05-global-standards.md`](../05-global-standards.md) §8.

**Package:** `com.flashseats.shared` · **Phase:** 1 · **Storage:** none

---

## 1. Scope

A Spring Modulith **open module**: every other module may depend on it without producing a boundary
violation. It holds cross-cutting types that all seven domain modules need and none of them owns.

```java
@ApplicationModule(type = Type.OPEN)
package com.flashseats.shared;
```

### Why it is required, not convenient

Error codes, session identity and money types are needed everywhere. Without a declared open module
there are only two outcomes, and both are bad: duplicate the types in seven places, or let modules
depend on each other to borrow them — which `ApplicationModules.verify()` rejects.

The 1st-pass docs had seven modules each inventing their own error shapes
(`HOLD_EXPIRED_OR_INVALID`, `INSUFFICIENT_STOCK`, `BOT_VERIFICATION_FAILED`) with no shared
contract, so no frontend could reliably switch on them. That is the gap this closes.

---

## 2. Contents

```
com.flashseats.shared
├── error
│   ├── ErrorCode.java              # the canonical enum — standards §2
│   ├── ProblemDetails.java         # factory applying the §1 extension schema
│   ├── FlashSeatsException.java    # base: carries an ErrorCode
│   └── GlobalExceptionHandler.java # @RestControllerAdvice, LOWEST_PRECEDENCE
├── identity
│   └── SessionId.java              # value type over the verified fsid
├── money
│   ├── AmountCents.java
│   └── Currency.java
├── time
│   └── ClockConfig.java            # injectable Clock, so timers are testable
└── web
    ├── ApiVersion.java
    └── PageRequestDefaults.java
```

### The advice split

`GlobalExceptionHandler` is `@Order(LOWEST_PRECEDENCE)` and handles **only** bean validation,
malformed JSON, and anything otherwise unhandled — mapping the last to a bare `500` carrying a
`traceId` and **no internal detail**.

Each module keeps its own `@RestControllerAdvice` for its own exceptions. A single global advice
would have to import every module's exception types into one class, breaking the very boundary
Modulith enforces.

---

## 3. What may never live here

- Any entity, repository, or table
- Any business rule, calculation, or policy
- Any DTO shared between exactly two modules — that belongs in the callee's `facade` package
- Anything that would make two modules change together

**The test:** if adding something here means two modules must be redeployed in lockstep for a
business change, it does not belong here. A kernel that accumulates behaviour stops being a kernel
and becomes a distributed monolith inside a modular one.

---

## 4. Transaction boundaries, idempotency, resilience

None. `shared` performs no I/O and has no state.

## 5. Metrics

None of its own. It defines the `ErrorCode` tag that every module's error-rate metric is keyed by
(standards §9).
