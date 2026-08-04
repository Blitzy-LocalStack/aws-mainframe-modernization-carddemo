package com.carddemo.reporting.mapper;

import com.carddemo.common.money.Money;
import java.util.Objects;

/**
 * Numeric edit masks for the daily transaction report and the cardholder statement.
 *
 * <p>This class is the sole home of every report and statement numeric edit mask in this
 * service. Each method takes one value and returns the edited character string that a single
 * declaring clause produces for it, at that clause's declared width and byte for byte. Nothing
 * here places a string into a record, reads a clock, or performs input or output. Placement is
 * the concern of the band descriptors and the artifact mappers that call these methods.</p>
 *
 * <h2>Seven regimes, and what that count is a count of</h2>
 *
 * <p>Assumptions: the seven regimes below are the DISPLAY and EDIT forms this module EMITS.
 * They are not a count of the ways money is represented in the system, and the number must not
 * be reconciled against the counts the shared kernel's codec package publishes for itself. That
 * package owns storage and wire contracts -- zoned sign overpunch, packed decimal, and one
 * edited comma-separated form -- which is a different axis entirely, and four of the seven
 * regimes below are named by no codec at all. The two counts describe different things, so a
 * reader who adds them together arrives at a number that means nothing.</p>
 *
 * <pre>
 * #  declared form         width  leading zeros  sign             declared at
 * 1  -ZZZ,ZZZ,ZZZ.ZZ          15  blanked        leading, minus   CVTRA07Y line 30
 *                                                only
 * 2  +ZZZ,ZZZ,ZZZ.ZZ          15  blanked        leading, always  CVTRA07Y lines 54,
 *                                                                 60 and 66
 * 3  9(nn)                    nn  preserved      none             CVTRA07Y line 24,
 *                                                                 CVACT03Y line 7
 * 4  EDIT=(TTTTTTTTT.TT)      12  preserved      none             PRTCATBL line 56
 * 5  +99999999.99             12  preserved      leading, always  CORPT00C line 77
 * 6  9(9).99-                 13  preserved      trailing, minus  CBSTM03A line 113
 *                                                only
 * 7  Z(9).99-                 13  blanked        trailing, minus  CBSTM03A lines 137
 *                                                only             and 142
 * </pre>
 *
 * <h2>Why byte-for-byte matters more here than the word "formatting" suggests</h2>
 *
 * <p>Assumptions: an error in this class is silent. A formatter that emits a zero where the
 * reference emits a blank, or omits a leading plus, yields an artifact differing from the golden
 * output in bytes a human reader would call "the same number", and every column written after it
 * lands one position out. The behaviour of each regime was therefore not inferred from a reading
 * of the editing rules. Each declaring clause was compiled and executed under the same compiler
 * and the same flags the reference suite itself uses -- GnuCOBOL 3.2.0 with the strict IBM
 * dialect and EBCDIC sign handling -- and the emitted bytes were captured. Every example string
 * in this file is one of those captured outputs, delimited by a vertical bar at each end so that
 * the blanks are countable rather than merely described.</p>
 *
 * <h2>The report sign is LEADING; the statement sign is TRAILING</h2>
 *
 * <p>Assumptions: the two artifacts place their sign at opposite ends of the value and carry
 * different widths, so no method of one is ever a substitute for a method of the other. Regimes
 * 1 and 2 are 15 characters with the sign in the FIRST position, as declared at lines 30, 54, 60
 * and 66 of {@code app/cpy/CVTRA07Y.cpy}. Regimes 6 and 7 are 13 characters with the sign in the
 * LAST position, as declared at lines 113, 137 and 142 of {@code app/cbl/CBSTM03A.CBL}. Passing
 * a statement amount through a report method, or the reverse, produces a string of the wrong
 * length with the sign at the wrong end -- which is exactly why the four are four methods and
 * not one method with a placement argument.</p>
 *
 * <h2>This class never emits a currency symbol</h2>
 *
 * <p>Assumptions: the dollar sign that appears beside a statement amount is not part of any mask
 * here. It is its own one-byte item, declared {@code FILLER PIC X(01)} with the literal value at
 * line 136 and again at line 141 of {@code app/cbl/CBSTM03A.CBL}, immediately preceding the
 * edited amount at lines 137 and 142. Emitting it from a mask method would place two bytes where
 * the band declares one item of one byte and one item of thirteen, and every column after it
 * would shift. The symbol is the band descriptor's item, so it is the band descriptor's
 * concern.</p>
 *
 * <h2>All seven live in one class</h2>
 *
 * <p>Alternatives Considered: one class per artifact -- report masks in one type, statement masks
 * in another, the sort-utility mask in a third -- was evaluated and rejected. The defining
 * property of these seven is that they COEXIST and are never interchangeable: the same 133-column
 * report emits a leading-minus detail amount from line 30 of {@code app/cpy/CVTRA07Y.cpy} and a
 * leading-plus total from lines 54, 60 and 66 of the same copybook, and the same 80-byte
 * statement emits a zero-preserving balance from line 113 of {@code app/cbl/CBSTM03A.CBL} beside
 * a zero-blanking amount from line 137. Splitting them across the files that call them would
 * scatter the one table in which that non-interchangeability is visible, and a maintainer
 * reaching for a mask would see one regime rather than the seven it has to be distinguished
 * from. The accepted cost is a larger single type.</p>
 *
 * <h2>No locale-sensitive formatter is constructed anywhere in this class</h2>
 *
 * <p>Assumptions: every edited string here is composed character by character from the decimal
 * digits of the value, using only a digit-to-character mapping that no locale can reinterpret.
 * Neither {@code java.text.DecimalFormat} nor {@code String.format} is used, so there is no call
 * on which a {@code Locale.ROOT} argument could be omitted. That absence is the whole point of
 * the choice, because the failure it removes is invisible: on a virtual machine whose default
 * locale groups with a period and separates the decimal places with a comma, a formatter left to
 * the default renders the amount one million two hundred thirty-four thousand five hundred
 * sixty-seven and eighty-nine hundredths as {@code 1.234.567,89} where the report declares
 * {@code 1,234,567.89}. The two strings are the same length, differ in two bytes, and read as
 * the same number to a human -- so the golden comparison would catch it and no reviewer
 * would.</p>
 *
 * <p>Assumptions: only one kind of test detects a regression against that hazard, so a test author
 * working from the example tables below should know which one it is. Running every method a
 * second time with the virtual machine's default locale set to one that groups with a period and
 * separates the decimal places with a comma, and asserting the output is byte-identical to the
 * first run, is the ONLY assertion that fails when a locale-sensitive formatter is reintroduced
 * without its root-locale argument. Every other assertion in this file's example tables passes on
 * a machine whose own default locale happens to agree with the artifact, which is why a suite
 * that omits the second run can be entirely green on one build agent and wrong on another.</p>
 *
 * <p>Trade-offs: the compromise accepted for that immunity is code volume and allocation
 * behaviour. A single shared {@code DecimalFormat} instance built once with
 * {@code DecimalFormatSymbols.getInstance(Locale.ROOT)} would express three of these regimes in
 * a pattern string instead of a loop, and would allocate nothing per call. It was rejected
 * because {@code DecimalFormat} is mutable and carries no thread-safety guarantee, and this
 * service composes report bands and statement bands concurrently, so a shared instance would
 * need either a lock on every amount or a thread-local holder -- and a per-invocation instance
 * allocates more than the loop it would replace. Correctness under concurrent band composition
 * decided it. The pattern languages are a second, independent reason: no
 * {@code #,##0.00}-family pattern blanks a grouping separator that stands to the left of the
 * first significant digit, and none blanks the decimal places of a zero value, so both regimes 1
 * and 2 would need a post-pass over the pattern's output. Composing the string directly makes
 * those two rules the loop's own logic, where each is one branch that a test can address.</p>
 *
 * <h2>This class performs no rounding and no rescaling</h2>
 *
 * <p>Assumptions: every method formats the value at the scale it already carries and rejects a
 * value carrying any other scale, rather than quietly reshaping it. All seven regimes declare
 * exactly two decimal positions, and {@link Money} holds its amount invariantly at
 * {@link Money#SCALE} decimal places, so the two agree by construction; the check is retained so
 * that the agreement is asserted at this boundary instead of inherited as an assumption about a
 * sibling module. It is also the reason no scale-adjusting call appears anywhere below: a mask
 * that silently reshaped its input would move the rounding decision out of the service layer
 * that owns it and into a formatter, where no reviewer would look for it.</p>
 *
 * <p>Assumptions: AAP Rule T4 (arithmetic order is preserved) imposes nothing on this class,
 * because this class is a pure formatter and performs no arithmetic on the value -- no product
 * is formed and no quotient is taken, so no ordering between them arises here. The rule binds
 * the callers instead: an amount must arrive already computed in the reference order, and a
 * caller must not pre-round an amount to make it presentable, because a mask that receives a
 * pre-rounded value cannot tell that it was rounded and will render the wrong cents without
 * complaint.</p>
 *
 * <h2>An unrepresentable magnitude raises rather than truncating</h2>
 *
 * <p>Alternatives Considered: reproducing the reference behaviour, which discards high-order
 * digits without signalling, was evaluated and rejected. The reference truncates: moving nine
 * integer digits into the eight-position mask at line 77 of {@code app/cbl/CORPT00C.cbl} yields
 * {@code |+99999999.99|} for an input of nine hundred ninety-nine million and change, a value
 * one thousandth of the original that is still a well-formed amount. That is precisely the
 * plausible-number-that-is-wrong this class exists to prevent, and it is unrecoverable
 * downstream because the truncated string carries no evidence of the digits it lost. Each method
 * therefore raises, naming the offending magnitude, its scale, and the mask that could not hold
 * it. The divergence from the reference is intentional and is registered with the other
 * documented divergences. It is a guard rather than an expected path for the report masks in
 * particular, because the three accumulators that feed them are declared {@code PIC S9(09)V99}
 * at lines 134, 135 and 136 of {@code app/cbl/CBTRN03C.cbl}, which is exactly the nine integer
 * positions both report masks provide. The guard is still reachable, because {@link Money}
 * admits ten integer digits by {@link Money#MAX_MAGNITUDE} where these masks provide nine.</p>
 *
 * <h2>Every method guarantees its declared width</h2>
 *
 * <p>Assumptions: a returned string of any length other than the declared width is a defect, so
 * each method asserts its own result before returning it. The artifacts these strings feed are
 * of a declared record length and are assembled by placing each item at a declared offset, so a
 * string one character short or long does not produce a slightly wrong field -- it shifts every
 * subsequent column of the record and corrupts the whole line. Asserting here names the defect
 * at the method that caused it rather than at the band that received it.</p>
 *
 * @see Money
 */
