/**
 * @file The bill payment screen, migrated from `app/cbl/COBIL00C.cbl` and its mapset
 * `app/bms/COBIL00.bms` (map `COBIL0A`, 24 `DFHMDF` fields -- the smallest of the 21 online screens),
 * reached at `/billpay`.
 *
 * Purpose
 * -------
 * Render the reference screen's two-turn workflow: look an account up and report the balance it owes,
 * then pay that balance in full behind an explicit confirmation. It replaces CICS transaction CB00,
 * which `app/csd/CARDDEMO.CSD` L337-L338 binds to that program, and it publishes the mapset's painted
 * literals, its declared field widths and its function-key legend so the screen tests assert against
 * one transcription rather than their own.
 *
 * What this screen owns, and what it does not
 * -------------------------------------------
 * Assumptions: rows 4 to 22 of the map are this screen's, and rows 1 to 3, 23 and 24 are the frame's.
 * `ui/src/layout/AppShell.tsx` paints the title band, the row-23 message line and the row-24 legend
 * from a delegated slot, and its own documentation states that no screen composes a `ScreenHeader`, a
 * row-23 `MessageBand` or a `PfKeyBar` of its own. This module therefore renders none of the three and
 * publishes them instead -- see the `useShellSlot` call in {@link BillPayScreen}.
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

import { Button, Divider, Flex, Form, Input, Popconfirm, Space, Typography, theme } from 'antd';
import type { InputRef } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChangeEvent, CSSProperties, ReactElement } from 'react';
// Assumptions: routing comes from `react-router` and never from `react-router-dom`. The companion
// package has no release at major version 8 at all -- its newest is a thin shim depending on
// `react-router@7` -- so importing the more idiomatic-looking specifier would silently pin routing a
// major version behind the one `ui/package.json` declares. `ui/eslint.config.js` lists it under
// `no-restricted-imports`, so the choice fails the build rather than resting on convention.
import { useLocation, useNavigate } from 'react-router';

import { isApiRequestError } from '../../api/client';
import { payAccountBalanceInFull } from '../../api/transactions';
import type { BillPaymentPreview } from '../../api/transactions';
import type { ApiError, FieldValidationState } from '../../api/types';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import { fieldAriaProps, fieldErrorHelp } from '../../layout/fieldHelp';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap, PfKeyRejection } from '../../layout/usePfKeys';
import {
  INVALID_KEY_PRESSED,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  formatMessageTemplate,
} from '../../messages/messages';
import {
  MAIN_MENU_ROUTE,
  inApplicationRoute,
  navigateSafely,
  screenTransitionState,
} from '../../routes/navigation';
import { BMS_TEXT_COLOR_TOKENS, SPACING_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';

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

/**
 * Screen title, verbatim from `app/bms/COBIL00.bms` L75-L79.
 *
 * Assumptions: the mapset paints this as its own `LENGTH=12` field with `ATTRB=(ASKIP,BRT)` and
 * `COLOR=NEUTRAL`, and the twelve characters here are that literal exactly. It is declared in this
 * module rather than read from the catalog because the catalog carries no entry for it: the nearest
 * value it holds is the main menu's option name, `'Bill Payment                       '`, padded to
 * the 35-character option width, and trimming that to reach this literal would make one field's text
 * depend on another field's padding.
 *
 * Alternatives Considered: `PROGRAM_MESSAGES.COBIL00C.BILL_PAYMENT`, which reads like the obvious
 * source and is the wrong one. That entry is `'BILL PAYMENT'` in upper case and it is the merchant
 * NAME the service writes into the transaction row at `app/cbl/COBIL00C.cbl` L227 -- a data value on a
 * record, not a caption on a screen. Using it would paint the title in the wrong case and would tie
 * this heading to a ledger field.
 */
export const BILL_PAY_TITLE = 'Bill Payment';

/**
 * The three literals the map paints beside this screen's fields, byte for byte.
 *
 * Assumptions: the trailing spaces are part of the values and are not incidental. `app/bms/COBIL00.bms`
 * declares each field's `LENGTH` and each `INITIAL` fills it exactly -- 14, 25 and 53 characters
 * respectively -- so the balance label ends with a space and the confirmation prompt ends with a space,
 * because on the terminal the value followed immediately in the next column. A reformat that trims
 * them, or an editor action that strips trailing whitespace inside these quotes, changes what the
 * screen paints while still reading as correct English.
 *
 * Assumptions: the confirmation prompt ends the first sentence with a FULL STOP and not a question
 * mark -- `'... your balance now. Please confirm: '` -- which reads like a transcription slip and is
 * not one. It is what `app/bms/COBIL00.bms` L109-L114 holds, and rule T8 carries user-visible text
 * across character for character, so it is never corrected.
 */
