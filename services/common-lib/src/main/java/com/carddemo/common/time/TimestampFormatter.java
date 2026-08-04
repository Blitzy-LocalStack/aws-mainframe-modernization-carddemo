package com.carddemo.common.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/**
 * Renders and reads back the twenty-six character timestamp contract shared by the migrated CardDemo
 * services.
 *
 * <h2>The contract</h2>
 *
 * <p>One rendering serves every origination and processing timestamp that crosses a persistence,
 * batch or API boundary: {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'}, realised by the
 * {@link DateTimeFormatter} pattern {@code uuuu-MM-dd HH:mm:ss.SSSSSS} resolved with
 * {@link ResolverStyle#STRICT}. Every element of that layout is contractual rather than
 * cosmetic:</p>
 *
 * <ul>
 *   <li>ten characters of ISO date, {@code YYYY-MM-DD};</li>
 *   <li>one SPACE between the date and the time, deliberately not the ISO 8601 {@code T};</li>
 *   <li>COLONS between the hour, the minute and the second;</li>
 *   <li>a PERIOD introducing the fractional second;</li>
 *   <li>exactly six fractional digits, so the value carries microsecond resolution and nothing
 *       finer;</li>
 *   <li>every component zero padded, so the rendered width never varies;</li>
 *   <li>{@code TIMESTAMP_LENGTH} characters in total, for every year the contract admits, which is
 *       {@code MIN_SUPPORTED_YEAR} through {@code MAX_SUPPORTED_YEAR} inclusive and is checked on
 *       every entry point rather than assumed.</li>
 * </ul>
 *
 * <h2>Strictness, and why it is part of the contract</h2>
 *
 * <p>Reading a value under this contract either yields the instant the value names or fails. It never
 * yields a NEIGHBOURING instant. That distinction is what the strict resolver buys, and it is not a
 * refinement: with the platform's default resolver, {@link #parse(String)} handed
 * {@code 2026-02-31 12:00:00.000000} returns the twenty-eighth of February rather than raising --
 * measured on this platform -- so an impossible date submitted through an API becomes a plausible
 * date three days away, with nothing anywhere reporting a problem. Every consumer here is a date that
 * must not move: the account expiration compared inclusively at {@code app/cbl/CBTRN02C.cbl} lines
 * 414 to 420, and the report range endpoints applied at {@code app/jcl/TRANREPT.jcl} lines 47 and 48.
 * The year range is the other half of the same guarantee -- it is what keeps a value this class
 * accepts renderable in exactly {@code TIMESTAMP_LENGTH} characters -- and both are argued in full on
 * the formatter constant and on {@code MAX_SUPPORTED_YEAR}.</p>
 *
 * <p>The same single value is declared three ways across the migration, and this class is what keeps
 * the three agreeing: {@code PIC X(26)} in the reference COBOL, {@code TIMESTAMP(6)} in the target
 * column, and {@link LocalDateTime} in the target Java type.</p>
 *
 * <h2>Obligation on callers</h2>
 *
 * <p>Format once per unit of work, then reuse the returned value for every record that unit of work
 * writes. Assumptions: the reference baseline builds its value exactly once per unit of work and
 * spends it several times. {@code app/cbl/CBTRN02C.cbl} line 437 performs the timestamp paragraph a
 * single time, line 438 stores the result, and lines 440 to 442 then drive three separate writes
 * from that one value, updating the category balance, then the account record, then the transaction
 * file. {@code app/cbl/CBACT04C.cbl} lines 496 to 498 make the same point in three lines: one
 * perform feeding two moves. Re-reading a clock per write would let the records of a single unit of
 * work disagree about when that work happened, which the baseline structurally cannot do, so no
 * method here reads a clock of its own accord.</p>
 *
 * <p>Bind the value returned by {@link #normalize(LocalDateTime)} or
 * {@link #normalizeNow(Clock)} to a database parameter, never a value read straight from a clock.
 * This is not a style preference and the difference was measured rather than inferred, on JDK
 * 21.0.11 with PostgreSQL JDBC 42.7.13 against a real {@code TIMESTAMP(6)} column. The two halves of
 * the boundary disagree about what to do with a seventh fractional digit: this class TRUNCATES it,
 * because that is what the {@code .SSSSSS} fraction field does, while the driver ROUNDS it to the
 * nearest microsecond before binding. A value of {@code 19:27:53.123456500} therefore renders as
 * {@code .123456} and persists as {@code .123457}, and the carry case is worse still, since
 * {@code 19:27:53.999999500} renders as {@code 53.999999} and persists as {@code 54.000000} -- a
 * different second. Truncating once, before either the rendering or the bind, leaves the driver
 * nothing to round: the same four cases were re-measured after truncation and the rendered string
 * and the stored column agreed exactly. A caller that renders one value into a report and binds
 * another into a column has produced two records of one event that a parity comparison will
 * correctly report as different.</p>
 *
 * <h2>Decision record</h2>
 *
 * <p>Refactoring Rationale: the paragraph this class replaces, {@code Z-GET-DB2-FORMAT-TIMESTAMP},
 * exists twice in the reference baseline and is byte for byte identical in both copies, at
 * {@code app/cbl/CBTRN02C.cbl} lines 692 to 705 and at {@code app/cbl/CBACT04C.cbl} lines 613 to
 * 626, being the same fourteen lines at a constant offset of seventy-nine. Two programs each holding
 * a private copy of one wire format is the duplication this class removes, and it is measurable
 * rather than notional: a change to either copy silently desynchronises two programs that write the
 * same field of the same record. The house precedent points the same way, since
 * {@code tests/README.md} line 268 compiles the reference programs against one copybook include
 * path, {@code -I app/cpy}, and lines 540 to 542 state the doctrine plainly, that a layout is never
 * duplicated and is kept single-sourced. This library is the Java analogue of that one include path,
 * and this class is the reason no service declares a timestamp format of its own.</p>
 *
 * <p>Alternatives Considered: emitting the reference baseline's native Db2 punctuation,
 * {@code YYYY-MM-DD-HH.MM.SS.cc0000}, was evaluated and not adopted. Three findings decided it.
 * First, the target column type renders natively in the space and colon form, so carrying the Db2
 * punctuation would require a translation at every persistence boundary rather than at none.
 * Second, the baseline is not self-consistent about the punctuation of this very field: the posting
 * path passes a seed value straight through in space and colon form at
 * {@code app/cbl/CBTRN02C.cbl} line 436, while the interest path stamps the same two fields in
 * hyphen and dot form from one perform at {@code app/cbl/CBACT04C.cbl} lines 496 to 498, so there is
 * no single baseline form to preserve and the target has to settle on one. Third, and decisively,
 * the only production consumer of this field reads its first ten bytes and nothing more, the sort
 * symbol {@code TRAN-PROC-DT,305,10,CH} at {@code app/jcl/TRANREPT.jcl} line 42, and those ten bytes
 * are byte identical under either punctuation. The baseline emits the Db2 form; this class emits the
 * ISO form; both are twenty-six characters at the same field position; the divergence is
 * deliberate and is documented here.</p>
 *
 * <p>Trade-offs: that choice is paid for in cosmetic divergence from the reference bytes beyond the
 * tenth character, and it is bought with the removal of a translation step at every boundary. The
 * leading ten characters, which are the only part any consumer reads, are unaffected either way,
 * so the compromise is settled in rendering and not in consumer behaviour. A second compromise is
 * accepted alongside it and is recorded on {@code TIMESTAMP_FORMAT} below, concerning fractional
 * resolution.</p>
 *
 * <p>Assumptions: the value carries no zone information, and that is why the parameter and return
 * type is {@link LocalDateTime}. Of the eight components of the baseline clock structure only seven
 * are ever moved into the formatted result, at {@code app/cbl/CBTRN02C.cbl} lines 693 to 700 and at
 * {@code app/cbl/CBACT04C.cbl} lines 614 to 621, namely the year, month, day, hour, minute, second
 * and hundredths. The eighth, {@code COB-REST PIC X(05)}, carries the offset from Greenwich that the
 * clock intrinsic returns, and a search of both programs finds it declared and never moved
 * anywhere.</p>
 *
 * <p>Alternatives Considered: an offset-bearing or zone-bearing parameter type, either
 * {@code java.time.OffsetDateTime} or {@code java.time.ZonedDateTime}, was evaluated against that
 * finding and rejected. Either would oblige this class to invent an offset that the source never
 * recorded, and every downstream comparison and every persisted value would then depend on the
 * invention rather than on the data. The zone question is not suppressed by that rejection, it is
 * relocated: {@link #formatNow(Clock)} takes a {@link Clock}, which carries its own zone, so the
 * decision is made visibly at the call site instead of silently here. For the same reason there is
 * no overload accepting a {@code java.time.Instant}, since converting an instant to a zone-less
 * local value requires choosing a zone and this class is not the right place to choose one.</p>
 *
 * <p>Assumptions: two time sources exist in the reference baseline and they stay separate here. An
 * injected business date arrives from outside the program, {@code app/jcl/INTCALC.jcl} line 22
 * running {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} into {@code PARM-DATE PIC X(10)} at
 * {@code app/cbl/CBACT04C.cbl} line 178 and consumed at line 476, while the processing stamp of that
 * same run is read from the wall clock at line 614. Injecting the business date is what lets a rerun
 * reproduce its output, so the separation is preserved rather than collapsed. Note that the injected
 * value is compact and unpunctuated, a year, month, day and hour with no separators at all: it is
 * not this class's punctuated twenty-six character form, and this class neither produces nor parses
 * it.</p>
 *
 * <p>Alternatives Considered: an entry point that read the wall clock on its own, whether a no
 * argument {@code format()} or a {@code now()}, was considered for caller convenience and rejected.
 * Such a method cannot be pinned by a test, and it would silently invite the per-write clock read
 * that the obligation above forbids. Every entry point here therefore takes its time as an argument,
 * either a {@link LocalDateTime} or a {@link Clock} the caller supplies.</p>
 *
 * <p>Alternatives Considered: declaring a dedicated exception type for a malformed value, or
 * reaching into the shared error model this library carries for the REST boundary, was evaluated and
 * rejected. That error model is a boundary shape applied by a controller advice at the web edge; a
 * leaf formatting utility that reached sideways into it would couple text formatting to the web
 * layer and would be unusable from a batch job. This class therefore signals failure with the
 * standard {@link NullPointerException}, {@link IllegalArgumentException} and
 * {@link DateTimeParseException} only, leaving each caller to translate them at whatever boundary it
 * owns. Failure is signalled by an exception rather than by an empty {@code Optional} because a
 * value that does not meet the contract is a defect in the caller's data and not an ordinary
 * absence.</p>
 *
 * <p>Alternatives Considered: Lombok was evaluated for suppressing the boilerplate of a utility
 * class, in particular its utility class annotation, which generates the private constructor. It is
 * not used, here or anywhere in this library. A generated constructor cannot carry the docstring
 * that the project Explainability rule requires at its line 15, which applies with no qualifier as
 * to visibility, so the annotation would trade eight lines of hand-written code for an
 * undocumentable member.</p>
 *
 * <p>Alternatives Considered: the legacy date and time API, {@code java.util.Date} with
 * {@code java.text.SimpleDateFormat}, was evaluated and rejected outright. Its formatter is mutable
 * and is not safe for concurrent use, so the single shared constant that this class relies on could
 * not be expressed with it without introducing a latent concurrency defect that appears only under
 * load. Assumptions: the {@code java.time} formatters used instead are immutable and are documented
 * as safe for concurrent use, which is what makes one shared instance correct. The whole import list
 * of this class is therefore drawn from the platform library, and no dependency is added to the
 * module to support it.</p>
 *
 * <p>This class holds no state, is not instantiable and is not a managed component. It is a plain
 * utility with no framework coupling, which is what lets a batch job, a message consumer and a REST
 * controller share one rendering without any of them dragging in the others' dependencies.</p>
 */
