/**
 * Verifies the shared web contracts of the kernel: the sealed keyset cursor token and the paging
 * envelope that carries it.
 *
 * <h2>What this package asserts, and why it exists at all</h2>
 *
 * <p>Two properties are under test here, and both are security properties rather than behavioural
 * conveniences, which is why they are asserted rather than left to review.</p>
 *
 * <p>The first is that a page boundary presented to a client is <b>opaque and authenticated</b>. The
 * reference baseline echoed its browse cursor to a 3270 terminal inside a communication area -- a
 * composite of card number and account identifier at lines 230 to 232 of {@code app/cbl/COCRDLIC.cbl},
 * and a transaction identifier at line 595 of {@code app/cbl/COTRN00C.cbl}. That echo stayed inside a
 * CICS session; the migrated equivalent answers a browser across a public edge, so the same echo would
 * publish a primary account number to the client. The tests here assert that a raw key is refused by
 * the envelope, that a sealed token round-trips to the key it was built from, that a token altered by
 * one character fails to open, that a token presented under a different query or subject binding fails
 * to open, and that an expired token is refused.</p>
 *
 * <p>The second is that neither type reports the value it rejected. Every refusal these types raise
 * names the shape, the length or the age of what arrived and never its characters, because the value a
 * caller is most likely to have supplied by mistake is exactly the raw card number the design exists to
 * withhold, and an exception message becomes a log line and an error body. The tests assert the absence
 * of that content in the message, which is the only way an absence stays absent.</p>
 *
 * <p>Assumptions: key material in these tests is a fixed literal of the required width, generated
 * nowhere and read from nothing. A test that drew a key from the environment would pass or fail
 * according to what the environment held, and a test that generated one would be unable to assert that
 * a token sealed a moment ago still opens.</p>
 *
 * <p>Alternatives Considered: asserting the sealed shape by matching the token against the same
 * expression the production code uses. Rejected, because a test that reuses the implementation's own
 * predicate asserts only that the predicate equals itself. The expectations here are written in terms
 * of observable behaviour -- what opens, what is refused -- so a change to the token format that broke
 * a client would fail a test rather than passing along with the code.</p>
 */
package com.carddemo.common.web;
