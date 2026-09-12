import { ApiError } from "./errors";
import type { Problem } from "./types";

const API_BASE = "/api/v1";

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...init.headers
    }
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const body = (await response.json()) as unknown;
  if (!response.ok) {
    throw new ApiError(body as Problem);
  }

  return body as T;
}
