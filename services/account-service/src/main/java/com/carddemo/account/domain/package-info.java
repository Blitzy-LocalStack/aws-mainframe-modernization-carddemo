/**
 * Persistence mapping for the account bounded context: three JPA entities over the three tables of
 * the {@code account} schema, each derived from a reference COBOL record layout through an
 * anti-corruption boundary that deliberately sits in a sibling package rather than in this one.
 *
 * <h2>Target contract, and the tree state this charter describes</h2>
 *
 * <p>Assumptions: every type name and every count below is BOTH this package's <b>target
 * contract</b> as the migration plan assigns it AND a measurement of the files sitting beside this
 * charter, because the two now agree: the directory holds {@code Account.java},
 * {@code CardXref.java}, {@code Customer.java} and this file, which is exactly the closed set of
 * four the inventory below declares. A reader may therefore take every statement here in the
 * present tense. Where a sibling package named further down is still incomplete, its own charter
 * says so; this one no longer needs the qualification.</p>
 *
 * <p>Refactoring Rationale: this paragraph used to declare the opposite -- that "at the checkpoint
 * that authored this one the directory holds this file alone" and that "the three entities named
 * throughout are therefore <b>planned</b> rather than missing". All three were authored afterwards
 * and the caveat was not withdrawn, so the one paragraph a reader was told to consult in order to
 * tell a target from a measurement was itself the file's least true sentence: it directed a reader
 * to read three delivered entities as absent. It is corrected rather than deleted because the
 * distinction it draws is still worth drawing -- a charter CAN legitimately precede its types, and
 * saying which case this file is in remains the first thing a reader needs. The lesson is recorded
 * with it: a caveat about tree state is a dated claim, so it has to be withdrawn by whoever makes it
 * false, and nothing mechanical can do that for prose phrased as a point-in-time observation.</p>
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
 * <p>A type in this package maps a Java object onto a physical row, and does nothing else. It carries
 * no business rule, because a transcribed baseline paragraph becomes a named method in
 * {@code com.carddemo.account.service}; no request or response shape, because those belong to
 * {@code com.carddemo.account.dto}; no query, because keyed reads and keyset browse belong to
 * {@code com.carddemo.account.repository}; and no representation concern of the baseline record,
 * because fixed widths, trailing blanks, dropped padding, masking, encryption and the context's one
 * renamed field all belong to {@code com.carddemo.account.mapper}. Exactly one decision is left for a
 * type here: which physical column, of which type and width, in which schema, each field of the three
 * baseline records lands on.</p>
 *
 * <h2>The three entities, and the three tables they map</h2>
 *
 * <p>Three entities and no fourth, each mapping exactly one table in the {@code account} schema. The
 * count is worth stating because it is not uniform across the migration -- the neighbouring card
 * context maps a single entity inside its own domain package.</p>
 *
 * <ul>
 *   <li>{@code Account} maps {@code account.accounts}, derived from {@code 01 ACCOUNT-RECORD}
 *       declared at {@code app/cpy/CVACT01Y.cpy} L4, whose header at L2 announces a 300-byte record.
 *       Twelve named fields at L5 to L16 and a {@code FILLER} at L17 account for those 300 bytes
 *       exactly.</li>
 *   <li>{@code Customer} maps {@code account.customers}, derived from {@code 01 CUSTOMER-RECORD}
 *       declared at {@code app/cpy/CVCUS01Y.cpy} L4, whose header at L2 announces a 500-byte record.
 *       Eighteen named fields at L5 to L22 and a {@code FILLER} at L23 account for those 500 bytes
 *       exactly.</li>
 *   <li>{@code CardXref} maps {@code account.card_xref}, derived from {@code 01 CARD-XREF-RECORD}
 *       declared at {@code app/cpy/CVACT03Y.cpy} L4, whose header at L2 announces a 50-byte record.
 *       Three named fields at L5 to L7 and a {@code FILLER} at L8 account for those 50 bytes
 *       exactly.</li>
 * </ul>
 *
 * <p>Assumptions: the three copybooks are normative. A field's picture clause decides its PostgreSQL
 * column type, its Java member type and its fixed-width byte offset, so a copybook is the
 * specification a mapping answers to rather than a note appended after one was chosen. Each states
 * its own record length in a header comment at L2, and in each case that length is exactly the sum of
 * the declared field widths beneath it -- 300, 500 and 50 respectively. That arithmetic is quoted
 * because it is independently checkable and because it is what distinguishes a deliberately dropped
 * {@code FILLER} from a field somebody forgot to map.</p>
 *
 * <h2>Where the physical shape is declared</h2>
 *
 * <p>Assumptions: the authoritative column list is the Flyway migration
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql}, and an entity
 * here is the other half of that two-sided contract rather than its source. Neither this package nor
 * that migration creates the {@code account} schema, its roles or its grants: those belong to
 * {@code data-migration/sql/V0__schemas_and_roles.sql}.</p>
 *
 * <p>Assumptions: every entity here is compared against that migration at start-up, because the
 * module runs the provider with schema handling held at {@code validate}. A member naming a column
 * the deployed table does not have therefore refuses to start, which is both earlier and easier to
 * attribute than a runtime failure on the first statement touching the column. Validation checks the
 * agreement, it does not produce it -- which is why the copybook citations above are given per field:
 * they are the common specification both sides are checked against and the only thing that can settle
 * a disagreement between an entity and a column.</p>
 *
 * <p>Assumptions: two columns carry no copybook field and cannot be inferred from any layout cited
 * above. {@code accounts} and {@code customers} each carry a version column, present because the
 * baseline already performs a before-image comparison by hand across the gap between screen turns and
 * the entity expresses that discipline natively; the context root charter records the baseline
 * evidence in full. {@code card_xref} carries no version column, and the asymmetry is deliberate --
 * the reason is recorded on the table itself in the migration -- so a reader assuming uniformity will
 * look for a third version member here and not find one.</p>
 *
 * <p>Assumptions: the schema qualifier is supplied by configuration rather than by an entity. This
 * module pins a default schema of {@code account} and sets the search path on every pooled connection,
 * so a mapping names a table without qualifying it. Repeating the qualifier on each entity would be
 * harmless until the day it disagreed with the configured value, at which point two sources would
 * compete and the mapping would silently win.</p>
 *
 * <h2>Encryption and masking are not this package's concern</h2>
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
 * <p>Assumptions: this bounded context carries eight of these charters, one at the context root and
 * one in each of {@code api}, {@code config}, {@code domain}, {@code dto}, {@code mapper},
 * {@code repository} and {@code service}. The shared kernel carries eleven, because it has ten
 * subpackages beside its own root, and that eleven must not be read across to here. The two counts
 * are recorded together because a reader who assumes the modules are symmetrical will look for a
 * ninth charter in this context and conclude that one is missing.</p>
 *
 * <p>Refactoring Rationale: the shared-kernel figure said nine charters over eight subpackages. The
 * kernel has since gained {@code common.messaging} and {@code common.control}, so it is ten
 * subpackages and eleven charters, which is what
 * {@code services/common-lib/src/main/java/com/carddemo/common/package-info.java} states and what
 * {@code SharedKernelInventoryTest} re-derives from that directory on every build. This charter's
 * own count of eight is unchanged and was re-measured with it. The figure is quoted here at all only
 * to stop a reader importing the kernel's shape into this context, and that is precisely why it went
 * stale: it is a fact about ANOTHER module, so nothing that changes this one disturbs it. It is kept
 * rather than replaced by a pointer, because the warning only works if the reader can see the two
 * numbers differ without opening a second file; the accepted cost is this restatement, which is why
 * it names the file that owns the figure so a doubtful reader can check it in one jump.</p>
 *
 * <h2>No association, and no foreign key behind one</h2>
 *
 * <p>Assumptions: there is no JPA association anywhere in this package and no inter-table foreign key
 * behind one. A customer identifier on {@code card_xref} is a plain numeric column and not a reference
 * to {@code Customer}; an account identifier there is a plain numeric column and not a reference to
 * {@code Account}. The independence is a designed property with evidence behind it, and the first two
 * findings are the decisive pair because they show the relationship cannot be expressed from either
 * side.</p>
 *
 * <ul>
 *   <li>{@code app/cpy/CVACT01Y.cpy} carries no customer identifier at all, so the account record has
 *       no member that could hold such a reference.</li>
 *   <li>{@code app/cpy/CVCUS01Y.cpy} carries no account identifier either. The one field whose name
 *       contains the word account is L20 {@code CUST-EFT-ACCOUNT-ID PIC X(10)} -- an <em>external</em>
 *       electronic-funds-transfer account, pointing at nothing inside this schema.</li>
 *   <li>{@code app/cpy/CVACT03Y.cpy} L6 {@code XREF-CUST-ID} and L7 {@code XREF-ACCT-ID} therefore
 *       make {@code card_xref} the sole join path between the other two.</li>
 *   <li>{@code app/cbl/COACTVWC.cbl} composes all three records with three separate keyed reads and no
 *       join: its only read verbs are at L727 against the cross-reference alternate-index path, L776
 *       against the account master and L826 against the customer master.</li>
 *   <li>The only foreign key the baseline declares anywhere belongs to a different context,
 *       {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L6 to L7, and this context's four
 *       storage resources are declared without recovery or journalling -- {@code JOURNAL(NO)} at
 *       {@code app/csd/CARDDEMO.CSD} L7, L44, L57 and L70 and {@code RECOVERY(NONE)} at L9, L46, L59
 *       and L72 -- so no referential integrity is asserted over these records by any means.</li>
 * </ul>
 *
 * <p>Assumptions: because the baseline asserts referential integrity explicitly where it wants it, its
 * absence here is informative rather than accidental, and either consequence alone settles the
 * question. A foreign key between these tables would refuse rows the baseline accepts, which the
 * standing constraint forbids -- structure may change freely, observable behaviour may not -- and a
 * mapped association would put lazy-loading proxies into objects that cross the mapping boundary,
 * leaking a persistence concern past {@code com.carddemo.account.mapper}.</p>
 *
 * <p>Trade-offs: the cost is real. Composing an account with its customer takes two keyed reads and a
 * cross-reference lookup rather than one traversal, and no database constraint stops a cross-reference
 * row from naming an account or a customer the other tables do not hold, so keeping the three
 * consistent falls to the loader and the service layer. What is bought is that each table can be
 * created, loaded and tested without the others present, that the bulk load can run the three extracts
 * in parallel, and that the referential question is answered by a verification stage reporting every
 * unmatched identifier rather than by a constraint aborting on the first.</p>
 *
 * <h2>Import boundaries every type here is held to</h2>
 *
 * <p>Three prohibitions bind every type in this package, and each is a build step rather than a review
 * convention because none of them fails to compile at the point where it is broken. They are quoted at
 * subtree granularity because a loose restatement would steer the next author away from the very
 * annotations these entities are built on.</p>
 *
 * <ul>
 *   <li>A {@code domain} type may not depend on {@code software.amazon.awssdk..},
 *       {@code com.amazonaws..}, {@code org.springframework.web..} or {@code jakarta.servlet..}. Both
 *       storage-client generations are named because both are importable.</li>
 *   <li>No bounded context may depend on another bounded context's {@code domain} package. The segment
 *       is matched as a complete dot-delimited element, so a package named {@code subdomain} or
 *       {@code domainevents} is not swept in by accident. The reactor arrow runs one way only: this
 *       context may depend on {@code com.carddemo.common}, and the shared kernel may never depend back
 *       on it.</li>
 *   <li>No IEEE-754 binary floating point type may appear as a field, a parameter or a return type, at
 *       the declaration or as a generic argument of one, anywhere beneath the {@code com.carddemo}
 *       root. That reaches this package directly, five of the twelve named fields of the account record
 *       being amounts; each is an exact fixed-point decimal in the schema and here.</li>
 * </ul>
 *
 * <h2>The closed set of types this package may hold</h2>
 *
 * <p>This package holds entities and nothing else. The boundary is a closed set rather than guidance
 * because every concern below has exactly one home in this context, and a second home for any of them
 * is the failure this charter exists to prevent: no repository interface and no query; no request or
 * response record, page envelope or error type; no mapper and no codec; no configuration class or bean
 * declaration; no locally declared exception type, a conflict, a not-found and a validation failure
 * all being expressed through the shared kernel's error model whose single advice already maps an
 * optimistic-lock failure; and no data-definition file, properties file, ignore file, second package
 * descriptor or copy of the architecture test.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Assumptions: every baseline path cited here is reference material -- read, cited by path and line,
 * and never modified. Where a mapping diverges from the reference, the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. Line numbers refer to the source as
 * committed, and columns 73 to 80 of a COBOL line carry a sequence field that is not part of the
 * statement.</p>
 */
package com.carddemo.account.domain;
