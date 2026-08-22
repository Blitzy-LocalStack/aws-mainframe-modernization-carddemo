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

import { Button, Flex, Form, Input, Popconfirm, Result, Spin, Typography, theme } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { CSSProperties, ReactElement } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';

import { getCard, updateCard } from '../../api/cards';
import type { CardDetail, CardUpdateRequest } from '../../api/cards';
import { isApiRequestError, isConflictFailure } from '../../api/client';
import type { FieldError } from '../../api/types';
import { useShellSlot } from '../../layout/AppShell';
import { MessageBand } from '../../layout/MessageBand';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { useServerInstant } from '../../hooks/useServerInstant';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap } from '../../layout/usePfKeys';
import { STATUS_MESSAGES } from '../../messages/messages';
import type { MapsetName } from '../../messages/messages';
import { cardDetailPath, isCardSelector, requireCardSelector } from '../../routes/cards';
import {
  inApplicationRoute,
  navigateSafely,
  navigationHandler,
  screenTransitionState,
} from '../../routes/navigation';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { FIELD_ERROR_TOKENS } from '../../theme/tokens';

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
 * Entry widths, each the `LENGTH=` operand of the field it renders in `app/bms/COCRDUP.bms`.
 *
 * Assumptions: these are the mapset's own figures and they agree with the record picture clauses in
 * `app/cpy/CVACT02Y.cpy` -- `CARD-EMBOSSED-NAME PIC X(50)`, `CARD-ACTIVE-STATUS PIC X(01)`, and the
 * two expiry parts carved out of `CARD-EXPIRAION-DATE PIC X(10)`. The two key fields take the widths
 * of `CC-ACCT-ID PIC X(11)` and `CC-CARD-NUM PIC X(16)` in `app/cpy/CVCRD01Y.cpy` L44-L47. A 3270
 * field physically cannot accept a character beyond its length, so `maxLength` is the browser's only
 * faithful equivalent and Transformation Rule T1 makes the copybook the source of the figure.
 */
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
   */
  return {
    action: 'CHANGES_OKAYED_BUT_FAILED',
    statement:
      isApiRequestError(failure) && failure.problem.message !== null
        ? failure.problem.message
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
   * WHY : Alternatives Considered: the save is gated by a CONTROLLED `Popconfirm` that the save key
   *       opens, rather than by a `Popconfirm` wrapped around a second save control in the body. The
   *       sibling authorization-detail screen takes the latter shape, so it was the obvious candidate --
   *       but it leaves TWO controls behind one action, and here both would carry the same legend text
   *       and become indistinguishable to anything selecting a control by its accessible name. Driving
   *       one overlay from the single legend control keeps one control per action, which is the property
   *       this screen already chose when it withdrew its bespoke save and cancel buttons.
   *       Trade-offs: the reference wrote the instant `F5` arrived, so this inserts a step the terminal
   *       did not have. It is accepted because AAP section 0.3.2 assigns `Popconfirm` exactly this role
   *       -- the browser form of the re-key-to-confirm convention -- and because a pointer can activate a
   *       painted control by accident where a function key cannot, while the write it guards is a
   *       balance-bearing record change that no second write undoes.
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
   * WHY : Assumptions: the read generation is a REF and not state, because nothing renders from it and
   *       it must be readable synchronously by a settlement that ran before the next render. A state
   *       value would be read from the closure of the render that opened the read, so every settlement
   *       would compare its own number against itself and every one of them would look current.
   */
  const readGeneration = useRef(0);
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
     * @returns {void} Completion is represented by the screen's own state.
     */
    (): void => {
      if (selector === null) {
        setLoading(false);
        return;
      }

      const generation = readGeneration.current + 1;

      readGeneration.current = generation;
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

  /*
   * WHY : ⚠️ Refactoring Rationale: `Escape` withdraws the confirmation, and it did NOT before. The
   *       overlay is opened by state and declares `trigger={[]}`, and that combination switches off the
   *       dismissal the library performs for itself -- an uncontrolled `Popconfirm` closes on `Escape`.
   *       Browser validation measured the consequence: `Escape`, a second `Escape`, a `Tab` then a third
   *       `Escape`, and a click outside all left the overlay open, so the only ways out were its own two
   *       buttons or `F12` -- and `F12` DISCARDS the edits. A keyboard operator therefore could not
   *       decline a confirmation without losing the work it was asking about. AAP section 0.4.4 makes
   *       keyboard fidelity a requirement rather than a nicety, because the screen this replaces is
   *       operated entirely from the keyboard, so this restores a library behaviour that controlling the
   *       overlay removed rather than inventing an affordance the reference lacks.
   * WHY : Assumptions: `Escape` DECLINES and never accepts. It routes to the same withdrawal the
   *       overlay's own cancel action uses, so no key press can commit a write; and the listener is
   *       installed only while the confirmation is open, so `Escape` is left to the browser and to any
   *       antd overlay on every other turn.
   * WHY : Alternatives Considered: registering `Escape` through `usePfKeys` beside the four attention
   *       identifiers. Rejected because `KEYBOARD_KEY_TO_AID` maps only `Enter` and the function keys --
   *       `Escape` is deliberately not an attention identifier, since the 3270 `CLEAR` and `PA1`/`PA2`
   *       keys it might stand for carry meanings this program never uses. Widening that shared map to
   *       serve one screen's overlay would put a browser concern into the hook that transcribes the
   *       reference's key contract.
   */
  useEffect(
    /**
     * Listens for `Escape` only while the confirmation is open, and withdraws it.
     * @returns {(() => void) | undefined} The listener's removal, or `undefined` on the turns where no
     *   listener was installed because no confirmation is open.
     */
    (): (() => void) | undefined => {
      if (!confirmingSave) {
        return undefined;
      }

      /**
       * Withdraws the confirmation when the operator presses `Escape`.
       * @param {KeyboardEvent} event - The key press being inspected.
       * @returns {void} Completion is represented by the screen's own state.
       */
      const withdrawOnEscape = (event: KeyboardEvent): void => {
        if (event.key === 'Escape') {
          setConfirmingSave(false);
        }
      };

      document.addEventListener('keydown', withdrawOnEscape);
      /**
       * Removes the listener when the confirmation closes or the screen unmounts.
       * @returns {void} Nothing; the listener registration is undone.
       */
      return (): void => {
        document.removeEventListener('keydown', withdrawOnEscape);
      };
    },
    [confirmingSave],
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
     */
    if (saving) {
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
    if (saving) {
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
    const request: CardUpdateRequest = {
      ...pendingValues,
      version: card.version,
    };
    setSaving(true);
    report(null, 'error');
    updateCard(requireCardSelector(selector), request).then(
      /*
       * WHY : ⚠️ Refactoring Rationale: a committed write STAYS on this screen and states the source's
       *       acknowledgement, where this screen used to navigate straight to the card detail route. The
       *       source does not leave either: `CCUP-CHANGES-OKAYED-AND-DONE` reaches
       *       `WHEN CCUP-CHANGES-OKAYED-AND-DONE SET CCUP-SHOW-DETAILS TO TRUE` at
       *       `app/cbl/COCRDUPC.cbl` L1010-L1012 and re-sends its own map, and `3250-SETUP-INFOMSG`
       *       states `Changes committed to database` on that turn (L1151-L1152). Navigating away made
       *       that sentence unreachable, so the one confirmation an operator gets that the write landed
       *       was never rendered; it also discarded the answer the service had just returned.
       * WHY : Assumptions: the RESPONSE is adopted as the record, not the request. It carries the
       *       version the next write must present, so a second edit in the same visit is submitted
       *       against the version the service now holds rather than the one this screen loaded -- which
       *       would be refused as a conflict every time.
       */
      /**
       * Adopts the committed record and states the source's own acknowledgement.
       * @param {CardDetail} updated - The card as the service reports it after the change, carrying the
       *   version a subsequent write must present.
       */
      (updated) => {
        setSaving(false);
        setPendingValues(null);
        setFieldErrors(new Map());
        setCard(updated);
        form.setFieldsValue(formValuesFrom(updated));
        setAction('CHANGES_OKAYED_AND_DONE');
        report(null, 'success');
      },
      /*
       * WHY : Assumptions: the rejection is classified rather than flattened, and the whole rationale --
       *       including why this screen previously could not reach the concurrency sentence -- is
       *       recorded on {@link classifySaveRejection}. A conflict additionally RE-READS, because the
       *       source refreshes its before-image from the current stored values before returning the
       *       operator to a live edit (L1512-L1518); leaving the stale values on screen would invite the
       *       same write to be resubmitted against the same superseded version.
       */
      /**
       * Classifies a rejected write and moves to the turn the source moves to.
       * @param {unknown} failure - The rejection the update call produced, of unknown provenance.
       */
      (failure: unknown) => {
        const rejection = classifySaveRejection(failure);

        setSaving(false);
        setPendingValues(null);
        /*
         * WHY : Assumptions: the re-read is issued BEFORE the sentence is stated, and the order is
         *       load-bearing rather than incidental. The reader clears the band as it starts its request
         *       rather than when the answer arrives -- the reason is recorded on `reload` -- so a
         *       sentence stated first would be blanked by the read that follows it, while a sentence
         *       stated afterwards is the last write and survives. It also resets the turn and the marks,
         *       which is why both are applied after it.
         */
        if (rejection.reread) {
          reload();
        }
        setFieldErrors(indexFieldErrors(rejection.fieldErrors));
        setAction(rejection.action);
        report(rejection.statement, 'error');
      },
    );
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
  }

  const keyHandlers: PfKeyHandlerMap = {
    ENTER: {
      /** Validates the edits and asks for confirmation, the source's `ENTER=Process` action. */
      onInvoke: () => {
        form.submit();
      },
      label: CARD_UPDATE_KEY_LABELS.ENTER,
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
        navigateSafely(navigate, callerOrigin ?? cardDetailPath(requireCardSelector(selector)));
      },
      label: CARD_UPDATE_KEY_LABELS.PFK03,
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
   * colour. Only the marker distinguishes them, at {@link blankMarkerFor}.
   * @param {string} field - Form member name, which the service uses as its own field name.
   * @returns {{validateStatus: 'error', help: string} | Record<string, never>} The override props for a
   *   refused field, or an empty object leaving the form's own rules in charge.
   */
  function refusalPropsFor(field: string): { validateStatus: 'error'; help: string } | object {
    const refusal = fieldErrors.get(field);

    return refusal === undefined ? {} : { validateStatus: 'error', help: refusal.message };
  }

  /**
   * Supplies the `'*'` marker a BLANK refusal adds beside a control, and nothing for any other state.
   *
   * Assumptions: the marker is for the BLANK state ALONE, where the red colour covers both states. The
   * template nests it one level deeper than the colour: `app/cpy/CSSETATY.cpy` L23-L26 moves `'*'` only
   * inside `IF FLG-(TESTVAR1)-BLANK`, while the `DFHRED` move at L21-L22 sits outside that test. So a
   * field holding an unacceptable value is red without a marker, and an empty one is red with it.
   *
   * Alternatives Considered: writing the marker INTO the control's value, which is literally what the
   * template does -- it moves `'*'` into the field's output byte. Rejected because these four controls
   * are managed by the form, so a marker placed in the value would be read back as the operator's input
   * and submitted as the embossed name or the expiry month. Rendering it as the input's suffix shows the
   * same character in the same place without it becoming data, which is the accommodation the sibling
   * account-update screen already makes for its own marker-bearing fields.
   * @param {string} field - Form member name, which the service uses as its own field name.
   * @returns {ReactElement | null} The marker element for a blank refusal, or `null`.
   */
  function blankMarkerFor(field: string): ReactElement | null {
    if (fieldErrors.get(field)?.state !== 'BLANK') {
      return null;
    }
    /*
     * WHY : Assumptions: the colour is the theme's own red taken from `FIELD_ERROR_TOKENS`, resolved
     *       through `cssVar` so it follows the CSS-variables theming antd 6 applies rather than freezing
     *       a literal. AAP section 0.3.3 records the mapping: the template's `DFHRED` is the design
     *       system's error colour, and no hex value belongs in a screen.
     */
    const markerStyle: CSSProperties = { color: cssVar[FIELD_ERROR_TOKENS.errorColor] };

    return <Typography.Text style={markerStyle}>{FIELD_ERROR_TOKENS.blankMarker}</Typography.Text>;
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
   *       called after one of them would change hook order between renders. The two erased states
   *       publish an EMPTY key list and no header or message, which is what they rendered before the
   *       shell existed: the reference answers an unaddressable selector with `SEND TEXT ... ERASE`
   *       rather than by re-sending the map, and a read in flight has no counterpart in it at all.
   * WHY : ⚠️ Trade-offs: an empty list is published rather than the member being omitted, and
   *       the reason recorded here -- that this screen binds PF12 as `cancel` while the shell binds PF12
   *       as sign-off "whenever no screen has published keys", so omitting it would put both listeners on
   *       the document and one press would cancel the edit AND end the session -- is withdrawn. The shell
   *       installs NO keyboard listener at all and offers sign-off as a rendered control, for the reason
   *       recorded at `SHELL_SIGN_OFF_LABEL`, so this screen's PF12 is the only PF12 on the document
   *       either way. Both forms render the same legend, none: `PfKeyBar` returns `null` for an empty
   *       binding list. The member is kept in both arms so they differ only in which zones carry content.
   */
  useShellSlot(
    selector === null || loading
      ? { pfKeys: { keys: [], onInvoke: invoke } }
      : {
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
           */
          message: { text: message, severity, mapset: CARD_UPDATE_MAPSET },
          pfKeys: { keys: bindings, onInvoke: invoke },
        },
  );

  if (selector === null) {
    return (
      <Result
        status="error"
        title={CARD_UPDATE_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text}
        subTitle={CARD_UPDATE_INVALID_LINK_GUIDANCE}
        extra={
          <Button onClick={navigationHandler(navigate, '/cards')}>
            {CARD_UPDATE_KEY_LABELS.PFK03}
          </Button>
        }
      />
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
      <Form<CardFormValues>
        form={form}
        layout="vertical"
        onFinish={process}
        onFinishFailed={refuseSubmission}
        requiredMark
      >
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
              pattern: EMBOSSED_NAME_PATTERN,
              message: CARD_UPDATE_MESSAGES.WS_NAME_MUST_BE_ALPHA.text,
            },
          ]}
          {...refusalPropsFor('embossedName')}
        >
          <Input
            maxLength={CARD_UPDATE_FIELD_WIDTHS.embossedName}
            autoFocus
            suffix={blankMarkerFor('embossedName')}
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
        <Form.Item
          label={`${CARD_UPDATE_FIELD_LABELS.expiryDate}${CARD_UPDATE_FIELD_LABELS.expirySeparator}`}
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
            maxLength={CARD_UPDATE_FIELD_WIDTHS.expirationMonth}
            inputMode="numeric"
            suffix={blankMarkerFor('expirationMonth')}
            {...(editable ? {} : { readOnly: true })}
          />
        </Form.Item>
        <Form.Item
          label={CARD_UPDATE_FIELD_LABELS.expiryDate}
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
            maxLength={CARD_UPDATE_FIELD_WIDTHS.expirationYear}
            inputMode="numeric"
            suffix={blankMarkerFor('expirationYear')}
            {...(editable ? {} : { readOnly: true })}
          />
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
            suffix={blankMarkerFor('activeStatus')}
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
       * WHY : ⚠️ Refactoring Rationale: the row-20 information line is composed HERE, and this screen
       *       composed no such line before -- it collapsed both of the mapset's message fields onto the
       *       single delegated band, so five of the six sentences `3250-SETUP-INFOMSG` can state were
       *       unreachable, including the acknowledgement that a write had landed. The mapset declares two
       *       independent fields at two rows and paints BOTH on every turn:
       *       `MOVE WS-INFO-MSG TO INFOMSGO` and `MOVE WS-RETURN-MSG TO ERRMSGO` at
       *       `app/cbl/COCRDUPC.cbl` L1160 and L1162. `INFOMSG` is `POS=(20,25)`, `ATTRB=(PROT)`,
       *       `COLOR=NEUTRAL`, `LENGTH=40` (`app/bms/COCRDUP.bms` L149-L153) and belongs to this screen's
       *       own field area; the `COLOR=RED` `ERRMSG` at `POS=(23,1)` is the shell's, delegated above.
       * WHY : Assumptions: it takes the `info` severity on every turn, because its source field is
       *       `COLOR=NEUTRAL` on every turn -- the program varies the field's brightness with
       *       `DFHBMDAR`/`DFHBMBRY` (L1309-L1313) but never its colour. AAP section 0.3.3 maps
       *       `COLOR=NEUTRAL` to the secondary text token, recorded as snap G3.
       * WHY : Assumptions: the sentence is DERIVED from the turn rather than stated by each arm, which is
       *       how the source works -- one `EVALUATE` over the action, evaluated fresh on every send. That
       *       also removes the ordering hazard a stated sentence has around a read, which is what made the
       *       cancel arm's previous statement fragile enough to need a note about it.
       * WHY : Assumptions: it closes the body, which is the reading order the source paints: row 20 sits
       *       below the last data field at row 15 and above the row-23 error line, and the shell paints
       *       rows 23 and 24 immediately beneath this region.
       */}
      {/*
       * WHY : ⚠️ Refactoring Rationale: the overlay is anchored to a wrapper that CONTAINS the
       *       information band, where it was anchored to an empty `<Flex />` beside it. An empty `Flex`
       *       cannot anchor anything: antd's own flex style ends with `'&:empty': { display: 'none' }`
       *       (`antd/lib/flex/style/index.js` L24-L26), so the element had no CSS box at all -- zero
       *       client rects and a null `offsetParent`. The popup's positioner measures its target before
       *       placing itself, and a target it cannot measure leaves the popup parked at its
       *       pre-alignment sentinel, `inset: -1000vh auto auto -1000vw`. Browser validation measured
       *       the consequence: the confirmation rendered at x=-14400, y=-9010, so it was invisible and
       *       a pointer could not reach `OK` at all -- the screen's only guard before an irreversible
       *       write was unreachable, while its markup and its content were perfectly correct. Nothing
       *       reported it: no console warning is emitted, and a DOM-level test finds the buttons by role
       *       whatever their coordinates, because jsdom performs no layout.
       *       Assumptions: wrapping the band gives the anchor a real box without introducing a design
       *       value -- the box is the band's own -- and it also puts the overlay where the note below
       *       always claimed it was. On the only turn the overlay can open, the band is guaranteed to
       *       render content, because `informationLineFor` returns a catalog sentence for every turn and
       *       this one is `Changes validated.Press F5 to save`.
       * WHY : Assumptions: the confirmation is anchored to the information line because that line is
       *       where the source asks for it -- `Changes validated.Press F5 to save` is the sentence this
       *       turn paints -- so the overlay opens beside the words that requested it. It is CONTROLLED
       *       rather than triggered by this element, so the element is an anchor and not a second control:
       *       the only thing that opens it is the save key, for the reason recorded at `confirmingSave`.
       * WHY : Assumptions: `trigger` is emptied because the anchor is now a real, visible element. The
       *       default action is `click`, which would otherwise make a click anywhere on the message line
       *       open a save confirmation -- an action the reference has no equivalent for, on an element
       *       that is a status region rather than a control.
       * WHY : Assumptions: the overlay's TITLE is the mapset's row-24 legend text, so the confirmation
       *       introduces no new wording of its own -- the words asking for it are words the mapset already
       *       paints. `okType="danger"` is the design system's marking for an irreversible write, which
       *       AAP section 0.3.2 assigns to this role.
       * WHY : Alternatives Considered: labelling the accept and decline actions with the same two legend
       *       strings, which was the first shape of this overlay. Rejected because the accept control
       *       would then carry the identical accessible name to the legend control that opened it, leaving
       *       two buttons named `F5=Save` on the document -- ambiguous to a screen reader announcing them
       *       and to anything selecting a control by name. The library's own defaults are used instead,
       *       which is also the one choice that authors no user-visible string in this screen: every
       *       sentence this file renders still comes from the catalog.
       */}
      <Popconfirm
        open={confirmingSave && awaitingConfirmation}
        trigger={[]}
        title={CARD_UPDATE_KEY_LABELS.PFK05}
        okType="danger"
        onConfirm={save}
        onCancel={dismissConfirmation}
      >
        <Flex vertical>
          <MessageBand
            mapset={CARD_UPDATE_MAPSET}
            severity="info"
            message={informationLineFor(action)}
            line="information"
          />
        </Flex>
      </Popconfirm>
    </Flex>
  );
}
