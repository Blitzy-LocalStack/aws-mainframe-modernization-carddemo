/**
 * Verifies the shared kernel's token-acceptance and identity-tokenisation contracts.
 *
 * <h2>What this package asserts</h2>
 *
 * <p>Three decisions taken once for every service are asserted here, because each fails silently if it
 * is wrong: a token that should not have been accepted authenticates a request, an identifier that
 * should have been opaque publishes the value it stands for, and a card number that should have been
 * masked is published in full by a response type whose constraint admitted it.</p>
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
 * <p><strong>What a masked card number IS.</strong> The masker produces a rendering; nothing established
 * what an acceptable one looks like, so three response contracts each checked a weaker approximation and
 * two of them admitted a full sixteen-digit number. The tests assert that the masker's own output is
 * accepted, that a full number and a value carrying a single mask character are refused, that six
 * near-miss shapes are refused, that the pattern constant and the imperative guard agree on every value
 * exercised, and that no refusal quotes the candidate, since a candidate reaching a refusal may be the
 * number itself.</p>
 *
 * <p>Assumptions: tokens under test are built directly rather than obtained from a provider, so the
 * claims are exactly what an expectation names. A test that needed a live user pool would assert the
 * provider's behaviour instead of this code's.</p>
 */
package com.carddemo.common.security;