public final class CobolEditMask {

    // WHY : Assumptions: 15 is the declared width of the two report amount masks, and it is a
    //       count of the mask's own positions rather than a derived figure: one sign, nine digit
    //       positions, two grouping separators, one decimal point and two decimal positions, as
    //       declared at line 30 of app/cpy/CVTRA07Y.cpy and repeated at lines 54, 60 and 66. The
    //       constant is public so that a caller and a test assert against one source of truth
    //       instead of each restating the literal.
    public static final int REPORT_AMOUNT_WIDTH = 15;

    // WHY : Assumptions: 12 is the character count of the sort-utility edit pattern declared at
    //       line 56 of app/jcl/PRTCATBL.jcl, being nine digit selectors, one literal decimal
    //       point and two more digit selectors. It is deliberately a separate constant from the
    //       one below even though both equal 12: the two regimes differ in how many of those
    //       positions are integer digits, so a shared constant would invite exactly the
    //       substitution that silently narrows an amount by one decimal order of magnitude.
    public static final int SORT_EDITED_AMOUNT_WIDTH = 12;

    // WHY : Assumptions: 12 is the declared width of the mask at line 77 of
    //       app/cbl/CORPT00C.cbl, being one sign, eight digit positions, one decimal point and
    //       two decimal positions. Eight integer positions is one fewer than every other money
    //       mask in this class carries, which is why this regime must never stand in for a
    //       report amount.
    public static final int SIGNED_ZERO_FILLED_AMOUNT_WIDTH = 12;

    // WHY : Assumptions: 13 is the declared width shared by the two statement amount masks --
    //       nine digit positions, one decimal point, two decimal positions and one trailing sign
    //       -- declared at line 113 and at lines 137 and 142 of app/cbl/CBSTM03A.CBL. One
    //       constant serves both because the two masks genuinely share their shape and differ
    //       only in leading-zero treatment, which is a per-method behaviour rather than a width.
    public static final int STATEMENT_AMOUNT_WIDTH = 13;

    // WHY : Assumptions: nine integer positions is what both report masks declare, and it is the
    //       ceiling this class enforces for them. It matches the PIC S9(09)V99 accumulators at
    //       lines 134 to 136 of app/cbl/CBTRN03C.cbl and the PIC S9(09)V99 transaction amount at
    //       line 10 of app/cpy/CVTRA05Y.cpy that line 370 of that program moves into the detail
    //       mask, so the report's own data cannot overflow it.
    private static final int REPORT_INTEGER_DIGITS = 9;

    // WHY : Assumptions: the sort-utility pattern at line 56 of app/jcl/PRTCATBL.jcl carries nine
    //       digit selectors ahead of its decimal point, and its input is the eleven-digit zoned
    //       field declared at line 50 of the same job, so nine integer digits and two decimal
    //       digits consume the input exactly with nothing left over.
    private static final int SORT_EDITED_INTEGER_DIGITS = 9;

