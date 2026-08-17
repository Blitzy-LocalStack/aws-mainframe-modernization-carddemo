package com.carddemo.transaction.mapper;

import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.PageResponse;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.BillPaymentResponse;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.dto.TransactionDetailResponse;
import com.carddemo.transaction.dto.TransactionListItemResponse;
import com.carddemo.transaction.dto.TransactionListRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Converts between the ledger context's persistence entity and the transfer objects the three
 * migrated transaction screens exchange.
 *
 * <p>Every crossing between {@code com.carddemo.transaction.domain.Transaction} and the
 * {@code com.carddemo.transaction.dto} records happens here, so that the reference record format
 * reaches this class and stops. The record being carried is {@code 01 TRAN-RECORD} at
 * {@code app/cpy/CVTRA05Y.cpy}, whose header line 2 declares its length as 350 and whose thirteen
 * named fields occupy lines 5 to 17. Its declared widths, in order, are 16, 2, 4, 10, 100, 11, 9,
 * 50, 50, 10, 16, 26 and 26, which places the card number at zero-based offset 262, the originating
 * timestamp at 278 and the processing timestamp at 304.
 *
 * <p>Assumptions: those offsets are corroborated by an unrelated artifact rather than only by the
 * arithmetic above. Lines 41 and 42 of {@code app/jcl/TRANREPT.jcl} declare
 * {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH} in one-based positions, which
 * are the same two offsets. Two independent sources agreeing is what makes every offset-dependent
 * decision below checkable instead of re-derivable.
 *
 * <h2>One. {@code FILLER} is dropped, and the drop is recorded here</h2>
 *
 * <p>Assumptions: line 18 of that copybook declares {@code 05 FILLER PIC X(20)}, and those twenty
 * bytes pad the record to its declared length of 350 rather than carrying a value. They have no
 * column, no entity member and no transfer-object component, so this class maps thirteen fields out
 * of a fourteen-item record. The drop is stated rather than left implicit because it is the one
 * transformation that makes the target narrower than the source: a reader reconciling a 350-byte
 * record against a thirteen-component list needs the twenty bytes accounted for. The declared
 * record length is the contract; the padding is not part of it.
 *
 * <h2>Two. The primary account number is masked in every response</h2>
 *
 * <p>Trade-offs: the baseline renders the full sixteen-digit primary account number. Line 179 of
 * {@code app/cbl/COTRN01C.cbl} moves {@code TRAN-CARD-NUM} straight into the screen field that line
 * 72 of {@code app/cpy-bms/COTRN01.CPY} declares {@code CARDNUMI PIC X(16)}, so an operator
 * reaching the reference detail screen sees all sixteen digits. The Java masks to the last four
 * digits in every response, and the divergence is documented. What is given up is that a caller
 * reading one transaction no longer obtains the whole number from this context; what is gained is
 * that the number stops appearing in every response body, log line and browser cache a detail view
 * touches, and the reference screen's audience was a terminal inside a controlled network whereas
 * these responses cross a public edge. A caller that needs the whole number obtains it from the
 * card context's own detail endpoint.
 *
 * <p>Assumptions: {@code CVTRA05Y} declares no card verification value, so this class has no such
 * path at all and suppresses nothing here. The prohibition on returning one is recorded because
 * this class is the only place a value joined from another context could enter one of these
 * responses, which makes it the place a later author reads the rule before writing the join.
 *
 * <h2>Three. Money crosses as exact decimal and leaves as a JSON string</h2>
 *
 * <p>Assumptions: {@code TRAN-AMT} is declared {@code PIC S9(09)V99} at line 10 of that copybook,
 * which is zoned decimal with sign overpunch rather than packed decimal, so the sign travels inside
 * the final digit byte. The seed data shows the encoding directly: in
 * {@code app/data/ASCII/dailytran.txt} the eleven amount bytes of the first record read
 * {@code 0000005047G}, whose trailing {@code G} carries the digit 7 together with a positive sign
 * and therefore denotes +504.77, while the second record's amount ends in a right-brace character
 * carrying the digit 0 together with a negative sign and therefore denotes -919.00. A negative
 * amount appears in the second of three hundred records rather than in a rare case. The decoding is
 * done once by the shared kernel and is never re-implemented here.
 *
 * <p>Alternatives Considered: transporting the amount as a JSON number, which is the obvious
 * alternative and is rejected. Most clients parse a JSON number into a binary double, and a value
 * such as 504.77 has no exact binary representation, so the exactness that the
 * {@code NUMERIC(11,2)} column and the scale-2 {@code BigDecimal} both maintain would be discarded
 * at the last hop, where nothing downstream could detect the loss. The cost accepted is that every
 * client performs an explicit parse and the payload is marginally larger. The amount components of
 * these records are therefore declared {@code Money} and never {@code BigDecimal}, because
 * {@code com.carddemo.common.money.MoneyModule} binds its quoted-string serialiser to the
 * {@code Money} type: a {@code BigDecimal} component compiles, runs and silently emits a JSON
 * number. Neither binary floating-point type, nor either of their wrappers, appears anywhere in
 * this class.
 *
 * <h2>Four. An identifier is a digits-validated string, never a number</h2>
 *
 * <p>Alternatives Considered: a numeric Java type for the transaction identifier and the card
 * number, rejected on evidence in the data. Lines 204 to 207 of {@code app/cbl/COTRN02C.cbl}
 * compute {@code WS-ACCT-ID-N}, declared {@code PIC 9(11)} at line 55, from {@code FUNCTION NUMVAL}
 * of the screen field and then move the numeric result back onto that same {@code X(11)} field;
 * lines 218 to 221 do the same for {@code WS-CARD-NUM-N}, declared {@code PIC 9(16)} at line 56,
 * and its {@code X(16)} field. Moving a numeric item onto a character field of matching width
 * left-pads it with zeros, so the reference program deliberately produces a fully zero-padded
 * identifier, and lines 448 to 451 generate a new transaction identifier by exactly that route.
 * The consequence is measurable: 30 of the 300 records in that seed file carry a card number
 * beginning with a zero at the sixteen bytes starting at offset 262, among them
 * {@code 0***********6232}, {@code 0***********5740} and {@code 0***********7330}, and the first
 * record's transaction identifier reads {@code 0000000000683580}. Trade-offs: those three are shown
 * with their twelve interior digits masked, in the same twelve-asterisk form the response contract
 * publishes, because the sentence needs only two properties of them -- that the first character is a
 * zero and that they are sixteen positions wide -- and reproducing the remaining digits would put
 * three usable primary account numbers into production documentation to make a point about padding.
 * The transaction identifier beside them is not a card number and is shown whole. A numeric type
 * would discard
 * those leading zeros and turn a sixteen-character identifier into a shorter number that no longer
 * matches the value stored, printed or indexed.
 *
 * <p>Assumptions: the X-over-9 {@code REDEFINES} pairing in {@code app/cpy/CVCRD01Y.cpy} --
 * {@code CC-ACCT-ID PIC X(11)} at line 34 redefined {@code PIC 9(11)} at line 36,
 * {@code CC-CARD-NUM PIC X(16)} at line 37 redefined {@code PIC 9(16)} at line 39, and
 * {@code CC-CUST-ID PIC X(09)} at line 40 redefined {@code PIC 9(9)} at line 42, all three
 * character forms carrying {@code VALUE SPACES} -- corroborates the same treatment: character on
 * the wire, numeric only for arithmetic. It is cited as a house idiom and nothing more, because
 * none of the four programs migrated into this module copies that book. A search for its
 * {@code COPY} statement across the reference programs matches five, and all five belong to the
 * account and card contexts.
 *
 * <h2>No field is renamed in this module</h2>
 *
 * <p>Assumptions: every name this class produces matches its copybook, and the absence of a rename
 * is a decision rather than an oversight. A reader who knows the neighbouring services will arrive
 * expecting a spelling change, because the wider migration does rename three misspelled baseline
 * names in its own target columns; all three belong elsewhere. {@code ACCT-EXPIRAION-DATE} belongs
 * to the account context, {@code CARD-EXPIRAION-DATE} to the card context and
 * {@code PA-MERCHANT-CATAGORY-CODE} to the authorization context. None of the three occurs in
 * {@code app/cpy/CVTRA05Y.cpy}, so there is nothing here to rename, and a rename introduced for
 * symmetry would invent a divergence from the reference layout in the one class whose purpose is to
 * absorb such differences rather than create them.
 *
 * <h2>Three 26-character timestamp forms, which are never normalised together</h2>
 *
 * <p>Assumptions: three different values reach a 26-character timestamp field in the reference
 * material, and collapsing them onto one another is a silent data error rather than a tidy-up.
 * First, a date-only value padded to width: lines 464 and 465 of {@code app/cbl/COTRN02C.cbl} move
 * two {@code PIC X(10)} screen fields -- {@code TORIGDTI} at line 102 and {@code TPROCDTI} at line
 * 108 of {@code app/cpy-bms/COTRN02.CPY} -- into the two {@code PIC X(26)} record fields, and a
 * ten-byte value moved into a twenty-six-byte character field is followed by sixteen spaces, so a
 * transaction captured through that screen carries a date and no time and a
 * microsecond-precision column necessarily renders midnight for it. Second, a zero-microsecond
 * value: line 266 of {@code app/cbl/COBIL00C.cbl} moves zeros into the microsecond component.
 * Third, a database-format value: lines 437 and 438 of {@code app/cbl/CBTRN02C.cbl} obtain one and
 * move it into the processing timestamp, which is the form a posted row carries. Only the first of
 * the three is produced by this class; the other two are read, and are named so that a later author
 * does not normalise a value this class never wrote.
 *
 * <p>Assumptions: the 26-byte group is {@code app/cpy/CSDAT01Y.cpy} lines 42 to 55, whose declared
 * widths sum to exactly 26 -- four for the year, two each for month, day, hour, minute and second,
 * six for the microseconds and one each for the six separators. Those separators survive only
 * because {@code INITIALIZE} leaves {@code FILLER} items untouched, so the value clauses at line 48
 * -- a space, at byte 11 -- and at line 54 -- a period, at byte 20 -- outlive an initialization
 * that clears every named component around them. A formatter emitting the digits and assuming the
 * punctuation follows produces a 26-byte value whose separator positions are blank, which parses as
 * neither a date nor a timestamp. Rendering therefore goes through
 * {@code com.carddemo.common.time.TimestampFormatter}, which emits the whole pattern, and no
 * pattern is declared here.
 *
 * <h2>The message width the target adopts, and the three it does not</h2>
 *
 * <p>Refactoring Rationale: the baseline's width regime is replaced, and what was wrong with it is
 * stated rather than glossed. Four widths coexist. Each of the four migrated programs declares an
 * internal work field {@code WS-MESSAGE PIC X(80)}, at line 38 of {@code app/cbl/COTRN00C.cbl},
 * {@code app/cbl/COTRN01C.cbl} and {@code app/cbl/COTRN02C.cbl}. Each renders it into a screen
 * field declared {@code PIC X(78)} -- {@code ERRMSGO PIC X(78)} at line 272 of
 * {@code app/cpy-bms/COTRN01.CPY} and of {@code app/cpy-bms/COTRN02.CPY}, and at line 728 of
 * {@code app/cpy-bms/COTRN00.CPY}, reached by the move at line 520 of {@code COTRN02C} -- so two
 * bytes of an eighty-character message are dropped on the way out and no null state is
 * representable at all. The Java adopts the seventy-five-character {@code CCARD-RETURN-MSG} form at
 * line 29 of {@code app/cpy/CVCRD01Y.cpy}, whose {@code LOW-VALUES} condition at line 30 maps to an
 * absent message rather than to a blank one, and the divergence is documented. The fourth width,
 * {@code PIC X(50)}, belongs to the shared message constants in {@code app/cpy/CSMSG01Y.cpy} and is
 * named only so that it is not mistaken for this one.
 *
 * <p>Assumptions: that condition attaches to the return message at line 29 alone.
 * {@code CCARD-ERROR-MSG} at line 28 of the same book carries no such condition, so an error
 * message has no sentinel and a blank error message is a blank error message. Mapping
 * {@code LOW-VALUES} onto a blank string would make an unset return message indistinguishable from
 * a deliberately empty one, which is exactly the distinction the condition name exists to draw, so
 * absence is represented as {@code null} and blankness is collapsed onto it through
 * {@code com.carddemo.common.validation.FieldValidationFlag}.
 *
 * <h2>Every user-visible string is passed through untouched</h2>
 *
 * <p>Alternatives Considered: a message catalogue declared on this class, evaluated and rejected.
 * The validation messages of {@code app/cbl/COTRN02C.cbl} are already published as constants by
 * {@code com.carddemo.transaction.dto.TransactionAddRequest}, and the selection of an outcome
 * message belongs to {@code com.carddemo.transaction.service}, so a second copy here could drift
 * from the first while both went on compiling -- the duplication transformation rule T2 exists to
 * foreclose. What this class owes rule T8 instead is that it never re-words, re-cases, re-spaces or
 * truncates a message it is handed, and the reason is that neighbouring reference strings differ by
 * one byte.
 *
 * <p>Assumptions: the paging outcomes of {@code app/cbl/COTRN00C.cbl} are five distinct strings and
 * not two. They are {@code 'You are already at the top of the page...'} at line 248,
 * {@code 'You are already at the bottom of the page...'} at line 270,
 * {@code 'You are at the top of the page...'} at line 608 with no "already",
 * {@code 'You have reached the bottom of the page...'} at line 642 and
 * {@code 'You have reached the top of the page...'} at line 676. Casing diverges too: that program
 * spells its lookup failure with a lower-case noun, {@code 'Unable to lookup transaction...'}, at
 * lines 615, 649 and 683, whereas the same failure is spelled with an upper-case noun,
 * {@code 'Unable to lookup Transaction...'}, at line 292 of {@code app/cbl/COTRN01C.cbl} and at
 * lines 664 and 693 of {@code app/cbl/COTRN02C.cbl}. Spacing diverges as well:
 * {@code 'Tran ID must be Numeric ...'} at line 214 of the list program carries a space before its
 * ellipsis where {@code 'Account ID must be Numeric...'} at line 199 of the add program carries
 * none, and {@code 'Invalid selection. Valid value is S'} at line 199 of the list program carries
 * no ellipsis at all. Both spellings and all five paging strings survive because this class treats a
 * message as opaque, and a well-meant consolidation anywhere on this path would silently re-word a
 * screen.
 *
 * <h2>A rejected submission is reported as a field error, not as a screen attribute</h2>
 *
 * <p>Assumptions: transformation rule T7 turns the baseline's validation-flag pattern into the
 * per-field error array on {@code com.carddemo.common.error.ApiError}, expressed through
 * {@code FieldValidationFlag} and preserving the asterisk that
 * {@code FieldValidationFlag.BLANK_SCREEN_MARKER} carries for a field left blank. That array is
 * assembled where a submission is refused, which is the service layer, so this class holds no
 * field-error path; it uses that same shared type only for the absence test its message sentinel
 * needs. The coupling the target severs is worth naming: the baseline gates its red highlight on
 * the pseudo-conversational re-entry flag, since {@code app/cpy/CSSETATY.cpy} lines 17 to 27 are a
 * templated copybook driven by that flag, and a stateless handler has no such flag, so the
 * highlight is driven purely by the response body.
 *
 * <h2>Record widths are carried, not screen widths</h2>
 *
 * <p>Trade-offs: the transfer objects carry the record's widths, so this class can emit values the
 * reference terminal could not render. {@code app/cbl/COTRN01C.cbl} narrows six values on their way
 * to the screen: line 177 moves {@code TRAN-AMT} into {@code WS-TRAN-AMT PIC +99999999.99} at line
 * 49, which is twelve characters holding a sign, eight integer digits, a point and two decimals
 * against the record's nine integer digits; line 184 moves {@code TRAN-DESC PIC X(100)} into
 * {@code TDESCI PIC X(60)} at line 96 of the map; line 188 moves
 * {@code TRAN-MERCHANT-NAME PIC X(50)} into {@code MNAMEI PIC X(30)} at line 126; line 189 moves
 * {@code TRAN-MERCHANT-CITY PIC X(50)} into {@code MCITYI PIC X(25)} at line 132; and lines 185 and
 * 186 move both {@code PIC X(26)} timestamps into {@code TORIGDTI} and {@code TPROCDTI}, declared
 * {@code PIC X(10)} at lines 108 and 114, leaving only the leading date. The 3270 field budget
 * forced each narrowing and a JSON body has no such budget, so the record width is carried and no
 * data is lost. The compromise accepted is that a client reproducing the reference screen decides
 * for itself what to do with the surplus, and truncating here was rejected because it would discard
 * data the record demonstrably holds and would make a response depend on which screen asked for it.
 *
 * <h2>Both code generators are rejected, and this class is the reason</h2>
 *
 * <p>Alternatives Considered: MapStruct, for generating these conversions. It is rejected because
 * the mapping is not mechanical. It drops {@code FILLER}, masks a primary account number to its
 * last four digits, admits no card verification value into any response, carries money as a string
 * so that no client parses it into a binary double, and preserves the baseline's own field
 * spellings rather than tidying them. Every one of those five is a decision a reader cannot recover
 * from the code, so every one needs a justification standing beside the statement that performs it,
 * and a generated mapper has nowhere to hold one. The rejection is enforced by absence rather than
 * by convention: no MapStruct coordinate is declared in
 * {@code services/transaction-service/pom.xml} or in the parent aggregator, so an annotation
 * reaching for it would not resolve.
 *
 * <p>Alternatives Considered: Lombok, for generated accessors on whatever intermediate shapes a
 * mapper needs. It is rejected because a generated member arrives with no Javadoc, so its accessors
 * would fail the documentation gate the parent POM binds ahead of compilation, and the tool would
 * breach the rule it was introduced to save effort under. Java 21 {@code record} types with
 * explicit constructors give the same brevity while leaving every member documentable, and Lombok
 * is likewise absent from both POMs.
 *
 * <h2>Shape</h2>
 *
 * <p>Assumptions: this class is a Spring component with no state at all, so
 * {@code TransactionController}, {@code TransactionListService} and {@code TransactionAddService}
 * receive one instance by constructor injection. Injection is what replaces the reference
 * program's static {@code CALL} linkage and its shared {@code WORKING-STORAGE}: a class holding no
 * member cannot carry a value between two conversions, which is what makes concurrent requests on
 * one shared instance safe and what lets a unit test exercise a conversion with no container. Every
 * method is therefore total in its arguments, and every helper is {@code static} for the same
 * reason.
 */
