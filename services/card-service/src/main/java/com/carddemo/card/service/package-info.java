/**
 * Business rules of the card bounded context, transcribed from the CardDemo
 * card-management COBOL programs. This package is the service layer of
 * card-service and it carries decision logic only: the controllers above it
 * validate and shape HTTP, the repositories below it read and write rows,
 * and the classes here hold the rules that neither of those layers may own.
 *
 * <h2>Service classes and their baseline provenance</h2>
 *
 * <p>The line counts below are the physical lengths of the reference
 * sources. Those sources are the specification for this package: they are
 * read and never modified. The roster is complete for this directory and is held
 * to it mechanically:
 *
 * <pre>
 * this directory: 7 java files = 6 classes + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: the roster previously closed at three classes and
 * omitted {@code CardAdminViewService} and {@code CardVerificationValueCipher},
 * both of which sat beside it. The marker line above is the form
 * {@code common-lib}'s {@code PackageCharterInventoryTest} re-measures against this
 * directory on every build, and the same test requires each member enumerated below
 * to be a file here, so the next class that lands fails the build instead of
 * silently falsifying this roster.
 *
 * <ul>
 *   <li>{@code CardListService} carries the paginated card browse, from
 *       {@code app/cbl/COCRDLIC.cbl} (1459 lines, CICS transaction
 *       {@code CCLI}). The baseline walks the file forward with
 *       {@code STARTBR} at line 1129, {@code READNEXT} at lines 1146 and
 *       1197 and {@code ENDBR} at line 1258, then backward with
 *       {@code STARTBR} at line 1273, {@code READPREV} at lines 1294 and
 *       1322 and {@code ENDBR} at line 1376. Here each direction becomes a
 *       single keyset query.</li>
 *   <li>{@code CardViewService} carries the single-card detail read, from
 *       {@code app/cbl/COCRDSLC.cbl} (887 lines, CICS transaction
 *       {@code CCDL}). {@code app/cbl/CBACT02C.cbl} (178 lines) is a
 *       secondary reference for record framing and for file-status
 *       discipline: it declares {@code FILE STATUS IS CARDFILE-STATUS} at
 *       line 33, distinguishes a good read at line 94 from end-of-file at
 *       line 98, and only then reaches its abend paragraph at line 113. That
 *       three-way split, rather than a single success test, is the shape the
 *       read path reproduces.</li>
 *   <li>{@code CardUpdateService} carries the card update validation chain
 *       and the write, from {@code app/cbl/COCRDUPC.cbl} (1560 lines, CICS
 *       transaction {@code CCUP}). That program commits in exactly one
 *       place, the {@code SYNCPOINT} at line 470, and that single commit
 *       point is the one transaction boundary this package reproduces.</li>
 *   <li>{@code CardAdminViewService} carries the administrative detail read, which is
 *       the one path in this context that discloses a whole card number rather than
 *       its last four digits. Assumptions: it is a class of its own rather than a
 *       flag on {@code CardViewService}, because the widest disclosure this context
 *       performs should have one entry point that a reader and a filter-chain rule
 *       can both name; a boolean parameter would make the disclosure decision a
 *       property of a call site instead.</li>
 *   <li>{@code CardVerificationValueCipher} enciphers and deciphers the stored
 *       verification value {@code CARD-CVV-CD PIC 9(03)}, which the baseline holds in
 *       the clear inside the 150-byte record. Assumptions: it is a service rather
 *       than a mapper or a domain type because it holds key material and reaches a
 *       key-management client, and no endpoint of this context returns the value it
 *       protects -- the cipher exists so the column can be written and compared, not
 *       so it can be published.</li>
 *   <li>{@code CardRecordConflictException} is the stale-revision refusal {@code CardUpdateService}
 *       raises, narrowing the shared contention type by carrying the card as it stood when the
 *       conflict was detected. Assumptions: it is a member of this package rather than of the
 *       request-and-response package because it is raised here and is part of this layer's outward
 *       contract, in the same way the sentences above are; the SHAPE it carries belongs to the other
 *       package and is imported from it. Refactoring Rationale: the refusal used to be the shared type,
 *       which has no member able to hold a rendered row, so the published conflict body's refreshed
 *       card could not be composed at all and a stale caller was told only the version number.</li>
 * </ul>
 *
 * <h2>The record contract every class here operates on</h2>
 *
 * <p>Every read and write path here works on the 150-byte {@code CARD-RECORD} declared at
 * {@code app/cpy/CVACT02Y.cpy} line 4: {@code CARD-NUM PIC X(16)},
 * {@code CARD-ACCT-ID PIC 9(11)}, {@code CARD-CVV-CD PIC 9(03)},
 * {@code CARD-EMBOSSED-NAME PIC X(50)},
 * {@code CARD-EXPIRAION-DATE PIC X(10)},
 * {@code CARD-ACTIVE-STATUS PIC X(01)} and {@code FILLER PIC X(59)}. Those
 * seven widths sum to 150, which is what determines the record length. The
 * layout reaches this package as the {@code Card} entity in the sibling
 * {@code domain} package, with the {@code FILLER} dropped because it is
 * padding to the fixed record length and holds no value a rule can read.
 *
 * <p>Assumptions: the entity spells the fifth field {@code expirationDate},
 * which does not match the copybook name quoted above. The baseline spelling
 * is quoted verbatim here on purpose, so that the lineage from copybook
 * field to entity property stays traceable from either end; the rename
 * itself is registered in
 * {@code docs/architecture/data-model-and-schema-mapping.md}. A reader who
 * expects the two names to agree should treat that document, not this list,
 * as the mapping of record to column.
 *
 * <h2>Shared contracts this package consumes</h2>
 *
 * <p>Assumptions: the whole shared dependency surface of this package is
 * named below so that it can be read without opening a class, and every
 * entry is taken from {@code com.carddemo.common} and from nowhere else.
 *
 * <ul>
 *   <li>{@code com.carddemo.common.web.PageResponse} is the keyset page
 *       envelope the browse answers with.</li>
 *   <li>{@code com.carddemo.common.error.ApiError} is the problem shape,
 *       carrying the per-field error array, and
 *       {@code com.carddemo.common.error.GlobalExceptionHandler} is the
 *       advice that maps an optimistic-lock failure onto HTTP 409. Neither
 *       mapping is restated in this package.</li>
 *   <li>{@code com.carddemo.common.error.AbendDetail} is the structured
 *       equivalent of the COBOL abend fields that {@code CBACT02C} reaches
 *       at line 113.</li>
 *   <li>{@code com.carddemo.common.validation.FieldValidationFlag} is the
 *       equivalent of the baseline per-field validation flags, and
 *       {@code com.carddemo.common.validation.DateEditValidator} carries the
 *       date edit rules the update chain applies.</li>
 * </ul>
 *
 * <p>None of those types is re-declared here. Holding them in one module is
 * the Java analogue of compiling every COBOL program against a single
 * copybook include path: a contract then has exactly one home, so it cannot
 * drift between the modules that consume it, and a reader who has learnt it
 * once has learnt it everywhere.
 *
 * <h2>Boundaries this package does not cross</h2>
 *
 * <p>Assumptions: no class here owns or imports a {@code Customer},
 * {@code Account} or {@code CardXref} type. All three belong to the account
 * bounded context, and this package reaches card data only.
 *
 * <p>One property of the baseline is worth recording, because it reads like
 * a cross-context dependency and is not one.
 * {@code app/cbl/COCRDSLC.cbl} carries {@code COPY CVCUS01Y.} at line 240
 * and {@code app/cbl/COCRDUPC.cbl} carries it at line 359, yet neither
 * program references a single {@code CUST-} field anywhere in its procedure
 * division. Both additionally carry the account layout commented out, at
 * lines 231 and 350, and the cross-reference layout commented out, at lines
 * 237 and 356. The customer include is therefore inert: it widens the
 * compiled data division and no statement reads it. The baseline includes
 * that layout; this package does not, and the divergence is recorded here
 * rather than reproduced as a dependency that never carried data.
 *
 * <p>Assumptions: the prohibition is enforced mechanically rather than left to
 * convention. {@code LayeringRulesTest}, at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture},
 * is the sole owner of layering enforcement in this build. It travels to this
 * module as a {@code test-jar} artifact and is collected by the
 * {@code architecture-rules} Surefire execution declared in
 * {@code services/pom.xml}, which scans that artifact through
 * {@code dependenciesToScan} and evaluates the rules against this module's own
 * classes -- so the boundary fails the {@code test} phase here rather than
 * failing a review. Checkstyle's {@code ImportControl}
 * module is deliberately absent from
 * {@code config/checkstyle/checkstyle.xml}, which records the reason in its
 * excluded-modules section: a second engine enforcing an overlapping half of one
 * constraint would leave a reader unable to tell which of the two owned a given
 * boundary.
 *
 * <h2>No class here holds session state</h2>
 *
 * <p>Assumptions: every class in this package is stateless, takes its
 * collaborators through the constructor and holds no static mutable state.
 * The baseline had a reason to keep continuity that this package does not:
 * its CICS tasks were pseudo-conversational, so a task ended at every screen
 * turn and what had to survive the turn travelled in a passed
 * {@code DFHCOMMAREA}. All three card transactions are defined with
 * {@code TWASIZE(0)} in {@code app/csd/CARDDEMO.CSD}, at line 348 for
 * {@code CCDL}, line 358 for {@code CCLI} and line 369 for {@code CCUP}, so
 * no transaction work area stood beside that communication area either. That
 * is what makes the accounting below complete rather than merely plausible:
 * everything carried between turns was in the one structure, and it
 * decomposes without remainder into four target mechanisms plus one field
 * that has no successor at all.
 *
 * <ul>
 *   <li>Identity becomes claims on a validated token. The baseline reads it
 *       from the communication area the terminal returns, so relocating it
 *       into a signed claim is a platform-capability difference and not a
 *       change of rule: the administrator and user split it expresses is
 *       carried across unchanged.</li>
 *   <li>Selection context becomes the request path, which makes every
 *       request self-describing and therefore independently
 *       authorizable.</li>
 *   <li>Navigation becomes client-side routing, so no method here returns
 *       the name of a next program or a next map.</li>
 *   <li>The browse cursor becomes the page envelope, and that is a
 *       transcription rather than a redesign. {@code app/cbl/COCRDLIC.cbl}
 *       already keeps a keyset cursor rather than an offset: a last-key pair
 *       at lines 230 to 232, a first-key pair at lines 233 to 235, and a
 *       next-page indicator at line 242. It derives that indicator by
 *       reading one record beyond the screen, testing the row count against
 *       its screen limit at line 1191 and then issuing a further
 *       {@code READNEXT} at line 1197 whose outcome sets the indicator at
 *       line 1210 or clears it at line 1216. Carrying those three fields
 *       across preserves page boundaries when rows are inserted between two
 *       requests, which an offset would not.</li>
 *   <li>The re-entry discriminator does not survive at all. It is
 *       {@code CDEMO-PGM-CONTEXT} at {@code app/cpy/COCOM01Y.cpy} line 29,
 *       with {@code CDEMO-PGM-ENTER} and {@code CDEMO-PGM-REENTER} at lines
 *       30 and 31, and all three card programs branch on it: at line 459 of
 *       {@code COCRDLIC.cbl}, line 357 of {@code COCRDSLC.cbl} and line 486
 *       of {@code COCRDUPC.cbl}. A stateless method that answers with a
 *       field error array has no first-entry against re-entry distinction
 *       left to draw, so no method here takes an equivalent argument or
 *       consults an equivalent flag.</li>
 * </ul>
 *
 * <h2>Why this contract is stated once, here</h2>
 *
 * <p>Alternatives Considered: repeating the shared-contract list, the
 * layering prohibition and the statelessness rule in the Javadoc of each of
 * the three service classes was weighed and rejected. Three copies diverge
 * as soon as one class is edited and the other two are not, and no build log
 * would report that they had, so a reader could not tell which copy was
 * current. Concentrating them costs one indirection when reading a single
 * class, which is accepted because this file cannot be skipped in any case:
 * {@code config/checkstyle/checkstyle.xml} requires it to exist through
 * {@code JavadocPackage} at line 215 and requires it to carry Javadoc
 * through {@code MissingJavadocPackage} at line 324, and
 * {@code services/pom.xml} binds that gate to the Maven {@code validate}
 * phase, so it is audited before a single class in this module is compiled.
 * Each class Javadoc therefore documents its own methods and cites its own
 * paragraphs, and defers to this file for the package-wide contract.
 */
package com.carddemo.card.service;
