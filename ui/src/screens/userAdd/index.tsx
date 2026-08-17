/**
 * @file The add-user screen, migrated from `app/cbl/COUSR01C.cbl` (299 lines) and its mapset
 * `app/bms/COUSR01.bms` (28 `DFHMDF` fields, 12 of them named), mounted at `/users/new`.
 *
 * Purpose
 * -------
 * Render the reference screen's single-turn create workflow: collect the five values the mapset
 * paints, refuse the first blank one in the order the program tests them, and write the new
 * administered user. It replaces CICS transaction `CU01`, which `app/cbl/COUSR01C.cbl` L37 declares
 * as `WS-TRANID PIC X(04) VALUE 'CU01'`, and it renders all five editable controls the mapset paints
 * plus every sentence the program emits.
 *
 * One turn, not two
 * -----------------
 * Assumptions: this screen has no read. Its sibling at `/users/:id/edit` fetches a row before it can
 * change one, but `app/cbl/COUSR01C.cbl` L90-L103 dispatches Enter straight into `PROCESS-ENTER-KEY`
 * and from there into `WRITE-USER-SEC-FILE` at L153-L160, so Enter validates and writes in the same
 * turn. Nothing here is fetched, so nothing here can be stale.
 *
 * The credential is collected and deliberately not transmitted
 * -----------------------------------------------------------
 * Assumptions: the password control and its `Password can NOT be empty...` refusal are both PRESENT,
 * because both are observable behaviour of this screen -- the mapset paints the control at
 * `app/bms/COUSR01.bms` L126 and the program raises the sentence at L138 -- while the value itself is
 * never submitted, because `CreateUserRequest` declares four members and none of them is a
 * credential. That split is registered as `D-RUNTIME-CREDENTIAL-HANDOVER` in
 * `docs/architecture/cobol-to-service-traceability.md` rather than left as a silent omission: the
 * service mints a one-time credential itself and names the Secrets Manager entry holding it in
 * `CreatedUserResponse.credentialSecretName`, so no credential travels in either direction. The
 * baseline instead stored an eight-character plaintext value at `app/cpy/CSUSR01Y.cpy` L21 and moved
 * the submitted one into the record at L157; the target has no password column at all, which
 * `D-4` records. Neither divergence is a repair of the COBOL -- both are documented, not fixed.
 *
 * Assumptions: the sibling update screen REMOVED its own credential control under registered
 * divergence `D-10`, and that entry does not reach this screen: its `Files` line names
 * `ui/src/screens/userUpdate/index.tsx` alone, it records `app/bms/COUSR01.bms` L126 as unaffected,
 * and it keeps `SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY` in the catalog precisely because
 * `app/cbl/COUSR01C.cbl` L138 still raises it. This screen is that remaining emitter.
 *
 * Refactoring Rationale: the pseudo-conversational session structure is gone, so three of its
 * members are answered from elsewhere. Navigation carried in `CDEMO-TO-PROGRAM` (L94) becomes a
 * client-side route change; the re-entry discriminator `CDEMO-PGM-CONTEXT`
 * (`app/cpy/COCOM01Y.cpy` L29-L31) disappears entirely, which is why the field marks below are
 * driven by the response body alone; and the operator's own authority is the signed
 * `cognito:groups` claim rather than anything this screen renders or submits.
 */

