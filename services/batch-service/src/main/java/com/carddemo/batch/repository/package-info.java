/**
 * Data access of the batch bounded context. This package is the repository layer of
 * batch-service: every file verb of the baseline lands here and nowhere else, so no
 * job definition and no service class issues a query of its own.
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
 * <h2>Repository roles</h2>
 *
 * <dl>
 *   <dt>{@code BatchRunRepository}</dt>
 *   <dd>PLANNED, not yet authored. Access to {@code batch.batch_run}, the durable step
 *       ledger. This is the one repository this module genuinely owns, and it is what
 *       makes a resumed step idempotent: a step that already recorded completion for a
 *       run becomes a no-op rather than repeating its writes. Assumptions: the baseline
 *       has no checkpoint contract to preserve -- the only {@code RESTART=} anywhere in
 *       {@code app/jcl} is commented out and there is no {@code CHKPT=} in the tree at
 *       all -- so this ledger is a documented improvement rather than a port, and it
 *       should be described that way wherever it is cited.</dd>
 *
 *   <dt>Data access over the granted {@code ledger} and {@code account} schemas</dt>
 *   <dd>PLANNED, not yet authored. The reads and writes the posting and interest jobs
 *       perform against tables owned by {@code transaction-service} and
 *       {@code account-service}.</dd>
 * </dl>
 *
 * <h2>Why this module reaches two schemas it does not own</h2>
 *
 * <p>Alternatives Considered: a saga, with each write committed independently and
 * compensating reversals on failure. Rejected outright, and the reason is parity rather
 * than preference. The baseline commits the transaction, the category balance and the
 * account together as one unit of work, so a saga would introduce observable
 * intermediate states -- a posted transaction with an unposted balance -- that do not
 * exist today and that the golden masters would correctly flag as a parity failure.</p>
 *
 * <p>Trade-offs: the accepted cost is a documented exception to database-per-service
 * purity. This module runs against the one cluster under a dedicated database role
 * holding narrowly scoped cross-schema write grants on {@code ledger} and
 * {@code account} only, so the unit of work stays a single ACID commit while the grant
 * remains auditable and bounded. The exception is deliberate, it is recorded in
 * {@code docs/architecture/data-model-and-schema-mapping.md}, and it must not be widened
 * to a general-purpose grant.</p>
 *
 * <h2>Boundaries this package does not cross</h2>
 *
 * <p>No class here holds a business rule. A repository method names a query and returns
 * rows; the decision about what those rows mean belongs to the sibling {@code service}
 * package, which is what keeps a COBOL paragraph citable against exactly one Java
 * method.</p>
 *
 * <p>Assumptions: no type here imports an AWS SDK type or a web type, and no type here
 * imports another service's {@code domain} package. Both prohibitions are ArchUnit
 * assertions in {@code common-lib} rather than conventions, so a violation fails the
 * build instead of a review.</p>
 */
package com.carddemo.batch.repository;
