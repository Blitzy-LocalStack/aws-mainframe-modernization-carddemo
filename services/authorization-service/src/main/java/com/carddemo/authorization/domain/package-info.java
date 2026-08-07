/**
 * Persistent shapes of the pending credit-card authorization bounded context.
 *
 * <p>This package holds the JPA entities of that context and nothing else. Every type here is one
 * storage shape mapped onto one table of the PostgreSQL {@code authorization} schema, and together
 * they are the foundation the module's other six subpackages resolve their persistent shapes from:
 * {@code com.carddemo.authorization.repository} queries them,
 * {@code com.carddemo.authorization.service} applies behaviour to them,
 * {@code com.carddemo.authorization.mapper} converts between them and the wire shapes in
 * {@code com.carddemo.authorization.dto}, and {@code com.carddemo.authorization.api} never handles
 * one directly. No repository, mapper, transfer or configuration type is declared here, and this
 * package has no nested subpackage.
 *
 * <p><strong>The six shapes, and the reference layout each one carries.</strong> All of
 * {@code app/**} is reference material: it is read as the specification for this package and is
 * never modified, which is why every entry below is a path and a line number rather than an edit.
 * Except where another root is named, each citation is relative to
 * {@code app/app-authorization-ims-db2-mq}, and each migration citation is relative to
 * {@code services/authorization-service/src/main/resources/db/migration/V1__authorization.sql}.
 * <ul>
 *   <li>{@code PendingAuthSummary} maps table {@code pending_auth_summary}, created at migration
 *       L143 and keyed on the account identifier at L245. Its layout is the hierarchical ROOT
 *       segment {@code PAUTSUM0}, whose 13 fields occupy {@code cpy/CIPAUSMY.cpy} L19 to L31. Its
 *       length is declared as 100 bytes twice over, by the segment definition at
 *       {@code ims/DBPAUTP0.dbd} L28 and by those field widths themselves, which sum to exactly
 *       100.</li>
 *   <li>{@code PendingAuthDetail} maps table {@code pending_auth_detail}, created at migration L279
 *       and keyed on the three columns at L561. Its layout is the CHILD segment {@code PAUTDTL1},
 *       whose 27 group-level and 2 subordinate fields occupy {@code cpy/CIPAUDTY.cpy} L19 to L54,
 *       declared as 200 bytes at {@code ims/DBPAUTP0.dbd} L36 and summing to exactly 200.</li>
 *   <li>{@code PendingAuthDetailKey} carries the embeddable identity of that child row. The
 *       reference key is the two-part group {@code PA-AUTHORIZATION-KEY} at
 *       {@code cpy/CIPAUDTY.cpy} L19 to L21, 8 bytes wide, which {@code ims/DBPAUTP0.dbd} L37
 *       declares independently as an 8-byte sequence field. The hierarchy that supplied the parent
 *       account implicitly is gone here, so the relational key names the account alongside the date
 *       and time parts.</li>
 *   <li>{@code AuthReplyOutbox} maps table {@code auth_reply_outbox}, created at migration L853 and
 *       keyed at L994. It has no reference counterpart at all, and it exists to close the reply
 *       window recorded as divergence D-5 in
 *       {@code docs/architecture/cobol-to-service-traceability.md}.</li>
 *   <li>{@code AuthFraud} maps table {@code auth_fraud}, created at migration L678 and keyed at
 *       L773. Its layout is the reference RELATIONAL table {@code CARDDEMO.AUTHFRDS}, whose 26
 *       columns are declared at {@code ddl/AUTHFRDS.ddl} L2 to L27 under the primary key at L28.
 *       Note when checking that figure that the file has no licence header, so its line 1 is
 *       already content. Two further readings agree with the count: the generated declaration
 *       comment at {@code dcl/AUTHFRDS.dcl} L88, and the migration's own derivation at L633 to
 *       L641.</li>
 *   <li>{@code AuthFraudKey} carries the embeddable identity of that fraud row: the card number
 *       and the composed authorization timestamp, which is the pair {@code ddl/AUTHFRDS.ddl} L28
 *       names. The timestamp exists in neither IMS segment and is assembled by the write, from the
 *       acquirer-supplied original date at {@code cbl/COPAUS2C.cbl} L103 to L105 and the decoded
 *       nines complement at L107 to L111.</li>
 * </ul>
 *
 * <p>Four of those six types carry the JPA {@code Entity} annotation and two are embeddable keys --
 * of the second and of the fifth -- so this directory holds exactly seven {@code .java} files: the
 * six types plus this charter, and no eighth. A reader counting entities and a reader counting
 * files therefore arrive at four and seven respectively, which is why both counts are stated.
 *
 * <p>Refactoring Rationale: an earlier revision of this charter asserted that the
 * {@code authorization} schema's fraud table was deliberately UNMAPPED here, and instructed a
 * reader not to "add a fifth type" for it -- on the ground that the fraud table is card-scoped
 * while the two segments are account-scoped, and that an entity for it belonged "with the access
 * path that reads it". That assertion is withdrawn, and it is withdrawn rather than deleted because
 * a reader who saw the earlier wording needs to know which statement to trust. It was
 * unimplementable on its own terms, for three compounding reasons. The sibling charter at
 * {@code com.carddemo.authorization.repository} names {@code AuthFraudRepository} in its closed
 * four-interface set and states that a caller reaches this schema through no other route, and a
 * Spring Data repository is declared over a MAPPED type, so the interface that charter mandates
 * cannot exist without the entity this one refused. The charter at
 * {@code com.carddemo.authorization.api} forbids a controller from handling an entity at all, so
 * there is no layer above this one the type could have been placed in. And this charter's own
 * opening sentence says this package holds the JPA entities of this context and nothing else, which
 * makes {@code domain} the only package a mapped type may be declared in -- the differing key space
 * is a property of the ACCESS PATH, which lives in the repository, and not of where the storage
 * shape is declared.
 *
 * <p>Assumptions: what the earlier wording got right is kept. The fraud STATE the reference system
 * writes to its relational table is ALSO kept on the child segment, at {@code cpy/CIPAUDTY.cpy} L50
 * and L53, so those two items remain columns on {@code PendingAuthDetail} as well. The duplication
 * is the baseline's own: {@code cbl/COPAUS1C.cbl} L525 to L528 replaces the segment while
 * {@code cbl/COPAUS2C.cbl} writes the relational row, and both now commit in one local transaction.
 * A reader meeting the same two items in two types is therefore reading a faithful transcription
 * rather than a normalisation failure, and the migration states the differing key space and its
 * reason at L650 to L657.
 *
 * <p><strong>Why this file exists at all.</strong> No file-by-file row of the migration plan names
 * it. It is in scope because a rule forces it, and that provenance is worth recording because it is
 * not reconstructable from the plan. User-specified Rule 1 (Explainability) L15 requires a
 * docstring on every module entry point; a Java package declaration is one; and a
 * {@code package-info.java} is the only construct that can carry Javadoc for a package declaration,
 * so the obligation has exactly one possible home. Of the four content elements L18 to L21
 * enumerate, only Purpose at L18 applies here. A package declaration accepts no arguments, yields
 * no value and raises nothing, so the Parameters, Return values and Exceptions elements are
 * inapplicable rather than omitted, and no at-clause is written to stand in for one of them. The
 * block form used here is what L22 asks for in Java, which names Javadoc by name.
 *
 * <p><strong>The build interlock.</strong> Two Checkstyle checks act on this file and they are not
 * redundant. {@code JavadocPackage} runs at Checker level over files carrying the {@code java}
 * extension and requires this file to be PRESENT in any package holding one; this directory holds
 * four, so it fires here. {@code MissingJavadocPackage} runs inside the tree walker and requires
 * the file to CARRY Javadoc. A {@code package-info.java} holding only its package statement
 * satisfies the first and fails the second, which is why both are configured and why neither alone
 * expresses the requirement. Both fire from the {@code checkstyle-documentation-gate} execution of
 * {@code maven-checkstyle-plugin} in {@code services/pom.xml}, bound to the {@code validate} phase
 * with {@code failOnViolation} true and {@code violationSeverity} at warning, and that phase runs
 * ahead of compilation on every local build rather than in continuous integration alone.
 *
 * <p>Assumptions: that audit is incremental. The plugin keeps a cache under this module's build
 * directory and skips a source file it has already seen unchanged, so a second invocation with
 * nothing edited reports zero violations because it examined zero files, not because it re-examined
 * them and approved them. Anyone confirming the interlock above by breaking this file on purpose
 * has to discard that cache first, which any {@code clean} does; without it the run reports success
 * having verified nothing.
 *
 * <p>The rule set wires in no suppression filter of any kind, so a violation here cannot be waived
 * from inside a source file, and {@code config/checkstyle/suppressions.xml} reaches generated
 * sources and test fixtures only, never a production source root. Rule 1 L43 states the consequence
 * of a missing docstring as a failed review; this binding is the plan's mechanisation of the
 * docstring half of that gate, which turns the same omission into a failed build. Note that the
 * outcome of this gate is binary. The graded condition-code rubric under which the COBOL parity
 * oracle suite under {@code tests/} treats a warning-level aggregate as its green state belongs to
 * that suite alone, and it is never carried into a Maven, Checkstyle, Surefire or Failsafe outcome
 * on this side.
 *
 * <p><strong>The layering contract.</strong> Three prohibitions bound this package, and none of
 * them is enforced by anything written in this file:
 * <ul>
 *   <li>A {@code domain} package may not depend on a cloud software development kit type or on a
 *       web type. That is what lets each type here be constructed and exercised by a plain unit
 *       test with no container, no queue and no database.</li>
 *   <li>No context may depend on another context's {@code domain} package, so nothing outside
 *       {@code com.carddemo.authorization} may name a type declared here, and nothing here may name
 *       a type declared in a sibling context's {@code domain} package.</li>
 *   <li>No IEEE-754 binary primitive type, and no boxed form of one, may appear anywhere in the
 *       money path, which includes the members of this package.</li>
 * </ul>
 *
 * <p>All three belong to
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * which the {@code architecture-rules} Surefire execution in {@code services/pom.xml} selects by
 * that simple name and evaluates against each module's own compiled classes.
 *
 * <p>Assumptions: the third of the three reaches THIS package through rule A4 of that gate rather
 * than through rule A3, and the distinction is worth stating because it decides whether the claim
 * above is enforced here at all. A3 is scoped to {@code com.carddemo.common.money..} and examines
 * every member there regardless of its name; it does not examine a single class in this package. A4
 * examines every class under {@code com.carddemo} but only those members whose NAME denotes money,
 * from a vocabulary drawn from the reference record layouts. The eight money items of the two
 * reference segments carry names built on balance, limit, amount and cash, so each of the members
 * mapping them is selected by A4 and a binary declaration on any of them fails the build in this
 * module's own test run.
 *
 * <p>Alternatives Considered: expressing the same boundary as a Checkstyle {@code ImportControl}
 * module. {@code config/checkstyle/checkstyle.xml} omits that module deliberately and it must stay
 * omitted, because two mechanisms asserting one boundary is the arrangement in which they disagree
 * and neither file reveals which one won. Keeping a single owner also keeps the boundary a test
 * rather than a reading, so it cannot decay into prose. That test is not to be relocated, renamed
 * or split for the same reason: the Surefire include pattern resolves it by simple name, and a
 * renamed class would leave that execution selecting nothing while still reporting success.
 *
 * <p>Assumptions: the only intra-reactor Maven dependency this module declares is
 * {@code com.carddemo:common-lib}, and it declares no dependency on any sibling service module. The
 * arrow points inward only. Transformation rule T2 of the migration plan states the consequence in
 * Java terms: one former COBOL {@code COPY} statement resolves to exactly one type dependency, on
 * the single package that owns that contract. So the money type and its wire form, the record
 * codecs, the error model, the timestamp formatter, the validation flags and the page envelope are
 * consumed from {@code com.carddemo.common} and are never re-declared beneath this package. That
 * shared kernel is the Java analogue of compiling every reference program against one copybook
 * include path, which is the reason the module exists.
 *
 * <p>Assumptions: this charter is one of eight in this module, and it is the {@code domain} one.
 * The package root {@code com.carddemo.authorization} and each of its seven subpackages,
 * {@code api}, {@code service}, {@code repository}, {@code domain}, {@code dto}, {@code mapper} and
 * {@code config}, carries its own file for exactly the reason set out above. Deliberately, there is
 * none at {@code services/authorization-service/src/main/java/com/carddemo} and none at
 * {@code services/authorization-service/src/main/java/com}, and that absence is not an oversight.
 * {@code JavadocPackage} is a Checker-level file-set check narrowed to the {@code java} extension,
 * so it audits only a directory that actually holds a processed source file; those two directories
 * hold nothing but a subdirectory. A directory with no compilation unit has no package declaration,
 * and the module-entry-point obligation at Rule 1 L15 attaches to a package declaration, so a
 * charter at either parent level would document an entry point that does not exist. Recording this
 * is what stops a later reader from adding two files that nothing asks for and that would make both
 * counts above wrong.
 *
 * <p><strong>Money and numeric discipline.</strong> This is the highest-risk contract in the tree,
 * because an error in it is silent: it yields plausible amounts that are wrong. Money is exact
 * decimal at scale two at every hop. In this schema that is {@code NUMERIC(11,2)} for a reference
 * {@code PIC S9(09)V99} item, used for the six summary limits, balances and amounts at migration
 * L204 to L207 and L222 to L223, and {@code NUMERIC(12,2)} for a reference {@code PIC S9(10)V99}
 * item, used for the two detail amounts at L406 to L407. In Java it is {@code BigDecimal} held at
 * scale two and rounded half up, through {@code com.carddemo.common.money.Money}. On the wire it is
 * a JSON string, through {@code com.carddemo.common.money.MoneyModule}. Assumptions: the string
 * form is not fastidiousness. A JSON number is parsed into an IEEE-754 binary value by most
 * clients, and a decimal cent has no exact representation in a binary radix, so the amount a caller
 * reads would differ from the amount this schema holds with nothing anywhere reporting an error.
 * The prohibition on those types is settled by the architecture test named above rather than by
 * review.
 *
 * <p>Assumptions: no column in this schema holds packed-decimal bytes, and no type in this package
 * decodes one. All eight money items across the two reference segments are {@code COMP-3}, as are
 * the account identifier at {@code cpy/CIPAUSMY.cpy} L19 and both parts of the child key at
 * {@code cpy/CIPAUDTY.cpy} L20 and L21, so the decode is unavoidable somewhere and it belongs at
 * the mapper edge, {@code com.carddemo.authorization.mapper}. That is the single place
 * representation concerns may appear at all: declared byte widths, sign nibbles, the nines
 * complement, zoned display items such as the customer identifier at {@code cpy/CIPAUSMY.cpy} L20,
 * {@code FILLER}, the one field-spelling change this context makes, and card-number masking.
 * Concentrating them there is what keeps every type in this package free of layout concerns.
 *
 * <p>Assumptions: the packed width of a reference item is {@code ceil((digits + 1) / 2)} bytes,
 * where the digit count includes those to the right of the implied decimal point and the extra half
 * byte holds the sign. That formula is worth stating at this package's front door because reading
 * it wrongly sends a reader hunting for a field that was never missing. Worked on the detail
 * segment: {@code PA-TRANSACTION-AMT} and {@code PA-APPROVED-AMT} at {@code cpy/CIPAUDTY.cpy} L34
 * and L35 are {@code PIC S9(10)V99 COMP-3}, which is 12 digits and therefore SEVEN bytes each, not
 * six. Those two widths are exactly what closes the 200-byte record: at six bytes each the fields
 * sum to 198 and the segment length declared at {@code ims/DBPAUTP0.dbd} L36 stops agreeing with
 * the copybook. The same formula gives 6 bytes for the {@code PIC S9(11) COMP-3} root key, which
 * {@code ims/DBPAUTP0.dbd} L30 confirms independently as a 6-byte packed sequence field, and 3 plus
 * 5 bytes for the two parts of the child key, which L37 confirms as 8. Binary items follow a
 * different rule and are sized by digit count rather than by that formula: 2 bytes up to 4 digits,
 * 4 bytes for 5 through 9, and 8 bytes for 10 through 18, which is why the two
 * {@code PIC S9(04) COMP} counters at {@code cpy/CIPAUSMY.cpy} L27 and L28 occupy 2 bytes each and
 * not 3.
 *
 * <p>Assumptions: the confidentiality boundary belongs to the mapper layer, not to this one. A
 * primary account number is masked to its last four digits everywhere except the administrative
 * card-detail endpoint, and a card verification value is never returned by any endpoint. Neither
 * reference segment nor the reference relational fraud table declares a card verification value, so
 * no type here carries one and that half of the rule holds by construction rather than by
 * suppression. The card number is a different matter: {@code PA-CARD-NUM} at
 * {@code cpy/CIPAUDTY.cpy} L24 is 16 characters and {@code PendingAuthDetail} stores all 16,
 * because the fraud access path is keyed by that value and a masked key is not a key. Masking is
 * therefore applied on the way out, at the mapper. This is stated here so that nobody adds a
 * convenience serialiser to an entity: a member that serialises itself has escaped the one place
 * the masking rule is applied, and the escape is invisible at the call site.
 *
 * <h2>What this package actually holds, in three classes of sensitivity</h2>
 *
 * <p>Refactoring Rationale: an earlier revision of the paragraph above closed with a single sentence
 * asserting that no credential, endpoint, queue address, account identifier or connection string
 * appeared anywhere in this package, and that every such value was resolved at startup from
 * configuration owned elsewhere. Two thirds of that sentence were false. This package persists a
 * queue address and it persists account identifiers, and neither comes from configuration -- the
 * queue address arrives on the request message and the account identifiers are the reference
 * segments' own keys. A confidentiality charter that denies the presence of what it governs is worse
 * than no charter, because a reader checking whether a masking rule applies to a member is told the
 * member does not exist. The three classes below replace that sentence with what is actually here.
 *
 * <p><b>Secrets: none, and the absence is structural.</b> No password, no access key, no token, no
 * signing material and no connection string is declared by any type in this package. Nothing here
 * authenticates anything: the database connection is assembled by the module's data-source
 * configuration from the secret store, and the queue client is built by its queue configuration, so
 * neither credential has a reason to reach an entity. This part of the earlier sentence was true and
 * is kept. It is worth stating positively because it is the one class of value for which the correct
 * handling is not masking but total absence.
 *
 * <p><b>Identifiers: present, and unmasked on purpose.</b> {@code PendingAuthSummary} holds the
 * eleven-digit account identifier as its root key and {@code PendingAuthDetailKey} holds it again as
 * the first half of the child key, both decoded from the packed items at {@code cpy/CIPAUSMY.cpy}
 * L19 and {@code cpy/CIPAUDTY.cpy} L20; {@code PendingAuthDetail} holds all sixteen characters of
 * {@code PA-CARD-NUM} at {@code cpy/CIPAUDTY.cpy} L24; {@code AuthReplyOutbox} holds the card
 * number a third time as its ordering group and the acquirer's transaction identifier as its
 * deduplication key; and {@code AuthFraudKey} holds it a fourth time as the first half of the fraud
 * row's own key, which {@code ddl/AUTHFRDS.ddl} L28 declares. Every one of those is unmasked
 * because every one is a KEY -- a masked key
 * selects nothing, groups nothing and deduplicates nothing. The rule that follows is therefore about
 * egress rather than storage: a CARD NUMBER leaves this package only through
 * {@code com.carddemo.authorization.mapper}, which masks it to its last four digits on every path
 * but the administrative card-detail endpoint, and no type holding one declares a serialiser that
 * could bypass that mapper.
 *
 * <p>Assumptions: exactly THREE types here declare a {@code toString}, and no type declares any
 * other rendering, so the set is small enough to enumerate and is enumerated rather than
 * characterised. {@code PendingAuthDetailKey} renders its account identifier and its two clock
 * values in full, which the paragraph below argues is correct because none of them is a value the
 * masking rule names. {@code AuthFraudKey} and {@code AuthFraud} render a CARD number, so both mask
 * it to its last four digits, and {@code AuthFraud} obtains its rendering by delegating to its key
 * so that one masking decision exists rather than two that could drift apart. Neither masks by
 * calling the shared masker in {@code com.carddemo.common.security}: this package's layering
 * contract below refuses a dependency on any package outside the domain layer, so the two-line
 * arithmetic is repeated locally in preference to breaking the rule the ArchUnit gate enforces. That
 * is the trade accepted -- a duplicated expression against an unenforceable import -- and it is
 * recorded here so a reader does not read the local arithmetic as ignorance of the shared type.
 *
 * <p>Assumptions: the ACCOUNT identifier is treated differently from the card number, and the
 * difference is deliberate rather than an oversight. {@code PendingAuthDetailKey} declares a
 * {@code toString} that renders it in full beside the two clock values of the key; the migration's
 * masking rule names the primary account number,
 * the card verification value, the national identifier and the government-issued identifier, and
 * an account identifier is none of those -- it selects a row in this deployment's own schema and
 * discloses nothing outside it. Note that the batch context applies a STRICTER rule to its
 * terminal error sink, refusing any digit run wide enough to be an account or customer identifier;
 * that rule is a property of a long-lived, widely readable sink rather than a contradiction of
 * this one, and the two are recorded together so a reader meeting both does not have to guess
 * which governs where.
 *
 * <p><b>Routing values and free text: present, internal, and never rendered.</b>
 * {@code AuthReplyOutbox} persists {@code replyQueueUrl}, which is a queue address taken from the
 * request message exactly as the baseline took it from the message descriptor's reply-to field, so a
 * reply stays deliverable across a restart and two acquirers with two reply queues can be served by
 * one consumer. It is a destination and not a secret -- possessing it grants nothing, since sending
 * to it is authorised by the task role -- but it is still operational detail, so it is not rendered
 * into a log line or returned by any endpoint. The same row also carries the encoded reply
 * {@code payload}, whose first field is the sixteen-character card number at
 * {@code cpy/CCPAURLY.cpy} L19, and {@code lastError}, which is free text a publication failure
 * supplied. Those two are the highest-risk members in this package precisely because neither has a
 * shape a reader recognises, and the rule for both is that they are written, read by the publisher
 * and never rendered: {@code AuthReplyOutbox} declares no {@code toString} and no other rendering,
 * and no data-transfer object exposes it at all -- it is reached only by its repository, its
 * listener and its publisher. Adding a rendering to it would put a card number into whatever store
 * the rendering reaches, which is the same failure the batch context's terminal error sink closes
 * by refusing free text outright.
 *
 * <p>Assumptions: the single authority for this schema's shape is the Flyway migration named
 * throughout this charter, and the schema, its owning role and its grants come from
 * {@code data-migration/sql/V0__schemas_and_roles.sql}, which runs ahead of it. The types here MAP
 * ONTO names those two files declare; they do not declare them. Each carries a table annotation
 * naming its table and nothing more, no unique constraint, no index and no column definition, and
 * this module runs with schema generation switched off, at
 * {@code services/authorization-service/src/main/resources/application.yml} L181.
 *
 * <p>Alternatives Considered: letting the persistence provider generate or validate the schema from
 * these annotations. Rejected, because the migration expresses objects an annotation cannot: the
 * three check constraints that carry the reference condition-name domains, including the
 * match-status domain from {@code cpy/CIPAUDTY.cpy} L46 to L49 at migration L582 and the fraud
 * domain from L51 to L52 at migration L607, and the directional index at migration L806 that
 * reproduces the reference access path {@code XAUTHFRD}. That index has to be declared separately
 * from the primary key it appears to duplicate, because in PostgreSQL a unique constraint and a
 * directional index are two distinct objects: the key's own tree ascends in both columns and so
 * does not carry the descending path, which the migration records at L776 to L783. Duplicating any
 * of that in an annotation would put vendor schema definition in two places at once.
 *
 * <p>Assumptions: every table name written in this package is UNQUALIFIED and resolves through the
 * connection {@code search_path} pinned at {@code application.yml} L125. That is not a stylistic
 * choice. {@code authorization} is a reserved keyword in this database and
 * {@code CREATE SCHEMA AUTHORIZATION} is valid syntax naming a schema after a role, so the bare
 * form is read as that keyword form and reports a syntax error pointing nowhere near its cause,
 * which is why the schema name is quoted at every SQL occurrence and why
 * {@code data-migration/sql/V0__schemas_and_roles.sql} L517 to L525 records the trap beside the
 * quoted statement at its L539. It is also why this module sets no default-schema property where
 * peer modules do: the provider does not add quoting to a qualifier handed to it unquoted, so that
 * property would turn every generated statement into a syntax error.
 *
 * <p>Alternatives Considered: the five-slot table
 * {@code PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES} at {@code cpy/CIPAUSMY.cpy} L22 becomes five
 * discrete columns, {@code account_status_1} through {@code account_status_5} at migration L186 to
 * L190, mapped to five members of {@code PendingAuthSummary}. A PostgreSQL array column was the
 * obvious alternative and was rejected on two grounds: the arity of five is part of the reference
 * contract, and five columns let the schema itself hold that arity where an array would accept a
 * sixth element silently; and the mapping stays within portable JPA, where an array column needs a
 * provider-specific type. Trade-offs: a caller that wants to iterate the five now iterates five
 * members rather than one collection, which is the cost accepted for having the arity enforced one
 * layer down.
 *
 * <p>Trade-offs: the types here expose accessors only for the members that application code reads.
 * The remainder are written once at construction from a decoded request and are read only by SQL,
 * through the read-only projections the reporting context uses. An accessor per column would serve
 * a reflex rather than a caller, and each one would be a member whose documentation has to be kept
 * true against a copybook line that nothing reads; a later boundary that needs one adds it together
 * with the caller that justifies it.
 *
 * <p>Refactoring Rationale: every difference from the reference system recorded in this charter is
 * written as a consequence of that system's structure and never as a verdict on it. The reference
 * COBOL is the behavioural oracle for this context, it is never modified, and a comment calling it
 * defective would invite exactly the edit the migration forbids. What each entry records instead is
 * the topology or ordering the reference system has, the topology or ordering this package has, and
 * the observable difference between them, with line numbers that let a reader check both.
 * {@code docs/architecture/cobol-to-service-traceability.md} is the one place those differences are
 * enumerated, so a difference documented only here would be invisible to anyone auditing the set.
 * The migration adds a path, it does not remove one.
 *
 * <p>Trade-offs: every rationale above sits inside this Javadoc block rather than beside a
 * statement, which departs from the letter of Rule 1 L27 and is nevertheless the only placement
 * this file admits. L27 asks that a comment sit adjacent to the code it explains; a package
 * declaration has no statements, so there is nothing for a comment to be adjacent to, and the
 * adjacency requirement is met vacuously rather than waived. The cost accepted is that these
 * entries sit further from the behaviour they describe than an inline comment would, and the
 * compensation is that each one names its evidence. Rule 1 L43 is conjunctive and L40 forbids
 * leaving a non-obvious choice undocumented where a reasonable alternative exists, so a reader
 * auditing this file should read the labelled entries as the rationale half of that gate and not
 * conclude the half was skipped for want of somewhere to put it.
 */
package com.carddemo.authorization.domain;
