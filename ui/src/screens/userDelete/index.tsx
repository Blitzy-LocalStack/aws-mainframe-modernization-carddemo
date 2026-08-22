/**
 * @file The delete-user screen, replacing BMS mapset `COUSR03` / map `COUSR3A` and program
 * `app/cbl/COUSR03C.cbl` (transaction `CU03`, file `USRSEC`).
 *
 * Purpose
 * -------
 * Renders the reference's two-stage workflow: an operator names a user, the row is FETCHED and
 * displayed, and only then may it be DELETED. The two stages are what makes this screen safe, and the
 * reference states the boundary itself -- its read arm ends by inviting a second, different keystroke,
 * `'Press PF5 key to delete this user ...'` at L283, so displaying a user and destroying one were never
 * the same act.
 *
 * What the mapset asks for, and what is given
 * -------------------------------------------
 * `app/bms/COUSR03.bms` declares 26 `DFHMDF` fields: 11 named and 15 anonymous. Six of the named ones
 * are the header band (`TRNNAME`, `TITLE01`, `CURDATE`, `PGMNAME`, `TITLE02`, `CURTIME`) and one is the
 * row-23 message line (`ERRMSG`); all seven are DELEGATED to `ui/src/layout/AppShell.tsx` rather than
 * rendered here, so this module paints only the body. Of the 15 anonymous fields, eleven are static
 * labels, the row-8 rule and the row-24 legend, and three are zero-length stoppers at `POS=(6,30)`,
 * `(11,39)` and `(13,39)` that exist solely to terminate a 3270 attribute region -- they carry no
 * content and have no target at all.
 *
 * Assumptions: exactly ONE field is enterable. `USRIDIN` at L85 is the mapset's only
 * `ATTRB=(FSET,IC,NORM,UNPROT)` field, which makes it simultaneously the only unprotected control, the
 * only initial-cursor position and the fetch key -- three facts that collapse into one `Input` carrying
 * one `autoFocus`. `FNAME`, `LNAME` and `USRTYPE` are all `ATTRB=(ASKIP,FSET,NORM)`, so they are
 * OUTPUT ONLY and are rendered as a record view rather than as controls.
 *
 * No credential, anywhere
 * -----------------------
 * Assumptions: this screen renders no password control of any kind, not even a disabled or masked one,
 * and that is the mapset's own shape rather than a restriction added here. `app/cpy-bms/COUSR03.CPY`
 * declares eleven field families and NONE of them is a password family, unlike its `COUSR01` and
 * `COUSR02` siblings which each declare one. The plaintext `05 SEC-USR-PWD PIC X(08).` at
 * `app/cpy/CSUSR01Y.cpy` L21 is not carried into the target at any layer -- no column, no response
 * property, no control -- per AAP section 0.7.8, so there would be nothing for a control here to show
 * or to send.
 *
 * Where the strings and the design values come from
 * ------------------------------------------------
 * Assumptions: every string an operator reads on this screen comes from `ui/src/messages/messages.ts`,
 * which holds the verbatim monopoly for this tree -- the banded sentences its program MOVEs, and the
 * caption, the four field labels, the user-type hint and the legend labels its mapset paints, each
 * carrying its own mapset line in that module. Every colour, weight and face resolves through
 * `ui/src/theme/tokens.ts`. This file therefore contains no colour literal, no spacing literal, no
 * user-visible text literal and no `ConfigProvider` -- theme injection belongs to `ui/src/App.tsx`
 * alone.
 *
 * Reference-only provenance: `app/bms/COUSR03.bms`, `app/cpy-bms/COUSR03.CPY`,
 * `app/cbl/COUSR03C.cbl`, `app/cpy/CSUSR01Y.cpy`, `app/cpy/COCOM01Y.cpy` and `app/cpy/CSMSG01Y.cpy`
 * are read as the specification and are never modified.
 */

import {
  Button,
  Descriptions,
  Divider,
  Flex,
  Form,
  Input,
  Popconfirm,
  Spin,
  Typography,
  theme,
} from 'antd';
import type { InputRef } from 'antd';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import type { ChangeEvent, CSSProperties, ReactElement } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';

import { USER_ID_MAX_LENGTH, deleteUser, getUser } from '../../api/auth';
import { isApiRequestError } from '../../api/client';
import type { ApiError, FieldValidationState, UserResponse, UserType } from '../../api/types';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import { fieldAriaProps, fieldErrorHelp } from '../../layout/fieldHelp';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { UNIFORM_PF_KEY_LABELS, decodeBmsLegendText } from '../../layout/PfKeyBar';
import { RECORD_VIEW_COLUMNS } from '../../layout/recordLayout';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap, PfKeyRejection } from '../../layout/usePfKeys';
import {
  INVALID_KEY_PRESSED,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  USER_DELETE_CAPTION,
  USER_DELETE_FIELD_LABELS,
  USER_DELETE_KEY_LABELS as CATALOGUED_USER_DELETE_KEY_LABELS,
  USER_DELETE_USER_TYPE_HINT,
  formatMessageTemplate,
} from '../../messages/messages';
import type { MapsetName } from '../../messages/messages';
import {
  ADMIN_MENU_ROUTE,
  inApplicationRoute,
  navigateSafely,
  screenTransitionState,
} from '../../routes/navigation';
import {
  BMS_COLOR_TOKENS,
  BMS_TEXT_COLOR_TOKENS,
  FIELD_ERROR_TOKENS,
  TYPOGRAPHY_TOKENS,
} from '../../theme/tokens';

/** The one sentence this screen's own program owns, from the catalog that owns every string it paints. */
const DELETE_MESSAGES = PROGRAM_MESSAGES.COUSR03C;

/** CICS transaction identifier this screen replaces, from `app/cbl/COUSR03C.cbl` L37. */
export const USER_DELETE_TRANSACTION_ID = 'CU03';

/** Source program name, rendered in the header band as row 2 of the 3270 screen painted it (L36). */
export const USER_DELETE_PROGRAM_NAME = 'COUSR03C';

/**
 * Mapset this screen stands in, which sizes the message band.
 *
 * Assumptions: the catalog registers `COUSR03` as a 78-character band, which is the `LENGTH=78` its
 * `ERRMSG` field declares at `app/bms/COUSR03.bms` L140-L143. It is passed explicitly rather than left
 * to the band's default -- which happens to be the same 78 -- because the default exists for a caller
 * that does not know its mapset, and this one does.
 */
export const USER_DELETE_MAPSET = 'COUSR03' as const satisfies MapsetName;

/** The four values this screen holds: the one key it accepts and the three it displays. */
export type UserDeleteField = 'userId' | 'firstName' | 'lastName' | 'userType';

/*
 * WHY : ⚠️ Refactoring Rationale: the row-4 caption, the four field labels and the user-type domain hint
 *       were transcribed HERE from `app/bms/COUSR03.bms` and are now imported from
 *       `ui/src/messages/messages.ts` as `USER_DELETE_CAPTION`, `USER_DELETE_FIELD_LABELS` and
 *       `USER_DELETE_USER_TYPE_HINT`, with the per-entry mapset lines beside them in
 *       `USER_DELETE_PAINTED_TEXT_SOURCES`. AAP transformation rule T8 and section 0.2.1.5 give every
 *       operator-visible string one owner, and this screen had three that its update and add siblings
 *       hold copies of -- `'User Type: '` and `'(A=Admin, U=User)'` are byte-identical to the add
 *       screen's, so a correction applied to one spelling would have left the others reading the old
 *       text with nothing to show the divergence.
 * WHY : Assumptions: nothing about the values changed in the move. The trailing space on `userType` is
 *       still part of the value -- `INITIAL='User Type: '` is `LENGTH=11` for ten visible characters at
 *       `app/bms/COUSR03.bms` L125-L129 -- and the catalog records the `COLOR=GREEN` of the enterable
 *       field's label against the `COLOR=TURQUOISE` of the three display labels per entry, which is the
 *       distinction the two label styles below resolve.
 * WHY : Assumptions: `UserDeleteField` and the widths below stay in this module and the labels no longer
 *       satisfy that union structurally. The union is this screen's own control vocabulary -- it keys the
 *       widths and the field-error mapping -- while the catalog's group is a per-mapset transcription
 *       shared with nothing; binding the catalog to a screen-local union would have made the catalog
 *       depend on a consumer. The four members are named literally at every use site instead, so a
 *       renamed member is still a compile error rather than an undefined label.
 */

