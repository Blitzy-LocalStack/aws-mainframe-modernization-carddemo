package com.carddemo.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reporting.dto.ReportRequest;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementTransactionResponse;
import com.carddemo.reporting.dto.TransactionReportLineResponse;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the JSON boundary of the reporting context: that nothing leaks and that no amount loses
 * exactness.
 *
 * <p>Assumptions: the obligations here are the deliberate inverse of the byte-exact emitters beside
 * them, and the inversion is the whole point of separating the two. {@code CobolEditMaskTest} pins
 * bytes into declared column positions; this class pins the absence of any such rendering. The
 * reference pictures the emitters reproduce are declared at L30, L54, L60 and L66 of
 * {@code app/cpy/CVTRA07Y.cpy} and at L113 of {@code app/cbl/CBSTM03A.CBL}. A mask exists so that a
 * 133-column or an 80-column record is byte-identical to a captured artifact. JSON has no column,
 * so a mask in a payload is corruption rather than formatting, and every assertion below is written
 * to fail if one appears.
 *
 * <p>Assumptions: the unit under test is expressed over values rather than over persistence types,
 * so every assertion here is a function of its own arguments. {@link ReportingDtoMapper} takes text,
 * a {@link Money} and a whole number, and returns the payload records it declares; it takes no
 * projection type and returns no entity. That shape is what makes a masking guarantee assertable at
 * all without a database, an application context or a stand-in collaborator, and it is why this
 * class needs none of the three.
 *
 * <p>Alternatives Considered: loading the ten fixed-width fixture records named in
 * {@link #FIXTURE_RECORD_NAMES} from the classpath and decoding a field out of each. Rejected on two
 * independent grounds. A guard whose execution depends on whether a resource happens to be present
 * silently becomes a no-op the moment the resource is absent, and a guard that cannot fail is not a
 * guard -- which is exactly the property every assertion in this class exists to supply. And the
 * mapper's entry points accept values, not records, so a record reader would insert an I/O layer
 * between a fixture byte and an assertion about a projected value without making the assertion any
 * stronger. The inputs below are therefore declared in source at the geometry the copybooks declare,
 * each carrying the path and line it was read from, and the fixture names are bound by exact name so
 * that the properties this class depends on are stated where a fixture author can read them.
 *
 * <p>Assumptions: the required fixture properties are these, and they are the preconditions the
 * assertions below rest on. A zero amount, so the two-place quoted form can be pinned. A negative
 * amount, so the sign is shown to survive as text. An amount at the {@code NUMERIC(11,2)} ceiling of
 * {@code TRAN-AMT PIC S9(09)V99} at L10 of {@code app/cpy/CVTRA05Y.cpy}, and a balance needing the
 * twelfth digit of {@code ACCT-CURR-BAL PIC S9(10)V99} at L7 of {@code app/cpy/CVACT01Y.cpy}, so the
 * two precisions are shown not to be merged. A populated card verification value, so its absence is
 * demonstrated against a real value rather than against nothing. And populated national and
 * government-issued identifiers, so masking is demonstrated to reduce something that was there.
 *
 * <p>Assumptions: {@code app/cpy/CVTRA05Y.cpy} and {@code app/cpy/COSTM01.CPY} are distinct record
 * types and never aliases of one another, and the two fixture files named for them are distinct for
 * the same reason. Both sum to 350 characters and both declare the same fourteen field names, but
 * the key is reordered: {@code TRAN-RECORD} opens with {@code TRAN-ID PIC X(16)} at L5 and places
 * {@code TRAN-CARD-NUM PIC X(16)} at L15, whereas {@code TRNX-RECORD} opens with the 32-character
 * {@code TRNX-KEY} at L21, whose leading component is {@code TRNX-CARD-NUM PIC X(16)} at L22. The
 * geometry therefore differs even where the names agree, and treating either as the other would read
 * a card number out of a description.
 *
 * <p>Alternatives Considered: writing one generic masking helper and applying it to all three
 * sensitive fields. Rejected because the three requirements are genuinely different -- the primary
 * account number keeps its last four characters, the national and government-issued identifiers keep
 * theirs at their own declared widths, and the card verification value must not exist at all -- and
 * a single generic masker is an invitation to apply it to the third one, which would turn a required
 * absence into a masked presence. The three treatments are asserted separately below and are
 * deliberately not unified.
 *
 * <p>Assumptions: the declared-type sweeps below are assertions about the shape of this module's own
 * payload records, and they do not restate an architecture rule. The prohibition on a binary
 * approximation anywhere on the money path is owned, as an executable rule, by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * which the build scans into this module's test run through a dedicated runner execution; that class
 * is not relocated, its rules are not duplicated here, and no local rule engine is declared. What
 * the sweeps here add is different in kind: they close the set of component types this module's
 * records may declare at all, which catches an exact-decimal component that no money-path rule
 * forbids and that nonetheless emits a bare JSON number.
 *
 * <p>Assumptions: a rationale in this file names the field, what is withheld from it, and what a
 * reader of the published form could otherwise recover. A rationale that appealed to safety in the
 * abstract would be unfalsifiable, and an unfalsifiable rationale cannot be reviewed against the
 * declaration it claims to follow -- which is the whole purpose of citing a copybook path and line
 * beside every reduction below. Every claim here is therefore tied to a declared width, a declared
 * precision, a byte range or a named alternative that was rejected.
 *
 * <p>Assumptions: an unreduced value appears in this file only as the argument of the assertion that
 * checks for its absence, and never in an assertion's description. A description is written to the
 * build log when the assertion fails, so putting a card number or a customer identifier into one
 * would publish, on the day something broke, exactly the value the assertion exists to keep out of
 * the payload. The descriptions attached below carry a record and component name or a copybook
 * citation, and the record used for the parameterised sweep renders itself as a label alone for the
 * same reason.
 *
 * <p>Assumptions: the graded return-code rubric that the parity oracle under {@code tests/} uses,
 * and the aggregate warn-level result that suite treats as its passing state, belong to that suite
 * alone. This module's gate is binary: either there are zero violations and every test passes, or
 * the build fails. Nothing in this class reports a tolerated outcome.
 *
 * <p>Assumptions: everything under {@code app/} is reference material and stays byte-identical.
 * Where this module's behaviour differs from the baseline's, the baseline does one thing, this module
 * does another, and the difference is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. No statement in this class describes an
 * edit to a reference artifact, and none is made.
 */
final class ReportingDtoMapperTest {

    /**
     * The ten fixed-width fixture records this class states its preconditions against, by exact name.
     *
     * <p>Assumptions: the names are bound literally rather than derived, because a fixture is
     * identified by its file name and a constructed name would match a file nobody wrote. They live
     * in {@code src/test/resources/fixtures/} and none is authored from this package. The layouts
     * behind them are {@code app/cpy/CVACT01Y.cpy}, {@code app/cpy/CVACT02Y.cpy},
     * {@code app/cpy/CVCUS01Y.cpy}, {@code app/cpy/CVACT03Y.cpy}, {@code app/cpy/CVTRA05Y.cpy},
     * {@code app/cpy/COSTM01.CPY}, {@code app/cpy/CVTRA03Y.cpy}, {@code app/cpy/CVTRA04Y.cpy} and
     * {@code app/cpy/CVTRA01Y.cpy}.
     */
    private static final List<String> FIXTURE_RECORD_NAMES =
            List.of(
                    "acctfile.txt",
                    "carddata.txt",
                    "custfile.txt",
                    "cardxref.txt",
                    "xreffile.txt",
                    "tranfile.txt",
                    "trnxfile.txt",
                    "trantype.txt",
                    "trancatg.txt",
                    "tcatbal.txt");

    /** The binary resource path of the reporting payload package, used to enumerate it. */
    private static final String DTO_RESOURCE_PATH = "com/carddemo/reporting/dto";

    /** The source path of the unit under test, relative to this module's base directory. */
    private static final String MAPPER_SOURCE_PATH =
            "src/main/java/com/carddemo/reporting/mapper/ReportingDtoMapper.java";

    /**
     * The twelve leading characters every synthetic primary account number below shares.
     *
     * <p>Assumptions: one shared prefix across all four card-number paths lets a single assertion
     * prove that no path forwarded an unmasked value, rather than four assertions that each prove it
     * for one path only. Sixteen characters is the width declared three times over, at L5 of
     * {@code app/cpy/CVACT02Y.cpy}, L5 of {@code app/cpy/CVACT03Y.cpy} and L15 of
     * {@code app/cpy/CVTRA05Y.cpy}, so twelve is that width less the four retained characters.
     */
    private static final String WITHHELD_CARD_PREFIX = "111122223333";

    /** The synthetic card-record primary account number, per {@code CARD-NUM} at CVACT02Y L5. */
    private static final String CARD_RECORD_NUMBER = WITHHELD_CARD_PREFIX + "4444";

    /** The synthetic cross-reference account number, per {@code XREF-CARD-NUM} at CVACT03Y L5. */
    private static final String XREF_CARD_NUMBER = WITHHELD_CARD_PREFIX + "5555";

    /** The synthetic transaction card number, per {@code TRAN-CARD-NUM} at CVTRA05Y L15. */
    private static final String TRAN_CARD_NUMBER = WITHHELD_CARD_PREFIX + "6666";

    /** The synthetic statement key card number, per {@code TRNX-CARD-NUM} at COSTM01 L22. */
    private static final String TRNX_CARD_NUMBER = WITHHELD_CARD_PREFIX + "7777";

    /**
     * The synthetic card verification value, three digits per {@code CARD-CVV-CD} at CVACT02Y L7.
     *
     * <p>Assumptions: this value is never handed to the unit under test, because the unit under test
     * offers nowhere to hand it. It exists so that the absence assertions have a concrete value to
     * search a payload for, and its three digits appear nowhere else in this class so that a match
     * would be a genuine leak rather than a coincidence of another field's digits.
     */
    private static final String CARD_VERIFICATION_VALUE = "808";

    /** The synthetic national identifier, nine digits per {@code CUST-SSN} at CVCUS01Y L17. */
    private static final String NATIONAL_IDENTIFIER = "909090909";

    /**
     * The synthetic government-issued identifier, twenty characters per CVCUS01Y L18.
     *
     * <p>Assumptions: {@code CUST-GOVT-ISSUED-ID} is declared {@code PIC X(20)}, an alphanumeric
     * field rather than a numeric one, so a synthetic value is free to open with letters. It does,
     * which also makes the withheld portion searchable as a distinctive string.
     */
    private static final String GOVERNMENT_IDENTIFIER = "GOVTID00000000009494";

    /** The synthetic transaction identifier, sixteen characters per {@code TRAN-ID} at CVTRA05Y L5. */
    private static final String TRANSACTION_IDENTIFIER = "TRANSACTIONID001";

    /** The synthetic account identifier, eleven digits per {@code XREF-ACCT-ID} at CVACT03Y L7. */
    private static final String ACCOUNT_IDENTIFIER = "12345678901";

    /** The synthetic customer identifier, nine digits per {@code CUST-ID} at CVCUS01Y L5. */
    private static final String CUSTOMER_IDENTIFIER = "123456789";

    /** The synthetic type code, two characters per {@code TRAN-TYPE-CD} at CVTRA05Y L6. */
    private static final String TYPE_CODE = "01";

    /** The synthetic category code, four digits per {@code TRAN-CAT-CD} at CVTRA05Y L7. */
    private static final String CATEGORY_CODE = "0001";

    /** The synthetic transaction source, ten characters per {@code TRAN-SOURCE} at CVTRA05Y L8. */
    private static final String TRANSACTION_SOURCE = "BATCHPOSTX";

    /** The synthetic merchant postal code, ten characters per CVTRA05Y L14. */
    private static final String MERCHANT_POSTAL_CODE = "ZIP0000001";

    /** The synthetic merchant identifier, within the nine-digit domain at CVTRA05Y L11. */
    private static final long MERCHANT_IDENTIFIER = 123456789L;

    /**
     * A synthetic 26-character timestamp in the form {@link TimestampFormatter} renders.
     *
     * <p>Assumptions: the form is year, month and day, then a space rather than a letter separator,
     * then the time to six fractional digits and no zone, occupying exactly
     * {@code TimestampFormatter.TIMESTAMP_LENGTH} characters. The reference declarations are
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}, both {@code PIC X(26)} at L16 and L17 of
     * {@code app/cpy/CVTRA05Y.cpy}.
     */
    private static final String ORIGINATING_TIMESTAMP = "2022-07-18 14:32:07.123456";

    /**
     * A synthetic 26-character timestamp whose final two positions are blank by design.
     *
     * <p>Assumptions: this is a legitimate value in the statement pipeline rather than a malformed
     * one. Lines 53 and 54 of {@code app/jcl/CREASTMT.JCL} reshape the transaction record with
     * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}, and a 50-character tail taken from input
     * position 279 covers the whole 26-character origination timestamp plus only the first 24
     * characters of the processing timestamp. The receiving field is still 26 positions wide, so the
     * last two arrive blank.
     */
    private static final String TRUNCATED_PROCESSING_TIMESTAMP = "2022-07-18 14:32:07.1234  ";

    /** A synthetic transaction description, within the 100 characters at CVTRA05Y L9. */
    private static final String TRANSACTION_DESCRIPTION = "SYNTHETIC POSTING FIXTURE RECORD";

    /** A synthetic reference type description, within the 50 characters at CVTRA03Y L6. */
    private static final String TYPE_DESCRIPTION = "SYNTHETIC TYPE DESCRIPTION";

    /** A synthetic reference category description, within the 50 characters at CVTRA04Y L8. */
    private static final String CATEGORY_DESCRIPTION = "SYNTHETIC CATEGORY DESCRIPTION";

    /** A synthetic merchant name, within the 50 characters at CVTRA05Y L12. */
    private static final String MERCHANT_NAME = "SYNTHETIC MERCHANT";

    /** A synthetic merchant city, within the 50 characters at CVTRA05Y L13. */
    private static final String MERCHANT_CITY = "SYNTHETIC CITY";

    /** A synthetic customer forename, within the 25 characters at CUSTREC L6. */
    private static final String FIRST_NAME = "SYNTHETICFIRST";

    /** A synthetic customer middle name, within the 25 characters at CUSTREC L7. */
    private static final String MIDDLE_NAME = "SYNTHETICMIDDLE";

    /** A synthetic customer surname, within the 25 characters at CUSTREC L8. */
    private static final String LAST_NAME = "SYNTHETICLAST";

    /**
     * A report name the reference program itself produces, within the ten characters at CORPT00C L58.
     *
     * <p>Assumptions: the three values {@code app/cbl/CORPT00C.cbl} moves into
     * {@code WS-REPORT-NAME PIC X(10)} are {@code 'Monthly'} at L214, {@code 'Yearly'} at L240 and
     * {@code 'Custom'} at L433, and every one of them is shorter than the field. Choosing one of the
     * three rather than a ten-character invention is what keeps the assertion about the declared
     * maximum from accidentally becoming an assertion about a required length.
     */
    private static final String REPORT_NAME = "Monthly";

    /** A synthetic reporting start date in the ten-character form declared at CORPT00C L72. */
    private static final String START_DATE = "2022-07-18";

    /** A synthetic reporting end date in the ten-character form declared at CORPT00C L72. */
    private static final String END_DATE = "2022-07-31";

    /** An amount of zero, the value whose rendered form this package spells four different ways. */
    private static final Money ZERO_AMOUNT = Money.of("0.00");

    /** A negative amount, so the sign can be shown to survive as text rather than as a mask byte. */
    private static final Money NEGATIVE_AMOUNT = Money.of("-1234.56");

    /** The greatest amount {@code TRAN-AMT PIC S9(09)V99} at CVTRA05Y L10 can hold. */
    private static final Money TRANSACTION_AMOUNT_CEILING = Money.of("999999999.99");

    /** The greatest balance {@code ACCT-CURR-BAL PIC S9(10)V99} at CVACT01Y L7 can hold. */
    private static final Money ACCOUNT_BALANCE_CEILING = Money.of("9999999999.99");

    /**
     * One primary account number path, its provenance, and the form it must be reduced to.
     *
     * @param label the human-readable name of the path being exercised
     * @param provenance the copybook path and line the declared width was read from
     * @param rawValue the synthetic sixteen-character value the path carries upstream
     * @param maskedValue the only form of that value this context is permitted to publish
     */
    private record CardNumberPath(
            String label, String provenance, String rawValue, String maskedValue) {

        /**
         * Renders this path as its label alone, so no unreduced value can reach a report.
         *
         * <p>Assumptions: a parameterised invocation's display name and a failure message are both
         * written to the build log, and a record's generated rendering would put the full sixteen
         * characters declared at L5 of {@code app/cpy/CVACT02Y.cpy} into both. Overriding the
         * rendering is what keeps the value inside the assertion that checks for its absence, which
         * is the only place it belongs.
         *
         * @return the label of this path, carrying no value from it
         */
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Builds a mapper whose only configured behaviour is the money wire contract under test.
     *
     * <p>Assumptions: the module is registered here by hand rather than obtained from an
     * auto-configured context. A contract that holds only because some starter happened to register
     * the module is a contract nobody can rely on when a starter changes, so the registration is made
     * explicit and the assertion below is about the module rather than about any wiring.
     *
     * @return a mapper with {@link MoneyModule} registered and nothing else configured
     */
    private static ObjectMapper jsonMapper() {
        return JsonMapper.builder().addModule(new MoneyModule()).build();
    }

    /**
     * Enumerates every record type declared in the reporting payload package.
     *
     * <p>Assumptions: the package is enumerated from the compiled class files on the test classpath
     * rather than from a list written here, because a list written here would silently stop covering
     * the package the moment a payload type were added to it -- and a component added without a type
     * assertion is exactly the case these assertions exist to catch. The caller checks the result
     * against the four types it knows about, so a lookup that found nothing fails rather than passing
     * on an empty sweep.
     *
     * @return every class declared in {@code com.carddemo.reporting.dto}, package descriptor excluded
     * @throws IOException if the class path cannot be enumerated
     * @throws URISyntaxException if a class path entry is not a usable file location
     * @throws ClassNotFoundException if an enumerated class file cannot be loaded
     */
    private static List<Class<?>> reportingDtoTypes()
            throws IOException, URISyntaxException, ClassNotFoundException {
        List<Class<?>> discovered = new ArrayList<>();
        Enumeration<URL> locations =
                Thread.currentThread().getContextClassLoader().getResources(DTO_RESOURCE_PATH);
        while (locations.hasMoreElements()) {
            File directory = new File(locations.nextElement().toURI());
            File[] entries = directory.listFiles();
            if (entries == null) {
                continue;
            }
            for (File entry : entries) {
                String name = entry.getName();
                if (!name.endsWith(".class") || name.startsWith("package-info")) {
                    continue;
                }
                String simpleName = name.substring(0, name.length() - ".class".length());
                discovered.add(
                        Class.forName(
                                DTO_RESOURCE_PATH.replace('/', '.') + "." + simpleName));
            }
        }
        return List.copyOf(discovered);
    }

    /**
     * Enumerates every payload record the unit under test declares.
     *
     * <p>Assumptions: the nested types are read off the class rather than listed, for the same reason
     * the payload package is enumerated rather than listed. A seventh payload record added later is
     * swept by every assertion below without any of them being edited.
     *
     * @return every record type declared inside {@link ReportingDtoMapper}
     */
    private static List<Class<?>> mapperPayloadTypes() {
        List<Class<?>> payloads = new ArrayList<>();
        for (Class<?> nested : ReportingDtoMapper.class.getDeclaredClasses()) {
            if (nested.isRecord()) {
                payloads.add(nested);
            }
        }
        return List.copyOf(payloads);
    }

    /**
     * Flattens a list of record types into the record components they declare.
     *
     * @param recordTypes the record types whose components are wanted
     * @return every component of every supplied record type, in declaration order per type
     */
    private static List<RecordComponent> componentsOf(List<Class<?>> recordTypes) {
        List<RecordComponent> components = new ArrayList<>();
        for (Class<?> recordType : recordTypes) {
            RecordComponent[] declared = recordType.getRecordComponents();
            if (declared != null) {
                components.addAll(List.of(declared));
            }
        }
        return List.copyOf(components);
    }

    /**
     * Reads the source of the unit under test so a documented decision can be asserted to exist.
     *
     * <p>Assumptions: a Javadoc block is discarded by the compiler and cannot be reached by
     * reflection, so a record that lives in one is only assertable by reading the source. The path is
     * resolved relative to this module's base directory, which is the working directory the test
     * runner uses, and its existence is asserted by the caller so that a changed working directory
     * fails loudly instead of turning the assertion into a no-op.
     *
     * @return the complete source text of {@link ReportingDtoMapper}
     * @throws IOException if the source file cannot be read
     */
    private static String readMapperSource() throws IOException {
        return Files.readString(Path.of(MAPPER_SOURCE_PATH));
    }

    /**
     * Reduces source text to one line with its Javadoc markup removed, so prose can be searched.
     *
     * <p>Assumptions: a documented citation is wrapped in inline code markup and is broken across
     * lines by the source formatter, so a literal search of the raw text would fail on formatting
     * rather than on content. Stripping the continuation asterisks, the inline code markup and the
     * whitespace runs leaves the citation itself, which is the part that has to be present.
     *
     * @param source the raw source text to reduce
     * @return the same text with Javadoc decoration removed and whitespace runs collapsed
     */
    private static String withoutJavadocMarkup(String source) {
        return source.replaceAll("(?m)^\\s*\\*", " ")
                .replace("{@code ", "")
                .replace("}", "")
                .replaceAll("\\s+", " ");
    }

    /**
     * Builds a report detail payload, whose reference band carries no card number at all.
     *
     * <p>Assumptions: no primary account number is supplied because the complete detail roster at
     * L15 to L31 of {@code app/cpy/CVTRA07Y.cpy} declares none -- a transaction identifier at L16, an
     * account identifier at L18, a type code at L20 with its description at L22, a category code at
     * L24 with its description at L26, a source at L28 and an amount at L30. Adding a card number in
     * order to mask it would widen the payload past the band it projects.
     *
     * @param amount the amount the report line carries
     * @return a report detail payload built from the synthetic values declared above
     */
    private static ReportingDtoMapper.ReportTransactionPayload reportLine(Money amount) {
        return ReportingDtoMapper.toReportTransaction(
                TRANSACTION_IDENTIFIER,
                ACCOUNT_IDENTIFIER,
                TYPE_CODE,
                TYPE_DESCRIPTION,
                CATEGORY_CODE,
                CATEGORY_DESCRIPTION,
                TRANSACTION_SOURCE,
                amount);
    }

    /**
     * Builds a statement transaction payload from the unmasked statement key card number.
     *
     * <p>Assumptions: the raw sixteen-character value is passed in deliberately, because a test that
     * supplied an already-reduced value would assert nothing about the reduction. The value's width
     * is {@code TRNX-CARD-NUM PIC X(16)} at L22 of {@code app/cpy/COSTM01.CPY}.
     *
     * @param amount the amount the statement line carries
     * @param processingTimestamp the 26-character processing timestamp to carry unaltered
     * @return a statement transaction payload built from the synthetic values declared above
     */
    private static ReportingDtoMapper.StatementTransactionPayload statementLine(
            Money amount, String processingTimestamp) {
        return ReportingDtoMapper.toStatementTransaction(
                TRNX_CARD_NUMBER,
                TRANSACTION_IDENTIFIER,
                TYPE_CODE,
                CATEGORY_CODE,
                TRANSACTION_SOURCE,
                TRANSACTION_DESCRIPTION,
                amount,
                MERCHANT_IDENTIFIER,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_POSTAL_CODE,
                ORIGINATING_TIMESTAMP,
                processingTimestamp);
    }

    /**
     * Builds a statement summary payload from unmasked identifiers of all three sensitive kinds.
     *
     * <p>Assumptions: the cross-reference card number, the national identifier and the
     * government-issued identifier are all supplied in full, at the widths declared by L5 of
     * {@code app/cpy/CVACT03Y.cpy}, L17 of {@code app/cpy/CVCUS01Y.cpy} and L18 of the same copybook.
     * Supplying all three through one payload is what lets a single serialisation show three
     * different reductions applied side by side.
     *
     * @param currentBalance the account balance the summary carries
     * @return a statement summary payload built from the synthetic values declared above
     */
    private static ReportingDtoMapper.StatementSummaryPayload statementSummary(
            Money currentBalance) {
        return ReportingDtoMapper.toStatementSummary(
                XREF_CARD_NUMBER,
                CUSTOMER_IDENTIFIER,
                ACCOUNT_IDENTIFIER,
                FIRST_NAME,
                MIDDLE_NAME,
                LAST_NAME,
                NATIONAL_IDENTIFIER,
                GOVERNMENT_IDENTIFIER,
                currentBalance);
    }

    /**
     * Builds every payload the unit under test can produce, so a sweep can cover all of them.
     *
     * <p>Assumptions: each of the six payload records is represented, so an absence or leak assertion
     * applied to this list is applied to the whole emitted surface rather than to a sample. The
     * amounts chosen span zero, a negative value and both declared ceilings, so a rendering fault
     * that only shows at one magnitude is still caught.
     *
     * @return one instance of every payload record the mapper declares
     */
    private static List<Object> everyPayload() {
        return List.of(
                reportLine(ZERO_AMOUNT),
                reportLine(NEGATIVE_AMOUNT),
                reportLine(TRANSACTION_AMOUNT_CEILING),
                statementLine(ZERO_AMOUNT, ORIGINATING_TIMESTAMP),
                statementLine(NEGATIVE_AMOUNT, TRUNCATED_PROCESSING_TIMESTAMP),
                statementSummary(ACCOUNT_BALANCE_CEILING),
                statementSummary(NEGATIVE_AMOUNT),
                ReportingDtoMapper.toTransactionType(TYPE_CODE, TYPE_DESCRIPTION),
                ReportingDtoMapper.toTransactionCategory(
                        TYPE_CODE, CATEGORY_CODE, CATEGORY_DESCRIPTION),
                ReportingDtoMapper.toReportRequest(REPORT_NAME, START_DATE, END_DATE));
    }

    /**
     * Serialises every payload into one text, so a single search covers the whole emitted surface.
     *
     * @return the concatenated JSON of every payload the mapper can produce
     * @throws JacksonException if any payload cannot be serialised
     */
    private static String everyPayloadAsJson() throws JacksonException {
        ObjectMapper mapper = jsonMapper();
        StringBuilder combined = new StringBuilder();
        for (Object payload : everyPayload()) {
            combined.append(mapper.writeValueAsString(payload));
        }
        return combined.toString();
    }

    /**
     * Reads one property out of a serialised payload as a JSON node.
     *
     * @param payload the payload to serialise
     * @param property the JSON property name to read back
     * @return the node found at that property, which is a missing node when the property is absent
     * @throws JacksonException if the payload cannot be serialised or its JSON cannot be re-read
     */
    private static JsonNode propertyOf(Object payload, String property) throws JacksonException {
        ObjectMapper mapper = jsonMapper();
        return mapper.readTree(mapper.writeValueAsString(payload)).path(property);
    }

    /**
     * Supplies the four distinct paths by which a primary account number reaches this context.
     *
     * <p>Assumptions: all four are enumerated rather than one taken as representative, because a
     * mapper that reduced one path and forwarded another is worse than one that reduced none -- the
     * reduced path makes the payload look careful while the forwarded one publishes the value in full.
     *
     * @return one argument set per card-number path, each carrying its provenance and expected form
     */
    private static Stream<Arguments> cardNumberPaths() {
        String maskPrefix = String.valueOf(ReportingDtoMapper.MASK_CHARACTER).repeat(12);
        return Stream.of(
                Arguments.of(
                        new CardNumberPath(
                                "card record",
                                "app/cpy/CVACT02Y.cpy L5 CARD-NUM PIC X(16)",
                                CARD_RECORD_NUMBER,
                                maskPrefix + "4444")),
                Arguments.of(
                        new CardNumberPath(
                                "cross reference",
                                "app/cpy/CVACT03Y.cpy L5 XREF-CARD-NUM PIC X(16)",
                                XREF_CARD_NUMBER,
                                maskPrefix + "5555")),
                Arguments.of(
                        new CardNumberPath(
                                "transaction record",
                                "app/cpy/CVTRA05Y.cpy L15 TRAN-CARD-NUM PIC X(16) at 263-278",
                                TRAN_CARD_NUMBER,
                                maskPrefix + "6666")),
                Arguments.of(
                        new CardNumberPath(
                                "statement key leading component",
                                "app/cpy/COSTM01.CPY L22 TRNX-CARD-NUM PIC X(16) at 1-16",
                                TRNX_CARD_NUMBER,
                                maskPrefix + "7777")));
    }

    /**
     * Names the concepts a card verification value could be spelled with, so absence can be searched.
     *
     * <p>Assumptions: the concept is matched by name rather than by type, because the field is a
     * three-digit numeric one at L7 of {@code app/cpy/CVACT02Y.cpy} and is therefore
     * indistinguishable by type from a category code or a credit score. Matching by name is what
     * makes the search able to find a component that was added under any of its usual spellings.
     *
     * @return the lower-case tokens that identify a card verification value in a member name
     */
    private static List<String> cardVerificationTokens() {
        return List.of(
                "cvv", "cvc", "cardverification", "verificationvalue", "verificationcode",
                "securitycode");
    }

    /**
     * Pins every primary account number path to its last four characters and to nothing more.
     *
     * @param path the card-number path under test, with its provenance and its permitted form
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cardNumberPaths")
    @DisplayName("every card-number path is reduced to its last four characters")
    void everyCardNumberPathKeepsOnlyItsLastFourCharacters(CardNumberPath path) {
        String published = ReportingDtoMapper.maskPrimaryAccountNumber(path.rawValue());

        // WHAT: the published form, its width, and the count of characters it still discloses.
        // WHY : Assumptions: the width is retained at sixteen so that a consumer reading into a
        //       declared-width field is not handed something narrower than the field, and the
        //       disclosed count is asserted at four because that is the whole of the concession. Each
        //       path carries the copybook declaration its width was read from, and that citation is
        //       attached to the assertion so a failure names the reference line rather than only the
        //       expected text. Sixteen is PIC X(16), declared identically at app/cpy/CVACT02Y.cpy L5,
        //       app/cpy/CVACT03Y.cpy L5, app/cpy/CVTRA05Y.cpy L15 and app/cpy/COSTM01.CPY L22.
        assertThat(published).as(path.provenance()).isEqualTo(path.maskedValue());
        assertThat(published).hasSize(ReportingDtoMapper.CARD_NUMBER_WIDTH);
        assertThat(published.chars().filter(Character::isDigit).count())
                .isEqualTo(ReportingDtoMapper.VISIBLE_TRAILING_CHARACTERS);

        // WHAT: that the withheld portion is generated rather than shortened or transformed.
        // WHY : Assumptions: a reduced value has to be unreconstructable, not merely shorter, so the
        //       twelve leading positions are asserted to be the mask character throughout. A checksum,
        //       a partial prefix or a length hint in those positions would leave the withheld digits
        //       recoverable, which is the outcome the reduction exists to prevent -- an attacker
        //       holding the published form would otherwise be able to narrow the sixteen-character
        //       value declared at app/cpy/CVACT02Y.cpy L5 to a searchable candidate set.
        assertThat(published.substring(0, 12))
                .isEqualTo(String.valueOf(ReportingDtoMapper.MASK_CHARACTER).repeat(12));
        assertThat(published).doesNotContain(WITHHELD_CARD_PREFIX);
    }

    /**
     * Pins the reduction as unconditional, because this context has no endpoint that may bypass it.
     *
     * @throws NoSuchMethodException if the single-argument reduction entry point is not declared
     */
    @Test
    @DisplayName("the reduction is unconditional and no entry point can switch it off")
    void theCardNumberReductionIsUnconditional() throws NoSuchMethodException {
        // WHAT: that no public entry point takes a second argument capable of selecting a mode.
        // WHY : Assumptions: the architecture reserves an unmasked card view for an administrative
        //       card-detail endpoint, and this context has no such endpoint -- it reads through
        //       read-only cross-schema views under a role holding SELECT and nothing else. There is
        //       therefore no branch to select, and a toggle parameter would create one where the
        //       architecture allows none, so the absence of any switch is asserted rather than
        //       assumed. Alternatives Considered: accepting a caller-supplied flag and defaulting it
        //       to the reducing behaviour; rejected because a default is a decision a call site can
        //       override, and this decision has no legitimate override in this context.
        for (Method method : ReportingDtoMapper.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                assertThat(parameter.getType()).isNotEqualTo(boolean.class);
                assertThat(parameter.getType()).isNotEqualTo(Boolean.class);
            }
        }
        assertThat(ReportingDtoMapper.class.getDeclaredMethod("maskPrimaryAccountNumber", String.class))
                .isNotNull();

        // WHAT: that both payloads carrying a card number publish the reduced form.
        // WHY : Assumptions: each payload is handed the full sixteen-character value declared at
        //       app/cpy/CVACT03Y.cpy L5 and app/cpy/COSTM01.CPY L22, so a payload that published it
        //       unchanged would fail here. Asserting on the constructed payload rather than on the
        //       helper is what proves the reduction happens on the path a caller actually uses.
        assertThat(statementLine(ZERO_AMOUNT, ORIGINATING_TIMESTAMP).cardNumber())
                .isEqualTo(ReportingDtoMapper.maskPrimaryAccountNumber(TRNX_CARD_NUMBER));
        assertThat(statementSummary(ZERO_AMOUNT).cardNumber())
                .isEqualTo(ReportingDtoMapper.maskPrimaryAccountNumber(XREF_CARD_NUMBER));
    }

    /**
     * Pins that no serialised payload and no rendered payload carries a withheld card prefix.
     *
     * @throws JacksonException if a payload cannot be serialised
     */
    @Test
    @DisplayName("no serialised or rendered payload carries the withheld card-number prefix")
    void theWithheldCardPrefixReachesNoPayloadOutput() throws JacksonException {
        String json = everyPayloadAsJson();

        // WHAT: that the twelve withheld characters appear nowhere in the whole emitted surface.
        // WHY : Assumptions: the four synthetic values share one twelve-character prefix precisely so
        //       that this single search covers all four paths at once. Searching for one path's full
        //       value would leave the other three unchecked, and a mapper that reduced one path while
        //       forwarding another would still pass such a search. The four declarations the paths
        //       come from are app/cpy/CVACT02Y.cpy L5, app/cpy/CVACT03Y.cpy L5, app/cpy/CVTRA05Y.cpy
        //       L15 and app/cpy/COSTM01.CPY L22, all PIC X(16).
        assertThat(json).doesNotContain(WITHHELD_CARD_PREFIX);

        // WHAT: that the rendered form of every payload withholds the same characters.
        // WHY : Assumptions: a record's rendered form is what reaches a log line or an exception
        //       message, and it is assembled from the component values rather than from the JSON, so
        //       it is a second and independent exposure path. Nothing in this module logs today; the
        //       point of asserting it is that a line added later cannot publish the value declared at
        //       app/cpy/COSTM01.CPY L22, because by then the component no longer holds it.
        for (Object payload : everyPayload()) {
            assertThat(payload.toString()).doesNotContain(WITHHELD_CARD_PREFIX);
        }
    }

    /**
     * Pins that no reporting type declares a card verification value component under any spelling.
     *
     * @throws IOException if the payload package cannot be enumerated
     * @throws URISyntaxException if a class path entry is not a usable file location
     * @throws ClassNotFoundException if an enumerated class file cannot be loaded
     */
    @Test
    @DisplayName("no reporting type declares a card verification value component")
    void noReportingTypeDeclaresACardVerificationValue()
            throws IOException, URISyntaxException, ClassNotFoundException {
        List<Class<?>> everyRecordType = new ArrayList<>(reportingDtoTypes());
        everyRecordType.addAll(mapperPayloadTypes());

        // WHAT: that the concept is absent from the declared shape of every reporting record.
        // WHY : Alternatives Considered: declaring a component and blanking or masking it, which is
        //       what the two customer identifiers do. Rejected for this one value because the
        //       requirement is absence rather than reduction: a component that exists can be
        //       un-masked by a later edit and still serialise, whereas one that does not exist gives a
        //       serialiser nothing to write at all. The complete rosters at app/cpy/CVTRA05Y.cpy
        //       L5-L18 and app/cpy/COSTM01.CPY L20-L36 declare no such member, so nothing is lost by
        //       having no component for it.
        for (RecordComponent component : componentsOf(List.copyOf(everyRecordType))) {
            String normalised = component.getName().toLowerCase(Locale.ROOT);
            for (String token : cardVerificationTokens()) {
                assertThat(normalised).doesNotContain(token);
            }
        }

        // WHAT: that no mapper entry point offers an argument for the value either.
        // WHY : Assumptions: a component cannot be populated through an entry point that has no
        //       parameter for it, so checking the parameter names closes the second half of the same
        //       guarantee. The module compiles with parameter names retained, which is what makes the
        //       names readable here rather than only their types -- and the types alone would prove
        //       nothing, because the three-digit field at app/cpy/CVACT02Y.cpy L7 is typed exactly
        //       like the four-digit category code at app/cpy/CVTRA05Y.cpy L7.
        for (Method method : ReportingDtoMapper.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                String normalised = parameter.getName().toLowerCase(Locale.ROOT);
                for (String token : cardVerificationTokens()) {
                    assertThat(normalised).doesNotContain(token);
                }
            }
        }
    }

    /**
     * Pins that neither the key nor the value of a card verification value reaches any output.
     *
     * @throws JacksonException if a payload cannot be serialised
     */
    @Test
    @DisplayName("no payload output carries a card verification value key or value")
    void noPayloadOutputCarriesACardVerificationValue() throws JacksonException {
        String json = everyPayloadAsJson();

        // WHAT: that the concrete three-digit value appears in no serialised and no rendered output.
        // WHY : Assumptions: the structural assertion above proves the shape and this one proves the
        //       emitted bytes, and both are kept because they fail for different reasons -- a shape
        //       assertion cannot catch a value smuggled into an unrelated text component, and a
        //       payload assertion cannot catch a component that exists but happened to be empty in
        //       this fixture. CARD-CVV-CD is PIC 9(03) at app/cpy/CVACT02Y.cpy L7, and those three
        //       digits appear nowhere else in this class, so a match here is a leak rather than a
        //       collision with another field.
        assertThat(json).doesNotContain(CARD_VERIFICATION_VALUE);
        for (Object payload : everyPayload()) {
            assertThat(payload.toString()).doesNotContain(CARD_VERIFICATION_VALUE);
        }

        // WHAT: that no serialised property name spells the concept either.
        // WHY : Assumptions: a key with no value still discloses that the record holds the field,
        //       which is information the reduction of the two customer identifiers deliberately does
        //       disclose and this suppression deliberately does not. Reading the property names back
        //       out of the serialised form checks the emitted contract rather than the declared one,
        //       so a naming strategy that renamed a component would not slip past. The two identifiers
        //       that do disclose their existence are PIC 9(09) at app/cpy/CVCUS01Y.cpy L17 and
        //       PIC X(20) at L18; the one that must not is PIC 9(03) at app/cpy/CVACT02Y.cpy L7.
        ObjectMapper mapper = jsonMapper();
        for (Object payload : everyPayload()) {
            for (String property : mapper.readTree(mapper.writeValueAsString(payload)).propertyNames()) {
                String normalised = property.toLowerCase(Locale.ROOT);
                for (String token : cardVerificationTokens()) {
                    assertThat(normalised).doesNotContain(token);
                }
            }
        }
    }

    /**
     * Pins the national and government-issued identifiers as present but reduced, each at its width.
     */
    @Test
    @DisplayName("the two customer identifiers are present but reduced at their declared widths")
    void theTwoCustomerIdentifiersArePresentButReduced() {
        ReportingDtoMapper.StatementSummaryPayload summary = statementSummary(ZERO_AMOUNT);
        String mask = String.valueOf(ReportingDtoMapper.MASK_CHARACTER);

        // WHAT: the reduced form of the national identifier, at its own nine-character width.
        // WHY : Assumptions: CUST-SSN is PIC 9(09) at app/cpy/CVCUS01Y.cpy L17, so nine is the width
        //       and five is nine less the four retained characters. The reduced width is the source
        //       width rather than four, because a consumer reading into the declared field must not be
        //       handed a shorter value; this is the same rule the card number follows at a different
        //       width, and asserting the arithmetic against the constant rather than against a literal
        //       five is what keeps the two from being confused.
        assertThat(summary.nationalIdentifier())
                .isEqualTo(
                        mask.repeat(
                                        ReportingDtoMapper.NATIONAL_IDENTIFIER_WIDTH
                                                - ReportingDtoMapper.VISIBLE_TRAILING_CHARACTERS)
                                + "0909");
        assertThat(summary.nationalIdentifier())
                .hasSize(ReportingDtoMapper.NATIONAL_IDENTIFIER_WIDTH);

        // WHAT: the reduced form of the government-issued identifier, at its own twenty-character width.
        // WHY : Assumptions: CUST-GOVT-ISSUED-ID is PIC X(20) at app/cpy/CVCUS01Y.cpy L18, so sixteen
        //       positions are withheld and four retained. Both identifiers are held encrypted at rest
        //       by the context that owns the record, and this class neither decrypts nor re-encrypts
        //       nor round-trips either one -- its obligation is the return side only, which is why the
        //       assertion is about the published width and content and about nothing else.
        assertThat(summary.governmentIssuedIdentifier())
                .isEqualTo(
                        mask.repeat(
                                        ReportingDtoMapper.GOVERNMENT_IDENTIFIER_WIDTH
                                                - ReportingDtoMapper.VISIBLE_TRAILING_CHARACTERS)
                                + "9494");
        assertThat(summary.governmentIssuedIdentifier())
                .hasSize(ReportingDtoMapper.GOVERNMENT_IDENTIFIER_WIDTH);
    }

    /**
     * Pins that the unreduced customer identifiers reach no serialised or rendered payload.
     *
     * @throws JacksonException if a payload cannot be serialised
     */
    @Test
    @DisplayName("the unreduced customer identifiers reach no payload output")
    void theUnreducedCustomerIdentifiersReachNoPayloadOutput() throws JacksonException {
        String json = everyPayloadAsJson();

        // WHAT: that neither identifier's full value, nor the withheld head of either, is emitted.
        // WHY : Assumptions: asserting the absence of the whole value alone would pass on a payload
        //       that emitted all but the final character, so the withheld head is searched for
        //       separately. The heads are the first five characters of the nine declared at
        //       app/cpy/CVCUS01Y.cpy L17 and the first sixteen of the twenty declared at L18.
        assertThat(json).doesNotContain(NATIONAL_IDENTIFIER);
        assertThat(json).doesNotContain(NATIONAL_IDENTIFIER.substring(0, 5));
        assertThat(json).doesNotContain(GOVERNMENT_IDENTIFIER);
        assertThat(json).doesNotContain(GOVERNMENT_IDENTIFIER.substring(0, 16));
        for (Object payload : everyPayload()) {
            assertThat(payload.toString()).doesNotContain(NATIONAL_IDENTIFIER);
            assertThat(payload.toString()).doesNotContain(GOVERNMENT_IDENTIFIER);
        }
    }

    /**
     * Pins that the three sensitive treatments stay three, rather than collapsing into one.
     */
    @Test
    @DisplayName("three sensitive fields keep three distinct treatments")
    void theThreeSensitiveTreatmentsStayDistinct() {
        ReportingDtoMapper.StatementSummaryPayload summary = statementSummary(ZERO_AMOUNT);
        String mask = String.valueOf(ReportingDtoMapper.MASK_CHARACTER);

        // WHAT: three different published shapes for three fields, and no component for the fourth.
        // WHY : Alternatives Considered: one shared reduction helper applied uniformly to every
        //       sensitive field. Rejected because the three requirements differ -- twelve withheld of
        //       sixteen at app/cpy/CVACT03Y.cpy L5, five of nine at app/cpy/CVCUS01Y.cpy L17 and
        //       sixteen of twenty at L18 -- and because a helper that handled all three would sooner
        //       or later be pointed at the three-digit field at app/cpy/CVACT02Y.cpy L7, which must
        //       have no component at all. Keeping the treatments separate keeps that fourth case
        //       outside the reach of the mechanism entirely.
        assertThat(summary.cardNumber()).hasSize(16).startsWith(mask.repeat(12)).endsWith("5555");
        assertThat(summary.nationalIdentifier())
                .hasSize(9)
                .startsWith(mask.repeat(5))
                .endsWith("0909");
        assertThat(summary.governmentIssuedIdentifier())
                .hasSize(20)
                .startsWith(mask.repeat(16))
                .endsWith("9494");

        // WHAT: that the three withheld lengths are three different numbers, not one repeated.
        // WHY : Assumptions: the widths differ because the source declarations differ, so asserting
        //       that the three counts are distinct is what would fail if a shared helper were
        //       introduced later and quietly reduced all three to one width. Asserting only the
        //       trailing characters would not: an unreduced value of the declared width ends with the
        //       same four characters as its reduced form, so the leading positions are where the
        //       distinction actually lives. The three source declarations are PIC X(16) at
        //       app/cpy/CVACT03Y.cpy L5, PIC 9(09) at app/cpy/CVCUS01Y.cpy L17 and PIC X(20) at
        //       app/cpy/CVCUS01Y.cpy L18, which is why the withheld counts are twelve, five and
        //       sixteen rather than one shared number.
        char maskCharacter = ReportingDtoMapper.MASK_CHARACTER;
        assertThat(summary.cardNumber().chars().filter(each -> each == maskCharacter).count())
                .isEqualTo(12);
        assertThat(
                        summary.nationalIdentifier().chars()
                                .filter(each -> each == maskCharacter)
                                .count())
                .isEqualTo(5);
        assertThat(
                        summary.governmentIssuedIdentifier().chars()
                                .filter(each -> each == maskCharacter)
                                .count())
                .isEqualTo(16);
        assertThat(componentsOf(List.of(ReportingDtoMapper.StatementSummaryPayload.class)))
                .noneMatch(
                        component ->
                                component.getName().toLowerCase(Locale.ROOT).contains("cvv"));
    }

    /**
     * Names the concepts a monetary component could be spelled with, so its type can be checked.
     *
     * @return the lower-case tokens that identify a monetary quantity in a member name
     */
    private static List<String> moneyTokens() {
        return List.of(
                "amount", "amt", "balance", "bal", "total", "limit", "fee", "interest", "credit",
                "debit");
    }

    /**
     * Pins every component of every reporting payload type to text or to the money type.
     *
     * @throws IOException if the payload package cannot be enumerated
     * @throws URISyntaxException if a class path entry is not a usable file location
     * @throws ClassNotFoundException if an enumerated class file cannot be loaded
     */
    @Test
    @DisplayName("every reporting payload component is text or the money type, and nothing else")
    void everyReportingPayloadComponentIsTextOrMoney()
            throws IOException, URISyntaxException, ClassNotFoundException {
        List<Class<?>> discovered = reportingDtoTypes();

        // WHAT: that enumeration actually found the package, before anything is concluded from it.
        // WHY : Assumptions: an exhaustive sweep that discovered nothing would pass every assertion
        //       below without examining a single component, which is the one way a guard like this
        //       fails silently. Checking four types that are known to exist turns an empty sweep
        //       into a failure. These four are a floor rather than the whole package -- the payload
        //       package also declares ReportSubmissionResponse, ReportTotalsResponse and
        //       StatementResponse -- and the enumeration is what keeps every further type covered
        //       without this line being edited.
        assertThat(discovered)
                .contains(
                        ReportRequest.class,
                        StatementRequest.class,
                        StatementTransactionResponse.class,
                        TransactionReportLineResponse.class);

        // WHAT: that the declared type of every component is one the wire format admits, and that no
        //       component can carry a decimal quantity outside the money type.
        // WHY : Alternatives Considered: asserting the serialised value of each monetary component
        //       instead of its declared type. Rejected because the shared module binds its handler to
        //       the money type itself at L271 and L272 of
        //       services/common-lib/src/main/java/com/carddemo/common/money/MoneyModule.java, so a
        //       component holding a correct value under an arbitrary-precision decimal type compiles,
        //       runs, and emits a bare JSON number that looks entirely correct. No value-based
        //       assertion can see that, because the digits are right; only the declared type reveals
        //       it. Admitting a closed set -- rather than listing the types to reject -- keeps the
        //       check closed, so a type nobody thought of fails by default.
        for (RecordComponent component : componentsOf(discovered)) {
            assertThat(admitsOnTheWire(component.getType()))
                    .as(
                            component.getDeclaringRecord().getSimpleName()
                                    + "."
                                    + component.getName()
                                    + " declares "
                                    + component.getType().getName()
                                    + ", which the payload wire format does not admit")
                    .isTrue();
        }
    }

    /**
     * Decides whether a payload component type is one the reporting wire format admits.
     *
     * <p>Four kinds are admitted, and the set is deliberately closed so that a type nobody
     * anticipated fails by default rather than passing unexamined:</p>
     *
     * <ul>
     *   <li>{@link String} -- every textual field, including the fixed-width tokens carried verbatim
     *       from the baseline under transformation rule T8.</li>
     *   <li>{@link Money} -- every monetary quantity, which
     *       {@code services/common-lib/src/main/java/com/carddemo/common/money/MoneyModule.java}
     *       writes as quoted text so no client parses it into a binary floating-point double.</li>
     *   <li>An {@code enum} -- a closed discriminator that Jackson writes as a quoted constant name,
     *       so it reaches the wire as text and cannot carry a quantity at all.
     *       {@code ReportTotalsResponse.Band} is the case in point: it names the three subtotal bands
     *       declared at {@code app/cpy/CVTRA07Y.cpy} L50, L56 and L62.</li>
     *   <li>{@code int} or {@code long} -- an integral count. {@code StatementResponse
     *       .transactionCount} is the case in point: it counts statement lines and is not a monetary
     *       quantity, so it is not in the money path that transformation rule T3 governs.</li>
     * </ul>
     *
     * <p>Assumptions: every decimal and floating-point type is refused, which is the hazard this
     * guard exists for. Alternatives Considered: admitting exactly {@code String} and {@link Money}
     * and nothing else. That was the original spelling, and it was accurate while the payload package
     * held four purely textual-and-monetary records; it now rejects a closed enum discriminator and an
     * integral count, neither of which can express a fractional quantity, so it would have forced two
     * well-grounded payload designs to be rewritten to satisfy a check aimed at a different problem.
     * Trade-offs: admitting {@code int} and {@code long} means an integral count reaches the wire as a
     * bare JSON number. That is accepted because a count has no scale to lose -- the exactness rule
     * protects money, and money is refused here unless it is declared as {@link Money}.</p>
     *
     * @param type the declared type of a payload record component; must not be {@code null}
     * @return {@code true} when the wire format admits the type, {@code false} otherwise
     */
    private static boolean admitsOnTheWire(Class<?> type) {
        return type == String.class
                || type == Money.class
                || type.isEnum()
                || type == int.class
                || type == long.class;
    }

    /**
     * Pins every monetary component of every mapper payload record to the money type by declaration.
     */
    @Test
    @DisplayName("every monetary payload component is declared as the money type")
    void everyMonetaryPayloadComponentIsDeclaredAsMoney() {
        List<Class<?>> payloads = mapperPayloadTypes();

        // WHAT: that enumeration found all six payload records before anything is concluded.
        // WHY : Assumptions: the mapper declares six payload records, so a sweep returning fewer has
        //       lost one and every assertion after it would be reporting on a partial surface. The
        //       count is asserted rather than the names, so adding a seventh record is a deliberate
        //       edit here rather than a silent gap. The six are declared inside
        //       services/reporting-service/src/main/java/com/carddemo/reporting/mapper/ReportingDtoMapper.java.
        assertThat(payloads).hasSize(6);

        // WHAT: that a monetary name always carries the money type, and that no other numeric type
        //       appears anywhere in the payload surface.
        // WHY : Alternatives Considered: naming only the components known to be monetary today.
        //       Rejected because a component added later under a monetary name would then be missed by
        //       the very assertion written to catch it. The two halves cover each other: the first
        //       catches a monetary name given a wrong type, and the closed set of admitted types
        //       catches a monetary value given a name outside the vocabulary. The whole-number
        //       merchant identifier is admitted separately because it is a magnitude with no
        //       fractional positions, declared PIC 9(09) at app/cpy/CVTRA05Y.cpy L11.
        for (RecordComponent component : componentsOf(payloads)) {
            String normalised = component.getName().toLowerCase(Locale.ROOT);
            String location =
                    component.getDeclaringRecord().getSimpleName() + "." + component.getName();
            boolean monetaryName = moneyTokens().stream().anyMatch(normalised::contains);
            if (monetaryName) {
                assertThat(component.getType()).as(location).isEqualTo(Money.class);
            }
            assertThat(component.getType()).as(location).isIn(String.class, Money.class, Long.class);
            assertThat(component.getType()).as(location).isNotEqualTo(BigDecimal.class);
            assertThat(component.getType().isPrimitive()).as(location).isFalse();
        }
    }

    /**
     * Pins the wire form of an amount to quoted plain decimal text at exactly two places.
     *
     * @param amount the plain decimal text to serialise, supplied by the value source
     * @throws JacksonException if the amount cannot be serialised
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "0.00",
                "0.01",
                "-0.01",
                "1234.56",
                "-1234.56",
                "999999999.99",
                "9999999999.99"
            })
    @DisplayName("an amount is quoted plain decimal text at scale two, with no exponent")
    void anAmountIsQuotedPlainDecimalTextAtScaleTwo(String amount) throws JacksonException {
        String json = jsonMapper().writeValueAsString(Money.of(amount));

        // WHAT: the quoting, the exact digits, the absence of an exponent and the absence of grouping.
        // WHY : Trade-offs: text is a less compact and less obviously numeric wire form than a JSON
        //       number, and that cost is accepted because a JSON number is read into an IEEE-754
        //       binary approximation by most clients, which destroys exactness at the one boundary a
        //       user actually reads. The digits would still look right, so the loss is silent. The
        //       largest value here needs twelve digit positions, matching ACCT-CURR-BAL PIC S9(10)V99
        //       at app/cpy/CVACT01Y.cpy L7, and a general decimal-to-text conversion is permitted to
        //       switch to an exponent at some scales, so the absence of one is asserted rather than
        //       assumed.
        assertThat(json).isEqualTo("\"" + amount + "\"");
        assertThat(json).doesNotContain("E").doesNotContain("e");
        assertThat(json).doesNotContain(",");
    }

    /**
     * Pins zero to the one rendering it takes here, out of the four this package spells it with.
     *
     * @throws JacksonException if the amount cannot be serialised
     */
    @Test
    @DisplayName("zero is the quoted two-place form and none of this package's other three")
    void zeroIsTheQuotedTwoPlaceForm() throws JacksonException {
        String json = jsonMapper().writeValueAsString(ZERO_AMOUNT);

        // WHAT: that zero is the quoted two-place form and not any of the other three in this package.
        // WHY : Assumptions: four renderings of zero coexist in this one package and each belongs to a
        //       different owner. The report detail and total regimes at app/cpy/CVTRA07Y.cpy L30 and
        //       L54, L60 and L66 suppress an all-zero item to fifteen blanks, and that is
        //       TransactionReportMapper's obligation. The zero-suppressing statement regime renders
        //       nine blanks then a two-place fraction then a blank sign, and the digit-filled
        //       statement regime PIC 9(9).99- at app/cbl/CBSTM03A.CBL L113 renders zero-filled
        //       digits; those are StatementTextMapper's and StatementHtmlMapper's obligations. This
        //       file owns the fourth and must never be handed one of the other three, because a
        //       blank-suppressed payload property is not a number a client can read at all.
        assertThat(json).isEqualTo("\"0.00\"");
        assertThat(json).isNotEqualTo("\"0\"");
        assertThat(json).isNotEqualTo("\"0.0\"");
        assertThat(json).isNotEqualTo("0.00");
        assertThat(json).isNotEqualTo("\"" + " ".repeat(15) + "\"");
        assertThat(json).isNotEqualTo("\"" + " ".repeat(9) + ".00 \"");
        assertThat(json).isNotEqualTo("\"+0.00\"");
    }

    /**
     * Pins a negative amount to a leading sign carried inside the quotes.
     *
     * @throws JacksonException if the amount cannot be serialised
     */
    @Test
    @DisplayName("a negative amount keeps a leading sign inside the quotes")
    void aNegativeAmountKeepsItsLeadingSignInsideTheQuotes() throws JacksonException {
        String json = jsonMapper().writeValueAsString(NEGATIVE_AMOUNT);

        // WHAT: that the sign leads, sits inside the quotes, and does not trail.
        // WHY : Assumptions: both byte-exact statement regimes place the sign last -- PIC 9(9).99- at
        //       app/cbl/CBSTM03A.CBL L113 and the zero-suppressing form beside it -- and the report
        //       regimes at app/cpy/CVTRA07Y.cpy L30 and L54 place it first inside a fifteen-character
        //       field. Neither position is a JSON position, so the sign here is the one a decimal text
        //       conversion produces, and asserting that it leads is what keeps a trailing-sign regime
        //       from being carried across from a neighbouring file.
        assertThat(json).isEqualTo("\"-1234.56\"");
        assertThat(json).startsWith("\"-");
        assertThat(json).doesNotEndWith("-\"");
    }

    /**
     * Pins an amount to a value equal at scale two after a round trip through the wire form.
     *
     * @throws JacksonException if the amount cannot be serialised or read back
     */
    @Test
    @DisplayName("an amount round-trips to an equal value at scale two")
    void anAmountRoundTripsToAnEqualValueAtScaleTwo() throws JacksonException {
        ObjectMapper mapper = jsonMapper();

        // WHAT: that reading the emitted text back yields a value equal to the one written.
        // WHY : Assumptions: equality is asserted on the value object rather than on the digits,
        //       because the value object's equality is defined at scale two and that is the property a
        //       consumer relies on. Both declared ceilings are round-tripped, so a conversion that
        //       held at a small magnitude and lost a digit at the twelve-position magnitude of
        //       ACCT-CURR-BAL PIC S9(10)V99 at app/cpy/CVACT01Y.cpy L7 would fail here.
        for (Money original :
                List.of(
                        ZERO_AMOUNT,
                        NEGATIVE_AMOUNT,
                        TRANSACTION_AMOUNT_CEILING,
                        ACCOUNT_BALANCE_CEILING)) {
            Money recovered =
                    mapper.readValue(mapper.writeValueAsString(original), Money.class);
            assertThat(recovered).isEqualTo(original);
            assertThat(recovered.amount().scale()).isEqualTo(Money.SCALE);
        }
    }

    /**
     * Pins every monetary property of every serialised payload to a JSON string token.
     *
     * @throws JacksonException if a payload cannot be serialised or its JSON cannot be re-read
     */
    @Test
    @DisplayName("every monetary payload property is a JSON string and never a JSON number")
    void everyMonetaryPayloadPropertyIsAJsonString() throws JacksonException {
        int monetaryPropertiesChecked = 0;

        // WHAT: the token type of each monetary property, discovered from the declared types.
        // WHY : Assumptions: the property names are derived from the record components rather than
        //       written out, so a payload gaining a monetary component is covered without this
        //       assertion being edited. The token type is checked as well as its text because a
        //       serialiser that emitted the right digits as a number would satisfy a text comparison
        //       on the digits alone, and it is the token type that decides whether a client parses the
        //       value into an exact decimal or into a binary approximation. The handler that produces
        //       the string token is bound to the money type itself at L271 and L272 of
        //       services/common-lib/src/main/java/com/carddemo/common/money/MoneyModule.java.
        for (Object payload : everyPayload()) {
            for (RecordComponent component : payload.getClass().getRecordComponents()) {
                if (!component.getType().equals(Money.class)) {
                    continue;
                }
                JsonNode node = propertyOf(payload, component.getName());
                String location =
                        payload.getClass().getSimpleName() + "." + component.getName();
                assertThat(node.isString()).as(location).isTrue();
                assertThat(node.isNumber()).as(location).isFalse();
                assertThat(node.stringValue()).as(location).contains(".");
                assertThat(node.stringValue().length() - node.stringValue().indexOf('.') - 1)
                        .as(location)
                        .isEqualTo(Money.SCALE);
                monetaryPropertiesChecked++;
            }
        }

        // WHAT: that at least one monetary property was actually reached.
        // WHY : Assumptions: the loop above concludes nothing when it finds no monetary component, so
        //       a count is asserted to keep a vacuous pass from reading as a guarded one. Three of the
        //       six payload records carry money, and the payload list exercises each of them more than
        //       once, so the reached count cannot legitimately be small. The two monetary magnitudes
        //       involved are PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy L10 and PIC S9(10)V99 at
        //       app/cpy/CVACT01Y.cpy L7.
        assertThat(monetaryPropertiesChecked).isGreaterThanOrEqualTo(7);
    }

    /**
     * Pins both declared monetary magnitudes as carried intact, at their own separate precisions.
     *
     * @throws JacksonException if a payload cannot be serialised or its JSON cannot be re-read
     */
    @Test
    @DisplayName("both declared monetary ceilings are carried intact at full precision")
    void bothDeclaredMonetaryCeilingsAreCarriedIntact() throws JacksonException {
        // WHAT: that a nine-integer-digit amount and a ten-integer-digit balance both survive whole.
        // WHY : Assumptions: the amount ceiling follows TRAN-AMT PIC S9(09)V99 at
        //       app/cpy/CVTRA05Y.cpy L10 and TRNX-AMT PIC S9(09)V99 at app/cpy/COSTM01.CPY L29, which
        //       is NUMERIC(11,2); the balance ceiling follows ACCT-CURR-BAL PIC S9(10)V99 at
        //       app/cpy/CVACT01Y.cpy L7, which is NUMERIC(12,2). The balance is asserted at thirteen
        //       characters of text because the twelfth digit is the one a merged precision would drop,
        //       and a dropped high-order digit yields a materially smaller number that still reads as
        //       money.
        assertThat(reportLine(TRANSACTION_AMOUNT_CEILING).amount())
                .isEqualTo(TRANSACTION_AMOUNT_CEILING);
        assertThat(propertyOf(reportLine(TRANSACTION_AMOUNT_CEILING), "amount").stringValue())
                .isEqualTo("999999999.99");
        assertThat(statementSummary(ACCOUNT_BALANCE_CEILING).currentBalance())
                .isEqualTo(ACCOUNT_BALANCE_CEILING);
        assertThat(
                        propertyOf(statementSummary(ACCOUNT_BALANCE_CEILING), "currentBalance")
                                .stringValue())
                .isEqualTo("9999999999.99");

        // WHAT: that the plain-text statement's high-order narrowing is not carried into JSON.
        // WHY : Assumptions: the baseline moves the ten-integer-digit balance into the nine-integer-digit
        //       edited field ST-CURR-BAL PIC 9(9).99- at app/cbl/CBSTM03A.CBL L113, by the statement at
        //       L484 of that program. That narrowing is a property of an 80-column fixed-width band and
        //       belongs to the plain-text channel; the JSON channel has no column budget, so it carries
        //       all twelve digit positions. The two output channels therefore differ by design, the
        //       difference is registered in docs/architecture/cobol-to-service-traceability.md, and the
        //       reference program is left exactly as it stands.
        assertThat(
                        propertyOf(statementSummary(ACCOUNT_BALANCE_CEILING), "currentBalance")
                                .stringValue()
                                .indexOf('.'))
                .isEqualTo(ReportingDtoMapper.ACCOUNT_BALANCE_INTEGER_DIGITS);
    }

    /**
     * Pins the two monetary precisions as separate, by showing one bound refuses what the other holds.
     */
    @Test
    @DisplayName("the two monetary precisions are not merged into one shared bound")
    void theTwoMonetaryPrecisionsAreNotMerged() {
        // WHAT: that the two declared integer-digit counts differ by exactly one.
        // WHY : Assumptions: the one-digit gap between PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy L10 and
        //       PIC S9(10)V99 at app/cpy/CVACT01Y.cpy L7 is the whole of the distinction, and the
        //       account record makes the contrast deliberate by declaring five separate twelve-character
        //       fields of the wider kind, at L7, L8, L9, L13 and L14. Both scales are two; only the
        //       precision differs.
        assertThat(
                        ReportingDtoMapper.ACCOUNT_BALANCE_INTEGER_DIGITS
                                - ReportingDtoMapper.TRANSACTION_AMOUNT_INTEGER_DIGITS)
                .isEqualTo(1);

        // WHAT: that a balance-sized value is admitted as a balance and refused as an amount.
        // WHY : Alternatives Considered: one shared monetary bound for the whole context. Rejected
        //       because it is wrong in one direction whichever of the two is kept -- widening the amount
        //       admits a value the nine-integer-digit field at app/cpy/CVTRA05Y.cpy L10 cannot hold and
        //       would silently truncate on the way back, and narrowing the balance refuses a value the
        //       ten-integer-digit field at app/cpy/CVACT01Y.cpy L7 legitimately holds. A refusal on one
        //       path and an acceptance on the other, asserted together, is what makes the separation
        //       executable rather than merely documented.
        assertThat(statementSummary(ACCOUNT_BALANCE_CEILING).currentBalance())
                .isEqualTo(ACCOUNT_BALANCE_CEILING);
        assertThatThrownBy(() -> reportLine(ACCOUNT_BALANCE_CEILING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nine integer digits");
        assertThatThrownBy(
                        () -> statementLine(ACCOUNT_BALANCE_CEILING, ORIGINATING_TIMESTAMP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nine integer digits");
    }

    /**
     * Pins the general monetary contract this class relies on, and that no amount is re-scaled here.
     */
    @Test
    @DisplayName("the general monetary contract is scale two half-up and no amount is re-scaled")
    void theGeneralMonetaryContractIsScaleTwoHalfUp() {
        // WHAT: the scale and the rounding mode of the general contract, and the mode's effect.
        // WHY : Assumptions: the general contract is the one this context uses. Where a reduction to
        //       cents happens anywhere on this path it is scale two, rounding half away from zero, and
        //       a three-place input reduces upward rather than raising. The separate baseline accrual
        //       mode exists because the reference interest paragraph carries no ROUNDED phrase and so
        //       truncates toward zero; that mode belongs to the accrual path in another service and is
        //       asserted here only to be shown different from the one in use, so the two cannot be
        //       conflated by a reader arriving from that service.
        assertThat(Money.SCALE).isEqualTo(2);
        assertThat(Money.GENERAL_ROUNDING).isEqualTo(RoundingMode.HALF_UP);
        assertThat(Money.of(new BigDecimal("1.005"))).isEqualTo(Money.of("1.01"));
        assertThat(Money.BASELINE_INTEREST_ROUNDING).isNotEqualTo(Money.GENERAL_ROUNDING);

        // WHAT: that a payload carries an amount at the scale it arrived with and re-scales nothing.
        // WHY : Assumptions: this class performs no arithmetic, so the order-of-operations rule that
        //       forms a product at full precision before dividing has nothing to reorder here -- but it
        //       is recorded because the rule is what makes a caller's already-computed amount safe to
        //       carry unchanged. Re-rounding on projection would alter a monetary result while claiming
        //       to project it, and the alteration would be invisible in the payload. The scale and mode
        //       relied on are Money.SCALE of two and Money.GENERAL_ROUNDING of HALF_UP, declared at
        //       L173 and L184 of
        //       services/common-lib/src/main/java/com/carddemo/common/money/Money.java.
        Money supplied = Money.of("-1234.56");
        assertThat(reportLine(supplied).amount()).isEqualTo(supplied);
        assertThat(reportLine(supplied).amount().amount().scale()).isEqualTo(Money.SCALE);
    }

    /**
     * Pins that no monetary property of any payload carries a byte-exact edit artifact.
     *
     * @throws JacksonException if a payload cannot be serialised or its JSON cannot be re-read
     */
    @Test
    @DisplayName("no monetary payload property carries an edit-mask artifact")
    void noMonetaryPayloadPropertyCarriesAnEditMaskArtifact() throws JacksonException {
        // WHAT: that no monetary property carries a grouping separator, a fixed sign, a currency
        //       symbol, a blank-suppressed position or a trailing sign.
        // WHY : Assumptions: every artifact searched for here is a real regime in this very package,
        //       which is why the search is specific rather than general. The grouping separator and the
        //       leading plus come from PIC -ZZZ,ZZZ,ZZZ.ZZ at app/cpy/CVTRA07Y.cpy L30 and
        //       PIC +ZZZ,ZZZ,ZZZ.ZZ at L54, L60 and L66; the trailing sign and the blank-suppressed
        //       positions come from the statement regimes, PIC 9(9).99- at app/cbl/CBSTM03A.CBL L113
        //       among them. Those masks exist so a 133-column or an 80-column record matches a captured
        //       artifact byte for byte. A JSON property has no column, so any of them appearing here
        //       would be corruption rather than formatting -- a consumer would have to strip it before
        //       it could parse the value at all.
        for (Object payload : everyPayload()) {
            for (RecordComponent component : payload.getClass().getRecordComponents()) {
                if (!component.getType().equals(Money.class)) {
                    continue;
                }
                String text = propertyOf(payload, component.getName()).stringValue();
                String location =
                        payload.getClass().getSimpleName() + "." + component.getName();
                assertThat(text).as(location).doesNotContain(",");
                assertThat(text).as(location).doesNotContain("+");
                assertThat(text).as(location).doesNotContain("$");
                assertThat(text).as(location).doesNotContain(" ");
                assertThat(text).as(location).doesNotEndWith("-");
                assertThat(text).as(location).isEqualTo(text.trim());
            }
        }

        // WHAT: that a screen-rendering picture declared in the request program is not applied either.
        // WHY : Assumptions: WS-TRAN-AMT PIC +99999999.99 at app/cbl/CORPT00C.cbl L77 is a twelve-character
        //       regime with EIGHT integer digits whose sign always prints and whose leading zeros are
        //       kept, because the picture uses the digit symbol rather than the zero-suppression symbol.
        //       It is narrower than the report band's nine integer digits and it is a terminal-rendering
        //       picture, not a JSON one. It is asserted absent precisely because it is declared in the
        //       very program this context's request shape is read from, which is where someone would
        //       most plausibly pick it up by mistake.
        String amountText = propertyOf(reportLine(ZERO_AMOUNT), "amount").stringValue();
        assertThat(amountText).isEqualTo("0.00");
        assertThat(amountText).isNotEqualTo("+00000000.00");
    }

    /**
     * Pins both statement timestamps as opaque 26-character text carried through byte for byte.
     *
     * @throws JacksonException if the payload cannot be serialised or its JSON cannot be re-read
     */
    @Test
    @DisplayName("timestamps are opaque 26-character text and are never parsed or reformatted")
    void timestampsAreOpaqueTwentySixCharacterText() throws JacksonException {
        ReportingDtoMapper.StatementTransactionPayload line =
                statementLine(ZERO_AMOUNT, TRUNCATED_PROCESSING_TIMESTAMP);

        // WHAT: that both timestamps are 26 characters and identical to the text supplied.
        // WHY : Assumptions: TRAN-ORIG-TS and TRAN-PROC-TS are PIC X(26) at app/cpy/CVTRA05Y.cpy L16
        //       and L17, and TRNX-ORIG-TS and TRNX-PROC-TS at app/cpy/COSTM01.CPY L34 and L35, and the
        //       processing member occupies positions 305 to 330 of the 350-character record. A
        //       26-character alphanumeric member carries no zone, so parsing it into a temporal value
        //       and rendering it back would attach a zone the source never held and would assert a
        //       precision it does not carry -- and the resulting payload would differ from the stored
        //       value for a reason invisible in the payload itself.
        assertThat(line.originatingTimestamp()).hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(line.processingTimestamp()).hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(line.originatingTimestamp()).isEqualTo(ORIGINATING_TIMESTAMP);
        assertThat(line.processingTimestamp()).isEqualTo(TRUNCATED_PROCESSING_TIMESTAMP);

        // WHAT: that the two blank tail positions of the truncated value are preserved, not trimmed.
        // WHY : Assumptions: app/jcl/CREASTMT.JCL L53-L54 reshapes the record so the processing member
        //       receives only its first 24 characters, leaving the last two blank by design. Trimming
        //       them would be indistinguishable from trimming storage padding, yet here the blanks are
        //       part of a value the pipeline deliberately produces, so removing them would report a
        //       precision the record does not have.
        assertThat(line.processingTimestamp()).endsWith("  ");
        assertThat(line.processingTimestamp().trim()).hasSize(24);
        assertThat(propertyOf(line, "processingTimestamp").stringValue())
                .isEqualTo(TRUNCATED_PROCESSING_TIMESTAMP);

        // WHAT: that the shared renderer produces the same 26-character shape it is not asked to
        //       re-derive here.
        // WHY : Assumptions: TimestampFormatter is the producer of this form on the write path, and on
        //       this read path the text already exists and passes through untouched. It is exercised
        //       once to confirm the width contract the payload is validated against is the same width
        //       that class publishes, and it is never applied to a value arriving from a record. That
        //       width is TIMESTAMP_LENGTH of 26 at L210 of
        //       services/common-lib/src/main/java/com/carddemo/common/time/TimestampFormatter.java,
        //       matching PIC X(26) at app/cpy/CVTRA05Y.cpy L16 and L17.
        assertThat(ReportingDtoMapper.renderTimestamp(TimestampFormatter.parse(ORIGINATING_TIMESTAMP)))
                .isEqualTo(ORIGINATING_TIMESTAMP);
    }

    /**
     * Pins the report request to the ten-character name and the ten-character date form declared.
     */
    @Test
    @DisplayName("the report request carries the ten-character name and ten-character date form")
    void theReportRequestCarriesTheDeclaredTenCharacterForms() {
        ReportingDtoMapper.ReportRequestPayload request =
                ReportingDtoMapper.toReportRequest(REPORT_NAME, START_DATE, END_DATE);

        // WHAT: the declared ten-character maximum for the report name, and the values it admits.
        // WHY : Assumptions: WS-REPORT-NAME is PIC X(10) VALUE SPACES at app/cbl/CORPT00C.cbl L58, and
        //       the three values that program moves into it -- Monthly at L214, Yearly at L240 and
        //       Custom at L433 -- are all shorter than the field. Ten therefore bounds the name rather
        //       than fixing it, and demanding exactly ten characters would refuse every name the
        //       reference program actually produces.
        assertThat(ReportingDtoMapper.REPORT_NAME_WIDTH).isEqualTo(10);
        assertThat(request.reportName()).isEqualTo(REPORT_NAME);
        assertThat(request.reportName().length())
                .isLessThanOrEqualTo(ReportingDtoMapper.REPORT_NAME_WIDTH);
        assertThatThrownBy(() -> ReportingDtoMapper.toReportRequest("ELEVENCHARS", START_DATE, END_DATE))
                .isInstanceOf(IllegalArgumentException.class);

        // WHAT: the exact ten-character date form, its two hyphen positions, and its ordering property.
        // WHY : Assumptions: WS-DATE-FORMAT is PIC X(10) VALUE 'YYYY-MM-DD' at app/cbl/CORPT00C.cbl
        //       L72, and the group that program composes each date from at L60 to L65 is a
        //       four-character year, a hyphen filler at L62, a two-character month, a hyphen filler at
        //       L64 and a two-character day; the end date repeats that geometry at L66 to L71. The form
        //       is carried as those ten characters rather than restructured into parts because the
        //       year-first ordering is what makes a text comparison equivalent to a date comparison,
        //       which is the property every range predicate downstream depends on.
        assertThat(ReportingDtoMapper.DATE_FORM).isEqualTo("YYYY-MM-DD");
        assertThat(ReportingDtoMapper.DATE_WIDTH).isEqualTo(10);
        assertThat(request.startDate()).isEqualTo(START_DATE).hasSize(10);
        assertThat(request.endDate()).isEqualTo(END_DATE).hasSize(10);
        assertThat(request.startDate().charAt(4)).isEqualTo('-');
        assertThat(request.startDate().charAt(7)).isEqualTo('-');
        assertThat(request.startDate().compareTo(request.endDate())).isNegative();
        assertThatThrownBy(() -> ReportingDtoMapper.toReportRequest(REPORT_NAME, "18-07-2022", END_DATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Pins that no reporting component corresponds to a padding field in a stored record.
     *
     * @throws IOException if the payload package cannot be enumerated
     * @throws URISyntaxException if a class path entry is not a usable file location
     * @throws ClassNotFoundException if an enumerated class file cannot be loaded
     */
    @Test
    @DisplayName("no reporting component corresponds to a stored-record padding field")
    void noReportingComponentCorrespondsToPadding()
            throws IOException, URISyntaxException, ClassNotFoundException {
        List<Class<?>> everyRecordType = new ArrayList<>(reportingDtoTypes());
        everyRecordType.addAll(mapperPayloadTypes());

        // WHAT: that no component is named for a padding field, across the whole reporting surface.
        // WHY : Assumptions: padding in a stored record is there to reach the declared record length
        //       and carries no value a consumer can use -- the X(20) at app/cpy/CVTRA05Y.cpy L18 and
        //       the X(20) at app/cpy/COSTM01.CPY L36 are the trailing bytes of a 350-character record,
        //       and the X(178) at app/cpy/CVACT01Y.cpy L17 is over half of a 300-character one. A
        //       component for any of them would put record geometry into a payload contract.
        for (RecordComponent component : componentsOf(List.copyOf(everyRecordType))) {
            assertThat(component.getName().toLowerCase(Locale.ROOT))
                    .as(component.getDeclaringRecord().getSimpleName())
                    .doesNotContain("filler")
                    .doesNotContain("padding")
                    .doesNotContain("reserved");
        }
    }

    /**
     * Pins that every dropped padding field is recorded, not merely dropped.
     *
     * @throws IOException if the source of the unit under test cannot be read
     */
    @Test
    @DisplayName("every dropped padding field is recorded by copybook path and line")
    void everyDroppedPaddingFieldIsRecorded() throws IOException {
        // WHAT: that the source of the unit under test is where this assertion expects it.
        // WHY : Assumptions: the path is resolved against this module's base directory, which is the
        //       runner's working directory. Asserting the file exists first means a changed working
        //       directory reports a missing source rather than an empty search that would pass every
        //       content assertion below. The file read is
        //       src/main/java/com/carddemo/reporting/mapper/ReportingDtoMapper.java, relative to
        //       services/reporting-service.
        assertThat(Path.of(MAPPER_SOURCE_PATH)).exists();
        String recorded = withoutJavadocMarkup(readMapperSource());

        // WHAT: that each of the seven dropped padding fields is named with its width, path and line.
        // WHY : Assumptions: the copybook is the normative source, and it asks for the dropping of
        //       padding to be documented per record rather than only performed -- a drop nobody can
        //       find is indistinguishable from a field somebody forgot. Asserting the record's presence
        //       is what makes deleting the record a build failure instead of a silent loss of the
        //       audit trail, which is the whole reason the obligation is worded as documentation. The
        //       widest of the seven is PIC X(178) at app/cpy/CVACT01Y.cpy L17, over half of a
        //       300-character record, and the narrowest is PIC X(04) at app/cpy/CVTRA04Y.cpy L9.
        assertThat(recorded).contains("X(20) at L18 of app/cpy/CVTRA05Y.cpy");
        assertThat(recorded).contains("X(20) at L36 of app/cpy/COSTM01.CPY");
        assertThat(recorded).contains("X(178) at L17 of app/cpy/CVACT01Y.cpy");
        assertThat(recorded).contains("X(14) at L8 of app/cpy/CVACT03Y.cpy");
        assertThat(recorded).contains("X(08) at L7 of app/cpy/CVTRA03Y.cpy");
        assertThat(recorded).contains("X(04) at L9 of app/cpy/CVTRA04Y.cpy");
        assertThat(recorded).contains("X(168) at L23 of app/cpy/CUSTREC.cpy");

        // WHAT: that the opposite treatment of the same word two files away is recorded alongside it.
        // WHY : Assumptions: the identical keyword means padding in a stored input record and means
        //       content in an emitted output record, and this is the most confusable rule in the
        //       package. All 22 of the declarations in app/cpy/CVTRA07Y.cpy carry a VALUE -- column
        //       headings, the hyphen joiners at L21 and L25, the dot leaders at L53, L59 and L65 -- so
        //       the byte-exact report mapper has to emit every one of them. Applying this file's rule
        //       there would delete text the report displays; applying that file's rule here would add
        //       components whose only content is padding. Recording both together is what stops the
        //       two being reconciled into one.
        assertThat(recorded).contains("app/cpy/CVTRA07Y.cpy");
        assertThat(recorded).contains("census is 22");
        assertThat(recorded).contains("carries a VALUE");
    }

    /**
     * Pins that this context renames nothing, because none of the recorded corrections belongs here.
     *
     * @throws IOException if the payload package cannot be enumerated
     * @throws URISyntaxException if a class path entry is not a usable file location
     * @throws ClassNotFoundException if an enumerated class file cannot be loaded
     */
    @Test
    @DisplayName("no reporting component renames a baseline field")
    void noReportingComponentRenamesABaselineField()
            throws IOException, URISyntaxException, ClassNotFoundException {
        List<Class<?>> everyRecordType = new ArrayList<>(reportingDtoTypes());
        everyRecordType.addAll(mapperPayloadTypes());

        // WHAT: that neither the reference spelling nor the target spelling of the three recorded
        //       renamings appears anywhere in this context's payload surface.
        // WHY : Assumptions: exactly three reference spellings are given a different target name
        //       across the whole migration, and every one of them belongs to a different context. The
        //       reference declares ACCT-EXPIRAION-DATE at app/cpy/CVACT01Y.cpy L11 and the account
        //       context publishes its own name for it; the reference declares CARD-EXPIRAION-DATE at
        //       app/cpy/CVACT02Y.cpy L9 and the card context publishes its own; the merchant category
        //       code belongs to the authorization context in the same way. Each reference declaration
        //       stays exactly as it is and each divergence is registered in
        //       docs/architecture/cobol-to-service-traceability.md. None of the three is a reporting
        //       field, so this context renames nothing -- and the absence is a deliberate scope
        //       boundary rather than an omission, because adopting a different name here would give
        //       one concept two names across two services and leave every cross-context lookup on it
        //       ambiguous.
        for (RecordComponent component : componentsOf(List.copyOf(everyRecordType))) {
            String normalised = component.getName().toLowerCase(Locale.ROOT);
            String location =
                    component.getDeclaringRecord().getSimpleName() + "." + component.getName();
            assertThat(normalised).as(location).doesNotContain("expiraion");
            assertThat(normalised).as(location).doesNotContain("expiration");
            assertThat(normalised).as(location).doesNotContain("catagory");
            assertThat(normalised).as(location).doesNotContain("merchantcategory");
        }
    }

    /**
     * Pins the envelope a page of payloads travels in, and the ordinal components it must not have.
     */
    @Test
    @DisplayName("a page of payloads travels in the keyset envelope and carries no ordinal position")
    void aPageOfPayloadsTravelsInTheKeysetEnvelope() {
        List<String> envelopeComponents = new ArrayList<>();
        for (RecordComponent component : PageResponse.class.getRecordComponents()) {
            envelopeComponents.add(component.getName());
        }

        // WHAT: that the envelope names the two ends of a page and whether a further page follows.
        // WHY : Assumptions: the envelope is the shared kernel's contract and no second one is declared
        //       in this package, because two declarations of one contract are two things to keep in
        //       step. Naming the ends is what a subsequent request is verified against, which is why an
        //       exhausted page carries no ends at all. The contract is declared at
        //       services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java.
        assertThat(envelopeComponents).contains("firstKey", "lastKey", "hasNext", "hasPrev");
        assertThat(PageResponse.empty().items()).isEmpty();
        assertThat(PageResponse.empty().firstKey()).isNull();
        assertThat(PageResponse.empty().lastKey()).isNull();
        assertThat(PageResponse.empty().hasNext()).isFalse();

        // WHAT: that the envelope declares no page number, offset, limit or total count.
        // WHY : Alternatives Considered: an ordinal envelope carrying a position and a page size.
        //       Rejected because an ordinal read loses and repeats rows once a concurrent insert shifts
        //       positions between two requests, which changes observable behaviour that a browse by key
        //       does not. Keying by the row's own identity is the direct analogue of the reference
        //       browse, which carried its cursor key forward rather than a position, so the keyset form
        //       preserves behaviour instead of approximating it.
        for (String component : envelopeComponents) {
            String normalised = component.toLowerCase(Locale.ROOT);
            assertThat(normalised).doesNotContain("offset");
            assertThat(normalised).doesNotContain("pagenumber");
            assertThat(normalised).doesNotContain("limit");
            assertThat(normalised).doesNotContain("total");
            assertThat(normalised).doesNotContain("index");
        }

        // WHAT: that the envelope refuses a page whose ends are the raw key columns themselves.
        // WHY : Assumptions: the ends of a page are published to the client, and one of the key columns
        //       in this context is a primary account number -- TRNX-CARD-NUM PIC X(16) is the leading
        //       component of the key at app/cpy/COSTM01.CPY L21 to L22. Handing the raw key forward
        //       would therefore publish in a cursor exactly what every reduction above withholds from a
        //       payload, which is why the refusal is asserted here rather than left to the envelope's
        //       own tests: this is the one place the two contracts meet, and a reduction that a cursor
        //       walks around is not a reduction.
        List<ReportingDtoMapper.ReportTransactionPayload> rows =
                List.of(reportLine(ZERO_AMOUNT), reportLine(NEGATIVE_AMOUNT));
        assertThatThrownBy(
                        () ->
                                PageResponse.ofRows(
                                        rows,
                                        TRANSACTION_IDENTIFIER,
                                        TRANSACTION_IDENTIFIER,
                                        true,
                                        false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Pins the unit under test as read-direction only, owning no schema and writing nothing back.
     */
    @Test
    @DisplayName("the mapper is read-direction only and maps nothing back into a persistence type")
    void theMapperIsReadDirectionOnly() {
        List<Class<?>> payloads = mapperPayloadTypes();

        // WHAT: that every published entry point returns text or a payload record, and nothing else.
        // WHY : Assumptions: this context owns no tables. It reads through read-only cross-schema views
        //       under a role holding SELECT and nothing else, so a mapping that produced a persistence
        //       type would produce something this context has no privilege to store -- the failure would
        //       surface at a grant check far from the mapping that caused it. The closed set is applied
        //       to the published surface rather than to every declared member, because the internal
        //       validation helpers legitimately hand values back to their own callers and are not a
        //       contract any consumer can reach. The views and their by-name SELECT grants are
        //       established by data-migration/sql/V1__reporting_views.sql, after
        //       data-migration/sql/V0__schemas_and_roles.sql has revoked every source-schema privilege
        //       from the service role.
        for (Method method : ReportingDtoMapper.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            Class<?> returned = method.getReturnType();
            boolean admitted = returned.equals(String.class) || payloads.contains(returned);
            assertThat(admitted).as(method.getName() + " returns " + returned.getSimpleName()).isTrue();
            assertThat(method.getName())
                    .doesNotStartWith("save")
                    .doesNotStartWith("write")
                    .doesNotStartWith("persist")
                    .doesNotStartWith("insert")
                    .doesNotStartWith("update")
                    .doesNotStartWith("delete");
        }

        // WHAT: that no member of the class, published or internal, touches a projection type.
        // WHY : Assumptions: the projection types this context reads are declared in a sibling package,
        //       and one of them exposes only a no-argument constructor at protected visibility, so a
        //       member bound to it could not be exercised without the persistence provider at all. This
        //       sweep covers every declared member rather than only the published ones, because an
        //       internal helper taking a projection would couple the class to that package just as
        //       firmly while being invisible on the published surface. The constructor in question is
        //       at L441 of
        //       services/reporting-service/src/main/java/com/carddemo/reporting/domain/StatementTransactionView.java.
        for (Method method : ReportingDtoMapper.class.getDeclaredMethods()) {
            assertThat(method.getReturnType().getName())
                    .as(method.getName() + " return type")
                    .doesNotContain("com.carddemo.reporting.domain");
            for (Parameter parameter : method.getParameters()) {
                assertThat(parameter.getType().getName())
                        .as(method.getName() + " parameter " + parameter.getName())
                        .doesNotContain("com.carddemo.reporting.domain");
            }
        }

        // WHAT: that shape is validated while meaning is not, shown on a range that is out of order.
        // WHY : Assumptions: an end date earlier than a start date is a business question about a run,
        //       and this class is an anti-corruption layer rather than a service, so it admits the pair
        //       and leaves the question to whoever owns it. Both values still satisfy the ten-character
        //       form declared at app/cbl/CORPT00C.cbl L72, which is the only thing asserted at this
        //       boundary. Evaluating the rule here would put one decision in two places and let the two
        //       disagree.
        ReportingDtoMapper.ReportRequestPayload reversed =
                ReportingDtoMapper.toReportRequest(REPORT_NAME, END_DATE, START_DATE);
        assertThat(reversed.startDate()).isEqualTo(END_DATE);
        assertThat(reversed.endDate()).isEqualTo(START_DATE);
    }

    /**
     * Pins the ten fixture record names this class states its preconditions against.
     */
    @Test
    @DisplayName("the ten fixture record names are bound exactly and none is authored here")
    void theTenFixtureRecordNamesAreBoundExactly() {
        // WHAT: that ten names are bound, that they are distinct, and that two easily confused ones
        //       remain separate.
        // WHY : Assumptions: the transaction record at app/cpy/CVTRA05Y.cpy and the reporting record at
        //       app/cpy/COSTM01.CPY both sum to 350 characters and declare the same fourteen field
        //       names, but their keys are reordered -- the first opens with the transaction identifier
        //       at L5 and holds the card number at L15, the second opens with a 32-character key at L21
        //       whose leading component is the card number at L22. Two fixture files are therefore
        //       required rather than one, and binding both names here is what stops a later reader
        //       treating either as the other and reading a card number out of a description.
        assertThat(FIXTURE_RECORD_NAMES).hasSize(10).doesNotHaveDuplicates();
        assertThat(FIXTURE_RECORD_NAMES).contains("tranfile.txt", "trnxfile.txt");
        assertThat(FIXTURE_RECORD_NAMES).allSatisfy(name -> assertThat(name).endsWith(".txt"));
    }
}
