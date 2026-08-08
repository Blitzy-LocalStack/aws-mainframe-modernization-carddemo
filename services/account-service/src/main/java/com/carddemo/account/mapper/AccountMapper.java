package com.carddemo.account.mapper;

import com.carddemo.account.domain.Account;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.common.error.ApiError;
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
 * Translates the account region of the update request onto the stored account row, and back.
 *
 * <p><b>Purpose.</b> This is the account-side counterpart of {@link CustomerMapper}, which already owns
 * the customer region of the same request. Between them the two cover all forty-three components of
 * {@link AccountUpdateRequest}: twenty-six are customer values and are converted there, and the
 * seventeen converted here are the account identifier, the active status, the five amounts, the nine
 * calendar parts of the three account dates and the group identifier.</p>
 *
 * <p>Assumptions: the split is the baseline's own, not a convenience. {@code app/cbl/COACTUPC.cbl}
 * updates two records through two separate rewrites, and the fields it moves into each are exactly the
 * two groups above. A single mapper spanning both would have to be told which record each field belongs
 * to; two mappers each own one record and the question does not arise.</p>
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
 * <p>Assumptions: {@link Account} carries an {@code addressZip} member and this class deliberately
 * leaves it alone. The update map has one postal-code field, {@code ACSZIPCI} at
 * {@code app/cpy-bms/COACTUP.CPY} L246, and it is the CUSTOMER's: the account record's own
 * {@code ACCT-ADDR-ZIP} at {@code app/cpy/CVACT01Y.cpy} L15 appears nowhere in the receiving program, so
 * the baseline never writes it from this screen. Writing it here would make the account row disagree
 * with the customer row after an update the baseline applied to one of them only.</p>
 *
 * @see CustomerMapper for the twenty-six customer components of the same request
 */
@Component
public class AccountMapper {

    /**
     * The declared width of the account identifier on the update map.
     *
     * <p>Assumptions: eleven on both sides, so the composition is exact rather than approximate.
     * {@code ACCTSIDI} is {@code PIC X(11)} at {@code app/cpy-bms/COACTUP.CPY} L60 and
     * {@code ACCT-ID} is {@code PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy} L5.</p>
     */
    private static final int ACCOUNT_IDENTIFIER_WIDTH = 11;

    /**
     * The declared width of the active-status flag.
     *
     * <p>Assumptions: one character, {@code ACSTTUSI} {@code PIC X(1)} at
     * {@code app/cpy-bms/COACTUP.CPY} L66 against {@code ACCT-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT01Y.cpy} L6.</p>
     */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /**
     * The declared width of the account group identifier.
     *
     * <p>Assumptions: ten on both sides, {@code AADDGRPI} {@code PIC X(10)} at
     * {@code app/cpy-bms/COACTUP.CPY} L150 against {@code ACCT-GROUP-ID PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy} L16.</p>
     */
    private static final int GROUP_ID_WIDTH = 10;

    /**
     * The declared width of an amount as the screen carries it.
     *
     * <p>Assumptions: fifteen characters is a RENDERING width and not a value width, which is why this
     * constant is not the precision of the stored column. {@code app/cbl/COACTUPC.cbl} L370 declares the
     * edit field as {@code PIC X(15)} and L371 its formatted companion as
     * {@code PIC +ZZZ,ZZZ,ZZZ.99}, a mask measuring exactly fifteen once its sign, two group separators
     * and decimal point are counted. The value beneath it is the zoned {@code PIC S9(10)V99} of
     * {@code app/cpy/CVACT01Y.cpy}, which occupies twelve bytes.</p>
     */
    private static final int AMOUNT_SCREEN_WIDTH = 15;

    /**
     * The number of digit positions the stored amount carries after its decimal point.
     */
    private static final int AMOUNT_SCALE = 2;

    /**
     * The declared width of a calendar year part.
     */
    private static final int DATE_YEAR_WIDTH = 4;

    /**
     * The declared width of a calendar month part.
     */
    private static final int DATE_MONTH_WIDTH = 2;

    /**
     * The declared width of a calendar day part.
     */
    private static final int DATE_DAY_WIDTH = 2;

