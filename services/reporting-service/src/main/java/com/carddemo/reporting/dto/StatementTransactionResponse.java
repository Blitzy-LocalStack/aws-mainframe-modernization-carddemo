package com.carddemo.reporting.dto;

import com.carddemo.common.money.Money;
import jakarta.validation.constraints.Size;

/**
 * One transaction line of a customer statement, as it crosses the reporting API boundary.
 *
 * <h2>What this record is, and what it refuses to do</h2>
 *
 * <p>This is the row type of the statement retrieval surface that {@code com.carddemo.reporting.api}
 * exposes in place of the batch statement flow, and it carries all thirteen named fields of the
 * 350-byte statement transaction record. Only three of those thirteen reach the plain-text artifact
 * the reference job writes, and both timestamps travel as opaque strings rather than as temporal
 * objects, for the arithmetic reason set out under the re-formatting heading below. Nothing here
 * renders, decodes, reads or calculates. The charter at {@code com.carddemo.reporting.dto} closes
 * this package to business logic and to any crossing into
 * {@code com.carddemo.reporting.domain}, so this record states a shape, publishes a declared width
 * per component and enforces the three invariants a row must satisfy to be nameable inside a page.
 *
 * <h2>The source record, and the deliberate re-keying that produced it</h2>
 *
 * <p>Assumptions: the normative record is {@code app/cpy/COSTM01.CPY}, and it is a re-keying of
 * another record rather than a new one. The reference material says so itself: its L2 header reads
 * "CardDemo - Transaction altered Layout for use in reporting", and the job stanza that builds its
 * input is headed "CREATE COPY OF TRANSACT FILE WITH CARD NUMBER AND TRAN ID AS KEY" at
 * {@code app/jcl/CREASTMT.JCL} L42 to L43. {@code 01 TRNX-RECORD.} sits at L20 and is composed of
 * {@code TRNX-KEY} at L21 to L23, which is 32 characters with the card number leading, and
 * {@code TRNX-REST} at L24 to L36, which is 318, so 32 + 318 = 350.
 *
 * <p>Assumptions: the record it re-keys is {@code app/cpy/CVTRA05Y.cpy}, whose L2 header reads
 * "Data-structure for TRANsaction record (RECLN = 350)" and whose declarations sum to exactly 350
 * as well. The one difference is which field leads. That record leads with
 * {@code TRAN-ID PIC X(16)} at L5, at byte position 0 counting from zero, and holds
 * {@code TRAN-CARD-NUM PIC X(16)} at L15, at byte position 262; this one leads with
 * {@code TRNX-CARD-NUM PIC X(16)} at L22 at byte position 0 and holds {@code TRNX-ID PIC X(16)} at
 * L23. Both records total 350 characters and only the field order differs, which is why the
 * statement is produced per card and the report is not. Stating this matters because the two look
 * like duplicates until the re-keying is explained, and a reader who takes them to be
 * interchangeable computes every byte position of one from the declarations of the other and is
 * wrong by 262 on the very first field.
 *
 * <p>Assumptions: the byte positions counting from zero within {@code app/cpy/COSTM01.CPY} are the
 * key at 0 to 31, then {@code TRNX-TYPE-CD} at 32, {@code TRNX-CAT-CD} at 34,
 * {@code TRNX-SOURCE} at 38, {@code TRNX-DESC} at 48, {@code TRNX-AMT} at 148 across 11 zoned
 * characters, {@code TRNX-MERCHANT-ID} at 159, {@code TRNX-MERCHANT-NAME} at 168,
 * {@code TRNX-MERCHANT-CITY} at 218, {@code TRNX-MERCHANT-ZIP} at 268, {@code TRNX-ORIG-TS} at
 * 278, {@code TRNX-PROC-TS} at 304 and {@code FILLER} at 330 to 349. They are recorded here only
 * because the re-formatting arithmetic below rests on them; this record performs no position
 * arithmetic of its own and decodes nothing, because reading a declared-width record is the shared
 * kernel codec's charge and never a response type's.
 *
 * <p>Assumptions: the {@code X(20)} {@code FILLER} declared at L36, occupying byte positions 330 to
 * 349 counting from zero, has no component here. It is padding to the declared record length rather
 * than data, and transformation rule T1 drops it. The arithmetic is stated so the count is never
 * read as an omission: 13 named fields plus 1 {@code FILLER} is 14 declarations, and this record
 * has 13 components.
 *
 * <h2>Identifiers are digit strings, and one of them arrives masked</h2>
 *
 * <p>Assumptions: {@code TRNX-CAT-CD} at L26 is {@code PIC 9(04)} and
 * {@code TRNX-MERCHANT-ID} at L30 is {@code PIC 9(09)}, and an unsigned {@code 9(nn)} picture
 * preserves a leading zero, so a category code occupies four positions and reads {@code 0001}
 * rather than {@code 1}. A numeric component would discard those zeros silently, which is why both
 * are strings here. The baseline's own discipline corroborates it: {@code app/cpy/CVCRD01Y.cpy}
 * declares each identifier twice over the same characters, once as characters and once as a number,
 * with {@code CC-ACCT-ID PIC X(11)} at L34 redefined as {@code CC-ACCT-ID-N PIC 9(11)} at L36,
 * {@code CC-CARD-NUM PIC X(16)} at L37 redefined numerically at L39, and
 * {@code CC-CUST-ID PIC X(09)} at L40 redefined numerically at L42. The character declaration is
 * what the wire carries and the numeric one exists so arithmetic can reach the same characters. The
 * card number and the transaction identifier are {@code PIC X(16)} character fields outright, at
 * L22 and L23.
 *
 * <p>Assumptions: the card number component arrives already reduced to a mask and its trailing four
 * digits, applied at the mapping site in {@code com.carddemo.reporting.mapper}, which is the single
 * place this context permits such a decision to appear. The unreduced primary account number is
 * answered only by the administrative card-detail endpoint, which belongs to another bounded
 * context entirely and is reached by a different authority, matching the shared masking decision at
 * {@code StatementRequest} L248 to L251 and the mapping boundary at
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/dto/package-info.java}
 * L12 to L17. Two consequences follow and
 * are stated because neither is visible from the declaration. First, no digits-only constraint is
 * asserted on that component even though its source field holds digits, because the value it
 * actually carries is a mask and a digits-only assertion would reject every legitimate one.
 * Second, no rendering override is declared on this record, whereas {@code StatementRequest} in
 * this same package declares one: that type's card number is a caller-supplied selector holding the
 * unreduced value, so its generated rendering had a value worth withholding, while this type's has
 * already been reduced before it is ever constructed.
 *
 * <p>Assumptions: no card verification value is answered by any endpoint of this migration, so this
 * record has no component for one and must never acquire one. The normative roster at
 * {@code app/cpy/COSTM01.CPY} L20 to L36 contains 13 named fields plus the {@code X(20)}
 * {@code FILLER} and contains no such member. It is named here rather than left silent because a
 * reader auditing the roster against that reference record needs to see the absence as a decision.
 *
 * <h2>Money leaves this boundary as a string, and four representations coexist</h2>
 *
 * <p>Alternatives Considered: the amount is {@code com.carddemo.common.money.Money} and serialises
 * as a JSON string at scale 2 in plain decimal, never in exponent notation. The alternative
 * evaluated and rejected was a JSON number. Most clients parse a JSON number into an IEEE-754
 * binary floating point value at the boundary, so exactness that survived every earlier hop would
 * be surrendered at the one hop a user actually reads. The margin does not permit it:
 * {@code TRNX-AMT PIC S9(09)V99} at L29 carries 11 significant digits against the roughly 15 to 17
 * that binary64 offers, which leaves nothing once a client chains two operations of its own. The
 * encoding is not applied component by component -- the shared kernel registers
 * {@code com.carddemo.common.money.MoneyModule} itself, both as an auto-configured bean and through a
 * Jackson service-provider file, for the reason recorded at {@code com.carddemo.reporting.dto}, and
 * those registrations are why this component needs no serialisation annotation of its own and why no
 * class in this module declares the module at all.
 *
 * <p>Assumptions: the plain-text statement renders money three ways, and all three place the sign
 * in the LAST position and group no thousands. {@code ST-CURR-BAL} is
 * {@code PIC 9(9).99-} at {@code app/cbl/CBSTM03A.CBL} L113, 13 characters, and preserves a
 * leading zero because every digit position is a {@code 9}; {@code ST-TRANAMT}, which renders this
 * record's amount, is {@code PIC Z(9).99-} at L137, 13 characters, and blanks a leading zero; and
 * {@code ST-TOTAL-TRAMT} is {@code PIC Z(9).99-} at L142, likewise 13 and likewise blanking.
 *
 * <p>Assumptions: the {@code '$'} immediately preceding an amount is a separate one-character
 * {@code FILLER} literal and is not part of any mask -- at {@code app/cbl/CBSTM03A.CBL} L136 for
 * the transaction line and at L141 for the total line. Folding the currency symbol into a format
 * pattern moves every column after it one position along, and the failure is silent because the
 * number still reads correctly to a human.
 *
 * <p>Assumptions: four representations of one amount coexist in this bounded context and none
 * substitutes for another. They are the statement's trailing-sign, non-grouped masks named just
 * above; the report's leading-sign, comma-grouped masks, which are
 * {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} at {@code app/cpy/CVTRA07Y.cpy} L30 and
 * {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} at L54, L60 and L66; the unsigned {@code 9(nn)} form of the
 * identifiers above, which preserves a leading zero and carries no sign at all; and this API form,
 * scale 2 under {@code RoundingMode.HALF_UP}, serialised as a string. Rounding here is the general
 * contract; the truncating rounding the interest calculation requires belongs to batch-service and
 * governs nothing on this surface.
 *
 * <p>Assumptions: that count of four is a count of representations an amount takes on its way
 * through this context, and it is a different axis from the seven declared edit forms that
 * {@code com.carddemo.reporting.mapper.CobolEditMask} enumerates -- where the statement amount form
 * is its regime 7, described at its L715, and the report detail form is its regime 1, described at
 * its L289. That class carries the same warning at its L18 to L23, and it is repeated here so the
 * two numbers are never reconciled or added: they describe different things. Producing any of these
 * forms is owned by {@code com.carddemo.reporting.mapper} together with
 * {@code com.carddemo.reporting.service}, and by nothing in this record.
 *
 * <h2>Why both timestamps are opaque strings: the re-formatting step loses 22 characters</h2>
 *
 * <p>Assumptions: the statement input is prepared by a sort step whose two directives settle both
 * the ordering of this surface and the width of one of its components.
 * {@code app/jcl/CREASTMT.JCL} L53 orders the file with
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} -- two keys, the card number and then the transaction
 * identifier, both ascending and both character -- which is total and therefore deterministic, and
 * which is exactly {@code TRNX-KEY}'s own composition. L54 then re-formats each record with
 * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}.
 *
 * <p>Assumptions: read in one-based input positions, that directive fills output 1 to 16 from input
 * 263 to 278, which is the card number hoisted to the front; fills output 17 to 278 from input 1 to
 * 262, which is everything up to and including the merchant postal code; and fills output 279 to 328
 * from input 279 to 328, which is 50 characters and no more. The arithmetic is therefore
 * 16 + 262 + 50 = 328, so the step delivers 328 of the record's 350 characters and leaves the
 * remaining 22 blank. Mapped onto the byte positions counting from zero listed above, output 279 to
 * 328 is positions 278 to 327: that covers {@code TRNX-ORIG-TS} in full, at 278 to 303, but only
 * the first 24 characters of {@code TRNX-PROC-TS}, which occupies 304 to 329. Positions 328 to 349
 * -- the last 2 characters of the processing timestamp followed by the whole 20-character
 * {@code FILLER} -- arrive blank.
 *
 * <p>Assumptions: four consequences follow, and the first is the reason this record exists in the
 * shape it does. A processing timestamp reaching this surface through the statement path holds 24
 * significant characters and 2 trailing blanks, so a strict 26-character parse of it, or any parse
 * of it into a temporal object, would raise rather than return. Both timestamps are therefore
 * opaque strings at the declared {@code X(26)} width, and the symmetry is deliberate: typing one as
 * a string and the other as a temporal object would leave the pair looking inconsistent and would
 * invite a tidying change that reintroduces the failure on the component that cannot survive it. An
 * all-blank 26-character timestamp is a legitimate value in the reference data and round-trips
 * through this record unchanged. And the 20-character {@code FILLER} caught by the same truncation
 * has no component here in any case, so its loss costs nothing -- stated so the two facts are not
 * run together, because the {@code FILLER} is dropped by rule and the timestamp is shortened by the
 * re-formatting step, which are different reasons with different consequences.
 *
 * <p>Assumptions: {@code com.carddemo.common.time.TimestampFormatter} is the producer of
 * well-formed values on the write side, and its published length of 26 is the same 26 the copybook
 * declares. This record is on the read side: it accepts whatever width the data carries, validates
 * no fractional part and normalises nothing, because a response type that altered its input would
 * hide exactly the shortening documented above. Everything under {@code app/} is reference material
 * and remains unaltered; the reference job re-formats as described, this record encodes an opaque
 * carrier that tolerates the result, and the difference is entered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which owns that register -- this
 * record cites it and defines nothing in it.
 *
 * <p>Assumptions: the report path is ordered differently, and the contrast explains why only that
 * path needs a secondary key added. {@code app/jcl/TRANREPT.jcl} L46 orders with
 * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} -- a single key, with no directive forcing equal keys to
 * keep their input sequence -- so its ordering is not total and two records sharing a card number
 * may be emitted in either order. The statement path's two-key ordering at L53 already is total,
 * which is why the cursor ordering of this surface needs nothing added to it.
 *
 * <h2>Only three of the thirteen reach the plain-text artifact</h2>
 *
 * <p>Assumptions: the transaction line of the plain-text statement is {@code ST-LINE14} at
 * {@code app/cbl/CBSTM03A.CBL} L132 to L137, and it is 80 characters composed of
 * {@code ST-TRANID PIC X(16)} at L133, a one-character {@code FILLER} space at L134,
 * {@code ST-TRANDT PIC X(49)} at L135, the one-character {@code '$'} {@code FILLER} at L136 and
 * {@code ST-TRANAMT PIC Z(9).99-} at L137. Three of this record's thirteen values consequently
 * appear in that artifact: the transaction identifier, a 49-character detail field and the amount.
 *
 * <p>Trade-offs: the description is narrowed from {@code X(100)} to {@code X(49)} by that
 * rendering, and this record carries the full declared width regardless. The narrowing is visible
 * in the reference material twice over: {@code TRNX-DESC} is 100 characters at
 * {@code app/cpy/COSTM01.CPY} L28 while {@code ST-TRANDT} is 49 at L135, and even the column
 * heading above it disagrees, being the literal {@code 'Tran Details    '} at
 * {@code PIC X(51)} at L130 inside {@code ST-LINE13} at L128 to L131. The hypertext artifact is
 * built on {@code HTML-FIXED-LN PIC X(100)} at L149 and has room for more. The compromise accepted
 * is that the API answers the unnarrowed value so that a consumer is never asked to reconstruct
 * what was cut, at the cost of the API and the plain-text artifact disagreeing about one field's
 * width -- a disagreement recorded here rather than resolved, because narrowing the API to 49 would
 * lose reference data and widening the artifact would change bytes a golden comparison keeps
 * unchanged.
 *
 * <p>Assumptions: against the three-value transaction line at
 * {@code app/cbl/CBSTM03A.CBL} L132 to L137, the remaining nine values -- the type code, the
 * category code, the source, the four merchant fields and both timestamps -- appear in neither
 * artifact, and are carried anyway because the consumer of this record is not the statement
 * artifact. Omitting them would force a consumer that needs a merchant name or a category to issue
 * a second request for a row it already holds, which is a cost paid on every row to save nothing.
 *
 * <h2>This record is the element type of the shared page envelope, and never imports it</h2>
 *
 * <p>Assumptions: a page of these rows is answered inside
 * {@code com.carddemo.common.web.PageResponse}, the single such envelope in the reactor, whose
 * FOUR components and one type parameter carry the rows together with the two sealed boundary
 * positions and the single more-to-come flag -- {@code items}, {@code firstKey}, {@code lastKey} and
 * {@code hasNext}, declared at
 * {@code services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java} L231 to L235.
 * This record is the element type that envelope is parameterised with, and the controller composes
 * the envelope; so the envelope is named in this prose and is deliberately not imported, because an
 * import that no declaration here needs would assert a dependency edge that does not exist. Its
 * boundary positions are sealed tokens, and its more-to-come flag is settled by the one-extra-row
 * contract rather than by counting the matching rows.
 *
 * <p>Refactoring Rationale: this paragraph said SEVEN components and two more-to-come flags, and
 * cited lines that do not carry the declaration. Both were wrong: the envelope has four components
 * and one flag. The correction is recorded rather than made silently because the inflated figure was
 * not harmless -- a reader building a client against this prose would have expected a backward
 * more-to-come flag that does not exist, and would have had to discover from behaviour that a
 * backward-reached page reports its forward flag unconditionally instead.
 *
 * <p>Assumptions: the cursor ordering on this surface is the card number then the transaction
 * identifier, which is exactly {@code TRNX-KEY}'s composition at
 * {@code app/cpy/COSTM01.CPY} L21 to L23 and exactly the two-key ordering the reference job already
 * applies at {@code app/jcl/CREASTMT.JCL} L53. Reading forward seeks keys strictly greater than the
 * trailing boundary, ascending, which is what the reference browse's read-next does; reading
 * backward seeks keys strictly less than the leading boundary, descending, which is its read-previous.
 * In the original keyboard workflow PF7 read backward and PF8 forward. That the reference job's own
 * ordering is already this exact key pair is the strongest evidence available that a key-based
 * cursor is a faithful mapping of the browse rather than a substitute for it, which is why L53 is
 * cited here and not merely in the ordering discussion above.
 *
 * <p>Assumptions: the reference material corroborates key-based paging independently.
 * {@code app/cbl/CBSTM03B.CBL} is a subprogram rather than a job -- its
 * {@code PROCEDURE DIVISION USING LK-M03B-AREA} is at L114 -- and it declares
 * {@code LK-M03B-KEY-LN PIC S9(4)} at L111, a significant-key-length generic browse, so the
 * baseline itself positions by partial key. Its {@code LK-M03B-KEY} at L110 is 25 characters
 * against the 32 of {@code TRNX-KEY}, which is an observation about the reference artifact and not
 * an asserted defect in it. It dispatches first on the data-definition name, at
 * {@code EVALUATE LK-M03B-DD} at L118, and then on one of six operation codes declared at L103 to
 * L108 -- open, close, read, keyed read, write and rewrite. Because this context reads and never
 * writes, only the first four are ever reached; the write and rewrite codes belong to contexts that
 * own their data.
 *
 * <p>Assumptions: no ordinal paging member, parameter or accessor appears here, and no prose here
 * implies one exists, matching
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/dto/package-info.java}
 * L190 to L183.
 * An ordinal cursor loses and repeats rows once a concurrent insert shifts positions between two
 * reads, which is a change in observable behaviour that a key-based browse does not produce -- and
 * that, rather than any claim about speed, is the whole of the reason. Two reference browse fields
 * are absent for the same reason and must not reappear: {@code WS-CA-SCREEN-NUM}, a counter, and
 * {@code WS-CA-LAST-PAGE-DISPLAYED}, whose polarity inverts the reading its name suggests because
 * shown is 0 and not-shown is 9. The carve-out is stated so it is not mistaken for a lapse: the
 * words page, report page, page total and grand total are domain vocabulary in this context, naming
 * a property of an output layout that has to be reproduced character for character, and they name
 * nothing of that kind.
 *
 * <p>Assumptions: this record carries no repeat-entry marker of any kind, so no resubmission flag,
 * no first-entry flag and no turn counter. The reference session structure's
 * {@code CDEMO-PGM-CONTEXT} discriminator is eliminated rather than ported, for the reason recorded
 * at {@code services/reporting-service/src/main/java/com/carddemo/reporting/dto/package-info.java}
 * L156 to L166: its value is remembered state that only means anything because a task ends at every
 * screen turn, and a handler answering field errors in a response body has nothing to remember.
 *
 * <p>Assumptions: this record is not an error carrier either. A failure surfaces through
 * {@code com.carddemo.common.error.ApiError} with its per-field entries from
 * {@code com.carddemo.common.validation.FieldValidationFlag}, and an abend through
 * {@code com.carddemo.common.error.AbendDetail}, whose four structured members are declared at
 * {@code app/cpy/CSMSG02Y.cpy} L21 to L29 at widths 4, 8, 50 and 72. That file ends at L35, so a
 * citation past it names nothing.
 *
 * <h2>Ownership, and where a declared width is enforced</h2>
 *
 * <p>Assumptions: this context owns no table, no index and no database schema-migration artifact,
 * and reads read-only cross-schema projections under a role holding read access alone, as recorded
 * at {@code services/reporting-service/src/main/java/com/carddemo/reporting/dto/package-info.java}
 * L61 to L75. So this record carries no version marker for optimistic concurrency and no
 * identifier a caller is expected to supply, because there is nothing in this context to write.
 *
 * <p>Alternatives Considered: each component's declared width is asserted once, by the
 * {@code @Size} constraint in the header, and the constructor below deliberately does not repeat
 * the check. Repeating it was the alternative, and it is rejected on the ground
 * {@code StatementRequest} L192 to L220 already records for the request half: two executable
 * positions for one declared width are what let a caller be published one contract and held to
 * another. The constraint is the single authority for width, and the constructor asserts only the
 * three things a constraint cannot express -- that the two components forming the cursor key carry
 * content, and that the amount is present.
 *
 * <p>Alternatives Considered: the constructor validates and never transforms, so every component is
 * stored exactly as supplied. Trimming trailing padding was evaluated and rejected, and the
 * rejection is specific rather than stylistic: two of the thirteen components must round-trip a
 * value that is partly or wholly blank, because the processing timestamp arrives from the statement
 * path with its last 2 positions blank per {@code app/jcl/CREASTMT.JCL} L54 and an all-blank
 * timestamp is legitimate reference data. A trimming rule general enough to apply to all thirteen
 * would destroy those two, and a rule applying to eleven of thirteen would make this record's
 * contract depend on which component a reader happens to hold. Removing padding is instead the
 * charge of {@code com.carddemo.reporting.mapper}, which is where this context's anti-corruption
 * boundary already sits and where such a decision has one auditable site.
 *
 * @param cardNumber the {@code String} card number the statement line belongs to, already reduced
 *     to a mask and its trailing four digits, and the leading component of this surface's cursor
 *     ordering; its source field is {@code TRNX-CARD-NUM PIC X(16)} at
 *     {@code app/cpy/COSTM01.CPY} L22, and a value carrying no content is refused because a row
 *     whose key cannot be named cannot be paged
 * @param transactionId the {@code String} identifier of the transaction the line reports, and the
 *     trailing component of the cursor ordering; its source field is {@code TRNX-ID PIC X(16)} at
 *     L23, it is the one value of the thirteen that the plain-text artifact prints first, and a
 *     value carrying no content is refused for the same paging reason as the card number
 * @param typeCode the {@code String} transaction type code as
 *     {@code TRNX-TYPE-CD PIC X(02)} declares it at L25, a two-character code carried verbatim; it
 *     reaches neither the plain-text nor the hypertext artifact and is present because an API
 *     consumer is not the statement artifact
 * @param categoryCode the {@code String} transaction category code as
 *     {@code TRNX-CAT-CD PIC 9(04)} declares it at L26, held as four digit characters rather than
 *     as a number so that a leading zero survives and {@code 0001} does not arrive as {@code 1}
 * @param source the {@code String} origin of the transaction as
 *     {@code TRNX-SOURCE PIC X(10)} declares it at L27, ten characters carried verbatim with
 *     whatever padding the reference data holds
 * @param description the {@code String} transaction description at its full declared width, which
 *     is {@code TRNX-DESC PIC X(100)} at L28; the plain-text artifact narrows it to 49 characters
 *     and this component deliberately does not, for the reason recorded under the rendering heading
 * @param amount the {@code Money} amount of the transaction, held as an exact scaled decimal and
 *     serialised as a JSON string; its source field is {@code TRNX-AMT PIC S9(09)V99} at L29, an
 *     11-significant-digit signed zoned quantity, and it is refused when absent because the
 *     artifact's total line at {@code app/cbl/CBSTM03A.CBL} L142 sums these values and cannot sum a
 *     missing one
 * @param merchantId the {@code String} merchant identifier as
 *     {@code TRNX-MERCHANT-ID PIC 9(09)} declares it at L30, held as nine digit characters for the
 *     same leading-zero reason as the category code; a numeric column behind this surface holds it
 *     without those zeros, and restoring it to nine positions happens at the mapping site rather
 *     than here
 * @param merchantName the {@code String} merchant name as
 *     {@code TRNX-MERCHANT-NAME PIC X(50)} declares it at L31, 50 characters carried verbatim
 * @param merchantCity the {@code String} merchant city as
 *     {@code TRNX-MERCHANT-CITY PIC X(50)} declares it at L32, 50 characters carried verbatim; it
 *     shares its width with the merchant name and is bounded by its own named constant regardless,
 *     because two declarations that happen to agree are not one declaration
 * @param merchantZip the {@code String} merchant postal code as
 *     {@code TRNX-MERCHANT-ZIP PIC X(10)} declares it at L33, ten characters carried verbatim and
 *     never narrowed to five, because the declared width is what the reference record holds
 * @param originTimestamp the {@code String} originating timestamp as
 *     {@code TRNX-ORIG-TS PIC X(26)} declares it at L34, carried as an opaque 26-character value
 *     and neither parsed nor normalised; the re-formatting step delivers this one intact, and it is
 *     nonetheless a string so that it cannot drift apart from its processing counterpart
 * @param processingTimestamp the {@code String} processing timestamp as
 *     {@code TRNX-PROC-TS PIC X(26)} declares it at L35, carried as an opaque 26-character value;
 *     through the statement path it arrives with 24 significant characters and 2 trailing blanks,
 *     and an all-blank value is legitimate, so this component is neither parsed nor normalised and
 *     round-trips exactly what it was given
 */
