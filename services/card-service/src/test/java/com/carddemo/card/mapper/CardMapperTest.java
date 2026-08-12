package com.carddemo.card.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.card.domain.Card;
import com.carddemo.card.domain.EncryptedCvv;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardSummary;
import com.carddemo.card.dto.CardUpdateRequest;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.security.MaskedCardNumber;
import com.carddemo.common.security.SealedSelector;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executable proof of the two disclosure properties {@link CardMapper} owes every caller.
 *
 * <p>Purpose: this class establishes, rather than presumes, that no serialised payload this context
 * publishes carries the card verification value in any form, and that the primary account number is
 * rendered masked to its last four digits on every response except the one administrative disclosure
 * authorised to receive it whole. Both properties are asserted on the JSON a real serialiser produces,
 * across every conversion {@link CardMapper} exposes. The remaining assertions hold the same class to
 * the record layout it bridges: the account identifier and the card number travel as digit strings, the
 * stored day of the month survives an update that cannot carry one, the trailing padding of the
 * baseline record reaches no response, and the two paging boundary tokens cross unchanged.</p>
 *
 * <p>The record under test is {@code 01 CARD-RECORD} at {@code app/cpy/CVACT02Y.cpy:4}, whose banner at
 * {@code :2} declares 150 bytes. Its declarations are {@code CARD-NUM PIC X(16)} at {@code :5},
 * {@code CARD-ACCT-ID PIC 9(11)} at {@code :6}, {@code CARD-CVV-CD PIC 9(03)} at {@code :7},
 * {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code :8}, {@code CARD-EXPIRAION-DATE PIC X(10)} at
 * {@code :9}, {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code :10} and {@code FILLER PIC X(59)} at
 * {@code :11}. Every baseline path named anywhere in this class is reference material: it is read and
 * cited by path and line, never modified, and still running.</p>
 *
 * <p>Assumptions: every assertion below is transcribed from the baseline source cited beside it and
 * from the two sibling contracts of record, and none is compared against recorded output.
 * {@code tests/README.md:83-85} states that the baseline's online programs cannot be exercised end to
 * end without a CICS runtime, which is absent from the runner, so no recorded output exists for any
 * card screen and none is claimed for one. That is why each assertion carries the program, paragraph or
 * declaration it came from: the citation is what makes this class reviewable against the baseline
 * rather than only against itself.</p>
 *
 * <p>Assumptions: where a shape is in question the two sibling artifacts decide it and this class
 * follows them. The published shapes are {@code src/main/resources/openapi/card-api.yaml}, whose five
 * operations are {@code listCards}, {@code lookupCard}, {@code getCard}, {@code updateCard} and
 * {@code getAdminCardDetail}, the last being the only one declaring
 * {@code x-required-authority: carddemo-admin}. The column list is
 * {@code src/main/resources/db/migration/V1__card.sql}, which holds the verification value as
 * {@code cvv_encrypted BYTEA} with no read path at all. Deriving either independently here is the
 * alternative and is what this avoids, since two derivations disagree the first time one is edited and
 * nothing in the build reports it.</p>
 *
 * <p>Alternatives Considered: loading a database through Testcontainers and asserting the same
 * properties over persisted rows. Rejected because {@link CardMapper} holds no repository and issues no
 * query, so every method it exposes is a function of its arguments; a container would add a startup
 * dependency and a failure mode without bringing any behaviour under test that is not reachable from a
 * constructed instance. For the same reason no Spring context is started here.</p>
 *
 * <p>Trade-offs: this class asserts what the mapper renders and deliberately not who is allowed to ask
 * for it. The administrative disclosure is proved to return the number whole, and the authority that
 * guards the route is asserted where the route is declared rather than here, because the mapper is
 * handed a stored row and has no access to the request, the token or the security context.</p>
 */
class CardMapperTest {

    /** Non-secret, self-describing key material for the selector sealer, at least 32 bytes. */
    private static final byte[] SELECTOR_KEY =
            "carddemo-card-mapper-test-selector-key-not-a-secret".getBytes(StandardCharsets.UTF_8);

    /** Non-secret, self-describing key material for the cursor sealer, at least 32 bytes. */
    private static final byte[] CURSOR_KEY =
            "carddemo-card-mapper-test-cursor-key-not-a-secret".getBytes(StandardCharsets.UTF_8);

    /** The classpath directory the card fixtures are published under. */
    private static final String FIXTURE_DIRECTORY = "/fixtures/";

    /** The fixture whose single record is the positive control for both disclosure properties. */
    private static final String POSITIVE_CONTROL = "card-valid-active";

    /** The fixture whose single record carries the second in-domain active status. */
    private static final String INACTIVE_CONTROL = "card-valid-inactive";

    /** The fixture whose four records sit on the inclusive expiry bounds. */
    private static final String EXPIRY_BOUNDS = "card-boundary-expiry-inclusive";

    /** The fixture whose three records carry three distinct stored days of the month. */
    private static final String DAY_PRESERVED = "card-expiry-day-preserved";

    /** The fixture whose single record carries an active status outside the two-value domain. */
    private static final String STATUS_OUT_OF_DOMAIN = "card-schema-reject-status-out-of-domain";

    /** The fixture whose two records carry expiry months outside the calendar-month domain. */
    private static final String MONTH_OUT_OF_RANGE = "card-schema-reject-expiry-month-out-of-range";

    /** Data bytes in one fixture record, per the record banner at {@code app/cpy/CVACT02Y.cpy:2}. */
    private static final int RECORD_LENGTH = 150;

    /** Bytes one fixture record occupies on disk, being the data bytes and one line feed. */
    private static final int RECORD_STRIDE = RECORD_LENGTH + 1;

    /** Half-open end of {@code CARD-NUM}, declared {@code PIC X(16)} at {@code CVACT02Y.cpy:5}. */
    private static final int CARD_NUM_END = 16;

    /** Half-open end of {@code CARD-ACCT-ID}, declared {@code PIC 9(11)} at {@code :6}. */
    private static final int ACCOUNT_ID_END = 27;

    /** Half-open end of {@code CARD-CVV-CD}, declared {@code PIC 9(03)} at {@code :7}. */
    private static final int VERIFICATION_VALUE_END = 30;

    /** Half-open end of {@code CARD-EMBOSSED-NAME}, declared {@code PIC X(50)} at {@code :8}. */
    private static final int EMBOSSED_NAME_END = 80;

    /** Half-open end of {@code CARD-EXPIRAION-DATE}, declared {@code PIC X(10)} at {@code :9}. */
    private static final int EXPIRATION_DATE_END = 90;

    /** Half-open end of {@code CARD-ACTIVE-STATUS}, declared {@code PIC X(01)} at {@code :10}. */
    private static final int ACTIVE_STATUS_END = 91;

    /** Half-open end of the year within the stored date, being {@code (1:4)} at COCRDUPC.cbl:1505. */
    private static final int DATE_YEAR_END = 4;