    // WHY : Assumptions: eight, not nine. The mask at line 77 of app/cbl/CORPT00C.cbl spells its
    //       integer positions out as eight consecutive digit positions, which is the narrowest
    //       money mask in this class and the one place where a value legal for every other
    //       method here is rejected.
    private static final int SIGNED_ZERO_FILLED_INTEGER_DIGITS = 8;

    // WHY : Assumptions: nine integer positions for both statement masks. The amounts that reach
    //       them fit exactly -- the transaction amount at line 29 of app/cpy/COSTM01.CPY and the
    //       accumulated total at line 65 of app/cbl/CBSTM03A.CBL are both nine integer digits --
    //       whereas the balance at line 7 of app/cpy/CVACT01Y.cpy carries ten, so a caller
    //       formatting a balance of a thousand million or more must decide how to narrow it
    //       rather than have this class decide silently.
    private static final int STATEMENT_INTEGER_DIGITS = 9;

    // WHY : Assumptions: all seven regimes declare exactly two decimal positions, so this is a
    //       property of the whole class rather than of any one mask -- the 2 suppression
    //       positions after the point at line 30 of app/cpy/CVTRA07Y.cpy and the 2 digit
    //       positions after the point at line 113 of app/cbl/CBSTM03A.CBL are the same count
    //       differently spelled. It is also the scale a supplied amount must already carry for
    //       this class to accept it.
    private static final int DECIMAL_DIGITS = 2;

    // WHY : Assumptions: the grouping separators in the two report masks fall every third digit
    //       counting leftward from the decimal point, which is what lines 30, 54, 60 and 66 of
    //       app/cpy/CVTRA07Y.cpy spell out as ZZZ,ZZZ,ZZZ. Naming the interval keeps the
    //       separator positions derived from the mask's shape rather than written as two
    //       hard-coded offsets that a change of width would silently invalidate.
    private static final int GROUPING_INTERVAL = 3;

    // WHY : Assumptions: eighteen is the widest unsigned picture this class accepts, chosen
    //       because every decimal integer of eighteen digits or fewer is exactly representable
    //       in the parameter type of the unsigned method, whereas nineteen digits is only
    //       partially representable. The two widths actually in use are far narrower, 9(04) at
    //       line 24 of app/cpy/CVTRA07Y.cpy and 9(11) at line 7 of app/cpy/CVACT03Y.cpy, so the
    //       bound rejects a nonsensical request without constraining any real caller.
    private static final int MAX_UNSIGNED_DIGITS = 18;

    // WHY : Assumptions: the six mask literals below are carried as constants so that the
    //       failure message of an unrepresentable magnitude names the mask in the same notation
    //       the declaring source uses. A maintainer who searches the reference tree for the
    //       15-character literal below lands on line 30 of app/cpy/CVTRA07Y.cpy, and for the
    //       13-character trailing-sign literal on line 113 of app/cbl/CBSTM03A.CBL; a prose
    //       description of either mask would not resolve to a line that way.
    private static final String REPORT_DETAIL_MASK = "-ZZZ,ZZZ,ZZZ.ZZ";

    private static final String REPORT_TOTAL_MASK = "+ZZZ,ZZZ,ZZZ.ZZ";

    private static final String SORT_EDITED_MASK = "EDIT=(TTTTTTTTT.TT)";

    private static final String SIGNED_ZERO_FILLED_MASK = "+99999999.99";

    private static final String STATEMENT_BALANCE_MASK = "9(9).99-";

    private static final String STATEMENT_AMOUNT_MASK = "Z(9).99-";

    /**
     * Prevents instantiation of this static mask holder.
     *
     * <p>Alternatives Considered: an instantiable class, or one exposing a shared instance, was
     * evaluated and rejected. Every mask here is a pure function of its argument and holds no
     * state, so an instance would advertise a lifecycle that does not exist and would invite
     * injection of something with nothing to inject. It would also reintroduce the very hazard
     * this class was shaped to avoid, because a stateful formatter shared between concurrently
     * composed report bands is the mutable-formatter problem under a different name. Declaring
     * the constructor private states the intent where the language enforces it, and the class is
     * final so that no subclass can reopen the decision.</p>
     */
    private CobolEditMask() {
    }

    /**
     * Formats a transaction amount for a report detail line, signing only a negative value.
     *
     * <p>Assumptions: this is regime 1, declared {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} at line 30 of
     * {@code app/cpy/CVTRA07Y.cpy} as the amount item of the detail band, and its input is the
     * transaction amount that line 370 of {@code app/cbl/CBTRN03C.cbl} moves into it -- declared
     * {@code PIC S9(09)V99} at line 10 of {@code app/cpy/CVTRA05Y.cpy}, so nine integer digits
     * into nine integer positions with nothing to lose. Three properties of the declared form are
     * load-bearing and none of them is what a default numeric rendering would produce. A leading
     * zero becomes a BLANK rather than a zero. A grouping separator standing to the left of the
     * first significant digit is ALSO blanked, so a four-digit value shows no separator at all
     * where the higher separator would have fallen. And the sign position prints a minus for a
     * negative value and a BLANK for a positive one, never a plus.</p>
     *
     * <p>Assumptions: a zero amount renders as {@value #REPORT_AMOUNT_WIDTH} blanks, the sign
     * position included. Every digit position of the mask at line 30 of
     * {@code app/cpy/CVTRA07Y.cpy} is a suppression position, the two decimal positions among
     * them, and the editing rules blank the entire item when such a mask receives zero. The
     * result is neither a zero, nor a bare decimal point with two zeros, nor a signed zero -- it
     * is a blank field, and that was confirmed by executing the declaring clause rather than
     * reasoned about. Suppression stops at the decimal point rather than crossing it, which is
     * why a value below one still shows its decimal places while all nine integer positions and
     * both separators stand blank.</p>
     *
     * <pre>
     *          0.00   |               |
     *          0.05   |            .05|
     *         -0.05   |-           .05|
     *        999.99   |         999.99|
     *       1234.56   |       1,234.56|
     *      -1234.56   |-      1,234.56|
     *    1000000.00   |   1,000,000.00|
     *  999999999.99   | 999,999,999.99|
     * -999999999.99   |-999,999,999.99|
     * </pre>
     *
     * @param amount the transaction amount to edit; must not be {@code null}, must carry exactly
     *     two decimal places, and must have a magnitude below one thousand
     *     million
     * @return the edited amount as a {@code String} of exactly {@value #REPORT_AMOUNT_WIDTH}
     *     characters, never {@code null}
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws ArithmeticException if the magnitude of {@code amount} needs more than
     *     nine integer digits, or if {@code amount} does not carry
     *     exactly two decimal places
     * @throws IllegalStateException if the composed string is not exactly
     *     {@value #REPORT_AMOUNT_WIDTH} characters, which reports a defect in this class rather
     *     than a fault of the caller
     */
    public static String formatReportDetailAmount(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");

        // WHY : Assumptions: the whole-item blank is tested before anything is composed, because
        //       it is not the composition's zero case -- it replaces the sign position too. Line
        //       30 of app/cpy/CVTRA07Y.cpy makes every digit position of this mask a suppression
        //       position, the two decimal positions included, and the editing rules blank the
        //       entire item when a mask of that shape receives zero. Reaching the composition
        //       below with zero would emit a decimal point and two zero digits, which is a
        //       different fifteen bytes.
        if (amount.isZero()) {
            return " ".repeat(REPORT_AMOUNT_WIDTH);
        }

        String digits = magnitudeDigits(amount, REPORT_INTEGER_DIGITS, REPORT_DETAIL_MASK);
        StringBuilder edited = new StringBuilder(REPORT_AMOUNT_WIDTH);

        // WHY : Assumptions: a positive value leaves this position blank rather than printing a
        //       plus. The mask at line 30 of app/cpy/CVTRA07Y.cpy spells its sign as a single
        //       minus, which is a constant position that prints only for a negative value; the
        //       always-signed behaviour belongs to the total masks at lines 54, 60 and 66 and is
        //       reached through the sibling method, not through an argument to this one.
        edited.append(amount.isNegative() ? '-' : ' ');
        edited.append(editIntegerRegion(digits, REPORT_INTEGER_DIGITS, true));
        edited.append('.');
        edited.append(digits, REPORT_INTEGER_DIGITS, digits.length());

        return requireExactWidth(edited.toString(), REPORT_AMOUNT_WIDTH, REPORT_DETAIL_MASK);
    }

