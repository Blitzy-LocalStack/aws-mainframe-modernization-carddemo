/**
 * @file The bill payment screen, migrated from `app/cbl/COBIL00C.cbl` and its mapset
 * `app/bms/COBIL00.bms` (map `COBIL0A`, 24 `DFHMDF` fields -- the smallest of the 21 online screens),
 * reached at `/billpay`.
 *
 * Purpose
 * -------
 * Render the reference screen's two-turn workflow: look an account up and report the balance it owes,
 * then pay that balance in full behind an explicit confirmation. It replaces CICS transaction CB00,
 * which `app/csd/CARDDEMO.CSD` L337-L338 binds to that program.
 *
 * Assumptions: this module publishes the mapset's declared FIELD WIDTHS and nothing else that a screen
 * test would otherwise transcribe; the painted literals, the domain hint and the two screen-owned
 * legend captions are published by `ui/src/messages/messages.ts` under the `BILL_PAY_*` names and are
 * imported below. A width is a per-mapset numeric contract rather than user-visible text, which is the
 * line AAP section 0.2.1.5 draws between what the catalog owns and what a screen keeps.
 *
 * What this screen owns, and what it does not
 * -------------------------------------------
 * Assumptions: rows 4 to 22 of the map are this screen's, and rows 1 to 3, 23 and 24 are the frame's.
 * `ui/src/layout/AppShell.tsx` paints the title band, the row-23 message line and the row-24 legend
 * from a delegated slot, and this module renders none of the three: it publishes all three instead --
 * see the `useShellSlot` call in {@link BillPayScreen}, which carries the reason for the choice.
 *
 * Assumptions: delegating is the MAJORITY contract in this tree and not a universal one. Counted over
 * the 21 screen directories, 18 delegate their three zones and three compose their own --
 * `ui/src/screens/menu`, `ui/src/screens/admin` and `ui/src/screens/authDetail` -- so a reader who
 * finds a locally composed band on one of those three has found the documented exception rather than a
 * defect. ⚠️ The claim that stood here, that no screen composes those bands itself, is WITHDRAWN as
 * false on that measurement.
 *
 * Assumptions: the transaction record this payment writes is composed ENTIRELY server-side, and none
 * of it appears here. `app/cbl/COBIL00C.cbl` L212-L235 generates the identifier by browsing the ledger
 * backwards for the highest key and adding one, then fills the row with the fixed literals `'02'`,
 * category `2`, `'POS TERM'`, `'BILL PAYMENT - ONLINE'`, merchant `999999999`, `'BILL PAYMENT'` and
 * two `'N/A'` values, and finally subtracts the amount from the balance at L234. Every one of those is
 * `services/transaction-service`'s, published as `BillPaymentMapper` constants; duplicating any of
 * them here would fork a contract this screen does not own, and the browser has no ledger to browse.
 *
 * Money
 * -----
 * Assumptions: the balance is a `string` from the wire to the pixel and is never converted. The
 * baseline itself puts it on the wire as characters -- `ACCT-CURR-BAL PIC S9(10)V99` at
 * `app/cpy/CVACT01Y.cpy` L7 reaches the map through `CURBALI PIC X(14)` at `app/cpy-bms/COBIL00.CPY`
 * L66 -- so treating it as text is transcription rather than caution. Rule T3 forbids the money path
 * from touching a float, and this file contains no `Number`, `parseFloat` or arithmetic of any kind.
 *
 * Rule 1
 * ------
 * Every exported symbol below carries a TSDoc block stating its purpose, each parameter with its type,
 * its return value and the failures it surfaces; every non-obvious decision carries an adjacent
 * comment labelled with one of the four rationale categories. `ui/eslint.config.js` and
 * `config/rule1/rule1_gate.py` decide the mechanical half of that at `--max-warnings=0`.
 */

import { Divider, Flex, Form, Input, Typography, theme } from 'antd';
import type { InputRef } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChangeEvent, CSSProperties, ReactElement } from 'react';
// Assumptions: routing comes from `react-router` and never from `react-router-dom`. The companion
// package has no release at major version 8 at all -- its newest is a thin shim depending on
// `react-router@7` -- so importing the more idiomatic-looking specifier would silently pin routing a
// major version behind the one `ui/package.json` declares. `ui/eslint.config.js` lists it under
// `no-restricted-imports`, so the choice fails the build rather than resting on convention.
import { useLocation, useNavigate } from 'react-router';

import { CONFIRMATION_ANSWERS, isApiRequestError, isConfirmingAnswer } from '../../api/client';
import { inquireAccountPayableBalance, payAccountBalanceConfirmed } from '../../api/transactions';
import type { BillPaymentPreview } from '../../api/transactions';
import type { ApiError, FieldValidationState } from '../../api/types';
import { MONEY_PICTURES, renderMoney } from '../../format/money';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import {
  busyAnnouncement,
  busyProps,
  fieldAriaProps,
  fieldErrorHelp,
  fieldHintId,
} from '../../layout/fieldHelp';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import { copybookFieldWidthStyle } from '../../layout/recordLayout';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap, PfKeyRejection, PfKeyRisk } from '../../layout/usePfKeys';
import {
  BILL_PAY_CONFIRM_DOMAIN_HINT,
  BILL_PAY_FIELD_LABELS,
  BILL_PAY_KEY_LABELS,
  BILL_PAY_TITLE,
  INVALID_KEY_PRESSED,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  formatMessageTemplate,
} from '../../messages/messages';
import {
  MAIN_MENU_ROUTE,
  inApplicationRoute,
  navigateSafely,
  screenTransitionState,
} from '../../routes/navigation';
import {
  BMS_TEXT_COLOR_TOKENS,
  HINT_TEXT_TOKENS,
  SPACING_TOKENS,
  TYPOGRAPHY_TOKENS,
} from '../../theme/tokens';

/*
 * WHY : Assumptions: every user-visible sentence this screen can paint is read from
 *       `ui/src/messages/messages.ts`, which is the single owner of such text in this tree, and none is
 *       written as a literal anywhere below. Ten of the fourteen belong to this program and live under
 *       its own key; the other four are composed by more than one program and live in the shared group,
 *       which is why they are reached through two names rather than one.
 */
const BILL_PAY_MESSAGES = PROGRAM_MESSAGES.COBIL00C;

/** CICS transaction identifier this screen replaces, as `app/csd/CARDDEMO.CSD` L337-L338 defines it. */
export const BILL_PAY_TRANSACTION_ID = 'CB00';

/** Source program name, painted in the frame's `Prog:` slot exactly as the 3270 screen painted it. */
export const BILL_PAY_PROGRAM_NAME = 'COBIL00C';

/**
 * Source mapset name, which the frame resolves this screen's message-line width from.
 *
 * Assumptions: naming the mapset is what fixes the message line's rendered width, and this mapset's is
 * the wider of the two the frame supports. `ERRMSG` is `LENGTH=78` at `app/bms/COBIL00.bms` L127-L130
 * and `ERRMSGI PIC X(78)` at `app/cpy-bms/COBIL00.CPY` L78, where the shared work-area contract carries
 * `CCARD-ERROR-MSG PIC X(75)` at `app/cpy/CVCRD01Y.cpy` L28. `ui/src/messages/messages.ts` records the
 * per-mapset width, so publishing the mapset name resolves the difference there instead of restating a
 * number here. The longest sentence this screen emits is 61 characters, so neither width truncates it.
 */
export const BILL_PAY_MAPSET = 'COBIL00';

/*
 * WHY : ⚠️ Refactoring Rationale: four groups of painted text were declared in this module -- the row-4
 *       title, the three field literals, the `(Y/N)` domain hint and the two screen-owned legend
 *       captions -- each with its own transcription note citing the mapset line and declared width.
 *       All four now live in `ui/src/messages/messages.ts` as `BILL_PAY_TITLE`,
 *       `BILL_PAY_FIELD_LABELS`, `BILL_PAY_CONFIRM_DOMAIN_HINT` and `BILL_PAY_KEY_LABELS`, with the
 *       per-entry mapset lines in `BILL_PAY_PAINTED_TEXT_SOURCES` keyed to
 *       `BILL_PAY_MAPSET_SOURCE_FILE`, and are imported above. AAP section 0.2.1.5 assigns the strings
 *       a screen renders to that catalog, and the move is what makes their byte-exactness reviewable:
 *       two of these values END IN A SPACE that fills a declared BMS field width, and a trailing space
 *       is a value a reviewer cannot check while it is spread across a component.
 * WHY : Assumptions: nothing about the values changed in the move, and the provenance did not move
 *       twice. Each citation is carried once, in the catalog entry that now owns the literal, so a
 *       reader after the mapset line, the declared width or the reason a full stop stands where a
 *       question mark reads more naturally finds it there rather than in a copy here that could drift
 *       from it.
 */

/**
 * Declared widths of the three data fields this screen renders, from the mapset and its symbolic map.
 *
 * Assumptions: these are per-mapset contracts and they must never be unified with the same amounts'
 * widths on other screens. The balance is FOURTEEN here because `app/cbl/COBIL00C.cbl` L56 declares
 * `WS-CURR-BAL PIC +9999999999.99` -- one sign position, ten integer digits, the point and two decimal
 * positions -- and L193-L194 move the record field through it into `CURBALI PIC X(14)`. The account
 * screens declare fifteen for the same amounts because their picture is `+ZZZ,ZZZ,ZZZ.99`, which
 * `ui/src/format/money.ts` implements and documents as `MONEY_MASK_WIDTH`, and the transaction screens
 * declare twelve. Three pictures, three widths, one underlying record field.
 *
 * Refactoring Rationale: `confirmation` is published here again, and the reason is a measured defect
 * rather than tidiness. The single-position field it names was replaced by a confirmation dialogue
 * whose primary control was focused programmatically on open; measured in a browser, an operator who
 * typed only the account digits and then pressed Enter three times paid the whole balance without
 * typing a character, because each of those two consecutive turns had "commit" as its default action.
 * `app/bms/COBIL00.bms` L115-L119 declares `CONFIRM LENGTH=1 ATTRB=(FSET,NORM,UNPROT)` with NO `IC`,
 * and `app/cbl/COBIL00C.cbl` L173-L191 reads what was typed into it -- so the gate the reference
 * relies on is a keystroke the operator supplies, not a control an Enter can fall onto.
 */
export const BILL_PAY_FIELD_WIDTHS = {
  /** `ACTIDIN LENGTH=11` at `app/bms/COBIL00.bms` L85-L89; `ACTIDINI PIC X(11)`. */
  accountId: 11,
  /** `CURBAL LENGTH=14` at `app/bms/COBIL00.bms` L103-L106; `CURBALI PIC X(14)`. */
  currentBalance: 14,
  /** `CONFIRM LENGTH=1` at `app/bms/COBIL00.bms` L115-L119; `CONFIRMI PIC X(1)`. */
  confirmation: 1,
} as const;

/**
 * The measured edit mask this screen's balance is painted through.
 *
 * Refactoring Rationale: this screen used to carry its own four-operation mask, on the ground that
 * `ui/src/format/money.ts` implemented only the account screens' `+ZZZ,ZZZ,ZZZ.99`. That module now
 * publishes all three measured pictures and renders any of them through one entry point, so the local
 * copy is withdrawn: the module records this picture's own provenance -- `app/cbl/COBIL00C.cbl` L56 --
 * derives the fourteen-character width from the picture's own positions rather than from a number
 * written beside it, and returns the sign semantics and the `white-space` mode with the text.
 *
 * Trade-offs: adopting the shared renderer also adopts its SIGN colouring, which supersedes this
 * mapset's single `COLOR=BLUE` on `CURBAL` at `app/bms/COBIL00.bms` L103-L106. That is the shared
 * module's own recorded decision and it is taken deliberately here rather than opted out of: the
 * baseline paints a credit, a debit and a settled balance in one hue, and the AAP requires the three
 * to be distinguishable. Colour is redundant either way, because every measured picture is signed and
 * the leading `+` or `-` is in the rendered text.
 */
const BALANCE_PICTURE = MONEY_PICTURES.billPayBalance;

/**
 * This screen's row-24 legend, assembled from the two sources that own its three captions.
 *
 * Assumptions: the assembly exists because the three captions have TWO owners, not because any of them
 * is transcribed here. `BILL_PAY_KEY_LABELS` in the message catalog carries the two that are this
 * mapset's own -- ENTER reads `Continue` where other mapsets paint `Process`, and PF3 reads `Back`
 * where nine others paint `Exit` -- and it deliberately omits `F4=Clear`, which is byte-identical
 * everywhere it appears and is published by `ui/src/layout/PfKeyBar.tsx`. The catalog cannot reach for
 * it, because that module imports nothing at load time, so the join happens at the one call site that
 * needs all three.
 *
 * Assumptions: THREE captions and no more, because two independent sources in the reference agree on
 * three. The row-24 field is one `LENGTH=33` literal, `'ENTER=Continue  F3=Back  F4=Clear'`, and
 * `app/cbl/COBIL00C.cbl` L125-L142 dispatches exactly those three attention identifiers before
 * answering everything else with the shared invalid-key sentence. The absence of PF5, PF7, PF8 and
 * PF12 is therefore the baseline's own.
 */
const BILL_PAY_LEGEND_LABELS = {
  ENTER: BILL_PAY_KEY_LABELS.ENTER,
  PFK03: BILL_PAY_KEY_LABELS.PFK03,
  PFK04: UNIFORM_PF_KEY_LABELS.PFK04,
} as const;

