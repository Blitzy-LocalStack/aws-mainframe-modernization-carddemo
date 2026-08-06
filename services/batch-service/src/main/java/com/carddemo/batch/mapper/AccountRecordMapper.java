package com.carddemo.batch.mapper;

import com.carddemo.batch.domain.Account;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Converts between the 300-byte account master image of {@code app/cpy/CVACT01Y.cpy} and the
 * {@link Account} entity, and does nothing else.
 *
 * <h2>The record this type reads and writes</h2>
 *
 * <p>Assumptions: the account master is 300 bytes carrying twelve named fields and one trailing
 * pad, and the geometry is not restated here. It is taken from the {@code ACCOUNT} descriptor
 * registered in {@link CopybookLayout}, whose thirteen offsets -- 0, 11, 12, 24, 36, 48, 58, 68,
 * 78, 90, 102, 112 and 122 -- tile the declared 300 bytes exactly. Five of the twelve are money,
 * three are dates and four are character fields, and each group is handled by one discipline stated
 * once below rather than field by field.</p>
 *
 * <h2>The round-trip property, and its two caveats</h2>
 *
 * <p>Assumptions: {@code toRecord(toEntity(image))} reproduces {@code image} byte for byte for any
 * valid 300-byte input, and the parity oracle depends on that. Exactly two spans are exempt, and
 * both are properties rather than surprises:</p>
 *
 * <ul> <li><b>Non-blank bytes inside the trailing {@code FILLER PIC X(178)} at
 * {@code app/cpy/CVACT01Y.cpy:17} are lost by design.</b> The pad is dropped on decode and rebuilt
 * as blanks on encode, so an image whose pad carried content re-encodes with that content replaced
 * by blanks. Every image the baseline writes carries a blank pad, so the exemption is not reachable
 * from baseline data.</li> <li><b>A zoned zero carrying the negative overpunch <code>'}'</code>
 * re-encodes with the positive overpunch <code>'{'</code>.</b> The exact decimal type has no
 * negative zero, so the two carriers decode to the same value and only one can be written back.
 * {@link FixedWidthCodec#encodeRecordPreservingSign} exists for a caller that holds the source
 * bytes and must restore the carrier; this type's encode signature takes an entity and no source
 * image, so it cannot and does not.</li> </ul>
 *
 * <h2>The optimistic-locking property is not part of the image</h2>
 *
 * <p>Assumptions: an entity produced by {@link #toEntity(byte[])} carries no version value taken
 * from the byte image, because the 300-byte record has no such field, and a caller must not rely on
 * one. The operational consequence is worth stating because it is not obvious from the signature:
 * {@code AccountRepository} is a write seam, so a batch step whose account rewrite loses the
 * version race must fail the step and let the orchestrator retry it rather than silently re-reading
 * and re-applying. The baseline rewrite at {@code app/cbl/CBTRN02C.cbl:554-559} accepts only a
 * clean status, so a retry from a fresh read is the behaviour that matches it and a silent
 * re-application is not.</p>
 *
 * <h2>The same picture clause is a different width in the export record</h2>
 *
 * <p>Assumptions: the twelve-byte assumption this type makes about {@code PIC S9(10)V99} holds for
 * this record and must not be carried to the export view, which is
 * {@code ExportRecordMapper}'s. Within one nine-line span
 * {@code app/cpy/CVEXPORT.cpy:47-60} declares that identical picture clause at three different
 * physical widths: {@code EXP-ACCT-CURR-BAL} at line 50 is {@code COMP-3} and occupies seven bytes,
 * {@code EXP-ACCT-CREDIT-LIMIT} at line 51 carries no usage clause and occupies twelve, and
 * {@code EXP-ACCT-CURR-CYC-DEBIT} at line 57 is {@code COMP} and occupies eight. The rule that
 * reconciles them is that <b>usage fixes the width and the picture clause never does</b>, so a
 * width read off a digit count is wrong for two of those three fields while leaving the record its
 * declared length.</p>
 *
 * <h2>What consumes this type, and what this type declines to do</h2>
 *
 * <p>The behaviour below belongs to the service layer and is referenced so that a reader knows what
 * the decoded values are for. <b>None of it is performed here</b>; this type converts
 * representations.</p>
 *
 * <ul> <li><b>Posting updates three of the five money fields.</b>
 * {@code app/cbl/CBTRN02C.cbl:547-552} adds the transaction amount to {@code ACCT-CURR-BAL}, then
 * adds that same amount to {@code ACCT-CURR-CYC-CREDIT} when it is non-negative and to
 * {@code ACCT-CURR-CYC-DEBIT} otherwise. The debit branch <b>adds a negative value; it does not
 * subtract</b>, so the debit bucket accumulates negatively. That sign semantic is the service's to
 * preserve, and it is named here so that it is not later tidied into a subtraction.</li>
 * <li><b>The over-limit projection is inclusive.</b>
 * {@code app/cbl/CBTRN02C.cbl:403-405} computes {@code ACCT-CURR-CYC-CREDIT} minus
 * {@code ACCT-CURR-CYC-DEBIT} plus the transaction amount, and line 407 compares
 * {@code ACCT-CREDIT-LIMIT} against it with {@code >=}, so a projection landing exactly on the
 * limit posts. This is the concrete reason every money field here must be exact: an error of one
 * cent moves a transaction across that boundary.</li> <li><b>Interest accrual also resets the
 * billing cycle.</b> {@code app/cbl/CBACT04C.cbl:350-356} adds the accrued total to
 * {@code ACCT-CURR-BAL} and then moves zero into both current-cycle buckets before rewriting the
 * record.</li> <li><b>A failed account rewrite is not an abend.</b>
 * {@code app/cbl/CBTRN02C.cbl:554-559} handles only an invalid key and sets reason code 109, which
 * reaches no reject stream, so the baseline continues past a rewrite that did not happen.</li>
 * </ul>
 *
 * @see CopybookLayout
 * @see FixedWidthCodec
 */
public final class AccountRecordMapper {

    /**
     * The registered name of the account master descriptor.
     */
    private static final String RECORD_NAME = "ACCOUNT";

    // WHY : Assumptions: the descriptor is resolved from the registry by name rather than built here,
    //       and the dependency is stronger than style. FixedWidthCodec recognises a trailing pad only
    //       when the descriptor it was handed is the REGISTERED instance -- its padding test compares
    //       CopybookLayout.layout(name) against the supplied spec by identity. A locally constructed
    //       spec with identical contents therefore fails that test, and encode would then reject the
    //       twelve-field map with a missing-required-field failure for FILLER instead of blank-filling
    //       it. Holding the registered instance in one constant makes that impossible to get wrong.
    /**
     * The registered account master descriptor, transcribed from {@code app/cpy/CVACT01Y.cpy}.
     */
    private static final CopybookLayout.RecordSpec LAYOUT = CopybookLayout.layout(RECORD_NAME);

    /**
     * The eleven-digit account identifier and primary key, declared at
     * {@code app/cpy/CVACT01Y.cpy:5}.
     */
    private static final String FIELD_ACCOUNT_ID = "ACCT-ID";

    /**
     * The one-character active-status code, declared at {@code app/cpy/CVACT01Y.cpy:6}.
     */
    private static final String FIELD_ACTIVE_STATUS = "ACCT-ACTIVE-STATUS";

    // WHY : Assumptions: each of the five money fields below occupies TWELVE bytes and not thirteen.
    //       PIC S9(10)V99 declares twelve digit positions, and the leading S adds no byte of its own
    //       because the sign is an overpunch folded into the low-order digit rather than a separate
    //       sign character. Miscounting this as ten digits plus two decimals plus one sign byte is the
    //       most common fixed-width error there is, and it is silent: it shifts every field after the
    //       first money field while leaving the record its declared length, so the decode succeeds and
    //       is wrong. The committed image at tests/fixtures/posting/happy_path/acctdata.txt shows the
    //       twelve-byte form directly -- ACCT-CURR-BAL reads 00000001930{ where the trailing '{' is
    //       the low-order digit zero carrying a positive sign.
    /**
     * The current outstanding balance, declared at {@code app/cpy/CVACT01Y.cpy:7}.
     */
    private static final String FIELD_CURR_BAL = "ACCT-CURR-BAL";

    /**
     * The credit limit the over-limit projection is compared against, declared at
     * {@code app/cpy/CVACT01Y.cpy:8}.
     */
    private static final String FIELD_CREDIT_LIMIT = "ACCT-CREDIT-LIMIT";

    /**
     * The cash credit limit, declared at {@code app/cpy/CVACT01Y.cpy:9}.
     */
    private static final String FIELD_CASH_CREDIT_LIMIT = "ACCT-CASH-CREDIT-LIMIT";

    /**
     * The date the account was opened, declared at {@code app/cpy/CVACT01Y.cpy:10}.
     */
    private static final String FIELD_OPEN_DATE = "ACCT-OPEN-DATE";

    // WHY : Assumptions: the baseline declares this field as ACCT-EXPIRAION-DATE at
    //       app/cpy/CVACT01Y.cpy:11, without the T in the third syllable; the Java implements it as
    //       expirationDate over the expiration_date column; and the divergence is documented in
    //       docs/architecture/data-model-and-schema-mapping.md. The constant below keeps the baseline
    //       spelling exactly, because the descriptor resolves field names by exact match and because a
    //       citation whose text has been tidied no longer locates the line it claims to. This boundary
    //       is where the two spellings meet and the only place either name may appear beside the
    //       other, which is what keeps the baseline spelling out of every column name, transfer object
    //       and interface downstream.
    /**
     * The account expiration date, declared at {@code app/cpy/CVACT01Y.cpy:11}.
     */
    private static final String FIELD_EXPIRATION_DATE = "ACCT-EXPIRAION-DATE";

    /**
     * The date the account was last reissued, declared at {@code app/cpy/CVACT01Y.cpy:12}.
     */
    private static final String FIELD_REISSUE_DATE = "ACCT-REISSUE-DATE";

    /**
     * The signed running total of credits in the current billing cycle, declared at
     * {@code app/cpy/CVACT01Y.cpy:13}.
     */
    private static final String FIELD_CURR_CYC_CREDIT = "ACCT-CURR-CYC-CREDIT";

    /**
     * The signed running total of debits in the current billing cycle, declared at
     * {@code app/cpy/CVACT01Y.cpy:14}.
     */
    private static final String FIELD_CURR_CYC_DEBIT = "ACCT-CURR-CYC-DEBIT";

    /**
     * The ten-character postal code, declared at {@code app/cpy/CVACT01Y.cpy:15}.
     */
    private static final String FIELD_ADDR_ZIP = "ACCT-ADDR-ZIP";

    /**
     * The ten-character disclosure-group identifier, declared at
     * {@code app/cpy/CVACT01Y.cpy:16}.
     */
    private static final String FIELD_GROUP_ID = "ACCT-GROUP-ID";

    /**
     * The absent-value form of a character field, which the codec pads to the declared width.
     */
    private static final String ABSENT_TEXT = "";

    /**
     * Prevents construction of this stateless converter.
     */
    private AccountRecordMapper() {
        // WHY : Alternatives Considered: an injectable instance shaped for constructor injection, as
        //       the service and repository layers of this module are. Rejected because this type holds
        //       no collaborator that could vary -- the descriptor is a registered constant and the
        //       codecs are themselves stateless holders -- so an instance would add a bean whose only
        //       distinguishing state is none, and a job would have to be wired to obtain a conversion
        //       that depends on nothing. The shape chosen matches the three shared-kernel types this
        //       file consumes, each of which is a final class with a private constructor and static
        //       members, so a reader meeting all four meets one shape. This charter's package
        //       separately forbids a shared supertype among the mappers here, so nothing is given up
        //       in polymorphism that was available in the first place.
    }

    /**
     * Decodes one 300-byte account master image into an entity.
     *
     * <p>Assumptions: the twelve named fields are read and the trailing {@code FILLER PIC X(178)} at
     * {@code app/cpy/CVACT01Y.cpy:17} is not. The pad is padding to the declared record length rather
     * than data, so it becomes no property of the entity, and the width it accounts for is recorded
     * on this type instead: twelve mapped fields spanning 122 bytes plus 178 bytes of pad reconcile
     * to the declared 300.</p>
     *
     * <p>Assumptions: the returned entity carries no optimistic-locking version, for the reason
     * stated on this type. A caller that needs one must read the row.</p>
     *
     * @param image the complete account master record; must be exactly 300 bytes and not
     *     {@code null}
     * @return an entity carrying the twelve mapped fields, with the three date properties absent
     *     where the source field was blank
     * @throws FixedWidthCodec.RecordLengthException if {@code image} is {@code null} or is not
     *     exactly 300 bytes
     * @throws FixedWidthCodec.FieldCodecException if a field's bytes are invalid for its declared
     *     storage regime, which for the five money fields means a low-order byte that is neither a
     *     digit nor a recognised sign overpunch
     * @throws IllegalArgumentException if a non-blank date field does not hold a calendar date in
     *     the {@code YYYY-MM-DD} form, or if a decoded amount does not carry the two decimal places
     *     the money contract requires
     */
    public static Account toEntity(byte[] image) {
        // WHY : Assumptions: the length and null checks are the codec's and are not repeated here. Its
        //       record-length guard rejects a null or wrongly sized image with a failure naming the
        //       record, the expected width and the received one, which is strictly more informative
        //       than a guard written here could be without restating the width this type deliberately
        //       does not hold. Adding a second check would create a second place the expected length is
        //       written down, and two places disagree eventually.
        Map<String, Object> fields = FixedWidthCodec.decodeRecord(image, LAYOUT);

        // WHY : Assumptions: the two adjacent PIC X(10) character fields are treated DIFFERENTLY on
        //       purpose, and the asymmetry is not an inconsistency. ACCT-GROUP-ID keeps all ten
        //       characters because it is a join key whose space padding is load-bearing:
        //       app/cbl/CBACT04C.cbl:437 moves the literal 'DEFAULT' into a PIC X(10) field, so the
        //       stored value is DEFAULT followed by three blanks, and the disclosure-group rate lookup
        //       matches only if that padding survives the boundary. Trimming it would silently lose the
        //       fallback rate for every account in the default group. ACCT-ADDR-ZIP is descriptive, so
        //       its trailing blanks are padding rather than data and are removed; the encode side pads
        //       the field back to ten bytes, so removing them costs nothing in round-trip fidelity.
        String groupId = textField(fields, FIELD_GROUP_ID);
        String addrZip = trailingBlanksRemoved(textField(fields, FIELD_ADDR_ZIP));

        return new Account(
                unsignedField(fields, FIELD_ACCOUNT_ID),
                textField(fields, FIELD_ACTIVE_STATUS),
                moneyField(fields, FIELD_CURR_BAL),
                moneyField(fields, FIELD_CREDIT_LIMIT),
                moneyField(fields, FIELD_CASH_CREDIT_LIMIT),
                isoDateField(fields, FIELD_OPEN_DATE),
                isoDateField(fields, FIELD_EXPIRATION_DATE),
                isoDateField(fields, FIELD_REISSUE_DATE),
                moneyField(fields, FIELD_CURR_CYC_CREDIT),
                moneyField(fields, FIELD_CURR_CYC_DEBIT),
                addrZip,
                groupId);
    }

    /**
     * Encodes one entity into a 300-byte account master image with the trailing pad blank-filled.
     *
     * <p>Assumptions: the trailing {@code FILLER PIC X(178)} is rebuilt as blanks rather than left
     * as the zero bytes a fresh array holds, because a reader of the record distinguishes low values
     * from spaces even though both look empty once decoded into text. Trade-offs: this direction is
     * deliberately not the mirror image of the decode, which drops the same span entirely. Treating
     * the two alike is the error either way round -- a decode that carried the pad would put an inert
     * 178-character property on the entity, while an encode that omitted it would emit a record right
     * in its first 122 bytes and 178 bytes short, which the byte-for-byte parity comparison reports
     * as a failure rather than as a near miss.</p>
     *
     * <p>Assumptions: encoding is lossless or it throws. An amount needing more than ten integer
     * digit positions, or carrying a third decimal place that is not zero, is refused rather than
     * truncated or rounded, because a rounding decision is a business decision that belongs to
     * {@link Money} at the service layer where the audit trail can show it, not to an encoder where
     * it would be invisible.</p>
     *
     * @param account the entity to encode; must not be {@code null}
     * @return a newly allocated record of exactly 300 bytes
     * @throws NullPointerException if {@code account} is {@code null}
     * @throws FixedWidthCodec.FieldCodecException if any value cannot be represented in its declared
     *     field, which covers an amount too large or too precise for {@code PIC S9(10)V99}, a
     *     character value longer than its declared width, and an absent value in a field that has no
     *     absent form
     * @throws IllegalArgumentException if a date property falls outside the four-digit year range
     *     the ten-character field can hold
     */
    public static byte[] toRecord(Account account) {
        Objects.requireNonNull(account, "account must not be null");

        // WHY : Assumptions: the map carries the twelve named fields and no thirteenth entry for the
        //       trailing pad. The codec rebuilds a registered pad it was not handed, and it rejects a
        //       key that names no declared field, so naming FILLER here would be redundant at best.
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELD_ACCOUNT_ID, account.getAccountId());
        fields.put(FIELD_ACTIVE_STATUS, blankIfAbsent(account.getActiveStatus()));
        fields.put(FIELD_CURR_BAL, account.getCurrBal());
        fields.put(FIELD_CREDIT_LIMIT, account.getCreditLimit());
        fields.put(FIELD_CASH_CREDIT_LIMIT, account.getCashCreditLimit());
        fields.put(FIELD_OPEN_DATE, isoDateText(account.getOpenDate()));
        fields.put(FIELD_EXPIRATION_DATE, isoDateText(account.getExpirationDate()));
        fields.put(FIELD_REISSUE_DATE, isoDateText(account.getReissueDate()));
        fields.put(FIELD_CURR_CYC_CREDIT, account.getCurrCycCredit());
        fields.put(FIELD_CURR_CYC_DEBIT, account.getCurrCycDebit());
        fields.put(FIELD_ADDR_ZIP, blankIfAbsent(account.getAddrZip()));
        fields.put(FIELD_GROUP_ID, blankIfAbsent(account.getGroupId()));

        // WHY : Assumptions: the optimistic-locking version is deliberately absent from the map, and
        //       its absence is a ruling rather than an oversight. It is a target-side artifact that the
        //       owning schema adds so a lost update can be detected; the 300-byte record declares no
        //       counterpart to it, so there is no interval to write it into. Supplying it would either
        //       be rejected as a key naming no declared field or, if a span were invented for it, would
        //       displace the trailing pad and change the record length. The observable consequence is
        //       the property this type's tests assert: two entities differing only in version encode to
        //       identical bytes.
        return FixedWidthCodec.encodeRecord(fields, LAYOUT);
    }

    /**
     * Reads one decoded character field, preserving its declared width exactly as the source holds
     * it.
     *
     * @param fields the decoded field values keyed by exact copybook field name
     * @param fieldName the copybook field name to read
     * @return the field's characters including any trailing blanks the source carried
     * @throws IllegalArgumentException if the decoded value is absent or is not character data,
     *     either of which means the registered descriptor no longer agrees with this mapper
     */
    private static String textField(Map<String, Object> fields, String fieldName) {
        Object value = fields.get(fieldName);
        if (value instanceof String text) {
            return text;
        }
        throw registryDisagreement(fieldName, "character text", value);
    }

    /**
     * Reads one decoded unsigned display field as the account identifier.
     *
     * @param fields the decoded field values keyed by exact copybook field name
     * @param fieldName the copybook field name to read
     * @return the field's non-negative integral value
     * @throws IllegalArgumentException if the decoded value is absent or is not integral, either of
     *     which means the registered descriptor no longer agrees with this mapper
     */
    private static Long unsignedField(Map<String, Object> fields, String fieldName) {
        Object value = fields.get(fieldName);
        if (value instanceof Long integral) {
            return integral;
        }
        throw registryDisagreement(fieldName, "an unsigned integral value", value);
    }

    /**
     * Reads one decoded money field as an exact amount at the contracted scale.
     *
     * <p>Assumptions: the scale is verified rather than imposed. The descriptor declares two decimal
     * places for every one of these fields and the codec returns a value at exactly that scale, so a
     * value arriving at any other scale means the registered descriptor and
     * {@code app/cpy/CVACT01Y.cpy} have diverged. This reports that rather than rescaling, because
     * rescaling here would compensate locally for a defect in a descriptor eight other records also
     * read from, hiding it from every one of them.</p>
     *
     * @param fields the decoded field values keyed by exact copybook field name
     * @param fieldName the copybook field name to read
     * @return the signed amount at exactly {@link Money#SCALE} decimal places
     * @throws IllegalArgumentException if the decoded value is absent, is not an exact decimal, or
     *     does not carry exactly {@link Money#SCALE} decimal places
     */
    private static BigDecimal moneyField(Map<String, Object> fields, String fieldName) {
        Object value = fields.get(fieldName);

        // WHY : Alternatives Considered: an IEEE-754 binary64 primitive, which is the reflex choice for
        //       an amount and is wrong here for a reason specific to this picture clause.
        //       app/cpy/CVACT01Y.cpy:7 declares ACCT-CURR-BAL as PIC S9(10)V99, which is twelve
        //       significant decimal digits once the two cent positions are counted. A binary64 value
        //       represents at most 15 to 17 significant decimal digits and only those whose exact value
        //       is a dyadic rational, and one cent is not: 0.01 has no terminating binary expansion, so
        //       a balance held that way is already an approximation before any arithmetic runs, and the
        //       error lands in the least significant cent. That is precisely the position the inclusive
        //       over-limit comparison at app/cbl/CBTRN02C.cbl:407 turns on, so the approximation is not
        //       cosmetic -- it decides whether a transaction posts or is rejected with reason 102. The
        //       exact decimal type carries the twelve digits and the scale as declared values, so no
        //       representation error exists to accumulate.
        if (!(value instanceof BigDecimal amount)) {
            throw registryDisagreement(fieldName, "an exact decimal amount", value);
        }
        if (amount.scale() != Money.SCALE) {
            throw new IllegalArgumentException("record " + RECORD_NAME + " field "
                    + LAYOUT.field(fieldName).describe() + " decoded at scale " + amount.scale()
                    + " but the money contract requires exactly " + Money.SCALE
                    + "; the registered descriptor and app/cpy/CVACT01Y.cpy have diverged");
        }
        return amount;
    }

    /**
     * Reads one decoded ten-character date field as a calendar date, treating an all-blank field as
     * absent.
     *
     * <p>Assumptions: converting these ten characters to a calendar date does not change the meaning
     * of the baseline's own comparisons, because the stored form is already ISO ordered and a lexical
     * comparison of {@code YYYY-MM-DD} is therefore equivalent to a chronological one. That
     * equivalence is exactly what {@code app/cbl/CBTRN02C.cbl:414} relies on when it compares
     * {@code ACCT-EXPIRAION-DATE} against the first ten characters of the 26-character
     * {@code DALYTRAN-ORIG-TS} with {@code >=} -- a character comparison standing in for a date
     * comparison. The service layer reproduces that inclusive boundary against the values this method
     * returns, and it means the same thing on either representation.</p>
     *
     * <p>Assumptions: an all-blank field is a legitimate absent date and not malformed input. A
     * fixed-width record has no null, so absence can only be expressed as blanks, and rejecting them
     * would turn an account that was never reissued into corrupt input.</p>
     *
     * @param fields the decoded field values keyed by exact copybook field name
     * @param fieldName the copybook field name to read
     * @return the calendar date the field holds, or {@code null} when the field is all blanks
     * @throws IllegalArgumentException if the field is neither blank nor a calendar date in the
     *     {@code YYYY-MM-DD} form
     */
    private static LocalDate isoDateField(Map<String, Object> fields, String fieldName) {
        String text = textField(fields, fieldName);
        if (text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException malformed) {
            // WHY : Alternatives Considered: letting the parse failure propagate as it is. Rejected so
            //       that every failure this mapper can raise belongs to one family -- the codec's own
            //       record-length and field failures both extend the platform argument exception -- and
            //       a caller wrapping a conversion needs one catch rather than two unrelated ones. The
            //       cause is chained rather than discarded, so the position within the ten characters
            //       that the parser objected to is still available.
            // WHY : Assumptions: the raw characters are quoted only when the descriptor declares the
            //       field not sensitive, which is the discipline this package's charter sets and the
            //       shared codecs already follow. No field of this record is marked sensitive today, so
            //       the branch always quotes here; it is written as a test rather than as an
            //       unconditional quote so that marking a field sensitive later cannot put its content
            //       into a log line by omission.
            CopybookLayout.FieldSpec field = LAYOUT.field(fieldName);
            String quoted = field.sensitive() ? "" : " from '" + text + "'";
            throw new IllegalArgumentException("record " + RECORD_NAME + " field " + field.describe()
                    + " is neither all blanks nor a YYYY-MM-DD calendar date" + quoted, malformed);
        }
    }

    /**
     * Renders a calendar date as the ten characters the record declares, or as an absent value.
     *
     * <p>Assumptions: the date is emitted in its own ISO form and is never reformatted or
     * re-punctuated, so the ten characters written back are the ten that were read. This restraint is
     * a package-wide constraint rather than a local preference: {@code app/jcl/INTCALC.jcl:22} passes
     * the baseline business date as {@code PARM='2022071800'}, a ten-character token that is not ISO
     * at all and that flows byte for byte into generated identifiers, so nothing here may normalise a
     * ten-character date-like token on the assumption that it is ISO.</p>
     *
     * @param date the date to render, or {@code null} for an absent date
     * @return the {@code YYYY-MM-DD} characters, or the empty string that the codec pads to ten
     *     blanks when the date is absent
     */
    private static String isoDateText(LocalDate date) {
        // WHY : Trade-offs: a year outside the four-digit range renders with an explicit sign and extra
        //       digits, which the codec then refuses because the value exceeds the ten bytes the field
        //       holds. Refusing is the intended outcome and is why no range check is written here: such
        //       a date has no representation in this record, and truncating it to ten characters would
        //       write a different date rather than reporting an unwritable one.
        return date == null ? ABSENT_TEXT : date.toString();
    }

    /**
     * Substitutes the absent-value form for a missing character value.
     *
     * <p>Assumptions: a fixed-width record has no null, so an absent character value can only be
     * written as blanks, and the codec pads the empty string to the field's declared width. This is
     * the faithful encoding of absence rather than a defensive default, which is why it is applied to
     * the character fields and not to the money fields: a missing amount has no blank form in a zoned
     * field and must be reported, not substituted.</p>
     *
     * @param value the character value to write, or {@code null} when the property is absent
     * @return the value unchanged, or the empty string when it is absent
     */
    private static String blankIfAbsent(String value) {
        return value == null ? ABSENT_TEXT : value;
    }

    /**
     * Removes the trailing blanks a descriptive character field carries as padding.
     *
     * @param value the field's characters at their declared width
     * @return the characters with trailing blanks removed, leading and interior blanks untouched
     */
    private static String trailingBlanksRemoved(String value) {
        // WHY : Alternatives Considered: trimming both ends. Rejected because only the trailing side is
        //       padding in a fixed-width field: content is written left-justified into the field, so a
        //       leading blank is a character the source actually holds and removing it would change the
        //       value rather than remove its padding.
        return value.stripTrailing();
    }

    /**
     * Reports a decoded field whose Java type does not match what its declared storage regime yields.
     *
     * <p>Assumptions: this condition is unreachable while the registered descriptor agrees with
     * {@code app/cpy/CVACT01Y.cpy}, so the message names the descriptor as the suspect rather than
     * the input. It is raised instead of being assumed away because a descriptor edit is exactly the
     * change that would make it reachable, and an unchecked cast would then fail with a message
     * naming neither the field nor the reason.</p>
     *
     * @param fieldName the copybook field name whose decoded value was unusable
     * @param expected a short description of the Java form the field's storage regime yields
     * @param actual the value actually decoded, used only for its type and its absence
     * @return the exception to throw, so that a caller can throw it on one line
     */
    private static IllegalArgumentException registryDisagreement(String fieldName, String expected,
            Object actual) {
        // WHY : Assumptions: the value's TYPE is reported and its content is not, following this
        //       package's charter on diagnostics. The type is what identifies a descriptor that has
        //       changed kind, which is the only way this failure arises, so nothing diagnostic is lost
        //       by withholding content that could reach a log aggregator.
        String actualType = actual == null ? "absent" : actual.getClass().getSimpleName();
        return new IllegalArgumentException("record " + RECORD_NAME + " field "
                + LAYOUT.field(fieldName).describe() + " should decode to " + expected
                + " but was " + actualType
                + "; the registered descriptor and app/cpy/CVACT01Y.cpy have diverged");
    }
}
