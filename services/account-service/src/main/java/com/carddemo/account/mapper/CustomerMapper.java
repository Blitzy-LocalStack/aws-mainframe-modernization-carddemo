package com.carddemo.account.mapper;

import com.carddemo.account.domain.Customer;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.dto.AccountViewResponse;
import com.carddemo.account.dto.CustomerResponse;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Translates the customer master row between its stored shape and the two transport shapes this
 * context publishes, and encrypts the two protected identifiers on the way in.
 *
 * <h2>Purpose</h2>
 * <p>This is the anti-corruption layer for {@code 01 CUSTOMER-RECORD}, declared at
 * {@code app/cpy/CVCUS01Y.cpy} L4 and running L5 through L23 over the 500 bytes its own header comment
 * at L2 states. Every representation decision the record forces is made here and justified beside the
 * statement that performs it, so that {@link Customer} carries the row, the transport records carry
 * clean values, and neither has to know what the other is shaped like.</p>
 *
 * <h2>Which representation concerns are live in this record, and which are not</h2>
 * <p>The package charter admits five concerns to this layer. Three of them are live for the customer
 * record and three named elsewhere are not, and stating the absences is what stops a reader treating a
 * missing conversion as an oversight.</p>
 *
 * <ul>
 *   <li>Fixed widths are live. Nineteen {@code PICTURE} clauses declare them, and four of the eighteen
 *       meaningful fields are declared at one width on the record and a different width on the account
 *       view map.</li>
 *   <li>Dropped padding is live. {@code FILLER PIC X(168)} at {@code app/cpy/CVCUS01Y.cpy} L23 has no
 *       counterpart on either transport shape.</li>
 *   <li>Masking is live, and it is the dominant concern here: the national identifier and the
 *       government-issued identifier are held encrypted and are published masked on BOTH shapes.</li>
 *   <li>Zoned-decimal sign overpunch is NOT live. That convention applies to a signed picture, and this
 *       record declares no {@code S9(n)V99} field anywhere across L5 through L22.</li>
 *   <li>Packed decimal is NOT live. No field of this record carries a {@code COMP-3} usage, so nothing
 *       here needs nibble decoding.</li>
 *   <li>A field rename is NOT live. No field name in {@code app/cpy/CVCUS01Y.cpy} is misspelled. This
 *       context owns exactly one rename, {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy}
 *       L11, and that field belongs to the account record rather than to this one.</li>
 * </ul>
 *
 * <h2>Why this class is written by hand</h2>
 * <p>Alternatives Considered: generating this translation with MapStruct, which is the shortest route
 * and is rejected. The decisive ground is that the mapping is not mechanical, and this class is the
 * proof rather than an assertion of it. It maps a screen field that has NO corresponding record field:
 * {@code ACSCITYI PIC X(50)} at {@code app/cpy-bms/COACTVW.CPY} L192 and at
 * {@code app/cpy-bms/COACTUP.CPY} L252 has no {@code CUST-CITY} to pair with, because no such field
 * exists anywhere under {@code app}. It produces two shapes that disagree about four widths, so no
 * generator could choose between two declared widths for one value. It composes nine physical screen
 * fields into three logical values, two of them with punctuation the record stores. And it encrypts and
 * masks. Each of those is a judgement a reader cannot recover from the code that performs it, each needs
 * its rationale at the point of use, and a generated member has nowhere to hold one. A second,
 * independent ground is availability: the most recent published MapStruct release is a beta, and a
 * generator standing between a stored row and a published contract is not somewhere this migration
 * accepts pre-release behaviour.</p>
 *
 * <p>Alternatives Considered: generating the accessors of the collaborating types with Lombok. Rejected
 * because a member produced during annotation processing has no source declaration on which a docstring
 * could sit, while {@code config/checkstyle/checkstyle.xml} declares {@code MissingJavadocMethod} at
 * L363 with {@code scope} set to private, {@code allowMissingPropertyJavadoc} set false and
 * {@code allowedAnnotations} explicitly cleared. A generated accessor would therefore be a violation
 * rather than a tolerated omission, so generation could only be made viable by weakening the gate, which
 * is the trade the gate exists to refuse.</p>
 *
 * <h2>The cryptography boundary, and why it arrives as a port</h2>
 * <p>Refactoring Rationale: {@link Customer} was deliberately left unable to encrypt or decrypt
 * anything. It holds the two identifiers as encrypted bytes, publishes no accessor for either, and
 * records that the architecture rule forbidding a {@code ..domain..} package from reaching an
 * infrastructure type is what makes that necessary. Both directions of the conversion therefore land
 * here, and this class is the only cryptography boundary in the account service.</p>
 *
 * <p>Alternatives Considered: reaching a key provider from this class directly, by constructing a cloud
 * provider client here. Rejected on two grounds that are independent of each other. It would put an
 * infrastructure type inside the mapping layer, which is exactly the dependency the shared architecture
 * test asserts against for the domain layer and which this package's charter closes for the same reason.
 * And it would make the class impossible to exercise without a live key provider, whereas a port lets a
 * unit test supply a substitute and assert the mapping itself. The port is injected through the
 * constructor and is never constructed from a literal, so no key identifier, endpoint or credential
 * appears anywhere in this file.</p>
 *
 * <p>Alternatives Considered: declaring the port with both an encrypt and a decrypt operation, which is
 * the symmetric shape and reads as the more complete contract. Rejected because the reverse direction
 * would be unreachable from this class: {@link Customer} publishes no accessor for either ciphertext
 * field, so no method here can obtain ciphertext from a row, and both output shapes mask
 * unconditionally rather than revealing any part of a decrypted value. A declared operation that no
 * caller could reach would suggest a capability this boundary does not offer, and a later reader would
 * have to establish by inspection that nothing used it. The port therefore declares the one direction
 * this class performs, and the asymmetry is recorded here rather than left to be discovered.</p>
 *
 * <h2>What this class does not do</h2>
 * <p>Assumptions: no row is read here and no business rule is applied here. This class receives a row or
 * a request and returns the other side's shape; it does not decide whether the row should have been
 * read, what to do when it was absent, or whether a supplied value satisfies a domain rule such as an
 * excluded identifier range or a state-and-postal-code pairing. Those belong to the service layer. It
 * declares no exception type and no error surface either: the shared kernel's advice already answers an
 * optimistic-lock failure with the conflict status carrying the reference's own verbatim message.</p>
 *
 * <p>Assumptions: nothing from the shared money package is imported, and the absence is a property of
 * this record rather than a gap. {@code app/cpy/CVCUS01Y.cpy} declares no amount at all; the five
 * amounts of this bounded context are {@code PIC S9(10)V99} fields of the account record, at
 * {@code app/cpy/CVACT01Y.cpy} L7, L8, L9, L13 and L14, the first of which is this migration's normative
 * exemplar for an amount. A later reader looking for an exact-decimal conversion here will not find one
 * because there is no amount here to convert.</p>
 *
 * <h2>The oracle this context does not have</h2>
 * <p>Assumptions: no golden-master comparison is available for anything this class translates.
 * {@code tests/README.md} states at L83 through L85 that the online programs cannot be run end to end
 * without a CICS runtime, which the runner does not have, and that only their extractable
 * field-validation logic is unit-tested; its business-rules section opens at L553 and names batch
 * programs only. What IS directly verifiable is exactly what this class deals in -- a declared width, a
 * record length, a dropped filler and a composed punctuation form are all checkable by string and
 * integer equality -- and those are asserted character for character in this service's own tests under
 * {@code services/account-service/src/test}, which is a different tree from the root {@code tests}
 * suite and is never conflated with it.</p>
 */
@Component
public class CustomerMapper {

    /**
     * Converts a protected identifier from its clear form into the ciphertext the row stores.
     *
     * <p>Assumptions: this is a port rather than a client. It is declared in this package's own
     * signature surface so that the mapping layer expresses WHAT it needs of a key provider without
     * naming one, and an implementation supplying it lives in the configuration layer where an
     * infrastructure type is admissible. A unit test satisfies it with a substitute, which is what
     * keeps every width, composition and masking decision in this class assertable without key
     * material anywhere in reach.</p>
     */
    public interface CustomerIdentifierProtection {

        /**
         * Converts one identifier from its clear form into the ciphertext the row stores.
         *
         * <p>Assumptions: the clear form arrives as text at its declared width rather than as a
         * number, and an implementation must encrypt exactly the characters it is given. The national
         * identifier is declared {@code PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy} L17 and the
         * government-issued identifier {@code PIC X(20)} at L18, and both admit a leading zero, so an
         * implementation that reduced either to a number before encrypting would return ciphertext
         * that decrypts to a different identifier than the reference stored, with nothing downstream
         * able to detect it.</p>
         *
         * @param clearText the identifier exactly as it is to be stored, at its declared character
         *     width; never {@code null} and never empty when this method is called from this package
         * @param field the column the value belongs to, supplied so that an implementation can select
         *     a key context and so that a refusal names which value was at fault; never {@code null}
         * @return the ciphertext to store, which must never be {@code null} and must never be empty
         * @throws IllegalStateException if the identifier cannot be protected, which this class
         *     propagates rather than catching, because a row written with an unprotected identifier is
         *     worse than a request that fails
         */
        byte[] encrypt(String clearText, String field);
    }

    /**
     * The fixed marker published in place of either protected identifier.
     */
    // WHY : Assumptions: the value deliberately matches the marker {@link Customer} renders in its own
    //   diagnostic form, so that a log line naming a withheld identifier and a response body naming the
    //   same one cannot disagree about how a withholding looks. It is a CONSTANT rather than a
    //   length-preserving or presence-preserving mask, and that is the whole point: nothing derived from
    //   the ciphertext -- not its bytes, not its length, not whether the optional government-issued
    //   identifier is present at all -- can reach a caller through it. A mask revealing a trailing
    //   portion, which is what this migration does for a primary account number, is unavailable here
    //   anyway, because that would need the clear value and Customer publishes no accessor for it.
    public static final String IDENTIFIER_REDACTED = "[REDACTED]";

    /**
     * The single character a submitter sends to REMOVE a stored optional protected identifier.
     *
     * <p>Assumptions: the marker is the reference's own, not one invented here.
     * {@code app/cbl/COACTUPC.cbl} L1401 to L1403 tests the screen field for {@code '*'} or spaces and
     * moves low values into the field when either holds, so the asterisk is already the character a user
     * of this screen types to blank a field. Reusing it means the target adds a capability -- telling
     * removal apart from an unedited field, which the baseline cannot -- without adding an input
     * convention a user would have to learn.</p>
     */
    public static final String IDENTIFIER_REMOVAL_MARKER = "*";

    /**
     * The declared width of the national identifier once its three screen parts are composed.
     */
    // WHY : Assumptions: nine is the sum of the three update-map parts and the width of the stored
    //   field, and the agreement is the evidence that the composition is complete rather than
    //   approximate. ACTSSN1I PIC X(3) at app/cpy-bms/COACTUP.CPY L168, ACTSSN2I PIC X(2) at L174 and
    //   ACTSSN3I PIC X(4) at L180 sum to nine, matching CUST-SSN PIC 9(09) at app/cpy/CVCUS01Y.cpy L17.
    private static final int NATIONAL_IDENTIFIER_WIDTH = 9;

    /** The declared width of the first screen part of the national identifier. */
    private static final int NATIONAL_IDENTIFIER_PART_1_WIDTH = 3;

    /** The declared width of the middle screen part of the national identifier. */
    private static final int NATIONAL_IDENTIFIER_PART_2_WIDTH = 2;

    /** The declared width of the last screen part of the national identifier. */
    private static final int NATIONAL_IDENTIFIER_PART_3_WIDTH = 4;

    /**
     * The declared width of the government-issued identifier, which agrees on record and on screen.
     */
    // WHY : Assumptions: twenty on both sides, so this field needs no width decision and is deliberately
    //   absent from the four divergences documented on the two output shapes.
    //   CUST-GOVT-ISSUED-ID PIC X(20) at app/cpy/CVCUS01Y.cpy L18 agrees with ACSGOVTI PIC X(20) at
    //   app/cpy-bms/COACTVW.CPY L210 and at app/cpy-bms/COACTUP.CPY L282. The screen field is ACSGOVTI
    //   and not any similarly shaped name: a search for ACSGVIDI returns nothing in either map.
    private static final int GOVERNMENT_IDENTIFIER_WIDTH = 20;

    /** The declared width of the customer identifier, which agrees on record and on both screens. */
    private static final int CUSTOMER_IDENTIFIER_WIDTH = 9;