    /**
     * Formats an accumulated report total, always signing the value.
     *
     * <p>Assumptions: this is regime 2, declared {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} three times in
     * {@code app/cpy/CVTRA07Y.cpy} -- at line 54 for the report page total, line 60 for the
     * account total and line 66 for the grand total. It suppresses leading zeros and blanks a
     * separator to the left of the first significant digit exactly as regime 1 does, and differs
     * from it in one position only: the sign is a constant plus, so a non-negative value prints
     * {@code +} where regime 1 prints a blank. That single byte is the entire difference between
     * the two masks, which is why they are two methods rather than one method with a flag whose
     * default would decide the artifact's appearance.</p>
     *
     * <p>Assumptions: a zero total renders as {@value #REPORT_AMOUNT_WIDTH} blanks and shows NO
     * plus sign. The whole-item blank applies to this mask for the same reason it applies to
     * regime 1 -- every digit position at lines 54, 60 and 66 of {@code app/cpy/CVTRA07Y.cpy} is
     * a suppression position -- and it blanks the sign position along with the digits. A report
     * page whose transactions net to zero therefore carries a blank total field rather than a
     * signed zero, and that was confirmed by executing the declaring clause.</p>
     *
     * <p>Assumptions: the three accumulators that reach this mask are declared
     * {@code PIC S9(09)V99} at lines 134, 135 and 136 of {@code app/cbl/CBTRN03C.cbl}, which is
     * exactly the nine integer positions this mask provides, so the report's own totals cannot
     * overflow it. The magnitude guard is retained regardless, because {@link Money} accepts ten
     * integer digits and this mask holds nine.</p>
     *
     * <pre>
     *          0.00   |               |
     *          0.05   |+           .05|
     *         -0.05   |-           .05|
     *       1234.56   |+      1,234.56|
     *      -1234.56   |-      1,234.56|
     *  999999999.99   |+999,999,999.99|
     * -999999999.99   |-999,999,999.99|
     * </pre>
     *
     * @param total the accumulated total to edit; must not be {@code null}, must carry exactly
     *     two decimal places, and must have a magnitude below one thousand
     *     million
     * @return the edited total as a {@code String} of exactly {@value #REPORT_AMOUNT_WIDTH}
     *     characters, never {@code null}
     * @throws NullPointerException if {@code total} is {@code null}
     * @throws ArithmeticException if the magnitude of {@code total} needs more than
     *     nine integer digits, or if {@code total} does not carry
     *     exactly two decimal places
     * @throws IllegalStateException if the composed string is not exactly
     *     {@value #REPORT_AMOUNT_WIDTH} characters, which reports a defect in this class rather
     *     than a fault of the caller
     */
    public static String formatReportTotalAmount(Money total) {
        Objects.requireNonNull(total, "total must not be null");

        // WHY : Assumptions: the constant plus of lines 54, 60 and 66 of app/cpy/CVTRA07Y.cpy
        //       does not survive a zero value. The whole-item blank of an all-suppression mask
        //       covers the sign position, so this early return precedes the sign decision below
        //       rather than following it.
        if (total.isZero()) {
            return " ".repeat(REPORT_AMOUNT_WIDTH);
        }

        String digits = magnitudeDigits(total, REPORT_INTEGER_DIGITS, REPORT_TOTAL_MASK);
        StringBuilder edited = new StringBuilder(REPORT_AMOUNT_WIDTH);

        // WHY : Assumptions: a plus is emitted for every non-negative value, which is the one
        //       byte separating this mask from the detail mask at line 30 of the same copybook.
        //       The three totals are read against one another down the right edge of the report,
        //       and an unsigned positive total beside a signed negative one would read as a
        //       missing value rather than as a positive number.
        edited.append(total.isNegative() ? '-' : '+');
        edited.append(editIntegerRegion(digits, REPORT_INTEGER_DIGITS, true));
        edited.append('.');
        edited.append(digits, REPORT_INTEGER_DIGITS, digits.length());

        return requireExactWidth(edited.toString(), REPORT_AMOUNT_WIDTH, REPORT_TOTAL_MASK);
    }