    /** Half-open start of the month within the stored date, being {@code (6:2)} at {@code :1506}. */
    private static final int DATE_MONTH_START = 5;

    /** Half-open end of the month within the stored date, completing that same interval. */
    private static final int DATE_MONTH_END = 7;

    /** Half-open start of the day within the stored date, being {@code (9:2)} at {@code :1507}. */
    private static final int DATE_DAY_START = 8;

    /** Lowest month {@code 88 VALID-MONTH} admits at {@code app/cbl/COCRDUPC.cbl:95}, inclusive. */
    private static final int LOWEST_MONTH = 1;

    /** Highest month that same condition name admits, likewise inclusive. */
    private static final int HIGHEST_MONTH = 12;

    /** The response member holding the opaque row selector, excluded from the value sweep below. */
    private static final String SELECTOR_PROPERTY = "key";

    /** Lower-cased fragments that identify a member as carrying the verification value. */
    private static final List<String> VERIFICATION_VALUE_NAMES =
            List.of("cvv", "verificationvalue", "verificationcode", "securitycode", "cvc", "cid");

    /** The mapper under test, rebuilt for every case so no case can observe another's state. */
    private CardMapper mapper;

    /** The serialiser every assertion reads, being the generation this deployment publishes with. */
    private ObjectMapper json;

    /** The cursor sealer that mints the two paging boundary tokens the page conversion carries. */
    private CursorToken cursorSealer;

    /**
     * Builds a mapper, a serialiser and a cursor sealer before each case.
     *
     * <p>Assumptions: the sealer is constructed from constant key material rather than random bytes, so
     * that a selector is the same string on every run and a failure reproduces from the reported value.
     * The material is a self-describing sentence and is not a credential: it keys nothing outside this
     * class, and the deployment's own key is generated at provisioning time into a secret store and
     * never appears in source.</p>
     */
    // WHY : Alternatives Considered: a mock or stub selector sealer returning a constant string. Rejected
    //       because the two response records refuse a component that is not a genuinely sealed token --
    //       CardSummary and CardDetail both call SealedSelector.hasSealedShape in their own compact
    //       constructors -- so a stub would either have to reproduce the real token shape, which is the
    //       real implementation by another name, or fail construction before any assertion ran.
    // WHY : Alternatives Considered: the serialiser this deployment publishes with is the one built
    //       here, rather than one assembled from the other Jackson generation also present on the
    //       classpath. The generation this service actually serialises through is the one the shared
    //       auto-configuration contributes its module to, so asserting on any other would prove a wire
    //       format no caller receives.
    @BeforeEach
    void buildMapperAndSerialiser() {
        this.mapper = new CardMapper(new SealedSelector(SELECTOR_KEY));
        this.json = JsonMapper.builder().build();
        this.cursorSealer = new CursorToken(CURSOR_KEY, Duration.ofMinutes(5));
    }

    /**
     * Reads one fixture and returns a stored card for each of its records.
     *
     * <p>Assumptions: the geometry used to slice a record is transcribed from the normative table at
     * {@code src/test/resources/fixtures/README.md:131-138}, which cites
     * {@code app/cpy/CVACT02Y.cpy:5-10} for each row, and is not derived a second time here. The
     * table's intervals are stated one-based and inclusive; the constants above express the same
     * intervals as Java's half-open ends, and because the six fields are contiguous each field begins
     * where the one before it ends, so only the ends are named.</p>
     *
     * @param fixtureName the fixture's base name, without directory or extension, resolved from the
     *     classpath directory the fixtures are published under
     * @return one stored card per fixture record, in the order the records appear in the file, each
     *     carrying its record's verification value so that suppressing it is a real suppression
     * @throws IOException if the fixture cannot be read from the classpath
     * @throws AssertionError if the fixture is absent, or if its size is not a whole number of records,
     *     which would mean every subsequent slice reads the wrong bytes
     */
    private static List<Card> cardsFrom(String fixtureName) throws IOException {
        List<String> records = recordsFrom(fixtureName);
        List<Card> cards = new ArrayList<>(records.size());
        for (String record : records) {
            cards.add(cardFrom(record));
        }
        return cards;
    }

    /**
     * Reads one fixture and returns its records as character strings of the declared width.
     *
     * @param fixtureName the fixture's base name, without directory or extension
     * @return the fixture's records, each exactly {@link #RECORD_LENGTH} characters and stripped of its
     *     line feed, in file order
     * @throws IOException if the fixture cannot be read from the classpath
     * @throws AssertionError if the fixture is absent from the classpath, if its size is not a whole
     *     multiple of the record stride, or if any record is not the declared width
     */
    private static List<String> recordsFrom(String fixtureName) throws IOException {
        String resource = FIXTURE_DIRECTORY + fixtureName + ".txt";
        byte[] raw;
        try (InputStream stream = CardMapperTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("fixture %s is published on the test classpath", resource).isNotNull();
            raw = stream.readAllBytes();
        }

        // WHY : Assumptions: the size invariant is asserted before any slice is taken, because it is
        //       the invariant the fixture register states -- README.md:202 requires every file to
        //       measure exactly the stride times its record count, with line feeds and no carriage
        //       returns. A fixture that drifted by one byte would still slice without error and would
        //       silently move every field one position, so checking the whole before reading the parts
        //       is what turns a wrong answer into a reported failure.
        assertThat(raw.length)
                .as("fixture %s measures a whole number of %d-byte records", resource, RECORD_STRIDE)
                .isGreaterThan(0)
                .isEqualTo(RECORD_STRIDE * (raw.length / RECORD_STRIDE));

        String text = new String(raw, StandardCharsets.US_ASCII);
        List<String> records = new ArrayList<>(raw.length / RECORD_STRIDE);
        for (int start = 0; start < raw.length; start += RECORD_STRIDE) {
            String record = text.substring(start, start + RECORD_LENGTH);
            assertThat(text.charAt(start + RECORD_LENGTH))
                    .as("record beginning at %d in %s ends with a line feed", start, resource)
                    .isEqualTo('\n');
            records.add(record);
        }
        return records;
    }