public record StatementTransactionResponse(
        @Size(max = CARD_NUMBER_WIDTH) String cardNumber,
        @Size(max = TRANSACTION_ID_WIDTH) String transactionId,
        @Size(max = TYPE_CODE_WIDTH) String typeCode,
        @Size(max = CATEGORY_CODE_WIDTH) String categoryCode,
        @Size(max = SOURCE_WIDTH) String source,
        @Size(max = DESCRIPTION_WIDTH) String description,
        Money amount,
        @Size(max = MERCHANT_ID_WIDTH) String merchantId,
        @Size(max = MERCHANT_NAME_WIDTH) String merchantName,
        @Size(max = MERCHANT_CITY_WIDTH) String merchantCity,
        @Size(max = MERCHANT_ZIP_WIDTH) String merchantZip,
        @Size(max = TIMESTAMP_WIDTH) String originTimestamp,
        @Size(max = TIMESTAMP_WIDTH) String processingTimestamp) {

    // WHY : Assumptions: 16 is the width TRNX-CARD-NUM PIC X(16) declares at app/cpy/COSTM01.CPY
    //       L22. The constant bounds the component even though the value arriving here is a mask
    //       and therefore shorter, because the width published to a consumer has to be the width
    //       the reference field declares rather than the length of the reduced form -- a masking
    //       decision is the mapping site's to make and revisit, and a schema pinned to its current
    //       output would contradict the record the moment that decision moved.
    private static final int CARD_NUMBER_WIDTH = 16;

    // WHY : Assumptions: 16 is the width TRNX-ID PIC X(16) declares at L23, and it is declared
    //       separately from the card number above rather than shared with it. The two agree by
    //       coincidence of the reference layout, not by a common contract: they are different
    //       fields on different lines, and one changing would have to leave the other alone.
    private static final int TRANSACTION_ID_WIDTH = 16;

    // WHY : Assumptions: 2 is the width TRNX-TYPE-CD PIC X(02) declares at L25.
    private static final int TYPE_CODE_WIDTH = 2;

    // WHY : Assumptions: 4 is the digit count TRNX-CAT-CD PIC 9(04) declares at L26, and it bounds
    //       a string rather than a number precisely so that all four positions survive. An
    //       unsigned picture preserves a leading zero, so the width is also the exact length every
    //       legitimate value has.
    private static final int CATEGORY_CODE_WIDTH = 4;

    // WHY : Assumptions: 10 is the width TRNX-SOURCE PIC X(10) declares at L27.
    private static final int SOURCE_WIDTH = 10;

    // WHY : Assumptions: 100 is the width TRNX-DESC PIC X(100) declares at L28, and it is the
    //       width this component publishes even though the plain-text artifact prints only 49 of
    //       those characters. Bounding at 49 would make the API unable to carry a value the
    //       reference record legitimately holds.
    private static final int DESCRIPTION_WIDTH = 100;

    // WHY : Assumptions: 9 is the digit count TRNX-MERCHANT-ID PIC 9(09) declares at L30, bounding
    //       a string for the same leading-zero reason as the category code above.
    private static final int MERCHANT_ID_WIDTH = 9;

    // WHY : Assumptions: 50 is the width TRNX-MERCHANT-NAME PIC X(50) declares at L31.
    private static final int MERCHANT_NAME_WIDTH = 50;

    // WHY : Assumptions: 50 is the width TRNX-MERCHANT-CITY PIC X(50) declares at L32. It is a
    //       second constant holding the same number as the merchant name above for the reason
    //       given on the transaction identifier: agreeing declarations are not a shared contract.
    private static final int MERCHANT_CITY_WIDTH = 50;

    // WHY : Assumptions: 10 is the width TRNX-MERCHANT-ZIP PIC X(10) declares at L33.
    private static final int MERCHANT_ZIP_WIDTH = 10;

    // WHY : Assumptions: 26 is the width both TRNX-ORIG-TS at L34 and TRNX-PROC-TS at L35 declare,
    //       and one constant serves both. These two genuinely do share a contract, unlike the
    //       coincidental pairs above: they are the same kind of quantity at the same width, and
    //       com.carddemo.common.time.TimestampFormatter publishes that same 26 as the single length
    //       its rendered form occupies. A value of 24 significant characters followed by 2 blanks
    //       still occupies 26 positions, so this bound admits the truncated form the statement path
    //       delivers without needing to know about it.
    private static final int TIMESTAMP_WIDTH = 26;

    /**
     * Refuses a row that could not be named inside a page or totalled on a statement.
     *
     * <p>The constructor accepts the thirteen record components documented by the type-level
     * component tags above, in declaration order. The compact canonical form repeats no parameter
     * list, and as a constructor it returns no separate value; successful completion creates this
     * record with every component stored exactly as supplied.
     *
     * <p>Assumptions: the three checks below are the complete set, and each one guards a property
     * that a bean-validation constraint on the header cannot express: the two key members declared
     * at {@code app/cpy/COSTM01.CPY} L21 to L23 must carry content, and the amount totalled at
     * {@code app/cbl/CBSTM03A.CBL} L142 must be present. They are asserted here rather than trusted
     * to a caller because an instance cannot come into being without passing through this
     * constructor, which is what makes the guarantee hold by construction instead of by every
     * mapping site remembering it.
     *
     * <p>Alternatives Considered: this constructor throws where {@code StatementRequest} L192 to
     * L220 deliberately does not, and the asymmetry is intended. That type is bound from a caller's
     * request body, so a violation is the caller's and has to arrive as a per-field entry naming the
     * offending field, which an exception raised during binding cannot do -- it surfaces as an
     * unstructured malformed-body failure instead. This type is constructed by the mapping layer
     * from a projection this service itself read, so a violation is a defect on this side of the
     * boundary with no caller field to name, and there is no per-field response that could usefully
     * carry it. Failing at construction keeps a row that no page could describe from ever reaching
     * a consumer, which is the outcome that matters here.
     *
     * <p>Assumptions: no message raised below quotes the value it rejected. The card number is the
     * {@code X(16)} member at {@code app/cpy/COSTM01.CPY} L22, and the shared masking decision at
     * {@code StatementRequest} L248 to L251 reveals only its trailing four digits, so naming the
     * component and withholding its content keeps that discipline from being defeated through a
     * diagnostic.
     *
     * @throws IllegalArgumentException if {@code cardNumber} or {@code transactionId} is
     *     {@code null} or carries no content, because those two are the ordered cursor key of this
     *     surface and a row whose key cannot be stated cannot be positioned in a page or read
     *     forward from; or if {@code amount} is {@code null}, because the artifact's total line at
     *     {@code app/cbl/CBSTM03A.CBL} L142 sums these values and an absent amount is not a zero
     */
    public StatementTransactionResponse {
        // WHY : Assumptions: the two key components are checked for content rather than merely for
        //       presence, because a blank of the declared width is a value the reference record can
        //       hold and is still not a key. The shared page envelope refuses a page whose ends it
        //       cannot name, so a blank key admitted here would be rejected one layer out by the
        //       shared envelope's L290 to L294 guard, with the page as the reported subject rather
        //       than the row -- much further from the mapping site that produced it.
        if (cardNumber == null || cardNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "cardNumber is the leading component of the statement cursor key and must "
                            + "carry content");
        }

        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException(
                    "transactionId is the trailing component of the statement cursor key and must "
                            + "carry content");
        }

        // WHY : Assumptions: an absent amount is refused rather than defaulted to zero. Zero is a
        //       meaningful amount on a statement line and it is summed into ST-TOTAL-TRAMT at
        //       app/cbl/CBSTM03A.CBL L142, so substituting it for a missing value would produce a
        //       statement that balances against nothing and reports no fault -- the failure would be
        //       silent, which is the one kind this context cannot afford in a monetary path.
        if (amount == null) {
            throw new IllegalArgumentException(
                    "amount must be present because the statement total sums every line");
        }
    }

    /**
     * Returns the masked card number that leads the statement cursor key.
     *
     * @return a {@code String} containing the masked card number and its trailing four digits
     */
    public String cardNumber() {
        return cardNumber;
    }

    /**
     * Returns the transaction identifier that completes the statement cursor key.
     *
     * @return a {@code String} containing the transaction identifier
     */
    public String transactionId() {
        return transactionId;
    }

    /**
     * Returns the transaction type code carried by the reference record.
     *
     * @return a {@code String} containing the transaction type code
     */
    public String typeCode() {
        return typeCode;
    }

    /**
     * Returns the four-character transaction category code.
     *
     * @return a {@code String} containing the category code with any leading zeros preserved
     */
    public String categoryCode() {
        return categoryCode;
    }

    /**
     * Returns the transaction source exactly as supplied by the mapping boundary.
     *
     * @return a {@code String} containing the transaction source
     */
    public String source() {
        return source;
    }

    /**
     * Returns the transaction description at its full declared API width.
     *
     * @return a {@code String} containing the unrendered transaction description
     */
    public String description() {
        return description;
    }

    /**
     * Returns the exact scaled amount carried by this statement line.
     *
     * @return the non-null {@code Money} transaction amount
     */
    public Money amount() {
        return amount;
    }

    /**
     * Returns the nine-character merchant identifier.
     *
     * @return a {@code String} containing the merchant identifier with any leading zeros preserved
     */
    public String merchantId() {
        return merchantId;
    }

    /**
     * Returns the merchant name exactly as supplied by the mapping boundary.
     *
     * @return a {@code String} containing the merchant name
     */
    public String merchantName() {
        return merchantName;
    }

    /**
     * Returns the merchant city exactly as supplied by the mapping boundary.
     *
     * @return a {@code String} containing the merchant city
     */
    public String merchantCity() {
        return merchantCity;
    }

    /**
     * Returns the merchant postal code at its declared width.
     *
     * @return a {@code String} containing the merchant postal code
     */
    public String merchantZip() {
        return merchantZip;
    }

    /**
     * Returns the opaque originating timestamp without parsing or normalisation.
     *
     * @return a {@code String} containing the originating timestamp exactly as supplied
     */
    public String originTimestamp() {
        return originTimestamp;
    }

    /**
     * Returns the opaque processing timestamp without parsing or normalisation.
     *
     * @return a {@code String} containing the processing timestamp exactly as supplied, including
     *     trailing blanks
     */
    public String processingTimestamp() {
        return processingTimestamp;
    }

    /**
     * The stand-in a withheld value is rendered as.
     */
    private static final String REDACTED = "REDACTED";

    /**
     * Renders this transaction line without the card number, the amount or the merchant free text.
     *
     * <p>Refactoring Rationale: this override exists because the record-generated {@code toString}
     * renders a primary account number, a monetary amount and four merchant fields including a free-text
     * name. A page of these rows is the element type of the shared envelope, so a single interpolation of
     * a page into a diagnostic renders every row it holds -- which makes this the highest-volume
     * disclosure of the five records corrected together, not merely one more of them.</p>
     *
     * <p>Assumptions: the merchant NAME and CITY are withheld while the merchant IDENTIFIER is not.
     * The identifier is an opaque code from a closed set that an operator needs in order to
     * attribute a posting problem to a merchant; the name and city are free text a cardholder's own
     * activity is described by, and a name plus a city plus an amount on one line describes where a
     * person was and what they spent.</p>
     *
     * <p>Assumptions: the transaction identifier, the two codes, the source and the two timestamps ARE
     * rendered. Those six are what a posting or statement defect is actually investigated with -- they
     * locate the row, name the rule that applied to it and place it in the processing window -- and none
     * of them is attributable to a person once the card number and the amount are withheld.</p>
     *
     * <p>Assumptions: the DESCRIPTION is withheld even though it looks like a bounded code field. It is
     * not: the reference carries a free-text description that includes values composed at run time, and
     * a description is the field most likely to have had a merchant name or an account reference written
     * into it by an upstream producer.</p>
     *
     * @return a rendering safe to write to any log or exception message, never {@code null}
     */
    @Override
    public String toString() {
        return "StatementTransactionResponse[cardNumber=" + REDACTED
                + ", transactionId=" + transactionId
                + ", typeCode=" + typeCode
                + ", categoryCode=" + categoryCode
                + ", source=" + source
                + ", description=" + REDACTED
                + ", amount=" + REDACTED
                + ", merchantId=" + merchantId
                + ", merchantName=" + REDACTED
                + ", merchantCity=" + REDACTED
                + ", merchantZip=" + REDACTED
                + ", originTimestamp=" + originTimestamp
                + ", processingTimestamp=" + processingTimestamp
                + "]";
    }
}
