/**
 * @file The user update screen, migrated from `app/cbl/COUSR02C.cbl` (415 lines) and its mapset
 * `app/bms/COUSR02.bms` (29 `DFHMDF` fields, 12 of them named), mounted at `/users/:id/edit`.
 *
 * Purpose
 * -------
 * Render the reference screen's two-turn fetch-then-save workflow over one administered user: read
 * the row by identifier, let an administrator edit the three values the contract admits, and write
 * the change. It replaces CICS transaction `CU02`, which `app/cbl/COUSR02C.cbl` L37 declares as
 * `WS-TRANID PIC X(04) VALUE 'CU02'`, and it renders four of the five editable controls the mapset
 * paints -- the credential being registered divergence D-10, below -- plus nine of the ten sentences the
 * program emits.
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
 * What is deliberately NOT carried across: the credential control
 * --------------------------------------------------------------
 * ⚠️ Refactoring Rationale: the mapset's password control and the program's `Password can NOT be
 * empty...` refusal are BOTH absent from this screen, and their absence is registered divergence D-10 in
 * `docs/architecture/cobol-to-service-traceability.md` section 7.2 rather than a silent omission. The
 * reference paints the control at `app/bms/COUSR02.bms` L125-L134, pre-fills it from the stored
 * plaintext credential -- `MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI` at `app/cbl/COUSR02C.cbl` L169,
 * reading `05 SEC-USR-PWD PIC X(08).` at `app/cpy/CSUSR01Y.cpy` L21 -- compares it at L227 to L230 and
 * writes it. None of those four steps has a target: AAP section 0.7.8 declines parity here explicitly,
 * moving identity to a managed user pool so that `auth.users` carries no password column at all, and the
 * published `UpdateUserRequest` seals itself with `additionalProperties: false` over three properties,
 * none of them a credential.
 *
 * ⚠️ Refactoring Rationale: an earlier shape of this screen KEPT the control and its blank refusal while
 * omitting the value from the request, on the ground that the refusal is "a user-visible behaviour of the
 * screen rather than a property of the request". A review found that reasoning insufficient and it is
 * withdrawn. Requiring an administrator to type a credential that is then discarded is worse than either
 * alternative: it states, by every affordance a form has, that a credential was set, and an administrator
 * who typed a new one would reasonably believe the account's password had changed when nothing anywhere
 * had changed. A control whose value is thrown away is also a control a password manager will fill and a
 * browser will retain, so the discarded value does not stay discarded.
 *
 * Alternatives Considered: implementing a truthful credential reset, so the control could keep its
 * meaning. Rejected on scope rather than on difficulty -- the AAP publishes no administrative
 * credential-reset operation, and `services/auth-service/src/main/resources/openapi/auth-api.yaml`
 * declares eight operations, none of them one. Onboarding's own credential is published as
 * `CreatedUserResponse.credentialSecretName`, the NAME of a managed-secret entry, precisely so that no
 * credential travels in a response body; a reset operation faithful to that design needs a service
 * endpoint, a user-pool administrative grant and its own contract, which is a capability to plan rather
 * than a control to re-label.
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
import { useShellSlot } from '../../layout/AppShell';
import { fieldAriaProps, fieldErrorHelp, fieldHintId } from '../../layout/fieldHelp';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { UNIFORM_PF_KEY_LABELS, decodeBmsLegendText } from '../../layout/PfKeyBar';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap, PfKeyRejection } from '../../layout/usePfKeys';
import {
  INVALID_KEY_PRESSED,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  formatMessageTemplate,
} from '../../messages/messages';
import type { MapsetName } from '../../messages/messages';
import { ADMIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import { ScreenTitle } from '../../layout/ScreenTitle';
import {
  BMS_COLOR_TOKENS,
  BMS_TEXT_COLOR_TOKENS,
  FIELD_ERROR_TOKENS,
  TYPOGRAPHY_TOKENS,
} from '../../theme/tokens';

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
export type UserUpdateField = 'userId' | 'firstName' | 'lastName' | 'userType';

/**
 * The four field labels this screen renders, verbatim from `app/bms/COUSR02.bms`.
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
  /*
   * WHY : ⚠️ Assumptions: the mapset's fifth label, `Password:` at L125-L129, is absent because the
   *       control it labels is absent -- registered divergence D-10. It is not carried as an unused
   *       constant, because a published label with no control is a value a later screen would render
   *       against a request that has nowhere to put it.
   */
  /** `app/bms/COUSR02.bms` L140-L144, `COLOR=TURQUOISE`, `LENGTH=11`, `POS=(15,17)`. */
  userType: 'User Type: ',
} as const satisfies Readonly<Record<UserUpdateField, string>>;

