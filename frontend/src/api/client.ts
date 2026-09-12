import { ApiError, problemFromUnknown } from "./errors";
import { serverClock } from "../clock/serverClock";

const API_BASE = "/api/v1";

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

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: "include",
    headers: requestHeaders(init)
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const body = await readJson(response);
  if (!response.ok) {
    throw new ApiError(problemFromUnknown(body, response.status));
  }

  updateServerClock(body);
  return body as T;
}

export async function downloadPdf(path: string, init: RequestInit = {}): Promise<Blob> {
  const headers = requestHeaders(init);
  headers.set("Accept", "application/pdf, application/problem+json");

  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: "include",
    headers
  });

  if (response.ok) {
    return response.blob();
  }

  const body = await readJson(response);
  throw new ApiError(problemFromUnknown(body, response.status));
}
