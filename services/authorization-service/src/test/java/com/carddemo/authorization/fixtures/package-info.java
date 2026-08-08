/**
 * Executable consumers for every test fixture this module ships.
 *
 * <p>Purpose: this package holds the tests that load each resource under
 * {@code src/test/resources/fixtures} and assert the contract that directory's {@code README.md}
 * records for it -- record width, field values, final record, and failure path. It exists so that a
 * fixture cannot be silently altered: without a consumer, every byte in a fixture is free to change
 * while the whole suite stays green, and a fixture nothing reads documents nothing.
 *
 * <p>Assumptions: the consumers live in their own package rather than beside the production package
 * whose types they exercise. A fixture family spans several production packages at once -- the
 * request wire belongs to the shared codec, the two segment layouts to the shared registry, and the
 * correspondence between a summary and its details to neither -- so filing the consumer under any one
 * of them would have put a cross-cutting assertion under a package that owns only part of it.
 *
 * <p>Alternatives Considered: asserting each fixture inside the test of the class that will eventually
 * read it in production. Rejected for two reasons. The fixture families here are read by more than one
 * collaborator, so the assertions would have been split across several test classes with no single
 * place stating the family's contract; and a fixture whose production reader has not been authored yet
 * would then have no consumer at all, which is precisely the gap this package closes.
 *
 * <p>Assumptions: the inventory of fixtures is asserted as a CLOSED set inside the consumer, not just
 * iterated, and the closure is established by DISCOVERING the fixture directory rather than by naming
 * its contents. {@code AuthorizationFixtureContractTest.everyFixtureIsPresentAndEnrolled} enumerates the
 * directory from the test class path and requires it to name exactly the resources the test enrols, and
 * requires both to number the count the fixture {@code README.md} publishes.
 *
 * <p>Refactoring Rationale: that closure used to be asserted by comparing the enrolled list against a
 * literal count, and a list compared against itself agrees however many files are on disk. The gap was
 * real rather than theoretical -- six fixtures had accumulated in the directory with no enrolment and no
 * consumer of any kind, so their bytes could have changed, or the files disappeared, with the whole
 * module staying green, which is the exact condition the paragraph above claimed to exclude. A fixture
 * added now fails that case until it is enrolled, given a consumer and recorded in the README with the
 * published count moved to match.
 *
 *
 * <p>Assumptions: this directory is the only place in the module where PACKED and BINARY bytes are
 * committed as data, so it is also the only place a decoding mistake is invisible to every other gate
 * in the build. A packed field read as characters, a signed binary counter read as unsigned, or a
 * negative zero re-encoded with a positive sign each produce a file of exactly the right length holding
 * the wrong values. Length is what a build checks; these tests check meaning.
 *
 * <p>Refactoring Rationale: two successive reviews each found committed fixtures that no test read at
 * all. Several exist specifically to pin a hazard -- line-terminator bytes inside a binary field, a
 * negative zero that must decode but must not re-encode, a non-blank filler that must not be dropped,
 * two unload variants that differ only in order, a reply frame carrying one byte more than its declared
 * width, a five-hundred-byte receive buffer, a match status outside its closed domain, and a key whose
 * stored bytes are impossible unless they are complemented -- and a hazard nothing exercises is a hazard
 * nobody is warned about. Every fixture now has at least one consumer that fails if its bytes change,
 * and the directory-discovering closure above is what keeps that true as fixtures are added, in place of
 * the self-comparing count that let the second batch through.
 *
 * <p>Trade-offs: one consumer here decodes the hundred-byte summary segment through a descriptor it
 * builds itself rather than through {@code CopybookLayout.layout("PAUTSUM0")}, and the reason is
 * specific rather than incidental. {@code FixedWidthCodec} drops blank text padding only when the
 * descriptor it is handed is the very instance the registry holds -- the test is on descriptor
 * IDENTITY, not on the record name -- so decoding through the registry would omit the trailing field
 * and put its thirty-four bytes beyond the reach of any assertion. Passing a locally built instance
 * keeps those bytes visible. The drift that a second transcription invites is closed by asserting the
 * local descriptor field-for-field against the registered one, so the two cannot disagree silently.
 *
 * <p>Assumptions: the canonical comma-separated request payload is deliberately NOT re-asserted here.
 * {@code com.carddemo.authorization.contract.WireCharacterConstraintTest} owns it, because there the
 * assertion is about agreement between that payload and the published contract. This package asserts
 * the amount VARIANTS, which that class does not read.
 * <p>Trade-offs: no fixture in this package is read by hand-slicing byte ranges; every one is decoded
 * through a validated descriptor and the shared codec, whether that descriptor is resolved from the
 * registry or built for the reason given above. Hand-slicing would have made these tests a second
 * transcription of the same copybook, free to disagree with the registry they verify; the independence
 * a second transcription would have given is supplied instead by the fixtures having been generated by
 * the Python ETL codec, which is an independently written transcription of the same copybooks.
 */
package com.carddemo.authorization.fixtures;