/**
 * The one hint this screen paints beside a control, verbatim and in `COLOR=BLUE`.
 *
 * ⚠️ Assumptions: the mapset paints TWO, and the credential's `(8 Char)` at L135-L139 is absent for the
 * same reason its control is -- registered divergence D-10. That hint advertised the width of a value
 * nothing now accepts.
 *
 * Assumptions: the user-type hint is the ONLY place the `'A'`/`'U'` domain is advertised to an
 * operator, because `app/cbl/COUSR02C.cbl` contains no domain check for that field -- L204 tests it
 * for blank and nothing else. Dropping the hint would leave the domain undiscoverable.
 *
 * Assumptions: the mapset paints a second hint, `INITIAL='(8 Char)'` at L135-L139, beside the
 * credential control. It goes with that control rather than being retained without it, and the width it
 * names is separately registered as `D-SIGNON-PASSWORD-HINT` for the one screen that still has a
 * credential control to hint at.
 */
export const USER_UPDATE_FIELD_HINTS = {
  /** `app/bms/COUSR02.bms` L150-L154, `COLOR=BLUE`, `LENGTH=17`, `POS=(15,19)`. */
  userType: '(A=Admin, U=User)',
} as const;

/** How one control is presented, with every member measured from the mapset's own operands. */
interface UserUpdateFieldPresentation {
  /** Measured `COLOR=` role of the control's label, resolved through `BMS_TEXT_COLOR_TOKENS`. */
  readonly labelTone: keyof typeof BMS_TEXT_COLOR_TOKENS;
  /** Whether the mapset places the initial cursor here, from its single `IC` operand. */
  readonly initialCursor?: boolean;
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
  userType: { labelTone: 'TURQUOISE', hint: USER_UPDATE_FIELD_HINTS.userType },
};

/*
 * WHY : Assumptions: every width below is the mapset's own `LENGTH=` operand, and each is corroborated
 *       independently by the record layout so neither source is trusted alone. `USRIDIN` is
 *       `LENGTH=8` at `app/bms/COUSR02.bms` L85-L89 and `05 SEC-USR-ID PIC X(08).` at
 *       `app/cpy/CSUSR01Y.cpy` L18; `FNAME` is `LENGTH=20` at L103-L107 and `PIC X(20)` at L19;
 *       `LNAME` is `LENGTH=20` at L116-L120 and `PIC X(20)` at L20; `USRTYPE` is `LENGTH=1` at
 *       L145-L149 and `PIC X(01)` at L22.
 *       These are the terminal's own field widths, which is how a 3270 refused a keystroke past the
 *       end of a field, so `maxLength` is the faithful browser equivalent rather than a convenience.
 * WHY : ⚠️ Assumptions: the mapset's fifth width, `PASSWD` at `LENGTH=8` (L130-L134) corroborated by
 *       `PIC X(08)` at L21, is absent because its control is -- registered divergence D-10. It is not
 *       carried as an unused constant: a width bounds keystrokes into a control, and there is none.
 */
