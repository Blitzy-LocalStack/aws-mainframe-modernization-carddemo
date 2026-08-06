// Assumptions: every test API is imported rather than taken from an ambient
// global, because ui/vitest.config.ts sets `globals: false` and records that as a
// contract: ambient test globals are declared per PROJECT, so admitting them here
// would make `expect` and `vi` visible to production screens as well, where a
// stray call would compile.
import type { AxiosRequestConfig, AxiosResponse } from "axios";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { getApiClient } from "./client";

// Refactoring Rationale: the bound asserted below is restated here as a literal
// rather than imported, because the value it must agree with lives in Java --
// `CORRELATION_ID_MAX_LENGTH` in
// `services/common-lib/src/main/java/com/carddemo/common/web/CorrelationIdFilter.java`
// -- and no build step spans the two languages. The Java side is held to the same
// number from its own side by the three contract tests under
// `services/*/src/test/java/**`, each asserting the published `CorrelationId`
// schema against that constant; this file closes the remaining edge, from the
// published schema to the value the browser actually transmits. Asserting a
// literal in each language is the only form available without introducing a
// generated constants module for one integer.
const CORRELATION_ID_MAX_LENGTH = 24;

// Assumptions: the minted shape is exactly twenty-four upper-case hexadecimal
// characters, which is what the service filter produces when it mints one itself.
// The assertion is on the shape rather than merely on the length, because the
// defect being guarded against -- `crypto.randomUUID()`, thirty-six characters
// with four hyphens -- would satisfy a length-only bound the moment anyone
// "fixed" it by truncation, and a truncated UUID still carries a hyphen.
const MINTED_SHAPE = /^[0-9A-F]{24}$/u;

const API_BASE_URL = "https://api.carddemo.example";

const CORRELATION_HEADER = "X-Correlation-Id";

let transmitted: string[] = [];

/**
 * Records the correlation header of one dispatched request and answers it without a network call.
 * @param {AxiosRequestConfig} config - The request configuration after the client's interceptors ran.
 * @returns {Promise<AxiosResponse>} An empty success response carrying the same configuration back.
 */
async function captureAdapter(
  config: AxiosRequestConfig,
): Promise<AxiosResponse> {
  // Assumptions: the header bag is narrowed through `unknown` rather than read
  // straight off the configuration, because Axios types its header collection with
  // an index signature returning `any`, and ui/eslint.config.js refuses an unsafe
  // assignment. Narrowing to a string here also means a non-string value records as
  // the empty string, which fails the shape assertion rather than passing a
  // stringified object.
  const headers: unknown = config.headers;
  const header =
    typeof headers === "object" && headers !== null
      ? (headers as Record<string, unknown>)[CORRELATION_HEADER]
      : undefined;
  transmitted.push(typeof header === "string" ? header : "");
  return Promise.resolve({
    data: {},
    status: 200,
    statusText: "OK",
    headers: {},
    config,
  } as AxiosResponse);
}

/** Supplies the build-time configuration the client validates before it is constructed. */
function stubBuildConfiguration(): void {
  transmitted = [];
  vi.stubEnv("VITE_API_BASE_URL", API_BASE_URL);
  vi.stubEnv("VITE_CORRELATION_ID_HEADER", CORRELATION_HEADER);
}

/** Restores the environment so no later file inherits this file's configuration. */
function restoreBuildConfiguration(): void {
  vi.unstubAllEnvs();
}

/**
 * Dispatches one request through the real client with the network answered locally.
 * @returns {Promise<string>} The correlation identifier that request transmitted.
 */
async function transmitOneRequest(): Promise<string> {
  const client = getApiClient();
  client.defaults.adapter = captureAdapter;
  await client.get("/api/v1/cards");
  const sent = transmitted.at(-1);
  return sent === undefined ? "" : sent;
}

/** Asserts the transmitted identifier fits the bound the shared service filter enforces. */
async function transmitsAnIdentifierWithinTheServiceBound(): Promise<void> {
  const sent = await transmitOneRequest();
  expect(sent.length).toBeLessThanOrEqual(CORRELATION_ID_MAX_LENGTH);
  expect(sent).toMatch(MINTED_SHAPE);
}

/** Asserts the transmitted identifier is not the thirty-six-character value the filter refuses. */
async function neverTransmitsARandomUuid(): Promise<void> {
  const sent = await transmitOneRequest();
  expect(sent).not.toContain("-");
  expect(sent.length).not.toBe(36);
}

/** Asserts each request is correlated separately rather than sharing one identifier. */
async function correlatesEachRequestSeparately(): Promise<void> {
  const first = await transmitOneRequest();
  const second = await transmitOneRequest();
  expect(first).not.toBe(second);
}

/** Groups the assertions that fix the request-correlation contract. */
function requestCorrelationContract(): void {
  beforeEach(stubBuildConfiguration);
  afterEach(restoreBuildConfiguration);
  it(
    "transmits an identifier within the bound the service filter enforces",
    transmitsAnIdentifierWithinTheServiceBound,
  );
  it("never transmits a random UUID", neverTransmitsARandomUuid);
  it("correlates each request separately", correlatesEachRequestSeparately);
}

describe("request correlation contract", requestCorrelationContract);
