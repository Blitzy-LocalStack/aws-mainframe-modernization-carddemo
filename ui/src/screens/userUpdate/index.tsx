/**
 * @file The user update screen, migrated from `app/cbl/COUSR02C.cbl` (415 lines) and its mapset
 * `app/bms/COUSR02.bms` (29 `DFHMDF` fields, 12 of them named), mounted at `/users/:id/edit`.
 *
 * Purpose
 * -------
 * Render the reference screen's two-turn fetch-then-save workflow over one administered user: read
 * the row by identifier, let an administrator edit the three values the contract admits, and write
 * the change. It replaces CICS transaction `CU02`, which `app/cbl/COUSR02C.cbl` L37 declares as
 * `WS-TRANID PIC X(04) VALUE 'CU02'`, and it renders the five editable controls the mapset paints
 * plus the ten sentences the program emits.
 *
 * The one screen where PF3 is not "go back"
 * ----------------------------------------
 * Assumptions: this is the ONLY screen in the application whose PF3 saves. Its row-24 legend field
 * (`app/bms/COUSR02.bms` L159-L164) paints `F3=Save&&Exit`, and the dispatch arm at
 * `app/cbl/COUSR02C.cbl` L111-L119 performs `UPDATE-USER-INFO` and only then transfers control.
 * `ui/src/layout/PfKeyBar.tsx` records the same exception from the other side -- it withholds a
 * default label for PF3 precisely because this mapset contradicts the otherwise-reliable reading --
 * so every one of this screen's key labels except `F4=Clear` is stated here rather than inherited.
 *
 * What is deliberately NOT carried across
 * --------------------------------------
 * Refactoring Rationale: the reference pre-fills the password control from the stored plaintext
 * credential -- `MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI` at `app/cbl/COUSR02C.cbl` L169, reading the
 * `05 SEC-USR-PWD PIC X(08).` field at `app/cpy/CSUSR01Y.cpy` L21 -- and this screen never does.
 * `auth.users` carries no password column, identity lives in a managed user pool, and
 * `ui/src/api/auth.ts` states that no password appears on any type outside the sign-on and challenge
 * request bodies. The control therefore renders empty after every fetch, and nothing this screen holds
 * can put a credential on the glass.
 *
 * Refactoring Rationale: the pseudo-conversational session structure is gone, so three of its members
 * are answered from elsewhere. The selected-user carrier `CDEMO-CU02-USR-SELECTED` (L58) becomes the
 * `id` route parameter; the re-entry discriminator `CDEMO-PGM-CONTEXT` disappears entirely, which is
 * why the field-error highlight below is driven by the response body alone; and the operator's own
 * authority is the signed `cognito:groups` claim rather than anything this screen renders or submits.
 */

