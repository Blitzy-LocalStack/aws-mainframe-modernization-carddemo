package com.carddemo.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the 133-column geometry of the seven daily transaction report bands to the copybook that
 * declares them.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class holds {@link ReportBandLayouts} to {@code app/cpy/CVTRA07Y.cpy} byte for byte. Every
 * figure asserted below is written out here as a constant summed by hand from that copybook read in
 * full: each band's declared record length, the sum of the widths its own items declare, how many of
 * those items are {@code FILLER}, the width of each dot leader, the content of each literal, and the
 * column each amount lands in. No expectation in this class is derived from the descriptor under test,
 * because an expectation read out of the thing being tested agrees with it by construction and asserts
 * nothing.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class instantiated by the test
 * engine, so the type accepts no parameter, yields no value and raises nothing of its own. The
 * inapplicability is stated rather than passed over, because user-specified Rule 1 (Explainability)
 * forbids at its line 39 a docstring that omits parameters, return values or purpose, and a reader has
 * to be able to tell a declared inapplicability from an oversight. Every method below carries its own
 * parameter, return and exception at-clauses.</p>
 *
 * <h2>Assumptions: the record length and the width sums are two contracts, not one</h2>
 *
 * <p>Two families of assertion appear below and BOTH are required. The first holds every one of the
 * seven descriptors to a record length of 133, which is the emission contract:
 * {@link FixedWidthCodec#encodeRecord(Map, CopybookLayout.RecordSpec)} returns an array of exactly that
 * many bytes. The second holds each band to the sum of the widths its own copybook items declare, which
 * are 115, 114, 114, 133, 112, 112 and 112 in declaration order. The second family is what proves the
 * field list COMPLETE. A descriptor that had dropped an item would still satisfy the first family,
 * because the shortfall is absorbed by padding and the encoded record would still be 133 bytes long with
 * its columns quietly moved. Only the width sum catches that.</p>
 *
 * <p>Assumptions: 133 is a DECLARED record length and never arithmetic over any band's fields. It is
 * anchored three independent ways. Line 48 of {@code app/cpy/CVTRA07Y.cpy} declares an elementary
 * level-01 item of {@code PIC X(133)}. Line 133 of {@code app/cbl/CBTRN03C.cbl} declares
 * {@code WS-BLANK-LINE PIC X(133) VALUE SPACES}. Line 78 of {@code app/jcl/TRANREPT.jcl} carries
 * {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} on the report data set. The detail band's own widths sum to
 * 114, so a reader who took that sum for the record length would land every later band nineteen bytes
 * short.</p>
 *
 * <h2>Assumptions: six of the seven bands are natively short of 133 and one is natively 133</h2>
 *
 * <p>Only the separator rule at line 48 reaches the record length on its own. The other six fall short
 * by 18, 19, 19, 21, 21 and 21 bytes and reach 133 through a trailing pad. This is the exact INVERSE of
 * the statement layouts, where every band is natively its declared width and none is padded at all. The
 * contrast is recorded here deliberately: the two artifacts sit in sibling classes in one package, and a
 * reader who carried this file's padding assumption across to the statement bands, or the statement
 * bands' no-padding assumption across to here, would be wrong in both directions.</p>
 *
 * <p>Assumptions: the trailing pad is a TARGET-SIDE construct and appears in no copybook.
 * {@link CopybookLayout.RecordSpec#validateGeometry()} is fail-closed on an exact sum, so the shortfall
 * cannot be left as an undescribed gap and the descriptor declares it as a field named
 * {@link ReportBandLayouts#FIELD_LINE_PAD}. Every width-sum assertion below therefore sums the band's
 * fields with that one field EXCLUDED, and the total including it is asserted separately against 133.
 * Summing all fields and expecting 115 would fail against a correct descriptor.</p>
 *
 * <h2>Assumptions: this test is independent of the edit-mask formatter</h2>
 *
 * <p>An edit mask is not one of the five storage regimes {@link CopybookLayout.Kind} offers, and
 * formatting an amount happens BEFORE the formatted string is placed into a band. The four amount items
 * of this copybook are declared {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} at line 30 and {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}
 * at lines 54, 60 and 66, each fifteen characters wide, and each is received by a plain character field
 * of that width. This class consequently names no formatter and imports none: it counts the fifteen
 * characters of those pictures for itself and asserts that width directly, so the geometry and the
 * rendering are pinned by two tests that cannot drift into agreeing with each other.</p>
 *
 * <p>Trade-offs: the cost of the approach above is bulk. Seventy-seven cases and around forty named
 * expectations hold seven lines of a report, and a far shorter version that compared each band against
 * one golden string would also fail when a column moved. That version was rejected because it would
 * fail as ONE assertion naming a whole record, leaving a reader to diff 133 bytes to learn which field
 * moved and by how much. The compromise accepted is verbosity here in exchange for a failure that names
 * the band, the field and the column, and for the three leader widths being separately falsifiable
 * rather than jointly.</p>
 *
 * <h2>Assumptions: every offset here is zero-based and every column number is one-based</h2>
 *
 * <p>{@link CopybookLayout.FieldSpec#start()} is a ZERO-based byte offset and
 * {@link CopybookLayout.FieldSpec#end()} is EXCLUSIVE. The column numbers a report is read by, and the
 * positions the reference sort control declares at lines 41 and 42 of {@code app/jcl/TRANREPT.jcl}, are
 * ONE-based. The conversion runs one way only, {@code zeroBased = oneBased - 1}. Applied backwards it
 * shifts a whole band by one byte, and a band shifted by one byte still prints readable text, so nothing
 * raises and only a byte comparison finds it. Both conventions are named explicitly at every site below
 * rather than left to the reader.</p>
 *
 * @see ReportBandLayouts
 * @see CopybookLayout
 */
class ReportBandLayoutsTest {

    // WHAT: the record length every band is emitted at.
    // WHY : Alternatives Considered: it is restated here as a literal rather than read from
    //       ReportBandLayouts.REPORT_RECORD_LENGTH, because a test that took the number from the class
    //       under test would assert that the class agrees with itself. The literal is the 133 declared
    //       at line 48 of app/cpy/CVTRA07Y.cpy, at line 133 of app/cbl/CBTRN03C.cbl and as LRECL at line
    //       78 of app/jcl/TRANREPT.jcl.
    private static final int DECLARED_RECORD_LENGTH = 133;

    // WHAT: the width of each of the four amount items, counted from their pictures.
    // WHY : Alternatives Considered: PIC -ZZZ,ZZZ,ZZZ.ZZ at line 30 and PIC +ZZZ,ZZZ,ZZZ.ZZ at lines 54,
    //       60 and 66 of app/cpy/CVTRA07Y.cpy each spell one sign position, nine digit positions, two
    //       grouping separators, one decimal point and two fractional digits, which is fifteen character
    //       positions. Counting the picture here rather than importing a formatter's width constant is
    //       what keeps this test independent of the formatter.
    private static final int AMOUNT_MASK_WIDTH = 15;

    // WHAT: the zero-based offset at which all four amount items open, and its one-based column.
    // WHY : Assumptions: 97 zero-based is one-based column 98, and 97 + 15 gives an exclusive end of
    //       112, so the amount occupies one-based columns 98 to 112 inclusive. The three total bands
    //       reach 97 from three different label-and-leader pairs and the detail band reaches it by
    //       accumulating its preceding items, so the agreement is arithmetic and any width change above
    //       an amount breaks it silently.
    private static final int AMOUNT_ZERO_BASED_START = 97;

    // WHAT: the one-based column the amount column opens on.
    // WHY : Alternatives Considered: named rather than written as 98 at each use, because the whole
    //       hazard this constant guards is a reader or an editor treating a zero-based offset and a
    //       one-based column as interchangeable. Lines 30, 54, 60 and 66 of app/cpy/CVTRA07Y.cpy place
    //       the mask at zero-based 97, which IS column 98, and lines 41 and 42 of app/jcl/TRANREPT.jcl
    //       declare their own positions one-based. A named one-based constant cannot be mistaken for an
    //       offset.
    private static final int AMOUNT_ONE_BASED_FIRST_COLUMN = 98;

    // WHAT: the one-based column the amount column closes on.
    // WHY : Assumptions: 112 is the LAST occupied column, inclusive, where FieldSpec.end() reports 112
    //       as an EXCLUSIVE offset. The two numbers coincide precisely because one is one-based and the
    //       other zero-based, which is a coincidence worth naming rather than a fact to lean on.
    private static final int AMOUNT_ONE_BASED_LAST_COLUMN = 112;

    // WHAT: the widths the six copybook items of the title band declare, summed.
    // WHY : Assumptions: lines 5, 7, 9, 11, 12 and 13 of app/cpy/CVTRA07Y.cpy declare 38, 41, 12, 10, 4
    //       and 10, which is 115. The seven native sums below are each a separate constant, and none is
    //       expressed in terms of another, so an edit to one band cannot move the expectation for a
    //       different band.
    private static final int NAME_HEADER_NATIVE_WIDTH = 115;

    // WHAT: the widths the sixteen copybook items of the detail band declare, summed.
    // WHY : Assumptions: lines 16 to 31 of app/cpy/CVTRA07Y.cpy declare 16, 1, 11, 1, 2, 1, 15, 1, 4, 1,
    //       29, 1, 10, 4, 15 and 2, which is 114. This is the number most likely to be mistaken for the
    //       record length, since it is the band a report is mostly made of.
    private static final int DETAIL_NATIVE_WIDTH = 114;

    // WHAT: the widths the seven copybook items of the column heading band declare, summed.
    // WHY : Assumptions: lines 34, 36, 38, 40, 42, 44 and 45 of app/cpy/CVTRA07Y.cpy declare 17, 12, 19,
    //       35, 14, 1 and 16, which is 114. It agrees with the detail band's sum by arithmetic
    //       coincidence and not by any shared rule, which is why it is declared separately.
    private static final int COLUMN_HEADER_NATIVE_WIDTH = 114;

    // WHAT: the width the single elementary item of the separator rule declares.
    // WHY : Alternatives Considered: line 48 of app/cpy/CVTRA07Y.cpy declares PIC X(133) directly on the
    //       level-01 item, so this band is the only one whose native width IS the record length. It is
    //       declared as its own constant rather than reusing DECLARED_RECORD_LENGTH so that the two
    //       facts stay independently falsifiable: one is what the data set holds, the other is what this
    //       one band declares.
    private static final int SEPARATOR_RULE_NATIVE_WIDTH = 133;

    // WHAT: the widths the three copybook items of the page total band declare, summed.
    // WHY : Assumptions: lines 51, 53 and 54 of app/cpy/CVTRA07Y.cpy declare 11, 86 and 15, which is
    //       112.
    private static final int PAGE_TOTAL_NATIVE_WIDTH = 112;

    // WHAT: the widths the three copybook items of the card-break total band declare, summed.
    // WHY : Alternatives Considered: lines 57, 59 and 60 of app/cpy/CVTRA07Y.cpy declare 13, 84 and 15,
    //       which is also 112. The three total bands reaching the same 112 from different
    //       label-and-leader pairs is the whole point of the compensating leader widths, so each band
    //       keeps its own constant and a single shared one would hide the compensation it exists to
    //       prove.
    private static final int ACCOUNT_TOTAL_NATIVE_WIDTH = 112;

    // WHAT: the widths the three copybook items of the grand total band declare, summed.
    // WHY : Assumptions: lines 63, 65 and 66 of app/cpy/CVTRA07Y.cpy declare 11, 86 and 15, which is
    //       112.
    private static final int GRAND_TOTAL_NATIVE_WIDTH = 112;

    // WHAT: the number of items each band declares in the copybook, pad excluded.
    // WHY : Alternatives Considered: the counts are 6, 16, 7, 1, 3, 3 and 3 across lines 4 to 66 of
    //       app/cpy/CVTRA07Y.cpy. They are asserted alongside the width sums because the two fail
    //       differently: dropping a one-byte item changes the count by one and the sum by one, whereas
    //       merging two adjacent items of 1 and 4 into one of 5 changes the count and leaves the sum
    //       untouched. Neither check subsumes the other.
    private static final int NAME_HEADER_ITEM_COUNT = 6;

    // WHAT: the item count of the detail band.
    // WHY : Assumptions: lines 16 to 31 of app/cpy/CVTRA07Y.cpy, sixteen items of which eight are
    //       FILLER. This is the band where a merge would be easiest to make and hardest to see, since
    //       eight of its items are single-byte separators.
    private static final int DETAIL_ITEM_COUNT = 16;

    // WHAT: the item count of the column heading band.
    // WHY : Assumptions: lines 34 to 45 of app/cpy/CVTRA07Y.cpy, seven items every one of which is
    //       FILLER, so this band has no varying content at all.
    private static final int COLUMN_HEADER_ITEM_COUNT = 7;

    // WHAT: the item count of the separator rule band.
    // WHY : Assumptions: line 48 of app/cpy/CVTRA07Y.cpy is an ELEMENTARY level-01 item with its own
    //       picture and no subordinates, so the band has exactly one item and that item is the band.
    private static final int SEPARATOR_RULE_ITEM_COUNT = 1;

    // WHAT: the item count of the page total band.
    // WHY : Assumptions: lines 51 to 54, 57 to 60 and 63 to 66 of app/cpy/CVTRA07Y.cpy each declare a
    //       label, a dot leader and an amount. The three counts agree because the three bands have the
    //       same shape, and each is declared separately for the reason recorded on
    //       ACCOUNT_TOTAL_NATIVE_WIDTH.
    private static final int PAGE_TOTAL_ITEM_COUNT = 3;

    // WHAT: the item count of the card-break total band.
    // WHY : Assumptions: lines 57, 59 and 60 of app/cpy/CVTRA07Y.cpy.
    private static final int ACCOUNT_TOTAL_ITEM_COUNT = 3;

    // WHAT: the item count of the grand total band.
    // WHY : Assumptions: lines 63, 65 and 66 of app/cpy/CVTRA07Y.cpy.
    private static final int GRAND_TOTAL_ITEM_COUNT = 3;

    // WHAT: how many of each band's items are declared FILLER.
    // WHY : Alternatives Considered: the counts are 1, 8, 7, 0, 2, 2 and 2, which total 22. They are
    //       asserted per band as well as in total because a total alone would be satisfied by twenty-two
    //       FILLER items distributed wrongly, and it is the DISTRIBUTION that determines which literals
    //       each band prints.
    private static final int NAME_HEADER_FILLER_COUNT = 1;

    // WHAT: the FILLER count of the detail band.
    // WHY : Assumptions: lines 17, 19, 21, 23, 25, 27, 29 and 31 of app/cpy/CVTRA07Y.cpy. Six carry
    //       VALUE SPACES and two carry a hyphen literal.
    private static final int DETAIL_FILLER_COUNT = 8;

    // WHAT: the FILLER count of the column heading band.
    // WHY : Assumptions: lines 34, 36, 38, 40, 42, 44 and 45 of app/cpy/CVTRA07Y.cpy. Six carry a
    //       heading literal and one carries VALUE SPACES.
    private static final int COLUMN_HEADER_FILLER_COUNT = 7;

    // WHAT: the FILLER count of the separator rule band.
    // WHY : Assumptions: line 48 of app/cpy/CVTRA07Y.cpy declares a NAMED elementary level-01 item, so
    //       this band has no FILLER at all. Counting its hyphen rule as a FILLER, which is tempting
    //       because the rule is invariant content just as the leaders are, would make the corpus total
    //       twenty-three and put the per-band distribution out by one.
    private static final int SEPARATOR_RULE_FILLER_COUNT = 0;

    // WHAT: the FILLER count of the page total band.
    // WHY : Assumptions: lines 51 and 53 of app/cpy/CVTRA07Y.cpy, the label and the dot leader. The
    //       amount item at line 54 is named, so it is not counted here.
    private static final int PAGE_TOTAL_FILLER_COUNT = 2;

    // WHAT: the FILLER count of the card-break total band.
    // WHY : Assumptions: lines 57 and 59 of app/cpy/CVTRA07Y.cpy.
    private static final int ACCOUNT_TOTAL_FILLER_COUNT = 2;

    // WHAT: the FILLER count of the grand total band.
    // WHY : Assumptions: lines 63 and 65 of app/cpy/CVTRA07Y.cpy.
    private static final int GRAND_TOTAL_FILLER_COUNT = 2;

    // WHAT: the corpus-wide FILLER total across all seven bands.
    // WHY : Assumptions: 1 + 8 + 7 + 0 + 2 + 2 + 2 is 22, counted item by item from app/cpy/CVTRA07Y.cpy
    //       read in full, and every one of the 22 carries a VALUE clause. The proportion is therefore
    //       not most of them but all of them, which is the fact the whole content-versus-padding
    //       argument below rests on.
    private static final int CORPUS_FILLER_COUNT = 22;

    // WHAT: how many FILLER items of the 22 carry a literal rather than blanks.
    // WHY : Assumptions: fifteen carry printed text -- the date joiner at line 12, the two hyphen
    //       joiners at lines 21 and 25, the six headings at lines 34, 36, 38, 40, 42 and 45, and the
    //       three label and leader pairs at lines 51, 53, 57, 59, 63 and 65 of app/cpy/CVTRA07Y.cpy. The
    //       remaining seven are declared VALUE SPACES and are blank by declaration, not by omission, so
    //       the two populations are counted apart.
    private static final int CONTENT_BEARING_FILLER_COUNT = 15;

    // WHAT: how many FILLER items of the 22 are declared VALUE SPACES.
    // WHY : Assumptions: lines 17, 19, 23, 27, 29, 31 and 44 of app/cpy/CVTRA07Y.cpy. These seven are
    //       the only FILLER items whose declared content is blank, and 15 + 7 closing on 22 is what
    //       proves neither population has an item missing from it.
    private static final int BLANK_FILLER_COUNT = 7;

    // WHAT: the three total-label widths, expected independently of the class under test.
    // WHY : Alternatives Considered: 11 at line 51, 13 at line 57 and 11 at line 63 of
    //       app/cpy/CVTRA07Y.cpy. The page and grand widths agreeing at 11 follows from their two
    //       literals happening to be declared the same width, so each is its own constant and one edit
    //       cannot move all three.
    private static final int EXPECTED_PAGE_LABEL_WIDTH = 11;

    // WHAT: the card-break total label width.
    // WHY : Assumptions: 13 at line 57 of app/cpy/CVTRA07Y.cpy, the widest of the three by two bytes,
    //       and those two bytes are exactly what its shorter dot leader gives back.
    private static final int EXPECTED_ACCOUNT_LABEL_WIDTH = 13;

    // WHAT: the grand total label width.
    // WHY : Assumptions: 11 at line 63 of app/cpy/CVTRA07Y.cpy.
    private static final int EXPECTED_GRAND_LABEL_WIDTH = 11;

    // WHAT: the page total dot-leader width.
    // WHY : Alternatives Considered: 86 at line 53 of app/cpy/CVTRA07Y.cpy. This constant and the two
    //       below are declared and asserted SEPARATELY, never as one shared width. A single width would
    //       satisfy an implementation that had unified the three, and the failure that unification
    //       causes is invisible in a smoke test: the report still looks like a report, with 13 + 86
    //       putting the card-break amount at offset 99 instead of 97, two columns right of the other
    //       three.
    private static final int EXPECTED_PAGE_LEADER_WIDTH = 86;

    // WHAT: the card-break total dot-leader width.
    // WHY : Assumptions: 84 at line 59 of app/cpy/CVTRA07Y.cpy, two shorter than its siblings because
    //       its label is two longer. This is the one of the three a simplification would get wrong.
    private static final int EXPECTED_ACCOUNT_LEADER_WIDTH = 84;

    // WHAT: the grand total dot-leader width.
    // WHY : Assumptions: 86 at line 65 of app/cpy/CVTRA07Y.cpy.
    private static final int EXPECTED_GRAND_LEADER_WIDTH = 86;

    // WHAT: the width of the unparenthesised picture at line 44.
    // WHY : Assumptions: line 44 of app/cpy/CVTRA07Y.cpy declares FILLER PIC X with NO repeat count,
    //       which is one byte and not an unspecified width. A tokeniser that requires the X(n) form
    //       skips the item entirely, and that single byte is what closes the headings at offset 97, so
    //       losing it pulls the amount heading one column left of the amounts beneath it.
    private static final int EXPECTED_BARE_PICTURE_WIDTH = 1;

    // WHAT: how many leading spaces the amount heading literal carries.
    // WHY : Alternatives Considered: line 46 of app/cpy/CVTRA07Y.cpy opens the literal with eight spaces
    //       before the word, and AAP Rule T8 carries user-visible strings across character for
    //       character. The count is asserted as a number as well as through the literal, because a
    //       whitespace run is the one thing an editor silently reflows and a literal comparison alone
    //       reports only that two strings differ.
    private static final int EXPECTED_AMOUNT_HEADING_LEADING_SPACES = 8;

    // WHAT: a fifteen-character specimen for the signed-negative detail amount.
    // WHY : Alternatives Considered: line 30 of app/cpy/CVTRA07Y.cpy declares PIC -ZZZ,ZZZ,ZZZ.ZZ, so
    //       the value reaching the band is already rendered and this class only needs something of the
    //       right width to prove placement. Rendering correctness belongs to the formatter's own test,
    //       and duplicating it here would give two tests one subject.
    private static final String DETAIL_AMOUNT_SPECIMEN = "-      1,234.56";

    // WHAT: a fifteen-character specimen for the leading-plus total amount.
    // WHY : Assumptions: lines 54, 60 and 66 of app/cpy/CVTRA07Y.cpy declare PIC +ZZZ,ZZZ,ZZZ.ZZ. One
    //       specimen serves all three total bands because the three masks are declared identically; the
    //       three LEADERS differ, and that is what the leader assertions cover.
    private static final String TOTAL_AMOUNT_SPECIMEN = "+      1,234.56";

    // WHAT: the names the descriptor gives the copybook's 22 FILLER items.
    // WHY : Alternatives Considered: FILLER is the language's keyword for an item with NO name, so a
    //       line-derived suffix is supplied where the copybook supplied nothing to carry across, and AAP
    //       Rule T1 is untouched because no declared name is changed. The names are written out here as
    //       literals rather than read from the class under test, whose own constants are private, so
    //       this list is an independent statement of which 22 lines of app/cpy/CVTRA07Y.cpy declare a
    //       FILLER.
    private static final List<String> CORPUS_FILLER_NAMES = List.of(
            "FILLER-L12",
            "FILLER-L17", "FILLER-L19", "FILLER-L21", "FILLER-L23", "FILLER-L25",
            "FILLER-L27", "FILLER-L29", "FILLER-L31",
            "FILLER-L34", "FILLER-L36", "FILLER-L38", "FILLER-L40", "FILLER-L42",
            "FILLER-L44", "FILLER-L45",
            "FILLER-L51", "FILLER-L53",
            "FILLER-L57", "FILLER-L59",
            "FILLER-L63", "FILLER-L65");

    // WHAT: the exact literal each of the fifteen content-bearing FILLER items carries.
    // WHY : Assumptions: a FILLER carrying a VALUE is CONTENT and not padding -- the literal IS the
    //       printed report text. The reference program never restates any of them because line 362 of
    //       app/cbl/CBTRN03C.cbl re-initialises the band and the language's INITIALIZE verb SKIPS
    //       FILLER, so the bytes survive in a record area that persists. A Java band is assembled into a
    //       freshly allocated array with no previous content to survive, so every one of these literals
    //       has to be emitted explicitly, and this map is what proves each still is.
    private static final Map<String, String> CONTENT_BEARING_FILLER_LITERALS = Map.ofEntries(
            Map.entry("FILLER-L12", " to "),
            Map.entry("FILLER-L21", "-"),
            Map.entry("FILLER-L25", "-"),
            Map.entry("FILLER-L34", "Transaction ID"),
            Map.entry("FILLER-L36", "Account ID"),
            Map.entry("FILLER-L38", "Transaction Type"),
            Map.entry("FILLER-L40", "Tran Category"),
            Map.entry("FILLER-L42", "Tran Source"),
            Map.entry("FILLER-L45", "        Amount"),
            Map.entry("FILLER-L51", "Page Total"),
            Map.entry("FILLER-L53", ".".repeat(EXPECTED_PAGE_LEADER_WIDTH)),
            Map.entry("FILLER-L57", "Account Total"),
            Map.entry("FILLER-L59", ".".repeat(EXPECTED_ACCOUNT_LEADER_WIDTH)),
            Map.entry("FILLER-L63", "Grand Total"),
            Map.entry("FILLER-L65", ".".repeat(EXPECTED_GRAND_LEADER_WIDTH)));

    // WHAT: the seven FILLER items whose declared content is blank.
    // WHY : Alternatives Considered: lines 17, 19, 23, 27, 29, 31 and 44 of app/cpy/CVTRA07Y.cpy declare
    //       VALUE SPACES, so blank is what these seven were DECLARED to hold. They are listed apart from
    //       the fifteen above so that a blank by declaration cannot be confused with a literal that went
    //       missing, which is the failure the two populations exist to separate.
    private static final List<String> BLANK_FILLER_NAMES = List.of(
            "FILLER-L17", "FILLER-L19", "FILLER-L23", "FILLER-L27",
            "FILLER-L29", "FILLER-L31", "FILLER-L44");

    /**
     * One band's hand-summed expectations paired with the descriptor they hold to account.
     *
     * <p>Assumptions: every component is a figure taken from {@code app/cpy/CVTRA07Y.cpy} by reading the
     * band's declarations and adding them up. None is derived from {@code spec}, because a figure read
     * out of the descriptor under test would agree with it whatever it held.</p>
     *
     * @param spec the band descriptor under test
     * @param copybookName the level-01 name the copybook declares for the band
     * @param firstLine the one-based copybook line the band's declaration opens on
     * @param lastLine the one-based copybook line the band's declaration closes on
     * @param nativeWidthSum the sum of the widths the band's own copybook items declare, with the
     *     target-side trailing pad excluded
     * @param itemCount the number of items the copybook declares for the band, pad excluded
     * @param fillerCount how many of those copybook items are declared FILLER
     * @param padWidth the width of the target-side trailing pad, zero when the band declares none
     * @param completeValues a value map holding every declared field of the band, ready to encode
     */
    private record BandExpectation(
            CopybookLayout.RecordSpec spec,
            String copybookName,
            int firstLine,
            int lastLine,
            int nativeWidthSum,
            int itemCount,
            int fillerCount,
            int padWidth,
            Map<String, Object> completeValues) {

        /**
         * Returns the band's copybook name so a parameterised failure names the band it came from.
         *
         * @return the level-01 name {@code app/cpy/CVTRA07Y.cpy} declares for this band
         */
        @Override
        public String toString() {
            return copybookName;
        }
    }

    /**
     * Supplies the seven bands paired with expectations summed by hand from the copybook.
     *
     * <p>Assumptions: the order is the copybook's own declaration order, lines 4 through 66 of
     * {@code app/cpy/CVTRA07Y.cpy}, so a parameterised failure names a band a reader can go straight to.
     * It is deliberately NOT the order a report emits the bands in, which interleaves them and repeats
     * the rule line at three separate sites.</p>
     *
     * <p>Trade-offs: one provider feeds every seven-band sweep rather than each sweep enumerating the
     * bands for itself. That couples the sweeps to one ordering, which is accepted because the
     * alternative repeats seven descriptors and eight expectations at each of six call sites, and a band
     * added to five of the six lists would leave the sixth silently uncovered.</p>
     *
     * @return a stream of one argument per band, each a {@link BandExpectation} holding the descriptor
     *     and the figures it is held to
     */
    static Stream<Arguments> declaredBands() {
        return Stream.of(
                Arguments.of(new BandExpectation(
                        ReportBandLayouts.REPORT_NAME_HEADER, "REPORT-NAME-HEADER", 4, 13,
                        NAME_HEADER_NATIVE_WIDTH, NAME_HEADER_ITEM_COUNT, NAME_HEADER_FILLER_COUNT,
                        DECLARED_RECORD_LENGTH - NAME_HEADER_NATIVE_WIDTH, completeNameHeader())),
                Arguments.of(new BandExpectation(
                        ReportBandLayouts.TRANSACTION_DETAIL_REPORT, "TRANSACTION-DETAIL-REPORT", 15, 31,
                        DETAIL_NATIVE_WIDTH, DETAIL_ITEM_COUNT, DETAIL_FILLER_COUNT,
                        DECLARED_RECORD_LENGTH - DETAIL_NATIVE_WIDTH, completeDetail())),
                Arguments.of(new BandExpectation(
                        ReportBandLayouts.TRANSACTION_HEADER_1, "TRANSACTION-HEADER-1", 33, 46,
                        COLUMN_HEADER_NATIVE_WIDTH, COLUMN_HEADER_ITEM_COUNT, COLUMN_HEADER_FILLER_COUNT,
                        DECLARED_RECORD_LENGTH - COLUMN_HEADER_NATIVE_WIDTH,
                        ReportBandLayouts.columnHeaderRecord())),
                Arguments.of(new BandExpectation(
                        ReportBandLayouts.TRANSACTION_HEADER_2, "TRANSACTION-HEADER-2", 48, 48,
                        SEPARATOR_RULE_NATIVE_WIDTH, SEPARATOR_RULE_ITEM_COUNT,
                        SEPARATOR_RULE_FILLER_COUNT,
                        DECLARED_RECORD_LENGTH - SEPARATOR_RULE_NATIVE_WIDTH,
                        ReportBandLayouts.separatorRuleRecord())),
                Arguments.of(new BandExpectation(
                        ReportBandLayouts.REPORT_PAGE_TOTALS, "REPORT-PAGE-TOTALS", 50, 54,
                        PAGE_TOTAL_NATIVE_WIDTH, PAGE_TOTAL_ITEM_COUNT, PAGE_TOTAL_FILLER_COUNT,
                        DECLARED_RECORD_LENGTH - PAGE_TOTAL_NATIVE_WIDTH, completePageTotals())),
                Arguments.of(new BandExpectation(
                        ReportBandLayouts.REPORT_ACCOUNT_TOTALS, "REPORT-ACCOUNT-TOTALS", 56, 60,
                        ACCOUNT_TOTAL_NATIVE_WIDTH, ACCOUNT_TOTAL_ITEM_COUNT, ACCOUNT_TOTAL_FILLER_COUNT,
                        DECLARED_RECORD_LENGTH - ACCOUNT_TOTAL_NATIVE_WIDTH, completeAccountTotals())),
                Arguments.of(new BandExpectation(
                        ReportBandLayouts.REPORT_GRAND_TOTALS, "REPORT-GRAND-TOTALS", 62, 66,
                        GRAND_TOTAL_NATIVE_WIDTH, GRAND_TOTAL_ITEM_COUNT, GRAND_TOTAL_FILLER_COUNT,
                        DECLARED_RECORD_LENGTH - GRAND_TOTAL_NATIVE_WIDTH, completeGrandTotals())));
    }

    /**
     * Completes the title band template with the two dates the template leaves to its caller.
     *
     * <p>Assumptions: the two specimen dates are the literals the reference job declares at lines 43 and
     * 44 of {@code app/jcl/TRANREPT.jcl}, so the fixture carries the same window the reference selects
     * on. Both fields are declared {@code PIC X(10)} holding an already ISO-ordered date, so ten
     * characters exactly fill each.</p>
     *
     * @return a value map holding every declared field of the title band, ready to encode
     */
    private static Map<String, Object> completeNameHeader() {
        Map<String, Object> values = new LinkedHashMap<>(ReportBandLayouts.nameHeaderTemplate());
        values.put(ReportBandLayouts.FIELD_REPT_START_DATE, "2022-01-01");
        values.put(ReportBandLayouts.FIELD_REPT_END_DATE, "2022-07-06");
        return values;
    }

    /**
     * Completes the detail band template with the eight items the template leaves to its caller.
     *
     * <p>Alternatives Considered: the category code is supplied as an {@link Integer} rather than as
     * text, because its field is declared {@code PIC 9(04)} at line 24 of {@code app/cpy/CVTRA07Y.cpy}
     * and the unsigned display regime right-justifies and ZERO-fills. Supplying it as an integral value
     * is what lets this fixture prove the leading zeros survive; supplying rendered text would prove
     * only that the text was copied.</p>
     *
     * <p>Assumptions: no fixture value anywhere in this class is a primary account number or a card
     * verification value. The detail band declares neither -- it carries a transaction identifier, an
     * account identifier, a type code and description, a category code and description, a source and an
     * amount, and nothing else -- and the transaction identifier specimen is deliberately alphanumeric
     * so it cannot be read as a card number by a reviewer scanning for one.</p>
     *
     * @return a value map holding every declared field of the detail band, ready to encode
     */
    private static Map<String, Object> completeDetail() {
        Map<String, Object> values = new LinkedHashMap<>(ReportBandLayouts.detailTemplate());
        values.put(ReportBandLayouts.FIELD_TRAN_REPORT_TRANS_ID, "TRN0000000000001");
        values.put(ReportBandLayouts.FIELD_TRAN_REPORT_ACCOUNT_ID, "00000000011");
        values.put(ReportBandLayouts.FIELD_TRAN_REPORT_TYPE_CD, "01");
        values.put(ReportBandLayouts.FIELD_TRAN_REPORT_TYPE_DESC, "Purchase");
        values.put(ReportBandLayouts.FIELD_TRAN_REPORT_CAT_CD, Integer.valueOf(1));
        values.put(ReportBandLayouts.FIELD_TRAN_REPORT_CAT_DESC, "Regular Sales Draft");
        values.put(ReportBandLayouts.FIELD_TRAN_REPORT_SOURCE, "POS TERM");
        values.put(ReportBandLayouts.FIELD_TRAN_REPORT_AMT, DETAIL_AMOUNT_SPECIMEN);
        return values;
    }

    /**
     * Completes the page total band template with the rendered total it leaves to its caller.
     *
     * @return a value map holding every declared field of the page total band, ready to encode
     */
    private static Map<String, Object> completePageTotals() {
        Map<String, Object> values = new LinkedHashMap<>(ReportBandLayouts.pageTotalsTemplate());
        values.put(ReportBandLayouts.FIELD_REPT_PAGE_TOTAL, TOTAL_AMOUNT_SPECIMEN);
        return values;
    }

    /**
     * Completes the card-break total band template with the rendered total it leaves to its caller.
     *
     * @return a value map holding every declared field of the card-break total band, ready to encode
     */
    private static Map<String, Object> completeAccountTotals() {
        Map<String, Object> values = new LinkedHashMap<>(ReportBandLayouts.accountTotalsTemplate());
        values.put(ReportBandLayouts.FIELD_REPT_ACCOUNT_TOTAL, TOTAL_AMOUNT_SPECIMEN);
        return values;
    }

    /**
     * Completes the grand total band template with the rendered total it leaves to its caller.
     *
     * @return a value map holding every declared field of the grand total band, ready to encode
     */
    private static Map<String, Object> completeGrandTotals() {
        Map<String, Object> values = new LinkedHashMap<>(ReportBandLayouts.grandTotalsTemplate());
        values.put(ReportBandLayouts.FIELD_REPT_GRAND_TOTAL, TOTAL_AMOUNT_SPECIMEN);
        return values;
    }

    /**
     * Sums the declared widths of a band's own copybook items, excluding the target-side pad.
     *
     * <p>Alternatives Considered: the pad named {@link ReportBandLayouts#FIELD_LINE_PAD} appears in no
     * copybook, so a sum that included it would equal the declared record length on every band and could
     * never disagree with it. Excluding it is what makes the width sum an independent check rather than
     * a restatement of the record length.</p>
     *
     * @param spec the band descriptor whose fields are summed
     * @return the sum of the lengths of every field except the trailing pad
     */
    private static int nativeWidthSumOf(CopybookLayout.RecordSpec spec) {
        int sum = 0;
        for (CopybookLayout.FieldSpec field : spec.fields()) {
            if (!ReportBandLayouts.FIELD_LINE_PAD.equals(field.name())) {
                sum += field.length();
            }
        }
        return sum;
    }

    /**
     * Collects the names of a band's fields that the descriptor models as FILLER items.
     *
     * @param spec the band descriptor whose fields are inspected
     * @return the names of every field of the band that appears in the corpus FILLER name list, in
     *     declaration order
     */
    private static List<String> fillerNamesOf(CopybookLayout.RecordSpec spec) {
        List<String> found = new ArrayList<>();
        for (CopybookLayout.FieldSpec field : spec.fields()) {
            if (CORPUS_FILLER_NAMES.contains(field.name())) {
                found.add(field.name());
            }
        }
        return found;
    }

    /**
     * Merges the seven band templates into one view of every seeded field name and value.
     *
     * <p>Assumptions: no field name repeats across the seven bands, because
     * {@link FixedWidthCodec#encodeRecord(Map, CopybookLayout.RecordSpec)} resolves values BY NAME and a
     * map holds one value per key, so the descriptor had to make all 22 FILLER names distinct for the
     * records to be composable at all. Merging is therefore lossless, and the merged view is what lets a
     * single assertion cover all 22 rather than seven assertions covering a partition of them.</p>
     *
     * @return every field name the seven templates seed, mapped to the value they seed it with
     */
    private static Map<String, Object> allSeededValues() {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(ReportBandLayouts.nameHeaderTemplate());
        merged.putAll(ReportBandLayouts.detailTemplate());
        merged.putAll(ReportBandLayouts.columnHeaderRecord());
        merged.putAll(ReportBandLayouts.separatorRuleRecord());
        merged.putAll(ReportBandLayouts.pageTotalsTemplate());
        merged.putAll(ReportBandLayouts.accountTotalsTemplate());
        merged.putAll(ReportBandLayouts.grandTotalsTemplate());
        return merged;
    }

    /**
     * Reports the distinct character codes a literal is built from, ascending.
     *
     * <p>Alternatives Considered: this exists because a character-sequence assertion can state that a
     * literal CONTAINS a character but not that it contains nothing else, and the invariant that matters
     * for a rule line and a dot leader is exactly the latter. Reducing to distinct code points turns
     * "every character is this one" into a single-element comparison.</p>
     *
     * @param literal the literal to inspect
     * @return the distinct code points of the literal in ascending order
     */
    private static int[] distinctCharacterCodesOf(String literal) {
        return literal.chars().distinct().sorted().toArray();
    }

    /**
     * Encodes a band and returns its record as characters so column spans can be read off directly.
     *
     * <p>Assumptions: the encoded record is US-ASCII, one byte per character, which is what makes a
     * character index into the returned string the same thing as a byte offset into the record. That
     * equivalence is the only reason a column span may be asserted against a substring; it would not
     * hold for the code page the reference data sets use.</p>
     *
     * @param expectation the band and the complete value map to encode
     * @return the encoded record decoded as US-ASCII, exactly {@link #DECLARED_RECORD_LENGTH} characters
     *     long
     */
    private static String encodeToText(BandExpectation expectation) {
        byte[] encoded = FixedWidthCodec.encodeRecord(expectation.completeValues(),
                expectation.spec());
        return new String(encoded, StandardCharsets.US_ASCII);
    }

    /**
     * Holds every band to the 133-byte record length the report data set declares.
     *
     * <p>This is the first of the two width contracts and it is the EMISSION contract: it fixes how many
     * bytes a band occupies once written. It does not and cannot prove the field list complete, which is
     * what the companion width-sum test exists for.</p>
     *
     * @param expectation the band under test together with the figures it is held to
     */
    @ParameterizedTest
    @MethodSource("declaredBands")
    void everyBandDeclaresTheReportRecordLength(BandExpectation expectation) {
        // WHAT: the declared record length of the band, and the name it is declared under.
        // WHY : Assumptions: 133 is DECLARED, not summed. Line 48 of app/cpy/CVTRA07Y.cpy declares PIC
        //       X(133), line 133 of app/cbl/CBTRN03C.cbl declares WS-BLANK-LINE PIC X(133) VALUE SPACES,
        //       and line 78 of app/jcl/TRANREPT.jcl carries LRECL=133 on the output data set. Three
        //       independent declarations of one number, and no band's field widths among them.
        assertThat(expectation.spec().reclen())
                .as("band %s declared at lines %d to %d of app/cpy/CVTRA07Y.cpy",
                        expectation.copybookName(), expectation.firstLine(), expectation.lastLine())
                .isEqualTo(DECLARED_RECORD_LENGTH);
        assertThat(expectation.spec().name()).isEqualTo(expectation.copybookName());
    }

    /**
     * Holds every band to the sum of the widths its own copybook items declare.
     *
     * <p>This is the second width contract and it is what proves the field list COMPLETE. A descriptor
     * that had dropped an item would still pass the record-length test, because the shortfall is
     * absorbed by the trailing pad and the encoded record would still be 133 bytes with its columns
     * quietly moved. Only this sum catches that, which is why both tests are present rather than either
     * standing in for the other.</p>
     *
     * @param expectation the band under test together with the figures it is held to
     */
    @ParameterizedTest
    @MethodSource("declaredBands")
    void everyBandsOwnCopybookWidthsSumToItsHandCheckedTotal(BandExpectation expectation) {
        // WHAT: the sum of the band's declared widths with the target-side pad left out.
        // WHY : Alternatives Considered: the pad exists in no copybook, so a sum including it would
        //       equal 133 on every band and could never disagree with the record length. The seven
        //       expected sums -- 115, 114, 114, 133, 112, 112 and 112 -- were added up by hand from
        //       app/cpy/CVTRA07Y.cpy read in full, and six of them are SHORT of 133 while only the
        //       separator rule at line 48 reaches it natively.
        assertThat(nativeWidthSumOf(expectation.spec()))
                .as("band %s, lines %d to %d of app/cpy/CVTRA07Y.cpy",
                        expectation.copybookName(), expectation.firstLine(), expectation.lastLine())
                .isEqualTo(expectation.nativeWidthSum());

        // WHAT: the pad closes the gap between the band's own widths and the record length.
        // WHY : Assumptions: this is the INVERSE of the statement layouts in StatementBandLayouts, where
        //       every band is natively its declared width and none is padded, and only line 48 of
        //       app/cpy/CVTRA07Y.cpy is natively 133 here. Asserting the pad width as the exact
        //       remainder is what stops a reader carrying one file's padding assumption across to the
        //       other, in either direction.
        assertThat(expectation.nativeWidthSum() + expectation.padWidth())
                .as("band %s must reach its declared length through its pad alone",
                        expectation.copybookName())
                .isEqualTo(DECLARED_RECORD_LENGTH);
    }

    /**
     * Holds every band's fields to a contiguous tiling of its 133 declared bytes from offset zero.
     *
     * <p>Contiguity is asserted here rather than trusted to the descriptor's own construction-time
     * check, because that check runs inside the class under test and a test that assumed it would be
     * asserting the subject's self-assessment. One gap or one overlap must fail.</p>
     *
     * @param expectation the band under test together with the figures it is held to
     */
    @ParameterizedTest
    @MethodSource("declaredBands")
    void everyBandTilesItsDeclaredLengthContiguouslyFromOffsetZero(BandExpectation expectation) {
        int cursor = 0;
        for (CopybookLayout.FieldSpec field : expectation.spec().fields()) {
            // WHAT: each field opens exactly where the previous field's exclusive end left off.
            // WHY : Alternatives Considered: FieldSpec.start() is ZERO-based and FieldSpec.end() is
            //       EXCLUSIVE, so equality between one field's end and the next field's start forbids a
            //       gap and an overlap in one condition. A gap would leave undeclared bytes that no
            //       value fills; an overlap would let one field's tail overwrite the next field's head.
            assertThat(field.start())
                    .as("band %s field %s", expectation.copybookName(), field.name())
                    .isEqualTo(cursor);
            assertThat(field.end()).isEqualTo(field.start() + field.length());
            cursor = field.end();
        }

        // WHAT: the tiling closes exactly on the declared record length.
        // WHY : Assumptions: the 133 this closes on is declared at line 48 of app/cpy/CVTRA07Y.cpy, at
        //       line 133 of app/cbl/CBTRN03C.cbl and as LRECL at line 78 of app/jcl/TRANREPT.jcl.
        //       Closing short would leave trailing bytes no value reaches and closing long is not
        //       expressible at all, so this single equality is what makes the declared length a derived
        //       fact about the field list rather than an unchecked claim beside it.
        assertThat(cursor)
                .as("band %s must tile its declared length exactly", expectation.copybookName())
                .isEqualTo(DECLARED_RECORD_LENGTH);
    }

    /**
     * Holds every band to the number of items its copybook section declares.
     *
     * <p>The item count and the width sum fail differently and neither subsumes the other. Dropping a
     * one-byte item changes both. Merging two adjacent items of one and four bytes into a single item of
     * five changes the count and leaves the sum untouched.</p>
     *
     * @param expectation the band under test together with the figures it is held to
     */
    @ParameterizedTest
    @MethodSource("declaredBands")
    void everyBandDeclaresTheItemCountItsCopybookSectionHolds(BandExpectation expectation) {
        int padFieldCount = expectation.padWidth() == 0 ? 0 : 1;

        // WHAT: the descriptor's field count once the target-side pad is discounted.
        // WHY : Alternatives Considered: the expected counts are 6, 16, 7, 1, 3, 3 and 3, read off
        //       app/cpy/CVTRA07Y.cpy item by item. The pad is discounted by its own width being zero or
        //       not, rather than by searching the field list for its name, so the discount cannot
        //       silently apply to a band that has no pad.
        assertThat(expectation.spec().fields().size() - padFieldCount)
                .as("band %s, lines %d to %d of app/cpy/CVTRA07Y.cpy",
                        expectation.copybookName(), expectation.firstLine(), expectation.lastLine())
                .isEqualTo(expectation.itemCount());

        // WHAT: the separator rule alone carries no pad, and the other six each carry exactly one.
        // WHY : Assumptions: line 48 of app/cpy/CVTRA07Y.cpy is an ELEMENTARY level-01 item carrying its
        //       own PIC X(133), so it is natively the record length and has nothing to pad. Pinning that
        //       asymmetry here means a pad added to it, or dropped from one of the other six, fails.
        assertThat(padFieldCount)
                .as("band %s pad field count", expectation.copybookName())
                .isEqualTo(expectation.spec() == ReportBandLayouts.TRANSACTION_HEADER_2 ? 0 : 1);
    }

    /**
     * Holds every band to the number of FILLER items its copybook section declares.
     *
     * <p>The distribution is asserted per band as well as in total, because a corpus total of twenty-two
     * would also be satisfied by twenty-two FILLER items distributed wrongly, and it is the distribution
     * that decides which literals each band prints.</p>
     *
     * @param expectation the band under test together with the figures it is held to
     */
    @ParameterizedTest
    @MethodSource("declaredBands")
    void everyBandModelsTheFillerItemsItsCopybookSectionDeclares(BandExpectation expectation) {
        // WHAT: how many of the band's fields are modelled FILLER items.
        // WHY : Assumptions: the expected distribution is 1, 8, 7, 0, 2, 2 and 2 across lines 4 to 66 of
        //       app/cpy/CVTRA07Y.cpy. The separator rule's zero is load-bearing: its hyphen rule is
        //       invariant content just as the dot leaders are, so counting it as a FILLER is tempting
        //       and would put the corpus total at twenty-three and the distribution out by one.
        assertThat(fillerNamesOf(expectation.spec()))
                .as("band %s, lines %d to %d of app/cpy/CVTRA07Y.cpy",
                        expectation.copybookName(), expectation.firstLine(), expectation.lastLine())
                .hasSize(expectation.fillerCount());
    }

    /**
     * Holds every band's field list to rejecting mutation.
     *
     * <p>The seven descriptors are static state shared by every request that emits a report. A mutable
     * field list would let one caller's edit change the geometry every later emission uses, and the
     * symptom would be a report whose columns moved rather than any kind of failure.</p>
     *
     * @param expectation the band under test together with the figures it is held to
     */
    @ParameterizedTest
    @MethodSource("declaredBands")
    void everyBandsFieldListRejectsMutation(BandExpectation expectation) {
        List<CopybookLayout.FieldSpec> fields = expectation.spec().fields();
        CopybookLayout.FieldSpec probe = CopybookLayout.text("MUTATION-PROBE", 0, 1);

        // WHAT: an append onto the published field list is refused.
        // WHY : Alternatives Considered: immutability is asserted by ATTEMPTING the mutation rather than
        //       by testing the runtime type of the list. CopybookLayout.RecordSpec takes a List.copyOf
        //       of the field list in its constructor, so the guarantee is real; a type check would
        //       nonetheless pass for any implementation that merely looked immutable, and it would also
        //       break on a future list implementation that is immutable under a different type. The
        //       attempt tests the property itself.
        assertThatThrownBy(() -> fields.add(probe))
                .as("band %s must publish an unmodifiable field list", expectation.copybookName())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(fields::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(expectation.spec().fields()).hasSameSizeAs(fields);
    }

    /**
     * Holds every band to encoding as exactly 133 bytes with no unwritten byte left behind.
     *
     * <p>This closes the loop between the declared geometry and the bytes a report is actually made of.
     * A band that satisfied every offset assertion above could still emit a record of the wrong length,
     * or leave a byte at zero rather than blank, and neither would be visible in a descriptor.</p>
     *
     * @param expectation the band under test together with the complete value map to encode
     */
    @ParameterizedTest
    @MethodSource("declaredBands")
    void everyBandEncodesToExactlyTheDeclaredRecordLength(BandExpectation expectation) {
        String encoded = encodeToText(expectation);

        // WHAT: the encoded record is exactly the declared record length.
        // WHY : Assumptions: every band is encoded from a COMPLETE value map here. These seven are
        //       declared in ReportBandLayouts and are deliberately absent from the CopybookLayout
        //       registry, so the codec rebuilds nothing and demands a value for every declared field
        //       including the pad; an incomplete map would fail naming the missing field rather than
        //       silently producing a short record.
        assertThat(encoded)
                .as("band %s encoded from a complete value map", expectation.copybookName())
                .hasSize(DECLARED_RECORD_LENGTH);

        // WHAT: no byte of the encoded record is left at zero.
        // WHY : Assumptions: FixedWidthCodec allocates the record as a fresh array, whose bytes start at
        //       0x00, and 0x00 is NOT the 0x20 of a blank -- the reference reader distinguishes a low
        //       value from a space even though both look empty in a Java string. Asserting the absence
        //       of a zero byte is what proves the blank fill and the trailing pad were both actually
        //       written rather than merely reserved.
        assertThat(encoded).doesNotContain("\u0000");
    }

    /**
     * Holds every band to declaring no sensitive field and no normalised timestamp.
     *
     * <p>Neither flag applies to this copybook: no band carries a primary account number or a card
     * verification value, and no band carries a run-generated timestamp. The absence is asserted rather
     * than assumed so that a flag set by a later edit, which would change what diagnostics may print or
     * what a parity comparison blanks, fails here.</p>
     *
     * @param expectation the band under test together with the figures it is held to
     */
    @ParameterizedTest
    @MethodSource("declaredBands")
    void everyBandDeclaresNoSensitiveFieldAndNoNormalisedTimestamp(BandExpectation expectation) {
        for (CopybookLayout.FieldSpec field : expectation.spec().fields()) {
            // WHAT: neither the sensitivity flag nor the timestamp-normalisation flag is set.
            // WHY : Alternatives Considered: app/cpy/CVTRA07Y.cpy declares a transaction identifier, an
            //       account identifier, two codes, two descriptions, a source and an amount, and no card
            //       number and no timestamp anywhere. Marking a field sensitive would not mask it in any
            //       case, since the flag only marks, and narrowing what the report prints would break
            //       the byte comparison the parity oracle performs.
            assertThat(field.sensitive())
                    .as("band %s field %s", expectation.copybookName(), field.name())
                    .isFalse();
            assertThat(field.normalizeTs()).isFalse();
        }
    }

    /**
     * Holds the corpus to twenty-two FILLER items, every one modelled and every one seeded.
     *
     * <p>Twenty-two is a counted figure, not an estimate: the count was taken item by item from
     * {@code app/cpy/CVTRA07Y.cpy} read in full, and all twenty-two carry a {@code VALUE} clause, so the
     * proportion is not most of them but all of them.</p>
     */
    @Test
    void theCopybookDeclaresTwentyTwoFillerItemsAndAllOfThemAreModelled() {
        List<String> modelled = new ArrayList<>();
        for (CopybookLayout.RecordSpec band : ReportBandLayouts.allBands()) {
            modelled.addAll(fillerNamesOf(band));
        }

        // WHAT: the corpus holds exactly twenty-two FILLER items and no name among them repeats.
        // WHY : Assumptions: the codec resolves values BY FIELD NAME and a map holds one value per key,
        //       so two FILLER items sharing a name inside one band would leave one of them unreachable
        //       and the band uncomposable. Asserting distinctness across the corpus is what makes the
        //       merged template view below lossless as well.
        assertThat(modelled)
                .as("all 22 FILLER items declared across lines 4 to 66 of app/cpy/CVTRA07Y.cpy")
                .hasSize(CORPUS_FILLER_COUNT)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(CORPUS_FILLER_NAMES);

        // WHAT: the two FILLER populations partition the twenty-two names exactly.
        // WHY : Alternatives Considered: fifteen carry a literal and seven are declared VALUE SPACES.
        //       The partition is asserted over the NAMES rather than by adding 15 and 7, because a sum
        //       of two constants is folded at compile time and can never fail; only comparing the united
        //       names against the corpus list catches an item that belongs to neither population or to
        //       both.
        assertThat(CONTENT_BEARING_FILLER_LITERALS).hasSize(CONTENT_BEARING_FILLER_COUNT);
        assertThat(BLANK_FILLER_NAMES).hasSize(BLANK_FILLER_COUNT);
        List<String> partition = new ArrayList<>(CONTENT_BEARING_FILLER_LITERALS.keySet());
        partition.addAll(BLANK_FILLER_NAMES);
        assertThat(partition)
                .hasSize(CORPUS_FILLER_COUNT)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(CORPUS_FILLER_NAMES);

        // WHAT: every one of the twenty-two is seeded by the band template that owns it.
        // WHY : Assumptions: a FILLER modelled in a descriptor but absent from its template is not
        //       merely undocumented, it is unencodable -- FixedWidthCodec.encodeRecord demands a value
        //       for every declared field of a layout the CopybookLayout registry does not hold.
        //       Asserting presence in the merged template view is therefore the assertion that the item
        //       is emitted rather than only described.
        assertThat(allSeededValues().keySet()).containsAll(CORPUS_FILLER_NAMES);
    }

    /**
     * Holds each of the fifteen content-bearing FILLER items to its exact literal.
     *
     * <p>These are not padding. A {@code FILLER} carrying a {@code VALUE} is content, and in every one
     * of these fifteen cases the literal IS the printed report text: the column headings, the joiners
     * inside a code-and-description pair, the dot leaders that carry the eye across to a total, and the
     * joiner between the two dates of the reporting window.</p>
     */
    @Test
    void everyContentBearingFillerIsSeededWithItsExactLiteral() {
        Map<String, Object> seeded = allSeededValues();

        for (Map.Entry<String, String> expected : CONTENT_BEARING_FILLER_LITERALS.entrySet()) {
            // WHAT: the seeded value equals the copybook literal character for character.
            // WHY : Assumptions: line 362 of app/cbl/CBTRN03C.cbl opens the detail paragraph with
            //       INITIALIZE, and the language's INITIALIZE verb SKIPS FILLER, so in the reference
            //       these literals survive untouched in a record area that persists and never need
            //       restating. A Java band is assembled into a freshly allocated array with nothing to
            //       survive, so each literal has to be emitted explicitly and blanking any one of them
            //       would delete printed text rather than remove spacing.
            assertThat(seeded.get(expected.getKey()))
                    .as("FILLER at app/cpy/CVTRA07Y.cpy line %s carries printed content",
                            expected.getKey().substring("FILLER-L".length()))
                    .isEqualTo(expected.getValue());
        }
    }

    /**
     * Holds each of the seven blank-valued FILLER items to being seeded as declared blank content.
     *
     * <p>These seven are blank BY DECLARATION and not by omission, which is why they are asserted apart
     * from the fifteen that carry a literal. An empty seeded value is expanded to a full field of blanks
     * by the codec, which the record-level assertion that no encoded byte is left at zero confirms.</p>
     */
    @Test
    void everyBlankValuedFillerIsSeededAsDeclaredBlankContent() {
        Map<String, Object> seeded = allSeededValues();

        for (String blankFiller : BLANK_FILLER_NAMES) {
            // WHAT: the item is present in its template and its seeded content is blank.
            // WHY : Alternatives Considered: lines 17, 19, 23, 27, 29, 31 and 44 of app/cpy/CVTRA07Y.cpy
            //       declare VALUE SPACES, so blank is the declared content. Presence is asserted apart
            //       from blankness because the two failures differ: an ABSENT key makes the band
            //       unencodable and fails loudly, whereas a key holding the wrong literal would encode a
            //       stray character into a column separator and fail only a byte comparison.
            assertThat(seeded)
                    .as("FILLER at app/cpy/CVTRA07Y.cpy line %s is declared VALUE SPACES",
                            blankFiller.substring("FILLER-L".length()))
                    .containsKey(blankFiller);
            assertThat(seeded.get(blankFiller)).isEqualTo("");
        }
    }

    /**
     * Holds the unparenthesised picture at copybook line 44 to a width of one byte.
     *
     * <p>Line 44 declares {@code FILLER PIC X VALUE SPACES} with no repeat count, which is one byte and
     * not an unspecified width. It is the item most easily read past in the whole copybook, and it is
     * load-bearing.</p>
     */
    @Test
    void theUnparenthesisedPictureAtLine44IsOneByteWideAndClosesTheHeadings() {
        CopybookLayout.FieldSpec bareItem =
                ReportBandLayouts.TRANSACTION_HEADER_1.field("FILLER-L44");

        // WHAT: the item declared with a bare picture is one byte wide.
        // WHY : Assumptions: a tokeniser that requires the X(n) form skips line 44 of
        //       app/cpy/CVTRA07Y.cpy entirely, and the failure is silent rather than loud -- the band
        //       still sums to something, so only the width sum and this assertion catch it.
        assertThat(bareItem.length())
                .as("app/cpy/CVTRA07Y.cpy line 44 declares FILLER PIC X with no repeat count")
                .isEqualTo(EXPECTED_BARE_PICTURE_WIDTH);

        // WHAT: this one byte closes the preceding headings exactly at the amount column.
        // WHY : Assumptions: 17 + 12 + 19 + 35 + 14 is 97, so this byte occupies zero-based offset 97
        //       and the amount heading opens at 98. Dropping it would pull the amount heading one column
        //       left of the amounts beneath it, which is the concrete consequence of treating it as
        //       absent.
        assertThat(bareItem.start()).isEqualTo(AMOUNT_ZERO_BASED_START);
        assertThat(ReportBandLayouts.TRANSACTION_HEADER_1.field("FILLER-L45").start())
                .isEqualTo(AMOUNT_ZERO_BASED_START + EXPECTED_BARE_PICTURE_WIDTH);
    }

    /**
     * Holds both hyphen joiner bytes to being declared single-byte content in their own right.
     *
     * <p>Lines 21 and 25 each declare {@code FILLER PIC X(01) VALUE '-'}. They render a code and its
     * description as {@code NN-Description}, so they are data. Neither is a sign position: the amount's
     * sign belongs to that item's own edit mask at line 30.</p>
     */
    @Test
    void bothHyphenJoinerBytesAreDeclaredAsSingleByteContent() {
        CopybookLayout.RecordSpec detail = ReportBandLayouts.TRANSACTION_DETAIL_REPORT;
        Map<String, Object> seeded = ReportBandLayouts.detailTemplate();

        // WHAT: the joiner after the type code is one byte of hyphen content at offset 31.
        // WHY : Assumptions: line 20 declares TRAN-REPORT-TYPE-CD PIC X(02) ending at zero-based 31, so
        //       line 21's joiner sits immediately after it and immediately before the description at 32.
        //       Treating it as padding and blanking it would print a code and a description separated by
        //       a space instead of joined, changing the byte stream on every detail line of the report.
        assertThat(detail.field("FILLER-L21").length()).isEqualTo(1);
        assertThat(detail.field("FILLER-L21").start()).isEqualTo(31);
        assertThat(seeded.get("FILLER-L21")).isEqualTo("-");

        // WHAT: the joiner after the category code is one byte of hyphen content at offset 52.
        // WHY : Alternatives Considered: line 24 declares TRAN-REPORT-CAT-CD PIC 9(04) ending at
        //       zero-based 52, so line 25's joiner binds the category code to its description exactly as
        //       line 21 binds the type code. Both are asserted because a single shared assertion would
        //       pass an implementation that emitted one joiner and blanked the other.
        assertThat(detail.field("FILLER-L25").length()).isEqualTo(1);
        assertThat(detail.field("FILLER-L25").start()).isEqualTo(52);
        assertThat(seeded.get("FILLER-L25")).isEqualTo("-");
    }

    /**
     * Holds the separator rule to 133 bytes of nothing but the ASCII hyphen-minus character.
     *
     * <p>Line 48 declares {@code 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}, an elementary
     * level-01 item. This is the only explicit 133 in the whole copybook and the only band that is
     * natively the record length.</p>
     */
    @Test
    void theSeparatorRuleIsOneHundredThirtyThreeAsciiHyphenMinusBytes() {
        // WHAT: the rule is exactly the record length and every character is the hyphen-minus.
        // WHY : Assumptions: the character is U+002D HYPHEN-MINUS specifically, not a dash of any other
        //       kind. A non-ASCII dash would still read as a rule on screen and would still be 133
        //       characters, but it is not a single byte in the target character set, so the encode would
        //       fail its reversibility check or emit the wrong byte count.
        assertThat(ReportBandLayouts.SEPARATOR_RULE)
                .as("app/cpy/CVTRA07Y.cpy line 48 declares PIC X(133) VALUE ALL '-'")
                .hasSize(DECLARED_RECORD_LENGTH)
                .isEqualTo("-".repeat(DECLARED_RECORD_LENGTH));
        assertThat(distinctCharacterCodesOf(ReportBandLayouts.SEPARATOR_RULE))
                .containsExactly('-');

        // WHAT: the band declares one field spanning all 133 bytes and it is named for the band.
        // WHY : Alternatives Considered: line 48 of app/cpy/CVTRA07Y.cpy declares an elementary level-01
        //       item, which has no subordinates, so the faithful reading is one field of 133 bytes whose
        //       name IS the band's name. Modelling it as a group with one child would invent a level the
        //       copybook does not have.
        assertThat(ReportBandLayouts.TRANSACTION_HEADER_2.fields()).hasSize(1);
        assertThat(ReportBandLayouts.FIELD_SEPARATOR_RULE).isEqualTo("TRANSACTION-HEADER-2");
        assertThat(ReportBandLayouts.TRANSACTION_HEADER_2
                .field(ReportBandLayouts.FIELD_SEPARATOR_RULE).length())
                .isEqualTo(SEPARATOR_RULE_NATIVE_WIDTH);
    }

    /**
     * Holds the page total dot leader to its own declared width of 86 bytes.
     *
     * <p>Declared at copybook line 53. This is the first of three deliberately SEPARATE leader
     * assertions.</p>
     */
    @Test
    void thePageTotalDotLeaderIsDeclaredEightySixBytesWide() {
        // WHAT: the page total leader is 86 bytes and sits immediately after an 11-byte label.
        // WHY : Assumptions: this is asserted apart from its two siblings on purpose. A single assertion
        //       over one shared width would be satisfied by an implementation that had unified the three
        //       leaders, and that unification is the single most likely simplification anyone makes here
        //       because two of the three widths genuinely are 86.
        assertThat(ReportBandLayouts.PAGE_TOTAL_LEADER_WIDTH)
                .as("app/cpy/CVTRA07Y.cpy line 53 declares PIC X(86) VALUE ALL '.'")
                .isEqualTo(EXPECTED_PAGE_LEADER_WIDTH);
        assertThat(ReportBandLayouts.REPORT_PAGE_TOTALS.field("FILLER-L53").length())
                .isEqualTo(EXPECTED_PAGE_LEADER_WIDTH);
        assertThat(ReportBandLayouts.REPORT_PAGE_TOTALS.field("FILLER-L53").start())
                .isEqualTo(EXPECTED_PAGE_LABEL_WIDTH);
    }

    /**
     * Holds the card-break total dot leader to its own declared width of 84 bytes.
     *
     * <p>Declared at copybook line 59. This is the leader that differs, and it is the one an
     * implementation that unified the three would get wrong.</p>
     */
    @Test
    void theCardBreakTotalDotLeaderIsDeclaredEightyFourBytesWide() {
        // WHAT: the card-break total leader is 84 bytes, two shorter than its two siblings.
        // WHY : Assumptions: it is shorter by exactly the two bytes its longer label consumes -- the
        //       label is 13 where the other two are 11. Unifying the three leaders at 86 would put this
        //       band's label and leader at 13 + 86 = 99, moving this one amount two columns right of the
        //       other three; the report would still look like a report, so a smoke test would pass and
        //       only a byte comparison against the golden master would find it.
        assertThat(ReportBandLayouts.ACCOUNT_TOTAL_LEADER_WIDTH)
                .as("app/cpy/CVTRA07Y.cpy line 59 declares PIC X(84) VALUE ALL '.'")
                .isEqualTo(EXPECTED_ACCOUNT_LEADER_WIDTH);
        assertThat(ReportBandLayouts.REPORT_ACCOUNT_TOTALS.field("FILLER-L59").length())
                .isEqualTo(EXPECTED_ACCOUNT_LEADER_WIDTH);
        assertThat(ReportBandLayouts.REPORT_ACCOUNT_TOTALS.field("FILLER-L59").start())
                .isEqualTo(EXPECTED_ACCOUNT_LABEL_WIDTH);
    }

    /**
     * Holds the grand total dot leader to its own declared width of 86 bytes.
     *
     * <p>Declared at copybook line 65. It agrees with the page total leader because their two labels
     * happen to be declared the same width, not because any rule is shared between them.</p>
     */
    @Test
    void theGrandTotalDotLeaderIsDeclaredEightySixBytesWide() {
        // WHAT: the grand total leader is 86 bytes and sits immediately after an 11-byte label.
        // WHY : Alternatives Considered: its agreement with the page total leader is a consequence of
        //       two label literals happening to be declared 11 bytes wide. Deriving this width from the
        //       page total's would encode that coincidence as a rule, and one edit to either label would
        //       then move both amounts.
        assertThat(ReportBandLayouts.GRAND_TOTAL_LEADER_WIDTH)
                .as("app/cpy/CVTRA07Y.cpy line 65 declares PIC X(86) VALUE ALL '.'")
                .isEqualTo(EXPECTED_GRAND_LEADER_WIDTH);
        assertThat(ReportBandLayouts.REPORT_GRAND_TOTALS.field("FILLER-L65").length())
                .isEqualTo(EXPECTED_GRAND_LEADER_WIDTH);
        assertThat(ReportBandLayouts.REPORT_GRAND_TOTALS.field("FILLER-L65").start())
                .isEqualTo(EXPECTED_GRAND_LABEL_WIDTH);
    }

    /**
     * Holds the three label-and-leader pairs to compensating onto one shared amount column.
     *
     * <p>This is the arithmetic the three separate leader widths exist to satisfy: 11 plus 86, 13 plus
     * 84, and 11 plus 86 all reach 97, so all three amounts open at one-based column 98.</p>
     */
    @Test
    void theThreeLeaderWidthsCompensateTheirLabelsOntoOneAmountColumn() {
        // WHAT: each label-and-leader pair the subject PUBLISHES reaches the same offset of 97.
        // WHY : Alternatives Considered: the three sums are read from the published constants and not
        //       from this class's own expectations. Summing the expectations would be summing two
        //       compile-time literals, which the compiler folds into a constant that can never disagree,
        //       and a trial mutation of ACCOUNT_TOTAL_LEADER_WIDTH from 84 to 86 confirmed such a test
        //       does not fail. They are also anchored to 97 individually rather than compared to each
        //       other, because equality among the three would still hold if all three were wrong by the
        //       same amount.
        assertThat(ReportBandLayouts.PAGE_TOTAL_LABEL_WIDTH
                + ReportBandLayouts.PAGE_TOTAL_LEADER_WIDTH)
                .as("app/cpy/CVTRA07Y.cpy lines 51 and 53 declare 11 and 86")
                .isEqualTo(AMOUNT_ZERO_BASED_START);
        assertThat(ReportBandLayouts.ACCOUNT_TOTAL_LABEL_WIDTH
                + ReportBandLayouts.ACCOUNT_TOTAL_LEADER_WIDTH)
                .as("app/cpy/CVTRA07Y.cpy lines 57 and 59 declare 13 and 84")
                .isEqualTo(AMOUNT_ZERO_BASED_START);
        assertThat(ReportBandLayouts.GRAND_TOTAL_LABEL_WIDTH
                + ReportBandLayouts.GRAND_TOTAL_LEADER_WIDTH)
                .as("app/cpy/CVTRA07Y.cpy lines 63 and 65 declare 11 and 86")
                .isEqualTo(AMOUNT_ZERO_BASED_START);

        // WHAT: the expectations this class carries agree with the copybook arithmetic themselves.
        // WHY : Assumptions: 11 + 86, 13 + 84 and 11 + 86 all reach 97 as read off lines 51 to 65 of
        //       app/cpy/CVTRA07Y.cpy. These three are folded constants and cannot fail, so they are
        //       stated as the arithmetic RECORD that the assertions above are checked against rather
        //       than as assertions in their own right, and the subject-facing checks sit above them.
        assertThat(EXPECTED_PAGE_LABEL_WIDTH + EXPECTED_PAGE_LEADER_WIDTH)
                .isEqualTo(AMOUNT_ZERO_BASED_START);
        assertThat(EXPECTED_ACCOUNT_LABEL_WIDTH + EXPECTED_ACCOUNT_LEADER_WIDTH)
                .isEqualTo(AMOUNT_ZERO_BASED_START);
        assertThat(EXPECTED_GRAND_LABEL_WIDTH + EXPECTED_GRAND_LEADER_WIDTH)
                .isEqualTo(AMOUNT_ZERO_BASED_START);

        // WHAT: the label widths themselves are the declared 11, 13 and 11.
        // WHY : Assumptions: the compensation is only meaningful if the labels are right too. A pair of
        //       12 and 85 would also sum to 97 and would place the amount correctly while printing the
        //       label and the leader at the wrong widths, which a sum-only assertion cannot see.
        assertThat(ReportBandLayouts.PAGE_TOTAL_LABEL_WIDTH).isEqualTo(EXPECTED_PAGE_LABEL_WIDTH);
        assertThat(ReportBandLayouts.ACCOUNT_TOTAL_LABEL_WIDTH)
                .isEqualTo(EXPECTED_ACCOUNT_LABEL_WIDTH);
        assertThat(ReportBandLayouts.GRAND_TOTAL_LABEL_WIDTH).isEqualTo(EXPECTED_GRAND_LABEL_WIDTH);
    }

    /**
     * Holds every dot leader to being built from the ASCII full stop and nothing else.
     *
     * <p>Lines 53, 59 and 65 each declare {@code VALUE ALL '.'}, which is U+002E FULL STOP. No other dot
     * glyph is admissible: another would still read as a leader on screen while not being a single byte
     * in the target character set.</p>
     */
    @Test
    void everyDotLeaderIsBuiltFromAsciiFullStops() {
        Map<String, Object> seeded = allSeededValues();

        // WHAT: each leader is its declared width of full stops and holds no other character.
        // WHY : Alternatives Considered: the leaders are the widest invariant literals in the copybook
        //       at 86, 84 and 86 bytes, which makes them the likeliest place for a single stray
        //       character to hide. A width assertion alone would not see one substituted character, and
        //       a content assertion alone would not see a wrong width, so both are made on each of the
        //       three.
        assertThat(seeded.get("FILLER-L53"))
                .as("app/cpy/CVTRA07Y.cpy line 53 declares VALUE ALL '.' over 86 bytes")
                .isEqualTo(".".repeat(EXPECTED_PAGE_LEADER_WIDTH));
        assertThat(seeded.get("FILLER-L59"))
                .as("app/cpy/CVTRA07Y.cpy line 59 declares VALUE ALL '.' over 84 bytes")
                .isEqualTo(".".repeat(EXPECTED_ACCOUNT_LEADER_WIDTH));
        assertThat(seeded.get("FILLER-L65"))
                .as("app/cpy/CVTRA07Y.cpy line 65 declares VALUE ALL '.' over 86 bytes")
                .isEqualTo(".".repeat(EXPECTED_GRAND_LEADER_WIDTH));
        assertThat(distinctCharacterCodesOf((String) seeded.get("FILLER-L53")))
                .containsExactly('.');
        assertThat(distinctCharacterCodesOf((String) seeded.get("FILLER-L59")))
                .containsExactly('.');
        assertThat(distinctCharacterCodesOf((String) seeded.get("FILLER-L65")))
                .containsExactly('.');
    }

    /**
     * Holds the three total labels to their literals and to fitting inside their declared widths.
     *
     * <p>Two of the three fill their field exactly, which is why the widths cannot be inferred from the
     * literals and both have to be asserted.</p>
     */
    @Test
    void theThreeTotalLabelsAreCarriedByteExactWithinTheirDeclaredWidths() {
        // WHAT: the page total label and the one byte its 11-wide field leaves spare.
        // WHY : Alternatives Considered: 'Page Total' is ten characters in a field of eleven, so exactly
        //       one trailing blank is field width rather than literal. Asserting the literal AND its
        //       declared width separately is what distinguishes a literal that lost a character from a
        //       field that lost a byte, which look identical in the printed output.
        assertThat(ReportBandLayouts.PAGE_TOTAL_LABEL)
                .as("app/cpy/CVTRA07Y.cpy lines 51 and 52")
                .isEqualTo("Page Total")
                .hasSize(EXPECTED_PAGE_LABEL_WIDTH - 1);

        // WHAT: the card-break total label exactly fills its 13-wide field.
        // WHY : Assumptions: 'Account Total' is thirteen characters in a field of thirteen, an EXACT fit
        //       with no spare byte, and that exact fit is why this band's label is two bytes wider than
        //       its siblings' and its leader two bytes shorter. The label wording is carried across
        //       unchanged under AAP Rule T8 even though the roll-up it closes breaks on card number
        //       rather than account; the baseline labels it this way, the Java carries the same label,
        //       and the divergence is documented rather than resolved here.
        assertThat(ReportBandLayouts.ACCOUNT_TOTAL_LABEL)
                .as("app/cpy/CVTRA07Y.cpy lines 57 and 58")
                .isEqualTo("Account Total")
                .hasSize(EXPECTED_ACCOUNT_LABEL_WIDTH);

        // WHAT: the grand total label exactly fills its 11-wide field.
        // WHY : Assumptions: 'Grand Total' is eleven characters in a field of eleven, another exact fit.
        //       It shares a width with the page total label while being one character longer, so a
        //       reader cannot infer either width from either literal.
        assertThat(ReportBandLayouts.GRAND_TOTAL_LABEL)
                .as("app/cpy/CVTRA07Y.cpy lines 63 and 64")
                .isEqualTo("Grand Total")
                .hasSize(EXPECTED_GRAND_LABEL_WIDTH);
    }

    /**
     * Holds the title band's four literals to being carried character for character.
     *
     * <p>Two of the four carry significant whitespace. The date-range label ends in a space that is part
     * of the literal, and the date joiner carries a space at BOTH ends.</p>
     */
    @Test
    void theTitleBandLiteralsAreCarriedByteExact() {
        // WHAT: the short and long report names.
        // WHY : Assumptions: lines 5 to 8 of app/cpy/CVTRA07Y.cpy declare these in fields of 38 and 41
        //       bytes, so both are far shorter than their fields and the trailing blanks are field width
        //       rather than literal. AAP Rule T8 carries user-visible strings across character for
        //       character, so neither is re-cased or abbreviated.
        assertThat(ReportBandLayouts.REPORT_SHORT_NAME)
                .as("app/cpy/CVTRA07Y.cpy lines 5 and 6").isEqualTo("DALYREPT");
        assertThat(ReportBandLayouts.REPORT_LONG_NAME)
                .as("app/cpy/CVTRA07Y.cpy lines 7 and 8").isEqualTo("Daily Transaction Report");

        // WHAT: the date-range label keeps the trailing space that exactly fills its 12-byte field.
        // WHY : Assumptions: 'Date Range: ' is twelve characters in a PIC X(12) field, so the trailing
        //       space is the twelfth character of the literal and not padding the codec supplies.
        //       Trimming it would close up the gap before the start date printed immediately after it.
        assertThat(ReportBandLayouts.DATE_RANGE_LABEL)
                .as("app/cpy/CVTRA07Y.cpy lines 9 and 10")
                .isEqualTo("Date Range: ")
                .hasSize(12)
                .endsWith(" ");

        // WHAT: the date joiner keeps BOTH its leading and its trailing space.
        // WHY : Assumptions: line 12 of app/cpy/CVTRA07Y.cpy declares PIC X(04) VALUE ' to ', so all
        //       four characters are literal and the field is exactly filled. Losing either space runs
        //       the joiner into one of the two dates it separates, and a trim of one end is the likelier
        //       accident because only one end reads as obviously significant.
        assertThat(ReportBandLayouts.DATE_RANGE_JOINER)
                .as("app/cpy/CVTRA07Y.cpy line 12 declares PIC X(04) VALUE ' to '")
                .isEqualTo(" to ")
                .hasSize(4)
                .startsWith(" ")
                .endsWith(" ");
        assertThat(ReportBandLayouts.REPORT_NAME_HEADER.field("FILLER-L12").length()).isEqualTo(4);
    }

    /**
     * Holds the amount heading to its eight leading spaces and to the alignment they exist to produce.
     *
     * <p>The eight spaces are not decorative. They are what right-aligns the visible word over the
     * right-hand end of the amount column beneath it, where a right-justified money value actually
     * prints.</p>
     */
    @Test
    void theAmountHeadingKeepsItsEightLeadingSpaces() {
        String heading = ReportBandLayouts.COLUMN_LABEL_AMOUNT;

        // WHAT: the literal opens with eight spaces and is fourteen characters in a 16-byte field.
        // WHY : Assumptions: line 46 of app/cpy/CVTRA07Y.cpy carries the eight spaces inside the PIC
        //       X(16) declared on line 45. The COUNT is asserted as a number as well as through the
        //       literal, because a whitespace run is the one thing an editor silently reflows and a
        //       literal comparison alone reports only that two strings differ, leaving a reader to count
        //       spaces in a diff to find out how.
        assertThat(heading)
                .as("app/cpy/CVTRA07Y.cpy lines 45 and 46 declare PIC X(16) opening with eight spaces")
                .isEqualTo("        Amount")
                .hasSize(14)
                .startsWith(" ".repeat(EXPECTED_AMOUNT_HEADING_LEADING_SPACES));
        assertThat(heading.length() - heading.stripLeading().length())
                .isEqualTo(EXPECTED_AMOUNT_HEADING_LEADING_SPACES);

        // WHAT: those eight spaces close the visible word on the amount column's last column.
        // WHY : Assumptions: the heading field opens at zero-based 98, so eight spaces put the word's
        //       first byte at 106 and its exclusive end at 112, which is one-based columns 107 to 112 --
        //       closing on the same column 112 the amount value closes on. That right-alignment is what
        //       the eight spaces are FOR, and it is the reason the count is eight rather than any other
        //       number. Left-trimming the literal would move the heading to the column's left edge, away
        //       from the right-justified money printed beneath it.
        int headingStart = ReportBandLayouts.TRANSACTION_HEADER_1.field("FILLER-L45").start();
        assertThat(headingStart + heading.length())
                .as("the heading word must close on the amount column's last one-based column")
                .isEqualTo(AMOUNT_ONE_BASED_LAST_COLUMN);
    }

    /**
     * Holds the five remaining column headings to their literals and declared widths.
     *
     * <p>The heading widths are 17, 12, 19, 35 and 14, each sized to span the detail-band items beneath
     * it rather than the heading text itself, which is why none can be inferred from its literal.</p>
     */
    @Test
    void theFiveRemainingColumnHeadingsAreCarriedByteExactWithinTheirDeclaredWidths() {
        CopybookLayout.RecordSpec headings = ReportBandLayouts.TRANSACTION_HEADER_1;

        // WHAT: each heading literal and the width of the field that carries it.
        // WHY : Alternatives Considered: a heading field is sized to the COLUMN beneath it, not to its
        //       own text, so 'Tran Category' occupies 35 bytes because the category code, its joiner and
        //       its 29-byte description sit under it. Inferring any of these widths from its literal
        //       would shorten every one of them and shift every heading after it left.
        assertThat(ReportBandLayouts.COLUMN_LABEL_TRANSACTION_ID)
                .as("app/cpy/CVTRA07Y.cpy lines 34 and 35").isEqualTo("Transaction ID");
        assertThat(headings.field("FILLER-L34").length()).isEqualTo(17);
        assertThat(ReportBandLayouts.COLUMN_LABEL_ACCOUNT_ID)
                .as("app/cpy/CVTRA07Y.cpy lines 36 and 37").isEqualTo("Account ID");
        assertThat(headings.field("FILLER-L36").length()).isEqualTo(12);
        assertThat(ReportBandLayouts.COLUMN_LABEL_TRANSACTION_TYPE)
                .as("app/cpy/CVTRA07Y.cpy lines 38 and 39").isEqualTo("Transaction Type");
        assertThat(headings.field("FILLER-L38").length()).isEqualTo(19);
        assertThat(ReportBandLayouts.COLUMN_LABEL_TRAN_CATEGORY)
                .as("app/cpy/CVTRA07Y.cpy lines 40 and 41").isEqualTo("Tran Category");
        assertThat(headings.field("FILLER-L40").length()).isEqualTo(35);
        assertThat(ReportBandLayouts.COLUMN_LABEL_TRAN_SOURCE)
                .as("app/cpy/CVTRA07Y.cpy lines 42 and 43").isEqualTo("Tran Source");
        assertThat(headings.field("FILLER-L42").length()).isEqualTo(14);

        // WHAT: the amount heading field is 16 bytes wide.
        // WHY : Assumptions: lines 45 and 46 declare PIC X(16) around a 14-character literal, leaving
        //       two trailing blanks. The width is asserted here beside its four siblings so that all six
        //       heading widths -- 17, 12, 19, 35, 14 and 16 -- can be read against one another, and the
        //       one-byte item of line 44 between the fifth and the sixth has its own test.
        assertThat(headings.field("FILLER-L45").length()).isEqualTo(16);
    }

    /**
     * Holds the detail band's two descriptions to their one-based column spans.
     *
     * <p>Both spans are load-bearing downstream, because a description longer than its span is truncated
     * to fit and the truncation point is the span's end.</p>
     */
    @Test
    void theDetailBandPlacesItsDescriptionsInTheirOneBasedColumnSpans() {
        CopybookLayout.RecordSpec detail = ReportBandLayouts.TRANSACTION_DETAIL_REPORT;
        CopybookLayout.FieldSpec typeDescription =
                detail.field(ReportBandLayouts.FIELD_TRAN_REPORT_TYPE_DESC);
        CopybookLayout.FieldSpec categoryDescription =
                detail.field(ReportBandLayouts.FIELD_TRAN_REPORT_CAT_DESC);

        // WHAT: the type description occupies one-based columns 33 to 47.
        // WHY : Assumptions: the conversion between the two numbering systems runs one way only,
        //       zeroBased = oneBased - 1, so a start of 32 IS column 33 and an EXCLUSIVE end of 47 IS
        //       the last occupied column 47. Applied backwards the whole band shifts one byte, and a
        //       band shifted one byte still prints readable text, so nothing raises and only a byte
        //       comparison finds it.
        assertThat(typeDescription.start())
                .as("app/cpy/CVTRA07Y.cpy line 22 declares PIC X(15) at one-based column 33")
                .isEqualTo(33 - 1);
        assertThat(typeDescription.length()).isEqualTo(15);
        assertThat(typeDescription.end()).isEqualTo(47);

        // WHAT: the category description occupies one-based columns 54 to 82.
        // WHY : Assumptions: line 26 declares the widest description in the band at 29 bytes, opening
        //       immediately after the second hyphen joiner at zero-based 52. Its end at an exclusive 82
        //       is where a longer description is truncated, which is why the span is pinned rather than
        //       only the width.
        assertThat(categoryDescription.start())
                .as("app/cpy/CVTRA07Y.cpy line 26 declares PIC X(29) at one-based column 54")
                .isEqualTo(54 - 1);
        assertThat(categoryDescription.length()).isEqualTo(29);
        assertThat(categoryDescription.end()).isEqualTo(82);
    }

    /**
     * Holds all four money-bearing bands to one shared amount column of one-based 98 to 112.
     *
     * <p>The three total bands reach the column through their compensating leaders and the detail band
     * reaches it by accumulating 97 bytes of preceding items, so the agreement is arithmetic rather than
     * declared and any width change above an amount breaks it.</p>
     */
    @Test
    void theFourMoneyBearingBandsShareOneAmountColumn() {
        List<CopybookLayout.FieldSpec> amounts = List.of(
                ReportBandLayouts.TRANSACTION_DETAIL_REPORT
                        .field(ReportBandLayouts.FIELD_TRAN_REPORT_AMT),
                ReportBandLayouts.REPORT_PAGE_TOTALS
                        .field(ReportBandLayouts.FIELD_REPT_PAGE_TOTAL),
                ReportBandLayouts.REPORT_ACCOUNT_TOTALS
                        .field(ReportBandLayouts.FIELD_REPT_ACCOUNT_TOTAL),
                ReportBandLayouts.REPORT_GRAND_TOTALS
                        .field(ReportBandLayouts.FIELD_REPT_GRAND_TOTAL));

        for (CopybookLayout.FieldSpec amount : amounts) {
            // WHAT: every amount item is fifteen bytes at one-based columns 98 to 112.
            // WHY : Assumptions: the fifteen is counted from the pictures at lines 30, 54, 60 and 66 of
            //       app/cpy/CVTRA07Y.cpy -- one sign, nine digits, two grouping separators, one decimal
            //       point and two fractional digits -- rather than taken from a formatter's width
            //       constant. Counting it here is what keeps the geometry and the rendering pinned by
            //       two tests that cannot drift into agreeing with each other.
            assertThat(amount.length())
                    .as("amount field %s", amount.name())
                    .isEqualTo(AMOUNT_MASK_WIDTH);
            assertThat(amount.start()).isEqualTo(AMOUNT_ONE_BASED_FIRST_COLUMN - 1);
            assertThat(amount.end()).isEqualTo(AMOUNT_ONE_BASED_LAST_COLUMN);

            // WHAT: the receiving field is a plain character field.
            // WHY : Alternatives Considered: an edit mask is NOT one of the five regimes
            //       CopybookLayout.Kind offers, and formatting happens BEFORE placement. Declaring an
            //       amount under a numeric regime would re-justify and re-fill an already rendered
            //       string, so the character regime is the only one that leaves the fifteen rendered
            //       bytes alone.
            assertThat(amount.kind()).isEqualTo(CopybookLayout.Kind.TEXT);
        }
    }

    /**
     * Holds the account identifier to being alphanumeric at eleven bytes.
     *
     * <p>It is the target of a zero-pad from {@code XREF-ACCT-ID PIC 9(11)} at line 7 of
     * {@code app/cpy/CVACT03Y.cpy}. Performing that pad is the mapper's business and is asserted by the
     * mapper's own test; this class asserts only the width and the regime it lands in.</p>
     */
    @Test
    void theAccountIdentifierIsAlphanumericAtElevenBytes() {
        CopybookLayout.FieldSpec accountId = ReportBandLayouts.TRANSACTION_DETAIL_REPORT
                .field(ReportBandLayouts.FIELD_TRAN_REPORT_ACCOUNT_ID);

        // WHAT: the account identifier is a character field of eleven bytes.
        // WHY : Alternatives Considered: line 18 of app/cpy/CVTRA07Y.cpy declares PIC X(11) where the
        //       category code four items later declares PIC 9(04), and that asymmetry is in the source
        //       and is load-bearing. A character field is placed left-justified and blank-filled, so a
        //       shorter value prints flush left with trailing blanks and is NOT zero-filled. Declaring
        //       this one numeric to make the two consistent would zero-pad it and change the printed
        //       bytes.
        assertThat(accountId.length())
                .as("app/cpy/CVTRA07Y.cpy line 18 declares PIC X(11)")
                .isEqualTo(11);
        assertThat(accountId.kind()).isEqualTo(CopybookLayout.Kind.TEXT);
        assertThat(accountId.start()).isEqualTo(17);
    }

    /**
     * Holds the category code to an unsigned display regime of four digit positions that keeps
     * its zeros.
     *
     * <p>{@code PIC 9(04)} at copybook line 24 is a fundamentally different regime from the
     * zero-suppressed masks the amounts use: it PRESERVES leading zeros, so category one prints as
     * {@code 0001}.</p>
     */
    @Test
    void theCategoryCodeIsUnsignedDisplayOfFourDigitPositionsAndKeepsLeadingZeros() {
        CopybookLayout.FieldSpec categoryCode = ReportBandLayouts.TRANSACTION_DETAIL_REPORT
                .field(ReportBandLayouts.FIELD_TRAN_REPORT_CAT_CD);

        // WHAT: the category code is the one numeric item of the band, at four digit positions.
        // WHY : Alternatives Considered: the unsigned display regime right-justifies and ZERO-fills,
        //       which is what PIC 9(04) means. Giving this field a zero-suppressing regime like the
        //       amounts' would blank the leading zeros and print a 1 followed by three blanks, and the
        //       joiner after it would then no longer sit against the description.
        assertThat(categoryCode.kind())
                .as("app/cpy/CVTRA07Y.cpy line 24 declares PIC 9(04)")
                .isEqualTo(CopybookLayout.Kind.UINT);
        assertThat(categoryCode.length()).isEqualTo(4);
        assertThat(categoryCode.start()).isEqualTo(48);

        String encoded = encodeToText(new BandExpectation(
                ReportBandLayouts.TRANSACTION_DETAIL_REPORT, "TRANSACTION-DETAIL-REPORT", 15, 31,
                DETAIL_NATIVE_WIDTH, DETAIL_ITEM_COUNT, DETAIL_FILLER_COUNT,
                DECLARED_RECORD_LENGTH - DETAIL_NATIVE_WIDTH, completeDetail()));

        // WHAT: category one renders as four digits with its leading zeros intact, joined to its
        //       description by the hyphen at line 25.
        // WHY : Alternatives Considered: the regime is proven through the ENCODED BYTES rather than only
        //       through the declared kind, because a kind is a label and only the encode shows what the
        //       label does. Zero-based 48 to 52 is one-based columns 49 to 52, and the joiner byte
        //       immediately after it makes the pair read as 0001- exactly as the reference renders a
        //       code and description.
        assertThat(encoded.substring(48, 53))
                .as("app/cpy/CVTRA07Y.cpy lines 24 and 25 render a zero-filled code and its joiner")
                .isEqualTo("0001-");

        // WHAT: the type code and its own joiner render the same way one column pair earlier.
        // WHY : Assumptions: line 20 declares PIC X(02) and line 21 its joiner, so zero-based 29 to 32
        //       is one-based columns 30 to 32. Asserting both code-and-joiner pairs in encoded bytes is
        //       what proves the two hyphens at lines 21 and 25 are emitted as data rather than absorbed
        //       as spacing.
        assertThat(encoded.substring(29, 32)).isEqualTo("01-");
    }

    /**
     * Holds the three total bands to rendering label, leader and amount in the columns they declare.
     *
     * <p>This is the compensation argument proven in bytes rather than in arithmetic. All three amounts
     * land in one-based columns 98 to 112 while their labels and leaders differ, which is the property a
     * unified leader width would destroy.</p>
     */
    @Test
    void theThreeTotalBandsRenderTheirAmountsInTheSameOneBasedColumnSpan() {
        assertTotalBandRendersInColumn(new BandExpectation(
                ReportBandLayouts.REPORT_PAGE_TOTALS, "REPORT-PAGE-TOTALS", 50, 54,
                PAGE_TOTAL_NATIVE_WIDTH, PAGE_TOTAL_ITEM_COUNT, PAGE_TOTAL_FILLER_COUNT,
                DECLARED_RECORD_LENGTH - PAGE_TOTAL_NATIVE_WIDTH, completePageTotals()),
                "Page Total", EXPECTED_PAGE_LABEL_WIDTH, EXPECTED_PAGE_LEADER_WIDTH);
        assertTotalBandRendersInColumn(new BandExpectation(
                ReportBandLayouts.REPORT_ACCOUNT_TOTALS, "REPORT-ACCOUNT-TOTALS", 56, 60,
                ACCOUNT_TOTAL_NATIVE_WIDTH, ACCOUNT_TOTAL_ITEM_COUNT, ACCOUNT_TOTAL_FILLER_COUNT,
                DECLARED_RECORD_LENGTH - ACCOUNT_TOTAL_NATIVE_WIDTH, completeAccountTotals()),
                "Account Total", EXPECTED_ACCOUNT_LABEL_WIDTH, EXPECTED_ACCOUNT_LEADER_WIDTH);
        assertTotalBandRendersInColumn(new BandExpectation(
                ReportBandLayouts.REPORT_GRAND_TOTALS, "REPORT-GRAND-TOTALS", 62, 66,
                GRAND_TOTAL_NATIVE_WIDTH, GRAND_TOTAL_ITEM_COUNT, GRAND_TOTAL_FILLER_COUNT,
                DECLARED_RECORD_LENGTH - GRAND_TOTAL_NATIVE_WIDTH, completeGrandTotals()),
                "Grand Total", EXPECTED_GRAND_LABEL_WIDTH, EXPECTED_GRAND_LEADER_WIDTH);
    }

    /**
     * Asserts one total band's encoded record carries its label, its leader and its amount in place.
     *
     * <p>Alternatives Considered: the three bands are asserted through one helper taking each band's own
     * label and leader widths as arguments, rather than through a loop over a shared width, so the
     * widths stay per-band while the assertion stays stated once.</p>
     *
     * @param expectation the total band and the complete value map to encode
     * @param label the label literal the band prints, taken from the copybook
     * @param labelWidth the declared width of that band's label field
     * @param leaderWidth the declared width of that band's dot leader, which differs between bands
     */
    private static void assertTotalBandRendersInColumn(BandExpectation expectation, String label,
            int labelWidth, int leaderWidth) {
        String encoded = encodeToText(expectation);

        // WHAT: the label opens the record and its field is blank-filled to its declared width.
        // WHY : Alternatives Considered: a character field is placed left-justified and blank-filled, so
        //       the label occupies the first labelWidth bytes with any spare byte blank. Comparing the
        //       whole field rather than only the literal is what catches a label that was
        //       right-justified instead.
        assertThat(encoded.substring(0, labelWidth))
                .as("band %s label field", expectation.copybookName())
                .isEqualTo(label + " ".repeat(labelWidth - label.length()));

        // WHAT: the dot leader fills every byte between the label and the amount column.
        // WHY : Assumptions: this is the compensation proven in bytes. The leader runs from labelWidth
        //       to 97 whatever labelWidth is, so 11 + 86 and 13 + 84 both close exactly on 97. A unified
        //       86-byte leader would overrun that boundary on the card-break band and this substring
        //       would come back two characters short.
        assertThat(encoded.substring(labelWidth, AMOUNT_ZERO_BASED_START))
                .as("band %s dot leader", expectation.copybookName())
                .isEqualTo(".".repeat(leaderWidth))
                .hasSize(leaderWidth);

        // WHAT: the amount occupies one-based columns 98 to 112 in every one of the three bands.
        // WHY : Assumptions: this single span holding for all three is the whole reason the leaders are
        //       86, 84 and 86 rather than one shared width, and asserting it in encoded bytes is what
        //       makes the claim checkable rather than arithmetic a reader has to redo.
        assertThat(encoded.substring(AMOUNT_ONE_BASED_FIRST_COLUMN - 1, AMOUNT_ONE_BASED_LAST_COLUMN))
                .as("band %s amount column", expectation.copybookName())
                .isEqualTo(TOTAL_AMOUNT_SPECIMEN);

        // WHAT: the trailing pad past the amount column is blank, not zero.
        // WHY : Assumptions: 133 minus 112 is 21 bytes of pad on each total band, and the pad exists
        //       only because the descriptor's geometry check is fail-closed on an exact sum. Asserting
        //       it as blanks proves the codec wrote it rather than leaving the fresh array's zero bytes,
        //       which a reference reader distinguishes from spaces even though both look empty here.
        assertThat(encoded.substring(AMOUNT_ONE_BASED_LAST_COLUMN))
                .as("band %s trailing pad", expectation.copybookName())
                .isEqualTo(" ".repeat(DECLARED_RECORD_LENGTH - AMOUNT_ONE_BASED_LAST_COLUMN));
    }

    /**
     * Holds the published band collection to the seven descriptors, in copybook order, unmodifiable.
     *
     * <p>The collection is what lets one assertion cover the whole set, so an eighth band added to the
     * class would be swept by every parameterised test above without any of them being edited.</p>
     */
    @Test
    void allBandsPublishesTheSevenDescriptorsInCopybookOrderAndRejectsMutation() {
        List<CopybookLayout.RecordSpec> bands = ReportBandLayouts.allBands();

        // WHAT: exactly seven bands, in the order app/cpy/CVTRA07Y.cpy declares them.
        // WHY : Assumptions: app/cpy/CVTRA07Y.cpy declares SEVEN level-01 groups between its lines 4 and
        //       66, and a reader or a parser that stopped at the first one would silently lose six.
        //       Pinning the count and the order here is what makes that loss a failure instead of a
        //       quietly shorter report.
        assertThat(bands)
                .as("app/cpy/CVTRA07Y.cpy declares seven level-01 items between lines 4 and 66")
                .hasSize(7)
                .containsExactly(
                        ReportBandLayouts.REPORT_NAME_HEADER,
                        ReportBandLayouts.TRANSACTION_DETAIL_REPORT,
                        ReportBandLayouts.TRANSACTION_HEADER_1,
                        ReportBandLayouts.TRANSACTION_HEADER_2,
                        ReportBandLayouts.REPORT_PAGE_TOTALS,
                        ReportBandLayouts.REPORT_ACCOUNT_TOTALS,
                        ReportBandLayouts.REPORT_GRAND_TOTALS);

        // WHAT: the published collection refuses mutation.
        // WHY : Assumptions: allBands() is static state read by every request that emits a report and is
        //       the source the eight parameterised sweeps above enumerate, so a caller able to remove
        //       one of the seven entries could change what a later emission covers. The mutation is
        //       attempted rather than the list's type inspected, for the reason recorded on the per-band
        //       immutability sweep.
        assertThatThrownBy(() -> bands.remove(0))
                .isInstanceOf(UnsupportedOperationException.class);

        // WHAT: the seven expected native width sums, read off in the same order.
        // WHY : Alternatives Considered: restating the seven sums as one ordered comparison catches a
        //       band swapped with another, which every per-band assertion above would miss because each
        //       of those is handed its own expectation alongside its own descriptor.
        List<Integer> nativeSums = new ArrayList<>();
        for (CopybookLayout.RecordSpec band : bands) {
            nativeSums.add(nativeWidthSumOf(band));
        }
        assertThat(nativeSums).containsExactly(
                NAME_HEADER_NATIVE_WIDTH, DETAIL_NATIVE_WIDTH, COLUMN_HEADER_NATIVE_WIDTH,
                SEPARATOR_RULE_NATIVE_WIDTH, PAGE_TOTAL_NATIVE_WIDTH, ACCOUNT_TOTAL_NATIVE_WIDTH,
                GRAND_TOTAL_NATIVE_WIDTH);
    }
}
