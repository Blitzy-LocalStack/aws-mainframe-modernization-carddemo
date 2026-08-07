/**
 * Persistence mapping for the card bounded context: one JPA entity over the single table this
 * context owns, derived from a reference COBOL record through an anti-corruption boundary that
 * deliberately sits in a sibling package rather than here, together with the one column type that
 * cannot be a plain Java type -- the enciphered card verification value and the attribute converter
 * that is its only route to and from the column.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored this charter</h2>
 *
 * <p>Assumptions: every type name below describes this package's <b>target contract</b> as the
 * migration plan assigns it. The directory now holds <b>four</b> compilation units -- this charter,
 * {@code Card}, {@code EncryptedCvv} and {@code EncryptedCvvConverter} -- so the entity named
 * throughout is present rather than planned, while the sibling <i>packages</i> named further down
 * are still a mixture of authored and planned. The context root charter at
 * {@code com.carddemo.card} draws the same distinction for the same reason; this paragraph is the
 * one place a reader of this file has to look to tell a target from a measurement.</p>
 *
 * <p>Refactoring Rationale: this paragraph previously stated that "at the checkpoint that authored
 * this one the directory holds this file alone". That was already untrue when it was written --
 * {@code Card} was tracked beside it -- and the arrival of the two encryption types made it more
 * wrong rather than differently wrong. It is restated as a measurement of the directory
 * ({@code ls} over this package) because a charter that understates its own inventory teaches the
 * next author that the package is empty and that a new type needs no reconciliation against the
 * closed-set rule stated below.</p>
 *
 * <p>Alternatives Considered: withholding this charter until the entity it governs exists. Rejected,
 * because the charter is what the author of that entity works from -- which physical column each
 * baseline field lands on, what may not be imported, and what the closed set of types here is -- so
 * writing it last would leave the package with no stated contract across exactly the interval in
 * which one is needed. The cost accepted is that its inventory reads as present tense unless the
 * distinction is declared, which is what the paragraph above exists to declare.</p>
 *
 * <h2>Purpose</h2>
 *
 * <p>A type in this package is a mapping from a Java object onto a physical row, and nothing else.
 * It carries no business rule, because a transcribed COBOL paragraph becomes a named method in
 * {@code com.carddemo.card.service}; no request or response shape, because those belong to
 * {@code com.carddemo.card.dto}; no query, because keyed and paged access belongs to
 * {@code com.carddemo.card.repository}; and no representation concern of the baseline record,
 * because those belong to {@code com.carddemo.card.mapper}. Exactly one decision is left for a type
 * here to make, and narrowing it to that single decision is what this charter is for: which physical
 * column, of which type and width, in which schema, each field of the baseline card record lands
 * on.</p>
 *
 * <h2>The one entity, and the one table it maps</h2>
 *
 * <p>The inventory is a single <b>entity</b>, {@code Card}, mapping the single table
 * {@code card.cards}. That is worth a sentence of its own because the count is not the one a reader
 * arrives with: the account bounded context maps three tables inside its own domain package, so a
 * reader coming from there will look for company beside {@code Card} and find none. There is none to
 * find. The {@code card} schema owns exactly one table, this package maps exactly one entity onto
 * it, and a second entity appearing here would mean a second table had come into existence outside
 * the migration that is meant to create it.</p>
 *
 * <p>Assumptions: entity count and file count are different numbers here, and the difference is the
 * rule rather than an exception to it. {@code EncryptedCvv} and {@code EncryptedCvvConverter} are
 * neither entities nor tables: the first is an immutable value carrying the self-describing envelope
 * the {@code cvv_encrypted} column holds, and the second is the {@code AttributeConverter} that is
 * the single boundary between that value and the raw bytes the column stores. They live here, beside
 * the entity, because the type of a column and the conversion into that type are mapping decisions
 * -- which is exactly and only what this charter says a type here may decide -- and because the
 * value type is what makes a plaintext verification value <b>inexpressible</b> as entity state
 * rather than merely discouraged. Alternatives Considered: putting the pair in
 * {@code com.carddemo.card.mapper} beside the other representation concerns. Rejected because the
 * mapper package converts between a record shape and a served shape, and a JPA converter is
 * consulted by the persistence provider rather than called by a mapper; a converter the entity
 * declares but that lives outside the entity's package would also read as an optional collaborator
 * when it is the only permitted route to the column. The cipher that produces an envelope is a
 * different matter and does <b>not</b> live here: it holds a key identifier and calls a key service,
 * so it sits in {@code com.carddemo.card.service}, and the import prohibitions below are what keep
 * it out.</p>
 *
 * <p>Assumptions: the authoritative column list is the Flyway migration at
 * {@code services/card-service/src/main/resources/db/migration/V1__card.sql} and not this charter.
 * That file declares seven columns, two named constraints and one secondary index, and it is the
 * only place any of their names, widths, nullability or check predicates is settled. Two of the
 * seven are named here and the other five deliberately are not, because those two are the pair that
 * cannot be inferred from the baseline record at all: {@code card_num} is the primary key, and
 * {@code version} is the one column carrying no copybook field, present because the baseline already
 * performs optimistic concurrency by hand across the pseudo-conversational gap and the entity
 * expresses that same discipline natively instead of reproducing its field-by-field comparison.</p>
 *
 * <p>Alternatives Considered: restating all seven columns here as a convenience inventory. Rejected
 * on the ground the context root charter already sets for the record layout: a column list
 * duplicated into prose becomes a second definition that no migration can keep true, and the two
 * then diverge the first time only one is edited, silently, with nothing in any build comparing
 * them. Naming the count instead of the columns still gives a reader enough to reconcile seven
 * columns against six named copybook fields plus padding, and it leaves exactly one file to edit
 * when a column changes.</p>
 *
 * <p>Assumptions: neither this package nor that migration creates the {@code card} schema, its
 * owning role or any grant. All three are bootstrapped by
 * {@code data-migration/sql/V0__schemas_and_roles.sql}, which is the exclusive authority for
 * schemas, roles and privileges across this system, so every mapping here assumes the schema already
 * exists by the time the migration runs. There is also no second migration in this module: this
 * context owns one table and has no reference data to seed, so nothing follows {@code V1__}.</p>
 *
 * <h2>Why this charter exists at all</h2>
 *
 * <p>Assumptions: this file is load bearing rather than decorative, and two independent authorities
 * converge on it. The project Explainability rule requires a docstring on every module entry point,
 * and in Java the entry point of a package is its package declaration, which only a
 * {@code package-info.java} can carry. Two Checkstyle modules then enforce that mechanically, and
 * neither is redundant: {@code JavadocPackage} inspects the file set and requires this file to exist
 * in any directory holding an audited source file, while {@code MissingJavadocPackage} inspects the
 * parsed tree and requires the file to carry documentation rather than an ordinary block comment. A
 * charter reduced to a bare package statement satisfies the first and fails the second, which is why
 * the prose is the deliverable and the file's mere existence is not. Both run at the Maven
 * {@code validate} phase, ahead of compilation, so neither is escaped by a build that merely
 * declines to run tests.</p>
 *
 * <p>Assumptions: this compilation unit holds one statement, so the rationale the rule's
 * inline-comment half asks for has no adjacent executable line to sit beside and is carried inside
 * this block under the rule's own category labels, which is the only placement a package makes
 * available. No parameter, return-value or exception at-clause appears anywhere here, and that
 * omission is the compliant reading of the rule rather than a departure from it: a package
 * declaration accepts no argument, yields no value and raises nothing, so any such tag would have to
 * be invented, and {@code NonEmptyAtclauseDescription} rejects an at-clause with an empty body. The
 * written convention every block in this module follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.</p>
 *
 * <p>Assumptions: this bounded context carries eight of these charters once complete, one at the
 * context root and one in each of {@code api}, {@code config}, {@code domain}, {@code dto},
 * {@code mapper}, {@code repository} and {@code service}. The shared kernel carries nine, because it
 * has eight subpackages beside its own root, and that nine must not be read across to here. The two
 * counts are recorded together because a reader who assumes the modules are symmetrical will look
 * for a ninth charter in this context and conclude that one is missing.</p>
 *
 * <h2>What this package does not own, and the evidence for each absence</h2>
 *
 * <p>Assumptions: this package neither owns nor imports {@code Account}, {@code Customer} or
 * {@code CardXref}. All three belong to the account bounded context, in its own
 * {@code com.carddemo.account.domain} package, mapping the {@code account} schema rather than this
 * one. An absence is invisible in source, so it is written down here: a reader who goes looking for a
 * customer mapping beside {@code Card} finds the reason it sits elsewhere instead of concluding it
 * was overlooked.</p>
 *
 * <p>One observation about the baseline is recorded alongside that boundary, because the baseline is
 * what a reader will check the claim against and on a first reading it appears to say the opposite.
 * Both card maintenance programs include the customer record layout: {@code app/cbl/COCRDSLC.cbl:240}
 * and {@code app/cbl/COCRDUPC.cbl:359} each carry an active {@code COPY} of {@code CVCUS01Y}, yet
 * neither program then references a single field of the {@code CUST-} prefixed group anywhere in its
 * procedure division, so that include contributes a working-storage layout no statement reads. Both
 * programs additionally carry the account and cross-reference layouts as commented-out includes
 * rather than active ones, at {@code app/cbl/COCRDSLC.cbl:231} and {@code app/cbl/COCRDUPC.cbl:350}
 * for the account record and at {@code app/cbl/COCRDSLC.cbl:237} and
 * {@code app/cbl/COCRDUPC.cbl:356} for the cross-reference. That is stated as an observation and as
 * nothing more. The baseline is reference material, read and never modified, it keeps running exactly
 * as it reads, and no line of it is altered by this migration. What the observation settles is narrow
 * and useful: an inert include is not a data dependency, so it confers no ownership, and the three
 * records it names are consequently mapped by the context that does read them.</p>
 *
 * <h2>Two absences inside the mapping itself</h2>
 *
 * <p>Assumptions: there is no JPA association anywhere in this package and no inter-table foreign key
 * behind one. The account identifier is a plain integer column on {@code card.cards} and not an
 * association onto {@code Account}. Two reasons apply, and either alone would settle it. The table
 * such an association would point at lives in a different schema owned by a different service, so
 * declaring it would create exactly the cross-context coupling the layering gate below refuses. And
 * the baseline does not reach an account's cards by joining anything: {@code app/csd/CARDDEMO.CSD}
 * surfaces the base cluster and its alternate-index path as two separate CICS files,
 * {@code CARDDAT} defined at {@code :25} and {@code CARDAIX} at {@code :13}, so a program addresses
 * them as two distinct files and that second access path becomes a secondary index over this one
 * table rather than a relationship between two.</p>
 *
 * <p>Trade-offs: the cost of that is real and is worth naming rather than leaving for someone to
 * discover. A caller needing an account alongside a card makes a second call instead of traversing a
 * reference, and no database constraint stops a card from carrying an account identifier the account
 * schema does not hold; keeping the two consistent is the loader's and the service's job. What is
 * bought is that this module's schema can be migrated, loaded and tested with no other service's
 * schema present, and that a change to the account model cannot break a card mapping.</p>
 *
 * <p>Assumptions: this package also imports nothing whatsoever from {@code com.carddemo.common},
 * which is not an oversight in a service that consumes the shared kernel throughout its other
 * layers. The entity reaches for {@code jakarta.persistence} and the JDK and for nothing beyond
 * them. Every concern that would otherwise have pulled the shared kernel in belongs to
 * {@code com.carddemo.card.mapper} instead: the digits-only string transport of the card number and
 * the account identifier, masking the primary account number to its last four digits everywhere
 * except the one administrative route authorised to receive it in full, suppressing the card
 * verification value outright, dropping the record's trailing padding, and reading the baseline
 * expiry field, whose declared name carries a misspelling, into a target member name that spells the
 * word out. The sibling charter at {@code com.carddemo.card.mapper} states that same boundary from
 * its own side, so the two agree with each other rather than each asserting a different edge.</p>
 *
 * <p>Assumptions: this context handles no money at all, so the project-wide exact-decimal invariant
 * has no subject in this package. The card record declares two numeric fields and neither is an
 * amount: one is an eleven-digit account identifier, the other a three-digit verification code.
 * Writing down a prohibition in a package that has nothing to prohibit may read as redundant, and it
 * is written anyway for a specific reason. The invariant is that an amount stays exact decimal at
 * every hop, and the cheapest way to breach it is for a package believed to hold no money to acquire
 * one convenient approximate numeric member later, under a charter that never said it could not.</p>
 *
 * <h2>Import boundaries every type here is held to</h2>
 *
 * <p>Three prohibitions bind every type in this package, and each is a build step rather than a
 * review convention because none of them fails to compile at the point where it is broken. They are
 * quoted precisely rather than summarised, because a loose restatement would be worse than none: it
 * would steer the next author away from the very annotations this package is built on.</p>
 *
 * <ul>
 *   <li>A {@code domain} type may not depend on {@code software.amazon.awssdk..},
 *       {@code com.amazonaws..}, {@code org.springframework.web..} or {@code jakarta.servlet..}.
 *       Both AWS SDK generations are named because both are importable, and naming only the current
 *       one would leave the older coordinate as an unguarded route inward.</li>
 *   <li>No bounded context may depend on another bounded context's {@code domain} package. The
 *       segment is matched as a complete dot-delimited element rather than as a substring, so a
 *       package named {@code subdomain} or {@code domainevents} is not swept in by accident. The
 *       reactor arrow runs one way only: this context may depend on {@code com.carddemo.common},
 *       and the shared kernel may never depend back on it.</li>
 *   <li>No binary floating-point type may appear anywhere beneath the {@code com.carddemo} root,
 *       which is wider than the money packages and therefore reaches this package too.</li>
 * </ul>
 *
 * <p>Assumptions: what those prohibitions do <b>not</b> say matters as much as what they do, which
 * is why they are quoted at subtree granularity above. Neither {@code org.springframework..} as a
 * whole nor {@code jakarta..} as a whole is forbidden here. In particular
 * {@code jakarta.persistence} is permitted, and it is precisely what an entity in this package
 * needs; a wider ban was evaluated when the gate was written and was rejected on the stated ground
 * that it would reject the persistence mapping these entities are built from. A reader who took a
 * loose paraphrase for the rule would avoid the annotations that make this package work, which is
 * the concrete failure this paragraph prevents.</p>
 *
 * <p>Alternatives Considered: expressing those same three rules a second time, either as a
 * Checkstyle import-control block or as a local architecture test inside this service. Both were
 * rejected for one reason. A layering rule duplicated per module can be tightened in one copy and
 * forgotten in the other eight, and no build log would ever report the divergence. Enforcement
 * therefore has exactly one owner, the architecture test under
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/}, which the shared
 * kernel publishes as a test artifact so that the same rules run inside every module of the reactor
 * against that module's own classpath.</p>
 *
 * <h2>The siblings that build on this package</h2>
 *
 * <p>Three packages consume what this one declares, and they are named here for orientation only.
 * This charter creates no dependency on any of them, and the dependency runs the other way in every
 * case: {@code com.carddemo.card.repository} carries the keyed read and the by-account query that
 * the secondary index supports; {@code com.carddemo.card.mapper} is the anti-corruption layer
 * between the record shape and the served shape; and {@code com.carddemo.card.service} holds the
 * rules transcribed from the baseline programs.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>The record this mapping is derived from is {@code 01 CARD-RECORD}, declared at
 * {@code app/cpy/CVACT02Y.cpy:4}, whose header at {@code :2} announces the 150-byte record length.
 * Assumptions: that copybook is normative -- a field's picture clause is what decides its column
 * type, its Java type and its byte position -- so it is the specification this mapping answers to
 * rather than a note appended after the fact. Its six named fields occupy 91 bytes between them and
 * a trailing padding field carries the remaining 59, which is the arithmetic a reader needs in order
 * to reconcile the column count named earlier against the layout.</p>
 *
 * <p>Every baseline path cited in this charter is reference material: read, never modified, and still
 * running. The migration adds a path rather than removing one, and where the target does something
 * the baseline does not, the difference is recorded rather than presented as a fault found in the
 * baseline. The stored verification value is the clearest instance. The baseline holds it as three
 * display digits on files declared {@code JOURNAL(NO)} and {@code RECOVERY(NONE)}, at
 * {@code app/csd/CARDDEMO.CSD:19} and {@code :21} for the alternate index and at {@code :31} and
 * {@code :33} for the base cluster; that is a property of a non-recoverable storage platform, which
 * has nowhere to keep ciphertext keys or an audit trail, rather than a shortcoming of the programs
 * written against it. The relational target stores an enciphered value in a byte column instead --
 * an {@code EncryptedCvv} envelope, framed and validated in both directions by
 * {@code EncryptedCvvConverter}, whose enciphered data key comes from a customer-managed key the
 * platform provides and the baseline platform had no equivalent of. Both readings are accurate at
 * the same time, and the difference between them is a platform capability and not a correction.</p>
 *
 * <p>Trade-offs: no golden-master oracle exists for the online paths this context serves. The card
 * screens cannot run end to end without a CICS runtime, as recorded at
 * {@code tests/README.md:83-85}, so parity for them rests on the transcribed rules and on the tests
 * under {@code services/card-service/src/test} rather than on byte comparison against recorded
 * mainframe output. That is a weaker guarantee than the batch contexts enjoy, and it is accepted
 * because the alternative, standing up a CICS region, lies outside this migration and would not be
 * reproducible in continuous integration. The mapping this package declares is the part least
 * exposed by that gap, because a column type, a width and a nullability setting are each checkable
 * directly against the copybook and the migration.</p>
 */
package com.carddemo.card.domain;