    /**
     * Formats an unsigned whole number as a zero-padded digit string of a declared width.
     *
     * <p>Assumptions: this is regime 3, an unsigned picture whose digit positions are ordinary
     * digit positions rather than suppression positions, so a leading zero is PRESERVED and
     * printed. It carries no sign position and no grouping separator, which makes it the exact
     * opposite of regimes 1 and 2 in both respects. Two widths of it are in use in the artifacts
     * this service emits: the four-digit category code declared {@code PIC 9(04)} at line 24 of
     * {@code app/cpy/CVTRA07Y.cpy}, and the eleven-digit account identifier declared
     * {@code PIC 9(11)} at line 7 of {@code app/cpy/CVACT03Y.cpy}. The width is a parameter
     * rather than a constant because those two are the same regime at two declared lengths, and
     * splitting them into two methods would duplicate the padding logic to express a number that
     * the calling band descriptor already knows.</p>
     *
     * <p>Assumptions: the zero padding is what makes this regime a declared-length identifier
     * rather than a number, and it is the reason a value must not be handed to a general-purpose
     * integer rendering on its way here. A category code of 7 occupies four bytes in the detail
     * band whether it is 7 or 7000, so the band's own field boundaries depend on the padding
     * being present. A left-padded blank would occupy the same four bytes and still be wrong,
     * because line 24 of {@code app/cpy/CVTRA07Y.cpy} declares digit positions and not
     * suppression positions.</p>
     *
     * <pre>
     *      7 at  4 digits   |0007|
     *      7 at 11 digits   |00000000007|
     *   1234 at  4 digits   |1234|
     * </pre>
     *
     * @param value the whole number to edit; must not be negative, because the declaring pictures
     *     are unsigned and carry no position in which a sign could be shown
     * @param declaredDigits the number of digit positions the receiving picture declares; must be
     *     between 1 and 18 inclusive
     * @return the value as a {@code String} of exactly {@code declaredDigits} characters, padded
     *     on the left with zeros, never {@code null}
     * @throws IllegalArgumentException if {@code value} is negative, or if
     *     {@code declaredDigits} is below 1 or above 18
     * @throws ArithmeticException if {@code value} needs more than {@code declaredDigits} digits,
     *     because rendering it would silently discard its high-order digits
     * @throws IllegalStateException if the composed string is not exactly {@code declaredDigits}
     *     characters, which reports a defect in this class rather than a fault of the caller
     */
    public static String formatUnsignedDigits(long value, int declaredDigits) {
        // WHY : Assumptions: a negative value is rejected rather than rendered as a magnitude,
        //       because the pictures at line 24 of app/cpy/CVTRA07Y.cpy and line 7 of
        //       app/cpy/CVACT03Y.cpy are unsigned and therefore have no position that could
        //       carry a sign. Silently rendering the magnitude would turn a debit into a credit
        //       with no trace, and rendering a sign would need a byte the band does not have.
        if (value < 0) {
            throw new IllegalArgumentException(
                    "value " + value + " is negative and the receiving picture 9("
                            + declaredDigits + ") is unsigned");
        }

        if (declaredDigits < 1 || declaredDigits > MAX_UNSIGNED_DIGITS) {
            throw new IllegalArgumentException(
                    "declaredDigits " + declaredDigits + " is outside the supported range 1 to "
                            + MAX_UNSIGNED_DIGITS);
        }

        String digits = Long.toString(value);
        if (digits.length() > declaredDigits) {
            throw new ArithmeticException(
                    "value " + value + " needs " + digits.length()
                            + " digits and the picture 9(" + declaredDigits + ") provides "
                            + declaredDigits);
        }

        StringBuilder edited = new StringBuilder(declaredDigits);
        for (int padded = digits.length(); padded < declaredDigits; padded++) {
            edited.append('0');
        }
        edited.append(digits);

        return requireExactWidth(edited.toString(), declaredDigits, "9(" + declaredDigits + ")");
    }

    /**
     * Formats a category balance under the sort-utility edit pattern, without any sign.
     *
     * <p>Assumptions: this is regime 4, and it is the one regime here that is not a COBOL picture.
     * It is the sort-utility edit pattern {@code EDIT=(TTTTTTTTT.TT)} declared at line 56 of
     * {@code app/jcl/PRTCATBL.jcl}, applied to the eleven-digit zoned balance named at line 50 of
     * the same job, and its pattern language inverts the COBOL convention this class otherwise
     * follows. A {@code T} digit selector always shows its digit including a leading zero, where
     * a COBOL {@code Z} blanks one; the utility's suppressing selector is a different letter
     * altogether and does not appear in this pattern. The pattern also carries no sign selector,
     * so there is NO sign position: a negative balance renders identically to its positive
     * counterpart, and a caller that needs the sign to survive must carry it in a separate item
     * of its own.</p>
     *
     * <p>Assumptions: this package supplies the mask for regime 4 and nothing else. The consumer
     * of the pattern is the category-balance extract at lines 53 to 56 of
     * {@code app/jcl/PRTCATBL.jcl}, whose output record length is declared as 40 at line 61 of
     * that job -- a declared attribute of the data set, which must be cited as such and never
     * presented as a sum of the extract's item widths, because summing them gives a different
     * number. That extract is not among this service's sources, which are
     * {@code app/cbl/CBTRN03C.cbl}, {@code app/jcl/TRANREPT.jcl} and
     * {@code app/cpy/CVTRA07Y.cpy}, so no band descriptor and no emit method for it exists in
     * this package. The two literal-blank operators the extract places around the edited number
     * -- one that emits a single blank and one that emits nine trailing blanks, both at lines 53
     * to 56 -- are likewise the caller's placement concern and are not part of the string
     * returned here.</p>
     *
     * <pre>
     *          0.00   |000000000.00|
     *       1234.56   |000001234.56|
     *      -1234.56   |000001234.56|
     *  999999999.99   |999999999.99|
     * </pre>
     *
     * @param balance the category balance to edit; must not be {@code null}, must carry exactly
     *     two decimal places, and must have a magnitude below one thousand million
     * @return the edited balance as a {@code String} of exactly
     *     {@value #SORT_EDITED_AMOUNT_WIDTH} characters, never {@code null}
     * @throws NullPointerException if {@code balance} is {@code null}
     * @throws ArithmeticException if the magnitude of {@code balance} needs more than nine
     *     integer digits, or if {@code balance} does not carry exactly two decimal places
     * @throws IllegalStateException if the composed string is not exactly
     *     {@value #SORT_EDITED_AMOUNT_WIDTH} characters, which reports a defect in this class
     *     rather than a fault of the caller
     */
    public static String formatSortEditedBalance(Money balance) {
        Objects.requireNonNull(balance, "balance must not be null");

        String digits = magnitudeDigits(balance, SORT_EDITED_INTEGER_DIGITS, SORT_EDITED_MASK);
        StringBuilder edited = new StringBuilder(SORT_EDITED_AMOUNT_WIDTH);

        // WHY : Assumptions: the digits are emitted verbatim with no suppression pass and no sign
        //       byte, which is the whole of the difference between this regime and regime 7. Both
        //       carry nine integer digits and two decimal digits; the pattern at line 56 of
        //       app/jcl/PRTCATBL.jcl spells its positions with the selector that shows a leading
        //       zero and omits a sign selector, so routing this value through the suppressing
        //       helper would blank digits the extract expects to see and would still leave the
        //       string one byte short of the mask it belongs to.
        edited.append(digits, 0, SORT_EDITED_INTEGER_DIGITS);
        edited.append('.');
        edited.append(digits, SORT_EDITED_INTEGER_DIGITS, digits.length());

        return requireExactWidth(
                edited.toString(), SORT_EDITED_AMOUNT_WIDTH, SORT_EDITED_MASK);
    }