public final class TimestampFormatter {

    /**
     * The exact character length of the timestamp contract, twenty-six.
     *
     * <p>Assumptions: six independent artefacts in the reference baseline agree on this width, which
     * is why it is treated as a contract rather than as a formatting preference, and why it is
     * declared here once instead of appearing as a literal at each place a length is checked.
     * {@code app/cpy/CVTRA05Y.cpy} line 17 declares {@code TRAN-PROC-TS PIC X(26)}, and the fourteen
     * fields of that record sum to exactly 350 bytes, which places the field at zero-based offset
     * 304. {@code app/cbl/CBTRN02C.cbl} line 159 declares {@code 01 DB2-FORMAT-TS PIC X(26)} and
     * line 160 redefines it, the component split at lines 161 to 174 summing
     * {@code 4+1+2+1+2+1+2+1+2+1+2+1+2+4} to 26, which proves the width a second time by arithmetic
     * rather than by assertion. {@code app/cbl/CBACT04C.cbl} lines 150 to 165 carry the same
     * declaration and the same redefinition, so the width is shared and is not one program's local
     * choice. {@code app/cpy/CVEXPORT.cpy} line 11 declares {@code EXPORT-TIMESTAMP PIC X(26)} and
     * redefines it at line 12 into a ten-byte date at line 13, a one-byte separator at line 14 and a
     * fifteen-byte time at line 15, which is {@code 10 + 1 + 15}. That the separator is modelled
     * there as its own named one-byte field is itself evidence that punctuation is a rendering choice
     * while this geometry is the contract. {@code tests/fixtures/posting/happy_path/dailytran.txt}
     * supplies an observable vector, its first record carrying
     * {@code 2022-06-10 19:27:53.000000} across columns 279 to 304. Finally
     * {@code app/jcl/TRANREPT.jcl} line 42 declares the sort symbol {@code TRAN-PROC-DT,305,10,CH},
     * whose one-based position 305 is that same zero-based offset 304, corroborating the placement
     * from an artefact that shares no code with the copybooks.</p>
     */
    public static final int TIMESTAMP_LENGTH = 26;