@Component
public class TransactionMapper {

    /**
     * The number of rows one page of the transaction list carries.
     *
     * <p>Assumptions: ten is the reference program's own page, not a chosen default. Line 290 of
     * {@code app/cbl/COTRN00C.cbl} clears ten row slots with {@code UNTIL WS-IDX > 10}, line 297
     * fills them forward with {@code UNTIL WS-IDX >= 11}, and lines 349 and 351 fill the same ten
     * backward from slot ten down to slot one. A different page size would change which rows a
     * given cursor returns and therefore which rows a client sees, so the figure is transcribed
     * rather than configured.
     */
    public static final int PAGE_SIZE = 10;

    /**
     * The declared character width of a transaction identifier.
     *
     * <p>Assumptions: {@code TRAN-ID PIC X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy}, which
     * agrees with {@code TRNIDINI PIC X(16)} at line 66 of {@code app/cpy-bms/COTRN00.CPY}. The
     * whole width is the key, so a shorter value is not a prefix of a key but a value that matches
     * none.
     */
    public static final int TRANSACTION_ID_WIDTH = 16;

    /**
     * The declared character width of a card number.
     *
     * <p>Assumptions: {@code TRAN-CARD-NUM PIC X(16)} at line 15 of that copybook, which agrees
     * with {@code CARDNINI PIC X(16)} at line 66 of {@code app/cpy-bms/COTRN02.CPY}. It is the
     * width the masked form retains, so a masked value is twelve mask characters and four digits.
     */
    public static final int CARD_NUMBER_WIDTH = 16;