export const BILL_PAY_FIELD_LABELS = {
  /** `app/bms/COBIL00.bms` L80-L84, `LENGTH=14`, the label beside `ACTIDIN`. */
  accountId: 'Enter Acct ID:',
  /** `app/bms/COBIL00.bms` L98-L102, `LENGTH=25`, the label beside `CURBAL`. Trailing space included. */
  currentBalance: 'Your current balance is: ',
  /** `app/bms/COBIL00.bms` L109-L114, `LENGTH=53`, the prompt beside `CONFIRM`. Trailing space included. */
  confirmPrompt: 'Do you want to pay your balance now. Please confirm: ',
} as const;

/**
 * The domain hint the map paints after the confirmation field, from `app/bms/COBIL00.bms` L122-L126.
 *
 * Assumptions: this five-character literal survives even though the single-character field it
 * annotated does not, and it survives because it still names the two answers. The confirmation dialogue
 * labels its controls `Y` and `N`, so the hint describes the choice an operator is actually offered
 * rather than describing a text field that is gone.
 */
export const BILL_PAY_CONFIRM_DOMAIN_HINT = '(Y/N)';

/**
 * Declared widths of the two data fields this screen renders, from the mapset and its symbolic map.
 *
 * Assumptions: these are per-mapset contracts and they must never be unified with the same amounts'
 * widths on other screens. The balance is FOURTEEN here because `app/cbl/COBIL00C.cbl` L56 declares
 * `WS-CURR-BAL PIC +9999999999.99` -- one sign position, ten integer digits, the point and two decimal
 * positions -- and L193-L194 move the record field through it into `CURBALI PIC X(14)`. The account
 * screens declare fifteen for the same amounts because their picture is `+ZZZ,ZZZ,ZZZ.99`, which
 * `ui/src/format/money.ts` implements and documents as `MONEY_MASK_WIDTH`, and the transaction screens
 * declare twelve. Three pictures, three widths, one underlying record field.
 */
export const BILL_PAY_FIELD_WIDTHS = {
  /** `ACTIDIN LENGTH=11` at `app/bms/COBIL00.bms` L85-L89; `ACTIDINI PIC X(11)`. */
  accountId: 11,
  /** `CURBAL LENGTH=14` at `app/bms/COBIL00.bms` L103-L106; `CURBALI PIC X(14)`. */
  currentBalance: 14,
} as const;

/**
 * Integer digit positions in this screen's balance picture, `+9999999999.99`.
 *
 * Assumptions: the picture uses `9` and not `Z`, so these positions are NOT zero-suppressed -- a `9`
 * prints its digit whatever that digit is. That single character is the whole difference between this
 * screen's presentation and the account screens', and it is why their mask cannot be reused here.
 */
const BALANCE_INTEGER_POSITIONS = 10;

/**
 * Function-key legend labels, split from the single row-24 field at `app/bms/COBIL00.bms` L131-L135.
 *
 * Assumptions: that field is `LENGTH=33` and holds `'ENTER=Continue  F3=Back  F4=Clear'` -- three
 * captions separated by two spaces each, 14 + 2 + 7 + 2 + 8 = 33. It advertises three keys and no
 * others, and `app/cbl/COBIL00C.cbl` L125-L142 dispatches exactly those three from `EIBAID` before
 * answering everything else with the shared invalid-key sentence. So this screen has no PF5, PF7, PF8
 * or PF12 binding at all, and the legend is the evidence rather than an inference.
 *
 * Assumptions: only PF4's caption is imported. `UNIFORM_PF_KEY_LABELS` holds the captions whose text is
 * identical on every mapset that paints them and `F4=Clear` is one of those, so importing it keeps this
 * module from becoming a second place that spelling could drift. ENTER reads `Continue` here where
 * other mapsets paint `Process`, and PF3 reads `Back` where nine others paint `Exit`, so both are
 * declared locally.
 */
const BILL_PAY_KEY_LABELS = {
  ENTER: 'ENTER=Continue',
  PFK03: 'F3=Back',
  PFK04: UNIFORM_PF_KEY_LABELS.PFK04,
} as const;

/**
 * The answer the confirmation dialogue sends when its primary control is used.
 *
 * Assumptions: a single `'Y'`, because the field it replaces is `CONFIRM LENGTH=1` at
 * `app/bms/COBIL00.bms` L115-L119 and `app/cbl/COBIL00C.cbl` L174-L176 accepts `'Y'` or `'y'` there.
 * Sending the upper-case form keeps the request inside the set the service's own confirmation
 * validator admits.
 */
