/**
 * @file Static parity gate between the headers this SPA uses and the CORS lists the edge permits.
 *
 * Purpose
 * -------
 * Prove, without a browser and without a deployment, that every non-safelisted request header this
 * client SETS appears in the API's `cors_allow_headers`, and that every non-safelisted response
 * header this client READS appears in its `cors_expose_headers`. The two inventories live in
 * different languages and different trees — the client's in `ui/src/api/**`, the edge's in
 * `infra/modules/api-gateway-http/variables.tf` — so nothing but a test can hold them together.
 *
 * Why this gate exists
 * --------------------
 * Refactoring Rationale: it was written because the two inventories HAD drifted and the drift was
 * invisible from both sides. The account-update flow reads an entity tag and submits it back as a
 * precondition, and report submission sends an idempotency key; none of those three header names was
 * in the edge's allow or expose defaults, so every deployed browser account update was refused before
 * it started. Neither tree could report it: the client compiles and type-checks against a contract
 * that declares the precondition required, and the Terraform validates and plans against lists that
 * are internally consistent. The failure only appears in a browser, which is the most expensive place
 * to discover it.
 *
 * Assumptions: the failure mode this guards is silent rather than loud, which is why a static
 * assertion is worth its maintenance. A request header absent from the allow list is WITHHELD by the
 * browser after the preflight, so the service never sees the request and its logs show nothing; a
 * response header absent from the expose list is present on the wire and readable in a network panel
 * while `response.headers.get(name)` returns null. Both look like service faults and neither is.
 *
 * How the two inventories are obtained
 * ------------------------------------
 * Alternatives Considered: exporting the client's header constants and importing them here, which is
 * the tidier shape and was rejected. Each of those constants is deliberately module-private — a
 * header name is an implementation detail of the module that sets it — and widening five modules'
 * public surface so a test can read them would make the test's convenience part of the production
 * contract. Reading the source text instead keeps the surface closed, and it is the shape
 * `ui/src/test/contentSecurityPolicy.test.ts` already established in this suite for exactly this kind
 * of cross-tree configuration gate.
 *
 * Trade-offs: parsing source text couples this file to the way those constants are DECLARED, so
 * renaming a constant breaks this test rather than silently passing. That is the intended direction:
 * the extraction below requires each expected constant to be found and fails when one is not, so a
 * rename surfaces here as a failure to locate rather than as an empty inventory that trivially
 * satisfies every assertion. An inventory hard-coded in this file would have the opposite and much
 * worse property — it would keep passing after the client stopped sending the header it names.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

/** Root of the UI package, relative to this file. */
const UI_ROOT = join(import.meta.dirname, '..', '..');

/** Repository root, one level above the UI package. */
const REPO_ROOT = join(UI_ROOT, '..');

/** The edge module whose variable defaults are the deployed CORS configuration. */
const API_GATEWAY_VARIABLES = join(
  REPO_ROOT,
  'infra',
  'modules',
  'api-gateway-http',
  'variables.tf',
);

/**
 * The Terraform variable declarations, read once for every case below.
 *
 * Assumptions: the module DEFAULT is read rather than an environment root's value, because neither
 * `infra/envs/dev` nor `infra/envs/prod` overrides either list — so the default is what both
 * environments actually deploy. A root that begins overriding one would need its own case here, and
 * {@link declaredEnvironmentOverrides} is what turns that from a silent gap into a failure.
 */
const gatewayVariables = readFileSync(API_GATEWAY_VARIABLES, 'utf8');

/**
 * Header names this client SETS on a request, paired with the module constant that declares each.
 *
 * Assumptions: `content-type` is listed even though a browser sets it from the request body rather
 * than from client code, because a JSON body makes the request non-simple and the preflight then
 * checks for that exact name. Omitting it from this inventory would leave the one entry whose absence
 * breaks every POST and PUT unguarded.
 */
const REQUEST_HEADER_SOURCES: readonly HeaderSource[] = [
  { file: join(UI_ROOT, 'src', 'api', 'client.ts'), constant: 'AUTHORIZATION_HEADER' },
  { file: join(UI_ROOT, 'src', 'api', 'client.ts'), constant: 'DEFAULT_CORRELATION_HEADER' },
  { file: join(UI_ROOT, 'src', 'api', 'accounts.ts'), constant: 'PRECONDITION_HEADER' },
  /*
   * WHY : Refactoring Rationale: this entry named `reporting.ts` until the constant moved to
   *       `client.ts`, where the response interceptor now reads it to decide whether a failed request
   *       is repeatable -- a request that carried an idempotency key may be retried, one that did not
   *       may not. The HEADER NAME is unchanged and this file's assertion is unchanged; what changed is
   *       which module declares it, and the inventory reads a DECLARATION rather than an import, so it
   *       had to follow. Leaving it pointed at `reporting.ts` would have failed with "declares no
   *       string constant", which is the inventory refusing to guess rather than a header going
   *       unchecked.
   */
  { file: join(UI_ROOT, 'src', 'api', 'client.ts'), constant: 'IDEMPOTENCY_KEY_HEADER' },
];