/*
 * WHY : Assumptions: each width is the mapset's `LENGTH=` operand corroborated independently by the
 *       record layout, so neither source is trusted alone. `USRIDIN` is `LENGTH=8` at
 *       `app/bms/COUSR03.bms` L85-L89, `05 SEC-USR-ID PIC X(08).` at `app/cpy/CSUSR01Y.cpy` L18 and
 *       `CDEMO-USER-ID PIC X(8)` in `app/cpy/COCOM01Y.cpy`; `FNAME` is `LENGTH=20` at L103-L107 and
 *       `PIC X(20)` at L19; `LNAME` is `LENGTH=20` at L116-L120 and `PIC X(20)` at L20; `USRTYPE` is
 *       `LENGTH=1` at L130-L134 and `PIC X(01)` at L22. A 3270 refused a keystroke past the end of a
 *       field, so `maxLength` on the one enterable control is the faithful browser equivalent rather
 *       than a convenience -- and the three display widths are recorded because they are the contract
 *       the values arrive under, even though no control bounds them.
 * WHY : Refactoring Rationale: the identifier's width is taken from `USER_ID_MAX_LENGTH` in
 *       `ui/src/api/auth.ts` rather than written as `8` here. That constant is the bound the service
 *       actually judges a submission against, so reading it means the control cannot admit a value the
 *       transport would refuse; a local literal would be a second copy of one contract.
 */
export const USER_DELETE_FIELD_WIDTHS = {
  userId: USER_ID_MAX_LENGTH,
  firstName: 20,
  lastName: 20,
  userType: 1,
} as const satisfies Readonly<Record<UserDeleteField, number>>;

/*
 * WHY : ⚠️ Refactoring Rationale: the three mapset-specific labels were LITERALS in this block and are
 *       now the catalog's `USER_DELETE_KEY_LABELS`, for the reason the caption and field labels moved:
 *       one owner per operator-visible string. `F3=Back` alone is painted by TEN of the twenty-one
 *       mapsets, so a literal here was one transcription among ten with nothing holding them in
 *       agreement.
 * WHY : Assumptions: the catalog's values are still passed through `decodeBmsLegendText` uniformly, and
 *       the pass is a deliberate no-op rather than a leftover. BMS source is assembler macro source in
 *       which `&` opens a variable symbol, so a legend containing an ampersand is written doubled there;
 *       this mapset's legend contains none, and both the decoder's own contract and the catalog entry
 *       state that it is idempotent over already-decoded text. Applying it to all four labels lets a
 *       reader check every one against a mapset by eye without having to remember which ones needed
 *       unescaping, and it keeps this screen's handling identical to the update sibling's, whose
 *       `F3=Save&&Exit` genuinely needs the decode. The four labels reconstruct the field's rendered
 *       text exactly -- `ENTER=Fetch  F3=Back  F4=Clear  F5=Delete`, 41 characters in the `LENGTH=58`
 *       field at `app/bms/COUSR03.bms` L144-L148, with TWO spaces between every pair.
 * WHY : Refactoring Rationale: only `F4=Clear` is taken from `UNIFORM_PF_KEY_LABELS`, and the catalog
 *       publishes no PFK04 entry for the same reason. That module publishes defaults for exactly the
 *       three keys whose wording is byte-identical across all measured legends and deliberately
 *       publishes none for ENTER, PF3 or PF5, because those differ per screen -- this mapset says
 *       `F3=Back` where its update sibling says `F3=Save&&Exit` and `F5=Delete` where that one says
 *       `F5=Save`. Inheriting a default for either would mislabel a control, which is the failure that
 *       module's omission exists to prevent.
 * WHY : Assumptions: there is NO PF12 label, and its absence is deliberate rather than an oversight.
 *       The reference binds `DFHPF12` at L123-L125 while its row-24 literal advertises only four
 *       actions, so the key works and is not advertised. Both facts are preserved: the binding is
 *       registered below with no `label`, which `PfKeyBar` renders as a keyboard-only handler.
 */
const USER_DELETE_KEY_LABELS = {
  ENTER: decodeBmsLegendText(CATALOGUED_USER_DELETE_KEY_LABELS.ENTER),
  PFK03: decodeBmsLegendText(CATALOGUED_USER_DELETE_KEY_LABELS.PFK03),
  PFK04: UNIFORM_PF_KEY_LABELS.PFK04,
  PFK05: decodeBmsLegendText(CATALOGUED_USER_DELETE_KEY_LABELS.PFK05),
} as const;

/** HTTP status the service answers when no user row carries the identifier. */
const NOT_FOUND_STATUS = 404;

/** The four values the screen holds, with the user type narrowed to the domain the contract admits. */
interface UserDeleteValues {
  /** The fetch key, as the one enterable control holds it. */
  readonly userId: string;
  /** First name as the last successful read returned it, or empty. */
  readonly firstName: string;
  /** Last name as the last successful read returned it, or empty. */
  readonly lastName: string;
  /** User type as the last successful read returned it, or empty before one. */
  readonly userType: '' | UserType;
}

/**
 * Every value empty, which is the state `INITIALIZE-ALL-FIELDS` leaves at `app/cbl/COUSR03C.cbl`
 * L349-L356.
 *
 * Assumptions: that paragraph blanks FOUR targets -- `USRIDINI`, `FNAMEI`, `LNAMEI` and `USRTYPEI` --
 * and the message, and it is performed from two places: the clear arm at L343 and the successful delete
 * at L315. So a completed deletion empties the fetch key as well as the record, which is why this
 * constant covers all four rather than the three display values.
 */
const EMPTY_VALUES: UserDeleteValues = {
  userId: '',
  firstName: '',
  lastName: '',
  userType: '',
};

/** One refusal this screen can render, narrowed to the single control it actually paints. */
export interface UserDeleteFieldError {
  /** Control the refusal names; only the fetch key is renderable, so only it is admitted. */
  readonly field: 'userId';
  /** Which of the baseline's two field conditions applies, which decides the blank marker. */
  readonly state: FieldValidationState;
  /** Verbatim sentence to render beneath the control. */
  readonly message: string;
}

/** What a rejected request puts on the screen: one sentence, any field refusals, and whether to focus. */
interface UserDeleteFailureReport {
  /** Verbatim sentence for the message band. */
  readonly message: string;
  /** Refusals to render beneath the fetch key. */
  readonly fieldErrors: readonly UserDeleteFieldError[];
  /** Whether to move the cursor back to the fetch key, mirroring `MOVE -1 TO USRIDINL`. */
  readonly focusKey: boolean;
}

/** Which of the two file operations a failure came out of, so its sentence names the right site. */
type UserDeleteStage = 'read' | 'delete';

/**
 * Reports whether the fetch key counts as empty by the reference's own test.
 *
 * Assumptions: the reference tests `USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES` -- at L145 on the
 * fetch arm and again at L177 on the delete arm -- and `SPACES` on a fixed-width field is satisfied by a
 * value of nothing but blanks, so a control holding three spaces is blank to the reference. Trimming
 * before the comparison reproduces that, where a bare emptiness test would admit a value the reference
 * refuses.
 * @param {string} value - Raw value as the control holds it.
 * @returns {boolean} `true` when the reference would treat the value as `SPACES` or `LOW-VALUES`.
 */
export function isBlankUserId(value: string): boolean {
  return value.trim() === '';
}

/**
 * Selects the refusals a rejected request raised against the one control this screen paints.
 *
 * Assumptions: the service accumulates its violations into `ApiError.fieldErrors` and may name a
 * property this screen has no control for -- `deleteUser` documents a `confirmed` entry for an absent or
 * false confirmation, which is a transport fault rather than something an operator typed. Entries are
 * therefore FILTERED to the fetch key rather than rendered blindly: a refusal attached to no control
 * would be marked on nothing, while its sentence still reaches the band through
 * {@link describeUserDeleteFailure}.
 *
 * Trade-offs: the array is kept rather than reduced to its first entry, because the caller decides how
 * many marks to render while the band shows exactly one sentence, which is the reference's observable
 * behaviour.
 * @param {ApiError} problem - The normalised problem document a rejected request carried.
 * @returns {readonly UserDeleteFieldError[]} One entry per refusal this screen can render, in the order
 *   the service listed them.
 */
export function resolveUserDeleteFieldErrors(problem: ApiError): readonly UserDeleteFieldError[] {
  const resolved: UserDeleteFieldError[] = [];

  for (const entry of problem.fieldErrors) {
    if (entry.field === 'userId') {
      resolved.push({ field: 'userId', state: entry.state, message: entry.message });
    }
  }

  return resolved;
}

