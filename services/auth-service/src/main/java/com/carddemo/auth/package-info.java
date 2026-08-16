/**
 * Stateless sign-on and user-administration bounded context, owning the single table
 * {@code auth.users} and recording that decision D-4 declines plaintext-password parity.
 *
 * <h2>Charter</h2>
 *
 * <p>Three clauses define this context, and each is a constraint rather than a description. It is
 * stateless: no request carries continuation state and no server-side session store exists, for
 * the reason set out under pseudo-conversational state below. It owns one table and no other
 * database object, so a reader looking here for a second table, a view or a seeded row will not
 * find one. And it is the single place in the migration where behavioural parity with the
 * baseline is deliberately declined, recorded in this charter so that the decline is discoverable
 * from the package a reader lands on first rather than only from an architecture document.</p>
 *
 * <p>This package also holds the context's Spring Boot entry point, {@code AuthApplication}, and
 * no other type. Every other type lives in a subpackage named for its layer. Assumptions: the
 * component scan is rooted here, at {@code com.carddemo.auth}, and is never widened. The entry
 * point's own declaration at {@code AuthApplication.java} L1 is {@code package com.carddemo.auth;}
 * and it carries no scan-base attribute, so the scanned root is exactly the package this file
 * documents. Shared kernel types sit outside that root under {@code com.carddemo.common} and reach
 * this context through {@code com.carddemo.common.CardDemoCommonAutoConfiguration} rather than
 * through the scan. That is the most easily misread thing about the layout, because a reader who
 * assumes the scan reaches the shared kernel will hunt for a missing component in the wrong
 * tree.</p>
 *
 * <p>Alternatives Considered: widening the scan to {@code com.carddemo} so that shared components
 * were discovered automatically. Rejected on a counted basis. {@code services/pom.xml} L191 to
 * L199 declares nine modules, one shared kernel and eight bounded contexts, so that wider root
 * would also enclose the types of the seven peer contexts and would turn independently deployable
 * services into one service started several ways. That is precisely the coupling
 * {@code LayeringRulesTest} forbids, so the widened scan would be rejected by a test even if it
 * were adopted here.</p>
 *
 * <h2>Why this charter exists at all</h2>
 *
 * <p>The project Explainability rule requires a docstring on every module entry point, and in
 * Java the entry point of a package is its package declaration, which only a
 * {@code package-info.java} compilation unit can carry. That makes this file load-bearing rather
 * than decorative.</p>
 *
 * <p>Alternatives Considered: omitting this file, or reducing it to a bare package statement.
 * Neither passes, because two independent Checkstyle modules divide the obligation between them
 * and each enforces only half of it. {@code JavadocPackage} is a Checker-level file-set check: it
 * inspects the file system and requires a {@code package-info.java} to exist in any directory
 * holding a processed source file, and this directory holds {@code AuthApplication}, so it
 * qualifies. {@code MissingJavadocPackage} sits inside {@code TreeWalker} instead, inspects the
 * parsed tree, and requires that file to carry Javadoc. A descriptor reduced to a bare package
 * statement therefore satisfies the first module and fails the second, which is why prose is the
 * deliverable here and mere existence is not. The binding makes the consequence immediate rather
 * than deferred: the audit runs in the Maven {@code validate} phase, ahead of compilation, and is
 * configured to fail on a violation at warning severity.</p>
 *
 * <p>No parameter, return or exception at-clause appears below. A package declaration accepts no
 * argument, yields no value and raises nothing, so those elements of the rule have no counterpart
 * here, and {@code NonEmptyAtclauseDescription} would in any case report an invented tag left with
 * an empty body. Omitting them is the compliant reading of the rule rather than a departure from
 * it. The rationale the rule's inline-comment half asks for has no adjacent executable line to sit
 * beside in a one-statement compilation unit, so it is carried inside this block under the four
 * canonical labels. The written convention every block in this tree follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.</p>
 *
 * <h2>The five programs this context replaces</h2>
 *
 * <p>Each entry below gives the baseline program, its length, the CICS transaction that reaches
 * it, and the responsibility it becomes here. The transaction-to-program pairings are read from
 * {@code app/csd/CARDDEMO.CSD}, a 505-line resource definition.</p>
 *
 * <ul>
 *   <li>{@code app/cbl/COSGN00C.cbl}, 260 lines, transaction {@code CC00} defined at
 *       {@code CARDDEMO.CSD} L378 with {@code PROGRAM(COSGN00C)} on L379 -- sign-on, which becomes
 *       a token exchange against a managed identity pool.</li>
 *   <li>{@code app/cbl/COUSR00C.cbl}, 695 lines, transaction {@code CU00} at L449 with
 *       {@code PROGRAM(COUSR00C)} on L450 -- the user list, browsed by key ten rows at a time.</li>
 *   <li>{@code app/cbl/COUSR01C.cbl}, 299 lines, transaction {@code CU01} at L459 with
 *       {@code PROGRAM(COUSR01C)} on L460 -- user create.</li>
 *   <li>{@code app/cbl/COUSR02C.cbl}, 414 lines, transaction {@code CU02} at L469 with
 *       {@code PROGRAM(COUSR02C)} on L470 -- user update.</li>
 *   <li>{@code app/cbl/COUSR03C.cbl}, 359 lines, transaction {@code CU03} at L479 with
 *       {@code PROGRAM(COUSR03C)} on L480 -- user delete.</li>
 * </ul>
 *
 * <p>Every one of the four user-administration transactions is declared
 * {@code TWASIZE(0) PROFILE(DFHCICST) STATUS(ENABLED)}, so none of them carries task-local storage
 * that a migrated handler would have to account for. Sign-on ends by transferring control to one
 * of two menus, and the branch is decided by the user type rather than by the terminal. The
 * administrative menu is a different transaction entirely, {@code CA00}, defined at
 * {@code CARDDEMO.CSD} L327 with {@code PROGRAM(COADM01C)} on L328, the program itself being
 * declared at L189. It is named here only to settle the boundary: {@code CC00} reaches
 * {@code COSGN00C} and nothing else, and the four transactions {@code CU00} through {@code CU03}
 * are the whole of the user-administration surface this context serves.</p>
 *
 * <h2>The record this context inherits</h2>
 *
 * <p>The baseline record is {@code 01 SEC-USER-DATA}, declared at {@code app/cpy/CSUSR01Y.cpy}
 * L17 in a 26-line copybook whose L1 to L16 are an Apache 2.0 header and whose L24 to L26 are a
 * version banner. It is 80 bytes wide and declares six elementary fields. Because 8 + 20 + 20 + 8
 * + 1 + 23 sums to exactly 80, every declared byte is accounted for and no field escapes
 * examination. Zero-based byte offsets follow; the copybook carries no record-length banner to
 * cross-check them against, so the sum is the only available check and it closes:</p>
 *
 * <ul>
 *   <li>{@code SEC-USR-ID PIC X(08)}, L18, offset 0 -- becomes {@code user_id}, the primary
 *       key.</li>
 *   <li>{@code SEC-USR-FNAME PIC X(20)}, L19, offset 8 -- becomes {@code first_name}.</li>
 *   <li>{@code SEC-USR-LNAME PIC X(20)}, L20, offset 28 -- becomes {@code last_name}.</li>
 *   <li>{@code SEC-USR-PWD PIC X(08)}, L21, offset 48 -- not carried across at all; this is
 *       decision D-4, recorded below.</li>
 *   <li>{@code SEC-USR-TYPE PIC X(01)}, L22, offset 56 -- becomes {@code user_type}.</li>
 *   <li>{@code SEC-USR-FILLER PIC X(23)}, L23, offset 57 -- dropped. It is a named FILLER whose
 *       only role is padding the record out to its declared length, and no program or symbolic
 *       map addresses it.</li>
 * </ul>
 *
 * <p>Assumptions: the identifier stays {@code CHAR(8)} and is never widened. The migration
 * declares {@code user_id CHAR(8) PRIMARY KEY} and the copybook declares eight bytes at offset 0,
 * so that width is part of an inherited fixed-length contract which the extract loader, the screen
 * field and the request-shape length validation all depend on at once. A varying-width column
 * would accept a ninth character in silence and would stop being that contract, and the divergence
 * would then surface in the data rather than in a build. Trailing-blank handling belongs to the
 * mapper layer and storage padding belongs to the PostgreSQL {@code CHAR(n)} type, so neither
 * concern is pushed onto a caller.</p>
 *
 * <h2>The single owned table</h2>
 *
 * <p>This context owns exactly one database object, the table {@code auth.users}, created by
 * {@code services/auth-service/src/main/resources/db/migration/V1__auth.sql}. That migration holds
 * exactly one executable statement, with no transaction wrapper and no conditional-creation
 * clause, and the five columns it declares are the whole persistence contract:</p>
 *
 * <ul>
 *   <li>{@code user_id CHAR(8) PRIMARY KEY}</li>
 *   <li>{@code first_name VARCHAR(20) NOT NULL}</li>
 *   <li>{@code last_name VARCHAR(20) NOT NULL}</li>
 *   <li>{@code user_type CHAR(1) NOT NULL CHECK (user_type IN ('A','U'))}</li>
 *   <li>{@code cognito_sub UUID NOT NULL UNIQUE}</li>
 * </ul>
 *
 * <p>Four consequences follow from that list being complete, and each is a prohibition on this
 * subtree rather than an observation about it. There is no password column, for the reason
 * recorded as D-4. There is no version, system or other optimistic-lock column, so no entity here
 * declares an optimistic-version field. There is no created, updated, audit or soft-delete
 * timestamp, so no type here carries a timestamp field and the shared
 * {@code com.carddemo.common.time.TimestampFormatter} is not imported by this context at all. And
 * there is no index beyond those the primary key and the unique constraint already imply, no
 * second table, no view, trigger, function, sequence or extension, and no seeded row.</p>
 *
 * <p>Assumptions: the {@code auth} schema itself is created neither here nor by that migration.
 * {@code V1__auth.sql} carries exactly one executable statement and no schema-creation statement of
 * any kind, because schema, role and grant bootstrap belongs to
 * {@code data-migration/sql/V0__schemas_and_roles.sql}. Every type in this subtree therefore
 * assumes the schema already exists by the time the migration runs, and none attempts to create it.
 * Where the committed contract at
 * {@code services/auth-service/src/main/resources/openapi/auth-api.yaml} and the migration ever
 * disagree about a field, the migration is the authority; the contract describes a transport shape,
 * while the migration is the object that shape is derived from.</p>
 *
 * <h2>Decision D-4: the one declined parity</h2>
 *
 * <p>Refactoring Rationale: the baseline stores an eight-character cleartext password in the user
 * record and compares it in application code. {@code app/cpy/CSUSR01Y.cpy} L21 declares
 * {@code 05 SEC-USR-PWD PIC X(08).} at zero-based offset 48, and {@code app/cbl/COSGN00C.cbl} L223
 * compares it directly with {@code IF SEC-USR-PWD = WS-USER-PWD}. The baseline does that; the Java
 * encodes authentication against a managed identity pool that holds the credential outside this
 * system and keeps only {@code cognito_sub} locally; the divergence is documented. This is the
 * single point in the migration where parity is declined on purpose, which is why it sits in the
 * charter rather than in a footnote.</p>
 *
 * <p>The credential surfaces at six places in the baseline, and naming all six is what makes the
 * decline complete rather than partial. It is stored at {@code CSUSR01Y.cpy} L21; compared at
 * {@code COSGN00C.cbl} L223; written from the create screen at {@code app/cbl/COUSR01C.cbl} L157;
 * echoed back to the terminal by the update screen at {@code app/cbl/COUSR02C.cbl} L169; read
 * again as the password arm of that screen's change detection at {@code COUSR02C.cbl} L227 to
 * L229; and carried in both the input and the echo face of the update map at
 * {@code app/cpy-bms/COUSR02.CPY} L78 and L152. Not one of the six has a counterpart here: there
 * is no password column, no entity field, no create, update or response component, and no local
 * comparison anywhere in this subtree.</p>
 *
 * <p>Alternatives Considered: two other designs were available and both were rejected. Porting the
 * password file read as it stands would have reproduced the cleartext credential in a database
 * column, in every backup taken of that column, and in any log line or query result that touched
 * the row. Adding a hashed local column instead would have kept credential material inside this
 * application's own storage and would have made this context responsible for a verification
 * algorithm, a work factor and a rotation policy that it has no other reason to own. Delegating to
 * a managed identity pool removes the whole class of exposure rather than narrowing it, and the
 * only column the design adds in exchange is {@code cognito_sub UUID NOT NULL UNIQUE}, which
 * carries no secret.</p>
 *
 * <h2>No optimistic version column, and why this context differs from its siblings</h2>
 *
 * <p>Update and delete here are a plain transactional read-modify-write and a plain transactional
 * read-then-delete. There is no before-image comparison and no optimistic version column, and that
 * is a deliberate difference from the account, customer and card entities elsewhere in the
 * migration, which genuinely do need one because the edit they guard spans a client think-time
 * gap.</p>
 *
 * <p>Refactoring Rationale: three independent witnesses show the baseline never needed a version,
 * so adding one would manufacture a conflict outcome the baseline cannot produce. First,
 * {@code app/cbl/COUSR02C.cbl} re-reads the record inside the update action at L215 to L217,
 * issues {@code EXEC CICS READ} at L322 to L331 carrying {@code RIDFLD (SEC-USR-ID)} on L326 and
 * {@code UPDATE} on L328, and then issues {@code EXEC CICS REWRITE} at L358 to L366 with neither
 * {@code RIDFLD} nor {@code KEYLENGTH}, because the read has already positioned and locked the
 * record. Second, {@code app/cbl/COUSR03C.cbl} follows the same shape for deletion at L188 to
 * L192, with {@code EXEC CICS READ} at L269 to L278 carrying {@code RIDFLD} on L273 and
 * {@code UPDATE} on L275, followed by {@code EXEC CICS DELETE} at L307 to L311 with no
 * {@code RIDFLD}, no {@code KEYLENGTH}, no {@code FROM} and no {@code LENGTH}. Third, the file is
 * defined pessimistically: {@code app/csd/CARDDEMO.CSD} L89 declares {@code RLSACCESS(NO)} and L93
 * declares {@code UPDATEMODEL(LOCKING)}. The lock is therefore held inside a single CICS task from
 * read through write and is never held across client think-time, so no window exists in which a
 * competing writer could be detected by a version.</p>
 *
 * <p>Assumptions: the read that precedes each mutation does carry its record identifier, and only
 * the mutation that follows omits it. Reading that omission as a missing key, rather than as
 * positioning already established by the preceding read, is the misreading this paragraph exists to
 * prevent. The create path settles the point from the other direction:
 * {@code app/cbl/COUSR01C.cbl} issues its {@code EXEC CICS WRITE} with {@code RIDFLD} present on
 * L244, so the insert is keyed, which is why that path has exactly one duplicate-key arm to
 * handle.</p>
 *
 * <p>The contrast that settles the comparison with the sibling contexts is
 * {@code app/cbl/COACTUPC.cbl}, a 4236-line program that does hand-roll before-image optimistic
 * concurrency. It carries a change flag, {@code 05 WS-DATACHANGED-FLAG PIC X(1).} at L168; it names
 * the resulting condition {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} at L521 to L522; and it
 * snapshots the entire pre-edit record under {@code 05 ACUP-OLD-DETAILS.} at L669, holding each
 * numeric twice, once as a display field and once through a numeric redefinition -- for instance
 * {@code 15 ACUP-OLD-CURR-BAL PIC X(12).} at L675, redefined as {@code PIC S9(10)V99} at L676 to
 * L677. None of the five programs this context replaces contains any equivalent of that
 * machinery.</p>
 *
 * <p>One nearby construct must not be mistaken for a concurrency check.
 * {@code app/cbl/COUSR02C.cbl} L239 emits {@code 'Please modify to update ...'}, highlighted
 * through {@code DFHRED} at L241, when an operator submits the update screen without having altered
 * a field. That is change detection on the submitter's own input, which is the semantic opposite of
 * a conflict with another writer, and conflating the two would put a concurrency message on a
 * no-op submission.</p>
 *
 * <p>Trade-offs: not one of the five programs contains a single {@code SYNCPOINT} verb, so the
 * declarative transaction boundaries this context declares in Java are a design decision taken here
 * rather than a parity requirement inherited from the baseline. The baseline obtained its atomicity
 * structurally, because the pessimistic lock spanned a CICS task that committed at its end.
 * Declaring the boundary explicitly is more code than the baseline needed and states an invariant
 * the baseline left implicit; the compromise accepted is that extra declaration, in exchange for
 * each mutation's extent being readable at the method that performs it instead of being inferred
 * from a runtime that is no longer present.</p>
 *
 * <h2>Pseudo-conversational state is decomposed, not ported</h2>
 *
 * <p>Refactoring Rationale: the baseline is strictly pseudo-conversational, so a CICS task ends at
 * every screen turn and all continuity between turns travels in one passed structure.
 * {@code app/cbl/COSGN00C.cbl} declares that structure across L64 to L67 as
 * {@code 01 DFHCOMMAREA.} holding {@code 05 LK-COMMAREA PIC X(01)} with
 * {@code OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}; it detects first entry at L80 with
 * {@code IF EIBCALEN = 0}; and it ends each turn at L98 to L102 with {@code EXEC CICS RETURN},
 * whose {@code COMMAREA} operand is on L100. The structure itself is
 * {@code 01 CARDDEMO-COMMAREA.} at {@code app/cpy/COCOM01Y.cpy} L19, in a 47-line copybook
 * declaring five {@code 05} groups at L20, L32, L37, L40 and L42 whose widths sum to exactly 160
 * bytes, as 34 + 84 + 12 + 16 + 14. None of it is ported. Four separate target mechanisms replace
 * that one structure, and the fourth is a removal rather than a replacement:</p>
 *
 * <ul>
 *   <li>The navigation fields -- the from and to transaction and program names at L21 to L24, and
 *       the last map and mapset at L43 and L44 -- become client-side router history. No
 *       server-side next-program field exists here in any form.</li>
 *   <li>Identity -- the user identifier and user type at L25 and L26 -- becomes claims on a
 *       validated token. This is a security improvement and not merely a re-housing: the structure
 *       was storage that the client echoed back, so a client could in principle assert its own user
 *       type, whereas a signed claim cannot be asserted by the client at all.</li>
 *   <li>Selection context -- the customer, account and card identifiers carried by the remaining
 *       groups -- becomes request path and query parameters, which makes each request
 *       self-describing and therefore independently authorizable.</li>
 *   <li>The re-entry discriminator disappears entirely. {@code COCOM01Y.cpy} L29 declares
 *       {@code 10 CDEMO-PGM-CONTEXT PIC 9(01).}, with the bare-numeric conditions
 *       {@code 88 CDEMO-PGM-ENTER VALUE 0.} on L30 and {@code 88 CDEMO-PGM-REENTER VALUE 1.} on
 *       L31. A stateless handler that answers an invalid submission with a field-error array has no
 *       first-entry-versus-re-entry distinction left to draw, so the discriminator has nothing left
 *       to discriminate.</li>
 * </ul>
 *
 * <p>The consequence is the charter's first clause. Every type in this subtree is stateless: there
 * are no sticky sessions and no server-side session store, which is exactly what lets horizontally
 * scaled container tasks sit behind a load balancer and serve any request interchangeably.</p>
 *
 * <h2>The admitted user-type domain</h2>
 *
 * <p>Assumptions: the complete domain of the user type is two values, and its sole authority is
 * {@code app/cpy/COCOM01Y.cpy} L26 to L28, which declares
 * {@code 10 CDEMO-USER-TYPE PIC X(01).} and then names both admitted values as quoted literals --
 * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'.} on L27 and {@code 88 CDEMO-USRTYP-USER VALUE 'U'.} on
 * L28. The user record is deliberately not the authority: {@code app/cpy/CSUSR01Y.cpy} L22 declares
 * only {@code 05 SEC-USR-TYPE PIC X(01).} and defines no condition name at all, and the screen
 * programs test that field for non-blank rather than for membership. Treating the record as the
 * authority is exactly what admits a third value by accident.</p>
 *
 * <p>Those two values are the authorization boundary of the whole context, so the domain is
 * enforced three times over rather than once: in request-shape validation, before a value reaches a
 * service; in service logic, where administrative routes are guarded by the group claim; and in the
 * database, by {@code CHECK (user_type IN ('A','U'))}, which holds for every write path including
 * one that never passes through application code. A single point of enforcement would leave the
 * boundary open to whichever path bypassed it.</p>
 *
 * <h2>The subtree this package roots</h2>
 *
 * <p>Assumptions: <b>all eight package charters that make up this context exist</b>, this one
 * included -- this root, {@code .api}, {@code .service}, {@code .repository}, {@code .domain},
 * {@code .dto}, {@code .mapper} and {@code .config} -- so the list below is a measurement of the
 * directories beside this one and not a target awaiting them. Each subpackage is named for its layer,
 * so the layer a type belongs to is legible from its import alone:</p>
 *
 * <p>Refactoring Rationale: two earlier revisions of this paragraph are corrected here rather than
 * overwritten silently, because each misled a reader in a different direction. The first stated the
 * figure of eight flatly at a point when two directories did not exist, so a count was presented as an
 * inventory. The second marked {@code .api} and {@code .mapper} as planned -- which was true when it
 * was written and is now false, {@code api/AuthController.java} and {@code mapper/UserMapper.java}
 * both being present. The second wording was the more damaging of the two: a reader looking for the
 * REST adapter or the fixed-width boundary would have been told to expect neither and would have had no
 * reason to open the directory that holds them. Assumptions: the correction removes the planned markers
 * entirely rather than moving them, because no subpackage of this context is now outstanding, and a
 * marker kept for symmetry would be the next thing to go stale.</p>
 *
 * <dl>
 *   <dt>{@code com.carddemo.auth}</dt>
 *   <dd>Stateless sign-on and user-administration bounded context. Owns {@code auth.users} only.
 *       Records that decision D-4 declines plaintext-password parity.</dd>
 *
 *   <dt>{@code .api}</dt>
 *   <dd>REST adapters only -- transport validation, HTTP status mapping, delegation. No business
 *       rules, no persistence access. Holds {@code AuthController}, which serves the three pre-token
 *       exchanges: sign-on, the sign-on challenge answer and token renewal.</dd>
 *
 *   <dt>{@code .service}</dt>
 *   <dd>COBOL paragraph-to-method business behaviour, identity exchange, transaction boundaries,
 *       keyset orchestration.</dd>
 *
 *   <dt>{@code .repository}</dt>
 *   <dd>JPA keyed operations plus exactly two keyset browse queries. No offset paging.</dd>
 *
 *   <dt>{@code .domain}</dt>
 *   <dd>Persistence entity derived from the copybook through the anti-corruption boundary. No
 *       password field, no version field.</dd>
 *
 *   <dt>{@code .dto}</dt>
 *   <dd>API records derived from BMS symbolic-map and copybook field order and widths. No local
 *       page type, no local error type.</dd>
 *
 *   <dt>{@code .mapper}</dt>
 *   <dd>The sole boundary at which fixed-width, trailing-blank and FILLER representation concerns
 *       may appear. Holds {@code UserMapper}.</dd>
 *
 *   <dt>{@code .config}</dt>
 *   <dd>Stateless JWT security, OpenAPI metadata, datasource and {@code search_path} wiring.</dd>
 * </dl>
 *
 * <h2>Dependency discipline</h2>
 *
 * <p>This context has exactly one sibling-module dependency, the shared kernel
 * {@code common-lib}. It never imports another service module, and never another service's
 * persistence package. The kernel types it consumes are consumed and never re-declared:
 * {@code error.ApiError}, {@code error.GlobalExceptionHandler}, {@code error.AbendDetail},
 * {@code web.CorrelationIdFilter}, {@code web.PageResponse}, {@code security.JwtRoleConverter} and
 * {@code observability.MetricsConfig}, each of them under {@code com.carddemo.common}. Two kernel
 * areas are deliberately not consumed here: the timestamp formatter, because no column of
 * {@code auth.users} is a timestamp, and the monetary types, because this bounded context handles
 * no monetary value at all. A security configuration, by contrast, has no kernel counterpart, so
 * {@code config/SecurityConfig.java} is owned by this context rather than inherited from the
 * kernel.</p>
 *
 * <p>Trade-offs: keeping shared concerns in one module rather than restating them per service is
 * the transformation rule the migration applies everywhere -- one former {@code COPY} statement
 * becomes one type import, taken from the single package that owns that contract. It is the direct
 * analogue of compiling every baseline program against one copybook include path, and the house
 * precedent is explicit at {@code tests/README.md} L540 to L542, which requires that a layout never
 * be duplicated and be kept single-sourced from {@code app/cpy}. The compromise accepted is a
 * build-order dependency: the kernel module must build before this one, and a change to a shared
 * type is felt by every service at once. Restating each concern locally would remove that coupling,
 * and would in exchange let two services disagree about a contract the baseline single-sourced,
 * which is the failure the rule exists to prevent.</p>
 *
 * <p>Layering itself has exactly one owner, the shared architecture test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * which the reactor selects through a dedicated architecture-rules test execution. Being a test
 * rather than a configuration entry, it cannot rot unnoticed. The Checkstyle import-restriction
 * module is deliberately absent from {@code config/checkstyle/checkstyle.xml} and must stay absent,
 * so that two mechanisms can never disagree about which one is authoritative.</p>
 *
 * <p>The migration has nine package roots: the shared kernel {@code com.carddemo.common}, this
 * context {@code .auth}, and seven peers named {@code .account}, {@code .card},
 * {@code .transaction}, {@code .reference}, {@code .batch}, {@code .authorization} and
 * {@code .reporting}. This file roots the {@code .auth} context. No file in this subtree may
 * reference any of the seven peer roots in any form -- not as an import, not as a fully qualified
 * name, and not as a string literal resolved reflectively.</p>
 *
 * <h2>What this subtree must never contain</h2>
 *
 * <p>Each prohibition below holds because the capability already has a single owner elsewhere, and
 * a local copy would create a second one:</p>
 *
 * <ul>
 *   <li>No local exception type, no local error type and no local pagination type. The kernel owns
 *       the problem shape, the abend detail and the keyset envelope.</li>
 *   <li>No message-catalog class, no properties file and no enum file. User-visible text is
 *       reproduced character for character where it is used, and an enumeration of the two admitted
 *       user-type values would become a fourth place obliged to stay in step with the three named
 *       above.</li>
 *   <li>No queue configuration and no batch-job configuration. This context neither consumes nor
 *       produces a message, and it runs no scheduled job.</li>
 *   <li>No architecture test. That rule set lives in the kernel's test sources under the single
 *       reserved name recorded above, and a second copy here would be a second authority.</li>
 *   <li>No README and no ignore file at this level. Module documentation lives at
 *       {@code services/auth-service/README.md}.</li>
 *   <li>No package descriptor above this one. Neither
 *       {@code services/auth-service/src/main/java/com/} nor
 *       {@code services/auth-service/src/main/java/com/carddemo/} contains a processed
 *       {@code .java} file -- each holds exactly one child directory and no file at all -- so the
 *       file-set check does not fire for either directory, and a descriptor placed there would
 *       document a package with no compilation unit to document.</li>
 * </ul>
 *
 * <h2>How this context is verified</h2>
 *
 * <p>Assumptions: this context has no executable baseline oracle, and nothing here may claim one.
 * All five programs it replaces are online CICS programs, and {@code tests/README.md} records at
 * L43 to L46, and again at L83 to L85, that such programs cannot be run end to end without a CICS
 * runtime, which is absent from the runner; only their extractable field-validation logic is unit
 * tested there. Behaviour here is therefore verified against the programs and copybooks read as a
 * specification, rather than against captured output. What that does not weaken is text fidelity:
 * every user-visible message is directly checkable against its originating copybook or program, and
 * is asserted character for character, because the migration carries such strings across
 * verbatim.</p>
 *
 * <p>Assumptions: the audit that governs this file is pass or fail, with no tolerated warning
 * level. Maven, Checkstyle, the two test plugins and the test engine each either succeed or do not,
 * and the documentation gate is configured to fail on a violation at warning severity, so warning
 * and failure are the same outcome here. The graded condition-code rubric recorded at
 * {@code tests/README.md} L412 to L423 belongs to the COBOL suite alone: its L420 declares code 4 a
 * warn or soft reject, which that suite treats as a passing result. That rubric never describes a
 * build in this tree, and reading a build here through it would treat a real violation as an
 * acceptable outcome.</p>
 */
package com.carddemo.auth;
