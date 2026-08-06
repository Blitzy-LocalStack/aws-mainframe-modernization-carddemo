/**
 * Read-only data access of the reporting bounded context. This package is the
 * repository layer of reporting-service, and it is the only layer here that reaches the
 * database: the services above it compose report and statement content, and no class
 * outside this package issues a query.
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
 * <p>Four roles, each reading one of the cross-schema views this module is granted, and
 * each mapping to a view projection in the sibling {@code domain} package rather than to
 * a table another context owns.</p>
 *
 * <dl>
 *   <dt>Report transaction access</dt>
 *   <dd>PLANNED, not yet authored. Reads {@code ReportTransactionView} filtered by
 *       processing date. Assumptions: the date range is a job parameter rather than a
 *       clock read, because the baseline supplies it through the sort control cards in
 *       {@code app/jcl/TRANREPT.jcl} and a rerun of that job over the same range must
 *       reproduce the same report bytes.</dd>
 *
 *   <dt>Statement transaction access</dt>
 *   <dd>PLANNED, not yet authored. Reads {@code StatementTransactionView} in card
 *       order. Assumptions: the card-ordered traversal the baseline achieves with a
 *       separate sorted file becomes an index plus a read-only projection here, not a
 *       second physical table, so there is no copy to keep in step.</dd>
 *
 *   <dt>Transaction-type access</dt>
 *   <dd>PLANNED, not yet authored. Reads {@code TransactionTypeView} to resolve the
 *       type descriptions a report line prints.</dd>
 *
 *   <dt>Transaction-category access</dt>
 *   <dd>PLANNED, not yet authored. Reads {@code TransactionCategoryView} to resolve the
 *       category descriptions a report line prints.</dd>
 * </dl>
 *
 * <h2>Why every role here is read-only, and how that is enforced</h2>
 *
 * <p>This module owns no schema and no table. It reads views over {@code ledger} and
 * {@code reference} under a dedicated database role holding {@code SELECT} grants only,
 * so a write attempted from this package fails at the database rather than succeeding
 * against data another context is responsible for.</p>
 *
 * <p>Alternatives Considered: a read replica for reporting traffic. Rejected because it
 * buys no parity benefit and costs both money and replica-lag semantics -- a statement
 * generated from a lagging replica could omit a transaction the writer had already
 * accepted, which is a behavioural difference the baseline does not have. Reads go to
 * the writer through read-only views instead.</p>
 *
 * <p>Trade-offs: read-only access is asserted in two independent places, the database
 * grant and this charter, and the grant is the one that decides. Documenting it here as
 * well is what tells an author of a future class in this package that adding a write
 * method is a design error rather than a missing feature.</p>
 *
 * <h2>Boundaries this package does not cross</h2>
 *
 * <p>No class here formats output. The 133-column fixed-width report layout, its
 * {@code -ZZZ,ZZZ,ZZZ.ZZ} and {@code +ZZZ,ZZZ,ZZZ.ZZ} edit masks and the statement
 * rendering all belong to the sibling {@code mapper} package, so a change to a column
 * position never reaches a query and a change to a query never reaches a column
 * position.</p>
 *
 * <p>Assumptions: no type here imports an AWS SDK type or a web type, and no type here
 * imports another service's {@code domain} package. Both prohibitions are ArchUnit
 * assertions in {@code common-lib} rather than conventions, so a violation fails the
 * build instead of a review.</p>
 */
package com.carddemo.reporting.repository;
