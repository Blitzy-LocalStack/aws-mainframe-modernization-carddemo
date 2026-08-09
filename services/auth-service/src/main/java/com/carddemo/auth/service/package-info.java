/**
 * COBOL paragraph-to-method business behaviour, identity exchange, transaction boundaries, keyset
 * orchestration.
 *
 * <h2>Baseline provenance</h2>
 *
 * <p>Everything beneath {@code app/} is reference material: it is the behavioural specification for
 * this package and is never modified. All five programs below reach the single {@code USRSEC} file
 * declared at {@code app/csd/CARDDEMO.CSD} line 88, each through the CICS transaction that the same
 * file defines for it. Line counts are given so a reader can tell at a glance how much baseline each
 * responsibility answers for.
 *
 * <ul>
 *   <li>{@code app/cbl/COSGN00C.cbl} (260 lines), transaction {@code CC00} per
 *       {@code app/csd/CARDDEMO.CSD} lines 378 and 379, is where the identity-exchange
 *       responsibility comes from, in its {@code READ-USER-SEC-FILE} paragraph at line 209.</li>
 *   <li>{@code app/cbl/COUSR00C.cbl} (695 lines), transaction {@code CU00} per lines 449 and 450, is
 *       where the keyset-orchestration responsibility comes from.</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} (299 lines), transaction {@code CU01} per lines 459 and 460,
 *       adds a user, with its write at line 240.</li>
 *   <li>{@code app/cbl/COUSR02C.cbl} (414 lines), transaction {@code CU02} per lines 469 and 470,
 *       updates one, with its read at line 322 and its rewrite at line 360.</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} (359 lines), transaction {@code CU03} per lines 479 and 480,
 *       removes one, with its read at line 269 and its delete at line 307.</li>
 * </ul>
 *
 * <h2>Why identity exchange sits in this layer</h2>
 *
 * <p>The baseline settles identity inside the application: the paragraph at
 * {@code app/cbl/COSGN00C.cbl} line 209 reads the security record and line 223 compares the stored
 * password against the entered one directly. The Java hands the credential check to a managed
 * identity provider and keeps only the exchange that turns a verified caller into a token and a
 * group membership, so no password value is carried forward into any table, payload or log. That
 * divergence is recorded in the register named above rather than introduced quietly. The separation
 * is narrow and deliberate: the baseline remains the specification for the three sign-on outcomes at
 * lines 242, 249 and 254, whose message text the classes here reproduce character for character, and
 * it is deliberately not the specification for how a secret is held.
 *
 * <p>Assumptions: the provider is named in the class-level documentation of the one class that talks
 * to it, never in this charter. Stating the responsibility as identity exchange keeps the package
 * charters of this migration reading consistently and means the charter does not have to be reopened
 * to change what sits behind the exchange.
 *
 * <h2>Why the transaction boundary is declared in this layer</h2>
 *
 * <p>Refactoring Rationale: the baseline defines {@code USRSEC} with {@code RECOVERY(NONE)} at
 * {@code app/csd/CARDDEMO.CSD} line 96, and no {@code SYNCPOINT} verb appears in any of the five
 * programs, so its updates are neither journalled nor backed out and a request that stops midway
 * leaves whatever it had already written in place. The Java encodes the same unit of work as an
 * explicit transaction boundary declared in this package, so a request that fails commits nothing.
 * The divergence is documented, and the reference source is left as it stands.
 *
 * <p>The boundary belongs here rather than on a repository method because it has to span a whole
 * rule, and this is the only layer that sees a whole rule. Scoping it to a single row access would
 * split a multi-step maintenance request into that many independently committed pieces, which is the
 * partial-write behaviour described above arriving back under a different name.
 *
 * <p>Refactoring Rationale: the boundary spans the DATABASE work and stops there -- the identity
 * provider is called strictly after the commit, never inside it. This is a correction rather than a
 * design preference, and the defect it corrects is worth naming because the earlier arrangement looked
 * safer than it was. Each of the three write paths was annotated transactional and issued its provider
 * call from within, which described the two stores as one atomic unit. They are not: the provider is
 * not a transaction participant, so it does not roll back. A provider call that SUCCEEDED and was
 * followed by a failed commit therefore left the provider holding a change to a row that was never
 * written -- and in the delete case, destroyed the account behind a row that still exists. Holding a
 * database connection across provider network latency was the smaller half of the problem.
 *
 * <p>What replaces it is a durable intention: each write path commits its row together with a ledger
 * entry in {@code auth.identity_sync_task} naming what the provider owes, and applies that entry after
 * the commit. Trade-offs: the two stores become eventually rather than immediately consistent, and the
 * window is the interval between commit and the post-commit call -- ordinarily sub-second, bounded by
 * the reconciliation pass otherwise. That is the cost. What is bought is that an inconsistency is now
 * always RECORDED and always converges, where before it was silent and permanent.
 * Alternatives Considered: a two-phase commit across the database and the provider, which the provider
 * does not support at all; and ordering the two calls so the smaller inconsistency is the reachable one
 * while accepting it, which is what the earlier arrangement did on the create path and which leaves an
 * identifier permanently unusable through a path only a log line records.
 *
 * <p>Assumptions: creation is the one path whose ordering cannot be reversed, because
 * {@code V1__auth.sql} declares {@code cognito_sub NOT NULL UNIQUE} and the provider mints that value,
 * so no row can be written before the provider has acted. It therefore provisions first with no
 * transaction open, flushes its insert inside the handler that compensates, and records its
 * compensating withdrawal in the same ledger so a failed insert cannot leave an orphaned account behind
 * nothing but a log line.
 *
 * <h2>Why paging is by key rather than by offset</h2>
 *
 * <p>The baseline does not read a page; it drives a browse, which {@code app/csd/CARDDEMO.CSD} line
 * 94 permits through {@code BROWSE(YES)}. In {@code app/cbl/COUSR00C.cbl} that browse opens at line
 * 588, advances at line 621, reverses at line 655 and closes at line 689, and its position between
 * screen turns is held in the communication area extension at lines 67 to 75 as a first key, a last
 * key, a page number and a next-page flag. That state is already a keyset cursor, so this package
 * assembles the same facts, a first key, a last key and whether further rows follow, from a
 * repository result list.
 *
 * <p>Alternatives Considered: numbering pages and asking the database to skip a row count was
 * evaluated and rejected. A concurrent insert or delete shifts the rows beneath an offset, so a
 * caller paging forward can be shown one row twice or never shown it at all, whereas a cursor
 * positioned by key can do neither. Adopting offsets would change behaviour a user can observe while
 * appearing to preserve the screen.
 *
 * <h2>What this package deliberately does not hold</h2>
 *
 * <p>The boundary is written down so that it can be audited. None of the following belongs here, and
 * each has exactly one owner elsewhere.
 *
 * <ul>
 *   <li>No error or problem type. The API problem shape and the structured abend detail are owned by
 *       {@code com.carddemo.common.error}, which is what makes every service module answer a failure
 *       in one shape.</li>
 *   <li>No pagination envelope type. The keyset page envelope is owned by
 *       {@code com.carddemo.common.web}. This package fills one in; it does not declare one.</li>
 *   <li>No validation-flag type. The equivalent of the baseline's not-ok and blank field flags is
 *       owned by {@code com.carddemo.common.validation}.</li>
 *   <li>No message catalog and no {@code .properties} file. Every user-visible string is an inline
 *       literal in the class that raises it, carried over character for character, and it reaches a
 *       caller through the shared problem payload. Assumptions: a catalog would put the string and
 *       the branch that produces it in two separate files, and for text this exact the only check
 *       available is reading it beside the condition it belongs to.</li>
 *   <li>No persistence access. Row access is owned by {@code com.carddemo.auth.repository}; no class
 *       here builds a query or holds a connection.</li>
 *   <li>No HTTP concern. Request binding, status selection and response shaping belong to the
 *       controller layer above.</li>
 * </ul>
 *
 * <p>No class here keeps state between requests either. The baseline is pseudo-conversational and
 * holds its continuity in the communication area, of which the browse cursor at
 * {@code app/cbl/COUSR00C.cbl} lines 67 to 75 is this context's share. Assumptions: that area
 * accounted for all of it, because all five transactions are defined with {@code TWASIZE(0)}, at
 * {@code app/csd/CARDDEMO.CSD} lines 379, 450, 460, 470 and 480, leaving no transaction work area to
 * fall back on. Here the caller's identity arrives in a validated token and the browse position
 * arrives in the request, which is what lets these classes run as several interchangeable instances
 * with nothing session-scoped behind them.
 */
package com.carddemo.auth.service;