import { Divider, Flex, Form, Input, Typography, theme } from 'antd';
import type { InputRef } from 'antd';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import type { ChangeEvent, CSSProperties, ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';

import { USER_ID_MAX_LENGTH, getUser, updateUser } from '../../api/auth';
import { isApiRequestError } from '../../api/client';
import type {
  ApiError,
  FieldValidationState,
  UpdateUserRequest,
  UserResponse,
  UserType,
} from '../../api/types';
import { useServerInstant } from '../../hooks/useServerInstant';
import { MessageBand } from '../../layout/MessageBand';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { PfKeyBar, UNIFORM_PF_KEY_LABELS, decodeBmsLegendText } from '../../layout/PfKeyBar';
import { ScreenHeader } from '../../layout/ScreenHeader';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap } from '../../layout/usePfKeys';
import {
  INVALID_KEY_PRESSED,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  formatMessageTemplate,
} from '../../messages/messages';
import type { MapsetName } from '../../messages/messages';
import { ADMIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import { BMS_COLOR_TOKENS, FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/** The two sentences this screen's own program owns, from the catalog that owns every string it paints. */
const UPDATE_MESSAGES = PROGRAM_MESSAGES.COUSR02C;

/** CICS transaction identifier this screen replaces, from `app/cbl/COUSR02C.cbl` L37. */
export const USER_UPDATE_TRANSACTION_ID = 'CU02';

/** Source program name, rendered in the header band as row 2 of the 3270 screen painted it (L36). */
export const USER_UPDATE_PROGRAM_NAME = 'COUSR02C';

/**
 * Mapset this screen stands in, which sizes the message band.
 *
 * Assumptions: `MESSAGE_BAND_BY_MAPSET` records `COUSR02` as a 78-character band, which is the
 * `LENGTH=78` its `ERRMSG` field declares at `app/bms/COUSR02.bms` L155-L158. It is passed explicitly
 * rather than left to the band's default -- which happens to be the same 78 -- because the default
 * exists for a caller that does not know its mapset, and this one does.
 */
export const USER_UPDATE_MAPSET = 'COUSR02' as const satisfies MapsetName;

/**
 * The screen's own caption, verbatim from `app/bms/COUSR02.bms` L75-L79.
 *
 * Assumptions: this is a BODY field and not part of the header band. It is painted at `POS=(4,35)`
 * with `ATTRB=(ASKIP,BRT)` and `COLOR=NEUTRAL` over `LENGTH=11`, below the two 40-character title
 * fields `ScreenHeader` owns on rows 1 and 2, so it belongs to this screen rather than to the shell.
 */
export const USER_UPDATE_CAPTION = 'Update User';

/** One editable control on this screen, named as the update contract names its property. */
export type UserUpdateField = 'userId' | 'firstName' | 'lastName' | 'password' | 'userType';

/**
 * The five field labels, verbatim from `app/bms/COUSR02.bms`.
 *
 * Assumptions: the trailing space on `userType` is part of the value. The mapset declares
 * `INITIAL='User Type: '` at `LENGTH=11` (L140-L144) where the visible text is ten characters, so the
 * eleventh is a space the terminal painted. The transcription rule for this tree is character-exact,
 * and trimming it here would be a silent edit to a user-visible string.
 */
export const USER_UPDATE_FIELD_LABELS = {
  /** `app/bms/COUSR02.bms` L80-L84, `COLOR=GREEN`, `LENGTH=14`, `POS=(6,6)`. */
  userId: 'Enter User ID:',
  /** `app/bms/COUSR02.bms` L98-L102, `COLOR=TURQUOISE`, `LENGTH=11`, `POS=(11,6)`. */
  firstName: 'First Name:',
  /** `app/bms/COUSR02.bms` L111-L115, `COLOR=TURQUOISE`, `LENGTH=10`, `POS=(11,45)`. */
  lastName: 'Last Name:',
  /** `app/bms/COUSR02.bms` L125-L129, `COLOR=TURQUOISE`, `LENGTH=9`, `POS=(13,6)`. */
  password: 'Password:',
  /** `app/bms/COUSR02.bms` L140-L144, `COLOR=TURQUOISE`, `LENGTH=11`, `POS=(15,17)`. */
  userType: 'User Type: ',
} as const satisfies Readonly<Record<UserUpdateField, string>>;

/**
 * The two hints the mapset paints beside a control, verbatim and in `COLOR=BLUE`.
 *
 * Assumptions: the user-type hint is the ONLY place the `'A'`/`'U'` domain is advertised to an
 * operator, because `app/cbl/COUSR02C.cbl` contains no domain check for that field -- L204 tests it
 * for blank and nothing else. Dropping the hint would leave the domain undiscoverable.
 */
export const USER_UPDATE_FIELD_HINTS = {
  /** `app/bms/COUSR02.bms` L135-L139, `COLOR=BLUE`, `LENGTH=8`, `POS=(13,25)`. */
  password: '(8 Char)',
  /** `app/bms/COUSR02.bms` L150-L154, `COLOR=BLUE`, `LENGTH=17`, `POS=(15,19)`. */
  userType: '(A=Admin, U=User)',
} as const;

/** How one control is presented, with every member measured from the mapset's own operands. */
interface UserUpdateFieldPresentation {
  /** Measured `COLOR=` role of the control's label, resolved through `BMS_COLOR_TOKENS`. */
  readonly labelTone: keyof typeof BMS_COLOR_TOKENS;
  /** Whether the mapset places the initial cursor here, from its single `IC` operand. */
  readonly initialCursor?: boolean;
  /** Whether the mapset renders the control non-display, from its `DRK` operand. */
  readonly secret?: boolean;
  /** Whether the value is an identifier column and takes the fixed-pitch face. */
  readonly fixedPitch?: boolean;
  /** Verbatim hint the mapset paints beside the control, when it paints one. */
  readonly hint?: string;
}

/*
 * WHY : Assumptions: `initialCursor` is set on the identifier ALONE, because `app/bms/COUSR02.bms`
 *       carries exactly one `IC` operand -- on `USRIDIN` at L85 -- and a 3270 map has one initial cursor
 *       position by construction. `app/cbl/COUSR02C.cbl` agrees from the program side: L98 moves `-1`
 *       into `USRIDINL` on first entry, before anything else is decided. A second `autoFocus` in a
 *       browser is not a second cursor but a race between two controls for the same one.
 */
const FIELD_PRESENTATION: Readonly<Record<UserUpdateField, UserUpdateFieldPresentation>> = {
  userId: { labelTone: 'GREEN', initialCursor: true, fixedPitch: true },
  firstName: { labelTone: 'TURQUOISE' },
  lastName: { labelTone: 'TURQUOISE' },
  password: { labelTone: 'TURQUOISE', secret: true, hint: USER_UPDATE_FIELD_HINTS.password },
  userType: { labelTone: 'TURQUOISE', hint: USER_UPDATE_FIELD_HINTS.userType },
};

/*
 * WHY : Assumptions: every width below is the mapset's own `LENGTH=` operand, and each is corroborated
 *       independently by the record layout so neither source is trusted alone. `USRIDIN` is
 *       `LENGTH=8` at `app/bms/COUSR02.bms` L85-L89 and `05 SEC-USR-ID PIC X(08).` at
 *       `app/cpy/CSUSR01Y.cpy` L18; `FNAME` is `LENGTH=20` at L103-L107 and `PIC X(20)` at L19;
 *       `LNAME` is `LENGTH=20` at L116-L120 and `PIC X(20)` at L20; `PASSWD` is `LENGTH=8` at
 *       L130-L134 and `PIC X(08)` at L21; `USRTYPE` is `LENGTH=1` at L145-L149 and `PIC X(01)` at L22.
 *       These are the terminal's own field widths, which is how a 3270 refused a keystroke past the
 *       end of a field, so `maxLength` is the faithful browser equivalent rather than a convenience.
 * WHY : Alternatives Considered: `PASSWORD_MAX_LENGTH` from `ui/src/api/auth.ts`, which is 256. It is
 *       rejected here because that constant bounds the SIGN-ON request body, where the credential is
 *       the identity provider's and may legitimately be longer than the baseline's field. This control
 *       submits nothing at all (see the request note on `save` below), so the only bound with meaning
 *       on this screen is the 3270 field width the mapset and the copybook agree on.
 */
export const USER_UPDATE_FIELD_WIDTHS = {
  userId: USER_ID_MAX_LENGTH,
  firstName: 20,
  lastName: 20,
  password: 8,
  userType: 1,
} as const satisfies Readonly<Record<UserUpdateField, number>>;

/*
 * WHY : Assumptions: the labels are transcribed in the mapset's OWN spelling -- including the doubled
 *       ampersand -- and decoded by `decodeBmsLegendText`, rather than being retyped in their
 *       displayed form. BMS source is assembler macro source, in which `&` opens a variable symbol, so
 *       `app/bms/COUSR02.bms` L163 writes `F3=Save&&Exit` for a legend the terminal displays with one
 *       ampersand. That decoder's own contract names this mapset as the only one affected and states
 *       that it is idempotent, which is why it is applied to all five labels uniformly: a reader can
 *       then check every one against the mapset by eye, and no label depends on remembering which of
 *       them needed unescaping. The five decoded labels reconstruct the field's rendered text exactly,
 *       `ENTER=Fetch  F3=Save&Exit  F4=Clear  F5=Save  F12=Cancel` -- 56 characters in a `LENGTH=58`
 *       field, with two spaces between every pair including before `F12`.
 * WHY : Refactoring Rationale: only `F4=Clear` is taken from `UNIFORM_PF_KEY_LABELS`. That module
 *       publishes defaults for exactly the three keys whose wording is byte-identical across all 17
 *       measured legends and deliberately publishes none for ENTER, PF3, PF5 or PF12, citing this very
 *       mapset as the reason. Inheriting a `F3=Exit` default here would mislabel a control that
 *       performs a write, which is the failure that module's omission exists to prevent.
 */
export const USER_UPDATE_KEY_LABELS = {
  ENTER: decodeBmsLegendText('ENTER=Fetch'),
  PFK03: decodeBmsLegendText('F3=Save&&Exit'),
  PFK04: UNIFORM_PF_KEY_LABELS.PFK04,
  PFK05: decodeBmsLegendText('F5=Save'),
  PFK12: decodeBmsLegendText('F12=Cancel'),
} as const;

/**
 * The sentence each control's blank refusal raises, verbatim from the catalog.
 *
 * Assumptions: all five are `SHARED_MESSAGES` entries rather than program-scoped ones because
 * `app/cbl/COUSR01C.cbl` raises the same five literals, and the catalog files a sentence under the
 * group that can cite every site emitting it. The provenance index in
 * `ui/src/messages/messages.ts` records this program's own lines for each: L182 for the identifier
 * (also L148 on the fetch arm), L188, L194, L200 and L206.
 */
const BLANK_FIELD_MESSAGES = {
  userId: SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY,
  firstName: SHARED_MESSAGES.FIRST_NAME_CAN_NOT_BE_EMPTY,
  lastName: SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY,
  password: SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY,
  userType: SHARED_MESSAGES.USER_TYPE_CAN_NOT_BE_EMPTY,
} as const satisfies Readonly<Record<UserUpdateField, string>>;

/*
 * WHY : Assumptions: the order is the reference's own and it is SHORT-CIRCUIT, not a set of
 *       independent rules. `app/cbl/COUSR02C.cbl` L179-L213 is one `EVALUATE TRUE` whose arms test the
 *       identifier (L180), the first name (L186), the last name (L192), the password (L198) and the
 *       user type (L204); the first arm that matches raises its sentence and no later arm is evaluated.
 *       An operator who clears three controls therefore reads exactly one sentence, about the first of
 *       them, which is why this is an ordered list walked to the first hit rather than a validation
 *       pass that accumulates.
 */
const SAVE_VALIDATION_ORDER: readonly UserUpdateField[] = [
  'userId',
  'firstName',
  'lastName',
  'password',
  'userType',
];

/** HTTP status the service answers when no user row carries the identifier. */
const NOT_FOUND_STATUS = 404;

/** Values the five controls hold, with the user type narrowed to the domain its control admits. */
interface UserUpdateValues {
  readonly userId: string;
  readonly firstName: string;
  readonly lastName: string;
  readonly password: string;
  readonly userType: '' | UserType;
}

/** Every control empty, which is the state `INITIALIZE-ALL-FIELDS` leaves at L403-L411. */
const EMPTY_VALUES: UserUpdateValues = {
  userId: '',
  firstName: '',
  lastName: '',
  password: '',
  userType: '',
};

/** One refusal this screen can render, narrowed to a control it actually paints. */
export interface UserUpdateFieldError {
  /** Control the refusal names. */
  readonly field: UserUpdateField;
  /** Which of the baseline's two field conditions applies, which decides the blank marker. */
  readonly state: FieldValidationState;
  /** Verbatim sentence to render beneath the control. */
  readonly message: string;
}

/** What a rejected request puts on the screen: one sentence, any field refusals, and where to focus. */
interface UserUpdateFailureReport {
  /** Verbatim sentence for the message band. */
  readonly message: string;
  /** Refusals to render beneath the controls they name. */
  readonly fieldErrors: readonly UserUpdateFieldError[];
  /** Control to move the cursor to, the analogue of the reference's `MOVE -1 TO <field>L`. */
  readonly focus: UserUpdateField;
}

/** Which half of the two-turn workflow a failure came out of, so its sentence names the right site. */
type UserUpdateStage = 'read' | 'write';

/**
 * Reports whether a control's value counts as empty by the reference's own test.
 *
 * Assumptions: the reference tests `= SPACES OR LOW-VALUES` (for example L180 and L186), and `SPACES`
 * on a fixed-width field is satisfied by a value of nothing but blanks -- so a control holding three
 * spaces is blank to the reference. Trimming before the comparison reproduces that, where a bare
 * emptiness test would admit a value the reference refuses.
 * @param {string} value - Raw value as the control holds it.
 * @returns {boolean} `true` when the reference would treat the value as `SPACES` or `LOW-VALUES`.
 */
export function isBlankFieldValue(value: string): boolean {
  return value.trim() === '';
}

/**
 * Finds the first control the reference's short-circuit cascade would refuse.
 * @param {UserUpdateValues} values - Values the five controls hold.
 * @param {readonly UserUpdateField[]} order - Controls to test, in the order the reference tests them.
 * @returns {UserUpdateField | null} The first blank control, or `null` when every one carries a value.
 */
export function firstBlankField(
  values: UserUpdateValues,
  order: readonly UserUpdateField[],
): UserUpdateField | null {
  for (const field of order) {
    if (isBlankFieldValue(values[field])) {
      return field;
    }
  }

  return null;
}

/**
 * Narrows an arbitrary problem-document field name to a control this screen paints.
 * @param {string} candidate - Field name as the response spelled it.
 * @returns {boolean} `true` when the name is one of this screen's five controls.
 */
function isUserUpdateField(candidate: string): candidate is UserUpdateField {
  return (SAVE_VALIDATION_ORDER as readonly string[]).includes(candidate);
}

/**
 * Maps a problem document's per-field entries onto the controls this screen renders.
 *
 * Assumptions: entries naming something this screen does not paint are DROPPED rather than rendered
 * loose, because there is no control to attach them to. Nothing is lost silently -- the document's own
 * sentence still reaches the message band through {@link describeFailure}.
 *
 * Trade-offs: the array is kept whole even though the reference raises at most one refusal per turn,
 * because the service accumulates its violations in a single pass and can legitimately name several
 * controls. Discarding all but the first would hide refusals an administrator has to fix; the band
 * still carries exactly one sentence, which is the reference's observable behaviour.
 * @param {ApiError} problem - The normalised problem document a rejected request carried.
 * @returns {readonly UserUpdateFieldError[]} One entry per refusal this screen can render, in the
 *   order the service listed them.
 */
export function resolveApiFieldErrors(problem: ApiError): readonly UserUpdateFieldError[] {
  const resolved: UserUpdateFieldError[] = [];

  for (const entry of problem.fieldErrors) {
    if (isUserUpdateField(entry.field)) {
      resolved.push({ field: entry.field, state: entry.state, message: entry.message });
    }
  }

  return resolved;
}

/**
 * Reduces a rejected request to the one sentence, the field marks and the cursor move it produces.
 *
 * Assumptions: the sentence for each outcome is the reference's own, chosen by the stage the failure
 * came out of, because the reference words the same condition differently on its two file operations.
 * A missing row is `User ID NOT found...` from both `DFHRESP(NOTFND)` arms -- L342 on the read and
 * L379 on the write -- and the cursor goes to the identifier on both (L344, L381). Anything else is
 * `Unable to lookup User...` from the read's `WHEN OTHER` arm (L349) or `Unable to Update User...`
 * from the write's (L386), and both move the cursor to the first name (L351, L388).
 *
 * Assumptions: a refusal naming a control takes precedence over the stage sentence, and this is the
 * one outcome with no reference site because the reference has no 400 path -- it validates every field
 * itself before touching the file. The service validates independently and can refuse a value this
 * screen let through, so its sentence is shown for the control it names, which is the same shape the
 * reference produces for its own refusals: one sentence, about the first offending field, with the
 * cursor on it.
 * @param {unknown} failure - Whatever the request rejected with, normally the normalised
 *   `ApiRequestError` that `ui/src/api/client.ts` mints.
 * @param {UserUpdateStage} stage - Which file operation the failure came out of, selecting the
 *   reference sentence for an outcome that names no field.
 * @returns {UserUpdateFailureReport} The sentence to band, the refusals to mark, and the control to
 *   focus.
 */
export function describeFailure(failure: unknown, stage: UserUpdateStage): UserUpdateFailureReport {
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
    return { message: stageMessage, fieldErrors: [], focus: 'firstName' };
  }

  const fieldErrors = resolveApiFieldErrors(failure.problem);

  if (failure.status === NOT_FOUND_STATUS) {
    return { message: SHARED_MESSAGES.USER_ID_NOT_FOUND, fieldErrors, focus: 'userId' };
  }

  const firstRefusal = fieldErrors[0];

  if (firstRefusal !== undefined) {
    return { message: firstRefusal.message, fieldErrors, focus: firstRefusal.field };
  }

  return { message: stageMessage, fieldErrors, focus: 'firstName' };
}

/*
 * WHY : Assumptions: the comparison covers the first name, the last name and the user type, and the
 *       reference's fourth comparison has NO target analogue. `app/cbl/COUSR02C.cbl` compares the first
 *       name at L219, the last name at L223, the PASSWORD at L227-L230 and the user type at L231, and
 *       sets `USR-MODIFIED-YES` when any differs. The password arm cannot be reproduced because no
 *       response type carries a password to compare against: `UserResponse` declares five members and
 *       none is a credential, and the update contract declares three. Excluding it is what keeps the
 *       `Please modify to update ...` outcome (L239) observable for the case that actually reaches an
 *       operator -- pressing save having changed nothing -- rather than making it unreachable because a
 *       freshly-typed password always differs from a value that does not exist.
 * WHY : Assumptions: both sides are trimmed before comparison, because the reference compares two
 *       equally space-padded fixed-width fields. `FNAMEI` is `PIC X(20)` and so is `SEC-USR-FNAME`, so
 *       trailing blanks are equal on both sides of L219 and the comparison is insensitive to them. The
 *       browser holds unpadded strings, so trimming reproduces that insensitivity; comparing untrimmed
 *       values would report a change for a trailing space the reference ignores.
 */

/**
 * Reports whether the typed values differ from the row as the service last returned it.
 * @param {UserUpdateValues} values - Values the five controls hold.
 * @param {UserResponse} stored - The row as the service returned it on the read this save performed.
 * @returns {boolean} `true` when at least one comparable value differs, which is the reference's
 *   `USR-MODIFIED-YES` condition.
 */
export function hasComparableChanges(values: UserUpdateValues, stored: UserResponse): boolean {
  return (
    values.firstName.trim() !== stored.firstName.trim() ||
    values.lastName.trim() !== stored.lastName.trim() ||
    values.userType !== stored.userType
  );
}

/*
 * WHY : Assumptions: the control admits only the two domain characters, and this is NOT an invented
 *       validation. `app/cbl/COUSR02C.cbl` has no domain arm for the user type at all -- L204 tests it
 *       for blank and the program then writes whatever single character it holds -- but the migrated
 *       schema declares `CHECK (user_type IN ('A','U'))` and `UpdateUserRequest.userType` is the
 *       `'A' | 'U'` union, so the target cannot carry any other value end to end. Refusing the
 *       keystroke is the same affordance `maxLength` already provides on this control: a 3270 refused a
 *       character past the end of a field with no message, and this refuses a character outside the
 *       domain the label beside it advertises, also with no message.
 *       Alternatives Considered: a `Select` over the union, which the design-system mapping permits.
 *       Rejected because a select has no character width, and the mapset's `LENGTH=1` with
 *       `PIC X(01)` is a field-width contract this tree preserves on every control.
 *       Alternatives Considered: submitting an out-of-domain character so the service refuses it and
 *       supplies the sentence. Rejected twice: it would need an unsafe cast past the union, and the
 *       service's wording is not in `ui/src/messages/messages.ts`, which owns every sentence this
 *       screen may paint -- so the screen would render text no catalog entry accounts for.
 */

/**
 * Normalises a typed user-type character to the domain the contract admits.
 * @param {string} typed - Raw value the control produced, of at most one character.
 * @returns {'' | UserType} The upper-cased character when it is in the domain, otherwise the empty
 *   string, so a refused keystroke leaves the control as it was.
 */
export function normaliseUserType(typed: string): '' | UserType {
  const upper = typed.toUpperCase();

  return upper === 'A' || upper === 'U' ? upper : '';
}

/**
 * Renders the administered-user update form, whose route carries the identifier to load.
 *
 * Assumptions: the route parameter is read as `id`, which is the name `ui/src/router.tsx` declares for
 * this route -- its sibling routes spell theirs `num`, `key` and `cd`, so the name is per-route and is
 * not interchangeable. It is optional in this component's own terms: an operator may reach the screen
 * with no identifier and type one, which is exactly what the reference permits when
 * `CDEMO-CU02-USR-SELECTED` arrives as `SPACES` (L99-L104).
 * @returns {ReactElement} The header band, caption, message band, the five controls and the key legend.
 */
export function UserUpdateScreen(): ReactElement {
  const navigate = useNavigate();
  /*
   * WHY : Assumptions: the instant is server-derived rather than read from the browser clock, because
   *       the reference reads ONE region clock for every terminal -- `POPULATE-HEADER-INFO` runs
   *       `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA` at L298 on each `SEND MAP`. Omitting the prop
   *       falls back to the browser's clock and its zone, under which two administrators looking at one
   *       row can read two different dates across midnight. Read during render so the value is a
   *       PAINT-time instant, matching a clock re-read per send rather than one advanced by a timer.
   */
  const paintedAt = useServerInstant();
  const { id: routeUserId } = useParams<{ id: string }>();
  /*
   * WHY : Assumptions: `cssVar` rather than `token` from the same hook. `token` returns RESOLVED values
   *       -- a hex string, a number -- so writing one into a `style` attribute bakes today's palette
   *       into the element and silently opts it out of antd 6's CSS-variable theming, leaving this one
   *       screen behind when a token changes. `cssVar` returns the `var(--...)` reference form of the
   *       same names, so every design value below still resolves through the single `ConfigProvider` in
   *       `ui/src/App.tsx`.
   */
  const { cssVar } = theme.useToken();
  /*
   * WHY : Assumptions: control identifiers are derived per instance rather than written as constants,
   *       because their only job is to bind each label to its control; a constant would collide if the
   *       screen were ever rendered twice in one document, leaving a label pointing at whichever
   *       control appeared first.
   */
  const idPrefix = useId();
  const controls = useRef<Partial<Record<UserUpdateField, InputRef | null>>>({});

  const [values, setValues] = useState<UserUpdateValues>(EMPTY_VALUES);
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');
  const [fieldErrors, setFieldErrors] = useState<readonly UserUpdateFieldError[]>([]);
  const [busy, setBusy] = useState(false);
  /*
   * WHY : Refactoring Rationale: the cursor destination is held as STATE and applied by the effect
   *       below rather than by calling `.focus()` where the refusal is decided. An inline call is the
   *       obvious shape and loses the cursor twice over, both times because it would run before React
   *       commits the render the refusal causes. First, the blank marker is an antd `Input` `suffix`,
   *       and adding one rewraps the control so a NEW `input` element is mounted -- focus applied to the
   *       old node is discarded and the cursor falls back to the document body. Second, every control is
   *       `disabled` while a turn is in flight, and a browser refuses focus on a disabled element
   *       silently, with no error to notice. This is the mechanism that makes the reference's six
   *       `MOVE -1 TO <field>L` sites actually observable in a browser.
   *       Trade-offs: one extra render per cursor move, which is the price of the cursor landing where
   *       the reference puts it. Alternatives Considered: a timer or an animation frame, both of which
   *       also outlive the commit but leave the cursor depending on elapsed time rather than on the
   *       render that caused it, so a slow commit would reintroduce the fault non-deterministically.
   */
  const [pendingFocus, setPendingFocus] = useState<UserUpdateField | null>(null);

  useEffect(
    /**
     * Applies a recorded cursor move once the render that caused it has been committed.
     * @returns {void} Completion is the focused control; a control that is not mounted is a no-op, and
     *   the request is cleared either way so it can never be replayed on a later render.
     */
    function applyPendingFocus(): void {
      if (pendingFocus === null) {
        return;
      }

      controls.current[pendingFocus]?.focus();
      setPendingFocus(null);
    },
    [pendingFocus],
  );

  /*
   * WHY : Assumptions: this is declared before {@link load} and is one of its dependencies, rather than
   *       being a plain function declaration hoisted into it. `load` is memoised on an empty dependency
   *       list so the mount effect below does not re-run on every render, and a memoised callback closes
   *       over the render in which it was created -- so a plain declaration would leave `load` calling
   *       the FIRST render's copy for the life of the screen. Memoising this one too makes that closure
   *       correct by construction instead of correct only because its body happens to touch nothing but
   *       state setters, which React guarantees to be stable.
   */

  const applyFailureReport = useCallback(
    /**
     * Publishes a reduced failure as the reference does: one sentence, field marks and one cursor move.
     * @param {UserUpdateFailureReport} report - The reduced failure, from {@link describeFailure}.
     * @returns {void} Completion is represented by the screen's own state.
     */
    (report: UserUpdateFailureReport): void => {
      setBusy(false);
      setMessage(report.message);
      setSeverity('error');
      setFieldErrors(report.fieldErrors);
      setPendingFocus(report.focus);
    },
    [],
  );

  /**
   * Reads one user and seeds the editable controls from the row, which is the reference's Enter arm.
   *
   * Assumptions: the four editable controls are blanked BEFORE the read is issued, because
   * `app/cbl/COUSR02C.cbl` L158-L161 moves `SPACES` into the first name, last name, password and user
   * type and only then performs `READ-USER-SEC-FILE` at L163. An operator therefore never sees one
   * row's values beside another row's identifier, not even briefly.
   *
   * Assumptions: the password control is left EMPTY on success. The reference fills it from the stored
   * plaintext credential at L169; the target holds no credential to fill it with, as the module header
   * records, so that single `MOVE` is the one line of this paragraph deliberately not carried across.
   */
  const load = useCallback(
    /**
     * Reads one user by identifier and seeds the editable controls from the row.
     * @param {string} userId - Identifier to read, as the control or the route supplied it.
     * @returns {void} Completion is represented by the screen's own state; every failure of the request
     *   is reduced to a band sentence and field marks rather than propagated.
     */
    (userId: string): void => {
      if (isBlankFieldValue(userId)) {
        /*
         * WHY : Assumptions: the blank identifier is refused WITHOUT issuing a request, and the refusal
         *       carries the `BLANK` state so the marker below is rendered. `app/cbl/COUSR02C.cbl`
         *       L146-L151 raises the sentence, places the cursor and never reaches the read.
         */
        setValues(
          /**
           * Keeps the identifier the operator typed so the refusal names a value still on the screen.
           * @param {UserUpdateValues} previous - Values as they stand.
           * @returns {UserUpdateValues} The same values carrying the identifier.
           */
          (previous: UserUpdateValues): UserUpdateValues => ({ ...previous, userId }),
        );
        setFieldErrors([{ field: 'userId', state: 'BLANK', message: BLANK_FIELD_MESSAGES.userId }]);
        setMessage(BLANK_FIELD_MESSAGES.userId);
        setSeverity('error');
        setPendingFocus('userId');
        return;
      }

      setValues(
        /**
         * Records the identifier and blanks the four editable controls, as L158-L161 does before reading.
         * @param {UserUpdateValues} previous - Values as they stand.
         * @returns {UserUpdateValues} The identifier alone, with every editable control emptied.
         */
        (previous: UserUpdateValues): UserUpdateValues => ({
          ...previous,
          userId,
          firstName: '',
          lastName: '',
          password: '',
          userType: '',
        }),
      );
      setFieldErrors([]);
      setMessage(null);
      setSeverity('error');
      setBusy(true);

      getUser(userId.trim()).then(
        /**
         * Seeds the three readable values and states that the row is now awaiting a save.
         * @param {UserResponse} found - The row as the service returned it.
         * @returns {void} Completion is represented by the screen's own state.
         */
        (found: UserResponse): void => {
          setValues(
            /**
             * Seeds the three readable values from the row, leaving the credential control empty.
             * @param {UserUpdateValues} previous - Values as they stand.
             * @returns {UserUpdateValues} The same values carrying the row.
             */
            (previous: UserUpdateValues): UserUpdateValues => ({
              ...previous,
              firstName: found.firstName,
              lastName: found.lastName,
              userType: found.userType,
            }),
          );
          setBusy(false);
          /*
           * WHY : Assumptions: this sentence is INFORMATIONAL, not a refusal, and the distinction is the
           *       reference's own: L336 sets it and L338 moves `DFHNEUTR` -- the neutral attribute -- into
           *       the message field's colour, where every refusal on this screen moves `DFHRED`. Rendering
           *       it as an error would invert what an operator is told about a successful read.
           */
          setMessage(UPDATE_MESSAGES.PRESS_PF5_KEY_TO_SAVE_YOUR_UPDATES);
          setSeverity('info');
          /*
           * WHY : Assumptions: the cursor returns to the identifier after a successful read, because
           *       L153 sets `MOVE -1 TO USRIDINL` on the arm that proceeds to read and the map is then
           *       sent `CURSOR` (L272-L278). It is requested here rather than before the request because
           *       the controls are disabled while the read is in flight and a disabled control cannot take
           *       focus -- which also matches the reference's timing, where the send follows the read.
           */
          setPendingFocus('userId');
        },
        /**
         * Reduces a refused read to the reference's own lookup sentences.
         * @param {unknown} failure - Whatever the read rejected with.
         * @returns {void} Completion is represented by the screen's own state.
         */
        (failure: unknown): void => {
          applyFailureReport(describeFailure(failure, 'read'));
        },
      );
    },
    [applyFailureReport],
  );

  useEffect(
    /**
     * Reads the row named by the route once on mount, and again if the route names a different one.
     *
     * Assumptions: this is the analogue of `app/cbl/COUSR02C.cbl` L99-L104, where a first entry
     * carrying a selected user in `CDEMO-CU02-USR-SELECTED` moves it into the identifier control and
     * performs `PROCESS-ENTER-KEY` immediately, so the row is already on the glass before the operator
     * presses anything. AAP section 0.7.1 relocates that selection carrier to the request path, so the
     * route parameter is what stands in for it.
     *
     * Assumptions: the effect is skipped entirely when the route carries no identifier, which the
     * reference's own guard does -- it tests the carrier against `SPACES AND LOW-VALUES` before using
     * it -- and leaves the screen waiting for a typed identifier rather than refusing a blank one the
     * operator never entered.
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
   * Refuses one control the way the reference does: its sentence, its marker and the cursor on it.
   * @param {UserUpdateField} field - Control the cascade refused.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function refuseBlankField(field: UserUpdateField): void {
    /*
     * WHY : Assumptions: the refusal is recorded with the `BLANK` state, which is what makes the marker
     *       below appear. `app/cpy/CSSETATY.cpy` moves the error colour into a field when its flag is
     *       not-OK OR blank (L18-L19) but writes the literal asterisk into the field's output subfield
     *       ONLY in the blank case (L23-L26) -- so the two conditions are not interchangeable, and every
     *       refusal this cascade raises is by definition the blank one.
     */
    setFieldErrors([{ field, state: 'BLANK', message: BLANK_FIELD_MESSAGES[field] }]);
    setMessage(BLANK_FIELD_MESSAGES[field]);
    setSeverity('error');
    setPendingFocus(field);
  }

  /**
   * Validates, compares and writes the change, which is the reference's `UPDATE-USER-INFO` paragraph.
   *
   * Assumptions: the row is RE-READ before the comparison, on every save, because
   * `app/cbl/COUSR02C.cbl` L215-L217 performs `READ-USER-SEC-FILE` again inside this paragraph even
   * when the Enter arm has just read the same row. Reading again is what makes the comparison a
   * comparison against the row as it stands now, so a change another administrator committed in the
   * meantime is written over deliberately rather than reported as "no change".
   * Trade-offs: one extra request per save. Comparing against a snapshot the fetch arm had left behind
   * would avoid it and would report `Please modify to update ...` for a row that has since moved -- a
   * divergence in the one direction that matters, because it would refuse a write the reference
   * performs. Re-reading also means this screen holds NO snapshot at all, so there is no second copy of
   * the row for the controls to drift away from.
   *
   * Assumptions: a read that fails here yields the READ sentences and not the write ones, which is the
   * reference's behaviour for the same reason -- the failing operation is `READ-USER-SEC-FILE`, whose
   * arms are L342 and L349.
   * @returns {Promise<void>} A promise that settles when the whole attempt has finished, whatever its
   *   outcome. It is never rejected for a refused request: each failure is reduced to a band sentence
   *   and field marks, so a caller may sequence on it without a rejection handler of its own.
   */
  function attemptSave(): Promise<void> {
    /*
     * WHY : Assumptions: a save arriving while a turn is in flight is ignored, and the guard lives here
     *       rather than being expressed as a disabled key binding. A disabled binding reports through
     *       `usePfKeys`' invalid-key channel, which would put `Invalid key pressed...` on the band for a
     *       key the screen does offer. The reference needs no such guard at all, because a terminal turn
     *       is serialised and a second key could not arrive while the first was being processed.
     */
    if (busy) {
      return Promise.resolve();
    }

    const blankField = firstBlankField(values, SAVE_VALIDATION_ORDER);
    const { userType } = values;

    /*
     * WHY : Assumptions: the second disjunct is the SAME condition the cascade's last arm already tests
     *       (L204), restated so the compiler can narrow the user type from `'' | UserType` to the union
     *       the request requires. The `??` fallback is therefore unreachable at run time -- a blank user
     *       type always makes `firstBlankField` return `'userType'` -- and exists only so the refusal is
     *       typed. Writing an unchecked assertion instead would type-check while silently admitting an
     *       empty user type into the request body.
     */
    if (blankField !== null || userType === '') {
      refuseBlankField(blankField ?? 'userType');
      return Promise.resolve();
    }

    const userId = values.userId.trim();

    setBusy(true);
    setFieldErrors([]);
    setMessage(null);
    setSeverity('error');

    return getUser(userId).then(
      /**
       * Compares the typed values with the row just read and writes only when one of them differs.
       * @param {UserResponse} current - The row as the re-read returned it.
       * @returns {Promise<void>} A promise that settles when the write has finished, or immediately
       *   when nothing differed and no write was issued.
       */
      (current: UserResponse): Promise<void> => {
        if (!hasComparableChanges(values, current)) {
          setBusy(false);
          /*
           * WHY : Assumptions: this outcome is a REFUSAL and is rendered as one, because L241 moves
           *       `DFHRED` into the message field's colour where the read's own sentence moved
           *       `DFHNEUTR`. It is also a distinct state from that sentence and must stay separately
           *       observable: `Press PF5 key to save your updates ...` (L336) says a row was read and
           *       may now be saved, while this one (L239) says a save was attempted and changed nothing.
           *       Collapsing them would lose one of the two outcomes an operator can reach.
           */
          setMessage(UPDATE_MESSAGES.PLEASE_MODIFY_TO_UPDATE);
          setSeverity('error');
          return Promise.resolve();
        }

        /*
         * WHY : Assumptions: the request carries THREE members and no credential, because
         *       `UpdateUserRequest` declares exactly `firstName`, `lastName` and `userType` and its
         *       contract seals itself with `additionalProperties: false`. That contract states outright
         *       that the baseline's `Password can NOT be empty...` branch "has no counterpart anywhere in
         *       this contract, since the service neither accepts nor stores a password on create or
         *       update". So the password this screen validated is deliberately NOT transmitted: the
         *       reference's own comparison of it (L227-L230) and its write of it have no target
         *       analogue, while its blank refusal (L198-L203) is kept because it is a user-visible
         *       behaviour of the screen rather than a property of the request.
         *       Trade-offs: an administrator must therefore type something into a control whose value is
         *       discarded. That is the reference's own requirement -- it refuses a blank password before
         *       writing -- and the alternative was to drop the control and its refusal altogether, which
         *       would silently delete a field the mapset paints and a sentence the program emits.
         */
        const request: UpdateUserRequest = {
          firstName: values.firstName.trim(),
          lastName: values.lastName.trim(),
          userType,
        };

        return updateUser(userId, request).then(
          /**
           * Acknowledges the committed change with the reference's composed sentence.
           * @param {UserResponse} saved - The row as the service reports it after the write.
           * @returns {void} Completion is represented by the screen's own state.
           */
          (saved: UserResponse): void => {
            /*
             * WHY : Assumptions: the three comparable controls are reseeded from the RESPONSE while the
             *       password control is left exactly as typed. The reference re-sends the same map after
             *       the write, so the values on the glass are the ones written -- which the response
             *       restates authoritatively -- and its password control keeps whatever was typed
             *       because nothing in `UPDATE-USER-SEC-FILE` (L358-L390) clears it. Only the fetch arm
             *       (L160) and `INITIALIZE-ALL-FIELDS` (L409) blank it.
             */
            setValues(
              /**
               * Restates the three written values from the response, leaving the credential as typed.
               * @param {UserUpdateValues} previous - Values as they stand.
               * @returns {UserUpdateValues} The same values carrying the stored row.
               */
              (previous: UserUpdateValues): UserUpdateValues => ({
                ...previous,
                firstName: saved.firstName,
                lastName: saved.lastName,
                userType: saved.userType,
              }),
            );
            setBusy(false);
            /*
             * WHY : Assumptions: the sentence is COMPOSED from the catalog template rather than written
             *       out, because the reference composes it: the `STRING` at L372-L375 concatenates the
             *       literal `'User '`, the key `DELIMITED BY SPACE` -- which truncates the eight-character
             *       field at its first blank -- and the literal `' has been updated ...'`. The template
             *       carries all three parts and `formatMessageTemplate` applies the same delimiter rule,
             *       so the leading literal and the single space before the ellipsis cannot be lost to a
             *       retyped string. It is a SUCCESS, from `DFHGREEN` at L371.
             */
            setMessage(
              formatMessageTemplate(MESSAGE_TEMPLATES.USER_HAS_BEEN_UPDATED, {
                'SEC-USR-ID': saved.userId,
              }),
            );
            setSeverity('success');
          },
          /**
           * Reduces a refused write to the reference's own update sentences.
           * @param {unknown} failure - Whatever the write rejected with.
           * @returns {void} Completion is represented by the screen's own state.
           */
          (failure: unknown): void => {
            applyFailureReport(describeFailure(failure, 'write'));
          },
        );
      },
      /**
       * Reduces a refused re-read to the reference's own lookup sentences.
       * @param {unknown} failure - Whatever the re-read rejected with.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (failure: unknown): void => {
        applyFailureReport(describeFailure(failure, 'read'));
      },
    );
  }

  /**
   * Leaves the screen for the administrative menu, which is where both exit arms transfer to.
   *
   * Assumptions: the destination is the administrative menu unconditionally. `app/cbl/COUSR02C.cbl`
   * L113-L118 prefers `CDEMO-FROM-PROGRAM` and falls back to `'COADM01C'`, and L125 uses that same
   * fallback for PF12 with no preference at all. The preferred arm reads a value a CALLING program
   * deliberately placed in the shared structure, and the router publishes no equivalent named caller --
   * a history entry is not a named program and need not even be inside this application -- so the
   * fallback is the only arm with a target analogue, and it is the reference's own default. The sibling
   * screens resolve the identical construct the same way.
   * @returns {void} Completion is the requested route transition.
   */
  function exitToAdminMenu(): void {
    navigateSafely(navigate, ADMIN_MENU_ROUTE);
  }

  /**
   * Reads the row named by the identifier control, which is the reference's Enter arm.
   * @returns {void} Completion is represented by the screen's own state; {@link load} reduces every
   *   failure of the request it issues to a band sentence and field marks, so no rejection escapes here.
   */
  function handleFetch(): void {
    /*
     * WHY : Assumptions: a fetch arriving while a turn is already in flight is ignored, and the guard is
     *       written here rather than as a disabled key binding for the same reason it is written inside
     *       {@link attemptSave} -- a disabled binding reports through `usePfKeys`' invalid-key channel,
     *       which would paint `Invalid key pressed...` for a key this screen does offer. The reference
     *       needs no guard at all: `app/cbl/COUSR02C.cbl` processes one terminal turn at a time, so a
     *       second Enter could not arrive while the first was still running its Enter arm at L143-L172.
     */
    if (busy) {
      return;
    }

    load(values.userId);
  }

  /**
   * Writes the change and stays on the screen, which is the reference's PF5 arm (L122-L123).
   * @returns {void} Completion is represented by the screen's own state; {@link attemptSave} reduces
   *   every failure of the requests it issues, and the handler below reduces any fault it could not.
   */
  function handleSave(): void {
    attemptSave().catch(
      /*
       * WHY : Assumptions: a rejection handler is attached rather than the promise being discarded.
       *       {@link attemptSave} reduces every failure of the requests it issues, so this arm is
       *       reachable only if a state update itself throws -- but the lint gate forbids a floating
       *       promise outright, and reducing an unexpected fault through the same mapper leaves the
       *       screen carrying a usable sentence instead of surfacing a raw rejection to the error
       *       boundary and replacing the screen with a blank one.
       */
      /**
       * Reports a fault the save could not reduce itself.
       * @param {unknown} failure - Whatever the save rejected with.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (failure: unknown): void => {
        applyFailureReport(describeFailure(failure, 'write'));
      },
    );
  }

  /**
   * Writes the change and then leaves, which is the reference's PF3 arm.
   *
   * Assumptions: PF3 SAVES here, and this screen is the only one in the application where it does.
   * Its legend field paints `F3=Save&&Exit` at `app/bms/COUSR02.bms` L163 -- BMS source doubles a
   * literal ampersand, so the terminal shows one -- and `app/cbl/COUSR02C.cbl` L111-L119 performs
   * `UPDATE-USER-INFO` and only then `RETURN-TO-PREV-SCREEN`. The shared PF3-means-back reading is
   * wrong on exactly this screen, which is why `ui/src/layout/PfKeyBar.tsx` publishes no default label
   * for the key and why this binding is stated explicitly rather than inherited.
   *
   * Assumptions: the transition is UNCONDITIONAL. No `ERR-FLG` test sits between the two `PERFORM`
   * statements at L112 and L119, so the reference navigates whether the save validated, wrote, or was
   * refused -- the error screen it has just sent is replaced by the transfer and is never read. That
   * sequencing is preserved rather than corrected, because AAP Rule T9 admits no silent behavioural
   * change and section 0.9.1 makes functional parity non-negotiable.
   * @returns {void} Completion is the save attempt followed by the requested route transition.
   */
  function handleSaveAndExit(): void {
    /*
     * WHY : Assumptions: the transition WAITS for the attempt to settle, on both outcomes, rather than
     *       being issued alongside it. The reference's `EXEC CICS REWRITE` is synchronous, so the write
     *       has completed -- or failed -- before `EXEC CICS XCTL` runs at L258-L261; sequencing on the
     *       promise reproduces that ordering. Alternatives Considered: navigating immediately and letting
     *       the request continue in the background. Rejected because it races the request against an
     *       unmount, which can leave the write unsent, and because it would make the write's completion
     *       unordered with respect to the destination screen's own reads.
     */
    attemptSave().then(exitToAdminMenu, exitToAdminMenu);
  }

  /**
   * Blanks every control and the message, which is the reference's PF4 arm.
   *
   * Assumptions: all FIVE controls are blanked including the identifier, and the message with them.
   * `INITIALIZE-ALL-FIELDS` at L403-L411 moves `SPACES` into the identifier, the first name, the last
   * name, the password, the user type and `WS-MESSAGE` in one statement, and L405 places the cursor back
   * on the identifier. The field marks go with them, because a mark left beneath a control the operator
   * has just emptied would name a refusal of a value that is no longer there.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function clearScreen(): void {
    setValues(EMPTY_VALUES);
    setFieldErrors([]);
    setMessage(null);
    setSeverity('error');
    setPendingFocus('userId');
  }

  /**
   * Records one control's value, normalising the user type to the domain its contract admits.
   *
   * Assumptions: the field marks and the band sentence are deliberately NOT cleared by an edit. AAP
   * section 0.7.1 removes the re-entry discriminator `CDEMO-PGM-REENTER` that
   * `app/cpy/CSSETATY.cpy` L20 gates its highlighting on, and the replacement is that the error state is
   * driven purely by what the last turn produced -- so a mark changes when a turn produces a new one,
   * exactly as row 23 and the field attributes only changed on a `SEND MAP`.
   * @param {UserUpdateField} field - Control the operator is editing.
   * @returns {(event: ChangeEvent<HTMLInputElement>) => void} Change handler for that control.
   */
  function changeHandler(field: UserUpdateField): (event: ChangeEvent<HTMLInputElement>) => void {
    /**
     * Applies one edit to the screen's values.
     * @param {ChangeEvent<HTMLInputElement>} event - Change event carrying the control's new value.
     * @returns {void} Completion is represented by the screen's own state.
     */
    return (event: ChangeEvent<HTMLInputElement>): void => {
      const edited = event.target.value;

      setValues(
        /**
         * Replaces one control value, leaving the rest as they stand.
         * @param {UserUpdateValues} previous - Values as they stand.
         * @returns {UserUpdateValues} The same values carrying the edit.
         */
        (previous: UserUpdateValues): UserUpdateValues => ({
          ...previous,
          [field]: field === 'userType' ? normaliseUserType(edited) : edited,
        }),
      );
    };
  }

  /**
   * Registers one control so a refusal can move the cursor to it.
   * @param {UserUpdateField} field - Control the element renders.
   * @returns {(control: InputRef | null) => void} Callback ref that records the control.
   */
  function registerControl(field: UserUpdateField): (control: InputRef | null) => void {
    /**
     * Records the mounted control, or forgets it on unmount.
     * @param {InputRef | null} control - The control, or `null` while it is being detached.
     * @returns {void} Completion is the updated lookup.
     */
    return (control: InputRef | null): void => {
      controls.current[field] = control;
    };
  }

  /*
   * WHY : Assumptions: all five keys are registered unconditionally, because the reference admits all
   *       five on every turn -- `app/cbl/COUSR02C.cbl` L108-L131 is a flat `EVALUATE EIBAID` with no
   *       state guarding any arm. A key withheld here would be reported through the invalid-key channel
   *       as though the screen did not offer it, which is what the reference does only for keys outside
   *       that list (L127-L130).
   * WHY : Assumptions: PF3 carries an explicit `action: 'save'`, overriding the shared
   *       `DEFAULT_PF_KEY_ACTIONS` entry, which maps it to `back`. The semantic matters beyond
   *       presentation because it is what the bar and any later consumer read to describe the control,
   *       and describing a control that writes as "back" would misreport the one key on this screen that
   *       does not mean what it means everywhere else. Its button emphasis is unaffected: emphasis is
   *       keyed by AID through `PRIMARY_ACTION_AIDS`, which lists ENTER and PF5, so PF3 stays a default
   *       button exactly as the design-system mapping requires.
   */
  const keyHandlers: PfKeyHandlerMap = {
    ENTER: { onInvoke: handleFetch, label: USER_UPDATE_KEY_LABELS.ENTER },
    PFK03: { onInvoke: handleSaveAndExit, label: USER_UPDATE_KEY_LABELS.PFK03, action: 'save' },
    PFK04: { onInvoke: clearScreen, label: USER_UPDATE_KEY_LABELS.PFK04 },
    PFK05: { onInvoke: handleSave, label: USER_UPDATE_KEY_LABELS.PFK05 },
    PFK12: { onInvoke: exitToAdminMenu, label: USER_UPDATE_KEY_LABELS.PFK12 },
  };

  const { bindings, invoke } = usePfKeys(keyHandlers, {
    /*
     * WHY : Assumptions: the invalid-key arm states the sentence and does NOT move the cursor, because
     *       the reference's `WHEN OTHER` arm at L127-L130 sets the error flag and the message and sends
     *       the map with nothing else -- unlike its six other arms, it moves no `-1` into a length
     *       subfield. `usePfKeys` offers a `restoreFocusRef` option for screens whose invalid-key arm
     *       does move the cursor, and it is deliberately not supplied here.
     * WHY : Assumptions: the sentence is the shared `INVALID_KEY_PRESSED` constant, which is the same
     *       `CCDA-MSG-INVALID-KEY` value the reference moves at L129 -- declared once at
     *       `app/cpy/CSMSG01Y.cpy` L20-L21 and already published by the hook on its rejection payload.
     *       It is read from the catalog rather than from that payload so the screen has one source for
     *       every sentence it paints.
     */
    /**
     * Reports a key the screen does not bind, using the reference's own invalid-key sentence.
     * @returns {void} Completion is represented by the screen's own state.
     */
    onInvalidKey: (): void => {
      setMessage(INVALID_KEY_PRESSED);
      setSeverity('error');
    },
  });

  /*
   * WHY : Assumptions: every design value below is a TOKEN NAME resolved through `cssVar`, so this
   *       screen carries no colour, weight, spacing or font literal. The colour roles are the mapset's
   *       own measured attributes, mapped by `BMS_COLOR_TOKENS`: `COLOR=NEUTRAL` on the caption
   *       (`app/bms/COUSR02.bms` L76) resolves to `colorTextSecondary`, `COLOR=GREEN` on the identifier
   *       label (L81) to `colorSuccess`, `COLOR=TURQUOISE` on the four remaining labels (L99, L112,
   *       L126, L141) to `colorInfo`, `COLOR=BLUE` on the two hints (L136, L151) to `colorPrimary`, and
   *       `COLOR=YELLOW` on the row-8 rule (L93) to `colorWarning`. `ATTRB=BRT` on the caption (L75) is
   *       carried by `fontWeightStrong` rather than by a colour, which is what keeps brightness and
   *       colour independent in the target the way the mapset has them independent.
   */
  const captionStyle: CSSProperties = {
    color: cssVar[BMS_COLOR_TOKENS.NEUTRAL],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };
  const hintStyle: CSSProperties = { color: cssVar[BMS_COLOR_TOKENS.BLUE] };
  const blankMarkerStyle: CSSProperties = { color: cssVar[FIELD_ERROR_TOKENS.errorColor] };
  /*
   * WHY : Assumptions: the rule's colour is set through a LOGICAL property, `border-block-start-color`,
   *       rather than `border-top-color`. The design-system invariants require logical properties
   *       throughout, and antd draws a horizontal `Divider` with `border-block-start`, so naming the
   *       logical edge overrides the colour the component already sets instead of adding a second,
   *       physical declaration that would only agree with it in a left-to-right document.
   */
  const ruleStyle: CSSProperties = { borderBlockStartColor: cssVar[BMS_COLOR_TOKENS.YELLOW] };
  /*
   * WHY : Assumptions: the identifier is rendered in the fixed-pitch face because the token bridge maps
   *       identifier columns to `fontFamilyCode`. `SEC-USR-ID` is a fixed eight-character key
   *       (`app/cpy/CSUSR01Y.cpy` L18) that a terminal displayed in a monospaced cell grid, so a
   *       proportional face would make two identifiers of equal length render at different widths -- the
   *       one property of the 3270 presentation that a browser can still keep. The three name and
   *       credential controls are free text and are deliberately left in the body face.
   */
  const fixedPitchStyle: CSSProperties = { fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData] };

  /**
   * Renders one labelled control with the mapset's colour, width and hint, plus any refusal it carries.
   *
   * Assumptions: the label is bound to its control by `htmlFor` and a generated identifier rather than
   * by nesting, so assistive technology announces the mapset's own label text when the control takes
   * focus. The 3270 original was keyboard-only, so this is fidelity rather than an addition: a label
   * that was merely adjacent on a character grid has to be associated explicitly in a browser.
   * @param {UserUpdateField} field - Control to render.
   * @returns {ReactElement} The form item, its control, and the marker and help text a refusal adds.
   */
  function renderField(field: UserUpdateField): ReactElement {
    const presentation = FIELD_PRESENTATION[field];
    const refusal = fieldErrors.find(
      /**
       * Selects the refusal naming this control, if the last turn produced one.
       * @param {UserUpdateFieldError} entry - One refusal from the last turn.
       * @returns {boolean} `true` when the refusal names this control.
       */
      (entry: UserUpdateFieldError): boolean => entry.field === field,
    );
    const controlId = `${idPrefix}${field}`;
    /*
     * WHY : Assumptions: the marker is hidden from assistive technology, because it duplicates
     *       information the help text already carries in words. `app/cpy/CSSETATY.cpy` L23-L26 writes the
     *       literal asterisk into a blank field as the terminal's only way to point at one, and the
     *       browser points at it with the form item's error state and its sentence; announcing a bare
     *       asterisk after that sentence would add noise, not information. It is rendered ONLY for the
     *       blank condition and never for a non-blank refusal, which is the distinction that copybook
     *       draws between its two arms.
     */
    const controlProps = {
      id: controlId,
      ref: registerControl(field),
      value: values[field],
      maxLength: USER_UPDATE_FIELD_WIDTHS[field],
      onChange: changeHandler(field),
      disabled: busy,
      autoFocus: presentation.initialCursor === true,
      ...(presentation.fixedPitch === true ? { style: fixedPitchStyle } : {}),
      ...(refusal?.state === 'BLANK'
        ? {
            suffix: (
              <Typography.Text aria-hidden="true" style={blankMarkerStyle}>
                {FIELD_ERROR_TOKENS.blankMarker}
              </Typography.Text>
            ),
          }
        : {}),
    };

    return (
      <Form.Item
        label={
          <Typography.Text style={{ color: cssVar[BMS_COLOR_TOKENS[presentation.labelTone]] }}>
            {USER_UPDATE_FIELD_LABELS[field]}
          </Typography.Text>
        }
        htmlFor={controlId}
        {...(refusal === undefined
          ? {}
          : { validateStatus: 'error' as const, help: refusal.message })}
        {...(presentation.hint === undefined
          ? {}
          : {
              extra: <Typography.Text style={hintStyle}>{presentation.hint}</Typography.Text>,
            })}
      >
        {/*
         * WHY : Trade-offs: the credential is rendered by `Input.Password` with its visibility toggle
         *       switched OFF, which is AAP gap G2 taken deliberately. The mapset declares this control
         *       `ATTRB=(DRK,FSET,UNPROT)` at `app/bms/COUSR02.bms` L130 -- `DRK` is non-display, so a
         *       terminal showed a truly blank field with no indication of length -- while
         *       `Input.Password` shows one dot per character. The difference is accepted as strictly
         *       better feedback with no behavioural change, and the toggle is suppressed because
         *       revealing the value is a capability the original did not have.
         *       Assumptions: this is one of only THREE genuine `(DRK,FSET,UNPROT)` password controls in
         *       the repository -- the others being `app/bms/COSGN00.bms` L175 and `app/bms/COUSR01.bms`
         *       L126 -- and is not to be confused with the `ASKIP` `DRK` carriers on the card and account
         *       maps, which are protected fields a program un-darkens to reveal text rather than inputs.
         */}
        {/*
         * WHY : Assumptions: the credential control declares `autocomplete="off"`, and the declaration
         *       is load-bearing rather than tidiness. This screen guarantees that the control renders
         *       EMPTY after every read -- that is the whole point of not reproducing
         *       `app/cbl/COUSR02C.cbl` L169 -- and a guarantee the code keeps can still be broken by the
         *       browser: without this attribute a password manager may fill the field on its own, which
         *       would put a stored credential on the glass exactly as the reference did. A browser
         *       console advisory suggests `current-password` for a control of this type; that value is
         *       refused here, because it would invite precisely the autofill this screen exists to
         *       avoid and because the value is never transmitted, so no stored credential could be
         *       correct for it.
         *       Alternatives Considered: `new-password`, which also suppresses autofill of an existing
         *       credential. Rejected because it additionally invites a generated-password suggestion for
         *       a value this screen discards, which would tell an operator a new credential had been set
         *       when none had.
         */}
        {presentation.secret === true ? (
          <Input.Password {...controlProps} visibilityToggle={false} autoComplete="off" />
        ) : (
          <Input {...controlProps} />
        )}
      </Form.Item>
    );
  }

  /*
   * WHY : Trade-offs: the mapset's absolute geometry is NOT reproduced, and this is AAP gap G1 taken
   *       deliberately -- `DESIGN_GAPS` in `ui/src/theme/tokens.ts` is the register that records it.
   *       `DFHMDI SIZE=(24,80)` fixes a 24-row by 80-column character grid and all 29 field definitions
   *       carry an absolute `POS=(row,column)`, so a faithful rendering would need character cells at
   *       fixed coordinates. What is preserved is what survives translation: the GROUPING, the READING
   *       ORDER and the TAB ORDER. Each block below is one of the mapset's own rows -- 4 for the caption,
   *       6 for the identifier, 8 for the rule, 11 for the two names, 13 for the credential and 15 for
   *       the user type -- and the two controls the mapset puts on row 11 share one row here. What is
   *       given up is pixel-for-character positioning, which no browser holds across viewport widths and
   *       which would be hostile to an operator using magnification or a screen reader.
   * WHY : Alternatives Considered: `Row` and `Col` with a `gutter`, which is antd's idiomatic form grid.
   *       Rejected because `gutter` takes a PIXEL NUMBER and AAP section 0.3.2 admits only values that
   *       resolve to a design token, so the idiomatic choice would put a hardcoded spacing literal on the
   *       one row that needs it. `Flex` takes antd's semantic sizes, which resolve through the theme's
   *       spacing scale, and its `flex` prop lets each control share the row without a width literal.
   */
  return (
    <Flex vertical gap="large">
      <ScreenHeader
        transactionId={USER_UPDATE_TRANSACTION_ID}
        programName={USER_UPDATE_PROGRAM_NAME}
        now={paintedAt}
      />
      {/*
       * Assumptions: heading level four rather than any other, because the token bridge maps a screen
       * caption to `fontSizeHeading4` and `lineHeightHeading4`, and `Typography.Title level={4}` is the
       * component that resolves to exactly those two tokens. This is the mapset's own row-4 field and
       * not the title band, which `ScreenHeader` paints from rows 1 and 2.
       */}
      <Typography.Title level={4} style={captionStyle}>
        {USER_UPDATE_CAPTION}
      </Typography.Title>
      {/*
       * Assumptions: the band is placed above the controls, which is where every authored screen in this
       * tree puts it, and it is sized from the mapset rather than from the route. The reference paints
       * its message on row 23 below the fields; the band reserves its space at all times either way, so
       * the reading order changes and the layout stability the reserved space exists for does not.
       */}
      <MessageBand message={message} severity={severity} mapset={USER_UPDATE_MAPSET} />
      <Form layout="vertical">
        {renderField('userId')}
        {/*
         * WHY : Refactoring Rationale: the mapset's row-8 rule is rendered as a `Divider` and NOT as its
         *       literal seventy asterisks (`app/bms/COUSR02.bms` L93-L97). A run of asterisks is a
         *       terminal's only way to draw a horizontal line, so the characters are the implementation
         *       of a separator rather than content -- carrying them across verbatim would put seventy
         *       asterisks into the accessibility tree for a screen reader to spell out, and would not
         *       reflow. `Divider` is the semantic separator the design system provides, and it needs no
         *       `aria-hidden` because it already carries the separator role, which is the meaning the
         *       asterisks were standing in for.
         */}
        <Divider style={ruleStyle} />
        <Flex gap="middle" wrap align="flex-start">
          <Flex vertical flex="1 1 0">
            {renderField('firstName')}
          </Flex>
          <Flex vertical flex="1 1 0">
            {renderField('lastName')}
          </Flex>
        </Flex>
        {renderField('password')}
        {renderField('userType')}
      </Form>
      {/*
       * Assumptions: the legend colour is left at the bar's default, which `app/bms/COUSR02.bms` L160
       * confirms -- this screen's row-24 field is `COLOR=YELLOW`, the majority the bar already defaults
       * to. A clicked control and the corresponding key press dispatch through the same `invoke`, so the
       * two paths cannot diverge.
       */}
      <PfKeyBar keys={bindings} onInvoke={invoke} />
    </Flex>
  );
}

/*
 * WHY : Assumptions: BOTH a named and a default export are published, because two consumers read this
 *       module differently. `ui/src/router.tsx` lazily imports each screen and republishes
 *       `module.<Name>` under the `default` key that `React.lazy` requires, so the named export is what
 *       the route table binds; the folder contract for this file additionally specifies a default
 *       export. Every other authored screen in this tree publishes the same pair, so a reader moving
 *       between them meets one convention.
 */
export default UserUpdateScreen;
