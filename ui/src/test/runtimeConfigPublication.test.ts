/**
 * @file Pins the container path's runtime-configuration publication and the no-source-map rule.
 *
 * WHY this file exists — Refactoring Rationale: the image had no way to be told which API to call.
 * `ui/docker-entrypoint.sh` rendered the policy placeholder and nothing else, the bundle carries no
 * compiled `VITE_API_BASE_URL` — it is built before the environment that creates the endpoint
 * exists — and `ui/src/api/runtimeConfig.ts` treats an ABSENT document as "use the compiled value".
 * A container therefore started cleanly and mounted an application whose every request was
 * unconfigured, which reached an operator as a sign-on refusing a credential that never left the
 * browser. The entrypoint now requires `CARDDEMO_API_BASE_URL`, publishes it as `/config.json`, and
 * derives the policy's `connect-src` origin from the same value.
 *
 * WHY : Alternatives Considered: starting a container and reading the document, which is how this
 * was verified once by hand. Rejected as the standing check for the same reason
 * `contentSecurityPolicy.test.ts` rejects it: it needs a container runtime `npm test` cannot assume,
 * and a case that silently skips when Docker is absent reports green on the platforms most likely to
 * be running it. What is asserted here instead are the properties readable from the sources — the
 * required input, the published shape, the atomic publication, the ordering against `exec`, and the
 * cross-file agreements that no single file can guarantee alone.
 *
 * WHY : Assumptions: the source-map rule is asserted in this file rather than in one of its own,
 * because it is the same class of claim: a build setting whose truth was asserted by three documents
 * and contradicted by the setting itself. Keeping the assertion beside the other cross-file
 * agreements is what makes the contradiction impossible to reintroduce quietly.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

/** Root of the UI package, relative to this file. */
const UI_ROOT = join(import.meta.dirname, '..', '..');

/** The renderer and publisher that runs at container start. */
const entrypoint = readFileSync(join(UI_ROOT, 'docker-entrypoint.sh'), 'utf8');

/** The site definition, installed into the image as a template. */
const nginxTemplate = readFileSync(join(UI_ROOT, 'nginx.conf'), 'utf8');

/** The browser-side loader that reads the published document. */
const runtimeConfigModule = readFileSync(join(UI_ROOT, 'src', 'api', 'runtimeConfig.ts'), 'utf8');

/** The build configuration whose output the image serves. */
const viteConfig = readFileSync(join(UI_ROOT, 'vite.config.ts'), 'utf8');

/** The package's own operator documentation. */
const readme = readFileSync(join(UI_ROOT, 'README.md'), 'utf8');

/**
 * Strips `//` line comments from a TypeScript source.
 *
 * Refactoring Rationale: this exists for the reason the sibling policy test strips nginx comments:
 * an assertion that reads comments cannot tell a configured value from a described one, so a check
 * for `sourcemap: false` would pass against a file whose comment merely MENTIONS the value while its
 * setting says otherwise — which is exactly the contradiction this file exists to prevent.
 * @param {string} source - Contents of a TypeScript source file.
 * @returns {string} The same text with every whole-line `//` comment removed.
 */
function settingsOnly(source: string): string {
  /**
   * Reports whether one line is anything other than a whole-line comment.
   *
   * Assumptions: a named declaration rather than an inline predicate, because
   * `ui/eslint.config.js` selects a function expression in every position and Prettier detaches a
   * block comment written above an argument from the function it documents.
   * @param {string} line - One line of the source.
   * @returns {boolean} `true` when the line carries a setting rather than commentary.
   */
  function isNotAWholeLineComment(line: string): boolean {
    return !line.trimStart().startsWith('//');
  }

  return source.split('\n').filter(isNotAWholeLineComment).join('\n');
}

/**
 * Asserts the API base URL is required input rather than an optional one.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theApiBaseUrlIsRequired(): void {
  expect(entrypoint).toContain('CARDDEMO_API_BASE_URL');
  // WHY : the refusal is asserted rather than the variable's mere presence. A script that read the
  //       variable and carried on with an empty value would satisfy a presence check while leaving
  //       the exact defect this file was written for.
  expect(entrypoint).toContain('is unset or empty');
  expect(entrypoint, 'a refusal must name the shapes that would have been accepted').toContain(
    'accepted shapes are an absolute https URL ending in /api/v1',
  );
}

/**
 * Asserts each shape the entrypoint must refuse has its own refusal.
 *
 * Assumptions: the four are asserted separately because they fail for different reasons and an
 * operator needs to be told which one applies — a single "invalid value" would send them back to the
 * documentation to guess.
 * @returns {void} Nothing; assertions raise on failure.
 */
function unsafeShapesAreRefused(): void {
  expect(entrypoint).toContain('must carry no wildcard');
  expect(entrypoint).toContain('must carry no query and no fragment');
  expect(entrypoint).toContain('must be a single value carrying no whitespace');
  expect(entrypoint).toContain('is not one of the accepted shapes');
  // Assumptions: both accepted shapes are anchored at the operation prefix, which is what makes a
  //   base URL one segment short a refusal at start rather than a 404 on every request.
  expect(entrypoint).toContain("absolute_api='^https://");
  expect(entrypoint, 'the absolute shape must end at the operation prefix').toContain(
    "/api/v1$'\n",
  );
  expect(entrypoint).toContain("same_origin_api='^(/[A-Za-z0-9._~-]+)*/api/v1$'");
}

