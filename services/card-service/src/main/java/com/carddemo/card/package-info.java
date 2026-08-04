// WHY : Alternatives Considered: this is the sole package-info.java in the com/carddemo/card
//       chain, and mirroring it at java/com and java/com/carddemo for symmetry was weighed and
//       rejected. Checkstyle's JavadocPackage audits only a directory that contains a
//       processed .java file, and the module-entry-point obligation in Rule 1 (Explainability)
//       attaches to a package declaration, which a directory holding only subdirectories does not
//       have. Adding one there would document a package that has no entry point to document.
/**
 * Root package of the card bounded context, which owns credit-card enquiry and maintenance.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every inventory, file name, class name and count in this charter describes the
 * package's <b>target contract</b> as the migration plan assigns it, not the set of files present
 * beside this one today. The migration lands its artifacts in plan order and this charter is
 * authored first, so at the checkpoint that authored it this directory holds this charter and three
 * subpackages, {@code config}, {@code mapper} and {@code service}, each of which holds only its own
 * charter. A type or test named below that has no file yet is therefore <b>planned</b>, not
 * missing, and a count below is a target total rather than a measurement of the directory.</p>
 *
 * <p>Alternatives Considered: withholding this charter until every class it governs
 * exists. Rejected, because the charter is what the authors of those classes work
 * from -- which type belongs here, which may not, what the closed set is -- so
 * writing it last would leave the package with no stated contract during exactly
 * the interval in which one is needed. The cost of authoring it first is that its
 * inventory reads as present tense unless the distinction is declared, which is
 * what this section is for; the sentence above is the single place a reader has to
 * look to tell a target from a measurement.</p>
 *
 * <p>Purpose: this package roots the Java re-expression of the CardDemo credit-card screens and of
 * the sequential card-file reader, migrated from z/OS COBOL running under CICS against VSAM onto
 * Spring Boot. The COBOL baseline is the specification, so the code beneath this package encodes
 * those rules rather than redefining them, and any intentional divergence is recorded in the
 * migration traceability matrix rather than introduced silently.
 *
 * <h2>Baseline provenance</h2>
 *
 * <p>Every behaviour implemented beneath this package traces to one of the following baseline
 * artifacts. They are reference material, read but never modified:
 *
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl}, 1459 lines: the card list screen, reached by CICS
 *       transaction {@code CCLI} per {@code app/csd/CARDDEMO.CSD:357}</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl}, 887 lines: the card detail screen, reached by CICS
 *       transaction {@code CCDL} per {@code app/csd/CARDDEMO.CSD:347}</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl}, 1560 lines: the card update screen, reached by CICS
 *       transaction {@code CCUP} per {@code app/csd/CARDDEMO.CSD:367}</li>
 *   <li>{@code app/cbl/CBACT02C.cbl}, 178 lines: the batch sequential card-file reader</li>
 *   <li>{@code app/cpy/CVACT02Y.cpy}, 14 lines: the 150-byte {@code CARD-RECORD} layout, which is
 *       the normative data contract for this whole context</li>
 * </ul>
 *
 * <p>Alternatives Considered: that record layout is deliberately not restated field by field
 * here. Reproducing it in this charter as well was weighed and rejected, because the layout would
 * then have two homes and the second would go stale the first time only one was edited. It is
 * single-sourced from the copybook into {@code domain}, mirroring the COBOL convention of
 * resolving every layout through one compiler include path instead of duplicating it, as the
 * repository documents at {@code tests/README.md:540-542}.
 *
 * <h2>Subpackage map</h2>
 *
 * <ul>
 *   <li>{@code domain}: the {@code Card} JPA entity</li>
 *   <li>{@code repository}: {@code CardRepository}, carrying the keyset queries and the
 *       by-account query</li>
 *   <li>{@code dto}: {@code CardSummary}, {@code CardDetail} and {@code CardUpdateRequest}</li>
 *   <li>{@code mapper}: {@code CardMapper}, the anti-corruption layer between the copybook-shaped
 *       record and the API surface</li>
 *   <li>{@code service}: {@code CardListService}, {@code CardViewService} and
 *       {@code CardUpdateService}</li>
 *   <li>{@code api}: {@code CardController}</li>
 *   <li>{@code config}: {@code SecurityConfig}, {@code OpenApiConfig} and
 *       {@code DataSourceConfig}</li>
 * </ul>
 *
 * <h2>Shared kernel</h2>
 *
 * <p>Assumptions: shared types are consumed only from {@code com.carddemo.common} and are never
 * re-declared here. Money, the fixed-width and zoned-decimal codecs, the error model, the keyset
 * page envelope and the validation flags therefore each have exactly one home, which is the Java
 * analogue of compiling every COBOL program against a single copybook include path. A local copy
 * of any of them would let this context's money arithmetic or sign handling drift from a sibling's
 * without either side changing, and money divergence is silent.
 *
 * <h2>Persistence boundary</h2>
 *
 * <p>This context owns the {@code card} schema and exactly one table within it, named
 * {@code card.cards}. Assumptions: the authoritative column list is the Flyway migration at
 * {@code services/card-service/src/main/resources/db/migration/V1__card.sql} and not this
 * overview, for the same single-sourcing reason the record layout is not restated above: a column
 * list duplicated into prose is a second definition that no migration can keep true.
 * The baseline serves the same data from the {@code CARDDAT} base cluster declared at
 * {@code app/csd/CARDDEMO.CSD:25} and reaches it by account through the {@code CARDAIX}
 * alternate-index path at {@code app/csd/CARDDEMO.CSD:13}; here that second access path becomes a
 * secondary index supporting the by-account query in {@code repository}.
 *
 * <h2>Boundaries this context does not cross</h2>
 *
 * <p>Assumptions: it neither owns nor imports {@code Account}, {@code CardXref} or
 * {@code Customer}, all of which belong to the account bounded context, and the layering rules in
 * {@code common-lib}'s architecture test make a cross-context domain import a build failure rather
 * than a review comment. One observation about the baseline is worth recording so that a reader
 * does not mistake an include for a dependency: {@code COCRDSLC.cbl}
 * and {@code COCRDUPC.cbl} each carry {@code COPY CVCUS01Y.} at lines 240 and 359 respectively,
 * yet neither program references a single {@code CUST-} field, and both additionally carry
 * commented-out includes for the account and cross-reference layouts. Those includes are inert, so
 * they confer no ownership.
 *
 * <p>Assumptions: this context also carries no messaging and no batch concern. Nothing is
 * published or consumed here, which is why {@code config} holds three classes and no queue or job
 * configuration, and why the module declares no messaging or batch dependency. The absence is a
 * property of the baseline rather than an omission to be corrected: the three card screens exchange
 * no message, and the one batch program in the provenance above is a read-and-print utility whose
 * logic becomes the read path on the repository.
 *
 * <p>Trade-offs: no golden-master oracle exists for the online screens named above, because they
 * cannot run end-to-end without a CICS runtime, as recorded at {@code tests/README.md:83-85}.
 * Parity for those paths therefore rests on the transcribed validation rules and the tests that
 * cover them rather than on byte comparison against recorded mainframe output, which is a weaker
 * guarantee than the batch contexts enjoy and is accepted because the alternative -- standing up a
 * CICS region -- is outside this migration and would not be reproducible in CI.
 */
package com.carddemo.card;