    /**
     * Builds one stored card from one fixture record.
     *
     * @param record one fixture record, exactly {@link #RECORD_LENGTH} characters wide
     * @return a stored card carrying the record's card number, account identifier, verification value,
     *     embossed name, expiration date and active status
     * @throws AssertionError if the record is not the declared width
     */
    private static Card cardFrom(String record) {
        assertThat(record).as("a fixture record is the declared width").hasSize(RECORD_LENGTH);

        // WHY : Assumptions: the trailing 59 bytes are read by nothing here. FILLER at
        //       app/cpy/CVACT02Y.cpy:11 pads CARD-RECORD out to the constant length the indexed file
        //       requires; the field names nothing, no program reads it, and a JSON body has no constant
        //       length for it to pad out. Dropping it is therefore a reading of what those bytes are
        //       rather than a loss of data, and the suppression case below asserts that no response
        //       carries a member standing in for them.
        // WHY : Assumptions: the embossed name is right-trimmed because the 50 positions of
        //       CARD-EMBOSSED-NAME PIC X(50) at app/cpy/CVACT02Y.cpy:8 are a declared field width, so
        //       the blanks past the name are that width being filled and not part of the
        //       cardholder's name. The same reasoning does not reach the two identifiers, whose leading
        //       zeros ARE data and are preserved untouched.
        return new Card(
                record.substring(0, CARD_NUM_END),
                Long.parseLong(record.substring(CARD_NUM_END, ACCOUNT_ID_END)),
                envelopeCarrying(verificationValueOf(record)),
                record.substring(VERIFICATION_VALUE_END, EMBOSSED_NAME_END).stripTrailing(),
                LocalDate.parse(record.substring(EMBOSSED_NAME_END, EXPIRATION_DATE_END)),
                record.substring(EXPIRATION_DATE_END, ACTIVE_STATUS_END));
    }

    /**
     * Extracts the three verification-value digits one fixture record stores.
     *
     * @param record one fixture record, exactly {@link #RECORD_LENGTH} characters wide
     * @return the three digit characters {@code CARD-CVV-CD} occupies at
     *     {@code app/cpy/CVACT02Y.cpy:7}, being the value no response may carry
     */
    private static String verificationValueOf(String record) {
        return record.substring(ACCOUNT_ID_END, VERIFICATION_VALUE_END);
    }

    /**
     * Frames a stored verification value into an envelope of the shape the entity's column accepts.
     *
     * <p>Assumptions: the envelope's ciphertext is built from the digits themselves rather than from
     * arbitrary bytes. The reason is that this makes the value genuinely present on the instance under
     * test, so a conversion that copied the column through in any encoding would put those digits
     * where a sweep could find them. Filling the envelope with unrelated bytes would leave the
     * suppression cases passing for the wrong reason, since there would be nothing to leak.</p>
     *
     * @param verificationValue the three stored digits to frame
     * @return an envelope of the shape {@code cvv_encrypted BYTEA} holds, carrying those digits
     * @throws IllegalArgumentException if the framed parts are not the widths the envelope declares,
     *     which the constant sizes below cannot produce
     */
    private static EncryptedCvv envelopeCarrying(String verificationValue) {
        byte[] digits = verificationValue.getBytes(StandardCharsets.US_ASCII);
        byte[] encipheredDataKey = new byte[EncryptedCvv.MAGIC_LENGTH];
        byte[] initialisationVector = new byte[EncryptedCvv.INITIALISATION_VECTOR_LENGTH];
        byte[] ciphertext = new byte[EncryptedCvv.MIN_CIPHERTEXT_LENGTH];
        for (int position = 0; position < ciphertext.length; position++) {
            ciphertext[position] = digits[position % digits.length];
        }
        return EncryptedCvv.wrap(encipheredDataKey, initialisationVector, ciphertext);
    }

    /**
     * Sweeps a serialised body for any trace of a stored verification value.
     *
     * <p>Assumptions: two independent traces are looked for, because either alone would let the other
     * through. A member whose name identifies the value discloses that the value exists, how wide it is
     * and which member to ask for, even if what it holds is masked; and a member with an innocuous name
     * discloses the value itself if it holds the digits. So the sweep reports a name it recognises
     * wherever it appears in the tree, and separately reports any value carrying the digits.</p>
     *
     * <p>Trade-offs: the sweep does not read the opaque row selector, and that exclusion is the one
     * compromise here. The selector is authenticated ciphertext of the card number over the deployment
     * key, so its base64 alphabet can contain any three-digit run by coincidence and a match inside it
     * would report a disclosure that had not occurred. What stands in for reading it is the stronger
     * assertion made separately below, that a serialised body is identical whether the stored
     * verification value is present or absent; that holds for every character of the body, selector
     * included, and it is a property no coincidence can satisfy.</p>
     *
     * @param body the parsed body to sweep, of any node kind, including a nested page envelope
     * @param verificationValue the three stored digits that must appear in no value
     * @return one finding per trace, naming the member or the path it was found at, and empty when the
     *     body carries no trace of the value
     */
    private static List<String> verificationValueLeaks(JsonNode body, String verificationValue) {
        List<String> findings = new ArrayList<>();
        collectLeaks(body, "$", verificationValue, findings);
        return findings;
    }

