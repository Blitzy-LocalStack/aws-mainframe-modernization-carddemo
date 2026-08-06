/**
 * Charter of the reference-data bounded context: transaction types and their
 * categories, disclosure-group interest rates, and the United States lookup
 * data that address validation in other contexts reads.
 *
 * <h2>Target contract, not a directory listing</h2>
 *
 * <p>Assumptions: every inventory and every name in this charter states the
 * package's <b>target contract</b> as the migration plan assigns it, so it is
 * read against the plan rather than against a listing of the tree beside it.
 * It records what this context owns and what it may never hold, and the
 * second half is the half a reader cannot reconstruct from the files.</p>
 *
 * <p>Alternatives Considered: deriving the inventory from the directory
 * instead of from the plan. Rejected, because a charter that describes
 * whatever happens to be present cannot say what may <em>not</em> be added,
 * and that is exactly the boundary a reviewer needs when weighing a proposed
 * addition. The cost accepted is that this charter has to be revised
 * whenever the contract itself changes.</p>
 *
 * <h2>Purpose</h2>
 *
 * <p>This package is the root of the {@code reference-service} module. It
 * holds that module's Spring Boot entry point and this charter, and no other
 * type: every class of this context lives in one of the layer subpackages
 * enumerated below.</p>
 *
 * <p>The context answers for the reference and lookup data that other
 * bounded contexts read but none of them owns. Address validation in
 * {@code account-service}, the interest accrual driven from
 * {@code batch-service}, and the transaction-type screens all resolve
 * against data this context alone may write.</p>
 *
 * <p>On parameters, return values and exceptions: a package declaration
 * accepts no argument, returns no value and raises nothing, so this charter
 * deliberately carries none of the three corresponding at-clauses. The
 * inapplicability is stated rather than left silent, because the
 * Explainability rule lists a docstring that omits its parameters or return
 * values among its forbidden patterns at line 39, and a reader has to be
 * able to tell a declared inapplicability from an oversight. Assumptions:
 * Javadoc models no parameter, return or exception concept for a package,
 * and the repository ruleset audits at-clause bodies for emptiness through
 * {@code NonEmptyAtclauseDescription}, so an invented empty at-clause would
 * be reported rather than credited.</p>
 *
 * <h2>What this context owns</h2>
 *
 * <p>One database schema, {@code reference}, holding six tables:
 * {@code transaction_types}, {@code transaction_categories},
 * {@code disclosure_groups}, {@code us_phone_area_codes}, {@code us_states}
 * and {@code us_state_zip_prefixes}. The authoritative column contract is
 * this module's {@code src/main/resources/db/migration/V1__reference.sql}.
 * This charter names the tables and defers to that file for every column,
 * type, constraint and index, rather than restating them where the two
 * could drift apart.</p>
 *
 * <p>Assumptions: that migration creates tables only. It contains no schema
 * creation, no role creation and no grant, because
 * {@code data-migration/sql/V0__schemas_and_roles.sql} owns the schemas and
 * the per-service roles and runs ahead of every module's migrations. A
 * module that also created its own schema would make the outcome depend on
 * which of the two ran first.</p>
 *
 * <p>This module is also the only one in the reactor carrying a second
 * migration, {@code src/main/resources/db/migration/V2__seed_reference.sql}.
 * Assumptions: that seed is data the system cannot start without rather
 * than sample content. The disclosure-rate lookup falls back to a row keyed
 * {@code DEFAULT}, and the lookup tables are the allow-lists address
 * validation tests against, drawn from {@code app/cpy/CSLKPCDY.cpy}.
 * Alternatives Considered: loading those rows through the
 * extract-transform-load path with the rest of the migrated data. Rejected,
 * because the rows are constrained by the schema shipped beside them, so
 * versioning them together is what keeps a constraint and the data it admits
 * from arriving separately.</p>
 *
 * <h2>What this context exposes</h2>
 *
 * <p>A synchronous REST surface contracted by this module's
 * {@code src/main/resources/openapi/reference-api.yaml}, written to OpenAPI
 * 3.1, covering the six tables plus a date-evaluation endpoint and a
 * reference-maintenance endpoint. Alongside it there is exactly one
 * asynchronous entry point, a queue consumer for the date-conversion flow,
 * which is why {@code spring-cloud-aws-starter-sqs} is a dependency of this
 * module and not only of the messaging contexts.</p>
 *
 * <h2>Layering</h2>
 *
 * <p>Every type sits in one of these subpackages, and each has one job:
 * <ul>
 *   <li>{@code api} -- REST controllers and request validation, holding no
 *       business rule.</li>
 *   <li>{@code service} -- the business rules, transcribed paragraph by
 *       paragraph from the baseline programs named below.</li>
 *   <li>{@code repository} -- Spring Data JPA repositories and the keyset
 *       queries that replace the baseline's cursor paging.</li>
 *   <li>{@code domain} -- one JPA entity per baseline record layout.</li>
 *   <li>{@code dto} -- request and response shapes, field for field from the
 *       record layout and the symbolic map.</li>
 *   <li>{@code mapper} -- the hand-written anti-corruption layer, and the
 *       only place a copybook representation concern may appear.</li>
 *   <li>{@code config} -- wiring, holding no business rule.</li>
 * </ul>
 *
 * <p>Assumptions: that layering has exactly one mechanical owner, the
 * ArchUnit {@code LayeringRulesTest} authored in {@code common-lib}'s test
 * tree and run against this module's own compiled classes. Checkstyle's
 * {@code ImportControl} is deliberately not configured. Alternatives
 * Considered: configuring it as a second gate over the same boundary.
 * Rejected, because a reader who found a layering violation would then have
 * two engines to consult and no way to tell which one owned the rule, and
 * the two could disagree while both reported success.</p>
 *
 * <h2>Baseline lineage</h2>
 *
 * <p>Everything beneath {@code app/} is the behavioural oracle for this
 * migration: it is read and never modified. This context answers for:
 * <ul>
 *   <li>{@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, the
 *       transaction-type inquiry and list screen, reached in the baseline as
 *       CICS transaction {@code CTLI}
 *       ({@code app/app-transaction-type-db2/csd/CRDDEMOD.csd} L25-L26).</li>
 *   <li>{@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}, the
 *       transaction-type maintenance screen, transaction {@code CTTU} (same
 *       resource definition, L35-L36).</li>
 *   <li>{@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl}, the batch
 *       reference maintenance program.</li>
 *   <li>{@code app/app-vsam-mq/cbl/CODATE01.cbl}, the queue-driven date
 *       conversion, transaction {@code CDRD}
 *       ({@code app/app-vsam-mq/csd/CRDDEMOM.csd} L27-L28).</li>
 *   <li>the disclosure-rate lookup semantics of
 *       {@code app/cbl/CBACT04C.cbl}, including the fallback to the group
 *       keyed {@code DEFAULT} when a group key is absent: paragraph
 *       {@code 1200-GET-INTEREST-RATE} performs
 *       {@code 1200-A-GET-DEFAULT-INT-RATE} at L438, and that paragraph is
 *       declared at L443.</li>
 *   <li>the date-edit rules of {@code app/cbl/CSUTLDTC.cbl}, which this
 *       context delegates to {@code DateEditValidator} in
 *       {@code com.carddemo.common.validation} rather than re-implementing,
 *       so one set of rules serves every context that edits a date.</li>
 * </ul>
 *
 * <h2>What this context does not own</h2>
 *
 * <p>Four exclusions, each stated because the reverse would be a reasonable
 * guess:
 * <ul>
 *   <li>No batch job repository, and no batch starter dependency.
 *       Assumptions: {@code COBTUPDT.cbl} is labelled {@code Layer: Business
 *       logic} at L3 and declares {@code SELECT TR-RECORD ASSIGN TO INPFILE}
 *       with {@code ORGANIZATION IS SEQUENTIAL} at L31-L32, so its migrated
 *       form is a service method reading a sequential input, not a
 *       chunk-oriented job needing a durable job repository. Chunk-oriented
 *       batch belongs to {@code batch-service}.</li>
 *   <li>No schema, role or grant statement, for the reason recorded
 *       above.</li>
 *   <li>No exception handler of its own. The mapping from a restricted
 *       delete to HTTP 409 is inherited from
 *       {@code com.carddemo.common.error.GlobalExceptionHandler}, so that
 *       status and its wording stay identical across every service.</li>
 *   <li>CICS transaction {@code CDRA}, which
 *       {@code app/app-vsam-mq/csd/CRDDEMOM.csd} L17-L18 binds to program
 *       {@code COACCT01}. That transaction belongs to
 *       {@code account-service}. Only {@code CDRD}, at L27-L28 of that same
 *       resource definition, is this context's, and the two sit close
 *       enough together in one file to be misread as a pair.</li>
 * </ul>
 *
 * <h2>Design decisions</h2>
 *
 * <p>Assumptions: the package root is fixed at
 * {@code com.carddemo.reference} by the migration plan's list of nine
 * package roots, which makes {@code com/carddemo} an organisational segment
 * holding no compilation unit. It therefore carries no charter of its own,
 * on two independent grounds: {@code common-lib} places its charters at
 * {@code com.carddemo.common} and below and never at {@code com.carddemo};
 * and the {@code JavadocPackage} check fires only for a directory holding a
 * file it processes, which that one does not. There is consequently no
 * violation to answer for there, and the Explainability rule cannot require
 * a docstring of an entry point that has no compilation unit to carry
 * one.</p>
 *
 * <p>Assumptions: the transaction-category code is a fixed-width character
 * value, {@code CHAR(4)} in the schema and a string in Java, and never an
 * integer type, even though two copybooks declare it numeric.
 * {@code app/cpy/CVTRA04Y.cpy} L7 declares {@code TRAN-CAT-CD PIC 9(04)}
 * and {@code app/cpy/CVTRA02Y.cpy} L8 declares {@code DIS-TRAN-CAT-CD PIC
 * 9(04)}. Three character-typed sources decide it against them: the table
 * definition at {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L3
 * declares {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL}; the generated host
 * structure at {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl}
 * L42-L43 declares {@code DCL-TRC-TYPE-CATEGORY} as {@code PIC X(4)}; and
 * the seed rows of {@code app/data/ASCII/trancatg.txt} carry zero-padded
 * codes, beginning {@code 0001} through {@code 0005}. An integer column
 * would drop those leading zeros, so a code would round-trip as {@code 1}
 * where all three of those sources say {@code 0001}. This is the one place
 * where the otherwise mechanical rule that the copybook is normative is
 * deliberately not applied, and the column contract records the same
 * decision at its own point of use.</p>
 *
 * <p>Assumptions: the disclosure interest rate is exact fixed point from end
 * to end -- {@code NUMERIC(6,2)} in the schema, a scale-2 decimal in Java,
 * and a JSON <em>string</em> on the wire, never a JSON number. The layout at
 * {@code app/cpy/CVTRA02Y.cpy} L9 declares {@code DIS-INT-RATE PIC
 * S9(04)V99}, and the rate is not merely displayed: it is an operand of
 * {@code app/cbl/CBACT04C.cbl} L464-L465, {@code COMPUTE WS-MONTHLY-INT = (
 * TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. A client handed the rate as a JSON
 * number would parse it into a binary floating-point double and carry that
 * inexactness into every interest figure derived from it, so the string form
 * is what stops the wire boundary from being where precision is lost.
 * Binary floating-point types are prohibited on this path, and
 * {@code LayeringRulesTest} asserts their absence rather than leaving it to
 * review.</p>
 *
 * <p>Trade-offs: the three baseline record layouts are fixed length, and
 * each pads to that length with a trailing {@code FILLER} that the
 * relational model drops. Each drop is recorded at the entity that drops it
 * rather than only here: {@code X(28)} of the 50-byte disclosure-group
 * record ({@code app/cpy/CVTRA02Y.cpy}), {@code X(08)} of the 60-byte
 * transaction-type record ({@code app/cpy/CVTRA03Y.cpy}), and {@code X(04)}
 * of the 60-byte transaction-category record
 * ({@code app/cpy/CVTRA04Y.cpy}). All three lengths were confirmed by
 * summing the declared field widths against the record length each
 * copybook's own header states. What is given up is the ability to rebuild a
 * byte-exact record from a row alone; what is bought is a schema in which
 * every column is data. Anything needing the padded form reconstructs it
 * from the declared widths held in {@code common-lib}'s codecs.</p>
 *
 * <p>Refactoring Rationale: the baseline's pseudo-conversational session
 * state is not carried across. Its 160-byte communication area
 * ({@code app/cpy/COCOM01Y.cpy}) decomposes into four separate target
 * mechanisms: the navigation fields become client-side routing, the identity
 * fields become validated token claims, the selected type code becomes a
 * path parameter on the request, and the re-entry discriminator
 * {@code CDEMO-PGM-CONTEXT} at L29-L31 has no counterpart at all. A
 * stateless handler that answers with a field-error array has no
 * first-entry-against-re-entry distinction left to draw. That last one
 * severs a coupling worth naming: {@code app/cpy/CSSETATY.cpy} gates its
 * error highlighting on {@code AND CDEMO-PGM-REENTER} at L20, so the
 * baseline decides how to present an error partly from a remembered turn
 * count, whereas this context drives presentation purely from the response
 * body. The baseline is unchanged and remains the oracle; the divergence is
 * registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: the four rationale labels used throughout this charter are
 * written in the plural, colon-terminated form that
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} L203-L213 permits, and in no
 * other. Its L236-L245 records why that differs from the singular idiom of
 * the repository's existing suite: that suite is reference-only and is not
 * retyped, while the plural is the form the Explainability rule itself uses
 * at its lines 31 to 34, which is the wording this tree is audited against.
 * The labels are searched for literally before they are read by a person, so
 * a second spelling would read as documented to a reviewer and as absent to
 * the search. The two forms are never mixed inside one file.</p>
 */
package com.carddemo.reference;
