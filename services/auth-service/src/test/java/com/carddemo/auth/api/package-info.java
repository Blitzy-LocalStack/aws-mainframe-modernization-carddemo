/**
 * Web-layer slice tests for the two REST adapters of the auth bounded context.
 *
 * <h2>The adapter charter, and the line it draws</h2>
 *
 * <p>The {@code api} package owns exactly three concerns: transport validation, HTTP status mapping
 * and delegation. It owns no business rule and reaches no store. That boundary is what fixes the
 * assertion set in this package to six subjects and no seventh, namely HTTP status, response-body
 * shape, verbatim message text, per-field error keys, authority enforcement and statelessness.</p>
 *
 * <p>The corollary is the more useful half, because it is what stops a reader hunting here for an
 * assertion that was never meant to be here. The transcribed validation chains, the per-program
 * validation orders read as behaviour, the dirty-check semantics of the update path and the
 * identity-exchange failure mapping are all asserted in the sibling
 * {@code com.carddemo.auth.service} package against a mocked repository. A rule asserted in neither
 * place would be genuinely untested; a rule asserted once, there, is correctly placed, and
 * asserting it twice would give a later change two places to update and one place to forget.</p>
 *
 * <h2>Two transport contracts, both derived rather than invented</h2>
 *
 * <p>Statelessness is the first, and it is a decomposition rather than a deletion. The reference
 * session structure at {@code app/cpy/COCOM01Y.cpy} opens with {@code 01 CARDDEMO-COMMAREA.} on
 * line 19 and carries four navigation fields on lines 21 through 24, an eight-character user
 * identifier on line 25, a one-character user type on line 26 whose only two condition names are
 * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'.} on line 27 and {@code 88 CDEMO-USRTYP-USER VALUE 'U'.}
 * on line 28, and a re-entry discriminator on lines 29 through 31. The sign-on program detects first
 * entry by testing {@code IF EIBCALEN = 0} on line 80 and ends every turn by returning that whole
 * structure to the terminal on lines 98 through 102, which is precisely what makes the client the
 * holder of the continuation. Three of those groups survive into the target and one does not:
 * navigation becomes client-side routing, identity becomes a validated token claim, selection
 * becomes a request path, and the re-entry discriminator has no counterpart at all, because a
 * stateless handler has no earlier turn to remember.</p>
 *
 * <p>The assertions that follow are negative ones, which is why they have to be written on purpose
 * or they are simply absent: no response carries a next-program field, no handler depends on a
 * prior call having happened, and the user type is read from the token rather than from anything the
 * caller supplied. That last one is a security property rather than a tidiness one. The reference
 * structure is storage the client echoes back, so a client could in principle assert its own type,
 * whereas a signed claim is not something a client can assert. The reference branch it replaces is
 * visible on lines 230 through 239 of the sign-on program, where a single test of the admin
 * condition name selects between two program transfers.</p>
 *
 * <p>Per-field error reporting is the second, and here the target is strictly wider than the
 * reference rather than equivalent to it. {@code app/cbl/COUSR01C.cbl} validates its input inside a
 * single {@code EVALUATE TRUE} spanning lines 117 to 151, holding five blank-field branches at
 * lines 118, 124, 130, 136 and 142 and a catch-all at line 148. Because it is one
 * {@code EVALUATE}, it short-circuits: the first failing field wins, one message reaches the screen
 * and the remaining fields are never examined. The highlight accompanying that message is templated
 * at {@code app/cpy/CSSETATY.cpy} lines 17 to 27, where lines 18 and 19 test the not-OK and blank
 * flags for a field, line 20 additionally requires the re-entry condition, lines 21 and 22 move the
 * red attribute into the field, and lines 23 to 25 place a literal asterisk in it when it is
 * blank.</p>
 *
 * <p>The target answers with a structured per-field error array instead, so one response can name
 * every failing field at once. Two consequences follow, and both are placement decisions rather
 * than coverage decisions. This package asserts the error keys, because which field an error
 * attaches to is a transport contract. The order in which the reference program would have
 * discovered those fields is behaviour, and it is asserted in the sibling service package. And the
 * re-entry condition on line 20 has no target counterpart, because the flag it tests is the
 * discriminator that the paragraph above shows disappearing, so error presentation here is driven
 * purely by the response body.</p>
 *
 * <p>Message text is asserted character for character, and the reason is a width discrepancy a
 * reader would otherwise try to reconcile. The sign-on program declares its message work field as
 * {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES.} on line 38, while the two shared constants it moves
 * into that field on lines 89 and 93 are each declared {@code PIC X(50)}, at
 * {@code app/cpy/CSMSG01Y.cpy} lines 18 and 20. The declared widths disagree by thirty characters
 * for one and the same message. Asserting a width would therefore encode whichever of the two
 * declarations a test author happened to read first, whereas asserting the text encodes what a user
 * actually sees, and that is the contract being preserved.</p>
 *
 * <h2>Decision record</h2>
 *
 * <p>Assumptions: these are slice tests, so the web layer is instantiated alone and the service
 * layer beneath it arrives as a mock. Nothing in this package starts a database, starts a container,
 * applies a schema migration or reaches a managed identity pool, and no assertion here may be
 * written as though one of those were available. The consequence is a division of labour rather
 * than a gap: repository behaviour and the schema constraints this service owns, among them the
 * two-value domain expressed as {@code CHECK (user_type IN ('A','U'))} in the migration
 * {@code services/auth-service/src/main/resources/db/migration/V1__auth.sql}, are verified by
 * {@code UserRepositoryIT} in the sibling {@code com.carddemo.auth.repository} test package, which
 * runs under Failsafe against a real container. That two-value domain is not introduced by the
 * migration either. It is the reference declaration already cited above, at
 * {@code app/cpy/COCOM01Y.cpy} lines 26 to 28, which is why the claim does not rest on the
 * migration alone.</p>
 *
 * <p>Alternatives Considered: a full {@code @SpringBootTest} was evaluated for this package and
 * rejected. It boots the persistence layer and runs the schema migration in order to reach a
 * controller, so every assertion about an HTTP status code, a response-body shape or a message
 * string would acquire a database-container dependency it has no use for, and the package would
 * stop running altogether on any host unable to start one. Serialisation and routing are the
 * concerns actually under test here, and coupling them to schema availability makes an adapter
 * regression and an infrastructure outage indistinguishable in the report. That specific confusion
 * is the damage being avoided, not a general preference for smaller contexts.</p>
 *
 * <p>Trade-offs: mocking the service layer means this package cannot observe that a controller and
 * its collaborator agree on anything beyond the signature the mock was written against. The cost is
 * accepted, and it is accepted at a known location rather than an unknown one: a drift between the
 * adapter and the service surfaces at compile time or in the sibling service package, and never as
 * a silently passing assertion here.</p>
 *
 * <h2>There is no executable oracle for this context</h2>
 *
 * <p>Every expected value in this package is derived by reading the reference source and its
 * symbolic maps, not by running something and capturing what came out. That is a property of the
 * repository rather than a preference exercised here. {@code tests/README.md} scopes its
 * golden-master tier on line 29 to the daily batch chain and to nothing else, and it records on
 * lines 83 and 84 that "Online CO* CICS programs cannot run end-to-end without a CICS runtime
 * (absent on the runner)". A census over all 590 lines of that file for the sign-on program, the
 * four user programs and the security file returns nothing at all, and broadening the census to the
 * whole existing suite returns nothing either: not one of them is named anywhere in it. There is
 * therefore no captured output for this context to be compared against, and no test here may imply
 * that there is one.</p>
 *
 * <p>Two disciplines follow. Every expected value cites the line that produced it, so that a
 * reviewer can re-derive the value instead of trusting it, and that line is confirmed by reading
 * the file, because a remembered line number is exactly the sort of claim that decays quietly.
 * And where the target does not reproduce the reference, the framing stays factual: the baseline
 * does one thing, the Java encodes another, and the divergence is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}. The reference source is never
 * described here as having been repaired, since it is not modified at all. The same file states on
 * lines 35 and 36 that its own tests encode the documented rules and do not redefine them, and this
 * package holds itself to that verb.</p>
 *
 * <h2>What this package may hold</h2>
 *
 * <p>Assumptions: this package holds slice tests and this descriptor, and nothing else. There is
 * deliberately no abstract base class, no suite aggregator, no separate fixture builder and no
 * test-scoped configuration class: a handful of test classes need no shared scaffolding, and
 * standing any of it up would move one class's setup into a second file, leaving a reader two
 * places to look and no way to tell which one applied. The boundary is stated as a rule about what
 * may be added rather than as a roster of files, because a roster is authoritative only until the
 * next class arrives.</p>
 */
package com.carddemo.auth.api;
