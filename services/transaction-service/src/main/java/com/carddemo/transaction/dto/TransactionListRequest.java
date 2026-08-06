package com.carddemo.transaction.dto;

import com.carddemo.common.validation.FieldValidationFlag;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The request shape of the migrated transaction-list screen: an optional starting transaction
 * identifier, one opaque paging cursor, and the direction that cursor is read in.
 *
 * <p><b>Purpose.</b> This is the bound request body of the transaction-list read. A controller in
 * {@code com.carddemo.transaction.api} validates and binds it, and
 * {@code com.carddemo.transaction.service} turns it into one keyset-paginated query answered by
 * {@code com.carddemo.common.web.PageResponse}. It holds no logic beyond three derived accessors
 * that name a decision the reference program makes, and it reaches nothing: no repository, no
 * clock, no entity.
 *
 * <p>The reference program is {@code app/cbl/COTRN00C.cbl} and its symbolic map is
 * {@code app/cpy-bms/COTRN00.CPY} -- upper case on disk, extension included, so a lower-case path
 * does not resolve. Both are read and cited only. Neither is modified by this record, and where the
 * migrated behaviour differs the divergence is stated here rather than introduced silently. The
 * repository states that same discipline for its own suite at {@code tests/README.md} lines 555 and
 * 556, which encode the specification rather than redefine it.
 *
 * <h2>Positioning the browse: the three states this record can express</h2>
 *
 * <p>Assumptions: the reference program positions its browse from exactly one of three places, and
 * the three components below are chosen to express those three and nothing else.
 *
 * <p><b>One. No filter and no cursor: begin at the start of the key space.</b> Line 206 of
 * {@code app/cbl/COTRN00C.cbl} tests {@code IF TRNIDINI OF COTRN0AI = SPACES OR LOW-VALUES} and
 * line 207 answers it with {@code MOVE LOW-VALUES TO TRAN-ID}, so an unsupplied filter begins the
 * browse at the low end of the key space rather than at any remembered position.
 *
 * <p>Assumptions: {@code LOW-VALUES} and {@code SPACES} are interchangeable in the test at line 206
 * and are emphatically NOT interchangeable in the assignment at line 207, which is why both lines
 * are cited rather than only the first. As INPUT, either spelling means the field was not supplied.
 * As the START KEY, only {@code LOW-VALUES} is the low end of the collating sequence; a space
 * collates above it, so moving spaces instead would begin the browse partway up the key space and
 * silently skip every identifier that collates below a space. That is a change in observable
 * behaviour, not in representation.
 *
 * <p>Assumptions: the two-arm absence test itself is not restated here. It is already owned by
 * {@link FieldValidationFlag#isNeverSupplied(String)} in the shared kernel, whose two arms are
 * declared as {@link FieldValidationFlag#ABSENT_INPUT_LOW_VALUE} and
 * {@link FieldValidationFlag#ABSENT_INPUT_SPACE} against the same reference idiom.
 * {@link #hasTransactionIdFilter()} delegates to it, so this record and every other consumer answer
 * the question one way. Transformation rule T2 exists to prevent the alternative, and the repository
 * states the same discipline for the reference layouts at {@code tests/README.md} lines 540 to 542,
 * which resolve every record layout through one compiler include path and never duplicate one.
 *
 * <p><b>Two. A supplied filter: begin at that identifier, which must be numeric.</b> Line 209 tests
 * {@code IF TRNIDINI OF COTRN0AI IS NUMERIC} and line 210 answers it with
 * {@code MOVE TRNIDINI OF COTRN0AI TO TRAN-ID}, using the value itself as the start key. A
 * non-numeric value takes the other branch: line 212 raises the error switch, lines 213 to 215 move
 * the message, line 216 moves minus one into the field's length subfield, and line 217 sends the
 * screen back.
 *
 * <p>Assumptions: one difference in input tolerance follows from that test and is registered here
 * rather than left to be discovered. The reference test at line 209 is applied to the whole
 * sixteen-character screen field, and a COBOL comparison of that kind requires every character
 * position to be a digit, so a shorter entry that the terminal pads out does not satisfy it. This
 * record accepts one to sixteen digits and uses the value as the start key exactly as line 210 does,
 * so the start-key semantics are identical and only the tolerance for a short entry differs. The
 * baseline behaves as it behaves, the Java accepts the shorter value, and the divergence is
 * documented in the migration traceability register rather than introduced silently.
 *
 * <p><b>Three. A cursor: continue from the page the caller already holds.</b> The two paging
 * function keys each position the browse from a stored key of the current page.
 * {@code PROCESS-PF8-KEY} at line 257 moves the LAST key in at line 262, and
 * {@code PROCESS-PF7-KEY} at line 234 moves the FIRST key in at line 239. One cursor plus a
 * direction therefore covers both, which is why this record carries one cursor component where the
 * response envelope carries two positions.
 *
 * <p>Assumptions: the envelope this record pages through is
 * {@code com.carddemo.common.web.PageResponse}, and its component names are the contract, not a
 * paraphrase. Reading forward is issued from that envelope's next-cursor position, which seeks keys
 * strictly greater than it in ascending order and is normally the key of the last returned row.
 * Reading backward is issued from its previous-cursor position, which seeks keys strictly less than
 * it in descending order -- precisely what the reference read-previous verb at line 660 does. The
 * envelope keeps those two request positions apart from the two row identities it also carries,
 * because a page whose rows were all filtered away after the read has a scan position and no last
 * row.
 *
 * <p>Assumptions: forward paging resumes strictly AFTER the last returned row, never after the row
 * that merely proved a further page exists. Line 297 fills exactly ten rows, and the eleventh read
 * at line 308 is not followed by the populating paragraph -- lines 309 to 313 use it only to set the
 * more-to-come condition -- so the reference discards that row. It never enters the display array.
 * A cursor taken from the discarded row would skip it on the following page, which is why this
 * component is interpreted against the envelope's stated last-returned-row contract and against no
 * other.
 *
 * <p>Assumptions: the cursor is opaque, and opaque here means SEALED rather than merely undocumented.
 * The envelope's own cursor components are tokens produced by
 * {@code com.carddemo.common.web.CursorToken}, whose payload carries the key inside an authenticated
 * wrapper bound to the query and the subject it was issued for. A caller therefore returns a token
 * exactly as it received it, and never parses, splits, decodes, increments or reconstructs one.
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>This list is as much a part of the contract as the components are, because an absence is
 * invisible and an author who cannot see why something is missing supplies it.
 *
 * <p><b>No page-size component.</b>
 *
 * <p>Trade-offs: the reference page is pinned at exactly ten rows by line 297, whose
 * {@code PERFORM UNTIL WS-IDX >= 11} runs from the {@code MOVE 1 TO WS-IDX} at line 295. Exposing a
 * size would let a client alter paging behaviour the reference program settled, and it would do so
 * in a way no test of this record would localise, because every size returns plausible rows. The
 * compromise accepted is real and worth stating plainly: a client cannot tune the page, not even to
 * ask for fewer rows over a slow link. It is accepted because the alternative changes observable
 * behaviour rather than merely structure, and because the size is a property of the screen that
 * asked rather than of the request that positions it -- the envelope declines to carry the same
 * number for the same reason.
 *
 * <p><b>No page number, no counted starting position, no page index and no total count.</b>
 *
 * <p>Alternatives Considered: pagination by ordinal position -- a count of rows skipped from the
 * start of the ordered set -- was evaluated and rejected. It silently skips and repeats rows when
 * rows are inserted between two reads, because the count is measured against a result set that has
 * changed, whereas a key already read keeps its place in the ordering no matter what is inserted
 * around it. The concurrent insertion is attested by the reference material rather than hypothetical:
 * lines 212 to 217 of {@code app/cbl/COBIL00C.cbl} mint the next transaction identifier over six
 * statements -- move high values, start browse, read previous, end browse, move the key out, add one
 * -- holding no lock across any of them, so two payments running together can read the same maximum
 * and land in the same region of the key space.
 *
 * <p>Assumptions: the reference page ordinal is a display value and never a positioning value, which
 * is what makes its omission a simplification rather than a loss. {@code CDEMO-CT00-PAGE-NUM} is
 * incremented at lines 306 and 307 and again at lines 317 and 318, and its only other use is line
 * 324, which moves it into the map field {@code PAGENUMI PIC X(8)} declared at line 60 of
 * {@code app/cpy-bms/COTRN00.CPY}. It participates in no key comparison anywhere in the program.
 *
 * <p><b>No selection marker.</b>
 *
 * <p>Assumptions: the reference row marker is navigation and not data. Lines 185 to 187 evaluate it,
 * and on either case of the letter S line 188 moves the detail program name into the transfer field
 * and lines 192 to 195 issue the control transfer. In the target that is a client-side route change
 * to the detail route, so the marker never reaches this request at all. Its rejection message at
 * lines 198 to 200 consequently has no server-side counterpart and belongs to the user-interface
 * message catalog; note that this one message carries no trailing ellipsis, unlike almost every
 * other message on these screens.
 *
 * <p>Assumptions: two statements on that same branch are commented out in the reference source --
 * line 197 and line 202 -- so an unrecognised marker moves the message without raising the error
 * switch and without sending the screen, and control falls through to the filter test at line 206.
 * This is recorded because a reader who assumes symmetry with the numeric-failure branch, which does
 * send the screen at line 217, would look for a send that is not there. The reference behaviour is
 * what it is, the target implements a route change, and the difference is documented; nothing about
 * those two lines is treated as something to put right.
 *
 * <p><b>No re-entry marker of any kind.</b> No component here is a resubmission flag, a first-entry
 * flag, a turn counter or any equivalent of the reference re-entry discriminator.
 *
 * <p>Refactoring Rationale: the mechanism being replaced is the pseudo-conversational communication
 * area, whose discriminator {@code CDEMO-PGM-CONTEXT} is declared at line 29 of
 * {@code app/cpy/COCOM01Y.cpy} with its two condition names at lines 30 and 31. What was wrong with
 * it is not its shape but that it was storage the client handed back on the following turn, so
 * continuity across a screen turn depended on the client returning it intact. A stateless handler
 * answering with a per-field error array has no first-entry-versus-re-entry distinction left to
 * make. That matters concretely here rather than abstractly: line 20 of
 * {@code app/cpy/CSSETATY.cpy} gates the field highlight on precisely that discriminator, so error
 * presentation in the target is driven by the response body alone and never by a remembered turn
 * count.
 *
 * <p><b>No monetary component and no timestamp component.</b>
 *
 * <p>Assumptions: this is stated so that no reader concludes an absent
 * {@code com.carddemo.common.money.Money} component is an oversight. A list request positions a
 * browse; it carries no amount, no balance and no rate, so the exact-decimal money type this
 * migration requires everywhere money travels has no subject in this record. Neither of the
 * reference record's two twenty-six-character timestamps appears here either, and no accessor on
 * this record reads a clock. Both omissions are properties of a positioning request, and a component
 * of either kind appearing here later would mean the shape had stopped being one.
 *
 * <h2>Validation is declarative, and a rejection answers per field</h2>
 *
 * <p>Assumptions: {@code jakarta.validation} is on both the compile and the runtime classpath of
 * this module. It does not arrive transitively from the shared kernel, which marks the validation
 * starter optional so that a module with no web tier does not inherit one;
 * {@code services/transaction-service/pom.xml} re-declares it at compile scope for that reason. A
 * component annotated with a constraint in a module that had not re-declared it would compile and
 * then fail at run time rather than at build time.
 *
 * <p>Alternatives Considered: imperative checks inside a compact canonical constructor were
 * evaluated and rejected on three separate grounds, each concrete.
 *
 * <p>First, a constructor that throws does so during deserialisation, before the handler method is
 * entered, so the failure never reaches the advice that owns the per-field error array. The array
 * would then be empty for exactly the inputs it exists to describe, and the client would receive a
 * bare deserialisation failure naming no field.
 *
 * <p>Second, these annotations are the same source the OpenAPI 3.1 contract this service publishes
 * is generated from, so the published width and the enforced width cannot drift apart. A constructor
 * check enforces a width the contract does not advertise, which is a disagreement no build failure
 * would ever surface.
 *
 * <p>Third, a compact constructor is a node the documentation gate audits in its own right: the
 * ruleset's method-completeness check governs compact record constructors under its default token
 * set, so the constructor would need a full tag set of its own, duplicating the three type-level
 * parameter tags without adding a fact.
 *
 * <p>Assumptions: transformation rule T7 turns the reference validation switches into a structured
 * per-field error array, carried by {@code com.carddemo.common.error.ApiError} with its per-field
 * entries typed by {@link FieldValidationFlag} and produced by the shared kernel's exception advice.
 * Three properties of that mechanism bear on how a caller reads a rejection of this request, and none
 * of them is recoverable from the code.
 *
 * <p>Assumptions: the blank state is a subset of the error state rather than a third alternative to
 * it. Lines 18 and 19 of {@code app/cpy/CSSETATY.cpy} form a single disjunctive test over the
 * not-ok switch and the blank switch, so the highlight fires for either. A caller therefore asks
 * {@link FieldValidationFlag#isError()} whether the field is in error and treats blank as a
 * refinement of that answer, rather than comparing the flag to the blank state as though the two
 * were exclusive.
 *
 * <p>Assumptions: the literal asterisk marker the reference writes into a blank field is
 * presentational, and that copybook proves it by writing the two effects into two different places.
 * Line 21 moves the red attribute into the field's colour subfield at line 22, whose name ends in
 * the letter C; lines 24 and 25 move the asterisk into the field's output subfield, whose name ends
 * in the letter O. The symbolic maps declare those as two separate items. The marker is therefore
 * preserved for the blank case as a property of the error entry, reachable through
 * {@link FieldValidationFlag#screenMarker()}, and never as a character prepended to a value.
 *
 * <p>Assumptions: the per-field array is keyed by field identifier, and the reference program is why
 * rather than a preference for keyed maps. It signals which field failed by moving minus one into
 * that field's length subfield -- {@code TRNIDINL COMP PIC S9(4)} at line 61 of
 * {@code app/cpy-bms/COTRN00.CPY} -- which is how the terminal places the cursor on the offending
 * field, at line 201 on the marker branch and at line 216 on the numeric branch. The key carries
 * exactly that information across. The minus one itself is a terminal mechanism and is not a
 * component of this record.
 *
 * @param transactionIdFilter the optional identifier the browse starts from, borne as a string of at
 *     most sixteen digits; it is the target of the reference screen field
 *     {@code TRNIDINI PIC X(16)} declared at line 66 of {@code app/cpy-bms/COTRN00.CPY}, and its
 *     sixteen characters are the width of the record key {@code TRAN-ID PIC X(16)} declared at line
 *     5 of {@code app/cpy/CVTRA05Y.cpy}; null, empty or entirely blank means the field was not
 *     supplied and the browse begins at the start of the key space, per lines 206 and 207 of
 *     {@code app/cbl/COTRN00C.cbl}
 * @param cursor the opaque paging position this request continues from, echoed back exactly as the
 *     preceding {@code com.carddemo.common.web.PageResponse} supplied it and never parsed,
 *     decoded, split or incremented by a client; null, empty or entirely blank means no position was
 *     supplied, which is the opening request for the ordered set
 * @param direction the direction {@code cursor} is read in, {@link Direction#NEXT} for the
 *     ascending continuation the eighth function key drives at lines 257 to 274 of
 *     {@code app/cbl/COTRN00C.cbl} and {@link Direction#PREVIOUS} for the descending one the seventh
 *     drives at lines 234 to 252; null is admitted and resolves to
 *     {@link Direction#NEXT} through {@link #effectiveDirection()}, which is the direction the
 *     reference program takes on an entry that pressed neither key
 */
public record TransactionListRequest(
    // WHY : Assumptions: the width is not chosen. Transformation rule T1 makes the reference
    //       copybook normative, so the sixteen comes from TRNIDINI PIC X(16) at COTRN00.CPY line
    //       66 and from TRAN-ID PIC X(16) at CVTRA05Y line 5, which agree. A value longer than the
    //       key can match no key at all, so the ceiling is a property of the key space rather than
    //       a policy this record invents.
    // WHY : Trade-offs: the width and the character class are two annotations rather than one
    //       bounded pattern, so a seventeen-digit value reports the width violation specifically
    //       instead of a generic pattern failure. The cost is that a caller can receive two
    //       findings for one value; the gain is that each names the constraint it broke, which is
    //       what makes the per-field array actionable.
    // WHY : Assumptions: this message is target-only and has no reference counterpart, which is
    //       stated so that it is never audited as a string that should have been carried across
    //       character for character. The reference screen field is sixteen characters wide, so an
    //       over-length value cannot arrive at COTRN00C line 209 at all and the program has no
    //       message for a condition it cannot meet. Over HTTP the value can arrive, so the
    //       condition needs a message, and inventing one is the honest option; the alternative of
    //       reusing the numeric message would report a width failure in words that name the wrong
    //       constraint.
    @Size(max = 16, message = "Tran ID must not exceed 16 characters")
    // WHY : Assumptions: the character class is written out as the ten digits rather than as the
    //       shorthand class, because the shorthand's reach depends on a matcher flag that Bean
    //       Validation does not set, whereas the reference test at COTRN00C line 209 accepts the
    //       ten ASCII digits and nothing else. Writing the range removes the dependency.
    // WHY : Assumptions: the alternation admitting a run of spaces is required and is not slack. A
    //       bare digits pattern would reject an all-blank value, yet line 206 routes exactly that
    //       value to the start of the key space, so rejecting it would contradict the reference
    //       program on its own first branch. The empty string needs no arm of its own: the digit
    //       run already matches it.
    // WHY : Alternatives Considered: admitting a run of low-value characters as a third arm was
    //       evaluated and rejected. That spelling of absence exists because a screen field always
    //       arrives as a character field of declared width, so an unfilled position reaches the
    //       reference program as a pad byte; over HTTP the same field arrives absent or empty
    //       instead, which the digit arm already matches. Admitting control characters into a
    //       published contract buys nothing and the OpenAPI document would carry them.
    // WHY : Refactoring Rationale: the digit run is EXACT at sixteen and an earlier revision of
    //       this component admitted any number of digits. Sixteen is the whole key -- TRAN-ID
    //       PIC X(16) at line 5 of app/cpy/CVTRA05Y.cpy -- and the committed extract is
    //       zero-padded to it, so a shorter run is not a prefix of a key but a value that matches
    //       none, and the published contract declares the same exact width. The empty string keeps
    //       its own arm because line 206 routes an unfilled field to the start of the key space.
    // WHY : Alternatives Considered: admitting a run of spaces as a third arm, which an earlier
    //       revision did. Over HTTP a padded screen field has no counterpart: absence arrives as an
    //       omitted member or as the empty string, both of which the first arm matches, so the
    //       space arm described a value no client sends while widening what this component accepts.
    @Pattern(regexp = "|[0-9]{16}", message = "Tran ID must be Numeric ...")
    String transactionIdFilter,
    // WHY : Alternatives Considered: a size constraint naming the sealed token's own ceiling was
    //       evaluated and rejected. The ceiling and the token shape are owned by
    //       com.carddemo.common.web.CursorToken, and restating either here as a literal would be a
    //       second copy of one constant that could drift from the first while both went on
    //       compiling -- the drift transformation rule T2 exists to foreclose. Verification
    //       belongs where the token is opened, which is the only place holding the key that can
    //       tell a genuine token from a well-formed forgery.
    String cursor,
    // WHY : Assumptions: nullability is the reference behaviour rather than laxity. An entry that
    //       pressed neither paging key reaches the forward fill at COTRN00C line 297 without
    //       having stated a direction, so requiring one here would reject the reference program's
    //       own first request. effectiveDirection() is where that default is resolved.
    Direction direction) {

    /**
     * The direction a page is read in, being the two paging function keys of the reference screen
     * expressed as a closed pair of constants.
     *
     * <p>Assumptions: this enumeration is nested inside the request it belongs to rather than
     * declared as a file of its own, because the package charter fixes this directory's inventory at
     * eight files and names the seven records that fill it. A ninth file would be outside that list
     * and invisibly so, whereas a nested type is reached as
     * {@code TransactionListRequest.Direction} and cannot be mistaken for a shape another screen
     * shares.
     *
     * <p>Assumptions: the pair is closed at two because the reference vocabulary is closed. The
     * attention identifier is normalised into sixteen condition names over
     * {@code CCARD-AID PIC X(5)} at lines 3 to 19 of {@code app/cpy/CVCRD01Y.cpy}, each with a
     * five-character padded value, and of those sixteen exactly two drive paging: the seventh
     * function key at line 14 and the eighth at line 15. Every other key on that screen clears,
     * returns or submits, so no third direction exists to add.
     *
     * <p>Trade-offs: this type names the two directions and deliberately not the keys that produce
     * them, so there is no constant here spelling a function-key number. Key handling belongs to the
     * user interface, which binds the keys and renders them as buttons; a request that named a key
     * would make the server's contract depend on the input device that reached it. The cost is that
     * the mapping from key to direction lives in two places, the client binding and the prose
     * below; the gain is that a client with no function keys at all can page.
     */
    public enum Direction {

        /**
         * Read the page after the cursor: keys strictly greater than it, in ascending order.
         *
         * <p>Assumptions: this is the eighth function key of the reference screen, whose paragraph
         * {@code PROCESS-PF8-KEY} begins at line 257 of {@code app/cbl/COTRN00C.cbl} and positions
         * the browse from the LAST key of the page on display at line 262, before performing the
         * forward page at line 268. Its analogue is the read-next verb at line 626. This is also the
         * direction an opening request takes, which is why {@link #effectiveDirection()} resolves an
         * unstated direction to this constant and not to its companion.
         */
        NEXT("next"),

        /**
         * Read the page before the cursor: keys strictly less than it, in descending order.
         *
         * <p>Assumptions: this is the seventh function key of the reference screen, whose paragraph
         * {@code PROCESS-PF7-KEY} begins at line 234 of {@code app/cbl/COTRN00C.cbl} and positions
         * the browse from the FIRST key of the page on display at line 239, before performing the
         * backward page at line 246. Its analogue is the read-previous verb at line 660, and the
         * descending order is what that verb does rather than an ordering this migration chose.
         */
        PREVIOUS("previous");

        /** The lower-case token this constant is spelled as on the wire. */
        private final String wireValue;

        /**
         * Binds one constant to the wire token that selects it.
         *
         * @param wireValue the lower-case token published for this constant; never {@code null} and
         *     never blank, both being properties of the two literals above rather than of any input
         */
        Direction(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * Returns the token this constant is spelled as on the wire.
         *
         * @return the published lower-case token, {@code next} or {@code previous}; never
         *     {@code null}
         */
        @JsonValue
        public String wireValue() {
            return wireValue;
        }

        /**
         * Resolves a wire token to the constant it names.
         *
         * <p>Refactoring Rationale: this factory exists because the Java identifier and the wire
         * token differ in case and an earlier revision had them differ in WORD too, naming the
         * constants {@code FORWARD} and {@code BACKWARD} while the published contract and the
         * already-authored browser client both carried {@code next} and {@code previous}. A query
         * parameter is a string on both sides, so neither build could report the mismatch and it
         * would have surfaced only as a refused request. The words now agree and only the case
         * differs, which Java's constant convention forces; this method is where that one remaining
         * difference is crossed, and it is the entry point a controller binds the query parameter
         * through rather than relying on any framework converter's case policy.
         *
         * <p>Alternatively the constants could have been spelled in lower case so that the default
         * converter matched them exactly. That was rejected because a lower-case constant reads as a
         * field at every use site, and the mismatch it avoids is avoided here anyway.
         *
         * @param wireValue the token as received, which may be {@code null} when the caller stated
         *     no direction
         * @return the constant the token names, or {@code null} when {@code wireValue} is
         *     {@code null}, absence being resolved by {@link #effectiveDirection()} rather than here
         * @throws IllegalArgumentException when the token is neither published value, so that an
         *     unrecognised direction is refused rather than silently read as the default
         */
        @JsonCreator
        public static Direction fromWireValue(String wireValue) {
            if (wireValue == null) {
                return null;
            }
            for (Direction candidate : values()) {
                if (candidate.wireValue.equals(wireValue)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException(
                "direction must be \"next\" or \"previous\"; received a value of length "
                    + wireValue.length());
        }
    }

    /**
     * Reports whether a starting identifier was supplied, so that a caller can tell a filtered
     * browse from one that begins at the start of the key space.
     *
     * <p>Assumptions: the answer is delegated rather than computed, because the two-arm test at line
     * 206 of {@code app/cbl/COTRN00C.cbl} is already owned by the shared kernel and a second
     * implementation of it could disagree with the first while both went on compiling. The delegate
     * folds a null, an empty value, an all-pad value and an all-blank value into one answer, which is
     * the same set of inputs that reference branch treats as unsupplied.
     *
     * <p>Assumptions: this deliberately does not use the platform's general blank test, and the
     * difference is observable. That test would treat a tab or a line separator as blank, whereas
     * neither equals either figurative constant the reference program compares against, so such a
     * value is supplied, fails the digits constraint, and is reported to the client rather than
     * silently widening the browse to the whole key space.
     *
     * @return {@code true} when a starting identifier was supplied and the browse is to begin at it;
     *     {@code false} when none was supplied and the browse is to begin at the start of the key
     *     space, per lines 206 and 207 of {@code app/cbl/COTRN00C.cbl}
     */
    public boolean hasTransactionIdFilter() {
        // WHY : Assumptions: the sense is inverted here rather than in the delegate, because the
        //       shared kernel states the question as absence to match the reference branch it was
        //       transcribed from, while a caller of this record is positioning a browse and asks
        //       the presence question. Restating the delegate's arms locally would be the
        //       duplication rule T2 forecloses.
        return !FieldValidationFlag.isNeverSupplied(transactionIdFilter);
    }

    /**
     * Resolves the direction this request is to be read in, supplying the reference program's own
     * default when the caller stated none.
     *
     * <p>Assumptions: the default is forward because that is what the reference program does on an
     * entry that pressed neither paging key: control reaches the forward fill at line 297 of
     * {@code app/cbl/COTRN00C.cbl} directly from the filter test at lines 206 to 210, with no
     * function key involved. Defaulting to backward, or refusing the request outright, would each
     * reject the reference program's own first request for a page.
     *
     * <p>Alternatives Considered: requiring the component instead, by constraining it to be present,
     * was evaluated and rejected. It would move the same decision into every client and let two
     * clients disagree about which direction an opening request has, and the disagreement would
     * surface as pages arriving in the wrong order rather than as a build failure. Resolving it once
     * here leaves the component optional on the wire, as the published contract shows it, and
     * removes a null a caller would otherwise have to test for.
     *
     * @return the stated direction when one was supplied, otherwise {@link Direction#NEXT};
     *     never {@code null}
     */
    public Direction effectiveDirection() {
        // WHY : Assumptions: this resolves the default at the point of use rather than in a
        //       canonical constructor, so the component keeps the absent value the caller actually
        //       sent. A constructor that substituted a value would make the record report a
        //       direction that was never on the wire, which no reader of a logged request could
        //       reconcile with what the client transmitted.
        return direction == null ? Direction.NEXT : direction;
    }

    /**
     * Reports whether a paging position was supplied, so that a caller can tell a continuation from
     * the opening request for the ordered set.
     *
     * <p>Assumptions: the three spellings of an absent position are folded into one answer here
     * because the response envelope folds the same three in its own canonical constructor, which
     * normalises a null, an empty and an entirely blank token alike to a null. A caller comparing the
     * component to null alone would treat an empty token as a position and try to open it, which
     * contradicts the envelope's contract and fails at the one place that could have accepted it.
     *
     * <p>Assumptions: the answer maps one to one onto the envelope's backward-availability
     * indicator, which is stated as false for the opening query that supplied no cursor and true for
     * any page reached from a neighbouring one, and is never inferred from a page's own contents.
     * Answering the presence question positively rather than asking whether this is an opening
     * request is what makes that mapping a direct assignment instead of a negation a reader has to
     * unwind.
     *
     * <p>Refactoring Rationale: this accessor was first written as a boolean asking whether the
     * request was the opening one, and the name is revised here because the {@code is} prefix had a
     * consequence beyond style. Serialisation introspects that prefix as a JavaBeans property
     * accessor, so the type acquired a fourth wire property alongside its three components; a
     * serialised request then carried {@code "openingRequest":false}, and reading that same document
     * back failed outright because no such creator property exists. The published contract would have
     * advertised a fourth readable field no client may send, contradicting the drift argument made
     * for declarative validation above. The {@code has} prefix carries no such meaning to the
     * introspector, so the leak is removed at its source rather than masked by an annotation
     * suppressing it, and this package keeps the freedom from serialisation concerns its charter
     * describes.
     *
     * <p>Assumptions: the blank test used here is the platform's, and that is a deliberate asymmetry
     * with {@link #hasTransactionIdFilter()} rather than an inconsistency. Each component follows the
     * contract of the type that owns it: the filter follows the reference program's two figurative
     * constants, while the cursor follows the envelope's own normalisation, which is written as the
     * general blank test. Harmonising them would break whichever contract lost.
     *
     * @return {@code true} when a paging position was supplied and the query is to continue from it
     *     in the resolved direction; {@code false} when none was supplied, which is the opening
     *     request and starts from the filter or from the start of the key space
     */
    public boolean hasCursor() {
        // WHY : Assumptions: an opening request is defined by the absence of a position and not by
        //       the absence of a filter. The two are independent: the reference program's first
        //       request may carry a starting identifier and still be the first page of the set,
        //       because line 210 uses that identifier as a start key rather than as a continuation.
        return cursor != null && !cursor.isBlank();
    }
}
