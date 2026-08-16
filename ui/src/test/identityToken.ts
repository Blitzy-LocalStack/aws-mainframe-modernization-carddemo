/**
 * @file Mints identity tokens for tests, carrying the claims the auth store validates.
 *
 * Purpose: give every suite that needs a signed-on session ONE place that knows what a token has to
 * carry, so a test installs a session by saying which groups it wants rather than by assembling a
 * token shape from memory.
 *
 * Refactoring Rationale: five suites used to mint their own `header.<base64>.signature` string
 *       carrying nothing but the groups claim, because that was all the store read. The store now
 *       validates the token -- three segments, a named algorithm that is not the unsecured one,
 *       `token_use` of `id`, a subject, and an expiry it is still inside -- so those strings no longer
 *       describe a session, and five suites were asserting behaviour that the delivered application
 *       will not produce. Each could have been given its own updated builder; that is what left them
 *       divergent in the first place, and it would leave the next change to the store to be
 *       discovered five times. The shape lives here once.
 *
 * Assumptions: these tokens are NOT signed and are not meant to be. The store deliberately does
 *       not verify a signature -- it holds no key material and the services verify on every request --
 *       so a test token needs to satisfy the checks that are actually performed and nothing more.
 *       Minting a genuinely signed token would need a key pair and a key-set endpoint per suite, and
 *       would test a verification this application does not perform.
 *
 * Trade-offs: the default expiry is an hour ahead, far outside the store's clock-skew tolerance,
 *       so a suite that runs slowly cannot have a token expire underneath it. Suites that need the
 *       opposite pass an override, which is why the overrides exist at all rather than the helper
 *       exposing one fixed shape.
 */

/** Seconds in one hour, the default distance a minted token's expiry sits in the future. */
const ONE_HOUR_IN_SECONDS = 3600;

/** Milliseconds in one second, for expressing `Date.now` in the units the time claims use. */
const MILLISECONDS_PER_SECOND = 1000;

/** Subject used when a caller does not supply one; any non-empty value satisfies the store. */
export const TEST_SUBJECT = '11111111-2222-3333-4444-555555555555';

/**
 * Claims a caller may override when the default token is not the one under test.
 *
 * Assumptions: every member is optional and each corresponds to one check the store performs, so a
 * suite can express exactly the invalid token it wants to assert on -- an expired one, one issued in
 * the future, one that is not an identity token -- without hand-building base64.
 */
export interface IdentityTokenOverrides {
  /** Value for the `exp` claim, in seconds since the epoch. */
  readonly expiresAt?: number;
  /** Value for the `iat` claim, in seconds since the epoch. */
  readonly issuedAt?: number;
  /** Value for the `nbf` claim, in seconds since the epoch. */
  readonly notBefore?: number;
  /** Value for the `token_use` claim; the store accepts only `id`. */
  readonly tokenUse?: string;
  /** Value for the `sub` claim; the store requires a non-empty string. */
  readonly subject?: string;
  /** Value for the header's `alg`; the store rejects an absent, empty or `none` algorithm. */
  readonly algorithm?: string;
}

/**
 * Encodes one object as a base64url token segment.
 *
 * Assumptions: the padding is stripped and the two substitutions applied, because that is the form the
 * store decodes. Leaving standard base64 in place would still decode -- the store re-pads -- but the
 * token would not look like one a pool issues, and a test fixture that differs from production input
 * in a way nobody noticed is how a decoder bug survives its own test.
 * @param {Record<string, unknown>} value - Object to encode.
 * @returns {string} The object as JSON in unpadded base64url.
 */
function encodeSegment(value: Record<string, unknown>): string {
  return btoa(JSON.stringify(value)).replace(/\+/gu, '-').replace(/\//gu, '_').replace(/=+$/u, '');
}

/**
 * Mints an identity token carrying the supplied groups and claims the auth store accepts.
 * @param {readonly string[]} groups - Group names to place in the `cognito:groups` claim.
 * @param {IdentityTokenOverrides} overrides - Claims to use instead of the valid defaults.
 * @returns {string} A three-segment token whose header and claims satisfy the store's validation.
 */
export function identityTokenFor(
  groups: readonly string[],
  overrides: IdentityTokenOverrides = {},
): string {
  const nowSeconds = Math.floor(Date.now() / MILLISECONDS_PER_SECOND);
  const header = { alg: overrides.algorithm ?? 'RS256', kid: 'test-key', typ: 'JWT' };
  const claims: Record<string, unknown> = {
    sub: overrides.subject ?? TEST_SUBJECT,
    token_use: overrides.tokenUse ?? 'id',
    exp: overrides.expiresAt ?? nowSeconds + ONE_HOUR_IN_SECONDS,
    iat: overrides.issuedAt ?? nowSeconds,
    'cognito:groups': [...groups],
  };
  if (overrides.notBefore !== undefined) {
    claims['nbf'] = overrides.notBefore;
  }
  // Assumptions: the signature segment is a fixed placeholder rather than random bytes. The store
  //   requires the segment to be PRESENT and never inspects it, so a stable value keeps a token
  //   reproducible across runs -- which matters for a suite that asserts a stored token is unchanged.
  return `${encodeSegment(header)}.${encodeSegment(claims)}.test-signature`;
}
