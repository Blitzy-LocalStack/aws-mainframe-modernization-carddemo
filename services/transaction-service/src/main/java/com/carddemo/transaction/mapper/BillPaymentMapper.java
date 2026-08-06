package com.carddemo.transaction.mapper;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Converts between the bill-payment wire shapes and the ledger row a payment appends, for the
 * screen migrated from {@code app/cbl/COBIL00C.cbl}.
 *
 * <p><b>Purpose.</b> Bill payment is the one write path in this bounded context that composes a
 * transaction record almost entirely from fixed values and from one figure another context owns,
 * rather than from operator input. The reference program accepts an account identifier and a single
 * confirmation character, and then fills the record from the ten moves at lines 220 to 229 of that
 * program -- eight of them literals and two of them values read elsewhere. This class is where
 * those ten decisions live, each beside the line that authorises it, so that a reader looking for
 * the payment's shape finds one place holding all of it rather than ten assignments distributed
 * through a service method.
 *
 * <p>It also owns three smaller translations the same screen needs: the twenty-six character
 * rendering of the instant the payment is stamped with, the confirmation sentence the successful
 * turn carries, and the per-field error array a refused submission answers with. All four
 * conversions read the reference program as specification and none of them redefines it.
 *
 * <h2>The screen declares three business fields, and two of its absences are load-bearing</h2>
 *
 * <p>{@code app/cpy-bms/COBIL00.CPY} declares exactly three business fields --
 * {@code ACTIDINI PIC X(11)} at line 60, {@code CURBALI PIC X(14)} at line 66 and
 * {@code CONFIRMI PIC X(1)} at line 72 -- alongside six framing fields that carry no business value
 * and one message line. The framing six are a four-character transaction name at line 24, two
 * forty-character titles at lines 30 and 48, an eight-character program name at line 42 and an
 * eight-character date and time at lines 36 and 54; the message line is {@code ERRMSGI PIC X(78)} at
 * line 78, whose width regime is recorded on {@link #returnMessageOrAbsent}. Three consequences
 * follow from the three business fields, and each is recorded because it is an absence a reader
 * would otherwise take for an oversight.
 *
 * <p>Assumptions: <b>no amount field exists on this screen at all</b>, so no amount is accepted
 * anywhere in this class. Line 224 of that program moves {@code ACCT-CURR-BAL} into
 * {@code TRAN-AMT}, which is the whole outstanding balance, and line 234 then subtracts that same
 * amount from the account, so the reference always pays the balance in full and offers no way to
 * pay part of it. {@link BillPaymentRequest} carries no amount component for the same reason.
 * Accepting one here would let a caller perform a partial payment the reference cannot express,
 * which is a change in observable behaviour rather than in structure.
 *
 * <p>Assumptions: <b>no transaction-identifier field exists on the screen either</b>, and reporting
 * the identifier is therefore a deliberate addition rather than a transcription. The reference mints
 * it at lines 212 to 219 -- high values into {@code TRAN-ID}, a {@code STARTBR}, a
 * {@code READPREV}, an {@code ENDBR}, the recovered identifier into {@code WS-TRAN-ID-NUM}, one
 * added to it, an {@code INITIALIZE} of the record and the incremented value moved back -- and then
 * has nowhere to display it, so it reaches the operator only inside the confirmation sentence the
 * write path builds at lines 527 to 531. {@link BillPaymentResponse} publishes it as a component,
 * which is what leaves a caller able to reference the payment it just made.
 *
 * <p>Assumptions: <b>no paging of any kind belongs here</b>, and the point is worth making because
 * the reference does contain browse verbs that could be mistaken for a cursor. There is no
 * {@code READNEXT} paragraph anywhere in that program's 572 lines; the {@code STARTBR} at line 213
 * and the {@code READPREV} at line 214 exist only to reach the highest existing key, as lines 216
 * and 217 show by reading it and adding one. Nothing in this class carries a page envelope, a page
 * number or a cursor key.
 *
 * <h2>Both code generators are rejected, and this class is why</h2>
 *
 * <p>Alternatives Considered: an annotation-processor mapping generator, for producing these
 * conversions from the two shapes' member names. It is rejected, and this class is the strongest
 * case for the rejection anywhere in the module: the mapping is not mechanical. Eight of the values
 * it writes are literals whose only justification is a cited reference line, one response component
 * is a figure a different bounded context owns, and one captured instant is deliberately written
 * into two separate members. Every one of those needs a justification standing beside the statement
 * that performs it, and a generated mapper has nowhere to hold one -- the value
 * {@value #MERCHANT_ID} in particular is indistinguishable from an accident unless the line that
 * assigns it is named. The rejection is enforced by absence as well as by argument: no such
 * coordinate is declared in {@code services/transaction-service/pom.xml} or in the parent
 * aggregator, so an annotation reaching for one would not resolve. This package's charter names the
 * library and records the same reasoning for the package as a whole.
 *
 * <p>Alternatives Considered: an annotation-driven accessor and builder generator, for shortening
 * whatever intermediate shapes a mapper needs. It is rejected because a generated member arrives
 * carrying no documentation, so its accessors would fail the documentation gate the parent POM binds
 * ahead of compilation -- the tool would breach the obligation it was introduced to save effort
 * under. There is no annotation-driven or comment-driven escape from that gate either: the ruleset
 * configures none of the three suppression filters that would read one. Java 21 {@code record} types
 * with explicit constructors give the same brevity while leaving every member documentable, and that
 * library is likewise absent from both POMs.
 *
 * <h2>Nothing here reaches into another bounded context</h2>
 *
 * <p>Alternatives Considered: taking the account record itself, so that the balance could be read
 * from it rather than handed in. Two shapes of that were evaluated and both are rejected. Accepting
 * an account entity would require importing the account context's persistence model, which the
 * layering rules this module runs against its own classes forbid by naming that package as a foreign
 * domain; the rule is a test, so the import fails the build rather than a review. Having this class
 * fetch the account itself would put a network call inside a translation boundary, which turns every
 * conversion into an operation that can fail for a reason unrelated to conversion. The balance
 * therefore arrives as a scalar argument, and the decision is restated at the signature that takes
 * it because that is where a reader meets it.
 *
 * <p>Assumptions: nothing in this class is masked, and that is a statement about the response shape
 * rather than a relaxation of the package convention. {@link BillPaymentResponse} declares five
 * components -- a transaction identifier, an account identifier, a balance, a paid discriminator and
 * a message -- and none of them is a card number, so the primary-account-number masking this package
 * applies where a card does cross has no site here. The card number reached through the
 * cross-reference at line 225 is written into the ledger row, where it is storage rather than a
 * response, and is therefore carried in full. No card verification value is read, held or returned
 * by any member of this class.
 *
 * <h2>No field is renamed in this module</h2>
 *
 * <p>Assumptions: every name beneath this class matches its copybook exactly, and the absence of a
 * rename is a decision rather than an oversight. A reader who knows the neighbouring services will
 * arrive expecting a spelling change, because the wider migration does correct three misspelled
 * baseline names in its target columns -- {@code ACCT-EXPIRAION-DATE} in the account context,
 * {@code CARD-EXPIRAION-DATE} in the card context and {@code PA-MERCHANT-CATAGORY-CODE} in the
 * authorization context. None of the three occurs in {@code app/cpy/CVTRA05Y.cpy} or in
 * {@code app/cpy-bms/COBIL00.CPY}, which are the only layouts this class reads, so there is nothing
 * here to rename and a rename introduced for symmetry would invent a divergence from the reference
 * layout in the one package whose job is to absorb such differences rather than create them.
 */
@Component
public class BillPaymentMapper {

    /**
     * The transaction type code every bill payment carries.
     *
     * <p>Assumptions: the value is the two-character literal moved at line 220 of
     * {@code app/cbl/COBIL00C.cbl}, and it is declared as text rather than as a number because
     * {@code TRAN-TYPE-CD PIC X(02)} at line 6 of {@code app/cpy/CVTRA05Y.cpy} is a character
     * field. A numeric two would render as one character and match no reference-data row.
     */
    public static final String TRANSACTION_TYPE_CODE = "02";

    /**
     * The transaction category code every bill payment carries.
     *
     * <p>Assumptions: the reference moves the numeric literal two at line 221 of that program into
     * {@code TRAN-CAT-CD PIC 9(04)} declared at line 7 of {@code app/cpy/CVTRA05Y.cpy}, and a
     * four-digit numeric field receives a numeric literal right-aligned and zero-filled, so the
     * stored characters are the four written here. The zeros are written out rather than left to a
     * formatter because the target column is a fixed-width character type and a one-character value
     * would satisfy neither the stored width nor the foreign key into the reference categories.
     */
    public static final String TRANSACTION_CATEGORY_CODE = "0002";

    /**
     * The transaction source every bill payment carries.
     *
     * <p>Assumptions: the literal is moved at line 222 of that program and is eight characters
     * against a {@code TRAN-SOURCE PIC X(10)} field declared at line 8 of
     * {@code app/cpy/CVTRA05Y.cpy}, so the reference stores it followed by two spaces. Those spaces
     * are padding contributed by the fixed-width column rather than data, so the logical value is
     * carried here and the column supplies the width.
     */
    public static final String TRANSACTION_SOURCE = "POS TERM";

    /**
     * The transaction description every bill payment carries.
     *
     * <p>Assumptions: reproduced character for character from the literal at line 223 of that
     * program, including the spaces on either side of the hyphen. This string is visible on a
     * statement and in a transaction list, so re-wording, re-casing or re-spacing it would change
     * what an operator reads.
     */
    public static final String TRANSACTION_DESCRIPTION = "BILL PAYMENT - ONLINE";

    /**
     * The merchant identifier every bill payment carries.
     *
     * <p>Assumptions: the literal is moved at line 226 of that program into
     * {@code TRAN-MERCHANT-ID PIC 9(09)} declared at line 11 of {@code app/cpy/CVTRA05Y.cpy}, and
     * nine nines is the largest value that field can hold. It is a reserved sentinel standing for
     * "no merchant" rather than a real merchant key, which is why a bill payment cannot be
     * attributed to one and why no lookup is attempted against it.
     */
    public static final long MERCHANT_ID = 999999999L;

    /**
     * The merchant name every bill payment carries.
     *
     * <p>Assumptions: the literal is moved at line 227 of that program. It is the display partner of
     * {@link #MERCHANT_ID}: because the identifier is a sentinel rather than a key, no lookup can
     * supply a name, so the reference supplies one directly.
     */
    public static final String MERCHANT_NAME = "BILL PAYMENT";

    /**
     * The merchant city every bill payment carries.
     *
     * <p>Assumptions: the literal is moved at line 228 of that program. The reference writes three
     * characters rather than leaving the field blank, and a written marker and a blank field are
     * distinguishable states in a fixed-width record, so blanking it here would alter the stored
     * bytes and lose the distinction between "not applicable" and "never set".
     */
    public static final String MERCHANT_CITY = "N/A";

    /**
     * The merchant postal code every bill payment carries.
     *
     * <p>Assumptions: the literal is moved at line 229 of that program, on the same footing as
     * {@link #MERCHANT_CITY}: an explicit not-applicable marker rather than an absent value.
     */
    public static final String MERCHANT_ZIP = "N/A";

    /**
     * The declared width of the transaction identifier and of the card number alike.
     *
     * <p>Assumptions: {@code TRAN-ID PIC X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy} and
     * {@code TRAN-CARD-NUM PIC X(16)} at line 15 of that copybook are both sixteen characters and
     * both become fixed-width character columns. A value shorter than the width is not a partial
     * key but one that matches no row, which is why both are padded on the left rather than trimmed.
     */
    public static final int IDENTIFIER_WIDTH = 16;

    /**
     * The declared width of the account identifier.
     *
     * <p>Assumptions: {@code ACTIDINI PIC X(11)} at line 60 of {@code app/cpy-bms/COBIL00.CPY} and
     * {@code CC-ACCT-ID PIC X(11)} at line 34 of {@code app/cpy/CVCRD01Y.cpy} are the same eleven
     * characters, and the account key on the reference master is {@code ACCT-ID PIC 9(11)} at line 5
     * of {@code app/cpy/CVACT01Y.cpy}. The three agree, so eleven is the width an echoed identifier
     * is padded to.
     */
    public static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * The response-body key under which an account-identifier complaint is reported.
     *
     * <p>Assumptions: the key is the request component's own name, because that is what a client
     * matches an error entry against. {@link BillPaymentRequest} declares no property-naming
     * strategy and no per-property annotation, so its component name is also its wire name, and a
     * key spelled anything else would describe a field the client cannot find.
     */
    public static final String ACCOUNT_ID_FIELD = "accountId";

    /**
     * The response-body key under which a confirmation complaint is reported.
     *
     * <p>Assumptions: as for {@link #ACCOUNT_ID_FIELD}, the key is the request component's own name.
     */
    public static final String CONFIRMATION_FIELD = "confirmation";

    /**
     * The complaint the reference makes about a blank account identifier.
     *
     * <p>Assumptions: reproduced character for character from line 161 of
     * {@code app/cbl/COBIL00C.cbl}, abbreviation and trailing points included, because
     * transformation rule T8 carries every user-visible string across verbatim. The abbreviated
     * spelling is <b>not</b> normalised against the equivalent complaint on the transaction-add
     * screen, which line 199 of {@code app/cbl/COTRN02C.cbl} writes out in full as
     * {@code 'Account ID must be Numeric...'}. Two screens spell the same concept two ways in the
     * reference; unifying them would change what an operator reads on at least one of them, and a
     * parity comparison of message text would report the change as a divergence. The two strings are
     * therefore kept apart, each owned by the screen that emits it.
     */
    public static final String MESSAGE_ACCOUNT_ID_EMPTY = "Acct ID can NOT be empty...";

    /**
     * The complaint the reference makes about a confirmation outside its accepted domain.
     *
     * <p>Assumptions: reproduced character for character from line 187 of
     * {@code app/cbl/COBIL00C.cbl}, parentheses and trailing points included. The identical string
     * appears at line 184 of {@code app/cbl/COTRN02C.cbl} for that screen's own out-of-domain
     * branch; the two are equal in the reference and are left equal here, which is a different
     * decision from the deliberate divergence recorded on {@link #MESSAGE_ACCOUNT_ID_EMPTY} and is
     * stated so that neither is mistaken for the other.
     */
    public static final String MESSAGE_INVALID_CONFIRMATION =
            "Invalid value. Valid values are (Y/N)...";

    /**
     * The refusal the reference makes when the account has no outstanding balance to settle.
     *
     * <p>Assumptions: reproduced character for character from line 201 of
     * {@code app/cbl/COBIL00C.cbl}. Lines 198 and 199 are the guard it answers, and the boundary is
     * <b>inclusive of zero</b>: the test is that the stored balance is less than or equal to zero
     * while the account identifier is not blank, so an account standing at exactly nothing is
     * refused rather than paid. A guard written as strictly less than zero would accept a payment of
     * zero and append a transaction the reference never appends. Both operands of that test are read
     * from the account record, so the guard itself belongs to the service that holds the record and
     * this constant is the text it reports.
     */
    public static final String MESSAGE_NOTHING_TO_PAY = "You have nothing to pay...";

    /**
     * The prompt the reference issues when the payment has not yet been confirmed.
     *
     * <p>Assumptions: reproduced character for character from line 237 of
     * {@code app/cbl/COBIL00C.cbl}, which is the branch taken when the confirmation is blank rather
     * than an approval. It is a prompt and not a complaint: line 237 sits under the else of the
     * confirmed test at line 210 and no error flag is set on that path, so the turn is a legitimate
     * submission awaiting an answer.
     */
    public static final String MESSAGE_CONFIRM_PAYMENT = "Confirm to make a bill payment...";

    /**
     * The report the reference makes when an account identifier resolves to no record.
     *
     * <p>Assumptions: reproduced character for character from line 361 of
     * {@code app/cbl/COBIL00C.cbl}, where the account read answers not-found. The identical string
     * is emitted from two further sites in the same program, at line 392 when the account rewrite
     * answers not-found and at line 425 when the cross-reference read does, so one constant serves
     * all three rather than three constants that could drift apart. This is also the only complaint
     * the reference makes about an account identifier that is present but unusable: it performs no
     * numeric or width edit on this screen before reading, so a malformed identifier is reported as
     * a lookup miss and no other message is invented for it.
     */
    public static final String MESSAGE_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /**
     * The report the reference makes when the account read fails for any reason but not-found.
     *
     * <p>Assumptions: reproduced character for character from line 368 of
     * {@code app/cbl/COBIL00C.cbl}, the other arm of the same evaluation as
     * {@link #MESSAGE_ACCOUNT_NOT_FOUND}. The reference distinguishes a missing record from a failed
     * read, so the two strings are kept distinct here as well; collapsing them would tell an
     * operator that an account does not exist when the truth is that it could not be reached.
     */
    public static final String MESSAGE_ACCOUNT_LOOKUP_FAILED = "Unable to lookup Account...";

    /**
     * The report the reference makes when the balance-reducing account update fails.
     *
     * <p>Assumptions: reproduced character for character from line 399 of
     * {@code app/cbl/COBIL00C.cbl}, capitalisation of the verb included -- the reference writes the
     * update verb with a capital where the two lookup messages write theirs in lower case, and that
     * inconsistency is carried across rather than tidied, because tidying it changes a string an
     * operator reads.
     */
    public static final String MESSAGE_ACCOUNT_UPDATE_FAILED = "Unable to Update Account...";

    /**
     * The report the reference makes when the card cross-reference read fails.
     *
     * <p>Assumptions: reproduced character for character from line 432 of
     * {@code app/cbl/COBIL00C.cbl}. The reference names the alternate-index path in the text it shows
     * an operator; the target reaches the same rows through a secondary index on the account column
     * instead, and the string is nonetheless carried unchanged, because rule T8 admits no editorial
     * improvement of a user-visible string and a reworded message would report a condition the
     * reference never reported.
     */
    public static final String MESSAGE_XREF_LOOKUP_FAILED = "Unable to lookup XREF AIX file...";

    /**
     * The report the reference makes when reaching the transaction file for the next key fails.
     *
     * <p>Assumptions: reproduced character for character from line 463 of
     * {@code app/cbl/COBIL00C.cbl}, where the browse start fails; the identical string is emitted at
     * line 492 when the backward read fails, so one constant serves both sites of the same
     * key-minting sequence.
     */
    public static final String MESSAGE_TRANSACTION_LOOKUP_FAILED =
            "Unable to lookup Transaction...";

    /**
     * The leading fragment of the confirmation sentence a successful payment carries.
     *
     * <p>Assumptions: the reference builds that sentence from four fragments at lines 527 to 531 of
     * {@code app/cbl/COBIL00C.cbl} rather than from one literal, and the first two fragments are
     * concatenated whole. This constant is the first of them and its trailing space is part of the
     * value: the second fragment opens with a space of its own, so the assembled sentence carries
     * <b>two</b> spaces between the two clauses. Trimming either fragment would produce a sentence
     * one byte shorter than the reference's and would fail a byte-level comparison of message text,
     * so both spaces are preserved and the reason is recorded here rather than left to be rediscovered.
     */
    public static final String MESSAGE_PAYMENT_SUCCESSFUL_PREFIX = "Payment successful. ";

    /**
     * The second fragment of the confirmation sentence a successful payment carries.
     *
     * <p>Assumptions: reproduced character for character from the fragment at line 528 of
     * {@code app/cbl/COBIL00C.cbl}, including its leading space, which is the second of the two
     * spaces recorded on {@link #MESSAGE_PAYMENT_SUCCESSFUL_PREFIX}, and its trailing space, which
     * separates the clause from the identifier that follows it.
     */
    public static final String MESSAGE_PAYMENT_SUCCESSFUL_INFIX = " Your Transaction ID is ";

    /**
     * The closing fragment of the confirmation sentence a successful payment carries.
     *
     * <p>Assumptions: reproduced from the fragment at line 530 of {@code app/cbl/COBIL00C.cbl}. It is
     * declared as a named fragment rather than appended as a bare character so that all three
     * fragments of one reference sentence are visible together and none can be edited without the
     * others being seen.
     */
    public static final String MESSAGE_PAYMENT_SUCCESSFUL_SUFFIX = ".";

    /**
     * Builds the ledger row a confirmed bill payment appends.
     *
     * <p>Assumptions: the amount written is the balance as it stood <b>before</b> the payment, and
     * statement order in the reference is the whole proof of that. Line 193 of
     * {@code app/cbl/COBIL00C.cbl} moves {@code ACCT-CURR-BAL} into a working field and line 194
     * moves that field onto the screen, both before anything is written; line 224 moves the same
     * untouched value into {@code TRAN-AMT}; line 233 writes the transaction; and only then does line
     * 234 compute {@code ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}, with line 235 rewriting the
     * account. Handing in the balance read <b>after</b> that subtraction would write an amount that
     * is invariably zero, because line 224 moves the whole balance into the amount line 234 then
     * subtracts, so the row would look plausible and carry no information.
     *
     * <p>Assumptions: eight of the ten values the reference moves at lines 220 to 229 are literals
     * and are supplied from this class's own constants; the remaining two are the amount at line 224
     * and the card number at line 225, both of which are read elsewhere and therefore arrive as
     * arguments. That split is why the signature is five parameters wide rather than two.
     *
     * <p>Assumptions: the identifier is minted by the caller and never accepted from a client, and
     * the reason is a property of the reference sequence rather than a preference. Lines 212 to 219
     * derive it by browsing the transaction file backwards from high values, recovering the last
     * identifier and adding one, and none of those four verbs holds a lock, so two payments running
     * together can derive the same next value. Keeping the mint in the service is what lets the
     * resulting collision be handled where the transaction boundary is, which is not here.
     *
     * <p>Assumptions: the account identifier on the request does not appear on the row at all.
     * {@code app/cpy/CVTRA05Y.cpy} declares no account field, so an account is reachable only through
     * the card number, which is why line 211 reads the cross-reference and line 225 stores what it
     * returns. The request is consequently read for its presence and for nothing else in this
     * conversion, and the caller supplies the resolved card number it obtained.
     *
     * @param request the confirmed submission, whose presence is what authorises the row; must not be
     *     {@code null}
     * @param transactionId the identifier the caller minted for this payment, as digit characters,
     *     which is padded on the left with zeros to {@link #IDENTIFIER_WIDTH}; must not be
     *     {@code null}
     * @param resolvedCardNumber the card number the caller obtained from the cross-reference, as
     *     digit characters, which is padded on the left with zeros to {@link #IDENTIFIER_WIDTH};
     *     must not be {@code null}
     * @param currentBalance the account balance as it stood <b>before</b> the payment, which becomes
     *     the transaction amount. It is a scalar rather than the account record because the account
     *     belongs to a different bounded context, as recorded on this class; the caller is the party
     *     that read it and is also the party that subtracts it afterwards, which keeps the append and
     *     the balance reduction inside the single unit of work lines 233 to 235 perform. Must not be
     *     {@code null}
     * @param paymentTimestamp the instant the caller captured for this payment, reduced to the
     *     resolution the timestamp contract carries and then written to <b>both</b> the originating
     *     and the processing member; must not be {@code null}
     * @return the row carrying the eight bill-payment literals, the supplied amount, the supplied
     *     card number and both timestamp members set to the one reduced instant, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the identifier or the card number is empty, holds a
     *     character other than a digit, or is longer than its declared width. The year of
     *     {@code paymentTimestamp} is deliberately <b>not</b> range-checked here: the reduction this
     *     method performs carries no such check, and {@link #renderTimestamp} is where a year the
     *     contract width cannot express is refused, because that is where the width applies
     */
    public Transaction toEntity(BillPaymentRequest request, String transactionId,
            String resolvedCardNumber, Money currentBalance, LocalDateTime paymentTimestamp) {

        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(currentBalance, "currentBalance must not be null:"
                + " the reference always has a balance to pay on a confirmed payment");

        // WHY : Assumptions: one instant is reduced once and then written to both members, rather
        //       than each member taking its own reading. Lines 231 and 232 of COBIL00C.cbl are a
        //       SINGLE move statement with two receiving fields, so the two are equal by
        //       construction in the reference and not by two clock reads landing in the same
        //       microsecond. Two readings here would be equal almost always and unequal
        //       occasionally, which is the worst available behaviour: a parity comparison would pass
        //       repeatedly and then fail once for a reason no fixture reproduces.
        // WHY : Assumptions: the reduction is performed through the shared formatter rather than
        //       here, so the stored value and the twenty-six character rendering that
        //       renderTimestamp returns derive from ONE truncation. Reducing in two places is how a
        //       row comes to be stored one microsecond away from the text written beside it.
        LocalDateTime paymentInstant = TimestampFormatter.normalize(paymentTimestamp);

        return new Transaction(
                zeroPaddedDigits(transactionId, IDENTIFIER_WIDTH, "transactionId"),
                TRANSACTION_TYPE_CODE,
                TRANSACTION_CATEGORY_CODE,
                TRANSACTION_SOURCE,
                TRANSACTION_DESCRIPTION,
                // WHY : Assumptions: the scalar decimal is taken out of the exact amount here
                //       because the entity member is the stored column's own type, while every
                //       crossing of a wire boundary carries the exact type instead. The sign
                //       survives the crossing and has to: app/data/ASCII/dailytran.txt holds 300
                //       records whose amount occupies the eleven characters at positions 133 to
                //       143, and the second of those records ends that field in a sign overpunch
                //       that decodes to a NEGATIVE amount. An amount path that lost the overpunch
                //       would read that record as positive and post it the wrong way.
                currentBalance.amount(),
                MERCHANT_ID,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_ZIP,
                zeroPaddedDigits(resolvedCardNumber, IDENTIFIER_WIDTH, "resolvedCardNumber"),
                paymentInstant,
                paymentInstant);
    }

    /**
     * Renders the instant a payment was stamped with in the twenty-six character contract form.
     *
     * <p>Assumptions: the reference stores both timestamps as text, not as a temporal type --
     * {@code TRAN-ORIG-TS PIC X(26)} at line 16 of {@code app/cpy/CVTRA05Y.cpy} and
     * {@code TRAN-PROC-TS PIC X(26)} at line 17 -- while the target stores them as microsecond
     * columns. The character rendering therefore still has to exist somewhere, because it is the
     * form a migrated row is compared against a reference extract in, and {@code tests/README.md}
     * records at its section on determinism that processing timestamps are normalised before any such
     * comparison. This method is that rendering, and it is public because the comparison is performed
     * outside this class.
     *
     * <p>Assumptions: the form this screen produces is the <b>zero-microsecond</b> one, and it is
     * fully determined by lines 255 to 266 of {@code app/cbl/COBIL00C.cbl}. The formatting call at
     * lines 255 to 261 asks for an eight-digit date rendered with hyphen separators and a time
     * rendered with colon separators; line 263 initialises the group; lines 264 and 265 place the
     * date at the first ten characters and the time at characters twelve to nineteen; and line 266
     * moves zeros into the six-digit fractional member. Characters eleven and twenty are written by
     * none of those statements. They survive because an initialise leaves filler items alone, so the
     * literal space declared at line 48 of {@code app/cpy/CSDAT01Y.cpy} and the literal point
     * declared at line 54 remain in place -- and the group those declarations belong to, lines 42 to
     * 55 of that copybook, sums to exactly twenty-six characters.
     *
     * <p>Assumptions: <b>three timestamp forms coexist in the reference and none of them is
     * normalised onto another.</b> This screen writes the zero-microsecond form just described. The
     * transaction-add screen writes something different: lines 464 and 465 of
     * {@code app/cbl/COTRN02C.cbl} move two ten-character screen fields into the two twenty-six
     * character members, so a row appended through that screen holds ten characters of date followed
     * by sixteen spaces and carries no time at all. The nightly posting program writes a third: line
     * 437 of {@code app/cbl/CBTRN02C.cbl} obtains a database-format timestamp and line 438 moves that
     * into the processing member, while line 436 copies the incoming originating timestamp through
     * unchanged, so its two members are not even equal to each other. A reader arriving from this
     * package's transaction conversions will have met the second form and should not expect it here;
     * folding the three into one would change the stored text of two of the three paths.
     *
     * @param paymentTimestamp the instant to render, which is reduced to the contract's resolution
     *     before rendering so that the text and any value stored from the same instant agree; must
     *     not be {@code null}
     * @return the rendered instant, exactly {@link TimestampFormatter#TIMESTAMP_LENGTH} characters of
     *     the form {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'}, never {@code null}
     * @throws NullPointerException if {@code paymentTimestamp} is {@code null}, because a timestamp
     *     member under this contract has no rendering for an absent value
     * @throws IllegalArgumentException if the year of {@code paymentTimestamp} lies outside the range
     *     the contract width can express
     */
    public String renderTimestamp(LocalDateTime paymentTimestamp) {
        // WHY : Assumptions: the shared formatter is called rather than a pattern being declared
        //       here. It reduces the fraction and renders in one operation, so this method cannot
        //       produce text that disagrees with the value toEntity stores from the same instant --
        //       which is the failure a locally declared pattern invites, since a pattern renders
        //       whatever fraction it is handed.
        return TimestampFormatter.format(paymentTimestamp);
    }

    /**
     * Converts an appended bill payment into the acknowledgement the screen shows.
     *
     * <p>Assumptions: the balance this acknowledgement reports is the <b>pre-payment</b> figure, and
     * it is therefore also the amount that was paid -- one number serving two meanings. The reference
     * proves it by what it does not do: lines 193 and 194 of {@code app/cbl/COBIL00C.cbl} fill the
     * balance field, line 224 reuses that same untouched value as the transaction amount, line 233
     * writes, line 234 subtracts and line 235 rewrites the account, and <b>nothing repopulates the
     * balance field</b> before the send at line 242. What the operator is shown after a successful
     * payment is consequently the balance from before it. Reading the figure off the row's own amount
     * here rather than off the account is what makes the acknowledgement describe the record that was
     * actually persisted.
     *
     * <p>Assumptions: <b>there is no post-payment balance component and adding one would have no
     * warrant in the reference.</b> {@link BillPaymentResponse} declares no such member, the screen
     * declares no field that could display one, and the value it would carry is invariably zero,
     * because line 224 moves the whole balance into the amount that line 234 then subtracts. A caller
     * that needs the balance the account is left holding re-reads the account from the context that
     * owns it.
     *
     * <p>Assumptions: the balance crosses as the shared exact-amount type and not as a bare decimal,
     * and the choice of declared type is what selects the wire form rather than any annotation on the
     * component. {@code com.carddemo.common.money.MoneyModule} binds its serialiser to the exact
     * type, so a component declared as a bare decimal compiles, runs, and silently emits a JSON
     * number -- which most clients then parse into an IEEE-754 binary value and lose exactness on.
     * Declaring the exact type is what keeps the amount a quoted decimal string end to end.
     *
     * <p>Assumptions: the balance's declared width on this screen is deliberately wider than the
     * amount field on the transaction-add screen, and the two must not be conflated. {@code CURBALI}
     * is {@code PIC X(14)} at line 66 of {@code app/cpy-bms/COBIL00.CPY} -- a sign, ten integer
     * digits, a point and two decimals -- because it renders
     * {@code ACCT-CURR-BAL PIC S9(10)V99} from line 7 of {@code app/cpy/CVACT01Y.cpy}, which carries
     * ten integer digits. {@code TRNAMTI} is {@code PIC X(12)} at line 96 of
     * {@code app/cpy-bms/COTRN02.CPY}, which is eight integer digits, because it renders a
     * transaction amount. Reusing the narrower width here would refuse a balance the account master
     * demonstrably stores.
     *
     * <p>Assumptions: the account identifier is echoed from the request rather than read from the
     * row, because the row has none -- {@code app/cpy/CVTRA05Y.cpy} declares no account field, as
     * recorded on {@link #toEntity}. The field the reference redisplays at line 242 is the one the
     * operator keyed, so echoing the submitted value reproduces exactly that.
     *
     * @param request the submission whose account identifier is echoed back; must not be {@code null}
     * @param transaction the appended row, whose identifier and amount must both be present; must not
     *     be {@code null}
     * @param returnMessage the sentence accompanying the payment, or {@code null} when there is none;
     *     every spelling of absence is collapsed onto {@code null} and an over-width value is refused,
     *     as recorded on {@link #returnMessageOrAbsent}
     * @return the acknowledgement carrying the minted identifier, the echoed account identifier, the
     *     pre-payment balance, the fixed paid discriminator and the message, never {@code null}
     * @throws NullPointerException if either {@code request} or {@code transaction} is {@code null},
     *     or if the row carries no transaction identifier or no amount
     * @throws IllegalArgumentException if the request's account identifier is empty, holds a character
     *     other than a digit, or is longer than {@link #ACCOUNT_ID_WIDTH}; and if
     *     {@code returnMessage} is wider than the message contract renders
     * @throws ArithmeticException if the stored amount cannot be reduced to the two decimal places the
     *     money contract carries
     */
    public BillPaymentResponse toResponse(BillPaymentRequest request, Transaction transaction,
            String returnMessage) {

        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(transaction, "transaction must not be null");

        // WHY : Assumptions: the acknowledgement is built through the record's own posted factory
        //       rather than through its canonical constructor, because the shape carries a paid
        //       discriminator whose only correct value on this path is the affirmative one.
        //       Constructing it directly would put that value at this call site, where a later edit
        //       could publish a body reporting that no payment was made about a transaction row that
        //       demonstrably exists -- and, to a client reading the body without its status code,
        //       that body is indistinguishable from a preview. The factory fixes the value, so this
        //       method cannot express the wrong state at all.
        // WHY : Trade-offs: the two identifiers on this shape are treated differently and the split
        //       is deliberate. The account identifier is caller-supplied, so it is guarded and
        //       normalised to its declared width on the way out, which is what lets a client compare
        //       the echoed value character for character with the one it submitted. The transaction
        //       identifier is this row's own primary key, already canonical because toEntity above
        //       wrote it that way, so it is echoed exactly as stored: re-normalising a stored key
        //       would report a value that differs from the one the row is addressed by, and the
        //       column is declared as a fixed-width character type, so a padded value read back
        //       would be refused by the digit guard rather than corrected by it.
        return BillPaymentResponse.posted(
                zeroPaddedDigits(request.accountId(), ACCOUNT_ID_WIDTH, ACCOUNT_ID_FIELD),
                requiredAmount(transaction.getTranAmt()),
                Objects.requireNonNull(transaction.getTranId(),
                        "tranId must not be null: it is this row's primary key"),
                returnMessageOrAbsent(returnMessage));
    }

    /**
     * Assembles the confirmation sentence the reference reports after a successful payment.
     *
     * <p>Assumptions: the reference does not hold this sentence as one literal. Lines 527 to 531 of
     * {@code app/cbl/COBIL00C.cbl} concatenate four fragments into the message field -- two literals
     * taken whole, the transaction identifier, and a closing point -- so it is assembled here from
     * the same four in the same order. The two spaces that fall between the opening clause and the
     * second are both real, one trailing the first fragment and one leading the second, and they are
     * recorded on {@link #MESSAGE_PAYMENT_SUCCESSFUL_PREFIX} because a reader would otherwise read
     * the repeated space as a typing slip and remove it.
     *
     * <p>Assumptions: the identifier contributes its full declared width. The reference delimits that
     * fragment at a space rather than at its size, which would stop the fragment at the first space
     * the field contains -- but the field cannot contain one: line 219 moves a sixteen-digit numeric
     * value into {@code TRAN-ID PIC X(16)}, so all sixteen character positions hold digits. The
     * delimiter therefore has no effect on this screen's output, and the padded identifier is used
     * whole rather than trimmed, which is what keeps the assembled sentence byte-identical to the
     * reference's.
     *
     * @param transactionId the identifier the payment was written under, as digit characters, which is
     *     padded on the left with zeros to {@link #IDENTIFIER_WIDTH} exactly as the stored key is;
     *     must not be {@code null}
     * @return the assembled sentence, never {@code null} and never wider than the message contract
     *     renders, since its three fragments and the padded identifier total well under that width
     * @throws NullPointerException if {@code transactionId} is {@code null}
     * @throws IllegalArgumentException if {@code transactionId} is empty, holds a character other than
     *     a digit, or is longer than {@link #IDENTIFIER_WIDTH}
     */
    public String paymentSuccessfulMessage(String transactionId) {
        return MESSAGE_PAYMENT_SUCCESSFUL_PREFIX
                + MESSAGE_PAYMENT_SUCCESSFUL_INFIX
                + zeroPaddedDigits(transactionId, IDENTIFIER_WIDTH, "transactionId")
                + MESSAGE_PAYMENT_SUCCESSFUL_SUFFIX;
    }

    /**
     * Converts the validation state of the screen's two inbound fields into the per-field error array
     * a refused submission answers with.
     *
     * <p>Refactoring Rationale: the mechanism being replaced is the reference's field-highlight
     * template, and what was wrong with it is that it depended on remembered turn state supplied by
     * the client. {@code app/cpy/CSSETATY.cpy} is a templated copybook expanded once per validated
     * field: lines 18 and 19 form one disjunctive test over that field's not-ok and blank flags, and
     * line 20 conjoins the pseudo-conversational re-entry discriminator, so the highlight fires only
     * on a turn the client itself declared to be a re-entry. A stateless handler has no such
     * discriminator and no turn to remember, so here the highlight is driven purely by the response
     * body: an entry in this array is present or absent, and no third input decides whether the client
     * renders it.
     *
     * <p>Assumptions: the marker the reference writes into a blank field travels as a property of the
     * entry and is never prepended to a value, and the copybook itself is what settles that by writing
     * the two effects into two different places. Lines 21 and 22 move the error colour into the
     * field's colour subfield, while lines 24 and 25 move the literal marker into the field's output
     * subfield -- and the symbolic map declares those as two separate members of the same field
     * group, {@code ERRMSGC PICTURE X} at line 136 and {@code ERRMSGO PIC X(78)} at line 140 of
     * {@code app/cpy-bms/COBIL00.CPY}, in an output half that redefines the input half at line 79.
     * The shared validation flag carries the marker for the blank state, so nothing here composes it
     * into text.
     *
     * <p>Assumptions: the blank state is a refinement of the error state rather than a third
     * alternative to it, because lines 18 and 19 fire for either flag. Both states therefore produce
     * an entry and only the marker on that entry differs.
     *
     * <p>Assumptions: <b>a never-supplied confirmation is not an error on this screen</b>, so a
     * caller reports it as acceptable and this method refuses the blank state for that field rather
     * than inventing a complaint for it. Lines 182 and 183 of {@code app/cbl/COBIL00C.cbl} send a
     * blank or low-value confirmation to the account read with no error flag set, and line 237 answers
     * that turn with a prompt. Emitting a field error for it would report a rejected submission where
     * the reference reports an unanswered one, so the state is refused where it can be named instead
     * of being silently mapped onto the out-of-domain complaint.
     *
     * <p>Assumptions: <b>this screen's confirmation semantics differ from the transaction-add
     * screen's and the two are not unified.</b> Lines 173 to 191 of {@code app/cbl/COBIL00C.cbl} are
     * a four-way evaluation with four distinct outcomes: an approval at lines 174 and 175 reads the
     * account and proceeds; a decline at lines 178 and 179 performs the screen clear at line 180 and
     * <b>then</b> sets the error flag at line 181, so a decline abandons the turn; a blank at lines
     * 182 and 183 reads the account with no error; and anything else falls to line 185 and is answered
     * by the complaint at line 187. Lines 173 to 181 of {@code app/cbl/COTRN02C.cbl} arrange the same
     * field quite differently -- a decline, a blank and a low-value answer all fall through together
     * to that screen's own confirmation prompt at line 178 -- so the same keystroke means "abandon"
     * on this screen and "you have not answered yet" on that one. Folding the two branches into one
     * shared reading would change the behaviour of one of the two screens.
     *
     * <p>Assumptions: the entries come back in the reference's own reporting order rather than
     * alphabetised or grouped, and that order is the account identifier first. The reference is a
     * short-circuit chain gated by the three error-flag tests at lines 169, 197 and 208: a blank
     * account identifier is caught at line 159 and reported at line 161 before the confirmation is
     * examined at line 173 at all, so the account identifier is the field a reader expects to see
     * first.
     *
     * @param accountIdState the validated state of the account identifier, whose blank case is the
     *     reference's own complaint at line 161 and whose otherwise-unacceptable case is reported with
     *     the lookup text at line 361; must not be {@code null}
     * @param confirmationState the validated state of the confirmation, which must not be the blank
     *     state because a never-supplied confirmation is an unanswered turn on this screen rather than
     *     a rejected one; must not be {@code null}
     * @return an unmodifiable array holding one entry per field in error, the account identifier first,
     *     and empty when both fields are acceptable; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code confirmationState} is the blank state
     */
    public List<ApiError.FieldError> toFieldErrors(FieldValidationFlag accountIdState,
            FieldValidationFlag confirmationState) {

        Objects.requireNonNull(accountIdState, "accountIdState must not be null");
        Objects.requireNonNull(confirmationState, "confirmationState must not be null");

        // WHY : Assumptions: the state is compared to the never-supplied constant rather than asked
        //       whether it carries a screen marker. The two predicates coincide today, because the
        //       marker belongs to that one state, but they answer different questions -- one is about
        //       which reference branch a submission took and the other about how a client renders a
        //       field -- and a guard written against the rendering question would change meaning if
        //       the marker's ownership ever did.
        if (confirmationState == FieldValidationFlag.BLANK) {
            throw new IllegalArgumentException("confirmationState must not be the blank state:"
                    + " a never-supplied confirmation drives the prompt branch rather than a"
                    + " refusal, so it is reported as acceptable and carries no field error");
        }

        // WHY : Alternatives Considered: the shared kernel also offers a map-driven collector that
        //       resolves each field's help text from its identity alone. It is rejected here because
        //       the account identifier's text depends on its STATE and not only on its name -- the
        //       blank case is answered at line 161 and any other unacceptable case at line 361 -- and
        //       a resolver keyed on identity cannot see the state. Composing the two entries directly
        //       keeps each message beside the state that selects it; the ordered list is built by hand
        //       for the same reason, and the reporting order recorded above is what that order is.
        List<FieldValidationFlag.FieldError> reported = new ArrayList<>(2);
        accountIdState.toFieldError(ACCOUNT_ID_FIELD, accountIdMessage(accountIdState))
                .ifPresent(reported::add);
        confirmationState.toFieldError(CONFIRMATION_FIELD, MESSAGE_INVALID_CONFIRMATION)
                .ifPresent(reported::add);

        // WHY : Assumptions: the validation entries are converted into the response package's own
        //       entry type rather than published as they are. The two records carry the same three
        //       components deliberately and stay distinct types, which is what lets the validation
        //       kernel be written without depending on the response package and keeps the dependency
        //       arrow between them pointing one way, as transformation rule T2 requires.
        return ApiError.FieldError.fromAll(reported);
    }

    /**
     * Selects the reference's own complaint for an unacceptable account identifier.
     *
     * <p>Assumptions: the reference makes exactly two complaints about this field and they are chosen
     * by state, not by field. A blank identifier is caught before any file is read and answered at
     * line 161 of {@code app/cbl/COBIL00C.cbl}. A present but unusable one gets no edit at all on this
     * screen -- there is no numeric test and no width test before the read -- so the reference reports
     * whatever the read returns, which is the not-found text at line 361. Selecting that text here is
     * what avoids inventing a third string the specification does not carry; the alternative of
     * fabricating a format complaint would put a sentence in front of an operator that no line of the
     * reference emits.
     *
     * <p>Assumptions: no argument check is written here and none is reachable. The comparison below
     * treats a null as "not the never-supplied state", so it cannot raise anything, and the only
     * caller has already refused a null state by the time it arrives. Adding a check would document a
     * failure the enclosing method has already made impossible.
     *
     * @param accountIdState the validated state of the account identifier, which the caller has
     *     already established is not {@code null}
     * @return the blank-case complaint when the state is the never-supplied one, and otherwise the
     *     not-found report; never {@code null}
     */
    private static String accountIdMessage(FieldValidationFlag accountIdState) {
        // WHY : Assumptions: the comparison is against the never-supplied constant for the reason
        //       recorded on the caller -- the choice of message follows which reference branch the
        //       submission took, not how a client renders the field.
        return accountIdState == FieldValidationFlag.BLANK
                ? MESSAGE_ACCOUNT_ID_EMPTY
                : MESSAGE_ACCOUNT_NOT_FOUND;
    }

    /**
     * Wraps a stored decimal amount in the exact money type the response component declares.
     *
     * <p>Assumptions: an absent amount is refused rather than reported as absent. The column is
     * declared without a not-null constraint, yet the reference record has no absent state for it:
     * {@code TRAN-AMT PIC S9(09)V99} at line 10 of {@code app/cpy/CVTRA05Y.cpy} occupies eleven bytes
     * of every 350-byte record and a zoned-decimal field always decodes to a number. An absent amount
     * is therefore a defect in the row rather than a state the reference can produce, and refusing
     * here names the row that carries it instead of emitting a null into a component documented as
     * always present.
     *
     * @param storedAmount the value read from the amount column; must not be {@code null}
     * @return the same value as an exact amount at two decimal places, never {@code null}
     * @throws NullPointerException if {@code storedAmount} is {@code null}
     * @throws ArithmeticException if the value declares a scale or a magnitude the money contract
     *     cannot reduce to two decimal places
     */
    private static Money requiredAmount(BigDecimal storedAmount) {
        return Money.of(Objects.requireNonNull(storedAmount,
                "tranAmt must not be null: the reference record has no absent amount, so an absent"
                        + " value is a defect in the row rather than a state to report"));
    }

    /**
     * Collapses every spelling of an absent message onto no value, and refuses one too wide to render.
     *
     * <p>Refactoring Rationale: the message regime being replaced is this program's own, and it has
     * two specific faults. The reference carries {@code WS-MESSAGE PIC X(80)} at line 39 of
     * {@code app/cbl/COBIL00C.cbl} and renders it at line 293 into {@code ERRMSGO PIC X(78)} at line
     * 140 of {@code app/cpy-bms/COBIL00.CPY}: eighty characters into seventy-eight, so the final two
     * disappear on every send with no signal that anything was lost. And a message field of declared
     * width has no way to say "nothing to report" that is distinct from "a message of blanks", so an
     * unset message and an empty one are the same bytes. The target adopts the seventy-five character
     * {@code CCARD-RETURN-MSG} form at line 29 of {@code app/cpy/CVCRD01Y.cpy} instead, whose
     * low-values condition at line 30 gives absence a representation of its own; absence maps to no
     * value here, and a value wider than the form renders is refused where the reference would have
     * silently narrowed it. The divergence is documented rather than hidden.
     *
     * <p>Assumptions: that low-values condition is attached to the return message and to that field
     * alone -- {@code CCARD-ERROR-MSG} at line 28 of the same copybook carries none -- so absence is
     * representable for exactly the field this response component maps to, which is why no value is
     * the right rendering of it and blanks are not substituted for it.
     *
     * <p>Assumptions: the width the refusal is measured against is the shared kernel's own published
     * rendering width rather than a number written here, so the response shape and the problem shape
     * are measured against one symbol and cannot come to disagree.
     *
     * @param message the message as supplied, which may be {@code null}, empty, a run of spaces, a run
     *     of low values, or genuine text
     * @return no value when the message is any spelling of absence, and otherwise the message
     *     unchanged
     * @throws IllegalArgumentException if the message is present and longer than
     *     {@link ApiError#MESSAGE_RENDERING_WIDTH} characters
     */
    private static String returnMessageOrAbsent(String message) {
        // WHY : Assumptions: the shared absence predicate is called rather than a local blank test.
        //       It folds a null, an empty value, a run of spaces AND a run of low values onto one
        //       answer, which the platform's general blank test does not: that test would treat a tab
        //       as absent and a run of low values as present, wrong on both counts against the
        //       figurative constants the reference compares against.
        if (FieldValidationFlag.isNeverSupplied(message)) {
            return null;
        }
        if (message.length() > ApiError.MESSAGE_RENDERING_WIDTH) {
            throw new IllegalArgumentException("returnMessage must be at most "
                    + ApiError.MESSAGE_RENDERING_WIDTH + " characters, because that is the width the"
                    + " return-message contract renders; a wider value would be narrowed silently");
        }
        return message;
    }

    /**
     * Pads a run of digits on the left with zeros to a fixed width, refusing anything that is not
     * digits.
     *
     * <p>Assumptions: an identifier is carried as digit characters and padded rather than held as a
     * number, and the reference settles this itself. Line 55 of {@code app/cbl/COTRN02C.cbl} declares
     * a numeric work field of eleven digits; lines 204 and 205 of that program compute it from the
     * keyed characters through the numeric-value function; and lines 206 and 207 move the numeric
     * result straight back onto the eleven-character screen field, which left-fills it with zeros to
     * the full width. Padding on the left with zeros is therefore the reference's own normalisation of
     * a keyed identifier, not a convention adopted here. The house declares the same idea structurally
     * at lines 34 and 36 of {@code app/cpy/CVCRD01Y.cpy}, where the account identifier is declared
     * once as eleven characters and once as eleven digits over the same bytes -- cited as a house
     * idiom rather than as this program's own, because {@code app/cbl/COBIL00C.cbl} does not copy that
     * book at all.
     *
     * <p>Assumptions: the committed extract makes the consequence measurable rather than theoretical.
     * {@code app/data/ASCII/dailytran.txt} holds 300 records of 350 bytes, and 30 of them carry a card
     * number whose first character is a zero. A numeric identifier would drop that leading zero on the
     * way out while still comparing equal on the way in, which is a silent corruption of a tenth of
     * that extract. No example value is reproduced here: a primary account number does not belong in
     * source prose even when it comes from a committed extract, and the count is what the decision
     * rests on.
     *
     * <p>Trade-offs: a value longer than the width is refused rather than truncated. Truncating a key
     * silently addresses a different row, and there is no rendering in which that outcome is preferable
     * to a refusal that names the argument which failed.
     *
     * @param value the digits to pad; must not be {@code null}
     * @param width the declared width to pad to, taken from the copybook picture clause of the field
     *     being written
     * @param component the argument name reproduced in a refusal so that the message names the value
     *     which failed rather than the check that caught it; must not be {@code null}
     * @return the value padded on the left with zeros to the declared width, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the value is empty, holds a character other than a digit, or
     *     is longer than the declared width
     */
    private static String zeroPaddedDigits(String value, int width, String component) {
        Objects.requireNonNull(value, component + " must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(component + " must not be empty");
        }
        if (value.length() > width) {
            throw new IllegalArgumentException(component + " must be at most " + width
                    + " characters, because the stored column is that wide");
        }
        // WHY : Assumptions: the ten digit characters are tested by range rather than through the
        //       platform's digit predicate, which answers true for the decimal digits of every script
        //       the character set defines. The reference accepts the ten ASCII digits and nothing
        //       else, so a value carrying a digit from another script would satisfy the predicate and
        //       then match no row, having passed a check that read as though it had verified the
        //       opposite.
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(component
                        + " must contain digits only before it is used as a key");
            }
        }
        return "0".repeat(width - value.length()) + value;
    }
}
