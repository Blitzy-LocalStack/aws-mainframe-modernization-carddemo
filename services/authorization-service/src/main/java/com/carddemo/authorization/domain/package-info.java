/**
 * The persistent shape of this bounded context's own data.
 *
 * <p>Four types live here, and they are the migrated form of three storage structures the baseline keeps
 * in two different database products plus one structure the baseline does not have at all:</p>
 *
 * <ul>
 *   <li>{@link com.carddemo.authorization.domain.PendingAuthSummary} -- the ROOT segment
 *       {@code PAUTSUM0} of the hierarchical database, declared at 100 bytes in
 *       {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} line 28, with its fields laid out at
 *       {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} lines 19 to 31.</li>
 *   <li>{@link com.carddemo.authorization.domain.PendingAuthDetail}, keyed by
 *       {@link com.carddemo.authorization.domain.PendingAuthDetailKey} -- the CHILD segment
 *       {@code PAUTDTL1}, declared at 200 bytes at line 36 of that same definition, with its fields at
 *       {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 19 to 54.</li>
 *   <li>{@link com.carddemo.authorization.domain.AuthReplyOutbox} -- no baseline counterpart. It exists
 *       to close the lost-reply window described below.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the baseline stores the two segments in a hierarchical database and its
 * fraud rows in a relational one, and joins the two with a TWO-PHASE COMMIT because one decision spans
 * two resource managers. All of it is one PostgreSQL schema here, so that same decision is one local
 * transaction. Two-phase commit is therefore ELIMINATED rather than emulated -- the strongest available
 * guarantee, reached by removing a mechanism instead of reproducing it. The simplification is recorded in
 * {@code docs/adr/ADR-004-messaging.md}.</p>
 *
 * <p>Assumptions: every table name in this package is UNQUALIFIED and resolves through the connection
 * {@code search_path} pinned in this module's configuration. That is not a stylistic choice.
 * {@code authorization} is a reserved word in this database, so a schema qualification has to be quoted
 * at every single occurrence or it is a syntax error rather than a wrong lookup -- a failure mode
 * {@code data-migration/sql/V0__schemas_and_roles.sql} documents at lines 471 to 493 after hitting it.
 * Resolving through one pinned {@code search_path} keeps that quoting concern in exactly one place, and
 * it is also why this module sets no {@code hibernate.default_schema} where its peer modules do: the
 * provider does not quote a qualifier it was handed unquoted, so that property would turn every generated
 * statement into a syntax error.</p>
 *
 * <p>Assumptions: money is exact fixed point at scale two throughout. The baseline holds these amounts
 * as packed decimal -- {@code COMP-3} -- and the extract loader decodes the nibbles once at the boundary,
 * so no packed byte is stored and nothing in this package decodes one. Transformation rule T3 binds every
 * member here, and the prohibition on a binary floating-point type is enforced by the architecture test
 * rather than by review.</p>
 *
 * <p>Assumptions: no type in this package imports a cloud client, a web type or another bounded
 * context's domain package, which the layering test asserts. That is what lets each of them be
 * constructed and exercised by a plain unit test with no container, no queue and no database.</p>
 *
 * <p>Trade-offs: the entities expose accessors only for the members that application code reads. The
 * remaining members are written once at construction from a decoded request and are read only by SQL,
 * through the reporting projections. Adding a getter per column would satisfy a reflex rather than a
 * caller, and each one would be a member whose documentation has to be maintained against a copybook line
 * that nothing reads; a later boundary that needs one adds it together with the caller that justifies it.</p>
 */
package com.carddemo.authorization.domain;
