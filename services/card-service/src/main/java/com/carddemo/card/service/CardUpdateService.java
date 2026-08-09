package com.carddemo.card.service;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardUpdateRequest;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.validation.FieldValidationFlag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one submitted edit to a single card, carrying the reference update program's edit chain, its
 * no-change short circuit and its concurrency discipline.
 *
 * <h2>What this service is</h2>
 *
 * <p>This is the migrated form of the card update program {@code app/cbl/COCRDUPC.cbl}, 1560 lines,
 * reached as CICS transaction {@code CCUP} whose definition sits at lines 367 to 369 of
 * {@code app/csd/CARDDEMO.CSD} -- the only one of the three card transactions carrying a
 * {@code DESCRIPTION} line, at line 368. Four attributes are editable and no others: the embossed
 * name, the active status, the expiry month and the expiry year. The card number is the key, the
 * account identifier is not editable from a card screen, and the verification value and the expiry day
 * are not editable at all.</p>
 *
 * <h2>Which reference paragraph each method carries</h2>
 *
 * <p>Transformation plan section 0.4.3 asks that each significant paragraph become a named method so
 * that the traceability matrix can cite paragraph-to-method pairs. The mapping this class
 * implements:</p>
 *
 * <ul>
 *   <li>{@code 1200-EDIT-MAP-INPUTS} lines 641 to 717 becomes two methods rather than one, because
 *       the paragraph has two mutually exclusive stages. Its search-key stage, lines 645 to 661,
 *       becomes {@link #resolveCardNumber(String)}; its field stage, lines 667 to 714, becomes
 *       {@link #editMapInputs(CardUpdateRequest)}.</li>
 *   <li>{@code 1210-EDIT-ACCOUNT} lines 721 to 758 and {@code 1220-EDIT-CARD} lines 762 to 802 are
 *       reached only from the search-key stage, so both fold into
 *       {@link #resolveCardNumber(String)}.</li>
 *   <li>{@code 1230-EDIT-NAME} lines 806 to 841 becomes {@link #editName(String)}.</li>
 *   <li>{@code 1240-EDIT-CARDSTATUS} lines 845 to 874 becomes {@link #editCardStatus(String)}.</li>
 *   <li>{@code 1250-EDIT-EXPIRY-MON} lines 877 to 910 becomes {@link #editExpiryMonth(String)}.</li>
 *   <li>{@code 1260-EDIT-EXPIRY-YEAR} lines 913 to 945 becomes {@link #editExpiryYear(String)}.</li>
 *   <li>The no-change test at lines 680 to 683 and the skip-all-edits short circuit at lines 685 to
 *       693 become {@link #hasNoChanges(CardUpdateRequest, Card)}.</li>
 *   <li>{@code 9000-READ-DATA} lines 1343 to 1372 and {@code 9100-GETCARD-BYACCTCARD} lines 1376 to
 *       1415 together become {@link #requireCard(String)}.</li>
 *   <li>{@code 9200-WRITE-PROCESSING} lines 1420 to 1494 becomes
 *       {@link #writeProcessing(CardUpdateRequest, Card)}.</li>
 *   <li>{@code 9300-CHECK-CHANGE-IN-REC} lines 1498 to 1521 becomes
 *       {@link #checkChangeInRecord(Card, Integer)}.</li>
 *   <li>The confirm arm of {@code 2000-DECIDE-ACTION}, lines 988 to 1001, is the order in which
 *       {@link #update(String, CardUpdateRequest)} composes the above.</li>
 * </ul>
 *
 * <p>{@code 1100-RECEIVE-MAP} lines 578 to 638 has no counterpart, because a parsed request body
 * arrives where a terminal datastream had to be read and unpacked. The presentation paragraphs
 * {@code 3000-SEND-MAP} through {@code 3400-SEND-SCREEN}, lines 1035 to 1338, belong to the browser
 * tree; {@code 3300-SETUP-SCREEN-ATTRS} at lines 1168 to 1319 was checked to contain no
 * {@code PERFORM}, no {@code COMPUTE}, no {@code EXEC CICS} and no {@code SET INPUT-ERROR}, so it is
 * purely presentational and none of it is transcribed here.</p>
 *
 * <h2>Why the edit chain becomes two methods and not one</h2>
 *
 * <p>Refactoring Rationale: the reference paragraph is a two-stage dispatcher whose stages never run
 * in the same invocation. Line 645 tests {@code CCUP-DETAILS-NOT-FETCHED}; when it holds, the two
 * search-key edits run at lines 647 to 651, the field group is cleared at line 653, and line 661
 * leaves the paragraph outright by {@code GO TO 1200-EDIT-MAP-INPUTS-EXIT}, so the four field edits
 * are not reached. Only once details have been fetched does control fall through to lines 667 to 714,
 * where the two filters are forced valid at lines 669 and 670 and only the four field edits are
 * performed, at lines 698 to 708. The stage split exists because the platform ended the CICS task at
 * every screen turn, so one screen could not both accept a search key and edit the record it had not
 * yet read; the two stages are two different turns. The target platform dispatches a single
 * self-describing request that names the card in its path and carries the new attribute values in its
 * body, so both stages are reachable within one call and the same behaviour is expressed as two
 * methods called in sequence rather than as one paragraph branching on how many turns have
 * elapsed.</p>
 *
 * <h2>How the reference's own concurrency discipline becomes the target's</h2>
 *
 * <p>Refactoring Rationale: the reference performs a before-image comparison by hand, and the target
 * expresses the identical intent through the persistence provider. It snapshots the pre-edit record
 * into {@code CCUP-OLD-DETAILS} at lines 291 to 301 -- an eleven-character account identifier at 292,
 * a sixteen-character card number at 293, a three-character verification value at 294 and a group at
 * 295 holding a fifty-character name at 296, an expiry at 297 composed of a four-character year at
 * 298, a two-character month at 299 and a two-character day at 300, and a one-character status at 301
 * -- clears it with {@code INITIALIZE} at line 1345, mirrors it with {@code CCUP-NEW-DETAILS} at lines
 * 303 to 313, and compares the two before rewriting. It does that because a CICS read-for-update lock
 * was never held across the operator's thinking time, which is precisely why the snapshot has to
 * exist. The target platform supplies a row version the provider maintains and checks, so the
 * comparison the reference codes field by field becomes a single token the client echoes back and
 * {@link #checkChangeInRecord(Card, Integer)} tests. Nothing is given up, because both mechanisms
 * detect the same condition over the same window.</p>
 *
 * <p>Assumptions: the snapshot's expiry group is eight characters, not ten. Lines 298 to 300 declare
 * four plus two plus two and the two hyphens are not snapshotted, which is why the comparison at lines
 * 1503 to 1508 reads positions {@code (1:4)}, {@code (6:2)} and {@code (9:2)} and steps over positions
 * five and eight. The record's own {@code CARD-UPDATE-EXPIRAION-DATE PIC X(10)} at line 319 is
 * hyphenated, and the {@code STRING} at lines 1467 to 1474 is what bridges the two forms.</p>
 *
 * <h2>What the migrated diagnostics gain and what they give up</h2>
 *
 * <p>Trade-offs: the reference reports through one {@code WS-RETURN-MSG PIC X(75)} field declared at
 * line 173, whose {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at line 174 is tested before every write
 * to it -- at lines 730, 743, 773 and 787 in the search-key stage and at lines 816, 833, 855, 868,
 * 888, 903, 921 and 939 in the field stage. That guard makes the aggregate sentence first-error-wins:
 * whichever edit faults first owns the one sentence a 24-row terminal could show. This service instead
 * returns an accumulating per-field array through {@link #validateAttributes(CardUpdateRequest)},
 * alongside one summary sentence chosen by the reference program's own precedence. What is gained is
 * that a caller who submitted three unacceptable attributes learns about all three from one exchange
 * rather than discovering the second only after correcting the first. What is given up is the
 * guarantee that the summary sentence and the faulted-field set describe the same single attribute,
 * which on a fixed-width screen they always did; a renderer wanting the reference behaviour exactly
 * can still get it by showing the summary sentence alone and ignoring the array.</p>
 *
 * <p>Trade-offs: the width contract behind that sentence is {@code PIC X(75)}, declared both at line
 * 173 here and as {@code CCARD-RETURN-MSG} at line 29 of {@code app/cpy/CVCRD01Y.cpy}, and it is
 * carried in the target as {@link ApiError#MESSAGE_RENDERING_WIDTH} rather than enforced here. Two
 * details are recorded so they are not conflated later: the copybook's own
 * {@code 88 CCARD-RETURN-MSG-OFF} at line 30 tests {@code LOW-VALUES} where this program's test at
 * line 174 tests {@code SPACES}, a real difference between the two declarations; and neither is the
 * fifty-character width of the shared message copybook nor the forty-character width of the title
 * copybook.</p>
 *
 * <h2>Statelessness, and the absence of a parity oracle</h2>
 *
 * <p>Assumptions: this class holds no mutable state and both collaborators arrive through the
 * constructor, so several instances serve concurrently behind a load balancer. The reference
 * transaction required the same of its program: {@code TWASIZE(0)} at line 369 of
 * {@code app/csd/CARDDEMO.CSD} gave it no transaction work area to keep anything in.</p>
 *
 * <p>Assumptions: lines 83 to 85 of {@code tests/README.md} record that the online programs cannot be
 * run end to end without a CICS runtime, which the runner does not have. There is therefore no
 * golden-master oracle for this path, and no claim of one should be read into the citations above.
 * Parity rests on transcription fidelity against the cited lines plus the tests in the sibling test
 * tree, and nothing stronger.</p>
 */
@Service
public class CardUpdateService {

    /**
     * The response-field identity carried by a fault against the embossed name.
     *
     * <p>Assumptions: the four identities below are spelled exactly as the components of
     * {@link CardUpdateRequest}, so a client can bind an array entry straight back to the member it
     * submitted. Any other spelling would leave a caller unable to attach the fault to an input.</p>
     */
    public static final String FIELD_EMBOSSED_NAME = "embossedName";

    /**
     * The response-field identity carried by a fault against the active status.
     */
    public static final String FIELD_ACTIVE_STATUS = "activeStatus";

    /**
     * The response-field identity carried by a fault against the expiry month.
     */
    public static final String FIELD_EXPIRATION_MONTH = "expirationMonth";

    /**
     * The response-field identity carried by a fault against the expiry year.
     */
    public static final String FIELD_EXPIRATION_YEAR = "expirationYear";

    /**
     * The refusal code this service reports when a submitted attribute is not acceptable.
     *
     * <p>Assumptions: the shared advice renders a rejected-input failure as HTTP 400 and takes the
     * code from the failure, so naming the shared validation code keeps this context's refusal bodies
     * identical in shape to every other context's.</p>
     */
    public static final String ATTRIBUTE_REFUSAL_CODE = ApiError.CODE_VALIDATION;

    /**
     * The reference sentence shown once the requested card has been read.
     *
     * <p>Assumptions: this and the five that follow it belong to
     * {@code WS-INFO-MSG PIC X(40)} at line 157, a different and narrower field from the
     * seventy-five-character sentence the refusals use. The width is forty here and forty-five in the
     * card-list program, so the two are not one contract; it is recorded because the two programs look
     * interchangeable and are not. Carried verbatim under transformation rule T8 from
     * {@code 88 FOUND-CARDS-FOR-ACCOUNT} at lines 160 and 161, set at line 668 by the field stage and
     * again at line 1394 by the successful arm of the read.</p>
     */
    public static final String MESSAGE_DETAILS_SHOWN = "Details of selected card shown above";

    /**
     * The reference sentence shown before a card has been chosen.
     *
     * <p>Assumptions: carried verbatim from {@code 88 PROMPT-FOR-SEARCH-KEYS} at lines 162 and 163.
     * Its three emitters, at lines 1142, 1144 and 1158, all sit inside the presentation paragraph
     * {@code 3250-SETUP-INFOMSG}, so the sentence belongs to the browser tree and this service emits
     * it nowhere. It is published so that tree has one place to take it from.</p>
     */
    public static final String MESSAGE_PROMPT_FOR_SEARCH_KEYS = "Please enter Account and Card Number";

    /**
     * The reference sentence inviting the operator to edit the record on display.
     *
     * <p>Assumptions: carried verbatim from {@code 88 PROMPT-FOR-CHANGES} at lines 164 and 165, whose
     * one emitter at line 1148 is likewise presentational. The closing period is inside the reference
     * literal.</p>
     */
    public static final String MESSAGE_PROMPT_FOR_CHANGES = "Update card details presented above.";

    /**
     * The reference sentence shown when edits passed but had not yet been confirmed.
     *
     * <p>Assumptions: carried verbatim from {@code 88 PROMPT-FOR-CONFIRMATION} at lines 166 and 167,
     * emitted at lines 1150 and 1315. The absent space after the period is inside the reference
     * literal and is carried exactly as it stands, because rule T8 reproduces the value character for
     * character.</p>
     *
     * <p>Alternatives Considered: reproducing the waiting step this sentence announces, by having the
     * request carry a confirmation flag and this service refuse an unconfirmed submission. The
     * reference two-step save is turn state rather than payload: {@code CCUP-CHANGE-ACTION PIC X(1)}
     * at lines 276 and 277 moves through the conditions declared at lines 278 to 290, and the confirm
     * arm at lines 988 to 1001 fires only when {@code CCUP-CHANGES-OK-NOT-CONFIRMED} at line 286 is
     * set and the function key was pressed. The keystroke was never a field in the data the screen
     * sent, so there is nothing to carry. It is rejected because one request that validates and writes
     * atomically is what a caller already has -- the client confirms before sending -- whereas a
     * server-side waiting step would need state held between two calls, which is exactly the
     * pseudo-conversational coupling this migration removes. {@link CardUpdateRequest} accordingly
     * carries no confirmation member and this service reads none.</p>
     */
    public static final String MESSAGE_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /**
     * The reference sentence shown after a successful rewrite.
     *
     * <p>Assumptions: carried verbatim from {@code 88 CONFIRM-UPDATE-SUCCESS} at lines 168 and 169,
     * emitted presentationally at line 1152. This service reports success by returning the saved
     * detail rather than by returning a sentence, because {@link CardDetail} carries no message
     * member; the sentence is published for the browser tree that does render one.</p>
     */
    public static final String MESSAGE_UPDATE_SUCCESS = "Changes committed to database";

    /**
     * The reference sentence shown after a rewrite that did not take effect.
     *
     * <p>Assumptions: carried verbatim from {@code 88 INFORM-FAILURE} at lines 170 and 171, emitted at
     * lines 1154 and 1156.</p>
     */
    public static final String MESSAGE_INFORM_FAILURE = "Changes unsuccessful. Please try again";

    /**
     * The reference sentence for the exit key.
     *
     * <p>Assumptions: carried verbatim from {@code 88 WS-EXIT-MESSAGE} at lines 175 and 176,
     * including the absent space after the period and the fourteen trailing spaces inside the literal,
     * which bring its declared length to thirty-four characters. Searching the program for that
     * condition name returns only line 175, so it has no emitter, and the navigation it described is a
     * client-side route change in the target rather than anything this service reports.</p>
     */
    public static final String MESSAGE_EXIT = "PF03 pressed.Exiting              ";

    /**
     * The reference sentence for an absent account search key.
     *
     * <p>Assumptions: carried verbatim from {@code 88 WS-PROMPT-FOR-ACCT} at lines 177 and 178,
     * latched at lines 730 and 731 inside the blank arm of the account edit. That arm is reachable
     * only from the search-key stage, which {@link #resolveCardNumber(String)} explains has no
     * surviving counterpart, so this service emits the sentence nowhere.</p>
     */
    public static final String MESSAGE_ACCOUNT_NOT_PROVIDED = "Account number not provided";

    /**
     * The reference sentence for an absent card search key.
     *
     * <p>Assumptions: carried verbatim from {@code 88 WS-PROMPT-FOR-CARD} at lines 179 and 180,
     * latched at lines 773 and 774, and unemitted here for the same reason as its account
     * counterpart.</p>
     */
    public static final String MESSAGE_CARD_NOT_PROVIDED = "Card number not provided";

    /**
     * The reference sentence for an absent embossed name.
     *
     * <p>Assumptions: carried verbatim from {@code 88 WS-PROMPT-FOR-NAME} at lines 181 and 182,
     * latched at lines 816 and 817. This is one of only two sentences in the field stage that this
     * service does emit for an absent value, the name edit being the single gate whose blank arm and
     * unacceptable arm carry different text.</p>
     */
    public static final String MESSAGE_NAME_NOT_PROVIDED = "Card name not provided";

    /**
     * The reference sentence for an embossed name holding anything but letters and spaces.
     *
     * <p>Assumptions: carried verbatim from {@code 88 WS-NAME-MUST-BE-ALPHA} at lines 183 and 184,
     * latched at lines 833 and 834.</p>
     */
    public static final String MESSAGE_NAME_MUST_BE_ALPHA =
            "Card name can only contain alphabets and spaces";

    /**
     * The reference sentence for a search that supplied neither key.
     *
     * <p>Assumptions: carried verbatim from {@code 88 NO-SEARCH-CRITERIA-RECEIVED} at lines 185 and
     * 186, set at lines 656 to 659 when both search-key flags came back blank. Unlike the per-field
     * writes around it that assignment is not wrapped in the first-error-wins guard, so in the
     * reference it supersedes whatever the account edit had already latched. It is unemitted here
     * because it belongs to the stage that does not survive.</p>
     */
    public static final String MESSAGE_NO_INPUT_RECEIVED = "No input received";

    /**
     * The reference sentence for a submission identical to the values last read.
     *
     * <p>Assumptions: carried verbatim from {@code 88 NO-CHANGES-DETECTED} at lines 187 and 188,
     * including the closing period inside the literal, and set at line 682. This one IS emitted here,
     * by {@link #update(String, CardUpdateRequest)}, and it is the sentence that accompanies the one
     * outcome in which a submission is accepted and nothing is written.</p>
     */
    public static final String MESSAGE_NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";

    /**
     * The reference sentence naming an all-zero account search key.
     *
     * <p>Assumptions: carried verbatim from {@code 88 SEARCHED-ACCT-ZEROES} at lines 189 and 190.
     * Searching the program for that condition name returns only line 189, so it has no emitter: the
     * reason is visible at line 727, where {@code CC-ACCT-ID-N EQUAL ZEROS} is the third disjunct of
     * the blank arm, so an all-zero key is reported as absent before any arm that could set this
     * condition is reached. It is published because rule T8 carries the value across regardless, and
     * annotated so that nobody attaches it to a live branch and thereby introduces behaviour the
     * reference does not have.</p>
     */
    public static final String MESSAGE_SEARCHED_ACCOUNT_ZEROES =
            "Account number must be a non zero 11 digit number";

    /**
     * The reference sentence naming a non-numeric account search key, as a second condition name.
     *
     * <p>Assumptions: carried verbatim from {@code 88 SEARCHED-ACCT-NOT-NUMERIC} at lines 191 and 192.
     * Its literal is character for character identical to {@link #MESSAGE_SEARCHED_ACCOUNT_ZEROES},
     * and the two are deliberately kept as separate constants rather than collapsed into one: the
     * reference declares two distinct condition names, and folding them together would erase a
     * distinction the reference makes and leave a later reader unable to tell which name a citation
     * refers to. Like its twin it has no emitter, the non-numeric arm writing the inline literal at
     * line 745 instead.</p>
     */
    public static final String MESSAGE_SEARCHED_ACCOUNT_NOT_NUMERIC =
            "Account number must be a non zero 11 digit number";

    /**
     * The reference sentence naming a non-numeric card search key as a condition name.
     *
     * <p>Assumptions: carried verbatim from {@code 88 SEARCHED-CARD-NOT-NUMERIC} at lines 193 and 194,
     * and it likewise has no emitter, the card edit writing the inline literal at line 789
     * instead.</p>
     */
    public static final String MESSAGE_SEARCHED_CARD_NOT_NUMERIC =
            "Card number if supplied must be a 16 digit number";

    /**
     * The reference sentence the account-key edit actually writes when the value is not numeric.
     *
     * <p>Assumptions: carried verbatim from the inline {@code MOVE} at lines 744 to 746, whose literal
     * is on line 745. Two spellings inside it are reference text and are not adjusted: there is no
     * space after the comma, and the article reads {@code A 11} rather than {@code AN 11}. Its
     * declared length is fifty-two characters.</p>
     */
    public static final String MESSAGE_ACCOUNT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * The reference sentence the card-key edit actually writes when the value is not numeric.
     *
     * <p>Assumptions: carried verbatim from the inline write at lines 788 to 790, whose literal is on
     * line 789, and paired with {@link #MESSAGE_ACCOUNT_FILTER_NOT_NUMERIC} for the same reason.</p>
     */
    public static final String MESSAGE_CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * The reference sentence for an active status outside its two permitted values.
     *
     * <p>Assumptions: carried verbatim from {@code 88 CARD-STATUS-MUST-BE-YES-NO} at lines 195 and
     * 196. One constant serves both arms of the status gate because the reference latches this same
     * condition twice: at lines 855 and 856 when the value is absent, and at lines 868 and 869 when it
     * is present but outside the set. That asymmetry against the name gate, which has two different
     * sentences, is reproduced rather than normalised.</p>
     */
    public static final String MESSAGE_STATUS_MUST_BE_YES_NO = "Card Active Status must be Y or N";

    /**
     * The reference sentence for an expiry month outside one through twelve.
     *
     * <p>Assumptions: carried verbatim from {@code 88 CARD-EXPIRY-MONTH-NOT-VALID} at lines 197 and
     * 198, and likewise serving both arms, at lines 888 and 889 for an absent value and at lines 903
     * and 904 for a value outside the range.</p>
     */
    public static final String MESSAGE_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    /**
     * The reference sentence for an expiry year outside nineteen fifty through twenty ninety-nine.
     *
     * <p>Assumptions: carried verbatim from {@code 88 CARD-EXPIRY-YEAR-NOT-VALID} at lines 199 and
     * 200, serving both arms at lines 921 and 922 and at lines 939 and 940. Note that it names no
     * range, where its month counterpart does; the wording is the reference's and is not
     * expanded.</p>
     */
    public static final String MESSAGE_EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";

    /**
     * The reference sentence for an account that no card row names.
     *
     * <p>Assumptions: carried verbatim from {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF} at lines 201 and
     * 202. Searching the program for that condition name returns only line 201, so it has no emitter
     * here, and it is attached to no branch in this service. It is published because
     * rule T8 carries the value across, and annotated because it is the sentence most easily mistaken
     * for the one the read actually latches -- see {@link #requireCard(String)}, which raises
     * {@link CardViewService#MESSAGE_CARD_NOT_FOUND} instead.</p>
     */
    public static final String MESSAGE_ACCOUNT_NOT_IN_CARDS_DATABASE =
            "Did not find this account in cards database";

    /**
     * The reference sentence for a failed read of the card file.
     *
     * <p>Assumptions: carried verbatim from {@code 88 XREF-READ-ERROR} at lines 211 and 212.
     * Searching the program for that condition name returns only line 211, so it has no emitter
     * either; the failed-read arm at lines 1407 to 1411 composes
     * {@code WS-FILE-ERROR-MESSAGE} from the operation name, the file name and the two response codes
     * instead. Published under rule T8 and annotated so that it is not attached to a live branch.</p>
     */
    public static final String MESSAGE_CARD_FILE_READ_ERROR = "Error reading Card Data File";

    /**
     * The reference sentence left in the program against work its author had not yet reached.
     *
     * <p>Assumptions: carried verbatim from {@code 88 CODING-TO-BE-DONE} at lines 213 and 214, all
     * four dots included. Searching the program for that condition name returns only line 213, so it
     * has no emitter and never reaches a user in the reference; the decision here is therefore that it
     * surfaces nowhere, and it is attached to no branch. Rule T8 is why it is present at all;
     * recording that it is unreached is what stops a later reader assuming it must be displayed
     * somewhere.</p>
     */
    public static final String MESSAGE_CODING_TO_BE_DONE = "Looks Good.... so far";

    /**
     * The reference sentence for a record another writer changed first.
     *
     * <p>Refactoring Rationale: this value is one thing in the reference and two in the target.
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} at lines 207 and 208 is a message-VALUE condition name
     * declared ON {@code WS-RETURN-MSG PIC X(75)}, not a standalone boolean, so
     * {@code SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE} at line 1511 records the OUTCOME and writes
     * the operator-visible TEXT in one act -- the two share the same storage. They shared it because
     * the platform's only output channel was one line of a 3270 screen, so there was nothing for an
     * outcome to be carried on separately. The target has two channels, a transport status and a
     * response body, and serves a browser application and a programmatic client from the same
     * endpoint; so the outcome becomes {@link RecordConflictException.Kind#STALE_VERSION}, which the
     * shared advice renders as HTTP 409, and the text becomes this constant. The condition at lines
     * 1455 to 1457, which branches away from the write when it is set, becomes the raise in
     * {@link #checkChangeInRecord(Card, Integer)}.</p>
     *
     * <p>Assumptions: the value is taken from {@link ApiError#COACTUPC_RECORD_CHANGED} rather than
     * written out again, so there is exactly one copy of the string in the codebase and the two cannot
     * drift. The shared constant is named for the account-update program, and this program declares
     * the literal independently at lines 207 and 208; the two are byte identical, which is why sharing
     * the storage is safe and why the citation here is to this program's own lines. Two spellings are
     * reference text: the third word is TWO words, and there is no closing period inside the literal
     * -- the period visible after the closing quote at line 208 is the COBOL statement
     * terminator.</p>
     */
    public static final String MESSAGE_RECORD_CHANGED = ApiError.COACTUPC_RECORD_CHANGED;

    /**
     * The reference sentence for a record that could not be held for update.
     *
     * <p>Refactoring Rationale: this is the second of the three message-VALUE condition names, and it
     * decomposes the same way. {@code 88 COULD-NOT-LOCK-FOR-UPDATE} at lines 205 and 206 is declared
     * on the same seventy-five-character field, and its emitters at lines 1446 and 993 set outcome and
     * text together, because a screen line was the only place either could go. In the target the
     * outcome is {@link RecordConflictException.Kind#LOCK_UNAVAILABLE} and the text is this constant.
     * The reference acquired an explicit hold with {@code EXEC CICS READ ... UPDATE} at lines 1427 to
     * 1436 and tested it at lines 1441 to 1449 because the platform's file access needed a hold before
     * a rewrite; the target platform detects contention through the row version instead, so this
     * service performs no lock-acquisition step and consequently has no branch that can raise this
     * outcome. The constant is carried under rule T8 and the shared advice already renders the same
     * text for the kind, so a future path that does take a hold has both halves waiting for it.</p>
     */
    public static final String MESSAGE_COULD_NOT_LOCK = "Could not lock record for update";

    /**
     * The reference sentence for a rewrite that was permitted but did not take effect.
     *
     * <p>Refactoring Rationale: this is the third message-VALUE condition name, and it decomposes
     * identically. {@code 88 LOCKED-BUT-UPDATE-FAILED} at lines 209 and 210 is declared on the same
     * field, and the test at lines 1488 to 1492 sets it when the rewrite's response was not normal,
     * again fusing outcome and text in one seventy-five-character store for want of anywhere else to
     * put either. In the target a write that does not take effect is raised by the persistence
     * provider and rendered by the shared advice, so the outcome is a transport status this service
     * does not choose and the text is this constant. Nothing here catches that condition, and the
     * reason is given on {@link #writeProcessing(CardUpdateRequest, Card)}.</p>
     */
    public static final String MESSAGE_UPDATE_FAILED = "Update of record failed";

    /**
     * The declared width of the embossed name, fifty characters.
     *
     * <p>Assumptions: three independent declarations agree, which is why it is a named constant rather
     * than an inline number: {@code CARD-EMBOSSED-NAME PIC X(50)} at line 8 of
     * {@code app/cpy/CVACT02Y.cpy} is the stored column, {@code CCUP-OLD-CRDNAME PIC X(50)} at line 296
     * and its mirror at line 308 are the two snapshot halves, and
     * {@code CARD-NAME-CHECK PIC X(50)} at line 87 is the scratch field the alphabetic test runs
     * over.</p>
     */
    public static final int EMBOSSED_NAME_WIDTH = 50;

    /**
     * The declared width of the expiry year, four characters, from lines 298 and 310.
     */
    public static final int EXPIRATION_YEAR_WIDTH = 4;

    /**
     * The declared width of the expiry month, two characters, from lines 299 and 311.
     */
    public static final int EXPIRATION_MONTH_WIDTH = 2;

    /**
     * The declared width of the expiry day, two characters, from lines 300 and 312.
     */
    public static final int EXPIRATION_DAY_WIDTH = 2;

    /**
     * The declared width of the active status, one character, from lines 301 and 313.
     */
    public static final int ACTIVE_STATUS_WIDTH = 1;

    /**
     * The total width of the group the no-change comparison is made over, fifty-nine characters.
     *
     * <p>Assumptions: {@code CCUP-OLD-CARDDATA} at line 295 and {@code CCUP-NEW-CARDDATA} at line 307
     * each enclose the name, the three expiry parts and the status and nothing else, so fifty plus four
     * plus two plus two plus one is the whole group. The figure is stated so that
     * {@link #cardDataGroup(String, String, String, String, String)} can be checked against the
     * reference layout by addition rather than by trust.</p>
     */
    public static final int CARD_DATA_GROUP_WIDTH = EMBOSSED_NAME_WIDTH + EXPIRATION_YEAR_WIDTH
            + EXPIRATION_MONTH_WIDTH + EXPIRATION_DAY_WIDTH + ACTIVE_STATUS_WIDTH;

    /**
     * The lowest expiry month the reference accepts, one.
     *
     * <p>Assumptions: this bound and the three that follow are the update program's OWN local
     * constants and are not shared with the date-edit copybooks. {@code 88 VALID-MONTH VALUES 1 THRU
     * 12} at line 95 and {@code 88 VALID-YEAR VALUES 1950 THRU 2099} at line 99 are declared in this
     * program's working storage, and none of the three card programs copies
     * {@code app/cpy/CSUTLDPY.cpy} or {@code app/cpy/CSUTLDWY.cpy} -- the thirteen {@code COPY}
     * statements of this program are {@code CVCRD01Y}, {@code COCOM01Y}, {@code DFHBMSCA},
     * {@code DFHAID}, {@code COTTL01Y}, {@code COCRDUP}, {@code CSDAT01Y}, {@code CSMSG01Y},
     * {@code CSMSG02Y}, {@code CSUSR01Y}, {@code CVACT02Y}, {@code CVCUS01Y} and {@code CSSTRPFY}.
     * The window is therefore transcribed locally, and it could not be taken from the date-edit
     * copybook in any case, which declares no such year range.</p>
     */
    public static final int MINIMUM_EXPIRY_MONTH = 1;

    /**
     * The highest expiry month the reference accepts, twelve, from line 95.
     */
    public static final int MAXIMUM_EXPIRY_MONTH = 12;

    /**
     * The lowest expiry year the reference accepts, nineteen fifty, from line 99.
     */
    public static final int MINIMUM_EXPIRY_YEAR = 1950;

    /**
     * The highest expiry year the reference accepts, twenty ninety-nine, from line 99.
     */
    public static final int MAXIMUM_EXPIRY_YEAR = 2099;

    /**
     * The active-status value meaning the card is in force.
     *
     * <p>Assumptions: the domain is exactly {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'} at line 91,
     * tested at line 863 after the submitted value is moved into
     * {@code FLG-YES-NO-CHECK PIC X(1) VALUE 'N'} at lines 89 and 90. Both members are upper case and
     * the condition admits no lower-case alternative, so accepting one would widen a domain the
     * reference did not.</p>
     */
    public static final char ACTIVE_STATUS_YES = 'Y';

    /**
     * The active-status value meaning the card is not in force, from line 91.
     */
    public static final char ACTIVE_STATUS_NO = 'N';

    /**
     * Records which edit ran and how it ended, never the values it carried.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardUpdateService.class);

    /**
     * The lowest ASCII letter of the case the reference folds towards.
     *
     * <p>Assumptions: the fold covers exactly the twenty-six ASCII letters, because
     * {@code LIT-LOWER PIC X(26)} at lines 262 and 263 and {@code LIT-UPPER PIC X(26)} at lines 260
     * and 261 enumerate them and nothing else. That is what makes the character-range fold in
     * {@link #foldAsciiToUpperCase(String)} an exact transcription rather than an approximation.</p>
     */
    private static final char ASCII_LOWER_A = 'a';

    /**
     * The highest ASCII letter of the case the reference folds from, from lines 262 and 263.
     */
    private static final char ASCII_LOWER_Z = 'z';

    /**
     * The distance from a lower-case ASCII letter to its upper-case counterpart.
     */
    private static final int ASCII_CASE_DISTANCE = 'a' - 'A';

    /**
     * The store this service reads the card from and writes it back to.
     */
    private final CardRepository cards;

    /**
     * The anti-corruption layer that opens selectors, applies the submitted attributes and masks the
     * result.
     */
    private final CardMapper mapper;

    /**
     * Builds the service from its two collaborators.
     *
     * <p>Assumptions: both collaborators arrive through the constructor and are held final, and the
     * class keeps no mutable state of any kind. That is what lets it be exercised without a database
     * and lets several instances serve concurrently, which the reference transaction's own
     * {@code TWASIZE(0)} definition at line 369 of {@code app/csd/CARDDEMO.CSD} shows the baseline also
     * required of its program.</p>
     *
     * @param cards the store this service reads and writes; must not be {@code null}
     * @param mapper the mapper that opens selectors, applies submitted attributes and renders a stored
     *     row as a detail response; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public CardUpdateService(CardRepository cards, CardMapper mapper) {
        this.cards = Objects.requireNonNull(cards, "cards must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Applies the submitted attributes to the card the selector names, or accepts the submission
     * without writing when nothing changed.
     *
     * <p>This composes the whole migrated write path, in the reference program's own order: resolve the
     * key, read the record, test for no change, edit the four attributes, test the concurrency token,
     * then rewrite.</p>
     *
     * <p>Refactoring Rationale: the method is transactional, and that carries the reference's
     * {@code EXEC CICS SYNCPOINT} at line 470 -- searching the program for that verb returns exactly
     * one hit, so there is one commit point and this is it. The commit had to be requested explicitly
     * because the file resource itself offered no recovery: {@code app/csd/CARDDEMO.CSD} defines
     * {@code CARDDAT} with {@code RECOVERY(NONE)} at line 33 and {@code JOURNAL(NO)} at line 31, and
     * declares {@code READINTEG(UNCOMMITTED)} at line 27 and {@code STRINGS(1)} at line 28, so
     * durability and isolation were the program's business rather than the store's. The target
     * platform's relational store supplies atomicity, consistency, isolation and durability natively,
     * so the same boundary is declared once here and the store keeps it. This is a
     * platform-capability difference, and the direct consequence is that a rollback is never requested
     * by name anywhere in this class: an exception propagating out of this method is what abandons the
     * unit of work, which is the target's expression of the reference's
     * {@code SYNCPOINT ROLLBACK}.</p>
     *
     * <p>Assumptions: the propagation is stated explicitly even though it is the default, because the
     * one-commit-point property above is the whole of the mapping and a reader checking it should not
     * have to know a framework default to confirm it.</p>
     *
     * <p>Assumptions: the response is projected from the SAVED row rather than from the request, so the
     * token it carries is the one a following edit must echo and the expiry it carries is the whole
     * stored date rather than the two parts submitted. A caller can therefore edit twice in succession
     * using only what this returns.</p>
     *
     * @param cardKey the sealed selector naming the card to change, exactly as a listing row or a
     *     previous detail response carried it; must not be {@code null}
     * @param request the attributes to apply together with the concurrency token last read; must not be
     *     {@code null}
     * @return the saved card's masked detail carrying the new token, or the unchanged card's masked
     *     detail when the submission matched what was already stored; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws ClientInputException if the selector cannot be opened, or if any submitted attribute
     *     fails its edit, which the shared advice renders as HTTP 400 carrying the per-field array
     * @throws NoSuchElementException if the selector opened cleanly but no stored row holds that card
     *     number, which the shared advice renders as HTTP 404
     * @throws RecordConflictException if the submitted token is not the row's current one, which the
     *     shared advice renders as HTTP 409 carrying {@link #MESSAGE_RECORD_CHANGED}
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public CardDetail update(String cardKey, CardUpdateRequest request) {

        Objects.requireNonNull(cardKey, "cardKey must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Card stored = requireCard(resolveCardNumber(cardKey));

        // WHY : Assumptions: the no-change test runs BEFORE the four edits, and the order is the
        //       reference's own rather than a convenience. Line 680 makes the comparison and line 682
        //       sets the sentence; lines 685 to 693 then force all four attribute flags valid and leave
        //       the paragraph, so the edits at lines 698 to 708 are never reached for an unchanged
        //       submission. Evaluating the edits first would refuse a submission the reference accepts.
        // WHY : Assumptions: over HTTP the declarative constraints on CardUpdateRequest fire ahead of
        //       this method, so a submission that is both unchanged and outside a declared domain would
        //       be refused rather than short-circuited. That combination cannot arise, because an
        //       unchanged submission equals the stored row by definition, and it could only fail a
        //       declared domain if the stored row itself fell outside it. The ordering here is what
        //       makes the property hold for a caller reaching this method directly, without the
        //       request-mapping layer.
        if (hasNoChanges(request, stored)) {

            // WHY : Trade-offs: the accepted-and-not-written outcome is reported by returning the
            //       stored detail, and its sentence travels in this log line rather than in the
            //       response body. CardDetail declares seven components and none of them is a message,
            //       so there is nowhere on a success body to put one; inventing a member for it would
            //       change a published contract to carry a value the reference showed on a screen line
            //       that no longer exists. What is given up is that an API caller cannot distinguish
            //       "saved" from "nothing to save" by the body alone -- both answer the same detail,
            //       which is also what the reference screen showed. What it buys is that the outcome
            //       stays observable to an operator, and the sentence stays available verbatim to the
            //       browser tree through MESSAGE_NO_CHANGES_DETECTED.
            LOG.info("event=card.update.no-change version={} outcome=\"{}\"",
                    stored.getVersion(), MESSAGE_NO_CHANGES_DETECTED);
            return this.mapper.toDetail(stored);
        }

        AttributeStates states = editMapInputs(request);

        if (states.hasError()) {
            throw refuseAttributes(states);
        }

        checkChangeInRecord(stored, request.version());

        return writeProcessing(request, stored);
    }

    /**
     * Edits all four submitted attributes and reports every fault found, without reading or writing
     * anything.
     *
     * <p>Assumptions: this returns an ACCUMULATING array rather than stopping at the first fault, which
     * is the per-field error surface transformation rule T7 asks for, and the accumulation is the
     * reference's own behaviour rather than an addition. Each of the four gates is performed
     * unconditionally at lines 698 to 708, and each gate's every {@code GO TO} names only its OWN exit
     * -- lines 819 and 836 for the name, 858 and 871 for the status, 891 and 906 for the month, 924 and
     * 942 for the year -- so a fault in one gate does not stop the next from running and all four flags
     * can be set from one submission.</p>
     *
     * <p>Assumptions: the reference expresses the same information through the four screen-attribute
     * flags and, for a blank field only, a literal asterisk written into it. The six asterisk sites at
     * lines 1249, 1259, 1270, 1281, 1294 and 1305 are two search keys plus these four attributes and
     * there is no seventh. That marker survives here as {@link ApiError.FieldError#screenMarker()},
     * which yields {@link FieldValidationFlag#BLANK_SCREEN_MARKER} for a blank attribute and nothing
     * for a merely unacceptable one, so a renderer reproduces the asterisk without this service
     * formatting anything. Both error states drive the highlight and only the blank state adds the
     * marker, which is what lines 1263 to 1272 show for the name and its three neighbours repeat.</p>
     *
     * <p>Assumptions: the reference gates that highlight on DIFFERENT conditions for the two classes of
     * field, and the difference is exactly what separates a faithful transcription here from a
     * divergence. {@code 3300-SETUP-SCREEN-ATTRS} gives every field two independent blocks, one for the
     * unacceptable state and one for the blank state. For the four EDITABLE attributes the gate is
     * {@code CCUP-CHANGES-NOT-OK}, at lines 1264, 1269, 1275, 1280, 1288, 1293, 1299 and 1304 -- an
     * application-state test meaning "the last edit found something wrong", which maps onto "this
     * response is a validation failure carrying field errors" without loss, so those four are
     * transcribed rather than diverged. For the two SEARCH KEYS the gate is instead
     * {@code CDEMO-PGM-REENTER}, at lines 1248 and 1258, while their unacceptable blocks at lines 1243
     * and 1253 carry no gate at all. That re-entry discriminator is the pseudo-conversational coupling
     * this migration removes: it distinguished a first display from a redisplay, and a stateless handler
     * has no turn count to consult, so a key fault is reported unconditionally. That single difference is
     * the documented divergence, and it is confined to the search keys.</p>
     *
     * <p>Assumptions: the templated highlight copybook {@code app/cpy/CSSETATY.cpy} is cited as the
     * PATTERN and never as an included source. Searching each of the three card programs for it returns
     * no match, so each inlines the shape rather than copying it, and its own substitution placeholders
     * at lines 18 to 25 are what the inlined blocks stand in for. Its comment at line 17 ends in the
     * run-on token {@code blankACSHLIM}, which is present in the reference and is left as it stands.
     * Worth noting is that the copybook expresses one disjunctive test where this program uses two
     * independent blocks, and that its gate is the re-entry discriminator, matching the search keys
     * rather than the four attributes.</p>
     *
     * <p>Alternatives Considered: accepting a literal asterisk as a clear token, which is what the
     * reference screen does INBOUND. Line 588 carries a comment saying exactly that, and lines 589 to
     * 635 fold an asterisk or spaces down to low values for six fields before the edits run -- the
     * account key at 589 to 596, the card key at 598 to 605, the name at 607 to 612, the status at 614 to
     * 619, the month at 623 to 628 and the year at 630 to 635, with the day at line 621 moved
     * unconditionally and so outside the convention. It was the only way to express "this field is now
     * empty" on a fixed-width screen, where a field always holds its full width of characters. It is not
     * reproduced because an HTTP caller expresses the same intention by omitting the member or sending it
     * empty, and reproducing it would make a literal asterisk a reserved value: a client legitimately
     * sending one in an embossed name would silently get a different request than the one it wrote.</p>
     *
     * <p>Assumptions: that inbound token is strictly distinct from the OUTBOUND asterisk this method's
     * array carries, and conflating the two would mislead every later reader. The inbound one is typed
     * by a user and means "clear this"; the outbound one is written by the server at lines 1249, 1259,
     * 1270, 1281, 1294 and 1305 and marks a field that came back empty. Same character, opposite
     * direction, different mechanism. A third use of the character exists nowhere in this class: the
     * re-entry gate discussed above is a condition, not a marker.</p>
     *
     * @param request the submission to edit; must not be {@code null}
     * @return an unmodifiable array holding one entry per faulted attribute in the reference program's
     *     evaluation order, empty when all four are acceptable; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public List<ApiError.FieldError> validateAttributes(CardUpdateRequest request) {

        Objects.requireNonNull(request, "request must not be null");

        AttributeStates states = editMapInputs(request);

        List<ApiError.FieldError> faults = new ArrayList<>(4);

        // WHY : Assumptions: each entry is minted through FieldValidationFlag.toFieldError, which
        //       returns an empty optional for an acceptable attribute, so an acceptable attribute
        //       contributes nothing and the array length always equals the number of faulted
        //       attributes. Building the entries by hand and filtering afterwards would let a VALID
        //       state reach an entry, which ApiError.FieldError refuses in its compact constructor --
        //       so delegating keeps the two records' invariants agreeing instead of restating one of
        //       them.
        addFault(faults, FIELD_EMBOSSED_NAME, states.name(), nameMessage(states.name()));
        addFault(faults, FIELD_ACTIVE_STATUS, states.status(), statusMessage(states.status()));
        addFault(faults, FIELD_EXPIRATION_MONTH, states.month(), monthMessage(states.month()));
        addFault(faults, FIELD_EXPIRATION_YEAR, states.year(), yearMessage(states.year()));

        return List.copyOf(faults);
    }

    /**
     * Resolves the card key into the stored card number, carrying the search-key stage of
     * {@code 1200-EDIT-MAP-INPUTS} at lines 645 to 661.
     *
     * <p>Refactoring Rationale: the two search-key edits this stage performed have no surviving
     * field-format counterpart, and the reason is where the identity now travels rather than any
     * shortcoming in them. {@code 1210-EDIT-ACCOUNT} at lines 721 to 756 and {@code 1220-EDIT-CARD} at
     * lines 762 to 800 each check that a value typed into a fixed-width screen field is present and is
     * a run of digits of the declared width, because the platform delivered those two values as
     * eleven and sixteen unvalidated characters of a terminal datastream and nothing upstream had
     * looked at them. In the target the card is named by a sealed selector in the request path, and
     * opening that selector IS the format check: a value this service did not issue does not open, so
     * a malformed key is refused before any lookup is attempted. The account identifier is not part of
     * the lookup at all, for the reason recorded on {@link #requireCard(String)}.</p>
     *
     * <p>Assumptions: the both-keys-absent test at lines 656 to 659 likewise has no counterpart,
     * because there is exactly one key in the target and it is a required path segment -- a request
     * that omitted it would not reach this method, the request-mapping layer having no route to match.
     * The sentence that test sets is still carried, as {@link #MESSAGE_NO_INPUT_RECEIVED}.</p>
     *
     * @param cardKey the sealed selector naming the card, exactly as the client echoed it back
     * @return the sixteen-character stored card number the selector stands for, never {@code null}
     * @throws ClientInputException if the selector is not one this context issued or can no longer be
     *     opened, which the shared advice renders as HTTP 400
     * @throws NullPointerException if {@code cardKey} is {@code null}
     */
    private String resolveCardNumber(String cardKey) {

        // WHY : Assumptions: the selector is opened by the mapper rather than parsed here, because the
        //       mapper owns both halves of the sealing and its refusal deliberately quotes neither the
        //       offered value nor the sealer's account of why it did not open. A selector that fails
        //       to open is attacker-supplied text whose most common real form is a raw card number sent
        //       in the wrong field, so re-deriving the refusal here would risk copying that number into
        //       an error body and a log line, which is the one disclosure the masking boundary exists to
        //       prevent.
        return this.mapper.openCardSelector(cardKey);
    }

    /**
     * Reads the card by its key and refuses absence, carrying {@code 9000-READ-DATA} at lines 1343 to
     * 1372 together with {@code 9100-GETCARD-BYACCTCARD} at lines 1376 to 1415.
     *
     * <p>Assumptions: the keyed read uses the CARD NUMBER ALONE, and the paragraph name is not evidence
     * to the contrary. Inside it the account-identifier move is commented out at line 1379, the live
     * move at line 1380 loads only {@code WS-CARD-RID-CARDNUM}, and the read at lines 1382 to 1390
     * passes that field as its {@code RIDFLD} with {@code KEYLENGTH} taken from it at line 1385. The
     * second read, the one taken for update at lines 1427 to 1436, is keyed identically and has its own
     * commented-out account-identifier move at line 1424.</p>
     *
     * <p>Assumptions: absence faults BOTH the account key and the card key, not only the one that
     * addressed the row. Lines 1395 to 1401 set {@code INPUT-ERROR}, then
     * {@code FLG-ACCTFILTER-NOT-OK} at line 1397 AND {@code FLG-CARDFILTER-NOT-OK} at line 1398, and
     * only then latch the sentence at lines 1399 to 1401. This paragraph is structurally the same
     * paragraph as the card-detail program's read, so the two-entry array is not re-derived here: it
     * has one owner, {@link CardViewService#notFoundFieldErrors()}, and two contexts describing one
     * condition differently would be a divergence no build would notice.</p>
     *
     * <p>Assumptions: the sentence raised is {@code 88 DID-NOT-FIND-ACCTCARD-COMBO} at lines 203 and
     * 204, taken from {@link CardViewService#MESSAGE_CARD_NOT_FOUND} so that the two services carry one
     * copy. It is chosen over the superficially similar
     * {@link #MESSAGE_ACCOUNT_NOT_IN_CARDS_DATABASE} because it is the sentence this arm actually
     * latches, at lines 1399 to 1401, whereas that one has no emitter anywhere in the program.</p>
     *
     * <p>Assumptions: the failed-read arm at lines 1402 to 1411 has a latch anomaly worth recording,
     * because it runs against the pattern every other write in the program follows. Its flag write at
     * lines 1404 to 1406 sits INSIDE the first-error-wins guard while its message write at line 1411
     * sits OUTSIDE it, so a failed read replaces any sentence an earlier edit had latched. Nothing is
     * caught here for that arm: the shared advice already renders an unexpected failure as HTTP 500
     * carrying the structured abend detail its own fields correspond to, and the target reaches the
     * same outcome by a different route, a failed read abandoning the request so that no earlier
     * sentence survives to be replaced.</p>
     *
     * @param cardNumber the sixteen-character key to read
     * @return the stored row, never {@code null}
     * @throws NoSuchElementException when no stored row holds that key, which the shared advice renders
     *     as HTTP 404
     */
    private Card requireCard(String cardNumber) {

        // WHY : Trade-offs: the diagnostic names NO card number, and the omission is deliberate rather
        //       than terse. A not-found failure is rendered into a response body and a log line, so
        //       quoting the key would put a full primary account number into both, reintroducing
        //       through an error path exactly the disclosure that masking exists to prevent. What is
        //       given up is the ability to tell two absent keys apart in a log; the correlation
        //       identifier the shared filter attaches is what recovers that.
        Card found = this.cards.findById(cardNumber)
                .orElseThrow(() -> new NoSuchElementException(CardViewService.MESSAGE_CARD_NOT_FOUND));

        LOG.debug("event=card.update.read.hit version={}", found.getVersion());

        return found;
    }

    /**
     * Reports whether the submission matches the values already stored, carrying the no-change test at
     * lines 680 to 683 and the short circuit at lines 685 to 693.
     *
     * <p>Assumptions: this is a HARD requirement of the reference rather than an optimisation. Line 680
     * compares {@code FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA)} against
     * {@code FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)}, line 682 sets the sentence, and lines 685 to 693
     * then force all four attribute flags valid and leave the edit paragraph, so an unchanged
     * submission is accepted, is never edited and is never written.</p>
     *
     * <p>Assumptions: the comparison is over the fifty-nine-character group and is case-insensitive,
     * and the case-insensitivity is coherent with -- not contradicted by -- the fact that the stored
     * name keeps the case it was typed in. There are exactly three fold sites in the program. The
     * snapshot fold at lines 1356 to 1358 upper-cases the name as it is read, so the snapshot always
     * holds an upper-case name; the conflict-check fold at lines 1499 to 1501 repeats it on the freshly
     * read record so that the comparison at line 1504 is upper against upper; and this test at line 680
     * folds both sides of the group. The value WRITTEN at line 1466 is never folded --
     * {@code CCUP-NEW-CRDNAME} appears at lines 308, 609, 611, 811 to 813, 823, 1114 and 1466 and is
     * the target of no {@code INSPECT}. The net behaviour is that change detection on the name ignores
     * case while the stored value keeps the case supplied, so retyping the same name differently cased
     * yields {@link #MESSAGE_NO_CHANGES_DETECTED} and no write. Both halves are transcribed.</p>
     *
     * <p>Assumptions: the day term of the group is taken from the STORED date on both sides. The
     * reference compares {@code CCUP-NEW-EXPDAY} against {@code CCUP-OLD-EXPDAY}, and those are equal
     * in every reachable state: the day arrives from a screen field that line 1285 renders non-display
     * and that lines 1119 to 1123 always repaint from the pre-edit snapshot, so the value returned is
     * the value sent. In the target the day is not a member of the request at all, so the equality is
     * structural rather than emergent. Supplying the stored day to both sides therefore reproduces the
     * comparison the reference actually makes, and does so without duplicating the mapper's own rule
     * for which day the saved row will carry.</p>
     *
     * @param request the submission to compare; must not be {@code null}
     * @param stored the row as it currently stands; must not be {@code null}
     * @return {@code true} when every editable attribute matches what is stored, ignoring the case of
     *     the embossed name, {@code false} otherwise
     * @throws NullPointerException if the stored row carries no expiration date, which its own
     *     not-null column forbids
     */
    private static boolean hasNoChanges(CardUpdateRequest request, Card stored) {

        LocalDate storedExpiry = Objects.requireNonNull(
                stored.getExpirationDate(), "stored expiration date must not be null");

        String storedDay = zeroPadded(storedExpiry.getDayOfMonth(), EXPIRATION_DAY_WIDTH);

        String submitted = cardDataGroup(request.embossedName(), request.expirationYear(),
                request.expirationMonth(), storedDay, request.activeStatus());

        String current = cardDataGroup(stored.getEmbossedName(),
                zeroPadded(storedExpiry.getYear(), EXPIRATION_YEAR_WIDTH),
                zeroPadded(storedExpiry.getMonthValue(), EXPIRATION_MONTH_WIDTH),
                storedDay, stored.getActiveStatus());

        return foldAsciiToUpperCase(submitted).equals(foldAsciiToUpperCase(current));
    }

    /**
     * Lays five values out as the fifty-nine-character group the reference compares.
     *
     * <p>Assumptions: each part is padded or truncated to the width the reference declares, because the
     * comparison is between two fixed-width COBOL groups and a shorter value occupies its field
     * followed by spaces. Concatenating unpadded values would let two different submissions produce one
     * string -- a name of {@code "AB"} with a year of {@code "1950"} against a name of {@code "AB1950"}
     * with an empty year -- so the padding is what keeps the group's field boundaries real.</p>
     *
     * @param embossedName the name part, laid out over {@link #EMBOSSED_NAME_WIDTH} characters
     * @param expirationYear the year part, laid out over {@link #EXPIRATION_YEAR_WIDTH} characters
     * @param expirationMonth the month part, laid out over {@link #EXPIRATION_MONTH_WIDTH} characters
     * @param expirationDay the day part, laid out over {@link #EXPIRATION_DAY_WIDTH} characters
     * @param activeStatus the status part, laid out over {@link #ACTIVE_STATUS_WIDTH} characters
     * @return a string of exactly {@link #CARD_DATA_GROUP_WIDTH} characters, never {@code null}
     */
    private static String cardDataGroup(String embossedName, String expirationYear,
            String expirationMonth, String expirationDay, String activeStatus) {

        return new StringBuilder(CARD_DATA_GROUP_WIDTH)
                .append(padOrTruncate(embossedName, EMBOSSED_NAME_WIDTH))
                .append(padOrTruncate(expirationYear, EXPIRATION_YEAR_WIDTH))
                .append(padOrTruncate(expirationMonth, EXPIRATION_MONTH_WIDTH))
                .append(padOrTruncate(expirationDay, EXPIRATION_DAY_WIDTH))
                .append(padOrTruncate(activeStatus, ACTIVE_STATUS_WIDTH))
                .toString();
    }

    /**
     * Edits the four submitted attributes, carrying the field stage of
     * {@code 1200-EDIT-MAP-INPUTS} at lines 667 to 714.
     *
     * <p>Assumptions: all four gates run on every call, in the reference program's own order, because
     * lines 698 to 708 perform them unconditionally and one after another. The order is load-bearing
     * even though the gates are independent: every sentence write in them is wrapped in the
     * first-error-wins guard, so whichever gate faults first owns the summary sentence, and evaluating
     * them differently would change which sentence a caller is shown for a submission that fails
     * several.</p>
     *
     * <p>Assumptions: the pessimistic {@code SET CCUP-CHANGES-NOT-OK} at line 696 and the promotion to
     * {@code CCUP-CHANGES-OK-NOT-CONFIRMED} at lines 710 to 714 have no counterpart, because they move
     * the screen state machine on to its waiting step and the target writes in the same call. What
     * survives of them is the shape of the outcome: the submission is acceptable exactly when no gate
     * faulted, which is what {@link AttributeStates#hasError()} answers.</p>
     *
     * <p>Assumptions: the gate for the expiry DAY is absent because the reference has none. Searching
     * the program for {@code 1270-EDIT} returns no match, so the day is never validated, and it is not
     * a member of the request either.</p>
     *
     * @param request the submission to edit; must not be {@code null}
     * @return the four states in the reference program's evaluation order, never {@code null}
     */
    private static AttributeStates editMapInputs(CardUpdateRequest request) {

        return new AttributeStates(
                editName(request.embossedName()),
                editCardStatus(request.activeStatus()),
                editExpiryMonth(request.expirationMonth()),
                editExpiryYear(request.expirationYear()));
    }

    /**
     * Edits the embossed name, carrying {@code 1230-EDIT-NAME} at lines 806 to 840.
     *
     * <p>Assumptions: the outcome is expressed as one of the shared flag's SEMANTIC states and never as
     * the byte the reference field held, because the two encodings in the codebase disagree. The card
     * programs declare each triad as not-ok {@code '0'}, valid {@code '1'} and blank space -- all six
     * triads at lines 57 to 80 of this program, and the same convention at lines 62 to 68 of
     * {@code app/cbl/COCRDLIC.cbl} and lines 56 to 62 of {@code app/cbl/COCRDSLC.cbl} -- whereas the
     * date-edit copybook the shared flag is modelled on declares valid as low values, not-ok as
     * {@code '0'} and blank as {@code 'B'}, at lines 43 to 57 of {@code app/cpy/CSUTLDWY.cpy}. Only the
     * not-ok byte agrees. Returning a byte would force every reader to know which program produced it,
     * and the byte crosses no wire in the target, so the state is the only thing worth carrying and no
     * comparison against a literal flag character appears anywhere in this class.</p>
     *
     * <p>Assumptions: a further reason not to key off names is that the host field and the condition are
     * named differently and only the condition appears in logic -- {@code WS-EDIT-CARDNAME-FLAG} at
     * line 65 carries {@code FLG-CARDNAME-NOT-OK}, {@code -ISVALID} and {@code -BLANK} at lines 66 to
     * 68, so the conditions are not spelled {@code FLG-WS-EDIT-CARDNAME-FLAG-}anything.</p>
     *
     * <p>Assumptions: this is the ONLY one of the four gates whose blank arm and unacceptable arm carry
     * DIFFERENT sentences, latching {@link #MESSAGE_NAME_NOT_PROVIDED} at lines 816 and 817 against
     * {@link #MESSAGE_NAME_MUST_BE_ALPHA} at lines 833 and 834. The status, month and year gates each
     * reuse one sentence for both arms. The asymmetry is reproduced exactly under transformation rule
     * T8 rather than smoothed out.</p>
     *
     * @param embossedName the submitted name, or {@code null} when the member was absent
     * @return {@link FieldValidationFlag#BLANK} when nothing usable was submitted,
     *     {@link FieldValidationFlag#NOT_OK} when it holds anything but letters and spaces, otherwise
     *     {@link FieldValidationFlag#VALID}; never {@code null}
     */
    private static FieldValidationFlag editName(String embossedName) {

        // WHY : Assumptions: the blank arm has THREE disjuncts, not two. Lines 811 and 812 test the
        //       field against low values and against spaces, and line 813 additionally tests it against
        //       zeros, so a value made only of the digit zero is reported as ABSENT rather than as a bad
        //       name. The third disjunct is transcribed for every one of the four gates because all four
        //       carry it -- lines 811 to 813, 850 to 852, 883 to 885 and 916 to 918 -- and it is the
        //       reason the month value 00 and the year value 0000 are classified blank rather than out
        //       of range further down.
        if (FieldValidationFlag.isNeverSupplied(embossedName) || isEntirelyZeros(embossedName)) {
            return FieldValidationFlag.BLANK;
        }

        // WHY : Assumptions: the permitted set is the twenty-six upper-case letters, the twenty-six
        //       lower-case letters and the space, and nothing else -- not digits, not punctuation, not
        //       an accented letter. The reference reaches that set by a subtraction rather than by a
        //       test: line 823 copies the value into the fifty-character scratch field, lines 824 to 826
        //       replace every character of the fifty-two-letter alphabet declared at lines 255 to 257
        //       with the spaces declared at lines 258 to 259, and line 828 concludes the value was
        //       acceptable when trimming what remains leaves nothing. Any residue is therefore a
        //       character that was neither a letter nor a space.
        if (!containsOnlyLettersAndSpaces(embossedName)) {
            return FieldValidationFlag.NOT_OK;
        }

        return FieldValidationFlag.VALID;
    }

    /**
     * Edits the active status, carrying {@code 1240-EDIT-CARDSTATUS} at lines 845 to 873.
     *
     * <p>Assumptions: one sentence serves both arms of this gate, because the reference latches
     * {@code 88 CARD-STATUS-MUST-BE-YES-NO} twice -- at lines 855 and 856 for an absent value and again
     * at lines 868 and 869 for a value outside the set. The flag states still differ, blank against
     * not-ok, so the two arms remain distinguishable by the asterisk marker even though their text is
     * identical.</p>
     *
     * @param activeStatus the submitted status, or {@code null} when the member was absent
     * @return {@link FieldValidationFlag#BLANK} when nothing usable was submitted,
     *     {@link FieldValidationFlag#NOT_OK} when it is not exactly one of the two permitted values,
     *     otherwise {@link FieldValidationFlag#VALID}; never {@code null}
     */
    private static FieldValidationFlag editCardStatus(String activeStatus) {

        if (FieldValidationFlag.isNeverSupplied(activeStatus) || isEntirelyZeros(activeStatus)) {
            return FieldValidationFlag.BLANK;
        }

        // WHY : Assumptions: the width is checked as well as the membership, because the reference moves
        //       the submitted value into a one-character field at line 861 before testing the condition
        //       at line 863, so a longer value is truncated to its first character by the move and only
        //       that character is ever tested. Over HTTP a longer value arrives whole, so testing
        //       membership alone would accept a two-character value whose first character happened to be
        //       acceptable -- which is input the screen could not have produced.
        if (activeStatus.length() != ACTIVE_STATUS_WIDTH) {
            return FieldValidationFlag.NOT_OK;
        }

        char submitted = activeStatus.charAt(0);

        if (submitted != ACTIVE_STATUS_YES && submitted != ACTIVE_STATUS_NO) {
            return FieldValidationFlag.NOT_OK;
        }

        return FieldValidationFlag.VALID;
    }

    /**
     * Edits the expiry month, carrying {@code 1250-EDIT-EXPIRY-MON} at lines 877 to 908.
     *
     * <p>Assumptions: the month value {@code 00} is classified BLANK and not out of range, because the
     * zeros disjunct of the blank arm at line 885 is evaluated before the range test at line 898 is ever
     * reached. The sentence a caller sees is the same either way, {@code 88
     * CARD-EXPIRY-MONTH-NOT-VALID} serving both arms at lines 889 and 904, but the flag state is not:
     * blank earns the asterisk marker and out-of-range does not, so the two are not interchangeable and
     * the ordering has to be preserved.</p>
     *
     * <p>Alternatives Considered: checking that the assembled year, month and day form a real calendar
     * date, by delegating to {@code com.carddemo.common.validation.DateEditValidator}. The reference
     * card path runs no calendar check whatsoever: none of the three card programs copies
     * {@code app/cpy/CSUTLDPY.cpy}, whose only includer repository-wide is line 4232 of
     * {@code app/cbl/COACTUPC.cbl}, nor {@code app/cpy/CSUTLDWY.cpy}, whose two includers are line 166
     * of that program and line 76 of
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}. Delegating is rejected on two independent
     * grounds. It would introduce a refusal the reference never issues, over the one part of the date a
     * caller cannot even submit; and there is nothing left for it to reject, because the mapper settles
     * the only combination that could be impossible -- a stored day absent from the submitted month, a
     * stored thirty-first against a submitted February -- by bringing the day back to that month's last
     * day, so the date reaching the store is always real. Hand-rolling the arithmetic here was rejected
     * outright: month length and the leap year are properties of a whole date and belong to the shared
     * validator that owns them for every context, never to a second copy in a service. The reference
     * concatenates characters into a ten-byte field at lines 1467 to 1474 without consulting a
     * calendar, so this is a documented divergence under transformation rule T9 and not a
     * transcription.</p>
     *
     * @param expirationMonth the submitted month, or {@code null} when the member was absent
     * @return {@link FieldValidationFlag#BLANK} when nothing usable was submitted,
     *     {@link FieldValidationFlag#NOT_OK} when it is not two digits inside the permitted range,
     *     otherwise {@link FieldValidationFlag#VALID}; never {@code null}
     */
    private static FieldValidationFlag editExpiryMonth(String expirationMonth) {

        if (FieldValidationFlag.isNeverSupplied(expirationMonth) || isEntirelyZeros(expirationMonth)) {
            return FieldValidationFlag.BLANK;
        }

        // WHY : Assumptions: the test is an exact-width all-digit test AND a range test, because the
        //       reference gets both from one move plus one condition. Line 896 moves the two-character
        //       value into CARD-MONTH-CHECK, whose numeric redefinition at lines 93 and 94 is what line
        //       898 evaluates 88 VALID-MONTH VALUES 1 THRU 12 over. The move is what imposes the width
        //       and the digits; the condition is what imposes the range. Over HTTP a short value arrives
        //       short rather than space-padded, so a range test alone would accept input the screen
        //       refused.
        if (!isDigitsInRange(expirationMonth, EXPIRATION_MONTH_WIDTH,
                MINIMUM_EXPIRY_MONTH, MAXIMUM_EXPIRY_MONTH)) {
            return FieldValidationFlag.NOT_OK;
        }

        return FieldValidationFlag.VALID;
    }

    /**
     * Edits the expiry year, carrying {@code 1260-EDIT-EXPIRY-YEAR} at lines 913 to 944.
     *
     * <p>Assumptions: this gate is structurally ASYMMETRIC against the other three and the asymmetry is
     * reproduced rather than normalised. The other three open with a pessimistic pre-set -- lines 808,
     * 847 and 880 -- and test for a blank value afterwards. This one tests for a blank value FIRST, at
     * lines 916 to 918, and reaches its pessimistic pre-set only at line 930, after that arm has
     * returned. The order of the two blocks below follows the reference for that reason.</p>
     *
     * <p>Assumptions: the difference is not observable, and saying so is part of reproducing it
     * honestly. A COBOL pre-set is a write to a flag that a later arm overwrites, so in all four gates
     * the flag ends blank for an absent value, not-ok for an unacceptable one and valid otherwise; no
     * reachable state distinguishes the two orderings. What is preserved here is the structure a reader
     * comparing this method against line 930 will look for, not a behavioural difference, and nothing in
     * this class depends on the position.</p>
     *
     * <p>Assumptions: the year value {@code 0000} is classified BLANK by the zeros disjunct at line 918,
     * exactly as {@code 00} is for the month, and the single sentence
     * {@code 88 CARD-EXPIRY-YEAR-NOT-VALID} serves both arms at lines 922 and 940.</p>
     *
     * @param expirationYear the submitted year, or {@code null} when the member was absent
     * @return {@link FieldValidationFlag#BLANK} when nothing usable was submitted,
     *     {@link FieldValidationFlag#NOT_OK} when it is not four digits inside the permitted window,
     *     otherwise {@link FieldValidationFlag#VALID}; never {@code null}
     */
    private static FieldValidationFlag editExpiryYear(String expirationYear) {

        if (FieldValidationFlag.isNeverSupplied(expirationYear) || isEntirelyZeros(expirationYear)) {
            return FieldValidationFlag.BLANK;
        }

        // WHY : Assumptions: the pessimistic default belongs HERE, after the blank arm, which is where
        //       line 930 puts it and one block later than lines 808, 847 and 880 put theirs. The window
        //       is this program's own 88 VALID-YEAR VALUES 1950 THRU 2099 at line 99, evaluated at line
        //       934 over the numeric redefinition declared at lines 97 and 98; the date-edit copybook
        //       declares no such window, so it could not have supplied one even had the program copied
        //       it.
        if (!isDigitsInRange(expirationYear, EXPIRATION_YEAR_WIDTH,
                MINIMUM_EXPIRY_YEAR, MAXIMUM_EXPIRY_YEAR)) {
            return FieldValidationFlag.NOT_OK;
        }

        return FieldValidationFlag.VALID;
    }

    /**
     * Builds the refusal for a submission whose attributes did not pass their edits.
     *
     * <p>Trade-offs: the shared refusal type carries one validation state for every field it names, and
     * its own contract reserves the multi-field form for members that fail together for the same
     * reason. This method therefore names EVERY faulted attribute only when their states agree, and
     * otherwise names the first attribute to fault in the reference program's evaluation order. What is
     * given up is that a submission faulting one attribute as absent and another as unacceptable
     * reports only the first, even though the reference would have highlighted both fields. What that
     * buys is that no field is ever labelled with a state that is not its own, which would misdirect the
     * asterisk marker; and the complete picture stays available through
     * {@link #validateAttributes(CardUpdateRequest)}, which is what a caller wanting all of them should
     * read.</p>
     *
     * @param states the four states the edits returned; must not be {@code null}
     * @return the refusal to throw, never {@code null}
     */
    private static ClientInputException refuseAttributes(AttributeStates states) {

        String summary = summaryMessage(states);

        LOG.info("event=card.update.refused name={} status={} month={} year={}",
                states.name(), states.status(), states.month(), states.year());

        List<String> agreeing = states.fieldsSharingSingleErrorState();

        // WHY : Trade-offs: the states are compared for EQUALITY to decide whether several attributes
        //       may be named at once, rather than simply naming every faulted attribute. The shared
        //       refusal type carries one state for all the members it names, so naming attributes whose
        //       states differ would label at least one of them with a state that is not its own and
        //       misdirect the blank marker. What is given up is that a mixed failure reports one
        //       attribute instead of several; what it buys is that no reported state is ever wrong, and
        //       validateAttributes(CardUpdateRequest) still answers the complete set.
        if (!agreeing.isEmpty()) {
            return new ClientInputException(ATTRIBUTE_REFUSAL_CODE, agreeing,
                    states.firstErrorState(), summary);
        }

        return new ClientInputException(ATTRIBUTE_REFUSAL_CODE, states.firstErrorField(),
                states.firstErrorState(), summary);
    }

    /**
     * Chooses the one summary sentence the reference would have displayed.
     *
     * <p>Assumptions: the precedence is the reference program's own, and it is first-fault-wins in the
     * gates' evaluation order. Every sentence write in the field stage is wrapped in
     * {@code IF WS-RETURN-MSG-OFF} -- lines 816, 833, 855, 868, 888, 903, 921 and 939 -- and the gates
     * are performed name, status, month, year at lines 698 to 708, so the name's sentence wins over the
     * status's and so on down. Unlike the search-key stage there is no unguarded cross-field write to
     * override the ordering, the one such write in the program being the both-keys-absent case at line
     * 658, which belongs to the stage that does not survive.</p>
     *
     * @param states the four states the edits returned; must not be {@code null}
     * @return the reference sentence for the first faulted attribute, or
     *     {@link #MESSAGE_DETAILS_SHOWN} when all four are acceptable; never {@code null}
     */
    private static String summaryMessage(AttributeStates states) {

        if (states.name().isError()) {
            return nameMessage(states.name());
        }
        if (states.status().isError()) {
            return statusMessage(states.status());
        }
        if (states.month().isError()) {
            return monthMessage(states.month());
        }
        if (states.year().isError()) {
            return yearMessage(states.year());
        }
        return MESSAGE_DETAILS_SHOWN;
    }

    /**
     * Resolves the reference sentence belonging to one embossed-name state.
     *
     * @param state the state the name edit returned; must not be {@code null}
     * @return the reference sentence for that state; never {@code null} and never blank
     */
    private static String nameMessage(FieldValidationFlag state) {

        // WHY : Assumptions: this switch has two distinct error sentences where the three that follow
        //       have one, and that is the reference's own asymmetry rather than an oversight here. Lines
        //       817 and 834 latch different conditions; lines 856 and 869, 889 and 904, and 922 and 940
        //       each latch the same condition twice.
        return switch (state) {
            case BLANK -> MESSAGE_NAME_NOT_PROVIDED;
            case NOT_OK -> MESSAGE_NAME_MUST_BE_ALPHA;
            case VALID -> MESSAGE_DETAILS_SHOWN;
        };
    }

    /**
     * Resolves the reference sentence belonging to one active-status state.
     *
     * @param state the state the status edit returned; must not be {@code null}
     * @return the reference sentence for that state; never {@code null} and never blank
     */
    private static String statusMessage(FieldValidationFlag state) {

        return switch (state) {
            case BLANK, NOT_OK -> MESSAGE_STATUS_MUST_BE_YES_NO;
            case VALID -> MESSAGE_DETAILS_SHOWN;
        };
    }

    /**
     * Resolves the reference sentence belonging to one expiry-month state.
     *
     * @param state the state the month edit returned; must not be {@code null}
     * @return the reference sentence for that state; never {@code null} and never blank
     */
    private static String monthMessage(FieldValidationFlag state) {

        return switch (state) {
            case BLANK, NOT_OK -> MESSAGE_EXPIRY_MONTH_NOT_VALID;
            case VALID -> MESSAGE_DETAILS_SHOWN;
        };
    }

    /**
     * Resolves the reference sentence belonging to one expiry-year state.
     *
     * @param state the state the year edit returned; must not be {@code null}
     * @return the reference sentence for that state; never {@code null} and never blank
     */
    private static String yearMessage(FieldValidationFlag state) {

        return switch (state) {
            case BLANK, NOT_OK -> MESSAGE_EXPIRY_YEAR_NOT_VALID;
            case VALID -> MESSAGE_DETAILS_SHOWN;
        };
    }

    /**
     * Appends one per-field entry to the accumulating array when the state is an error state.
     *
     * @param faults the array being accumulated; must not be {@code null}
     * @param field the response-field identity to name; must not be {@code null}
     * @param state the state the field's edit returned; must not be {@code null}
     * @param message the reference sentence for that state; must not be {@code null} or blank
     */
    private static void addFault(List<ApiError.FieldError> faults, String field,
            FieldValidationFlag state, String message) {

        state.toFieldError(field, message).map(ApiError.FieldError::from).ifPresent(faults::add);
    }

    /**
     * Refuses the edit when the row moved on since the caller read it, carrying
     * {@code 9300-CHECK-CHANGE-IN-REC} at lines 1498 to 1520 and the guard at lines 1455 to 1457.
     *
     * <p>Assumptions: the reference compares SIX fields, listed at lines 1503 to 1508 -- the
     * verification value, the embossed name, then the year, month and day read out of the stored date
     * by the reference modifications {@code (1:4)}, {@code (6:2)} and {@code (9:2)}, then the active
     * status. It does NOT compare the account identifier, because the two identity halves of the
     * snapshot are seeded from the INPUT at lines 1346 and 1347 rather than from the record, so
     * comparing them would compare the input against itself. The target reaches the same detection over
     * the same window with one row version, which covers every column rather than the six the reference
     * enumerated.</p>
     *
     * <p>Assumptions: the refusal reports the row's CURRENT version, and that is the target's form of
     * something the reference does deliberately. On detecting the conflict the reference does not merely
     * refuse: lines 1512 to 1517 refresh all six snapshot fields FROM the freshly read record before
     * branching away at line 1518, so the screen it redisplays shows the server's current state and the
     * operator can resubmit against it. Passing the current version into the failure is what carries
     * that across -- the shared advice turns a non-null version into an entry in the response's
     * per-field array -- so a client can re-render and resubmit without a second round trip to discover
     * what it should have sent.</p>
     *
     * <p>Assumptions: an absent token is treated as a conflict rather than as a bad request, because a
     * caller that cannot name the version it read has not established that it read one. The member is
     * declared not-null on the request in any case, so over HTTP this arm is reached only by a caller
     * invoking the service directly.</p>
     *
     * @param stored the row as it currently stands; must not be {@code null}
     * @param submitted the token the caller echoed back, which may be {@code null}
     * @throws RecordConflictException if the token is absent or is not the row's current version, which
     *     the shared advice renders as HTTP 409 carrying {@link #MESSAGE_RECORD_CHANGED}
     */
    private static void checkChangeInRecord(Card stored, Integer submitted) {

        int current = stored.getVersion();

        if (submitted != null && submitted.intValue() == current) {
            return;
        }

        // WHY : Trade-offs: the diagnostic records that a conflict was declared and the version the row
        //       actually holds, and deliberately not the version that was submitted. A submitted value
        //       is caller-supplied and adds nothing an operator can act on, whereas the current value is
        //       what a support conversation needs. Neither is personal data, so the choice is about
        //       usefulness rather than disclosure.
        LOG.info("event=card.update.conflict reason=stale-version current={}", current);

        // WHY : Refactoring Rationale: the outcome and the sentence are raised as two separate things
        //       here, where the reference held them as one. Its 88 DATA-WAS-CHANGED-BEFORE-UPDATE at
        //       lines 207 and 208 is a message-VALUE condition on WS-RETURN-MSG PIC X(75), so the SET at
        //       line 1511 records the outcome and writes the operator-visible text in a single act --
        //       the platform gave a 3270 screen line as the only output channel, so one field was all
        //       there was to record either in. The target answers a browser application and a
        //       programmatic client from one endpoint and has a transport status as well as a body, so
        //       the kind below chooses the status and MESSAGE_RECORD_CHANGED carries the text.
        // WHY : Assumptions: no rollback is requested here, and none is requested anywhere in this
        //       class. Letting this propagate out of the transactional entry point is what abandons the
        //       unit of work, which is the target's expression of the reference's SYNCPOINT ROLLBACK. A
        //       manual rollback call would additionally mark the transaction dead while returning
        //       normally, which would hide the refusal from the caller.
        throw new RecordConflictException(RecordConflictException.Kind.STALE_VERSION, (long) current);
    }

    /**
     * Applies the submission and rewrites the row, carrying {@code 9200-WRITE-PROCESSING} at lines 1420
     * to 1493.
     *
     * <p>Assumptions: three columns are written and no others -- the embossed name, the active status
     * and the expiration date -- and the three that the reference also touches are deliberately left
     * alone. Each is a documented divergence registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}, and each is stated as what the
     * reference does against what the Java implements rather than as a judgement on either.</p>
     *
     * <p>Assumptions: the stored VERIFICATION VALUE is left untouched. What the reference does: line
     * 1464 moves {@code CCUP-NEW-CVV-CD} into a scratch field and line 1465 moves its numeric
     * redefinition into the record, so a value derived from that field is written on every rewrite. That
     * field is written by nothing: searching the program for it returns exactly two lines, its
     * declaration at 306 and its use as a source at 1464, and the only thing that ever sets it is the
     * {@code INITIALIZE CCUP-NEW-DETAILS} at line 586, so it holds spaces. Its old-value counterpart is
     * different in kind -- line 1354 populates {@code CCUP-OLD-CVV-CD} from the record and lines 1503
     * and 1512 compare and refresh it -- which is why the two must not be read as a pair. What the Java
     * implements: {@link CardUpdateRequest} carries no verification-value member, so the column is not
     * reachable from this operation and {@link CardMapper#applyUpdate(CardUpdateRequest, Card)} writes
     * nothing to it.</p>
     *
     * <p>Assumptions: the stored ACCOUNT IDENTIFIER is left untouched. What the reference does: line
     * 1463 moves {@code CC-ACCT-ID-N}, the value that arrived from the screen, into the record on every
     * rewrite, and it never checks that value against the record's own account identifier -- the record
     * field is read and compared nowhere, the only occurrences of that name in the program being an
     * unrelated local scratch pair at lines 104 and 105, and the six-field comparison at lines 1503 to
     * 1508 does not include it. What the Java implements: the identifier is absent from
     * {@link CardUpdateRequest} and {@link Card} exposes no mutator for it, so the stored value is
     * preserved structurally rather than by a check.</p>
     *
     * <p>Alternatives Considered: carrying the account identifier in the request and refusing a
     * submission whose value did not match the row. It is rejected because a member that must equal the
     * stored value carries no information, so the refusal would exist only to answer a request that
     * should never have been expressible; and because the identifier is already not editable from a card
     * screen, so accepting it would publish an editable-looking member that is not. Omitting it removes
     * the need to enforce anything about it, which is smaller and more durable than enforcing it
     * correctly.</p>
     *
     * <p>Assumptions: the stored expiry DAY is preserved. What the reference does: line 621 moves the
     * day input into the new-value group unconditionally, outside the clear-token convention its
     * neighbours at lines 623 to 628 and 630 to 635 each receive, and the {@code STRING} at lines 1467
     * to 1474 composes {@code CCUP-NEW-EXPYEAR}, {@code CCUP-NEW-EXPMON} and {@code CCUP-NEW-EXPDAY}
     * into the ten-character stored date -- so the day WRITTEN is the day that came back from the map,
     * not the snapshot day. It equals the stored day all the same, because line 1285 renders that field
     * non-display and lines 1119 to 1123 always repaint it from {@code CCUP-OLD-EXPDAY}, line 1122 which
     * would have repainted the typed day being commented out. The reference holds the invariant
     * emergently. What the Java implements: the day is not a member of the request, and the mapper takes
     * it from the row, so the invariant is structural.</p>
     *
     * <p>Assumptions: the embossed name is written in the case it was submitted in, which is what line
     * 1466 does -- it is the one place the name is moved into the record and it applies no fold. The
     * three fold sites are enumerated on {@link #hasNoChanges(CardUpdateRequest, Card)}, and none of
     * them touches the value being written.</p>
     *
     * <p>Assumptions: nothing is caught here. The reference tests the rewrite's response at lines 1488
     * to 1492 and sets {@code 88 LOCKED-BUT-UPDATE-FAILED} when it was not normal, because a
     * command-level API reports by response code and the program had to inspect it. The target raises
     * instead, and the shared advice already renders a failed write -- including a contention the
     * provider detects in the window between the check above and this call -- so catching it here would
     * duplicate that mapping in a second place. The sentence that arm sets is carried as
     * {@link #MESSAGE_UPDATE_FAILED}.</p>
     *
     * @param request the validated submission to apply; must not be {@code null}
     * @param stored the row to apply it onto; must not be {@code null}
     * @return the saved row's masked detail carrying the new token, never {@code null}
     */
    private CardDetail writeProcessing(CardUpdateRequest request, Card stored) {

        // WHY : Assumptions: the loaded row is mutated and saved rather than a replacement being
        //       constructed, so the provider writes the columns that changed and every column this
        //       operation does not mention keeps its stored value. The reference reaches the opposite
        //       way round -- line 1461 clears a whole record area with INITIALIZE and lines 1462 to 1475
        //       then repopulate every field of it, which is why a field nothing populated, the
        //       verification value, still reached the store. Mutating in place is what makes the three
        //       untouched columns untouched by construction.
        // WHY : Assumptions: the write is flushed within the transaction rather than left to the
        //       commit, so a contention the provider detects surfaces from this call while the request
        //       is still being served and can be rendered as a conflict. Deferring it to commit time
        //       would raise after the handler had returned, where the shared advice can no longer shape
        //       the response.
        Card saved = this.cards.saveAndFlush(this.mapper.applyUpdate(request, stored));

        // WHY : Trade-offs: the diagnostic records the new token and none of the values written. The
        //       embossed name identifies a cardholder and the expiry completes part of a card
        //       credential, so both are withheld from a durable store here for the same reason the
        //       request record withholds them from its own rendering. What is given up is an audit line
        //       naming what changed; the row's own version, incremented and recorded here, is what lets
        //       a change be located afterwards.
        LOG.info("event=card.updated version={}", saved.getVersion());

        return this.mapper.toDetail(saved);
    }

    /**
     * Reports whether a value is made entirely of the digit zero.
     *
     * <p>Assumptions: this is the target reading of the third disjunct each of the four blank arms
     * carries, the {@code EQUAL ZEROS} tests at lines 813, 852, 885 and 918. In COBOL those compare a
     * fixed-width field against a zero-filled figurative constant, so any all-zero value satisfies them
     * whatever its width, which is why the test here is per-character rather than a comparison against
     * one zero-filled string of the declared width.</p>
     *
     * @param value the value to inspect, which may be {@code null}
     * @return {@code true} when the value is non-empty and every character is the digit zero,
     *     {@code false} otherwise, including for {@code null}
     */
    private static boolean isEntirelyZeros(String value) {

        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a value holds only ASCII letters of either case and the space character.
     *
     * <p>Alternatives Considered: a compiled regular expression, and
     * {@link Character#isLetter(char)}. The pattern was rejected because this predicate sits on a
     * validation path taken by every submission and a pattern match allocates where a character scan
     * does not. The library predicate was rejected for a correctness reason rather than a cost one: it
     * accepts letters of every Unicode script, so an accented or non-Latin letter would satisfy it while
     * failing the reference test, whose alphabet at lines 255 to 257 enumerates exactly the fifty-two
     * ASCII letters and nothing more.</p>
     *
     * @param value the value to inspect; must not be {@code null}
     * @return {@code true} when every character is an ASCII letter or a space, {@code false} otherwise
     */
    private static boolean containsOnlyLettersAndSpaces(String value) {

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean acceptable = character == ' '
                    || (character >= 'A' && character <= 'Z')
                    || (character >= ASCII_LOWER_A && character <= ASCII_LOWER_Z);
            if (!acceptable) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a value is exactly the given number of ASCII digits and falls inside a range.
     *
     * <p>Assumptions: the width and the range are tested together because the reference obtains them
     * from two statements that always appear together -- a {@code MOVE} into a fixed-width field with a
     * numeric redefinition, at lines 896 and 932, followed by a class condition evaluated over that
     * redefinition, at lines 898 and 934. Separating them would accept a value of the wrong width whose
     * numeric reading happened to fall in range, which the screen's own field width made
     * unreachable.</p>
     *
     * <p>Alternatives Considered: {@link Integer#parseInt(String)} after a width check. It is rejected
     * because it accepts a leading sign and would therefore read {@code "+5"} as five, and because it
     * reports by throwing on input this predicate is expected to answer {@code false} for -- so the
     * call would have to be wrapped in a catch used for ordinary control flow. Accumulating the digits
     * by hand answers both concerns and cannot overflow at the widths involved, which are two and
     * four.</p>
     *
     * @param value the value to inspect, which may be {@code null}
     * @param width the exact number of digits required
     * @param minimum the lowest acceptable numeric value, inclusive
     * @param maximum the highest acceptable numeric value, inclusive
     * @return {@code true} when the value is exactly {@code width} ASCII digits reading as a number
     *     between {@code minimum} and {@code maximum} inclusive, {@code false} otherwise
     */
    private static boolean isDigitsInRange(String value, int width, int minimum, int maximum) {

        if (value == null || value.length() != width) {
            return false;
        }

        int accumulated = 0;

        for (int index = 0; index < width; index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
            accumulated = accumulated * 10 + (character - '0');
        }

        return accumulated >= minimum && accumulated <= maximum;
    }

    /**
     * Folds the twenty-six lower-case ASCII letters of a value to upper case and leaves everything else
     * alone.
     *
     * <p>Alternatives Considered: {@link String#toUpperCase()}, and the same call with a fixed locale.
     * The no-argument form is rejected outright because it folds using the default locale, and in a
     * Turkish locale it maps a lower-case {@code i} to a dotted capital, so two names differing only in
     * the case of that one letter would compare unequal on one deployment and equal on another -- a
     * no-change test whose answer depended on a host setting. Supplying a fixed locale removes that
     * hazard but still folds every script's letters, which is wider than the reference. The reference
     * folds exactly the twenty-six letters {@code LIT-LOWER PIC X(26)} at lines 262 and 263 enumerates,
     * onto the twenty-six {@code LIT-UPPER} names at lines 260 and 261, so a character-range fold is the
     * exact transcription and the two library forms are both approximations of it.</p>
     *
     * @param value the value to fold; must not be {@code null}
     * @return the value with every lower-case ASCII letter replaced by its upper-case counterpart and
     *     every other character unchanged; never {@code null}
     */
    private static String foldAsciiToUpperCase(String value) {

        char[] folded = value.toCharArray();

        for (int index = 0; index < folded.length; index++) {
            char character = folded[index];
            if (character >= ASCII_LOWER_A && character <= ASCII_LOWER_Z) {
                folded[index] = (char) (character - ASCII_CASE_DISTANCE);
            }
        }

        return new String(folded);
    }

    /**
     * Lays a value out over a fixed width, padding it with spaces or truncating it to fit.
     *
     * <p>Assumptions: a short value is padded on the RIGHT and a long one is truncated from the right,
     * which is what a COBOL alphanumeric {@code MOVE} into a shorter or longer field does. An absent
     * value becomes the whole field of spaces, matching the state a group cleared by
     * {@code INITIALIZE} at line 586 is left in.</p>
     *
     * @param value the value to lay out, which may be {@code null}
     * @param width the exact number of characters the result must occupy
     * @return a string of exactly {@code width} characters, never {@code null}
     */
    private static String padOrTruncate(String value, int width) {

        String source = value == null ? "" : value;

        if (source.length() == width) {
            return source;
        }
        if (source.length() > width) {
            return source.substring(0, width);
        }

        StringBuilder padded = new StringBuilder(width).append(source);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * Renders a whole number as a fixed-width run of ASCII digits with leading zeros.
     *
     * <p>Alternatives Considered: {@link String#format(String, Object...)} with a zero-padded integer
     * conversion. It is rejected because that conversion formats using the default locale, and a locale
     * whose decimal digits are not ASCII would render digits that neither compare equal to the
     * submitted value nor read back as a number -- turning a no-change test into a host-dependent
     * answer. Building the digits directly cannot vary by locale.</p>
     *
     * @param value the whole number to render; must not be negative
     * @param width the exact number of digits the result must occupy
     * @return a string of exactly {@code width} ASCII digits, never {@code null}
     * @throws IllegalArgumentException if the value is negative or needs more than {@code width} digits,
     *     neither of which a date component drawn from a stored date can be
     */
    private static String zeroPadded(int value, int width) {

        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative, but was " + value);
        }

        StringBuilder digits = new StringBuilder(width);
        int remaining = value;

        for (int position = 0; position < width; position++) {
            digits.append((char) ('0' + remaining % 10));
            remaining /= 10;
        }

        if (remaining != 0) {
            throw new IllegalArgumentException(
                    "value " + value + " does not fit in " + width + " digits");
        }

        return digits.reverse().toString();
    }

    /**
     * The four states the field-stage edits returned, in the reference program's evaluation order.
     *
     * <p>Assumptions: the four are carried together rather than returned one at a time because the
     * reference performs all four gates before deciding anything -- lines 698 to 708 -- and the decision
     * at lines 710 to 714 reads the accumulated result. A method returning one state at a time would
     * make an early exit the natural shape and lose the accumulation transformation rule T7
     * requires.</p>
     *
     * @param name the state the embossed-name gate returned
     * @param status the state the active-status gate returned
     * @param month the state the expiry-month gate returned
     * @param year the state the expiry-year gate returned
     */
    private record AttributeStates(FieldValidationFlag name, FieldValidationFlag status,
            FieldValidationFlag month, FieldValidationFlag year) {

        /**
         * Rejects a partially populated set of states.
         *
         * <p>Assumptions: none of the four may be absent, because every gate returns one of the three
         * semantic states unconditionally and an absent state would mean a gate had not run. Refusing
         * here is what keeps {@link #hasError()} total: it reads all four, so a null would surface as a
         * failure inside the decision rather than at the point the set was built.</p>
         *
         * @param name the state the embossed-name gate returned; must not be {@code null}
         * @param status the state the active-status gate returned; must not be {@code null}
         * @param month the state the expiry-month gate returned; must not be {@code null}
         * @param year the state the expiry-year gate returned; must not be {@code null}
         * @throws NullPointerException if any of the four states is {@code null}
         */
        private AttributeStates {
            Objects.requireNonNull(name, "name state must not be null");
            Objects.requireNonNull(status, "status state must not be null");
            Objects.requireNonNull(month, "month state must not be null");
            Objects.requireNonNull(year, "year state must not be null");
        }

        /**
         * Reports whether any gate faulted.
         *
         * <p>Assumptions: this is the target reading of {@code IF INPUT-ERROR} at line 710, which the
         * reference evaluates after all four gates have run. Any single fault is enough, the gates
         * having each raised the shared input-error flag rather than a per-field one.</p>
         *
         * @return {@code true} when at least one of the four states is an error state, {@code false}
         *     when all four are acceptable
         */
        private boolean hasError() {
            return this.name.isError() || this.status.isError()
                    || this.month.isError() || this.year.isError();
        }

        /**
         * Names the first gate to fault, in the reference program's evaluation order.
         *
         * @return the response-field identity of the first faulted attribute, never {@code null}
         * @throws IllegalStateException if no gate faulted, which its only caller has already excluded
         */
        private String firstErrorField() {
            if (this.name.isError()) {
                return FIELD_EMBOSSED_NAME;
            }
            if (this.status.isError()) {
                return FIELD_ACTIVE_STATUS;
            }
            if (this.month.isError()) {
                return FIELD_EXPIRATION_MONTH;
            }
            if (this.year.isError()) {
                return FIELD_EXPIRATION_YEAR;
            }
            throw new IllegalStateException("no attribute faulted, so none can be named");
        }

        /**
         * Reports the state of the first gate to fault, in the reference program's evaluation order.
         *
         * @return the state of the first faulted attribute, never {@code null} and never
         *     {@link FieldValidationFlag#VALID}
         * @throws IllegalStateException if no gate faulted, which its only caller has already excluded
         */
        private FieldValidationFlag firstErrorState() {
            if (this.name.isError()) {
                return this.name;
            }
            if (this.status.isError()) {
                return this.status;
            }
            if (this.month.isError()) {
                return this.month;
            }
            if (this.year.isError()) {
                return this.year;
            }
            throw new IllegalStateException("no attribute faulted, so no state can be reported");
        }

        /**
         * Names every faulted attribute when they all failed in the same way, and nothing otherwise.
         *
         * <p>Assumptions: the shared refusal type carries one validation state for all the fields it
         * names, so several fields may be named together only when their states agree. When they
         * disagree an empty result is returned and the caller names the first faulted attribute alone,
         * which is what keeps a field from being labelled with a state that is not its own and
         * misdirecting the asterisk marker.</p>
         *
         * @return an unmodifiable list of two or more field identities sharing one error state, or an
         *     empty list when fewer than two attributes faulted or their states differ; never
         *     {@code null}
         */
        private List<String> fieldsSharingSingleErrorState() {

            List<String> faulted = new ArrayList<>(4);
            FieldValidationFlag shared = null;

            if (this.name.isError()) {
                faulted.add(FIELD_EMBOSSED_NAME);
                shared = this.name;
            }
            if (this.status.isError()) {
                if (shared != null && shared != this.status) {
                    return List.of();
                }
                faulted.add(FIELD_ACTIVE_STATUS);
                shared = this.status;
            }
            if (this.month.isError()) {
                if (shared != null && shared != this.month) {
                    return List.of();
                }
                faulted.add(FIELD_EXPIRATION_MONTH);
                shared = this.month;
            }
            if (this.year.isError()) {
                if (shared != null && shared != this.year) {
                    return List.of();
                }
                faulted.add(FIELD_EXPIRATION_YEAR);
            }

            return faulted.size() < 2 ? List.of() : List.copyOf(faulted);
        }
    }
}
