package com.carddemo.common.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Records, for one input field, whether it is acceptable, holds an unacceptable value, or was never
 * supplied at all.
 *
 * <h2>The contract</h2>
 *
 * <p>One marker serves every validated input field in the migrated services, and it carries exactly
 * three states. The reference baseline declares that marker as a single character with three
 * condition names attached, and it declares it that way three times over -- once each for the year,
 * the month and the day of a date -- at {@code app/cpy/CSUTLDWY.cpy} lines 46 to 57. The three
 * states and the byte each one holds are not open to interpretation there: acceptable is the
 * low-value byte, unacceptable is the digit zero, and never-supplied is the letter B. This type is
 * those three states, named, with the predicates that read them.</p>
 *
 * <p>Assumptions: the marker is a general per-field contract rather than a date-specific one, and
 * the count settles it. The same three condition names, with the same suffixes, are declared 71
 * times across the baseline for 71 separate field identities -- among them an account status, an
 * account filter, a transaction-type filter and a description -- of which only three belong to the
 * date components. This type is therefore usable on its own, with no date validator anywhere in the
 * call path, and one baseline program relies on exactly that: at
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} line 76 it copies the marker declarations
 * while never copying the date algorithm, and it runs no date edit at all.</p>
 *
 * <h2>Never-supplied is a kind of error, not a third alternative to it</h2>
 *
 * <p>Trade-offs: the three states are modelled so that never-supplied is a REASON WITHIN error
 * rather than a peer of it, and a flat set of three equal alternatives was the other option. The
 * flat shape is easier to write and was rejected, because four separate properties of the baseline
 * say the containment is real:</p>
 *
 * <ul>
 *   <li>the presentation template asks ONE question, not two. At {@code app/cpy/CSSETATY.cpy} lines
 *       18 and 19 the test is a single disjunction over the unacceptable and never-supplied
 *       conditions, so nothing downstream of it distinguishes them when deciding whether a field is
 *       in error;</li>
 *   <li>the never-supplied path sets TWO markers. It sets this per-field marker and, separately, a
 *       caller-owned error switch declared at
 *       {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} lines 81 to 84, so a field that was
 *       never supplied is unconditionally in error and additionally records why;</li>
 *   <li>the never-supplied test is itself a two-way disjunction over two distinct byte patterns,
 *       both resolving to the one outcome, which is the tolerance {@link #fromCode(char)}
 *       reproduces;</li>
 *   <li>the template's second action is conditioned on never-supplied ALONE, at
 *       {@code app/cpy/CSSETATY.cpy} lines 23 to 25, which is why the state has to remain
 *       distinguishable at all rather than collapsing into a single error state.</li>
 * </ul>
 *
 * <p>The compromise accepted is that a caller cannot ask "is this field in error" by comparing
 * against a constant, since two of the three states answer yes. That is the point: the question the
 * baseline asks has one answer, so this type exposes it as one predicate, {@link #isError()}.
 * Reading it as three peers would let a caller write a comparison against never-supplied where the
 * correct question is whether the field is in error at all, and that caller would then silently
 * pass an unacceptable value as though it were acceptable.</p>
 *
 * <p>Trade-offs: {@link #isError()} is exposed as a derived predicate rather than leaving callers to
 * compare against the constants themselves. The cost is one more member on an otherwise minimal
 * type. It is accepted because the disjunction at {@code app/cpy/CSSETATY.cpy} lines 18 and 19 is
 * expanded 40 times in the baseline, so a caller-side comparison would be the one piece of logic
 * most likely to be written 40 different ways, and a two-term disjunction is exactly the shape in
 * which omitting a term produces no compiler complaint.</p>
 *
 * <h2>The screen marker is not a state value</h2>
 *
 * <p>Assumptions: two single characters belong to this contract and they live at different layers,
 * so this type keeps them apart. The letter B is a STATE VALUE, stored in the marker field itself,
 * declared at {@code app/cpy/CSUTLDWY.cpy} lines 46 to 57. The asterisk is a PRESENTATION ARTEFACT,
 * written into the screen field so that an empty box visibly shows something, and it appears at
 * {@code app/cpy/CSSETATY.cpy} line 24. The template makes the separation structural rather than
 * incidental: at lines 21 and 22 it moves the error colour into the field's ATTRIBUTE position,
 * while at lines 24 and 25 it moves the asterisk into the field's DATA position. Two destinations,
 * one condition. Merging the two characters would put a literal asterisk into the state domain,
 * after which a marker field could hold a value the baseline never stores in one, so the asterisk is
 * published here as {@link #BLANK_SCREEN_MARKER} and is never a value {@link #code()} returns.</p>
 *
 * <h2>One parameterised type replaces forty textual expansions</h2>
 *
 * <p>Refactoring Rationale: the baseline expands {@code app/cpy/CSSETATY.cpy} forty times across two
 * programs -- thirty-nine in {@code app/cbl/COACTUPC.cbl} at lines 3208 through 3432 and one in
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} at line 1358 -- one textual expansion per
 * validated screen field, because a COBOL copybook substitution macro has no runtime
 * parameterisation. A single Java type parameterised by field identity replaces all forty
 * expansions. Field identity is a value here, carried as the first component of
 * {@link FieldError}, which is what turns forty compile-time substitutions into one runtime
 * argument.</p>
 *
 * <p>Assumptions: the substitution is the whole of what varies between those forty sites. Each
 * expansion supplies three tokens and nothing else -- the marker to test, the screen field to alter
 * and the map that field belongs to -- so the forty sites differ in identity alone and share their
 * logic entirely. Two properties of that block record what maintaining forty copies by hand costs,
 * and both were read from the file rather than inferred. The expansions sit six lines apart from
 * line 3208 to line 3400 and then the spacing breaks, falling to five lines at 3405, at 3422, at
 * 3427 and at 3432. At lines 3426 through 3435 two adjacent descriptive comments are transposed
 * relative to the expansions they introduce, so the comment naming an account identifier stands
 * above the expansion for a cardholder marker and vice versa. The block also retains, at lines 3198
 * to 3205, a commented-out hand-written form of the same logic that the macro superseded, directly
 * beneath the program's own note at lines 3195 and 3196 that a substitution copy is being used to
 * set attributes for the remaining fields. The baseline carries all three of these; this type
 * carries one implementation reached by forty callers; the divergence is documented.</p>
 *
 * <h2>The re-entry gate is not carried forward</h2>
 *
 * <p>Refactoring Rationale: the baseline's highlight does not fire whenever a field is in error. At
 * {@code app/cpy/CSSETATY.cpy} line 20 the disjunction above is conjoined with a further condition,
 * a one-digit discriminator declared at {@code app/cpy/COCOM01Y.cpy} lines 29 to 31 with two
 * condition names, zero meaning a first pass through the program and one meaning a subsequent one.
 * The highlight therefore fires only on a subsequent pass. That discriminator lives in the session
 * structure the terminal hands back between screen turns, so in the baseline the marker state and
 * its presentation are coupled through a remembered turn count, and a field can be in error without
 * being shown as being in error.</p>
 *
 * <p>The migrated services are stateless and hold no such count, so the coupling is severed at its
 * source: a marker in either error state is reported in the response body unconditionally, and the
 * client renders whatever the body contains. The consequence is deliberate and is stated rather than
 * left to be discovered -- a validation failure on a first submission highlights fields where the
 * baseline would not have. The baseline gates on the discriminator; this type has no gate; the
 * divergence is documented. The constraint that follows binds this whole type: no member here
 * accepts, stores or exposes a pass count, a submission count or any other per-conversation state,
 * and none may acquire one, because {@link #isError()} answering differently on two identical
 * inputs is precisely the behaviour that was removed.</p>
 *
 * <h2>Shape decisions</h2>
 *
 * <p>Alternatives Considered: an annotation processor generating the accessors was evaluated and
 * rejected, and the parent build omits one deliberately. Generated members carry no documentation,
 * and this project's single rule requires a docstring on every method stating its purpose, its
 * parameters and its return value; a generated accessor cannot hold one, so the gate that enforces
 * the rule would have nothing to read. A Java 21 record with an explicit compact constructor gives
 * the same brevity while leaving every member a place to be documented, which is why
 * {@link FieldError} is written that way.</p>
 *
 * <p>Alternatives Considered: this type sits in the same package as the date edit rules, and
 * splitting marker state from date logic into two packages was the alternative. The baseline settles
 * it two out of two: both programs that copy the marker declarations also expand the highlight
 * template. {@code app/cbl/COACTUPC.cbl} copies the declarations at line 166 and expands the
 * template thirty-nine times; {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} copies them at
 * line 76 and expands the template once. There is no program that takes one without the other, so a
 * package boundary between them would separate two contracts that the baseline has never once
 * separated.</p>
 *
 * <p>Assumptions: the two copy statements naming those declarations are spelled differently and both
 * are left exactly as they stand. Line 166 names the file in quotation marks with a terminating
 * period; line 76 names it in quotation marks within a sequence-numbered layout. The difference is
 * recorded so that a reader comparing the two lines does not take either for a defect.</p>
 *
 * <p>Assumptions: the symmetry of the three Java state names is this type's own, and the baseline it
 * derives from is not symmetric. Of the three marker fields at {@code app/cpy/CSUTLDWY.cpy} lines 46
 * to 57 only the year field carries a trailing abbreviation for flag; the month field at line 50 and
 * the day field at line 54 do not. The same inconsistency recurs in the other program that copies
 * them, where {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} lines 110 to 114 give the year
 * subfield an extra infix that the month and day subfields at lines 112 and 114 lack, inside a group
 * of seven lines. Neither asymmetry is reproduced in the Java names, and neither is a defect to be
 * read back into the baseline from the tidier names here.</p>
 *
 * <p>Assumptions: field identity is modelled as a value rather than as a name, and one baseline
 * declaration is the reason it has to be. At {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}
 * lines 115 and 116 a field is declared as a redefinition of itself, which strict COBOL does not
 * admit; the baseline retains that declaration and this migration does not alter it. Identifying a
 * field by name alone is what makes such a collision expressible at all, so identity is passed to
 * {@link #toFieldError(String, String)} as an argument and validated on arrival.</p>
 *
 * <h2>Encoding tolerance, and why it is not laxity</h2>
 *
 * <p>Assumptions: the three bytes are the contract, but two of them demonstrably vary across the
 * baseline while their meanings do not, so {@link #fromCode(char)} accepts both spellings of each.
 * The measurement over all 71 declarations is precise. The unacceptable byte is the digit zero at 71
 * sites out of 71, with no variation at all. The acceptable byte is the low-value byte at 52 sites
 * and the digit one at 16. The never-supplied byte is the letter B at 55 sites and the SPACE
 * character at 16. Decisively, {@code app/cbl/COACTUPC.cbl} -- the program holding thirty-nine of
 * the forty expansions -- carries BOTH spellings, 47 fields using the letter B and two using SPACE,
 * and every one of its expansions works regardless, because the template at
 * {@code app/cpy/CSSETATY.cpy} lines 18 and 19 tests the named condition and never the raw byte. The
 * semantic state is therefore the stable contract and the byte is an encoding detail. Accepting both
 * spellings of a state is not tolerance of arbitrary input: an unrecognised byte is rejected, and
 * the canonical spelling that {@link #code()} emits is the one at
 * {@code app/cpy/CSUTLDWY.cpy} lines 46 to 57.</p>
 *
 * <p>Assumptions: a raw byte is never this type's primary abstraction, and one baseline pairing shows
 * why it must not be. In {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} the digit zero means
 * acceptable on the caller-owned error switch at line 82 and means UNACCEPTABLE on a per-field
 * marker at line 96, fourteen lines apart in one program. The same byte carries opposite senses
 * depending on which field holds it, so this type is an enumeration whose states are named, and the
 * byte is reachable only through {@link #code()} and {@link #fromCode(char)}.</p>
 *
 * <h2>Verification</h2>
 *
 * <p>Assumptions: no golden-master comparison covers this type, and the limit is recorded rather than
 * glossed. The repository's parity oracle suite exercises batch flows, and its own guide records at
 * {@code tests/README.md} lines 83 to 85 that the online programs cannot run end to end without a
 * terminal-monitor runtime, so only their extractable field-validation logic is unit-tested. This
 * type is an online-path concept with no batch caller, so its correctness rests on the unit tests in
 * this module's own test tree together with the baseline citations above. Claiming golden-master
 * backing for it would overstate the evidence behind every assertion made here.</p>
 *
 * <p>This type holds no state beyond its three constants, is safe for concurrent use, and adds no
 * dependency to the module: its whole import list is drawn from the platform library, so a batch
 * job, a message consumer and a REST controller can share one marker contract without any of them
 * acquiring the others' dependencies.</p>
 */
public enum FieldValidationFlag {

    /**
     * The field holds an acceptable value, so nothing about it is reported.
     *
     * <p>Assumptions: this is the state the baseline spells with the low-value byte, at
     * {@code app/cpy/CSUTLDWY.cpy} lines 47, 51 and 55. It is the only state for which
     * {@link #isError()} answers false, and the only one that yields no {@link FieldError}.</p>
     */
    VALID,

    /**
     * The field was supplied but holds a value the rules reject.
     *
     * <p>Assumptions: this is the state the baseline spells with the digit zero, at
     * {@code app/cpy/CSUTLDWY.cpy} lines 48, 52 and 56, and it is the one byte of the three that
     * never varies across the 71 baseline declarations. It is reported as an error and carries no
     * screen marker, because the template at {@code app/cpy/CSSETATY.cpy} lines 23 to 25 conditions
     * the marker on the never-supplied state alone.</p>
     */
    NOT_OK,

    /**
     * The field was never supplied at all.
     *
     * <p>Assumptions: this is the state the baseline spells with the letter B, at
     * {@code app/cpy/CSUTLDWY.cpy} lines 49, 53 and 57. It is an error state -- the disjunction at
     * {@code app/cpy/CSSETATY.cpy} lines 18 and 19 makes no distinction between it and an
     * unacceptable value -- and it is additionally the only state that carries
     * {@link #BLANK_SCREEN_MARKER}, per lines 23 to 25 of that same template.</p>
     */
    BLANK;

    /**
     * The canonical byte for {@link #VALID}, the low-value character.
     *
     * <p>Assumptions: COBOL writes this value as a figurative constant naming the lowest character in
     * the collating sequence, which on every platform this migration targets is the zero byte, and
     * that is what the literal below spells. It is declared as a named constant rather than written
     * as a literal at each comparison because a zero byte is invisible in source and a reader cannot
     * tell a deliberate one from a typing accident.</p>
     */
    public static final char VALID_CODE = '\u0000';

    /**
     * The canonical byte for {@link #NOT_OK}, the digit zero.
     *
     * <p>Assumptions: this is the one byte of the three that does not vary anywhere in the baseline,
     * holding at 71 declarations out of 71. It is nonetheless not a universal marker of failure: the
     * caller-owned error switch at {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} line 82 uses
     * this same digit to mean acceptable, so the byte is meaningful only against the field that holds
     * it.</p>
     */
    public static final char NOT_OK_CODE = '0';

    /**
     * The canonical byte for {@link #BLANK}, the letter B.
     *
     * <p>Assumptions: this is the spelling at {@code app/cpy/CSUTLDWY.cpy} lines 49, 53 and 57, which
     * is the declaration this type treats as normative, and it is the majority spelling at 55 of the
     * 71 baseline declarations. It is deliberately not the same character as
     * {@link #BLANK_SCREEN_MARKER}.</p>
     */
    public static final char BLANK_CODE = 'B';

    /**
     * A second byte the baseline also uses for {@link #VALID}, the digit one.
     *
     * <p>Assumptions: 16 of the 71 baseline declarations spell the acceptable state as the digit one
     * instead of the low-value byte, among them the marker declarations in
     * {@code app/cbl/COCRDUPC.cbl}, {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COACTVWC.cbl} and
     * {@code app/cbl/COCRDSLC.cbl}. It is accepted by {@link #fromCode(char)} and is never emitted by
     * {@link #code()}, so decoding tolerates the variation while encoding stays canonical.</p>
     */
    public static final char ALTERNATE_VALID_CODE = '1';

    /**
     * A second byte the baseline also uses for {@link #BLANK}, the space character.
     *
     * <p>Assumptions: the same 16 declarations spell the never-supplied state as a space rather than
     * the letter B, and {@code app/cbl/COACTUPC.cbl} carries both spellings at once, 47 fields using
     * the letter and two using the space. This is the second arm of the two-way disjunction the
     * baseline's own never-supplied test performs, and it is why {@link #fromCode(char)} resolves two
     * distinct bytes to the one state.</p>
     */
    public static final char ALTERNATE_BLANK_CODE = ' ';

    /**
     * The presentation marker written into a never-supplied field, an asterisk.
     *
     * <p>Assumptions: this is a screen artefact and not a state value. The baseline moves it into the
     * field's DATA position at {@code app/cpy/CSSETATY.cpy} lines 24 and 25, having moved the error
     * colour into that field's separate ATTRIBUTE position two lines earlier, and it does so only
     * when the marker reports never-supplied. It is published as text rather than as a character
     * because it is carried in a response body beside the field identity, never stored in a marker
     * field, and {@link #code()} can therefore never return it.</p>
     */
    public static final String BLANK_SCREEN_MARKER = "*";

    /**
     * The absence of a presentation marker, an empty string.
     *
     * <p>Trade-offs: an empty string is returned for the two states that carry no marker, rather than
     * an absent optional value. The compromise accepted is that a caller cannot distinguish "no
     * marker" from "marker not computed", which is a distinction the baseline does not make either:
     * the field simply keeps whatever it already held, because the move at
     * {@code app/cpy/CSSETATY.cpy} lines 24 and 25 is skipped. An empty string concatenates and
     * renders without a null check at every call site, which an optional value would require at
     * each.</p>
     */
    public static final String NO_SCREEN_MARKER = "";

    /**
     * The low-value character that marks an absent INPUT, as distinct from a marker byte.
     *
     * <p>Assumptions: this is the first arm of the two-way test the baseline performs on a field's
     * VALUE to decide whether the field was supplied, at {@code app/cpy/CSUTLDPY.cpy} lines 30, 94 and
     * 154. It is deliberately declared apart from {@link #VALID_CODE} even though the two hold the
     * same character, because they belong to different layers and mean opposite things: as the content
     * of a MARKER field the low-value byte means the field is acceptable, per
     * {@code app/cpy/CSUTLDWY.cpy} line 47, while as the content of an INPUT field it means the field
     * was never supplied. Naming the two roles separately is what keeps a reader of
     * {@link #isNeverSupplied(String)} from concluding that an absent input is an acceptable one.</p>
     */
    public static final char ABSENT_INPUT_LOW_VALUE = '\u0000';

    /**
     * The space character that marks an absent INPUT.
     *
     * <p>Assumptions: this is the second arm of that same two-way test, the alternative the baseline
     * checks immediately after the first at {@code app/cpy/CSUTLDPY.cpy} lines 31, 95 and 155. It is
     * declared apart from {@link #ALTERNATE_BLANK_CODE} for the same layering reason as its
     * companion above: that constant is a byte STORED IN a marker field, this one is a byte FOUND IN
     * an input field, and only the second of the two is what a screen returns for an untouched
     * box.</p>
     */
    public static final char ABSENT_INPUT_SPACE = ' ';


    /**
     * Returns the canonical baseline byte for this state.
     *
     * <p>The value returned is always the spelling declared at {@code app/cpy/CSUTLDWY.cpy} lines 46
     * to 57, never one of the two alternates {@link #fromCode(char)} also accepts, so a value
     * round-tripped through this type is normalised onto the normative encoding.</p>
     *
     * @return the canonical byte, one of {@link #VALID_CODE}, {@link #NOT_OK_CODE} or
     *     {@link #BLANK_CODE}; never {@link #ALTERNATE_VALID_CODE},
     *     {@link #ALTERNATE_BLANK_CODE} or the asterisk of {@link #BLANK_SCREEN_MARKER}
     */
    public char code() {
        // WHY : Alternatives Considered: holding the byte in an instance field populated from each
        //       constant's argument list was the obvious shape and is unavailable, which was
        //       confirmed by compiling it rather than reasoned about. Java requires enum constants to
        //       be the first members of the body, so the named constants cannot be declared above
        //       them, and an argument list referring to a constant declared below is rejected as an
        //       illegal forward reference by javac 21.0.11. Spelling the three bytes as literals in
        //       the argument lists would compile, at the cost of declaring each contract byte twice:
        //       once as an unnamed literal and once as the named constant callers read. This switch
        //       leaves each byte declared exactly once, and its exhaustiveness over the three states
        //       is checked by the compiler, so a fourth state could not be added without this method
        //       failing to compile.
        return switch (this) {
            case VALID -> VALID_CODE;
            case NOT_OK -> NOT_OK_CODE;
            case BLANK -> BLANK_CODE;
        };
    }

    /**
     * Reports whether the field holds an acceptable value.
     *
     * @return {@code true} only for {@link #VALID}; {@code false} for both error states
     */
    public boolean isValid() {
        return this == VALID;
    }

    /**
     * Reports whether the field is in error, in either of the two ways a field can be.
     *
     * <p>This is the migrated form of the single question the baseline's highlight template asks. At
     * {@code app/cpy/CSSETATY.cpy} lines 18 and 19 that question is one disjunction over the
     * unacceptable and never-supplied conditions, so this predicate answers {@code true} for both and
     * is the predicate a per-field error array is built from.</p>
     *
     * <p>Assumptions: the baseline conjoins a further condition at line 20 of that template, gating
     * the highlight on a subsequent pass through the program. This predicate has no such gate and
     * cannot acquire one, so it answers identically for identical input. The reasoning is set out on
     * this type.</p>
     *
     * @return {@code true} for {@link #NOT_OK} and for {@link #BLANK}; {@code false} only for
     *     {@link #VALID}
     */
    public boolean isError() {
        // WHY : Trade-offs: this is written as the negation of the single acceptable state rather
        //       than as a disjunction of the two error states. Both read identically today. The
        //       negation is chosen because a state added to this type would default to being
        //       reported as an error, which fails safe towards surfacing a field the client can
        //       correct, whereas a two-term disjunction would silently omit the new state and let an
        //       unvalidated field pass as acceptable. That omission is the failure mode the
        //       forty expanded copies of the disjunction in the baseline are most exposed to.
        return this != VALID;
    }

    /**
     * Reports whether this state carries the asterisk screen marker.
     *
     * <p>This is the migrated form of the template's inner test. At {@code app/cpy/CSSETATY.cpy} lines
     * 23 to 25 the asterisk is moved into the field only when the marker reports never-supplied, so
     * an unacceptable value is highlighted without being marked. Keeping the two actions separate is
     * the reason this type retains three states rather than collapsing to a boolean.</p>
     *
     * @return {@code true} only for {@link #BLANK}; {@code false} for {@link #NOT_OK} and for
     *     {@link #VALID}
     */
    public boolean requiresBlankMarker() {
        return this == BLANK;
    }

    /**
     * Returns the screen marker text this state contributes to a rendered field.
     *
     * @return {@link #BLANK_SCREEN_MARKER} for {@link #BLANK}; otherwise {@link #NO_SCREEN_MARKER}
     */
    public String screenMarker() {
        return requiresBlankMarker() ? BLANK_SCREEN_MARKER : NO_SCREEN_MARKER;
    }

    /**
     * Resolves a stored baseline byte to the state it denotes, accepting either spelling of a state.
     *
     * <p>Two of the three states are spelled two ways in the baseline and both spellings resolve here
     * to the one state, which is what the baseline's own never-supplied test does with its two-arm
     * disjunction. The measurement that establishes the variation, and the reason the semantic state
     * rather than the byte is this type's contract, are set out on this type.</p>
     *
     * @param code a single byte as stored in a baseline marker field; accepted values are
     *     {@link #VALID_CODE} and {@link #ALTERNATE_VALID_CODE} for {@link #VALID},
     *     {@link #NOT_OK_CODE} for {@link #NOT_OK}, and {@link #BLANK_CODE} and
     *     {@link #ALTERNATE_BLANK_CODE} for {@link #BLANK}
     * @return the state the byte denotes, never {@code null}
     * @throws IllegalArgumentException if {@code code} is none of the five accepted bytes, because a
     *     marker field holding an unrecognised byte has no state this type can report and treating it
     *     as acceptable would pass an unvalidated field through as though it had been checked
     */
    public static FieldValidationFlag fromCode(char code) {
        // WHY : Assumptions: the asterisk is deliberately absent from the accepted set even though it
        //       is one of the four characters this contract names. It is written into a SCREEN field
        //       at app/cpy/CSSETATY.cpy lines 24 and 25, never into the marker field, so a marker
        //       field holding it means the two layers have been conflated somewhere upstream. Falling
        //       through to the rejection below reports that rather than absorbing it.
        return switch (code) {
            case VALID_CODE, ALTERNATE_VALID_CODE -> VALID;
            case NOT_OK_CODE -> NOT_OK;
            case BLANK_CODE, ALTERNATE_BLANK_CODE -> BLANK;
            default -> throw new IllegalArgumentException(describeUnknownCode(code));
        };
    }

    /**
     * Builds the message for a byte that denotes no known state.
     *
     * @param code the unrecognised byte, reported in a form that stays legible for a
     *     non-printing value
     * @return the exception detail message naming the offending byte
     */
    private static String describeUnknownCode(char code) {
        // WHY : Assumptions: the byte is reported as a numeric code point rather than as itself,
        //       because three of the five bytes this contract uses are invisible or
        //       indistinguishable when printed -- the low-value byte renders as nothing at all, and
        //       a space is indistinguishable from the surrounding message text. A message reading
        //       "unrecognised code ' '" would leave a reader unable to tell which of those two
        //       arrived, which is exactly the diagnosis this message exists to supply.
        return "unrecognised field validation code: U+"
                + String.format("%04X", (int) code)
                + "; expected one of U+0000, '1', '0', 'B' or ' '";
    }


    /**
     * Reports whether a raw input value counts as never supplied, by the baseline's two-way test.
     *
     * <p>This is the generic first gate every validated field passes through, and it is the reason
     * {@link #BLANK} exists as a state separate from {@link #NOT_OK}. The baseline performs it as a
     * two-arm disjunction over the field's VALUE -- not over its marker byte -- at
     * {@code app/cpy/CSUTLDPY.cpy} lines 30 and 31 for the year, lines 94 and 95 for the month and
     * lines 154 and 155 for the day, and on either arm it then sets two things at lines 32 and 33: a
     * caller-owned error switch AND the never-supplied marker. This method is the first half of that,
     * leaving the caller to record the state; the second half is why {@link #isError()} answers
     * {@code true} for {@link #BLANK}.</p>
     *
     * <p>A caller composes the whole decision for one field from this primitive plus its own rules:
     * a never-supplied value yields {@link #BLANK}, a supplied value that fails the rules yields
     * {@link #NOT_OK}, and a supplied value that passes yields {@link #VALID}.</p>
     *
     * <p>Assumptions: the two arms are tested separately rather than merged, so a value mixing
     * low-value characters with spaces satisfies NEITHER arm and is reported as supplied. That is what
     * the baseline does, because a COBOL comparison against a figurative constant requires EVERY
     * character position to match it and a mixed field equals neither constant. The consequence is
     * benign and is worth stating: such a field is then judged by the caller's own rules, which reject
     * it, so it is reported as {@link #NOT_OK} rather than {@link #BLANK}. Both are error states and
     * {@link #isError()} answers {@code true} for either, so the field is reported to the client
     * either way and only its screen marker differs.</p>
     *
     * <p>Assumptions: whitespace other than the space character does not mark a field absent. A tab or
     * a line separator equals neither of the two figurative constants the baseline compares against,
     * so this method deliberately does not use the platform's general blank test, which would treat
     * any whitespace as absent while also treating a run of low-value characters as present -- wrong
     * on both counts against {@code app/cpy/CSUTLDPY.cpy} lines 30 and 31.</p>
     *
     * @param value the raw value returned for the field, which may be {@code null} when nothing
     *     arrived for it at all
     * @return {@code true} when the value is {@code null}, is empty, consists wholly of
     *     {@link #ABSENT_INPUT_LOW_VALUE} or consists wholly of {@link #ABSENT_INPUT_SPACE};
     *     {@code false} otherwise
     */
    public static boolean isNeverSupplied(String value) {
        // WHY : Assumptions: a null and an empty value are folded into the same answer as a run of
        //       pad characters, because the baseline cannot tell those cases apart and this method
        //       must not invent a distinction it never made. A screen field always arrives at a COBOL
        //       program as a character field of declared width, so "nothing arrived" reaches it as
        //       pad characters rather than as an absence; over HTTP the same field arrives absent or
        //       empty. All three describe a field the user never filled in.
        if (value == null || value.isEmpty()) {
            return true;
        }

        // WHY : Assumptions: the arms are evaluated over ALL characters separately rather than
        //       character by character against either pad, so a mixed value satisfies neither. The
        //       reasoning, and why the outcome stays safe, is on this method.
        return isEntirely(value, ABSENT_INPUT_LOW_VALUE) || isEntirely(value, ABSENT_INPUT_SPACE);
    }

    /**
     * Reports whether every character of a value is one given pad character.
     *
     * @param value the value to inspect, never {@code null} and never empty when called from
     *     {@link #isNeverSupplied(String)}
     * @param pad the pad character that every position must hold for the answer to be {@code true}
     * @return {@code true} when all characters equal {@code pad}; {@code false} on the first that does
     *     not
     */
    private static boolean isEntirely(String value, char pad) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != pad) {
                return false;
            }
        }

        return true;
    }

    /**
     * Produces the per-field error entry for one named field, if this state is an error at all.
     *
     * <p>This is the runtime replacement for one textual expansion of the baseline's highlight
     * template. Where the baseline substitutes a marker name, a screen field name and a map name into
     * a copy of the template at compile time -- forty times over, as recorded on this type -- a caller
     * here passes the field identity as an argument and reaches one implementation.</p>
     *
     * @param field the identity of the field being reported, as it is named in the response body and
     *     matched by the client; must not be {@code null}, empty or entirely whitespace
     * @param message the help text describing what is wrong with the field, carried beside the
     *     identity so the client can render it against the field; must not be {@code null}, empty or
     *     entirely whitespace
     * @return an entry describing the error, or an empty optional when this state is {@link #VALID}
     *     and there is consequently nothing to report
     * @throws NullPointerException if {@code field} or {@code message} is {@code null}, and only when
     *     this state is an error, since the arguments are unused for an acceptable field
     * @throws IllegalArgumentException if {@code field} or {@code message} is empty or entirely
     *     whitespace, under the same condition
     */
    public Optional<FieldError> toFieldError(String field, String message) {
        // WHY : Trade-offs: this makes the arguments unvalidated on the acceptable path, so a caller
        //       passing a null identity for a field that turns out to be acceptable is not told. The
        //       compromise is accepted deliberately, because the alternative reverses the baseline's
        //       own control flow: at app/cpy/CSSETATY.cpy lines 18 and 19 the template evaluates
        //       nothing whatsoever for a field that is not in error. Validating first would also
        //       oblige every caller to supply help text for fields it has no complaint about, which
        //       for a screen such as the account update -- 39 validated fields, most of them
        //       acceptable on any given submission -- means resolving message text that is then
        //       discarded.
        if (isValid()) {
            return Optional.empty();
        }

        return Optional.of(new FieldError(field, this, message));
    }

    /**
     * Collects the per-field error array for a set of validated fields, in encounter order.
     *
     * <p>This is the assembled form of the contract: one entry per field in error, each carrying its
     * identity, its state and its help text, ready to be placed in a response body by the layer that
     * owns the response shape. Acceptable fields contribute nothing, exactly as a field that is not
     * in error contributes nothing at {@code app/cpy/CSSETATY.cpy} lines 18 and 19.</p>
     *
     * @param flagsByField the state of each validated field keyed by field identity; iterated in
     *     whatever order the supplied map defines, so an insertion-ordered map yields the fields in
     *     the order they were validated. Must not be {@code null}, and must contain neither a
     *     {@code null} key nor a {@code null} value
     * @param messageForField resolves the help text for a field identity, invoked only for a field
     *     that is in error; must not be {@code null}, and must not return {@code null}, an empty
     *     string or a wholly whitespace string for any identity it is asked about
     * @return an unmodifiable list holding one entry per field in error, in the iteration order of
     *     {@code flagsByField}; empty when every field is acceptable
     * @throws NullPointerException if either argument is {@code null}, if any key or value of
     *     {@code flagsByField} is {@code null}, or if {@code messageForField} returns {@code null} for
     *     a field in error
     * @throws IllegalArgumentException if a key of {@code flagsByField} is empty or entirely
     *     whitespace, or if {@code messageForField} returns such a string for a field in error
     */
    public static List<FieldError> collectErrors(
            Map<String, FieldValidationFlag> flagsByField,
            Function<String, String> messageForField) {

        Objects.requireNonNull(flagsByField, "flagsByField must not be null");
        Objects.requireNonNull(messageForField, "messageForField must not be null");

        // WHY : Assumptions: the order fields are reported in is part of the migrated behaviour, not
        //       an implementation detail, so it is preserved rather than sorted or grouped. The
        //       baseline highlights fields by running its forty expansions in the order they are
        //       written, from app/cbl/COACTUPC.cbl line 3208 to line 3432, which is the order the
        //       fields appear on the screen. A caller passing an insertion-ordered map therefore gets
        //       the baseline's reporting order back; a caller passing an unordered map gets that
        //       map's order, which is its own choice to make and is documented on the parameter
        //       rather than silently overridden here.
        List<FieldError> errors = new ArrayList<>();

        for (Map.Entry<String, FieldValidationFlag> entry : flagsByField.entrySet()) {
            String field = entry.getKey();
            FieldValidationFlag state = entry.getValue();

            // WHY : Assumptions: a null key or value is rejected here rather than being skipped. A
            //       missing state for a field the caller has listed means the field was never
            //       validated, and skipping it would report that field as acceptable -- the one
            //       outcome this type exists to prevent, since an unvalidated field would then reach
            //       persistence indistinguishable from a checked one.
            Objects.requireNonNull(field, "flagsByField must not contain a null field identity");
            Objects.requireNonNull(
                    state, () -> "flagsByField must not contain a null state, found one for " + field);

            // WHY : Trade-offs: this guard duplicates the acceptable-state test that
            //       toFieldError already performs, and the duplication is accepted because it is
            //       what makes the resolver lazy. Java evaluates an argument before the call it
            //       belongs to, so resolving the message inside the toFieldError argument list
            //       would invoke the resolver for EVERY field including the acceptable ones,
            //       defeating the early return that method documents and contradicting the
            //       contract stated on the parameter above. On the account update screen, whose
            //       39 validated fields are mostly acceptable on any one submission, that is
            //       roughly 36 message lookups whose results are discarded; worse, a resolver
            //       that raises for a field it has no message for would then raise on a
            //       submission that had nothing wrong with it.
            if (state.isError()) {
                state.toFieldError(field, messageForField.apply(field)).ifPresent(errors::add);
            }
        }

        return Collections.unmodifiableList(errors);
    }

    /**
     * Validates that a supplied text argument carries content.
     *
     * @param value the argument to check
     * @param name the parameter name, used in the failure message so a caller can identify which
     *     argument was at fault
     * @return {@code value} unchanged, so the check can be applied inline at the point of assignment
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is empty or contains only whitespace
     */
    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, () -> name + " must not be null");

        // WHY : Assumptions: a wholly whitespace value is rejected as well as an empty one, because
        //       the two error states of this contract exist precisely to distinguish a field that was
        //       never supplied from one holding a bad value. A blank field identity or a blank help
        //       text would render as an entry the client cannot attach to any field and cannot
        //       display, which reports the presence of an error while withholding both of the things
        //       needed to act on it.
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }

        return value;
    }

    /**
     * One entry of the per-field error array: which field, in which error state, and why.
     *
     * <p>This is the structured form of what the baseline's highlight template writes onto a screen.
     * The template's two actions map onto two parts of this entry: the error colour it moves into the
     * field's attribute position at {@code app/cpy/CSSETATY.cpy} lines 21 and 22 becomes the presence
     * of the entry itself, and the asterisk it moves into the field's data position at lines 24 and 25
     * becomes {@link #screenMarker()}. The help text has no counterpart in that template, which sets
     * colour and marker only; it is supplied by the caller from the migrated message catalogue so that
     * the client has something to render beside the field.</p>
     *
     * <p>Assumptions: an entry exists only for a field in error. There is no entry carrying
     * {@link FieldValidationFlag#VALID}, because the compact constructor rejects it, so the mere
     * presence of an entry means the field is in error and no consumer has to re-test the state to
     * find out.</p>
     *
     * <p>Alternatives Considered: carrying the screen marker as a fourth component was evaluated and
     * rejected. It is derivable from the state in every case, so a fourth component would admit an
     * entry whose marker and state disagree -- an asterisk against an unacceptable value, say, which
     * the baseline never produces because lines 23 to 25 of that template condition the marker on the
     * never-supplied state alone. Deriving it in {@link #screenMarker()} makes that disagreement
     * unrepresentable rather than merely discouraged.</p>
     *
     * @param field the identity of the field in error, as named in the response body and matched by
     *     the client; never {@code null}, empty or entirely whitespace
     * @param state the error state of that field, either {@link FieldValidationFlag#NOT_OK} or
     *     {@link FieldValidationFlag#BLANK}; never {@code null} and never
     *     {@link FieldValidationFlag#VALID}
     * @param message the help text describing what is wrong with the field; never {@code null}, empty
     *     or entirely whitespace
     */
    public record FieldError(String field, FieldValidationFlag state, String message) {

        /**
         * Rejects any entry that could not describe a field in error.
         *
         * @param field the field identity to accept, required to carry content because an entry the
         *     client cannot attach to a field is unusable to it
         * @param state the state to accept, required to be one of the two error states because the
         *     presence of an entry is what signals an error
         * @param message the help text to accept, required to carry content because an entry the
         *     client cannot display withholds the reason for the error it announces
         * @throws NullPointerException if {@code field}, {@code state} or {@code message} is
         *     {@code null}
         * @throws IllegalArgumentException if {@code field} or {@code message} is empty or entirely
         *     whitespace, or if {@code state} is {@link FieldValidationFlag#VALID}
         */
        public FieldError {
            field = requireText(field, "field");
            message = requireText(message, "message");
            Objects.requireNonNull(state, "state must not be null");

            // WHY : Assumptions: an acceptable state is rejected rather than stored, which is what
            //       makes the presence of an entry sufficient evidence of an error. The baseline
            //       reaches its highlight only through the disjunction at app/cpy/CSSETATY.cpy lines
            //       18 and 19, so it has no representation for "this field is fine" on the screen
            //       either; an entry carrying an acceptable state would be a shape with no baseline
            //       counterpart, and a consumer counting entries to decide whether a submission
            //       failed would count it and reject a valid submission.
            if (state.isValid()) {
                throw new IllegalArgumentException(
                        "state must be an error state, but was " + state + " for field " + field);
            }
        }

        /**
         * Returns the screen marker this entry contributes, derived from its state.
         *
         * @return {@link FieldValidationFlag#BLANK_SCREEN_MARKER} when the field was never supplied;
         *     otherwise {@link FieldValidationFlag#NO_SCREEN_MARKER}
         */
        public String screenMarker() {
            return state.screenMarker();
        }
    }
}