    /**
     * Formats an amount with a leading sign and zero-padded digits, in eight integer positions.
     *
     * <p>Assumptions: this is regime 5, declared {@code PIC +99999999.99} at line 77 of
     * {@code app/cbl/CORPT00C.cbl}. It combines the always-present leading sign of regime 2 with
     * the zero-preserving digit positions of regime 6, and it is the narrowest money mask in this
     * class: EIGHT integer positions where every other money regime here provides nine. Its
     * digit positions are ordinary digit positions rather than suppression positions, so a
     * leading zero prints as a zero and a zero amount renders as a signed string of zeros rather
     * than as blanks -- the whole-item blank of regimes 1 and 2 has no application here.</p>
     *
     * <p>Assumptions: this regime must never stand in for a report amount, and the reason is the
     * missing ninth position rather than the differing width. Both this mask and the sort-utility
     * mask are twelve characters, so a width check alone would not catch the substitution, and
     * the report masks at lines 30, 54, 60 and 66 of {@code app/cpy/CVTRA07Y.cpy} routinely carry
     * values of nine integer digits. Handing such a value to this mask exceeds it by one digit,
     * which is why the magnitude guard is an ordinary path for this method rather than the
     * defect-only guard it is for the report masks.</p>
     *
     * <pre>
     *          0.00   |+00000000.00|
     *         -0.05   |-00000000.05|
     *       1234.56   |+00001234.56|
     *   12345678.90   |+12345678.90|
     *  -12345678.90   |-12345678.90|
     * </pre>
     *
     * @param amount the amount to edit; must not be {@code null}, must carry exactly two decimal
     *     places, and must have a magnitude below one hundred million
     * @return the edited amount as a {@code String} of exactly
     *     {@value #SIGNED_ZERO_FILLED_AMOUNT_WIDTH} characters, never {@code null}
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws ArithmeticException if the magnitude of {@code amount} needs more than eight
     *     integer digits, or if {@code amount} does not carry exactly two decimal places
     * @throws IllegalStateException if the composed string is not exactly
     *     {@value #SIGNED_ZERO_FILLED_AMOUNT_WIDTH} characters, which reports a defect in this
     *     class rather than a fault of the caller
     */
    public static String formatSignedZeroFilledAmount(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");

        String digits = magnitudeDigits(
                amount, SIGNED_ZERO_FILLED_INTEGER_DIGITS, SIGNED_ZERO_FILLED_MASK);
        StringBuilder edited = new StringBuilder(SIGNED_ZERO_FILLED_AMOUNT_WIDTH);

        // WHY : Assumptions: the sign is emitted unconditionally and leads the value, matching the
        //       constant plus that opens the mask at line 77 of app/cbl/CORPT00C.cbl. There is no
        //       whole-item blank to test for first, because the digit positions of that mask are
        //       ordinary digit positions and a zero amount is therefore a signed string of zeros
        //       rather than a blank field -- the opposite of what regimes 1 and 2 do with zero.
        edited.append(amount.isNegative() ? '-' : '+');
        edited.append(digits, 0, SIGNED_ZERO_FILLED_INTEGER_DIGITS);
        edited.append('.');
        edited.append(digits, SIGNED_ZERO_FILLED_INTEGER_DIGITS, digits.length());

        return requireExactWidth(
                edited.toString(), SIGNED_ZERO_FILLED_AMOUNT_WIDTH, SIGNED_ZERO_FILLED_MASK);
    }

    /**
     * Formats a statement balance with zero-padded digits and a trailing sign.
     *
     * <p>Assumptions: this is regime 6, declared {@code PIC 9(9).99-} at line 113 of
     * {@code app/cbl/CBSTM03A.CBL} as the balance item of the statement band. Two properties
     * distinguish it from every report regime. Its digit positions are ordinary digit positions,
     * so a leading zero is PRESERVED and a zero balance renders as nine zeros, a decimal point
     * and two more zeros. And its sign occupies the LAST position rather than the first, printing
     * a minus for a negative balance and a BLANK for a non-negative one, so the trailing byte of
     * the returned string is significant and must not be trimmed by a caller.</p>
     *
     * <p>Assumptions: this method accepts nine integer digits and rejects more, while the balance
     * that reaches it is declared {@code PIC S9(10)V99} at line 7 of {@code app/cpy/CVACT01Y.cpy}
     * and therefore carries TEN. Line 484 of {@code app/cbl/CBSTM03A.CBL} moves the wider item
     * into the narrower one, so the reference discards one high-order digit for any balance of a
     * thousand million or more. The Java raises instead of reproducing that, and the divergence
     * is registered with the other documented divergences. The narrowing decision therefore
     * belongs to the caller assembling the statement band, which is the only place that can
     * decide what a balance too wide for its own field should show; making it here would hide a
     * ten-digit balance behind a nine-digit string that looks correct.</p>
     *
     * <pre>
     *          0.00   |000000000.00 |
     *         -0.05   |000000000.05-|
     *       1234.56   |000001234.56 |
     *      -1234.56   |000001234.56-|
     *  999999999.99   |999999999.99 |
     * </pre>
     *
     * @param balance the statement balance to edit; must not be {@code null}, must carry exactly
     *     two decimal places, and must have a magnitude below one thousand million
     * @return the edited balance as a {@code String} of exactly
     *     {@value #STATEMENT_AMOUNT_WIDTH} characters whose last character is the sign position,
     *     never {@code null}
     * @throws NullPointerException if {@code balance} is {@code null}
     * @throws ArithmeticException if the magnitude of {@code balance} needs more than nine
     *     integer digits, or if {@code balance} does not carry exactly two decimal places
     * @throws IllegalStateException if the composed string is not exactly
     *     {@value #STATEMENT_AMOUNT_WIDTH} characters, which reports a defect in this class
     *     rather than a fault of the caller
     */
    public static String formatStatementBalance(Money balance) {
        Objects.requireNonNull(balance, "balance must not be null");

        String digits = magnitudeDigits(balance, STATEMENT_INTEGER_DIGITS, STATEMENT_BALANCE_MASK);
        StringBuilder edited = new StringBuilder(STATEMENT_AMOUNT_WIDTH);

        // WHY : Assumptions: the digits are emitted without a suppression pass, which is the ONLY
        //       difference between this method and the sibling that formats a statement
        //       transaction amount. The two masks are both thirteen characters and share their
        //       shape exactly; line 113 of app/cbl/CBSTM03A.CBL spells its integer positions with
        //       digit positions while line 137 spells the same nine positions with suppression
        //       positions. That single difference is why the two are separate methods: a shared
        //       method taking a suppression flag would let a caller silently blank a balance's
        //       leading zeros, which changes the statement's bytes while still reading as the
        //       same number.
        edited.append(digits, 0, STATEMENT_INTEGER_DIGITS);
        edited.append('.');
        edited.append(digits, STATEMENT_INTEGER_DIGITS, digits.length());

        // WHY : Assumptions: the sign trails the value here, unlike every report regime, because
        //       line 113 of app/cbl/CBSTM03A.CBL places its minus after the decimal positions. A
        //       non-negative balance leaves the position blank rather than printing a plus, so
        //       the returned string ends in a meaningful blank that a caller must place rather
        //       than trim.
        edited.append(balance.isNegative() ? '-' : ' ');

        return requireExactWidth(
                edited.toString(), STATEMENT_AMOUNT_WIDTH, STATEMENT_BALANCE_MASK);
    }

