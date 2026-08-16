package com.carddemo.reporting.dto;

import com.carddemo.common.money.Money;
import jakarta.validation.constraints.Size;

/**
 * The header, the total and the two artifact references of a customer statement that has been
 * produced.
 *
 * <h2>What this record carries, and what it refuses to do</h2>
 *
 * <p>This is the answer a caller receives once a statement run has completed for one card. It
 * carries three things and nothing else: the header values a statement is issued under, the summed
 * total the statement reports, and a reference to each of the two artifacts the run wrote. The
 * transaction lines themselves are not here -- they arrive separately as
 * {@code StatementTransactionResponse} rows, for the reason set out under the paging heading below.
 * Both artifact references always denote objects the current run wrote, because the reference job
 * replaces both artifacts on every run rather than appending to them, which is established under the
 * artifact heading. Nothing in this record renders, encodes, decodes, accumulates, parses a
 * timestamp, builds or interprets a reference, reads data or calculates anything. The charter at
 * {@code com.carddemo.reporting.dto} closes this package to business rules and to any crossing into
 * {@code com.carddemo.reporting.domain}, so this record states a shape, publishes a declared width
 * per component and refuses the values that would make an instance impossible to describe.
 *
 * <h2>The 80-character statement block the header values are read from</h2>
 *
 * <p>Assumptions: the normative source for every header width is the plain-text statement block,
 * which is {@code 01 STATEMENT-LINES.} at {@code app/cbl/CBSTM03A.CBL} L85. Every line declared
 * inside it occupies exactly 80 characters, matching {@code FD-STMTFILE-REC PIC X(80)} at L45, so a
 * width taken from that block is a width the artifact genuinely holds rather than one inferred from
 * a screen. Three of this record's components are read from it: the customer name is
 * {@code ST-NAME PIC X(75)} at L91, followed by a five-character {@code FILLER} at L92 that pads the
 * line to 80; the account identifier is {@code ST-ACCT-ID PIC X(20)} at L109, sitting after the
 * 20-character label literal at L107 to L108; and the total is {@code ST-TOTAL-TRAMT} at L142, whose
 * mask is described under the money heading.
 *
 * <p>Assumptions: two width asymmetries inside that same block are recorded here because each reads
 * as a transcription slip until the declaration is seen, and a reader who assumes symmetry
 * normalises one of them away. The first is in the address lines: {@code ST-ADD1 PIC X(50)} at L94
 * and {@code ST-ADD2 PIC X(50)} at L97 are both 50 characters with a 30-character {@code FILLER}
 * behind them, while {@code ST-ADD3} at L99 to L100 is declared {@code PIC X(80)} and consumes the
 * whole line by itself. The third address line is therefore genuinely wider than the first two, and
 * an implementation that renders it at 50 truncates reference data. No address value is a component
 * of this record -- customer address data is owned by another bounded context and this surface never
 * carries it -- so the widths are recorded for whoever renders the artifact and for no other reason.
 *
 * <p>Assumptions: the second asymmetry is between the two banner lines, and neither is a
 * mis-alignment of the other. The opening banner {@code ST-LINE0} at L86 to L89 is 31 asterisks,
 * then an 18-character title literal, then 31 asterisks. The closing banner {@code ST-LINE15} at
 * L143 to L146 is 32 asterisks, then a 16-character title literal, then 32 asterisks. Each of the
 * two sums to the same 80 characters the block requires, and the two are deliberately not symmetric
 * with one another, so aligning them onto a common split would change bytes a golden comparison
 * holds unchanged.
 *
 * <p>Assumptions: two literals in that block carry significant whitespace inside the literal itself,
 * which a formatter that trims is guaranteed to destroy. The summary heading at L122 to L125 holds
 * a 20-character literal reading {@code 'TRANSACTION SUMMARY '} whose twentieth character is a
 * trailing space, and the column heading row at L128 to L131 holds a 13-character literal reading
 * {@code '  Tran Amount'} whose first two characters are leading spaces, alongside a 51-character
 * {@code 'Tran Details    '} literal on the same row. The spaces are positional and are part of the
 * declared width rather than decoration around it.
 *
 * <p>Assumptions: two of the block's label-and-value pairs render their value far wider than the
 * quantity behind it, and this is why the account identifier is a digit string here at a declared
 * width of 20 rather than a number. {@code ST-ACCT-ID} at L109 is rendered {@code X(20)} although
 * the identifier it carries is eleven digits wide, and the pair at L118 renders its value
 * {@code X(20)} as well although the quantity behind that one is only three digits wide. Both are
 * deliberate over-wide character renderings in the reference material. The value rendered by the
 * pair at L118 has no component on this surface at all and must not acquire one; it is cited so that
 * a reader auditing the block against this record sees the absence as a decision rather than an
 * oversight. No card verification value is answered by any endpoint of this migration, and no
 * component here carries one.
 *
 * <h2>The total leaves this boundary as a string, and four numeric regimes coexist</h2>
 *
 * <p>Alternatives Considered: the total is {@code com.carddemo.common.money.Money} and serialises as
 * a JSON string at scale 2 in plain decimal, never in exponent notation. The alternative evaluated
 * and rejected was a JSON number. Most clients parse a JSON number into an IEEE-754 binary floating
 * point value at the boundary, so exactness that survived every earlier hop would be surrendered at
 * the one hop a user actually reads, and a statement total is the value a reader is most likely to
 * check by hand. The margin does not permit it: the accumulator behind this component is
 * {@code WS-TOTAL-AMT PIC S9(9)V99} in the packed group at {@code app/cbl/CBSTM03A.CBL} L64 to L65,
 * eleven significant digits, against the roughly 15 to 17 that binary64 offers -- which leaves
 * nothing at all once a client chains two operations of its own on a value that is itself a sum. The
 * encoding is not applied component by component: the shared kernel registers
 * {@code com.carddemo.common.money.MoneyModule} itself, both as an auto-configured bean and through a
 * Jackson service-provider file, for the reason recorded at {@code com.carddemo.reporting.dto}, and
 * those registrations are why this component needs no serialisation annotation of its own and why no
 * class in this module declares the module at all.
 *
 * <p>Assumptions: the plain-text statement renders money three ways, all three place the sign in the
 * LAST position and none of the three groups thousands. The balance is
 * {@code ST-CURR-BAL PIC 9(9).99-} at {@code app/cbl/CBSTM03A.CBL} L113, 13 characters. The
 * transaction line amount, which belongs to {@code StatementTransactionResponse} rather than to this
 * record, is {@code ST-TRANAMT PIC Z(9).99-} at L137, also 13 characters. This record's total is
 * {@code ST-TOTAL-TRAMT PIC Z(9).99-} at L142, likewise 13 characters.
 *
 * <p>Assumptions: two of those three masks are the same 13 characters wide and yet handle a leading
 * zero in opposite directions, which is the specific reason a single shared formatter cannot serve
 * the block. The mask at L113 preserves a leading zero because every digit position in it is a
 * {@code 9}, so a small balance prints with its zeros intact. The mask at L142 suppresses a leading
 * zero to a blank because its digit positions are {@code Z}, so the same magnitude prints with
 * leading blanks instead. Identical width and opposite zero handling inside one 80-character block
 * means a formatter written for either one silently corrupts the other, and the corruption is
 * invisible because both results still read as a number to a human.
 *
 * <p>Assumptions: the {@code '$'} immediately preceding an amount is a separate one-character
 * {@code FILLER} literal and is no part of any mask -- at {@code app/cbl/CBSTM03A.CBL} L141 for the
 * total line and at L136 for the transaction line. Folding the currency symbol into a format pattern
 * moves every column after it one character along, and that failure is silent for the same reason as
 * the one above.
 *
 * <p>Assumptions: four numeric regimes coexist in this bounded context and none substitutes for
 * another. They are the statement's trailing-sign, non-comma-grouped masks named just above; the
 * report's leading-sign, comma-grouped masks, which are {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} at
 * {@code app/cpy/CVTRA07Y.cpy} L30 and {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} at L54, L60 and L66, each 15
 * characters, where a leading-plus position always prints a sign and a mask carrying no {@code 9}
 * position renders zero as 15 blanks; the unsigned {@code 9(nn)} form of the identifiers, which
 * preserves a leading zero and carries no sign at all; and this API form, scale 2 under
 * {@code RoundingMode.HALF_UP}, serialised as a string. The statement masks are emphatically not
 * unified with the report's: the sign sits at the opposite end of the value and the thousands
 * separators are present in one family and absent in the other, so a merged renderer would produce
 * bytes neither artifact declares. Rounding on this surface is the general contract; the truncating
 * rounding the interest calculation requires belongs to batch-service and governs nothing here.
 * Producing any of these display forms is owned by {@code com.carddemo.reporting.mapper} together
 * with {@code com.carddemo.reporting.service}, and by nothing in this record.
 *
 * <h2>The two artifacts, and why both references are always fresh</h2>
 *
 * <p>Assumptions: one run of the statement writer produces two artifacts, not one, and this record
 * carries a reference to each because a caller has no other way to reach either. The plain-text
 * artifact is written through the record declared as {@code FD-STMTFILE-REC PIC X(80)} at
 * {@code app/cbl/CBSTM03A.CBL} L45, and its data definition at {@code app/jcl/CREASTMT.JCL} L87 to
 * L91 states {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)}. The hypertext artifact is written through
 * {@code FD-HTMLFILE-REC PIC X(100)} at L46, and its data definition at L92 to L96 states
 * {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)}. Two artifacts of two different declared record
 * lengths from a single run is exactly why there are two reference components here rather than one
 * with a format discriminator beside it.
 *
 * <p>Assumptions: the hypertext artifact is a declared-width record file whose lines happen to
 * contain markup, and not a free-form document. {@code 01 HTML-LINES.} at
 * {@code app/cbl/CBSTM03A.CBL} L148 to L150 declares {@code 05 HTML-FIXED-LN PIC X(100)} at L149,
 * and the condition-name constants that supply its content begin at L150 with
 * {@code 88 HTML-L01 VALUE '<!DOCTYPE html>'}. Its 100-character line width is therefore a contract
 * carried by the record declaration rather than a formatting preference of whoever writes it, which
 * is what the 100 in its data definition reflects.
 *
 * <p>Assumptions: both artifacts are replaced on every run and neither is ever appended to, and this
 * is the whole reason a reference in this record can be relied on to denote an object the current
 * run wrote. The job brackets production with a delete step and a create step: the step at
 * {@code app/jcl/CREASTMT.JCL} L66 runs {@code PGM=IEFBR14} with both datasets held at
 * {@code DISP=(MOD,DELETE,DELETE)}, at L67 to L71 and at L72 to L75, which removes whatever the
 * previous run left; the step at L79 then runs the statement writer with both held at
 * {@code DISP=(NEW,CATLG,DELETE)}, at L87 and at L92. Delete-then-create is the replace semantic, so
 * neither reference can point at a stale artifact and neither can point at one carrying an earlier
 * run's lines ahead of this one's.
 *
 * <p>Assumptions: two properties of the reference job are recorded as observations of immutable
 * reference material, and neither is asserted as a defect in it. First, the record length stated on
 * the hypertext delete stanza at {@code app/jcl/CREASTMT.JCL} L69 is 80 rather than 100, and it is
 * inert: {@code IEFBR14} writes no data at all, so a delete step's record-length attribute is never
 * consulted. It is noted only so that a reader scanning the job for the hypertext record length does
 * not take 80 from that stanza instead of the 100 the create stanza and the record declaration both
 * state. Second, L90 within the plain-text create stanza is a corrupted overlapping line, reading as
 * fragments of three different clauses run together, and it is deliberately not quoted here as
 * though it were well formed. The surrounding stanza remains unambiguous -- L87 to L91 carries the
 * disposition, the record-length attributes and the dataset name between them -- so the artifact
 * contract is readable in full despite it.
 *
 * <p>Assumptions: a statement composes four inputs, which is why one header record carries a card
 * number, an account identifier and a customer name together. The statement step's four data inputs
 * are the definitions at {@code app/jcl/CREASTMT.JCL} L83 to L86 -- {@code TRNXFILE},
 * {@code XREFFILE}, {@code ACCTFILE} and {@code CUSTFILE} -- and they correspond exactly to the four
 * file declarations of the access subprogram {@code app/cbl/CBSTM03B.CBL}, at its L31, L37, L43 and
 * L49. The card-ordered transaction input supplies the card number, the cross-reference resolves it,
 * and the account and customer inputs supply the identifier and the name. This record issues no
 * query of its own and imports no data-access type; it is only the shape those four inputs collapse
 * into.
 *
 * <p>Assumptions: the {@code card} schema is exercised by neither of this context's two generators,
 * and the discrepancy a reader might expect from that does not arise. Neither the statement input
 * list at L83 to L86 nor the transaction report's own input list reaches it, and every card number
 * this context surfaces originates in the cross-reference and in the card-ordered statement
 * projection of {@code app/cpy/COSTM01.CPY} instead. The service's connection-time schema resolution
 * names one schema, the {@code reporting} schema holding the read-only views, rather than naming
 * four source schemas; {@code services/reporting-service/src/main/resources/application.yml} owns
 * that decision and argues it in place, so there is no four-schema resolution here for the absence
 * to contradict.
 *
 * <h2>Divergence D-2: the reference writer's two unchecked tables</h2>
 *
 * <p>Assumptions: the reference statement writer holds its working set in two independent tables
 * that it does not bounds-check, and three numbers are involved that must each be stated separately
 * because no one of them stands for another. The first is a declared arity: the inner table is
 * declared {@code 10 WS-TRAN-TBL OCCURS 10 TIMES.} at {@code app/cbl/CBSTM03A.CBL} L228, nested
 * inside the outer table, and that declared 10 is an arity in the source text and not a threshold
 * anybody reaches.
 *
 * <p>Assumptions: the second number is the measured same-card threshold, which is 512. A single card
 * renders up to 512 transactions, and the 513th overruns that inner table and faults, a condition
 * recorded as {@code F-STMT-INNER-OVERFLOW}.
 *
 * <p>Assumptions: the third number is the distinct-card limit, which is 51, and it is independent of
 * the threshold above rather than a restatement of it at a different scale. It is declared twice,
 * once as {@code 05 WS-CARD-TBL OCCURS 51 TIMES.} at {@code app/cbl/CBSTM03A.CBL} L226 and once as
 * {@code 05 WS-TRN-TBL-CTR OCCURS 51 TIMES.} at L232, and it is measured at that same value: the
 * 52nd distinct card overruns the outer table and faults, a condition recorded as
 * {@code F-STMT-OUTER-OVERFLOW}. The two overruns are separate defects of two separate tables, so
 * they are stated in separate sentences and no sentence anywhere in this file describes them jointly.
 *
 * <p>Assumptions: the difference is framed the way this context frames every difference. The
 * baseline behaves as just described; the Java statement service behind this record holds no table of
 * fixed arity at all and therefore has neither threshold to reach and no bound to check; and the
 * difference is registered rather than absorbed silently.
 * {@code docs/architecture/cobol-to-service-traceability.md} owns that register, where the entry is
 * D-2, and this record cites the entry without restating it. Everything under {@code app/} is
 * reference material and remains byte-identical; no statement in this file describes an edit to any
 * of it.
 *
 * <h2>The production timestamp is an opaque 26-character value</h2>
 *
 * <p>Assumptions: the production timestamp is carried as an opaque string at its declared width and
 * never as a temporal object. {@code com.carddemo.common.time.TimestampFormatter} is the producer of
 * well-formed values on the write side, and the length it publishes at its L210 is 26, the width of
 * the punctuated form it renders. Two reasons make an opaque carrier the correct choice, and they are
 * the same two {@code StatementTransactionResponse} records for its own pair of timestamps. The
 * reference data stores a timestamp as a 26-character field rather than as a typed value, and an
 * all-blank 26-character timestamp is a legitimate value in it that a strict parse would raise on
 * instead of returning. This component therefore round-trips exactly what it was given, blanks
 * included, and validates no punctuation and no fractional part.
 *
 * <p>Assumptions: the statement path gives that discipline a second, stronger justification, which
 * {@code StatementTransactionResponse} derives in full and this record does not re-derive. The
 * re-formatting directive at {@code app/jcl/CREASTMT.JCL} L54 delivers only 24 of the 26 characters
 * of the processing timestamp it copies, so a value reaching this context through the statement path
 * can be short of its declared width by construction. A parse into a temporal object would fail on
 * exactly the data the reference job produces.
 *
 * <h2>The count, and why it is the one integer on this surface</h2>
 *
 * <p>Assumptions: the transaction count is a whole number and is declared as an integer rather than
 * as a string or as a monetary value, which makes it the only component of this kind in the package.
 * Its source is {@code TR-CNT PIC S9(4)} in the binary group at {@code app/cbl/CBSTM03A.CBL} L61,
 * declared immediately after {@code CR-CNT PIC S9(4)} at L60, and it is a tally of lines rather than
 * an identifier or an amount. An integer is correct for it on both counts: unlike an identifier it
 * has no leading zero to preserve, because nothing renders it into a positional field of declared
 * width; and unlike an amount it carries no scale, so representing it as a whole number surrenders
 * no exactness and leaves the prohibition on inexact monetary representation untouched -- that
 * prohibition governs values with cents, and this value has none.
 *
 * <p>Assumptions: the count is the statement's own tally of the lines the run produced for this card,
 * and it is not a property of any page of results. It is not a page total in the report sense, and it
 * is not an artefact of ordinal paging, which this context does not use anywhere. Saying so
 * explicitly is worth a sentence because a numeric count sitting beside two references is exactly the
 * component a reader might mistake for a paging quantity.
 *
 * <h2>What this record is not</h2>
 *
 * <p>Assumptions: this record is not the transaction list and is not the envelope that carries one.
 * The statement's transaction lines are answered as {@code StatementTransactionResponse} rows inside
 * {@code com.carddemo.common.web.PageResponse}, the single such envelope in the reactor, whose FOUR
 * components and one type parameter -- {@code items}, {@code firstKey}, {@code lastKey} and
 * {@code hasNext} -- are declared at
 * {@code services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java} L231 to L235.
 * This record is neither that envelope nor an element inside it, and a controller composes the two
 * answers independently.
 *
 * <p>Refactoring Rationale: this said seven components and cited L210 to L217, which is prose rather
 * than the declaration. The envelope has four components. The correction is recorded rather than made
 * silently because the same inflated figure appeared on the sibling row record, so a reader who found
 * it twice would reasonably have treated it as verified. The envelope is therefore named in this prose and deliberately not
 * imported, because an import that no declaration here needs would assert a dependency edge that
 * does not exist.
 *
 * <p>Assumptions: no ordinal paging component, parameter or accessor appears here, and no prose here
 * implies that one exists. A cursor keyed on the columns a query already orders by is used instead,
 * because an ordinal cursor loses and repeats rows once a concurrent insert shifts positions between
 * two reads, and that is a change in observable behaviour rather than any claim about speed. Two
 * reference browse fields are absent for the same reason and must not reappear:
 * {@code WS-CA-SCREEN-NUM}, a counter, and {@code WS-CA-LAST-PAGE-DISPLAYED}, whose polarity inverts
 * the reading its name suggests because shown is 0 and not-shown is 9. The carve-out is stated so it
 * is not mistaken for a lapse: the words page, report page, page total and grand total are domain
 * vocabulary in this context, naming properties of an output layout that has to be reproduced
 * character for character, and the reference report declares two of them outright as the literals at
 * {@code app/cpy/CVTRA07Y.cpy} L52 and L64.
 *
 * <p>Assumptions: the card number reaches this record already reduced to a mask and its trailing four
 * digits, applied at the mapping site in {@code com.carddemo.reporting.mapper}, which is the single
 * place this context permits such a decision to appear. The unreduced primary account number is
 * answered only by the administrative card-detail endpoint, which belongs to another bounded context
 * and is reached by a different authority. Two consequences follow that are not visible from the
 * declaration. No digits-only constraint is asserted on that component even though its source field
 * holds digits, because the value it actually carries is a mask and a digits-only assertion would
 * reject every legitimate one. And no rendering override is declared on this record, whereas
 * {@code StatementRequest} in this same package declares one at its L99 to L103: that type's card
 * number is a caller-supplied selector holding the unreduced value, so its generated rendering had
 * something worth withholding, while this type's has already been reduced before an instance can
 * exist.
 *
 * <p>Assumptions: this record carries no repeat-entry marker of any kind, so no resubmission flag, no
 * first-entry flag and no turn counter. The reference session structure's discriminator is eliminated
 * rather than ported, for the reason recorded at {@code com.carddemo.reporting.dto}: its value is
 * remembered state that means something only because a task ends at every screen turn, and a handler
 * answering field errors in a response body has nothing to remember.
 *
 * <p>Assumptions: this record is not an error carrier either. A failure surfaces through
 * {@code com.carddemo.common.error.ApiError} with its per-field entries from
 * {@code com.carddemo.common.validation.FieldValidationFlag}, and an abend through
 * {@code com.carddemo.common.error.AbendDetail}, whose four structured members are declared at
 * {@code app/cpy/CSMSG02Y.cpy} L21 to L29 at widths 4, 8, 50 and 72. That file ends at L35, so a
 * citation past it names nothing. No thrown type, no problem shape and no message catalogue is
 * declared here.
 *
 * <p>Assumptions: this context owns no table, no index and no schema-management artifact, and reads
 * read-only cross-schema projections under a role holding read access alone, as recorded at
 * {@code com.carddemo.reporting.dto}. So this record carries no version marker for optimistic
 * concurrency and no identifier a caller is expected to supply, because there is nothing in this
 * context to write.
 *
 * <h2>Where a width is enforced, and the two compromises accepted</h2>
 *
 * <p>Alternatives Considered: each declared width is asserted once, by the {@code @Size} constraint
 * in the header, and the constructor below deliberately does not repeat that check. Repeating it was
 * the alternative and it is rejected on the ground {@code StatementRequest} L192 to L220 already
 * records: two executable positions for one declared width are what let a caller be published one
 * contract and held to another. The constraint is the single authority for width, and the constructor
 * asserts only the properties a width constraint cannot express -- that the values naming the
 * statement carry content, that the total is present, that the count is not negative and that each
 * artifact reference actually refers to something.
 *
 * <p>Trade-offs: each artifact reference is carried as a plain string rather than as a parsed
 * location object, so this record cannot tell a well-formed reference from a malformed one. The
 * compromise is accepted because the two locations are supplied by the runtime that wrote the
 * artifacts, and a parsed type would commit this record to a scheme vocabulary it has no business
 * choosing, rejecting a legitimate location the runtime supplies. Neither reference is constructed,
 * decomposed or interpreted here, and no location, container name or region appears anywhere in this
 * file. The compensating discipline is the constructor's refusal of a reference carrying no content,
 * so an artifact that was never written cannot arrive looking like one that was.
 *
 * <p>Trade-offs: the count is a primitive whole number, so it cannot express absence, and a run that
 * genuinely produced no count is indistinguishable here from one that produced a count of zero. The
 * compromise is accepted because zero is the truthful count for a card with no qualifying
 * transactions, which is a real and unremarkable outcome, whereas an absent count is not a state the
 * writer at {@code app/cbl/CBSTM03A.CBL} L61 can reach -- it initialises the tally and increments it,
 * so a completed run always has one. The cost is that a defective caller passing nothing at all
 * cannot be distinguished from a correct caller passing zero, and the negative-value refusal in the
 * constructor is what narrows the remaining gap.
 *
 * @param cardNumber the {@code String} card number the statement was produced for, already reduced
 *     to a mask and its trailing four digits before an instance can exist; its source field is the
 *     control-break key {@code WS-SAVE-CARD PIC X(16)} at {@code app/cbl/CBSTM03A.CBL} L69, the
 *     value that program compares against to decide where one card's statement ends and the next
 *     begins, and a value carrying no content is refused because a statement that cannot name its
 *     card cannot be attributed to one
 * @param accountId the {@code String} account identifier the statement is issued for, held as
 *     digit characters so that a leading zero survives; its rendered field is
 *     {@code ST-ACCT-ID PIC X(20)} at L109, which is 20 characters wide although the identifier
 *     itself is eleven digits, and a value carrying no content is refused because the identifier is
 *     how a statement is located again after the run
 * @param customerName the {@code String} name the statement is issued to, as
 *     {@code ST-NAME PIC X(75)} declares it at L91; it is carried at that full declared width with
 *     whatever padding the reference data holds, and it is deliberately not refused when blank
 *     because a blank name is a value the reference record can hold and is not grounds for
 *     discarding a statement that was produced
 * @param totalAmount the {@code Money} sum of every transaction line on the statement, held as an
 *     exact scaled decimal and serialised as a JSON string; it is rendered by
 *     {@code ST-TOTAL-TRAMT PIC Z(9).99-} at L142 and accumulated in
 *     {@code WS-TOTAL-AMT PIC S9(9)V99} at L65, and it is refused when absent because a statement
 *     reporting no total reports nothing a reader can reconcile and a substituted zero would say
 *     something untrue without saying it loudly
 * @param transactionCount the {@code int} number of transaction lines the run produced for this
 *     card, from {@code TR-CNT PIC S9(4)} at L61; it is a tally rather than an amount or an
 *     identifier, carries no scale and is therefore outside the exact-decimal monetary discipline
 *     entirely, and a negative value is refused because no sequence of increments can reach one
 * @param plainTextUri the {@code String} location the plain-text artifact is collected from, whose
 *     record is {@code FD-STMTFILE-REC PIC X(80)} at L45 and whose declared record length of 80 is
 *     stated at {@code app/jcl/CREASTMT.JCL} L87 to L91; it is opaque to this record, which neither
 *     builds nor interprets it, and it is {@code null} exactly when the store holds no such artifact
 *     -- but a PRESENT value carrying no content is still refused, because a blank location is not an
 *     absence, it is an absence a caller cannot detect
 * @param htmlUri the {@code String} location the hypertext artifact of the same run is collected
 *     from, whose record is {@code FD-HTMLFILE-REC PIC X(100)} at L46 and whose declared record
 *     length of 100 is stated at L92 to L96; it is a second location rather than a variant of the
 *     first because the run writes two artifacts of two different record lengths, and it is
 *     {@code null} or refused on the same terms as the plain-text location
 * @param generatedAt the {@code String} instant the statement artifact was written, carried as an
 *     opaque 26-character value matching the length
 *     {@code com.carddemo.common.time.TimestampFormatter} publishes at its L210; it is neither
 *     parsed nor normalised, an all-blank value round-trips unchanged and so is deliberately not
 *     refused, and it is {@code null} when the store holds no artifact to have written
 * @param firstRecord the zero-based ordinal of the FIRST RECORD this card's statement occupies inside
 *     the run-wide plain-text artifact, or {@code null} when no artifact is stored or the run's index
 *     does not name this card; it is a record ordinal rather than a byte offset because the artifact is
 *     a sequence of records of the fixed width {@code FD-STMTFILE-REC PIC X(80)} declares at L45, so an
 *     ordinal locates a statement without depending on how the artifact is framed in transport
 * @param recordCount how many consecutive records the statement occupies from that ordinal, or
 *     {@code null} on the same terms; the pair is what makes a run-wide artifact usable from a per-card
 *     response, and the two are always present or absent together
 */
