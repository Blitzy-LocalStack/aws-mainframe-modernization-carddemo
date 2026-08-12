/**
 * Structural tests over the data-transfer contracts of the account bounded context.
 *
 * <p>Purpose: two classes execute here, one per direction of the contract.
 * {@code AccountUpdateRequestContractTest} pins the SUBMITTED shape -- the exact ordered list of all
 * forty-three component names, the declared type of every one of them, and each one's character width held
 * against the baseline symbolic map at {@code app/cpy-bms/COACTUP.CPY} -- because those names are wire names
 * and a rename, a reordering or a retyping is a breaking change to a caller this repository does not
 * contain. {@code AccountUpdateResponseShapeTest} pins the ANSWERED shape, and asserts a property of a
 * TYPE rather than a behaviour of a method -- that the two detail records
 * {@code POST /api/v1/accounts/update} answers with declare no component able to carry a whole
 * national identifier or a whole government-issued identifier, that every submitted value is either
 * mirrored, composed or deliberately withheld, and that the response's two row components are typed to
 * the records the committed contract declares. The subject is the SHAPE of a record, so the assertions
 * read declared components reflectively rather than calling accessors by name.</p>
 *
 * <p>Refactoring Rationale: the subject was a flat {@code AccountUpdateEcho} record, which
 * {@code src/main/resources/openapi/account-api.yaml} does not declare and which no response
 * referenced -- so the disclosure property was being asserted of a type that never left the service.
 * The record is withdrawn and the same property is asserted of the two shapes the contract publishes,
 * {@code AccountDetail} and {@code CustomerDetail}, whose {@code ssnMasked} and
 * {@code governmentIssuedIdMasked} members are the only form either identifier takes on the wire.</p>
 *
 * <p>Alternatives Considered: asserting the same protection by building one response, serialising it
 * and searching the JSON for a digit run. Rejected, because it proves the property only for the
 * instance the test happened to populate and only for the serialiser configuration in force, while the
 * failure it has to catch is a component ADDED by a later edit. A reflective sweep over the declaration
 * fails the moment such a component appears, whether or not any test remembers to populate it, so the
 * assertion belongs over the declaration rather than over an instance.</p>
 *
 * <p>Trade-offs: a reflective assertion names a component when it fails where a value comparison would
 * name a value, which is the weaker diagnostic of the two. That is accepted here because the stronger
 * diagnostic cannot fail at all for the case being guarded against.</p>
 *
 * <h2>What belongs here, and what belongs beside a different layer</h2>
 *
 * <p>Assumptions: this package holds assertions about the declared shape of a transfer type -- its
 * components, their arity, and the invariants its own constructor enforces. It holds no assertion about
 * how such a type is POPULATED. An assertion about the hand-written anti-corruption layer that maps a
 * record image or a submitted request onto these types belongs in the sibling
 * {@code com.carddemo.account.mapper} test package, beside the mapper it covers, and an assertion about
 * the status and body a route returns belongs in the sibling {@code com.carddemo.account.api} test
 * package, beside the adapter that returns them. A rule asserted in two of the three gives a later
 * change two files to update and one to forget.</p>
 *
 * <p>Assumptions: nothing here starts a Spring context, a database, a container or a server socket, and
 * no assertion may be written as though one were available. The subjects are records, and a record needs
 * no runtime beyond the class loader that defines it -- which is also why these assertions are cheap
 * enough to be worth making over every component rather than a sampled few.</p>
 *
 * <p>Assumptions: reading a file from the working tree is admitted by that ruling and is not an exception to
 * it. {@code AccountUpdateRequestContractTest} reads the baseline symbolic map because the widths it asserts
 * have to come from somewhere OTHER than the type under test -- a width taken from the target's own prose
 * would move with the same edit it is supposed to catch. A file read needs no runtime beyond the file
 * system, so the cheapness the ruling protects is preserved, and the precedent is
 * {@code com.carddemo.batch.dto.DisclosureGroupSeedParityTest}, which holds seeded reference rates against
 * the immutable extract the running system loads.</p>
 *
 * <p>Assumptions: this charter governs a DIRECTORY rather than a compilation unit, because that is what
 * Checkstyle's {@code JavadocPackage} rule audits and this module's documentation gate includes test
 * sources. A directory holding a test class and no charter fails the build, so a class added here later
 * is covered by this descriptor with no further edit -- which is why the descriptor states the
 * directory's subject rather than any one class in it.</p>
 *
 * <p>Assumptions: every class here ends in {@code Test}, and that suffix is load-bearing rather than a
 * naming preference. {@code services/pom.xml} splits the two test phases by class name alone --
 * Surefire selecting {@code *Test} in {@code test} and Failsafe selecting {@code *IT} in
 * {@code integration-test}, both left at their default include patterns -- so a class placed here and
 * named to end in {@code IT} would be selected by neither. It would neither run nor fail, and would
 * simply be absent from a complete, green report, which is worse than a missing test because a missing
 * test is visible.</p>
 *
 * <p>Trade-offs: this descriptor exists because the project Explainability rule requires a docstring on
 * every module entry point and, in Java, the entry point of a package is its declaration --
 * {@code package-info.java} being the only compilation unit able to carry one. It is also what
 * {@code JavadocPackage} and {@code MissingJavadocPackage} in {@code config/checkstyle/checkstyle.xml}
 * together require: the first asserts the file exists and would accept an empty one, the second asserts
 * the declaration carries a Javadoc block and would reject it. Reducing this file to its {@code package}
 * line therefore stops the build rather than simplifying anything.</p>
 *
 * <p>Assumptions: the rationale labels used throughout this package are the four
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes -- {@code Alternatives Considered:},
 * {@code Refactoring Rationale:}, {@code Assumptions:} and {@code Trade-offs:} -- in that document's
 * written form: plural, unparenthesised, colon retained and no emphasis markup. A label that is
 * byte-different is a rationale a literal search cannot find, and a rationale a search cannot find is
 * one a review cannot count.</p>
 *
 * <p>Assumptions: the parameter, return-value and exception elements of the rule's docstring
 * specification describe callable code, and a package declaration accepts no argument, yields no value
 * and raises nothing. They are omitted deliberately rather than written out empty, because
 * {@code NonEmptyAtclauseDescription} reports an at-clause with no description as a violation in its own
 * right -- so an empty tag would fail the very gate it was added to satisfy.</p>
 */
package com.carddemo.account.dto;
