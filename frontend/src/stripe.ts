import { loadStripe } from "@stripe/stripe-js";

/*
 * The client mirrors the server's seam rather than assuming a provider.
 *
 * `flashseats.payment.stripe.enabled` is FALSE by default, so dev, test, the
 * load harness and every drill run the in-process stub gateway through the whole
 * journey — decline, outage and 3-D Secure included. A client that cannot start
 * without a publishable key could not exercise the configuration this repo
 * actually defaults to, and threw a white screen at anyone who cloned it.
 *
 * Set VITE_STRIPE_PUBLISHABLE_KEY (see .env.example) to drive the real provider;
 * leave it blank to drive the stub.
 */
const publishableKey = import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY?.trim();

export const stripeEnabled = Boolean(publishableKey);

export const stripePromise = publishableKey
  ? loadStripe(publishableKey)
  : null;