public record StatementResponse(
        @Size(max = CARD_NUMBER_WIDTH) String cardNumber,
        @Size(max = ACCOUNT_ID_WIDTH) String accountId,
        @Size(max = CUSTOMER_NAME_WIDTH) String customerName,
        Money totalAmount,
        int transactionCount,
        String plainTextUri,
        String htmlUri,
        @Size(max = TIMESTAMP_WIDTH) String generatedAt,
        Long firstRecord,
        Long recordCount) {

    // WHY : Assumptions: 16 is the width the control-break key WS-SAVE-CARD PIC X(16) declares at
    //       app/cbl/CBSTM03A.CBL L69, and the same 16 that TRNX-CARD-NUM declares at
    //       app/cpy/COSTM01.CPY L22. The constant bounds the component even though the value
    //       arriving here is a mask and therefore shorter, because the width published to a
    //       consumer has to be the width the reference field declares rather than the length of
    //       the reduced form -- masking is the mapping site's decision to make and to revisit, and
    //       a bound pinned to its current output would contradict this record the moment that
    //       decision moved.
    private static final int CARD_NUMBER_WIDTH = 16;

    // WHY : Assumptions: 20 is the RENDERED width ST-ACCT-ID PIC X(20) declares at
    //       app/cbl/CBSTM03A.CBL L109, not the eleven digits the identifier itself occupies. The
    //       rendered width is the bound because that is what the statement block reserves and what
    //       a value read back out of the artifact can legitimately carry; bounding at eleven would
    //       reject a value the reference artifact holds.
    private static final int ACCOUNT_ID_WIDTH = 20;

    // WHY : Assumptions: 75 is the width ST-NAME PIC X(75) declares at app/cbl/CBSTM03A.CBL L91.
    //       The five-character FILLER behind it at L92 pads the line to the block's 80 characters
    //       and is not part of the name, so the bound is 75 and never 80.
    private static final int CUSTOMER_NAME_WIDTH = 75;

    // WHY : Assumptions: 26 is the length com.carddemo.common.time.TimestampFormatter publishes at
    //       its L210 for the punctuated form it renders, and the same width the reference records
    //       declare for a timestamp field. A value carrying fewer significant characters than that
    //       still occupies 26 positions, so this bound admits the shortened form the statement
    //       path delivers per app/jcl/CREASTMT.JCL L54 without needing to know about it.
    private static final int TIMESTAMP_WIDTH = 26;

    /**
     * Refuses a statement that could not be attributed, reconciled or collected.
     *
     * <p>The constructor accepts the eight record components documented by the component tags on
     * the type above, in declaration order. The compact canonical form repeats no parameter list,
     * and as a constructor it yields no separate value; successful completion creates this record
     * with every component stored exactly as it was supplied, because a response type that altered
     * its input would conceal the shortened timestamp the statement path delivers.
     *
     * <p>Assumptions: the six refusals below are the complete set, and each guards a property that
     * a width constraint on the header cannot express. Two of them protect attribution: a statement
     * has to name the card it covers and the account it is issued for. One protects
     * reconciliation: a total that is absent is not a total of zero. One protects the tally against
     * a value no sequence of increments at {@code app/cbl/CBSTM03A.CBL} L61 can produce. Two
     * protect collection in the one way still available to them: a location that is PRESENT has to be
     * followable, so a blank location is refused while an absent one is admitted and means the store
     * holds no artifact. They are asserted here rather than trusted to the caller because
     * an instance cannot come into being without passing through this constructor, which is what
     * makes the guarantee hold by construction instead of by every mapping site remembering it.
     *
     * <p>⚠️ Refactoring Rationale: the two artifact locations were REQUIRED here, and that requirement
     * was what forced the producer to assert a location whether or not an artifact existed. A review
     * found the consequence: every location published named an object nothing writes, so a caller
     * could not distinguish a statement that had been produced from one that had not. Admitting
     * {@code null} moves the distinction into the contract, where a caller can act on it, and keeping
     * the blank refusal preserves the only part of the old guarantee that was ever worth having.
     * Alternatives Considered: keeping both required and publishing a sentinel such as an empty
     * string, which is the state this constructor exists to refuse.
     *
     * <p>Assumptions: two components are deliberately NOT refused when they carry no content, and
     * the omissions are decisions rather than gaps. The customer name may be blank because the
     * reference record can hold it blank, and discarding a statement that was genuinely produced
     * over a blank name would lose data to no purpose. The production timestamp may be blank
     * because an all-blank 26-character timestamp is legitimate reference data that has to
     * round-trip unchanged, so refusing it would reject exactly the value the reference material
     * supplies.
     *
     * <p>Assumptions: no message raised below reproduces the value it rejected. One component is a
     * primary account number already reduced to a mask, and two are artifact locations, so naming
     * the component and withholding its content keeps a diagnostic from re-exposing either. This
     * matches the refusal discipline {@code StatementRequest} L211 to L220 records for the request
     * half of this surface.
     *
     * @throws IllegalArgumentException if {@code cardNumber} is {@code null} or carries no content,
     *     because the card is what the reference writer breaks its statements on at
     *     {@code app/cbl/CBSTM03A.CBL} L69 and an unattributable statement cannot be returned; or
     *     if {@code accountId} is {@code null} or carries no content, because the identifier
     *     rendered at L109 is how the statement is attributed and located again; or if
     *     {@code totalAmount} is {@code null}, because the figure rendered at L142 is the sum a
     *     reader reconciles against and an absent total is not a zero; or if
     *     {@code transactionCount} is negative, because the tally at L61 is reached by
     *     initialisation and increment and cannot descend below zero; or if {@code plainTextUri} or
     *     {@code htmlUri} is PRESENT and carries no content, because a location the caller cannot
     *     follow is worse than a declared absence -- {@code null} is admitted for either, and means
     *     the store holds no such artifact
     */
    public StatementResponse {
        // WHY : Assumptions: the card number is checked for content rather than merely for
        //       presence, because a value of the declared width consisting entirely of blanks is
        //       something the reference record can hold and is still not an attribution. The
        //       reference writer compares this value against its saved key at
        //       app/cbl/CBSTM03A.CBL L69 to decide where one card's statement ends, so a value it
        //       could not have compared is not a value this record can carry.
        if (cardNumber == null || cardNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "cardNumber identifies the card the statement was produced for and must carry "
                            + "content");
        }

        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException(
                    "accountId identifies the account the statement is issued for and must carry "
                            + "content");
        }

        // WHY : Assumptions: an absent total is refused rather than defaulted to zero. Zero is a
        //       truthful total for a card with no qualifying transactions, so substituting it for a
        //       missing value would produce a statement that reconciles against nothing and reports
        //       no fault -- the failure would be silent, which is the one kind a monetary surface
        //       cannot afford. Refusing keeps the two states distinguishable at the only point
        //       where the difference is still cheap to report.
        if (totalAmount == null) {
            throw new IllegalArgumentException(
                    "totalAmount is the sum the statement reports and must be present; an absent "
                            + "total is not a total of zero");
        }

        // WHY : Assumptions: the tally is refused only when negative, and zero is admitted. The
        //       source counter at app/cbl/CBSTM03A.CBL L61 is initialised and then incremented, so
        //       zero is reachable and describes a card with no qualifying lines, while a negative
        //       value is reachable by no sequence of increments and therefore evidences a defect on
        //       this side of the boundary rather than an unusual statement.
        if (transactionCount < 0) {
            throw new IllegalArgumentException(
                    "transactionCount counts the statement's transaction lines and cannot be "
                            + "negative");
        }

        // WHY : Assumptions: each artifact location is checked for CONTENT WHEN PRESENT and admitted
        //       when absent, and the asymmetry is the point. Absent states that the store holds no
        //       such artifact, which is a fact a caller can act on; blank states nothing at all while
        //       looking like a location, so a caller would follow it and fail. Trade-offs: the two
        //       are still checked independently in the order the header declares them, even though
        //       one run writes both at app/jcl/CREASTMT.JCL L87 and L92 -- an interrupted run or a
        //       lifecycle rule that expires one artifact leaves the store holding the other, and
        //       reporting the one that exists beats reporting neither.
        if (plainTextUri != null && plainTextUri.isBlank()) {
            throw new IllegalArgumentException(
                    "plainTextUri must locate the plain-text artifact or be absent; a blank "
                            + "location is an absence a caller cannot detect");
        }

        if (htmlUri != null && htmlUri.isBlank()) {
            throw new IllegalArgumentException(
                    "htmlUri must locate the hypertext artifact or be absent; a blank location is "
                            + "an absence a caller cannot detect");
        }

        // WHY : Assumptions: the two position components are checked as a PAIR, because either alone
        //       says nothing usable -- an ordinal without a count does not delimit a statement, and a
        //       count without an ordinal does not locate one. Refusing the half-populated shape here is
        //       what stops a caller having to guess which of the two it can trust. Trade-offs: a
        //       negative value is refused separately from the pairing, because a negative ordinal is a
        //       defect on this side of the boundary while a half-populated pair is a mapping mistake,
        //       and one message naming both would identify neither.
        if ((firstRecord == null) != (recordCount == null)) {
            throw new IllegalArgumentException(
                    "firstRecord and recordCount locate one statement inside the run artifact and "
                            + "must be present or absent together");
        }

        if (firstRecord != null && (firstRecord < 0 || recordCount < 0)) {
            throw new IllegalArgumentException(
                    "firstRecord and recordCount are ordinals into the run artifact and cannot be "
                            + "negative");
        }
    }

    /**
     * Returns the masked card number the statement was produced for.
     *
     * <p>Assumptions: the value returned is already the reduced form and this accessor performs no
     * reduction of its own, so it cannot be the place a mask is applied or forgotten. Reduction
     * happens once, at the mapping site in {@code com.carddemo.reporting.mapper}, before an instance
     * of this record exists; an accessor that masked on the way out would leave the unreduced value
     * inside the record where a generated rendering or a debugger could still reach it.
     *
     * @return a {@code String} carrying the mask and the trailing four digits of the card number,
     *     never the unreduced primary account number
     */
    public String cardNumber() {
        return cardNumber;
    }

    /**
     * Returns the account identifier the statement is issued for.
     *
     * @return a {@code String} carrying the account identifier as digit characters, with any
     *     leading zero preserved
     */
    public String accountId() {
        return accountId;
    }

    /**
     * Returns the customer name the statement is issued to.
     *
     * @return a {@code String} carrying the customer name at its full declared width, including
     *     whatever padding the reference data holds, or a blank value where the reference data
     *     holds one
     */
    public String customerName() {
        return customerName;
    }

    /**
     * Returns the exact summed total the statement reports.
     *
     * @return the non-null {@code Money} total, carried as an exact scaled decimal and serialised
     *     onward as a JSON string
     */
    public Money totalAmount() {
        return totalAmount;
    }

    /**
     * Returns the number of transaction lines the run produced for this card.
     *
     * @return a non-negative {@code int} tally of the statement's own transaction lines, which is
     *     not a quantity describing any page of results
     */
    public int transactionCount() {
        return transactionCount;
    }

    /**
     * Returns the location the plain-text artifact is collected from.
     *
     * @return a non-blank {@code String} location, opaque to this record, or {@code null} when the
     *     store holds no plain-text artifact for this statement
     */
    public String plainTextUri() {
        return plainTextUri;
    }

    /**
     * Returns the location the hypertext artifact of the same run is collected from.
     *
     * @return a non-blank {@code String} location, opaque to this record, or {@code null} when the
     *     store holds no hypertext artifact for this statement
     */
    public String htmlUri() {
        return htmlUri;
    }

    /**
     * Returns the opaque production timestamp without parsing or normalisation.
     *
     * @return a {@code String} carrying the timestamp exactly as supplied, including trailing
     *     blanks, an all-blank value where the reference data holds one, or {@code null} when the
     *     store holds no artifact whose write instant could be reported
     */
    public String generatedAt() {
        return generatedAt;
    }

    /**
     * Returns where this card's statement begins inside the run-wide plain-text artifact.
     *
     * @return the zero-based record ordinal, or {@code null} when no stored artifact names this card
     */
    public Long firstRecord() {
        return firstRecord;
    }

    /**
     * Returns how many records this card's statement occupies inside that artifact.
     *
     * @return the record count, or {@code null} on the same terms as the ordinal
     */
    public Long recordCount() {
        return recordCount;
    }

    /**
     * The stand-in a withheld value is rendered as.
     */
    private static final String REDACTED = "REDACTED";

    /**
     * Renders this statement summary without the cardholder values it carries.
     *
     * <p>Refactoring Rationale: this override exists because the record-generated {@code toString}
     * renders the primary account number, the account identifier, the customer's name and the statement
     * total. Three of those four are exactly the values every other rendering in this reactor is careful
     * to withhold, and the fourth -- the total -- is a monetary value attributable to a named person by
     * the other three on the same line.</p>
     *
     * <p>Assumptions: the primary account number is withheld ENTIRELY rather than masked to its last
     * four digits here. A masked rendering is the right answer in a response body, where a cardholder is
     * confirming which of their own cards they are looking at; it is the wrong answer in a log, where the
     * last four digits combined with a customer name on the same line identify the card outright.</p>
     *
     * <p>Assumptions: the transaction count and the two artefact locations ARE rendered. The count is a
     * cardinality with no attribution once the identifiers are withheld, and each location is a served
     * path whose only variable part is a keyed opaque selector -- it spells out no account identifier and
     * no part of a card number, which is precisely why the location is built that way, and it is the
     * single most useful thing this rendering can carry when a failure concerns one artefact. A
     * {@code null} renders as such and reports the absence the response declares. The write instant is
     * rendered for the same reason and carries no personal content. The two artifact positions are
     * rendered too: they are ordinals into a run-wide document, they identify nobody, and they are the
     * values an operator needs in order to look at the records a complaint is about.</p>
     *
     * @return a rendering safe to write to any log or exception message, never {@code null}
     */
    @Override
    public String toString() {
        return "StatementResponse[cardNumber=" + REDACTED
                + ", accountId=" + REDACTED
                + ", customerName=" + REDACTED
                + ", totalAmount=" + REDACTED
                + ", transactionCount=" + transactionCount
                + ", plainTextUri=" + plainTextUri
                + ", htmlUri=" + htmlUri
                + ", generatedAt=" + generatedAt
                + ", firstRecord=" + firstRecord
                + ", recordCount=" + recordCount
                + "]";
    }
}
