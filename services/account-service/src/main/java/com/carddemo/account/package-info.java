/**
 * Stateless account, customer and card-cross-reference bounded context, owning the PostgreSQL schema
 * {@code account} and its three tables, carrying JPA optimistic versioning and no transactional outbox.
 *
 * <h2>Charter</h2>
 *
 * <p>Three clauses define this context, and each is a constraint rather than a description.</p>
 *
 * <p>It is <strong>stateless</strong>. No request carries continuation state, no sticky session is
 * required and no server-side session store exists, for the reason set out under pseudo-conversational
 * state below. That property is what makes horizontally scaled container tasks behind a load balancer a
 * viable target at all.</p>
 *
 * <p>It <strong>owns the schema {@code account} and exactly three tables</strong> -- {@code accounts},
 * {@code customers} and {@code card_xref} -- and no fourth table, no view and no seeded reference row. A
 * reader looking here for the transaction-type or state and ZIP lookup rows will not find them; those are
 * owned elsewhere, as recorded under the lookup allow-lists below.</p>
 *
 * <p>It <strong>carries JPA optimistic versioning</strong> on {@code accounts} and {@code customers}, and
 * it <strong>deliberately carries no transactional outbox</strong>. Both facts are stated in the charter
 * rather than only in the code that implements them, because both differ from a sibling context and a
 * reader who assumes uniformity across the eight contexts will get exactly one of them backwards. Each
 * has its own section below, with the baseline evidence that settles it.</p>
 *
 * <h2>Why this descriptor exists at all</h2>
 *
 * <p>Alternatives Considered: leaving this directory without a package descriptor, or supplying one that
 * carries only the {@code package} statement. Both were evaluated against the documentation gate and both
 * fail it, for two different reasons that are easy to conflate. {@code JavadocPackage} is declared at
 * <em>Checker</em> level in {@code config/checkstyle/checkstyle.xml} L245, outside the
 * {@code TreeWalker} that opens at L259, so it is a file-set check: it demands that the
 * {@code package-info.java} <em>file exist</em> in any directory holding a processed compilation unit,
 * and the sibling {@code AccountApplication} in this very directory is what activates it here.
 * {@code MissingJavadocPackage} is declared at L378 <em>inside</em> that {@code TreeWalker}, and it
 * demands that the file <em>carry</em> Javadoc. A descriptor holding nothing but a bare {@code package}
 * statement therefore satisfies the first check and fails the second, which is why the omission and the
 * empty-file shortcut were both rejected in favour of a documented descriptor.</p>
 *
 * <p>The gate is bound to the Maven {@code validate} phase in {@code services/pom.xml} L816 to L817
 * under execution id {@code checkstyle-documentation-gate}, with {@code failOnViolation} true at L906
 * and {@code violationSeverity} warning at L907, so either failure stops every local build before
 * compilation rather than only in continuous integration. Note also what is absent: not one of the three
 * filters that could suppress a finding from inside the source -- the warning-annotation filter, the
 * comment filter and the nearby-comment filter -- is declared anywhere in that configuration, so there
 * is no in-code bypass at all and no route to silence a finding other than writing the
 * documentation.</p>
 *
 * <p>Assumptions: a Java {@code package} declaration is the module entry point that the project
 * Explainability rule requires a docstring on at its L15, and {@code package-info.java} is the only
 * place that docstring can live. That rule's parameter, return value and exception elements at L19 to
 * L21 describe callable code and have no counterpart on a package declaration, so they are omitted
 * deliberately rather than written out empty; L21 is itself qualified "where applicable". Fabricating a
 * parameter or return-value block tag here to look compliant would add unverifiable content and would
 * offend the specificity requirement at L41. The formatting checks that would demand authorship or
 * version tags -- {@code JavadocStyle}, {@code WriteTag} and {@code JavadocParagraph} -- are likewise
 * absent from the configuration, so no such tag appears here either.</p>
 *
 * <h2>The six programs this context replaces</h2>
 *
 * <p>The behaviour gathered under this root is transcribed from six baseline programs. Line counts are
 * given because they are the honest measure of how much logic each one carries, and
 * {@code app/cbl/COACTUPC.cbl} at 4236 lines is the largest online program in the baseline.</p>
 *
 * <ul>
 *   <li>{@code app/cbl/COACTVWC.cbl}, 941 lines, CICS transaction {@code CAVW} -- account view,
 *       composing customer and cross-reference data.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl}, 4236 lines, CICS transaction {@code CAUP} -- account update.</li>
 *   <li>{@code app/cbl/CBACT01C.cbl}, 430 lines, batch -- account master sequential reader.</li>
 *   <li>{@code app/cbl/CBACT03C.cbl}, 178 lines, batch -- card cross-reference sequential reader.</li>
 *   <li>{@code app/cbl/CBCUS01C.cbl}, 178 lines, batch -- customer master sequential reader.</li>
 *   <li>{@code app/app-vsam-mq/cbl/COACCT01.cbl}, 620 lines, request and reply over a queue --
 *       account inquiry.</li>
 * </ul>
 *
 * <p>The two online transactions are bound to their programs in {@code app/csd/CARDDEMO.CSD}, a
 * 505-line resource definition. {@code CAUP} is defined at L306 with a description at L307 and
 * {@code PROGRAM(COACTUPC) TWASIZE(0)} at L308; {@code CAVW} is defined at L317 with
 * {@code PROGRAM(COACTVWC) TWASIZE(0)} at L318 and carries no description line of its own. Both declare
 * {@code PRIORITY(1) TRANCLASS(DFHTCL00)} at L311 and L321 and {@code ACTION(BACKOUT) WAIT(YES)} at
 * L313 and L323. The programs themselves are defined at L173 and L181.</p>
 *
 * <p>{@code TWASIZE(0)} on both transactions is load bearing rather than incidental: there is no
 * transaction work area at all, so every scrap of continuity between screen turns lived in the
 * communication area. That is precisely why the communication area can be decomposed into request-scoped
 * and client-side mechanisms without stranding state that had nowhere else to live.</p>
 *
 * <h2>The three records this context inherits</h2>
 *
 * <p>The copybooks are normative. A field's {@code PICTURE} clause determines its PostgreSQL type, its
 * Java type and its fixed-width byte offset, and the derivation is applied by rule rather than by
 * judgement: {@code PIC 9(n)} used as a key becomes {@code BIGINT} and {@code Long}; {@code PIC X(n)}
 * used as a key or a fixed code becomes {@code CHAR(n)} and {@code String}; {@code PIC X(n)} used
 * descriptively becomes {@code VARCHAR(n)} and {@code String}, because trailing blanks there are padding
 * rather than data; {@code PIC S9(10)V99} becomes {@code NUMERIC(12,2)} and {@code BigDecimal};
 * {@code PIC 9(03)} holding a credit score becomes {@code SMALLINT} and {@code Short}; and
 * {@code FILLER} is dropped, with the drop recorded for each record so that a later reader can tell a
 * deliberate omission from an oversight.</p>
 *
 * <p>{@code app/cpy/CVACT01Y.cpy} declares {@code 01 ACCOUNT-RECORD} at L4 across 300 bytes. Five of its
 * fields are money, at L7 {@code ACCT-CURR-BAL}, L8 {@code ACCT-CREDIT-LIMIT}, L9
 * {@code ACCT-CASH-CREDIT-LIMIT}, L13 {@code ACCT-CURR-CYC-CREDIT} and L14
 * {@code ACCT-CURR-CYC-DEBIT}, each declared {@code PIC S9(10)V99}. L17 is {@code FILLER PIC X(178)},
 * dropped.</p>
 *
 * <p>Assumptions: this record holds exactly three date fields, at L10 {@code ACCT-OPEN-DATE}, L11
 * {@code ACCT-EXPIRAION-DATE} and L12 {@code ACCT-REISSUE-DATE}, each a {@code PIC X(10)} carrying
 * {@code 'YYYY-MM-DD'}. Two further fields share that same {@code PIC X(10)} width and are emphatically
 * not dates: L15 {@code ACCT-ADDR-ZIP} and L16 {@code ACCT-GROUP-ID}. The properties derived from those
 * two stay character typed and must never be narrowed to a date, because a postal code and a group
 * identifier put through a date parser are lost silently rather than loudly. What makes the three
 * genuine dates safe to narrow at all is the {@code 'YYYY-MM-DD'} ordering itself: it is already
 * lexically monotonic, so a character comparison over the source values and a date comparison over the
 * target values order identically, and no browse or range boundary shifts under the conversion.</p>
 *
 * <p>{@code app/cpy/CVCUS01Y.cpy} declares {@code 01 CUSTOMER-RECORD} at L4 across 500 bytes, with L17
 * {@code CUST-SSN PIC 9(09)}, L18 {@code CUST-GOVT-ISSUED-ID PIC X(20)}, L22
 * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} and L23 {@code FILLER PIC X(168)}, dropped. Its sole date is
 * L19 {@code CUST-DOB-YYYY-MM-DD}; L14 {@code CUST-ADDR-ZIP} and L20 {@code CUST-EFT-ACCOUNT-ID} are
 * again {@code PIC X(10)} and again not dates. There is no city field in this record at all: the screen
 * city input maps onto {@code CUST-ADDR-LINE-3}, the two agreeing exactly at {@code X(50)}, while the
 * screen postal input is narrower than the record field it feeds. Neither mapping is one to one, and
 * each needs its justification written beside it at the point of use.</p>
 *
 * <p>{@code app/cpy/CVACT03Y.cpy} declares {@code 01 CARD-XREF-RECORD} at L4 across 50 bytes -- L5
 * {@code XREF-CARD-NUM PIC X(16)}, L6 {@code XREF-CUST-ID PIC 9(09)}, L7 {@code XREF-ACCT-ID PIC 9(11)}
 * and L8 {@code FILLER PIC X(14)}, dropped. The same 50 bytes are corroborated independently by
 * {@code app/cbl/CBACT03C.cbl} L38 to L40, which describes the record as a 16-byte key plus 34 bytes of
 * data.</p>
 *
 * <p>Refactoring Rationale: exactly one field in this context is renamed. {@code ACCT-EXPIRAION-DATE}
 * at {@code CVACT01Y.cpy} L11 is misspelled in the baseline, and the target reads
 * {@code Account.expirationDate} over {@code accounts.expiration_date}. The misspelling is not a
 * one-off typing slip confined to the copybook: it recurs at {@code app/cbl/CBACT01C.cbl} L64, L207 and
 * L222, which are its only three occurrences in that program, and it is carried into the update
 * program's own before-image declaration at {@code app/cbl/COACTUPC.cbl} L690. Propagating a known
 * misspelling into a column name, an accessor and a serialised field name would multiply it across the
 * whole target surface, so it is carried across once as a rename and registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. No other field in this context is renamed;
 * the similarly misspelled card expiry and merchant category fields belong to other contexts and are not
 * touched from here.</p>
 *
 * <h2>The three owned tables</h2>
 *
 * <p>Schema {@code account} holds {@code accounts} from the 300-byte record, {@code customers} from the
 * 500-byte record and {@code card_xref} from the 50-byte record. The schema, the service roles and their
 * grants are created by {@code data-migration/sql/V0__schemas_and_roles.sql}, so the migration this
 * module ships at {@code src/main/resources/db/migration/V1__account.sql} creates tables within a schema
 * that already exists and never creates the schema itself. The entities under {@code .domain} and that
 * migration have to agree field for field; the agreement is an obligation on both sides rather than a
 * property either one can assert alone, and neither of those two files is authored from this
 * descriptor.</p>
 *
 * <p>Assumptions: the baseline reaches the cross-reference by two different keys, and the second of them
 * has to survive as a real secondary index -- {@code idx_card_xref_account_id} -- rather than as a
 * comment. Four independent sources establish it. The resource definition declares
 * {@code FILE(CXACAIX)} at {@code app/csd/CARDDEMO.CSD} L63 and describes it at L64 as the alternate
 * index to {@code CCXREF} by account key. Its data set name at L65 ends {@code .AIX.PATH} where the
 * base cluster {@code CCXREF}, defined at L37 and described at L38, ends {@code .KSDS} at L39 over the
 * same underlying name, so the two are two access paths onto one store rather than two stores. The view
 * program holds the alternate index name in a literal at {@code app/cbl/COACTVWC.cbl} L192 to L193 and
 * reads through it at L727 to L732, supplying an account identifier as the record identification field.
 * And {@code app/cbl/CBACT03C.cbl} L32 declares the base cluster's record key to be the card number,
 * not the account, which is the reason a separate access path had to exist in the first place. Drop the
 * index and every by-account lookup either fails or degrades into a scan.</p>
 *
 * <p>The customer table's access contract comes from the batch reader rather than from a screen.
 * {@code app/cbl/CBCUS01C.cbl} declares {@code RECORD KEY IS FD-CUST-ID} at L32 with sequential access
 * at L31, pulls in the record layout at L45, and abends through the language environment at L158, which
 * is the batch-side counterpart of the structured abend detail the shared kernel carries. That yields
 * two required repository capabilities and no more: a keyed read by customer identifier, and an
 * ascending keyset scan ordered by the same column. {@code app/cbl/CBACT01C.cbl} L32 gives the account
 * table the same treatment with {@code RECORD KEY IS FD-ACCT-ID}. Note that a second customer copybook
 * exists in the baseline tree; it is not this contract, and the question is settled by
 * {@code CBCUS01C.cbl} L45 naming the layout the reader actually copies.</p>
 *
 * <p>Trade-offs: the national identifier at {@code CVCUS01Y.cpy} L17 and the government-issued
 * identifier at L18 are stored encrypted as {@code BYTEA} and returned masked, never in the clear. This
 * costs a mapping hop on the way in and out, and it forfeits SQL predicates, sorting and joining over
 * the plaintext, so any future need to search on those values has to be met by a derived and separately
 * protected value rather than by a straightforward {@code WHERE} clause. What it buys is that neither
 * identifier can reach a response body, a log line or a database dump in readable form. The baseline
 * offers no counterweight to trade against here, because it protected them not at all: all four file
 * resources this context reads are declared {@code RECOVERY(NONE) FWDRECOVLOG(NO)} at
 * {@code app/csd/CARDDEMO.CSD} L9, L46, L59 and L72, with {@code READINTEG(UNCOMMITTED)} alongside. The
 * primary account number is likewise masked to its last four digits everywhere except the one
 * administrative detail endpoint that exists to show it.</p>
 *
 * <p>Those same resource attributes are worth reading once more for what they say about isolation.
 * {@code READINTEG(UNCOMMITTED)} permits a read to observe an uncommitted change; the target's default
 * read-committed isolation does not. Encryption at rest, automated backup and committed-read isolation
 * are therefore a strengthening of posture rather than a port of one, and describing them as anything
 * else would misrepresent the baseline.</p>
 *
 * <h2>Money never leaves fixed point</h2>
 *
 * <p>Alternatives Considered: carrying amounts as a binary numeric type in Java, or emitting them as
 * JSON numbers so that clients receive something arithmetic-looking. Both were rejected, and the second
 * is the more tempting mistake because it looks like a convenience rather than a defect. An amount is
 * {@code NUMERIC(12,2)} in the schema, {@code BigDecimal} at scale two with half-up rounding in Java, and
 * a JSON <em>string</em> on the wire, produced through the shared money module. The exemplar for the
 * whole migration is {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L7, and the
 * five money fields listed above at L7, L8, L9, L13 and L14 all take the same treatment. A JSON number
 * is parsed into an IEEE-754 binary floating point value by most clients by default, which reintroduces
 * representation error at precisely the boundary a statement or a credit limit is read at, after the
 * database and the service have both kept the value exact. Emitting a string keeps the exactness the
 * other two hops paid for. IEEE-754 binary floating point types are consequently barred from the money
 * path outright, and the bar is asserted by the shared architecture test rather than left to review.</p>
 *
 * <p>Where an amount is computed, order of operations is preserved as the baseline wrote it: a product
 * is taken at full precision first and only then divided, with the scale and rounding mode stated
 * explicitly. Dividing first and multiplying second changes the intermediate precision and shifts
 * results by whole cents on ordinary inputs, so the re-ordering is not an optimisation but a behavioural
 * change.</p>
 *
 * <p>Assumptions: the source values are zoned decimal with a sign overpunch, not a printable minus sign,
 * and decoding them requires the sign convention to be declared rather than defaulted.
 * {@code tests/README.md} L273 to L274 records that the EBCDIC sign setting is required and that the
 * ASCII default misreads the overpunch and silently corrupts negative balances. Silently is the
 * operative word: nothing fails, and the numbers simply come back wrong. That is the direct justification
 * for routing every fixed-width amount through the shared zoned-decimal codec instead of through any
 * general-purpose numeric parse.</p>
 *
 * <p>The transport types follow from the same evidence. Long identifiers and amounts travel as
 * digits-only strings and are validated for digits, rather than being typed as JSON numbers, because the
 * baseline itself treats them as characters on the wire and as numbers only inside arithmetic. The
 * before-image discussed below holds each numeric as a character field with a numeric
 * {@code REDEFINES} over it, and every money field on both screen maps is alphanumeric, as is the
 * account identifier on the update map at {@code app/cpy-bms/COACTUP.CPY} L60.</p>
 *
 * <h2>Optimistic concurrency is inherited, not invented</h2>
 *
 * <p>Refactoring Rationale: {@code accounts} and {@code customers} carry a JPA version column. This is
 * not a new guarantee imposed on a baseline that lacked one -- it is the native expression of a
 * before-image comparison the update program already performs, and replacing that machinery is the whole
 * point. {@code app/cbl/COACTUPC.cbl} snapshots the complete pre-edit record into
 * {@code ACUP-OLD-DETAILS}, spanning L669 to L756 and ending exactly where {@code ACUP-NEW-DETAILS}
 * begins at L757. Each numeric in that snapshot is held as a character field with a numeric
 * {@code REDEFINES} laid over it, as at L675 to L677 where a {@code PIC X(12)} balance is redefined as
 * {@code PIC S9(10)V99}. A tri-state outcome flag at L664 to L668 distinguishes changes not yet
 * confirmed from changes applied and from changes that failed, and the comparison itself is performed
 * field by field at L4109 to L4192, with the paragraph exit at L4193.</p>
 *
 * <p>What the old approach cost is the reason to replace it: a hand-maintained parallel copy of every
 * field, a width conversion at every boundary, and a comparison that has to be edited in step with the
 * record for as long as the record lives. A version column expresses the same intent in one column and
 * cannot drift out of step with the fields it protects. Nothing is given up in the exchange, because the
 * read-for-update lock was never held across the user's thinking time -- that is exactly why the
 * before-image had to exist. An optimistic lock failure surfaces as HTTP 409 carrying the baseline's own
 * wording, {@code Record changed by some one else. Please review}, reproduced from
 * {@code COACTUPC.cbl} L521 to L522 character for character including "some one" as two words.</p>
 *
 * <p>One structural detail of the baseline is worth recording, because it explains why the target needs
 * two things where the baseline needed one. Those conflict messages are declared as condition names at
 * L511 to L528 sitting on {@code WS-RETURN-MSG PIC X(75)}, declared at L479 -- not on the
 * data-changed flag. Setting the condition therefore both signals the conflict and loads the text a user
 * will read, in a single statement. In the target that fused idiom necessarily splits: the exception
 * carries the signal and the error body carries the text. The commit boundary sits at L952 to L954,
 * immediately before control transfers at L956 to L959, and becomes a transactional boundary around the
 * service method.</p>
 *
 * <p>Trade-offs: the baseline's comparison is case insensitive in places, and the target is not, so the
 * target is strictly the stricter of the two. Across L4109 to L4202 there are exactly two
 * {@code FUNCTION LOWER-CASE} calls, both at L4139 to L4140 folding the account group identifier against
 * its snapshot, and they are the only two occurrences anywhere in that 4236-line program; eighteen
 * {@code FUNCTION UPPER-CASE} calls fold nine further field pairs at L4152 to L4173. A concurrent edit
 * that altered nothing but letter case would consequently pass the baseline's comparison and be
 * overwritten without comment, whereas a version column treats it as the conflict it is and returns 409.
 * The compromise accepted is that a caller who re-cases a field and saves may now meet a conflict where
 * the baseline was silent. It is accepted deliberately: refusing a write that is genuinely concurrent is
 * the safe direction to err, the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, and the alternative -- reproducing
 * case-folded comparison so as to keep a lost update -- would preserve a defect in the name of
 * parity.</p>
 *
 * <p>Refactoring Rationale: the baseline's rollback handling across the two rewrites is asymmetric, and
 * a single transactional boundary subsumes both cases uniformly. The account rewrite's failure path at
 * L4076 to L4081 performs no rollback, which is locally correct because nothing had yet been written;
 * the customer rewrite at L4085 to L4091 has an otherwise identical failure path at L4095 to L4103 that
 * does issue {@code EXEC CICS SYNCPOINT ROLLBACK} at L4099 to L4101, the verb itself on L4100. Keeping
 * that asymmetry would mean encoding, per write, whether a compensating action is owed -- a reader has to
 * reconstruct which writes have already landed in order to know whether a given path is right. Letting
 * the exception propagate out of one transactional boundary makes the question disappear: no manual
 * rollback call is issued anywhere in this context, and both paths unwind identically.</p>
 *
 * <h2>No transactional outbox, and why this context differs from its siblings</h2>
 *
 * <p>Assumptions: the inquiry flow this context absorbs commits its receive, its business logic and its
 * reply as one atomic unit of work, so there is no window in which committed data exists without a reply
 * having been sent, and therefore no lost-reply window for an outbox to close.
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl} settles it. The receive is set up under a syncpoint at L347,
 * immediately before the get call at L352; the reply is sent under a syncpoint at L475, immediately
 * before the put call at L479; the error path at L512 puts under a syncpoint as well. There is no
 * no-syncpoint option anywhere in that 620-line program. The record read sits inside the same window, at
 * L396 to L400. The listener that replaces this flow therefore relies on the queue's own visibility
 * timeout with deletion on success, and adds no outbox table, no publication relay and no reconciliation
 * job -- because there is nothing for them to reconcile.</p>
 *
 * <p>This is stated in the charter rather than buried at the listener because the neighbouring context is
 * the exact opposite. The authorization flow's consumer reads and replies under the <em>no</em>-syncpoint
 * options and commits its database work separately, so a failure between commit and reply loses a reply
 * the data says was produced; that context consequently does need an outbox. The three decoupled
 * extensions simply do not share one messaging discipline, and treating them uniformly would break one of
 * them. Getting these two backwards is the single most likely cross-context error in the migration, in
 * either direction: adding an outbox here would be dead machinery whose reconciliation logic could never
 * be exercised, and omitting one there would drop replies.</p>
 *
 * <p>The rest of that program's contract is preserved as read. Its program declaration at L2 marks it
 * initial, so working storage is reset on every invocation, which is the natural counterpart of a
 * stateless per-message listener. The wait interval at L337 is five seconds and is preserved as the
 * receive wait rather than rounded, since it sets the flow's latency and its polling cost together. The
 * termination test at L377 to L378 exits on the no-message-available reason code, which becomes a bounded
 * poll loop rather than an unbounded one. Correlation is echoed, not regenerated: the inbound correlation
 * identifier is taken at L365, saved at L370, and moved back onto the outbound descriptor at L470, with
 * the reply destination read from the inbound descriptor at L366 and the string format set at L471. The
 * queue names themselves are held in a group at L92 whose four entries at L93 to L96 are declared as
 * blank-initialised fields, which is first-hand evidence that destinations were runtime configuration in
 * the baseline too; none is hard-coded here either. That program copies the same 300-byte account record
 * at L171, and that shared layout is the reason the inquiry belongs inside this context rather than
 * standing up as a separate service that would have split ownership of one table across two
 * deployables.</p>
 *
 * <p>Physical line numbers are cited throughout because that program is sequence-numbered in the legacy
 * style, carrying an ordinal in its first six columns and a second identifier in its last eight. Those
 * ordinals are not line numbers and do not increase in step with them, so a citation taken from the
 * printed ordinal would not resolve.</p>
 *
 * <h2>Pseudo-conversational state is decomposed, not ported</h2>
 *
 * <p>Refactoring Rationale: the baseline's shared communication area is not carried across in any form.
 * {@code app/cpy/COCOM01Y.cpy} L19 to L44 declares {@code 01 CARDDEMO-COMMAREA} in five groups whose
 * declared widths sum to exactly 160 bytes, and every online program echoes that structure to the
 * terminal between turns because the task itself ends at each turn. What was wrong with it is not its
 * size but its trust model and its lifetime: it is storage the client hands back, and it fuses four
 * unrelated concerns into one parameter that every program must accept, pass on and keep consistent. It
 * decomposes into four target mechanisms, each with an owner.</p>
 *
 * <ul>
 *   <li><strong>Navigation</strong> -- the originating and destination transaction and program fields at
 *       L21 to L24, and the last map and mapset at L43 to L44 -- becomes client-side router history.
 *       There is no server-side next-program field in the target at all, so the transfer of control at
 *       {@code app/cbl/COACTVWC.cbl} L349 and {@code app/cbl/COACTUPC.cbl} L956 to L959 does not become a
 *       server-issued redirect.</li>
 *   <li><strong>Identity</strong> -- the user identifier at L25 and the user type at L26, with its
 *       admin and user condition names at L27 and L28 -- becomes validated token claims, converted to
 *       authorities by the shared kernel's role converter. This is a genuine improvement in security and
 *       not merely a change of carrier: in the baseline the value is storage the client returns, so a
 *       client could in principle assert its own user type, whereas a signed claim cannot be forged by
 *       the party it describes.</li>
 *   <li><strong>Selection context</strong> -- the customer identifier at L33, the account identifier and
 *       status at L38 to L39, and the card number at L41 -- becomes path and query parameters, which is
 *       what makes each request self-describing and therefore independently authorisable instead of
 *       authorisable only in the light of a previous turn.</li>
 *   <li><strong>The re-entry discriminator</strong> at L29, with its enter and re-enter condition names
 *       at L30 and L31, <strong>disappears entirely</strong>. A stateless handler that answers an invalid
 *       submission with a status and a per-field error array has no first-entry versus re-entry
 *       distinction left to draw.</li>
 * </ul>
 *
 * <p>Note when quoting that copybook that the user-type condition values at L27 and L28 are quoted
 * character literals while the context values at L30 and L31 are bare numerics; the distinction is real
 * and is preserved. The consequence of the whole decomposition is the first charter clause:
 * statelessness, with no sticky session and no server-side session store, which the absence of any
 * transaction work area at {@code app/csd/CARDDEMO.CSD} L308 and L318 shows was already within reach.</p>
 *
 * <h2>Composition, validation and the verbatim message contract</h2>
 *
 * <p>The account view is a three-hop composition that short-circuits at each miss, and the hop order is
 * part of the contract rather than an implementation detail. {@code app/cbl/COACTVWC.cbl} runs
 * {@code 9000-READ-ACCT} at L687, which drives {@code 9200-GETCARDXREF-BYACCT} at L723 through the
 * alternate index, then {@code 9300-GETACCTDATA-BYACCT} at L774, then
 * {@code 9400-GETCUSTDATA-BYCUST} at L825, their exits at L720, L771, L821 and L870. Each miss ends the
 * sequence with its own message, so a missing cross-reference row and a missing customer row are
 * distinguishable outcomes and must remain so. The file-name literals it works through are declared at
 * L184 to L193; the card-file alternate index also declared there belongs to the card context and is not
 * read from here.</p>
 *
 * <p>Assumptions: user-visible text is reproduced byte for byte, including whitespace, and the literal
 * that is <em>emitted</em> is the contract rather than the one that is declared. The two differ in this
 * program, which is the trap. The condition-name declarations at L125 to L126 and L127 to L128 read
 * "Account number must be a non zero 11 digit number", whereas the literal actually moved to the message
 * field at L672 reads "Account Filter must  be a non-zero 11 digit number" -- three differences, not
 * one: a doubled space between "must" and "be", a hyphen in "non-zero", and the word "Filter" in place
 * of "number". What the user sees is the emitted form, so that is the form the target carries, and
 * normalising the doubled space to a single one would be a silent change to a user-visible string.</p>
 *
 * <p>Message widths come from the program side, not the screen side. {@code COACTVWC.cbl} declares
 * {@code WS-RETURN-MSG PIC X(75)} at L117, and that 75-character width is this context's message
 * contract; the informational field at L110 is 40 characters. Both screen maps declare wider fields,
 * so the narrower program-side width is the binding one. The shared message copybook is a separate,
 * narrower regime and should not be confused with either: {@code app/cpy/CSMSG01Y.cpy} declares just two
 * messages at L17 to L21, both {@code PIC X(50)}. The structured abend contract likewise comes from
 * {@code app/cpy/CSMSG02Y.cpy}, whose {@code ABEND-DATA} spans L21 to L29 with a 4-character code, an
 * 8-character culprit, a 50-character reason and a 72-character message, and which the shared kernel's
 * abend detail type represents.</p>
 *
 * <p>The update program's validation chain is transcribed paragraph by paragraph, and it is identified
 * here by name and by span rather than by a count, because the count depends on what one chooses to
 * count and any single figure would misinform. The numbered edit paragraphs run from
 * {@code 1210-EDIT-ACCOUNT} at {@code app/cbl/COACTUPC.cbl} L1783 to {@code 1280-EDIT-US-STATE-ZIP-CD}
 * at L2536, taking in mandatory, yes-or-no, alphabetic and alphanumeric required and optional, numeric
 * required, signed two-decimal, phone number, national identifier, state code and credit score edits.
 * Three further edit routines carry no number at all -- {@code EDIT-AREA-CODE} at L2246,
 * {@code EDIT-US-PHONE-PREFIX} at L2316 and {@code EDIT-US-PHONE-LINENUM} at L2370 -- so counting
 * numbered paragraphs and counting edit routines give different answers. The driver
 * {@code 1200-EDIT-MAP-INPUTS} at L1429 and the comparison {@code 1205-COMPARE-OLD-NEW} at L1681 are
 * separate again. The span is the reliable identifier.</p>
 *
 * <p>Validation outcomes become a structured per-field error array on the response rather than screen
 * attributes. The three-state flag pattern is visible at {@code app/cbl/COACTVWC.cbl} L58 to L61, where
 * one character distinguishes not-valid at {@code '0'} from valid at {@code '1'} from blank at a space,
 * and it is the source of the shared kernel's field validation flag. The rendering half lives in
 * {@code app/cpy/CSSETATY.cpy}, a procedure-division macro fragment rather than a data record: at L18 to
 * L19 it tests the not-valid and blank states, at L21 to L22 it moves a red attribute onto the field's
 * colour byte, and at L24 to L25 it additionally writes a literal asterisk into a blank field. That
 * asterisk marker for the blank case is preserved. Its L20 condition is the coupling the target severs:
 * the whole highlight is gated on the re-entry flag, and since that flag no longer exists, field-error
 * rendering is driven purely by what the response body says rather than by a remembered turn count.</p>
 *
 * <h2>The lookup allow-lists this context queries but does not own</h2>
 *
 * <p>Address validation checks against allow-lists that the baseline embeds as condition names in
 * {@code app/cpy/CSLKPCDY.cpy}, a 1318-line asset. There are five such condition names across three
 * validation targets, and both framings are recorded here because reconciling them into a single figure
 * would lose information. Counting by target gives three: the phone area code field at L24, the state
 * code field at L1012, and the combined state and leading postal digits field at L1072. Counting by
 * condition name gives five, because the phone area code field alone carries three separate lists -- the
 * general area-code list at L30, the general-purpose code list at L521, and the easily-recognisable code
 * list at L931 -- while the state code carries one at L1013 and the state and postal combination carries
 * one at L1073.</p>
 *
 * <p>That the phone field owns three lists rather than one is the operative detail: honouring only the
 * first would reject valid area codes that the baseline accepts, so address validation has to consult all
 * three. Two further properties matter at the point of use. The combination check sits on the
 * {@code X(4)} level rather than the enclosing group, so it examines only the two-character state code
 * plus the leading two postal digits, and the trailing three-digit field at L1314 carries no condition
 * name and is not validated at all. And the area-code list is a point-in-time snapshot of an externally
 * maintained registry, its provenance recorded in the copybook's own comment at L25 to L29; it is
 * therefore data that can go stale without anything in this codebase changing, which is an assumption to
 * state at the validation site rather than a fact to rely on silently.</p>
 *
 * <p>These lookup rows are seeded and owned by the reference context, not by this one. Address validation
 * queries them; it must not seed them, must not own them, and must not keep a local copy that could drift
 * from the owner's. Anyone re-deriving the lists from that copybook should also note that 1033 of its
 * 1318 lines contain literal tab characters, so it has to be tokenised on whitespace runs rather than on
 * fixed columns.</p>
 *
 * <h2>Lists page by key</h2>
 *
 * <p>Alternatives Considered: paging any list endpoint by numeric position, which is the shorter route
 * and the one the persistence framework makes easiest. It is rejected throughout this subtree, because
 * the baseline reaches these records only by key -- {@code app/cbl/CBCUS01C.cbl} L31 declares
 * {@code ACCESS MODE IS SEQUENTIAL} over the keyed file whose {@code RECORD KEY IS FD-CUST-ID} at L32,
 * and {@code app/cbl/CBACT01C.cbl} L32 declares {@code RECORD KEY IS FD-ACCT-ID} for the same walk --
 * so an ordinal offset is a concept the source never expresses. Every list result is instead carried in
 * the shared kernel's keyset page envelope, which exposes the first key, the last key and whether a
 * further page exists. A forward page is a query for keys strictly greater than the last key seen,
 * ordered ascending, taking one row more than the page size so that the existence of a next page is
 * discovered rather than computed. A backward page is a query for keys strictly less than the first key
 * seen, ordered descending, which is precisely what the baseline's read-previous does.</p>
 *
 * <p>The compromise position-based paging offers is not worth taking, because it is not equivalent under
 * concurrency: a row inserted or deleted before the current position shifts every later row, so a caller
 * walking the pages silently skips rows or sees them twice. Key-based paging cannot exhibit either
 * behaviour, and the baseline's own next-page availability is already a key-based signal rather than a
 * count. Choosing position-based paging would therefore have changed observable behaviour to save a
 * little code. No position-based paging vocabulary, framework page abstraction or page-number concept
 * appears anywhere beneath this root.</p>
 *
 * <h2>The screen contracts behind the transport types</h2>
 *
 * <p>Transport field shapes and widths derive from the symbolic map copybooks alongside the record
 * layouts: {@code app/cpy-bms/COACTVW.CPY} at 464 lines carries 37 named data fields, and
 * {@code app/cpy-bms/COACTUP.CPY} at 668 lines carries 54. Both file names are upper case on disk, as
 * all 17 files in that directory are, which matters on a case-sensitive file system. Both open their
 * input group at L17 and both begin with a 12-byte filler at L18 that is the terminal buffer header
 * rather than a data field, so it never becomes a transport property; the output groups redefine the
 * input groups at L241 and L343 respectively.</p>
 *
 * <p>Assumptions: where the two maps disagree, the target follows the stricter. The account identifier is
 * declared numeric on the view map at {@code COACTVW.CPY} L60 and alphanumeric on the update map at
 * {@code COACTUP.CPY} L60, so the transport type is a digits-only string validated for digits, matching
 * the update map and consistent with the fixed-point discipline above. The government-issued identifier
 * field is named {@code ACSGOVTI} on both maps, at {@code COACTVW.CPY} L210 and {@code COACTUP.CPY} L282;
 * that spelling is worth reading off the source rather than reconstructing from the record field name,
 * because a plausible-looking abbreviation of the same words matches nothing in either map. The update
 * map's function-key legends at L330, L336 and L342 fix the save and cancel actions for this context's
 * screens.</p>
 *
 * <p>Two different field counts circulate for these screens and both are correct: counts in the hundreds
 * come from the map definition source and include protected label fields, whereas the 37 and 54 above are
 * the named data fields in the symbolic maps. They measure different things, so they are recorded side by
 * side rather than reconciled into one number.</p>
 *
 * <h2>The subtree this package roots</h2>
 *
 * <p>This package holds two compilation units at its own level and no more: the Spring Boot entry point,
 * and this descriptor. Every type carrying behaviour lives in a subpackage named for its layer, and the
 * charter for each is a constraint on what may appear there.</p>
 *
 * <ul>
 *   <li>{@code .api} -- transport adapters only: request binding, transport-level validation, status
 *       mapping and delegation. No business rule, no persistence access and no entity ever leaves
 *       here.</li>
 *   <li>{@code .service} -- the transcribed business behaviour, one named method per significant baseline
 *       paragraph so that the traceability matrix can cite pairs, plus transaction boundaries, keyset
 *       orchestration, address validation and the inquiry listener.</li>
 *   <li>{@code .repository} -- keyed operations and keyset browse queries, including the by-account query
 *       that stands in for the alternate index. No position-based paging.</li>
 *   <li>{@code .domain} -- the three persistence entities derived from the copybooks through the
 *       anti-corruption boundary, with the version column on the account and customer entities.</li>
 *   <li>{@code .dto} -- transport records following the symbolic-map and copybook field order and widths,
 *       with amounts and long identifiers as digits-only strings. No local page type and no local error
 *       type.</li>
 *   <li>{@code .mapper} -- the sole boundary at which fixed-width, trailing-blank, filler, masking,
 *       encryption and misspelling-correction concerns may appear at all.</li>
 *   <li>{@code .config} -- stateless token security, the published contract's metadata, datasource and
 *       schema search-path wiring, and queue listener configuration.</li>
 * </ul>
 *
 * <p>Assumptions: the component scan is rooted here, at this package, and is never widened. Every shared
 * kernel type lives under {@code com.carddemo.common}, which sits outside that root, so shared components
 * are not discovered by the scan at all; they arrive through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which the framework loads from the single
 * line of the shared module's registration resource at
 * {@code services/common-lib/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * -- a file that names exactly that one class and nothing else, and through which this service receives
 * the correlation filter, the common-tag meter filter, the money codec module and the single error advice
 * without declaring any of the four. This is the most easily misread thing about the layout, because a
 * reader who assumes the scan reaches the shared kernel will hunt in the wrong module for a bean that was
 * never scannable, and the symptom is silent: log lines with no correlation identity and failed requests
 * rendered in the framework's default shape rather than this migration's error contract.</p>
 *
 * <p>Alternatives Considered: widening the scan to the parent package so that shared components were
 * found automatically. Rejected outright. It would also reach every other bounded context's types, which
 * turns eight independently deployable services into one service that happens to be startable eight ways.
 * That is not a stylistic objection: it is the precise coupling asserted against by
 * {@code NO_SERVICE_DEPENDS_ON_ANOTHER_SERVICES_DOMAIN} at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java} L457,
 * so widening the scan would fail a build rather than merely offend a convention.</p>
 *
 * <h2>Dependency discipline</h2>
 *
 * <p>This context declares exactly one dependency inside the build reactor,
 * {@code com.carddemo:common-lib}, at {@code services/account-service/pom.xml} L199 to L203 with the test
 * artefact at L545 to L550. It declares no dependency on a sibling service module and no type beneath
 * this root may import another context's domain package -- not as an import, not as a fully qualified
 * name, and not as a string literal resolved reflectively. The nine roots in the reactor are the shared
 * kernel {@code com.carddemo.common} plus the eight context roots {@code .auth}, {@code .account},
 * {@code .card}, {@code .transaction}, {@code .reference}, {@code .batch}, {@code .authorization} and
 * {@code .reporting}; this descriptor roots {@code .account}.</p>
 *
 * <p>Layering has exactly one owner. The shared architecture test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * asserts that a domain type depends on no transport or infrastructure type, that no context depends on
 * another context's domain, and that no type on the money path declares an IEEE-754 binary floating point
 * type. Because those are assertions in a test rather than prose in a document, they cannot rot: a
 * violation fails a build instead of surviving a review. {@code ImportControl} is deliberately absent from
 * the Checkstyle configuration for the same reason, and adding it would create a second, silently
 * divergent owner of the same rule.</p>
 *
 * <p>Shared concerns are consumed from the shared kernel and never re-declared here: exact money and its
 * serialisation, the fixed-width, zoned-decimal and packed-decimal codecs and their layout descriptor,
 * the error model with its per-field array and its abend detail, the correlation filter and the keyset
 * page envelope, the token role converter, the metrics configuration, the timestamp formatter, and the
 * date-edit validator and field validation flag. In particular the global exception handler in that module
 * already maps an optimistic lock failure and a restricted delete to HTTP 409, so this subtree must not
 * re-implement either mapping.</p>
 *
 * <p>Alternatives Considered: re-declaring those shared types per context, and generating the accessor
 * and mapping code. All were rejected. Per-context copies were rejected because one former copybook
 * inclusion becomes exactly one import from the single package that owns that contract, which is the Java
 * counterpart of compiling every baseline program against one copybook path; the house precedent is
 * explicit at {@code tests/README.md} L540 to L542, which requires a layout never to be duplicated and to
 * stay single-sourced. Accessor generation was rejected because generated members cannot carry the
 * docstrings the Explainability rule requires and {@code MissingJavadocMethod} is configured with
 * {@code allowMissingPropertyJavadoc} false at {@code config/checkstyle/checkstyle.xml} L365, so they
 * would fail the gate. Mapping generation was rejected because the mapping is not mechanical: it drops
 * filler, masks the primary account number, encrypts two identifiers and renames a misspelled field, and
 * every one of those needs a justification written beside it that a generated mapper has nowhere to
 * hold.</p>
 *
 * <p>The framework starters for web, validation, security and token resource-server support are declared
 * by this module directly, because the shared module marks them optional and optional dependencies are not
 * transitive; assuming otherwise produces a missing class at runtime rather than at build time. Logging
 * arrives transitively through the framework's own logging starter and is never declared. All versions are
 * managed by the parent and are never re-pinned here.</p>
 *
 * <h2>What this subtree must never contain</h2>
 *
 * <p>These are standing prohibitions on what may be authored here, not a record of anything removed.</p>
 *
 * <ul>
 *   <li>No batch job configuration. The batch starter is reserved to the batch context and is not a
 *       declared dependency of this module, so it is not even on the classpath here.</li>
 *   <li>No Java platform module descriptor. Not one exists anywhere in the repository, and introducing
 *       one here would break the flat-classpath assumption every sibling module is built on.</li>
 *   <li>No ignore file. The repository keeps exactly one, at its root, and it is one of only three
 *       pre-existing files this migration modifies at all.</li>
 *   <li>No locally declared exception type, pagination type, error type or message-catalogue type, and no
 *       properties file; each of those either belongs to the shared kernel or is expressed in the profile
 *       configuration this module already carries.</li>
 *   <li>No copy of the architecture test, which belongs to the shared module.</li>
 *   <li>No package descriptor above this one. Neither the {@code com} nor the {@code com.carddemo}
 *       directory contains a processed Java compilation unit, so the file-set check that requires a
 *       descriptor does not fire for either, and the canon for this context is the eight descriptors
 *       covering this root and its seven subpackages.</li>
 * </ul>
 *
 * <h2>How this context is verified</h2>
 *
 * <p>This context has no executable baseline oracle, and claiming one would misdescribe the evidence.
 * {@code tests/README.md} L83 to L85 records that the online programs cannot be run end to end without a
 * transaction runtime, which is absent from the runner, so only their extractable field-validation logic
 * is unit tested there. Independently, that suite's verbatim business-rule section opens at L553 and names
 * only the interest program, the posting program and the transaction-category balance across its whole
 * length; not one of this context's six programs appears in it. Parity here therefore rests on transcribed
 * validation logic and on the copybook contracts, checked against the sources cited throughout this
 * descriptor, rather than on a golden-master comparison.</p>
 *
 * <p>Message text is the exception, and it is verifiable directly. Every user-visible string in this
 * context is present in a copybook or a program at a stated line, so it can be and must be asserted
 * character for character -- including the emitted account-filter literal with its doubled space and the
 * conflict message with "some one" as two words.</p>
 *
 * <p>Assumptions: the gates over this module are pass or fail, with no tolerated middle result. The build,
 * the documentation gate, the test runners and the architecture assertions each either pass or fail the
 * build. The graded condition-code rubric belongs to the baseline COBOL oracle suite under the
 * repository's {@code tests} directory alone: it is defined at {@code tests/README.md} L412, and its L420
 * row declares code 4 a warn or soft reject, which that suite treats as a passing aggregate result. That
 * suite is a separate artefact from this module's own test sources. Reading a build here through that
 * rubric would treat a real violation as an acceptable outcome, so no build in this tree is ever
 * described in its terms.</p>
 *
 * <p>The conventions this descriptor is written to are stated once in
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, which is subordinate to
 * {@code config/checkstyle/checkstyle.xml} where the two ever disagree; the linter configuration is
 * authoritative, and the project Explainability rule outranks both.</p>
 */
package com.carddemo.account;
