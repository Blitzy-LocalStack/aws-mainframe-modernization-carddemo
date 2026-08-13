package com.carddemo.batch.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.Transaction;
import com.carddemo.common.codec.FixedWidthCodec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the 350-byte transaction boundary in both directions and under both layouts.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link TransactionRecordMapper} had no executable consumer, and it is the most consequential of
 * this module's three record boundaries: it carries the money field the posting job writes, both
 * timestamp spans, the sensitive card number and the trailing pad whose contents the committed parity
 * expectations depend on. This class asserts every one of the thirteen mapped fields in both
 * directions, the <b>asymmetric</b> trim rule that separates the three compared-at-width fields from
 * the five descriptive ones, <b>both</b> accepted timestamp forms, the blank-timestamp form that means
 * unposted, the three byte regions that only the sign-preserving encode can reproduce, the
 * masked diagnostic for the sensitive field, and every documented rejection.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises to anything outside the test engine,
 * so the type itself accepts no parameter, returns nothing and throws nothing. The inapplicability is
 * stated rather than passed over, because user-specified Rule 1 (Explainability) forbids a docstring
 * that omits parameters, return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an oversight. Every member below carries its own parameter, return and
 * exception at-clauses.</p>
 *
 * <h2>Assumptions: the record image is the committed parity expectation, pad included</h2>
 *
 * <p>{@link #POSTED_RECORD} reproduces the single record of
 * {@code tests/golden/posting/happy_path/tranfile.expected}, including its trailing 20 bytes of
 * <b>low values rather than blanks</b>. That detail is the reason one of the three encode entry points
 * exists: an encode that rebuilt the pad as blanks differs from the oracle in exactly those 20
 * positions, so a parity comparison against the committed expectation would fail on a record that was
 * right in every field. The constant is assembled from named field pieces so each can be checked
 * against {@code app/cpy/CVTRA05Y.cpy} without counting characters.</p>
 */
@DisplayName("TransactionRecordMapper: 350-byte transaction decode and encode")
class TransactionRecordMapperTest {

    /** Declared record length of the transaction, from {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int RECORD_LENGTH = 350;

    /** Zero-based offset of the transaction identifier, which is also the record key. */
    private static final int TRAN_ID_OFFSET = 0;

    /** Zero-based offset of the two-character transaction type code. */
    private static final int TYPE_CD_OFFSET = 16;

    /** Zero-based offset of the four-digit category code. */
    private static final int CAT_CD_OFFSET = 18;

    /** Zero-based offset of the signed zoned amount, corroborated by the mapper's own constant. */
    private static final int AMOUNT_OFFSET = 132;

    /** Declared width of the signed zoned amount: nine integer digits plus two decimal digits. */
    private static final int AMOUNT_LENGTH = 11;

    /** Zero-based offset of the sensitive card number, corroborated by the report sort control. */
    private static final int CARD_NUM_OFFSET = 262;

    /** Zero-based offset of the origination timestamp span. */
    private static final int ORIG_TS_OFFSET = 278;

    /** Zero-based offset of the processing timestamp span, corroborated by the alternate index. */
    private static final int PROC_TS_OFFSET = 304;

    /** Declared width of each timestamp span. */
    private static final int TIMESTAMP_LENGTH = 26;

    /** Zero-based offset of the trailing pad. */
    private static final int PAD_OFFSET = 330;

    /** Declared width of the trailing pad. */
    private static final int PAD_LENGTH = 20;

    /** Zero-based offset of the description, the one span whose pad the named layout decides. */
    private static final int DESC_OFFSET = 32;

    /** Declared width of the description, from {@code TRAN-DESC PIC X(100)}. */
    private static final int DESC_LENGTH = 100;

    /**
     * The 330 populated bytes of {@code tests/golden/posting/happy_path/tranfile.expected}.
     *
     * <p>Assumptions: the pad is excluded from this constant and supplied separately by
     * {@link #postedImage()}, because it is the one span that is not text and cannot be written as a
     * readable literal beside the others.</p>
     */
    private static final String POSTED_PREFIX =
            "0000000000683580"                            // TRAN-ID             PIC X(16)
            + "01"                                        // TRAN-TYPE-CD        PIC X(02)
            + "0001"                                      // TRAN-CAT-CD         PIC 9(04)
            + "POS TERM  "                                // TRAN-SOURCE         PIC X(10)
            + "Purchase at Abshire-Lowe" + " ".repeat(76) // TRAN-DESC           PIC X(100)
            + "0000005047G"                               // TRAN-AMT S9(09)V99, +504.77
            + "800000000"                                 // TRAN-MERCHANT-ID    PIC 9(09)
            + "Abshire-Lowe" + " ".repeat(38)             // TRAN-MERCHANT-NAME  PIC X(50)
            + "North Enoshaven" + " ".repeat(35)          // TRAN-MERCHANT-CITY  PIC X(50)
            + "72112     "                                // TRAN-MERCHANT-ZIP   PIC X(10)
            + "4859452612877065"                          // TRAN-CARD-NUM       PIC X(16)
            + "2022-06-10 19:27:53.000000"                // TRAN-ORIG-TS        PIC X(26)
            + " ".repeat(TIMESTAMP_LENGTH);                // TRAN-PROC-TS       PIC X(26), unposted

    /**
     * Assembles the committed parity record, pad of low values included.
     *
     * @return a freshly allocated 350-byte image whose last {@value #PAD_LENGTH} bytes are zero
     */
    private static byte[] postedImage() {
        byte[] image = new byte[RECORD_LENGTH];
        byte[] prefix = POSTED_PREFIX.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(prefix, 0, image, 0, prefix.length);
        return image;
    }

    /**
     * Returns a copy of the parity record with one field span overwritten.
     *
     * @param offset the zero-based offset of the span to overwrite
     * @param value the replacement content, which must be exactly the span's declared width
     * @return a freshly allocated 350-byte image carrying the replacement
     */
    private static byte[] spliced(int offset, String value) {
        byte[] image = postedImage();
        byte[] replacement = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(replacement, 0, image, offset, replacement.length);
        return image;
    }

    /**
     * Reads one span of an image as text.
     *
     * @param image the record image to read
     * @param offset the zero-based offset of the span
     * @param length the span's declared width
     * @return the span decoded as text, exactly {@code length} characters
     */
    private static String span(byte[] image, int offset, int length) {
        return new String(image, offset, length, StandardCharsets.UTF_8);
    }

    /**
     * Asserts that the assembled parity record is exactly the declared length with a low-value pad.
     */
    @Test
    @DisplayName("the assembled parity record is exactly the declared length with a low-value pad")
    void theAssembledParityRecordIsExactlyTheDeclaredLength() {
        byte[] image = postedImage();

        // WHY : Assumptions: the pad is asserted to be LOW VALUES rather than blanks, because that is
        //       what the committed expectation holds and it is the whole reason the sign-preserving
        //       encode entry point exists. A reader who assumed blanks would find the plain encode
        //       correct and the parity comparison inexplicably failing in twenty positions.
        assertThat(POSTED_PREFIX).hasSize(PAD_OFFSET);
        assertThat(image).hasSize(RECORD_LENGTH);
        assertThat(Arrays.copyOfRange(image, PAD_OFFSET, RECORD_LENGTH))
                .containsOnly((byte) 0);
    }

    /**
     * Asserts that every one of the thirteen mapped fields decodes to its expected value.
     */
    @Test
    @DisplayName("every one of the thirteen mapped fields decodes to its expected value")
    void everyMappedFieldDecodesToItsExpectedValue() {
        Transaction transaction = TransactionRecordMapper.toEntity(postedImage());

        assertThat(transaction.getTransactionId()).isEqualTo("0000000000683580");
        assertThat(transaction.getTypeCd()).isEqualTo("01");
        assertThat(transaction.getCategoryCd()).isEqualTo("0001");
        assertThat(transaction.getSource()).isEqualTo("POS TERM");
        assertThat(transaction.getDescription()).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(transaction.getAmount()).isEqualByComparingTo("504.77");
        assertThat(transaction.getAmount().scale()).isEqualTo(2);
        assertThat(transaction.getMerchantId()).isEqualTo(800000000L);
        assertThat(transaction.getMerchantName()).isEqualTo("Abshire-Lowe");
        assertThat(transaction.getMerchantCity()).isEqualTo("North Enoshaven");
        assertThat(transaction.getMerchantZip()).isEqualTo("72112");
        assertThat(transaction.getCardNum()).isEqualTo("4859452612877065");
        assertThat(transaction.getOrigTs()).isEqualTo(LocalDateTime.of(2022, 6, 10, 19, 27, 53));
        assertThat(transaction.getProcTs()).isNull();
    }

    /**
     * Asserts that the three compared-at-width fields keep their width while five are trimmed.
     */
    @Test
    @DisplayName("the three compared-at-width fields keep their width while five are trimmed")
    void theThreeComparedAtWidthFieldsKeepTheirWidth() {
        byte[] image = spliced(TRAN_ID_OFFSET, "12345           ");
        System.arraycopy("SRC       ".getBytes(StandardCharsets.UTF_8), 0, image, 22, 10);

        Transaction transaction = TransactionRecordMapper.toEntity(image);

        // WHY : Assumptions: the trim rule is ASYMMETRIC across adjacent PIC X(n) fields and the
        //       dividing line is whether the value is COMPARED at a fixed width. TRAN-ID keys the row
        //       and orders the combine flow's sort, TRAN-TYPE-CD is a reference-data key whose
        //       counterpart column is itself fixed width, and TRAN-CARD-NUM is an index key -- a
        //       trimmed value of any of the three still equals itself and no longer equals its
        //       blank-padded counterpart, which is a mismatch no length check reveals. TRAN-SOURCE is
        //       never compared, so its trailing blanks are padding under transformation rule T1.
        //       Asserting one padded and one trimmed field from the SAME image is what shows the rule
        //       is a distinction rather than an inconsistency.
        assertThat(transaction.getTransactionId()).isEqualTo("12345           ").hasSize(16);
        assertThat(transaction.getSource()).isEqualTo("SRC");
        assertThat(transaction.getCardNum()).hasSize(16);
        assertThat(transaction.getTypeCd()).hasSize(2);
    }

    /**
     * Asserts that a timestamp in the baseline form is accepted as readily as the target form.
     */
    @Test
    @DisplayName("a timestamp in the baseline form is accepted as readily as the target form")
    void aTimestampInTheBaselineFormIsAccepted() {
        byte[] image = spliced(PROC_TS_OFFSET, "2022-06-10-19.27.53.000000");

        Transaction transaction = TransactionRecordMapper.toEntity(image);

        // WHY : Assumptions: BOTH producers' forms must decode, and the two are asserted to yield the
        //       SAME instant so that neither is being normalised into the other by accident. The
        //       target form separates the date from the time with a blank and uses colons; the
        //       baseline form declared at app/cbl/CBTRN02C.cbl:160-174 uses a hyphen and full stops.
        //       They differ at positions 11, 14, 17 and 21 through 26, so a decoder written for one
        //       rejects the other outright rather than mis-parsing it.
        assertThat(transaction.getProcTs()).isEqualTo(LocalDateTime.of(2022, 6, 10, 19, 27, 53));
        assertThat(transaction.getProcTs()).isEqualTo(transaction.getOrigTs());
    }

    /**
     * Asserts that a twenty-six-blank timestamp span decodes as absent rather than raising.
     */
    @Test
    @DisplayName("a twenty-six-blank timestamp span decodes as absent rather than raising")
    void aBlankTimestampSpanDecodesAsAbsent() {
        // WHY : Assumptions: a blank processing stamp is a LEGITIMATE value and not a defect: it is
        //       what an unposted row carries. That is why the migrated daily-transaction walk is
        //       ordered by transaction identifier rather than by processing stamp -- ordering by a
        //       column that is blank on precisely the rows the walk exists to find would put them in
        //       an arbitrary position.
        assertThat(TransactionRecordMapper.toEntity(postedImage()).getProcTs()).isNull();
        assertThat(TransactionRecordMapper
                .toEntity(spliced(ORIG_TS_OFFSET, " ".repeat(TIMESTAMP_LENGTH))).getOrigTs())
                .isNull();
    }

    /**
     * Asserts that the plain encode renders both timestamps in the target form.
     */
    @Test
    @DisplayName("the plain encode renders both timestamps in the target form")
    void thePlainEncodeRendersTimestampsInTheTargetForm() {
        Transaction transaction =
                TransactionRecordMapper.toEntity(spliced(PROC_TS_OFFSET, "2022-06-10-19.27.53.000000"));

        byte[] encoded = TransactionRecordMapper.toRecord(transaction);

        // WHY : Assumptions: the plain entry point is the correct one for a flow that read a row out
        //       of the database and has no source image to defer to, so it renders the TARGET form
        //       even when the value arrived in the baseline form. Asserting that conversion here is
        //       what distinguishes this entry point from the sign-preserving one below, which keeps
        //       the original span.
        assertThat(span(encoded, PROC_TS_OFFSET, TIMESTAMP_LENGTH))
                .isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(span(encoded, ORIG_TS_OFFSET, TIMESTAMP_LENGTH))
                .isEqualTo("2022-06-10 19:27:53.000000");
    }

    /**
     * Asserts that the sign-preserving encode reproduces the parity record byte for byte.
     */
    @Test
    @DisplayName("the sign-preserving encode reproduces the parity record byte for byte")
    void theSignPreservingEncodeReproducesTheParityRecord() {
        byte[] image = postedImage();
        Transaction transaction = TransactionRecordMapper.toEntity(image);

        assertThat(TransactionRecordMapper.toRecord(transaction,
                TransactionRecordMapper.Layout.POSTED_MASTER, image))
                .isEqualTo(image);
    }

    /**
     * Asserts that the plain encode reproduces the parity record, trailing pad included.
     *
     * <p>Refactoring Rationale: this case asserted the opposite -- that the plain encode differed from
     * the committed record in exactly the twenty pad bytes, because the encode rebuilt them with the
     * charset's blank. That difference is now closed at its source: the mapper writes the low value the
     * reference's own record area holds, measured in the pad slice of every non-empty
     * {@code tests/golden/posting/}{@code *}{@code /tranfile.expected} and of all three
     * {@code tests/golden/interest/}{@code *}{@code /transact.expected}. The case is rewritten rather
     * than deleted because the property it pins is the one that matters most here, and it is now
     * stronger: an entity alone reproduces the whole record.</p>
     */
    @Test
    @DisplayName("the plain encode reproduces the parity record, trailing pad included")
    void thePlainEncodeReproducesTheParityRecordIncludingItsPad() {
        byte[] image = postedImage();

        byte[] encoded = TransactionRecordMapper.toRecord(TransactionRecordMapper.toEntity(image));

        // WHY : Assumptions: the whole record is compared and the pad is ALSO asserted separately, and
        //       the pair is not redundant. Whole-record equality is the property a parity comparison
        //       needs; naming the pad byte is what tells a reader which of the two candidate bytes this
        //       record carries, so a future change that reintroduced the blank would fail with a
        //       message that says which span and which byte rather than only that 350 bytes differ.
        assertThat(encoded).hasSize(RECORD_LENGTH).isEqualTo(image);
        assertThat(Arrays.copyOf(encoded, PAD_OFFSET)).isEqualTo(Arrays.copyOf(image, PAD_OFFSET));
        assertThat(Arrays.copyOfRange(encoded, PAD_OFFSET, RECORD_LENGTH))
                .containsOnly((byte) 0x00);
    }

    /**
     * Asserts that the sign-preserving encode keeps a negative-zero sign carrier.
     */
    @Test
    @DisplayName("the sign-preserving encode keeps a negative-zero sign carrier")
    void theSignPreservingEncodeKeepsANegativeZeroSignCarrier() {
        byte[] image = spliced(AMOUNT_OFFSET, "0000000000}");
        Transaction transaction = TransactionRecordMapper.toEntity(image);

        // WHY : Assumptions: the target decimal type has no negative zero to distinguish, so the
        //       shared zoned codec emits the canonical POSITIVE-zero overpunch for every zero. A
        //       record whose amount was stored with the negative carrier therefore cannot be
        //       reproduced from the entity alone, which is why the sign-preserving entry point copies
        //       the carrier from the source image. Both behaviours are asserted together because
        //       either one alone reads as a defect.
        assertThat(transaction.getAmount()).isEqualByComparingTo("0.00");
        assertThat(span(TransactionRecordMapper.toRecord(transaction,
                TransactionRecordMapper.Layout.POSTED_MASTER, image), AMOUNT_OFFSET, AMOUNT_LENGTH))
                .isEqualTo("0000000000}");
        assertThat(span(TransactionRecordMapper.toRecord(transaction), AMOUNT_OFFSET, AMOUNT_LENGTH))
                .isEqualTo("0000000000{");
    }

    /**
     * Asserts that the sign-preserving encode renders a changed timestamp instead of copying it.
     */
    @Test
    @DisplayName("the sign-preserving encode renders a changed timestamp instead of copying it")
    void theSignPreservingEncodeRendersAChangedTimestamp() {
        byte[] image = spliced(PROC_TS_OFFSET, "2022-06-10-19.27.53.000000");
        Transaction transaction = TransactionRecordMapper.toEntity(image);
        transaction.setProcTs(LocalDateTime.of(2022, 6, 11, 8, 0, 0));

        byte[] encoded = TransactionRecordMapper.toRecord(transaction,
                TransactionRecordMapper.Layout.POSTED_MASTER, image);

        // WHY : Assumptions: the timestamp restoration is CONDITIONAL and the condition is what keeps
        //       this entry point honest. Copying the source span unconditionally would make it ignore
        //       the entity's own value, so a caller that had adjusted a processing stamp would
        //       silently emit the old one -- a wrong record that still matched its source. The
        //       unchanged origination span is asserted in the same test to show the condition is
        //       per-field rather than per-record.
        assertThat(span(encoded, PROC_TS_OFFSET, TIMESTAMP_LENGTH))
                .isEqualTo("2022-06-11 08:00:00.000000");
        assertThat(span(encoded, ORIG_TS_OFFSET, TIMESTAMP_LENGTH))
                .isEqualTo("2022-06-10 19:27:53.000000");
    }

    /**
     * Asserts that both layouts place every field identically and differ only in the description pad.
     *
     * <p>Refactoring Rationale: this case asserted that the two layouts emit the SAME bytes, which was
     * true while the layout carried nothing but a comparator's normalisation marker and is no longer.
     * Section 6.3 of {@code services/batch-service/src/test/resources/fixtures/README.md} measures the
     * description pad as a property of the writing job -- {@code STRING} at
     * {@code app/cbl/CBACT04C.cbl:485-489} leaves the tail of the field untouched while the
     * {@code MOVE} at {@code app/cbl/CBTRN02C.cbl:429} blank-pads it -- so the layout now decides that
     * byte. What still holds, and what is asserted, is that every FIELD is placed identically: the two
     * differ inside one field's pad and nowhere else, which is why a wrongly named layout shifts
     * nothing.</p>
     */
    @Test
    @DisplayName("both layouts place every field identically and differ only in the description pad")
    void bothLayoutsPlaceEveryFieldIdentically() {
        byte[] image = postedImage();
        Transaction fromPosted =
                TransactionRecordMapper.toEntity(image, TransactionRecordMapper.Layout.POSTED_MASTER);
        Transaction fromInterest = TransactionRecordMapper.toEntity(image,
                TransactionRecordMapper.Layout.INTEREST_GENERATED);

        // WHY : Assumptions: the two layouts differ only in which timestamp a parity comparator
        //       normalises, so their byte behaviour must be identical and the argument must still be
        //       required. The registry holds a THIRD 350-byte entry whose geometry differs, so a
        //       method taking an open specification would let a caller hand that one in and get a
        //       decode that consumed exactly 350 bytes and placed every field wrongly; the closed
        //       enumeration is what makes that unwritable, and asserting the closed set is what keeps
        //       it closed.
        assertThat(fromInterest.getTransactionId()).isEqualTo(fromPosted.getTransactionId());
        assertThat(fromInterest.getAmount()).isEqualByComparingTo(fromPosted.getAmount());

        byte[] underInterest = TransactionRecordMapper.toRecord(fromInterest,
                TransactionRecordMapper.Layout.INTEREST_GENERATED, image);
        byte[] underPosting = TransactionRecordMapper.toRecord(fromPosted,
                TransactionRecordMapper.Layout.POSTED_MASTER, image);

        // WHY : Assumptions: the two images are compared span by span rather than as wholes, because
        //       the ONE span they may differ in is the description's tail and every other byte must
        //       agree. Comparing the wholes would report a difference without saying where, which is
        //       precisely the failure a reader of this case needs located: a geometry drift and a pad
        //       rule change would look the same.
        assertThat(Arrays.copyOf(underInterest, DESC_OFFSET))
                .isEqualTo(Arrays.copyOf(underPosting, DESC_OFFSET));
        assertThat(Arrays.copyOfRange(underInterest, DESC_OFFSET + DESC_LENGTH, RECORD_LENGTH))
                .isEqualTo(Arrays.copyOfRange(underPosting, DESC_OFFSET + DESC_LENGTH,
                        RECORD_LENGTH));
        assertThat(TransactionRecordMapper.Layout.POSTED_MASTER.descriptionPad()).isEqualTo((byte) ' ');
        assertThat(TransactionRecordMapper.Layout.INTEREST_GENERATED.descriptionPad())
                .isEqualTo((byte) 0x00);
        assertThat(TransactionRecordMapper.Layout.values()).hasSize(2);
        assertThat(TransactionRecordMapper.Layout.POSTED_MASTER.registryName()).isEqualTo("TRAN");
        assertThat(TransactionRecordMapper.Layout.INTEREST_GENERATED.registryName())
                .isEqualTo("INTTRAN");
        assertThat(TransactionRecordMapper.Layout.POSTED_MASTER.spec().reclen())
                .isEqualTo(RECORD_LENGTH);
    }

    /**
     * Asserts that naming a layout without a source image encodes that producer's own bytes.
     *
     * <p>Refactoring Rationale: this case asserted that naming either layout produced the DEFAULT
     * bytes, which held while the layout reached no byte of the output. It now decides the description
     * pad, so the posting layout is asserted to equal the default -- it IS the default -- and the
     * accrual layout is asserted to differ from it in exactly that one span.</p>
     */
    @Test
    @DisplayName("naming a layout without a source image encodes that producer's own bytes")
    void namingALayoutWithoutASourceImageEncodesTheDefaultBytes() {
        Transaction transaction = TransactionRecordMapper.toEntity(postedImage());

        // WHY : Refactoring Rationale: the interest-generation flow is the caller that NEEDS this
        //       two-argument overload, and it is the one overload no other case here reaches on a
        //       success path. A freshly generated interest row has never been read from a file, so
        //       there is no source image to defer to and the three-argument form is unusable; and it
        //       must name INTTRAN rather than accept the posting default, so the one-argument form is
        //       unusable too. Covering it only through its null-layout rejection would leave the
        //       delegation below unpinned, and a changed default would then go unnoticed.
        assertThat(TransactionRecordMapper.toRecord(transaction,
                TransactionRecordMapper.Layout.POSTED_MASTER))
                .isEqualTo(TransactionRecordMapper.toRecord(transaction));

        byte[] generated = TransactionRecordMapper.toRecord(transaction,
                TransactionRecordMapper.Layout.INTEREST_GENERATED);
        byte[] posted = TransactionRecordMapper.toRecord(transaction);

        // WHY : Assumptions: the overload's own documented contract is that it rebuilds the trailing
        //       pad with the low value a producer's record area holds and renders a present timestamp
        //       in the TARGET form, because it has no image to copy from. Both are asserted here rather
        //       than inferred from the one-argument tests, since a caller reaching this overload for
        //       the interest layout is relying on THIS method's contract.
        assertThat(generated).hasSize(RECORD_LENGTH);
        assertThat(Arrays.copyOfRange(generated, PAD_OFFSET, RECORD_LENGTH))
                .containsOnly((byte) 0x00);
        assertThat(span(generated, ORIG_TS_OFFSET, TIMESTAMP_LENGTH))
                .isEqualTo("2022-06-10 19:27:53.000000");

        // WHY : Assumptions: the accrual layout's own difference is asserted at the description and
        //       NOWHERE else, which is what makes the two overloads' relationship legible: the layout
        //       argument reaches one span. The parity record's description is the posting one, so the
        //       accrual encode pads its tail with low values while the posting encode pads it with
        //       blanks, and every byte outside that field is identical.
        assertThat(Arrays.copyOfRange(generated, DESC_OFFSET + DESC_LENGTH, RECORD_LENGTH))
                .isEqualTo(Arrays.copyOfRange(posted, DESC_OFFSET + DESC_LENGTH, RECORD_LENGTH));
        assertThat(Arrays.copyOf(generated, DESC_OFFSET)).isEqualTo(Arrays.copyOf(posted, DESC_OFFSET));
        assertThat(generated).isNotEqualTo(posted);

        // WHY : Trade-offs: the processing span is asserted separately from the pad, and stating the
        //       two separately looks redundant until the reasons are named -- the pad holds low values
        //       because it was REBUILT with no member behind it, whereas this span is blank because the
        //       parity record is an UNPOSTED row whose member decodes as absent. Collapsing them into
        //       one assertion would let a regression that dropped a populated timestamp still pass.
        assertThat(span(generated, PROC_TS_OFFSET, TIMESTAMP_LENGTH))
                .isEqualTo(" ".repeat(TIMESTAMP_LENGTH));
    }

    /**
     * Asserts that a diagnostic for the sensitive card-number field masks all but its last four.
     */
    @Test
    @DisplayName("a diagnostic for the sensitive card-number field masks all but its last four")
    void aDiagnosticForTheSensitiveCardNumberIsMasked() {
        Transaction transaction = TransactionRecordMapper.toEntity(postedImage());
        transaction.setCardNum("12345678901234567");

        // WHY : Assumptions: the assertion is that the leading digits are ABSENT and only the last
        //       four survive. A rejected primary account number reaching a log is the exposure a
        //       fixed-width boundary is most likely to create, because the value has to appear in a
        //       message for the message to be useful. Asserting the absence of the full value -- not
        //       merely the presence of asterisks -- is what actually protects it.
        assertThatThrownBy(() -> TransactionRecordMapper.toRecord(transaction))
                .isInstanceOf(TransactionRecordMapper.RecordMappingException.class)
                .hasMessageContaining("TRAN-CARD-NUM")
                .hasMessageContaining("4567")
                .hasMessageNotContaining("12345678901234567");
    }

    /**
     * Supplies each documented decode rejection with the diagnostic fragment it must produce.
     *
     * @return a stream of case label, the decode that must be refused and a diagnostic fragment
     */
    private static Stream<Arguments> decodeRejections() {
        return Stream.of(
                Arguments.of("a null image",
                        (Executable) () -> TransactionRecordMapper.toEntity(null),
                        "expected 350 bytes"),
                Arguments.of("an image one byte short",
                        (Executable) () -> TransactionRecordMapper
                                .toEntity(Arrays.copyOf(postedImage(), RECORD_LENGTH - 1)),
                        "received 349"),
                Arguments.of("an absent layout",
                        (Executable) () -> TransactionRecordMapper.toEntity(postedImage(), null),
                        "a layout must be named"),
                Arguments.of("a timestamp in neither producer's form",
                        (Executable) () -> TransactionRecordMapper
                                .toEntity(spliced(ORIG_TS_OFFSET, "not-a-timestamp-at-all!!!!")),
                        "neither the target 26-character form"),
                Arguments.of("a non-digit in the category code",
                        (Executable) () -> TransactionRecordMapper
                                .toEntity(spliced(CAT_CD_OFFSET, "00X1")),
                        "TRAN-CAT-CD"),
                Arguments.of("a non-digit in the amount span",
                        (Executable) () -> TransactionRecordMapper
                                .toEntity(spliced(AMOUNT_OFFSET, "000000504X7")),
                        "TRAN-AMT"));
    }

    /**
     * Asserts that each documented decode rejection raises with a diagnostic naming its cause.
     *
     * @param label a short description of the case, shown in the case name
     * @param invocation the decode that must be refused
     * @param fragment a fragment the diagnostic must contain
     */
    @ParameterizedTest(name = "the decode refuses {0}")
    @MethodSource("decodeRejections")
    @DisplayName("each documented decode rejection raises with a diagnostic naming its cause")
    void eachDocumentedDecodeRejectionRaises(String label, Executable invocation, String fragment) {
        assertThatThrownBy(invocation::execute)
                .isInstanceOfAny(FixedWidthCodec.RecordLengthException.class,
                        FixedWidthCodec.FieldCodecException.class,
                        TransactionRecordMapper.RecordMappingException.class)
                .hasMessageContaining(fragment);
    }

    /**
     * Supplies each documented encode rejection with the diagnostic fragment it must produce.
     *
     * @return a stream of case label, the encode that must be refused and a diagnostic fragment
     */
    private static Stream<Arguments> encodeRejections() {
        return Stream.of(
                Arguments.of("an absent transaction",
                        (Executable) () -> TransactionRecordMapper.toRecord(null),
                        "absent transaction"),
                Arguments.of("an absent layout",
                        (Executable) () -> TransactionRecordMapper
                                .toRecord(TransactionRecordMapper.toEntity(postedImage()), null),
                        "a layout must be named"),
                // Refactoring Rationale: the three amount cases that stood here have MOVED to
                //   theAmountMutatorGovernsWhatTheColumnCanHold below, because the boundary they
                //   exercise moved. Transaction.setAmount now canonicalises through
                //   Money.ofPicture, so an absent amount and a ten-integer-digit amount are refused
                //   at the assignment and never reach this encoder, and a third decimal place is
                //   reduced there under the general money contract rather than surviving to be
                //   refused here. Asserting them through the encoder would now assert nothing about
                //   the encoder: the executable would raise before toRecord was called, and the
                //   diagnostic fragment matched would be the mutator's. The encoder retains its own
                //   guards, which are still reachable for an entity the persistence provider
                //   materialised by field assignment, and that reachability is why they were not
                //   removed with these cases.
                Arguments.of("an absent source image for the sign-preserving encode",
                        (Executable) () -> TransactionRecordMapper.toRecord(
                                TransactionRecordMapper.toEntity(postedImage()),
                                TransactionRecordMapper.Layout.POSTED_MASTER, null),
                        "source record is absent"),
                Arguments.of("a source image of the wrong length",
                        (Executable) () -> TransactionRecordMapper.toRecord(
                                TransactionRecordMapper.toEntity(postedImage()),
                                TransactionRecordMapper.Layout.POSTED_MASTER,
                                Arrays.copyOf(postedImage(), RECORD_LENGTH - 1)),
                        "sign-carrier source"));
    }

    /**
     * Asserts that the amount mutator governs exactly what the persisted column can hold.
     *
     * <p>Three properties are asserted together because they are one contract: the column is
     * {@code NUMERIC(11,2) NOT NULL}, so an absent amount and a magnitude needing a tenth integer
     * digit are refused, while a value carrying more decimal places than the column keeps is reduced
     * to two under the general money contract rather than being handed to the driver to coerce.
     *
     * <p>Assumptions: the nine-integer-digit bound is the picture's, not the shared kernel's.
     * {@code Money.of} admits ten integer digits because {@code PIC S9(10)V99} is the widest money
     * field in the reference corpus, so the value refused here is one that would have passed a bare
     * canonicalisation and then failed at the database as a numeric-field-overflow.
     *
     * <p>The method accepts no parameters, returns nothing, and expects
     * {@link NullPointerException} and {@link ArithmeticException} from the two refused assignments.
     */
    @Test
    @DisplayName("the amount mutator refuses an absent or over-wide amount and reduces excess scale")
    void theAmountMutatorGovernsWhatTheColumnCanHold() {
        Transaction transaction = TransactionRecordMapper.toEntity(postedImage());

        assertThatThrownBy(() -> transaction.setAmount(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transaction.setAmount(new BigDecimal("1000000000.00")))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("integer digits")
                .hasMessageNotContaining("1000000000");

        transaction.setAmount(new BigDecimal("1.234"));
        assertThat(transaction.getAmount()).isEqualByComparingTo(new BigDecimal("1.23"));
        assertThat(transaction.getAmount().scale()).isEqualTo(2);

        transaction.setAmount(new BigDecimal("-999999999.99"));
        assertThat(transaction.getAmount()).isEqualByComparingTo(new BigDecimal("-999999999.99"));
    }

    /**
     * Asserts that each documented encode rejection raises rather than emitting a wrong record.
     *
     * @param label a short description of the case, shown in the case name
     * @param invocation the encode that must be refused
     * @param fragment a fragment the diagnostic must contain
     */
    @ParameterizedTest(name = "the encode refuses {0}")
    @MethodSource("encodeRejections")
    @DisplayName("each documented encode rejection raises rather than emitting a wrong record")
    void eachDocumentedEncodeRejectionRaises(String label, Executable invocation, String fragment) {
        assertThatThrownBy(invocation::execute)
                .isInstanceOfAny(FixedWidthCodec.RecordLengthException.class,
                        FixedWidthCodec.FieldCodecException.class,
                        TransactionRecordMapper.RecordMappingException.class)
                .hasMessageContaining(fragment);
    }

    /**
     * Asserts that a fixed-width code present at the wrong width is refused, not padded.
     */
    @Test
    @DisplayName("a fixed-width code present at the wrong width is refused, not padded")
    void aFixedWidthCodePresentAtTheWrongWidthIsRefused() {
        Transaction transaction = TransactionRecordMapper.toEntity(postedImage());
        transaction.setTypeCd("1");

        // WHY : Assumptions: a one-character type code is refused rather than blank-padded to two,
        //       because the receiving reference-data column is itself fixed width. Padding it here
        //       would produce a record that encoded cleanly and then failed to join, which is the
        //       failure that surfaces one job later with no diagnostic attached to its cause.
        assertThatThrownBy(() -> TransactionRecordMapper.toRecord(transaction))
                .isInstanceOf(TransactionRecordMapper.RecordMappingException.class)
                .hasMessageContaining("TRAN-TYPE-CD");
    }

    /**
     * Asserts that the mapper's own geometry constants agree with the report sort control.
     */
    @Test
    @DisplayName("the mapper's own geometry constants agree with the report sort control")
    void theGeometryConstantsAgreeWithTheReportSortControl() {
        // WHY : Assumptions: the two offsets asserted here are corroborated by a source outside any
        //       Java descriptor. app/jcl/TRANREPT.jcl declares its sort fields in ONE-based positions,
        //       TRAN-CARD-NUM at 263 and TRAN-PROC-DT at 305, which are zero-based 262 and 304. That
        //       independent agreement is what makes these offsets facts rather than transcriptions,
        //       and it is asserted here because the mapper holds the same two numbers as constants
        //       for exactly that reason.
        assertThat(CARD_NUM_OFFSET).isEqualTo(263 - 1);
        assertThat(PROC_TS_OFFSET).isEqualTo(305 - 1);
        assertThat(TransactionRecordMapper.Layout.POSTED_MASTER.spec().field("TRAN-CARD-NUM").start())
                .isEqualTo(CARD_NUM_OFFSET);
        assertThat(TransactionRecordMapper.Layout.POSTED_MASTER.spec().field("TRAN-PROC-TS").start())
                .isEqualTo(PROC_TS_OFFSET);
        assertThat(TransactionRecordMapper.Layout.POSTED_MASTER.spec().field("TRAN-AMT").start())
                .isEqualTo(AMOUNT_OFFSET);
    }
}