    /** The declared width of the credit score, which agrees on record and on both screens. */
    private static final int CREDIT_SCORE_WIDTH = 3;

    /** The declared width of each of the three name fields. */
    private static final int NAME_WIDTH = 25;

    /** The declared width of each of the three address lines, the third of which carries the city. */
    private static final int ADDRESS_LINE_WIDTH = 50;

    /** The declared width of the state code. */
    private static final int STATE_CODE_WIDTH = 2;

    /** The declared width of the country code. */
    private static final int COUNTRY_CODE_WIDTH = 3;

    /**
     * The declared width of the postal code AS STORED, which is twice the width the screen shows.
     */
    // WHY : Assumptions: ten is the record width and it is not a date width, despite the picture being
    //   the same shape as the record's one date. CUST-ADDR-ZIP PIC X(10) at app/cpy/CVCUS01Y.cpy L14 is
    //   a postal code, CUST-EFT-ACCOUNT-ID PIC X(10) at L20 is an external account identifier, and only
    //   CUST-DOB-YYYY-MM-DD PIC X(10) at L19 is a date. Treating either of the other two as a date
    //   would discard characters no date format has a position for.
    private static final int POSTAL_CODE_RECORD_WIDTH = 10;

    /** The declared width of the postal code on the account view and account update maps. */
    private static final int POSTAL_CODE_SCREEN_WIDTH = 5;

    /** The declared width of each telephone number AS STORED, punctuation included. */
    private static final int TELEPHONE_RECORD_WIDTH = 15;

    /** The declared width of each telephone number on the account view map. */
    private static final int TELEPHONE_SCREEN_WIDTH = 13;

    /** The declared width of the area-code part of a telephone number on the update map. */
    private static final int TELEPHONE_AREA_CODE_WIDTH = 3;

    /** The declared width of the exchange-prefix part of a telephone number on the update map. */
    private static final int TELEPHONE_PREFIX_WIDTH = 3;

    /** The declared width of the line-number part of a telephone number on the update map. */
    private static final int TELEPHONE_LINE_NUMBER_WIDTH = 4;

    /** The number of parts a submitted telephone number arrives in, all three required together. */
    private static final int TELEPHONE_PART_COUNT = 3;

    /** The request property carrying the year part of the submitted date of birth. */
    private static final String DATE_OF_BIRTH_YEAR_PROPERTY = "dateOfBirthYear";

    /** The request property carrying the month part of the submitted date of birth. */
    private static final String DATE_OF_BIRTH_MONTH_PROPERTY = "dateOfBirthMonth";

    /** The request property carrying the day part of the submitted date of birth. */
    private static final String DATE_OF_BIRTH_DAY_PROPERTY = "dateOfBirthDay";

    /**
     * The request-property stem the three parts of the first telephone number share.
     *
     * <p>Assumptions: the stem is a constant rather than a literal at the call site because the part
     * identities are composed from it, so a stem that did not match the request record's property names
     * would produce three unbindable keys at once rather than one.</p>
     */
    private static final String TELEPHONE_1_PROPERTY_STEM = "phone1";

    /** The request-property stem the three parts of the second telephone number share. */
    private static final String TELEPHONE_2_PROPERTY_STEM = "phone2";

    /** The suffix the request record appends to a stem to name the area-code part. */
    private static final String TELEPHONE_AREA_CODE_PROPERTY_SUFFIX = "AreaCode";

    /** The suffix the request record appends to a stem to name the exchange-prefix part. */
    private static final String TELEPHONE_PREFIX_PROPERTY_SUFFIX = "Prefix";

    /** The suffix the request record appends to a stem to name the line-number part. */
    private static final String TELEPHONE_LINE_NUMBER_PROPERTY_SUFFIX = "LineNumber";

    /** The declared width of the electronic-transfer account identifier. */
    private static final int EFT_ACCOUNT_ID_WIDTH = 10;

    /** The declared width of the primary-cardholder indicator. */
    private static final int PRIMARY_CARD_HOLDER_INDICATOR_WIDTH = 1;

    /** The declared width of the date of birth as the record and both maps hold it. */
    private static final int DATE_OF_BIRTH_WIDTH = 10;

    /** The declared width of the year part of the date of birth on the update map. */
    private static final int DATE_OF_BIRTH_YEAR_WIDTH = 4;

    /** The declared width of the month part of the date of birth on the update map. */
    private static final int DATE_OF_BIRTH_MONTH_WIDTH = 2;

    /** The declared width of the day part of the date of birth on the update map. */
    private static final int DATE_OF_BIRTH_DAY_WIDTH = 2;

    /**
     * The width of the trailing padding this class drops, recorded so the drop is auditable.
     */
    // WHY : Assumptions: FILLER PIC X(168) at app/cpy/CVCUS01Y.cpy L23 is padding to a fixed record
    //   length rather than data, and the arithmetic is what establishes that rather than the field name:
    //   the eighteen meaningful widths at L5 through L22 sum to 332, and 332 + 168 = 500, exactly the
    //   RECLN 500 the copybook states at its own L2. Neither output shape carries a nineteenth component,
    //   so the drop transformation rule T1 requires is recorded here rather than left to be inferred from
    //   a missing field.
    // WHY : Trade-offs: this and the two constants below are PUBLIC while every other width here is
    //   private, following the one public width the sibling reply encoder already exposes. The arithmetic
    //   is the self-check that proves no field was lost or invented, and it is worth nothing unless a test
    //   can assert it; keeping it private would leave the claim checkable only by re-reading the copybook
    //   by hand. Nothing about the mapping is configurable through them -- they are stated facts about a
    //   reference record, and no code path reads them to decide anything.
    public static final int DROPPED_FILLER_WIDTH = 168;

    /** The record length the meaningful fields and the dropped padding sum to together. */
    public static final int RECORD_LENGTH = 500;

    /** The sum of the widths of the eighteen meaningful fields, excluding the dropped padding. */
    public static final int MEANINGFUL_FIELD_WIDTH_SUM = RECORD_LENGTH - DROPPED_FILLER_WIDTH;

    /** The opening punctuation character the reference writes into the first telephone separator slot. */
    private static final String TELEPHONE_OPENING_PUNCTUATION = "(";

    /** The closing punctuation character the reference writes into the second telephone separator slot. */
    private static final String TELEPHONE_CLOSING_PUNCTUATION = ")";

    /** The separator the reference writes into the third telephone separator slot. */
    private static final String TELEPHONE_SEPARATOR = "-";

    /** The separator the reference writes between the three parts of the date of birth. */
    private static final String DATE_PART_SEPARATOR = "-";

    /**
     * The help text carried by every entry of the per-field array, which is this migration's own wording.
     */
    // WHY : Trade-offs: one shared sentence rather than a sentence per field. The reference attaches NO
    //   per-field text to a highlighted field -- app/cpy/CSSETATY.cpy moves a colour attribute at L21 and
    //   L22 and a marker character at L24 and L25, and nothing else -- so there is no baseline literal to
    //   carry across here. Authoring a different sentence for each field would produce twenty strings a
    //   later reader could mistake for migrated text, and transformation rule T8 exists precisely so that
    //   migrated text is recognisable as such. A single plainly-authored sentence keeps the array useful
    //   while asserting no provenance it does not have.
    private static final String FIELD_NEVER_SUPPLIED_HELP =
            "This field is required and no value was supplied";

    /** The injected boundary through which a clear identifier becomes the ciphertext the row stores. */
    private final CustomerIdentifierProtection protection;

    /**
     * Creates the mapper over the boundary that protects the two identifiers this record holds.
     *
     * <p>Assumptions: the collaborator is supplied rather than created, and the refusal below is
     * immediate rather than deferred to the first mapping. A mapper constructed without a protection
     * boundary could still serve both read projections and would fail only on the first write, which is
     * the least convenient moment to discover a wiring mistake.</p>
     *
     * @param protection the boundary that converts a clear identifier into stored ciphertext; must not
     *     be {@code null}
     * @throws NullPointerException if {@code protection} is {@code null}
     */
    public CustomerMapper(CustomerIdentifierProtection protection) {
        this.protection = Objects.requireNonNull(protection, "protection must not be null");
    }

    /**
     * Projects a stored customer row onto the response contract derived at RECORD widths.
     *
     * <p>Assumptions: this is one of TWO output shapes for one row, and it is the shape whose widths come
     * from {@code app/cpy/CVCUS01Y.cpy}. The other, {@link #toCustomerDetail(Customer)}, takes its widths
     * from the account view map, and the two disagree about exactly four fields: the postal code, the
     * national identifier and both telephone numbers. Neither shape may stand in for the other, because a
     * value carried at the wrong side's width is silently either truncated or padded.</p>
     *
     * <p>Assumptions: the eighteen components correspond one for one to the {@code 05} level items at
     * {@code app/cpy/CVCUS01Y.cpy} L5 through L22, and there is no nineteenth. The nineteenth
     * {@code PICTURE} clause of that copybook is the {@code FILLER PIC X(168)} at L23, which is dropped;
     * the rationale and the arithmetic that closes the record length sit on the constant recording that
     * width.</p>
     *
     * @param row the stored customer master row; must not be {@code null}
     * @return the response contract at record widths, with both protected identifiers masked, never
     *     {@code null}
     * @throws NullPointerException if {@code row} is {@code null}, because an absent row is a not-found
     *     answer for the caller to decide on rather than a value to project
     * @throws IllegalStateException if a column the schema declares {@code NOT NULL} is absent, or if a
     *     stored value is wider than the width its {@code PICTURE} clause declares
     */
    public CustomerResponse toCustomerResponse(Customer row) {
        Objects.requireNonNull(row, "row must not be null");

        return new CustomerResponse(
                customerIdentifierDigits(row.getCustomerId()),
                // WHY : Assumptions: the three name fields and the first two address lines are carried as
                //   stored rather than padded out to their declared widths. Their declared width is a
                //   maximum for a descriptive value -- a name shorter than twenty-five characters is a
                //   shorter name and not a defective one -- and the entity records that their trailing
                //   blanks in the fixed-width record are padding that is not stored. Padding them here
                //   would put back exactly the characters the load deliberately removed.
                atMostWidth(row.getFirstName(), NAME_WIDTH, "first_name", true),
                atMostWidth(row.getMiddleName(), NAME_WIDTH, "middle_name", false),
                atMostWidth(row.getLastName(), NAME_WIDTH, "last_name", true),
                atMostWidth(row.getAddressLine1(), ADDRESS_LINE_WIDTH, "addr_line_1", true),
                atMostWidth(row.getAddressLine2(), ADDRESS_LINE_WIDTH, "addr_line_2", false),
                // WHY : Assumptions: this component is named for the copybook field and carries the value
                //   the account view screen labels as the city. There is no field to name it after: a
                //   search for CUST-CITY returns nothing under app/cpy, nothing in app/cbl/CBCUS01C.cbl
                //   and nothing anywhere under app, while app/cbl/COACTVWC.cbl L513 moves
                //   CUST-ADDR-LINE-3 straight into the screen's city field. Naming it for the screen here
                //   would assert a stored field the baseline does not declare, which is why the screen
                //   name appears only on the shape derived from the screen.
                atMostWidth(row.getAddressLine3(), ADDRESS_LINE_WIDTH, "addr_line_3", true),
                exactWidth(row.getAddressStateCode(), STATE_CODE_WIDTH, "addr_state_cd", true),
                exactWidth(row.getAddressCountryCode(), COUNTRY_CODE_WIDTH, "addr_country_cd", true),
                // WHY : Assumptions: ten characters, which is the FIRST of the four widths this shape and
                //   the screen shape disagree about. CUST-ADDR-ZIP PIC X(10) at app/cpy/CVCUS01Y.cpy L14
                //   against ACSZIPCI PIC X(5) at app/cpy-bms/COACTVW.CPY L186, and app/cbl/COACTVWC.cbl
                //   L515 moves the wider value into the narrower field, so the baseline itself shows five
                //   of the ten on that screen. This shape is derived at record width and carries all ten;
                //   narrowing it here would discard five characters the record holds and would leave no
                //   endpoint through which the stored value could be read at all.
                exactWidth(row.getAddressZip(), POSTAL_CODE_RECORD_WIDTH, "addr_zip", true),
                // WHY : Assumptions: fifteen characters, which is the THIRD and FOURTH of the four
                //   disagreements. CUST-PHONE-NUM-1 and CUST-PHONE-NUM-2 are PIC X(15) at
                //   app/cpy/CVCUS01Y.cpy L15 and L16, against ACSPHN1I and ACSPHN2I at PIC X(13) at
                //   app/cpy-bms/COACTVW.CPY L204 and L216. The difference is not arbitrary and is not a
                //   truncation of data: the composed form occupies thirteen of the fifteen bytes and the
                //   remaining two are the trailing pad the before-image declares as FILLER PIC X(2) at
                //   app/cbl/COACTUPC.cbl L731, and again at L741 for the second number. This shape
                //   carries the stored fifteen; a thirteen-character component would adopt the screen's
                //   truncation as though it were the stored layout.
                storedTelephoneNumber(row.getPhoneNumber1(), "phone_num_1"),
                storedTelephoneNumber(row.getPhoneNumber2(), "phone_num_2"),
                // WHY : Trade-offs: both identifiers are published as a fixed marker and the baseline
                //   publishes neither that way -- app/cbl/COACTVWC.cbl L496 through L504 composes the
                //   national identifier whole with two separators into the screen field, and L519 passes
                //   the government-issued identifier through untouched. The reference stores both in
                //   clear on a file its resource definition declares READINTEG(UNCOMMITTED) at
                //   app/csd/CARDDEMO.CSD L53 and RECOVERY(NONE) at L59, inside the CUSTDAT stanza opening
                //   at L50; the Java stores them encrypted with automated backups and publishes them
                //   masked, and the divergence is documented rather than presented as equivalent. The
                //   cost accepted is that a caller cannot reconcile either identifier against the source
                //   system from a response alone; the alternative put the record's only directly
                //   identifying credentials into a document a caller may store, log or forward. The one
                //   audited path to an unmasked value in this migration is an administrative card-detail
                //   path owned by the card service, not by this one.
                IDENTIFIER_REDACTED,
                IDENTIFIER_REDACTED,
                dateOfBirthText(row.getDateOfBirth()),
                exactWidth(row.getEftAccountId(), EFT_ACCOUNT_ID_WIDTH, "eft_account_id", true),
                exactWidth(row.getPrimaryCardHolderIndicator(), PRIMARY_CARD_HOLDER_INDICATOR_WIDTH,
                        "pri_card_holder_ind", true),
                creditScoreDigits(row.getFicoCreditScore()));
    }

