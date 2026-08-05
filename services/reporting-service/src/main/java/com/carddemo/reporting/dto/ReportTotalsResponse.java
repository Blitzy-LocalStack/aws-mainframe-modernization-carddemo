package com.carddemo.reporting.dto;

import com.carddemo.common.money.Money;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * Carries one subtotal band of the 133-column daily transaction report as an API response.
 *
 * <p>The reference report emits exactly three subtotal bands, and this one type describes all
 * three, discriminated by {@link Band}. The declared line width is
 * {@code TRANSACTION-HEADER-2 PIC X(133)} at {@code app/cpy/CVTRA07Y.cpy} L48, corroborated by
 * {@code WS-BLANK-LINE PIC X(133)} at {@code app/cbl/CBTRN03C.cbl} L133 and by
 * {@code DCB=(LRECL=133,...)} at {@code app/jcl/TRANREPT.jcl} L78. It is a declared width, and it
 * is never arrived at by adding component widths together.</p>
 *
 * <p>Nothing here computes a total. The figure in {@code amount} is already final when it reaches
 * this record, accumulation belongs to {@code com.carddemo.reporting.service}, and the edit mask
 * that renders the figure belongs to {@code com.carddemo.reporting.mapper.CobolEditMask}. The
 * accumulation order is nevertheless written out below, because a reader of this contract cannot
 * otherwise know what {@code GRAND} is a total of.</p>
 *
 * <p>Assumptions: the band roster is closed at three because the reference layout declares three
 * and no more. {@code app/cpy/CVTRA07Y.cpy} declares {@code 01 REPORT-PAGE-TOTALS.} at L50,
 * {@code 01 REPORT-ACCOUNT-TOTALS.} at L56 and {@code 01 REPORT-GRAND-TOTALS.} at L62, and the
 * report driver has one writing paragraph for each, at {@code app/cbl/CBTRN03C.cbl} L293, L306 and
 * L318. Transformation rule T1 makes the copybook normative, so a fourth band is not an extension
 * point but a value the byte-level report has no line for.</p>
 *
 * <p>Assumptions: the three label literals are transported character for character under
 * transformation rule T8, and each is held as data on {@link Band} rather than derived from a
 * constant name. {@code 'Page Total'} sits in an {@code X(11)} carrier at
 * {@code app/cpy/CVTRA07Y.cpy} L51, {@code 'Account Total'} in an {@code X(13)} carrier at L57 and
 * {@code 'Grand Total'} in an {@code X(11)} carrier at L63. Two irregularities defeat any naming
 * convention that might otherwise generate them: {@code 'Account Total'} is two words separated by
 * a single space where the constant is one word, and all three literals are singular where their
 * enclosing groups are plural. Neither is normalised, retitled nor merged.</p>
 *
 * <p>Assumptions: the {@code @Size(max = 13)} bound on {@code label} is the widest of the three
 * declared carriers rather than a chosen ceiling. The three widths are 11 at L51, 13 at L57 and 11
 * at L63, so a single component serving all three bands is bounded by the largest of them. Reading
 * 13 as a general field width would be a misreading: it is the width of one specific literal.</p>
 *
 * <p>Assumptions: that constraint is retained on the generated field and nowhere else, which is
 * where a schema generator reading field annotations finds it. Two language rules combine to put it
 * there and both are easy to trip over. The annotation's own {@code @Target} list admits a method,
 * a field, an annotation type, a constructor, a parameter and a type use, but not a record
 * component, so it cannot be read back from the record component at all; and a component
 * annotation propagates onto an accessor or a constructor parameter only where that member is
 * implicitly declared, which neither is here because both are written out below. A check that
 * looked for the constraint on the record component would therefore report it missing while it is
 * present and correct, so such a check has to read the field. {@link
 * TransactionReportLineResponse} resolves the same way for the same reason, so the two types are
 * consistent rather than merely similar.</p>
 *
 * <p>Assumptions: the dot-leader widths deliberately differ, and they compensate the label widths
 * so that all three amounts occupy the same columns. The leaders are {@code X(86)} at
 * {@code app/cpy/CVTRA07Y.cpy} L53, {@code X(84)} at L59 and {@code X(86)} at L65, each
 * {@code VALUE ALL '.'}, and the arithmetic is the proof: 11 plus 86 is 97, and 13 plus 84 is also
 * 97. Every band therefore starts its 15-character amount at column 98 and finishes it at column
 * 112. Tidying the three leaders to one width would shift the {@code 'Account Total'} amount two
 * columns and break byte comparison against the golden master, which is why the relationship is
 * recorded on the type a reader reaches first even though the rendering itself lives in the mapper
 * package.</p>
 *
 * <p>Assumptions: the accumulation order behind these bands is not the obvious one, and the
 * reference driver settles it. {@code app/cbl/CBTRN03C.cbl} declares three accumulators, all
 * {@code PIC S9(09)V99}: {@code WS-PAGE-TOTAL} at L134, {@code WS-ACCOUNT-TOTAL} at L135 and
 * {@code WS-GRAND-TOTAL} at L136. One detail transaction feeds two of them in a single statement,
 * {@code ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL} spanning L287 to L288, so the two
 * subtotals advance together rather than in separate passes. The page band is emitted on a depth
 * test, {@code IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0} at L282 against
 * {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at L131 to L132, which writes the band at L283
 * and a fresh header block at L284.</p>
 *
 * <p>Assumptions: {@code GRAND} is a total of page totals and not a total of transactions. The
 * page paragraph at L293 moves the accumulator into its band at L294, stages and writes the line
 * at L295 to L296, and only then performs {@code ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL} at L297
 * before clearing the page accumulator at L298. The grand accumulator is consequently fed once per
 * page rather than once per transaction, and the grand paragraph at L318 merely moves it at L319
 * and stages and writes it at L320 to L321. Preserving that path matters under transformation rule
 * T4, which requires the arithmetic order to survive the move.</p>
 *
 * <p>Assumptions: the account subtotal never feeds the grand total, and the evidence is an absence
 * rather than a statement. The account paragraph runs from L306 to L316: it moves the accumulator
 * at L307, stages and writes the line at L308 to L309, and clears the accumulator at L310. Read in
 * full, that paragraph contains no {@code ADD} into {@code WS-GRAND-TOTAL} at all. Since every
 * amount has already reached the grand total through its page band, re-summing the account bands
 * into it would count every transaction on the report twice.</p>
 *
 * <p>Assumptions: a band consumes report lines of its own, so the bands are interleaved with the
 * detail lines rather than appended after them. Each band write advances the line counter, at L299
 * and L302 in the page paragraph and at L311 and L314 in the account paragraph, and re-emits
 * {@code TRANSACTION-HEADER-2} at L300 and L312. A consumer assembling these responses in
 * sequence is therefore reproducing an interleaving, not decorating a flat list.</p>
 *
 * <p>Trade-offs: the totals band stays a separate response type from the detail line even though
 * both amounts are 15 characters wide and land in the same columns. The three band amounts are
 * edited {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} at {@code app/cpy/CVTRA07Y.cpy} L54, L60 and L66, where the
 * fixed plus position always prints a sign, positive or negative alike. The detail figure is
 * edited {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} at L30, where the fixed minus position prints a sign for a
 * negative value and a blank for a positive one. Those are two different sign disciplines over the
 * same span, so one shared amount-line type would have to carry a discriminator telling a renderer
 * which mask to apply, putting a presentation concern into the payload. {@link
 * TransactionReportLineResponse} is the sibling response carrying the minus-leading form, and it
 * records the same distinction from its own side.</p>
 *
 * <p>Assumptions: the suppression semantics of these masks are not what a general-purpose
 * formatter produces, and the difference is visible at zero. Each {@code Z} suppresses a leading
 * zero to a blank rather than to a zero character; there is no {@code 9} position anywhere in the
 * mask, the cents included; and a comma to the left of the first significant digit is blanked as
 * well. A band whose transactions sum to zero therefore renders as 15 blanks, printing its label
 * and its dot leader and nothing else, rather than as any zero-bearing text. The greatest
 * magnitude the mask can render is {@code 999,999,999.99}, which is exactly the
 * {@code S9(09)V99} accumulator domain at L134 to L136.</p>
 *
 * <p>Assumptions: four numeric regimes coexist in this context and none substitutes for another.
 * The report uses the {@code Z}-suppressed masks above in their plus-leading and minus-leading
 * forms; unsigned {@code 9(nn)} fields preserve leading zeros, as
 * {@code TRAN-REPORT-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA07Y.cpy} L24 does; the statement
 * uses trailing-sign, non-comma-grouped masks at {@code app/cbl/CBSTM03A.CBL} L113 and L137; and
 * the API carries {@link Money} at scale two under {@code RoundingMode.HALF_UP}, written as quoted
 * text. One Java representation cannot reproduce all four display contracts, which is why this
 * record holds a value and states which regime renders it rather than holding rendered text.</p>
 *
 * <p>Alternatives Considered: emitting {@code amount} as a JSON number was evaluated and rejected
 * in favour of the quoted string written by {@code com.carddemo.common.money.MoneyModule}. Most
 * clients parse a JSON number into an IEEE-754 binary floating point value at the boundary, which
 * surrenders at the last hop the exactness every earlier hop preserved. The margin does not permit
 * it here: the accumulators are {@code PIC S9(09)V99} at {@code app/cbl/CBTRN03C.cbl} L134 to
 * L136, eleven significant digits each, a grand total is a sum of sums rather than a single
 * reading, and binary64 offers roughly 15 to 17 significant digits, so nothing is left over once a
 * client chains its own arithmetic onto the value. The encoding is applied by the module
 * registration recorded in the decision register at {@code com.carddemo.reporting.dto}, so this
 * record carries {@link Money} and declares no serializer of its own.</p>
 *
 * <p>Trade-offs: no magnitude bound is asserted on {@code amount} here, even though the mask has a
 * hard ceiling. {@code CobolEditMask.formatReportTotalAmount} in
 * {@code com.carddemo.reporting.mapper} already rejects a total whose magnitude needs more than
 * nine integer digits, and {@link Money} legitimately admits a wider domain than this report
 * because the widest money field in the base masters is {@code ACCT-CURR-BAL PIC S9(10)V99} at
 * {@code app/cpy/CVACT01Y.cpy} L7, twelve significant digits against this report's eleven.
 * Repeating the ceiling on this component would give one
 * invariant two enforcement sites that could drift apart, and it would surface an accumulation
 * overflow at the serialisation boundary instead of where the figure was produced. What is
 * asserted here instead is the invariant this record alone can enforce, namely that the label and
 * the band agree.</p>
 *
 * <p>Assumptions: a report band and an API cursor are different mechanisms that happen to share a
 * word, and this is the one place the distinction is drawn. {@code REPORT-PAGE-TOTALS} at
 * {@code app/cpy/CVTRA07Y.cpy} L50 to L54 is an output band, and
 * {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at {@code app/cbl/CBTRN03C.cbl} L131 to L132
 * fixes its twenty-detail depth, so {@code PAGE} names a report-formatting concept that belongs to
 * the printed artifact. API traversal in this context is positioned by an opaque cursor instead,
 * serving a different consumer entirely. This response accordingly carries no ordinal and no band
 * depth, and it restores neither of the two baseline browse fields the migration drops,
 * {@code WS-CA-SCREEN-NUM} and {@code WS-CA-LAST-PAGE-DISPLAYED}, the second of which carries the
 * counter-intuitive polarity of shown {@code 0} against not-shown {@code 9}.</p>
 *
 * <p>Assumptions: report ordering carries a determinism requirement, because the sort at
 * {@code app/jcl/TRANREPT.jcl} L46 names the card number alone and no {@code EQUALS}, leaving rows
 * that share a card with no stable baseline tie order; Java ordering adds the transaction
 * identifier as a stable secondary key so that golden comparison is reproducible.
 * {@link TransactionReportLineResponse} carries the fuller statement of this.</p>
 *
 * <p>Assumptions: this context owns no table, no index and no schema-migration artifact, reading
 * read-only cross-schema views under a select-only database role, as
 * {@code services/reporting-service/src/main/resources/application.yml} sets out at L70 to L83:
 * the {@code reporting} schema exists but is owned by the non-login role
 * {@code carddemo_reporting_owner}, and this service holds usage on the schema and read access to
 * its named views alone. Correspondingly there is no {@code db/migration} directory on this
 * service's classpath at all. This record therefore runs no query and imports no data-access type
 * and no view projection, which applies the no-persistence decision recorded in the register at
 * {@code com.carddemo.reporting.dto}.</p>
 *
 * <p>Documentation label spelling follows {@code docs/CODE_DOCUMENTATION_STANDARD.md}. A type
 * declaration accepts no argument, returns no value and raises nothing; the three component
 * parameters are documented below.</p>
 *
 * @param band the subtotal band this response describes, discriminating the three groups declared
 *     at {@code app/cpy/CVTRA07Y.cpy} L50, L56 and L62; never {@code null}
 * @param label the verbatim band label as declared at {@code app/cpy/CVTRA07Y.cpy} L51, L57 or
 *     L63, at most 13 characters because that is the widest of the three declared carriers; it
 *     always equals the literal {@code band} declares, and the constructor enforces that
 * @param amount the accumulated figure for this band, carried as exact scale-two {@link Money} and
 *     written on the wire as quoted text; for {@code GRAND} this is a sum of page totals, per the
 *     accumulation path at {@code app/cbl/CBTRN03C.cbl} L297
 */
public record ReportTotalsResponse(
        Band band,
        @Size(max = 13) String label,
        Money amount) {

    /**
     * Names the three subtotal bands the daily transaction report emits.
     *
     * <p>Each constant corresponds to one reference group and to one writing paragraph in the
     * report driver:</p>
     *
     * <ul>
     *   <li>{@code PAGE} is declared as {@code 01 REPORT-PAGE-TOTALS.} at
     *       {@code app/cpy/CVTRA07Y.cpy} L50 and written by the paragraph at
     *       {@code app/cbl/CBTRN03C.cbl} L293. It is emitted every twenty detail lines by the depth
     *       test at L282, and its figure is the one that feeds the grand total at L297.</li>
     *   <li>{@code ACCOUNT} is declared as {@code 01 REPORT-ACCOUNT-TOTALS.} at
     *       {@code app/cpy/CVTRA07Y.cpy} L56 and written by the paragraph at
     *       {@code app/cbl/CBTRN03C.cbl} L306. It is emitted on a control break, and its figure is
     *       never added into the grand total. What it breaks on is recorded below.</li>
     *   <li>{@code GRAND} is declared as {@code 01 REPORT-GRAND-TOTALS.} at
     *       {@code app/cpy/CVTRA07Y.cpy} L62 and written once by the paragraph at
     *       {@code app/cbl/CBTRN03C.cbl} L318. Its figure is a sum of page totals.</li>
     * </ul>
     *
     * <p>Assumptions: the {@code ACCOUNT} band breaks on the card number rather than on an
     * account, and this is an observation about the reference baseline rather than an asserted
     * defect. The control variable is {@code WS-CURR-CARD-NUM PIC X(16)} at
     * {@code app/cbl/CBTRN03C.cbl} L137, a 16-byte card number; the sort feeding the report orders
     * by {@code TRAN-CARD-NUM} alone at {@code app/jcl/TRANREPT.jcl} L46; and the source
     * transaction carries no account identifier whatsoever, declaring {@code TRAN-CARD-NUM} at
     * {@code app/cpy/CVTRA05Y.cpy} L15 among its fifteen fields and nothing account-keyed anywhere,
     * which is why the report obtains an account identifier for a detail line by cross-reference
     * lookup at {@code app/cbl/CBTRN03C.cbl} L364 instead. A per-account break was therefore never
     * available to the baseline. The baseline breaks on the card number under the label
     * {@code 'Account Total'} declared at {@code app/cpy/CVTRA07Y.cpy} L57; the Java encodes the
     * same card-number break and keeps that literal verbatim. Substituting a per-account grouping
     * would produce output differing from the golden master for every account holding more than one
     * card. The register for documented divergences is
     * {@code docs/architecture/cobol-to-service-traceability.md}, which this type cites and never
     * redefines.</p>
     */
    public enum Band {

        // WHY : Assumptions: each literal is supplied as a constructor argument, so the label is
        //       data held beside its constant rather than a string derived from the constant's own
        //       name. Two irregularities defeat any such derivation: 'Account Total' at
        //       app/cpy/CVTRA07Y.cpy L57 is two words separated by a single space where the
        //       constant is one word, and all three literals are singular where their enclosing
        //       groups at L50, L56 and L62 are plural. Encoding both as naming rules would put a
        //       verbatim reference string behind a transformation.
        PAGE("Page Total"),
        ACCOUNT("Account Total"),
        GRAND("Grand Total");

        private final String reportLabel;

        /**
         * Binds a band to the verbatim label its reference group declares.
         *
         * @param reportLabel the label literal for this band, exactly as declared at
         *     {@code app/cpy/CVTRA07Y.cpy} L51, L57 or L63
         */
        Band(String reportLabel) {
            this.reportLabel = reportLabel;
        }

        /**
         * Returns the verbatim report label this band declares.
         *
         * @return the label literal for this band, never {@code null} and never longer than the
         *     13 characters of the widest declared carrier
         */
        public String reportLabel() {
            return reportLabel;
        }
    }

    /**
     * Creates a subtotal band response, rejecting any label that is not the literal its band
     * declares.
     *
     * <p>Trade-offs: the label travels on the wire even though {@link Band} already determines it,
     * and checking the two against each other here is what makes that redundancy safe. Carrying it
     * keeps the response self-describing, so a consumer renders a band line without holding its own
     * copy of the constant-to-literal mapping; checking it means the redundancy can never become a
     * disagreement. Deriving the label on the consumer side was the alternative, and it was
     * rejected because it would place the three verbatim literals declared at
     * {@code app/cpy/CVTRA07Y.cpy} L51, L57 and L63 outside this service, where the
     * character-for-character guarantee of transformation rule T8 could no longer be audited in one
     * place.</p>
     *
     * <p>Assumptions: this equality check subsumes a blank test and a length test, so neither is
     * written separately. A value equal to one of the three literals at
     * {@code app/cpy/CVTRA07Y.cpy} L51, L57 and L63 is necessarily non-blank and necessarily within
     * the 13-character bound that the widest of them sets, so a separate test for either could
     * never fail and would be unreachable. The {@code @Size} constraint on the component is not
     * made redundant by this: the annotation is the declared width a schema generator reads off the
     * generated field and publishes in the API contract, while this check is the runtime guarantee
     * protecting the byte-level report.</p>
     *
     * <p>Assumptions: nullness is enforced here rather than by a constraint annotation, because a
     * response payload is not routed through a validator on its way out. A constraint annotation
     * would state an intention that nothing evaluates, and the {@code @Size} retention finding
     * recorded on the type above shows why that is not merely a theoretical concern: on this record
     * a component constraint reaches the generated field and neither the accessor nor the
     * constructor parameter, so nothing on the path a response value actually takes would consult
     * it. A constructor check sits directly on that path and cannot be bypassed.</p>
     *
     * @param band the subtotal band to describe; must not be {@code null}
     * @param label the verbatim band label to carry; must not be {@code null} and must equal the
     *     literal that {@code band} declares
     * @param amount the accumulated figure for the band; must not be {@code null}, and a zero
     *     figure is a legitimate value that renders as 15 blanks rather than as zero-bearing text
     * @throws NullPointerException if {@code band}, {@code label} or {@code amount} is {@code null}
     * @throws IllegalArgumentException if {@code label} is not the literal that {@code band}
     *     declares
     */
    public ReportTotalsResponse(Band band, String label, Money amount) {
        Objects.requireNonNull(band, "band must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (!band.reportLabel().equals(label)) {
            // WHY : Assumptions: the message names both the expected and the received literal
            //       because the two differ by whitespace or by case in every realistic failure,
            //       and a message reporting only the rejected value would leave a caller unable to
            //       see which of the three declared literals it was measured against.
            throw new IllegalArgumentException(
                    "label must be the literal declared for band " + band + ": expected '"
                            + band.reportLabel() + "' but received '" + label + "'");
        }
        this.band = band;
        this.label = label;
        this.amount = amount;
    }

    /**
     * Returns the subtotal band this response describes.
     *
     * @return the band discriminating which of the three reference groups this response carries,
     *     never {@code null}
     */
    public Band band() {
        return band;
    }

    /**
     * Returns the verbatim band label this response carries.
     *
     * @return the label literal, never {@code null} and always equal to the literal that
     *     {@link #band()} declares
     */
    public String label() {
        return label;
    }

    /**
     * Returns the accumulated figure for this band.
     *
     * @return the exact scale-two amount, never {@code null}, written on the wire as quoted text
     */
    public Money amount() {
        return amount;
    }
}
