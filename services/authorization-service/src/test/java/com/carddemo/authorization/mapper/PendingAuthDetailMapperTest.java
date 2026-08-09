package com.carddemo.authorization.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.PendingAuthDetailResponse;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the single-value rules and projection boundaries of {@link PendingAuthDetailMapper} to the
 * committed detail images.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class owns the part of the mapper's contract that whole-record conversion does not reach: the
 * single-value RULES applied to one field at a time -- a nines complement, a zero fill, a component
 * re-order, a mask -- and four boundary behaviours of the public projections. Those four are that
 * {@link PendingAuthDetailResponse} crosses to JSON with its amount as a STRING, that the projection
 * publishes the APPROVED amount rather than the requested one, that the prefixed and bare unload shapes
 * are not interchangeable, and that the composed timestamp draws its two halves from two DIFFERENT stored
 * fields. Each is a place where a wrong answer is a plausible one, so an unasserted rule reads as
 * coverage while proving nothing.</p>
 *
 * <p>Alternatives Considered: folding these cases into {@code SegmentConversionContractTest}, which
 * already drives the public conversions of both segment mappers. Rejected because that class is
 * organised around whole-record conversion held against a fixture, whereas everything here is a
 * single-value rule exercised at its own entry point. Mixing the two would mean a failing zero-fill
 * reported itself as a failing record conversion, and the class that owns record conversion would grow a
 * second subject with no name for it. This package's charter permits an additional class for exactly this
 * case and requires that it name a contract no sibling holds.</p>
 *
 * <p>Refactoring Rationale: five readings this class was briefed with are superseded by the charters and
 * by {@code docs/architecture/cobol-to-service-traceability.md}, and each is corrected here rather than
 * acted on, because acting on any one of them would have produced an assertion that is wrong or a
 * duplicate that hides its own authority. FIRST, a {@code .repository} TEST package DOES exist in this
 * module and it holds one {@code RepositoryIT} per table, so a database constraint deferred to below is
 * asserted there; the briefing said no such package existed and that
 * {@code fixtures.PendingAuthFraudDomainRepositoryIT} was the single persistence test, which is now the
 * one that remains in {@code fixtures} because its subject is the recorded rows rather than the schema. SECOND, the
 * architecture rule forbidding a binary floating-point member IS inherited by this package -- it selects
 * classes under the analysed {@code com.carddemo} root, and the money package identifier an earlier
 * reading mistook for its scope is only that rule's own emptiness anchor -- so this class asserts NO
 * declaration-level prohibition and would be re-proving an inherited rule if it did. What remains its
 * own is behaviour an import graph cannot see: a scale of two, half-up rounding, and an amount crossing
 * the boundary as a string. THIRD, divergence D-H is the fourteen-character money layout parsed through
 * a thirteen-character receiver, registered as {@code D-AUTH-AMOUNT-TOLERANT-READ} and verified by
 * {@code AuthRequestWireFixtureTest}; it is NOT the reply's transmitted length, which is the separate
 * {@code D-REPLY-PUT-LENGTH}. FOURTH, and consequently, the candidate divergence this class was asked to
 * leave unlettered IS D-H, so no new letter is coined for it. FIFTH, the fixtures directory carries its
 * own README and a contract test enrols every resource, so this class is NOT the sole Explainability
 * carrier for those images -- but the per-test statement of the layout invariant each case depends upon
 * is still mandatory, because a README cannot say which invariant a particular test rests on.
 *
 * <p>Assumptions: three neighbouring contracts are owned elsewhere, and each is named so that a reader
 * does not read their absence here as a gap.</p>
 * <ul>
 *   <li>The field-by-field decode of the canonical image belongs to
 *       {@code SegmentConversionContractTest} and to the fixtures package's own contract test. What this
 *       class reads from that image instead is the 23-character timestamp COMPOSED from it, the complete
 *       27-component PROJECTION of it, and the values its render helpers produce.</li>
 *   <li>The out-of-domain match status in {@code pautdtl1-match-status-invalid.bin} is asserted by
 *       {@code fixtures.PendingAuthDetailDomainRefusalFixtureTest} at the field and entity level and by
 *       {@code SegmentConversionContractTest} at the record level. What this class asserts about that
 *       domain is the value the PROJECTION publishes, which nothing else reads.</li>
 *   <li>The prohibition on a binary floating-point member is an architecture rule inherited by this
 *       package, so no declaration-level assertion is made here. What remains this class's own is
 *       behaviour an import graph cannot see: a scale of two, half-up rounding, and an amount crossing
 *       the boundary as a string.</li>
 * </ul>
 *
 * <p>Assumptions: persistence is exercised by {@code fixtures.PendingAuthFraudDomainRepositoryIT}, filed
 * with the fixture tests that supply its rows, and that is the class named below wherever a database
 * constraint is deferred to. This module declares no {@code .repository} test package.</p>
 *
 * <h2>Where every asserted value comes from</h2>
 *
 * <p>Assumptions: every DECODE-side case works from a committed byte image or from a value read out of
 * one, because a hand-built object cannot disagree with the mapper about where a field lives. Where a
 * case needs two stored fields to DISAGREE -- which no committed image does, every one of them holding an
 * originating time that matches its key -- the disagreement is produced by copying an image and
 * overwriting one field's bytes in the copy at the offset the layout declares. The fixture on disk is
 * never written to.</p>
 *
 * <p>Trade-offs: the ENCODE-side refusal cases are the one exception, and they are synthetic by
 * necessity rather than by preference. An amount too wide for its picture cannot be carried by any
 * committed image, because the picture is what fixes that image's width, so
 * {@link #canonicalWithAmounts(BigDecimal, BigDecimal)} reconstitutes the canonical row through the
 * rehydration factory with literal over-wide amounts and every other member copied from the image. The
 * cost is that those cases trust the factory rather than the decoder; what they buy is the only reachable
 * proof that the encoder refuses a value the schema cannot hold.</p>
 *
 * <p>Assumptions: the 200-byte detail segment's geometry is read from the {@code PAUTDTL} registration
 * in {@link CopybookLayout} rather than written here as literals, so a layout change moves every offset
 * this class patches or inspects with it. The record length, each field's start and each packed money
 * width come from that registration; the two constants this class still declares in its own terms are
 * the unload record's stride and its packed key prefix, which belong to the unload RECORD rather than to
 * the segment. Every layout assertion belongs to the shared kernel's codec tests, so nothing here
 * asserts the registration -- it consumes it.</p>
 *
 * <p>Assumptions: the two nines-complement bases are 99999 for the five-digit Julian date and 999999999
 * for the nine-digit millisecond time. Both directions use the same base, so the operation is its own
 * inverse and the round trip is an identity.</p>
 *
 * <p>Trade-offs: the render helpers are package-private statics and this class is in their package, so
 * they are called directly. Reaching them reflectively, or widening them to public, were both available
 * and both rejected: reflection would turn a signature change into a run-time failure with no compiler
 * warning, and widening production visibility to suit a test would publish an internal rule as an API
 * that some other context could then depend upon. The cost accepted is that this class cannot move to
 * another package, which is the correct constraint rather than an inconvenience.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class PendingAuthDetailMapperTest {

    /**
     * The classpath directory every committed image in this module sits beneath.
     */
    private static final String FIXTURE_ROOT = "fixtures/";

    /**
     * The parent account identifier the single-account detail images were generated under.
     *
     * <p>Assumptions: a detail segment carries no account identifier of its own -- the hierarchy
     * supplies it from the root -- so every conversion of a bare segment takes it as an argument, and
     * this is the value the committed single-account images were produced with.</p>
     */
    private static final Long FIXTURE_ACCOUNT_ID = 10_000_000_001L;

    /**
     * The second parent account identifier the interleaved unload image alternates onto.
     */
    private static final Long SECOND_ACCOUNT_ID = 10_000_000_002L;

    /**
     * The registered geometry of the detail segment, and the single source of every offset below.
     *
     * <p>Assumptions: the offsets and widths this class patches and inspects are READ from this
     * registration rather than written as literals, so a layout correction moves them all together. A
     * duplicated offset is the one drift this class could not detect on its own: a record read one byte
     * out of alignment still decodes to plausible characters, so nothing raises and no case fails.</p>
     */
    private static final CopybookLayout.RecordSpec SEGMENT_LAYOUT = CopybookLayout.layout("PAUTDTL");

    private static final int SEGMENT_LENGTH = SEGMENT_LAYOUT.reclen();

    private static final int ORIGINATING_DATE_OFFSET = startOf("PA-AUTH-ORIG-DATE");

    private static final int ORIGINATING_TIME_OFFSET = startOf("PA-AUTH-ORIG-TIME");

    private static final int RESPONSE_CODE_OFFSET = startOf("PA-AUTH-RESP-CODE");

    private static final int RESPONSE_REASON_OFFSET = startOf("PA-AUTH-RESP-REASON");

    private static final int RESPONSE_REASON_WIDTH = widthOf("PA-AUTH-RESP-REASON");

    private static final int APPROVED_AMOUNT_OFFSET = startOf("PA-APPROVED-AMT");

    private static final int MONEY_SPAN_WIDTH = widthOf("PA-APPROVED-AMT");

    private static final int PADDING_OFFSET = startOf("FILLER");

    /**
     * The declared byte length of one prefixed unload record.
     *
     * <p>Assumptions: this and the prefix width below are the two geometries this class still states in
     * its own terms, because they belong to the unload RECORD rather than to the segment and the segment
     * registration therefore does not carry them. The prefix is six bytes, the packed form of the
     * eleven-digit signed parent key the reference unload record writes ahead of its segment, which is
     * what makes the difference between the two strides a named quantity rather than an unexplained gap
     * between 200 and 206.</p>
     */
    private static final int UNLOAD_RECORD_LENGTH = 206;

    private static final int UNLOAD_PREFIX_WIDTH = 6;

    /**
     * The width of the host variable the reference application binds its composed timestamp into.
     *
     * <p>Assumptions: 26, from the {@code AUTH-TS PIC X(26)} host variable the reference fraud
     * statements bind. The composed value is 23 characters, so three positions are always padding and
     * the direction of that padding is what one case below establishes.</p>
     */
    private static final int TIMESTAMP_HOST_VARIABLE_LENGTH = 26;

    /**
     * The number of components the detail response projection declares.
     *
     * <p>Assumptions: 27, and the count is asserted rather than assumed so that a twenty-eighth
     * component added without a rendering rule fails here instead of reaching a client as a null.</p>
     *
     * <p>Assumptions: this constant describes the RESPONSE RECORD and nothing else. It is used only by
     * the reflection over {@link PendingAuthDetailResponse}, never to size a segment field map, because
     * the two counts are equal by coincidence rather than by correspondence -- the projection omits six
     * segment fields the reference screen never displayed and adds six components of screen chrome no
     * segment holds. The segment side has its own constant below.</p>
     */
    private static final int RESPONSE_COMPONENT_COUNT = 27;

    /**
     * The registered name of the detail segment's layout in the shared kernel.
     */
    // WHY : Assumptions: the name is the copybook's own, PAUTDTL, and it is named once here so the
    //       derivation below and any future reader reach the same registration. The shared kernel is
    //       where the geometry lives; this class asserts the projection over it and does not restate it.
    private static final String SEGMENT_LAYOUT_NAME = "PAUTDTL";

    /**
     * The name the layout gives the segment's trailing padding descriptor.
     */
    // WHY : Assumptions: written once and used both by the derivation below and by the two assertions
    //       that name the descriptor, so a rename in the layout cannot leave one of the three behind.
    //       CIPAUDTY L54 declares it FILLER PIC X(17) at offset 183.
    private static final String PADDING_FIELD_NAME = "FILLER";

    /**
     * The number of descriptors of the detail segment that carry meaning, padding excluded.
     */
    // WHY : Refactoring Rationale: DERIVED from the registered layout rather than written as a literal,
    //       and declared separately from the response count above rather than borrowing it. The two
    //       figures are both 27 today and that is a coincidence this class states in prose; an
    //       assertion that borrowed one for the other would keep passing if the segment gained a field
    //       and the response did not -- and would then fail in a place that names the response while
    //       the segment is what changed. Deriving it also means a descriptor added to the layout is
    //       reflected here automatically, so the segment assertions test the projection rather than a
    //       number somebody remembered to update.
    // WHY : Assumptions: the padding descriptor is EXCLUDED by name rather than by subtracting one from
    //       the total. Subtracting encodes an assumption about how many padding runs the segment has,
    //       which is true of this layout and is not a property of layouts in general; filtering states
    //       exactly what is meant and stays correct if the layout ever declared a second run.
    private static final int SEGMENT_MEANINGFUL_FIELD_COUNT = (int) CopybookLayout
            .layout(SEGMENT_LAYOUT_NAME).fields().stream()
            .filter(field -> !PADDING_FIELD_NAME.equals(field.name()))
            .count();

    /**
     * The account number every detail image in this module presents, unmasked.
     *
     * <p>Assumptions: the images share one card so that a disclosure sweep has a single value to look
     * for. A per-image card number would make the sweep below prove only that each image differs from
     * the others.</p>
     */
    private static final String UNMASKED_CARD_NUMBER = "4000123456789010";

    /**
     * The serialiser the disclosure and money cases publish a response through.
     *
     * <p>Assumptions: only the money module is registered, matching the module the shared kernel
     * publishes through the service loader rather than a fully configured application mapper. Adding
     * anything else would let a second module's behaviour explain a passing assertion.</p>
     */
    private static final JsonMapper MAPPER =
            JsonMapper.builder().addModule(new MoneyModule()).build();

    /**
     * Reads one field's zero-based start offset off the registered segment geometry.
     *
     * @param field the copybook field name, spelled exactly as the registration declares it; must not be
     *     {@code null}
     * @return the field's zero-based start offset within a detail segment
     * @throws IllegalArgumentException if the registration declares no field of that name, which means
     *     the name here is misspelled or the layout renamed the field
     * @throws NullPointerException if {@code field} is {@code null}
     */
    private static int startOf(String field) {
        return SEGMENT_LAYOUT.field(field).start();
    }

    /**
     * Reads one field's declared byte width off the registered segment geometry.
     *
     * @param field the copybook field name, spelled exactly as the registration declares it; must not be
     *     {@code null}
     * @return the field's declared width in bytes, which for a packed field is its packed width
     * @throws IllegalArgumentException if the registration declares no field of that name
     * @throws NullPointerException if {@code field} is {@code null}
     */
    private static int widthOf(String field) {
        return SEGMENT_LAYOUT.field(field).length();
    }

    /**
     * Reads one committed image off the test classpath as bytes.
     *
     * <p>Assumptions: the resource is read whole and is never trimmed or decoded. A recorded byte image
     * in this module carries no trailing newline, so its length equals its content length and a
     * length assertion over it is meaningful; reading it as text would fold the packed spans through a
     * character decoder and change bytes that are not characters.</p>
     *
     * @param name the resource name below the fixture root; must not be {@code null}
     * @return the resource's bytes, never {@code null}
     * @throws AssertionError if the resource does not resolve on the test classpath, which means the
     *     image was renamed or removed rather than that any rule under test is wrong
     * @throws UncheckedIOException if the resource resolves but cannot be read
     * @throws NullPointerException if {@code name} is {@code null}, raised by the resource lookup
     */
    private static byte[] bytesOf(String name) {
        try (InputStream stream = PendingAuthDetailMapperTest.class.getClassLoader()
                .getResourceAsStream(FIXTURE_ROOT + name)) {
            if (stream == null) {
                throw new AssertionError("fixture " + name + " is not on the test classpath");
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("fixture " + name + " could not be read", failure);
        }
    }

    /**
     * Cuts one whole record out of a multi-record image at a fixed stride.
     *
     * <p>Assumptions: the ordinal is multiplied by the stride rather than searched for, because these
     * images carry no delimiter of any kind -- a record boundary exists only as a multiple of the
     * declared length. A stride that did not divide the file would land mid-record and still return
     * bytes, which is why the divisibility of each image is asserted where it is loaded.</p>
     *
     * @param image the whole committed image; must not be {@code null}
     * @param ordinal the zero-based record number to cut
     * @param stride the declared length of one record in this image
     * @return a newly allocated copy of exactly {@code stride} bytes
     * @throws ArrayIndexOutOfBoundsException if {@code ordinal} or {@code stride} is negative
     * @throws IllegalArgumentException if the computed start exceeds the computed end
     * @throws NullPointerException if {@code image} is {@code null}
     */
    private static byte[] recordAt(byte[] image, int ordinal, int stride) {
        return Arrays.copyOfRange(image, ordinal * stride, (ordinal + 1) * stride);
    }

    /**
     * Copies an image and overwrites one character field in the copy.
     *
     * <p>Assumptions: the replacement is written as US-ASCII bytes at the offset the layout declares,
     * and the caller supplies a replacement of exactly the field's declared width, so the copy stays a
     * whole record of the same length. The image on disk is never touched; producing the disagreement in
     * a copy is what lets a case assert which of two stored fields a rule actually reads, and no
     * committed image carries such a disagreement because the reference application writes both fields
     * from one observation.</p>
     *
     * @param source the image to copy; must not be {@code null}
     * @param offset the zero-based offset of the field to overwrite
     * @param replacement the characters to write, which must not run past the end of the record; must
     *     not be {@code null}
     * @return a newly allocated copy of {@code source} carrying {@code replacement} at {@code offset}
     * @throws IllegalArgumentException if the replacement would run past the end of the record, so a
     *     silently truncated patch cannot produce a case that passes for the wrong reason
     * @throws NullPointerException if {@code source} or {@code replacement} is {@code null}
     */
    private static byte[] patchedText(byte[] source, int offset, String replacement) {
        byte[] patched = source.clone();
        byte[] written = replacement.getBytes(StandardCharsets.US_ASCII);

        // WHY : Assumptions: the bound is CHECKED rather than documented, because the failure it guards
        //   is silent. An over-long replacement would throw from the copy, but an over-long OFFSET plus a
        //   short replacement lands inside the record and patches the wrong field, and the case would
        //   then pass or fail for a reason unrelated to the rule it names.
        if (offset < 0 || offset + written.length > patched.length) {
            throw new IllegalArgumentException("a patch of " + written.length + " bytes at offset "
                    + offset + " does not fit a record of " + patched.length + " bytes");
        }
        System.arraycopy(written, 0, patched, offset, written.length);
        return patched;
    }

    /**
     * Builds the screen context the detail projection needs but no segment holds.
     *
     * <p>Assumptions: the six values are the running task's own identity and clock rather than
     * properties of the authorization, so they are supplied rather than derived. Fixed values are used
     * because no case below asserts anything about them; what the cases assert is that the
     * authorization's own values reach the projection unchanged or correctly rendered, and a varying
     * chrome would add noise to that without adding coverage.</p>
     *
     * @return a populated context, never {@code null}
     */
    private static PendingAuthDetailMapper.ScreenContext screenContext() {
        return new PendingAuthDetailMapper.ScreenContext("CP01", "CardDemo", "COPAUS1C",
                "Pending Authorization Details", LocalDateTime.of(2024, 7, 15, 9, 15, 30), null);
    }

    /**
     * Decodes the canonical detail image into its entity under the fixture's parent account.
     *
     * @return the entity the canonical image decodes to, never {@code null}
     */
    private static PendingAuthDetail canonicalEntity() {
        return PendingAuthDetailMapper.toEntity(bytesOf("pautdtl1-canonical.bin"),
                FIXTURE_ACCOUNT_ID);
    }

    /**
     * Rebuilds the canonical authorization with two substituted amounts.
     *
     * <p>Assumptions: the entity's amount members carry no setter and its originating constructor
     * validates only the match status and the entry mode, so substituting an amount means reconstituting
     * the row through the rehydration factory with every other member copied from the canonical image.
     * That is what lets an encode-side refusal be driven from a value rather than from a byte image: no
     * committed image can carry an amount wider than its own picture, because the picture is what fixes
     * the image's width.</p>
     *
     * @param requested the requested amount to carry, which may be wider than the picture admits
     * @param approved the approved amount to carry, which may be wider than the picture admits
     * @return a reconstituted authorization identical to the canonical one but for its two amounts
     */
    private static PendingAuthDetail canonicalWithAmounts(BigDecimal requested, BigDecimal approved) {
        PendingAuthDetail source = canonicalEntity();
        return PendingAuthDetail.rehydrated(source.getId(), source.getAuthOrigDate(),
                source.getAuthOrigTime(), source.getCardNum(), source.getAuthType(),
                source.getCardExpiryDate(), source.getMessageType(), source.getMessageSource(),
                source.getAuthIdCode(), source.getAuthRespCode(), source.getAuthRespReason(),
                source.getProcessingCode(), requested, approved, source.getMerchantCategoryCode(),
                source.getAcqrCountryCode(), source.getPosEntryMode(), source.getMerchantId(),
                source.getMerchantName(), source.getMerchantCity(), source.getMerchantState(),
                source.getMerchantZip(), source.getTransactionId(), source.getMatchStatus());
    }

    /**
     * Renders every component of a detail response as text for a value sweep.
     *
     * <p>Assumptions: the components are read reflectively rather than named one by one, so a component
     * added later is swept without this helper being revisited. That matters for the disclosure sweep in
     * particular: a sweep written against a fixed list would keep passing after a twenty-eighth
     * component started carrying the value the sweep exists to look for.</p>
     *
     * @param response the projection to read; must not be {@code null}
     * @return one string per declared component, null components rendered as the empty string, never
     *     {@code null}
     * @throws AssertionError if a component accessor cannot be invoked, which can only mean the record's
     *     accessors are no longer reachable from this package
     * @throws NullPointerException if {@code response} is {@code null}, raised by the first accessor
     *     invocation
     */
    private static String[] componentTextOf(PendingAuthDetailResponse response) {
        RecordComponent[] components = PendingAuthDetailResponse.class.getRecordComponents();
        String[] rendered = new String[components.length];
        for (int index = 0; index < components.length; index++) {
            try {
                Object value = components[index].getAccessor().invoke(response);
                rendered[index] = value == null ? "" : value.toString();
            } catch (ReflectiveOperationException unreachable) {
                throw new AssertionError("the response component "
                        + components[index].getName() + " is no longer readable", unreachable);
            }
        }
        return rendered;
    }

    /**
     * The four complement operations, which no other test in this module calls.
     *
     * <p>Assumptions: the images this group reads are three single-record files of exactly 200 bytes --
     * {@code pautdtl1-canonical.bin}, whose packed date complement is {@code 76 81 9C} and whose packed
     * time complement is {@code 85 69 74 87 6C}; {@code pautdtl1-raw-complement-trap.bin}, whose two
     * complements are {@code 75 89 9C} and {@code 87 99 99 99 9C}; and
     * {@code pautdtl1-time-leading-nines.bin}, whose two complements are {@code 75 89 9C} and
     * {@code 99 99 98 99 9C} -- together with the two-record 400-byte
     * {@code pautdtl1-date-formats.bin}, whose SECOND record carries the date complement
     * {@code 00 63 4C}. Every one of those spans is packed decimal, two digits to a byte with the sign
     * in the final low nibble, so none of it is text.</p>
     *
     * <p>Assumptions: the operations are exercised at their own entry points AND through the public key
     * conversion, so that the group establishes both the arithmetic and the fact that the public path
     * routes to it. Asserting only the public path would leave the four helpers unreachable from any
     * test, which is the state that made this group necessary; asserting only the helpers would leave the
     * public path free to compute the same answer some other way and drift.</p>
     */
    @Nested
    @DisplayName("the nines-complement key arithmetic")
    class NinesComplementArithmetic {

        /**
         * Confirms both components decode at the bases the reference application subtracts from.
         *
         * <p>Assumptions: the two bases are five nines and nine nines, matching the five and nine digit
         * positions the two key pictures declare, and they appear as literals on both sides of the
         * reference application -- in the encode that writes the segment and in the decodes that read it
         * back. A base written with one nine too few would decode every key to a value ten times too
         * small and still produce positive integers, so the constants are asserted rather than
         * assumed.</p>
         *
         * <p>Assumptions: the canonical image's stored complements are 76819 and 856974876, and they
         * decode to the Julian date 23180 and the millisecond time 143025123. The image's separately
         * stored originating date of {@code 230629} corroborates the first independently, day 180 of
         * 2023 being the twenty-ninth of June.</p>
         */
        @Test
        @DisplayName("both components decode at the bases the reference application subtracts from")
        void bothComponentsDecodeAtTheDeclaredBases() {
            assertThat(PendingAuthDetailMapper.AUTH_DATE_COMPLEMENT_BASE).isEqualTo(99_999);
            assertThat(PendingAuthDetailMapper.AUTH_TIME_COMPLEMENT_BASE).isEqualTo(999_999_999);

            assertThat(PendingAuthDetailMapper.decodeAuthDate(76_819)).isEqualTo(23_180);
            assertThat(PendingAuthDetailMapper.decodeAuthTime(856_974_876)).isEqualTo(143_025_123);

            // WHY : Assumptions: the public key conversion is asserted to AGREE with the helpers rather
            //       than being asserted against the same literals a second time. Comparing it to the
            //       helper is what establishes the routing; comparing it to a literal would pass on an
            //       implementation that had stopped calling the helper and duplicated the arithmetic.
            PendingAuthDetailKey key = PendingAuthDetailMapper.toKey(
                    bytesOf("pautdtl1-canonical.bin"), FIXTURE_ACCOUNT_ID);
            assertThat(key.getAuthDate()).isEqualTo(PendingAuthDetailMapper.decodeAuthDate(76_819));
            assertThat(key.getAuthTime())
                    .isEqualTo(PendingAuthDetailMapper.decodeAuthTime(856_974_876));
        }

        /**
         * Confirms the complement is its own inverse for every stored value the corpus carries.
         *
         * <p>Assumptions: the encode direction has no caller anywhere else, so this is the only place
         * the identity {@code base - (base - v) == v} is exercised. It is the cheapest available proof
         * that the decode is right, because a decode alone can only be compared with a written
         * expectation whereas the identity holds against the stored value itself.</p>
         *
         * <p>Assumptions: the ten date complements listed are every distinct one in this module's detail
         * images, recomputed against their bytes: 76819, 76634, 75998, 75904, 75899, 75889, 75888,
         * 75879, 75878 and 634. Sweeping the whole set rather than one value is what makes the leading
         * zero case part of the identity rather than a separate concession.</p>
         */
        @Test
        @DisplayName("the complement is its own inverse in both directions")
        void theComplementIsItsOwnInverseInBothDirections() {
            int[] storedDates = {76_819, 76_634, 75_998, 75_904, 75_899, 75_889, 75_888, 75_879,
                    75_878, 634};
            for (int stored : storedDates) {
                int decoded = PendingAuthDetailMapper.decodeAuthDate(stored);
                assertThat(PendingAuthDetailMapper.encodeAuthDate(decoded))
                        .as("re-encoding the decode of %d must reproduce it", stored)
                        .isEqualTo(stored);
            }

            int[] storedTimes = {856_974_876, 999_998_999, 879_999_999, 908_499_999, 836_954_499,
                    836_954_249, 764_040_000, 886_999_999};
            for (int stored : storedTimes) {
                int decoded = PendingAuthDetailMapper.decodeAuthTime(stored);
                assertThat(PendingAuthDetailMapper.encodeAuthTime(decoded))
                        .as("re-encoding the decode of %d must reproduce it", stored)
                        .isEqualTo(stored);
            }
        }

        /**
         * Confirms the trap image's stored complements are structurally impossible until they decode.
         *
         * <p>Assumptions: this is what the trap image is for. Its stored date complement is 75899, whose
         * final three digits read as a day of year of 899 -- no year has one -- while the decoded 24100
         * reads as day 100 of 2024; and its stored time complement is 879999999, whose leading two
         * digits read as an hour of 87 while the decoded 120000000 reads as the twelfth hour. A decode
         * that leaked the stored value would therefore produce a date or a clock reading that cannot
         * exist, and this image is the one that makes that failure loud rather than plausible.</p>
         *
         * <p>Assumptions: the impossibility is asserted on the digits rather than by parsing a date,
         * because the point is that the RAW value is not a date at all. Parsing it would raise, and a
         * raise is indistinguishable from the many other reasons a parse can fail.</p>
         */
        @Test
        @DisplayName("the trap image's stored complements are impossible until they are decoded")
        void theTrapComplementsAreImpossibleUntilDecoded() {
            byte[] trap = bytesOf("pautdtl1-raw-complement-trap.bin");
            assertThat(trap).hasSize(SEGMENT_LENGTH);

            // WHY : Assumptions: the two stored values are READ OUT of the image rather than written as
            //       literals, so an image regenerated with different complements fails here instead of
            //       leaving this case passing over numbers the corpus no longer contains. The field
            //       projection returns the two key components still complemented, which is what makes it
            //       the right source for the raw values this case is about.
            Map<String, Object> fields = PendingAuthDetailMapper.toSegmentFields(trap);
            int storedDate = ((BigDecimal) fields.get("PA-AUTH-DATE-9C")).intValueExact();
            int storedTime = ((BigDecimal) fields.get("PA-AUTH-TIME-9C")).intValueExact();
            assertThat(storedDate).isEqualTo(75_899);
            assertThat(storedTime).isEqualTo(879_999_999);

            assertThat(storedDate % 1000)
                    .as("no year has a day 899, so the stored value cannot be a Julian date")
                    .isEqualTo(899)
                    .isGreaterThan(366);
            assertThat(PendingAuthDetailMapper.decodeAuthDate(storedDate)).isEqualTo(24_100);
            assertThat(PendingAuthDetailMapper.decodeAuthDate(storedDate) % 1000)
                    .as("day 100 of 2024 is a day that exists")
                    .isEqualTo(100)
                    .isLessThanOrEqualTo(366);

            assertThat(PendingAuthDetailMapper.paddedTimeDigits(storedTime).substring(0, 2))
                    .as("no clock has an hour 87, so the stored value cannot be a time of day")
                    .isEqualTo("87");
            assertThat(PendingAuthDetailMapper.decodeAuthTime(storedTime)).isEqualTo(120_000_000);
            assertThat(PendingAuthDetailMapper.paddedTimeDigits(
                    PendingAuthDetailMapper.decodeAuthTime(storedTime)).substring(0, 2))
                    .as("the twelfth hour is an hour that exists")
                    .isEqualTo("12");

            // WHY : Assumptions: the projection is swept for the two raw values as well, because the
            //       failure this image exists to catch is a LEAK rather than a wrong arithmetic result.
            //       An implementation that decoded the key correctly and then published the stored
            //       complement somewhere else would satisfy every assertion above.
            PendingAuthDetail loaded = PendingAuthDetailMapper.toEntity(trap, FIXTURE_ACCOUNT_ID);
            assertThat(loaded.getId().getAuthDate()).isEqualTo(24_100).isNotEqualTo(storedDate);
            assertThat(loaded.getId().getAuthTime()).isEqualTo(120_000_000).isNotEqualTo(storedTime);
            for (String component : componentTextOf(
                    PendingAuthDetailMapper.toResponse(loaded, null, screenContext()))) {
                assertThat(component)
                        .as("no projected component may carry a stored complement")
                        .doesNotContain(Integer.toString(storedDate))
                        .doesNotContain(Integer.toString(storedTime));
            }
        }

        /**
         * Confirms a complement whose leading byte is a zero byte decodes without being truncated.
         *
         * <p>Assumptions: the second record of {@code pautdtl1-date-formats.bin} stores the date
         * complement 634 as the three bytes {@code 00 63 4C}, so its FIRST byte is a zero byte. That is
         * the single most likely silent failure in this decode path: a reader that treated the zero byte
         * as a terminator, or that trimmed leading zero bytes before unpacking, would decode 634 as 34
         * or as 4 and produce a Julian date that is entirely well formed. The stored 634 decodes to
         * 99365, day 365 of the two-digit year 99, which the record's separately stored originating date
         * of {@code 991231} corroborates.</p>
         *
         * <p>Assumptions: the zero byte is asserted to be present in the image before the decode is
         * asserted, so a fixture regenerated without it would fail here rather than leaving this case
         * passing over bytes that no longer exercise it.</p>
         */
        @Test
        @DisplayName("a complement whose leading byte is zero decodes without truncation")
        void aLeadingZeroByteIsAPackedDigitPairAndNotATerminator() {
            byte[] pair = bytesOf("pautdtl1-date-formats.bin");
            assertThat(pair).hasSize(2 * SEGMENT_LENGTH);

            byte[] boundary = recordAt(pair, 1, SEGMENT_LENGTH);
            assertThat(boundary[0])
                    .as("the leading byte of this record's packed date complement is a zero byte")
                    .isEqualTo((byte) 0x00);

            assertThat(PendingAuthDetailMapper.decodeAuthDate(634)).isEqualTo(99_365);
            assertThat(PendingAuthDetailMapper.encodeAuthDate(99_365)).isEqualTo(634);
            assertThat(PendingAuthDetailMapper.toKey(boundary, FIXTURE_ACCOUNT_ID).getAuthDate())
                    .as("the public path must not lose the leading zero byte either")
                    .isEqualTo(99_365);
        }

        /**
         * Confirms a component outside its base is refused rather than wrapping into a valid-looking key.
         *
         * <p>Assumptions: the guard is a RANGE check against the base and deliberately not a calendar or
         * clock check, so the values refused here are the ones the field's own picture cannot hold. Each
         * of the four calls raises {@link IllegalArgumentException}: a negative input in either
         * direction, and an input one past the base. Without the guard, the subtraction would return a
         * negative number that the key's integer columns would accept, so the refusal is what keeps an
         * unrepresentable stored value from becoming a stored row.</p>
         *
         * <p>Assumptions: the calendar question is answered elsewhere and is not folded in here -- the
         * migration's own column constraints answer it at the database, and composing an instant answers
         * it by refusing one that names no day. Folding both into this guard would refuse reference data
         * on grounds the reference application never applied.</p>
         */
        @Test
        @DisplayName("a component outside its complement base is refused")
        void aComponentOutsideItsBaseIsRefused() {
            int dateBase = PendingAuthDetailMapper.AUTH_DATE_COMPLEMENT_BASE;
            int timeBase = PendingAuthDetailMapper.AUTH_TIME_COMPLEMENT_BASE;

            assertThatThrownBy(() -> PendingAuthDetailMapper.decodeAuthDate(dateBase + 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authorization date");
            assertThatThrownBy(() -> PendingAuthDetailMapper.encodeAuthDate(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authorization date");
            assertThatThrownBy(() -> PendingAuthDetailMapper.decodeAuthTime(timeBase + 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authorization time");
            assertThatThrownBy(() -> PendingAuthDetailMapper.encodeAuthTime(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authorization time");
        }
    }

    /**
     * The nine-digit zero padding a decoded time is rendered through before it is sliced.
     *
     * <p>Assumptions: the image this group reads is {@code pautdtl1-time-leading-nines.bin}, one 200-byte
     * record whose packed time complement {@code 99 99 98 99 9C} decodes to 1000 -- one second past
     * midnight expressed in milliseconds. Every one of that record's eight packed key bytes, the date's
     * {@code 75 89 9C} included, falls outside the range a digit character occupies, so an
     * implementation that had read the key as text would produce nothing resembling a number here.</p>
     *
     * <p>Assumptions: the padding is load-bearing rather than presentational. The reference application
     * declares its decoded time as a nine-position display numeric and overlays it with an alphanumeric
     * redefinition, then slices that overlay at constant positions; a display numeric is stored
     * zero-filled to its declared width, so the overlay always presents nine characters. This is the
     * only test in the module that reaches the padding operation at all.</p>
     */
    @Nested
    @DisplayName("the nine-digit time padding")
    class PaddedTimeDigits {

        /**
         * Confirms a time before ten in the morning is padded before the constant slices address it.
         *
         * <p>Assumptions: the failure this guards against is silent rather than loud. The stored image
         * decodes to 1000, which pads to {@code 000001000} and slices to the zeroth hour, the zeroth
         * minute and the first second; an unpadded {@code 1000} slices to the tenth hour and a minute
         * the string cannot supply. Both readings are well formed and only one of them is the stored
         * time, which is why the unpadded width is asserted alongside the padded one rather than the
         * padded one being asserted alone.</p>
         */
        @Test
        @DisplayName("a time before ten in the morning pads to nine digits before it is sliced")
        void aTimeBeforeTenInTheMorningPadsBeforeItIsSliced() {
            byte[] segment = bytesOf("pautdtl1-time-leading-nines.bin");
            assertThat(segment).hasSize(SEGMENT_LENGTH);

            int decoded = PendingAuthDetailMapper.toKey(segment, FIXTURE_ACCOUNT_ID)
                    .getAuthTime().intValue();
            assertThat(decoded).isEqualTo(1000);

            String padded = PendingAuthDetailMapper.paddedTimeDigits(decoded);
            assertThat(padded).isEqualTo("000001000")
                    .hasSize(PendingAuthDetailMapper.TIME_DIGIT_POSITIONS);
            assertThat(padded.substring(0, 2)).as("the hour slice").isEqualTo("00");
            assertThat(padded.substring(2, 4)).as("the minute slice").isEqualTo("00");
            assertThat(padded.substring(4, 6)).as("the second slice").isEqualTo("01");
            assertThat(padded.substring(6, 9)).as("the millisecond slice").isEqualTo("000");

            assertThat(Integer.toString(decoded))
                    .as("an unpadded rendering is four characters, so its hour slice reads as ten")
                    .hasSize(4)
                    .startsWith("10");
        }

        /**
         * Confirms the narrowest and the widest admissible times both render nine digits.
         *
         * <p>Assumptions: the two ends are exercised because the padding is implemented as a
         * fixed-width format rather than as a loop, so a width taken from the value instead of from the
         * declaration would show up at one end and not in the middle. The widest admissible value is the
         * complement base itself, which is the largest number the nine declared positions hold.</p>
         */
        @Test
        @DisplayName("the narrowest and widest admissible times both render nine digits")
        void bothEndsOfTheRangeRenderNineDigits() {
            assertThat(PendingAuthDetailMapper.paddedTimeDigits(0)).isEqualTo("000000000");
            assertThat(PendingAuthDetailMapper.paddedTimeDigits(
                    PendingAuthDetailMapper.AUTH_TIME_COMPLEMENT_BASE)).isEqualTo("999999999");
        }

        /**
         * Confirms a time needing more or fewer than the nine declared positions is refused.
         *
         * <p>Assumptions: both calls raise {@link IllegalArgumentException}. A negative value would
         * render a minus sign into a position the constant slices read as a digit, and a value past the
         * base would render ten characters and shift every slice by one, so each would produce a
         * well-formed clock reading of the wrong time rather than a failure. Refusing at the render is
         * what keeps that from reaching a composed timestamp.</p>
         */
        @Test
        @DisplayName("a time outside the nine declared positions is refused")
        void aTimeOutsideTheDeclaredPositionsIsRefused() {
            int base = PendingAuthDetailMapper.AUTH_TIME_COMPLEMENT_BASE;

            assertThatThrownBy(() -> PendingAuthDetailMapper.paddedTimeDigits(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("digit positions");
            assertThatThrownBy(() -> PendingAuthDetailMapper.paddedTimeDigits(base + 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("digit positions");
        }
    }

    /**
     * The two-clock composition of the authorization timestamp, and the separators a guess inverts.
     *
     * <p>Assumptions: the images this group reads are {@code pautdtl1-canonical.bin}, one 200-byte record
     * storing the originating date {@code 230629}, the originating time {@code 143025} and a packed time
     * complement that decodes to 143025123; and {@code pautdtl1-order-same-day-times.bin}, three
     * 200-byte records sharing the originating date {@code 240404} whose packed time complements decode
     * to 163045500, 91500000 and 163045750. The third of those is the record whose milliseconds are
     * non-zero and differ from the first, which is what makes the millisecond positions distinguishable
     * from the literal ones.</p>
     *
     * <p>Assumptions: the composed form is {@code YY-MM-DD HH.MI.SSsss000} at 23 characters, and its two
     * halves come from two DIFFERENT stored fields. The calendar half is the acquirer's declared
     * originating date, copied into the segment as characters; the clock half is this system's own
     * observation, composed as a millisecond time of day and complemented into the packed key. Neither
     * the separately stored originating TIME nor the packed DATE key participates, even though both are
     * available, so the two symmetric implementations a reader would reach for -- two character fields,
     * or two packed fields -- are both wrong. The asymmetry is deliberate reference behaviour and not an
     * oversight to reconcile: the composed value is the key of an existing relational row, so drawing
     * either half from the other clock would address a different row.</p>
     *
     * <p>Assumptions: no committed image carries a disagreement between the two clocks, because the
     * reference application writes both fields from one observation, so the cases that establish the
     * provenance patch a COPY of an image and assert what does and does not move as a result. That is
     * the only construction that can distinguish "reads this field" from "reads a field that happens to
     * agree".</p>
     */
    @Nested
    @DisplayName("the twenty-three character authorization timestamp")
    class AuthorizationTimestampComposition {

        /**
         * Confirms the clock half is read from the packed key and not from the stored originating time.
         *
         * <p>Assumptions: the canonical image's two clocks agree, so a copy is made with the originating
         * time overwritten to {@code 000000} and the composition asserted to be UNCHANGED. The same copy
         * is then passed to the display rendering, which DOES change -- proving that the field is read
         * elsewhere in this class and that the composition's indifference to it is a property of the
         * composition rather than of the field being ignored everywhere. An implementation that composed
         * from the originating time would produce {@code 23-06-29 00.00.00000000} here, which is a
         * perfectly well-formed timestamp naming an instant fourteen and a half hours earlier.</p>
         */
        @Test
        @DisplayName("the clock half comes from the packed key, not from the stored originating time")
        void theClockHalfComesFromThePackedKey() {
            byte[] canonical = bytesOf("pautdtl1-canonical.bin");
            assertThat(canonical).hasSize(SEGMENT_LENGTH);

            PendingAuthDetail stored = PendingAuthDetailMapper.toEntity(canonical, FIXTURE_ACCOUNT_ID);
            assertThat(stored.getAuthOrigTime()).isEqualTo("143025");
            assertThat(PendingAuthDetailMapper.authTimestampText(stored))
                    .isEqualTo("23-06-29 14.30.25123000");

            byte[] disagreeing = patchedText(canonical, ORIGINATING_TIME_OFFSET, "000000");
            PendingAuthDetail patched =
                    PendingAuthDetailMapper.toEntity(disagreeing, FIXTURE_ACCOUNT_ID);

            assertThat(patched.getAuthOrigTime())
                    .as("the copy really does carry a different originating time")
                    .isEqualTo("000000");
            assertThat(patched.getId().getAuthTime())
                    .as("the packed key is untouched by the patch")
                    .isEqualTo(143_025_123);
            assertThat(PendingAuthDetailMapper.authTimestampText(patched))
                    .as("the composition follows the packed key and ignores the originating time")
                    .isEqualTo("23-06-29 14.30.25123000");

            // WHY : Assumptions: the display rendering is asserted on the same copy so that the case
            //       cannot be satisfied by an implementation that never reads the originating time at
            //       all. One of the two must move and the other must not, and that pair is the claim.
            assertThat(PendingAuthDetailMapper.renderOriginatingTime(patched.getAuthOrigTime()))
                    .as("the display rendering does read the originating time")
                    .isEqualTo("00:00:00");
        }

        /**
         * Confirms the calendar half is read from the originating characters and not from the packed key.
         *
         * <p>Assumptions: the mirror of the clock case, and it needs the mirror because the two halves
         * fail independently. A copy is made with the originating date overwritten to {@code 231231} and
         * the composition asserted to MOVE with it while the packed date key stays at the Julian 23180.
         * An implementation that composed the calendar half from the packed date key would ignore the
         * patch and would additionally have to invent a month and a day, because a Julian date has
         * neither.</p>
         *
         * <p>Assumptions: the date-time rendering is asserted alongside the character rendering because
         * the two are separate code paths over the same two members, and the reference application's own
         * two consumers differ in exactly that way -- one binds the characters and lets the database
         * convert them, the other takes a value. A disagreement between them would put one instant on a
         * screen and a different one in a fraud row's key.</p>
         */
        @Test
        @DisplayName("the calendar half comes from the originating characters, not from the packed key")
        void theCalendarHalfComesFromTheOriginatingCharacters() {
            byte[] canonical = bytesOf("pautdtl1-canonical.bin");
            byte[] moved = patchedText(canonical, ORIGINATING_DATE_OFFSET, "231231");

            PendingAuthDetail stored = PendingAuthDetailMapper.toEntity(canonical, FIXTURE_ACCOUNT_ID);
            PendingAuthDetail patched = PendingAuthDetailMapper.toEntity(moved, FIXTURE_ACCOUNT_ID);

            assertThat(stored.getId().getAuthDate())
                    .as("both records carry the same packed date key")
                    .isEqualTo(23_180)
                    .isEqualTo(patched.getId().getAuthDate());

            assertThat(PendingAuthDetailMapper.authTimestampText(patched))
                    .as("the composition follows the originating characters")
                    .isEqualTo("23-12-31 14.30.25123000");
            assertThat(PendingAuthDetailMapper.authTimestamp(stored))
                    .isEqualTo(LocalDateTime.of(2023, 6, 29, 14, 30, 25, 123_000_000));
            assertThat(PendingAuthDetailMapper.authTimestamp(patched))
                    .as("the value rendering moves with the characters exactly as the text one does")
                    .isEqualTo(LocalDateTime.of(2023, 12, 31, 14, 30, 25, 123_000_000));
        }

        /**
         * Confirms the separator at the date-time boundary is a space and the clock separators are stops.
         *
         * <p>Assumptions: these are the two choices a guess inverts, and both are read off the reference
         * group's own filler literals rather than from the shape of a timestamp. The boundary separator
         * is a SPACE and not a hyphen, and the clock separators are FULL STOPS and not colons, because
         * the value's only consumer is a conversion mask written that way. A colon-separated value of
         * the same width would be refused by that mask as a data exception naming neither the field nor
         * the record it came from, so the absence of a colon is asserted directly.</p>
         */
        @Test
        @DisplayName("the boundary separator is a space and the clock separators are full stops")
        void theSeparatorsAreASpaceAndTwoFullStops() {
            String composed = PendingAuthDetailMapper.authTimestampText(canonicalEntity());

            assertThat(composed).hasSize(PendingAuthDetailMapper.AUTH_TIMESTAMP_LENGTH);
            assertThat(composed.charAt(2)).as("the first calendar separator").isEqualTo('-');
            assertThat(composed.charAt(5)).as("the second calendar separator").isEqualTo('-');
            assertThat(composed.charAt(8)).as("the date-time boundary is a space").isEqualTo(' ');
            assertThat(composed.charAt(11)).as("the first clock separator").isEqualTo('.');
            assertThat(composed.charAt(14)).as("the second clock separator").isEqualTo('.');
            assertThat(composed)
                    .as("a colon anywhere would be refused by the reference conversion mask")
                    .doesNotContain(":");
        }

        /**
         * Confirms the trailing three positions are a literal while the milliseconds before them are data.
         *
         * <p>Assumptions: the last three characters are a hardcoded literal in the reference group and
         * not a value. The packed key carries millisecond resolution only, so the three microsecond
         * positions the conversion mask reads can never carry information, and nobody computing a
         * duration between two stored timestamps should read microsecond resolution into them.</p>
         *
         * <p>Assumptions: the claim is asserted across the three same-day records rather than on one,
         * because one of them -- the record decoding to 91500000 -- happens to carry milliseconds of
         * zero, so its data positions and its literal positions are indistinguishable. Asserting only
         * that record would establish nothing; asserting the pair whose milliseconds are 500 and 750
         * against the literal that stays at zeros is what separates the two.</p>
         */
        @Test
        @DisplayName("the trailing three positions are a literal while the milliseconds are data")
        void theTrailingPositionsAreALiteralAndTheMillisecondsAreNot() {
            byte[] sameDay = bytesOf("pautdtl1-order-same-day-times.bin");
            assertThat(sameDay).hasSize(3 * SEGMENT_LENGTH);

            String[] composed = new String[3];
            for (int ordinal = 0; ordinal < 3; ordinal++) {
                PendingAuthDetail detail = PendingAuthDetailMapper.toEntity(
                        recordAt(sameDay, ordinal, SEGMENT_LENGTH), FIXTURE_ACCOUNT_ID);
                composed[ordinal] = PendingAuthDetailMapper.authTimestampText(detail);
                assertThat(composed[ordinal].substring(20, 23))
                        .as("record %d's trailing literal", ordinal + 1)
                        .isEqualTo(PendingAuthDetailMapper.MILLISECOND_MICROSECOND_PAD);
            }

            assertThat(composed[0]).isEqualTo("24-04-04 16.30.45500000");
            assertThat(composed[1]).isEqualTo("24-04-04 09.15.00000000");
            assertThat(composed[2]).isEqualTo("24-04-04 16.30.45750000");
            assertThat(composed[0].substring(17, 20))
                    .as("the millisecond positions carry data and differ between these two records")
                    .isEqualTo("500")
                    .isNotEqualTo(composed[2].substring(17, 20));
        }

        /**
         * Confirms the composed value is left-justified into the wider host variable it is bound to.
         *
         * <p>Assumptions: the host variable is 26 characters and the composed value is 23, so three
         * positions are always padding and the direction decides whether the value parses at all. A
         * left-justified move puts the value at position one with three trailing blanks, which the
         * conversion mask reads; a right-aligned one would put three blanks in front of the year and the
         * mask would refuse it. The composed value carries no leading or trailing blank of its own, so
         * the padding cannot be confused with content in either direction.</p>
         */
        @Test
        @DisplayName("the composed value is left-justified and blank-padded into its host variable")
        void theCompositionIsLeftJustifiedIntoItsHostVariable() {
            String composed = PendingAuthDetailMapper.authTimestampText(canonicalEntity());

            assertThat(composed).doesNotStartWith(" ").doesNotEndWith(" ");
            assertThat(PendingAuthDetailMapper.AUTH_TIMESTAMP_LENGTH + 3)
                    .as("three positions of the host variable are always padding")
                    .isEqualTo(TIMESTAMP_HOST_VARIABLE_LENGTH);

            String leftJustified = String.format("%-" + TIMESTAMP_HOST_VARIABLE_LENGTH + "s", composed);
            assertThat(leftJustified).hasSize(TIMESTAMP_HOST_VARIABLE_LENGTH)
                    .startsWith(composed)
                    .endsWith("   ");

            // WHY : Assumptions: the right-aligned alternative is asserted to be DIFFERENT rather than
            //       merely described, because both paddings produce a 26-character string and a length
            //       assertion alone cannot tell them apart. Only the position of the year does.
            String rightAligned = String.format("%" + TIMESTAMP_HOST_VARIABLE_LENGTH + "s", composed);
            assertThat(rightAligned).isNotEqualTo(leftJustified).startsWith("   ");
        }

        /**
         * Confirms an originating date of the wrong width is refused rather than sliced.
         *
         * <p>Assumptions: both calls raise {@link IllegalArgumentException}. The originating date is
         * acquirer-supplied and is stored as characters precisely so an unparseable one can be held, so a
         * value of the wrong width is reachable rather than hypothetical; a value one character longer
         * would slice without complaint and compose a well-formed timestamp out of the wrong characters.
         * The width is therefore checked before the first slice rather than left to the slice to
         * discover.</p>
         */
        @Test
        @DisplayName("an originating date of the wrong width is refused")
        void anOriginatingDateOfTheWrongWidthIsRefused() {
            assertThatThrownBy(
                    () -> PendingAuthDetailMapper.composeAuthTimestampText("23062", 143_025_123))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("originating date must be exactly");
            assertThatThrownBy(
                    () -> PendingAuthDetailMapper.composeAuthTimestampText(null, 143_025_123))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("originating date must be exactly");
        }
    }

    /**
     * The two zero-filled renderings that restore leading zeros the codec discarded.
     *
     * <p>Assumptions: the image this group reads is {@code pautdtl1-canonical.bin}, one 200-byte record
     * whose two-digit entry mode at offset 95 holds the characters {@code 05} and whose six-digit
     * processing code at offset 68 holds {@code 000000}. Both are unsigned DISPLAY numerics rather than
     * packed fields, so their stored form is digit characters zero-filled on the left.</p>
     *
     * <p>Assumptions: the shared codec decodes an unsigned display field to an integral value, which
     * necessarily discards the leading zeros, and the response components these values reach are
     * constrained to digits. A rendering that published the integral value directly would publish
     * {@code 5} where the segment holds {@code 05}, and both satisfy a digits-only constraint -- so the
     * defect would pass validation and reach a consumer that reads the field positionally. Neither of
     * these renderings had a caller in any test before this group.</p>
     */
    @Nested
    @DisplayName("the zero-filled display renderings")
    class ZeroFilledRenderings {

        /**
         * Confirms both renderings restore the leading zeros the integral decode discarded.
         *
         * <p>Assumptions: the decoded integral values are read out of the field map rather than written
         * as literals, so the case establishes the ROUND from stored characters through the codec's
         * integral form and back to characters. Comparing the rendering against the stored characters is
         * what makes it a round rather than a restatement: the stored {@code 05} and the rendered
         * {@code 05} are the two ends, and the integral 5 in the middle is where the zero was lost.</p>
         *
         * <p>Assumptions: the two widths are separate constants taken from their own pictures rather than
         * one shared width, which is why both are asserted. A shared rendering parameterised by width
         * would take that width from its caller, and a caller is exactly where a wrong width would then
         * come from.</p>
         */
        @Test
        @DisplayName("both renderings restore the leading zeros the integral decode discarded")
        void bothRenderingsRestoreTheDiscardedLeadingZeros() {
            byte[] canonical = bytesOf("pautdtl1-canonical.bin");
            assertThat(canonical).hasSize(SEGMENT_LENGTH);

            Map<String, Object> fields = PendingAuthDetailMapper.toSegmentFields(canonical);
            Long decodedEntryMode = (Long) fields.get("PA-POS-ENTRY-MODE");
            Long decodedProcessingCode = (Long) fields.get("PA-PROCESSING-CODE");

            assertThat(decodedEntryMode)
                    .as("the integral decode of the stored 05 has lost its leading zero")
                    .isEqualTo(5L);
            assertThat(decodedProcessingCode).isEqualTo(0L);

            assertThat(PendingAuthDetailMapper.renderPosEntryMode(
                    Short.valueOf(decodedEntryMode.shortValue())))
                    .isEqualTo("05")
                    .hasSize(PendingAuthDetailMapper.POS_ENTRY_MODE_DIGITS);
            assertThat(PendingAuthDetailMapper.renderProcessingCode(decodedProcessingCode))
                    .isEqualTo("000000")
                    .hasSize(PendingAuthDetailMapper.PROCESSING_CODE_DIGITS);

            PendingAuthDetail stored =
                    PendingAuthDetailMapper.toEntity(canonical, FIXTURE_ACCOUNT_ID);
            assertThat(stored.getPosEntryMode()).isEqualTo(Short.valueOf((short) 5));
            assertThat(stored.getProcessingCode())
                    .as("the entity carries the restored characters and not the integral value")
                    .isEqualTo("000000");
        }

        /**
         * Confirms both renderings answer an absent value with no value rather than with zeros.
         *
         * <p>Assumptions: absent and zero are different states on the way OUT even though the encode
         * direction collapses them, and the asymmetry is deliberate. A segment has no representation for
         * absent, so an encode substitutes zeros; a response component does have one, so publishing
         * {@code 00} for a column an extract left unset would assert an entry mode nobody recorded.</p>
         */
        @Test
        @DisplayName("both renderings answer an absent value with no value")
        void bothRenderingsAnswerAnAbsentValueWithNoValue() {
            assertThat(PendingAuthDetailMapper.renderPosEntryMode(null)).isNull();
            assertThat(PendingAuthDetailMapper.renderProcessingCode(null)).isNull();
        }

        /**
         * Confirms a value wider than its own picture, or negative, is refused by both renderings.
         *
         * <p>Assumptions: all four calls raise {@link IllegalArgumentException}. Both pictures are
         * UNSIGNED, so a negative value has no stored form at all, and a value past the picture's digit
         * count would render one character too many and shift every positional read of the response by
         * one. The refusal names the digit positions rather than the value's magnitude, because the
         * constraint is the picture and not a business range.</p>
         */
        @Test
        @DisplayName("a value wider than its picture, or negative, is refused")
        void aValueOutsideItsPictureIsRefused() {
            assertThatThrownBy(
                    () -> PendingAuthDetailMapper.renderPosEntryMode(Short.valueOf((short) 100)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("entry mode");
            assertThatThrownBy(
                    () -> PendingAuthDetailMapper.renderPosEntryMode(Short.valueOf((short) -1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("entry mode");
            assertThatThrownBy(
                    () -> PendingAuthDetailMapper.renderProcessingCode(Long.valueOf(1_000_000L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("processing code");
            assertThatThrownBy(
                    () -> PendingAuthDetailMapper.renderProcessingCode(Long.valueOf(-1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("processing code");
        }
    }

    /**
     * The fraud position's two display shapes and the month-first parse of its report date.
     *
     * <p>Assumptions: the images this group reads are {@code pautdtl1-auth-fraud-domain.bin}, three
     * 200-byte records sharing the packed date complement {@code 75 87 9C} and the originating date
     * {@code 240429}, whose fraud positions at offset 174 are {@code F}, {@code R} and a SPACE and whose
     * report dates at offset 175 are {@code 04/29/24}, {@code 04/29/24} and eight blanks; and
     * {@code pautdtl1-auth-fraud-invalid.bin}, one 200-byte record whose fraud position is {@code Y} and
     * whose report date is the perfectly valid {@code 04/30/24}. The second record's date being valid is
     * deliberate: it makes the fraud position the only thing at fault, so a refusal or a rendering
     * change can be attributed to it.</p>
     *
     * <p>Assumptions: the declaring copybook gives condition names to exactly two values and gives none
     * to a blank, yet a blank is the ordinary unmarked state -- the reference insert path writes a space
     * into the position on every insert. The reference display branches on those two condition names and
     * writes a bare hyphen over the whole field otherwise, so the unmarked shape is not an error shape.
     * Neither the display composition nor the report-date parse had a caller in any test before this
     * group.</p>
     */
    @Nested
    @DisplayName("the fraud position rendering")
    class FraudFieldRendering {

        /**
         * Confirms a marked position renders ten characters and an unmarked one renders a bare hyphen.
         *
         * <p>Assumptions: both widths are asserted, not only the values. The marked shape is the flag,
         * a separator and the eight-character report date, which fills the reference display field
         * exactly; the unmarked shape is a single hyphen, deliberately shorter than the display field
         * whose remaining nine bytes the terminal rendered as blanks. Publishing nine blanks that mean
         * nothing was the alternative, and the trade the mapper makes is recorded at its own
         * declaration.</p>
         */
        @Test
        @DisplayName("a marked position renders ten characters and an unmarked one a bare hyphen")
        void aMarkedPositionRendersTenCharactersAndAnUnmarkedOneAHyphen() {
            byte[] domain = bytesOf("pautdtl1-auth-fraud-domain.bin");
            assertThat(domain).hasSize(3 * SEGMENT_LENGTH);

            Map<String, Object> reported =
                    PendingAuthDetailMapper.toSegmentFields(recordAt(domain, 0, SEGMENT_LENGTH));
            Map<String, Object> removed =
                    PendingAuthDetailMapper.toSegmentFields(recordAt(domain, 1, SEGMENT_LENGTH));
            Map<String, Object> unmarked =
                    PendingAuthDetailMapper.toSegmentFields(recordAt(domain, 2, SEGMENT_LENGTH));

            assertThat(PendingAuthDetailMapper.renderFraudMark(
                    (String) reported.get("PA-AUTH-FRAUD"),
                    (String) reported.get("PA-FRAUD-RPT-DATE")))
                    .isEqualTo("F-04/29/24")
                    .hasSize(10);
            assertThat(PendingAuthDetailMapper.renderFraudMark(
                    (String) removed.get("PA-AUTH-FRAUD"),
                    (String) removed.get("PA-FRAUD-RPT-DATE")))
                    .isEqualTo("R-04/29/24")
                    .hasSize(10);
            assertThat(PendingAuthDetailMapper.renderFraudMark(
                    (String) unmarked.get("PA-AUTH-FRAUD"),
                    (String) unmarked.get("PA-FRAUD-RPT-DATE")))
                    .isEqualTo(PendingAuthDetailMapper.FRAUD_MARK_ABSENT)
                    .hasSize(1);
        }

        /**
         * Confirms an out-of-domain fraud position is refused rather than normalised into unmarked.
         *
         * <p>Refactoring Rationale: this case asserted the OPPOSITE -- that {@code toEntity} left the
         * entity unmarked and carried on. That behaviour lost audit state without reporting anything: the
         * column admits the two marking characters, a blank and null, so an entity presenting null loaded
         * clean, the check constraint never saw the offending byte, and an authorization the extract said
         * had been marked arrived in the target unmarked. The conversion now REFUSES the record, and this
         * case asserts the refusal names the character and the domain so an operator can find the records
         * at fault in the extract.
         *
         * <p>Assumptions: the record's report date is valid, so the unmarked rendering here cannot be
         * explained by a missing date. The rendering branches on the position alone.</p>
         */
        @Test
        @DisplayName("an out-of-domain fraud position is refused rather than normalised into unmarked")
        void anOutOfDomainFraudPositionIsRefusedRatherThanNormalised() {
            byte[] invalid = bytesOf("pautdtl1-auth-fraud-invalid.bin");
            assertThat(invalid).hasSize(SEGMENT_LENGTH);

            Map<String, Object> fields = PendingAuthDetailMapper.toSegmentFields(invalid);
            assertThat(fields.get("PA-AUTH-FRAUD"))
                    .as("the projection carries the stored character unrepaired")
                    .isEqualTo("Y");
            assertThat(fields.get("PA-FRAUD-RPT-DATE"))
                    .as("the report date is valid, so only the position is at fault")
                    .isEqualTo("04/30/24");

            assertThat(PendingAuthDetailMapper.renderFraudMark("Y", "04/30/24"))
                    .as("the rendering branches on the position and answers with the unmarked shape")
                    .isEqualTo(PendingAuthDetailMapper.FRAUD_MARK_ABSENT);

            assertThatThrownBy(() -> PendingAuthDetailMapper.toEntity(invalid, FIXTURE_ACCOUNT_ID))
                    .as("the conversion refuses the record instead of discarding the character")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'Y'")
                    .hasMessageContaining("outside the domain");
        }

        /**
         * Confirms a marked position with no report date renders the flag and its separator alone.
         *
         * <p>Assumptions: this branch is unreachable from any committed image and is still reachable in
         * production, which is why it is exercised from values rather than from a fixture. The reference
         * branch tests the position ALONE and would compose the field with blanks in the date positions,
         * so a marked position with a blank date is not refused; the rendering carries the two
         * significant characters and drops the eight blanks that carry nothing. A blank date and an
         * absent one are treated alike because the two arise from different sources -- an insert path
         * writes spaces, an extract leaves a column unset -- and mean the same thing here.</p>
         */
        @Test
        @DisplayName("a marked position with no report date renders the flag and its separator alone")
        void aMarkedPositionWithNoReportDateRendersTwoCharacters() {
            assertThat(PendingAuthDetailMapper.renderFraudMark(PendingAuthDetail.FRAUD_REPORTED,
                    "        ")).isEqualTo("F-").hasSize(2);
            assertThat(PendingAuthDetailMapper.renderFraudMark(PendingAuthDetail.FRAUD_REMOVED, null))
                    .isEqualTo("R-").hasSize(2);
        }

        /**
         * Confirms a marked position whose report date is the wrong width is refused.
         *
         * <p>Assumptions: the call raises {@link IllegalArgumentException}. A date one character longer
         * would render an eleven-character field into a display position of ten, and a date shorter than
         * declared would render a field the terminal padded silently, so neither can be allowed to
         * compose. The width is the copybook's and not the parse's, which is why this refusal is
         * separate from the parse refusals below.</p>
         */
        @Test
        @DisplayName("a marked position whose report date is the wrong width is refused")
        void aMarkedPositionWithAMisWidthedReportDateIsRefused() {
            assertThatThrownBy(() -> PendingAuthDetailMapper.renderFraudMark(
                    PendingAuthDetail.FRAUD_REPORTED, "04/29/2024"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fraud report date");
        }

        /**
         * Confirms the report date parses month-first on both sides of the century pivot.
         *
         * <p>Assumptions: the ordering is MONTH first and is the opposite of the ISO ordering the rest of
         * the migration uses, so it is asserted rather than inferred. The two values are read from the
         * committed images -- {@code 04/29/24} from the fraud-domain image and {@code 12/31/99} from the
         * second record of the two-record 400-byte {@code pautdtl1-date-formats.bin} -- and they land on
         * opposite sides of the pivot, so a pivot applied in the wrong direction would put both in one
         * century while both results stayed valid calendar dates.</p>
         *
         * <p>Assumptions: a blank and an absent value both yield no date, because the reference insert
         * path writes eight spaces into this field on every insert and eight spaces cannot be parsed as a
         * date. The mapping to no date is therefore a requirement rather than a convenience: the
         * relational fraud row's report-date column is a nullable date for exactly this reason, while the
         * detail row keeps the eight characters themselves.</p>
         */
        @Test
        @DisplayName("the report date parses month-first on both sides of the century pivot")
        void theReportDateParsesMonthFirstAcrossThePivot() {
            byte[] domain = bytesOf("pautdtl1-auth-fraud-domain.bin");
            byte[] pair = bytesOf("pautdtl1-date-formats.bin");
            assertThat(pair).hasSize(2 * SEGMENT_LENGTH);

            String recent = (String) PendingAuthDetailMapper
                    .toSegmentFields(recordAt(domain, 0, SEGMENT_LENGTH)).get("PA-FRAUD-RPT-DATE");
            String old = (String) PendingAuthDetailMapper
                    .toSegmentFields(recordAt(pair, 1, SEGMENT_LENGTH)).get("PA-FRAUD-RPT-DATE");

            assertThat(recent).isEqualTo("04/29/24");
            assertThat(old).isEqualTo("12/31/99");

            assertThat(PendingAuthDetailMapper.parseFraudReportDate(recent))
                    .as("the leading pair is the month, so this is the twenty-ninth of April")
                    .isEqualTo(LocalDate.of(2024, 4, 29));
            assertThat(PendingAuthDetailMapper.parseFraudReportDate(old))
                    .as("a year at or above the pivot resolves into the twentieth century")
                    .isEqualTo(LocalDate.of(1999, 12, 31));

            assertThat(PendingAuthDetailMapper.parseFraudReportDate("        ")).isNull();
            assertThat(PendingAuthDetailMapper.parseFraudReportDate(null)).isNull();
        }

        /**
         * Confirms the report date refuses misplaced separators, non-digits and an impossible day.
         *
         * <p>Assumptions: all four calls raise {@link IllegalArgumentException}, and the four classes are
         * exercised separately because they fail at different points and a single refusal would not
         * establish that the later checks are reachable. A hyphen-separated value has the right width and
         * the right digits; a non-digit month has the right width and the right separators; the
         * thirtieth of February has all three and names no day; and a seven-character value is refused on
         * width before any of the rest is examined.</p>
         */
        @Test
        @DisplayName("the report date refuses misplaced separators, non-digits and an impossible day")
        void theReportDateRefusesItsFourFaultClasses() {
            assertThatThrownBy(() -> PendingAuthDetailMapper.parseFraudReportDate("04-29-24"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("separators");
            assertThatThrownBy(() -> PendingAuthDetailMapper.parseFraudReportDate("aa/29/24"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("digits");
            assertThatThrownBy(() -> PendingAuthDetailMapper.parseFraudReportDate("02/30/24"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("calendar date");
            assertThatThrownBy(() -> PendingAuthDetailMapper.parseFraudReportDate("4/29/24"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fraud report date");
        }
    }

    /**
     * The one disclosure narrowing this bounded context adds, asserted as an absence.
     *
     * <p>Assumptions: the image this group reads is {@code pautdtl1-canonical.bin}, one 200-byte record
     * whose sixteen-character account number is the value {@link #UNMASKED_CARD_NUMBER} names. Every
     * detail image in this module presents that same card, which is what lets a sweep look for one value
     * rather than for a per-image one. The value itself is referred to by constant rather than written
     * out here, so this file states an account-number-shaped literal in exactly one place.</p>
     *
     * <p>Assumptions: the masking is a target control with no reference antecedent, and it is a
     * registered divergence rather than parity. The reference resource definitions enable tracing and
     * dumping without classifying the payload as confidential, the reference detail screen presents all
     * sixteen digits, and no resource-level or command-level check stands in front of either. Nothing of
     * the reference is removed; a control is added on the target side. It is asserted as the ABSENCE of
     * the unmasked value rather than as the presence of the masked one, because a component that carried
     * both would satisfy a presence assertion.</p>
     */
    @Nested
    @DisplayName("the account-number narrowing")
    class DisclosureNarrowing {

        /**
         * Confirms no component of the projection, and no byte of its JSON, carries the unmasked number.
         *
         * <p>Assumptions: the sweep is reflective over every declared component rather than written
         * against a named list, so a component added later is swept without this case being revisited.
         * That is the whole point: a sweep over a fixed list would keep passing after a twenty-eighth
         * component started carrying the value it exists to look for, and the failure would be a
         * disclosure rather than a wrong value.</p>
         *
         * <p>Assumptions: the serialised text is swept as well as the components, because a component
         * whose own rendering hides the value could still serialise it -- a nested type, or a custom
         * serialiser, would do exactly that. The two sweeps together are what make the claim about the
         * payload and not only about the object.</p>
         */
        @Test
        @DisplayName("no component and no serialised byte carries the unmasked account number")
        void noComponentOrSerialisedByteCarriesTheUnmaskedNumber() {
            PendingAuthDetail stored = canonicalEntity();
            assertThat(stored.getCardNum())
                    .as("the entity does hold the unmasked value, so the sweep has something to find")
                    .isEqualTo(UNMASKED_CARD_NUMBER);

            PendingAuthDetailResponse response =
                    PendingAuthDetailMapper.toResponse(stored, null, screenContext());

            assertThat(response.cardNumber())
                    .isEqualTo(PendingAuthDetailMapper.maskedCardNumber(UNMASKED_CARD_NUMBER))
                    .isNotEqualTo(UNMASKED_CARD_NUMBER)
                    .endsWith(UNMASKED_CARD_NUMBER.substring(UNMASKED_CARD_NUMBER.length() - 4));

            for (String component : componentTextOf(response)) {
                assertThat(component)
                        .as("a projection component must not carry the unmasked account number")
                        .doesNotContain(UNMASKED_CARD_NUMBER);
            }
            assertThat(MAPPER.writeValueAsString(response))
                    .as("the serialised payload must not carry it either")
                    .doesNotContain(UNMASKED_CARD_NUMBER);
        }

        /**
         * Confirms the mask answers an absent account number with no value rather than with a mask.
         *
         * <p>Assumptions: a null is distinguished from a value because the two mean different things to a
         * reader of the response -- a masked run of characters asserts that a card was presented, and a
         * column an extract left unset asserts nothing. Masking a null would manufacture the first claim
         * out of the second.</p>
         */
        @Test
        @DisplayName("the mask answers an absent account number with no value")
        void theMaskAnswersAnAbsentNumberWithNoValue() {
            assertThat(PendingAuthDetailMapper.maskedCardNumber(null)).isNull();
        }
    }

    /**
     * The shape of the projection, and the trailing padding that reaches none of it.
     *
     * <p>Assumptions: the image this group reads is {@code pautdtl1-canonical.bin}, one 200-byte record
     * whose seventeen bytes of padding from offset 183 are blanks. The declaring copybook gives the
     * segment twenty-eight field descriptors, the last of which is that padding, so twenty-seven carry
     * meaning; the record's closure at 200 is the shared kernel's subject and is not re-derived here.</p>
     *
     * <p>Assumptions: the projection also declares twenty-seven components, and the equality of the two
     * counts is a COINCIDENCE rather than a correspondence. The projection omits six segment fields the
     * reference screen never displayed and adds six components of screen chrome no segment holds, so
     * mapping the two sets onto each other one for one would put the wrong value in most positions.</p>
     *
     * <p>Refactoring Rationale: the two counts are therefore carried by two INDEPENDENT constants, and
     * the cases below use each only for its own side -- the response count for the reflection over the
     * record, and the layout-derived segment count for the field map. They previously shared one
     * constant, which made the coincidence load-bearing: a segment that gained a descriptor would have
     * failed an assertion named after the response, pointing a reader at the shape that had not
     * changed.</p>
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
     * parameter, return or exception section.</p>
     */
    @Nested
    @DisplayName("the projection shape and the dropped padding")
    class ProjectionShape {

        /**
         * Confirms the projection declares twenty-seven components and none of them is padding-derived.
         *
         * <p>Assumptions: the count is asserted so that a twenty-eighth component added without a
         * rendering rule fails here rather than reaching a client as a null. The absence of a
         * padding-derived component is asserted by name because padding is the one field a positional
         * reader is most likely to carry through -- it decodes cleanly, it is the widest run of blanks in
         * the record, and nothing about it looks wrong.</p>
         */
        @Test
        @DisplayName("the projection declares twenty-seven components and none is padding-derived")
        void theProjectionDeclaresTwentySevenComponentsAndNoPadding() {
            RecordComponent[] components = PendingAuthDetailResponse.class.getRecordComponents();

            assertThat(components).hasSize(RESPONSE_COMPONENT_COUNT);
            for (RecordComponent component : components) {
                assertThat(component.getName().toLowerCase(java.util.Locale.ROOT))
                        .as("no component may be derived from the record's trailing padding")
                        .doesNotContain("filler")
                        .doesNotContain("padding");
            }

            Map<String, Object> fields =
                    PendingAuthDetailMapper.toSegmentFields(bytesOf("pautdtl1-canonical.bin"));
            assertThat(fields)
                    .as("the blank padding descriptor is dropped from the field projection")
                    .doesNotContainKey(PADDING_FIELD_NAME)
                    .hasSize(SEGMENT_MEANINGFUL_FIELD_COUNT);
        }

        /**
         * Confirms a record whose padding is not blank maps to the same entity and the same projection.
         *
         * <p>Assumptions: the padding difference is visible at the CODEC and invisible at the mapper, and
         * both halves are asserted because either alone would be satisfiable the wrong way. The codec
         * drops the descriptor only when it is blank, so the patched copy's field map carries one more
         * entry than the canonical one -- which proves the copy really does differ. The entity and the
         * projection are then asserted identical, which proves the difference reaches neither.</p>
         *
         * <p>Assumptions: the copy is made by overwriting the seventeen padding bytes with a non-blank
         * character in a clone of the committed image. The image on disk carries blanks and is never
         * written to; nobody may make a byte image tidy in either direction.</p>
         */
        @Test
        @DisplayName("a record whose padding is not blank maps to the same entity and projection")
        void aNonBlankPaddingReachesNeitherTheEntityNorTheProjection() {
            byte[] canonical = bytesOf("pautdtl1-canonical.bin");
            byte[] scribbled = patchedText(canonical, PADDING_OFFSET,
                    "X".repeat(SEGMENT_LENGTH - PADDING_OFFSET));

            assertThat(scribbled).hasSize(SEGMENT_LENGTH).isNotEqualTo(canonical);
            assertThat(PendingAuthDetailMapper.toSegmentFields(scribbled))
                    .as("a non-blank padding descriptor is retained, so the two records do differ")
                    .containsEntry(PADDING_FIELD_NAME, "X".repeat(SEGMENT_LENGTH - PADDING_OFFSET))
                    .hasSize(SEGMENT_MEANINGFUL_FIELD_COUNT + 1);

            PendingAuthDetail fromCanonical =
                    PendingAuthDetailMapper.toEntity(canonical, FIXTURE_ACCOUNT_ID);
            PendingAuthDetail fromScribbled =
                    PendingAuthDetailMapper.toEntity(scribbled, FIXTURE_ACCOUNT_ID);

            assertThat(fromScribbled.getId()).isEqualTo(fromCanonical.getId());
            assertThat(fromScribbled.getTransactionId()).isEqualTo(fromCanonical.getTransactionId());
            assertThat(fromScribbled.getMerchantName()).isEqualTo(fromCanonical.getMerchantName());
            assertThat(fromScribbled.getApprovedAmount()).isEqualTo(fromCanonical.getApprovedAmount());
            assertThat(fromScribbled.getMatchStatus()).isEqualTo(fromCanonical.getMatchStatus());

            assertThat(componentTextOf(PendingAuthDetailMapper.toResponse(fromScribbled, null,
                    screenContext())))
                    .as("every projected component is identical across the padding difference")
                    .isEqualTo(componentTextOf(PendingAuthDetailMapper.toResponse(fromCanonical, null,
                            screenContext())));
        }

        /**
         * Confirms the projection publishes the derived approval character for every stored outcome.
         *
         * <p>Assumptions: the image this case reads is {@code pautdtl1-newyear-pair.bin}, two 200-byte
         * records whose two-character response codes at offset 62 are {@code 00} and {@code 05}. Their
         * packed date complements are {@code 76 63 4C} and {@code 75 99 8C}, decoding to the Julian dates
         * 23365 and 24001, and their originating dates are {@code 231231} and {@code 240101} -- the last
         * day of one year and the first of the next. A third outcome is produced by copying the approved
         * record and overwriting the response code with {@code 01}, because a code that is numerically
         * small but not the approved one is the case a comparison written as a numeric test rather than a
         * character test would get wrong, and no committed image carries one.</p>
         *
         * <p>Assumptions: two further properties of that image are noted here and deliberately NOT
         * asserted, so that a reader does not mistake their absence for an oversight. First, because the
         * key is stored complemented, the two records' RAW bytes order the opposite way from their decoded
         * values, which is why the target's query layer sorts descending on the decoded columns; that
         * inversion is asserted by {@code fixtures.PendingAuthDetailNewYearFixtureTest} and restating it
         * here would leave two suites asserting one contract. Second, the same pair exposes a baseline
         * expiry-arithmetic defect -- subtracting the two Julian ordinals as plain integers yields 636 for
         * two days one day apart, which a five-day threshold then reads as long expired -- and that
         * behaviour belongs to the purge job and therefore to the sibling {@code .service} package. It is
         * out of scope for a mapper test in either direction.</p>
         *
         * <p>Assumptions: the derivation is a single unconditional test against the approved code with
         * every other code declining, and the RULE is owned by the sibling view mapper's own test. What
         * this case establishes is what the DETAIL projection publishes, which nothing else asserts: only
         * the derived character reaches it, so a projection carrying the two-character code beside it
         * would publish something the screen it mirrors could not show.</p>
         *
         * <p>Trade-offs: that last claim is asserted as the published character's WIDTH rather than by
         * sweeping every component for the code's two characters, and the weaker instrument is the
         * correct one here. Every detail image in this module stores the entry mode {@code 05}, which is
         * the same two characters as this record's response code, so a value sweep would report the entry
         * mode as a leak of the response code and fail on a projection that is entirely correct. A width
         * of one excludes a two-character code without depending on which two characters it is.</p>
         */
        @Test
        @DisplayName("the projection publishes the derived approval character for every outcome")
        void theProjectionPublishesTheDerivedApprovalCharacter() {
            byte[] pair = bytesOf("pautdtl1-newyear-pair.bin");
            assertThat(pair).hasSize(2 * SEGMENT_LENGTH);

            byte[] approvedImage = recordAt(pair, 0, SEGMENT_LENGTH);
            PendingAuthDetail approved =
                    PendingAuthDetailMapper.toEntity(approvedImage, FIXTURE_ACCOUNT_ID);
            PendingAuthDetail declined = PendingAuthDetailMapper.toEntity(
                    recordAt(pair, 1, SEGMENT_LENGTH), FIXTURE_ACCOUNT_ID);
            PendingAuthDetail nearlyApproved = PendingAuthDetailMapper.toEntity(
                    patchedText(approvedImage, RESPONSE_CODE_OFFSET, "01"), FIXTURE_ACCOUNT_ID);

            assertThat(approved.getAuthRespCode()).isEqualTo("00");
            assertThat(declined.getAuthRespCode()).isEqualTo("05");
            assertThat(nearlyApproved.getAuthRespCode()).isEqualTo("01");

            assertThat(PendingAuthDetailMapper.toResponse(approved, null, screenContext())
                    .authResponse()).isEqualTo("A");
            assertThat(PendingAuthDetailMapper.toResponse(declined, null, screenContext())
                    .authResponse()).isEqualTo("D");
            assertThat(PendingAuthDetailMapper.toResponse(nearlyApproved, null, screenContext())
                    .authResponse())
                    .as("a code adjacent to the approved one still declines")
                    .isEqualTo("D");


            assertThat(PendingAuthDetailMapper.toResponse(declined, null, screenContext())
                    .authResponse())
                    .as("only the derived character is published, so the code cannot be it")
                    .hasSize(1);
        }

        /**
         * Confirms every admitted match status reaches the projection unchanged.
         *
         * <p>Assumptions: the image this case reads is {@code pautdtl1-match-status-domain.bin}, 800 bytes
         * of four 200-byte records sharing the packed date complement {@code 75 88 9C} and the originating
         * date {@code 240419}, whose match statuses at offset 173 are {@code P}, {@code D}, {@code E} and
         * {@code M} and whose packed time complements decode to hourly values from ten in the morning to
         * one in the afternoon. All four are admitted values and all four are reachable from real data --
         * the purge writes the third and the posting match writes the fourth -- so an implementation that
         * admitted only the two an insert originates would fail an extract load of ordinary rows.</p>
         *
         * <p>Assumptions: the value is asserted at the PROJECTION and not at the entity, which is what
         * makes this case distinct from the origination and refusal cases that already cover this image
         * and its out-of-domain sibling elsewhere in the module. The projection's own component is the one
         * nothing else reads, and a mapper that normalised a later-transition status into the pending one
         * on the way out would show a matched authorization as still pending.</p>
         */
        @Test
        @DisplayName("every admitted match status reaches the projection unchanged")
        void everyAdmittedMatchStatusReachesTheProjectionUnchanged() {
            byte[] domain = bytesOf("pautdtl1-match-status-domain.bin");
            assertThat(domain).hasSize(4 * SEGMENT_LENGTH);

            String[] expected = {PendingAuthDetail.MATCH_STATUS_PENDING,
                    PendingAuthDetail.MATCH_STATUS_DECLINED,
                    PendingAuthDetail.MATCH_STATUS_PENDING_EXPIRED,
                    PendingAuthDetail.MATCH_STATUS_MATCHED_WITH_TRAN};

            for (int ordinal = 0; ordinal < expected.length; ordinal++) {
                PendingAuthDetail loaded = PendingAuthDetailMapper.toEntity(
                        recordAt(domain, ordinal, SEGMENT_LENGTH), FIXTURE_ACCOUNT_ID);

                assertThat(PendingAuthDetailMapper.toResponse(loaded, null, screenContext())
                        .matchStatus())
                        .as("record %d's status reaches the projection as stored", ordinal + 1)
                        .isEqualTo(expected[ordinal]);
            }
        }
    }

    /**
     * The money contract at this projection's boundary, which no import graph can see.
     *
     * <p>Assumptions: the images this group reads are {@code pautdtl1-canonical.bin}, one 200-byte record
     * whose two seven-byte packed amounts at offsets 74 and 81 both hold
     * {@code 00 00 00 01 23 45 6C}; {@code pautdtl1-amount-ten-integer-digits.bin}, one 200-byte record
     * whose two amounts both hold {@code 09 99 99 99 99 99 9C}, the widest value ten integer digits and
     * two decimal places admit; and {@code unload-prefixed-detail-206.bin}, whose FOURTH 206-byte record
     * is the declined authorization that requests 125.00 and approves nothing.</p>
     *
     * <p>Alternatives Considered: asserting the declaration-level prohibition on binary floating-point
     * members here as well. It is not asserted, because the architecture rule carrying that prohibition
     * selects classes under the analysed {@code com.carddemo} root and so already reaches this package --
     * the money package identifier in that rule is its emptiness anchor and not its scope. Repeating the
     * scan here would leave two suites asserting one contract with no way to tell which is the authority.
     * What belongs to this group is the behaviour the rule cannot see: a scale, a rounding mode, and the
     * form an amount takes on the wire.</p>
     */
    @Nested
    @DisplayName("the money contract at the projection boundary")
    class MoneyAtTheProjectionBoundary {

        /**
         * Confirms both amounts reach the entity at scale two and agree with the shared money contract.
         *
         * <p>Assumptions: a scale is a property of a VALUE and not of a declared type, so no import graph
         * can see it and nothing else in the module asserts it for these two fields. The two ends of the
         * range are used because a scale derived from the value rather than from the picture would show
         * at one end: the canonical amount has significant hundredths, and the widest amount fills every
         * declared position.</p>
         *
         * <p>Assumptions: the mapper's own two picture constants are asserted to AGREE with the shared
         * money contract rather than being restated as literals. That is what keeps this context's
         * bound at the copybook's ten integer digits and its scale at the kernel's two, so a change to
         * either side fails here instead of producing two silently different bounds.</p>
         */
        @Test
        @DisplayName("both amounts reach the entity at scale two and agree with the shared contract")
        void bothAmountsReachTheEntityAtScaleTwo() {
            assertThat(PendingAuthDetailMapper.MONEY_DECIMAL_DIGITS).isEqualTo(Money.SCALE);
            assertThat(Money.GENERAL_ROUNDING).isEqualTo(RoundingMode.HALF_UP);
            assertThat(PendingAuthDetailMapper.MONEY_INTEGER_DIGITS)
                    .isEqualTo(Money.MAX_PICTURE_INTEGER_DIGITS);

            PendingAuthDetail canonical = canonicalEntity();
            assertThat(canonical.getTransactionAmount()).isEqualByComparingTo("1234.56");
            assertThat(canonical.getTransactionAmount().scale()).isEqualTo(Money.SCALE);
            assertThat(canonical.getApprovedAmount().scale()).isEqualTo(Money.SCALE);

            PendingAuthDetail widest = PendingAuthDetailMapper.toEntity(
                    bytesOf("pautdtl1-amount-ten-integer-digits.bin"), FIXTURE_ACCOUNT_ID);
            assertThat(widest.getApprovedAmount()).isEqualByComparingTo("9999999999.99");
            assertThat(widest.getApprovedAmount().scale()).isEqualTo(Money.SCALE);
            assertThat(widest.getApprovedAmount().toPlainString())
                    .as("the widest admissible value survives with every digit intact")
                    .isEqualTo("9999999999.99");
        }

        /**
         * Confirms the published amount crosses to JSON as a string rather than as a bare number.
         *
         * <p>Assumptions: the assertion is made against the serialised TEXT and not against a parsed
         * value, because a parsed value normalises away the one distinction under test. A bare JSON
         * number is read into a binary floating-point value by most clients, which discards exactness at
         * the one boundary a user actually sees, and the widest admissible amount is the value on which
         * that loss is largest -- so it is the one used here rather than a convenient small amount.</p>
         *
         * <p>Assumptions: this projection is serialised nowhere else in the module, so the absence of this
         * case would mean nothing anywhere held its payload form. The negative half of the assertion --
         * that the unquoted form is absent -- is what makes it fail if a future change emits a
         * number.</p>
         */
        @Test
        @DisplayName("the published amount crosses to JSON as a string, not as a bare number")
        void thePublishedAmountCrossesAsAString() {
            PendingAuthDetail widest = PendingAuthDetailMapper.toEntity(
                    bytesOf("pautdtl1-amount-ten-integer-digits.bin"), FIXTURE_ACCOUNT_ID);

            String json = MAPPER.writeValueAsString(
                    PendingAuthDetailMapper.toResponse(widest, null, screenContext()));

            assertThat(json).contains("\"approvedAmount\":\"9999999999.99\"");
            assertThat(json)
                    .as("a bare number here would be parsed into a binary floating-point value")
                    .doesNotContain("\"approvedAmount\":9999999999.99");
        }

        /**
         * Confirms the projection publishes the approved amount and never the requested one.
         *
         * <p>Assumptions: the two amounts differ on a declined authorization and are equal on an approved
         * one, so the claim can only be established from a declined record. The fourth record of the
         * prefixed unload image is that record: it requests 125.00 and approves nothing, and its response
         * code is the declining one. Publishing the requested amount would show a declined authorization
         * as though it had gone through, which is a wrong answer that looks entirely reasonable.</p>
         *
         * <p>Assumptions: an absent approved amount becomes an explicit zero rather than no value,
         * because the reference display field was an edited numeric that always rendered digits and had
         * no blank state.</p>
         */
        @Test
        @DisplayName("the projection publishes the approved amount and never the requested one")
        void theProjectionPublishesTheApprovedAmount() {
            byte[] prefixed = bytesOf("unload-prefixed-detail-206.bin");
            assertThat(prefixed).hasSize(4 * UNLOAD_RECORD_LENGTH);

            PendingAuthDetail declined = PendingAuthDetailMapper.fromUnloadRecord(
                    recordAt(prefixed, 3, UNLOAD_RECORD_LENGTH));

            assertThat(declined.getAuthRespCode()).isEqualTo("05");
            assertThat(declined.getTransactionAmount()).isEqualByComparingTo("125.00");
            assertThat(declined.getApprovedAmount()).isEqualByComparingTo("0.00");

            PendingAuthDetailResponse response =
                    PendingAuthDetailMapper.toResponse(declined, null, screenContext());

            assertThat(response.approvedAmount()).isEqualTo(Money.ZERO);
            assertThat(response.approvedAmount().amount())
                    .as("the requested amount must not be the one published")
                    .isNotEqualByComparingTo(declined.getTransactionAmount());
        }

        /**
         * Confirms the stored amount has no textual width, so a receiver truncation cannot arise here.
         *
         * <p>Assumptions: this is the storage-side NEIGHBOUR of the wire-side divergence registered as
         * {@code D-AUTH-AMOUNT-TOLERANT-READ} in {@code docs/architecture/cobol-to-service-traceability.md}
         * and asserted by {@code AuthRequestWireFixtureTest} against the second record of the request
         * amount-variants image. On the wire the widths differ: the request copybook declares a
         * fourteen-character money token and the reference consumer receives it into a thirteen-character
         * item, so the fourteenth character does not arrive and a numeric conversion reads one fraction
         * digit as readily as two, which places a declared-width token one decimal position out with
         * nothing raised. The storage side cannot express that condition at all, and establishing why is
         * this case's subject: the stored form has no textual width. Its width is fixed in BYTES by the
         * picture, its leading nibble is a mandatory zero pad that is a structural artefact of an odd
         * digit count rather than data, and a value the picture cannot hold is REFUSED at the encode
         * rather than losing a digit. The wire-side assertion and this one therefore cannot be merged:
         * one is about characters a receiver does not read, the other about a value an encoder
         * refuses.</p>
         *
         * <p>Assumptions: the refusal raises {@link IllegalArgumentException}, which is the type both the
         * shared codec's field exception and its packed exception extend, so the case does not depend on
         * which of the two layers notices first. The over-wide value is reconstituted through the entity
         * rather than patched into an image, because no committed image can carry an amount wider than
         * its own picture.</p>
         */
        @Test
        @DisplayName("the stored amount has no textual width, so a receiver truncation cannot arise")
        void theStoredAmountHasNoTextualWidthToTruncate() {
            byte[] widestImage = bytesOf("pautdtl1-amount-ten-integer-digits.bin");
            byte[] storedSpan = Arrays.copyOfRange(widestImage, APPROVED_AMOUNT_OFFSET,
                    APPROVED_AMOUNT_OFFSET + MONEY_SPAN_WIDTH);

            assertThat(storedSpan[0] & 0xF0)
                    .as("the leading nibble is a mandatory zero pad and not a digit of the value")
                    .isZero();
            assertThat(storedSpan[0] & 0x0F)
                    .as("the low nibble of the same byte is the value's first significant digit")
                    .isEqualTo(9);

            PendingAuthDetail widest =
                    PendingAuthDetailMapper.toEntity(widestImage, FIXTURE_ACCOUNT_ID);
            assertThat(widest.getApprovedAmount()).isEqualByComparingTo("9999999999.99");

            byte[] reEmitted = PendingAuthDetailMapper.toSegment(widest);
            assertThat(Arrays.copyOfRange(reEmitted, APPROVED_AMOUNT_OFFSET,
                    APPROVED_AMOUNT_OFFSET + MONEY_SPAN_WIDTH))
                    .as("the pad nibble is re-emitted as zero rather than filled with a digit")
                    .isEqualTo(storedSpan);

            PendingAuthDetail overWide = canonicalWithAmounts(new BigDecimal("10000000000.00"),
                    new BigDecimal("10000000000.00"));
            assertThatThrownBy(() -> PendingAuthDetailMapper.toSegment(overWide))
                    .as("a value the picture cannot hold is refused, never narrowed")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Confirms the stored amount is a third representation whose sign occupies a nibble.
         *
         * <p>Assumptions: this is the storage-side NEIGHBOUR of divergence D-I, which the parent charter
         * registers as one logical amount having two textual forms -- the wire carrying the
         * zero-SUPPRESSED edited rendering and the database carrying the zero-FILLED one -- and requires
         * both to be asserted because asserting either alone would let the other drift. This case
         * establishes that the segment's own form is a THIRD representation and neither of those two. It
         * zero-FILLS in nibbles, so its leading bytes are zero bytes and never the blank byte a
         * suppressed rendering leaves; and its SIGN is the low nibble of the final byte while the high
         * nibble of that same byte is a significant digit, whereas both textual forms carry the sign as a
         * whole character in a position of its own. No single assertion can validate a sign that is half
         * a byte here and a whole character there, which is why the wire-side and storage-side claims are
         * separate cases in separate classes.</p>
         *
         * <p>Assumptions: the canonical amount 1234.56 is the one used because it has leading positions
         * to fill. The widest amount fills every position with a nine, so on that record a zero-filled
         * and a zero-suppressed rendering would be indistinguishable and the case would prove
         * nothing.</p>
         */
        @Test
        @DisplayName("the stored amount zero-fills in nibbles and carries its sign in a nibble")
        void theStoredAmountCarriesItsSignInANibble() {
            byte[] canonical = bytesOf("pautdtl1-canonical.bin");
            byte[] storedSpan = Arrays.copyOfRange(canonical, APPROVED_AMOUNT_OFFSET,
                    APPROVED_AMOUNT_OFFSET + MONEY_SPAN_WIDTH);

            for (int index = 0; index < 3; index++) {
                assertThat(storedSpan[index])
                        .as("leading position %d is zero-filled, not blank-suppressed", index)
                        .isEqualTo((byte) 0x00)
                        .isNotEqualTo((byte) ' ');
            }

            byte last = storedSpan[storedSpan.length - 1];
            assertThat((last & 0xF0) >> 4)
                    .as("the high nibble of the final byte is the value's last significant digit")
                    .isEqualTo(6);
            assertThat(last & 0x0F)
                    .as("the sign occupies the low nibble of that same byte")
                    .isEqualTo(0x0C);

            PendingAuthDetail canonicalDetail =
                    PendingAuthDetailMapper.toEntity(canonical, FIXTURE_ACCOUNT_ID);
            assertThat(canonicalDetail.getApprovedAmount()).isEqualByComparingTo("1234.56");
            assertThat(Arrays.copyOfRange(PendingAuthDetailMapper.toSegment(canonicalDetail),
                    APPROVED_AMOUNT_OFFSET, APPROVED_AMOUNT_OFFSET + MONEY_SPAN_WIDTH))
                    .as("the encode keeps the zero fill and does not carry the wire's suppression in")
                    .isEqualTo(storedSpan);
        }
    }

    /**
     * The two unload record shapes, and the six-byte misalignment that must never decode quietly.
     *
     * <p>Assumptions: the images this group reads are {@code unload-prefixed-detail-206.bin}, 824 bytes
     * of four 206-byte records each carrying a six-byte packed eleven-digit parent key ahead of a
     * 200-byte segment, interleaved across two accounts in the order A, B, A, B with the transaction
     * identifiers {@code TXN000000000100}, {@code TXN000000000110}, {@code TXN000000000101} and
     * {@code TXN000000000111}; and {@code unload-gsam-detail-200.bin}, 800 bytes of four bare 200-byte
     * segments with no prefix, carrying the same four authorizations GROUPED by account rather than
     * interleaved. Neither image holds a delimiter of any kind, so a record boundary exists only as a
     * multiple of the stride.</p>
     *
     * <p>Assumptions: the two shapes exist because a detail segment names no account. The prefixed shape
     * is what the reference unload writes per child while positioned on its parent, so the prefix is the
     * only thing that attributes a child to a parent; the bare shape carries no such attribution and
     * therefore cannot be inserted without its parent being known from somewhere else. That is the reason
     * every other decode in this class takes the account identifier as an argument.</p>
     *
     * <p>Assumptions: the two strides differ by six, and a slice taken at the wrong one still LOOKS like
     * a record. That is what makes this the most damaging silent failure available here: a six-byte
     * misalignment shifts every field of a 200-byte read without changing its length, so the length check
     * that guards the prefixed path cannot catch it and the content check has to.</p>
     */
    @Nested
    @DisplayName("the two unload record shapes")
    class UnloadShapeFraming {

        /**
         * Confirms each prefix names the parent its own segment hangs under.
         *
         * <p>Assumptions: the four prefixes are asserted as the interleaved sequence rather than as a set,
         * because interleaving is the property that makes the prefix load-bearing. Were the file grouped
         * by account, a reader could attribute a child by position and the prefix would be redundant; with
         * the accounts alternating, a reader that ignored the prefix would attribute every second child to
         * the wrong parent and every record would still be well formed.</p>
         *
         * <p>Assumptions: the extracted segment is compared against the bytes that follow the prefix
         * rather than against a written expectation, so the case establishes WHERE the segment begins
         * instead of restating what it contains. The whole-record decode is then asserted to carry the
         * prefix's identifier into the key, which is what an unload-then-reload cycle depends upon.</p>
         */
        @Test
        @DisplayName("each prefix names the parent its own segment hangs under")
        void eachPrefixNamesTheParentItsSegmentHangsUnder() {
            byte[] prefixed = bytesOf("unload-prefixed-detail-206.bin");
            assertThat(prefixed).hasSize(4 * UNLOAD_RECORD_LENGTH);
            assertThat(PendingAuthDetailMapper.unloadRecordLength()).isEqualTo(UNLOAD_RECORD_LENGTH);

            Long[] expectedAccounts = {FIXTURE_ACCOUNT_ID, SECOND_ACCOUNT_ID, FIXTURE_ACCOUNT_ID,
                    SECOND_ACCOUNT_ID};
            String[] expectedTransactions = {"TXN000000000100", "TXN000000000110",
                    "TXN000000000101", "TXN000000000111"};

            for (int ordinal = 0; ordinal < expectedAccounts.length; ordinal++) {
                byte[] record = recordAt(prefixed, ordinal, UNLOAD_RECORD_LENGTH);

                assertThat(PendingAuthDetailMapper.unloadedAccountId(record))
                        .as("record %d's prefix", ordinal + 1)
                        .isEqualTo(expectedAccounts[ordinal]);
                assertThat(PendingAuthDetailMapper.unloadedSegment(record))
                        .as("record %d's segment begins after the prefix", ordinal + 1)
                        .isEqualTo(Arrays.copyOfRange(record, UNLOAD_PREFIX_WIDTH,
                                UNLOAD_RECORD_LENGTH));

                PendingAuthDetail loaded = PendingAuthDetailMapper.fromUnloadRecord(record);
                assertThat(loaded.getId().getAccountId()).isEqualTo(expectedAccounts[ordinal]);
                assertThat(loaded.getTransactionId()).isEqualTo(expectedTransactions[ordinal]);
            }
        }

        /**
         * Confirms the bare shape carries no owning account and cannot be loaded without one.
         *
         * <p>Assumptions: the refusal raises {@link NullPointerException}, because the account identifier
         * is a required argument rather than an optional one and inventing a value for it would be
         * inventing the row's identity. That is the whole reason two shapes exist: the bare form is
         * complete as a segment and incomplete as a row.</p>
         *
         * <p>Assumptions: the two images hold the same four authorizations in DIFFERENT orders, the
         * prefixed one interleaving the two accounts and the bare one grouping them, so the bare first,
         * second, third and fourth records correspond to the prefixed first, third, second and fourth. The
         * correspondence is asserted position by position rather than as a set, because as a set it would
         * hide the reordering that a positional reader of either file has to know about.</p>
         *
         * <p>Assumptions: three of the four correspondences are byte-identical and the FOURTH is not, and
         * this was verified against the images rather than assumed. Their declined records differ in
         * exactly one byte, the second of the four-character response reason at offset 64: the prefixed
         * image records {@code 4100} and the bare image {@code 4200}, both under the same declining
         * response code. The discrepancy is asserted rather than smoothed over -- an equality assertion
         * across all four would be simply false, and a case that skipped the fourth record would leave
         * the corpus free to drift further apart unnoticed. Every other byte of that record, its key, its
         * amounts and its match status included, is identical.</p>
         */
        @Test
        @DisplayName("the bare shape carries no owning account and cannot be loaded without one")
        void theBareShapeCarriesNoOwningAccount() {
            byte[] bare = bytesOf("unload-gsam-detail-200.bin");
            byte[] prefixed = bytesOf("unload-prefixed-detail-206.bin");
            assertThat(bare).hasSize(4 * SEGMENT_LENGTH);

            byte[] firstBare = recordAt(bare, 0, SEGMENT_LENGTH);
            assertThatThrownBy(() -> PendingAuthDetailMapper.toEntity(firstBare, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("accountId");

            int[] prefixedOrdinalOf = {0, 2, 1};
            for (int ordinal = 0; ordinal < prefixedOrdinalOf.length; ordinal++) {
                byte[] embedded = PendingAuthDetailMapper.unloadedSegment(
                        recordAt(prefixed, prefixedOrdinalOf[ordinal], UNLOAD_RECORD_LENGTH));
                assertThat(recordAt(bare, ordinal, SEGMENT_LENGTH))
                        .as("bare record %d is the prefixed image's record %d", ordinal + 1,
                                prefixedOrdinalOf[ordinal] + 1)
                        .isEqualTo(embedded);
            }

            byte[] bareDeclined = recordAt(bare, 3, SEGMENT_LENGTH);
            byte[] prefixedDeclined = PendingAuthDetailMapper.unloadedSegment(
                    recordAt(prefixed, 3, UNLOAD_RECORD_LENGTH));
            for (int offset = 0; offset < SEGMENT_LENGTH; offset++) {
                if (offset >= RESPONSE_REASON_OFFSET
                        && offset < RESPONSE_REASON_OFFSET + RESPONSE_REASON_WIDTH) {
                    continue;
                }
                assertThat(bareDeclined[offset])
                        .as("byte %d, outside the response reason, is identical in both images", offset)
                        .isEqualTo(prefixedDeclined[offset]);
            }
            assertThat(PendingAuthDetailMapper.toSegmentFields(prefixedDeclined)
                    .get("PA-AUTH-RESP-REASON")).isEqualTo("4100");
            assertThat(PendingAuthDetailMapper.toSegmentFields(bareDeclined)
                    .get("PA-AUTH-RESP-REASON"))
                    .as("the bare image records a different decline reason for the same authorization")
                    .isEqualTo("4200");
        }

        /**
         * Confirms the two shapes are not interchangeable in either direction.
         *
         * <p>Assumptions: every call below raises, and the types differ by direction because the two
         * paths refuse for different reasons. A bare segment offered to the prefixed path raises
         * {@link IllegalArgumentException} naming the 206 bytes it expected -- deliberately not the
         * shared codec's own record-length type, because no 206-byte layout is registered and only the
         * class owning a registered layout may claim a record is the wrong length for it. A prefixed
         * record offered to the segment path raises
         * {@link FixedWidthCodec.RecordLengthException}, which is that type, because the segment layout
         * IS registered.</p>
         *
         * <p>Assumptions: the third refusal is the one that matters most and the one a length check cannot
         * provide. A prefixed record truncated to its first 200 bytes is exactly the length the segment
         * layout declares, so it passes every length test and then reads the packed parent key where the
         * date complement belongs and every subsequent field six bytes early. It is caught by the content
         * of the packed spans rather than by their extent, which is why the assertion here is on the
         * refusal and not on the length.</p>
         */
        @Test
        @DisplayName("the two shapes are not interchangeable in either direction")
        void theTwoShapesAreNotInterchangeable() {
            byte[] bareSegment = recordAt(bytesOf("unload-gsam-detail-200.bin"), 0, SEGMENT_LENGTH);
            byte[] prefixedRecord =
                    recordAt(bytesOf("unload-prefixed-detail-206.bin"), 0, UNLOAD_RECORD_LENGTH);

            assertThatThrownBy(() -> PendingAuthDetailMapper.unloadedAccountId(bareSegment))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(Integer.toString(UNLOAD_RECORD_LENGTH));
            assertThatThrownBy(() -> PendingAuthDetailMapper.unloadedSegment(bareSegment))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(Integer.toString(UNLOAD_RECORD_LENGTH));
            assertThatThrownBy(() -> PendingAuthDetailMapper.fromUnloadRecord(bareSegment))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(Integer.toString(UNLOAD_RECORD_LENGTH));

            assertThatThrownBy(() -> PendingAuthDetailMapper.toSegmentFields(prefixedRecord))
                    .isInstanceOf(FixedWidthCodec.RecordLengthException.class);
            assertThatThrownBy(
                    () -> PendingAuthDetailMapper.toEntity(prefixedRecord, FIXTURE_ACCOUNT_ID))
                    .isInstanceOf(FixedWidthCodec.RecordLengthException.class);

            byte[] misaligned = Arrays.copyOf(prefixedRecord, SEGMENT_LENGTH);
            assertThat(misaligned)
                    .as("the misaligned slice is the length the segment layout declares")
                    .hasSize(PendingAuthDetailMapper.segmentLength())
                    .isNotEqualTo(PendingAuthDetailMapper.unloadedSegment(prefixedRecord));
            assertThatThrownBy(() -> PendingAuthDetailMapper.toSegmentFields(misaligned))
                    .as("a six-byte misalignment is caught by content, because length cannot catch it")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