    /**
     * The earliest year the twenty-six character contract admits, one thousand.
     *
     * <p>Refactoring Rationale: this bound and its upper counterpart are declared because the width
     * above is only a contract while the year is four digits, and {@link LocalDateTime} permits years
     * that are not. The measured behaviour on JDK 21.0.11 is that the year field of this pattern pads
     * to four digits and then, past four, both widens and gains a sign: year 999 renders
     * {@code 0999} and holds the width at twenty-six, year 10000 renders {@code +10000} and produces
     * twenty-eight characters. A twenty-eight character value assigned to a {@code PIC X(26)} field
     * is truncated by the receiving layout rather than rejected, which turns a rejected record into a
     * silently corrupted one, so the range is enforced here instead of being described in prose.</p>
     *
     * <p>Assumptions: the bound is one thousand rather than the year zero that four-digit padding
     * would also tolerate. The class documentation declared the {@code 1000} to {@code 9999} range
     * before these constants existed, so this is the code being brought into line with its own stated
     * contract, not a new restriction. It is also the range in which the rendering is unambiguous
     * without relying on zero padding to disambiguate it, and every timestamp this system can
     * legitimately carry -- a card transaction, an interest accrual, a statement run -- falls inside
     * it by a millennium.</p>
     */
    public static final int MIN_SUPPORTED_YEAR = 1000;

    /**
     * The latest year the twenty-six character contract admits, nine thousand nine hundred and
     * ninety nine.
     *
     * <p>Assumptions: this is the largest year the pattern can render in four characters, so it is
     * the point at which the width contract fails rather than a policy ceiling. See
     * {@code MIN_SUPPORTED_YEAR} for the measurement that fixes it and for why the range is checked
     * in code.</p>
     */
    public static final int MAX_SUPPORTED_YEAR = 9999;

