package com.carddemo.reporting.mapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Byte-exact assembly of the 100-character HTML cardholder statement, fragment by fragment.
 *
 * <p>This class is the anti-corruption boundary of the markup statement path. It is the one place
 * in that path where a declared record length, a trailing blank, a right-trim regime and a literal
 * markup byte are allowed to appear, and everything downstream of it carries values rather than
 * layouts. Nothing here reads a clock, performs input or output, rounds a monetary value or
 * consults a locale.</p>
 *
 * <h2>Assumptions: the declared record length is 100, and it is corroborated five ways</h2>
 *
 * <p>Every record this class returns is exactly {@value #HTML_RECORD_LENGTH} characters,
 * blank-padded on the right. That length is a property of the data set rather than a sum of field
 * widths, and it is anchored in five places so the number is checkable: the output record is
 * declared {@code 01 FD-HTMLFILE-REC PIC X(100).} at line 47 of {@code app/cbl/CBSTM03A.CBL}
 * under the file declaration opened at line 46; the data-set attributes at line 94 of
 * {@code app/jcl/CREASTMT.JCL} name the same 100 as the logical record length; and three further
 * {@code PIC X(100)} items corroborate it, the reusable fragment buffer at line 149 and the three
 * assembly buffers at lines 221, 222 and 223.</p>
 *
 * <p>Assumptions: the {@code LRECL=80} that appears twice in {@code app/jcl/CREASTMT.JCL}, in the
 * delete stanzas at lines 67 to 71 and 72 to 75, is inert and must not be read as the HTML length.
 * The step at line 66 invokes the null utility program, which writes no data, so those attributes
 * describe nothing that is ever written; the same step is what gives the statement job its replace
 * semantics, because both outputs are deleted there before the statement program at line 79
 * recreates them. The plain-text statement genuinely is 80, declared at line 45 of
 * {@code app/cbl/CBSTM03A.CBL} and at line 89 of the job. Two artifacts, two declared lengths.</p>
 *
 * <p><b>Assumptions: three padding situations coexist in this module and none of them generalises
 * to the others.</b> They are stated together here because a reader who carries one of them across
 * writes the wrong assembly. <b>This artifact</b>: every fragment and every cell is <em>shorter</em>
 * than the declared 100 -- the longest declared fragment is 85 characters, at line 157 of
 * {@code app/cbl/CBSTM03A.CBL} -- so every record must be blank-padded out to 100. <b>The
 * plain-text statement</b>: all seventeen {@code ST-LINE} bands are natively <em>exactly</em> 80,
 * so padding one of them would overrun the record and {@code StatementTextMapper} pads nothing.
 * <b>The daily transaction report</b>: six of its seven bands are short of the declared 133 and
 * only the separator is natively the full width, so there padding is mandatory for six bands and
 * wrong for one. Three artifacts, three obligations.</p>
 *
 * <p>Assumptions: in the reference the padding does not come from the assembly statement. Where a
 * record is written from a whole buffer the buffer is declared at the full 100 and the unused
 * suffix is blank by declaration; where a record is assembled by concatenation the blanks come
 * from a preceding blanking move, at lines 561, 569, 577 and 585 of
 * {@code app/cbl/CBSTM03A.CBL}. The concatenation statement itself pads nothing, so an assembly
 * that omitted the blanking move would leave whatever the previous record left behind. Line 561
 * is worth a second look for an unrelated reason: it carries no terminating period, which is legal
 * because the statement that follows it continues the same sentence, and a transcription that
 * treated the missing period as a defect would be reading a syntax rule as an error.</p>
 *
 * <h2>Assumptions: four buffers, two groups, and one of each is never written</h2>
 *
 * <p>The reference does not assemble this artifact from one item. It declares <b>four</b>
 * 100-character items -- the reusable fragment buffer at line 149 of
 * {@code app/cbl/CBSTM03A.CBL} and three assembly buffers at lines 221, 222 and 223, one each for
 * the address cells, the basic-detail cells and the transaction cells -- and it declares two group
 * items beside them, at lines 212 and 217. The groups are <em>not</em> among the 34 condition names:
 * counting them in would give a table of 36, which is wrong in a different way from a table of
 * 31.</p>
 *
 * <p>Alternatives Considered: modelling the reusable buffer at line 149 as mutable state on this
 * class, so that an emission sequence would select and write in the two steps the reference uses at
 * lines 508 and 509. Rejected because that buffer exists in the reference to avoid declaring 34
 * separate 100-character output items in a program with no dynamic storage, and it has no
 * counterpart obligation here; carrying it would add shared mutable state to an assembly whose whole
 * value is that two statements produce the same bytes in any order. Each of the four buffers is
 * therefore modelled as an independent record assembly, and each operation below returns whole
 * records rather than mutating one.</p>
 *
 * <p>Assumptions: the first group, at lines 212 to 216, is the account-number heading. It is 59
 * characters of content -- a 34-character literal, a 20-character account item and a 5-character
 * literal -- and it is written exactly once per statement, at line 530, fed by the move at line
 * 529.</p>
 *
 * <p><b>Assumptions: the second group, at lines 217 to 220, is never written at all.</b> It is 76
 * characters -- a 26-character styled paragraph prefix and a 50-character name item -- and it
 * declares <em>no position for a closing tag</em>, so writing it would emit an unterminated
 * paragraph. What the reference does instead is assemble the same markup by concatenation at lines
 * 562 to 567, supplying the closing tag as a literal. The group is unused; <b>its subordinate name
 * item is not</b> -- line 560 moves the assembled name into it and line 563 reads it back -- and
 * the two facts are consistent rather than contradictory: an unused group can still hold a used
 * item. Both are recorded as observations about what the reference does. The same reading applies to
 * the one declared fragment that is never selected, noted at the table below.</p>
 *
 * <p>Assumptions: this class holds no table of cards and no table of transactions, so it imposes no
 * arity of its own. The reference declares its working-storage tables at lines 225 to 233 with
 * arities of 51 cards and 10 transactions per card and performs no bound check on either; each
 * operation below emits one row or one block independently of any count, and the departure is
 * registered in {@code docs/architecture/cobol-to-service-traceability.md}. Those two figures are
 * declared arities and are not to be confused with the measured overflow thresholds the existing
 * COBOL suite records for the same program, which are a different kind of number entirely.</p>
 *
 * <p>Assumptions: two transcription hazards in the sources are worth naming once. The clause order
 * of this program is <b>value before picture</b>, as at its line 69 and throughout its bands, where
 * the report copybook of this module writes the picture first; a transcription assuming one order
 * mis-reads the other. And {@code app/cbl/CBSTM03B.CBL} is a called input-output subroutine, with
 * its linkage section at line 99 and its procedure heading at line 114, so it contributes no
 * literal, no mask and no band to this artifact and is never a source for one.</p>
 *
 * <h2>Four operations, one per reference paragraph</h2>
 *
 * <p>The markup emission of the reference is spread across four paragraphs, and this class exposes
 * one operation per paragraph rather than one operation for the artifact. The paragraphs are the
 * header at line 506 of {@code app/cbl/CBSTM03A.CBL}, the name, address and basic-detail block at
 * line 558, the per-transaction row at line 675, and the markup tail of the paragraph at line 416,
 * whose eight writes stand at lines 439 to 454. Alternatives Considered: one operation returning the
 * whole document. Rejected because the four paragraphs have different cardinalities -- the header
 * and the tail run once per statement, the row runs once per transaction -- so a single operation
 * would have to take the transactions as a collection and would then own the iteration that the
 * calling service owns.</p>
 *
 * <p>Assumptions: the records this artifact is written to are replaced rather than appended to. The
 * statement job deletes both outputs at its null-utility step at line 66 of
 * {@code app/jcl/CREASTMT.JCL} before the program at line 79 recreates them, so a caller that
 * appended to an existing data set would produce a document with two of everything.</p>
 *
 * <h2>Assumptions: populate once, render twice</h2>
 *
 * <p>The values this class renders are prepared by {@link StatementTextMapper} and are consumed
 * here unchanged. That is not a convenience; it is what the reference does. The shared storage
 * group is populated once per card at lines 462 to 485 of {@code app/cbl/CBSTM03A.CBL}, and both
 * artifacts then read the same items. The markup paragraph reads the name at line 560, the three
 * address items at lines 571, 579 and 587, and the account identifier, balance and credit score at
 * lines 614, 621 and 628. The per-transaction items are populated at lines 676 to 678, written to
 * the plain-text band at line 679, and read again by three markup cells at lines 687, 699 and 711.
 * The markup artifact therefore renders <b>the same edited values</b> as the plain-text one, edit
 * masks included.</p>
 *
 * <p>Alternatives Considered: two other placements of the preparation were weighed and both are
 * rejected. <b>Preparing the values again here</b> would let the two artifacts disagree about one
 * customer or one amount, and the reference proves they cannot: the seven values read at lines 560,
 * 571, 579, 587, 614, 621 and 628 come from the one storage group written at lines 462 to 485, and
 * the three read at lines 687, 699 and 711 come from the one written at lines 676 to 678.
 * <b>Hoisting the preparation into a third class</b> has no basis in the specification or in the
 * ruleset, and it would separate the preparation from the plain-text bands it fills at lines 488 to
 * 502, which are the items whose declared widths decide what those values must be. The preparation
 * therefore stays with the plain-text mapper and this class consumes its records.</p>
 *
 * <p>Assumptions: the components of those records arrive at their <b>exact declared widths with
 * their trailing blanks intact</b>, and this class must not trim on receipt. Both markup regimes
 * below depend on those blanks -- one looks for a paired blank inside them, the other transfers
 * them into the output -- so a value handed over already trimmed would make the first regime
 * meaningless and would shorten the second.</p>
 *
 * <h2>Assumptions: the two amount regimes are embedded verbatim, never re-formatted</h2>
 *
 * <p>The balance cell embeds {@code ST-CURR-BAL}, declared {@code PIC 9(9).99-} at line 113 of
 * {@code app/cbl/CBSTM03A.CBL}, which is regime 6 of {@link CobolEditMask}: thirteen characters,
 * trailing sign, leading zeros preserved. The amount cell embeds {@code ST-TRANAMT}, declared
 * {@code PIC Z(9).99-} at line 137, which is regime 7: thirteen characters, trailing sign, leading
 * zeros blanked. The two are the same width and the same shape and differ only in leading-zero
 * treatment, so they are never interchanged; substituting one for the other still fills the cell
 * and still parses as the same number, and only a byte comparison would show it.</p>
 *
 * <p>Assumptions: both values reach this class as the already-edited strings that
 * {@code CobolEditMask.formatStatementBalance} and {@code CobolEditMask.formatStatementAmount}
 * produced, and this class embeds them as they stand. Their declared width is taken from
 * {@link CobolEditMask#STATEMENT_AMOUNT_WIDTH} rather than written as a literal 13, so the cell
 * arithmetic below cannot drift from the mask that produced the value. A monetary value never
 * arrives here as a bare number: {@code com.carddemo.common.money.Money} is the module's exact
 * decimal type and the masks above are the only renderings of it this artifact admits. No
 * arithmetic and no rounding happens here at all.</p>
 *
 * <p>Alternatives Considered: accepting {@code com.carddemo.common.money.Money} in this class and
 * calling the mask at line 113 or the one at line 137 here. Rejected because it would give a caller
 * a second route to a rendered amount -- one that bypasses the prepared record -- and the two
 * routes could then disagree about one transaction while both still produced thirteen characters
 * with a sign in the last position. The mask is called once, where the record is prepared.</p>
 *
 * <h2>Assumptions: no primary account number is emitted, so nothing is masked here</h2>
 *
 * <p>This is counter-intuitive enough to state outright. No fragment and no cell of this artifact
 * carries a card number. In {@code app/cbl/CBSTM03A.CBL} the card number appears only in roles
 * that produce no output: a table key declared at line 227, a read-key restore at line 421 and a
 * table populate at line 827, alongside the control-break comparand declared at line 69. Narrowing
 * an exposure that does not exist would rewrite bytes the golden comparison expects untouched, so
 * masking here would break parity while appearing prudent. Data-exposure narrowing belongs to the
 * transfer-object mapper of this package alone.</p>
 *
 * <h2>Assumptions: the markup labels differ from the plain-text labels by one character</h2>
 *
 * <p>The three colon labels declared at lines 108, 112 and 117 of {@code app/cbl/CBSTM03A.CBL}
 * each occupy twenty positions with the colon in the <em>last</em> of them and nothing after it.
 * The three markup prefixes at lines 614, 621 and 628 carry <b>one blank after the colon</b>,
 * which is why each of them is twenty-four characters rather than twenty-three. The two forms
 * differ by exactly one character and are never brought into line with one another: each is the
 * declared content of a different record at a different declared length, and a byte comparison
 * fails on one character as readily as on a hundred. Carrying both verbatim is AAP Rule T8
 * (user-visible strings are verbatim).</p>
 *
 * <h2>What the parity oracle covers here, and what it does not</h2>
 *
 * <p>Assumptions: this artifact does have an oracle, and the sibling report artifact of this module
 * does not, so the two cannot be validated on the same footing. The statement programs are batch
 * programs, and lines 40 to 46 of {@code tests/README.md} record that ten of the twelve batch
 * programs are fully automatable, so a golden statement exists to compare bytes against. The
 * on-demand report is driven by an online program, and the same lines together with lines 83 to 85
 * record that an online program cannot be run end to end without a terminal monitor, so that side
 * has no golden output at all.</p>
 *
 * <p>Assumptions: the graded return-code rubric of that suite, and its documented aggregate
 * warn-level result, belong to the COBOL suite alone. The gate this class is audited by is binary:
 * the build either has zero findings or it fails, and there is no warn tier in it to lean on.</p>
 *
 * <h2>Baseline framing</h2>
 *
 * <p>Everything under {@code app/} is reference material and stays byte-identical; no statement in
 * this file describes an edit to it. Two declarations described below are never written by the
 * reference at all, and both are recorded as observations about what the reference does rather
 * than as anything to be changed. Where migrated behaviour departs from the reference the
 * departure is registered in {@code docs/architecture/cobol-to-service-traceability.md}, which
 * owns that register; this class defines one such entry by reference and creates none.</p>
 *
 * <p>Assumptions: none of the three baseline misspelling corrections named by AAP Rule T1 reaches
 * this package, so no field is renamed anywhere in this file. It is stated once here so that a
 * reader does not go looking for a fourth rename that was never planned.</p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The obligation this file answers is user-specified Rule 1 (Explainability), whose validation
 * gate stands at its line 43, mechanised by the Javadoc modules of
 * {@code config/checkstyle/checkstyle.xml} and set out for Java in
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}. Where the three could be read differently the order
 * of precedence is the rule, then the ruleset, then the standard. Every method below carries
 * Javadoc regardless of visibility, because the rule's presence clause at its line 15 attaches no
 * visibility qualifier.</p>
 *
 * @see StatementTextMapper
 * @see CobolEditMask
 */
public final class StatementHtmlMapper {

    /**
     * Declared length of one HTML statement record, in characters.
     *
     * <p>Assumptions: corroborated five ways, as the type-level charter above sets out.</p>
     */
    public static final int HTML_RECORD_LENGTH = 100;

    /**
     * Number of named markup fragments the reference declares.
     *
     * <p>Assumptions: {@code grep -cE '^ +88 +HTML-' app/cbl/CBSTM03A.CBL} returns exactly 34, at
     * lines 150 to 157, 159 to 163, 165, 167, 169, 171, 173, 176, 179, 181, 183, 186, 188, 191,
     * 193, 196, 198, 201, 204, 207 and 209 to 211. A table of 31 entries omits three fragments and
     * a table of 36 has wrongly counted the two groups at lines 212 and 217, which are ordinary
     * group items rather than condition names. The count is asserted at class initialisation
     * below so that neither mistake can survive a build.</p>
     */
    public static final int FRAGMENT_COUNT = 34;

    /**
     * Content length of the account-number heading record before padding, in characters.
     *
     * <p>Assumptions: the group at lines 212 to 216 of {@code app/cbl/CBSTM03A.CBL} is a
     * {@value #ACCOUNT_HEADING_PREFIX_LENGTH}-character literal, a 20-character account item and a
     * {@value #ACCOUNT_HEADING_SUFFIX_LENGTH}-character literal, which is 59 characters of content
     * inside a 100-character record.</p>
     */
    public static final int ACCOUNT_HEADING_CONTENT_LENGTH = 59;

    /**
     * Declared width of the account-number heading prefix, in characters.
     *
     * <p>Assumptions: declared {@code FILLER PIC X(34)} at line 213 of
     * {@code app/cbl/CBSTM03A.CBL} with its value at line 214, and the thirty-fourth character is
     * a blank that separates the heading text from the digits.</p>
     */
    public static final int ACCOUNT_HEADING_PREFIX_LENGTH = 34;

    /**
     * Declared width of the account-number heading suffix, in characters.
     *
     * <p>Assumptions: declared {@code FILLER PIC X(05)} at line 216 of
     * {@code app/cbl/CBSTM03A.CBL}.</p>
     */
    public static final int ACCOUNT_HEADING_SUFFIX_LENGTH = 5;

    /**
     * Declared width of the styled paragraph prefix the name cell opens with, in characters.
     *
     * <p>Assumptions: declared {@code FILLER PIC X(26)} at line 218 of
     * {@code app/cbl/CBSTM03A.CBL} with its value at line 219, and the concatenation at lines 562
     * to 567 rebuilds the same 26 characters.</p>
     */
    public static final int NAME_CELL_PREFIX_LENGTH = 26;

    /**
     * Length of each basic-detail cell prefix, in characters.
     *
     * <p>Assumptions: three characters of paragraph tag, the 20-character label and one blank
     * after its colon, which is the one character by which these prefixes differ from the
     * plain-text labels. The three literals stand at lines 614, 621 and 628 of
     * {@code app/cbl/CBSTM03A.CBL}.</p>
     */
    public static final int BASIC_DETAIL_PREFIX_LENGTH = 24;

    /**
     * Length of the unstyled paragraph prefix, in characters.
     *
     * <p>Assumptions: the literal {@code <p>} opens the three address cells at lines 570, 578 and
     * 586 of {@code app/cbl/CBSTM03A.CBL} and the three transaction cells at lines 687, 699 and
     * 711.</p>
     */
    public static final int PLAIN_CELL_PREFIX_LENGTH = 3;

    /**
     * Length of the paragraph suffix every assembled cell closes with, in characters.
     *
     * <p>Assumptions: the literal {@code </p>} closes all ten assembled cells, and it is supplied
     * by the concatenation rather than by a declared item, which is why the group at lines 217 to
     * 220 of {@code app/cbl/CBSTM03A.CBL} has no position for it.</p>
     */
    public static final int CELL_SUFFIX_LENGTH = 4;

    /**
     * Length of the blank pair the right-trim regime re-appends, in characters.
     *
     * <p>Assumptions: the literals at lines 564, 572, 580 and 588 of
     * {@code app/cbl/CBSTM03A.CBL} are each two blanks transferred whole, so exactly two blanks
     * are re-appended after a value that was cut at its first blank pair.</p>
     */
    public static final int PAIRED_BLANK_LENGTH = 2;

    /**
     * Number of records the document-header operation emits.
     *
     * <p>Assumptions: the paragraph labelled at line 506 of {@code app/cbl/CBSTM03A.CBL} issues 21
     * fragment writes and one group write, the latter at line 530. The four operation counts in
     * this class sum to 75, which is exactly what
     * {@code grep -c 'WRITE FD-HTMLFILE-REC' app/cbl/CBSTM03A.CBL} reports, and that agreement is
     * the check that no write site has been dropped from any of the four sequences.</p>
     */
    public static final int DOCUMENT_HEADER_LINE_COUNT = 22;

    /**
     * Number of records the name, address and basic-detail operation emits.
     *
     * <p>Assumptions: the paragraph labelled at line 558 of {@code app/cbl/CBSTM03A.CBL} issues 27
     * fragment writes and seven assembled-cell writes, at lines 568, 576, 584, 592, 619, 626 and
     * 633.</p>
     */
    public static final int NAME_ADDRESS_BASIC_DETAIL_LINE_COUNT = 34;

    /**
     * Number of records one transaction row emits.
     *
     * <p>Assumptions: the paragraph labelled at line 675 of {@code app/cbl/CBSTM03A.CBL} issues
     * eight fragment writes and three assembled-cell writes, at lines 692, 704 and 716. The
     * paragraph is performed once per transaction, so this is a per-row count rather than a
     * per-statement one.</p>
     */
    public static final int TRANSACTION_ROW_LINE_COUNT = 11;

    /**
     * Number of records the document-footer operation emits.
     *
     * <p>Assumptions: the markup tail of the paragraph labelled at line 416 of
     * {@code app/cbl/CBSTM03A.CBL} issues eight fragment writes, at lines 440 to 454, and it is
     * the only place the four closing fragments are set.</p>
     */
    public static final int DOCUMENT_FOOTER_LINE_COUNT = 8;

    /**
     * Prefix of the account-number heading, its trailing blank included.
     *
     * <p>Assumptions: the value declared at line 214 of {@code app/cbl/CBSTM03A.CBL} ends in a
     * blank, and that blank is content rather than source-formatting slack: it is what separates
     * the heading text from the digits that follow, and dropping it would close the heading up
     * against the account number.</p>
     */
    public static final String ACCOUNT_HEADING_PREFIX = "<h3>Statement for Account Number: ";

    /**
     * Suffix of the account-number heading.
     *
     * <p>Assumptions: declared as a separate item at line 216 of {@code app/cbl/CBSTM03A.CBL},
     * after the account item rather than after a trimmed account item, which is why the nine
     * declared blanks of that item stand between the digits and this suffix.</p>
     */
    public static final String ACCOUNT_HEADING_SUFFIX = "</h3>";

    /**
     * Styled paragraph prefix the customer-name cell opens with.
     *
     * <p>Assumptions: this literal is declared twice in the reference for one output position --
     * once as the group prefix at line 219 of {@code app/cbl/CBSTM03A.CBL} and once as the first
     * item of the concatenation at line 562 -- and only the second of the two is ever written. The
     * type-level charter records why.</p>
     */
    public static final String NAME_CELL_PREFIX = "<p style=\"font-size:16px\">";

    /**
     * Unstyled paragraph prefix the address and transaction cells open with.
     */
    public static final String PLAIN_CELL_PREFIX = "<p>";

    /**
     * Paragraph suffix every assembled cell closes with.
     */
    public static final String CELL_SUFFIX = "</p>";

    /**
     * The blank pair that both delimits and terminates a right-trimmed cell value.
     *
     * <p>Assumptions: the same two characters play two roles in one statement. They are the
     * delimiter the value is cut at, at lines 563, 571, 579 and 587 of
     * {@code app/cbl/CBSTM03A.CBL}, and they are the literal re-appended immediately afterwards,
     * at lines 564, 572, 580 and 588. Using one constant for both keeps the two roles visibly the
     * same two characters, which is what makes the regime read as a cut-and-restore rather than as
     * a cut followed by an unrelated pair of blanks.</p>
     */
    public static final String PAIRED_BLANK = "  ";

    /**
     * Prefix of the account-identifier basic-detail cell, its trailing blank included.
     *
     * <p>Assumptions: 24 characters, declared at line 614 of {@code app/cbl/CBSTM03A.CBL}. The
     * plain-text label at line 108 is the same text without the blank after the colon, and the two
     * are never harmonised.</p>
     */
    public static final String ACCOUNT_ID_CELL_PREFIX = "<p>Account ID         : ";

    /**
     * Prefix of the current-balance basic-detail cell, its trailing blank included.
     *
     * <p>Assumptions: 24 characters, declared at line 621 of {@code app/cbl/CBSTM03A.CBL},
     * differing from the plain-text label at line 112 by that one blank.</p>
     */
    public static final String CURRENT_BALANCE_CELL_PREFIX = "<p>Current Balance    : ";

    /**
     * Prefix of the credit-score basic-detail cell, its trailing blank included.
     *
     * <p>Assumptions: 24 characters, declared at line 628 of {@code app/cbl/CBSTM03A.CBL},
     * differing from the plain-text label at line 117 by that one blank.</p>
     */
    public static final String FICO_SCORE_CELL_PREFIX = "<p>FICO Score         : ";

    // WHY : Assumptions: the 34 declarations below are condition names on ONE reusable
    //       100-character buffer, declared at line 149 of app/cbl/CBSTM03A.CBL. Selecting a
    //       condition name moves its literal into that buffer and the following write emits the
    //       buffer, blank-padded to the declared 100. That mechanism is why the reference has no
    //       template and no substitution: the artifact is a sequence of whole declared literals,
    //       and the only thing a position decides is WHICH literal. The names are kept exactly as
    //       the reference spells them -- including the two that carry more than one output line
    //       number -- because those names are the only surviving record of which output positions
    //       each literal serves.
    // WHY : Assumptions: eleven of the 34 literals are continued across source lines, at 157 to
    //       158, 163 to 164, 165 to 166, 173 to 175, 176 to 178, 183 to 185, 188 to 190, 193 to
    //       195, 198 to 200, 201 to 203 and 204 to 206 of app/cbl/CBSTM03A.CBL, and the
    //       continuation is marked in the indicator column rather than by any token inside the
    //       literal. A transcription that read one physical line per literal would silently
    //       truncate every one of those eleven at the source margin.
    // WHY : Assumptions: the declarations take three distinct shapes and all three occur -- name
    //       and value on one line at line 150 of app/cbl/CBSTM03A.CBL, name then value on the next
    //       line with no continuation at lines 167 to 168, and name then value then a continuation
    //       line at lines 173 to 175 -- so a line-oriented parser mis-reads at least two of the
    //       three. Every literal here was extracted by joining on the indicator column and then
    //       checked against the source, rather than pattern-matched.
    // WHY : Alternatives Considered: a templating engine holding this markup, with the values
    //       substituted at render. Rejected because the reference output is a sequence of whole
    //       declared literals -- the 34 declared at lines 150 to 211 of app/cbl/CBSTM03A.CBL --
    //       rather than a document with holes in it, so a template would become a second source of
    //       truth for bytes that have to match a golden artifact exactly, and the two sources could
    //       then differ in a blank nobody would see in a diff of the template.
    // WHY : Alternatives Considered: moving this table into a companion constants class and
    //       leaving only the sequence here. Rejected because the names HTML-L22-35 at line 173 and
    //       HTML-L30-42 at line 176 embed their output line numbers precisely BECAUSE one literal
    //       is emitted at several positions, so the table and the sequence are two halves of one
    //       fact; separating them would hide the repetition that the names exist to record. The
    //       package charter records the same reasoning for the package as a whole.
    // WHY : Trade-offs: the cost of keeping the table here is a long file with 34 literals in it,
    //       several of them 83 to 85 characters wide, and the compensation is that every emission
    //       position below names the literal it emits and a reader can check the pair without
    //       leaving the file. The literals are carried byte for byte under AAP Rule T8
    //       (user-visible strings are verbatim): no blank is normalised, no colour value is
    //       shortened and no attribute is reordered or minified.

    // WHY : Assumptions: the table below is attributed once, here, rather than one comment block
    //       per constant. Each row names the declaration, the source line of app/cbl/CBSTM03A.CBL
    //       it is declared at, its length in characters and how many times the reference selects
    //       it. Attribution in one place is what keeps a 34-entry table readable, and the
    //       selection column is the part that cannot be recovered from the declarations at all:
    //       the selections sum to 64, which is what `grep -c 'SET HTML-' app/cbl/CBSTM03A.CBL`
    //       reports. Twenty-seven of the 34 serve exactly one output position, six serve more than
    //       one and one serves none at all, which is 27 plus 6 plus 1 for the 34 declarations and
    //       27 plus 37 for the 64 selections.
    //
    //       name          declared at   length   selected
    //       HTML_L01      150               15          1
    //       HTML_L02      151               16          1
    //       HTML_L03      152                6          1
    //       HTML_L04      153               22          1
    //       HTML_L05      154               32          1
    //       HTML_L06      155                7          1
    //       HTML_L07      156               26          1
    //       HTML_L08      157 to 158        85          1
    //       HTML_LTRS     159                4          9
    //       HTML_LTRE     160                5          9
    //       HTML_LTDS     161                4          0
    //       HTML_LTDE     162                5         13
    //       HTML_L10      163 to 164        68          2
    //       HTML_L15      165 to 166        66          1
    //       HTML_L16      167 to 168        41          1
    //       HTML_L17      169 to 170        22          1
    //       HTML_L18      171 to 172        23          1
    //       HTML_L22_35   173 to 175        66          2
    //       HTML_L30_42   176 to 178        85          2
    //       HTML_L31      179 to 180        43          1
    //       HTML_L43      181 to 182        49          1
    //       HTML_L47      183 to 185        83          1
    //       HTML_L48      186 to 187        37          1
    //       HTML_L50      188 to 190        83          1
    //       HTML_L51      191 to 192        42          1
    //       HTML_L53      193 to 195        84          1
    //       HTML_L54      196 to 197        36          1
    //       HTML_L58      198 to 200        83          1
    //       HTML_L61      201 to 203        83          1
    //       HTML_L64      204 to 206        84          1
    //       HTML_L75      207 to 208        25          1
    //       HTML_L78      209                8          1
    //       HTML_L79      210                7          1
    //       HTML_L80      211                7          1

    public static final String HTML_L01 = "<!DOCTYPE html>";

    public static final String HTML_L02 = "<html lang=\"en\">";

    public static final String HTML_L03 = "<head>";

    public static final String HTML_L04 = "<meta charset=\"utf-8\">";

    public static final String HTML_L05 = "<title>HTML Table Layout</title>";

    public static final String HTML_L06 = "</head>";

    public static final String HTML_L07 = "<body style=\"margin:0px;\">";

    // WHY : Assumptions: there are TWO blanks after the element name, and they are declared bytes
    //       of the reference output rather than source-formatting slack -- the literal begins at
    //       line 157 of app/cbl/CBSTM03A.CBL and continues at 158, and both blanks fall inside it.
    //       Collapsing them to one would shorten the record's content by a character and fail a
    //       byte comparison on a difference that reads as invisible whitespace.
    public static final String HTML_L08 =
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";

    public static final String HTML_LTRS = "<tr>";

    public static final String HTML_LTRE = "</tr>";

    // WHY : Assumptions: this is the one declared literal the reference never emits.
    //       `grep -c 'SET HTML-LTDS TO TRUE' app/cbl/CBSTM03A.CBL` returns 0, while the matching
    //       close below is selected 13 times, because every cell this artifact opens is opened by
    //       one of the styled variants that carry a width and a colour instead. It is carried here
    //       so that the table is the reference's 34 declarations rather than the 33 that have an
    //       emission position, and so that a reader who counts selections does not conclude a
    //       fragment is missing. The reference declares it and does not use it; this class
    //       declares it and does not emit it.
    public static final String HTML_LTDS = "<td>";

    public static final String HTML_LTDE = "</td>";

    // WHY : Assumptions: the colour value carries EIGHT hexadecimal digits, not six. The last two
    //       are an alpha component, and the literal at lines 163 to 164 of
    //       app/cbl/CBSTM03A.CBL declares all eight. A transcription that read it as a six-digit
    //       colour with two stray characters would both shorten the record and change the rendered
    //       banner from translucent to opaque.
    public static final String HTML_L10 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";

    public static final String HTML_L15 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";

    // WHY : Assumptions: these three literals are the issuer identity block of the reference
    //       output, declared at lines 167 to 172 of app/cbl/CBSTM03A.CBL. They are artifact
    //       CONTENT and not configuration: the reference prints them from declared storage with no
    //       parameter and no data-set field behind them, so moving them into configuration would
    //       make an output byte depend on a deployment value and would let one environment print a
    //       statement the golden comparison rejects. They are carried verbatim under AAP Rule T8
    //       (user-visible strings are verbatim).
    public static final String HTML_L16 = "<p style=\"font-size:16px\">Bank of XYZ</p>";

    public static final String HTML_L17 = "<p>410 Terry Ave N</p>";

    public static final String HTML_L18 = "<p>Seattle WA 99999</p>";

    // WHY : Assumptions: the name of this declaration carries TWO output line numbers because the
    //       literal is emitted at two positions, selected at lines 551 and 610 of
    //       app/cbl/CBSTM03A.CBL. The name is kept as the reference spells it rather than renamed
    //       to one of its roles, because either role name would read as the only one.
    public static final String HTML_L22_35 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";

    // WHY : Assumptions: this name likewise carries two output line numbers, and it is selected at
    //       lines 600 and 640 of app/cbl/CBSTM03A.CBL.
    public static final String HTML_L30_42 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";

    public static final String HTML_L31 = "<p style=\"font-size:16px\">Basic Details</p>";

    public static final String HTML_L43 = "<p style=\"font-size:16px\">Transaction Summary</p>";

    // WHY : Assumptions: the three column widths of this artifact are 25, 55 and 20 per cent and
    //       they are declared twice over -- once on the heading row at lines 183, 188 and 193 of
    //       app/cbl/CBSTM03A.CBL and once on the detail row at lines 198, 201 and 204 -- with a
    //       different background colour on each row and the third column right-aligned in both.
    //       The two rows are separate declarations rather than one reused literal, so a shared
    //       constant would emit the heading colour into the detail row.
    public static final String HTML_L47 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";

    public static final String HTML_L48 = "<p style=\"font-size:16px\">Tran ID</p>";

    public static final String HTML_L50 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";

    public static final String HTML_L51 = "<p style=\"font-size:16px\">Tran Details</p>";

    public static final String HTML_L53 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";

    public static final String HTML_L54 = "<p style=\"font-size:16px\">Amount</p>";

    public static final String HTML_L58 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";

    public static final String HTML_L61 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";

    public static final String HTML_L64 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";

    public static final String HTML_L75 = "<h3>End of Statement</h3>";

    public static final String HTML_L78 = "</table>";

    public static final String HTML_L79 = "</body>";

    public static final String HTML_L80 = "</html>";

    /**
     * The 34 declared markup fragments in declaration order.
     *
     * <p>Assumptions: this list exists so that the count and the content of the table are
     * assertable as a whole rather than one constant at a time, and its order is the reference's
     * declaration order at lines 150 to 211 of {@code app/cbl/CBSTM03A.CBL} rather than any
     * emission order. It is <em>not</em> an emission sequence and indexing into it would be
     * reading it as one; the four emission operations name their fragments individually.</p>
     */
    public static final List<String> FRAGMENT_TABLE = List.of(
            HTML_L01, HTML_L02, HTML_L03, HTML_L04, HTML_L05, HTML_L06, HTML_L07, HTML_L08,
            HTML_LTRS, HTML_LTRE, HTML_LTDS, HTML_LTDE, HTML_L10, HTML_L15, HTML_L16, HTML_L17,
            HTML_L18, HTML_L22_35, HTML_L30_42, HTML_L31, HTML_L43, HTML_L47, HTML_L48, HTML_L50,
            HTML_L51, HTML_L53, HTML_L54, HTML_L58, HTML_L61, HTML_L64, HTML_L75, HTML_L78,
            HTML_L79, HTML_L80);

    static {
        // WHY : Assumptions: the census of 34 is asserted at class initialisation rather than left
        //       to a test, because the two ways of getting this table wrong are both silent. A
        //       table of 31 omits three literals and still emits a well-formed document, and a
        //       table of 36 has counted the two group items at lines 212 and 217 of
        //       app/cbl/CBSTM03A.CBL as though they were condition names. Either would pass
        //       compilation and would surface only as a byte difference in a golden comparison.
        if (FRAGMENT_TABLE.size() != FRAGMENT_COUNT) {
            throw new IllegalStateException("fragment table holds " + FRAGMENT_TABLE.size()
                    + " entries but app/cbl/CBSTM03A.CBL declares " + FRAGMENT_COUNT);
        }

        // WHY : Assumptions: a fragment longer than the declared record length could not be
        //       emitted at all, so the ceiling is checked here rather than discovered as a
        //       truncation at the first statement produced. The longest declared literal is 85
        //       characters, at lines 157 to 158, so the check has 15 characters of headroom and
        //       exists to catch a transcription that ran two literals together.
        for (String fragment : FRAGMENT_TABLE) {
            if (fragment.length() > HTML_RECORD_LENGTH) {
                throw new IllegalStateException("fragment exceeds the declared record length of "
                        + HTML_RECORD_LENGTH + ": " + fragment);
            }
        }
    }

    /**
     * Prevents instantiation of this static assembler.
     *
     * <p>Assumptions: the reference emits its markup from declared storage inside one program and
     * keeps no per-statement state of its own, so there is nothing for an instance to hold. Every
     * operation below is a pure function of its arguments and of the declared table above.</p>
     *
     * <p>Alternatives Considered: an instantiable mapper held as an injected collaborator. Rejected
     * because an instance would offer a seam for per-statement state, and per-statement state is
     * exactly what a byte-exact assembly must not have: the transaction row at line 675 of
     * {@code app/cbl/CBSTM03A.CBL} is emitted once per transaction, so two rows assembled in either
     * order have to produce the same 100-character records. Declaring the constructor private
     * states that intent where the language enforces it, and the class is final so no subclass can
     * reopen the decision. The two sibling assemblers of this package are shaped the same way.</p>
     *
     * <p>Assumptions: this constructor carries full Javadoc rather than being left bare because
     * user-specified Rule 1 (Explainability) attaches its docstring obligation at line 15 to every
     * function, class and module entry point and names no visibility, and the
     * {@code MissingJavadocMethod} module of {@code config/checkstyle/checkstyle.xml} is configured
     * at private scope so an undocumented private constructor fails the build.</p>
     */
    private StatementHtmlMapper() {
    }

    /**
     * Emits the opening records of the markup statement, up to and including the first panel cell.
     *
     * <p>Assumptions: the sequence transcribed here is the paragraph labelled at line 506 of
     * {@code app/cbl/CBSTM03A.CBL} through its exit at line 554, in source order: the document
     * opening at lines 508 to 523, a row and banner cell at 524 and 526, the account-number
     * heading at 529 and 530, the cell and row closes at 531 and 533, then the issuer identity
     * block bracketed at 535 to 547, and finally a row and the first neutral panel cell at 549 and
     * 551. That is {@value #DOCUMENT_HEADER_LINE_COUNT} records.</p>
     *
     * <p>Assumptions: three declarations are selected more than once inside this one sequence --
     * the row open at lines 524, 535 and 549, the cell close at 531 and 545, and the row close at
     * 533 and 547 -- and each repetition is emitted again rather than collapsed. A sequence that
     * emitted a repeated fragment once would produce a document whose rows do not close, and the
     * same discipline governs the other three operations.</p>
     *
     * <p>Alternatives Considered: taking the whole prepared-field record here rather than the one
     * item this paragraph reads. Rejected on the reference's own ordering: the header is written at
     * line 461 of {@code app/cbl/CBSTM03A.CBL}, <em>before</em> the shared storage group is
     * populated at lines 462 to 485, so the account identifier is the only prepared value the
     * paragraph can read and taking the record would oblige a caller to have prepared six values
     * this operation never touches.</p>
     *
     * @param accountIdItem the account identifier at the declared width of
     *     {@value StatementTextMapper#ACCOUNT_ID_ITEM_WIDTH} characters, being the digits followed
     *     by declared blanks, exactly as
     *     {@link StatementTextMapper#renderAccountIdItem(long)} produces it and as
     *     {@code StatementTextMapper.PreparedHeaderFields.accountId()} carries it
     * @return {@value #DOCUMENT_HEADER_LINE_COUNT} freshly allocated records, each exactly
     *     {@value #HTML_RECORD_LENGTH} bytes, in emission order; never {@code null}
     * @throws NullPointerException if {@code accountIdItem} is {@code null}
     * @throws IllegalArgumentException if {@code accountIdItem} is not exactly
     *     {@value StatementTextMapper#ACCOUNT_ID_ITEM_WIDTH} characters, because a shorter or
     *     longer item would move the closing tag of the heading off its declared position
     */
    public static List<byte[]> emitDocumentHeader(String accountIdItem) {
        Objects.requireNonNull(accountIdItem, "accountIdItem must not be null");
        requireExactWidth(accountIdItem, StatementTextMapper.ACCOUNT_ID_ITEM_WIDTH,
                "accountIdItem");

        return List.of(
                fragmentRecord(HTML_L01),
                fragmentRecord(HTML_L02),
                fragmentRecord(HTML_L03),
                fragmentRecord(HTML_L04),
                fragmentRecord(HTML_L05),
                fragmentRecord(HTML_L06),
                fragmentRecord(HTML_L07),
                fragmentRecord(HTML_L08),
                fragmentRecord(HTML_LTRS),
                fragmentRecord(HTML_L10),
                accountHeadingRecord(accountIdItem),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_LTRE),
                fragmentRecord(HTML_LTRS),
                fragmentRecord(HTML_L15),
                fragmentRecord(HTML_L16),
                fragmentRecord(HTML_L17),
                fragmentRecord(HTML_L18),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_LTRE),
                fragmentRecord(HTML_LTRS),
                fragmentRecord(HTML_L22_35));
    }

    /**
     * Emits the customer name, the three address cells and the three basic-detail cells.
     *
     * <p>Assumptions: the sequence transcribed here is the paragraph labelled at line 558 of
     * {@code app/cbl/CBSTM03A.CBL} through its exit at line 671, in source order: the name cell
     * written at line 568, the three address cells at 576, 584 and 592, the panel and heading
     * scaffolding at 594 to 611, the three basic-detail cells at 619, 626 and 633, and the column
     * scaffolding at 634 to 669. That is {@value #NAME_ADDRESS_BASIC_DETAIL_LINE_COUNT}
     * records.</p>
     *
     * <p>Assumptions: the first four of those cells use the right-trim regime and the last three
     * use the whole-item regime, and the difference is not a stylistic one. The narrowed name and
     * the three address items are cut at their first blank pair; the account identifier, the edited
     * balance and the credit score are transferred whole, trailing blanks included. Applying either
     * regime to the other three cells changes the bytes of every statement.</p>
     *
     * <p>Assumptions: the narrowed name comes from
     * {@code StatementTextMapper.PreparedHeaderFields.markupName()} rather than from the assembled
     * name directly, because line 560 of {@code app/cbl/CBSTM03A.CBL} moves the assembled name
     * into a narrower item before line 563 reads it, and that narrowing keeps the trailing blanks
     * of the leftmost {@value StatementTextMapper#MARKUP_NAME_WIDTH} characters, which is what the
     * right-trim regime then examines.</p>
     *
     * @param fields the once-per-card prepared values, every component at its exact declared width
     *     with its trailing blanks intact
     * @return {@value #NAME_ADDRESS_BASIC_DETAIL_LINE_COUNT} freshly allocated records, each
     *     exactly {@value #HTML_RECORD_LENGTH} bytes, in emission order; never {@code null}
     * @throws NullPointerException if {@code fields} is {@code null}
     * @throws IllegalStateException if any assembled cell exceeds the declared record length, which
     *     reports a defect in this class rather than a fault of the caller
     */
    public static List<byte[]> emitNameAddressAndBasicDetails(
            StatementTextMapper.PreparedHeaderFields fields) {
        Objects.requireNonNull(fields, "fields must not be null");

        // WHY : Assumptions: the four right-trimmed cells are not symmetrical in the reference and
        //       the asymmetry is transcribed rather than tidied. The name cell is assembled
        //       directly into the output record and written with no source item, at lines 566 and
        //       568 of app/cbl/CBSTM03A.CBL, while the three address cells are assembled into a
        //       separate buffer and written from it, at lines 574, 576, 582, 584, 590 and 592. The
        //       distinction has no effect on the bytes -- both routes reach the same
        //       100-character record -- so it is honoured here only in so far as each cell is one
        //       independent assembly, which is what the four separate blanking moves at lines 561,
        //       569, 577 and 585 make it.
        return List.of(
                rightTrimmedCellRecord(NAME_CELL_PREFIX, fields.markupName()),
                rightTrimmedCellRecord(PLAIN_CELL_PREFIX, fields.addressLine1()),
                rightTrimmedCellRecord(PLAIN_CELL_PREFIX, fields.addressLine2()),
                rightTrimmedCellRecord(PLAIN_CELL_PREFIX, fields.assembledAddress()),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_LTRE),
                fragmentRecord(HTML_LTRS),
                fragmentRecord(HTML_L30_42),
                fragmentRecord(HTML_L31),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_LTRE),
                fragmentRecord(HTML_LTRS),
                fragmentRecord(HTML_L22_35),
                wholeItemCellRecord(ACCOUNT_ID_CELL_PREFIX, fields.accountId()),

                // WHY : Assumptions: the edited balance is embedded as it arrives. It was produced
                //       by CobolEditMask.formatStatementBalance under the mask declared at line 113
                //       of app/cbl/CBSTM03A.CBL, and line 621 embeds that same edited item in the
                //       markup, so re-formatting it here -- or formatting a number instead -- could
                //       make the two artifacts disagree about one balance while both still read as
                //       the same amount.
                wholeItemCellRecord(CURRENT_BALANCE_CELL_PREFIX, fields.currentBalance()),
                wholeItemCellRecord(FICO_SCORE_CELL_PREFIX, fields.creditScore()),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_LTRE),
                fragmentRecord(HTML_LTRS),
                fragmentRecord(HTML_L30_42),
                fragmentRecord(HTML_L43),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_LTRE),
                fragmentRecord(HTML_LTRS),
                fragmentRecord(HTML_L47),
                fragmentRecord(HTML_L48),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_L50),
                fragmentRecord(HTML_L51),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_L53),
                fragmentRecord(HTML_L54),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_LTRE));
    }

    /**
     * Emits one transaction row: an identifier cell, a detail cell and an amount cell.
     *
     * <p>Assumptions: the sequence transcribed here is the markup half of the paragraph labelled at
     * line 675 of {@code app/cbl/CBSTM03A.CBL}, in source order: a row open at line 681, then a
     * detail cell, its assembled content and its close for each of the three columns at 684 to 694,
     * 696 to 706 and 708 to 718, and a row close at 720. That is
     * {@value #TRANSACTION_ROW_LINE_COUNT} records, and the paragraph is performed once per
     * transaction rather than once per statement.</p>
     *
     * <p>Assumptions: all three cells use the whole-item regime, so each value is transferred with
     * its trailing blanks and the amount keeps its sign position. The plain-text band at line 679
     * is written from the same three items, which is why they arrive here already edited.</p>
     *
     * @param fields the per-transaction prepared values, every component at its exact declared
     *     width with its trailing blanks intact
     * @return {@value #TRANSACTION_ROW_LINE_COUNT} freshly allocated records, each exactly
     *     {@value #HTML_RECORD_LENGTH} bytes, in emission order; never {@code null}
     * @throws NullPointerException if {@code fields} is {@code null}
     * @throws IllegalStateException if any assembled cell exceeds the declared record length, which
     *     reports a defect in this class rather than a fault of the caller
     */
    public static List<byte[]> emitTransactionRow(
            StatementTextMapper.PreparedTransactionFields fields) {
        Objects.requireNonNull(fields, "fields must not be null");

        return List.of(
                fragmentRecord(HTML_LTRS),
                fragmentRecord(HTML_L58),
                wholeItemCellRecord(PLAIN_CELL_PREFIX, fields.transactionId()),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_L61),
                wholeItemCellRecord(PLAIN_CELL_PREFIX, fields.description()),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_L64),

                // WHY : Assumptions: this is the other of the two amount regimes and the two are
                //       never interchanged. The value arrives from
                //       CobolEditMask.formatStatementAmount under the mask declared at line 137 of
                //       app/cbl/CBSTM03A.CBL, which blanks leading zeros where the balance mask at
                //       line 113 preserves them. Both are thirteen characters with a trailing sign,
                //       so a substitution would still fill the cell and would still parse as the
                //       same number.
                wholeItemCellRecord(PLAIN_CELL_PREFIX, fields.amount()),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_LTRE));
    }

    /**
     * Emits the closing records of the markup statement, from the final banner to the document
     * close.
     *
     * <p>Assumptions: the sequence transcribed here is the markup tail of the paragraph labelled at
     * line 416 of {@code app/cbl/CBSTM03A.CBL}, at lines 439 to 454, in source order: a row and
     * banner cell, the closing heading, the cell and row closes, and then the table, body and
     * document closes. That is {@value #DOCUMENT_FOOTER_LINE_COUNT} records, and those lines are
     * the only place the four closing declarations are selected at all.</p>
     *
     * <p>Alternatives Considered: folding this tail into the transaction-row operation, so that
     * three operations covered the whole artifact. Rejected because the tail is written once per
     * statement while the row is written once per transaction, so a combined operation would emit
     * the document close after every transaction. Alternatives Considered: leaving the tail to the
     * caller. Rejected because the four closing declarations would then have no emission position
     * in this class and the artifact it assembles could not be a complete document.</p>
     *
     * <p>Parameters, return values, exceptions or errors: this operation reads nothing, so it takes
     * no parameter and raises nothing; the inapplicability is stated rather than passed over,
     * because user-specified Rule 1 (Explainability) forbids at its line 39 a docstring that omits
     * parameters, and a reader has to be able to tell a declared inapplicability from an
     * oversight.</p>
     *
     * @return {@value #DOCUMENT_FOOTER_LINE_COUNT} freshly allocated records, each exactly
     *     {@value #HTML_RECORD_LENGTH} bytes, in emission order; never {@code null}
     */
    public static List<byte[]> emitDocumentFooter() {
        return List.of(
                fragmentRecord(HTML_LTRS),
                fragmentRecord(HTML_L10),
                fragmentRecord(HTML_L75),
                fragmentRecord(HTML_LTDE),
                fragmentRecord(HTML_LTRE),
                fragmentRecord(HTML_L78),
                fragmentRecord(HTML_L79),
                fragmentRecord(HTML_L80));
    }

    /**
     * Places one whole declared fragment into a record of the declared length.
     *
     * <p>Assumptions: this is the analogue of selecting a condition name on the reusable buffer at
     * line 149 of {@code app/cbl/CBSTM03A.CBL} and then writing that buffer. In the reference the
     * blanks after the literal are already there, because the buffer is declared at the full
     * {@value #HTML_RECORD_LENGTH} and a move into it blank-fills the remainder; here they are
     * appended, which reaches the same record from the opposite direction.</p>
     *
     * @param fragment one of the {@value #FRAGMENT_COUNT} declared fragments; must be a member of
     *     {@link #FRAGMENT_TABLE} in the sense of being one of those constants rather than a
     *     literal composed at the call site
     * @return a freshly allocated record of exactly {@value #HTML_RECORD_LENGTH} bytes holding the
     *     fragment followed by declared blanks, never {@code null}
     * @throws IllegalStateException if the fragment is longer than the declared record length,
     *     which reports a defect in the table above rather than a fault of the caller
     */
    private static byte[] fragmentRecord(String fragment) {
        return toRecord(fragment);
    }

    /**
     * Assembles the account-number heading record from its two literals and the account item.
     *
     * <p>Assumptions: the group at lines 212 to 216 of {@code app/cbl/CBSTM03A.CBL} is written once
     * per statement, at line 530, and it is fed by the move at line 529. That move takes
     * {@code ACCT-ID}, declared {@code PIC 9(11)} at line 5 of {@code app/cpy/CVACT01Y.cpy}, into a
     * character item of {@value StatementTextMapper#ACCOUNT_ID_ITEM_WIDTH} positions, so the eleven
     * digits land left-justified and <b>nine declared blanks stand between them and the closing
     * tag</b>. Those nine blanks are output content: the heading suffix is a separate declared item
     * that follows the account item rather than following its digits, so nothing closes up the
     * gap.</p>
     *
     * <p>Assumptions: the same eleven-digit rendering also feeds the plain-text band, because line
     * 483 moves the same source into another character item of the same declared width. One source
     * item, two positions, the same bytes, which is why this operation takes the prepared item
     * rather than rendering the digits again.</p>
     *
     * @param accountIdItem the account identifier at exactly
     *     {@value StatementTextMapper#ACCOUNT_ID_ITEM_WIDTH} characters, its declared blanks
     *     included
     * @return a freshly allocated record of exactly {@value #HTML_RECORD_LENGTH} bytes whose first
     *     {@value #ACCOUNT_HEADING_CONTENT_LENGTH} characters are the heading, never {@code null}
     * @throws IllegalStateException if the assembled heading is not exactly
     *     {@value #ACCOUNT_HEADING_CONTENT_LENGTH} characters, which reports a defect in this class
     *     rather than a fault of the caller
     */
    private static byte[] accountHeadingRecord(String accountIdItem) {
        String heading = ACCOUNT_HEADING_PREFIX + accountIdItem + ACCOUNT_HEADING_SUFFIX;

        // WHY : Assumptions: the assembled length is asserted against the declared 59 rather than
        //       merely against the record ceiling, because this record is the one place in the
        //       artifact where a declared literal, a declared item width and a second declared
        //       literal have to line up exactly. 34 plus 20 plus 5 is 59, and any other total means
        //       one of the three declarations has been transcribed at the wrong width -- which a
        //       ceiling check would let through, since 59 is far below 100.
        if (heading.length() != ACCOUNT_HEADING_CONTENT_LENGTH) {
            throw new IllegalStateException("account heading is " + heading.length()
                    + " characters but app/cbl/CBSTM03A.CBL declares "
                    + ACCOUNT_HEADING_CONTENT_LENGTH);
        }
        return toRecord(heading);
    }

    /**
     * Assembles a cell whose value is cut at its first blank pair and then closed with two blanks.
     *
     * <p>Assumptions: this is the {@code DELIMITED BY '  '} regime, used at exactly four positions
     * -- lines 563, 571, 579 and 587 of {@code app/cbl/CBSTM03A.CBL} -- and
     * {@code grep -c "DELIMITED BY '  '"} returns 4, so those four are all of them. The statement
     * transfers the value up to its first <b>pair</b> of blanks, then transfers a literal pair of
     * blanks whole, then the closing tag. Two consequences follow and both are load-bearing:
     * <b>a single trailing blank survives</b>, because one blank is not a pair and the delimiter is
     * therefore not found; and a value carrying a blank pair anywhere inside it is cut there, not
     * at its end.</p>
     *
     * <p>Alternatives Considered: {@code String.strip()} or {@code String.trim()} in place of this
     * helper at the four positions above. Rejected because each is wrong in both directions at once.
     * They remove <em>every</em> trailing blank, so a value whose single trailing blank the
     * statements at lines 563, 571, 579 and 587 carry through would lose it here and the cell would
     * be one character shorter than the golden artifact; and neither cuts at an internal blank pair,
     * which is the only thing this regime actually does. Neither method appears anywhere in this
     * file.</p>
     *
     * <p>Assumptions: the blank pair is re-appended unconditionally, including after a value that
     * had no pair to be cut at, because the literal at lines 564, 572, 580 and 588 is transferred
     * whole rather than conditionally. A cell whose value ran to its full declared width therefore
     * still carries two blanks before its closing tag.</p>
     *
     * @param prefix the opening literal of the cell, either the styled prefix of
     *     {@value #NAME_CELL_PREFIX_LENGTH} characters or the unstyled one of
     *     {@value #PLAIN_CELL_PREFIX_LENGTH}
     * @param value the item to render, at its exact declared width with its trailing blanks intact
     *     and not trimmed by the caller
     * @return a freshly allocated record of exactly {@value #HTML_RECORD_LENGTH} bytes, never
     *     {@code null}
     * @throws IllegalStateException if the assembled cell exceeds the declared record length, which
     *     reports a defect in this class rather than a fault of the caller
     */
    private static byte[] rightTrimmedCellRecord(String prefix, String value) {
        return toRecord(prefix + upToFirstBlankPair(value) + PAIRED_BLANK + CELL_SUFFIX);
    }

    /**
     * Assembles a cell whose value is transferred whole, trailing blanks included.
     *
     * <p>Assumptions: this is the {@code DELIMITED BY '*'} regime, and it transfers the complete
     * item because <b>no asterisk occurs in the data</b>. A delimiter that is never found leaves the
     * whole item to transfer, so the declared blanks of a short value travel into the cell. That is
     * the opposite of the regime above and the two are never substituted for one another.</p>
     *
     * <p>Assumptions: there are <b>six</b> such cells, not three: the basic-detail cells at lines
     * 614, 621 and 628 of {@code app/cbl/CBSTM03A.CBL} and the transaction cells at lines 687, 699
     * and 711. The arithmetic that proves the list is complete runs against the count of 26 that
     * {@code grep -c "DELIMITED BY '*'"} reports: the name cell contributes 2 asterisk-delimited
     * items, the three address cells 2 each for 6, the three basic-detail cells 3 each for 9 and the
     * three transaction cells 3 each for 9, and 2 plus 6 plus 9 plus 9 is 26 exactly. A site list
     * of three would leave 9 of those items unaccounted for.</p>
     *
     * @param prefix the opening literal of the cell, either one of the three basic-detail prefixes
     *     of {@value #BASIC_DETAIL_PREFIX_LENGTH} characters or the unstyled prefix of
     *     {@value #PLAIN_CELL_PREFIX_LENGTH}
     * @param value the item to render, at its exact declared width with its trailing blanks intact;
     *     for the two monetary cells this is the already-edited string of
     *     {@value CobolEditMask#STATEMENT_AMOUNT_WIDTH} characters, sign position included
     * @return a freshly allocated record of exactly {@value #HTML_RECORD_LENGTH} bytes, never
     *     {@code null}
     * @throws IllegalStateException if the assembled cell exceeds the declared record length, which
     *     reports a defect in this class rather than a fault of the caller
     */
    private static byte[] wholeItemCellRecord(String prefix, String value) {
        return toRecord(prefix + value + CELL_SUFFIX);
    }

    /**
     * Returns the characters of a value that precede its first pair of consecutive blanks.
     *
     * <p>Assumptions: a delimiter that does not occur in the operand leaves the whole operand to
     * transfer, which is what makes a value with at most one trailing blank pass through unchanged.
     * The search is for two consecutive blanks rather than for any run of whitespace: no other
     * whitespace character occurs in a declared character item of this artifact, and treating a tab
     * or a line separator as a blank would cut a value the reference would carry whole.</p>
     *
     * <p>Assumptions: this is also where the paired-blank name observation becomes visible, and the
     * behaviour here is the reference's own rather than a departure from it. The plain-text mapper
     * joins the three name parts with one blank literal after each, so a customer with no middle
     * name yields a name carrying a blank <em>pair</em> between the first and last names; this
     * helper then cuts at that pair, so the markup cell shows the first name alone while the
     * plain-text band still shows the whole name. The same exposure applies to the assembled
     * address, whose four parts are joined the same way and reach this helper at line 587 of
     * {@code app/cbl/CBSTM03A.CBL}. The asymmetry is reproduced rather than reconciled, is
     * registered as {@code D-STMT-PAIRED-BLANK-NAME} in
     * {@code docs/architecture/cobol-to-service-traceability.md}, and the decision is the same one
     * {@link StatementTextMapper#assembleName(String, String, String)} records at its own point of
     * use, so the two halves of the statement path cannot drift apart on it.</p>
     *
     * <p>Trade-offs: reconciling the two renderings -- by collapsing the blank pair before line
     * 563's cut reads it -- was weighed and declined. It would make the two artifacts agree on a
     * name whose middle part is empty, at the cost of changing the bytes of a 100-character markup
     * cell against its golden output, which AAP Rule T9 (structure changes, behaviour does not)
     * forbids. The accepted cost is that the two renderings of one such name genuinely differ,
     * exactly as they do in the reference, where the plain-text band at line 488 shows the whole
     * name and the markup cell at line 568 shows the first name alone.</p>
     *
     * @param value the item to cut, at its exact declared width with its trailing blanks intact
     * @return the leading characters of {@code value} up to but excluding its first blank pair, or
     *     the whole of {@code value} when it holds no blank pair; never {@code null}
     */
    private static String upToFirstBlankPair(String value) {
        int pair = value.indexOf(PAIRED_BLANK);
        return pair < 0 ? value : value.substring(0, pair);
    }

    /**
     * Blank-pads assembled content out to the declared record length and encodes it.
     *
     * <p>Assumptions: the artifact's character encoding is US-ASCII, matching the encoding the
     * shared codec uses for the plain-text statement and the seed data sets. Using one encoding for
     * both artifacts is what keeps them from disagreeing about a character that neither can
     * represent: whatever the encoder substitutes for such a character, it substitutes in both.</p>
     *
     * <p>Alternatives Considered: composing these records with a format string or a decimal
     * formatter instead of concatenation. Rejected because both are locale-sensitive, and a runtime
     * whose default locale groups with a full stop and separates decimals with a comma would render
     * the thirteen characters of the mask at line 113 of {@code app/cbl/CBSTM03A.CBL} as
     * {@code 1.234.567,89} where the reference renders {@code 1234567.89} -- the same number,
     * different bytes, and a byte comparison is the only thing that would notice. Plain
     * concatenation has no locale to consult, so the question does not arise here at all; there is
     * consequently no format string and no formatter anywhere in this file.</p>
     *
     * @param content the assembled content of one record, at or below the declared record length
     * @return a freshly allocated record of exactly {@value #HTML_RECORD_LENGTH} bytes, the content
     *     followed by blanks, never {@code null}
     * @throws IllegalStateException if {@code content} is longer than
     *     {@value #HTML_RECORD_LENGTH} characters, which reports a defect in this class rather than
     *     a fault of the caller, since every declared fragment and every assembled cell of this
     *     artifact is shorter than the declared length
     */
    private static byte[] toRecord(String content) {
        if (content.length() > HTML_RECORD_LENGTH) {
            throw new IllegalStateException("record content is " + content.length()
                    + " characters but app/cbl/CBSTM03A.CBL declares " + HTML_RECORD_LENGTH + ": "
                    + content);
        }

        // WHY : Assumptions: the padding is appended here rather than left to a caller because the
        //       declared length is a property of the data set, not of any one cell, so a cell that
        //       knew its own padding would have to know the record it lands in. Line 94 of
        //       app/jcl/CREASTMT.JCL fixes that length for every record alike.
        String padded = content + " ".repeat(HTML_RECORD_LENGTH - content.length());
        return padded.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Rejects a value that does not arrive at the exact declared width of its item.
     *
     * <p>Assumptions: the check is for an exact width rather than a maximum one, and the difference
     * matters here. A short value would still be padded out by the record assembly, so the artifact
     * would look correct while the closing tag of a cell sat at the wrong column; both regimes above
     * depend on the declared blanks being present in full.</p>
     *
     * @param value the value to check; must not be {@code null}
     * @param declaredWidth the width the reference item declares, in characters
     * @param component the component name to report, so that a rejection names the value rather
     *     than only its width
     * @throws IllegalArgumentException if {@code value} is not exactly {@code declaredWidth}
     *     characters
     */
    private static void requireExactWidth(String value, int declaredWidth, String component) {
        if (value.length() != declaredWidth) {
            throw new IllegalArgumentException(component + " must be exactly " + declaredWidth
                    + " characters at its declared width but was " + value.length());
        }
    }
}