    /**
     * The separator a composed date carries between its parts.
     *
     * <p>Assumptions: the same single character the reference's own {@code STRING} statements insert, so
     * that a date composed here is byte-identical to the ten characters the reference stores rather than
     * to the eight-character before-image form, which is a real shape in the reference and the wrong one
     * to persist.</p>
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
     * Applies the account region of a submitted update onto the row the caller loaded.
     *
     * <p>Assumptions: the caller has already decided the submission is acceptable. This method converts
     * and assigns; it reaches no verdict and produces no message, because the verdicts belong to the
     * service layer that owns the reference's edit routines. A value that reaches this method and cannot
     * be converted is therefore a caller defect rather than a user error, which is why the failures
     * below are unchecked.</p>
     *
     * <p>Assumptions: the account identifier is NOT assigned. It is the row's key, the row was loaded by
     * it, and the reference's rewrite does not move it either -- {@code app/cbl/COACTUPC.cbl} writes the
     * record it read, so the key is whatever the read established. Assigning it here would let a
     * submission relocate a row.</p>
     *
     * @param row the managed account row to mutate; must not be {@code null}
     * @param request the submitted update whose account region supplies every value assigned; must not
     *     be {@code null}
     * @throws NullPointerException if {@code row} or {@code request} is {@code null}
     * @throws IllegalArgumentException if a value was never supplied where the reference requires one,
     *     is not the shape the screen mask emits, or names a day that does not exist
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
        row.setOpenDate(composedDate(request.openDateYear(), request.openDateMonth(),
                request.openDateDay(), "openDate"));
        row.setExpirationDate(composedDate(request.expirationDateYear(),
                request.expirationDateMonth(), request.expirationDateDay(), "expirationDate"));
        row.setReissueDate(composedDate(request.reissueDateYear(), request.reissueDateMonth(),
                request.reissueDateDay(), "reissueDate"));

        // WHY : Trade-offs: the group identifier is the one account value the reference NEVER EDITS. It
        //   declares no validation flag for it and reaches no edit routine with it: AADDGRPI appears in
        //   app/cbl/COACTUPC.cbl at exactly three places -- L1213 and L1214 fold the marker character and
        //   spaces into the never-supplied state, and L1217 moves the value on -- and nowhere in the edit
        //   chain at L1429 to L1678. So a submission that never supplied it is accepted rather than
        //   refused, because refusing it would decline a request the reference accepts.
        //
        // WHY : Assumptions: an absent group is stored as BLANKS and not as null, and the column decides
        //   that rather than taste. group_id is CHAR(10) NOT NULL and the entity's setter refuses null
        //   outright, so null is not an available representation of absence here. Blanks are also the
        //   authoritative one: the reference folds an absent group into low values at L1213 and L1214 and
        //   writes the field regardless, the shipped account seeds carry a blank group, and the interest
        //   calculation's DEFAULT disclosure-group fallback is the live behaviour that a blank group
        //   reaches. Storing a literal 'DEFAULT' here instead would record a group the submission did not
        //   name and would bypass the fallback rather than trigger it.
        row.setGroupId(FieldValidationFlag.isNeverSupplied(request.groupId())
                ? " ".repeat(GROUP_ID_WIDTH)
                : atMostWidth(request.groupId(), GROUP_ID_WIDTH, "groupId"));
    }

    /**
     * Reports which account inputs of an update request were never supplied.
     *
     * <p>Assumptions: this reports the NEVER-SUPPLIED state only and never a value-domain rule, which is
     * the same division {@link CustomerMapper} draws for the customer half. Whether an amount is the
     * shape the mask emits, whether a status is one of the two admitted letters and whether a composed
     * date names a real day are decisions of the service layer; this class owns representation.
     * Never-supplied belongs here because it IS a representation fact: the reference spells it in pad
     * characters and folds both a marker character and a field of spaces into low values before any rule
     * runs, and the shared never-supplied test is the single implementation of that fold.</p>
     *
     * <p>Assumptions: the group identifier is absent from the report, for the reason recorded on
     * {@link #applyUpdate}: the reference edits it optionally, so reporting it would refuse a submission
     * the reference accepts. Every other account component is required.</p>
     *
     * @param request the submitted update whose account region is examined; must not be {@code null}
     * @return an unmodifiable list holding one entry per required account field that was never supplied,
     *     in the reference's normalisation order, and empty when every required field arrived; never
     *     {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public List<ApiError.FieldError> accountFieldErrors(AccountUpdateRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // WHY : Assumptions: insertion order is preserved deliberately, because the order the fields are
        //   reported in is the reference's normalisation order and a client rendering the array top to
        //   bottom then highlights the screen in the order the screen reads.
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
     * rows, and the masking is what makes that the right choice. The response contract already accepts
     * that a masked echo cannot be resubmitted as it stands, so a client changing one of the two
     * identifiers supplies it again; re-deriving the other forty-one values from the rows would add a
     * whole outbound conversion path whose only observable difference from the submission is the padding
     * the stored columns apply.</p>
     *
     * <p>Assumptions: this is the only place the account context masks these two values on this path, and
     * it is in the mapper package because that is where the response contract states masking is applied
     * -- once, before any value reaches the record. The two identifiers are the national identifier,
     * carried in three components, and the government-issued identifier.</p>
     *
     * <p>Trade-offs: the withheld marker is the constant {@link CustomerMapper#IDENTIFIER_REDACTED}
     * rather than a second spelling declared here. Two spellings of one withholding would let a response
     * body and a log line disagree about how a withheld value looks, and the sibling mapper's constant
     * is already the one the entity's own diagnostic form renders.</p>
     *
     * @param request the submitted update to echo; must not be {@code null}
     * @return the same forty-three components with the four identifier components replaced by the
     *     withheld marker, never {@code null}
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
     * {@code FUNCTION TEST-NUMVAL-C} at {@code app/cbl/COACTUPC.cbl} L2201 and treats a non-zero result
     * as "not valid", so the predicate below decides the same verdict the reference decides, leaving the
     * MESSAGE to the caller that owns the field's label.</p>
     *
     * <p>Trade-offs: the accepted grammar is deliberately NARROWER than the intrinsic function's.
     * {@code TEST-NUMVAL-C} also accepts a currency sign, a {@code CR} or {@code DB} credit indicator and
     * a parenthesised negative. None of those can arrive from this screen: the field is rendered by
     * {@code PIC +ZZZ,ZZZ,ZZZ.99} at {@code app/cbl/COACTUPC.cbl} L371, a mask that emits a sign, group
     * separators and a decimal point and nothing else, so a value carrying any of the other forms was
     * never produced by the map. Accepting them would store an amount this mask could not render back,
     * which is a worse outcome than refusing an input the screen cannot generate.</p>
     *
     * @param screenValue the value as the screen carries it; may be {@code null}
     * @return {@code true} when the value can be converted to a stored amount, {@code false} when it
     *     never was supplied or is not the shape the mask emits
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
     * Converts a screen value to the exact amount the column stores.
     *
     * <p>Assumptions: the result carries the stored scale exactly, so an amount typed without a fraction
     * and the same amount typed with one reach the column identically. Exact fixed point is used
     * throughout and no binary floating-point type appears on this path at any point.</p>
     *
     * @param screenValue the value as the screen carries it; must be an accepted shape
     * @param field the component name used in a failure, so a caller defect names the field it came
     *     from; must not be {@code null}
     * @return the amount at the stored scale, never {@code null}
     * @throws IllegalArgumentException if the value was never supplied or is not the shape the mask emits
     */
    public static BigDecimal storedAmount(String screenValue, String field) {
        Objects.requireNonNull(field, "field must not be null");
        if (FieldValidationFlag.isNeverSupplied(screenValue)) {
            throw new IllegalArgumentException(
                    "no value was supplied for " + field + ", which the reference requires");
        }
        String plain = normalisedAmount(screenValue.trim());
        if (plain == null) {
            throw new IllegalArgumentException("the supplied value of " + field
                    + " is not the shape the reference's amount mask emits");
        }
        return new BigDecimal(plain).setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * Reduces an accepted screen amount to a plain decimal, or reports that it is not one.
     *
     * <p>Assumptions: the reduction removes the mask's decoration rather than interpreting it, so the
     * digits reaching the exact decimal type are the digits the user typed. A trailing sign is moved to
     * the front because the mask can emit either position and the decimal type accepts only the leading
     * one.</p>
     *
     * @param value the trimmed screen value; must not be {@code null}
     * @return the plain decimal form, or {@code null} when the value is not an accepted shape
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
     * @param value the value to test; must not be {@code null}
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
     * Answers whether three calendar parts compose a day that exists.
     *
     * <p>Assumptions: the parts are tested by COMPOSING them and letting the calendar reject an
     * impossible day, rather than by range-checking each part separately. A month of 02 and a day of 30
     * passes every per-part range yet names no day, and the reference's own date routine reaches the same
     * verdict by calling out to the shared date utility rather than by comparing parts.</p>
     *
     * @param year the four-character year part; may be {@code null}
     * @param month the two-character month part; may be {@code null}
     * @param day the two-character day part; may be {@code null}
     * @return {@code true} when the three parts compose an existing day
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
     * <p>Assumptions: this is published so that a caller deciding whether a submission CHANGED a stored
     * date can compare a date against a date rather than text against text. Comparing the texts would
     * report a change whenever the screen spelled a value differently from the way the column renders
     * it, which is the mistake the reference avoids by comparing its numeric redefinitions.</p>
     *
     * @param year the four-character year part; may be {@code null}
     * @param month the two-character month part; may be {@code null}
     * @param day the two-character day part; may be {@code null}
     * @return the composed date, never {@code null}
     * @throws IllegalArgumentException if the three parts do not compose a day that exists
     */
    public static LocalDate storedDate(String year, String month, String day) {
        return composedDate(year, month, day, "date");
    }

    /**
     * Composes three calendar parts into the stored date.
     *
     * @param year the four-character year part
     * @param month the two-character month part
     * @param day the two-character day part
     * @param field the component group name used in a failure; must not be {@code null}
     * @return the composed date, never {@code null}
     * @throws IllegalArgumentException if the three parts do not compose a day that exists
     */
    private static LocalDate composedDate(String year, String month, String day, String field) {
        if (!isComposableDate(year, month, day)) {
            throw new IllegalArgumentException("the supplied parts of " + field
                    + " do not compose a date the calendar admits");
        }
        return LocalDate.parse(composedText(year.trim(), month.trim(), day.trim()));
    }

    /**
     * Joins three calendar parts with the separator the reference inserts.
     *
     * @param year the four-character year part; must not be {@code null}
     * @param month the two-character month part; must not be {@code null}
     * @param day the two-character day part; must not be {@code null}
     * @return the ten-character composed form, never {@code null}
     */
    private static String composedText(String year, String month, String day) {
        return year + DATE_PART_SEPARATOR + month + DATE_PART_SEPARATOR + day;
    }

    /**
     * Returns a required value padded to the width its {@code PICTURE} clause declares.
     *
     * @param value the submitted value; may be {@code null}
     * @param width the declared width
     * @param field the component name used in a failure; must not be {@code null}
     * @return the value at exactly the declared width, never {@code null}
     * @throws IllegalArgumentException if the value was never supplied or exceeds the declared width
     */
    private static String requiredExactWidth(String value, int width, String field) {
        if (FieldValidationFlag.isNeverSupplied(value)) {
            throw new IllegalArgumentException(
                    "no value was supplied for " + field + ", which the reference requires");
        }
        String bounded = atMostWidth(value, width, field);
        return bounded + " ".repeat(width - bounded.length());
    }

    /**
     * Returns a value that fits the width its {@code PICTURE} clause declares.
     *
     * @param value the submitted value; must not be {@code null}
     * @param width the declared width
     * @param field the component name used in a failure; must not be {@code null}
     * @return the value unchanged, never {@code null}
     * @throws IllegalArgumentException if the value exceeds the declared width
     */
    private static String atMostWidth(String value, int width, String field) {
        if (value.length() > width) {
            throw new IllegalArgumentException("the supplied value of " + field + " occupies "
                    + value.length() + " characters but the reference field declares " + width
                    + "; truncating it would store a value the submission does not carry");
        }
        return value;
    }
}