    /**
     * The one formatter that renders and reads the contract described on this class.
     *
     * <p>Trade-offs: this is a single shared constant rather than an instance built per call, because
     * {@link DateTimeFormatter} is immutable and is documented as safe for concurrent use, so one
     * instance can serve every thread of every service that links this library. The compromise
     * accepted is that the pattern is decided once at class initialisation and cannot be varied by a
     * caller, which is the intent: a caller-varied pattern is precisely how the twenty-six character
     * width would drift.</p>
     *
     * <p>Assumptions: {@link Locale#ROOT} is passed explicitly rather than left to the default
     * locale. Every field in this pattern is numeric, and a locale-sensitive default can select a
     * different numbering system for numeric fields, which would render digits this contract does
     * not admit and would break the byte comparison the width exists to support. Pinning the locale
     * makes the output identical on every host.</p>
     *
     * <p>Assumptions: the mask comment at {@code app/cbl/CBTRN02C.cbl} line 149,
     * {@code EEEE-MM-DD-UU.MM.SS.HH0000}, is an accurate description of what the reference baseline
     * writes. It records native Db2 punctuation, and the paragraph at lines 692 to 705 produces
     * exactly that, moving a hyphen into all three separator positions at line 702 and a period into
     * all three remaining positions at line 703. It is a COBOL commentary mask and it must never be
     * transliterated into the pattern string below. Two of its letters mean something else entirely
     * in a Java pattern: {@code EEEE} is the day-of-week name, so the rendered value would open with
     * a weekday where the year belongs, and {@code UU} is not a pattern letter at all, so
     * {@link DateTimeFormatter#ofPattern(String, Locale)} would raise
     * {@link IllegalArgumentException} while this class was still initialising and every caller would
     * fail before reaching any timestamp. This paragraph exists so that the mask is read as the
     * documentation of the baseline that it is, and not restored into the pattern in an attempt at
     * fidelity that would not compile a single value.</p>
     *
     * <p>Trade-offs: six fractional digits are emitted although the reference baseline can populate
     * only two of them. The only wall clock reads in the baseline are
     * {@code app/cbl/CBTRN02C.cbl} line 693 and {@code app/cbl/CBACT04C.cbl} line 614, both moving
     * the clock intrinsic into a twenty-one character structure whose fractional component is two
     * digits of hundredths, and each paragraph then moves the literal {@code '0000'} into the four
     * remaining fractional positions, at line 701 and at line 622 respectively. Assumptions: the
     * last four fractional digits are therefore structurally zero in the baseline, and what the
     * contract carries is the {@code PIC X(26)} width and not the fractional resolution. The
     * baseline populates two fractional digits; this class emits six at whatever resolution its
     * caller supplies; the divergence is documented. The compromise accepted is that a rendered
     * value implies a resolution its source may not have had, and the width fidelity that
     * {@code PIC X(26)} and {@code TIMESTAMP(6)} both require is what is bought with it.</p>
     *
     * <p>Refactoring Rationale: the proleptic year letter {@code uuuu} paired with
     * {@link ResolverStyle#STRICT} is what is written below, and an earlier shape of this constant
     * used the year-of-era letter {@code yyyy} with the default resolver on the reasoning that the
     * contract is stated as a {@code yyyy} pattern and the constant should be its literal
     * realisation. That reasoning weighed a pattern spelling against a correctness property and got
     * the trade the wrong way round, because the default resolver is {@link ResolverStyle#SMART} and
     * SMART does not reject an impossible calendar date, it silently moves it. The behaviour was
     * measured on JDK 21.0.11 rather than assumed, and under the old configuration four separate
     * impossible values parsed successfully into a different value than the one supplied:
     * {@code 2023-02-30} became the twenty-eighth of February, {@code 2023-02-29} in a non-leap year
     * became the same, {@code 2023-04-31} became the thirtieth of April, and {@code 24:00:00} became
     * midnight of the FOLLOWING day. Each of those is a date arithmetic error in the caller's data
     * that {@link #parse(String)} promises to report and instead absorbed, and the last is the most
     * dangerous of the four because it moves the ten-character date prefix that every production
     * consumer of this field reads. Under {@code uuuu} with the strict resolver all four raise
     * {@link DateTimeParseException}, while the legitimate leap day {@code 2024-02-29} still parses,
     * which is the whole of the intended change.</p>
     *
     * <p>Assumptions: the two settings are coupled, and the coupling is why they are changed
     * together in one edit rather than one at a time. {@code yyyy} is the year of era, so requesting
     * the strict resolver while retaining {@code yyyy} leaves the era unresolved and every parse then
     * fails at resolution even on a well-formed value; that combination was measured too, and
     * {@code 2023-06-10 19:27:53.000000} -- a value this class itself renders -- failed to parse
     * under it. Only two combinations are sound, the strict resolver with {@code uuuu}, which is what
     * is written below, or the default resolver with {@code yyyy}, which is what was written before
     * and which does not reject an impossible date. A maintainer who loosens one of the two without
     * the other will break parsing outright, which is the failure this paragraph exists to
     * prevent.</p>
     *
     * <p>Trade-offs: {@code uuuu} and {@code yyyy} render identically for every year the contract
     * admits, so no rendered value changes as a result of this decision. They diverge only outside
     * that range, where {@code uuuu} renders a negative year with a leading minus and {@code yyyy}
     * renders it as a positive year of the preceding era -- and the year range enforced by
     * {@code MIN_SUPPORTED_YEAR} and {@code MAX_SUPPORTED_YEAR} places both cases outside what any
     * entry point here accepts. The compromise is therefore paid entirely in the pattern string no
     * longer being a character-for-character echo of the prose contract, and it buys a parser that
     * rejects a date the calendar does not have.</p>
     */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Prevents instantiation of this static utility holder.
     *
     * <p>Alternatives Considered: an instantiable class, or one exposing a shared instance, was
     * evaluated and rejected. Neither would carry any state, so an instance would communicate a
     * lifecycle that does not exist and would invite injection of something that has nothing to
     * inject. Declaring the constructor private states that intent in the one place the language
     * enforces it, and the class is final so that no subclass can reopen the decision.</p>
     */
    private TimestampFormatter() {
        // WHY : Alternatives Considered: throwing from this body was evaluated and judged noise,
        //       since the private modifier already makes the only call site that could reach it
        //       impossible to write, so the exception would document a state the compiler already
        //       forbids.
    }