    /**
     * Walks one node of a parsed body, appending any trace of the verification value it finds.
     *
     * @param node the node to examine, which may be an object, an array or a single value
     * @param path the JSON path this node was reached by, reproduced in any finding so a failure names
     *     the member rather than only the fact of a match
     * @param verificationValue the three stored digits that must appear in no value
     * @param findings the accumulator every trace is appended to, mutated in place
     */
    private static void collectLeaks(
            JsonNode node, String path, String verificationValue, List<String> findings) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                String member = property.getKey();
                String lowered = member.toLowerCase(Locale.ROOT);
                // WHY : Assumptions: the comparison is made on a lower-cased name against a list of
                //       fragments rather than against exact member names, because a member spelled in
                //       any casing or compounded with a prefix or a suffix would disclose exactly as
                //       much as the bare name does. Matching the bare name alone would be satisfied by
                //       renaming rather than by suppressing.
                if (VERIFICATION_VALUE_NAMES.stream().anyMatch(lowered::contains)) {
                    findings.add("member " + path + "." + member);
                }
                collectLeaks(property.getValue(), path + "." + member, verificationValue, findings);
            }
            return;
        }
        if (node.isArray()) {
            int index = 0;
            for (JsonNode element : node.values()) {
                collectLeaks(element, path + "[" + index + "]", verificationValue, findings);
                index++;
            }
            return;
        }
        if (node.isValueNode()
                && !path.endsWith("." + SELECTOR_PROPERTY)
                && node.asString().contains(verificationValue)) {
            findings.add("value at " + path);
        }
    }

    /**
     * Seals a raw keyset position into the opaque boundary token a page envelope requires.
     *
     * <p>Assumptions: the position sealed is the card number followed by the account identifier, which
     * is the order the baseline's own browse cursor holds them in. {@code app/cbl/COCRDLIC.cbl:230}
     * declares the group {@code WS-CA-LAST-CARDKEY}, whose parts are
     * {@code WS-CA-LAST-CARD-NUM PIC X(16)} at {@code :231} and
     * {@code WS-CA-LAST-CARD-ACCT-ID PIC 9(11)} at {@code :232}, so the position is twenty-seven
     * characters in that sequence. It is deliberately not the twenty-eight-character display row at
     * {@code :258-260}, which holds the account number first, the card number second and the active
     * status as its twenty-eighth character; that row is what the screen painted, and reading it as a
     * position would page in a different sequence from the one the browse actually used.</p>
     *
     * @param keysetPosition the raw position to seal, being the card number then the account identifier
     * @return the sealed boundary token, which is the only form the page envelope accepts
     */
    private String sealedCursor(String keysetPosition) {
        return cursorSealer.seal(
                CursorToken.binding("listCards", "card-mapper-test-caller", CursorToken.SCOPE_NONE),
                keysetPosition);
    }

    /**
     * A deliberately verification-value-bearing shape, existing only to prove the sweep can fail.
     *
     * @param key the opaque row selector, carried so the shape resembles a real response row and so
     *     that the sweep's exclusion of that member is exercised rather than assumed
     * @param displayCardNumber the masked rendering, carried for the same reason
     * @param cvv the stored verification value, held in a member named after it so that both the name
     *     trace and the value trace have something to find
     */
    record LeakyRow(String key, String displayCardNumber, String cvv) {
    }

    /**
     * Confirms the sweep reports a verification value when it is given one to find.
     *
     * <p>Purpose: this is the negative control for every suppression case below, and without it those
     * cases could pass while proving nothing.</p>
     *
     * @throws IOException if the positive-control fixture cannot be read from the classpath
     */
    // WHY : Assumptions: a suppression assertion that cannot fail is not evidence of suppression. If
    //       the sweep searched for a member name no shape ever uses, or for digits it never compares,
    //       every case below would report a clean body over a body that carried the value in plain
    //       sight. The control is deliberately serialised through the same serialiser the real bodies
    //       go through, so it exercises the same node kinds and the same member-naming behaviour, and
    //       it asserts BOTH traces separately because a control that fired on only one half would
    //       leave the other half unproven.
    @Test
    @DisplayName("the sweep reports a verification value when it is given one to find")
    void theSweepReportsAVerificationValueWhenGivenOne() throws IOException {
        Card control = cardsFrom(POSITIVE_CONTROL).get(0);
        String storedValue = verificationValueOf(recordsFrom(POSITIVE_CONTROL).get(0));
        CardSummary honest = this.mapper.toSummary(control);
        LeakyRow leaky = new LeakyRow(honest.key(), honest.displayCardNumber(), storedValue);

        List<String> findings =
                verificationValueLeaks(this.json.readTree(this.json.writeValueAsString(leaky)),
                        storedValue);

        assertThat(findings)
                .as("both traces fire on a body that carries the value under its own name")
                .contains("member $.cvv", "value at $.cvv");
    }

    /**
     * Confirms no conversion this mapper exposes serialises the verification value.
     *
     * <p>Purpose: this is the primary assertion of this class. It covers every conversion in turn,
     * being the listing row, the masked detail body, the administrative disclosure composed onto that
     * body, the detail body of a card an update has just been applied to, and a page of listing
     * rows.</p>
     *
     * @throws IOException if the positive-control fixture cannot be read from the classpath
     */
    // WHY : Alternatives Considered: asserting on the accessors of each returned record instead of on
    //       the JSON. Rejected because an accessor sweep sees only the members this class thought to
    //       ask for, so a member added later, or a serialisation annotation that renamed or synthesised
    //       one, would pass unremarked. The serialised body is what a caller actually receives, so it
    //       is the only artifact on which absence can be asserted rather than inferred.
    // WHY : Refactoring Rationale: the value being absent rather than masked PRESERVES an absence the
    //       baseline already had. No presentation artifact of the three card programs names it: a
    //       case-insensitive search finds nothing in app/cpy-bms/COCRDSL.CPY, COCRDLI.CPY or
    //       COCRDUP.CPY, nor in app/bms/COCRDSL.bms, COCRDLI.bms or COCRDUP.bms. It is never accepted
    //       as input either -- CCUP-NEW-CVV-CD at app/cbl/COCRDUPC.cbl:306 is cleared by INITIALIZE at
    //       :586 and then read as a MOVE source at :1464, and those two lines are the only places the
    //       name appears at all, so no statement ever moves a value into it. What the target adds is
    //       encryption of the stored column and nothing to the wire.
    // WHY : Trade-offs: absence is asserted rather than a masked rendering, and the alternative was
    //       genuinely available. It is rejected on a specific ground: a masked member still publishes
    //       that the value exists, how wide it is and which member to ask for. CARD-CVV-CD is declared
    //       PIC 9(03) at app/cpy/CVACT02Y.cpy:7, so its entire domain is a thousand possibilities and
    //       the width alone is most of what there is to know. An absent member publishes none of the
    //       three.
    @Test
    @DisplayName("no conversion serialises the verification value")
    void noConversionSerialisesTheVerificationValue() throws IOException {
        String record = recordsFrom(POSITIVE_CONTROL).get(0);
        String storedValue = verificationValueOf(record);
        Card stored = cardFrom(record);
        CardDetail detail = this.mapper.toDetail(stored);
        String position = stored.getCardNum() + detail.accountId();

        // WHY : Assumptions: the update is applied to a SEPARATE instance read from the same record,
        //       because applyUpdate writes onto the row it is handed and returns that same instance
        //       rather than a copy. Sharing one instance would leave the earlier bodies in this list
        //       built from pre-update state and the later ones from post-update state, which still
        //       proves the suppression but makes the case impossible to read.
        Card updated = this.mapper.applyUpdate(
                new CardUpdateRequest(stored.getEmbossedName(), "N", "09", "2031",
                        detail.version()),
                cardFrom(record));

        List<String> bodies = List.of(
                this.json.writeValueAsString(this.mapper.toSummary(stored)),
                this.json.writeValueAsString(detail),
                this.json.writeValueAsString(
                        this.mapper.discloseCardNumberToAdministrator(stored)),
                this.json.writeValueAsString(this.mapper.toDetail(updated)),
                this.json.writeValueAsString(this.mapper.toSummaryPage(PageResponse.ofRows(
                        List.of(stored), sealedCursor(position), sealedCursor(position), true))));

        for (String body : bodies) {
            assertThat(verificationValueLeaks(this.json.readTree(body), storedValue))
                    .as("body carries no trace of the stored verification value: %s", body)
                    .isEmpty();
        }
    }

    /**
     * Confirms every serialised body is identical whether the stored verification value is present.
     *
     * <p>Purpose: this closes the one gap the sweep above leaves, which is that the sweep cannot read
     * the opaque row selector. Comparing two bodies built from rows that differ only in that column
     * covers every character of both, the selector included.</p>
     *
     * @throws IOException if the positive-control fixture cannot be read from the classpath
     */
    // WHY : Alternatives Considered: extending the sweep to decode the selector and inspect its
    //       plaintext. Rejected because it would put the deployment key and the sealing purpose into an
    //       assertion, so the test would then depend on how the selector is constructed and would
    //       break on a change that disclosed nothing. This comparison depends on no internal detail: if
    //       a body is byte for byte the same when the column is populated and when it is absent, then
    //       no part of that body is a function of the column, which is a stronger statement than any
    //       search for a particular value can make.
    @Test
    @DisplayName("every serialised body is independent of the stored verification value")
    void everySerialisedBodyIsIndependentOfTheStoredVerificationValue() throws IOException {
        String record = recordsFrom(POSITIVE_CONTROL).get(0);
        Card carrying = cardFrom(record);
        Card withoutValue = new Card(
                carrying.getCardNum(),
                carrying.getAccountId(),
                null,
                carrying.getEmbossedName(),
                carrying.getExpirationDate(),
                carrying.getActiveStatus());

        assertThat(this.json.writeValueAsString(this.mapper.toSummary(carrying)))
                .as("the listing row does not vary with the stored verification value")
                .isEqualTo(this.json.writeValueAsString(this.mapper.toSummary(withoutValue)));
        assertThat(this.json.writeValueAsString(this.mapper.toDetail(carrying)))
                .as("the detail body does not vary with the stored verification value")
                .isEqualTo(this.json.writeValueAsString(this.mapper.toDetail(withoutValue)));
        assertThat(this.mapper.discloseCardNumberToAdministrator(carrying))
                .as("the administrative disclosure does not vary with the stored verification value")
                .isEqualTo(this.mapper.discloseCardNumberToAdministrator(withoutValue));
    }

    /**
     * Confirms neither a refusal nor a diagnostic rendering quotes the value or the card number.
     *
     * <p>Purpose: masking reaches a response body and reaches neither an error message nor a log line,
     * so these are the two destinations at which a value withheld everywhere else can still escape. A
     * refusal that echoed what it rejected, or a rendering that printed what it holds, would put in a
     * diagnostic exactly what every body above withholds.</p>
     *
     * @throws IOException if the positive-control fixture cannot be read from the classpath
     */
    // WHY : Assumptions: the value most likely to be offered as a selector by mistake is the card
    //       number itself, so the refusal is swept for that as well as for the verification value. The
    //       refusal is also asserted to carry the stable code and the contract's own field name,
    //       because once the offending value is withheld those are what an operator correlates on.
    // WHY : Assumptions: the diagnostic renderings are asserted here rather than left to the response
    //       sweep, because a record's rendering is reached by a log statement and not by a serialiser,
    //       so no assertion on a serialised body covers it. Both are checked to withhold the card
    //       number as well, since a log store keeps the whole line and a full number in one is as
    //       durable as one in a body.
    @Test
    @DisplayName("neither a refusal nor a diagnostic rendering quotes the value or the card number")
    void neitherARefusalNorADiagnosticRenderingQuotesTheValueOrTheCardNumber() throws IOException {
        String record = recordsFrom(POSITIVE_CONTROL).get(0);
        String cardNumber = record.substring(0, CARD_NUM_END);
        String storedValue = verificationValueOf(record);
        Card stored = cardFrom(record);

        assertThatThrownBy(() -> this.mapper.openCardSelector(cardNumber))
                .isInstanceOf(ClientInputException.class)
                .satisfies(refused -> {
                    ClientInputException refusal = (ClientInputException) refused;
                    assertThat(refusal.code()).isEqualTo(CardMapper.SELECTOR_REFUSAL_CODE);
                    assertThat(refusal.field()).isEqualTo(CardMapper.SELECTOR_FIELD);
                    assertThat(refusal.getMessage()).doesNotContain(cardNumber, storedValue);
                });

        assertThat(this.mapper.toSummary(stored).toString())
                .doesNotContain(cardNumber, storedValue);
        assertThat(this.mapper.toDetail(stored).toString())
                .doesNotContain(cardNumber, storedValue);
    }

    /**
     * Confirms the card number is masked to its last four digits on every non-administrative body.
     *
     * <p>Purpose: the listing row and the detail body are the two shapes a caller holding only the
     * ordinary authority receives, and neither may carry the number whole.</p>
     *
     * @throws IOException if the positive-control fixture cannot be read from the classpath
     */
    // WHY : Assumptions: the masked rendering is asserted to be as WIDE as the value it hides, not
    //       merely to be shorter than it. CardNumberMasker replaces the leading positions rather than
    //       removing them, and a client renders a listing in columns, so a rendering that changed width
    //       would change the column a caller reads even though it disclosed nothing extra.
    // WHY : Assumptions: this is an ADDITION to what the storage platform offered rather than a repair
    //       of it. All eighteen transaction stanzas of app/csd/CARDDEMO.CSD are defined CONFDATA(NO),
    //       three of them at :353, :363 and :374, each with DUMP(YES) TRACE(YES) on the line above, so
    //       that platform did not suppress confidential data in a dump or a trace and the programs
    //       written against it had no facility to ask it to. Masking at this boundary is a capability
    //       the target has and that platform did not.
    @Test
    @DisplayName("the card number is masked to its last four digits on every non-administrative body")
    void theCardNumberIsMaskedOnEveryNonAdministrativeBody() throws IOException {
        String record = recordsFrom(POSITIVE_CONTROL).get(0);
        String cardNumber = record.substring(0, CARD_NUM_END);
        Card stored = cardFrom(record);

        String maskedOnRow = this.mapper.toSummary(stored).displayCardNumber();
        String maskedOnDetail = this.mapper.toDetail(stored).displayCardNumber();

        for (String masked : List.of(maskedOnRow, maskedOnDetail)) {
            assertThat(MaskedCardNumber.isMasked(masked)).isTrue();
            assertThat(masked)
                    .hasSize(MaskedCardNumber.MASKED_LENGTH)
                    .hasSameSizeAs(cardNumber)
                    .endsWith(cardNumber.substring(cardNumber.length()
                            - CardNumberMasker.VISIBLE_TAIL_LENGTH))
                    .startsWith(String.valueOf(CardNumberMasker.MASK_CHARACTER)
                            .repeat(MaskedCardNumber.MASK_PREFIX_LENGTH));
        }
        assertThat(this.json.writeValueAsString(this.mapper.toSummary(stored)))
                .doesNotContain(cardNumber);
        assertThat(this.json.writeValueAsString(this.mapper.toDetail(stored)))
                .doesNotContain(cardNumber);
    }

    /**
     * Confirms the administrative disclosure returns the card number whole.
     *
     * <p>Purpose: the exception to masking has to be proved as well as the rule, because a suppression
     * that also suppressed the authorised route would be a defect rather than a control.</p>
     *
     * @throws IOException if the positive-control fixture cannot be read from the classpath
     */
    // WHY : Assumptions: the unmasked number is reached through a separate named method rather than
    //       through a flag on the masked conversion, and the assertion is written against that shape
    //       deliberately. A flag would put the authorised and the unauthorised outcome one boolean
    //       apart at every call site; two differently named calls cannot be confused for one another.
    //       Which callers may make this call is settled by x-required-authority: carddemo-admin on the
    //       getAdminCardDetail operation of card-api.yaml, and is asserted where that route is
    //       declared, because this class is handed a stored row and can see no request.
    @Test
    @DisplayName("the administrative disclosure returns the card number whole")
    void theAdministrativeDisclosureReturnsTheCardNumberWhole() throws IOException {
        String record = recordsFrom(POSITIVE_CONTROL).get(0);
        String cardNumber = record.substring(0, CARD_NUM_END);

        String disclosed = this.mapper.discloseCardNumberToAdministrator(cardFrom(record));

        assertThat(disclosed)
                .isEqualTo(cardNumber)
                .hasSize(CARD_NUM_END)
                .containsOnlyDigits();
        assertThat(this.json.writeValueAsString(disclosed))
                .as("the disclosed number travels as a JSON string and never as a JSON number")
                .isEqualTo('"' + cardNumber + '"');
    }

    /**
     * Confirms the detail body publishes its expiration date under the target spelling.
     *
     * <p>Purpose: the member name is part of the published contract, so it is asserted rather than
     * assumed to follow from the field it was read out of.</p>
     *
     * @throws IOException if the positive-control fixture cannot be read from the classpath
     */
    // WHY : Refactoring Rationale: the baseline field at app/cpy/CVACT02Y.cpy:9 spells the word with a
    //       missing letter, and the target member and column are spelled expirationDate and
    //       expiration_date. This is a TARGET-SIDE NAMING DECISION and nothing more: the baseline tree
    //       is reference material that keeps its own spelling, its snapshot group at
    //       app/cbl/COCRDUPC.cbl:297 carries the same one, and the pairing is registered in
    //       docs/architecture/data-model-and-schema-mapping.md so the lineage reads from either side.
    //       The assertion pins both halves -- the target spelling is emitted, and the baseline spelling
    //       is emitted nowhere -- because the value of a rename is entirely in the second half.
    @Test
    @DisplayName("the detail body publishes its expiration date under the target spelling")
    void theDetailBodyPublishesItsExpirationDateUnderTheTargetSpelling() throws IOException {
        String record = recordsFrom(POSITIVE_CONTROL).get(0);
        JsonNode body = this.json.readTree(
                this.json.writeValueAsString(this.mapper.toDetail(cardFrom(record))));

        assertThat(body.propertyNames()).contains("expirationDate");
        assertThat(body.get("expirationDate").asString())
                .isEqualTo(record.substring(EMBOSSED_NAME_END, EXPIRATION_DATE_END));
        assertThat(body.propertyNames())
                .as("the baseline spelling reaches no member of the published body")
                .noneMatch(member -> member.toLowerCase(Locale.ROOT).contains("expiraion"));
    }

    /**
     * Confirms no serialised body carries the record's trailing padding under any name.
     *
     * <p>Purpose: the published member set is asserted exactly, so that a member standing in for the
     * padding, or any other member added on one side of the contract only, fails here rather than
     * serialising unremarked.</p>
     *
     * @throws IOException if the positive-control fixture cannot be read from the classpath
     */
    // WHY : Assumptions: the 59-byte FILLER at app/cpy/CVACT02Y.cpy:11 pads CARD-RECORD out to the
    //       constant length the indexed file requires, the field names nothing and no program reads it,
    //       and a JSON body has no constant length for it to pad out. So the assumption those bytes are
    //       a storage artefact rather than data is what licenses dropping them, and asserting the member
    //       set as a CLOSED set is what holds the drop in place -- checking only that no member is
    //       called filler would be satisfied by any other name for the same bytes.
    // WHY : Assumptions: the embossed name is checked to carry no trailing blank for the same reason at
    //       a smaller scale. Its 50 positions at :8 are a declared width, so the blanks past the name
    //       are that width being filled rather than part of the cardholder's name.
    // WHY : Assumptions: the listing row is expected to carry three baseline values and not the whole
    //       record, and the baseline settles that count rather than judgement settling it. The list
    //       screen's body is a twenty-eight-character group repeated seven times, held as
    //       WS-ALL-ROWS PIC X(196) at app/cbl/COCRDLIC.cbl:253 with OCCURS 7 TIMES at :255 and the
    //       program's own arithmetic stated in its comment at :250. That group's three data fields are
    //       WS-ROW-ACCTNO PIC X(11) at :258, WS-ROW-CARD-NUM PIC X(16) at :259 and
    //       WS-ROW-CARD-STATUS PIC X(1) at :260 -- eleven plus sixteen plus one, leaving nothing over,
    //       so there is no fourth data field to carry. The embossed name and the expiry are absent
    //       because that screen displayed neither. The fourth member asserted here carries no baseline
    //       field at all: it is the opaque selector by which a stateless client addresses the row, which
    //       the baseline had no need of because an operator marked a row and transmitted the whole
    //       screen, leaving the program already holding every displayed card number in its own storage.
    @Test
    @DisplayName("no serialised body carries the record's trailing padding")
    void noSerialisedBodyCarriesTheRecordsTrailingPadding() throws IOException {
        Card stored = cardsFrom(POSITIVE_CONTROL).get(0);
        String position = stored.getCardNum() + this.mapper.toDetail(stored).accountId();

        JsonNode row = this.json.readTree(
                this.json.writeValueAsString(this.mapper.toSummary(stored)));
        JsonNode detail = this.json.readTree(
                this.json.writeValueAsString(this.mapper.toDetail(stored)));
        JsonNode page = this.json.readTree(this.json.writeValueAsString(
                this.mapper.toSummaryPage(PageResponse.ofRows(
                        List.of(stored), sealedCursor(position), sealedCursor(position), false))));

        assertThat(row.propertyNames())
                .containsExactlyInAnyOrder("key", "displayCardNumber", "accountId", "activeStatus");
        assertThat(detail.propertyNames())
                .containsExactlyInAnyOrder("key", "displayCardNumber", "accountId", "embossedName",
                        "expirationDate", "activeStatus", "version");
        assertThat(page.propertyNames())
                .containsExactlyInAnyOrder(
                        "items", "firstKey", "lastKey", "hasNext");
        assertThat(detail.get("embossedName").asString())
                .isEqualTo(detail.get("embossedName").asString().stripTrailing())
                .isNotBlank();
    }

    /**
     * Confirms both identifiers travel as digit strings with their leading zeros intact.
     *
     * <p>Purpose: the account identifier of the positive control is stored with eight leading zeros, so
     * it is the case that distinguishes a character rendering from a numeric one.</p>
     *
     * @throws IOException if the positive-control fixture cannot be read from the classpath
     */
    // WHY : Assumptions: identifiers are characters on the wire and numbers only in arithmetic, which
    //       is how the baseline itself holds them. app/cpy/CVCRD01Y.cpy declares CC-ACCT-ID as
    //       PIC X(11) at :34 and overlays CC-ACCT-ID-N as PIC 9(11) on the same bytes by REDEFINES at
    //       :36; it does the same for the card number, PIC X(16) at :37 overlaid by PIC 9(16) at :39,
    //       and again for the customer identifier at :40 and :42. Two consequences follow and either
    //       alone settles it: a leading zero is data here and every numeric type discards it, and a
    //       sixteen-digit card number exceeds the largest integer an IEEE-754 binary64 value holds
    //       exactly, so a client parsing such a member as a JSON number would read back a different
    //       card from the one it was sent.
    @Test
    @DisplayName("both identifiers travel as digit strings with leading zeros intact")
    void bothIdentifiersTravelAsDigitStringsWithLeadingZerosIntact() throws IOException {
        String record = recordsFrom(POSITIVE_CONTROL).get(0);
        String storedAccountId = record.substring(CARD_NUM_END, ACCOUNT_ID_END);
        JsonNode detail = this.json.readTree(
                this.json.writeValueAsString(this.mapper.toDetail(cardFrom(record))));

        assertThat(detail.get("accountId").isString())
                .as("the account identifier is a JSON string, never a JSON number")
                .isTrue();
        assertThat(detail.get("accountId").asString())
                .isEqualTo(storedAccountId)
                .startsWith("0")
                .hasSize(ACCOUNT_ID_END - CARD_NUM_END)
                .containsOnlyDigits();
        assertThat(detail.get("displayCardNumber").isString()).isTrue();
        assertThat(detail.get("version").isNumber())
                .as("the concurrency counter is the one whole-number member, identifying nothing")
                .isTrue();
    }

    /**
     * Confirms an update preserves the day of the month already stored on the row.
     *
     * <p>Purpose: the update shape carries a month and a year and no day, so the day has to come from
     * the stored row; the fixture used here holds three different stored days so that a defaulted day
     * cannot pass by coinciding with one of them.</p>
     *
     * @throws IOException if the day-preservation fixture cannot be read from the classpath
     */
    // WHY : Assumptions: the day is taken from stored state and never from a caller, and the baseline
    //       is the reason rather than taste. It snapshots the stored date as three parts, a year at
    //       app/cbl/COCRDUPC.cbl:298, a month at :299 and a day at :300, and it concatenates all three
    //       back into the record at :1467-1474, which is what fixes the stored form as a
    //       hyphen-separated year, month and day. Its before-image comparison confirms those positions
    //       independently by reference modification at :1503-1508, reading (1:4), (6:2) and (9:2) and
    //       stepping over the two hyphens. Yet the day is never validated -- 1200-EDIT-MAP-INPUTS at
    //       :641 performs six edits, at :647, :650, :698, :701, :704 and :707, and a search for a
    //       1270-EDIT paragraph returns no match at all -- and it is never typed either, the field
    //       being ATTRB=(DRK,FSET,PROT) at app/bms/COCRDUP.bms:142 where the month at :127 and the year
    //       at :135 are both ATTRB=(UNPROT) with HILIGHT=UNDERLINE. So the target carries no day
    //       component and preserves the stored one; the divergence is registered in
    //       docs/architecture/cobol-to-service-traceability.md.
    // WHY : Assumptions: the day is not read from the wall clock either, which is what this case would
    //       catch. A clock-derived day would make the same request store a different row depending on
    //       when it was replayed.
    @Test
    @DisplayName("an update preserves the day of the month already stored on the row")
    void anUpdatePreservesTheDayOfTheMonthAlreadyStored() throws IOException {
        List<String> records = recordsFrom(DAY_PRESERVED);
        assertThat(records).hasSizeGreaterThan(1);

        for (String record : records) {
            String storedDate = record.substring(EMBOSSED_NAME_END, EXPIRATION_DATE_END);
            String storedDay = storedDate.substring(DATE_DAY_START);
            Card stored = cardFrom(record);

            CardDetail updated = this.mapper.toDetail(this.mapper.applyUpdate(
                    new CardUpdateRequest(stored.getEmbossedName(), stored.getActiveStatus(),
                            "09", "2030", stored.getVersion()),
                    stored));

            assertThat(updated.expirationDate())
                    .as("the stored day survives an update that carries a month and a year only")
                    .isEqualTo("2030-09-" + storedDay)
                    .endsWith(storedDay);
        }
    }

    /**
     * Confirms the expiry rendering round trips on each of its four inclusive bounds.
     *
     * <p>Purpose: the fixture used here sits on the lowest and highest month and the lowest and highest
     * year the baseline admits, so it is the case that would expose an exclusive comparison or a
     * two-digit year rendering.</p>
     *
     * @throws IOException if the boundary fixture cannot be read from the classpath
     */
    // WHY : Assumptions: the four bounds are the ones the baseline's own condition names state.
    //       app/cbl/COCRDUPC.cbl:95 declares 88 VALID-MONTH VALUES 1 THRU 12 and :99 declares
    //       88 VALID-YEAR VALUES 1950 THRU 2099, and THRU is inclusive at both ends, so a value ON a
    //       bound is valid and must render rather than be refused or clamped. Enforcing those two
    //       domains is not this class's subject -- the mapper converts and does not validate -- so what
    //       is asserted here is only that the conversion is faithful at the extremes.
    @Test
    @DisplayName("the expiry rendering round trips on each of its four inclusive bounds")
    void theExpiryRenderingRoundTripsOnEachInclusiveBound() throws IOException {
        List<String> records = recordsFrom(EXPIRY_BOUNDS);
        List<String> rendered = new ArrayList<>(records.size());

        for (String record : records) {
            String storedDate = record.substring(EMBOSSED_NAME_END, EXPIRATION_DATE_END);
            Card stored = cardFrom(record);

            assertThat(this.mapper.toDetail(stored).expirationDate())
                    .as("the stored date is published whole and unaltered")
                    .isEqualTo(storedDate)
                    .hasSize(EXPIRATION_DATE_END - EMBOSSED_NAME_END);

            CardDetail reapplied = this.mapper.toDetail(this.mapper.applyUpdate(
                    new CardUpdateRequest(stored.getEmbossedName(), stored.getActiveStatus(),
                            storedDate.substring(DATE_MONTH_START, DATE_MONTH_END),
                            storedDate.substring(0, DATE_YEAR_END), stored.getVersion()),
                    stored));
            assertThat(reapplied.expirationDate())
                    .as("resubmitting the stored month and year reproduces the stored date exactly")
                    .isEqualTo(storedDate);
            rendered.add(reapplied.expirationDate());
        }

        assertThat(rendered)
                .as("the four inclusive bounds are all present in the fixture that drives this case")
                .anyMatch(date -> date.startsWith("1950"))
                .anyMatch(date -> date.startsWith("2099"))
                .anyMatch(date -> date.substring(DATE_MONTH_START, DATE_MONTH_END).equals("01"))
                .anyMatch(date -> date.substring(DATE_MONTH_START, DATE_MONTH_END).equals("12"));
    }

    /**
     * Confirms the page conversion carries both boundary tokens across unchanged.
     *
     * <p>Purpose: a caller can only continue from a position the server can reconstruct, so a
     * conversion that masked, re-sealed or reordered either token would leave the listing unable to
     * page while every individual row still looked correct.</p>
     *
     * @throws IOException if the boundary fixture cannot be read from the classpath
     */
    // WHY : Assumptions: the position these tokens stand for is the card number followed by the
    //       account identifier, which is the order app/cbl/COCRDLIC.cbl:230-232 holds the browse cursor
    //       in -- WS-CA-LAST-CARD-NUM PIC X(16) then WS-CA-LAST-CARD-ACCT-ID PIC 9(11). It is
    //       deliberately not the twenty-eight-character display row at :258-260, whose sequence is the
    //       account number, then the card number, then the active status as its twenty-eighth
    //       character; that row is what the screen painted, and the program's own comment at :250
    //       states its arithmetic across the seven rows it repeats. Reading the display row as a
    //       position would page in a different sequence from the one the browse used.
    // WHY : Trade-offs: the two tokens are carried VERBATIM even though every row's card number in the
    //       same response is masked, and masking them uniformly was the alternative. It is rejected
    //       because no position can be reconstructed from a masked value, so a uniformly masked
    //       envelope would page nowhere. The compromise is narrower than it looks: the envelope's own
    //       constructor refuses a component that is not a sealed token, so the raw position cannot
    //       travel in either member, which the last assertion here checks directly.
    @Test
    @DisplayName("the page conversion carries both boundary tokens across unchanged")
    void thePageConversionCarriesBothBoundaryTokensUnchanged() throws IOException {
        List<Card> stored = cardsFrom(EXPIRY_BOUNDS);
        String leadingPosition = stored.get(0).getCardNum()
                + this.mapper.toDetail(stored.get(0)).accountId();
        String trailingPosition = stored.get(stored.size() - 1).getCardNum()
                + this.mapper.toDetail(stored.get(stored.size() - 1)).accountId();
        String firstToken = sealedCursor(leadingPosition);
        String lastToken = sealedCursor(trailingPosition);

        PageResponse<CardSummary> page = this.mapper.toSummaryPage(
                PageResponse.ofRows(stored, firstToken, lastToken, true));

        assertThat(page.firstKey()).isEqualTo(firstToken);
        assertThat(page.lastKey()).isEqualTo(lastToken);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.items())
                .as("one listing row per stored card, in the order the query settled")
                .hasSameSizeAs(stored)
                .extracting(CardSummary::accountId)
                .containsExactlyElementsOf(stored.stream()
                        .map(card -> this.mapper.toDetail(card).accountId()).toList());
        assertThat(this.json.writeValueAsString(page))
                .as("no raw keyset position reaches the serialised envelope")
                .doesNotContain(leadingPosition, trailingPosition);
    }

    /**
     * Confirms the mapper neither refuses nor normalises a value handed to it outside its domain.
     *
     * <p>Purpose: the mapper converts and does not validate, so a value outside a declared domain has
     * to pass through it untouched; asserting that positively is what stops a second copy of a
     * validation rule appearing here.</p>
     *
     * @throws IOException if the out-of-domain fixture cannot be read from the classpath
     */
    // WHY : Assumptions: the active status domain belongs upstream, on the request record's own
    //       constraints and in the service layer, and its provenance is
    //       88 FLG-YES-NO-VALID VALUES 'Y', 'N' at app/cbl/COCRDUPC.cbl:91, transcribed there and
    //       nowhere else. Re-checking it here would make this class a SECOND place the rule is written,
    //       and two statements of one rule diverge the first time only one is edited -- with the copy
    //       here being the one no request reaches, since a request that failed the first copy never
    //       arrives.
    // WHY : Assumptions: the fixture driving this case is registered Class B by
    //       src/test/resources/fixtures/README.md:252-264, meaning the schema itself refuses the value,
    //       so it is used here as bytes handed to a converter and is inserted into no table. That is
    //       the register's own instruction at :261, and honouring it keeps a failure pointing at the
    //       layer being tested rather than at a constraint violation from another one.
    @Test
    @DisplayName("the mapper neither refuses nor normalises an out-of-domain value")
    void theMapperNeitherRefusesNorNormalisesAnOutOfDomainValue() throws IOException {
        String record = recordsFrom(STATUS_OUT_OF_DOMAIN).get(0);
        String storedStatus = record.substring(EXPIRATION_DATE_END, ACTIVE_STATUS_END);
        Card stored = cardFrom(record);

        assertThat(storedStatus).isNotIn("Y", "N");
        assertThat(this.mapper.toSummary(stored).activeStatus()).isEqualTo(storedStatus);
        assertThat(this.mapper.toDetail(stored).activeStatus()).isEqualTo(storedStatus);
        assertThat(this.json.readTree(this.json.writeValueAsString(this.mapper.toDetail(stored)))
                .get("activeStatus").asString())
                .isEqualTo(storedStatus);
        assertThat(this.mapper.applyUpdate(
                new CardUpdateRequest(stored.getEmbossedName(), storedStatus, "09", "2030",
                        stored.getVersion()), stored).getActiveStatus())
                .isEqualTo(storedStatus);
    }

    /**
     * Confirms the out-of-range month fixture is never turned into a date by this class.
     *
     * <p>Purpose: this records, as an executable statement, where date parsing and range checking
     * happen relative to the mapper, which is upstream of it in both cases.</p>
     *
     * @throws IOException if the out-of-range fixture cannot be read from the classpath
     */
    // WHY : Assumptions: this fixture is read as characters and is deliberately never converted, and no
    //       stored card is built from it anywhere in this class. Its two records carry months outside
    //       the calendar-month domain, so neither stored date names a day that exists; the mapper's own
    //       parameter is already a calendar date, so an unrepresentable month cannot reach it and the
    //       range check that refuses one belongs to the request record's constraints and to the shared
    //       date validator, whose provenance is 88 VALID-MONTH VALUES 1 THRU 12 at
    //       app/cbl/COCRDUPC.cbl:95. Asserting the out-of-domain months directly, rather than asserting
    //       that a conversion of them fails, is what keeps this case about the boundary instead of
    //       about a parser.
    // WHY : Assumptions: the register at src/test/resources/fixtures/README.md:261 requires a Class B
    //       record to stay out of card.cards, and this case honours that by touching no table at all.
    @Test
    @DisplayName("the out-of-range month fixture is never turned into a date")
    void theOutOfRangeMonthFixtureIsNeverTurnedIntoADate() throws IOException {
        List<String> records = recordsFrom(MONTH_OUT_OF_RANGE);
        assertThat(records).hasSizeGreaterThan(1);

        for (String record : records) {
            String storedDate = record.substring(EMBOSSED_NAME_END, EXPIRATION_DATE_END);
            int month = Integer.parseInt(storedDate.substring(DATE_MONTH_START, DATE_MONTH_END));

            assertThat(month)
                    .as("stored month %s of %s lies outside the calendar-month domain", month,
                            storedDate)
                    .matches(stored -> stored < LOWEST_MONTH || stored > HIGHEST_MONTH,
                            "outside the inclusive domain the baseline admits");
        }
    }
}
