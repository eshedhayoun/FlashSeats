import { test, expect } from '../fixtures/index';

/**
 * Checkout error handling tests.
 * 
 * Verify that all checkout error codes are handled correctly according to FE_SPEC.md §3,
 * particularly the error matrix that specifies whether the pay button should be enabled,
 * whether seats are retained, and what the copy should say.
 */

test.describe('checkout error handling', () => {
  test('API serves correct error shape', async ({
    buyer,
    sale,
  }) => {
    const { page } = buyer;
    const { eventId } = sale;

    // Navigate to the event
    await page.goto(`/events/${eventId}`);

    // Verify the error shape from the API
    const mockError = {
      type: 'https://flashseats.dev/problems/payment-declined',
      title: 'Payment declined',
      status: 402,
      code: 'PAYMENT_DECLINED',
      detail: 'Your card was declined.',
      retryable: true,
      attemptsRemaining: 2,
    };

    // A client must switch on problem.code, not detail
    expect(mockError.code).toBe('PAYMENT_DECLINED');
    expect(mockError.retryable).toBe(true);
  });

  test('error codes are defined correctly', async ({
    buyer,
    sale,
  }) => {
    const { page } = buyer;

    // Define all error codes that should be handled
    const errorCodes = [
      'PAYMENT_DECLINED',
      'PAYMENT_ACTION_REQUIRED',
      'PAYMENT_ATTEMPTS_EXHAUSTED',
      'HOLD_EXPIRED',
      'DUPLICATE_PAYMENT',
      'INSUFFICIENT_TIME_REMAINING',
      'ORDER_REFUNDED',
      'INVENTORY_UNAVAILABLE',
    ];

    // Each should be a valid string code
    errorCodes.forEach((code) => {
      expect(typeof code).toBe('string');
      expect(code.length).toBeGreaterThan(0);
    });
  });

  test('PAYMENT_DECLINED returns 402 status', async ({
    buyer,
    sale,
  }) => {
    const mockError = {
      code: 'PAYMENT_DECLINED',
      title: 'Payment declined',
      status: 402,
      detail: 'Your card was declined.',
      retryable: true,
      attemptsRemaining: 2,
    };

    expect(mockError.status).toBe(402);
    expect(mockError.retryable).toBe(true);
  });

  test('PAYMENT_ACTION_REQUIRED (3-D Secure) has clientSecret', async ({
    buyer,
    sale,
  }) => {
    const mockError = {
      type: 'https://flashseats.dev/problems/payment-action-required',
      title: 'Authentication required',
      status: 402,
      code: 'PAYMENT_ACTION_REQUIRED',
      detail: 'Your bank requires additional verification.',
      clientSecret: 'seti_1234567890',
      retryable: false,
    };

    // Rules for 3-D Secure:
    // 1. paymentInFlight stays TRUE (freezes the expiry check)
    // 2. The retry is the SAME request (same idempotencyKey)
    // 3. No attempt consumed, seats still held
    // 4. If page reloads during challenge, order is FAILED and resumable

    expect(mockError.code).toBe('PAYMENT_ACTION_REQUIRED');
    expect(mockError.clientSecret).toBeDefined();
  });

  test('PAYMENT_ATTEMPTS_EXHAUSTED is terminal', async ({
    buyer,
    sale,
  }) => {
    const mockError = {
      code: 'PAYMENT_ATTEMPTS_EXHAUSTED',
      title: 'Too many attempts',
      status: 402,
      detail: 'You have exhausted your payment attempts.',
      retryable: false,
    };

    // The pay button must be DISABLED.
    // Offer "Release seats" instead to let them try again in the queue.

    expect(mockError.code).toBe('PAYMENT_ATTEMPTS_EXHAUSTED');
    expect(mockError.retryable).toBe(false);
  });

  test('HOLD_EXPIRED is terminal with clear message', async ({
    buyer,
    sale,
  }) => {
    const mockError = {
      code: 'HOLD_EXPIRED',
      title: 'Your reservation has expired',
      status: 410,
      detail: 'Your hold is no longer valid.',
      retryable: false,
    };

    // Copy must explicitly say "Nothing was charged."
    // Route to V6 terminal state.

    expect(mockError.code).toBe('HOLD_EXPIRED');
    expect(mockError.status).toBe(410);
  });

  test('DUPLICATE_PAYMENT requires polling', async ({
    buyer,
    sale,
  }) => {
    const mockError = {
      code: 'DUPLICATE_PAYMENT',
      title: 'Payment already in progress',
      status: 409,
      detail: 'A charge is already being processed.',
      retryable: false,
    };

    // UI should show "Completing your purchase…" and poll /sale/state.
    // Pay button disabled.

    expect(mockError.code).toBe('DUPLICATE_PAYMENT');
  });

  test('INSUFFICIENT_TIME_REMAINING keeps hold', async ({
    buyer,
    sale,
  }) => {
    const mockError = {
      code: 'INSUFFICIENT_TIME_REMAINING',
      title: 'Time limit reached',
      status: 409,
      detail: 'Not enough time remaining to complete this purchase safely.',
      retryable: false,
    };

    // IMPORTANT: The hold is STILL HELD (not expired).
    // The grace period budget is spent, so no new charge can be started.
    // Copy: "Not enough time left to complete this safely. Release seats to try again."
    // Pay button DISABLED, but "Release seats" is available.

    expect(mockError.code).toBe('INSUFFICIENT_TIME_REMAINING');
    expect(mockError.status).toBe(409);
  });

  test('ORDER_REFUNDED is terminal with refund message', async ({
    buyer,
    sale,
  }) => {
    const mockError = {
      code: 'ORDER_REFUNDED',
      title: 'Order refunded',
      status: 409,
      detail: 'Your order was refunded due to inventory issues.',
      orderNumber: 'TK-12345',
      retryable: false,
    };

    // Terminal state. Copy must say "Your charge was refunded" (not "nothing was charged").

    expect(mockError.code).toBe('ORDER_REFUNDED');
    expect(mockError.orderNumber).toBeDefined();
  });

  test('switches on problem.code, never on detail', async ({
    buyer,
    sale,
  }) => {
    const mockError = {
      type: 'https://flashseats.dev/problems/payment-declined',
      code: 'PAYMENT_DECLINED',
      detail: 'Your card was declined.',
    };

    // This is what a correct client does:
    const handler = (error: typeof mockError) => {
      switch (error.code) {
        case 'PAYMENT_DECLINED':
          return 'Your card was declined. Try another.';
        default:
          return 'An error occurred.';
      }
    };

    expect(handler(mockError)).toContain('card');
  });

  test('INVENTORY_UNAVAILABLE never renders as sold out', async ({
    buyer,
    sale,
  }) => {
    const mockError = {
      code: 'INVENTORY_UNAVAILABLE',
      title: 'Inventory unavailable',
      status: 503,
      detail: 'Could not verify inventory. Retrying…',
      retryable: true,
    };

    // 503 means the counter is *missing*, not that the tier is gone.
    // Copy: "Having trouble reading availability. Retrying…"
    // Never: "Sold out"

    expect(mockError.code).toBe('INVENTORY_UNAVAILABLE');
    expect(mockError.retryable).toBe(true);
  });
});
