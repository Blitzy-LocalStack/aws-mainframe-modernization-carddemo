/**
 * Read and write access to the six reference tables, and the keyset queries that replace the
 * baseline's cursor paging.
 *
 * <h2>Purpose</h2>
 *
 * <p>Every type here is a Spring Data JPA repository over one entity of
 * {@code com.carddemo.reference.domain}. Their only consumer is
 * {@code com.carddemo.reference.service}; nothing here decides a business rule, renders a message or
 * reads a request shape. A repository is the migrated form of a file verb: where the baseline issues a
 * keyed read, a start-browse and a read-next, this package offers a keyed finder and a bounded ordered
 * walk.</p>
 *
 * <p>Assumptions: all six repositories are <b>landed</b> as compilation units beside this descriptor,
 * one per table the migration creates. Nothing named below is outstanding, so a reader who cannot open
 * one has found a gap rather than the expected state.</p>
 *
 * <h2>Why every browse is a bounded ordered walk and never an offset</h2>
 *
 * <p>Alternatives Considered: offset paging is shorter to express and was rejected on a behavioural
 * ground rather than a preference. A window taken by ordinal skips rows and repeats rows when another
 * caller inserts or deletes between two requests, because what precedes the ordinal is re-counted
 * against a changed set. A position that names a key cannot do either. This reference data is
 * maintained through the very operations these queries serve -- the transaction-type and category
 * screens insert, replace and delete rows -- so that is a real case and not a hypothetical one.</p>
 *
 * <p>Assumptions: each browse offers three methods, not one: a first page with no position, a forward
 * page strictly after a position, and a backward page strictly before one, ordered descending. The
 * backward walk is a separate query rather than a reversal of the forward one because the row set it
 * must bound is different -- taking rows below a key and taking rows above it are different
 * predicates, and reversing a forward page in memory would require having read the whole table.</p>
 *
 * <p>Assumptions: every walk is bounded by a {@code Limit} the caller supplies, and the caller asks for
 * one row more than the window it intends to publish. That extra row is how a has-next flag is
 * established without a count query: if it arrives, a further page exists. The baseline establishes
 * the same flag the same way, by discovering one more record than fits on the screen.</p>
 *
 * <h2>What no type here does</h2>
 *
 * <ul>
 *   <li><b>No count query and no total.</b> {@code com.carddemo.common.web.PageResponse} carries no
 *       total and no page count, and the published contract declares none, so a count would compute a
 *       value nothing could publish -- at the cost of a second scan on every page.</li>
 *   <li><b>No derived delete or bulk update.</b> A delete is performed through the inherited keyed
 *       operation so that the foreign key the migration declares {@code ON DELETE RESTRICT} is the
 *       thing that refuses a restricted delete. A bulk statement would bypass the entity, and with it
 *       the optimistic-lock counter that makes a replace safe.</li>
 *   <li><b>No native SQL.</b> Every query here is expressed as a derived method name or as JPQL, so the
 *       property names are checked against the entities at start-up rather than at first execution.</li>
 * </ul>
 *
 * <h2>Why this file exists</h2>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) requires a docstring on every module entry
 * point, and in Java a package's entry point is its package declaration, which only a
 * {@code package-info} compilation unit can carry. {@code config/checkstyle/checkstyle.xml} enforces
 * that in two halves -- {@code JavadocPackage} asserts this file exists and
 * {@code MissingJavadocPackage} asserts it carries Javadoc -- and both run at the {@code validate}
 * phase with violations failing the build. The written convention it follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
package com.carddemo.reference.repository;
