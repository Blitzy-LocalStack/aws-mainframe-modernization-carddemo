package com.carddemo.account.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.validation.FieldValidationFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Validates the three address elements whose acceptable values the baseline holds as lookup
 * allow-lists: a North American telephone area code, a United States state code, and a state
 * paired with the leading digits of its postal code.
 *
 * <h2>What this class migrates</h2>
 *
 * <p>The allow-lists live in {@code app/cpy/CSLKPCDY.cpy}, 1318 lines, whose own header at lines 2
 * to 5 announces the three of them in exactly that order. They are not data in that file: they are
 * condition names attached to three working-storage fields, so the baseline's test is "is this
 * candidate one of these literals" and the migrated form of that test is whether a row exists.
 * {@code app/cbl/COACTUPC.cbl} draws the copybook in at line 602 and consumes it from three
 * validation paragraphs, and this class carries one method per paragraph so that the matrix at
 * {@code docs/architecture/cobol-to-service-traceability.md} can cite a paragraph-to-method pair
 * rather than naming a class and leaving a reader to search it.</p>
 *
 * <ul>
 *   <li>{@code 1260-EDIT-US-PHONE-NUM} at {@code app/cbl/COACTUPC.cbl} lines 2225 to 2427, with its
 *       three sub-paragraphs at lines 2246, 2316 and 2370, becomes
 *       {@link #validateUsPhoneNumber(String, String, String)} and the area-code half of it becomes
 *       {@link #validateAreaCode(String, String, String)}.</li>
 *   <li>{@code 1270-EDIT-US-STATE-CD} at lines 2493 to 2511 becomes
 *       {@link #validateStateCode(String, String, String)}.</li>
 *   <li>{@code 1280-EDIT-US-STATE-ZIP-CD} at lines 2536 to 2558 becomes
 *       {@link #validateStateZipCombination(String, String, String, String)}.</li>
 * </ul>
 *
 * <h2>Three allow-lists sit on one field, and all three are consulted</h2>
 *
 * <p>Assumptions: the telephone target carries THREE allow-lists, not one, and every one of them is
 * consulted here. {@code app/cpy/CSLKPCDY.cpy} line 24 declares
 * {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX.} -- the legacy short spelling of a three
 * character field, not a differently shaped one -- and hangs three condition names over that single
 * field: {@code VALID-PHONE-AREA-CODE} at line 30, {@code VALID-GENERAL-PURP-CODE} at line 521 and
 * {@code VALID-EASY-RECOG-AREA-CODE} at line 931. Reading only the first would answer a narrower
 * question than the baseline asks, so {@link #classifyAreaCode(String)} reports the candidate's
 * standing against each of the three.</p>
 *
 * <p>Assumptions: the three lists form a total and disjoint partition, which is what lets one
 * primary-key probe answer all three questions instead of three separate reads. Counting the
 * distinct literals in each range of the copybook gives 490 for line 30, 410 for line 521 and 80 for
 * line 931; 410 and 80 sum to 490, the two narrower lists share no member, and their union equals
 * the broad one exactly. The migrated schema records that partition rather than rediscovering it:
 * {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} line 327
 * declares {@code reference.us_phone_area_codes} with a classification column bounded at line 359 to
 * the two values {@code 'G'} and {@code 'E'}. Presence of a row therefore answers line 30,
 * classification {@code 'G'} answers line 521 and classification {@code 'E'} answers line 931.</p>
 *
 * <p>Assumptions: the acceptance gate the account update path applies is the general-purpose list at
 * line 521, and this is read from the program rather than inferred from the copybook. At
 * {@code app/cbl/COACTUPC.cbl} lines 2296 and 2297 the trimmed candidate is moved into the field,
 * and line 2298 tests {@code IF VALID-GENERAL-PURP-CODE}. The message the failure emits corroborates
 * it word for word at line 2306. An area code that is assigned in the plan but easily recognisable
 * is therefore reported by {@link #classifyAreaCode(String)} as assigned, and refused by
 * {@link #validateAreaCode(String, String, String)} with that same line 2306 wording, because those
 * are two different questions and the baseline answers them differently.</p>
 *
 * <p>Assumptions: the area-code list is a point-in-time snapshot of a registry maintained outside
 * this repository, not a derived or computable set. The copybook records its own provenance in the
 * comment at lines 25 to 29, whose lines 26 to 28 name the North American Numbering Plan
 * Administrator and the published numbering-plan-area report the literals were taken from. A code
 * assigned after that snapshot is absent from the list and is refused, and that is a data currency
 * question answered by reseeding the reference table, never by widening the rule here.</p>
 *
 * <p>Assumptions: the state and postal-prefix pairings are a second, independent point-in-time
 * snapshot. {@code app/cbl/COACTUPC.cbl} line 2535 records it in the program's own words as a crude
 * edit based on postal service data, so the same currency caveat applies to that list and for the
 * same reason.</p>
 *
 * <h2>The tables are queried here and owned elsewhere</h2>
 *
 * <p>Assumptions: {@code reference.us_phone_area_codes}, {@code reference.us_states} and
 * {@code reference.us_state_zip_prefixes} are owned and seeded by {@code reference-service}, whose
 * {@code V2__seed_reference.sql} loads them from this copybook. This class neither owns, seeds nor
 * caches them, and it holds no in-code copy of any of the three lists. Two independent properties of
 * the design make a direct read impossible rather than merely discouraged: account-service has no
 * lookup repository -- its repository package is exactly the account, customer and cross-reference
 * repositories -- and the {@code reference} schema is not among the cross-schema grants, which are
 * issued to the batch context alone. The lookup therefore crosses the service boundary, and it
 * crosses it as a call rather than as an import, which is the arrangement
 * {@code reference-service}'s own package charter records at lines 146 to 156 of
 * {@code services/reference-service/src/main/java/com/carddemo/reference/service/package-info.java}.
 * No type from that service is imported here; the contract this class depends on is
 * {@link ReferenceAddressLookup}, declared below and satisfied by an adapter.</p>
 *
 * <p>Alternatives Considered: a resilience library and a circuit breaker in front of that lookup were
 * both evaluated and both rejected, and the posture adopted instead is bounded timeouts only. The
 * call is a synchronous hop inside the private network, reached through an internal load balancer,
 * so the failure it has to survive is a slow or unreachable peer and an explicit connect timeout
 * plus an explicit read timeout is the whole of what bounds that. A breaker would add a state
 * machine that can itself refuse a call the peer would have served, which is a new failure mode in
 * exchange for none removed. Retry was considered separately and also declined: these three probes
 * are pure reads, so a retry would be safe, but a retried read behind a bounded timeout multiplies
 * the worst-case latency of an address edit by the attempt count while the operator waits. Recorded
 * here rather than in the adapter because the constraint belongs to the contract, and
 * {@link ReferenceAddressLookup} restates it as an obligation on any implementation.</p>
 *
 * <h2>Message text is carried across byte for byte</h2>
 *
 * <p>Assumptions: every message this class emits is the baseline literal unchanged, including its
 * leading punctuation and the presence or absence of a closing period, and no whitespace in any of
 * them is normalised. Three of them show why that has to be deliberate rather than incidental:
 * {@code ': is not a valid state code'} at {@code app/cbl/COACTUPC.cbl} line 2503 opens with a colon
 * and a space and closes without a period; {@code ': Area code must be supplied.'} at line 2254
 * opens the same way and does close with one; and {@code 'Invalid zip code for state'} at line 2550
 * carries no leading punctuation at all because, alone among the messages in this area, it is not
 * prefixed with a field name. Tidying any of the three into a house form would change text an
 * operator reads.</p>
 *
 * <p>Assumptions: the messages that take a prefix are assembled the way the baseline assembles them,
 * from the trimmed field label followed by the literal. The baseline writes that as
 * {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} followed by the literal, at
 * {@code app/cbl/COACTUPC.cbl} lines 2502 and 2503, and the label it trims is a plain caller-supplied
 * string set immediately before the edit runs -- {@code 'State'} at line 1592,
 * {@code 'Phone Number 1'} at line 1632 and {@code 'Phone Number 2'} at line 1640. The label is
 * consequently a parameter of each method here and not a constant of this class, because one edit
 * serves two differently labelled telephone fields.</p>
 *
 * <p>Refactoring Rationale: the aggregate message is latched rather than overwritten, and the
 * baseline mechanism it replaces is a program-wide field guarded by a condition name. Every emitting
 * site is wrapped in {@code IF WS-RETURN-MSG-OFF}, at {@code app/cbl/COACTUPC.cbl} lines 2500, 2548
 * and 2251 among others, over the field declared at line 479 as {@code PIC X(75)} whose line 480
 * condition name {@code WS-RETURN-MSG-OFF} tests it for spaces. First message wins, and later
 * failures record their field markers without disturbing it. A program-wide mutable field cannot
 * survive into a stateless service, so the latch becomes the nullable message component of
 * {@link AddressValidationResult}: absent when nothing failed, and otherwise the first failure's
 * text. That is the same convention {@link ApiError} already uses for its own message, and
 * {@link ApiError#latchMessage(String)} is the method that applies it, so the aggregate is assembled
 * by the layer that owns the response rather than duplicated here. The width is not restated either:
 * the 75 of line 479 is published once as {@link ApiError#MESSAGE_RENDERING_WIDTH}.</p>
 *
 * <h2>Fixed-width input, and why parsing is by position</h2>
 *
 * <p>Assumptions: every value reaching these methods originates in a fixed-width field, so it is
 * normalised to the declared width before it is inspected. The customer record declares
 * {@code CUST-ADDR-STATE-CD PIC X(02)} at {@code app/cpy/CVCUS01Y.cpy} line 12,
 * {@code CUST-ADDR-ZIP PIC X(10)} at line 14 and both telephone fields as {@code PIC X(15)} at lines
 * 15 and 16. A COBOL move of a shorter alphanumeric value into a longer field left-justifies it and
 * fills the remainder with spaces, and a longer value is truncated on the right; over HTTP the same
 * field can arrive shorter, longer, empty or absent. Normalising to the declared width reproduces
 * the move rather than approximating it, and it is what makes positional parsing total instead of a
 * source of index errors.</p>
 *
 * <p>Assumptions: the values in the reference tables are stored in a blank-insensitive fixed-width
 * type, so a padded probe still matches its row. The three columns are declared {@code CHAR(3)},
 * {@code CHAR(2)} and {@code CHAR(4)} at
 * {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} lines 328, 390
 * and 413, and the note beside the state column records that the choice is what lets a value arriving
 * from a fixed-width source match without being trimmed first.</p>
 *
 * <p>Assumptions: reading the copybook by whitespace runs rather than by fixed columns is a hard
 * requirement, and it is stated here because the file looks columnar and is not. 1033 of its 1318
 * lines carry literal tab characters, and the indentation is not even uniform between lists: lines 31
 * to 33 open with two tabs and nothing else, while the state literals from line 1014 open with a
 * space followed by two tabs. Anything that reads this file -- the reference seed, a verification
 * query, a future audit of the lists against a fresh registry export -- must tokenise on whitespace
 * runs, because a fixed-column parse of it does not fail loudly. It yields values that are quietly
 * wrong.</p>
 *
 * <h2>Concurrency and cost</h2>
 *
 * <p>This class holds no mutable state. Its only field is the injected lookup contract, so a single
 * instance is safe for concurrent use by every request thread and it is registered as one. Every
 * method returns a fresh result and mutates nothing, and no method reads a clock, a session or any
 * other per-conversation state, which is what keeps two identical inputs answerable identically.</p>
 *
 * <p>Assumptions: the documentation obligation these comments discharge is defined once at
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and the four rationale labels used throughout are the
 * ones it names. Presence and completeness are checked mechanically by
 * {@code config/checkstyle/checkstyle.xml}, which the build binds to the {@code validate} phase.</p>
 *
 * <p>Assumptions: no golden-master comparison covers any rule in this class, and the limit is
 * recorded rather than glossed. {@code tests/README.md} lines 83 to 85 state that the online
 * programs cannot be run end to end without a terminal-monitor runtime and that only their
 * extractable field-validation logic is unit-tested, and the business rules that guide's section 13
 * asserts verbatim from line 553 onwards are the posting, interest and category-balance rules -- none
 * of which reaches {@code app/cbl/COACTUPC.cbl} or this copybook. Correctness here therefore rests on
 * the transcribed logic, the copybook contracts cited above and this module's own unit tests.
 * Claiming an oracle would overstate the evidence behind every assertion made here.</p>
 */
@Service
public class AddressValidationService {

    /**
     * Message suffix for a state code absent from the allow-list, from line 2503 of the baseline.
     *
     * <p>Assumptions: the leading colon and space belong to the literal and the absence of a closing
     * period does too, exactly as {@code app/cbl/COACTUPC.cbl} line 2503 writes it. Appended to the
     * trimmed label {@code 'State'} of line 1592 the operator reads
     * {@code State: is not a valid state code}.</p>
     */
    public static final String MSG_STATE_CODE_INVALID = ": is not a valid state code";

    /**
     * Message for a state and postal-prefix pairing absent from the allow-list, from line 2550.
     *
     * <p>Assumptions: this literal is used whole and is never given a field-name prefix. It is the
     * one message in this area that {@code app/cbl/COACTUPC.cbl} does not assemble from the label at
     * all: the assembly at lines 2549 and 2550 goes straight from the verb to the literal with no
     * trimmed label between them, where every neighbouring message has one. Prefixing it to match
     * those neighbours would add text the baseline never emits.</p>
     */
    public static final String MSG_STATE_ZIP_COMBINATION_INVALID = "Invalid zip code for state";

    /**
     * Message suffix for an area code that was never supplied, from line 2254 of the baseline.
     *
     * <p>Assumptions: this literal closes with a period where its state-code counterpart does not.
     * The inconsistency is the baseline's, at {@code app/cbl/COACTUPC.cbl} lines 2254 and 2503, and
     * both are carried as they stand.</p>
     */
    public static final String MSG_AREA_CODE_BLANK = ": Area code must be supplied.";

    /**
     * Message suffix for an area code that is not three digits, from line 2272 of the baseline.
     *
     * <p>Assumptions: the capital {@code A} before the digit count is the baseline's own, at
     * {@code app/cbl/COACTUPC.cbl} line 2272, and is not corrected to a lower-case article.</p>
     */
    public static final String MSG_AREA_CODE_NOT_THREE_DIGITS = ": Area code must be A 3 digit number.";

    /**
     * Message suffix for an all-zero area code, from line 2286 of the baseline.
     */
    public static final String MSG_AREA_CODE_ZERO = ": Area code cannot be zero";

    /**
     * Message suffix for an area code outside the general-purpose list, from line 2306.
     *
     * <p>Assumptions: this wording is the corroboration that the gate at
     * {@code app/cbl/COACTUPC.cbl} line 2298 is the general-purpose list of
     * {@code app/cpy/CSLKPCDY.cpy} line 521 and not the broader list of line 30. It names the
     * narrower list explicitly, so an implementation testing the broader one would emit a message
     * that contradicts its own rule.</p>
     */
    public static final String MSG_AREA_CODE_NOT_GENERAL_PURPOSE =
            ": Not valid North America general purpose area code";

    /**
     * Message suffix for a telephone prefix that was never supplied, from line 2325.
     */
    public static final String MSG_PREFIX_BLANK = ": Prefix code must be supplied.";

    /**
     * Message suffix for a telephone prefix that is not three digits, from line 2343.
     */
    public static final String MSG_PREFIX_NOT_THREE_DIGITS = ": Prefix code must be A 3 digit number.";

    /**
     * Message suffix for an all-zero telephone prefix, from line 2357 of the baseline.
     */
    public static final String MSG_PREFIX_ZERO = ": Prefix code cannot be zero";

    /**
     * Message suffix for a line number that was never supplied, from line 2378.
     */
    public static final String MSG_LINE_NUMBER_BLANK = ": Line number code must be supplied.";

    /**
     * Message suffix for a line number that is not four digits, from line 2396.
     */
    public static final String MSG_LINE_NUMBER_NOT_FOUR_DIGITS =
            ": Line number code must be A 4 digit number.";

    /**
     * Message suffix for an all-zero line number, from line 2410 of the baseline.
     */
    public static final String MSG_LINE_NUMBER_ZERO = ": Line number code cannot be zero";

    /**
     * Declared width of an area code, three characters.
     *
     * <p>Assumptions: {@code app/cpy/CSLKPCDY.cpy} line 24 spells this {@code PIC XXX}, the legacy
     * short form, and {@code app/cbl/COACTUPC.cbl} line 87 spells the same width {@code PIC X(3)}.
     * The two spellings declare one width and neither is a defect.</p>
     */
    public static final int AREA_CODE_WIDTH = 3;

    /**
     * Declared width of a telephone prefix, three characters, from line 92 of the program.
     */
    public static final int PHONE_PREFIX_WIDTH = 3;

    /**
     * Declared width of a telephone line number, four characters, from line 97 of the program.
     */
    public static final int PHONE_LINE_NUMBER_WIDTH = 4;

    /**
     * Declared width of a state code, two characters.
     *
     * <p>Assumptions: declared twice and identically, as {@code PIC X(2)} at
     * {@code app/cpy/CSLKPCDY.cpy} line 1012 and as {@code PIC X(02)} at
     * {@code app/cpy/CVCUS01Y.cpy} line 12.</p>
     */
    public static final int STATE_CODE_WIDTH = 2;

    /**
     * Declared width of a postal code, ten characters, from line 14 of the customer record.
     */
    public static final int ZIP_CODE_WIDTH = 10;

    /**
     * Number of leading postal digits that take part in the state pairing, two.
     *
     * <p>Assumptions: this is not a chosen prefix length. {@code app/cbl/COACTUPC.cbl} line 2538
     * applies the reference modification {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)}, taking the first two
     * characters and no more, and the two of them alongside a two-character state code fill the
     * four-character field of {@code app/cpy/CSLKPCDY.cpy} line 1072 exactly.</p>
     */
    public static final int ZIP_PREFIX_DIGITS = 2;

    /**
     * Combined width of a state code and its two leading postal digits, four characters.
     *
     * <p>Assumptions: this is the declared width of the field the allow-list hangs on,
     * {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4).} at {@code app/cpy/CSLKPCDY.cpy} line 1072, whose
     * condition name follows at line 1073 with literals such as {@code 'AA34'} at line 1074 and
     * {@code 'AE90'} at line 1075. It is deliberately the sum of the two parts and not a third
     * independent number.</p>
     */
    public static final int STATE_ZIP_PREFIX_WIDTH = STATE_CODE_WIDTH + ZIP_PREFIX_DIGITS;

    /**
     * Declared width of a stored telephone number, fifteen characters, from line 82 of the program.
     *
     * <p>Assumptions: thirteen of the fifteen are used. {@code app/cbl/COACTUPC.cbl} lines 82 to 100
     * lay the field out as a one-character opening bracket, the three-character area code, a
     * one-character closing bracket, the three-character prefix, a one-character separator, the
     * four-character line number and a two-character remainder, and the comments at lines 86, 91 and
     * 96 name the three punctuation characters. The program's own note at lines 2227 and 2228 shows
     * the shape {@code (999)999-9999} above a thirteen-position ruler. Line 2227 says "date" where it
     * means the telephone number; the wording is not carried forward and carries no meaning.</p>
     */
    public static final int STORED_PHONE_WIDTH = 15;

    /**
     * Index at which the area code begins inside a stored telephone number, one.
     *
     * <p>Assumptions: position zero holds the opening bracket declared as filler at
     * {@code app/cbl/COACTUPC.cbl} line 85, so the area code occupies positions one to three.</p>
     */
    public static final int AREA_CODE_OFFSET = 1;

    /**
     * Index at which the telephone prefix begins inside a stored telephone number, five.
     *
     * <p>Assumptions: the closing bracket declared as filler at {@code app/cbl/COACTUPC.cbl} line 90
     * occupies position four, so the prefix occupies positions five to seven.</p>
     */
    public static final int PHONE_PREFIX_OFFSET = AREA_CODE_OFFSET + AREA_CODE_WIDTH + 1;

    /**
     * Index at which the line number begins inside a stored telephone number, nine.
     *
     * <p>Assumptions: the separator declared as filler at {@code app/cbl/COACTUPC.cbl} line 95
     * occupies position eight, so the line number occupies positions nine to twelve and the
     * two-character remainder of line 100 occupies positions thirteen and fourteen.</p>
     */
    public static final int PHONE_LINE_NUMBER_OFFSET = PHONE_PREFIX_OFFSET + PHONE_PREFIX_WIDTH + 1;

    /**
     * Separator between a telephone field identity and one of its three part identities, a full stop.
     *
     * <p>Assumptions: the baseline groups the three part markers under one per-field group and moves
     * that whole group to a per-telephone destination, at {@code app/cbl/COACTUPC.cbl} lines 1637 and
     * 1638 for the first number and lines 1645 and 1646 for the second. One identity with three
     * suffixed parts reproduces that grouping, which is why the three part identities are derived
     * from the caller's single field identity rather than passed as three further arguments.</p>
     */
    public static final String FIELD_PART_SEPARATOR = ".";

    /**
     * Identity suffix for the area-code part of a telephone field.
     *
     * <p>Assumptions: named for the marker it replaces, {@code FLG-EDIT-US-PHONEA-*} declared at
     * {@code app/cbl/COACTUPC.cbl} lines 105 to 107.</p>
     */
    public static final String FIELD_SUFFIX_AREA_CODE = FIELD_PART_SEPARATOR + "areaCode";

    /**
     * Identity suffix for the prefix part of a telephone field.
     *
     * <p>Assumptions: named for {@code FLG-EDIT-US-PHONEB-*} declared at
     * {@code app/cbl/COACTUPC.cbl} lines 109 to 111.</p>
     */
    public static final String FIELD_SUFFIX_PHONE_PREFIX = FIELD_PART_SEPARATOR + "prefix";

    /**
     * Identity suffix for the line-number part of a telephone field.
     *
     * <p>Assumptions: named for {@code FLG-EDIT-US-PHONEC-*} declared at
     * {@code app/cbl/COACTUPC.cbl} lines 113 to 115.</p>
     */
    public static final String FIELD_SUFFIX_PHONE_LINE_NUMBER = FIELD_PART_SEPARATOR + "lineNumber";

    /**
     * The digit the all-zero tests compare every position against.
     *
     * <p>Assumptions: the baseline performs those tests through numeric redefinitions of the three
     * character parts, declared at {@code app/cbl/COACTUPC.cbl} lines 88 and 89, 93 and 94, and 98
     * and 99, and compares each against zero at lines 2280, 2351 and 2404. A part reaches that
     * comparison only after the digits test above it has passed, so every position is known to hold a
     * digit and "the number is zero" and "every digit is zero" are the same question.</p>
     */
    private static final char ZERO_DIGIT = '0';

    /**
     * The pad character a fixed-width alphanumeric field is filled with.
     *
     * <p>Assumptions: this is the fill a COBOL move of a shorter alphanumeric value into a longer
     * field applies, and it is the same character {@link FieldValidationFlag#ABSENT_INPUT_SPACE}
     * names as one of the two bytes that mark a field absent, so a normalised short value stays
     * recognisable as never supplied.</p>
     */
    private static final char PAD_CHARACTER = ' ';

    /**
     * The digit an all-digits test accepts as its upper bound.
     *
     * <p>Assumptions: the bound is the ASCII digit nine rather than a general category test, for the
     * reason recorded on {@link #isAllDigits(String)}.</p>
     */
    private static final char NINE_DIGIT = '9';

    // WHY : Refactoring Rationale: the allow-lists arrive through an injected contract, where the
    //       baseline reached its equivalents by static linkage and shared working storage. There is
    //       no field on this class that a rule below can read other than this one, so every rule is
    //       exercisable against a hand-written stand-in with no server and no network attached --
    //       which is precisely what app/cbl/COACTUPC.cbl cannot offer, because it draws the literals
    //       in textually at line 602 and there is no seam at which a test could substitute them.
    private final ReferenceAddressLookup lookup;

    /**
     * Creates the service over the contract that reads the three address allow-lists.
     *
     * @param lookup the contract for reading the allow-lists, whose obligations are stated on
     *     {@link ReferenceAddressLookup}; must not be {@code null}
     * @throws NullPointerException if {@code lookup} is {@code null}, because every rule this class
     *     carries consults it and a service constructed without one could only report every address
     *     as acceptable
     */
    public AddressValidationService(ReferenceAddressLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
    }

    /**
     * Reports a candidate area code's standing against all three allow-lists of the copybook.
     *
     * <p>This is the reading half of the telephone target, kept separate from the deciding half in
     * {@link #validateAreaCode(String, String, String)} because the baseline asks two different
     * questions of one field. All three condition names of {@code app/cpy/CSLKPCDY.cpy} line 24 are
     * answered here -- the broad list at line 30, the general-purpose list at line 521 and the
     * easily-recognisable list at line 931 -- so a caller can tell an unassigned code from an assigned
     * one that the account update path nonetheless refuses.</p>
     *
     * <p>Assumptions: the candidate is trimmed before it is looked up, which is what
     * {@code app/cbl/COACTUPC.cbl} lines 2296 and 2297 do with
     * {@code MOVE FUNCTION TRIM (WS-EDIT-US-PHONE-NUMA)} before the test at line 2298. On the path
     * through {@link #validateAreaCode(String, String, String)} the trim cannot change the value,
     * because the digits test at line 2264 has already established that all three positions hold
     * digits; it matters on this method, which is reachable directly with a padded value.</p>
     *
     * @param areaCode the candidate area code, which may be padded, may be shorter or longer than
     *     {@link #AREA_CODE_WIDTH} and may be {@code null} when nothing was supplied for it
     * @return the candidate's standing against the three allow-lists, reporting no assignment when it
     *     appears in none of them
     * @throws RuntimeException if the owning service cannot be reached or answers unusably, as
     *     propagated unchanged from {@link ReferenceAddressLookup#findAreaCodeClass(String)}; a
     *     transport failure is deliberately not converted into an unassigned answer, because that
     *     would report a valid area code as invalid
     */
    public AreaCodeClassification classifyAreaCode(String areaCode) {
        String candidate = areaCode == null ? "" : areaCode.trim();

        // WHY : Trade-offs: an empty candidate is answered without consulting the owning service.
        //       The compromise is one branch that duplicates what an absent row would have reported
        //       anyway; what it buys is that the optional-telephone path below, which reaches this
        //       class with three blank parts on every submission that leaves a number empty, costs no
        //       call at all. Every literal in the list at app/cpy/CSLKPCDY.cpy line 30 is three
        //       characters wide, so no empty candidate can match one and the two answers agree.
        if (candidate.isEmpty()) {
            return AreaCodeClassification.unassigned();
        }

        return lookup.findAreaCodeClass(candidate)
                .map(AreaCodeClassification::of)
                .orElseGet(AreaCodeClassification::unassigned);
    }

    /**
     * Validates one telephone area code, as {@code EDIT-AREA-CODE} does at lines 2246 to 2314.
     *
     * <p>The four checks are applied in the baseline's order, and the first to fail is the only one
     * reported, because each failing branch in {@code app/cbl/COACTUPC.cbl} leaves the paragraph
     * immediately: never supplied at lines 2247 and 2248, not three digits at line 2264, all zero at
     * line 2280, and outside the general-purpose allow-list at line 2298.</p>
     *
     * <p>Assumptions: the first check reports the never-supplied marker and the other three report the
     * unacceptable-value marker, which is the distinction the baseline draws with two different
     * condition names over one marker field -- {@code FLG-EDIT-US-PHONEA-BLANK} for the first and
     * {@code FLG-EDIT-US-PHONEA-NOT-OK} for the rest, declared at
     * {@code app/cbl/COACTUPC.cbl} lines 105 to 107. Those declarations spell the three states with
     * the low-value byte, the digit zero and the letter B, which are exactly the canonical bytes of
     * {@link FieldValidationFlag}, so the mapping onto that type is an identity rather than an
     * interpretation.</p>
     *
     * <p>Assumptions: the allow-list this method gates on is the general-purpose list at
     * {@code app/cpy/CSLKPCDY.cpy} line 521, not the broader list at line 30. The gate is read from
     * {@code app/cbl/COACTUPC.cbl} line 2298, which names that condition, and the wording it emits at
     * line 2306 names the same narrower list. An assigned but easily-recognisable code is therefore
     * refused here while {@link #classifyAreaCode(String)} still reports it as assigned.</p>
     *
     * @param areaCode the candidate area code as it stands in its fixed-width field, which may be
     *     padded, may be blank and may be {@code null}
     * @param fieldName the identity to report the field under, as the client matches it; must not be
     *     {@code null}, empty or entirely whitespace
     * @param label the field label the message is prefixed with, the counterpart of the values set at
     *     {@code app/cbl/COACTUPC.cbl} lines 1632 and 1640; must not be {@code null}
     * @return the outcome, carrying at most one entry because the baseline paragraph leaves on its
     *     first failure
     * @throws NullPointerException if {@code fieldName} or {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is empty or entirely whitespace, as
     *     propagated from the per-field entry construction in {@code common-lib}
     * @throws RuntimeException if the owning service cannot be reached, as propagated unchanged from
     *     {@link ReferenceAddressLookup#findAreaCodeClass(String)}
     */
    public AddressValidationResult validateAreaCode(String areaCode, String fieldName, String label) {
        Objects.requireNonNull(label, "label must not be null");

        String candidate = normaliseToWidth(areaCode, AREA_CODE_WIDTH);

        if (FieldValidationFlag.isNeverSupplied(candidate)) {
            return singleError(
                    fieldName, FieldValidationFlag.BLANK, prefixedMessage(label, MSG_AREA_CODE_BLANK));
        }

        if (!isAllDigits(candidate)) {
            return singleError(
                    fieldName,
                    FieldValidationFlag.NOT_OK,
                    prefixedMessage(label, MSG_AREA_CODE_NOT_THREE_DIGITS));
        }

        if (isAllZeroDigits(candidate)) {
            return singleError(
                    fieldName, FieldValidationFlag.NOT_OK, prefixedMessage(label, MSG_AREA_CODE_ZERO));
        }

        // WHY : Assumptions: the general-purpose component is read rather than the assignment
        //       component, because app/cbl/COACTUPC.cbl line 2298 tests VALID-GENERAL-PURP-CODE. The
        //       consequence under the alternative is not a refused edit but an accepted one: reading
        //       assignment instead would admit the 80 easily-recognisable codes of
        //       app/cpy/CSLKPCDY.cpy line 931, which the baseline refuses, while still emitting the
        //       line 2306 wording that says those codes are not accepted.
        if (!classifyAreaCode(candidate).generalPurpose()) {
            return singleError(
                    fieldName,
                    FieldValidationFlag.NOT_OK,
                    prefixedMessage(label, MSG_AREA_CODE_NOT_GENERAL_PURPOSE));
        }

        return AddressValidationResult.valid();
    }

    /**
     * Validates one state code, as {@code 1270-EDIT-US-STATE-CD} does at lines 2493 to 2511.
     *
     * <p>The paragraph moves the candidate into the two-character field of
     * {@code app/cpy/CSLKPCDY.cpy} line 1012 at {@code app/cbl/COACTUPC.cbl} line 2494, tests the
     * allow-list of line 1013 at line 2495, and on failure marks the field at line 2499 and composes
     * the message at lines 2502 and 2503.</p>
     *
     * <p>Assumptions: a failure here is always the unacceptable-value marker and never the
     * never-supplied one, so a blank state code is reported as an unacceptable value. That is what
     * the paragraph does -- line 2499 sets {@code FLG-STATE-NOT-OK} on every failing path and the
     * paragraph has no blank branch at all -- because whether the field was supplied is settled
     * earlier, by the mandatory-and-alphabetic edit the program runs over the same value at
     * {@code app/cbl/COACTUPC.cbl} lines 1593 to 1595. Reporting a blank as never-supplied here would
     * mark the field twice for one omission.</p>
     *
     * @param stateCode the candidate state code as it stands in its fixed-width field, which may be
     *     padded, may be blank and may be {@code null}
     * @param fieldName the identity to report the field under; must not be {@code null}, empty or
     *     entirely whitespace
     * @param label the field label the message is prefixed with, the counterpart of the value set at
     *     {@code app/cbl/COACTUPC.cbl} line 1592; must not be {@code null}
     * @return the outcome, carrying one entry when the code is outside the allow-list and none when it
     *     is inside it
     * @throws NullPointerException if {@code fieldName} or {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is empty or entirely whitespace, as
     *     propagated from the per-field entry construction in {@code common-lib}
     * @throws RuntimeException if the owning service cannot be reached, as propagated unchanged from
     *     {@link ReferenceAddressLookup#stateCodeExists(String)}
     */
    public AddressValidationResult validateStateCode(String stateCode, String fieldName, String label) {
        Objects.requireNonNull(label, "label must not be null");

        String candidate = normaliseToWidth(stateCode, STATE_CODE_WIDTH);

        if (lookup.stateCodeExists(candidate)) {
            return AddressValidationResult.valid();
        }

        return singleError(
                fieldName, FieldValidationFlag.NOT_OK, prefixedMessage(label, MSG_STATE_CODE_INVALID));
    }

    /**
     * Validates a state against the leading digits of its postal code, as lines 2536 to 2558 do.
     *
     * <p>{@code 1280-EDIT-US-STATE-ZIP-CD} builds a four-character candidate at
     * {@code app/cbl/COACTUPC.cbl} lines 2537 to 2540 and tests the allow-list of
     * {@code app/cpy/CSLKPCDY.cpy} line 1073 at line 2542.</p>
     *
     * <p>Assumptions: exactly four characters take part, and this is read from the program rather
     * than chosen. Line 2538 applies the reference modification
     * {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)}, so only the first two postal characters are used, and
     * together with the two-character state code they fill the field of line 1072 exactly. The
     * remaining postal characters are not validated anywhere: the copybook declares them at line 1314
     * as {@code 02 LAST-3-OF-ZIP PIC X(3).} and hangs no condition name on that field, so the absence
     * of a rule for them is the baseline's own and is recorded here so that a later reader does not
     * take it for an oversight and supply one.</p>
     *
     * <p>Refactoring Rationale: one failure is reported against two fields, which replaces a paragraph
     * that sets two markers for one condition. Lines 2546 and 2547 set {@code FLG-STATE-NOT-OK} and
     * {@code FLG-ZIPCODE-NOT-OK} together, declared at lines 304 to 306 and 308 to 310, so the
     * baseline highlights both boxes on the screen. Reporting a single entry would highlight one box
     * and leave the operator to guess which half of the pairing to change, in a check whose whole
     * subject is that the two halves disagree. Both entries carry the same wording, because the
     * paragraph composes exactly one message at line 2550.</p>
     *
     * <p>Assumptions: this is a cross-field check with a precondition the caller owns, and the
     * precondition is not enforced here. The program runs the paragraph only inside the guard at
     * {@code app/cbl/COACTUPC.cbl} lines 1665 and 1666, which requires both fields to have passed
     * their own edits first, under the comment at line 1664 announcing the cross-field edits. That
     * guard belongs to the calling paragraph rather than to this one, and it is left there so that one
     * baseline paragraph maps to one method and the traceability matrix can cite the pair. A caller
     * invoking this method on a state code that has already failed
     * {@link #validateStateCode(String, String, String)} would report that field twice.</p>
     *
     * @param stateCode the candidate state code as it stands in its fixed-width field, which may be
     *     padded, may be blank and may be {@code null}
     * @param zipCode the candidate postal code as it stands in its fixed-width field, of which only
     *     the first {@link #ZIP_PREFIX_DIGITS} characters are read; may be padded, blank or
     *     {@code null}
     * @param stateFieldName the identity to report the state field under; must not be {@code null},
     *     empty or entirely whitespace
     * @param zipFieldName the identity to report the postal field under; must not be {@code null},
     *     empty or entirely whitespace
     * @return the outcome, carrying two entries when the pairing is outside the allow-list and none
     *     when it is inside it
     * @throws NullPointerException if {@code stateFieldName} or {@code zipFieldName} is {@code null}
     * @throws IllegalArgumentException if either field identity is empty or entirely whitespace, as
     *     propagated from the per-field entry construction in {@code common-lib}
     * @throws RuntimeException if the owning service cannot be reached, as propagated unchanged from
     *     {@link ReferenceAddressLookup#stateZipPrefixExists(String)}
     */
    public AddressValidationResult validateStateZipCombination(
            String stateCode, String zipCode, String stateFieldName, String zipFieldName) {

        // WHY : Assumptions: the two halves are concatenated in this order and at these widths
        //       because app/cbl/COACTUPC.cbl lines 2537 to 2540 concatenate them that way with
        //       DELIMITED BY SIZE, which appends each operand at its declared width and pads
        //       nothing. Normalising each half first is what makes the join total: a short postal
        //       value would otherwise leave fewer than two characters to take, and the widths would
        //       no longer sum to the four the allow-list literals are written at.
        String candidate = normaliseToWidth(stateCode, STATE_CODE_WIDTH)
                + normaliseToWidth(zipCode, ZIP_CODE_WIDTH).substring(0, ZIP_PREFIX_DIGITS);

        if (lookup.stateZipPrefixExists(candidate)) {
            return AddressValidationResult.valid();
        }

        List<ApiError.FieldError> errors = new ArrayList<>(2);
        appendError(
                errors,
                stateFieldName,
                FieldValidationFlag.NOT_OK,
                MSG_STATE_ZIP_COMBINATION_INVALID);
        appendError(
                errors, zipFieldName, FieldValidationFlag.NOT_OK, MSG_STATE_ZIP_COMBINATION_INVALID);

        return new AddressValidationResult(errors, MSG_STATE_ZIP_COMBINATION_INVALID);
    }

    /**
     * Validates a stored telephone number, as {@code 1260-EDIT-US-PHONE-NUM} does at lines 2225 to
     * 2427.
     *
     * <p>The paragraph splits the stored fifteen-character value into an area code, a prefix and a
     * line number by position, using the layout declared at {@code app/cbl/COACTUPC.cbl} lines 82 to
     * 100, and edits each part in its own sub-paragraph at lines 2246, 2316 and 2370.</p>
     *
     * <p>Assumptions: a telephone number is optional, so an entirely blank one is acceptable and is
     * reported without any entry. The program says so in its own comment at line 2233 and implements
     * it at lines 2234 to 2241, where all three parts being blank sets the valid marker and leaves the
     * paragraph before any part is edited.</p>
     *
     * <p>Refactoring Rationale: the three parts accumulate their failures instead of stopping at the
     * first, and this is the one validator in this class that behaves that way. Every failing branch
     * of the area-code sub-paragraph transfers to the PREFIX sub-paragraph rather than to the exit --
     * at {@code app/cbl/COACTUPC.cbl} lines 2259, 2277 and 2291 -- and the prefix branches transfer
     * onward to the line-number sub-paragraph at lines 2330, 2348 and 2362. A caller that stopped at
     * the first failing part would ask the operator to correct one part of a telephone number per
     * submission where the baseline reports every part that is wrong at once. Within a part the
     * behaviour is the opposite and is preserved as such, so the outcome carries at most one entry per
     * part and at most three in total.</p>
     *
     * <p>Trade-offs: the blank test on the third part reads the third part, and the baseline's third
     * clause does not. At {@code app/cbl/COACTUPC.cbl} lines 2234 to 2239 the test is three
     * parenthesised clauses, each meant to ask whether one part is blank; the first reads the area
     * code, the second the prefix, and the third pairs a check of the AREA CODE against spaces with a
     * check of the LINE NUMBER against the low-value byte. The baseline does that; this method tests
     * the line number against both spellings; the divergence is recorded in
     * {@code docs/architecture/cobol-to-service-traceability.md}. The compromise accepted is a
     * departure from the baseline on one input shape -- a number whose area code and prefix are blank
     * and whose line number is space-filled rather than low-value-filled, which the baseline treats as
     * a supplied number and then reports three failures for. Carrying the clause across as written was
     * the alternative and was rejected, because it makes the optional-number path depend on which of
     * the two interchangeable pad bytes a client happens to send, and
     * {@link FieldValidationFlag#isNeverSupplied(String)} treats those two bytes alike everywhere else
     * in this migration.</p>
     *
     * @param storedPhoneNumber the stored telephone number in the layout of lines 82 to 100, which may
     *     be padded, shorter, longer, blank or {@code null}
     * @param fieldName the identity of the telephone field, from which the three part identities are
     *     derived by the suffixes {@link #FIELD_SUFFIX_AREA_CODE},
     *     {@link #FIELD_SUFFIX_PHONE_PREFIX} and {@link #FIELD_SUFFIX_PHONE_LINE_NUMBER}; must not be
     *     {@code null}, empty or entirely whitespace
     * @param label the field label every message is prefixed with, the counterpart of the values set
     *     at {@code app/cbl/COACTUPC.cbl} lines 1632 and 1640; must not be {@code null}
     * @return the outcome, carrying no entry when the number is entirely blank or wholly acceptable,
     *     and otherwise one entry per failing part in area-code, prefix, line-number order
     * @throws NullPointerException if {@code fieldName} or {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is empty or entirely whitespace, as
     *     propagated from the per-field entry construction in {@code common-lib}
     * @throws RuntimeException if the owning service cannot be reached, as propagated unchanged from
     *     {@link ReferenceAddressLookup#findAreaCodeClass(String)}
     */
    public AddressValidationResult validateUsPhoneNumber(
            String storedPhoneNumber, String fieldName, String label) {

        Objects.requireNonNull(fieldName, "fieldName must not be null");
        Objects.requireNonNull(label, "label must not be null");

        String stored = normaliseToWidth(storedPhoneNumber, STORED_PHONE_WIDTH);
        String areaCode = partAt(stored, AREA_CODE_OFFSET, AREA_CODE_WIDTH);
        String phonePrefix = partAt(stored, PHONE_PREFIX_OFFSET, PHONE_PREFIX_WIDTH);
        String lineNumber = partAt(stored, PHONE_LINE_NUMBER_OFFSET, PHONE_LINE_NUMBER_WIDTH);

        // WHY : Trade-offs: the third clause reads the line number where the baseline's third clause
        //       at app/cbl/COACTUPC.cbl lines 2238 and 2239 reads the AREA CODE against spaces. The
        //       reasoning, the one input shape on which the two answers differ, and the alternative
        //       that was rejected are set out in full on this method, alongside lines 2234 to 2239.
        if (FieldValidationFlag.isNeverSupplied(areaCode)
                && FieldValidationFlag.isNeverSupplied(phonePrefix)
                && FieldValidationFlag.isNeverSupplied(lineNumber)) {
            return AddressValidationResult.valid();
        }

        // WHY : Refactoring Rationale: all three parts are evaluated before anything is returned,
        //       rather than returning as soon as one fails. That is what the transfers at
        //       app/cbl/COACTUPC.cbl lines 2259, 2277, 2291, 2330, 2348 and 2362 do, every one of
        //       them naming the NEXT sub-paragraph rather than the exit. Returning early would hide
        //       a wrong prefix behind a wrong area code, so an operator correcting a number would
        //       need one submission per wrong part.
        return mergeInOrder(List.of(
                validateAreaCode(areaCode, fieldName + FIELD_SUFFIX_AREA_CODE, label),
                validateUsPhonePrefix(phonePrefix, fieldName + FIELD_SUFFIX_PHONE_PREFIX, label),
                validateUsPhoneLineNumber(
                        lineNumber, fieldName + FIELD_SUFFIX_PHONE_LINE_NUMBER, label)));
    }

    /**
     * Validates a telephone prefix, as {@code EDIT-US-PHONE-PREFIX} does at lines 2316 to 2367.
     *
     * <p>Three checks in the baseline's order, each leaving the sub-paragraph on failure: never
     * supplied at {@code app/cbl/COACTUPC.cbl} lines 2317 and 2318, not three digits at line 2335,
     * and all zero at line 2351. There is no allow-list for this part, which is why it has three
     * checks where the area code has four.</p>
     *
     * <p>Assumptions: the markers are the prefix-specific ones declared at
     * {@code app/cbl/COACTUPC.cbl} lines 109 to 111, spelling the same three states with the same
     * three canonical bytes as the area-code markers do, so the never-supplied case reports
     * {@link FieldValidationFlag#BLANK} and the other two report
     * {@link FieldValidationFlag#NOT_OK}.</p>
     *
     * @param phonePrefix the prefix part as extracted from the stored number, always exactly
     *     {@link #PHONE_PREFIX_WIDTH} characters when called from
     *     {@link #validateUsPhoneNumber(String, String, String)}
     * @param fieldName the identity to report the part under, already suffixed by the caller; must not
     *     be {@code null}, empty or entirely whitespace
     * @param label the field label the message is prefixed with; must not be {@code null}
     * @return the outcome, carrying at most one entry because the sub-paragraph leaves on its first
     *     failure
     * @throws NullPointerException if {@code fieldName} or {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is empty or entirely whitespace, as
     *     propagated from the per-field entry construction in {@code common-lib}
     */
    private static AddressValidationResult validateUsPhonePrefix(
            String phonePrefix, String fieldName, String label) {

        if (FieldValidationFlag.isNeverSupplied(phonePrefix)) {
            return singleError(
                    fieldName, FieldValidationFlag.BLANK, prefixedMessage(label, MSG_PREFIX_BLANK));
        }

        if (!isAllDigits(phonePrefix)) {
            return singleError(
                    fieldName,
                    FieldValidationFlag.NOT_OK,
                    prefixedMessage(label, MSG_PREFIX_NOT_THREE_DIGITS));
        }

        if (isAllZeroDigits(phonePrefix)) {
            return singleError(
                    fieldName, FieldValidationFlag.NOT_OK, prefixedMessage(label, MSG_PREFIX_ZERO));
        }

        return AddressValidationResult.valid();
    }

    /**
     * Validates a telephone line number, as {@code EDIT-US-PHONE-LINENUM} does at lines 2370 to 2421.
     *
     * <p>The same three checks in the same order as the prefix, over a four-character part: never
     * supplied at {@code app/cbl/COACTUPC.cbl} lines 2371 and 2372, not four digits at line 2388, and
     * all zero at line 2404. Its markers are declared at lines 113 to 115.</p>
     *
     * <p>Assumptions: this part is four characters wide where the other two are three, declared
     * {@code PIC X(4)} at {@code app/cbl/COACTUPC.cbl} line 97, and its message says four digits
     * accordingly at line 2396. The width is not shared with the other parts for that reason.</p>
     *
     * @param lineNumber the line-number part as extracted from the stored number, always exactly
     *     {@link #PHONE_LINE_NUMBER_WIDTH} characters when called from
     *     {@link #validateUsPhoneNumber(String, String, String)}
     * @param fieldName the identity to report the part under, already suffixed by the caller; must not
     *     be {@code null}, empty or entirely whitespace
     * @param label the field label the message is prefixed with; must not be {@code null}
     * @return the outcome, carrying at most one entry because the sub-paragraph leaves on its first
     *     failure
     * @throws NullPointerException if {@code fieldName} or {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is empty or entirely whitespace, as
     *     propagated from the per-field entry construction in {@code common-lib}
     */
    private static AddressValidationResult validateUsPhoneLineNumber(
            String lineNumber, String fieldName, String label) {

        if (FieldValidationFlag.isNeverSupplied(lineNumber)) {
            return singleError(
                    fieldName,
                    FieldValidationFlag.BLANK,
                    prefixedMessage(label, MSG_LINE_NUMBER_BLANK));
        }

        if (!isAllDigits(lineNumber)) {
            return singleError(
                    fieldName,
                    FieldValidationFlag.NOT_OK,
                    prefixedMessage(label, MSG_LINE_NUMBER_NOT_FOUR_DIGITS));
        }

        if (isAllZeroDigits(lineNumber)) {
            return singleError(
                    fieldName,
                    FieldValidationFlag.NOT_OK,
                    prefixedMessage(label, MSG_LINE_NUMBER_ZERO));
        }

        return AddressValidationResult.valid();
    }

    /**
     * Combines the outcomes of the three telephone parts, keeping the first message only.
     *
     * <p>Entries are concatenated in the order the parts were evaluated, which is the order the
     * baseline's transfers impose, and the aggregate message is the first one present. That is the
     * migrated form of the {@code IF WS-RETURN-MSG-OFF} guard the baseline wraps every emitting site
     * in, over the field declared at {@code app/cbl/COACTUPC.cbl} line 479 whose line 480 condition
     * name tests it for spaces.</p>
     *
     * @param parts the per-part outcomes in evaluation order; must not be {@code null} and must hold
     *     no {@code null} element
     * @return one outcome carrying every entry from every part and the first part message that was
     *     present, or a passing outcome when no part reported anything
     * @throws NullPointerException if {@code parts} is {@code null} or holds a {@code null} element
     */
    private static AddressValidationResult mergeInOrder(List<AddressValidationResult> parts) {
        Objects.requireNonNull(parts, "parts must not be null");

        List<ApiError.FieldError> errors = new ArrayList<>();
        String latched = null;

        for (AddressValidationResult part : parts) {
            Objects.requireNonNull(part, "parts must not contain a null outcome");
            errors.addAll(part.fieldErrors());

            // WHY : Refactoring Rationale: the first message present is kept and later ones are
            //       discarded, rather than the last one winning or the three being joined. The
            //       baseline's guard at app/cbl/COACTUPC.cbl line 2251 and its siblings only writes
            //       the message field while it still holds spaces, so a later failure leaves it
            //       alone. Joining the three would exceed the 75 characters that field is declared
            //       at on line 479, and taking the last would show the operator the line-number
            //       complaint while the area code was the first thing wrong.
            if (latched == null) {
                latched = part.message();
            }
        }

        return new AddressValidationResult(errors, latched);
    }

    /**
     * Builds the outcome for a single failing field.
     *
     * @param fieldName the identity to report the field under; must not be {@code null}, empty or
     *     entirely whitespace
     * @param flag the marker state to report, one of {@link FieldValidationFlag#BLANK} or
     *     {@link FieldValidationFlag#NOT_OK}
     * @param message the wording to report, already composed with any label prefix
     * @return an outcome carrying exactly one entry together with that wording as the aggregate
     *     message
     * @throws NullPointerException if {@code fieldName}, {@code flag} or {@code message} is
     *     {@code null}
     * @throws IllegalArgumentException if {@code fieldName} or {@code message} is empty or entirely
     *     whitespace, or if {@code flag} reports an acceptable field, in which case no entry is
     *     produced and the outcome would announce a failure with nothing to report
     */
    private static AddressValidationResult singleError(
            String fieldName, FieldValidationFlag flag, String message) {

        List<ApiError.FieldError> errors = new ArrayList<>(1);
        appendError(errors, fieldName, flag, message);

        // WHY : Trade-offs: an acceptable marker reaching here is caught by the outcome's own
        //       constructor rather than by a guard on this method, which makes the failure a
        //       constructor complaint one frame away from the caller instead of on this line. It is
        //       accepted because the invariant that a message and an entry travel together -- which
        //       the baseline states by setting the marker and then the message together, at
        //       app/cbl/COACTUPC.cbl lines 2499 and 2503 -- is enforced in exactly one place, and
        //       duplicating the test here would leave two places able to disagree about it.
        return new AddressValidationResult(errors, message);
    }

    /**
     * Appends one per-field entry to a list, contributing nothing for an acceptable marker.
     *
     * <p>The entry is produced by the marker itself and converted to the shape a response body
     * carries, so the two migrated contracts are used as they are defined and no third per-field type
     * exists between them.</p>
     *
     * @param sink the list to append to; must not be {@code null}
     * @param fieldName the identity to report the field under; must not be {@code null}, empty or
     *     entirely whitespace
     * @param flag the marker state, which contributes an entry only when it reports an error
     * @param message the wording to carry beside the identity; must not be {@code null}, empty or
     *     entirely whitespace
     * @throws NullPointerException if {@code sink} or {@code flag} is {@code null}, or if
     *     {@code fieldName} or {@code message} is {@code null} while {@code flag} reports an error
     * @throws IllegalArgumentException if {@code fieldName} or {@code message} is empty or entirely
     *     whitespace while {@code flag} reports an error
     */
    private static void appendError(
            List<ApiError.FieldError> sink,
            String fieldName,
            FieldValidationFlag flag,
            String message) {

        Objects.requireNonNull(sink, "sink must not be null");
        Objects.requireNonNull(flag, "flag must not be null");

        // WHY : Alternatives Considered: constructing the response-body entry directly was the
        //       shorter route and is not taken. The rule that an acceptable field contributes
        //       nothing is the baseline's own, expressed as the single disjunction at
        //       app/cpy/CSSETATY.cpy lines 18 and 19, and asking the marker for the entry keeps that
        //       rule in the one type that owns it. It also routes every entry through the checks
        //       that type already performs on the identity and the wording, so an unrenderable
        //       entry cannot be assembled here by a different route.
        flag.toFieldError(fieldName, message).map(ApiError.FieldError::from).ifPresent(sink::add);
    }

    /**
     * Composes a message from a trimmed field label and a baseline literal.
     *
     * <p>This is the migrated form of {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} followed by
     * the literal, which the baseline writes at {@code app/cbl/COACTUPC.cbl} lines 2502 and 2503 and
     * repeats at every other prefixed site. The literal already carries its own leading punctuation,
     * so nothing is inserted between the two.</p>
     *
     * @param label the field label to trim and place first; must not be {@code null}, and may be
     *     blank, in which case the message is the literal alone
     * @param literal the baseline message literal, used exactly as declared
     * @return the composed message
     * @throws NullPointerException if {@code label} or {@code literal} is {@code null}
     */
    private static String prefixedMessage(String label, String literal) {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(literal, "literal must not be null");

        // WHY : Assumptions: no separator is inserted between the two operands, because the leading
        //       colon and space are part of every prefixed literal rather than something this method
        //       supplies. Inserting one would give an operator "State : is not a valid state code"
        //       where app/cbl/COACTUPC.cbl lines 2502 and 2503 produce "State: is not a valid state
        //       code", and the one message that takes no prefix at all, at line 2550, would gain a
        //       separator it never had.
        return label.trim() + literal;
    }

    /**
     * Reproduces a COBOL alphanumeric move by bringing a value to exactly the declared width.
     *
     * <p>A move of a shorter alphanumeric value into a longer field left-justifies it and fills the
     * remainder with spaces; a longer value is truncated on the right. Doing that here is what makes
     * every positional read below total, whatever a client sent.</p>
     *
     * @param value the value as it arrived, which may be shorter, longer or {@code null}
     * @param width the declared width of the field it is being read as, which must be positive
     * @return a value of exactly {@code width} characters, space-filled on the right when the input was
     *     shorter or absent and truncated on the right when it was longer
     */
    private static String normaliseToWidth(String value, int width) {
        // WHY : Assumptions: an absent value is treated as a field of pad characters rather than
        //       rejected, because that is the state in which the baseline receives an unfilled screen
        //       field: every one of these values is declared at a fixed width, among them
        //       CUST-PHONE-NUM-1 PIC X(15) at app/cpy/CVCUS01Y.cpy line 15, so "nothing was entered"
        //       reaches a COBOL program as padding and never as an absence. Over HTTP the same field
        //       arrives absent or empty, and folding all three onto one representation is what lets
        //       FieldValidationFlag.isNeverSupplied answer the same way for all of them.
        String supplied = value == null ? "" : value;

        if (supplied.length() >= width) {
            return supplied.substring(0, width);
        }

        return supplied + String.valueOf(PAD_CHARACTER).repeat(width - supplied.length());
    }

    /**
     * Extracts one positional part of a stored telephone number.
     *
     * <p>The offsets and widths are those of the layout at {@code app/cbl/COACTUPC.cbl} lines 82 to
     * 100, where three single-character fillers separate the three data parts and a two-character
     * filler closes the field.</p>
     *
     * @param stored the stored number, already brought to exactly {@link #STORED_PHONE_WIDTH}
     *     characters by {@link #normaliseToWidth(String, int)}
     * @param offset the zero-based index at which the part begins
     * @param width the declared width of the part
     * @return the part, of exactly {@code width} characters
     * @throws IndexOutOfBoundsException if {@code offset} and {@code width} together reach past the
     *     end of {@code stored}, which can only happen if the value was not normalised first
     */
    private static String partAt(String stored, int offset, int width) {
        return stored.substring(offset, offset + width);
    }

    /**
     * Reports whether every position of a value holds a decimal digit.
     *
     * <p>This is the migrated form of the {@code IS NUMERIC} class test the baseline applies to each
     * telephone part, at {@code app/cbl/COACTUPC.cbl} lines 2264, 2335 and 2388. Those parts are
     * declared as unsigned character fields, for which the test asks exactly this.</p>
     *
     * @param value the value to inspect, never {@code null} when called from this class because every
     *     caller normalises first
     * @return {@code true} when the value is non-empty and every position holds a digit;
     *     {@code false} otherwise, including for an empty value
     */
    private static boolean isAllDigits(String value) {
        // WHY : Alternatives Considered: the platform's own digit predicate was the obvious choice and
        //       is deliberately not used. It answers true for decimal digits from every script,
        //       whereas the COBOL class test this replaces -- at app/cbl/COACTUPC.cbl lines 2264,
        //       2335 and 2388, over unsigned display fields declared at lines 87, 92 and 97 --
        //       accepts only the ten characters between zero and nine. Using it would admit a
        //       telephone part written in another script's digits, which would then pass the digits
        //       test, fail no other check, and be stored in a field the baseline can only hold
        //       western digits in.
        if (value.isEmpty()) {
            return false;
        }

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            if (character < ZERO_DIGIT || character > NINE_DIGIT) {
                return false;
            }
        }

        return true;
    }

    /**
     * Reports whether every position of a value holds the digit zero.
     *
     * <p>This is the migrated form of comparing the numeric redefinition of a telephone part against
     * zero, which the baseline does at {@code app/cbl/COACTUPC.cbl} lines 2280, 2351 and 2404 through
     * the redefinitions declared at lines 88 and 89, 93 and 94, and 98 and 99.</p>
     *
     * @param value the value to inspect, never {@code null} and known to hold digits at every position
     *     because every caller applies {@link #isAllDigits(String)} first
     * @return {@code true} when every position holds the digit zero; {@code false} on the first
     *     position that does not
     */
    private static boolean isAllZeroDigits(String value) {
        // WHY : Assumptions: the value is compared character by character rather than parsed to a
        //       number, and the two agree only because the digits test above has already run. The
        //       baseline reaches its own comparison the same way, testing the numeric redefinition
        //       only after the class test, at app/cbl/COACTUPC.cbl lines 2280, 2351 and 2404. Parsing
        //       would additionally accept a signed or space-padded value that the class test has
        //       already refused, so it would answer a question the baseline never reaches this
        //       comparison with.
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != ZERO_DIGIT) {
                return false;
            }
        }

        return true;
    }

    /**
     * The contract for reading the three address allow-lists from the owning service.
     *
     * <p>The tables behind these three questions are owned and seeded by {@code reference-service}
     * and are read across the service boundary, so this interface is the port and an adapter is the
     * only place transport belongs. Declaring it here rather than importing a client keeps this class
     * free of any type from that service, which is what the layering rules require and what
     * {@code reference-service}'s own package charter describes at lines 146 to 156 of
     * {@code services/reference-service/src/main/java/com/carddemo/reference/service/package-info.java}.</p>
     *
     * <p>Alternatives Considered: injecting a configured HTTP client into
     * {@link AddressValidationService} and issuing the three calls there was the alternative, and it
     * was rejected because it puts an endpoint, a serialisation format and a status-code vocabulary
     * inside a class whose subject is the wording of an address rule. The cost of the port is one
     * more type and one more indirection; what it buys is that every rule below is exercisable
     * against a hand-written stand-in with no server, no network and no configuration, which is the
     * property the baseline lacked because its equivalents were reached by static linkage.</p>
     *
     * <p>Obligations on an implementation, stated here because they are part of the contract and not
     * of any one caller. It must apply an explicit connect timeout and an explicit read timeout, so
     * that an unreachable or slow peer fails an address edit rather than holding a request thread. It
     * must resolve its endpoint from configuration; no address of any kind is written into this
     * source tree. It must not retry and must not interpose a circuit breaker, for the reasons set
     * out on {@link AddressValidationService}. It must be safe for concurrent use, because the
     * service that holds it is a single instance shared across request threads.</p>
     *
     * <p>Assumptions: all three questions are pure existence or classification reads with no rule
     * behind them, so none of them can fail a validation on its own -- it either answers or it
     * raises. That is the shape {@code reference-service} records for the same three tables when it
     * explains at lines 146 to 156 of its package charter that they carry no rule beyond whether a
     * code exists.</p>
     */
    public interface ReferenceAddressLookup {

        /**
         * Reports which of the two area-code classifications a candidate belongs to, if any.
         *
         * <p>One probe answers all three allow-lists of {@code app/cpy/CSLKPCDY.cpy}, because the two
         * classes partition the broad list totally and disjointly as recorded on
         * {@link AddressValidationService}. A present result means the code appears in the list at
         * line 30; {@link AreaCodeClass#GENERAL_PURPOSE} means it also appears in the list at line
         * 521; {@link AreaCodeClass#EASILY_RECOGNISABLE} means it appears instead in the list at line
         * 931.</p>
         *
         * @param areaCode the candidate area code, already trimmed of padding by the caller and
         *     expected to be {@link AddressValidationService#AREA_CODE_WIDTH} digits
         * @return the classification of that area code, or an empty optional when the code appears in
         *     none of the three lists
         * @throws RuntimeException if the owning service cannot be reached or answers unusably; the
         *     concrete type is the implementation's own and is deliberately unconstrained here,
         *     because a transport failure is not a validation outcome and must not be reported as one
         */
        Optional<AreaCodeClass> findAreaCodeClass(String areaCode);

        /**
         * Reports whether a state code appears in the allow-list at line 1013 of the copybook.
         *
         * @param stateCode the candidate state code, normalised by the caller to
         *     {@link AddressValidationService#STATE_CODE_WIDTH} characters
         * @return {@code true} when the code appears in the list; {@code false} otherwise
         * @throws RuntimeException if the owning service cannot be reached or answers unusably, on
         *     the same terms as {@link #findAreaCodeClass(String)}
         */
        boolean stateCodeExists(String stateCode);

        /**
         * Reports whether a state and postal-prefix pairing appears in the list at line 1073.
         *
         * @param stateZipPrefix the candidate pairing, normalised by the caller to
         *     {@link AddressValidationService#STATE_ZIP_PREFIX_WIDTH} characters, holding the state
         *     code followed by the two leading postal digits in that order
         * @return {@code true} when the pairing appears in the list; {@code false} otherwise
         * @throws RuntimeException if the owning service cannot be reached or answers unusably, on
         *     the same terms as {@link #findAreaCodeClass(String)}
         */
        boolean stateZipPrefixExists(String stateZipPrefix);
    }

    /**
     * Which of the two narrower area-code allow-lists an assigned area code belongs to.
     *
     * <p>Assumptions: two classes and no more, because the two narrower lists of
     * {@code app/cpy/CSLKPCDY.cpy} partition the broad one exactly. The stored form is the
     * single-character code that
     * {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} bounds at
     * line 359 to {@code 'G'} and {@code 'E'}, so this enumeration and that constraint are two
     * spellings of one closed domain and a third class would have to be added to both or to
     * neither.</p>
     */
    public enum AreaCodeClass {

        /**
         * The code appears in the general-purpose list at line 521 of the copybook.
         *
         * <p>Assumptions: this is the class the account update path requires, because
         * {@code app/cbl/COACTUPC.cbl} line 2298 tests that list and no other. There are 410 such
         * codes.</p>
         */
        GENERAL_PURPOSE,

        /**
         * The code appears in the easily-recognisable list at line 931 of the copybook.
         *
         * <p>Assumptions: these are the 80 codes of the repeated-digit and N11 shape, and they are
         * assigned area codes that the account update path nonetheless refuses, with the wording of
         * {@code app/cbl/COACTUPC.cbl} line 2306.</p>
         */
        EASILY_RECOGNISABLE;

        /**
         * The stored code for {@link #GENERAL_PURPOSE}, the letter G.
         */
        public static final char GENERAL_PURPOSE_CODE = 'G';

        /**
         * The stored code for {@link #EASILY_RECOGNISABLE}, the letter E.
         */
        public static final char EASILY_RECOGNISABLE_CODE = 'E';

        /**
         * Returns the single-character code this class is stored as.
         *
         * @return {@link #GENERAL_PURPOSE_CODE} or {@link #EASILY_RECOGNISABLE_CODE}, never any other
         *     character
         */
        public char code() {
            // WHY : Alternatives Considered: carrying the character in an instance field set from
            //       each constant's argument list was the obvious shape and is not available here.
            //       Java requires enum constants to precede every other member, so the two named
            //       constants cannot be declared above them, and an argument list naming a constant
            //       declared below is rejected as an illegal forward reference. Spelling the two
            //       characters as bare literals in the argument lists would compile at the cost of
            //       declaring each stored code twice. This switch declares each exactly once, and
            //       the compiler checks its exhaustiveness against the two-value domain bounded at
            //       line 359 of
            //       services/reference-service/src/main/resources/db/migration/V1__reference.sql, so
            //       adding a third class without deciding its stored code would fail to compile
            //       rather than return a wrong character.
            return switch (this) {
                case GENERAL_PURPOSE -> GENERAL_PURPOSE_CODE;
                case EASILY_RECOGNISABLE -> EASILY_RECOGNISABLE_CODE;
            };
        }

        /**
         * Resolves a stored single-character code to the class it denotes.
         *
         * @param code the stored classification character, expected to be
         *     {@link #GENERAL_PURPOSE_CODE} or {@link #EASILY_RECOGNISABLE_CODE}
         * @return the class that character denotes, never {@code null}
         * @throws IllegalArgumentException if {@code code} is neither of the two accepted characters,
         *     which means a row reached this service that the check constraint at line 359 of
         *     {@code V1__reference.sql} should have refused at load time
         */
        public static AreaCodeClass fromCode(char code) {
            // WHY : Assumptions: an unrecognised character is refused rather than defaulted to
            //       either class. Defaulting to the general-purpose class would accept an area code
            //       the account update path refuses at app/cbl/COACTUPC.cbl line 2298, and
            //       defaulting to the other would refuse one it accepts, so both directions silently
            //       alter an address rule on the strength of a value the check constraint at line 359
            //       of services/reference-service/src/main/resources/db/migration/V1__reference.sql
            //       says cannot exist.
            return switch (code) {
                case GENERAL_PURPOSE_CODE -> GENERAL_PURPOSE;
                case EASILY_RECOGNISABLE_CODE -> EASILY_RECOGNISABLE;
                default -> throw new IllegalArgumentException(
                        "unrecognised area code classification: U+"
                                + String.format("%04X", (int) code)
                                + "; expected '" + GENERAL_PURPOSE_CODE
                                + "' or '" + EASILY_RECOGNISABLE_CODE + "'");
            };
        }
    }

    /**
     * A candidate area code's standing against each of the three allow-lists of the copybook.
     *
     * <p>This is the reported form of consulting all three lists rather than one. The account update
     * path acts on {@link #generalPurpose()} alone, because that is the list
     * {@code app/cbl/COACTUPC.cbl} line 2298 tests, but the other two answers are what let a caller
     * tell an unassigned code from an assigned one that the update path happens to refuse.</p>
     *
     * <p>Assumptions: the three components are not independent, and the constraints between them are
     * enforced rather than merely documented. Because the two narrower lists partition the broad one,
     * an unassigned code belongs to neither narrower list and an assigned code belongs to exactly
     * one; a value reporting membership of a narrower list without being assigned, or of both narrower
     * lists at once, describes a state the copybook cannot express.</p>
     *
     * @param assigned whether the code appears in the broad list at line 30 of
     *     {@code app/cpy/CSLKPCDY.cpy}, which is the union of the two narrower lists
     * @param generalPurpose whether the code appears in the general-purpose list at line 521, which
     *     is the only one of the three the account update path tests
     * @param easilyRecognisable whether the code appears in the easily-recognisable list at line 931
     */
    public record AreaCodeClassification(
            boolean assigned, boolean generalPurpose, boolean easilyRecognisable) {

        /**
         * Rejects any combination the three allow-lists cannot produce.
         *
         * @param assigned membership of the broad list, required to be true whenever either narrower
         *     membership is true because the broad list is their union
         * @param generalPurpose membership of the general-purpose list, required not to coincide with
         *     easily-recognisable membership because the two lists are disjoint
         * @param easilyRecognisable membership of the easily-recognisable list, on the same terms
         * @throws IllegalArgumentException if a narrower membership is reported without assignment,
         *     if both narrower memberships are reported at once, or if assignment is reported with
         *     neither
         */
        public AreaCodeClassification {
            // WHY : Assumptions: the partition is total and disjoint, measured over the copybook
            //       rather than assumed -- the 410 literals of app/cpy/CSLKPCDY.cpy line 521 and the
            //       80 of line 931 sum to the 490 of line 30, share no member, and their union
            //       equals it. Both halves of that property are checked because each rules out a
            //       distinct wrong answer: without totality a caller could see an assigned code that
            //       belongs to no class and could not decide whether the update path accepts it, and
            //       without disjointness a code could report both classes, which would make the
            //       general-purpose gate depend on which component happened to be read first.
            if (generalPurpose && easilyRecognisable) {
                throw new IllegalArgumentException(
                        "an area code cannot be both general purpose and easily recognisable, "
                                + "because the two allow-lists are disjoint");
            }

            if (assigned != (generalPurpose || easilyRecognisable)) {
                throw new IllegalArgumentException(
                        "assignment must equal membership of exactly one narrower allow-list, "
                                + "because the broad list is their union");
            }
        }

        /**
         * The classification of a code that appears in none of the three allow-lists.
         *
         * @return a value reporting no assignment and neither narrower membership
         */
        public static AreaCodeClassification unassigned() {
            return new AreaCodeClassification(false, false, false);
        }

        /**
         * Builds the classification of an assigned code from the class it is stored under.
         *
         * @param areaCodeClass the class the code is stored under, never {@code null}
         * @return a value reporting assignment together with membership of that one narrower list
         * @throws NullPointerException if {@code areaCodeClass} is {@code null}, since an assigned
         *     code always carries a class and an absent one is the concern of
         *     {@link #unassigned()}
         */
        public static AreaCodeClassification of(AreaCodeClass areaCodeClass) {
            Objects.requireNonNull(areaCodeClass, "areaCodeClass must not be null");

            return new AreaCodeClassification(
                    true,
                    areaCodeClass == AreaCodeClass.GENERAL_PURPOSE,
                    areaCodeClass == AreaCodeClass.EASILY_RECOGNISABLE);
        }
    }

    /**
     * The outcome of one address validation: the fields to report and the aggregate message.
     *
     * <p>Assumptions: this is a result envelope and not a second error shape. The entries it carries
     * are {@link ApiError.FieldError} values, the per-field array type this migration already has, so
     * a caller drops them into {@link ApiError#ofFieldErrors} unchanged and no conversion or parallel
     * error vocabulary exists anywhere between this class and a response body.</p>
     *
     * <p>Refactoring Rationale: the message is nullable, and absent means the baseline's message-off
     * state rather than an empty message. The baseline holds one program-wide message field, declared
     * {@code PIC X(75)} at {@code app/cbl/COACTUPC.cbl} line 479, and gates every write to it on the
     * condition name at line 480 that tests it for spaces, so the first failure's wording survives
     * and later failures add only their field markers. A stateless service cannot keep that field, so
     * the latch becomes this component: absent when nothing failed, and otherwise the wording of the
     * first failure. {@link ApiError#latchMessage(String)} then applies it with the same first-wins
     * rule at the layer that owns the response.</p>
     *
     * <p>Assumptions: the two components move together, and the constructor enforces it. Every
     * failing path in the migrated paragraphs records at least one field and exactly one message, so
     * entries without a message would announce a failure with nothing to render at the top of the
     * screen, and a message without entries would announce one with no field to attach it to. Neither
     * shape has a baseline counterpart.</p>
     *
     * @param fieldErrors the fields to report, in the order the validation encountered them; never
     *     {@code null}, always unmodifiable, and empty exactly when the validation passed
     * @param message the aggregate wording carried verbatim from the originating baseline literal;
     *     {@code null} exactly when the validation passed
     */
    public record AddressValidationResult(List<ApiError.FieldError> fieldErrors, String message) {

        /**
         * Copies the entries defensively and rejects a result that could not describe its own outcome.
         *
         * @param fieldErrors the entries to carry, copied so that a caller retaining the list it
         *     passed cannot alter a result afterwards
         * @param message the aggregate wording, required to be present exactly when there are entries
         * @throws NullPointerException if {@code fieldErrors} is {@code null} or holds a {@code null}
         *     element
         * @throws IllegalArgumentException if entries are present without a message or a message is
         *     present without entries
         */
        public AddressValidationResult {
            // WHY : Trade-offs: the list is copied on the way in rather than trusted, which costs an
            //       allocation on every validation including the passing ones. It is accepted
            //       because the telephone path accumulates across three parts -- the transfers at
            //       app/cbl/COACTUPC.cbl lines 2259, 2277 and 2291 each naming the next
            //       sub-paragraph rather than the exit -- so an uncopied result would alias a list
            //       that is still being appended to, and a caller holding the first part's result
            //       would watch it gain entries. List.copyOf also rejects a null element, which is
            //       the check that keeps an unrenderable entry out of a response body.
            fieldErrors = List.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors must not be null"));

            if (fieldErrors.isEmpty() != (message == null)) {
                throw new IllegalArgumentException(
                        "a message and at least one field error must be present together or absent "
                                + "together, but found " + fieldErrors.size()
                                + " field errors with message " + (message == null ? "absent" : "present"));
            }
        }

        /**
         * The outcome of a validation that found nothing to report.
         *
         * @return a result carrying no entries and no message
         */
        public static AddressValidationResult valid() {
            return new AddressValidationResult(List.of(), null);
        }

        /**
         * Reports whether the validation found nothing to report.
         *
         * @return {@code true} when there are no entries and consequently no message; {@code false}
         *     when at least one field is in error
         */
        public boolean isValid() {
            return fieldErrors.isEmpty();
        }
    }
}