    /**
     * Projects a stored customer row onto the account view contract derived at SCREEN widths.
     *
     * <p>Assumptions: the components are declared in the order the symbolic map declares its fields, so
     * the two can be read side by side, and each width comes from the map rather than from the copybook.
     * The customer region of {@code app/cpy-bms/COACTVW.CPY} runs from {@code ACSTNUMI} at L126 to
     * {@code ACSPFLGI} at L228, and the moves that populate it are the block at
     * {@code app/cbl/COACTVWC.cbl} L493 through L522.</p>
     *
     * <p>Assumptions: three of the four width disagreements are resolved toward the screen here, and the
     * fourth field is not one of them. The postal code narrows and both telephone numbers narrow; the
     * city agrees at {@code PIC X(50)} on both sides and the government-issued identifier agrees at
     * {@code PIC X(20)}, so neither of those needs a width decision at all. Keeping the identity-width
     * remapping of the city separate from the three genuine narrowings is what makes the list of
     * disagreements checkable instead of merely plausible.</p>
     *
     * @param row the stored customer master row; must not be {@code null}
     * @return the customer grouping of the account view contract at screen widths, with both protected
     *     identifiers masked, never {@code null}
     * @throws NullPointerException if {@code row} is {@code null}, for the reason recorded on
     *     {@link #toCustomerResponse(Customer)}
     * @throws IllegalStateException if a column the schema declares {@code NOT NULL} is absent, or if a
     *     stored value is wider than the width its {@code PICTURE} clause declares
     */
    public AccountViewResponse.CustomerDetail toCustomerDetail(Customer row) {
        Objects.requireNonNull(row, "row must not be null");

        return new AccountViewResponse.CustomerDetail(
                // WHY : Assumptions: nine digits on this shape as well, because ACSTNUMI PIC X(9) at
                //   app/cpy-bms/COACTVW.CPY L126 agrees with CUST-ID PIC 9(09) at
                //   app/cpy/CVCUS01Y.cpy L5. The move at app/cbl/COACTVWC.cbl L494 is direct, so there is
                //   nothing to narrow here and the identifier is not among the four disagreements.
                customerIdentifierDigits(row.getCustomerId()),
                // WHY : Trade-offs: the marker is ten characters and the screen field ACSTSSNI is
                //   PIC X(12) at app/cpy-bms/COACTVW.CPY L132, so it fits without truncation. The
                //   twelve-character screen width against PIC 9(09) at app/cpy/CVCUS01Y.cpy L17 is the
                //   SECOND of the four disagreements, and the reference explains the extra characters
                //   rather than leaving them unaccounted for: app/cbl/COACTVWC.cbl L496 through L504
                //   composes three digits, a separator, two digits, a separator and four digits, which is
                //   eleven characters in a twelve-character field. Because this shape masks, the width
                //   the composition needed is documentation here rather than an instruction, and the
                //   masking is deliberately identical to the other shape's so that a caller cannot infer
                //   anything from the difference between two responses.
                IDENTIFIER_REDACTED,
                // WHY : Assumptions: the date of birth is NOT among the four disagreements, because
                //   ACSTDOBI PIC X(10) at app/cpy-bms/COACTVW.CPY L138 agrees with
                //   CUST-DOB-YYYY-MM-DD PIC X(10) at app/cpy/CVCUS01Y.cpy L19 and the move at
                //   app/cbl/COACTVWC.cbl L507 is direct. It is carried whole on this shape rather than
                //   split into parts; only the UPDATE map splits it, at three separate fields.
                dateOfBirthText(row.getDateOfBirth()),
                creditScoreDigits(row.getFicoCreditScore()),
                atMostWidth(row.getFirstName(), NAME_WIDTH, "first_name", true),
                atMostWidth(row.getMiddleName(), NAME_WIDTH, "middle_name", false),
                atMostWidth(row.getLastName(), NAME_WIDTH, "last_name", true),
                atMostWidth(row.getAddressLine1(), ADDRESS_LINE_WIDTH, "addr_line_1", true),
                exactWidth(row.getAddressStateCode(), STATE_CODE_WIDTH, "addr_state_cd", true),
                atMostWidth(row.getAddressLine2(), ADDRESS_LINE_WIDTH, "addr_line_2", false),
                screenPostalCode(row.getAddressZip()),
                // WHY : Assumptions: the city component of this shape is fed from the THIRD ADDRESS LINE,
                //   and the two widths agree exactly at fifty characters -- CUST-ADDR-LINE-3 PIC X(50) at
                //   app/cpy/CVCUS01Y.cpy L11 against ACSCITYI PIC X(50) at app/cpy-bms/COACTVW.CPY L192
                //   and at app/cpy-bms/COACTUP.CPY L252. That exact agreement is the evidence for the
                //   choice rather than the names being similar, because the names are not similar at all:
                //   no CUST-CITY field exists, proven three independent ways -- no match under app/cpy,
                //   no match in app/cbl/CBCUS01C.cbl, and no such field among L5 to L23 of the record.
                //   The third line is additionally the only fifty-byte field left unclaimed once the
                //   first two are bound to ACSADL1I and ACSADL2I. Because the widths agree, this is an
                //   identity-width remapping between differently named fields and NOT one of the four
                //   width disagreements; conflating the two phenomena is the likeliest reading error
                //   here, so they are kept apart deliberately.
                atMostWidth(row.getAddressLine3(), ADDRESS_LINE_WIDTH, "addr_line_3", true),
                exactWidth(row.getAddressCountryCode(), COUNTRY_CODE_WIDTH, "addr_country_cd", true),
                screenTelephoneNumber(row.getPhoneNumber1(), "phone_num_1"),
                // WHY : Trade-offs: masked on THIS shape as well as on the record shape, so no output of
                //   this class discloses either identifier. The screen field is ACSGOVTI PIC X(20) at
                //   app/cpy-bms/COACTVW.CPY L210, the same width as CUST-GOVT-ISSUED-ID PIC X(20) at
                //   app/cpy/CVCUS01Y.cpy L18, and app/cbl/COACTVWC.cbl L519 moves the value across
                //   untouched. Masking a field whose widths agree is therefore a decision about
                //   disclosure alone and not about width, which is why this field is absent from the four
                //   disagreements while still being masked.
                IDENTIFIER_REDACTED,
                screenTelephoneNumber(row.getPhoneNumber2(), "phone_num_2"),
                exactWidth(row.getEftAccountId(), EFT_ACCOUNT_ID_WIDTH, "eft_account_id", true),
                exactWidth(row.getPrimaryCardHolderIndicator(), PRIMARY_CARD_HOLDER_INDICATOR_WIDTH,
                        "pri_card_holder_ind", true));
    }


    /**
     * Applies a submitted edit onto the LOADED customer row, preserving what the submitter cannot resend.
     *
     * <p>Purpose: this is the update path. It replaces the previous shape, in which
     * a {@code toCustomer(AccountUpdateRequest)} method built a brand-new {@code Customer} from the
     * request alone, and it exists because that shape lost data three separate ways -- each of which is
     * a consequence of the mapper never seeing the stored row. That method and the postal-code widener
     * it used are REMOVED rather than left beside this one: the reference program persists this record
     * with {@code EXEC CICS REWRITE} at {@code app/cbl/COACTUPC.cbl} L4086 and issues no
     * {@code WRITE} anywhere, so there is no create flow for a customer in this context and the removed
     * method had no baseline counterpart to serve. Leaving it would have left a route that resets the
     * version and blanks the postal-code tail available to the next caller who found it.</p>
     *
     * <p>Refactoring Rationale: the three losses, and why mutating the loaded row fixes all three at
     * once. (1) The optimistic-lock version was reset to zero on every update, so the concurrent-change
     * detection the reference performs with its before-image had no target form at all; the provider
     * compares the version it LOADED, so only a mutated managed row can reproduce the check.
     * (2) An omitted government-issued identifier became {@code null} and DELETED stored ciphertext the
     * submitter had never been shown and could not have re-supplied. (3) The stored postal code's
     * positions six to ten were overwritten with blanks on every update. None of the three is fixable
     * from the request alone, because in each case the value that must survive is one only the stored row
     * holds.</p>
     *
     * <p>Assumptions: the two protected identifiers are resolved into
     * {@link Customer.ProtectedValueUpdate} intents HERE rather than in the entity, because deciding
     * whether a submission edited a masked field is a representation question and this class owns
     * representation. The entity is told what to do; it does not infer it from a value.</p>
     *
     * <p>Assumptions: the national identifier is re-enciphered only when the submitted three parts
     * compose to something, and preserved otherwise. Re-enciphering on every update would produce fresh
     * ciphertext for an unedited value on every save, which advances the version, makes the column change
     * in every audit of the table, and defeats any downstream change detection -- for a value that did
     * not change.</p>
     *
     * <p>Trade-offs: because the submitter is shown a mask, this class cannot tell an unedited national
     * identifier from a deliberate re-entry of the same digits, and treats a supplied value as a
     * replacement in both cases. The cost is one unnecessary encipherment when a submitter retypes the
     * value it was already storing; the alternative -- deciphering the stored value to compare -- would
     * require a decipher route this context deliberately does not have.</p>
     *
     * @param stored the managed row loaded in this transaction; must not be {@code null}
     * @param request the submitted edit; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws ClientInputException if a submitted value is absent where the reference requires one, is
     *     wider than the field it is stored in, holds a character its numeric picture cannot carry, or
     *     is one part of a telephone number or date whose remaining parts were not supplied; every
     *     entry it names is keyed by the REQUEST PROPERTY the value arrived in, so a caller can attach
     *     the refusal to the control it typed into
     * @throws IllegalArgumentException if a composed value's width contradicts this class's own
     *     arithmetic, which is an internal invariant rather than a caller's mistake and is therefore
     *     deliberately answered as a server fault
     * @throws IllegalStateException if the injected protection boundary cannot protect an identifier,
     *     which is propagated rather than caught because a row written with an unprotected identifier is
     *     worse than a request that fails
     */
    public void applyUpdate(Customer stored, AccountUpdateRequest request) {
        Objects.requireNonNull(stored, "stored must not be null");
        Objects.requireNonNull(request, "request must not be null");

        // WHY : Refactoring Rationale: the ten identities below name the REQUEST PROPERTY the value
        //   arrived in -- firstName, city, stateCode -- where they previously named the stored column
        //   -- first_name, addr_line_3, addr_state_cd. The identity is not a diagnostic label: it
        //   becomes the key of a per-field entry in the emitted problem document, which transformation
        //   rule T7 makes the way a refusal reaches a form control, and a form binds its controls to
        //   the property names it submitted. A column name is unbindable, so an array keyed that way
        //   left a caller with a refusal it could not attach to anything on the screen. The change also
        //   settles an inconsistency inside this very method: the postal code, the credit score, the
        //   three identifier parts and the three date parts ALREADY named their request properties, so
        //   one response could carry both vocabularies at once.
        // WHY : Assumptions: the reference lineage the column names carried is not lost. It is recorded
        //   against each component on the entity and on the request record, which is where a reader
        //   looking for a copybook field goes; the value here is read by a client, not by that reader.
        stored.applyUpdate(
                requiredAtMostWidth(request.firstName(), NAME_WIDTH, "firstName"),
                optionalAtMostWidth(request.middleName(), NAME_WIDTH, "middleName"),
                requiredAtMostWidth(request.lastName(), NAME_WIDTH, "lastName"),
                requiredAtMostWidth(request.addressLine1(), ADDRESS_LINE_WIDTH, "addressLine1"),
                optionalAtMostWidth(request.addressLine2(), ADDRESS_LINE_WIDTH, "addressLine2"),
                requiredAtMostWidth(request.city(), ADDRESS_LINE_WIDTH, "city"),
                requiredExactWidth(request.stateCode(), STATE_CODE_WIDTH, "stateCode"),
                requiredExactWidth(request.countryCode(), COUNTRY_CODE_WIDTH, "countryCode"),
                updatedPostalCode(request.zipCode(), stored.getAddressZip()),
                telephoneNumber(request.phone1AreaCode(), request.phone1Prefix(),
                        request.phone1LineNumber(), TELEPHONE_1_PROPERTY_STEM),
                telephoneNumber(request.phone2AreaCode(), request.phone2Prefix(),
                        request.phone2LineNumber(), TELEPHONE_2_PROPERTY_STEM),
                nationalIdentifierUpdate(request),
                governmentIdentifierUpdate(request),
                dateOfBirth(request.dateOfBirthYear(), request.dateOfBirthMonth(),
                        request.dateOfBirthDay()),
                requiredExactWidth(request.eftAccountId(), EFT_ACCOUNT_ID_WIDTH, "eftAccountId"),
                requiredExactWidth(request.primaryCardHolderIndicator(),
                        PRIMARY_CARD_HOLDER_INDICATOR_WIDTH, "primaryCardHolderIndicator"),
                creditScoreValue(request.ficoCreditScore()));
    }