    /**
     * Formats a statement transaction amount with blanked leading zeros and a trailing sign.
     *
     * <p>Assumptions: this is regime 7, declared {@code PIC Z(9).99-} at line 137 of
     * {@code app/cbl/CBSTM03A.CBL} for the per-transaction amount and again at line 142 for the
     * accumulated total of the band below it. Its nine integer positions are suppression
     * positions, so a leading zero becomes a BLANK, and unlike the report masks it carries no
     * grouping separator at all -- a value of one thousand two hundred thirty-four and
     * fifty-six hundredths shows as {@code 1234.56} with no comma. Its sign occupies the last
     * position and prints a minus for a negative amount and a blank for a non-negative one.</p>
     *
     * <p>Assumptions: a zero amount does NOT blank the whole field here, and that is the sharpest
     * contrast in this class. Line 137 of {@code app/cbl/CBSTM03A.CBL} spells the two decimal
     * positions as ordinary digit positions rather than suppression positions, so the
     * whole-item blank that governs regimes 1 and 2 does not apply: a zero renders with its nine
     * integer positions blank, then a decimal point, then two zeros, then a blank sign position.
     * Regimes 6 and 7 are otherwise the same thirteen-character shape and differ only in
     * leading-zero treatment, which is exactly why they are two methods and not one.</p>
     *
     * <p>Assumptions: the amounts that reach this mask fit its nine integer positions exactly,
     * so no narrowing arises. The per-transaction amount is declared {@code PIC S9(09)V99} at
     * line 29 of {@code app/cpy/COSTM01.CPY} and the accumulated total at line 65 of
     * {@code app/cbl/CBSTM03A.CBL}, both nine integer digits into nine positions.</p>
     *
     * <p>Assumptions: the string returned here is embedded verbatim in the markup statement as
     * well as the plain-text one. Line 711 of {@code app/cbl/CBSTM03A.CBL} builds a markup
     * element from this very item, taking the whole edited field including its blanks and its
     * sign position, exactly as line 621 does for the balance of regime 6. A markup mapper must
     * therefore call this method rather than render a plain number of its own, or the two
     * artifacts will disagree on the same amount.</p>
     *
     * <pre>
     *          0.00   |         .00 |
     *          0.05   |         .05 |
     *         -0.05   |         .05-|
     *       1234.56   |     1234.56 |
     *      -1234.56   |     1234.56-|
     *    1000000.00   |  1000000.00 |
     *  999999999.99   |999999999.99 |
     * </pre>
     *
     * @param amount the statement transaction amount or accumulated total to edit; must not be
     *     {@code null}, must carry exactly two decimal places, and must have a magnitude below
     *     one thousand million
     * @return the edited amount as a {@code String} of exactly
     *     {@value #STATEMENT_AMOUNT_WIDTH} characters whose last character is the sign position,
     *     never {@code null}
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws ArithmeticException if the magnitude of {@code amount} needs more than nine integer
     *     digits, or if {@code amount} does not carry exactly two decimal places
     * @throws IllegalStateException if the composed string is not exactly
     *     {@value #STATEMENT_AMOUNT_WIDTH} characters, which reports a defect in this class
     *     rather than a fault of the caller
     */
    public static String formatStatementAmount(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");

        String digits = magnitudeDigits(amount, STATEMENT_INTEGER_DIGITS, STATEMENT_AMOUNT_MASK);
        StringBuilder edited = new StringBuilder(STATEMENT_AMOUNT_WIDTH);

        // WHY : Assumptions: suppression runs over these nine positions but grouping does not,
        //       because line 137 of app/cbl/CBSTM03A.CBL declares nine consecutive suppression
        //       positions with no separator between them, where lines 30, 54, 60 and 66 of
        //       app/cpy/CVTRA07Y.cpy declare the same nine broken into groups of three. Passing
        //       the grouping argument as true here would insert two commas the statement band has
        //       no room for and would return fifteen characters into a thirteen-byte item.
        edited.append(editIntegerRegion(digits, STATEMENT_INTEGER_DIGITS, false));
        edited.append('.');
        edited.append(digits, STATEMENT_INTEGER_DIGITS, digits.length());

        // WHY : Assumptions: no whole-item blank is tested before this point, unlike regimes 1 and
        //       2. The two decimal positions at line 137 of app/cbl/CBSTM03A.CBL are ordinary
        //       digit positions, so the editing rules leave the decimal places printing for a
        //       zero amount and only the integer positions blank.
        edited.append(amount.isNegative() ? '-' : ' ');

        return requireExactWidth(
                edited.toString(), STATEMENT_AMOUNT_WIDTH, STATEMENT_AMOUNT_MASK);
    }