    /**
     * Renders a local timestamp in the twenty-six character contract form.
     *
     * <p>This is the primary entry point, and the one a unit of work should call once before writing
     * any of its records, per the obligation documented on this class.</p>
     *
     * <p>Fractional resolution is normalised here, so the returned string and the value a caller
     * should persist are derived from one truncation rather than two. See
     * {@link #normalize(LocalDateTime)} for the measured reason that matters.</p>
     *
     * @param timestamp the local date and time to render; must not be {@code null}, and is zone-less
     *     because the reference baseline never recorded a zone for this field
     * @return the rendered value, exactly {@code TIMESTAMP_LENGTH} characters, of the form
     *     {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'}
     * @throws NullPointerException if {@code timestamp} is {@code null}, since a timestamp column
     *     under this contract has no rendering for an absent value and a caller holding one has a
     *     defect to address rather than a blank to write
     * @throws IllegalArgumentException if the year of {@code timestamp} lies outside
     *     {@code MIN_SUPPORTED_YEAR} through {@code MAX_SUPPORTED_YEAR}, because such a value cannot
     *     be rendered in the contract width; and, as a defence in depth, if the rendered value is
     *     not exactly {@code TIMESTAMP_LENGTH} characters
     */
    public static String format(LocalDateTime timestamp) {
        // WHY : Alternatives Considered: delegating the null check to java.time was evaluated and
        //       rejected. It would also fail on null, but from inside the formatter and without
        //       naming the parameter, which sends a maintainer to read this class rather than their
        //       own call site. Assumptions: an explicit null check is the platform's documented
        //       idiom for a mandatory argument, so no local convention is invented for it.
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        // WHAT: establish the year is renderable in four characters before rendering anything.
        // WHY : Trade-offs: the alternative is to render first and then measure the result, which
        //       this method also does on the line below, and on its own that would be enough to stop
        //       a wrong-width value escaping. Checking the year FIRST is what makes the failure
        //       diagnosable: the message names the year and the range, whereas a length complaint
        //       about a rendered string leaves a caller to work out which of its components was too
        //       wide. Both checks are kept because they fail for different reasons -- the first is
        //       the rule, the second is the proof that the rule was sufficient.
        LocalDateTime supported = requireSupportedYear(timestamp);

        // WHAT: truncate to microseconds once, here, and render the truncated value.
        // WHY : Assumptions: rendering the raw value would produce the same six digits, because the
        //       .SSSSSS fraction field truncates rather than rounds, so this line does not change any
        //       returned string. What it changes is that ONE truncation now defines the contract
        //       value, and a caller persisting normalize(t) is guaranteed to be persisting exactly
        //       what format(t) rendered. Deriving the two from separate truncations is how they drift
        //       apart at the seventh digit, which is the defect recorded on normalize below.
        String rendered = TIMESTAMP_FORMAT.format(normalize(supported));

        return requireRenderedWidth(rendered, supported);
    }

    /**
     * Renders the current local timestamp of a caller-supplied clock, in the contract form.
     *
     * <p>Assumptions: the clock is a parameter and never a default. This is the deliberate
     * counterpart to the injected business date recorded on this class, and it is what allows a test
     * or a rerun to pin the value, since a {@link Clock} pinned to a single instant yields one
     * predictable rendering whereas a method reading the ambient clock could not be asserted against
     * at all. It is also where the zone decision this class declines to make is made instead, since
     * the supplied clock carries its own zone.</p>
     *
     * @param clock the clock to read the current date and time from, whose zone determines the local
     *     value produced; must not be {@code null}
     * @return the rendered value, exactly {@code TIMESTAMP_LENGTH} characters, of the form
     *     {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'}
     * @throws NullPointerException if {@code clock} is {@code null}, since there is deliberately no
     *     ambient clock to fall back to
     * @throws IllegalArgumentException if the clock yields a year outside
     *     {@code MIN_SUPPORTED_YEAR} through {@code MAX_SUPPORTED_YEAR}, which a fixed clock in a
     *     test can produce even though a system clock cannot
     */
    public static String formatNow(Clock clock) {
        // WHY : Assumptions: naming this parameter in the message matters more here than on
        //       format above, because a null clock usually means a caller expected an ambient
        //       default that this class deliberately does not provide, and the parameter name is
        //       what points them at that absence rather than at a formatting fault.
        Objects.requireNonNull(clock, "clock must not be null");

        // WHY : Assumptions: one read per call is what makes the once-per-unit-of-work obligation
        //       documented on this class satisfiable at all. A caller stores what this returns and
        //       spends it on every record of the unit of work, mirroring the single perform at
        //       app/cbl/CBTRN02C.cbl line 437 that feeds the three writes at lines 440 to 442.
        return format(LocalDateTime.now(clock));
    }