/**
 * Reduces a rejected request to the one sentence, the field marks and the cursor move it produces.
 *
 * Assumptions: the sentence for each outcome is the reference's own, chosen by the stage the failure came
 * out of, because the reference words the same condition differently on its two file operations. A
 * missing row is `User ID NOT found...` from both `DFHRESP(NOTFND)` arms -- L289 on the read and L325 on
 * the delete -- and the cursor returns to the fetch key on both (L291, L327).
 *
 * ⚠️ Assumptions: anything else is `Unable to lookup User...` from the read's `WHEN OTHER` arm (L296) or,
 * emphatically, `Unable to Update User...` from the delete's (L332). The second is the UPDATE wording on
 * the DELETE path and it is reproduced exactly as the baseline writes it: message fidelity under AAP rule
 * T8 makes every user-visible string verbatim, so "correcting" the verb would be an undocumented
 * behavioural change to an externally observable string. `ui/src/api/auth.ts` carries the same literal
 * across on its own 500 branch for the same reason, so the two layers agree.
 *
 * Assumptions: neither `WHEN OTHER` arm returns a cursor move, and that is measured rather than
 * inferred. Both move `-1` into `FNAMEL` (L298 and L334) -- the length subfield of a field the mapset
 * declares `ATTRB=(ASKIP,FSET,NORM)` at L103 and L116 -- so the reference points the cursor at a
 * PROTECTED field an operator cannot type into. The target renders that field as a record value with no
 * focusable control at all, so there is nothing to focus and leaving the cursor where it is the closest
 * faithful outcome.
 * @param {unknown} failure - Whatever the request rejected with, normally the normalised
 *   `ApiRequestError` that `ui/src/api/client.ts` mints.
 * @param {UserDeleteStage} stage - Which file operation the failure came out of, selecting the reference
 *   sentence for an outcome that names no field.
 * @returns {UserDeleteFailureReport} The sentence to band, the refusals to mark, and whether the cursor
 *   returns to the fetch key.
 */
export function describeUserDeleteFailure(
  failure: unknown,
  stage: UserDeleteStage,
): UserDeleteFailureReport {
  const stageMessage =
    stage === 'read'
      ? SHARED_MESSAGES.UNABLE_TO_LOOKUP_USER
      : SHARED_MESSAGES.UNABLE_TO_UPDATE_USER;

  /*
   * WHY : Assumptions: a failure that is not the normalised error is still reported through the
   *       reference's own sentence rather than surfaced raw. `ui/src/api/client.ts` normalises every
   *       transport outcome it produces -- a problem document, a non-JSON gateway body, a timeout and a
   *       network fault alike -- so reaching this arm means the rejection came from somewhere else
   *       entirely, and the reference's `WHEN OTHER` arm is exactly the catch-all for a file operation
   *       that failed for a reason it could not name.
   */
  if (!isApiRequestError(failure)) {
    return { message: stageMessage, fieldErrors: [], focusKey: false };
  }

  const fieldErrors = resolveUserDeleteFieldErrors(failure.problem);

  if (failure.status === NOT_FOUND_STATUS) {
    return { message: SHARED_MESSAGES.USER_ID_NOT_FOUND, fieldErrors, focusKey: true };
  }

  const firstRefusal = fieldErrors[0];

  /*
   * WHY : Assumptions: a refusal naming the fetch key takes precedence over the stage sentence, and this
   *       outcome has no reference site because the reference has no 400 path -- it validates the key
   *       itself before touching the file. The service validates independently and can refuse a value
   *       this screen let through, so its sentence is shown for the control it names, which is the shape
   *       the reference produces for its own refusal: one sentence, about the offending field, with the
   *       cursor on it.
   */
  if (firstRefusal !== undefined) {
    return { message: firstRefusal.message, fieldErrors, focusKey: true };
  }

  return { message: stageMessage, fieldErrors, focusKey: false };
}

/**
 * Composes the deletion acknowledgement exactly as the reference's `STRING` statement builds it.
 *
 * Assumptions: the reference concatenates three parts at L318-L320 -- the literal `'User '`, then
 * `SEC-USR-ID` **`DELIMITED BY SPACE`**, then the literal `' has been deleted ...'`. The middle
 * delimiter is what makes the identifier appear TRIMMED: `SEC-USR-ID` is `PIC X(08)` and space-padded, so
 * `DELIMITED BY SPACE` stops at the first blank and a four-character identifier contributes four
 * characters rather than eight. The catalog template encodes that same delimiter, so the trimming is
 * performed by `formatMessageTemplate` rather than restated here.
 *
 * Refactoring Rationale: the sentence is built from the catalog template rather than concatenated from
 * literals in this file. The catalog owns every user-visible string in this tree and cites this
 * message's three source lines, so a local concatenation would put the same sentence in two places with
 * no build failure to catch them drifting apart.
 * @param {string} userId - Identifier of the row that was deleted, as the control held it.
 * @returns {string} The acknowledgement sentence, with the identifier trimmed as the delimiter requires.
 */
export function formatUserDeletedMessage(userId: string): string {
  return formatMessageTemplate(MESSAGE_TEMPLATES.USER_HAS_BEEN_DELETED, {
    'SEC-USR-ID': userId,
  });
}

/**
 * Renders the delete-user screen, whose route may carry the identifier to load.
 *
 * Assumptions: the route parameter is read as `id`, which is the name the router declares for this
 * route -- the same spelling its update sibling uses. The name is per-route rather than global, so a
 * mismatch resolves to `undefined` silently rather than failing, and it is optional in this component's
 * own terms: an operator may reach the screen with no identifier and type one, which is exactly what the
 * reference permits when `CDEMO-CU03-USR-SELECTED` arrives as `SPACES` (L99-L104).
 *
 * Error paths, all of which end as a banded sentence rather than a thrown value: a rejected read or
 * delete is reduced by {@link describeUserDeleteFailure} to one sentence for the message band plus any
 * `ApiError.fieldErrors` entry naming the fetch key, which is rendered as
 * `Form.Item validateStatus="error"` with `help` text and, for the blank condition only, the literal
 * `'*'` marker. A 404 becomes `User ID NOT found...` on both operations; any other read failure becomes
 * `Unable to lookup User...`; any other delete failure becomes `Unable to Update User...`. A key the
 * screen does not bind becomes `CCDA-MSG-INVALID-KEY`.
 * @returns {ReactElement} The row-4 caption, the fetch control, the row-8 rule, the three-value record
 *   view and the confirmed delete trigger. The header band, message line and key legend are delegated to
 *   the shell rather than rendered here.
 */
