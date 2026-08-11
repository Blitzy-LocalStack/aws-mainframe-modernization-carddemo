package com.carddemo.authorization.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.AuthFraudKey;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.FraudMarkRequest;
import com.carddemo.authorization.dto.FraudMarkResponse;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import jakarta.persistence.Column;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds {@link AuthFraudMapper} to the twenty-six-column fraud row the baseline table declares.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class asserts the five things that make the crossing from the IMS detail segment to the
 * relational {@code AUTHFRDS} row non-mechanical, and it asserts them at the row: the merchant name that
 * is deliberately NOT trimmed, the two numeric REGIME CHANGES, the one field name the target replaces,
 * the report date that has two independent provenances, and the projection's shape and order. Every
 * case constructs its subject directly and reads at most a committed byte image; nothing here starts an
 * application context, opens a connection, executes a statement or sends a message.
 *
 * <p>Assumptions: the eleven members this class reaches had NO caller anywhere in this module's test
 * tree before it existed -- {@code toFraudRow}, {@code applyFraudState}, {@code fraudRowKey},
 * {@code detailKey}, {@code fraudFlagColumn}, {@code processingCodeColumn},
 * {@code merchantNameColumn}, {@code markResponse}, {@code toView} and the two package-private
 * constants. The two members that did have callers, the pair that reads and writes the segment's own
 * eight-character report date, are asserted by {@code SegmentConversionContractTest} and are therefore
 * not re-asserted here; this class uses one of them only as the counterpart in the provenance
 * comparison that is its own subject.
 *
 * <h2>What this class does not re-prove</h2>
 *
 * <p>The package charter at {@code com/carddemo/authorization/mapper/package-info.java} sends the codec
 * internals to {@code services/common-lib} and divides the rest of this boundary by contract. The
 * following are asserted elsewhere and a second assertion here would leave two suites claiming one
 * contract with nothing to say which is the authority:
 *
 * <ul>
 *   <li>The packed-decimal widths, the sign and pad nibble policies and the two segment closure proofs
 *       belong to the shared kernel's codec tests. This class reads decoded values and never a nibble.
 *   <li>The nines-complement arithmetic, the nine-digit time padding, the composition of the
 *       twenty-three-character timestamp, the zero-filled RENDERINGS, the ten-character fraud display
 *       form and its refusals, the account-number mask and the detail response projection belong to
 *       {@code PendingAuthDetailMapperTest}. Where this class touches one of those it asserts what the
 *       FRAUD ROW does with the result, never how the result was produced.
 *   <li>The no-trim contract on {@code pending_auth_detail} belongs to
 *       {@code MerchantNameNoTrimFixtureTest}, which asserts it on the detail entity and on the raw
 *       image. This class asserts it one boundary further on, where the value reaches a VARCHAR column
 *       rather than a CHAR one, which is the only place the decision is observable in the stored length.
 *   <li>The catalogue facts -- that the primary key names its two columns in order, that a second index
 *       object exists, and that it descends on its second column -- belong to
 *       {@code AuthFraudRepositoryIT}, which asks the engine. This class asserts the ordering PROPERTY
 *       of the values this mapper composes, which is what makes a descending index meaningful, and it
 *       asks no database anything.
 *   <li>The action's closed domain as a validation constraint, and the fraud request body's component
 *       count, belong to {@code RowSelectorContractTest}. What this class asserts is that the two write
 *       paths REFUSE an action outside that domain, which is a property of the mapper rather than of
 *       the body.
 * </ul>
 *
 * <h2>Five briefed readings this class corrects, each re-verified against the source</h2>
 *
 * <p>Refactoring Rationale: each of the five was carried into this file's brief as a fact and is
 * superseded by the artifacts named beside it. They are recorded rather than silently dropped because
 * each one, acted on, would have produced a passing assertion about something the code does not do.
 *
 * <ul>
 *   <li>{@code FraudMarkRequest} declares ONE component and not five. The five-member shape is the
 *       reference communication area's, at {@code cbl/COPAUS2C.cbl} L73 to L86, whose two identifiers,
 *       segment, action, status and message sum to 272 bytes. The target splits that: the row is named
 *       by the operation's path selector, the customer identifier is resolved server-side, the status
 *       and the message belong to the response, and the body carries the action alone -- registered as
 *       {@code D-AUTH-FRAUD-TARGET-STATE}.
 *   <li>The twenty-six-versus-twenty-seven discrepancy has TWO different explanations and the briefed
 *       one belongs to the other artifact. {@code ddl/AUTHFRDS.ddl} lists 26 columns on L2 to L27 plus
 *       one {@code PRIMARY KEY} clause on L28, which is the 27 items in its parenthesis; the VARCHAR
 *       length-and-text pair is what makes {@code dcl/AUTHFRDS.dcl} L55 to L86 hold 28 elementary items
 *       while its own L88 states 26 columns. Both reconciliations resolve to 26 and neither is the
 *       other.
 *   <li>No character timestamp key was retained. {@code ddl/AUTHFRDS.ddl} L3 declares
 *       {@code AUTH_TS TIMESTAMP} and the migration declares {@code TIMESTAMP(6)}, so the baseline
 *       column was never characters and the target did not convert it. The {@code PIC X(26)} at
 *       {@code dcl/AUTHFRDS.dcl} L57 is the HOST VARIABLE the composed twenty-three-character text is
 *       bound through, and {@code cbl/COPAUS2C.cbl} L171 to L172 converts it with
 *       {@code TIMESTAMP_FORMAT} on the way in.
 *   <li>An out-of-domain fraud position is REFUSED and not passed through. Both the decode and this
 *       mapper's own column boundary reject it, and the production code records that passing it
 *       through silently is the behaviour they replaced.
 *   <li>The prohibition on binary floating point IS inherited. The shared kernel's rule A3 selects the
 *       whole analysed root rather than the money package alone, and it is re-run against this module's
 *       compiled classes by a dedicated Surefire execution, so a sweep here would re-prove a rule this
 *       package already inherits. What is NOT inherited is scale, rounding and the string form on the
 *       wire, because no import graph can see any of them, and those are asserted below.
 * </ul>
 *
 * <h2>Fixtures</h2>
 *
 * <p>Assumptions: five committed images are read, each in BINARY and never as text, because a text
 * decode would fold the two packed money spans through a character decoder and change bytes that are
 * not characters. A recorded image in this module carries no trailing newline, so its length equals its
 * content length. Each case states the layout and the length invariant it depends on in its own
 * {@code Assumptions:} block; the directory's own README carries the per-file description and
 * {@code AuthorizationFixtureContractTest} enrols every resource, so the three artifacts discharge the
 * documentation obligation jointly and none of them is redundant.
 *
 * <p>Trade-offs: the offsets and widths below are READ from the registered layout rather than written
 * as literals, at the cost of one indirection per constant. A literal offset is the single drift this
 * class could not detect on its own -- a span read one byte out of alignment still decodes to plausible
 * characters, so nothing raises and no case fails.
 *
 * <p>Trade-offs: this class reaches four package-private members of {@link PendingAuthDetailMapper} --
 * the timestamp composition, its text form, the fraud display rendering and the entity decode -- which
 * it can do because it shares their package. The alternative was to widen those members to public so a
 * test outside the package could reach them, which would publish package internals to satisfy a test
 * and would let another context depend on them. The cost accepted is that this class cannot move out of
 * this package.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause.
 */
@DisplayName("The fraud row projection, its two regime changes, its one rename and its two date sources")
class AuthFraudMapperTest {

    /**
     * The classpath directory every committed image in this module sits beneath.
     */
    private static final String FIXTURE_ROOT = "fixtures/";

    /**
     * The schema migration that declares the target fraud table, read as text and never executed.
     *
     * <p>Assumptions: this resource is on the test classpath because it is a MAIN resource of the same
     * module, and reading it is a declaration-level reading rather than a database question. The
     * engine-level equivalents -- that the table exists with these columns and that its second index
     * descends -- are asserted by {@code AuthFraudRepositoryIT} against a live catalogue.</p>
     */
    private static final String MIGRATION_RESOURCE = "db/migration/V1__authorization.sql";

    /**
     * The parent account identifier the single-account detail images were generated under.
     *
     * <p>Assumptions: a detail segment carries no account identifier of its own, so every conversion of
     * a bare segment takes one as an argument, and this is the value the committed images were produced
     * with. The fraud row then reads it back off the decoded key rather than accepting it a second
     * time, which is why no case here passes it to {@link AuthFraudMapper#toFraudRow}.</p>
     */
    private static final Long FIXTURE_ACCOUNT_ID = 10_000_000_001L;

    /**
     * The customer identifier a caller resolves server-side and hands to the fraud projection.
     *
     * <p>Assumptions: this value comes from the authorization's parent summary row and NOT from the
     * request body, which is why it is a parameter of the projection. The reference program takes it
     * from its communication area at {@code cbl/COPAUS2C.cbl} L76 and moves it into the row's last
     * column at L139; the target declines to accept it from a browser.</p>
     */
    private static final Long FIXTURE_CUSTOMER_ID = 900_000_001L;

    /**
     * The report date every case injects, standing in for the value the database supplies.
     *
     * <p>Assumptions: a fixed date rather than today's, because the column's value is a PARAMETER of
     * both write paths and a case that read a clock could not distinguish a mapper honouring the
     * parameter from one ignoring it. It is deliberately far from every fixture's own dates so that a
     * value taken from the wrong source is visible rather than coincidentally equal.</p>
     */
    private static final LocalDate DATABASE_CURRENT_DATE = LocalDate.of(2026, 8, 6);

    /**
     * The registered geometry of the detail segment, and the single source of every offset below.
     */
    private static final CopybookLayout.RecordSpec SEGMENT_LAYOUT = CopybookLayout.layout("PAUTDTL");

    /**
     * The declared length of one detail segment image, from the registration rather than a literal.
     */
    private static final int SEGMENT_LENGTH = SEGMENT_LAYOUT.reclen();

    /**
     * The zero-based start of the sixteen-digit account number inside a segment image.
     */
    private static final int CARD_NUMBER_OFFSET = startOf("PA-CARD-NUM");

    /**
     * The declared width of the account number, which the fraud table's first key column repeats.
     */
    private static final int CARD_NUMBER_WIDTH = widthOf("PA-CARD-NUM");

    /**
     * The zero-based start of the six-digit numeric processing code, the first regime change's source.
     */
    private static final int PROCESSING_CODE_OFFSET = startOf("PA-PROCESSING-CODE");

    /**
     * The declared width of the processing code, which is six on both sides of the regime change.
     */
    private static final int PROCESSING_CODE_WIDTH = widthOf("PA-PROCESSING-CODE");

    /**
     * The zero-based start of the four-character merchant category code, the renamed field's source.
     */
    private static final int MERCHANT_CATEGORY_OFFSET = startOf("PA-MERCHANT-CATAGORY-CODE");

    /**
     * The declared width of the merchant category code, unchanged by the rename.
     *
     * <p>Assumptions: the field name spelled here is the BASELINE spelling, which the copybook writes
     * at {@code cpy/CIPAUDTY.cpy} L36 and the registration repeats. It appears in this file only where
     * a baseline artifact is being named, never as a Java identifier.</p>
     */
    private static final int MERCHANT_CATEGORY_WIDTH = widthOf("PA-MERCHANT-CATAGORY-CODE");

    /**
     * The zero-based start of the two-digit numeric entry mode, the second regime change's source.
     */
    private static final int POS_ENTRY_MODE_OFFSET = startOf("PA-POS-ENTRY-MODE");

    /**
     * The declared width of the entry mode in the segment, which is two bytes of digit characters.
     */
    private static final int POS_ENTRY_MODE_WIDTH = widthOf("PA-POS-ENTRY-MODE");

    /**
     * The zero-based start of the twenty-two-character merchant name.
     */
    private static final int MERCHANT_NAME_OFFSET = startOf("PA-MERCHANT-NAME");

    /**
     * The zero-based start of the one-character fraud position.
     */
    private static final int FRAUD_FLAG_OFFSET = startOf("PA-AUTH-FRAUD");

    /**
     * The zero-based start of the segment's own eight-character fraud report date.
     */
    private static final int FRAUD_REPORT_DATE_OFFSET = startOf("PA-FRAUD-RPT-DATE");