/**
 * Asserts the document is published atomically, into the served root, before nginx is exec'd.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theDocumentIsPublishedAtomicallyBeforeNginxStarts(): void {
  expect(entrypoint).toContain('printf \'{"apiBaseUrl":"%s"}\\n\' "$api_base_url"');
  expect(entrypoint, 'a reader must never observe a partial document').toContain(
    'mv -f "$CONFIG_STAGING" "$CONFIG_DOCUMENT"',
  );
  // Assumptions: the staging path is asserted to sit INSIDE the document root, because `mv` is
  //   atomic only within one filesystem -- staging in /tmp would degrade to copy-then-unlink and
  //   reopen the window the move exists to close.
  expect(entrypoint).toContain('CONFIG_STAGING="$DOCUMENT_ROOT/');
  expect(entrypoint).toContain('CONFIG_DOCUMENT="$DOCUMENT_ROOT/config.json"');

  const publication = entrypoint.indexOf('mv -f "$CONFIG_STAGING"');
  const handover = entrypoint.indexOf("exec nginx -g 'daemon off;'");
  expect(publication).toBeGreaterThan(-1);
  expect(handover).toBeGreaterThan(-1);
  // WHY : ordering is the property, not presence. Publishing after `exec` is unreachable code, and
  //       publishing after nginx began serving would let a health check or a browser read the
  //       previous deployment's document.
  expect(
    publication,
    'the document must exist before the first request can be served',
  ).toBeLessThan(handover);
}

/**
 * Asserts the policy's API origin is derived from the same value the document publishes.
 * @returns {void} Nothing; assertions raise on failure.
 */
function thePolicyOriginIsDerivedFromTheApiBaseUrl(): void {
  expect(entrypoint).toContain('rendered_origins="$rendered_origins $api_origin"');
  // Assumptions: the de-duplication is asserted because naming the API origin in both variables is
  //   the ordinary case for a deployment that also lists it explicitly, and a header listing one
  //   host twice reads as a rendering fault to whoever audits it.
  expect(entrypoint).toContain('*" $api_origin "*)');
}

/**
 * Asserts the document root the entrypoint writes to is the root nginx serves.
 *
 * WHY : Assumptions: nothing else checks this pair. The entrypoint could publish a valid document
 * into a directory no location serves, and every symptom would appear on the browser side — a 404
 * for `/config.json`, read as an absent document, falling back to a compiled value the image does
 * not carry.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theDocumentRootMatchesTheServedRoot(): void {
  const servedRoot = /^\s*root\s+(\S+);/mu.exec(nginxTemplate);
  const publishedRoot = /^DOCUMENT_ROOT=(\S+)$/mu.exec(entrypoint);
  expect(servedRoot).not.toBeNull();
  expect(publishedRoot).not.toBeNull();
  expect(publishedRoot?.[1]).toBe(servedRoot?.[1]);
}

/**
 * Asserts the key the entrypoint writes is the key the browser reads.
 *
 * Assumptions: the two are in different languages with no build step spanning them, so the only
 * thing keeping `apiBaseUrl` spelled identically on both sides is an assertion that reads both.
 * @returns {void} Nothing; assertions raise on failure.
 */
function thePublishedKeyIsTheKeyTheLoaderReads(): void {
  expect(entrypoint).toContain('"apiBaseUrl"');
  expect(runtimeConfigModule).toContain('.apiBaseUrl');
  expect(runtimeConfigModule, 'the loader must read the same same-origin path form').toContain(
    "API_BASE_URL_REQUIRED_SUFFIX = '/api/v1'",
  );
}

/**
 * Asserts no production source map is emitted, and that the documents saying so are now true.
 * @returns {void} Nothing; assertions raise on failure.
 */
function noProductionSourceMapIsPublished(): void {
  const settings = settingsOnly(viteConfig);
  expect(settings).toContain('sourcemap: false');
  expect(settings, 'a published map discloses the original module structure').not.toContain(
    'sourcemap: true',
  );
  // Assumptions: the two documents are asserted as well as the setting, because the defect this
  //   replaces was not a wrong setting alone -- it was a setting contradicted by every document
  //   describing it, so a reader could not tell which was authoritative.
  expect(readme).toContain('publish **no source maps**');
  // Assumptions: the nginx comment is read with its continuation prefixes collapsed, so the
  //   assertion survives a reflow of the comment block. Matching the wrapped text literally would
  //   fail on a purely cosmetic edit, and a test that fails for cosmetic reasons gets relaxed rather
  //   than fixed.
  expect(nginxTemplate.replaceAll(/\n\s*#\s*/gu, ' ')).toContain('sets `sourcemap` to false');
}

/**
 * Registers the runtime-configuration publication cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function runtimeConfigurationPublicationCases(): void {
  it('requires an API base URL at container start', theApiBaseUrlIsRequired);
  it('refuses every unsafe or unusable shape', unsafeShapesAreRefused);
  it(
    'publishes the document atomically before nginx starts',
    theDocumentIsPublishedAtomicallyBeforeNginxStarts,
  );
  it('derives the policy origin from the API base URL', thePolicyOriginIsDerivedFromTheApiBaseUrl);
  it('writes into the root nginx serves', theDocumentRootMatchesTheServedRoot);
  it('publishes the key the browser reads', thePublishedKeyIsTheKeyTheLoaderReads);
  it('publishes no production source map', noProductionSourceMapIsPublished);
}

describe('container runtime configuration', runtimeConfigurationPublicationCases);
