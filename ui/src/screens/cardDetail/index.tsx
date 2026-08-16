/**
 * @file The card detail screen, migrated from `app/cbl/COCRDSLC.cbl` and its mapset
 * `app/bms/COCRDSL.bms` (31 `DFHMDF` fields), reached at the card detail route.
 *
 * Purpose
 * -------
 * Render one card as a read-only record view and offer the single transition the reference screen
 * offers, into the update screen. It replaces CICS transaction CCDL, which `app/csd/CARDDEMO.CSD`
 * L347-L348 binds to that program, and it publishes the field labels and guidance strings the
 * screen tests assert against.
 *
 * Composition
 * ------------
 * Assumptions: the record is rendered with antd `Descriptions` rather than a form, because four of
 * the six data fields the mapset paints are read-only -- `CRDNAME` carries no `ATTRB` at
 * `app/bms/COCRDSL.bms` L107 and `CRDSTCD`, `EXPMON` and `EXPYEAR` are `ATTRB=(ASKIP)` at L116, L126
 * and L133. Only the two search fields are `UNPROT`, and they are rendered as a form of their own
 * above the record, so the record view offers no editing affordance the transaction does not have.
 *
 * Selector-addressed and criteria-gathering arrivals
 * ---------------------------------------------------
 * Assumptions: the reference screen has TWO arrivals and this screen reproduces both. Arriving from
 * the browse screen the program skips its own field edits and reads immediately, because "SELECTION
 * CRITERIA ALREADY VALIDATED" (`app/cbl/COCRDSLC.cbl` L336-L344), and `1300-SETUP-SCREEN-ATTRS` at
 * L505-L512 moves `DFHBMPRF` into both input fields so they are PROTECTED on that arrival. Arriving
 * from anywhere else the program sends the map with `DFHBMFSE` in both fields -- unprotected -- under
 * the comment "COMING FROM SOME OTHER CONTEXT / SELECTION CRITERIA TO BE GATHERED" (L350-L356), and
 * gathers the account and card numbers itself. The two arrivals map onto the target exactly: a route
 * carrying a usable selector is the first, and one carrying none is the second.
 *
 * Refactoring Rationale: an earlier revision of this screen reproduced only the first arrival. It
 * omitted the mapset's two editable fields altogether -- `ACCTSID` at `app/bms/COCRDSL.bms` L84-L88
 * and `CARDSID` at L96-L100 -- and answered an unusable selector with a dead-end result surface, so
 * three behaviours the reference has were missing: the field edits at L647-L722 with their
 * first-message-wins precedence, the both-blank override at L637-L639, and the cursor and highlight
 * placement at L515-L552. They are restored here. What is NOT restored is a second way to reach a
 * card: a resolved search navigates to the selector route rather than rendering the record in place,
 * so the record on display is always the record the address names.
 *
 * Assumptions: the route parameter keeps its published spelling, `cardKey`, and is deliberately NOT
 * renamed to the `num` the authoring prompt named. What travels in it is an opaque selector a card
 * response published, never a card number: `ui/src/api/cards.ts` records that a target and its query
 * string are written into the edge access log and the browser's history before any application code
 * runs, and `getAdminCardDetail` is the single address permitted to publish a whole number. Renaming
 * the parameter would not by itself change what it carries, but the name is what every caller reads
 * to decide WHAT to put there -- so a route spelled `:num` invites a card number into exactly the two
 * stores the masking posture exists to keep it out of. A typed card number is instead resolved to its
 * selector by `lookupCard`, which sends the number in a request body.
 *
 * Assumptions: the criteria-gathering edits exist on the browse screen at `/cards` as well, and the
 * duplication is the reference's own rather than introduced here -- `COCRDLIC` carries its own copy of
 * the same two edits with BLANK rather than NOT-OK pre-set flags, which is why the two screens report
 * a missing value differently. The two implementations therefore encode two different contracts and
 * folding them into one would lose that distinction.
 *
 * WHY : Refactoring Rationale: a competing remedy for the same finding argued the opposite -- that the
 *       two fields should stay absent here and the criteria-gathering behaviour be restored on the
 *       BROWSE screen alone, which is where it also lives. That remedy shipped and stands: the account
 *       filter control, its edit and the first-error-wins precedence are all present in
 *       `ui/src/screens/cardList/index.tsx` and are asserted by the browse cases in
 *       `ui/src/screens/cardScreens.test.tsx`. What is NOT taken from it is the claim that the browse
 *       screen's copy makes this screen's redundant, because the two copies are not the same chain:
 *       `COCRDLIC` pre-sets its filter flags to BLANK and treats an unsupplied value as legitimate
 *       (`app/cbl/COCRDLIC.cbl` L1003-L1030), whereas `COCRDSLC` pre-sets its own to NOT-OK and
 *       REFUSES an unsupplied value (L692-L697) under a both-blank override no browse screen has. A
 *       single implementation would have to pick one of those two contracts and would lose the other,
 *       so both are delivered and each is asserted where it is defined -- this screen's chain by the
 *       edit cases in `ui/src/screens/cardDetail/cardDetail.test.tsx`.
 * WHY : Trade-offs: the accepted cost is that an operator can reach a card two ways -- by the address a
 *       browse row publishes and by retyping the pair here. That is the reference's own arrangement and
 *       it stays honest because a resolved search NAVIGATES to the selector address rather than
 *       rendering the record in place, so the record on display is always the record the address names.
 */