    /**
     * The declared width of the segment's fraud report date, which the target column widens.
     */
    private static final int FRAUD_REPORT_DATE_WIDTH = widthOf("PA-FRAUD-RPT-DATE");

    /**
     * The serialiser the money cases publish a rendered fraud row through.
     *
     * <p>Assumptions: only the money module is registered, matching the module the shared kernel
     * publishes rather than a fully configured application mapper. Adding anything else would let a
     * second module's behaviour explain a passing assertion.</p>
     */
    private static final JsonMapper MAPPER =
            JsonMapper.builder().addModule(new MoneyModule()).build();

    /**
     * Reads one field's zero-based start offset off the registered segment geometry.
     *
     * @param field the copybook field name, spelled exactly as the registration declares it; must not
     *     be {@code null}
     * @return the field's zero-based start offset within a detail segment
     * @throws IllegalArgumentException if the registration declares no field of that name, which means
     *     the name here is misspelled or the layout renamed the field
     */
    private static int startOf(String field) {
        return SEGMENT_LAYOUT.field(field).start();
    }

    /**
     * Reads one field's declared byte width off the registered segment geometry.
     *
     * @param field the copybook field name, spelled exactly as the registration declares it; must not
     *     be {@code null}
     * @return the field's declared width in bytes
     * @throws IllegalArgumentException if the registration declares no field of that name
     */
    private static int widthOf(String field) {
        return SEGMENT_LAYOUT.field(field).length();
    }