    /**
     * Reduces a local timestamp to the microsecond resolution the contract carries.
     *
     * <p>This is the value to bind to a {@code TIMESTAMP(6)} parameter, and the one
     * {@link #format(LocalDateTime)} renders, so a caller that persists what this returns cannot end
     * up with a stored column that disagrees with the string it wrote elsewhere.</p>
     *
     * <p>Refactoring Rationale: this method exists because the two sides of the persistence boundary
     * dispose of a seventh fractional digit differently, and the difference was measured on JDK
     * 21.0.11 with PostgreSQL JDBC 42.7.13 against a real {@code TIMESTAMP(6)} column rather than
     * taken on trust. The {@code .SSSSSS} fraction field of this class TRUNCATES, so
     * {@code 19:27:53.123456500} renders {@code .123456}. The driver ROUNDS to the nearest
     * microsecond before binding, so the same value was stored as {@code .123457}. The carry case
     * crosses a second boundary: {@code 19:27:53.999999500} rendered {@code 53.999999} and was stored
     * as {@code 54.000000}, a stored value one second later than the rendered one, and one whose
     * ten-character date prefix would differ too if the clock read had landed at the end of a day.
     * Both discrepancies disappeared when the same values were truncated before binding, which is the
     * measurement that fixes the design: truncate once, deliberately, and the driver is left with
     * nothing to round.</p>
     *
     * <p>Alternatives Considered: rounding here instead, to agree with the driver rather than
     * disagree with it, was evaluated and rejected. Rounding can carry into the next second, minute,
     * hour and day, so a timestamp could leave this class denoting a later day than the clock read
     * that produced it, and the ten-character date prefix that every production consumer of this
     * field reads would move with it. Truncation cannot move any component other than the fraction it
     * discards. Assumptions: the discarded digits are below the resolution the contract carries at
     * all, and the reference baseline populates only two fractional digits and zero-fills the
     * remaining four, so nothing that the migrated value is compared against records anything finer
     * than a microsecond in the first place.</p>
     *
     * <p>Alternatives Considered: leaving each caller to truncate at its own persistence site was
     * evaluated and rejected, and it is what an earlier shape of this class implied by documenting
     * neither the truncation nor the driver's rounding. A rule applied at every call site is a rule
     * that is eventually missed at one, and the missed site produces a record that is wrong by one
     * microsecond or one second while every other record of the same unit of work is right -- a
     * discrepancy small enough to survive review and large enough to fail a byte-deterministic parity
     * comparison. Naming the operation once, here, is what makes it reviewable.</p>
     *
     * @param timestamp the local date and time to reduce; must not be {@code null}
     * @return the same instant of the same day with any fraction below one microsecond discarded, so
     *     that its nanosecond component is always a whole number of microseconds
     * @throws NullPointerException if {@code timestamp} is {@code null}
     */
    public static LocalDateTime normalize(LocalDateTime timestamp) {
        // WHAT: reject a null before touching the value, matching the idiom used on every entry
        //       point here.
        // WHY : this method is called from format above, so a null reaching it would otherwise be
        //       reported from two call depths below the caller's own line.
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        // WHAT: truncate toward the start of the second, at microsecond granularity.
        // WHY : Assumptions: ChronoUnit.MICROS is the platform's own name for the unit this contract
        //       carries, and truncatedTo is documented to discard the remainder rather than round it,
        //       which is exactly the semantic the paragraph above requires. Writing the equivalent
        //       arithmetic by hand -- dividing the nanosecond field by one thousand and multiplying
        //       back -- was considered and rejected: it computes the same result while obscuring the
        //       intent and inviting an off-by-one at the boundary this method exists to control.
        return timestamp.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Reads a caller-supplied clock and reduces the result to the contract resolution.
     *
     * <p>Assumptions: this pairs with {@link #formatNow(Clock)} the way
     * {@link #normalize(LocalDateTime)} pairs with {@link #format(LocalDateTime)}. A unit of work
     * that needs both a rendered value and a value to persist calls this once, keeps the result, and
     * hands the same value to {@link #format(LocalDateTime)} and to its database parameter, which is
     * what makes the two provably identical instead of merely similar. Calling
     * {@code formatNow(clock)} and then reading the clock a second time for the persisted value is
     * the defect this method removes, because a system clock advances between the two reads.</p>
     *
     * @param clock the clock to read the current date and time from, whose zone determines the local
     *     value produced; must not be {@code null}
     * @return the clock's current local date and time, truncated to whole microseconds
     * @throws NullPointerException if {@code clock} is {@code null}, since there is deliberately no
     *     ambient clock to fall back to
     */
    public static LocalDateTime normalizeNow(Clock clock) {
        // WHAT: validate the clock, then read it exactly once.
        // WHY : one read per call is the same obligation formatNow above satisfies, and it is the
        //       reason this method takes a clock rather than reading an ambient one: a test can pin
        //       it, and a caller cannot read two different instants for one unit of work by
        //       accident.
        Objects.requireNonNull(clock, "clock must not be null");

        return normalize(LocalDateTime.now(clock));
    }

    /**
     * Reads a rendered contract value back into a local timestamp.
     *
     * <p>Assumptions: the input is a complete value in the form this class renders, and not the
     * reference baseline's native Db2 punctuation, whose separators differ from the eleventh
     * character onward. A value in that other form is twenty-six characters and so passes the width
     * check, then fails the pattern and raises {@link DateTimeParseException}; that is the intended
     * outcome, because silently accepting both forms would leave the punctuation of persisted values
     * undetermined, which is exactly the ambiguity this class exists to end.</p>
     *
     * <p>Assumptions: an impossible calendar date is a failure and never a correction. Because the
     * formatter resolves strictly, {@code 2023-02-30}, {@code 2023-02-29} in a non-leap year,
     * {@code 2023-04-31} and an hour of {@code 24} are each rejected rather than moved to a
     * neighbouring valid value, while a genuine leap day such as {@code 2024-02-29} is accepted. The
     * rationale, and the measurement behind it, are recorded on the formatter constant.</p>
     *
     * @param timestamp the rendered value to read, which must be exactly
     *     {@code TIMESTAMP_LENGTH} characters in the form {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'}
     * @return the local date and time the value denotes, carrying no zone, with a nanosecond
     *     component that is always a whole number of microseconds because six fractional digits are
     *     all the form carries
     * @throws NullPointerException if {@code timestamp} is {@code null}
     * @throws IllegalArgumentException if {@code timestamp} is not exactly
     *     {@code TIMESTAMP_LENGTH} characters long
     * @throws DateTimeParseException if {@code timestamp} is of the required length but is not a
     *     value of the contract form, which covers a malformed component, the wrong punctuation, a
     *     date the calendar does not have, and a year outside {@code MIN_SUPPORTED_YEAR} through
     *     {@code MAX_SUPPORTED_YEAR}
     */
    public static LocalDateTime parse(String timestamp) {
        // WHY : Trade-offs: the two failures are reported through different exception types on
        //       purpose. A wrong length is a structural error in the caller's record layout, most
        //       often a field read at the wrong offset, and IllegalArgumentException with the two
        //       lengths in its message points straight at that. A right-length value that will not
        //       parse is a content error, and DateTimeParseException carries the parse position that
        //       diagnoses it. Collapsing both into one type would discard that distinction, which is
        //       the more useful half of the diagnosis.
        String checked = requireContractLength(timestamp);

        // WHAT: range-check the year of the parsed value, and report a breach as a parse failure.
        // WHY : Assumptions: the width check above cannot catch this one. A year of 0999 is four
        //       characters like any other, so "0999-06-10 19:27:53.000000" is exactly twenty-six
        //       characters and parses cleanly, yet it is outside the range this contract declares and
        //       outside what format above will render -- so accepting it here would make the class
        //       able to read a value it refuses to write. Trade-offs: the breach is reported as
        //       DateTimeParseException rather than the IllegalArgumentException that format uses for
        //       the same rule, because on this side of the boundary the offending year arrived inside
        //       the caller's DATA rather than as its argument, and every other content defect in that
        //       data is already reported through that type.
        return requireSupportedYearParsed(
                LocalDateTime.parse(checked, TIMESTAMP_FORMAT), checked);
    }

    /**
     * Extracts the ten-character ISO date prefix of a rendered contract value.
     *
     * <p>Assumptions: the leading ten characters are the part of this field that production code
     * actually reads, and they are identical under either candidate punctuation, so this method is
     * stable across the divergence recorded on this class. The reference baseline reads them in two
     * unrelated places. {@code app/cbl/CBTRN02C.cbl} line 414 evaluates
     * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}, taking the first ten bytes of a
     * twenty-six character timestamp in order to compare a date; the spelling of that expiration
     * field is the baseline's own and is reproduced here exactly as it stands.
     * {@code app/jcl/TRANREPT.jcl} line 42 declares the sort symbol {@code TRAN-PROC-DT,305,10,CH},
     * ten bytes in character mode.</p>
     *
     * <p>Assumptions: over this prefix, comparing text agrees with comparing dates. The form is of
     * constant width, is zero padded and places its most significant component first, so a
     * lexicographic comparison of two prefixes returns the same answer as a chronological comparison
     * of the two dates. That is not a convenience this method offers, it is a property production
     * code already depends on: {@code app/jcl/TRANREPT.jcl} lines 47 and 48 apply
     * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND, TRAN-PROC-DT,LE,PARM-END-DATE)}
     * against the ISO-hyphenated character constants at lines 43 and 44, so a production date range
     * filter is executed as a text comparison and is correct only because of the equivalence. Both
     * endpoints of that filter are inclusive, matching the inclusive boundary the baseline applies at
     * {@code app/cbl/CBTRN02C.cbl} lines 414 to 420, where an equal date takes the continue branch
     * and posts rather than being rejected.</p>
     *
     * @param timestamp the rendered value to slice, which must be exactly
     *     {@code TIMESTAMP_LENGTH} characters in the form {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'}
     * @return the leading ten characters, the ISO date in the form {@code yyyy-MM-dd}
     * @throws NullPointerException if {@code timestamp} is {@code null}
     * @throws IllegalArgumentException if {@code timestamp} is not exactly
     *     {@code TIMESTAMP_LENGTH} characters long
     */
    public static String datePrefix(String timestamp) {
        // WHY : Alternatives Considered: accepting any string of ten characters or more was
        //       evaluated and rejected. This method exists to slice a value of this contract, and a
        //       shorter or longer argument almost always means a field was read at the wrong offset
        //       within the 350-byte record; validating the whole width catches that at the slice
        //       rather than letting a plausible-looking date escape into a comparison.
        String checked = requireContractLength(timestamp);

        // WHY : Assumptions: it is stated twice in the reference baseline, as the ten-byte
        //       EXPORT-DATE at app/cpy/CVEXPORT.cpy line 13 and as the trailing 10 of the sort
        //       symbol TRAN-PROC-DT,305,10,CH at app/jcl/TRANREPT.jcl line 42. It is not derivable
        //       from TIMESTAMP_LENGTH, so it is written here beside its citations rather than
        //       promoted to a second public constant that no caller has asked for.
        return checked.substring(0, 10);
    }

    /**
     * Reads the ten-character ISO date prefix of a rendered contract value as a date.
     *
     * <p>Alternatives Considered: parsing the whole twenty-six character value and taking the date
     * from it was evaluated and rejected. It would reject a value whose time component is malformed
     * even though the caller asked only for the date, which is not what the baseline consumers do:
     * both the reference-modified read at {@code app/cbl/CBTRN02C.cbl} line 414 and the character
     * mode sort symbol at {@code app/jcl/TRANREPT.jcl} line 42 take ten bytes and are indifferent to
     * everything after them. This method is the typed form of {@link #datePrefix(String)} and keeps
     * that indifference; a caller that wants the whole value validated calls
     * {@link #parse(String)} instead, and the two methods are deliberately not interchangeable in
     * that respect.</p>
     *
     * @param timestamp the rendered value to read the date from, which must be exactly
     *     {@code TIMESTAMP_LENGTH} characters in the form {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'}
     * @return the date the leading ten characters denote
     * @throws NullPointerException if {@code timestamp} is {@code null}
     * @throws IllegalArgumentException if {@code timestamp} is not exactly
     *     {@code TIMESTAMP_LENGTH} characters long
     * @throws DateTimeParseException if the leading ten characters are not a valid ISO date, such as
     *     a day that the given month does not have, or denote a year outside
     *     {@code MIN_SUPPORTED_YEAR} through {@code MAX_SUPPORTED_YEAR}
     */
    public static LocalDate toLocalDate(String timestamp) {
        // WHY : Assumptions: the prefix of this contract is precisely an ISO local date, so
        //       LocalDate.parse reads it without a second pattern being declared. That parser also
        //       resolves strictly over a proleptic year -- the same discipline the formatter constant
        //       now applies to the full value -- so an impossible calendar date such as a
        //       thirtieth of February is rejected here rather than being quietly shifted, which the
        //       lenient alternative would have done.
        LocalDate date = LocalDate.parse(datePrefix(timestamp));

        // WHAT: apply the contract's year range to the typed result as well.
        // WHY : Assumptions: one contract carries one year range, so the range is checked wherever a
        //       year crosses this class's boundary rather than only where it is rendered. Without
        //       this line the typed reader would accept a year that parse above rejects and that
        //       format refuses to write, and the inconsistency would surface as one code path
        //       tolerating a record that another rejects -- the hardest kind of defect to attribute.
        //       Note that datePrefix deliberately keeps no such check: it returns a slice and is
        //       documented as indifferent to content, so the check belongs on the method that
        //       interprets the slice.
        requireSupportedYearParsed(date.atStartOfDay(), timestamp);

        return date;
    }

    /**
     * Validates that a candidate value is present and is exactly the contract width.
     *
     * <p>Refactoring Rationale: the same two guards are needed by every method that accepts a
     * rendered value, and an earlier shape of this class repeated them at each of the three entry
     * points. Three copies of one guard is how the width check drifts, by being tightened in one
     * place and forgotten in the other two, which is the same failure this class was written to
     * remove at the scale of a whole paragraph. Returning the validated reference, in the manner of
     * {@link Objects#requireNonNull(Object, String)}, lets each caller validate and use in a single
     * expression instead of pairing a void call with a separate use.</p>
     *
     * @param timestamp the candidate value to validate, which may be {@code null} or of any length
     *     since establishing that it is neither is this method's purpose
     * @return the same reference that was passed in, once it is known to satisfy both guards
     * @throws NullPointerException if {@code timestamp} is {@code null}
     * @throws IllegalArgumentException if {@code timestamp} is not exactly
     *     {@code TIMESTAMP_LENGTH} characters long
     */
    private static String requireContractLength(String timestamp) {
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        if (timestamp.length() != TIMESTAMP_LENGTH) {
            // WHY : Trade-offs: the message is longer than a bare statement of the rule, and the
            //       extra text is what turns the failure into a diagnosis. The received length is
            //       usually the whole clue, since a value one or two characters short normally means
            //       a caller sliced a neighbouring field boundary rather than misunderstanding the
            //       contract itself.
            throw new IllegalArgumentException(
                    "timestamp must be exactly " + TIMESTAMP_LENGTH
                            + " characters, but was " + timestamp.length());
        }

        return timestamp;
    }

    /**
     * Validates that a value to be rendered has a year the contract width can carry.
     *
     * <p>Refactoring Rationale: this guard is a separate method for the same reason
     * {@link #requireContractLength(String)} is, namely that more than one entry point needs it and a
     * duplicated bound is a bound that gets tightened in one copy only. Returning the validated
     * reference lets a caller validate and use in one expression.</p>
     *
     * @param timestamp the value whose year is to be validated; must not be {@code null}, which its
     *     callers have already established
     * @return the same reference that was passed in, once its year is known to be within range
     * @throws IllegalArgumentException if the year lies outside {@code MIN_SUPPORTED_YEAR} through
     *     {@code MAX_SUPPORTED_YEAR}
     */
    private static LocalDateTime requireSupportedYear(LocalDateTime timestamp) {
        int year = timestamp.getYear();

        if (year < MIN_SUPPORTED_YEAR || year > MAX_SUPPORTED_YEAR) {
            // WHAT: state the offending year, the range and the reason the range exists.
            // WHY : Trade-offs: a bare statement of the rule would be shorter, and it would leave the
            //       reader to discover why a date library that accepts the year is being refused by
            //       this class. Naming the width is what makes the refusal make sense, because the
            //       constraint comes from the twenty-six character field the value is destined
            //       for and not from the type it is held in.
            throw new IllegalArgumentException(
                    "timestamp year must be between " + MIN_SUPPORTED_YEAR + " and "
                            + MAX_SUPPORTED_YEAR + " to render in " + TIMESTAMP_LENGTH
                            + " characters, but was " + year);
        }

        return timestamp;
    }

    /**
     * Validates that a value read back from a rendered string has a year the contract admits.
     *
     * <p>Assumptions: this is the read-side counterpart of {@link #requireSupportedYear} and applies
     * the identical range through a different exception type, because here the offending year arrived
     * inside data rather than as a caller's argument. The distinction is documented on
     * {@link #parse(String)}, which is where it is visible to a caller.</p>
     *
     * @param parsed the value that was read from {@code text}; must not be {@code null}, which its
     *     callers have already established
     * @param text the rendered value the year was read from, carried so that the failure can quote
     *     the input that produced it
     * @return the same reference that was passed in, once its year is known to be within range
     * @throws DateTimeParseException if the year lies outside {@code MIN_SUPPORTED_YEAR} through
     *     {@code MAX_SUPPORTED_YEAR}
     */
    private static LocalDateTime requireSupportedYearParsed(LocalDateTime parsed, String text) {
        int year = parsed.getYear();

        if (year < MIN_SUPPORTED_YEAR || year > MAX_SUPPORTED_YEAR) {
            // WHAT: raise the platform's parse exception with an error index of zero.
            // WHY : Assumptions: zero is where the year begins in this form, so the index points at
            //       the component that failed rather than at an arbitrary position. Alternatives
            //       Considered: re-throwing whatever the formatter produced was not possible here,
            //       because the formatter accepted the value -- the range is this contract's rule and
            //       not the pattern's -- so the exception has to be constructed. That keeps
            //       the type a caller already handles for every other content defect in the same
            //       string.
            throw new DateTimeParseException(
                    "timestamp year must be between " + MIN_SUPPORTED_YEAR + " and "
                            + MAX_SUPPORTED_YEAR + ", but was " + year,
                    text,
                    0);
        }

        return parsed;
    }

    /**
     * Validates that a value this class has just rendered is exactly the contract width.
     *
     * <p>Trade-offs: this check is expected never to fail, and it is written anyway. The year range
     * enforced before rendering is what actually holds the width, so this method proves that rule was
     * sufficient rather than adding a second rule; the cost is one comparison per rendered value, and
     * what it buys is that a future change to the pattern cannot widen the output without a test
     * failing. Alternatives Considered: a Java {@code assert}, which would express the same intent,
     * was rejected because assertions are disabled unless a runtime flag enables them, so the
     * guarantee would hold in a test run and evaporate in production -- the one place a truncated
     * record cannot be recovered.</p>
     *
     * @param rendered the freshly rendered value to measure
     * @param source the value that produced it, carried so that the failure can name the input rather
     *     than only the output
     * @return the same rendered value, once it is known to be exactly {@code TIMESTAMP_LENGTH}
     *     characters
     * @throws IllegalArgumentException if {@code rendered} is not exactly {@code TIMESTAMP_LENGTH}
     *     characters, which indicates the year guard above no longer covers every case the pattern
     *     can widen
     */
    private static String requireRenderedWidth(String rendered, LocalDateTime source) {
        if (rendered.length() != TIMESTAMP_LENGTH) {
            throw new IllegalArgumentException(
                    "rendering " + source + " produced " + rendered.length()
                            + " characters instead of " + TIMESTAMP_LENGTH + ": " + rendered);
        }

        return rendered;
    }
}