    /**
     * Widens a submitted postal code to the stored width, KEEPING the stored tail when it still applies.
     *
     * <p>Purpose: the update screen field is five characters wide -- {@code ACSZIPCI PIC X(5)} at
     * {@code app/cpy-bms/COACTUP.CPY} L246, declared {@code LENGTH=5} at {@code app/bms/COACTUP.bms}
     * L384 -- while the column is ten. The reference resolves that with an ordinary alphanumeric move
     * into the wider field, which left-justifies and blank-fills: {@code app/cbl/COACTUPC.cbl} L1354
     * moves the screen field into {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} at L809, and L4025 moves that
     * into {@code CUST-UPDATE-ADDR-ZIP PIC X(10)} at L447. So the reference OVERWRITES positions six to
     * ten with blanks on every update.</p>
     *
     * <p>Refactoring Rationale: this is a DOCUMENTED DIVERGENCE from that behaviour, and it is registered
     * as one rather than reproduced, because the reference behaviour destroys committed data that the
     * submitter was never shown. The seed extract is what settles it: of the fifty records in
     * {@code app/data/ASCII/custdata.txt}, THIRTY carry a real four-digit extension in positions six to
     * ten -- {@code 19852-6716} and twenty-nine more -- and only twenty are five characters followed by
     * blanks. The account view screen shows five characters
     * ({@code app/cbl/COACTVWC.cbl} L515 into {@code ACSZIPCI PIC X(5)}), so a submitter who opens a
     * customer, changes a telephone number and saves would silently drop the extension on any of those
     * thirty. That is data loss with no user intent behind it.</p>
     *
     * <p>Assumptions: the tail is preserved when the submitted five characters MATCH the stored leading
     * five, and cleared when they differ. The condition is what keeps the divergence narrow and correct
     * in both directions: an unchanged postal code keeps its extension, and a genuinely changed postal
     * code does not silently retain an extension belonging to the old one, which would be a different
     * and worse defect. Nothing observable on the screen changes either way, because the screen shows
     * only the leading five in both cases.</p>
     *
     * <p>Alternatives Considered: widening the screen field to ten so a submitter could send the whole
     * value. Rejected because the field width is the baseline's and is asserted from two independent
     * places, and widening it would change the reference contract this migration reproduces rather than
     * closing the loss. Also considered: refusing an update whose stored value has a tail. Rejected
     * because it would make thirty of the fifty seed customers uneditable.</p>
     *
     * @param screenValue the five-character value the update screen supplies; must be supplied
     * @param storedValue the stored ten-character value from the loaded row, which may be {@code null}
     *     on a row that somehow holds none
     * @return the value at exactly the ten characters the column stores, never {@code null}
     * @throws IllegalArgumentException if the value was never supplied or is wider than the five
     *     characters the screen field declares
     */
    private static String updatedPostalCode(String screenValue, String storedValue) {
        String atScreenWidth = requiredAtMostWidth(screenValue, POSTAL_CODE_SCREEN_WIDTH, "zipCode");
        if (storedValue != null && storedValue.length() == POSTAL_CODE_RECORD_WIDTH) {
            String storedHead = storedValue.substring(0, POSTAL_CODE_SCREEN_WIDTH);
            String storedTail = storedValue.substring(POSTAL_CODE_SCREEN_WIDTH);
            // WHY : Assumptions: the comparison is on the SUBMITTED value padded to the screen width,
            //       not on the raw submission, because a submitter sending four characters and a
            //       submitter sending those four followed by a blank mean the same postal code and the
            //       screen cannot distinguish them. Comparing unpadded would clear the tail for one and
            //       keep it for the other.
            if (padToWidth(atScreenWidth, POSTAL_CODE_SCREEN_WIDTH, "addr_zip").equals(storedHead)) {
                return storedHead + storedTail;
            }
        }
        return padToWidth(atScreenWidth, POSTAL_CODE_RECORD_WIDTH, "addr_zip");
    }

    /**
     * Decides what the submission intends for the national identifier.
     *
     * <p>Assumptions: the column is declared {@code NOT NULL}, so the only two available intents are
     * preserve and replace, and an absent submission means preserve. That is the whole reason this method
     * exists rather than the value being passed straight through: a submitter is shown a mask, so an
     * absent value is "I did not edit this" and never "store nothing".</p>
     *
     * @param request the submitted edit; must not be {@code null}
     * @return the intent for the national identifier, never {@code null}
     * @throws IllegalStateException if the protection boundary cannot protect the identifier
     */
    private Customer.ProtectedValueUpdate nationalIdentifierUpdate(AccountUpdateRequest request) {
        if (FieldValidationFlag.isNeverSupplied(request.ssnPart1())
                && FieldValidationFlag.isNeverSupplied(request.ssnPart2())
                && FieldValidationFlag.isNeverSupplied(request.ssnPart3())) {
            return Customer.ProtectedValueUpdate.preserve();
        }
        return Customer.ProtectedValueUpdate.replaceWith(this.protection.encrypt(
                nationalIdentifier(request.ssnPart1(), request.ssnPart2(), request.ssnPart3()),
                "ssn_encrypted"));
    }

    /**
     * Decides what the submission intends for the government-issued identifier.
     *
     * <p>Assumptions: the column is nullable, so all three intents are available, and an absent
     * submission means PRESERVE rather than clear. This is the finding's centre: an absent value
     * previously became {@code null} and deleted stored ciphertext. Preserve is the only reading that
     * cannot destroy data a submitter never saw, and the submitter has an explicit way to remove a value
     * -- the marker character the reference itself uses -- which is what the clearing branch below
     * serves.</p>
     *
     * <p>Assumptions: the CLEAR intent is selected by the reference's own removal marker rather than by
     * an absent value. {@code app/cbl/COACTUPC.cbl} L1403 moves low values into the field when the screen
     * field holds the marker or spaces, and the shared never-supplied test folds marker, empty and pad
     * into one answer -- so the baseline cannot distinguish "remove" from "unedited" at all. The target
     * can, and it uses the marker for removal specifically because that is the character the reference
     * already gives a user for the purpose, so no new input convention is invented.</p>
     *
     * @param request the submitted edit; must not be {@code null}
     * @return the intent for the government-issued identifier, never {@code null}
     * @throws IllegalStateException if the protection boundary cannot protect the identifier
     */
    private Customer.ProtectedValueUpdate governmentIdentifierUpdate(AccountUpdateRequest request) {
        String submitted = request.governmentIssuedId();
        if (IDENTIFIER_REMOVAL_MARKER.equals(submitted)) {
            return Customer.ProtectedValueUpdate.clear();
        }
        if (FieldValidationFlag.isNeverSupplied(submitted)) {
            return Customer.ProtectedValueUpdate.preserve();
        }
        return Customer.ProtectedValueUpdate.replaceWith(this.protection.encrypt(
                padToWidth(submitted, GOVERNMENT_IDENTIFIER_WIDTH, "govt_issued_id_encrypted"),
                "govt_issued_id_encrypted"));
    }