/**
 * Reports whether a typed answer DECLINES, on the domain `app/cbl/COBIL00C.cbl` L178-L179 evaluates.
 *
 * Alternatives Considered: importing this alongside {@link isConfirmingAnswer} from
 * `ui/src/api/client.ts`, which is where the answer letters themselves come from. That module
 * publishes only the CONFIRMING test, and deliberately -- its own note records that the confirming
 * direction is the one whose misclassification moves money, so that is the test every caller shares.
 * The declining test is needed only by a screen that owns the field and has to tell `'N'` apart from a
 * character the reference refuses, which is this screen alone; the letter is still read from the shared
 * constant so the two spellings cannot drift.
 *
 * Assumptions: both cases are accepted, exactly as the reference accepts both -- `WHEN 'N'` and
 * `WHEN 'n'` are two arms of one branch at L178-L179. Accepting only the upper case would send a
 * lower-case `'n'` down the refusal path and paint "Invalid value. Valid values are (Y/N)..." for an
 * answer the terminal honoured.
 * @param {string} answer - The single character the operator typed into the confirmation field.
 * @returns {boolean} `true` only for a declining answer; `false` for a confirming one, for the empty
 *   never-answered state, and for every character the reference refuses.
 */
function isDecliningAnswer(answer: string): boolean {
  return answer === CONFIRMATION_ANSWERS.DECLINE || answer === 'n';
}

/*
 * WHY : Refactoring Rationale: the confirmation is a FOUR-state machine and not a boolean, because the
 *       reference's is. `app/cbl/COBIL00C.cbl` L173-L191 is one `EVALUATE CONFIRMI` with four arms --
 *       pay, decline, preview, refuse -- and the dialogue that replaced the field could express only
 *       two of them, which is how the refusal arm at L185-L190 became unreachable and how the
 *       never-answered arm at L182-L184 got merged into "the operator pressed Enter, so pay".
 *       Enumerating the four as a type is what makes the missing arms a compile-time concern.
 */
type BillPayConfirmationIntent = 'PAY' | 'DECLINE' | 'PREVIEW' | 'REFUSE';

/**
 * Classifies a typed confirmation answer into the four states the reference evaluates it as.
 *
 * Assumptions: the arms are tested in the reference's OWN order -- confirming, declining, blank, then
 * everything else -- so a value that could satisfy two tests resolves the way the terminal resolved
 * it. No value can, at one character, but the order is preserved rather than reasoned about because
 * the cost of preserving it is nil and the cost of getting it wrong is a payment.
 *
 * Assumptions: the blank state is the EMPTY string and not a space. The reference tests
 * `WHEN SPACES` and `WHEN LOW-VALUES` because a 3270 field is space-filled to its declared width and
 * an unsent field arrives as low-values; a browser control that was never typed into holds the empty
 * string, and `maxLength` of one means a space typed into it is one character rather than padding.
 * A typed space is therefore correctly a refusal -- it is a character the operator supplied that is
 * neither answer -- which is the same outcome the terminal reached by a different route.
 * @param {string} answer - The confirmation field's current value, at most one character wide.
 * @returns {BillPayConfirmationIntent} `PAY` for a confirming answer, `DECLINE` for a declining one,
 *   `PREVIEW` for the never-answered state, and `REFUSE` for any other character.
 */
export function classifyConfirmationAnswer(answer: string): BillPayConfirmationIntent {
  if (isConfirmingAnswer(answer)) {
    return 'PAY';
  }
  if (isDecliningAnswer(answer)) {
    return 'DECLINE';
  }
  if (answer === '') {
    return 'PREVIEW';
  }
  return 'REFUSE';
}

/**
 * Control identifier for the account field, used to bind its error text to it for assistive software.
 *
 * Assumptions: a constant rather than a `useId` value, because `ui/src/layout/fieldHelp.tsx` derives
 * the error element's own identifier from it and this screen renders exactly one such field, so a
 * generated value would buy uniqueness the screen cannot consume.
 */
const ACCOUNT_ID_CONTROL_ID = 'billpay-account-id';

/**
 * Contract field name the service attributes an account-entry refusal to.
 *
 * Assumptions: `'accountId'`, which is the name `services/transaction-service` publishes as
 * `BillPaymentMapper.ACCOUNT_ID_FIELD`. Matching it is what lets a refusal the service attributed and one
 * this screen raised locally mark the same control through one code path.
 */
const ACCOUNT_ID_FIELD = 'accountId';

/**
 * Control identifier for the confirmation field, used to bind its refusal text and hint to it.
 *
 * Assumptions: a constant for the same reason the account entry's is -- `ui/src/layout/fieldHelp.tsx`
 * derives both the refusal element's identifier and the hint element's from it, and this screen renders
 * exactly one such field.
 */
const CONFIRMATION_CONTROL_ID = 'billpay-confirmation';

/**
 * Stable handle on the confirmation control, kept at the name the withdrawn trigger carried.
 *
 * Refactoring Rationale: the value is UNCHANGED from the one the confirmation trigger published, and
 * keeping it is deliberate rather than incidental. `ui/src/screens/mutationTurnLock.test.tsx` -- a
 * cross-screen suite that drives this screen's in-flight lock -- locates the confirmation control by
 * this handle and asserts its enabled state on both sides of a deferred turn. The control changed from
 * a button to the field the reference declares, but "the control that answers the confirmation" is the
 * same role, so re-using the handle keeps that suite pointed at the right element instead of at
 * nothing.
 */
const CONFIRMATION_CONTROL_TEST_ID = 'billpay-confirm';

/**
 * Contract field name the service attributes a confirmation refusal to.
 *
 * Assumptions: `'confirmation'`, which is the member name `BillPaymentRequest` publishes and the name
 * the service's own `^[YyNn]?$` pattern is declared on. Matching it is what lets a refusal the service
 * attributed and one this screen raised locally mark the same control through one code path.
 */
const CONFIRMATION_FIELD = 'confirmation';

/**
 * Shape an account entry must have before this screen will look it up.
 *
 * Assumptions: exactly eleven decimal digits, because that is what `ACCT-ID PIC 9(11)` at
 * `app/cpy/CVACT01Y.cpy` L5 can receive. A `PIC 9(11)` field cannot hold a shorter value -- a
 * three-digit entry does not fill it -- so a wrong width is refused by the same edit that refuses a
 * non-digit, which is how `app/cbl/COACTVWC.cbl` L666-L680 expresses the same rule with one
 * `NOT NUMERIC` test.
 */
const ACCOUNT_ID_PATTERN = /^[0-9]{11}$/u;

/**
 * The refusal sentence for an account entry that is present but not a non-zero eleven-digit number.
 *
 * Refactoring Rationale: this screen previously had no such edit at all, and the consequence was
 * measured twice. A paste of `{{7*7}} and ${7*7}` had its digits EXTRACTED to `7777`, which reached the
 * wire, returned a real balance for an account the operator never named, and armed the payment; and an
 * entry of `0` was accepted by this money-moving screen while the read-only account-view screen refuses
 * it locally with zero requests. A mutating screen laxer than its read-only sibling is the wrong way
 * round, so the sibling's edit is adopted here.
 *
 * Assumptions: the sentence is `COACTVWC`'s and is READ from the catalog, not retyped. It carries a
 * DOUBLE SPACE after "must" -- `app/cbl/COACTVWC.cbl` L672 -- and any whitespace-collapsing edit
 * destroys it silently because the result still reads as correct English.
 *
 * Alternatives Considered: composing a new sentence in this program's own voice, since the refusal is
 * new to this screen. Rejected under transformation rule T8: `app/cbl/COBIL00C.cbl` declares no such
 * literal, so a sentence written here would be text no line of the baseline holds. Borrowing the one
 * the baseline already emits for precisely this condition on precisely this field is transcription;
 * inventing one is not.
 */
const ACCOUNT_FILTER_REFUSAL =
  PROGRAM_MESSAGES.COACTVWC.ACCOUNT_FILTER_MUST_BE_A_NON_ZERO_11_DIGIT_NUMBER;

/**
 * HTTP status the services answer for a record that does not exist.
 *
 * Assumptions: a named constant rather than the literal at the comparison, because
 * `ui/eslint.config.js` treats a bare numeric comparison as a magic number and because the name is what
 * ties the branch to the contract that documents the status.
 */
const NOT_FOUND_STATUS = 404;

/*
 * WHY : Assumptions: the destinations are enumerated as a closed pair because the reference has exactly
 *       two. `app/cbl/COBIL00C.cbl` writes `MOVE -1 TO ACTIDINL` on every refusal and on
 *       `INITIALIZE-ALL-FIELDS` at L561, and `MOVE -1 TO CONFIRML` at L239 beside the confirmation
 *       prompt; no other field on the mapset is ever named as a cursor destination. Naming the pair as a
 *       type rather than passing element references around keeps the destination a decision the turn
 *       records and the effect below carries out, which is what lets the move outlive its own commit.
 */
type BillPayCursorTarget = 'accountId' | 'confirm';

/** One field-level refusal, reduced to what this screen renders and where it puts the cursor. */
export interface BillPayFieldError {
  /** Contract field name the service attributed the refusal to, such as `accountId`. */
  readonly field: string;
  /** Whether the value was absent or merely wrong, which the reference distinguishes. */
  readonly state: FieldValidationState;
  /** The refusal sentence, rendered unchanged because the service composes it from the baseline. */
  readonly message: string;
}

/** What this screen paints after a turn, and whether the payment control is offered. */
export interface BillPayTurn {
  /** Balance to paint in `CURBAL`, or `null` to leave it blank as `INITIALIZE-ALL-FIELDS` does. */
  readonly balance: string | null;
  /** Whether a confirmed payment may be offered, which only a positive balance permits. */
  readonly payable: boolean;
  /** Sentence for the row-23 line, or `null` for the one turn the reference answers silently. */
  readonly message: string | null;
  /** Severity the reference painted that sentence in. */
  readonly severity: MessageBandSeverity;
  /** Whether the account entry should be cleared, as the reference clears it on two paths. */
  readonly clearEntry: boolean;
}

/**
 * Applies the account-entry edits this screen refuses a lookup on, before any request is composed.
 *
 * Purpose: answer a malformed, mistyped or zero account entry LOCALLY, so a value the reference field
 * could not have held never reaches the wire and never returns a balance for an account the operator
 * did not name.
 *
 * Assumptions: the blank arm is this program's own and the malformed arm is borrowed, and the split is
 * the reference's. `app/cbl/COBIL00C.cbl` L159-L164 tests the entry against spaces and low-values
 * FIRST -- ahead of the confirmation evaluate at L173 -- and answers with its own
 * `'Acct ID can NOT be empty...'`, so the blank case is transcribed. The reference has no second edit
 * on this field, which is the gap this closes with the sentence `app/cbl/COACTVWC.cbl` L672 emits for
 * the identical condition on the identical field.
 *
 * Assumptions: the all-zeroes case is tested by comparing against a run of zeroes at the DECLARED
 * WIDTH rather than by converting the entry to a number. `app/cpy/CVCRD01Y.cpy` L34-L36 declares
 * `CC-ACCT-ID PIC X(11)` with a numeric `REDEFINES`, so the identifier is characters on the wire and a
 * number only inside arithmetic; converting it here would discard the leading zeroes that belong to the
 * declared width and would put an eleven-digit value through an IEEE-754 double on the way.
 *
 * Trade-offs: the entry is TRIMMED before it is judged, and only for the purpose of judging it. A
 * pasted value with a trailing blank would otherwise be refused with a sentence about digits, which
 * reports the wrong reason -- the 3270 field was space-filled to its declared width, so a trailing
 * blank was not a character the operator supplied.
 * @param {string} raw - The account entry exactly as the control currently holds it.
 * @returns {BillPayFieldError | null} The refusal to render and mark, or `null` when the entry may be
 *   looked up.
 */
export function refuseAccountIdEntry(raw: string): BillPayFieldError | null {
  const entry = raw.trim();

  if (entry === '') {
    /*
     * WHY : Assumptions: the entry is marked BLANK rather than merely wrong, because the contract
     *       distinguishes the two states and the reference's own condition here is emptiness. The
     *       sentence is the catalog's, so the field marker and the message line carry one wording
     *       between them.
     */
    return {
      field: ACCOUNT_ID_FIELD,
      state: 'BLANK',
      message: BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY,
    };
  }

  if (!ACCOUNT_ID_PATTERN.test(entry) || entry === '0'.repeat(BILL_PAY_FIELD_WIDTHS.accountId)) {
    return { field: ACCOUNT_ID_FIELD, state: 'NOT_OK', message: ACCOUNT_FILTER_REFUSAL };
  }

  return null;
}

/**
 * Composes the payment success sentence from the catalog template.
 *
 * Assumptions: the sentence contains a DOUBLE SPACE between its two halves and both spaces are
 * deliberate. `app/cbl/COBIL00C.cbl` L527-L531 concatenates `'Payment successful. '`, which ends with a
 * space, and `' Your Transaction ID is '`, which begins with one, so the rendered text carries both.
 * `MESSAGE_TEMPLATES.PAYMENT_SUCCESSFUL` holds the two literals separately for exactly that reason, and
 * composing through the template is what keeps the pair intact -- writing the sentence by hand is how
 * one of the two spaces gets normalised away by a reformat or by a reader who takes it for a typo.
 *
 * Assumptions: the identifier is rendered with all sixteen of its characters. `TRAN-ID` is `PIC X(16)`
 * at `app/cpy/CVTRA05Y.cpy` L5 fed from a `PIC 9(16)` counter, so it arrives zero-padded and the
 * template's `DELIMITED BY SPACE` clause truncates at the first blank -- of which a fully populated
 * sixteen-digit value has none. The result is 61 characters, well inside this mapset's 78.
 * @param {string} transactionId - Identifier the service reported for the written payment row.
 * @returns {string} The composed sentence, both spaces preserved, ending in a full stop.
 * @throws {Error} If the template no longer declares the substitution this passes, which
 *   `formatMessageTemplate` reports rather than silently emitting a gap. It is unreachable while the
 *   catalog entry keeps its `TRAN-ID` part, and it is documented because the call can raise.
 */
export function paymentSuccessMessage(transactionId: string): string {
  return formatMessageTemplate(MESSAGE_TEMPLATES.PAYMENT_SUCCESSFUL, {
    'TRAN-ID': transactionId,
  });
}

