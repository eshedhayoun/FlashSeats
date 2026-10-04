package com.flashseats.hold.service;

import java.time.Instant;

/**
 * A {@code hold:{token}} timer fired while its hold still had time left, so the timer has to be armed
 * again for {@code expiresAt}. The re-arm is a Redis write, which waits for the reading transaction to
 * end like every other one (invariant 9). Losing it costs latency only: the sweeper still reclaims.
 */
record HoldTimerFiredEarlyEvent(String holdToken, Instant expiresAt) {}
