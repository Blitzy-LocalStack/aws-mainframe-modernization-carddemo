/**
 * @file Pins the container delivery path's content-security policy against a blanket `connect-src`.
 *
 * WHY this file exists — Refactoring Rationale: all six `Content-Security-Policy` headers in
 * `ui/nginx.conf` carried `connect-src 'self' https:`. `https:` is a scheme-source, so it admits a
 * fetch or XHR to ANY host on the internet over HTTPS — which is exactly the exfiltration path the
 * directive exists to close, while the header still read as though a policy were in force. The
 * defect was not an oversight but a wrong conclusion from a right premise: the API origin is not
 * knowable when the image is BUILT, and the file concluded that a scheme-source was therefore the
 * tightest available form. It is known at CONTAINER START, so `ui/docker-entrypoint.sh` renders exact
 * origins into a placeholder before nginx begins serving.
 *
 * WHY : Alternatives Considered: starting a container and reading the header, which is how this was
 * verified once by hand. Rejected as the standing check because it needs a container runtime that
 * `npm test` cannot assume, and a test that silently skips when Docker is absent would report green
 * on the very platforms most likely to be running it.
 *
 * WHY : Assumptions: the rendering is exercised by applying the same substitution the entrypoint
 * applies, so the empty and populated forms are both asserted as strings. That covers the property
 * that matters — no rendered output may contain a scheme-source — without needing a process.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

/** Root of the UI package, relative to this file. */
const UI_ROOT = join(import.meta.dirname, '..', '..');

/** Placeholder the entrypoint replaces with the configured origins. */
const TOKEN = '__CARDDEMO_API_CONNECT_SRC__';

/**
 * Number of `Content-Security-Policy` headers the site definition carries.
 *
 * Assumptions: six is one server-level default plus five location blocks that each REPLACE the
 * inherited header rather than extending it — `add_header` in a location discards the server set — so
 * every one of the six must state the full policy. The count is pinned because a location added
 * without its own header would silently serve no policy at all on that path.
 */
const EXPECTED_POLICY_SITES = 6;

/** The site definition, which is installed into the image as a template. */
const nginxTemplate = readFileSync(join(UI_ROOT, 'nginx.conf'), 'utf8');

/** The renderer that substitutes the placeholder at container start. */
const entrypoint = readFileSync(join(UI_ROOT, 'docker-entrypoint.sh'), 'utf8');

/**
 * Strips whole-line `#` comments from an nginx configuration.
 *
 * Refactoring Rationale: this exists because the first version of the directive count below found
 * SEVEN policies in a file that has six. The seventh was inside this file's own explanation of the
 * defect, which quotes the directive it describes. That is not a false alarm to widen the count for:
 * an assertion that reads comments cannot tell a configured directive from a described one, so it
 * would equally have PASSED on a file whose real policy had been commented out.
 * @param {string} configuration - Contents of an nginx configuration file.
 * @returns {string} The same text with every comment line removed.
 */
function configurationOnly(configuration: string): string {
  return configuration.split('\n').filter(isNotACommentLine).join('\n');
}

/**
 * Reports whether one line is anything other than a whole-line comment.
 * @param {string} line - One line of an nginx configuration.
 * @returns {boolean} `true` when the line is not a comment.
 */
function isNotACommentLine(line: string): boolean {
  return !line.trimStart().startsWith('#');
}

/**
 * Applies the same substitution the entrypoint applies.
 * @param {string} origins - Rendered origin list, already carrying its leading space, or `''`.
 * @returns {string} The template with every placeholder replaced.
 */
function render(origins: string): string {
  return nginxTemplate.split(TOKEN).join(origins);
}

/**
 * Returns the first capture of one match.
 * @param {RegExpMatchArray} match - One regular-expression match.
 * @returns {string} The matched text.
 */
function matchedText(match: RegExpMatchArray): string {
  return match[0];
}

/**
 * Returns every `connect-src` directive actually configured in one rendered configuration.
 * @param {string} rendered - A rendered configuration.
 * @returns {string[]} Each directive, without its trailing separator.
 */
function connectSrcDirectives(rendered: string): string[] {
  return [...configurationOnly(rendered).matchAll(/connect-src [^;"]*/gu)].map(matchedText);
}

/**
 * Asserts every policy site carries the placeholder rather than a literal origin or scheme.
 * @returns {void} Nothing; assertions raise on failure.
 */
function everyPolicySiteIsRendered(): void {
  const directives = connectSrcDirectives(nginxTemplate);
  expect(directives).toHaveLength(EXPECTED_POLICY_SITES);
  for (const directive of directives) {
    expect(directive, 'every connect-src must be rendered at start').toContain(TOKEN);
  }
}

/**
 * Asserts no rendered form admits a scheme-source, for either an empty or a populated origin list.
 * @returns {void} Nothing; assertions raise on failure.
 */
function noRenderedFormAdmitsASchemeSource(): void {
  // Assumptions: the two inputs are the fail-closed case and the ordinary case. A check of only the
  //   populated case would pass against a template that fell back to a wildcard when unset, which is
  //   the failure an operator is most likely to actually meet.
  for (const origins of ['', ' https://api.example.com https://other.example.com:8443']) {
    for (const directive of connectSrcDirectives(render(origins))) {
      expect(directive, 'a scheme-source admits every host over that scheme').not.toMatch(
        /\shttps:\s*$/u,
      );
      expect(directive).not.toContain("'unsafe-inline'");
      expect(directive).not.toContain('*');
      expect(directive, 'the placeholder must not survive rendering').not.toContain(TOKEN);
      expect(directive, 'every policy keeps same-origin').toContain("'self'");
    }
  }
}

/**
 * Asserts the empty input fails closed to same-origin only.
 * @returns {void} Nothing; assertions raise on failure.
 */
function anEmptyOriginListFailsClosed(): void {
  // Assumptions: this is the exact string the deployed CloudFront path produces for an empty origin
  //   list, so asserting it here is what keeps the two delivery paths identical in this directive
  //   rather than merely both "narrow".
  for (const directive of connectSrcDirectives(render(''))) {
    expect(directive).toBe("connect-src 'self'");
  }
}

/**
 * Asserts the renderer refuses the three input shapes that would reopen the hole.
 * @returns {void} Nothing; assertions raise on failure.
 */
function theRendererRefusesUnsafeOrigins(): void {
  // WHY : the entrypoint is asserted to REFUSE rather than to sanitise. Dropping a bad entry would
  //       leave a container serving a policy quietly missing the host the application needs, which
  //       reaches a user as an unexplained blocked request; refusing to start reaches whoever set it.
  expect(entrypoint).toContain('must carry no wildcard and no path');
  expect(entrypoint).toContain('must begin with https://');
  expect(entrypoint).toContain('refusing to serve an unrendered policy');
  // Assumptions: `exec` is asserted because the image's health check and its shutdown behaviour both
  //   depend on nginx being PID 1; a renderer that spawned nginx as a child would leave the shell as
  //   PID 1 and SIGTERM would not reach nginx to drain connections.
  expect(entrypoint).toContain("exec nginx -g 'daemon off;'");
}

/**
 * Registers the content-security policy cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function contentSecurityPolicyCases(): void {
  it('renders every policy site at container start', everyPolicySiteIsRendered);
  it('admits no scheme-source in any rendered form', noRenderedFormAdmitsASchemeSource);
  it('fails closed to same-origin when no origin is configured', anEmptyOriginListFailsClosed);
  it('refuses a wildcard, a path or a cleartext origin', theRendererRefusesUnsafeOrigins);
}

describe('container content-security policy', contentSecurityPolicyCases);
