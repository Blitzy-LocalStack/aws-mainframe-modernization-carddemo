/**
 * @file The card update screen, migrated from `app/cbl/COCRDUPC.cbl` and its mapset
 * `app/bms/COCRDUP.bms` (34 `DFHMDF` fields), reached at the card edit route.
 *
 * Purpose
 * -------
 * Render the reference screen's fetch-then-replace workflow: read the current card, let an operator
 * edit the fields the reference permits, and submit the replacement behind an explicit confirmation.
 * It replaces CICS transaction CCUP, which `app/csd/CARDDEMO.CSD` L368-L369 binds to that program,
 * and it publishes the field labels, the active-status options and the guidance strings the screen
 * tests assert against.
 *
 * Concurrency
 * -----------
 * Assumptions: a conflicting concurrent change is reported to the operator rather than silently
 * overwritten. The reference performs this itself across the pseudo-conversational gap by comparing
 * a before-image of the record it read; the migrated service expresses the same guarantee with an
 * optimistic version column and answers HTTP 409, so this screen surfaces the data-changed message
 * and leaves the operator's edits in place to re-submit.
 *
 * Confirmation
 * ------------
 * Assumptions: the save is confirmed before it is sent, which is the browser form of the reference's
 * re-key-to-confirm convention. Losing it would make an accidental keypress a committed write.
 */

import { ConfigProvider, Flex, Form, Input, Modal, Space, Spin, Typography, theme } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { InputRef } from 'antd';
import type { CSSProperties, ReactElement, ReactNode } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';

