/**
 * Tests that bind the wire contract this context PUBLISHES to the codec that IMPLEMENTS it.
 *
 * <p>Assumptions: the two artifacts under comparison here are of different kinds and no compiler
 * relates them. One is a YAML document,
 * {@code src/main/resources/openapi/authorization-api.yaml}, which states in a JSON Schema pattern
 * what a value on the comma-separated authorization wire may contain. The other is Java,
 * {@code com.carddemo.common.codec.CsvAuthCodec}, which refuses a value that violates the same
 * policy. A regular expression in a document and a character scan in a constructor can disagree
 * indefinitely without any build noticing, because each is internally valid. The class here is that
 * comparison, run as a test.</p>
 *
 * <p>Refactoring Rationale: this package exists because the two DID disagree, twice over, and both
 * disagreements were found by writing an assertion rather than by reading. The published schemas
 * first bounded every member by LENGTH only, so a merchant name containing the delimiter satisfied
 * the contract and was then refused by the codec. When a shared constraint was added to close that,
 * its first published form admitted control characters -- accurately describing the encoder of the
 * day and inaccurately describing the decoder, which already refused them across a whole payload.
 * Neither gap was visible to any gate in the build.</p>
 *
 * <p>Alternatives Considered: restating the published pattern as a literal in this package and
 * asserting the codec against that. Rejected: a literal copy is a third artifact to keep in step,
 * and it would keep passing after the published document drifted, which is the precise failure this
 * package exists to prevent. The pattern is therefore READ off the test class path from the packaged
 * contract, so the assertion is about the shipped document.</p>
 *
 * <p>Trade-offs: agreement is asserted over a TABLE of candidate values rather than proved for the
 * whole character space. A proof would enumerate every code point, which is feasible for the
 * single-byte range but not beyond it, and would still not cover the surrogate case that motivates
 * one of the rules. The table is chosen instead and populated at the boundaries -- on each side of
 * the delimiter, on each side of both control ranges, and on each side of the single-byte ceiling --
 * because a disagreement between two range expressions appears at a boundary or not at all.</p>
 */
package com.carddemo.authorization.contract;
