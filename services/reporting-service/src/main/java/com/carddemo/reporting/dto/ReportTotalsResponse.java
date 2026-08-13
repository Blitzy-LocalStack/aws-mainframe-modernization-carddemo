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
 * constant name -- for the two irregularities that defeat any such derivation, see {@link Band}.
 * {@code 'Page Total'} sits in an {@code X(11)} carrier at {@code app/cpy/CVTRA07Y.cpy} L51,
 * {@code 'Account Total'} in an {@code X(13)} carrier at L57 and {@code 'Grand Total'} in an
 * {@code X(11)} carrier at L63. None is normalised, retitled nor merged.</p>
 *
 * <p>Assumptions: the {@code @Size(max = 13)} bound on {@code label} is the widest of those three
 * declared carriers rather than a chosen ceiling, so a single component serving all three bands is
 * bounded by the largest of them. Reading 13 as a general field width would be a misreading: it is
 * the width of one specific literal.</p>
 *
 * <p>Assumptions: that constraint is retained on the generated field and nowhere else, which is
 * where a schema generator reading field annotations finds it. The annotation's {@code @Target}
 * list does not admit a record component, so it cannot be read back from one; and a component
 * annotation propagates onto an accessor or a constructor parameter only where that member is
 * implicitly declared, which neither is here because both are written out below. A check that
 * looked on the record component would report it missing while it is present and correct.
 * {@link TransactionReportLineResponse} resolves the same way for the same reason.</p>
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
 * {@code PIC S9(09)V99}, at L134 to L136, and one detail transaction feeds two of them in a single
 * statement -- {@code ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL} at L287 to L288 -- so the
 * page and account subtotals advance together rather than in separate passes. The page band is
 * emitted on a depth test at L282 against {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at L131 to
 * L132.</p>
 *
 * <p>Assumptions: {@code GRAND} is a total of page totals and not a total of transactions. The page
 * paragraph writes its line and only then performs {@code ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL} at
 * L297, clearing the page accumulator at L298, so the grand accumulator is fed once per page.
 * Preserving that path matters under transformation rule T4, which requires the arithmetic order to
 * survive the move.</p>
 *
 * <p>Assumptions: the account subtotal never feeds the grand total, and the evidence is an absence
 * rather than a statement: the account paragraph at L306 to L316 moves, writes and clears its
 * accumulator and contains no {@code ADD} into {@code WS-GRAND-TOTAL} at all. Since every amount
 * has already reached the grand total through its page band, re-summing the account bands into it
 * would count every transaction on the report twice.</p>
 *
 * <p>Assumptions: a band consumes report lines of its own -- each write advances the line counter
 * and re-emits {@code TRANSACTION-HEADER-2} -- so the bands are interleaved with the detail lines
 * rather than appended after them. A consumer assembling these responses in sequence is reproducing
 * an interleaving, not decorating a flat list.</p>
 *
 * <p>Trade-offs: the totals band stays a separate response type from the detail line even though
 * both amounts are 15 characters wide and land in the same columns. The three band amounts are
 * edited {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} at {@code app/cpy/CVTRA07Y.cpy} L54, L60 and L66, where the
 * fixed plus position always prints a sign, positive or negative alike. The detail figure is edited
 * {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} at L30, where the fixed minus position prints a sign for a negative
 * value and a blank for a positive one. Those are two different sign disciplines over the same
 * span, so one shared amount-line type would have to carry a discriminator telling a renderer which
 * mask to apply, putting a presentation concern into the payload. {@link
 * TransactionReportLineResponse} is the sibling response carrying the minus-leading form, and it
 * records the same distinction from its own side.</p>
 *
 * <p>Assumptions: the suppression semantics of these masks are not what a general-purpose formatter
 * produces, and the difference is visible at zero. Each {@code Z} suppresses a leading zero to a
 * blank rather than to a zero character; there is no {@code 9} position anywhere in the mask, the
 * cents included; and a comma to the left of the first significant digit is blanked as well. A band
 * whose transactions sum to zero therefore renders as 15 blanks, printing its label and its dot
 * leader and nothing else, rather than as any zero-bearing text. The greatest magnitude the mask
 * can render is {@code 999,999,999.99}, which is exactly the {@code S9(09)V99} accumulator domain
 * at L134 to L136.</p>
 *
 * <p>Assumptions: four numeric regimes coexist in this context and none substitutes for another --
 * the report's {@code Z}-suppressed masks in both sign forms, unsigned {@code 9(nn)} fields that
 * preserve leading zeros, the statement's trailing-sign non-comma-grouped masks at
 * {@code app/cbl/CBSTM03A.CBL} L113 and L137, and {@link Money} at scale two under
 * {@code RoundingMode.HALF_UP} written as quoted text. One Java representation cannot reproduce all
 * four display contracts, which is why this record holds a value and states which regime renders it
 * rather than holding rendered text.</p>
 *
 * <p>Alternatives Considered: emitting {@code amount} as a JSON number was rejected in favour of
 * the quoted string written by {@code com.carddemo.common.money.MoneyModule}. Most clients parse a
 * JSON number into an IEEE-754 binary floating point value at the boundary, surrendering at the
 * last hop the exactness every earlier hop preserved, and the margin does not permit it here: the
 * accumulators at {@code app/cbl/CBTRN03C.cbl} L134 to L136 are eleven significant digits each, a
 * grand total is a sum of sums, and binary64 offers roughly 15 to 17 digits, so nothing is left
 * over once a client chains its own arithmetic onto the value. This record therefore carries
 * {@link Money} and declares no serializer of its own.</p>
 *
 * <p>Trade-offs: no magnitude bound is asserted on {@code amount} here, even though the mask has a
 * hard ceiling. {@code CobolEditMask.formatReportTotalAmount} already rejects a total needing more
 * than nine integer digits, and {@link Money} legitimately admits a wider domain than this report
 * because the widest money field in the base masters is {@code ACCT-CURR-BAL PIC S9(10)V99} at
 * {@code app/cpy/CVACT01Y.cpy} L7. Repeating the ceiling here would give one invariant two
 * enforcement sites that could drift apart, and would surface an accumulation overflow at the
 * serialisation boundary instead of where the figure was produced. What this record asserts instead
 * is the invariant it alone can enforce, that the label and the band agree.</p>
 *
 * <p>Assumptions: a report band and an API cursor are different mechanisms that happen to share a
 * word, and this is the one place the distinction is drawn. {@code REPORT-PAGE-TOTALS} at
 * {@code app/cpy/CVTRA07Y.cpy} L50 to L54 is an output band whose twenty-detail depth is fixed by
 * {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at {@code app/cbl/CBTRN03C.cbl} L131 to L132, so
 * {@code PAGE} names a report-formatting concept belonging to the printed artifact. API traversal
 * in this context is positioned by an opaque cursor instead, serving a different consumer entirely.
 * This response accordingly carries no ordinal and no band depth.</p>
 *
 * <p>Assumptions: report ordering carries a determinism requirement, because the sort at
 * {@code app/jcl/TRANREPT.jcl} L46 names the card number alone and no {@code EQUALS}; Java ordering
 * adds the transaction identifier as a stable secondary key so that golden comparison is
 * reproducible. {@link TransactionReportLineResponse} carries the fuller statement.</p>
 *
 * <p>Assumptions: this context owns no table, no index and no schema-migration artifact, reading
 * read-only cross-schema views under a select-only database role. The {@code reporting} schema is
 * owned by the non-login role {@code carddemo_reporting_owner}, created with this service's login
 * and its usage grant by {@code data-migration/sql/V0__schemas_and_roles.sql}, while the views and
 * their {@code SELECT} grants come from {@code data-migration/sql/V1__reporting_views.sql}. This
 * record therefore runs no query and imports no data-access type, which applies the no-persistence
 * decision recorded in the register at {@code com.carddemo.reporting.dto}.</p>
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
     * <p>Each constant corresponds to one reference group and one writing paragraph in the report
     * driver: {@code PAGE} to L50 and L293, emitted every twenty detail lines by the depth test at
     * L282; {@code ACCOUNT} to L56 and L306, emitted on the control break recorded below;
     * {@code GRAND} to L62 and L318, written once. The declarations are in
     * {@code app/cpy/CVTRA07Y.cpy} and the paragraphs in {@code app/cbl/CBTRN03C.cbl}; which figure
     * feeds which is stated on the type.</p>
     *
     * <p>Assumptions: the {@code ACCOUNT} band breaks on the card number rather than on an account,
     * and this is an observation about the reference baseline rather than an asserted defect. The
     * control variable is {@code WS-CURR-CARD-NUM PIC X(16)} at {@code app/cbl/CBTRN03C.cbl} L137,
     * a 16-byte card number; the sort feeding the report orders by {@code TRAN-CARD-NUM} alone at
     * {@code app/jcl/TRANREPT.jcl} L46; and the source transaction carries no account identifier
     * whatsoever, declaring {@code TRAN-CARD-NUM} at {@code app/cpy/CVTRA05Y.cpy} L15 among its
     * fifteen fields and nothing account-keyed anywhere, which is why the report obtains an account
     * identifier for a detail line by cross-reference lookup at {@code app/cbl/CBTRN03C.cbl} L364
     * instead. A per-account break was therefore never available to the baseline. The baseline
     * breaks on the card number under the label {@code 'Account Total'} declared at
     * {@code app/cpy/CVTRA07Y.cpy} L57; the Java encodes the same card-number break and keeps that
     * literal verbatim. Substituting a per-account grouping would produce output differing from the
     * golden master for every account holding more than one card. The register for documented
     * divergences is {@code docs/architecture/cobol-to-service-traceability.md}, which this type
     * cites and never redefines.</p>
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
     * never fail and would be unreachable. It does not make the {@code @Size} constraint redundant:
     * that annotation is the declared width a schema generator publishes in the API contract, while
     * this check is the runtime guarantee protecting the byte-level report.</p>
     *
     * <p>Assumptions: nullness is enforced here rather than by a constraint annotation, because a
     * response payload is not routed through a validator on its way out, and the annotation
     * retention recorded on the type above shows the concern is not theoretical: a component
     * constraint reaches only the generated field, so nothing on the path a response value actually
     * takes would consult it. A constructor check sits directly on that path.</p>
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

    /**
     * Renders the band and its label WITHOUT the total.
     *
     * <p>Purpose. The amount is a monetary value and is withheld by
     * {@code docs/architecture/observability.md} L1093 to L1112. A total is the most consequential single
     * figure this context produces -- it is a sum over a date range for an account or a whole portfolio --
     * so the compiler-generated rendering put the report's headline number into any line that stringified
     * one of these.</p>
     *
     * <p>Assumptions: the band and its label are kept and disclose nothing. The band is a closed
     * enumeration naming which subtotal this is, and the label is the reference report's own literal
     * heading carried across character for character; neither is derived from a row.</p>
     *
     * <p>Trade-offs: what is lost is the ability to reconcile a report from a log, which is a real cost for
     * a reporting context in particular. It is accepted because the total is published in the response and
     * written into the report artifact, so a reconciliation reads the artifact -- the authoritative
     * output -- rather than a line about it.</p>
     *
     * @return a rendering naming the band and its label, with the total omitted; never {@code null}
     */
    @Override
    public String toString() {
        return "ReportTotalsResponse[band=" + this.band + ", label=" + this.label + ']';
    }
}