    /**
     * Reduces an amount to the zero-padded decimal digits of its magnitude, sign discarded.
     *
     * <p>Assumptions: this is the single validation gate for all six money regimes, so that the
     * scale contract and the magnitude ceiling are enforced identically by each of them rather
     * than restated six times with six chances to differ. It returns magnitude digits only,
     * because the six regimes place their sign in three different ways -- leading and conditional,
     * leading and unconditional, trailing and conditional -- and one of them has no sign position
     * at all, so the sign cannot be composed here without the helper knowing which regime it
     * serves.</p>
     *
     * <p>Assumptions: the scale check is retained even though {@link Money} holds its amount
     * invariantly at {@link Money#SCALE} decimal places and every regime here declares exactly
     * two. The check asserts that agreement at this boundary instead of inheriting it as an
     * assumption about a sibling module, and it is the reason no scale-adjusting call appears
     * anywhere in this class: were the shared kernel's scale ever to change, these masks would
     * fail loudly rather than quietly emit a string of the wrong width with the decimal point in
     * the wrong column.</p>
     *
     * <p>Alternatives Considered: reading the magnitude through the shared kernel's whole-cents
     * accessor rather than through its decimal value was evaluated and rejected. The two agree
     * for every amount the kernel admits, but the accessor returns a primitive integral type, and
     * taking the absolute value of a primitive integral type has a documented edge at its own
     * minimum where the result stays negative. Reading the unscaled digits and taking their
     * magnitude as an arbitrary-precision integer has no such edge, and it also yields the digit
     * characters directly rather than requiring a second rendering step.</p>
     *
     * <p>Assumptions: this helper raises nothing for a {@code null} amount, because every caller
     * in this class rejects {@code null} at its own entry before reaching here.</p>
     *
     * @param amount the amount to reduce; already rejected as {@code null} by the calling method
     * @param integerDigits the number of integer positions the receiving mask declares
     * @param maskName the declared form of the receiving mask, used only to compose a failure
     *     message that a maintainer can search the reference tree for
     * @return the magnitude of {@code amount} as a {@code String} of exactly
     *     {@code integerDigits} plus two digit characters, padded on the left with zeros and
     *     carrying no sign, never {@code null}
     * @throws ArithmeticException if {@code amount} does not carry exactly two decimal places, or
     *     if its magnitude needs more than {@code integerDigits} integer digits
     */
    private static String magnitudeDigits(Money amount, int integerDigits, String maskName) {
        int scale = amount.amount().scale();
        if (scale != DECIMAL_DIGITS) {
            throw new ArithmeticException(
                    "amount " + amount.toPlainString() + " carries scale " + scale
                            + " and the mask " + maskName + " declares " + DECIMAL_DIGITS
                            + " decimal positions; this class does not rescale");
        }

        // WHY : Assumptions: the digits are taken from the unscaled value rather than from a
        //       rendered decimal string, because the unscaled value is already the exact digit
        //       sequence the mask's positions consume -- 11 digits for the 9-integer-position
        //       masks such as line 30 of app/cpy/CVTRA07Y.cpy, and 10 for the 8-integer-position
        //       mask at line 77 of app/cbl/CORPT00C.cbl -- so no separator or point has to be
        //       removed from it first. Taking the magnitude as an arbitrary-precision integer
        //       also keeps the step free of the primitive absolute-value edge described above.
        String digits = amount.amount().unscaledValue().abs().toString();
        int declaredDigits = integerDigits + DECIMAL_DIGITS;

        if (digits.length() > declaredDigits) {
            throw new ArithmeticException(
                    "amount " + amount.toPlainString() + " needs "
                            + (digits.length() - DECIMAL_DIGITS)
                            + " integer digits and the mask " + maskName + " provides "
                            + integerDigits);
        }

        StringBuilder magnitude = new StringBuilder(declaredDigits);
        for (int padded = digits.length(); padded < declaredDigits; padded++) {
            magnitude.append('0');
        }
        magnitude.append(digits);

        return magnitude.toString();
    }

    /**
     * Applies leading-zero suppression across the integer positions of a suppressing mask.
     *
     * <p>Assumptions: suppression stops at the first non-zero digit and never crosses the decimal
     * point, so this helper covers the integer positions only and its callers append the decimal
     * point and the decimal digits verbatim afterwards. That is why a value below one still shows
     * its decimal places under regimes 1, 2 and 7 while all of its integer positions stand
     * blank.</p>
     *
     * <p>Assumptions: a grouping separator standing to the LEFT of the first significant digit is
     * blanked along with the digits around it, which is the rule a pattern string cannot express
     * and the reason this loop exists at all. Lines 30, 54, 60 and 66 of
     * {@code app/cpy/CVTRA07Y.cpy} place two separators inside the suppression region, so a
     * value of four significant digits blanks the higher separator and prints only the lower
     * one. Composing the region a position at a time makes that one branch on a flag the loop
     * already maintains.</p>
     *
     * <p>Assumptions: the separator positions are derived from the region width and the grouping
     * interval rather than written as two literal offsets, so the rule stays correct for the
     * ungrouped nine-position region of regime 7 and would stay correct if a mask of another
     * width were ever added. A separator precedes a position when that position is not the first
     * and the number of positions remaining is an exact multiple of the interval, which places
     * them after the third and sixth digits of a nine-digit region exactly as the declared masks
     * do.</p>
     *
     * <p>Assumptions: this helper raises nothing of its own. Every caller supplies a string that
     * {@link #magnitudeDigits} has already padded to at least {@code integerDigits} characters.</p>
     *
     * @param magnitudeDigits the zero-padded magnitude digits, integer positions first
     * @param integerDigits the number of leading characters of {@code magnitudeDigits} that are
     *     integer positions of the mask
     * @param groupInThousands whether the mask declares grouping separators inside its
     *     suppression region, which regimes 1 and 2 do and regime 7 does not
     * @return the edited integer region as a {@code String}, being {@code integerDigits}
     *     characters plus one character for each grouping separator when grouping applies, never
     *     {@code null}
     */
    private static String editIntegerRegion(
            String magnitudeDigits, int integerDigits, boolean groupInThousands) {
        int separators = groupInThousands ? (integerDigits - 1) / GROUPING_INTERVAL : 0;
        StringBuilder region = new StringBuilder(integerDigits + separators);
        boolean significantSeen = false;

        for (int position = 0; position < integerDigits; position++) {
            if (groupInThousands && position > 0
                    && (integerDigits - position) % GROUPING_INTERVAL == 0) {
                // WHY : Assumptions: the separator takes the state of the flag as it stands BEFORE
                //       this position's digit is examined, which is what places a blank where the
                //       higher separator of a small value would fall. Examining the digit first
                //       would print a separator immediately to the left of the first significant
                //       digit, so a 4-significant-digit value would render as 6 blanks then a
                //       stray separator then 1,234.56 -- one character past the 15 that line 30
                //       of app/cpy/CVTRA07Y.cpy declares.
                region.append(significantSeen ? ',' : ' ');
            }

            char digit = magnitudeDigits.charAt(position);
            if (digit != '0') {
                significantSeen = true;
            }

            region.append(significantSeen ? digit : ' ');
        }

        return region.toString();
    }

    /**
     * Confirms an edited string occupies exactly the width its mask declares.
     *
     * <p>Assumptions: the artifacts these strings feed are of a declared record length and are
     * assembled by placing each item at a declared offset, so a string one character short or
     * long does not yield a slightly wrong field -- it shifts every column written after it and
     * corrupts the whole line. Asserting the width at the method that composed the string names
     * the defect where it happened rather than at the band that received it, which is the only
     * place a maintainer could otherwise start looking.</p>
     *
     * <p>Alternatives Considered: an assertion statement was evaluated and rejected, because
     * assertions are disabled unless the virtual machine is asked to enable them, so the guard
     * would be inert in exactly the runs whose output reaches a reader. Raising unconditionally
     * costs one comparison per edited value and cannot be switched off by a deployment
     * setting.</p>
     *
     * @param edited the composed string to check
     * @param declaredWidth the width the receiving mask declares
     * @param maskName the declared form of the mask, used only to compose a failure message that
     *     a maintainer can search the reference tree for
     * @return {@code edited} unchanged, so that this helper can be used in a return statement
     * @throws IllegalStateException if {@code edited} is not exactly {@code declaredWidth}
     *     characters, which reports a defect in this class rather than a fault of any caller
     */
    private static String requireExactWidth(String edited, int declaredWidth, String maskName) {
        if (edited.length() != declaredWidth) {
            throw new IllegalStateException(
                    "edited form for mask " + maskName + " is " + edited.length()
                            + " characters where the declared width is " + declaredWidth);
        }

        return edited;
    }
}