export function UserDeleteScreen(): ReactElement {
  const navigate = useNavigate();
  const location = useLocation();
  /*
   * WHY : Assumptions: the instant is server-derived rather than read from the browser clock, because
   *       the reference reads ONE region clock for every terminal -- `POPULATE-HEADER-INFO` runs
   *       `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA` at L245 on each `SEND MAP`. Omitting it falls
   *       back to the browser's clock and its zone, under which two administrators looking at one row can
   *       read two different dates across midnight. Read during render so the value is a PAINT-time
   *       instant, matching a clock re-read per send rather than one advanced by a timer.
   */
  const paintedAt = useServerInstant();
  /*
   * WHY : Assumptions: the route parameter is the target of `CDEMO-CU03-USR-SELECTED`, the `PIC X(08)`
   *       prefill slot the reference declares at L58 and consumes at L99-L102. AAP section 0.7.1 relocates
   *       every selection carrier out of the pseudo-conversational session struct and into the request
   *       path, so the URL is where that value now lives and the router fixes its name as `id`.
   */
  const { id: routeUserId } = useParams<{ id: string }>();
  /*
   * WHY : Assumptions: `cssVar` rather than `token` from the same hook. `token` returns RESOLVED values --
   *       a hex string, a number -- so writing one into a `style` attribute bakes today's palette into the
   *       element and silently opts it out of antd 6's CSS-variable theming, leaving this one screen behind
   *       when a token changes. `cssVar` returns the `var(--...)` reference form of the same names, so
   *       every design value below still resolves through the single `ConfigProvider` in `ui/src/App.tsx`.
   */
  const { cssVar } = theme.useToken();
  /*
   * WHY : Assumptions: the control identifier is derived per instance rather than written as a constant,
   *       because its only job is to bind the label and the help text to the control; a constant would
   *       collide if the screen were ever rendered twice in one document, leaving a label pointing at
   *       whichever control appeared first.
   */
  const idPrefix = useId();
  const fetchKeyControl = useRef<InputRef | null>(null);

  const [values, setValues] = useState<UserDeleteValues>(EMPTY_VALUES);
  const [loaded, setLoaded] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');
  const [fieldErrors, setFieldErrors] = useState<readonly UserDeleteFieldError[]>([]);
  const [busy, setBusy] = useState(false);
  /*
   * WHY : Assumptions: the confirmation is held as state so the SAME modal can be opened by the PF5 key
   *       and by a click on its trigger. antd's `Popconfirm` opens itself on a click when uncontrolled,
   *       which would leave the key press with no way to reach it -- and the key press is the path the
   *       reference guarantees, since a 3270 had no pointer at all.
   */
  const [confirmOpen, setConfirmOpen] = useState(false);
  /*
   * WHY : Refactoring Rationale: the cursor destination is held as STATE and applied by the effect below
   *       rather than by calling `.focus()` where the refusal is decided. An inline call is the obvious
   *       shape and loses the cursor twice over, both times because it would run before React commits the
   *       render the refusal causes. First, the blank marker is an antd `Input` `suffix`, and adding one
   *       rewraps the control so a NEW `input` element is mounted -- focus applied to the old node is
   *       discarded and the cursor falls back to the document body. Second, the control is `disabled`
   *       while a turn is in flight, and a browser refuses focus on a disabled element silently, with no
   *       error to notice. This is the mechanism that makes the reference's five `MOVE -1 TO USRIDINL`
   *       sites actually observable in a browser.
   *       Trade-offs: one extra render per cursor move, which is the price of the cursor landing where the
   *       reference puts it. Alternatives Considered: a timer or an animation frame, both of which also
   *       outlive the commit but leave the cursor depending on elapsed time rather than on the render that
   *       caused it, so a slow commit would reintroduce the fault non-deterministically.
   */
  const [focusRequested, setFocusRequested] = useState(false);

  useEffect(
    /**
     * Applies a recorded cursor move once the render that caused it has been committed.
     * @returns {void} Completion is the focused control; an unmounted control is a no-op, and the request
     *   is cleared either way so it can never be replayed on a later render.
     */
    function applyRequestedFocus(): void {
      if (!focusRequested) {
        return;
      }

      fetchKeyControl.current?.focus();
      setFocusRequested(false);
    },
    [focusRequested],
  );

  /*
   * WHY : Assumptions: every request this screen issues is sequenced, and the counter is the ONLY thing
   *       that makes an outcome's arrival order irrelevant. Both operations resolve asynchronously, and
   *       nothing in a promise's resolution order relates it to the turn the operator is now waiting on,
   *       so the LAST answer to arrive would otherwise win whichever question it answered.
   * WHY : Assumptions: the `busy` guards on the key handlers do NOT close this on their own, because the
   *       competing turn is not always a key press. The mount effect below re-runs whenever the route
   *       names a different user, so navigating from `/users/A/delete` to `/users/B/delete` issues B's
   *       read with A's still outstanding -- with no key pressed and no handler to guard. If A's answer
   *       arrived second it would display A's name and type beneath B's identifier, and the delete arm
   *       destroys whatever the fetch key holds, so an operator could confirm a deletion against a record
   *       that is not the one on the glass. That is the most serious failure this screen has, which is why
   *       it is sequenced rather than merely guarded.
   * WHY : Trade-offs: requests are IGNORED rather than aborted. `getUser` and `deleteUser` in
   *       `ui/src/api/auth.ts` accept no abort signal, so aborting would mean changing the transport
   *       contract for every caller of both; the cost of ignoring is a response body already on the wire
   *       being discarded, which the operator cannot observe and which cannot produce a wrong screen. This
   *       is the same trade-off the sibling update screen records for the same reason, and the two use one
   *       shape deliberately.
   */
  const turnSequence = useRef(0);

  /**
   * Opens a new turn, superseding any outcome still in flight.
   * @returns {number} The token this turn's outcome must present to be applied.
   */
  function beginTurn(): number {
    const token = turnSequence.current + 1;
    turnSequence.current = token;
    return token;
  }

  /**
   * Reports whether an outcome belongs to the turn the operator is still waiting on.
   * @param {number} token - The token the outcome captured when its turn opened.
   * @returns {boolean} `true` when no later turn has opened since.
   */
  function isCurrentTurn(token: number): boolean {
    return turnSequence.current === token;
  }

  /**
   * Invalidates any outcome still in flight without opening a turn of its own.
   *
   * Assumptions: this is what CLEARING does, and what leaving the screen does. Neither issues a request,
   * so neither should have a token waiting on it -- but both mean the outstanding question is about a
   * screen state the operator has abandoned, so its answer must not be applied.
   * @returns {void} Completion is the advanced sequence.
   */
  function invalidateTurnsInFlight(): void {
    turnSequence.current += 1;
  }

  useEffect(
    /**
     * Registers the unmount invalidation, so an answer cannot be applied to a screen that has gone.
     * @returns {() => void} The cleanup that makes an outstanding turn inert.
     */
    (): (() => void) => invalidateTurnsInFlight,
    [],
  );

  const applyFailureReport = useCallback(
    /**
     * Publishes a reduced failure as the reference does: one sentence, field marks and one cursor move.
     * @param {UserDeleteFailureReport} report - The reduced failure, from
     *   {@link describeUserDeleteFailure}.
     * @returns {void} Completion is represented by the screen's own state.
     */
    (report: UserDeleteFailureReport): void => {
      setBusy(false);
      setMessage(report.message);
      setSeverity('error');
      setFieldErrors(report.fieldErrors);

      if (report.focusKey) {
        setFocusRequested(true);
      }
    },
    [],
  );

  /**
   * Refuses a blank fetch key exactly as both of the reference's guard arms do.
   *
   * Assumptions: the sentence and the cursor move are identical on the two arms -- L145-L150 on the fetch
   * and L177-L182 on the delete raise the same literal and both move `-1` into `USRIDINL` -- so one
   * function serves both and the two paths cannot drift apart.
   *
   * Assumptions: the refusal carries the `BLANK` state, which is what makes the `'*'` marker appear.
   * `app/cpy/CSSETATY.cpy` moves the error colour into a field when its flag is not-OK OR blank (L18-L19)
   * but writes the literal asterisk into the field's output subfield ONLY in the blank case (L23-L26), so
   * the two conditions are not interchangeable and this one is by definition the blank one.
   * @returns {void} Completion is represented by the screen's own state; no request is issued.
   */
  function refuseBlankFetchKey(): void {
    setFieldErrors([
      { field: 'userId', state: 'BLANK', message: SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY },
    ]);
    setMessage(SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY);
    setSeverity('error');
    setFocusRequested(true);
  }

  /**
   * Reads one user and displays the row, which is the reference's Enter arm (`PROCESS-ENTER-KEY`).
   *
   * Assumptions: the three displayed values are blanked BEFORE the read is issued, because L157-L159
   * moves `SPACES` into `FNAMEI`, `LNAMEI` and `USRTYPEI` and only then performs `READ-USER-SEC-FILE` at
   * L161. An operator therefore never sees one row's values beside another row's identifier, not even
   * briefly -- which matters more on this screen than on its siblings, because the record shown is the
   * record the delete arm destroys.
   *
   * Refactoring Rationale: the read is issued through `getUser`, the SAME operation the update screen
   * uses. Its own contract names it "the shared load contract for the update and delete views", so adding
   * a second read for this screen would give one service operation two clients that could diverge.
   * @param {string} userId - Identifier to read, as the control or the route supplied it.
   * @returns {void} Completion is represented by the screen's own state; every failure of the request is
   *   reduced to a band sentence and field marks rather than propagated.
   */
  const load = useCallback(
    /**
     * Reads one user by identifier and displays the row for confirmation.
     * @param {string} userId - Identifier to read, as the control or the route supplied it.
     * @returns {void} Completion is represented by the screen's own state; a blank key is refused without
     *   a request, and every failure of the request that is issued is reduced to a band sentence and field
     *   marks rather than propagated.
     */
    (userId: string): void => {
      /*
       * WHY : Assumptions: the turn opens BEFORE the blank test, so a refusal supersedes an outstanding
       *       read as surely as a request does. A read the operator has replaced with a refusal is still a
       *       read whose answer would display a record, and the refusal leaves the identifier on the
       *       screen -- so an older answer arriving afterwards would fill the record view beneath a
       *       sentence saying the identifier is empty, with the delete key live against it.
       */
      const token = beginTurn();

      if (isBlankUserId(userId)) {
        /*
         * WHY : Assumptions: the blank key is refused WITHOUT issuing a request, because L145-L150 raises
         *       the sentence, places the cursor and never reaches the read at L161. The typed value is kept
         *       so the refusal names a control still holding what the operator left in it.
         */
        setValues(
          /**
           * Keeps the typed key and empties the record view, since no row is displayed for a blank key.
           * @param {UserDeleteValues} previous - Values as they stand.
           * @returns {UserDeleteValues} The typed key with the three displayed values emptied.
           */
          (previous: UserDeleteValues): UserDeleteValues => ({
            ...previous,
            userId,
            firstName: '',
            lastName: '',
            userType: '',
          }),
        );
        setLoaded(false);
        refuseBlankFetchKey();
        /*
         * WHY : Assumptions: the flag is cleared here because this arm opened a turn -- deliberately, so an
         *       outstanding read cannot display a record beneath a refusal -- and then issued no request,
         *       so nothing else will clear it. The read it superseded returns without touching the flag,
         *       because a superseded answer must not re-enable keys while a NEWER request is in flight.
         */
        setBusy(false);
        return;
      }

      setValues(
        /**
         * Records the key and blanks the three displayed values, as L157-L159 does before reading.
         * @param {UserDeleteValues} previous - Values as they stand.
         * @returns {UserDeleteValues} The key alone, with the record view emptied.
         */
        (previous: UserDeleteValues): UserDeleteValues => ({
          ...previous,
          userId,
          firstName: '',
          lastName: '',
          userType: '',
        }),
      );
      setLoaded(false);
      setFieldErrors([]);
      setMessage(null);
      setSeverity('error');
      setBusy(true);

      /*
       * WHY : Assumptions: the two-argument `.then(onFulfilled, onRejected)` form is used rather than a
       *       trailing `.catch`, because `ui/eslint.config.js` runs `no-floating-promises` with
       *       `ignoreVoid: false` and recognises a promise as handled when both arms are supplied. It also
       *       keeps the rejection arm from catching a fault thrown by the success arm, which a trailing
       *       `.catch` would swallow and report as a lookup failure.
       */
      getUser(userId.trim()).then(
        /**
         * Displays the row and states that it is now awaiting the confirming keystroke.
         * @param {UserResponse} found - The row as the service returned it.
         * @returns {void} Completion is represented by the screen's own state.
         */
        (found: UserResponse): void => {
          /*
           * WHY : Assumptions: a superseded answer returns having touched NOTHING, and `busy` in
           *       particular is left alone. It describes the turn now in flight rather than this one, so
           *       clearing it here would re-enable the keys -- including the delete key -- while a newer
           *       request was still outstanding.
           */
          if (!isCurrentTurn(token)) {
            return;
          }

          setValues(
            /**
             * Seeds the three displayed values from the row, leaving the keyed identifier as typed.
             * @param {UserDeleteValues} previous - Values as they stand.
             * @returns {UserDeleteValues} The same values carrying the row.
             */
            (previous: UserDeleteValues): UserDeleteValues => ({
              ...previous,
              firstName: found.firstName,
              lastName: found.lastName,
              userType: found.userType,
            }),
          );
          setLoaded(true);
          setBusy(false);
          /*
           * WHY : Assumptions: this sentence is INFORMATIONAL, not a refusal, and the distinction is the
           *       reference's own: L283 sets it and L285 moves `DFHNEUTR` -- the neutral attribute -- into
           *       the message field's colour, where every refusal on this screen moves the red one.
           *       Rendering it as an error would tell an operator a successful read had failed, turning the
           *       normal path of this screen into an apparent fault.
           */
          setMessage(DELETE_MESSAGES.PRESS_PF5_KEY_TO_DELETE_THIS_USER);
          setSeverity('info');
          /*
           * WHY : Assumptions: the cursor returns to the fetch key after a successful read, because L152
           *       moves `-1` into `USRIDINL` on the arm that proceeds to read and the map is then sent
           *       `CURSOR` at L219-L225. It is requested here rather than before the request because the
           *       control is disabled while the read is in flight and a disabled control cannot take focus
           *       -- which also matches the reference's timing, where the send follows the read.
           */
          setFocusRequested(true);
        },
        /**
         * Reduces a refused read to the reference's own read-arm sentences.
         * @param {unknown} failure - Whatever the read rejected with.
         * @returns {void} Completion is represented by the screen's own state.
         */
        (failure: unknown): void => {
          /*
           * WHY : Assumptions: a superseded FAILURE is discarded on the same terms as a superseded success.
           *       A refusal is an answer too, so applying it would put `User ID NOT found...` and a marker
           *       on a screen the operator has since pointed at a different user.
           */
          if (!isCurrentTurn(token)) {
            return;
          }

          applyFailureReport(describeUserDeleteFailure(failure, 'read'));
        },
      );
    },
    [applyFailureReport],
  );

  useEffect(
    /**
     * Reads the row named by the route once on mount, and again if the route names a different one.
     *
     * Refactoring Rationale: this is what the reference's prefill-then-fetch becomes once selection
     * context moves into the URL. L99-L102 tests `CDEMO-CU03-USR-SELECTED` against `SPACES AND
     * LOW-VALUES`, moves it into `USRIDINI` and performs `PROCESS-ENTER-KEY` at L103 -- so the row is
     * already on the glass before the operator presses anything. The session struct that carried the value
     * is removed by AAP section 0.7.1 and the route parameter stands in for it, so the auto-fetch is
     * preserved behaviour rather than an addition.
     *
     * Assumptions: the effect is skipped entirely when the route carries no identifier, which is the
     * reference's own guard -- it tests the carrier before using it -- and leaves the screen waiting for a
     * typed identifier rather than refusing a blank one the operator never entered.
     * @returns {void} Completion is represented by the screen's own state.
     */
    function loadRouteUser(): void {
      if (routeUserId === undefined) {
        return;
      }

      load(routeUserId);
    },
    [load, routeUserId],
  );

  /**
   * Reads the row named by the fetch key, which is the reference's Enter arm.
   * @returns {void} Completion is represented by the screen's own state; {@link load} reduces every
   *   failure of the request it issues, so no rejection escapes here.
   */
  function handleFetch(): void {
    /*
     * WHY : Assumptions: a fetch arriving while a turn is in flight is ignored, and the guard is written
     *       here as well as on the binding because a disabled binding reports through the invalid-key
     *       channel, whose sentence would claim the screen does not offer a key whose legend is on the
     *       glass. The reference needs no guard at all: it processes one terminal turn at a time, so a
     *       second Enter could not arrive while the first was still running L142-L169.
     */
    if (busy) {
      return;
    }

    load(values.userId);
  }

  /**
   * Opens the deletion confirmation, which is the first half of the reference's PF5 arm.
   *
   * Refactoring Rationale: the reference gates a deletion behind a SECOND, DIFFERENT KEYSTROKE -- its read
   * arm displays the record and invites `'Press PF5 key to delete this user ...'` (L283), so the operator
   * confirms by pressing a key they have not yet pressed. A keystroke cannot survive as a keystroke in a
   * browser, where the same key is one press away at any moment, so the gate is reconstructed as a modal
   * confirmation. `Popconfirm` with `okType="danger"` is the design system's own component for exactly
   * this, so the safeguard is preserved rather than dropped.
   *
   * Assumptions: PF5 REMAINS BOUND to this action rather than being replaced by the modal. The reference
   * screen was keyboard-only -- a 3270 had no pointer -- so unbinding the key and leaving only a clickable
   * trigger would remove the workflow the original guarantees. The key opens the confirmation and the
   * confirmation issues the request, which is two deliberate steps in both interfaces.
   *
   * Assumptions: the blank-key guard runs BEFORE the confirmation opens, because L177-L182 raises its
   * sentence and returns without reaching `READ-USER-SEC-FILE` or the delete at L190-L191. Opening a modal
   * that asked an operator to confirm destroying a record named by an empty key would invent a prompt the
   * reference never shows.
   * @returns {void} Completion is either the opened confirmation or a banded refusal; no request is issued
   *   by this function.
   */
  function requestDelete(): void {
    if (busy) {
      return;
    }

    if (isBlankUserId(values.userId)) {
      refuseBlankFetchKey();
      return;
    }

    setConfirmOpen(true);
  }

  /**
   * Deletes the row once the confirmation has been accepted, which is the reference's `DELETE-USER-INFO`.
   *
   * Refactoring Rationale: the reference performs TWO file operations here -- `EXEC CICS READ ... UPDATE`
   * at L269-L278 followed by a `RIDFLD`-less `EXEC CICS DELETE` at L307-L311 -- and the target issues ONE
   * HTTP delete. That pair is the CICS read-for-update LOCKING PROTOCOL, not a business rule: the read
   * acquires the exclusive lock the delete then consumes, which is why the delete needs no record
   * identifier of its own. HTTP has no such protocol and the service performs the removal atomically, so
   * reproducing the read would issue a request whose only purpose was to satisfy a lock manager that no
   * longer exists.
   *
   * Trade-offs: there is exactly ONE not-found path, and that is faithful rather than a simplification.
   * `DELETE-USER-INFO` does not re-guard between its read and its delete -- L188-L192 performs both
   * unconditionally -- so a missing user produces `User ID NOT found...` whether the read or the delete
   * discovers it (L325 as well as L289). Splitting the target into two not-found outcomes would invent a
   * distinction the reference does not make.
   *
   * Assumptions: the request carries `confirmed` as the literal `true`, which `deleteUser` declares as a
   * REQUIRED parameter with no default. That flag is the transport-level half of the same gate the modal
   * provides at the interface level, and its own contract explains why it cannot be defaulted: without it
   * a prefetch, a retried request or a crawler following a link could destroy a row. The two halves are
   * deliberately independent, so neither this call nor the modal may be bypassed and they must not be
   * collapsed into one.
   * @returns {void} Completion is represented by the screen's own state; every failure of the request is
   *   reduced to a band sentence and field marks rather than propagated.
   */
  function confirmDelete(): void {
    const userId = values.userId;
    const token = beginTurn();

    setConfirmOpen(false);
    setFieldErrors([]);
    setMessage(null);
    setSeverity('error');
    setBusy(true);

    deleteUser(userId.trim(), true).then(
      /**
       * Empties every field and acknowledges the deletion, as the reference's success arm does.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (): void => {
        if (!isCurrentTurn(token)) {
          return;
        }

        /*
         * WHY : Assumptions: ALL FOUR values are emptied, including the fetch key, because the reference's
         *       success arm performs `INITIALIZE-ALL-FIELDS` at L315 and that paragraph blanks `USRIDINI`
         *       as well as the three displayed fields (L351-L355). Keeping the key would leave a live
         *       delete trigger pointing at a row that no longer exists.
         */
        setValues(EMPTY_VALUES);
        setLoaded(false);
        setBusy(false);
        /*
         * WHY : Assumptions: the acknowledgement is banded at `'success'` severity because L317 moves
         *       `DFHGREEN` into the message field's colour before composing the sentence -- the reference
         *       recolours this one line to green, exactly as it recolours the awaiting-confirmation line to
         *       neutral. The two moves together are the severity contract for this screen, so both are
         *       honoured rather than defaulting either to the error colour every refusal uses.
         */
        setMessage(formatUserDeletedMessage(userId));
        setSeverity('success');
        /*
         * WHY : Assumptions: the cursor returns to the now-empty fetch key, because `INITIALIZE-ALL-FIELDS`
         *       moves `-1` into `USRIDINL` at L351 before blanking anything, so the reference leaves the
         *       operator positioned to name the next user.
         */
        setFocusRequested(true);
      },
      /**
       * Reduces a refused deletion to the reference's own delete-arm sentences.
       * @param {unknown} failure - Whatever the deletion rejected with.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (failure: unknown): void => {
        if (!isCurrentTurn(token)) {
          return;
        }

        applyFailureReport(describeUserDeleteFailure(failure, 'delete'));
      },
    );
  }

  /**
   * Empties the screen, which is the reference's PF4 arm (`CLEAR-CURRENT-SCREEN`, L341-L344).
   *
   * Assumptions: the arm blanks the fetch key, the three displayed values AND the message, because
   * `INITIALIZE-ALL-FIELDS` includes `WS-MESSAGE` among its targets at L356 -- so clearing removes the
   * sentence as well as the data rather than leaving the last outcome banded over an empty screen.
   * @returns {void} Completion is represented by the screen's own state; any outstanding answer is made
   *   inert rather than applied.
   */
  function clearScreen(): void {
    /*
     * WHY : Assumptions: clearing INVALIDATES an outstanding turn without opening one. The operator has
     *       abandoned the question, so an answer arriving afterwards must not repopulate the record view
     *       they just emptied -- which on this screen would leave a live delete trigger over a record they
     *       did not ask to see.
     */
    invalidateTurnsInFlight();
    setValues(EMPTY_VALUES);
    setLoaded(false);
    setFieldErrors([]);
    setMessage(null);
    setSeverity('error');
    setBusy(false);
    setConfirmOpen(false);
    setFocusRequested(true);
  }

  /**
   * Leaves the screen for the route it was entered from, which is the reference's PF3 arm.
   *
   * Assumptions: the reference prefers `CDEMO-FROM-PROGRAM` and falls back to `'COADM01C'` when that field
   * is blank (L111-L118), which is a two-armed decision the target reproduces with both arms intact. The
   * origin is read from the router transition state and validated by `inApplicationRoute` against a CLOSED
   * set of this application's own routes, because router state is attacker-writable through a hand-edited
   * history entry -- an unvalidated path-shaped value could carry the operator out of the application on a
   * key press they believe goes back one screen. Anything the set does not admit falls through to the
   * administrative menu, which is the reference's own default.
   *
   * ⚠️ Assumptions: the user browse is the origin this screen is normally entered with, and it now
   * resolves to itself rather than to the fallback. `ui/src/screens/userList/index.tsx` hands
   * `{ from: USER_LIST_ROUTE }` over on the row action that opens this screen, matching
   * `app/cbl/COUSR00C.cbl` L203-L204 where the browse moves its own program name into
   * `CDEMO-FROM-PROGRAM` before the transfer, and `/users` is declared in `ui/src/routes/navigation.ts`
   * inside the closed set `inApplicationRoute` matches against -- so the preference arm is reached and
   * the operator is returned to the page they selected a row on. The administrative menu remains the
   * answer for an arrival that names no origin: a directly-entered path, a reload, or the full-document
   * fallback in `navigateSafely`, which starts a history entry carrying no state. That is the
   * reference's own default arm, so the unnamed case is a behaviour the source has rather than a
   * degradation introduced here.
   * @returns {void} Completion is the requested route transition.
   */
  function exitToOrigin(): void {
    const origin = inApplicationRoute(screenTransitionState(location.state).from);

    invalidateTurnsInFlight();
    navigateSafely(navigate, origin ?? ADMIN_MENU_ROUTE);
  }

  /**
   * Leaves the screen for the administrative menu, which is the reference's PF12 arm (L123-L125).
   *
   * Assumptions: this arm is UNCONDITIONAL where PF3's is not. The reference moves `'COADM01C'` into
   * `CDEMO-TO-PROGRAM` with no preference for the calling program at all, so PF12 always returns to the
   * administrative menu even when PF3 on the same screen would have gone elsewhere. The two arms are kept
   * distinct for that reason rather than sharing one destination.
   * @returns {void} Completion is the requested route transition.
   */
  function exitToAdminMenu(): void {
    invalidateTurnsInFlight();
    navigateSafely(navigate, ADMIN_MENU_ROUTE);
  }

  /**
   * Records the typed fetch key and drops the refusal it was carrying.
   *
   * Assumptions: the refusal is cleared on the first keystroke rather than left until the next submission,
   * because the reference's field highlight is applied by a `SEND MAP` and is therefore gone the moment the
   * operator types into the field -- the terminal repaints the attribute on the next turn, not on every
   * character. Leaving a mark beneath a value the operator has already corrected would outlast the
   * condition it describes.
   * @param {ChangeEvent<HTMLInputElement>} event - Change event the control produced.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function handleFetchKeyChange(event: ChangeEvent<HTMLInputElement>): void {
    const typed = event.target.value;

    setValues(
      /**
       * Applies the typed key, leaving the displayed record untouched.
       * @param {UserDeleteValues} previous - Values as they stand.
       * @returns {UserDeleteValues} The same values carrying the typed key.
       */
      (previous: UserDeleteValues): UserDeleteValues => ({ ...previous, userId: typed }),
    );
    setFieldErrors([]);
  }

  /**
   * Records the fetch control so a refusal or a completed turn can return the cursor to it.
   * @param {InputRef | null} control - The mounted control, or `null` as it unmounts.
   * @returns {void} Completion is the updated reference.
   */
  function registerFetchKeyControl(control: InputRef | null): void {
    fetchKeyControl.current = control;
  }

  /*
   * WHY : Assumptions: FIVE attention identifiers are registered where the row-24 legend advertises FOUR,
   *       and the difference is the reference's own. It binds `DFHENTER`, `DFHPF3`, `DFHPF4`, `DFHPF5` and
   *       `DFHPF12` at L109-L125 while its legend literal at L148 names only Enter, F3, F4 and F5 -- so
   *       PF12 works and is not published. Both facts are preserved deliberately: the binding below carries
   *       no `label`, which `PfKeyBar` renders as a keyboard-only handler, so the legend still reads exactly
   *       as the mapset paints it while the key an operator may have learned still works.
   * WHY : Assumptions: `PFK05` keeps the `save` action `DEFAULT_PF_KEY_ACTIONS` assigns it rather than being
   *       overridden to something delete-shaped. The action is presentation and analytics metadata, and on
   *       this mapset PF5 is the screen's committing key exactly as it is on its update sibling; the label
   *       is what tells an operator what it commits, and that label is the mapset's own `F5=Delete`.
   * WHY : Trade-offs: ENTER and PF5 are DISABLED while a turn is in flight and PF4 and PF12 are not. The
   *       first two would start a competing turn -- and PF5 in particular would open a confirmation over a
   *       record still being read -- while PF4 aborts the outstanding turn and PF12 leaves the screen, so
   *       greying either would trap an operator on a screen whose only unresponsive keys were the ones
   *       offering a way off it. PF3 is left enabled for the same reason as PF12.
   */
  const keyHandlers: PfKeyHandlerMap = {
    ENTER: { onInvoke: handleFetch, label: USER_DELETE_KEY_LABELS.ENTER, disabled: busy },
    PFK03: { onInvoke: exitToOrigin, label: USER_DELETE_KEY_LABELS.PFK03 },
    PFK04: { onInvoke: clearScreen, label: USER_DELETE_KEY_LABELS.PFK04 },
    PFK05: { onInvoke: requestDelete, label: USER_DELETE_KEY_LABELS.PFK05, disabled: busy },
    PFK12: { onInvoke: exitToAdminMenu },
  };

  const { bindings, invoke } = usePfKeys(keyHandlers, {
    /*
     * WHY : Assumptions: the invalid-key arm states the sentence and does NOT move the cursor, because the
     *       reference's `WHEN OTHER` arm at L126-L129 sets the error flag and the message and sends the map
     *       with nothing else -- unlike its other arms it moves no `-1` into a length subfield. `usePfKeys`
     *       offers a `restoreFocusRef` option for screens whose invalid-key arm does move the cursor, and it
     *       is deliberately not supplied.
     * WHY : Assumptions: the sentence is the shared `INVALID_KEY_PRESSED` constant, which is the same
     *       `CCDA-MSG-INVALID-KEY` value the reference moves at L128 -- declared once at
     *       `app/cpy/CSMSG01Y.cpy` L20-L21 and already published on the hook's rejection payload. It is read
     *       from the catalog rather than from that payload so this screen has one source for every sentence
     *       it paints.
     */
    /**
     * Reports a key the screen does not bind, using the reference's own invalid-key sentence.
     * @param {PfKeyRejection} rejection - Why the key was refused, and by which attention identifier.
     * @returns {void} Completion is represented by the screen's own state.
     */
    onInvalidKey: (rejection: PfKeyRejection): void => {
      /*
       * WHY : Assumptions: a key refused because it is momentarily DISABLED is silent, and only an UNMAPPED
       *       one raises the sentence. The reference's `WHEN OTHER` arm answers for keys outside its list of
       *       five; a key this screen registers and greys out for the duration of one turn is not outside
       *       that list, so answering `Invalid key pressed...` for it would tell an operator the screen does
       *       not offer a key whose own legend is on the glass in front of them.
       */
      if (rejection.reason === 'disabled') {
        return;
      }

      setMessage(INVALID_KEY_PRESSED);
      setSeverity('error');
    },
  });

  /*
   * WHY : Assumptions: all three persistent zones are DELEGATED to `ui/src/layout/AppShell.tsx` -- the
   *       row-1/2 title band, the row-23 message line and the row-24 key legend -- and none is composed
   *       here. That shell paints a zone if and only if a screen has published one, so a screen that also
   *       rendered its own would show two of each: two live regions announcing one sentence and a duplicate
   *       legend. This screen's mapset declares a single message field, `ERRMSG` at `POS=(23,1)` (L140), and
   *       no row-22 informational line, so the one band the shell owns is the only band this screen needs.
   * WHY : Assumptions: the severity travels WITH the text because this one channel carries three different
   *       kinds of outcome -- the neutral awaiting-confirmation line, the green acknowledgement and the red
   *       refusals -- and the reference recolours the field for each. Publishing text alone would paint an
   *       acknowledged deletion in the refusal colour.
   * WHY : Assumptions: no `legendColor` is delegated, because the mapset paints this screen's row-24 field
   *       `COLOR=YELLOW` at L145, which is the slot's own default -- so stating it would restate a default
   *       rather than override one.
   * WHY : Assumptions: delegating `pfKeys` hands the shell the bindings to RENDER while this screen keeps the
   *       keyboard: `bindings` and `invoke` both come from its own `usePfKeys` call and travel up unchanged,
   *       so an activation of a rendered legend control is forwarded straight back to the same dispatcher a
   *       real key press uses and the two cannot diverge.
   */
  useShellSlot({
    screen: {
      transactionId: USER_DELETE_TRANSACTION_ID,
      programName: USER_DELETE_PROGRAM_NAME,
    },
    now: paintedAt,
    message: { text: message, severity, mapset: USER_DELETE_MAPSET },
    pfKeys: {
      keys: bindings,
      onInvoke: invoke,
    },
  });

  /*
   * WHY : Assumptions: every design value below is a TOKEN NAME resolved through `cssVar`, so this file
   *       carries no colour, weight, spacing or font literal -- the zero-hardcoded-values rule of AAP
   *       section 0.3.2, whose only exempt values are `0`, `none`, `auto`, `inherit`, `currentColor` and
   *       `transparent`. The colour roles are the mapset's own measured attributes mapped by the token
   *       bridge: `COLOR=NEUTRAL` on the caption (L76) resolves to the secondary text role, `COLOR=GREEN`
   *       on the fetch label (L81) to the base text role, `COLOR=TURQUOISE` on the three record labels
   *       (L99, L112, L126) to the label role, `COLOR=BLUE` on the user-type hint (L136) to the primary
   *       text role, and `COLOR=YELLOW` on the row-8 rule (L93) to the warning role. `ATTRB=BRT` on the
   *       caption (L75) is carried by the strong-weight token rather than by a colour, which is what keeps
   *       brightness and colour independent in the target the way the mapset has them independent.
   * WHY : Assumptions: `HILIGHT=UNDERLINE` -- which the mapset sets on all four data fields (L87, L105,
   *       L118, L132) -- contributes NO token and no style at all. It is AAP gap G4: the underline was a
   *       3270's only way to show where a field began and ended, and the design system expresses that
   *       structurally through the `Input` border and the `Descriptions` cell rules. Adding an underline on
   *       top would draw the affordance twice.
   */
  const captionStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };
  const fetchLabelStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.GREEN] };
  const recordLabelStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] };
  const hintStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.BLUE] };
  const blankMarkerStyle: CSSProperties = { color: cssVar[FIELD_ERROR_TOKENS.errorColor] };
  /*
   * WHY : Assumptions: the rule's colour is set through the LOGICAL property `border-block-start-color`
   *       rather than `border-top-color`. The design-system invariants require logical properties
   *       throughout, and antd draws a horizontal `Divider` with `border-block-start`, so naming the logical
   *       edge overrides the colour the component already sets instead of adding a second, physical
   *       declaration that would only agree with it in a left-to-right document.
   */
  const ruleStyle: CSSProperties = { borderBlockStartColor: cssVar[BMS_COLOR_TOKENS.YELLOW] };
  /*
   * WHY : Assumptions: the identifier is rendered in the fixed-pitch face because the token bridge maps
   *       identifier columns to the code face. `SEC-USR-ID` is a fixed eight-character key
   *       (`app/cpy/CSUSR01Y.cpy` L18) that a terminal displayed in a monospaced cell grid, so a
   *       proportional face would make two identifiers of equal length render at different widths -- one of
   *       the few properties of the 3270 presentation a browser can still keep. The two names are free text
   *       and are deliberately left in the body face.
   */
  const fixedPitchStyle: CSSProperties = { fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData] };

  const fetchKeyId = `${idPrefix}userId`;
  const refusal = fieldErrors[0];

  /*
   * WHY : Trade-offs: the mapset's absolute geometry is NOT reproduced, and this is AAP gap G1 taken
   *       deliberately. `DFHMDI SIZE=(24,80)` fixes a 24-row by 80-column character grid and all 26 field
   *       definitions carry an absolute `POS=(row,column)`, so a faithful rendering would need character
   *       cells at fixed coordinates. What is preserved is what survives translation: the GROUPING, the
   *       READING ORDER and the TAB ORDER. Each block below is one of the mapset's own rows -- 4 for the
   *       caption, 6 for the fetch key, 8 for the rule, and 11, 13 and 15 for the three record values in
   *       that order. What is given up is pixel-for-character positioning, which no browser holds across
   *       viewport widths and which would be hostile to an operator using magnification or a screen reader.
   */
  return (
    <Flex vertical gap="large">
      {/*
       * Assumptions: this is the mapset's own row-4 field and not the title band, which the shell paints
       * from rows 1 and 2 out of the delegated identity. `ScreenTitle` owns the heading SIZE for every
       * screen while the caller owns the COLOUR, because the colour is a per-mapset attribute.
       */}
      <ScreenTitle style={captionStyle}>{USER_DELETE_CAPTION}</ScreenTitle>
      <Form layout="vertical">
        {/*
         * WHY : Assumptions: this is the mapset's ONLY control. `USRIDIN` at L85 is its single
         *       `UNPROT` field, its single `IC` field and the key both operations are performed
         *       against, so the one `Input` in this file carries the one `autoFocus` -- a second
         *       would not be a second cursor but a race between two controls for the same one.
         */}
        <Form.Item
          label={
            <Typography.Text style={fetchLabelStyle}>
              {USER_DELETE_FIELD_LABELS.userId}
            </Typography.Text>
          }
          htmlFor={fetchKeyId}
          {...(refusal === undefined
            ? {}
            : {
                validateStatus: 'error' as const,
                help: fieldErrorHelp(fetchKeyId, refusal.message),
              })}
        >
          <Input
            {...fieldAriaProps(fetchKeyId, {
              invalid: refusal !== undefined,
              hasError: refusal !== undefined,
              hasHint: false,
            })}
            id={fetchKeyId}
            ref={registerFetchKeyControl}
            value={values.userId}
            maxLength={USER_DELETE_FIELD_WIDTHS.userId}
            onChange={handleFetchKeyChange}
            disabled={busy}
            autoFocus
            style={fixedPitchStyle}
            {...(refusal?.state === 'BLANK'
              ? {
                  /*
                   * WHY : Assumptions: the marker is hidden from assistive technology because it duplicates
                   *       in a symbol what the help text already carries in words.
                   *       `app/cpy/CSSETATY.cpy` L23-L26 writes the literal asterisk into a blank field as
                   *       the terminal's only way to point at one, and the browser points at it with the
                   *       form item's error state and its sentence; announcing a bare asterisk after that
                   *       sentence would add noise rather than information. It is rendered ONLY for the
                   *       blank condition and never for a non-blank refusal, which is the distinction that
                   *       copybook draws between its two arms.
                   */
                  suffix: (
                    <Typography.Text aria-hidden="true" style={blankMarkerStyle}>
                      {FIELD_ERROR_TOKENS.blankMarker}
                    </Typography.Text>
                  ),
                }
              : {})}
          />
        </Form.Item>
      </Form>
      {/*
       * WHY : Refactoring Rationale: the mapset's row-8 rule is rendered as a `Divider` and NOT as its
       *       literal seventy asterisks (`app/bms/COUSR03.bms` L93-L97). A run of asterisks is a
       *       terminal's only way to draw a horizontal line, so the characters are the implementation of a
       *       separator rather than content -- carrying them across verbatim would put seventy asterisks
       *       into the accessibility tree for a screen reader to spell out, and would not reflow.
       *       `Divider` is the semantic separator the design system provides and needs no `aria-hidden`,
       *       because it already carries the separator role the asterisks were standing in for.
       */}
      <Divider style={ruleStyle} />
      {/*
       * WHY : Assumptions: the record is rendered with `Descriptions` rather than as a `Form` of disabled
       *       inputs, because all three fields are PROTECTED in the mapset -- `FNAME`, `LNAME` and
       *       `USRTYPE` are each `ATTRB=(ASKIP,FSET,NORM)` at L103, L116 and L130, with no `UNPROT` among
       *       them. A form of disabled controls would render an editing affordance the reference denies and
       *       would suggest the values could be changed here; `Descriptions` states them as what they are,
       *       a record being shown for confirmation before it is destroyed.
       *       Alternatives Considered: read-only `Input`s carrying the mapset's `LENGTH=` widths, which
       *       would preserve the field widths as well as the values. Rejected because a read-only text box
       *       is still announced as a text box, so the affordance problem survives the attribute.
       * WHY : Assumptions: the column count comes from the shared record-layout constant rather than the
       *       literal `2`, so the grid collapses to one column below the medium breakpoint. Two-up at every
       *       width pushes a bordered table past a narrow viewport, and the reading order the G1 trade-off
       *       commits to is preserved at both widths.
       * WHY : Assumptions: a `Spin` wraps the record view while a turn is in flight. It has no 3270
       *       equivalent -- a terminal simply did not repaint until the task completed -- so it is additive,
       *       and it is kept minimal for that reason: it reports that the values shown are being replaced,
       *       which matters here because the delete trigger acts on the record beside it.
       */}
      <Spin spinning={busy}>
        <Descriptions bordered column={RECORD_VIEW_COLUMNS}>
          <Descriptions.Item
            label={
              <Typography.Text style={recordLabelStyle}>
                {USER_DELETE_FIELD_LABELS.firstName}
              </Typography.Text>
            }
          >
            {values.firstName}
          </Descriptions.Item>
          <Descriptions.Item
            label={
              <Typography.Text style={recordLabelStyle}>
                {USER_DELETE_FIELD_LABELS.lastName}
              </Typography.Text>
            }
          >
            {values.lastName}
          </Descriptions.Item>
          {/*
           * WHY : Assumptions: the user type is rendered as DATA ONLY, beside the mapset's own hint, and
           *       carries no control of any kind. It is authority-shaped information but it is not the
           *       authority: an operator's privileges derive solely from the signed `cognito:groups` claim
           *       per AAP section 0.7.8, and `useAuth` deliberately exposes no setter for it. The value and
           *       the hint share one item because the mapset paints them adjacently on row 15 -- `USRTYPE`
           *       at `POS=(15,17)` and `'(A=Admin, U=User)'` at `POS=(15,19)` -- so keeping them together
           *       preserves the pairing that makes a single character readable.
           */}
          <Descriptions.Item
            label={
              <Typography.Text style={recordLabelStyle}>
                {USER_DELETE_FIELD_LABELS.userType}
              </Typography.Text>
            }
          >
            <Flex gap="small" align="center" wrap>
              <Typography.Text style={fixedPitchStyle}>{values.userType}</Typography.Text>
              <Typography.Text style={hintStyle}>{USER_DELETE_USER_TYPE_HINT}</Typography.Text>
            </Flex>
          </Descriptions.Item>
        </Descriptions>
      </Spin>
      {/*
       * WHY : Assumptions: the confirmation is CONTROLLED by this screen's own state so the PF5 key and a
       *       click on the trigger open the same modal, and accepting it is the only path to
       *       {@link confirmDelete}. `okType="danger"` is the design system's destructive emphasis, which
       *       is what tells an operator which of the two buttons destroys the row.
       * WHY : Assumptions: the prompt is the reference's OWN awaiting-confirmation sentence rather than a
       *       newly written question. `'Press PF5 key to delete this user ...'` at L283 is precisely the
       *       text the reference shows to invite the confirming keystroke, so it is the faithful prompt for
       *       the modal that replaces that keystroke -- and it comes from the message catalog, which holds
       *       the verbatim monopoly for this tree, so no user-visible string is invented here.
       * WHY : Trade-offs: the trigger is disabled until a row has been displayed. The reference reaches its
       *       delete arm from any state and answers `User ID NOT found...` for a key naming nothing, so the
       *       keyboard path is left able to do exactly that -- PF5 with a typed but unfetched key still
       *       issues the guarded delete and still reads the reference's sentence. What is withheld is only
       *       the POINTER affordance, which the reference had no equivalent of at all, and withholding it
       *       keeps a click from destroying a record the operator has not seen.
       */}
      <Popconfirm
        open={confirmOpen}
        title={DELETE_MESSAGES.PRESS_PF5_KEY_TO_DELETE_THIS_USER}
        okType="danger"
        onConfirm={confirmDelete}
        onCancel={
          /**
           * Closes the confirmation without issuing the deletion.
           * @returns {void} Completion is the closed confirmation; the record stays as it is.
           */
          (): void => {
            setConfirmOpen(false);
          }
        }
      >
        <Button
          danger
          disabled={busy || !loaded}
          onClick={
            /**
             * Opens the confirmation through the same guarded path the PF5 key uses.
             * @returns {void} Completion is either the opened confirmation or a banded refusal.
             */
            (): void => {
              requestDelete();
            }
          }
        >
          {USER_DELETE_KEY_LABELS.PFK05}
        </Button>
      </Popconfirm>
    </Flex>
  );
}