/** Header names this client READS from a response, paired with the constant that declares each. */
const RESPONSE_HEADER_SOURCES: readonly HeaderSource[] = [
  { file: join(UI_ROOT, 'src', 'api', 'accounts.ts'), constant: 'REVISION_HEADER' },
];

/**
 * The request header a browser contributes itself, which no client constant declares.
 *
 * Assumptions: named as a constant rather than inlined so the reason it is not extracted from source
 * sits beside the value. It is set by the browser from the body's media type, so there is no client
 * declaration to read.
 */
const BROWSER_SUPPLIED_REQUEST_HEADER = 'content-type';

/**
 * The response header a report submission reads through the transport rather than by name.
 *
 * Assumptions: `location` is followed by the client to learn which execution its submission created,
 * and it is reached through the response object rather than through a named constant, so it is stated
 * here with that reason rather than extracted.
 */
const SUBMISSION_LOCATION_HEADER = 'location';

/**
 * Reads one string-literal constant's value out of a TypeScript source file.
 *
 * Assumptions: the pattern requires a `const <name> = '<value>';` declaration and admits either quote
 * style. It deliberately does NOT fall back to a default when the constant is absent: a missing
 * constant means the client no longer declares that header under that name, and answering with an
 * empty string would let every membership assertion below pass vacuously.
 * @param {string} file - Absolute path of the source file to read.
 * @param {string} constant - Name of the constant whose literal value is wanted.
 * @returns {string} The literal's value, exactly as written.
 * @throws {Error} When the file holds no such constant declaration, which is a drift this test must
 *   report rather than absorb.
 */
function stringConstant(file: string, constant: string): string {
  const source = readFileSync(file, 'utf8');
  const declaration = new RegExp(`const ${constant} = ['"]([^'"]+)['"];`, 'u').exec(source);
  if (declaration === null) {
    throw new Error(
      `${file} declares no string constant named ${constant}; the CORS header contract test cannot ` +
        'read the header inventory it is meant to compare.',
    );
  }
  return declaration[1] ?? '';
}

/** One site that declares a header name: the file holding it and the constant naming it. */
interface HeaderSource {
  /** Absolute path of the source file to read. */
  readonly file: string;
  /** Name of the constant whose string literal is the header name. */
  readonly constant: string;
}

/**
 * Reads one declaring site's header name, lowercased for comparison.
 * @param {HeaderSource} source - The declaring site to read.
 * @returns {string} The header name in lower case.
 */
function lowercasedHeaderName(source: HeaderSource): string {
  return stringConstant(source.file, source.constant).toLowerCase();
}

/**
 * Collects the header names one inventory declares, lowercased for comparison.
 *
 * Assumptions: names are lowercased because HTTP header names are case-insensitive while the two
 * inventories spell them differently on purpose — the client writes `If-Match` and `X-Correlation-Id`
 * in their conventional casing because that is what goes on the wire, and the Terraform lists are
 * lowercase because the module's own validation refuses two entries differing only in case. Comparing
 * the spellings verbatim would report drift between two correct inventories.
 * @param {readonly HeaderSource[]} sources - The declaring sites to read.
 * @returns {readonly string[]} The lowercased header names, in the order the sites are listed.
 */
function headerNames(sources: readonly HeaderSource[]): readonly string[] {
  return sources.map(lowercasedHeaderName);
}

/**
 * Extracts one Terraform list variable's default entries.
 *
 * Assumptions: the variable block is located by name and the FIRST `default = [...]` inside it is
 * read, which is sound because a variable block declares at most one default. The search is bounded
 * to the block by cutting the text at the next `variable "` so a later variable's default cannot be
 * mistaken for this one's.
 * @param {string} name - The Terraform variable name, without quotes.
 * @returns {readonly string[]} The default's entries, lowercased and in declaration order.
 * @throws {Error} When the variable or its default cannot be located, so a renamed or restructured
 *   variable fails loudly instead of yielding an empty list every assertion would satisfy.
 */
function terraformListDefault(name: string): readonly string[] {
  const blockStart = gatewayVariables.indexOf(`variable "${name}"`);
  if (blockStart < 0) {
    throw new Error(`${API_GATEWAY_VARIABLES} declares no variable named ${name}.`);
  }
  const nextBlock = gatewayVariables.indexOf('variable "', blockStart + 1);
  const block = gatewayVariables.slice(
    blockStart,
    nextBlock < 0 ? gatewayVariables.length : nextBlock,
  );
  const defaultList = /default\s*=\s*\[([^\]]*)\]/u.exec(block);
  if (defaultList === null) {
    throw new Error(`Variable ${name} in ${API_GATEWAY_VARIABLES} declares no list default.`);
  }
  return [...(defaultList[1] ?? '').matchAll(/['"]([^'"]+)['"]/gu)].map(quotedEntryValue);
}

/**
 * Returns one quoted list entry's value, lowercased.
 * @param {RegExpMatchArray} entry - One match over a quoted Terraform list entry.
 * @returns {string} The entry's value in lower case, or the empty string when it captured nothing.
 */
function quotedEntryValue(entry: RegExpMatchArray): string {
  return (entry[1] ?? '').toLowerCase();
}