    /**
     * Reports which customer inputs of an account update request were never supplied, as the structured
     * per-field array a response body carries.
     *
     * <p>Assumptions: this reports the NEVER-SUPPLIED state only, and never a value-domain rule. Whether
     * a national identifier falls in an excluded range, whether a state code pairs with a postal-code
     * prefix, and whether a credit score is plausible are all decisions of the service layer; this class
     * owns representation. Never-supplied belongs here because it IS a representation fact: the reference
     * spells it in pad characters, and {@code app/cbl/COACTUPC.cbl} L1224 through L1229 shows the
     * normalisation doing exactly that, folding both the marker character and a field of spaces into
     * low values before any rule runs. The shared never-supplied test is the single implementation of
     * that fold and is consumed rather than restated.</p>
     *
     * <p>Assumptions: only the fields the reference treats as REQUIRED are reported, and the split comes
     * from the reference rather than from taste. {@code app/cbl/COACTUPC.cbl} edits the first and last
     * names through the required routine at L1563 and L1579 and the middle name through the optional
     * routine at L1571, so a middle name is legitimately absent. The second address line, the second
     * telephone number and the government-issued identifier are optional on the same footing, and
     * reporting any of them would refuse a request the reference accepts.</p>
     *
     * <p>Refactoring Rationale: the reference gates its field highlighting on a re-entry discriminator
     * and the target has none, so this array is driven purely by the request. The rendering template at
     * {@code app/cpy/CSSETATY.cpy} L18 and L19 tests the not-acceptable and never-supplied conditions,
     * and L20 additionally requires {@code CDEMO-PGM-REENTER}, which is the condition declared at
     * {@code app/cpy/COCOM01Y.cpy} L31 over {@code CDEMO-PGM-CONTEXT PIC 9(01)} at L29 alongside
     * {@code CDEMO-PGM-ENTER} at L30. A stateless handler has no first-entry-against-re-entry
     * distinction to make, so that coupling is severed and the presentation is decided by the response
     * body alone. What is NOT severed is the marker: L23 through L25 of that template writes a literal
     * marker character into a never-supplied field, and the never-supplied state carried in each entry
     * below is what still produces it.</p>
     *
     * <p>Trade-offs: the help text is this migration's own wording and is deliberately identical for
     * every field. The reference attaches no per-field text at all -- its template moves a colour
     * attribute at L21 and L22 and the marker at L24 and L25, and nothing else -- so there is no baseline
     * literal to carry across, and inventing a field-specific sentence would produce strings a later
     * reader could mistake for migrated text. One shared, plainly-authored sentence keeps the array
     * useful without asserting a provenance it does not have.</p>
     *
     * @param request the submitted account update whose customer region is examined; must not be
     *     {@code null}
     * @return an unmodifiable array holding one entry per required customer field that was never
     *     supplied, in screen order, and empty when every required field arrived; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public List<ApiError.FieldError> customerFieldErrors(AccountUpdateRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // WHY : Assumptions: insertion order is preserved because the ORDER fields are reported in is
        //   migrated behaviour rather than an implementation detail. The reference highlights fields by
        //   running its expansions in the order they are written, which is the order the fields appear on
        //   the map, so the keys below follow the customer region of app/cpy-bms/COACTUP.CPY from
        //   ACSTNUMI at L162 to ACSPFLGI at L312. The shared collector iterates whatever order the
        //   supplied map defines, so an unordered map here would silently replace that sequence.
        Map<String, FieldValidationFlag> flagsByField = new LinkedHashMap<>();
        flagsByField.put("customerId", flagFor(request.customerId()));
        // WHY : Assumptions: the three parts of the national identifier are reported INDIVIDUALLY rather
        //   than as one composed field, because the map declares them individually at
        //   app/cpy-bms/COACTUP.CPY L168, L174 and L180 and the reference normalises each on its own at
        //   app/cbl/COACTUPC.cbl L1233, L1240 and L1247. A single composed entry would tell the caller
        //   that the identifier is missing without saying which of the three inputs to correct.
        flagsByField.put("ssnPart1", flagFor(request.ssnPart1()));
        flagsByField.put("ssnPart2", flagFor(request.ssnPart2()));
        flagsByField.put("ssnPart3", flagFor(request.ssnPart3()));
        // WHY : Assumptions: the date of birth is likewise reported as its three map fields, at
        //   app/cpy-bms/COACTUP.CPY L186, L192 and L198, for the same reason. The composition into the
        //   ten-character stored form happens later and cannot report which part was absent.
        flagsByField.put("dateOfBirthYear", flagFor(request.dateOfBirthYear()));
        flagsByField.put("dateOfBirthMonth", flagFor(request.dateOfBirthMonth()));
        flagsByField.put("dateOfBirthDay", flagFor(request.dateOfBirthDay()));
        flagsByField.put("ficoCreditScore", flagFor(request.ficoCreditScore()));
        flagsByField.put("firstName", flagFor(request.firstName()));
        flagsByField.put("lastName", flagFor(request.lastName()));
        flagsByField.put("addressLine1", flagFor(request.addressLine1()));
        flagsByField.put("stateCode", flagFor(request.stateCode()));
        // WHY : Assumptions: the key is the request component's own name, city, and not the stored column
        //   it lands in. A per-field error identifies an INPUT so that a client can highlight the field
        //   the user typed into, and the client typed into a field the map labels city at
        //   app/cpy-bms/COACTUP.CPY L252; naming the third address line here would point at a column the
        //   client has no control labelled for.
        flagsByField.put("city", flagFor(request.city()));
        flagsByField.put("countryCode", flagFor(request.countryCode()));
        flagsByField.put("zipCode", flagFor(request.zipCode()));
        // WHY : Assumptions: only the FIRST telephone number is required. Its three parts are declared at
        //   app/cpy-bms/COACTUP.CPY L264, L270 and L276, and the second number's three at L288, L294 and
        //   L300 are omitted from this map because the stored column for the second number is nullable on
        //   the same footing as the middle name. Reporting it would refuse a customer with one telephone
        //   number, which the reference stores without complaint.
        flagsByField.put("phone1AreaCode", flagFor(request.phone1AreaCode()));
        flagsByField.put("phone1Prefix", flagFor(request.phone1Prefix()));
        flagsByField.put("phone1LineNumber", flagFor(request.phone1LineNumber()));
        flagsByField.put("eftAccountId", flagFor(request.eftAccountId()));
        flagsByField.put("primaryCardHolderIndicator",
                flagFor(request.primaryCardHolderIndicator()));

        // WHY : Assumptions: the entries are produced by the shared collector and then converted by the
        //   shared response type rather than assembled here. Transformation rule T2 gives one concept one
        //   definition, and the two records deliberately remain distinct types so the validation package
        //   never has to depend on the response package; the conversion is the documented cost of keeping
        //   that direction one-way.
        return ApiError.FieldError.fromAll(
                FieldValidationFlag.collectErrors(flagsByField, field -> FIELD_NEVER_SUPPLIED_HELP));
    }

    /**
     * Joins the three screen parts of the national identifier into the nine characters the record stores.
     *
     * <p>Assumptions: the reference performs this join with a GROUP ITEM rather than with arithmetic, and
     * that is why simple concatenation is exact rather than approximate. {@code app/cbl/COACTUPC.cbl}
     * L830 opens {@code ACUP-NEW-CUST-SSN-X} as a group whose three members are {@code PIC X(03)} at
     * L831, {@code PIC X(02)} at L832 and {@code PIC X(04)} at L833, and L834 and L835 redefine that same
     * storage as {@code PIC 9(09)}. The nine-digit numeric view IS the three parts laid end to end, so
     * joining them in order reproduces the stored value byte for byte. The single write at L4044 into
     * {@code CUST-UPDATE-SSN PIC 9(09)}, declared at L450, confirms the parts must already be joined by
     * the time the record is written.</p>
     *
     * <p>Assumptions: each part is required at EXACTLY its declared width, and this is where the rule
     * differs from the whole-field identifiers below. The three parts are declared with ALPHANUMERIC
     * pictures on the map -- {@code ACTSSN1I PIC X(3)} at {@code app/cpy-bms/COACTUP.CPY} L168,
     * {@code ACTSSN2I PIC X(2)} at L174 and {@code ACTSSN3I PIC X(4)} at L180 -- and an alphanumeric
     * field is left-justified and blank-filled, so a shorter part would carry a trailing space into the
     * middle of the nine characters. The group's numeric redefinition would then read a value with an
     * embedded space, which is not a number at all. Accepting a short part and padding it would therefore
     * manufacture an identifier the reference could never have stored.</p>
     *
     * <p>Assumptions: the view map holds the same value as a single {@code ACSTSSNI PIC X(12)} at
     * {@code app/cpy-bms/COACTVW.CPY} L132, which is twelve rather than nine because
     * {@code app/cbl/COACTVWC.cbl} L496 through L504 inserts two separators for display. That width is
     * the second of the four disagreements between the two output shapes and has no bearing on this
     * composition, which produces the STORED nine.</p>
     *
     * @param part1 the first screen part, required at exactly three characters, all digits
     * @param part2 the middle screen part, required at exactly two characters, all digits
     * @param part3 the last screen part, required at exactly four characters, all digits
     * @return the nine characters to protect and store, never {@code null}
     * @throws IllegalArgumentException if any part was never supplied, is not exactly its declared width,
     *     or holds a character that is not a digit
     */
    private static String nationalIdentifier(String part1, String part2, String part3) {
        String first = requiredDigitsAtExactWidth(part1, NATIONAL_IDENTIFIER_PART_1_WIDTH, "ssnPart1");
        String middle = requiredDigitsAtExactWidth(part2, NATIONAL_IDENTIFIER_PART_2_WIDTH, "ssnPart2");
        String last = requiredDigitsAtExactWidth(part3, NATIONAL_IDENTIFIER_PART_3_WIDTH, "ssnPart3");

        String composed = first + middle + last;
        // WHY : Assumptions: the width of the result is checked even though the three inputs were each
        //   checked, because the arithmetic 3 + 2 + 4 = 9 is the claim this method rests on and a change
        //   to any one constant would break it silently. The check costs nothing and turns a future
        //   mis-edit of a constant into an immediate refusal rather than into a stored identifier of the
        //   wrong length.
        if (composed.length() != NATIONAL_IDENTIFIER_WIDTH) {
            throw new IllegalArgumentException("the composed national identifier occupies "
                    + composed.length() + " characters but the reference field declares "
                    + NATIONAL_IDENTIFIER_WIDTH);
        }
        return composed;
    }

    /**
     * Rebuilds the date of birth from its three screen parts, restoring the two separators the record
     * carries and the eight-character before-image does not.
     *
     * <p>Refactoring Rationale: the reference cannot simply move the three parts across, because the form
     * it compares against and the form it stores are DIFFERENT WIDTHS, and that asymmetry is what forces
     * a rebuild rather than a transfer. {@code app/cbl/COACTUPC.cbl} L837 declares
     * {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD PIC X(08)}, redefined at L838 and L839 into the three parts
     * {@code PIC X(4)} at L840, {@code PIC X(2)} at L841 and {@code PIC X(2)} at L842, and the
     * before-image at L746 through L751 is the same eight-character shape. The stored field is
     * {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy} L19 and
     * {@code CUST-UPDATE-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cbl/COACTUPC.cbl} L452. Eight characters
     * of parts plus two separators is ten, which is why L4047 through L4052 of that program assembles the
     * value with an explicit {@code STRING} carrying a separator between each part rather than with a
     * move.</p>
     *
     * <p>Assumptions: the composed text is parsed into a date rather than stored as text, and the
     * reference's own ordering is what makes that behaviour-preserving. The stored order is year, month,
     * day, so a lexical comparison of two stored values orders them identically to a date comparison,
     * which is the property the column type and this member type both rely on. A representation that
     * reordered the parts, or that stored them as three fields, would break every comparison the
     * reference makes by slicing the ten characters.</p>
     *
     * <p>Assumptions: each part is required at exactly its declared width, for the same reason as the
     * identifier parts above -- {@code DOBYEARI PIC X(4)} at {@code app/cpy-bms/COACTUP.CPY} L186,
     * {@code DOBMONI PIC X(2)} at L192 and {@code DOBDAYI PIC X(2)} at L198 are alphanumeric, so a short
     * part would shift the separator positions and the parse would then read a different field.</p>
     *
     * @param year the four-character year part, required, all digits
     * @param month the two-character month part, required, all digits
     * @param day the two-character day part, required, all digits
     * @return the date of birth, never {@code null}
     * @throws ClientInputException if any part was never supplied, is not exactly its declared width,
     *     holds a character that is not a digit, or if the three together do not name a real calendar
     *     date -- all of which are refusals of a value the caller supplied
     * @throws IllegalArgumentException if the three validated parts compose to something other than the
     *     declared width, which is an internal invariant of this method's own arithmetic rather than
     *     anything the caller did, and is therefore deliberately NOT a client-input refusal
     */
    private static LocalDate dateOfBirth(String year, String month, String day) {
        // WHY : Refactoring Rationale: the three identities are constants rather than literals now that
        //   the combination refusal below names the same three. Two literal sets naming one trio is how
        //   a per-part refusal and a cross-part refusal end up keyed differently for one control.
        String yearPart = requiredDigitsAtExactWidth(year, DATE_OF_BIRTH_YEAR_WIDTH,
                DATE_OF_BIRTH_YEAR_PROPERTY);
        String monthPart = requiredDigitsAtExactWidth(month, DATE_OF_BIRTH_MONTH_WIDTH,
                DATE_OF_BIRTH_MONTH_PROPERTY);
        String dayPart = requiredDigitsAtExactWidth(day, DATE_OF_BIRTH_DAY_WIDTH,
                DATE_OF_BIRTH_DAY_PROPERTY);

        // WHY : Assumptions: the separators are re-inserted in the same two positions the reference's
        //   STRING statement writes them, which is what makes the ten characters below identical to the
        //   ten the reference stores. Composing without them would produce the eight-character
        //   before-image form instead, which is a real shape in the reference and the WRONG one to store.
        String composed = yearPart + DATE_PART_SEPARATOR + monthPart + DATE_PART_SEPARATOR + dayPart;
        if (composed.length() != DATE_OF_BIRTH_WIDTH) {
            throw new IllegalArgumentException("the composed date of birth occupies " + composed.length()
                    + " characters but the reference field declares " + DATE_OF_BIRTH_WIDTH);
        }

        try {
            return LocalDate.parse(composed);
        } catch (DateTimeParseException notADate) {
            // WHY : Assumptions: the parse failure is re-raised as an argument refusal and the original is
            //   kept as the cause. The three parts were already proven to be digits at the declared
            //   widths, so the only remaining way to arrive here is a value that is well formed and not a
            //   real calendar date, which is a client mistake rather than a server fault; keeping the
            //   cause preserves which position the parser objected to, and discarding it would leave a
            //   diagnostic naming the whole field.
            // WHY : Refactoring Rationale: a client-input refusal rather than a bare argument one, which
            //   is what this site's own note already argued for -- it recorded that the only way to
            //   arrive here is a well-formed value that is not a real calendar date, "which is a client
            //   mistake rather than a server fault" -- while the type it raised had the shared advice
            //   answer HTTP 500. The type now matches the reading.
            // WHY : Assumptions: all three parts are named, because the parts are individually valid
            //   and only their COMBINATION is not: a thirty-first of February is refused without either
            //   part being wrong on its own, so naming one would point a caller at a value it need not
            //   change. Trade-offs: the parse exception is dropped as a cause rather than kept, because
            //   the advice logs the message of what it renders and the platform's parse message quotes
            //   the composed date; the position it identifies is not worth carrying a caller's value
            //   into a log for.
            throw new ClientInputException(ApiError.CODE_VALIDATION,
                    List.of(DATE_OF_BIRTH_YEAR_PROPERTY, DATE_OF_BIRTH_MONTH_PROPERTY,
                            DATE_OF_BIRTH_DAY_PROPERTY),
                    FieldValidationFlag.NOT_OK,
                    "the supplied date of birth parts do not name a real calendar date");
        }
    }