/**
 * Reduces a problem document's field refusals to the shape this screen renders.
 *
 * Assumptions: each refusal's sentence is carried through unchanged. The services compose these from
 * the baseline's own wording, so rewording one here would replace a transcribed refusal with an
 * invented one -- which rule T8 forbids and which the screen tests would not catch, because a reworded
 * sentence still renders.
 * @param {ApiError} problem - The normalised problem document the client raised.
 * @returns {readonly BillPayFieldError[]} One entry per attributed field, in the order the service
 *   sent them, so the first is the one the cursor moves to.
 */
export function resolveApiFieldErrors(problem: ApiError): readonly BillPayFieldError[] {
  return problem.fieldErrors.map(
    /**
     * Copies one refusal into this screen's shape.
     * @param {ApiError['fieldErrors'][number]} refusal - One attributed field error.
     * @returns {BillPayFieldError} The same refusal, reduced to the members this screen reads.
     */
    (refusal) => ({
      field: refusal.field,
      state: refusal.state,
      message: refusal.message,
    }),
  );
}

/**
 * Decides what the screen paints for an unconfirmed lookup, and whether payment may be offered.
 *
 * Assumptions: the three outcomes below are the three the service publishes, and this function ADOPTS
 * its decision rather than re-deriving one. `services/transaction-service`'s `BillPaymentService`
 * answers a declined confirmation with `BillPaymentPreview.cleared` and no account read at all, a
 * non-positive balance with `reporting(..., MESSAGE_NOTHING_TO_PAY)` and a positive one with
 * `reporting(..., MESSAGE_CONFIRM_PAYMENT)`, transcribing `app/cbl/COBIL00C.cbl` L178-L181, L198-L204
 * and L236-L240 respectively.
 *
 * Alternatives Considered: testing the sign of `payableBalance` here -- reading a leading `'-'` or an
 * all-zero digit run -- so the screen decided payability for itself. Rejected because the reference's
 * test is `IF ACCT-CURR-BAL <= ZEROS` at L198 and the service already implements exactly that,
 * inclusively, against the balance it read under its own lock. A second implementation of one rule can
 * only ever agree or disagree with the first, and when it disagreed the screen would offer a payment
 * the service then refused, or withhold one it would have accepted. Reading the answer is also what
 * keeps this file free of any inspection of a money value's magnitude.
 *
 * Assumptions: the comparison is against the catalog constants by identity, and the catalog is the same
 * source the service's own constants transcribe, so the two cannot drift apart without the catalog
 * changing. The sentence is still rendered from `returnMessage` rather than from the matched constant,
 * so what the operator reads is what the service actually sent.
 *
 * Trade-offs: an unrecognised sentence FAILS CLOSED -- the balance and the sentence are painted and the
 * payment control is withheld. Offering payment on an outcome this screen cannot classify would let an
 * unrecognised service state authorise a movement of money, and withholding it costs at worst one extra
 * keypress on a state that should not arise.
 * @param {BillPaymentPreview} preview - The unconfirmed answer the service returned.
 * @returns {BillPayTurn} The balance to paint, the sentence and its severity, whether payment may be
 *   offered, and whether the account entry is cleared.
 */
export function previewTurn(preview: BillPaymentPreview): BillPayTurn {
  /*
   * WHY : Assumptions: a null balance identifies the DECLINED turn and nothing else, because the
   *       contract declares the member required-and-nullable and the service writes null on that turn
   *       alone. `app/cbl/COBIL00C.cbl` L178-L181 answers a declining answer by clearing the whole
   *       screen and setting the error flag, which suppresses every later sentence -- so this branch
   *       paints no message at all. A "payment cancelled" sentence would be text no line of the
   *       reference emits, which rule T8 forbids.
   */
  if (preview.payableBalance === null) {
    return {
      balance: null,
      payable: false,
      message: null,
      severity: 'error',
      clearEntry: true,
    };
  }

  const balance = preview.payableBalance;
  const reported = preview.returnMessage;

  /*
   * WHY : Assumptions: the nothing-to-pay outcome is a DISTINCT observable result and not a variety of
   *       failure. The reference reaches it by the same mechanism it reaches the confirmation prompt --
   *       move a sentence, send the map -- so it is an ordinary turn that paints the balance it just
   *       read, leaves the account entry in place and offers no payment. The reference also puts the
   *       cursor back on the account field here rather than on the confirmation field, which is why the
   *       caller focuses differently on this branch than on the next one.
   * WHY : Assumptions: the test behind this sentence is inclusive of zero AND of a credit balance. L198
   *       is `<= ZEROS`, and a credit balance is negative in this record's sign convention, so both
   *       reach the same advisory.
   */
  if (reported === BILL_PAY_MESSAGES.YOU_HAVE_NOTHING_TO_PAY) {
    return {
      balance,
      payable: false,
      message: reported,
      severity: 'error',
      clearEntry: false,
    };
  }

  /*
   * WHY : Assumptions: this sentence renders at ERROR severity even though it reports success at
   *       finding a payable balance, and the mapset is what settles it. `ERRMSG` is declared
   *       `COLOR=RED` at `app/bms/COBIL00.bms` L127-L130, and `app/cbl/COBIL00C.cbl` overrides that
   *       colour on exactly one path -- `MOVE DFHGREEN TO ERRMSGC` at L526, the payment success path.
   *       The confirmation prompt at L237 sets no error flag yet is painted by the same field in the
   *       same red, so mapping it to anything gentler would repaint a line the reference paints red.
   */
  if (reported === BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT) {
    return {
      balance,
      payable: true,
      message: reported,
      severity: 'error',
      clearEntry: false,
    };
  }

  return {
    balance,
    payable: false,
    message: reported,
    severity: 'error',
    clearEntry: false,
  };
}

/** A failed turn reduced to the sentence, the field refusals and where the cursor belongs. */
export interface BillPayFailure {
  /** Sentence for the row-23 line, always one the baseline emits. */
  readonly message: string;
  /** Field refusals to mark, empty when the failure was not attributed to a field. */
  readonly fieldErrors: readonly BillPayFieldError[];
}

/**
 * Maps a rejected request to the sentence, the refusals and the cursor position the reference gives it.
 *
 * Assumptions: the service's own sentence is PREFERRED over any fallback, because it is the one the
 * baseline emits for the condition that actually occurred. `BillPaymentService` raises the reference's
 * wording for every path this screen can provoke -- `'Account ID NOT found...'` for a missing account,
 * `'Unable to lookup Account...'` for a failed read, `'Unable to lookup XREF AIX file...'`,
 * `'Transaction ID NOT found...'`, `'Unable to lookup Transaction...'`, `'Tran ID already exist...'`,
 * `'Unable to Add Bill pay Transaction...'` and `'Unable to Update Account...'` -- and the client
 * normalises each into `problem.message`. Choosing a local sentence first would discard the service's
 * discrimination and paint one message for eight distinct conditions.
 *
 * Assumptions: `'Account ID NOT found...'` is the fallback for a 404 and the screen does NOT try to say
 * which lookup failed. The reference emits that one sentence from three different sites --
 * `app/cbl/COBIL00C.cbl` L361 on the account read, L392 on the account rewrite and L425 on the
 * cross-reference read -- so one string covering three causes is the transcribed behaviour, not a loss
 * of detail.
 *
 * Assumptions: a failure that is not a transport failure at all still paints a baseline sentence rather
 * than a runtime message. An unexpected throw from the client layer means the lookup did not complete,
 * which is the condition `'Unable to lookup Account...'` names, and surfacing a JavaScript error text to
 * an operator would put words on the screen no line of the reference holds.
 * @param {unknown} failure - Whatever the request rejected with.
 * @param {boolean} paying - Whether the failed turn was the confirmed payment rather than the lookup,
 *   which selects the fallback sentence appropriate to the step that failed.
 * @returns {BillPayFailure} The sentence to paint and the field refusals to mark.
 */
export function billPayFailure(failure: unknown, paying: boolean): BillPayFailure {
  /*
   * WHY : Assumptions: the two fallbacks differ because the reference has different wording for the two
   *       steps. A lookup that cannot complete is L368's `'Unable to lookup Account...'`; a payment that
   *       cannot be written is L543's `'Unable to Add Bill pay Transaction...'`. Using one for both would
   *       tell an operator whose payment failed that the lookup did, which is a different fault.
   */
  const fallback = paying
    ? BILL_PAY_MESSAGES.UNABLE_TO_ADD_BILL_PAY_TRANSACTION
    : BILL_PAY_MESSAGES.UNABLE_TO_LOOKUP_ACCOUNT;

  if (!isApiRequestError(failure)) {
    return { message: fallback, fieldErrors: [] };
  }

  const fieldErrors = resolveApiFieldErrors(failure.problem);
  const reported = failure.problem.message;

  if (reported !== null && reported !== '') {
    return { message: reported, fieldErrors };
  }

  /*
   * WHY : Assumptions: a 404 that carried no sentence falls back to `'Account ID NOT found...'` rather
   *       than to either step's generic wording, because that is the sentence the reference emits for a
   *       record that is not there. It is the fallback for BOTH steps: the reference reaches the same
   *       string from three sites -- the account read at L361, the account rewrite at L392 and the
   *       cross-reference read at L425 -- so a missing record on the paying turn says exactly what a
   *       missing record on the reporting turn says, and this screen does not attempt to distinguish
   *       which of the three lookups failed.
   */
  if (failure.status === NOT_FOUND_STATUS) {
    return { message: SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND, fieldErrors };
  }

  /*
   * WHY : Assumptions: a document carrying refusals but no top-line sentence is answered with the first
   *       refusal's own sentence rather than with the fallback, because the refusal is the more specific
   *       transcription of what went wrong. The index is read through a guard because
   *       `noUncheckedIndexedAccess` types it as possibly absent, and it genuinely is on a document with
   *       an empty array.
   */
  const firstRefusal = fieldErrors[0];
  if (firstRefusal !== undefined) {
    return { message: firstRefusal.message, fieldErrors };
  }

  return { message: fallback, fieldErrors };
}

/*
 * WHY : ⚠️ Refactoring Rationale: the paste filter that stood here is WITHDRAWN. It read
 *       `raw.replace(NON_DIGIT_PATTERN, '').slice(0, 11)`, and its stated justification -- that
 *       filtering is "the browser equivalent of that field behaviour" -- is false in the one respect
 *       that matters. A 3270 numeric field DISCARDED a non-numeric keystroke; it did not compact the
 *       digits it found in a longer string into a different, shorter, well-formed identifier. Measured
 *       in a browser: a paste of `{{7*7}} and ${7*7}` became `7777`, `' OR 1=1 --` became `11` and
 *       `1' UNION SELECT NULL--` became `1`, each of which then reached the wire, returned a real
 *       balance for an account the operator never named, and armed the payment control -- with no
 *       message anywhere on the screen saying the value had not been honoured.
 * WHY : Alternatives Considered: keeping the filter and adding a message when it altered the value.
 *       Rejected because the operator would then be told about an alteration they cannot undo -- the
 *       original text is already gone from the control -- and because the alteration is the defect, not
 *       the silence. What replaces it is {@link refuseAccountIdEntry}, which leaves the operator's own
 *       characters in the field, refuses the turn locally with the sentence the baseline emits for that
 *       condition, and sends nothing.
 * WHY : Trade-offs: `maxLength` on the control is retained and is now the ONLY inbound constraint, so
 *       an ordinary keystroke past the eleventh is still dropped the way the terminal dropped it. It
 *       does not bound a paste, which is exactly why the refusal above tests the width as well as the
 *       digits rather than trusting the attribute.
 */

/**
 * Renders the bill payment screen: look an account up, then pay its balance in full on confirmation.
 *
 * Assumptions: the account entry starts EMPTY and no value is read from the route or the query string.
 * `app/cbl/COBIL00C.cbl` L116-L121 does pre-fill the field from `CDEMO-CB00-TRN-SELECTED` and run the
 * lookup immediately, and that path is dead code: a repository-wide search for `CDEMO-CB00` matches
 * exactly one file, `COBIL00C.cbl` itself, where the group is declared at L64-L72 and read at L116 and
 * L118 and populated by nothing anywhere in `app/`. Its only caller is the main menu, which sets none of
 * it.
 *
 * Alternatives Considered: accepting an `?acctId=` query parameter so the dead path had a browser
 * equivalent. Rejected on two independent grounds -- it would add an arrival behaviour the baseline
 * never exhibits, and the route this screen is mounted on carries no parameter of any kind, so the
 * parameter would be an invention of the migration rather than a transcription of it.
 * @returns {ReactElement} The bill payment screen, with its title band, message line and function-key
 *   legend delegated to the application frame.
 */