/*
 * WHY : Assumptions: the component is published under BOTH its name and as the default, and both keys are
 *       deliberate. AAP section 0.6.2.1 forbids "default-export barrels for screens" -- a barrel being an
 *       index module that re-exports from OTHER modules -- and this `index.tsx` is not a barrel: it is the
 *       screen itself, which is exactly the distinction this file's own specification draws when it
 *       requires the default-exported component. The default is the key actually consumed: `ui/src/router.tsx`
 *       declares `USER_DELETE_PATH` and mounts this screen through the direct
 *       `lazy(() => import('./screens/userDelete'))` form, which resolves the module's default and would
 *       fail to render if only a named key existed.
 *       Trade-offs: two keys name one component where one would do, and today only the default is read.
 *       It is kept because the tree mounts its lazy screens BOTH ways -- the sibling user screen is loaded
 *       through a named-to-default adapter in the same file -- so a module that publishes only the default
 *       would have to be edited again to be reached the other way, and the second key costs nothing at
 *       runtime: both names are the one binding, so neither adds a chunk nor defeats tree-shaking.
 *       Assumptions: `ui/src/routes/navigation.ts` deliberately does NOT carry this path. Its closed route
 *       set is what `inApplicationRoute` validates a PF3 origin against, and a destructive per-record route
 *       is not a place any screen should be able to send an operator by claiming it as a referrer.
 * WHY : Refactoring Rationale: the admin gate is NOT re-implemented here and `useAuth` is deliberately not
 *       imported. `ui/src/router.tsx` owns route authorisation through `RequireAdmin`, so a second check in
 *       this component would be a second place for the policy to be stated -- and a screen-level check is
 *       the weaker of the two anyway, because it runs only once the route has already been entered and the
 *       module already loaded.
 */
export default UserDeleteScreen;