    /**
     * Composes one telephone number from its three screen parts into the punctuated fifteen characters
     * the record stores.
     *
     * <p>Assumptions: the punctuation is PART OF THE STORED VALUE rather than a display concern, and the
     * evidence for that forms a closed loop across two independent declarations. The before-image
     * redefines the fifteen stored characters at {@code app/cbl/COACTUPC.cbl} L722 through L731 as a
     * one-character {@code FILLER} at L725, three characters at L726, a {@code FILLER} at L727, three
     * characters at L728, a {@code FILLER} at L729, four characters at L730 and a two-character
     * {@code FILLER} at L731, which sums 1 + 3 + 1 + 3 + 1 + 4 + 2 to exactly fifteen; L732 through L741
     * declares the second number identically, with its separator slots at L735, L737 and L739 and its
     * tail at L741, and L810 through L819 and L820 through L829 repeat the same geometry on the
     * new-value side. The write-back then fills precisely those three single-character slots with
     * literals: L4027 emits the opening character, L4029 the closing one and L4031 the separator, into
     * {@code CUST-UPDATE-PHONE-NUM-1 PIC X(15)} declared at L448, and L4035 through L4041 does the same
     * for the second number. Three separator slots predicted by a redefinition and three literals emitted
     * by a {@code STRING} is the loop closing.</p>
     *
     * <p>Assumptions: the composed value occupies thirteen of the fifteen characters and the remaining two
     * stay blank, which is exactly the tail the redefinition declares. That is also why the account view
     * map declares {@code ACSPHN1I PIC X(13)} at {@code app/cpy-bms/COACTVW.CPY} L204 and
     * {@code ACSPHN2I PIC X(13)} at L216: a third, unrelated declaration agreeing with the same
     * arithmetic. Storing the digits without punctuation would be the tidier representation and is
     * refused, because a consumer reading the record at fixed offsets would then find its fields shifted
     * and the reference's own report and screen renderings would disagree with the stored value.</p>
     *
     * <p>Trade-offs: a number whose three parts were all never supplied yields no value at all rather
     * than fifteen blanks. The stored column is nullable and the reference cannot express absence -- an
     * alphanumeric field holds blanks either way, which is why {@code app/cbl/COACTUPC.cbl} L1350 through
     * L1355 converts the marker character and a field of spaces into low values before any rule sees them
     * -- so publishing punctuation around empty parts would assert a stored telephone number consisting
     * of separators alone. The cost is that a caller cannot distinguish a never-supplied number from one
     * the reference stored blank, which is a distinction the reference never made either.</p>
     *
     * @param areaCode the three-character area-code part, or a never-supplied value
     * @param prefix the three-character exchange-prefix part, or a never-supplied value
     * @param lineNumber the four-character line-number part, or a never-supplied value
     * @param propertyStem the request-property stem the three parts share, being {@code phone1} or
     *     {@code phone2}, from which each part's own property name is composed so that a refusal names
     *     the control the caller submitted rather than the column the value lands in
     * @return the punctuated fifteen characters to store, or {@code null} when all three parts were
     *     never supplied
     * @throws com.carddemo.common.error.ClientInputException if some but not all parts were supplied,
     *     or if a supplied part is not exactly its declared width
     */
    private static String telephoneNumber(String areaCode, String prefix, String lineNumber,
            String propertyStem) {

        // WHY : Refactoring Rationale: the three part identities are COMPOSED from the stem rather than
        //   passed in, because the request record names them exactly stem + AreaCode, stem + Prefix and
        //   stem + LineNumber -- phone1AreaCode, phone1Prefix, phone1LineNumber -- so composing them
        //   keeps one argument where six would otherwise be needed and makes the two call sites
        //   differ only in the stem. Alternatives Considered: a three-element list per call site, which
        //   is explicit but repeats the same three suffixes at both sites and lets one site drift.
        String areaCodeProperty = propertyStem + TELEPHONE_AREA_CODE_PROPERTY_SUFFIX;
        String prefixProperty = propertyStem + TELEPHONE_PREFIX_PROPERTY_SUFFIX;
        String lineNumberProperty = propertyStem + TELEPHONE_LINE_NUMBER_PROPERTY_SUFFIX;

        boolean areaCodeAbsent = FieldValidationFlag.isNeverSupplied(areaCode);
        boolean prefixAbsent = FieldValidationFlag.isNeverSupplied(prefix);
        boolean lineNumberAbsent = FieldValidationFlag.isNeverSupplied(lineNumber);

        if (areaCodeAbsent && prefixAbsent && lineNumberAbsent) {
            return null;
        }

        // WHY : Assumptions: a PARTLY supplied number is refused rather than completed with blanks,
        //   because there is no position in the fifteen characters where a missing part could be
        //   represented. Every one of the three parts occupies a fixed span between two separator slots,
        //   so a blank part would store a value whose punctuation says a number is present while the
        //   digits say it is not, and the redefinition at app/cbl/COACTUPC.cbl L725 through L730 offers no
        //   alternative arrangement. Refusing names the mistake at the boundary instead of storing an
        //   unreadable number.
        if (areaCodeAbsent || prefixAbsent || lineNumberAbsent) {
            // WHY : Refactoring Rationale: this is raised as a CLIENT-INPUT refusal and not as a bare
            //   argument one, so that the shared advice answers HTTP 400 with a per-field entry instead
            //   of HTTP 500 with an abend block. The advice's own recorded contract draws that line by
            //   the exception's declared type: a bare argument refusal names an internal invariant and
            //   is deliberately answered as a server fault, while a value that came from OUTSIDE the
            //   process is answered as the caller's to correct. A partly typed telephone number is
            //   plainly the latter.
            // WHY : Assumptions: only the ABSENT parts are named, not all three. The remedy is to
            //   supply what is missing, so naming a part the caller already filled would ask for an
            //   edit that is not needed and would mark a satisfied control as at fault. The state is
            //   the blank one, which is the reference's own reading of an empty control -- its
            //   templated highlight draws an asterisk for exactly that case.
            List<String> absentParts = new ArrayList<>(TELEPHONE_PART_COUNT);
            if (areaCodeAbsent) {
                absentParts.add(areaCodeProperty);
            }
            if (prefixAbsent) {
                absentParts.add(prefixProperty);
            }
            if (lineNumberAbsent) {
                absentParts.add(lineNumberProperty);
            }
            throw new ClientInputException(ApiError.CODE_VALIDATION, List.copyOf(absentParts),
                    FieldValidationFlag.BLANK,
                    "the telephone number beginning " + propertyStem
                            + " was supplied in part only; all three parts are required together"
                            + " because each occupies a fixed span between the punctuation the record"
                            + " stores");
        }

        // WHY : Assumptions: the three parts are checked for WIDTH ONLY and not for digits, because the
        //   stored field has no numeric view for a non-digit to invalidate: CUST-PHONE-NUM-1 PIC X(15) at
        //   app/cpy/CVCUS01Y.cpy L15 is alphanumeric, and every part of its redefinition at
        //   app/cbl/COACTUPC.cbl L726, L728 and L730 is declared PIC X(n) rather than PIC 9(n). Contrast
        //   the national identifier, whose three parts compose into storage redefined as PIC 9(09) at
        //   L834 and L835 and which therefore does carry a digit obligation. Whether an area code names a
        //   real exchange is a value-domain question the reference answers against its own lookup asset,
        //   and that answer belongs to the service layer; testing it here would put a business rule in the
        //   mapping layer.
        String area = requiredAtExactWidth(areaCode, TELEPHONE_AREA_CODE_WIDTH, areaCodeProperty);
        String exchange = requiredAtExactWidth(prefix, TELEPHONE_PREFIX_WIDTH, prefixProperty);
        String line =
                requiredAtExactWidth(lineNumber, TELEPHONE_LINE_NUMBER_WIDTH, lineNumberProperty);

        String composed = TELEPHONE_OPENING_PUNCTUATION + area + TELEPHONE_CLOSING_PUNCTUATION
                + exchange + TELEPHONE_SEPARATOR + line;
        // WHY : Assumptions: the composed thirteen characters are padded out to the stored fifteen rather
        //   than left short, so the value this class produces already matches the width the record
        //   declares and the outbound projections do not have to guess whether a stored value was padded
        //   on load or not. The two characters added are exactly the FILLER PIC X(2) tail at
        //   app/cbl/COACTUPC.cbl L731, so nothing is invented.
        return padToWidth(composed, TELEPHONE_RECORD_WIDTH, propertyStem);
    }

    /**
     * Renders a stored telephone number at the RECORD width, punctuation and trailing pad included.
     *
     * <p>Assumptions: the value is padded out to fifteen rather than trimmed, because fifteen is what the
     * record declares at {@code app/cpy/CVCUS01Y.cpy} L15 and L16 and the two trailing characters are a
     * declared part of the layout rather than incidental whitespace. A trimmed thirteen-character
     * rendering would be indistinguishable from the SCREEN width, which is the third and fourth of the
     * four disagreements between this class's two output shapes, and the whole reason two shapes exist is
     * that those widths must stay distinguishable.</p>
     *
     * @param stored the stored value, or {@code null} when the optional column holds nothing
     * @param field the column name, so a refusal names which value was at fault
     * @return the value at exactly fifteen characters, or {@code null} when nothing is stored
     * @throws IllegalStateException if the stored value is wider than the fifteen characters the record
     *     declares, because truncating it would silently publish the screen width in the record shape
     */
    private static String storedTelephoneNumber(String stored, String field) {
        return exactWidth(stored, TELEPHONE_RECORD_WIDTH, field, false);
    }

    /**
     * Renders a stored telephone number at the SCREEN width, dropping exactly the declared trailing pad.
     *
     * <p>Assumptions: the thirteen characters kept are the LEADING thirteen, which is what the reference's
     * direct move produces: {@code app/cbl/COACTVWC.cbl} L517 and L518 move the fifteen-character stored
     * fields into {@code ACSPHN1I PIC X(13)} at {@code app/cpy-bms/COACTVW.CPY} L204 and
     * {@code ACSPHN2I PIC X(13)} at L216, and an alphanumeric move into a narrower field keeps the
     * leftmost characters. Because the composed form occupies the first thirteen and the trailing two are
     * the {@code FILLER PIC X(2)} declared at {@code app/cbl/COACTUPC.cbl} L731 and L741, this narrowing
     * discards padding rather than data -- which is why the reference could afford to do it directly.</p>
     *
     * @param stored the stored value, or {@code null} when the optional column holds nothing
     * @param field the column name, so a refusal names which value was at fault
     * @return the leading thirteen characters, or {@code null} when nothing is stored
     * @throws IllegalStateException if the stored value is wider than the fifteen characters the record
     *     declares
     */
    private static String screenTelephoneNumber(String stored, String field) {
        String atRecordWidth = exactWidth(stored, TELEPHONE_RECORD_WIDTH, field, false);
        if (atRecordWidth == null) {
            return null;
        }
        return atRecordWidth.substring(0, TELEPHONE_SCREEN_WIDTH);
    }


    /**
     * Narrows the stored postal code to the width the account view screen shows.
     *
     * <p>Assumptions: five of the ten characters are published on this shape and the loss is reproduced
     * rather than avoided, because it is the reference's own behaviour on this screen:
     * {@code app/cbl/COACTVWC.cbl} L515 moves {@code CUST-ADDR-ZIP PIC X(10)} from
     * {@code app/cpy/CVCUS01Y.cpy} L14 directly into {@code ACSZIPCI PIC X(5)} at
     * {@code app/cpy-bms/COACTVW.CPY} L186, keeping the leftmost five. This is the FIRST of the four
     * width disagreements and the only one of the four that loses characters a value can actually
     * occupy. Publishing all ten through this contract would show a value the screen it mirrors has never
     * shown; the record shape carries the full ten, so nothing is unreachable.</p>
     *
     * @param stored the stored postal code, which the schema declares {@code NOT NULL}
     * @return the leading five characters, never {@code null}
     * @throws IllegalStateException if the column is absent or is wider than the ten characters the
     *     record declares
     */
    private static String screenPostalCode(String stored) {
        String atRecordWidth = exactWidth(stored, POSTAL_CODE_RECORD_WIDTH, "addr_zip", true);
        return atRecordWidth.substring(0, POSTAL_CODE_SCREEN_WIDTH);
    }

