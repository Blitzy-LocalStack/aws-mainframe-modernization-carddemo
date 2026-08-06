/**
 * COBOL paragraph-to-method business behaviour, identity exchange, transaction boundaries, keyset
 * orchestration.
 *
 * <h2>Target contract, not a directory listing</h2>
 *
 * <p>Assumptions: every inventory, file name, class name and count in this charter states the
 * package's <b>target contract</b> as the migration plan assigns it. It is a specification of what
 * this package owns and of what it may never hold, so it is read against the plan rather than against
 * a listing of the directory beside it.</p>
 *
 * <p>Alternatives Considered: deriving the inventory from the directory instead of from the plan.
 * Rejected, because a charter that describes whatever happens to be present cannot say what may
 * <em>not</em> be added, and that is the half of a package contract a reader cannot reconstruct from
 * the files. Stating the closed set costs a charter that has to be revised when the contract itself
 * changes, and buys a boundary a reviewer can enforce against a proposed addition.</p>
 *
 * <p>Those four responsibilities occupy one package because each of them is a decision, and this is
 * the only layer of {@code auth-service} allowed to hold a decision. The controllers above it
 * translate HTTP and shape payloads, the repositories below it move rows, and neither settles a
 * question. A reader looking for the rule that governs a sign-on attempt or a user maintenance
 * request therefore has one place to look, and the register at
 * {@code docs/architecture/cobol-to-service-traceability.md} can cite a paragraph-to-method pair for
 * each migrated rule instead of naming a class and leaving the paragraph to be hunted for. A
 * paragraph that carries a rule is never folded into its caller here, even where folding would read
 * more naturally in Java, because the register has to be able to name the method that stands for it.
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
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This file exists because the Explainability rule requires a docstring on every module entry
 * point at its line 15, and in Java a package declaration is that entry point, with
 * {@code package-info.java} the only construct able to carry Javadoc for one. Two Checkstyle checks
 * act on this file and they are not duplicates: {@code JavadocPackage} runs at checker level and
 * requires the file to be present in a package that holds Java sources, while
 * {@code MissingJavadocPackage} runs inside the tree walker and requires the file to carry Javadoc.
 * A {@code package-info.java} holding nothing but a package statement satisfies the first and fails
 * the second, so both are needed to express the actual obligation. Both fire from the
 * {@code maven-checkstyle-plugin} execution that {@code services/pom.xml} binds to the
 * {@code validate} phase, which precedes compilation on every build, and no in-code suppression
 * filter is configured, so a finding here cannot be waived from inside this file.
 *
 * <p>Of the four docstring elements that rule enumerates at its lines 18 to 21, only Purpose
 * applies. A package declaration accepts no parameter, yields no value and raises nothing, so the
 * Parameters, Return values and Exceptions elements are inapplicable here rather than omitted, and
 * no at-clause is written to stand in for one of them. The inapplicability is declared instead of
 * left silent because that rule's line 39 forbids a docstring that omits parameters, return values
 * or purpose, and a reader has to be able to tell a declared inapplicability from an oversight. An
 * invented empty at-clause would also assert something untrue and be reported by the
 * non-empty-at-clause check. The prose convention these paragraphs follow is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and the mechanical gate is
 * {@code config/checkstyle/checkstyle.xml}.
 */
package com.carddemo.auth.service;
