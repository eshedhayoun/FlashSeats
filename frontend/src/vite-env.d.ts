/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** `pk_test_...` to drive Stripe; blank drives the server's stub gateway (ADR-058). */
  readonly VITE_STRIPE_PUBLISHABLE_KEY?: string;
  /** reCAPTCHA v3 site key. Blank sends no challenge token, and the server fails open (ADR-055). */
  readonly VITE_RECAPTCHA_SITE_KEY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