    /**
     * The declared digit width of a merchant identifier.
     *
     * <p>Assumptions: {@code TRAN-MERCHANT-ID PIC 9(09)} at line 11 of that copybook. The stored
     * column is numeric because the value is a magnitude, so this width is what restores the
     * character form the reference field declares.
     */
    public static final int MERCHANT_ID_WIDTH = 9;

    /**
     * The declared character width of the date values the add screen supplies.
     *
     * <p>Assumptions: {@code TORIGDTI} and {@code TPROCDTI} are declared {@code PIC X(10)} at lines
     * 102 and 108 of {@code app/cpy-bms/COTRN02.CPY}, and line 60 of
     * {@code app/cbl/COTRN02C.cbl} holds {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} as the
     * form those ten characters take.
     */
    public static final int DATE_WIDTH = 10;

    /**
     * The declared character width of an account identifier.
     *
     * <p>Assumptions: {@code CC-ACCT-ID PIC X(11)} at line 34 of {@code app/cpy/CVCRD01Y.cpy}, which
     * agrees with {@code ACTIDINI PIC X(11)} at line 60 of {@code app/cpy-bms/COBIL00.CPY}. It is
     * eleven and not the sixteen of a card number, and the two are kept as separate constants rather
     * than one because a shared constant would make a change to either width change both.
     */
    public static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * Converts one stored transaction into the detail view the single-transaction screen shows.
     *
     * <p>Assumptions: the component order this builds is the record's own declaration order at lines
     * 5 to 17 of {@code app/cpy/CVTRA05Y.cpy}, which is also the order
     * {@code TransactionDetailResponse} declares, so the two read against each other directly and a
     * transposed pair of same-typed components cannot hide.
     *
     * <p>Assumptions: every character value except the card number is passed through unaltered, and
     * the fixed-width ones keep their storage padding. The columns behind those are declared
     * {@code transaction_id CHAR(16)}, {@code type_cd CHAR(2)}, {@code category_cd CHAR(4)},
     * {@code source CHAR(10)} and {@code merchant_zip CHAR(10)}, which PostgreSQL pads with spaces
     * to the declared width, and the reference program moves each value into a screen field of that
     * same width, so the padding is what the reference screen also showed. The three descriptive
     * values -- {@code description VARCHAR(100)}, {@code merchant_name VARCHAR(50)} and
     * {@code merchant_city VARCHAR(50)} -- are passed through in exactly the same way but have no
     * padding to preserve, because a varying-length column stores only what was written to it.
     *
     * <p>Assumptions: the merchant identifier is the exception among those components and is NOT a
     * pass-through, which is worth stating because it sits between two values that are. Its column
     * is {@code merchant_id BIGINT} rather than a character column, because line 11 of that
     * copybook declares {@code TRAN-MERCHANT-ID PIC 9(09)} -- an unsigned magnitude rather than a
     * token -- so there is no stored padding for anything to preserve. The nine-character form the
     * reference field declares is RE-CREATED on the way out, by left-padding the decimal rendering
     * with zeros to that width, and a value needing more positions than the field declares is
     * refused rather than truncated.
     *
     * <p>Alternatives Considered: right-trimming every character value on the way out, evaluated and
     * rejected. A value whose own final character is a space would then be indistinguishable from a
     * shorter value the column padded, and the components of this record are documented at record
     * widths rather than at trimmed lengths, so trimming would be an interpretation this class is
     * not entitled to make.
     *
     * @param transaction the stored row to render, whose identifier and amount must both be
     *     present; must not be {@code null}
     * @param returnMessage the confirmation or advisory text accompanying the read, or {@code null}
     *     when there is none; any of the three spellings of absence is collapsed onto {@code null}
     * @return the detail view carrying all thirteen mapped record fields plus the message, with the
     *     card number masked to its last four digits and both timestamps rendered as
     *     twenty-six-character values or reported absent
     * @throws NullPointerException if {@code transaction} is {@code null}, or if the row carries no
     *     transaction identifier or no amount
     * @throws ArithmeticException if the stored amount cannot be reduced to the two decimal places
     *     the money contract carries
     * @throws IllegalArgumentException if the stored merchant identifier is negative or needs more
     *     digit positions than the reference field declares, or if a stored timestamp falls outside
     *     the year range the twenty-six-character contract can express
     */
    public TransactionDetailResponse toDetailResponse(Transaction transaction,
            String returnMessage) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        return new TransactionDetailResponse(
                Objects.requireNonNull(transaction.getTranId(),
                        "tranId must not be null: it is this row's primary key"),
                transaction.getTranTypeCd(),
                transaction.getTranCatCd(),
                transaction.getTranSource(),
                transaction.getTranDesc(),
                requiredAmount(transaction.getTranAmt(), "tranAmt"),
                renderMerchantIdOrAbsent(transaction.getMerchantId()),
                transaction.getMerchantName(),
                transaction.getMerchantCity(),
                transaction.getMerchantZip(),
                // WHY : Trade-offs: masking is applied at this single crossing rather than in each
                //   controller or on the entity. A mask applied in a controller would have to be
                //   applied in every controller that returns a transaction, and one omission
                //   publishes the whole number; a mask applied on the entity would alter the value
                //   the posting and reporting paths read from that same column. The shared masker
                //   is reused rather than re-implemented, and it returns no value for an absent
                //   card number, so an absent column is reported absent instead of masked.
                CardNumberMasker.mask(transaction.getCardNum()),
                renderTimestampOrAbsent(transaction.getOrigTs()),
                renderTimestampOrAbsent(transaction.getProcTs()),
                absentWhenNeverSupplied(returnMessage));
    }

    /**
     * Converts one stored transaction into a row of the paged transaction list.
     *
     * <p>Assumptions: the row carries the originating timestamp and not the processing one, and the
     * reference paragraph settles which without ambiguity. Line 384 of
     * {@code app/cbl/COTRN00C.cbl} moves {@code TRAN-ORIG-TS} into the program's timestamp work
     * field and {@code TRAN-PROC-TS} is never read there, so a second instant would make this row
     * broader than the reference row contract with no warrant in the reference material.
     *
     * <p>Trade-offs: the timestamp is carried whole rather than in the eight-character form the
     * reference row displays, and this narrowing is reversed in the direction of more information.
     * Lines 385 to 387 of that program take the final two digits of the year together with the
     * month and the day, and line 388 assembles them into {@code WS-CURDATE-MM-DD-YY}, whose
     * separators are the two {@code FILLER PIC X(01) VALUE '/'} items at lines 32 and 34 of
     * {@code app/cpy/CSDAT01Y.cpy}, so the displayed form is a slash-separated month, day and
     * two-digit year written into {@code TDATE01I PIC X(8)} at line 84 of
     * {@code app/cpy-bms/COTRN00.CPY}. Because line 385 keeps only two digits of the year, that
     * eight-character form cannot be widened back into the instant it came from, so this row
     * carries the twenty-six-character instant and a client reproducing the reference screen
     * derives the eight characters it needs. The divergence is documented rather than presented as
     * equivalence.
     *
     * <p>Assumptions: no card number appears on this row, and none is withheld here either. The
     * reference row is four fields wide -- {@code TRNID01I PIC X(16)} at line 78,
     * {@code TDATE01I PIC X(8)} at line 84, {@code TDESC01I PIC X(26)} at line 90 and
     * {@code TAMT001I PIC X(12)} at line 96 of that map, repeated at a thirty-line stride -- and
     * declares no card field at all. The selection marker {@code SEL0001I PIC X(1)} at line 72 is
     * likewise not a component: lines 188 to 195 of the program use an {@code 'S'} there to transfer
     * control to the detail program, which is client-side navigation rather than data.
     *
     * @param transaction the stored row to render, whose identifier and amount must both be
     *     present; must not be {@code null}
     * @return the list row carrying the identifier, the description at its record width, the exact
     *     amount and the whole originating instant, or an absent instant when the column holds none
     * @throws NullPointerException if {@code transaction} is {@code null}, or if the row carries no
     *     transaction identifier or no amount
     * @throws ArithmeticException if the stored amount cannot be reduced to the two decimal places
     *     the money contract carries
     * @throws IllegalArgumentException if the stored originating timestamp falls outside the year
     *     range the twenty-six-character contract can express
     */
    public TransactionListItemResponse toListItem(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        return new TransactionListItemResponse(
                Objects.requireNonNull(transaction.getTranId(),
                        "tranId must not be null: it is this row's primary key"),
                transaction.getTranDesc(),
                requiredAmount(transaction.getTranAmt(), "tranAmt"),
                renderTimestampOrAbsent(transaction.getOrigTs()));
    }

    /**
     * Places the rows a keyset scan returned into the ascending order a page is displayed in.
     *
     * <p>Assumptions: a backward scan reads descending and must be reversed, and the reference
     * program does exactly that rather than displaying rows in the order it read them. Line 349 of
     * {@code app/cbl/COTRN00C.cbl} sets the slot index to ten, line 351 runs
     * {@code UNTIL WS-IDX &lt;= 0} and line 352 reads the previous record, so the first row read
     * lands in slot ten and the last row read lands in slot one; the screen therefore shows
     * ascending keys while the file was read descending. The repository expresses the same two
     * directions as {@code findByTranIdGreaterThanOrderByTranIdAsc} and
     * {@code findByTranIdLessThanOrderByTranIdDesc}, so a backward result arrives descending and
     * this reversal is what restores the displayed order.
     *
     * <p>Alternatives Considered: reversing inside the page assembly rather than exposing this step,
     * evaluated and rejected. The boundary keys a caller has to seal are the identifiers of the
     * first and last displayed rows, so a caller that could not obtain the display order would have
     * to repeat this reversal to find them, and two copies of an ordering rule can disagree. Making
     * the step available separately lets a caller order once, seal from the ordered ends and then
     * assemble.
     *
     * <p>Trade-offs: the argument is copied rather than reversed where it lies. Reversing in place
     * would mutate a list the caller still holds -- and a list a persistence provider returned may
     * refuse mutation outright -- so one copy per page is accepted in exchange for leaving the
     * caller's list untouched.
     *
     * @param rows the rows as the scan returned them, ascending for a forward scan and descending
     *     for a backward one; must not be {@code null} and must contain no {@code null} element
     * @param direction the direction the scan was issued in, which decides whether a reversal is
     *     needed; must not be {@code null}
     * @return an unmodifiable list holding the same rows in ascending key order, which is the order
     *     a page is displayed in
     * @throws NullPointerException if {@code rows} is {@code null}, if {@code direction} is
     *     {@code null}, or if {@code rows} contains a {@code null} element
     */
    public List<Transaction> orderForDisplay(List<Transaction> rows,
            TransactionListRequest.Direction direction) {
        Objects.requireNonNull(rows, "rows must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        List<Transaction> ordered = new ArrayList<>(rows);
        if (direction == TransactionListRequest.Direction.PREVIOUS) {
            Collections.reverse(ordered);
        }
        // WHY : Assumptions: the copy is sealed rather than returned as a mutable list, and
        //   List.copyOf is what rejects a null element. A page carrying a null row would fail
        //   inside the envelope's own constructor instead, one call later, where the diagnostic no
        //   longer names the scan that produced it.
        return List.copyOf(ordered);
    }

    /**
     * Assembles one page of the transaction list from rows already in display order.
     *
     * <p>Assumptions: forward availability is the reference program's read-one-past-the-page result
     * and is taken from the caller rather than inferred from the row count. Lines 305 to 320 of
     * {@code app/cbl/COTRN00C.cbl} perform one further read after the ten slots are filled: line 308
     * issues it, line 310 sets the further-page condition when it succeeded and line 312 clears it
     * when it did not. That is precisely the envelope's forward indicator, so the mapping is
     * one-to-one rather than an approximation, and the probe read belongs to whoever ran the query.
     *
     * <p>Assumptions: the two boundary tokens arrive already sealed and this class never mints one.
     * The envelope refuses a raw keyset cursor, and sealing needs both a key and the binding a token
     * was issued for; neither belongs to a class that holds no state, so the caller seals the first
     * and last displayed identifiers and passes the tokens through. Line 393 of that program captures
     * the first displayed identifier at slot one and lines 437 to 439 capture the last at slot ten,
     * which is the same pair of boundaries.
     *
     * <p>Refactoring Rationale: the baseline refreshes its trailing cursor only on a full page,
     * because the capture at line 439 sits inside the {@code WHEN 10} branch, so a page returning
     * fewer than ten rows leaves that field holding the previous page's value. The target names the
     * boundary of the page it actually returned, since the envelope requires both boundaries whenever
     * rows are present. What was wrong with the older behaviour is that a client paging forward from
     * a stale trailing cursor re-reads rows it has already seen, and it does so silently.
     *
     * <p>Alternatives Considered: offset pagination, rejected because it changes observable
     * behaviour that browse-by-key does not. Under concurrent inserts an offset skips and repeats
     * rows, whereas a scan resumed from the last key returns each row once regardless of what was
     * inserted between two requests.
     *
     * <p>Alternatives Considered: reporting an exhausted page through the envelope's
     * filtered-empty factory, which retains a surviving scan position, evaluated and rejected here.
     * That factory derives forward availability from the position it is given, whereas line 315 of
     * that program sets the no-further-page condition unconditionally when the fill read nothing, so
     * using it would report a further page the reference program denies.
     *
     * <p>Assumptions: no rows together with a reported further page is a contradiction the caller
     * cannot have observed, so it is refused rather than normalised. The conditional at lines 305 to
     * 320 of that program makes the two mutually exclusive by construction: the probe read at line
     * 308 is reached only on the branch where at least one row was filled, and the branch taken when
     * nothing was filled sets the no-further-page condition at line 315. A probe read past an empty
     * page is the first read of that scan, so a caller that returned no rows cannot have found one
     * beyond them.
     *
     * <p>Trade-offs: refusing adds a failure mode the reference program does not have, and the two
     * inconsistent inputs are treated differently on purpose. A boundary token supplied with no rows
     * is discarded rather than refused, because a token naming a row that was not returned changes
     * nothing a client does; a reported further page is acted on, and a client following one issues a
     * request that returns nothing or repeats rows it already holds. The cost of the refusal is paid
     * once, by the caller that is wrong.
     *
     * @param displayOrderedRows the rows to carry, in the ascending order
     *     {@link #orderForDisplay(List, TransactionListRequest.Direction)} produces; must not be
     *     {@code null}, and each row must carry an identifier and an amount
     * @param firstKeyToken the sealed cursor token identifying the first row of this page, required
     *     whenever rows are present
     * @param lastKeyToken the sealed cursor token identifying the last row of this page, required
     *     whenever rows are present and whenever a further page is reported
     * @param hasNext whether the caller's probe read found a row beyond this page
     * @return a page carrying one list row per supplied row together with both boundary tokens and the
     *     further-page indicator, or an exhausted page carrying no rows, no token and no further page
     *     when nothing was returned
     * @throws NullPointerException if {@code displayOrderedRows} is {@code null}, if it contains a
     *     {@code null} element, or if a row carries no transaction identifier or no amount
     * @throws IllegalArgumentException if a further page is reported while no rows were returned, if
     *     rows are present and either boundary token is absent, if a further page is reported without
     *     a trailing token, if either supplied token is not a sealed cursor token, or if a stored
     *     timestamp falls outside the year range the twenty-six-character contract can express
     * @throws ArithmeticException if a stored amount cannot be reduced to the two decimal places the
     *     money contract carries
     */
    public PageResponse<TransactionListItemResponse> toListPage(
            List<Transaction> displayOrderedRows, String firstKeyToken, String lastKeyToken,
            boolean hasNext) {
        Objects.requireNonNull(displayOrderedRows, "displayOrderedRows must not be null");
        if (displayOrderedRows.isEmpty()) {
            if (hasNext) {
                throw new IllegalArgumentException("hasNext must be false when no rows were"
                        + " returned, because the probe read past an empty page is that scan's first"
                        + " read and cannot have found a row beyond rows it did not return");
            }
            // WHY : Assumptions: an exhausted page carries neither a boundary token nor a further
            //   page, matching line 315 of app/cbl/COTRN00C.cbl, which sets the no-further-page
            //   condition whenever the fill read nothing. A token supplied here would publish a
            //   position that names no returned row, so it is left out rather than carried.
            return PageResponse.empty();
        }
        List<TransactionListItemResponse> items = new ArrayList<>(displayOrderedRows.size());
        for (Transaction row : displayOrderedRows) {
            items.add(toListItem(row));
        }
        return PageResponse.ofRows(items, firstKeyToken, lastKeyToken, hasNext);
    }

    /**
     * Builds the row an accepted submission appends, from the validated request and the two values
     * its caller resolved.
     *
     * <p>Assumptions: the identifier is supplied rather than derived here, because deriving it needs
     * a read this class cannot perform. Lines 444 to 447 of {@code app/cbl/COTRN02C.cbl} position at
     * the high end of the key, read the previous record and end the browse; line 448 moves that
     * identifier into {@code WS-TRAN-ID-N PIC 9(16)}, line 449 adds one and line 451 moves the
     * numeric result back onto the {@code X(16)} record field, which left-pads it with zeros. The
     * browse is the caller's work and the zero-padding is this class's, so a caller may supply the
     * incremented value in either form.
     *
     * <p>Assumptions: the card number is supplied rather than taken from the request, and the reason
     * is that the reference program overwrites whatever the client typed. Lines 195 to 230 of that
     * program are a single conditional selection whose first branch, at line 196, tests the account
     * field for content: when an account is supplied the branch validates it numeric at lines 197 to
     * 203, normalises it at lines 204 to 207, reads the account-keyed cross-reference at line 208 and
     * then at line 209 moves the cross-referenced card number onto the card field, discarding the
     * client's value. Only when the account field is empty does the card branch at lines 210 to 223
     * run, which reads the card-keyed cross-reference and writes the account back from it at line
     * 223. The rule is therefore account-first precedence and not an exclusive choice, which is the
     * part most easily mis-implemented, and the cross-reference read that resolves it belongs to the
     * caller.
     *
     * <p>Trade-offs: the request type narrows that precedence to an exclusive choice, since it is
     * annotated to admit exactly one of the two key components, so the two rules coincide for every
     * request that reaches this class. This class still takes the resolved card number as an argument
     * rather than reading the request's own component, because the resolved value is the one the
     * reference program writes into the record and a request that supplied an account carries no card
     * number to write. The cost accepted is one more argument; what it buys is that the record never
     * receives a client-supplied card number when an account was the key.
     *
     * <p>Assumptions: the account identifier is not a member of this record at all.
     * {@code app/cpy/CVTRA05Y.cpy} declares no account field, so the account reaches a transaction
     * only through the card number and the cross-reference, and the request's account component is
     * consumed by the caller's resolution rather than by this conversion.
     *
     * <p>Assumptions: the confirmation component is likewise not a member of the record. It is turn
     * control: {@code CONFIRMI PIC X(1)} at line 138 of {@code app/cpy-bms/COTRN02.CPY} drives the
     * branch at lines 174 to 188 of that program, which either prompts for confirmation or refuses an
     * unrecognised answer, and neither outcome writes a record.
     *
     * <p>Trade-offs: the amount admitted here is the record's range and not the screen's, and the two
     * differ. Lines 339 to 351 of that program validate the amount field byte by byte over twelve
     * characters -- position one a sign, positions two to nine numeric, position ten a point and
     * positions eleven and twelve numeric -- which is eight integer digits, and line 345 states the
     * form verbatim as {@code 'Amount should be in format -99999999.99'}. The record is declared
     * {@code PIC S9(09)V99}, which is nine, so the reference screen cannot express the full range the
     * record holds. The request type bounds the amount at the record's nine digits, so this class
     * accepts a value the reference screen could not have submitted. The cost accepted is that the
     * accepted range is wider than the screen's; the alternative of clamping to eight digits would
     * refuse a value the record demonstrably stores and that the posting path already writes.
     *
     * <p>Assumptions: both dates carry a time of midnight, and that is the reference behaviour rather
     * than a default chosen here. Lines 464 and 465 move two {@code PIC X(10)} screen fields into the
     * two {@code PIC X(26)} record fields, so a record written through that screen holds ten
     * characters of date followed by sixteen spaces and has no time component at all. Rendering that
     * into a microsecond-precision column therefore yields midnight, and inventing a submission time
     * instead would record an instant the reference never captured.
     *
     * <p>Assumptions: only the lexical shape of a date is checked here, and semantic validity is the
     * caller's. Lines 353 to 366 and 368 to 381 of that program test each date character by character
     * -- four digits, a hyphen, two digits, a hyphen, two digits -- with the messages at lines 360 and
     * 375 stating the form, and the request type carries that same shape as a declarative constraint.
     * Whether the date exists is decided separately, by the call to the date utility at lines 389 to
     * 395, and that call belongs to the service layer. This class parses the shape the constraint
     * already accepted and does not re-implement the utility's calendar rules.
     *
     * @param request the validated submission, whose amount, dates and merchant identifier are read
     *     into the record; must not be {@code null}
     * @param transactionId the identifier the caller derived for this row, as digits, which is
     *     left-padded with zeros to the declared width; must not be {@code null}
     * @param resolvedCardNumber the card number the caller obtained from the cross-reference, as
     *     digits, which is left-padded with zeros to the declared width; must not be {@code null}
     * @return a row carrying all thirteen mapped record fields, with both timestamps at midnight of
     *     the supplied dates
     * @throws NullPointerException if any argument is {@code null}, or if the request carries no
     *     amount, no merchant identifier or no date
     * @throws IllegalArgumentException if the identifier, the card number or the merchant identifier
     *     is empty, contains a character other than a digit, or is longer than its declared width
     * @throws DateTimeParseException if either date is lexically well-shaped but denotes no calendar
     *     date, such as a thirty-first of February
     */
    public Transaction toEntity(TransactionAddRequest request, String transactionId,
            String resolvedCardNumber) {
        Objects.requireNonNull(request, "request must not be null");
        return new Transaction(
                zeroPaddedDigits(transactionId, TRANSACTION_ID_WIDTH, "transactionId"),
                request.typeCode(),
                request.categoryCode(),
                request.source(),
                request.description(),
                requiredScalarAmount(request.amountValue(), "amount"),
                parseMerchantId(request.merchantId()),
                request.merchantName(),
                request.merchantCity(),
                request.merchantZip(),
                zeroPaddedDigits(resolvedCardNumber, CARD_NUMBER_WIDTH, "resolvedCardNumber"),
                dateAtStartOfDay(request.originDate(), "originDate"),
                dateAtStartOfDay(request.processDate(), "processDate"));
    }

    /**
     * Converts an appended transaction into the acknowledgement the add screen shows.
     *
     * <p>Assumptions: the amount echoed back is the stored value and therefore the normalised one,
     * which is what the reference screen also shows. Lines 383 to 386 of
     * {@code app/cbl/COTRN02C.cbl} compute the numeric amount from the submitted text, move it
     * through the edited field {@code WS-TRAN-AMT-E PIC +99999999.99} declared at line 59 and then
     * move that edited value back onto the screen field, so the operator sees a canonical rendering
     * rather than the characters typed. Reading the amount off the appended row reproduces that,
     * because line 458 wrote the same normalised value into the record.
     *
     * <p>Assumptions: this acknowledgement carries no card number, so nothing is masked here. The
     * response declares three components and none of them is the card, which is why the masking
     * decision recorded on this class reaches only the detail view among this class's conversions.
     * It is not the only masked crossing in the module -- {@code BillPaymentMapper} masks the card
     * its response reports -- so the decision is a package-wide one applied at each crossing that
     * carries a card, rather than one this class owns alone.
     *
     * @param transaction the appended row, whose identifier and amount must both be present; must
     *     not be {@code null}
     * @param returnMessage the confirmation text accompanying the append, or {@code null} when there
     *     is none; any of the three spellings of absence is collapsed onto {@code null}
     * @return the acknowledgement carrying the identifier, the normalised amount and the message
     * @throws NullPointerException if {@code transaction} is {@code null}, or if the row carries no
     *     transaction identifier or no amount
     * @throws ArithmeticException if the stored amount cannot be reduced to the two decimal places
     *     the money contract carries
     */
    public TransactionAddResponse toAddResponse(Transaction transaction, String returnMessage) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        return new TransactionAddResponse(
                Objects.requireNonNull(transaction.getTranId(),
                        "tranId must not be null: it is this row's primary key"),
                requiredAmount(transaction.getTranAmt(), "tranAmt"),
                absentWhenNeverSupplied(returnMessage));
    }

    /**
     * Wraps a stored decimal amount in the exact money type the response components declare.
     *
     * <p>Assumptions: an absent amount is refused rather than reported as absent, and the two
     * nullable members of this record are treated differently on purpose. The column is declared
     * {@code amount NUMERIC(11,2)} without a not-null constraint, yet the reference record has no
     * absent state for it: {@code TRAN-AMT PIC S9(09)V99} occupies eleven bytes of every 350-byte
     * record and a zoned-decimal field always decodes to a number. An absent amount is therefore a
     * data defect rather than a state the reference can produce, and refusing here names the row that
     * carries it instead of emitting a null into a component documented as always present.
     *
     * @param storedAmount the value read from the amount column; must not be {@code null}
     * @param component the member name reproduced in the refusal so that it names the value that
     *     failed; must not be {@code null}
     * @return the same value as an exact amount at two decimal places
     * @throws NullPointerException if {@code storedAmount} is {@code null}
     * @throws ArithmeticException if the value declares a scale or a magnitude the money contract
     *     cannot reduce to two decimal places
     */
    private static Money requiredAmount(BigDecimal storedAmount, String component) {
        Objects.requireNonNull(storedAmount, component
                + " must not be null: the reference record has no absent amount, so an absent value"
                + " is a defect in the row rather than a state to report");
        return Money.of(storedAmount);
    }

    /**
     * Unwraps a submitted amount into the scalar the entity member declares.
     *
     * <p>Assumptions: the scalar is read off the money type rather than re-parsed from the submitted
     * text, so the value the request's constraints accepted is bit-for-bit the value the row stores.
     * Re-parsing would introduce a second place the amount is interpreted, and the two could disagree
     * about a trailing zero or a leading sign while both went on compiling.
     *
     * @param amount the amount the request carries; must not be {@code null}
     * @param component the component name reproduced in the refusal so that it names the value that
     *     failed; must not be {@code null}
     * @return the underlying decimal at the two decimal places the money type maintains
     * @throws NullPointerException if {@code amount} is {@code null}
     */
    private static BigDecimal requiredScalarAmount(Money amount, String component) {
        Objects.requireNonNull(amount, component
                + " must not be null: a submission with no amount has nothing to post");
        return amount.amount();
    }

    /**
     * Renders a stored timestamp in the twenty-six-character contract form, or reports it absent.
     *
     * <p>Assumptions: an absent timestamp is reported as absent and never as an epoch instant, and
     * the seed data is why the case is real rather than defensive. Every one of the 300 records in
     * {@code app/data/ASCII/dailytran.txt} carries twenty-six spaces at the processing-timestamp
     * field, the twenty-six bytes starting at zero-based offset 304, because a staged transaction has
     * not been processed; the originating timestamp at offset 278 is populated, reading
     * {@code 2022-06-10 19:27:53.000000} in the first record. Twenty-six spaces decode to no value,
     * so decoding them to a zero instant would date an unprocessed transaction to 1970 and make it
     * indistinguishable from one processed at an absurd time.
     *
     * <p>Alternatives Considered: refusing an absent timestamp, as this class refuses an absent
     * amount, evaluated and rejected. The two are not alike: the reference produces a blank timestamp
     * routinely and the column permits one accordingly, whereas it produces no blank amount at all,
     * so refusing here would reject a row the reference itself can write.
     *
     * @param timestamp the value read from a timestamp column, which may be {@code null}
     * @return {@code null} when the column holds no value; otherwise exactly twenty-six characters in
     *     the form {@code YYYY-MM-DD HH:MM:SS.mmmmmm}
     * @throws IllegalArgumentException if the year of a present value lies outside the range the
     *     twenty-six-character contract can express
     */
    private static String renderTimestampOrAbsent(LocalDateTime timestamp) {
        // WHY : Assumptions: com.carddemo.common.time.TimestampFormatter refuses a null argument,
        //   so the absence test is made here rather than delegated to it. Delegating would turn a
        //   representable storage state into a thrown failure at the one crossing that has to be
        //   able to report it.
        return timestamp == null ? null : TimestampFormatter.format(timestamp);
    }

    /**
     * Restores the declared character form of a merchant identifier, or reports it absent.
     *
     * <p>Assumptions: the column is numeric and the component is characters, so the digits are
     * left-padded back to the declared width on the way out. {@code TRAN-MERCHANT-ID PIC 9(09)} at
     * line 11 of {@code app/cpy/CVTRA05Y.cpy} is nine digit positions, and the entity holds it as a
     * magnitude because every measured value in the seed data is {@code 800000000} and line 226 of
     * {@code app/cbl/COBIL00C.cbl} writes the all-nines sentinel {@code 999999999} for a bill
     * payment, neither of which has a significant leading zero. Rendering the magnitude back to nine
     * characters is what returns the component to the width the reference field declares.
     *
     * <p>Assumptions: an absent identifier is reported as absent rather than padded. Padding a null
     * would emit {@code 000000000}, which is a merchant identifier rather than the absence of one,
     * and a client could not tell the invented value from a recorded one.
     *
     * <p>Trade-offs: a value the declared width cannot hold is refused rather than rendered. The
     * reference field is unsigned and nine positions wide, so a negative magnitude would render with
     * a sign character in place of a digit and a ten-digit magnitude would render one character too
     * wide; either would leave the component outside the shape its contract declares while appearing
     * to succeed. Refusing costs a failed read on a defective row and buys a component that is always
     * exactly nine digits.
     *
     * @param merchantId the value read from the merchant column, which may be {@code null}
     * @return {@code null} when the column holds no value; otherwise the digits left-padded with
     *     zeros to the declared width
     * @throws IllegalArgumentException if a present value is negative or needs more than the declared
     *     number of digit positions
     */
    private static String renderMerchantIdOrAbsent(Long merchantId) {
        if (merchantId == null) {
            return null;
        }
        String digits = Long.toString(merchantId);
        if (merchantId < 0L || digits.length() > MERCHANT_ID_WIDTH) {
            throw new IllegalArgumentException("merchantId must be a non-negative magnitude of at"
                    + " most " + MERCHANT_ID_WIDTH + " digits, because the reference field is"
                    + " declared unsigned at that width");
        }
        return "0".repeat(MERCHANT_ID_WIDTH - digits.length()) + digits;
    }

    /**
     * Reads a submitted merchant identifier into the magnitude the entity member declares.
     *
     * <p>Assumptions: leading zeros are accepted and discarded on the way in, which is the inverse of
     * the padding applied on the way out and loses nothing, because the column stores a magnitude.
     * The request's own constraint already admits exactly nine digits or an empty value, so the check
     * here is the boundary's own and not the only one.
     *
     * <p>Alternatives Considered: parsing the submitted text directly, which is the shorter route and
     * is rejected. A numeric parse also accepts a leading sign, so a submitted value beginning with a
     * hyphen would become a negative magnitude that the outbound rendering then refuses -- the defect
     * would surface on a later read rather than at the submission that introduced it. Routing the
     * value through the same digits-and-width check the identifiers use rejects it here, and reuses
     * one rule rather than writing a second that could disagree with it.
     *
     * <p>Assumptions: the parse itself cannot fail once the check has passed, and no exception is
     * declared for it. The check returns exactly the declared number of ASCII digits and no sign, so
     * the largest value reaching the parse is nine nines, which a sixty-four-bit signed magnitude
     * holds with room to spare. Declaring a numeric-format failure here would document a condition
     * this method cannot reach and would invite a caller to handle it.
     *
     * @param merchantId the nine-digit identifier the request carries; must not be {@code null}
     * @return the same value as a magnitude
     * @throws NullPointerException if {@code merchantId} is {@code null}
     * @throws IllegalArgumentException if the value is empty, holds a character other than a digit,
     *     or is longer than the declared width
     */
    private static Long parseMerchantId(String merchantId) {
        return Long.valueOf(zeroPaddedDigits(merchantId, MERCHANT_ID_WIDTH, "merchantId"));
    }

    /**
     * Reads a ten-character date into the timestamp value the record carries for it.
     *
     * <p>Assumptions: the time is midnight because the reference record has no time to carry. Lines
     * 464 and 465 of {@code app/cbl/COTRN02C.cbl} move a {@code PIC X(10)} screen field into a
     * {@code PIC X(26)} record field, which leaves ten characters of date followed by sixteen
     * spaces, so the record written through the add screen holds no time component and a
     * microsecond-precision column renders midnight for it.
     *
     * <p>Alternatives Considered: the shared formatter's own date entry points, which are not
     * applicable here. Both of them require an argument of exactly the twenty-six-character contract
     * length and slice its leading ten, whereas the value arriving here is those ten characters
     * alone, so passing it to either would be refused on length. The platform's own date parse is
     * used instead, and no date pattern is declared here.
     *
     * @param isoDate the date as ten characters in the form {@code YYYY-MM-DD}; must not be
     *     {@code null}
     * @param component the component name reproduced in the refusal so that it names the value that
     *     failed; must not be {@code null}
     * @return that date at midnight, carrying no zone, which is the form the record's
     *     twenty-six-character field expresses for a date-only value
     * @throws NullPointerException if {@code isoDate} is {@code null}
     * @throws DateTimeParseException if the value is not a calendar date in that form, such as a
     *     thirty-first of February
     */
    private static LocalDateTime dateAtStartOfDay(String isoDate, String component) {
        Objects.requireNonNull(isoDate, component
                + " must not be null: the reference record's date positions are always written");
        return LocalDate.parse(isoDate).atStartOfDay();
    }

    /**
     * Left-pads a digit string with zeros to a declared width, refusing anything that is not digits.
     *
     * <p>Assumptions: the padding reproduces what the reference program does with a numeric-to-
     * character move. Lines 204 to 207 and 218 to 221 of {@code app/cbl/COTRN02C.cbl} move a numeric
     * item onto a character field of matching width, which left-pads with zeros, and line 451 does
     * the same for a newly derived transaction identifier. A caller may therefore supply either the
     * padded form or the bare digits and obtain the same stored value.
     *
     * <p>Trade-offs: an over-long or non-numeric value is refused rather than truncated or coerced.
     * Truncating a seventeen-digit value to sixteen would store a key that matches no row while
     * appearing to succeed, and both identifiers this is applied to are keys or index columns, so a
     * silent coercion is the failure that costs most to find.
     *
     * @param value the digits to pad, in either the bare or the already-padded form, validated
     *     exactly as supplied with no trimming, so a value carrying a leading or trailing blank is
     *     refused rather than cleaned; must not be {@code null}
     * @param width the declared character width of the field the value is stored in
     * @param component the argument name reproduced in the refusal so that it names the value that
     *     failed; must not be {@code null}
     * @return the value left-padded with zeros to exactly {@code width} characters
     * @throws NullPointerException if {@code value} or {@code component} is {@code null}
     * @throws IllegalArgumentException if the value is empty, holds a character other than a digit,
     *     or is longer than {@code width}
     */
    private static String zeroPaddedDigits(String value, int width, String component) {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(value, component + " must not be null");

        // WHY : Refactoring Rationale: the value is validated exactly as supplied. An earlier
        //   revision trimmed it first, which quietly widened the contract: " 123 " reached the digit
        //   loop as "123" and was accepted, although the Javadoc above and the refusal message below
        //   both say a character other than a digit is rejected. Trade-offs: refusing the padded form
        //   here costs nothing, because none of the three call sites can legitimately produce one.
        //   transactionId and resolvedCardNumber are derived inside this service -- the identifier by
        //   the caller and the card number from the cross-reference -- so a surrounding blank in
        //   either is a defect in the producer, and trimming it would store a key while hiding the
        //   defect that formed it. merchantId is the one value a client supplies, and it is trimmed
        //   nowhere because it does not need to be: TransactionAddRequest declares it @NotBlank with
        //   @Pattern(regexp = MERCHANT_ID_DIGITS), and a Bean Validation pattern matches the WHOLE
        //   value, so " 123456789 " is refused at that boundary and never reaches this method. That
        //   constraint is the explicit external boundary for the padded form, and it is the only
        //   place a submitted value's surrounding whitespace is ruled on.
        String digits = value;
        if (digits.isEmpty() || digits.length() > width) {
            throw new IllegalArgumentException(component + " must be between 1 and " + width
                    + " digits, because the reference field declares that width");
        }
        for (int index = 0; index < digits.length(); index++) {
            char character = digits.charAt(index);
            // WHY : Assumptions: the ten ASCII digits are tested directly rather than through the
            //   platform's general digit predicate, which also accepts the decimal digits of other
            //   scripts. A non-ASCII digit would pass that predicate and then store a byte that
            //   the reference field's own numeric test, at line 197 of app/cbl/COTRN02C.cbl,
            //   rejects.
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(component
                        + " must hold digits only, because the reference field is tested numeric"
                        + " before it is used as a key");
            }
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Collapses every spelling of an absent message onto {@code null}, leaving a present one
     * untouched.
     *
     * <p>Assumptions: absence and blankness are one state for this field and two for its neighbour.
     * The low-values condition at line 30 of {@code app/cpy/CVCRD01Y.cpy} is nested under
     * {@code CCARD-RETURN-MSG} at line 29 and under that field alone, so an unset return message has
     * a sentinel and maps to no value; {@code CCARD-ERROR-MSG} at line 28 carries no such condition,
     * which is why this collapse is applied to the return message only. A message read out of a field
     * of declared width arrives padded, so the shared absence test folds a null, an empty value, a
     * run of spaces and a run of low values alike.
     *
     * <p>Assumptions: a present message is returned exactly as received, padding included. This class
     * does not re-word, re-case, re-space or truncate a message, because neighbouring reference
     * strings differ from one another by a single byte and normalising one would silently re-word a
     * screen.
     *
     * @param message the message as supplied, which may be {@code null}, empty, blank or genuine text
     * @return {@code null} when the value is any spelling of absence, and otherwise the value
     *     unchanged
     */
    private static String absentWhenNeverSupplied(String message) {
        return FieldValidationFlag.isNeverSupplied(message) ? null : message;
    }

    /**
     * Converts a posted bill payment into the acknowledgement the bill-payment screen shows.
     *
     * <p>Refactoring Rationale: this method exists because nothing constructed
     * {@link BillPaymentResponse} at all. The record and the published contract each described a shape
     * and no code produced either, so the two could -- and did -- drift apart with no build step able to
     * notice. A mapper is what makes the shape a fact rather than a description, and it is why the
     * balance's side of the subtraction is decided HERE, at one site, instead of at each call site.
     *
     * <p>Assumptions: the balance handed in must be the one read BEFORE the payment was applied, and
     * that is the whole contract of the argument. Statement order in the reference fixes it: line 193 of
     * {@code app/cbl/COBIL00C.cbl} moves {@code ACCT-CURR-BAL} into the display field, line 224 reuses
     * the same untouched value as the transaction amount, line 233 writes the transaction, and only line
     * 234 subtracts. Line 242 sends the display field unchanged, so what the operator sees is the
     * pre-payment figure. Reading the balance off the UPDATED account entity instead would report the
     * post-payment figure, which for this program is always exactly zero because line 234 subtracts the
     * whole balance from itself -- a value that would look plausible and carry no information.
     *
     * <p>Trade-offs: the balance is taken as an argument rather than read from an account entity, which
     * means the caller can hand in the wrong one. Reading it here was the alternative and is worse: this
     * mapper's context does not own the account table, so it would either reach across a bounded-context
     * boundary the layering rules forbid or receive the entity AFTER the update and read the zero. Taking
     * the value the caller already holds keeps the ownership boundary intact and puts the requirement in
     * this documentation, where the argument's own name cannot express it.
     *
     * <p>Assumptions: the identifiers are zero-padded to their declared widths on the way out, the same
     * treatment the append acknowledgement gives, so a caller comparing the echoed account identifier
     * with the one it submitted finds the same characters. {@code CC-ACCT-ID PIC X(11)} at line 34 of
     * {@code app/cpy/CVCRD01Y.cpy} and {@code TRAN-ID PIC X(16)} at line 5 of
     * {@code app/cpy/CVTRA05Y.cpy} are those widths.
     *
     * @param accountId the account that was paid, in either the bare or the already-padded form; must
     *     not be {@code null}
     * @param balanceBeforePayment the balance read before the payment was applied, which is also the
     *     amount paid; must not be {@code null}
     * @param transactionId the key of the transaction the payment wrote, in either form; must not be
     *     {@code null}
     * @param returnMessage the confirmation sentence accompanying the payment, or {@code null} when
     *     there is none; any of the three spellings of absence is collapsed onto {@code null}
     * @return the acknowledgement, with its posted discriminator fixed by the record's own factory
     * @throws NullPointerException if {@code accountId}, {@code balanceBeforePayment} or
     *     {@code transactionId} is {@code null}
     * @throws IllegalArgumentException if either identifier is empty, holds a character other than a
     *     digit, or is longer than its declared width
     */
    public BillPaymentResponse toBillPaymentResponse(String accountId, Money balanceBeforePayment,
            String transactionId, String returnMessage) {
        Objects.requireNonNull(balanceBeforePayment, "balanceBeforePayment must not be null:"
                + " the reference always has a balance to report on a posted payment");
        return BillPaymentResponse.posted(
                zeroPaddedDigits(accountId, ACCOUNT_ID_WIDTH, "accountId"),
                balanceBeforePayment,
                zeroPaddedDigits(transactionId, TRANSACTION_ID_WIDTH, "transactionId"),
                absentWhenNeverSupplied(returnMessage));
    }
}
