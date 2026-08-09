package com.carddemo.account.service;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.Customer;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.dto.AccountUpdateResponse;
import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.common.validation.FieldValidationFlag;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Edits a submitted account and customer change and applies it under a concurrency precondition.
 *
 * <p>Purpose: this is the migrated form of the edit and write halves of {@code app/cbl/COACTUPC.cbl},
 * the CICS transaction {@code CAUP}. Three regions of that program land here. Its edit driver
 * {@code 1200-EDIT-MAP-INPUTS} at L1429, whose exit is L1678, becomes
 * {@link #editMapInputs(AccountUpdateRequest, Account, Customer)}. The edit routines spanning L1783 to
 * L2536 each become one named method so that a paragraph and a method can be cited as a pair. Its write
 * path {@code 9600-WRITE-PROCESSING} at L3888, whose exit is L4105, becomes
 * {@link #update(long, AccountUpdateRequest, String)}, and the change comparison
 * {@code 9700-CHECK-CHANGE-IN-REC} at L4109, whose body ends at L4192, becomes the precondition that
 * method enforces.</p>
 *
 * <h2>The edit routines are parameterised, not one method per field</h2>
 *
 * <p>Refactoring Rationale: the baseline passes arguments to its edit routines through a block of shared
 * {@code WORKING-STORAGE} declared as {@code 05 WS-GENERIC-EDITS.} at {@code app/cbl/COACTUPC.cbl} L52
 * and running to L80. The in-parameters are {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at L53, the field
 * label; {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at L61, the value; and
 * {@code WS-EDIT-ALPHANUM-LENGTH PIC S9(4) COMP-3} at L62, the width to examine. The out-parameters are
 * the marker bytes at L56 to L59 and L64 to L80. A caller moves the label, moves the value, moves the
 * length, performs the routine and then moves the marker into the field's own marker, which is visible
 * at L1605 to L1611 and again at L1615 to L1620. Twenty-seven such invocations reach those routines
 * across the program, so the routines are already generic and are already reused; what makes them read
 * as one-offs is only that their arguments live in shared storage rather than on the call. Here the
 * label, the value and the width become method parameters and the marker becomes the return value, so
 * the shared block disappears entirely. That is the concrete realisation of this migration's
 * constructor-injection-over-shared-storage design decision: two edits can no longer interfere by
 * leaving a value behind in a field the next one reads.</p>
 *
 * <h2>Where a verdict is reached, and where it is not</h2>
 *
 * <p>Assumptions: three families of edit are DELEGATED rather than restated. The date edits are
 * {@code EDIT-DATE-CCYYMMDD}, which the program obtains by {@code COPY 'CSUTLDWY'.} at
 * {@code app/cbl/COACTUPC.cbl} L166 and performs four times, at L1479 to L1482 for the open date, L1491
 * to L1494 for the expiry date, L1504 to L1507 for the reissue date and L1536 to L1538 for the date of
 * birth; they belong to {@link DateEditValidator} in the shared kernel because the copybook is shared.
 * The telephone, state-code and state-with-postal-prefix edits are {@code 1260-EDIT-US-PHONE-NUM} at
 * L2225 -- together with the three unnumbered parts it performs, {@code EDIT-AREA-CODE} at L2246,
 * {@code EDIT-US-PHONE-PREFIX} at L2316 and {@code EDIT-US-PHONE-LINENUM} at L2370, which fall through
 * to one another and reach the shared exit at L2424 ahead of the enclosing paragraph's own exit at
 * L2427 -- {@code 1270-EDIT-US-STATE-CD} at L2493 and {@code 1280-EDIT-US-STATE-ZIP-CD} at L2536; they
 * belong to {@link AddressValidationService} because they read the allow-lists the program obtains by
 * {@code COPY CSLKPCDY.} at L602, and the reference context owns those tables. Restating either family
 * here would put one rule in two places.</p>
 *
 * <p>Assumptions: representation is NOT decided here. Whether a value arrived at all, whether it fits
 * the column it is stored in, whether an amount converts and whether three parts compose a real day are
 * settled by {@link AccountMapper} and {@link CustomerMapper}, which state that division on their own
 * members. What this class owns is the verdicts the baseline's edit paragraphs reach and the wording
 * they compose. Because the edits run BEFORE either mapper is asked to apply anything, the mappers'
 * own arrival checks are a backstop for a direct caller rather than a second opinion, and the wording a
 * user sees is the baseline's own.</p>
 *
 * <h2>Optimistic concurrency already exists in the baseline</h2>
 *
 * <p>Refactoring Rationale: the program implements a before-image comparison by hand, and this class
 * replaces the apparatus while keeping the behaviour. It copies the entire pre-edit record into
 * {@code 05 ACUP-OLD-DETAILS.} at {@code app/cbl/COACTUPC.cbl} L669, a block running to L756 with
 * {@code 05 ACUP-NEW-DETAILS.} beginning at L757, holding each amount twice -- as a display field and
 * as a numeric redefinition, for instance {@code ACUP-OLD-CURR-BAL PIC X(12)} at L675 redefined
 * {@code PIC S9(10)V99} at L676 and L677. It carries the change state in the conditions at L664 to
 * L668. Then, inside the write task, it takes both locks -- the account read for update at L3894 to
 * L3903 and the customer read for update at L3921 to L3930 -- asks its own question in a comment at
 * L3944 to L3946, performs the comparison at L3947 and L3948, and abandons the write at L3950 when the
 * comparison failed. The two locks being taken THERE, while the before-image was captured in an
 * earlier task, is the proof that the file lock was never held across the submitter's thinking time,
 * and that is exactly why the before-image exists at all. The target keeps that property and drops the
 * apparatus: {@link Account} and {@link Customer} each carry a version member the provider compares at
 * flush, so eleven duplicated fields are unnecessary and the comparison is made against a value a read
 * actually established. The commit boundary follows the same route -- {@code EXEC CICS SYNCPOINT} at
 * L952 to L954 becomes the {@link Transactional} boundary on
 * {@link #update(long, AccountUpdateRequest, String)}.</p>
 *
 * <p>Refactoring Rationale: the baseline's own rollback discipline is ASYMMETRIC and the two arms
 * collapse into one here. The account rewrite's failure path at {@code app/cbl/COACTUPC.cbl} L4076 to
 * L4081 sets the failure state and leaves the paragraph with no rollback, because nothing had been
 * written yet. The customer rewrite at L4085 to L4091 has the same failure path at L4095 to L4103 but
 * WITH {@code EXEC CICS SYNCPOINT ROLLBACK} at L4099 to L4101, the verb itself on L4100, because by
 * then the account had been written. A single transaction subsumes both arms uniformly: an exception
 * propagates and the provider discards the unit of work, so neither arm needs a rollback statement and
 * neither can be forgotten. No manual rollback call appears in this class for that reason. The
 * collapse changes how the discipline is expressed and not what an observer sees, since neither
 * baseline arm leaves a partial write either, so it is recorded among the structural entries of
 * {@code docs/architecture/cobol-to-service-traceability.md} section 7.3 rather than as a behavioural
 * difference.</p>
 *
 * <p>Trade-offs: the baseline's comparison is CASE-INSENSITIVE over part of the record and the version
 * member is not, so the target refuses a narrow class of concurrent change the baseline would have
 * allowed. Over {@code app/cbl/COACTUPC.cbl} L4109 to L4202 there are exactly two occurrences of the
 * lower-casing function, both at L4139 and L4140 and both wrapping {@code ACCT-GROUP-ID} against its
 * before-image, and eighteen occurrences of the upper-casing function forming nine field pairs at L4152
 * to L4173 -- the three name fields, the three address lines, the state code, the country code and the
 * government-issued identifier. A concurrent writer who changed only the letter case of one of those
 * ten fields would not have made the baseline's comparison fail, and does make the version member
 * advance. The target is therefore STRICTER than the baseline on that input and never looser, which is
 * a statement of which way the difference runs rather than a claim that being strict is desirable. The
 * divergence is registered as {@code D-UPDATE-CASE-SENSITIVE-COMPARE} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <h2>Statelessness</h2>
 *
 * <p>Refactoring Rationale: nothing in this class holds anything between requests, and the baseline's
 * session structure is what had to be decomposed to make that possible. {@code app/cpy/COCOM01Y.cpy}
 * declares a 160-byte {@code CARDDEMO-COMMAREA} at L19 in five groups at L20, L32, L37, L40 and L42.
 * Its navigation fields become the browser's own history; its identity fields, {@code CDEMO-USER-ID}
 * at L25 and {@code CDEMO-USER-TYPE} at L26 with its two conditions at L27 and L28, become validated
 * token claims that a client cannot assert for itself; its selection fields become the request path and
 * query; and its re-entry discriminator at L29, with the conditions at L30 and L31, disappears
 * altogether, because a handler that never holds a previous turn has no first-entry-versus-re-entry
 * distinction to make. The transaction definition corroborates that there was never any task-local
 * storage to carry either: {@code app/csd/CARDDEMO.CSD} L308 declares {@code TWASIZE(0)} for
 * {@code CAUP}. The consequence for this class is direct -- the per-field markers it produces travel in
 * the response body and nowhere else, so a control transfer becomes a client-side route change rather
 * than a server-side redirect, and there is no next-program field anywhere in the target.</p>
 *
 * <h2>What this class deliberately does not carry</h2>
 *
 * <p>Alternatives Considered: automatic retry around the write, and a circuit breaker in front of it.
 * The framework offers retry in its own core, enabled by {@code @EnableResilientMethods} on a
 * configuration class and configured by a {@code maxRetries} attribute whose value is one fewer than the
 * total attempts, so no third-party resilience library is needed to have it. Neither is applied here, and
 * the reason is specific to what this class can fail at. Its two failure modes are a refused submission
 * and a stale precondition, and both are answers rather than faults: retrying a refused submission would
 * refuse it again with the same wording, and retrying a stale precondition would be WRONG, because the
 * precondition the caller sent is stale by definition once the comparison has failed and a second attempt
 * with the same token can only fail identically or, worse, succeed against a row that moved again. A
 * breaker is likewise omitted: the only call this class makes off its own thread is to the repository on
 * the one cluster, so a breaker would add an open state that refuses work the database was willing to do,
 * without removing any failure mode. The posture is bounded timeouts on the datasource and the message
 * the baseline already declares.</p>
 *
 * <p>Alternatives Considered: no paging appears here, because the baseline's update transaction browses
 * nothing -- it reads one account and one customer by key. Were a listing ever added to this class it
 * would carry the shared keyset envelope rather than an offset, because an offset skips and repeats rows
 * under concurrent inserts: a row inserted before the cursor shifts every later row's position, so a
 * caller paging forward misses one row and sees another twice. That is a behaviour the baseline's
 * browse-by-key does not have, so adopting it would be a change nobody asked for.</p>
 */
@Service
public class AccountUpdateService {

    /**
     * The declared width of the baseline's field-label argument, which TRUNCATES a longer label.
     *
     * <p>Assumptions: {@code WS-EDIT-VARIABLE-NAME} is {@code PIC X(25)} at
     * {@code app/cbl/COACTUPC.cbl} L53, so a move into it keeps the leading twenty-five characters and
     * discards the rest before the message is composed. Exactly one of the labels the program moves
     * into it is longer than that -- {@link #LABEL_CURRENT_CYCLE_CREDIT_LIMIT}, set at L1515, is
     * twenty-six characters -- so the sentence a user actually sees carries a label whose last
     * character is missing. The truncation is applied rather than tidied away because tidying it would
     * change one user-visible sentence, and the shared date validator applies the same width for the
     * same reason at {@link DateEditValidator#FIELD_LABEL_LENGTH}.</p>
     */
    public static final int LABEL_WIDTH = 25;

    /**
     * The declared width of the account key the edit examines.
     *
     * <p>Assumptions: eleven, from {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy} L5, and
     * named in the sentence the edit composes at {@code app/cbl/COACTUPC.cbl} L1807 and L1808.</p>
     */
    public static final int ACCOUNT_KEY_WIDTH = 11;

    /**
     * The declared width of the credit-score field, and the number of digits its edit examines.
     *
     * <p>Assumptions: three, from {@code ACUP-NEW-CUST-FICO-SCORE-X PIC X(03)} at
     * {@code app/cbl/COACTUPC.cbl} L845, and the length the driver moves at L1548.</p>
     */
    public static final int CREDIT_SCORE_WIDTH = 3;

    /**
     * The lowest credit score the baseline accepts.
     *
     * <p>Assumptions: {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at
     * {@code app/cbl/COACTUPC.cbl} L848 and L849, declared over the numeric redefinition at L846 and
     * L847, and the bound the sentence at L2523 names.</p>
     */
    public static final int CREDIT_SCORE_FLOOR = 300;

    /**
     * The highest credit score the baseline accepts.
     *
     * <p>Assumptions: the upper half of the same range declared at {@code app/cbl/COACTUPC.cbl} L848
     * and L849, and the bound the sentence at L2523 names.</p>
     */
    public static final int CREDIT_SCORE_CEILING = 850;

    /**
     * The sentence the key edit composes when no account was supplied.
     *
     * <p>Assumptions: reproduced verbatim from the condition declared at
     * {@code app/cbl/COACTUPC.cbl} L483 and L484 over {@code WS-RETURN-MSG PIC X(75)} at L479, which
     * the blank branch of the key edit sets at L1792. It is NOT composed from a label and a suffix the
     * way the other sentences are, so no label is prefixed to it.</p>
     */
    public static final String MESSAGE_ACCOUNT_NOT_PROVIDED = "Account number not provided";

    /**
     * The sentence the key edit composes when the account is not eleven non-zero digits.
     *
     * <p>Assumptions: this is the THIRD distinct account-number wording in the baseline and it is the
     * one this edit emits, so it is the one reproduced. It is built rather than declared, by the
     * statement at {@code app/cbl/COACTUPC.cbl} L1806 to L1810 joining the literal at L1807 to the
     * literal at L1808 without a delimiter, so the single space between the two halves is the leading
     * space of L1808. The other two wordings are distinct from it and from each other: the two declared
     * conditions at L493 to L494 and L495 to L496 carry one wording between them, identical to each
     * other, and the account view program emits a different one again at
     * {@code app/cbl/COACTVWC.cbl} L672. That makes three wordings across the two programs, and none is
     * merged with another, because merging them would change what at least one screen says.</p>
     */
    public static final String MESSAGE_ACCOUNT_NOT_ELEVEN_DIGITS =
            "Account Number if supplied must be a 11 digit Non-Zero Number";

    /**
     * The sentence the driver latches when the submission changes nothing.
     *
     * <p>Assumptions: reproduced verbatim from the condition declared at
     * {@code app/cbl/COACTUPC.cbl} L491 and L492, including its terminating period. The comparison
     * paragraph sets it at L1769 on the path that finds no change, and the driver's own short-circuit
     * at L1463 to L1467 then leaves without editing a single field.</p>
     */
    public static final String MESSAGE_NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";

    /**
     * The sentence the write path latches once every edit has passed.
     *
     * <p>Assumptions: reproduced verbatim from the condition declared at
     * {@code app/cbl/COACTUPC.cbl} L527 and L528, including its FOUR dots. The ellipsis is the
     * baseline's own text rather than a typographic choice made here, so it is written out at that
     * length instead of being normalised to three.</p>
     */
    public static final String MESSAGE_UPDATE_ACCEPTED = "Looks Good.... so far";

    /**
     * The suffix every never-supplied verdict appends to its field label.
     *
     * <p>Assumptions: byte-exact from {@code app/cbl/COACTUPC.cbl} L1915, including the LEADING space
     * that separates it from the trimmed label and the trailing period. The same literal appears at
     * L1841, L1869, L1972, L2126 and L2191, so one constant serves all six sites; they are one
     * sentence rather than six similar ones.</p>
     */
    public static final String SUFFIX_MUST_BE_SUPPLIED = " must be supplied.";

    /**
     * The suffix the letters-only verdict appends to its field label.
     *
     * <p>Assumptions: byte-exact from {@code app/cbl/COACTUPC.cbl} L1941, with its leading space and
     * trailing period. The same literal appears at L2047 for the optional variant.</p>
     */
    public static final String SUFFIX_ALPHABETS_ONLY = " can have alphabets only.";

    /**
     * The suffix the letters-or-digits verdict appends to its field label.
     *
     * <p>Assumptions: byte-exact from {@code app/cbl/COACTUPC.cbl} L1999, with its leading space and
     * trailing period. The same literal appears at L2095 for the optional variant.</p>
     */
    public static final String SUFFIX_NUMBERS_OR_ALPHABETS_ONLY =
            " can have numbers or alphabets only.";

    /**
     * The suffix the digits-only verdict appends to its field label.
     *
     * <p>Assumptions: byte-exact from {@code app/cbl/COACTUPC.cbl} L2146, with its leading space and
     * trailing period.</p>
     */
    public static final String SUFFIX_ALL_NUMERIC = " must be all numeric.";

    /**
     * The suffix the non-zero verdict appends to its field label.
     *
     * <p>Assumptions: byte-exact from {@code app/cbl/COACTUPC.cbl} L2163, with its leading space and
     * trailing period.</p>
     */
    public static final String SUFFIX_MUST_NOT_BE_ZERO = " must not be zero.";

    /**
     * The suffix the yes-or-no verdict appends to its field label.
     *
     * <p>Assumptions: byte-exact from {@code app/cbl/COACTUPC.cbl} L1886, with its leading space and
     * trailing period. It is a distinct sentence from the account-status condition declared at L503
     * and L504, which no path in the edit driver reaches, and the two are not merged.</p>
     */
    public static final String SUFFIX_MUST_BE_YES_OR_NO = " must be Y or N.";

    /**
     * The suffix the signed-amount verdict appends to its field label.
     *
     * <p>Assumptions: byte-exact from {@code app/cbl/COACTUPC.cbl} L2209, which carries NO trailing
     * period where the five suffixes above all carry one. The asymmetry is the baseline's and is
     * preserved; adding a period would change the sentence a user reads on every amount field on the
     * screen.</p>
     */
    public static final String SUFFIX_IS_NOT_VALID = " is not valid";

    /**
     * The suffix the credit-score range verdict appends to its field label.
     *
     * <p>Assumptions: byte-exact from {@code app/cbl/COACTUPC.cbl} L2523, which begins with a COLON
     * and a space rather than a space alone and carries no trailing period.</p>
     */
    public static final String SUFFIX_CREDIT_SCORE_RANGE = ": should be between 300 and 850";

    /**
     * The suffix the national-identifier prefix verdict appends to its field label.
     *
     * <p>Assumptions: byte-exact from {@code app/cbl/COACTUPC.cbl} L2457, which begins with a colon
     * and a space and carries no trailing period. The domain it names is the one declared at L121 to
     * L123 over the numeric redefinition at L119 and L120.</p>
     */
    public static final String SUFFIX_NATIONAL_IDENTIFIER_PREFIX =
            ": should not be 000, 666, or between 900 and 999";

    // WHY : Assumptions: the twenty-six labels below are the literals the edit driver moves into
    //       WS-EDIT-VARIABLE-NAME immediately before each edit, and they are user-visible strings in
    //       their own right rather than diagnostic names, because the composed sentence begins with
    //       the trimmed label. They are therefore carried across character for character, at the
    //       physical line each is set on, and none is renamed, re-cased or re-spaced. Two further
    //       facts about the set are recorded where they bite: L1614 sets a label for the second
    //       address line and is COMMENTED OUT, so the following line's 'City' label is what is
    //       applied to the third address line at L1616; and the credit-score, telephone, state and
    //       postal labels are also passed to the collaborators that own those edits, so one
    //       declaration serves both this class and the sentence they compose.

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1472 for the account's active status. */
    public static final String LABEL_ACCOUNT_STATUS = "Account Status";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1478 for the account's open date. */
    public static final String LABEL_OPEN_DATE = "Open Date";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1484 for the credit limit. */
    public static final String LABEL_CREDIT_LIMIT = "Credit Limit";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1490 for the account's expiry date. */
    public static final String LABEL_EXPIRY_DATE = "Expiry Date";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1496 for the cash credit limit. */
    public static final String LABEL_CASH_CREDIT_LIMIT = "Cash Credit Limit";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1503 for the account's reissue date. */
    public static final String LABEL_REISSUE_DATE = "Reissue Date";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1509 for the current balance. */
    public static final String LABEL_CURRENT_BALANCE = "Current Balance";

    /**
     * The label set at {@code app/cbl/COACTUPC.cbl} L1515 for the current cycle's credit limit.
     *
     * <p>Assumptions: this is the ONE label in the set longer than the twenty-five characters
     * {@link #LABEL_WIDTH} declares, at twenty-six, so the sentence composed from it loses its final
     * character. It is declared at full length here and truncated at the point of use, so that a
     * reader can see both the literal the baseline sets and the width that reduces it.</p>
     */
    public static final String LABEL_CURRENT_CYCLE_CREDIT_LIMIT = "Current Cycle Credit Limit";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1522 for the current cycle's debit limit. */
    public static final String LABEL_CURRENT_CYCLE_DEBIT_LIMIT = "Current Cycle Debit Limit";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1529 for the national identifier as a whole. */
    public static final String LABEL_NATIONAL_IDENTIFIER = "SSN";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1533 for the date of birth. */
    public static final String LABEL_DATE_OF_BIRTH = "Date of Birth";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1545 for the credit score. */
    public static final String LABEL_CREDIT_SCORE = "FICO Score";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1560 for the customer's first name. */
    public static final String LABEL_FIRST_NAME = "First Name";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1568 for the customer's middle name. */
    public static final String LABEL_MIDDLE_NAME = "Middle Name";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1576 for the customer's last name. */
    public static final String LABEL_LAST_NAME = "Last Name";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1584 for the first address line. */
    public static final String LABEL_ADDRESS_LINE_1 = "Address Line 1";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1592 for the state code. */
    public static final String LABEL_STATE = "State";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1605 for the postal code. */
    public static final String LABEL_ZIP = "Zip";

    /**
     * The label set at {@code app/cbl/COACTUPC.cbl} L1615 for the city.
     *
     * <p>Assumptions: this label is applied to the THIRD address line, which the driver moves at
     * L1616, and the record has no city field of its own -- {@code app/cpy/CVCUS01Y.cpy} declares
     * {@code CUST-ADDR-LINE-3} at L11 and no {@code CUST-CITY} at all. The line preceding the label in
     * {@code app/cbl/COACTUPC.cbl}, L1614, sets a second-address-line label and is commented out, with
     * L1613 recording that the second line is optional, so this is the live label for that field.</p>
     */
    public static final String LABEL_CITY = "City";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1623 for the country code. */
    public static final String LABEL_COUNTRY = "Country";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1632 for the first telephone number. */
    public static final String LABEL_PHONE_NUMBER_1 = "Phone Number 1";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1640 for the second telephone number. */
    public static final String LABEL_PHONE_NUMBER_2 = "Phone Number 2";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1648 for the funds-transfer account. */
    public static final String LABEL_EFT_ACCOUNT_ID = "EFT Account Id";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L1657 for the primary card holder marker. */
    public static final String LABEL_PRIMARY_CARD_HOLDER = "Primary Card Holder";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L2439 for the identifier's first three digits. */
    public static final String LABEL_NATIONAL_IDENTIFIER_PART_1 = "SSN: First 3 chars";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L2469 for the identifier's fourth and fifth. */
    public static final String LABEL_NATIONAL_IDENTIFIER_PART_2 = "SSN 4th & 5th chars";

    /** The label set at {@code app/cbl/COACTUPC.cbl} L2481 for the identifier's last four digits. */
    public static final String LABEL_NATIONAL_IDENTIFIER_PART_3 = "SSN Last 4 chars";

    // WHY : Assumptions: the identities below name the REQUEST PROPERTY a value arrived in rather than
    //       the stored column it lands in, which is the same vocabulary AccountMapper and CustomerMapper
    //       report under. The identity is not a diagnostic label: transformation rule T7 makes the
    //       per-field array the way a refusal reaches a form control, and a form binds its controls to
    //       the property names it submitted, so a column name would be unbindable. Only the five reused
    //       across more than one method are declared; the rest are written at their single recording
    //       site, following the same choice AccountMapper makes in its own field report.

    /** The identity of the submitted state code, reported by two edits and by the pairing edit. */
    public static final String FIELD_STATE_CODE = "stateCode";

    /** The identity of the submitted postal code, reported by its own edit and by the pairing edit. */
    public static final String FIELD_ZIP_CODE = "zipCode";

    /**
     * The identity STEM of the first telephone number, from which three part identities are derived.
     *
     * <p>Assumptions: one stem is passed rather than three identities because the collaborator derives
     * the parts itself, using {@link AddressValidationService#FIELD_PART_SEPARATOR}, and it does so to
     * reproduce the baseline's own grouping -- {@code app/cbl/COACTUPC.cbl} L1637 and L1638 move a
     * single three-marker group to one per-telephone destination. The derived identities are therefore
     * dotted paths beneath this stem rather than the flat request properties, which is the price of
     * having all three parts edited at all.</p>
     */
    public static final String FIELD_PHONE_1 = "phone1";

    /**
     * The identity STEM of the second telephone number, from which three part identities are derived.
     *
     * <p>Assumptions: the counterpart of {@link #FIELD_PHONE_1}, reproducing the second group move at
     * {@code app/cbl/COACTUPC.cbl} L1645 and L1646.</p>
     */
    public static final String FIELD_PHONE_2 = "phone2";

    /** The identity of the submitted credit score, reported by two edits in sequence. */
    public static final String FIELD_CREDIT_SCORE = "ficoCreditScore";

    // WHY : Assumptions: the widths below are the LENGTH argument the edit driver moves immediately
    //       before each generic edit, not the width of the column behind the field, and the two differ
    //       in one place that matters. The postal code is examined at five characters at
    //       app/cbl/COACTUPC.cbl L1607 while the column is ten wide at app/cpy/CVCUS01Y.cpy L14,
    //       because the screen field the value arrives in is five. Taking the column width instead
    //       would put five pad characters inside the digits-only test and refuse every postal code.

    /** The length the driver examines the three name fields at, moved at L1562, L1570 and L1578. */
    public static final int NAME_EDIT_WIDTH = 25;

    /** The length the driver examines an address line at, moved at L1586 and L1617. */
    public static final int ADDRESS_LINE_EDIT_WIDTH = 50;

    /** The length the driver examines the state code at, moved at L1594. */
    public static final int STATE_CODE_EDIT_WIDTH = 2;

    /** The length the driver examines the postal code at, moved at L1607. */
    public static final int POSTAL_CODE_EDIT_WIDTH = 5;

    /** The length the driver examines the country code at, moved at L1626. */
    public static final int COUNTRY_CODE_EDIT_WIDTH = 3;

    /** The length the driver examines the funds-transfer account at, moved at L1651. */
    public static final int EFT_ACCOUNT_ID_EDIT_WIDTH = 10;

    /** The length the identifier edit examines its first part at, moved at L2441. */
    public static final int NATIONAL_IDENTIFIER_PART_1_WIDTH = 3;

    /** The length the identifier edit examines its second part at, moved at L2471. */
    public static final int NATIONAL_IDENTIFIER_PART_2_WIDTH = 2;

    /** The length the identifier edit examines its third part at, moved at L2483. */
    public static final int NATIONAL_IDENTIFIER_PART_3_WIDTH = 4;

    /**
     * The lowest value the identifier's first part may not be, and the value it may not equal.
     *
     * <p>Assumptions: {@code 88 INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999} at
     * {@code app/cbl/COACTUPC.cbl} L121 to L123, declared over the numeric redefinition at L119 and
     * L120. Three separate bounds are named rather than one predicate so that each can be read against
     * the declaration.</p>
     */
    public static final int NATIONAL_IDENTIFIER_PREFIX_ZERO = 0;

    /** The single value in the middle of the refused identifier prefixes, from L122. */
    public static final int NATIONAL_IDENTIFIER_PREFIX_SIX_SIX_SIX = 666;

    /** The lowest of the refused identifier prefix range, from L123. */
    public static final int NATIONAL_IDENTIFIER_PREFIX_RANGE_FLOOR = 900;

    /** The highest of the refused identifier prefix range, from L123. */
    public static final int NATIONAL_IDENTIFIER_PREFIX_RANGE_CEILING = 999;

    /**
     * The width of the year part in the unseparated date form the date edit is given.
     *
     * <p>Assumptions: the before-image dates are {@code PIC X(08)} redefined into a four-character year
     * and two two-character parts, at {@code app/cbl/COACTUPC.cbl} L684 to L689 for the open date, L690
     * to L695 for the expiry date and L696 to L700 for the reissue date. Four plus two plus two is the
     * eight-character unseparated form the shared date validator accepts, which is why the three
     * submitted parts are assembled at these widths rather than at the ten-character separated width the
     * master record holds.</p>
     */
    public static final int DATE_YEAR_WIDTH = 4;

    /** The width of the month part in the unseparated date form, from the redefinitions above. */
    public static final int DATE_MONTH_WIDTH = 2;

    /** The width of the day part in the unseparated date form, from the redefinitions above. */
    public static final int DATE_DAY_WIDTH = 2;

    /** Loads and flushes the account master row. */
    private final AccountRepository accounts;

    /** Loads and flushes the customer master row. */
    private final CustomerRepository customers;

    /** Resolves the account's customer through the by-account cross-reference path. */
    private final CardXrefRepository crossReferences;

    // WHY : Refactoring Rationale: the account mapper is held rather than the narrower context mapper
    //       that projects rows onto the read contract. Two things this path needs live only on this
    //       one: applyUpdate, which assigns the account region of a submission onto the loaded row, and
    //       toAccountUpdateResponse, which assembles the update response including its
    //       seventy-five-character aggregate channel. Without the first, the account half of every
    //       submission -- the status, the five amounts, the three dates and the group -- was accepted
    //       and then silently discarded, so a user could change a credit limit and be told the update
    //       had succeeded while the column kept its old value.

    /** Applies the account region of a submission and assembles the published response. */
    private final AccountMapper accountMapper;

    /** Applies the customer region of a submission and projects the committed customer state. */
    private final CustomerMapper customerMapper;

    /** The value-domain edits that read the reference context's three address allow-lists. */
    private final AddressValidationService addressValidation;

    // WHY : Assumptions: the current date is taken from an injected time source rather than read from
    //       the wall clock, because one edit needs it -- the date-of-birth range at
    //       app/cbl/COACTUPC.cbl L1540 and L1541 rejects a date that is not in the past -- and the
    //       shared kernel states the rule for the whole migration on its own timestamp helper: a value
    //       reaches a decision through a supplied time source, never straight from a clock. The bean
    //       exists already, declared with a missing-bean condition in the shared auto-configuration, so
    //       injecting it also keeps this edit's boundary pinnable from a test to a single instant.

    /** The time source the date-of-birth range edit compares against. */
    private final Clock clock;

    /**
     * Creates the update service over the rows it writes, the projections it publishes and its edits.
     *
     * @param accounts the account master repository; must not be {@code null}
     * @param customers the customer master repository; must not be {@code null}
     * @param crossReferences the card cross-reference repository, used for the by-account path; must
     *     not be {@code null}
     * @param accountMapper the account region's write direction and the response assembly; must not be
     *     {@code null}
     * @param customerMapper the customer region's write direction and projection; must not be
     *     {@code null}
     * @param addressValidation the telephone, state and state-with-postal-prefix edits; must not be
     *     {@code null}
     * @param clock the time source the date-of-birth range edit compares against; must not be
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public AccountUpdateService(AccountRepository accounts, CustomerRepository customers,
            CardXrefRepository crossReferences, AccountMapper accountMapper,
            CustomerMapper customerMapper, AddressValidationService addressValidation, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.crossReferences =
                Objects.requireNonNull(crossReferences, "crossReferences must not be null");
        this.accountMapper = Objects.requireNonNull(accountMapper, "accountMapper must not be null");
        this.customerMapper = Objects.requireNonNull(customerMapper, "customerMapper must not be null");
        this.addressValidation =
                Objects.requireNonNull(addressValidation, "addressValidation must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Reads the revision token a caller must return on its next update of this account.
     *
     * <p>Assumptions: the token is DERIVED from the two rows on every read and is never stored, so it
     * cannot drift from the value the provider will compare at flush. Persisting one would create a
     * second source of truth for the same fact, and the two could then disagree about whether a row had
     * moved.</p>
     *
     * @param accountId the account whose revision is required
     * @return the opaque revision token covering the account and its customer, never {@code null}
     * @throws NoSuchElementException if the account has no row, has no cross-reference row, or names a
     *     customer the customer master does not hold
     */
    @Transactional(readOnly = true)
    public String currentRevision(long accountId) {
        Account account = loadAccount(accountId);
        Customer customer = loadCustomer(accountId);
        return revisionOf(account, customer);
    }

    /**
     * Edits a submitted change, applies it to both rows and commits, or refuses it.
     *
     * <p>Purpose: this is the migrated form of {@code 9600-WRITE-PROCESSING} at
     * {@code app/cbl/COACTUPC.cbl} L3888, whose exit is L4105, together with the commit at L952 to
     * L954 that the program reaches once the write has succeeded. The order of work reproduces the
     * baseline's own: every edit runs first, the concurrency question is asked next, and only then are
     * the two records written.</p>
     *
     * <p>Refactoring Rationale: the edits run BEFORE the precondition is tested because the baseline
     * reaches its comparison only after they have all passed. In the baseline the edits belong to an
     * earlier task -- {@code 1000-PROCESS-INPUTS} performs the edit driver at L1028 -- and the write
     * task is entered only when the action paragraph permits it, which it does not while the input-error
     * switch is set. A submission that carries both an unacceptable field and a stale precondition is
     * therefore answered with the field error, which is what the screen would have shown. Testing the
     * precondition first was the alternative and would have reported a conflict for a submission the
     * baseline would never have carried as far as the comparison.</p>
     *
     * <p>Refactoring Rationale: the whole method is ONE transaction and no rollback is ever called by
     * hand. {@code EXEC CICS SYNCPOINT} at {@code app/cbl/COACTUPC.cbl} L952 to L954 is the commit, and
     * the baseline's two rewrite failure paths handle rollback asymmetrically -- the account arm at
     * L4076 to L4081 has none because nothing had been written, the customer arm at L4095 to L4103 has
     * one at L4099 to L4101 with the verb on L4100 because the account had. A single boundary subsumes
     * both: an exception propagates, the provider discards the unit of work, and there is no rollback
     * statement to place correctly or to forget.</p>
     *
     * <p>Assumptions: both rows are written inside that one boundary because the baseline rewrites both
     * inside one unit of work and commits once. Splitting them would make a state observable that the
     * baseline cannot produce -- an updated account beside an unchanged customer -- and the baseline's
     * single rollback covers both records.</p>
     *
     * @param accountId the account to update
     * @param request the submitted change, an {@link AccountUpdateRequest}; must not be {@code null}
     * @param expectedRevision the revision token the caller was given, from a precondition header;
     *     must not be {@code null}
     * @return the committed state with its aggregate message, an {@link AccountUpdateResponse}, never
     *     {@code null}
     * @throws NullPointerException if {@code request} or {@code expectedRevision} is {@code null}
     * @throws NoSuchElementException if the account or its customer is absent
     * @throws ClientInputException if any edit refused a submitted value, carrying one entry per
     *     offending request property and the first refusal's wording; the shared advice renders it as
     *     HTTP 400
     * @throws IllegalArgumentException as the parent of the above, since {@link ClientInputException}
     *     extends it and a caller may catch either
     * @throws RecordConflictException if the precondition is blank or names a state the rows have left,
     *     which the shared advice renders as HTTP 409 carrying the baseline's changed-record sentence
     * @throws org.springframework.dao.OptimisticLockingFailureException if either row moves between
     *     this transaction's read and its flush, which the shared advice renders as the same conflict
     */
    @Transactional
    public AccountUpdateResponse update(long accountId, AccountUpdateRequest request,
            String expectedRevision) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(expectedRevision, "expectedRevision must not be null");

        Account account = loadAccount(accountId);
        Customer customer = loadCustomer(accountId);

        EditVerdict verdict = editMapInputs(request, account, customer);
        refuseWhenAnyEditFailed(verdict);
        requireCurrentRevision(account, customer, expectedRevision);

        this.accountMapper.applyUpdate(account, request);
        this.customerMapper.applyUpdate(customer, request);

        // WHY : Assumptions: the flush is FORCED here rather than left to the transaction's end, so an
        //       optimistic failure is raised while this method is still on the stack and the response
        //       below is never assembled from rows that failed to persist. Left to commit time it would
        //       surface from the transaction interceptor after a response object had already been built.
        //       Alternatives Considered: catching it here and raising a conflict of our own. Rejected
        //       because the shared advice ALREADY renders an optimistic-lock failure as HTTP 409 with
        //       the baseline's verbatim changed-record sentence, so translating it here would put one
        //       behaviour in two places and the two could disagree about the wording.
        this.customers.saveAndFlush(customer);
        this.accounts.saveAndFlush(account);

        // WHY : Assumptions: the aggregate channel carries the baseline's no-change sentence when the
        //       comparison found nothing to change and its accepted sentence otherwise, because those
        //       are the two texts the baseline latches on these two paths -- app/cbl/COACTUPC.cbl L1769
        //       reaching the condition at L491 and L492, and the condition at L527 and L528. Emitting
        //       the accepted sentence on both paths would tell a user that a submission which changed
        //       nothing had changed something.
        return this.accountMapper.toAccountUpdateResponse(account,
                this.customerMapper.toCustomerDetail(customer),
                verdict.noChangesFound() ? MESSAGE_NO_CHANGES_DETECTED : MESSAGE_UPDATE_ACCEPTED,
                List.of());
    }

    /**
     * Runs every edit the submission calls for, in the baseline's order, and reports one verdict.
     *
     * <p>Purpose: this is the migrated form of {@code 1200-EDIT-MAP-INPUTS} at
     * {@code app/cbl/COACTUPC.cbl} L1429, whose exit is L1678. It reproduces both of that paragraph's
     * gates and then the twenty-four edits it performs, at the physical line each is set up on.</p>
     *
     * <p>Assumptions: the FIRST gate is the fetched-details test at L1433, whose condition is declared
     * at L656 to L658 over a slot that is empty until a record has been read. On that path the paragraph
     * performs the key edit alone at L1435 and L1436, clears the before-image at L1438, marks an absent
     * key at L1441 to L1443 and leaves at L1446 -- so no other field is edited at all. Here the same
     * gate is an absent loaded pair: a caller that has not yet read a record passes {@code null} for
     * both rows and receives the key verdict on its own. That is why the two rows are parameters rather
     * than being loaded inside this method.</p>
     *
     * <p>Assumptions: the SECOND gate is the no-change short-circuit at L1463 to L1467, which clears
     * every non-key marker at L1466 and leaves at L1467, so a submission that changes nothing is not
     * edited either. It is reached from the comparison the paragraph performs at L1460 and L1461. The
     * gate is reproduced exactly in that respect -- no field is edited -- and it is deliberately NOT
     * extended to skip the write, for the reason recorded on
     * {@link #compareOldNew(AccountUpdateRequest, Account, Customer)}.</p>
     *
     * <p>Trade-offs: every edit runs and every failing field is reported, whereas the baseline has one
     * seventy-five-character message field, declared at L479 with its cleared condition at L480, and
     * each edit writes to it only while it is still clear -- so a user sees the FIRST complaint and no
     * others. The array here accumulates and the aggregate message keeps the first-wins rule, so the
     * sentence is the baseline's and the array is larger than a fixed-width line could render. What is
     * bought is that a caller correcting two fields need not submit twice to discover the second; what
     * is given up is that a caller reading only the sentence learns about one field where the array
     * names several. The same compromise is taken, on the same ground, by the card context's list
     * narrowing.</p>
     *
     * @param request the submitted change, an {@link AccountUpdateRequest}; must not be {@code null}
     * @param fetchedAccount the account row already read, or {@code null} when no record has been
     *     fetched, which selects the key-only path
     * @param fetchedCustomer the customer row already read, or {@code null} with the same effect
     * @return the verdict: one entry per offending request property in encounter order, the first
     *     refusal's wording, whether any edit failed and whether the comparison found no change; never
     *     {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public EditVerdict editMapInputs(AccountUpdateRequest request, Account fetchedAccount,
            Customer fetchedCustomer) {
        Objects.requireNonNull(request, "request must not be null");

        EditLatch latch = new EditLatch();

        // WHY : Assumptions: the key-only path is selected by an absent row rather than by a flag the
        //       caller sets, because the baseline's own discriminator is an EMPTY SLOT -- the condition
        //       at app/cbl/COACTUPC.cbl L656 to L658 tests the details field for low values or spaces.
        //       An absent row is the target's spelling of the same fact, so no extra argument is needed
        //       and no caller can claim details were fetched when they were not.
        if (fetchedAccount == null || fetchedCustomer == null) {
            latch.record("accountId", editAccountKey(request.accountId()));
            return latch.toVerdict(false);
        }

        if (compareOldNew(request, fetchedAccount, fetchedCustomer)) {
            latch.latchMessage(MESSAGE_NO_CHANGES_DETECTED);
            return latch.toVerdict(true);
        }

        latch.record("activeStatus", editYesNo(LABEL_ACCOUNT_STATUS, request.activeStatus()));
        recordDateEdit(latch, "openDate", LABEL_OPEN_DATE, request.openDateYear(),
                request.openDateMonth(), request.openDateDay(), false);
        latch.record("creditLimit", editSignedNineV2(LABEL_CREDIT_LIMIT, request.creditLimit()));
        recordDateEdit(latch, "expirationDate", LABEL_EXPIRY_DATE, request.expirationDateYear(),
                request.expirationDateMonth(), request.expirationDateDay(), false);
        latch.record("cashCreditLimit",
                editSignedNineV2(LABEL_CASH_CREDIT_LIMIT, request.cashCreditLimit()));
        recordDateEdit(latch, "reissueDate", LABEL_REISSUE_DATE, request.reissueDateYear(),
                request.reissueDateMonth(), request.reissueDateDay(), false);
        latch.record("currentBalance",
                editSignedNineV2(LABEL_CURRENT_BALANCE, request.currentBalance()));
        latch.record("currentCycleCredit",
                editSignedNineV2(LABEL_CURRENT_CYCLE_CREDIT_LIMIT, request.currentCycleCredit()));
        latch.record("currentCycleDebit",
                editSignedNineV2(LABEL_CURRENT_CYCLE_DEBIT_LIMIT, request.currentCycleDebit()));

        NationalIdentifierVerdict identifier = editNationalIdentifier(
                request.ssnPart1(), request.ssnPart2(), request.ssnPart3());
        latch.record("ssnPart1", identifier.firstPart());
        latch.record("ssnPart2", identifier.middlePart());
        latch.record("ssnPart3", identifier.lastPart());

        recordDateEdit(latch, "dateOfBirth", LABEL_DATE_OF_BIRTH, request.dateOfBirthYear(),
                request.dateOfBirthMonth(), request.dateOfBirthDay(), true);

        // WHY : Assumptions: the range edit is GUARDED on the digits-only edit having passed, which is
        //       the test at app/cbl/COACTUPC.cbl L1553. Without the guard a non-numeric credit score
        //       would be reported twice -- once as not numeric and once as outside the range -- and the
        //       range sentence would name bounds for a value that is not a number at all.
        EditOutcome creditScoreShape =
                editNumericRequired(LABEL_CREDIT_SCORE, request.ficoCreditScore(),
                        CREDIT_SCORE_WIDTH);
        latch.record(FIELD_CREDIT_SCORE, creditScoreShape);
        if (creditScoreShape.state().isValid()) {
            latch.record(FIELD_CREDIT_SCORE,
                    editCreditScore(LABEL_CREDIT_SCORE, request.ficoCreditScore()));
        }

        latch.record("firstName",
                editAlphaRequired(LABEL_FIRST_NAME, request.firstName(), NAME_EDIT_WIDTH));
        latch.record("middleName",
                editAlphaOptional(LABEL_MIDDLE_NAME, request.middleName(), NAME_EDIT_WIDTH));
        latch.record("lastName",
                editAlphaRequired(LABEL_LAST_NAME, request.lastName(), NAME_EDIT_WIDTH));
        latch.record("addressLine1",
                editMandatory(LABEL_ADDRESS_LINE_1, request.addressLine1(),
                        ADDRESS_LINE_EDIT_WIDTH));

        // WHY : Assumptions: the allow-list edit is GUARDED on the letters-only edit having passed,
        //       which is the test at app/cbl/COACTUPC.cbl L1599. The collaborator's state edit is total
        //       -- it pads a blank to width and looks it up -- so an omitted state would otherwise be
        //       reported as a state outside the allow-list, telling a caller to correct a value it
        //       never sent.
        EditOutcome stateShape =
                editAlphaRequired(LABEL_STATE, request.stateCode(), STATE_CODE_EDIT_WIDTH);
        latch.record(FIELD_STATE_CODE, stateShape);
        boolean stateAcceptable = stateShape.state().isValid();
        if (stateAcceptable) {
            AddressValidationService.AddressValidationResult allowList =
                    this.addressValidation.validateStateCode(
                            request.stateCode(), FIELD_STATE_CODE, LABEL_STATE);
            latch.merge(allowList);
            stateAcceptable = allowList.isValid();
        }

        EditOutcome postalShape =
                editNumericRequired(LABEL_ZIP, request.zipCode(), POSTAL_CODE_EDIT_WIDTH);
        latch.record(FIELD_ZIP_CODE, postalShape);

        latch.record("city",
                editAlphaRequired(LABEL_CITY, request.city(), ADDRESS_LINE_EDIT_WIDTH));
        latch.record("countryCode",
                editAlphaRequired(LABEL_COUNTRY, request.countryCode(), COUNTRY_CODE_EDIT_WIDTH));

        latch.merge(this.addressValidation.validateUsPhoneNumber(
                assembledTelephoneNumber(request.phone1AreaCode(), request.phone1Prefix(),
                        request.phone1LineNumber()),
                FIELD_PHONE_1, LABEL_PHONE_NUMBER_1));
        latch.merge(this.addressValidation.validateUsPhoneNumber(
                assembledTelephoneNumber(request.phone2AreaCode(), request.phone2Prefix(),
                        request.phone2LineNumber()),
                FIELD_PHONE_2, LABEL_PHONE_NUMBER_2));

        latch.record("eftAccountId",
                editNumericRequired(LABEL_EFT_ACCOUNT_ID, request.eftAccountId(),
                        EFT_ACCOUNT_ID_EDIT_WIDTH));
        latch.record("primaryCardHolderIndicator",
                editYesNo(LABEL_PRIMARY_CARD_HOLDER, request.primaryCardHolderIndicator()));

        // WHY : Assumptions: the cross-field pairing runs LAST and only when both halves are already
        //       acceptable, which is the guard at app/cbl/COACTUPC.cbl L1665 and L1666 immediately
        //       before the edit at L1667. Pairing a state that is already known to be wrong with a
        //       postal code would report a second failure about a combination whose first half the
        //       caller has already been told about, and the guard is why the state's own allow-list
        //       result is kept in a local above rather than discarded.
        if (stateAcceptable && postalShape.state().isValid()) {
            latch.merge(this.addressValidation.validateStateZipCombination(
                    request.stateCode(), request.zipCode(), FIELD_STATE_CODE, FIELD_ZIP_CODE));
        }

        return latch.toVerdict(false);
    }

    /**
     * Edits the account key, as {@code 1210-EDIT-ACCOUNT} does at L1783 to L1820.
     *
     * <p>Three verdicts in the paragraph's order. It initialises to not-acceptable at
     * {@code app/cbl/COACTUPC.cbl} L1784. It tests for an absent value at L1787 and L1788, marks it
     * absent at L1790 and latches the declared sentence at L1792. Otherwise it tests at L1802 and L1803
     * that the value is numeric and is not zero, latching the built sentence at L1806 to L1810 when
     * either fails, and marks the key acceptable at L1816.</p>
     *
     * <p>Assumptions: a value WIDER than the eleven characters the field declares is refused here rather
     * than truncated to width, and this is the one edit where that decision is taken locally. The
     * baseline cannot receive one -- its screen field is eleven characters -- and nothing downstream
     * refuses one either, because the account mapper deliberately does not assign the key onto the row
     * it loaded. Since the sentence the paragraph composes names eleven digits, refusing the value is
     * the reading that keeps the sentence true.</p>
     *
     * <p>Assumptions: the submitted key is edited but is NOT compared against the key the caller
     * addressed. The baseline has one key and reads the record by it; the target addresses the row by
     * path and the mapper does not assign the submitted value, so a disagreement between the two cannot
     * relocate a row. Adding a comparison would introduce a refusal the baseline has no counterpart
     * for.</p>
     *
     * <p>Assumptions: this edit belongs to the FIRST of the program's two marker encodings, and the two
     * are not interchangeable. Its own marker is declared at {@code app/cbl/COACTUPC.cbl} L183 with the
     * acceptable byte a digit one at L184, the not-acceptable byte a digit zero at L185 and the absent
     * byte a SPACE at L186; the customer key filter beside it repeats that spelling exactly at L187 to
     * L190. Every non-key screen field instead sits in the group at L191 running to L352, where the
     * absent byte is the letter B and the acceptable byte varies per field -- the two letters at L193 and
     * L350, the low-value byte at L197 and L346. Returning a state rather than a byte is what lets both
     * encodings survive in one type: the shared marker admits the digit one and the space as alternate
     * spellings of acceptable and absent, so neither regime has to be rewritten into the other and
     * neither is silently flattened.</p>
     *
     * @param accountId the submitted account key as it arrived, which may be padded, short, over-wide,
     *     blank or {@code null}
     * @return the verdict, carrying no wording when the key is acceptable, an {@link EditOutcome} that
     *     is never {@code null}
     */
    public EditOutcome editAccountKey(String accountId) {
        if (FieldValidationFlag.isNeverSupplied(accountId)) {
            return EditOutcome.blank(MESSAGE_ACCOUNT_NOT_PROVIDED);
        }

        String candidate = accountId.trim();
        if (candidate.length() > ACCOUNT_KEY_WIDTH || !isAllDigits(candidate)
                || isAllZeroDigits(candidate)) {
            return EditOutcome.notAcceptable(MESSAGE_ACCOUNT_NOT_ELEVEN_DIGITS);
        }

        return EditOutcome.acceptable();
    }

    /**
     * Edits a field that must merely be present, as {@code 1215-EDIT-MANDATORY} does at L1824 to L1852.
     *
     * <p>The paragraph initialises to not-acceptable at {@code app/cbl/COACTUPC.cbl} L1826, applies the
     * three-armed absence test at L1829 to L1834, marks the field absent at L1837 and composes the
     * sentence at L1839 to L1844, and otherwise marks it acceptable at L1850. It examines no character
     * class at all, which is why it is the edit the first address line uses.</p>
     *
     * <p>Assumptions: the baseline's three arms collapse into the shared absence test, and the collapse
     * loses nothing. Its arms are an all-low-value field, an all-space field and a field that trims to
     * nothing, and for a fixed-width field the third is the second. The shared test additionally folds a
     * {@code null} and an empty value into the same answer, which a screen field can never present and a
     * request property can.</p>
     *
     * @param label the field label the sentence is prefixed with, one of this class's label constants;
     *     must not be {@code null}
     * @param value the submitted value as it arrived, which may be padded, short, blank or {@code null}
     * @param length the number of characters to examine, the length the driver moves before the edit
     * @return the verdict, an {@link EditOutcome} that is never {@code null}
     * @throws NullPointerException if {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code length} is not positive, since a non-positive width
     *     would examine no characters and report every value as acceptable
     */
    public EditOutcome editMandatory(String label, String value, int length) {
        String examined = examinedValue(label, value, length);
        if (FieldValidationFlag.isNeverSupplied(examined)) {
            return EditOutcome.blank(composed(label, SUFFIX_MUST_BE_SUPPLIED));
        }
        return EditOutcome.acceptable();
    }

    /**
     * Edits a two-state marker, as {@code 1220-EDIT-YESNO} does at L1856 to L1894.
     *
     * <p>The paragraph tests for an absent value at {@code app/cbl/COACTUPC.cbl} L1861 to L1863, marks
     * it absent at L1865 and composes the shared absence sentence at L1867 to L1872. It then tests the
     * value against its admitted set at L1878, marking it not-acceptable at L1882 and composing the
     * sentence at L1884 to L1889.</p>
     *
     * <p>Assumptions: the absence test has a THIRD arm the other edits do not have -- L1863 tests the
     * field against zeros, which for a one-character alphanumeric field means the digit zero -- so a
     * submitted {@code 0} counts as never supplied rather than as an unacceptable value. It is
     * reproduced because the two answers differ in both the marker and the sentence.</p>
     *
     * <p>Assumptions: the marker this edit yields is the SEMANTIC state and not the byte the baseline
     * moves, and the difference matters for this edit alone. The baseline's marker field for a two-state
     * value holds the VALUE itself -- the driver moves it at L1476 and again at L1662 -- which is why the
     * acceptable condition for those two fields lists the two letters at L193 and L350 while every other
     * field's lists the low-value byte at L197 and L346. That is also why the paragraph's own
     * initialisation at L1858 is commented out: setting a not-acceptable byte would have destroyed the
     * value the field was carrying. Returning a state rather than a byte removes the overloading, and the
     * shared marker type admits both spellings explicitly so neither regime is lost.</p>
     *
     * @param label the field label the sentence is prefixed with; must not be {@code null}
     * @param value the submitted marker as it arrived, which may be blank or {@code null}
     * @return the verdict, an {@link EditOutcome} that is never {@code null}
     * @throws NullPointerException if {@code label} is {@code null}
     */
    public EditOutcome editYesNo(String label, String value) {
        Objects.requireNonNull(label, "label must not be null");

        String examined = atWidth(value, 1);
        if (FieldValidationFlag.isNeverSupplied(examined) || "0".equals(examined)) {
            return EditOutcome.blank(composed(label, SUFFIX_MUST_BE_SUPPLIED));
        }

        if (!"Y".equals(examined) && !"N".equals(examined)) {
            return EditOutcome.notAcceptable(composed(label, SUFFIX_MUST_BE_YES_OR_NO));
        }

        return EditOutcome.acceptable();
    }

    /**
     * Edits a required letters-only field, as {@code 1225-EDIT-ALPHA-REQD} does at L1898 to L1951.
     *
     * <p>The paragraph initialises to not-acceptable at {@code app/cbl/COACTUPC.cbl} L1900, applies the
     * three-armed absence test at L1903 to L1908 and composes the shared absence sentence at L1913 to
     * L1918. It then converts every acceptable character to a space at L1925 to L1928 and tests whether
     * anything is left at L1930 to L1933, composing its own sentence at L1939 to L1944 when something
     * is, and marks the field acceptable at L1949.</p>
     *
     * <p>Alternatives Considered: the character-class test is written as a direct membership test rather
     * than as the baseline's strip-and-measure. The baseline strips because it has no membership operator
     * -- it converts each of the fifty-two letters declared at L588 to L591 into a space and then asks
     * whether the trimmed remainder has any length -- so the space is acceptable by construction rather
     * than by being listed, and a residue of one character means one unacceptable character. A direct
     * test states the same set explicitly, which is why the space is named explicitly here; reproducing
     * the strip would have needed a mutable copy of the value and a second pass to measure it, and would
     * have hidden the fact that the space is admitted.</p>
     *
     * @param label the field label the sentence is prefixed with; must not be {@code null}
     * @param value the submitted value as it arrived, which may be padded, short, blank or {@code null}
     * @param length the number of characters to examine, the length the driver moves before the edit
     * @return the verdict, an {@link EditOutcome} that is never {@code null}
     * @throws NullPointerException if {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code length} is not positive
     */
    public EditOutcome editAlphaRequired(String label, String value, int length) {
        String examined = examinedValue(label, value, length);
        if (FieldValidationFlag.isNeverSupplied(examined)) {
            return EditOutcome.blank(composed(label, SUFFIX_MUST_BE_SUPPLIED));
        }
        if (!isLettersAndSpacesOnly(examined)) {
            return EditOutcome.notAcceptable(composed(label, SUFFIX_ALPHABETS_ONLY));
        }
        return EditOutcome.acceptable();
    }

    /**
     * Edits a required letters-or-digits field, as {@code 1230-EDIT-ALPHANUM-REQD} does at L1955 to
     * L2009.
     *
     * <p>The paragraph is the letters-only edit with a wider admitted set: the absence test at
     * {@code app/cbl/COACTUPC.cbl} L1960 to L1965, the conversion at L1982 to L1986 over the
     * sixty-two characters declared at L586 to L593, the residue test at L1988 to L1991 and its own
     * sentence at L1997 to L2002.</p>
     *
     * <p>Assumptions: this edit is DEFINED in the baseline and performed nowhere in it -- no statement
     * anywhere in the program performs it, while the eight other generic edits are performed
     * twenty-seven times between them. It is migrated all the same, because the transcription is of the
     * paragraph set and a reader comparing the two would otherwise find one paragraph missing with no
     * record of why. It is exposed rather than hidden for the same reason a caller can use it: the set it
     * admits is the one the baseline declares.</p>
     *
     * @param label the field label the sentence is prefixed with; must not be {@code null}
     * @param value the submitted value as it arrived, which may be padded, short, blank or {@code null}
     * @param length the number of characters to examine
     * @return the verdict, an {@link EditOutcome} that is never {@code null}
     * @throws NullPointerException if {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code length} is not positive
     */
    public EditOutcome editAlphanumericRequired(String label, String value, int length) {
        String examined = examinedValue(label, value, length);
        if (FieldValidationFlag.isNeverSupplied(examined)) {
            return EditOutcome.blank(composed(label, SUFFIX_MUST_BE_SUPPLIED));
        }
        if (!isLettersDigitsAndSpacesOnly(examined)) {
            return EditOutcome.notAcceptable(composed(label, SUFFIX_NUMBERS_OR_ALPHABETS_ONLY));
        }
        return EditOutcome.acceptable();
    }

    /**
     * Edits an optional letters-only field, as {@code 1235-EDIT-ALPHA-OPT} does at L2012 to L2057.
     *
     * <p>The paragraph differs from its required counterpart in one branch and one branch only: where
     * the required edit reports an absent value, this one marks the field ACCEPTABLE at
     * {@code app/cbl/COACTUPC.cbl} L2024 and leaves at L2025. Everything after that is identical -- the
     * conversion at L2031 to L2034, the residue test at L2036 to L2039 and the same sentence at L2044 to
     * L2050. It is the edit the middle name uses.</p>
     *
     * @param label the field label the sentence is prefixed with; must not be {@code null}
     * @param value the submitted value as it arrived, which may be padded, short, blank or {@code null}
     * @param length the number of characters to examine
     * @return the verdict, which is acceptable and carries no wording when the field is absent, an
     *     {@link EditOutcome} that is never {@code null}
     * @throws NullPointerException if {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code length} is not positive
     */
    public EditOutcome editAlphaOptional(String label, String value, int length) {
        String examined = examinedValue(label, value, length);
        if (FieldValidationFlag.isNeverSupplied(examined)) {
            return EditOutcome.acceptable();
        }
        if (!isLettersAndSpacesOnly(examined)) {
            return EditOutcome.notAcceptable(composed(label, SUFFIX_ALPHABETS_ONLY));
        }
        return EditOutcome.acceptable();
    }

    /**
     * Edits an optional letters-or-digits field, as {@code 1240-EDIT-ALPHANUM-OPT} does at L2061 to
     * L2105.
     *
     * <p>The paragraph marks an absent field acceptable at {@code app/cbl/COACTUPC.cbl} L2072 and leaves
     * at L2073, then applies the wider conversion at L2079 to L2082, the residue test at L2084 to L2087
     * and its sentence at L2092 to L2098.</p>
     *
     * <p>Assumptions: like its required counterpart this edit is defined in the baseline and performed
     * nowhere in it, and it is migrated for the same reason -- so that the paragraph set maps one to one
     * and no reader has to wonder which paragraph was dropped.</p>
     *
     * @param label the field label the sentence is prefixed with; must not be {@code null}
     * @param value the submitted value as it arrived, which may be padded, short, blank or {@code null}
     * @param length the number of characters to examine
     * @return the verdict, which is acceptable and carries no wording when the field is absent, an
     *     {@link EditOutcome} that is never {@code null}
     * @throws NullPointerException if {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code length} is not positive
     */
    public EditOutcome editAlphanumericOptional(String label, String value, int length) {
        String examined = examinedValue(label, value, length);
        if (FieldValidationFlag.isNeverSupplied(examined)) {
            return EditOutcome.acceptable();
        }
        if (!isLettersDigitsAndSpacesOnly(examined)) {
            return EditOutcome.notAcceptable(composed(label, SUFFIX_NUMBERS_OR_ALPHABETS_ONLY));
        }
        return EditOutcome.acceptable();
    }

    /**
     * Edits a required non-zero digits-only field, as {@code 1245-EDIT-NUM-REQD} does at L2109 to L2176.
     *
     * <p>Three verdicts in the paragraph's order: the absence test at {@code app/cbl/COACTUPC.cbl} L2114
     * to L2119 with the shared sentence at L2124 to L2129; the digits-only test at L2137 and L2138 with
     * its own sentence at L2144 to L2149; and the non-zero test at L2156 and L2157 with its sentence at
     * L2161 to L2166. It is the most reused of the eight generic edits: the driver performs it for the
     * credit score, the postal code and the funds-transfer account, and the identifier edit performs it
     * three times more.</p>
     *
     * <p>Assumptions: the digits-only test is applied to the field AT WIDTH, so a value shorter than the
     * examined length is padded and then refused. That is the baseline's behaviour rather than an
     * accident of the padding: its field is fixed-width and space-filled, and a space is not a digit, so
     * a two-character credit score in a three-character field fails the test at L2137. Trimming before
     * the test would accept a value the baseline refuses.</p>
     *
     * <p>Assumptions: the non-zero test is a digit test rather than a conversion. The baseline converts
     * and compares against zero at L2156, but it reaches that statement only once the digits-only test
     * has passed, so every character is a digit and the converted value is zero exactly when all of them
     * are zero. Converting would add a numeric type to a path that needs none.</p>
     *
     * @param label the field label the sentence is prefixed with; must not be {@code null}
     * @param value the submitted value as it arrived, which may be padded, short, blank or {@code null}
     * @param length the number of characters to examine
     * @return the verdict, an {@link EditOutcome} that is never {@code null}
     * @throws NullPointerException if {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code length} is not positive
     */
    public EditOutcome editNumericRequired(String label, String value, int length) {
        String examined = examinedValue(label, value, length);
        if (FieldValidationFlag.isNeverSupplied(examined)) {
            return EditOutcome.blank(composed(label, SUFFIX_MUST_BE_SUPPLIED));
        }
        if (!isAllDigits(examined)) {
            return EditOutcome.notAcceptable(composed(label, SUFFIX_ALL_NUMERIC));
        }
        if (isAllZeroDigits(examined)) {
            return EditOutcome.notAcceptable(composed(label, SUFFIX_MUST_NOT_BE_ZERO));
        }
        return EditOutcome.acceptable();
    }

    /**
     * Edits a signed amount, as {@code 1250-EDIT-SIGNED-9V2} does at L2180 to L2221.
     *
     * <p>Two verdicts in the paragraph's order: the absence test at {@code app/cbl/COACTUPC.cbl} L2184
     * and L2185 with the shared sentence at L2189 to L2194, and the shape test at L2201 with its own
     * sentence at L2207 to L2211. The driver performs it for all five amounts on the screen.</p>
     *
     * <p>Assumptions: the shape test is delegated to the account mapper's own accepted-amount predicate
     * rather than restated, because the shape an amount must have is a representation fact and that
     * class owns representation. The baseline asks the same question of the same field with a numeric
     * test function at L2201; asking it in two places would let a value be acceptable to the edit and
     * unconvertible to the mapper that has to store it.</p>
     *
     * <p>Alternatives Considered: converting the value here and reporting the conversion's own failure.
     * Rejected because it would reach a value, and this edit reaches a verdict -- exact fixed point is
     * carried through the mapper and the entity, so no IEEE-754 binary floating point appears on this
     * path at any point, and pulling the conversion forward would put a second copy of that guarantee
     * in a class whose job is to say yes or no.</p>
     *
     * @param label the field label the sentence is prefixed with; must not be {@code null}
     * @param value the submitted amount as the screen carries it, which may be blank or {@code null}
     * @return the verdict, an {@link EditOutcome} that is never {@code null}
     * @throws NullPointerException if {@code label} is {@code null}
     */
    public EditOutcome editSignedNineV2(String label, String value) {
        Objects.requireNonNull(label, "label must not be null");

        if (FieldValidationFlag.isNeverSupplied(value)) {
            return EditOutcome.blank(composed(label, SUFFIX_MUST_BE_SUPPLIED));
        }
        if (!AccountMapper.isEditedAmount(value)) {
            return EditOutcome.notAcceptable(composed(label, SUFFIX_IS_NOT_VALID));
        }
        return EditOutcome.acceptable();
    }

    /**
     * Edits the three parts of the national identifier, as {@code 1265-EDIT-US-SSN} does at L2431 to
     * L2489.
     *
     * <p>The paragraph edits each part with the required non-zero digits-only edit, performing it at
     * {@code app/cbl/COACTUPC.cbl} L2442 for the first part at length three, L2472 for the second at
     * length two and L2484 for the third at length four, each under its own label set at L2439, L2469
     * and L2481. Those three are the only invocations of a generic edit anywhere outside the driver, and
     * they are why the driver's own count of them is twenty-four rather than the file's twenty-seven.</p>
     *
     * <p>Assumptions: the FIRST part carries a second, value-domain test that the other two do not --
     * the refused-prefix condition declared at L121 to L123 -- and it is guarded on the digits-only edit
     * having passed, at L2448. The guard is reproduced: the domain is expressed over a numeric
     * redefinition, so asking it of a value that is not all digits would compare something that is not
     * a number.</p>
     *
     * <p>Trade-offs: three verdicts are returned together rather than one, because the paragraph reports
     * three markers -- it moves each part's marker to its own destination at L2444, L2474 and L2486 --
     * and each part is a separate control on the screen. Collapsing them into one would tell a caller
     * that the identifier is wrong without saying which third of it to correct.</p>
     *
     * @param firstPart the submitted first three characters, which may be blank or {@code null}
     * @param middlePart the submitted fourth and fifth characters, which may be blank or {@code null}
     * @param lastPart the submitted final four characters, which may be blank or {@code null}
     * @return the three verdicts in the paragraph's own order, a {@link NationalIdentifierVerdict} that
     *     is never {@code null}
     */
    public NationalIdentifierVerdict editNationalIdentifier(String firstPart, String middlePart,
            String lastPart) {

        EditOutcome first = editNumericRequired(LABEL_NATIONAL_IDENTIFIER_PART_1, firstPart,
                NATIONAL_IDENTIFIER_PART_1_WIDTH);
        if (first.state().isValid() && isRefusedIdentifierPrefix(firstPart)) {
            first = EditOutcome.notAcceptable(composed(LABEL_NATIONAL_IDENTIFIER_PART_1,
                    SUFFIX_NATIONAL_IDENTIFIER_PREFIX));
        }

        return new NationalIdentifierVerdict(first,
                editNumericRequired(LABEL_NATIONAL_IDENTIFIER_PART_2, middlePart,
                        NATIONAL_IDENTIFIER_PART_2_WIDTH),
                editNumericRequired(LABEL_NATIONAL_IDENTIFIER_PART_3, lastPart,
                        NATIONAL_IDENTIFIER_PART_3_WIDTH));
    }

    /**
     * Edits the credit score's range, as {@code 1275-EDIT-FICO-SCORE} does at L2514 to L2531.
     *
     * <p>The paragraph tests one condition at {@code app/cbl/COACTUPC.cbl} L2515 -- the range declared
     * at L848 and L849 over the numeric redefinition at L846 and L847 -- marks the field
     * not-acceptable at L2519 and composes its sentence at L2521 to L2526.</p>
     *
     * <p>Assumptions: this edit assumes the digits-only edit has already passed, which is the guard the
     * driver applies at L1553. A value that is not three digits therefore yields the not-acceptable
     * verdict here as well, because a value outside the digit domain is also outside the numeric range,
     * and that is the same answer the guard would have produced by never reaching this edit.</p>
     *
     * @param label the field label the sentence is prefixed with; must not be {@code null}
     * @param value the submitted credit score as the screen carries it, which may be blank or
     *     {@code null}
     * @return the verdict, an {@link EditOutcome} that is never {@code null}
     * @throws NullPointerException if {@code label} is {@code null}
     */
    public EditOutcome editCreditScore(String label, String value) {
        Objects.requireNonNull(label, "label must not be null");

        String examined = atWidth(value, CREDIT_SCORE_WIDTH);
        if (!isAllDigits(examined)) {
            return EditOutcome.notAcceptable(composed(label, SUFFIX_CREDIT_SCORE_RANGE));
        }

        int score = Integer.parseInt(examined);
        if (score < CREDIT_SCORE_FLOOR || score > CREDIT_SCORE_CEILING) {
            return EditOutcome.notAcceptable(composed(label, SUFFIX_CREDIT_SCORE_RANGE));
        }
        return EditOutcome.acceptable();
    }

    /**
     * The outcome of ONE edit: the marker it sets and the sentence it composed.
     *
     * <p>Refactoring Rationale: this pair is what the baseline's edit routines actually produce, and
     * carrying it as a return value is what lets the shared argument block disappear. A routine writes a
     * marker byte -- one of the fields at {@code app/cbl/COACTUPC.cbl} L56 to L59 or L64 to L80 -- and,
     * while the message field declared at L479 is still clear, a sentence into it. Both were shared
     * storage that the next edit overwrote, so an edit's outcome had a lifetime of exactly one statement.
     * Here it is a value, so two edits cannot interfere and a caller can hold both outcomes at once.</p>
     *
     * <p>Assumptions: this is deliberately NOT the collaborator's own result type, even though the two
     * describe adjacent things. That type carries per-field entries, so the field identity is already
     * baked into it; these edits are field-AGNOSTIC by design -- they take a label and not an identity,
     * which is the whole point of the parameterisation -- so the identity is supplied by the driver when
     * it records the outcome. Reusing the other type would have forced every generic edit to take an
     * identity it has no use for.</p>
     *
     * @param state the marker the edit set: acceptable, never supplied, or not acceptable; never
     *     {@code null}
     * @param message the sentence the edit composed, carried verbatim from the baseline literals;
     *     {@code null} exactly when the state is acceptable
     */
    public record EditOutcome(FieldValidationFlag state, String message) {

        /**
         * Rejects an outcome that could not describe its own verdict.
         *
         * @param state the marker to accept, required because every edit sets one
         * @param message the sentence to accept, required to be present exactly when the state is an
         *     error, because an error with nothing to say cannot be rendered and an acceptable field
         *     has nothing to say
         * @throws NullPointerException if {@code state} is {@code null}
         * @throws IllegalArgumentException if a sentence is present for an acceptable state or absent
         *     for an error state
         */
        public EditOutcome {
            Objects.requireNonNull(state, "state must not be null");

            // WHY : Assumptions: the two components are required to move together because every failing
            //       path in the migrated paragraphs sets a marker AND composes a sentence, and every
            //       passing path sets a marker and composes nothing. A shape outside those two has no
            //       baseline counterpart, and permitting one would let a caller record a field in error
            //       with no text for the user or a passing field that nonetheless carries a complaint.
            if (state.isValid() != (message == null)) {
                throw new IllegalArgumentException("an error state must carry a message and an "
                        + "acceptable state must carry none, but found state " + state
                        + " with a message " + (message == null ? "absent" : "present"));
            }
        }

        /**
         * The outcome of an edit that found nothing to report.
         *
         * @return an outcome carrying the acceptable marker and no sentence, never {@code null}
         */
        public static EditOutcome acceptable() {
            return new EditOutcome(FieldValidationFlag.VALID, null);
        }

        /**
         * The outcome of an edit whose field was never supplied.
         *
         * @param message the sentence to carry, verbatim from the baseline literal
         * @return an outcome carrying the never-supplied marker, never {@code null}
         * @throws IllegalArgumentException if {@code message} is {@code null}, since a reported field
         *     must carry text
         */
        public static EditOutcome blank(String message) {
            return new EditOutcome(FieldValidationFlag.BLANK, message);
        }

        /**
         * The outcome of an edit whose field carried a value the rules refuse.
         *
         * @param message the sentence to carry, verbatim from the baseline literal
         * @return an outcome carrying the not-acceptable marker, never {@code null}
         * @throws IllegalArgumentException if {@code message} is {@code null}
         */
        public static EditOutcome notAcceptable(String message) {
            return new EditOutcome(FieldValidationFlag.NOT_OK, message);
        }
    }

    /**
     * The three outcomes the national-identifier edit produces, in the baseline's own order.
     *
     * <p>Assumptions: three components rather than one, because the paragraph moves three separate
     * markers to three separate destinations at {@code app/cbl/COACTUPC.cbl} L2444, L2474 and L2486, and
     * each part is its own control on the screen and its own property in the request.</p>
     *
     * @param firstPart the verdict for the first three characters, edited at L2442; never {@code null}
     * @param middlePart the verdict for the fourth and fifth, edited at L2472; never {@code null}
     * @param lastPart the verdict for the final four, edited at L2484; never {@code null}
     */
    public record NationalIdentifierVerdict(EditOutcome firstPart, EditOutcome middlePart,
            EditOutcome lastPart) {

        /**
         * Rejects a verdict missing any of the three parts it must report.
         *
         * @param firstPart the first part's verdict to accept, required because the paragraph always
         *     reaches it
         * @param middlePart the second part's verdict to accept, required for the same reason
         * @param lastPart the third part's verdict to accept, required for the same reason
         * @throws NullPointerException if any component is {@code null}
         */
        public NationalIdentifierVerdict {
            Objects.requireNonNull(firstPart, "firstPart must not be null");
            Objects.requireNonNull(middlePart, "middlePart must not be null");
            Objects.requireNonNull(lastPart, "lastPart must not be null");
        }
    }

    /**
     * The outputs of one run of the edit driver.
     *
     * <p>Refactoring Rationale: the four components are exactly the four externals the baseline's driver
     * leaves behind, so that a reader can match them one to one instead of inferring them. The entry
     * array is the migrated form of the per-field marker group declared at {@code app/cbl/COACTUPC.cbl}
     * L191 and running to L352, which the presentation template at {@code app/cpy/CSSETATY.cpy} L17 to
     * L27 renders onto the screen. That template is invoked THIRTY-NINE times in the program, each time
     * with the three substitution placeholders filled for one screen field -- the pattern is visible at
     * {@code app/cbl/COACTUPC.cbl} L3208 to L3211, where the marker, the screen field and the mapset are
     * substituted in that order -- and thirty-nine exceeds the marker count because a decomposed date is
     * highlighted one part at a time. An array carries all of them, so the entry count is not capped by
     * anything here. The remaining three come from the program again: the sentence is the
     * seventy-five-character message field declared at {@code app/cbl/COACTUPC.cbl} L479; the failure
     * switch is {@code WS-INPUT-FLAG} declared at L171 with its acceptable condition at L172, which the
     * driver sets at L1431 and which its own tail tests at L1671; and the no-change marker is
     * {@code WS-DATACHANGED-FLAG} declared at L168 with its two conditions at L169 and L170. Returning
     * them together is what makes the driver testable without a screen.</p>
     *
     * @param fieldErrors one entry per offending request property, in the order the driver encountered
     *     them; never {@code null}, always unmodifiable, and empty exactly when nothing failed
     * @param message the first refusal's sentence, or the no-change sentence, or {@code null} when the
     *     channel was never written -- absent rather than empty, because the baseline's own guard tests
     *     the field for being clear
     * @param inputError whether any edit refused a value, the counterpart of the baseline's failure
     *     switch
     * @param noChangesFound whether the comparison found nothing to change, in which case no field was
     *     edited at all
     */
    public record EditVerdict(List<ApiError.FieldError> fieldErrors, String message,
            boolean inputError, boolean noChangesFound) {

        /**
         * Seals the entry array and rejects a verdict that could not describe its own outcome.
         *
         * @param fieldErrors the entries to carry, copied so that a caller retaining the list it passed
         *     cannot alter a published verdict afterwards
         * @param message the sentence to carry, required to be present whenever an entry is
         * @param inputError the failure switch to carry, which needs no validation because both of its
         *     values are meaningful
         * @param noChangesFound the no-change marker to carry, for the same reason
         * @throws NullPointerException if {@code fieldErrors} is {@code null} or holds a {@code null}
         *     element
         * @throws IllegalArgumentException if entries are present without a sentence, or if entries are
         *     present alongside the no-change marker, which the driver's short-circuit makes impossible
         */
        public EditVerdict {
            fieldErrors = List.copyOf(
                    Objects.requireNonNull(fieldErrors, "fieldErrors must not be null"));

            if (!fieldErrors.isEmpty() && message == null) {
                throw new IllegalArgumentException("a verdict reporting " + fieldErrors.size()
                        + " field errors must carry the first refusal's message");
            }

            // WHY : Assumptions: entries and the no-change marker are mutually exclusive because the
            //       driver's short-circuit at app/cbl/COACTUPC.cbl L1463 to L1467 clears the whole
            //       marker group at L1466 before leaving, so the baseline cannot produce a no-change
            //       outcome that also highlights a field. Rejecting the combination here is what keeps
            //       a future caller from assembling one and having it rendered.
            if (noChangesFound && !fieldErrors.isEmpty()) {
                throw new IllegalArgumentException(
                        "a no-change verdict cannot also report " + fieldErrors.size()
                                + " field errors, because no field was edited");
            }
        }

        /**
         * Names every offending request property once, keeping the order the driver encountered them in.
         *
         * <p>Assumptions: the names are de-duplicated while KEEPING first-seen order. The cross-field
         * pairing edit names the state a second time when the combination fails, and a repeated name
         * would render the same field twice in the array, which a client binding entries to controls
         * would show as two markers on one control.</p>
         *
         * @return the distinct property names in encounter order, never {@code null} and always
         *     unmodifiable
         */
        public List<String> fieldNames() {
            Set<String> distinct = new LinkedHashSet<>();
            for (ApiError.FieldError entry : fieldErrors) {
                distinct.add(entry.field());
            }
            return List.copyOf(distinct);
        }
    }

    /**
     * Accumulates the driver's outputs while it runs: the entry array and the first-wins sentence.
     *
     * <p>Refactoring Rationale: this is the mutable half of the baseline's shared storage, kept as one
     * short-lived object created per run instead of as program-level fields. The baseline holds the
     * marker group at {@code app/cbl/COACTUPC.cbl} L191 to L352 and the message field at L479 for the
     * lifetime of the task, which is why it must clear them explicitly at L1466 before leaving on the
     * no-change path. A per-run object needs no clearing at all, so the one statement that could be
     * forgotten does not exist here.</p>
     */
    private static final class EditLatch {

        /** The entries recorded so far, in the order the driver produced them. */
        private final List<ApiError.FieldError> errors = new ArrayList<>();

        /** The first sentence recorded, or {@code null} while the channel is still clear. */
        private String message;

        /**
         * Records one edit's outcome against the property it examined, ignoring an acceptable one.
         *
         * @param field the request property the edit examined; must not be {@code null}, empty or
         *     entirely whitespace when the outcome is an error
         * @param outcome the edit's verdict; must not be {@code null}
         * @throws NullPointerException if {@code outcome} is {@code null}, or if {@code field} is
         *     {@code null} and the outcome is an error
         * @throws IllegalArgumentException if {@code field} is empty or entirely whitespace and the
         *     outcome is an error, as propagated from the shared entry construction
         */
        private void record(String field, EditOutcome outcome) {
            Objects.requireNonNull(outcome, "outcome must not be null");

            // WHY : Assumptions: an acceptable outcome records NOTHING rather than an entry saying so,
            //       because the presence of an entry is what signals a failure -- the shared entry type
            //       refuses an acceptable state for exactly that reason, and the baseline's template at
            //       app/cpy/CSSETATY.cpy L18 and L19 evaluates nothing whatsoever for a field that is
            //       not in error.
            if (outcome.state().isValid()) {
                return;
            }

            this.errors.add(new ApiError.FieldError(field, outcome.state(), outcome.message()));
            latchMessage(outcome.message());
        }

        /**
         * Merges a collaborator's outcome, keeping its entries in order and its sentence if first.
         *
         * @param result the collaborator's outcome; must not be {@code null}
         * @throws NullPointerException if {@code result} is {@code null}
         */
        private void merge(AddressValidationService.AddressValidationResult result) {
            Objects.requireNonNull(result, "result must not be null");

            if (result.isValid()) {
                return;
            }
            this.errors.addAll(result.fieldErrors());
            latchMessage(result.message());
        }

        /**
         * Records one already-assembled entry, used where a collaborator reports its own identities.
         *
         * @param entry the entry to record; must not be {@code null}
         * @throws NullPointerException if {@code entry} is {@code null}
         */
        private void recordEntry(ApiError.FieldError entry) {
            this.errors.add(Objects.requireNonNull(entry, "entry must not be null"));
        }

        /**
         * Writes a sentence to the aggregate channel only while that channel is still clear.
         *
         * @param candidate the sentence to consider; ignored when {@code null}
         */
        private void latchMessage(String candidate) {
            // WHY : Assumptions: first wins, because the baseline gates every write to its message
            //       field on the field still being clear -- the condition declared at
            //       app/cbl/COACTUPC.cbl L480 guards the write at L1912 and at every other composing
            //       site. Last-wins would show a user the complaint about whichever field happens to be
            //       edited last rather than the first thing that went wrong.
            if (this.message == null && candidate != null) {
                this.message = candidate;
            }
        }

        /**
         * Seals what has been accumulated into the driver's verdict.
         *
         * @param noChangesFound whether the comparison found nothing to change
         * @return the verdict, an {@link EditVerdict} that is never {@code null}
         * @throws IllegalArgumentException if entries were recorded without a sentence, or alongside the
         *     no-change marker, as propagated from the verdict's own construction
         */
        private EditVerdict toVerdict(boolean noChangesFound) {
            return new EditVerdict(this.errors, this.message, !this.errors.isEmpty(),
                    noChangesFound);
        }
    }

    /**
     * Separates the two version numbers inside one revision token.
     *
     * <p>Assumptions: a character that cannot occur in a decimal version, so the token stays unambiguous
     * however large either version grows.</p>
     */
    private static final String REVISION_SEPARATOR = "-";

    /**
     * The letters the baseline admits in a letters-only field.
     *
     * <p>Assumptions: the two halves are the values of the fields declared at
     * {@code app/cbl/COACTUPC.cbl} L588 and L590, carried on the continuation lines L589 and L591, joined
     * in that order so the fifty-two characters read exactly as the baseline groups them.</p>
     */
    private static final String ADMITTED_LETTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /**
     * The digits the baseline adds for a letters-or-digits field.
     *
     * <p>Assumptions: the value of the field declared at {@code app/cbl/COACTUPC.cbl} L592, carried on
     * the continuation line L593.</p>
     */
    private static final String ADMITTED_DIGITS = "0123456789";

    /**
     * Refuses the submission when any edit reported a failure.
     *
     * <p>Assumptions: the refusal carries the FIRST failure's state and sentence alongside every
     * offending property, because that pairing is what the baseline shows -- one sentence on its
     * seventy-five-character line and a marker on each failing field. The shared refusal type carries
     * exactly that shape, so nothing here is invented.</p>
     *
     * @param verdict the driver's outcome; must not be {@code null}
     * @throws ClientInputException if the verdict reports any failure, which the shared advice renders
     *     as HTTP 400 with one entry per property
     * @throws IllegalArgumentException as the parent of the above, since {@link ClientInputException}
     *     extends it
     */
    private static void refuseWhenAnyEditFailed(EditVerdict verdict) {
        if (!verdict.inputError()) {
            return;
        }
        throw new ClientInputException(ApiError.CODE_VALIDATION, verdict.fieldNames(),
                verdict.fieldErrors().get(0).state(), verdict.message());
    }

    /**
     * Runs one date edit and records its component verdicts under the request's own property names.
     *
     * <p>Assumptions: the date edits are the shared kernel's, because the baseline obtains them from a
     * shared copybook by {@code COPY 'CSUTLDWY'.} at {@code app/cbl/COACTUPC.cbl} L166 rather than
     * declaring them, and it performs the same routine for all four of its date fields -- at L1479 to
     * L1482, L1491 to L1494, L1504 to L1507 and L1536 to L1538. Restating the calendar rules here would
     * put one shared routine in two places.</p>
     *
     * <p>Assumptions: the range edit runs only when the general edit has already passed, which is the
     * guard at L1539 immediately before the performance at L1540 and L1541. The shared validator states
     * the same precondition on its own member and raises rather than reports for a date it cannot read,
     * so calling it unguarded would turn a user's typing mistake into a server fault.</p>
     *
     * <p>Trade-offs: the shared validator reports its three components under the generic names its own
     * copybook declares, and they are re-keyed here onto the request's four-times-three properties. The
     * cost is a translation; what it buys is that a client can attach the refusal to the control the
     * value came from, which it could not do with three identically named components on a screen that
     * carries four dates.</p>
     *
     * @param latch the accumulator to record into; must not be {@code null}
     * @param propertyStem the request property prefix the three parts arrived under; must not be
     *     {@code null}
     * @param label the field label the sentences are prefixed with; must not be {@code null}
     * @param year the submitted year part, which may be blank or {@code null}
     * @param month the submitted month part, which may be blank or {@code null}
     * @param day the submitted day part, which may be blank or {@code null}
     * @param rangeChecked whether the not-in-the-future range edit applies, which it does for the date
     *     of birth alone
     * @throws IllegalArgumentException if the shared validator reports a failure carrying no text, which
     *     would breach its own contract, as propagated from the shared entry construction
     */
    private void recordDateEdit(EditLatch latch, String propertyStem, String label, String year,
            String month, String day, boolean rangeChecked) {

        String assembled = assembledDate(year, month, day);
        DateEditValidator.DateEditResult result = DateEditValidator.validate(label, assembled);
        if (result.isValid() && rangeChecked) {
            result = DateEditValidator.validateDateOfBirth(label, assembled,
                    LocalDate.now(this.clock));
        }
        if (result.isValid()) {
            return;
        }

        for (FieldValidationFlag.FieldError entry : result.fieldErrors()) {
            latch.recordEntry(new ApiError.FieldError(
                    datePartProperty(propertyStem, entry.field()), entry.state(), entry.message()));
        }

        // WHY : Assumptions: a refusal that names no component is recorded against the date AS A WHOLE
        //       rather than dropped, and the case is real rather than defensive. The shared validator's
        //       own documentation records that its final gate can set the failure switch while leaving
        //       all three component markers acceptable, because the statement it reproduces clears the
        //       marker group. Dropping such a refusal would leave the failure switch set with nothing
        //       recorded, and the driver would then report a failure with an empty array.
        if (result.fieldErrors().isEmpty()) {
            latch.recordEntry(new ApiError.FieldError(
                    propertyStem, FieldValidationFlag.NOT_OK, result.message()));
        }

        latch.latchMessage(result.message());
    }

    /**
     * Names the request property one date component arrived under.
     *
     * @param propertyStem the request property prefix, such as the open date's; must not be {@code null}
     * @param component the component identity the shared validator reported, one of its three declared
     *     names
     * @return the request property name for that component, never {@code null}
     * @throws IllegalArgumentException if the component is not one of the shared validator's three, since
     *     an unrecognised component would otherwise be reported under a property no request carries
     */
    private static String datePartProperty(String propertyStem, String component) {
        return switch (component) {
            case DateEditValidator.FIELD_YEAR -> propertyStem + "Year";
            case DateEditValidator.FIELD_MONTH -> propertyStem + "Month";
            case DateEditValidator.FIELD_DAY -> propertyStem + "Day";
            default -> throw new IllegalArgumentException(
                    "the shared date validator reported an unrecognised component named " + component);
        };
    }

    /**
     * Assembles three submitted parts into the unseparated eight-character date form.
     *
     * <p>Assumptions: each part is taken AT its declared width, so the result is always exactly eight
     * characters and the shared validator's positional split always lands correctly. That is the
     * baseline's own shape: its date field is {@code PIC X(08)} redefined into a four-character year and
     * two two-character parts, at {@code app/cbl/COACTUPC.cbl} L684 to L689 and twice more at L690 to
     * L700, so a part the user left empty reaches the routine as spaces inside a full-width field rather
     * than shortening it. Joining the parts as they arrived would have produced a shorter value, which
     * the shared validator refuses outright as a caller-assembly fault rather than reporting per
     * component.</p>
     *
     * @param year the submitted year part, which may be blank or {@code null}
     * @param month the submitted month part, which may be blank or {@code null}
     * @param day the submitted day part, which may be blank or {@code null}
     * @return the eight-character unseparated form, never {@code null}
     */
    private static String assembledDate(String year, String month, String day) {
        return atWidth(year, DATE_YEAR_WIDTH) + atWidth(month, DATE_MONTH_WIDTH)
                + atWidth(day, DATE_DAY_WIDTH);
    }

    /**
     * Assembles three submitted parts into the fifteen-character stored telephone form.
     *
     * <p>Assumptions: the layout is the one the baseline declares at {@code app/cbl/COACTUPC.cbl} L82 to
     * L100 and describes in its own comment at L2227 and L2228 -- an opening bracket, three digits, a
     * closing bracket, three digits, a hyphen, four digits and two trailing positions. Each part is taken
     * at its declared width so the three positions the collaborator reads are always exact; a part wider
     * than its field is a representation fault the customer mapper refuses by exact width at the same
     * boundary, so narrowing it here cannot let an over-wide value through.</p>
     *
     * @param areaCode the submitted area code, which may be blank or {@code null}
     * @param phonePrefix the submitted prefix, which may be blank or {@code null}
     * @param lineNumber the submitted line number, which may be blank or {@code null}
     * @return the fifteen-character stored form, never {@code null}
     */
    private static String assembledTelephoneNumber(String areaCode, String phonePrefix,
            String lineNumber) {
        return "(" + atWidth(areaCode, AddressValidationService.AREA_CODE_WIDTH)
                + ")" + atWidth(phonePrefix, AddressValidationService.PHONE_PREFIX_WIDTH)
                + "-" + atWidth(lineNumber, AddressValidationService.PHONE_LINE_NUMBER_WIDTH)
                + "  ";
    }

    /**
     * Validates a generic edit's arguments and yields the value at the width it examines.
     *
     * @param label the field label the caller supplied; must not be {@code null}
     * @param value the submitted value as it arrived, which may be blank or {@code null}
     * @param length the number of characters to examine
     * @return the value at exactly {@code length} characters, never {@code null}
     * @throws NullPointerException if {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code length} is not positive, because a non-positive width
     *     would examine no characters at all and report every value as acceptable
     */
    private static String examinedValue(String label, String value, int length) {
        Objects.requireNonNull(label, "label must not be null");
        if (length <= 0) {
            throw new IllegalArgumentException(
                    "length must be positive, but was " + length + " for label " + label);
        }
        return atWidth(value, length);
    }

    /**
     * Presents a value at exactly one width, padding a short one and narrowing a long one.
     *
     * <p>Assumptions: this is the target's form of a COBOL alphanumeric move into a fixed-width field,
     * which left-justifies, pads on the right with spaces and truncates what does not fit. Every generic
     * edit operates on such a field, so presenting the value this way is what makes the edits comparable
     * with the paragraphs rather than merely similar to them.</p>
     *
     * @param value the value as it arrived, which may be blank or {@code null}
     * @param width the width to present it at, which must be positive
     * @return the value at exactly {@code width} characters, never {@code null}
     */
    private static String atWidth(String value, int width) {
        String supplied = value == null ? "" : value;
        if (supplied.length() >= width) {
            return supplied.substring(0, width);
        }
        return supplied + " ".repeat(width - supplied.length());
    }

    /**
     * Composes one edit's sentence from a field label and a baseline suffix.
     *
     * <p>Assumptions: the label is narrowed to {@link #LABEL_WIDTH} and then trimmed, in that order,
     * because that is what the baseline does -- it moves the label into a twenty-five character field at
     * {@code app/cbl/COACTUPC.cbl} L53 and trims it inside the composing statement, for instance at
     * L1913 and L1914. The order is not interchangeable: trimming first and narrowing afterwards would
     * keep a twenty-sixth character that the baseline has already discarded, which changes the one
     * sentence built from a label longer than the field.</p>
     *
     * @param label the field label, one of this class's label constants; must not be {@code null}
     * @param suffix the baseline suffix, one of this class's suffix constants; must not be {@code null}
     * @return the composed sentence, never {@code null}
     * @throws NullPointerException if {@code label} or {@code suffix} is {@code null}
     */
    private static String composed(String label, String suffix) {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(suffix, "suffix must not be null");
        return atWidth(label, LABEL_WIDTH).trim() + suffix;
    }

    /**
     * Answers whether every character of a value is a decimal digit.
     *
     * @param value the value to examine; must not be {@code null}
     * @return {@code true} when the value has content and every character is a digit; {@code false}
     *     otherwise
     */
    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (ADMITTED_DIGITS.indexOf(value.charAt(index)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Answers whether a value is digits only and every one of them is zero.
     *
     * @param value the value to examine; must not be {@code null}
     * @return {@code true} when the value has content and every character is the digit zero;
     *     {@code false} otherwise
     */
    private static boolean isAllZeroDigits(String value) {
        if (!isAllDigits(value)) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Answers whether every character of a value is a letter or a space.
     *
     * <p>Assumptions: the space is admitted although it is not among the fifty-two characters the
     * baseline lists at {@code app/cbl/COACTUPC.cbl} L588 to L591, and the reason is mechanical: the
     * baseline converts each listed character INTO a space and then asks whether the trimmed remainder
     * has any length, so a space that was there to begin with survives the conversion indistinguishably
     * and is trimmed away with the rest. Admitting it explicitly here states what that mechanism
     * implies, and the paragraph's own comment at L1924 says the same thing in words.</p>
     *
     * @param value the value to examine; must not be {@code null}
     * @return {@code true} when every character is a letter or a space; {@code false} otherwise
     */
    private static boolean isLettersAndSpacesOnly(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ' ' && ADMITTED_LETTERS.indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Answers whether every character of a value is a letter, a digit or a space.
     *
     * <p>Assumptions: the admitted set is the sixty-two characters the baseline groups at
     * {@code app/cbl/COACTUPC.cbl} L586 to L593, plus the space for the mechanical reason recorded on
     * {@link #isLettersAndSpacesOnly(String)}.</p>
     *
     * @param value the value to examine; must not be {@code null}
     * @return {@code true} when every character is a letter, a digit or a space; {@code false} otherwise
     */
    private static boolean isLettersDigitsAndSpacesOnly(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ' ' && ADMITTED_LETTERS.indexOf(character) < 0
                    && ADMITTED_DIGITS.indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Answers whether the identifier's first part names one of the refused prefixes.
     *
     * <p>Assumptions: the three refused shapes are the ones declared at {@code app/cbl/COACTUPC.cbl}
     * L121 to L123 over the numeric redefinition at L119 and L120, and they are tested as numbers
     * because the condition is declared over that redefinition rather than over the character field.
     * The caller reaches this test only once the digits-only edit has passed, at L2448, so the value is
     * known to convert.</p>
     *
     * @param firstPart the submitted first part, which may be blank or {@code null}
     * @return {@code true} when the part is one of the refused prefixes; {@code false} when it is not,
     *     including when it is not three digits at all
     */
    private static boolean isRefusedIdentifierPrefix(String firstPart) {
        String examined = atWidth(firstPart, NATIONAL_IDENTIFIER_PART_1_WIDTH);
        if (!isAllDigits(examined)) {
            return false;
        }
        int prefix = Integer.parseInt(examined);
        return prefix == NATIONAL_IDENTIFIER_PREFIX_ZERO
                || prefix == NATIONAL_IDENTIFIER_PREFIX_SIX_SIX_SIX
                || (prefix >= NATIONAL_IDENTIFIER_PREFIX_RANGE_FLOOR
                        && prefix <= NATIONAL_IDENTIFIER_PREFIX_RANGE_CEILING);
    }

    /**
     * Answers whether the submission changes nothing, as {@code 1205-COMPARE-OLD-NEW} decides at L1681
     * to L1777.
     *
     * <p>The paragraph presets the no-change marker at {@code app/cbl/COACTUPC.cbl} L1682, compares the
     * account region in one condition at L1684 to L1700 and the customer region in a second at L1708 to
     * L1768, and sets the change marker at L1703 or L1771 the moment either condition fails, leaving by
     * its own exit at L1704 or L1772 for the paragraph exit at L1777.</p>
     *
     * <p>Assumptions: the split into two conditions is carried across as two predicates because the
     * split follows the two RECORDS. The baseline writes no comment saying so, but the boundary is
     * evident from the operands: the first condition names only account members and the second only
     * customer members, and each has its own change-marker set and its own branch to the exit. One
     * predicate per record therefore keeps the arrangement rather than reorganising it, and a reader
     * checking one condition against one predicate has the same operand list on both sides.</p>
     *
     * <p>Refactoring Rationale: the comparison is made against the LOADED ROWS where the baseline
     * compares against a before-image it echoed to the terminal a turn earlier. There is no earlier turn
     * in the target -- the re-entry discriminator at {@code app/cpy/COCOM01Y.cpy} L29 to L31 is gone and
     * the transaction carries no task storage, per {@code TWASIZE(0)} at {@code app/csd/CARDDEMO.CSD}
     * L308 -- so the only prior state available is the state a read established. Using the loaded rows is
     * also the stronger reading: a submission that matches what is stored now genuinely changes nothing,
     * whereas one that matched a stale echo might have.</p>
     *
     * <p>Assumptions: this decides only whether the FIELD EDITS run, and deliberately not whether the
     * write runs. In the baseline the write is a separate confirmed action that the action paragraph
     * permits only on an explicit save, so a no-change submission simply redisplayed; over HTTP the
     * submission IS the confirmation and there is no second turn to redisplay in. Writing values
     * identical to the stored ones is observably indistinguishable from not writing them, and the version
     * member does not advance either, because the provider does not dirty a managed row whose members
     * were assigned their existing values.</p>
     *
     * <p>Trade-offs: every comparison that cannot be made resolves to CHANGED, which errs toward running
     * the edits rather than skipping them, and three of them cannot be made exactly. The two protected
     * identifiers are stored enciphered and the entity exposes no accessor for either, so only the
     * submitter's INTENT is knowable -- an omitted value means preserve and therefore no change, while a
     * supplied one is treated as a change even if it happens to match. The telephone numbers are compared
     * as assembled numbers rather than as three parts each, which additionally notices a difference in
     * the separators that the part-wise comparison at {@code app/cbl/COACTUPC.cbl} L1748 to L1753
     * ignores. In every one of
     * those cases the error is in the safe direction: the edits run when they need not have, never the
     * reverse.</p>
     *
     * @param request the submitted change; must not be {@code null}
     * @param account the loaded account row; must not be {@code null}
     * @param customer the loaded customer row; must not be {@code null}
     * @return {@code true} when every compared value already holds the submitted value; {@code false}
     *     when anything differs or cannot be compared
     */
    private static boolean compareOldNew(AccountUpdateRequest request, Account account,
            Customer customer) {
        return accountRegionUnchanged(request, account)
                && customerRegionUnchanged(request, customer);
    }

    /**
     * Answers whether the account region of a submission matches the loaded row.
     *
     * <p>Assumptions: the eleven comparisons are the ones the first condition makes, at
     * {@code app/cbl/COACTUPC.cbl} L1684 to L1700, in that order -- the key, the status without regard to
     * case, the five amounts, the three dates and the group without regard to case or padding. The
     * account's own postal code is absent from the comparison because it is absent from the baseline's
     * before-image too, and the request carries no component for it.</p>
     *
     * @param request the submitted change; must not be {@code null}
     * @param account the loaded account row; must not be {@code null}
     * @return {@code true} when every compared value already holds the submitted value
     */
    private static boolean accountRegionUnchanged(AccountUpdateRequest request, Account account) {
        return sameNumber(request.accountId(), account.getAccountId())
                && sameTextIgnoringCase(request.activeStatus(), account.getActiveStatus())
                && sameAmount(request.currentBalance(), account.getCurrentBalance())
                && sameAmount(request.creditLimit(), account.getCreditLimit())
                && sameAmount(request.cashCreditLimit(), account.getCashCreditLimit())
                && sameAmount(request.currentCycleCredit(), account.getCurrentCycleCredit())
                && sameAmount(request.currentCycleDebit(), account.getCurrentCycleDebit())
                && sameDate(request.openDateYear(), request.openDateMonth(), request.openDateDay(),
                        account.getOpenDate())
                && sameDate(request.expirationDateYear(), request.expirationDateMonth(),
                        request.expirationDateDay(), account.getExpirationDate())
                && sameDate(request.reissueDateYear(), request.reissueDateMonth(),
                        request.reissueDateDay(), account.getReissueDate())
                && sameTextIgnoringCase(request.groupId(), account.getGroupId());
    }

    /**
     * Answers whether the customer region of a submission matches the loaded row.
     *
     * <p>Assumptions: the comparisons are the ones the second condition makes, at
     * {@code app/cbl/COACTUPC.cbl} L1708 to L1768, in that order. Ten of them disregard case and padding
     * because the baseline wraps them in its upper-casing and trimming functions; the funds-transfer
     * account at L1761 and L1762 and the credit score at L1767 and L1768 do not, and are compared without
     * either.</p>
     *
     * @param request the submitted change; must not be {@code null}
     * @param customer the loaded customer row; must not be {@code null}
     * @return {@code true} when every compared value already holds the submitted value
     */
    private static boolean customerRegionUnchanged(AccountUpdateRequest request,
            Customer customer) {
        return sameNumber(request.customerId(), customer.getCustomerId())
                && sameTextIgnoringCase(request.firstName(), customer.getFirstName())
                && sameTextIgnoringCase(request.middleName(), customer.getMiddleName())
                && sameTextIgnoringCase(request.lastName(), customer.getLastName())
                && sameTextIgnoringCase(request.addressLine1(), customer.getAddressLine1())
                && sameTextIgnoringCase(request.addressLine2(), customer.getAddressLine2())
                && sameTextIgnoringCase(request.city(), customer.getAddressLine3())
                && sameTextIgnoringCase(request.stateCode(), customer.getAddressStateCode())
                && sameTextIgnoringCase(request.countryCode(), customer.getAddressCountryCode())
                && sameTextIgnoringCase(request.zipCode(), customer.getAddressZip())
                && sameTelephoneNumber(request.phone1AreaCode(), request.phone1Prefix(),
                        request.phone1LineNumber(), customer.getPhoneNumber1())
                && sameTelephoneNumber(request.phone2AreaCode(), request.phone2Prefix(),
                        request.phone2LineNumber(), customer.getPhoneNumber2())
                && FieldValidationFlag.isNeverSupplied(request.ssnPart1())
                && FieldValidationFlag.isNeverSupplied(request.ssnPart2())
                && FieldValidationFlag.isNeverSupplied(request.ssnPart3())
                && FieldValidationFlag.isNeverSupplied(request.governmentIssuedId())
                && sameDate(request.dateOfBirthYear(), request.dateOfBirthMonth(),
                        request.dateOfBirthDay(), customer.getDateOfBirth())
                && sameText(request.eftAccountId(), customer.getEftAccountId())
                && sameTextIgnoringCase(request.primaryCardHolderIndicator(),
                        customer.getPrimaryCardHolderIndicator())
                && sameNumber(request.ficoCreditScore(), (long) customer.getFicoCreditScore());
    }

    /**
     * Answers whether a submitted value and a stored value carry the same text, ignoring padding.
     *
     * <p>Assumptions: both sides are trimmed before comparison although the baseline compares two
     * fixed-width fields directly at the sites that use no trimming function. Two fixed-width fields
     * cannot differ in padding, so trimming changes nothing there; a request property and a padded column
     * can, and comparing them untrimmed would report a change whenever the two spellings of the same
     * value differed only in trailing spaces.</p>
     *
     * @param submitted the submitted value, which may be blank or {@code null}
     * @param stored the stored value, which may be blank or {@code null}
     * @return {@code true} when both are absent or both trim to the same text
     */
    private static boolean sameText(String submitted, String stored) {
        return trimmedOrEmpty(submitted).equals(trimmedOrEmpty(stored));
    }

    /**
     * Answers whether a submitted value and a stored value carry the same text, ignoring case.
     *
     * <p>Assumptions: case is disregarded because the baseline wraps both sides of these comparisons in
     * its upper-casing function, for instance at {@code app/cbl/COACTUPC.cbl} L1685 to L1688 for the
     * status and L1712 to L1715 for the first name. Comparing with regard to case would report a change
     * the baseline does not see, which on the no-change path would run edits the baseline skipped.</p>
     *
     * @param submitted the submitted value, which may be blank or {@code null}
     * @param stored the stored value, which may be blank or {@code null}
     * @return {@code true} when both are absent or both trim to the same text ignoring case
     */
    private static boolean sameTextIgnoringCase(String submitted, String stored) {
        return trimmedOrEmpty(submitted).equalsIgnoreCase(trimmedOrEmpty(stored));
    }

    /**
     * Answers whether a submitted numeric text and a stored number are the same value.
     *
     * <p>Assumptions: the two are compared as NUMBERS where the baseline compares zero-padded character
     * fields, for instance at {@code app/cbl/COACTUPC.cbl} L1684 for the account key. A fixed-width
     * numeric display field is always padded to its declared width, so the baseline's two sides cannot
     * differ in padding; a request property can, and comparing the texts would report a change merely
     * because a client sent the same identifier with a different number of leading zeros.</p>
     *
     * @param submitted the submitted numeric text, which may be blank or {@code null}
     * @param stored the stored value, which may be {@code null} on a row that holds none
     * @return {@code true} when the submitted text is digits naming exactly the stored value
     */
    private static boolean sameNumber(String submitted, Long stored) {
        String trimmed = trimmedOrEmpty(submitted);
        if (stored == null || !isAllDigits(trimmed)) {
            return false;
        }
        try {
            return Long.parseLong(trimmed) == stored;
        } catch (NumberFormatException tooWide) {
            // WHY : Assumptions: a value with more digits than a 64-bit integer holds is reported as a
            //       change rather than raised, because this method runs BEFORE any edit and its only
            //       job is to decide whether the edits should run. Raising here would answer a caller's
            //       typing mistake with a server fault, and the edits that follow report it properly.
            return false;
        }
    }

    /**
     * Answers whether a submitted amount and a stored amount are the same exact value.
     *
     * <p>Assumptions: the shape test and the conversion both come from the account mapper, which owns
     * representation, so an unconvertible amount is reported as a change rather than raised -- this
     * method runs before any edit and must not refuse anything itself. Exact fixed point is carried
     * throughout and no IEEE-754 binary floating point appears on this path, which is why the comparison
     * is made by scale-insensitive numeric ordering rather than by equality: an amount typed without a
     * fraction and the same amount typed with one are the same value and must not read as a change.</p>
     *
     * <p>Alternatives Considered: comparing the submitted text against the stored amount rendered back to
     * text, and converting both through a primitive binary type before comparing. The first was rejected
     * because it makes the answer depend on how the column renders rather than on what it holds, which is
     * the mistake the scale-insensitive comparison above exists to avoid. The second was rejected because
     * the amounts are declared with two fraction digits -- {@code ACCT-CURR-BAL PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy} L7 is one of the five money fields on the account record -- and a
     * binary type cannot hold every such value exactly, so two amounts that are equal in the store could
     * compare unequal and report a change nobody made. The exactness matters most on the negative side,
     * where the baseline's own toolchain has a documented trap: {@code tests/README.md} L273 to L274
     * records that reading the zoned-decimal sign with the wrong convention silently corrupts negative
     * balances rather than failing, so a comparison that quietly loses precision would be just as
     * invisible.</p>
     *
     * @param submitted the submitted amount as the screen carries it, which may be blank or {@code null}
     * @param stored the stored amount, which may be {@code null} on a row that holds none
     * @return {@code true} when the submitted text converts to exactly the stored amount
     */
    private static boolean sameAmount(String submitted, BigDecimal stored) {
        if (stored == null || !AccountMapper.isEditedAmount(submitted)) {
            return false;
        }
        return AccountMapper.storedAmount(submitted, "amount").compareTo(stored) == 0;
    }

    /**
     * Answers whether three submitted date parts name the stored date.
     *
     * <p>Assumptions: the parts are composed and the calendar decides, using the account mapper's own
     * predicate and conversion, so an impossible day is reported as a change rather than raised. The
     * baseline compares the eight-character before-image fields at {@code app/cbl/COACTUPC.cbl} L1692 to
     * L1694; comparing calendar dates instead means a date is not reported as changed merely because the
     * screen spelled it differently from the way the column renders it, which is the mistake the baseline
     * avoids by holding both forms.</p>
     *
     * @param year the submitted year part, which may be blank or {@code null}
     * @param month the submitted month part, which may be blank or {@code null}
     * @param day the submitted day part, which may be blank or {@code null}
     * @param stored the stored date, which may be {@code null} on a row that holds none
     * @return {@code true} when the three parts compose exactly the stored date
     */
    private static boolean sameDate(String year, String month, String day, LocalDate stored) {
        if (stored == null || !AccountMapper.isComposableDate(year, month, day)) {
            return false;
        }
        return AccountMapper.storedDate(year, month, day).equals(stored);
    }

    /**
     * Answers whether three submitted telephone parts name the stored number.
     *
     * <p>Assumptions: an entirely absent submission matches an entirely absent stored number, because
     * the second telephone number is optional on this screen and both sides are then the same nothing.
     * Otherwise the parts are assembled into the stored layout and compared at that width, for the reason
     * recorded on {@link #compareOldNew(AccountUpdateRequest, Account, Customer)}.</p>
     *
     * @param areaCode the submitted area code, which may be blank or {@code null}
     * @param phonePrefix the submitted prefix, which may be blank or {@code null}
     * @param lineNumber the submitted line number, which may be blank or {@code null}
     * @param stored the stored fifteen-character number, which may be blank or {@code null}
     * @return {@code true} when both are absent or the assembled number equals the stored one
     */
    private static boolean sameTelephoneNumber(String areaCode, String phonePrefix,
            String lineNumber, String stored) {

        boolean submittedAbsent = FieldValidationFlag.isNeverSupplied(areaCode)
                && FieldValidationFlag.isNeverSupplied(phonePrefix)
                && FieldValidationFlag.isNeverSupplied(lineNumber);
        if (submittedAbsent) {
            return FieldValidationFlag.isNeverSupplied(stored);
        }
        if (stored == null) {
            return false;
        }
        return assembledTelephoneNumber(areaCode, phonePrefix, lineNumber)
                .equals(atWidth(stored, AddressValidationService.STORED_PHONE_WIDTH));
    }

    /**
     * Yields a value trimmed of padding, treating an absent value as empty.
     *
     * @param value the value as it arrived or as the column holds it, which may be {@code null}
     * @return the trimmed value, or the empty string when the value is absent, never {@code null}
     */
    private static String trimmedOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Loads the account master row this update writes.
     *
     * @param accountId the account to load
     * @return the managed row, never {@code null}
     * @throws NoSuchElementException if no such account exists
     */
    private Account loadAccount(long accountId) {
        return this.accounts.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException(
                        "no account master row exists for account " + accountId));
    }

    /**
     * Loads the account's customer master row, through the by-account cross-reference path.
     *
     * <p>Assumptions: the customer is reached through the cross-reference rather than being addressed
     * directly, because that is the path the baseline takes -- its write path moves a customer key it
     * obtained earlier, at {@code app/cbl/COACTUPC.cbl} L3919, and the by-account index the target reads
     * is the migrated form of the alternate index the online programs read as a file.</p>
     *
     * @param accountId the account whose customer is required
     * @return the managed row, never {@code null}
     * @throws NoSuchElementException if the account has no cross-reference row, or the row names a
     *     customer the customer master does not hold
     */
    private Customer loadCustomer(long accountId) {
        return this.crossReferences.findByAccountIdOrderByCardNumAsc(accountId).stream()
                .findFirst()
                .map(row -> row.getCustomerId())
                .flatMap(this.customers::findById)
                .orElseThrow(() -> new NoSuchElementException(
                        "no customer could be resolved for account " + accountId));
    }

    /**
     * Refuses a submission whose precondition does not name the state the rows now hold.
     *
     * <p>Purpose: this is the migrated form of {@code 9700-CHECK-CHANGE-IN-REC} at
     * {@code app/cbl/COACTUPC.cbl} L4109, whose body ends at L4192 and whose exit is L4193. That
     * paragraph compares thirty-odd fields against the before-image and, on any difference, sets the
     * changed-record condition at L4143 or L4189 and transfers to the write path's exit so that nothing
     * is written. The version members on the two rows carry the same question in one comparison each,
     * which is why no field-by-field comparison appears here.</p>
     *
     * <p>Assumptions: a blank precondition is refused rather than read as no opinion. Reading it as
     * permission would make the check opt-in, and a caller that simply omitted the header would get
     * exactly the silent-overwrite behaviour the check exists to remove.</p>
     *
     * <p>Refactoring Rationale: the refusal names the CONDITION and composes no sentence of its own,
     * because the baseline's message-valued condition at L521 and L522 is declared once for the whole
     * migration in the shared error model and the shared advice selects it from the condition. Composing
     * a sentence here would create a second wording for one outcome with no rule for choosing between
     * them, and the baseline's own wording -- whose fourth and fifth words are two words and which ends
     * without a period -- would then have two spellings.</p>
     *
     * @param account the loaded account row; must not be {@code null}
     * @param customer the loaded customer row; must not be {@code null}
     * @param expectedRevision the caller's precondition; must not be {@code null}
     * @throws RecordConflictException if the precondition is blank or names a state the rows have left,
     *     which the shared advice renders as HTTP 409 carrying the baseline's changed-record sentence
     */
    private static void requireCurrentRevision(Account account, Customer customer,
            String expectedRevision) {
        if (expectedRevision.isBlank() || !revisionOf(account, customer).equals(expectedRevision)) {
            throw new RecordConflictException(RecordConflictException.Kind.STALE_VERSION,
                    customer.getVersion());
        }
    }

    /**
     * Renders the revision token for a loaded pair of rows.
     *
     * <p>Trade-offs: one token covers BOTH rows, rendered as the two versions joined, rather than one
     * token per row. The baseline's before-image spans both records and its comparison fails if either
     * changed, so a single token reproduces that. What is accepted in exchange is that a concurrent edit
     * to either row refuses an edit to the other; that is the baseline's behaviour, and the screen edits
     * the two together in any case.</p>
     *
     * @param account the loaded account row; must not be {@code null}
     * @param customer the loaded customer row; must not be {@code null}
     * @return the opaque token, never {@code null}
     */
    private static String revisionOf(Account account, Customer customer) {
        return account.getVersion() + REVISION_SEPARATOR + customer.getVersion();
    }
}