    /**
     * Reads one committed image off the test classpath as bytes.
     *
     * @param name the resource name below the fixture root; must not be {@code null}
     * @return the resource's bytes, never {@code null}
     * @throws AssertionError if the resource does not resolve on the test classpath, which means the
     *     image was renamed or removed rather than that any rule under test is wrong
     * @throws UncheckedIOException if the resource resolves but cannot be read
     */
    private static byte[] bytesOf(String name) {
        try (InputStream stream = AuthFraudMapperTest.class.getClassLoader()
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
     * Cuts one whole segment out of a multi-record image at the declared stride.
     *
     * @param image the whole image; must not be {@code null} and must divide by the segment length
     * @param ordinal the zero-based record position to cut
     * @return a copy of that record's bytes, exactly one segment long
     * @throws IndexOutOfBoundsException if the ordinal names a record the image does not hold
     */
    private static byte[] recordAt(byte[] image, int ordinal) {
        return Arrays.copyOfRange(image, ordinal * SEGMENT_LENGTH, (ordinal + 1) * SEGMENT_LENGTH);
    }

    /**
     * Reads one span of a segment image back as the characters it holds.
     *
     * <p>Assumptions: the span is decoded as US-ASCII, which is the character set the committed images
     * were generated in, and only spans the layout declares as text are read this way. A packed span
     * read through this helper would return replacement characters rather than a number.</p>
     *
     * @param record one whole segment image; must not be {@code null}
     * @param offset the span's zero-based start
     * @param width the span's declared width in bytes
     * @return the characters the span holds, at full declared width and never trimmed
     */
    private static String spanOf(byte[] record, int offset, int width) {
        return new String(record, offset, width, StandardCharsets.US_ASCII);
    }

    /**
     * Decodes one record of a committed image into the stored authorization it represents.
     *
     * @param fixture the image's resource name below the fixture root; must not be {@code null}
     * @param ordinal the zero-based record position within that image
     * @return the decoded authorization, never {@code null}
     * @throws IllegalArgumentException if the record carries a value outside a domain its columns
     *     admit, which one committed image does deliberately
     */
    private static PendingAuthDetail detailOf(String fixture, int ordinal) {
        return PendingAuthDetailMapper.toEntity(recordAt(bytesOf(fixture), ordinal),
                FIXTURE_ACCOUNT_ID);
    }

    /**
     * Projects one record of a committed image onto the fraud row a stated action would write.
     *
     * @param fixture the image's resource name below the fixture root; must not be {@code null}
     * @param ordinal the zero-based record position within that image
     * @param action the fraud action the request body carries; must be in the closed two-value domain
     * @return the populated fraud row, never {@code null}
     * @throws IllegalArgumentException if the action is outside the closed domain, or if the record
     *     carries a value outside a domain its columns admit
     */
    private static AuthFraud fraudRowOf(String fixture, int ordinal, String action) {
        return AuthFraudMapper.toFraudRow(detailOf(fixture, ordinal), new FraudMarkRequest(action),
                FIXTURE_CUSTOMER_ID, DATABASE_CURRENT_DATE);
    }

    /**
     * Lists the mapped fields of one persistent type in the order its declaration writes them.
     *
     * <p>Assumptions: a mapped field is one carrying a {@link Column} annotation, and the embedded key
     * is excluded here because its own components are counted through a second call on the key type.
     * The reflection API reports fields in declaration order for classes this compiler emits, which is
     * what makes the position of the last two meaningful; the stored ORDER of the columns themselves is
     * a catalogue fact and is asserted against the engine by {@code AuthFraudRepositoryIT}.</p>
     *
     * @param type the persistent type to inspect; must not be {@code null}
     * @return the annotated fields in declaration order, never {@code null}
     */
    private static List<Field> mappedFieldsOf(Class<?> type) {
        List<Field> mapped = new ArrayList<>();
        for (Field candidate : type.getDeclaredFields()) {
            if (candidate.isAnnotationPresent(Column.class)) {
                mapped.add(candidate);
            }
        }
        return mapped;
    }

    /**
     * Lists the column names one persistent type declares, in declaration order.
     *
     * @param type the persistent type to inspect; must not be {@code null}
     * @return the column names its {@link Column} annotations name, never {@code null}
     */
    private static List<String> columnNamesOf(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field mapped : mappedFieldsOf(type)) {
            names.add(mapped.getAnnotation(Column.class).name());
        }
        return names;
    }

    /**
     * Reads one named mapped field of a persistent type.
     *
     * @param type the persistent type to inspect; must not be {@code null}
     * @param field the field's Java name; must not be {@code null}
     * @return that field, which is known to carry a {@link Column} annotation, never {@code null}
     * @throws AssertionError if the type declares no field of that name, or declares one that carries
     *     no column annotation, either of which means this test names a member that no longer exists
     */
    private static Field mappedFieldOf(Class<?> type, String field) {
        for (Field candidate : type.getDeclaredFields()) {
            if (candidate.getName().equals(field) && candidate.isAnnotationPresent(Column.class)) {
                return candidate;
            }
        }
        throw new AssertionError(type.getSimpleName() + " declares no mapped field named " + field);
    }

    /**
     * Reads the {@link Column} annotation one named field of a persistent type carries.
     *
     * @param type the persistent type to inspect; must not be {@code null}
     * @param field the field's Java name; must not be {@code null}
     * @return that field's column annotation, never {@code null}
     * @throws AssertionError if the type declares no such mapped field
     */
    private static Column columnOf(Class<?> type, String field) {
        return mappedFieldOf(type, field).getAnnotation(Column.class);
    }

    /**
     * Reads the shipped migration's definition of the fraud table as text.
     *
     * <p>Assumptions: the block is cut from the opening of {@code CREATE TABLE auth_fraud} to the first
     * statement terminator that follows it, so a column named in a neighbouring table cannot satisfy an
     * assertion written about this one.</p>
     *
     * @return the fraud table's definition, comments included, never {@code null}
     * @throws AssertionError if the migration does not resolve on the classpath or declares no such
     *     table, either of which means the schema moved rather than that a mapping rule is wrong
     * @throws UncheckedIOException if the migration resolves but cannot be read
     */
    private static String fraudTableDefinition() {
        String migration;
        try (InputStream stream = AuthFraudMapperTest.class.getClassLoader()
                .getResourceAsStream(MIGRATION_RESOURCE)) {
            if (stream == null) {
                throw new AssertionError(MIGRATION_RESOURCE + " is not on the test classpath");
            }
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(MIGRATION_RESOURCE + " could not be read", failure);
        }
        int opening = migration.indexOf("CREATE TABLE auth_fraud (");
        if (opening < 0) {
            throw new AssertionError(MIGRATION_RESOURCE + " declares no auth_fraud table");
        }
        int closing = migration.indexOf("\n);", opening);
        if (closing < 0) {
            throw new AssertionError(MIGRATION_RESOURCE + " leaves auth_fraud unterminated");
        }
        return migration.substring(opening, closing);
    }

    /**
     * Reads the shipped migration's declaration of one fraud-table column.
     *
     * <p>Assumptions: the line is matched by a pattern that allows for leading indentation and is
     * returned exactly as the file holds it, so an assertion about a declared type never depends on how
     * the file happens to be aligned. Alignment is formatting and a reflow of it must not fail a test
     * about a type. Trade-offs: matching rather than stripping the indentation is slightly more verbose,
     * and it keeps this file free of any trimming call outside the one that exists to PROHIBIT trimming
     * -- so a reader sweeping for a stray trim finds exactly one occurrence and it is the intended
     * one.</p>
     *
     * @param column the column name as the migration writes it; must not be {@code null}
     * @return that column's declaration line as the file holds it, never {@code null}
     * @throws AssertionError if the fraud table declares no column of that name
     */
    private static String fraudColumnDeclaration(String column) {
        Pattern declaration = Pattern.compile("^\\s*" + Pattern.quote(column) + "\\s.*$");
        return fraudTableDefinition().lines()
                .filter(line -> declaration.matcher(line).matches())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the auth_fraud table declares no column named " + column));
    }

    /**
     * The merchant name that keeps its trailing blanks, which is this class's headline property.
     *
     * <p>Assumptions: the whole contract rests on one COBOL idiom that is invisible unless it is read.
     * {@code cbl/COPAUS2C.cbl} L130 performs {@code MOVE LENGTH OF PA-MERCHANT-NAME TO
     * MERCHANT-NAME-LEN} and L131 then moves the text. The operand is declared {@code PIC X(22)} at
     * {@code cpy/CIPAUDTY.cpy} L40, so {@code LENGTH OF} it is the CONSTANT 22 and not the length of
     * whatever the field happens to contain. The move is unconditional, so the variable-length column is
     * written at its full declared width on every row the reference application inserts.</p>
     *
     * <p>Assumptions: the asymmetry with the rest of the table is what makes the decision OBSERVABLE at
     * all. Every other character column of {@code AUTHFRDS} is {@code CHAR(n)}, where the padding
     * belongs to the column type and a mapper can neither preserve nor discard it; for a VARCHAR the
     * stored length is a decision, and the baseline decided twenty-two unconditionally.</p>
     *
     * <p>Alternatives Considered: storing the trimmed content length, which is what a VARCHAR normally
     * implies and what a generated mapper would do. Rejected because it changes the stored value on
     * every row whose merchant name is shorter than its field: the value would become variable-length,
     * and any consumer comparing against the stored form -- a predicate, a join, a checksum over the
     * column -- would stop matching while every row still looked correct on inspection.</p>
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
     * at-clause.</p>
     */
    @Nested
    @DisplayName("the merchant name that is not trimmed")
    class MerchantNameNoTrim {

        /**
         * The fraud row's merchant name arrives at twenty-two characters, trailing blanks included.
         *
         * <p>Assumptions: {@code pautdtl1-merchant-name-notrim.bin} is one 200-byte image holding a
         * single record. Its merchant name span at offset 112 holds {@code ACME CO} followed by fifteen
         * spaces, which is the shortest content of any committed image and therefore the only one where
         * a trim would be visible in the length. Its two money spans hold 812.45 and the transaction
         * amount's span ends {@code 0x5C}, a final digit sharing its byte with the positive sign nibble;
         * the nibble rule itself belongs to the shared kernel's packed codec tests and is not re-derived
         * here.</p>
         *
         * <p>Assumptions: the padded value is compared for EQUALITY against the twenty-two characters
         * rather than merely checked for length, and it is additionally compared against its own trimmed
         * form. A length check alone would pass for a value re-padded after a trim, and the inequality
         * against the trimmed form is what makes a future trim fail loudly instead of quietly.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the row's merchant name is the padded twenty-two characters, not its trimmed form")
        void theRowKeepsAllTwentyTwoCharacters() {
            byte[] record = recordAt(bytesOf("pautdtl1-merchant-name-notrim.bin"), 0);
            String stored = spanOf(record, MERCHANT_NAME_OFFSET,
                    AuthFraudMapper.MERCHANT_NAME_COLUMN_WIDTH);

            AuthFraud row = fraudRowOf("pautdtl1-merchant-name-notrim.bin", 0,
                    PendingAuthDetail.FRAUD_REPORTED);

            assertThat(stored).as("the committed span itself, before any conversion")
                    .isEqualTo("ACME CO               ");
            assertThat(row.getMerchantName())
                    .as("the value reaching merchant_name VARCHAR(22), padding included")
                    .isEqualTo(stored)
                    .hasSize(AuthFraudMapper.MERCHANT_NAME_COLUMN_WIDTH);
            assertThat(row.getMerchantName())
                    .as("a trim would yield seven characters, so this inequality is the whole contract")
                    .isNotEqualTo(stored.trim());
            assertThat(row.getMerchantName().substring("ACME CO".length()))
                    .as("characters eight through twenty-two are the retained padding")
                    .isEqualTo(" ".repeat(AuthFraudMapper.MERCHANT_NAME_COLUMN_WIDTH
                            - "ACME CO".length()));
            assertThat(row.getTransactionAmt())
                    .as("the same image's amount, so a shifted span would fail here too")
                    .isEqualByComparingTo("812.45");
        }

        /**
         * The column boundary hands every padding shape back exactly as it arrived.
         *
         * <p>Assumptions: an all-blank name is neither converted to {@code null} nor collapsed to an
         * empty string, which is what separates this field from every other blank-tolerant one in the
         * context. Twenty-two blanks are what the reference insert path writes into a name it has
         * nothing for, and they are a legitimate stored value of the declared width rather than an
         * absence: the column is what carries absence, and this mapper is not asked to invent one.</p>
         *
         * <p>Assumptions: {@code null} is answered with {@code null} rather than with blanks, because a
         * null arriving here can only have come from a column an extract left unset, and padding it
         * would fabricate a value the source did not hold.</p>
         *
         * @param shape the name of the padding shape under test, used to name a failing row
         * @param stored the merchant name as a segment would hold it, which may be {@code null}
         * @param expected the value the column boundary must return unchanged
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource(
                "com.carddemo.authorization.mapper.AuthFraudMapperTest#merchantNamePaddingShapes")
        @DisplayName("every padding shape crosses the column boundary unchanged")
        void theBoundaryHandsEveryPaddingShapeBackUnchanged(String shape, String stored,
                String expected) {
            assertThat(AuthFraudMapper.merchantNameColumn(stored))
                    .as("%s: the boundary exists to make the absence of a trim readable", shape)
                    .isEqualTo(expected);
        }

        /**
         * A name wider than the column declares is refused rather than truncated to fit.
         *
         * <p>Assumptions: refusal is the only safe answer because the column could not store the value
         * either. Truncating would produce a row that looked well formed and named a different merchant,
         * and the twenty-third character is exactly the evidence that something upstream widened a field
         * the copybook fixes at twenty-two.</p>
         *
         * <p>This test takes no parameter and returns no value. It asserts that
         * {@link IllegalArgumentException} is raised, which is named here because the assertion's
         * expectation sits inside a lambda where a documentation gate cannot see it.</p>
         */
        @Test
        @DisplayName("a twenty-three character name is refused, naming the declared width")
        void anOverWideNameIsRefused() {
            String tooWide = "A".repeat(AuthFraudMapper.MERCHANT_NAME_COLUMN_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AuthFraudMapper.merchantNameColumn(tooWide))
                    .withMessageContaining(String.valueOf(
                            AuthFraudMapper.MERCHANT_NAME_COLUMN_WIDTH));
        }

        /**
         * The merchant name is the one variable-length column the fraud table declares.
         *
         * <p>Assumptions: the shipped migration is read as TEXT and no statement is executed, so this is
         * a reading of the declaration rather than a question to a database. The baseline agrees twice
         * over: {@code MERCHANT_NAME VARCHAR(22)} at {@code ddl/AUTHFRDS.ddl} L18 is the only VARCHAR
         * among the twenty-six columns, and {@code dcl/AUTHFRDS.dcl} L73 to L77 is the only varying
         * length host structure -- a group holding a binary length field and a twenty-two-character text
         * field.</p>
         *
         * <p>Assumptions: the entity's declared width is asserted against the mapper's own constant
         * rather than against a literal twenty-two, so the width exists in one place. The constant's own
         * three sources are recorded where it is declared.</p>
         *
         * <p>Alternatives Considered: asking a live catalogue which of the table's columns is
         * variable-length, which is how the sibling repository case establishes catalogue facts. Rejected
         * for THIS claim on two grounds. It would move a case that needs nothing but a declaration into a
         * suite that needs a container, so the property would stop being checked wherever a container is
         * unavailable; and the claim being made is about the SHIPPED DECLARATION rather than about a
         * deployed schema, since what a future author would change is this line of the migration. The
         * catalogue-level reading is not dropped -- it stays where it belongs -- and the two are
         * complementary, because a declaration that no longer says VARCHAR and a catalogue that no longer
         * reports one are different regressions.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("merchant_name is the only variable-length column, and it is twenty-two wide")
        void theMerchantNameIsTheOnlyVariableWidthColumn() {
            List<String> varyingColumns = fraudTableDefinition().lines()
                    .filter(line -> line.contains("VARCHAR"))
                    .toList();

            assertThat(varyingColumns)
                    .as("the fraud table's variable-length columns, of which there is exactly one")
                    .hasSize(1);
            assertThat(varyingColumns.get(0))
                    .as("and the one that is variable-length is the merchant name")
                    .isEqualTo(fraudColumnDeclaration("merchant_name"))
                    .contains("VARCHAR(" + AuthFraudMapper.MERCHANT_NAME_COLUMN_WIDTH + ")");
            assertThat(columnOf(AuthFraud.class, "merchantName").length())
                    .as("the entity declares the same width the column does")
                    .isEqualTo(AuthFraudMapper.MERCHANT_NAME_COLUMN_WIDTH);
        }
    }

    /**
     * Supplies the padding shapes the merchant name column boundary must hand back unchanged.
     *
     * <p>Assumptions: the shapes are chosen so that each would be altered by a DIFFERENT plausible
     * implementation -- a trailing trim, a blank-to-null conversion, an empty-to-null conversion and a
     * null-to-blank padding -- so a single implementation error cannot pass all four.</p>
     *
     * @return one argument triple per padding shape, never {@code null}
     */
    private static Stream<Arguments> merchantNamePaddingShapes() {
        int width = AuthFraudMapper.MERCHANT_NAME_COLUMN_WIDTH;
        return Stream.of(
                Arguments.of("content with retained trailing blanks", "ACME CO" + " ".repeat(15),
                        "ACME CO" + " ".repeat(15)),
                Arguments.of("content filling the declared width", "A".repeat(width),
                        "A".repeat(width)),
                Arguments.of("all blank at the declared width", " ".repeat(width),
                        " ".repeat(width)),
                Arguments.of("empty rather than blank", "", ""),
                Arguments.of("absent, which stays absent", null, null));
    }

    /**
     * The two fields whose STORAGE REGIME changes as they cross from the segment to the table.
     *
     * <p>Assumptions: both are numeric {@code DISPLAY} fields in the segment and neither stays one.
     * {@code PA-PROCESSING-CODE} is {@code PIC 9(06)} at {@code cpy/CIPAUDTY.cpy} L33 and becomes
     * {@code PROCESSING-CODE PIC X(6)} at {@code dcl/AUTHFRDS.dcl} L65, surfacing as
     * {@code PROCESSING_CODE CHAR(6)} at {@code ddl/AUTHFRDS.ddl} L11 -- numeric to CHARACTER.
     * {@code PA-POS-ENTRY-MODE} is {@code PIC 9(02)} at {@code cpy/CIPAUDTY.cpy} L38 and becomes
     * {@code POS-ENTRY-MODE PIC S9(4) USAGE COMP} at {@code dcl/AUTHFRDS.dcl} L71, surfacing as
     * {@code POS_ENTRY_MODE SMALLINT} at {@code ddl/AUTHFRDS.ddl} L16 -- display text to BINARY.</p>
     *
     * <p>Assumptions: the first change makes leading zeros SIGNIFICANT. In the source they are
     * insignificant because the field is numeric; in the destination they are part of the value because
     * the field is characters and a fixed-width character column compares on its whole width. A code of
     * forty-two that reached the column as {@code 42} or as four spaces and two digits would compare
     * equal to no row the baseline wrote, so every query and join on it would return nothing rather than
     * report a mismatch.</p>
     *
     * <p>Assumptions: the second change is the dangerous one, and the danger is a COINCIDENCE of size.
     * Two digit characters and a binary halfword both occupy two bytes, so a mapper that copied the
     * bytes through unchanged would produce a number that fits the column perfectly and is wildly wrong
     * -- the two characters {@code 05} read as a big-endian halfword are 12341. Nothing about the width
     * would reveal it.</p>
     *
     * <p>Assumptions: the second change also WIDENS the digit capacity from two to four while the byte
     * count stays at two, because {@code S9(4) COMP} declares four digit positions. The target column is
     * therefore not narrowed back to the segment's two; the two-digit bound that does exist belongs to
     * the segment's own display form and is enforced on the detail side, not on this column.</p>
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
     * at-clause.</p>
     */
    @Nested
    @DisplayName("the two numeric regime changes")
    class NumericRegimeChanges {

        /**
         * Both regime changes are checked as named rows, so a regression in either is attributable.
         *
         * <p>Assumptions: the two are exercised together because they are one decision applied twice --
         * a numeric display field is dispatched to the representation its destination declares rather
         * than to the one it arrived in -- and a table naming both makes a change to only one of them
         * fail with the field's name in the failure. The values come from
         * {@code pautdtl1-canonical.bin}, one 200-byte image whose processing code span at offset 68
         * holds {@code 000000} and whose entry mode span at offset 95 holds {@code 05}.</p>
         *
         * @param field the segment field name under test, used to name a failing row
         * @param sourceRegime the storage regime the segment declares for it
         * @param targetRegime the storage regime the table declares for it
         * @param mapped the value the fraud row carries once the change has been applied
         * @param expectedType the Java type the fraud row's column must hold it in
         */
        @ParameterizedTest(name = "{0}: {1} becomes {2}")
        @MethodSource("com.carddemo.authorization.mapper.AuthFraudMapperTest#regimeChanges")
        @DisplayName("each regime change reaches the column in its destination's representation")
        void eachRegimeChangeReachesItsDestinationRepresentation(String field, String sourceRegime,
                String targetRegime, Object mapped, Class<?> expectedType) {
            assertThat(mapped)
                    .as("%s crosses from %s to %s", field, sourceRegime, targetRegime)
                    .isInstanceOf(expectedType);
        }

        /**
         * The processing code reaches its character column as six zero-filled digits.
         *
         * <p>Assumptions: the fraud row's own column boundary is what is asserted here. The zero-filled
         * RENDERING itself is proved by {@code PendingAuthDetailMapperTest}, so what this case adds is
         * that the fraud projection routes through that rendering and that the value landing on
         * {@code processing_code} is a six-character string rather than a number -- the two halves of the
         * regime change that a rendering test alone cannot show.</p>
         *
         * <p>Assumptions: an absent code stays absent rather than becoming six zeros, because a code of
         * zero and no code at all are different readings and the column is nullable.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the processing code is six zero-filled characters, never an unpadded number")
        void theProcessingCodeIsSixZeroFilledCharacters() {
            assertThat(AuthFraudMapper.processingCodeColumn(42L))
                    .as("leading zeros are part of the value once the destination is characters")
                    .isEqualTo("000042")
                    .hasSize(PROCESSING_CODE_WIDTH);
            assertThat(AuthFraudMapper.processingCodeColumn(0L))
                    .as("a zero code fills the whole width rather than collapsing to one digit")
                    .isEqualTo("000000");
            assertThat(AuthFraudMapper.processingCodeColumn(999_999L))
                    .as("the widest code the six declared digit positions admit")
                    .isEqualTo("999999");
            assertThat(AuthFraudMapper.processingCodeColumn(null))
                    .as("an absent code is not a code of zero")
                    .isNull();

            AuthFraud row = fraudRowOf("pautdtl1-canonical.bin", 0, PendingAuthDetail.FRAUD_REPORTED);

            assertThat(row.getProcessingCode())
                    .as("the value reaching processing_code CHAR(6) from the committed image")
                    .isEqualTo(spanOf(recordAt(bytesOf("pautdtl1-canonical.bin"), 0),
                            PROCESSING_CODE_OFFSET, PROCESSING_CODE_WIDTH));
            assertThat(columnOf(AuthFraud.class, "processingCode").length())
                    .as("and the column it lands in is six characters wide")
                    .isEqualTo(PROCESSING_CODE_WIDTH);
        }

        /**
         * The entry mode reaches its column as a binary number parsed from the two digit characters.
         *
         * <p>Assumptions: the byte-copy reading is asserted to be EXCLUDED rather than merely left
         * untested. The two characters {@code 05} occupy the same two bytes a halfword does, so a
         * copy-through implementation yields 12341 -- a value inside the column's range, which is why
         * nothing downstream would reject it. Asserting the correct value alone would pass for an
         * implementation that happened to be correct on a zero-leading pair; asserting the inequality
         * names the failure mode.</p>
         *
         * <p>Assumptions: the column's own type is asserted from the entity's declaration rather than
         * from the mapped value's class, because a null-valued column would make an instance check
         * vacuous while the declaration is always readable.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the entry mode is a parsed halfword, not the two digit bytes copied through")
        void theEntryModeIsAParsedBinaryHalfword() {
            byte[] record = recordAt(bytesOf("pautdtl1-canonical.bin"), 0);
            String stored = spanOf(record, POS_ENTRY_MODE_OFFSET, POS_ENTRY_MODE_WIDTH);
            int asCopiedBytes = ((record[POS_ENTRY_MODE_OFFSET] & 0xFF) << 8)
                    | (record[POS_ENTRY_MODE_OFFSET + 1] & 0xFF);

            AuthFraud row = fraudRowOf("pautdtl1-canonical.bin", 0, PendingAuthDetail.FRAUD_REPORTED);

            assertThat(stored).as("the segment holds two ASCII digit characters").isEqualTo("05");
            assertThat(row.getPosEntryMode())
                    .as("pos_entry_mode is the number those digits name")
                    .isEqualTo(Short.valueOf((short) 5));
            assertThat(asCopiedBytes)
                    .as("the same two bytes read as a halfword, which is what a copy would store")
                    .isEqualTo(12_341);
            assertThat(row.getPosEntryMode().intValue())
                    .as("a value that fits the column perfectly and is wrong is the failure excluded")
                    .isNotEqualTo(asCopiedBytes);
            assertThat(mappedFieldOf(AuthFraud.class, "posEntryMode").getType())
                    .as("the column is declared numeric, not as two characters")
                    .isEqualTo(Short.class);
        }

        /**
         * The target column keeps the four digit positions the declaration widens it to.
         *
         * <p>Assumptions: the widening is asserted as a property of the COLUMN and not of any value the
         * segment can produce. {@code S9(4) USAGE COMP} at {@code dcl/AUTHFRDS.dcl} L71 declares four
         * digit positions and {@code SMALLINT} holds them comfortably, so a value above ninety-nine is
         * representable even though a two-digit source can never supply one. The point of asserting it
         * is that the target must not be narrowed back to two digits on the strength of the source's
         * width, which would make the column unable to hold what its own declaration admits.</p>
         *
         * <p>Assumptions: the two-digit bound that DOES exist belongs to the segment's display form,
         * where a value has to fit back into {@code PIC 9(02)}. That bound is enforced on the detail
         * side and is asserted by {@code PendingAuthDetailMapperTest}; it is not a bound on this
         * column.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the entry mode column admits the four declared digits, not the source's two")
        void theEntryModeColumnKeepsItsFourDeclaredDigits() {
            assertThat((int) Short.MAX_VALUE)
                    .as("SMALLINT holds every value four digit positions can name")
                    .isGreaterThan(9_999);
            assertThat(columnOf(AuthFraud.class, "posEntryMode").name())
                    .as("the column the declaration widens")
                    .isEqualTo("pos_entry_mode");
            assertThat(fraudColumnDeclaration("pos_entry_mode"))
                    .as("and the migration declares it SMALLINT rather than a two-character field")
                    .contains("SMALLINT");
        }
    }

    /**
     * Supplies the two regime changes as named rows, each carrying its source and target regimes.
     *
     * <p>Assumptions: the mapped values are read from one projection of the canonical image so that both
     * rows describe the same authorization, which is what makes a difference between them attributable
     * to the regime change rather than to two different inputs.</p>
     *
     * @return one argument row per regime change, never {@code null}
     */
    private static Stream<Arguments> regimeChanges() {
        AuthFraud row = fraudRowOf("pautdtl1-canonical.bin", 0, PendingAuthDetail.FRAUD_REPORTED);
        return Stream.of(
                Arguments.of("PA-PROCESSING-CODE", "PIC 9(06) display numeric", "CHAR(6)",
                        row.getProcessingCode(), String.class),
                Arguments.of("PA-POS-ENTRY-MODE", "PIC 9(02) display numeric", "SMALLINT",
                        row.getPosEntryMode(), Short.class));
    }

    /**
     * The one field name this context replaces, and the value the replacement leaves untouched.
     *
     * <p>Assumptions: the correction happens in the TARGET and nowhere else, which is exactly why it has
     * to be asserted rather than assumed. The baseline spells the field with the misspelling in five
     * places and two of them are persisted names: the segment field at {@code cpy/CIPAUDTY.cpy} L36, the
     * message payload field at {@code cpy/CCPAURQY.cpy} L28, the table column at
     * {@code ddl/AUTHFRDS.ddl} L14, the generated table declaration at {@code dcl/AUTHFRDS.dcl} L37 and
     * the generated host field at its L68. {@code cbl/COPAUS2C.cbl} L125 to L126 moves one misspelled
     * name into the other, so there is no COBOL-side evidence that a correction ever occurred.</p>
     *
     * <p>Assumptions: the authoritative target name is
     * {@code docs/architecture/data-model-and-schema-mapping.md}, which registers
     * {@code merchant_category_code} as one of the migration's three name corrections and records that
     * this one is a breaking change at the schema boundary rather than an internal tidying, because it
     * reaches a persisted column name.</p>
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
     * at-clause.</p>
     */
    @Nested
    @DisplayName("the one field name the target corrects")
    class MerchantCategoryCodeRename {

        /**
         * The corrected name carries the baseline's four characters through unchanged.
         *
         * <p>Assumptions: the value is read from the committed image's own span at offset 88 for four
         * bytes and compared to what the row carries, so the assertion is that the rename changed the
         * IDENTIFIER and not the content. A rename that also normalised the value -- trimmed it, upper
         * cased it, or reordered it -- would still satisfy a name-only assertion.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the corrected name carries the value byte for byte from the segment")
        void theCorrectedNameCarriesTheBaselineValue() {
            byte[] record = recordAt(bytesOf("pautdtl1-canonical.bin"), 0);
            String stored = spanOf(record, MERCHANT_CATEGORY_OFFSET, MERCHANT_CATEGORY_WIDTH);

            AuthFraud row = fraudRowOf("pautdtl1-canonical.bin", 0, PendingAuthDetail.FRAUD_REPORTED);

            assertThat(stored).as("the committed span, four characters at offset 88").isEqualTo("5411");
            assertThat(row.getMerchantCategoryCode())
                    .as("the corrected member carries the same four characters")
                    .isEqualTo(stored);
            assertThat(columnOf(AuthFraud.class, "merchantCategoryCode").name())
                    .as("and the column it lands in carries the corrected spelling")
                    .isEqualTo("merchant_category_code");
            assertThat(columnOf(AuthFraud.class, "merchantCategoryCode").length())
                    .as("at the width the copybook and the table both declare")
                    .isEqualTo(MERCHANT_CATEGORY_WIDTH);
        }

        /**
         * No member or column of the mapped surface survives under the baseline's misspelling.
         *
         * <p>Assumptions: the sweep is reflective rather than a search for one known name, because the
         * failure this guards against is a NEW member reintroducing the baseline spelling rather than the
         * existing one reverting. It covers the row entity, its key and the published view together, so a
         * member added to any of the three is caught by the same case.</p>
         *
         * <p>Assumptions: the comparison is case-insensitive because the misspelling reaches the target
         * in three casings -- the copybook's upper-case hyphenated name, a camel-cased Java member and a
         * lower-cased snake column -- and a case-sensitive sweep would pass over two of the three.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("no member or column anywhere on the mapped surface keeps the misspelling")
        void noMisspelledNameSurvivesOnTheMappedSurface() {
            List<String> names = new ArrayList<>();
            for (Class<?> mapped : List.of(AuthFraud.class, AuthFraudKey.class)) {
                for (Field field : mapped.getDeclaredFields()) {
                    names.add(field.getName());
                }
                names.addAll(columnNamesOf(mapped));
            }
            for (RecordComponent component
                    : AuthFraudMapper.FraudRowView.class.getRecordComponents()) {
                names.add(component.getName());
            }

            assertThat(names)
                    .as("every declared name on the fraud row, its key and its published view")
                    .isNotEmpty()
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("catagory"));
            assertThat(names)
                    .as("and the corrected spellings are the ones that are present")
                    .contains("merchantCategoryCode", "merchant_category_code");
        }
    }

    /**
     * The report date that has two independent sources, which is the subtlest fact in this file.
     *
     * <p>Assumptions: one operator action writes this date TWICE, and the two writes do not share a
     * source in the baseline. The SEGMENT copy is application-sourced: {@code cbl/COPAUS2C.cbl} L91 to
     * L100 read the transaction monitor's clock and format it month-first with separators into a
     * {@code PIC X(08)} field, and L101 moves the result into {@code PA-FRAUD-RPT-DATE}. The TABLE
     * column is database-sourced: the insert names {@code FRAUD_RPT_DATE} in its column list at L166 and
     * supplies {@code CURRENT DATE} positionally at L194, and the duplicate-key update assigns
     * {@code FRAUD_RPT_DATE = CURRENT DATE} at L225. The host variable is bypassed on both paths.</p>
     *
     * <p>Assumptions: the WIDTH discrepancy is the mechanical reason the host variable is bypassed. The
     * segment's field is eight characters at {@code cpy/CIPAUDTY.cpy} L53 while the generated
     * declaration is {@code FRAUD-RPT-DATE PIC X(10)} at {@code dcl/AUTHFRDS.dcl} L84, so eight
     * characters cannot fill the ten the declaration reserves for a date the server writes.</p>
     *
     * <p>Assumptions: the segment stamp is UNCONDITIONAL. L101 executes before the program forks on the
     * requested action, so the segment's date is written on the withdraw path exactly as on the report
     * path. That reads like an oversight and is baseline behaviour, so it is preserved rather than
     * corrected.</p>
     *
     * <p>Refactoring Rationale: the divergence registered as {@code D-AUTH-FRAUD-ONE-CLOCK} in
     * {@code docs/architecture/cobol-to-service-traceability.md} replaces the baseline's TWO clock reads
     * with one. What was wrong with the old approach is that the two reads can disagree -- transiently
     * across midnight, and permanently when the region's zone and the database subsystem's zone differ
     * -- while neither program compares them, so one operator action could leave two dates naming two
     * days. The collapse itself is pinned at the service boundary by {@code FraudMarkingServiceTest};
     * what these cases add is the mapper-level property that makes it possible, namely that the column's
     * value is a required PARAMETER here and the segment's eight characters are never its source.</p>
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
     * at-clause.</p>
     */
    @Nested
    @DisplayName("the two sources of the fraud report date")
    class FraudReportDateProvenance {

        /**
         * The column takes the date it is given and never the eight characters the segment holds.
         *
         * <p>Assumptions: {@code pautdtl1-auth-fraud-domain.bin} is 600 bytes holding three 200-byte
         * records, one per admitted fraud state. Its first record carries {@code F} at offset 174 and the
         * eight characters {@code 04/29/24} at offset 175, which is a date more than two years from the
         * one every case here injects -- so a mapper reading the segment field would produce a visibly
         * different value rather than a coincidentally equal one.</p>
         *
         * <p>Assumptions: the segment's own eight characters are asserted to be STILL THERE afterwards.
         * The claim is that the two values have different sources, not that one displaces the other, and
         * a case that only checked the row's date would also pass if the mapper had cleared the
         * segment.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the row's date is the injected one while the segment keeps its own")
        void theColumnTakesTheInjectedDateAndNotTheSegmentText() {
            byte[] record = recordAt(bytesOf("pautdtl1-auth-fraud-domain.bin"), 0);
            String segmentDate = spanOf(record, FRAUD_REPORT_DATE_OFFSET, FRAUD_REPORT_DATE_WIDTH);
            PendingAuthDetail detail = detailOf("pautdtl1-auth-fraud-domain.bin", 0);

            AuthFraud row = AuthFraudMapper.toFraudRow(detail,
                    new FraudMarkRequest(PendingAuthDetail.FRAUD_REPORTED), FIXTURE_CUSTOMER_ID,
                    DATABASE_CURRENT_DATE);

            assertThat(segmentDate).as("the committed segment's own eight characters")
                    .isEqualTo("04/29/24");
            assertThat(row.getFraudRptDate())
                    .as("auth_fraud.fraud_rpt_date is the date supplied, not the segment's")
                    .isEqualTo(DATABASE_CURRENT_DATE)
                    .isNotEqualTo(AuthFraudMapper.segmentFraudReportDate(segmentDate));
            assertThat(detail.getFraudReportDate())
                    .as("and the segment copy is left exactly as it was found")
                    .isEqualTo(segmentDate);
        }

        /**
         * The two projections of this one date disagree in type and in provenance, by design.
         *
         * <p>Assumptions: the disagreement is asserted directly rather than each side being asserted in
         * isolation. Two cases that each passed alone would not establish that the two values are
         * DIFFERENT things, and the failure this guards against is a future mapper that unified them --
         * which would be wrong in exactly one of the two places, on every row it wrote.</p>
         *
         * <p>Assumptions: the detail side carries characters and the row side carries a date, and the
         * character form is deliberately not ISO-ordered, so a lexical compare on it is not a date
         * compare. That is why the two are compared as a rendered form against a calendar value rather
         * than as two strings.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the detail keeps eight month-first characters where the row keeps a date")
        void theTwoProjectionsDisagreeByDesign() {
            PendingAuthDetail detail = detailOf("pautdtl1-auth-fraud-domain.bin", 0);

            AuthFraud row = AuthFraudMapper.toFraudRow(detail,
                    new FraudMarkRequest(PendingAuthDetail.FRAUD_REPORTED), FIXTURE_CUSTOMER_ID,
                    DATABASE_CURRENT_DATE);

            assertThat(detail.getFraudReportDate())
                    .as("pending_auth_detail.fraud_rpt_date is CHAR(%d), month first",
                            FRAUD_REPORT_DATE_WIDTH)
                    .hasSize(FRAUD_REPORT_DATE_WIDTH);
            assertThat(mappedFieldOf(PendingAuthDetail.class, "fraudReportDate").getType())
                    .as("the detail side holds characters")
                    .isEqualTo(String.class);
            assertThat(mappedFieldOf(AuthFraud.class, "fraudRptDate").getType())
                    .as("while the fraud row holds a calendar date")
                    .isEqualTo(LocalDate.class);
            assertThat(fraudColumnDeclaration("fraud_rpt_date"))
                    .as("and the migration declares that column DATE")
                    .contains("DATE");
            assertThat(AuthFraudMapper.segmentFraudReportDateText(row.getFraudRptDate()))
                    .as("rendering the row's date into the segment form yields eight characters, "
                            + "which is why the ten-character host declaration cannot be filled from it")
                    .hasSize(FRAUD_REPORT_DATE_WIDTH)
                    .isNotEqualTo(detail.getFraudReportDate());
        }

        /**
         * Both write paths require the date, so neither can read a clock of its own.
         *
         * <p>Assumptions: a required parameter is what makes this mapper reproducible. A member that
         * read the current date internally would produce a different row on every run and could not be
         * asserted at all, and the baseline's own column is server-sourced rather than
         * application-sourced, so accepting it is also the faithful shape.</p>
         *
         * <p>Assumptions: two different injected dates are asserted to produce two different rows. A null
         * check alone would pass for an implementation that demanded the argument and then ignored it.</p>
         *
         * <p>This test takes no parameter and returns no value. It asserts that
         * {@link NullPointerException} is raised on both write paths, named here because both
         * expectations sit inside lambdas a documentation gate cannot see into.</p>
         */
        @Test
        @DisplayName("the report date is a required parameter on both write paths and decides the value")
        void theDateIsARequiredParameterOnBothWritePaths() {
            PendingAuthDetail detail = detailOf("pautdtl1-canonical.bin", 0);
            FraudMarkRequest request = new FraudMarkRequest(PendingAuthDetail.FRAUD_REPORTED);
            LocalDate later = DATABASE_CURRENT_DATE.plusDays(1);

            AuthFraud onFirstDate = AuthFraudMapper.toFraudRow(detail, request, FIXTURE_CUSTOMER_ID,
                    DATABASE_CURRENT_DATE);
            AuthFraud onSecondDate = AuthFraudMapper.toFraudRow(detail, request, FIXTURE_CUSTOMER_ID,
                    later);
            AuthFraudMapper.applyFraudState(onFirstDate, request, later);

            assertThat(onSecondDate.getFraudRptDate())
                    .as("the parameter decides the stored value, so two dates give two rows")
                    .isEqualTo(later)
                    .isNotEqualTo(DATABASE_CURRENT_DATE);
            assertThat(onFirstDate.getFraudRptDate())
                    .as("restating an existing row moves the date to the one supplied")
                    .isEqualTo(later);
            assertThatExceptionOfType(NullPointerException.class)
                    .as("a projection cannot leave the column's source unfilled")
                    .isThrownBy(() -> AuthFraudMapper.toFraudRow(detail, request, FIXTURE_CUSTOMER_ID,
                            null));
            assertThatExceptionOfType(NullPointerException.class)
                    .as("nor can a restatement of an existing row")
                    .isThrownBy(() -> AuthFraudMapper.applyFraudState(onFirstDate, request, null));
        }

        /**
         * The withdraw path stamps the date exactly as the report path does.
         *
         * <p>Assumptions: this preserves an unconditional baseline stamp that reads like a defect.
         * {@code cbl/COPAUS2C.cbl} L101 executes before the program forks, so a withdrawal records the
         * day it was withdrawn on rather than clearing the date, and the only program that blanks these
         * two fields is the message path's insert. Treating a withdrawal as a return to the
         * never-examined state would erase the evidence that the authorization was examined at all,
         * which is why the apparent defect is carried rather than corrected.</p>
         *
         * <p>Assumptions: the marker and the date are asserted TOGETHER on the withdraw path, because a
         * row carrying one without the other is a state neither reference program produces.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a withdrawal stamps the report date too, as the reference unconditionally does")
        void theWithdrawPathStampsTheDateAsWell() {
            AuthFraud withdrawn = fraudRowOf("pautdtl1-canonical.bin", 0,
                    PendingAuthDetail.FRAUD_REMOVED);

            assertThat(withdrawn.getAuthFraud())
                    .as("the marker names the state the request asked to end in")
                    .isEqualTo(PendingAuthDetail.FRAUD_REMOVED);
            assertThat(withdrawn.getFraudRptDate())
                    .as("and the date is stamped on this path exactly as on the report path")
                    .isEqualTo(DATABASE_CURRENT_DATE);
            assertThat(AuthFraudMapper.segmentFraudReportDateText(withdrawn.getFraudRptDate()))
                    .as("the segment-equivalent rendering of the same day is the month-first form")
                    .isEqualTo("08/06/26");
        }
    }

    /**
     * The shape of the row this mapper produces: twenty-six columns, in the baseline's order.
     *
     * <p>Assumptions: twenty-six has two independent sources and is neither twenty-five nor
     * twenty-seven. {@code ddl/AUTHFRDS.ddl} carries its columns on L2 through L27 and declares
     * {@code PRIMARY KEY(CARD_NUM,AUTH_TS)} on L28, so the twenty-seven items inside its parenthesis are
     * twenty-six columns plus one key clause. The generated declaration states the figure outright at
     * {@code dcl/AUTHFRDS.dcl} L88 while its own structure at L55 to L86 lists twenty-eight elementary
     * items, because the variable-length merchant name is declared as a group of two -- a binary length
     * field and a twenty-two-character text field. Both discrepancies resolve to twenty-six by different
     * arithmetic, which is why the count is asserted against the mapper's own constant rather than
     * recounted here.</p>
     *
     * <p>Assumptions: {@code ACCT_ID} and {@code CUST_ID} are the LAST two columns, and that is worth
     * asserting because a reader who knows the IMS side expects the opposite -- there the account
     * identifier is the root segment's sequence field. {@code cbl/COPAUS2C.cbl} L138 and L139 move them
     * last, after the fraud marker at L137.</p>
     *
     * <p>Assumptions: the three entities of this context carry THREE DIFFERENT key shapes, so key shape
     * must never be assumed symmetric across them. The summary row's key is a single column, the detail
     * row's is three components -- the account identifier and the two decoded halves of
     * {@code PA-AUTHORIZATION-KEY} -- and the fraud row's is a nested pair of the account number and the
     * composed timestamp.</p>
     *
     * <p>Assumptions: both identifier columns are declared PACKED in the generated structure --
     * {@code ACCT-ID PIC S9(11)V USAGE COMP-3} and {@code CUST-ID PIC S9(9)V USAGE COMP-3} at
     * {@code dcl/AUTHFRDS.dcl} L85 and L86, each with a trailing {@code V} marking an implied decimal
     * point and no decimal places -- even though the customer identifier is plain unsigned display in the
     * summary segment. That is a third representational shift and it is recorded as supporting evidence
     * for dispatching on the destination's declaration rather than the source's; it is not asserted as a
     * further regime change, because the target holds both as whole numbers and no zoned or packed form
     * survives into it.</p>
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
     * at-clause.</p>
     */
    @Nested
    @DisplayName("the twenty-six column projection and its order")
    class FraudRowProjectionShape {

        /**
         * The row declares twenty-six columns, counting the two the nested key contributes.
         *
         * <p>Assumptions: the two halves are counted separately and then summed, because the key's
         * components are declared on the key type rather than on the row. A count taken over the row
         * alone returns twenty-four, and that number is not wrong so much as incomplete -- which is
         * exactly how an accidental twenty-five or twenty-seven would be argued into place.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("twenty-four mapped fields plus the key's two components make twenty-six")
        void theRowDeclaresTwentySixColumns() {
            int onTheRow = mappedFieldsOf(AuthFraud.class).size();
            int onTheKey = mappedFieldsOf(AuthFraudKey.class).size();

            assertThat(onTheKey).as("the nested key contributes the two primary-key columns").isEqualTo(2);
            assertThat(onTheRow + onTheKey)
                    .as("the fraud row's column count, against the mapper's own published constant")
                    .isEqualTo(AuthFraudMapper.FRAUD_ROW_COLUMN_COUNT)
                    .isEqualTo(26);
        }

        /**
         * The two identifiers are the last columns declared, and both are whole numbers.
         *
         * <p>Assumptions: the reflection API reports declared fields in source order for classes this
         * compiler emits, which is what makes the POSITION of the last two readable here. The stored
         * order of the columns themselves is a catalogue fact and belongs to
         * {@code AuthFraudRepositoryIT}; what this case pins is that the entity's declaration still
         * mirrors the reference write order rather than the IMS one.</p>
         *
         * <p>Assumptions: the published view's component order is asserted alongside it because a
         * record's component order is fixed by its declaration and is therefore the stronger of the two
         * statements. The view ends with the same two identifiers.</p>
         *
         * <p>Assumptions: {@code DECIMAL(11)} and {@code DECIMAL(9,0)} both carry no decimal places, so
         * the target holds them as whole numbers rather than as fixed-point values; the migration
         * declares both {@code BIGINT}, which admits every value eleven digit positions can name.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("acct_id and cust_id are declared last and are held as whole numbers")
        void theTwoIdentifiersAreTheLastColumnsAndAreWholeNumbers() {
            List<String> columns = columnNamesOf(AuthFraud.class);
            List<String> viewComponents = new ArrayList<>();
            for (RecordComponent component
                    : AuthFraudMapper.FraudRowView.class.getRecordComponents()) {
                viewComponents.add(component.getName());
            }

            assertThat(columns.subList(columns.size() - 2, columns.size()))
                    .as("the reference write order puts the two identifiers last")
                    .containsExactly("acct_id", "cust_id");
            assertThat(viewComponents.subList(viewComponents.size() - 2, viewComponents.size()))
                    .as("and the published view preserves that order in its own components")
                    .containsExactly("accountId", "customerId");
            assertThat(mappedFieldOf(AuthFraud.class, "acctId").getType())
                    .as("eleven digit positions, held as a whole number")
                    .isEqualTo(Long.class);
            assertThat(mappedFieldOf(AuthFraud.class, "custId").getType())
                    .as("nine digit positions with no decimal places, held as a whole number")
                    .isEqualTo(Long.class);
            assertThat(fraudColumnDeclaration("acct_id")).contains("BIGINT");
            assertThat(fraudColumnDeclaration("cust_id")).contains("BIGINT");
        }

        /**
         * The key is a nested pair naming the whole account number and the composed instant.
         *
         * <p>Assumptions: the stored key carries all sixteen digits and is never masked, because
         * {@code ddl/AUTHFRDS.ddl} L2 and L3 declare both components {@code NOT NULL} and its L28 makes
         * the pair the primary key. Masking a key component would address no row at all. The published
         * view narrows the same value to its last four digits, and both are correct because they answer
         * different questions -- the asymmetry is the point, and the mask itself is asserted by
         * {@code PendingAuthDetailMapperTest} and {@code MapperRenderingExposureTest}.</p>
         *
         * <p>Assumptions: the second component is a native instant rather than characters. The composed
         * text is twenty-three characters and is bound through a {@code PIC X(26)} host variable, but the
         * column it lands in is {@code TIMESTAMP} at {@code ddl/AUTHFRDS.ddl} L3 and {@code TIMESTAMP(6)}
         * in the migration, and {@code cbl/COPAUS2C.cbl} L171 to L172 converts the text on the way in
         * with an explicit format mask.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the key names the whole account number and the composed instant, unmasked")
        void theKeyIsANestedPairCarryingTheWholeAccountNumber() {
            byte[] record = recordAt(bytesOf("pautdtl1-canonical.bin"), 0);
            String storedCard = spanOf(record, CARD_NUMBER_OFFSET, CARD_NUMBER_WIDTH);
            PendingAuthDetail detail = detailOf("pautdtl1-canonical.bin", 0);

            AuthFraudKey key = AuthFraudMapper.fraudRowKey(detail);

            assertThat(columnNamesOf(AuthFraudKey.class))
                    .as("the key's two components, in the order the primary key names them")
                    .containsExactly("card_num", "auth_ts");
            assertThat(key.getCardNum())
                    .as("all %d digits, because a masked key would address no row", CARD_NUMBER_WIDTH)
                    .isEqualTo(storedCard)
                    .hasSize(CARD_NUMBER_WIDTH);
            assertThat(key.getAuthTs())
                    .as("the composed instant, which the detail mapper owns the composition of")
                    .isEqualTo(PendingAuthDetailMapper.authTimestamp(detail))
                    .isEqualTo(LocalDateTime.of(2023, 6, 29, 14, 30, 25, 123_000_000));
            assertThat(mappedFieldOf(AuthFraudKey.class, "authTs").getType())
                    .as("held as an instant rather than as the twenty-three characters it composed from")
                    .isEqualTo(LocalDateTime.class);
            assertThat(AuthFraudMapper.toView(fraudRowOf("pautdtl1-canonical.bin", 0,
                    PendingAuthDetail.FRAUD_REPORTED)).maskedCardNumber())
                    .as("while the published form of the same value shows only its last four digits")
                    .isNotEqualTo(storedCard)
                    .endsWith(storedCard.substring(CARD_NUMBER_WIDTH - 4));
        }

        /**
         * The canonical segment projects onto all twenty-six columns, field by field.
         *
         * <p>Assumptions: {@code pautdtl1-canonical.bin} is one 200-byte image holding a single record.
         * Its documented values are an originating date of {@code 230629} and time of {@code 143025},
         * account number {@code 4000123456789010}, an approving response code, a processing code of
         * {@code 000000}, both money spans at 1234.56, merchant category {@code 5411}, entry mode
         * {@code 05}, match status {@code P} and a blank fraud position with a blank report date.</p>
         *
         * <p>Assumptions: every column is asserted rather than a representative few, because the failure
         * this guards against is a field-order error in the projection -- and a shifted assignment
         * produces plausible values in the wrong members, which only a complete comparison catches. The
         * three values the segment does not carry are the two identifiers and the marker, which arrive as
         * arguments.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("every one of the twenty-six columns carries the value its source declares")
        void theCanonicalSegmentProjectsFieldByField() {
            AuthFraud row = fraudRowOf("pautdtl1-canonical.bin", 0, PendingAuthDetail.FRAUD_REPORTED);

            assertThat(row.getId().getCardNum()).isEqualTo("4000123456789010");
            assertThat(row.getId().getAuthTs())
                    .isEqualTo(LocalDateTime.of(2023, 6, 29, 14, 30, 25, 123_000_000));
            assertThat(row.getAuthType()).isEqualTo("PURC");
            assertThat(row.getCardExpiryDate()).isEqualTo("1227");
            assertThat(row.getMessageType()).isEqualTo("0100  ");
            assertThat(row.getMessageSource()).isEqualTo("POS   ");
            assertThat(row.getAuthIdCode()).isEqualTo("A00001");
            assertThat(row.getAuthRespCode()).isEqualTo("00");
            assertThat(row.getAuthRespReason()).isEqualTo("0000");
            assertThat(row.getProcessingCode()).isEqualTo("000000");
            assertThat(row.getTransactionAmt()).isEqualByComparingTo("1234.56");
            assertThat(row.getApprovedAmt()).isEqualByComparingTo("1234.56");
            assertThat(row.getMerchantCategoryCode()).isEqualTo("5411");
            assertThat(row.getAcqrCountryCode()).isEqualTo("840");
            assertThat(row.getPosEntryMode()).isEqualTo(Short.valueOf((short) 5));
            assertThat(row.getMerchantId()).isEqualTo("MERCH0000000001");
            assertThat(row.getMerchantName()).isEqualTo("ACME HARDWARE         ");
            assertThat(row.getMerchantCity()).isEqualTo("SEATTLE      ");
            assertThat(row.getMerchantState()).isEqualTo("WA");
            assertThat(row.getMerchantZip()).isEqualTo("98101    ");
            assertThat(row.getTransactionId()).isEqualTo("TXN000000000001");
            assertThat(row.getMatchStatus()).isEqualTo("P");
            assertThat(row.getAuthFraud())
                    .as("the marker is the requested action, which the segment does not carry")
                    .isEqualTo(PendingAuthDetail.FRAUD_REPORTED);
            assertThat(row.getFraudRptDate())
                    .as("the report date is the supplied one, which the segment does not carry either")
                    .isEqualTo(DATABASE_CURRENT_DATE);
            assertThat(row.getAcctId()).isEqualTo(FIXTURE_ACCOUNT_ID);
            assertThat(row.getCustId()).isEqualTo(FIXTURE_CUSTOMER_ID);
        }

        /**
         * The key-component guard refuses values no stored key could hold.
         *
         * <p>Assumptions: the guard is on the VALUE and not on the length, because the two components
         * arrive DECODED and a stored complement is the same width as the value it complements.
         * {@code cbl/COPAUA0C.cbl} subtracts from 99999 and 999999999, so those two are the bases and a
         * decoded component cannot exceed either or be negative.</p>
         *
         * <p>Assumptions: the guard admits a value that is in range but improbable, and refuses only what
         * no key could hold. A complement mistaken for a date is inside the range and is therefore NOT
         * refusable here; that mistake is prevented by the shape of the operation rather than by a bound,
         * and the entity's own key type applies the narrower calendar checks.</p>
         *
         * <p>This test takes no parameter and returns no value. It asserts that
         * {@link IllegalArgumentException} is raised for each of the three out-of-range components, named
         * here because the expectations sit inside lambdas a documentation gate cannot see into.</p>
         */
        @Test
        @DisplayName("the key-component guard admits a decoded key and refuses impossible components")
        void theKeyComponentGuardRefusesImpossibleComponents() {
            PendingAuthDetailKey admitted = AuthFraudMapper.detailKey(FIXTURE_ACCOUNT_ID, 23_180,
                    143_025_123);

            assertThat(admitted.getAccountId()).isEqualTo(FIXTURE_ACCOUNT_ID);
            assertThat(admitted.getAuthDate()).as("the decoded Julian date, never the complement")
                    .isEqualTo(23_180);
            assertThat(admitted.getAuthTime()).as("the decoded time of day, never the complement")
                    .isEqualTo(143_025_123);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("no account identifier is negative")
                    .isThrownBy(() -> AuthFraudMapper.detailKey(-1L, 23_180, 143_025_123));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a date key above its five declared digit positions")
                    .isThrownBy(() -> AuthFraudMapper.detailKey(FIXTURE_ACCOUNT_ID, 100_000,
                            143_025_123));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a time key above its nine declared digit positions")
                    .isThrownBy(() -> AuthFraudMapper.detailKey(FIXTURE_ACCOUNT_ID, 23_180,
                            1_000_000_000));
        }
    }

    /**
     * The ordering property that makes the target's mixed-direction index meaningful.
     *
     * <p>Assumptions: {@code ddl/XAUTHFRD.ddl} L3 orders its unique index {@code CARD_NUM ASC, AUTH_TS
     * DESC}, and the target reproduces it as a second catalogue object because a primary-key index is
     * ascending on both of its columns. Equality lookups are served by either; the fraud screen's
     * newest-first scan for one card is served only by the descending one.</p>
     *
     * <p>Assumptions: the index's EXISTENCE and its direction are catalogue facts and are asserted
     * against a live engine by {@code AuthFraudRepositoryIT}, which reads the definition the engine
     * reports. What this class asserts instead is the property of the mapped VALUES that makes such an
     * index answer the question the screen asks -- that two authorizations of one card order by instant
     * in the order they occurred. Recording it here is what stops the requirement from living only in a
     * migration file.</p>
     *
     * <p>Assumptions: the century caveat is real and applies to the baseline's own column. The composed
     * text carries a TWO-DIGIT year, so its lexical order equals its chronological order only within one
     * century; a 1999 authorization and a 2024 one sort by their {@code 99} and {@code 24} prefixes. The
     * target removes the caveat rather than inheriting it, because the migration supplies a pivot that
     * resolves the century when the text becomes an instant -- which is also why the pivot is a migration
     * decision and not a reading of the reference application, whose stored years were never widened.</p>
     *
     * <p>Trade-offs: the key's second component is held as a native instant rather than as the characters
     * the reference application binds. Retaining characters would preserve the bound form byte for byte
     * and would keep the ordering caveat above permanently, since a character column can only compare
     * lexically and the two-digit year would decide the order across a century boundary. Holding an
     * instant costs the byte-level correspondence with the host variable -- which the conversion mask at
     * {@code cbl/COPAUS2C.cbl} L171 to L172 shows the baseline itself did not preserve into the column --
     * and buys an ordering that is correct for every pair of dates. The column was never characters on
     * either side: {@code ddl/AUTHFRDS.ddl} L3 declares {@code TIMESTAMP}.</p>
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
     * at-clause.</p>
     */
    @Nested
    @DisplayName("the ordering the recent-first index depends on")
    class RecentIndexOrdering {

        /**
         * Three authorizations of one card order by instant, so a descending scan answers newest first.
         *
         * <p>Assumptions: {@code pautdtl1-auth-fraud-domain.bin} is 600 bytes holding three 200-byte
         * records. All three carry the same account number and the same originating date {@code 240429},
         * and their times are {@code 140000}, {@code 150000} and {@code 160000}, so the three keys share
         * their first column and differ only in the second -- which is exactly the shape the index's two
         * directions were chosen for.</p>
         *
         * <p>Assumptions: the stored key halves DESCEND as the instants ascend, because the reference
         * application stores them complemented so that an ascending read returns the newest first. That
         * inversion is what the target re-expresses as a descending index, so the two artifacts are one
         * fact recorded twice; the complement arithmetic itself belongs to
         * {@code PendingAuthDetailMapperTest}.</p>
         *
         * <p>Assumptions: the ascending order is asserted to be the OPPOSITE of what the screen needs.
         * Without that half, a case would pass over an implementation that ordered correctly and left the
         * primary key as the only access path.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("one card's keys order by instant, and ascending is the opposite of newest first")
        void oneCardsKeysOrderByInstant() {
            List<AuthFraudKey> keys = new ArrayList<>();
            for (int ordinal = 0; ordinal < 3; ordinal++) {
                keys.add(AuthFraudMapper.fraudRowKey(detailOf("pautdtl1-auth-fraud-domain.bin",
                        ordinal)));
            }
            List<LocalDateTime> instants = new ArrayList<>();
            for (AuthFraudKey key : keys) {
                instants.add(key.getAuthTs());
            }

            assertThat(keys).extracting(AuthFraudKey::getCardNum)
                    .as("all three share the index's first column, so only the second orders them")
                    .containsOnly("4000123456789010");
            assertThat(instants)
                    .as("the three composed instants of one card, one hour apart")
                    .containsExactly(
                            LocalDateTime.of(2024, 4, 29, 14, 0),
                            LocalDateTime.of(2024, 4, 29, 15, 0),
                            LocalDateTime.of(2024, 4, 29, 16, 0))
                    .isSorted();
            assertThat(instants.get(instants.size() - 1))
                    .as("ascending puts the newest last, which is why the second index descends")
                    .isAfter(instants.get(0));
        }

        /**
         * The composed text form orders lexically exactly as it orders chronologically, within a century.
         *
         * <p>Assumptions: this is the property that made the baseline's own descending index over its
         * bound value meaningful, and it holds only because the year is the leading field of the text.
         * The three forms compared here share a century, which is the scope of the claim; the caveat
         * beyond one century is recorded on the enclosing class rather than restated as a case, because
         * this module's committed images carry no pair that straddles a century and a case written on
         * fabricated text would assert the format rather than the images.</p>
         *
         * <p>Assumptions: the composition of the text is {@code PendingAuthDetailMapper}'s and is proved
         * there, including its exact separators and its trailing literal. What is asserted here is a
         * RELATION between two composed values, which no composition case establishes.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the composed text sorts lexically in the order the instants sort chronologically")
        void theComposedTextSortsAsTheInstantsDo() {
            List<String> composed = new ArrayList<>();
            List<LocalDateTime> instants = new ArrayList<>();
            for (int ordinal = 0; ordinal < 3; ordinal++) {
                PendingAuthDetail detail = detailOf("pautdtl1-auth-fraud-domain.bin", ordinal);
                composed.add(PendingAuthDetailMapper.authTimestampText(detail));
                instants.add(PendingAuthDetailMapper.authTimestamp(detail));
            }

            assertThat(composed)
                    .as("the bound text of the three images, in the order the images are stored")
                    .isSorted();
            assertThat(instants).as("and their instants sort the same way").isSorted();
            assertThat(composed.get(0).compareTo(composed.get(2)) < 0)
                    .as("lexically ascending, %s before %s", composed.get(0), composed.get(2))
                    .isEqualTo(instants.get(0).isBefore(instants.get(2)));
        }
    }

    /**
     * The fraud marker's closed domain, its blank state and the two outcome sentences.
     *
     * <p>Assumptions: the domain is closed by exactly two condition names, {@code 88 PA-FRAUD-CONFIRMED
     * VALUE 'F'} at {@code cpy/CIPAUDTY.cpy} L51 and {@code 88 PA-FRAUD-REMOVED VALUE 'R'} at L52.
     * There is NO condition name for a blank, yet a blank is the ordinary state of an authorization no
     * reviewer has touched -- the message path's insert writes one into the position on every
     * authorization it stores. So the domain a stored record can hold is three states while the domain a
     * marking can name is two, and the two must not be conflated.</p>
     *
     * <p>Assumptions: the marker the fraud row records is the REQUESTED ACTION and not the segment's own
     * position. {@code cbl/COPAUS2C.cbl} L137 moves {@code WS-FRD-ACTION} into {@code AUTH-FRAUD}, so
     * the two are separate sources of the same one-character value and a row whose marker came from the
     * segment would report the state the authorization was already in.</p>
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
     * at-clause.</p>
     */
    @Nested
    @DisplayName("the fraud marker domain and the outcome bodies")
    class FraudMarkerDomain {

        /**
         * Each of the three states a stored record can hold reaches the column as it should.
         *
         * <p>Assumptions: {@code pautdtl1-auth-fraud-domain.bin} holds one 200-byte record per admitted
         * state in the order their evidence appears -- {@code F}, then {@code R}, then the blank -- with
         * the fraud position at offset 174 and the report date at offset 175. The third record's report
         * date is blank as well, which is the pairing the insert path writes.</p>
         *
         * <p>Assumptions: a blank becomes SQL {@code NULL} and never a space. Two absent states in one
         * nullable column would mean a predicate written for one silently missed rows holding the other,
         * and the fraud table's column is a bare {@code CHAR(1)} with no check of its own at
         * {@code ddl/AUTHFRDS.ddl} L24.</p>
         *
         * @param state the name of the stored state under test, used to name a failing row
         * @param ordinal the zero-based record position within the committed image
         * @param expected the value the column boundary must produce for that state
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.authorization.mapper.AuthFraudMapperTest#fraudPositionStates")
        @DisplayName("each stored fraud state reaches the column, with the blank becoming no value")
        void eachStoredStateReachesTheColumn(String state, int ordinal, String expected) {
            PendingAuthDetail detail = detailOf("pautdtl1-auth-fraud-domain.bin", ordinal);

            assertThat(AuthFraudMapper.fraudFlagColumn(detail.getAuthFraud()))
                    .as("%s: the value auth_fraud receives from a stored record", state)
                    .isEqualTo(expected);
        }

        /**
         * A blank of any declared width, and an absent value, both become no value.
         *
         * <p>Assumptions: the widths exercised are the one-character position and the eight-character
         * report date, because those are the two blank runs this context actually receives; a blank run of
         * some other width is not a case the segment can produce.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a blank marker and an absent marker are both no value, never a space")
        void aBlankMarkerBecomesNoValue() {
            assertThat(AuthFraudMapper.fraudFlagColumn(" ")).as("the segment's own blank").isNull();
            assertThat(AuthFraudMapper.fraudFlagColumn(
                    " ".repeat(FRAUD_REPORT_DATE_WIDTH))).as("a wider blank run").isNull();
            assertThat(AuthFraudMapper.fraudFlagColumn(null)).as("a column left unset").isNull();
            assertThat(AuthFraudMapper.fraudFlagColumn(PendingAuthDetail.FRAUD_REPORTED))
                    .as("a marked position is carried through unchanged")
                    .isEqualTo(PendingAuthDetail.FRAUD_REPORTED);
            assertThat(AuthFraudMapper.fraudFlagColumn(PendingAuthDetail.FRAUD_REMOVED))
                    .as("as is a withdrawn one")
                    .isEqualTo(PendingAuthDetail.FRAUD_REMOVED);
        }

        /**
         * An out-of-domain fraud position is refused, both at the column boundary and at the decode.
         *
         * <p>Assumptions: {@code pautdtl1-auth-fraud-invalid.bin} is one 200-byte image whose fraud
         * position at offset 174 holds {@code Y}, which no condition name declares. Every other field of
         * that record is valid and in domain -- its neighbour at offset 173 holds a valid {@code P} and
         * both money pad nibbles are correct -- so the record decodes cleanly up to that byte and the
         * refusal is attributable to the fraud position alone. Its Julian date is one day later than its
         * siblings' so that a combined load reaches this check rather than a key collision. The fixture
         * is deliberately invalid and must never be corrected.</p>
         *
         * <p>Refactoring Rationale: passing an out-of-domain character through unchanged is the behaviour
         * this code REPLACED, and the reason it was wrong is recorded on the production member: the
         * character was discarded, the entity presented no value, the check constraint never saw the
         * offending byte, and the row loaded clean while the extract's own record said an authorization
         * had been marked. Normalising it to unmarked and normalising it to reported were both considered
         * and both rejected, because each invents a state the source did not hold and the two invent
         * opposite ones.</p>
         *
         * <p>Assumptions: the database constraint that refuses the same character by name is asserted
         * against a live engine by the fixtures package's recorded-image case, so the two refusals are
         * complementary rather than duplicated -- this one is the mapper's, before any write is
         * attempted.</p>
         *
         * <p>This test takes no parameter and returns no value. It asserts that
         * {@link IllegalArgumentException} is raised on both paths, named here because both expectations
         * sit inside lambdas a documentation gate cannot see into.</p>
         */
        @Test
        @DisplayName("an out-of-domain fraud position is refused rather than normalised or carried")
        void anOutOfDomainPositionIsRefused() {
            byte[] record = recordAt(bytesOf("pautdtl1-auth-fraud-invalid.bin"), 0);

            assertThat(spanOf(record, FRAUD_FLAG_OFFSET, 1))
                    .as("the committed byte at offset %d, which is outside the closed domain",
                            FRAUD_FLAG_OFFSET)
                    .isEqualTo("Y");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the column boundary refuses it and names the domain")
                    .isThrownBy(() -> AuthFraudMapper.fraudFlagColumn("Y"))
                    .withMessageContaining(PendingAuthDetail.FRAUD_REPORTED)
                    .withMessageContaining(PendingAuthDetail.FRAUD_REMOVED);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("and the decode refuses the record, so no such row can be projected at all")
                    .isThrownBy(() -> detailOf("pautdtl1-auth-fraud-invalid.bin", 0));
        }

        /**
         * The row's marker is the state the request names, not the state the segment already held.
         *
         * <p>Assumptions: the second record of the domain image is used because it already carries
         * {@code R}, so a request asking for {@code F} makes the two sources disagree. Written against a
         * record whose stored position already matched the request, the case would pass whichever source
         * the mapper read.</p>
         *
         * <p>Assumptions: the segment's own position is asserted to be UNCHANGED afterwards, because the
         * claim is that the two are separate sources rather than that one overwrites the other. The
         * ten-character display form of the segment's own state is checked for consistency with it; the
         * form's composition is proved by {@code PendingAuthDetailMapperTest} and is not re-derived
         * here.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the marker comes from the requested action while the segment keeps its own")
        void theMarkerComesFromTheRequestedAction() {
            PendingAuthDetail alreadyWithdrawn = detailOf("pautdtl1-auth-fraud-domain.bin", 1);

            AuthFraud row = AuthFraudMapper.toFraudRow(alreadyWithdrawn,
                    new FraudMarkRequest(PendingAuthDetail.FRAUD_REPORTED), FIXTURE_CUSTOMER_ID,
                    DATABASE_CURRENT_DATE);

            assertThat(alreadyWithdrawn.getAuthFraud())
                    .as("the stored position the segment arrived with")
                    .isEqualTo(PendingAuthDetail.FRAUD_REMOVED);
            assertThat(row.getAuthFraud())
                    .as("the row records the state the request asked to end in")
                    .isEqualTo(PendingAuthDetail.FRAUD_REPORTED);
            assertThat(PendingAuthDetailMapper.renderFraudMark(alreadyWithdrawn.getAuthFraud(),
                    alreadyWithdrawn.getFraudReportDate()))
                    .as("the segment's own state still renders as its ten-character display form")
                    .isEqualTo("R-04/29/24")
                    .hasSize(1 + 1 + FRAUD_REPORT_DATE_WIDTH);
            assertThat(PendingAuthDetailMapper.renderFraudMark(
                    detailOf("pautdtl1-auth-fraud-domain.bin", 2).getAuthFraud(),
                    detailOf("pautdtl1-auth-fraud-domain.bin", 2).getFraudReportDate()))
                    .as("while an unmarked authorization renders as the bare separator")
                    .isEqualTo("-")
                    .hasSize(1);
        }

        /**
         * An action outside the closed two-value domain is refused on both write paths.
         *
         * <p>Assumptions: the comparison is CASE-SENSITIVE, because a COBOL condition name matches the
         * bytes of its field against the literal it was declared with, so a lower-case form satisfies
         * neither condition and would still reach the column. A mapper that accepted it would store a
         * character the reference program cannot represent.</p>
         *
         * <p>Assumptions: both write paths are exercised. The insert path and the duplicate-key update
         * path validate independently in the reference program, and a guard on only one of them would
         * leave the other able to write an out-of-domain marker onto a row that already exists.</p>
         *
         * <p>This test takes no parameter and returns no value. It asserts that
         * {@link IllegalArgumentException} is raised for an out-of-domain action and that
         * {@link NullPointerException} is raised for an absent one, both named here because the
         * expectations sit inside lambdas a documentation gate cannot see into.</p>
         */
        @Test
        @DisplayName("an out-of-domain or absent action is refused on the insert and update paths")
        void anOutOfDomainActionIsRefusedOnBothWritePaths() {
            PendingAuthDetail detail = detailOf("pautdtl1-canonical.bin", 0);
            AuthFraud existing = fraudRowOf("pautdtl1-canonical.bin", 0,
                    PendingAuthDetail.FRAUD_REPORTED);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a third character is not an unhandled case but an unrepresentable value")
                    .isThrownBy(() -> AuthFraudMapper.toFraudRow(detail, new FraudMarkRequest("S"),
                            FIXTURE_CUSTOMER_ID, DATABASE_CURRENT_DATE));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a lower-cased action satisfies neither condition name")
                    .isThrownBy(() -> AuthFraudMapper.applyFraudState(existing,
                            new FraudMarkRequest("f"), DATABASE_CURRENT_DATE));
            assertThatExceptionOfType(NullPointerException.class)
                    .as("and a body carrying no action names no state to end in")
                    .isThrownBy(() -> AuthFraudMapper.toFraudRow(detail, new FraudMarkRequest(null),
                            FIXTURE_CUSTOMER_ID, DATABASE_CURRENT_DATE));
            assertThat(existing.getAuthFraud())
                    .as("the refused restatement left the existing row exactly as it was")
                    .isEqualTo(PendingAuthDetail.FRAUD_REPORTED);
        }

        /**
         * The two outcome bodies are the reference sentences, inside the one declared message width.
         *
         * <p>Assumptions: the two sentences are carried verbatim under the migration's rule that
         * user-visible strings are reproduced character for character -- {@code ADD SUCCESS} on the
         * insert path at {@code cbl/COPAUS2C.cbl} L201 and {@code UPDT SUCCESS} on the duplicate-key
         * update path at L232. This mapper only chooses between them, on the same branch the reference
         * program takes when it tests its status at L199 and finds the duplicate-key condition at
         * L203.</p>
         *
         * <p>Assumptions: FIFTY is the one width this response honours, and it is
         * {@code WS-FRD-ACT-MSG PIC X(50)} at {@code cbl/COPAUS2C.cbl} L86. Several message widths
         * coexist in this bounded context and they must not be normalised into one: the screen responses
         * carry a wider field and the house error contract carries a different width again. The bound is
         * read from the response's own declaration rather than restated as a literal here, so there is
         * one place for it to be corrected.</p>
         *
         * <p>Assumptions: there is no failure body to select. A failed write is reported as a non-success
         * status carrying the house error contract, so the reference program's own failure value is not
         * reachable through this member -- a body saying the write failed inside a successful response is
         * a contradiction no caller can resolve.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the insert and update bodies are the reference sentences within fifty characters")
        void theOutcomeBodiesAreTheReferenceSentences() {
            FraudMarkResponse created = AuthFraudMapper.markResponse(true);
            FraudMarkResponse restated = AuthFraudMapper.markResponse(false);

            assertThat(declaredMessageBound())
                    .as("the declared bound on the outcome message")
                    .isEqualTo(50);
            assertThat(created.message()).isEqualTo(FraudMarkResponse.MESSAGE_ADD_SUCCESS);
            assertThat(restated.message()).isEqualTo(FraudMarkResponse.MESSAGE_UPDATE_SUCCESS);
            assertThat(created.message().length()).isLessThanOrEqualTo(declaredMessageBound());
            assertThat(restated.message().length()).isLessThanOrEqualTo(declaredMessageBound());
            assertThat(created.updateStatus())
                    .as("both outcomes are successes, so both carry the one success value")
                    .isEqualTo(FraudMarkResponse.UPDATE_STATUS_SUCCESS)
                    .isEqualTo(restated.updateStatus());
            assertThat(created.message())
                    .as("and the two sentences are distinct, so a caller can tell the paths apart")
                    .isNotEqualTo(restated.message());
        }
    }

    /**
     * The money contract at the fraud row's boundary, which is asserted locally for one reason only.
     *
     * <p>Assumptions: the DECLARATION-level prohibition on binary floating point is INHERITED and is
     * therefore not re-proved here. The shared kernel's third layering rule selects the whole analysed
     * root rather than the money package alone, and a dedicated Surefire execution re-runs it against
     * this module's own compiled classes, so a reflective sweep for a float or a double in this
     * package's members would assert a rule this package already inherits. What inheritance cannot see
     * is the SCALE a value carries, the rounding contract it was produced under and the form it takes on
     * the wire -- those are properties of values and payloads rather than of declared types, and they are
     * what this group asserts.</p>
     *
     * <p>Assumptions: the precision is TWELVE and not eleven. The two amounts are
     * {@code PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L34 and L35, which is twelve digits, and
     * {@code ddl/AUTHFRDS.ddl} L12 and L13 declare {@code DECIMAL(12,2)} to match. The summary segment's
     * amounts are one digit narrower, so borrowing that width here would reject values this column
     * accepts.</p>
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
     * at-clause.</p>
     */
    @Nested
    @DisplayName("the money contract at the fraud row boundary")
    class MoneyAtTheFraudRowBoundary {

        /**
         * Both amounts reach the row as exact decimals at scale two, in twelve-digit columns.
         *
         * <p>Assumptions: the scale is asserted on the VALUE and the precision on the DECLARATION,
         * because those are where each lives. A value's scale is what a comparison and a rendering depend
         * on, while precision is a property of the column and is never observable from one value.</p>
         *
         * <p>Assumptions: the general money contract is the one this boundary depends on -- scale two,
         * rounding half up -- and never the truncating variant reserved for the interest formula, because
         * this bounded context performs no interest arithmetic at all. No rounding actually occurs on
         * this path, since both stored values are already exact at scale two when they are decoded; the
         * contract is asserted because it is what the projection routes through.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("both amounts are exact at scale two and their columns declare twelve digits")
        void bothAmountsAreExactAtScaleTwoInTwelveDigitColumns() {
            AuthFraud row = fraudRowOf("pautdtl1-canonical.bin", 0, PendingAuthDetail.FRAUD_REPORTED);

            assertThat(row.getTransactionAmt())
                    .as("the requested amount, exact fixed point")
                    .isEqualTo(new BigDecimal("1234.56"));
            assertThat(row.getTransactionAmt().scale())
                    .as("scale two, because a rendering and a comparison both depend on it")
                    .isEqualTo(Money.SCALE);
            assertThat(row.getApprovedAmt().scale()).isEqualTo(Money.SCALE);
            assertThat(columnOf(AuthFraud.class, "transactionAmt").precision())
                    .as("twelve digits, matching PIC S9(10)V99 rather than the summary's eleven")
                    .isEqualTo(12);
            assertThat(columnOf(AuthFraud.class, "transactionAmt").scale()).isEqualTo(Money.SCALE);
            assertThat(columnOf(AuthFraud.class, "approvedAmt").precision()).isEqualTo(12);
            assertThat(columnOf(AuthFraud.class, "approvedAmt").scale()).isEqualTo(Money.SCALE);
            assertThat(fraudColumnDeclaration("transaction_amt")).contains("NUMERIC(12,2)");
            assertThat(fraudColumnDeclaration("approved_amt")).contains("NUMERIC(12,2)");
            assertThat(Money.GENERAL_ROUNDING)
                    .as("the contract this boundary routes through is half up")
                    .isEqualTo(RoundingMode.HALF_UP);
        }

        /**
         * The widest amount the source picture admits survives the projection without loss.
         *
         * <p>Assumptions: {@code pautdtl1-amount-ten-integer-digits.bin} is one 200-byte image whose two
         * money spans each hold the widest value {@code PIC S9(10)V99 COMP-3} admits -- ten integer
         * digits and two decimal places, stored as the seven bytes {@code 09 99 99 99 99 99 9C}, whose
         * leading nibble is the zero pad and whose final nibble is the positive sign. It is the only
         * committed image that reaches the boundary of the column's declared magnitude.</p>
         *
         * <p>Assumptions: the value is compared for exact equality including its scale rather than only
         * by magnitude, because a loss here would most plausibly appear as a changed final digit or a
         * dropped decimal place, and both survive a magnitude comparison that rounds.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("ten integer digits and two decimals reach the twelve-digit column intact")
        void theWidestAmountSurvivesTheProjection() {
            AuthFraud row = fraudRowOf("pautdtl1-amount-ten-integer-digits.bin", 0,
                    PendingAuthDetail.FRAUD_REPORTED);

            assertThat(row.getTransactionAmt())
                    .as("the widest requested amount the source picture can carry")
                    .isEqualTo(new BigDecimal("9999999999.99"));
            assertThat(row.getApprovedAmt())
                    .as("fully approved, so the two amounts agree")
                    .isEqualTo(new BigDecimal("9999999999.99"));
            assertThat(row.getTransactionAmt().precision())
                    .as("twelve significant digits, which the column's twelve admit exactly")
                    .isEqualTo(12);
            assertThat(AuthFraudMapper.toView(row).transactionAmount())
                    .as("and the published form carries the same value")
                    .isEqualTo(Money.of("9999999999.99"));
        }

        /**
         * The published amounts cross to JSON as strings rather than as bare numbers.
         *
         * <p>Assumptions: a bare number in a payload is parsed into a binary floating-point value by
         * most clients, which discards exactness at the one boundary a user actually sees. That is why
         * the negative half of this case matters as much as the positive one: a serialiser emitting the
         * right digits without the quotation marks would satisfy a contains-the-digits assertion.</p>
         *
         * <p>Assumptions: only the shared money module is registered on the serialiser, so a passing
         * assertion cannot be explained by some other module's behaviour.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("both published amounts are JSON strings, never bare numbers")
        void thePublishedAmountsCrossAsStrings() {
            AuthFraud row = fraudRowOf("pautdtl1-canonical.bin", 0, PendingAuthDetail.FRAUD_REPORTED);

            String json = MAPPER.writeValueAsString(AuthFraudMapper.toView(row));

            assertThat(json).as("the requested amount, quoted").contains("\"transactionAmount\":\"1234.56\"");
            assertThat(json).as("the approved amount, quoted").contains("\"approvedAmount\":\"1234.56\"");
            assertThat(json)
                    .as("a bare number here would be parsed into a binary floating-point value")
                    .doesNotContain("\"transactionAmount\":1234.56")
                    .doesNotContain("\"approvedAmount\":1234.56");
        }
    }

    /**
     * Supplies the three fraud-position states a stored record can hold, with the value each maps to.
     *
     * <p>Assumptions: the ordinals follow the committed image's own order, which the fixture README
     * records as the order the states' evidence appears -- the two condition names first and then the
     * blank whose only evidence is the absence of a condition name for it.</p>
     *
     * @return one argument triple per stored state, never {@code null}
     */
    private static Stream<Arguments> fraudPositionStates() {
        return Stream.of(
                Arguments.of("reported, the first condition name", 0, PendingAuthDetail.FRAUD_REPORTED),
                Arguments.of("withdrawn, the second condition name", 1, PendingAuthDetail.FRAUD_REMOVED),
                Arguments.of("blank, which no condition name declares", 2, null));
    }

    /**
     * Reads the declared bound on the fraud response's outcome message.
     *
     * <p>Assumptions: the bound is read from the record's own field rather than from a constant of this
     * test, because the response type declares it privately against the reference field width and a
     * literal here would be a second place for it to drift.</p>
     *
     * @return the maximum length the response declares for its message component
     * @throws AssertionError if the response declares no message component, or declares one carrying no
     *     size constraint, either of which means the contract moved rather than that a rule is wrong
     */
    private static int declaredMessageBound() {
        Field message;
        try {
            message = FraudMarkResponse.class.getDeclaredField("message");
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("FraudMarkResponse declares no message component", absent);
        }
        Size bound = message.getAnnotation(Size.class);
        if (bound == null) {
            throw new AssertionError("FraudMarkResponse's message component declares no size bound");
        }
        return bound.max();
    }
}
