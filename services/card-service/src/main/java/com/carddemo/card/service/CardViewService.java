package com.carddemo.card.service;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one card's detail, addressed by its opaque selector, by its number, or by the two screen
 * filters the reference transaction edits before it reads.
 *
 * <p>This is the migrated form of the card-detail program {@code app/cbl/COCRDSLC.cbl} (887 lines),
 * reached as CICS transaction {@code CCDL} whose definition sits at lines 347 and 348 of
 * {@code app/csd/CARDDEMO.CSD}. Its single file read becomes a keyed repository read under
 * transformation rule T5, and its two field edits become the gates on
 * {@link #view(String, String)}.</p>
 *
 * <h2>Which reference paragraph each method carries</h2>
 *
 * <p>Transformation plan section 0.4.3 asks that each significant paragraph become a named method so
 * that the traceability matrix can cite paragraph-to-method pairs. The mapping this class
 * implements:</p>
 *
 * <ul>
 *   <li>{@code 2200-EDIT-MAP-INPUTS} lines 608 to 645, including the cross-field test at lines 637
 *       to 640, becomes {@link #validateFilters(String, String)}.</li>
 *   <li>{@code 2210-EDIT-ACCOUNT} lines 647 to 683 becomes
 *       {@link #editAccountFilter(String)}.</li>
 *   <li>{@code 2220-EDIT-CARD} lines 685 to 724 becomes {@link #editCardFilter(String)}.</li>
 *   <li>{@code 9000-READ-DATA} lines 726 to 734 and {@code 9100-GETCARD-BYACCTCARD} lines 736 to
 *       777 together become {@link #requireCard(String)}, which is the whole of the read.</li>
 *   <li>{@code 9150-GETCARD-BYACCT} lines 779 to 812 has no counterpart here, for the reason
 *       recorded on {@link #requireCard(String)}.</li>
 * </ul>
 *
 * <p>The presentation paragraphs {@code 1000-SEND-MAP} through {@code 1400-SEND-SCREEN}, lines 412
 * to 578, belong to the browser tree rather than to this service, so no screen-attribute logic
 * appears here. {@code 0000-MAIN} at lines 248 and 408 likewise has no counterpart, because the
 * turn it drove is now the request the framework dispatches.</p>
 *
 * <h2>Why the reference program's own shape does not survive intact</h2>
 *
 * <p>Refactoring Rationale: the reference form is strictly pseudo-conversational. The platform ended
 * the CICS task at every screen turn, so nothing could be held in the program between turns and the
 * continuation had to travel with the terminal; the transaction is defined with {@code TWASIZE(0)}
 * at line 348 of {@code app/csd/CARDDEMO.CSD}, so there was not even a work area to hold it in. That
 * is what the echoed {@code DFHCOMMAREA} carried, and it is why the program needs a re-entry
 * discriminator to tell a first display from a redisplay. The target platform serves each HTTP
 * request independently and holds no session, so identity arrives in validated token claims and the
 * selection context arrives in the request itself. There is consequently nothing for a re-entry
 * discriminator to distinguish, and the two screen-attribute tests that read it -- lines 541 to 545
 * and 547 to 551, each pairing {@code FLG-*-BLANK} with {@code CDEMO-PGM-REENTER} -- become a single
 * per-field array on the response computed from the request alone. The same behaviour is reached by
 * a different route because the platform underneath it offers a different guarantee.</p>
 *
 * <h2>What the migrated diagnostics gain and what they give up</h2>
 *
 * <p>Trade-offs: the reference program reports through one {@code WS-RETURN-MSG PIC X(75)} field
 * declared at line 134, so it can only ever show one sentence and it keeps the first one. Every
 * assignment to it is guarded by {@code IF WS-RETURN-MSG-OFF}, which is what makes it
 * first-error-wins. This service instead returns an accumulating array covering every field it
 * faulted, alongside one summary sentence chosen by the reference program's own precedence. What is
 * gained is that a caller who supplied two unacceptable filters learns about both from one exchange
 * rather than discovering the second only after correcting the first. What is given up is the
 * guarantee that the summary sentence and the faulted-field set describe the same single field,
 * which on a 24-row terminal they always did; a renderer that wants the reference behaviour exactly
 * can still get it by displaying the summary sentence alone and ignoring the array.</p>
 *
 * <h2>Disclosure and the absence of a parity oracle</h2>
 *
 * <p>Assumptions: no method here discloses a full primary account number and none discloses a card
 * verification value. The masked rendering is produced by {@link CardMapper#toDetail(Card)}, and the
 * unmasked rendering has exactly one producer, {@code discloseCardNumberToAdministrator}, which this
 * class never calls. {@link CardDetail} has no verification-value component at all, so the
 * suppression is a property of the type rather than of this code.</p>
 *
 * <p>Assumptions: lines 83 to 85 of {@code tests/README.md} record that the online programs cannot
 * be run end to end without a CICS runtime, which the runner does not have. There is therefore no
 * golden-master oracle for this path, and no claim of one should be read into the citations above.
 * Parity here rests on transcription fidelity against the cited lines plus the tests in the sibling
 * test tree, and nothing stronger.</p>
 */
@Service
public class CardViewService {

    /**
     * The response-field identity carried by a fault against the account filter.
     *
     * <p>Assumptions: this is the member name the published contract uses for the account
     * identifier, so a client can bind an array entry straight to the input it came from.</p>
     */
    public static final String FIELD_ACCOUNT_ID = "accountId";

    /**
     * The response-field identity carried by a fault against the card filter.
     */
    public static final String FIELD_CARD_NUMBER = "cardNumber";

    /**
     * The declared width of the account filter, eleven characters.
     *
     * <p>Assumptions: read from {@code 10 CC-ACCT-ID PIC X(11)} at line 34 of
     * {@code app/cpy/CVCRD01Y.cpy}, which is the field the reference program edits.</p>
     */
    public static final int ACCOUNT_FILTER_WIDTH = 11;

    /**
     * The declared width of the card filter, sixteen characters.
     *
     * <p>Assumptions: sixteen is asserted by three independent places that agree, which is why it is
     * stated as a constant rather than inlined: the record's own {@code CARD-NUM PIC X(16)} at line 5
     * of {@code app/cpy/CVACT02Y.cpy}, the cluster's {@code KEYS(16 0)} at line 54 of
     * {@code app/jcl/CARDFILE.jcl}, and the key length the read passes at line 745 of
     * {@code app/cbl/COCRDSLC.cbl}.</p>
     */
    public static final int CARD_FILTER_WIDTH = 16;

    /**
     * The refusal code this service reports when a filter is not acceptable.
     *
     * <p>Assumptions: the shared advice renders a rejected-input failure as HTTP 400 and takes the
     * code from the failure, so naming the shared validation code here keeps this context's refusal
     * bodies identical in shape to every other context's.</p>
     */
    public static final String FILTER_REFUSAL_CODE = ApiError.CODE_VALIDATION;

    /**
     * The reference sentence shown when the requested card was read successfully.
     *
     * <p>Assumptions: carried verbatim under transformation rule T8 from
     * {@code 88 FOUND-CARDS-FOR-ACCOUNT} at lines 129 and 130 of {@code app/cbl/COCRDSLC.cbl}. The
     * three leading spaces are inside the reference literal and are part of the value, not
     * indentation that may be trimmed; the declared length is thirty-one characters including
     * them.</p>
     */
    public static final String MESSAGE_DISPLAYING_REQUESTED_DETAILS =
            "   Displaying requested details";

    /**
     * The reference sentence shown before any filter has been supplied.
     *
     * <p>Assumptions: carried verbatim from {@code 88 WS-PROMPT-FOR-INPUT} at lines 131 and 132,
     * which the reference program sets at lines 460 and 491 while composing the first display.</p>
     */
    public static final String MESSAGE_PROMPT_FOR_INPUT =
            "Please enter Account and Card Number";

    /**
     * The reference sentence for an absent account filter.
     *
     * <p>Assumptions: carried verbatim from {@code 88 WS-PROMPT-FOR-ACCT} at lines 138 and 139, set
     * at line 657 inside the blank arm of the account edit.</p>
     */
    public static final String MESSAGE_ACCOUNT_NOT_PROVIDED = "Account number not provided";

    /**
     * The reference sentence for an absent card filter.
     *
     * <p>Assumptions: carried verbatim from {@code 88 WS-PROMPT-FOR-CARD} at lines 140 and 141, set
     * at line 697 inside the blank arm of the card edit.</p>
     */
    public static final String MESSAGE_CARD_NOT_PROVIDED = "Card number not provided";

    /**
     * The reference sentence for a request that supplied neither filter.
     *
     * <p>Assumptions: carried verbatim from {@code 88 NO-SEARCH-CRITERIA-RECEIVED} at lines 142 and
     * 143, set at line 639 by the cross-field test. That assignment is the one message write in the
     * edit chain that is not wrapped in {@code IF WS-RETURN-MSG-OFF}, so when both filters are absent
     * it supersedes the per-field sentence line 657 had already latched. The precedence in
     * {@link #summaryMessage(FieldValidationFlag, FieldValidationFlag)} reproduces that
     * ordering.</p>
     */
    public static final String MESSAGE_NO_INPUT_RECEIVED = "No input received";

    /**
     * The reference sentence naming an all-zero account filter.
     *
     * <p>Assumptions: carried verbatim from {@code 88 SEARCHED-ACCT-ZEROES} at lines 144 and 145.
     * Grepping the program for that condition name returns only its declaration, so it has no
     * emitter in the reference baseline and this service does not attach it to any branch. It is
     * published because rule T8 carries the value across regardless of whether a branch reaches it,
     * and annotated so that nobody wires it to one and thereby introduces behaviour the baseline
     * does not have. The reason it is unreached is visible at line 653: an all-zero account filter
     * satisfies the blank arm's third disjunct and is reported as absent.</p>
     */
    public static final String MESSAGE_SEARCHED_ACCOUNT_ZEROES =
            "Account number must be a non zero 11 digit number";

    /**
     * The reference sentence naming a non-numeric account filter, as a second condition name.
     *
     * <p>Assumptions: carried verbatim from {@code 88 SEARCHED-ACCT-NOT-NUMERIC} at lines 146 and
     * 147. Its literal is character-for-character identical to
     * {@link #MESSAGE_SEARCHED_ACCOUNT_ZEROES}, and the two are deliberately kept as separate
     * constants rather than collapsed into one: the reference declares two distinct condition names,
     * and folding them together would erase a distinction the baseline makes and leave a later reader
     * unable to tell which name a citation refers to. Like its twin it has no emitter, because the
     * non-numeric arm writes the inline literal at line 670 instead.</p>
     */
    public static final String MESSAGE_SEARCHED_ACCOUNT_NOT_NUMERIC =
            "Account number must be a non zero 11 digit number";

    /**
     * The reference sentence naming a non-numeric card filter as a condition name.
     *
     * <p>Assumptions: carried verbatim from {@code 88 SEARCHED-CARD-NOT-NUMERIC} at lines 148 and
     * 149, and likewise without an emitter, the card edit writing the inline literal at line 711
     * instead.</p>
     */
    public static final String MESSAGE_SEARCHED_CARD_NOT_NUMERIC =
            "Card number if supplied must be a 16 digit number";

    /**
     * The reference sentence the account-filter edit actually writes when the value is not numeric.
     *
     * <p>Assumptions: carried verbatim from the inline
     * {@code MOVE ... TO WS-RETURN-MSG} at lines 669 to 671, whose literal is on line 670. This is
     * the sentence the non-numeric arm emits, in place of the condition name declared at line 146.
     * The absent space after the comma is inside the reference literal; its declared length is
     * fifty-two characters.</p>
     */
    public static final String MESSAGE_ACCOUNT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * The reference sentence the card-filter edit actually writes when the value is not numeric.
     *
     * <p>Assumptions: carried verbatim from the inline write at lines 710 to 712, whose literal is on
     * line 711, and paired with {@link #MESSAGE_ACCOUNT_FILTER_NOT_NUMERIC} for the same reason.</p>
     */
    public static final String MESSAGE_CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * The reference sentence for an account that no card row names.
     *
     * <p>Assumptions: carried verbatim from {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF} at lines 151 and
     * 152. It has no reachable emitter in the card baseline: its only assignment is at line 799,
     * which sits inside {@code 9150-GETCARD-BYACCT}, and nothing performs that paragraph. It is
     * published under rule T8 and annotated so that it is not attached to a live branch here.</p>
     */
    public static final String MESSAGE_ACCOUNT_NOT_IN_CARDS_DATABASE =
            "Did not find this account in cards database";

    /**
     * The reference sentence for a search that matched no card, and the one this service reports.
     *
     * <p>Assumptions: carried verbatim from {@code 88 DID-NOT-FIND-ACCTCARD-COMBO} at lines 153 and
     * 154. Unlike the four sentences above it this one has a live emitter, at line 760 inside the
     * not-found arm of the read, which is why it and not one of the others is the sentence
     * {@link #requireCard(String)} raises.</p>
     */
    public static final String MESSAGE_CARD_NOT_FOUND =
            "Did not find cards for this search condition";

    /**
     * The reference sentence for a failed read of the card file.
     *
     * <p>Assumptions: carried verbatim from {@code 88 XREF-READ-ERROR} at lines 155 and 156.
     * Grepping for that condition name returns only its declaration, so it has no emitter; the failed
     * read at lines 767 to 771 composes {@code WS-FILE-ERROR-MESSAGE} from the operation name, the
     * file name and the two response codes instead. Published under rule T8 and annotated so that it
     * is not attached to a live branch.</p>
     */
    public static final String MESSAGE_CARD_FILE_READ_ERROR = "Error reading Card Data File";

    /**
     * The reference sentence left in the program against work its author had not yet reached.
     *
     * <p>Assumptions: carried verbatim from {@code 88 CODING-TO-BE-DONE} at lines 157 and 158, four
     * dots included. It has no emitter, so it never reaches a user in the reference baseline, and it
     * is attached to no branch here either. Rule T8 is why it is present at all; recording that it is
     * unreached is what stops a later reader assuming it must be surfaced somewhere.</p>
     */
    public static final String MESSAGE_CODING_TO_BE_DONE = "Looks Good.... so far";

    /**
     * The reference sentence for the exit key.
     *
     * <p>Assumptions: carried verbatim from {@code 88 WS-EXIT-MESSAGE} at lines 136 and 137,
     * including the absent space after the period and the fourteen trailing spaces inside the
     * literal, which bring its declared length to thirty-four characters. It has no emitter either,
     * and the navigation it described is a client-side route change in the target rather than
     * anything this service reports.</p>
     */
    public static final String MESSAGE_EXIT = "PF03 pressed.Exiting              ";

    /**
     * Records which read ran and how it ended, never the numbers it carried.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardViewService.class);

    /**
     * The reader this service resolves a card through.
     */
    private final CardRepository cards;

    /**
     * The anti-corruption layer that masks the account number and mints the selector.
     */
    private final CardMapper mapper;

    /**
     * Builds the service from its two collaborators.
     *
     * <p>Assumptions: both collaborators arrive through the constructor and are held final, and the
     * class keeps no mutable state of any kind. That is what lets it be exercised without a database
     * and lets several instances serve concurrently behind a load balancer, which the reference
     * transaction's own {@code TWASIZE(0)} definition at line 348 of
     * {@code app/csd/CARDDEMO.CSD} shows the baseline also required of its program.</p>
     *
     * @param cards the card reader; must not be {@code null}
     * @param mapper the mapper that renders a stored row as a detail response and opens selectors;
     *     must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public CardViewService(CardRepository cards, CardMapper mapper) {
        this.cards = Objects.requireNonNull(cards, "cards must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Edits both screen filters, then reads the one card the card filter names.
     *
     * <p>This is the reference program's {@code 2200-EDIT-MAP-INPUTS} to {@code 9000-READ-DATA} chain
     * end to end, and it is the entry point that reproduces the transaction's behaviour rather than
     * merely reaching its data.</p>
     *
     * <p>Assumptions: both filters are MANDATORY on this screen. The account edit opens by setting
     * {@code FLG-ACCTFILTER-NOT-OK} at line 648 and the card edit by setting
     * {@code FLG-CARDFILTER-NOT-OK} at line 688, and each blank arm then raises {@code INPUT-ERROR}
     * as well, at lines 654 and 694. The value each flag is pre-set to IS the mandatory-versus-optional
     * semantic; there is no separate mechanism to consult. The contrast that proves it is the card-list
     * program, which pre-sets {@code FLG-ACCTFILTER-BLANK} at line 1004 of
     * {@code app/cbl/COCRDLIC.cbl} and {@code FLG-CARDFILTER-BLANK} at line 1039, and whose blank arm
     * at line 1010 sets no input error -- so an absent filter there means "do not filter" while an
     * absent filter here means the request is incomplete.</p>
     *
     * @param accountId the account filter exactly as the caller supplied it, or {@code null} when the
     *     caller supplied none
     * @param cardNumber the card filter exactly as the caller supplied it, or {@code null} when the
     *     caller supplied none
     * @return the card the card filter names, with its account number masked to the last four digits,
     *     never {@code null}
     * @throws ClientInputException if either filter is absent or is not a number of its declared
     *     width, which the shared advice renders as HTTP 400 carrying the per-field array
     * @throws NoSuchElementException if both filters are acceptable but no stored row holds that card
     *     number, which the shared advice renders as HTTP 404
     */
    @Transactional(readOnly = true)
    public CardDetail view(String accountId, String cardNumber) {

        // WHY : Assumptions: the two edits are evaluated in the reference program's own order --
        //       2210-EDIT-ACCOUNT is performed at lines 630 and 631 before 2220-EDIT-CARD at lines 633
        //       and 634 -- and the order is load-bearing rather than incidental. Because every message
        //       write in the chain is wrapped in IF WS-RETURN-MSG-OFF, whichever edit faults first is
        //       the one whose sentence survives, so evaluating them in the other order would change
        //       which sentence a caller is shown for a request that fails both.
        // WHY : Refactoring Rationale: the selection context arrives as these two parameters, where the
        //       reference form recovered it from an area echoed back by the terminal, because the
        //       platform ended its task at each screen turn and left it nowhere else to keep it. The
        //       target platform dispatches a self-describing request, so the same two values arrive
        //       directly and the re-entry discriminator that told a first display from a redisplay has
        //       nothing left to distinguish. That is why this method takes no flag argument and reads no
        //       session.
        FieldValidationFlag accountState = editAccountFilter(accountId);
        FieldValidationFlag cardState = editCardFilter(cardNumber);

        // WHY : Assumptions: a BLANK state is an ERROR here, and that single fact is the whole of the
        //       mandatory-filter semantic. Each edit opens by pre-setting its flag to not-ok, at line
        //       648 for the account and line 688 for the card, and each blank arm additionally raises
        //       INPUT-ERROR at lines 654 and 694, so an absent filter halts the transaction. The
        //       card-list program pre-sets the BLANK condition instead, at lines 1004 and 1039 of
        //       app/cbl/COCRDLIC.cbl, and its blank arm at line 1010 raises no input error -- so there
        //       an absent filter means "do not filter". The value the flag is pre-set to IS the
        //       distinction; there is no second mechanism to consult, which is why this gate treats
        //       both error states alike rather than testing for a separate required-field marker.
        if (accountState.isError() || cardState.isError()) {
            throw refuseFilters(accountState, cardState);
        }

        // WHY : Assumptions: the read is keyed on the CARD FILTER ALONE, and the paragraph name
        //       9100-GETCARD-BYACCTCARD is not evidence to the contrary. Inside it the account-id move
        //       is commented out at line 739, the live move at line 740 loads only
        //       WS-CARD-RID-CARDNUM, and the read at lines 742 to 750 passes that field as its RIDFLD
        //       with KEYLENGTH taken from it at line 745. The cluster agrees independently:
        //       app/jcl/CARDFILE.jcl line 54 declares KEYS(16 0), a sixteen-byte key at offset zero,
        //       which is the card number and nothing else. The account filter is therefore edited and
        //       required, exactly as the baseline requires it, but it takes no part in the lookup
        //       predicate -- so no composite key and no account-id component appears here.
        return this.mapper.toDetail(requireCard(cardNumber));
    }

    /**
     * Edits both filters and reports every fault found, without reading anything.
     *
     * <p>Assumptions: this returns an ACCUMULATING array rather than stopping at the first fault, which
     * is the per-field error surface transformation rule T7 asks for. The reference program expresses
     * the same information through two independent screen-attribute flags, and its blank case
     * additionally writes a literal asterisk into the field, at line 543 for the account and line 549
     * for the card -- exactly two sites, matching the two mandatory filters. That marker survives here
     * as {@link ApiError.FieldError#screenMarker()}, which yields
     * {@link FieldValidationFlag#BLANK_SCREEN_MARKER} for a blank field and nothing for a merely
     * unacceptable one, so a renderer can reproduce the asterisk without this service formatting
     * anything.</p>
     *
     * @param accountId the account filter as supplied, or {@code null} when none was supplied
     * @param cardNumber the card filter as supplied, or {@code null} when none was supplied
     * @return an unmodifiable array holding one entry per faulted filter in the reference program's
     *     evaluation order, empty when both filters are acceptable; never {@code null}
     */
    public List<ApiError.FieldError> validateFilters(String accountId, String cardNumber) {

        FieldValidationFlag accountState = editAccountFilter(accountId);
        FieldValidationFlag cardState = editCardFilter(cardNumber);

        List<ApiError.FieldError> faults = new ArrayList<>(2);

        // WHY : Assumptions: each entry is minted through FieldValidationFlag.toFieldError, which
        //       returns an empty optional for an acceptable field, so an acceptable filter contributes
        //       nothing and the array length always equals the number of faulted filters. Building the
        //       entries by hand and filtering afterwards would let a VALID state reach an entry, which
        //       ApiError.FieldError refuses in its compact constructor -- so delegating here keeps the
        //       two records' invariants agreeing instead of restating one of them.
        accountState.toFieldError(FIELD_ACCOUNT_ID, accountFilterMessage(accountState))
                .map(ApiError.FieldError::from)
                .ifPresent(faults::add);
        cardState.toFieldError(FIELD_CARD_NUMBER, cardFilterMessage(cardState))
                .map(ApiError.FieldError::from)
                .ifPresent(faults::add);

        return List.copyOf(faults);
    }

    /**
     * Reports the per-field array the reference program raises when its read finds no card.
     *
     * <p>Assumptions: the not-found arm faults BOTH filters, not just the one that addressed the row.
     * Lines 755 to 761 set {@code INPUT-ERROR}, then {@code FLG-ACCTFILTER-NOT-OK} at line 757 AND
     * {@code FLG-CARDFILTER-NOT-OK} at line 758, and only then latch the sentence at lines 759 to 761.
     * Both filters are therefore reported as not acceptable, which is coherent on a screen where the
     * pair is what the user searched with. This is published as a method so that the card-update
     * service can reproduce the identical contract: its own read paragraph, at lines 1376 to 1417 of
     * {@code app/cbl/COCRDUPC.cbl}, is structurally the same paragraph, and two contexts describing one
     * condition differently would be a divergence no build would notice.</p>
     *
     * @return an unmodifiable two-entry array faulting the account filter and then the card filter,
     *     each carrying the reference not-found sentence; never {@code null} and never empty
     */
    public static List<ApiError.FieldError> notFoundFieldErrors() {

        // WHY : Assumptions: both entries carry NOT_OK rather than BLANK, because the reference arm
        //       sets the not-ok condition on each flag and never the blank one. The distinction is not
        //       cosmetic: BLANK is what drives the asterisk marker, and a filter the caller did supply
        //       must not be rendered as one they left empty.
        return List.of(
                new ApiError.FieldError(
                        FIELD_ACCOUNT_ID, FieldValidationFlag.NOT_OK, MESSAGE_CARD_NOT_FOUND),
                new ApiError.FieldError(
                        FIELD_CARD_NUMBER, FieldValidationFlag.NOT_OK, MESSAGE_CARD_NOT_FOUND));
    }

    /**
     * Reads the card the supplied selector stands for.
     *
     * @param selector the opaque selector this context minted for the card, exactly as a list row or a
     *     previous detail response carried it; must not be {@code null}
     * @return the card with its account number masked to the last four digits, never {@code null}
     * @throws ClientInputException if the selector is not one this context issued or can no longer be
     *     opened, which the contract reports as 400 because the request named nothing resolvable
     * @throws NoSuchElementException if the selector opened cleanly but names no stored row
     * @throws NullPointerException if {@code selector} is {@code null}
     */
    @Transactional(readOnly = true)
    public CardDetail viewBySelector(String selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return this.mapper.toDetail(loadBySelector(selector));
    }

    /**
     * Reads the card the supplied primary account number names, without editing screen filters.
     *
     * <p>Assumptions: this entry point exists because a primary account number may not appear in a
     * request line. The load balancer writes the request target into a durable access-log object
     * before any application code runs, so a number placed in a path segment or a query string is
     * persisted verbatim and no later masking can redact a log already written. The published contract
     * therefore carries the number in a request body, and this method is what serves it. It performs
     * the read without the account filter the reference screen also required, because a caller reaching
     * it has already supplied the one value the lookup is keyed on and has no screen to complete.</p>
     *
     * @param cardNumber the sixteen-digit primary account number, taken from the request body; must
     *     not be {@code null}
     * @return the card with its account number masked, and carrying the selector every other
     *     single-card operation addresses it by, never {@code null}
     * @throws NoSuchElementException if no stored row holds that number
     * @throws NullPointerException if {@code cardNumber} is {@code null}
     */
    @Transactional(readOnly = true)
    public CardDetail viewByCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber must not be null");
        return this.mapper.toDetail(requireCard(cardNumber));
    }

    /**
     * Reads the card a selector stands for and returns the stored row itself.
     *
     * <p>Trade-offs: this is package-private and answers the ENTITY rather than a response shape, so
     * that the administrative read can reuse the selector resolution without first going through the
     * masking mapper and then discarding its result. What is given up is that the row escapes this
     * class at all; what that buys is a single resolution path, and the containment is real because
     * widening it to public would let a caller outside this package obtain an unmasked row, which is
     * the one thing the mapper boundary exists to prevent.</p>
     *
     * @param selector the opaque selector naming the card to read; must not be {@code null}
     * @return the stored row, never {@code null}
     * @throws ClientInputException if the selector is not one this context issued or can no longer be
     *     opened
     * @throws NoSuchElementException if the selector opened cleanly but names no stored row
     * @throws NullPointerException if {@code selector} is {@code null}
     */
    @Transactional(readOnly = true)
    Card loadBySelector(String selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return requireCard(this.mapper.openCardSelector(selector));
    }

    /**
     * Edits the account filter, carrying {@code 2210-EDIT-ACCOUNT} at lines 647 to 683.
     *
     * <p>Alternatives Considered: accepting a literal asterisk as a clear token, which is what the
     * reference screen does. Lines 614 to 627, under the comment {@code REPLACE * WITH LOW-VALUES},
     * fold an asterisk or spaces in either input field down to low values before the edits run, so a
     * 3270 user blanks a filter by typing over it -- there is no way to send an empty field on a
     * fixed-width screen, so the affordance was the only way to express "I no longer want this
     * filter". It is not reproduced because an HTTP caller expresses the same intention by omitting
     * the member, which arrives here as {@code null}. Reproducing it would additionally make a literal
     * asterisk a reserved value in an account identifier, and a client sending one would silently get a
     * different request than the one it wrote.</p>
     *
     * @param accountId the account filter as supplied, or {@code null} when none was supplied
     * @return {@link FieldValidationFlag#BLANK} when nothing usable was supplied,
     *     {@link FieldValidationFlag#NOT_OK} when the value is not eleven digits, otherwise
     *     {@link FieldValidationFlag#VALID}; never {@code null}
     */
    private FieldValidationFlag editAccountFilter(String accountId) {

        // WHY : Assumptions: the outcome is expressed as one of the shared flag's SEMANTIC states and
        //       never as the byte the reference field held, because the two encodings disagree. The card
        //       programs declare the triad as not-ok '0', valid '1' and blank space, at lines 56 to 58
        //       and 60 to 62 of app/cbl/COCRDSLC.cbl, whereas the date-edit template the shared flag is
        //       modelled on declares it as valid low-values, not-ok '0' and blank 'B', at lines 43 to 57
        //       of app/cpy/CSUTLDWY.cpy. Only the not-ok byte agrees. Returning a byte would therefore
        //       force every reader to know which program produced it; the flag byte crosses no wire in
        //       the target, so the state is the only thing worth carrying.
        // WHY : Alternatives Considered: normalising a literal asterisk to "absent" before testing, as
        //       lines 614 to 627 do under the comment REPLACE * WITH LOW-VALUES. Rejected here for the
        //       reason given on this method: an HTTP caller omits the member instead, and reserving the
        //       character would change the meaning of a value a client legitimately sent.
        // WHY : Assumptions: the blank arm has THREE disjuncts, not two. Lines 651 and 652 test the
        //       character field against low values and against spaces, and line 653 additionally tests
        //       the numeric redefinition against zeros -- so an all-zero filter is reported as ABSENT
        //       rather than as a bad value. That third disjunct is why the SEARCHED-ACCT-ZEROES
        //       condition declared at line 144 has no emitter: the arm that would have set it is
        //       reached only through a value line 653 has already classified as blank.
        if (FieldValidationFlag.isNeverSupplied(accountId) || isEntirelyZeros(accountId)) {
            return FieldValidationFlag.BLANK;
        }

        // WHY : Assumptions: the reference test at line 665 is IS NOT NUMERIC applied to the whole
        //       eleven-character field, so every one of the eleven positions has to be a digit; a value
        //       occupying only part of the field leaves spaces in the remainder and fails it. The
        //       target equivalent of that is an exact-width all-digit test rather than a
        //       "digits only" test, because over HTTP a short value arrives short instead of
        //       space-padded and a length-agnostic test would accept input the screen refused.
        if (!isExactlyDigits(accountId, ACCOUNT_FILTER_WIDTH)) {
            return FieldValidationFlag.NOT_OK;
        }

        return FieldValidationFlag.VALID;
    }

    /**
     * Edits the card filter, carrying {@code 2220-EDIT-CARD} at lines 685 to 724.
     *
     * <p>Assumptions: this paragraph has the same three-way shape as the account edit, with its
     * pessimistic pre-set at line 688, its three-disjunct blank arm at lines 691 to 693 and its
     * not-numeric test at line 706, so the two are transcribed as a matched pair. The only differences
     * are the declared width and the sentences.</p>
     *
     * @param cardNumber the card filter as supplied, or {@code null} when none was supplied
     * @return {@link FieldValidationFlag#BLANK} when nothing usable was supplied,
     *     {@link FieldValidationFlag#NOT_OK} when the value is not sixteen digits, otherwise
     *     {@link FieldValidationFlag#VALID}; never {@code null}
     */
    private FieldValidationFlag editCardFilter(String cardNumber) {

        if (FieldValidationFlag.isNeverSupplied(cardNumber) || isEntirelyZeros(cardNumber)) {
            return FieldValidationFlag.BLANK;
        }

        if (!isExactlyDigits(cardNumber, CARD_FILTER_WIDTH)) {
            return FieldValidationFlag.NOT_OK;
        }

        return FieldValidationFlag.VALID;
    }

    /**
     * Builds the refusal for a request whose filters did not pass their edits.
     *
     * <p>Trade-offs: the shared refusal type carries one validation state for every field it names, and
     * its own contract records that a multi-field refusal is meant for members that fail together for
     * the same reason. This method therefore names BOTH filters only when their states agree -- which
     * covers the reference cross-field case at lines 637 to 640, where both are absent -- and otherwise
     * names the first filter to fault in the reference program's evaluation order. What is given up is
     * that a request faulting one filter as absent and the other as unacceptable reports only the
     * first, even though the reference program would have highlighted both fields. What that buys is
     * that no field is ever labelled with a state that is not its own, which would misdirect the
     * asterisk marker; and the complete picture stays available through
     * {@link #validateFilters(String, String)}, which is what a caller wanting both should read.</p>
     *
     * @param accountState the state the account edit returned; must not be {@code null}
     * @param cardState the state the card edit returned; must not be {@code null}
     * @return the refusal to throw, never {@code null}
     */
    private ClientInputException refuseFilters(
            FieldValidationFlag accountState, FieldValidationFlag cardState) {

        String summary = summaryMessage(accountState, cardState);

        LOG.info("event=card.filters.refused accountState={} cardState={}",
                accountState, cardState);

        // WHY : Trade-offs: the states are compared for EQUALITY to decide whether both filters may be
        //       named, rather than simply naming every faulted filter. The shared refusal type carries
        //       one state for all the members it names and its own contract reserves the multi-member
        //       form for members that fail together for the same reason, so naming two filters whose
        //       states differ would label one of them with a state that is not its own and misdirect the
        //       blank marker. What is given up is that a mixed failure reports one filter instead of
        //       two; what it buys is that no reported state is ever wrong, and
        //       validateFilters(String, String) still answers the complete set for any caller that wants
        //       both.
        if (accountState == cardState) {
            return new ClientInputException(FILTER_REFUSAL_CODE,
                    List.of(FIELD_ACCOUNT_ID, FIELD_CARD_NUMBER), accountState, summary);
        }

        if (accountState.isError()) {
            return new ClientInputException(
                    FILTER_REFUSAL_CODE, FIELD_ACCOUNT_ID, accountState, summary);
        }

        return new ClientInputException(
                FILTER_REFUSAL_CODE, FIELD_CARD_NUMBER, cardState, summary);
    }

    /**
     * Chooses the one summary sentence the reference program would have displayed.
     *
     * <p>Assumptions: the precedence below is the reference program's own, and it is not simply
     * "first fault wins". Every per-field sentence is written under {@code IF WS-RETURN-MSG-OFF} --
     * lines 656 to 658 and 668 to 672 for the account, 696 to 698 and 709 to 713 for the card -- so
     * among the per-field sentences the account's does win. The cross-field write at line 639 is the
     * exception: it is NOT wrapped in that guard, so when both filters are absent it overwrites the
     * sentence line 657 had already latched. That is why the both-absent case is tested first here
     * rather than falling out of the account-before-card ordering.</p>
     *
     * @param accountState the state the account edit returned; must not be {@code null}
     * @param cardState the state the card edit returned; must not be {@code null}
     * @return the reference sentence for the highest-precedence condition present, or the successful
     *     read sentence when both filters are acceptable; never {@code null}
     */
    private static String summaryMessage(
            FieldValidationFlag accountState, FieldValidationFlag cardState) {

        if (accountState == FieldValidationFlag.BLANK && cardState == FieldValidationFlag.BLANK) {
            return MESSAGE_NO_INPUT_RECEIVED;
        }
        if (accountState.isError()) {
            return accountFilterMessage(accountState);
        }
        if (cardState.isError()) {
            return cardFilterMessage(cardState);
        }
        return MESSAGE_DISPLAYING_REQUESTED_DETAILS;
    }

    /**
     * Resolves the reference sentence belonging to one account-filter state.
     *
     * @param state the state the account edit returned; must not be {@code null}
     * @return the reference sentence for that state; never {@code null} and never blank
     */
    private static String accountFilterMessage(FieldValidationFlag state) {

        // WHY : Assumptions: the not-numeric arm resolves to the INLINE literal at line 670 and not to
        //       the SEARCHED-ACCT-NOT-NUMERIC condition declared at line 146, because line 669 writes
        //       that literal directly to WS-RETURN-MSG and nothing ever sets the condition. Choosing
        //       the condition's text here would report a sentence the baseline never displays.
        return switch (state) {
            case BLANK -> MESSAGE_ACCOUNT_NOT_PROVIDED;
            case NOT_OK -> MESSAGE_ACCOUNT_FILTER_NOT_NUMERIC;
            case VALID -> MESSAGE_DISPLAYING_REQUESTED_DETAILS;
        };
    }

    /**
     * Resolves the reference sentence belonging to one card-filter state.
     *
     * @param state the state the card edit returned; must not be {@code null}
     * @return the reference sentence for that state; never {@code null} and never blank
     */
    private static String cardFilterMessage(FieldValidationFlag state) {

        // WHY : Assumptions: the same reasoning as the account sentence applies -- line 710 writes the
        //       inline literal on line 711 rather than setting the SEARCHED-CARD-NOT-NUMERIC condition
        //       declared at line 148, which has no emitter.
        return switch (state) {
            case BLANK -> MESSAGE_CARD_NOT_PROVIDED;
            case NOT_OK -> MESSAGE_CARD_FILTER_NOT_NUMERIC;
            case VALID -> MESSAGE_DISPLAYING_REQUESTED_DETAILS;
        };
    }

    /**
     * Reads one card by its key and refuses absence, carrying the whole of the reference read.
     *
     * <p>This is {@code 9000-READ-DATA} at lines 726 to 734 together with
     * {@code 9100-GETCARD-BYACCTCARD} at lines 736 to 777, which are one path rather than two: the
     * driver performs the reader unconditionally at lines 728 and 729 and chooses between nothing.</p>
     *
     * <p>Assumptions: the reference reader's three-arm response test at lines 752 to 772 maps onto three
     * target outcomes, and the secondary batch program corroborates the three-way shape without
     * specifying this read. {@code app/cbl/CBACT02C.cbl} is headed
     * {@code Type : BATCH COBOL Program} at line 4 and {@code Read and print card data file.} at line
     * 5, declares {@code ACCESS MODE IS SEQUENTIAL} at line 31, and drives a whole-file loop at lines
     * 70 to 87 that displays each record at line 78 -- so it is a sequential dump and is NOT the
     * specification for a keyed lookup. What it does contribute is record framing, its
     * {@code FD-CARDFILE-REC} at lines 38 to 40 summing sixteen plus one hundred and thirty-four to the
     * declared one hundred and fifty with the typed view supplied by {@code COPY CVACT02Y.} at line 45,
     * and the three-way discipline of separating a good read at lines 94 and 95 from end of file at
     * lines 98 and 99 from anything else at line 101. Its internal result values are a convention
     * private to that program and describe nothing outside it.</p>
     *
     * <p>Assumptions: {@code 9150-GETCARD-BYACCT} at lines 779 to 812 is the only alternate-index read
     * in the card context, and nothing performs it -- grepping the program for that paragraph name
     * returns just its label at line 779 and its own exit at line 810. The alternate-index file name is
     * likewise declared and unused: at line 189 in this program with its two references at lines 784 and
     * 804 both sitting inside that paragraph, at line 253 of {@code app/cbl/COCRDUPC.cbl} as a
     * declaration alone, and at line 215 of {@code app/cbl/COCRDLIC.cbl} as a declaration alone. No live
     * path in any of the three card programs reads the alternate index. This is recorded as an
     * OBSERVATION about the baseline and for one purpose only, so that a later reader does not wire the
     * sentence at lines 151 and 152 to a live branch on the strength of finding it declared. It is
     * expressly NOT the justification for any by-account access path: the secondary index this context
     * owns is mandated by transformation plan section 0.4.1.3 and by the alternate index being defined
     * to CICS with {@code BROWSE(YES)} and {@code READ(YES)} at lines 13 to 24 of
     * {@code app/csd/CARDDEMO.CSD}, and no by-account read appears in this class at all.</p>
     *
     * @param cardNumber the sixteen-character key to read
     * @return the stored row, never {@code null}
     * @throws NoSuchElementException when no stored row holds that key
     */
    private Card requireCard(String cardNumber) {

        // WHY : Assumptions: absence is reported as a not-found refusal rather than as an empty
        //       success, matching lines 755 to 761, where the reference reader raises an input error
        //       and latches a sentence instead of displaying an empty record. A caller naming one card
        //       by key has asked for a row that either exists or does not, unlike a browse, where
        //       matching nothing is a legitimate answer.
        // WHY : Assumptions: the sentence raised is the one at lines 153 and 154, and it is chosen over
        //       the superficially similar one at lines 151 and 152 because it is the sentence this arm
        //       actually latches, at line 760.
        // WHY : Alternatives Considered: appending an ellipsis to this sentence. The shared advice
        //       carries a context's own wording onto the response body only when the sentence ends in
        //       one, and this sentence does not, so the body shows the shared default while the
        //       reference wording travels in the failure and the log. Appending one would make it pass
        //       that gate, and it is rejected because the whole purpose of the value is to be
        //       reproduced exactly; a sentence altered to satisfy a renderer is no longer the sentence
        //       being carried across.
        // WHY : Trade-offs: the diagnostic names NO card number, and the omission is deliberate rather
        //       than terse. A not-found failure is rendered into a response body and a log line, so
        //       quoting the key here would put a full primary account number into both, reintroducing
        //       through an error path exactly the disclosure that keeping the number out of the request
        //       target exists to prevent. What is given up is the ability to tell two absent keys apart
        //       in a log; the correlation identifier the shared filter attaches is what recovers that.
        // WHY : Assumptions: this single keyed read is the whole of the migrated read, and the batch
        //       program app/cbl/CBACT02C.cbl is NOT what specifies it. That program declares
        //       ACCESS MODE IS SEQUENTIAL at line 31 and walks the entire file at lines 70 to 87,
        //       displaying each record, so citing it here would justify a scan where the contract calls
        //       for a lookup. What it does corroborate is the record framing its FD-CARDFILE-REC gives
        //       at lines 38 to 40 and the three-way split between a good read, an end of file and
        //       anything else at lines 94 to 101, which is the shape this method's own three outcomes
        //       follow.
        // WHY : Assumptions: no by-account read appears here, and the reason is NOT that the reference
        //       program's alternate-index paragraph is unperformed. That observation is recorded on this
        //       method for one purpose only, to stop a later reader wiring the sentence at lines 151 and
        //       152 to a live branch. Any secondary access path this context gains is owed to
        //       transformation plan section 0.4.1.3 and to the alternate index being defined to CICS
        //       with BROWSE(YES) and READ(YES) at lines 13 to 24 of app/csd/CARDDEMO.CSD, never to the
        //       unperformed paragraph.
        Card found = this.cards.findById(cardNumber)
                .orElseThrow(() -> new NoSuchElementException(MESSAGE_CARD_NOT_FOUND));

        // WHY : Assumptions: nothing is caught here, and the failed-read arm at lines 762 to 771 is
        //       deliberately left to the shared advice, which already renders an unexpected failure as
        //       HTTP 500 carrying the structured abend detail that arm's own fields correspond to.
        //       Catching a read failure locally would duplicate that mapping in a second place and
        //       would also mean this method silently swallowed a condition the platform is better
        //       placed to report. Worth recording about that arm: its flag write at lines 764 to 766
        //       sits inside the first-error-wins guard but its message write at line 771 sits OUTSIDE
        //       it, so a failed read replaces any sentence an earlier edit had latched. It is the one
        //       message write in the reader that behaves that way, and it is noted here because the
        //       target reaches the same outcome by a different route -- a read failure aborts the
        //       request, so no earlier sentence survives to be replaced.
        LOG.debug("event=card.read.hit version={}", found.getVersion());

        return found;
    }

    /**
     * Reports whether a value is entirely made of the digit zero.
     *
     * <p>Assumptions: this is the target reading of the reference blank arm's third disjunct, the
     * {@code CC-ACCT-ID-N EQUAL ZEROS} test at line 653 and its card counterpart at line 693. Those
     * compare a numeric redefinition of the character field, so any all-zero value satisfies them
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
     * Reports whether a value is exactly the given number of decimal digits.
     *
     * <p>Alternatives Considered: a compiled regular expression, and
     * {@link Character#isDigit(char)}. The pattern was rejected because this predicate sits on a
     * validation path taken by every request and a pattern match allocates where a character scan does
     * not. The library predicate was rejected for a correctness reason rather than a cost one: it
     * accepts the decimal digits of every Unicode script, so a value written in non-ASCII digits would
     * satisfy it while failing the reference field's own numeric test, and the sixteen-character key
     * built from it could then address no row.</p>
     *
     * @param value the value to inspect, which may be {@code null}
     * @param width the exact number of digits required
     * @return {@code true} when the value is non-null, of exactly {@code width} characters, and every
     *     character is an ASCII digit; {@code false} otherwise
     */
    private static boolean isExactlyDigits(String value, int width) {

        if (value == null || value.length() != width) {
            return false;
        }
        for (int index = 0; index < width; index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
