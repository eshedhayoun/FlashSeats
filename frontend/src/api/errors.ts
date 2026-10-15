import type { Problem } from "./types";

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

export function isProblem(value: unknown): value is Problem {
  if (!isRecord(value)) return false;

  return (
    typeof value.type === "string" &&
    typeof value.title === "string" &&
    typeof value.status === "number" &&
    typeof value.detail === "string" &&
    typeof value.code === "string"
  );
}

export function problemFromUnknown(value: unknown, status: number): Problem {
  if (isProblem(value)) return value;

  return {
    type: "about:blank",
    title: "Request failed",
    status,
    detail: "The server returned an unreadable error response.",
    code: "INTERNAL_ERROR"
  };
}

export class ApiError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail);
    this.name = "ApiError";
    this.problem = problem;
  }

  get code() {
    return this.problem.code;
  }

  get status() {
    return this.problem.status;
  }

  get retryable() {
    return this.problem.retryable ?? false;
  }
}

/**
 * Codes the client mints itself, for failures that never produced a problem document. They are not
 * in the server's registry and never sent to it.
 */
export const NETWORK_ERROR = "NETWORK_ERROR";
export const CLIENT_ERROR = "CLIENT_ERROR";

/** A failure that never reached the server, or never came back from it. */
export function networkError(): ApiError {
  return new ApiError({
    type: "about:blank",
    title: "Network error",
    status: 0,
    detail: "We couldn't reach FlashSeats. Check your connection and try again.",
    code: NETWORK_ERROR,
    retryable: true
  });
}

/** Anything thrown that is not an {@link ApiError}, so every caller switches on one shape. */
export function asApiError(cause: unknown, detail = "Something went wrong. Please try again."): ApiError {
  if (cause instanceof ApiError) return cause;
  return new ApiError({
    type: "about:blank",
    title: "Unexpected error",
    status: 0,
    detail,
    code: CLIENT_ERROR
  });
}

/**
 * Back-pressure, not a fault: the server asks for the same request again shortly (ADR-059,
 * ADR-011). Safe to repeat for every call that uses it, because each is idempotent or
 * find-or-create on the server.
 */
export function isBackPressure(error: unknown): error is ApiError {
  return (
    error instanceof ApiError &&
    (error.code === "SERVICE_BUSY" || error.code === "RATE_LIMITED" || error.code === NETWORK_ERROR)
  );
}