/** Request headers the edge permits a browser to send. */
const allowHeaders = terraformListDefault('cors_allow_headers');

/** Response headers the edge permits a browser to read. */
const exposeHeaders = terraformListDefault('cors_expose_headers');

/**
 * Reports whether either environment root overrides the two lists this gate reads.
 *
 * Assumptions: an override is detected by name across both roots rather than parsed, because the only
 * question here is whether the module default is still the deployed value. A root that starts
 * supplying its own list makes this gate's premise false, and the case below turns that into a
 * failure telling the author to extend this test rather than letting it keep checking a value nothing
 * deploys.
 * @returns {readonly string[]} The root files that mention either variable as an argument, empty when
 *   both roots inherit the module defaults.
 */
function declaredEnvironmentOverrides(): readonly string[] {
  return ['dev', 'prod'].filter(rootOverridesAHeaderList);
}

/**
 * Reports whether one environment root supplies either CORS header list as a module argument.
 * @param {string} environment - The environment root's directory name under `infra/envs`.
 * @returns {boolean} `true` when that root passes either list, so the module default is not deployed.
 */
function rootOverridesAHeaderList(environment: string): boolean {
  const root = join(REPO_ROOT, 'infra', 'envs', environment, 'main.tf');
  return /cors_(allow|expose)_headers\s*=/u.test(readFileSync(root, 'utf8'));
}

/**
 * Asserts the allow list carries every non-safelisted request header this client sets.
 * @returns {void} Nothing; the assertion is the outcome.
 */
function everyRequestHeaderIsAllowed(): void {
  const sent = [...headerNames(REQUEST_HEADER_SOURCES), BROWSER_SUPPLIED_REQUEST_HEADER];
  for (const header of sent) {
    expect(allowHeaders, `cors_allow_headers must list ${header}`).toContain(header);
  }
}

/**
 * Asserts the expose list carries every non-safelisted response header this client reads.
 * @returns {void} Nothing; the assertion is the outcome.
 */
function everyResponseHeaderIsExposed(): void {
  const read = [...headerNames(RESPONSE_HEADER_SOURCES), SUBMISSION_LOCATION_HEADER];
  for (const header of read) {
    expect(exposeHeaders, `cors_expose_headers must list ${header}`).toContain(header);
  }
}

/**
 * Asserts the account-update precondition pair by name, independently of the inventories.
 *
 * Assumptions: this duplicates two entries the inventory cases already cover, deliberately. Those
 * cases would pass if a future edit removed the constant declaring one of them together with its use,
 * which for most headers is a legitimate change — but the account contract declares the entity-tag
 * precondition REQUIRED on the update operation, so this pair cannot legitimately disappear while
 * that contract stands. Naming them makes the regression that motivated this file
 * un-reintroducible rather than merely detectable.
 * @returns {void} Nothing; the assertion is the outcome.
 */
function thePreconditionPairIsConfigured(): void {
  expect(exposeHeaders).toContain('etag');
  expect(allowHeaders).toContain('if-match');
}

/**
 * Asserts the report-submission idempotency key is sendable cross-origin.
 * @returns {void} Nothing; the assertion is the outcome.
 */
function theIdempotencyKeyIsAllowed(): void {
  expect(allowHeaders).toContain('idempotency-key');
}

/**
 * Asserts both lists satisfy the module's own validations.
 *
 * Assumptions: the module's three properties are re-checked here because this gate is what adds
 * entries to those lists, and an entry that trips a Terraform validation fails at plan time — far
 * from the change that caused it. Checking them here means a header added for a client need is
 * refused in this suite first.
 * @returns {void} Nothing; the assertion is the outcome.
 */
function bothListsSatisfyTheModuleValidations(): void {
  for (const list of [allowHeaders, exposeHeaders]) {
    expect(list).not.toContain('*');
    expect(list).toStrictEqual(list.map(toLowerCase));
    expect(new Set(list).size).toBe(list.length);
  }
}

/**
 * Lowercases one header name.
 * @param {string} header - The header name to fold.
 * @returns {string} The name in lower case.
 */
function toLowerCase(header: string): string {
  return header.toLowerCase();
}

/**
 * Asserts this gate is still reading the value both environment roots deploy.
 * @returns {void} Nothing; the assertion is the outcome.
 */
function bothRootsStillInheritTheModuleDefaults(): void {
  expect(declaredEnvironmentOverrides()).toStrictEqual([]);
}

/**
 * Registers the cases proving the edge admits every header this client uses.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function corsHeaderContractCases(): void {
  it('permits every request header the client sets', everyRequestHeaderIsAllowed);
  it('exposes every response header the client reads', everyResponseHeaderIsExposed);
  it('names the entity tag and its precondition', thePreconditionPairIsConfigured);
  it('names the idempotency key on the allow list', theIdempotencyKeyIsAllowed);
  it('keeps both lists lowercase, unique and wildcard-free', bothListsSatisfyTheModuleValidations);
  it('still reads the defaults both roots inherit', bothRootsStillInheritTheModuleDefaults);
}

describe('edge CORS configuration admits every header this client uses', corsHeaderContractCases);
