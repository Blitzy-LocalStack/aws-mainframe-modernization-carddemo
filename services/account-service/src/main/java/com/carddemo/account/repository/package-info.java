/**
 * Spring Data JPA data-access layer of the account bounded context, holding one repository interface
 * per record the context owns.
 *
 * <p>Every path by which the reference system reaches an account, a customer or a card
 * cross-reference arrives here as a typed method on an interface. The four rulings below are stated once
 * in this descriptor so that a member interface can cite them rather than re-argue them.</p>
 *
 * <p>Refactoring Rationale: that sentence used to end "which is why two of the three interfaces declare
 * no method of their own at all beyond what they inherit", and no interface here is in that state:
 * {@code AccountRepository} and {@code CustomerRepository} each declare their own derived keyset reads,
 * and {@code CardXrefRepository} declares five reads and inherits nothing at all. The claim is withdrawn
 * rather than recounted, because a count of methods is not what the rulings are for -- they exist so that
 * each interface can cite one argument instead of restating it, which holds however many members it
 * declares.</p>
 *
 * <h2>The three interfaces</h2>
 *
 * <p>Assumptions: beside the three interfaces this package declares one record, {@code AccountScreenRow},
 * and it is not a fourth port. It is the shape ONE query returns -- the account screen's three-table
 * composition -- and it exists because a single statement is what gives that screen one snapshot. It is
 * declared here rather than in {@code com.carddemo.account.dto} because it carries ENTITIES and is never
 * serialised to a caller; a transfer record carrying entities would put persistence types on the published
 * boundary, which is exactly what the mapper seam exists to prevent.</p>
 *
 * <p>⚠️ Refactoring Rationale: this package also declares ONE CLASS, {@code InquiryReplyLedger}, and it is
 * the single documented exception to "one repository interface per record". This descriptor previously
 * stated that no other kind of member is declared here, and that sentence is withdrawn rather than
 * stretched. The exception exists because its central statement cannot be expressed as a Spring Data
 * method: it must insert a row if and only if no row holds that key and report which of the two happened,
 * in ONE round trip, which is {@code INSERT ... ON CONFLICT DO NOTHING} and has no JPQL form. Expressing
 * the same intent as a read followed by a conditional insert would leave the decision to the gap between
 * two statements, which is exactly where two concurrent deliveries of one message would both decide they
 * were first. Alternatives Considered: mapping {@code account.inquiry_reply_ledger} as a fourth entity so
 * a fourth interface could be declared over it. Rejected because a mapped entity would let any member of
 * this module read or write the ledger through the persistence context -- including flushing a stale copy
 * over a row a concurrent delivery had already advanced -- where three named statements over one table
 * offer nothing else. Assumptions: the precedent does not generalise. It is admissible here because the
 * statement is not expressible otherwise, not because a class is a convenient shape.</p>
 *
 * <p>Assumptions: what that ledger is FOR belongs to the exchange rather than to this package, and the
 * argument is recorded in full on the class and on
 * {@code services/account-service/src/main/resources/db/migration/V2__account_inquiry_reply_ledger.sql}.
 * In outline: the asynchronous inquiry consumer sends its reply and then returns, and the queue
 * acknowledges the request only on that return, so a task killed between the two leaves the request
 * visible again and the next delivery would send a second reply bearing the same correlation identifier as
 * the first. The ledger records the answer under the BROKER's own identifier for the delivery before
 * it is sent -- an identity that is stable across every redelivery of one message and unique per
 * accepted send -- so a redelivery can tell that it was already produced. Assumptions: the key is
 * deliberately not either identity the PRODUCER supplies, because neither is authenticated or
 * constrained and a producer reusing one correlation identifier across several questions would have
 * its second, genuine inquiry suppressed as a redelivery of the first. Both are retained below the
 * broker identifier as legacy fallbacks, for a request that never passed the broker.</p>
 *
 * <ul>
 *   <li>{@code AccountRepository}, over {@code com.carddemo.account.domain.Account}. The entity
 *       derives from {@code ACCOUNT-RECORD}, declared at {@code app/cpy/CVACT01Y.cpy} L4, whose L2
 *       header states a record length of 300 bytes. Its key is {@code ACCT-ID}, {@code PIC 9(11)} at
 *       L5, carried as {@code accounts.account_id} of type {@code BIGINT}.</li>
 *   <li>{@code CustomerRepository}, over {@code com.carddemo.account.domain.Customer}. The entity
 *       derives from {@code CUSTOMER-RECORD}, declared at {@code app/cpy/CVCUS01Y.cpy} L4, whose L2
 *       header states 500 bytes. Its key is {@code CUST-ID}, {@code PIC 9(09)} at L5, carried as
 *       {@code customers.customer_id} of type {@code BIGINT}.</li>
 *   <li>{@code CardXrefRepository}, over {@code com.carddemo.account.domain.CardXref}. The entity
 *       derives from {@code CARD-XREF-RECORD}, declared at {@code app/cpy/CVACT03Y.cpy} L4, whose L2
 *       header states 50 bytes. Its key is {@code XREF-CARD-NUM}, {@code PIC X(16)} at L5, carried as
 *       {@code card_xref.card_num} of type {@code CHAR(16)}.</li>
 * </ul>
 *
 * <p>Assumptions: on all three records the key is the record's LEADING field, and each batch program's
 * file description says so independently of the copybook. {@code app/cbl/CBACT01C.cbl} splits the
 * account record into {@code FD-ACCT-ID PIC 9(11)} at L54 and {@code FD-ACCT-DATA PIC X(289)} at L55,
 * which sum to the declared 300; {@code app/cbl/CBCUS01C.cbl} splits it into {@code PIC 9(09)} at L39
 * and {@code PIC X(491)} at L40, summing to 500; {@code app/cbl/CBACT03C.cbl} splits it into
 * {@code PIC X(16)} at L39 and {@code PIC X(34)} at L40, summing to 50. Two independent statements of
 * the same key position are what let a reader trust the key mapping without re-deriving it.</p>
 *
 * <h2>Where the access paths come from</h2>
 *
 * <p>This package replaces the file verbs of six programs, and the two kinds of program contribute
 * two different kinds of access path. Conflating them is the mistake this section exists to prevent.</p>
 *
 * <p>The KEYED reads come from the online programs. {@code app/cbl/COACTVWC.cbl} reads the account at
 * L776 through L781, entering {@code DATASET (LIT-ACCTFILENAME)} on a record identification field and
 * receiving the record {@code INTO (ACCOUNT-RECORD)}; it reads the customer through the same shape at
 * L826 through L831 against {@code DATASET (LIT-CUSTFILENAME)}; and it reads the cross-reference by
 * account at L727 through L732. Those three reads are three paragraphs of one chain --
 * {@code 9200-GETCARDXREF-BYACCT.} at L723, {@code 9300-GETACCTDATA-BYACCT.} at L774 and
 * {@code 9400-GETCUSTDATA-BYCUST.} at L825 -- driven from {@code 9000-READ-ACCT.} at L687 by the
 * {@code PERFORM} statements at L701 through L711. {@code app/cbl/COACTUPC.cbl} repeats the same chain
 * at L3650, L3701 and L3752.</p>
 *
 * <p>The SEQUENTIAL scans come from the batch programs, and they are not keyed reads in disguise. All
 * three declare {@code ACCESS MODE IS SEQUENTIAL} at L31 -- {@code app/cbl/CBACT01C.cbl},
 * {@code app/cbl/CBCUS01C.cbl} and {@code app/cbl/CBACT03C.cbl} alike -- and each drives an open,
 * get-next, close triad rather than a random read: {@code 0000-ACCTFILE-OPEN.} at L317,
 * {@code 1000-ACCTFILE-GET-NEXT.} at L165 whose body at L166 is
 * {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD.}, and {@code 9000-ACCTFILE-CLOSE.} at L388, with the
 * other two programs placing the same triad at L118, L92 and L136.</p>
 *
 * <h2>Ruling one: keyed and ordered scans, and no positional paging</h2>
 *
 * <p>Alternatives Considered: browse results are reached by key and in key order. The rejected
 * alternative is positional paging -- asking the database to skip a counted number of rows and return
 * the next batch -- and it is rejected on observable behaviour rather than on taste. Under concurrent
 * inserts a positional query skips rows and repeats rows, because the count it skips is evaluated
 * against whatever the table holds at the moment of the second query; a scan that resumes from the last
 * key it actually returned cannot do either, because the key it resumes from names a row rather than a
 * distance. The reference never counted rows either: {@code app/cbl/COCRDLIC.cbl} carries a key pair
 * across the terminal turn at L230 through L235, never an ordinal into a result set.</p>
 *
 * <p>The reference browse state is ALREADY a key cursor, which is what makes the choice above a
 * one-to-one carry-over rather than an approximation of something looser.
 * {@code app/cbl/COCRDLIC.cbl} declares {@code 01 WS-THIS-PROGCOMMAREA.} at L229 and persists across
 * the terminal turn, at L230 through L244, a trailing key pair at L230 through L232, a leading key pair
 * at L233 through L235, a screen ordinal at L237 and L238, a last-screen-displayed flag at L239
 * through L241 and a further-rows-exist indicator at L242 through L244, alongside the row counter at
 * L145. The reference sets that further-rows indicator by discovering one row more than the screen can
 * hold, which is exactly how the migrated form establishes the same answer.</p>
 *
 * <p>The shared envelope those ordered results feed is
 * {@code com.carddemo.common.web.PageResponse} in {@code common-lib}, whose four components are
 * {@code items}, {@code firstKey}, {@code lastKey} and {@code hasNext}. Reading
 * forward asks for keys strictly greater than {@code lastKey} in ascending order and reading backward
 * for keys strictly less than {@code firstKey} in descending order, which is what makes the backward
 * direction the exact counterpart of the reference read-previous rather than a re-scan from the top.
 * Assumptions: backward availability is NOT a component of the envelope; it is the presence of
 * {@code firstKey}, which is the position a backward request is issued from, and the envelope
 * therefore states forward availability only. What the reference answers with a separate flag it
 * answers from the screen ordinal it declares at L237 and L238 rather than from any read of the
 * file -- it refuses the backward step on the opening page at L902 and L903 on exactly that
 * condition -- and that ordinal's migrated home is the browser client's own navigation state, so the
 * client refuses the backward step without asking a repository here anything. The envelope is
 * assembled ABOVE this package: a repository here yields entities and ordered collections of entities,
 * and the service layer seals the boundary keys and settles the forward direction question. That
 * division is
 * recorded on the by-account finder's own descriptor, and it is what lets the same interface serve both
 * a screen that browses a screenful and a batch caller that wants every row for one account without
 * either of them asking for the other's shape.</p>
 *
 * <h2>Ruling two: optimistic versioning, and no pessimistic lock</h2>
 *
 * <p>Refactoring Rationale: {@code Account} and {@code Customer} each carry a JPA version column and
 * {@code CardXref} deliberately carries none, the cross-reference having no update path. The version
 * column replaces a comparison the reference performs by hand.
 * {@code app/cbl/COACTUPC.cbl} enters {@code 9600-WRITE-PROCESSING.} at L3888, under the comment at
 * L3890 announcing a read for update, and issues at L3894 through L3900 a read naming
 * {@code FILE (LIT-ACCTFILENAME)} with the bare {@code UPDATE} option at L3896. It then asks, under the
 * comment at L3945 wondering aloud whether anyone changed the record while the program was away,
 * whether the row still matches the snapshot it took: the {@code PERFORM} at L3947 and L3948 enters
 * {@code 9700-CHECK-CHANGE-IN-REC.} at L4109, whose first comparison is at L4115 and whose body runs to
 * L4192 before the exit paragraph at L4193, raising the changed condition at L4189. The snapshot it
 * compares against is {@code 05 ACUP-OLD-DETAILS.} at L669, which ends immediately before
 * {@code 05 ACUP-NEW-DETAILS.} at L757 and therefore spans L669 through L756, and the paragraph
 * enclosing the whole write reaches its own exit at L4105.</p>
 *
 * <p>Nothing is lost by not holding a lock, and the reason is load-bearing. The reference
 * read-for-update lock was never held across client think-time -- that is PRECISELY why the snapshot at
 * L669 has to exist at all. So no explicit locking hint, no select-for-update and no pessimistic mode
 * is introduced here: any of them would hold a database row across a request boundary the reference
 * never held one across, which is a stronger claim than the source makes and a new way for one slow
 * user to stall another.</p>
 *
 * <h2>Ruling three: the alternate index becomes a real secondary index</h2>
 *
 * <p>Assumptions: {@code CardXrefRepository} carries a by-account path, and that path is backed by the
 * non-unique index {@code idx_card_xref_account_id} on {@code card_xref.account_id} rather than left to
 * a scan behind a method that reads like a lookup. What is assumed is that the reference genuinely
 * reached this record two ways, which {@code app/csd/CARDDEMO.CSD} asserts in words at L63 and L64. An
 * alternate index there is an access path, not decoration, and five independent witnesses say so:</p>
 *
 * <ol>
 *   <li>{@code app/csd/CARDDEMO.CSD} L63 defines a file resource named {@code CXACAIX}, and L64
 *       describes it in words as an alternate index to {@code CCXREF} by account key.</li>
 *   <li>Its data set name at L65 ends {@code .AIX.PATH} where the base cluster's, at L39 under the
 *       resource defined at L37 and described at L38, ends {@code .KSDS} -- the same {@code CARDXREF}
 *       base name reached two ways. The resource name of that base cluster is {@code CCXREF}, not
 *       {@code CARDXREF}.</li>
 *   <li>{@code app/cbl/COACTVWC.cbl} declares the literal {@code 'CXACAIX '} at L192 and L193 and
 *       consumes it in the read at L727 through L732.</li>
 *   <li>The same program says so in a comment at L725, naming the access as being by way of an
 *       alternate index on the account identifier.</li>
 *   <li>{@code app/cbl/CBACT03C.cbl} declares {@code RECORD KEY IS FD-XREF-CARD-NUM} at L32 beside its
 *       {@code ACCESS MODE IS SEQUENTIAL} at L31. The base cluster is therefore keyed by CARD NUMBER
 *       and not by account, which is exactly why a separate index has to exist for the by-account
 *       path to be a path at all.</li>
 * </ol>
 *
 * <p>This package owns {@code idx_card_xref_account_id} and nothing else in that family.
 * The four resources it replaces are those at {@code app/csd/CARDDEMO.CSD} L1, L37, L50 and L63; the
 * resources at L13 and L25 belong to the card context, L76 to the transaction context and L88 to the
 * authentication context, and the account-keyed index over the card master named by the literal at L190
 * and L191 of {@code app/cbl/COACTVWC.cbl} is the card context's, not this one's.</p>
 *
 * <h2>Ruling four: no table name is qualified here</h2>
 *
 * <p>Assumptions: no member of this package qualifies a table name. Members that declare query text DO
 * exist -- {@code CardXrefRepository} carries three JPQL reads and {@code InquiryReplyLedger} declares
 * native statements -- and every one of them names an entity or an unqualified relation and never a
 * schema. Whether derived from a method name or written out, each resolves against whatever search path
 * the connection already carries.
 * Refactoring Rationale: this ruling used to add "and no member declares query text in which it could",
 * asserting that every read is inherited or derived. That was false when written and the ruling never
 * needed it: what matters is that no query text here carries a schema prefix, which a reader can check by
 * reading the query text, whereas "there is no query text" invited a reader to stop looking.
 * {@code com.carddemo.account.config.DataSourceConfig} pins that search path on every pooled
 * connection and verifies the pin, so it is the single owner of the question; restating its answer here
 * would give one setting two definitions that could drift apart, and the drift would surface as a
 * relation-not-found failure at run time rather than as a compilation error.</p>
 *
 * <h2>Boundaries this package does not cross</h2>
 *
 * <ul>
 *   <li>No transport type. A repository here never sees a data-transfer record. Its own descriptor
 *       establishes the reciprocal half -- no transport record derives from an entity, because
 *       {@code com.carddemo.account.mapper} is the sole seam at which the two vocabularies meet -- and
 *       this is the mirror of it. Repositories yield entities; the mapper re-shapes them.</li>
 *   <li>No local error, paging, validation or message type is declared here.
 *       {@code com.carddemo.common.error.GlobalExceptionHandler} already answers a version conflict and
 *       a restrict-on-delete violation with HTTP 409, and it recognises the version conflict by
 *       fully-qualified class NAME rather than by importing the persistence abstraction, because
 *       {@code common-lib} carries no persistence dependency to import. The sentence a client reads on
 *       that conflict is chosen in the service layer, not here.</li>
 *   <li>No dependency on a sibling service. The only intra-reactor dependency permitted is
 *       {@code common-lib}, and no member names another context's package in any form.</li>
 *   <li>No association to navigate. The three entities declare no association and the schema declares
 *       no foreign key between the three tables, so {@code card_xref} is the only path from a card to an
 *       account. Refactoring Rationale: this bullet also read "so every cross-table reach is a separate
 *       scalar-keyed query", and that clause is withdrawn. One member now spans all three tables in a
 *       single statement -- the account screen's composition -- because three statements under this
 *       datasource's read-committed isolation each take their own snapshot and could compose an account
 *       from before a concurrent update with a customer from after it. The join is written as an explicit
 *       predicate rather than as a navigation, so the absence of an association is unchanged: nothing here
 *       asserts a referential guarantee the database does not enforce.</li>
 *   <li>No accessor generator, no mapping generator, no resilience library, no circuit breaker, no
 *       cache tier, no message broker, no read replica, no second data source, no batch framework, no
 *       outbox, no distributed commit and no compensating-transaction orchestration. On the outbox in
 *       particular, the inquiry flow this context absorbs already reads and replies under syncpoint at
 *       L347 and L475 of {@code app/app-vsam-mq/cbl/COACCT01.cbl}, so there is no publication window
 *       outside a commit for an outbox to close.</li>
 *   <li>No credential, no endpoint and no sample identifier. Primary account numbers are masked to
 *       their last four digits by the mapper and never here, verification values are returned by no
 *       path at all, and this descriptor uses no real or invented account number, card number or
 *       personal identifier as illustration.</li>
 * </ul>
 *
 * <h2>Lockstep with the schema, and the absence of a parity oracle</h2>
 *
 * <p>Assumptions: the names above are a two-sided agreement with
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql}, which owns the
 * tables, the primary-key constraints and {@code idx_card_xref_account_id}. Neither side may rename
 * {@code accounts.account_id}, {@code customers.customer_id}, {@code card_xref.card_num} or
 * {@code card_xref.account_id} alone. That migration creates no schema, because schema, role and grant
 * creation belong to {@code data-migration/sql/V0__schemas_and_roles.sql} instead.</p>
 *
 * <p>None of the six programs this package replaces has an executable parity oracle, and
 * this is stated rather than glossed because a reader may reasonably assume one exists. {@code L83}
 * through {@code L85} of {@code tests/README.md} record that the online programs cannot be run end to
 * end without a CICS runtime, which the runner does not have, so only their extractable
 * field-validation logic is unit-tested; and the business rules that suite asserts verbatim, from
 * {@code L553} onward, govern the posting, interest and category-balance programs of other contexts and
 * name none of this context's six. So the behaviour of this package is established by its own tests
 * against the cited copybook and program lines, and no golden-master comparison is claimed for it. The
 * graded condition-code rubric under {@code tests/**}, in which a soft code still reads as success,
 * belongs to that COBOL suite alone; the gate over this package is pass or fail.</p>
 *
 * <h2>Isolation</h2>
 *
 * <p>Trade-offs: this is a cross-reference and not a fresh claim, because
 * {@code com.carddemo.account.config.DataSourceConfig} settles isolation. All four resources this
 * package replaces are defined to read without regard to uncommitted change -- the
 * {@code READINTEG(UNCOMMITTED)} operand appears at {@code app/csd/CARDDEMO.CSD} L3, L40, L53 and L66
 * -- alongside {@code UPDATEMODEL(LOCKING) LOAD(NO) RECORDFORMAT(V) ADD(YES)} at L6, L43, L56 and L69
 * and {@code JNLSYNCWRITE(YES) RECOVERY(NONE) FWDRECOVLOG(NO)} at L9, L46, L59 and L72. The default
 * isolation this package runs under is therefore the stronger of the two, and the compromise accepted
 * is the direction nobody minds: a read here may refuse to see something the reference would have
 * shown, and never the reverse.</p>
 *
 * <h2>Why this descriptor exists, and why it carries no tag section</h2>
 *
 * <p>Assumptions: this file exists for two reasons that happen to coincide, and it carries no
 * parameter, return or exception section for a third. The project Explainability rule requires a
 * docstring on every module entry point at its L15 and hardens that into a conjunctive review gate at
 * its L43, and in Java a package declaration is that entry point, so {@code package-info.java} is the
 * only compilation unit able to carry a package-level docstring. Mechanically and separately,
 * {@code JavadocPackage} is declared at Checker level in {@code config/checkstyle/checkstyle.xml},
 * outside the tree walker, so it is a file-set check demanding the FILE; {@code MissingJavadocPackage}
 * is declared inside the tree walker and demands that the file CARRY Javadoc. An empty descriptor, or
 * one holding only a plain block comment, satisfies the first and fails the second. As for the missing
 * sections: a package declaration accepts no argument and yields no value, so the rule's parameter and
 * return elements have nothing to describe here, and its exception element is conditional on being
 * applicable, which a declaration that cannot be invoked does not meet. Inventing a tag to look
 * compliant would be the defect the rule's forbidden-pattern list is aimed at. Where this descriptor
 * and {@code docs/CODE_DOCUMENTATION_STANDARD.md} appear to differ, the rule governs first, that
 * Checkstyle configuration second and the prose standard third.</p>
 */
package com.carddemo.account.repository;
