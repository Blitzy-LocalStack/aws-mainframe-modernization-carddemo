package com.carddemo.authorization.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.PendingAuthRowView;
import com.carddemo.authorization.dto.PendingAuthSummaryResponse;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import jakarta.persistence.Column;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the typed conversions of {@link PendingAuthSummaryMapper} to the committed summary images.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class asserts what the authorization context DOES with a decoded hundred-byte root segment,
 * one typed surface at a time: the carrier {@link PendingAuthSummaryMapper.SummarySegment}, the
 * aggregate {@link PendingAuthSummary}, and the screen projection
 * {@link PendingAuthSummaryResponse}. Five properties of that crossing are its subject, and each is a
 * place where a wrong answer is a plausible one that no later stage can detect: that three different
 * numeric encodings coexist inside one record and are dispatched by DECLARED regime rather than by
 * position or by apparent content; that the five-slot account-status array splits POSITIONALLY into
 * five discrete columns rather than collapsing into one value; that the trailing padding reaches
 * neither the aggregate nor the projection; that this segment's money is bounded one integer digit
 * NARROWER than the child segment's; and that the projection's component order carries a documented
 * irregularity at its fifth row.</p>
 *
 * <p>Parameters, return values, exceptions or errors. A test class accepts no parameter, yields no
 * value and raises nothing, so those three elements of user-specified Rule 1 are INAPPLICABLE here
 * rather than omitted. Every member below carries its own at-clauses or, where a case is a {@code void}
 * no-argument method, states the same inapplicability in prose.</p>
 *
 * <h2>Assumptions: what this class asserts, and where the same bytes are asserted differently</h2>
 *
 * <p>Four other classes read these same seven images, so the boundary between them is stated rather
 * than left to be inferred. {@code fixtures.PendingAuthSummaryFixtureTest} asserts the decoded FIELD
 * MAP through {@link FixedWidthCodec} against a descriptor it builds itself;
 * {@code dto.PendingAuthSummarySegmentFixtureTest} asserts the same values at raw packed-decimal
 * OFFSETS; {@code SegmentConversionContractTest} asserts the record-level round trip and the routing
 * rule between the two decodes; and {@code domain.PendingAuthSummaryRehydrationTest} asserts the
 * aggregate factory's argument validation. None of them asserts the typed consequences listed above,
 * which is why this class exists and why nothing here re-reads a byte at an offset.</p>
 *
 * <p>Assumptions: the codec internals belong to {@code services/common-lib} and are not restated. The
 * hundred-byte closure proof, the packed and binary width rules, the sign-nibble policy and the
 * negative-zero canonicalisation belong to its packed-decimal tests; the layout registry and the
 * padding policy to its fixed-width tests; and every {@code FieldSpec} kind, offset and length for
 * {@code PAUTSUM0} to its {@code CopybookLayoutTest}. This class therefore asserts the DECODED JAVA
 * TYPE each regime yields, which is a property of what this context consumes, and never the registry
 * entry that produced it.</p>
 *
 * <p>Assumptions: the zoned sign-overpunch alphabet does not apply anywhere in this record and is
 * referenced nowhere below. {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} L20 declares
 * {@code PA-CUST-ID PIC 9(09)} with no leading {@code S}, so the field is UNSIGNED display: nine
 * characters, nine digits, no sign position. Reading its final byte as an overpunched sign would
 * corrupt the ninth digit and raise nothing.</p>
 *
 * <h2>Assumptions: the committed images this class reads, named with what matters in each</h2>
 *
 * <p>The fixtures directory carries its own README, and this inventory is written here anyway rather
 * than deferred to it. A README states what an image contains; it cannot state which invariant a
 * particular test depends upon, so an image consumed without that statement is undocumented even in a
 * documented directory. Each entry below gives the length, the record count and the specific bytes this
 * class relies on, and every group further down repeats the part it uses at the case that uses it.</p>
 *
 * <ul>
 *   <li>{@code pautsum0-canonical.bin} -- 100 bytes, ONE record. Account 10000000001, customer
 *       {@code 000000451}, status {@code A}, the five distinct slots {@code A1} through {@code E5},
 *       limits 5000.00 and 1000.00, a NEGATIVE credit balance of -100.00 whose six packed bytes are
 *       {@code 00 00 00 10 00 0D} with the sign in the low nibble of the last one, a zero cash balance,
 *       counters 42 ({@code 0x002A}) and 7, totals 4200.00 and 700.00, and a BLANK padding interval. It
 *       is round-trip safe: no negative packed zero and no padding content, so every encode reproduces
 *       it.</li>
 *   <li>{@code pautsum0-filler-nonblank.bin} -- 100 bytes, ONE record, whose padding interval at
 *       offsets 66 to 99 carries {@code FILLERFILLERFILLERFILLERFILLER1234}. Its real fields DIFFER from
 *       the canonical image's: balance 250.00, counters 17 and 2, totals 1700.00 and 200.00. DECODE-ONLY
 *       for the carrier and aggregate paths, which hold no padding member, so an encode from either
 *       restores the interval as blanks.</li>
 *   <li>{@code pautsum0-negative-zero-decode-only.bin} -- 100 bytes, ONE record, whose credit balance is
 *       all-zero digits under the negative sign nibble, {@code 00 00 00 00 00 0D}. DECODE-ONLY, as its
 *       name declares: the value is zero, and an ordinary encode writes the positive nibble, so the one
 *       byte holding the sign differs from the source.</li>
 *   <li>{@code pautsum0-line-terminator-bytes.bin} -- 200 bytes, TWO records. The first carries counters
 *       whose bytes are {@code 0x0D0A} and {@code 0x0A0A}, a carriage-return and line-feed pair and two
 *       line feeds, decoding to 3338 and 2570. The second carries {@code 0xFF0A} and {@code 0xFF0D},
 *       decoding to -246 and -243, and the widest negative balance the field holds at full scale,
 *       -12345678.90.</li>
 *   <li>{@code pautsum0-purge-parent.bin} -- 100 bytes, ONE record. Account 10000000001, both counters
 *       2, totals 300.00 and 150.00, balance 450.00. PAIRED with
 *       {@code pautdtl1-newyear-pair.bin}.</li>
 *   <li>{@code unload-prefixed-summary-100.bin} -- 200 bytes, TWO records, in REVERSE account order with
 *       10000000002 first, and 59 of its 200 bytes are NULL. It carries no key prefix.</li>
 *   <li>{@code unload-gsam-summary-100.bin} -- 200 bytes, the same TWO records in CANONICAL account
 *       order with 10000000001 first, and the same 59 null bytes. Its order is load-bearing.</li>
 *   <li>{@code pautdtl1-newyear-pair.bin} -- 400 bytes, TWO 200-byte CHILD segments belonging to the
 *       purge parent's account, read here only to show where their attribution comes from.</li>
 *   <li>{@code unload-prefixed-detail-206.bin} -- read here only for its first 206-byte CHILD unload
 *       record, to show that the parent shape and the child shape are not interchangeable.</li>
 * </ul>
 *
 * <p>Assumptions: every image is read as BYTES and never as characters, and no image is created, edited
 * or written by hand anywhere in this class. Where a variant is needed that no committed image supplies
 * -- a blank status slot -- it is DERIVED from a committed image through that image's own decoded field
 * map and the sign-preserving encode, so every offset and width still comes from the registered layout
 * and every packed sign nibble the source carried survives.</p>
 *
 * <h2>Assumptions: four findings correct the brief this class was written from</h2>
 *
 * <p>Each is recorded because acting on the superseded reading would have produced a test that passed
 * while asserting the wrong thing, and because the repository is the authority where the two differ.
 * FIRST, {@link PendingAuthSummaryResponse} declares {@value #RESPONSE_COMPONENT_COUNT} record
 * components and not sixty-two: the first {@value #MAP_DERIVED_COMPONENT_COUNT} are derived from the
 * symbolic map {@code cpy-bms/COPAU00.cpy} and five opaque selectors are APPENDED after them, each
 * carrying no map field of its own. The sixty-two figure is real and is the map-derived prefix, so both
 * numbers are asserted below and neither is dropped. SECOND, neither summary unload image carries a key
 * prefix: {@code cbl/PAUDBUNL.CBL} L44 declares the parent record as {@code PIC X(100)}, the segment
 * verbatim, and the six-byte packed prefix its L45 to L48 declare belongs to the CHILD record at 206
 * bytes. The two shapes that genuinely differ are therefore the parent's and the child's, across two
 * mappers, and that is what the non-interchangeability case below asserts. THIRD, the non-blank padding
 * image does not share the canonical image's field values -- its balance, both counters and both totals
 * all differ -- so the padding drop is asserted as an interval property of one image rather than as an
 * equality between two. FOURTH, the segment declares TEN numeric fields and not eleven: seven packed
 * decimal, two binary halfwords and one unsigned display, which is the enumeration the brief itself
 * gives and which sums to ten. The dispatch table below therefore holds ten rows and is exhaustive at
 * ten, and the derived claim that a uniform decode would fail eight of eleven rows is restated below at
 * the counts that actually follow from the layout.</p>
 *
 * <p>Refactoring Rationale: this class was briefed to assert for itself that no binary floating-point
 * member reaches this mapper's money path, on the reading that the inherited layering rule was scoped
 * to the shared money package alone. Both charters in this tree record that reading as false: the rule
 * selects classes in the {@code com.carddemo} root followed by the subpackage wildcard, so
 * {@code com.carddemo.authorization} is inside its subject set and the money package identifier is only
 * that rule's own emptiness anchor. The sweep below is kept for a different and narrower reason, stated
 * at the case itself: an import graph sees a DECLARED type, and this mapper carries ten numeric fields
 * whose exactness depends on values rather than declarations.</p>
 *
 * @see PendingAuthSummaryMapper
 * @see PendingAuthDetailMapper
 */
class PendingAuthSummaryMapperTest {

    /**
     * The number of record components {@link PendingAuthSummaryResponse} declares.
     *
     * <p>Assumptions: sixty-seven is measured from the type and corroborated by the type's own trailing
     * ordinal comments, which run to sixty-two for the map-derived components and then to sixty-seven
     * across the five appended selectors. It is asserted rather than described so that a sixty-eighth
     * component added without a projection rule fails here instead of reaching a client as a null.</p>
     */
    private static final int RESPONSE_COMPONENT_COUNT = 67;

    /**
     * The number of leading response components derived from the symbolic map.
     *
     * <p>Assumptions: sixty-two is the count of field components the symbolic map
     * {@code cpy-bms/COPAU00.cpy} declares, and it is a PREFIX of the response rather than the whole of
     * it. Holding the prefix separately is what lets both figures be checked: a selector inserted among
     * the row components would leave the total unchanged while moving every map ordinal after it.</p>
     */
    private static final int MAP_DERIVED_COMPONENT_COUNT = 62;

    /**
     * The zero-based position of the fifth row's selection marker inside the response.
     *
     * <p>Assumptions: sixty is the position the map's own declaration order produces, not a position
     * chosen here. {@code cpy-bms/COPAU00.cpy} declares {@code SEL0005I} at L384, AFTER
     * {@code PAMT005I} at L378, while rows one to four each lead with their selection component. The
     * constant is named so the irregularity is visible at the assertion rather than buried in an index
     * literal.</p>
     */
    private static final int ROW5_SELECTION_POSITION = 60;

    /**
     * The account key both the canonical image and the purge parent carry.
     *
     * <p>Assumptions: {@code 10000000001} is the value the first six bytes decode to under the packed
     * rule, and it is shared by five of the seven images this class reads. It is held as a constant so
     * a case asserting cross-image identity cannot drift from a case asserting one image's value.</p>
     */
    private static final long FIRST_ACCOUNT = 10000000001L;

    /**
     * The account key the second record of each paired image carries.
     *
     * <p>Assumptions: {@code 10000000002} differs from {@link #FIRST_ACCOUNT} in its final digit alone,
     * which is deliberate in the images: a decode that read the packed key one nibble adrift would still
     * produce a plausible eleven-digit account, so the two keys are close enough that only an exact
     * assertion separates them.</p>
     */
    private static final long SECOND_ACCOUNT = 10000000002L;

    /**
     * The customer identifier the images carrying {@link #FIRST_ACCOUNT} declare.
     *
     * <p>Assumptions: the nine stored characters are {@code 000000451} and the decoded value is
     * {@code 451}. The leading zeros are padding to the declared nine positions rather than data, so the
     * character form and the numeric form are both asserted below and neither stands in for the
     * other.</p>
     */
    private static final long FIRST_CUSTOMER = 451L;

    /**
     * The nine stored characters of the customer identifier on the first-account images.
     *
     * <p>Assumptions: this is the field's LITERAL content, asserted so that the unsigned display regime
     * is visible as characters and not only as a number. A reader who saw the numeric form alone could
     * not tell nine ASCII digits from a binary quantity.</p>
     */
    private static final String FIRST_CUSTOMER_DIGITS = "000000451";

    /**
     * The five account-status slots the canonical image carries, in slot order.
     *
     * <p>Assumptions: the five pairs are deliberately DISTINCT and ascend in both characters, so a
     * reversal, a rotation, an off-by-one or a collapse changes at least one of the five. A fixture whose
     * slots repeated a value would satisfy a positional assertion under a transposition.</p>
     */
    private static final List<String> CANONICAL_SLOTS = List.of("A1", "B2", "C3", "D4", "E5");

    /**
     * The five account-status slots the second unload record carries, in slot order.
     *
     * <p>Assumptions: this second distinct set is read as well as the first, because a positional
     * mapping written against one image can be satisfied by five hard-coded literals. Two images whose
     * slots differ in every position cannot both be satisfied that way.</p>
     */
    private static final List<String> SECOND_SLOTS = List.of("B1", "C2", "D3", "E4", "F5");

    /**
     * The single authorization status character every summary image carries.
     *
     * <p>Assumptions: the character is passed through with no value domain imposed, because
     * {@code cpy/CIPAUSMY.cpy} L21 declares {@code PA-AUTH-STATUS PIC X(01)} and declares no
     * {@code 88}-level condition names beneath it. Inventing a domain here would refuse a value the
     * reference application stores.</p>
     */
    private static final String AUTH_STATUS = "A";

    /**
     * The zero-based offset at which the trailing padding field begins.
     *
     * <p>Assumptions: sixty-six is the sum of the thirteen declarations before it and is read from the
     * mapper's own constants rather than written as a literal, so the two cannot disagree. It is used
     * only to split an image into the part the padding occupies and the part it does not.</p>
     */
    private static final int PADDING_OFFSET =
            PendingAuthSummaryMapper.segmentLength() - PendingAuthSummaryMapper.FILLER_WIDTH;

    /**
     * The exact content of the non-blank padding image's trailing field.
     *
     * <p>Assumptions: the thirty-four characters are five repetitions of the word and then four digits.
     * The digits are the part that matters: padding made only of letters could not reveal a decoder that
     * had absorbed the interval into a preceding NUMERIC field, because letters are not digit
     * nibbles.</p>
     */
    private static final String NON_BLANK_PADDING = "FILLERFILLERFILLERFILLERFILLER1234";

    /**
     * The binding under which every selector and page cursor in this class is sealed.
     *
     * <p>Assumptions: any constant satisfies the shape predicate the response and the page envelope
     * apply, because nothing here reopens a token. The binding is named rather than inlined so that all
     * of this class's tokens are sealed under one value and a failure cannot be a binding mismatch.</p>
     */
    private static final String SELECTOR_BINDING = "pending-auth-row";

    /**
     * The unmasked account number a row's masked component is derived from.
     *
     * <p>Assumptions: the value never reaches an assertion as itself; it exists so the masking helper
     * this package shares has a real sixteen-digit input, and so the masked form asserted below is
     * derived rather than written out.</p>
     */
    private static final String UNMASKED_CARD_NUMBER = "4111111111111111";

    /**
     * The serialiser used for the payload-shape assertions.
     *
     * <p>Assumptions: the module registering the fixed-point amount type is added explicitly, because
     * the amount's JSON form is the property under assertion and a default mapper would render it by
     * whatever its bean introspection chose. This mirrors the sibling detail test's setup so the two
     * report the same payload shape for the same amount.</p>
     */
    private static final JsonMapper MAPPER =
            JsonMapper.builder().addModule(new MoneyModule()).build();

    /**
     * Reads one committed fixture image from this module's test resources as raw bytes.
     *
     * <p>Assumptions: the stream is read as BYTES and never as characters, and this is the only place
     * an image is opened. Two of the images this class reads carry counter bytes that are line
     * terminators and one carries thirty-six null bytes, so a character reader, a newline normaliser or
     * a line splitter anywhere on this path would corrupt a record while leaving it the right
     * length.</p>
     *
     * @param name the fixture file name within the {@code fixtures} resource directory
     * @return the whole file's bytes, never {@code null}
     * @throws IllegalStateException if no resource of that name is on the test classpath, which means
     *     the fixture was renamed or removed rather than that a decode failed
     * @throws UncheckedIOException if the resource cannot be read to completion
     */
    private static byte[] bytesOf(String name) {
        String resource = "fixtures/" + name;
        try (InputStream stream =
                PendingAuthSummaryMapperTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing committed fixture " + resource);
            }
            return stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + resource, unreadable);
        }
    }

    /**
     * Slices one hundred-byte segment out of a fixture holding a whole number of them.
     *
     * <p>Assumptions: the stride is taken from {@link PendingAuthSummaryMapper#segmentLength()} rather
     * than written as a literal, so a case that sliced with the wrong stride could not exist. The
     * length is checked first because a stream that is not a whole number of records is a renamed or
     * regenerated fixture, and reporting that is more useful than reporting the decode failure it would
     * otherwise cause.</p>
     *
     * @param stream the whole fixture image; must not be {@code null}
     * @param index the zero-based record ordinal to slice
     * @return exactly one segment image of the declared length, never {@code null}
     * @throws IllegalStateException if the stream is not a whole number of segments or does not hold a
     *     record at that ordinal
     */
    private static byte[] recordAt(byte[] stream, int index) {
        int stride = PendingAuthSummaryMapper.segmentLength();
        if (stream.length % stride != 0 || index < 0 || index >= stream.length / stride) {
            throw new IllegalStateException("a stream of " + stream.length + " bytes holds no record "
                    + index + " at a stride of " + stride);
        }
        return Arrays.copyOfRange(stream, index * stride, (index + 1) * stride);
    }

    /**
     * Reads one field's declared byte offset from the single registered layout.
     *
     * <p>Assumptions: the offset is READ from the registration the mapper itself binds rather than
     * written out as a literal, and the distinction is load-bearing twice over. A duplicated offset is
     * the one drift this class could not detect on its own, because a run read one byte adrift still
     * yields characters; and the registry's own geometry is asserted by the shared kernel's layout
     * tests, so restating it here would put one contract in two suites with nothing holding the copies
     * equal. This method locates a run for inspection; it makes no claim about where the run should
     * be.</p>
     *
     * @param fieldName the exact copybook field name whose offset is wanted
     * @return the zero-based byte offset the layout declares for that field
     * @throws IllegalStateException if the registered layout declares no field of that name, which
     *     means the field was renamed rather than that an offset is wrong
     */
    private static int offsetOf(String fieldName) {
        return CopybookLayout.layout(PendingAuthSummaryMapper.SEGMENT_LAYOUT_NAME).fields().stream()
                .filter(field -> field.name().equals(fieldName))
                .mapToInt(CopybookLayout.FieldSpec::start)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        PendingAuthSummaryMapper.SEGMENT_LAYOUT_NAME + " declares no field named "
                                + fieldName));
    }

    /**
     * Reads a run of an image as the characters the fixed-width fields store.
     *
     * <p>Assumptions: the single-byte Latin encoding is used rather than a multi-byte one, because
     * these records are fixed-width and one declared character position is one byte. A multi-byte
     * decoder would fold an invalid sequence into a replacement character and change the run's length,
     * which is precisely the corruption the byte-mode rule above exists to prevent.</p>
     *
     * @param image the segment image to read from; must not be {@code null}
     * @param offset the zero-based byte offset at which the run starts
     * @param length the number of bytes to read
     * @return the run as characters, one per byte, never {@code null}
     */
    private static String charactersAt(byte[] image, int offset, int length) {
        return new String(image, offset, length, StandardCharsets.ISO_8859_1);
    }

    /**
     * Builds a sealer whose tokens satisfy the shape both the page envelope and the response demand.
     *
     * <p>Assumptions: the key material is a fixed fill rather than a random value. Nothing in this
     * class reopens a token -- only its sealed SHAPE is required, because the response refuses a raw
     * composite key in a selector position -- so a deterministic value keeps a failure reproducible
     * without weakening anything that is actually asserted.</p>
     *
     * @return a sealer over deterministic key material, never {@code null}
     */
    private static CursorToken sealer() {
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        Arrays.fill(keyMaterial, (byte) 0x5A);
        return new CursorToken(keyMaterial, Duration.ofMinutes(5));
    }

    /**
     * Seals one opaque token for a row selector or a page cursor.
     *
     * @param cursorKey the plain key material to seal, which no assertion here reads back
     * @return a token carrying the sealed shape, never {@code null}
     */
    private static String sealed(String cursorKey) {
        return sealer().seal(SELECTOR_BINDING, cursorKey);
    }

    /**
     * Builds one authorization row for the screen projection.
     *
     * <p>Assumptions: the stored date and time are supplied as the SIX characters the child segment
     * declares, not as rendered text, because the rendering is what the projection performs and
     * supplying it pre-rendered would assert nothing. The card number is supplied already masked
     * because the row type refuses an unmasked one, so the mask is applied by this package's single
     * masking helper rather than written out.</p>
     *
     * @param transactionId the row's transaction identifier
     * @param storedDate the six stored date characters, year first
     * @param storedTime the six stored time characters, hours first
     * @param amount the approved amount the row publishes
     * @return a row whose selector is sealed and whose card number is masked, never {@code null}
     */
    private static PendingAuthRowView rowOf(String transactionId, String storedDate, String storedTime,
            Money amount) {
        return new PendingAuthRowView(sealed(transactionId), transactionId, storedDate, storedTime,
                "0100", PendingAuthRowView.APPROVAL_STATUS_APPROVED, "P", amount,
                PendingAuthDetailMapper.maskedCardNumber(UNMASKED_CARD_NUMBER));
    }

    /**
     * Wraps rows in the keyset page the projection takes.
     *
     * <p>Assumptions: the two cursor tokens are sealed for the same reason a selector is -- the page
     * envelope refuses a raw keyset cursor, since a cursor built from the key columns would publish
     * those columns. An empty list takes the envelope's own empty form, because the populated form
     * requires both cursors to be present.</p>
     *
     * @param rows the rows to publish, in display order; must not be {@code null}
     * @return a page over those rows, never {@code null}
     */
    private static PageResponse<PendingAuthRowView> pageOf(List<PendingAuthRowView> rows) {
        if (rows.isEmpty()) {
            return PageResponse.empty();
        }
        return PageResponse.ofRows(rows, sealed("first"), sealed("last"), false);
    }

    /**
     * Supplies the six screen components no stored segment holds.
     *
     * <p>Assumptions: each value is a plausible rendered form rather than a decoded one, matching the
     * reference program, which moves a title constant, a transaction identifier, a program name and an
     * already-rendered date and time onto the screen. Nothing below asserts these values; they exist so
     * the projection can be called at all.</p>
     *
     * @return the screen chrome for a projection call, never {@code null}
     */
    private static PendingAuthSummaryMapper.ScreenChrome chrome() {
        return new PendingAuthSummaryMapper.ScreenChrome("CP00", "CardDemo", "07/15/24", "COPAUS0C",
                "Pending Authorizations", "09:15:30");
    }

    /**
     * Supplies the five cardholder components the summary segment does not store.
     *
     * <p>Assumptions: the account-status component is supplied ABSENT, because the reference program
     * moves nothing into it anywhere and the projection passes it through as given. Supplying a value
     * would put content on a screen the baseline never populated.</p>
     *
     * @return the cardholder context for a projection call, never {@code null}
     */
    private static PendingAuthSummaryMapper.CardholderContext cardholder() {
        return new PendingAuthSummaryMapper.CardholderContext("CARDHOLDER NAME", "ADDRESS ONE", null,
                "ADDRESS TWO", "2065550100");
    }

    /**
     * Projects a stored summary and a page of rows onto the screen response.
     *
     * @param summary the stored aggregate to publish; must not be {@code null}
     * @param rows the rows to display, in order; must not be {@code null}
     * @return the fully populated projection, never {@code null}
     */
    private static PendingAuthSummaryResponse responseOf(PendingAuthSummary summary,
            List<PendingAuthRowView> rows) {
        return PendingAuthSummaryMapper.toResponse(summary, pageOf(rows), chrome(), cardholder(), null);
    }

    /**
     * Derives a segment image from a committed one with its account-status array replaced.
     *
     * <p>Assumptions: the replacement goes through the decoded field map and the SIGN-PRESERVING
     * encode, so every offset and width still comes from the registered layout and every packed sign
     * nibble the source image carried survives. Writing the ten bytes into the array by hand would let
     * this class agree with itself about where the array lives, which is the one error a committed
     * fixture exists to prevent.</p>
     *
     * @param image the committed segment image to derive from; must not be {@code null}
     * @param statusArray the ten characters to place in the array's interval
     * @return a newly allocated image of the declared segment length, never {@code null}
     */
    private static byte[] withStatusArray(byte[] image, String statusArray) {
        Map<String, Object> fields =
                new LinkedHashMap<>(PendingAuthSummaryMapper.toSegmentFields(image));
        fields.put("PA-ACCOUNT-STATUS", statusArray);
        return PendingAuthSummaryMapper.toSegment(fields, image);
    }

    /**
     * Lists the five account-status members of a decoded carrier in slot order.
     *
     * <p>Assumptions: this method loops where its entity-side counterpart below calls five accessors
     * individually, and the asymmetry is deliberate rather than an oversight. The carrier exposes ONE
     * indexed accessor taking a slot ordinal, so a loop is the only shape available to it, whereas the
     * stored aggregate exposes five separately declared accessors whose SEPARATENESS is itself under
     * assertion -- which is why only the entity-side helper is written out longhand.</p>
     *
     * @param decoded the carrier to read; must not be {@code null}
     * @return the five slot values in slot order, any of which may be {@code null}
     */
    private static List<String> slotsOf(PendingAuthSummaryMapper.SummarySegment decoded) {
        List<String> slots = new ArrayList<>(PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOTS);
        for (int slot = 1; slot <= PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOTS; slot++) {
            slots.add(decoded.accountStatusSlot(slot));
        }
        return slots;
    }

    /**
     * Lists the five account-status columns of a stored aggregate in column order.
     *
     * <p>Assumptions: the five accessors are called individually rather than through a loop over
     * reflection, because their SEPARATENESS is the property under assertion. A reflective read would
     * gather them into one collection and lose the distinction this method exists to demonstrate.</p>
     *
     * @param summary the aggregate to read; must not be {@code null}
     * @return the five column values in column order, any of which may be {@code null}
     */
    private static List<String> columnsOf(PendingAuthSummary summary) {
        return Arrays.asList(summary.getAccountStatus1(), summary.getAccountStatus2(),
                summary.getAccountStatus3(), summary.getAccountStatus4(), summary.getAccountStatus5());
    }

    /**
     * Reads one declared field of a mapped type, failing loudly when the name no longer exists.
     *
     * <p>Assumptions: an absent field is a RENAMED mapping rather than a wrong value, so the failure
     * names the type and the field instead of reporting a reflective error. That distinction is what
     * sends a reader to the mapping that moved rather than to the assertion that consumed it.</p>
     *
     * @param owner the type declaring the field; must not be {@code null}
     * @param fieldName the Java field name to read
     * @return the declared field, never {@code null}
     * @throws IllegalStateException if the type declares no field of that name
     */
    private static Field declaredFieldOf(Class<?> owner, String fieldName) {
        try {
            return owner.getDeclaredField(fieldName);
        } catch (NoSuchFieldException absent) {
            throw new IllegalStateException(owner.getSimpleName() + " declares no field named "
                    + fieldName, absent);
        }
    }

    /**
     * Reads the declared precision and scale of one mapped money column.
     *
     * <p>Assumptions: the figures are read from the mapping annotation rather than from generated data
     * definition language, because the annotation is what this module declares and what a schema
     * generator would read. A migration script asserted instead would move the subject from this
     * module's own declaration to a file the repository tree keeps elsewhere.</p>
     *
     * @param entityType the mapped type declaring the column; must not be {@code null}
     * @param fieldName the Java field name the column is mapped from
     * @return the declared precision and scale, in that order
     * @throws IllegalStateException if the field carries no column annotation, which means the mapping
     *     was removed rather than that a value is wrong
     */
    private static int[] precisionAndScaleOf(Class<?> entityType, String fieldName) {
        Column column = declaredFieldOf(entityType, fieldName).getAnnotation(Column.class);
        if (column == null) {
            throw new IllegalStateException(entityType.getSimpleName() + '.' + fieldName
                    + " declares no column mapping, so it has no precision to assert");
        }
        return new int[] {column.precision(), column.scale()};
    }

    /**
     * Lists the names of every record component a projection declares, in declaration order.
     *
     * @param recordType the record type to inspect; must not be {@code null}
     * @return the component names in declaration order, never {@code null}
     */
    private static List<String> componentNamesOf(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * The three numeric encodings that coexist inside one hundred-byte record.
     *
     * <p>Purpose. This group asserts that each numeric field is decoded by its DECLARED regime, and it
     * is the headline subject of this class. A mapper that applied one decode uniformly would still
     * produce a number for every field: applying the packed rule everywhere leaves the seven packed
     * fields right and the other three quietly wrong, and applying the display rule everywhere leaves
     * one right and nine quietly wrong. Neither case raises.</p>
     *
     * <p>Assumptions: the regimes coexist because {@code cpy/CIPAUSMY.cpy} L19 to L31 was hand laid out
     * for a hierarchical database rather than generated from one storage rule. Seven fields are packed
     * decimal -- the account key at L19 and the six amounts at L23 to L26 and L29 to L30, six bytes
     * apiece; two are binary halfwords, the counters at L27 and L28, two bytes apiece; and one is plain
     * unsigned display, the customer identifier at L20, nine bytes of characters.</p>
     *
     * <p>Assumptions: this is the LAST place the distinction can be caught, which is why it is asserted
     * here rather than left to a later stage. The target unifies all three onto one column set -- an
     * exact numeric for the amounts, a small integer for the counters and a big integer for both
     * identifiers -- so downstream of this mapper there is no regime to disagree with. A wrong decode
     * arrives as a well-typed, well-scaled, entirely plausible value.</p>
     *
     * <p>Assumptions: the CONSEQUENCE of a wrong generalisation is worth naming because neither failure
     * raises. Reading the canonical image's approved counter as packed decimal interprets the two bytes
     * {@code 0x002A} as digit nibbles with a trailing sign, finding digits zero, zero and two and an
     * alternate positive sign nibble that is legal; the answer would be two rather than forty-two.
     * Reading the customer identifier as a binary quantity consumes nine characters as though they were
     * an integer.</p>
     */
    @Nested
    @DisplayName("the three coexisting numeric regimes")
    class NumericRegimeDispatch {

        /**
         * Supplies every numeric field of the segment with the regime that decodes it.
         *
         * <p>Assumptions: all ten numeric fields are walked in one table rather than asserted in ten
         * separate cases, so a mapper that applied one regime uniformly fails on at least three rows at
         * once instead of on one. A single-case failure reads as one wrong value; three or more
         * simultaneous failures read as the dispatch defect they actually are.</p>
         *
         * <p>Assumptions: the expected Java type is part of each row, because the type is what the
         * regime produces and is the only observable difference between two regimes that both yield a
         * number. The packed and binary fields both arrive as an exact decimal while the unsigned
         * display field arrives as a whole number, so the type separates display from the other two and
         * the value separates packed from binary.</p>
         *
         * @return one row per numeric field, each holding the copybook field name, the regime that
         *     decodes it, the expected decoded value as text and the expected decoded type
         */
        private static Stream<Arguments> numericFields() {
            return Stream.of(
                    Arguments.of("PA-ACCT-ID", "packed", "10000000001", BigDecimal.class),
                    Arguments.of("PA-CUST-ID", "unsigned display", "451", Long.class),
                    Arguments.of("PA-CREDIT-LIMIT", "packed", "5000.00", BigDecimal.class),
                    Arguments.of("PA-CASH-LIMIT", "packed", "1000.00", BigDecimal.class),
                    Arguments.of("PA-CREDIT-BALANCE", "packed", "-100.00", BigDecimal.class),
                    Arguments.of("PA-CASH-BALANCE", "packed", "0.00", BigDecimal.class),
                    Arguments.of("PA-APPROVED-AUTH-CNT", "binary halfword", "42", BigDecimal.class),
                    Arguments.of("PA-DECLINED-AUTH-CNT", "binary halfword", "7", BigDecimal.class),
                    Arguments.of("PA-APPROVED-AUTH-AMT", "packed", "4200.00", BigDecimal.class),
                    Arguments.of("PA-DECLINED-AUTH-AMT", "packed", "700.00", BigDecimal.class));
        }

        /**
         * Confirms each numeric field decodes to the value and the type its declared regime produces.
         *
         * <p>Assumptions: the canonical image is the subject because its ten numeric fields differ
         * from one another in magnitude and in sign, so no two rows of the table can be satisfied by the
         * same bytes. Its layout is the ledger recorded on this class: the packed key at offset zero,
         * the display identifier at six, the four packed limits and balances at twenty-six, thirty-two,
         * thirty-eight and forty-four, the two binary counters at fifty and fifty-two, and the two
         * packed totals at fifty-four and sixty.</p>
         *
         * @param fieldName the copybook field name to read
         * @param regime the storage regime that decodes it, named so a failure message states which
         *     rule was expected to apply
         * @param expectedValue the value the regime yields, written as text so the assertion compares
         *     exact quantities rather than representations
         * @param expectedType the Java type the regime yields
         */
        @ParameterizedTest(name = "{0} is {1} and decodes to {2}")
        @MethodSource("numericFields")
        @DisplayName("every numeric field decodes by its declared regime, not by its position")
        void everyNumericFieldDecodesByItsDeclaredRegime(String fieldName, String regime,
                String expectedValue, Class<?> expectedType) {
            Map<String, Object> fields =
                    PendingAuthSummaryMapper.toSegmentFields(bytesOf("pautsum0-canonical.bin"));

            Object decoded = fields.get(fieldName);

            assertThat(decoded)
                    .as("%s is declared as %s, so its decoded type is what that regime yields",
                            fieldName, regime)
                    .isInstanceOf(expectedType);
            if (decoded instanceof BigDecimal exact) {
                assertThat(exact).isEqualByComparingTo(expectedValue);
            } else {
                assertThat(decoded).hasToString(expectedValue);
            }
        }

        /**
         * Confirms the customer identifier is unsigned display characters and carries no sign position.
         *
         * <p>Assumptions: {@code cpy/CIPAUSMY.cpy} L20 declares {@code PA-CUST-ID PIC 9(09)} with NO
         * leading {@code S}, so the nine bytes are nine digit characters and there is no sign anywhere
         * in the field. The literal characters are asserted alongside the decoded number because the
         * number alone cannot distinguish nine ASCII digits from a binary quantity that happens to
         * equal the same value.</p>
         *
         * <p>Assumptions: this is the concrete reason the zoned sign-overpunch alphabet is out of scope
         * for this whole module. Treating the field as sign-overpunched would read its FINAL byte as a
         * combined digit and sign, so {@code 000000451} would lose its ninth digit and yield a
         * different, still plausible, eight-digit customer -- a corruption that raises nothing because
         * the character in that position is a legal overpunch in some encodings.</p>
         *
         * <p>Assumptions: the field is also the only display numeric in the record, so no other field
         * can reveal this defect if this one does not. Its neighbours are a packed key before it and a
         * character status after it, and both decode correctly under any of the three rules.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the customer identifier is nine unsigned display digits with no sign position")
        void theCustomerIdentifierIsUnsignedDisplay() {
            byte[] image = bytesOf("pautsum0-canonical.bin");

            assertThat(charactersAt(image, offsetOf("PA-CUST-ID"),
                    PendingAuthSummaryMapper.CUSTOMER_ID_DIGITS))
                    .as("the nine stored bytes are digit characters, one per declared position")
                    .isEqualTo(FIRST_CUSTOMER_DIGITS)
                    .hasSize(PendingAuthSummaryMapper.CUSTOMER_ID_DIGITS)
                    .containsOnlyDigits();

            PendingAuthSummaryMapper.SummarySegment decoded =
                    PendingAuthSummaryMapper.toSummarySegment(image);

            assertThat(decoded.customerId())
                    .as("the leading zeros are padding to nine positions, not part of the value")
                    .isEqualTo(FIRST_CUSTOMER);
            assertThat(Long.toString(decoded.customerId()))
                    .as("no digit is consumed by a sign, so the last stored digit survives")
                    .endsWith(FIRST_CUSTOMER_DIGITS.substring(FIRST_CUSTOMER_DIGITS.length() - 1));
        }

        /**
         * Confirms the carrier widens both identifiers to whole numbers although two regimes stored
         * them.
         *
         * <p>Assumptions: the two identifiers arrive from the field map as DIFFERENT Java types -- the
         * packed key as an exact decimal and the display identifier as a whole number -- and the carrier
         * publishes both as whole numbers because the migration maps a numeric used as a key or an
         * identifier onto a big integer column. Neither is money, so neither carries a scale, and
         * asserting that here is what stops a later reader treating the packed key as an amount because
         * it decoded as a decimal.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("both identifiers reach the carrier as whole numbers and neither carries a scale")
        void bothIdentifiersReachTheCarrierAsWholeNumbers() {
            byte[] image = bytesOf("pautsum0-canonical.bin");
            Map<String, Object> fields = PendingAuthSummaryMapper.toSegmentFields(image);

            assertThat(fields.get("PA-ACCT-ID")).isInstanceOf(BigDecimal.class);
            assertThat(fields.get("PA-CUST-ID")).isInstanceOf(Long.class);

            PendingAuthSummaryMapper.SummarySegment decoded =
                    PendingAuthSummaryMapper.toSummarySegment(image);

            assertThat(decoded.accountId()).isEqualTo(FIRST_ACCOUNT);
            assertThat(decoded.customerId()).isEqualTo(FIRST_CUSTOMER);
            assertThat(PendingAuthSummaryMapper.fromExtractRecord(image).getAccountId())
                    .as("the aggregate stores the key as the same whole number the carrier published")
                    .isEqualTo(FIRST_ACCOUNT);
        }

        /**
         * Confirms a stream of two records is refused rather than decoded as though it were one.
         *
         * <p>Assumptions: the refusal is asserted because a paired image is exactly what a caller has in
         * hand when it reads one of the two unload files, and a decode that read the first hundred bytes
         * of a two-hundred-byte argument would succeed silently on record one and lose record two. The
         * length check is what makes the caller slice deliberately.</p>
         *
         * <p>This test names its exception in prose because the checked construct sits inside a lambda:
         * the decode raises {@link FixedWidthCodec.RecordLengthException} naming both the declared and
         * the supplied length. It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a two-record stream is refused by the single-segment decode")
        void aTwoRecordStreamIsRefusedByTheSingleSegmentDecode() {
            byte[] stream = bytesOf("unload-gsam-summary-100.bin");

            assertThat(stream)
                    .hasSize(2 * PendingAuthSummaryMapper.segmentLength());
            assertThatThrownBy(() -> PendingAuthSummaryMapper.toSummarySegment(stream))
                    .isInstanceOf(FixedWidthCodec.RecordLengthException.class)
                    .hasMessageContaining(Integer.toString(PendingAuthSummaryMapper.segmentLength()))
                    .hasMessageContaining(Integer.toString(stream.length));
        }
    }

    /**
     * The positional split of the five-slot account-status array into five discrete columns.
     *
     * <p>Purpose. This group asserts that occurrence one lands in the first column and occurrence five
     * in the fifth, that the five stay SEPARATE, and that a blank slot neither shifts its neighbours nor
     * acquires a value. It is the property this package owns exclusively, so its absence here is its
     * absence everywhere.</p>
     *
     * <p>Assumptions: {@code cpy/CIPAUSMY.cpy} L22 declares {@code PA-ACCOUNT-STATUS PIC X(02) OCCURS 5
     * TIMES}, which the layout registers as ONE ten-character interval. The five slots therefore begin
     * at offsets sixteen, eighteen, twenty, twenty-two and twenty-four of the record, and the split into
     * five members is the mapper's own work rather than the codec's -- which is exactly why it needs
     * asserting on this side of the boundary.</p>
     *
     * <p>Assumptions: POSITION is asserted and membership is not. A test that checked only that the five
     * expected values were all present would pass under a reversal, a rotation and any transposition,
     * and every one of those produces a well-formed row describing a different account. Each slot is
     * therefore compared to its own expected value individually.</p>
     *
     * <p>Alternatives Considered: the target could have carried the array as a single collection member
     * behind a PostgreSQL array column, and five discrete columns were chosen instead. The arity of five
     * is declared by the copybook, so five columns let the SCHEMA assert it: a sixth slot has no column
     * to land in and a fourth cannot go missing. An array column would move that arity into application
     * code, enforced only by whatever happened to build the list, and would need a vendor-specific
     * mapping in place of five portable character columns. The cost accepted is five accessors instead of
     * one, and the exchange is worthwhile because arity is precisely what a defect here would break.</p>
     */
    @Nested
    @DisplayName("the five-slot account-status array")
    class AccountStatusPositionalSplit {

        /**
         * Confirms each slot reaches the column of the same ordinal, in both typed forms.
         *
         * <p>Assumptions: the canonical image's five slots are deliberately DISTINCT and ascend in both
         * characters -- the first is {@code A1} and the fifth {@code E5} -- so any off-by-one, reversal
         * or collapse changes at least one of the five. The slot characters are also read straight out of
         * the image at the array's own declared offset and compared to the joined expectation, so the
         * mapping is asserted from the bytes through to the column rather than only between two typed
         * views of the same decode.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("each occurrence lands in the column of its own ordinal")
        void eachOccurrenceLandsInTheColumnOfItsOwnOrdinal() {
            byte[] image = bytesOf("pautsum0-canonical.bin");

            assertThat(charactersAt(image, offsetOf("PA-ACCOUNT-STATUS"),
                    PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOTS
                            * PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOT_WIDTH))
                    .as("the array is one interval in the record and the slots are its consecutive pairs")
                    .isEqualTo(String.join("", CANONICAL_SLOTS));

            PendingAuthSummaryMapper.SummarySegment decoded =
                    PendingAuthSummaryMapper.toSummarySegment(image);

            assertThat(slotsOf(decoded))
                    .as("the carrier splits the interval positionally, slot one first")
                    .containsExactlyElementsOf(CANONICAL_SLOTS);
            assertThat(decoded.accountStatus1()).isEqualTo(CANONICAL_SLOTS.get(0));
            assertThat(decoded.accountStatus2()).isEqualTo(CANONICAL_SLOTS.get(1));
            assertThat(decoded.accountStatus3()).isEqualTo(CANONICAL_SLOTS.get(2));
            assertThat(decoded.accountStatus4()).isEqualTo(CANONICAL_SLOTS.get(3));
            assertThat(decoded.accountStatus5()).isEqualTo(CANONICAL_SLOTS.get(4));
            assertThat(columnsOf(PendingAuthSummaryMapper.fromExtractRecord(image)))
                    .as("the aggregate's five columns carry the same five values in the same order")
                    .containsExactlyElementsOf(CANONICAL_SLOTS);
        }

        /**
         * Confirms the positional rule holds for a second image whose five slots differ everywhere.
         *
         * <p>Assumptions: the second unload record's slots are {@code B1} through {@code F5}, which
         * differ from the canonical image's in EVERY position. A positional mapping implemented with five
         * hard-coded literals would satisfy the case above and fail here, which is the only way to tell
         * a real split from a memorised one.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a second image with five different slots maps positionally too")
        void aSecondImageWithDifferentSlotsMapsPositionally() {
            byte[] record = recordAt(bytesOf("unload-gsam-summary-100.bin"), 1);

            assertThat(slotsOf(PendingAuthSummaryMapper.toSummarySegment(record)))
                    .as("no slot of this record shares a value with the canonical image's")
                    .containsExactlyElementsOf(SECOND_SLOTS)
                    .doesNotContainAnyElementsOf(CANONICAL_SLOTS);
            assertThat(columnsOf(PendingAuthSummaryMapper.fromExtractRecord(record)))
                    .containsExactlyElementsOf(SECOND_SLOTS);
        }

        /**
         * Confirms the five slots stay separate members and are never gathered into one value.
         *
         * <p>Assumptions: the shape is asserted by TYPE rather than by value, because a collapse is a
         * shape change that no value comparison can see: five values joined into one delimited string,
         * or held in a list, would still contain all five. The carrier's five components and the
         * aggregate's five mapped columns are therefore each required to be a plain character member, and
         * neither type may declare a collection or array member for them.</p>
         *
         * <p>Assumptions: the aggregate's mapped column names are read from its mapping annotations, so
         * the assertion covers what the SCHEMA receives and not only what Java holds. That is the half
         * that makes the arity enforceable by the database, which is the reason five columns were chosen
         * over an array in the first place.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the five slots are five separate character members, never one collapsed value")
        void theFiveSlotsAreFiveSeparateCharacterMembers() {
            List<String> carrierComponents = new ArrayList<>();
            for (RecordComponent component
                    : PendingAuthSummaryMapper.SummarySegment.class.getRecordComponents()) {
                if (component.getName().startsWith("accountStatus")) {
                    carrierComponents.add(component.getName());
                    assertThat(component.getType())
                            .as("carrier component %s must be a plain character member",
                                    component.getName())
                            .isEqualTo(String.class);
                }
            }
            assertThat(carrierComponents)
                    .containsExactly("accountStatus1", "accountStatus2", "accountStatus3",
                            "accountStatus4", "accountStatus5");

            List<String> mappedColumns = new ArrayList<>();
            for (Field field : PendingAuthSummary.class.getDeclaredFields()) {
                if (!field.getName().startsWith("accountStatus")) {
                    continue;
                }
                assertThat(field.getType())
                        .as("stored column %s must be a plain character member", field.getName())
                        .isEqualTo(String.class);
                Column column = field.getAnnotation(Column.class);
                assertThat(column).as("%s must be a mapped column", field.getName()).isNotNull();
                assertThat(column.length())
                        .as("%s stores exactly the two characters the picture declares", field.getName())
                        .isEqualTo(PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOT_WIDTH);
                mappedColumns.add(column.name());
            }
            assertThat(mappedColumns)
                    .as("the schema receives five discrete columns rather than one array column")
                    .containsExactly("account_status_1", "account_status_2", "account_status_3",
                            "account_status_4", "account_status_5");
        }

        /**
         * Confirms a blank slot becomes an absent value and shifts none of the slots after it.
         *
         * <p>Assumptions: the reference application clears an unused slot to SPACES and the migration
         * declares the five columns nullable, so a slot of spaces there means what an absent value means
         * here; carrying the spaces through would make a never-set slot compare unequal to an unset one
         * for no behavioural reason. The blank is placed in the MIDDLE, at occurrence two, because a
         * split that compacted the array would move occurrences three, four and five up by one and the
         * result would still be a well-formed row.</p>
         *
         * <p>Assumptions: the image under test is DERIVED from the committed canonical one through its
         * own decoded field map and the sign-preserving encode, so every offset and width still comes
         * from the registered layout and every packed sign nibble survives. No fixture is created,
         * edited or written by hand anywhere in this class.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a blank slot is absent and does not shift the slots after it")
        void aBlankSlotIsAbsentAndShiftsNothing() {
            byte[] canonical = bytesOf("pautsum0-canonical.bin");
            byte[] blankSecond = withStatusArray(canonical, "A1  C3D4E5");

            assertThat(blankSecond)
                    .hasSize(PendingAuthSummaryMapper.segmentLength())
                    .isNotEqualTo(canonical);
            assertThat(slotsOf(PendingAuthSummaryMapper.toSummarySegment(blankSecond)))
                    .as("occurrences three to five keep their own positions across the blank")
                    .containsExactly("A1", null, "C3", "D4", "E5");
            assertThat(columnsOf(PendingAuthSummaryMapper.fromExtractRecord(blankSecond)))
                    .containsExactly("A1", null, "C3", "D4", "E5");
        }

        /**
         * Confirms a wholly blank array leaves every one of the five columns absent.
         *
         * <p>Assumptions: the field map RETAINS the ten spaces and the absence is produced by the
         * mapper's own blank-to-absent rule rather than by the codec dropping the key. Only the trailing
         * padding is dropped when blank, and only because the descriptor handed to the codec is the very
         * registry instance; every other character field of this record survives blank. Asserting the
         * spaces in the map and the absence in the two typed views together is what locates the rule in
         * the mapper, where it is, instead of in the codec.</p>
         *
         * <p>Assumptions: the whole-array case is asserted as well as the single-slot case because it is
         * the state a segment is in immediately after creation -- the reference program clears the array
         * before any authorization has recorded a status -- so it is the common case rather than an edge
         * one, and a split that answered two spaces here would put a meaningless value in all five
         * columns of every new row.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a wholly blank array leaves all five columns absent")
        void aWhollyBlankArrayLeavesAllFiveColumnsAbsent() {
            byte[] canonical = bytesOf("pautsum0-canonical.bin");
            String blanks = " ".repeat(PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOTS
                    * PendingAuthSummaryMapper.ACCOUNT_STATUS_SLOT_WIDTH);
            byte[] blankArray = withStatusArray(canonical, blanks);

            assertThat(PendingAuthSummaryMapper.toSegmentFields(blankArray))
                    .as("the interval survives the decode as spaces; only padding is dropped when blank")
                    .containsEntry("PA-ACCOUNT-STATUS", blanks);
            assertThat(slotsOf(PendingAuthSummaryMapper.toSummarySegment(blankArray)))
                    .containsExactly(null, null, null, null, null);
            assertThat(columnsOf(PendingAuthSummaryMapper.fromExtractRecord(blankArray)))
                    .containsExactly(null, null, null, null, null);
        }
    }

    /**
     * The money contract of this segment, and the one integer digit that separates it from the child's.
     *
     * <p>Purpose. This group asserts the properties of money that no import graph and no column type can
     * see: that every amount carries a scale of two, that rounding is half up, that an amount crosses to
     * a payload as a STRING rather than as a bare number, and that this segment's declared precision is
     * eleven where the child segment's is twelve.</p>
     *
     * <p>Assumptions: the two segments genuinely differ by one integer digit, and a shared money column
     * type would therefore be wrong. {@code cpy/CIPAUSMY.cpy} declares every amount here as
     * {@code PIC S9(09)V99}, giving nine integer digits and two decimals, so the column is
     * {@code NUMERIC(11,2)}; {@code cpy/CIPAUDTY.cpy} L34 and L35 declare the child's two amounts as
     * {@code PIC S9(10)V99}, so those columns are {@code NUMERIC(12,2)}. Folding the two onto one
     * constant is the kind of harmless-looking unification that truncates the wider one silently, which
     * is why both figures are read from their own mappings and compared.</p>
     */
    @Nested
    @DisplayName("the money contract and the summary-versus-detail precision split")
    class MoneyPrecisionContract {

        /**
         * Confirms all six amounts reach the aggregate at scale two under half-up rounding.
         *
         * <p>Assumptions: a scale is a property of a VALUE rather than of a declared type, so nothing in
         * the import graph can assert it and this is where it has to be asserted. All six components are
         * checked rather than one, because the six are populated from three different places on the
         * screen -- two mirrored limits, two balances and two running totals -- and a scale derived from
         * the value instead of from the picture would show on whichever of them happened to arrive
         * whole.</p>
         *
         * <p>Assumptions: the rounding mode is asserted against the shared kernel's own constant rather
         * than written as a literal, so this context cannot drift from the migration's single statement
         * that money rounds half up.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("all six amounts carry scale two and the shared half-up rounding")
        void allSixAmountsCarryScaleTwo() {
            assertThat(PendingAuthSummaryMapper.MONEY_DECIMAL_DIGITS).isEqualTo(Money.SCALE);
            assertThat(Money.GENERAL_ROUNDING).isEqualTo(RoundingMode.HALF_UP);

            PendingAuthSummary stored =
                    PendingAuthSummaryMapper.fromExtractRecord(bytesOf("pautsum0-canonical.bin"));

            assertThat(stored.getCreditLimit()).isEqualByComparingTo("5000.00");
            assertThat(stored.getCashLimit()).isEqualByComparingTo("1000.00");
            assertThat(stored.getCreditBalance()).isEqualByComparingTo("-100.00");
            assertThat(stored.getCashBalance()).isEqualByComparingTo("0.00");
            assertThat(stored.getApprovedAuthAmount()).isEqualByComparingTo("4200.00");
            assertThat(stored.getDeclinedAuthAmount()).isEqualByComparingTo("700.00");
            for (BigDecimal amount : List.of(stored.getCreditLimit(), stored.getCashLimit(),
                    stored.getCreditBalance(), stored.getCashBalance(),
                    stored.getApprovedAuthAmount(), stored.getDeclinedAuthAmount())) {
                assertThat(amount.scale())
                        .as("every stored amount is exact at the scale its column declares")
                        .isEqualTo(Money.SCALE);
            }
        }

        /**
         * Confirms this segment's amounts declare eleven digits of precision where the child's declare
         * twelve.
         *
         * <p>Assumptions: both figures are read from the mapping annotations of the two aggregates rather
         * than from one written expectation, so the assertion is a COMPARISON between two declarations
         * and not a restatement of either. That is what makes it able to fail when one side is changed to
         * match the other, which is exactly the unification it exists to prevent.</p>
         *
         * <p>Assumptions: eleven follows from nine integer digits plus two decimals and twelve from ten
         * plus two, so the difference is one INTEGER digit and not a difference in scale. Both scales are
         * asserted equal for that reason: a reader who saw only the precision difference might conclude
         * the two segments store money differently, when in fact they store the same fixed point over a
         * different range.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the summary declares eleven digits of precision and the detail twelve")
        void theSummaryDeclaresElevenDigitsWhereTheDetailDeclaresTwelve() {
            int[] summaryLimit = precisionAndScaleOf(PendingAuthSummary.class, "creditLimit");
            int[] summaryTotal = precisionAndScaleOf(PendingAuthSummary.class, "approvedAuthAmount");
            int[] detailAmount = precisionAndScaleOf(PendingAuthDetail.class, "transactionAmount");
            int[] detailApproved = precisionAndScaleOf(PendingAuthDetail.class, "approvedAmount");

            assertThat(summaryLimit[0])
                    .as("nine integer digits plus two decimals is eleven, from PIC S9(09)V99")
                    .isEqualTo(PendingAuthSummaryMapper.MONEY_INTEGER_DIGITS
                            + PendingAuthSummaryMapper.MONEY_DECIMAL_DIGITS)
                    .isEqualTo(11);
            assertThat(summaryTotal[0]).isEqualTo(summaryLimit[0]);
            assertThat(detailAmount[0])
                    .as("the child's PIC S9(10)V99 carries one more integer digit, so twelve")
                    .isEqualTo(12);
            assertThat(detailApproved[0]).isEqualTo(detailAmount[0]);
            assertThat(summaryLimit[0])
                    .as("the two segments differ by one integer digit, so one shared constant is wrong")
                    .isNotEqualTo(detailAmount[0]);
            assertThat(summaryLimit[1]).isEqualTo(Money.SCALE);
            assertThat(detailAmount[1])
                    .as("the difference is in the integer range and never in the scale")
                    .isEqualTo(summaryLimit[1]);
        }

        /**
         * Confirms every amount the projection publishes crosses to a payload as a string.
         *
         * <p>Assumptions: a bare JSON number is parsed into a binary floating-point value by most
         * clients, which discards exactness at the one boundary a user actually sees, so the amount is
         * serialised as a quoted decimal. The assertion is made against the serialised TEXT rather than
         * against the object, because the object cannot reveal how it will be written and a custom writer
         * is precisely what decides it.</p>
         *
         * <p>Assumptions: the absence of the unquoted form is asserted as well as the presence of the
         * quoted one. A payload that carried the same amount twice, once each way, would satisfy a
         * presence-only assertion while still handing a client a number to round.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a published amount crosses to JSON as a string, not as a bare number")
        void aPublishedAmountCrossesToJsonAsAString() {
            PendingAuthSummary stored =
                    PendingAuthSummaryMapper.fromExtractRecord(bytesOf("pautsum0-canonical.bin"));

            PendingAuthSummaryResponse response = responseOf(stored, List.of());
            String json = MAPPER.writeValueAsString(response);

            assertThat(response.creditLimit()).isEqualTo(Money.of("5000.00"));
            assertThat(json)
                    .as("the amount is a quoted decimal, so no client parses it into a double")
                    .contains("\"creditLimit\":\"5000.00\"")
                    .contains("\"creditBalance\":\"-100.00\"")
                    .contains("\"approvedAuthAmount\":\"4200.00\"");
            assertThat(json)
                    .as("no amount appears unquoted anywhere in the payload")
                    .doesNotContain(":5000.00")
                    .doesNotContain(":-100.00")
                    .doesNotContain(":4200.00");
        }

        /**
         * Confirms no member of this mapper declares a binary floating-point type.
         *
         * <p>Refactoring Rationale: this case replaces a per-class prohibition that the brief called for
         * on the reading that the inherited layering rule reached only the shared money package. Both
         * charters record that reading as superseded -- the rule selects the {@code com.carddemo} root
         * followed by the subpackage wildcard, so this package is already inside its subject set -- and
         * what was wrong with the old approach is that it would have re-proved an inherited rule and, by
         * appearing to be the only guard, told a reader this module was otherwise unprotected.</p>
         *
         * <p>Assumptions: the sweep is kept for a narrower reason than the one it was briefed with. It
         * covers every declared member of THIS mapper in one place, including its three nested records
         * and its private helpers, which is the sweep a reader of this file can check without opening the
         * kernel's rule set; and it is reflective over the declared members rather than written against a
         * named list, so a helper added later is swept without this case being revisited.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("no field, parameter or return type of this mapper is binary floating point")
        void noMemberOfThisMapperIsBinaryFloatingPoint() {
            List<Class<?>> swept = new ArrayList<>();
            swept.add(PendingAuthSummaryMapper.class);
            swept.addAll(Arrays.asList(PendingAuthSummaryMapper.class.getDeclaredClasses()));

            assertThat(swept)
                    .as("the mapper and its three nested carriers are all swept")
                    .hasSize(4);
            int inspected = 0;
            for (Class<?> type : swept) {
                for (Field field : type.getDeclaredFields()) {
                    inspected++;
                    assertThat(isBinaryFloatingPoint(field.getType()))
                            .as("field %s.%s must not be binary floating point", type.getSimpleName(),
                                    field.getName())
                            .isFalse();
                }
                for (Method method : type.getDeclaredMethods()) {
                    inspected++;
                    assertThat(isBinaryFloatingPoint(method.getReturnType()))
                            .as("return type of %s.%s must not be binary floating point",
                                    type.getSimpleName(), method.getName())
                            .isFalse();
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(isBinaryFloatingPoint(parameter))
                                .as("a parameter of %s.%s must not be binary floating point",
                                        type.getSimpleName(), method.getName())
                                .isFalse();
                    }
                }
            }
            assertThat(inspected)
                    .as("members inspected; none would make this sweep vacuous")
                    .isPositive();
        }

        /**
         * Confirms a stored negative amount decodes exactly and survives a round trip through the
         * aggregate.
         *
         * <p>Assumptions: the canonical image's credit balance is negative because its packed sign nibble
         * is the negative one, and the value is minus one hundred exactly. A negative balance is the case
         * a decode that ignored the sign position would get wrong while still producing a plausible
         * hundred, so the sign is asserted as well as the magnitude.</p>
         *
         * <p>Assumptions: the round trip asserted here is the AGGREGATE's -- decode to a stored row and
         * encode it back -- which is the one the unload path performs. It is byte-identical for this image
         * because its padding is blank and none of its packed amounts is a negative zero; the two cases
         * where that does not hold are asserted separately, in this group and in the padding group.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a negative balance decodes exactly and re-encodes to the same bytes")
        void aNegativeBalanceDecodesExactlyAndReEncodes() {
            byte[] image = bytesOf("pautsum0-canonical.bin");

            PendingAuthSummary stored = PendingAuthSummaryMapper.fromExtractRecord(image);

            assertThat(stored.getCreditBalance())
                    .as("the sign nibble is the negative one, so the balance is below zero")
                    .isNegative()
                    .isEqualByComparingTo("-100.00");
            assertThat(PendingAuthSummaryMapper.toUnloadRecord(stored))
                    .as("a blank-padded image carrying no negative zero round-trips byte for byte")
                    .isEqualTo(image);
        }

        /**
         * Confirms a stored negative zero decodes to zero and that its image is not an encode oracle.
         *
         * <p>Assumptions: the negative-zero image's credit balance carries all-zero digits under the
         * negative sign nibble, and both nibble values are legitimate there, so the two describe the same
         * quantity and differ only in bytes. What this case asserts is that the mapper neither raises on
         * the value nor carries the sign into the aggregate: the balance arrives as an exact zero whose
         * sign is neither plus nor minus, at the scale its column stores.</p>
         *
         * <p>Assumptions: this image is DECODE-ONLY, and the assertion states that rather than working
         * around it. Re-encoding from the aggregate writes the positive nibble, so the result differs from
         * the source in exactly the one byte that holds the sign; using this image as an encode oracle
         * would produce a failure that reads like a codec defect and is not one. The canonicalisation
         * rule itself belongs to the shared kernel's packed-decimal tests and is not restated here.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a stored negative zero decodes to zero and its image is decode-only")
        void aStoredNegativeZeroDecodesToZeroAndIsDecodeOnly() {
            byte[] image = bytesOf("pautsum0-negative-zero-decode-only.bin");

            PendingAuthSummary stored = PendingAuthSummaryMapper.fromExtractRecord(image);

            assertThat(stored.getCreditBalance())
                    .as("negative zero is zero, so the stored amount has no sign")
                    .isEqualByComparingTo("0.00");
            assertThat(stored.getCreditBalance().signum()).isZero();
            assertThat(stored.getCreditBalance().scale()).isEqualTo(Money.SCALE);
            assertThat(PendingAuthSummaryMapper.toUnloadRecord(stored))
                    .as("the aggregate holds no sign carrier, so this image cannot be an encode oracle")
                    .isNotEqualTo(image)
                    .hasSize(PendingAuthSummaryMapper.segmentLength());
        }
    }

    /**
     * Reports whether a type is one of the four binary floating-point types.
     *
     * <p>Assumptions: both the primitive and the boxed form of each width are named, because a boxed
     * member is the form a nullable column or a record component would actually take and a check on the
     * primitives alone would miss exactly the declarations most likely to appear.</p>
     *
     * @param type the declared type to classify; must not be {@code null}
     * @return {@code true} when the type is a binary floating-point type in either form
     */
    private static boolean isBinaryFloatingPoint(Class<?> type) {
        return type == float.class || type == double.class
                || type == Float.class || type == Double.class;
    }

    /**
     * The two binary counters, including the values a purge can legitimately drive below zero.
     *
     * <p>Purpose. This group asserts that the two counters are read as SIGNED two-byte quantities into
     * the small-integer members their columns declare, and that a negative counter is carried through
     * rather than clamped or refused.</p>
     *
     * <p>Assumptions: the image these cases read is {@code pautsum0-line-terminator-bytes.bin}, two
     * hundred bytes holding two records. Its first record's counters are the bytes of a carriage return
     * followed by a line feed, decoding to 3338, and two line feeds, decoding to 2570; its second
     * record's are a high byte before each of those terminators, decoding to minus 246 and minus 243.
     * That is the image's whole purpose: the counter bytes coincide with LINE TERMINATORS, so any path
     * that opened it as text, normalised newlines or split on them would corrupt a record while leaving
     * the file the right length. It is the tripwire for text-mode handling of a binary segment.</p>
     *
     * <p>Assumptions: a negative counter is REACHABLE and therefore legal. The purge program subtracts
     * from both counters at {@code cbl/CBPAUP0C.cbl} L288 and L291 with no floor beneath either, so a
     * purge that removes more authorizations than a counter recorded drives it below zero. A mapper that
     * clamped at zero or refused the value would diverge from the baseline, which is why this case
     * asserts the pass-through and does not repair it.</p>
     */
    @Nested
    @DisplayName("the two binary counters")
    class BinaryCounterContract {

        /**
         * Confirms counter bytes that spell line terminators decode as the numbers they are.
         *
         * <p>Assumptions: the expected values are written as the byte pairs they come from rather than as
         * bare decimals, so the assertion carries its own reason: 3338 is the carriage-return and
         * line-feed pair and 2570 is two line feeds. A reader who saw only the decimals could not tell
         * why this image exists.</p>
         *
         * <p>Assumptions: the file is also asserted to hold MORE pieces than records when split on a line
         * feed, which is the mechanical statement of the trap. That check is what distinguishes a fixture
         * that merely happens to contain a terminator byte from one that would actually be broken by a
         * line-oriented reader.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("counter bytes spelling line terminators decode as signed halfwords")
        void counterBytesSpellingLineTerminatorsDecodeAsHalfwords() {
            byte[] stream = bytesOf("pautsum0-line-terminator-bytes.bin");

            assertThat(stream).hasSize(2 * PendingAuthSummaryMapper.segmentLength());
            assertThat(charactersAt(stream, 0, stream.length).split("\n", -1).length)
                    .as("a line-oriented reader would find more pieces than the two records there are")
                    .isGreaterThan(2);

            PendingAuthSummaryMapper.SummarySegment first =
                    PendingAuthSummaryMapper.toSummarySegment(recordAt(stream, 0));

            assertThat(first.accountId()).isEqualTo(FIRST_ACCOUNT);
            assertThat(first.approvedAuthCount())
                    .as("0x0D0A is a carriage return and a line feed, and it is the number 3338")
                    .isEqualTo(Short.valueOf((short) 0x0D0A))
                    .isEqualTo(Short.valueOf((short) 3338));
            assertThat(first.declinedAuthCount())
                    .as("0x0A0A is two line feeds, and it is the number 2570")
                    .isEqualTo(Short.valueOf((short) 0x0A0A))
                    .isEqualTo(Short.valueOf((short) 2570));
            assertThat(PendingAuthSummaryMapper.fromExtractRecord(recordAt(stream, 0))
                    .getApprovedAuthCount())
                    .as("the aggregate stores the same halfword the carrier published")
                    .isEqualTo(Short.valueOf((short) 3338));
        }

        /**
         * Confirms a counter below zero is carried through rather than clamped or refused.
         *
         * <p>Assumptions: the two negative values are the high byte followed by each terminator byte, so
         * the same image proves both that the field is SIGNED and that a high byte survives beside a
         * terminator. An unsigned reading of the same two bytes would give 65290 and 65293, which are
         * outside a four-digit picture and would fail the aggregate's own bound rather than merely
         * differing -- so an unsigned decode would present as a validation error and send a reader looking
         * in the wrong place.</p>
         *
         * <p>Assumptions: the second record also carries the widest negative balance the field can hold at
         * full scale, minus twelve million three hundred and forty-five thousand six hundred and
         * seventy-eight point ninety. It is asserted alongside the counters because the two are the only
         * negative values in either record, and a decode that dropped a sign would drop both.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a counter driven below zero by a purge is carried through unchanged")
        void aCounterBelowZeroIsCarriedThroughUnchanged() {
            byte[] record = recordAt(bytesOf("pautsum0-line-terminator-bytes.bin"), 1);

            PendingAuthSummaryMapper.SummarySegment decoded =
                    PendingAuthSummaryMapper.toSummarySegment(record);

            assertThat(decoded.accountId()).isEqualTo(SECOND_ACCOUNT);
            assertThat(decoded.approvedAuthCount())
                    .as("0xFF0A read as the signed halfword it is")
                    .isEqualTo(Short.valueOf((short) 0xFF0A))
                    .isEqualTo(Short.valueOf((short) -246));
            assertThat(decoded.declinedAuthCount())
                    .as("0xFF0D read as the signed halfword it is")
                    .isEqualTo(Short.valueOf((short) 0xFF0D))
                    .isEqualTo(Short.valueOf((short) -243));
            assertThat(decoded.creditBalance().amount()).isEqualByComparingTo("-12345678.90");

            PendingAuthSummary stored = PendingAuthSummaryMapper.fromExtractRecord(record);

            assertThat(stored.getApprovedAuthCount())
                    .as("the aggregate accepts a negative counter rather than clamping it at zero")
                    .isEqualTo(Short.valueOf((short) -246));
            assertThat(stored.getDeclinedAuthCount()).isEqualTo(Short.valueOf((short) -243));
            assertThat(PendingAuthSummaryMapper.toUnloadRecord(stored))
                    .as("the negative counters survive the encode in their own two bytes")
                    .isEqualTo(record);
        }

        /**
         * Confirms both counters are held as small integers within a signed halfword's range.
         *
         * <p>Assumptions: the storage type is asserted reflectively and the values numerically, because
         * the two claims are independent: a counter widened to a larger integer type would still hold
         * every value this image carries, and a value outside the halfword would still fit a member of the
         * right type. The type is what the four-digit picture maps onto and the range is what the two
         * stored bytes can express.</p>
         *
         * <p>Assumptions: every counter this class reads is checked against the halfword bounds rather
         * than only the negative ones, so the range claim covers the positive extreme the terminator image
         * carries as well as the negative one.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("both counters are small integers inside a signed halfword's range")
        void bothCountersAreSmallIntegersInsideAHalfwordRange() {
            for (String fieldName : List.of("approvedAuthCount", "declinedAuthCount")) {
                assertThat(declaredFieldOf(PendingAuthSummary.class, fieldName).getType())
                        .as("%s is stored as the small integer the four-digit picture maps onto",
                                fieldName)
                        .isEqualTo(Short.class);
            }

            byte[] stream = bytesOf("pautsum0-line-terminator-bytes.bin");
            for (int index = 0; index < stream.length / PendingAuthSummaryMapper.segmentLength();
                    index++) {
                PendingAuthSummaryMapper.SummarySegment decoded =
                        PendingAuthSummaryMapper.toSummarySegment(recordAt(stream, index));
                for (Short counter : List.of(decoded.approvedAuthCount(),
                        decoded.declinedAuthCount())) {
                    assertThat(counter.intValue())
                            .as("record %d carries a counter a signed halfword can express", index)
                            .isBetween((int) Short.MIN_VALUE, (int) Short.MAX_VALUE);
                }
            }
        }
    }

    /**
     * The trailing padding field, and the fact that nothing downstream carries it.
     *
     * <p>Purpose. This group asserts that the thirty-four bytes at the end of the record reach neither the
     * aggregate nor the projection, and that a difference confined to them is invisible to both.</p>
     *
     * <p>Assumptions: {@code cpy/CIPAUSMY.cpy} L31 declares {@code FILLER PIC X(34)} with no
     * {@code VALUE} clause, so the interval is padding to the declared hundred bytes and carries nothing.
     * That distinction matters because a filler carrying a literal IS data -- this context composes a
     * timestamp elsewhere whose own three-character filler must survive -- so the drop is a property of
     * this field rather than of the word.</p>
     *
     * <p>Assumptions: the committed non-blank-padding image does NOT share the canonical image's field
     * values. Its balance is 250.00 where the canonical's is minus 100.00, its counters are 17 and 2
     * where the canonical's are 42 and 7, and its totals are 1700.00 and 200.00 where the canonical's are
     * 4200.00 and 700.00. The drop is therefore asserted as an INTERVAL property of that one image --
     * every byte before the padding survives an aggregate round trip and every byte of the padding comes
     * back blank -- rather than as an equality between two images, which would have been false.</p>
     */
    @Nested
    @DisplayName("the trailing padding field")
    class TrailingPaddingDrop {

        /**
         * Confirms neither the aggregate nor the projection declares a padding-derived member.
         *
         * <p>Assumptions: the absence is checked by NAME across every declared member of both types,
         * because padding is the one field a positional reader is most likely to carry through: it decodes
         * cleanly, it is the widest run of blanks in the record, and nothing about it looks wrong. A
         * reflective sweep also covers a member added later without this case being revisited.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("no aggregate column and no projection component is padding-derived")
        void noStoredColumnOrPublishedComponentIsPaddingDerived() {
            for (Field field : PendingAuthSummary.class.getDeclaredFields()) {
                assertThat(field.getName().toLowerCase(Locale.ROOT))
                        .as("no stored column may be derived from the record's trailing padding")
                        .doesNotContain("filler")
                        .doesNotContain("padding");
            }
            for (String component : componentNamesOf(PendingAuthSummaryResponse.class)) {
                assertThat(component.toLowerCase(Locale.ROOT))
                        .as("no published component may be derived from the trailing padding")
                        .doesNotContain("filler")
                        .doesNotContain("padding");
            }
            for (RecordComponent component
                    : PendingAuthSummaryMapper.SummarySegment.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("the carrier holds no padding member either, which is why an encode from it"
                                + " cannot restore non-blank padding")
                        .doesNotContain("filler")
                        .doesNotContain("padding");
            }
        }

        /**
         * Confirms a difference confined to the padding reaches neither the aggregate nor the projection.
         *
         * <p>Assumptions: the two images compared are the committed non-blank-padding one and the
         * aggregate round trip of that same image, so the comparison is between one record and its own
         * re-encoding. Every byte before the padding offset must be identical, which is what proves no
         * real field was disturbed, and every byte of the padding must be blank, which is what proves the
         * interval was not carried.</p>
         *
         * <p>Assumptions: this image is DECODE-ONLY for the aggregate path and the assertion says so
         * rather than working around it. The aggregate holds no member in which thirty-four non-blank
         * bytes could be kept, so an encode from it restores the interval as blanks by design; a later
         * reader who tried to make this round trip byte-identical would have to retain padding, which is
         * the opposite of what the record declares. The field map keeps the bytes, so the byte-exact path
         * for this image is the map-based encode -- and that path is asserted by the fixture-contract
         * class rather than here.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a non-blank padding is dropped from the aggregate and restored as blanks")
        void aNonBlankPaddingIsDroppedAndRestoredAsBlanks() {
            byte[] image = bytesOf("pautsum0-filler-nonblank.bin");

            assertThat(charactersAt(image, PADDING_OFFSET, PendingAuthSummaryMapper.FILLER_WIDTH))
                    .as("the committed image genuinely carries content in its padding interval")
                    .isEqualTo(NON_BLANK_PADDING);
            assertThat(PendingAuthSummaryMapper.toSegmentFields(image))
                    .as("the codec retains a non-blank padding, so the difference is real at the map")
                    .containsEntry("FILLER", NON_BLANK_PADDING);

            byte[] roundTripped = PendingAuthSummaryMapper.toUnloadRecord(
                    PendingAuthSummaryMapper.fromExtractRecord(image));

            assertThat(Arrays.copyOf(roundTripped, PADDING_OFFSET))
                    .as("every byte before the padding survives, so no real field was disturbed")
                    .isEqualTo(Arrays.copyOf(image, PADDING_OFFSET));
            assertThat(charactersAt(roundTripped, PADDING_OFFSET,
                    PendingAuthSummaryMapper.FILLER_WIDTH))
                    .as("the padding comes back as blanks because nothing downstream carried it")
                    .isEqualTo(" ".repeat(PendingAuthSummaryMapper.FILLER_WIDTH));
            assertThat(roundTripped)
                    .as("the record is still exactly the declared length either way")
                    .hasSize(PendingAuthSummaryMapper.segmentLength())
                    .isNotEqualTo(image);
        }

        /**
         * Confirms a blank padding is absent from the decode while a non-blank one is present.
         *
         * <p>Assumptions: the two committed images differ in exactly this respect, so the pair is what
         * makes the conditional nature of the drop observable. The canonical image's decode carries twelve
         * entries and the non-blank one's thirteen, and the extra entry is the padding; a reader who saw
         * only one of the two could not tell a rule that drops blank padding from one that drops the field
         * unconditionally.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a blank padding is absent from the decode and a non-blank one is present")
        void aBlankPaddingIsAbsentAndANonBlankOneIsPresent() {
            Map<String, Object> blankPadded =
                    PendingAuthSummaryMapper.toSegmentFields(bytesOf("pautsum0-canonical.bin"));
            Map<String, Object> contentPadded =
                    PendingAuthSummaryMapper.toSegmentFields(bytesOf("pautsum0-filler-nonblank.bin"));

            assertThat(blankPadded).doesNotContainKey("FILLER");
            assertThat(contentPadded).containsKey("FILLER");
            assertThat(contentPadded).hasSize(blankPadded.size() + 1);
            assertThat(blankPadded.keySet())
                    .as("the twelve data components are the same in both, in declaration order")
                    .containsExactlyElementsOf(
                            contentPadded.keySet().stream().filter(key -> !"FILLER".equals(key))
                                    .toList());
        }
    }

    /**
     * The flattened screen projection, and the irregular position of the fifth row's selection marker.
     *
     * <p>Purpose. This group asserts the projection's component COUNT and its component ORDER, and in
     * particular that the fifth row's selection marker sits after that row's amount rather than beside the
     * other four markers.</p>
     *
     * <p>Assumptions: the response declares {@value #RESPONSE_COMPONENT_COUNT} components, of which the
     * first {@value #MAP_DERIVED_COMPONENT_COUNT} are derived from the symbolic map
     * {@code cpy-bms/COPAU00.cpy} -- six of screen chrome, fifteen of account and cardholder context,
     * forty of row data across five rows of eight, and one message line -- and the last five are opaque
     * row selectors that no map field declares. Both figures are asserted, because a selector inserted
     * among the row components would leave the total unchanged while moving every map ordinal after
     * it.</p>
     *
     * <p>Assumptions: the fifth row's order is IRREGULAR in the source and the irregularity is preserved.
     * Rows one through four each lead with their selection component, {@code SEL0001I} at
     * {@code cpy-bms/COPAU00.cpy} L150 preceding its row's data, whereas {@code SEL0005I} is declared at
     * L384, AFTER {@code PAMT005I} at L378. The response's component order was derived from that
     * declaration order, so the position is authoritative even though it looks like a transcription
     * slip.</p>
     *
     * <p>Trade-offs: the target keeps the surprising order for compatibility rather than normalising it
     * for readability. Moving the marker into line with the other four would tidy the type and would
     * change the shape of a payload a client reads, and because the two components involved are a
     * one-character marker and a twelve-character amount, a client that had bound to the published order
     * would silently transpose them. The cost accepted is that a reader of the type meets one component
     * out of the pattern; the compensation is that both the type and this case name the source lines that
     * put it there.</p>
     */
    @Nested
    @DisplayName("the flattened projection and the fifth row's selection marker")
    class ResponseFlattening {

        /**
         * Confirms the projection declares exactly sixty-seven components with sixty-two map-derived.
         *
         * <p>Assumptions: the total is asserted exactly rather than as a lower bound, so that an
         * accidental sixty-sixth or sixty-eighth component fails here instead of reaching a client
         * unpopulated. The map-derived prefix is asserted by locating the message line, which is the map's
         * final field at L390, and requiring it to be the sixty-second component; everything after it is
         * therefore an appended selector.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the projection declares sixty-seven components, sixty-two of them map-derived")
        void theProjectionDeclaresSixtySevenComponents() {
            List<String> components = componentNamesOf(PendingAuthSummaryResponse.class);

            assertThat(components).hasSize(RESPONSE_COMPONENT_COUNT);
            assertThat(components.indexOf("message"))
                    .as("the message line is the map's final field, so it closes the map-derived prefix")
                    .isEqualTo(MAP_DERIVED_COMPONENT_COUNT - 1);
            assertThat(components.subList(MAP_DERIVED_COMPONENT_COUNT, RESPONSE_COMPONENT_COUNT))
                    .as("the five components after the prefix are the appended selectors")
                    .containsExactly("row1Selector", "row2Selector", "row3Selector", "row4Selector",
                            "row5Selector");
        }

        /**
         * Confirms the fifth row's selection marker follows that row's amount, component by component.
         *
         * <p>Assumptions: the tail of the projection is asserted as an exact SEQUENCE rather than by
         * membership, because the defect this case exists to catch is a reordering and every component
         * involved would still be present after one. The sequence begins at the fourth row's selection
         * marker so that the regular shape of row four and the irregular shape of row five appear in the
         * same assertion and can be compared directly.</p>
         *
         * <p>Assumptions: the first four rows are asserted to be regular as well, since the fifth row's
         * position is only meaningful relative to them. A reader shown the fifth row alone could not tell
         * an irregularity from the convention.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the fifth row's selection marker is declared after that row's amount")
        void theFifthRowSelectionMarkerFollowsItsAmount() {
            List<String> components = componentNamesOf(PendingAuthSummaryResponse.class);

            for (int row = 1; row <= 4; row++) {
                assertThat(components.indexOf("row" + row + "Selection"))
                        .as("row %d leads with its selection marker, which row five does not", row)
                        .isLessThan(components.indexOf("row" + row + "ApprovedAmount"));
            }
            assertThat(components.indexOf("row5Selection"))
                    .as("the fifth marker FOLLOWS its amount, which the map declares at L384 after L378")
                    .isEqualTo(ROW5_SELECTION_POSITION)
                    .isGreaterThan(components.indexOf("row5ApprovedAmount"));
            assertThat(components.subList(components.indexOf("row4Selection"),
                    RESPONSE_COMPONENT_COUNT))
                    .as("the tail order is the map's own, irregular fifth row included")
                    .containsExactly("row4Selection", "row4TransactionId", "row4AuthDate",
                            "row4AuthTime", "row4AuthType", "row4ApprovalStatus", "row4MatchStatus",
                            "row4ApprovedAmount", "row5TransactionId", "row5AuthDate", "row5AuthTime",
                            "row5AuthType", "row5ApprovalStatus", "row5MatchStatus",
                            "row5ApprovedAmount", "row5Selection", "message", "row1Selector",
                            "row2Selector", "row3Selector", "row4Selector", "row5Selector");
        }

        /**
         * Confirms each row's published amount is the amount the row carries, in row order.
         *
         * <p>Assumptions: the amount a row publishes is the APPROVED amount and never the requested one.
         * {@code cbl/COPAUS0C.cbl} L525 moves the approved amount into the row's amount field, and the two
         * differ on a declined authorization, where an amount was requested and nothing was approved;
         * publishing the requested amount would show a declined authorization as though it had gone
         * through. The selection is made by the sibling view mapper when it builds the row, so what this
         * case asserts is that the projection reads the row's amount rather than substituting or
         * recomputing one.</p>
         *
         * <p>Assumptions: five rows with five DIFFERENT amounts are supplied, so a projection that filled
         * every row from the first one, or that transposed two rows, fails. Equal amounts would satisfy
         * such a projection.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("each row publishes its own approved amount in its own position")
        void eachRowPublishesItsOwnApprovedAmount() {
            List<Money> amounts = List.of(Money.of("11.11"), Money.of("22.22"), Money.of("33.33"),
                    Money.of("44.44"), Money.of("55.55"));
            List<PendingAuthRowView> rows = new ArrayList<>();
            for (int row = 0; row < amounts.size(); row++) {
                rows.add(rowOf("00000000000000" + (row + 1), "240715", "091530", amounts.get(row)));
            }

            PendingAuthSummaryResponse response = responseOf(
                    PendingAuthSummaryMapper.fromExtractRecord(bytesOf("pautsum0-canonical.bin")), rows);

            assertThat(response.row1ApprovedAmount()).isEqualTo(rows.get(0).amount());
            assertThat(response.row2ApprovedAmount()).isEqualTo(rows.get(1).amount());
            assertThat(response.row3ApprovedAmount()).isEqualTo(rows.get(2).amount());
            assertThat(response.row4ApprovedAmount()).isEqualTo(rows.get(3).amount());
            assertThat(response.row5ApprovedAmount()).isEqualTo(rows.get(4).amount());
            assertThat(List.of(response.row1ApprovedAmount(), response.row2ApprovedAmount(),
                    response.row3ApprovedAmount(), response.row4ApprovedAmount(),
                    response.row5ApprovedAmount()))
                    .as("five distinct amounts land in five distinct positions")
                    .containsExactlyElementsOf(amounts);
        }

        /**
         * Confirms the projection routes through the shared render helpers rather than a private copy.
         *
         * <p>Assumptions: the date, the time and the account-number mask are defined ONCE for this
         * package, by the sibling detail mapper, and this projection calls them. The internals of each are
         * the sibling test's subject and are not re-proved; what is asserted here is AGREEMENT, by
         * comparing each published value against the helper's own answer for the same input. A divergent
         * private copy inside this mapper would satisfy every other case in this class and fail only
         * here.</p>
         *
         * <p>Assumptions: one date, one time and one masking case are enough because the failure mode is a
         * duplicated definition rather than a wrong value: a second implementation of a re-ordering does
         * not agree with the first on one input and disagree on another, it either is the same rule or is
         * not. The date is the input that matters most, since the stored order is year first and the
         * displayed order is month first, so a copy that re-formatted instead of re-ordering would produce
         * a well-formed and wrong date.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the published date, time and mask agree with this package's shared helpers")
        void thePublishedRenderingsAgreeWithTheSharedHelpers() {
            PendingAuthRowView row = rowOf("000000000000001", "240715", "091530", Money.of("1234.56"));

            PendingAuthSummaryResponse response = responseOf(
                    PendingAuthSummaryMapper.fromExtractRecord(bytesOf("pautsum0-canonical.bin")),
                    List.of(row));

            assertThat(response.row1AuthDate())
                    .as("the stored order is year first and the published order is month first")
                    .isEqualTo(PendingAuthDetailMapper.renderOriginatingDate(row.authOrigDate()))
                    .isEqualTo("07/15/24");
            assertThat(response.row1AuthTime())
                    .as("the stored and published orders agree for the time, unlike the date beside it")
                    .isEqualTo(PendingAuthDetailMapper.renderOriginatingTime(row.authOrigTime()))
                    .isEqualTo("09:15:30");
            assertThat(row.cardNum())
                    .as("the row's own account number is masked by the one masking helper this package"
                            + " defines")
                    .isEqualTo(PendingAuthDetailMapper.maskedCardNumber(UNMASKED_CARD_NUMBER))
                    .isNotEqualTo(UNMASKED_CARD_NUMBER)
                    .endsWith(UNMASKED_CARD_NUMBER.substring(UNMASKED_CARD_NUMBER.length() - 4));
            for (String component : componentNamesOf(PendingAuthSummaryResponse.class)) {
                assertThat(component.toLowerCase(Locale.ROOT))
                        .as("the summary map declares no card-number field, so none is published")
                        .doesNotContain("card");
            }
            assertThat(MAPPER.writeValueAsString(response))
                    .as("and no serialised byte carries an account number in any form")
                    .doesNotContain(UNMASKED_CARD_NUMBER)
                    .doesNotContain(row.cardNum());
        }
    }

    /**
     * The two committed unload shapes of the parent record, and what their ordering does and does not
     * mean.
     *
     * <p>Purpose. This group asserts that the parent unload record IS the segment, that both committed
     * images decode record for record whatever order they are in, that each record satisfies the
     * counter-sum invariant the images encode, and that the parent's shape and the child's are not
     * interchangeable.</p>
     *
     * <p>Assumptions: the parent record carries NO key prefix, and this is the finding most likely to be
     * got wrong from the outside. {@code cbl/PAUDBUNL.CBL} L44 declares the parent output record as
     * {@code PIC X(100)} -- the segment verbatim -- and its L227 moves the whole segment into it in one
     * statement, so the declared unload length equals the declared segment length. The six-byte packed
     * prefix belongs to the CHILD record, which its L45 to L48 declare at 206 bytes ahead of a 200-byte
     * child image, because a child segment does not carry its parent's key and would otherwise be
     * unattributable. A reader who generalised from the child to the parent would skip six bytes of a
     * hundred and shift every field after them.</p>
     *
     * <p>Assumptions: {@code unload-prefixed-summary-100.bin} holds its two records in REVERSE account
     * order, the account ending in two first, and 59 of its 200 bytes are null. The null density is why
     * the image must be read in binary mode and why a null must never be treated as a terminator: packed
     * zero nibbles produce null bytes wherever an amount has leading zeros, which is most of this
     * record.</p>
     *
     * <p>Assumptions: {@code unload-gsam-summary-100.bin} holds the same two records in CANONICAL account
     * order, the account ending in one first, with the same 59 null bytes. Its order is load-bearing
     * because the load path it feeds requires a parent to exist before any child that hangs under it, so
     * the difference between the two images is deliberate rather than noise -- and a consumer that assumed
     * the first record was the lowest account would pass against one image and fail against the other.</p>
     */
    @Nested
    @DisplayName("the two unload shapes of the parent record")
    class UnloadRecordShapes {

        /**
         * Confirms the unload entry point reads the record as a whole segment with no prefix skipped.
         *
         * <p>Assumptions: the equality asserted is between the unload decode and the plain segment decode
         * of the SAME bytes, which is the exact statement that no prefix is skipped. Asserting the decoded
         * values against written expectations instead would pass for a decode that skipped six bytes and
         * happened to be given six bytes of leading zeros, so the comparison is between two entry points
         * rather than against a literal.</p>
         *
         * <p>Assumptions: the two declared lengths are asserted equal for the same reason. They coincide
         * for this record and diverge for the child's, so the coincidence is the property that makes one
         * method able to serve both directions here.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the parent unload record is the segment, with no key prefix to skip")
        void theParentUnloadRecordIsTheSegment() {
            assertThat(PendingAuthSummaryMapper.unloadRecordLength())
                    .as("the parent record is declared PIC X(100), the segment verbatim")
                    .isEqualTo(PendingAuthSummaryMapper.segmentLength());

            for (String name : List.of("unload-prefixed-summary-100.bin",
                    "unload-gsam-summary-100.bin")) {
                byte[] stream = bytesOf(name);
                assertThat(stream).as("%s holds two parent records", name)
                        .hasSize(2 * PendingAuthSummaryMapper.unloadRecordLength());
                for (int index = 0; index < 2; index++) {
                    byte[] record = recordAt(stream, index);
                    assertThat(PendingAuthSummaryMapper.fromUnloadRecord(record))
                            .as("%s record %d decodes identically through both entry points, which is"
                                    + " what proves no prefix is skipped", name, index)
                            .isEqualTo(PendingAuthSummaryMapper.toSummarySegment(record));
                }
            }
        }

        /**
         * Confirms both parents decode to their own values in each image, whichever order they are in.
         *
         * <p>Assumptions: the two images are asserted to be REORDERINGS of each other by comparing the
         * decode of one image's first record against the other image's second, and the values of each are
         * asserted individually so that two images in different orders holding different data cannot
         * satisfy the case. The pair is what shows file position carries no meaning for the prefixed image
         * even though it carries one for the sequential image the load consumes.</p>
         *
         * <p>Assumptions: the account keys differ only in their final digit, so a packed decode one nibble
         * adrift would still yield a plausible eleven-digit account. That is why the keys are asserted
         * exactly and why the two records' five status slots are also asserted, since those differ in every
         * position.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("both images hold the same two parents in opposite order")
        void bothImagesHoldTheSameTwoParentsInOppositeOrder() {
            byte[] prefixed = bytesOf("unload-prefixed-summary-100.bin");
            byte[] sequential = bytesOf("unload-gsam-summary-100.bin");

            PendingAuthSummaryMapper.SummarySegment prefixedFirst =
                    PendingAuthSummaryMapper.fromUnloadRecord(recordAt(prefixed, 0));
            PendingAuthSummaryMapper.SummarySegment sequentialFirst =
                    PendingAuthSummaryMapper.fromUnloadRecord(recordAt(sequential, 0));

            assertThat(prefixedFirst.accountId())
                    .as("the prefixed image opens on the HIGHER account, so its order is reversed")
                    .isEqualTo(SECOND_ACCOUNT);
            assertThat(slotsOf(prefixedFirst)).containsExactlyElementsOf(SECOND_SLOTS);
            assertThat(prefixedFirst.creditLimit().amount()).isEqualByComparingTo("7500.00");
            assertThat(sequentialFirst.accountId())
                    .as("the sequential image opens on the LOWER account, which its load path requires")
                    .isEqualTo(FIRST_ACCOUNT);
            assertThat(slotsOf(sequentialFirst)).containsExactlyElementsOf(CANONICAL_SLOTS);
            assertThat(sequentialFirst.creditLimit().amount()).isEqualByComparingTo("5000.00");

            assertThat(PendingAuthSummaryMapper.fromUnloadRecord(recordAt(prefixed, 1)))
                    .as("the two images are reorderings of one another, not different data")
                    .isEqualTo(sequentialFirst);
            assertThat(PendingAuthSummaryMapper.fromUnloadRecord(recordAt(sequential, 1)))
                    .isEqualTo(prefixedFirst);
        }

        /**
         * Confirms each parent's two counters sum to the number of children the pair encodes.
         *
         * <p>Assumptions: the sequential image's two parents carry counter pairs of two and zero, and of
         * one and one, so the approved count plus the declined count is TWO for each of them. That
         * invariant is what makes the image usable as a cross-check against a count of child records: a
         * load that read the parents and the children of the same extract can compare the two without
         * needing a separate expectation written anywhere.</p>
         *
         * <p>Assumptions: the two pairs are deliberately DIFFERENT while summing to the same total, so the
         * invariant cannot be satisfied by reading one counter twice. A pair of two and zero and a pair of
         * one and one are distinguishable in every individual counter and identical in the sum.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("each parent's approved and declined counts sum to the two children it owns")
        void eachParentCounterPairSumsToTwo() {
            byte[] stream = bytesOf("unload-gsam-summary-100.bin");

            PendingAuthSummaryMapper.SummarySegment first =
                    PendingAuthSummaryMapper.fromUnloadRecord(recordAt(stream, 0));
            PendingAuthSummaryMapper.SummarySegment second =
                    PendingAuthSummaryMapper.fromUnloadRecord(recordAt(stream, 1));

            assertThat(first.approvedAuthCount()).isEqualTo(Short.valueOf((short) 2));
            assertThat(first.declinedAuthCount()).isEqualTo(Short.valueOf((short) 0));
            assertThat(second.approvedAuthCount()).isEqualTo(Short.valueOf((short) 1));
            assertThat(second.declinedAuthCount()).isEqualTo(Short.valueOf((short) 1));
            assertThat(first.approvedAuthCount() + first.declinedAuthCount())
                    .as("the counter pairs differ while both totals are two, so neither can be read"
                            + " twice to satisfy the invariant")
                    .isEqualTo(2)
                    .isEqualTo(second.approvedAuthCount() + second.declinedAuthCount());
        }

        /**
         * Confirms the parent shape and the child shape are not interchangeable in either direction.
         *
         * <p>Assumptions: the two shapes differ by the child's six-byte key prefix, so the child record is
         * 206 bytes where the parent's is 100. Handing a parent record to the child's unload path is
         * refused on the DECLARED LENGTH rather than misread, and that is the property worth asserting: a
         * six-byte misalignment still parses, producing a record whose every field after the prefix is
         * shifted and whose values are all plausible.</p>
         *
         * <p>Assumptions: both directions are asserted. The parent path refuses a 206-byte child record on
         * its own declared length too, so neither mapper can be handed the other's record by accident, and
         * a reader who fixed one direction alone would leave the other open.</p>
         *
         * <p>This test names its exceptions in prose because both checks sit inside lambdas: the child path
         * raises {@link IllegalArgumentException} naming its 206-byte expectation, and the parent path
         * raises {@link FixedWidthCodec.RecordLengthException} naming its 100-byte one. It takes no
         * parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the parent and child unload shapes are not interchangeable in either direction")
        void theParentAndChildShapesAreNotInterchangeable() {
            byte[] parentRecord = recordAt(bytesOf("unload-gsam-summary-100.bin"), 0);
            byte[] childRecord = Arrays.copyOf(bytesOf("unload-prefixed-detail-206.bin"),
                    PendingAuthDetailMapper.unloadRecordLength());

            assertThat(PendingAuthDetailMapper.unloadRecordLength())
                    .as("the child record carries a six-byte parent key ahead of its segment")
                    .isEqualTo(PendingAuthDetailMapper.segmentLength() + 6)
                    .isNotEqualTo(PendingAuthSummaryMapper.unloadRecordLength());

            assertThatThrownBy(() -> PendingAuthDetailMapper.fromUnloadRecord(parentRecord))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(
                            Integer.toString(PendingAuthDetailMapper.unloadRecordLength()))
                    .hasMessageContaining(
                            Integer.toString(PendingAuthSummaryMapper.unloadRecordLength()));
            assertThatThrownBy(() -> PendingAuthSummaryMapper.fromUnloadRecord(childRecord))
                    .isInstanceOf(FixedWidthCodec.RecordLengthException.class)
                    .hasMessageContaining(
                            Integer.toString(PendingAuthSummaryMapper.unloadRecordLength()))
                    .hasMessageContaining(
                            Integer.toString(PendingAuthDetailMapper.unloadRecordLength()));
        }
    }

    /**
     * The purge-flow parent, and the single column its primary key occupies.
     *
     * <p>Purpose. This group asserts the full typed projection of the committed purge parent and the shape
     * of the aggregate's primary key.</p>
     *
     * <p>Assumptions: {@code pautsum0-purge-parent.bin} is one hundred-byte record carrying account
     * 10000000001, customer 451, an authorization status of {@code A}, the five distinct status slots, a
     * credit limit of 5000.00, a cash limit of 1000.00, a credit balance of 450.00, a cash balance of
     * 0.00, an approved count of 2, a declined count of 2, an approved total of 300.00, a declined total of
     * 150.00, and a blank padding interval. Both counters are non-zero on purpose, because a purge acts on
     * a parent that HAS children and the counters are the only evidence of children this record
     * carries.</p>
     *
     * <p>Assumptions: the image is PAIRED with {@code pautdtl1-newyear-pair.bin}, whose two child records
     * belong to this same account, and the pairing exists so the purge job's parent-counter arithmetic can
     * be exercised over a parent and its children together. The arithmetic itself belongs to the sibling
     * service package and is out of scope here: this group asserts only that the parent projects correctly
     * and that the attribution between the two images runs through the parent's key.</p>
     */
    @Nested
    @DisplayName("the purge-flow parent record")
    class PurgeParentProjection {

        /**
         * Confirms the purge parent projects to every value the committed image carries.
         *
         * <p>Assumptions: all sixteen components are asserted rather than the counters alone, because the
         * counters are what a purge reads and the rest is what a purge must leave untouched. A projection
         * checked only on the two counters would pass while dropping a balance the same transaction is
         * meant to preserve.</p>
         *
         * <p>Assumptions: both counters are positive and equal here, which is the state a parent is in
         * before any purge has run. The negative case that a purge produces is asserted in the counter
         * group from the image that carries it, so the two states are held apart rather than conflated in
         * one fixture.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the purge parent projects to its documented state in every component")
        void thePurgeParentProjectsToItsDocumentedState() {
            byte[] image = bytesOf("pautsum0-purge-parent.bin");

            PendingAuthSummaryMapper.SummarySegment decoded =
                    PendingAuthSummaryMapper.toSummarySegment(image);

            assertThat(decoded.accountId()).isEqualTo(FIRST_ACCOUNT);
            assertThat(decoded.customerId()).isEqualTo(FIRST_CUSTOMER);
            assertThat(decoded.authStatus()).isEqualTo(AUTH_STATUS);
            assertThat(slotsOf(decoded)).containsExactlyElementsOf(CANONICAL_SLOTS);
            assertThat(decoded.creditLimit().amount()).isEqualByComparingTo("5000.00");
            assertThat(decoded.cashLimit().amount()).isEqualByComparingTo("1000.00");
            assertThat(decoded.creditBalance().amount()).isEqualByComparingTo("450.00");
            assertThat(decoded.cashBalance().amount()).isEqualByComparingTo("0.00");
            assertThat(decoded.approvedAuthCount())
                    .as("a purge acts on a parent that has children, so both counters are non-zero")
                    .isEqualTo(Short.valueOf((short) 2));
            assertThat(decoded.declinedAuthCount()).isEqualTo(Short.valueOf((short) 2));
            assertThat(decoded.approvedAuthAmount().amount()).isEqualByComparingTo("300.00");
            assertThat(decoded.declinedAuthAmount().amount()).isEqualByComparingTo("150.00");

            PendingAuthSummary stored = PendingAuthSummaryMapper.fromExtractRecord(image);

            assertThat(stored.getApprovedAuthCount()).isEqualTo(Short.valueOf((short) 2));
            assertThat(stored.getDeclinedAuthCount()).isEqualTo(Short.valueOf((short) 2));
            assertThat(stored.getApprovedAuthAmount()).isEqualByComparingTo("300.00");
            assertThat(stored.getDeclinedAuthAmount()).isEqualByComparingTo("150.00");
            assertThat(PendingAuthSummaryMapper.toUnloadRecord(stored))
                    .as("the parent's blank padding and positive signs make it round-trip safe")
                    .isEqualTo(image);
        }

        /**
         * Confirms the aggregate's primary key is the account alone, unlike the child's composite key.
         *
         * <p>Assumptions: the root segment is keyed by ACCOUNT ONLY, matching the single unique sequence
         * field the database description declares over its first six bytes, so no composite key type exists
         * on this side at all. Assuming symmetry with the child would be wrong: the child's key is three
         * components -- the account, the authorization date and the authorization time -- carried in its
         * own embeddable type, and a reader who expected one here would look for a type that does not
         * exist.</p>
         *
         * <p>Assumptions: the two shapes are asserted by their MAPPED forms rather than by their Java
         * accessors, because the claim is about the key each table declares. The aggregate's key column is
         * read from its identifier field's own mapping and the child's three from its key type's, so a
         * change to either table's key fails here.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the summary key is one column where the detail key is three")
        void theSummaryKeyIsOneColumnWhereTheDetailKeyIsThree() {
            List<String> summaryIdColumns = new ArrayList<>();
            for (Field field : PendingAuthSummary.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    summaryIdColumns.add(field.getAnnotation(Column.class).name());
                }
            }
            assertThat(summaryIdColumns)
                    .as("the root segment is keyed by account alone, so there is no composite key type")
                    .containsExactly("account_id");
            assertThat(PendingAuthSummary.class.getDeclaredFields())
                    .as("and no field of the aggregate is an embedded identifier")
                    .noneMatch(field ->
                            field.isAnnotationPresent(jakarta.persistence.EmbeddedId.class));

            List<String> detailKeyColumns = new ArrayList<>();
            for (Field field : com.carddemo.authorization.domain.PendingAuthDetailKey.class
                    .getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column != null) {
                    detailKeyColumns.add(column.name());
                }
            }
            assertThat(detailKeyColumns)
                    .as("the child's key is three components, which is why assuming symmetry is wrong")
                    .containsExactly("account_id", "auth_date", "auth_time")
                    .hasSize(3);
        }

        /**
         * Confirms the parent's key is what attributes the paired child records to this account.
         *
         * <p>Assumptions: the paired child image carries NO account of its own. A child segment stores only
         * its own key components -- the authorization date and time -- so the account that owns it arrives
         * either from an unload record's prefix or from the caller, which is why the child decode takes the
         * account as an argument. The pairing between the two images therefore runs through the parent's
         * decoded key, and that is what this case asserts.</p>
         *
         * <p>Assumptions: the purge arithmetic itself is OUT OF SCOPE here and belongs to the sibling
         * service package, which owns the job that reads a parent, deletes expired children and adjusts the
         * counters in one transaction. What this case establishes is the precondition that job depends on:
         * that the parent's key and the account its children are attributed to are the same value.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the paired child records are attributed to the parent's own account key")
        void thePairedChildRecordsAreAttributedToTheParentKey() {
            Long parentAccount = PendingAuthSummaryMapper
                    .toSummarySegment(bytesOf("pautsum0-purge-parent.bin")).accountId();
            byte[] children = bytesOf("pautdtl1-newyear-pair.bin");
            int childStride = PendingAuthDetailMapper.segmentLength();

            assertThat(parentAccount).isEqualTo(FIRST_ACCOUNT);
            assertThat(children)
                    .as("the paired image holds two bare child segments and no key prefix")
                    .hasSize(2 * childStride);
            for (int index = 0; index < 2; index++) {
                byte[] child = Arrays.copyOfRange(children, index * childStride,
                        (index + 1) * childStride);
                PendingAuthDetail attributed = PendingAuthDetailMapper.toEntity(child, parentAccount);
                assertThat(attributed.getId().getAccountId())
                        .as("child %d takes its account from the parent's key, not from its own bytes",
                                index)
                        .isEqualTo(parentAccount);
            }
        }
    }
}
