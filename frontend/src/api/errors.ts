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
