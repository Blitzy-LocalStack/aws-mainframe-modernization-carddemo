package com.carddemo.common.validation;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Applies the migrated CardDemo date edit rules to one supplied century-year-month-day value and
 * reports the verdict for each of its three components.
 *
 * <h2>What this type migrates</h2>
 *
 * <p>Two baseline artifacts are migrated here and nowhere else: {@code app/cpy/CSUTLDPY.cpy}, a
 * procedure-only copybook holding a chain of five validation gates plus a separate date-of-birth
 * reasonableness test, and {@code app/cpy/CSUTLDWY.cpy}, that chain's working storage. The gates
 * reach a Language Environment date service through {@code app/cbl/CSUTLDTC.cbl}, whose behaviour
 * {@link #evaluateWithLanguageEnvironment(String, String)} reproduces.</p>
 *
 * <p>This type is not a single-field predicate. It reports three markers, an aggregate switch, one
 * latched message and a list of per-field errors, because the baseline writes all five and a caller
 * reads all five.</p>
 *
 * <p>Refactoring Rationale: the algorithm and its working storage are held together here although the
 * baseline holds them 4066 lines apart, because the separation is unworkable rather than merely
 * inconvenient. The algorithm has one includer in the whole repository and the working storage has
 * two, and the second includer supplies the storage to a program that includes no date algorithm and
 * runs no date edit — so the two can be, and are, supplied independently.</p>
 *
 * <h2>Control flow: five gates that accumulate taint, one true early exit</h2>
 *
 * <p>Assumptions: a failing gate does NOT stop the gates after it, and reading the baseline's fifteen
 * unconditional branches as early returns inverts the behaviour of the whole chain. Each of the first
 * three gates branches to its own local exit paragraph, and each of those holds nothing but an empty
 * exit statement, so control falls straight into the gate below. A date whose year is unusable still
 * has its month and day examined. Every gate runs here too, each writes its own marker, and the
 * aggregate verdict is derived from all three afterwards.</p>
 *
 * <p>Assumptions: exactly one construct decides whether the Language Environment gate runs, and it
 * sits at the FOOT of gate four rather than at its head ({@code CSUTLDPY.cpy} L274-L279). Only an
 * all-clear reaches gate five, which is why {@link DateEditResult#languageEnvironment()} is empty
 * exactly when that branch would have been taken.</p>
 *
 * <p>Assumptions: the two published entry points are the two the sole includer invokes.
 * {@link #validate(String, String)} is the general chain; {@link #validateDateOfBirth(String, String,
 * java.time.LocalDate)} is the separate range, and its contract states it is applied only after the
 * first has passed, exactly as {@code app/cbl/COACTUPC.cbl} L1539 requires.</p>
 *
 * <h2>The leap-year rule keeps the baseline's shape</h2>
 *
 * <p>Alternatives Considered: writing the canonical
 * {@code (y % 4 == 0 && y % 100 != 0) || y % 400 == 0} directly. Rejected because this method is what
 * a reader compares against the baseline when checking parity, so {@link #isLeapYear(int)} keeps the
 * baseline's own two-branch divisor selection ({@code CSUTLDPY.cpy} L243-L272): divisor 400 when the
 * year-within-century component is zero and 4 otherwise, accepting on a zero remainder. The two forms
 * agree on every year — the branch on {@code yy == 0} partitions the years so that each case reduces
 * to the same test the canonical rule reduces to — and this module's tests assert the agreement over a
 * wide span rather than over a handful of samples.</p>
 *
 * <p>Assumptions: the remainder is taken in integer arithmetic. The baseline's scratch variables are
 * packed signed integers, so its division is exact, and an approximate division would give a
 * leap-year verdict that is wrong on some years and right on others.</p>
 *
 * <h2>Fan-out, polarity and the accumulator</h2>
 *
 * <p>Assumptions: the number of components a failure marks VARIES by failure mode, and marking a
 * uniform three would diverge on two of the five modes. Thirty-one days in a thirty-day month marks
 * two components ({@code CSUTLDPY.cpy} L213-L217) and thirty days in February marks two as well
 * (L228-L241) — that second arm is easily missed and is not a variant of the first. The remaining
 * three modes mark three each.</p>
 *
 * <p>Trade-offs: the gates are modelled against an explicit mutable accumulator, {@link EditContext},
 * rather than as pure predicates. A pure single-component predicate would be simpler to write and to
 * test, and it cannot express what the baseline writes: it could produce neither the two-component
 * fan-out nor the first-error-wins message latch, which is a single slot written across the whole
 * chain rather than per component.</p>
 *
 * <p>Trade-offs: three of the four initialisations assume failure and the fourth assumes success, and
 * the asymmetry is carried as it stands rather than made uniform. Every arm of every gate terminates
 * with an explicit marking, so no reachable path observes the difference — but the initialiser is the
 * contract any arm ADDED to a gate would inherit, and substituting one polarity for the other is a
 * behavioural change with no stated reason.</p>
 *
 * <p>Trade-offs: the accumulator, the outcome and the Language Environment result are nested types
 * rather than separate files, because the package charter names two production classes and states
 * there will be no third. The cost is a longer file; the benefit is that a reader looking for the date
 * contract finds all of it in one place.</p>
 *
 * <h2>Three separate conventions in which a zero means success</h2>
 *
 * <p>Assumptions: one call chain carries three independent all-zero-means-acceptance conventions,
 * stated together so the pattern surprises a reader once rather than three times. The date service's
 * feedback token named for an INVALID date carries eight zero bytes and selects the text reporting
 * that the date IS valid ({@code CSUTLDTC.cbl} L62, L129-L130) — the condition name means the opposite
 * of what it says, and anyone who trusts the name inverts every date verdict in the system, which is
 * why {@link FeedbackCode#INVALID_DATE} keeps the baseline's name and a severity of zero. A severity
 * of zero is the success arm ({@code CSUTLDPY.cpy} L298). And the acceptable aggregate marker is the
 * lowest character in the collating sequence ({@code CSUTLDWY.cpy} L44).</p>
 *
 * <h2>Documented divergences</h2>
 *
 * <p>Refactoring Rationale: this type standardises on the TEN-character mask at the service leaf,
 * although the chain migrated here supplies eight ({@code CSUTLDPY.cpy} L291) across a call boundary
 * whose callee declares ten ({@code CSUTLDTC.cbl} L84-L85). Five of the six sites in the repository
 * use the ten-character form, the callee's own linkage declares it, and the eight-character form is
 * still published as {@link #BASELINE_DATE_FORMAT_MASK} and still accepted, so a caller reproducing
 * the baseline call exactly can still do so. The divergence is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Trade-offs: a date rejected by the Language Environment gate is reported here as unacceptable
 * AND RETAINS its three per-component errors, whereas the baseline clears those markers on the way
 * out ({@code CSUTLDPY.cpy} L327, reached on both routes out of the gate). Clearing them to match
 * would strand the migration's per-field error contract empty for this one rejection mode — a response
 * body announcing a failure with no field attached, and a client with nothing to render. The
 * divergence is unreachable through {@link #validate(String, String)} today, and that is measured
 * rather than hoped: the four gates ahead close the set totally, admitting exactly the well-formed
 * calendar dates of 1900 through 2099, every one of which the service gate accepts. It is carried so
 * the per-field contract stays whole should a future gate arm open the route, and the covering test is
 * named for the geometry so nobody restores the clearing by mistake.</p>
 *
 * <p>Assumptions: this type rejects an unsupported-range date, because the artifact it migrates tests
 * the severity and nothing else ({@code CSUTLDPY.cpy} L298). Two OTHER callers of the date service
 * forgive message number 2513, and they are served rather than unified with:
 * {@link #evaluateWithLanguageEnvironment(String, String)} publishes the severity and the message
 * number separately and {@link LanguageEnvironmentResult#unsupportedRange()} names the tolerance
 * point, so a caller that forgives the code decides that for itself.</p>
 *
 * <p>Trade-offs: the message latch is scoped to ONE CALL here, whereas the baseline arms its guard
 * once for an entire edit pass so the first failure among all its validated fields is the one whose
 * text survives. Reproducing that scope would require state between calls, which would make this type
 * unsafe to share and contradict the stateless-service requirement. A caller validating several date
 * fields composes screen scope itself by keeping the first non-blank {@link DateEditResult#message()}
 * across its results.</p>
 *
 * <h2>Message text and constants</h2>
 *
 * <p>Assumptions: thirteen message texts are carried character for character under Transformation Rule
 * T8, including five separate punctuation conventions that are preserved rather than made consistent —
 * a month out of range opens with a colon, a space and a capitalised component name while a day out of
 * range opens with a colon, no space and a lower-case one. Two structurally identical failures, two
 * spellings, both carried. Two texts run past the 75-character message field once a long enough field
 * label is prefixed, so {@link #AGGREGATE_MESSAGE_LENGTH} is applied.</p>
 *
 * <p>Assumptions: the texts are compiled-in constants and are not externalised, because this module
 * has no resource directory and a bundle would need a lookup path and a locale policy the baseline has
 * no counterpart for.</p>
 *
 * <p>Trade-offs: {@link #MAX_VALID_FEBRUARY_DAY} is referenced by nothing anywhere in the baseline —
 * the February arms test the individual day constants instead — and is published regardless, because
 * the copybook is the normative source and a reader comparing the two files would otherwise find a
 * constant present there and absent here with no explanation. Its documentation says plainly that it is
 * unused, and it is not represented as live.</p>
 *
 * <h2>Two date comparisons in one system, with opposite polarities</h2>
 *
 * <p>Assumptions: the date-of-birth test is STRICT and the account-expiry test is INCLUSIVE, and both
 * are recorded so neither is brought into line with the other. A date of birth equal to the current
 * date is rejected ({@code CSUTLDPY.cpy} L350); a transaction dated exactly on the expiry date posts
 * ({@code app/cbl/CBTRN02C.cbl} L414). This type implements the strict comparison and does not touch
 * the expiry boundary, which belongs to the posting job.</p>
 *
 * <p>Alternatives Considered: computing an elapsed duration for the date-of-birth test. The baseline
 * author evaluated exactly that and chose otherwise — the rejected form survives commented out at
 * {@code CSUTLDPY.cpy} L351-L353 beneath the comparison that was kept. This type keeps the comparison,
 * expressed against the platform's date class, because that class compares calendar dates directly and
 * the baseline's day-number conversion exists only to give it something an ordinary relational
 * operator can compare.</p>
 *
 * <h2>Structural decisions</h2>
 *
 * <p>Refactoring Rationale: the leap-year scratch variables are LOCALS here. In the baseline they
 * cannot be — a procedure-only copybook has no data division, so its sole includer hoists three values
 * with no lifetime beyond one computation into storage shared by an entire program. Only the genuinely
 * caller-owned state — the error switch, the message field, its guard and the field label — became
 * members of {@link EditContext}.</p>
 *
 * <p>Refactoring Rationale: {@link LanguageEnvironmentResult} is the ONE type replacing four separate
 * declarations of the same 80-character result structure, none of which keeps the others in step and
 * all of which would have to be edited together for the layout to change.
 * {@link LanguageEnvironmentResult#render()} emits the layout so the single declaration is verifiable
 * rather than merely asserted.</p>
 *
 * <p>Assumptions: blank detection belongs in the gates, not at the service leaf, because the leaf
 * cannot tell a blank date from a malformed one and the gates can. Each gate tests for absence against
 * both the lowest character in the collating sequence and the space, which is what
 * {@link FieldValidationFlag#isNeverSupplied(String)} reproduces.</p>
 *
 * <p>Alternatives Considered: an annotation processor generating accessors and constructors. Rejected
 * because generated members have nowhere to hold the docstring the Explainability rule requires, so the
 * gate enforcing that rule would have nothing to read. A Java 21 record with an explicit compact
 * constructor gives the same brevity while leaving every member documentable.</p>
 *
 * <p>Alternatives Considered: publishing the individual gates, which the baseline's own header comment
 * invites by describing the first three as reusable. They are private because publishing them would
 * expose the fall-through geometry as an API and let a caller run gate four against components gates
 * one to three had never examined — a state the chain never reaches. The two published primitives,
 * {@link #isLeapYear(int)} and {@link #evaluateWithLanguageEnvironment(String, String)}, are the two
 * pieces that genuinely have callers of their own outside the chain.</p>
 *
 * <h2>Verification</h2>
 *
 * <p>Assumptions: no golden-master comparison covers the routes through this type, and the limit is
 * recorded rather than glossed over. The parity oracle covers batch flows and cannot run the online
 * programs end to end without a terminal-monitor runtime; the gate chain is reached only through such a
 * program. The service leaf IS directly covered by two dedicated oracles and the boundary rules are
 * exercised indirectly by the batch date boundaries, but the screen-oriented routes are not covered at
 * all. Correctness therefore rests on this module's own tests together with the citations above;
 * claiming golden-master backing for the whole of this type would overstate the evidence.</p>
 *
 * <p>Assumptions: several traces of hand editing are visible in the two source artifacts — an exit
 * paragraph targeted by no branch, a stray sequence number, a header comment listing one paragraph
 * name twice and naming a working-storage artifact that does not exist. None is propagated here and
 * none is altered where it stands, because the baseline is the behavioural oracle.</p>
 *
 * <p>This type holds no mutable static state, so every published method is safe to call concurrently:
 * each call allocates its own {@link EditContext} and returns an immutable {@link DateEditResult}. Its
 * whole import list is drawn from the platform library and from one type in its own package, so a batch
 * job, a message consumer and a request handler can share one date contract without any of them
 * acquiring the others' dependencies.</p>
 */
public final class DateEditValidator {

    /** The century value the baseline accepts as the present one, from {@code CSUTLDWY.cpy} line 9. */
    public static final int THIS_CENTURY = 20;

    /** The century value the baseline accepts as the previous one, from {@code CSUTLDWY.cpy} line 10. */
    public static final int LAST_CENTURY = 19;

    /** Lowest acceptable month, the lower end of {@code CSUTLDWY.cpy} lines 19 and 20. */
    public static final int MIN_VALID_MONTH = 1;

    /** Highest acceptable month, the upper end of {@code CSUTLDWY.cpy} lines 19 and 20. */
    public static final int MAX_VALID_MONTH = 12;

    /** The month the February arms of gate four single out, from {@code CSUTLDWY.cpy} line 24. */
    public static final int FEBRUARY = 2;

    /** Lowest acceptable day, the lower end of {@code CSUTLDWY.cpy} lines 28 and 29. */
    public static final int MIN_VALID_DAY = 1;

    /** Highest acceptable day, the upper end of {@code CSUTLDWY.cpy} lines 28 and 29. */
    public static final int MAX_VALID_DAY = 31;

    /** The day value gate four's first arm tests for, from {@code CSUTLDWY.cpy} line 30. */
    public static final int DAY_31 = 31;

    /** The day value gate four's February-30 arm tests for, from {@code CSUTLDWY.cpy} line 31. */
    public static final int DAY_30 = 30;

    /** The day value gate four's leap-year arm tests for, from {@code CSUTLDWY.cpy} line 32. */
    public static final int DAY_29 = 29;

    /**
     * Highest February day the baseline declares acceptable, from {@code CSUTLDWY.cpy} lines 33 and 34.
     *
     * <p>Trade-offs: this constant is referenced by nothing in the baseline and is published anyway.
     * The reasoning is on this class.</p>
     */
    public static final int MAX_VALID_FEBRUARY_DAY = 28;

    /**
     * The months carrying 31 days, from {@code CSUTLDWY.cpy} lines 21 to 23.
     *
     * <p>Assumptions: the baseline writes this domain as an enumerated list of seven values rather than
     * as a range, and the two forms sit side by side in one file -- an enumerated list here, ranges for
     * the month and day domains at lines 19 and 28. The inconsistency is carried, not made uniform.</p>
     */
    public static final Set<Integer> THIRTY_ONE_DAY_MONTHS = Set.of(1, 3, 5, 7, 8, 10, 12);

    /** Divisor gate four selects for a century year, from {@code CSUTLDPY.cpy} line 246. */
    public static final int LEAP_CENTURY_DIVISOR = 400;

    /** Divisor gate four selects for every other year, from {@code CSUTLDPY.cpy} line 248. */
    public static final int LEAP_ORDINARY_DIVISOR = 4;

    /** Lowest year the four-digit year field can hold, from the picture at {@code CSUTLDWY.cpy} line 15. */
    public static final int MIN_YEAR = 0;

    /** Highest year the four-digit year field can hold, from the picture at {@code CSUTLDWY.cpy} line 15. */
    public static final int MAX_YEAR = 9999;

    /**
     * The three marker bytes that spell the acceptable aggregate, from {@code CSUTLDWY.cpy} line 44.
     *
     * <p>Assumptions: the baseline states this as a value clause on the marker group itself, and a value
     * clause on a group spans its subordinates, so one figurative constant sets all three one-character
     * markers at once. {@link DateEditResult#aggregateFlagCodes()} is what a caller compares against
     * this.</p>
     */
    public static final String AGGREGATE_VALID_CODES =
            String.valueOf(new char[] {
                FieldValidationFlag.VALID_CODE,
                FieldValidationFlag.VALID_CODE,
                FieldValidationFlag.VALID_CODE,
            });

    /** The three marker bytes that spell the unacceptable aggregate, from {@code CSUTLDWY.cpy} line 45. */
    public static final String AGGREGATE_INVALID_CODES =
            String.valueOf(new char[] {
                FieldValidationFlag.NOT_OK_CODE,
                FieldValidationFlag.NOT_OK_CODE,
                FieldValidationFlag.NOT_OK_CODE,
            });

    /** Declared width of the field-label field, from {@code app/cbl/COACTUPC.cbl} line 53. */
    public static final int FIELD_LABEL_LENGTH = 25;

    /** Declared width of the aggregate message field, from {@code app/cbl/COACTUPC.cbl} line 479. */
    public static final int AGGREGATE_MESSAGE_LENGTH = 75;

    /** Width of the unseparated date the gate chain operates on, from {@code CSUTLDWY.cpy} lines 4 to 27. */
    public static final int PACKED_DATE_LENGTH = 8;

    /** Width of the separated date the service leaf declares, from {@code app/cbl/CSUTLDTC.cbl} line 84. */
    public static final int MASKED_DATE_LENGTH = 10;

    /** Declared width of the service leaf's result structure, from {@code app/cbl/CSUTLDTC.cbl} line 86. */
    public static final int RESULT_LENGTH = 80;

    /**
     * The ten-character mask five of the six baseline call sites use.
     *
     * <p>Assumptions: this is the form the service leaf's own linkage declares and the form the parity
     * oracle validates under. The reasoning for standardising on it is on this class.</p>
     */
    public static final String DATE_FORMAT_MASK = "YYYY-MM-DD";

    /**
     * The eight-character mask the gate chain moves, from {@code CSUTLDPY.cpy} line 291.
     *
     * <p>Assumptions: this is the sole outlier among the six call sites and it is published so that a
     * caller reproducing the baseline call exactly still can.</p>
     */
    public static final String BASELINE_DATE_FORMAT_MASK = "YYYYMMDD";

    /** The separator character the ten-character mask places at its fifth and eighth positions. */
    public static final char MASK_SEPARATOR = '-';

    /**
     * The first date the service leaf accepts, inclusive.
     *
     * <p>Assumptions: the oracle suite pins this boundary from both sides. Its unit layer accepts this
     * exact date and rejects the day below it, at {@code tests/cobol-unit/CSUTLDTC_test.cbl} lines 178
     * to 210, and its integration layer's stand-in tests the same numeric floor at
     * {@code tests/integration/test_csutldtc_date.py} line 231.</p>
     */
    public static final LocalDate GREGORIAN_FLOOR = LocalDate.of(1582, 10, 15);

    /** The severity that means acceptance, from the test at {@code CSUTLDPY.cpy} line 298. */
    public static final int SEVERITY_VALID = 0;

    /** The severity every named failure token carries, from {@code app/cbl/CSUTLDTC.cbl} lines 63 to 70. */
    public static final int SEVERITY_ERROR = 3;

    /**
     * The severity a token matching none of the nine named conditions carries.
     *
     * <p>Assumptions: this tier arises only where a coarser stand-in returns such a token, which drives
     * the service leaf to its final arm at {@code app/cbl/CSUTLDTC.cbl} lines 147 and 148. The oracle's
     * integration layer grades on it; its unit layer, which models the real token set, does not.</p>
     */
    public static final int SEVERITY_UNRECOGNISED = 12;

    /**
     * The message number two other callers of the service leaf forgive.
     *
     * <p>Assumptions: this value is the message-number field of the unsupported-range token declared at
     * {@code app/cbl/CSUTLDTC.cbl} line 66, whose two bytes are 0x09D1. The gate chain migrated here
     * does not forgive it; the reasoning is on this class.</p>
     */
    public static final int MSG_NO_UNSUPP_RANGE = 2513;

    /** Field identity reported for the year component, ordered first at {@code CSUTLDWY.cpy} line 46. */
    public static final String FIELD_YEAR = "year";

    /** Field identity reported for the month component, ordered second at {@code CSUTLDWY.cpy} line 50. */
    public static final String FIELD_MONTH = "month";

    /** Field identity reported for the day component, ordered third at {@code CSUTLDWY.cpy} line 54. */
    public static final String FIELD_DAY = "day";

    /** Message text for an unsupplied year, verbatim from {@code CSUTLDPY.cpy} line 37. */
    public static final String MSG_YEAR_REQUIRED = " : Year must be supplied.";

    /**
     * Message text for a year that is not four digits, verbatim from {@code CSUTLDPY.cpy} line 54.
     *
     * <p>Assumptions: this is the one text of the thirteen that carries no colon at all. It opens with a
     * space and runs straight into its wording. The punctuation is carried as it stands.</p>
     */
    public static final String MSG_YEAR_NOT_FOUR_DIGITS = " must be 4 digit number.";

    /** Message text for a century outside the accepted pair, verbatim from {@code CSUTLDPY.cpy} line 79. */
    public static final String MSG_CENTURY_INVALID = " : Century is not valid.";

    /** Message text for an unsupplied month, verbatim from {@code CSUTLDPY.cpy} line 101. */
    public static final String MSG_MONTH_REQUIRED = " : Month must be supplied.";

    /**
     * Message text for a month outside its domain, verbatim from {@code CSUTLDPY.cpy} lines 119 and 136.
     *
     * <p>Assumptions: the baseline writes this same text twice, once on its range arm at line 111 and
     * once on its numeric arm at line 126, so the two arms are indistinguishable to a caller. That is
     * what makes the arm ordering inside gate two unobservable, and the reasoning is on
     * {@link #editMonth(EditContext)}.</p>
     */
    public static final String MSG_MONTH_RANGE = ": Month must be a number between 1 and 12.";

    /** Message text for an unsupplied day, verbatim from {@code CSUTLDPY.cpy} line 161. */
    public static final String MSG_DAY_REQUIRED = " : Day must be supplied.";

    /**
     * Message text for a day outside its domain, verbatim from {@code CSUTLDPY.cpy} lines 180 and 195.
     *
     * <p>Assumptions: this text opens with a colon and no space and names its component in lower case,
     * where the month text opens with a colon and a space and names its component capitalised. Two
     * structurally identical failures, two spellings, both carried as they stand. The baseline also
     * writes this same text on both arms of gate three, at lines 170 and 187.</p>
     */
    public static final String MSG_DAY_RANGE = ":day must be a number between 1 and 31.";

    /** Message text for 31 days in a 30-day month, verbatim from {@code CSUTLDPY.cpy} line 221. */
    public static final String MSG_NO_31_DAYS = ":Cannot have 31 days in this month.";

    /** Message text for 30 days in February, verbatim from {@code CSUTLDPY.cpy} line 236. */
    public static final String MSG_NO_30_DAYS = ":Cannot have 30 days in this month.";

    /**
     * Message text for 29 February in a non-leap year, verbatim from {@code CSUTLDPY.cpy} line 266.
     *
     * <p>Assumptions: there is no space after the colon and no space after the first period. Both
     * omissions are in the baseline text and both are carried, because the migration's string rule
     * requires user-visible text to be reproduced character for character.</p>
     */
    public static final String MSG_NOT_LEAP_YEAR =
            ":Not a leap year.Cannot have 29 days in this month.";

    /**
     * Message text for a date of birth that is not in the past, verbatim from {@code CSUTLDPY.cpy}
     * line 363.
     *
     * <p>Assumptions: this text ends with a trailing space and names its subject in lower case. Both
     * are in the baseline text and both are carried.</p>
     */
    public static final String MSG_FUTURE_DATE = ":cannot be in the future ";

    /** First assembled fragment of the service-leaf message, verbatim from {@code CSUTLDPY.cpy} line 308. */
    public static final String MSG_LE_SEVERITY_FRAGMENT = " validation error Sev code: ";

    /** Second assembled fragment of the service-leaf message, verbatim from {@code CSUTLDPY.cpy} line 310. */
    public static final String MSG_LE_MESSAGE_CODE_FRAGMENT = " Message code: ";

    /** Interior literal of the result structure, from {@code app/cbl/CSUTLDTC.cbl} line 45. */
    private static final String RESULT_LABEL_MESSAGE_CODE = "Mesg Code:";

    /** Interior literal of the result structure, from {@code app/cbl/CSUTLDTC.cbl} line 51. */
    private static final String RESULT_LABEL_TEST_DATE = "TstDate:";

    /** Interior literal of the result structure, from {@code app/cbl/CSUTLDTC.cbl} line 54. */
    private static final String RESULT_LABEL_MASK_USED = "Mask used:";

    /** Declared width of the severity and message-number text fields, from {@code CSUTLDWY.cpy} lines 61 and 66. */
    private static final int ZONED_FIELD_WIDTH = 4;

    /** Declared width of the verdict text field, from {@code CSUTLDWY.cpy} line 71. */
    private static final int VERDICT_WIDTH = 15;

    /** Declared width of the message-code label filler, from {@code CSUTLDWY.cpy} line 64. */
    private static final int MESSAGE_CODE_LABEL_WIDTH = 11;

    /** Declared width of the test-date label filler, from {@code CSUTLDWY.cpy} line 74. */
    private static final int TEST_DATE_LABEL_WIDTH = 9;

    /** Declared width of the mask label filler, from {@code CSUTLDWY.cpy} line 79. */
    private static final int MASK_LABEL_WIDTH = 10;

    /** Declared width of the result structure's trailing filler, from {@code CSUTLDWY.cpy} line 84. */
    private static final int TRAILING_FILLER_WIDTH = 3;

    /** Offset of the year component within an unseparated date, from {@code CSUTLDWY.cpy} line 5. */
    private static final int PACKED_YEAR_START = 0;

    /** Offset one past the year component within an unseparated date. */
    private static final int PACKED_YEAR_END = 4;

    /** Offset of the month component within an unseparated date, from {@code CSUTLDWY.cpy} line 16. */
    private static final int PACKED_MONTH_START = 4;

    /** Offset one past the month component within an unseparated date. */
    private static final int PACKED_MONTH_END = 6;

    /** Offset of the day component within an unseparated date, from {@code CSUTLDWY.cpy} line 25. */
    private static final int PACKED_DAY_START = 6;

    /** Offset one past the day component within an unseparated date. */
    private static final int PACKED_DAY_END = 8;

    /** Offset of the month component within a separated date, from the mask's own layout. */
    private static final int MASKED_MONTH_START = 5;

    /** Offset one past the month component within a separated date. */
    private static final int MASKED_MONTH_END = 7;

    /** Offset of the day component within a separated date, from the mask's own layout. */
    private static final int MASKED_DAY_START = 8;

    /** Position of the first separator within a separated date. */
    private static final int MASK_FIRST_SEPARATOR_INDEX = 4;

    /** Position of the second separator within a separated date. */
    private static final int MASK_SECOND_SEPARATOR_INDEX = 7;

    /** Divisor that reduces a four-digit year to its year-within-century, from {@code CSUTLDPY.cpy} line 245. */
    private static final int CENTURY_MODULUS = 100;

    /** Declared width of the century-and-year component, from {@code CSUTLDWY.cpy} lines 14 and 15. */
    private static final int YEAR_WIDTH = 4;

    /** Declared width of the century, month and day components, from {@code CSUTLDWY.cpy} lines 6, 16 and 25. */
    private static final int COMPONENT_WIDTH = 2;

    /** The value a component parse yields when the text is not a run of digits of the declared width. */
    private static final int NOT_NUMERIC = -1;

    /**
     * Prevents instantiation of a class that publishes only static members.
     *
     * <p>Alternatives Considered: an instance-based validator holding the field label and the markers as
     * instance state was evaluated and rejected. It would let one instance be reused across two dates,
     * carrying the first date's latched message into the second, which is the one behaviour the
     * per-call latch documented on this class exists to avoid. Static entry points that allocate their
     * own {@link EditContext} make that reuse unrepresentable.</p>
     *
     * @throws AssertionError always, so that reflective instantiation is reported rather than absorbed
     */
    private DateEditValidator() {
        throw new AssertionError("DateEditValidator publishes only static members and is never instantiated");
    }

    /**
     * The feedback outcomes the Language Environment date service can report, with the severity, message
     * number and verdict text each one carries.
     *
     * <p>Assumptions: the baseline declares nine of these as named conditions over an eight-byte token,
     * at {@code app/cbl/CSUTLDTC.cbl} lines 62 to 70, and selects a verdict text from them in a
     * ten-armed evaluation at lines 128 to 149. The tenth arm is the evaluation's catch-all at lines 147
     * and 148, which has no condition name of its own and is published here as {@link #OTHER}. The
     * severity and the message number of each constant were decoded from the token bytes themselves: the
     * token's first two bytes are its severity and its next two are its message number, both declared as
     * signed binary halfwords at lines 72 and 73, and its last four bytes are a severity-control byte and
     * the three-character facility identifier that every one of the nine shares.</p>
     */
    public enum FeedbackCode {

        /**
         * The date is acceptable.
         *
         * <p>Assumptions: this constant's name is the baseline's and its name is the opposite of its
         * meaning. The condition is declared at {@code app/cbl/CSUTLDTC.cbl} line 62 with a token of
         * eight zero bytes, and at lines 129 and 130 that same condition selects the text reporting that
         * the date is valid. Its severity is therefore zero and it is the ONE constant here for which
         * {@link #acceptable()} answers true. Reading the name as a rejection inverts every date verdict
         * in the system, which is why the name is kept rather than tidied: a reader who meets it here
         * meets the trap in the one place that explains it.</p>
         */
        INVALID_DATE(SEVERITY_VALID, 0, "Date is valid", "0000000000000000"),

        /** The supplied text did not carry enough of a date to interpret, from line 63 and lines 131 to 132. */
        INSUFFICIENT_DATA(SEVERITY_ERROR, 2507, "Insufficient", "000309CB59C3C5C5"),

        /** A component was outside the calendar's domain, from line 64 and lines 133 to 134. */
        BAD_DATE_VALUE(SEVERITY_ERROR, 2508, "Datevalue error", "000309CC59C3C5C5"),

        /** The era the date names is not one the service supports, from line 65 and lines 135 to 136. */
        INVALID_ERA(SEVERITY_ERROR, 2509, "Invalid Era    ", "000309CD59C3C5C5"),

        /**
         * The date is well formed but outside the supported calendar range, from line 66 and lines 137
         * to 138.
         *
         * <p>Assumptions: this is the one outcome two other baseline callers forgive, and the message
         * number that identifies it is {@link DateEditValidator#MSG_NO_UNSUPP_RANGE}. The gate chain
         * migrated here does not forgive it. The oracle suite's unit layer reaches this arm and no other
         * scenario there does, which is what pins the value.</p>
         */
        UNSUPP_RANGE(SEVERITY_ERROR, MSG_NO_UNSUPP_RANGE, "Unsupp. Range  ", "000309D159C3C5C5"),

        /** The month component was outside 1 through 12, from line 67 and lines 139 to 140. */
        INVALID_MONTH(SEVERITY_ERROR, 2517, "Invalid month  ", "000309D559C3C5C5"),

        /**
         * The supplied mask was not one the service can interpret, from line 68 and lines 141 to 142.
         *
         * <p>Assumptions: this is what an unrecognised mask yields rather than a raised exception,
         * because the baseline declares a condition for exactly this case and returns it like any other
         * feedback. The reasoning is on
         * {@link DateEditValidator#evaluateWithLanguageEnvironment(String, String)}.</p>
         */
        BAD_PIC_STRING(SEVERITY_ERROR, 2518, "Bad Pic String ", "000309D659C3C5C5"),

        /** A component held something other than digits, from line 69 and lines 143 to 144. */
        NON_NUMERIC_DATA(SEVERITY_ERROR, 2520, "Nonnumeric data", "000309D859C3C5C5"),

        /** The year within its era was zero, from line 70 and lines 145 to 146. */
        YEAR_IN_ERA_ZERO(SEVERITY_ERROR, 2521, "YearInEra is 0 ", "000309D959C3C5C5"),

        /**
         * A token matching none of the nine named conditions, from the catch-all at lines 147 and 148.
         *
         * <p>Assumptions: {@link DateEditValidator#evaluateWithLanguageEnvironment(String, String)} never
         * returns this constant, because it models the real nine-token set that the oracle suite's unit
         * layer models. It is published because the baseline service has this arm and a coarser stand-in
         * reaches it: the oracle suite's integration layer returns a token whose severity halfword is
         * 0x000C, which matches no named condition and lands here, and its two truth tables are graded on
         * the severity that results. Carrying the constant is what lets both oracles be read against this
         * enumeration instead of only one of them.</p>
         */
        OTHER(SEVERITY_UNRECOGNISED, 0, "Date is invalid", "000C000059C3C5C5");

        /** The severity halfword of this outcome's token. */
        private final int severity;

        /** The message-number halfword of this outcome's token. */
        private final int messageNumber;

        /** The verdict text this outcome selects, at the width the baseline writes it. */
        private final String verdict;

        /** The eight-byte token, rendered as sixteen upper-case hexadecimal digits. */
        private final String token;

        /**
         * Binds one feedback outcome to the four values its baseline declaration carries.
         *
         * @param severity the severity halfword, zero for acceptance and non-zero for every rejection
         * @param messageNumber the message-number halfword, which is what the two tolerant baseline
         *     callers test when they decide whether to forgive a rejection
         * @param verdict the verdict text this outcome selects, carried at its source width
         * @param token the eight-byte feedback token as sixteen hexadecimal digits, so that the
         *     zero-means-acceptance encoding is visible rather than implied
         */
        FeedbackCode(int severity, int messageNumber, String verdict, String token) {
            this.severity = severity;
            this.messageNumber = messageNumber;
            this.verdict = verdict;
            this.token = token;
        }

        /**
         * Returns the severity halfword this outcome carries.
         *
         * @return the severity, one of {@link DateEditValidator#SEVERITY_VALID},
         *     {@link DateEditValidator#SEVERITY_ERROR} or
         *     {@link DateEditValidator#SEVERITY_UNRECOGNISED}
         */
        public int severity() {
            return severity;
        }

        /**
         * Returns the message-number halfword this outcome carries.
         *
         * @return the message number, zero for {@link #INVALID_DATE} and for {@link #OTHER}
         */
        public int messageNumber() {
            return messageNumber;
        }

        /**
         * Returns the verdict text this outcome selects, at the width the baseline writes it.
         *
         * @return the verdict text, between 12 and 15 characters long
         */
        public String verdict() {
            return verdict;
        }

        /**
         * Returns this outcome's eight-byte feedback token as sixteen hexadecimal digits.
         *
         * @return the token text, always 16 characters
         */
        public String token() {
            return token;
        }

        /**
         * Reports whether this outcome means the date was accepted.
         *
         * <p>Assumptions: acceptance is a severity of zero, which is the test the gate chain performs at
         * {@code app/cpy/CSUTLDPY.cpy} line 298, and it holds for exactly one constant here -- the one
         * whose name says the opposite. This predicate exists so that no caller has to write that test
         * itself and risk reading the constant's name instead of its severity.</p>
         *
         * @return {@code true} only for {@link #INVALID_DATE}; {@code false} for every other outcome
         */
        public boolean acceptable() {
            return severity == SEVERITY_VALID;
        }

    }

    /**
     * The outcome of one call to the Language Environment date service, in the shape the baseline's
     * 80-character result structure carries.
     *
     * <p>Assumptions: the structure's interior carries three literal labels that no participant ever
     * changes, declared as filler items at {@code app/cbl/CSUTLDTC.cbl} lines 45, 51 and 54. They are not
     * components of this record because no caller supplies them and none may vary them; they are applied
     * by {@link #render()} at the widths their declarations give.</p>
     *
     * @param feedbackCode the outcome the service reported, carrying its own severity, message number and
     *     verdict text
     * @param severity the severity halfword as reported, which is what the gate chain tests and what the
     *     baseline service forwards to its return code at {@code app/cbl/CSUTLDTC.cbl} line 98
     * @param messageNumber the message-number halfword as reported, which is what the two tolerant
     *     baseline callers test when deciding whether to forgive a rejection
     * @param verdict the verdict text the service selected, at the width the baseline writes it
     * @param date the date text as submitted, echoed back the way the service echoes it at
     *     {@code app/cbl/CSUTLDTC.cbl} lines 107 and 108
     * @param mask the mask text as submitted, echoed back the way the service echoes it at
     *     {@code app/cbl/CSUTLDTC.cbl} lines 111 to 113
     */
    public record LanguageEnvironmentResult(
            FeedbackCode feedbackCode,
            int severity,
            int messageNumber,
            String verdict,
            String date,
            String mask) {

        /**
         * Rejects any outcome that could not have come from the service.
         *
         * @param feedbackCode the outcome to accept, required because the severity and message number are
         *     read from it and a result without one has no outcome to report
         * @param severity the severity to accept, required to agree with the outcome's own severity so
         *     that the two can never be read against each other and disagree
         * @param messageNumber the message number to accept, required to agree with the outcome's own for
         *     the same reason
         * @param verdict the verdict text to accept, required because it is the text a caller renders
         * @param date the submitted date text to accept, required because the echo is part of the layout
         * @param mask the submitted mask text to accept, required for the same reason
         * @throws NullPointerException if {@code feedbackCode}, {@code verdict}, {@code date} or
         *     {@code mask} is {@code null}
         * @throws IllegalArgumentException if {@code severity} or {@code messageNumber} disagrees with the
         *     value {@code feedbackCode} carries, because a result whose severity contradicts its outcome
         *     would let one caller accept a date that another rejects on the same evidence
         */
        public LanguageEnvironmentResult {
            Objects.requireNonNull(feedbackCode, "feedbackCode must not be null");
            Objects.requireNonNull(verdict, "verdict must not be null");
            Objects.requireNonNull(date, "date must not be null");
            Objects.requireNonNull(mask, "mask must not be null");

            // Assumptions: the severity and the message number are held as components as well as
            //     being derivable from the outcome, because the baseline transports them as two
            //     separate four-character text fields and its callers read those fields rather than any
            //     token. Holding them makes the transported shape explicit; checking them here is what
            //     stops the two representations from ever disagreeing, which is the one hazard that
            //     holding a derivable value twice introduces.
            if (severity != feedbackCode.severity()) {
                throw new IllegalArgumentException(
                        "severity " + severity + " contradicts " + feedbackCode
                                + ", which carries " + feedbackCode.severity());
            }
            if (messageNumber != feedbackCode.messageNumber()) {
                throw new IllegalArgumentException(
                        "messageNumber " + messageNumber + " contradicts " + feedbackCode
                                + ", which carries " + feedbackCode.messageNumber());
            }
        }

        /**
         * Reports whether the service accepted the date.
         *
         * @return {@code true} when the severity is {@link DateEditValidator#SEVERITY_VALID};
         *     {@code false} for every non-zero severity
         */
        public boolean acceptable() {
            return severity == SEVERITY_VALID;
        }

        /**
         * Renders this outcome for a log or a diagnostic without reproducing the date it echoed.
         *
         * <p>Alternatives Considered: dropping the two echoed components from the record so that the
         * generated rendering is safe by construction. Rejected because the echo is part of the
         * baseline layout {@link #render()} has to reproduce, so removing the components would break
         * the contract this class exists to preserve. Overriding the rendering keeps the layout intact
         * and closes the accidental path.</p>
         *
         * @return the outcome, its severity, its message number and its verdict, together with the
         *     widths of the echoed date and mask; never the echoed values themselves
         */
        @Override
        public String toString() {
            return "LanguageEnvironmentResult[feedbackCode=" + feedbackCode
                    + ", severity=" + severity
                    + ", messageNumber=" + messageNumber
                    + ", verdict=" + verdict
                    + ", dateLength=" + date.length()
                    + ", maskLength=" + mask.length() + ']';
        }

        /**
         * Reports whether this rejection is the one two other baseline callers forgive.
         *
         * <p>Assumptions: four baseline call sites reject a date only when its message number is not
         * {@link DateEditValidator#MSG_NO_UNSUPP_RANGE} -- at {@code app/cbl/COTRN02C.cbl} lines 399 and
         * 419 and at {@code app/cbl/CORPT00C.cbl} lines 398 and 418 -- while the gate chain migrated here
         * tests the severity alone and forgives nothing. This predicate is published so that a caller
         * reproducing the tolerant behaviour has the test named for it, and so that the divergence stays a
         * caller's decision rather than being settled inside
         * {@link DateEditValidator#validate(String, String)}.</p>
         *
         * @return {@code true} when the message number identifies the unsupported-range outcome;
         *     {@code false} otherwise, including for an accepted date
         */
        public boolean unsupportedRange() {
            return messageNumber == MSG_NO_UNSUPP_RANGE;
        }

        /**
         * Emits this outcome in the baseline's 80-character result layout.
         *
         * <p>Assumptions: the layout is the field sequence declared at {@code app/cpy/CSUTLDWY.cpy} lines
         * 60 to 85 and, field for field, at {@code app/cbl/CSUTLDTC.cbl} lines 42 to 57. The two numeric
         * fields are emitted as four zero-padded digits because their declarations are four-character text
         * fields with numeric redefinitions over them, which is the form the tolerant callers compare
         * against when they test for the four-character acceptable value. Every text field is padded or
         * truncated to its declared width, which is what the baseline's own moves do.</p>
         *
         * @return the result text, always exactly {@link DateEditValidator#RESULT_LENGTH} characters
         * @throws IllegalStateException if the assembled text is not the declared width, which would mean
         *     the layout above no longer matches the four baseline declarations it reproduces
         */
        public String render() {
            StringBuilder rendered = new StringBuilder(RESULT_LENGTH);
            rendered.append(zoned(severity));
            rendered.append(padOrTruncate(RESULT_LABEL_MESSAGE_CODE, MESSAGE_CODE_LABEL_WIDTH));
            rendered.append(zoned(messageNumber));
            rendered.append(' ');
            rendered.append(padOrTruncate(verdict, VERDICT_WIDTH));
            rendered.append(' ');
            rendered.append(padOrTruncate(RESULT_LABEL_TEST_DATE, TEST_DATE_LABEL_WIDTH));
            rendered.append(padOrTruncate(date, MASKED_DATE_LENGTH));
            rendered.append(' ');
            rendered.append(padOrTruncate(RESULT_LABEL_MASK_USED, MASK_LABEL_WIDTH));
            rendered.append(padOrTruncate(mask, MASKED_DATE_LENGTH));
            rendered.append(' ');
            rendered.append(" ".repeat(TRAILING_FILLER_WIDTH));

            // Assumptions: the declared width is asserted rather than trusted. The layout is the
            //     one contract four baseline declarations agree on, and an arithmetic slip in the
            //     sequence above would produce a result that still reads correctly field by field while
            //     being unusable to any caller that indexes into it -- which is exactly how the two
            //     tolerant callers read it, by offset, at app/cbl/COTRN02C.cbl lines 65 to 69.
            if (rendered.length() != RESULT_LENGTH) {
                throw new IllegalStateException(
                        "rendered result is " + rendered.length() + " characters, but the layout declares "
                                + RESULT_LENGTH);
            }
            return rendered.toString();
        }
    }

    /**
     * The outcome of one date edit: a marker per component, the aggregate switch, the latched message and
     * the per-field error array assembled from them.
     *
     * <p>Assumptions: every one of these components corresponds to something the baseline writes and a
     * caller reads. The three markers are the one-character fields at {@code app/cpy/CSUTLDWY.cpy} lines
     * 46, 50 and 54, and their declaration order is the order this record lists them and the order
     * {@link #fieldErrors()} reports them. The aggregate switch is the caller-owned error switch its
     * includer declares at {@code app/cbl/COACTUPC.cbl} line 173. The message is the caller-owned message
     * field declared at line 479 of that program. The per-field error array is the migrated form of the
     * highlight the baseline's presentation template writes onto a screen, which is the transformation
     * rule this package's charter names.</p>
     *
     * @param fieldLabel the label the messages were composed against, already reduced to the width the
     *     baseline's label field declares and then trimmed, exactly as the assembly at
     *     {@code app/cpy/CSUTLDPY.cpy} line 307 trims it
     * @param year the verdict for the century-and-year component
     * @param month the verdict for the month component
     * @param day the verdict for the day component
     * @param inputError the caller-owned error switch as it stands after the edit; this is the value that
     *     survives the baseline's marker clearing, and the reasoning is on {@link DateEditValidator}
     * @param message the first failure's assembled text, or an empty string when nothing failed; empty
     *     rather than absent because the baseline's own guard condition is that the field is still blank
     * @param fieldErrors one entry per component in error, in the components' declaration order; never
     *     {@code null} and never modifiable
     * @param languageEnvironment the fifth gate's outcome when that gate ran, or empty when the checkpoint
     *     branched past it
     */
    public record DateEditResult(
            String fieldLabel,
            FieldValidationFlag year,
            FieldValidationFlag month,
            FieldValidationFlag day,
            boolean inputError,
            String message,
            List<FieldValidationFlag.FieldError> fieldErrors,
            Optional<LanguageEnvironmentResult> languageEnvironment) {

        /**
         * Rejects any outcome that could not have come from an edit, and seals the error list.
         *
         * @param fieldLabel the label to accept, required because every assembled message is prefixed with
         *     it and a result without one cannot explain which field it describes
         * @param year the year verdict to accept, required because every edit reports all three components
         * @param month the month verdict to accept, required for the same reason
         * @param day the day verdict to accept, required for the same reason
         * @param inputError the switch to accept, which needs no validation because both of its values are
         *     meaningful
         * @param message the message to accept, required to be present though it may be empty, because
         *     empty is how the baseline spells the absence of a message
         * @param fieldErrors the error list to accept, required and sealed here so that a caller cannot
         *     add to an outcome after the edit that produced it has finished
         * @param languageEnvironment the fifth gate's outcome to accept, required to be present as an
         *     optional though the optional itself may be empty
         * @throws NullPointerException if {@code fieldLabel}, {@code year}, {@code month}, {@code day},
         *     {@code message}, {@code fieldErrors} or {@code languageEnvironment} is {@code null}, or if
         *     {@code fieldErrors} holds a null entry, since the sealing copy below rejects one
         */
        public DateEditResult {
            Objects.requireNonNull(fieldLabel, "fieldLabel must not be null");
            Objects.requireNonNull(year, "year must not be null");
            Objects.requireNonNull(month, "month must not be null");
            Objects.requireNonNull(day, "day must not be null");
            Objects.requireNonNull(message, "message must not be null");
            Objects.requireNonNull(fieldErrors, "fieldErrors must not be null");
            Objects.requireNonNull(languageEnvironment, "languageEnvironment must not be null");

            // Trade-offs: the list is copied and sealed rather than stored as supplied. The copy
            //     costs one allocation per edit. It is accepted because a record component is only as
            //     immutable as the object it references, and this list is the migrated form of a screen
            //     highlight that the baseline writes once and never revises; a caller able to append to
            //     it could report a field in error that no gate examined.
            fieldErrors = List.copyOf(fieldErrors);
        }

        /**
         * Reports whether the date passed every gate.
         *
         * <p>Assumptions: this conjoins the caller-owned switch with all three markers, and in the
         * baseline those two can disagree. The aggregate marker condition at
         * {@code app/cpy/CSUTLDWY.cpy} line 44 reads acceptable after a fifth-gate rejection because the
         * statement at {@code app/cpy/CSUTLDPY.cpy} line 327 has cleared the marker group, while the
         * switch the caller actually tests remains set. Conjoining the two here is what makes the
         * contradiction unrepresentable, and it is the documented divergence set out on
         * {@link DateEditValidator}.</p>
         *
         * @return {@code true} only when the switch is clear and all three components are acceptable
         */
        public boolean isValid() {
            return !inputError && year.isValid() && month.isValid() && day.isValid();
        }

        /**
         * Returns the three marker bytes as the baseline's marker group would hold them.
         *
         * <p>Assumptions: this is the group the two aggregate conditions at {@code app/cpy/CSUTLDWY.cpy}
         * lines 44 and 45 are declared over, in the components' declaration order, so the returned text
         * equals {@link DateEditValidator#AGGREGATE_VALID_CODES} exactly when every component is
         * acceptable and {@link DateEditValidator#AGGREGATE_INVALID_CODES} exactly when all three carry the
         * unacceptable byte. It is published so that those two group-level conditions are directly
         * testable rather than only inferable from three separate markers.</p>
         *
         * @return three characters, one canonical marker byte per component
         */
        public String aggregateFlagCodes() {
            return String.valueOf(new char[] {year.code(), month.code(), day.code()});
        }
    }

    /**
     * The mutable state one edit accumulates: the three markers, the caller-owned switch, the latched
     * message and the component text the gates read.
     *
     * <p>Assumptions: the members here are exactly the externals the baseline copybook uses without
     * declaring, and no more. Its includer supplies the error switch at {@code app/cbl/COACTUPC.cbl} line
     * 173, the message field and its guard at lines 479 and 480, and the field label at line 53. The three
     * arithmetic scratch variables that includer also supplies, at lines 152, 154 and 157, are absent
     * here on purpose: they hold no state between statements, so they are locals of
     * {@link DateEditValidator#isLeapYear(int)} instead.</p>
     */
    private static final class EditContext {

        /** The label every assembled message is prefixed with, already reduced and trimmed. */
        private final String fieldLabel;

        /** The century-and-year component as supplied, four characters wide or empty when absent. */
        private final String rawYear;

        /** The month component as supplied, two characters wide or empty when absent. */
        private final String rawMonth;

        /** The day component as supplied, two characters wide or empty when absent. */
        private final String rawDay;

        /** The year component's numeric value, or {@link DateEditValidator#NOT_NUMERIC} when it has none. */
        private final int yearValue;

        /** The century component's numeric value, or {@link DateEditValidator#NOT_NUMERIC}. */
        private final int centuryValue;

        /** The month component's numeric value, or {@link DateEditValidator#NOT_NUMERIC}. */
        private final int monthValue;

        /** The day component's numeric value, or {@link DateEditValidator#NOT_NUMERIC}. */
        private final int dayValue;

        /** The year component's marker. */
        private FieldValidationFlag year = FieldValidationFlag.VALID;

        /** The month component's marker. */
        private FieldValidationFlag month = FieldValidationFlag.VALID;

        /** The day component's marker. */
        private FieldValidationFlag day = FieldValidationFlag.VALID;

        /** The text reported against the year component, or {@code null} while it has none. */
        private String yearMessage;

        /** The text reported against the month component, or {@code null} while it has none. */
        private String monthMessage;

        /** The text reported against the day component, or {@code null} while it has none. */
        private String dayMessage;

        /** The caller-owned error switch, set by any failing arm of any gate. */
        private boolean inputError;

        /** The latched aggregate message; empty is how the baseline spells the absence of one. */
        private String message = "";

        /** The fifth gate's outcome, or {@code null} while that gate has not run. */
        private LanguageEnvironmentResult languageEnvironment;

        /**
         * Binds one edit to the label it reports against and the three component texts it examines.
         *
         * @param fieldLabel the label to prefix every assembled message with, already reduced to the
         *     declared width and trimmed
         * @param rawYear the century-and-year text as supplied, or an empty string when nothing arrived
         * @param rawMonth the month text as supplied, or an empty string when nothing arrived
         * @param rawDay the day text as supplied, or an empty string when nothing arrived
         */
        EditContext(String fieldLabel, String rawYear, String rawMonth, String rawDay) {
            this.fieldLabel = fieldLabel;
            this.rawYear = rawYear;
            this.rawMonth = rawMonth;
            this.rawDay = rawDay;

            String centuryText =
                    rawYear.length() == YEAR_WIDTH ? rawYear.substring(0, COMPONENT_WIDTH) : "";

            // Assumptions: the numeric values are resolved once here rather than at each reading,
            //     because the baseline reads them through numeric redefinitions laid over the very same
            //     bytes, so a component's value is whatever its bytes already are and cannot change
            //     between gates. Resolving per reading would let two gates disagree about one component.
            //     The century is resolved separately from the whole year because the baseline redefines it
            //     independently, at app/cpy/CSUTLDWY.cpy line 7, and gate one tests that component alone
            //     rather than a range over the whole year.
            this.yearValue = digitsToValue(rawYear, YEAR_WIDTH);
            this.centuryValue = digitsToValue(centuryText, COMPONENT_WIDTH);
            this.monthValue = digitsToValue(rawMonth, COMPONENT_WIDTH);
            this.dayValue = digitsToValue(rawDay, COMPONENT_WIDTH);
        }

        /**
         * Records a failure: sets the caller-owned switch, marks every named component and latches the
         * assembled text.
         *
         * <p>Assumptions: the order of the three actions is the order every failing arm of the baseline
         * performs them -- the switch first, then the markers, then the guarded message assembly -- and the
         * number of components named is the fan-out width of that arm. The census of widths is on
         * {@link DateEditValidator}, and naming the components explicitly at each call site is what keeps
         * a two-component fan-out from silently becoming a three-component one.</p>
         *
         * @param state the marker state to record, either the unacceptable state or the never-supplied one
         * @param messageText the message text for this failure, verbatim from the baseline, which is
         *     appended to the field label to form the reported text
         * @param fields the component identities this failure marks, one or more of
         *     {@link DateEditValidator#FIELD_YEAR}, {@link DateEditValidator#FIELD_MONTH} and
         *     {@link DateEditValidator#FIELD_DAY}
         * @throws IllegalArgumentException if any element of {@code fields} is not one of the three
         *     component identities, because a marker this type does not hold cannot be recorded
         */
        void fail(FieldValidationFlag state, String messageText, String... fields) {
            inputError = true;
            String composed = fieldLabel + messageText;
            for (String field : fields) {
                mark(field, state, composed);
            }
            latch(composed);
        }

        /**
         * Records one component's marker and the text reported against it.
         *
         * <p>Assumptions: that overwrite is reachable and its effect is worth stating, because it looks
         * like a lost verdict. A date whose year component is absent while its month and day read 02 and 29
         * has its year marked never-supplied by gate one, at {@code app/cpy/CSUTLDPY.cpy} line 33, and then
         * marked unacceptable by gate four's leap-year arm at line 262, which marks all three components
         * without consulting what any of them already said. The final year marker is therefore the
         * unacceptable one while the latched aggregate message still reports the absence, because the
         * message was latched first. Both halves of that are the baseline's, and neither is smoothed
         * over.</p>
         *
         * @param field the component identity to mark
         * @param state the marker state to record
         * @param composed the already-assembled text to report against the component, or {@code null} when
         *     the state is acceptable and there is nothing to report
         * @throws IllegalArgumentException if {@code field} is not one of the three component identities
         */
        void mark(String field, FieldValidationFlag state, String composed) {
            switch (field) {
                case FIELD_YEAR -> {
                    year = state;
                    yearMessage = composed;
                }
                case FIELD_MONTH -> {
                    month = state;
                    monthMessage = composed;
                }
                case FIELD_DAY -> {
                    day = state;
                    dayMessage = composed;
                }
                default -> throw new IllegalArgumentException(
                        "unknown date component identity: " + field);
            }
        }

        /**
         * Writes the aggregate message if, and only if, no message has been written yet.
         *
         * <p>Assumptions: this is the first-error-wins guard the baseline expresses as a condition on the
         * message field still being blank, at {@code app/cpy/CSUTLDPY.cpy} line 305 and at the equivalent
         * line of every other failing arm. Across the whole chain the field therefore keeps the FIRST
         * failure's text rather than the last, which is the opposite of what an unguarded assignment would
         * do.</p>
         *
         * @param composed the already-assembled text to latch
         */
        void latch(String composed) {
            if (message.isEmpty()) {
                message = composed.length() > AGGREGATE_MESSAGE_LENGTH
                        ? composed.substring(0, AGGREGATE_MESSAGE_LENGTH)
                        : composed;
            }
        }

        /**
         * Reports whether all three markers currently read acceptable.
         *
         * <p>Assumptions: this is the group-level aggregate condition declared at
         * {@code app/cpy/CSUTLDWY.cpy} line 44, and it is the test the checkpoint at
         * {@code app/cpy/CSUTLDPY.cpy} lines 274 to 279 performs to decide whether the fifth gate runs at
         * all. It deliberately does NOT consult the caller-owned switch, because the baseline condition is
         * declared over the marker group alone and the switch sits outside it.</p>
         *
         * @return {@code true} when the year, month and day markers are all acceptable
         */
        boolean aggregateValid() {
            return year.isValid() && month.isValid() && day.isValid();
        }

        /**
         * Returns the text reported against one component.
         *
         * @param field the component identity to look up
         * @return the reported text for that component, never {@code null} for a component in error
         * @throws IllegalArgumentException if {@code field} is not one of the three component identities
         * @throws IllegalStateException if the component is in error yet carries no text, which would mean
         *     a marker had been recorded outside {@link #fail(FieldValidationFlag, String, String...)} and
         *     the per-field error array would announce a failure it could not explain
         */
        String messageFor(String field) {
            String reported = switch (field) {
                case FIELD_YEAR -> yearMessage;
                case FIELD_MONTH -> monthMessage;
                case FIELD_DAY -> dayMessage;
                default -> throw new IllegalArgumentException(
                        "unknown date component identity: " + field);
            };
            if (reported == null) {
                throw new IllegalStateException(
                        "date component " + field + " is in error but carries no reported text");
            }
            return reported;
        }

        /**
         * Freezes this accumulator into the immutable outcome a caller receives.
         *
         * <p>Assumptions: the components are presented to the error-array builder in an insertion-ordered
         * map so that the reported order is the order the baseline declares the three markers in, at
         * {@code app/cpy/CSUTLDWY.cpy} lines 46, 50 and 54. That builder consults the text resolver only
         * for components actually in error, so an acceptable component never has its text looked up.</p>
         *
         * @return the outcome of this edit, with its error list already sealed
         * @throws IllegalStateException if a component in error carries no reported text
         */
        DateEditResult toResult() {
            Map<String, FieldValidationFlag> markers = new LinkedHashMap<>();
            markers.put(FIELD_YEAR, year);
            markers.put(FIELD_MONTH, month);
            markers.put(FIELD_DAY, day);

            List<FieldValidationFlag.FieldError> errors =
                    FieldValidationFlag.collectErrors(markers, this::messageFor);

            return new DateEditResult(
                    fieldLabel,
                    year,
                    month,
                    day,
                    inputError,
                    message,
                    errors,
                    Optional.ofNullable(languageEnvironment));
        }
    }

    /**
     * Runs the whole five-gate chain over one supplied date and reports a verdict per component.
     *
     * <p>This is the migrated form of the range that opens at {@code app/cpy/CSUTLDPY.cpy} line 18 and
     * terminates at line 329, and it is the entry point the sole baseline includer performs for all four
     * of its date fields, at {@code app/cbl/COACTUPC.cbl} lines 1478 to 1482, 1490 to 1494, 1503 to 1507
     * and 1533 to 1538. Every gate runs: a component that fails does not stop the components after it, and
     * the aggregate verdict is derived from all three markers once the chain has finished. The reasoning
     * behind that, behind the one point at which the chain does exit early, and behind the one divergence
     * this type carries, is all on {@link DateEditValidator}.</p>
     *
     * @param fieldLabel the human-readable name of the field being edited, which prefixes every reported
     *     message exactly as the baseline prefixes it; reduced to
     *     {@link #FIELD_LABEL_LENGTH} characters and then trimmed, which is what a move into the
     *     baseline's label field followed by its trim at {@code app/cpy/CSUTLDPY.cpy} line 307 does
     * @param date the date to edit, either the eight-character unseparated form the baseline's date field
     *     holds or the ten-character separated form five of its six service call sites use; may be
     *     {@code null} or empty, which is how a field that never arrived over a request presents itself and
     *     is treated exactly as the baseline's all-absent field is
     * @return the outcome, carrying a marker per component, the error switch, the first failure's message,
     *     the per-field error array and the fifth gate's outcome when that gate ran
     * @throws NullPointerException if {@code fieldLabel} is {@code null}, because every reported message is
     *     composed against it and there is no default that would not misattribute the failure
     * @throws IllegalArgumentException if {@code fieldLabel} is empty or wholly whitespace, or if
     *     {@code date} carries content at some width other than eight or ten characters, or is ten
     *     characters without separators at its fifth and eighth positions; a date of an unrecognised width
     *     cannot be split into components and reporting it as merely unacceptable would attribute a
     *     caller's error to the user's input
     */
    public static DateEditResult validate(String fieldLabel, String date) {
        EditContext context = newContext(fieldLabel, date);

        // Assumptions: the first three gates are invoked unconditionally and in sequence because the
        //     baseline's short-circuit branches target each gate's own empty exit paragraph, and every
        //     one of those paragraphs falls straight through into the gate below it -- line 88 into line
        //     91, line 145 into line 150, line 205 into line 209. Guarding a gate on its predecessor's
        //     verdict would turn taint accumulation into early return and lose two of the three markers
        //     whenever the first component failed.
        editYearCcyy(context);
        editMonth(context);
        editDay(context);

        // Assumptions: gate four reports whether the chain continues, and it is the only gate that
        //     can stop it. Three of its arms branch to the outer terminus at line 329 directly, at lines
        //     225, 240 and 270, and its closing checkpoint at lines 274 to 279 branches there too
        //     whenever any marker is already in error. All four routes skip the fifth gate entirely,
        //     which is why an outcome's fifth-gate result is empty rather than absent.
        if (editDayMonthYear(context)) {
            editDateLanguageEnvironment(context);
        }

        return context.toResult();
    }

    /**
     * Applies the date-of-birth reasonableness test: a date of birth must lie strictly in the past.
     *
     * <p>This is the migrated form of the separate range at {@code app/cpy/CSUTLDPY.cpy} lines 341 to 370,
     * which the sole baseline includer performs at {@code app/cbl/COACTUPC.cbl} lines 1540 and 1541 nested
     * inside a test at line 1539 that the general edit had already passed. A caller composes the two the
     * same way: run {@link #validate(String, String)} first and call this only when its outcome is
     * acceptable.</p>
     *
     * <p>Assumptions: the comparison is strict, at line 350, so a date of birth equal to the supplied
     * current date is rejected. That is the opposite polarity from the account-expiry boundary at
     * {@code app/cbl/CBTRN02C.cbl} line 414, which is inclusive. Both are recorded on
     * {@link DateEditValidator} so that neither is brought into line with the other.</p>
     *
     * @param fieldLabel the human-readable name of the field being edited, reduced and trimmed exactly as
     *     {@link #validate(String, String)} reduces and trims it
     * @param date the date of birth to test, in either the eight-character unseparated form or the
     *     ten-character separated form
     * @param today the current business date to compare against, supplied by the caller rather than read
     *     from a clock
     * @return the outcome, which marks all three components when the date is not in the past and marks
     *     none of them when it is; its fifth-gate result is always empty, because this range does not
     *     invoke the date service
     * @throws NullPointerException if {@code fieldLabel} or {@code today} is {@code null}
     * @throws IllegalArgumentException if {@code fieldLabel} is empty or wholly whitespace, if {@code date}
     *     carries content at an unrecognised width, or if {@code date} is not a well-formed calendar date;
     *     the last of those is a caller error rather than a user error, because the baseline reaches this
     *     range only once the general edit has passed and the day-number function it calls at lines 345 to
     *     348 has no defined result for an ill-formed date
     */
    public static DateEditResult validateDateOfBirth(String fieldLabel, String date, LocalDate today) {
        Objects.requireNonNull(today, "today must not be null");
        EditContext context = newContext(fieldLabel, date);
        LocalDate suppliedDate = toCalendarDate(context, date);

        // Assumptions: the comparison is strictly greater rather than greater-or-equal, reproducing
        //     line 350, so a date of birth equal to today fails. The baseline compares two day numbers it
        //     derives at lines 345 to 348 purely so that an ordinary relational operator can be used on
        //     them; the platform's date class compares calendar dates directly, so the derivation has no
        //     counterpart here and its absence changes no outcome.
        if (today.isAfter(suppliedDate)) {
            return context.toResult();
        }

        // Assumptions: this failure marks all three components, in the order lines 357 to 359 mark
        //     them, and it is a three-component fan-out rather than a day-only one. Narrowing it would
        //     leave the month and year unhighlighted where the baseline highlights them.
        context.fail(
                FieldValidationFlag.NOT_OK, MSG_FUTURE_DATE, FIELD_DAY, FIELD_MONTH, FIELD_YEAR);
        return context.toResult();
    }

    /**
     * Reports whether a four-digit year is a leap year, using the baseline's own two-branch divisor
     * selection.
     *
     * <p>Assumptions: this reproduces {@code app/cpy/CSUTLDPY.cpy} lines 245 to 256 statement for
     * statement -- select 400 when the year-within-century component is zero and 4 otherwise, take one
     * remainder, accept a remainder of zero. That looks like an incomplete leap-year rule and is not; the
     * proof that it is exactly equivalent to the canonical three-clause rule is on
     * {@link DateEditValidator}, and it is what licenses keeping this shape rather than substituting the
     * canonical expression.</p>
     *
     * @param year the four-digit year to test, between {@link #MIN_YEAR} and {@link #MAX_YEAR} because that
     *     is the domain the baseline's year field can hold
     * @return {@code true} when February of that year has 29 days
     * @throws IllegalArgumentException if {@code year} lies outside the four-digit domain, since the
     *     year-within-century reduction below has no meaning for a value the baseline's field cannot hold
     */
    public static boolean isLeapYear(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new IllegalArgumentException(
                    "year " + year + " is outside the four-digit domain " + MIN_YEAR + " to " + MAX_YEAR);
        }

        // Refactoring Rationale: the divisor, the quotient and the remainder are locals here. The
        //     baseline had no choice but to hoist all three into its includer's storage, at
        //     app/cbl/COACTUPC.cbl lines 152, 154 and 157, because a procedure-only copybook has no data
        //     division of its own -- so three values with no lifetime beyond this computation became
        //     program-wide state that any other paragraph could read or overwrite.
        int yearWithinCentury = year % CENTURY_MODULUS;
        int divisor = yearWithinCentury == 0 ? LEAP_CENTURY_DIVISOR : LEAP_ORDINARY_DIVISOR;

        // Assumptions: the remainder is taken in integer arithmetic. The baseline's three scratch
        //     variables are packed four-digit signed integers, so its division is exact, and an
        //     approximate division would place the leap-year boundary wrong on some years and right on
        //     others -- a failure that shows up only on the years it happens to hit.
        int remainder = year % divisor;
        return remainder == 0;
    }

    /**
     * Evaluates one date against one mask the way the baseline's date-service wrapper evaluates it.
     *
     * <p>This is the migrated form of {@code app/cbl/CSUTLDTC.cbl}, which wraps a Language Environment date
     * service and maps its eight-byte feedback token onto a severity, a message number and a verdict text
     * through the ten-armed evaluation at lines 128 to 149. The published outcomes are
     * {@link FeedbackCode}, and the trap in the first of them -- an all-zero token that means acceptance
     * while its condition name says the opposite -- is set out on {@link DateEditValidator} and again on
     * {@link FeedbackCode#INVALID_DATE}.</p>
     *
     * <p>Assumptions: this models the real nine-token set, which is the set the parity oracle's unit layer
     * models, so every rejection it reports carries {@link #SEVERITY_ERROR}. It never reports
     * {@link FeedbackCode#OTHER}; that outcome arises only where a coarser stand-in returns a token
     * matching none of the nine, which the oracle's integration layer does deliberately.</p>
     *
     * @param date the date text to evaluate, whose width must match the mask: eight characters for
     *     {@link #BASELINE_DATE_FORMAT_MASK} and ten for {@link #DATE_FORMAT_MASK}, and whose
     *     separator positions must hold {@link #MASK_SEPARATOR} when the mask declares one
     * @param mask the mask describing that text; a mask this method does not recognise yields
     *     {@link FeedbackCode#BAD_PIC_STRING} rather than an exception, because the baseline service
     *     declares a condition for exactly that case and returns it like any other feedback
     * @return the outcome, carrying the feedback code, its severity and message number, the verdict text
     *     and the submitted date and mask echoed back
     * @throws NullPointerException if {@code date} or {@code mask} is {@code null}
     * @throws DateWidthException if {@code date} is not the width the recognised {@code mask}
     *     declares, since the service reads its components by offset and a shorter text has no components
     *     to read; the type is distinct from the invariant failures this class also reports through the
     *     same supertype, so a boundary can answer this case alone as a caller refusal
     */
    public static LanguageEnvironmentResult evaluateWithLanguageEnvironment(String date, String mask) {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(mask, "mask must not be null");

        String yearText;
        String monthText;
        String dayText;
        if (DATE_FORMAT_MASK.equals(mask)) {
            requireWidth(date, MASKED_DATE_LENGTH, mask);

            // Refactoring Rationale: this check was missing, and its absence made the two public
            //     entry points of this class disagree about the same value. The components below are
            //     sliced AROUND positions four and seven because the mask declares a hyphen at each,
            //     and nothing established that either position actually held one -- so
            //     "2024/02/29", and even "2024x02y29", were accepted here and reported as valid
            //     dates, while newContext rejects exactly those values for lacking their
            //     separators. One class cannot hold two answers about whether a value matches a
            //     mask; a caller choosing between the two entry points would be choosing a verdict.
            if (date.charAt(MASK_FIRST_SEPARATOR_INDEX) != MASK_SEPARATOR
                    || date.charAt(MASK_SECOND_SEPARATOR_INDEX) != MASK_SEPARATOR) {
                return feedback(FeedbackCode.BAD_DATE_VALUE, date, mask);
            }

            yearText = date.substring(PACKED_YEAR_START, PACKED_YEAR_END);
            monthText = date.substring(MASKED_MONTH_START, MASKED_MONTH_END);
            dayText = date.substring(MASKED_DAY_START, MASKED_DATE_LENGTH);
        } else if (BASELINE_DATE_FORMAT_MASK.equals(mask)) {
            requireWidth(date, PACKED_DATE_LENGTH, mask);
            yearText = date.substring(PACKED_YEAR_START, PACKED_YEAR_END);
            monthText = date.substring(PACKED_MONTH_START, PACKED_MONTH_END);
            dayText = date.substring(PACKED_DAY_START, PACKED_DAY_END);
        } else {
            // Assumptions: an unrecognised mask is reported as feedback rather than raised, because
            //     the baseline service declares a condition for a mask it cannot interpret at
            //     app/cbl/CSUTLDTC.cbl line 68 and selects a verdict for it at lines 141 and 142. Raising
            //     instead would deny a caller the one outcome the baseline gives it for this case.
            return feedback(FeedbackCode.BAD_PIC_STRING, date, mask);
        }

        int year = digitsToValue(yearText, YEAR_WIDTH);
        int month = digitsToValue(monthText, COMPONENT_WIDTH);
        int day = digitsToValue(dayText, COMPONENT_WIDTH);
        if (year == NOT_NUMERIC || month == NOT_NUMERIC || day == NOT_NUMERIC) {
            return feedback(FeedbackCode.NON_NUMERIC_DATA, date, mask);
        }
        if (year == 0) {
            return feedback(FeedbackCode.YEAR_IN_ERA_ZERO, date, mask);
        }
        if (month < MIN_VALID_MONTH || month > MAX_VALID_MONTH) {
            return feedback(FeedbackCode.INVALID_MONTH, date, mask);
        }
        if (day < MIN_VALID_DAY || day > daysInMonth(year, month)) {
            return feedback(FeedbackCode.BAD_DATE_VALUE, date, mask);
        }

        // Assumptions: the supported calendar has an inclusive lower bound and a date below it is
        //     reported as out of range rather than as malformed. The oracle suite pins the boundary from
        //     both sides -- its unit layer accepts the floor date and reports the day below it as an
        //     out-of-range feedback at tests/cobol-unit/CSUTLDTC_test.cbl lines 178 to 210 -- so the day
        //     below the floor is a well-formed calendar date that the service still declines.
        if (LocalDate.of(year, month, day).isBefore(GREGORIAN_FLOOR)) {
            return feedback(FeedbackCode.UNSUPP_RANGE, date, mask);
        }
        return feedback(FeedbackCode.INVALID_DATE, date, mask);
    }

    /**
     * Gate one: examines the century-and-year component.
     *
     * <p>This is {@code app/cpy/CSUTLDPY.cpy} lines 25 to 87. It pre-sets its marker unacceptable at line
     * 27, then tests three things in order: that the component was supplied at all, at lines 30 and 31;
     * that it is four digits, at line 48; and that its century is one of the two the baseline accepts, at
     * lines 70 and 71. Each failure returns, which reproduces a branch to the empty exit paragraph at line
     * 88 -- and that paragraph falls through into gate two, so returning here does not stop the chain.</p>
     *
     * @param context the accumulator to record this gate's verdict into
     */
    private static void editYearCcyy(EditContext context) {
        // Trade-offs: this gate assumes failure until proven otherwise, reproducing line 27, while
        //     gate three assumes success. The asymmetry is carried rather than made uniform because the
        //     two initialisers are not interchangeable; the reasoning is on DateEditValidator. The text
        //     is null because a pre-set marker has nothing to report yet.
        context.mark(FIELD_YEAR, FieldValidationFlag.NOT_OK, null);

        if (FieldValidationFlag.isNeverSupplied(context.rawYear)) {
            context.fail(FieldValidationFlag.BLANK, MSG_YEAR_REQUIRED, FIELD_YEAR);
            return;
        }

        if (context.yearValue == NOT_NUMERIC) {
            context.fail(FieldValidationFlag.NOT_OK, MSG_YEAR_NOT_FOUR_DIGITS, FIELD_YEAR);
            return;
        }

        // Assumptions: only two century values are accepted, and the baseline says why in its own
        //     comment at lines 66 to 68 -- that it codes only these two, having been unable to imagine
        //     the language still running in the century after them. Widening the domain would accept
        //     dates the baseline rejects, so the pair is carried exactly.
        if (context.centuryValue != THIS_CENTURY && context.centuryValue != LAST_CENTURY) {
            context.fail(FieldValidationFlag.NOT_OK, MSG_CENTURY_INVALID, FIELD_YEAR);
            return;
        }

        context.mark(FIELD_YEAR, FieldValidationFlag.VALID, null);
    }

    /**
     * Gate two: examines the month component.
     *
     * <p>This is {@code app/cpy/CSUTLDPY.cpy} lines 91 to 144. It pre-sets its marker unacceptable at line
     * 92, tests that the component was supplied at lines 94 and 95, then tests its domain at line 111 and
     * that it is numeric at line 126. A failure returns, reproducing a branch to the empty exit paragraph
     * at line 145, which falls through into gate three.</p>
     *
     * @param context the accumulator to record this gate's verdict into
     */
    private static void editMonth(EditContext context) {
        context.mark(FIELD_MONTH, FieldValidationFlag.NOT_OK, null);

        if (FieldValidationFlag.isNeverSupplied(context.rawMonth)) {
            context.fail(FieldValidationFlag.BLANK, MSG_MONTH_REQUIRED, FIELD_MONTH);
            return;
        }

        // Assumptions: the baseline's two remaining arms are collapsed into one test here, and the
        //     collapse changes nothing a caller can observe. The baseline tests the domain first, at line
        //     111, against a condition declared over a numeric redefinition of two characters, and tests
        //     numerically second, at line 126; a non-numeric pair of characters equals none of the twelve
        //     values that condition enumerates, so the domain arm is the one that fires for it. Both arms
        //     assemble the identical text, at lines 119 and 136, so which one fired is invisible. Gate
        //     three orders the same two arms the other way round, and that asymmetry is likewise
        //     unobservable for the same reason.
        if (context.monthValue < MIN_VALID_MONTH || context.monthValue > MAX_VALID_MONTH) {
            context.fail(FieldValidationFlag.NOT_OK, MSG_MONTH_RANGE, FIELD_MONTH);
            return;
        }

        context.mark(FIELD_MONTH, FieldValidationFlag.VALID, null);
    }

    /**
     * Gate three: examines the day component.
     *
     * <p>This is {@code app/cpy/CSUTLDPY.cpy} lines 150 to 204. It pre-sets its marker ACCEPTABLE at line
     * 152 -- the one initialisation of the four that assumes success -- tests that the component was
     * supplied at lines 154 and 155, then tests that it is numeric at line 170 and that it is within its
     * domain at line 187. A failure returns, reproducing a branch to the empty exit paragraph at line 205,
     * which falls through into gate four.</p>
     *
     * @param context the accumulator to record this gate's verdict into
     */
    private static void editDay(EditContext context) {
        // Trade-offs: this pre-set assumes SUCCESS where gates one and two assume failure, and the
        //     opposite polarity is reproduced rather than aligned with them. No route through this gate
        //     observes the difference today, because every arm below ends in an explicit marking; the
        //     initialiser is the contract for a route that does not yet exist, and substituting the
        //     pessimistic form would hand such a route the opposite verdict. The reasoning is on
        //     DateEditValidator.
        context.mark(FIELD_DAY, FieldValidationFlag.VALID, null);

        if (FieldValidationFlag.isNeverSupplied(context.rawDay)) {
            context.fail(FieldValidationFlag.BLANK, MSG_DAY_REQUIRED, FIELD_DAY);
            return;
        }

        if (context.dayValue < MIN_VALID_DAY || context.dayValue > MAX_VALID_DAY) {
            context.fail(FieldValidationFlag.NOT_OK, MSG_DAY_RANGE, FIELD_DAY);
            return;
        }

        context.mark(FIELD_DAY, FieldValidationFlag.VALID, null);
    }

    /**
     * Gate four: examines the three components in combination, then decides whether the chain continues.
     *
     * <p>This is {@code app/cpy/CSUTLDPY.cpy} lines 209 to 279. Three arms test combinations no calendar
     * admits -- 31 days in a month that has 30, at lines 213 to 226; 30 days in February, at lines 228 to
     * 241; and 29 days in a February of a non-leap year, at lines 243 to 272 -- and each branches straight
     * to the outer terminus at line 329. The closing checkpoint at lines 274 to 279 then branches there too
     * whenever any marker is already in error, which is the one construct that decides whether the fifth
     * gate runs at all.</p>
     *
     * <p>Assumptions: this gate runs on a date the gates before it may already have rejected, because it is
     * reached by fall-through and its checkpoint sits at its foot rather than at its head. Its three arms
     * therefore read component values that may have no numeric meaning, exactly as the baseline's
     * conditions do: a condition declared over a numeric redefinition of two non-numeric characters matches
     * none of its enumerated values, so an unusable component simply fails to match. That is reproduced by
     * resolving an unusable component to a sentinel that equals no constant.</p>
     *
     * @param context the accumulator to record this gate's verdict into
     * @return {@code true} when the chain continues into the fifth gate, {@code false} when any of this
     *     gate's four exit routes has been taken
     */
    private static boolean editDayMonthYear(EditContext context) {
        // Assumptions: this arm marks TWO components, the day and the month, reproducing lines 215 to
        //     217. It does not mark the year. Marking three here would highlight a year the baseline
        //     leaves untouched, and the census of widths on DateEditValidator is what records that three
        //     of the five failure modes mark three components while two mark only two.
        if (!THIRTY_ONE_DAY_MONTHS.contains(context.monthValue) && context.dayValue == DAY_31) {
            context.fail(FieldValidationFlag.NOT_OK, MSG_NO_31_DAYS, FIELD_DAY, FIELD_MONTH);
            return false;
        }

        // Assumptions: this second two-component arm is easy to overlook and is not a variant of the
        //     one above it. It has its own condition pair at lines 228 and 229, its own markings at lines
        //     231 and 232 and its own message text at line 236, and it too leaves the year untouched.
        if (context.monthValue == FEBRUARY && context.dayValue == DAY_30) {
            context.fail(FieldValidationFlag.NOT_OK, MSG_NO_30_DAYS, FIELD_DAY, FIELD_MONTH);
            return false;
        }

        if (context.monthValue == FEBRUARY && context.dayValue == DAY_29) {
            if (!isLeapYearOfSuppliedComponents(context)) {
                // Assumptions: this arm marks THREE components, reproducing the four marking
                //     statements at lines 259 to 262 -- the error switch plus the day, the month AND the
                //     year. A 29 February in a non-leap year is a fault of the year as much as of the day,
                //     and narrowing this to the day alone would leave two of the three components
                //     unhighlighted where the baseline highlights all three.
                context.fail(
                        FieldValidationFlag.NOT_OK,
                        MSG_NOT_LEAP_YEAR,
                        FIELD_DAY,
                        FIELD_MONTH,
                        FIELD_YEAR);
                return false;
            }
        }

        // Assumptions: this is the checkpoint at lines 274 to 279 and it is the only true early exit
        //     in the whole chain. It tests the group-level aggregate condition, so it consults the three
        //     markers and deliberately not the caller-owned error switch, which the baseline declares
        //     outside that group. Only an all-clear reaches the fifth gate.
        return context.aggregateValid();
    }

    /**
     * Gate five: submits the date to the Language Environment service as a backstop.
     *
     * <p>This is {@code app/cpy/CSUTLDPY.cpy} lines 284 to 328. The baseline's own banner at lines 286 and
     * 287 describes the gate as covering the case where a bad date reached it despite the four gates ahead
     * of it. It initialises the result structure at line 290, sets the mask at line 291, calls the service
     * at lines 293 to 296 and tests the severity at line 298. Its success arm re-affirms the day marker at
     * lines 318 to 320; its failure arm marks all three components at lines 301 to 304 and assembles the
     * service message at lines 305 to 314.</p>
     *
     * <p>Assumptions: this method carries the one divergence in the file. In the baseline, both routes out
     * of this gate reach line 327, which sets the group-level acceptable condition and thereby clears the
     * three markings the failure arm has just made, while the caller-owned error switch survives outside
     * that group. This method keeps the markings. The full geometry, the reason the branch at line 315 is
     * not what causes the clearing, the reason the markings are kept, and the measurement showing that the
     * failure arm below is unreachable through {@link DateEditValidator#validate(String, String)} are all on
     * {@link DateEditValidator}.</p>
     *
     * @param context the accumulator to record this gate's verdict into
     */
    private static void editDateLanguageEnvironment(EditContext context) {
        // Refactoring Rationale: the ten-character mask is used here where the baseline moves an
        //     eight-character one at line 291. Five of the six baseline call sites use ten characters and
        //     the service's own linkage declares ten, at app/cbl/CSUTLDTC.cbl lines 84 and 85, so the
        //     eight-character move supplies narrower fields than its callee declares. This type does not
        //     reproduce that width difference, and the divergence is recorded on DateEditValidator.
        LanguageEnvironmentResult result =
                evaluateWithLanguageEnvironment(maskedDate(context), DATE_FORMAT_MASK);
        context.languageEnvironment = result;

        // Assumptions: acceptance is a severity of zero, reproducing line 298, and the outcome that
        //     carries a zero severity is the one whose baseline condition name says the opposite. Testing
        //     the severity rather than the condition name is what keeps this the right way round.
        if (result.severity() == SEVERITY_VALID) {
            // Assumptions: the day marker is re-affirmed only when nothing has failed anywhere
            //     earlier, reproducing lines 318 to 320. In the baseline the switch it consults is
            //     program-wide, so an unrelated field's failure could leave this arm untaken; here the
            //     switch is scoped to one date, and the checkpoint at lines 274 to 279 has already
            //     required all three markers to be acceptable, so the guard's false branch is
            //     unreachable within one call. It is reproduced rather than dropped because dropping it
            //     would assert unconditionally what the baseline asserts conditionally.
            if (!context.inputError) {
                context.mark(FIELD_DAY, FieldValidationFlag.VALID, null);
            }
            return;
        }

        // Assumptions: the message is assembled from the label and four fragments in the order the
        //     assembly at lines 306 to 313 lists them -- the trimmed label, a severity fragment, the
        //     four-character severity, a message-code fragment and the four-character message number.
        //     Both numbers are inserted at the four-character width their fields declare, which is why
        //     they are emitted as zero-padded digits rather than in their shortest form.
        String assembled = MSG_LE_SEVERITY_FRAGMENT
                + zoned(result.severity())
                + MSG_LE_MESSAGE_CODE_FRAGMENT
                + zoned(result.messageNumber());

        // Trade-offs: these three markings are RETAINED, where the baseline clears them at line 327
        //     on both routes out of this gate. The compromise is a visible difference on this one
        //     rejection mode; the alternative leaves the migrated per-field error array empty for it, so
        //     a response body would announce a failure with no field attached and a client would have
        //     nothing to render beside any field. The baseline clears them, this method keeps them, and
        //     the divergence is recorded on DateEditValidator.
        context.fail(
                FieldValidationFlag.NOT_OK, assembled, FIELD_DAY, FIELD_MONTH, FIELD_YEAR);
    }

    /**
     * Reports whether the supplied year component names a leap year.
     *
     * <p>Assumptions: an unusable year component is reported as not a leap year, and that is a documented
     * divergence rather than a reproduction. The baseline divides a numeric redefinition laid over
     * non-numeric characters, at lines 251 to 254, which has no defined result. This method declines the
     * leap year instead. The route is reachable only when gate one has already marked the year, so the
     * outcome is unacceptable either way and the message the chain reports is already latched from gate
     * one's own failure; what the divergence can change is the month and day markers, and both changes are
     * towards reporting more of a date that is being rejected regardless.</p>
     *
     * @param context the accumulator holding the year component to test
     * @return {@code true} when the year component is usable and names a leap year
     */
    private static boolean isLeapYearOfSuppliedComponents(EditContext context) {
        if (context.yearValue == NOT_NUMERIC) {
            return false;
        }

        // Trade-offs: the divisor selection needs the year-within-century value, which the baseline
        //     reads from a component it redefines separately at app/cpy/CSUTLDWY.cpy line 12, and this
        //     passes the whole year instead and lets isLeapYear reduce it. Reading that component here
        //     and selecting the divisor here was the alternative and was rejected: it would put a second
        //     copy of the rule in this file, and two copies of one predicate is exactly the condition the
        //     equivalence proof on this class exists to make unnecessary. The reduction and the component
        //     agree for every usable year, because a four-digit run of digits has a trailing pair of
        //     digits with the same value as the year taken modulo one hundred.
        return isLeapYear(context.yearValue);
    }

    /**
     * Builds the accumulator for one edit, splitting the supplied date into its three components.
     *
     * <p>Assumptions: two widths carry content and both are accepted. The eight-character unseparated form
     * is what the baseline's own date field holds, laid out four, two and two at
     * {@code app/cpy/CSUTLDWY.cpy} lines 5, 16 and 25; the ten-character separated form is what five of the
     * six baseline service call sites use. A value carrying no content at all -- absent, empty, wholly the
     * lowest character in the collating sequence, or wholly spaces -- is split into three absent components,
     * which is what the baseline's includer produces for an unsupplied field when it moves that figurative
     * constant into each component at {@code app/cbl/COACTUPC.cbl} lines 1258, 1265 and 1272.</p>
     *
     * @param fieldLabel the label to report against, reduced and trimmed here
     * @param date the date text to split, or {@code null} when nothing arrived for the field
     * @return a fresh accumulator carrying the label and the three component texts
     * @throws NullPointerException if {@code fieldLabel} is {@code null}
     * @throws IllegalArgumentException if {@code fieldLabel} is empty or wholly whitespace, or if
     *     {@code date} carries content at an unrecognised width or lacks its separators
     */
    private static EditContext newContext(String fieldLabel, String date) {
        String label = normalizeLabel(fieldLabel);

        // Assumptions: the no-content case is decided before the width cases, using the same
        //     two-armed test the gates use per component, because a request can present an absent field
        //     as null or as an empty string while a terminal presents it as a run of pad characters. All
        //     three describe a field nobody filled in, and none of them can be split by offset.
        if (date == null || date.isEmpty() || FieldValidationFlag.isNeverSupplied(date)) {
            return new EditContext(label, "", "", "");
        }

        if (date.length() == PACKED_DATE_LENGTH) {
            return new EditContext(
                    label,
                    date.substring(PACKED_YEAR_START, PACKED_YEAR_END),
                    date.substring(PACKED_MONTH_START, PACKED_MONTH_END),
                    date.substring(PACKED_DAY_START, PACKED_DAY_END));
        }

        if (date.length() == MASKED_DATE_LENGTH
                && date.charAt(MASK_FIRST_SEPARATOR_INDEX) == MASK_SEPARATOR
                && date.charAt(MASK_SECOND_SEPARATOR_INDEX) == MASK_SEPARATOR) {
            return new EditContext(
                    label,
                    date.substring(PACKED_YEAR_START, PACKED_YEAR_END),
                    date.substring(MASKED_MONTH_START, MASKED_MONTH_END),
                    date.substring(MASKED_DAY_START, MASKED_DATE_LENGTH));
        }

        // Refactoring Rationale: the two ways of arriving here are now reported separately. One
        //     message covered both, so a ten-character value whose separators were wrong was told
        //     that it "carries content at width 10" while the same sentence listed a ten-character
        //     form as accepted -- a contradiction a reader has to decode. The separator case is now
        //     named as itself, which also makes this rejection legible beside the BAD_DATE_VALUE
        //     feedback evaluateWithLanguageEnvironment reports for the very same value. Neither
        //     message carries any part of the date: the width and the mask are the whole diagnosis,
        //     and the field one caller passes is a date of birth, which the shared record layout
        //     classifies as sensitive at CUST-DOB-YYYY-MM-DD in app/cpy/CVCUS01Y.cpy line 19.
        if (date.length() == MASKED_DATE_LENGTH) {
            throw new IllegalArgumentException(
                    "a " + MASKED_DATE_LENGTH + "-character date must carry '" + MASK_SEPARATOR
                            + "' at positions " + (MASK_FIRST_SEPARATOR_INDEX + 1) + " and "
                            + (MASK_SECOND_SEPARATOR_INDEX + 1) + " to match " + DATE_FORMAT_MASK
                            + ", and the supplied date carries something else at one of them");
        }

        // Trade-offs: an unrecognised width is raised rather than reported as an unacceptable date.
        //     Reporting it would be gentler on the caller and was rejected, because the two accepted
        //     widths are both structural contracts -- one from the baseline's field layout, one from the
        //     service's linkage -- so a third width means the caller assembled the value wrongly, and
        //     attributing that to the user's input would send a field error for a fault the user cannot
        //     correct. The separator case above is raised for the same reason: the map field the
        //     baseline reads from carries its two hyphens as map literals, so a value reaching this
        //     method without them was assembled by the caller rather than typed by a user.
        throw new IllegalArgumentException(
                "date carries content at width " + date.length() + ", but only the "
                        + PACKED_DATE_LENGTH + "-character unseparated form and the "
                        + MASKED_DATE_LENGTH + "-character " + DATE_FORMAT_MASK + " form are accepted");
    }

    /**
     * Reduces a field label to the width the baseline's label field declares and then trims it.
     *
     * <p>Assumptions: the two steps are the two the baseline performs. A move into the label field at
     * {@code app/cbl/COACTUPC.cbl} line 53 reduces anything longer to 25 characters and pads anything
     * shorter with spaces; the assembly at {@code app/cpy/CSUTLDPY.cpy} line 307 then trims what it reads.
     * The reduction bites on that program's longest labels but on none of its four date labels, whose
     * longest is 13 characters, so the date path never sees a reduced label -- and the step is applied
     * anyway, because a caller may pass a longer one and the baseline would have reduced it.</p>
     *
     * @param fieldLabel the label as supplied
     * @return the reduced and trimmed label, never empty
     * @throws NullPointerException if {@code fieldLabel} is {@code null}
     * @throws IllegalArgumentException if {@code fieldLabel} is empty or reduces to nothing but whitespace,
     *     because every reported message is prefixed with it and a message opening with punctuation alone
     *     names no field for a client to attach it to
     */
    private static String normalizeLabel(String fieldLabel) {
        Objects.requireNonNull(fieldLabel, "fieldLabel must not be null");
        String reduced = fieldLabel.length() > FIELD_LABEL_LENGTH
                ? fieldLabel.substring(0, FIELD_LABEL_LENGTH)
                : fieldLabel;
        String trimmed = reduced.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("fieldLabel must carry content, but was blank");
        }
        return trimmed;
    }

    /**
     * Assembles the ten-character separated form of the date an accumulator holds.
     *
     * <p>Assumptions: this is only ever called from the fifth gate, which the checkpoint at
     * {@code app/cpy/CSUTLDPY.cpy} lines 274 to 279 reaches only when all three markers are acceptable, so
     * all three components are present and are digits at their declared widths. That is what makes the
     * assembly unconditional.</p>
     *
     * @param context the accumulator holding the three component texts
     * @return the date in the ten-character separated form the service's linkage declares
     */
    private static String maskedDate(EditContext context) {
        return context.rawYear + MASK_SEPARATOR + context.rawMonth + MASK_SEPARATOR + context.rawDay;
    }

    /**
     * Resolves the date an accumulator holds to a calendar date.
     *
     * @param context the accumulator holding the three component texts
     * @param date the date text as supplied, used only to describe the fault if it cannot be resolved
     * @return the calendar date the components name
     * @throws IllegalArgumentException if the components do not name a well-formed calendar date, which is a
     *     caller fault rather than a user fault because the baseline reaches its date-of-birth range only
     *     once the general edit has passed, at {@code app/cbl/COACTUPC.cbl} line 1539
     */
    private static LocalDate toCalendarDate(EditContext context, String date) {
        if (context.yearValue == NOT_NUMERIC
                || context.monthValue < MIN_VALID_MONTH
                || context.monthValue > MAX_VALID_MONTH
                || context.dayValue < MIN_VALID_DAY
                || context.dayValue > daysInMonth(context.yearValue, context.monthValue)) {
            // Assumptions: the message describes only the SHAPE of the submitted value and never
            //     interpolates the value itself, because the one caller that reaches this method is
            //     the date-of-birth gate -- a field the shared record layout classifies as
            //     sensitive, at CUST-DOB-YYYY-MM-DD in app/cpy/CVCUS01Y.cpy line 19. An
            //     IllegalArgumentException message reaches a stack trace, and a stack trace reaches
            //     a log aggregator and sometimes an error response, so interpolating the date would
            //     leave it one unhandled exception away from a place it must not be. What an
            //     operator needs in order to act is which component was unacceptable, and the fault
            //     is in the caller's ordering rather than in the value's digits.
            throw new IllegalArgumentException(
                    "the date-of-birth test requires a date the general edit has already accepted, but "
                            + describe(date) + " is not a well-formed calendar date: "
                            + componentVerdicts(context));
        }
        return LocalDate.of(context.yearValue, context.monthValue, context.dayValue);
    }

    /**
     * Describes which of the three date components an accumulator holds are unacceptable.
     *
     * <p>Assumptions: this reports a VERDICT per component and never a component's value. The month
     * and the day are bounded small integers whose acceptable ranges are public, so naming which one
     * is out of range identifies the fault; naming the value would additionally disclose part of a
     * field the layout classifies as sensitive, and would identify nothing the range does not.</p>
     *
     * @param context the accumulator whose three component values are to be judged
     * @return a comma-separated rendering naming each unacceptable component and the constraint it
     *     failed, or a phrase stating that each component is individually acceptable -- which happens
     *     when only the combination is invalid, a day of 31 in a 30-day month being the instance
     */
    private static String componentVerdicts(EditContext context) {
        StringBuilder verdicts = new StringBuilder();
        if (context.yearValue == NOT_NUMERIC) {
            verdicts.append("the year is not four digits");
        }
        if (context.monthValue < MIN_VALID_MONTH || context.monthValue > MAX_VALID_MONTH) {
            appendVerdict(verdicts, "the month is outside " + MIN_VALID_MONTH + " to " + MAX_VALID_MONTH);
        }
        if (context.dayValue < MIN_VALID_DAY || context.dayValue > MAX_VALID_DAY) {
            appendVerdict(verdicts, "the day is outside " + MIN_VALID_DAY + " to " + MAX_VALID_DAY);
        } else if (context.yearValue != NOT_NUMERIC
                && context.monthValue >= MIN_VALID_MONTH
                && context.monthValue <= MAX_VALID_MONTH
                && context.dayValue > daysInMonth(context.yearValue, context.monthValue)) {
            appendVerdict(verdicts, "the day exceeds the "
                    + daysInMonth(context.yearValue, context.monthValue)
                    + " days that month of that year holds");
        }

        return verdicts.isEmpty()
                ? "each component is individually within range, so the combination is what fails"
                : verdicts.toString();
    }

    /**
     * Appends one verdict to a rendering, separating it from any already present.
     *
     * <p>Trade-offs: a separator is applied conditionally here rather than every verdict being
     * collected into a list and joined. The list form reads better in isolation and was rejected
     * because it allocates a collection on a path that exists only to describe a caller fault, and
     * because this helper keeps the three call sites above one line each.</p>
     *
     * @param verdicts the rendering to append to, appended in place
     * @param verdict the verdict text to add
     */
    private static void appendVerdict(StringBuilder verdicts, String verdict) {
        if (!verdicts.isEmpty()) {
            verdicts.append(", ");
        }
        verdicts.append(verdict);
    }

    /**
     * Returns the number of days in one month of one year.
     *
     * <p>Assumptions: the three cases are the three the baseline's own domain constants describe -- the
     * seven months carrying 31 days at {@code app/cpy/CSUTLDWY.cpy} lines 21 to 23, February at line 24,
     * and everything else. February's length is resolved through {@link #isLeapYear(int)} rather than by a
     * second leap-year expression, so the rule has one implementation in this file and not two.</p>
     *
     * @param year the four-digit year the month belongs to, which decides February's length
     * @param month the month to measure, between {@link #MIN_VALID_MONTH} and {@link #MAX_VALID_MONTH}
     * @return the number of days in that month, between {@link #MAX_VALID_FEBRUARY_DAY} and
     *     {@link #MAX_VALID_DAY}
     * @throws IllegalArgumentException if {@code year} lies outside the four-digit domain
     */
    private static int daysInMonth(int year, int month) {
        if (THIRTY_ONE_DAY_MONTHS.contains(month)) {
            return DAY_31;
        }
        if (month == FEBRUARY) {
            return isLeapYear(year) ? DAY_29 : MAX_VALID_FEBRUARY_DAY;
        }
        return DAY_30;
    }

    /**
     * Builds the service outcome for one feedback code.
     *
     * @param code the feedback outcome the service reported
     * @param date the date text as submitted, echoed into the result the way the service echoes it
     * @param mask the mask text as submitted, echoed into the result the same way
     * @return the outcome, with its severity, message number and verdict taken from the feedback code so
     *     that the three can never disagree
     */
    private static LanguageEnvironmentResult feedback(FeedbackCode code, String date, String mask) {
        return new LanguageEnvironmentResult(
                code, code.severity(), code.messageNumber(), code.verdict(), date, mask);
    }

    /**
     * Reports whether a component text is a run of digits at exactly its declared width, and returns its
     * value when it is.
     *
     * <p>Assumptions: this is the migrated form of reading a numeric redefinition laid over a character
     * field. The baseline's conditions compare the raw bytes against the encoded forms of their enumerated
     * values, so a character field holding anything but digits at its full width matches no value any
     * condition names -- including a field holding a digit and a space, which is neither numeric nor equal
     * to any encoded pair. Returning {@link #NOT_NUMERIC} reproduces that: the sentinel is negative, so it
     * lies below every domain this file tests and matches no constant.</p>
     *
     * @param text the component text to read, which may be empty when the component was never supplied
     * @param width the declared width of that component, either {@link #YEAR_WIDTH} or
     *     {@link #COMPONENT_WIDTH}
     * @return the component's value, or {@link #NOT_NUMERIC} when the text is not a run of exactly
     *     {@code width} ASCII digits
     */
    private static int digitsToValue(String text, int width) {
        if (text.length() != width) {
            return NOT_NUMERIC;
        }
        int value = 0;
        for (int index = 0; index < width; index++) {
            char digit = text.charAt(index);

            // Assumptions: only the ten ASCII digits count, so the platform's general digit test is
            //     deliberately not used -- it accepts digits from every script the character set defines,
            //     and a component holding one of those would be reported numeric here while the baseline's
            //     byte comparison would reject it.
            if (digit < '0' || digit > '9') {
                return NOT_NUMERIC;
            }
            value = value * 10 + (digit - '0');
        }
        return value;
    }

    /**
     * Renders a value as four zero-padded digits, the width the severity and message-number fields declare.
     *
     * <p>Assumptions: four characters is the declared width of both fields, at
     * {@code app/cpy/CSUTLDWY.cpy} lines 61 and 66, and the zero-padded form is what the two tolerant
     * baseline callers compare against when they test the severity for its four-character acceptable value
     * at {@code app/cbl/COTRN02C.cbl} line 397.</p>
     *
     * @param value the value to render, between zero and 9999 because that is what a four-digit field holds
     * @return exactly four decimal digits
     * @throws IllegalArgumentException if {@code value} lies outside the four-digit domain, since a wider
     *     value cannot be rendered into the declared width without losing digits a caller compares against
     */
    private static String zoned(int value) {
        if (value < MIN_YEAR || value > MAX_YEAR) {
            throw new IllegalArgumentException(
                    "value " + value + " does not fit the " + ZONED_FIELD_WIDTH + "-digit field width");
        }
        StringBuilder rendered = new StringBuilder(Integer.toString(value));
        while (rendered.length() < ZONED_FIELD_WIDTH) {
            rendered.insert(0, '0');
        }
        return rendered.toString();
    }

    /**
     * Pads a text with spaces or reduces it, so that it occupies exactly one declared width.
     *
     * <p>Assumptions: this is what a move into a character field of declared width does -- pad on the right
     * with spaces when the source is shorter, truncate on the right when it is longer. Both directions are
     * reachable in the layouts this file reproduces: two of the ten verdict texts are written shorter than
     * their field and are padded, and two of the thirteen message texts exceed the message field once a
     * label at its full width is prefixed and are reduced.</p>
     *
     * @param value the text to fit
     * @param width the declared width to fit it to
     * @return the text at exactly {@code width} characters
     */
    private static String padOrTruncate(String value, int width) {
        if (value.length() == width) {
            return value;
        }
        if (value.length() > width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Describes a supplied date text for a failure message by its width rather than by its content.
     *
     * <p>Refactoring Rationale: this rendered the text inside quotation marks and now renders only its
     * width. Its one caller is {@link #toCalendarDate(EditContext, String)}, which the date-of-birth gate
     * reaches, and the shared record layout classifies that field as sensitive at
     * {@code CUST-DOB-YYYY-MM-DD} in {@code app/cpy/CVCUS01Y.cpy} line 19. The rendering travels inside an
     * {@link IllegalArgumentException} message, so every stack trace that fault produced carried a date of
     * birth into a log aggregator and, on an unhandled path, into an error response.</p>
     *
     * @param date the date text as supplied, which may be {@code null}; read only for its length
     * @return a phrase naming the text's width, or a phrase naming its absence; never any part of the text
     */
    private static String describe(String date) {
        return date == null ? "an absent date" : "the supplied " + date.length() + "-character date";
    }

    /**
     * Requires a date text to be exactly the width its mask declares.
     *
     * <p>Refactoring Rationale: the failure message quoted the measured text and now reports only the
     * expected width and the observed width. Both public entry points pass a caller-supplied date through
     * here, and one of them is the date-of-birth gate over a field the shared record layout classifies as
     * sensitive at {@code CUST-DOB-YYYY-MM-DD} in {@code app/cpy/CVCUS01Y.cpy} line 19; the message travels
     * in an exception, so the quoted form put that value into every stack trace this fault produced. A
     * width mismatch is diagnosed entirely by the two widths and the mask that declares one of them, so
     * removing the content removes nothing an operator acts on.</p>
     *
     * @param date the date text to measure; read only for its length
     * @param width the width the mask declares
     * @param mask the mask itself, named in the failure message so a caller can see which pairing failed
     * @throws DateWidthException if {@code date} is not exactly {@code width} characters; the message
     *     names the two widths and the mask, and never any part of {@code date}
     */
    private static void requireWidth(String date, int width, String mask) {
        if (date.length() != width) {
            throw new DateWidthException(
                    "mask " + mask + " declares a " + width + "-character date, but the supplied date is "
                            + date.length() + " characters");
        }
    }

    /**
     * The one condition this class raises about a CALLER's value rather than about an internal invariant.
     *
     * <p>Refactoring Rationale: this type exists because the width mismatch was previously raised as a
     * bare {@code IllegalArgumentException}, which left the only consumer able to recognise it -- the
     * synchronous date endpoint of the reference context -- with no choice but to catch that whole
     * supertype. Everything else this class raises with that supertype is an INTERNAL invariant: a
     * feedback record whose severity contradicts its own code, an unknown date-component identity, a year
     * outside the four-digit domain, a value too wide for a four-digit field. Catching the supertype
     * therefore reported each of those to the caller as a four-hundred naming the date parameter,
     * telling a caller its own input was at fault when the fault was in this class. A distinct type lets
     * the boundary catch exactly the caller-caused case and lets every invariant failure reach the
     * internal-failure channel the alerting watches.</p>
     *
     * <p>Alternatives Considered: having the width mismatch return a feedback verdict, the way an
     * unrecognised mask does. It was rejected because the two are different in the baseline. An
     * unrecognised picture has a declared condition and a selected verdict at
     * {@code app/cbl/CSUTLDTC.cbl} line 68 and lines 141 to 142, so returning it is transcription; a
     * date whose width disagrees with its picture has no such condition, because the platform passed
     * fixed-width linkage fields that could not disagree. Inventing a verdict for it would publish a
     * feedback code the utility never produces.</p>
     *
     * <p>Alternatives Considered: extending the shared caller-refusal type in
     * {@code com.carddemo.common.error} directly, so the shared advice would answer it as four hundred
     * with no boundary catch at all. It was rejected because this package is a validation kernel with no
     * dependency on the error-rendering package, and reversing that would put a web-facing concern
     * inside the rules; the boundary that renders HTTP is the right place to decide a status, and it can
     * now do so from a type that names the case precisely.</p>
     *
     * <p>Assumptions: the supertype is retained rather than exchanged for a checked exception. Every
     * other consumer of this class treats a width disagreement as programmer error against a value it
     * has already bounded, and making it checked would oblige each of them to handle a case they have
     * excluded upstream.</p>
     *
     * <p>Trade-offs: the message names the two widths and the mask, and never any part of the date. One
     * of this class's public entry points is the date-of-birth gate over a field the shared record layout
     * classifies as sensitive at {@code CUST-DOB-YYYY-MM-DD} in {@code app/cpy/CVCUS01Y.cpy} line 19, and
     * an exception message travels into every stack trace the fault produces. What is given up is that an
     * operator cannot see the offending characters; what it buys is that a date of birth cannot reach a
     * log aggregator, and a width disagreement is diagnosed entirely by the two widths anyway.</p>
     */
    public static final class DateWidthException extends IllegalArgumentException {

        /**
         * The serialization identity of this condition, declared rather than generated.
         *
         * <p>Assumptions: the inherited hierarchy is serializable and this class adds no member of its
         * own, so a fixed value is declared to keep the identity stable if one is ever added. A generated
         * identity changes with the shape of the class, which would break a peer holding a serialized
         * copy for no functional reason.</p>
         */
        private static final long serialVersionUID = 1L;

        /**
         * Builds the condition with a message naming the two widths and the mask.
         *
         * @param message the diagnostic, which names the declared width, the observed width and the mask,
         *     and must name no part of the date itself
         */
        public DateWidthException(String message) {
            super(message);
        }
    }
}
