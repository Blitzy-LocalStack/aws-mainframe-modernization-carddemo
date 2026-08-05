/**
 * Verifies the shared kernel's token-acceptance and identity-tokenisation contracts.
 *
 * <h2>What this package asserts</h2>
 *
 * <p>Two decisions taken once for every service are asserted here, because both fail silently if they
 * are wrong: a token that should not have been accepted authenticates a request, and an identifier that
 * should have been opaque publishes the value it stands for.</p>
 *
 * <p><strong>Which tokens a resource server accepts.</strong> The identity provider this migration
 * adopts mints two token kinds per sign-in from one issuer, and the descriptive one carries the app
 * client id in its audience claim -- so signature, issuer, audience and time validation, which is the
 * framework's complete set, accepts it. The tests assert that the descriptive token is refused, that a
 * token naming a different client is refused, that a token carrying none of the required scopes is
 * refused, that a token satisfying all three is accepted, and that no refusal reproduces the token or a
 * claim value, since a bearer token in a log is a usable credential.</p>
 *
 * <p><strong>How a protected value becomes an identity.</strong> A queue group identifier and a
 * correlation identifier both need to be stable and neither may be the value it stands for. The tests
 * assert stability under one key, difference across purposes, difference across keys, refusal of key
 * material too short to key the underlying code, and that no token contains the value.</p>
 *
 * <p>Assumptions: tokens under test are built directly rather than obtained from a provider, so the
 * claims are exactly what an expectation names. A test that needed a live user pool would assert the
 * provider's behaviour instead of this code's.</p>
 */
package com.carddemo.common.security;
