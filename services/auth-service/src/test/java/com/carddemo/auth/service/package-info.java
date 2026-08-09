/**
 * Unit tests for the service layer of the authentication bounded context, together with the written
 * boundary for what may be added to them.
 *
 * <h2>The production surface under test</h2>
 *
 * <p>The main-source package this one mirrors declares its charter as COBOL paragraph-to-method
 * business behaviour, identity exchange, transaction boundaries and keyset orchestration. That
 * charter is the subject here. It is carried by four collaborating services and one result carrier,
 * whose public surface is enumerated so that a reader can audit the boundary rather than infer
 * it:</p>
 *
 * <ul>
 *   <li>{@code CognitoIdentityService}, three public methods, {@code authenticate},
 *       {@code answerChallenge} and {@code refresh}. Its identity-exchange responsibility comes from
 *       the {@code READ-USER-SEC-FILE} paragraph at {@code app/cbl/COSGN00C.cbl} line 209.</li>
 *   <li>{@code UserService}, five public methods, {@code list}, {@code read}, {@code create},
 *       {@code update} and {@code delete}. They answer for four reference programs: the browse in
 *       {@code app/cbl/COUSR00C.cbl}, which opens at line 588, advances at line 621, reverses at
 *       line 655 and closes at line 689; the write at {@code app/cbl/COUSR01C.cbl} line 240; the
 *       read and the rewrite at {@code app/cbl/COUSR02C.cbl} lines 322 and 360; and the read and the
 *       delete at {@code app/cbl/COUSR03C.cbl} lines 269 and 307.</li>
 *   <li>{@code CognitoUserProvisioningService}, four public methods, {@code provision},
 *       {@code reassignGroup}, {@code withdraw} and {@code synchronise}. No single reference
 *       paragraph answers for it, because the baseline keeps its users in one file and has no
 *       separate identity store to provision into.</li>
 *   <li>{@code UserAuthorityService}, one public method, {@code reassign}, which moves a user
 *       between the only two authority values the baseline admits, declared as the condition names
 *       on {@code CDEMO-USER-TYPE} at {@code app/cpy/COCOM01Y.cpy} lines 27 and 28.</li>
 *   <li>{@code AuthorityReassignment} carries the outcome of a reassignment between the two services
 *       above and declares no behaviour of its own for a test to assert.</li>
 * </ul>
 *
 * <p>Those counts are why this package holds the four test types below and no others: one per
 * service, none for the carrier.</p>
 *
 * <h2>What this package holds</h2>
 *
 * <ul>
 *   <li>{@code CognitoIdentityServiceTest}, over the three exchanges and the provider outcomes they
 *       classify.</li>
 *   <li>{@code UserServiceTest}, over the five user-administration operations and the reference
 *       sentences they report.</li>
 *   <li>{@code CognitoUserProvisioningServiceTest}, over the identity a new user row is bound to and
 *       who is permitted to choose it.</li>
 *   <li>{@code UserAuthorityServiceTest}, over authority movement and its restoration when the
 *       surrounding transaction does not commit.</li>
 * </ul>
 *
 * <h2>What this package deliberately does not hold</h2>
 *
 * <p>Each exclusion below has exactly one owner elsewhere, and the boundary is written down so that
 * it can be audited.</p>
 *
 * <ul>
 *   <li>No controller test. Request binding, status selection and response shaping are asserted in
 *       the {@code com.carddemo.auth.api} test package, which drives each controller through a
 *       standalone web-layer setup.</li>
 *   <li>No repository integration test. The container-backed {@code UserRepositoryIT} belongs to the
 *       {@code com.carddemo.auth.repository} test package, where the suffix carries mechanical
 *       weight: {@code services/pom.xml} leaves {@code maven-failsafe-plugin} bound to
 *       {@code integration-test} and {@code verify}, so a name ending in {@code IT} runs in those
 *       phases, whereas every name here ends in {@code Test} and runs under Surefire at
 *       {@code test}. Putting a database-backed assertion in this package would move it into the
 *       earlier phase and make a plain unit build depend on a container being available.</li>
 *   <li>No shared base type, no helper, no builder and no external argument source. Every fixture
 *       vector is written inline by value in the test that reads it, beside a citation of the
 *       reference material it was taken from, so a reader never has to open a second file to learn
 *       where an expected value came from.</li>
 *   <li>No data file and no profile configuration. Both belong under
 *       {@code services/auth-service/src/test/resources}, which is where the two seed scripts and
 *       the test profile already sit.</li>
 *   <li>No architecture test. The layering rules are owned by
 *       {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 *       and are inherited rather than copied: {@code services/pom.xml} declares a Surefire execution
 *       that scans the {@code common-lib} artifact for that one name, so those rules already run
 *       against this module's compiled output. A local copy would be free to drift from the original
 *       with nothing to reveal that it had.</li>
 * </ul>
 *
 * <h2>Decision record</h2>
 *
 * <p>Assumptions: this descriptor exists because the documentation gate is configured to read test
 * sources, {@code includeTestSourceDirectory} resolving to true in the effective build, which places
 * a test package under the same two package-documentation modules as a main one. Those two ask for
 * different things and neither substitutes for the other. {@code JavadocPackage} runs at
 * {@code Checker} level over the directory and is satisfied only by this file being present, while
 * {@code MissingJavadocPackage} runs inside {@code TreeWalker} over the parsed file and is satisfied
 * only by Javadoc attached to the package declaration; a {@code package-info.java} holding nothing
 * but its package statement therefore passes the first and fails the second, which is why the
 * obligation has to be met as existence and content together. On the shape of that content, a
 * package declaration accepts no parameters, returns no value and propagates nothing, so of the four
 * docstring elements Rule 1 enumerates at lines 18 to 21 only Purpose has a subject here. That is
 * why this block is prose carrying no tag, and why line 39's prohibition on a docstring that omits
 * its parameters or its return value is not engaged: there is nothing present to omit. A parameter
 * tag, a return tag or an exception tag on this file would describe something the compilation unit
 * does not have, which is the reason none appears and the reason none should be introduced. The
 * convention this file follows across the migrated languages is set out in
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 *
 * <p>Trade-offs: the tests here stand in for the identity provider rather than reaching one. The
 * calls under test are administrative writes against a user pool, so an integration variant would
 * need either a live pool or an emulator reproducing its exact attribute schema, and a unit build
 * has neither. What the stand-in buys is the assertion that matters most for this subject, that a
 * user type outside the two-value domain reaches no provider call at all, which can be shown only by
 * observing that the collaborator was never touched. The cost is accepted and is stated rather than
 * glossed: the provider's own wire behaviour is asserted nowhere in this package, and the attribute
 * schema it is called with is held in place by the arguments these tests capture rather than by the
 * provider agreeing to them.</p>
 *
 * <p>Alternatives Considered: asserting provisioning end to end through the create-user endpoint
 * with a stood-in provider behind it, so that a single test covered the controller and the provider
 * call together. Rejected because the two refusals then become indistinguishable: a request turned
 * away by bean validation and a user type turned away by the group lookup arrive as one status, and
 * the property under test here is precisely that the second refusal happens before any account
 * exists. Splitting them across the two packages keeps each failure attributable to one layer.</p>
 *
 * <p>One divergence from the reference behaviour is restated here because it settles what the
 * sign-on tests are able to assert at all. The baseline compares a stored eight-character credential
 * directly, at {@code app/cbl/COSGN00C.cbl} line 223; the Java encodes that check as a call to a
 * managed identity provider and retains no such value in any table, payload or log; the divergence
 * is documented in {@code docs/architecture/cobol-to-service-traceability.md}. The consequence for
 * this package is that no test here asserts a credential comparison, because no stored value remains
 * to compare. The reference source does remain the specification for the sign-on outcomes at
 * {@code app/cbl/COSGN00C.cbl} lines 242, 249 and 254. Of those three, the two this exchange can
 * reach are pinned character for character by the tests here; the sentence at line 249 is one the
 * migrated exchange deliberately does not emit, for the reason recorded where the operation is
 * declared, so no assertion for it belongs in this package.</p>
 */
package com.carddemo.auth.service;