export function BillPayScreen(): ReactElement {
  const navigate = useNavigate();
  const location = useLocation();
  const { cssVar } = theme.useToken();

  /*
   * WHY : Assumptions: the paint instant is read from the shared hook and threaded into the delegated
   *       slot rather than being read inside the frame. `ui/src/layout/AppShell.tsx` records the reason
   *       on the slot member: a screen knows when it painted and the frame does not, so reading it here
   *       keeps one clock change from re-rendering a frame no mounted screen asked to update.
   */
  const paintedAt = useServerInstant();

  const [accountId, setAccountId] = useState('');
  const [balance, setBalance] = useState<string | null>(null);
  const [payable, setPayable] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');
  const [fieldErrors, setFieldErrors] = useState<readonly BillPayFieldError[]>([]);

  /*
   * WHY : Refactoring Rationale: the confirmation answer is STATE the operator types, where it used to
   *       be a value two dialogue controls supplied. That is the whole of the CRITICAL defect's fix:
   *       while the answer was carried by a control, "commit" was the default action of whichever
   *       control held focus, and focus was moved onto it -- so an Enter that the row-24 legend itself
   *       advertises as `ENTER=Continue` paid a balance. An answer held here can only become `'Y'`
   *       because a `'Y'` was typed, which is precisely the property `EVALUATE CONFIRMI` at
   *       `app/cbl/COBIL00C.cbl` L173-L191 relies on.
   */
  const [confirmation, setConfirmation] = useState('');

  /*
   * WHY : Assumptions: this one flag is the WHOLE-TURN keyboard lock and not a spinner condition. It is
   *       set before the request leaves and cleared only when the turn settles, and every control and
   *       every attention identifier this screen offers is held shut for that entire window: the account
   *       entry, the confirmation dialogue and its trigger, and the ENTER, PF3 and PF4 bindings. A 3270
   *       accepted no keystroke between sending the map and receiving the reply, so a screen that moves
   *       money reproduces that here rather than allowing an exit or a clear to race a commit.
   * WHY : Trade-offs: the lock is expressed as ONE piece of state rather than per-control flags,
   *       accepting that no control can be exempted without an edit here. That is the point -- a
   *       per-control flag is what let PF3 and PF4 stay live while a payment committed, and a single
   *       flag makes the next control added to this screen locked by default rather than by attention.
   */
  const [busy, setBusy] = useState(false);

  /*
   * WHY : Refactoring Rationale: WHICH control the outstanding turn belongs to is recorded, in addition
   *       to the fact that one is outstanding. The in-progress indicator used to sit on the payment
   *       control unconditionally, so pressing `ENTER=Continue` to READ a balance drew a spinner on the
   *       money button -- measured, and the operator is then shown activity on the one control they did
   *       not touch. The reading turn is answered from the account entry and the paying turn from the
   *       confirmation field, so naming the turn's own control is what puts the indication where the
   *       keystroke went.
   * WHY : Alternatives Considered: putting the indicator on the row-24 legend control the operator
   *       actually pressed, which is the most literal reading of "the pressed control". Not reachable
   *       from here: `ui/src/layout/PfKeyBar.tsx` renders those from a descriptor carrying `label`,
   *       `enabled` and an invocation, with no per-binding busy member, and that module is not this
   *       screen's. The field the turn reads its answer from is the nearest control this screen owns,
   *       and it is where the operator's attention already is because the cursor was placed in it.
   * WHY : Trade-offs: `null` while idle rather than a boolean pair, so the two indicators cannot both
   *       be drawn and neither can be drawn without a turn in flight.
   */
  const [busyControl, setBusyControl] = useState<BillPayCursorTarget | null>(null);

  /*
   * WHY : Refactoring Rationale: the cursor is moved explicitly, in addition to marking the field with
   *       `validateStatus`, and for this mapset the movement is the more faithful of the two signals.
   *       `app/cbl/COBIL00C.cbl` does NOT `COPY CSSETATY` -- the templated copybook that recolours a
   *       field and writes a `'*'` marker into it -- so it has no per-field colour signal at all. What it
   *       has is `MOVE -1 TO ACTIDINL` and `MOVE -1 TO CONFIRML`, which place the 3270 cursor, and it
   *       issues one on every refusal path. Marking the field without moving the cursor would drop the
   *       only per-field indication this screen's source actually produces.
   */
  const accountInputRef = useRef<InputRef>(null);
  /*
   * WHY : Refactoring Rationale: the confirmation cursor destination is the FIELD the reference
   *       declares, not a button. It held an `HTMLButtonElement` -- the dialogue's trigger -- which is
   *       how `MOVE -1 TO CONFIRML` at `app/cbl/COBIL00C.cbl` L239 came to place the cursor on a control
   *       whose activation moved money. `CONFIRM` at `app/bms/COBIL00.bms` L115-L119 is
   *       `ATTRB=(FSET,NORM,UNPROT) LENGTH=1`: an unprotected one-position input, so the cursor landing
   *       in it arms nothing at all until a character is typed.
   */
  const confirmInputRef = useRef<InputRef>(null);

  /**
   * Whether a turn is already in flight, held in a ref so two events in one batch cannot both pass.
   */
  const inFlight = useRef(false);

  /*
   * WHY : Refactoring Rationale: a requested cursor move is RECORDED as state and applied by the effect
   *       below, where it used to be performed inline at the point of decision. Measured in a browser
   *       rather than reasoned about: a `focus()` issued inline landed nowhere on every turn that had
   *       reached the network, leaving `document.activeElement` on `<body>`. The cause is a collision
   *       between two things this screen does deliberately. Every control the cursor can be sent to is
   *       `disabled` while a turn is in flight -- the account entry and the confirmation trigger among
   *       them, since the lock covers the whole turn -- and the handler that settles a turn clears that
   *       flag and names the cursor's destination in the same batch. React commits both together, so an
   *       inline call ran while the target was still disabled, and a disabled element refuses focus
   *       silently, with no error to notice. Worse, disabling the element the operator was typing into
   *       makes the browser blur it first, so the inline form did not merely fail to move the cursor:
   *       it lost it.
   * WHY : Alternatives Considered: three. (1) `readOnly` instead of `disabled` on the account entry,
   *       which keeps focus -- rejected because it also keeps the control looking and announcing as
   *       editable during a turn it will not accept, which misstates the lock rather than expressing it.
   *       (2) `setTimeout` or `requestAnimationFrame` around the inline call, which would also outlive
   *       the commit -- rejected because it makes the cursor's position depend on a timer instead of on
   *       the render that decided it, so a slow commit reintroduces the same bug non-deterministically
   *       and only under load. (3) Reading the flag and re-issuing focus from the settle handler --
   *       rejected because it duplicates the enabled-state rule at every call site instead of stating it
   *       once. This is also the pattern the sibling `ui/src/screens/transactionAdd/index.tsx` settled
   *       on for the identical collision, so the two screens now answer it the same way.
   * WHY : Trade-offs: one extra render per cursor move. That is the whole cost, and it buys the cursor
   *       actually arriving where `MOVE -1 TO ACTIDINL` and `MOVE -1 TO CONFIRML` put it -- which for
   *       this mapset is the ONLY per-field signal the reference produces, since it does not
   *       `COPY CSSETATY`.
   */
  const [pendingFocus, setPendingFocus] = useState<BillPayCursorTarget | null>(null);

  useEffect(
    /**
     * Applies a recorded cursor move once the render that requested it has reached the DOM.
     *
     * Assumptions: the request is cleared whether or not a control was there to receive it, so a
     * destination recorded for a control that has since been withdrawn -- the confirmation field on a
     * turn that stopped offering payment -- cannot be replayed against a later render.
     * @returns {void} Nothing; completion is the focused control and the discharged request.
     */
    function applyPendingFocus(): void {
      if (pendingFocus === null) {
        return;
      }

      if (pendingFocus === 'confirm') {
        confirmInputRef.current?.focus();
      } else {
        accountInputRef.current?.focus();
      }

      setPendingFocus(null);
    },
    [pendingFocus],
  );

  const focusAccountId = useCallback(
    /**
     * Records the cursor move onto the account entry, which is `MOVE -1 TO ACTIDINL`.
     * @returns {void} Nothing; the effect above performs the move after the commit.
     */
    function placeCursorOnAccountId(): void {
      setPendingFocus('accountId');
    },
    [],
  );

  const focusConfirm = useCallback(
    /**
     * Records the cursor move onto the confirmation FIELD, which is `MOVE -1 TO CONFIRML`.
     *
     * Assumptions: the destination is the one-position input and nothing else, and that is the whole
     * of the CRITICAL fix rather than a detail of it. `app/bms/COBIL00.bms` L115-L119 declares
     * `CONFIRM` unprotected with no `IC`, so the cursor arriving there arms nothing: the field is
     * empty, and an Enter pressed against an empty confirmation is answered by this screen with no
     * request and no dialogue at all. The destination it replaced was a button whose activation paid
     * the balance, which made Enter the commit gesture at two consecutive turns.
     * @returns {void} Nothing; the effect above performs the move after the commit, by which point the
     *   control the reference points at has been enabled by the same commit.
     */
    function placeCursorOnConfirm(): void {
      setPendingFocus('confirm');
    },
    [],
  );

  const clearScreen = useCallback(
    /**
     * Reproduces `INITIALIZE-ALL-FIELDS` at `app/cbl/COBIL00C.cbl` L560-L566.
     *
     * Assumptions: all four of the paragraph's moves are reproduced and the cursor move with them -- the
     * account entry, the displayed balance, the confirmation state and the message are cleared, and the
     * cursor returns to the account field. Clearing only the entry would leave a balance on screen that
     * no longer describes anything the operator typed.
     * @returns {void} Nothing; the cleared state is the screen's own.
     */
    function initializeAllFields(): void {
      setAccountId('');
      setBalance(null);
      setPayable(false);
      /*
       * WHY : Assumptions: the typed answer is cleared here as well, which is the paragraph's own
       *       `MOVE SPACES ... CONFIRMI OF COBIL0AI` at `app/cbl/COBIL00C.cbl` L563-L565 -- one group
       *       move that blanks the entry, the balance, the answer and the message together. Leaving a
       *       `'Y'` in the field across a clear would leave the next turn already answered, and the
       *       next turn is the one that pays.
       */
      setConfirmation('');
      setMessage(null);
      setFieldErrors([]);
      setSeverity('error');
      focusAccountId();
    },
    [focusAccountId],
  );

  /**
   * Applies one settled turn to the screen and puts the cursor where that turn puts it.
   * @param {BillPayTurn} turn - The outcome to paint.
   * @returns {void} Nothing; the painted state is the screen's own.
   */
  function applyTurn(turn: BillPayTurn): void {
    setBalance(turn.balance);
    setPayable(turn.payable);
    setMessage(turn.message);
    setSeverity(turn.severity);
    setFieldErrors([]);

    /*
     * WHY : Assumptions: a settled reading turn leaves the confirmation field EMPTY, whatever it held
     *       when the turn began. The reference re-sends the whole map with `CONFIRMI` space-filled from
     *       `INITIALIZE-ALL-FIELDS` or repainted by the prompt path, so the answer never survives the
     *       turn that reads the balance -- and a surviving answer is the state in which one further
     *       Enter would pay against a figure that had just been replaced.
     */
    setConfirmation('');

    if (turn.clearEntry) {
      setAccountId('');
    }

    /*
     * WHY : Assumptions: the cursor lands on the confirmation FIELD only when payment is being
     *       offered, and on the account entry otherwise. That is the reference's own split: L239 moves
     *       the cursor to `CONFIRML` alongside the confirmation prompt, while the nothing-to-pay path at
     *       L203 and every refusal path move it to `ACTIDINL`. It is safe to move it there because the
     *       field it moves to is empty and an empty answer commits nothing.
     */
    if (turn.payable) {
      focusConfirm();
      return;
    }
    focusAccountId();
  }

  /**
   * Reports a failed turn in the reference's own words and marks the fields it attributed.
   * @param {unknown} failure - Whatever the request rejected with.
   * @param {boolean} paying - Whether the confirmed payment failed rather than the lookup.
   * @returns {void} Nothing; the reported state is the screen's own.
   */
  function reportFailure(failure: unknown, paying: boolean): void {
    const reported = billPayFailure(failure, paying);
    setMessage(reported.message);
    setSeverity('error');
    setFieldErrors(reported.fieldErrors);

    /*
     * WHY : Assumptions: a failed turn withdraws the payment control. Every refusal path in the
     *       reference sets the error flag, and L208's `IF NOT ERR-FLG-ON` gate means the confirmation
     *       prompt is never reached on such a turn -- so the operator is not left able to confirm against
     *       a balance the last turn failed to establish.
     * WHY : Assumptions: the typed answer goes with it, for the same reason. A withdrawn payment with a
     *       `'Y'` still standing in the field would re-arm the moment a later turn made the balance
     *       payable again, without the operator having answered that turn.
     */
    setPayable(false);
    setConfirmation('');
    focusAccountId();
  }

  /**
   * Paints the outcome of a written payment.
   *
   * Assumptions: the screen is CLEARED and then the sentence is set, in that order, because that is the
   * order `app/cbl/COBIL00C.cbl` L523-L532 uses -- `PERFORM INITIALIZE-ALL-FIELDS` at L524 runs before
   * the success sentence is composed at L527. So a successful payment leaves the account entry blank, the
   * balance blank and only the sentence on screen. Setting the sentence first and clearing afterwards
   * would erase the very message the turn exists to deliver.
   * @param {string} transactionId - Identifier the service reported for the written payment row.
   * @returns {void} Nothing; the painted state is the screen's own.
   */
  function applyPaid(transactionId: string): void {
    clearScreen();
    setMessage(paymentSuccessMessage(transactionId));
    /*
     * WHY : Assumptions: success is the ONLY path that repaints the message line, and the mapset is what
     *       settles it. `ERRMSG` is declared `COLOR=RED`, and `MOVE DFHGREEN TO ERRMSGC` at
     *       `app/cbl/COBIL00C.cbl` L526 is the single override in the program. Every other sentence this
     *       screen can paint therefore stays red, the confirmation prompt and the nothing-to-pay advisory
     *       included, even though neither sets the program's error flag.
     */
    setSeverity('success');
  }

  /**
   * Releases the transport bookkeeping a turn holds, whatever that turn's outcome was.
   *
   * Purpose: release the double-submit guard, the screen-wide lock and the per-control indicator in one
   * place, so no outcome path can release one of the three and leave another standing.
   *
   * Refactoring Rationale: the three were released inline in both handlers of the single request this
   * screen used to make. There are now two requests and four outcome paths between them, and a lock
   * left standing on any one of them is a screen an operator cannot use again without reloading it.
   * @returns {void} Nothing; completion is the released lock.
   */
  function settleTurn(): void {
    inFlight.current = false;
    setBusy(false);
    setBusyControl(null);
  }

  /**
   * Refuses the account entry locally, before any request is composed.
   *
   * Purpose: answer a blank, malformed or zero account entry the way the reference answers it -- with a
   * sentence, a marked field and the cursor back on the entry -- and reach no network at all.
   *
   * Assumptions: the reported balance is discarded with the refusal. The entry that produced the
   * balance is the entry now being refused, so leaving the figure on screen would leave the operator
   * looking at a balance belonging to no account the screen currently names.
   * @param {BillPayFieldError} refusal - The refusal {@link refuseAccountIdEntry} raised for the entry.
   * @returns {void} Nothing; the painted state is the screen's own, and no request is made.
   */
  function refuseAccountEntry(refusal: BillPayFieldError): void {
    setMessage(refusal.message);
    setSeverity('error');
    setBalance(null);
    setPayable(false);
    setConfirmation('');
    setFieldErrors([refusal]);
    focusAccountId();
  }

  /**
   * Answers a confirmation character the reference refuses, which is its `WHEN OTHER` arm.
   *
   * Purpose: paint `'Invalid value. Valid values are (Y/N)...'` and put the cursor back on the
   * confirmation field, transcribing `app/cbl/COBIL00C.cbl` L185-L190 exactly.
   *
   * Refactoring Rationale: this arm was UNREACHABLE while the answer was supplied by a two-control
   * dialogue -- nothing but `'Y'` or `'N'` could be submitted, so no third character existed to refuse.
   * Restoring the typed field restores the arm, and the sentence is read from the shared catalog rather
   * than retyped because a second program emits the same literal.
   *
   * Assumptions: the reported balance and the standing offer both SURVIVE this refusal, and the
   * reference is what settles that. The arm sends the map at L190, before the balance move at
   * L193-L194 has run, so the operator keeps looking at the balance the previous turn read and the
   * confirmation field stays open for a second attempt. Withdrawing the offer here would make a typo
   * cost the operator the lookup as well.
   * @returns {void} Nothing; the painted state is the screen's own, and no request is made.
   */
  function refuseConfirmationAnswer(): void {
    setMessage(SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N);
    setSeverity('error');
    setFieldErrors([
      {
        field: CONFIRMATION_FIELD,
        state: 'NOT_OK',
        message: SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N,
      },
    ]);
    focusConfirm();
  }

  /**
   * Leaves the standing payment offer exactly as it is, clears the answer, and makes no request.
   *
   * Purpose: serve the two arms of the reference's confirmation evaluate that must not move money and
   * must not damage the turn's work -- the never-answered arm and the declining arm.
   *
   * Refactoring Rationale: this is where the CRITICAL defect is actually answered. While the answer was
   * a focusable control, the never-answered arm did not exist as a state at all: the cursor was placed
   * on a control whose default activation paid, so an Enter pressed against an unanswered prompt WAS
   * the payment. Measured before the fix, an operator who typed only the eleven account digits and then
   * pressed Enter three times moved the whole balance. Answering the unanswered prompt with nothing but
   * the prompt again is what makes Enter safe by default.
   *
   * Assumptions: the never-answered arm makes NO request, where `app/cbl/COBIL00C.cbl` L182-L184
   * performs `READ-ACCTDAT-FILE`. The observable outcome is identical -- the reference re-read the
   * record and repainted the same balance and the same prompt, because a 3270 turn always round-trips
   * -- so what is dropped is one request whose answer the screen already holds, not a state the
   * operator could see. Not making it is also what lets the regression be asserted directly: after a
   * preview, a bare Enter is provably a no-op on the wire.
   *
   * Assumptions: the declining arm keeps the entry and the balance, where L178-L181 performs
   * `CLEAR-CURRENT-SCREEN`. This is a DELIBERATE divergence and it is the one this screen's QA finding
   * names: measured, `'N'` erased the account number and discarded the fetched balance with no
   * acknowledgement anywhere on screen, so an operator who declined once had to retype the identifier
   * and pay for a second lookup to get back to where they were. Declining a payment is a safe act and a
   * safe act must not destroy work.
   *
   * Assumptions: the prompt is re-asserted rather than an acknowledgement composed, because
   * `app/cbl/COBIL00C.cbl` declares no cancellation literal at all -- its declining arm sets the error
   * flag precisely so that no sentence is emitted -- and rule T8 forbids putting words on the screen
   * that no line of the baseline holds. The offer genuinely does still stand, so the sentence that
   * states it is the honest one to leave standing.
   * @returns {void} Nothing; the painted state is the screen's own, and no request is made.
   */
  function awaitConfirmationAnswer(): void {
    setConfirmation('');
    setFieldErrors([]);
    setMessage(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);
    setSeverity('error');
    focusConfirm();
  }

  /**
   * Reads the payable balance for an account, which is the reference's reporting turn.
   *
   * Purpose: compose the ONE request that carries no confirmation, so a reading turn cannot post a
   * payment however the screen's state got there.
   *
   * Refactoring Rationale: the reading and the paying turn used to be one call whose only difference
   * was whether a `confirmation` member was present, which made "this turn cannot move money" a
   * property of a conditional rather than of the call. `inquireAccountPayableBalance` builds the body
   * itself and refuses to report a posted payment, so the guarantee is carried by the function that is
   * called and not by the branch that called it.
   * @param {string} account - The eleven-digit account entry, already refused if it was not one.
   * @returns {void} Nothing; every outcome, including every failure, is painted onto the screen.
   */
  function dispatchInquiry(account: string): void {
    inFlight.current = true;
    setBusy(true);
    setBusyControl('accountId');

    /*
     * WHY : Assumptions: the promise is consumed with a two-argument `then` rather than being awaited in
     *       an async handler. `@typescript-eslint/no-floating-promises` requires a rejection handler at
     *       the call site, and an event handler cannot be `async` without either returning a promise the
     *       caller drops or wrapping every call in a discard -- so the rejection path is given a named
     *       function here, which is also where the reference's own refusal wording is applied.
     */
    inquireAccountPayableBalance(account).then(
      /**
       * Paints the balance the service read.
       * @param {BillPaymentPreview} preview - The unconfirmed answer the service returned.
       * @returns {void} Nothing; the painted state is the screen's own.
       */
      (preview: BillPaymentPreview): void => {
        settleTurn();
        applyTurn(previewTurn(preview));
      },
      /**
       * Paints a failed lookup in the reference's own words.
       * @param {unknown} failure - Whatever the request rejected with.
       * @returns {void} Nothing; the reported state is the screen's own.
       */
      (failure: unknown): void => {
        settleTurn();
        reportFailure(failure, false);
      },
    );
  }

  /**
   * Writes the payment the operator confirmed by typing a confirming answer.
   *
   * Purpose: compose the ONE request that carries the confirmation, and reach it only from the arm that
   * classified a typed answer as confirming.
   *
   * Assumptions: the payment is ONE request, not a second write layered on the report. The reference
   * performs the cross-reference read, the identifier generation, the ledger write and the balance
   * reduction inside a single CICS task at L210-L235, and the service performs the equivalent inside one
   * database transaction. Splitting it client-side would make a partially applied payment observable, a
   * state the baseline never exhibits.
   *
   * Trade-offs: `payAccountBalanceConfirmed` REJECTS if the service answers a confirmed request with a
   * preview, so that outcome is painted with `'Unable to Add Bill pay Transaction...'` rather than with
   * the preview's own advisory. It is reachable -- the balance can fall to zero between the two turns,
   * because the reading turn deliberately takes no lock, and L197-L206 re-tests it on the confirming
   * turn for exactly that reason. The exchange is accepted: the sentence painted is one the reference
   * emits for a payment that did not write, it is painted for a payment that did not write, and what is
   * bought with it is a commit path that structurally cannot be reached with a non-confirming answer.
   * @param {string} account - The eleven-digit account entry, already refused if it was not one.
   * @param {string} answer - The confirming answer the operator typed, `'Y'` or `'y'`.
   * @returns {void} Nothing; every outcome, including every failure, is painted onto the screen.
   */
  function dispatchPayment(account: string, answer: string): void {
    inFlight.current = true;
    setBusy(true);
    setBusyControl('confirm');

    payAccountBalanceConfirmed(account, answer).then(
      /**
       * Paints the outcome of a written payment.
       * @param {Awaited<ReturnType<typeof payAccountBalanceConfirmed>>} payment - The row the service
       *   wrote for the payment.
       * @returns {void} Nothing; the painted state is the screen's own.
       */
      (payment): void => {
        settleTurn();
        applyPaid(payment.transactionId);
      },
      /**
       * Paints a failed payment in the reference's own words.
       * @param {unknown} failure - Whatever the request rejected with.
       * @returns {void} Nothing; the reported state is the screen's own.
       */
      (failure: unknown): void => {
        settleTurn();
        reportFailure(failure, true);
      },
    );
  }

  /**
   * Runs one turn of the screen, which is `PROCESS-ENTER-KEY` at `app/cbl/COBIL00C.cbl` L154-L244.
   *
   * Purpose: dispatch the Enter key to exactly one of the reference's arms, reading the confirmation
   * answer the operator typed rather than inferring one from which control happens to hold the cursor.
   *
   * Assumptions: the turn is dispatched on the FOUR-state classification of the typed answer, because
   * the reference's `EVALUATE CONFIRMI` at L173-L191 has four arms. Only one of them writes anything,
   * and it is reached only by a character the operator supplied, which is the whole of the safety
   * property this screen has to hold.
   *
   * Assumptions: the answer is only consulted while a payment is actually on offer. The reference has
   * the same gate in a different shape -- L208's `IF NOT ERR-FLG-ON` and the `CONF-PAY-YES` test at
   * L210 are both reached only after a read established a positive balance -- and this screen withdraws
   * the offer on every refusal, every failure and every edit of the account entry, so a stale answer
   * cannot survive into a turn that would honour it.
   *
   * Assumptions: the blank-entry refusal is answered LOCALLY and reaches no network, because the
   * reference answers it before it reaches a file. L159-L164 tests the entry against spaces and
   * low-values FIRST -- ahead of the confirmation evaluate at L173 -- and sends the map immediately, so
   * the check guards the payment turn as well as the report. The service validates the same condition
   * independently, so this is a duplicate by design rather than the only guard.
   * @returns {void} Nothing; every outcome, including every failure, is painted onto the screen.
   */
  function runEnterTurn(): void {
    /*
     * WHY : Assumptions: a turn arriving while one is in flight is DROPPED, and the guard matters more
     *       here than on a read-only screen. A 3270 keyboard locks until the region replies, so the
     *       reference is serialised by the hardware and needs no such guard; without one, a doubled Enter
     *       could submit the same payment twice and move the money twice where the operator asked once. A
     *       ref rather than the busy flag is what makes it effective: state updates are batched, so two
     *       events in one batch would both observe the old flag.
     */
    if (inFlight.current) {
      return;
    }

    const refusal = refuseAccountIdEntry(accountId);
    if (refusal !== null) {
      refuseAccountEntry(refusal);
      return;
    }

    /*
     * WHY : Assumptions: the entry is trimmed for the wire the same way it was trimmed to be judged, so
     *       the value looked up is the value the edit admitted. A 3270 field arrives space-filled to its
     *       declared width and the reference moves it into `ACCT-ID PIC 9(11)`, which discards the
     *       padding; a browser control has no padding, so the only blanks that can be here came from a
     *       paste and are not part of the identifier either.
     */
    const account = accountId.trim();

    if (!payable) {
      dispatchInquiry(account);
      return;
    }

    switch (classifyConfirmationAnswer(confirmation)) {
      case 'PAY':
        dispatchPayment(account, confirmation);
        return;
      case 'REFUSE':
        refuseConfirmationAnswer();
        return;
      /*
       * WHY : Assumptions: the declining answer and the never-answered field share one arm here where
       *       the reference gives them two, and they share it because after the divergence recorded at
       *       `awaitConfirmationAnswer` the two outcomes are identical: no request, no state discarded,
       *       the answer blank and the offer still standing. Writing them as two arms with one body
       *       would assert a difference that no longer exists.
       */
      case 'DECLINE':
      case 'PREVIEW':
      default:
        awaitConfirmationAnswer();
        return;
    }
  }

  /*
   * WHY : Assumptions: the destination is the origin the arriving screen declared, and the main menu is
   *       the default when it declared none. That is `app/cbl/COBIL00C.cbl` L128-L135 exactly: PF3 returns
   *       to `CDEMO-FROM-PROGRAM`, and substitutes `'COMEN01C'` -- the main menu -- when that field is
   *       blank or low-values.
   * WHY : Alternatives Considered: `navigate(-1)`, which reads as the natural browser form of "back".
   *       Rejected on two grounds. It replays whatever the browser's history holds, which may be a page
   *       outside this application entirely, where the reference can only ever return to a CardDemo
   *       program; and it cannot express the stated default, because there is no history entry to fall
   *       back to on a screen reached by a typed URL. `inApplicationRoute` matches the claimed origin
   *       against a closed set of this application's own routes, so a hand-edited history entry cannot
   *       redirect the key either.
   */
  const backDestination =
    inApplicationRoute(screenTransitionState(location.state).from) ?? MAIN_MENU_ROUTE;

  /*
   * WHY : Assumptions: exactly THREE bindings, because two independent sources in the reference agree on
   *       three. The row-24 legend advertises `ENTER=Continue  F3=Back  F4=Clear` and nothing else, and
   *       `app/cbl/COBIL00C.cbl` L125-L142 dispatches `DFHENTER`, `DFHPF3` and `DFHPF4` and sends every
   *       other attention identifier to `WHEN OTHER`. There is deliberately no PF5, PF7, PF8 or PF12
   *       entry here.
   * WHY : Assumptions: `PF_KEY_ALIASES` already folds PF13 to PF24 onto PF01 to PF12, which is
   *       `app/cpy/CSSTRPFY.cpy`'s own aliasing, so F15 and F16 reach the PF3 and PF4 handlers below
   *       without this screen registering them. Verified in a browser: F16 left the screen in a state
   *       byte-identical to the F4 state, and F15 reached the same destination as F3.
   * WHY : Refactoring Rationale: ALL THREE bindings are held shut for the duration of a turn, where
   *       only ENTER was. A 3270 keyboard is locked for the whole turn -- the terminal accepts no
   *       attention identifier at all between sending the map and receiving the region's reply -- so
   *       the reference could not have taken PF3 or PF4 mid-turn even in principle. Leaving those two
   *       live let an operator press F3 and leave the screen, or F4 and watch it clear, while
   *       `payAccountBalanceConfirmed` was still in flight and about to commit: the screen then showed a
   *       cleared or abandoned state for a payment that had moved money. Reproducing the lock explicitly
   *       is what a browser has to do, because nothing between the keyboard and this handler map
   *       enforces it.
   * WHY : Assumptions: they are held shut through TWO different channels and the split is deliberate.
   *       ENTER reports `busy`, because it is the key whose own turn is outstanding, and a busy control
   *       stays present, focusable and named while declining the press -- which is what the terminal
   *       did. PF3 and PF4 report `disabled`, because they are being withheld for a turn that is not
   *       theirs, and there is nothing running for them to announce. Both channels decline in silence
   *       here: the hook returns without reporting for a busy entry, and the `onInvalidKey` sink below
   *       discards a `'disabled'` rejection for exactly this reason.
   * WHY : Alternatives Considered: keeping the two keys live and CANCELLING the request instead, with
   *       an abort signal on the client. Rejected because it presents a cancellation this screen cannot
   *       perform: aborting the HTTP request abandons the response, not the write -- the service posts
   *       the ledger row and reduces the balance inside one database transaction that has no client-side
   *       undo -- so the operator would be told the payment was cancelled while it committed. A real
   *       cancellation would need an idempotent reversal endpoint, which is neither in the baseline nor
   *       in this checkpoint. Waiting is the honest affordance.
   * WHY : Trade-offs: for the duration of one request the operator cannot leave this screen by F3 or
   *       clear it by F4, and the two legend controls render greyed while that lasts. The alternative
   *       cost is a lost or contradicted payment, so the wait is accepted; it is bounded by the client's
   *       own request timeout, after which the rejection path re-enables everything and paints the
   *       reference's own `'Unable to Add Bill pay Transaction...'`.
   */
  /*
   * WHY : Assumptions: the risk this legend entry declares follows what the key WILL DO on the next
   *       press, not which attention identifier carries it and not the caption. `ENTER=Continue` is one
   *       caption over two different actions in this program: `app/cbl/COBIL00C.cbl` L182-L184 re-reads
   *       the record and writes nothing, while L173-L176 into L210-L235 writes the ledger row and
   *       reduces the balance. So the emphasis is derived from the arm {@link runEnterTurn} will take --
   *       which is exactly the pair of conditions that guard `dispatchPayment` there -- and the operator
   *       sees the strongest control on the bar only in the state where pressing it moves money.
   * WHY : Alternatives Considered: declaring this entry `'destructive'` for the whole session, which is
   *       the example `ui/src/layout/usePfKeys.ts` gives for a bill payment. Rejected on two grounds.
   *       It would paint the paying emphasis on every turn including the reading one, so the signal
   *       that distinguishes them would be gone; and `ui/src/layout/PfKeyBar.tsx` wraps a destructive
   *       control in `destructiveFocusTheme`, which this screen deliberately does not use -- that theme
   *       overrides only `components.Button.colorPrimaryBorder` and, since the confirmation dialogue was
   *       withdrawn, there is no destructive `Button` on this screen for it to act on. `'mutating'`
   *       resolves to a solid primary with no danger wrap, which is the honest treatment for a write
   *       that the operator has already been asked to confirm in a field of its own.
   * WHY : Trade-offs: the emphasis changes as the confirmation field is typed into, so the bar is not
   *       static across a turn. That is accepted because the change is the information: the control
   *       becomes emphatic at precisely the moment the next press would pay, and the field that caused
   *       it is the one the cursor is already sitting in.
   */
  const enterRisk: PfKeyRisk =
    payable && classifyConfirmationAnswer(confirmation) === 'PAY' ? 'mutating' : 'read-only';

  const pfKeyHandlers: PfKeyHandlerMap = {
    ENTER: {
      label: BILL_PAY_LEGEND_LABELS.ENTER,
      risk: enterRisk,
      /*
       * WHY : Refactoring Rationale: this entry reports `busy` where it declared `disabled: busy`, and
       *       the swap is not cosmetic -- `ui/src/layout/usePfKeys.ts` tests `disabled` BEFORE `busy`,
       *       so an entry carrying both resolves as disabled and the busy channel never runs. The two
       *       differ in what the operator gets: a disabled legend control is greyed, unfocusable and
       *       loses its place in the tab order for the duration of a write, where a busy one stays
       *       present, focusable and named and simply declines the press. `ui/src/layout/PfKeyBar.tsx`
       *       records the reference behaviour this reproduces -- a 3270 announced a running task and
       *       withdrew nothing -- and inhibited input at the keyboard, which is a silent decline and
       *       not a message. Both activation paths stay closed: the hook refuses the key press and the
       *       design system's own button refuses the click while it is loading.
       * WHY : Assumptions: the flag is read from the synchronous ref rather than from the `busy` render
       *       state, and it is passed as a PREDICATE so it is evaluated at dispatch. The ref is set
       *       inside the same task that starts the turn, where a state update is batched and cannot be
       *       observed by a second event in the same batch -- which is the doubled-Enter this guard
       *       exists for and the reason {@link runEnterTurn} already latches on the same ref.
       */
      busy:
        /**
         * Answers whether a turn this key started is still outstanding.
         *
         * Purpose: give the legend control its busy affordance for exactly the window in which a second
         * press would duplicate a write, and release it the moment the turn settles.
         * @returns {boolean} True while a turn started from this key has not yet settled.
         */
        (): boolean => inFlight.current,
      onInvoke:
        /**
         * Runs one turn, which is the reference's `DFHENTER` arm at L126-L127.
         *
         * Refactoring Rationale: the binding passes NO argument, where it passed `null` to mean "read,
         * do not pay". The turn now reads the confirmation field itself, which is what the reference
         * does at L173 -- so the key that the row-24 legend advertises as `ENTER=Continue` no longer
         * has a second caller that can pass it a confirming answer the operator never typed.
         * @returns {void} Nothing; the turn paints itself and never rejects to this caller.
         */
        (): void => {
          runEnterTurn();
        },
    },
    PFK03: {
      label: BILL_PAY_LEGEND_LABELS.PFK03,
      /*
       * WHY : Assumptions: `F3=Back` is declared `'read-only'` because the caption names a navigation
       *       and `app/cbl/COBIL00C.cbl` L128-L135 performs exactly that -- an `XCTL` to the origin
       *       program with no file access on the way. It keeps `disabled: busy` rather than reporting
       *       busy, because it does not OWN the outstanding turn: the busy channel says "the key you
       *       pressed is running, wait", which would be a false statement about a key that is not
       *       running and is being withheld for the duration of a write it has nothing to do with.
       */
      risk: 'read-only',
      disabled: busy,
      onInvoke:
        /**
         * Returns to the screen that transferred here, or to the main menu.
         * @returns {void} Nothing; navigation is performed for its effect.
         */
        (): void => {
          navigateSafely(navigate, backDestination);
        },
    },
    PFK04: {
      label: BILL_PAY_LEGEND_LABELS.PFK04,
      /*
       * WHY : Assumptions: `F4=Clear` is declared `'read-only'` on the strength of what it does to the
       *       RECORD, not to the screen. `CLEAR-CURRENT-SCREEN` at `app/cbl/COBIL00C.cbl` L552-L555
       *       moves spaces into the map's own fields and sends it; no file is read and none is written,
       *       so nothing an operator could lose exists outside the fields they can retype. Treating a
       *       screen reset as `'mutating'` would put the paying emphasis on a key that cannot pay and
       *       would leave the bar with two emphatic controls, which is the signal this channel exists
       *       to make scarce.
       */
      risk: 'read-only',
      disabled: busy,
      onInvoke:
        /**
         * Clears every field and the message, which is `CLEAR-CURRENT-SCREEN` at L552-L555.
         * @returns {void} Nothing; the cleared state is the screen's own.
         */
        (): void => {
          clearScreen();
        },
    },
  };

  /*
   * WHY : Refactoring Rationale: an `onInvalidKey` sink is supplied, where this call passed its handler
   *       map alone. The claim that stood here -- that `usePfKeys` paints the shared sentence itself, so
   *       raising it from the screen would paint it twice -- is WITHDRAWN as measurably false.
   *       `ui/src/layout/usePfKeys.ts` L479-L493 composes the rejection and then delivers it through
   *       `options.onInvalidKey?.(...)` and nowhere else, so a call site that supplies no sink discards
   *       it. Measured in a browser rather than inferred: F5, F7, F8, F9 and F12 each arrived as a
   *       trusted keydown and the row-23 line stayed empty for all five, with a mutation observer on the
   *       band recording zero changes -- so the sentence was painted zero times, not twice. The same key
   *       on the main menu, which does supply a sink, painted it. The `WHEN OTHER` arm at
   *       `app/cbl/COBIL00C.cbl` L138-L141 moves `CCDA-MSG-INVALID-KEY` into the message and re-sends the
   *       map, so leaving it unpainted dropped a message the reference always emits.
   * WHY : Assumptions: no `restoreFocusRef` is supplied, and that omission remains deliberate. The option
   *       exists for screens whose invalid-key arm performs the reference's `MOVE -1 TO <field>L`, and
   *       this program's does not: those three statements set the error flag, move the sentence and send
   *       the map, with no cursor move among them. Supplying a destination would move the cursor on the
   *       one turn the reference leaves it alone.
   */
  const { bindings, invoke } = usePfKeys(pfKeyHandlers, {
    onInvalidKey:
      /**
       * Paints the shared invalid-key sentence for an attention identifier this map does not carry.
       *
       * Assumptions: the sentence is passed on UNCHANGED from the catalog, trailing spaces included. It
       * is declared `PIC X(50)` at `app/cpy/CSMSG01Y.cpy` L20-L21 and the catalog carries the declared
       * width, so trimming it here would narrow a contract this screen does not own.
       * @param {PfKeyRejection} rejection - Which identifier was refused, why, and in what words.
       * @returns {void} Nothing; completion is the painted message line.
       */
      (rejection: PfKeyRejection): void => {
        /*
         * WHY : Assumptions: only an UNMAPPED identifier paints the sentence; one refused because its
         *       handler is momentarily disabled is answered in silence. The two are different events in
         *       the reference. An unmapped identifier is the `WHEN OTHER` arm, which paints. A disabled
         *       one only ever arises here because a turn is in flight, which holds PF3 and PF4 shut --
         *       and that is the 3270 keyboard lock, which the hardware enforced by refusing the
         *       keystroke before the program ever saw it, so no sentence was composed and none was
         *       painted. Answering both alike would invent a message on a keystroke the reference
         *       discards, and it would invent it three times over for an operator who kept pressing
         *       keys while a payment was being written. The sibling main-menu screen draws the same
         *       distinction for the same reason.
         * WHY : Assumptions: ENTER never reaches this sink at all during a turn, because it reports
         *       `busy` rather than `disabled` and `ui/src/layout/usePfKeys.ts` returns from a busy entry
         *       without reporting anything. So the two channels agree on the outcome -- silence -- by
         *       two routes, and this guard remains load-bearing for the two keys that still take the
         *       disabled route.
         */
        if (rejection.reason !== 'unmapped') {
          return;
        }

        setMessage(INVALID_KEY_PRESSED);
        setSeverity('error');
      },
  });

  /*
   * WHY : ⚠️ Assumptions: this screen composes no title band, no row-23 message line and no row-24
   *       legend of its own, and publishes all three here instead. The claim that stood here -- that
   *       `ui/src/layout/AppShell.tsx` documents every screen as delegating and none as self-composing
   *       -- is WITHDRAWN as false. Counted over the 21 screen directories, 18 delegate through this
   *       hook and three compose their own three bands: `ui/src/screens/menu`, `ui/src/screens/admin`
   *       and `ui/src/screens/authDetail`. Delegation is the majority contract with three documented
   *       exceptions, and it is safe to be either because the frame paints a zone only for a screen that
   *       published one, so a self-composing screen leaves the frame's own bands unrendered rather than
   *       doubled.
   * WHY : Assumptions: this screen chooses the majority side because it has nothing to gain from the
   *       exception and one thing to lose. Publishing keeps the message line a SINGLE live region: a
   *       payment refusal and a payment success are announced once each, where a screen-composed band
   *       inside the frame's would announce both twice, and the row-24 legend would offer two controls
   *       per key with the same handler behind each. The three exceptions are menus and a detail panel
   *       that own their whole viewport; this screen owns rows 4 to 22 of a frame that is already there.
   * WHY : Assumptions: the frame is mounted ABOVE this route rather than by it. `ui/src/router.tsx`
   *       mounts one `AppShell` as the outermost layout route, whose children are the sign-on route and
   *       a pathless guarded route -- so `RequireSignOn` sits INSIDE the frame and `ui/src/App.tsx`
   *       composes no frame at all, only the theme and the one router provider. That is why this call
   *       publishes into a frame it never renders and never needs to check for.
   * WHY : Assumptions: the bindings and the dispatcher published here are the SAME pair `usePfKeys`
   *       resolved above, so a legend control the frame renders and a physical key press run one handler.
   *       Publishing a separately built descriptor list would let the two diverge.
   */
  useShellSlot({
    screen: {
      transactionId: BILL_PAY_TRANSACTION_ID,
      programName: BILL_PAY_PROGRAM_NAME,
    },
    now: paintedAt,
    /*
     * WHY : Assumptions: the `message` slot carries NO `information` member, and the omission is a
     *       measured decision rather than an oversight. The frame's information channel reproduces the
     *       second message line some mapsets declare at row 22 -- `INFOMSG` -- and `COBIL00` declares
     *       none: `grep -n "POS=(2[0-4]" app/bms/COBIL00.bms` returns exactly two fields, `ERRMSG` at
     *       `POS=(23,1)` and the legend at `POS=(24,1)`, and `grep -n INFOMSG` returns nothing. This
     *       screen therefore has one message line and reserves no row for a second.
     * WHY : Alternatives Considered: publishing `information: { text: null }` to reserve the row anyway,
     *       which is the shape a screen whose mapset DOES declare row 22 must publish on every turn so
     *       the row does not appear and disappear under the operator. Rejected here because it would
     *       reserve vertical space this map never spends, pushing the row-24 legend down by one line
     *       relative to the terminal on every turn of this screen -- a fidelity loss taken to hold open
     *       a channel that has nothing to say.
     */
    message: { text: message, severity, mapset: BILL_PAY_MAPSET },
    pfKeys: { keys: bindings, onInvoke: invoke },
    /*
     * WHY : Assumptions: the write-in-flight flag is delegated so the FRAME's sign-off control is held
     *       shut for the same window this screen holds its own keys and inputs shut. Every other way off
     *       this screen is already locked for the turn -- Enter, F3, F4 and both entry controls -- and the
     *       shell's control was the one remaining exit, which is also the most damaging one: it discards
     *       the session locally while the request already carrying its token completes at the service, so
     *       the operator lands on sign-on believing the turn was abandoned.
     * WHY : Trade-offs: the flag is published rather than the shell inferring it. The frame sees a title,
     *       a message and a legend, none of which distinguishes a read from a write, so inferring it would
     *       mean the frame reaching into this screen's transport state. `ui/src/layout/AppShell.tsx`
     *       records the same conclusion on the slot member.
     */
    busy,
  });

  /*
   * WHY : Refactoring Rationale: the text colours resolve through `BMS_TEXT_COLOR_TOKENS` rather than
   *       through the hue map `BMS_COLOR_TOKENS`. The measured source roles are unchanged -- `COLOR=NEUTRAL`
   *       on the title and the domain hint, `COLOR=GREEN` on the account label, `COLOR=TURQUOISE` on the
   *       balance label and the confirmation prompt, `COLOR=BLUE` on the balance itself, `COLOR=YELLOW` on
   *       the rule -- but the hue map's entries are fill-grade anchors and several measure below WCAG AA
   *       when read as text. `ui/src/theme/tokens.ts` records the per-role measurement and which roles kept
   *       their hue family.
   * WHY : Trade-offs: every value below is a token reference and not a literal. AAP section 0.3.2 admits
   *       only `0`, `none`, `auto`, `inherit`, `currentColor` and `transparent` as literals, so a hex
   *       colour or a pixel measurement here would be a design value with no entry in the bridge and no
   *       way for the theme to move it.
   */
  const titleStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };
  const accountLabelStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.GREEN] };
  const promptStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] };
  const ruleStyle: CSSProperties = { borderColor: cssVar[BMS_TEXT_COLOR_TOKENS.YELLOW] };

  /*
   * WHY : Refactoring Rationale: the `(Y/N)` hint resolves through `HINT_TEXT_TOKENS` rather than
   *       through the BMS role map directly. Both name the same measured source attribute -- `(Y/N)` is
   *       `COLOR=NEUTRAL` at `app/bms/COBIL00.bms` L121-L125 -- but the hint map is where the tree
   *       records that a parenthesised domain hint has TWO measured roles, blue and neutral, and that
   *       the neutral one must resolve to the text-grade secondary shade rather than to the design
   *       system's own de-emphasis default. Reading it from there is what keeps this hint painted the
   *       same as every other neutral hint in the application.
   */
  const domainHintStyle: CSSProperties = { color: cssVar[HINT_TEXT_TOKENS.NEUTRAL] };

  /*
   * WHY : Refactoring Rationale: both entry controls are sized from the character width their copybook
   *       PICTURE declares, where neither was sized at all. Measured, the eleven-character account entry
   *       rendered 1173.33px wide -- the full content column -- so the value it holds sat alone at the
   *       left of a field a hundred times wider than its data, and any marker at the field's right-hand
   *       edge sat about 1150px from the value it qualified. The helper is spread onto the antd control
   *       itself rather than onto a wrapper because the padding term it reads resolves in the control's
   *       own class scope and returns empty on a plain element.
   * WHY : Trade-offs: the declared width is a CEILING, not a fixed size -- the helper keeps
   *       `inlineSize: '100%'` alongside it -- so a field wider than a phone viewport still shrinks to
   *       fit rather than forcing the page to scroll sideways. Design gap G1 records the same trade for
   *       position.
   */
  const accountFieldStyle = copybookFieldWidthStyle(BILL_PAY_FIELD_WIDTHS.accountId, cssVar);
  const confirmationFieldStyle = copybookFieldWidthStyle(
    BILL_PAY_FIELD_WIDTHS.confirmation,
    cssVar,
  );

  /*
   * WHY : Refactoring Rationale: the balance is rendered through the shared money renderer, which
   *       returns the masked text, the sign it classified, the token that sign is painted in and the
   *       `white-space` mode the rendering requires. The mode is load-bearing rather than decorative:
   *       every measured picture pads its integer field, and HTML collapses a run of spaces in a text
   *       node, so painting the text without it discards the column alignment the mask exists to
   *       produce.
   * WHY : Trade-offs: the colour therefore comes from the SIGN and no longer from this mapset's single
   *       `COLOR=BLUE` on `CURBAL` at `app/bms/COBIL00.bms` L103-L106. That is the shared module's own
   *       recorded decision and it is taken deliberately here: the baseline paints a credit, a debit and
   *       a settled balance in one hue, and this screen can reach all three -- the nothing-to-pay
   *       advisory at `app/cbl/COBIL00C.cbl` L198 is `<= ZEROS`, so zero and credit both land on it.
   *       Colour is redundant either way, because the picture is signed and the leading `+` or `-` is in
   *       the rendered text.
   */
  const renderedBalance = balance === null ? null : renderMoney(balance, BALANCE_PICTURE);

  /*
   * WHY : Refactoring Rationale: the resolved reference is narrowed by a `typeof` TEST, where it was
   *       narrowed by calling `String` on it. The renderer publishes a token NAME typed as any key of
   *       the theme's token map, and that map carries non-string members -- radii, heights -- so the
   *       indexed type is wider than a colour even though every name the renderer can return addresses
   *       one. `String` looked like the narrowing that cannot be wrong, but it is the one narrowing that
   *       cannot FAIL: applied to a member that was not a string it would have produced a CSS value of
   *       `[object Object]` and painted nothing, which is why `@typescript-eslint/no-base-to-string`
   *       refuses it. A `typeof` test proves the member is a string before it is used and drops the
   *       declaration entirely when it is not, so a mis-typed token name loses the colour rather than
   *       poisoning the style.
   * WHY : Assumptions: at run time this is the same value it always was. Every name the renderer can
   *       return addresses a colour, and a resolved reference is already the string `var(--ant-...)`,
   *       so the test passes on every reachable input and the painted colour is unchanged.
   * WHY : Alternatives Considered: a type assertion on the indexed access, which the checker accepts
   *       silently. Rejected because it asserts the very thing that would be false in the failing case
   *       and would put the `[object Object]` back with the diagnostic removed.
   */
  const balanceColourReference =
    renderedBalance === null ? null : cssVar[renderedBalance.colorToken];
  const balanceColour = typeof balanceColourReference === 'string' ? balanceColourReference : null;
  const balanceStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
    ...(renderedBalance === null
      ? {}
      : {
          ...(balanceColour === null ? {} : { color: balanceColour }),
          whiteSpace: renderedBalance.whiteSpace,
        }),
  };

  const accountRefusal = fieldErrors.find(
    /**
     * Selects the refusal attributed to the account entry.
     * @param {BillPayFieldError} refusal - One refusal from the settled turn.
     * @returns {boolean} True when the refusal names the account entry.
     */
    (refusal) => refusal.field === ACCOUNT_ID_FIELD,
  );

  /*
   * WHY : Refactoring Rationale: refusals attributed to the confirmation field are selected and
   *       rendered, where they were discarded. There is now a control for them to mark -- the field the
   *       reference declares -- and two sources can raise one: this screen's own `WHEN OTHER` arm, and
   *       the service, which validates the same member independently against its own `^[YyNn]?$`
   *       pattern. Both arrive as the same shape and are marked by the same code path.
   */
  const confirmationRefusal = fieldErrors.find(
    /**
     * Selects the refusal attributed to the confirmation answer.
     * @param {BillPayFieldError} refusal - One refusal from the settled turn.
     * @returns {boolean} True when the refusal names the confirmation field.
     */
    (refusal) => refusal.field === CONFIRMATION_FIELD,
  );

  /**
   * Drops the confirmation field's refusal while leaving every other refusal standing.
   *
   * Purpose: let the operator's next keystroke in the confirmation field clear that field's sentence
   * without disturbing a refusal the same turn attributed to the account entry.
   * @param {readonly BillPayFieldError[]} current - The refusals the last turn left standing.
   * @returns {readonly BillPayFieldError[]} The same refusals without the confirmation field's.
   */
  function withoutConfirmationRefusal(
    current: readonly BillPayFieldError[],
  ): readonly BillPayFieldError[] {
    return current.filter(
      /**
       * Keeps every refusal that is not the confirmation field's.
       * @param {BillPayFieldError} refusal - One refusal the last turn left standing.
       * @returns {boolean} True when the refusal names a field other than the confirmation entry.
       */
      (refusal: BillPayFieldError): boolean => refusal.field !== CONFIRMATION_FIELD,
    );
  }

  /*
   * WHY : Trade-offs: the map's absolute row and column positions are NOT reproduced. Every field on
   *       `COBIL0A` is placed at a character cell on a fixed 24 by 80 grid, and reproducing that in a
   *       browser would be hostile to assistive software and impossible to reflow. Gap G1 in AAP section
   *       0.3.4 records the deviation. What IS preserved is everything the positions encoded: the grouping
   *       of the fields, their reading order down the screen and the tab order through them.
   * WHY : Trade-offs: all spacing comes from `Flex` and `Space` with gaps read from the spacing token
   *       scale, and no element below carries bespoke layout CSS. AAP section 0.3.2 requires layout through
   *       the design system's own primitives, so a hand-rolled flex or grid on a raw element would put a
   *       design value outside the bridge.
   */
  return (
    <Flex vertical gap={cssVar[SPACING_TOKENS.sectionGapLarge]}>
      {/*
       * Assumptions: the title is a heading element rather than emphasised text, because it is the
       * screen's own name and assistive software needs it in the document outline. `ScreenTitle` fixes the
       * level so every screen agrees, and `BRT` resolves to the strong-weight token rather than to a
       * colour, which is how the 3270 brightness attribute is carried across.
       */}
      <ScreenTitle style={titleStyle}>{BILL_PAY_TITLE}</ScreenTitle>
      {/*
       * WHY : Purpose: this states IN WORDS that a turn is running, which nothing else on the screen
       *       does. `aria-busy` on the field that owns the turn reports the state and no remedy;
       *       `REQUEST_IN_PROGRESS` reads "Working on your request. Wait for the screen to answer." and
       *       tells the operator what to do about it. It matters most on the paying turn, where the
       *       operator has just committed money and every control they might reach for -- the two
       *       fields, PF3 and PF4 -- has gone quiet at once.
       * WHY : Assumptions: the region is mounted on EVERY turn and holds the empty string when idle,
       *       which is `busyAnnouncement`'s own contract and is load-bearing rather than tidy. A
       *       `role="status"` element that is inserted at the moment it acquires text is frequently not
       *       announced at all, because the assistive reader has no live region to observe until after
       *       the text is already there; one that is present from the first render and changes content
       *       is announced. The element is visually hidden, so an always-mounted region costs nothing
       *       an operator can see.
       * WHY : Assumptions: the sentence is passed for the WHOLE busy window rather than per control,
       *       because the whole screen is inhibited for it -- `busy` disables both fields and both
       *       withheld keys -- so a per-control announcement would say the same thing twice about one
       *       event. Which control owns the turn is already carried by `aria-busy` through
       *       {@link busyProps}, and that is the right channel for it: it is a property of a control,
       *       not a sentence for an operator.
       * WHY : Alternatives Considered: publishing the sentence onto the row-23 message line instead.
       *       Rejected because that line is a parity surface -- every sentence on it is transcribed from
       *       a baseline source under rule T8 -- and it already holds the reference's own
       *       `'Confirm to make a bill payment...'` at exactly the moment a payment is in flight.
       *       Overwriting a transcribed sentence with an authored one would lose the offer the operator
       *       is answering, and restoring it afterwards would repaint the band on a turn the reference
       *       does not repaint.
       */}
      {busyAnnouncement(busy ? REQUEST_IN_PROGRESS : undefined)}

      {/*
       * Assumptions: no `onFinish` and no submit control, deliberately. Every turn on this screen is
       * driven by a function key or by the confirmation dialogue, so a native form submission would give
       * ENTER a second path and run the lookup twice. `usePfKeys` does not claim ENTER while focus is in a
       * text input -- its own target selector lists buttons, links, selects and textareas and not text
       * inputs -- so ENTER typed in the account field reaches the binding above.
       */}
      <Form layout="vertical">
        <Form.Item
          label={
            <Typography.Text style={accountLabelStyle}>
              {BILL_PAY_FIELD_LABELS.accountId}
            </Typography.Text>
          }
          htmlFor={ACCOUNT_ID_CONTROL_ID}
          {...(accountRefusal === undefined
            ? {}
            : {
                validateStatus: 'error' as const,
                help: fieldErrorHelp(ACCOUNT_ID_CONTROL_ID, accountRefusal.message),
              })}
        >
          {/*
           * Assumptions: `maxLength` is eleven because three independent declarations in the reference say
           * so -- `ACTIDIN LENGTH=11` at `app/bms/COBIL00.bms` L85-L89, `ACTIDINI PIC X(11)` at
           * `app/cpy-bms/COBIL00.CPY` L60, and the record field `ACCT-ID PIC 9(11)` at
           * `app/cpy/CVACT01Y.cpy` L5 that the entry is moved into. The carried selection
           * `CDEMO-ACCT-ID PIC 9(11)` at `app/cpy/COCOM01Y.cpy` L38 agrees.
           * Assumptions: this is the screen's only INITIAL-cursor control, because `ACTIDIN` carries this
           * mapset's single `IC` attribute and `app/cbl/COBIL00C.cbl` L115 moves `-1` into `ACTIDINL` on
           * first entry, so the cursor starts here whatever else happens. The confirmation dialogue below
           * also focuses its answer on open, and that is a different thing: it is not a second `IC` but
           * the `MOVE -1 TO CONFIRML` placement of L239, on a panel that does not exist until the
           * operator opens it and is destroyed when it closes.
           * Alternatives Considered: the design system's numeric input with `controls={false}` and
           * `stringMode`. Rejected because this identifier is CHARACTERS on the wire, not a number:
           * `app/cpy/CVCRD01Y.cpy` L34-L36 declares `CC-ACCT-ID PIC X(11)` with a numeric `REDEFINES`, so
           * the value is text everywhere except in arithmetic. A numeric control would route eleven digits
           * through a JavaScript number and can drop a leading zero, which would change the key being
           * looked up.
           * Assumptions: `HILIGHT=UNDERLINE` on the source field needs no token of its own. The design
           * system expresses an input affordance structurally, through the control's border, which gap G4
           * in AAP section 0.3.4 records so a later reader does not read the absence as an oversight.
           */}
          <Input
            id={ACCOUNT_ID_CONTROL_ID}
            ref={accountInputRef}
            value={accountId}
            maxLength={BILL_PAY_FIELD_WIDTHS.accountId}
            inputMode="numeric"
            autoFocus
            disabled={busy}
            style={accountFieldStyle}
            {...busyProps(busyControl === 'accountId')}
            {...fieldAriaProps(ACCOUNT_ID_CONTROL_ID, {
              invalid: accountRefusal !== undefined,
              hasError: accountRefusal !== undefined,
              hasHint: false,
            })}
            onChange={
              /**
               * Records the operator's own entry unchanged and discards the rest of the turn's state.
               *
               * Refactoring Rationale: the proposed value is stored AS TYPED, where it used to be run
               * through a digit filter first. The filter's failure was measured, not theorised: a paste
               * of `{{7*7}} and ${7*7}` became `7777`, which was then looked up, returned a real
               * balance for an account the operator never named, and armed the payment -- with nothing
               * on screen saying the value had been altered. A 3270 numeric field DISCARDED a
               * non-numeric keystroke; it never compacted the digits out of a longer string into a
               * different well-formed identifier. Keeping the operator's characters and refusing the
               * turn is the honest reconstruction, and {@link refuseAccountIdEntry} is what refuses it.
               * @param {ChangeEvent<HTMLInputElement>} event - The control's change event.
               * @returns {void} Nothing; the entry becomes the screen's state.
               */
              (event: ChangeEvent<HTMLInputElement>): void => {
                setAccountId(event.target.value);
                /*
                 * WHY : Assumptions: editing the entry discards the reported balance and withdraws the
                 *       payment control, because the balance on screen describes the account that WAS
                 *       looked up. Leaving it in place would let an operator confirm a payment against a
                 *       figure belonging to a different account than the one now typed, which is a
                 *       money-moving confusion the reference cannot produce -- its balance field is
                 *       repainted by the same turn that reads the entry.
                 * WHY : Assumptions: the typed answer goes with them. An operator who had already typed
                 *       `'Y'` and then corrected the identifier would otherwise be one Enter away from
                 *       paying the account they had just finished typing, on an answer they gave about a
                 *       different one.
                 */
                setBalance(null);
                setPayable(false);
                setConfirmation('');
                setFieldErrors([]);
              }
            }
          />
        </Form.Item>
      </Form>

      {/*
       * Alternatives Considered: emitting the seventy hyphens the mapset paints, which is what
       * `app/bms/COBIL00.bms` L93-L97 declares as a `LENGTH=70` text field. Rejected here: a run of
       * punctuation is announced character by character by some screen readers and carries no meaning
       * beyond "these groups are separate", which is exactly what a separator element says structurally.
       * The rule's `COLOR=YELLOW` is carried onto the separator so the measured source colour is not lost.
       * Trade-offs: the separator spans its container rather than seventy of eighty columns, which is the
       * same character-grid deviation gap G1 already records for every other field on the screen.
       */}
      <Divider style={ruleStyle} />

      {/*
       * Assumptions: the balance is read-only and is never an editable control. `CURBAL` is
       * `ATTRB=(ASKIP,FSET,NORM)` at `app/bms/COBIL00.bms` L103-L106 -- auto-skip, so the 3270 cursor
       * could not enter it -- and the operator has no way to alter the figure they are being asked to pay.
       * Assumptions: it is rendered in the fixed-pitch token so the digits occupy one column width each,
       * which is what kept amounts aligned on the terminal and what makes the zero-padded mask legible.
       */}
      <Flex align="baseline" gap={cssVar[SPACING_TOKENS.sectionGapCompact]} wrap>
        <Typography.Text style={promptStyle}>
          {BILL_PAY_FIELD_LABELS.currentBalance}
        </Typography.Text>
        <Typography.Text style={balanceStyle} data-testid="billpay-current-balance">
          {renderedBalance === null ? '' : renderedBalance.text}
        </Typography.Text>
      </Flex>

      {/*
       * WHY : ⚠️ Refactoring Rationale: the confirmation is the single-position `CONFIRM` FIELD the
       *       mapset declares, and the dialogue that replaced it is withdrawn. The claim it was
       *       introduced on -- that the field "only existed because a 3270 terminal had no modal" -- is
       *       measurably the wrong way round: the field is what made the payment safe. `CONFIRM` at
       *       `app/bms/COBIL00.bms` L115-L119 is `ATTRB=(FSET,NORM,UNPROT) LENGTH=1` with NO `IC`, and
       *       `app/cbl/COBIL00C.cbl` L173-L191 pays only on a `'Y'` or `'y'` the operator put there --
       *       so the gate was a KEYSTROKE, not a control an Enter could fall onto.
       * WHY : ⚠️ Refactoring Rationale: what the dialogue actually produced was measured in a browser
       *       and it is this screen's release blocker. The dialogue's trigger was focused
       *       programmatically once a balance became payable, and the dialogue's own affirmative control
       *       carried `autoFocus`, so "commit" was the default action at two CONSECUTIVE turns: an
       *       operator who typed only the eleven account digits and then pressed Enter three times --
       *       zero clicks, no `Y` ever typed -- moved the entire current balance, on a key the row-24
       *       legend advertises as `ENTER=Continue`. Both auto-focus steps are gone and the answer is a
       *       character the operator types.
       * WHY : Assumptions: `'Invalid value. Valid values are (Y/N)...'` at `app/cbl/COBIL00C.cbl` L187
       *       becomes REACHABLE again, where the dialogue had made it dead code -- two controls sending
       *       `'Y'` and `'N'` left no third character to refuse. {@link refuseConfirmationAnswer} is
       *       that arm, and it is now the only thing a character outside the domain can produce.
       * WHY : Alternatives Considered: keeping the dialogue and merely moving its initial focus onto the
       *       declining control, which is the narrowest possible change and is what the finding's own
       *       suggested fix offers first. Rejected on two grounds. It leaves Enter as a COMMIT gesture
       *       one Tab away rather than removing it, so the same defect returns for any operator who
       *       tabs once; and it makes Enter on a dialogue opened in order to pay clear the screen
       *       instead, which discards the operator's work silently. Also considered: keeping the
       *       dialogue and adding a typed field inside it. Rejected as two gates for one question --
       *       the baseline asks once, and asking twice trains an operator to answer without reading.
       * WHY : Trade-offs: no control on this screen is now marked destructive, and `destructiveFocusTheme`
       *       is therefore NOT wrapped around this field. Three reasons, in order of weight. The theme
       *       overrides `Button.colorPrimaryBorder` only, so on an `Input` it resolves to nothing at all
       *       and wrapping it would assert a treatment that does not exist. The mapset paints this field
       *       `COLOR=GREEN` with no warning hue anywhere near it, so an error-ramp ring here is a design
       *       value the baseline contradicts. And this field already uses the error ramp for its REAL
       *       refusal state, so painting the same ramp on focus would give one control two meanings for
       *       one colour. The control that commits is the row-24 `ENTER=Continue` button, which
       *       `ui/src/layout/PfKeyBar.tsx` owns.
       */}
      <Form layout="vertical">
        <Form.Item
          /*
           * WHY : Refactoring Rationale: the 53-character prompt is the field's LABEL, where it was an
           *       inline block beside the control. Measured at 375: the prompt rendered as a fixed
           *       333.7px block that filled the line, so the answer control wrapped below it and came to
           *       rest flush at x=0 with its focus ring clipped on the left. A label owns its own line
           *       and takes the control's width with it, which removes the orphaned wrap rather than
           *       relying on the viewport being wide enough to avoid it.
           * WHY : Assumptions: the text is `COLOR=TURQUOISE` on `app/bms/COBIL00.bms` L109-L113 and the
           *       trailing space the catalog carries is part of the declared literal, so neither is
           *       altered by moving where the text is rendered.
           */
          label={
            <Typography.Text style={promptStyle}>
              {BILL_PAY_FIELD_LABELS.confirmPrompt}
            </Typography.Text>
          }
          htmlFor={CONFIRMATION_CONTROL_ID}
          {...(confirmationRefusal === undefined
            ? {}
            : {
                validateStatus: 'error' as const,
                help: fieldErrorHelp(CONFIRMATION_CONTROL_ID, confirmationRefusal.message),
              })}
        >
          {/*
           * WHY : Refactoring Rationale: the hint sits in a `Flex` with a token gap, where it sat in a
           *       `Space` with the same token passed as `size`. Measured, that produced a 0.0px gap and
           *       the `(Y/N)` butted directly against the control. The cause is in the pinned package:
           *       `antd/lib/_util/gapSize.js` accepts only the four preset NAMES or a number, and
           *       `antd/lib/space/index.js` emits neither a preset class nor a `columnGap` for anything
           *       else -- so a `var(--ant-margin-xs)` reference was accepted and then dropped. `Flex`
           *       assigns its `gap` straight to the CSS property, where a custom-property reference
           *       resolves.
           * WHY : Assumptions: a gap belongs here at all because the mapset declares one. `CONFIRM`
           *       occupies column 60 and the `(Y/N)` literal starts at `POS=(15,63)` on
           *       `app/bms/COBIL00.bms` L121-L125, so the terminal left two character cells between
           *       them.
           */}
          <Flex align="center" gap={cssVar[SPACING_TOKENS.sectionGapCompact]} wrap>
            {/*
             * Assumptions: `maxLength` is ONE because three declarations agree -- `CONFIRM LENGTH=1` at
             * `app/bms/COBIL00.bms` L115-L119, `CONFIRMI PIC X(1)` in the symbolic map, and the
             * service's own `^[YyNn]?$` pattern on the request member. One position is also what makes
             * the four-arm classification total: there is no multi-character value to disambiguate.
             * Assumptions: this is NOT an initial-cursor control. `ACTIDIN` carries this mapset's single
             * `IC` attribute, so `autoFocus` stays on the account entry; the cursor arrives here only
             * when a settled turn offers payment, which is `MOVE -1 TO CONFIRML` at
             * `app/cbl/COBIL00C.cbl` L239, and arriving here arms nothing because the field is empty.
             * Assumptions: the control is DISABLED until a payment is on offer, which is the browser's
             * reconstruction of a field the reference simply ignored -- L208's `IF NOT ERR-FLG-ON` and
             * the `CONF-PAY-YES` test at L210 are reached only after a read established a positive
             * balance, so a `'Y'` typed before that read had no effect on the terminal either.
             * Trade-offs: `inputMode` is left at its default rather than set to `numeric`, because the
             * two values this field admits are letters. The account entry above sets it because that
             * field admits digits only.
             */}
            <Input
              id={CONFIRMATION_CONTROL_ID}
              ref={confirmInputRef}
              value={confirmation}
              maxLength={BILL_PAY_FIELD_WIDTHS.confirmation}
              disabled={!payable || busy}
              style={confirmationFieldStyle}
              data-testid={CONFIRMATION_CONTROL_TEST_ID}
              {...busyProps(busyControl === 'confirm')}
              {...fieldAriaProps(CONFIRMATION_CONTROL_ID, {
                invalid: confirmationRefusal !== undefined,
                hasError: confirmationRefusal !== undefined,
                hasHint: true,
              })}
              onChange={
                /**
                 * Records the answer the operator typed, and nothing else.
                 *
                 * Assumptions: the character is stored UNCHANGED -- not upper-cased, not filtered to
                 * the domain. `app/cbl/COBIL00C.cbl` L174-L175 and L178-L179 accept both cases as two
                 * arms of one branch, so a lower-case answer is honoured rather than corrected; and a
                 * character outside the domain has to survive into state for the `WHEN OTHER` arm at
                 * L185-L190 to have anything to refuse. Filtering here would silently swallow the
                 * mistake instead of naming it, which is the same defect the account entry above had.
                 * @param {ChangeEvent<HTMLInputElement>} event - The control's change event.
                 * @returns {void} Nothing; the typed answer becomes the screen's state.
                 */
                (event: ChangeEvent<HTMLInputElement>): void => {
                  setConfirmation(event.target.value);
                  /*
                   * WHY : Assumptions: retyping clears the refusal on THIS field only, so the sentence
                   *       under the control disappears as soon as the operator starts correcting it,
                   *       exactly as the account entry above clears its own. The row-23 line is left
                   *       standing until a turn replaces it, because the reference repaints that line
                   *       only when it sends the map.
                   */
                  setFieldErrors(withoutConfirmationRefusal);
                }
              }
            />
            <Typography.Text id={fieldHintId(CONFIRMATION_CONTROL_ID)} style={domainHintStyle}>
              {BILL_PAY_CONFIRM_DOMAIN_HINT}
            </Typography.Text>
          </Flex>
        </Form.Item>
      </Form>
    </Flex>
  );
}

/*
 * WHY : Assumptions: this module publishes the component under BOTH keys, and each has a caller. The
 *       NAMED export is the shape `ui/src/router.tsx` consumes for a screen it loads through the adapter
 *       form, and the DEFAULT export is the shape a bare `lazy(() => import(...))` accepts. Publishing one
 *       alias of one binding satisfies both without creating the re-export barrel the screen conventions
 *       forbid, and there is only ever one component to keep in step because the second key is the same
 *       binding rather than a second declaration.
 */
export default BillPayScreen;
