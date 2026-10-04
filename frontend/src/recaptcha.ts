/*
 * reCAPTCHA v3 on "Join the sale", and nowhere else (FE_SPEC §5, ADR-055).
 *
 * Executed on the press, never on page load: tokens are short-lived, so one minted early is stale for
 * anyone who reads the page first. And everything here FAILS OPEN: no key configured, a script that
 * will not load, a provider that is slow — each yields no token, and the join is sent anyway. The
 * server then falls back to rate limits. No sale may close because a third-party script did not load.
 */

type Grecaptcha = {
  ready(callback: () => void): void;
  execute(siteKey: string, options: { action: string }): Promise<string>;
};

declare global {
  interface Window {
    grecaptcha?: Grecaptcha;
  }
}

const siteKey = import.meta.env.VITE_RECAPTCHA_SITE_KEY?.trim();
const PROVIDER_TIMEOUT_MS = 3_000;

let script: Promise<Grecaptcha | null> | null = null;

function loadScript(key: string): Promise<Grecaptcha | null> {
  if (window.grecaptcha) return Promise.resolve(window.grecaptcha);
  if (!script) {
    script = new Promise((resolve) => {
      const element = document.createElement("script");
      element.src = `https://www.google.com/recaptcha/api.js?render=${encodeURIComponent(key)}`;
      element.async = true;
      element.onload = () => resolve(window.grecaptcha ?? null);
      element.onerror = () => {
        script = null; // let a later press try again
        resolve(null);
      };
      document.head.appendChild(element);
    });
  }
  return script;
}

function withTimeout<T>(promise: Promise<T>, fallback: T): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((resolve) => window.setTimeout(() => resolve(fallback), PROVIDER_TIMEOUT_MS))
  ]);
}

/** A token for `action`, or `null` — which the server treats as "no challenge", never as a failure. */
export async function challengeToken(action: string): Promise<string | null> {
  if (!siteKey) return null;
  try {
    const grecaptcha = await withTimeout(loadScript(siteKey), null);
    if (!grecaptcha) return null;
    return await withTimeout(
      new Promise<string | null>((resolve) =>
        grecaptcha.ready(() => grecaptcha.execute(siteKey, { action }).then(resolve, () => resolve(null)))
      ),
      null
    );
  } catch {
    return null;
  }
}
