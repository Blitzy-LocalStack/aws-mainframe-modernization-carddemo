package com.carddemo.account.mapper;

import com.carddemo.account.domain.Account;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.dto.AccountUpdateResponse;
import com.carddemo.account.dto.AccountViewResponse;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.validation.FieldValidationFlag;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Translates the account record between its stored row and the two published account contracts.
 *
 * <p><b>Purpose.</b> This class is the anti-corruption layer for one baseline record, the 300-byte
 * account entity declared at {@code app/cpy/CVACT01Y.cpy} lines 4 to 17. It carries that record in both
 * directions: outward, a stored row becomes the account grouping of the human view; inward, the account
 * region of a submitted update becomes assignments onto a managed row. It is the account-side counterpart
 * of {@link CustomerMapper}, which owns the customer region of the same request, and between them the two
 * cover all forty-three components of {@link AccountUpdateRequest} -- twenty-six customer values there
 * and the seventeen account values here.</p>
 *
 * <h2>Which representation concerns are live in this file, and which is not</h2>
 *
 * <p>The migration's design makes a mapper the single place a copybook's representation concerns may
 * appear, so that everything downstream of it holds clean values. Five of those concerns are live for
 * this record and one is measurably absent:</p>
 *
 * <ul>
 *   <li><b>Fixed widths</b> -- every field of the record and of both screen contracts is declared at an
 *       exact width, and each width is held as a named constant below beside the {@code PICTURE} clause
 *       it came from.</li>
 *   <li><b>Sign overpunch</b> -- reached through the money contract rather than decoded here, for the
 *       reason recorded on {@link #editedMoney}.</li>
 *   <li><b>{@code FILLER}</b> -- 178 bytes of it, dropped, and the drop recorded at
 *       {@link #DROPPED_FILLER_WIDTH}.</li>
 *   <li><b>A misspelling</b> -- exactly one, renamed on the way out at {@link #toAccountDetail}.</li>
 *   <li><b>Masking</b> -- the composite view this class assembles carries two protected customer
 *       identifiers, and they arrive already masked, as {@link #toAccountViewResponse} records.</li>
 * </ul>
 *
 * <p>Assumptions: packed decimal is NOT live here, and the absence is a measured finding rather than an
 * omission. No field of {@code app/cpy/CVACT01Y.cpy} is declared {@code COMP-3}: its five monetary fields
 * at lines 7, 8, 9, 13 and 14 are all zoned {@code PIC S9(10)V99}, and its remaining fields are a display
 * numeric at line 5 and seven alphanumerics. A reader looking for packed-decimal handling should look at
 * the export record and the authorization segments, which are the places it occurs, and not add a decoder
 * here for a format this record does not use.</p>
 *
 * <h2>Why no arithmetic happens in this class</h2>
 *
 * <p>Assumptions: every method below converts a REPRESENTATION and none of them computes a quantity. No
 * amount is added to, subtracted from, multiplied by or divided by another anywhere in this file. That is
 * stated explicitly because a mapper holding five monetary fields is a plausible place to look for the
 * interest formula, and the formula is not here: the migration keeps the multiply-before-divide ordering
 * rule in the batch context that owns the interest job, and the shared money type publishes the divisor it
 * uses. Looking here for it would waste a reader's time; worse, adding a computation here would put an
 * arithmetic decision outside the place the ordering rule is documented and tested.</p>
 *
 * <h2>Why the mapping is written by hand</h2>
 *
 * <p>Alternatives Considered: generating this class with MapStruct, which is the obvious choice for a type
 * whose name ends in {@code Mapper} and which would remove most of the assignment statements below.
 * Rejected on two independent grounds. The first is that the mapping is not mechanical, and the list of
 * fields it treats non-identically is long: it drops the {@code FILLER} at
 * {@code app/cpy/CVACT01Y.cpy} line 17, renames the misspelled expiry field at line 11, converts five
 * monetary fields into a fixed-point type, composes three dates from nine screen parts on the way in and
 * decomposes them on the way out, distinguishes the three genuine dates at lines 10, 11 and 12 from the
 * two fields of identical declared width at lines 15 and 16 that are not dates, and preserves a field the
 * receiving program never writes back. A generated mapper reproduces none of those decisions; it would
 * either map the two non-dates as dates or need an override per field, at which point the generator has
 * saved nothing. The second ground is that each of those sites needs an adjacent justification, and
 * generated code has nowhere to put one -- which would make the documentation obligation unsatisfiable for
 * precisely the fields where it matters most. MapStruct's most recent published release being a beta is a
 * third reason and the weakest of the three.
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} reaches the same conclusion for the migration as a whole at
 * lines 465 to 470, and names the same list of non-identical treatments this record exhibits.</p>
 *
 * <p>Alternatives Considered: Lombok, to remove the accessor and constructor boilerplate this class and
 * its collaborators carry. Rejected because generated members carry no documentation, which
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} records at lines 461 to 464, and because the rejection here
 * is mechanical rather than only conventional: {@code config/checkstyle/checkstyle.xml} sets
 * {@code allowMissingPropertyJavadoc} to false at line 365 and clears {@code allowedAnnotations} entirely
 * at line 366, so no accessor exemption exists for a generated member to fall through. The gate would have
 * to be weakened to admit the dependency, which inverts the trade: the dependency exists to save typing,
 * and the cost would be the mechanical half of the rule the project is held to.</p>
 *
 * <h2>Why this class mutates a row while its sibling constructs one</h2>
 *
 * <p>Assumptions: the difference is forced by the entities and is not a stylistic divergence.
 * {@link com.carddemo.account.domain.Customer} declares no setter, so its mapper constructs a complete
 * row. {@link Account} declares a setter for every updatable member, so this mapper mutates the row the
 * caller already loaded. Mutating the loaded row is what lets the provider's own concurrency counter do
 * its work: the row is managed, the counter was read with it, and the comparison happens at flush.
 * Constructing a detached row here and merging it would carry a counter this class had invented, and the
 * comparison would then be against a value no read had established.</p>
 *
 * <h2>Why the account postal code is never written</h2>
 *
 * <p>Assumptions: {@link Account} carries an {@code addressZip} member and this class deliberately leaves
 * it alone, which is what preserves it. The evidence is that the receiving program never carries it: the
 * before-image block {@code ACUP-OLD-ACCT-DATA} spans {@code app/cbl/COACTUPC.cbl} lines 670 to 708 and
 * holds eleven members -- the identifier, the active status, the three limits and balances, the three
 * dates, the two cycle totals and the group identifier -- with no postal code among them; and the
 * write-back block at lines 3970 to 4002 moves those same values and no postal code either. The update map
 * does have a postal-code field, {@code ACSZIPCI} at {@code app/cpy-bms/COACTUP.CPY} line 246, and it is
 * the CUSTOMER's, which is why {@link AccountUpdateRequest} carries a {@code zipCode} component that
 * {@link CustomerMapper} consumes and this class does not. Writing the account's own copy from this screen
 * would record a value the submission never named.</p>
 *
 * <h2>What this class does not protect, and where the protection lives instead</h2>
 *
 * <p>Trade-offs: nothing in this class encrypts or decrypts an account field, and that is a boundary
 * decision rather than an omission. The record holds no card verification value and no national or
 * government-issued identifier -- those live on the customer record and on the card record -- so there is
 * nothing here to suppress, which is worth stating so a reviewer does not go looking for a cipher that
 * should be present. What the account record needs is protection at rest, and the target supplies that
 * infrastructurally rather than field by field: the baseline defines this file with
 * {@code READINTEG(UNCOMMITTED)} at {@code app/csd/CARDDEMO.CSD} line 3 and {@code RECOVERY(NONE)} at line
 * 9, together with no journalling, so there is no baseline encryption or recovery behaviour to port. The
 * migration therefore encrypts the whole datastore under a managed key and enables automated backups, which
 * is a documented CORRECTION of the baseline's posture and not a translation of it. Encrypting individual
 * columns here on top of that would add a second scheme with its own key handling for no additional
 * confidentiality against the threat the datastore encryption addresses.</p>
 *
 * <p>Trade-offs: primary account numbers are masked to their last four digits wherever this bounded context
 * publishes one, but no such number passes through THIS class -- the account record carries none, and the
 * cross-reference and card contracts are where they appear. The administrative path that deliberately
 * returns an unmasked number belongs to the card context and not to this one, so a reader looking for the
 * exception to the masking rule will not find it here and should not add one.</p>
 *
 * @see CustomerMapper for the twenty-six customer components of the same request
 * @see AccountViewResponse.AccountDetail for the ten published account components
 */
@Component
public class AccountMapper {

    /**
     * The fixed length of the baseline account record in bytes.
     *
     * <p>Assumptions: 300 is declared in the record's own header comment at
     * {@code app/cpy/CVACT01Y.cpy} line 2 and is reachable by addition from the field widths at lines 5
     * to 17, so the two agree and neither is taken on trust.</p>
     */
    public static final int RECORD_LENGTH = 300;

    /**
     * The width of the trailing {@code FILLER} this mapping drops.
     *
     * <p>Assumptions: the 178 bytes declared at {@code app/cpy/CVACT01Y.cpy} line 17 are padding to the
     * fixed record length and carry no value, so no column, no entity member and no published component
     * corresponds to them. The drop is recorded here rather than left implicit because a reader
     * reconciling twelve mapped fields against a 300-byte record needs to see where the remaining bytes
     * went; 122 meaningful bytes plus 178 of padding is the whole record, and the sum is asserted by
     * {@link #MEANINGFUL_FIELD_WIDTH_SUM} so a future edit cannot leave the two disagreeing.</p>
     */
    public static final int DROPPED_FILLER_WIDTH = 178;

    /**
     * The number of record bytes that carry data rather than padding.
     *
     * <p>Assumptions: 122 is the sum of the twelve declared field widths at
     * {@code app/cpy/CVACT01Y.cpy} lines 5 to 16 -- eleven for the identifier, one for the active status,
     * twelve for each of the five zoned amounts, and ten for each of the four alphanumerics that follow
     * the amounts. It is expressed as a subtraction rather than as the literal 122 so that the identity
     * with the record length is checked by the compiler instead of by a reader.</p>
     */
    public static final int MEANINGFUL_FIELD_WIDTH_SUM = RECORD_LENGTH - DROPPED_FILLER_WIDTH;

    /**
     * The message the reference emits when an account filter is not a usable identifier.
     *
     * <p>Assumptions: this is carried across character for character from the literal EMITTED at
     * {@code app/cbl/COACTVWC.cbl} line 672, which is fifty characters long and is reached from the test
     * at lines 666 and 667 by way of the flag set at line 669 and the guard at line 670. Three details of
     * it are deliberate and none may be normalised: there are two consecutive space characters between the
     * third and fourth words, the fifth word is hyphenated, and the second word is {@code Filter}. A
     * search of the whole baseline for the opening of this sentence returns that one line and no other.</p>
     *
     * <p>Trade-offs: this text and the text DECLARED for the two conditions at
     * {@code app/cbl/COACTVWC.cbl} lines 125 to 128 differ, and the difference is preserved rather than
     * reconciled. Those declarations read {@code number} where this reads {@code Filter}, carry a single
     * space and no hyphen, and the same pair of declarations appears again at
     * {@code app/cbl/COACTUPC.cbl} lines 493 to 496. The emitted literal is the one a user sees on this
     * validation failure, so it is the one reproduced here; merging the two into a single string would
     * silently change what the screen says, and choosing the declared form would change it for the one
     * path that actually reaches text.</p>
     */
    public static final String ACCOUNT_FILTER_NOT_NUMERIC =
            "Account Filter must  be a non-zero 11 digit number";

    /**
     * The declared width of the account identifier.
     *
     * <p>Assumptions: eleven on every side, so the rendering is exact rather than approximate.
     * {@code ACCT-ID} is {@code PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy} line 5, {@code ACCTSIDI} is
     * {@code PIC X(11)} at {@code app/cpy-bms/COACTUP.CPY} line 60, and the before-image holds it as
     * {@code PIC X(11)} at {@code app/cbl/COACTUPC.cbl} line 671 over a numeric redefinition at lines 672
     * and 673.</p>
     */
    private static final int ACCOUNT_IDENTIFIER_WIDTH = 11;

    /**
     * The declared width of the active-status flag.
     *
     * <p>Assumptions: one character, {@code ACSTTUSI} {@code PIC X(1)} at
     * {@code app/cpy-bms/COACTUP.CPY} line 66 against {@code ACCT-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT01Y.cpy} line 6.</p>
     */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /**
     * The declared width of the account group identifier.
     *
     * <p>Assumptions: ten on both sides, {@code AADDGRPI} {@code PIC X(10)} at
     * {@code app/cpy-bms/COACTUP.CPY} line 150 against {@code ACCT-GROUP-ID PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy} line 16.</p>
     */
    private static final int GROUP_ID_WIDTH = 10;

    /**
     * The declared width of an amount as the screen carries it.
     *
     * <p>Assumptions: fifteen characters is a RENDERING width and not a value width, which is why this
     * constant is not the precision of the stored column. Every monetary field on both screen contracts is
     * declared alphanumeric at this width -- {@code app/cpy-bms/COACTVW.CPY} lines 78, 90, 102, 108 and
     * 120, and {@code app/cpy-bms/COACTUP.CPY} lines 90, 114, 138, 144 and 156 -- and
     * {@code app/cbl/COACTUPC.cbl} line 370 declares the edit field as {@code PIC X(15)} with its
     * formatted companion at line 371 as {@code PIC +ZZZ,ZZZ,ZZZ.99}, a mask measuring exactly fifteen
     * once its sign, two group separators and decimal point are counted. The value beneath it is the zoned
     * {@code PIC S9(10)V99} of {@code app/cpy/CVACT01Y.cpy}, which occupies twelve bytes.</p>
     */
    private static final int AMOUNT_SCREEN_WIDTH = 15;

    /**
     * The number of digit positions the stored amount carries after its decimal point.
     *
     * <p>Assumptions: two, from the {@code V99} of {@code PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy} lines 7, 8, 9, 13 and 14, and the same two the shared money type
     * publishes as its canonical scale.</p>
     */
    private static final int AMOUNT_SCALE = 2;

    /**
     * The number of digit positions the stored amount carries before its decimal point.
     *
     * <p>Assumptions: ten, from the {@code S9(10)} of the same five clauses. It is passed to the shared
     * money type's picture-bounded factory so that an amount too wide for the baseline field is refused
     * here rather than silently stored and then failing to render back through a fifteen-character
     * mask.</p>
     */
    private static final int AMOUNT_PICTURE_INTEGER_DIGITS = 10;

    /**
     * The declared width of a calendar year part on the update map.
     *
     * <p>Assumptions: four, from {@code OPNYEARI} at {@code app/cpy-bms/COACTUP.CPY} line 72 and its two
     * counterparts at lines 96 and 120.</p>
     */
    private static final int DATE_YEAR_WIDTH = 4;

    /**
     * The declared width of a calendar month part on the update map.
     *
     * <p>Assumptions: two, from {@code OPNMONI} at {@code app/cpy-bms/COACTUP.CPY} line 78 and its two
     * counterparts at lines 102 and 126.</p>
     */
    private static final int DATE_MONTH_WIDTH = 2;

    /**
     * The declared width of a calendar day part on the update map.
     *
     * <p>Assumptions: two, from {@code OPNDAYI} at {@code app/cpy-bms/COACTUP.CPY} line 84 and its two
     * counterparts at lines 108 and 132.</p>
     */
    private static final int DATE_DAY_WIDTH = 2;

    /**
     * The declared width of a date as the master record and the view map hold it.
     *
     * <p>Assumptions: ten, and the ten is what distinguishes the stored form from the before-image form.
     * The three master dates are {@code PIC X(10)} at {@code app/cpy/CVACT01Y.cpy} lines 10, 11 and 12
     * and the three view-map fields match at {@code app/cpy-bms/COACTVW.CPY} lines 72, 84 and 96, whereas
     * the before-image holds each as {@code PIC X(08)} at {@code app/cbl/COACTUPC.cbl} lines 684, 690 and
     * 696. Eight plus the two separators is ten, which is the whole of the asymmetry.</p>
     */
    private static final int STORED_DATE_WIDTH = 10;

    /**
     * The separator a composed date carries between its parts.
     *
     * <p>Assumptions: the same single character the reference's own {@code STRING} statements insert at
     * {@code app/cbl/COACTUPC.cbl} lines 3976 to 3982, 3984 to 3990 and 3994 to 4000, so that a date
     * composed here is byte-identical to the ten characters the reference stores rather than to the
     * eight-character before-image form, which is a real shape in the reference and the wrong one to
     * persist.</p>
     */
    private static final String DATE_PART_SEPARATOR = "-";

    /**
     * The group separator the amount mask emits inside the integer part.
     */
    private static final char AMOUNT_GROUP_SEPARATOR = ',';

    /**
     * The character the amount mask emits ahead of the fraction digits.
     */
    private static final char AMOUNT_DECIMAL_POINT = '.';

    /**
     * The help text every never-supplied entry carries.
     *
     * <p>Trade-offs: one shared sentence rather than a sentence per field, and this deliberately matches
     * the decision {@link CustomerMapper} records for the customer half. The reference attaches no
     * per-field text to a highlighted field at all -- {@code app/cpy/CSSETATY.cpy} moves a colour
     * attribute and a marker character and nothing else -- so there is no baseline literal to carry
     * across, and inventing a field-specific sentence would produce strings a later reader could mistake
     * for migrated text.</p>
     */
    private static final String NEVER_SUPPLIED_HELP =
            "This field is required and no value was supplied.";

    /**
     * Creates the shared, stateless translator this bounded context injects.
     *
     * <p>Assumptions: the class holds no state, so one instance serves every request thread. Translating
     * this record needs no cryptography, no lookup and no clock: every value it publishes is derived from
     * the row or the request handed to it, and the two protected customer identifiers on the composite
     * view arrive already masked from the mapper that owns them.</p>
     *
     * <p>Alternatives Considered: declaring no bean at all and exposing the whole class as static
     * utilities, which for a stateless translation is a defensible shape and is in fact how the
     * value-shape predicates below are exposed. Rejected for the instance methods so that a caller
     * receives this mapper by constructor injection and can be exercised against a substitute without
     * starting a container. That is also the migration's replacement for the baseline's static linkage
     * between programs, so keeping it a bean is the consistent reading rather than a local preference.</p>
     */
    public AccountMapper() {
        // WHY : Assumptions: the body is empty because there is no state to establish. A no-argument
        //       constructor that assigned nothing would be indistinguishable from the implicit default
        //       one, and it is written out anyway so that the two paragraphs above have somewhere to live
        //       at the member they describe rather than in the class charter.
    }

    /**
     * Projects a stored account row onto the ten published account components of the human view.
     *
     * <p>Assumptions: there are exactly TEN components and their order is the order the view map declares
     * its fields, {@code app/cpy-bms/COACTVW.CPY} lines 66 to 120: the active status at line 66, the open
     * date at 72, the credit limit at 78, the expiry date at 84, the cash credit limit at 90, the reissue
     * date at 96, the current balance at 102, the current cycle credit at 108, the group identifier at 114
     * and the current cycle debit at 120. The interleaving looks arbitrary read as a list of fields and is
     * not: it is the top-to-bottom, left-to-right order of the screen, so a client rendering the record in
     * declaration order reproduces the layout without holding a second ordering of its own.</p>
     *
     * <p>Assumptions: FIVE of the ten are monetary and are published as {@link Money} -- the two limits,
     * the balance and the two cycle totals, from {@code app/cpy/CVACT01Y.cpy} lines 7, 8, 9, 13 and 14.
     * The stored columns are already exact fixed point at the scale the contract publishes, so this
     * direction hands each column value to the shared money type and performs no reduction of its own.</p>
     *
     * <p>Refactoring Rationale: the expiry component is published as {@code expirationDate} and stored in a
     * column of that name, whereas the baseline field is spelled {@code ACCT-EXPIRAION-DATE} at
     * {@code app/cpy/CVACT01Y.cpy} line 11 -- the second syllable of the word is absent. This is the ONE
     * renamed field in this record; the migration renames nothing else here. The misspelling is not a
     * transcription slip in a single place but a consistent one, which is why the rename happens at this
     * boundary rather than the spelling being carried through: the reference keeps its own spelling
     * untouched everywhere, and the batch reporting program repeats it at exactly three points,
     * {@code app/cbl/CBACT01C.cbl} lines 64, 207 and 222, the before-image declares
     * {@code ACUP-OLD-EXPIRAION-DATE} at {@code app/cbl/COACTUPC.cbl} line 690, and the view program moves
     * it to the map at {@code app/cbl/COACTVWC.cbl} line 488. Carrying the misspelling forward would put it
     * into a published contract and a column name, where every future consumer would have to reproduce it
     * exactly; correcting it once here confines the old spelling to the reference. The lineage is
     * registered in {@code docs/architecture/data-model-and-schema-mapping.md} so the correspondence is
     * never ambiguous.</p>
     *
     * <p>Alternatives Considered: not writing this projection at all, because
     * {@link AccountContextMapper} carries a projection of the same record onto the same record type and
     * the current services reach for that one. Rejected, and the overlap is stated here rather than left
     * for a reader to discover: this class is the anti-corruption layer for the account record and owns
     * both of its directions, so the outward direction belongs with the inward one and with the field-level
     * justifications that explain them. The two projections are deliberately identical in result -- both
     * build the same ten components in the same order -- so either may be injected, and a future
     * consolidation onto one of them changes no observable behaviour. What is not acceptable is two
     * projections that DISAGREE, so any change to the component order or to the money handling must be made
     * in both or in neither.</p>
     *
     * @param row the stored account row to project, an {@link Account}; must not be {@code null}
     * @return the ten published account components as an {@link AccountViewResponse.AccountDetail}, never
     *     {@code null}
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws ArithmeticException if a stored amount lies outside the domain the shared money type admits,
     *     which for a column declared at the baseline's own precision indicates a row written past that
     *     precision rather than a caller defect
     */
    public AccountViewResponse.AccountDetail toAccountDetail(Account row) {
        Objects.requireNonNull(row, "row must not be null");

        // WHY : Assumptions: the three dates are rendered as their stored ten-character text and NOT
        //       reformatted. The columns hold year-month-day in that order, which is the order the
        //       baseline stores at app/cpy/CVACT01Y.cpy lines 10, 11 and 12 and the order the view map
        //       expects at app/cpy-bms/COACTVW.CPY lines 72, 84 and 96, so the rendering is the stored
        //       form rather than a presentation choice. That ordering is also why the column type is a
        //       date at all: a lexical comparison of these ten characters and a calendar comparison of
        //       the same two values reach the same verdict, so nothing the baseline could express about
        //       them is lost by storing them as dates.
        return new AccountViewResponse.AccountDetail(
                row.getActiveStatus(),
                storedDateText(row.getOpenDate()),
                publishedAmount(row.getCreditLimit(), "creditLimit"),
                storedDateText(row.getExpirationDate()),
                publishedAmount(row.getCashCreditLimit(), "cashCreditLimit"),
                storedDateText(row.getReissueDate()),
                publishedAmount(row.getCurrentBalance(), "currentBalance"),
                publishedAmount(row.getCurrentCycleCredit(), "currentCycleCredit"),
                row.getGroupId(),
                publishedAmount(row.getCurrentCycleDebit(), "currentCycleDebit"));
    }

    /**
     * Assembles the composite account view from a stored row and an already-projected customer detail.
     *
     * <p>Assumptions: the customer half is a PARAMETER rather than something this method derives, because
     * {@link CustomerMapper} owns the eighteen customer components and the two protected identifiers among
     * them are masked there. Deriving them here would put the masking decision in a second place, and two
     * places that mask can disagree about what a masked value looks like.</p>
     *
     * <p>Assumptions: the account's own postal code is deliberately NOT among the components this view
     * publishes, and its absence is the view map's own. {@code app/cpy/CVACT01Y.cpy} line 15 declares
     * {@code ACCT-ADDR-ZIP}, yet the ten account fields of {@code app/cpy-bms/COACTVW.CPY} at lines 66 to
     * 120 contain no postal code; the postal code the view screen shows is the customer's, which is why it
     * appears among the customer components and not here. Publishing the account's copy as well would put
     * two postal codes on one screen with nothing to tell a reader which is which.</p>
     *
     * <p>Trade-offs: the two message channels are parameters and are neither defaulted nor validated here.
     * The reference keeps them at different widths and treats an unset channel as meaningful -- an
     * informational channel of forty characters at {@code app/cbl/COACTVWC.cbl} line 110 and an aggregate
     * return channel of seventy-five at line 117, the latter with an explicit unset condition at line 118
     * -- so a caller that has nothing to say passes nothing, and this method preserves that rather than
     * substituting text of its own.</p>
     *
     * @param row the stored account row to project, an {@link Account}; must not be {@code null}
     * @param customer the customer half of the view, an {@link AccountViewResponse.CustomerDetail} whose
     *     protected identifiers arrive already masked; may be {@code null} when a lookup reached no
     *     customer
     * @param informationMessage the informational-channel {@code String}, carried through exactly as
     *     supplied; may be {@code null} when the channel is unset
     * @param returnMessage the aggregate-message {@code String}, carried through exactly as supplied; may
     *     be {@code null} when the channel is unset
     * @return the assembled {@link AccountViewResponse}, never {@code null}
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws ArithmeticException if a stored amount lies outside the domain the shared money type admits
     */
    public AccountViewResponse toAccountViewResponse(Account row,
            AccountViewResponse.CustomerDetail customer, String informationMessage,
            String returnMessage) {
        Objects.requireNonNull(row, "row must not be null");

        return new AccountViewResponse(
                accountIdentifierDigits(row.getAccountId()),
                toAccountDetail(row),
                customer,
                informationMessage,
                returnMessage);
    }

    /**
     * Assembles the dual-channel update response from committed state, one aggregate message and the
     * per-field errors.
     *
     * <p>Assumptions: the response carries BOTH channels at once and they answer different questions. The
     * aggregate message is the single latched sentence the reference holds in
     * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTUPC.cbl} line 479, of which the program can
     * show exactly one at a time; the per-field array is the target's form of the reference's per-field
     * highlighting, of which any number can be active together. Collapsing the two into one channel would
     * lose whichever distinction the caller had drawn.</p>
     *
     * <p>Assumptions: the array is built on {@link ApiError.FieldError}, the shared per-field entry type,
     * and is never a locally declared shape. Its source contract is the reference's tri-state validation
     * flag: {@code app/cbl/COACTVWC.cbl} line 58 declares {@code WS-EDIT-ACCT-FLAG PIC X(1)} with three
     * conditions at lines 59, 60 and 61 -- not acceptable, valid, and blank -- and a second parallel
     * tri-state for the customer filter follows at lines 62 to 65. Three states rather than a boolean is
     * the whole point: blank is distinguishable from wrong, and the rendering template acts on that
     * distinction.</p>
     *
     * <p>Refactoring Rationale: the reference renders those flags through the templated fragment at
     * {@code app/cpy/CSSETATY.cpy} lines 18 to 27, which moves a red attribute onto a field that is either
     * not acceptable or blank, and additionally moves a literal asterisk into a field that is blank. Two
     * things happen to that template here. The asterisk marker SURVIVES -- the shared entry type publishes
     * it, so a blank field is still marked as the reference marks it. What does not survive is the
     * conjunct at line 20, {@code AND CDEMO-PGM-REENTER}, which gates the whole highlight on the
     * pseudo-conversational re-entry discriminator declared at {@code app/cpy/COCOM01Y.cpy} line 29 with
     * its two conditions at lines 30 and 31. A stateless handler has no first-entry-versus-re-entry
     * distinction to make, because it never holds a previous turn, so there is no flag to test; field-error
     * rendering is therefore driven purely by what the response body carries. Keeping the conjunct would
     * have required inventing server-side turn state for no purpose other than to reproduce a condition
     * that only existed to serve it.</p>
     *
     * @param row the committed account row to echo, an {@link Account}; must not be {@code null}
     * @param customer the committed customer half, an {@link AccountViewResponse.CustomerDetail} whose
     *     protected identifiers arrive already masked; may be {@code null} when no customer was reached
     * @param returnMessage the single latched aggregate-message {@code String} for the seventy-five
     *     character channel, carried through exactly as supplied; may be {@code null} when the channel is
     *     unset
     * @param fieldErrors the {@code List} of {@link ApiError.FieldError} entries to publish, empty when
     *     every field was acceptable; must not be {@code null}
     * @return the assembled {@link AccountUpdateResponse}, never {@code null}
     * @throws NullPointerException if {@code row} or {@code fieldErrors} is {@code null}, the latter
     *     because the response contract seals the array and refuses an absent one
     * @throws ArithmeticException if a stored amount lies outside the domain the shared money type admits
     */
    public AccountUpdateResponse toAccountUpdateResponse(Account row,
            AccountViewResponse.CustomerDetail customer, String returnMessage,
            List<ApiError.FieldError> fieldErrors) {
        Objects.requireNonNull(row, "row must not be null");
        Objects.requireNonNull(fieldErrors, "fieldErrors must not be null");

        // WHY : Trade-offs: the informational channel is passed as absent on this path rather than given
        //       a sentence of its own. The reference's update program declares no informational-channel
        //       counterpart to the two the view program carries at app/cbl/COACTVWC.cbl lines 113 to 116,
        //       so there is no migrated text to place there, and inventing one would put a string in a
        //       published response that no baseline line supports.
        return new AccountUpdateResponse(
                accountIdentifierDigits(row.getAccountId()),
                null,
                returnMessage,
                fieldErrors,
                toAccountDetail(row),
                customer);
    }

    /**
     * Assembles the update response that reports the row was changed by another writer.
     *
     * <p>Refactoring Rationale: the baseline already implements optimistic concurrency by hand across the
     * pseudo-conversational gap, and this method is the reporting half of the target's replacement for it.
     * The reference snapshots the entire pre-edit record into {@code ACUP-OLD-DETAILS} at
     * {@code app/cbl/COACTUPC.cbl} line 669, a block running to line 756 with {@code ACUP-NEW-DETAILS}
     * beginning at line 757, and latches the outcome in a message-valued condition declared at lines 521
     * and 522. That whole apparatus exists because the file lock was never held across the user's thinking
     * time. The target keeps the behaviour and drops the apparatus: {@link Account} carries a version
     * member the provider compares at flush, so the before-image copy of eleven fields is unnecessary and
     * the comparison is made against a value a read actually established.</p>
     *
     * <p>Assumptions: the sentence itself comes from {@link ApiError#COACTUPC_RECORD_CHANGED}, which
     * declares it once for the whole migration, and is NOT respelled here. Its two-word spelling of the
     * fourth and fifth words and its absent terminating period are properties of the reference literal at
     * {@code app/cbl/COACTUPC.cbl} line 522, and one declaration is what keeps a response body and a log
     * line from disagreeing about them.</p>
     *
     * <p>Trade-offs: this method reports a conflict and does not DETECT or translate one. No handler for
     * the provider's optimistic-lock failure is declared in this class, because
     * {@link com.carddemo.common.error.GlobalExceptionHandler} already maps that failure to the conflict
     * status carrying this same sentence. Declaring a second mapping here would put one behaviour in two
     * places that could then disagree; what this class owns is placing the text into the seventy-five
     * character channel when a caller has decided to answer with a body rather than to let the failure
     * propagate.</p>
     *
     * @param row the account row as currently stored, an {@link Account}, so the caller can show the
     *     values that were found; must not be {@code null}
     * @param customer the customer half as currently stored, an
     *     {@link AccountViewResponse.CustomerDetail} whose protected identifiers arrive already masked;
     *     may be {@code null} when no customer was reached
     * @return the assembled {@link AccountUpdateResponse} carrying the reference's changed-record sentence
     *     in its aggregate channel and no per-field entries, never {@code null}
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws ArithmeticException if a stored amount lies outside the domain the shared money type admits
     */
    public AccountUpdateResponse toConflictResponse(Account row,
            AccountViewResponse.CustomerDetail customer) {

        // WHY : Assumptions: the per-field array is EMPTY rather than carrying an entry per field. A
        //       concurrent write is not a fault in any particular submitted value -- every field may have
        //       been acceptable -- so highlighting fields would tell the user to correct values that are
        //       not wrong. The reference reaches the same shape: the condition at app/cbl/COACTUPC.cbl
        //       lines 521 and 522 sets the aggregate channel and no field attribute.
        return toAccountUpdateResponse(row, customer, ApiError.COACTUPC_RECORD_CHANGED, List.of());
    }

    /**
     * Applies the account region of a submitted update onto the row the caller loaded.
     *
     * <p>Assumptions: the caller has already decided the submission is acceptable. This method converts
     * and assigns; it reaches no verdict and produces no message, because the verdicts belong to the
     * service layer that owns the reference's edit routines. A value that reaches this method and cannot be
     * converted is nevertheless reported as a per-field failure rather than as an opaque one, for the
     * reason recorded on {@link #editedMoney}.</p>
     *
     * <p>Assumptions: the account identifier is NOT assigned. It is the row's key, the row was loaded by
     * it, and the reference's rewrite does not move it either -- {@code app/cbl/COACTUPC.cbl} writes the
     * record it read, so the key is whatever the read established. Assigning it here would let a submission
     * relocate a row.</p>
     *
     * <p>Assumptions: the account's own postal code is NOT assigned either, and that omission is what
     * PRESERVES it. Because this method mutates the managed row the caller loaded, a field left unassigned
     * keeps the value the read brought back; the alternative reading -- that an absent request component
     * means "make it empty" -- would write a blank over a stored postal code on every update. The reference
     * settles which reading is right twice over: {@code ACCT-ADDR-ZIP} is absent from the before-image
     * block at {@code app/cbl/COACTUPC.cbl} lines 670 to 708, so the program does not even remember its
     * prior value, and it is absent from the write-back block at lines 3970 to 4002, so the program does
     * not write it. A submission therefore cannot express a change to it, and
     * {@link AccountUpdateRequest} correspondingly carries no component for it.</p>
     *
     * @param row the managed account row to mutate, an {@link Account}; must not be {@code null}
     * @param request the submitted update whose account region supplies every value assigned, an
     *     {@link AccountUpdateRequest}; must not be {@code null}
     * @throws NullPointerException if {@code row} or {@code request} is {@code null}
     * @throws ClientInputException if a value was never supplied where the reference requires one, is not
     *     the shape the screen mask emits, is wider than the field it is stored in, or names a day that
     *     does not exist; the shared advice renders it as one entry per offending component
     * @throws IllegalArgumentException as the parent of the above, since
     *     {@link ClientInputException} extends it and a caller may catch either
     */
    public void applyUpdate(Account row, AccountUpdateRequest request) {
        Objects.requireNonNull(row, "row must not be null");
        Objects.requireNonNull(request, "request must not be null");

        row.setActiveStatus(
                requiredExactWidth(request.activeStatus(), ACTIVE_STATUS_WIDTH, "activeStatus"));
        row.setCreditLimit(storedAmount(request.creditLimit(), "creditLimit"));
        row.setCashCreditLimit(storedAmount(request.cashCreditLimit(), "cashCreditLimit"));
        row.setCurrentBalance(storedAmount(request.currentBalance(), "currentBalance"));
        row.setCurrentCycleCredit(storedAmount(request.currentCycleCredit(), "currentCycleCredit"));
        row.setCurrentCycleDebit(storedAmount(request.currentCycleDebit(), "currentCycleDebit"));

        // WHY : Refactoring Rationale: each date is COMPOSED from three separate screen components rather
        //       than read from one, because the update map genuinely carries three: a four-character year,
        //       a two-character month and a two-character day, at app/cpy-bms/COACTUP.CPY lines 72, 78 and
        //       84 for the open date and at lines 96 to 108 and 120 to 132 for the other two. The
        //       decomposition is the baseline's deliberate choice and not an accident of the screen: on the
        //       way IN the program takes substrings at app/cbl/COACTUPC.cbl lines 3832 to 3834, 3837 to
        //       3839 and 3843 to 3845, using one-based offsets of (1:4), (6:2) and (9:2) that skip
        //       positions five and eight -- the two separators -- and the whole-field moves that would
        //       have avoided the substrings sit COMMENTED OUT immediately above each triple, at lines 3831,
        //       3836 and 3842. Those three commented lines are the proof: the author wrote the simple form,
        //       rejected it, and kept the three-part one, because the before-image field is eight
        //       characters at lines 684, 690 and 696 while the master field is ten.
        //       Assumptions: the target composes to a calendar date rather than to text, so the day is
        //       validated by the calendar as a side effect of being represented. Storing the composed text
        //       and validating separately would admit a month of 02 with a day of 30, which passes every
        //       per-part range check and names no day.
        row.setOpenDate(composedDate(request.openDateYear(), request.openDateMonth(),
                request.openDateDay(), "openDate"));
        row.setExpirationDate(composedDate(request.expirationDateYear(),
                request.expirationDateMonth(), request.expirationDateDay(), "expirationDate"));
        row.setReissueDate(composedDate(request.reissueDateYear(), request.reissueDateMonth(),
                request.reissueDateDay(), "reissueDate"));

        // WHY : Trade-offs: the group identifier is the one account value the reference NEVER EDITS. It
        //       declares no validation flag for it and reaches no edit routine with it: AADDGRPI appears in
        //       app/cbl/COACTUPC.cbl at exactly three places -- L1213 and L1214 fold the marker character
        //       and spaces into the never-supplied state, and L1217 moves the value on -- and nowhere in
        //       the edit chain at L1429 to L1678. So a submission that never supplied it is accepted rather
        //       than refused, because refusing it would decline a request the reference accepts.
        //
        // WHY : Assumptions: an absent group is stored as BLANKS and not as null, and the column decides
        //       that rather than taste. group_id is CHAR(10) NOT NULL and the entity's setter refuses null
        //       outright, so null is not an available representation of absence here. Blanks are also the
        //       authoritative one: the reference folds an absent group into low values at L1213 and L1214
        //       and writes the field regardless, the shipped account seeds carry a blank group, and the
        //       interest calculation's DEFAULT disclosure-group fallback is the live behaviour that a blank
        //       group reaches. Storing a literal 'DEFAULT' here instead would record a group the submission
        //       did not name and would bypass the fallback rather than trigger it.
        row.setGroupId(FieldValidationFlag.isNeverSupplied(request.groupId())
                ? " ".repeat(GROUP_ID_WIDTH)
                : atMostWidth(request.groupId(), GROUP_ID_WIDTH, "groupId"));
    }

    /**
     * Reports which account inputs of an update request were never supplied.
     *
     * <p>Assumptions: this reports the NEVER-SUPPLIED state only and never a value-domain rule, which is
     * the same division {@link CustomerMapper} draws for the customer half. Whether an amount is the shape
     * the mask emits, whether a status is one of the two admitted letters and whether a composed date names
     * a real day are decisions of the service layer; this class owns representation. Never-supplied belongs
     * here because it IS a representation fact: the reference spells it in pad characters and folds both a
     * marker character and a field of spaces into low values before any rule runs, and the shared
     * never-supplied test is the single implementation of that fold.</p>
     *
     * <p>Assumptions: the group identifier is absent from the report, for the reason recorded on
     * {@link #applyUpdate}: the reference edits it optionally, so reporting it would refuse a submission
     * the reference accepts. The account's own postal code is absent for a different reason -- the request
     * carries no component for it at all, so there is nothing to examine. Every other account component is
     * required.</p>
     *
     * @param request the submitted update whose account region is examined, an
     *     {@link AccountUpdateRequest}; must not be {@code null}
     * @return an unmodifiable {@code List} of {@link ApiError.FieldError} holding one entry per required
     *     account field that was never supplied, in the reference's normalisation order, and empty when
     *     every required field arrived; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public List<ApiError.FieldError> accountFieldErrors(AccountUpdateRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // WHY : Assumptions: insertion order is preserved deliberately, because the order the fields are
        //       reported in is the reference's normalisation order and a client rendering the array top to
        //       bottom then highlights the screen in the order the screen reads.
        Map<String, String> required = new LinkedHashMap<>();
        required.put("accountId", request.accountId());
        required.put("activeStatus", request.activeStatus());
        required.put("creditLimit", request.creditLimit());
        required.put("cashCreditLimit", request.cashCreditLimit());
        required.put("currentBalance", request.currentBalance());
        required.put("currentCycleCredit", request.currentCycleCredit());
        required.put("currentCycleDebit", request.currentCycleDebit());
        required.put("openDateYear", request.openDateYear());
        required.put("openDateMonth", request.openDateMonth());
        required.put("openDateDay", request.openDateDay());
        required.put("expirationDateYear", request.expirationDateYear());
        required.put("expirationDateMonth", request.expirationDateMonth());
        required.put("expirationDateDay", request.expirationDateDay());
        required.put("reissueDateYear", request.reissueDateYear());
        required.put("reissueDateMonth", request.reissueDateMonth());
        required.put("reissueDateDay", request.reissueDateDay());

        List<ApiError.FieldError> errors = new ArrayList<>();
        for (Map.Entry<String, String> field : required.entrySet()) {
            if (FieldValidationFlag.isNeverSupplied(field.getValue())) {
                errors.add(new ApiError.FieldError(field.getKey(), FieldValidationFlag.BLANK,
                        NEVER_SUPPLIED_HELP));
            }
        }
        return List.copyOf(errors);
    }

    /**
     * Renders the submitted request back to its sender with both protected identifiers withheld.
     *
     * <p>Assumptions: the echo is built from the SUBMITTED values rather than re-derived from the stored
     * rows, and the masking is what makes that the right choice. The response contract already accepts that
     * a masked echo cannot be resubmitted as it stands, so a client changing one of the two identifiers
     * supplies it again; re-deriving the other forty-one values from the rows would add a whole outbound
     * conversion path whose only observable difference from the submission is the padding the stored columns
     * apply.</p>
     *
     * <p>Assumptions: this is the only place the account context masks these two values on this path, and
     * it is in the mapper package because that is where the response contract states masking is applied --
     * once, before any value reaches the record. The two identifiers are the national identifier, carried in
     * three components, and the government-issued identifier.</p>
     *
     * <p>Trade-offs: the withheld marker is the constant {@link CustomerMapper#IDENTIFIER_REDACTED} rather
     * than a second spelling declared here. Two spellings of one withholding would let a response body and
     * a log line disagree about how a withheld value looks, and the sibling mapper's constant is already the
     * one the entity's own diagnostic form renders.</p>
     *
     * @param request the submitted update to echo, an {@link AccountUpdateRequest}; must not be
     *     {@code null}
     * @return the same forty-three components as an {@link AccountUpdateRequest} with the four identifier
     *     components replaced by the withheld marker, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public AccountUpdateRequest toEcho(AccountUpdateRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        return new AccountUpdateRequest(request.accountId(), request.activeStatus(),
                request.creditLimit(), request.cashCreditLimit(), request.currentBalance(),
                request.currentCycleCredit(), request.currentCycleDebit(), request.openDateYear(),
                request.openDateMonth(), request.openDateDay(), request.expirationDateYear(),
                request.expirationDateMonth(), request.expirationDateDay(),
                request.reissueDateYear(), request.reissueDateMonth(), request.reissueDateDay(),
                request.groupId(), request.customerId(),
                CustomerMapper.IDENTIFIER_REDACTED, CustomerMapper.IDENTIFIER_REDACTED,
                CustomerMapper.IDENTIFIER_REDACTED,
                request.dateOfBirthYear(), request.dateOfBirthMonth(), request.dateOfBirthDay(),
                request.ficoCreditScore(), request.firstName(), request.middleName(),
                request.lastName(), request.addressLine1(), request.addressLine2(), request.city(),
                request.stateCode(), request.countryCode(), request.zipCode(),
                request.phone1AreaCode(), request.phone1Prefix(), request.phone1LineNumber(),
                request.phone2AreaCode(), request.phone2Prefix(), request.phone2LineNumber(),
                CustomerMapper.IDENTIFIER_REDACTED, request.eftAccountId(),
                request.primaryCardHolderIndicator());
    }

    /**
     * Answers whether a screen value is the shape the amount mask emits.
     *
     * <p>Assumptions: this is the target's form of the reference's numeric test. The reference reaches
     * {@code FUNCTION TEST-NUMVAL-C} at {@code app/cbl/COACTUPC.cbl} L2201 and treats a non-zero result as
     * "not valid", so the predicate below decides the same verdict the reference decides, leaving the
     * MESSAGE to the caller that owns the field's label.</p>
     *
     * <p>Trade-offs: the accepted grammar is deliberately NARROWER than the intrinsic function's.
     * {@code TEST-NUMVAL-C} also accepts a currency sign, a {@code CR} or {@code DB} credit indicator and a
     * parenthesised negative. None of those can arrive from this screen: the field is rendered by
     * {@code PIC +ZZZ,ZZZ,ZZZ.99} at {@code app/cbl/COACTUPC.cbl} L371, a mask that emits a sign, group
     * separators and a decimal point and nothing else, so a value carrying any of the other forms was never
     * produced by the map. Accepting them would store an amount this mask could not render back, which is a
     * worse outcome than refusing an input the screen cannot generate.</p>
     *
     * @param screenValue the value as the screen carries it, a {@code String}; may be {@code null}
     * @return {@code true} when the value can be converted to a stored amount, {@code false} when it never
     *     was supplied or is not the shape the mask emits
     */
    public static boolean isEditedAmount(String screenValue) {
        if (FieldValidationFlag.isNeverSupplied(screenValue)) {
            return false;
        }
        String trimmed = screenValue.trim();
        if (trimmed.length() > AMOUNT_SCREEN_WIDTH) {
            return false;
        }
        return normalisedAmount(trimmed) != null;
    }

    /**
     * Converts a screen value into the shared fixed-point money type.
     *
     * <p><b>This method is the conversion point the whole money contract turns on.</b>
     * {@link AccountUpdateRequest} declares every one of its forty-three components as text, including the
     * five amounts, and that is deliberate on its part: a malformed amount arriving as text reaches
     * validation and can be reported against the field it came from, whereas an amount declared as a number
     * on the request would fail during deserialisation, before any field-level machinery exists, and the
     * caller would be told that a body could not be read rather than which amount was wrong. The
     * consequence lands here: because the request refuses to convert, this class must, and it must convert
     * in a way that keeps the per-field channel intact.</p>
     *
     * <p>Trade-offs: the failure is therefore raised as {@link ClientInputException} carrying the field
     * name, not as a bare unchecked failure. That is the compromise this method accepts -- a conversion
     * helper takes a field name it does not otherwise need, and every caller has to supply one -- and it is
     * what buys the property the request record was shaped for. The shared advice renders that exception as
     * one entry per offending component, so a malformed amount arrives at the client as a per-field error
     * exactly as a never-supplied one does. Raising an unnamed failure instead would collapse five
     * distinguishable amounts into one indistinguishable rejection.</p>
     *
     * <p>Assumptions: the baseline itself treats these values as CHARACTERS on the wire and as numbers only
     * in arithmetic, so converting at this boundary reproduces its own division of labour rather than
     * imposing one. Three separate pieces of evidence say so. Every monetary field on both screen contracts
     * is declared alphanumeric at fifteen characters -- {@code app/cpy-bms/COACTVW.CPY} lines 78, 90, 102,
     * 108 and 120 and {@code app/cpy-bms/COACTUP.CPY} lines 90, 114, 138, 144 and 156. The before-image
     * holds each amount as text over a numeric redefinition, most plainly
     * {@code ACUP-OLD-CURR-BAL PIC X(12)} at {@code app/cbl/COACTUPC.cbl} line 675 redefined at lines 676
     * and 677 as {@code PIC S9(10)V99}, and identically for the credit limit at line 678 over 679 and 680,
     * the cash credit limit at 681 over 682 and 683, the two cycle totals at 702 over 703 and 704 and at 705
     * over 706 and 707, and the eleven-digit identifier at 671 over 672 and 673. And the stored form is
     * zoned decimal with a sign overpunch, which the project's own build notes at
     * {@code tests/README.md} lines 273 and 274 record as requiring the reference sign convention because
     * the default one misreads the overpunch and silently corrupts negative balances. That last point is
     * why sign handling belongs to the shared kernel's zoned codec and is never re-implemented here: a
     * second decoder is a second chance to get the overpunch wrong, and the failure mode is silent.</p>
     *
     * <p>Alternatives Considered: publishing these amounts as JSON numbers, which is the obvious encoding
     * and the one a reader would expect. Rejected because most clients parse a JSON number into an
     * IEEE-754 binary floating-point value, and that representation cannot hold every two-place decimal
     * exactly, so a balance would arrive at the boundary the user actually reads having already lost
     * exactness -- while looking entirely plausible. The contract therefore carries money as a string and
     * the shared serialiser enforces it, which is the one encoding that survives an arbitrary client
     * unchanged.</p>
     *
     * <p>Assumptions: no arithmetic is performed here. The value is re-represented and bounded, never
     * combined with another value.</p>
     *
     * @param screenValue the value as the screen carries it, a {@code String}; must be an accepted shape
     * @param field the component name of type {@code String} used in a failure, so a rejection names the
     *     component it came from; must not be {@code null}
     * @return the amount as a {@link Money} at the contract's canonical scale, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws ClientInputException if the value was never supplied, is not the shape the mask emits, or
     *     declares more integer digits than the baseline field admits
     * @throws IllegalArgumentException as the parent of the above, since {@link ClientInputException}
     *     extends it
     */
    public static Money editedMoney(String screenValue, String field) {
        BigDecimal exact = storedAmount(screenValue, field);

        // WHY : Assumptions: the picture-bounded factory is used rather than the plain one, so that an
        //       amount wider than the reference field is refused at the boundary instead of being stored
        //       and later failing to render back through a fifteen-character mask. The bound is the
        //       S9(10) of app/cpy/CVACT01Y.cpy lines 7, 8, 9, 13 and 14.
        //       Trade-offs: the overflow the factory raises is an arithmetic failure, which would escape
        //       the per-field channel and reach the caller as an internal fault. It is caught and
        //       re-raised as a per-field rejection because the value came from a screen field and an
        //       over-wide amount is a caller's input mistake, not a defect in this service.
        try {
            return Money.ofPicture(exact, AMOUNT_PICTURE_INTEGER_DIGITS);
        } catch (ArithmeticException tooWide) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, field, FieldValidationFlag.NOT_OK,
                    "the supplied value of " + field + " declares more than "
                            + AMOUNT_PICTURE_INTEGER_DIGITS
                            + " digits before its decimal point, which the reference field cannot hold");
        }
    }

    /**
     * Converts a screen value to the exact amount the column stores.
     *
     * <p>Assumptions: the result carries the stored scale exactly, so an amount typed without a fraction and
     * the same amount typed with one reach the column identically. Exact fixed point is used throughout and
     * no IEEE-754 binary floating-point type appears on this path at any point.</p>
     *
     * <p>Trade-offs: the reduction to the stored scale is requested with the rounding mode that REFUSES to
     * round rather than with the contract's general one. That looks inconsistent with the shared money
     * type, which rounds half away from zero, and it is deliberate: rounding is correct where a computation
     * has produced more precision than the column holds, whereas here the digits came from a screen field
     * whose mask emits exactly two fraction positions, so a third digit means the value did not come from
     * that mask. Refusing is how that is surfaced instead of quietly absorbed.</p>
     *
     * @param screenValue the value as the screen carries it, a {@code String}; must be an accepted shape
     * @param field the component name of type {@code String} used in a failure, so a rejection names the
     *     component it came from; must not be {@code null}
     * @return the amount as a {@link BigDecimal} at the stored scale, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws ClientInputException if the value was never supplied or is not the shape the mask emits
     * @throws IllegalArgumentException as the parent of the above, since {@link ClientInputException}
     *     extends it
     */
    public static BigDecimal storedAmount(String screenValue, String field) {
        Objects.requireNonNull(field, "field must not be null");
        if (FieldValidationFlag.isNeverSupplied(screenValue)) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, field, FieldValidationFlag.BLANK,
                    "no value was supplied for " + field + ", which the reference requires");
        }
        String plain = normalisedAmount(screenValue.trim());
        if (plain == null) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    FieldValidationFlag.NOT_OK, "the supplied value of " + field
                            + " is not the shape the reference's amount mask emits");
        }
        return new BigDecimal(plain).setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * Renders a stored account identifier as the digits-only text the contracts publish.
     *
     * <p>Assumptions: the identifier is published as text and not as a number, and the two screen contracts
     * disagree about that in a way the target has to settle. {@code app/cpy-bms/COACTVW.CPY} line 60
     * declares {@code ACCTSIDI} as an eleven-position display NUMERIC, whereas
     * {@code app/cpy-bms/COACTUP.CPY} line 60 declares the same-named field as {@code PIC X(11)},
     * ALPHANUMERIC. The stricter of the two is taken -- digits-only text at exactly eleven characters --
     * because it satisfies both readings at once: it is acceptable everywhere the alphanumeric field is
     * acceptable, and it carries only the characters the numeric field would admit. The before-image agrees,
     * holding the identifier as {@code PIC X(11)} at {@code app/cbl/COACTUPC.cbl} line 671 with a numeric
     * redefinition at lines 672 and 673, which is the same text-over-number pairing the five amounts
     * use.</p>
     *
     * <p>Trade-offs: leading zeros are preserved by padding on the left, which means the published value is
     * eleven characters even for a small identifier. That is accepted because the baseline field is an
     * unsigned display numeric in which the value is right-justified within its declared width; padding on
     * the right would multiply the identifier by a power of ten as far as any consumer reading it as a
     * number is concerned, and trimming the zeros would publish a value that no longer matches the declared
     * width of eleven.</p>
     *
     * @param accountId the stored identifier of type {@link Long}; must not be {@code null}
     * @return the zero-padded digits-only rendering as a {@code String}, exactly eleven characters long,
     *     never {@code null}
     * @throws IllegalStateException if {@code accountId} is {@code null}, because the column behind it is
     *     declared not-null, or if it needs more than eleven digits, since truncating it would publish a
     *     different identifier
     */
    public static String accountIdentifierDigits(Long accountId) {
        if (accountId == null) {
            throw new IllegalStateException("accountId is null, but it is declared NOT NULL in the"
                    + " schema and nullable = false on the entity");
        }
        String digits = String.valueOf(accountId.longValue());
        if (digits.length() > ACCOUNT_IDENTIFIER_WIDTH) {
            throw new IllegalStateException("accountId occupies " + digits.length()
                    + " digits but the reference field declares " + ACCOUNT_IDENTIFIER_WIDTH
                    + "; truncating it would publish a different identifier");
        }
        return "0".repeat(ACCOUNT_IDENTIFIER_WIDTH - digits.length()) + digits;
    }

    /**
     * Answers whether three calendar parts compose a day that exists.
     *
     * <p>Assumptions: the parts are tested by COMPOSING them and letting the calendar reject an impossible
     * day, rather than by range-checking each part separately. A month of 02 and a day of 30 passes every
     * per-part range yet names no day, and the reference's own date routine reaches the same verdict by
     * calling out to the shared date utility rather than by comparing parts.</p>
     *
     * @param year the four-character year part, a {@code String}; may be {@code null}
     * @param month the two-character month part, a {@code String}; may be {@code null}
     * @param day the two-character day part, a {@code String}; may be {@code null}
     * @return {@code true} when the three parts compose an existing day, {@code false} when any part was
     *     never supplied, is not all digits, is not its declared width, or the three name no day
     */
    public static boolean isComposableDate(String year, String month, String day) {
        if (FieldValidationFlag.isNeverSupplied(year)
                || FieldValidationFlag.isNeverSupplied(month)
                || FieldValidationFlag.isNeverSupplied(day)) {
            return false;
        }
        if (!isAllDigits(year.trim()) || !isAllDigits(month.trim()) || !isAllDigits(day.trim())) {
            return false;
        }
        if (year.trim().length() != DATE_YEAR_WIDTH
                || month.trim().length() != DATE_MONTH_WIDTH
                || day.trim().length() != DATE_DAY_WIDTH) {
            return false;
        }
        try {
            LocalDate.parse(composedText(year.trim(), month.trim(), day.trim()));
            return true;
        } catch (DateTimeParseException impossible) {
            return false;
        }
    }

    /**
     * Composes three calendar parts into the stored date, for a caller comparing against a column.
     *
     * <p>Assumptions: this is published so that a caller deciding whether a submission CHANGED a stored date
     * can compare a date against a date rather than text against text. Comparing the texts would report a
     * change whenever the screen spelled a value differently from the way the column renders it, which is
     * the mistake the reference avoids by comparing its numeric redefinitions.</p>
     *
     * @param year the four-character year part, a {@code String}; may be {@code null}
     * @param month the two-character month part, a {@code String}; may be {@code null}
     * @param day the two-character day part, a {@code String}; may be {@code null}
     * @return the composed {@link LocalDate}, never {@code null}
     * @throws ClientInputException if the three parts do not compose a day that exists
     * @throws IllegalArgumentException as the parent of the above, since {@link ClientInputException}
     *     extends it
     */
    public static LocalDate storedDate(String year, String month, String day) {
        return composedDate(year, month, day, "date");
    }

    /**
     * Renders a stored date as the ten-character text the master record and the view map hold.
     *
     * <p>Refactoring Rationale: this is the OUTWARD half of the date mechanic, and it is a separate decision
     * from the inward half rather than its mirror image, because the reference performs the two differently.
     * Inward, the program takes three substrings that SKIP the separators. Outward, it puts the separators
     * back explicitly, with {@code STRING} statements that interleave a literal hyphen between the parts and
     * are delimited by size: {@code app/cbl/COACTUPC.cbl} lines 3976 to 3982 for the open date, 3984 to 3990
     * for the expiry date and 3994 to 4000 for the reissue date. So the hyphens are not decoration this
     * method has chosen to add -- they are the stored form, and the program spends three statements
     * reconstructing them.</p>
     *
     * <p>Assumptions: the ten-character result is the MASTER form and not the before-image form, and the two
     * are genuinely different shapes in the reference. The master fields are {@code PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy} lines 10, 11 and 12; the before-image fields are {@code PIC X(08)} at
     * {@code app/cbl/COACTUPC.cbl} lines 684, 690 and 696, each redefined into a four-character year and two
     * two-character parts at lines 685 to 689, 691 to 695 and 697 to 701. Eight characters plus the two
     * separators is ten. Emitting the eight-character form here would publish the shape the reference keeps
     * only in working storage and never persists.</p>
     *
     * <p>Trade-offs: the calendar type's own text form is used rather than a constructed formatter. One
     * formatter is one more place the pattern is written and one more chance for two spellings of it to
     * diverge; the calendar type already emits exactly the year-month-day form at the width
     * {@link #STORED_DATE_WIDTH} names, for every year a four-position year field can express. The cost of
     * relying on it is that it does NOT emit that width for a year outside four positions -- it prefixes a
     * sign and widens instead -- so the width is checked rather than assumed.</p>
     *
     * @param value the stored date, a {@link LocalDate}; may be {@code null} when the column holds nothing
     * @return the ten-character text as a {@code String}, or {@code null} when the date is absent
     * @throws IllegalStateException if the date renders at any width other than the declared ten, which for
     *     this calendar type means a year outside the four positions the reference field holds
     */
    public static String storedDateText(LocalDate value) {
        // WHY : Assumptions: an absent date renders as absent and NOT as blanks or as a zero date. The
        //       reissue date is the one of the three a row can legitimately lack, and collapsing absent
        //       into blank would make an unissued account indistinguishable from one whose date failed to
        //       load -- a distinction a reader of the view has no other way to recover.
        if (value == null) {
            return null;
        }

        String text = value.toString();

        // WHY : Assumptions: the width is verified rather than trusted, because this calendar type widens
        //       its own text form outside the year range the reference field can express -- a year past
        //       four positions gains a sign prefix and extra digits. A value of that shape would be
        //       published into a ten-character contract and stored into a ten-byte field, so it is refused
        //       here where the field width is known rather than truncated downstream where it is not.
        if (text.length() != STORED_DATE_WIDTH) {
            throw new IllegalStateException("a stored date rendered " + text.length()
                    + " characters but the reference field declares " + STORED_DATE_WIDTH
                    + "; the year lies outside the four positions the field can express");
        }
        return text;
    }

    /**
     * Decomposes a stored date into the four-character year part the update map carries.
     *
     * <p>Refactoring Rationale: the three part-wise renderings exist because the update map genuinely has
     * three fields per date, at {@code app/cpy-bms/COACTUP.CPY} lines 72, 78 and 84 and at the two
     * corresponding triples, so a caller pre-filling that screen from a stored row needs the parts and not
     * the composed text. The reference reaches the same three values by substring at
     * {@code app/cbl/COACTUPC.cbl} lines 3832 to 3834 and its two counterparts; taking them from the
     * calendar type instead removes the offset arithmetic, which is where an off-by-one would otherwise
     * live.</p>
     *
     * @param value the stored date, a {@link LocalDate}; must not be {@code null}
     * @return the four-character year as a {@code String}, zero-padded so a year below 1000 still occupies
     *     its declared width, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}, because a part of an absent date names
     *     nothing a screen field could hold
     */
    public static String dateYearPart(LocalDate value) {
        Objects.requireNonNull(value, "value must not be null");
        return datePart(value.getYear(), DATE_YEAR_WIDTH);
    }

    /**
     * Decomposes a stored date into the two-character month part the update map carries.
     *
     * <p>Refactoring Rationale: this exists for the reason recorded on {@link #dateYearPart} -- the update
     * map declares three fields per date rather than one, so a caller pre-filling it from a stored row needs
     * the parts. The month is taken from the calendar type rather than by substring, which is what removes
     * the one-based offset of {@code (6:2)} that {@code app/cbl/COACTUPC.cbl} line 3833 uses and with it the
     * chance of reading the separator instead of the digits.</p>
     *
     * @param value the stored date, a {@link LocalDate}; must not be {@code null}
     * @return the two-character month as a {@code String}, zero-padded so a single-digit month still
     *     occupies its declared width, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}, because a part of an absent date names
     *     nothing a screen field could hold
     */
    public static String dateMonthPart(LocalDate value) {
        Objects.requireNonNull(value, "value must not be null");
        return datePart(value.getMonthValue(), DATE_MONTH_WIDTH);
    }

    /**
     * Decomposes a stored date into the two-character day part the update map carries.
     *
     * <p>Refactoring Rationale: as for {@link #dateMonthPart}, and the day is the part where the substring
     * approach is most fragile. The reference reads it at {@code (9:2)} at {@code app/cbl/COACTUPC.cbl}
     * line 3834, an offset that is correct only while the two separators sit at positions five and eight;
     * taking the day from the calendar type makes the value independent of where the separators fall.</p>
     *
     * @param value the stored date, a {@link LocalDate}; must not be {@code null}
     * @return the two-character day as a {@code String}, zero-padded so a single-digit day still occupies
     *     its declared width, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}, because a part of an absent date names
     *     nothing a screen field could hold
     */
    public static String dateDayPart(LocalDate value) {
        Objects.requireNonNull(value, "value must not be null");
        return datePart(value.getDayOfMonth(), DATE_DAY_WIDTH);
    }

    /**
     * Answers whether a field of the account record that is ten characters wide is one of the three dates.
     *
     * <p>Assumptions: this predicate exists because the record contains FIVE fields declared
     * {@code PIC X(10)} and only THREE of them are dates, which is the single most consequential fact about
     * mapping this record. The three that are dates are {@code ACCT-OPEN-DATE} at
     * {@code app/cpy/CVACT01Y.cpy} line 10, {@code ACCT-EXPIRAION-DATE} at line 11 and
     * {@code ACCT-REISSUE-DATE} at line 12. The two that are NOT are {@code ACCT-ADDR-ZIP} at line 15,
     * which is a postal code, and {@code ACCT-GROUP-ID} at line 16, which is a group identifier. Any
     * statement that this record has four ten-character date fields is superseded by the lines themselves;
     * narrowing either of the last two to a calendar date would be silent data loss, because a postal code
     * and a group code are not parseable as days and the values would be rejected or, worse, coerced.
     * {@link Account} is built on the same reading: it declares exactly three {@link LocalDate} members and
     * holds the postal code and the group identifier as text.</p>
     *
     * <p>Trade-offs: a predicate over field names is a weak mechanism -- it cannot fail a build the way a
     * type can -- and it is provided anyway so that a caller iterating the record's ten-character fields has
     * one place to consult rather than a repeated three-way comparison. The strong mechanism is the entity's
     * own member types, which make the distinction unrepresentable; this method is for code that works from
     * field names, such as a fixed-width reader or a diagnostic.</p>
     *
     * @param baselineFieldName the record field name of type {@code String}, spelled as the copybook
     *     spells it, including the reference's own spelling of the expiry field; may be {@code null}
     * @return {@code true} for the three date fields at lines 10, 11 and 12, {@code false} for every other
     *     name including the two ten-character non-dates at lines 15 and 16
     */
    public static boolean isTenCharacterDateField(String baselineFieldName) {
        return "ACCT-OPEN-DATE".equals(baselineFieldName)
                || "ACCT-EXPIRAION-DATE".equals(baselineFieldName)
                || "ACCT-REISSUE-DATE".equals(baselineFieldName);
    }

    /**
     * Publishes a stored column amount as the shared fixed-point money type.
     *
     * <p>Assumptions: the stored column is already exact fixed point at the contract's scale, because it is
     * declared with the precision and scale the baseline's {@code PIC S9(10)V99} implies, so this direction
     * bounds the value and does not reduce it. Reduction belongs to the inbound direction, where the digits
     * came from a screen field.</p>
     *
     * <p>Trade-offs: a failure here is reported as an arithmetic fault naming the component rather than as
     * a per-field input rejection, which is the opposite of the choice {@link #editedMoney} makes. The
     * asymmetry is deliberate: an out-of-domain value on THIS path did not come from a caller, it came from
     * a row, so it is a data or schema fault and telling a client to correct a field would misdirect them.
     * The component name is still included so the fault names which of the five amounts was at fault.</p>
     *
     * @param stored the column value of type {@link BigDecimal}; must not be {@code null}
     * @param field the component name of type {@code String} used in a failure, so a fault names which
     *     amount it came from; must not be {@code null}
     * @return the amount as a {@link Money} at the contract's canonical scale, never {@code null}
     * @throws IllegalStateException if {@code stored} is {@code null}, because every monetary column of this
     *     record is declared not-null
     * @throws ArithmeticException if the stored value lies outside the domain the shared money type admits,
     *     which indicates a row written past the precision the reference field declares
     */
    private static Money publishedAmount(BigDecimal stored, String field) {
        if (stored == null) {
            throw new IllegalStateException(field + " is null, but it is declared NOT NULL in the"
                    + " schema and nullable = false on the entity");
        }
        try {
            return Money.of(stored);
        } catch (ArithmeticException outOfDomain) {
            throw new ArithmeticException("the stored value of " + field
                    + " lies outside the domain the money contract admits: "
                    + outOfDomain.getMessage());
        }
    }

    /**
     * Renders one calendar component as zero-padded digits at its declared width.
     *
     * <p>Assumptions: padding is applied on the left because each screen part is a fixed-width numeric field
     * in which the value is right-justified, so a single-digit month occupies both positions as {@code 0} and
     * the digit. Padding on the right would move the digit into the tens position and change the value.</p>
     *
     * @param component the calendar component as an {@code int}, a year, a month number or a day of month
     * @param width the declared width of the screen part as an {@code int}, four for a year and two for a
     *     month or a day
     * @return the zero-padded digits as a {@code String}, exactly {@code width} characters long, never
     *     {@code null}
     * @throws IllegalStateException if the component needs more digits than the declared width admits, which
     *     for a date the calendar accepted means a year beyond the four positions the screen field holds
     */
    private static String datePart(int component, int width) {
        String digits = Integer.toString(component);
        if (digits.length() > width) {
            throw new IllegalStateException("a calendar component of " + digits
                    + " needs " + digits.length() + " digits but the reference field declares " + width
                    + "; truncating it would name a different date");
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Reduces an accepted screen amount to a plain decimal, or reports that it is not one.
     *
     * <p>Assumptions: the reduction removes the mask's decoration rather than interpreting it, so the digits
     * reaching the exact decimal type are the digits the user typed. A trailing sign is moved to the front
     * because the mask can emit either position and the decimal type accepts only the leading one.</p>
     *
     * @param value the trimmed screen value, a {@code String}; must not be {@code null}
     * @return the plain decimal form as a {@code String}, or {@code null} when the value is not an accepted
     *     shape
     */
    private static String normalisedAmount(String value) {
        String body = value;
        String sign = "";
        if (!body.isEmpty() && (body.charAt(0) == '+' || body.charAt(0) == '-')) {
            sign = body.charAt(0) == '-' ? "-" : "";
            body = body.substring(1);
        } else if (!body.isEmpty()) {
            char last = body.charAt(body.length() - 1);
            if (last == '+' || last == '-') {
                sign = last == '-' ? "-" : "";
                body = body.substring(0, body.length() - 1);
            }
        }
        if (body.isEmpty()) {
            return null;
        }

        int pointAt = body.indexOf(AMOUNT_DECIMAL_POINT);
        String integerPart = pointAt < 0 ? body : body.substring(0, pointAt);
        String fractionPart = pointAt < 0 ? "" : body.substring(pointAt + 1);
        if (fractionPart.indexOf(AMOUNT_DECIMAL_POINT) >= 0 || fractionPart.length() > AMOUNT_SCALE) {
            return null;
        }

        // WHY : Assumptions: group separators are accepted only in the INTEGER part and their POSITIONS
        //   are not checked, which matches the intrinsic function the reference calls: it accepts a
        //   comma as decoration wherever the integer part allows one and does not require groups of
        //   three. Enforcing three-digit grouping here would refuse values the reference accepts.
        String integerDigits = integerPart.replace(String.valueOf(AMOUNT_GROUP_SEPARATOR), "");
        if (integerDigits.isEmpty() && fractionPart.isEmpty()) {
            return null;
        }
        if (!isAllDigits(integerDigits) || !isAllDigits(fractionPart)) {
            return null;
        }

        String padded = fractionPart.isEmpty()
                ? "0".repeat(AMOUNT_SCALE)
                : fractionPart + "0".repeat(AMOUNT_SCALE - fractionPart.length());
        String whole = integerDigits.isEmpty() ? "0" : integerDigits;
        return sign + whole + AMOUNT_DECIMAL_POINT + padded;
    }

    /**
     * Answers whether every character of a value is a digit.
     *
     * <p>Assumptions: an EMPTY value answers {@code true}, and callers depend on that rather than merely
     * tolerating it. {@link #normalisedAmount} asks this question about a fraction part that is legitimately
     * absent when the screen carried no decimal point, so an empty part must not be reported as
     * non-numeric. The alternative reading -- that empty is not all digits -- would refuse every whole
     * amount the mask emits without a fraction.</p>
     *
     * <p>Trade-offs: the test is a character loop rather than a regular expression or the character class
     * the platform offers. The loop is used because the platform's own digit test accepts digits from every
     * script the character set defines, whereas the reference field admits only the ten it declares; a
     * value carrying a non-Latin digit would pass that test and then fail to parse or, worse, parse to
     * something the screen could not render back.</p>
     *
     * @param value the value to test, a {@code String}; must not be {@code null}
     * @return {@code true} when the value holds only digits, including when it is empty
     */
    private static boolean isAllDigits(String value) {
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Composes three calendar parts into the stored date.
     *
     * @param year the four-character year part, a {@code String}; may be {@code null}
     * @param month the two-character month part, a {@code String}; may be {@code null}
     * @param day the two-character day part, a {@code String}; may be {@code null}
     * @param field the component group name of type {@code String} used in a failure, so a rejection names
     *     which of the three dates was at fault; must not be {@code null}
     * @return the composed {@link LocalDate}, never {@code null}
     * @throws ClientInputException if the three parts do not compose a day that exists
     * @throws IllegalArgumentException as the parent of the above, since {@link ClientInputException}
     *     extends it
     */
    private static LocalDate composedDate(String year, String month, String day, String field) {
        if (!isComposableDate(year, month, day)) {
            // WHY : Trade-offs: one rejection covers all three parts of the date rather than one per part,
            //       and it names the date rather than the part. An impossible day is a property of the
            //       COMBINATION -- the thirtieth of February has no single part at fault -- so attributing
            //       it to the day field would tell the user to change the one value that might well be the
            //       one they meant.
            throw new ClientInputException(ApiError.CODE_VALIDATION, field, FieldValidationFlag.NOT_OK,
                    "the supplied parts of " + field + " do not compose a date the calendar admits");
        }
        return LocalDate.parse(composedText(year.trim(), month.trim(), day.trim()));
    }

    /**
     * Joins three calendar parts with the separator the reference inserts.
     *
     * <p>Assumptions: this validates NOTHING, and the omission is deliberate rather than an oversight. It is
     * reached only from {@link #isComposableDate}, which has already established that each part is all
     * digits at its declared width, and from {@link #composedDate}, which reaches it only after that same
     * predicate has answered. Re-checking here would run the same three width tests a second time on every
     * composition and would place a second, independently maintained copy of the width rules where the two
     * could drift apart.</p>
     *
     * <p>Trade-offs: the parts are concatenated rather than passed through a formatter. That is the same
     * shape the reference uses -- its {@code STRING} statements at {@code app/cbl/COACTUPC.cbl} lines 3976
     * to 3982 interleave a literal separator between the parts and nothing more -- so the composed text is
     * produced the way the reference produces it, and no pattern string exists here to disagree with the
     * one the calendar type uses when it renders the value back.</p>
     *
     * @param year the four-character year part, a {@code String}; must not be {@code null}
     * @param month the two-character month part, a {@code String}; must not be {@code null}
     * @param day the two-character day part, a {@code String}; must not be {@code null}
     * @return the composed form as a {@code String}, {@link #STORED_DATE_WIDTH} characters long for parts
     *     at their declared widths, never {@code null}
     */
    private static String composedText(String year, String month, String day) {
        return year + DATE_PART_SEPARATOR + month + DATE_PART_SEPARATOR + day;
    }

    /**
     * Returns a required value padded to the width its {@code PICTURE} clause declares.
     *
     * <p>Assumptions: padding is applied on the RIGHT with spaces here, which is the OPPOSITE of
     * {@link #accountIdentifierDigits}, and the two are not inconsistent -- the difference follows the
     * declared category of the field. This method serves alphanumeric fields, and an alphanumeric
     * {@code PIC X(n)} is left-justified and space-filled, so a one-character status stored into a wider
     * field carries the character first and spaces after. The identifier is an unsigned display numeric,
     * which is right-justified and zero-filled. Applying one rule to both would either left-pad a status
     * with spaces, changing where a consumer reading fixed offsets finds it, or right-pad an identifier with
     * zeros, multiplying it by a power of ten.</p>
     *
     * <p>Trade-offs: the value is PADDED rather than stored at whatever width it arrived with. That costs a
     * comparison and an allocation on a value that is usually already the right width, and it is accepted
     * because the stored column is fixed width: a value short of it would be padded by the datastore
     * anyway, and padding here means the row this mapper produces and the row a later read returns carry
     * the same characters, so a caller comparing them to detect a change does not see one that is only
     * padding.</p>
     *
     * @param value the submitted value, a {@code String}; may be {@code null}
     * @param width the declared width as an {@code int}
     * @param field the component name of type {@code String} used in a failure; must not be {@code null}
     * @return the value as a {@code String} at exactly the declared width, never {@code null}
     * @throws ClientInputException if the value was never supplied or exceeds the declared width
     * @throws IllegalArgumentException as the parent of the above, since {@link ClientInputException}
     *     extends it
     */
    private static String requiredExactWidth(String value, int width, String field) {
        if (FieldValidationFlag.isNeverSupplied(value)) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, field, FieldValidationFlag.BLANK,
                    "no value was supplied for " + field + ", which the reference requires");
        }
        String bounded = atMostWidth(value, width, field);
        return bounded + " ".repeat(width - bounded.length());
    }

    /**
     * Returns a value that fits the width its {@code PICTURE} clause declares.
     *
     * @param value the submitted value, a {@code String}; must not be {@code null}
     * @param width the declared width as an {@code int}
     * @param field the component name of type {@code String} used in a failure; must not be {@code null}
     * @return the value unchanged as a {@code String}, never {@code null}
     * @throws ClientInputException if the value exceeds the declared width
     * @throws IllegalArgumentException as the parent of the above, since {@link ClientInputException}
     *     extends it
     */
    private static String atMostWidth(String value, int width, String field) {
        if (value.length() > width) {
            // WHY : Trade-offs: an over-wide value is REFUSED rather than truncated to fit. Truncating
            //       would store a value the submission does not carry and would do so silently, which for
            //       an identifier or a group code means storing a different one; refusing costs the caller
            //       a round trip and keeps the stored value one the caller actually sent.
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    FieldValidationFlag.NOT_OK, "the supplied value of " + field + " occupies "
                            + value.length() + " characters but the reference field declares " + width
                            + "; truncating it would store a value the submission does not carry");
        }
        return value;
    }
}