import { Button, Descriptions, Flex, Form, Input, Spin, Typography, theme } from 'antd';
import type { InputRef } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { CSSProperties, ChangeEvent, ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';

import { getCard, lookupCard } from '../../api/cards';
import type { CardDetail } from '../../api/cards';
import type { ApiError } from '../../api/types';
import { useShellSlot } from '../../layout/AppShell';
import { MessageBand } from '../../layout/MessageBand';
import { RECORD_VIEW_COLUMNS } from '../../layout/recordLayout';
import { useServerInstant } from '../../hooks/useServerInstant';
import { usePfKeys } from '../../layout/usePfKeys';
import {
  ACCESS_DENIED_NOT_AUTHORIZED,
  CARD_DETAIL_EDIT_CONTROL_LABEL,
  CARD_DETAIL_INVALID_LINK_GUIDANCE,
  SHARED_MESSAGES,
  STATUS_MESSAGES,
} from '../../messages/messages';
import { FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';
import { cardDetailPath, cardEditPath, isCardSelector } from '../../routes/cards';
import { navigateSafely, navigationHandler } from '../../routes/navigation';
import { ScreenTitle } from '../../layout/ScreenTitle';

/** Screen-level messages this screen renders, taken verbatim from the catalog keyed by its program. */
const CARD_DETAIL_MESSAGES = STATUS_MESSAGES.COCRDSLC;

/**
 * Mapset this screen stands in, which is the identity a message band sizes itself from.
 *
 * Refactoring Rationale: named once here rather than written as a literal at each band. The screen now
 * paints TWO of the mapset's message fields -- the row-20 informational line in its own body and the
 * row-23 error line the shell paints on its behalf -- and `app/cpy-bms/COCRDSL.CPY` L102/L194 declare
 * this mapset's `ERRMSGI`/`ERRMSGO` at `PIC X(80)` where nineteen mapsets declare `X(78)`, so the name
 * is what carries the width exception. Two spellings of one identity would be two places for that
 * exception to be dropped from.
 */
const CARD_DETAIL_MAPSET = 'COCRDSL';

/** CICS transaction identifier this screen replaces, as `app/csd/CARDDEMO.CSD` L347-L348 defines it. */
export const CARD_DETAIL_TRANSACTION_ID = 'CCDL';

/** Source program name, rendered in the header band exactly as the 3270 screen did. */
export const CARD_DETAIL_PROGRAM_NAME = 'COCRDSLC';

/**
 * Screen title, verbatim from the mapset's own title field at `app/bms/COCRDSL.bms` L78.
 *
 * Assumptions: this lives in the screen module rather than in the message catalog because that is the
 * ownership boundary the catalog itself draws: it excludes every `INITIAL=` literal a `.bms` file
 * paints and records that "a screen's own field labels belong to that screen under
 * `ui/src/screens/**`". A field label is positional -- it is meaningless apart from the control it
 * sits beside -- so centralising it would separate it from the only thing that gives it meaning.
 */
export const CARD_DETAIL_TITLE = 'View Credit Card Detail';

/**
 * The five field labels this screen paints, verbatim from `app/bms/COCRDSL.bms`.
 *
 * Assumptions: the interior runs of spaces and the trailing space on two of them are part of the
 * value, not formatting. The mapset pads each label to a fixed cell width so the colons line up down
 * the column, and the transcription rule for this tree is byte-exact; a renderer may collapse the
 * runs visually, but nothing here may discard them.
 */
export const CARD_DETAIL_FIELD_LABELS = {
  /** `app/bms/COCRDSL.bms` L83. */
  accountNumber: 'Account Number    :',
  /** `app/bms/COCRDSL.bms` L95. */
  cardNumber: 'Card Number       :',
  /** `app/bms/COCRDSL.bms` L106. */
  nameOnCard: 'Name on card      :',
  /** `app/bms/COCRDSL.bms` L115. */
  cardActive: 'Card Active Y/N   : ',
  /** `app/bms/COCRDSL.bms` L125. */
  expiryDate: 'Expiry Date       : ',
} as const;

/**
 * Function-key legend labels, split from this mapset's own legend literal.
 *
 * Assumptions: `app/bms/COCRDSL.bms` L147-L152 paints exactly `ENTER=Search Cards  F3=Exit` in one
 * `COLOR=YELLOW` field, and `app/cbl/COCRDSLC.cbl` L292-L293 admits exactly those two attention
 * identifiers, so the painted legend and the accepted key set agree and these two are the whole
 * contract. No third key is bound: the uniform legend labels were NOT assumed here, because two of
 * the three sibling card mapsets paint different key sets and one of them paints two legend fields.
 */
const CARD_DETAIL_KEY_LABELS = {
  ENTER: 'ENTER=Search Cards',
  PFK03: 'F3=Exit',
} as const;

/*
 * WHY : Refactoring Rationale: this screen's two additive visible strings -- the label of the control
 *       that opens the update screen, and the guidance shown when the address names no card -- were
 *       DECLARED here and are now imported from `ui/src/messages/messages.ts`. They were held locally
 *       on the catalogue's own exclusion of strings no COBOL source holds, but that exclusion is about
 *       `INITIAL=` field labels, which are positional. A control's accessible name and a sentence of
 *       guidance are neither positional nor label-like, and while they lived here a reader auditing
 *       the catalogue for completeness could not tell a missing additive string from a forgotten one.
 *       The two field-label groups below stay local, because those ARE positional `INITIAL=` literals.
 */

/** Declared width of the account search field, `ACCTSID LENGTH=11` at `app/bms/COCRDSL.bms` L84-L88. */
const ACCOUNT_SEARCH_WIDTH = 11;

/** Declared width of the card search field, `CARDSID LENGTH=16` at `app/bms/COCRDSL.bms` L96-L100. */
const CARD_SEARCH_WIDTH = 16;

/*
 * WHY : Assumptions: the four statuses below are named rather than written as bare numbers at the
 *       comparison sites, because each one selects a DIFFERENT outcome for the operator and a reader
 *       checking `describeRetrievalFailure` against the card contract needs to see which is which.
 *       The contract that declares them is `ui/src/api/cards.ts`, whose read operations document 400
 *       for a selector that cannot be opened, 401 when no session is held, 403 for a caller outside
 *       the required group, 404 when the selector addresses no row, and 500 or 503 for a service fault
 *       or a write window -- so `SERVICE_FAULT_STATUS` is a FLOOR rather than an equality, which is
 *       what lets 503 take the same arm as 500 without a second constant.
 */

/** HTTP status a service answers when no session is held; the transport ends the session itself. */
const SESSION_REFUSED_STATUS = 401;

/** HTTP status a service answers a caller outside the required group. */
const AUTHORITY_REFUSED_STATUS = 403;

/** HTTP status a service answers when the addressed card does not exist. */
const NOT_FOUND_STATUS = 404;

/** Lowest HTTP status that reports a service fault rather than a refused request. */
const SERVICE_FAULT_STATUS = 500;

/** Identifier tying the account search control to its own label. */
const ACCOUNT_SEARCH_FIELD_ID = 'card-detail-account-search';

/** Identifier tying the card search control to its own label. */
const CARD_SEARCH_FIELD_ID = 'card-detail-card-search';

/**
 * The three states one search field can be in after the reference's own field edit.
 *
 * Assumptions: the three names are the reference's own condition names rather than a scheme invented
 * here. `app/cbl/COCRDSLC.cbl` L56-L62 declares `FLG-ACCTFILTER-NOT-OK`, `FLG-ACCTFILTER-ISVALID` and
 * `FLG-ACCTFILTER-BLANK` and the same three for the card filter, and every downstream decision the
 * program makes -- which field takes the cursor at L515-L524, which is coloured red at L533-L539, and
 * which carries the literal `'*'` at L541-L551 -- reads exactly these three values. Collapsing BLANK
 * into NOT-OK would lose the marker, and collapsing it into ISVALID would lose the message.
 */
type SearchFieldState = 'ISVALID' | 'NOT_OK' | 'BLANK';

/**
 * The outcome of `2200-EDIT-MAP-INPUTS` as this screen holds it.
 *
 * Assumptions: one object carries both field states, the single message and the cursor target, because
 * the reference reaches all four from one pass: the two field edits run in order, the cross-field edit
 * runs after them, and `1300-SETUP-SCREEN-ATTRS` then reads the flags to place the cursor. Returning
 * them separately would let a caller apply the message from one turn to the flags of another.
 */
export interface CardSearchEdit {
  /** State of the account field after `2210-EDIT-ACCOUNT` (`app/cbl/COCRDSLC.cbl` L647-L681). */
  readonly account: SearchFieldState;
  /** State of the card field after `2220-EDIT-CARD` (`app/cbl/COCRDSLC.cbl` L685-L722). */
  readonly card: SearchFieldState;
  /** The single sentence row 23 shows, or `null` when both fields were accepted. */
  readonly message: string | null;
  /** Which control receives the cursor, per the `EVALUATE` at `app/cbl/COCRDSLC.cbl` L515-L524. */
  readonly cursor: 'account' | 'card';
  /** The accepted account number, or `null` when the field was not accepted. */
  readonly accountId: string | null;
  /** The accepted card number, or `null` when the field was not accepted. */
  readonly cardNumber: string | null;
}

/**
 * Applies the reference's blank sentinel to a raw search entry.
 *
 * Assumptions: a lone `'*'` counts as no input, and blanks do too. `app/cbl/COCRDSLC.cbl` L615-L627
 * tests each field for `'*'` or `SPACES` and moves `LOW-VALUES` into the working field for either, so
 * the two are one case. The sentinel has to be recognised because the screen WRITES `'*'` into a field
 * it rejected as blank -- L543 and L549 -- and the next turn therefore reads its own marker back; an
 * operator pressing Enter twice on an empty screen must not be told the marker is a malformed number.
 *
 * Trade-offs: surrounding blanks are trimmed as well, which the reference cannot do because a 3270
 * field is space-filled to its declared width. Trimming is the browser equivalent: a pasted value with
 * a trailing space would otherwise fail the digits test and be reported as a wrong-width number, which
 * names the wrong reason.
 * @param {string} raw - The value as the control currently holds it.
 * @returns {string} The entry with the sentinel and surrounding blanks removed, so an empty result
 *   means no value was supplied.
 */
function normaliseSearchEntry(raw: string): string {
  const trimmed = raw.trim();
  return trimmed === FIELD_ERROR_TOKENS.blankMarker ? '' : trimmed;
}

/**
 * Reduces a keystroke or a paste to the characters a search field accepts.
 *
 * Assumptions: the sentinel is admitted alongside the digits, for the round-trip reason
 * {@link normaliseSearchEntry} records -- a field this screen marked with `'*'` has to be able to hold
 * that character until the operator replaces it.
 *
 * Trade-offs: filtering on the way IN as well as declaring `maxLength` on the control is deliberate.
 * `maxLength` bounds the length a browser accepts but says nothing about the alphabet, and the
 * reference's fields are `PICIN` numeric, so a pasted `4111-1111-1111-1111` would otherwise sit in the
 * control at its full width and be reported as a wrong-width number rather than simply not entered.
 * @param {string} raw - The proposed value from the control.
 * @param {number} width - The field's declared width.
 * @returns {string} The proposed value reduced to accepted characters and bounded to the width.
 */
function acceptSearchKeystrokes(raw: string, width: number): string {
  const kept = [...raw].filter(
    /**
     * Reports whether one character may be held by a numeric search field.
     * @param {string} character - One character of the proposed value.
     * @returns {boolean} True when the character is a digit or the blank marker.
     */
    (character: string): boolean =>
      (character >= '0' && character <= '9') || character === FIELD_ERROR_TOKENS.blankMarker,
  );
  return kept.join('').slice(0, width);
}

/**
 * Applies one field's edit, in the reference's branch order and with its own message precedence.
 *
 * Assumptions: the two paragraphs `2210-EDIT-ACCOUNT` and `2220-EDIT-CARD` are structurally identical
 * -- pre-set NOT-OK, test not-supplied first and set BLANK with the prompt sentence, then test
 * `IS NOT NUMERIC` and set NOT-OK with the filter sentence, otherwise set ISVALID -- so they are one
 * function taking the width and the two sentences rather than two near-copies. The `NOT NUMERIC` test
 * covers a wrong width as well, because the receiving fields are `PIC 9(11)` and `PIC 9(16)`: a shorter
 * entry cannot fill one.
 *
 * Refactoring Rationale: this function reports the sentence its own branch selects and does NOT decide
 * whether that sentence reaches the screen. It first took a flag standing in for the
 * `IF WS-RETURN-MSG-OFF` guard both paragraphs place around every `MOVE` to the message field (L656,
 * L668, L696 and L709), and a defect injection showed that flag could be inverted with no observable
 * effect: the caller already selects the FIRST non-null sentence, so one rule was encoded twice and
 * only one of the two encodings was reachable. Two encodings of one rule where one is dead is worse
 * than one, because a later reader cannot tell which is authoritative -- so the guard now lives only at
 * the selection in {@link editCardSearchInputs}, where swapping the two operands does change what an
 * operator sees.
 * @param {string} entry - The field's raw value as the control holds it.
 * @param {number} width - The field's declared width.
 * @param {string} blankMessage - Sentence for a field that carried no value.
 * @param {string} malformedMessage - Sentence for a field that carried something other than a number
 *   of the declared width.
 * @returns {{ state: SearchFieldState; message: string | null; value: string | null }} The field's
 *   state, the sentence its branch selects, and the accepted value.
 */
function editSearchField(
  entry: string,
  width: number,
  blankMessage: string,
  malformedMessage: string,
): {
  readonly state: SearchFieldState;
  readonly message: string | null;
  readonly value: string | null;
} {
  const supplied = normaliseSearchEntry(entry);
  const digits = new RegExp(`^[0-9]{${String(width)}}$`, 'u');

  // WHY : Assumptions: the all-zeroes case joins the not-supplied branch rather than the malformed
  //       one, because that is where the reference puts it -- `OR CC-ACCT-ID-N EQUAL ZEROS` is the
  //       third leg of the not-supplied test at L651-L653, not a separate test after it. It is
  //       compared as a run of zeroes at the declared width rather than by converting the entry to a
  //       number: `app/cpy/CVCRD01Y.cpy` declares these identifiers as `PIC X(n)` redefined as
  //       `PIC 9(n)`, so they are characters on the wire and numbers only inside arithmetic, and a
  //       conversion would discard leading zeros and route sixteen digits through an IEEE-754 double.
  if (supplied === '' || supplied === '0'.repeat(width)) {
    return { state: 'BLANK', message: blankMessage, value: null };
  }

  if (!digits.test(supplied)) {
    return { state: 'NOT_OK', message: malformedMessage, value: null };
  }

  return { state: 'ISVALID', message: null, value: supplied };
}

/**
 * Applies `2200-EDIT-MAP-INPUTS` to the two search entries.
 *
 * Assumptions: the order is the reference's -- account edit, then card edit, then the cross-field edit
 * -- and the order is load-bearing rather than incidental, because the message field is claimed by the
 * first edit that wants it. So two empty fields report the account's prompt from L654-L657 and the
 * card's prompt from L692-L697 is set and discarded, before the cross-field edit at L637-L639
 * replaces the whole thing UNCONDITIONALLY with `No input received`. That final override is the one
 * exception to first-wins in the paragraph, and it is why an operator pressing Enter on an empty
 * screen sees `No input received` and never `Account number not provided`.
 *
 * Assumptions: the two malformed sentences are the literals the paragraphs actually `MOVE` -- L669-L671
 * and L711-L713 -- and NOT the `SEARCHED-ACCT-NOT-NUMERIC` and `SEARCHED-CARD-NOT-NUMERIC` condition
 * names declared at L145-L149. Those two condition names are declared and never `SET` anywhere in the
 * program, so their text is unreachable; transcribing them would show an operator a sentence the
 * reference screen cannot produce. The literals that ARE moved are the same two the browse screen
 * moves, which is why they live in the shared group of the message catalogue.
 *
 * Assumptions: the cursor rule is transcribed from the `EVALUATE` at L515-L524 exactly, including its
 * `WHEN OTHER` arm -- the account field takes the cursor when nothing was refused, because it carries
 * the mapset's single `IC` attribute at L84.
 * @param {string} accountEntry - The account field's raw value.
 * @param {string} cardEntry - The card field's raw value.
 * @returns {CardSearchEdit} The two field states, the single sentence, the cursor target and the two
 *   accepted values.
 */
export function editCardSearchInputs(accountEntry: string, cardEntry: string): CardSearchEdit {
  const account = editSearchField(
    accountEntry,
    ACCOUNT_SEARCH_WIDTH,
    CARD_DETAIL_MESSAGES.WS_PROMPT_FOR_ACCT.text,
    SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER,
  );
  const card = editSearchField(
    cardEntry,
    CARD_SEARCH_WIDTH,
    CARD_DETAIL_MESSAGES.WS_PROMPT_FOR_CARD.text,
    SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER,
  );

  const bothBlank = account.state === 'BLANK' && card.state === 'BLANK';
  /*
   * WHY : Assumptions: the ACCOUNT operand comes first, and that order is the whole of the
   *       first-message-wins rule. `2210-EDIT-ACCOUNT` runs before `2220-EDIT-CARD` (L630-L634) and
   *       every `MOVE` to the message field in the second is guarded by `IF WS-RETURN-MSG-OFF`, so a
   *       turn refusing both fields shows the ACCOUNT's sentence. Swapping these two operands is
   *       therefore a behavioural change and not a stylistic one.
   */
  const message = bothBlank
    ? CARD_DETAIL_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text
    : (account.message ?? card.message);

  const cursor: 'account' | 'card' =
    account.state !== 'ISVALID' ? 'account' : card.state !== 'ISVALID' ? 'card' : 'account';

  return {
    account: account.state,
    card: card.state,
    message,
    cursor,
    accountId: account.value,
    cardNumber: card.value,
  };
}

/**
 * The subset of `Form.Item` props that carry a field-level refusal.
 *
 * Assumptions: this is an object to be spread rather than two attributes passed directly, because
 * `ui/tsconfig.json` enables `exactOptionalPropertyTypes` -- under which passing
 * `validateStatus={undefined}` is an error rather than an omission, so the accepted case has to be
 * expressed as an absent property instead of an undefined one.
 */
interface FieldRefusalProps {
  /** Present only when the field is refused; `Form.Item` renders its error treatment. */
  readonly validateStatus?: 'error';
}

/**
 * Maps a field's edit state onto the design system's field-error treatment.
 *
 * Assumptions: a refused field gets a COLOUR and no help text, which is what the reference gives it.
 * `app/cbl/COCRDSLC.cbl` L533-L539 moves `DFHRED` into the field's colour subfield and L532 moves the
 * single sentence into the screen's message field -- one message channel, not two -- so repeating the
 * sentence beneath the control would print it twice for one refusal. The blank case additionally
 * carries the `'*'` marker, which is written into the control's own value rather than passed here.
 *
 * Assumptions: both refused states take the same treatment because the reference colours both the same
 * -- L533 for NOT-OK and L544 for BLANK, both `DFHRED`. What separates them is the marker and the
 * sentence, not the colour.
 * @param {SearchFieldState | undefined} state - The field's state after the last turn, or `undefined`
 *   when no turn has been taken.
 * @returns {FieldRefusalProps} Props to spread onto the field's `Form.Item`.
 */
function refusalPropsFor(state: SearchFieldState | undefined): FieldRefusalProps {
  return state === undefined || state === 'ISVALID' ? {} : { validateStatus: 'error' };
}

/**
 * Narrows a rejected read to the normalised problem document the API client raises.
 *
 * Alternatives Considered: `isApiRequestError` from `ui/src/api/client.ts`, which is the obvious
 * candidate and is rejected for the reason the account view screen records for the same narrowing --
 * it is an `instanceof` check, so it answers `false` for the hand-assembled response doubles the test
 * harness under `ui/src/test/**` builds, and `client.ts` is not among this module's declared
 * dependencies whereas `ui/src/api/types.ts` is.
 * @param {unknown} reason - The value a rejected read settled with.
 * @returns {ApiError | null} The problem document, or `null` when the rejection carried none -- a
 *   `RangeError` from the client's own argument or response checks, or any other thrown value.
 */
function problemFrom(reason: unknown): ApiError | null {
  if (typeof reason !== 'object' || reason === null) {
    return null;
  }
  const candidate = (reason as { readonly problem?: unknown }).problem ?? reason;
  if (typeof candidate !== 'object' || candidate === null) {
    return null;
  }
  const { fieldErrors, status } = candidate as {
    readonly fieldErrors?: unknown;
    readonly status?: unknown;
  };
  return Array.isArray(fieldErrors) && typeof status === 'number' ? (candidate as ApiError) : null;
}

/**
 * Chooses the sentence a failed card read reports, from the status the failure carries.
 *
 * Refactoring Rationale: every rejection used to report the not-found sentence, on a stated belief that
 * "the transport module reports a rejection without its status". That belief was wrong -- `ApiError`
 * declares a required `status` member and `ApiRequestError` republishes it -- so a session that had
 * expired, a caller outside the required group, a malformed selector and a service fault were all
 * reported to the operator as a card that does not exist. The reference does branch here: it takes
 * `DFHRESP(NOTFND)` at `app/cbl/COCRDSLC.cbl` L755-L761 and composes a different message for any other
 * file response at L762-L771, so one sentence for every outcome was not faithful either.
 *
 * Assumptions: the not-found sentence is now used for 404 and for nothing else, which is the branch it
 * transcribes. A 5xx takes this screen's own `XREF-READ-ERROR` sentence from L155-L156 -- the closest
 * the program has to "the card file read failed for a reason other than absence" -- rather than the
 * composed `WS-FILE-ERROR-MESSAGE`, which appends an internal file name and the CICS response and
 * reason codes and is therefore withheld under the same redaction the catalogue's register applies at
 * its other sites. The correlation identifier on the problem document remains the way to recover the
 * suppressed detail server-side.
 *
 * Assumptions: 401 reports NOTHING, and that is deliberate rather than an omission. The transport
 * module ends the session on a 401 answered to a bearer-carrying request, so the route guard replaces
 * this screen with the sign-on screen within the same paint; a sentence written here would either
 * flash for one frame or, worse, imply the card is missing when the session is.
 *
 * Assumptions: a 400 shows the service's own sentence when it sent one, because it is authored
 * server-side to be read and rewording it here would give one message two voices.
 *
 * ⚠️ Refactoring Rationale: a 403 shows {@link ACCESS_DENIED_NOT_AUTHORIZED} and no longer the
 * transcribed administrator-only refusal. Both operations this screen calls -- the lookup and the
 * selector read -- declare `x-required-authority: carddemo-user` in
 * `services/card-service/src/main/resources/openapi/card-api.yaml`, which its authority model defines as
 * any authenticated caller, so a refusal of either means the token carries NEITHER CardDemo group. Naming
 * administrative authority told the operator the function was reserved when it was not, and pointed them
 * at rights they must not be granted while hiding the cause. The transcribed sentence stays where the
 * baseline used it -- the administrative route guard and the whole-number administrative read, which this
 * screen does not call.
 * @param {unknown} reason - The value the read rejected with.
 * @returns {string | null} The sentence to show, or `null` when the outcome is reported elsewhere.
 */
export function describeRetrievalFailure(reason: unknown): string | null {
  const problem = problemFrom(reason);

  if (problem === null) {
    return SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED;
  }
  if (problem.status === SESSION_REFUSED_STATUS) {
    return null;
  }
  if (problem.status === AUTHORITY_REFUSED_STATUS) {
    return ACCESS_DENIED_NOT_AUTHORIZED;
  }
  if (problem.status === NOT_FOUND_STATUS) {
    return CARD_DETAIL_MESSAGES.DID_NOT_FIND_ACCTCARD_COMBO.text;
  }
  if (problem.status >= SERVICE_FAULT_STATUS) {
    return CARD_DETAIL_MESSAGES.XREF_READ_ERROR.text;
  }

  const reported = problem.message ?? '';
  return reported === '' ? SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED : reported;
}

/**
 * Splits the stored ten-character expiry text into the two parts the mapset paints.
 *
 * Assumptions: the named groups are the reference's own regrouping of the same bytes.
 * `app/cbl/COCRDSLC.cbl` L84 declares `CARD-EXPIRAION-DATE-X PIC X(10)` and L85-L92 redefine it as
 * `CARD-EXPIRY-YEAR PIC X(4)`, a one-byte FILLER, `CARD-EXPIRY-MONTH PIC X(2)`, a second FILLER and
 * `CARD-EXPIRY-DAY PIC X(2)`, so the day is matched here only to establish that the whole value has
 * the shape those offsets assume -- it is never read.
 */
const STORED_EXPIRY_TEXT = /^(?<year>[0-9]{4})-(?<month>[0-9]{2})-(?<day>[0-9]{2})$/u;

/**
 * Renders a stored expiry the way the source screen paints it: month, a solidus, then year.
 *
 * Assumptions: the stored value is the ten-character ISO text `YYYY-MM-DD` and stays TEXT rather than
 * becoming an instant, which is the reason `ui/src/api/types.ts` gives for its own date members: the
 * expiry boundary is inclusive in the reference, where a transaction dated equal to the expiration
 * date posts and one day later is refused, so a timezone-induced day of drift would change an outcome
 * rather than a rendering.
 *
 * Assumptions: THE DAY IS DELIBERATELY DISCARDED, and that is the mapset's decision rather than a
 * simplification made here. `app/bms/COCRDSL.bms` paints exactly two data fields for this value --
 * `EXPMON LENGTH=2` at L126-L129 and `EXPYEAR LENGTH=4` at L133-L136 -- separated by a one-character
 * `INITIAL='/'` literal at L130-L132, and the program moves only `CARD-EXPIRY-MONTH` and
 * `CARD-EXPIRY-YEAR` into them at `app/cbl/COCRDSLC.cbl` L480 and L482. There is no map field for the
 * day, so rendering the stored text whole shows a component the terminal never displayed.
 *
 * Trade-offs: a value of unexpected shape is returned UNCHANGED rather than sliced at the fixed
 * offsets the reference's positional MOVE would use. Slicing blindly is the closer transcription and
 * was rejected because of what it produces on a value that is not ten-character ISO text: a
 * plausible-looking `MM/YYYY` assembled from the wrong bytes, which a reader cannot tell apart from a
 * correct rendering. Passing the value through instead keeps a contract change visible.
 * @param {string} expirationDate - Stored expiry as ten-character `YYYY-MM-DD` text, exactly as
 *   `CardDetail` carries it.
 * @returns {string} The `MM/YYYY` rendering the mapset's two fields and their separator produce
 *   together, or the argument unchanged when it is not ten-character ISO date text.
 */
export function formatCardExpiry(expirationDate: string): string {
  const matched = STORED_EXPIRY_TEXT.exec(expirationDate);
  // Assumptions: both parts are read through optional chaining and tested before use because
  //   `noUncheckedIndexedAccess` is in force in ui/tsconfig.json, which types a capture-group lookup
  //   as `string | undefined`. The test the compiler requires is also the unexpected-shape branch the
  //   trade-off above describes, so one expression discharges both.
  const year = matched?.groups?.year;
  const month = matched?.groups?.month;
  // WHY : Alternatives Considered: a guard clause of the ordinary form -- `if (...) { return
  //       expirationDate; }` followed by the composed return -- which is the shape this function would
  //       otherwise take. The single expression is kept because the unexpected-shape branch and the
  //       compiler-required test are the SAME test, so one expression discharges both and a guard
  //       clause would state the condition twice.
  // WHY : Refactoring Rationale: an earlier revision of this note gave a different and now-obsolete
  //       reason -- that a two-space `if (` above the component would be mistaken for the component's
  //       own first early return by `ui/src/layout/screenHeaderClock.test.tsx`. That was true of the
  //       search that test used and is no longer: it now finds the component declaration first and
  //       searches only inside it, so a module-level helper may carry a guard clause freely.
  return year === undefined || month === undefined ? expirationDate : `${month}/${year}`;
}

/**
 * Renders one card addressed by the opaque selector in its route.
 *
 * Assumptions: the route parameter is validated before any request is issued, so a card number or a
 * masked rendering pasted into the address bar produces this screen's own invalid-link state rather
 * than an HTTP 400 -- and, more importantly, is never interpolated into a request line.
 * @returns {ReactElement} The card detail screen or a bounded invalid-link result.
 */
export function CardDetailScreen(): ReactElement {
  const navigate = useNavigate();
  /*
   * WHY : Assumptions: the fixed-pitch face is read as a token NAME from ui/src/theme/tokens.ts and
   *       resolved through `cssVar`, never written as a font literal. `cssVar` returns the reference
   *       form -- `var(--ant-font-family-code)` -- so the element keeps following the theme antd 6
   *       installs through CSS variables, whereas the sibling `token` member of the same hook returns
   *       a RESOLVED value and would bake today's face into this element and silently opt it out of
   *       later theme changes. `ui/src/layout/ScreenHeader.tsx` L463 reads the same token the same way
   *       for the same two fixed-width slots it paints.
   */
  const { cssVar } = theme.useToken();
  // WHY : Assumptions: read here, at the top of the component and above every early return, because
  //       the rules of hooks require an unconditional call site -- the early returns below would make
  //       a later call conditional. Reading it during render is deliberate rather than incidental: the
  //       band displays a PAINT-time instant, which is the property the baseline had because
  //       `POPULATE-HEADER-INFO` re-read the clock on each `SEND MAP` rather than on a timer.
  const paintedAt = useServerInstant();
  /*
   * WHY : Assumptions: the parameter is spelled `cardKey`, which is the name
   *       `ui/src/routes/cards.ts` L88 declares in `CARD_DETAIL_ROUTE = '/cards/:cardKey'` and which
   *       `ui/src/router.tsx` mounts this screen under. The spelling is load-bearing rather than
   *       cosmetic: `useParams` resolves an unmatched name to `undefined` without any diagnostic, so a
   *       near-miss such as `num` or `cardNumber` would compile, type-check and render this screen's
   *       invalid-link result on every visit -- a screen that is permanently empty for a reason no
   *       error reports.
   * WHY : Assumptions: what the parameter carries is an opaque SELECTOR and never a card number, so
   *       the guard below is `isCardSelector` rather than a length or digit test. `ui/src/api/cards.ts`
   *       records why the number may not travel in a path at all: a target and its query string are
   *       written into the edge access log and the browser's history before any application code runs,
   *       and neither store is reachable by anything this screen could add afterwards.
   */
  const { cardKey: routeIdentifier } = useParams<{
    cardKey: string;
  }>();
  const [card, setCard] = useState<CardDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [accountEntry, setAccountEntry] = useState('');
  const [cardEntry, setCardEntry] = useState('');
  /*
   * WHY : Assumptions: the edit OUTCOME is held rather than recomputed during render, because the
   *       reference only edits on a turn -- `2200-EDIT-MAP-INPUTS` runs from
   *       `2000-PROCESS-INPUTS` on a re-entry (`app/cbl/COCRDSLC.cbl` L583-L586) and never on the
   *       first send. Recomputing per keystroke would refuse an empty screen before the operator had
   *       pressed anything, and would clear a refusal as soon as one character was typed rather than
   *       when the next turn accepted it.
   */
  const [edit, setEdit] = useState<CardSearchEdit | null>(null);
  const accountControl = useRef<InputRef>(null);
  const cardControl = useRef<InputRef>(null);
  /*
   * WHY : Assumptions: the read generation is a REF and not state, because nothing renders from it and
   *       it must be readable synchronously by a settlement that ran before the next render. A state
   *       value would be read from the closure of the render that opened the read, so every settlement
   *       would compare its own number against itself and every one of them would look current.
   */
  const readGeneration = useRef(0);

  const selector =
    routeIdentifier !== undefined && isCardSelector(routeIdentifier) ? routeIdentifier : null;

  const openRead = useCallback(
    /**
     * Opens a new read generation and answers the predicate its settlements are guarded by.
     *
     * ⚠️ Refactoring Rationale: this bookkeeping was inline in the selector read and ABSENT from
     * the criteria lookup, so one of the screen's two service calls applied its answer unconditionally.
     * The lookup's answer is a NAVIGATION, which makes the omission worse than a stale paint: a
     * resolution settling after the operator had already left -- followed a different row, signed off,
     * or unmounted the screen -- moved the address to a card they were no longer asking for, and in the
     * sign-off case moved it while the session that authorised the lookup no longer existed. Naming the
     * bookkeeping once means a third caller cannot repeat the omission by writing the easy half.
     *
     * Alternatives Considered: a second ref of its own for the lookup. Rejected because the two calls
     * are alternatives rather than companions -- an arrival with a selector reads, an arrival without one
     * looks up, and a route change turns the second into the first -- so they must supersede EACH OTHER.
     * Two counters would each look current while the other's answer was applied.
     * @returns {() => boolean} A predicate that is true only while no later read has been opened.
     */
    (): (() => boolean) => {
      const generation = readGeneration.current + 1;

      readGeneration.current = generation;

      /**
       * Whether the read this predicate was made for is still the one the screen wants.
       * @returns {boolean} True while no later read has been opened.
       */
      return function isCurrent(): boolean {
        return readGeneration.current === generation;
      };
    },
    [],
  );

  useEffect(
    /**
     * Supersedes whatever read or lookup is outstanding when this screen unmounts.
     *
     * Assumptions: the cleanup moves the generation rather than setting a flag, because the guard the
     * settlements already hold is a generation comparison -- so one mechanism covers supersession by a
     * later read and supersession by the screen going away, and there is no second condition for a
     * settlement to forget to test.
     *
     * Assumptions: an empty dependency list, so this runs at mount and its cleanup at unmount ONLY. A
     * cleanup keyed on the selector would fire on every route change, which the read opened by that
     * change already handles by opening a later generation.
     * @returns {() => void} Cleanup that invalidates every generation opened so far.
     */
    (): (() => void) => {
      /**
       * Moves the generation past every read opened so far, so no outstanding answer applies.
       * @returns {void} Nothing; the moved generation is the whole effect.
       */
      return function invalidateOutstandingReads(): void {
        readGeneration.current += 1;
      };
    },
    [],
  );

  /*
   * WHY : Refactoring Rationale: the read is a named callback rather than a body inlined in the
   *       effect, because two callers now need it -- the mount effect and the Enter key. The source
   *       program has the same shape: `9000-READ-DATA` is one paragraph performed both on entry from
   *       the list screen and from the Enter arm (`app/cbl/COCRDSLC.cbl` L339-L345), so a single
   *       reader is the source's own structure rather than a convenience. `useCallback` is what keeps
   *       it usable as an effect dependency; an ordinary function would be a new value each render
   *       and would re-run the effect on every one of them.
   */
  const reload = useCallback(
    /**
     * Reads the addressed card and publishes it, or settles without a request when the selector was
     * rejected.
     *
     * ⚠️ Refactoring Rationale: each read opens a GENERATION and applies its answer only while that
     * generation is still the current one. Every read used to apply unconditionally, and two reads can
     * be outstanding at once for two ordinary reasons: a route change from one card to another
     * re-creates this callback and the effect re-runs it while the first request is in flight, and the
     * Enter arm re-reads on demand. Whichever settled LAST won, so following a list row while a slower
     * read of the previous card was outstanding could leave the previous card's embossed name, expiry
     * and status under the new card's route -- a record attributed to the wrong card, which is a
     * disclosure and not merely a stale view.
     *
     * Alternatives Considered: `AbortController` threaded into `getCard`. Rejected because it would
     * widen the transport signature for every caller of that operation to fix an ordering property of
     * this screen, and because an aborted request still has to be prevented from applying -- so the
     * guard is needed either way and the signature change buys only the cancelled round trip. The
     * finding itself admits either mechanism.
     * @returns {void} Completion is represented by the screen's own state.
     */
    (): void => {
      if (selector === null) {
        setLoading(false);
        return;
      }
      const isCurrent = openRead();

      setLoading(true);
      setError(null);

      getCard(selector).then(
        /**
         * Publishes the retrieved record to the screen, unless a later read has superseded this one.
         * @param {CardDetail} selectedCard - The record the service returned.
         */
        (selectedCard) => {
          if (!isCurrent()) {
            return;
          }
          setCard(selectedCard);
          /*
           * WHY : Assumptions: the two search controls are filled from the record that was read, so
           *       the selector arrival paints the same two values the reference paints on it. The
           *       browse screen moves the account and card numbers into `CDEMO-ACCT-ID` and
           *       `CDEMO-CARD-NUM` and `COCRDSLC` renders them into `ACCTSIDO` and `CARDSIDO` with
           *       both fields `DFHBMPRF` -- protected -- at `app/cbl/COCRDSLC.cbl` L505-L512. Leaving
           *       them empty on this arrival would show two blank fields beside a populated record.
           *       Trade-offs: the card control receives the RENDERED number, twelve asterisks and
           *       four digits, because that is the whole of what any ordinary read publishes. The
           *       field is protected on this arrival, so the rendering is never edited and never
           *       re-validated; an operator who wants to search types over it only after arriving
           *       without a selector, where the control starts empty.
           */
          setAccountEntry(selectedCard.accountId);
          setCardEntry(selectedCard.displayCardNumber);
          setLoading(false);
        },
        /*
         * WHY : Assumptions: the sentence is chosen from the failure's STATUS by
         *       `describeRetrievalFailure`, whose own note records why -- the source program branches
         *       on the file response at `app/cbl/COCRDSLC.cbl` L755-L771, and the status is the one
         *       member of the problem document carrying the same distinction. Whatever sentence it
         *       returns names no card, so no identifier reaches the band or any log that captures it.
         */
        /**
         * Reports a retrieval failure, choosing the sentence the failure's status selects.
         * @param {unknown} reason - The value the read rejected with.
         *
         * ⚠️ Assumptions: a superseded FAILURE is discarded exactly as a superseded success is. It is the
         * half that is easy to leave out, and leaving it out is worse than leaving out the other: the
         * refusal sentence would appear beneath a record that had loaded correctly, so an operator would
         * be told a card could not be read while looking at it.
         * @returns {void} Nothing; the outcome is published through the screen's own state.
         */
        (reason: unknown) => {
          if (!isCurrent()) {
            return;
          }
          setError(describeRetrievalFailure(reason));
          setLoading(false);
        },
      );
    },
    [openRead, selector],
  );

  useEffect(
    /**
     * Reads the record once on mount and again whenever the validated selector changes.
     * @returns {void} Completion is represented by the screen's own state.
     */
    (): void => {
      reload();
    },
    [reload],
  );
  /*
   * WHY : Assumptions: the cursor is moved by an EFFECT keyed on the edit outcome rather than inside
   *       the submit handler, because the control that must receive it may not be the one that has it
   *       and React has not yet committed the refusal's rendering when the handler returns.
   *       `1300-SETUP-SCREEN-ATTRS` places the cursor as part of building the map it is about to send
   *       (`app/cbl/COCRDSLC.cbl` L515-L524), which is the same ordering: decide, then paint, then
   *       position.
   */
  useEffect(
    /**
     * Puts the cursor on the control the reference's own cursor rule names.
     * @returns {void} Completion is the focused control.
     */
    (): void => {
      if (edit === null) {
        return;
      }
      const target = edit.cursor === 'account' ? accountControl : cardControl;
      target.current?.focus();
    },
    [edit],
  );

  /*
   * WHY : Refactoring Rationale: the search turn is a callback of its own rather than part of the
   *       Enter binding, because the reference reaches it from two places -- the Enter arm at
   *       `app/cbl/COCRDSLC.cbl` L357-L371 and the coerced invalid-key arm at L291-L299, which sets
   *       `CCARD-AID-ENTER` and falls into the same path. A binding body could not be shared with the
   *       invalid-key handler without duplicating it.
   */
  const submitSearch = useCallback(
    /**
     * Runs the reference's field edits and, when both fields are accepted, resolves the typed card
     * number to the selector its record is addressed by.
     * @returns {void} Completion is represented by the screen's own state, or by a route change.
     */
    (): void => {
      const outcome = editCardSearchInputs(accountEntry, cardEntry);
      setEdit(outcome);

      /*
       * WHY : Assumptions: the marker is written into the CONTROL rather than synthesised while
       *       rendering, because that is what the reference does and the difference is observable.
       *       `app/cbl/COCRDSLC.cbl` L541-L551 moves a literal `'*'` into the field's own output
       *       subfield, so the next turn reads the marker back as input -- which is precisely why
       *       `2200-EDIT-MAP-INPUTS` tests for `'*'` at L615 and L622 before it tests anything else.
       *       Synthesising it at render time would show the same glyph but would leave the control
       *       holding an empty string, so an operator typing after a refusal would be editing a value
       *       that had never contained what they could see.
       */
      if (outcome.account === 'BLANK') {
        setAccountEntry(FIELD_ERROR_TOKENS.blankMarker);
      }
      if (outcome.card === 'BLANK') {
        setCardEntry(FIELD_ERROR_TOKENS.blankMarker);
      }

      /*
       * WHY : Assumptions: a refused turn re-sends the screen and issues NO request, which is the
       *       reference's own control flow: `IF INPUT-ERROR` performs `1000-SEND-MAP` and returns
       *       without reaching `9000-READ-DATA` (`app/cbl/COCRDSLC.cbl` L360-L364).
       */
      if (outcome.cardNumber === null || outcome.accountId === null) {
        setError(outcome.message);
        return;
      }

      /*
       * WHY : Assumptions: the read key is the CARD number alone, and the accepted account number is
       *       validated and then not used as a key. That is the reference's own behaviour rather than
       *       an omission here: `9100-GETCARD-BYACCTCARD` moves only `CC-CARD-NUM` into the record
       *       identification field and the account move immediately above it is commented out
       *       (`app/cbl/COCRDSLC.cbl` L740-L741), and the by-account paragraph `9150-GETCARD-BYACCT`
       *       at L779 is never performed from anywhere. Both fields are still REQUIRED, because the
       *       card edit sets `INPUT-ERROR` when its field is blank, so a turn carrying only an account
       *       number is refused before any read -- which is why an account-only search is impossible
       *       in the reference and is impossible here.
       * WHY : Assumptions: the resolved record is reached by NAVIGATING to its selector route rather
       *       than by rendering it in place. It keeps one invariant that would otherwise be lost: the
       *       record on display is always the record the address names, so a reload, a bookmark and a
       *       shared link all show what the operator was looking at. `lookupCard` is what makes it
       *       possible -- it answers with the record's own selector, so the number never has to travel
       *       in the address to get there.
       */
      setError(null);
      setLoading(true);

      const isCurrent = openRead();

      lookupCard(outcome.cardNumber).then(
        /**
         * Moves to the address the resolved record is published under, unless this turn was superseded.
         * @param {CardDetail} resolved - The record the typed number named.
         * @returns {void} Completion is the route change.
         */
        (resolved: CardDetail): void => {
          /*
           * WHY : ⚠️ Assumptions: a superseded resolution navigates NOWHERE, and this is the arm
           *       the guard exists for. The screen has left the state that asked the question by the
           *       time a superseded answer arrives -- the operator followed a list row, took a later
           *       turn, signed off, or the tree unmounted -- and a navigation is not a stale paint the
           *       next render corrects: it moves the address to a card nobody asked for, under whatever
           *       session is current, and the operator's own destination is lost.
           */
          if (!isCurrent()) {
            return;
          }
          setLoading(false);
          // WHY : Assumptions: the selector is read from `key`, which is the member `CardSummary`
          //       publishes it under in `ui/src/api/types.ts`; `CardDetail` extends that shape rather
          //       than restating it, so the member is inherited and not declared beside the record's
          //       own three additions.
          navigateSafely(navigate, cardDetailPath(resolved.key));
        },
        /**
         * Reports a failed resolution, choosing the sentence the failure's status selects.
         * @param {unknown} reason - The value the resolution rejected with.
         * @returns {void} Nothing; the outcome is published through the screen's own state.
         */
        (reason: unknown): void => {
          // Assumptions: the FAILURE arm is guarded too, for the reason the selector read's own failure
          //   arm records: a superseded refusal would print "card not found" over whatever the screen
          //   moved on to, so an operator would be told a read failed while looking at its result.
          if (!isCurrent()) {
            return;
          }
          setError(describeRetrievalFailure(reason));
          setLoading(false);
        },
      );
    },
    [accountEntry, cardEntry, navigate, openRead],
  );

  /*
   * WHY : Assumptions: which arm Enter runs is decided by whether the address named a card, because
   *       that is the discriminator the reference uses. Arriving with criteria already validated it
   *       re-reads (L336-L345); arriving without them it edits what was typed (L357-L371). One
   *       binding, two arms, selected by the same condition the reference selects on.
   */
  const submitTurn = useCallback(
    /**
     * Runs whichever Enter arm this arrival is in.
     * @returns {void} Completion is represented by the screen's own state, or by a route change.
     */
    (): void => {
      if (selector === null) {
        submitSearch();
        return;
      }
      reload();
    },
    [selector, submitSearch, reload],
  );

  /*
   * WHY : Assumptions: the handler map is keyed by CICS attention identifier, so the PF13-PF24
   *       aliasing `app/cpy/CSSTRPFY.cpy` L54-L77 performs is applied once by the hook rather than by
   *       every screen. Exactly the two identifiers `app/cbl/COCRDSLC.cbl` L292-L293 admits are
   *       bound, and both carry a label because this mapset paints both of them.
   */
  const { bindings, invoke } = usePfKeys(
    {
      ENTER: {
        /**
         * Runs the Enter arm this arrival is in -- a re-read of the card on display, or the field
         * edits and the resolution of a typed card number. Both are the source program's own arms;
         * see {@link CardDetailScreen}'s `submitTurn` for the discriminator and its citations.
         * @returns {void} Nothing; the outcome is published through the screen's own state.
         */
        onInvoke: () => {
          submitTurn();
        },
        label: CARD_DETAIL_KEY_LABELS.ENTER,
      },
      PFK03: {
        /**
         * Returns to the caller, which the source program resolves to the list screen it was reached
         * from and otherwise to the main menu (`app/cbl/COCRDSLC.cbl` L305-L333). The browse screen
         * is this route's only caller in the delivered route table, so it is the single destination.
         * @returns {void} Nothing; the transition is performed as a side effect on the router.
         */
        onInvoke: () => {
          navigateSafely(navigate, '/cards');
        },
        label: CARD_DETAIL_KEY_LABELS.PFK03,
      },
    },
    {
      /**
       * Coerces an unmapped key into a reload of the card, showing no message.
       *
       * Assumptions: an unrecognised key re-runs the Enter arm and shows NO message, because that
       * is precisely what the source program does -- `app/cbl/COCRDSLC.cbl` L291-L299 sets an
       * invalid flag and then `SET CCARD-AID-ENTER TO TRUE`, so the key press is coerced rather
       * than reported. This deliberately differs from the sign-on screen, whose `WHEN OTHER`
       * branch does move `CCDA-MSG-INVALID-KEY` into the message field; carrying that behaviour
       * here would invent a message this screen never shows. Trade-offs: only the `unmapped`
       * rejection is coerced. A `disabled` rejection cannot arise on this screen, since neither
       * binding is ever disabled, so coercing it too would be unreachable code rather than
       * fidelity.
       * @param {object} rejection - Why the key was not dispatched, whose `reason`
       *   distinguishes an unmapped key from a disabled binding.
       * @returns {void} Nothing; the coerced arm performs the reload itself.
       */
      onInvalidKey: (rejection) => {
        if (rejection.reason === 'unmapped') {
          submitTurn();
        }
      },
    },
  );

  /*
   * WHY : Refactoring Rationale: the three persistent zones are DELEGATED to the one `AppShell` that
   *       `ui/src/App.tsx` mounts, where this screen used to compose all three itself. The per-screen
   *       composition was how the application worked before the shell was wired in, and keeping it
   *       once a shell is mounted would render a second title band, a second message line and a second
   *       named legend region on this screen.
   * WHY : Assumptions: the publication is made unconditionally, ABOVE the early return below, and WHAT
   *       it publishes is what varies. A hook called after an early return changes hook order between
   *       renders, which React reports as a broken component rather than a missing band, so the call
   *       site cannot move; the ternary is therefore the only place the distinction can live.
   * WHY : Assumptions: the ONE erased state is a read in flight, and an unaddressable selector is no
   *       longer one of them. A loading state has no counterpart in the reference at all, so a header
   *       whose clock and identifiers describe a record not yet read would be an invention; an arrival
   *       carrying no usable selector, by contrast, is a map the reference SENDS -- header rows, legend
   *       row and both message fields included -- under the comment "COMING FROM SOME OTHER CONTEXT /
   *       SELECTION CRITERIA TO BE GATHERED" at `app/cbl/COCRDSLC.cbl` L350-L356, so it publishes the
   *       whole slot and the operator can take a turn from it.
   * WHY : ⚠️ Trade-offs: the empty list is published rather than the `pfKeys` member being
   *       omitted, and the difference IS cosmetic where this note said it was not. The reason given was
   *       that omitting the member would activate key handling of the shell's own beside this screen's,
   *       and one PF12 press would then both end the session and take a turn; it is withdrawn, because
   *       the shell installs NO keyboard listener at all and offers sign-off as a rendered control, for
   *       the reason recorded at `SHELL_SIGN_OFF_LABEL`. The two forms also render the same thing --
   *       `PfKeyBar` returns `null` for an empty binding list, so neither paints a legend. The empty list
   *       is kept so the two arms of this ternary differ only in which zones carry content, which is what
   *       makes the erased arm reviewable against the one beside it.
   */
  useShellSlot(
    loading
      ? { pfKeys: { keys: [], onInvoke: invoke } }
      : {
          screen: {
            transactionId: CARD_DETAIL_TRANSACTION_ID,
            programName: CARD_DETAIL_PROGRAM_NAME,
          },
          now: paintedAt,
          message: { text: error, mapset: CARD_DETAIL_MAPSET },
          pfKeys: { keys: bindings, onInvoke: invoke },
        },
  );

  /*
   * WHY : Refactoring Rationale: an unusable selector no longer returns a shell-less result surface.
   *       It renders the whole screen with the two search fields ACTIVE, which is the reference's own
   *       answer to an arrival that carried no validated criteria -- `1000-SEND-MAP` under the comment
   *       "COMING FROM SOME OTHER CONTEXT / SELECTION CRITERIA TO BE GATHERED"
   *       (`app/cbl/COCRDSLC.cbl` L350-L356), with `DFHBMFSE` in both fields at L510-L511. The result
   *       surface was argued from `SEND-PLAIN-TEXT` at L838-L848, but that path serves the `WHEN OTHER`
   *       arm at L373-L380, which is reached when the communication area's context flag is neither
   *       ENTER nor RE-ENTER -- a corrupt control block, not a missing search key. Mapping a missing
   *       key onto it left an operator at a dead end the reference does not have, with the two controls
   *       that exist precisely to recover from it withheld.
   * WHY : Assumptions: the guidance sentence survives the change and is shown as the screen's INFO
   *       line, which is a field the reference has and this screen previously did not render --
   *       `INFOMSG` at `app/bms/COCRDSL.bms` L139. So the two message channels the mapset paints are
   *       now both present: the error line carries whatever the edits refused, and the info line
   *       carries either the reference's own prompt for input or, when a caller supplied an address
   *       that names no card, the sentence saying how to get a usable one.
   */
  const searchActive = selector === null;
  /*
   * WHY : Alternatives Considered: keeping the header band and the message band mounted around the
   *       spinner, so only the record region swapped. Rejected as out of proportion to what it buys
   *       here. A loading state has NO counterpart in the reference at all -- a 3270 terminal holds
   *       the previous map until the next one arrives, so there is no painted state to be faithful to
   *       -- which means neither choice can be argued from the source, and the plain spinner is the
   *       one that cannot go stale: composing the band would mean rendering a header whose clock and
   *       identifiers describe a record not yet read. Trade-offs: the accepted cost is one layout
   *       shift when the record arrives and the chrome appears beneath it. That cost is bounded to
   *       this first paint only; the band that exists specifically to stop LATER shifts -- reserving
   *       its own height whether or not it holds a message -- is mounted for every subsequent state.
   */
  if (loading) {
    return <Spin size="large" />;
  }

  /*
   * WHY : Assumptions: the three FIXED-WIDTH values below are rendered in the code face and the two
   *       free-text ones are not, which is the distinction `TYPOGRAPHY_TOKENS.fixedPitchData` records:
   *       the 3270 cell grid aligned every column for free, and a proportional face gives digits
   *       different advance widths, so the eleven-digit account identifier, the twelve asterisks and
   *       four digits of the masked rendering, and the `MM/YYYY` expiry stop lining up down the value
   *       column. The embossed name and the one-character active flag are excluded deliberately -- a
   *       name is proportional text with nothing to align against, and a single character cannot
   *       misalign.
   * WHY : Alternatives Considered: setting the face on the `Descriptions` component so every value
   *       inherited it. Rejected because it would put the 50-character embossed name in the code face
   *       as well, which neither aligns anything nor matches the reference: `CRDNAME` is the one data
   *       field on this mapset that carries no numeric or fixed-width content.
   */
  const fixedPitchValueStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

  /*
   * WHY : ⚠️ Refactoring Rationale: the source's INFORMATION line is rendered, where this screen painted
   *       only its error line and dropped the other entirely. The mapset declares TWO message fields, not
   *       one: `INFOMSG` is a 40-character `COLOR=NEUTRAL` field at `POS=(20,25)` (`app/bms/COCRDSL.bms`
   *       L139 to L143) and `ERRMSG` an 80-character `COLOR=RED` field at `POS=(23,1)` (L144 to L148), and
   *       `1400-SEND-SCREEN` moves a value into BOTH on every sent map -- `WS-RETURN-MSG` to `ERRMSGO` at
   *       `app/cbl/COCRDSLC.cbl` L494 and `WS-INFO-MSG` to `INFOMSGO` at L496. A successful retrieval
   *       therefore confirmed itself on the terminal and said nothing at all here.
   * WHY : Assumptions: the two sentences are the program's own and are SELECTED, not composed. It sets
   *       `FOUND-CARDS-FOR-ACCOUNT` on a normal file response (L754 and L795) and falls back to
   *       `WS-PROMPT-FOR-INPUT` whenever the field would otherwise be blank (L490 to L491), so the
   *       information line is never empty on a sent map -- which is why this derivation has no null arm.
   * WHY : Assumptions: the confirmation is reported PER TURN, which is why both a retrieved record and
   *       a settled read are required for it. `WS-INFO-MSG` lives in `WORKING-STORAGE` with no `VALUE`
   *       clause, and CICS gives each pseudo-conversational turn a fresh copy, so the field arrives
   *       blank on every turn and only the turn that read the record sets it -- a turn whose read
   *       failed reaches L490 with the field still blank and falls back to the prompt, even though the
   *       turn before it had confirmed. Keying the line on the record alone would confirm a retrieval
   *       this turn did not perform, and keying it on the error alone would confirm one before any
   *       record existed.
   * WHY : Trade-offs: this screen KEEPS the previously retrieved record on display when a re-read
   *       fails, where `1400-SEND-SCREEN` issues `SEND MAP ... ERASE` (L569-L577) and only moves the
   *       record fields into the map `IF FOUND-CARDS-FOR-ACCOUNT` (L474), so the terminal cleared them.
   *       Retaining them is the accepted divergence -- a browser that blanked a record the operator is
   *       reading in order to report that a refresh failed would lose information the failure did not
   *       invalidate -- and the information line is reported faithfully regardless, because it describes
   *       the turn rather than the region below it.
   */
  /*
   * WHY : Refactoring Rationale: the guidance sentence for an address that names no card is one arm of
   *       THIS derivation rather than a second informational surface of its own. Two independent
   *       derivations were written for the row-20 line -- one selecting between the retrieval
   *       confirmation and the prompt, the other between the prompt and the guidance -- and the mapset
   *       declares exactly ONE such field, `INFOMSG` at `app/bms/COCRDSL.bms` L139-L143, so rendering
   *       both would paint two row-20 lines where the terminal paints one. The three arms are ordered
   *       the way the program reaches them: `FOUND-CARDS-FOR-ACCOUNT` is set on a normal file response
   *       (`app/cbl/COCRDSLC.cbl` L754 and L795) and therefore wins whenever a record was retrieved on
   *       this turn; the guidance is shown when the caller supplied an address the screen cannot open,
   *       which is the only outcome the program has no sentence of its own for; and `WS-PROMPT-FOR-INPUT`
   *       is the fallback the program itself applies whenever the field would otherwise be blank
   *       (L490-L491), which is why this derivation has no null arm.
   */
  const informationLine =
    card !== null && error === null
      ? CARD_DETAIL_MESSAGES.FOUND_CARDS_FOR_ACCOUNT.text
      : searchActive && routeIdentifier !== undefined
        ? CARD_DETAIL_INVALID_LINK_GUIDANCE
        : CARD_DETAIL_MESSAGES.WS_PROMPT_FOR_INPUT.text;

  return (
    <Flex vertical gap="large">
      {/*
       * Assumptions: level 3 sits below the band's own level-4 application heading in size but is a
       * SEPARATE field in the source -- the mapset paints its title at row 4, under the two title
       * rows the band reproduces -- so the two are not competing for one slot. Level 2 was used here
       * before the band was composed, when this was the only heading on the screen.
       */}
      <ScreenTitle>{CARD_DETAIL_TITLE}</ScreenTitle>
      {/*
       * WHY : Refactoring Rationale: the row-23 error line this screen used to compose is DELEGATED to the
       *       one `AppShell` above, published in the `useShellSlot` call as `message`. The prose that stood
       *       here recorded why the band -- rather than a bare alert -- owns that line, and why the mapset
       *       name travels with it: `app/cpy-bms/COCRDSL.CPY` L102/L194 declare this screen's
       *       `ERRMSGI`/`ERRMSGO` at `PIC X(80)` where nineteen mapsets declare `X(78)`, so the name is what
       *       carries the width exception. Both facts still hold and are now discharged by the delegation,
       *       which passes `CARD_DETAIL_MAPSET` with the sentence; composing a second band here would paint
       *       two row-23 lines on one screen.
       */}
      {/*
       * WHY : Refactoring Rationale: the row-20 informational line is painted ONCE, at the close of this
       *       body rather than here above the search fields. Two independent revisions each added it,
       *       one at each position, and the mapset declares a single such field -- `INFOMSG` at
       *       `app/bms/COCRDSL.bms` L139-L143 -- so painting both put two row-20 lines on one screen and
       *       made `ui/src/screens/cardScreens.test.tsx` count two carriers of one sentence. The closing
       *       position is the one the source paints: row 20 sits BELOW the last record field at row 15
       *       and above the row-23 error line, so it reads last.
       * WHY : Assumptions: that line stays a SEPARATE surface from the row-23 band and is not folded
       *       into it, because the program drives the two from two different working-storage fields --
       *       `WS-INFO-MSG` into `INFOMSGO` and `WS-RETURN-MSG` into `ERRMSGO`
       *       (`app/cbl/COCRDSLC.cbl` L494 and L496) -- and `ATTRB=(PROT)` on the first against
       *       `ATTRB=(ASKIP,BRT,FSET)` on the second is what distinguishes an instruction from a
       *       refusal. Folding them would make an ordinary prompt look like one.
       */}
      {/*
       * WHY : Assumptions: the two search fields are rendered on BOTH arrivals and differ only in
       *       whether they accept input, because that is exactly how the reference differs -- the
       *       fields are painted either way and `1300-SETUP-SCREEN-ATTRS` chooses `DFHBMPRF` or
       *       `DFHBMFSE` for them at `app/cbl/COCRDSLC.cbl` L505-L512. Hiding them on the
       *       selector arrival would drop two values the reference displays; making them editable there
       *       would offer a second way to change the record on display, which the reference refuses by
       *       protecting them.
       * WHY : Assumptions: `Form` wraps the pair purely so each control can carry the design system's
       *       own field-error treatment. It has no submit of its own: the turn is taken by Enter
       *       through the key bindings, which is the only way the reference takes one, and a nested
       *       submit button would offer a second.
       */}
      <Form layout="vertical" component="div">
        <Flex gap="large" wrap="wrap">
          <Form.Item
            label={CARD_DETAIL_FIELD_LABELS.accountNumber}
            htmlFor={ACCOUNT_SEARCH_FIELD_ID}
            {...refusalPropsFor(edit?.account)}
          >
            {/*
             * WHY : Assumptions: `maxLength` is eleven because the mapset declares `LENGTH=11` for
             *       `ACCTSID` at `app/bms/COCRDSL.bms` L84-L88 and the receiving field is
             *       `CC-ACCT-ID PIC X(11)` at `app/cpy/CVCRD01Y.cpy` L34. Both figures are the same
             *       one, so the control cannot hold a value the edit would then reject for width.
             * WHY : Assumptions: `autoFocus` is on this control and on no other, because `ACCTSID`
             *       carries this mapset's single `IC` attribute at L84 and the cursor `EVALUATE`'s
             *       `WHEN OTHER` arm returns the cursor here (`app/cbl/COCRDSLC.cbl` L522-L523). Later
             *       turns move it through the ref, not through this attribute.
             * WHY : Alternatives Considered: the design system's numeric input with `stringMode`.
             *       Rejected because it cannot hold this field's blank marker -- L543 writes a literal
             *       `'*'` into it and L615 reads it back -- and a text control with digit filtering
             *       needs no `stringMode` to stay clear of an IEEE-754 double, because it never holds
             *       a number at all.
             */}
            <Input
              id={ACCOUNT_SEARCH_FIELD_ID}
              ref={accountControl}
              autoFocus={searchActive}
              disabled={!searchActive}
              aria-invalid={edit !== undefined && edit !== null && edit.account !== 'ISVALID'}
              inputMode="numeric"
              maxLength={ACCOUNT_SEARCH_WIDTH}
              value={accountEntry}
              onChange={
                /**
                 * Records the proposed account number, reduced to the characters the field accepts.
                 * @param {ChangeEvent<HTMLInputElement>} event - Change event whose target value is
                 *   the text the operator typed or pasted.
                 * @returns {void} Completion is represented by this screen's own state.
                 */
                (event: ChangeEvent<HTMLInputElement>): void => {
                  setAccountEntry(acceptSearchKeystrokes(event.target.value, ACCOUNT_SEARCH_WIDTH));
                }
              }
            />
          </Form.Item>
          <Form.Item
            label={CARD_DETAIL_FIELD_LABELS.cardNumber}
            htmlFor={CARD_SEARCH_FIELD_ID}
            {...refusalPropsFor(edit?.card)}
          >
            {/*
             * WHY : Assumptions: `maxLength` is sixteen because the mapset declares `LENGTH=16` for
             *       `CARDSID` at `app/bms/COCRDSL.bms` L96-L100 and the receiving field is
             *       `CC-CARD-NUM PIC X(16)` at `app/cpy/CVCRD01Y.cpy` L38. The same width also holds
             *       the rendered form this control shows on the selector arrival -- twelve asterisks
             *       and four digits is sixteen characters -- so neither arrival can overflow it.
             */}
            <Input
              id={CARD_SEARCH_FIELD_ID}
              ref={cardControl}
              disabled={!searchActive}
              aria-invalid={edit !== undefined && edit !== null && edit.card !== 'ISVALID'}
              inputMode="numeric"
              maxLength={CARD_SEARCH_WIDTH}
              value={cardEntry}
              onChange={
                /**
                 * Records the proposed card number, reduced to the characters the field accepts.
                 * @param {ChangeEvent<HTMLInputElement>} event - Change event whose target value is
                 *   the text the operator typed or pasted.
                 * @returns {void} Completion is represented by this screen's own state.
                 */
                (event: ChangeEvent<HTMLInputElement>): void => {
                  setCardEntry(acceptSearchKeystrokes(event.target.value, CARD_SEARCH_WIDTH));
                }
              }
            />
          </Form.Item>
        </Flex>
      </Form>
      {/*
       * WHY : Trade-offs: the record is laid out by GROUPING and not by the mapset's absolute
       *       coordinates, which is documented gap G1 in the `DESIGN_GAPS` register of
       *       `ui/src/theme/tokens.ts`. Every field on this map carries a `POS=(row,col)` on a fixed
       *       24x80 character grid -- `ACCTSID` at `POS=(7,45)`, `CRDNAME` at `POS=(11,25)`,
       *       `EXPYEAR` at `POS=(15,30)` -- and none of that survives. What is given up is
       *       character-cell fidelity; what is kept is field grouping, reading order and tab order,
       *       which are the properties an operator actually navigates by. Reproducing the grid was
       *       rejected on two specific grounds: absolute positioning cannot reflow, so the layout
       *       would break at any viewport narrower than 80 monospace columns, and a grid of
       *       positioned cells gives a screen reader no label-to-value association, whereas
       *       `Descriptions` emits each pair as a row a reader announces together.
       * WHY : Refactoring Rationale: the column count comes from `RECORD_VIEW_COLUMNS` and is no longer
       *       the literal `2` this block was authored with. Two-up at EVERY width was the defect: at a
       *       phone width each value cell gets roughly half of 375 pixels less its label cell, and the
       *       three fixed-pitch values here -- an eleven-digit account identifier, a sixteen-character
       *       masked rendering and an `MM/YYYY` expiry -- are rendered in a face whose advance width
       *       cannot shrink, so the bordered table pushed past the viewport instead of reflowing.
       *       `ui/src/layout/recordLayout.ts` states the policy once for all three record screens: one
       *       column below the design system's medium breakpoint, two from it upward.
       * WHY : Assumptions: the WIDE case remains two columns because that figure is the mapset's own
       *       shape rather than a preference -- the source paints its label column and its value column
       *       side by side down the body zone, at the two distinct `col` values the `POS=` operands
       *       above show. What the narrow case gives up is that left/right PAIRING, which is positional
       *       identification G1 has already surrendered; reading order is preserved at both widths.
       */}
      {card === null ? null : (
        <Descriptions bordered column={RECORD_VIEW_COLUMNS}>
          {/*
           * Assumptions: the five labels and their order are the mapset's, read top to bottom from
           * `app/bms/COCRDSL.bms` -- account number, card number, name on card, active flag, expiry
           * date. An earlier revision of this screen labelled them `Card`, `Account`, `Embossed
           * name`, `Expiration` and `Status`, none of which the source screen paints.
           */}
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.accountNumber}>
            <Typography.Text style={fixedPitchValueStyle}>{card.accountId}</Typography.Text>
          </Descriptions.Item>
          {/*
           * WHY : Assumptions: the rendering is emitted EXACTLY as the service returned it -- this
           *       screen neither masks nor unmasks. `ui/src/api/cards.ts` produces the masked form
           *       server-side and checks it on arrival against the contract's own
           *       `^\*{12}[0-9]{4}$`, and the whole sixteen-digit number is published only by
           *       `getAdminCardDetail`, a SEPARATE address under a separate authority that answers an
           *       ordinary caller with HTTP 403. Reformatting here would either undo a deliberate
           *       redaction or re-apply one to a value already redacted, and re-masking would hide a
           *       server-side rendering failure the client is positioned to report.
           */}
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.cardNumber}>
            <Typography.Text style={fixedPitchValueStyle}>{card.displayCardNumber}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.nameOnCard}>
            {card.embossedName}
          </Descriptions.Item>
          {/*
           * Assumptions: the flag renders as the stored character, `Y` or `N`, which is what the
           * source screen displays -- `CRDSTCD` is a one-character field and its label names the
           * domain, `Card Active Y/N`. An earlier revision expanded it to `Active` and `Inactive`,
           * two words no COBOL source in this application holds, and the expansion also contradicted
           * the label beside it.
           */}
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.cardActive}>
            {card.activeStatus}
          </Descriptions.Item>
          {/*
           * WHY : Refactoring Rationale: the stored value was rendered WHOLE here, as `2023-01-20`,
           *       and it is now put through `formatCardExpiry` to paint `01/2023`. Rendering it whole
           *       showed the day, and the terminal had no field to show a day in: the mapset paints
           *       `EXPMON LENGTH=2` and `EXPYEAR LENGTH=4` with a `'/'` literal between them
           *       (`app/bms/COCRDSL.bms` L126-L136) and the program fills only those two
           *       (`app/cbl/COCRDSLC.cbl` L480 and L482). It also showed them in the opposite order
           *       and under a different separator, so an operator reading `2023-01` off this screen
           *       would read a year where the terminal put a month.
           */}
          <Descriptions.Item label={CARD_DETAIL_FIELD_LABELS.expiryDate}>
            <Typography.Text style={fixedPitchValueStyle}>
              {formatCardExpiry(card.expirationDate)}
            </Typography.Text>
          </Descriptions.Item>
        </Descriptions>
      )}
      {/*
       * Refactoring Rationale: the bespoke `Back` control is gone. It navigated to the browse screen,
       * which is exactly what the PF3 binding below now does, and the key bar renders that binding as
       * a button of its own -- so keeping both would have put two controls for one action on the
       * screen, with two places for the destination to drift apart. The remaining control has no key
       * behind it, which is why it stays.
       */}
      {/*
       * WHY : Assumptions: the control is absent rather than disabled when the address names no card,
       *       because its destination is composed FROM the selector -- `cardEditPath` cannot be called
       *       without one -- so there is no address to disable a control towards. A disabled control
       *       would also imply the update screen is reachable once something is fixed on this screen,
       *       when in fact it becomes reachable only after a record has been resolved and the address
       *       has changed.
       */}
      {selector === null ? null : (
        <Flex gap="small">
          <Button
            type="primary"
            disabled={card === null}
            onClick={navigationHandler(navigate, cardEditPath(selector))}
          >
            {CARD_DETAIL_EDIT_CONTROL_LABEL}
          </Button>
        </Flex>
      )}
      {/*
       * WHY : Assumptions: ONE band is rendered here and it is the row-20 INFORMATIONAL line, because
       *       the mapset declares two independent message fields at two different rows and only one of
       *       them belongs to this screen's own field area -- `INFOMSG` at `POS=(20,25)`,
       *       `ATTRB=(PROT) COLOR=NEUTRAL`, `LENGTH=40` (`app/bms/COCRDSL.bms` L139-L143). It takes the
       *       `info` severity, which is the appearance its source field always had; the row-23
       *       `COLOR=RED` `ERRMSG` beside it (L144-L148) is the shell's, delegated above.
       * WHY : Assumptions: it closes the body, which is the reading order the source paints -- row 20
       *       sits below the last record field at row 15 and above the row-23 error line, and the shell
       *       paints rows 23 and 24 immediately beneath this region.
       * WHY : Alternatives Considered: capping the band at the source field's own 40 characters instead
       *       of naming the mapset. Rejected because the width the band publishes is a MEASURED census
       *       of the twenty-one `ERRMSGI`/`ERRMSGO` declarations, typed `78 | 80`, and a per-screen
       *       per-field third figure would move a design value out of the band and into a screen. The
       *       figure is a `maxInlineSize` CAP rather than a truncation, and both sentences this line can
       *       hold are 31 and 36 characters, so no cap at or above 40 changes what is painted; naming
       *       the mapset is also what every other band on the delivered screens does.
       */}
      <MessageBand
        mapset={CARD_DETAIL_MAPSET}
        severity="info"
        message={informationLine}
        line="information"
      />
      {/*
       * Refactoring Rationale: the key legend that used to close this body, the message line above it
       * and the title band that opened it are all delegated to the shell in the `useShellSlot` call
       * above. No `legendColor` is delegated with them, because `app/bms/COCRDSL.bms` L148 paints this
       * screen's legend `COLOR=YELLOW`, which is the slot's own default and the 15-of-17 majority; the
       * browse screen is one of the two measured exceptions and does state it. The message keeps this
       * screen's mapset name for the reason it always did: `app/cpy-bms/COCRDSL.CPY` L102/L194 declare
       * `ERRMSGI`/`ERRMSGO` at `PIC X(80)` rather than the `X(78)` nineteen mapsets use, so omitting the
       * name would render this screen's message five characters narrower than the terminal did.
       */}
    </Flex>
  );
}
