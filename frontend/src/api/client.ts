import { ApiError, isBackPressure, networkError, problemFromUnknown } from "./errors";
import { serverClock } from "../clock/serverClock";

const API_BASE = "/api/v1";

export type RequestOptions = RequestInit & {
  /**
   * How many times to repeat the request on back-pressure (`SERVICE_BUSY`, `RATE_LIMITED`, a dropped
   * connection) before surfacing it. A handful at most, never a loop: ten thousand clients retrying
   * forever is the thundering herd the waiting room exists to prevent (FE_SPEC §5).
   */
  retries?: number;
};

type ServerTimedResponse = {
  serverTime?: unknown;
};

function updateServerClock(body: unknown) {
  if (
    typeof body === "object" &&
    body !== null &&
    typeof (body as ServerTimedResponse).serverTime === "string"
  ) {
    serverClock.update((body as ServerTimedResponse).serverTime as string);
  }
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return undefined;

  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

function requestHeaders(init: RequestInit): Headers {
  const headers = new Headers(init.headers);
  if (!headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  return headers;
}

/** The problem document, with `Retry-After` folded in when the body did not carry it. */
async function problemOf(response: Response): Promise<ApiError> {
  const problem = problemFromUnknown(await readJson(response), response.status);
  const retryAfter = Number(response.headers.get("Retry-After"));
  if (problem.retryAfterSeconds == null && Number.isFinite(retryAfter) && retryAfter > 0) {
    problem.retryAfterSeconds = retryAfter;
  }
  return new ApiError(problem);
}

async function send(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(`${API_BASE}${path}`, { ...init, credentials: "include" });
  } catch {
    throw networkError();
  }
}

/** The server's own estimate when it gave one, else full jitter: never a fixed interval. */
export function backoffMs(error: ApiError, attempt: number): number {
  const asked = error.problem.retryAfterSeconds;
  if (asked != null && asked > 0) {
    return asked * 1000 + Math.random() * 250;
  }
  return Math.random() * Math.min(30_000, 500 * 2 ** attempt);
}

const sleep = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms));

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { retries = 0, ...init } = options;

  for (let attempt = 0; ; attempt++) {
    try {
      const response = await send(path, { ...init, headers: requestHeaders(init) });
      if (response.status === 204) {
        return undefined as T;
      }
      if (!response.ok) {
        throw await problemOf(response);
      }
      const body = await readJson(response);
      updateServerClock(body);
      return body as T;
    } catch (cause) {
      if (attempt >= retries || !isBackPressure(cause)) throw cause;
      await sleep(backoffMs(cause, attempt));
    }
  }
}

export async function downloadPdf(path: string): Promise<Blob> {
  // Asking for the PDF alone would make every failure here unnegotiable: a 406 instead of the
  // problem document that says why (FE_SPEC V5).
  const response = await send(path, {
    headers: { Accept: "application/pdf, application/problem+json" }
  });
  if (response.ok) {
    return response.blob();
  }
  throw await problemOf(response);
}

export { ApiError };