    /**
     * Renders the customer identifier as the nine digits both the record and both screens declare.
     *
     * <p>Assumptions: the identifier crosses the boundary as text at its declared width, leading zeros
     * included, and the reference itself treats it that way rather than as a quantity. Inside the
     * before-image group it is declared {@code ACUP-OLD-CUST-ID-X PIC X(09)} at
     * {@code app/cbl/COACTUPC.cbl} L710 and only THEN redefined as {@code PIC 9(09)} at L711 and L712 --
     * characters on the wire, a number only where arithmetic needs one. The screen fields agree at nine:
     * {@code ACSTNUMI PIC X(9)} at {@code app/cpy-bms/COACTVW.CPY} L126 and at
     * {@code app/cpy-bms/COACTUP.CPY} L162, so this value is not among the four width disagreements.</p>
     *
     * @param value the stored identifier, which is this row's primary key
     * @return the identifier as exactly nine digits, zero-padded on the left, never {@code null}
     * @throws IllegalStateException if the identifier is absent, or occupies more digits than the nine the
     *     reference declares, the latter because truncating it would publish a different customer's
     *     identifier
     */
    private static String customerIdentifierDigits(Long value) {
        if (value == null) {
            throw new IllegalStateException(
                    "customer_id is null, but it is this entity's primary key");
        }
        return leftZeroPadded(String.valueOf(value.longValue()), CUSTOMER_IDENTIFIER_WIDTH,
                "customer_id");
    }

    /**
     * Reads the customer identifier the update request supplies into the value the row keys on.
     *
     * <p>Assumptions: fewer than nine digits are accepted and zero-extended, and this is the one place
     * the rule deliberately differs from the composition parts above. The stored picture is NUMERIC,
     * {@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy} L5, and a numeric picture is
     * right-justified with zero fill, so a shorter value moved into it acquires leading zeros rather than
     * trailing spaces. Zero-extending here therefore reproduces what the reference does with the same
     * input, whereas the identifier and date parts are ALPHANUMERIC pictures whose fill character is a
     * space and for which the same leniency would embed a space inside a number.</p>
     *
     * @param text the identifier as the request supplies it, digits only; must be supplied
     * @return the identifier value the row keys on, never {@code null}
     * @throws IllegalArgumentException if the value was never supplied, holds a character that is not a
     *     digit, or occupies more than the nine digits the reference declares
     */
    private static Long customerIdentifierValue(String text) {
        String digits = requiredDigitsAtMostWidth(text, CUSTOMER_IDENTIFIER_WIDTH, "customerId");
        // WHY : Assumptions: the parse cannot overflow and no overflow branch is written, because the
        //   value was just proven to be at most nine digits and the widest nine-digit value is far inside
        //   the range this member type carries. A defensive catch here would be unreachable code, which
        //   transformation rule T9 treats as a behavioural claim the reference does not make.
        return Long.valueOf(Long.parseLong(digits));
    }

    /**
     * Renders the credit score as the three digits the record and both screens declare.
     *
     * <p>Assumptions: the score crosses the boundary as text rather than as a number, on the same footing
     * as the identifier above and for the same documented reason: the reference declares
     * {@code ACUP-OLD-CUST-FICO-SCORE-X PIC X(03)} at {@code app/cbl/COACTUPC.cbl} L754 and redefines it
     * as {@code PIC 9(03)} at L755 and L756. The screen fields agree at three, {@code ACSTFCOI PIC X(3)}
     * at {@code app/cpy-bms/COACTVW.CPY} L144 and at {@code app/cpy-bms/COACTUP.CPY} L204, so the score is
     * not among the four width disagreements either. It is a bounded small integer and is not an amount:
     * this record declares no amount at all.</p>
     *
     * @param value the stored score, which the schema declares {@code NOT NULL}
     * @return the score as exactly three digits, zero-padded on the left, never {@code null}
     * @throws IllegalStateException if the stored score is negative or occupies more than the three digits
     *     the reference declares
     */
    private static String creditScoreDigits(short value) {
        // WHY : Assumptions: a negative stored score is refused rather than rendered, because
        //   CUST-FICO-CREDIT-SCORE PIC 9(03) at app/cpy/CVCUS01Y.cpy L22 is an UNSIGNED picture and has no
        //   position for a sign. Rendering one would produce a four-character value in a three-character
        //   contract; refusing names the row that holds it. No upper range check beyond the declared width
        //   is applied, deliberately: the reference bounds this value at three digits and nothing
        //   narrower, and a plausible-looking range would reject rows the migration load actually carries.
        if (value < 0) {
            throw new IllegalStateException("fico_credit_score holds a negative value, but the reference"
                    + " field is declared with an unsigned picture that has no position for a sign");
        }
        return leftZeroPadded(Integer.toString(value), CREDIT_SCORE_WIDTH, "fico_credit_score");
    }

    /**
     * Reads the credit score the update request supplies into the value the row stores.
     *
     * <p>Assumptions: fewer than three digits are accepted and zero-extended, for the numeric-picture
     * reason recorded on the customer identifier above -- {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at
     * {@code app/cpy/CVCUS01Y.cpy} L22 is right-justified with zero fill.</p>
     *
     * @param text the score as the request supplies it, digits only; must be supplied
     * @return the score value the row stores
     * @throws IllegalArgumentException if the value was never supplied, holds a character that is not a
     *     digit, or occupies more than the three digits the reference declares
     */
    private static short creditScoreValue(String text) {
        String digits = requiredDigitsAtMostWidth(text, CREDIT_SCORE_WIDTH, "ficoCreditScore");
        // WHY : Assumptions: the parse cannot overflow, because at most three digits is at most 999 and
        //   that is well inside the sixteen-bit range the column and this member type both carry.
        return Short.parseShort(digits);
    }

    /**
     * Renders the date of birth as the ten characters the record and both view fields declare.
     *
     * <p>Assumptions: the rendering is the year-month-day form with two separators, which both the record
     * and the account view map hold: {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at
     * {@code app/cpy/CVCUS01Y.cpy} L19 and {@code ACSTDOBI PIC X(10)} at
     * {@code app/cpy-bms/COACTVW.CPY} L138, connected by the direct move at
     * {@code app/cbl/COACTVWC.cbl} L507. The width check below is not decoration: a year outside four
     * digits renders in a different form, and publishing it would break every consumer that reads the
     * field by position.</p>
     *
     * @param value the stored date of birth, which the schema declares {@code NOT NULL}
     * @return the date as exactly ten characters, never {@code null}
     * @throws IllegalStateException if the column is absent, or renders to a width other than the ten
     *     characters the reference declares
     */
    private static String dateOfBirthText(LocalDate value) {
        if (value == null) {
            throw new IllegalStateException("dob is null, but it is declared NOT NULL in the schema"
                    + " and nullable = false on the entity");
        }
        String rendered = value.toString();
        if (rendered.length() != DATE_OF_BIRTH_WIDTH) {
            throw new IllegalStateException("the stored date of birth renders to " + rendered.length()
                    + " characters but the reference field declares " + DATE_OF_BIRTH_WIDTH);
        }
        return rendered;
    }

    /**
     * Renders a stored code field at exactly the width its {@code PICTURE} clause declares.
     *
     * <p>Assumptions: a value shorter than the field is PADDED rather than published short, because a code
     * field's declared width is part of its contract rather than a maximum. The distinction against the
     * descriptive helper below is the one the entity states for itself: a name is stored trimmed because
     * its blanks are padding, while the state code, country code, postal code, telephone numbers,
     * transfer-account identifier and primary-holder indicator are fixed-width codes whose padding is part
     * of the value a consumer reads by position.</p>
     *
     * @param value the stored value, which may be {@code null} for an optional column
     * @param width the width the reference declares for the field
     * @param field the column name, so a refusal names which value was at fault
     * @param required whether the schema declares the column {@code NOT NULL}
     * @return the value at exactly {@code width} characters, or {@code null} when an optional column holds
     *     nothing
     * @throws IllegalStateException if a required column is absent, or if the stored value is wider than
     *     the declared width, the latter because truncating it would publish a value the record does not
     *     hold
     */
    private static String exactWidth(String value, int width, String field, boolean required) {
        if (value == null) {
            if (required) {
                throw new IllegalStateException(field + " is null, but it is declared NOT NULL in the"
                        + " schema and nullable = false on the entity");
            }
            return null;
        }
        if (value.length() > width) {
            throw new IllegalStateException("the stored value of " + field + " occupies "
                    + value.length() + " characters but the reference field declares " + width
                    + "; truncating it would publish a value the record does not hold");
        }
        return padToWidth(value, width, field);
    }

    /**
     * Renders a stored descriptive field as it is held, refusing only a value wider than its declared
     * width.
     *
     * <p>Assumptions: no padding is added, because a descriptive value's declared width is a maximum. A
     * name shorter than twenty-five characters and an address line shorter than fifty are shorter values
     * rather than defective ones, the entity records that their trailing blanks in the fixed-width record
     * are padding that the load does not store, and re-adding them here would put back exactly the
     * characters the load removed. The upper bound is still enforced, because a value wider than the
     * reference declares could not have come from the reference and would overflow the field on any path
     * that wrote it back.</p>
     *
     * @param value the stored value, which may be {@code null} for an optional column
     * @param width the width the reference declares for the field
     * @param field the column name, so a refusal names which value was at fault
     * @param required whether the schema declares the column {@code NOT NULL}
     * @return the value as stored, or {@code null} when an optional column holds nothing
     * @throws IllegalStateException if a required column is absent, or if the stored value is wider than
     *     the declared width
     */
    private static String atMostWidth(String value, int width, String field, boolean required) {
        if (value == null) {
            if (required) {
                throw new IllegalStateException(field + " is null, but it is declared NOT NULL in the"
                        + " schema and nullable = false on the entity");
            }
            return null;
        }
        if (value.length() > width) {
            throw new IllegalStateException("the stored value of " + field + " occupies "
                    + value.length() + " characters but the reference field declares " + width);
        }
        return value;
    }

