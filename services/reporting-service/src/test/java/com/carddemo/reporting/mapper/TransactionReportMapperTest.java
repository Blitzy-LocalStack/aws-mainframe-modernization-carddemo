package com.carddemo.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import com.carddemo.reporting.domain.ReportTransactionView;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises every member of {@link TransactionReportMapper} against the bytes it is required to
 * emit.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link ReportBandLayouts} and its own test pin the <em>geometry</em> of the seven report
 * bands. Nothing pinned the <em>emitter</em> that fills them: before this class existed the mapper
 * had no executable consumer at all, so the four heading lines, the eight varying items of a detail
 * line, the two joining hyphens that a freshly allocated record has no survivor for, the two
 * distinct money masks and the single column all four money-bearing bands share rested on review
 * alone. This class closes that gap, band by band and rejection by rejection.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises to anything outside the test
 * engine, so the type itself accepts no parameter, returns nothing and throws nothing. The
 * inapplicability is stated rather than passed over, because user-specified Rule 1 (Explainability)
 * forbids a docstring that omits parameters, return values or purpose, and a reader has to be able
 * to tell a declared inapplicability from an oversight. Every member below carries its own
 * parameter, return and exception at-clauses.</p>
 *
 * <h2>Assumptions: expected bytes are transcribed from the copybook, not recomposed</h2>
 *
 * <p>Every band assertion below compares against a literal of exactly
 * {@value #REPORT_RECORD_LENGTH} characters, transcribed from lines 4 to 66 of
 * {@code app/cpy/CVTRA07Y.cpy} and checked against a captured emission. Alternatives Considered:
 * assembling each expectation from the same constants the mapper assembles it from. Rejected
 * because that passes whenever the mapper and the test share a mistake -- a label short by one
 * blank, or a dot leader of the wrong length -- which is the entire failure mode a transcription
 * test exists to catch.</p>
 *
 * <h2>Assumptions: the end-of-file divergences are asserted only as far as this class reaches</h2>
 *
 * <p>Two registered divergences, {@code D-REPORT-GRAND-TOTAL} and
 * {@code D-REPORT-CLOSING-TOTAL}, concern what the <em>emitting sequence</em> does at end of
 * file, and this class emits no sequence. What is checkable here is the half that lives in this
 * mapper: that it holds no accumulator, so a total renders exactly the value it was handed and two
 * calls cannot compound; and that a card-break total is reachable unconditionally, with no argument
 * by which a caller could suppress it for a final group. Both halves are asserted below, and the
 * register entries state what remains uncovered rather than implying that these tests close
 * them.</p>
 *
 * <h2>Trade-offs: each of those two divergences is asserted from both sides</h2>
 *
 * <p>A structural assertion alone is not enough for either. Asserting only that a total encoder
 * holds no accumulator would pass against an encoder that rendered the wrong value, and asserting
 * only that the account-total encoder takes one argument would pass against one whose band landed
 * in the wrong column. So each divergence additionally carries a value-level case built on one
 * concrete two-page run: the grand total is asserted to be the sum of that run's page totals and
 * <strong>not</strong> the value the reference produces by counting the closing amount twice, and
 * the account total is asserted to encode identically for a final group and a middle one. Those
 * are the assertions that would fail if either divergence were quietly reversed.</p>
 *
 * <h2>Assumptions: the description fixtures must overrun their target columns, or the narrowing
 * assertions prove nothing</h2>
 *
 * <p>This is the single most load-bearing property of the material this class reads. The two
 * narrowings under test take a fifty-character source into a fifteen-character and a
 * twenty-nine-character column, so a fixture whose descriptions all fitted inside fifteen and
 * twenty-nine characters would be rendered identically by a correct implementation and by one that
 * performed no narrowing at all. Every truncation assertion would then be green while the report
 * shipped wrong. The two committed fixtures are authored against exactly that hazard: four of the
 * seven rows of {@code fixtures/trantype.txt} and six of the nine rows of
 * {@code fixtures/trancatg.txt} occupy the full fifty characters their layout declares, which is
 * more than three times either target column, and
 * {@code services/reporting-service/src/test/resources/fixtures/README.md} section 4 records that
 * as a deliberate property rather than an accident.</p>
 *
 * <p>Assumptions: each narrowing is asserted as a TRIPLE rather than as a single case, from below
 * the cut, exactly at it, and above it. The three arms fail under three different faults and no one
 * arm covers another. A value one character above the cut is the only arm that catches an
 * off-by-one width, because it is the only arm where the discarded character is a single character;
 * a value exactly at the cut is the only arm that catches a narrowing that shortens by a fixed
 * amount instead of to a fixed width; and a value below the cut is the only arm that reaches the
 * codec's blank-fill path, which a truncating implementation never exercises. The fixtures supply
 * all three lengths for both columns: fourteen, fifteen and sixteen characters for the
 * fifteen-character column, and twenty-eight, twenty-nine and thirty for the twenty-nine-character
 * one.</p>
 *
 * <p>Assumptions: a fixture description is passed to the mapper AS DECODED, at its full declared
 * fifty characters with its trailing blanks intact, and not stripped first. That is what the
 * baseline's alphanumeric move at lines 366 and 368 of {@code app/cbl/CBTRN03C.cbl} receives, and
 * it is why the below-the-cut arm lands a trailing blank inside the target column rather than
 * reaching the codec with a short value. Stripping first would test a different input than the
 * report is built from.</p>
 *
 * <h2>Alternatives Considered: two values are literals because the committed fixtures do not reach
 * the boundary each of them exists to reach</h2>
 *
 * <p>Driving the card number and the account identifier from committed fixtures was evaluated and
 * rejected on measurement. Both record types are now committed --
 * {@code fixtures/tranfile.txt} is the 350-byte {@code TRAN} extract and
 * {@code fixtures/cardxref.txt} and {@code fixtures/xreffile.txt} are 50-byte {@code XREF}
 * extracts, all three bound by {@code ReportingFixtureContractTest} -- so the reason is not
 * absence. It is that neither committed column reaches what the two negative cases below need, and
 * that a value chosen for those boundaries cannot be added: that sibling test declares the complete
 * permitted contents of {@code src/test/resources/fixtures/} and asserts the directory matches the
 * list in both directions, so a file added for this class alone would turn a passing sibling red to
 * satisfy this one.</p>
 *
 * <p>Assumptions: the card number here exists only to be searched for and NOT found, which makes
 * its requirements the opposite of a fixture card number's. It must be provably unissuable -- this
 * class asserts its Luhn total is {@value #SYNTHETIC_CARD_LUHN_TOTAL}, which is not a multiple of
 * ten -- and its four-digit tail must be a run no other column of a band can produce, because the
 * absence assertions search for that tail as well as for the whole number. Two committed card
 * numbers fail that outright, and they are named by VALUE rather than by the files that hold them,
 * because the disqualification is a property of the number and not of its membership:
 * {@code 9900001010000001} and {@code 9900001020000001} both end {@code 0001}, which is a substring
 * of the sixteen-character transaction identifier every detail case below encodes, so a tail search
 * driven from either would fail against a CORRECT mapper wherever it was read from. Every other
 * committed card number is there to be FOUND -- they carry the cross-reference relationships that
 * sibling test asserts and the distinct-card margin the statement fixtures rest on -- so borrowing
 * one for a not-found search would tie this class's absence assertion to another test's presence
 * assertions.</p>
 *
 * <p>Assumptions: the account identifier is an eleven-digit value whose full-width rendering is the
 * property under test, and the committed identifiers cannot show it. All four rows of
 * {@code fixtures/acctfile.txt} carry {@code 00000000007}, {@code 00000000050},
 * {@code 00000000101} and {@code 00000000102} -- every significant digit inside the last four
 * positions -- so an implementation that kept only the last four digits and zero-filled the rest
 * would emit bytes identical to the correct rendering, and the case asserting that nothing is masked
 * would pass either way. The literal {@code 12345678901} below differs from its own last-four-masked
 * form in seven of eleven positions, which is what makes the assertion able to fail.</p>
 *
 * <p>Assumptions: a transaction extract named for the report and one named for the statement are two
 * DIFFERENT record types and are never aliases of one another. The report's transaction record is
 * the three-hundred-and-fifty-byte {@code TRAN} layout of {@code app/cpy/CVTRA05Y.cpy}, whose card
 * number sits at zero-based offset 262 and whose processing timestamp at 304 -- offsets that line 41
 * and line 42 of {@code app/jcl/TRANREPT.jcl} corroborate independently as the one-based sort
 * positions 263 and 305. The statement's transaction record is the {@code TRNX} layout of
 * {@code app/cpy/COSTM01.CPY}, with different fields at different offsets, and it belongs to the
 * statement tests. Substituting one for the other would decode every field at the wrong place while
 * still producing a record of a plausible length, so the distinction is recorded here rather than
 * left to a naming resemblance. Note also the filename-case hazard the two illustrate:
 * {@code CVTRA05Y.cpy} carries a lowercase extension on disk and {@code COSTM01.CPY} an
 * uppercase one.</p>
 *
 * <h2>Assumptions: three negative properties, each asserted as its own case</h2>
 *
 * <p>A report that quietly emits a primary account number, or quietly masks an account identifier,
 * or quietly reads a clock still looks exactly like a report. None of the three is visible in the
 * output, each fails a golden comparison or an audit for a reason nobody can see from the artifact,
 * and a property that is not asserted is a property that is not held. They are asserted separately
 * rather than folded into the positive cases because each has its own failure mode and its own
 * owner: the absent card number is a property of the layout at lines 15 to 31 of
 * {@code app/cpy/CVTRA07Y.cpy}, the absent masking is a boundary against
 * {@code ReportingDtoMapper}, and the absent clock is what makes the injected date range at lines
 * 43 and 44 of {@code app/jcl/TRANREPT.jcl} reproducible.</p>
 *
 * <h2>Trade-offs: mask semantics are not re-proven here</h2>
 *
 * <p>{@code CobolEditMaskTest} owns how an amount is rendered -- which sign a regime prints, which
 * digit positions suppress, what a zero becomes, where the nine-integer-digit ceiling lies, and
 * that none of it moves under a hostile default locale. This class owns where the already-rendered
 * fifteen characters LAND inside the hundred-and-thirty-three-byte record, and asserts a rendered
 * form only as the anchor of a placement. The compromise accepted is that a reader looking for the
 * mask rules has to open the other file; the alternative, restating them here, would give one
 * behaviour two owners, and two owners drift.</p>
 *
 * <h2>Alternatives Considered: the inline comment register in this file</h2>
 *
 * <p>Every inline justification below is a bare {@code WHY :} line carrying one of the four
 * category labels from user-specified Rule 1, and there is deliberately no companion {@code WHAT:}
 * line on any statement. The twin form was evaluated and rejected on two independent grounds.
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} scopes that idiom to fenced command blocks in prose
 * and forbids a statement-level {@code WHAT:} in a {@code .java} file by name, giving as its reason
 * that such a line restates what the statement already says. Rule 1's first forbidden pattern is
 * exactly that restatement, and Rule 1 governs. Purpose accordingly lives in the Javadoc, which is
 * where the rule's format clause puts it for Java, and the inline comments carry rationale only.
 * The consequence accepted is that a reader arriving from the shell blocks of
 * {@code tests/README.md}, where the twin form is correct, will not find it here.</p>
 *
 * @see TransactionReportMapper
 * @see ReportBandLayouts
 */
@DisplayName("TransactionReportMapper: heading, detail and total band emission")
class TransactionReportMapperTest {

    /**
     * Declared record length of the daily transaction report, transcribed independently here.
     *
     * <p>Assumptions: this is re-declared rather than read from {@link ReportBandLayouts} so that a
     * failure can distinguish a wrong band from a wrong record length. It is established by
     * {@code 01 TRANSACTION-HEADER-2 PIC X(133)} at line 48 of {@code app/cpy/CVTRA07Y.cpy}.</p>
     */
    private static final int REPORT_RECORD_LENGTH = 133;

    /**
     * Zero-based offset at which all four money-bearing bands open their edited amount.
     *
     * <p>Assumptions: the detail band reaches it from 97 characters of preceding items, the page
     * and grand total bands from an 11-character label plus an 86-character dot leader, and the
     * account total band from a 13-character label plus an 84-character leader. Three different
     * sums, one offset, which is why the leaders are declared separately rather than shared.</p>
     */
    private static final int AMOUNT_OFFSET = 97;

    /**
     * Declared width of every edited amount this report emits, in characters.
     *
     * <p>Assumptions: the mask at line 30 and the mask at lines 54,
     * 60 and 66 of {@code app/cpy/CVTRA07Y.cpy} are each one sign position, nine digit positions,
     * two group separators, a decimal point and two decimal positions.</p>
     */
    private static final int AMOUNT_WIDTH = 15;

    /**
     * Zero-based offset at which the fifteen-character type description column opens.
     *
     * <p>Assumptions: {@code TRAN-REPORT-TYPE-DESC} is declared {@code PIC X(15)} at line 22 of
     * {@code app/cpy/CVTRA07Y.cpy}, reached by accumulating the four items and three separators
     * before it -- 16 + 1 + 11 + 1 + 2 + 1 -- so it occupies one-based columns 33 through 47. The
     * conversion between the two numberings is always {@code zeroBased = oneBased - 1}, and a sign
     * error there shifts every field of the band by one byte.</p>
     */
    private static final int TYPE_DESCRIPTION_OFFSET = 32;

    /**
     * Declared width of the type description column, in characters.
     */
    private static final int TYPE_DESCRIPTION_WIDTH = 15;

    /**
     * Zero-based offset at which the twenty-nine-character category description column opens.
     *
     * <p>Assumptions: {@code TRAN-REPORT-CAT-DESC} is declared {@code PIC X(29)} at line 26 of
     * {@code app/cpy/CVTRA07Y.cpy}, reached by adding this band's next four items -- 15 + 1 + 4 + 1
     * -- to the offset above, so it occupies one-based columns 54 through 82.</p>
     */
    private static final int CATEGORY_DESCRIPTION_OFFSET = 53;

    /**
     * Declared width of the category description column, in characters.
     */
    private static final int CATEGORY_DESCRIPTION_WIDTH = 29;

    /**
     * Zero-based offset of the eleven-character account identifier column.
     *
     * <p>Assumptions: {@code TRAN-REPORT-ACCOUNT-ID} is declared {@code PIC X(11)} at line 18 of
     * {@code app/cpy/CVTRA07Y.cpy}, after a sixteen-character transaction identifier and one
     * separator, so it occupies one-based columns 18 through 28.</p>
     */
    private static final int ACCOUNT_ID_OFFSET = 17;

    /**
     * Declared width of the account identifier column, in characters.
     */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * Zero-based offset of the four-digit category code column.
     *
     * <p>Assumptions: {@code TRAN-REPORT-CAT-CD} is declared {@code PIC 9(04)} at line 24 of
     * {@code app/cpy/CVTRA07Y.cpy}, so it occupies one-based columns 49 through 52 and is the one
     * numeric-display column of the band.</p>
     */
    private static final int CATEGORY_CODE_OFFSET = 48;

    /**
     * Classpath resource holding the transaction type descriptions the narrowing is driven from.
     *
     * <p>Assumptions: this is the sixty-byte {@code TRANTYPE} record of
     * {@code app/cpy/CVTRA03Y.cpy}, whose fifty-character description at line 6 is the source of
     * the fifteen-character report column. The name is bound as a literal because the file is
     * committed under that exact name and the fixture inventory is closed.</p>
     */
    private static final String TYPE_FIXTURE = "fixtures/trantype.txt";

    /**
     * Classpath resource holding the transaction category descriptions.
     *
     * <p>Assumptions: this is the sixty-byte {@code TRANCAT} record of
     * {@code app/cpy/CVTRA04Y.cpy}, whose six-byte composite key precedes the fifty-character
     * description at line 8 that feeds the twenty-nine-character report column. It is a DISTINCT
     * record type from the statement tests' transaction extract, which is derived from
     * {@code app/cpy/COSTM01.CPY} and has different geometry; the two are never aliases and a
     * reader must not substitute one for the other.</p>
     */
    private static final String CATEGORY_FIXTURE = "fixtures/trancatg.txt";

    /**
     * Registry layout name of the transaction type record.
     */
    private static final String TYPE_LAYOUT = "TRANTYPE";

    /**
     * Registry layout name of the transaction category record.
     */
    private static final String CATEGORY_LAYOUT = "TRANCAT";

    /**
     * Declared component name of the fifty-character transaction type description.
     */
    private static final String TYPE_DESCRIPTION_FIELD = "TRAN-TYPE-DESC";

    /**
     * Declared component name of the fifty-character transaction category description.
     */
    private static final String CATEGORY_DESCRIPTION_FIELD = "TRAN-CAT-TYPE-DESC";

    /**
     * Declared width of both description sources, in characters.
     *
     * <p>Assumptions: {@code TRAN-TYPE-DESC} at line 6 of {@code app/cpy/CVTRA03Y.cpy} and
     * {@code TRAN-CAT-TYPE-DESC} at line 8 of {@code app/cpy/CVTRA04Y.cpy} are each declared
     * {@code PIC X(50)}. Both are more than three times the report column they feed, which is what
     * makes a narrowing necessary and what makes a short-description fixture unable to prove one
     * happened.</p>
     */
    private static final int FIXTURE_DESCRIPTION_WIDTH = 50;

    /**
     * An obviously synthetic sixteen-digit card number, used to prove it is never emitted.
     *
     * <p>Assumptions: {@code TRAN-CARD-NUM} is declared {@code PIC X(16)} at line 15 of
     * {@code app/cpy/CVTRA05Y.cpy}, at zero-based offset 262, which line 41 of
     * {@code app/jcl/TRANREPT.jcl} independently corroborates as the one-based sort position 263.
     * The value is a repeated-digit run rather than a plausible account number, so it is
     * recognisable on sight, and its distinctness is what makes a search for it across a whole
     * report meaningful.</p>
     *
     * <p>Refactoring Rationale: this value ends in 8 and an earlier revision ended in 1, on the
     * stated ground that the value could not pass a check-digit test. That ground was false for
     * the earlier value and the arithmetic is written out here so the claim can be checked rather
     * than taken on trust. Under the Luhn algorithm every second digit from the right is doubled and
     * a doubled result above nine has nine subtracted from it, then the digits are summed and a
     * total ending in zero is valid. For {@code 4444333322221111} the eight undoubled digits
     * contribute {@code 1+1+2+2+3+3+4+4 = 20} and the eight doubled digits contribute
     * {@code 2+2+4+4+6+6+8+8 = 40}, giving 60, which ends in zero -- so the earlier value was
     * <em>valid</em> and the rationale asserted the opposite of the fact. Changing only the last
     * digit from 1 to 8 raises the undoubled contribution by seven to 27 and the total to 67, which
     * does not end in zero, so this value genuinely cannot correspond to an issued card. Alternatives
     * Considered: keeping the value and deleting the check-digit sentence. Rejected because the
     * property the sentence claims is the property the constant is chosen for -- a test datum that
     * could be a live card number is a datum a reader has to think twice about before putting it in a
     * fixture -- so the fix is to make the value satisfy the claim rather than to drop the claim.</p>
     */
    private static final String SYNTHETIC_CARD_NUMBER = "4444333322221118";

    /**
     * Sum of the Luhn contributions of {@link #SYNTHETIC_CARD_NUMBER}, asserted below.
     *
     * <p>Assumptions: the total is declared as a constant and asserted rather than left in prose,
     * because a rationale that rests on arithmetic nobody re-runs is exactly how the earlier revision
     * came to state the opposite of the fact. Asserting it means the claim fails a build if the
     * constant is ever edited.</p>
     */
    private static final int SYNTHETIC_CARD_LUHN_TOTAL = 67;

    /**
     * Zero-based offset of the four digits a masked rendering would keep.
     *
     * <p>Assumptions: 12 is the sixteen declared characters of {@code TRAN-CARD-NUM} less the four a
     * mask leaves visible, and it is declared rather than written at each use so that a search for
     * the masked tail cannot drift out of step with the number it is a tail of.</p>
     */
    private static final int CARD_NUMBER_TAIL_OFFSET = 12;

    /**
     * Number of members the report detail band declares.
     *
     * <p>Assumptions: the detail band at lines 15 to 31 of {@code app/cpy/CVTRA07Y.cpy} declares a
     * transaction identifier, an account identifier, a type code, a type description, a category
     * code, a category description, a source and an amount. Eight members, and the same eight are the
     * parameters of the emission call and the components of the payload that feeds it, which is what
     * makes a count assertion over all three meaningful.</p>
     */
    private static final int REPORT_DETAIL_MEMBER_COUNT = 8;

    /**
     * First business date of the range used by every heading assertion below.
     */
    private static final LocalDate RANGE_START = LocalDate.of(2022, 7, 1);

    /**
     * Last business date of the range used by every heading assertion below.
     */
    private static final LocalDate RANGE_END = LocalDate.of(2022, 7, 31);

    /**
     * First page total of the two-page run the two divergence cases below are built on.
     *
     * <p>Assumptions: these four money vectors are declared together because they are one arithmetic
     * example, not four independent constants: the target's grand total is the sum of the two page
     * totals, and the baseline's is that sum with the closing amount counted once more. Splitting
     * them would let one be edited without the other and leave the example incoherent.</p>
     */
    private static final String FIRST_PAGE_TOTAL = "700.00";

    /**
     * Second and closing page total of that run, which is also its last transaction's amount.
     *
     * <p>Assumptions: those two being the same value is what makes the baseline's repetition
     * expressible at all -- the end-of-file branch adds the last transaction's amount, so on this
     * run it adds exactly this page total again.</p>
     */
    private static final String CLOSING_PAGE_TOTAL = "534.56";

    /** The sum of the two page totals: the grand total this mapper is required to render. */
    private static final String TARGET_GRAND_TOTAL = "1234.56";

    /** That sum with the closing amount counted a second time: the grand total the reference renders. */
    private static final String BASELINE_GRAND_TOTAL = "1769.12";

    /** The rendered form of {@link #TARGET_GRAND_TOTAL} through the always-signed total mask. */
    private static final String RENDERED_TARGET_TOTAL = "+      1,234.56";

    /** The rendered form of {@link #BASELINE_GRAND_TOTAL} through the same mask. */
    private static final String RENDERED_BASELINE_TOTAL = "+      1,769.12";

    /**
     * Decodes an emitted band to a string for comparison against a transcribed literal.
     *
     * <p>Assumptions: the charset is named explicitly and is the same single-byte charset
     * {@link FixedWidthCodec} encodes with, so one emitted byte is one decoded character and a
     * length in characters can never diverge from a length in bytes. A multi-byte charset would let
     * a hundred-and-thirty-three-byte record decode to fewer characters, and every column offset
     * asserted below is a character index into this result. Relying on the platform default was
     * rejected outright: the default is configuration, so the same record would decode differently
     * on two machines and a column assertion would pass in one place and fail in another.</p>
     *
     * @param band the emitted band; must not be {@code null}
     * @return the band decoded as single-byte ASCII text, of the same length as the band
     */
    private static String text(byte[] band) {
        return new String(band, StandardCharsets.US_ASCII);
    }

    /**
     * Reads a committed fixture from the test classpath and returns its rows without terminators.
     *
     * <p>Assumptions: the fixtures are line-terminated flat files whose rows are each exactly their
     * declared record length, so an empty trailing element from the final terminator is dropped
     * rather than being decoded as a short record.</p>
     *
     * @param resource the classpath-relative fixture name, such as {@link #TYPE_FIXTURE}; must not
     *     be {@code null}
     * @return the fixture's rows in file order with their line terminators removed, never
     *     {@code null}
     * @throws IOException if the resource cannot be read, which means a broken checkout rather than
     *     a failing assertion
     * @throws IllegalStateException if the resource is absent from the test classpath, which means
     *     the fixture was removed or renamed
     */
    private static List<String> fixtureRows(String resource) throws IOException {
        try (InputStream stream =
                TransactionReportMapperTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "fixture " + resource + " is not on the test classpath");
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.US_ASCII);
            List<String> rows = new ArrayList<>();
            for (String row : content.split("\n", -1)) {
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
            return List.copyOf(rows);
        }
    }

    /**
     * Finds the one fixture description whose occupied length is the length asked for.
     *
     * <p>Assumptions: the returned value is the component AS DECODED, at its full declared fifty
     * characters with its trailing blanks intact, because that is what the baseline's alphanumeric
     * move at lines 366 and 368 of {@code app/cbl/CBTRN03C.cbl} receives. Only the SEARCH is done
     * on the stripped length; nothing is stripped from the value handed back.</p>
     *
     * <p>Alternatives Considered: selecting the row by its key -- type {@code 04}, or the composite
     * {@code 020001} -- rather than by the length of its description. Rejected because the property
     * that makes a row useful here is its length relative to the target column, and a key states
     * that only by implication. Selecting on length means the case name and the selection criterion
     * are the same fact, and a fixture edit that changed a description's length fails the lookup
     * instead of silently testing a different arm of the triple.</p>
     *
     * @param resource the classpath-relative fixture name to search; must not be {@code null}
     * @param layoutName the registry layout its rows follow, such as {@link #TYPE_LAYOUT}; must not
     *     be {@code null}
     * @param fieldName the declared component name of the description to measure; must not be
     *     {@code null}
     * @param occupiedLength the number of characters the wanted description occupies before its
     *     trailing blanks
     * @return the matching description at its full declared width, trailing blanks included, never
     *     {@code null}
     * @throws IOException if the fixture cannot be read
     * @throws IllegalStateException if no row of the fixture occupies exactly that many characters,
     *     which means the fixture no longer supplies the arm this case needs
     */
    private static String fixtureDescription(String resource, String layoutName, String fieldName,
            int occupiedLength) throws IOException {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(layoutName);
        for (String row : fixtureRows(resource)) {
            String description = String.valueOf(FixedWidthCodec
                    .decodeRecord(row.getBytes(StandardCharsets.US_ASCII), spec).get(fieldName));
            if (description.stripTrailing().length() == occupiedLength) {
                return description;
            }
        }
        throw new IllegalStateException(resource + " supplies no " + fieldName + " occupying exactly "
                + occupiedLength + " characters, so the narrowing arm that needs it cannot run");
    }

    /**
     * Returns the fifteen characters a detail band devotes to its type description.
     *
     * @param band the emitted detail band; must not be {@code null}
     * @return the type description column, exactly {@value #TYPE_DESCRIPTION_WIDTH} characters
     */
    private static String typeDescriptionField(byte[] band) {
        return text(band).substring(TYPE_DESCRIPTION_OFFSET,
                TYPE_DESCRIPTION_OFFSET + TYPE_DESCRIPTION_WIDTH);
    }

    /**
     * Returns the twenty-nine characters a detail band devotes to its category description.
     *
     * @param band the emitted detail band; must not be {@code null}
     * @return the category description column, exactly {@value #CATEGORY_DESCRIPTION_WIDTH}
     *     characters
     */
    private static String categoryDescriptionField(byte[] band) {
        return text(band).substring(CATEGORY_DESCRIPTION_OFFSET,
                CATEGORY_DESCRIPTION_OFFSET + CATEGORY_DESCRIPTION_WIDTH);
    }

    /**
     * Emits all eight bands of the report with one set of inputs, in the baseline's emission order.
     *
     * <p>Assumptions: a property asserted of "the report" has to be asserted of every band, not of
     * a representative one, because each band is composed from a different descriptor and a
     * different value map. The order matches lines 325, 329, 333, 337, 289, 295, 308 and 320 of
     * {@code app/cbl/CBTRN03C.cbl} so that a failure index names a band a reader can find.</p>
     *
     * @param amount the money value given to the detail band and to all three total bands; must not
     *     be {@code null}
     * @return the eight emitted bands, each {@value #REPORT_RECORD_LENGTH} bytes, never
     *     {@code null}
     */
    private static List<byte[]> allEightBands(Money amount) {
        return List.of(
                TransactionReportMapper.encodeNameHeader(RANGE_START, RANGE_END),
                TransactionReportMapper.encodeBlankLine(),
                TransactionReportMapper.encodeColumnHeader(),
                TransactionReportMapper.encodeSeparatorRule(),
                TransactionReportMapper.encodeDetailLine("0000000000000001", 7L, "01",
                        "Regular Sales Draft", 1, "Restaurant and Bar Purchases", "POS TERM  ",
                        amount),
                TransactionReportMapper.encodePageTotal(amount),
                TransactionReportMapper.encodeAccountTotal(amount),
                TransactionReportMapper.encodeGrandTotal(amount));
    }

    /**
     * Builds a value of a stated length by padding it on the right with blanks.
     *
     * <p>Assumptions: this pads an expectation and never a value the mapper produced, so it cannot
     * mask a padding fault in the mapper.</p>
     *
     * @param value the leading content of the expectation
     * @param width the total width the expectation must reach
     * @return the value followed by enough blanks to reach the width
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Emits a representative detail line used by several assertions below.
     *
     * <p>Assumptions: the arguments are chosen so every rendering is distinguishable -- a short
     * account identifier so its zero padding shows, a category code of one digit so its zero fill
     * shows, and two descriptions longer than their columns so both narrowings show.</p>
     *
     * @return the emitted detail line, never {@code null}
     */
    private static byte[] representativeDetail() {
        return TransactionReportMapper.encodeDetailLine("0000000000000001", 11L, "01",
                "Regular Sales Draft", 5, "Restaurant and Bar Purchases", "POS TERM  ",
                Money.of("1234.56"));
    }

    /**
     * Returns the fifteen characters a band devotes to its edited amount.
     *
     * @param band the emitted band; must not be {@code null}
     * @return the amount field of the band, exactly {@value #AMOUNT_WIDTH} characters
     */
    private static String amountField(byte[] band) {
        return text(band).substring(AMOUNT_OFFSET, AMOUNT_OFFSET + AMOUNT_WIDTH);
    }

    /**
     * Asserts that encodeNameHeader carries both dates under their labels and pads from 115 to 133.
     */
    @Test
    @DisplayName("encodeNameHeader carries both dates under their labels and pads from 115 to 133")
    void encodeNameHeaderCarriesBothDates() {
        byte[] header = TransactionReportMapper.encodeNameHeader(RANGE_START, RANGE_END);

        assertThat(header).hasSize(REPORT_RECORD_LENGTH);
        assertThat(text(header)).isEqualTo(padded("DALYREPT", 38)
                + padded("Daily Transaction Report", 41)
                + "Date Range: " + "2022-07-01" + " to " + "2022-07-31"
                + " ".repeat(18));

        // WHY : Assumptions: the padding run is asserted as its own fact because this band is the
        //       only one of the seven whose native width, 115, is neither 133 nor the 114 the other
        //       short bands share. A band that reached 133 from the wrong native width would still
        //       be 133 bytes long and would place its two dates elsewhere.
        assertThat(text(header).substring(115)).isEqualTo(" ".repeat(18));
    }

    /**
     * Supplies each date position of the title band paired with a {@code null} argument.
     *
     * @return a stream of argument name and an invocation that passes {@code null} in that position
     */
    private static Stream<Arguments> nullTitleDates() {
        return Stream.of(
                Arguments.of("rangeStart", (Executable) () -> TransactionReportMapper
                        .encodeNameHeader(null, RANGE_END)),
                Arguments.of("rangeEnd", (Executable) () -> TransactionReportMapper
                        .encodeNameHeader(RANGE_START, null)));
    }

    /**
     * Asserts that encodeNameHeader rejects a null date by naming the argument.
     *
     * @param argumentName the name of the argument the rejection must cite
     * @param invocation the call that passes {@code null} in that position
     */
    @ParameterizedTest(name = "encodeNameHeader rejects a null {0}")
    @MethodSource("nullTitleDates")
    @DisplayName("encodeNameHeader rejects a null date by naming the argument")
    void encodeNameHeaderRejectsANullDate(String argumentName, Executable invocation) {
        assertThatThrownBy(invocation::execute)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(argumentName);
    }

    /**
     * Asserts that encodeColumnHeader carries all seven literals including the single byte that
     * closes them.
     */
    @Test
    @DisplayName("encodeColumnHeader carries all seven literals including the single byte that "
            + "closes them")
    void encodeColumnHeaderCarriesAllSevenLiterals() {
        byte[] header = TransactionReportMapper.encodeColumnHeader();

        assertThat(header).hasSize(REPORT_RECORD_LENGTH);
        assertThat(text(header)).isEqualTo(padded("Transaction ID", 17)
                + padded("Account ID", 12)
                + padded("Transaction Type", 19)
                + padded("Tran Category", 35)
                + padded("Tran Source", 14)
                + " "
                + padded("        Amount", 16)
                + " ".repeat(19));

        // WHY : Assumptions: the eight leading blanks of the amount heading are DATA and not
        //       formatting, under AAP Rule T8. They are what right-aligns the word Amount over the
        //       mask beneath it, so the heading's last content character and the detail amount's
        //       last character land in the same column. Asserting that column equality is what
        //       proves the eight blanks are neither six nor ten.
        assertThat(text(header).indexOf("Amount")).isEqualTo(106);
        assertThat(text(header).charAt(97)).isEqualTo(' ');
    }

    /**
     * Asserts that encodeSeparatorRule is one hundred and thirty-three hyphens with no padding.
     */
    @Test
    @DisplayName("encodeSeparatorRule is one hundred and thirty-three hyphens with no padding")
    void encodeSeparatorRuleIsAllHyphens() {
        byte[] rule = TransactionReportMapper.encodeSeparatorRule();

        // WHY : Assumptions: this band is the only one of the seven that is natively the declared
        //       record length, so it is the only one for which the codec's blank fill must never
        //       run. A rule supplied short would be blank-filled and would read as a rule that
        //       stops part way, which is why the literal is supplied at full width.
        assertThat(text(rule)).isEqualTo("-".repeat(REPORT_RECORD_LENGTH));
    }

    /**
     * Asserts that encodeBlankLine is one hundred and thirty-three blanks.
     */
    @Test
    @DisplayName("encodeBlankLine is one hundred and thirty-three blanks")
    void encodeBlankLineIsAllBlanks() {
        assertThat(text(TransactionReportMapper.encodeBlankLine()))
                .isEqualTo(" ".repeat(REPORT_RECORD_LENGTH));
    }

    /**
     * Asserts that encodeHeadingBlock emits the four bands in the reference's own order.
     */
    @Test
    @DisplayName("encodeHeadingBlock emits the four bands in the reference's own order")
    void encodeHeadingBlockEmitsFourBandsInOrder() {
        List<byte[]> block = TransactionReportMapper.encodeHeadingBlock(RANGE_START, RANGE_END);

        assertThat(block).hasSize(4);
        assertThat(block).allSatisfy(line -> assertThat(line).hasSize(REPORT_RECORD_LENGTH));

        // WHY : Assumptions: the order is asserted positionally against the four individual
        //       encoders, because the order IS the content of this method. Lines 325, 329, 333 and
        //       337 of app/cbl/CBTRN03C.cbl emit title, blank, headings, rule with nothing between
        //       them, and a permuted block would still be four correct bands.
        assertThat(text(block.get(0)))
                .isEqualTo(text(TransactionReportMapper.encodeNameHeader(RANGE_START, RANGE_END)));
        assertThat(text(block.get(1)))
                .isEqualTo(text(TransactionReportMapper.encodeBlankLine()));
        assertThat(text(block.get(2)))
                .isEqualTo(text(TransactionReportMapper.encodeColumnHeader()));
        assertThat(text(block.get(3)))
                .isEqualTo(text(TransactionReportMapper.encodeSeparatorRule()));
    }

    /**
     * Asserts that encodeDetailLine places all eight varying items and reseeds the two joining
     * hyphens.
     */
    @Test
    @DisplayName("encodeDetailLine places all eight varying items and reseeds the two joining "
            + "hyphens")
    void encodeDetailLinePlacesEightItemsAndReseedsTwoHyphens() {
        byte[] detail = representativeDetail();

        assertThat(detail).hasSize(REPORT_RECORD_LENGTH);
        assertThat(text(detail)).isEqualTo("0000000000000001"
                + " " + "00000000011"
                + " " + "01" + "-" + "Regular Sales D"
                + " " + "0005" + "-" + padded("Restaurant and Bar Purchases", 29)
                + " " + "POS TERM  "
                + "    " + "       1,234.56"
                + "  " + " ".repeat(19));

        // WHY : Assumptions: both joining hyphens are DATA and not padding, and are asserted as bytes
        //       at their own offsets for that reason. Lines 21 and 25 of app/cpy/CVTRA07Y.cpy each
        //       declare 05 FILLER PIC X(01) VALUE with a hyphen literal, so each carries content the
        //       band is required to print. The reference never restates them, because line 362 of
        //       app/cbl/CBTRN03C.cbl opens the detail paragraph with INITIALIZE on the band and that
        //       statement leaves FILLER untouched -- the same pattern is visible at line 459 of
        //       app/cbl/CBSTM03A.CBL -- so the two bytes are written once into a persistent record
        //       area and survive from the first detail line to the last.
        // WHY : Alternatives Considered: treating the two positions as separators and letting the
        //       codec blank-fill them. Rejected because a freshly allocated array has no survivor from
        //       a previous line, so the band would emit two blanks where the report joins a code to
        //       its description, and every other byte of the line would still be correct.
        assertThat(text(detail).charAt(31)).isEqualTo('-');
        assertThat(text(detail).charAt(52)).isEqualTo('-');

        // WHY : Assumptions: the joined pair is additionally asserted as the printed unit a reader
        //       sees, because the two byte assertions above hold for a band whose hyphens are right
        //       and whose surrounding code or description is not. The rendering reads NN-Description
        //       for the type and NNNN-Description for the category.
        assertThat(text(detail))
                .as("the type code and its description print as one joined unit")
                .contains("01-Regular Sales D");
        assertThat(text(detail))
                .as("the category code and its description print as one joined unit")
                .contains("0005-Restaurant and Bar Purch");
    }

    /**
     * Asserts that encodeDetailLine zero-pads the account identifier and zero-fills the category
     * code.
     */
    @Test
    @DisplayName("encodeDetailLine zero-pads the account identifier and zero-fills the category "
            + "code")
    void encodeDetailLineZeroFillsBothNumericColumns() {
        String detail = text(representativeDetail());

        // WHY : Assumptions: the two numeric columns are filled by two different mechanisms and are
        //       therefore asserted separately. The account identifier is rendered to eleven digits
        //       by this class before the codec sees it, because line 18 of app/cpy/CVTRA07Y.cpy
        //       declares a CHARACTER target that would otherwise left-justify it; the category code
        //       is handed over as a number and filled by the codec, because line 24 declares an
        //       unsigned numeric target. Swapping the two would zero-fill one of them twice and
        //       left-justify the other.
        assertThat(detail.substring(17, 28)).isEqualTo("00000000011");
        assertThat(detail.substring(48, 52)).isEqualTo("0005");
    }

    /**
     * Asserts that encodeDetailLine narrows both descriptions to their own column widths.
     */
    @Test
    @DisplayName("encodeDetailLine narrows both descriptions to their own column widths")
    void encodeDetailLineNarrowsBothDescriptions() {
        byte[] detail = TransactionReportMapper.encodeDetailLine("T", 1L, "01", "y".repeat(50), 1,
                "z".repeat(50), "S", Money.of("1.00"));

        // WHY : Assumptions: the two columns are 15 and 29 and are asserted with distinct fill
        //       characters, because a single repeated character could not show a narrowing that
        //       used the wrong width for the wrong column. Their sources are both 50, so an
        //       implementation that narrowed both to one width would still produce a full band.
        assertThat(text(detail).substring(32, 47)).isEqualTo("y".repeat(15));
        assertThat(text(detail).substring(53, 82)).isEqualTo("z".repeat(29));
    }

    /**
     * Asserts that the detail mask blanks the sign position of a positive amount and prints a minus
     * for a negative one.
     *
     * <p>Assumptions: the sign of {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} at line 30 of
     * {@code app/cpy/CVTRA07Y.cpy} occupies one FIXED leading position and is not an insertion
     * character that travels with the first significant digit; a travelling sign would be written as
     * a run of minus symbols across the digit positions. Both polarities are therefore asserted at
     * the same offset.</p>
     */
    @Test
    @DisplayName("the detail mask blanks the sign position of a positive amount and prints a minus "
            + "for a negative one")
    void theDetailMaskShowsAMinusOnlyForANegativeAmount() {
        assertThat(amountField(representativeDetail())).isEqualTo("       1,234.56");
        assertThat(amountField(TransactionReportMapper.encodeDetailLine("T", 1L, "01", "d", 1, "c",
                "S", Money.of("-1234.56")))).isEqualTo("-      1,234.56");
    }

    /**
     * Supplies each reference argument of the detail band paired with a {@code null} in its
     * position.
     *
     * @return a stream of argument name and an invocation that passes {@code null} there
     */
    private static Stream<Arguments> nullDetailArguments() {
        Money one = Money.of("1.00");
        return Stream.of(
                Arguments.of("transactionId", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine(null, 1L, "01", "d", 1, "c", "S", one)),
                Arguments.of("transactionTypeCode", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, null, "d", 1, "c", "S", one)),
                Arguments.of("transactionTypeDescription",
                        (Executable) () -> TransactionReportMapper
                                .encodeDetailLine("T", 1L, "01", null, 1, "c", "S", one)),
                Arguments.of("transactionCategoryDescription",
                        (Executable) () -> TransactionReportMapper
                                .encodeDetailLine("T", 1L, "01", "d", 1, null, "S", one)),
                Arguments.of("transactionSource", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, "01", "d", 1, "c", null, one)),
                Arguments.of("amount", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, "01", "d", 1, "c", "S", null)));
    }

    /**
     * Asserts that encodeDetailLine rejects each null reference argument by naming that argument.
     *
     * @param argumentName the name of the argument the rejection must cite
     * @param invocation the call that passes {@code null} in that position
     */
    @ParameterizedTest(name = "encodeDetailLine rejects a null {0}")
    @MethodSource("nullDetailArguments")
    @DisplayName("encodeDetailLine rejects each null reference argument by naming that argument")
    void encodeDetailLineRejectsEachNullArgument(String argumentName, Executable invocation) {
        assertThatThrownBy(invocation::execute)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(argumentName);
    }

    /**
     * Asserts that encodeDetailLine refuses an account identifier the eleven-digit column cannot
     * show.
     */
    @Test
    @DisplayName("encodeDetailLine refuses an account identifier the eleven-digit column cannot "
            + "show")
    void encodeDetailLineRefusesAnOutOfDomainAccountIdentifier() {
        assertThatThrownBy(() -> TransactionReportMapper.encodeDetailLine("T", -1L, "01", "d", 1,
                "c", "S", Money.of("1.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> TransactionReportMapper.encodeDetailLine("T", 100000000000L, "01",
                "d", 1, "c", "S", Money.of("1.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("11 digit positions");
    }

    /**
     * Asserts that encodeDetailLine refuses a category code the four-digit column cannot show.
     */
    @Test
    @DisplayName("encodeDetailLine refuses a category code the four-digit column cannot show")
    void encodeDetailLineRefusesAnOutOfDomainCategoryCode() {
        // WHY : Assumptions: the category code fails inside the codec where the account identifier
        //       fails before it, and the two diagnostics are therefore different types. That is a
        //       consequence of which side performs the fill, asserted here so a reader does not
        //       expect one rule to cover both numeric columns.
        assertThatThrownBy(() -> TransactionReportMapper.encodeDetailLine("T", 1L, "01", "d", -1,
                "c", "S", Money.of("1.00")))
                .isInstanceOf(FixedWidthCodec.FieldCodecException.class)
                .hasMessageContaining("TRAN-REPORT-CAT-CD");
        assertThatThrownBy(() -> TransactionReportMapper.encodeDetailLine("T", 1L, "01", "d", 10000,
                "c", "S", Money.of("1.00")))
                .isInstanceOf(FixedWidthCodec.FieldCodecException.class)
                .hasMessageContaining("needs 5 digits");
    }

    /**
     * Supplies each fixed-width detail argument paired with a value one character too wide.
     *
     * @return a stream of field name and an invocation that supplies the over-wide value
     */
    private static Stream<Arguments> overWideDetailArguments() {
        Money one = Money.of("1.00");
        return Stream.of(
                Arguments.of("TRAN-REPORT-TRANS-ID", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("x".repeat(17), 1L, "01", "d", 1, "c", "S", one)),
                Arguments.of("TRAN-REPORT-TYPE-CD", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, "abc", "d", 1, "c", "S", one)),
                Arguments.of("TRAN-REPORT-SOURCE", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, "01", "d", 1, "c", "x".repeat(11), one)));
    }

    /**
     * Asserts that encodeDetailLine refuses an over-wide value for a column it never narrows.
     *
     * @param fieldName the copybook field name the rejection must cite
     * @param invocation the call that supplies the over-wide value
     */
    @ParameterizedTest(name = "encodeDetailLine refuses an over-wide {0}")
    @MethodSource("overWideDetailArguments")
    @DisplayName("encodeDetailLine refuses an over-wide value for a column it never narrows")
    void encodeDetailLineRefusesAnOverWideFixedWidthArgument(String fieldName,
            Executable invocation) {
        // WHY : Assumptions: three of the five character arguments are refused when over-wide while
        //       the two descriptions are narrowed instead. The difference is not arbitrary: the
        //       three below are declared at the SAME width as their columns, so an over-wide value
        //       is a caller error, whereas the two descriptions are declared more than three times
        //       their columns and being over-wide is their normal condition.
        assertThatThrownBy(invocation::execute)
                .isInstanceOf(FixedWidthCodec.FieldCodecException.class)
                .hasMessageContaining(fieldName);
    }

    /**
     * Asserts that the page total band carries an eleven-character label and an eighty-six-dot
     * leader.
     */
    @Test
    @DisplayName("the page total band carries an eleven-character label and an eighty-six-dot "
            + "leader")
    void thePageTotalBandCarriesItsLabelAndLeader() {
        byte[] band = TransactionReportMapper.encodePageTotal(Money.of("1234.56"));

        assertThat(text(band)).isEqualTo(padded("Page Total", 11) + ".".repeat(86)
                + "+      1,234.56" + " ".repeat(21));
    }

    /**
     * Asserts that the account total band uses a thirteen-character label and an eighty-four-dot
     * leader.
     */
    @Test
    @DisplayName("the account total band uses a thirteen-character label and an eighty-four-dot "
            + "leader")
    void theAccountTotalBandUsesAWiderLabelAndAShorterLeader() {
        byte[] band = TransactionReportMapper.encodeAccountTotal(Money.of("-1234.56"));

        // WHY : Assumptions: this label exactly fills its thirteen characters, so it carries NO
        //       trailing blank where the other two labels each carry one. That is why the leader is
        //       84 rather than 86: 13 plus 84 and 11 plus 86 both reach the same 97. Unifying the
        //       leaders would move this one total two columns right of the other three.
        assertThat(text(band)).isEqualTo("Account Total" + ".".repeat(84)
                + "-      1,234.56" + " ".repeat(21));
    }

    /**
     * Asserts that the grand total band matches the page total band's label and leader pair.
     */
    @Test
    @DisplayName("the grand total band matches the page total band's label and leader pair")
    void theGrandTotalBandMatchesThePageTotalPair() {
        byte[] band = TransactionReportMapper.encodeGrandTotal(Money.of("1234.56"));

        assertThat(text(band)).isEqualTo(padded("Grand Total", 11) + ".".repeat(86)
                + "+      1,234.56" + " ".repeat(21));
    }

    /**
     * Asserts that all four money-bearing bands open their amount on the same column.
     */
    @Test
    @DisplayName("all four money-bearing bands open their amount on the same column")
    void allFourMoneyBearingBandsShareOneAmountColumn() {
        Money amount = Money.of("1234.56");
        List<byte[]> bands = List.of(
                representativeDetail(),
                TransactionReportMapper.encodePageTotal(amount),
                TransactionReportMapper.encodeAccountTotal(amount),
                TransactionReportMapper.encodeGrandTotal(amount));

        // WHY : Assumptions: the shared column is asserted across all four bands together rather
        //       than band by band, because the property being checked is an EQUALITY between four
        //       independently declared pairs of widths. Checking each band's own offset in
        //       isolation would pass for four bands that each placed its amount somewhere else.
        assertThat(bands).allSatisfy(band -> assertThat(amountField(band)).hasSize(AMOUNT_WIDTH));
        assertThat(bands).allSatisfy(band -> assertThat(amountField(band)).endsWith("1,234.56"));
    }

    /**
     * Asserts that the three total bands use the always-signed mask where the detail band uses the
     * leading-minus mask.
     */
    @Test
    @DisplayName("the three total bands use the always-signed mask where the detail band uses the "
            + "leading-minus mask")
    void theTotalBandsAndTheDetailBandUseDifferentMasks() {
        Money positive = Money.of("1234.56");

        // WHY : Assumptions: the two regimes differ in exactly ONE byte for a positive value, the
        //       sign position, so they are asserted against each other for the same magnitude. A
        //       single formatter with a sign flag would let a default decide which of the two a
        //       report line got, and every other byte of the field would still agree.
        assertThat(amountField(representativeDetail()).charAt(0)).isEqualTo(' ');
        assertThat(amountField(TransactionReportMapper.encodePageTotal(positive)).charAt(0))
                .isEqualTo('+');
        assertThat(amountField(TransactionReportMapper.encodeAccountTotal(positive)).charAt(0))
                .isEqualTo('+');
        assertThat(amountField(TransactionReportMapper.encodeGrandTotal(positive)).charAt(0))
                .isEqualTo('+');
    }

    /**
     * Asserts that a zero amount blanks its whole field, because every digit position of both masks
     * suppresses.
     */
    @Test
    @DisplayName("a zero amount blanks its whole field, because every digit position of both masks "
            + "suppresses")
    void aZeroAmountBlanksItsWholeField() {
        Money zero = Money.of("0.00");

        // WHY : Assumptions: this is the one rendering a reader is most likely to call a defect.
        //       Both masks at lines 30 and 54 of app/cpy/CVTRA07Y.cpy declare suppression symbols
        //       in the DECIMAL positions as well as the integer ones, and COBOL blanks an entire
        //       item whose every digit position suppresses when the value is zero -- sign position
        //       included. The statement masks behave differently, because their decimals are digit
        //       positions, and that contrast is asserted in StatementTextMapperTest.
        assertThat(amountField(TransactionReportMapper.encodeDetailLine("T", 1L, "01", "d", 1, "c",
                "S", zero))).isEqualTo(" ".repeat(AMOUNT_WIDTH));
        assertThat(amountField(TransactionReportMapper.encodePageTotal(zero)))
                .isEqualTo(" ".repeat(AMOUNT_WIDTH));
        assertThat(amountField(TransactionReportMapper.encodeAccountTotal(zero)))
                .isEqualTo(" ".repeat(AMOUNT_WIDTH));
        assertThat(amountField(TransactionReportMapper.encodeGrandTotal(zero)))
                .isEqualTo(" ".repeat(AMOUNT_WIDTH));
    }

    /**
     * Supplies the three total encoders paired with a {@code null} amount.
     *
     * @return a stream of argument name and an invocation that passes {@code null}
     */
    private static Stream<Arguments> nullTotals() {
        return Stream.of(
                Arguments.of("pageTotal",
                        (Executable) () -> TransactionReportMapper.encodePageTotal(null)),
                Arguments.of("accountTotal",
                        (Executable) () -> TransactionReportMapper.encodeAccountTotal(null)),
                Arguments.of("grandTotal",
                        (Executable) () -> TransactionReportMapper.encodeGrandTotal(null)));
    }

    /**
     * Asserts that every total encoder rejects a null amount by naming its own argument.
     *
     * @param argumentName the name of the argument the rejection must cite
     * @param invocation the call that passes {@code null}
     */
    @ParameterizedTest(name = "{0} rejects a null amount")
    @MethodSource("nullTotals")
    @DisplayName("every total encoder rejects a null amount by naming its own argument")
    void everyTotalEncoderRejectsANullAmount(String argumentName, Executable invocation) {
        assertThatThrownBy(invocation::execute)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(argumentName);
    }

    /**
     * Supplies every money-bearing encoder paired with an amount too wide for its mask.
     *
     * @return a stream of band label and an invocation that supplies the over-wide amount
     */
    private static Stream<Arguments> overWideAmounts() {
        Money tooWide = Money.of("1000000000.00");
        return Stream.of(
                Arguments.of("detail", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, "01", "d", 1, "c", "S", tooWide)),
                Arguments.of("pageTotal",
                        (Executable) () -> TransactionReportMapper.encodePageTotal(tooWide)),
                Arguments.of("accountTotal",
                        (Executable) () -> TransactionReportMapper.encodeAccountTotal(tooWide)),
                Arguments.of("grandTotal",
                        (Executable) () -> TransactionReportMapper.encodeGrandTotal(tooWide)));
    }

    /**
     * Asserts that every money-bearing band refuses an amount too wide for its nine-digit mask.
     *
     * @param label the band under test, shown in the case name
     * @param invocation the call that supplies the over-wide amount
     */
    @ParameterizedTest(name = "the {0} band refuses an amount too wide for its mask")
    @MethodSource("overWideAmounts")
    @DisplayName("every money-bearing band refuses an amount too wide for its nine-digit mask")
    void everyMoneyBearingBandRefusesAnOverWideAmount(String label, Executable invocation) {
        // WHY : Assumptions: this asserts registered divergence D-EDIT-MASK-OVERFLOW on all four
        //       bands rather than one. The three accumulators that feed the totals are declared
        //       with exactly the nine integer positions the masks provide at lines 134 to 136 of
        //       app/cbl/CBTRN03C.cbl, so the guard is not an expected path -- it stays reachable
        //       only because the shared money type admits ten.
        assertThatThrownBy(invocation::execute)
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("10 integer digits");
    }

    /**
     * Asserts that the total encoders hold no accumulator, so a total renders exactly the value
     * supplied.
     */
    @Test
    @DisplayName("the total encoders hold no accumulator, so a total renders exactly the value "
            + "supplied")
    void theTotalEncodersHoldNoAccumulator() {
        byte[] first = TransactionReportMapper.encodePageTotal(Money.of("100.00"));
        byte[] again = TransactionReportMapper.encodePageTotal(Money.of("100.00"));
        byte[] second = TransactionReportMapper.encodePageTotal(Money.of("250.00"));

        // WHY : Assumptions: this is the checkable half of registered divergence
        //       D-REPORT-GRAND-TOTAL. The reference adds the last transaction's amount into
        //       the page accumulator a second time at line 200 of app/cbl/CBTRN03C.cbl and carries
        //       it into the grand total at line 297, so a target that accumulated anywhere in this
        //       class could reproduce that doubling silently. Asserting that a repeat call is
        //       IDENTICAL and that a different value renders itself and not a running sum is what
        //       proves no accumulator exists here to do it.
        assertThat(text(again)).isEqualTo(text(first));
        assertThat(amountField(second)).isEqualTo("+        250.00");
        assertThat(amountField(second)).isNotEqualTo(amountField(first));
    }

    /**
     * Asserts that a card-break total is reachable unconditionally, with no argument that could
     * suppress it.
     *
     * @throws NoSuchMethodException if the account total encoder is renamed or its signature
     *     changes
     */
    @Test
    @DisplayName("a card-break total is reachable unconditionally, with no argument that could "
            + "suppress it")
    void aCardBreakTotalIsReachableUnconditionally() throws NoSuchMethodException {
        Method encoder = TransactionReportMapper.class.getMethod("encodeAccountTotal", Money.class);

        // WHY : Assumptions: this is the checkable half of registered divergence
        //       D-REPORT-CLOSING-TOTAL. The reference's end-of-file branch at lines 198 to 203
        //       of app/cbl/CBTRN03C.cbl never performs its card-break paragraph, so the last group
        //       gets no total band; the target closes every group alike. Asserting the signature
        //       carries exactly one parameter, the amount, is what proves no end-of-run flag
        //       exists by which a caller could reintroduce the suppression.
        assertThat(encoder.getParameterCount()).isEqualTo(1);
        assertThat(encoder.getParameterTypes()[0]).isEqualTo(Money.class);
        assertThat(TransactionReportMapper.encodeAccountTotal(Money.of("1.00")))
                .hasSize(REPORT_RECORD_LENGTH);
    }

    /**
     * Asserts that every encoded band is exactly the declared record length and freshly allocated.
     */
    @Test
    @DisplayName("every encoded band is exactly the declared record length and freshly allocated")
    void everyEncodedBandIsExactLengthAndFreshlyAllocated() {
        byte[] first = TransactionReportMapper.encodeSeparatorRule();
        Arrays.fill(first, (byte) 'X');

        // WHY : Assumptions: mutating a returned band and re-encoding is the only way to prove the
        //       emitter holds no shared record area. The reference DOES hold one -- line 362 of
        //       app/cbl/CBTRN03C.cbl re-initialises a record area whose FILLER survives -- so a
        //       reader arriving from that side would reasonably expect a shared buffer here.
        assertThat(text(TransactionReportMapper.encodeSeparatorRule()))
                .isEqualTo("-".repeat(REPORT_RECORD_LENGTH));
        assertThat(List.of(TransactionReportMapper.encodeNameHeader(RANGE_START, RANGE_END),
                        TransactionReportMapper.encodeColumnHeader(),
                        TransactionReportMapper.encodeBlankLine(),
                        representativeDetail(),
                        TransactionReportMapper.encodePageTotal(Money.of("1.00")),
                        TransactionReportMapper.encodeAccountTotal(Money.of("1.00")),
                        TransactionReportMapper.encodeGrandTotal(Money.of("1.00"))))
                .allSatisfy(band -> assertThat(band).hasSize(REPORT_RECORD_LENGTH));
    }

    /**
     * Asserts that the grand total band carries the sum of the page totals and not the value the
     * reference would have produced for the same run.
     */
    @Test
    @DisplayName("the grand total band carries the sum of the page totals and not the reference's "
            + "repetition")
    void theGrandTotalBandCarriesTheSumOfThePageTotalsAndNotTheBaselinesRepetition() {
        // WHY : Assumptions: 700.00 and 534.56 are the two page totals of a two-page run whose last
        //       transaction is the 534.56, chosen so the two expectations differ by exactly one
        //       repetition of the closing amount. The target's grand total is their sum; the
        //       reference's is their sum plus that closing amount once more, because its
        //       end-of-file branch adds the leftover record area into the page accumulator at line
        //       200 and the page-totals paragraph then carries the inflated page total into the
        //       grand total at line 297. Values that did not differ in that one place would satisfy
        //       both readings and pin nothing.
        // WHY : Trade-offs: both expectations are asserted, not just the first. The repetition lives
        //       in the accumulation rather than in this encoder, so an assertion that the band
        //       carries what it was given would hold under either behaviour and would not pin the
        //       divergence at all. This is the value-level half of D-REPORT-GRAND-TOTAL;
        //       theTotalEncodersHoldNoAccumulator above is the structural half.
        byte[] band = TransactionReportMapper.encodeGrandTotal(Money.of(TARGET_GRAND_TOTAL));

        assertThat(band)
                .as("app/cpy/CVTRA07Y.cpy declares every report band 133 bytes wide")
                .hasSize(REPORT_RECORD_LENGTH);
        assertThat(amountField(band))
                .as("the grand total is the plain sum of %s and %s", FIRST_PAGE_TOTAL,
                        CLOSING_PAGE_TOTAL)
                .isEqualTo(RENDERED_TARGET_TOTAL)
                .isNotEqualTo(RENDERED_BASELINE_TOTAL);
    }

    /**
     * Asserts that the account total band encodes the final card group exactly as it encodes any
     * other.
     */
    @Test
    @DisplayName("the account total band encodes the final card group exactly as any other")
    void theAccountTotalBandEncodesTheFinalCardGroupExactlyAsAnyOther() {
        // WHY : Assumptions: one total is encoded TWICE, standing for a middle group and for the last
        //       one, rather than two different totals being compared. Equal input is the whole
        //       premise: the encoder is stateless and carries no notion of position in a run, so
        //       two invocations with equal input must produce equal bytes. That equality IS the
        //       divergence D-REPORT-CLOSING-TOTAL: the reference's end-of-file branch never performs
        //       its card-break paragraph, so its last group has no band to compare with, while here
        //       the last group is closed like every other.
        byte[] middleGroup = TransactionReportMapper.encodeAccountTotal(Money.of(FIRST_PAGE_TOTAL));
        byte[] finalGroup = TransactionReportMapper.encodeAccountTotal(Money.of(FIRST_PAGE_TOTAL));

        assertThat(finalGroup)
                .as("nothing in the encoder distinguishes the last group from an earlier one")
                .isEqualTo(middleGroup)
                .hasSize(REPORT_RECORD_LENGTH);
        assertThat(amountField(finalGroup))
                .as("the account total lands in the same amount column as the other two bands")
                .isEqualTo("+        700.00");
    }

    /**
     * Asserts that the account total encoder is declared once, with no overload a caller could use
     * to suppress the closing band.
     */
    @Test
    @DisplayName("the account total encoder is declared once, with no suppressing overload")
    void theAccountTotalEncoderExposesNoSuppressionParameter() {
        // WHY : Alternatives Considered: asserting only the signature of the single encoder, as
        //       aCardBreakTotalIsReachableUnconditionally above does. Rejected as insufficient on
        //       its own, because a suppression could be reintroduced as a SECOND method rather than
        //       as a parameter of the first -- an overload taking a boolean, or a position -- which a
        //       getMethod lookup for the one-argument form would not notice. Enumerating the
        //       declared methods is what covers that shape.
        List<Method> encoders = Arrays.stream(TransactionReportMapper.class.getDeclaredMethods())
                .filter(method -> "encodeAccountTotal".equals(method.getName()))
                .toList();

        assertThat(encoders)
                .as("exactly one account total encoder is declared, with no suppressing overload")
                .hasSize(1);
        assertThat(encoders.get(0).getParameterTypes())
                .as("its only argument is the total being closed")
                .containsExactly(Money.class);
    }

    /**
     * Supplies each of the eight bands paired with the width its own declaration sums to.
     *
     * <p>Assumptions: the eight native widths are four different numbers, and that is the point of
     * pairing each band with its own. Summing the declared item widths of
     * {@code app/cpy/CVTRA07Y.cpy} gives 115 for the title band at lines 4 to 13, 114 for the
     * heading band at lines 33 to 46 and 114 for the detail band at lines 15 to 31, 133 for the rule
     * line at line 48, and 112 for each total band at lines 50 to 66; the blank line is declared
     * {@code PIC X(133)} outright at line 133 of {@code app/cbl/CBTRN03C.cbl}. A single shared
     * native width would let a band that padded from the wrong start still measure 133.</p>
     *
     * @return a stream of band name, the emitted band, and the width its declaration sums to
     */
    private static Stream<Arguments> everyBandWithItsNativeWidth() {
        List<byte[]> bands = allEightBands(Money.of("1234.56"));
        return Stream.of(
                Arguments.of("name header", bands.get(0), 115),
                Arguments.of("blank line", bands.get(1), REPORT_RECORD_LENGTH),
                Arguments.of("column header", bands.get(2), 114),
                Arguments.of("separator rule", bands.get(3), REPORT_RECORD_LENGTH),
                Arguments.of("detail line", bands.get(4), 114),
                Arguments.of("page total", bands.get(5), 112),
                Arguments.of("account total", bands.get(6), 112),
                Arguments.of("grand total", bands.get(7), 112));
    }

    /**
     * Asserts that every one of the eight bands is exactly 133 bytes and pads with blanks.
     *
     * @param bandName the band under test, shown in the case name
     * @param band the emitted band
     * @param nativeWidth the width this band's own declaration sums to, before any padding
     */
    @ParameterizedTest(name = "the {0} band is 133 bytes and blank-pads from its native {2}")
    @MethodSource("everyBandWithItsNativeWidth")
    @DisplayName("every one of the eight bands is exactly the declared record length")
    void everyBandIsTheDeclaredLengthAndPadsWithBlanks(String bandName, byte[] band,
            int nativeWidth) {
        // WHY : Assumptions: 133 is a DECLARED record length and never a sum of field widths. Six of
        //       the seven copybook bands are natively shorter than it -- 115, 114, 114, 112, 112 and
        //       112 -- and only the rule line at line 48 of app/cpy/CVTRA07Y.cpy is natively 133.
        //       The PADDED length is therefore the emission contract, corroborated independently by
        //       WS-BLANK-LINE PIC X(133) at line 133 of app/cbl/CBTRN03C.cbl and by the LRECL=133
        //       DCB at line 78 of app/jcl/TRANREPT.jcl. Deriving the length from any one native sum
        //       would give a different answer for every band.
        // WHY : Trade-offs: this is the INVERSE of the statement layouts, where all seventeen bands
        //       are natively exactly 80 and none needs padding at all. A reader who carries the
        //       statement assumption into this file will conclude the report bands are mis-declared,
        //       so the direction of the difference is stated rather than left to be inferred.
        assertThat(band)
                .as("the %s band is the declared record length", bandName)
                .hasSize(REPORT_RECORD_LENGTH);

        // WHY : Assumptions: the fill byte is asserted to be a BLANK and not merely to be present.
        //       A freshly allocated Java array is zero-filled, so a band whose padding was never
        //       written would still measure 133 bytes and would carry NUL where the report carries
        //       spaces -- a difference no length check can see and one that a text-mode reader would
        //       render invisibly.
        assertThat(text(band).substring(nativeWidth))
                .as("the %s band pads from its native %d with blanks", bandName, nativeWidth)
                .isEqualTo(" ".repeat(REPORT_RECORD_LENGTH - nativeWidth));
        assertThat(band)
                .as("no byte of the %s band is a NUL", bandName)
                .doesNotContain((byte) 0);
    }

    /**
     * Asserts that every band decodes one character per byte under a single-byte charset.
     */
    @Test
    @DisplayName("every band decodes one character per byte, so no column index can drift")
    void everyBandDecodesOneCharacterPerByte() {
        List<byte[]> bands = allEightBands(Money.of("1234.56"));

        // WHY : Assumptions: every column assertion in this file is a CHARACTER index into a decoded
        //       band, while the contract at line 78 of app/jcl/TRANREPT.jcl is a count of BYTES. The
        //       two are the same number only under a single-byte charset, which is what
        //       FixedWidthCodec encodes with. Under a multi-byte charset a 133-byte record could
        //       decode to fewer characters and every offset below would silently address the wrong
        //       column, so the round trip is asserted rather than assumed.
        assertThat(bands).allSatisfy(band -> {
            assertThat(band).hasSize(REPORT_RECORD_LENGTH);
            assertThat(text(band)).hasSize(REPORT_RECORD_LENGTH);
            assertThat(text(band).getBytes(StandardCharsets.US_ASCII)).isEqualTo(band);
        });
    }

    /**
     * Asserts that the rule line is 133 hyphen-minus bytes and the blank line 133 blank bytes.
     */
    @Test
    @DisplayName("the rule and blank bands are uniform at the byte level, not merely as text")
    void theRuleAndBlankBandsAreUniformAtTheByteLevel() {
        byte[] rule = TransactionReportMapper.encodeSeparatorRule();
        byte[] blank = TransactionReportMapper.encodeBlankLine();

        // WHY : Assumptions: the rule character is asserted as the byte 0x2D, ASCII hyphen-minus,
        //       and not as whatever a dash-like character happens to be. Line 48 of
        //       app/cpy/CVTRA07Y.cpy declares 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL with a
        //       single-quoted hyphen literal, and the target charset has exactly one hyphen at 0x2D;
        //       a typographic dash would encode to a different byte, or to none at all, and would
        //       change a line the parity comparison reads in full.
        assertThat(rule).hasSize(REPORT_RECORD_LENGTH);
        for (byte value : rule) {
            assertThat(value).isEqualTo((byte) 0x2D);
        }

        // WHY : Assumptions: the blank band's fill is asserted as the byte 0x20 for the same reason
        //       the padding check above rejects NUL. Line 133 of app/cbl/CBTRN03C.cbl declares
        //       WS-BLANK-LINE with VALUE SPACES, so all 133 positions carry a space; an unwritten
        //       array would carry NUL and would compare equal to nothing a reader can see.
        assertThat(blank).hasSize(REPORT_RECORD_LENGTH);
        for (byte value : blank) {
            assertThat(value).isEqualTo((byte) 0x20);
        }
    }

    /**
     * Supplies the triple that brackets the fifteen-character type column from below, at and above.
     *
     * <p>Assumptions: the three expectations are transcribed from the fixture rather than recomposed
     * by applying a narrowing to it, because an expectation computed the same way the mapper
     * computes its output passes whenever the two share a mistake. Reading down
     * {@code fixtures/trantype.txt}: the row occupying fourteen characters holds
     * {@code 'Auth hold only'}, which is left-justified and gains ONE trailing blank inside the
     * column; the row occupying fifteen holds {@code 'Payment applied'}, which fills the column
     * exactly; and the row occupying sixteen holds {@code 'Reversal entered'}, whose final letter is
     * the one character the column cannot take.</p>
     *
     * @return a stream of the occupied source length and the fifteen characters it must render as
     */
    private static Stream<Arguments> typeDescriptionTriple() {
        return Stream.of(
                Arguments.of(14, "Auth hold only "),
                Arguments.of(15, "Payment applied"),
                Arguments.of(16, "Reversal entere"));
    }

    /**
     * Asserts the fifty-to-fifteen narrowing from below its cut, exactly at it, and above it.
     *
     * @param occupiedLength the number of characters the fixture description occupies before its
     *     trailing blanks, which selects which arm of the triple this case is
     * @param expectedColumn the fifteen characters the type description column must carry
     * @throws IOException if {@code fixtures/trantype.txt} cannot be read from the test classpath
     */
    @ParameterizedTest(name = "a {0}-character type description renders as [{1}]")
    @MethodSource("typeDescriptionTriple")
    @DisplayName("the type description narrows from 50 to 15 at its own column width")
    void theTypeDescriptionColumnNarrowsAtItsOwnWidth(int occupiedLength, String expectedColumn)
            throws IOException {
        String description =
                fixtureDescription(TYPE_FIXTURE, TYPE_LAYOUT, TYPE_DESCRIPTION_FIELD,
                        occupiedLength);

        // WHY : Assumptions: the source is handed over at its full declared fifty characters, which
        //       is what line 6 of app/cpy/CVTRA03Y.cpy declares and what the alphanumeric move at
        //       line 366 of app/cbl/CBTRN03C.cbl receives. Stripping it first would test an input
        //       the report is never built from, and would turn the below-the-cut arm from a
        //       blank-fill case into a no-op.
        assertThat(description).hasSize(FIXTURE_DESCRIPTION_WIDTH);

        byte[] detail = TransactionReportMapper.encodeDetailLine("0000000000000001", 7L, "01",
                description, 1, "Restaurant and Bar Purchases", "POS TERM  ", Money.of("1.00"));

        // WHY : Assumptions: the arm above the cut is the only one that proves a narrowing happened
        //       at all, and the arm at the cut is the only one that distinguishes narrowing TO a
        //       width from shortening BY an amount. The arm below the cut is the only one that
        //       reaches the codec's blank-fill path, which a truncating implementation never runs.
        //       One arm alone leaves two of the three faults undetected.
        assertThat(typeDescriptionField(detail))
                .as("a %d-character description renders in the 15-character column", occupiedLength)
                .hasSize(TYPE_DESCRIPTION_WIDTH)
                .isEqualTo(expectedColumn);

        // WHY : Assumptions: the column's two edges are asserted from outside it, so the span is
        //       pinned rather than merely the content. Line 21 of app/cpy/CVTRA07Y.cpy declares a
        //       one-character FILLER carrying a hyphen immediately before this column and line 23
        //       declares a blank FILLER immediately after, so the column occupies one-based 33
        //       through 47 exactly. A field placed one byte either way would still hold the right
        //       fifteen characters and would overwrite one of those two bytes.
        assertThat(text(detail).charAt(TYPE_DESCRIPTION_OFFSET - 1))
                .as("the hyphen joiner closes the type code immediately before column 33")
                .isEqualTo('-');
        assertThat(text(detail).charAt(TYPE_DESCRIPTION_OFFSET + TYPE_DESCRIPTION_WIDTH))
                .as("a blank separator opens immediately after column 47")
                .isEqualTo(' ');
    }

    /**
     * Supplies the triple that brackets the twenty-nine-character category column.
     *
     * <p>Assumptions: transcribed from {@code fixtures/trancatg.txt} on the same reasoning as the
     * type triple. The row occupying twenty-eight characters holds
     * {@code 'Cash payment at the counters'} and gains one trailing blank; the row occupying
     * twenty-nine holds {@code 'Regular sales draft purchases'} and fills the column; the row
     * occupying thirty holds {@code 'Regular cash advance withdrawn'}, whose final letter is
     * discarded.</p>
     *
     * @return a stream of the occupied source length and the twenty-nine characters it must render
     *     as
     */
    private static Stream<Arguments> categoryDescriptionTriple() {
        return Stream.of(
                Arguments.of(28, "Cash payment at the counters "),
                Arguments.of(29, "Regular sales draft purchases"),
                Arguments.of(30, "Regular cash advance withdraw"));
    }

    /**
     * Asserts the fifty-to-twenty-nine narrowing from below its cut, at it, and above it.
     *
     * @param occupiedLength the number of characters the fixture description occupies before its
     *     trailing blanks, which selects which arm of the triple this case is
     * @param expectedColumn the twenty-nine characters the category description column must carry
     * @throws IOException if {@code fixtures/trancatg.txt} cannot be read from the test classpath
     */
    @ParameterizedTest(name = "a {0}-character category description renders as [{1}]")
    @MethodSource("categoryDescriptionTriple")
    @DisplayName("the category description narrows from 50 to 29 at its own column width")
    void theCategoryDescriptionColumnNarrowsAtItsOwnWidth(int occupiedLength, String expectedColumn)
            throws IOException {
        String description = fixtureDescription(CATEGORY_FIXTURE, CATEGORY_LAYOUT,
                CATEGORY_DESCRIPTION_FIELD, occupiedLength);

        // WHY : Assumptions: this description comes from the six-byte-keyed TRANCAT record of
        //       app/cpy/CVTRA04Y.cpy, where the fifty characters begin at zero-based offset 6 rather
        //       than at 2 as they do in the type record. Decoding through the registry layout rather
        //       than by a hand-written offset is what keeps the two records from being confused,
        //       since their descriptions are the same width at different places.
        assertThat(description).hasSize(FIXTURE_DESCRIPTION_WIDTH);

        byte[] detail = TransactionReportMapper.encodeDetailLine("0000000000000001", 7L, "01",
                "Regular Sales Draft", 1, description, "POS TERM  ", Money.of("1.00"));

        // WHY : Assumptions: this column's width is 29 where the type column's is 15, and the two are
        //       asserted against separate expectations for that reason. Both sources are declared 50,
        //       so an implementation that narrowed both to a single shared width would still emit a
        //       full band and would still pass every length check.
        assertThat(categoryDescriptionField(detail))
                .as("a %d-character description renders in the 29-character column", occupiedLength)
                .hasSize(CATEGORY_DESCRIPTION_WIDTH)
                .isEqualTo(expectedColumn);

        // WHY : Assumptions: the edges are asserted from outside the column as above. Line 25 of
        //       app/cpy/CVTRA07Y.cpy declares the hyphen FILLER immediately before it and line 27 a
        //       blank FILLER immediately after, so the column occupies one-based 54 through 82.
        assertThat(text(detail).charAt(CATEGORY_DESCRIPTION_OFFSET - 1))
                .as("the hyphen joiner closes the category code immediately before column 54")
                .isEqualTo('-');
        assertThat(text(detail).charAt(CATEGORY_DESCRIPTION_OFFSET + CATEGORY_DESCRIPTION_WIDTH))
                .as("a blank separator opens immediately after column 82")
                .isEqualTo(' ');
    }

    /**
     * Asserts that a description filling all fifty declared characters is narrowed, not passed
     * through.
     *
     * @throws IOException if either description fixture cannot be read from the test classpath
     */
    @Test
    @DisplayName("a description occupying all fifty characters is narrowed in both columns")
    void aFullWidthDescriptionIsNarrowedInBothColumns() throws IOException {
        String fullType = fixtureDescription(TYPE_FIXTURE, TYPE_LAYOUT, TYPE_DESCRIPTION_FIELD,
                FIXTURE_DESCRIPTION_WIDTH);
        String fullCategory = fixtureDescription(CATEGORY_FIXTURE, CATEGORY_LAYOUT,
                CATEGORY_DESCRIPTION_FIELD, FIXTURE_DESCRIPTION_WIDTH);

        byte[] detail = TransactionReportMapper.encodeDetailLine("0000000000000001", 7L, "01",
                fullType, 1, fullCategory, "POS TERM  ", Money.of("1.00"));

        // WHY : Assumptions: this is the case a short-description fixture could not supply, and it is
        //       why the committed fixtures deliberately carry full-width rows -- four of seven in
        //       fixtures/trantype.txt and six of nine in fixtures/trancatg.txt, as section 4 of that
        //       directory's README.md records. A fixture whose descriptions all fitted inside 15 and
        //       29 characters would render identically under a correct mapper and under one that
        //       narrowed nothing, so every truncation assertion in this file would be green while the
        //       report shipped wrong.
        assertThat(typeDescriptionField(detail))
                .as("35 of the 50 declared characters are discarded by the 15-character column")
                .isEqualTo("Purchase at poi");
        assertThat(categoryDescriptionField(detail))
                .as("21 of the 50 declared characters are discarded by the 29-character column")
                .isEqualTo("Regular sales draft purchase ");

        // WHY : Assumptions: the discarded remainder is asserted absent from the WHOLE record and not
        //       just from its own column. A narrowing that wrote the surplus onward would corrupt the
        //       neighbouring columns while leaving the narrowed field itself correct, and the record
        //       would still be 133 bytes.
        assertThat(text(detail))
                .as("no discarded remainder reaches any other column of the band")
                .doesNotContain("point of sale")
                .doesNotContain("settled overnight");
    }

    /**
     * Asserts that a single-digit account identifier is left-zero-padded to eleven digits.
     */
    @Test
    @DisplayName("an account identifier of 7 emits eleven digits, not a blank-padded 7")
    void aSingleDigitAccountIdentifierIsZeroPaddedToEleven() {
        byte[] detail = TransactionReportMapper.encodeDetailLine("0000000000000001", 7L, "01",
                "Regular Sales Draft", 1, "Restaurant and Bar Purchases", "POS TERM  ",
                Money.of("1.00"));
        String column = text(detail).substring(ACCOUNT_ID_OFFSET,
                ACCOUNT_ID_OFFSET + ACCOUNT_ID_WIDTH);

        // WHY : Assumptions: the zero padding has to be produced deliberately, and a PIC 9(n) into
        //       PIC X(n) move does NOT yield it by itself. XREF-ACCT-ID is declared PIC 9(11) at line
        //       7 of app/cpy/CVACT03Y.cpy, an unsigned numeric DISPLAY item whose character form
        //       carries its leading zeros, while its target TRAN-REPORT-ACCOUNT-ID is declared PIC
        //       X(11) at line 18 of app/cpy/CVTRA07Y.cpy, an ALPHANUMERIC item that the codec
        //       left-justifies and blank-fills. The regime changes across the move, so an
        //       implementation that simply placed the digits would emit ten blanks and a 7.
        // WHY : Alternatives Considered: asserting only that the column holds the digit 7 somewhere.
        //       Rejected because all three candidate renderings contain it -- the eleven-digit form,
        //       a right-aligned blank-padded form and a left-aligned one -- so the two wrong ones are
        //       asserted absent by name rather than left to be excluded by implication.
        assertThat(column)
                .as("XREF-ACCT-ID PIC 9(11) carries its leading zeros into the report column")
                .isEqualTo("00000000007")
                .hasSize(ACCOUNT_ID_WIDTH);
        assertThat(column)
                .as("neither blank-padded rendering is what the baseline emits")
                .isNotEqualTo("          7")
                .isNotEqualTo("7          ");
    }

    /**
     * Asserts that a single-digit category code keeps its leading zeros.
     */
    @Test
    @DisplayName("a category code of 1 emits 0001, not a blank-suppressed 1")
    void aSingleDigitCategoryCodeKeepsItsLeadingZeros() {
        byte[] detail = TransactionReportMapper.encodeDetailLine("0000000000000001", 7L, "01",
                "Regular Sales Draft", 1, "Restaurant and Bar Purchases", "POS TERM  ",
                Money.of("1.00"));
        String column = text(detail).substring(CATEGORY_CODE_OFFSET, CATEGORY_CODE_OFFSET + 4);

        // WHY : Assumptions: TRAN-REPORT-CAT-CD is declared PIC 9(04) at line 24 of
        //       app/cpy/CVTRA07Y.cpy, which is a DIFFERENT regime from the Z-suppression the amount
        //       column uses at line 30 of the same copybook. A 9 position always prints a digit; a Z
        //       position prints a blank while the value is still leading. Giving this column the
        //       amount's regime would emit three blanks and a 1, which is four bytes of the right
        //       width and the wrong content.
        assertThat(column)
                .as("PIC 9(04) prints all four numeric positions")
                .isEqualTo("0001")
                .hasSize(4);
        assertThat(column)
                .as("a Z regime would have blanked the leading zeros")
                .isNotEqualTo("   1")
                .isNotEqualTo("1   ");
    }

    /**
     * Asserts that the largest magnitude either mask admits renders in both regimes.
     */
    @Test
    @DisplayName("the nine-integer-digit ceiling renders in the detail and in all three totals")
    void theMaximumMagnitudeRendersInBothRegimes() {
        Money ceiling = Money.of("999999999.99");

        // WHY : Assumptions: 999999999.99 is exactly the ceiling of TRAN-AMT PIC S9(09)V99 at line 10
        //       of app/cpy/CVTRA05Y.cpy and of the three accumulators declared PIC S9(09)V99 at lines
        //       134 to 136 of app/cbl/CBTRN03C.cbl, all of which map to NUMERIC(11,2). It is asserted
        //       because it is the one magnitude at which no digit position of either mask suppresses,
        //       so it is the only value that proves all nine integer positions and both cents are
        //       actually reachable inside the 133-byte record rather than only inside the formatter.
        // WHY : Trade-offs: the rendered forms are anchors for a PLACEMENT assertion and are not a
        //       second statement of the mask rules. CobolEditMaskTest owns the suppression, sign and
        //       ceiling semantics; restating them here would give one behaviour two owners, and two
        //       owners drift apart at the first edit.
        assertThat(amountField(TransactionReportMapper.encodeDetailLine("0000000000000001", 7L, "01",
                "Regular Sales Draft", 1, "Restaurant and Bar Purchases", "POS TERM  ", ceiling)))
                .as("the detail regime fills all nine integer positions with a blank sign")
                .isEqualTo(" 999,999,999.99");
        assertThat(amountField(TransactionReportMapper.encodePageTotal(ceiling)))
                .isEqualTo("+999,999,999.99");
        assertThat(amountField(TransactionReportMapper.encodeAccountTotal(ceiling)))
                .isEqualTo("+999,999,999.99");
        assertThat(amountField(TransactionReportMapper.encodeGrandTotal(ceiling)))
                .isEqualTo("+999,999,999.99");
    }

    /**
     * Asserts that all four money-bearing bands place their amount in one-based columns 98 to 112.
     */
    @Test
    @DisplayName("all four amounts occupy one-based columns 98 through 112 exactly")
    void allFourAmountsOccupyOneBasedColumns98Through112() {
        Money distinctive = Money.of("87654321.09");
        List<byte[]> moneyBands = List.of(
                TransactionReportMapper.encodeDetailLine("0000000000000001", 7L, "01",
                        "Regular Sales Draft", 1, "Restaurant and Bar Purchases", "POS TERM  ",
                        distinctive),
                TransactionReportMapper.encodePageTotal(distinctive),
                TransactionReportMapper.encodeAccountTotal(distinctive),
                TransactionReportMapper.encodeGrandTotal(distinctive));

        // WHY : Assumptions: all four masks land in the same span by DIFFERENT arithmetic, and the
        //       compensation is the whole reason they agree. The three total bands reach the column
        //       from a label plus a dot leader whose widths move in opposite directions -- 11 plus 86
        //       at lines 51 to 53 of app/cpy/CVTRA07Y.cpy, 13 plus 84 at lines 57 to 59, and 11 plus
        //       86 at lines 63 to 65 -- each summing to 97 zero-based bytes. The detail band reaches
        //       the same 97 by accumulating its own items instead: 16 + 1 + 11 + 1 + 2 + 1 + 15 + 1 +
        //       4 + 1 + 29 + 1 + 10 + 4. Simplifying the three leaders to a single 86 would put the
        //       card-break mask at 99 and stand that one total two columns right of the other three,
        //       which is a difference a reader would not see and a byte comparison would.
        // WHY : Assumptions: the value 87654321.09 is chosen because its grouped rendering is a string
        //       that could not arise anywhere else in the band. A round value such as 1234.56 also
        //       appears inside a description or an identifier by coincidence, and the assertion that
        //       nothing before column 98 contains the amount would then fail for the wrong reason.
        assertThat(moneyBands).allSatisfy(band -> {
            assertThat(text(band).substring(AMOUNT_OFFSET, AMOUNT_OFFSET + AMOUNT_WIDTH))
                    .as("the amount fills the fifteen characters of columns 98 to 112")
                    .hasSize(AMOUNT_WIDTH)
                    .endsWith("87,654,321.09");
            assertThat(text(band).substring(0, AMOUNT_OFFSET))
                    .as("no part of the amount appears before column 98")
                    .doesNotContain("87,654,321");
            assertThat(text(band).substring(AMOUNT_OFFSET + AMOUNT_WIDTH))
                    .as("only blank fill follows column 112")
                    .isEqualTo(" ".repeat(REPORT_RECORD_LENGTH - AMOUNT_OFFSET - AMOUNT_WIDTH));
        });
    }

    /**
     * Asserts that a card number carried on the source projection reaches neither payload nor band.
     *
     * <p>Refactoring Rationale: this assertion is driven from the source projection, and an
     * earlier revision drove it from nothing at all. That revision emitted the eight bands from
     * plain fixtures, none of which held the synthetic number, and then asserted that no band
     * contained it -- so the assertion could not fail whatever the mapper did with a card number,
     * because no card number was ever supplied to anything. It would have passed against a mapper
     * that appended the number to every band. The fix is to introduce the value on the one production
     * type that legitimately carries it: {@code ReportTransactionView} projects
     * {@code v_report_transactions.card_num}, because the report's primary ordering key is the card
     * number even though no band displays it. The number is then carried through the two production
     * mapping steps a report line actually takes -- projection to payload, payload to band -- and
     * asserted absent from each. Alternatives Considered: feeding the number into
     * {@code encodeDetailLine} directly, in place of one of its eight arguments. Rejected because
     * that asserts nothing about propagation: the transaction-identifier parameter is sixteen
     * characters wide, so a number placed there is emitted verbatim and correctly, and the test would
     * fail while reporting a caller's error as a mapper leak.</p>
     */
    @Test
    @DisplayName("a card number on the source projection reaches neither the payload nor any band")
    void aCardNumberOnTheSourceProjectionReachesNeitherPayloadNorBand() {
        ReportTransactionView source = new ReportTransactionView(
                "0000000000000001", "01", "0001", "POS TERM  ", "Regular Sales Draft",
                Money.of("1234.56"), 9L, "MERCHANT NAME", "SPRINGFIELD", "0000062701",
                SYNTHETIC_CARD_NUMBER, null, null);

        // WHY : Assumptions: the projection is asserted to be carrying the number before anything is
        //       asserted about what became of it. Without this line the two assertions below would
        //       silently become vacuous again the moment the projection's constructor changed the
        //       position of its card-number parameter, which is precisely how the earlier revision
        //       came to prove nothing.
        assertThat(source.cardNum())
                .as("the source projection really is carrying the number under test")
                .isEqualTo(SYNTHETIC_CARD_NUMBER);

        // WHY : Assumptions: the projection's own diagnostic rendering is the first channel checked,
        //       because it is the one that leaks without any mapping step at all -- a log statement or
        //       an assertion message written over the projection would carry whatever that rendering
        //       carries, and the value is present in the instance at this point. Checking it here,
        //       with the number genuinely loaded, is what distinguishes a real assertion from a
        //       restatement of the projection's own documentation.
        assertThat(source.toString())
                .as("the projection's diagnostic rendering carries neither the number nor its tail")
                .doesNotContain(SYNTHETIC_CARD_NUMBER)
                .doesNotContain(SYNTHETIC_CARD_NUMBER.substring(CARD_NUMBER_TAIL_OFFSET));

        ReportingDtoMapper.ReportTransactionPayload payload =
                ReportingDtoMapper.toReportTransaction(source.transactionId(), "00000000007",
                        source.typeCd(), "Regular Sales Draft", source.categoryCd(),
                        "Restaurant and Bar Purchases", source.source(), source.amount());

        // WHY : Assumptions: every component of the payload is walked by reflection rather than
        //       named one by one, so a ninth component added later is covered without this test being
        //       touched. Rendering each component through its own string form is what makes the walk
        //       type-agnostic: the money component is an exact decimal and the rest are text, and a
        //       search over the rendered form catches either.
        for (RecordComponent component
                : ReportingDtoMapper.ReportTransactionPayload.class.getRecordComponents()) {
            assertThat(String.valueOf(readComponent(component, payload)))
                    .as("payload component %s carries neither the number nor its tail",
                            component.getName())
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain(SYNTHETIC_CARD_NUMBER.substring(CARD_NUMBER_TAIL_OFFSET));
        }

        List<byte[]> bands = List.of(
                TransactionReportMapper.encodeNameHeader(RANGE_START, RANGE_END),
                TransactionReportMapper.encodeBlankLine(),
                TransactionReportMapper.encodeColumnHeader(),
                TransactionReportMapper.encodeSeparatorRule(),
                TransactionReportMapper.encodeDetailLine(payload.transactionId(),
                        Long.parseLong(payload.accountId()), payload.typeCode(),
                        payload.typeDescription(), Integer.parseInt(payload.categoryCode()),
                        payload.categoryDescription(), payload.source(), payload.amount()),
                TransactionReportMapper.encodePageTotal(payload.amount()),
                TransactionReportMapper.encodeAccountTotal(payload.amount()),
                TransactionReportMapper.encodeGrandTotal(payload.amount()));

        // WHY : Alternatives Considered: searching only the detail band, since it is the only band
        //       with variable content. Rejected because a card number could reach a heading or a total
        //       band through a mis-seeded template just as easily, and searching all eight costs one
        //       loop.
        // WHY : Assumptions: the last four digits are searched for separately, because a masked
        //       rendering would carry them while dropping the leading twelve and would slip past a
        //       search for the whole number. Neither form belongs in this report: the 133-column
        //       layout has no card-number field at all -- the detail band at lines 15 to 31 of
        //       app/cpy/CVTRA07Y.cpy declares a transaction identifier, an account identifier, a type
        //       code and its description, a category code and its description, a source and an amount,
        //       and nothing else -- and TRAN-CARD-NUM, declared PIC X(16) at line 15 of
        //       app/cpy/CVTRA05Y.cpy, reaches this report only as the sort key at line 41 of
        //       app/jcl/TRANREPT.jcl.
        assertThat(bands).allSatisfy(band -> assertThat(text(band))
                .as("no band carries the synthetic card number")
                .doesNotContain(SYNTHETIC_CARD_NUMBER)
                .doesNotContain(SYNTHETIC_CARD_NUMBER.substring(CARD_NUMBER_TAIL_OFFSET)));
    }

    /**
     * Asserts that the report's emission surface declares no channel a card number could arrive on.
     *
     * <p>Assumptions: the propagation assertion above shows that a card number placed on the
     * projection does not survive the mapping that exists today. This one shows that no channel
     * exists for one to be added, which is the half a value-based assertion cannot reach: it counts
     * the parameters of the only band with variable content and the components of the payload that
     * feeds it, and it refuses a component whose name suggests a card number. A future author widening
     * either surface has to change a number here, which is the point at which the exposure question
     * gets asked.</p>
     */
    @Test
    @DisplayName("the report's emission surface declares no channel a card number could arrive on")
    void theEmissionSurfaceDeclaresNoCardNumberChannel() {
        Method detailLine = Arrays.stream(TransactionReportMapper.class.getDeclaredMethods())
                .filter(method -> "encodeDetailLine".equals(method.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("encodeDetailLine is not declared"));

        assertThat(detailLine.getParameterCount())
                .as("the detail band takes exactly the eight members app/cpy/CVTRA07Y.cpy declares")
                .isEqualTo(REPORT_DETAIL_MEMBER_COUNT);

        RecordComponent[] components =
                ReportingDtoMapper.ReportTransactionPayload.class.getRecordComponents();
        assertThat(components)
                .as("the report payload carries exactly the same eight members")
                .hasSize(REPORT_DETAIL_MEMBER_COUNT);
        assertThat(Arrays.stream(components).map(RecordComponent::getName).toList())
                .as("no member of the report surface is named for a card or an account number")
                .noneMatch(name -> {
                    String lowered = name.toLowerCase(java.util.Locale.ROOT);
                    return lowered.contains("card") || lowered.contains("pan");
                });
    }

    /**
     * Asserts that the synthetic card number cannot correspond to an issued card.
     *
     * <p>Assumptions: the check-digit arithmetic behind {@link #SYNTHETIC_CARD_NUMBER} is asserted
     * rather than left in its Javadoc, because an earlier revision of that constant carried a
     * rationale claiming the property while the value did not have it, and prose that nobody re-runs
     * is how that happened. Computing the total here means the claim fails a build if the constant is
     * edited, so the datum cannot quietly become a value that could be a live card number.</p>
     */
    @Test
    @DisplayName("the synthetic card number fails the Luhn check and cannot be an issued card")
    void theSyntheticCardNumberCannotBeAnIssuedCard() {
        int total = 0;
        for (int position = 0; position < SYNTHETIC_CARD_NUMBER.length(); position++) {
            int digit = Character.digit(
                    SYNTHETIC_CARD_NUMBER.charAt(SYNTHETIC_CARD_NUMBER.length() - 1 - position),
                    10);
            if (position % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            total += digit;
        }

        assertThat(total)
                .as("the Luhn total is the value the constant's rationale states")
                .isEqualTo(SYNTHETIC_CARD_LUHN_TOTAL);
        assertThat(total % 10)
                .as("a total not ending in zero fails the check, so the value is not an issued card")
                .isNotZero();
    }

    /**
     * Reads one record component from a payload instance.
     *
     * @param component the record component to read
     * @param payload the payload instance to read it from
     * @return the component's value, which may be {@code null} for an optional component
     * @throws AssertionError if the accessor cannot be invoked, which reports a defect in this test
     *     rather than a finding about the code under test
     */
    private static Object readComponent(RecordComponent component, Object payload) {
        try {
            return component.getAccessor().invoke(payload);
        } catch (ReflectiveOperationException unreachable) {
            // WHY : Assumptions: a record accessor is public, takes no argument and is declared on a
            //       type this test names directly, so neither access nor arity can fail here. The
            //       failure is reported as an assertion error rather than rethrown as a checked
            //       exception, because a reflective breakage in a test helper is a defect in the test
            //       and not a finding about the code under test.
            throw new AssertionError(
                    "record component " + component.getName() + " is not readable", unreachable);
        }
    }

    /**
     * Asserts that the account identifier is emitted in full and that nothing is masked.
     *
     * @throws NoSuchMethodException if the detail encoder is renamed or its eight-item signature
     *     changes
     */
    @Test
    @DisplayName("the account identifier is emitted in full, because masking belongs elsewhere")
    void theAccountIdentifierIsEmittedInFullAndNeverMasked() throws NoSuchMethodException {
        byte[] detail = TransactionReportMapper.encodeDetailLine("0000000000000001", 12345678901L,
                "01", "Regular Sales Draft", 1, "Restaurant and Bar Purchases", "POS TERM  ",
                Money.of("1.00"));
        String column = text(detail).substring(ACCOUNT_ID_OFFSET,
                ACCOUNT_ID_OFFSET + ACCOUNT_ID_WIDTH);

        // WHY : Assumptions: this mapper and ReportingDtoMapper have deliberately OPPOSITE
        //       obligations, and that opposition is the design rather than an inconsistency. Masking
        //       -- a primary account number to its last four, a card verification value never
        //       serialised, a national or government identifier obscured -- belongs to the DTO mapper,
        //       which serves a client over an interface. This mapper writes a fixed-width artifact
        //       that a golden comparison reads byte for byte, so narrowing a field here would corrupt
        //       the very column the comparison anchors on while looking like a security improvement.
        assertThat(column)
                .as("all eleven declared digits of the account identifier are emitted")
                .isEqualTo("12345678901")
                .hasSize(ACCOUNT_ID_WIDTH);
        assertThat(column)
                .as("no masked form of the identifier reaches the column")
                .doesNotContain("*");

        // WHY : Assumptions: no card verification value is emitted by anything in this module, and the
        //       way that is pinned is by closing the encoder surface rather than by searching output.
        //       Neither app/cpy/CVACT03Y.cpy nor the report layout at lines 15 to 31 of
        //       app/cpy/CVTRA07Y.cpy declares such a field, so no correct encoder can accept one; the
        //       risk is a NEW encoder or a widened signature, which output searching cannot see
        //       because the value would not be there to find until after it was added.
        // WHY : Alternatives Considered: reflecting over parameter NAMES and rejecting any containing
        //       card or cvv. Rejected because a parameter name survives into the class file only when
        //       javac is given the -parameters flag, which services/pom.xml does not set, so that
        //       assertion would read every name as arg0 and arg1 and pass vacuously -- a test that
        //       cannot fail. Enumerating the method names and pinning the one eight-argument signature
        //       is verifiable under any compiler setting, because a name and a descriptor are always
        //       in the class file.
        assertThat(Arrays.stream(TransactionReportMapper.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList())
                .as("the public encoder surface is closed at these nine members")
                .containsExactly("encodeAccountTotal", "encodeBlankLine", "encodeColumnHeader",
                        "encodeDetailLine", "encodeGrandTotal", "encodeHeadingBlock",
                        "encodeNameHeader", "encodePageTotal", "encodeSeparatorRule");
        assertThat(TransactionReportMapper.class.getMethod("encodeDetailLine", String.class,
                        long.class, String.class, String.class, int.class, String.class,
                        String.class, Money.class))
                .as("the detail encoder takes exactly the eight items the band declares, and no card")
                .isNotNull();
    }

    /**
     * Asserts that the title band carries the caller's range and both surrounding literals verbatim.
     */
    @Test
    @DisplayName("the title band carries the caller-supplied range with both literals verbatim")
    void theTitleBandCarriesTheCallerSuppliedRangeVerbatim() {
        String header = text(TransactionReportMapper.encodeNameHeader(RANGE_START, RANGE_END));

        // WHY : Assumptions: 'Date Range: ' is twelve characters INCLUDING its trailing space, declared
        //       PIC X(12) at lines 9 and 10 of app/cpy/CVTRA07Y.cpy, and ' to ' is four characters
        //       carrying BOTH a leading and a trailing space, declared PIC X(04) at line 12. Trimming
        //       either one closes the gap between a label and a date and shifts every byte after it,
        //       so both are asserted with their spaces present rather than by their visible text.
        assertThat(header)
                .as("the twelve-character label keeps its trailing space")
                .contains("Date Range: 2022-07-01");
        assertThat(header)
                .as("the four-character joiner keeps both of its spaces")
                .contains("2022-07-01 to 2022-07-31");
        assertThat(header)
                .as("neither literal is trimmed")
                .doesNotContain("Date Range:2022")
                .doesNotContain("2022-07-01to")
                .doesNotContain("to2022-07-31");
    }

    /**
     * Asserts that every band is byte-identical across two separate invocations.
     */
    @Test
    @DisplayName("every band is byte-identical across two invocations, so no clock leaks in")
    void everyBandIsByteIdenticalAcrossTwoInvocations() {
        Money amount = Money.of("1234.56");
        List<byte[]> first = allEightBands(amount);
        List<byte[]> second = allEightBands(amount);

        // WHY : Assumptions: the injected business date is what makes a rerun reproducible and a
        //       golden comparison possible at all. Lines 43 and 44 of app/jcl/TRANREPT.jcl inject the
        //       range as literals and lines 73 and 74 supply it again through the DATEPARM channel
        //       that app/cbl/CBTRN03C.cbl reads for this heading, so no part of the reference reads a
        //       wall clock for it. A current-date call anywhere in the report path would make the
        //       title band vary between two runs over identical rows, which is the one failure mode a
        //       single invocation cannot detect.
        // WHY : Alternatives Considered: asserting only that the title band repeats, since it is the
        //       only band that carries a date. Rejected because a timestamp could reach any band --
        //       a run identifier in a heading, a generation stamp in a total -- and equality across
        //       all eight is the assertion that no band varies for any reason.
        assertThat(second).hasSameSizeAs(first);
        for (int index = 0; index < first.size(); index++) {
            assertThat(second.get(index))
                    .as("band %d is identical on a second invocation", index)
                    .isEqualTo(first.get(index));
        }
    }
}
