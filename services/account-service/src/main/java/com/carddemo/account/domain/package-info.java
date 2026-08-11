/**
 * Persistence mapping for the account bounded context: three JPA entities over the three tables of
 * the {@code account} schema, each derived from a reference COBOL record layout through an
 * anti-corruption boundary that deliberately sits in a sibling package rather than in this one.
 *
 * <h2>Target contract, and the tree state this charter was authored in</h2>
 *
 * <p>Assumptions: every type name and every count below states this package's <b>target
 * contract</b> as the migration plan assigns it, and not the set of files sitting beside this
 * charter. A package charter is authored ahead of the types it governs, so at the checkpoint that
 * authored this one the directory holds this file alone. The three entities named throughout are
 * therefore <b>planned</b> rather than missing, and the same qualification carries to every sibling
 * package named further down. This paragraph is the one place a reader of this file has to look in
 * order to tell a target from a measurement.</p>
 *
 * <p>Alternatives Considered: withholding this charter until the three entities it governs exist.
 * Rejected, because the charter is what the author of those entities works from -- which physical
 * column each baseline field lands on, what may not be imported, and what the closed set of types
 * here is -- so writing it last would leave the package with no stated contract across exactly the
 * interval in which one is needed. A mechanical reason points the same way and is recorded in full
 * under the documentation gate below: the check that demands this file inspects the file set of a
 * whole directory, so an entity authored beside an absent charter fails the build for the directory
 * rather than for itself. The cost accepted is that the inventory reads as present tense unless the
 * distinction is declared, which is what the paragraph above exists to declare.</p>
 *
 * <h2>Purpose</h2>
 *
 * <p>A type in this package maps a Java object onto a physical row, and does nothing else. It
 * carries no business rule, because a transcribed baseline paragraph becomes a named method in
 * {@code com.carddemo.account.service}; no request or response shape, because those belong to
 * {@code com.carddemo.account.dto}; no query, because keyed reads and keyset browse belong to
 * {@code com.carddemo.account.repository}; and no representation concern of the baseline record,
 * because fixed widths, trailing blanks, dropped padding, masking, encryption and the context's one
 * renamed field all belong to {@code com.carddemo.account.mapper}. Exactly one decision is left for
 * a type here to make, and narrowing it to that single decision is what this charter is for: which
 * physical column, of which type and width, in which schema, each field of the three baseline
 * records lands on.</p>
 *
 * <h2>The three entities, and the three tables they map</h2>
 *
 * <p>The inventory is three entities and no fourth, each mapping exactly one table in the
 * {@code account} schema. The count is worth stating plainly because it is not uniform across the
 * migration: the neighbouring card context maps a single entity inside its own domain package, so a
 * reader arriving from there will not expect company here, and a reader arriving from here will
 * wrongly expect it there.</p>
 *
 * <ul>
 *   <li>{@code Account} maps {@code account.accounts}, derived from {@code 01 ACCOUNT-RECORD}
 *       declared at {@code app/cpy/CVACT01Y.cpy} L4, whose header at L2 announces a 300-byte
 *       record. Twelve named fields at L5 to L16 and a {@code FILLER} at L17 account for those 300
 *       bytes exactly.</li>
 *   <li>{@code Customer} maps {@code account.customers}, derived from {@code 01 CUSTOMER-RECORD}
 *       declared at {@code app/cpy/CVCUS01Y.cpy} L4, whose header at L2 announces a 500-byte
 *       record. Eighteen named fields at L5 to L22 and a {@code FILLER} at L23 account for those
 *       500 bytes exactly.</li>
 *   <li>{@code CardXref} maps {@code account.card_xref}, derived from {@code 01 CARD-XREF-RECORD}
 *       declared at {@code app/cpy/CVACT03Y.cpy} L4, whose header at L2 announces a 50-byte record.
 *       Three named fields at L5 to L7 and a {@code FILLER} at L8 account for those 50 bytes
 *       exactly.</li>
 * </ul>
 *
 * <p>Assumptions: the three copybooks named above are normative. A field's picture clause is what
 * decides its PostgreSQL column type, its Java member type and its fixed-width byte offset, so a
 * copybook is the specification a mapping here answers to rather than a note appended after a
 * mapping was chosen. Each of the three states its own record length in a header comment at L2, and
 * in each case that stated length is exactly the sum of the declared field widths beneath it -- 300
 * for {@code CVACT01Y.cpy}, 500 for {@code CVCUS01Y.cpy} and 50 for {@code CVACT03Y.cpy}. That
 * agreement is why the arithmetic is quoted at all: it is independently checkable, it is what lets a
 * later reader reconcile a column count against a layout, and it is what distinguishes a
 * deliberately dropped {@code FILLER} from a field somebody forgot to map.</p>
 *
 * <h2>Where the physical shape is actually declared</h2>
 *
 * <p>Assumptions: the authoritative column list is the Flyway migration at
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql} and not this
 * charter. That file declares the three tables, thirteen columns on {@code accounts}, nineteen on
 * {@code customers} and three on {@code card_xref}, the three primary keys {@code pk_accounts},
 * {@code pk_customers} and {@code pk_card_xref}, two check constraints, and one non-unique index
 * {@code idx_card_xref_account_id}. It is the only place any of their names, widths, nullability or
 * check predicates is settled, and no reader should look for the declaration in this package: there
 * is no data-definition statement anywhere beneath it.</p>
 *
 * <p>Alternatives Considered: restating all thirty-five of those columns here as a convenience
 * inventory. Rejected, because a column list duplicated into prose becomes a second definition that
 * no migration can keep true, and the two then diverge the first time only one of them is edited --
 * silently, with nothing in any build comparing them. Naming the counts and the constraint
 * identifiers instead still lets a reader reconcile thirteen columns against twelve named copybook
 * fields plus a version column, and it leaves exactly one file to edit when a column changes.</p>
 *
 * <p>Assumptions: neither this package nor that migration creates the {@code account} schema, its
 * owning role or any grant. All three are bootstrapped by
 * {@code data-migration/sql/V0__schemas_and_roles.sql}, which is the exclusive authority for
 * schemas, roles and privileges across this system, so every mapping here assumes the schema
 * already exists by the time the migration runs.</p>
 *
 * <p>Assumptions: every entity in this package is compared against that migration at startup.
 * {@code ddl-auto} is set to {@code validate} in {@code src/main/resources/application.yml} at its
 * L765, which means Hibernate emits no data definition of its own but does assert the mapping: the
 * migration runs first, and a member here that named a column the migration does not declare fails
 * the context refresh naming that column. Every column still exists at runtime only because the
 * migration created it -- validation checks the agreement, it does not produce it. That is the
 * concrete reason the copybook citations above are given per field rather than per record -- they
 * are the common specification both sides are checked against, and they are the only thing that can
 * settle a disagreement between an entity and a column.</p>
 *
 * <p>Refactoring Rationale: this paragraph previously recorded {@code ddl-auto: none} and stated
 * that nothing in the running system compared an entity against the migration, so their agreement
 * was an obligation neither side could assert. That was accurate while the migration was still
 * being authored, but it described the weaker of two available settings: under {@code none} a
 * mismatch surfaced as a runtime failure on the first statement touching the column, which is both
 * later and harder to attribute than a refusal to start. The setting was changed rather than the
 * asymmetry documented, because a drift this package cannot detect is one it will eventually
 * ship.</p>
 *
 * <p>Two of those columns carry no copybook field at all and so cannot be inferred from any layout
 * cited above. {@code accounts} and {@code customers} each carry a version column, present because
 * the baseline already performs a before-image comparison by hand across the gap between screen
 * turns, and the entity expresses that same discipline natively instead of reproducing a
 * field-by-field comparison; the context root charter at {@code com.carddemo.account} records the
 * baseline evidence for it in full. {@code card_xref} carries no version column, and that asymmetry
 * is deliberate rather than an omission: the reason is recorded on the table itself in the migration
 * named above, and a reader who assumes uniformity across the three tables will look for a third
 * version member here and not find one.</p>
 *
 * <p>Assumptions: the schema qualifier is supplied by configuration rather than by an entity. This
 * module pins a default schema of {@code account} and additionally sets the search path on every
 * pooled connection, so a mapping here names a table and does not need to qualify it. Repeating the
 * qualifier on each entity would be harmless until the day it disagreed with the configured value,
 * at which point two sources would compete and the mapping would silently win.</p>
 *
 * <h2>Why this charter exists at all</h2>
 *
 * <p>Assumptions: this file is load bearing rather than decorative, and two independent authorities
 * converge on it. The project Explainability rule requires a docstring on every module entry point,
 * and in Java the entry point of a package is its package declaration, which only a
 * {@code package-info.java} can carry. Two Checkstyle modules then enforce that mechanically, and
 * neither one is redundant. {@code JavadocPackage} is declared at Checker level in
 * {@code config/checkstyle/checkstyle.xml} L245, outside the {@code TreeWalker} that opens at L259,
 * so it inspects the file set and requires this <em>file to exist</em> in any directory holding an
 * audited compilation unit. {@code MissingJavadocPackage} is declared at L378 <em>inside</em> that
 * {@code TreeWalker}, so it inspects the parsed Javadoc tree and requires the file to <em>carry
 * documentation</em> rather than an ordinary block comment. A charter reduced to a bare
 * {@code package} statement therefore satisfies the first and fails the second, which is why the
 * prose is the deliverable and this file's mere existence is not.</p>
 *
 * <p>Trade-offs: that split placement is recorded at length because the consequence of misreading it
 * falls somewhere other than where the mistake is made. {@code JavadocPackage} at L245 inspects a
 * directory rather than a compilation unit, so deleting this charter on the ground that it declares
 * no type would not fail this file -- it would fail every entity beside it, and the reported
 * violation would name those entities rather than the deletion that caused it. The compromise
 * accepted in exchange is a compilation unit that carries no executable statement, contributes
 * nothing to the running system, and still has to be maintained in step with the package it
 * describes.</p>
 *
 * <p>Both checks run under the {@code checkstyle-documentation-gate} execution bound to the Maven
 * {@code validate} phase in {@code services/pom.xml} L816 to L817, with {@code failOnViolation} true
 * at L906 and {@code violationSeverity} warning at L907. Validate precedes compilation, so either
 * failure stops every local build rather than only a continuous-integration run, and neither is
 * escaped by a build that merely declines to run tests. Note also what is absent: not one of the
 * three filters that could suppress a finding from inside a source file -- the warning-annotation
 * filter, the comment filter and the nearby-comment filter -- is declared anywhere in that
 * configuration, so there is no in-source bypass at all. The file-based filter that <em>is</em>
 * declared, at L226, is configured non-optional and reaches only generated sources and test
 * fixtures, so no suppression is available for this tree and the only route past a finding is to
 * write the documentation.</p>
 *
 * <p>Assumptions: this compilation unit holds one statement, so the rationale the rule's
 * inline-comment half asks for has no adjacent executable line to sit beside and is carried inside
 * this block under the rule's own category labels, which is the only placement a package makes
 * available. No parameter, return-value or exception at-clause appears anywhere here, and that
 * omission is the compliant reading of the rule rather than a departure from it: a package
 * declaration accepts no argument, yields no value and raises nothing, so any such tag would have to
 * be invented, and {@code NonEmptyAtclauseDescription} at L470 rejects an at-clause whose
 * description is empty. The checks that would demand an authorship, version or history tag are
 * likewise absent from that configuration by deliberate choice, so no such tag appears here either.
 * The written convention every block in this module follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.</p>
 *
 * <p>Assumptions: this bounded context carries eight of these charters once complete, one at the
 * context root and one in each of {@code api}, {@code config}, {@code domain}, {@code dto},
 * {@code mapper}, {@code repository} and {@code service}. The shared kernel carries nine, because it
 * has eight subpackages beside its own root, and that nine must not be read across to here. The two
 * counts are recorded together because a reader who assumes the modules are symmetrical will look
 * for a ninth charter in this context and conclude that one is missing.</p>
 *
 * <h2>No association, and no foreign key behind one</h2>
 *
 * <p>Assumptions: there is no JPA association anywhere in this package and no inter-table foreign
 * key behind one. A customer identifier on {@code card_xref} is a plain numeric column and not a
 * reference to {@code Customer}; an account identifier there is a plain numeric column and not a
 * reference to {@code Account}. The three entities are mutually independent, and that is a designed
 * property with evidence behind it rather than an artefact of the order in which they are authored.
 * Five independent findings establish it, and the first two are the decisive pair because they show
 * the relationship cannot be expressed from either side.</p>
 *
 * <ul>
 *   <li>{@code app/cpy/CVACT01Y.cpy} carries no customer identifier at all. A search of that file
 *       for the customer field prefix returns nothing across its whole length, so the account record
 *       has no member that could carry such a reference.</li>
 *   <li>{@code app/cpy/CVCUS01Y.cpy} carries no account identifier either. A search for the account
 *       field prefix likewise returns nothing, and the one field whose name contains the word
 *       account is L20 {@code CUST-EFT-ACCOUNT-ID PIC X(10)} -- an <em>external</em>
 *       electronic-funds-transfer account, not a CardDemo account, and pointing at nothing inside
 *       this schema.</li>
 *   <li>{@code app/cpy/CVACT03Y.cpy} L6 {@code XREF-CUST-ID} and L7 {@code XREF-ACCT-ID} therefore
 *       make {@code card_xref} the sole join path between the other two. Everything a caller can
 *       relate, it relates through that 50-byte record.</li>
 *   <li>{@code app/cbl/COACTVWC.cbl} composes all three records with three separate keyed reads and
 *       no join whatsoever. Its 941 lines contain exactly three read verbs: at L727 against the
 *       cross-reference alternate-index path named at L728, at L776 against the account master named
 *       at L777, and at L826 against the customer master named at L827. The second and third sit in
 *       paragraphs declared at L774 and L825 whose names say what they read and by which key.</li>
 *   <li>The only foreign key the baseline declares anywhere belongs to a different context:
 *       {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L6 to L7, a restricting reference
 *       between two reference-data tables that reference-service owns. This context has no
 *       data-definition source of its own in the baseline at all, and its four storage resources are
 *       declared without recovery or journalling -- {@code JOURNAL(NO)} at
 *       {@code app/csd/CARDDEMO.CSD} L7, L44, L57 and L70, and {@code RECOVERY(NONE)} at L9, L46,
 *       L59 and L72 -- so no referential integrity is asserted over these records by any means.</li>
 * </ul>
 *
 * <p>Assumptions: because the baseline asserts referential integrity explicitly where it wants it,
 * its absence here is informative rather than accidental, and two consequences follow. Declaring a
 * foreign key between these tables would refuse rows the baseline accepts, which the migration's
 * standing constraint forbids: structure may change freely, observable behaviour may not. And a
 * mapped association would put lazy-loading proxies into objects that cross the mapping boundary,
 * leaking a persistence concern past {@code com.carddemo.account.mapper}, which exists precisely so
 * that no such concern travels further. Either consequence alone settles the question. The
 * corresponding reasoning on the schema side, including the load-order argument that an independent
 * and parallel bulk load cannot tolerate a constraint imposing an order on it, is recorded in the
 * migration named above rather than duplicated here.</p>
 *
 * <p>Trade-offs: the cost of that absence is real and is named here rather than left to be
 * discovered. Composing an account with its customer takes two keyed reads and a cross-reference
 * lookup rather than one traversal, which is what the baseline already did and what the sibling
 * {@code com.carddemo.account.repository} is shaped to serve. No database constraint stops a
 * cross-reference row from naming an account or a customer the other tables do not hold, so keeping
 * the three consistent falls to the loader and to the service layer. What is bought is that each
 * table can be created, loaded and tested without the others present, that the bulk load can run the
 * three extracts in parallel, and that the referential question is answered by a verification stage
 * that inspects the whole loaded set and reports every unmatched identifier, instead of by a
 * constraint that aborts on the first one.</p>
 *
 * <h2>Import boundaries every type here is held to</h2>
 *
 * <p>Three prohibitions bind every type in this package, and each is a build step rather than a
 * review convention because none of them fails to compile at the point where it is broken. They are
 * quoted at subtree granularity rather than summarised, because a loose restatement would be worse
 * than none here: it would steer the next author away from the very annotations these entities are
 * built on.</p>
 *
 * <ul>
 *   <li>A {@code domain} type may not depend on {@code software.amazon.awssdk..},
 *       {@code com.amazonaws..}, {@code org.springframework.web..} or {@code jakarta.servlet..}.
 *       Both storage-client generations are named because both are importable, and naming only the
 *       current coordinate would leave the older one as an unguarded route inward.</li>
 *   <li>No bounded context may depend on another bounded context's {@code domain} package. The
 *       segment is matched as a complete dot-delimited element rather than as a substring, so a
 *       package named {@code subdomain} or {@code domainevents} is not swept in by accident. The
 *       reactor arrow runs one way only: this context may depend on {@code com.carddemo.common}, and
 *       the shared kernel may never depend back on it.</li>
 *   <li>No IEEE-754 binary floating point type may appear as a field, a parameter or a return type,
 *       at the declaration itself or as a generic argument of one, anywhere beneath the
 *       {@code com.carddemo} root. That scope is wider than the money packages and therefore reaches
 *       this package directly, which matters because five of the twelve named fields of the account
 *       record are amounts. Each is an exact fixed-point decimal in the schema and an exact
 *       fixed-point decimal member here; the context root charter records why a binary
 *       approximation is barred rather than merely discouraged.</li>
 * </ul>
 *
 * <p>Assumptions: what those prohibitions do <b>not</b> say matters as much as what they do, which
 * is the reason they are quoted at subtree granularity above. Neither {@code org.springframework..}
 * as a whole nor {@code jakarta..} as a whole is forbidden. In particular {@code jakarta.persistence}
 * is permitted, and it is exactly what an entity in this package needs; a blanket ban on either root
 * was evaluated when the gate was written and was rejected on the recorded ground that it would
 * refuse the persistence mapping these entities are built from. The permitted import surface for a
 * type here is consequently narrow and closed: {@code jakarta.persistence}, the Java platform
 * library, and {@code com.carddemo.common} where a shared kernel type is genuinely needed. A reader
 * who took a loose paraphrase for the rule would avoid the annotations that make this package work,
 * which is the concrete failure this paragraph prevents.</p>
 *
 * <p>Alternatives Considered: expressing those same three rules a second time, either as a
 * Checkstyle import-control block or as an architecture test local to this service. Both were
 * rejected for one reason. A layering rule duplicated per module can be tightened in one copy and
 * forgotten in the others, and no build log would ever report the divergence. Enforcement therefore
 * has exactly one owner, the architecture test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * which the shared kernel publishes as a test artefact so that the same rules run inside every
 * module of the reactor against that module's own classpath. Because those are assertions in a test
 * rather than prose in a document, they cannot decay into a convention: a violation fails a build
 * instead of surviving a review.</p>
 *
 * <p>Assumptions: this charter is itself imported by that test as an ordinary class, because a
 * compiled package descriptor is a class like any other. It is therefore subject to the two import
 * prohibitions exactly as an entity is -- which is one more reason it declares no import at all --
 * while contributing no field, parameter or return type for the fixed-point rule to examine. That
 * rule's own non-vacuity guard discounts descriptors for precisely that reason, so this file must
 * never be mistaken for the thing that makes the money assertion non-empty in this module.</p>
 *
 * <h2>The closed set of types this package may hold</h2>
 *
 * <p>This package holds entities and nothing else. The boundary is stated as a closed set rather
 * than as guidance because every one of the concerns below has exactly one home in this context, and
 * a second home for any of them is the failure this charter exists to prevent.</p>
 *
 * <ul>
 *   <li>No repository interface and no query. Keyed reads and the keyset browse that replaces the
 *       baseline's alternate-index access belong to {@code com.carddemo.account.repository}.</li>
 *   <li>No request or response record, no locally declared page envelope and no locally declared
 *       error type. Transport shapes belong to {@code com.carddemo.account.dto}, and the page
 *       envelope and error model are shared kernel types that are consumed and never re-declared.
 *       </li>
 *   <li>No mapper and no codec. Fixed-width decoding, sign conventions, trailing-blank handling,
 *       dropped padding, masking, encryption and the one renamed field belong to
 *       {@code com.carddemo.account.mapper}, which is the sole boundary at which any of them may
 *       appear.</li>
 *   <li>No configuration class and no bean declaration. Security, datasource, schema search path and
 *       published-contract metadata belong to {@code com.carddemo.account.config}.</li>
 *   <li>No locally declared exception type. A conflict, a not-found and a validation failure are all
 *       expressed through the shared kernel's error model, whose single advice already maps an
 *       optimistic-lock failure, so re-declaring any of them here would create a second mapping for
 *       one condition.</li>
 *   <li>No data-definition file, no properties file, no platform module descriptor, no ignore file,
 *       no second package descriptor and no copy of the architecture test. Each of those either
 *       belongs to a named location elsewhere in this module or does not exist anywhere in the
 *       repository, and introducing one here would break an assumption every sibling module is built
 *       on.</li>
 * </ul>
 *
 * <h2>Provenance, and how this package is verified</h2>
 *
 * <p>Every baseline path cited in this charter is reference material: read, cited by path and
 * physical line, never modified, and still running exactly as it reads. The migration adds a path
 * rather than removing one, so where the target does something the baseline does not -- encryption at
 * rest over resources declared without recovery or journalling, a version column in place of a
 * hand-maintained before-image, committed-read isolation in place of a resource declared to permit
 * uncommitted reads -- the difference is recorded as a platform capability the baseline had no way to
 * express, and never as a fault found in programs written against it.</p>
 *
 * <p>Physical line numbers are cited throughout because several of these baseline sources carry a
 * sequence ordinal in their first six columns. Those ordinals are not line numbers and do not
 * increase in step with them, so a citation taken from a printed ordinal would not resolve.</p>
 *
 * <p>Trade-offs: no executable parity oracle exists for the programs this context serves, and
 * claiming one would misdescribe the evidence. {@code tests/README.md} L83 to L85 records that the
 * online programs cannot be run end to end without a transaction runtime, which is absent from the
 * runner, so only their extractable field-validation logic is unit tested there; that suite's
 * verbatim business-rule section opens at L553 and names only the interest program, the posting
 * program and the transaction-category balance, none of which belongs to this context. Parity here
 * therefore rests on the copybook contracts and on this module's own tests rather than on byte
 * comparison against recorded mainframe output. That is a weaker guarantee than the batch contexts
 * enjoy, and it is accepted because the alternative, standing up a transaction region, lies outside
 * this migration and would not be reproducible in continuous integration. The mapping this package
 * declares is the part least exposed by that gap, because a column type, a width and a nullability
 * setting are each checkable directly against a copybook line and a migration line.</p>
 *
 * <p>Assumptions: the gates over this package are pass or fail, with no tolerated middle result. The
 * compiler, the documentation gate, the architecture assertions and the test runner each either pass
 * or fail the build. The graded condition-code rubric that treats a soft-reject code as an acceptable
 * aggregate outcome belongs to the baseline oracle suite under the repository's {@code tests}
 * directory alone, and that suite is a separate artefact from this module. Reading a build here
 * through that rubric would treat a real violation as an acceptable result, so no build in this tree
 * is ever described in its terms.</p>
 */
package com.carddemo.account.domain;