export const USER_UPDATE_FIELD_WIDTHS = {
  userId: USER_ID_MAX_LENGTH,
  firstName: 20,
  lastName: 20,
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
 * Assumptions: all four are `SHARED_MESSAGES` entries rather than program-scoped ones because
 * `app/cbl/COUSR01C.cbl` raises the same literals, and the catalog files a sentence under the
 * group that can cite every site emitting it. The provenance index in
 * `ui/src/messages/messages.ts` records this program's own lines for each: L182 for the identifier
 * (also L148 on the fetch arm), L188, L194 and L206.
 *
 * ⚠️ Assumptions: the cascade's FIFTH sentence, `Password can NOT be empty...` at L200, has no entry
 * here because it has no control to refuse -- registered divergence D-10. The catalog still publishes it,
 * because a catalog is a register of what the reference emits rather than of what this screen renders, and
 * `ui/src/screens/signon` raises the same literal for a credential it genuinely submits.
 */
const BLANK_FIELD_MESSAGES = {
  userId: SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY,
  firstName: SHARED_MESSAGES.FIRST_NAME_CAN_NOT_BE_EMPTY,
  lastName: SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY,
  userType: SHARED_MESSAGES.USER_TYPE_CAN_NOT_BE_EMPTY,
} as const satisfies Readonly<Record<UserUpdateField, string>>;

/*
 * WHY : Assumptions: the order is the reference's own and it is SHORT-CIRCUIT, not a set of
 *       independent rules. `app/cbl/COUSR02C.cbl` L179-L213 is one `EVALUATE TRUE` whose arms test the
 *       identifier (L180), the first name (L186), the last name (L192), the credential (L198) and the
 *       user type (L204); the first arm that matches raises its sentence and no later arm is evaluated.
 *       An operator who clears three controls therefore reads exactly one sentence, about the first of
 *       them, which is why this is an ordered list walked to the first hit rather than a validation
 *       pass that accumulates.
 * WHY : ⚠️ Assumptions: FOUR of the five arms are transcribed, and the missing one is the password's at
 *       L198 to L203 -- registered divergence D-10. Its removal does not reorder the others: the arms
 *       are independent tests reached in sequence, so dropping the fourth leaves the first three
 *       reachable exactly as before and moves the user type's arm from fifth position to fourth without
 *       changing which submissions reach it. An operator who clears the last name still reads the last
 *       name's sentence, and one who clears the user type still reads the user type's.
 */
const SAVE_VALIDATION_ORDER: readonly UserUpdateField[] = [
  'userId',
  'firstName',
  'lastName',
  'userType',
];

/** HTTP status the service answers when no user row carries the identifier. */
const NOT_FOUND_STATUS = 404;

/** Values the four controls hold, with the user type narrowed to the domain its control admits. */
interface UserUpdateValues {
  readonly userId: string;
  readonly firstName: string;
  readonly lastName: string;
  readonly userType: '' | UserType;
}

/** Every control empty, which is the state `INITIALIZE-ALL-FIELDS` leaves at L403-L411. */
const EMPTY_VALUES: UserUpdateValues = {
  userId: '',
  firstName: '',
  lastName: '',
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
 * @param {UserUpdateValues} values - Values the four controls hold.
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
 * @returns {boolean} `true` when the name is one of this screen's four controls.
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
 *       sets `USR-MODIFIED-YES` when any differs. The password arm cannot be reproduced because there is
 *       nothing on either side of it: this screen renders no credential control -- registered divergence
 *       D-10 -- and no response type carries a credential to compare one against, `UserResponse`
 *       declaring five members of which none is one and the update contract declaring three. Excluding it
 *       is also what keeps the `Please modify to update ...` outcome (L239) reachable at all: a
 *       comparison against a value that does not exist would differ on every save.
 * WHY : Assumptions: both sides are trimmed before comparison, because the reference compares two
 *       equally space-padded fixed-width fields. `FNAMEI` is `PIC X(20)` and so is `SEC-USR-FNAME`, so
 *       trailing blanks are equal on both sides of L219 and the comparison is insensitive to them. The
 *       browser holds unpadded strings, so trimming reproduces that insensitivity; comparing untrimmed
 *       values would report a change for a trailing space the reference ignores.
 */

/**
 * Reports whether the typed values differ from the row as the service last returned it.
 * @param {UserUpdateValues} values - Values the four controls hold.
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
 * this route. The name is per-route rather than global -- the only other parameterised routes in the
 * table are the two card routes, and both spell theirs `cardKey` (`ui/src/routes/cards.ts` L88, L91) --
 * so the two spellings are not interchangeable and a mismatch resolves to `undefined` silently. It is
 * optional in this component's own terms: an operator may reach the screen with no identifier and type
 * one, which is exactly what the reference permits when `CDEMO-CU02-USR-SELECTED` arrives as `SPACES`
 * (L99-L104).
 * @returns {ReactElement} The header band, caption, message band, the four controls and the key legend.
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
   * WHY : Assumptions: every request this screen issues is sequenced, and the counter is the ONLY thing
   *       that makes an outcome's arrival order irrelevant. Both turns this screen has -- the read on
   *       the Enter arm and the re-read-then-write on the save arms -- resolve asynchronously, and
   *       nothing in a promise's resolution order relates it to the turn the operator is now waiting on,
   *       so the LAST answer to arrive would otherwise win whichever question it answered.
   * WHY : Assumptions: the `busy` guards on the key handlers do NOT close this on their own, and the
   *       reason is that the competing turn is not always a key press. `loadRouteUser` below re-runs
   *       whenever the route names a different user, so navigating from `/users/A/edit` to
   *       `/users/B/edit` issues B's read with A's read still outstanding -- with no key pressed and no
   *       handler to guard. If A's answer arrived second it would seed A's first name, last name and
   *       user type into the form under B's identifier, and because the form's values are what the save
   *       arm submits, the next save would write A's values onto B. The counter is what makes that
   *       impossible; the key guards only close the ordinary way of reaching it.
   * WHY : Assumptions: ONE counter sequences reads and saves together rather than one counter per kind.
   *       The question every outcome has to answer is the same -- is this still the turn the operator is
   *       waiting on -- and any newer turn supersedes any older one whatever its kind. Per-kind counters
   *       would let a fresh read leave a stale save's re-read live, which is the pairing that reseeds the
   *       form from a row the operator has already navigated away from.
   * WHY : Trade-offs: requests are IGNORED rather than aborted. `getUser` and `updateUser` in
   *       `ui/src/api/auth.ts` accept no abort signal, so aborting would mean changing the transport
   *       contract for every caller of both; the cost of ignoring is a response body already on the wire
   *       being discarded, which the operator cannot observe and which cannot produce a wrong screen.
   *       This is the same trade-off `ui/src/screens/accountUpdate/index.tsx` records for the same
   *       reason, and the two screens use one shape deliberately.
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
   * Assumptions: this is what CLEARING does, and what leaving the screen does. Neither issues a
   * request, so neither should have a token waiting on it -- but both mean the outstanding question is
   * about a screen state the operator has abandoned, so its answer must not be applied.
   * @returns {void} Completion is the advanced sequence.
   */
  function invalidateTurnsInFlight(): void {
    turnSequence.current += 1;
  }

  /*
   * WHY : Assumptions: the cleanup invalidates rather than cancels, for the reason recorded on the
   *       trade-off above. Trade-offs: this half is DEFENSIVE and is not observable in the React version
   *       this bundle pins -- a state update on an unmounted component is a silent no-op in React 19,
   *       measured rather than assumed -- so no test distinguishes it. It is kept because it completes
   *       the invariant at no runtime cost and because the guarantee it leans on belongs to React rather
   *       than to this screen, which a later upgrade could withdraw.
   */
  useEffect(
    /**
     * Registers the unmount invalidation.
     * @returns {() => void} The cleanup that makes an outstanding turn inert.
     */
    (): (() => void) => invalidateTurnsInFlight,
    [],
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
   * Assumptions: the three editable controls are blanked BEFORE the read is issued, because
   * `app/cbl/COUSR02C.cbl` L158-L161 moves `SPACES` into the first name, last name, password and user
   * type and only then performs `READ-USER-SEC-FILE` at L163. An operator therefore never sees one
   * row's values beside another row's identifier, not even briefly. The reference's fourth target on
   * that one `MOVE` is the credential, which this screen does not paint -- see the module header -- so
   * three of the four blanks are reproduced and the fourth has nothing to blank.
   *
   * ⚠️ Assumptions: two of that paragraph's statements have no counterpart, both concerning the
   * credential -- the blanking at L160 and the pre-fill from the stored plaintext value at L169. Neither
   * is carried because this screen renders no credential control at all; registered divergence D-10 states
   * why, and the module header carries the reasoning.
   */
  const load = useCallback(
    /**
     * Reads one user by identifier and seeds the editable controls from the row.
     * @param {string} userId - Identifier to read, as the control or the route supplied it.
     * @returns {void} Completion is represented by the screen's own state; every failure of the request
     *   is reduced to a band sentence and field marks rather than propagated.
     */
    (userId: string): void => {
      /*
       * WHY : Assumptions: the turn opens BEFORE the blank test, so a refusal supersedes an outstanding
       *       read as surely as a request does. A read the operator has replaced with a refusal is still
       *       a read whose answer would seed the form, and the refusal leaves the identifier on the
       *       screen -- so an older answer arriving afterwards would fill the three controls beneath a
       *       sentence saying the identifier is empty.
       */
      const token = beginTurn();

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
        /*
         * WHY : ⚠️ Refactoring Rationale: the flag is CLEARED here, and its absence left the screen
         *       permanently disabled. This arm opens a turn -- deliberately, so an outstanding read
         *       cannot seed the form beneath a refusal -- and then issues no request, so it never sets
         *       the flag itself; meanwhile the read it superseded returns without touching the flag,
         *       because a superseded answer must not re-enable keys while a NEWER request is in flight.
         *       With neither party clearing it, an operator who emptied the identifier while a read was
         *       outstanding was left with every key inert and no way back except the clear key. The
         *       invariant the superseded arm below states -- that every superseding path either opens a
         *       turn of its own or clears the flag itself -- is what this line restores.
         */
        setBusy(false);
        return;
      }

      setValues(
        /**
         * Records the identifier and blanks the three editable controls, as L158-L161 does before reading.
         * @param {UserUpdateValues} previous - Values as they stand.
         * @returns {UserUpdateValues} The identifier alone, with every editable control emptied.
         */
        (previous: UserUpdateValues): UserUpdateValues => ({
          ...previous,
          userId,
          firstName: '',
          lastName: '',
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
          /*
           * WHY : Assumptions: a superseded answer returns having touched NOTHING, and `busy` in
           *       particular is left alone. It describes the turn now in flight rather than this one, so
           *       clearing it here would re-enable the keys while a newer request was still outstanding.
           *       Every path that supersedes a turn either opens one of its own -- which sets `busy` --
           *       or clears `busy` itself, so it can never be left set with nobody to clear it.
           */
          if (!isCurrentTurn(token)) {
            return;
          }

          setValues(
            /**
             * Seeds the three editable values from the row, leaving the keyed identifier as typed.
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
          /*
           * WHY : Assumptions: a superseded FAILURE is discarded on the same terms as a superseded
           *       success. A refusal is an answer too, so applying it would put `User ID NOT found...`
           *       and a marker on a screen the operator has since pointed at a different user -- naming a
           *       refusal of a key that is no longer on the glass.
           */
          if (!isCurrentTurn(token)) {
            return;
          }

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

    /*
     * WHY : Assumptions: the turn opens here rather than at the top of this function, because the two
     *       returns above supersede nothing -- the `busy` arm has not accepted a turn at all, and the
     *       blank arm refuses locally without issuing a request, so an older answer it might discard is
     *       one that belongs to a turn the operator has not replaced. Opening a turn on either would
     *       silently cancel an outstanding read that is still the one being waited on.
     */
    const token = beginTurn();

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
        /*
         * WHY : Assumptions: the re-read's answer is checked against the turn BEFORE the comparison, not
         *       after it. The comparison's other operand is `values`, which a later turn may already have
         *       replaced, so comparing first would decide `Please modify to update ...` -- or a write --
         *       from one turn's row against another turn's form. The row is the half that goes stale here,
         *       because the form is read at the moment of comparison and the row was fetched earlier.
         */
        if (!isCurrentTurn(token)) {
          return Promise.resolve();
        }

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
         * WHY : ⚠️ Assumptions: the request carries THREE members, and every one of them is a value this
         *       screen renders and the operator can see. `UpdateUserRequest` declares exactly
         *       `firstName`, `lastName` and `userType` and seals itself with
         *       `additionalProperties: false`; that contract states outright that the baseline's
         *       `Password can NOT be empty...` branch "has no counterpart anywhere in this contract,
         *       since the service neither accepts nor stores a password on create or update".
         *       ⚠️ Refactoring Rationale: this screen used to COLLECT a credential and omit it here, and
         *       the note in this position defended that as keeping "a user-visible behaviour of the
         *       screen". It is withdrawn -- the control and its refusal are gone, registered as
         *       divergence D-10 -- because the request being three members is now a property a reader can
         *       verify against the form rather than a discrepancy the form actively contradicted: an
         *       administrator who typed a credential into a discarded control had every reason to believe
         *       the account's password had changed.
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
             * WHY : ⚠️ Refactoring Rationale: this guard was LOST while the note two arms below kept
             *       claiming that "all four outcomes of a save turn ... are applied only while the turn is
             *       still the one being waited on" and that "gating three of the four would leave one path
             *       by which a superseded save reaches the band". Three were gated and this, the fourth,
             *       was not -- so a write released after PF4 had emptied the screen reseeded the three
             *       blanked controls from its response and painted `User NNNNNNNN has been updated ...`
             *       over a form the operator had just cleared. It is restored.
             * WHY : Assumptions: a superseded WRITE still committed, and only its screen effects are
             *       dropped. The reference's `EXEC CICS REWRITE` is equally irreversible once issued, so
             *       discarding the outcome here is not discarding the change -- it is declining to reseed
             *       a form the operator has since pointed elsewhere, and to claim `... has been updated
             *       ...` about a row that is no longer the one on the glass. The row the next read
             *       returns is the authority either way.
             */
            if (!isCurrentTurn(token)) {
              return;
            }

            /*
             * WHY : Assumptions: the three comparable controls are reseeded from the RESPONSE rather than
             *       left as typed. The reference re-sends the same map after the write, so the values on
             *       the glass are the ones written, and the response is what restates them
             *       authoritatively -- reusing the typed values would show an operator their own input
             *       where the stored row is what they need to see.
             */
            setValues(
              /**
               * Restates the three written values from the response, which are all this screen holds.
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
            if (!isCurrentTurn(token)) {
              return;
            }

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
        /*
         * WHY : Assumptions: both of this arm's siblings are gated on the same token, so all four
         *       outcomes of a save turn -- re-read refused, nothing changed, written, write refused --
         *       are applied only while the turn is still the one being waited on. Gating three of the
         *       four would leave one path by which a superseded save reaches the band.
         */
        if (!isCurrentTurn(token)) {
          return;
        }

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
     * WHY : Assumptions: this arm is guarded on the in-flight flag SEPARATELY from the guard inside
     *       {@link attemptSave}, and the separate guard is the point. That one returns an already-resolved
     *       promise when a turn is in flight, which this arm's `.then` runs immediately -- so a PF3 taken
     *       while a read or a save was outstanding EXITED THE SCREEN WITHOUT SAVING, on the one key whose
     *       own legend promises `F3=Save&&Exit`. The unconditional transition recorded below is
     *       unconditional on the save's OUTCOME, which is the reference's behaviour; it was never meant to
     *       be unconditional on the save having been attempted.
     * WHY : Trade-offs: this guard and the `disabled` on the PF3 binding are REDUNDANT, and the redundancy
     *       is deliberate rather than accidental. It was measured: removing either one alone leaves
     *       `userUpdate.test.tsx`'s exit case green, because each closes the path on its own, and only
     *       removing both fails it. The pair is kept because they close it at different levels -- the
     *       binding withholds the key so an operator sees it greyed rather than pressing a live-looking
     *       control that does nothing, and this guard makes the handler correct on its own terms whatever
     *       binds it, which matters for the one key whose failure mode is leaving the screen without
     *       writing. Alternatives Considered: keeping only the binding, so every line is individually
     *       falsifiable. Rejected here, unlike the analogous case on the transaction-capture screen, because
     *       that one was UNREACHABLE by construction while this one runs whenever the binding is reached
     *       without its flag -- it is redundant, not dead.
     */
    if (busy) {
      return;
    }

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
   * Assumptions: all FOUR controls this screen renders are blanked including the identifier, and the
   * message with them. `INITIALIZE-ALL-FIELDS` at L403-L411 moves `SPACES` into the identifier, the first
   * name, the last name, the password, the user type and `WS-MESSAGE` in one statement, and L405 places
   * the cursor back on the identifier; the credential is the one item of the six with no control here to
   * blank, per registered divergence D-10. The field marks go with them, because a mark left beneath a control the operator
   * has just emptied would name a refusal of a value that is no longer there.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function clearScreen(): void {
    /*
     * WHY : Assumptions: PF4 is deliberately NOT guarded on the in-flight flag, unlike ENTER, PF5 and
     *       PF3. It is not a competing turn -- it asks no question and issues no request -- it is the
     *       ABORT of whichever turn is outstanding, which is why it invalidates the sequence and clears
     *       the flag instead of declining to run. That also gives the operator a way out of a turn that
     *       is taking its time, on the one key whose whole purpose is to put the screen back to empty.
     * WHY : Assumptions: the invalidation is what makes the clear STICK. Without it, a read still on the
     *       wire would resolve into the three controls this function has just blanked, seeding one row's
     *       values under an empty identifier and painting `Press PF5 key to save your updates ...` over a
     *       screen the operator had just emptied -- and because the form's values are what the save arm
     *       submits, the next save would be addressed to a blank key carrying that row's values.
     * WHY : Assumptions: the flag is cleared here rather than left to the superseded response, because
     *       that response now returns before touching it. Every other superseding path opens a turn of
     *       its own, which sets the flag again; this one does not, so it owns the clear.
     */
    invalidateTurnsInFlight();
    setBusy(false);
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
   * WHY : Assumptions: the three keys that OPEN a turn -- ENTER, PF3 and PF5 -- additionally carry
   *       `disabled` while one is in flight, so the legend greys them out instead of leaving a control
   *       that looks live and does nothing. The guards inside their handlers are kept as well rather than
   *       replaced: they are what a test can exercise deterministically, and two independent refusals of
   *       a second concurrent turn are cheaper than one.
   * WHY : Assumptions: PF4 and PF12 are deliberately left ENABLED throughout. PF4 is the abort of the
   *       outstanding turn, not a competitor for it, and PF12 leaves the screen -- greying either would
   *       trap an operator on a screen whose only unresponsive keys were the ones offering a way off it.
   * WHY : Trade-offs: `disabled` reports through the invalid-key channel, whose sentence
   *       `Invalid key pressed...` is the reference's answer for a key OUTSIDE its list (L127-L130) and
   *       would be wrong for one this screen offers. The rejection payload carries its reason, so the
   *       handler below states the sentence only for an unmapped key; the alternative -- withholding
   *       `disabled` and relying on the in-handler guards alone -- was what left the keys looking live.
   */
  const keyHandlers: PfKeyHandlerMap = {
    ENTER: { onInvoke: handleFetch, label: USER_UPDATE_KEY_LABELS.ENTER, disabled: busy },
    PFK03: {
      onInvoke: handleSaveAndExit,
      label: USER_UPDATE_KEY_LABELS.PFK03,
      action: 'save',
      disabled: busy,
    },
    PFK04: { onInvoke: clearScreen, label: USER_UPDATE_KEY_LABELS.PFK04 },
    PFK05: { onInvoke: handleSave, label: USER_UPDATE_KEY_LABELS.PFK05, disabled: busy },
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
     * @param {PfKeyRejection} rejection - Why the key was refused, and by which attention identifier.
     * @returns {void} Completion is represented by the screen's own state.
     */
    onInvalidKey: (rejection: PfKeyRejection): void => {
      /*
       * WHY : Assumptions: a key refused because it is momentarily DISABLED is silent, and only an
       *       UNMAPPED one raises the sentence. The reference's `WHEN OTHER` arm answers for keys outside
       *       its list of six; a key this screen registers and greys out for the duration of one turn is
       *       not outside that list, so answering `Invalid key pressed...` for it would tell an operator
       *       the screen does not offer a key whose own legend is on the glass in front of them.
       */
      if (rejection.reason === 'disabled') {
        return;
      }

      setMessage(INVALID_KEY_PRESSED);
      setSeverity('error');
    },
  });

  /*
   * WHY : ⚠️ Refactoring Rationale: ALL THREE persistent zones are delegated to the shell -- the
   *       title band, the row-23 message line and the row-24 legend -- and each half of that arrived by
   *       its own correction, which is why two contradictory notes stood here. The first said the message
   *       band "stays local"; it does not, and by the time that sentence was written this screen's own
   *       band had already been removed, so every sentence `report` wrote into state -- all five field
   *       refusals, the invalid-key sentence and the stored-write acknowledgement -- was computed and
   *       painted nowhere. `ui/src/layout/AppShell.tsx` is mounted as the layout route and paints a zone
   *       only for a screen that published one, so publishing all three is what gets them painted once,
   *       and a screen that also composed them would show two of each.
   * WHY : Assumptions: the mapset's `severity` travels with the text because this one channel carries the
   *       stored-write acknowledgement as well as the refusals, and painting an acknowledgement in the
   *       refusal colour would tell an operator a committed write had failed. No legend colour is
   *       delegated, because `app/bms/COUSR02.bms` L160 paints this screen's row-24 field `COLOR=YELLOW`,
   *       which is the slot's own default.
   * WHY : ⚠️ Assumptions: delegating `pfKeys` hands the shell the bindings to RENDER and leaves this
   *       screen owning the keyboard -- `bindings` and `invoke` come from its own `usePfKeys` call and
   *       travel up unchanged, and an activation of a rendered legend control is forwarded straight back
   *       to `invoke`. The claim that stood here, that the shell adds a sign-off key of its own "when
   *       this screen leaves that attention identifier free, which is decided by AID in the shell", is
   *       withdrawn: the shell installs NO keyboard listener at all and offers sign-off as a rendered
   *       control, for the reason recorded at `SHELL_SIGN_OFF_LABEL`. So there is no second listener to
   *       stand down and no AID arbitration anywhere -- this screen's PF12 exit is the only PF12 on the
   *       document while it is mounted.
   */
  useShellSlot({
    screen: { transactionId: USER_UPDATE_TRANSACTION_ID, programName: USER_UPDATE_PROGRAM_NAME },
    now: paintedAt,
    message: { text: message, severity, mapset: USER_UPDATE_MAPSET },
    pfKeys: {
      keys: bindings,
      onInvoke: invoke,
    },
  });

  /*
   * WHY : Assumptions: every design value below is a TOKEN NAME resolved through `cssVar`, so this
   *       screen carries no colour, weight, spacing or font literal. The colour roles are the mapset's
   *       own measured attributes, mapped by `BMS_COLOR_TOKENS`: `COLOR=NEUTRAL` on the caption
   *       (`app/bms/COUSR02.bms` L76) resolves to `colorTextSecondary`, `COLOR=GREEN` on the identifier
   *       label (L81) to `colorSuccess`, `COLOR=TURQUOISE` on the three remaining labels (L99, L112 and
   *       L141) to `colorInfo`, `COLOR=BLUE` on the one hint (L151) to `colorPrimary`, and
   *       `COLOR=YELLOW` on the row-8 rule (L93) to `colorWarning`. `ATTRB=BRT` on the caption (L75) is
   *       carried by `fontWeightStrong` rather than by a colour, which is what keeps brightness and
   *       colour independent in the target the way the mapset has them independent.
   */
  const captionStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };
  const hintStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.BLUE] };
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
   *       one property of the 3270 presentation that a browser can still keep. The two name controls
   *       and the one-character user type are free text and are deliberately left in the body face.
   */
  const fixedPitchStyle: CSSProperties = { fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData] };

  /**
   * Renders one labelled control with the mapset's colour, width and hint, plus any refusal it carries.
   *
   * Assumptions: the label is bound to its control by `htmlFor` and a generated identifier rather than
   * by nesting, so assistive technology announces the mapset's own label text when the control takes
   * focus. The 3270 original was keyboard-only, so this is fidelity rather than an addition: a label
   * that was merely adjacent on a character grid has to be associated explicitly in a browser.
   *
   * ⚠️ Refactoring Rationale: the refusal sentence and the width hint are now ASSOCIATED with the
   * control as well as rendered beside it, through the shared `ui/src/layout/fieldHelp.tsx` helpers. The
   * label was bound and the other two were not: the refusal was passed to `Form.Item` as a bare string,
   * so the design system rendered it in its own container with an identifier nothing referenced, and the
   * hint went to `extra` the same way. The control therefore carried no `aria-invalid` and no
   * `aria-describedby`, which leaves an operator using a screen reader with a marked field whose reason
   * is announced only if they happen to move past it -- and on this screen the reason is the whole of the
   * refusal, since the band shows the same sentence for whichever of the four controls failed.
   *
   * Assumptions: the helpers are used rather than the attributes written here, and that is what keeps
   * this screen consistent with the four that already use them. `fieldAriaProps` also encodes the order
   * the design system renders the two containers in -- refusal above hint -- so the description is
   * announced in the order it is read.
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
      ...fieldAriaProps(controlId, {
        invalid: refusal !== undefined,
        hasError: refusal !== undefined,
        hasHint: presentation.hint !== undefined,
      }),
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
          <Typography.Text style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS[presentation.labelTone]] }}>
            {USER_UPDATE_FIELD_LABELS[field]}
          </Typography.Text>
        }
        htmlFor={controlId}
        {...(refusal === undefined
          ? {}
          : {
              validateStatus: 'error' as const,
              help: fieldErrorHelp(controlId, refusal.message),
            })}
        {...(presentation.hint === undefined
          ? {}
          : {
              extra:
                (
                  /*
                   * WHY : Assumptions: the hint element carries the identifier `fieldAriaProps` derives for
                   *       it, so the control's description resolves to something rendered. The style stays
                   *       on this element rather than moving into the helper, because the colour is the
                   *       mapset's own `COLOR=BLUE` on that field and the helper is colour-agnostic by
                   *       design -- the design system colours the refusal container itself.
                   */
                  <Typography.Text id={fieldHintId(controlId)} style={hintStyle}>
                    {presentation.hint}
                  </Typography.Text>
                ),
            })}
      >
        {/*
         * WHY : ⚠️ Refactoring Rationale: every control this screen renders is a plain `Input`, and the
         *       branch that selected `Input.Password` for a non-display control is gone with the control
         *       it served. The mapset does declare one `ATTRB=(DRK,FSET,UNPROT)` field, at
         *       `app/bms/COUSR02.bms` L130, and rendering it as a masked input was AAP gap G2 taken
         *       deliberately -- but the value was then discarded rather than submitted, so what the
         *       masking protected was a credential that went nowhere. Registered divergence D-10 records
         *       the removal; the two remaining genuine password controls in the repository,
         *       `app/bms/COSGN00.bms` L175 and `app/bms/COUSR01.bms` L126, are unaffected, and the sign-on
         *       screen still takes gap G2 exactly as before, because a credential it collects is a
         *       credential it sends.
         */}
        <Input {...controlProps} />
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
   *       6 for the identifier, 8 for the rule, 11 for the two names and 15 for the user type -- and the
   *       two controls the mapset puts on row 11 share one row here. Row 13 is absent because the control
   *       it carried is: registered divergence D-10. What is
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
      {/*
       * ⚠️ Assumptions: the rank comes from `ui/src/layout/ScreenTitle.tsx` and the SIZE from the
       * bridge entries it applies, where this site read `level={4}` and relied on the component to
       * supply both. That reliance is what the outline could not survive: ten other screens painting the
       * same row-4 caption chose `level={3}` for the same size reason and thereby outranked the
       * application title above them. Separating the two lets every caption share one rank without any
       * of them changing size. This is the mapset's own row-4 field and not the title band, which the
       * shell paints from rows 1 and 2 out of the delegated identity.
       */}
      <ScreenTitle style={captionStyle}>{USER_UPDATE_CAPTION}</ScreenTitle>
      {/*
       * Refactoring Rationale: the message line that used to sit here is delegated to the shell, which
       * paints it at row 23 -- below the fields, which is where the reference paints it. Composing it
       * above the controls was this tree's earlier convention and it inverted the source's order; the
       * band reserves its space at all times either way, so the layout stability that reservation exists
       * for is unaffected by the move.
       */}
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

        {renderField('userType')}
      </Form>
      {/*
       * Assumptions: the legend the shell paints from this screen's delegated bindings dispatches through
       * the same `invoke` a real key press does, so a clicked control and its key cannot diverge.
       */}
    </Flex>
  );
}

/*
 * WHY : Refactoring Rationale: this module publishes the component under its NAME ONLY, and the
 *       default export that used to sit here has been removed rather than kept alongside it. The
 *       argument for publishing both was that a route could then be declared as
 *       `lazy(() => import('./screens/<name>'))` with no adapter -- but no route is declared that way
 *       anywhere, so the second key had no caller, and AAP section 0.6.2.1 fixes the import discipline
 *       for this tree as named imports with the named-to-default adapter held in `ui/src/router.tsx`.
 *       Two keys for one component also make a screen reachable by two spellings, so a reader cannot
 *       tell from an import which convention this tree follows.
 */