const CONFIRMING_ANSWER = 'Y';

/**
 * The answer the confirmation dialogue sends when its secondary control is used.
 *
 * Assumptions: a single `'N'`, matching the declining arm at `app/cbl/COBIL00C.cbl` L178-L179, which
 * accepts `'N'` or `'n'`. It is used as the control's caption rather than as a request member, because
 * the declining turn is answered without a request at all -- see the dialogue's cancel handler.
 */
const DECLINING_ANSWER = 'N';

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
 * Matches every character an account entry may not contain, for the paste filter below.
 *
 * Assumptions: the complement of the decimal digits, because the entry feeds `ACCT-ID PIC 9(11)` and
 * COBOL's `IS NUMERIC` on the receiving field admits digits and nothing else -- no sign, no separator and
 * no space.
 */
const NON_DIGIT_PATTERN = /[^0-9]/gu;

/**
 * Shape the wire guarantees for this screen's balance: optional sign, digits, point, two decimals.
 *
 * Assumptions: the service publishes money through `Money.toPlainString()`, so the decimal point and
 * both decimal positions are always present and no grouping separator ever is.
 */
const WIRE_BALANCE_PATTERN = /^(-?)([0-9]+)\.([0-9]{2})$/u;

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
 * Renders a wire balance through this screen's own `+9999999999.99` edit mask.
 *
 * Assumptions: the mask is reproduced here rather than imported, and the reason is that
 * `ui/src/format/money.ts` implements a DIFFERENT picture. Its `applyMoneyEditMask` renders
 * `+ZZZ,ZZZ,ZZZ.99` -- nine zero-suppressed positions with group separators, fifteen characters wide --
 * because that is what the account mapsets declare. This screen's picture is `WS-CURR-BAL
 * PIC +9999999999.99` at `app/cbl/COBIL00C.cbl` L56: ten positions, `9` rather than `Z` so nothing is
 * suppressed, no separators, fourteen characters wide. Calling the shared helper would paint a
 * comma-grouped, zero-suppressed, fifteen-character value where the terminal painted a zero-padded
 * fourteen-character one, which is precisely the unification the per-mapset widths exist to prevent.
 *
 * Alternatives Considered: widening the shared helper with a picture parameter so one function served
 * both screens. Rejected because the two pictures differ in three independent respects -- suppression,
 * grouping and position count -- so the parameter list would carry the whole picture and the shared
 * function would become a picture interpreter serving two callers. The transformation is four
 * operations on a captured string; keeping it beside the field it formats is smaller than the
 * abstraction that would hide it.
 *
 * Trade-offs: the operation is purely lexical -- it reads captured digit groups and pads one of them --
 * so it parses nothing and cannot lose a digit to binary floating point. That is the whole reason the
 * balance is carried as text; a helper that formatted by converting to a number first would discard the
 * exactness on the one figure the operator is about to pay.
 *
 * Trade-offs: a value needing more than ten integer positions is rendered in full rather than
 * truncated, matching the registered divergence `D-MONEY-MASK-NO-TRUNCATION` that
 * `ui/src/format/money.ts` records for the same situation. A decimal-aligned COBOL `MOVE` would discard
 * the high-order digit and understate the balance with nothing on screen to say so, and a display
 * defect is not worth transcribing faithfully. The service bounds the value at `9999999999.99`, which
 * is exactly ten digits, so the widening path is unreachable in practice.
 * @param {string} wireBalance - Money as the service published it: optional sign, integer digits, a
 *   decimal point and exactly two decimal digits.
 * @returns {string} The balance rendered through the mask, or the input unchanged when it does not
 *   match the wire contract -- returning the text keeps the amount truthful in the one case where its
 *   presentation cannot be, which is the same discipline the shared money helper follows.
 */
export function applyBalanceEditMask(wireBalance: string): string {
  const parsed = WIRE_BALANCE_PATTERN.exec(wireBalance);
  if (parsed === null) {
    return wireBalance;
  }

  /*
   * WHY : Assumptions: each group is destructured with an empty-string default that is unreachable
   *       rather than meaningful. All three groups are mandatory in the pattern, so a successful match
   *       populates every one; the defaults exist only because `ui/tsconfig.json` types a capture group
   *       as possibly absent, and a default is preferred to a non-null assertion because it cannot
   *       throw if the pattern is ever edited.
   */
  const [, sign = '', integerDigits = '', decimalDigits = ''] = parsed;

  /*
   * WHY : Assumptions: the sign position always emits a character, because the picture's leading `+` is
   *       a FIXED position rather than a conditional one -- it prints `+` for a non-negative value and
   *       `-` for a negative one. A negative zero cannot arrive, because the pattern captures the sign
   *       separately from the digits and a scale-two zero is published as `0.00`.
   */
  const signCharacter = sign === '-' ? '-' : '+';

  /*
   * WHY : Assumptions: the integer field is zero-PADDED and never blanked, which is the one behaviour
   *       that separates this mask from the account screens'. A `9` position prints its digit
   *       unconditionally, so a balance of `1234.56` paints as `+0000001234.56` and a zero balance
   *       paints as `+0000000000.00`. Padding with spaces here, as a `Z` picture would, would silently
   *       adopt the other screens' presentation.
   */
  const integerField =
    integerDigits.length >= BALANCE_INTEGER_POSITIONS
      ? integerDigits
      : integerDigits.padStart(BALANCE_INTEGER_POSITIONS, '0');

  return `${signCharacter}${integerField}.${decimalDigits}`;
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

/**
 * Reduces a proposed account entry to the characters the reference field could have received.
 *
 * Trade-offs: the value is filtered on the way in as well as being bounded by `maxLength` on the
 * control. The declared maximum is what a keyboard obeys, but it does not constrain a PASTE of mixed
 * characters, and the reference field could not receive one at all -- `ACTIDIN` is an eleven-position
 * field feeding `ACCT-ID PIC 9(11)`. Filtering is the browser equivalent of that field behaviour;
 * without it a pasted value would reach the refusal path and report a digit problem the operator did not
 * create.
 * @param {string} raw - The value the control is proposing, as typed or pasted.
 * @returns {string} The value reduced to at most eleven decimal digits.
 */
function acceptAccountIdKeystrokes(raw: string): string {
  return raw.replace(NON_DIGIT_PATTERN, '').slice(0, BILL_PAY_FIELD_WIDTHS.accountId);
}

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
  const [busy, setBusy] = useState(false);

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
  const confirmButtonRef = useRef<HTMLButtonElement>(null);

  /**
   * Whether a turn is already in flight, held in a ref so two events in one batch cannot both pass.
   */
  const inFlight = useRef(false);

  /*
   * WHY : Refactoring Rationale: a requested cursor move is RECORDED as state and applied by the effect
   *       below, where it used to be performed inline at the point of decision. Measured in a browser
   *       rather than reasoned about: a `focus()` issued inline landed nowhere on every turn that had
   *       reached the network, leaving `document.activeElement` on `<body>`. The cause is a collision
   *       between two things this screen does deliberately. The account entry and the confirmation
   *       control are both `disabled` while a turn is in flight -- the keyboard lock a 3270 got from the
   *       hardware -- and the handler that settles a turn clears that flag and names the cursor's
   *       destination in the same batch. React commits both together, so an inline call ran while the
   *       target was still disabled, and a disabled element refuses focus silently, with no error to
   *       notice. Worse, disabling the element the operator was typing into makes the browser blur it
   *       first, so the inline form did not merely fail to move the cursor: it lost it.
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
     * destination recorded for a control that has since been withdrawn -- the confirmation button on a
     * turn that stopped offering payment -- cannot be replayed against a later render.
     * @returns {void} Nothing; completion is the focused control and the discharged request.
     */
    function applyPendingFocus(): void {
      if (pendingFocus === null) {
        return;
      }

      if (pendingFocus === 'confirm') {
        confirmButtonRef.current?.focus();
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
     * Records the cursor move onto the confirmation control, which is `MOVE -1 TO CONFIRML`.
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

    if (turn.clearEntry) {
      setAccountId('');
    }

    /*
     * WHY : Assumptions: the cursor lands on the confirmation control only when payment is being
     *       offered, and on the account entry otherwise. That is the reference's own split: L239 moves
     *       the cursor to `CONFIRML` alongside the confirmation prompt, while the nothing-to-pay path at
     *       L203 and every refusal path move it to `ACTIDINL`.
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
     */
    setPayable(false);
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
   * Runs one turn of the screen, which is `PROCESS-ENTER-KEY` at `app/cbl/COBIL00C.cbl` L154-L244.
   *
   * Assumptions: ONE function serves both the report and the payment, because the reference has one
   * paragraph serving both. `PROCESS-ENTER-KEY` validates the entry, evaluates the confirmation field and
   * then either reports the balance or writes the payment, so the confirmation value is the only
   * difference between the two turns and it is this function's only parameter.
   *
   * Assumptions: the blank-entry refusal is answered LOCALLY and reaches no network, because the reference
   * answers it before it reaches a file. L159-L164 tests the entry against spaces and low-values FIRST --
   * ahead of the confirmation evaluate at L173 -- and sends the map immediately, so the check guards the
   * payment turn as well as the report. The service validates the same condition independently, so this
   * is a duplicate by design rather than the only guard.
   *
   * Assumptions: the payment is ONE request carrying the confirmation, not a second write layered on the
   * report. The reference performs the cross-reference read, the identifier generation, the ledger write
   * and the balance reduction inside a single CICS task at L210-L235, and the service performs the
   * equivalent inside one database transaction. Splitting it client-side would make a partially applied
   * payment observable, a state the baseline never exhibits.
   * @param {string | null} confirmation - `'Y'` to pay the reported balance, or `null` to report it
   *   without writing anything.
   * @returns {void} Nothing; every outcome, including every failure, is painted onto the screen.
   */
  function runTurn(confirmation: string | null): void {
    /*
     * WHY : Assumptions: a turn arriving while one is in flight is DROPPED, and the guard matters more
     *       here than on a read-only screen. A 3270 keyboard locks until the region replies, so the
     *       reference is serialised by the hardware and needs no such guard; without one, a doubled Enter
     *       or a double-clicked confirmation could submit the same payment twice and move the money twice
     *       where the operator asked once. A ref rather than the busy flag is what makes it effective:
     *       state updates are batched, so two events in one batch would both observe the old flag.
     */
    if (inFlight.current) {
      return;
    }

    if (accountId === '') {
      setMessage(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY);
      setSeverity('error');
      setBalance(null);
      setPayable(false);
      /*
       * WHY : Assumptions: the entry is marked BLANK rather than merely wrong, because the contract
       *       distinguishes the two states and the reference's own condition is emptiness. The sentence is
       *       the catalog's, so the field marker and the message line carry one wording between them.
       */
      setFieldErrors([
        {
          field: ACCOUNT_ID_FIELD,
          state: 'BLANK',
          message: BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY,
        },
      ]);
      focusAccountId();
      return;
    }

    /*
     * WHY : Assumptions: the confirmation member is OMITTED on the reporting turn rather than set to
     *       `undefined`. The contract declares it optional and the service reads its absence as the
     *       reference reads a blank `CONFIRMI` at L182-L184 -- read the account and report the balance
     *       without writing. `ui/tsconfig.json` sets `exactOptionalPropertyTypes`, under which an
     *       explicit `undefined` and an absent member are different types, and only absence matches.
     */
    const request = confirmation === null ? { accountId } : { accountId, confirmation };

    inFlight.current = true;
    setBusy(true);

    /*
     * WHY : Assumptions: the promise is consumed with a two-argument `then` rather than being awaited in
     *       an async handler. `@typescript-eslint/no-floating-promises` requires a rejection handler at
     *       the call site, and an event handler cannot be `async` without either returning a promise the
     *       caller drops or wrapping every call in a discard -- so the rejection path is given a named
     *       function here, which is also where the reference's own refusal wording is applied.
     */
    payAccountBalanceInFull(request).then(
      /**
       * Paints a settled turn.
       * @param {Awaited<ReturnType<typeof payAccountBalanceInFull>>} outcome - Which outcome the service
       *   reported: a written payment, or the balance a confirmed request would pay.
       * @returns {void} Nothing; the painted state is the screen's own.
       */
      (outcome): void => {
        inFlight.current = false;
        setBusy(false);

        if (outcome.outcome === 'PAID') {
          applyPaid(outcome.payment.transactionId);
          return;
        }

        /*
         * WHY : Assumptions: a CONFIRMED request answered with a preview is painted as a preview rather
         *       than reported as a fault. The service answers 200 with a preview whenever it declines to
         *       write, and a balance that turned non-positive between the two turns is the realistic
         *       case, because the reporting turn deliberately takes no lock. The reference behaves the
         *       same way: L197-L206 re-tests the balance on the confirming turn before it writes.
         */
        applyTurn(previewTurn(outcome.preview));
      },
      /**
       * Paints a failed turn in the reference's own words.
       * @param {unknown} failure - Whatever the request rejected with.
       * @returns {void} Nothing; the reported state is the screen's own.
       */
      (failure: unknown): void => {
        inFlight.current = false;
        setBusy(false);
        reportFailure(failure, confirmation !== null);
      },
    );
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
   */
  const pfKeyHandlers: PfKeyHandlerMap = {
    ENTER: {
      label: BILL_PAY_KEY_LABELS.ENTER,
      disabled: busy,
      onInvoke:
        /**
         * Runs the reporting turn, which is the reference's `DFHENTER` arm at L126-L127.
         * @returns {void} Nothing; the turn paints itself and never rejects to this caller.
         */
        (): void => {
          runTurn(null);
        },
    },
    PFK03: {
      label: BILL_PAY_KEY_LABELS.PFK03,
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
      label: BILL_PAY_KEY_LABELS.PFK04,
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
         *       one only ever arises here because a turn is in flight and the ENTER binding is held
         *       shut -- and that is the 3270 keyboard lock, which the hardware enforced by refusing the
         *       keystroke before the program ever saw it, so no sentence was composed and none was
         *       painted. Answering both alike would invent a message on a keystroke the reference
         *       discards. The sibling main-menu screen draws the same distinction for the same reason.
         */
        if (rejection.reason !== 'unmapped') {
          return;
        }

        setMessage(INVALID_KEY_PRESSED);
        setSeverity('error');
      },
  });

  /*
   * WHY : Assumptions: this screen composes no title band, no row-23 message line and no row-24 legend of
   *       its own, and publishes all three here instead. `ui/src/layout/AppShell.tsx` is the frame that
   *       renders them, and its own documentation states that every screen delegates and none
   *       self-composes; a screen that also rendered them would produce two live regions announcing one
   *       message and a duplicate legend. The mechanism is the module-scoped store that module already
   *       uses for the same job, reached through its `useShellSlot` hook -- not a context provider, which
   *       that file records as deliberately absent from this tree.
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
    message: { text: message, severity, mapset: BILL_PAY_MAPSET },
    pfKeys: { keys: bindings, onInvoke: invoke },
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
  const hintStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] };
  const ruleStyle: CSSProperties = { borderColor: cssVar[BMS_TEXT_COLOR_TOKENS.YELLOW] };
  const balanceStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.BLUE],
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

  const accountRefusal = fieldErrors.find(
    /**
     * Selects the refusal attributed to the account entry.
     * @param {BillPayFieldError} refusal - One refusal from the settled turn.
     * @returns {boolean} True when the refusal names this screen's only editable field.
     */
    (refusal) => refusal.field === ACCOUNT_ID_FIELD,
  );

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
            {...fieldAriaProps(ACCOUNT_ID_CONTROL_ID, {
              invalid: accountRefusal !== undefined,
              hasError: accountRefusal !== undefined,
              hasHint: false,
            })}
            onChange={
              /**
               * Accepts the digits of a proposed entry and discards the rest of the turn's state.
               * @param {ChangeEvent<HTMLInputElement>} event - The control's change event.
               * @returns {void} Nothing; the accepted value becomes the screen's state.
               */
              (event: ChangeEvent<HTMLInputElement>): void => {
                const accepted = acceptAccountIdKeystrokes(event.target.value);
                setAccountId(accepted);
                /*
                 * WHY : Assumptions: editing the entry discards the reported balance and withdraws the
                 *       payment control, because the balance on screen describes the account that WAS
                 *       looked up. Leaving it in place would let an operator confirm a payment against a
                 *       figure belonging to a different account than the one now typed, which is a
                 *       money-moving confusion the reference cannot produce -- its balance field is
                 *       repainted by the same turn that reads the entry.
                 */
                setBalance(null);
                setPayable(false);
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
          {balance === null ? '' : applyBalanceEditMask(balance)}
        </Typography.Text>
      </Flex>

      <Flex align="center" gap={cssVar[SPACING_TOKENS.sectionGapCompact]} wrap>
        <Typography.Text style={promptStyle}>{BILL_PAY_FIELD_LABELS.confirmPrompt}</Typography.Text>

        {/*
         * WHY : Refactoring Rationale: the single-character `CONFIRM` field is replaced by a confirmation
         *       dialogue, and the field only existed because a 3270 terminal had no modal. `CONFIRM` is
         *       `LENGTH=1` at `app/bms/COBIL00.bms` L115-L119 and the program re-reads the whole map to
         *       find out what was typed into it; a dialogue asks the question directly and cannot receive
         *       an answer outside the two it offers.
         * WHY : Assumptions: `'Invalid value. Valid values are (Y/N)...'` at `app/cbl/COBIL00C.cbl` L187
         *       therefore becomes UNREACHABLE from this screen, and that is a consequence of the dialogue
         *       rather than an omission. The two controls send `'Y'` and `'N'` and nothing else can be
         *       submitted, so the branch that rejected a third character has no way to fire. The sentence
         *       stays in the shared catalog because a second program still emits it and because the
         *       service still validates the member independently, and `billPayFailure` renders it verbatim
         *       if it ever arrives.
         * WHY : Trade-offs: the primary control is marked as a destructive action. Paying a balance in
         *       full moves real money and cannot be undone from this screen, so it is treated as
         *       destructive of value even though it destroys no record. The alternative reading is that
         *       the measured source colours are `COLOR=TURQUOISE` on the prompt and `COLOR=GREEN` on the
         *       field, with no warning hue anywhere near either; that is accepted as the weaker argument
         *       here because the 3270 convention for "are you sure" was the re-key itself, which has no
         *       colour, and the dialogue is what now carries that weight.
         * WHY : Assumptions: the two controls are LABELLED with the same two characters the mapset's own
         *       `(Y/N)` hint names, so the answers an operator gives are the answers the reference
         *       documented, and the retained hint beside them still describes the choice.
         */}
        {/*
         * WHY : Refactoring Rationale: the dialogue takes the cursor onto its CONFIRMING control when it
         *       opens, and is destroyed when it closes. Neither was set before, and measuring the result
         *       in a browser is what settled both. antd renders the panel into a portal at the end of
         *       `<body>`, so an operator who opened it from the keyboard had to press Tab FIVE times to
         *       reach the answer -- traversing all three row-24 legend controls on the way -- and the
         *       first control inside the panel is the DECLINING one, so an Enter pressed a single stop
         *       early cleared the screen instead of paying. That is the opposite of what an operator who
         *       opened a payment confirmation asked for, and it is nothing like the reference, where
         *       L239's `MOVE -1 TO CONFIRML` puts the cursor straight onto the answer field.
         * WHY : Assumptions: the two-act shape of the reference is preserved exactly, not shortened.
         *       Activating this screen's `Y` is the analogue of typing `'Y'` into `CONFIRMI`, and
         *       answering in the dialogue is the analogue of the Enter that follows it at L173-L177 --
         *       so the operator still performs two deliberate acts after the prompt, and the count is
         *       unchanged from the terminal.
         * WHY : Trade-offs: the cursor therefore lands on a control that moves money, which is the cost
         *       of this choice and is accepted on two grounds. The declining control stays FIRST in
         *       document order, so it is one Shift+Tab away and remains the leftmost target for a
         *       pointer; and the alternative -- taking the cursor to the declining control instead --
         *       would make Enter clear the screen on a turn opened in order to pay, which is a worse
         *       failure than the one it avoids because it discards work silently.
         * WHY : Assumptions: `destroyOnHidden` is required for the cursor move to happen more than once.
         *       `ui/node_modules/antd/es/_util/ActionButton.js` focuses from an effect keyed on the flag
         *       alone, so it fires on mount and never again; without destruction the panel stays mounted
         *       after the first close and every later opening would leave the cursor outside it.
         */}
        <Popconfirm
          title={BILL_PAY_FIELD_LABELS.confirmPrompt}
          okText={CONFIRMING_ANSWER}
          cancelText={DECLINING_ANSWER}
          okType="danger"
          okButtonProps={{ autoFocus: true }}
          destroyOnHidden
          disabled={!payable || busy}
          onOpenChange={
            /**
             * Returns the cursor to the answer control when the dialogue is DISMISSED rather than
             * answered.
             *
             * Assumptions: a dismissal leaves the confirmation prompt standing, and while that prompt
             * stands the cursor belongs on the answer -- which is `MOVE -1 TO CONFIRML` at
             * `app/cbl/COBIL00C.cbl` L239, the placement the reference issues alongside the prompt
             * itself. So this restores the reference's own resting position rather than inventing one.
             *
             * Assumptions: this is the KEYBOARD dismissal path in practice. It is registered for every
             * close, but a close driven by a pointer carries the browser's own focus action for the same
             * gesture, and that action is applied afterwards and supersedes this one. That outcome is
             * deliberate and is documented at the guard below.
             * @param {boolean} nextOpen - Whether the dialogue is now open.
             * @returns {void} Nothing; completion is the recorded cursor move, applied after the commit.
             */
            (nextOpen: boolean): void => {
              /*
               * WHY : Refactoring Rationale: this handler exists because taking the cursor INTO the
               *       dialogue created an exit that did not previously exist. Measured in a browser: once
               *       the panel holds focus, closing it with Escape destroys the focused node and leaves
               *       `document.activeElement` on `<body>`, from which four Tab presses are needed to get
               *       back to the answer and an Enter would re-run the lookup instead of re-opening the
               *       dialogue. Before the cursor was moved in, Escape could not lose it, so this is a
               *       path the focus change introduced and therefore one it has to close.
               * WHY : Assumptions: the two ANSWERED exits are excluded, and the guard reads state rather
               *       than a flag because antd's own ordering makes that sufficient and self-evident.
               *       Confirming runs `onConfirm` BEFORE `close()`, so `runTurn` has already set the
               *       in-flight ref by the time this fires and the first clause skips -- which is what
               *       keeps the cursor from flicking onto the answer control on the very turn that is
               *       clearing the screen. Declining runs this handler BEFORE `onCancel`, so the recorded
               *       destination here is superseded within the same batch by the account-entry
               *       destination that `INITIALIZE-ALL-FIELDS` records, and last write wins.
               * WHY : Trade-offs: `payable` is re-tested even though the dialogue is `disabled` whenever
               *       it is false and antd returns early on a disabled trigger. Retested anyway because
               *       the cost is one boolean read and the failure it prevents is silent: were the panel
               *       ever dismissed with payment withdrawn, the cursor would be sent to a control that
               *       is disabled, focus would be refused, and it would rest on `<body>` again.
               * WHY : Alternatives Considered: making this restore WIN against a pointer dismissal, by
               *       cancelling the default focus action or re-asserting the destination after the
               *       pointer's own action had landed. REJECTED, and measurement is what rejected it. On a
               *       pointer dismissal the focus sequence is: this handler moves the cursor to the answer
               *       control, and about a millisecond later the browser's action for the same mousedown
               *       moves it to whatever was clicked. Where the operator clicks the ACCOUNT ENTRY -- the
               *       obvious way to correct a mistyped identifier -- the browser's action is the one that
               *       is right: the caret lands in the entry and typing goes into it, which was verified
               *       character by character. Making this handler win there would pull the caret out of
               *       the field the operator had just clicked in order to edit, and the same reasoning
               *       applies to the frame's sign-off control, which was verified to activate on its first
               *       click. Losing this race is therefore the CORRECT outcome for a pointer, and the
               *       cosmetic cost -- a click on non-focusable frame chrome resting the cursor on the
               *       container or on `<body>` -- was reproduced with no dialogue present at all, on this
               *       screen and on the main menu alike. It is the browser's own delegation over
               *       non-focusable content, and belongs to the frame that owns that markup rather than
               *       to this screen. Escape has no competing default action, which is exactly why the
               *       keyboard path lands.
               */
              if (nextOpen || !payable || inFlight.current) {
                return;
              }

              focusConfirm();
            }
          }
          onConfirm={
            /**
             * Submits the confirming answer, which pays the balance in full.
             * @returns {void} Nothing; the turn paints itself and never rejects to this caller.
             */
            (): void => {
              runTurn(CONFIRMING_ANSWER);
            }
          }
          onCancel={
            /**
             * Answers a declining response by clearing the screen and saying nothing.
             *
             * Assumptions: the decline is handled LOCALLY and reaches no network, because the reference
             * reaches no file either -- `app/cbl/COBIL00C.cbl` L178-L181 performs `CLEAR-CURRENT-SCREEN`
             * and sets the error flag, which suppresses every later sentence. The service agrees: its
             * declined path performs zero account interactions and answers with a cleared preview
             * carrying no balance and no message. Sending the answer would spend a request to be told
             * what is already known, and would let an unrelated transport failure paint an error on a
             * turn the reference answers silently.
             * @returns {void} Nothing; the cleared state is the screen's own.
             */
            (): void => {
              clearScreen();
            }
          }
        >
          {/*
           * Assumptions: the control is a real button carrying the dialogue's trigger, so it is reachable
           * by keyboard and announced as an action. It is also the element `MOVE -1 TO CONFIRML` maps onto,
           * which is why a ref is attached to it.
           * Trade-offs: the in-progress affordance is ADDITIVE and has no counterpart in the reference at
           * all -- a 3270 keyboard locks while the region works, so the terminal expressed "busy" by
           * refusing input rather than by drawing anything. Gap G5 in AAP section 0.3.4 records that
           * class of addition. It is carried on this control rather than as a separate overlay so the
           * indication sits on the action it describes, and the same flag disables the account entry and
           * the ENTER binding, which together reproduce the keyboard lock the hardware provided.
           */}
          <Space size={cssVar[SPACING_TOKENS.sectionGapCompact]}>
            <Button
              ref={confirmButtonRef}
              type="primary"
              danger
              disabled={!payable}
              loading={busy}
              data-testid="billpay-confirm"
            >
              {CONFIRMING_ANSWER}
            </Button>
            <Typography.Text style={hintStyle}>{BILL_PAY_CONFIRM_DOMAIN_HINT}</Typography.Text>
          </Space>
        </Popconfirm>
      </Flex>
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
