package com.carddemo.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.money.Money;
import com.carddemo.common.security.HtmlTextEncoder;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds the markup statement assembler to the bytes it must emit, and to deriving nothing itself.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this class is the executable consumer of {@link StatementHtmlMapper}. It pins three
 * families of property that nothing else in the module pins. <b>Geometry</b>: every record the
 * assembler returns is exactly {@value StatementHtmlMapper#HTML_RECORD_LENGTH} bytes, blank-padded,
 * and no assembly is ever allowed to overrun that. <b>Content</b>: all
 * {@value StatementHtmlMapper#FRAGMENT_COUNT} declared markup fragments carry their reference bytes
 * character for character, the account heading lines its three declared components up at 59
 * characters, and the two assembly regimes treat trailing blanks in exactly opposite ways.
 * <b>Provenance</b>: every value the document carries arrives from the prepared-field records that
 * {@link StatementTextMapper} produced, and this assembler re-derives none of them.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises outside the test engine, so the type
 * itself accepts no parameter, returns nothing and throws nothing. The inapplicability is stated
 * rather than passed over, because user-specified Rule 1 (Explainability) forbids at its line 39 a
 * docstring that omits parameters, return values or purpose, and a reader has to be able to tell a
 * declared inapplicability from an oversight. Every member below carries its own block.</p>
 *
 * <h2>Assumptions: the declared record length is 100, and three independent artifacts say so</h2>
 *
 * <p>Every assertion about width in this class resolves to
 * {@link StatementHtmlMapper#HTML_RECORD_LENGTH} rather than to a literal, and that constant is
 * corroborated three ways in the reference material: the reusable fragment buffer
 * {@code HTML-FIXED-LN PIC X(100)} at line 149 of {@code app/cbl/CBSTM03A.CBL}, under the group
 * {@code 01 HTML-LINES.} opened at line 148; the three assembly buffers {@code HTML-ADDR-LN},
 * {@code HTML-BSIC-LN} and {@code HTML-TRAN-LN}, all {@code PIC X(100)}, at lines 221, 222 and 223;
 * and the {@code DCB=(LRECL=100,...)} of the markup output data set at line 94 of
 * {@code app/jcl/CREASTMT.JCL}, inside the DD statement that spans lines 92 to 96. A fourth
 * declaration, the output record {@code 01 FD-HTMLFILE-REC PIC X(100)} at line 47 under the file
 * declaration at line 46, agrees with all three.</p>
 *
 * <p>Assumptions: 100 and 80 are NEVER interchangeable and this class asserts the distinction
 * outright. The plain-text statement is 80, declared {@code 01 FD-STMTFILE-REC PIC X(80)} at line 45
 * of {@code app/cbl/CBSTM03A.CBL} and {@code LRECL=80} at line 89 of {@code app/jcl/CREASTMT.JCL}.
 * Two artifacts, two declared lengths, one program. Substituting either width produces a record that
 * is well formed and wrong, and only a byte comparison notices.</p>
 *
 * <h2>Assumptions: no layout descriptor and no fixed-width codec appears in this file</h2>
 *
 * <p>This is the clearest structural difference between the two statement emitters and it is stated
 * here so that a reader does not go looking for the missing import. The plain-text bands are PLACED
 * BY FIELD OFFSET through a record descriptor, so {@code StatementTextMapper} owns descriptors and
 * the sibling test of those descriptors pins their geometry. The markup records are ASSEMBLED BY
 * CONCATENATION into a record of one declared length, so there is no descriptor for them, no field
 * offset to check and nothing for a fixed-width codec to encode. Binding this file to the layout
 * descriptors would therefore assert a structure the assembler does not have.</p>
 *
 * <p>Assumptions: the padding mechanism is correspondingly different, and the difference is
 * transcribed rather than unified. In the reference every markup assembly is preceded by a blanking
 * move -- at lines 561, 569, 577, 585, 686, 698 and 710 of {@code app/cbl/CBSTM03A.CBL} -- so the
 * target is all blanks before the concatenation begins and whatever the concatenation does not fill
 * stays blank. That, and not an encoder, is what makes a 23-character transaction-identifier cell
 * into a 100-character record.</p>
 *
 * <h2>Assumptions: bytes are asserted, never a parsed document</h2>
 *
 * <p>No markup parser, no XML reader and no document model appears in this file, and their absence is
 * the point rather than an omission. Every parser normalises insignificant whitespace, re-orders or
 * canonicalises attributes and re-serialises tags -- and the run of two blanks inside
 * {@code HTML_L08}, the single blank at the end of the account heading prefix, and the exact
 * attribute order of every styled cell are precisely the things this class exists to pin. A
 * parse-then-compare assertion would pass a document whose bytes had drifted, which is the one
 * outcome the golden statement comparison outside this module cannot tolerate.</p>
 *
 * <h2>Assumptions: the encoding is single-byte, so a length in characters is a length in bytes</h2>
 *
 * <p>Every record is decoded with {@link StandardCharsets#US_ASCII} rather than with a platform
 * default, and one case below asserts that each record's byte length equals its character length.
 * Without that, "the record is 100 long" would be two different claims -- the data set contract at
 * line 94 of {@code app/jcl/CREASTMT.JCL} is a contract about bytes -- and a multi-byte character
 * would satisfy one while breaking the other.</p>
 *
 * <h2>Assumptions: the boundary values are transcribed here rather than read from a fixture</h2>
 *
 * <p>Three fixture properties have to be reached for the assertions below to prove rather than merely
 * exercise: a name whose assembled 75-character form exceeds the 50 the markup cell narrows it to, so
 * the narrowing is provably a truncation; an address line carrying TRAILING BLANKS, so the
 * right-trim regime is provably trimming; and a transaction description carrying TRAILING BLANKS, so
 * the whole-item regime is provably NOT trimming. Every one is supplied as a transcribed literal at
 * the point of use, and each carries a note stating the boundary it reaches.</p>
 *
 * <p>Alternatives Considered: driving them from this module's fixture files. Rejected on measurement.
 * The register at {@code src/test/resources/fixtures/README.md} declares four data record types --
 * {@code ACCOUNT}, {@code CUSTOMER}, {@code TRANTYPE} and {@code TRANCAT} -- and none of them is a
 * {@code TRNX} record, so no fixture description exists to carry trailing blanks in the first place;
 * and {@code ReportingFixtureContractTest} asserts that the fixture directory contains EXACTLY its
 * six registered entries, so adding one would fail that sibling rather than help this one. The
 * sibling {@code StatementTextMapperTest} reaches its own four boundaries the same way and for the
 * same recorded reason, so the two emitters are proven against values built by one convention.</p>
 *
 * <p>Assumptions: {@code TRNX} and {@code TRAN} are distinct record types and neither is an alias of
 * the other. {@code TRNX-RECORD} is declared at line 20 of {@code app/cpy/COSTM01.CPY} with
 * {@code TRNX-DESC PIC X(100)} at line 28 and {@code TRNX-AMT PIC S9(09)V99} at line 29; the
 * statement program reads that record and narrows its description into the band item this artifact
 * renders. Treating the two as one record would read the amount from the wrong offset entirely.</p>
 *
 * <h2>Assumptions: three owners share one rule about the money masks, and this file owns the least</h2>
 *
 * <p>{@code CobolEditMaskTest} owns what each edit mask DOES -- suppression, sign placement, width.
 * {@code StatementTextMapperTest} owns WHICH mask each source picture is paired with. This file owns
 * only that the already-edited bytes are EMBEDDED UNCHANGED, and it re-proves neither of the other
 * two. Three owners of one rule is worse than two, because the third copy is the one that drifts and
 * nothing says which copy is normative.</p>
 *
 * <h2>Baseline framing</h2>
 *
 * <p>Everything under {@code app/} is reference material, is read here and is never modified. Two of
 * its declarations are never written by the reference as units; both are recorded below as
 * observations about what the reference does, and neither is described as anything to be changed.
 * Where the migrated assembler departs from the reference the departure is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which owns that register.</p>
 *
 * <p>Assumptions: the graded condition-code rubric of the COBOL functional-parity oracle suite under
 * {@code tests/} belongs to that suite alone. The gate this class runs under is binary: the build has
 * zero findings or it fails, and there is no soft tier in it to lean on.</p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The obligation this file answers is user-specified Rule 1 (Explainability), whose conjunctive
 * validation gate stands at its line 43: a docstring stating purpose, parameters and return values,
 * AND an adjacent inline comment giving the WHY under one of the rule's four named categories. Its
 * line 21 adds the exceptions clause and its line 41 forbids a rationale without a specific
 * justification, so every why-comment below cites a concrete artifact -- a reference path and line, a
 * byte sum that closes, or a count verified by inspection. Every method carries Javadoc regardless of
 * visibility, because the rule's presence clause at its line 15 attaches no visibility qualifier and
 * the {@code MissingJavadocMethod} module of {@code config/checkstyle/checkstyle.xml} is configured
 * at private scope.</p>
 *
 * @see StatementHtmlMapper
 * @see StatementTextMapper
 * @see CobolEditMask
 */
class StatementHtmlMapperTest {

    /**
     * Attribute names that would put a dynamic value into a URL context.
     *
     * <p>Assumptions: these four fetch or navigate to a location, so a value interpolated into any
     * one of them is a URL and a text-context escape does not make it safe -- a URL can carry a
     * script scheme with no angle bracket in it at all. The {@code style} attribute is deliberately
     * absent: the artifact declares exactly one, it is a compile-time constant, and no dynamic value
     * reaches it.</p>
     */
    private static final List<String> URL_ATTRIBUTE_NAMES =
            List.of("href=", "src=", "action=", "formaction=");

    /**
     * Shape of an inline event-handler attribute, which would put a value into a script context.
     *
     * <p>Alternatives Considered: searching for the two-character prefix every such attribute shares.
     * Rejected because those two characters occur inside ordinary prose -- a fragment reading
     * {@code Statement on ...} would match -- so the check would fail on a legitimate literal added
     * later and would then be relaxed or removed. Matching the whole attribute shape, being
     * whitespace, the prefix, at least one further letter and an equals sign, cannot collide with
     * prose because prose does not assign.</p>
     */
    private static final Pattern EVENT_HANDLER_ATTRIBUTE = Pattern.compile("\\s+on[a-z]+\\s*=");

    /**
     * Number of cell and heading literals counted alongside the declared fragment table.
     *
     * <p>Assumptions: seven literals sit outside the fragment table and are walked with it -- the five
     * cell prefixes, the styled name prefix among them, plus the two halves of the account heading.
     * Declaring the number means the walk below cannot silently stop covering one of them.</p>
     */
    private static final int NON_FRAGMENT_LITERAL_COUNT = 7;

    /**
     * Prefix of every field name the assembler declares one markup fragment under.
     *
     * <p>Assumptions: the reference names each fragment with this prefix at lines 150 to 211 of
     * {@code app/cbl/CBSTM03A.CBL}, so a reflective walk over fields carrying it enumerates exactly
     * the transcribed table. The prefix is stated once here rather than repeated at each use so the
     * census below and the constancy check below cannot come to disagree about which fields they
     * cover.</p>
     */
    private static final String FRAGMENT_FIELD_PREFIX = "HTML_L";

    /** The declared width of the assembled name item. */
    private static final int ASSEMBLED_NAME_WIDTH = StatementTextMapper.ASSEMBLED_NAME_WIDTH;

    /** The declared width the markup cell narrows the assembled name to. */
    private static final int MARKUP_NAME_WIDTH = StatementTextMapper.MARKUP_NAME_WIDTH;

    /** The declared width of one address line item. */
    private static final int ADDRESS_LINE_WIDTH = StatementTextMapper.ADDRESS_LINE_WIDTH;

    /** The declared width of the assembled address item, the tightest cell in the artifact. */
    private static final int ASSEMBLED_ADDRESS_WIDTH = StatementTextMapper.ASSEMBLED_ADDRESS_WIDTH;

    /** The declared width of the account-identifier display item. */
    private static final int ACCOUNT_ID_ITEM_WIDTH = StatementTextMapper.ACCOUNT_ID_ITEM_WIDTH;

    /** The declared digit count of the account identifier's own unsigned picture. */
    private static final int ACCOUNT_ID_DIGITS = StatementTextMapper.ACCOUNT_ID_DIGITS;

    /** The declared width of the credit-score display item. */
    private static final int CREDIT_SCORE_ITEM_WIDTH = StatementTextMapper.CREDIT_SCORE_ITEM_WIDTH;

    /** The declared width of the transaction-identifier item. */
    private static final int TRANSACTION_ID_WIDTH = StatementTextMapper.TRANSACTION_ID_WIDTH;

    /** The declared width of the transaction-description item. */
    private static final int DESCRIPTION_ITEM_WIDTH = StatementTextMapper.DESCRIPTION_ITEM_WIDTH;

    /**
     * The declared width of both edited money items.
     *
     * <p>Assumptions: this resolves to the width the EDIT MASK declares rather than to the width the
     * plain-text band declares, and the two are the same 13 for a reason worth stating: the band is
     * sized to hold the mask's output. Reading it from the mask is what keeps this file free of the
     * band descriptors it has no business binding to, as the type-level charter records.</p>
     */
    private static final int AMOUNT_WIDTH = CobolEditMask.STATEMENT_AMOUNT_WIDTH;

    /** The zero-based index of the account heading in the document-header emission. */
    private static final int ACCOUNT_HEADING_INDEX = 10;

    /** The zero-based index of the name cell in the name-address-detail emission. */
    private static final int NAME_CELL_INDEX = 0;

    /** The zero-based index of the first address cell. */
    private static final int ADDRESS_LINE_1_INDEX = 1;

    /** The zero-based index of the second address cell. */
    private static final int ADDRESS_LINE_2_INDEX = 2;

    /** The zero-based index of the assembled-address cell. */
    private static final int ASSEMBLED_ADDRESS_INDEX = 3;

    /** The zero-based index of the account-identifier basic-detail cell. */
    private static final int ACCOUNT_ID_INDEX = 13;

    /** The zero-based index of the current-balance basic-detail cell. */
    private static final int CURRENT_BALANCE_INDEX = 14;

    /** The zero-based index of the credit-score basic-detail cell. */
    private static final int CREDIT_SCORE_INDEX = 15;

    /** The zero-based index of the transaction-identifier cell in a transaction row. */
    private static final int TRANSACTION_ID_INDEX = 2;

    /** The zero-based index of the description cell in a transaction row. */
    private static final int DESCRIPTION_INDEX = 5;

    /** The zero-based index of the amount cell in a transaction row. */
    private static final int AMOUNT_INDEX = 8;

    /**
     * Number of clear-then-assemble sites the two regimes below account for.
     *
     * <p>Assumptions: the seven are the four right-trimmed cells and the three whole-item transaction
     * cells, whose blanking moves stand at lines 561, 569, 577, 585, 686, 698 and 710 of
     * {@code app/cbl/CBSTM03A.CBL}. The three basic-detail cells are blanked the same way at lines
     * 613, 620 and 627, so the paragraph pair holds ten such moves in total and this constant counts
     * the seven the two regimes under test contribute. The distinction is drawn rather than rounded
     * because seven and ten are different facts about the same program.</p>
     */
    private static final int REGIME_CLEAR_SITE_COUNT = 7;

    /**
     * A customer name whose assembled form exceeds the width the markup cell narrows it to.
     *
     * <p>Assumptions: this reaches the first required fixture property. Its 59 characters are longer
     * than the {@value StatementTextMapper#MARKUP_NAME_WIDTH} the markup cell keeps, so the narrowing
     * at line 560 of {@code app/cbl/CBSTM03A.CBL} provably drops content rather than happening to fit;
     * and the 51st character onward is what a re-derivation from the wrong source would keep.</p>
     *
     * <p>Refactoring Rationale: this paragraph said 60 and the literal below measures 59. The figure is
     * load-bearing rather than decorative, because the whole point of the constant is that it EXCEEDS
     * the fifty the markup cell keeps, and the nine dropped characters are named elsewhere in this class
     * as the 51st through 59th. Five other sites already stated 59 correctly, so this one paragraph
     * disagreed with the rest of the file and with the literal it introduces; a reader reconciling them
     * would have had to count the string by hand to learn which was right. The narrowing claim itself is
     * unaffected -- 59 is greater than 50 exactly as 60 would have been -- which is precisely why no
     * assertion failed and why the figure had to be measured rather than trusted.</p>
     */
    private static final String OVER_WIDE_NAME_TEXT =
            "ANNA-MARIE ELISABETH VON HOHENBERG-SCHWARZENSTEIN THE THIRD";

    /**
     * A customer name carrying single interior blanks and nothing else before its padding.
     *
     * <p>Assumptions: this is the value that separates a right-trim from a first-word truncation. It
     * holds two single interior blanks, so a regime that cut at ONE blank would keep only
     * {@code ANNA} while the reference's two-blank delimiter at line 563 of
     * {@code app/cbl/CBSTM03A.CBL} keeps the whole of it.</p>
     */
    private static final String INTERIOR_SPACE_NAME_TEXT = "ANNA MARIE SMITH";

    /** A conforming customer name whose cut result is the text before its trailing padding. */
    private static final String NAME_TEXT = "JOHN A DOE";

    /** A conforming first address line carrying one interior blank run of one. */
    private static final String ADDRESS_1_TEXT = "123 MAIN ST";

    /** A conforming second address line. */
    private static final String ADDRESS_2_TEXT = "APT 4";

    /** A conforming assembled address, single-blank separated so no interior pair cuts it. */
    private static final String ASSEMBLED_ADDRESS_TEXT = "123 MAIN ST SPRINGFIELD IL 62701";

    /**
     * A synthetic eleven-digit account identifier.
     *
     * <p>Assumptions: the value is obviously synthetic and is an ACCOUNT identifier rather than a card
     * number. No primary account number reaches this artifact at all: in
     * {@code app/cbl/CBSTM03A.CBL} the card number appears only as a table key declared at line 227,
     * a read-key restore at line 421 and a table populate at line 827, none of which produces
     * output.</p>
     */
    private static final String ACCOUNT_DIGITS = "00000000011";

    /** A conforming credit score. */
    private static final String CREDIT_SCORE_TEXT = "750";

    /**
     * A synthetic transaction identifier at its exact declared width.
     *
     * <p>Assumptions: this is {@code TRNX-ID}, the SECOND component of the key declared at line 21 of
     * {@code app/cpy/COSTM01.CPY}, and not the card number that is its first. The two are both
     * {@code PIC X(16)} and a sixteen-digit literal reads like a primary account number, so the
     * distinction is stated outright: no card number reaches this artifact at all, and this value is
     * fifteen zeros and a one so that it cannot be mistaken for one.</p>
     */
    private static final String TRANSACTION_ID_TEXT = "0000000000000001";

    /**
     * A transaction description carrying interior single blanks and then trailing blanks.
     *
     * <p>Assumptions: this reaches the third required fixture property. Padded to
     * {@value StatementTextMapper#DESCRIPTION_ITEM_WIDTH} it carries 32 trailing blanks, so a regime
     * that trimmed would visibly shorten the cell and the whole-item transfer at line 700 of
     * {@code app/cbl/CBSTM03A.CBL} is provably not a trim.</p>
     */
    private static final String DESCRIPTION_TEXT = "PURCHASE AT STORE";

    /**
     * The edited balance every parity assertion reads, produced by the mask that declares it.
     *
     * <p>Assumptions: this is DERIVED from {@link CobolEditMask#formatStatementBalance(Money)} rather
     * than hand-written, so the bytes the cell is asserted to embed are the bytes the mask actually
     * produces. A transcribed literal of the same width would agree with itself while the mask
     * drifted. The magnitude is chosen with seven integer digits so the two leading zeros the
     * {@code PIC 9(9).99-} declaration at line 113 of {@code app/cbl/CBSTM03A.CBL} preserves are
     * present to be observed.</p>
     */
    private static final String BALANCE_ITEM =
            CobolEditMask.formatStatementBalance(Money.of("1234567.89"));

    /**
     * The edited amount every parity assertion reads, produced by the mask that declares it.
     *
     * <p>Assumptions: derived from {@link CobolEditMask#formatStatementAmount(Money)} for the reason
     * recorded on the balance above, and additionally because this mask's output ENDS IN A BLANK for
     * a positive amount -- the sign position of the {@code PIC Z(9).99-} declaration at line 137 of
     * {@code app/cbl/CBSTM03A.CBL}. That trailing blank is itself a whole-item-regime witness, and a
     * hand-written literal that happened to put its blanks at the front would lose it.</p>
     */
    private static final String AMOUNT_ITEM =
            CobolEditMask.formatStatementAmount(Money.of("250.00"));

    /**
     * The 34 declared markup fragments, transcribed independently from the reference declarations.
     *
     * <p>Assumptions: these literals are transcribed from the {@code 88}-level declarations at lines
     * 150 to 211 of {@code app/cbl/CBSTM03A.CBL}, INCLUDING the continuation lines that split six of
     * them across two source records, and they are held here so the verbatim assertion compares the
     * assembler's constants against an independent reading of the reference rather than against
     * themselves. The order is the reference's declaration order, which is what lets the assertion
     * pair each entry with the constant it transcribes.</p>
     *
     * <p>Assumptions: not one of these literals is normalised. The two consecutive blanks after
     * {@code <table} in the eighth entry, the mixed-case colour values, the semicolon before the
     * closing quote and the exact attribute order are all reference bytes; reformatting any of them
     * would change the document while leaving it valid markup, which is the failure mode a byte
     * comparison exists to catch. AAP Rule T8 carries user-visible strings across character for
     * character, and markup a reader's browser renders is user-visible.</p>
     */
    private static final List<String> DECLARED_FRAGMENTS = List.of(
            "<!DOCTYPE html>",
            "<html lang=\"en\">",
            "<head>",
            "<meta charset=\"utf-8\">",
            "<title>HTML Table Layout</title>",
            "</head>",
            "<body style=\"margin:0px;\">",
            "<table  align=\"center\" frame=\"box\" style=\"width:70%;"
                    + " font:12px Segoe UI,sans-serif;\">",
            "<tr>",
            "</tr>",
            "<td>",
            "</td>",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">",
            "<p style=\"font-size:16px\">Bank of XYZ</p>",
            "<p>410 Terry Ave N</p>",
            "<p>Seattle WA 99999</p>",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1;"
                    + " text-align:center;\">",
            "<p style=\"font-size:16px\">Basic Details</p>",
            "<p style=\"font-size:16px\">Transaction Summary</p>",
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E;"
                    + " text-align:left;\">",
            "<p style=\"font-size:16px\">Tran ID</p>",
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E;"
                    + " text-align:left;\">",
            "<p style=\"font-size:16px\">Tran Details</p>",
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E;"
                    + " text-align:right;\">",
            "<p style=\"font-size:16px\">Amount</p>",
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2;"
                    + " text-align:left;\">",
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2;"
                    + " text-align:left;\">",
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2;"
                    + " text-align:right;\">",
            "<h3>End of Statement</h3>",
            "</table>",
            "</body>",
            "</html>");

    /**
     * The names the assembler declares those 34 fragments under, in the same order.
     *
     * <p>Assumptions: the names are asserted alongside the values because the fragments are addressed
     * BY NAME in every emitter and never by position. A list of the right 34 values under the wrong 34
     * names would emit a well-formed document with two cells transposed, and only pairing name with
     * value excludes it.</p>
     */
    private static final List<String> DECLARED_FRAGMENT_NAMES = List.of(
            "HTML_L01", "HTML_L02", "HTML_L03", "HTML_L04", "HTML_L05", "HTML_L06", "HTML_L07",
            "HTML_L08", "HTML_LTRS", "HTML_LTRE", "HTML_LTDS", "HTML_LTDE", "HTML_L10", "HTML_L15",
            "HTML_L16", "HTML_L17", "HTML_L18", "HTML_L22_35", "HTML_L30_42", "HTML_L31",
            "HTML_L43", "HTML_L47", "HTML_L48", "HTML_L50", "HTML_L51", "HTML_L53", "HTML_L54",
            "HTML_L58", "HTML_L61", "HTML_L64", "HTML_L75", "HTML_L78", "HTML_L79", "HTML_L80");

    /**
     * One of the four cells assembled under the two-blank-delimited right-trim regime.
     *
     * <p>Assumptions: the four are modelled as data rather than as one representative case because the
     * reference writes them as FOUR SEPARATE concatenation statements, at lines 563, 571, 579 and 587
     * of {@code app/cbl/CBSTM03A.CBL}. An implementation that right-trimmed the name and transferred
     * the third address line whole would pass a single-case test and would emit a third address cell
     * whose closing tag sat 50 positions further right than the reference's.</p>
     *
     * @param label the component's name, used in the parameterised case name and in any failure
     *     message so a failure identifies which of the four sites broke
     * @param recordIndex the zero-based position of this cell's record inside the emission that
     *     {@link StatementHtmlMapper#emitNameAddressAndBasicDetails} returns
     * @param prefix the opening literal this cell is declared to carry, being the styled paragraph
     *     prefix for the name cell and the unstyled one for the three address cells
     * @param componentOrdinal the zero-based position of the component this site reads inside
     *     {@code StatementTextMapper.PreparedHeaderFields}
     * @param declaredWidth the width the component's own item declares, which is the width a value
     *     must be padded to before the prepared record will accept it
     */
    private record RightTrimSite(String label, int recordIndex, String prefix, int componentOrdinal,
            int declaredWidth) {
    }

    /**
     * One of the three transaction cells assembled under the asterisk-delimited whole-item regime.
     *
     * <p>Assumptions: the three are modelled as data for the reason recorded on the sibling record
     * above -- they are three separate concatenation statements, at lines 688, 700 and 712 of
     * {@code app/cbl/CBSTM03A.CBL} -- and because the amount cell is the one whose declared blanks a
     * reader is most likely to assume are slack rather than content.</p>
     *
     * @param label the component's name, used in the parameterised case name and in any failure
     *     message so a failure identifies which of the three sites broke
     * @param recordIndex the zero-based position of this cell's record inside the emission that
     *     {@link StatementHtmlMapper#emitTransactionRow} returns
     * @param componentOrdinal the zero-based position of the component this site reads inside
     *     {@code StatementTextMapper.PreparedTransactionFields}
     * @param declaredWidth the width the component's own item declares, which is the width a value
     *     must be padded to before the prepared record will accept it
     */
    private record WholeItemSite(String label, int recordIndex, int componentOrdinal,
            int declaredWidth) {
    }

    /**
     * Pads a value out to a declared width with trailing blanks, as the reference move does.
     *
     * @param text the value's own characters, which must not be longer than the declared width
     * @param declaredWidth the width the receiving item declares
     * @return the value followed by blanks, exactly {@code declaredWidth} characters long
     */
    private static String item(String text, int declaredWidth) {
        return text + " ".repeat(declaredWidth - text.length());
    }

    /**
     * Renders one emitted record as text so an assertion can read it.
     *
     * @param record one emitted record of the declared length
     * @return the record's characters, decoded as US-ASCII rather than under a platform default
     */
    private static String text(byte[] record) {
        return new String(record, StandardCharsets.US_ASCII);
    }

    /**
     * Builds the record a cell is expected to occupy, from its literals and its rendered value.
     *
     * <p>Assumptions: this composes an expectation from the assembler's PUBLISHED literal constants
     * and the value the case supplied, and performs no cut and no escaping of its own. It is therefore
     * an expectation about bytes rather than a second copy of the assembly, which would agree with the
     * production code about any assembly at all.</p>
     *
     * @param content the whole assembled content of the record, opening literal and closing tag
     *     included
     * @return the content followed by blanks out to the declared record length
     */
    private static String expectedRecord(String content) {
        return content + " ".repeat(StatementHtmlMapper.HTML_RECORD_LENGTH - content.length());
    }

    /**
     * Extracts the span a cell's dynamic value occupies, between its opening literal and closing tag.
     *
     * <p>Assumptions: the span is located by the prefix LENGTH and by the first occurrence of the
     * closing tag, so the assertion reads the value rather than the wrapper. Searching the whole record
     * for an angle bracket would match the wrapper's own tags, which are declared markup and
     * legitimately carry them.</p>
     *
     * @param record one emitted cell record of the declared length
     * @param prefix the opening literal that cell is declared to carry
     * @return the characters between the prefix and the closing tag, trailing blanks included
     */
    private static String renderedSpan(byte[] record, String prefix) {
        String content = text(record);
        return content.substring(prefix.length(), content.indexOf(StatementHtmlMapper.CELL_SUFFIX));
    }

    /**
     * Builds the conforming header fields every parity assertion below reads.
     *
     * @return prepared header fields whose every component sits at its exact declared width and holds
     *     only characters the reference seed corpus uses
     */
    private static StatementTextMapper.PreparedHeaderFields conformingHeader() {
        return headerWithComponent(-1, null);
    }

    /**
     * Builds the conforming header fields with one component replaced by a supplied value.
     *
     * <p>Assumptions: one builder serves every substitution so that the six components a case is NOT
     * exercising are identical across cases. Six separate builders would let two cases disagree about
     * an unrelated component, and a cross-emitter assertion could then fail for a reason that had
     * nothing to do with the cell under test.</p>
     *
     * @param componentOrdinal the zero-based position of the component to replace, or any negative
     *     value to leave every component at its conforming default
     * @param value the replacement component, already padded to its own declared width, or
     *     {@code null} when {@code componentOrdinal} is negative
     * @return prepared header fields carrying the substitution, every other component conforming
     */
    private static StatementTextMapper.PreparedHeaderFields headerWithComponent(int componentOrdinal,
            String value) {
        List<String> components = new ArrayList<>(List.of(
                item(NAME_TEXT, ASSEMBLED_NAME_WIDTH),
                item(ADDRESS_1_TEXT, ADDRESS_LINE_WIDTH),
                item(ADDRESS_2_TEXT, ADDRESS_LINE_WIDTH),
                item(ASSEMBLED_ADDRESS_TEXT, ASSEMBLED_ADDRESS_WIDTH),
                item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH),
                BALANCE_ITEM,
                item(CREDIT_SCORE_TEXT, CREDIT_SCORE_ITEM_WIDTH)));
        if (componentOrdinal >= 0) {
            components.set(componentOrdinal, value);
        }
        return new StatementTextMapper.PreparedHeaderFields(components.get(0), components.get(1),
                components.get(2), components.get(3), components.get(4), components.get(5),
                components.get(6));
    }

    /**
     * Builds header fields identical to the conforming ones but for a substituted assembled name.
     *
     * @param assembledName the assembled-name component to substitute, at its declared width
     * @return prepared header fields carrying the supplied name
     */
    private static StatementTextMapper.PreparedHeaderFields headerWithName(String assembledName) {
        return headerWithComponent(0, assembledName);
    }

    /**
     * Builds header fields identical to the conforming ones but for a substituted assembled address.
     *
     * @param assembledAddress the assembled-address component to substitute, at its declared width
     * @return prepared header fields carrying the supplied assembled address
     */
    private static StatementTextMapper.PreparedHeaderFields headerWithAddress(
            String assembledAddress) {
        return headerWithComponent(3, assembledAddress);
    }

    /**
     * Builds the conforming transaction fields every parity assertion below reads.
     *
     * @return prepared transaction fields whose three components sit at their exact declared widths
     */
    private static StatementTextMapper.PreparedTransactionFields conformingRow() {
        return rowWithComponent(-1, null);
    }

    /**
     * Builds the conforming transaction fields with one component replaced by a supplied value.
     *
     * @param componentOrdinal the zero-based position of the component to replace, or any negative
     *     value to leave every component at its conforming default
     * @param value the replacement component, already padded to its own declared width, or
     *     {@code null} when {@code componentOrdinal} is negative
     * @return prepared transaction fields carrying the substitution, every other component conforming
     */
    private static StatementTextMapper.PreparedTransactionFields rowWithComponent(
            int componentOrdinal, String value) {
        List<String> components = new ArrayList<>(List.of(
                item(TRANSACTION_ID_TEXT, TRANSACTION_ID_WIDTH),
                item(DESCRIPTION_TEXT, DESCRIPTION_ITEM_WIDTH),
                AMOUNT_ITEM));
        if (componentOrdinal >= 0) {
            components.set(componentOrdinal, value);
        }
        return new StatementTextMapper.PreparedTransactionFields(components.get(0),
                components.get(1), components.get(2));
    }

    /**
     * Builds transaction fields with one substituted description.
     *
     * @param description the description component to substitute, at its declared width
     * @return prepared transaction fields carrying the supplied description
     */
    private static StatementTextMapper.PreparedTransactionFields rowWithDescription(
            String description) {
        return rowWithComponent(1, description);
    }

    /**
     * Supplies the 34 declared fragments paired with the names and constants they transcribe.
     *
     * @return a stream of declared name, independently transcribed reference literal and the constant
     *     the assembler publishes under that name, one entry per declared fragment
     */
    private static Stream<Arguments> declaredFragments() {
        List<String> published = StatementHtmlMapper.FRAGMENT_TABLE;
        return Stream.iterate(0, index -> index + 1)
                .limit(DECLARED_FRAGMENTS.size())
                .map(index -> Arguments.of(DECLARED_FRAGMENT_NAMES.get(index),
                        DECLARED_FRAGMENTS.get(index), published.get(index)));
    }

    /**
     * Supplies the four cells assembled under the two-blank-delimited right-trim regime.
     *
     * @return a stream holding one {@link RightTrimSite} per site, in the reference's own order
     */
    private static Stream<Arguments> rightTrimSites() {
        return Stream.of(
                // WHY : Assumptions: the prepared record accepts the assembled name only at 75, and
                //       line 560 of app/cbl/CBSTM03A.CBL narrows it to 50 before line 563 reads it,
                //       so the value has to be built at 75 and expected at the narrowed width. A site
                //       declaring 50 here would be rejected by the record's own width check.
                Arguments.of(new RightTrimSite("markupName", NAME_CELL_INDEX,
                        StatementHtmlMapper.NAME_CELL_PREFIX, 0, ASSEMBLED_NAME_WIDTH)),
                Arguments.of(new RightTrimSite("addressLine1", ADDRESS_LINE_1_INDEX,
                        StatementHtmlMapper.PLAIN_CELL_PREFIX, 1, ADDRESS_LINE_WIDTH)),
                Arguments.of(new RightTrimSite("addressLine2", ADDRESS_LINE_2_INDEX,
                        StatementHtmlMapper.PLAIN_CELL_PREFIX, 2, ADDRESS_LINE_WIDTH)),
                Arguments.of(new RightTrimSite("assembledAddress", ASSEMBLED_ADDRESS_INDEX,
                        StatementHtmlMapper.PLAIN_CELL_PREFIX, 3, ASSEMBLED_ADDRESS_WIDTH)));
    }

    /**
     * Supplies the three transaction cells assembled under the whole-item regime.
     *
     * @return a stream holding one {@link WholeItemSite} per site, in the reference's own order
     */
    private static Stream<Arguments> wholeItemSites() {
        return Stream.of(
                Arguments.of(new WholeItemSite("transactionId", TRANSACTION_ID_INDEX, 0,
                        TRANSACTION_ID_WIDTH)),
                Arguments.of(new WholeItemSite("description", DESCRIPTION_INDEX, 1,
                        DESCRIPTION_ITEM_WIDTH)),
                Arguments.of(new WholeItemSite("amount", AMOUNT_INDEX, 2, AMOUNT_WIDTH)));
    }

    /**
     * Collects every record the four emitters produce for one conforming statement.
     *
     * <p>Assumptions: the four emissions are concatenated in the order the reference performs their
     * paragraphs -- the header at line 506 of {@code app/cbl/CBSTM03A.CBL}, the name and detail block
     * at line 558, one transaction row at line 675 and the markup tail written at lines 439 to 454 --
     * so a sweep over the result covers the whole artifact rather than one operation.</p>
     *
     * @return every record of a one-transaction statement, in emission order
     */
    private static List<byte[]> wholeStatement() {
        List<byte[]> records = new ArrayList<>();
        records.addAll(StatementHtmlMapper.emitDocumentHeader(
                item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH)));
        records.addAll(StatementHtmlMapper.emitNameAddressAndBasicDetails(conformingHeader()));
        records.addAll(StatementHtmlMapper.emitTransactionRow(conformingRow()));
        records.addAll(StatementHtmlMapper.emitDocumentFooter());
        return records;
    }

    /**
     * Every record of every emission is exactly the declared length of 100 bytes.
     *
     * <p>Assumptions: the length is swept over all four emitters in one case rather than asserted
     * inside each behavioural test, because it belongs to the record contract and not to any one
     * emitter. An emitter added later that assembled its own records without padding them would fail
     * here even while passing every test written for its own behaviour.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("every record of every emission is exactly the declared length of 100 bytes")
    void everyRecordOfEveryEmissionIsTheDeclaredLength() {
        List<byte[]> records = wholeStatement();

        // WHY : Assumptions: an all-satisfy assertion over an empty collection passes vacuously, so
        //       an emitter that returned nothing at all would read as green here. The expected total
        //       is 22 + 34 + 11 + 8, and grep -c 'WRITE FD-HTMLFILE-REC' app/cbl/CBSTM03A.CBL
        //       reports exactly 75, which is the same number reached from the other direction.
        assertThat(records).hasSize(StatementHtmlMapper.DOCUMENT_HEADER_LINE_COUNT
                + StatementHtmlMapper.NAME_ADDRESS_BASIC_DETAIL_LINE_COUNT
                + StatementHtmlMapper.TRANSACTION_ROW_LINE_COUNT
                + StatementHtmlMapper.DOCUMENT_FOOTER_LINE_COUNT);
        assertThat(records).allSatisfy(record ->
                assertThat(record).hasSize(StatementHtmlMapper.HTML_RECORD_LENGTH));
    }

    /**
     * The declared record length of 100 is corroborated by three independent reference artifacts.
     *
     * <p>Assumptions: three confirmations are asserted rather than one because a width declared in a
     * single place is a width one edit away from drifting with nothing to contradict it. The three are
     * the reusable fragment buffer at line 149 of {@code app/cbl/CBSTM03A.CBL}, the three assembly
     * buffers at lines 221 to 223, and the data-set attributes at line 94 of
     * {@code app/jcl/CREASTMT.JCL}; the output record at line 47 agrees with all three.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the declared record length of 100 is corroborated three independent ways")
    void theDeclaredRecordLengthIsCorroboratedThreeWays() {
        // WHY : Assumptions: this is the anchor every other width assertion in the file resolves
        //       through, so it is pinned to a literal exactly once and here. The three artifacts are
        //       independent of one another: a program's working storage, a program's file section and
        //       a job's data-set attributes are edited by different people for different reasons, so
        //       agreement between them is evidence rather than repetition.
        assertThat(StatementHtmlMapper.HTML_RECORD_LENGTH).isEqualTo(100);

        // WHY : Assumptions: the two longest declared fragments are 85 characters -- the table element
        //       at lines 157 to 158 of app/cbl/CBSTM03A.CBL and the centred panel cell at lines 176 to
        //       178 -- so the table has 15 characters of headroom. Asserting the headroom rather than
        //       only the ceiling is what would catch a transcription that ran two literals together,
        //       which is the specific hazard of a declaration split across two source records.
        assertThat(StatementHtmlMapper.FRAGMENT_TABLE)
                .allSatisfy(fragment -> assertThat(fragment.length())
                        .isLessThanOrEqualTo(StatementHtmlMapper.HTML_RECORD_LENGTH));
        int longestFragment = StatementHtmlMapper.FRAGMENT_TABLE.stream()
                .mapToInt(String::length)
                .max()
                .orElseThrow();
        assertThat(longestFragment).isEqualTo(85);
        assertThat(StatementHtmlMapper.HTML_RECORD_LENGTH - longestFragment).isEqualTo(15);
    }

    /**
     * The markup length of 100 and the plain-text length of 80 are never interchangeable.
     *
     * <p>Assumptions: the two are asserted UNEQUAL and both are named, because substituting either for
     * the other yields a record that is still well formed. A markup record truncated to 80 loses its
     * closing tag and a text band padded to 100 overruns its data set, and neither failure is visible
     * without a byte comparison. The plain-text 80 is declared at line 45 of
     * {@code app/cbl/CBSTM03A.CBL} and at line 89 of {@code app/jcl/CREASTMT.JCL}.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("100 and 80 are never interchangeable between the two statement artifacts")
    void oneHundredAndEightyAreNeverInterchangeable() {
        byte[] markupRecord = StatementHtmlMapper.emitDocumentFooter().get(0);
        byte[] textLine = StatementTextMapper.emitTransactionLine(conformingRow());

        // WHY : Assumptions: the difference of twenty is stated as arithmetic on the two published
        //       lengths rather than as the literal 20, so the assertion cannot pass by accident if
        //       either declaration is ever re-read from its source. Line 66 of
        //       app/jcl/CREASTMT.JCL is the reason a reader can find an LRECL of 80 near the markup
        //       output at all: that step invokes the null utility to delete both data sets before the
        //       statement program at line 79 recreates them, so its attributes describe nothing that
        //       is written and must not be read as the markup length.
        assertThat(markupRecord).hasSize(StatementHtmlMapper.HTML_RECORD_LENGTH);
        assertThat(textLine).hasSize(80);
        assertThat(markupRecord.length).isNotEqualTo(textLine.length);
        assertThat(markupRecord.length - textLine.length).isEqualTo(20);
    }

    /**
     * The records are encoded single-byte, so a length in characters is a length in bytes.
     *
     * <p>Assumptions: this is asserted rather than assumed because the data-set contract at line 94 of
     * {@code app/jcl/CREASTMT.JCL} is a contract about BYTES. Under a multi-byte encoding a record of
     * 100 characters would encode to more than 100 bytes, so "the record is 100 long" would be two
     * different claims and a character-count assertion would pass while the emitted data set was
     * malformed.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("every record is single-byte encoded, so its character count is its byte count")
    void everyRecordIsSingleByteEncoded() {
        List<byte[]> records = wholeStatement();

        // WHY : Assumptions: the round trip is the check. Decoding as US-ASCII and re-encoding must
        //       return the same byte count for every record, which holds only while every character
        //       occupies one byte. Relying on the platform default would make the outcome depend on
        //       the runtime's locale rather than on the emitted artifact.
        assertThat(records).allSatisfy(record -> {
            String decoded = text(record);
            assertThat(decoded).hasSize(record.length);
            assertThat(decoded.getBytes(StandardCharsets.US_ASCII)).hasSize(record.length);
        });

        // WHY : Assumptions: US-ASCII encodes every code point from 0x00 to 0x7F, so an encoder check
        //       alone would admit a carriage return or a null byte. On a fixed-length record data set
        //       an embedded terminator forges a record boundary and displaces every record after it,
        //       so the printable range and the encodable range are different obligations.
        assertThat(records).allSatisfy(record -> {
            for (byte encoded : record) {
                assertThat(encoded).isBetween((byte) 0x20, (byte) 0x7E);
            }
        });
    }

    /**
     * No record ever exceeds the declared length, even at the widest conforming input.
     *
     * <p>Assumptions: the widest conforming input is every component filled to its declared width with
     * no blank pair anywhere in it, so nothing is trimmed and every cell reaches its largest possible
     * assembled size. The tightest cell is then the assembled address: its 3-character prefix, 80
     * declared characters, re-appended blank pair and 4-character closing tag sum to 89, which leaves
     * 11 characters of headroom inside the declared 100.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("no record exceeds the declared length even at the widest conforming input")
    void noRecordExceedsTheDeclaredLengthAtTheWidestInput() {
        String widestName = "N".repeat(ASSEMBLED_NAME_WIDTH);
        String widestAddress = "A".repeat(ASSEMBLED_ADDRESS_WIDTH);
        StatementTextMapper.PreparedHeaderFields widest = new StatementTextMapper.PreparedHeaderFields(
                widestName, "B".repeat(ADDRESS_LINE_WIDTH), "C".repeat(ADDRESS_LINE_WIDTH),
                widestAddress, "9".repeat(ACCOUNT_ID_ITEM_WIDTH), BALANCE_ITEM,
                "8".repeat(CREDIT_SCORE_ITEM_WIDTH));
        StatementTextMapper.PreparedTransactionFields widestRow =
                new StatementTextMapper.PreparedTransactionFields(
                        "T".repeat(TRANSACTION_ID_WIDTH), "D".repeat(DESCRIPTION_ITEM_WIDTH),
                        AMOUNT_ITEM);

        List<byte[]> records = new ArrayList<>();
        records.addAll(StatementHtmlMapper.emitNameAddressAndBasicDetails(widest));
        records.addAll(StatementHtmlMapper.emitTransactionRow(widestRow));

        // WHY : Assumptions: a value with no blank pair is the worst case for the right-trim regime,
        //       because the delimiter at line 563 of app/cbl/CBSTM03A.CBL is never found and the whole
        //       declared width transfers. The name cell then reaches 26 + 50 + 2 + 4 = 82 and the
        //       assembled address 3 + 80 + 2 + 4 = 89, so both close well inside 100 and the arithmetic
        //       is checkable rather than asserted.
        assertThat(records).isNotEmpty().allSatisfy(record ->
                assertThat(record).hasSize(StatementHtmlMapper.HTML_RECORD_LENGTH));
        assertThat(renderedSpan(records.get(NAME_CELL_INDEX),
                StatementHtmlMapper.NAME_CELL_PREFIX)).hasSize(MARKUP_NAME_WIDTH + 2);
        assertThat(renderedSpan(records.get(ASSEMBLED_ADDRESS_INDEX),
                StatementHtmlMapper.PLAIN_CELL_PREFIX)).hasSize(ASSEMBLED_ADDRESS_WIDTH + 2);

        // WHY : Trade-offs: refusal is the behaviour under test and truncation is the alternative that
        //       was not taken. A cell truncated at 100 could sever a closing tag or split a character
        //       reference and leave a bare ampersand in the document, and a length check afterwards
        //       could not see either. The cost is that one such row fails the statement it appears in.
        String overrunning = "X".repeat(ASSEMBLED_ADDRESS_WIDTH - 3) + "\"\"\"";
        assertThat(overrunning).hasSize(ASSEMBLED_ADDRESS_WIDTH);
        assertThatThrownBy(() -> StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithAddress(overrunning)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assembledAddress");
    }

    /**
     * The fragment table holds exactly 34 entries, no more and no fewer.
     *
     * <p>Assumptions: the census is its own case rather than a line inside another assertion because
     * both ways of getting it wrong are silent. A table of 31 omits three literals and still emits a
     * well-formed document; a table of 36 has counted the two group items at lines 212 and 217 of
     * {@code app/cbl/CBSTM03A.CBL} as though they were condition names. Either survives compilation
     * and surfaces only as a byte difference in a golden comparison.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the fragment table holds exactly 34 entries")
    void theFragmentTableHoldsExactlyThirtyFourEntries() {
        // WHY : Assumptions: the figure is verified rather than estimated. Over lines 150 to 211 of
        //       app/cbl/CBSTM03A.CBL, grep -cE '^ +88 +HTML-' returns exactly 34, and the same command
        //       over the whole file returns the same 34, so no fragment is declared outside that span.
        //       This file's own independent transcription and its name list are the second and third
        //       counts, and all four have to agree.
        assertThat(StatementHtmlMapper.FRAGMENT_COUNT).isEqualTo(34);
        assertThat(StatementHtmlMapper.FRAGMENT_TABLE).hasSize(34);
        assertThat(DECLARED_FRAGMENTS).hasSize(34);
        assertThat(DECLARED_FRAGMENT_NAMES).hasSize(34);

        // WHY : Assumptions: four counts of this module have been verified independently and none
        //       substitutes for another -- 34 markup fragments here, 17 plain-text statement bands, 22
        //       FILLER items in the daily transaction report copybook, and 10 enabled checks in
        //       config/checkstyle/checkstyle.xml, which declares 13 modules in total. Asserting the
        //       inequalities pins the one this file owns against the three most likely to be
        //       substituted for it.
        assertThat(StatementHtmlMapper.FRAGMENT_COUNT)
                .isNotEqualTo(17)
                .isNotEqualTo(22)
                .isNotEqualTo(10);

        // WHY : Assumptions: four of the reference's literals are short closing or opening tags that
        //       differ by a single character -- the row and cell pairs at lines 159 to 162 -- so a
        //       transcription that repeated one of them would still produce a table of 34. A duplicate
        //       check is what distinguishes a complete table from one of the right size.
        assertThat(StatementHtmlMapper.FRAGMENT_TABLE).doesNotHaveDuplicates();
        assertThat(StatementHtmlMapper.FRAGMENT_TABLE).allSatisfy(fragment ->
                assertThat(fragment).isNotBlank());
    }

    /**
     * Each declared fragment carries its reference bytes character for character.
     *
     * @param fragmentName the name the assembler declares this fragment under, which is also the name
     *     the reference declares its condition name under with underscores for hyphens
     * @param transcribed the literal transcribed independently in this file from the reference
     *     declaration, continuation lines joined
     * @param published the constant the assembler publishes at the same position of its table
     */
    @ParameterizedTest(name = "{0} is carried across character for character")
    @MethodSource("declaredFragments")
    @DisplayName("every declared fragment is carried across character for character")
    void everyDeclaredFragmentIsCarriedAcrossVerbatim(String fragmentName, String transcribed,
            String published) {
        // WHY : Assumptions: markup a browser renders is a user-visible string, so AAP Rule T8 carries
        //       it across character for character -- every blank, angle bracket, quote and attribute
        //       value. The transcription in this file is read from the 88-level declarations at lines
        //       150 to 211 of app/cbl/CBSTM03A.CBL rather than copied from the production constants, so
        //       the two agreeing is evidence about the reference rather than a tautology.
        assertThat(published)
                .as("%s must carry its reference bytes", fragmentName)
                .isEqualTo(transcribed);

        // WHY : Assumptions: three specific normalisations would each leave valid markup and change the
        //       bytes. Collapsing the two consecutive blanks after <table at line 157 of
        //       app/cbl/CBSTM03A.CBL, stripping a leading or trailing blank, or lower-casing a colour
        //       value such as #33FFD1 at line 177 all render identically and all fail a byte
        //       comparison, so each is excluded here rather than left to review.
        assertThat(published).isEqualTo(published.strip());
        assertThat(published).doesNotContain("\t").doesNotContain("\n").doesNotContain("\r");
        assertThat(published.toLowerCase(Locale.ROOT).equals(published))
                .as("%s keeps the reference's own letter case", fragmentName)
                .isEqualTo(transcribed.toLowerCase(Locale.ROOT).equals(transcribed));
    }

    /**
     * Every fragment is a compile-time constant the assembler exposes by name rather than by index.
     *
     * <p>Alternatives Considered: holding the fragments as an array and having each emitter index into
     * it. Rejected because the reference SELECTS A CONDITION NAME at each of its 75 write sites -- for
     * example at lines 508, 524 and 551 of {@code app/cbl/CBSTM03A.CBL} -- so a name is what the
     * reference itself addresses. With indices, reordering the table would silently transpose two
     * cells of the document while every index stayed in range and every record stayed 100 bytes;
     * with names, the same reordering cannot change what any emitter emits at all.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws ReflectiveOperationException if a declared fragment field cannot be read, which reports
     *     a change to the assembler's own field declarations rather than a fault of this assertion
     */
    @Test
    @DisplayName("every fragment is a named compile-time constant rather than a table position")
    void everyFragmentIsANamedCompileTimeConstant() throws ReflectiveOperationException {
        List<Field> fragmentFields = Arrays.stream(StatementHtmlMapper.class.getDeclaredFields())
                .filter(field -> field.getName().startsWith(FRAGMENT_FIELD_PREFIX))
                .toList();

        // WHY : Assumptions: the one prefix covers both spellings the reference uses -- the numbered
        //       fragments at lines 150 to 158 and 163 onward, and the four row and cell tags named
        //       LTRS, LTRE, LTDS and LTDE at lines 159 to 162. A narrower prefix would silently cover
        //       fewer: matching on the numbered form alone would count nine and read as though
        //       twenty-five fragments had gone missing.
        assertThat(fragmentFields).hasSize(StatementHtmlMapper.FRAGMENT_COUNT);
        assertThat(fragmentFields).extracting(Field::getName)
                .containsExactlyInAnyOrderElementsOf(DECLARED_FRAGMENT_NAMES);

        // WHY : Assumptions: static and final together are what make a fragment a compile-time constant
        //       rather than a value rebuilt per call, which is the property the reference's own
        //       storage-declared literals have. A non-final field would let one emission change what a
        //       later emission emits, and two statements assembled in either order have to produce the
        //       same bytes.
        for (Field field : fragmentFields) {
            int modifiers = field.getModifiers();
            assertThat(Modifier.isStatic(modifiers))
                    .as("%s is static", field.getName()).isTrue();
            assertThat(Modifier.isFinal(modifiers))
                    .as("%s is final", field.getName()).isTrue();
            assertThat(Modifier.isPublic(modifiers))
                    .as("%s is addressable by name", field.getName()).isTrue();
            assertThat(field.getType()).isEqualTo(String.class);
            assertThat(StatementHtmlMapper.FRAGMENT_TABLE)
                    .as("%s appears in the declared table", field.getName())
                    .contains((String) field.get(null));
        }

        // WHY : Assumptions: the table is walked by this class and by the attribute-context assertion
        //       below, so a caller able to add an entry could introduce a fragment that no emitter
        //       emits and that no assertion covers. Refusal on mutation is what keeps the census above
        //       a statement about the artifact rather than about one moment in a run.
        assertThatThrownBy(() -> StatementHtmlMapper.FRAGMENT_TABLE.add("<script>"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The account heading is 59 characters of content assembled from three independent components.
     *
     * <p>Assumptions: the three components are asserted SEPARATELY rather than as one 59-character
     * literal, because the middle one is variable data and the two outers are fixed markup. A single
     * literal assertion would be satisfied by a heading whose account field had been transcribed at
     * the wrong width, provided the two literals absorbed the difference -- which is exactly the
     * transcription error the three declared widths at lines 213, 215 and 216 of
     * {@code app/cbl/CBSTM03A.CBL} exist to prevent.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the account heading is 59 characters from three independently sized components")
    void theAccountHeadingIsFiftyNineFromThreeComponents() {
        String accountItem = item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH);
        byte[] heading = StatementHtmlMapper.emitDocumentHeader(accountItem)
                .get(ACCOUNT_HEADING_INDEX);

        // WHY : Assumptions: the byte sum has to close, and it does: the opening literal declared
        //       FILLER PIC X(34) at line 213 of app/cbl/CBSTM03A.CBL with its value at line 214, the
        //       account item L11-ACCT PIC X(20) at line 215, and the closing literal FILLER PIC X(05)
        //       at line 216 sum to 34 + 20 + 5 = 59. Asserting the sum without asserting the parts
        //       would accept 30 + 24 + 5.
        assertThat(StatementHtmlMapper.ACCOUNT_HEADING_PREFIX)
                .hasSize(StatementHtmlMapper.ACCOUNT_HEADING_PREFIX_LENGTH)
                .hasSize(34);
        assertThat(ACCOUNT_ID_ITEM_WIDTH).isEqualTo(20);
        assertThat(StatementHtmlMapper.ACCOUNT_HEADING_SUFFIX)
                .hasSize(StatementHtmlMapper.ACCOUNT_HEADING_SUFFIX_LENGTH)
                .hasSize(5);
        assertThat(StatementHtmlMapper.ACCOUNT_HEADING_PREFIX_LENGTH + ACCOUNT_ID_ITEM_WIDTH
                + StatementHtmlMapper.ACCOUNT_HEADING_SUFFIX_LENGTH)
                .isEqualTo(StatementHtmlMapper.ACCOUNT_HEADING_CONTENT_LENGTH)
                .isEqualTo(59);

        // WHY : Assumptions: the thirty-fourth character of the value at line 214 of
        //       app/cbl/CBSTM03A.CBL is a blank, and it is what separates the heading text from the
        //       digits. A transcription that read it as source-formatting slack would produce a 33
        //       character prefix, close the heading up against the account number, and still assemble
        //       a valid element.
        assertThat(StatementHtmlMapper.ACCOUNT_HEADING_PREFIX).endsWith(" ");
        assertThat(StatementHtmlMapper.ACCOUNT_HEADING_PREFIX.strip())
                .hasSize(StatementHtmlMapper.ACCOUNT_HEADING_PREFIX_LENGTH - 1);

        // WHY : Assumptions: the padding from 59 to 100 is 41 blanks and comes from the record being
        //       written from a group declared inside the 100-character storage at line 148 of
        //       app/cbl/CBSTM03A.CBL, so the unused suffix is blank by declaration. Asserting the whole
        //       record rather than its prefix is what pins the padding as well as the content.
        assertThat(text(heading)).isEqualTo(expectedRecord(
                StatementHtmlMapper.ACCOUNT_HEADING_PREFIX + accountItem
                        + StatementHtmlMapper.ACCOUNT_HEADING_SUFFIX));
        assertThat(heading).hasSize(StatementHtmlMapper.HTML_RECORD_LENGTH);
        assertThat(text(heading).substring(StatementHtmlMapper.ACCOUNT_HEADING_CONTENT_LENGTH))
                .isBlank()
                .hasSize(StatementHtmlMapper.HTML_RECORD_LENGTH
                        - StatementHtmlMapper.ACCOUNT_HEADING_CONTENT_LENGTH);
    }

    /**
     * The account identifier blank-pads into 20 here where the report zero-pads into 11.
     *
     * <p>Assumptions: both renderings of ONE source picture are asserted in one place, because either
     * alone reads as the general rule and substituting it for the other produces a plausible heading
     * that is wrong. {@code ACCT-ID} is declared {@code PIC 9(11)} at line 5 of
     * {@code app/cpy/CVACT01Y.cpy} in both paths; line 483 of {@code app/cbl/CBSTM03A.CBL} moves it
     * into a {@code PIC X(20)} display item, so the eleven digits land left-justified with nine BLANKS
     * after them, while the report's own account field is declared exactly eleven wide and takes the
     * digit form whole with its leading ZEROS.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the account identifier blank-pads into 20 here and zero-pads into 11 in the report")
    void theAccountIdentifierBlankPadsHereAndZeroPadsInTheReport() {
        long accountId = 11L;
        String headingItem = StatementTextMapper.renderAccountIdItem(accountId);
        String reportField = CobolEditMask.formatUnsignedDigits(accountId, ACCOUNT_ID_DIGITS);
        byte[] heading = StatementHtmlMapper.emitDocumentHeader(headingItem)
                .get(ACCOUNT_HEADING_INDEX);

        // WHY : Assumptions: the two forms share their eleven leading characters and differ only in the
        //       nine that follow, so the difference is located precisely. The leading zeros are present
        //       in BOTH; a reader told only that one pads with blanks and the other with zeros could
        //       otherwise conclude the leading zeros differed too.
        assertThat(headingItem)
                .isEqualTo(ACCOUNT_DIGITS + " ".repeat(9))
                .hasSize(ACCOUNT_ID_ITEM_WIDTH)
                .startsWith(reportField);
        assertThat(reportField).isEqualTo(ACCOUNT_DIGITS).hasSize(ACCOUNT_ID_DIGITS);
        assertThat(headingItem.substring(ACCOUNT_ID_DIGITS)).isBlank().doesNotContain("0");

        // WHY : Assumptions: the closing literal is declared as a separate item AFTER the account item
        //       at line 216 of app/cbl/CBSTM03A.CBL rather than after a trimmed one, so the nine
        //       declared blanks stand between the digits and the closing tag. An assembly that trimmed
        //       them would pull the closing tag nine positions left and still emit valid markup.
        assertThat(text(heading)).contains(ACCOUNT_DIGITS + " ".repeat(9)
                + StatementHtmlMapper.ACCOUNT_HEADING_SUFFIX);
        assertThat(ACCOUNT_ID_ITEM_WIDTH).isNotEqualTo(ACCOUNT_ID_DIGITS);
    }

    /**
     * The name paragraph is rebuilt with a closing tag rather than emitted as the declared group.
     *
     * <p>Assumptions: the group opened at line 217 of {@code app/cbl/CBSTM03A.CBL} is dead AS A
     * RENDERING UNIT and its subordinate field is live, and the two facts are consistent rather than
     * contradictory. The group is 76 characters -- a 26-character styled prefix at lines 218 to 219
     * plus a 50-character name item at line 220 -- and it declares no position for a closing tag, so it
     * is never written whole anywhere in the program. Its subordinate {@code L23-NAME} IS read: line
     * 560 moves the assembled name into it and line 563 reads it back out. What the reference emits is
     * neither the group nor nothing: it is the concatenation at lines 562 to 567, which rebuilds the
     * same opening prefix, embeds the narrowed name, and supplies the closing tag as a literal.</p>
     *
     * <p>Alternatives Considered: reproducing the group's 76 bytes exactly as declared, closing tag
     * absent, on the ground that byte fidelity to the reference is this class's whole purpose. Rejected
     * because there is no observable output to be faithful to -- the group is never written in the
     * reference either -- so the fidelity target is the concatenation at lines 562 to 567, and that is
     * what is reproduced. The migrated behaviour is therefore a transcription of what the reference
     * emits rather than a departure from it.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the name paragraph is rebuilt with its closing tag rather than emitted as the group")
    void theNameParagraphIsRebuiltWithItsClosingTag() {
        byte[] nameCell = StatementHtmlMapper.emitNameAddressAndBasicDetails(conformingHeader())
                .get(NAME_CELL_INDEX);

        // WHY : Assumptions: the byte sum closes at 26 + 50 = 76, which is what makes the missing
        //       closing position observable: a group that ended in a closing tag would declare 80. The
        //       widths are asserted from the published constants so the arithmetic is checkable against
        //       lines 218 and 220 of app/cbl/CBSTM03A.CBL rather than asserted as a bare total.
        assertThat(StatementHtmlMapper.NAME_CELL_PREFIX)
                .hasSize(StatementHtmlMapper.NAME_CELL_PREFIX_LENGTH)
                .hasSize(26);
        assertThat(MARKUP_NAME_WIDTH).isEqualTo(50);
        assertThat(StatementHtmlMapper.NAME_CELL_PREFIX_LENGTH + MARKUP_NAME_WIDTH).isEqualTo(76);

        // WHY : Assumptions: the closing tag is supplied by the concatenation at line 565 of
        //       app/cbl/CBSTM03A.CBL as a literal delimited by an asterisk, so it is four characters
        //       transferred whole. An emitted cell 76 characters long with no closing tag would be the
        //       group; an emitted cell that closed would be the concatenation, and it is the
        //       concatenation the reference writes at line 568.
        assertThat(StatementHtmlMapper.CELL_SUFFIX)
                .isEqualTo("</p>")
                .hasSize(StatementHtmlMapper.CELL_SUFFIX_LENGTH);
        assertThat(text(nameCell))
                .startsWith(StatementHtmlMapper.NAME_CELL_PREFIX)
                .contains(StatementHtmlMapper.CELL_SUFFIX);
        assertThat(text(nameCell).strip()).endsWith(StatementHtmlMapper.CELL_SUFFIX);

        // WHY : Assumptions: prefix, then the cut name, then the re-appended blank pair, then the
        //       closing tag, then blanks to 100 -- which for this name is 26 + 10 + 2 + 4 = 42
        //       characters of content and 58 blanks. Naming the whole record is what excludes a cell
        //       that closed in the right place but carried the wrong blank run before it.
        assertThat(text(nameCell)).isEqualTo(expectedRecord(
                StatementHtmlMapper.NAME_CELL_PREFIX + NAME_TEXT + StatementHtmlMapper.PAIRED_BLANK
                        + StatementHtmlMapper.CELL_SUFFIX));
    }

    /**
     * The narrowed 50-character name is read from the prepared record and never re-derived here.
     *
     * <p>Assumptions: this is the whole point of populate once, render twice. The narrowing lives on
     * {@code StatementTextMapper.PreparedHeaderFields.markupName()}, which derives the leftmost
     * {@value StatementTextMapper#MARKUP_NAME_WIDTH} characters of the assembled name exactly as line
     * 560 of {@code app/cbl/CBSTM03A.CBL} moves it into the narrower item. Two independent derivations
     * of one value drift: the plain-text band would then carry one customer's name while the markup
     * cell carried a differently narrowed form of it, and each artifact would be internally
     * consistent.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the narrowed name is read from the prepared record rather than re-derived here")
    void theNarrowedNameIsReadFromThePreparedRecord() {
        String assembled = item(OVER_WIDE_NAME_TEXT, ASSEMBLED_NAME_WIDTH);
        StatementTextMapper.PreparedHeaderFields fields = headerWithName(assembled);
        byte[] nameCell = StatementHtmlMapper.emitNameAddressAndBasicDetails(fields)
                .get(NAME_CELL_INDEX);

        // WHY : Assumptions: this reaches the first required fixture property, and without it the case
        //       is vacuous. A name shorter than 50 would be narrowed to itself, so a mapper that
        //       re-derived the narrowing and a mapper that read it from the record would emit the same
        //       bytes and the assertion would prove nothing about which of the two happened.
        assertThat(OVER_WIDE_NAME_TEXT.length()).isGreaterThan(MARKUP_NAME_WIDTH);
        assertThat(assembled).hasSize(ASSEMBLED_NAME_WIDTH);

        // WHY : Assumptions: the expectation is taken from fields.markupName() rather than recomputed
        //       here, so the assertion states that the cell renders WHAT THE RECORD CARRIES. Computing
        //       the leftmost 50 characters independently in this file would put the narrowing in a
        //       third place and the assertion would then agree with its own copy rather than with the
        //       record the plain-text artifact also reads.
        String narrowed = fields.markupName();
        assertThat(narrowed).hasSize(MARKUP_NAME_WIDTH);
        assertThat(renderedSpan(nameCell, StatementHtmlMapper.NAME_CELL_PREFIX))
                .isEqualTo(narrowed + StatementHtmlMapper.PAIRED_BLANK);

        // WHY : Assumptions: the narrowing is provably a truncation because the tail exists and is
        //       gone. The assembled name is 59 characters of text, so its 51st through 59th characters
        //       are declared content that the markup cell must not carry; a mapper reading the wrong
        //       component would carry them and would still emit a 100-byte record.
        String droppedTail = OVER_WIDE_NAME_TEXT.substring(MARKUP_NAME_WIDTH);
        assertThat(droppedTail).isNotEmpty();
        assertThat(text(nameCell)).doesNotContain(droppedTail);

        // WHY : Alternatives Considered: storing the narrowed name beside the assembled one as an
        //       eighth component. Rejected because nothing would check that the two agreed, so an
        //       instance could hold the narrowing of one name beside another name entirely. Pinning the
        //       component list is what stops the eighth component being reintroduced.
        assertThat(StatementTextMapper.PreparedHeaderFields.class.getRecordComponents())
                .hasSize(7);
    }

    /**
     * Each right-trimmed cell stops at the first blank pair and re-appends the blank pair after it.
     *
     * @param site the cell under test, being one of the four the reference assembles with a
     *     two-blank delimiter at lines 563, 571, 579 and 587 of {@code app/cbl/CBSTM03A.CBL}
     */
    @ParameterizedTest(name = "{0} is cut at its first blank pair and closed with a blank pair")
    @MethodSource("rightTrimSites")
    @DisplayName("each right-trimmed cell cuts at its first blank pair and re-appends the pair")
    void eachRightTrimmedCellCutsAtTheFirstBlankPair(RightTrimSite site) {
        String text = "VALUE ONE";
        String padded = item(text, site.declaredWidth());
        List<byte[]> records = StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithComponent(site.componentOrdinal(), padded));
        String rendered = renderedSpan(records.get(site.recordIndex()), site.prefix());

        // WHY : Assumptions: this reaches the second required fixture property, and the case is vacuous
        //       without it. A value that already ended at its declared width would carry no blank pair,
        //       the delimiter would never be found, and a trimming implementation and a non-trimming
        //       one would emit identical bytes.
        assertThat(padded).hasSize(site.declaredWidth()).endsWith("  ");

        // WHY : Assumptions: the reference transfers the item only up to its first PAIR of blanks --
        //       grep -c "DELIMITED BY '  '" over app/cbl/CBSTM03A.CBL returns exactly 4, which is these
        //       four sites and no others -- so the declared padding does not reach the document. The
        //       cut is asserted on the rendered span rather than on the whole record because the
        //       record's own prefix and closing tag legitimately carry blanks of their own.
        assertThat(rendered)
                .as("%s is cut at its first blank pair", site.label())
                .startsWith(text);
        assertThat(rendered.substring(0, text.length())).isEqualTo(text);

        // WHY : Assumptions: the literal at lines 564, 572, 580 and 588 of app/cbl/CBSTM03A.CBL is two
        //       blanks DELIMITED BY SIZE, so it transfers whole and unconditionally. One blank would be
        //       a different document and none would be a different document again, so the count is
        //       asserted rather than the presence of whitespace.
        assertThat(StatementHtmlMapper.PAIRED_BLANK)
                .isEqualTo("  ")
                .hasSize(StatementHtmlMapper.PAIRED_BLANK_LENGTH);
        assertThat(rendered)
                .as("%s closes with exactly the declared blank pair", site.label())
                .isEqualTo(text + StatementHtmlMapper.PAIRED_BLANK);

        // WHY : Assumptions: the record is the unit the data set holds, so the assertion is made on all
        //       100 bytes. A cell correct in its first 20 characters and wrong in its padding would
        //       satisfy a prefix assertion and would still be a byte difference in the golden
        //       comparison outside this module.
        assertThat(text(records.get(site.recordIndex()))).isEqualTo(expectedRecord(
                site.prefix() + text + StatementHtmlMapper.PAIRED_BLANK
                        + StatementHtmlMapper.CELL_SUFFIX));
    }

    /**
     * A right-trimmed cell whose value holds single interior blanks keeps the whole of it.
     *
     * @param site the cell under test, being one of the four right-trimmed sites
     */
    @ParameterizedTest(name = "{0} survives its single interior blanks")
    @MethodSource("rightTrimSites")
    @DisplayName("each right-trimmed cell keeps a value whose interior blanks are single")
    void eachRightTrimmedCellKeepsSingleInteriorBlanks(RightTrimSite site) {
        String padded = item(INTERIOR_SPACE_NAME_TEXT, site.declaredWidth());
        List<byte[]> records = StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithComponent(site.componentOrdinal(), padded));
        String rendered = renderedSpan(records.get(site.recordIndex()), site.prefix());

        // WHY : Assumptions: the precondition is what makes the case meaningful. The value holds two
        //       single blanks and no pair before its padding, so the only pair in it is the one the
        //       padding begins with -- which is exactly where the cut must fall.
        assertThat(INTERIOR_SPACE_NAME_TEXT).contains(" ").doesNotContain("  ");

        // WHY : Assumptions: the delimiter is TWO blanks and not one, and this is the assertion that
        //       separates a right-trim from a first-word truncation. It is exactly the mistake a reader
        //       of the plain-text name assembly reaches for: that assembly at lines 462 to 469 of
        //       app/cbl/CBSTM03A.CBL is delimited by a SINGLE blank and therefore does stop at the
        //       first word boundary, and generalising from it to here would drop two words of every
        //       customer's name from the markup document.
        assertThat(rendered)
                .as("%s keeps every word of a value with single interior blanks", site.label())
                .isEqualTo(INTERIOR_SPACE_NAME_TEXT + StatementHtmlMapper.PAIRED_BLANK);
        assertThat(rendered).contains("MARIE").contains("SMITH");
        assertThat(rendered).isNotEqualTo("ANNA" + StatementHtmlMapper.PAIRED_BLANK);
    }

    /**
     * The two-blank delimiter here and the single-blank delimiter of the text mapper both hold.
     *
     * <p>Assumptions: the same three name parts reach two artifacts through two different delimiters
     * and produce two different results, and BOTH are correct. The plain-text assembly at lines 462 to
     * 469 of {@code app/cbl/CBSTM03A.CBL} is delimited by a single blank, so each part stops at its own
     * first blank and the three are joined by unconditional blank literals -- which is what produces
     * the paired-blank separator when a middle name is absent. The markup paragraph at line 563 is
     * delimited by a blank PAIR, so it right-trims instead. Substituting either delimiter for the
     * other is a silent data defect.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the markup pair delimiter right-trims where the text single delimiter stops at a word")
    void theTwoDelimitersProduceDifferentAndBothCorrectResults() {
        String assembled = StatementTextMapper.assembleName("MARY JO", "", "VAN DYKE");

        // WHY : Assumptions: this is the plain-text mapper's own documented behaviour and it is
        //       asserted here only as the CONTRAST, not re-proven. The first part contributes MARY
        //       alone, the blank middle part contributes nothing between two blank literals, and the
        //       third contributes VAN, so the assembled item is MARY, blank, blank, VAN and then its
        //       declared padding.
        assertThat(assembled).hasSize(ASSEMBLED_NAME_WIDTH);
        assertThat(assembled.strip()).isEqualTo("MARY  VAN");
        assertThat(assembled).doesNotContain("JO").doesNotContain("DYKE");

        // WHY : Assumptions: the markup regime sees the paired-blank separator the single-blank
        //       assembly produced and cuts there, so the markup cell carries MARY alone. That is not
        //       a defect of either regime: it is what a two-blank delimiter does to a value holding a
        //       two-blank run, and the reference does exactly this at line 563 of app/cbl/CBSTM03A.CBL.
        byte[] nameCell = StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithName(assembled)).get(NAME_CELL_INDEX);
        assertThat(renderedSpan(nameCell, StatementHtmlMapper.NAME_CELL_PREFIX))
                .isEqualTo("MARY" + StatementHtmlMapper.PAIRED_BLANK);

        // WHY : Assumptions: naming the inequality is what makes the two delimiters non-substitutable.
        //       The plain-text item carries MARY, two blanks and VAN; the markup cell carries MARY and
        //       stops. A reader who generalised from one file to the other would be wrong in whichever
        //       direction they generalised.
        assertThat(assembled.strip()).isNotEqualTo("MARY");
        assertThat(renderedSpan(nameCell, StatementHtmlMapper.NAME_CELL_PREFIX).strip())
                .isNotEqualTo(assembled.strip());
    }

    /**
     * Each whole-item cell carries its entire declared item, trailing blanks included.
     *
     * @param site the cell under test, being one of the three the reference assembles with an
     *     asterisk delimiter at lines 688, 700 and 712 of {@code app/cbl/CBSTM03A.CBL}
     */
    @ParameterizedTest(name = "{0} is transferred whole, trailing blanks included")
    @MethodSource("wholeItemSites")
    @DisplayName("each whole-item cell transfers its entire item including trailing blanks")
    void eachWholeItemCellTransfersTheEntireItem(WholeItemSite site) {
        String text = "VALUE ONE";
        String padded = item(text, site.declaredWidth());
        List<byte[]> records = StatementHtmlMapper.emitTransactionRow(
                rowWithComponent(site.componentOrdinal(), padded));
        String rendered = renderedSpan(records.get(site.recordIndex()),
                StatementHtmlMapper.PLAIN_CELL_PREFIX);

        // WHY : Assumptions: this reaches the third required fixture property. Without trailing blanks
        //       there is nothing for a trim to remove, so a trimming implementation and this one would
        //       emit identical bytes and the case would prove nothing.
        assertThat(padded).hasSize(site.declaredWidth()).endsWith(" ");
        assertThat(padded.length() - text.length()).isPositive();

        // WHY : Assumptions: the delimiter at lines 688, 700 and 712 of app/cbl/CBSTM03A.CBL is an
        //       ASTERISK, and no asterisk occurs in the data these items carry, so the delimiter is
        //       never found and the concatenation consumes the whole item. This is an idiomatic COBOL
        //       whole-field copy and not a trim; reading it as a trim would strip the declared padding
        //       out of three columns and pull the closing tag of every transaction row left, so the
        //       three columns of the table would no longer line up down the document.
        assertThat(rendered)
                .as("%s is transferred whole", site.label())
                .isEqualTo(padded)
                .hasSize(site.declaredWidth());
        assertThat(rendered).endsWith(" ");

        // WHY : Assumptions: this regime appends NO blank pair, which is the second difference from the
        //       right-trim regime and is as load-bearing as the first. The concatenation at lines 687 to
        //       690 of app/cbl/CBSTM03A.CBL has three operands where the one at lines 562 to 567 has
        //       four, so a blank pair inserted here would push the closing tag two positions right.
        assertThat(text(records.get(site.recordIndex()))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + padded + StatementHtmlMapper.CELL_SUFFIX));

        // WHY : Assumptions: the absence of the appended pair is stated as arithmetic rather than as a
        //       suffix check, because a whole-item span legitimately ENDS in blanks -- they are the
        //       item's own declared padding. Only the length distinguishes padding the item carried from
        //       a pair the assembly added, and the right-trim regime's own span is asserted at the
        //       trimmed length plus exactly two.
        assertThat(rendered.length()).isEqualTo(site.declaredWidth());
        assertThat(rendered.length())
                .isNotEqualTo(site.declaredWidth() + StatementHtmlMapper.PAIRED_BLANK_LENGTH);
    }

    /**
     * Each whole-item cell also transfers a value that has no trailing blanks to remove.
     *
     * @param site the cell under test, being one of the three whole-item sites
     */
    @ParameterizedTest(name = "{0} is transferred whole when it has no padding at all")
    @MethodSource("wholeItemSites")
    @DisplayName("each whole-item cell transfers a fully occupied item unchanged")
    void eachWholeItemCellTransfersAFullyOccupiedItem(WholeItemSite site) {
        String full = "9".repeat(site.declaredWidth());
        List<byte[]> records = StatementHtmlMapper.emitTransactionRow(
                rowWithComponent(site.componentOrdinal(), full));

        // WHY : Assumptions: this is what distinguishes "no trim happened" from "there was nothing to
        //       trim". The case above proves padding survives; this one proves the regime is not a
        //       conditional trim that happens to leave a full item alone, so together they pin the
        //       regime rather than one of its outcomes.
        assertThat(full).hasSize(site.declaredWidth()).doesNotContain(" ");
        assertThat(renderedSpan(records.get(site.recordIndex()),
                StatementHtmlMapper.PLAIN_CELL_PREFIX))
                .as("%s is unchanged when fully occupied", site.label())
                .isEqualTo(full);
    }

    /**
     * The two regimes produce different bytes for the same value, differing exactly in its padding.
     *
     * <p>Assumptions: the two cells cannot be given a literally identical string because their items
     * declare different widths -- an address line is 50 and a transaction description is 49 -- so the
     * same TEXT is padded to each cell's own declared width. That is the closest the two regimes can be
     * brought together, and it is enough: both values carry the same characters followed by trailing
     * blanks, so any difference in the emitted spans is a difference the regimes made.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the two regimes emit different bytes for one value and differ only in its padding")
    void theTwoRegimesEmitDifferentBytesForOneValue() {
        String shared = "COFFEE SHOP";
        String rightTrimmed = renderedSpan(StatementHtmlMapper.emitNameAddressAndBasicDetails(
                        headerWithComponent(1, item(shared, ADDRESS_LINE_WIDTH)))
                .get(ADDRESS_LINE_1_INDEX), StatementHtmlMapper.PLAIN_CELL_PREFIX);
        String wholeItem = renderedSpan(StatementHtmlMapper.emitTransactionRow(
                        rowWithDescription(item(shared, DESCRIPTION_ITEM_WIDTH)))
                .get(DESCRIPTION_INDEX), StatementHtmlMapper.PLAIN_CELL_PREFIX);

        // WHY : Assumptions: this is the assertion that makes substituting one regime for the other
        //       impossible. If either regime were replaced by the other, the two spans would become
        //       equal in one direction or the other, and every other assertion in this file about the
        //       affected cell would still pass because each cell would still be a well-formed
        //       100-byte record.
        assertThat(rightTrimmed).isNotEqualTo(wholeItem);

        // WHY : Assumptions: naming the difference precisely is what turns an inequality into a
        //       specification. Stripped, the two agree, so neither regime altered the characters of the
        //       value; unstripped, the right-trimmed span is the text plus the re-appended pair while
        //       the whole-item span is the text plus all 38 of its declared blanks.
        assertThat(rightTrimmed.strip()).isEqualTo(wholeItem.strip()).isEqualTo(shared);
        assertThat(rightTrimmed).isEqualTo(shared + StatementHtmlMapper.PAIRED_BLANK);
        assertThat(wholeItem).isEqualTo(item(shared, DESCRIPTION_ITEM_WIDTH));
        assertThat(wholeItem.length()).isGreaterThan(rightTrimmed.length());

        // WHY : Assumptions: the shared text carries one interior blank, so a regime that cut at a
        //       single blank would reduce it to COFFEE under either treatment. Asserting the interior
        //       blank on both sides means the inequality above is about trailing padding alone and not
        //       about one regime having truncated the value.
        assertThat(rightTrimmed).contains("COFFEE SHOP");
        assertThat(wholeItem).contains("COFFEE SHOP");
    }

    /**
     * The seven regime cells are blanked before assembly, which is what pads them out to 100.
     *
     * <p>Assumptions: the blanking move, not a fixed-width encoder, is the padding mechanism in this
     * artifact. The reference issues {@code MOVE SPACES} immediately before every one of these
     * assemblies -- at lines 561, 569, 577 and 585 of {@code app/cbl/CBSTM03A.CBL} for the four
     * right-trimmed cells and at lines 686, 698 and 710 for the three transaction cells -- so the
     * target is all blanks before the concatenation begins and whatever the concatenation does not
     * reach stays blank. The concatenation itself pads nothing, so an assembly with no preceding
     * blanking move would leave whatever the previous record left behind.</p>
     *
     * <p>Assumptions: the three basic-detail cells are blanked the same way at lines 613, 620 and 627,
     * so the two paragraphs hold ten such moves in total. Seven is the count these two regimes
     * contribute and ten is the count of the paragraphs; the two figures are kept distinct rather than
     * rounded into one.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the seven regime cells are blank-padded to 100 by being cleared before assembly")
    void theSevenRegimeCellsAreBlankPaddedToOneHundred() {
        List<byte[]> headerCells = StatementHtmlMapper.emitNameAddressAndBasicDetails(
                conformingHeader());
        List<byte[]> rowCells = StatementHtmlMapper.emitTransactionRow(conformingRow());
        List<byte[]> regimeCells = List.of(
                headerCells.get(NAME_CELL_INDEX),
                headerCells.get(ADDRESS_LINE_1_INDEX),
                headerCells.get(ADDRESS_LINE_2_INDEX),
                headerCells.get(ASSEMBLED_ADDRESS_INDEX),
                rowCells.get(TRANSACTION_ID_INDEX),
                rowCells.get(DESCRIPTION_INDEX),
                rowCells.get(AMOUNT_INDEX));

        // WHY : Assumptions: the arithmetic closes -- grep -c "DELIMITED BY '  '" over
        //       app/cbl/CBSTM03A.CBL returns 4 for the right-trim regime, and the transaction paragraph
        //       labelled at line 675 holds 3 asserted cells -- so seven is a sum of two verified counts
        //       rather than a figure carried over from anywhere else.
        assertThat(regimeCells).hasSize(REGIME_CLEAR_SITE_COUNT).hasSize(7);

        // WHY : Assumptions: the shortest of the seven is the transaction-identifier cell at 3 + 16 + 4
        //       = 23 characters, so 77 of its bytes are padding and the padding is most of the record.
        //       A zero-filled remainder would satisfy a length assertion and would put 77 null bytes
        //       into a data set the reference declares as blank-filled, which no text reader would show.
        assertThat(regimeCells).allSatisfy(record -> {
            assertThat(record).hasSize(StatementHtmlMapper.HTML_RECORD_LENGTH);
            String content = text(record);
            int contentEnd = content.indexOf(StatementHtmlMapper.CELL_SUFFIX)
                    + StatementHtmlMapper.CELL_SUFFIX_LENGTH;
            assertThat(content.substring(contentEnd)).isBlank();
            for (int position = contentEnd; position < record.length; position++) {
                assertThat(record[position]).isEqualTo((byte) 0x20);
            }
        });

        // WHY : Assumptions: naming the number is what makes the padding checkable. The identifier cell
        //       occupies 3 + 16 + 4 = 23 characters inside the 100 declared at line 94 of
        //       app/jcl/CREASTMT.JCL, so its padding is exactly 77 blanks; an off-by-one in either
        //       direction would still read as blank.
        assertThat(text(rowCells.get(TRANSACTION_ID_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + item(TRANSACTION_ID_TEXT, TRANSACTION_ID_WIDTH)
                        + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(StatementHtmlMapper.HTML_RECORD_LENGTH
                - (StatementHtmlMapper.PLAIN_CELL_PREFIX_LENGTH + TRANSACTION_ID_WIDTH
                        + StatementHtmlMapper.CELL_SUFFIX_LENGTH))
                .isEqualTo(77);
    }

    /**
     * The balance is embedded exactly as the leading-zero-preserving mask rendered it.
     *
     * <p>Assumptions: the balance cell embeds {@code ST-CURR-BAL}, declared {@code PIC 9(9).99-} at
     * line 113 of {@code app/cbl/CBSTM03A.CBL}: thirteen characters, nine integer digit positions with
     * their LEADING ZEROS PRESERVED, a decimal point, two decimal digits and a TRAILING sign position.
     * Nine consecutive digit positions with nothing between them means no grouping separator, where the
     * report copybook breaks the same nine into groups of three.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the balance is embedded exactly as its leading-zero-preserving mask rendered it")
    void theBalanceIsEmbeddedExactlyAsItsMaskRenderedIt() {
        byte[] balanceCell = StatementHtmlMapper.emitNameAddressAndBasicDetails(conformingHeader())
                .get(CURRENT_BALANCE_INDEX);
        String embedded = renderedSpan(balanceCell, StatementHtmlMapper.CURRENT_BALANCE_CELL_PREFIX);

        // WHY : Assumptions: the expectation is the mask's return value rather than a literal, so the
        //       assertion states that the assembler embedded the string it was given. This file owns
        //       only that embedding: CobolEditMaskTest owns what the mask does and
        //       StatementTextMapperTest owns which source picture is paired with which mask, and a third
        //       copy of either rule here would be the copy that drifts with nothing saying which is
        //       normative.
        assertThat(embedded)
                .isEqualTo(BALANCE_ITEM)
                .isEqualTo(CobolEditMask.formatStatementBalance(Money.of("1234567.89")))
                .hasSize(AMOUNT_WIDTH)
                .hasSize(13);

        // WHY : Assumptions: these three are asserted at the point of embedding because each has a
        //       plausible wrong form that still fills the cell. Inserting two grouping separators would
        //       return fifteen characters into a thirteen-character item; stripping the leading zeros
        //       would produce the other statement regime's output; and moving the sign to the front
        //       would produce a value that reads as the same number and is a byte difference.
        assertThat(embedded).startsWith("00");
        assertThat(embedded).doesNotContain(",");
        assertThat(embedded.charAt(AMOUNT_WIDTH - 1)).isEqualTo(' ');
        assertThat(embedded).doesNotStartWith("+").doesNotStartWith("-");

        // WHY : Assumptions: the sign position is only observable on a negative value, so the positive
        //       case above cannot establish it alone. A trailing hyphen is what the declaration at line
        //       113 of app/cbl/CBSTM03A.CBL specifies, and a leading one would still be thirteen
        //       characters.
        String negative = CobolEditMask.formatStatementBalance(Money.of("-1234567.89"));
        String negativeEmbedded = renderedSpan(StatementHtmlMapper.emitNameAddressAndBasicDetails(
                        headerWithComponent(5, negative)).get(CURRENT_BALANCE_INDEX),
                StatementHtmlMapper.CURRENT_BALANCE_CELL_PREFIX);
        assertThat(negativeEmbedded).isEqualTo(negative);
        assertThat(negativeEmbedded.charAt(AMOUNT_WIDTH - 1)).isEqualTo('-');
    }

    /**
     * The amount is embedded exactly as the zero-suppressing mask rendered it, cents always printed.
     *
     * <p>Assumptions: the amount cell embeds {@code ST-TRANAMT}, declared {@code PIC Z(9).99-} at line
     * 137 of {@code app/cbl/CBSTM03A.CBL}: thirteen characters whose nine integer positions SUPPRESS
     * leading zeros while its two decimal positions are ordinary digit positions and therefore always
     * print. It is the same width and the same shape as the balance mask and differs only in that
     * suppression, so substituting one for the other still fills the cell and still reads as the same
     * number.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the amount is embedded exactly as its zero-suppressing mask rendered it")
    void theAmountIsEmbeddedExactlyAsItsMaskRenderedIt() {
        String embedded = renderedSpan(StatementHtmlMapper.emitTransactionRow(conformingRow())
                .get(AMOUNT_INDEX), StatementHtmlMapper.PLAIN_CELL_PREFIX);

        // WHY : Assumptions: the value arrives already edited under the declaration at line 137 of
        //       app/cbl/CBSTM03A.CBL, so the assembler's only obligation is to copy it. Asserting
        //       equality with the mask's return value rather than with a literal is what keeps this file
        //       from re-proving the mask, and the leading blanks are asserted present because they are
        //       that declaration's own suppression output and not slack.
        assertThat(embedded)
                .isEqualTo(AMOUNT_ITEM)
                .isEqualTo(CobolEditMask.formatStatementAmount(Money.of("250.00")))
                .hasSize(AMOUNT_WIDTH);
        assertThat(embedded).startsWith(" ").doesNotStartWith("0");
        assertThat(embedded).doesNotContain(",");

        // WHY : Assumptions: the two masks are both thirteen characters with a trailing sign, so an
        //       interchange is invisible to every geometry check in this file. Naming the inequality is
        //       what excludes it: the balance mask at line 113 of app/cbl/CBSTM03A.CBL preserves the
        //       leading zeros the amount mask at line 137 blanks.
        assertThat(CobolEditMask.formatStatementAmount(Money.of("250.00")))
                .isNotEqualTo(CobolEditMask.formatStatementBalance(Money.of("250.00")))
                .hasSameSizeAs(CobolEditMask.formatStatementBalance(Money.of("250.00")));

        // WHY : Trade-offs: embedding already-formatted bytes rather than accepting an exact decimal and
        //       formatting here is the choice under test, and its cost is that the assembler cannot
        //       validate the number it renders. What it buys is that one rendering exists per value: a
        //       second formatting route would let the two artifacts disagree about one amount while both
        //       still produced thirteen characters with a sign in the last position.
        String odd = "0000000AB.CD ";
        assertThat(odd).hasSize(AMOUNT_WIDTH);
        assertThat(renderedSpan(StatementHtmlMapper.emitTransactionRow(rowWithComponent(2, odd))
                .get(AMOUNT_INDEX), StatementHtmlMapper.PLAIN_CELL_PREFIX)).isEqualTo(odd);
    }

    /**
     * A zero amount renders as nine blanks, a point, two zeros and a blank sign, not as blanks.
     *
     * <p>Assumptions: this is the most confusable pair of renderings in the module, so both zeros are
     * asserted in one place. The statement's amount mask at line 137 of {@code app/cbl/CBSTM03A.CBL}
     * suppresses its nine integer positions but not its two decimal positions, so a zero shows nine
     * blanks, a decimal point, two zeros and a blank sign -- thirteen characters of which three are not
     * blank. The daily transaction report's mask suppresses its decimal places too, so a zero there is
     * fifteen blanks and nothing else.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("a zero amount embeds as nine blanks and its cents, never as the report's blank field")
    void aZeroAmountEmbedsAsNineBlanksAndItsCents() {
        String statementZero = CobolEditMask.formatStatementAmount(Money.ZERO);
        String reportZero = CobolEditMask.formatReportDetailAmount(Money.ZERO);
        String embedded = renderedSpan(StatementHtmlMapper.emitTransactionRow(
                        rowWithComponent(2, statementZero)).get(AMOUNT_INDEX),
                StatementHtmlMapper.PLAIN_CELL_PREFIX);

        // WHY : Assumptions: naming all thirteen is the only assertion that excludes the four wrong
        //       forms a reader reaches for, because every one of them is shorter and every one parses as
        //       the same number: a wholly blank item, a bare decimal point with cents, an unsuppressed
        //       zero before the point, and a signed zero.
        assertThat(statementZero).isEqualTo(" ".repeat(9) + ".00 ").hasSize(AMOUNT_WIDTH);
        assertThat(statementZero).isNotEqualTo("0.00").isNotEqualTo(".00").isNotEqualTo("+0.00");
        assertThat(embedded).isEqualTo(statementZero);

        // WHY : Assumptions: the report's zero is blank in all fifteen positions and the statement's is
        //       not blank at all, so substituting one artifact's zero for the other's produces a cell
        //       that reads as empty where a cardholder's statement must show a zero amount. The widths
        //       differ too, 13 against 15, which is a second reason the two cannot be interchanged.
        assertThat(reportZero).isEqualTo(" ".repeat(CobolEditMask.REPORT_AMOUNT_WIDTH)).isBlank();
        assertThat(statementZero).isNotBlank().isNotEqualTo(reportZero);
        assertThat(AMOUNT_WIDTH).isNotEqualTo(CobolEditMask.REPORT_AMOUNT_WIDTH);
        assertThat(text(StatementHtmlMapper.emitTransactionRow(rowWithComponent(2, statementZero))
                .get(AMOUNT_INDEX))).contains(".00");
    }

    /**
     * The header and footer emit their fragments in the reference's own order.
     *
     * <p>Assumptions: ORDER is asserted and not merely membership, because markup is order-dependent. A
     * set-equality assertion would pass a document that held every correct tag and nested them wrongly
     * -- a row closed before the cell inside it, for instance -- and the result would still contain
     * exactly the right 22 records.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the header and footer emit their fragments in the reference's own order")
    void theHeaderAndFooterEmitTheirFragmentsInReferenceOrder() {
        List<byte[]> header = StatementHtmlMapper.emitDocumentHeader(
                item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH));
        List<byte[]> footer = StatementHtmlMapper.emitDocumentFooter();

        // WHY : Assumptions: the sequence transcribed is the paragraph labelled at line 506 of
        //       app/cbl/CBSTM03A.CBL through its exit at line 554, in source order: the document opening
        //       at lines 508 to 523, a row and banner cell at 524 and 526, the account heading at 529
        //       and 530, the cell and row closes at 531 and 533, the issuer identity block bracketed at
        //       535 to 547, and a row and the first neutral panel cell at 549 and 551.
        List<String> headerContent = header.stream().map(StatementHtmlMapperTest::text)
                .map(String::strip).toList();
        assertThat(headerContent).containsExactly(
                StatementHtmlMapper.HTML_L01,
                StatementHtmlMapper.HTML_L02,
                StatementHtmlMapper.HTML_L03,
                StatementHtmlMapper.HTML_L04,
                StatementHtmlMapper.HTML_L05,
                StatementHtmlMapper.HTML_L06,
                StatementHtmlMapper.HTML_L07,
                StatementHtmlMapper.HTML_L08,
                StatementHtmlMapper.HTML_LTRS,
                StatementHtmlMapper.HTML_L10,
                (StatementHtmlMapper.ACCOUNT_HEADING_PREFIX
                        + item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH)
                        + StatementHtmlMapper.ACCOUNT_HEADING_SUFFIX).strip(),
                StatementHtmlMapper.HTML_LTDE,
                StatementHtmlMapper.HTML_LTRE,
                StatementHtmlMapper.HTML_LTRS,
                StatementHtmlMapper.HTML_L15,
                StatementHtmlMapper.HTML_L16,
                StatementHtmlMapper.HTML_L17,
                StatementHtmlMapper.HTML_L18,
                StatementHtmlMapper.HTML_LTDE,
                StatementHtmlMapper.HTML_LTRE,
                StatementHtmlMapper.HTML_LTRS,
                StatementHtmlMapper.HTML_L22_35);

        // WHY : Assumptions: three declarations are selected more than once inside this one sequence --
        //       the row open at lines 524, 535 and 549 of app/cbl/CBSTM03A.CBL, the cell close at 531
        //       and 545, and the row close at 533 and 547. A sequence that emitted a repeated fragment
        //       once would produce a document whose rows never close, and it would still hold every
        //       distinct tag the reference declares.
        assertThat(headerContent).filteredOn(StatementHtmlMapper.HTML_LTRS::equals).hasSize(3);
        assertThat(headerContent).filteredOn(StatementHtmlMapper.HTML_LTDE::equals).hasSize(2);
        assertThat(headerContent).filteredOn(StatementHtmlMapper.HTML_LTRE::equals).hasSize(2);

        // WHY : Assumptions: the sequence is the markup tail of the paragraph labelled at line 416 of
        //       app/cbl/CBSTM03A.CBL, at lines 439 to 454, and it is the only place the four closing
        //       declarations are selected at all. The table, body and document closes have to arrive in
        //       that order because each encloses the next; reversing any pair leaves the same eight
        //       records and an unparseable document.
        assertThat(footer.stream().map(StatementHtmlMapperTest::text).map(String::strip).toList())
                .containsExactly(
                        StatementHtmlMapper.HTML_LTRS,
                        StatementHtmlMapper.HTML_L10,
                        StatementHtmlMapper.HTML_L75,
                        StatementHtmlMapper.HTML_LTDE,
                        StatementHtmlMapper.HTML_LTRE,
                        StatementHtmlMapper.HTML_L78,
                        StatementHtmlMapper.HTML_L79,
                        StatementHtmlMapper.HTML_L80);

        // WHY : Assumptions: the strip above is applied only to compare content against the declared
        //       literal, so the unstripped record is asserted here to begin at position zero with no
        //       leading blank. A fragment emitted with one space of indentation would strip to the same
        //       literal and would be a byte difference in all 22 header records at once.
        assertThat(header).allSatisfy(record ->
                assertThat(text(record)).doesNotStartWith(" "));
        assertThat(footer).allSatisfy(record ->
                assertThat(text(record)).doesNotStartWith(" "));
    }

    /**
     * Every value the document carries comes from the prepared records and nothing is derived here.
     *
     * <p>Assumptions: the property is asserted structurally rather than by inspection. Each component
     * is given a recognisable value, and every one of them is then located in the emitted records; a
     * component the assembler derived for itself instead of reading would not carry the marker.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("every value in the document comes from the prepared records and none is derived here")
    void everyValueComesFromThePreparedRecords() {
        StatementTextMapper.PreparedHeaderFields header = new StatementTextMapper.PreparedHeaderFields(
                item("MARKERNAME", ASSEMBLED_NAME_WIDTH),
                item("MARKERADDRONE", ADDRESS_LINE_WIDTH),
                item("MARKERADDRTWO", ADDRESS_LINE_WIDTH),
                item("MARKERADDRTHREE", ASSEMBLED_ADDRESS_WIDTH),
                item("00000000123", ACCOUNT_ID_ITEM_WIDTH),
                BALANCE_ITEM,
                item("811", CREDIT_SCORE_ITEM_WIDTH));
        StatementTextMapper.PreparedTransactionFields row =
                new StatementTextMapper.PreparedTransactionFields(
                        item("MARKERTRANID", TRANSACTION_ID_WIDTH),
                        item("MARKERDESCRIPTION", DESCRIPTION_ITEM_WIDTH),
                        AMOUNT_ITEM);

        String document = Stream.concat(
                        StatementHtmlMapper.emitNameAddressAndBasicDetails(header).stream(),
                        StatementHtmlMapper.emitTransactionRow(row).stream())
                .map(StatementHtmlMapperTest::text)
                .reduce("", String::concat);

        // WHY : Assumptions: the reference populates its shared storage once per card at lines 462 to
        //       485 of app/cbl/CBSTM03A.CBL and once per transaction at lines 676 to 678, and both
        //       artifacts then read those same items -- the markup paragraph at lines 560, 571, 579, 587,
        //       614, 621 and 628 and the transaction cells at 687, 699 and 711. A marker missing here
        //       would mean the assembler had computed that value rather than read it.
        assertThat(document)
                .contains("MARKERNAME")
                .contains("MARKERADDRONE")
                .contains("MARKERADDRTWO")
                .contains("MARKERADDRTHREE")
                .contains("00000000123")
                .contains(BALANCE_ITEM)
                .contains("811")
                .contains("MARKERTRANID")
                .contains("MARKERDESCRIPTION")
                .contains(AMOUNT_ITEM);
    }

    /**
     * Rendering the markup document leaves every component of the prepared records unchanged.
     *
     * <p>Assumptions: the records are Java records and therefore immutable in their components, so this
     * asserts what the assembler does with them rather than what the type permits. An assembler that
     * normalised or trimmed a component on receipt and carried the result forward would be observable
     * only by rendering twice, which the case below does.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("rendering leaves every component of the prepared records unchanged")
    void renderingLeavesEveryComponentUnchanged() {
        StatementTextMapper.PreparedHeaderFields header = conformingHeader();
        StatementTextMapper.PreparedTransactionFields row = conformingRow();
        List<String> headerBefore = List.of(header.assembledName(), header.addressLine1(),
                header.addressLine2(), header.assembledAddress(), header.accountId(),
                header.currentBalance(), header.creditScore(), header.markupName());
        List<String> rowBefore = List.of(row.transactionId(), row.description(), row.amount());

        StatementHtmlMapper.emitNameAddressAndBasicDetails(header);
        StatementHtmlMapper.emitTransactionRow(row);

        // WHY : Assumptions: the derived narrowed name is included among the eight, because it is the one
        //       value the record computes rather than stores and therefore the one that could change if
        //       the assembler mutated the component it derives from. Comparing before and after is what
        //       makes the claim about the assembler rather than about the record type.
        assertThat(List.of(header.assembledName(), header.addressLine1(), header.addressLine2(),
                header.assembledAddress(), header.accountId(), header.currentBalance(),
                header.creditScore(), header.markupName())).isEqualTo(headerBefore);
        assertThat(List.of(row.transactionId(), row.description(), row.amount()))
                .isEqualTo(rowBefore);
    }

    /**
     * Rendering the same prepared records twice produces byte-identical output.
     *
     * <p>Assumptions: this is the property that lets a statement be re-generated and compared, and it
     * covers three failure modes at once: retained state between calls, a shared buffer returned rather
     * than copied, and a value read from outside the arguments such as a clock or a locale.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("rendering the same prepared records twice produces byte-identical output")
    void renderingTwiceProducesByteIdenticalOutput() {
        StatementTextMapper.PreparedHeaderFields header = conformingHeader();
        String accountItem = item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH);

        List<byte[]> first = new ArrayList<>();
        first.addAll(StatementHtmlMapper.emitDocumentHeader(accountItem));
        first.addAll(StatementHtmlMapper.emitNameAddressAndBasicDetails(header));
        first.addAll(StatementHtmlMapper.emitTransactionRow(conformingRow()));
        first.addAll(StatementHtmlMapper.emitDocumentFooter());

        List<byte[]> second = new ArrayList<>();
        second.addAll(StatementHtmlMapper.emitDocumentHeader(accountItem));
        second.addAll(StatementHtmlMapper.emitNameAddressAndBasicDetails(header));
        second.addAll(StatementHtmlMapper.emitTransactionRow(conformingRow()));
        second.addAll(StatementHtmlMapper.emitDocumentFooter());

        // WHY : Assumptions: the reference keeps no per-statement state of its own beyond the storage it
        //       re-blanks before each assembly, so two runs over one card must agree. Any disagreement
        //       here would mean the assembler consulted something outside its arguments, and a date read
        //       from a clock is the likeliest such thing.
        assertThat(second).hasSameSizeAs(first);
        for (int index = 0; index < first.size(); index++) {
            assertThat(second.get(index))
                    .as("record %d is reproduced byte for byte", index)
                    .isEqualTo(first.get(index));
        }

        // WHY : Assumptions: two runs would also agree if both returned the same array, so identity is
        //       asserted separately from equality. A caller writing one record to a data set and then
        //       assembling the next must not find the first changed underneath it, which is what a
        //       shared 100-byte buffer -- the shape the reference uses at line 149 of
        //       app/cbl/CBSTM03A.CBL -- would produce here.
        for (int index = 0; index < first.size(); index++) {
            assertThat(second.get(index)).isNotSameAs(first.get(index));
        }
    }

    /**
     * The markup document and the plain-text statement agree about every value they share.
     *
     * <p>Assumptions: this is the reason the prepared records exist at all. Without it the two emitters
     * could each be internally green while disagreeing about the same customer's name, the same
     * balance or the same transaction amount, and nothing in either file would notice. The two are
     * allowed to differ in exactly two respects and no others: the record lengths, 80 against 100, and
     * the right-trim regime's treatment of trailing blanks, which the plain-text bands do not apply.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the markup document and the plain-text statement agree about every shared value")
    void theTwoArtifactsAgreeAboutEverySharedValue() {
        StatementTextMapper.PreparedHeaderFields header = conformingHeader();
        StatementTextMapper.PreparedTransactionFields row = conformingRow();

        String textDocument = StatementTextMapper.emitHeaderBlock(header).stream()
                .map(StatementHtmlMapperTest::text)
                .reduce("", String::concat)
                + text(StatementTextMapper.emitTransactionLine(row));
        List<byte[]> markupCells = StatementHtmlMapper.emitNameAddressAndBasicDetails(header);
        List<byte[]> markupRow = StatementHtmlMapper.emitTransactionRow(row);

        // WHY : Assumptions: these three reach both artifacts under the whole-item regime, so they agree
        //       character for character with no allowance at all. The account identifier keeps its nine
        //       declared blanks, the balance keeps its leading zeros and its sign position, and the
        //       credit score keeps its seventeen declared blanks, in both.
        assertThat(renderedSpan(markupCells.get(ACCOUNT_ID_INDEX),
                StatementHtmlMapper.ACCOUNT_ID_CELL_PREFIX)).isEqualTo(header.accountId());
        assertThat(renderedSpan(markupCells.get(CURRENT_BALANCE_INDEX),
                StatementHtmlMapper.CURRENT_BALANCE_CELL_PREFIX))
                .isEqualTo(header.currentBalance());
        assertThat(renderedSpan(markupCells.get(CREDIT_SCORE_INDEX),
                StatementHtmlMapper.FICO_SCORE_CELL_PREFIX)).isEqualTo(header.creditScore());
        assertThat(textDocument)
                .contains(header.accountId())
                .contains(header.currentBalance())
                .contains(header.creditScore());

        // WHY : Assumptions: line 679 of app/cbl/CBSTM03A.CBL writes the plain-text band from the same
        //       three items that lines 687, 699 and 711 embed in the markup, so the two renderings are
        //       the same bytes. This is what makes a per-transaction disagreement between the artifacts
        //       structurally impossible rather than merely unobserved.
        assertThat(renderedSpan(markupRow.get(TRANSACTION_ID_INDEX),
                StatementHtmlMapper.PLAIN_CELL_PREFIX)).isEqualTo(row.transactionId());
        assertThat(renderedSpan(markupRow.get(DESCRIPTION_INDEX),
                StatementHtmlMapper.PLAIN_CELL_PREFIX)).isEqualTo(row.description());
        assertThat(renderedSpan(markupRow.get(AMOUNT_INDEX),
                StatementHtmlMapper.PLAIN_CELL_PREFIX)).isEqualTo(row.amount());
        assertThat(textDocument)
                .contains(row.transactionId())
                .contains(row.description())
                .contains(row.amount());

        // WHY : Assumptions: this is the one documented allowance, and it is stated as an equality after
        //       stripping rather than left as an inequality. The plain-text bands carry each item at its
        //       declared width while the markup cells cut each at its first blank pair, so the two
        //       differ in padding alone -- and the markup name additionally reflects the narrowing the
        //       record derives, so it is compared against that rather than against the assembled name.
        assertThat(renderedSpan(markupCells.get(NAME_CELL_INDEX),
                StatementHtmlMapper.NAME_CELL_PREFIX).strip())
                .isEqualTo(header.markupName().strip());
        assertThat(header.markupName().strip()).isEqualTo(header.assembledName().strip());
        assertThat(renderedSpan(markupCells.get(ADDRESS_LINE_1_INDEX),
                StatementHtmlMapper.PLAIN_CELL_PREFIX).strip())
                .isEqualTo(header.addressLine1().strip());
        assertThat(renderedSpan(markupCells.get(ADDRESS_LINE_2_INDEX),
                StatementHtmlMapper.PLAIN_CELL_PREFIX).strip())
                .isEqualTo(header.addressLine2().strip());
        assertThat(renderedSpan(markupCells.get(ASSEMBLED_ADDRESS_INDEX),
                StatementHtmlMapper.PLAIN_CELL_PREFIX).strip())
                .isEqualTo(header.assembledAddress().strip());

        // WHY : Assumptions: naming both allowances closes the assertion. If a third difference appeared
        //       -- a value formatted differently, a label spelled differently, a mask interchanged -- it
        //       would fall outside the two and would fail one of the equalities above rather than passing
        //       as an accepted variation.
        assertThat(StatementTextMapper.emitTransactionLine(row)).hasSize(80);
        assertThat(markupRow.get(AMOUNT_INDEX))
                .hasSize(StatementHtmlMapper.HTML_RECORD_LENGTH);
    }

    /**
     * The assembler reads no clock, so its output depends on its arguments alone.
     *
     * <p>Assumptions: no date or time appears in this artifact at all -- the reference's markup
     * paragraphs read none -- so the property asserted is the stronger one: two runs separated in time
     * produce identical bytes, and no field of any record changes between them.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the assembler reads no clock, so two runs separated in time agree")
    void theAssemblerReadsNoClock() {
        String accountItem = item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH);
        String firstRun = StatementHtmlMapper.emitDocumentHeader(accountItem).stream()
                .map(StatementHtmlMapperTest::text)
                .reduce("", String::concat);
        String secondRun = StatementHtmlMapper.emitDocumentHeader(accountItem).stream()
                .map(StatementHtmlMapperTest::text)
                .reduce("", String::concat);

        // WHY : Assumptions: an emitted year is the cheapest observable evidence of a clock read, and the
        //       reference's markup carries none -- its only dynamic values are the seven customer and
        //       account items and the three transaction items. Asserting the absence of a four-digit year
        //       alongside the equality catches a date that happened to be stable within one run.
        assertThat(secondRun).isEqualTo(firstRun);
        assertThat(firstRun)
                .doesNotContainPattern("\\b(?:19|20)\\d\\d\\b")
                .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }

    /**
     * A statement assembled from conforming data carries exactly the bytes it carried before.
     *
     * <p>Assumptions: this is the parity assertion the text-node encoding has to survive, and it is
     * expressed as the seven dynamic cells' exact expected records rather than as a document-level
     * comparison. Each expectation is the opening literal, the value as supplied, and the closing tag,
     * with no character reference anywhere in it, so the case passes only while the encoding leaves the
     * reference seed character classes -- upper-case letters, digits, blank, full stop and hyphen --
     * untouched.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("a statement assembled from conforming data is byte-identical to its former output")
    void conformingDataIsUnchangedByTheEncoding() {
        List<byte[]> records =
                StatementHtmlMapper.emitNameAddressAndBasicDetails(conformingHeader());

        assertThat(records).hasSize(StatementHtmlMapper.NAME_ADDRESS_BASIC_DETAIL_LINE_COUNT);
        assertThat(text(records.get(NAME_CELL_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.NAME_CELL_PREFIX + NAME_TEXT
                        + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(ADDRESS_LINE_1_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + ADDRESS_1_TEXT
                        + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(ADDRESS_LINE_2_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + ADDRESS_2_TEXT
                        + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(ASSEMBLED_ADDRESS_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + ASSEMBLED_ADDRESS_TEXT
                        + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));

        // WHY : Assumptions: that regime transfers the item complete -- the three concatenations at lines
        //       615, 622 and 629 of app/cbl/CBSTM03A.CBL are delimited by an asterisk the data never
        //       holds -- so asserting them trimmed would pass for an implementation that had started
        //       trimming, and that would move the closing tag of every basic-detail cell and misalign
        //       the panel down the document.
        assertThat(text(records.get(ACCOUNT_ID_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.ACCOUNT_ID_CELL_PREFIX
                        + item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH)
                        + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(CURRENT_BALANCE_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.CURRENT_BALANCE_CELL_PREFIX + BALANCE_ITEM
                        + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(CREDIT_SCORE_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.FICO_SCORE_CELL_PREFIX
                        + item(CREDIT_SCORE_TEXT, CREDIT_SCORE_ITEM_WIDTH)
                        + StatementHtmlMapper.CELL_SUFFIX));

        // WHY : Assumptions: the declared fragments carry real angle brackets -- every one of the 34
        //       literals at lines 150 to 211 of app/cbl/CBSTM03A.CBL opens or closes an element -- so a
        //       change that encoded them would leave the document rendering its own tags as visible
        //       text. A per-cell assertion would not see that, because the fragments are not cells.
        assertThat(records).allSatisfy(record -> assertThat(text(record))
                .doesNotContain(StatementHtmlMapper.AMPERSAND_ENTITY)
                .doesNotContain(StatementHtmlMapper.LESS_THAN_ENTITY)
                .doesNotContain(StatementHtmlMapper.GREATER_THAN_ENTITY)
                .doesNotContain(StatementHtmlMapper.QUOTE_ENTITY)
                .doesNotContain(StatementHtmlMapper.APOSTROPHE_ENTITY));
    }

    /**
     * A transaction row assembled from conforming data carries exactly the bytes it carried before.
     *
     * <p>Assumptions: the row is asserted separately from the header block because its three cells reach
     * the encoding through the whole-item regime while the header block's first four reach it through
     * the right-trim regime, so a regression confined to one regime would leave the other passing.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("a transaction row assembled from conforming data is byte-identical to its former"
            + " output")
    void conformingTransactionRowIsUnchangedByTheEncoding() {
        List<byte[]> records = StatementHtmlMapper.emitTransactionRow(conformingRow());

        assertThat(records).hasSize(StatementHtmlMapper.TRANSACTION_ROW_LINE_COUNT);
        assertThat(text(records.get(TRANSACTION_ID_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX
                        + item(TRANSACTION_ID_TEXT, TRANSACTION_ID_WIDTH)
                        + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(DESCRIPTION_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX
                        + item(DESCRIPTION_TEXT, DESCRIPTION_ITEM_WIDTH)
                        + StatementHtmlMapper.CELL_SUFFIX));
        assertThat(text(records.get(AMOUNT_INDEX))).isEqualTo(expectedRecord(
                StatementHtmlMapper.PLAIN_CELL_PREFIX + AMOUNT_ITEM
                        + StatementHtmlMapper.CELL_SUFFIX));
    }

    /**
     * The declared fragments reach the document as markup rather than as encoded text.
     *
     * <p>Assumptions: this is its own case because it is the property most easily broken by
     * over-applying the encoding. Encoding at the shared record exit rather than at the two cell
     * helpers would produce a document whose every tag rendered as visible text, which is a different
     * defect of the same origin and would leave every geometry assertion in this file passing.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the declared fragments reach the document as markup and are never encoded")
    void declaredFragmentsAreNotEncoded() {
        List<byte[]> header = StatementHtmlMapper.emitDocumentHeader(
                item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH));

        assertThat(header).hasSize(StatementHtmlMapper.DOCUMENT_HEADER_LINE_COUNT);
        String document = header.stream().map(StatementHtmlMapperTest::text)
                .reduce("", String::concat);
        assertThat(document)
                .contains("<")
                .contains(">")
                .doesNotContain(StatementHtmlMapper.LESS_THAN_ENTITY)
                .doesNotContain(StatementHtmlMapper.GREATER_THAN_ENTITY);
        assertThat(text(header.get(ACCOUNT_HEADING_INDEX)))
                .startsWith(StatementHtmlMapper.ACCOUNT_HEADING_PREFIX)
                .contains(ACCOUNT_DIGITS);
        assertThat(StatementHtmlMapper.emitDocumentFooter()).allSatisfy(record ->
                assertThat(text(record))
                        .doesNotContain(StatementHtmlMapper.LESS_THAN_ENTITY)
                        .doesNotContain(StatementHtmlMapper.GREATER_THAN_ENTITY));
    }

    /**
     * A name shaped like a script element is rendered as text and cannot execute.
     *
     * <p>Assumptions: both directions are asserted -- the encoded form present AND the executable form
     * absent. Asserting only the presence of the encoded form would pass for output that carried both,
     * which is what a partial encoding produces.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("a name shaped like a script element is rendered as text rather than as an element")
    void aScriptShapedNameIsRenderedAsText() {
        String hostile = "<script>alert(1)</script>";
        byte[] nameCell = StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithName(item(hostile, ASSEMBLED_NAME_WIDTH))).get(NAME_CELL_INDEX);

        assertThat(text(nameCell)).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(text(nameCell)).doesNotContain("<script").doesNotContain("</script");
        assertThat(text(nameCell)).isEqualTo(expectedRecord(StatementHtmlMapper.NAME_CELL_PREFIX
                + "&lt;script&gt;alert(1)&lt;/script&gt;"
                + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));
    }

    /**
     * A stored character reference is encoded again rather than passed through.
     *
     * <p>Assumptions: this is the case that proves the ampersand is encoded, and it is the one most
     * easily left out. A value holding {@code &lt;} would, with the ampersand untouched, render in a
     * browser as an angle bracket -- so the encoding would be defeated by data that merely spells the
     * encoding itself.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("a stored character reference is encoded again and cannot reintroduce a metacharacter")
    void aStoredCharacterReferenceIsEncodedAgain() {
        byte[] nameCell = StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithName(item("&lt;script&gt;", ASSEMBLED_NAME_WIDTH))).get(NAME_CELL_INDEX);

        assertThat(text(nameCell)).isEqualTo(expectedRecord(StatementHtmlMapper.NAME_CELL_PREFIX
                + "&amp;lt;script&amp;gt;"
                + StatementHtmlMapper.PAIRED_BLANK + StatementHtmlMapper.CELL_SUFFIX));

        // WHY : Assumptions: the record's own opening literal and closing tag are declared markup and
        //       legitimately carry angle brackets -- the 26 characters declared at lines 218 and 219 of
        //       app/cbl/CBSTM03A.CBL and the 4 the concatenation supplies at line 565 -- so extracting
        //       the value's span by that declared prefix length is what keeps the assertion about the
        //       data rather than about the wrapper.
        assertThat(renderedSpan(nameCell, StatementHtmlMapper.NAME_CELL_PREFIX))
                .doesNotContain("<").doesNotContain(">");
    }

    /**
     * Every markup-significant character is replaced by its own character reference.
     *
     * <p>Assumptions: all five are swept in one case because the encoding is a switch, and a switch is
     * exactly the shape in which one arm goes missing without the others noticing. Each is checked BOTH
     * ways round -- the raw character absent and the reference present -- because an encoder that
     * dropped a character rather than replacing it would satisfy the first assertion alone.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("every markup-significant character is replaced by its own character reference")
    void everyMarkupSignificantCharacterIsReplaced() {
        Map<String, String> references = Map.of(
                "&", StatementHtmlMapper.AMPERSAND_ENTITY,
                "<", StatementHtmlMapper.LESS_THAN_ENTITY,
                ">", StatementHtmlMapper.GREATER_THAN_ENTITY,
                "\"", StatementHtmlMapper.QUOTE_ENTITY,
                "'", StatementHtmlMapper.APOSTROPHE_ENTITY);

        references.forEach((hostile, reference) -> {
            String emitted = text(StatementHtmlMapper.emitTransactionRow(rowWithDescription(
                    item("A" + hostile + "B", DESCRIPTION_ITEM_WIDTH))).get(DESCRIPTION_INDEX));

            assertThat(emitted)
                    .as("%s must not reach the document unencoded", hostile)
                    .doesNotContain("A" + hostile + "B");
            assertThat(emitted)
                    .as("%s must arrive as %s", hostile, reference)
                    .contains("A" + reference + "B");
        });
    }

    /**
     * A record-breaking control character is refused and the diagnostic withholds the value.
     *
     * <p>Assumptions: the artifact is a fixed-length record data set, so an embedded line feed does not
     * merely look wrong -- it forges a record boundary and displaces every record after it. Refusal is
     * asserted for the two terminators, the null and one escape specifically, and the diagnostic is
     * asserted to name the component while withholding the value.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("a record-breaking control character is refused and the diagnostic withholds the value")
    void aControlCharacterIsRefusedWithoutQuotingTheValue() {
        for (String control : List.of("\n", "\r", "\u0000", "\u001b")) {
            String hostile = item("AB" + control + "CD", DESCRIPTION_ITEM_WIDTH);
            assertThatThrownBy(() ->
                    StatementHtmlMapper.emitTransactionRow(rowWithDescription(hostile)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("description")
                    .hasMessageContaining("position 2")
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("AB"));
        }
    }

    /**
     * A character outside US-ASCII is refused rather than silently substituted.
     *
     * <p>Assumptions: the encoder would otherwise substitute a replacement byte, which would put a byte
     * into a cardholder's statement that the cardholder's data does not contain, and nothing downstream
     * could tell that had happened. Refusal is the only outcome in which the substitution cannot go
     * unnoticed.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("a character outside US-ASCII is refused rather than encoded as a replacement byte")
    void aNonAsciiCharacterIsRefused() {
        assertThatThrownBy(() -> StatementHtmlMapper.emitTransactionRow(
                rowWithDescription(item("AB\u00e9CD", DESCRIPTION_ITEM_WIDTH))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description")
                .hasMessageContaining("US-ASCII");

        assertThatThrownBy(() -> StatementHtmlMapper.emitTransactionRow(
                rowWithDescription(item("AB\u007fCD", DESCRIPTION_ITEM_WIDTH))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    /**
     * The tightest cell accepts two expanding characters and refuses three.
     *
     * <p>Assumptions: the boundary is asserted from BOTH sides on the assembled address, which is the
     * tightest cell in the artifact: its 3-character prefix, its 80 declared characters, its
     * re-appended blank pair and its 4-character closing tag occupy 89 of the 100, leaving eleven. A
     * quotation mark expands by five, so two fit at 99 and three overrun at 104. Asserting only the
     * overrun would pass for an implementation that refused every quote, and asserting only the
     * accepted case would pass for one that truncated instead of refusing.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the tightest cell accepts two expanding characters and refuses three")
    void theTightestCellFailsClosedWhenEncodingOverrunsTheRecord() {
        String twoQuotes = "X".repeat(ASSEMBLED_ADDRESS_WIDTH - 2) + "\"\"";
        String threeQuotes = "X".repeat(ASSEMBLED_ADDRESS_WIDTH - 3) + "\"\"\"";
        assertThat(twoQuotes).hasSize(ASSEMBLED_ADDRESS_WIDTH);
        assertThat(threeQuotes).hasSize(ASSEMBLED_ADDRESS_WIDTH);

        assertThatCode(() -> StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithAddress(twoQuotes))).doesNotThrowAnyException();

        assertThatThrownBy(() -> StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithAddress(threeQuotes)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assembledAddress")
                .hasMessageContaining(String.valueOf(StatementHtmlMapper.HTML_RECORD_LENGTH))
                .hasMessageContaining("withheld");
    }

    /**
     * The overlength diagnostic reports lengths only and never the assembled content.
     *
     * <p>Assumptions: the marker searched for is a distinctive string placed inside the offending value,
     * so its absence from the message is evidence that no part of the value was reproduced. The
     * declared literals are searched for as well, because the assembled content is the value wrapped in
     * them and a message quoting the wrapper would be quoting the value's position in the
     * document.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the overlength diagnostic reports lengths and never the statement content")
    void theOverlengthDiagnosticWithholdsTheContent() {
        String marker = "DISTINCTIVEVALUE";
        String hostile =
                marker + "X".repeat(ASSEMBLED_ADDRESS_WIDTH - marker.length() - 3) + "\"\"\"";
        assertThat(hostile).hasSize(ASSEMBLED_ADDRESS_WIDTH);

        assertThatThrownBy(() -> StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithAddress(hostile)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain(marker)
                        .doesNotContain(StatementHtmlMapper.QUOTE_ENTITY)
                        .doesNotContain(StatementHtmlMapper.CELL_SUFFIX)
                        .contains("104")
                        .contains("excess of 4"));
    }

    /**
     * The account heading refuses any character outside the digits and blanks its item declares.
     *
     * <p>Assumptions: this cell is guarded by a character domain rather than by the encoding the other
     * cells receive, because its assembled heading must be exactly 59 characters and any expansion at
     * all would fail that. Both directions are asserted: a conforming item is accepted, and twenty
     * characters of markup are refused rather than reaching the document as a heading.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the account heading refuses any character outside digits and blanks")
    void theAccountHeadingRefusesAnythingButDigitsAndBlanks() {
        assertThatCode(() -> StatementHtmlMapper.emitDocumentHeader(
                item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH))).doesNotThrowAnyException();

        String hostile = "<script>abcdefghij</";
        assertThat(hostile).hasSize(ACCOUNT_ID_ITEM_WIDTH);
        assertThatThrownBy(() -> StatementHtmlMapper.emitDocumentHeader(hostile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountIdItem")
                .hasMessageContaining("position 0");
    }

    /**
     * Each emitter that takes an argument refuses a null one rather than rendering the word null.
     *
     * <p>Assumptions: asserted because the alternative failure is silent rather than loud. String
     * concatenation renders a null reference as four characters, so an emitter that did not check would
     * produce a well-formed record of exactly the declared length carrying that word where a
     * cardholder's name belongs, and every geometry assertion in this file would pass.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("each emitter refuses a null argument rather than rendering the word null")
    void eachEmitterRefusesANullArgument() {
        assertThatThrownBy(() -> StatementHtmlMapper.emitDocumentHeader(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StatementHtmlMapper.emitNameAddressAndBasicDetails(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StatementHtmlMapper.emitTransactionRow(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * The blank-pair cut is applied before the encoding, not after it.
     *
     * <p>Assumptions: the order is observable and is asserted for that reason. Encoding first would let
     * a character reference introduced by the encoding supply the blank pair the cut looks for, or move
     * the pair the value carried, so a value would be cut at a position the reference's own
     * concatenation would not have cut it at. The value here carries a metacharacter before its blank
     * pair and text after it: the metacharacter must arrive encoded and the trailing text must be
     * gone.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the blank-pair cut precedes the encoding")
    void theBlankPairCutPrecedesTheEncoding() {
        String emitted = text(StatementHtmlMapper.emitNameAddressAndBasicDetails(
                headerWithName(item("A&B  CUTAWAY", ASSEMBLED_NAME_WIDTH))).get(NAME_CELL_INDEX));

        assertThat(emitted).contains("A" + StatementHtmlMapper.AMPERSAND_ENTITY + "B")
                .doesNotContain("CUTAWAY");
    }

    /**
     * A script element in the transaction description is rendered as text.
     *
     * <p>Assumptions: the description sink is asserted separately from the name sink because the two
     * reach the encoding through different regimes, so a regression confined to one would leave the
     * other passing. The description is also the only one of the ten dynamic sinks fed from transaction
     * data rather than from customer data, which is the half an operator cannot vet.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("a script element in the transaction description is rendered as text")
    void aScriptElementInTheDescriptionIsRenderedAsText() {
        String emitted = text(StatementHtmlMapper.emitTransactionRow(rowWithDescription(
                item("<script>alert(1)</script>", DESCRIPTION_ITEM_WIDTH))).get(DESCRIPTION_INDEX));

        assertThat(emitted).doesNotContain("<script").doesNotContain("</script");
        assertThat(emitted).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    /**
     * No declared fragment and no cell prefix opens an attribute a dynamic value could reach.
     *
     * <p>Assumptions: this is what makes the text-context encoding SUFFICIENT rather than merely
     * necessary. Encoding for a text node does not make a value safe in a URL or a script context -- a
     * value placed in a location attribute can carry a script scheme with no angle bracket in it at all
     * -- so the claim that the text encoding is the whole obligation depends on the artifact having no
     * such context. Walking the whole table means a fragment added later cannot introduce one without
     * failing here.</p>
     *
     * <p>Assumptions: every attribute any of these literals declares is inherited from the reference
     * declarations at lines 150 to 219 of {@code app/cbl/CBSTM03A.CBL} and none is authored here. The
     * only attribute in the artifact is the presentational one on three of the paragraph prefixes and
     * on eleven of the cell literals; no literal declares a location attribute, an event handler or a
     * script element, and none references an external resource.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("no declared fragment or cell prefix opens a URL or script attribute")
    void noFragmentOpensAUrlOrScriptAttribute() {
        List<String> literals = Stream.concat(
                        StatementHtmlMapper.FRAGMENT_TABLE.stream(),
                        Stream.of(StatementHtmlMapper.NAME_CELL_PREFIX,
                                StatementHtmlMapper.PLAIN_CELL_PREFIX,
                                StatementHtmlMapper.ACCOUNT_ID_CELL_PREFIX,
                                StatementHtmlMapper.CURRENT_BALANCE_CELL_PREFIX,
                                StatementHtmlMapper.FICO_SCORE_CELL_PREFIX,
                                StatementHtmlMapper.ACCOUNT_HEADING_PREFIX,
                                StatementHtmlMapper.ACCOUNT_HEADING_SUFFIX))
                .toList();

        assertThat(literals)
                .as("the fragment table is complete and the seven prefixes are counted with it")
                .hasSize(StatementHtmlMapper.FRAGMENT_COUNT + NON_FRAGMENT_LITERAL_COUNT);
        assertThat(literals).allSatisfy(literal -> {
            String lowered = literal.toLowerCase(Locale.ROOT);
            assertThat(URL_ATTRIBUTE_NAMES)
                    .as("the literal %s opens no URL attribute", literal)
                    .noneSatisfy(urlAttribute -> assertThat(lowered).contains(urlAttribute));
            assertThat(EVENT_HANDLER_ATTRIBUTE.matcher(lowered).find())
                    .as("the literal %s declares no inline event handler", literal)
                    .isFalse();
            assertThat(lowered)
                    .as("the literal %s declares no script element", literal)
                    .doesNotContain("<script");
        });
    }

    /**
     * The four declared record counts hold exactly for conforming values.
     *
     * <p>Assumptions: the counts are asserted as EXACT rather than as minima, which is the geometry
     * decision the assembler makes. One cell occupies one record and a value that would widen past the
     * record length is refused at the sink, so an emission's arity is fixed by the reference paragraph
     * it reproduces and never by the data. An implementation that continued an over-wide cell onto a
     * further record would satisfy a minimum and would break the reference's line numbering.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the four declared record counts hold exactly for conforming values")
    void theFourDeclaredCountsHoldExactly() {
        assertThat(StatementHtmlMapper.emitDocumentHeader(
                item(ACCOUNT_DIGITS, ACCOUNT_ID_ITEM_WIDTH)))
                .hasSize(StatementHtmlMapper.DOCUMENT_HEADER_LINE_COUNT);
        assertThat(StatementHtmlMapper.emitNameAddressAndBasicDetails(conformingHeader()))
                .hasSize(StatementHtmlMapper.NAME_ADDRESS_BASIC_DETAIL_LINE_COUNT);
        assertThat(StatementHtmlMapper.emitTransactionRow(conformingRow()))
                .hasSize(StatementHtmlMapper.TRANSACTION_ROW_LINE_COUNT);
        assertThat(StatementHtmlMapper.emitDocumentFooter())
                .hasSize(StatementHtmlMapper.DOCUMENT_FOOTER_LINE_COUNT);

        // WHY : Assumptions: 22 + 34 + 11 + 8 is 75, and grep -c 'WRITE FD-HTMLFILE-REC' over
        //       app/cbl/CBSTM03A.CBL reports exactly 75. The two numbers are reached independently, so
        //       their agreement is what shows no write site has been dropped from any of the four
        //       sequences -- which a per-operation count on its own could not show.
        assertThat(StatementHtmlMapper.DOCUMENT_HEADER_LINE_COUNT
                + StatementHtmlMapper.NAME_ADDRESS_BASIC_DETAIL_LINE_COUNT
                + StatementHtmlMapper.TRANSACTION_ROW_LINE_COUNT
                + StatementHtmlMapper.DOCUMENT_FOOTER_LINE_COUNT)
                .isEqualTo(75);
    }

    /**
     * The five published entity constants are the spellings the shared encoder actually produces.
     *
     * <p>Purpose: this artifact declares its entity vocabulary publicly, and those five constants are
     * asserted by a dozen cases in this class. Since the replacement itself is now performed by
     * {@link HtmlTextEncoder} rather than by a table in the mapper, the constants would otherwise be a
     * second statement of the same fact -- correct today and free to drift the moment the shared
     * encoder changed a spelling. This case makes them a MIRROR that fails loudly instead.</p>
     *
     * <p>Refactoring Rationale: the mapper used to hold its own five-character replacement table, and
     * the two tables were byte-identical by coincidence rather than by construction: nothing compared
     * them, so a correction applied to the shared encoder would have left this artifact emitting the
     * old spelling. The table here has been removed and the encoding delegated; what remains is the
     * published vocabulary, and this case is what binds it to the implementation.</p>
     *
     * <p>Assumptions: the encoder is called on the bare character rather than through a cell, because
     * the point of comparison is the SPELLING and a cell would add prefix, padding and record framing
     * that the other cases in this class already cover.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the published entity constants mirror the shared encoder's own spellings")
    void thePublishedEntityConstantsMirrorTheSharedEncoder() {
        assertThat(HtmlTextEncoder.encode("&"))
                .as("the ampersand must be encoded first and spelled as this artifact publishes it")
                .isEqualTo(StatementHtmlMapper.AMPERSAND_ENTITY);
        assertThat(HtmlTextEncoder.encode("<"))
                .isEqualTo(StatementHtmlMapper.LESS_THAN_ENTITY);
        assertThat(HtmlTextEncoder.encode(">"))
                .isEqualTo(StatementHtmlMapper.GREATER_THAN_ENTITY);
        assertThat(HtmlTextEncoder.encode("\""))
                .isEqualTo(StatementHtmlMapper.QUOTE_ENTITY);
        assertThat(HtmlTextEncoder.encode("'"))
                .as("the apostrophe is the numeric reference, not the named one, because this artifact"
                        + " declares no document type that would guarantee the named form")
                .isEqualTo(StatementHtmlMapper.APOSTROPHE_ENTITY);
    }

    /**
     * The shared encoder replaces exactly the five characters this artifact publishes and no others.
     *
     * <p>Assumptions: the closure is asserted over the whole printable US-ASCII range this artifact
     * admits, so a shared encoder that began encoding a sixth character would fail here. That matters
     * to a fixed-length record: an unannounced replacement would expand a cell by up to five
     * characters and displace every closing tag after it, and the expansion budget this class computes
     * is sized from the five it knows about.</p>
     *
     * <p>This test takes no parameter, returns no value and raises nothing.</p>
     */
    @Test
    @DisplayName("the shared encoder replaces exactly the five characters this artifact publishes")
    void theSharedEncoderReplacesExactlyTheFivePublishedCharacters() {
        List<String> replaced = new ArrayList<>();
        for (char character = StatementHtmlMapper.LOWEST_RENDERABLE_CHARACTER;
                character < StatementHtmlMapper.LOWEST_REFUSED_HIGH_CHARACTER; character++) {
            String single = String.valueOf(character);
            if (!HtmlTextEncoder.encode(single).equals(single)) {
                replaced.add(single);
            }
        }

        assertThat(replaced)
                .as("a sixth replaced character would expand a cell this artifact has already sized")
                .containsExactlyInAnyOrder("&", "<", ">", "\"", "'");
    }
}
