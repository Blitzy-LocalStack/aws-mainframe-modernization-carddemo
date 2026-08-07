package com.carddemo.transaction.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionDetailResponse;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one stored transaction by its identifier, transcribing the reference transaction-detail
 * screen paragraph by paragraph.
 *
 * <p><b>Purpose.</b> This class holds the business rules of the migrated
 * {@code app/cbl/COTRN01C.cbl}, a 330-line CICS program whose whole job is to answer a single
 * question: given one transaction identifier, either show that transaction or say why it cannot be
 * shown. It owns the validation order, the message selection and the read; it owns nothing else.
 * The wire shape belongs to {@code com.carddemo.transaction.dto}, every representation concern of
 * the reference record format belongs to {@code com.carddemo.transaction.mapper}, the query belongs
 * to {@code com.carddemo.transaction.repository}, and the HTTP surface belongs to the API package.
 * The package charter beside this file governs all four boundaries and is cited rather than
 * restated.
 *
 * <h2>One program, one class, and what each paragraph became</h2>
 *
 * <p>The organising principle is transcription rather than redesign, so a significant paragraph
 * becomes one named method and {@code docs/architecture/cobol-to-service-traceability.md} can cite
 * the pair. The reference program's procedure division declares nine paragraphs, of which two
 * become methods here and seven have no counterpart at all. Each of the nine is listed, because a
 * paragraph left out of the pairing reads as an oversight, and each line number below was read in
 * the file:
 *
 * <ul>
 *   <li>{@code PROCESS-ENTER-KEY} at line 144 becomes {@link #viewTransaction(String)}, the one
 *       public method here.</li>
 *   <li>{@code READ-TRANSACT-FILE} at line 267 becomes the private {@code readTransactFile} below,
 *       named for it so the pair reads as a pair.</li>
 *   <li>{@code MAIN-PARA} at line 86 has no method. It is CICS task orchestration -- it decides
 *       whether this is a first turn or a later one, dispatches on the attention identifier and
 *       ends the task at lines 136 to 139 with the communication area attached. A stateless request
 *       handler replaces the whole of it.</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} at line 197 has no method. It zeroes the re-entry
 *       discriminator at line 204 and transfers control to another program at lines 205 to 208, and
 *       transformation rule T5 maps a transfer of control onto a client route change.</li>
 *   <li>{@code SEND-TRNVIEW-SCREEN} at line 213 has no method. Returning
 *       {@link TransactionDetailResponse} is what replaces the map send, and the message it moves
 *       into the screen at line 217 travels as a raised message instead.</li>
 *   <li>{@code RECEIVE-TRNVIEW-SCREEN} at line 230 and {@code POPULATE-HEADER-INFO} at line 243
 *       have no method for the same reason: one reads a terminal datastream, the other reads the
 *       clock and fills the screen banner at lines 247 to 250 and 256 to 262, both of which are
 *       client concerns.</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} at line 301 and {@code INITIALIZE-ALL-FIELDS} at line 309 have
 *       no method either, and the ruling is recorded further down under the absences rather than
 *       left to be inferred from their omission here.</li>
 * </ul>
 *
 * <h2>This class is a read path, and one measurement proves it</h2>
 *
 * <p>Assumptions: searching the whole 330-line program for the eight verbs that would make it
 * anything else -- {@code STARTBR}, {@code READNEXT}, {@code READPREV}, {@code ENDBR},
 * {@code WRITE}, {@code REWRITE}, {@code COMPUTE} and {@code SYNCPOINT} -- matches no line at all.
 * That single measurement settles four separate design questions, and it is worth stating as one
 * fact because each of the four is otherwise argued from scratch:
 *
 * <ul>
 *   <li>There is no browse, so this class carries no page envelope, no cursor and no keyset query.
 *       The module's own architectural gate additionally fails the build if any type in this package
 *       so much as depends on an ordinal-paging construct.</li>
 *   <li>There is no write, so nothing here saves, and no transaction boundary exists for a mutation
 *       to sit inside.</li>
 *   <li>There is no arithmetic, so the amount is carried and never computed. Nothing in this class
 *       adds, scales or rounds money.</li>
 *   <li>There is no commit point, so transformation rule T5's mapping of a syncpoint onto a
 *       declarative boundary has no subject. The boundary declared on the lookup below is read-only
 *       for exactly that reason.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the read-only boundary is declared on this class rather than on the
 * repository, and the repository's own contract is what settles where it belongs: marking a
 * repository method read-only would enlist a caller's read into a scope the caller never opened, so
 * the boundary is the caller's to declare. Declaring it read-only rather than leaving the read
 * unbounded is what tells the persistence provider it may skip dirty-checking and the flush that
 * would otherwise precede the query, and -- the part that matters for parity -- it makes the absence
 * of a write a property of the code rather than a property of a reader's memory.
 *
 * <h2>The keyed read carries an update option the reference program never uses</h2>
 *
 * <p>Refactoring Rationale: the reference {@code EXEC CICS READ} at lines 269 to 278 names its
 * dataset at line 270, the receiving record at line 271, its length at line 272,
 * {@code RIDFLD (TRAN-ID)} at line 273 and the key length at line 274 -- and then, at line 275,
 * supplies the {@code UPDATE} option. That option acquires an exclusive read-for-update lock, and
 * the measurement above shows the program contains no {@code REWRITE} and no {@code WRITE}
 * anywhere, so no statement in the 330 lines ever uses the lock it takes. The Java performs a plain
 * read-only lookup instead, through the repository's inherited keyed finder, with no pessimistic
 * lock mode and no locking annotation. The baseline does one thing, the Java implements another, and
 * the divergence is documented in the migration traceability register rather than introduced
 * silently.
 *
 * <p>Assumptions: the identifier is a character string and not a number, and the read is performed
 * on it unchanged. {@code app/cpy/CVTRA05Y.cpy} declares {@code TRAN-ID PIC X(16)} at line 5, the
 * entity's identity attribute is the same sixteen-character value, and the reference program at
 * line 172 moves the keyed-in screen field straight into the record key with no numeric conversion
 * in between. Parsing it to a number here would discard a leading zero while still comparing equal
 * on the way in, which is the failure mode the sibling response record documents against the seed
 * extract.
 *
 * <h2>The drill-down is a path parameter, and the re-entry flag disappears</h2>
 *
 * <p>Refactoring Rationale: lines 103 to 108 are the list-screen drill-down. On a first turn, when
 * the selected-transaction field of the communication area holds something other than spaces and
 * low values, the program copies it into the input field and performs the lookup before the screen
 * is ever sent. The mechanism it uses is {@code CARDDEMO-COMMAREA} at lines 19 to 44 of
 * {@code app/cpy/COCOM01Y.cpy}, which the client echoes back on every turn. That structure does not
 * travel: the identifier arrives as a path segment, which makes each request self-describing and
 * therefore independently authorisable, and no field of it is trusted from a client here.
 *
 * <p>Refactoring Rationale: the first-entry-versus-re-entry distinction collapses with it, so the
 * branch at lines 103 to 108 has no Java analogue and none is invented to preserve it. The
 * discriminator is {@code CDEMO-PGM-CONTEXT PIC 9(01)} at line 29 of that copybook, with its
 * first-entry and re-entry condition names at lines 30 and 31, and it exists only because a CICS
 * task cannot remember whether it has already sent the screen. A handler that answers one request
 * and returns a field entry has no such question to settle. What would not hold for
 * carrying it forward is visible in {@code app/cpy/CSSETATY.cpy}: line 20 gates the whole
 * field-highlight template on that very flag, so keeping the flag would make the presentation of an
 * error depend on a remembered turn count instead of on the response body.
 *
 * <h2>The function key that saves elsewhere navigates here</h2>
 *
 * <p>Refactoring Rationale: the attention-identifier dispatch at lines 112 to 132 selects the
 * lookup on enter at line 113, returns to the caller on PF3 at lines 115 to 122, clears the screen
 * on PF4 at lines 123 and 124, and on PF5 at lines 125 to 127 moves {@code 'COTRN00C'} into the
 * destination program and returns -- that is, PF5 on this screen goes back to the transaction list.
 * The design-system function-key table reads PF5 as a save, and on this screen it is not one. The
 * per-screen surfaces genuinely differ and are not normalised: the list program's dispatch at lines
 * 119 to 134 of {@code app/cbl/COTRN00C.cbl} offers enter, PF3, PF7 and PF8 with no PF4 and no PF5;
 * the capture program's at lines 133 to 152 of {@code app/cbl/COTRN02C.cbl} binds PF5 to copying the
 * last transaction's data; and the payment program's at lines 125 to 142 of
 * {@code app/cbl/COBIL00C.cbl} offers enter, PF3 and PF4 only. Three programs, three meanings for
 * PF5, none of them a save.
 *
 * <p>Refactoring Rationale: none of that dispatch is implemented here, and the deviation above is
 * recorded because a reader who knows the global table would otherwise expect a save method on this
 * class. What does not hold for carrying the dispatch across is that it puts the choice of the next
 * screen on the server: each branch of that selection ends either in a screen send or in a transfer
 * of control naming another program, so a class holding it would be deciding navigation from a
 * destination field the client supplied. Transformation rule T5 maps a transfer of control onto a
 * client route change instead, which is why there is no method here for enter, for PF3, for PF4, for
 * PF5 or for the invalid-key arm at lines 128 to 131 -- whose text is not an inline literal at all
 * but the copybook constant {@code CCDA-MSG-INVALID-KEY PIC X(50)} at lines 20 and 21 of
 * {@code app/cpy/CSMSG01Y.cpy}, published for reuse by the shared error contract.
 *
 * <h2>Three verbatim messages, two of which must never be merged</h2>
 *
 * <p>Transformation rule T8 carries every user-visible string across character for character, so
 * the three constants below are reproduced from their lines without re-wording, re-casing or
 * re-spacing, and each is measured rather than estimated: 27, 27 and 31 characters, every one of
 * them inside the 75-character message field the module answers in.
 *
 * <p>Assumptions: two strings differing only in case are two constants. The lookup-failure sentence
 * of this program, at line 292, capitalises its noun, while the list program spells the same
 * sentence with a lower-case noun at lines 615, 649 and 683 of {@code app/cbl/COTRN00C.cbl}. The
 * capitalised form recurs at lines 664 and 693 of {@code app/cbl/COTRN02C.cbl} and lines 463 and
 * 492 of {@code app/cbl/COBIL00C.cbl}, so the census across this context is two spellings over
 * eight source lines. A reader meeting the two side by side must not consolidate them: merging
 * would silently re-word one of the two sets of screens, and choosing which set to re-word would be
 * an arbitrary decision. Trade-offs: the accepted cost is a catalogue whose neighbouring entries
 * look like duplicates and invite a well-meant consolidation.
 *
 * <h2>A refused submission carries one field entry</h2>
 *
 * <p>Alternatives Considered: collecting every failing field and answering with all of them at once
 * was evaluated and rejected. The reference validation at lines 146 to 156 is a conditional
 * selection that takes its first matching branch and sends the screen straight back, and the read
 * that follows at lines 158 to 174 is guarded on the error flag being off, so the program surfaces
 * exactly one message per submission. Answering with several would show a user errors the reference
 * never showed. Transformation rule T7 makes the array the shape; one element is the arity.
 *
 * <p>Alternatives Considered: assembling the whole problem shape here was also evaluated and
 * rejected, for a reason a reader cannot recover from the code alone. The shared factory that builds
 * a validation problem additionally requires a correlation identity, a request path and a clock;
 * this class is stateless and holds none of the three, and the package charter forbids an exception
 * handler in this package. Raising the shared refusal type instead reaches the shared advice, whose
 * published behaviour is to answer HTTP 400 with exactly one field entry keyed by the field the
 * refusal names -- which is the arity above, produced by the layer that holds the three values.
 *
 * <h2>Two widths that look like defects and are not</h2>
 *
 * <p>Assumptions: 80 is not a message regime, and the near miss is named because it appears in this
 * very program. {@code WS-MESSAGE PIC X(80)} is declared at line 38 as internal work storage, and
 * line 217 moves it into the screen field {@code ERRMSGI PIC X(78)} at line 144 of
 * {@code app/cpy-bms/COTRN01.CPY}, so the reference program itself drops two bytes on the way out
 * and 80 never reaches an interface. The width this class answers in is the 75 of
 * {@code CCARD-ERROR-MSG} and {@code CCARD-RETURN-MSG} at lines 28 and 29 of
 * {@code app/cpy/CVCRD01Y.cpy}. That symbolic-map file name is upper case on disk, extension
 * included, so a lower-case path does not resolve.
 *
 * <p>Trade-offs: the amount is carried at nine integer digits even though the reference screen
 * renders eight, and the difference is latent reference behaviour rather than a shortfall being
 * addressed. {@code app/cbl/COTRN01C.cbl} declares {@code WS-TRAN-AMT PIC +99999999.99} at line 49
 * -- a sign, eight integer digits and two decimals -- and line 177 routes the record amount through
 * that edited field before line 183 writes it to the map, while {@code TRAN-AMT} is declared
 * {@code PIC S9(09)V99} at line 10 of {@code app/cpy/CVTRA05Y.cpy} with nine. The map field
 * corroborates the ceiling independently at {@code TRNAMTI PIC X(12)} on line 102 of the symbolic
 * map, which is exactly a sign, eight digits, a point and two more. A ninth integer digit is
 * therefore representable in the record and not on the screen. The response carries all nine, the
 * compromise accepted is that a client rendering into a column of the reference width has to decide
 * what to do with the surplus, and the divergence is documented. Nothing in this class inspects it:
 * the mapper is what converts the stored amount, and the money contract it applies is exact decimal
 * at scale two with half-up rounding, transported as a JSON string so that no client parses it as a
 * binary approximation.
 *
 * <h2>The two timestamps, and what the reference extract actually holds</h2>
 *
 * <p>Assumptions: {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are declared {@code PIC X(26)} at
 * lines 16 and 17 of that copybook and reach the response as the 26-character form the shared
 * formatter emits, with a space between the date and the time and always six fractional digits. The
 * rendering is the mapper's, not this class's.
 *
 * <p>Assumptions: the reference extract contains values that fill only part of that width, which is
 * why no code here parses either field. In {@code app/data/ASCII/dailytran.txt} the first record
 * carries a fully-formed originating instant while the 26 bytes of its processing timestamp are
 * spaces. That extract feeds the daily-transaction table, whose processing timestamp is nullable;
 * the posted-transaction table this class reads declares both timestamps present, so no branch here
 * tests the processing timestamp for absence -- a branch the schema forbids from ever being taken
 * would be untestable and would read as though the column were optional.
 *
 * <h2>What this class deliberately does not contain</h2>
 *
 * <p>The absences below are decisions. They are recorded so that a reader looking for one of these
 * things finds a ruling rather than concluding it was forgotten.
 *
 * <p>Refactoring Rationale: there is no method for {@code CLEAR-CURRENT-SCREEN} at line 301 or for
 * {@code INITIALIZE-ALL-FIELDS} at line 309, and the pairing at the head of this document defers the
 * ruling to here because it takes a paragraph to state. Three facts decide it. The pair is performed
 * only from the PF4 branch at lines 123 and 124, and function-key dispatch is client-side. What
 * they do is reset a screen buffer that survives between pseudo-conversational turns -- line 311
 * repositions the cursor and lines 312 to 326 move spaces into the input field, the thirteen display
 * fields and the message -- and no such buffer exists here, because a response is built for one
 * request and then discarded. And a cleared view is not representable in the response type at all:
 * line 318 moves spaces into {@code TRNAMTI}, which the symbolic map declares
 * {@code PIC X(12)} at line 102, whereas the corresponding response component is a money value for
 * which blank is not a value; the response record's own charter makes its message the only component
 * that may be null. Methods for the pair would therefore be unreachable code carrying an
 * unrepresentable result, so the clearing of a form is left where it belongs, on the client.
 *
 * <p>Assumptions: nothing here logs. The reference program's diagnostic at line 290 displays the
 * response and reason codes of a failed read, and its Java equivalent is the cause attached to the
 * refusal raised below, which the shared advice logs once at the edge together with the request
 * path and the correlation identity it alone holds. Logging here as well would write two records of
 * one failure, and the second would carry less context than the first.
 *
 * <p>Assumptions: no synchronous call leaves this class, so it builds no HTTP client and needs no
 * timeout budget. This program reads the transaction file and nothing else -- the cross-context read
 * of the card cross-reference belongs to the capture and payment services, whose paragraphs perform
 * it. For the same reason no resilience library and no circuit breaker appear here or anywhere under
 * this package root, a prohibition the shared architectural gate enforces on every build.
 *
 * <p>Assumptions: the class holds no session state and no mutable instance state, its two
 * collaborators arrive through the constructor and are final, and no field is written after
 * construction. That is what makes several identical tasks behind one load balancer interchangeable,
 * so any of them can answer a request with no sticky session and no shared session store. It is also
 * what makes the class testable with mocked collaborators and no database.
 *
 * <h2>No golden master covers this class</h2>
 *
 * <p>Assumptions: {@code tests/README.md} records at lines 83 to 85 that the online CICS programs
 * cannot be run end to end without a CICS runtime, which the test runner does not have, so only
 * their extractable field-validation logic is unit-tested. The program this class transcribes is one
 * of those, so no assertion about this class may be justified by pointing at a reference run.
 * Parity rests on two things instead and no others: the validation logic transcribed above, each
 * branch cited to its line, and the copybook record contracts. Stating which of the two it is keeps
 * a later reader from inferring coverage that was never available.
 */
@Service
public class TransactionViewService {

    /**
     * The response-field identity a refusal of the identifier is keyed by.
     *
     * <p>Assumptions: the value is the name of the published path parameter of the detail operation,
     * because the key exists so that a client can attach its help text to the control the operator
     * actually typed into, which transformation rule T7 requires of every entry. A key naming the
     * reference screen field {@code TRNIDINI}, declared {@code PIC X(16)} at line 60 of
     * {@code app/cpy-bms/COTRN01.CPY}, would name something no client has.
     */
    public static final String FIELD_TRANSACTION_ID = "transactionId";

    /**
     * The refusal text for an identifier that was never supplied, verbatim from line 149 of
     * {@code app/cbl/COTRN01C.cbl}.
     *
     * <p>Assumptions: the emphatic capitalisation of the negation is the reference spelling and is
     * carried across unchanged, as transformation rule T8 requires of every user-visible string.
     * Re-casing it to ordinary prose would be a re-wording of a screen.
     */
    public static final String MESSAGE_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

    /**
     * The text for an identifier that names no stored row, verbatim from line 285 of
     * {@code app/cbl/COTRN01C.cbl}.
     *
     * <p>Assumptions: this is the reference program's own not-found wording and it is published here
     * rather than left to the shared advice's default sentence, which the advice's own contract
     * anticipates: a context needing the reference wording supplies it.
     */
    public static final String MESSAGE_TRANSACTION_ID_NOT_FOUND = "Transaction ID NOT found...";

    /**
     * The text for a read that failed for any reason other than absence, verbatim from line 292 of
     * {@code app/cbl/COTRN01C.cbl}.
     *
     * <p>Assumptions: the capitalised noun is this program's spelling and is deliberately NOT shared
     * with the lower-case spelling of the same sentence at lines 615, 649 and 683 of
     * {@code app/cbl/COTRN00C.cbl}. The two are two constants; the census and the reason are on this
     * class.
     */
    public static final String MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION =
            "Unable to lookup Transaction...";

    /** The keyed reader of the posted-transaction table, standing in for the reference dataset. */
    private final TransactionRepository transactions;

    /** The converter that renders a stored row as the detail response, masking included. */
    private final TransactionMapper transactionMapper;

    /**
     * Creates the service around the two collaborators it reads through.
     *
     * @param transactions the repository whose inherited keyed finder replaces the reference read at
     *     line 269 of {@code app/cbl/COTRN01C.cbl}; must not be {@code null}
     * @param transactionMapper the converter that turns a stored row into the response, applying the
     *     masking, the money construction and the timestamp rendering this class must not perform
     *     itself; must not be {@code null}
     * @throws NullPointerException if either collaborator is {@code null}
     */
    public TransactionViewService(TransactionRepository transactions,
            TransactionMapper transactionMapper) {

        // WHY : Assumptions: both collaborators are checked at construction rather than at first
        //       use. A container that cannot supply one fails while the context is being built,
        //       naming the member, instead of answering the first request of the day with a null
        //       dereference whose stack trace names a line in this class rather than the wiring
        //       that actually differs.
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.transactionMapper =
                Objects.requireNonNull(transactionMapper, "transactionMapper must not be null");
    }

    /**
     * Returns the one transaction an identifier names, or refuses the request the way the reference
     * screen refuses it.
     *
     * <p>This transcribes {@code PROCESS-ENTER-KEY} at line 144 of {@code app/cbl/COTRN01C.cbl} in
     * the order that paragraph evaluates: the blank guard of lines 146 to 156 first, then the keyed
     * read of lines 158 to 174, then the thirteen record fields of lines 176 to 190.
     *
     * <p>Refactoring Rationale: the read-only boundary is declared here and not on the repository,
     * and it is read-only rather than read-write because the reference program contains no write
     * verb and no syncpoint at all. The measurement behind that claim, and the four consequences it
     * settles, are recorded on this class.
     *
     * <p>Assumptions: the identifier arrives as a path segment rather than from a session, so this
     * one parameter is the whole of the selection context. It is neither padded nor parsed here: the
     * reference program at line 172 moves the keyed-in field into the record key unchanged, and the
     * stored key is a declared-width character column, so a shorter value matches the padded row it
     * names without this class inventing a padding rule the reference program does not apply.
     *
     * @param transactionId the sixteen-character transaction identifier to look up, as the caller
     *     supplied it; {@code null}, empty, wholly-space and wholly-low-value values are all refused
     *     rather than looked up
     * @return the detail view of the stored transaction, carrying the thirteen mapped record fields
     *     with the card number masked to its last four digits, the amount as an exact decimal and no
     *     return message
     * @throws ClientInputException if the identifier was never supplied, carrying
     *     {@link #MESSAGE_TRAN_ID_EMPTY} and keyed by {@link #FIELD_TRANSACTION_ID}, which the
     *     shared advice answers as HTTP 400 with one field entry
     * @throws NoSuchElementException if no stored transaction carries the identifier, carrying
     *     {@link #MESSAGE_TRANSACTION_ID_NOT_FOUND}, which the shared advice answers as HTTP 404
     * @throws IllegalStateException if the keyed read fails for any reason other than absence,
     *     carrying {@link #MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION} and the underlying failure as its
     *     cause
     * @throws ArithmeticException if the stored amount cannot be reduced to the two decimal places
     *     the money contract carries, raised by the converter rather than here
     * @throws IllegalArgumentException if a stored merchant identifier or timestamp falls outside the
     *     domain its reference field declares, likewise raised by the converter; the refusal above is
     *     a subtype of this type and is listed separately because the two reach different statuses
     */
    @Transactional(readOnly = true)
    public TransactionDetailResponse viewTransaction(String transactionId) {

        // WHY : Assumptions: this guard is the reference selection's own first branch at lines 146 to
        //       156, so it runs before anything reaches the table rather than alongside the read.
        //       The reference test at line 147 compares the input field against SPACES and against
        //       LOW-VALUES, and the shared predicate is the exact analogue of that pair: it answers
        //       true for a null, for an empty value, for a value made wholly of the space pad and
        //       for one made wholly of the low-value pad, and for nothing else. The platform's
        //       general blank test is deliberately not used in its place, because it would call a
        //       tab absent -- a tab equals neither figurative constant -- while calling a run of low
        //       values present, which is wrong on both counts against the reference comparison.
        if (FieldValidationFlag.isNeverSupplied(transactionId)) {

            // WHY : Assumptions: the state is the blank one rather than the not-acceptable one, and
            //       the state is what fixes the marker: the entry derives its screen marker from the
            //       state, and the marker for a blank field is the asterisk that
            //       app/cpy/CSSETATY.cpy moves into the field's own subfield at lines 24 and 25
            //       while lines 21 and 22 move the error colour into a different subfield. A
            //       refused non-blank value would be the other state; this branch is reached only
            //       when the field was left empty.
            // WHY : Trade-offs: building the entry costs an allocation on a path that then raises,
            //       and it is built anyway because its canonical constructor is what validates the
            //       triple -- it refuses a blank field identity, a blank text and a state that is
            //       not an error state. A mis-keyed refusal therefore fails on this line, where the
            //       three values are visible together, rather than at the edge where the field key
            //       is all a client receives.
            ApiError.FieldError blankIdentifier = new ApiError.FieldError(
                    FIELD_TRANSACTION_ID, FieldValidationFlag.BLANK, MESSAGE_TRAN_ID_EMPTY);

            // WHY : Alternatives Considered: assembling the problem shape here and returning it.
            //       Rejected because the shared factory for a validation problem additionally needs
            //       a correlation identity, a request path and a clock, and a stateless service
            //       holds none of the three; the package charter also forbids an exception handler
            //       in this package. Raising the shared refusal type reaches the shared advice,
            //       which holds all three values and renders exactly one entry keyed by the field
            //       named here -- the arity the reference paragraph produces, because its selection
            //       leaves on its first matching branch and the read that follows is guarded on the
            //       error flag being off.
            // WHY : Refactoring Rationale: the never-supplied STATE is passed as well as the field and
            //       the message, where an earlier arrangement passed only the latter two and left the
            //       shared advice to assume the rejected-value state. The two states draw different
            //       markers -- app/cpy/CSSETATY.cpy moves an asterisk into a blank field and only the
            //       colour attribute into a rejected one -- so a form told this control held a
            //       rejected value drew no asterisk where the reference draws one.
            throw new ClientInputException(ApiError.CODE_VALIDATION, blankIdentifier.field(),
                    blankIdentifier.state(), blankIdentifier.message());
        }

        // WHY : Assumptions: the clearing of the thirteen display fields at lines 159 to 171 has no
        //       counterpart, and its absence is a decision rather than an oversight. Those moves
        //       blank a screen buffer that survives between turns, so that a read which then fails
        //       cannot leave the previous record on display. A failure here produces no response
        //       body at all, so there is nothing retained for a clear to remove.
        Transaction stored = readTransactFile(transactionId);

        // WHY : Assumptions: the message argument is null and not a blank string, because line 30 of
        //       app/cpy/CVCRD01Y.cpy attaches a message-off condition valued at low values to the
        //       return message alone, making absence representable for that one field. The
        //       reference program clears its message at line 91 on every turn and sets one only on a
        //       failure, so a successful read carries none. The converter collapses every spelling
        //       of absence onto null, so a blank string would arrive at the same place; null states
        //       the intent at the call site instead of relying on that collapse.
        return this.transactionMapper.toDetailResponse(stored, null);
    }

    /**
     * Reads the single stored transaction a keyed lookup names, reporting absence and failure the
     * way the reference paragraph reports them.
     *
     * <p>This transcribes {@code READ-TRANSACT-FILE} at line 267 of {@code app/cbl/COTRN01C.cbl},
     * whose {@code EXEC CICS READ} spans lines 269 to 278 and whose three-armed selection over the
     * response code spans lines 280 to 296: the normal arm continues at lines 281 and 282, the
     * not-found arm sets its message at line 285, and the catch-all arm sets its message at line
     * 292.
     *
     * @param transactionId the identifier to read on, already known to have been supplied; it is
     *     passed to the query unchanged, as line 172 passes it to the record key
     * @return the stored transaction, never {@code null}
     * @throws NoSuchElementException if the identifier names no row, carrying
     *     {@link #MESSAGE_TRANSACTION_ID_NOT_FOUND}
     * @throws IllegalStateException if the lookup itself fails, carrying
     *     {@link #MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION} and the originating failure as its cause
     */
    private Transaction readTransactFile(String transactionId) {

        Optional<Transaction> found;

        // WHY : Assumptions: only the lookup sits inside the guarded block, because in the reference
        //       program only the read itself sets the response code that the selection at line 280
        //       then examines. Enclosing the absence test as well would let the refusal raised for a
        //       missing row be caught by the arm meant for a failed read, and a routine not-found
        //       would then be reported as a server failure.
        try {

            // WHY : Refactoring Rationale: the reference read supplies the UPDATE option at line
            //       275, and the program contains no REWRITE and no WRITE anywhere in its 330
            //       lines, so it acquires an exclusive read-for-update lock that no statement ever
            //       uses. This is the plain read-only lookup that replaces it: no lock mode, no
            //       locking annotation. The baseline does one thing, the Java implements another,
            //       and the difference is registered as D-VIEW-READ-WITHOUT-LOCK in
            //       docs/architecture/cobol-to-service-traceability.md -- which states plainly that
            //       this target holds FEWER locks than the baseline, so a concurrent writer the
            //       baseline would have blocked now proceeds.
            found = this.transactions.findById(transactionId);

        } catch (RuntimeException lookupFailure) {

            // WHY : Alternatives Considered: catching the persistence layer's translated
            //       data-access supertype instead of every runtime failure. Rejected because the arm
            //       being transcribed is the reference selection's catch-all over every response
            //       code other than normal and not-found, so a driver failure that escaped
            //       translation would slip past the narrower catch and reach the edge carrying the
            //       shared advice's generic sentence rather than the reference one -- which is the
            //       single observable thing this arm exists to produce.
            // WHY : Assumptions: the cause is attached rather than logged. Line 290 displays the
            //       response and reason codes of the failed read, and the equivalent context here is
            //       the failure object itself, which the shared advice logs once at the edge with
            //       the request path and the correlation identity it alone holds. Attaching it also
            //       feeds that advice's classifier, which walks the cause chain for three contention
            //       conditions; a keyed read requesting no lock can raise none of the three, so the
            //       classification lands on the server-failure arm rather than on a conflict.
            throw new IllegalStateException(MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION, lookupFailure);
        }

        // WHY : Assumptions: an absent row is an ordinary outcome rather than a failure, which is
        //       what the reference program says by handling not-found at lines 283 to 288 inside the
        //       same selection as a normal read instead of abending. An empty result is how the
        //       query carries that, and the standard no-such-element type is what the shared advice
        //       answers as HTTP 404 -- so the status is not decided here either.
        return found.orElseThrow(
                () -> new NoSuchElementException(MESSAGE_TRANSACTION_ID_NOT_FOUND));
    }
}