    /**
     * Accepts a required descriptive value from a request, bounded by the width the reference declares.
     *
     * <p>Assumptions: the refusal is an argument refusal rather than a state refusal, and the split is
     * deliberate throughout this class: a value arriving from a request is the client's, so a violation is
     * an argument problem, while a value arriving from a stored row is this system's, so a violation is a
     * state problem. Keeping the two apart is what lets the shared exception advice answer one as a client
     * error and the other as a server fault without inspecting messages.</p>
     *
     * @param value the value the request supplies
     * @param width the width the reference declares for the field
     * @param field the request component name, so a refusal names the input the client can correct
     * @return the value as supplied, never {@code null}
     * @throws ClientInputException if the value was never supplied or is wider than the declared width
     */
    private static String requiredAtMostWidth(String value, int width, String field) {
        String supplied = requiredValue(value, field);
        if (supplied.length() > width) {
            // WHY : Assumptions: a client-input refusal in the NOT-OK state, not the blank one, for the
            //   reason recorded on requiredValue: a value was supplied and was refused, so the
            //   reference recolours the control rather than marking it empty. The width itself is
            //   reported because it is the one fact that makes the refusal actionable, and it discloses
            //   nothing -- it is published in the request record's own documentation.
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    FieldValidationFlag.NOT_OK, "the supplied value of " + field + " occupies "
                    + supplied.length() + " characters but the reference field declares " + width);
        }
        return supplied;
    }

    /**
     * Accepts an optional descriptive value from a request, bounded by the width the reference declares.
     *
     * <p>Assumptions: a never-supplied value becomes no value rather than an empty one, so that the
     * nullable column records the absence the reference could not express. The shared never-supplied test
     * is what folds a missing component, an empty one and a run of pad characters into the single answer
     * the reference gives through its own normalisation.</p>
     *
     * @param value the value the request supplies, which may be absent
     * @param width the width the reference declares for the field
     * @param field the request component name, so a refusal names the input the client can correct
     * @return the value as supplied, or {@code null} when it was never supplied
     * @throws ClientInputException if a supplied value is wider than the declared width
     */
    private static String optionalAtMostWidth(String value, int width, String field) {
        if (FieldValidationFlag.isNeverSupplied(value)) {
            return null;
        }
        if (value.length() > width) {
            // WHY : Assumptions: an OPTIONAL field that was supplied too wide is refused on the same
            //   terms as a mandatory one. Optionality governs whether a value must arrive, not whether
            //   an arriving value may exceed the width the record declares, and silently truncating
            //   here would store a value the caller never typed.
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    FieldValidationFlag.NOT_OK, "the supplied value of " + field + " occupies "
                    + value.length() + " characters but the reference field declares " + width);
        }
        return value;
    }

    /**
     * Accepts a required code value from a request and stores it at exactly its declared width.
     *
     * <p>Assumptions: the value is padded on the RIGHT, because every field this helper serves is declared
     * with an alphanumeric picture and an alphanumeric transfer left-justifies and blank-fills. That is
     * what the reference's own moves do into {@code ACUP-NEW-CUST-ADDR-STATE-CD},
     * {@code ACUP-NEW-CUST-ADDR-COUNTRY-CD}, {@code ACUP-NEW-CUST-EFT-ACCOUNT-ID PIC X(10)} at
     * {@code app/cbl/COACTUPC.cbl} L843 and the primary-holder indicator, so the padding direction is
     * taken from the reference rather than chosen. Padding on the left instead would shift every character
     * of a short code and store a different code.</p>
     *
     * @param value the value the request supplies
     * @param width the width the reference declares for the field
     * @param field the request component name, so a refusal names the input the client can correct
     * @return the value at exactly {@code width} characters, never {@code null}
     * @throws IllegalArgumentException if the value was never supplied or is wider than the declared width
     */
    private static String requiredExactWidth(String value, int width, String field) {
        return padToWidth(requiredAtMostWidth(value, width, field), width, field);
    }

    /**
     * Extends a value on the right with blanks until it occupies exactly the declared width.
     *
     * <p>Assumptions: this is the one place a pad is applied, so the direction is stated once rather than
     * at each call site. Blanks on the right reproduce an alphanumeric transfer into a field of the target
     * width, which is what every widening in this class is: five postal-code characters into ten at
     * {@code app/cbl/COACTUPC.cbl} L809 and L1354, thirteen composed telephone characters into fifteen at
     * L448, and a shorter government-issued identifier into the twenty at L836.</p>
     *
     * @param value the value to extend; must not be {@code null} and must not exceed {@code width}
     * @param width the width the reference declares for the field
     * @param field the field name, so a refusal names which value was at fault
     * @return the value at exactly {@code width} characters, never {@code null}
     * @throws IllegalArgumentException if the value is wider than the declared width, which callers are
     *     expected to have already refused and which is re-checked here so that the pad arithmetic below
     *     can never be asked to repeat a negative number of characters
     */
    private static String padToWidth(String value, int width, String field) {
        if (value.length() > width) {
            throw new IllegalArgumentException("the value of " + field + " occupies " + value.length()
                    + " characters but the reference field declares " + width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Extends a digit string on the left with zeros until it occupies exactly the declared width.
     *
     * <p>Assumptions: zeros on the LEFT, because both fields this helper serves are declared with numeric
     * pictures -- {@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy} L5 and
     * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at L22 -- and a numeric picture is right-justified with
     * zero fill. This is the mirror of the blank-on-the-right rule for alphanumeric fields, and using the
     * wrong one of the two would change the value rather than merely its appearance.</p>
     *
     * @param digits the digit string to extend; must not be {@code null}
     * @param width the width the reference declares for the field
     * @param field the field name, so a refusal names which value was at fault
     * @return the digits at exactly {@code width} characters, never {@code null}
     * @throws IllegalStateException if the digit string is longer than the declared width, because
     *     truncating an identifier or a score would publish a different value entirely
     */
    private static String leftZeroPadded(String digits, int width, String field) {
        if (digits.length() > width) {
            throw new IllegalStateException("the stored value of " + field + " occupies "
                    + digits.length() + " digits but the reference field declares " + width
                    + "; truncating it would publish a different value");
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Accepts a required value from a request, refusing one that was never supplied.
     *
     * <p>Assumptions: absence is decided by the shared never-supplied test rather than by a general blank
     * check, because the reference spells absence in two specific ways and a general check gets both
     * wrong. {@code app/cbl/COACTUPC.cbl} L1224 through L1229 folds the marker character and a field of
     * spaces into low values before any rule runs, so a null, an empty value and a run of pad characters
     * all describe a field the user never filled in, while other whitespace does not.</p>
     *
     * @param value the value the request supplies, which may be absent
     * @param field the request component name, so a refusal names the input the client can correct
     * @return the value as supplied, never {@code null}
     * @throws ClientInputException if the value was never supplied
     */
    private static String requiredValue(String value, String field) {
        if (FieldValidationFlag.isNeverSupplied(value)) {
            // WHY : Refactoring Rationale: a client-input refusal rather than a bare argument one, so
            //   the shared advice answers HTTP 400 with a per-field entry rather than HTTP 500 with an
            //   abend block. That advice draws the line by declared type and states the rule plainly: a
            //   service that wants the 400 shape for a value that came from outside the process raises
            //   this type. An omitted mandatory field is the most ordinary caller mistake there is, and
            //   answering it as a server fault told the caller the service had failed while routing an
            //   empty form control into the channel that is supposed to mean an abend.
            // WHY : Assumptions: the state is the BLANK one, which is the direct target form of the
            //   reference's FLG-*-BLANK conditions -- its templated highlight at
            //   app/cpy/CSSETATY.cpy L17 through L27 moves an asterisk into a field that is blank and
            //   only recolours one whose value was refused, so the two states are the reference's own
            //   distinction rather than an addition.
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    FieldValidationFlag.BLANK,
                    "no value was supplied for " + field + ", which the reference requires");
        }
        return value;
    }

    /**
     * Accepts a required value from a request at EXACTLY the width its screen field declares.
     *
     * <p>Assumptions: exact width is required for every part of a composed value, and the reason is
     * positional rather than defensive. Each part is declared with an alphanumeric picture on the update
     * map, so a shorter part would be blank-filled on the right and its blank would land INSIDE the
     * composed value -- between two characters of the national identifier, between a separator and a date
     * part, or between a telephone separator and the digits it separates. The composed result would then
     * be well formed in width and wrong in content, which is exactly the failure a width check at the
     * boundary exists to prevent.</p>
     *
     * @param value the value the request supplies
     * @param width the width the reference declares for the screen field
     * @param field the request component name, so a refusal names the input the client can correct
     * @return the value as supplied, never {@code null}
     * @throws ClientInputException if the value was never supplied or is not exactly the declared
     *     width
     */
    private static String requiredAtExactWidth(String value, int width, String field) {
        String supplied = requiredValue(value, field);
        if (supplied.length() != width) {
            // WHY : Assumptions: a client-input refusal in the NOT-OK state, for the reason recorded on
            //   requiredAtMostWidth. This helper is the one an exactly-sized PART of a composed value
            //   goes through, so its refusal is what a caller sees for a two-digit area code.
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    FieldValidationFlag.NOT_OK, "the supplied value of " + field + " occupies "
                    + supplied.length() + " characters but the reference field declares exactly " + width
                    + "; a shorter part would place a pad character inside the composed value");
        }
        return supplied;
    }

    /**
     * Accepts a required digit value from a request at EXACTLY the width its screen field declares.
     *
     * <p>Assumptions: the digit test is applied ONLY where the composed value has a numeric view, and
     * that distinction is what keeps this class clear of a value-domain rule. The three parts of the
     * national identifier compose into storage the reference redefines as {@code PIC 9(09)} at
     * {@code app/cbl/COACTUPC.cbl} L834 and L835, and the three parts of the date of birth compose into a
     * value that is read as a calendar date, so in both cases a non-digit would make the composed result
     * unreadable in its own declared terms -- a representation fact rather than a judgement about
     * plausibility.</p>
     *
     * <p>Assumptions: the telephone parts deliberately do NOT come through here, and the omission is a
     * finding rather than an oversight. {@code CUST-PHONE-NUM-1 PIC X(15)} at
     * {@code app/cpy/CVCUS01Y.cpy} L15 is alphanumeric throughout, its redefinition at
     * {@code app/cbl/COACTUPC.cbl} L722 through L731 declares every part {@code PIC X(n)}, and the screen
     * parts at {@code app/cpy-bms/COACTUP.CPY} L264, L270 and L276 are alphanumeric as well, so the
     * composed fifteen characters have no numeric view for a non-digit to invalidate. Whether an area code
     * is a real one is a value-domain question the reference answers against its own lookup asset, and
     * that answer belongs to the service layer; applying a digit test here would move a business rule into
     * the mapping layer under cover of a width check.</p>
     *
     * @param value the value the request supplies
     * @param width the width the reference declares for the screen field
     * @param field the request component name, so a refusal names the input the client can correct
     * @return the value as supplied, never {@code null}
     * @throws IllegalArgumentException if the value was never supplied, is not exactly the declared width,
     *     or holds a character that is not a digit
     */
    private static String requiredDigitsAtExactWidth(String value, int width, String field) {
        return digitsOnly(requiredAtExactWidth(value, width, field), field);
    }

    /**
     * Accepts a required digit value from a request at AT MOST the width its numeric picture declares.
     *
     * <p>Assumptions: the leniency applies only where the STORED picture is numeric, so a shorter value
     * acquires leading zeros rather than trailing blanks and the round trip is exact. The two fields this
     * serves are the customer identifier and the credit score, and both are declared numeric on the record
     * even though the screen carries them as characters -- which is the character-over-numeric pattern the
     * reference states outright at {@code app/cbl/COACTUPC.cbl} L710 through L712 and L754 through
     * L756.</p>
     *
     * @param value the value the request supplies
     * @param width the width the reference declares for the field
     * @param field the request component name, so a refusal names the input the client can correct
     * @return the value as supplied, never {@code null}
     * @throws IllegalArgumentException if the value was never supplied, is wider than the declared width,
     *     or holds a character that is not a digit
     */
    private static String requiredDigitsAtMostWidth(String value, int width, String field) {
        String supplied = requiredAtMostWidth(value, width, field);
        return digitsOnly(supplied, field);
    }

    /**
     * Refuses a value holding any character the reference's numeric picture cannot carry.
     *
     * <p>Assumptions: the test is against the ten ASCII digits rather than against the platform's general
     * digit test, and the narrowing is deliberate. The general test accepts digits from other scripts,
     * which a fixed-width record encoded one byte per character cannot represent at all, so accepting one
     * here would let a value into the pipeline that the migration's own fixed-width readers could not
     * write back.</p>
     *
     * @param value the value to test; must not be {@code null}
     * @param field the field name, so a refusal names the input the client can correct
     * @return the value unchanged, never {@code null}
     * @throws ClientInputException if any character of the value is not an ASCII digit
     */
    private static String digitsOnly(String value, String field) {
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                // WHY : Assumptions: a client-input refusal in the NOT-OK state, and the POSITION is
                //   reported while the CHARACTER is not. The position is what makes the refusal
                //   actionable; the character is part of the value the caller submitted, and this
                //   helper guards the national identifier and the credit score, so echoing it would
                //   copy a fragment of a protected identifier into a diagnostic.
                throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                        FieldValidationFlag.NOT_OK, "the supplied value of " + field
                        + " holds a character at position " + (position + 1)
                        + " that the reference's numeric picture cannot carry");
            }
        }
        return value;
    }

    /**
     * Classifies one request component as acceptable or as never supplied.
     *
     * <p>Assumptions: only two of the three states this class can report are produced here, and the third
     * is deliberately out of reach. A never-supplied component yields the state that carries the marker
     * the reference writes back, per {@code app/cpy/CSSETATY.cpy} L23 through L25; anything supplied
     * yields the acceptable state, because deciding that a SUPPLIED value is unacceptable is a
     * value-domain judgement belonging to the service layer rather than a representation fact. Returning
     * the not-acceptable state from here would quietly move a business rule into the mapping layer.</p>
     *
     * @param value the value the request supplies, which may be absent
     * @return the never-supplied state when nothing arrived, and the acceptable state otherwise, never
     *     {@code null}
     */
    private static FieldValidationFlag flagFor(String value) {
        return FieldValidationFlag.isNeverSupplied(value)
                ? FieldValidationFlag.BLANK
                : FieldValidationFlag.VALID;
    }
}
