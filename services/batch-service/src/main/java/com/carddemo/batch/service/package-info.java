/**
 * Transcribed COBOL business rules of the batch bounded context. This package is the
 * service layer of batch-service and it carries decision logic only: the job
 * definitions above it wire readers, processors, writers and step ordering, the
 * repositories below it read and write rows, and the classes here hold the rules that
 * neither of those layers may own.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every inventory, file name, class name and count in this charter describes the
 * package's <b>target contract</b> as the migration plan assigns it, not the set of files present
 * beside this one today. The migration lands its artifacts in plan order and this charter is
 * authored first, so at the checkpoint that authored it this directory holds this charter and
 * nothing else. A type or test named below that has no file yet is therefore <b>planned</b>, not
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
 * <h2>Service classes and their baseline provenance</h2>
 *
 * <p>Four classes, and no fifth. The list is closed, so the question "which class does
 * this rule belong in" keeps a definite answer as the tree grows. Each significant
 * COBOL paragraph becomes one named method, so
 * {@code docs/architecture/cobol-to-service-traceability.md} can cite a
 * paragraph-to-method pair for every migrated rule rather than naming a class and
 * leaving a reader to search it. Every collaborator arrives through constructor
 * injection.</p>
 *
 * <dl>
 *   <dt>{@code PostingValidationService}</dt>
 *   <dd>PLANNED, not yet authored. The posting reject chain, from
 *       {@code app/cbl/CBTRN02C.cbl}. The baseline evaluates four conditions in a
 *       short-circuit order that is itself part of the contract, because a
 *       transaction failing two of them is rejected under the first one reached:
 *       reason 100 {@code INVALID CARD NUMBER FOUND} when the cross-reference read
 *       misses, reason 101 {@code ACCOUNT RECORD NOT FOUND}, reason 102
 *       {@code OVERLIMIT TRANSACTION}, and reason 103
 *       {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}. Two boundaries are
 *       inclusive and must stay inclusive: a balance exactly at the credit limit
 *       posts and one cent beyond rejects, and a transaction dated equal to the
 *       account expiration date posts while one day past rejects. The four message
 *       strings are carried across character for character under Transformation
 *       Rule T8.</dd>
 *
 *   <dt>{@code CategoryBalanceService}</dt>
 *   <dd>PLANNED, not yet authored. The transaction-category-balance maintenance, from
 *       {@code app/cbl/CBTRN02C.cbl}. The baseline branches on whether the category
 *       row already exists, creating it in one paragraph and updating it in another,
 *       and both paths remain separately reachable and separately testable here
 *       rather than collapsing into an upsert whose branch a test cannot observe.</dd>
 *
 *   <dt>{@code InterestCalculationService}</dt>
 *   <dd>PLANNED, not yet authored. The monthly interest accrual, from
 *       {@code app/cbl/CBACT04C.cbl}. Two behaviours are load-bearing. The
 *       disclosure-group lookup falls back to the {@code DEFAULT} group when the
 *       account's own key is not found, which is why
 *       {@code reference-service}'s seed data must carry that row. And the formula is
 *       {@code (balance * rate) / 1200}, which must multiply at full precision and
 *       only then divide with an explicit scale and rounding mode: dividing first
 *       yields different cents on many inputs, so Transformation Rule T4 forbids
 *       re-ordering it.</dd>
 *
 *   <dt>{@code DatasetGenerationService}</dt>
 *   <dd>PLANNED, not yet authored. The generation-dataset naming and retention
 *       discipline that replaces the baseline's generation data groups. Ten bases are
 *       defined across {@code app/jcl/DEFGDGB.jcl}, {@code app/jcl/DEFGDGD.jcl} and
 *       {@code app/jcl/DALYREJS.jcl}, every one at a five-generation scratch limit, so
 *       this class owns the {@code dt=}/{@code gen=} prefix convention and the
 *       relative-generation semantics that {@code (+1)} and {@code (0)} expressed.</dd>
 * </dl>
 *
 * <h2>Boundaries this package does not cross</h2>
 *
 * <p>No class here opens a transaction, reads an HTTP request or touches a queue. The
 * transaction boundary belongs to the job layer, because the baseline's posting unit of
 * work commits three writes together -- the transaction, the category balance and the
 * account -- and splitting that commit across service calls would make a partial
 * posting observable where the baseline makes it impossible.</p>
 *
 * <p>Assumptions: money crossing this package is {@code BigDecimal} at scale 2 with
 * {@code RoundingMode.HALF_UP}, never {@code double} or {@code float}. That is not a
 * convention here but a checked rule: the ArchUnit money-path assertion in
 * {@code common-lib} fails the build on a binary floating-point type in this path, so
 * the prohibition cannot rot into a comment nobody enforces.</p>
 *
 * <h2>What a green build for this package does and does not prove</h2>
 *
 * <p>Trade-offs: the parity oracle for these rules is the COBOL suite under
 * {@code tests}, which compares committed golden outputs after timestamp
 * normalisation. A green build here proves transcription compiles and its unit tests
 * pass; it does not by itself prove byte parity with the baseline, and no stronger
 * claim should be read into it. The outcome of this build is binary -- the graded
 * return-code rubric belongs to the COBOL suite and has no meaning for this
 * module.</p>
 */
package com.carddemo.batch.service;