import { getCard, updateCard } from '../../api/cards';
import type { CardDetail, CardUpdateRequest } from '../../api/cards';
import {
  isApiRequestError,
  isConflictFailure,
  isTransientFailure,
  requireConditionalOn,
  withoutConcurrentDuplicate,
} from '../../api/client';
import type { FieldError } from '../../api/types';
import { useShellSlot } from '../../layout/AppShell';
import type { ShellInformationSlot, ShellSlot } from '../../layout/AppShell';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import {
  BLANK_FIELD_MARKER_CHARACTERS,
  busyAnnouncement,
  busyProps,
  fieldRefusalRendering,
} from '../../layout/fieldHelp';
import type { FieldRefusalRendering } from '../../layout/fieldHelp';
import { copybookFieldWidthStyle } from '../../layout/recordLayout';
import { useServerInstant } from '../../hooks/useServerInstant';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap } from '../../layout/usePfKeys';
import {
  REQUEST_IN_PROGRESS,
  STATUS_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
  fitsDeclaredWidth,
  normaliseForWire,
} from '../../messages/messages';
import type { MapsetName } from '../../messages/messages';
import { cardDetailPath, isCardSelector, requireCardSelector } from '../../routes/cards';
import {
  CARD_LIST_ROUTE,
  inApplicationRoute,
  navigateSafely,
  screenTransitionState,
} from '../../routes/navigation';
import type { ScreenTransitionState } from '../../routes/navigation';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { destructiveFocusTheme } from '../../theme/antdTheme';
import { TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/** Screen-level messages this screen renders, taken verbatim from the catalog keyed by its program. */
const CARD_UPDATE_MESSAGES = STATUS_MESSAGES.COCRDUPC;

/** CICS transaction identifier this screen replaces, as `app/csd/CARDDEMO.CSD` L368-L369 defines it. */
export const CARD_UPDATE_TRANSACTION_ID = 'CCUP';

/** Source program name, rendered in the header band exactly as the 3270 screen did. */
export const CARD_UPDATE_PROGRAM_NAME = 'COCRDUPC';

/** Screen title, verbatim from the mapset's own title field at `app/bms/COCRDUP.bms` L78. */
export const CARD_UPDATE_TITLE = 'Update Credit Card Details';

/*
 * WHY : Assumptions: the mapset is NAMED rather than the band's width being written here, and this
 *       screen is one of only two where the distinction is observable. `ERRMSG LENGTH=80` at
 *       `app/bms/COCRDUP.bms` L154-L157 is wider than the 78 that nineteen of the twenty-one mapsets
 *       declare -- `COCRDSL` is the only other 80 -- so a band that defaulted would paint this screen's
 *       row-23 line two characters narrow. The DISPLAY width and the CONTENT contract are two different
 *       figures and both are preserved: the field is 80 columns, while the sentence it carries is
 *       `WS-RETURN-MSG PIC X(75)` (`app/cbl/COCRDUPC.cbl` L173) -- declared identically as
 *       `CCARD-ERROR-MSG`/`CCARD-RETURN-MSG PIC X(75)` in `app/cpy/CVCRD01Y.cpy` L28-L29. Typing the
 *       constant as `MapsetName` checks the name against the catalog's own measured table, so a typo is
 *       a build failure rather than a silently defaulted width.
 */
export const CARD_UPDATE_MAPSET: MapsetName = 'COCRDUP';

/**
 * Builds the document identifier for one of this screen's controls.
 *
 * Assumptions: the two protected key controls hold no form member, so `Form.Item` cannot generate an
 * identifier for them or associate their label the way it does for a named field -- it derives both
 * from `name`. Without an explicit pairing those two controls carry NO accessible name, which a screen
 * reader announces as an unlabelled text box: the operator would be told the record has two anonymous
 * fields rather than an account number and a card number. Supplying the identifier here and referencing
 * it from the item's `htmlFor` restores the association, which is the arrangement the sibling
 * account-update and account-view screens already use.
 * @param {string} field - Field name, matching the member or label the control stands for.
 * @returns {string} A document-unique identifier namespaced to this screen.
 */
export function cardUpdateFieldDomId(field: string): string {
  return `carddemo-card-update-${field}`;
}

/**
 * Entry widths, each the `LENGTH=` operand of the field it renders in `app/bms/COCRDUP.bms`.
 *
 * Assumptions: these are the mapset's own figures and they agree with the record picture clauses in
 * `app/cpy/CVACT02Y.cpy` -- `CARD-EMBOSSED-NAME PIC X(50)`, `CARD-ACTIVE-STATUS PIC X(01)`, and the
 * two expiry parts carved out of `CARD-EXPIRAION-DATE PIC X(10)`. The two key fields take the widths
 * of `CC-ACCT-ID PIC X(11)` and `CC-CARD-NUM PIC X(16)` in `app/cpy/CVCRD01Y.cpy` L44-L47. A 3270
 * field physically cannot accept a character beyond its length, so `maxLength` is the browser's only
 * faithful equivalent and Transformation Rule T1 makes the copybook the source of the figure.
 */
export const CARD_UPDATE_FIELD_WIDTHS = {
  /** `app/bms/COCRDUP.bms` L84-L88. */
  accountNumber: 11,
  /** `app/bms/COCRDUP.bms` L96-L100. */
  cardNumber: 16,
  /** `app/bms/COCRDUP.bms` L107-L110. */
  embossedName: 50,
  /** `app/bms/COCRDUP.bms` L117-L120. */
  activeStatus: 1,
  /** `app/bms/COCRDUP.bms` L127-L131. */
  expirationMonth: 2,
  /** `app/bms/COCRDUP.bms` L135-L139. */
  expirationYear: 4,
} as const;

/**
 * The five field labels and the expiry separator, verbatim from `app/bms/COCRDUP.bms`.
 *
 * Assumptions: the interior padding and the trailing space on two of them are part of the value, for
 * the reason the sibling detail screen records: the mapset pads each label so the colons align down
 * the column, and the transcription rule for this tree is byte-exact.
 */
export const CARD_UPDATE_FIELD_LABELS = {
  /** `app/bms/COCRDUP.bms` L83. */
  accountNumber: 'Account Number    :',
  /** `app/bms/COCRDUP.bms` L95. */
  cardNumber: 'Card Number       :',
  /** `app/bms/COCRDUP.bms` L106. */
  nameOnCard: 'Name on card      :',
  /** `app/bms/COCRDUP.bms` L116. */
  cardActive: 'Card Active Y/N   : ',
  /** `app/bms/COCRDUP.bms` L126. */
  expiryDate: 'Expiry Date       : ',
  /** `app/bms/COCRDUP.bms` L134 -- the separator painted between the expiry month and year. */
  expirySeparator: '/',
} as const;

/*
 * WHY : ⚠️ Purpose: the expiry month and the expiry year need names that tell them apart, and neither
 *       the mapset nor the program supplies one. `app/bms/COCRDUP.bms` paints ONE label for the pair --
 *       `Expiry Date       : ` at `POS=(15,4)` (L122-L126) -- then `EXPMON` at `POS=(15,25)`, a bare `/`
 *       at `POS=(15,28)` and `EXPYEAR` at `POS=(15,30)` (L127-L139). On a terminal the two are told
 *       apart by their COLUMN, which is exactly the association design gap G1 gives up, so the
 *       distinction has to be stated rather than inferred from where the box sits. A browser measured
 *       the consequence of not stating it: the two consecutive required controls carried accessible
 *       names differing only by a trailing `" /"`, identical at all six widths, so a screen reader
 *       announced them as the same field twice and nothing said which one took the month.
 * WHY : Assumptions: these are ADDITIVE and never rendered visibly, and they follow a convention this
 *       application already established rather than opening a second one --
 *       `ACCOUNT_UPDATE_PART_NAMES` in `ui/src/screens/accountUpdate/index.tsx` L469-L477 names the
 *       parts of that screen's split date and telephone groups the same way, for the same reason, and
 *       records the same G1 grounds. Declaring them beside their renderer rather than in
 *       `ui/src/messages/messages.ts` is that module's own boundary: the catalog carries every string
 *       transcribed FROM the baseline, and an accessible name the baseline never painted is not one.
 * WHY : Alternatives Considered: two, both rejected. Naming the year control with the separator alone,
 *       so that each control's name is literally the text the mapset paints immediately before it --
 *       rejected because an accessible name of `/` is announced as a punctuation mark and states less
 *       than the ambiguous pair it replaces. Composing the names from the program's own refusal
 *       sentences, `Card expiry month must be between 1 and 12` and `Invalid card expiry year` -- also
 *       rejected: a whole sentence is not a field name, and taking a fragment of one invents a string
 *       while pretending not to.
 */

/** Accessible qualifiers for the two halves of the expiry group. Additive: no painted counterpart. */
export const CARD_UPDATE_PART_NAMES = {
  month: 'Month',
  year: 'Year',
} as const;

/**
 * Identifier of the element naming the record inside the save confirmation.
 *
 * Assumptions: both of the confirmation's actions point at this element with `aria-describedby`, so
 * the record being written is announced with whichever action the operator lands on rather than only
 * when the surface as a whole is read. `ui/src/screens/refTypeEdit/index.tsx` wires its own delete
 * confirmation the same way, which is what keeps the nine mutating screens saying this one thing
 * identically.
 */
const SAVE_CONFIRMATION_RECORD_ID = 'card-update-save-confirmation-record';

/**
 * Identifier carried by the expiry group's painted caption.
 *
 * Purpose: the mapset paints ONE caption over two entry fields, so the group of two boxes has to name
 * itself by REFERENCE to that caption rather than by a second copy of its words. Referencing the
 * painted element means the announced name IS the painted caption and cannot drift from it.
 *
 * Assumptions: this follows the convention `partGroupAria` establishes in
 * `ui/src/screens/accountUpdate/index.tsx`, which does the same for that screen's four date groups and
 * its identifier and telephone groups. The helper itself is NOT imported: every screen is mounted
 * through `lazy()` in `ui/src/router.tsx`, so a value import from another screen would fold that
 * screen's whole chunk into this one -- and that screen is the largest in the delivery.
 */
const EXPIRY_GROUP_CAPTION_ID = 'card-update-expiry-group-caption';

/**
 * Function-key legend labels, split from this mapset's TWO legend fields.
 *
 * Assumptions: `app/bms/COCRDUP.bms` paints two fields on row 24, not one. `FKEYS` at L158-L162 is
 * `ATTRB=(ASKIP,NORM)` and always visible, carrying `ENTER=Process F3=Exit`; `FKEYSC` at L163-L167 is
 * `ATTRB=(ASKIP,DRK)` -- non-display -- carrying `F5=Save F12=Cancel`, and `app/cbl/COCRDUPC.cbl`
 * L1315-L1317 un-darkens it only under `PROMPT-FOR-CONFIRMATION`. So two of the four keys are painted
 * from the first turn and two appear only once edits have been validated, which is what the
 * conditional labels below reproduce.
 *
 * Assumptions: PF12's VALIDITY and its VISIBILITY differ in the source and both are preserved.
 * `app/cbl/COCRDUPC.cbl` L418 admits PF12 whenever the record has been fetched, while its legend is
 * painted only at the confirmation turn -- so the binding is registered throughout with an empty
 * label until then, which `usePfKeys` defines as a keyboard-only handler.
 */
const CARD_UPDATE_KEY_LABELS = {
  ENTER: 'ENTER=Process',
  PFK03: 'F3=Exit',
  PFK05: 'F5=Save',
  PFK12: 'F12=Cancel',
} as const;

/**
 * The turn the screen is on, transcribed from `CCUP-CHANGE-ACTION` at `app/cbl/COCRDUPC.cbl` L278-L290.
 *
 * Assumptions: the source holds this in a one-character field carried between pseudo-conversational
 * turns, with a condition name per value -- `'S'` `CCUP-SHOW-DETAILS`, `'E'` `CCUP-CHANGES-NOT-OK`,
 * `'N'` `CCUP-CHANGES-OK-NOT-CONFIRMED`, `'C'` `CCUP-CHANGES-OKAYED-AND-DONE`, `'L'`
 * `CCUP-CHANGES-OKAYED-LOCK-ERROR` and `'F'` `CCUP-CHANGES-OKAYED-BUT-FAILED`. AAP section 0.7.1
 * removes the structure that carried it, so the browser holds it instead.
 *
 * Alternatives Considered: keeping the two independent booleans this screen used before -- a loaded
 * record and a pending set of values -- and deriving the turn from their combination. Rejected because
 * the source's information line is a single `EVALUATE` over exactly these six states
 * (`3250-SETUP-INFOMSG`, L1138-L1160), and four of them are indistinguishable as a pair of booleans:
 * committed, lock-failed and write-failed all present a record with nothing pending, yet the source
 * states a different sentence for each. Naming the state makes that `EVALUATE` transcribable
 * one-for-one instead of approximable.
 *
 * `DETAILS_NOT_FETCHED` is absent deliberately: the route seals a selector, so this screen never has
 * the source's key-entry turn. Its sentence is still carried, for the reason recorded at
 * {@link informationLineFor}.
 */
export type CardUpdateAction =
  | 'SHOW_DETAILS'
  | 'CHANGES_NOT_OK'
  | 'CHANGES_OK_NOT_CONFIRMED'
  | 'CHANGES_OKAYED_AND_DONE'
  | 'CHANGES_OKAYED_LOCK_ERROR'
  | 'CHANGES_OKAYED_BUT_FAILED';

/**
 * Selects the row-20 information sentence for a turn, transcribing `3250-SETUP-INFOMSG`.
 *
 * Assumptions: the source sets this line on EVERY turn and never leaves it blank -- its `EVALUATE`
 * closes with `WHEN WS-NO-INFO-MESSAGE SET PROMPT-FOR-SEARCH-KEYS TO TRUE` (`app/cbl/COCRDUPC.cbl`
 * L1157-L1158), so an unset line falls back to the prompt rather than painting nothing. That fallback
 * is why `PROMPT_FOR_SEARCH_KEYS` is reachable here even though this route always carries its key: it
 * is the source's own default arm, not a key-entry turn this screen can reach.
 *
 * Assumptions: both lock-failed and write-failed map to the same sentence, which is the source's own
 * arrangement rather than a collapse chosen here -- L1153-L1156 sets `INFORM-FAILURE` from both arms.
 * @param {CardUpdateAction} action - The turn the screen is on.
 * @returns {string} The sentence for that turn, verbatim from the catalog keyed by this program.
 */
export function informationLineFor(action: CardUpdateAction): string {
  switch (action) {
    case 'SHOW_DETAILS':
      return CARD_UPDATE_MESSAGES.FOUND_CARDS_FOR_ACCOUNT.text;
    case 'CHANGES_NOT_OK':
      return CARD_UPDATE_MESSAGES.PROMPT_FOR_CHANGES.text;
    case 'CHANGES_OK_NOT_CONFIRMED':
      return CARD_UPDATE_MESSAGES.PROMPT_FOR_CONFIRMATION.text;
    case 'CHANGES_OKAYED_AND_DONE':
      return CARD_UPDATE_MESSAGES.CONFIRM_UPDATE_SUCCESS.text;
    case 'CHANGES_OKAYED_LOCK_ERROR':
    case 'CHANGES_OKAYED_BUT_FAILED':
      return CARD_UPDATE_MESSAGES.INFORM_FAILURE.text;
    default:
      return CARD_UPDATE_MESSAGES.PROMPT_FOR_SEARCH_KEYS.text;
  }
}

/**
 * Composes the row-22 slot for a turn: its sentence, and the emphasis the source gives that field.
 *
 * Purpose: publish the reference's `INFOMSG` line through the frame's information channel, so the
 * line the operator has to read sits in the pinned zone with row 23 rather than in the scrolling body.
 *
 * ⚠️ Assumptions: exactly ONE turn names a severity, and it is the turn that acknowledges a committed
 * write. The mapset declares `INFOMSG` `COLOR=NEUTRAL` on every turn, which is why every other arm
 * omits the member and takes the channel's own `neutral` default -- naming one there could only
 * disagree with the mapset. What the program varies instead is the field's INTENSITY: it moves
 * `DFHBMDAR` into `INFOMSGA` when the line is empty and `DFHBMBRY` -- brightened -- whenever it
 * carries a sentence (`app/cbl/COCRDUPC.cbl` L1309-L1313), so emphasis on a line that has something
 * to say is the source's own behaviour and not an addition. `success` is the browser's expression of
 * it for the one sentence that reports a write has landed, which is the sentence a browser pass found
 * an operator could not see at all.
 *
 * Trade-offs: `success` resolves to the GREEN text role where the field's own operand is `NEUTRAL`,
 * so this one turn paints a colour the mapset does not name. It is accepted for that turn only,
 * because the alternative measured worse in both directions: a neutral acknowledgement is the least
 * distinguishable line on the screen, and a rendering review independently recorded the inverse
 * mapping -- successes rendering as information while a cancellation rendered red -- as a defect in
 * its own right. Every other turn keeps the mapset's colour exactly.
 * @param {CardUpdateAction} action - The turn the screen is on.
 * @returns {ShellInformationSlot} The advisory line for that turn, with a severity only where the
 *   source brightens the field for a reason this application can express.
 */
export function informationSlotFor(action: CardUpdateAction): ShellInformationSlot {
  const text = informationLineFor(action);

  /*
   * WHY : Assumptions: the severity is omitted by SPREAD rather than passed as `undefined`, because
   *       `ui/tsconfig.json` enables `exactOptionalPropertyTypes` -- under which `severity: undefined`
   *       is a type error rather than an absent member. The channel documents the absent member as the
   *       instruction to resolve the appearance from the BMS field, so the two forms are not
   *       interchangeable here even setting the compiler aside.
   */
  return action === 'CHANGES_OKAYED_AND_DONE' ? { text, severity: 'success' } : { text };
}

/**
 * Reports whether the four editable controls accept typing on a turn.
 *
 * Assumptions: the source re-derives every field's protection on each turn in
 * `3300-SETUP-SCREEN-ATTRS` (`app/cbl/COCRDUPC.cbl` L1172-L1208) rather than fixing it in the mapset,
 * and the confirmation and committed turns move `DFHBMPRF` -- protected -- into all six fields at
 * L1191-L1199. So a validated set of edits awaiting `F5` is displayed but not editable, which is what
 * stops the operator changing a value between validating it and committing it.
 * @param {CardUpdateAction} action - The turn the screen is on.
 * @returns {boolean} `true` on the turns the source leaves the four data fields unprotected.
 */
export function acceptsEdits(action: CardUpdateAction): boolean {
  return action === 'SHOW_DETAILS' || action === 'CHANGES_NOT_OK';
}

/**
 * Guidance shown beside the refused-link result, which is a new string.
 *
 * Assumptions: the heading is the baseline's own `No input received`, declared at
 * `app/cbl/COCRDUPC.cbl` L186 for a turn that carried no usable key, and an absent or malformed
 * selector is exactly that. Only the guidance is new, because the selector is a target construct and
 * the baseline has no sentence about a value it never had.
 */
export const CARD_UPDATE_INVALID_LINK_GUIDANCE =
  'Return to the card list and select the record again.';

interface CardFormValues {
  readonly embossedName: string;
  readonly expirationMonth: string;
  readonly expirationYear: string;
  readonly activeStatus: 'Y' | 'N';
}

/*
 * WHY : Refactoring Rationale: the form edits the expiry MONTH and YEAR and offers no day field, where
 *       an earlier revision edited one ISO date. The baseline's update screen edits only those two
 *       parts -- its day input is rendered non-display at app/cbl/COCRDUPC.cbl:1285 and redisplayed
 *       from the pre-edit snapshot at :1123 -- so a date field let an operator change a value the
 *       screen it replaces does not expose. The day the stored date keeps is now the day it already
 *       held, supplied by the service, and no browser can influence it.
 * WHY : Assumptions: the two parts are seeded by SPLITTING the stored date, which is safe because the
 *       response constrains that value to exactly ten ISO characters -- four digits, a hyphen, two
 *       digits, a hyphen, two digits. Splitting by position rather than by parsing a date keeps the
 *       characters the service sent, with no locale or time zone able to shift them.
 */
const EXPIRATION_MONTH_PATTERN = /^(0[1-9]|1[0-2])$/u;

/*
 * WHY : Assumptions: the alphabet is the source's own, character for character.
 *       `app/cbl/COCRDUPC.cbl` L255-L257 declares the 52 characters it accepts as
 *       `ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz`, converts every one of them to a space
 *       at L823-L826 and then accepts the value only if what remains trims to nothing (L828) -- so a
 *       space passes and nothing else does. The class here is the ASCII letters and the space and
 *       deliberately NOT a Unicode letter property: `\p{L}` would admit accented and non-Latin letters
 *       the source rejects, which would let a value through the browser that the service then refuses.
 */
const EMBOSSED_NAME_PATTERN = /^[A-Za-z ]*$/u;

const EXPIRATION_YEAR_PATTERN = /^(19[5-9][0-9]|20[0-9]{2})$/u;

/*
 * WHY : Assumptions: the pair is the source's own accepted domain, upper case only.
 *       `1240-EDIT-CARDSTATUS` moves the entry into `FLG-YES-NO-CHECK` and accepts it only under
 *       `88 FLG-YES-NO-VALID VALUES 'Y','N'` (`app/cbl/COCRDUPC.cbl` L859-L862), and no paragraph
 *       upper-cases this field on the way in -- the only `INSPECT CONVERTING` to upper case in the
 *       program is applied to the embossed name, at L1499-L1501 and L1401-L1403. So a lower-case `y`
 *       is refused by the reference, and admitting it here would accept a value the service rejects.
 */
const ACTIVE_STATUS_PATTERN = /^[YN]$/u;

const EXPIRATION_YEAR_END = 4;

const EXPIRATION_MONTH_START = 5;

const EXPIRATION_MONTH_END = 7;

/**
 * Prefix of the duplicate-write guard key, joined to the selector of the card being written.
 *
 * Assumptions: the key names the METHOD and the target together, matching the shape the sibling
 * reference and user deletions use (`DELETE ${target}`), so two guards in one process cannot collide
 * on a bare identifier. The selector supplies the target half, which is what keeps a write to one card
 * from suppressing a concurrent write to another: this guard exists to collapse a DUPLICATE of one
 * write, not to serialise the screen.
 */
const CARD_WRITE_GUARD_KEY = 'PUT card';

/**
 * Why a read of the edited record was issued, which decides what that read may disturb.
 *
 * Assumptions: the source performs the same read for two different purposes and the two are not
 * interchangeable. `9000-READ-DATA` reads to POPULATE the screen, on entry and again from the `F12`
 * arm (`app/cbl/COCRDUPC.cbl` L484-L497), and it is followed by `SET CCUP-SHOW-DETAILS TO TRUE` so the
 * turn, the field attributes and the information line are all rebuilt from it. `9300-CHECK-CHANGE-IN-REC`
 * reads to RENEW the before-image inside a write turn (L1498-L1519) and touches nothing else: the
 * operator's edits, the refusal it is about to report and the map already on the screen all survive it.
 * Naming the purpose is what keeps the second from behaving like the first.
 */
type CardReadPurpose =
  /** Populate the screen from the stored record, resetting the turn, the marks and the band. */
  | 'DISPLAY'
  /** Renew the retained record's optimistic-lock version only, disturbing nothing else. */
  | 'REFRESH_BEFORE_IMAGE';

/** The turn to move to after a refused write, with the sentence and marks that turn carries. */
export interface CardSaveRejection {
  /** Turn the screen moves to, which selects the row-20 sentence and the field protection. */
  readonly action: CardUpdateAction;
  /** Row-23 sentence to render, always one the service supplied or one of this program's own. */
  readonly statement: string;
  /** Per-field refusals to mark, empty unless the service named fields. */
  readonly fieldErrors: readonly FieldError[];
  /** Whether the record must be re-read before it is redisplayed. */
  readonly reread: boolean;
}

/**
 * Classifies a refused write into the turn the source moves to, its sentence and its field marks.
 *
 * Assumptions: this transcribes the `EVALUATE` at `app/cbl/COCRDUPC.cbl` L988-L1001, which branches a
 * refused write three ways -- a lock that could not be taken sets `CCUP-CHANGES-OKAYED-LOCK-ERROR`, a
 * rewrite that failed sets `CCUP-CHANGES-OKAYED-BUT-FAILED`, and a record another user changed sets
 * `CCUP-SHOW-DETAILS` because `9300-CHECK-CHANGE-IN-REC` has already refreshed the before-image from
 * the current stored values (L1512-L1518) and the operator is returned to a live edit rather than left
 * on a dead one.
 *
 * ⚠️ Refactoring Rationale: the concurrency refusal is recognised through `isConflictFailure` and the
 * whole of this classification is new. This screen previously answered EVERY rejected write with
 * `Update of record failed`, on the recorded ground that "the transport module reports a rejection
 * without its status" -- which is not true of the module it names: `ApiRequestError` carries `status`
 * and `problem`, and `ui/src/api/client.ts` exports `isConflictFailure` precisely so that a screen does
 * not compare the literal itself. That module's own documentation names the consequence of omitting it,
 * that the screen "would render a concurrent change as an ordinary failure", and this screen was that
 * screen: `Record changed by some one else. Please review` could not be reached, so the one refusal
 * that is not the operator's mistake was reported as though it were.
 *
 * Assumptions: a service sentence is preferred over this program's own wherever the problem document
 * carries one, because the service transcribed the same COBOL literals and is the authority on which
 * of them applies; the local constant is the fallback for a rejection that arrived without a body.
 * @param {unknown} failure - The caught value from the update call, of unknown provenance.
 * @returns {CardSaveRejection} The turn to move to, the sentence to render, any field refusals to
 *   mark, and whether the record must be re-read.
 */
export function classifySaveRejection(failure: unknown): CardSaveRejection {
  if (isConflictFailure(failure)) {
    return {
      action: 'SHOW_DETAILS',
      statement:
        failure.problem.message ?? CARD_UPDATE_MESSAGES.DATA_WAS_CHANGED_BEFORE_UPDATE.text,
      fieldErrors: [],
      reread: true,
    };
  }
  /*
   * WHY : Assumptions: a named-field rejection returns to the EDITABLE refusal turn rather than to a
   *       failed one, because that is what the source does with its own edits -- `CCUP-CHANGES-NOT-OK`
   *       is the turn it sets when a field is unacceptable (L697), and it leaves all four data fields
   *       unprotected so the operator can correct them. A write-failed turn would protect them and
   *       leave the refusal uncorrectable.
   * WHY : Assumptions: only the FIRST refusal reaches the row-23 line while every named field is
   *       marked, which is the source's split exactly: each edit paragraph guards its message
   *       assignment with `IF WS-RETURN-MSG-OFF` (L813-L815 and its three siblings) so the first
   *       sentence set is the one that survives, whereas the per-field marks at L1262-L1306 are applied
   *       unconditionally to every field its flag names.
   */
  if (isApiRequestError(failure) && failure.problem.fieldErrors.length > 0) {
    const [first] = failure.problem.fieldErrors;

    return {
      action: 'CHANGES_NOT_OK',
      statement: first?.message ?? CARD_UPDATE_MESSAGES.LOCKED_BUT_UPDATE_FAILED.text,
      fieldErrors: failure.problem.fieldErrors,
      reread: false,
    };
  }

  /*
   * WHY : Trade-offs: a lock failure and a refused rewrite are not distinguishable over this contract,
   *       and the write-failed sentence is chosen for both. The source tells them apart by the CICS
   *       response code of a `READ ... UPDATE` against that of a `REWRITE`, and neither code crosses
   *       the wire -- the service answers a refused write with one problem document. `Update of record
   *       failed` is true of both outcomes, whereas naming a lock would assert a cause this screen has
   *       not established; `Could not lock record for update` stays in the catalog and is rendered
   *       whenever the service's own document supplies it.
   * WHY : ⚠️ Refactoring Rationale: a TRANSIENT refusal is now stated differently from a persistent
   *       one, where every bodiless refusal used to be reported as `Update of record failed`. That
   *       sentence is a claim about the record -- the program sets it after a `REWRITE` came back
   *       refused (`app/cbl/COCRDUPC.cbl` L988-L1001) -- and it is simply untrue of a request that
   *       never reached the service. An operator told the update failed will go and check whether it
   *       partly applied; an operator told the service is unavailable will retry. The classification
   *       is read through `isTransientFailure` rather than by comparing a status, because
   *       `ui/src/api/client.ts` owns which kinds and statuses are transient and a screen-local list
   *       would drift from it.
   * WHY : ⚠️ Trade-offs: the PERSISTENT branch keeps the program's own `LOCKED_BUT_UPDATE_FAILED` and
   *       deliberately does NOT take the authored `PERSISTENT_FAILURE_REPORT_IT`. Rule T8 holds a
   *       user-visible string with a mainframe source to its verbatim form, and this case has one: a
   *       refused rewrite is exactly the outcome the source screen writes that sentence for. The
   *       authored sentence exists for a case the reference never had, which is why the transient
   *       branch takes it and this one does not. Alternatives Considered: using the authored pair for
   *       both branches, which is the simpler shape -- rejected because it would retire a transcribed
   *       sentence the parity suite asserts in favour of a more general one.
   * WHY : Assumptions: a service-supplied sentence still wins over BOTH, unchanged, because the
   *       service transcribed the same COBOL literals and is the authority on which of them applies.
   *       These two are the fallback for a rejection that arrived without a body.
   */
  if (isApiRequestError(failure) && failure.problem.message !== null) {
    return {
      action: 'CHANGES_OKAYED_BUT_FAILED',
      statement: failure.problem.message,
      fieldErrors: [],
      reread: false,
    };
  }

  return {
    action: 'CHANGES_OKAYED_BUT_FAILED',
    statement: isTransientFailure(failure)
      ? TRANSIENT_FAILURE_TRY_AGAIN
      : CARD_UPDATE_MESSAGES.LOCKED_BUT_UPDATE_FAILED.text,
    fieldErrors: [],
    reread: false,
  };
}

/**
 * Indexes a service's field refusals by the form member each one names.
 *
 * Assumptions: the service names fields with the same member names this form uses, which is the
 * property that lets a `FieldError` bind to a control without a translation table -- the request
 * shape in `ui/src/api/types.ts` and the form values here are the same four names. A refusal naming
 * anything else is kept in the map and simply marks no control, which is preferred over discarding it
 * silently: its sentence still reaches the row-23 line through {@link classifySaveRejection}.
 * @param {readonly FieldError[]} errors - Field refusals as the problem document carried them.
 * @returns {ReadonlyMap<string, FieldError>} The refusals keyed by field name, first occurrence
 *   winning so the order the service chose is the order that decides.
 */
export function indexFieldErrors(errors: readonly FieldError[]): ReadonlyMap<string, FieldError> {
  const indexed = new Map<string, FieldError>();

  for (const error of errors) {
    if (!indexed.has(error.field)) {
      indexed.set(error.field, error);
    }
  }
  return indexed;
}

/**
 * Carves the four editable values out of a card record as the form holds them.
 *
 * Assumptions: the expiry parts are taken by POSITION out of the stored value, which is safe because
 * the contract constrains it to exactly ten ISO characters -- four digits, a hyphen, two digits, a
 * hyphen, two digits. Slicing rather than parsing a date keeps the characters the service sent, with no
 * locale or time zone able to shift them across a boundary. The source carves the same three
 * substrings, at `CARD-EXPIRAION-DATE(1:4)`, `(6:2)` and `(9:2)` (`app/cbl/COCRDUPC.cbl` L1514-L1516),
 * and this function deliberately takes only the first two: the day is not an editable value.
 * @param {CardDetail} loaded - The record as the service returned it.
 * @returns {CardFormValues} The four values the form edits, seeded from that record.
 */
function formValuesFrom(loaded: CardDetail): CardFormValues {
  return {
    embossedName: loaded.embossedName,
    expirationYear: loaded.expirationDate.slice(0, EXPIRATION_YEAR_END),
    expirationMonth: loaded.expirationDate.slice(EXPIRATION_MONTH_START, EXPIRATION_MONTH_END),
    activeStatus: loaded.activeStatus,
  };
}

/**
 * Renders the card update form addressed by the opaque selector in its route.
 *
 * Assumptions: the route parameter is validated before any request is issued, for the same reason
 * recorded on the detail screen: a value that cannot address a card must not become a request.
 * @returns {ReactElement} The card update screen or a bounded invalid-link result.
 */
export function CardUpdateScreen(): ReactElement {
  const navigate = useNavigate();
  const location = useLocation();
  // WHY : Assumptions: read here, at the top of the component and above every early return, because
  //       the rules of hooks require an unconditional call site -- the early returns below would make
  //       a later call conditional. Reading it during render is deliberate rather than incidental: the
  //       band displays a PAINT-time instant, which is the property the baseline had because
  //       `POPULATE-HEADER-INFO` re-read the clock on each `SEND MAP` rather than on a timer.
  const paintedAt = useServerInstant();
  const { cardKey: routeIdentifier } = useParams<{
    cardKey: string;
  }>();
  const [form] = Form.useForm<CardFormValues>();
  const { cssVar } = theme.useToken();
  const [card, setCard] = useState<CardDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');
  /*
   * WHY : Assumptions: the turn is held explicitly, for the reason recorded on {@link CardUpdateAction}:
   *       the source's information line is one `EVALUATE` over six named states and three of them look
   *       identical from the outside. It opens on the show-details turn because the route seals a
   *       selector, so this screen never has the source's key-entry turn.
   */
  const [action, setAction] = useState<CardUpdateAction>('SHOW_DETAILS');
  /*
   * WHY : Assumptions: field refusals come from the RESPONSE and nothing else, and no re-entry flag
   *       gates them. `app/cpy/CSSETATY.cpy` L18-L20 gates its mark on `CDEMO-PGM-REENTER` and
   *       `app/cbl/COCRDUPC.cbl` L1263-L1264 gates the data fields on `CCUP-CHANGES-NOT-OK`; both
   *       conditions live in the passed structure that AAP section 0.7.1 removes, and a stateless
   *       handler has no first-entry-versus-re-entry distinction left to draw. What replaces them is
   *       simply whether the response named the field.
   */
  const [fieldErrors, setFieldErrors] = useState<ReadonlyMap<string, FieldError>>(new Map());
  /*
   * WHY : Alternatives Considered: the save is gated by a CONTROLLED dialog that the save key opens,
   *       rather than by a confirmation wrapped around a second save control in the body. The sibling
   *       authorization-detail screen takes the latter shape, so it was the obvious candidate -- but it
   *       leaves TWO controls behind one action, and here both would carry the same legend text and
   *       become indistinguishable to anything selecting a control by its accessible name. Driving one
   *       surface from the single legend control keeps one control per action, which is the property
   *       this screen already chose when it withdrew its bespoke save and cancel buttons.
   *       Trade-offs: the reference wrote the instant `F5` arrived, so this inserts a step the terminal
   *       did not have. It is accepted because AAP section 0.3.2 assigns a confirmation surface exactly
   *       this role -- the browser form of the re-key-to-confirm convention -- and because a pointer can
   *       activate a painted control by accident where a function key cannot, while the write it guards
   *       is a balance-bearing record change that no second write undoes.
   *       Assumptions: the surface itself is an antd `Modal` rather than the `Popconfirm` this screen
   *       used before, because only the dialog primitive can carry the dialog role, the focus trap and
   *       the focus restoration a confirmation needs; the measurement is recorded at the element.
   */
  const [confirmingSave, setConfirmingSave] = useState(false);
  /*
   * WHY : Assumptions: this is the source program's `CCUP-CHANGES-OK-NOT-CONFIRMED` state, held here
   *       because the screen needs it and the server no longer can. `app/cbl/COCRDUPC.cbl` L414-L419
   *       admits PF5 ONLY in that state and PF12 only once the record has been fetched, so the two
   *       keys cannot be bound with the source's behaviour unless the state exists. In the baseline it
   *       lives in the passed structure between two pseudo-conversational turns; AAP section 0.7.1
   *       removes that structure, so the browser holds it -- which is the same relocation the
   *       navigation and selection fields underwent.
   *       Trade-offs: the two turns are preserved even though one request could validate and write
   *       together. Collapsing them would make Enter and PF5 do the same thing, leave the mapset's
   *       second legend field with nothing to reveal, and drop three sentences an operator reads --
   *       the confirmation prompt, the no-change refusal and the committed acknowledgement.
   */
  const [pendingValues, setPendingValues] = useState<CardFormValues | null>(null);

  /*
   * WHY : ⚠️ Purpose: the confirmation's focus has to go SOMEWHERE when the overlay closes, and this is
   *       the control it goes to. The overlay opens focus on its safe choice, which is what stops an
   *       unread Enter from committing; the consequence is that on close the focused element is a button
   *       inside a surface the operator can no longer see, so the next keystroke is delivered to a dead
   *       control. Measured here: the shell defers the ENTER identifier whenever the focused element is
   *       one a browser activates natively, so with focus stranded on the dismissed overlay's Cancel
   *       button an Enter press reached that button instead of the screen's process arm -- the screen
   *       took no turn at all and the operator saw nothing happen.
   * WHY : Assumptions: the destination is the NAME control because that is where the reference puts the
   *       cursor for the turn the closed confirmation returns to. Its cursor block positions on
   *       `CRDNAMEL` for `FOUND-CARDS-FOR-ACCOUNT` and for `NO-CHANGES-DETECTED`
   *       (`app/cbl/COCRDUPC.cbl` L1211-L1214) and on a refused field only when a field was refused --
   *       and nothing is refused on either path out of this overlay, because the edits were validated
   *       before it opened.
   * WHY : Alternatives Considered: `afterOpenChange`, which is the library's own close hook. Rejected
   *       because it fires on motion end, and the two arms below are the only two ways this overlay
   *       closes -- restoring focus in them runs the restore synchronously with the decision that
   *       caused it rather than after an animation that a test environment never finishes.
   */
  const nameControl = useRef<InputRef>(null);
  /*
   * WHY : Assumptions: the read generation is a REF and not state, because nothing renders from it and
   *       it must be readable synchronously by a settlement that ran before the next render. A state
   *       value would be read from the closure of the render that opened the read, so every settlement
   *       would compare its own number against itself and every one of them would look current.
   */
  const readGeneration = useRef(0);
  /*
   * WHY : ⚠️ Refactoring Rationale: the write is latched in a REF and not in the `saving` state, and
   *       the state alone was a duplicate-write defect rather than a redundancy. `setSaving(true)` does
   *       not change the value the CURRENT task already closed over, so two activations that arrive
   *       before React re-renders -- a pointer press and a key press in one task, or an event replayed
   *       into the same batch -- both read `saving === false`, both pass the guard, and both dispatch
   *       the PUT. Browser validation measured exactly that: two `PUT /api/v1/cards/{key}` requests
   *       carrying the SAME `version`, so the service accepted the first and answered the second with
   *       HTTP 409 -- and the screen then reported `Record changed by some one else. Please review` for
   *       a save that had in fact succeeded. A ref is mutated synchronously, so the second arrival in
   *       the same task reads `true` and returns.
   *       Assumptions: `saving` is KEPT alongside it, because the two are used for different things --
   *       the ref decides whether a dispatch may leave, while the state is what a render reads to show
   *       the write is outstanding. A ref cannot do the second job: mutating it schedules no render.
   */
  const writeInFlight = useRef(false);
  const selector =
    routeIdentifier !== undefined && isCardSelector(routeIdentifier) ? routeIdentifier : null;

  /*
   * WHY : Refactoring Rationale: the caller is READ rather than assumed. The exit arm below named the
   *       same card's detail route unconditionally, on the stated ground that the detail screen is
   *       this route's caller -- which is false: the browse screen's `'U'` arm transfers straight here
   *       (`app/cbl/COCRDLIC.cbl` L554), so an operator who came from the browse was returned to a
   *       screen they had never visited and had to press the exit key twice. The reference resolves
   *       the destination instead of fixing it: `app/cbl/COCRDUPC.cbl` L442-L454 prefers
   *       `CDEMO-FROM-TRANID`/`CDEMO-FROM-PROGRAM` and takes its own default only when neither was
   *       recorded, and this is that resolution.
   * WHY : Assumptions: the claim is validated by `inApplicationRoute` rather than trusted, because
   *       router state is writable through a hand-edited history entry -- an unchecked path-shaped
   *       value there would either reach the not-found result or, protocol-relative, leave the
   *       application entirely on a key the operator believes goes back one screen.
   * WHY : Alternatives Considered: the browser's own history, through `navigate(-1)`. Rejected for the
   *       reason `ui/src/routes/navigation.ts` records on `ScreenTransitionState.from`: a history entry
   *       is not a named origin, need not belong to this application, and does not exist at all on a
   *       screen reached by typing its address -- which is precisely the arrival this screen's fallback
   *       has to answer.
   */
  const callerOrigin = inApplicationRoute(screenTransitionState(location.state).from);

  /*
   * WHY : ⚠️ Assumptions: the arrival's narrowing is carried back out on the exit arm, unread by this
   *       screen. This is the COMMAREA round trip and nothing else: the caller writes `CDEMO-ACCT-ID`
   *       before its `XCTL` (`app/cbl/COCRDLIC.cbl` L532 and L560), this program receives that same
   *       area at L671 to L672 and never writes that field, and its exit arm hands the area back with
   *       `XCTL ... COMMAREA(CARDDEMO-COMMAREA)` (`app/cbl/COCRDUPC.cbl` L442 to L460). So the browse
   *       an operator returns to is still narrowed the way they left it. In the target the area does
   *       not travel by itself, so the one member this screen is a waypoint for is forwarded
   *       explicitly.
   *       Alternatives Considered: forwarding the whole received state object. Rejected because it
   *       would also forward `from` -- this screen's OWN caller -- to the screen that caller is, which
   *       would make the browse's exit key return to the browse.
   */
  const forwardedNarrowing = screenTransitionState(location.state).accountId;

  /**
   * Builds the hand-back the exit arm carries to whichever screen it returns to.
   *
   * Assumptions: absent rather than empty when the arrival carried no narrowing, for the reason
   * `ui/tsconfig.json`'s `exactOptionalPropertyTypes` makes structural -- an absent member and a member
   * holding nothing are different types, and only absence means "this transition carries none".
   * @returns {ScreenTransitionState | undefined} The narrowing to hand back, or nothing to hand back.
   */
  function exitHandover(): ScreenTransitionState | undefined {
    return forwardedNarrowing === undefined ? undefined : { accountId: forwardedNarrowing };
  }

  /*
   * WHY : Refactoring Rationale: the read is a named callback because TWO callers need it -- the mount
   *       effect, and the PF12 arm which discards uncommitted edits by re-reading the record. The
   *       source has the same shape: `9000-READ-DATA` is one paragraph, performed on entry and again
   *       from the PF12 arm at `app/cbl/COCRDUPC.cbl` L484-L497. `useCallback` is what makes it usable
   *       as an effect dependency without re-running the effect on every render.
   * WHY : Assumptions: the band is cleared HERE, before the request is issued, and NOT in the
   *       resolution handler below. The difference is load-bearing for the cancel arm: it calls this
   *       reader and then states its own message synchronously, so a handler that cleared the band on
   *       arrival would blank a message the caller had already set. Clearing at the start leaves the
   *       caller's statement as the last write and needs no promise to be threaded back out.
   */
  const reload = useCallback(
    /**
     * Reads the record the form edits and seeds the form from it, discarding any uncommitted edit.
     *
     * ⚠️ Refactoring Rationale: each read opens a GENERATION and seeds the form only while that
     * generation is still the current one. Every read used to apply unconditionally, and two can be
     * outstanding at once for two ordinary reasons: a route change from one card to another re-creates
     * this callback and the effect re-runs it while the first request is in flight, and the cancel arm
     * re-reads on demand. Whichever settled LAST won, so following one row while a slower read of the
     * previous card was outstanding could seed this form -- and the version the save sends with it --
     * from the wrong card. That is worse here than on the detail screen: the retained record carries the
     * optimistic-lock value, so a stale seed would send a write against the version of a card the
     * operator is no longer editing.
     *
     * Alternatives Considered: `AbortController` threaded into `getCard`. Rejected because it would
     * widen the transport signature for every caller of that operation to fix an ordering property of
     * two screens, and because an aborted request still has to be prevented from applying -- so the
     * guard is needed either way.
     * @param {CardReadPurpose} [purpose] - Why the read was issued, defaulting to `'DISPLAY'`. A
     *   `'REFRESH_BEFORE_IMAGE'` read renews only the retained record and disturbs nothing the caller
     *   has stated; see {@link CardReadPurpose}.
     * @returns {void} Completion is represented by the screen's own state.
     */
    (purpose: CardReadPurpose = 'DISPLAY'): void => {
      if (selector === null) {
        setLoading(false);
        return;
      }

      const generation = readGeneration.current + 1;
      const refreshingBeforeImage = purpose === 'REFRESH_BEFORE_IMAGE';

      readGeneration.current = generation;
      /*
       * WHY : ⚠️ Assumptions: a refresh disturbs NOTHING a caller has stated, and the whole of the
       *       screen reset below is skipped for it. This block is what made the concurrency refusal
       *       unreachable in practice: the refused write stated `Record changed by some one else.
       *       Please review` and then this reader cleared the band, blanked the form behind a spinner
       *       and -- once the shell slot saw `loading` -- withdrew the row-23 zone and the key legend
       *       that would have carried the sentence at all. The refresh is a background renewal of the
       *       before-image, exactly as `9300-CHECK-CHANGE-IN-REC` performs it inside the SAME turn
       *       (`app/cbl/COCRDUPC.cbl` L1498-L1519), so the map the operator is looking at stays painted
       *       and the refusal stays on it.
       */
      if (!refreshingBeforeImage) {
        setLoading(true);
        setPendingValues(null);
        setMessage(null);
        setSeverity('error');
        /*
         * WHY : Assumptions: a read clears the field marks and returns to the show-details turn, because
         *       the source reaches this path only by re-reading the record -- `9000-READ-DATA` followed by
         *       `SET CCUP-SHOW-DETAILS TO TRUE` at `app/cbl/COCRDUPC.cbl` L484-L494 -- and that turn's own
         *       attribute pass rebuilds every field unmarked. Marks left behind would accuse fields of
         *       refusals raised against values this read has just replaced.
         */
        setFieldErrors(new Map());
        setAction('SHOW_DETAILS');
      }

      /**
       * Whether this read is still the one whose answer the form wants.
       * @returns {boolean} True while no later read has been opened.
       */
      const isCurrent = (): boolean => readGeneration.current === generation;

      getCard(selector).then(
        /**
         * Seeds the form with the three editable fields and retains the loaded
         * record, whose version carries the optimistic-lock value the save needs.
         * @param {CardDetail} selectedCard - The record the service returned.
         */
        (selectedCard) => {
          if (!isCurrent()) {
            return;
          }
          setCard(selectedCard);
          /*
           * WHY : ⚠️ Assumptions: a refresh renews the retained record and does NOT re-seed the form,
           *       so the operator's rejected edits stay on the screen to be resubmitted. That is the
           *       source's own division: `9300-CHECK-CHANGE-IN-REC` moves the freshly read values into
           *       the `CCUP-OLD-*` before-image ONLY (`app/cbl/COCRDUPC.cbl` L1512-L1518) and leaves
           *       `CCUP-NEW-*` -- the operator's typing -- untouched, then re-sends the map. Re-seeding
           *       would discard the edits the refusal has just asked the operator to review, and the
           *       screen's own concurrency note promises they are left in place.
           *       Trade-offs: what the operator now sees is their edits over a record whose stored
           *       values have moved, which is what the refusal sentence exists to say. The renewed
           *       version is what makes the resubmission conditional on the row as it now stands.
           */
          if (refreshingBeforeImage) {
            return;
          }
          form.setFieldsValue(formValuesFrom(selectedCard));
          setLoading(false);
        },
        /*
         * WHY : Trade-offs: one sentence covers every rejection, where the source branches on the file
         *       response -- `DFHRESP(NOTFND)` sets this sentence at `app/cbl/COCRDUPC.cbl`
         *       L1395-L1401 while any other response composes `WS-FILE-ERROR-MESSAGE` at L1402-L1411.
         *       The coarser one is chosen because the other branch's text is not reproducible: it
         *       appends the internal VSAM file name and the CICS response and reason codes, none of
         *       which exist in this architecture, and surfacing infrastructure identifiers to a browser
         *       would be an information-disclosure regression rather than a parity gain. Distinguishing
         *       the branches is POSSIBLE here -- `ApiRequestError` carries `status` -- so this is a
         *       deliberate choice about the text, not a limit of the transport.
         *       Assumptions: the sentence names no card, so no identifier reaches the band or a log.
         */
        /*
         * WHY : ⚠️ Assumptions: a superseded FAILURE is discarded exactly as a superseded success is. It
         *       is the half that is easy to leave out, and leaving it out is worse than leaving out the
         *       other: the refusal sentence would appear over a form that had seeded correctly, so an
         *       operator would be told a card could not be read while editing it.
         */
        /** Reports a retrieval failure using the source program's own not-found sentence. */
        () => {
          if (!isCurrent()) {
            return;
          }
          /*
           * WHY : ⚠️ Assumptions: a FAILED refresh states nothing and leaves the caller's sentence
           *       standing, which is the second half of the wrong-answer defect this reader caused.
           *       The refused write had already named the refusal; this handler then replaced it with
           *       `Did not find cards for this search condition`, so the operator was told the card no
           *       longer existed when what had actually happened was that someone else had changed it --
           *       and, on the measured sequence, that the save had failed when it had succeeded. The
           *       refresh is an attempt to renew the before-image, and failing to renew it is not news
           *       about the write: the sentence the write produced is the true one and it survives.
           *       Trade-offs: an unrenewed before-image means a resubmission carries the version this
           *       screen still holds and will be refused again -- which is correct, because nothing has
           *       established a newer one.
           */
          if (refreshingBeforeImage) {
            return;
          }
          setMessage(CARD_UPDATE_MESSAGES.DID_NOT_FIND_ACCTCARD_COMBO.text);
          setSeverity('error');
          setLoading(false);
        },
      );
    },
    [form, selector],
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

  /**
   * Publishes one screen-level message with the appearance its source field had.
   *
   * Assumptions: the source screen has two message fields and this tree provides one band, so both
   * collapse onto it and the caller states which appearance applies. `INFOMSG` is a 40-character
   * `COLOR=NEUTRAL` field (`app/bms/COCRDUP.bms` L145-L149) carrying the prompts and the committed
   * acknowledgement; `ERRMSG` is an 80-character `COLOR=RED` field at row 23 (L152-L156) carrying the
   * refusals. The `field` member on each catalog entry records which of the two a sentence came from,
   * so the severity passed at each call site is read off the source rather than chosen.
   * @param {string | null} text - Sentence to render, or `null` to clear the band.
   * @param {MessageBandSeverity} appearance - Appearance matching the source field the sentence came
   *   from: `info` for `WS-INFO-MSG` and `error` for `WS-RETURN-MSG`.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function report(text: string | null, appearance: MessageBandSeverity): void {
    setMessage(text);
    setSeverity(appearance);
  }

  /**
   * Reports whether the submitted values differ from the record as it was read.
   *
   * Assumptions: the comparison is on the four editable values -- the embossed name, the active
   * status and the two expiry parts -- because those are the whole of what this screen can change.
   * The day the stored expiration date carries is redisplayed from the record and no browser can
   * influence it, so it can never differ. That set is the source's own: `CCUP-NEW-CARDDATA`
   * (`app/cbl/COCRDUPC.cbl` L307-L314) groups exactly `CCUP-NEW-CRDNAME`, the expiry year, month and
   * day, and `CCUP-NEW-CRDSTCD`, and it deliberately EXCLUDES the account id, the card id and the
   * card verification value, which sit outside the group one level up. The source refuses an
   * unchanged submission with `No change detected with respect to values fetched.` (L188).
   * @param {CardFormValues} values - Values the form validated.
   * @param {CardDetail} loaded - The record as the service returned it.
   * @returns {boolean} `true` when at least one editable value differs.
   */
  function hasChanges(values: CardFormValues, loaded: CardDetail): boolean {
    /*
     * WHY : Assumptions: the comparison is CASE-INSENSITIVE, and that is the source's semantic rather
     *       than a convenience here. `app/cbl/COCRDUPC.cbl` L680-L681 tests
     *       `FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA) EQUAL FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)` --
     *       one comparison over the WHOLE group with both sides folded -- so re-typing a stored
     *       `JOHN SMITH` as `john smith` is NO CHANGE to the reference and must not reach a write here
     *       either. A case-sensitive comparison would send a write the reference answers with the
     *       no-change sentence, which is a behavioural divergence and not a cosmetic one. The fold is
     *       applied to every member, mirroring the group-level `UPPER-CASE`, so the semantic does not
     *       depend on ACTIVE_STATUS_PATTERN continuing to admit upper case only. `toUpperCase` is the
     *       locale-INDEPENDENT fold and it agrees with the source exactly over the admissible domain:
     *       the source converts the 26 ASCII letters it declares at L260-L263, and
     *       EMBOSSED_NAME_PATTERN admits nothing beyond those letters and the space, so no
     *       locale-specific mapping is reachable.
     */
    /**
     * Folds one value to upper case, standing in for the source's `FUNCTION UPPER-CASE`.
     * @param {string} value - The value to fold, either submitted or as read.
     * @returns {string} The value with its ASCII letters raised to upper case.
     */
    const foldCase = (value: string): string => value.toUpperCase();

    return (
      foldCase(values.embossedName) !== foldCase(loaded.embossedName) ||
      foldCase(values.activeStatus) !== foldCase(loaded.activeStatus) ||
      foldCase(values.expirationYear) !==
        foldCase(loaded.expirationDate.slice(0, EXPIRATION_YEAR_END)) ||
      foldCase(values.expirationMonth) !==
        foldCase(loaded.expirationDate.slice(EXPIRATION_MONTH_START, EXPIRATION_MONTH_END))
    );
  }

  /**
   * Validates the submitted edits and asks for confirmation, which is the source's Enter arm.
   *
   * Assumptions: this writes nothing. `app/cbl/COCRDUPC.cbl` edits the fields on Enter and, when they
   * are acceptable, reaches `PROMPT-FOR-CONFIRMATION` -- which sets the prompt at L167 and un-darkens
   * the second legend field at L1315-L1317 -- and only the later PF5 turn performs
   * `9200-WRITE-PROCESSING` (L988-L1001). Field-level refusals are raised by the form's own rules
   * before this runs, so what arrives here has already passed them.
   * @param {CardFormValues} values - Values validated by the Ant Design form.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function process(values: CardFormValues): void {
    /*
     * WHY : Assumptions: a submission arriving while a write is in flight is ignored, and the guard is
     *       here rather than expressed as a disabled binding. A disabled binding reports through the
     *       hook's invalid-key channel, and this screen's channel re-runs this arm -- so disabling it
     *       would route a suppressed key straight back into the thing it was suppressing. The source
     *       needs no such guard at all: a terminal turn is serialised, so a second Enter could not
     *       arrive while the first was still being processed.
     * WHY : Refactoring Rationale: this reads the same REF the write arm reads, not `saving`, for the
     *       reason recorded on `writeInFlight` -- a state value cannot answer an arrival inside the task
     *       that set it. This arm writes nothing, so a duplicate of it is not the defect the write arm's
     *       duplicate is; the two guards nonetheless read one value, so a write in flight suppresses
     *       both arms consistently rather than one of them.
     */
    if (writeInFlight.current) {
      return;
    }
    if (selector === null || card === null) {
      report(CARD_UPDATE_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text, 'error');
      return;
    }
    if (!hasChanges(values, card)) {
      /*
       * WHY : Assumptions: an unchanged submission returns to the SHOW-DETAILS turn rather than to a
       *       refusal turn, which is the source's own arrangement: `NO-CHANGES-DETECTED` sets all four
       *       field flags VALID and leaves the edit paragraph before `CCUP-CHANGES-NOT-OK` is ever set
       *       (`app/cbl/COCRDUPC.cbl` L684-L693). So the record stays editable and no field is marked --
       *       nothing about it was wrong.
       */
      setPendingValues(null);
      setFieldErrors(new Map());
      setAction('SHOW_DETAILS');
      /*
       * WHY : Assumptions: an open confirmation is dismissed by a read, because the values it was asking
       *       about are the ones this read is about to replace. Accepting it afterwards would write a set
       *       of edits the operator can no longer see.
       */
      setConfirmingSave(false);
      report(CARD_UPDATE_MESSAGES.NO_CHANGES_DETECTED.text, 'error');
      return;
    }
    /*
     * WHY : Assumptions: the row-23 line is CLEARED as the confirmation turn opens, because the sentence
     *       that turn carries is an information-line sentence and not a refusal --
     *       `PROMPT-FOR-CONFIRMATION` is an `88` on `WS-INFO-MSG` at `app/cbl/COCRDUPC.cbl` L167-L168,
     *       and the catalog entry records its field as `WS-INFO-MSG` too. It reaches the row-20 band
     *       through the action below; leaving a stale refusal on row 23 beside it would show a rejection
     *       that no longer applies.
     */
    setPendingValues(values);
    setFieldErrors(new Map());
    setAction('CHANGES_OK_NOT_CONFIRMED');
    report(null, 'info');
  }

  /**
   * Moves to the refusal turn when the form's own rules rejected the submission.
   *
   * Assumptions: the source runs all four edits on every turn and marks every field whose flag is not
   * valid, while only the first sentence reaches the row-23 line -- each assignment is guarded by
   * `IF WS-RETURN-MSG-OFF` (`app/cbl/COCRDUPC.cbl` L813-L815 and its three siblings). This screen
   * renders each refusal against its own control through the form's rules, so the sentences are already
   * placed; what is left is the turn, which selects the row-20 information line and keeps all four
   * fields editable so the refusals can be corrected.
   *
   * Trade-offs: the first refusal is NOT additionally copied onto the row-23 line. The source paints
   * both lines at once (`MOVE WS-INFO-MSG TO INFOMSGO` and `MOVE WS-RETURN-MSG TO ERRMSGO`, L1160 and
   * L1162), so a second surface for the same sentence would be faithful to it -- but it would render one
   * refusal twice on a screen that already shows it against the control it belongs to, and the design
   * system's own mapping assigns per-field validation to `Form.Item` and reserves the band for a
   * screen-level message. The row-23 line is therefore used for refusals the SERVICE raises, which have
   * no control to sit beside until their field is named.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function refuseSubmission(): void {
    setPendingValues(null);
    setConfirmingSave(false);
    setAction('CHANGES_NOT_OK');
  }

  /**
   * Writes the confirmed edits, which is the source's PF5 arm.
   *
   * Assumptions: the version travels with the request, so the service refuses a write against a record
   * another user has changed. The source holds the same guard as a before-image comparison across the
   * two turns and answers `Record changed by some one else. Please review`; here the comparison is the
   * service's and the refusal arrives as a rejected request.
   *
   * ⚠️ Assumptions: exactly ONE request leaves per confirmation, and the version it carries is the one
   * the operator's own read returned. The two properties compose: two dispatches carrying one version
   * are not a harmless retry, because a service honouring the version accepts the first and answers the
   * second with a conflict -- so the operator is shown a failure for a write that succeeded. Each is
   * guarded separately below, at the point where it can go wrong.
   * @returns {void} Completion is represented by the screen's own state or a route change.
   */
  function save(): void {
    /*
     * WHY : Assumptions: the confirmation is dismissed as the write begins, so the overlay cannot be
     *       accepted a second time while the first write is outstanding. The in-flight guard below is
     *       kept as well rather than replaced by it, because the two answer different arrivals: this
     *       closes the overlay, while the guard covers a key or a control reaching this arm by any other
     *       route. The source needs neither -- a terminal turn is serialised, so a second confirmation
     *       could not arrive while the first was being processed.
     */
    setConfirmingSave(false);
    nameControl.current?.focus();
    /*
     * WHY : ⚠️ Refactoring Rationale: the guard reads the REF and not `saving`, and the state it read
     *       before could not hold. Both `setConfirmingSave(false)` above and `setSaving(true)` below
     *       take effect at the next render, so a second arrival inside the same task saw an open
     *       confirmation and an idle screen and dispatched a second identical PUT. The reason this is a
     *       correctness defect and not a duplicated-effort one is recorded on `writeInFlight`.
     */
    if (writeInFlight.current) {
      return;
    }
    if (selector === null || card === null || pendingValues === null) {
      report(CARD_UPDATE_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text, 'error');
      return;
    }

    /*
     * WHY : Assumptions: the request carries the expiry MONTH and YEAR and no day, which is the shape
     *       `CardUpdateRequest` in `ui/src/api/types.ts` declares. The source assembles the stored value
     *       as `STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-' CCUP-NEW-EXPDAY` at
     *       `app/cbl/COCRDUPC.cbl` L1467-L1474, and the day it feeds in is the one it read: the line
     *       that would have carried an operator-supplied day is commented out at L1122 in favour of
     *       `MOVE CCUP-OLD-EXPDAY`, under the source's own note that these are "NON-DISPLAY FIELDS THAT
     *       WE ARE NOT ALLOWING USER TO CHANGE". So the day is never an input, and this contract keeps
     *       it server-side rather than round-tripping a value no browser may influence.
     * WHY : Assumptions: no card verification value is sent, and none is held. `CARD-CVV-CD PIC 9(03)`
     *       takes part in the source's before-image comparison and its rewrite (L1464-L1465, L1503) yet
     *       has NO field in `app/bms/COCRDUP.bms` at all -- the operator never sees or supplies it -- so
     *       the service preserves it exactly as the source does, and AAP section 0.4.1.9 makes its
     *       non-return absolute.
     * WHY : Assumptions: the version travels so the service can refuse a write against a record that
     *       has moved; see {@link classifySaveRejection} for what it does with the refusal.
     */
    /*
     * WHY : ⚠️ Assumptions: the name is put into ITS CANONICAL FORM before it goes on the wire, through
     *       `normaliseForWire` from `ui/src/messages/messages.ts`, and it was sent exactly as typed
     *       before. The destination is a fixed-width record -- `CARD-EMBOSSED-NAME PIC X(50)` -- where a
     *       character has one representation and fifty bytes is the whole field, while a browser will
     *       hand back either of two encodings for the same letter depending on how it was produced: a
     *       precomposed one, or a base letter followed by a combining mark. Sent unnormalised, the two
     *       reach the service as different values for the same name, so a record written from a paste
     *       and the same record retyped no longer compare equal -- which is the condition the whole
     *       before-image comparison at `app/cbl/COCRDUPC.cbl` L1498-L1520 turns on.
     *       Trade-offs: on THIS field every accepted value is already canonical, because the alphabet
     *       the source declares admits only ASCII letters and the space, so this is a no-op today. It is
     *       applied regardless because the canonical form is a property of the WIRE and not of whichever
     *       rule admitted the value: the guarantee then survives a widened alphabet without a second
     *       edit, at the cost of one call whose effect is currently invisible.
     */
    const request: CardUpdateRequest = {
      ...pendingValues,
      embossedName: normaliseForWire(pendingValues.embossedName),
      version: card.version,
    };
    const target = requireCardSelector(selector);

    /*
     * WHY : ⚠️ Assumptions: the version is checked to be USABLE before the request is composed onto the
     *       wire, and the check is `requireConditionalOn` from `ui/src/api/client.ts` rather than a
     *       comparison written here -- the same rule the account edit applies to its entity tag, so the
     *       two screens cannot disagree about which preconditions are unusable. What it refuses is a
     *       negative or non-safe-integer version, which is a value that cannot have come from a read:
     *       sending one would ask the service to make the write conditional on a row state that never
     *       existed, and a service that compared it would answer a conflict the operator cannot act on.
     *       Refusing it here names the real condition instead.
     * WHY : Alternatives Considered: relying on the response validator in `ui/src/api/cards.ts`, which
     *       already applies this rule to every card it returns. Rejected as sufficient on its own: it
     *       guards the value's ARRIVAL, and this guards its DEPARTURE. The value in `request` is the one
     *       about to be sent, and the two are the same value only while nothing between the read and
     *       this line has replaced the retained record -- which is exactly what the refresh path above
     *       does. Checking the outgoing value is what makes "the version carried is the one the read
     *       returned" a property of the request rather than a property of an earlier response.
     * WHY : Assumptions: the sentence is the source's own `Could not lock record for update`, set at
     *       `app/cbl/COCRDUPC.cbl` L1441-L1447 for the case where the program could not establish a
     *       conditional read of the record it was about to rewrite, and the turn is the one that arm
     *       leads to -- `CCUP-CHANGES-OKAYED-LOCK-ERROR` at L993-L994. An unusable version is that
     *       condition in this architecture: there is no basis on which the write could be made
     *       conditional, so nothing is sent.
     */
    try {
      requireConditionalOn(request.version, 'A card edit');
    } catch {
      setPendingValues(null);
      setAction('CHANGES_OKAYED_LOCK_ERROR');
      report(CARD_UPDATE_MESSAGES.COULD_NOT_LOCK_FOR_UPDATE.text, 'error');
      return;
    }

    writeInFlight.current = true;
    setSaving(true);
    report(null, 'error');
    /*
     * WHY : ⚠️ Refactoring Rationale: the dispatch is wrapped in `withoutConcurrentDuplicate`, keyed on
     *       the card this write targets. It is the SECOND of the two guards and it covers what the ref
     *       cannot: a ref belongs to one mounted component, so a remount -- which a route re-key
     *       performs -- produces a fresh one that knows nothing of a write the previous instance still
     *       has outstanding. The key is module-level and outlives the mount, so the second dispatch
     *       joins the first promise instead of issuing a second PUT against the same version.
     *       Assumptions: the key NAMES the target, so two different cards are never collapsed into one
     *       another -- the guard is against a duplicate of one write, not against concurrency as such.
     *       Trade-offs: a joining caller is told the outcome but does not adopt the committed record,
     *       because the record is adopted inside the attempt that actually ran. That is the right way
     *       round: applying a response twice would be a second write's worth of state change for one
     *       write, and the joining instance still learns of a refusal through the handler below.
     */
    withoutConcurrentDuplicate(
      `${CARD_WRITE_GUARD_KEY} ${target}`,
      /**
       * Issues the write and adopts the committed record inside the guarded attempt.
       * @returns {Promise<void>} Settles when the service has answered; a refusal is left to reject so
       *   the handler below reports it once.
       */
      async (): Promise<void> => {
        adoptCommittedRecord(await updateCard(target, request));
      },
    ).then(releaseWriteLatch, reportRefusedWrite);
  }

  /**
   * Releases the write latch and the rendered busy state, whatever the write answered.
   *
   * Assumptions: the latch is released in BOTH settlements and not only on success, because a latch
   * that survived a refusal would leave the save key permanently inert -- the operator would correct
   * the refusal and find nothing happened. It is released here rather than in the two outcome handlers
   * so that neither can omit it.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function releaseWriteLatch(): void {
    writeInFlight.current = false;
    setSaving(false);
  }

  /**
   * Adopts the committed record and states the source's own acknowledgement.
   *
   * ⚠️ Refactoring Rationale: a committed write STAYS on this screen and states the source's
   * acknowledgement, where this screen used to navigate straight to the card detail route. The source
   * does not leave either: `CCUP-CHANGES-OKAYED-AND-DONE` reaches
   * `WHEN CCUP-CHANGES-OKAYED-AND-DONE SET CCUP-SHOW-DETAILS TO TRUE` at `app/cbl/COCRDUPC.cbl`
   * L1010-L1012 and re-sends its own map, and `3250-SETUP-INFOMSG` states `Changes committed to
   * database` on that turn (L1151-L1152). Navigating away made that sentence unreachable, so the one
   * confirmation an operator gets that the write landed was never rendered; it also discarded the answer
   * the service had just returned.
   *
   * Assumptions: the RESPONSE is adopted as the record, not the request. It carries the version the next
   * write must present, so a second edit in the same visit is submitted against the version the service
   * now holds rather than the one this screen loaded -- which would be refused as a conflict every time.
   * @param {CardDetail} updated - The card as the service reports it after the change, carrying the
   *   version a subsequent write must present.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function adoptCommittedRecord(updated: CardDetail): void {
    setPendingValues(null);
    setFieldErrors(new Map());
    setCard(updated);
    form.setFieldsValue(formValuesFrom(updated));
    setAction('CHANGES_OKAYED_AND_DONE');
    report(null, 'success');
  }

  /**
   * Classifies a rejected write, moves to the turn the source moves to and releases the latch.
   *
   * Assumptions: the rejection is classified rather than flattened, and the whole rationale --
   * including why this screen previously could not reach the concurrency sentence -- is recorded on
   * {@link classifySaveRejection}. A conflict additionally refreshes the retained record, because the
   * source refreshes its before-image from the current stored values before returning the operator to a
   * live edit (`app/cbl/COCRDUPC.cbl` L1512-L1518); leaving the stale version behind would invite the
   * same write to be resubmitted against the same superseded one.
   * @param {unknown} failure - The rejection the update call produced, of unknown provenance.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function reportRefusedWrite(failure: unknown): void {
    const rejection = classifySaveRejection(failure);

    releaseWriteLatch();
    setPendingValues(null);
    /*
     * WHY : ⚠️ Refactoring Rationale: the refresh is issued as a `'REFRESH_BEFORE_IMAGE'` read and the
     *       sentence is stated AFTER it, and the ordering is no longer what makes the sentence survive.
     *       It used to be: the reader cleared the band as it started, so a sentence stated first was
     *       blanked -- and a sentence stated afterwards was still replaced when that read FAILED, which
     *       is the sequence browser validation measured (`PUT` 409, then `GET` 409, then
     *       `Did not find cards for this search condition` on screen in place of the conflict). The
     *       refresh now disturbs nothing this handler states, in either settlement, for the reasons
     *       recorded on `reload`. The order is kept because the turn and the marks below belong to the
     *       refusal and must be the last word on it either way.
     */
    if (rejection.reread) {
      reload('REFRESH_BEFORE_IMAGE');
    }
    setFieldErrors(indexFieldErrors(rejection.fieldErrors));
    setAction(rejection.action);
    report(rejection.statement, 'error');
  }

  /**
   * Discards uncommitted edits and redisplays the stored record, which is the source's PF12 arm.
   *
   * Assumptions: the source re-reads the record rather than restoring a snapshot --
   * `app/cbl/COCRDUPC.cbl` L484-L497 performs `9000-READ-DATA` again and re-sends the map with
   * `CCUP-SHOW-DETAILS` set -- so a cancel shows the record as it stands now, including a change
   * another user has committed in the meantime.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function cancelEdits(): void {
    /*
     * WHY : ⚠️ Refactoring Rationale: the cancel arm no longer STATES a sentence, and the sentence it
     *       stated was the wrong one. It reported `Update card details presented above.`, which is the
     *       source's refusal-turn line -- `3250-SETUP-INFOMSG` sets `PROMPT-FOR-CHANGES` only
     *       `WHEN CCUP-CHANGES-NOT-OK` (`app/cbl/COCRDUPC.cbl` L1147-L1148). The cancel arm sets
     *       `CCUP-SHOW-DETAILS` (L1010-L1012, and L494 on the entry path), whose line is
     *       `FOUND-CARDS-FOR-ACCOUNT` at L1145-L1146. `reload` moves to that turn and the row-20 band is
     *       derived from it, so the correct sentence now appears without being stated -- which also
     *       removes the ordering hazard that a statement made around a read has to be reasoned about.
     */
    reload();
  }

  /*
   * WHY : Assumptions: the handler map is BUILT rather than written as a literal, because two of its
   *       four members are conditional in the source. `app/cbl/COCRDUPC.cbl` L414-L419 admits PF5 only
   *       while validated edits await confirmation and PF12 only once the record has been fetched, so
   *       a fixed map would offer a write key on a turn the source refuses it -- and `usePfKeys`
   *       reports an unregistered key through the invalid-key channel, which is exactly the coercion
   *       the source performs for it.
   * WHY : Assumptions: PF12 carries a label only at the confirmation turn, which separates its validity
   *       from its visibility the way the mapset does -- `FKEYSC` is non-display until then, so the key
   *       works before its legend appears. An empty label is how `usePfKeys` expresses a handler with
   *       no painted legend.
   */
  const awaitingConfirmation = pendingValues !== null;

  /**
   * Withdraws the confirmation without writing, leaving the validated edits in place.
   *
   * Assumptions: declining the overlay is NOT the source's `F12` arm and must not behave like one. `F12`
   * discards the edits and re-reads the record (`app/cbl/COCRDUPC.cbl` L484-L497); declining a
   * confirmation only means "not yet", so the edits and the confirmation turn both survive and the save
   * key can be pressed again. The source's own analogue is the turn where the confirmation was requested
   * and no `F5` arrived, which it answers with `WHEN CCUP-CHANGES-OK-NOT-CONFIRMED CONTINUE` (L1005-L1007)
   * -- it changes nothing at all.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function dismissConfirmation(): void {
    setConfirmingSave(false);
    nameControl.current?.focus();
  }

  /**
   * Runs the source's `ENTER=Process` arm, which is not always a form submission.
   *
   * Assumptions: the mapset paints `ENTER=Process F3=Exit` on row 24 with `ATTRB=(ASKIP,NORM)` and no
   * paragraph ever darkens it (`app/bms/COCRDUP.bms` L158-L162; the only field
   * `3300-SETUP-SCREEN-ATTRS` touches is `FKEYSC`, at `app/cbl/COCRDUPC.cbl` L1315-L1317), so Enter is
   * an offered action on EVERY turn including the one that holds no record. What it does on that turn
   * is the source's own answer: `1200-EDIT-MAP-INPUTS` validates the two search keys while
   * `CCUP-DETAILS-NOT-FETCHED` and, finding both blank, sets `NO-SEARCH-CRITERIA-RECEIVED` (L645-L659),
   * whose literal is `No input received` (L185-L186). So the keyless turn answers Enter with that
   * sentence on row 23 rather than by submitting anything.
   *
   * Alternatives Considered: leaving the binding as a bare `form.submit()`, which is what it was.
   * Rejected because the form is not mounted on either of the two turns that render no record -- the
   * refused-selector turn and a read in flight -- and Ant Design's `useForm` instance warns and does
   * nothing when submitted while unconnected. So the operator pressed an offered key and got silence in
   * the frame and a warning in the console, where the reference states a sentence.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function submitEdits(): void {
    if (selector === null) {
      report(CARD_UPDATE_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text, 'error');
      return;
    }
    /*
     * WHY : Assumptions: a read in flight states NOTHING, where the keyless turn above states a
     *       sentence. The reference has no read-in-flight turn to transcribe -- a terminal turn is
     *       serialised, so the region either has the record or is not accepting input -- and the two
     *       cases are not the same condition: `No input received` is an answer about the KEYS, and
     *       during a read the keys are known and being used. Stating it here would report a refusal for
     *       a turn that has not refused anything.
     */
    if (loading) {
      return;
    }
    form.submit();
  }

  /*
   * WHY : ⚠️ Refactoring Rationale: every key below now DECLARES what its action risks, and none did.
   *       `ui/src/layout/PfKeyBar.tsx` used to derive emphasis from the AID alone, through
   *       `PRIMARY_ACTION_AIDS`, and that table cannot be right across this application: the same
   *       `PFK05` is `F5=Save` here, `F5=Delete` on `app/bms/COUSR03.bms` L148 and a browse key
   *       elsewhere, so one AID-keyed answer paints a delete and a save identically. The risk is
   *       therefore read off the LABEL -- what the key says it does -- and never off the key it is
   *       bound to.
   * WHY : ⚠️ Assumptions: exactly ONE key on this screen is `mutating`, and it is `F5=Save`. The
   *       classification follows the source arm rather than the name: `ENTER=Process` runs
   *       `2000-PROCESS-INPUTS` and ends by setting the confirmation prompt without writing
   *       (`app/cbl/COCRDUPC.cbl` L1138-L1163), `F3=Exit` transfers without writing (L442-L454) and
   *       `F12=Cancel` redisplays the stored record, so all three write nothing and take `read-only`.
   *       The save is `mutating` rather than `destructive` because it updates a record the operator can
   *       come back and edit again -- `destructive` is reserved for an action that removes a record or
   *       moves money, which is the distinction `PfKeyBar`'s own emphasis constants draw.
   */
  const keyHandlers: PfKeyHandlerMap = {
    ENTER: {
      /** Validates the edits and asks for confirmation, the source's `ENTER=Process` action. */
      onInvoke: submitEdits,
      label: CARD_UPDATE_KEY_LABELS.ENTER,
      risk: 'read-only',
    },
    PFK03: {
      /**
       * Returns to the caller the transition named, and otherwise to this card's own detail screen.
       *
       * Assumptions: the source resolves the same two arms in the same order -- it prefers the
       * recorded caller and falls back only when none was recorded
       * (`app/cbl/COCRDUPC.cbl` L442-L454). This route has exactly two callers, the browse screen's
       * `'U'` arm and the detail screen's edit control, and the browse always names itself while the
       * detail screen forwards whatever named IT. So the fallback answers an arrival with no caller
       * anywhere in the chain: a typed address, a bookmark, a reload, or a transition that fell back
       * to a full document navigation and so dropped its state.
       *
       * Trade-offs: the fallback is this card's detail screen where the source's is the main menu
       * (`LIT-MENUPGM` at L451). It is a deliberate divergence: the source could only reach that arm
       * by being started as a bare transaction with no card, while every arrival here carries a
       * selector in its own address, so the record is on hand and the nearest screen showing it is
       * one step away. Returning an operator to the menu from a card they were editing would discard
       * a context the address still holds. The cost is that the menu is then two presses away rather
       * than one, by way of the browse the detail screen's own exit key reaches -- and the reference
       * routes an operator the same way, since `COCRDSLC` L318-L320 also prefers its own recorded
       * caller, the card list, over the menu it names only when none was recorded.
       */
      onInvoke: () => {
        /*
         * WHY : ⚠️ Assumptions: a REFUSED selector falls back to the browse and not to this card's own
         *       detail screen, because there is no card to show one for. The fallback below reads the
         *       selector through `requireCardSelector`, which THROWS on a value that is absent or
         *       malformed -- exactly the value this arm is reached with when the address is the reason
         *       the screen refused. So the key the refusal turn paints, `F3=Exit` (`app/bms/COCRDUP.bms`
         *       L158-L162), would have raised rather than exited. The browse is where the reference's own
         *       neighbours send an operator with nothing to return to: `COCRDSLC` L318-L320 prefers its
         *       recorded caller and this screen has no caller when the address was typed.
         */
        if (selector === null) {
          navigateSafely(navigate, callerOrigin ?? CARD_LIST_ROUTE, exitHandover());
          return;
        }
        navigateSafely(
          navigate,
          callerOrigin ?? cardDetailPath(requireCardSelector(selector)),
          exitHandover(),
        );
      },
      label: CARD_UPDATE_KEY_LABELS.PFK03,
      risk: 'read-only',
    },
    ...(awaitingConfirmation
      ? {
          PFK05: {
            /**
             * Asks for confirmation of the validated edits, the source's `F5=Save` action.
             *
             * Assumptions: this OPENS the confirmation rather than writing, for the reason recorded at
             * `confirmingSave`; the write itself is `save`, reached from the overlay's accept action.
             */
            onInvoke: () => {
              setConfirmingSave(true);
            },
            label: CARD_UPDATE_KEY_LABELS.PFK05,
            risk: 'mutating',
            /**
             * Reports whether this screen's write is still outstanding.
             *
             * ⚠️ Assumptions: the answer comes from the REF and not from the `saving` state, for the
             * same reason both write guards read the ref -- a state value cannot answer an arrival
             * inside the very task that raised it, and this predicate is evaluated on the dispatch path
             * as well as during render. The paint still updates, because `saving` changes in the same
             * task and is what schedules the render on which this is re-read.
             *
             * Assumptions: a busy key stays present, enabled, focusable and named, and the key path
             * declines it SILENTLY. That is the 3270 input-inhibit analogue: a key pressed while a turn
             * is outstanding is a valid key pressed early rather than an invalid key, and this program
             * emits no invalid-key sentence at all.
             * @returns {boolean} `true` while this screen's write is outstanding.
             */
            busy: (): boolean => writeInFlight.current,
          },
        }
      : {}),
    ...(card === null
      ? {}
      : {
          PFK12: {
            /** Discards uncommitted edits and redisplays the record, the source's `F12=Cancel`. */
            onInvoke: cancelEdits,
            label: awaitingConfirmation ? CARD_UPDATE_KEY_LABELS.PFK12 : '',
            risk: 'read-only',
          },
        }),
  };

  /*
   * WHY : ⚠️ Alternatives Considered: an unrecognised key does NOTHING here, where this screen used to
   *       coerce it into the Enter arm and submit the form. The source does coerce --
   *       `IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE` at `app/cbl/COCRDUPC.cbl` L422-L424 -- and
   *       reproducing it was the alternative. It is rejected as a documented divergence for two reasons
   *       that only apply to the target. A 3270 keyboard offers a CLOSED set of attention identifiers,
   *       so "any other AID" there means one of about thirty keys that all end the turn anyway; a
   *       browser has no such set, so the same rule makes every keystroke that reaches the document
   *       submit the form -- including keys an assistive technology sends. And the coerced turn would
   *       write nothing yet re-run validation, so a stray key would mark fields red without the operator
   *       having asked for anything, which is a usability and accessibility regression rather than
   *       parity.
   *       Trade-offs: the divergence costs the case the source coerces deliberately, `F5` pressed before
   *       the edits are validated. That case is already answered better here: the save key is not
   *       painted at all until the confirmation turn -- its legend field is `ATTRB=(ASKIP,DRK)` and
   *       un-darkened only at L1315-L1317 -- so there is nothing to press early.
   *       Assumptions: `CCDA-MSG-INVALID-KEY` in `app/cpy/CSMSG01Y.cpy` is NOT surfaced either, because
   *       `COCRDUPC` never emits it: it copies that book for the thank-you literal and its coercion path
   *       sets no message at all. Rendering one would invent a sentence this screen never showed.
   */
  const { bindings, invoke } = usePfKeys(keyHandlers);
  const editable = acceptsEdits(action);

  /**
   * Supplies the refusal presentation a SERVICE-named field carries, if the response named it.
   *
   * Assumptions: this returns nothing at all when the service named no such field, which leaves the
   * form's own rules in charge of that control. `Form.Item` treats an explicit `validateStatus` as an
   * override, so returning a cleared status instead would silence a client-side refusal on every field
   * the service happened not to mention.
   *
   * Assumptions: `DFHRED` applies to a field whose flag is NOT-OK **or** BLANK, which is why both states
   * produce the error status -- `app/cpy/CSSETATY.cpy` L18-L22 tests the two together before moving the
   * colour. Only the `'*'` marker distinguishes them, and that marker is rendered by
   * `fieldRefusalRendering` in `ui/src/layout/fieldHelp.tsx`, which owns it for every transcribed form;
   * the helper this sentence used to name was this screen's own withdrawn copy of half of it.
   * @param {string} field - Form member name, which the service uses as its own field name.
   * @returns {{validateStatus: 'error', help: string} | Record<string, never>} The override props for a
   *   refused field, or an empty object leaving the form's own rules in charge.
   */
  function refusalPropsFor(field: string): { validateStatus: 'error'; help: string } | object {
    const refusal = fieldErrors.get(field);

    return refusal === undefined ? {} : { validateStatus: 'error', help: refusal.message };
  }

  /**
   * Supplies the presentation one entry control carries: its declared width, and any refusal on it.
   *
   * Purpose
   * -------
   * Render the baseline's field-level highlight through the ONE helper that owns it, and size the
   * control from the width its PICTURE clause declares, so both decisions are made the same way here
   * as on every other transcribed form.
   *
   * ⚠️ Refactoring Rationale: the refusal presentation comes from `fieldRefusalRendering` in
   * `ui/src/layout/fieldHelp.tsx`, where this screen carried its own copy of half of it. The copy
   * rendered the `'*'` marker for a BLANK refusal and stopped there -- it never put the error COLOUR
   * into the field, which `app/cpy/CSSETATY.cpy` moves for a not-OK value AND for a blank one at L18-L22,
   * outside the test that adds the marker at L23-L26. So a field holding a value the service rejected
   * got the design system's red BORDER from `validateStatus` and left its text in the base colour,
   * where the reference recolours the field itself. The shared helper is the contract for that
   * asymmetry, and adopting it also brings the marker's `aria-hidden` and its test identifier, both of
   * which the local copy lacked: without the first a screen reader announced a bare asterisk after the
   * value, and the marker is decoration for a refusal that `help` already states in words.
   *
   * ⚠️ Refactoring Rationale: the DECLARED WIDTH is applied, and these controls previously had none --
   * each ran the full measure of the form. That is what put the blank marker at the far side of the
   * viewport: it is the input's suffix, so it sits at the control's right-hand edge, which on a
   * full-width control is roughly a thousand pixels from the two characters it qualifies.
   * `copybookFieldWidthStyle` in `ui/src/layout/recordLayout.ts` states that exact purpose -- keep a
   * transcribed field the width of the data it carries so that what sits at its right-hand edge is
   * adjacent to the value -- and it is a CEILING rather than a fixed size, so a narrow viewport still
   * shrinks the control instead of forcing the page sideways.
   *
   * Assumptions: the width style is spread FIRST and the refusal style second, so a refusal's colour
   * cannot be dropped by the width expression and the two never contend -- they set disjoint
   * properties, and the order states which would win if that ever stopped being true.
   *
   * Assumptions: the result is spread onto the design-system CONTROL and not onto a wrapper, which is
   * the constraint `copybookFieldWidthStyle` records: the theme scopes its custom properties to
   * component class scopes, so the padding term in its measure resolves on an `.ant-input` and returns
   * the empty string on a plain element.
   * @param {string} field - Form member name, which the service uses as its own field name.
   * @param {number} declaredWidth - The field's PICTURE width, from {@link CARD_UPDATE_FIELD_WIDTHS}.
   * @returns {{style: CSSProperties, suffix?: ReactElement}} Props to spread onto the control: its
   *   measure merged with any refusal colour, and the blank marker when the refusal was a blank one.
   * @throws {RangeError} When `declaredWidth` is not a positive integer, from the width helper.
   */
  function entryControlProps(
    field: string,
    declaredWidth: number,
  ): { style: CSSProperties; suffix?: ReactElement } {
    const rendering: FieldRefusalRendering = fieldRefusalRendering(
      fieldErrors.get(field)?.state,
      cssVar,
    );
    /*
     * WHY : ⚠️ Refactoring Rationale: the marker slot is declared to the measure, and only when the
     *       marker is actually rendered. With a suffix present the design system sizes the affix
     *       WRAPPER, whose space the value and the slot then share, so a maximum computed for the value
     *       alone leaves the value short by whatever the slot takes -- measured on a sibling screen's
     *       two-character field as an unreadable record key. On this screen the narrowest marker-bearing
     *       control is the two-character expiry month, which is squarely inside that range.
     *       Assumptions: the allowance is CONDITIONAL on the same test that renders the suffix, so an
     *       accepted field keeps exactly the measure it has always had and only a refused one widens.
     *       That matters because the two states already differ in box shape -- the comment below records
     *       antd moving the variant class onto the wrapper -- so widening both would change every
     *       accepted field on the screen to fix a state none of them are in.
     */
    const style: CSSProperties = {
      ...copybookFieldWidthStyle(
        declaredWidth,
        cssVar,
        rendering.suffix === undefined ? 0 : BLANK_FIELD_MARKER_CHARACTERS,
      ),
      ...rendering.style,
    };

    /*
     * WHY : Assumptions: the suffix is omitted by SPREAD rather than passed as undefined, because
     *       `ui/tsconfig.json` enables `exactOptionalPropertyTypes` -- under which an explicit
     *       `suffix: undefined` is a type error rather than an absent property. It also matters at
     *       runtime here: antd treats the presence of a suffix as a shape change, wrapping the control
     *       in an affix element and moving the variant class onto that wrapper, so passing one
     *       unconditionally would give a refused field and an accepted field two different kinds of box.
     */
    return rendering.suffix === undefined ? { style } : { style, suffix: rendering.suffix };
  }

  /*
   * WHY : ⚠️ Refactoring Rationale: this screen DELEGATES its title band and its key legend to
   *       the shell instead of painting them itself. `ui/src/layout/AppShell.tsx` is mounted as
   *       the authenticated layout route, so the frame is painted once above the outlet rather
   *       than rebuilt per screen; a screen that also painted them would show two title bands
   *       and two legends. The message band stays local, because the shell paints a zone only
   *       when it is delegated and this screen's message is bound to controls in its own body.
   * WHY : ⚠️ Assumptions: the legend is delegated rather than dropped, so the SCREEN keeps
   *       owning the keyboard -- `bindings` and `invoke` come from this screen's own `usePfKeys`
   *       call and travel up unchanged, and an activation of a rendered legend control is
   *       forwarded straight back to `invoke`. The claim that stood here, that the shell "adds its
   *       sign-off key beside them only when this screen leaves that attention identifier free,
   *       which is decided by AID in the shell", is withdrawn: the shell installs NO keyboard
   *       listener at all and offers sign-off as a rendered control, for the reason recorded at
   *       `SHELL_SIGN_OFF_LABEL`. So there is no second listener to stand down and no AID
   *       arbitration anywhere -- this screen's bindings are the only ones on the document while it
   *       is mounted.
   */
  /*
   * WHY : ⚠️ Refactoring Rationale: the row-23 message line is delegated WITH the title band and the
   *       legend. An earlier revision of this delegation withheld it, on the stated ground that this
   *       screen's message is bound to controls in its own body -- but this screen stopped composing a
   *       band when it stopped composing the header, so every sentence it reports through `report`
   *       had nowhere to be painted. Publishing it here restores the one row-23 field the mapset has.
   * WHY : Assumptions: the publication is unconditional and ABOVE both early returns, because a hook
   *       called after one of them would change hook order between renders.
   * WHY : ⚠️ Refactoring Rationale: the REFUSED-SELECTOR turn now delegates the whole frame, where it
   *       published an empty key list and no header or message. The reason recorded for withholding it
   *       -- that "the reference answers an unaddressable selector with `SEND TEXT ... ERASE` rather
   *       than by re-sending the map" -- is false of the program this screen replaces: `COCRDUPC`
   *       contains no `SEND TEXT` at all, and every arm of its send paragraph issues
   *       `EXEC CICS SEND MAP(CCARD-NEXT-MAP)` with `CCRDUPA` (`app/cbl/COCRDUPC.cbl` L1323-L1336). A
   *       turn holding no key is one of those arms and not an exception to them:
   *       `3250-SETUP-INFOMSG` gives `CCUP-DETAILS-NOT-FETCHED` its own row-22 sentence at L1141-L1144,
   *       which only a painted map could carry. So the refusal KEEPS the frame -- identity on rows 1-2,
   *       the row-22 prompt, row 23 reserved and quiet, and the row-24 legend the mapset paints
   *       unconditionally at `app/bms/COCRDUP.bms` L158-L162.
   * WHY : Assumptions: a read in flight still publishes the empty list, because the reference has no
   *       read-in-flight turn to transcribe -- a 3270 turn is serialised, so the region either holds the
   *       record or is not accepting input. That state is a target artefact and nothing in `app/**`
   *       fixes what it paints.
   * WHY : ⚠️ Trade-offs: an empty list is published rather than the member being omitted, and
   *       the reason recorded here -- that this screen binds PF12 as `cancel` while the shell binds PF12
   *       as sign-off "whenever no screen has published keys", so omitting it would put both listeners on
   *       the document and one press would cancel the edit AND end the session -- is withdrawn. The shell
   *       installs NO keyboard listener at all and offers sign-off as a rendered control, for the reason
   *       recorded at `SHELL_SIGN_OFF_LABEL`, so this screen's PF12 is the only PF12 on the document
   *       either way. Both forms render the same legend, none: `PfKeyBar` returns `null` for an empty
   *       binding list. The member is kept in both arms so they differ only in which zones carry content.
   */
  /*
   * WHY : Alternatives Considered: a nested conditional expression in the argument position, which is
   *       what this was while it had two arms. Rejected at three arms because the middle arm carries a
   *       fourth zone the others do not and the reader would have to hold two levels of condition to
   *       see which zones each arm publishes. A named value assigned by an ordinary branch reads as the
   *       three turns it is.
   * WHY : Assumptions: the refused turn is tested BEFORE the read-in-flight turn, and the order is
   *       observable rather than stylistic. `loading` opens `true` and the effect that clears it for a
   *       refused selector runs after the first paint, so testing `loading` first would paint the
   *       erased frame for one commit and then replace it with the refusal frame -- a visible flash of
   *       a frame the reference never paints. Testing the refusal first makes the first commit the
   *       final one.
   */
  let shellSlot: ShellSlot;
  if (selector === null) {
    shellSlot = {
      screen: {
        transactionId: CARD_UPDATE_TRANSACTION_ID,
        programName: CARD_UPDATE_PROGRAM_NAME,
      },
      now: paintedAt,
      /*
       * WHY : Assumptions: row 23 is delegated CARRYING the screen's own message rather than a hard
       *       `null`, and it is `null` until something states a sentence. `3250-SETUP-INFOMSG` moves
       *       `WS-RETURN-MSG` into `ERRMSGO` on every turn (`app/cbl/COCRDUPC.cbl` L1162-L1164), and on
       *       arrival that field is unset -- so the line is reserved and quiet. It is not permanently
       *       quiet: Enter on this turn states `No input received`, for the reason recorded at
       *       {@link submitEdits}, and this is the zone that has to be able to carry it.
       * WHY : Assumptions: the row-22 line is published through the frame's INFORMATION channel rather
       *       than composed in the body below. The mapset declares two message rows -- `INFOMSG` at
       *       `POS=(20,25) COLOR=NEUTRAL` and `ERRMSG` at `POS=(23,1) COLOR=RED`
       *       (`app/bms/COCRDUP.bms` L149-L157) -- and the channel keeps them adjacent in the frame's
       *       pinned zone the way the terminal kept them adjacent on the screen. The sentence is the
       *       source's own for this turn: `WHEN CCUP-DETAILS-NOT-FETCHED SET PROMPT-FOR-SEARCH-KEYS TO
       *       TRUE` (L1141-L1144).
       * WHY : Assumptions: no `severity` is named on the information line, so it takes the appearance
       *       its own BMS field declares. `COLOR=NEUTRAL` is what `ShellInformationSlot` resolves to by
       *       default, so naming one here could only disagree with the mapset.
       */
      message: {
        text: message,
        severity,
        mapset: CARD_UPDATE_MAPSET,
        /*
         * WHY : Assumptions: this arm names the constant rather than going through
         *       {@link informationSlotFor}, and the difference is not stylistic. That helper derives
         *       the sentence from `action`, whose opening value is `SHOW_DETAILS` -- the turn that has
         *       a record -- because {@link CardUpdateAction} deliberately omits the source's
         *       `CCUP-DETAILS-NOT-FETCHED` state for a route that always carries its key. On the one
         *       turn where the route does NOT, the source's own arm for it is
         *       `WHEN CCUP-DETAILS-NOT-FETCHED SET PROMPT-FOR-SEARCH-KEYS TO TRUE`
         *       (`app/cbl/COCRDUPC.cbl` L1141-L1144), which is this constant. Deriving it here would
         *       announce that cards had been found for an account this screen never read.
         */
        information: { text: CARD_UPDATE_MESSAGES.PROMPT_FOR_SEARCH_KEYS.text },
      },
      pfKeys: { keys: bindings, onInvoke: invoke },
    };
  } else if (loading) {
    shellSlot = { pfKeys: { keys: [], onInvoke: invoke } };
  } else {
    shellSlot = {
      screen: {
        transactionId: CARD_UPDATE_TRANSACTION_ID,
        programName: CARD_UPDATE_PROGRAM_NAME,
      },
      now: paintedAt,
      /*
       * WHY : Assumptions: the mapset is named so the delegated band is sized to this screen's own
       *       80-column field rather than the 78 nineteen of the twenty-one mapsets declare; the
       *       measurement and the reason it is a display width and not a content width are recorded at
       *       `CARD_UPDATE_MAPSET`. Omitting it silently painted the line two characters narrow.
       * WHY : ⚠️ Refactoring Rationale: the row-22 INFORMATION line is DELEGATED here, and it was
       *       composed inside this screen's own body. That is not a tidying: a browser measured the
       *       screen-composed band at 775.67-815.67 INSIDE `main`, underneath the sticky pinned zone
       *       spanning 764-860, and `document.elementFromPoint(459, 796)` returned an element the band
       *       did not contain -- so after a successful save the acknowledgement
       *       `Changes committed to database` was FULLY OCCLUDED at `scrollY 0` and appeared only once
       *       the operator scrolled the remaining 52 pixels. An operator saved and saw nothing. The
       *       frame paints this line as a direct child of its pinned zone immediately above row 23,
       *       which is where `app/bms/COCRDUP.bms` puts it -- `INFOMSG` at `POS=(20,25)` above `ERRMSG`
       *       at `POS=(23,1)` (L149-L157) -- so both message rows are again adjacent and both are
       *       above the fold. The sibling `/account/update` screen already publishes this way.
       * WHY : Assumptions: the sentence is DERIVED from the turn on every render rather than latched,
       *       which is the source's own arrangement -- `3250-SETUP-INFOMSG` is one `EVALUATE` over the
       *       change action, re-evaluated on every send (`app/cbl/COCRDUPC.cbl` L1138-L1163). So there
       *       is no stale-advisory state to clear, and the frame treats an information-only change as a
       *       real change, which is what lets the acknowledgement replace the confirmation prompt.
       */
      message: {
        text: message,
        severity,
        mapset: CARD_UPDATE_MAPSET,
        information: informationSlotFor(action),
      },
      pfKeys: { keys: bindings, onInvoke: invoke },
    };
  }
  useShellSlot(shellSlot);

  if (selector === null) {
    /*
     * WHY : ⚠️ Refactoring Rationale: this turn renders a SCREEN inside the frame, where it rendered
     *       an `antd` `Result` that replaced the frame with a centred error page. The frame is what the
     *       reference paints on this turn -- `COCRDUPC` has no `SEND TEXT` anywhere and answers a turn
     *       holding no key by sending `CCRDUPA` like every other turn, with its own row-22 prompt at
     *       `app/cbl/COCRDUPC.cbl` L1141-L1144 -- so a page that erases rows 1, 2, 22, 23 and 24 states
     *       less than the terminal did and states it somewhere else. The sibling detail screen already
     *       keeps its frame for the same condition, so the two halves of one record disagreed about
     *       what a bad address looks like.
     * WHY : Assumptions: the row-23 sentence and the row-24 legend are NOT rendered here, because both
     *       are delegated above and rendering either would put a second copy of it on the document.
     *       `PfKeyBar` renders each binding as a real `Button` (`ui/src/layout/PfKeyBar.tsx` L497-L556),
     *       so the exit control the `Result` offered in its own `extra` slot is present in the frame,
     *       once, carrying the mapset's own `F3=Exit` legend.
     * WHY : Assumptions: the heading goes through `ScreenTitle` rather than a chosen level, so this turn
     *       carries the same real heading element the record turn does -- an operator navigating by
     *       heading finds the screen rather than a page with none.
     */
    return (
      <Flex vertical gap="large">
        <ScreenTitle>{CARD_UPDATE_TITLE}</ScreenTitle>
        {/*
         * Assumptions: this is the one sentence on the turn that is NOT the reference's, and it is
         * rendered as body text rather than through either message line for that reason. The selector
         * is a target construct -- the baseline received two typed key fields and never held an
         * address that could be malformed -- so the guidance has no BMS field to stand in for, and
         * putting it in one would claim a row the mapset fills with something else.
         */}
        <Typography.Paragraph>{CARD_UPDATE_INVALID_LINK_GUIDANCE}</Typography.Paragraph>
      </Flex>
    );
  }
  if (loading) {
    return <Spin size="large" />;
  }

  return (
    <Flex vertical gap="large">
      {/*
       * Assumptions: the header band is composed here because both of its values belong to this
       * screen -- the transaction identifier and the program name rows 1 and 2 of the 3270 screen
       * painted -- and `ScreenHeaderProps` requires both, so no shell could supply them.
       */}
      {/*
       * WHY : Refactoring Rationale: `now` is supplied. It was omitted here, and at every other call
       *       site, although `ScreenHeader`'s contract states that the composing caller must pass a
       *       SERVER-derived instant -- so the band fell through to `dayjs()` and displayed the
       *       BROWSER's clock. The baseline read one region clock for every terminal
       *       (`FUNCTION CURRENT-DATE` at `app/cbl/COSGN00C.cbl:179`), so the omission meant two
       *       operators looking at one record could read two different dates across midnight.
       *       Assumptions: the value comes from a hook rather than from a shell component. Both of the
       *       band's other inputs are this screen's own, as the note above records, so a shell could
       *       not render the band on this screen's behalf; the hook supplies the one value that is
       *       NOT screen-specific without inventing a component to hold it.
       */}
      <ScreenTitle>{CARD_UPDATE_TITLE}</ScreenTitle>
      {/*
       * Refactoring Rationale: `onFinish` runs the VALIDATE arm, not the write. The source screen's
       * Enter key edits the fields and asks for confirmation, and only the later PF5 turn writes, so
       * submitting the form is the first of those two and `save` is reached from the PF5 binding.
       */}
      {/*
       * WHY : Trade-offs: the fields are grouped by a FORM with stacked labels, and the mapset's
       *       absolute character positions are deliberately not reproduced. `app/bms/COCRDUP.bms`
       *       declares `DFHMDI SIZE=(24,80)` and gives all 34 of its `DFHMDF` fields an explicit
       *       `POS=(row,col)`, so the source layout is a fixed 24-by-80 character grid. What is kept
       *       is what carries meaning: the field GROUPING, the READING ORDER and the TAB ORDER, each
       *       label's verbatim text, and each control's `maxLength` taken from the field's own
       *       `LENGTH=`. What is given up is pixel-for-character positioning. Reproducing the grid
       *       would demand a fixed-width canvas that cannot reflow, which is hostile to a screen
       *       reader -- absolute positioning divorces the visual order from the DOM order -- and
       *       cannot be made responsive at any viewport the design system supports. The six
       *       `LENGTH=0` stopper fields at L89, L101, L111, L121, L140 and L147 are dropped for the
       *       same reason: they are a 3270 field-DELIMITING device with no browser analogue, not
       *       content. This is the documented deviation the migration plan records as gap G1.
       */}
      {/*
       * WHY : ⚠️ Refactoring Rationale: the outstanding write is STATED on the region, and it was
       *       stated nowhere at all before -- `saving` was held and read only by the two guards, so a
       *       screen reader was told nothing between the confirmation and the acknowledgement. That
       *       gap matters more now the write is single-flight: a second activation is deliberately
       *       ignored, and without this the operator has no way to tell "working" from "the key did
       *       nothing".
       *       Assumptions: `aria-busy` is ARIA state and not a sentence, so it carries the SILENT half
       *       only: it tells assistive technology the region is mid-change, which suppresses chatter
       *       rather than producing any.
       * WHY : ⚠️ Refactoring Rationale: the audible half is now stated too, through the live region
       *       below, where this screen previously recorded the sentence as a catalogue gap. It is not a
       *       gap any more -- `ui/src/messages/messages.ts` exports `REQUEST_IN_PROGRESS`, an authored
       *       operator sentence registered and width-checked alongside the transcribed catalogue -- so
       *       the note claiming otherwise was stale and is withdrawn rather than left to mislead. The
       *       sentence is still not composed here: it is imported, which is the rule the note was
       *       protecting.
       * WHY : ⚠️ Assumptions: the sentence is AUTHORED rather than transcribed, and that is admissible
       *       here precisely because the reference has nothing to transcribe. A 3270 terminal inhibits
       *       input for the duration of a turn and says so through the keyboard itself, so
       *       `app/cbl/COCRDUPC.cbl` never writes a "working" message -- there was no moment at which
       *       the operator could have read one. A browser has no such interlock, so the state the
       *       hardware used to carry has to be said out loud, and Rule T8 is not in tension with that:
       *       it governs a string with a mainframe source, and this one has none.
       */}
      {/*
       * WHY : ⚠️ Refactoring Rationale: the design system's REQUIRED MARK is switched off, and it was on.
       *       That mark is an always-on red asterisk prefixed to a label, and this application already
       *       carries a red asterisk with a different meaning: the baseline's blank-field marker, which
       *       `app/cpy/CSSETATY.cpy` L23-L26 writes into a field only when that field is BLANK on a
       *       refused turn. So the same glyph stated "this field must be filled in" beside the label and
       *       "this field is empty and was refused" beside the value, on one form -- and the always-on
       *       one is the wrong claim to make in the reference's vocabulary, because a 3270 map has no
       *       required-field concept at all. `app/bms/COCRDUP.bms` paints no such marker on any of its
       *       34 fields; what it paints is the four `UNPROT` entry fields themselves.
       *       Assumptions: nothing about the RULES changes -- every `required` rule below still refuses
       *       a blank entry with the program's own sentence, and antd still publishes `aria-required`
       *       from those rules, so the obligation is still announced. Only the duplicated glyph goes.
       *       Alternatives Considered: keeping the mark and dropping the blank marker instead. Rejected
       *       because the blank marker is the TRANSCRIBED one -- a real field-level behaviour of the
       *       reference, asserted by the parity suite -- while the required mark is the design system's
       *       default chrome.
       */}
      <Form<CardFormValues>
        form={form}
        layout="vertical"
        onFinish={process}
        onFinishFailed={refuseSubmission}
        requiredMark={false}
        {...busyProps(saving)}
      >
        {/*
         * WHY : ⚠️ Refactoring Rationale: the live region is the FIRST child of the form and is rendered
         *       on every turn, empty while nothing is outstanding. Both properties are load-bearing:
         *       `ui/src/layout/fieldHelp.tsx` records that a live region has to be in the accessibility
         *       tree BEFORE its content changes for the change to be announced, so a region mounted
         *       with its sentence already in place is frequently treated as initial content and read by
         *       nothing -- which would lose the first transition, the one that matters.
         * WHY : Assumptions: the visible half is not duplicated here. The save control's own busy state
         *       and the row-23 line already carry it, and this region is visually hidden by the helper
         *       for that reason.
         */}
        {busyAnnouncement(saving ? REQUEST_IN_PROGRESS : undefined)}
        {/*
         * WHY : ⚠️ Alternatives Considered: the account and card numbers are RENDERED, as protected
         *       controls, and this screen rendered neither before -- it defined both labels and used
         *       them nowhere, so two of the mapset's six data fields were missing and an operator could
         *       not see which card was being edited. The two candidates were a plain text run and a
         *       protected control. The control is chosen because the mapset declares them as fields, the
         *       program re-protects them on this turn rather than removing them
         *       (`MOVE DFHBMPRF TO ACCTSIDA/CARDSIDA` at `app/cbl/COCRDUPC.cbl` L1183-L1185), the design
         *       system's mapping forbids a raw element where `Input` exists, and the sibling card-detail
         *       screen already paints the same two as controls.
         * WHY : ⚠️ Alternatives Considered: `readOnly` rather than `disabled`, and the difference is
         *       reachability. A disabled input leaves the focus order and the accessibility tree, so a
         *       screen reader would not announce the identity of the record being edited and the keyboard
         *       could not reach it. A 3270 protected field is readable and cursor-addressable and refuses
         *       only TYPING -- `MOVE -1 TO <field>L` positions the cursor onto one -- so `readOnly` is
         *       the faithful mapping and `disabled` claims more than the mapset does.
         * WHY : Assumptions: both are carried as digits-only STRINGS, never numbers. The source declares
         *       each twice over the same storage -- `CC-ACCT-ID PIC X(11)` redefined as `PIC 9(11)` and
         *       `CC-CARD-NUM PIC X(16)` redefined as `PIC 9(16)` in `app/cpy/CVCRD01Y.cpy` L44-L47 -- and
         *       treats the character form as the wire form, the numeric form only for arithmetic. An
         *       11-digit account and a 16-digit card both exceed what a double represents exactly, and
         *       a numeric type would also drop the leading zeros the fixture `00000000011` carries.
         * WHY : Assumptions: the card number shown is whatever the contract published for this route --
         *       `displayCardNumber`, masked to its last four digits by AAP section 0.4.1.9 -- and it is
         *       rendered as received. Nothing here unmasks, reconstructs or re-derives a full number, and
         *       the administrative detail operation is the only one that publishes one.
         */}
        <Form.Item
          label={CARD_UPDATE_FIELD_LABELS.accountNumber}
          htmlFor={cardUpdateFieldDomId('accountNumber')}
        >
          <Input
            id={cardUpdateFieldDomId('accountNumber')}
            value={card?.accountId ?? ''}
            maxLength={CARD_UPDATE_FIELD_WIDTHS.accountNumber}
            readOnly
            style={copybookFieldWidthStyle(CARD_UPDATE_FIELD_WIDTHS.accountNumber, cssVar)}
          />
        </Form.Item>
        <Form.Item
          label={CARD_UPDATE_FIELD_LABELS.cardNumber}
          htmlFor={cardUpdateFieldDomId('cardNumber')}
        >
          <Input
            id={cardUpdateFieldDomId('cardNumber')}
            value={card?.displayCardNumber ?? ''}
            maxLength={CARD_UPDATE_FIELD_WIDTHS.cardNumber}
            readOnly
            style={copybookFieldWidthStyle(CARD_UPDATE_FIELD_WIDTHS.cardNumber, cssVar)}
          />
        </Form.Item>
        {/*
         * Refactoring Rationale: the length rule is gone and `maxLength` alone carries the constraint.
         * The rule could never fire -- the control refuses a 51st character -- so its message was
         * unreachable text with no source in the baseline, and ADR-006 already states that an input's
         * maximum length IS its copybook picture width, which `CARD-EMBOSSED-NAME PIC X(50)` fixes.
         * Assumptions: both remaining refusals are the source program's own sentences, and the
         * alphabetic rule is one the earlier revision omitted entirely.
         */}
        {/*
         * WHY : ⚠️ Alternatives Considered: the opening cursor is placed on the NAME control, and the
         *       mapset appears to disagree -- `ACCTSID` is the field carrying `IC` at
         *       `app/bms/COCRDUP.bms` L84. It is not a disagreement: the program overrides the static
         *       attribute at run time and positions the cursor itself with `MOVE -1 TO <field>L`
         *       (`app/cbl/COCRDUPC.cbl` L1211-L1235), and its first arm is
         *       `WHEN FOUND-CARDS-FOR-ACCOUNT MOVE -1 TO CRDNAMEL` -- which is precisely the turn this
         *       route always opens on, because the selector is already sealed into it. The other
         *       candidate, honouring `IC` and opening on the account number, was rejected because that
         *       control is protected on this turn (L1183-L1184): it would open the screen with the cursor
         *       on a field that refuses typing, which is neither what the mapset intends nor what the
         *       program does. `IC` describes the key-entry turn this route cannot reach.
         */}
        <Form.Item
          label={CARD_UPDATE_FIELD_LABELS.nameOnCard}
          name="embossedName"
          rules={[
            { required: true, message: CARD_UPDATE_MESSAGES.WS_PROMPT_FOR_NAME.text },
            {
              /*
               * WHY : ⚠️ Refactoring Rationale: the alphabet test and the DECLARED WIDTH test are one
               *       rule, where the alphabet was tested alone. `maxLength` was carrying the width on
               *       its own, and it counts the wrong unit: a browser counts UTF-16 code units, while
               *       `CARD-EMBOSSED-NAME PIC X(50)` is fifty BYTES of a fixed-width record. A measured
               *       consequence elsewhere in this application is that `Unicode Emoji` text consumes
               *       18 of a 20-unit allowance for 16 characters, so the visible capacity of a name
               *       field halves as soon as the operator types outside the Basic Latin block.
               *       `fitsDeclaredWidth` in `ui/src/messages/messages.ts` is the shared check for
               *       exactly this: it normalises first and then requires the value to fit in code
               *       POINTS and in UTF-8 BYTES, which is the conservative reading a fixed-width record
               *       needs, and its own documentation states it is meant to run ALONGSIDE `maxLength`
               *       rather than instead of it. Both are therefore kept: the attribute stops the
               *       keystroke, and this refuses the turn.
               * WHY : ⚠️ Assumptions: the two conditions share ONE sentence, and the sentence is the
               *       program's own `WS-NAME-MUST-BE-ALPHA` at `app/cbl/COCRDUPC.cbl` L184-L185. That is
               *       honest rather than convenient here, because on this field the width term can only
               *       be reached by a value the alphabet term already refuses: the accepted set is the
               *       52 ASCII letters and the space that L255-L257 declares and L823-L828 tests, and
               *       every one of those is one unit, one code point and one byte -- so fifty of them
               *       fit exactly and nothing that satisfies the alphabet can overflow the field. A
               *       value that overflows in bytes therefore contains a character outside the
               *       alphabet, which is precisely what this sentence says. Inventing a second sentence
               *       for a state that cannot occur without the first would put text on the screen the
               *       reference never shows.
               * WHY : Alternatives Considered: a third rule carrying its own width message. Rejected on
               *       the reasoning this file already applied when it deleted an earlier length rule:
               *       an unreachable message is unreachable text. Also considered: checking the width
               *       at the wire boundary instead of in the form. Rejected because a refusal belongs
               *       on the field the operator can fix, and the wire boundary has no field to mark.
               */
              /**
               * Refuses an embossed name that is not alphabetic or does not fit its declared width.
               *
               * Assumptions: the function is SYNCHRONOUS and hands back a settled promise, rather than
               * being declared `async`. `Form.Item` reads a rejected promise as the refusal either way,
               * and an `async` body with nothing to await advertises work this check does not do -- both
               * terms are pure string tests over a value already in memory.
               * @param {unknown} _rule - The rule the form is applying, which this check does not read.
               * @param {unknown} value - The entered value, typed `unknown` because the form supplies
               *   whatever the control produced.
               * @returns {Promise<void>} A resolved promise when the value is acceptable, and a promise
               *   rejected with the program's own sentence when it is not.
               */
              validator: (_rule: unknown, value: unknown): Promise<void> => {
                const entered = typeof value === 'string' ? value : '';
                if (
                  EMBOSSED_NAME_PATTERN.test(entered) &&
                  fitsDeclaredWidth(entered, CARD_UPDATE_FIELD_WIDTHS.embossedName)
                ) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error(CARD_UPDATE_MESSAGES.WS_NAME_MUST_BE_ALPHA.text));
              },
            },
          ]}
          {...refusalPropsFor('embossedName')}
        >
          <Input
            ref={nameControl}
            maxLength={CARD_UPDATE_FIELD_WIDTHS.embossedName}
            autoFocus
            {...entryControlProps('embossedName', CARD_UPDATE_FIELD_WIDTHS.embossedName)}
            {...(editable ? {} : { readOnly: true })}
          />
        </Form.Item>
        {/*
         * Assumptions: the month and the year are two fields answering to two different refusals, and
         * that is the baseline's own arrangement rather than a decomposition chosen here: it answers a
         * bad month with 'Card expiry month must be between 1 and 12' at app/cbl/COCRDUPC.cbl:197-198
         * and a bad year with 'Invalid card expiry year' at :199-200, from two separate edit
         * paragraphs. One combined field could carry only one of those two messages.
         */}
        {/*
         * Refactoring Rationale: all four refusal sentences are now the source program's own, and the
         * two that were closest are the reason this mattered: an earlier revision rendered
         * `Card expiry month must be between 1 and 12.` and `Invalid card expiry year.`, each with a
         * trailing full stop the COBOL literals at `app/cbl/COCRDUPC.cbl` L198 and L200 do not carry.
         * A one-character divergence reads as correct in review, which is exactly why the text is
         * taken from the catalog rather than retyped.
         * Assumptions: the month and the year stay two controls answering to two refusals, because the
         * source declares two separate sentences for them; one combined control could carry only one.
         * The separator the mapset paints between them is rendered between the two labels.
         */}
        {/*
         * WHY : ⚠️ Refactoring Rationale: the two parts and the separator between them are laid out
         *       INLINE, under one painted caption, where they used to stack vertically. They stacked
         *       because they were two SIBLING form items and the separator was rendered as the second
         *       item's own label -- and sibling form items stack by design, so the arrangement could
         *       never have been horizontal. `app/bms/COCRDUP.bms` settles what the arrangement should
         *       be, and it settles it on ONE row: L123-L126 paints the caption at row 15 column 4 over
         *       `LENGTH=20`, `EXPMON` at L127-L131 sits at row 15 column 25 over `LENGTH=2`, the
         *       anonymous `INITIAL='/'` at L132-L134 at row 15 column 28 over `LENGTH=1`, and `EXPYEAR`
         *       at L135-L139 at row 15 column 30 over `LENGTH=4`. Four fields, one row, in that order.
         * WHY : ⚠️ Assumptions: AAP design gap G1 surrenders pixel-for-character POSITION and commits to
         *       preserving field GROUPING and reading order, so a caption and two parts the mapset paints
         *       side by side are a group whose members belong on one line. A browser review measured the
         *       consequence of the sibling arrangement directly: the month, the `/` and the year stacked
         *       at 375, 768, 1280 and 1920 alike, while the paired account-update screen rendered its
         *       own three-part dates inline at every one of those widths -- two screens rendering the
         *       same construct two different ways, which is the divergence the review reported.
         * WHY : ⚠️ Assumptions: the two parts stay TWO controls, which is why the group is a wrapper
         *       rather than a combined field. The comments above record the reason -- two separate edit
         *       paragraphs and two separate sentences -- and both survive here unchanged: each part keeps
         *       its own `name`, its own rules and its own `refusalPropsFor`, so each still marks itself
         *       and still carries its own refusal.
         *       Alternatives Considered: giving the separator its own bare form item beside the other
         *       two, so all three sit in one row of the surrounding layout. Rejected because the `/` is
         *       painted decoration -- an anonymous field with an `INITIAL` and no name -- and a form item
         *       is the wrapper for a FIELD, so wrapping a separator in one asserts a field that does not
         *       exist and gives it a label column of its own.
         *       Alternatives Considered: `Flex` with a gap instead of `Space.Compact`. Rejected because
         *       `Space.Compact` is what `ui/src/screens/accountUpdate/index.tsx` already uses for
         *       exactly this construct, and the point of the change is that the two screens stop
         *       rendering one construct two ways.
         */}
        <Form.Item
          htmlFor={cardUpdateFieldDomId('expirationMonth')}
          label={<span id={EXPIRY_GROUP_CAPTION_ID}>{CARD_UPDATE_FIELD_LABELS.expiryDate}</span>}
        >
          {/*
           * WHY : ⚠️ Assumptions: the group is named by `aria-labelledby` pointing at the painted
           *       caption, and the caption's `for` points back at the group's first part. Both halves
           *       are needed: without the group, two consecutive controls under a shared caption are
           *       announced with nothing tying them together; without the `for`, the caption is a label
           *       associated with no field, which is the finding an accessibility audit raised against
           *       the equivalent groups on the account-update screen.
           *       Assumptions: pointing the caption at the month costs the month nothing -- its own
           *       `aria-label` still supplies its accessible name, because an explicit name takes
           *       precedence over an associated label, and the case that tells the two parts apart
           *       asserts exactly those names.
           */}
          <Space.Compact role="group" aria-labelledby={EXPIRY_GROUP_CAPTION_ID}>
            {/*
             * WHY : Assumptions: the inner items carry no `label` at all rather than an empty one. In
             *       this form's vertical layout an item given `label=""` still reserves its label row,
             *       which would put two blank rows above the parts and undo the single-line arrangement
             *       this restructure exists to produce.
             */}
            <Form.Item
              name="expirationMonth"
              rules={[
                { required: true, message: CARD_UPDATE_MESSAGES.CARD_EXPIRY_MONTH_NOT_VALID.text },
                {
                  pattern: EXPIRATION_MONTH_PATTERN,
                  message: CARD_UPDATE_MESSAGES.CARD_EXPIRY_MONTH_NOT_VALID.text,
                },
              ]}
              {...refusalPropsFor('expirationMonth')}
            >
              <Input
                id={cardUpdateFieldDomId('expirationMonth')}
                aria-label={`${CARD_UPDATE_FIELD_LABELS.expiryDate}${CARD_UPDATE_PART_NAMES.month}`}
                maxLength={CARD_UPDATE_FIELD_WIDTHS.expirationMonth}
                inputMode="numeric"
                {...entryControlProps('expirationMonth', CARD_UPDATE_FIELD_WIDTHS.expirationMonth)}
                {...(editable ? {} : { readOnly: true })}
              />
            </Form.Item>
            {/*
             * WHY : Assumptions: the separator is rendered as secondary text, which is the tone the
             *       mapset gives it -- an anonymous protected field with no colour operand of its own,
             *       against two `HILIGHT=UNDERLINE` entry fields either side. It is not hidden from
             *       assistive technology, because a slash between a month and a year is the same
             *       information for a listener as for a reader.
             */}
            <Typography.Text type="secondary">
              {CARD_UPDATE_FIELD_LABELS.expirySeparator}
            </Typography.Text>
            <Form.Item
              name="expirationYear"
              rules={[
                { required: true, message: CARD_UPDATE_MESSAGES.CARD_EXPIRY_YEAR_NOT_VALID.text },
                {
                  pattern: EXPIRATION_YEAR_PATTERN,
                  message: CARD_UPDATE_MESSAGES.CARD_EXPIRY_YEAR_NOT_VALID.text,
                },
              ]}
              {...refusalPropsFor('expirationYear')}
            >
              <Input
                id={cardUpdateFieldDomId('expirationYear')}
                aria-label={`${CARD_UPDATE_FIELD_LABELS.expiryDate}${CARD_UPDATE_PART_NAMES.year}`}
                maxLength={CARD_UPDATE_FIELD_WIDTHS.expirationYear}
                inputMode="numeric"
                {...entryControlProps('expirationYear', CARD_UPDATE_FIELD_WIDTHS.expirationYear)}
                {...(editable ? {} : { readOnly: true })}
              />
            </Form.Item>
          </Space.Compact>
        </Form.Item>
        {/*
         * WHY : ⚠️ Refactoring Rationale: the active flag is a one-character ENTRY, not a chooser. It was
         *       a `Select` over two options, which cannot carry the mapset's constraint at all: the field
         *       is `LENGTH=1` `ATTRB=(UNPROT)` at `app/bms/COCRDUP.bms` L117-L120 over
         *       `CARD-ACTIVE-STATUS PIC X(01)`, and a chooser has no `maxLength` for that width to become
         *       -- so the one figure Transformation Rule T1 makes normative for this control had nowhere
         *       to land, and the refusal sentence beside it became unreachable because a chooser cannot
         *       hold a value outside its options.
         *       Alternatives Considered: keeping the chooser and accepting the widened entry surface. It
         *       is the friendlier control, and it is rejected because it silently removes a refusal the
         *       parity suite asserts -- `Card Active Status must be Y or N` is a real sentence this
         *       program emits (L196), reachable only if the operator can type something that is neither.
         * WHY : Assumptions: the accepted domain is exactly `Y` and `N`, from
         *       `88 FLG-YES-NO-VALID VALUES 'Y','N'` in the source's validation storage, and the refusal
         *       fires when the value is blank OR outside that pair -- `1240-EDIT-CARDSTATUS` sets the same
         *       sentence from both arms (L849-L871).
         */}
        <Form.Item
          label={CARD_UPDATE_FIELD_LABELS.cardActive}
          name="activeStatus"
          rules={[
            { required: true, message: CARD_UPDATE_MESSAGES.CARD_STATUS_MUST_BE_YES_NO.text },
            {
              pattern: ACTIVE_STATUS_PATTERN,
              message: CARD_UPDATE_MESSAGES.CARD_STATUS_MUST_BE_YES_NO.text,
            },
          ]}
          {...refusalPropsFor('activeStatus')}
        >
          <Input
            maxLength={CARD_UPDATE_FIELD_WIDTHS.activeStatus}
            {...entryControlProps('activeStatus', CARD_UPDATE_FIELD_WIDTHS.activeStatus)}
            {...(editable ? {} : { readOnly: true })}
          />
        </Form.Item>
      </Form>
      {/*
       * Refactoring Rationale: the bespoke `Cancel` and `Save` controls are gone, and the delegated key
       * legend carries both as F12 and F5. Keeping them would have put two controls behind each action
       * with two chances to diverge, and the `Save` control specifically wrote without the
       * confirmation turn the source requires -- so the mapset's second legend field, which exists
       * only to reveal those two keys, would have had nothing to reveal.
       * Assumptions: no legend colour is delegated, which `app/bms/COCRDUP.bms` L159 and L164 confirm --
       * both of this screen's legend fields are `COLOR=YELLOW`, the majority the slot already defaults
       * to.
       */}
      {/*
       * WHY : ⚠️ Refactoring Rationale: the row-20 information line is no longer composed HERE, and the
       *       reason it moved is a measurement rather than a preference. A browser found the band this
       *       block used to render at rect 775.67-815.67 INSIDE `main`, underneath the sticky pinned
       *       zone spanning 764-860: `document.elementFromPoint(459, 796)` returned an element the band
       *       did not contain, so the acknowledgement `Changes committed to database` was fully occluded
       *       at `scrollY 0` and became readable only after the operator scrolled the remaining 52
       *       pixels. The operator saved and saw nothing. The sentence is now published through the
       *       frame's row-22 channel in the `useShellSlot` call above, which renders it as a direct
       *       child of the pinned zone immediately above row 23 -- the arrangement
       *       `app/bms/COCRDUP.bms` declares, `INFOMSG` at `POS=(20,25)` above `ERRMSG` at `POS=(23,1)`
       *       (L149-L157). Nothing about WHICH sentence is stated changed: the derivation is the same
       *       `3250-SETUP-INFOMSG` transcription, at {@link informationSlotFor}.
       * WHY : Assumptions: no wrapper is left behind where the band was. The band was also serving as
       *       the confirmation overlay's ANCHOR, and the overlay below needs none -- see the note on the
       *       `Modal`, which positions itself against the viewport rather than against an element -- so
       *       an empty anchor would be an element with no content, no box and no purpose.
       */}
      {/*
       * WHY : ⚠️ Refactoring Rationale: the save confirmation is an antd `Modal` and was a `Popconfirm`.
       *       The reason is mechanical and no amount of wiring could have reached it from the previous
       *       component: `Popconfirm` renders through the tooltip primitive, whose overlay hardcodes
       *       `role: "tooltip"` at `ui/node_modules/@rc-component/tooltip/es/Popup.js` with no prop path
       *       to override it, and it neither traps focus nor carries `aria-modal`. So the one guard
       *       standing between a keypress and an irreversible rewrite of a card was announced to a
       *       screen reader as a tooltip. `Modal` renders through the dialog primitive, which sets
       *       `role="dialog"`, `aria-modal="true"` and `aria-labelledby` from the title
       *       (`ui/node_modules/@rc-component/dialog/es/Dialog/Content/Panel.js` L113-L115), locks focus
       *       inside itself while open and restores it on close. The sibling `/users/:id/delete`
       *       confirmation was migrated the same way and measured in a browser as `role="dialog"`,
       *       `aria-modal="true"`, focus on Cancel and zero requests after `Escape`.
       *       Alternatives Considered: keeping the `Popconfirm` and setting `role="dialog"` through a
       *       pass-through prop -- there is none, and an attribute hand-set on the anchor cannot give
       *       the overlay a focus trap. Also considered: the imperative `Modal.confirm` API -- rejected
       *       because its content would be composed outside this render tree and would not re-read the
       *       record, so a dialog opened before a value changed would name a stale one.
       * WHY : ⚠️ Assumptions: the migration also retires the ANCHORING problem the previous component
       *       had rather than merely moving it. A `Popconfirm` measures its target before placing
       *       itself, and a target with no CSS box left it parked at its pre-alignment sentinel --
       *       measured at x=-14400, y=-9010 on this very screen, an unreachable confirmation with
       *       perfectly correct markup. A `Modal` is positioned against the viewport and has no target
       *       at all, so the failure mode does not exist for it.
       * WHY : Assumptions: the surface stays CONTROLLED by this screen's own state, so the only thing
       *       that opens it is the save key, for the reason recorded at `confirmingSave`. It is also
       *       gated on `awaitingConfirmation`, so a dialog cannot be open over a turn that has no
       *       validated edits to commit.
       * WHY : ⚠️ Refactoring Rationale: the document-level `Escape` listener this screen used to install
       *       is GONE, and its removal is the point rather than a casualty. It existed because the
       *       previous component was controlled AND declared `trigger={[]}`, which switched off the
       *       library's own dismissal -- browser validation measured `Escape`, a second `Escape`, a
       *       `Tab` then a third `Escape` and a click outside all leaving the overlay open, so the only
       *       ways out were its two buttons or `F12`, and `F12` DISCARDS the edits. `Modal` routes
       *       `Escape` to `onCancel` itself: the installed `@rc-component/dialog` hands an `onEsc`
       *       callback down to the portal, and that callback calls `onClose` when its dialog is the
       *       TOP one and `keyboard` is on (`DialogWrap.js` L45-L54) -- so the withdrawal is now the
       *       library's, it reaches the same
       *       {@link dismissConfirmation} the Cancel control does -- including the focus hand-back a
       *       bare `setConfirmingSave(false)` did not perform -- and the shared `Escape` key is left
       *       alone on every turn where no confirmation is open.
       * WHY : ⚠️ Assumptions: the TITLE is the catalogued sentence the program paints on this exact turn,
       *       `PROMPT-FOR-CONFIRMATION` at `app/cbl/COCRDUPC.cbl` L166-L167, so the ask is the
       *       reference's own words and this screen authors none. That sentence is deliberately on the
       *       glass twice while the dialog is open -- once on the row-22 line the frame paints, once as
       *       the title -- because the alternative was a second wording for one question.
       *       `ui/src/screens/refTypeEdit/index.tsx` resolves the identical choice the identical way.
       * WHY : ⚠️ Assumptions: the record is named as DATA under the mapset's own field labels, so no
       *       prose is invented for it either -- `Account Number    :` at `app/bms/COCRDUP.bms` L80-L83
       *       and `Card Number       :` at L92-L95, each followed by the value the read returned. The
       *       card number is the MASKED rendering the contract published; nothing here reconstructs a
       *       full one, and AAP section 0.4.1.9 makes that absolute. It is rendered in the fixed-pitch
       *       token so the two identifiers read as the digit runs they are.
       * WHY : ⚠️ Assumptions: both actions point at that element with `aria-describedby`, so the record
       *       is announced with whichever action the operator lands on and not only when the surface as
       *       a whole is read.
       * WHY : ⚠️ Assumptions: initial focus is placed on the DECLINING choice through
       *       `cancelButtonProps`, so a bare Enter on arrival withdraws and writes nothing and
       *       committing is a deliberate second act. Nothing in this screen focuses anything after the
       *       dialog opens: the only `focus()` calls are in {@link save} and
       *       {@link dismissConfirmation}, both of which run as the dialog CLOSES. A measurement on a
       *       sibling screen recorded the opposite arrangement -- a focus call placed after opening the
       *       prompt -- stealing focus from the declining choice and leaving it on the destructive one.
       *       Alternatives Considered: focusing the accept, which is what the bill-pay confirmation
       *       does. Rejected outright here: one keystroke would then rewrite a card. Assumptions: this
       *       is STRICTER than the terminal rather than looser -- there the confirmation turn accepted
       *       Enter as the process key, which validated again and wrote nothing.
       * WHY : ⚠️ Assumptions: the surface is NOT `closable`, and withdrawing the corner dismiss control
       *       is what makes the focus placement above structural rather than incidental. The dialog
       *       primitive locks focus inside the panel and, whenever focus is not already in there when
       *       the lock engages, pulls it to the panel's FIRST focusable node
       *       (`@rc-component/util/lib/Dom/focus.js` `syncFocus`, `focusableList[0]`). With a corner
       *       dismiss rendered, that first node is the corner dismiss -- measured here as
       *       `activeElement` landing on `button.ant-modal-close` instead of on the declining action,
       *       which is a control whose purpose an operator has to infer from an icon. Without it the
       *       first focusable node IS the declining action, so both mechanisms agree.
       *       Assumptions: nothing is lost by withdrawing it. The reference's confirmation turn offers
       *       exactly two answers -- `PFK05` commits and `PFK12` abandons (`app/cbl/COCRDUPC.cbl`
       *       L414-L419) -- and this dialog states both as named actions; a third, unlabelled exit was
       *       additive, and `Escape` still performs the same withdrawal for anyone reaching for one.
       * WHY : ⚠️ Assumptions: the accept carries `danger` ON TOP of the dialog's default primary type
       *       rather than the legacy `okType="danger"` this surface used before.
       *       `convertLegacyProps('danger')` yields `danger: true` with the DEFAULT variant
       *       (`ui/node_modules/antd/lib/button/buttonHelpers.js` L21-L30), so the committing control
       *       rendered as an outlined red button beside a neutral Cancel of the same shape -- a browser
       *       measured the destructive action as the smaller and quieter control in every dangerous
       *       confirmation in this application. `Modal` renders its accept with `type={okType}`, whose
       *       default is `primary`, so `danger` alone now composes to the solid destructive variant a
       *       browser measured on the migrated sibling as `ant-btn-primary ant-btn-dangerous
       *       ant-btn-variant-solid` on `rgb(207, 19, 34)`.
       *       Assumptions: the SIZE is left alone. Both actions render at the theme's own
       *       `controlHeight`, which `CONTROL_SCALE_DECISION` in `ui/src/theme/tokens.ts` records as
       *       clearing the 24-pixel AA target-size floor of success criterion 2.5.8.
       * WHY : ⚠️ Assumptions: only the ACCEPT is wrapped in the destructive-focus provider, not the whole
       *       dialog, which is why the footer is composed through the render-prop form -- it is the only
       *       way to reach one of the two stock buttons without also reaching the other. Wrapping the
       *       dialog would put the error-ramp ring around Cancel, the one control on the surface that is
       *       safe, and a focus ring is a risk signal. The application's own ring is hue-neutral because
       *       the design system derives one outline for every button variant, so a destructive control
       *       asserted danger at rest and on hover and none of it at the moment of commitment.
       * WHY : Assumptions: cancel is rendered FIRST, which is both the stock order and the safe one --
       *       the first control a keyboard reaches on this surface is the one that changes nothing.
       * WHY : ⚠️ Assumptions: the library's own action labels are kept. Labelling the accept `F5=Save`
       *       would put two controls of that exact accessible name on the document, since the frame's
       *       legend still paints the key that opened the dialog -- ambiguous to a screen reader and to
       *       anything selecting a control by name -- and the defaults are also the one choice that
       *       authors no user-visible string here.
       * WHY : ⚠️ Refactoring Rationale: a dismissed confirmation is DESTROYED rather than hidden. The
       *       library keeps a closed surface mounted by default, so the question and the record identity
       *       stayed in the document after the operator had answered them -- and with the title being a
       *       catalogued sentence the row-22 line also carries, a query for that sentence matched TWO
       *       elements once the dialog had been opened and closed.
       *       Trade-offs: the surface is rebuilt each time it opens rather than reused, which costs one
       *       mount per confirmation. That is paid at most once per save on a screen whose save is
       *       already behind a network write, and it buys a document that says one thing once.
       * WHY : Assumptions: no in-flight indicator is passed. {@link save} closes the dialog in the same
       *       task that raises the write flag, so a `confirmLoading` spinner would have nothing to
       *       appear on; the outstanding write is reported where the operator is actually left, on the
       *       form's own busy region and its announcement.
       */}
      <Modal
        cancelButtonProps={{
          autoFocus: true,
          'aria-describedby': SAVE_CONFIRMATION_RECORD_ID,
        }}
        closable={false}
        okButtonProps={{
          danger: true,
          'aria-describedby': SAVE_CONFIRMATION_RECORD_ID,
        }}
        destroyOnHidden
        footer={
          /**
           * Composes the footer so the accept alone carries the destructive focus ring.
           * @param {ReactNode} _stockFooter - The stock pair, unused; the two members are placed by hand.
           * @param {object} controls - The stock buttons the dialog supplies: `controls.CancelBtn` and
           *   `controls.OkBtn`, each of which reads this dialog's own `okButtonProps`,
           *   `cancelButtonProps` and handlers. The member types are left to inference rather than
           *   named here, because naming React's `ComponentType` in a doc comment would require
           *   importing a type this module never uses in its code.
           * @returns {ReactElement} The declining control followed by the destructive accept.
           */
          (_stockFooter: ReactNode, controls): ReactElement => (
            <>
              <controls.CancelBtn />
              <ConfigProvider theme={destructiveFocusTheme}>
                <controls.OkBtn />
              </ConfigProvider>
            </>
          )
        }
        open={confirmingSave && awaitingConfirmation}
        title={CARD_UPDATE_MESSAGES.PROMPT_FOR_CONFIRMATION.text}
        onCancel={dismissConfirmation}
        onOk={save}
      >
        <Typography.Text
          id={SAVE_CONFIRMATION_RECORD_ID}
          style={{ fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData] }}
        >
          {`${CARD_UPDATE_FIELD_LABELS.accountNumber} ${card?.accountId ?? ''} ${CARD_UPDATE_FIELD_LABELS.cardNumber} ${card?.displayCardNumber ?? ''}`}
        </Typography.Text>
      </Modal>
    </Flex>
  );
}
