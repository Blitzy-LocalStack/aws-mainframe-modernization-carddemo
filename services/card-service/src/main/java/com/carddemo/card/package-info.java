// WHAT: the sole package-info.java in the com/carddemo/card chain; there is deliberately none at
//       java/com or at java/com/carddemo.
// WHY : Alternatives Considered: mirroring this file at every parent level for symmetry was
//       weighed and rejected. Checkstyle's JavadocPackage audits only a directory that contains a
//       processed .java file, and the module-entry-point obligation in Rule 1 (Explainability)
//       attaches to a package declaration, which a directory holding only subdirectories does not
//       have. Adding one there would document a package that has no entry point to document.
/**
 * Root package of the card bounded context, which owns credit-card enquiry and maintenance.
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
 * <p>That record layout is deliberately not restated field by field here. It is single-sourced
 * from the copybook into {@code domain}, mirroring the COBOL convention of resolving every layout
 * through one compiler include path instead of duplicating it, as the repository documents at
 * {@code tests/README.md:540-542}.
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
 * <p>Shared types are consumed only from {@code com.carddemo.common} and are never re-declared
 * here. Money, the fixed-width and zoned-decimal codecs, the error model, the keyset page envelope
 * and the validation flags therefore each have exactly one home, which is the Java analogue of
 * compiling every COBOL program against a single copybook include path.
 *
 * <h2>Persistence boundary</h2>
 *
 * <p>This context owns the {@code card} schema and exactly one table within it, named
 * {@code card.cards}. The authoritative column list is the Flyway migration at
 * {@code services/card-service/src/main/resources/db/migration/V1__card.sql}, not this overview.
 * The baseline serves the same data from the {@code CARDDAT} base cluster declared at
 * {@code app/csd/CARDDEMO.CSD:25} and reaches it by account through the {@code CARDAIX}
 * alternate-index path at {@code app/csd/CARDDEMO.CSD:13}; here that second access path becomes a
 * secondary index supporting the by-account query in {@code repository}.
 *
 * <h2>Boundaries this context does not cross</h2>
 *
 * <p>It neither owns nor imports {@code Account}, {@code CardXref} or {@code Customer}, all of
 * which belong to the account bounded context. One observation about the baseline is worth
 * recording so that a reader does not mistake an include for a dependency: {@code COCRDSLC.cbl}
 * and {@code COCRDUPC.cbl} each carry {@code COPY CVCUS01Y.} at lines 240 and 359 respectively,
 * yet neither program references a single {@code CUST-} field, and both additionally carry
 * commented-out includes for the account and cross-reference layouts. Those includes are inert, so
 * they confer no ownership.
 *
 * <p>This context also carries no messaging and no batch concern. Nothing is published or consumed
 * here, which is why {@code config} holds three classes and no queue or job configuration, and why
 * the module declares no messaging or batch dependency. The one batch program in the provenance
 * above is a read-and-print utility, and its logic becomes the read path on the repository.
 *
 * <p>No golden-master oracle exists for the online screens named above, because they cannot run
 * end-to-end without a CICS runtime, as recorded at {@code tests/README.md:83-85}. Parity for
 * those paths rests instead on the transcribed validation rules and the tests that cover them.
 */
package com.carddemo.card;
