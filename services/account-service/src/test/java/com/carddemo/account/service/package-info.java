/**
 * Unit tests for this context's service layer, exercised against mocked collaborators.
 *
 * <p><b>Purpose.</b> EIGHT classes execute here, one per rule or entry point of this context's service
 * layer, all against substituted collaborators unless a class's own subject forbids it.</p>
 *
 * <ul>
 *   <li>{@code InquiryMessageListenerTest} covers the asynchronous entry point -- the request/reply
 *       exchange transcribed from {@code app/app-vsam-mq/cbl/COACCT01.cbl}, whose fixed reply layout is a
 *       wire contract rather than a formatting choice -- with no database, no container and no queue.</li>
 *   <li>{@code RestReferenceAddressLookupTest} covers the outbound synchronous adapter, the account side
 *       of the reference context's published address lookups. It is the one exception to the
 *       mock-everything sentence above, for the reason the trade-off below records.</li>
 *   <li>{@code AccountUpdatePreservationTest} covers which submitted values the update applies and which
 *       stored values it preserves, from {@code app/cbl/COACTUPC.cbl}.</li>
 *   <li>{@code AccountAddressValidationTest} covers the three address edits the same program performs
 *       against the seeded lookups of {@code app/cpy/CSLKPCDY.cpy}.</li>
 *   <li>{@code AccountViewRevisionTest} covers the optimistic-concurrency token the read path publishes
 *       and the single composing read it is derived from.</li>
 *   <li>{@code CardXrefByAccountReadTest} covers the two migrated forms of the {@code CXACAIX}
 *       by-account access path, the deterministic single read and the paged walk.</li>
 *   <li>{@code CustomerMasterReadTest} covers the customer-master read the screen composes with.</li>
 *   <li>{@code CustomerIdentifierCipherTest} covers the at-rest protection of the two identifiers the
 *       customer record carries in the clear in the baseline.</li>
 * </ul>
 *
 * <p>⚠️ Refactoring Rationale: this paragraph read "Two classes execute here" and named two, while the
 * directory held seven. It had already been corrected once -- from "One class executes here" -- and the
 * correction's own stated reason is why it is corrected again with a roster rather than a number alone:
 * a charter that states a count is what makes a missing class visible, and a count six short of the
 * directory makes six classes invisible instead. Alternatives Considered: loosening the sentence to "the
 * classes here", which cannot go stale. Rejected for the reason the earlier correction gives -- it would
 * absorb every future addition silently -- and because the roster carries what a count cannot: which rule
 * each class is responsible for, so a reader can tell a rule asserted twice from one asserted nowhere.</p>
 *
 * <p>Trade-offs: {@code RestReferenceAddressLookupTest} binds a real loopback HTTP server on an
 * ephemeral port instead of mocking its transport, so this package is not uniformly mock-only. That is
 * a consequence of its subject rather than a departure from the placement rule: the class under test IS
 * the HTTP adapter, its constructor installs its own request factory to carry a connect timeout and a
 * read timeout, and {@code MockRestServiceServer} works by installing a competing factory on the same
 * builder -- so the shorter route would have proved something about whichever factory won rather than
 * about the adapter. The server class ships with the platform, so no dependency is added, and the port
 * is ephemeral, so parallel execution cannot collide.</p>
 *
 * <p>Assumptions: the authentication of an INTERNAL caller is not asserted here, and the omission is a
 * placement decision rather than a gap. That decision is made by an ordered filter chain --
 * {@code com.carddemo.account.config.InternalApiSecurityConfig} -- whose token is verified by the
 * framework's own decoder, so what there is to assert is which chain matches which path and which
 * authority it requires. That belongs beside the chain, and
 * {@code com.carddemo.account.config.InternalApiSecurityConfigTest} holds it.</p>
 *
 * <p>Assumptions: this charter governs a DIRECTORY rather than a compilation unit, because that is what
 * Checkstyle's {@code JavadocPackage} rule audits and this module's documentation gate includes test
 * sources. A package that holds a test class and no charter fails the build, so a class added here later
 * is covered by this descriptor without any further edit -- and the descriptor has to describe the
 * directory's subject rather than any one class in it.</p>
 *
 * <p>Assumptions: what belongs here is a decision, and what does not is a transport contract or a schema
 * constraint. The status a refused caller receives, and which rule of the filter chain refuses it, are
 * asserted in the sibling {@code com.carddemo.account.config} test package against
 * {@code SecurityConfig}'s own installed authorization managers; the schema constraints this service owns
 * are asserted by the container-backed {@code *RepositoryIT} classes that run under Failsafe. A rule
 * asserted in two of the three places gives a later change two files to update and one to forget.</p>
 *
 * <p>Assumptions: every class here ends in {@code Test} and that suffix is load-bearing rather than a
 * naming preference. {@code services/pom.xml} splits the two test phases by class name alone -- Surefire
 * selects {@code *Test} in {@code test} and Failsafe selects {@code *IT} in {@code integration-test} -- so
 * a class in this package named to end in {@code IT} would be selected by neither and would neither run
 * nor fail. It would simply be absent from a complete, green report, which is worse than a missing test
 * because a missing test is visible.</p>
 *
 * <p>Assumptions: the rationale labels used throughout this package are the four
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes -- {@code Alternatives Considered:},
 * {@code Refactoring Rationale:}, {@code Assumptions:} and {@code Trade-offs:} -- in that document's
 * written form: plural, unparenthesised, colon retained and no emphasis markup. A label that is
 * byte-different is a rationale a literal search cannot find, and a rationale a search cannot find is one
 * a review cannot count.</p>
 *
 * <p>Trade-offs: this descriptor exists because the project Explainability rule requires a docstring on
 * every module entry point and, in Java, the entry point of a package is its declaration --
 * {@code package-info.java} being the only compilation unit able to carry one. It is also what
 * {@code JavadocPackage} and {@code MissingJavadocPackage} in {@code config/checkstyle/checkstyle.xml}
 * together require: the first asserts the file exists and would accept an empty one, the second asserts
 * the declaration carries a Javadoc block and would reject it. Emptying this file down to its
 * {@code package} line therefore stops the build rather than simplifying anything.</p>
 *
 * <p>Assumptions: the parameter, return-value and exception elements of the rule's docstring
 * specification describe callable code, and a package declaration accepts no argument, yields no value
 * and raises nothing. They are omitted deliberately rather than written out empty, because
 * {@code NonEmptyAtclauseDescription} reports an at-clause with no description as a violation in its own
 * right -- so an empty tag would fail the gate it was added to satisfy.</p>
 */
package com.carddemo.account.service;