import { Flex, Form, Input, Typography, theme } from 'antd';
import type { InputRef } from 'antd';
import { useEffect, useId, useRef, useState } from 'react';
import type { ChangeEvent, CSSProperties, ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { USER_ID_MAX_LENGTH, createUser } from '../../api/auth';
import { isApiRequestError, isConflictFailure } from '../../api/client';
import type {
  ApiError,
  CreatedUserResponse,
  CreateUserRequest,
  FieldValidationState,
  UserType,
} from '../../api/types';
import { useAuth } from '../../hooks/useAuth';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import { fieldAriaProps, fieldErrorHelp, fieldHintId } from '../../layout/fieldHelp';
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
import type { MapsetName } from '../../messages/messages';
import { SIGN_ON_ROUTE } from '../../routes/guards';
import { ADMIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import { BMS_TEXT_COLOR_TOKENS, FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/** The two sentences this screen's own program owns, from the catalog that owns every string it paints. */
const ADD_MESSAGES = PROGRAM_MESSAGES.COUSR01C;

/** CICS transaction identifier this screen replaces, from `app/cbl/COUSR01C.cbl` L37. */
export const USER_ADD_TRANSACTION_ID = 'CU01';

/** Source program name, rendered in the header band as row 2 of the 3270 screen painted it (L36). */
export const USER_ADD_PROGRAM_NAME = 'COUSR01C';

/**
 * Mapset this screen stands in, which sizes the message band.
 *
 * Assumptions: `MESSAGE_BAND_BY_MAPSET` records `COUSR01` as a 78-character band, which is the
 * `LENGTH=78` its `ERRMSG` field declares at `app/bms/COUSR01.bms` L151-L154 and the width
 * `ERRMSGI PIC X(78)` declares at `app/cpy-bms/COUSR01.CPY` L90. It is passed explicitly rather than
 * left to the band's default -- which happens to be the same 78 -- because the default exists for a
 * caller that does not know its mapset, and this one does.
 */
export const USER_ADD_MAPSET = 'COUSR01' as const satisfies MapsetName;

/**
 * The screen's own caption, verbatim from `app/bms/COUSR01.bms` L75-L79.
 *
 * Assumptions: this is a BODY field and not part of the header band. It is painted at `POS=(4,35)`
 * with `ATTRB=(ASKIP,BRT)` and `COLOR=NEUTRAL` over `LENGTH=9`, below the two 40-character title
 * fields `ScreenHeader` owns on rows 1 and 2, so it belongs to this screen rather than to the shell.
 */
export const USER_ADD_CAPTION = 'Add User';

/**
 * One editable control on this screen.
 *
 * Assumptions: FIVE members where the create contract has four, and the fifth is deliberate.
 * `password` is a control this screen paints and validates but never submits, for the reason recorded
 * in the module header; naming it here is what lets the blank cascade and the renderer treat all five
 * uniformly while the request is assembled from the four the contract declares.
 */
export type UserAddField = 'firstName' | 'lastName' | 'userId' | 'password' | 'userType';

/**
 * The five field labels this screen renders, verbatim from `app/bms/COUSR01.bms`.
 *
 * Assumptions: the trailing space on `userType` is part of the value. The mapset declares
 * `INITIAL='User Type: '` at `LENGTH=11` (L136-L140) where the visible text is ten characters, so the
 * eleventh is a space the terminal painted. The transcription rule for this tree is character-exact,
 * and trimming it here would be a silent edit to a user-visible string.
 */
export const USER_ADD_FIELD_LABELS = {
  /** `app/bms/COUSR01.bms` L80-L83, `COLOR=TURQUOISE`, `LENGTH=11`, `POS=(8,6)`. */
  firstName: 'First Name:',
  /** `app/bms/COUSR01.bms` L92-L96, `COLOR=TURQUOISE`, `LENGTH=10`, `POS=(8,45)`. */
  lastName: 'Last Name:',
  /** `app/bms/COUSR01.bms` L106-L110, `COLOR=TURQUOISE`, `LENGTH=8`, `POS=(11,6)`. */
  userId: 'User ID:',
  /** `app/bms/COUSR01.bms` L121-L125, `COLOR=TURQUOISE`, `LENGTH=9`, `POS=(11,45)`. */
  password: 'Password:',
  /** `app/bms/COUSR01.bms` L136-L140, `COLOR=TURQUOISE`, `LENGTH=11`, `POS=(14,6)`. */
  userType: 'User Type: ',
} as const satisfies Readonly<Record<UserAddField, string>>;

/**
 * The three hints this screen paints beside a control, verbatim and in `COLOR=BLUE`.
 *
 * Assumptions: the two width hints are BOTH carried and they are separate entries even though their
 * text is identical, because the mapset paints two distinct fields -- one after the identifier at
 * L116-L120 and one after the credential at L131-L135. Collapsing them into one shared constant would
 * make a later edit to either silently move both.
 *
 * Assumptions: the user-type hint is the ONLY place the `'A'`/`'U'` domain is advertised to an
 * operator, because `app/cbl/COUSR01C.cbl` contains no domain check for that field -- L142 tests it
 * for blank and nothing else. Dropping the hint would leave the domain undiscoverable.
 */
export const USER_ADD_FIELD_HINTS = {
  /** `app/bms/COUSR01.bms` L116-L120, `COLOR=BLUE`, `LENGTH=8`, `POS=(11,24)`. */
  userId: '(8 Char)',
  /** `app/bms/COUSR01.bms` L131-L135, `COLOR=BLUE`, `LENGTH=8`, `POS=(11,64)`. */
  password: '(8 Char)',
  /** `app/bms/COUSR01.bms` L146-L150, `COLOR=BLUE`, `LENGTH=17`, `POS=(14,19)`. */
  userType: '(A=Admin, U=User)',
} as const;

/*
 * WHY : Assumptions: every width below is the mapset's own `LENGTH=` operand, and each is corroborated
 *       independently by two further sources so no single one is trusted alone. `FNAME` is `LENGTH=20`
 *       at `app/bms/COUSR01.bms` L87, `FNAMEI PIC X(20)` at `app/cpy-bms/COUSR01.CPY` L60 and
 *       `05 SEC-USR-FNAME PIC X(20).` at `app/cpy/CSUSR01Y.cpy` L19; `LNAME` is `LENGTH=20` at L100,
 *       `PIC X(20)` at L66 and L20; `USERID` is `LENGTH=8` at L114, `PIC X(8)` at L72 and
 *       `05 SEC-USR-ID PIC X(08).` at L18; `PASSWD` is `LENGTH=8` at L129, `PIC X(8)` at L78 and L21;
 *       `USRTYPE` is `LENGTH=1` at L144, `PIC X(1)` at L84 and `PIC X(01)` at L22. Those six record
 *       fields sum to the 80-byte layout `CSUSR01Y.cpy` L17-L23 declares.
 *       These are the terminal's own field widths, which is how a 3270 refused a keystroke past the
 *       end of a field, so `maxLength` is the faithful browser equivalent rather than a convenience.
 * WHY : Assumptions: the identifier's width comes from `USER_ID_MAX_LENGTH` rather than the literal 8,
 *       because that constant is published by `ui/src/api/auth.ts` as the contract's own bound and the
 *       service refuses a wider value. Writing 8 here would leave two independent spellings of one
 *       bound, and the mapset's `LENGTH=8` is what the constant already agrees with.
 */
export const USER_ADD_FIELD_WIDTHS = {
  firstName: 20,
  lastName: 20,
  userId: USER_ID_MAX_LENGTH,
  password: 8,
  userType: 1,
} as const satisfies Readonly<Record<UserAddField, number>>;

/** How one control is presented, with every member measured from the mapset's own operands. */
interface UserAddFieldPresentation {
  /** Measured `COLOR=` role of the control's label, resolved through `BMS_TEXT_COLOR_TOKENS`. */
  readonly labelTone: keyof typeof BMS_TEXT_COLOR_TOKENS;
  /** Whether the mapset places the initial cursor here, from its single `IC` operand. */
  readonly initialCursor?: boolean;
  /** Whether the value is an identifier column and takes the fixed-pitch face. */
  readonly fixedPitch?: boolean;
  /** Whether the mapset declares the control non-display, which selects the masked input. */
  readonly nonDisplay?: boolean;
  /** Verbatim hint the mapset paints beside the control, when it paints one. */
  readonly hint?: string;
}

/*
 * WHY : Assumptions: `initialCursor` is set on the first name ALONE, because `app/bms/COUSR01.bms`
 *       carries exactly one `IC` operand -- on `FNAME` at L84 -- and a 3270 map has one initial cursor
 *       position by construction. `app/cbl/COUSR01C.cbl` agrees from the program side and does so four
 *       separate times: it moves `-1` into `FNAMEL` on first entry (L86), on the cascade's fall-through
 *       arm (L149), after a clear (L289) and on the generic write failure (L272). A second `autoFocus`
 *       in a browser is not a second cursor but a race between two controls for the same one.
 * WHY : Assumptions: `HILIGHT=UNDERLINE` on all five controls (L86, L99, L113, L128, L143) resolves to
 *       NO token, and the absence is a decision rather than an oversight. AAP gap G4 records it: the
 *       underline was a 3270's only way to show where a field began, and antd's `Input` carries that
 *       affordance in its own border, so a token here would double the cue rather than translate it.
 * WHY : Assumptions: only the identifier takes the fixed-pitch face. `SEC-USR-ID` is a fixed
 *       eight-character key (`app/cpy/CSUSR01Y.cpy` L18) that a terminal displayed in a monospaced cell
 *       grid, so a proportional face would render two identifiers of equal length at different widths.
 *       The two names and the one-character user type are free text and stay in the body face.
 */
const FIELD_PRESENTATION: Readonly<Record<UserAddField, UserAddFieldPresentation>> = {
  firstName: { labelTone: 'TURQUOISE', initialCursor: true },
  lastName: { labelTone: 'TURQUOISE' },
  userId: { labelTone: 'TURQUOISE', fixedPitch: true, hint: USER_ADD_FIELD_HINTS.userId },
  password: { labelTone: 'TURQUOISE', nonDisplay: true, hint: USER_ADD_FIELD_HINTS.password },
  userType: { labelTone: 'TURQUOISE', hint: USER_ADD_FIELD_HINTS.userType },
};

/*
 * WHY : Assumptions: the four labels reconstruct the mapset's row-24 field exactly. L155-L159 paints
 *       `INITIAL='ENTER=Add User  F3=Back  F4=Clear  F12=Exit'` in a `LENGTH=43` field with TWO spaces
 *       between each pair, and the four labels below are its four segments in order. No unescaping is
 *       applied because this mapset contains no `&&`: BMS source is assembler macro source in which
 *       `&` opens a variable symbol, and `decodeBmsLegendText` exists for the one mapset that needs it.
 * WHY : Refactoring Rationale: only `F4=Clear` is taken from `UNIFORM_PF_KEY_LABELS`. That module
 *       publishes defaults for exactly the three keys whose wording is byte-identical across all 17
 *       measured legends and deliberately publishes none for ENTER, PF3 or PF12, so the other three are
 *       stated here from this mapset's own operand. `ENTER=Add User` in particular is unique to this
 *       screen, and inheriting a generic submit label would mislabel the one control that writes.
 */
export const USER_ADD_KEY_LABELS = {
  ENTER: 'ENTER=Add User',
  PFK03: 'F3=Back',
  PFK04: UNIFORM_PF_KEY_LABELS.PFK04,
  PFK12: 'F12=Exit',
} as const;

/*
 * WHY : Assumptions: this ORDER is observable behaviour and not a list of fields that happens to be
 *       sorted. `app/cbl/COUSR01C.cbl` L117-L151 is an `EVALUATE TRUE` whose arms are tested in
 *       sequence and which stops at the first that matches -- first name L118, last name L124,
 *       identifier L130, credential L136, user type L142 -- so exactly one sentence reaches the message
 *       band per turn and WHICH one is determined by this order. It is the mapset's own reading order
 *       too, down rows 8, 11 and 14 and left to right within each. Validating every control at once, or
 *       testing them in any other order, would change the first sentence an operator sees.
 */
export const BLANK_VALIDATION_ORDER: readonly UserAddField[] = [
  'firstName',
  'lastName',
  'userId',
  'password',
  'userType',
];

/**
 * The sentence each control's blank refusal raises, verbatim from the catalog.
 *
 * Assumptions: all five live in `SHARED_MESSAGES` rather than under this program's own key, because
 * `app/cbl/COUSR02C.cbl` composes the same five sentences at L188, L194, L182, L200 and L206. The
 * catalog is keyed by origin and a group named for one program cannot cite a second, so a sentence two
 * programs emit is filed as shared; the provenance for this screen's sites is recorded there as
 * `COUSR01C` L120, L126, L132, L138 and L144.
 */
export const USER_ADD_BLANK_MESSAGES = {
  firstName: SHARED_MESSAGES.FIRST_NAME_CAN_NOT_BE_EMPTY,
  lastName: SHARED_MESSAGES.LAST_NAME_CAN_NOT_BE_EMPTY,
  userId: SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY,
  password: SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY,
  userType: SHARED_MESSAGES.USER_TYPE_CAN_NOT_BE_EMPTY,
} as const satisfies Readonly<Record<UserAddField, string>>;

/**
 * What the five controls hold between turns.
 *
 * Assumptions: `userType` is typed as the contract's own union widened only by the empty string, not
 * as `string`, and that is what lets the request below be assembled with no cast. Every other control
 * is free text at the mapset's declared width.
 */
interface UserAddValues {
  /** `FNAMEI PIC X(20)`, `app/cpy-bms/COUSR01.CPY` L60. */
  readonly firstName: string;
  /** `LNAMEI PIC X(20)`, `app/cpy-bms/COUSR01.CPY` L66. */
  readonly lastName: string;
  /** `USERIDI PIC X(8)`, `app/cpy-bms/COUSR01.CPY` L72. */
  readonly userId: string;
  /** `PASSWDI PIC X(8)`, `app/cpy-bms/COUSR01.CPY` L78. Never submitted; see the module header. */
  readonly password: string;
  /** `USRTYPEI PIC X(1)`, `app/cpy-bms/COUSR01.CPY` L84, narrowed to the contract's domain. */
  readonly userType: '' | UserType;
}

/** Every control empty, which is the state `INITIALIZE-ALL-FIELDS` leaves at L287-L295. */
const EMPTY_VALUES: UserAddValues = {
  firstName: '',
  lastName: '',
  userId: '',
  password: '',
  userType: '',
};

/** One refusal this screen can render, narrowed to a control it actually paints. */
export interface UserAddFieldError {
  /** Control the refusal names. */
  readonly field: UserAddField;
  /** Which of the baseline's two field conditions applies, which decides the blank marker. */
  readonly state: FieldValidationState;
  /** Verbatim sentence to render beneath the control. */
  readonly message: string;
}

/** What a rejected write puts on the screen: one sentence, any field refusals, and where to focus. */
interface UserAddFailureReport {
  /** Verbatim sentence for the message band. */
  readonly message: string;
  /** Refusals to render beneath the controls they name. */
  readonly fieldErrors: readonly UserAddFieldError[];
  /** Control to move the cursor to, the analogue of the reference's `MOVE -1 TO <field>L`. */
  readonly focus: UserAddField;
}

/**
 * Reports whether a control's value counts as empty by the reference's own test.
 *
 * Assumptions: the reference tests `= SPACES OR LOW-VALUES` (for example L118 and L124), and `SPACES`
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
 * @param {UserAddValues} values - Values the five controls hold.
 * @param {readonly UserAddField[]} order - Controls to test, in the order the reference tests them.
 * @returns {UserAddField | null} The first blank control, or `null` when every one carries a value.
 */
export function firstBlankField(
  values: UserAddValues,
  order: readonly UserAddField[],
): UserAddField | null {
  for (const field of order) {
    if (isBlankFieldValue(values[field])) {
      return field;
    }
  }

  return null;
}

/*
 * WHY : Assumptions: the control admits only the two domain characters, and this is NOT an invented
 *       validation. `app/cbl/COUSR01C.cbl` has no domain arm for the user type at all -- L142 tests it
 *       for blank and the program then writes whatever single character it holds at L158 -- but the
 *       migrated schema declares `CHECK (user_type IN ('A','U'))` on `auth.users` and
 *       `CreateUserRequest.userType` is the `'A' | 'U'` union, so the target cannot carry any other
 *       value end to end. Refusing the keystroke is the same affordance `maxLength` already provides on
 *       this control: a 3270 refused a character past the end of a field with no message, and this
 *       refuses a character outside the domain the label beside it advertises, also with no message. No
 *       client-side REFUSAL SENTENCE is invented -- the only sentence this control can raise is the
 *       catalogued blank one, exactly as the reference has it.
 *       Alternatives Considered: submitting an out-of-domain character so the service refuses it and
 *       supplies the sentence, which would keep the server-side domain check reachable from a browser.
 *       Rejected on two independent grounds: it needs an unsafe cast past the union, which this tree
 *       does not permit, and the service's wording is not in `ui/src/messages/messages.ts`, which owns
 *       every sentence this screen may paint -- so the screen would render text no catalog entry
 *       accounts for. The service's refusal path is still reachable in code: `resolveApiFieldErrors`
 *       below renders a `userType` refusal if the service ever raises one.
 *       Alternatives Considered: a `Select` or a `Radio.Group` over the union, which the design-system
 *       mapping permits and which would make an out-of-domain value impossible to express. Rejected
 *       because neither has a character width, and the mapset's `LENGTH=1` at L144 with `PIC X(01)` at
 *       `app/cpy/CSUSR01Y.cpy` L22 is a field-width contract this tree preserves on every control.
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
 * Narrows an arbitrary problem-document field name to a control this screen paints.
 * @param {string} candidate - Field name as the response spelled it.
 * @returns {boolean} `true` when the name is one of this screen's five controls.
 */
function isUserAddField(candidate: string): candidate is UserAddField {
  return (BLANK_VALIDATION_ORDER as readonly string[]).includes(candidate);
}

/**
 * Maps a problem document's per-field entries onto the controls this screen renders.
 *
 * Assumptions: entries naming something this screen does not paint are DROPPED rather than rendered
 * loose, because there is no control to attach them to. Nothing is lost silently -- the document's own
 * sentence still reaches the message band through {@link describeCreateFailure}.
 *
 * Assumptions: `password` is among the names this accepts even though no response can name it, because
 * the create contract declares no such property for a validator to refuse. Admitting it costs one array
 * entry in a list that is already the screen's control set, and excluding it would make the accepted
 * names disagree with the controls the renderer can mark.
 *
 * Trade-offs: the array is kept whole even though the reference raises at most one refusal per turn,
 * because the service accumulates its violations in a single pass and can legitimately name several
 * controls. Discarding all but the first would hide refusals an administrator has to fix; the band
 * still carries exactly one sentence, which is the reference's observable behaviour.
 * @param {ApiError} problem - The normalised problem document a rejected request carried.
 * @returns {readonly UserAddFieldError[]} One entry per refusal this screen can render, in the order
 *   the service listed them.
 */
export function resolveApiFieldErrors(problem: ApiError): readonly UserAddFieldError[] {
  const resolved: UserAddFieldError[] = [];

  for (const entry of problem.fieldErrors) {
    if (isUserAddField(entry.field)) {
      resolved.push({ field: entry.field, state: entry.state, message: entry.message });
    }
  }

  return resolved;
}

/**
 * Reduces a rejected write to the one sentence, the field marks and the cursor move it produces.
 *
 * Assumptions: the duplicate-identifier outcome is selected by the transport status through
 * `isConflictFailure` and not by comparing `status` with 409 here. The reference reaches the same state
 * from two CICS response codes that fall into ONE handler -- `WHEN DFHRESP(DUPKEY)` at L260 and
 * `WHEN DFHRESP(DUPREC)` at L261 -- and answers `User ID already exist...` at L263 with the cursor on
 * the identifier at L265. Leaking a raw status, or letting a duplicate fall through to the generic
 * sentence, would break a message contract the reference states in one place.
 *
 * Trade-offs: the sentence is reproduced with its missing `'s'`. `User ID already exist...` is what
 * L263 holds, and restoring the letter would be a silent edit to an externally observable string, so
 * the reading error is carried across verbatim -- documented, not fixed.
 *
 * Assumptions: a refusal naming a control takes precedence over the generic sentence, and this is the
 * one outcome with no reference site because the reference has no 400 path -- it validates every field
 * itself before touching the file. The service validates independently and can refuse a value this
 * screen let through, so its sentence is shown for the control it names, which is the same shape the
 * reference produces for its own refusals: one sentence, about the first offending field, with the
 * cursor on it.
 * @param {unknown} failure - Whatever the request rejected with, normally the normalised
 *   `ApiRequestError` that `ui/src/api/client.ts` mints.
 * @returns {UserAddFailureReport} The sentence to band, the refusals to mark, and the control to focus.
 */
export function describeCreateFailure(failure: unknown): UserAddFailureReport {
  /*
   * WHY : Assumptions: a failure that is not the normalised error is still reported through the
   *       reference's own sentence rather than surfaced raw. `ui/src/api/client.ts` normalises every
   *       transport outcome it produces -- a problem document, a non-JSON gateway body, a timeout and a
   *       network fault alike -- so reaching this arm means the rejection came from somewhere else
   *       entirely, and the reference's `WHEN OTHER` arm at L267-L273 is exactly the catch-all for a
   *       file operation that failed for a reason it could not name.
   */
  if (!isApiRequestError(failure)) {
    return { message: ADD_MESSAGES.UNABLE_TO_ADD_USER, fieldErrors: [], focus: 'firstName' };
  }

  const fieldErrors = resolveApiFieldErrors(failure.problem);

  if (isConflictFailure(failure)) {
    /*
     * WHY : Assumptions: the identifier is MARKED as well as focused, and the mark is synthesised here
     *       when the response carries none. A 409 is a refusal about one field, and the reference says
     *       so twice over in the same arm -- it moves the sentence at L263 AND moves `-1` into
     *       `USERIDL` at L265, which on a 3270 is how a program points at the offending field. A
     *       problem document for a duplicate key legitimately arrives with an empty `fieldErrors`
     *       array, because the collision is detected by a unique constraint rather than by field
     *       validation, so relying on the response alone would move the cursor to a control that
     *       carried no visible indication of why it was refused.
     * WHY : Assumptions: the synthesised state is `NOT_OK` and never `BLANK`. `app/cpy/CSSETATY.cpy`
     *       distinguishes the two: both take the error colour (L18-L22) but only the blank case also
     *       receives the literal asterisk (L23-L25). A duplicate identifier is present and wrong
     *       rather than absent, so it takes the colour and the sentence without the marker.
     * WHY : Assumptions: a mark the SERVICE supplied for the identifier is preferred over the
     *       synthesised one, so a service that words the collision more precisely is not overwritten.
     *       The synthesis fills a gap; it does not override a statement.
     */
    const identifierMarked = fieldErrors.some(
      /**
       * Reports whether a refusal already names the identifier control.
       * @param {UserAddFieldError} entry - One refusal the response carried.
       * @returns {boolean} `true` when the refusal names the identifier.
       */
      (entry: UserAddFieldError): boolean => entry.field === 'userId',
    );

    return {
      message: ADD_MESSAGES.USER_ID_ALREADY_EXIST,
      fieldErrors: identifierMarked
        ? fieldErrors
        : [
            ...fieldErrors,
            {
              field: 'userId',
              state: 'NOT_OK',
              message: ADD_MESSAGES.USER_ID_ALREADY_EXIST,
            },
          ],
      focus: 'userId',
    };
  }

  const firstRefusal = fieldErrors[0];

  if (firstRefusal !== undefined) {
    return { message: firstRefusal.message, fieldErrors, focus: firstRefusal.field };
  }

  return { message: ADD_MESSAGES.UNABLE_TO_ADD_USER, fieldErrors, focus: 'firstName' };
}

/**
 * Composes the stored-write acknowledgement the reference builds with a `STRING` statement.
 *
 * Assumptions: the sentence is assembled from the catalogued template rather than concatenated here, so
 * its three parts stay the reference's own. `app/cbl/COUSR01C.cbl` L255-L258 writes
 * `STRING 'User ' DELIMITED BY SIZE, SEC-USR-ID DELIMITED BY SPACE, ' has been added ...'
 * DELIMITED BY SIZE`, which means the identifier is truncated at its FIRST BLANK and the closing
 * fragment carries both a leading space and a space before its ellipsis. `formatMessageTemplate`
 * implements `DELIMITED BY SPACE` as exactly that truncation, so the composition is performed by the
 * catalog and not restated.
 *
 * Assumptions: the identifier comes from the RESPONSE and not from the control the operator typed,
 * because the service canonicalises the key -- registered divergence `D-USER-ID-CANONICAL-DOMAIN`
 * trims it, folds it to upper case, and refuses a value containing a space. Naming the stored key means
 * the acknowledgement names the row that now exists. That divergence cites this very statement as its
 * reason for excluding the space: an identifier containing one would be rendered here truncated at the
 * blank, which is a different identifier from the one stored.
 * @param {string} storedUserId - The identifier the create response returned, already canonical.
 * @returns {string} The acknowledgement, byte-identical to the sentence the reference composes.
 * @throws {Error} If the catalogued template names a value this call does not supply, which
 *   `formatMessageTemplate` raises rather than substituting an empty string.
 */
export function composeAddedMessage(storedUserId: string): string {
  return formatMessageTemplate(MESSAGE_TEMPLATES.USER_HAS_BEEN_ADDED, {
    'SEC-USR-ID': storedUserId,
  });
}

/**
 * Renders the add-user form and writes the administered user its five controls describe.
 *
 * Assumptions: this component performs NO authorization check of its own. `/users/new` is declared
 * inside the administrative subtree in `ui/src/router.tsx`, which gates that whole subtree on the
 * signed `carddemo-admin` claim and answers a refused operator with the catalogued
 * `No access - Admin Only option... ` sentence. A second check here would fork one authorization
 * decision across two modules that could then disagree, and the one in the router is the one that can
 * refuse before this screen mounts at all. `useAuth` is read below for an AFFORDANCE only -- the key
 * that leaves the application -- and never as a gate.
 * @returns {ReactElement} The screen's caption and its five labelled controls. The title band, the
 *   row-23 message line and the row-24 key legend are delegated to the shell rather than rendered here.
 */
export function UserAddScreen(): ReactElement {
  /*
   * WHY : Assumptions: `cssVar` rather than `token` from the same hook. `token` returns RESOLVED values
   *       captured at render time, so a theme change would repaint the provider and leave a value this
   *       screen had already inlined behind. `cssVar` returns the `var(--...)` reference form, which the
   *       browser re-resolves, so the design values below stay live and stay token references rather
   *       than becoming literals -- which is the property AAP section 0.3.2's zero-hardcoded-values rule
   *       actually asks for.
   */
  const { cssVar } = theme.useToken();
  const navigate = useNavigate();
  const { signOut } = useAuth();
  const paintedAt = useServerInstant();
  /*
   * WHY : Assumptions: control identifiers are derived from a GENERATED prefix rather than written as
   *       fixed strings, because each label is bound to its control by `htmlFor` and a document may not
   *       carry the same identifier twice. A fixed string would collide if this screen were ever
   *       rendered twice in one document, leaving a label pointing at whichever control appeared first.
   */
  const idPrefix = useId();
  const controls = useRef<Partial<Record<UserAddField, InputRef | null>>>({});

  const [values, setValues] = useState<UserAddValues>(EMPTY_VALUES);
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');
  const [fieldErrors, setFieldErrors] = useState<readonly UserAddFieldError[]>([]);
  const [busy, setBusy] = useState(false);
  /*
   * WHY : Refactoring Rationale: the cursor destination is held as STATE and applied by the effect
   *       below rather than by calling `.focus()` where the refusal is decided. An inline call is the
   *       obvious shape and loses the cursor twice over, both times because it would run before React
   *       commits the render the refusal causes. First, the blank marker is an antd `Input` `suffix`,
   *       and adding one rewraps the control so a NEW `input` element is mounted -- focus applied to the
   *       old node is discarded and the cursor falls back to the document body. Second, every control is
   *       `disabled` while the write is in flight, and a browser refuses focus on a disabled element
   *       silently, with no error to notice. This is the mechanism that makes the reference's eight
   *       `MOVE -1 TO <field>L` sites actually observable in a browser.
   *       Trade-offs: one extra render per cursor move, which is the price of the cursor landing where
   *       the reference puts it. Alternatives Considered: a timer or an animation frame, both of which
   *       also outlive the commit but leave the cursor depending on elapsed time rather than on the
   *       render that caused it, so a slow commit would reintroduce the fault non-deterministically.
   */
  const [pendingFocus, setPendingFocus] = useState<UserAddField | null>(null);

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

  /**
   * Records a mounted control so a cursor move can reach it, or forgets it on unmount.
   * @param {UserAddField} field - Control the callback ref belongs to.
   * @returns {(control: InputRef | null) => void} Callback ref that records the control.
   */
  function registerControl(field: UserAddField): (control: InputRef | null) => void {
    /**
     * Records the mounted control, or forgets it on unmount.
     * @param {InputRef | null} control - The control, or `null` while it is being detached.
     * @returns {void} Completion is the updated lookup.
     */
    return (control: InputRef | null): void => {
      controls.current[field] = control;
    };
  }

  /**
   * Builds the change handler for one control.
   *
   * Assumptions: the user type is normalised on the way IN rather than on the way out, so the control
   * can never hold a value outside the contract's domain and the request needs no cast. Every other
   * control stores what was typed, bounded only by its `maxLength`.
   * @param {UserAddField} field - Control whose value the handler records.
   * @returns {(event: ChangeEvent<HTMLInputElement>) => void} Handler for that control's change event.
   */
  function changeHandler(field: UserAddField): (event: ChangeEvent<HTMLInputElement>) => void {
    /**
     * Records one control's new value.
     * @param {ChangeEvent<HTMLInputElement>} event - Change event the control raised.
     * @returns {void} Completion is the updated value state.
     */
    return (event: ChangeEvent<HTMLInputElement>): void => {
      const typed = event.target.value;

      setValues(
        /**
         * Replaces the one control's value, leaving the other four as they were.
         * @param {UserAddValues} current - Values before this keystroke.
         * @returns {UserAddValues} Values with this control updated.
         */
        (current: UserAddValues): UserAddValues => ({
          ...current,
          [field]: field === 'userType' ? normaliseUserType(typed) : typed,
        }),
      );
    };
  }

  /**
   * Blanks every control, the field marks and the message, which is the reference's PF4 arm.
   *
   * Assumptions: all FIVE controls are blanked and the message with them. `INITIALIZE-ALL-FIELDS` at
   * L287-L295 moves `SPACES` into the identifier, the first name, the last name, the credential, the
   * user type and `WS-MESSAGE` in one statement, and L289 places the cursor back on the first name.
   * `CLEAR-CURRENT-SCREEN` at L279-L282 performs exactly that paragraph and then re-sends the map, so
   * PF4 clears the message as well -- which is what distinguishes it from the success path below, where
   * the same paragraph runs and the message is then REPLACED by the acknowledgement.
   *
   * Assumptions: the field marks go with the values, because a mark left beneath a control the operator
   * has just emptied would name a refusal of a value that is no longer there.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function clearScreen(): void {
    setValues(EMPTY_VALUES);
    setFieldErrors([]);
    setMessage(null);
    setSeverity('error');
    setPendingFocus('firstName');
  }

  /**
   * Refuses one blank control with its catalogued sentence, marking the control and moving the cursor.
   *
   * Assumptions: the refusal is recorded with the `BLANK` state, which is what selects the literal
   * asterisk in the renderer. `app/cpy/CSSETATY.cpy` L17-L27 moves the error colour into a field when
   * its validation flag is not-OK OR blank, and moves a literal `'*'` into the field only in the blank
   * case (L23-L25), so a not-OK-but-non-blank refusal takes the colour and no marker.
   * @param {UserAddField} field - The blank control the cascade stopped on.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function refuseBlankField(field: UserAddField): void {
    setMessage(USER_ADD_BLANK_MESSAGES[field]);
    setSeverity('error');
    setFieldErrors([{ field, state: 'BLANK', message: USER_ADD_BLANK_MESSAGES[field] }]);
    setPendingFocus(field);
  }

  /**
   * Validates the five controls and, when every one carries a value, writes the new user.
   *
   * Assumptions: the write happens only after the cascade passes, which is the reference's own
   * sequencing -- `PROCESS-ENTER-KEY` runs the `EVALUATE TRUE` at L117-L151 and then guards the write
   * with `IF NOT ERR-FLG-ON` at L153, so a refused turn never reaches the file.
   * @returns {void} Completion is represented by the screen's own state; every rejection of the request
   *   is reduced to a band sentence and field marks by {@link describeCreateFailure}, so no rejection
   *   escapes this handler.
   */
  function handleAdd(): void {
    /*
     * WHY : Assumptions: a second Enter arriving while a write is in flight is ignored. The reference
     *       needs no such guard -- `app/cbl/COUSR01C.cbl` processes one terminal turn at a time, so a
     *       second Enter could not arrive while the first was still running its arm -- and a browser
     *       offers no such serialisation, so without this a double activation would issue two creates
     *       and the second would answer `User ID already exist...` for the row the first had just
     *       written.
     */
    if (busy) {
      return;
    }

    const { userType } = values;
    const blankField = firstBlankField(values, BLANK_VALIDATION_ORDER);

    /*
     * WHY : Assumptions: the user-type emptiness is restated in this condition purely so the compiler
     *       can narrow it from `'' | UserType` to the union the request needs. It is not a second check:
     *       an empty user type always makes `firstBlankField` return `'userType'`, because that control
     *       is the fifth entry of the cascade order and an empty string is blank by
     *       `isBlankFieldValue`. The refusal is therefore raised by the cascade, and this clause exists
     *       only so the narrowing is provable rather than asserted with a cast.
     */
    if (blankField !== null || userType === '') {
      refuseBlankField(blankField ?? 'userType');
      return;
    }

    /*
     * WHY : Assumptions: the request carries EXACTLY the four properties `CreateUserRequest` declares,
     *       and the credential this screen collected is not among them. The contract publishes
     *       `firstName`, `lastName`, `userId` and `userType` and no credential, because the service
     *       provisions the account and mints its one-time password itself -- registered divergence
     *       `D-RUNTIME-CREDENTIAL-HANDOVER`, which returns the NAME of the Secrets Manager entry in
     *       `CreatedUserResponse.credentialSecretName` rather than any value. `ui/tsconfig.json` sets
     *       `exactOptionalPropertyTypes` and the project is `strict`, so an excess property on an object
     *       literal of this type is a compile error: the omission is mechanically enforced here and not
     *       a matter of care.
     * WHY : Refactoring Rationale: the collected credential is used for the blank refusal and for
     *       nothing else. The baseline moved it into the record at L157, where
     *       `05 SEC-USR-PWD PIC X(08).` at `app/cpy/CSUSR01Y.cpy` L21 stored it in plain text; the
     *       target has no password column at all, which registered divergence `D-4` records. So the
     *       value is never transmitted, never logged, never written to storage of any kind, never placed
     *       in a path, query string or route parameter, and never echoed back into the control -- no
     *       response type carries one to echo. It lives only in this component's state and is discarded
     *       when the screen unmounts or the success path clears it. This is a documented divergence, not
     *       a repair of the COBOL.
     * WHY : Assumptions: the three text values are trimmed and the user type is not. The reference
     *       compares equally space-padded fixed-width fields, so leading and trailing blanks are not
     *       data; the user type is a single character already narrowed to the domain, so there is
     *       nothing to trim.
     * WHY : Assumptions: no subject identifier is supplied, and no control is added to collect one. The
     *       mapset has no such field among its twelve named ones, so there is nothing on this screen to
     *       carry it, and `CreateUserRequest` does not declare one either: `cognitoSub` is published on
     *       `UserResponse` as an OUTPUT, minted by the identity provider when the account is
     *       provisioned. `ui/src/api/types.ts` states the reason on both shapes -- a creation request
     *       that supplied a subject "would be asserting an identity it cannot have created" -- which is
     *       why that type declares four members and the response declares five.
     *       Trade-offs: the SERVICE contract was read as listing a subject among the create properties,
     *       and this screen follows the client-facing declaration instead, which `ui/src/api/types.ts`
     *       establishes as authoritative when the two disagree. The alternative was to add a control or
     *       to mint a value here; both were rejected, because an invented identifier is one the provider
     *       would then have to either honour or contradict, and neither the declared type nor the
     *       compiler would accept the property at all.
     */
    const request: CreateUserRequest = {
      firstName: values.firstName.trim(),
      lastName: values.lastName.trim(),
      userId: values.userId.trim(),
      userType,
    };

    setBusy(true);
    setMessage(null);
    setFieldErrors([]);

    createUser(request).then(
      /*
       * WHY : Assumptions: the parameter is typed as the contract's whole `CreatedUserResponse` and only
       *       its identifier is read. The response additionally carries `credentialSecretName`, the NAME
       *       of the Secrets Manager entry holding the account's one-time credential -- registered
       *       divergence `D-RUNTIME-CREDENTIAL-HANDOVER`. That locator is deliberately NOT rendered,
       *       logged or stored here: the reference's acknowledgement at L255-L258 names the identifier
       *       and nothing else, and putting a credential locator on the glass would disclose through the
       *       screen what the contract took care to keep out of the response body's value.
       */
      /**
       * Acknowledges a stored write, clears the form and returns the cursor to the first control.
       * @param {CreatedUserResponse} created - The created user as the service stored it, whose
       *   `userId` is the canonical key the acknowledgement names.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (created: CreatedUserResponse): void => {
        setBusy(false);
        /*
         * WHY : Assumptions: the success path clears all five controls AND replaces the message, which
         *       is two effects from two different statements. `WHEN DFHRESP(NORMAL)` at L251 performs
         *       `INITIALIZE-ALL-FIELDS` first (L252), which blanks the five controls and `WS-MESSAGE`
         *       and homes the cursor to the first name, and only then composes the acknowledgement into
         *       `WS-MESSAGE` (L255-L258). So the message is not cleared on this path as it is on PF4 --
         *       it is overwritten -- and the ORDER is what makes that observable.
         * WHY : Assumptions: the band is switched to the success severity because the reference moves
         *       `DFHGREEN` into `ERRMSGC` at L254, the one place this screen paints its message line in
         *       anything but the `COLOR=RED` its `ERRMSG` field declares at L152. Leaving it on the
         *       refusal colour would tell an operator a committed write had failed.
         */
        setValues(EMPTY_VALUES);
        setFieldErrors([]);
        setMessage(composeAddedMessage(created.userId));
        setSeverity('success');
        setPendingFocus('firstName');
      },
      /**
       * Reports a refused write with the reference's own sentence, retaining what the operator typed.
       * @param {unknown} failure - Whatever the request rejected with.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (failure: unknown): void => {
        setBusy(false);
        /*
         * WHY : Assumptions: the five controls are RETAINED on every failure, and this asymmetry with
         *       the success path is the reference's own. Neither failing arm performs
         *       `INITIALIZE-ALL-FIELDS`: the duplicate arm at L262-L266 and the catch-all at
         *       L269-L273 each set the error flag, move a sentence and reposition the cursor, then
         *       re-send the map with the operator's values still in it. Clearing here would discard four
         *       correct values because the fifth collided.
         */
        const report = describeCreateFailure(failure);

        setMessage(report.message);
        setSeverity('error');
        setFieldErrors(report.fieldErrors);
        setPendingFocus(report.focus);
      },
    );
  }

  /**
   * Returns to the administrative menu, which is the reference's PF3 arm.
   *
   * Assumptions: the destination is the ADMINISTRATIVE menu and not the main one, because
   * `app/cbl/COUSR01C.cbl` L93-L95 moves `'COADM01C'` into `CDEMO-TO-PROGRAM` and transfers control
   * there. `COADM01C` is the administrative menu program, so `/menu` would be the wrong screen even
   * though it is where most PF3 arms in this application land.
   * @returns {void} Completion is the route change.
   */
  function exitToAdminMenu(): void {
    navigateSafely(navigate, ADMIN_MENU_ROUTE);
  }

  /**
   * Leaves the application, which is what this screen's row-24 legend advertises for PF12.
   *
   * Assumptions: PF12 is bound at all only because the mapset advertises it. The legend field at
   * `app/bms/COUSR01.bms` L155-L159 paints `F12=Exit`, but `app/cbl/COUSR01C.cbl`'s `EVALUATE EIBAID`
   * at L90-L103 has arms for `DFHENTER`, `DFHPF3` and `DFHPF4` only -- there is no `DFHPF12` arm -- so
   * the reference answers the key its own legend advertises with `Invalid key pressed...` from the
   * `WHEN OTHER` arm at L98-L102. The AAP resolves the contradiction in favour of the advertised
   * legend, and the resolution is a documented divergence for
   * `docs/architecture/cobol-to-service-traceability.md` rather than a repair of the COBOL: a key
   * printed on the glass that answers "invalid key" is the behaviour being diverged from, and it is
   * recorded, not fixed.
   *
   * Assumptions: "Exit" is realised as a sign-off, matching `ui/src/screens/admin/index.tsx`, which
   * discards the session and returns to sign-on for the same semantic. The local credential clear is
   * unconditional and immediate; the route change follows it.
   * @returns {void} Completion is the discarded session and the route change.
   */
  function signOffAndExit(): void {
    signOut();
    navigateSafely(navigate, SIGN_ON_ROUTE);
  }

  /*
   * WHY : Assumptions: FOUR keys are registered and PF5 is deliberately absent. The row-24 legend at
   *       L159 advertises exactly `ENTER=Add User  F3=Back  F4=Clear  F12=Exit`, and
   *       `app/cbl/COUSR01C.cbl`'s dispatch has no PF5 arm, so this screen commits on Enter. The
   *       sibling user screens DO bind PF5 -- to save and to delete -- which is precisely why its
   *       absence here has to be deliberate: inheriting a save key from them would give this screen a
   *       second, unadvertised way to write.
   * WHY : Assumptions: no key carries an `action` override, because `DEFAULT_PF_KEY_ACTIONS` already
   *       maps all four to the semantics this screen uses -- ENTER to `submit`, PF3 to `back`, PF4 to
   *       `clear` and PF12 to `cancel`. Button emphasis follows from the AID through
   *       `PRIMARY_ACTION_AIDS`, which lists ENTER and PF5, so Enter renders as the primary control and
   *       PF3, PF4 and PF12 render as default ones exactly as the design-system mapping requires,
   *       without this screen restating either decision.
   * WHY : Assumptions: only ENTER carries `disabled` while the write is in flight, so the legend greys
   *       out the one key that would start a second write. PF3, PF4 and PF12 stay enabled throughout:
   *       PF4 aborts the turn rather than competing with it and the other two leave the screen, and
   *       greying those would trap an operator on a screen whose only unresponsive keys were the ones
   *       offering a way off it. The in-handler `busy` guard is kept as well rather than replaced,
   *       because it is what a test can exercise deterministically.
   */
  const keyHandlers: PfKeyHandlerMap = {
    ENTER: { onInvoke: handleAdd, label: USER_ADD_KEY_LABELS.ENTER, disabled: busy },
    PFK03: { onInvoke: exitToAdminMenu, label: USER_ADD_KEY_LABELS.PFK03 },
    PFK04: { onInvoke: clearScreen, label: USER_ADD_KEY_LABELS.PFK04 },
    PFK12: { onInvoke: signOffAndExit, label: USER_ADD_KEY_LABELS.PFK12 },
  };

  const { bindings, invoke } = usePfKeys(keyHandlers, {
    /*
     * WHY : Alternatives Considered: `usePfKeys` publishes a `restoreFocusRef` option for exactly this
     *       arm, and it is deliberately NOT used even though this screen's invalid-key arm DOES move the
     *       cursor -- `app/cbl/COUSR01C.cbl` L100 moves `-1` into `FNAMEL` before sending the map. The
     *       option takes a ref to an `HTMLElement`, while every control here is registered as an antd
     *       `InputRef`, so supplying it would mean maintaining a second, parallel ref graph over the
     *       same five controls. Routing this move through `pendingFocus` instead keeps ONE focus
     *       mechanism in the file, and it is the mechanism that survives the control being rewrapped by
     *       a blank marker or disabled by a write in flight, which a direct element ref does not.
     */
    /**
     * Reports a key the screen does not bind, using the reference's own invalid-key sentence.
     * @param {PfKeyRejection} rejection - Why the key was refused, and by which attention identifier.
     * @returns {void} Completion is represented by the screen's own state.
     */
    onInvalidKey: (rejection: PfKeyRejection): void => {
      /*
       * WHY : Assumptions: a key refused because it is momentarily DISABLED is silent, and only an
       *       UNMAPPED one raises the sentence. The reference's `WHEN OTHER` arm answers for keys
       *       outside its list of three; Enter, which this screen greys out for the duration of one
       *       write, is not outside that list, so answering `Invalid key pressed...` for it would tell
       *       an operator the screen does not offer a key whose own legend is on the glass in front of
       *       them.
       */
      if (rejection.reason === 'disabled') {
        return;
      }

      /*
       * WHY : Assumptions: the sentence is the shared `INVALID_KEY_PRESSED` constant, which is the same
       *       `CCDA-MSG-INVALID-KEY` value the reference moves at L101 -- declared once at
       *       `app/cpy/CSMSG01Y.cpy` L20-L21 and already published by the hook on its rejection
       *       payload. It is read from the catalog rather than from that payload so the screen has one
       *       source for every sentence it paints.
       * WHY : Assumptions: the cursor goes to the first name, because the reference's `WHEN OTHER` arm
       *       moves `-1` into `FNAMEL` at L100 before it sends the map. Unlike the sibling update
       *       screen, whose equivalent arm repositions nothing, this one does.
       */
      setMessage(INVALID_KEY_PRESSED);
      setSeverity('error');
      setPendingFocus('firstName');
    },
  });

  /*
   * WHY : Assumptions: all three persistent zones are delegated to the shell -- the title band, the
   *       row-23 message line and the row-24 legend -- and this screen composes NONE of them.
   *       `ui/src/layout/AppShell.tsx` is mounted as the layout route above every screen and already
   *       renders a `MessageBand` and a `PfKeyBar`, painting each only for a screen that published one.
   *       Publishing here is therefore what gets them painted exactly once.
   *       Alternatives Considered: composing `MessageBand` and `PfKeyBar` inside this screen's own
   *       content region, which is what four older screens in this tree still do. Rejected because the
   *       shell renders its pair unconditionally once a slot is published, so a screen that did both
   *       would put TWO live regions on the document announcing one sentence and two `message-band`
   *       test handles where a caller expects one. Delegation is the only shape that keeps the
   *       one-band, one-legend invariant true by construction rather than by convention.
   * WHY : Assumptions: `severity` travels with the text because this one channel carries the
   *       stored-write acknowledgement as well as every refusal, and painting an acknowledgement in the
   *       refusal colour would contradict the `DFHGREEN` the reference moves at L254.
   * WHY : Assumptions: no `legendColor` is delegated, because `app/bms/COUSR01.bms` L156 paints this
   *       screen's row-24 field `COLOR=YELLOW`, which is the slot's own default -- stating it would add
   *       a value that changes nothing.
   * WHY : Assumptions: delegating `pfKeys` hands the shell the bindings to RENDER and leaves this
   *       screen owning the keyboard. `bindings` and `invoke` both come from this screen's own
   *       `usePfKeys` call and travel up unchanged, so an activation of a rendered legend control is
   *       forwarded straight back to the same dispatcher a real key press reaches, and a clicked
   *       control and its key cannot diverge.
   */
  useShellSlot({
    screen: { transactionId: USER_ADD_TRANSACTION_ID, programName: USER_ADD_PROGRAM_NAME },
    now: paintedAt,
    message: { text: message, severity, mapset: USER_ADD_MAPSET },
    pfKeys: {
      keys: bindings,
      onInvoke: invoke,
    },
  });

  /*
   * WHY : Assumptions: every design value below is a TOKEN NAME resolved through `cssVar`, so this
   *       screen carries no colour, weight, spacing or font literal. The colour roles are the mapset's
   *       own measured attributes, mapped by `BMS_TEXT_COLOR_TOKENS`: `COLOR=NEUTRAL` on the caption
   *       (L76) resolves to `colorTextSecondary`, `COLOR=TURQUOISE` on all five labels (L80, L93, L107,
   *       L122 and L137) to `colorTextLabel`, and `COLOR=BLUE` on the three hints (L117, L132 and L147)
   *       to `colorPrimaryTextActive`. `ATTRB=BRT` on the caption (L75) is carried by
   *       `fontWeightStrong` rather than by a colour, which is what keeps brightness and colour
   *       independent in the target the way the mapset has them independent.
   */
  const captionStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };
  const hintStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.BLUE] };
  const blankMarkerStyle: CSSProperties = { color: cssVar[FIELD_ERROR_TOKENS.errorColor] };
  const fixedPitchStyle: CSSProperties = { fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData] };

  /**
   * Renders one labelled control with the mapset's colour, width and hint, plus any refusal it carries.
   *
   * Assumptions: the label is bound to its control by `htmlFor` and a generated identifier rather than
   * by nesting, so assistive technology announces the mapset's own label text when the control takes
   * focus. The 3270 original was keyboard-only, so this is fidelity rather than an addition: a label
   * that was merely adjacent on a character grid has to be associated explicitly in a browser.
   *
   * Assumptions: the refusal sentence and the width hint are ASSOCIATED with the control as well as
   * rendered beside it, through the shared `ui/src/layout/fieldHelp.tsx` helpers. Passing the refusal to
   * `Form.Item` as a bare string would let the design system render it in a container nothing
   * references, leaving the control with no `aria-invalid` and no `aria-describedby` -- so an operator
   * using a screen reader would meet a marked field whose reason is announced only if they happen to
   * move past it.
   * @param {UserAddField} field - Control to render.
   * @returns {ReactElement} The form item, its control, and the marker and help text a refusal adds.
   */
  function renderField(field: UserAddField): ReactElement {
    const presentation = FIELD_PRESENTATION[field];
    const refusal = fieldErrors.find(
      /**
       * Selects the refusal naming this control, if the last turn produced one.
       * @param {UserAddFieldError} entry - One refusal from the last turn.
       * @returns {boolean} `true` when the refusal names this control.
       */
      (entry: UserAddFieldError): boolean => entry.field === field,
    );
    const controlId = `${idPrefix}${field}`;
    /*
     * WHY : Assumptions: the marker is hidden from assistive technology, because it duplicates
     *       information the help text already carries in words. `app/cpy/CSSETATY.cpy` L23-L25 writes
     *       the literal asterisk into a blank field as the terminal's only way to point at one, and the
     *       browser points at it with the form item's error state and its sentence; announcing a bare
     *       asterisk after that sentence would add noise, not information. It is rendered ONLY for the
     *       blank condition and never for a non-blank refusal, which is the distinction that copybook
     *       draws between its two arms -- the colour applies to both, the asterisk to the blank one
     *       alone.
     */
    /*
     * WHY : Assumptions: `autoComplete` is additive -- a terminal had no concept of a credential
     *       manager -- and it is applied because it is INVISIBLE accessibility in the sense
     *       `ui/src/screens/signon/index.tsx` L1333-L1339 already establishes for the same attribute:
     *       it changes no rendered pixel, so it cannot conflict with the mapset, and without it a
     *       browser emits a DOM advisory naming its absence on the credential control.
     *       Refactoring Rationale: the value is DERIVED from `nonDisplay` rather than declared per
     *       field on `FIELD_PRESENTATION`, because there is ONE decision here and not five. Every
     *       control on this screen collects a THIRD PARTY's data -- an administrator is creating
     *       somebody else's account -- so no control may receive the operator's own stored values, and
     *       `off` is that instruction for the two names, the identifier and the type. Declaring it five
     *       times would let a later edit set four of them and miss the fifth.
     * WHY : Assumptions: the credential control takes `new-password` and NOT `off` or
     *       `current-password`, which is the value `signon` L1453-L1458 selects for its replacement
     *       credential and for the reason recorded there: `new-password` is what stops a manager
     *       filling the OPERATOR's own current password into a box asking for a different user's
     *       initial one. `current-password` -- the value the browser's own advisory suggests -- is
     *       wrong here precisely because the value being typed is not the typist's own.
     *       Trade-offs: a manager may still offer to generate and store a value that, under divergence
     *       D-4, this screen never transmits. That is accepted over leaving the attribute unset,
     *       because unset lets a manager both autofill AND save, and silently pre-filling one
     *       operator's credential into another user's account is the larger of those two harms.
     */
    const controlProps = {
      autoComplete: presentation.nonDisplay === true ? 'new-password' : 'off',
      ...fieldAriaProps(controlId, {
        invalid: refusal !== undefined,
        hasError: refusal !== undefined,
        hasHint: presentation.hint !== undefined,
      }),
      id: controlId,
      ref: registerControl(field),
      value: values[field],
      maxLength: USER_ADD_FIELD_WIDTHS[field],
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
            {USER_ADD_FIELD_LABELS[field]}
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
                   * WHY : Assumptions: the hint element carries the identifier `fieldAriaProps` derives
                   *       for it, so the control's description resolves to something rendered. The style
                   *       stays on this element rather than moving into the helper, because the colour is
                   *       the mapset's own `COLOR=BLUE` on that field and the helper is colour-agnostic by
                   *       design -- the design system colours the refusal container itself.
                   */
                  <Typography.Text id={fieldHintId(controlId)} style={hintStyle}>
                    {presentation.hint}
                  </Typography.Text>
                ),
            })}
      >
        {/*
         * WHY : Trade-offs: the credential control is an `Input.Password`, so it MASKS what the 3270
         *       left blank. The mapset declares `ATTRB=(DRK,FSET,UNPROT)` at `app/bms/COUSR01.bms`
         *       L126 -- non-display, so a terminal echoed nothing at all and the operator saw an empty
         *       field while typing. Dots are accepted as strictly better feedback with no behavioural
         *       difference, which is AAP gap G2 taken deliberately. The visibility toggle is switched
         *       OFF because the source field offered no reveal affordance, so leaving antd's default on
         *       would add a control the reference does not have.
         *       Assumptions: this is one of exactly THREE genuine `(DRK,FSET,UNPROT)` password inputs in
         *       the application -- `app/bms/COSGN00.bms` L175, this mapset's L126 and
         *       `app/bms/COUSR02.bms` L130 -- and AAP section 0.3.2's "6 occurrences" figure counts a
         *       different attribute set: the six `(ASKIP,DRK,FSET)` hidden carriers `CRDSTP2` through
         *       `CRDSTP7` on `app/bms/COCRDLI.bms`, which are skip-protected data carriers rather than
         *       inputs an operator types into.
         */}
        {presentation.nonDisplay === true ? (
          <Input.Password {...controlProps} visibilityToggle={false} />
        ) : (
          <Input {...controlProps} />
        )}
      </Form.Item>
    );
  }

  /*
   * WHY : Trade-offs: the mapset's absolute geometry is NOT reproduced, and this is AAP gap G1 taken
   *       deliberately -- `DESIGN_GAPS` in `ui/src/theme/tokens.ts` is the register that records it.
   *       `DFHMDI SIZE=(24,80)` fixes a 24-row by 80-column character grid and all 28 field definitions
   *       carry an absolute `POS=(row,column)` -- the five controls at `(8,18)`, `(8,56)`, `(11,15)`,
   *       `(11,55)` and `(14,17)` -- so a faithful rendering would need character cells at fixed
   *       coordinates. What is preserved is what survives translation: the GROUPING, the READING ORDER
   *       and the TAB ORDER. Each block below is one of the mapset's own rows -- 4 for the caption, 8
   *       for the two names, 11 for the identifier and the credential, 14 for the user type -- and the
   *       two controls the mapset puts on a shared row share one row here. What is given up is
   *       pixel-for-character positioning, which no browser holds across viewport widths and which
   *       would be hostile to an operator using magnification or a screen reader.
   * WHY : Alternatives Considered: `Row` and `Col` with a `gutter`, which is antd's idiomatic form grid.
   *       Rejected because `gutter` takes a PIXEL NUMBER and AAP section 0.3.2 admits only values that
   *       resolve to a design token, so the idiomatic choice would put a hardcoded spacing literal on
   *       the two rows that need it. `Flex` takes antd's semantic sizes, which resolve through the
   *       theme's spacing scale, and its `flex` prop lets each control share a row without a width
   *       literal.
   * WHY : Assumptions: the controls are rendered in the mapset's reading order, so the browser's own tab
   *       order is the reference's cursor order -- first name, last name, identifier, credential, user
   *       type -- with no `tabIndex` anywhere. An explicit tab index would have to be maintained
   *       alongside the order the elements already appear in, and the two could then disagree.
   */
  return (
    <Flex vertical gap="large">
      <ScreenTitle style={captionStyle}>{USER_ADD_CAPTION}</ScreenTitle>
      <Form layout="vertical">
        <Flex gap="middle" wrap align="flex-start">
          <Flex vertical flex="1 1 0">
            {renderField('firstName')}
          </Flex>
          <Flex vertical flex="1 1 0">
            {renderField('lastName')}
          </Flex>
        </Flex>

        <Flex gap="middle" wrap align="flex-start">
          <Flex vertical flex="1 1 0">
            {renderField('userId')}
          </Flex>
          <Flex vertical flex="1 1 0">
            {renderField('password')}
          </Flex>
        </Flex>

        {renderField('userType')}
      </Form>
    </Flex>
  );
}

/*
 * WHY : Alternatives Considered: this module publishes the component under BOTH its name and the
 *       `default` key, where eight of this tree's screens publish only a name. The default export is
 *       what lets `ui/src/router.tsx` declare the route with a bare `lazy(() => import(...))`: that
 *       module documents two adapter shapes and states that a screen publishing a `default` needs no
 *       wrapper, while a name-only screen needs one whose whole body exists to republish the name under
 *       the `default` key. Publishing both keeps the named import available to the Vitest suite and to
 *       any sibling that prefers it, and it is the shape six of the fourteen screens already use, so it
 *       introduces no convention this tree does not already carry.
 */
export default UserAddScreen;
