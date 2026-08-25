/**
 * @file The user update screen, migrated from `app/cbl/COUSR02C.cbl` (415 lines) and its mapset
 * `app/bms/COUSR02.bms` (29 `DFHMDF` fields, 12 of them named), mounted at `/users/:id/edit`.
 *
 * Assumptions: the route's selector segment is OPTIONAL, so one pattern serves both arrivals this
 * screen has to answer -- `/users/edit`, which administrative option 3 enters with no identifier and
 * which matches `app/cbl/COUSR02C.cbl`'s empty first turn, and `/users/<id>/edit`, which the browse
 * enters with one already chosen. The mount effect below reads nothing when the route carries no
 * identifier, which is what lets one module serve both without a second component.
 *
 * Assumptions: the identifier segment is OPTIONAL in that one declaration, so both arrivals the
 * reference has reach this screen -- `/users/:id/edit` from a caller that selected a row, and
 * `/users/edit` from the administrative menu, which selects nobody. The mount effect below reads
 * nothing in the second case, mirroring `app/cbl/COUSR02C.cbl` L99-L104, which tests its selection
 * carrier against `SPACES AND LOW-VALUES` before using it.
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
 * declares nine operations, none of them one. Onboarding's own credential is published as
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
import { useLocation, useNavigate, useParams } from 'react-router';

import { USER_ID_MAX_LENGTH, getUser, updateUser } from '../../api/auth';
import { isApiRequestError, retainOutcomeAcrossNavigation } from '../../api/client';
import type {
  ApiError,
  FieldValidationState,
  UpdateUserRequest,
  UserResponse,
  UserType,
} from '../../api/types';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import {
  BLANK_FIELD_MARKER_CHARACTERS,
  busyAnnouncement,
  fieldAriaProps,
  fieldErrorHelp,
  fieldHintId,
} from '../../layout/fieldHelp';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { UNIFORM_PF_KEY_LABELS, decodeBmsLegendText } from '../../layout/PfKeyBar';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap, PfKeyRejection } from '../../layout/usePfKeys';
import {
  INVALID_KEY_PRESSED,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  fitsDeclaredWidth,
  formatMessageTemplate,
  normaliseForWire,
} from '../../messages/messages';
import type { MapsetName } from '../../messages/messages';
import {
  ADMIN_MENU_ROUTE,
  USER_LIST_ROUTE,
  USER_UPDATE_ROUTE_TEMPLATE,
  inApplicationRoute,
  navigateSafely,
  screenTransitionState,
} from '../../routes/navigation';
import { copybookFieldWidthStyle } from '../../layout/recordLayout';
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
 * The band this screen published for a save it then left, for whichever screen the operator lands on.
 *
 * Purpose: carry ONE sentence and its tone across a route change, and nothing else. A save reached by
 * `F3=Save&&Exit` publishes its outcome onto a band and then transfers away in the same turn, so the
 * band it published is discarded by the unmount before it can be read.
 *
 * Assumptions: the tone travels beside the sentence rather than being re-derived at the destination. It
 * is the tone the reference itself moved into the message field's colour attribute -- `DFHGREEN` for a
 * committed write at `app/cbl/COUSR02C.cbl` L371, `DFHRED` for a refusal at L241 -- and a destination
 * screen has no way to recover which of those a sentence carried by inspecting the sentence.
 */
export interface UserUpdateSaveHandover {
  /** The sentence, already composed, exactly as this screen put it on its own band. */
  readonly message: string;
  /** The tone that sentence was published with, from the colour the reference moved beside it. */
  readonly severity: MessageBandSeverity;
}

/**
 * Name the save handover is retained and collected under.
 *
 * Assumptions: the name is COMPOSED from the route template both screens already import from
 * `ui/src/routes/navigation.ts`, rather than being written out as a literal in each of them. The
 * retention and the collection are in two different modules here -- this screen retains, the browse
 * collects -- so the claim is a name two call sites have to agree on, and deriving both from one shared
 * constant is what makes agreement structural instead of a matter of two literals staying in step.
 *
 * ⚠️ Alternatives Considered: exporting this constant and importing it in the browse, which is what
 * `ui/src/api/client.ts` assumes when it records that "both call sites are in the same screen".
 * Rejected because every screen in this tree is mounted through `lazy()` in `ui/src/router.tsx`, so a
 * static import from one screen module into another would fold this screen's chunk into the browse's and
 * charge every operator who opens the list for code they may never reach. The suffix is the only
 * duplicated text, and it is inert: a mismatch would leave an outcome uncollected rather than mis-routed,
 * and the pair of cases in `ui/src/test/userList.test.tsx` and `ui/src/test/userUpdate.test.tsx` fails on
 * exactly that.
 */
const USER_UPDATE_SAVE_CLAIM = `${USER_UPDATE_ROUTE_TEMPLATE}#saved`;

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
 * names is separately registered as `D-SIGNON-RETIRED-WIDTH-HINT` for the one screen that still has a
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
 * WHY : ⚠️ Refactoring Rationale: entry is now measured against the declared width in the two units the
 *       RECORD is stated in, where the control's `maxLength` was the only bound. `maxLength` stops the
 *       keystroke, which is the right interaction, but it counts UTF-16 CODE UNITS -- a unit neither
 *       `PIC X(20)` nor a `VARCHAR(20)` column is declared in. A rendering review measured the
 *       consequence on these name fields directly: `Ünïcödé Émoji 🎉🏦` is sixteen characters and
 *       eighteen code units, so a twenty-position field stops accepting after eighteen of them, and a
 *       field filled entirely with astral characters stops after ten. The operator meets a field that
 *       silently holds half of what its own hint advertises, and nothing says so.
 * WHY : ⚠️ Assumptions: BOTH measures have to fit and the BYTE measure usually binds, which is why the
 *       shared predicate is used rather than a character count here. `SEC-USR-FNAME PIC X(20)`
 *       (`app/cpy/CSUSR01Y.cpy` L19) is twenty BYTES on the record, so twenty accented letters are
 *       twenty code points and forty bytes -- a value `maxLength` admits and the record cannot hold.
 *       In the other direction one astral character is a single code point that `maxLength` counts
 *       twice, refusing a character the field has room for. Clamping on the record's own units removes
 *       both errors at once.
 * WHY : ⚠️ Trade-offs: an over-long entry is CLAMPED rather than refused with a sentence, and the reason
 *       is that no sentence exists to say it in. `app/cbl/COUSR02C.cbl` L182-L206 declares five field
 *       refusals -- the four controls this screen paints plus the withdrawn credential's -- and every
 *       one of them is a BLANK test, `... can NOT be empty...`, with no length arm at all, because a 3270
 *       field of length n physically cannot hold n+1 characters, so an over-length condition never
 *       arose for the program to report. The catalog is transcribed from the program, so stating one
 *       here would mean authoring operator prose the reference never wrote. Clamping is the terminal's
 *       own behaviour: the keyboard simply stopped accepting into a full field.
 * WHY : Alternatives Considered: importing the identically-reasoned clamp from the sibling add screen or
 *       from `ui/src/screens/accountUpdate`. Rejected because every screen is mounted through `lazy()`
 *       in `ui/src/router.tsx`, so a value import from another screen folds that screen's chunk into
 *       this one; the rule is restated and the shared PREDICATES it consults -- `normaliseForWire` and
 *       `fitsDeclaredWidth` -- are imported, so the screens agree by construction on the part that
 *       could actually diverge.
 */

/**
 * Clamps one entry to a declared field width, measured as the record measures it.
 *
 * Purpose: keep the value the operator can see identical to the value the field can store, so nothing
 * is accepted on the glass that the record then cannot hold.
 *
 * Assumptions: the entry is normalised to Normalization Form C FIRST and the clamp then walks whole
 * CODE POINTS rather than UTF-16 units, so a surrogate pair is kept or dropped as one character and can
 * never be cut in half into a lone surrogate -- a value no byte measure could make sense of.
 *
 * Trade-offs: the fit is re-tested per candidate length rather than computed from a byte count in one
 * step. That is a loop over at most the entry's own length on a keystroke, and it buys the property that
 * this function and any validator agree BY CONSTRUCTION because both consult one predicate, where byte
 * arithmetic of its own here would be a second implementation of the same rule.
 * @param {string} entry - The value the control reported, exactly as it arrived.
 * @param {number} declaredWidth - The field's `PIC X(n)` width; a positive integer.
 * @returns {string} The normalised entry, shortened by whole code points until it fits the width.
 * @throws {RangeError} If `declaredWidth` is negative or not an integer, raised by the predicate.
 */
export function clampToDeclaredWidth(entry: string, declaredWidth: number): string {
  const normalised = normaliseForWire(entry);
  if (fitsDeclaredWidth(normalised, declaredWidth)) {
    return normalised;
  }

  const codePoints = Array.from(normalised);
  let kept = codePoints.length - 1;
  while (kept > 0 && !fitsDeclaredWidth(codePoints.slice(0, kept).join(''), declaredWidth)) {
    kept -= 1;
  }

  return codePoints.slice(0, kept).join('');
}

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
 *
 * ⚠️ Alternatives Considered: the message catalogue now publishes two AUTHORED failure sentences --
 * `TRANSIENT_FAILURE_TRY_AGAIN` for a condition that may clear and `PERSISTENT_FAILURE_REPORT_IT` for
 * one that will not -- selected on the classification `ui/src/api/client.ts` already computes. Neither
 * is taken here, and the reason is Rule T8 rather than inertia: every failure sentence this function
 * can reach HAS a mainframe source, so substituting an authored one would replace a verbatim
 * operator-facing string with a better-worded invention. The authored pair is for screens and states
 * the reference never had a sentence for -- which is why the busy announcement this screen renders does
 * take one.
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
 * optional in this component's own terms, and that is a property of the COMPONENT rather than of any
 * route: `useParams` types every parameter as possibly absent, and a mount outside a match of this
 * screen's one pattern -- which is how its covering test reaches the no-identifier state -- supplies
 * none. The screen answers that arrival the way the reference answers a carrier arriving as `SPACES`
 * (L99-L104): it reads nothing and waits for a typed identifier.
 *
 * ⚠️ Refactoring Rationale: no ROUTE produces that arrival any more, and the previous wording -- "an
 * operator may reach the screen with no identifier and type one" -- claimed one did. A selector-free
 * `/users/edit` was mounted on this component so the administrative menu's update option had somewhere
 * to go, and it was withdrawn because AAP section 0.4.1.4 enumerates twenty-one screen routes and names
 * `/users/:id/edit` as the only user-update route. `ui/src/screens/admin/index.tsx` sends that option to
 * the user browse instead, where the identifier is selected, so every routed arrival here now carries
 * one.
 * @returns {ReactElement} The header band, caption, message band, the four controls and the key legend.
 */
export function UserUpdateScreen(): ReactElement {
  const navigate = useNavigate();
  /*
   * WHY : Assumptions: the location is read for its `state` alone, which is where the caller origin
   *       arrives. `app/cpy/COCOM01Y.cpy` L23-L26 carried `CDEMO-FROM-PROGRAM` in the shared structure
   *       and this screen's PF3 arm prefers it over its own menu destination, so a stateless target needs
   *       some carrier for it; router state is that carrier because it travels in the history entry and
   *       therefore appears in no request line, unlike a query member.
   */
  const location = useLocation();
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

  /**
   * What the save turn now running published onto the band, or `null` before it has published anything.
   *
   * Purpose: make the outcome of a save readable by {@link handleSaveAndExit} at the moment it leaves.
   *
   * ⚠️ Assumptions: a ref and not the `message` state above, because the exit arm reads this INSIDE the
   * continuation that published it. A state setter's effect is not visible to the closure that called it
   * -- `message` in that closure is still whatever it held when the render was created -- so reading the
   * state there would hand the destination the PREVIOUS turn's sentence, or `null` on the first save.
   * That is the same class of defect as the write itself: a message that describes the wrong thing is
   * worse than no message, because an operator cannot tell it apart from a right one.
   *
   * Assumptions: it is reset at the head of each save turn and written by every arm of that turn, so it
   * is turn-scoped rather than a running log of everything the band has ever shown. The read arm's
   * failures are written through {@link applyFailureReport} as well, which is harmless precisely because
   * of that reset: the exit arm only ever reads it after the save turn it opened has settled.
   *
   * Alternatives Considered: changing {@link attemptSave} to resolve with its outcome instead of `void`.
   * Rejected because that promise is also what the exit arm SEQUENCES on -- it is awaited for its
   * ordering, which is the reference's synchronous `EXEC CICS REWRITE` before `EXEC CICS XCTL` -- and
   * the other caller, PF5, wants neither the value nor the ordering. Threading a value through a promise
   * two callers read for two different reasons buys nothing a ref does not.
   */
  const saveTurnBand = useRef<UserUpdateSaveHandover | null>(null);
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
   * WHY : ⚠️ Refactoring Rationale: an effect used to register {@link invalidateTurnsInFlight} as an
   *       UNMOUNT cleanup, and it is withdrawn. Its own note recorded it as purely defensive and as not
   *       observable in the React version this bundle pins -- a state update on an unmounted component
   *       is a silent no-op in React 19 -- so nothing it protected is lost. What it cost is not
   *       hypothetical: React runs an effect's cleanup and then the effect again when a MOUNTED tree
   *       suspends and resumes, and this screen is mounted through `lazy()` under a `Suspense` boundary
   *       in `ui/src/router.tsx`. A replay therefore invalidated the read that was still on the wire,
   *       and the screen recovered only because the replayed effect issued a SECOND read whose answer
   *       was current -- which is exactly the duplicate `GET` a browser sweep measured on one arrival at
   *       this route. Suppressing that duplicate through {@link requestedRouteUserId} while keeping this
   *       cleanup would have left the first answer discarded and the second never issued, so the row
   *       would never have landed at all: the duplicate was load-bearing, and both halves have to go
   *       together.
   * WHY : Assumptions: every OTHER caller of {@link invalidateTurnsInFlight} is unaffected, because each
   *       of them is an operator act rather than a lifecycle event. PF4 still invalidates -- clearing
   *       the screen is precisely the abort of an outstanding turn -- and every outcome is still gated
   *       on {@link isCurrentTurn}, so the A-then-B navigation race the counter exists for is closed
   *       exactly as before. Alternatives Considered: keeping the cleanup and distinguishing an unmount
   *       from a replay. Rejected because React publishes no such signal: a cleanup is a cleanup, and
   *       code that guessed which one it was would be guessing on every release.
   */

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
      /*
       * WHY : Assumptions: a REFUSED save is recorded for the handover as well as a committed one, and
       *       the mechanism's own note says why: "a FAILURE is retained as well as a success, and that
       *       is half the value of the mechanism". `F3=Save&&Exit` transfers on either outcome -- no
       *       `ERR-FLG` test sits between the two `PERFORM`s at `app/cbl/COUSR02C.cbl` L112 and L119 --
       *       so an operator who takes it after a refused write would otherwise be told nothing at all
       *       about a change they believe they made.
       */
      saveTurnBand.current = { message: report.message, severity: 'error' };
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
           * WHY : ⚠️ Assumptions: the severity is `'neutral'` and NOT `'info'`, because `DFHNEUTR` is the
           *       NEUTRAL role and not the TURQUOISE one. `ui/src/theme/tokens.ts` resolves NEUTRAL to
           *       `colorTextSecondary` and TURQUOISE to `colorInfo`, and `ui/src/layout/MessageBand.tsx`
           *       records that keeping those two apart is precisely what the token bridge's G3 decision
           *       exists to enforce -- so `'info'` painted a de-emphasised line in the informational hue,
           *       which a rendering review recorded as informational-looking content in the outcome band.
           *       The same one-word correction is applied to the sibling delete screen, whose program
           *       recolours its own awaiting sentence the same way (`app/cbl/COUSR03C.cbl` L285), so the
           *       two administrative screens that have such a line render it identically.
           *       Assumptions: the CHANNEL is unchanged, and correctly so: `app/bms/COUSR02.bms` declares
           *       `ERRMSG` at `POS=(23,1)` and no `INFOMSG`, so row 22 does not exist on this mapset, and
           *       the sentence reports the outcome of the read just taken rather than standing guidance.
           */
          setMessage(UPDATE_MESSAGES.PRESS_PF5_KEY_TO_SAVE_YOUR_UPDATES);
          setSeverity('neutral');
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

  /**
   * The route identifier this screen has already issued a read for, or `null` before the first one.
   *
   * Purpose: make the effect below read one identifier ONCE however many times React runs it.
   *
   * ⚠️ Assumptions: a mounted effect can run again without the route having changed, so "runs once per
   * mount" is not a property this screen may assume. Two mechanisms produce it here and both are real:
   * this screen is mounted through `lazy()` under a `Suspense` boundary in `ui/src/router.tsx`, and
   * React re-runs a mounted tree's effects when that tree suspends and resumes; and React's development
   * mode deliberately double-invokes every effect to surface exactly this class of defect. A browser
   * sweep of the PRODUCTION bundle measured two `GET /api/v1/auth/users/{id}` requests carrying two
   * distinct correlation identifiers for ONE arrival at `/users/:id/edit`, which rules the development
   * double-invoke out as the explanation of what was measured and leaves suspend-and-resume as the
   * cause. Guarding on the identifier answers both, because both re-run the effect with the same route
   * parameter.
   *
   * Assumptions: a ref and not state, because the value must be readable and writable inside the effect
   * without scheduling a render. Holding it in state would re-render on every arrival and would still
   * not be observable by the effect run that has to be suppressed, since a state write made from an
   * effect is visible only to the NEXT run.
   *
   * Alternatives Considered: deduplicating the read in the transport layer, beside the destructive-write
   * guard `withoutConcurrentDuplicate` in `ui/src/api/client.ts`. Rejected, and the rejection is
   * measured rather than assumed: a lookup is safe and repeatable, so collapsing two identical reads
   * there would also collapse a DELIBERATE re-read -- and this screen has one, because
   * {@link attemptSave} re-reads the row on every save exactly as `app/cbl/COUSR02C.cbl` L215-L217
   * does. A transport that joined those two requests would make a save compare against the fetch arm's
   * answer instead of against the row as it now stands.
   *
   * Trade-offs: a route that names the same identifier twice in succession -- which the router cannot
   * produce, because navigating to the current location is a no-op -- reads once. That is the intended
   * reading rather than a limitation: the row the operator asked for is already on the glass, and Enter
   * is the key that asks for it again.
   */
  const requestedRouteUserId = useRef<string | null>(null);

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
     * Assumptions: the effect is skipped entirely when NO identifier reaches it, which the reference's
     * own guard does -- it tests the carrier against `SPACES AND LOW-VALUES` before using it -- and
     * leaves the screen waiting for a typed identifier rather than refusing a blank one the operator
     * never entered. Since `/users/edit` was withdrawn, the router cannot produce that arrival: this
     * component is mounted at `/users/:id/edit` alone, so the guard covers the optional shape
     * `useParams` gives the parameter and a mount outside a matched route, both of which its covering
     * tests exercise. Removing it would trade a defined waiting state for a read of `undefined`.
     * @returns {void} Completion is represented by the screen's own state.
     */
    function loadRouteUser(): void {
      if (routeUserId === undefined) {
        return;
      }

      /*
       * WHY : Assumptions: the guard is the ONE place a duplicate read is stopped, and it is written
       *       here rather than inside {@link load}, which every other caller reaches. The Enter arm and
       *       the save arm both read the same identifier deliberately -- the reference re-reads the row
       *       inside `UPDATE-USER-INFO` at `app/cbl/COUSR02C.cbl` L215-L217 even when its Enter arm has
       *       just read it -- so a guard inside `load` would suppress a read the reference performs.
       *       What is being suppressed is narrower than "a second read of this row": it is a second
       *       read caused by this EFFECT running again for an unchanged route.
       */
      if (requestedRouteUserId.current === routeUserId) {
        return;
      }

      /*
       * WHY : Assumptions: the identifier is recorded BEFORE the request is issued, not when it
       *       settles. A re-run arriving while the first read is still on the wire is exactly the case
       *       measured -- the two requests were 2 ms apart -- so a marker written on completion would
       *       be written too late to stop the duplicate it exists to stop.
       */
      requestedRouteUserId.current = routeUserId;
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
    /*
     * WHY : Assumptions: a save refused before any request left the browser is recorded too. The
     *       reference reaches `RETURN-TO-PREV-SCREEN` from `F3` whether the cascade at L182-L207 refused
     *       the turn or a write was issued, so this is one of the outcomes an operator can leave on.
     */
    saveTurnBand.current = { message: BLANK_FIELD_MESSAGES[field], severity: 'error' };
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

    /*
     * WHY : Assumptions: the handover record is cleared HERE, past the in-flight guard and ahead of the
     *       validation cascade, so it is scoped to the turn this call actually opens. Clearing it before
     *       the guard would discard the outcome of the turn still running -- which is the one the exit
     *       arm is waiting on -- and clearing it later would leave a refusal raised by the cascade
     *       carrying whatever the previous turn published.
     */
    saveTurnBand.current = null;

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
          saveTurnBand.current = {
            message: UPDATE_MESSAGES.PLEASE_MODIFY_TO_UPDATE,
            severity: 'error',
          };
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
            /*
             * WHY : Refactoring Rationale: the composition is bound to a name and used twice rather than
             *       being inlined into the setter, because the same sentence is now also what a save
             *       reached by `F3=Save&&Exit` hands to the screen the operator lands on. Composing it
             *       twice would let the band and the handover drift apart, and a handover that said
             *       something slightly different from what the screen itself would have said is the one
             *       failure mode of carrying a sentence across a route change.
             */
            const committed = formatMessageTemplate(MESSAGE_TEMPLATES.USER_HAS_BEEN_UPDATED, {
              'SEC-USR-ID': saved.userId,
            });

            setMessage(committed);
            setSeverity('success');
            saveTurnBand.current = { message: committed, severity: 'success' };
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
   * Reports the route the back arm leaves for, without leaving for it.
   *
   * ⚠️ Refactoring Rationale: this replaced an `exitToOrigin` helper that both computed the destination
   * and navigated to it, and the split happened because a SECOND caller needs the answer BEFORE the
   * transfer rather than as a side effect of it: {@link handOverAndExit} decides whether to leave its
   * sentence behind based on whether the destination is a screen that collects one. Computing it twice
   * -- once to decide and once to navigate -- would let the two disagree if the router state changed
   * between them, and the disagreement would present as a sentence retained for a screen that never
   * mounts. The navigating half is now inside {@link handOverAndExit}, which is the only caller PF3
   * has, so `exitToOrigin` was left with no call site at all and is gone rather than kept as a second
   * route out of this screen that nothing takes.
   *
   * ⚠️ Refactoring Rationale: the destination is a VALIDATED caller origin where PF3 previously
   * transferred to the administrative menu unconditionally, and the rationale then recorded -- that the
   * router publishes no equivalent of a named calling program -- was wrong: `ui/src/routes/navigation.ts`
   * publishes exactly that as `ScreenTransitionState.from`, and the user browse hands its own route over
   * on the row action it opens this screen with (`ui/src/screens/userList/index.tsx`). Taking the
   * fallback unconditionally lost real behaviour: an administrator who opened a row from the list was
   * returned to the administrative menu and had to re-enter the browse and re-page to reach the next
   * row, which is the behaviour loss `app/cbl/COUSR00C.cbl` L192-L197 avoids by naming itself in
   * `CDEMO-FROM-PROGRAM` before it transfers.
   *
   * Assumptions: both arms of the reference's decision are reproduced intact -- `app/cbl/COUSR02C.cbl`
   * L113-L118 prefers `CDEMO-FROM-PROGRAM` and falls back to `'COADM01C'` when it is blank -- so an
   * origin the closed set does not admit, and an arrival with none at all, both reach the administrative
   * menu, which is the reference's own default.
   *
   * Assumptions: the claim is validated rather than trusted, because router state is attacker-writable
   * through a hand-edited history entry: an unvalidated path-shaped value would either reach the
   * not-found surface or, if protocol-relative, leave the application on a key press the operator
   * believes goes back one screen. The origin selects a destination and nothing else -- the row this
   * screen reads and writes comes from the path parameter under the signed token -- so a forged origin
   * changes where PF3 goes and reaches no other operator's record.
   * @returns {string} The caller's route when it is one this application admits, and the administrative
   *   menu otherwise, which is the reference's own default.
   */
  function originDestination(): string {
    return inApplicationRoute(screenTransitionState(location.state).from) ?? ADMIN_MENU_ROUTE;
  }

  /**
   * Leaves the screen for the administrative menu, which is the reference's PF12 arm (L124-L125).
   *
   * Assumptions: this arm is UNCONDITIONAL where PF3's is not, and the two are kept as separate
   * functions for that reason rather than sharing one destination. `app/cbl/COUSR02C.cbl` L124-L125 moves
   * `'COADM01C'` into `CDEMO-TO-PROGRAM` with no preference for the calling program at all, so PF12
   * always returns to the administrative menu even on a turn where PF3 would have gone back to the
   * browse. The sibling deletion screen draws the same distinction from its own L121-L122.
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
     * WHY : Assumptions: BOTH settlements transfer to the same place, and that place is the validated
     *       caller origin rather than the menu. The reference's L112-L119 has no `ERR-FLG` test between
     *       `UPDATE-USER-INFO` and `RETURN-TO-PREV-SCREEN`, so the destination does not depend on the
     *       save's outcome; {@link originDestination} is what encodes which destination that is.
     */
    attemptSave().then(handOverAndExit, handOverAndExit);
  }

  /**
   * Hands the save's own sentence to the destination and then leaves.
   *
   * ⚠️ Purpose: close the one gap a browser sweep measured on this key. `PUT /api/v1/auth/users/{id}`
   * returned `200` and was followed immediately by a client-side route change whose band was EMPTY -- the
   * write happened and the operator was told nothing, on a key whose own legend promises it saves.
   *
   * Assumptions: this is not a divergence from the reference, it is the reference's own behaviour
   * surviving a platform difference. `UPDATE-USER-SEC-FILE` composes the green sentence and performs
   * `SEND-USRUPD-SCREEN` at `app/cbl/COUSR02C.cbl` L370-L377, so the program DOES send the operator its
   * outcome on this path; what discards it there is the `EXEC CICS XCTL` at L258-L261 overwriting the
   * terminal buffer with the next program's map, which is a consequence of how 3270 screens are
   * delivered rather than a decision that the operator should not be told. Carrying the composed sentence
   * to the destination is that sentence reaching its intended reader. Registered as an improvement rather
   * than a parity break: no value the reference writes changes, and no value it does not write appears.
   *
   * Assumptions: the outcome is retained UNCONDITIONALLY when one was published, whatever its tone. Both
   * arms of the caller reach here for the reason recorded there -- no `ERR-FLG` test sits between L112
   * and L119 -- so a refused write leaves on this key exactly as a committed one does, and it is the
   * refusal that an operator most needs carried.
   *
   * Alternatives Considered: publishing the band and delaying the transfer until the operator has read
   * it. Rejected outright: the reference transfers in the same turn as the write, so holding the screen
   * would invent a turn the program does not have and would strand an operator on a screen whose own
   * legend says they have left it.
   * @returns {void} Completion is the retention followed by the requested route transition.
   */
  function handOverAndExit(): void {
    const published = saveTurnBand.current;
    const destination = originDestination();

    /*
     * WHY : ⚠️ Assumptions: the outcome is retained only when the destination this key is about to reach
     *       is one that COLLECTS it. Retention is not a broadcast: `ui/src/api/client.ts` holds a claim
     *       until somebody takes it, deliberately, so that an outcome landing a few milliseconds after
     *       its reader mounted is not lost. The same property makes an UNCOLLECTED claim durable -- it
     *       would sit in the store and be taken by the next screen that does collect, which is the user
     *       browse, and an operator arriving there later would be shown an acknowledgement of a write
     *       they were told about on another screen or not at all. A sentence that describes an earlier
     *       turn is indistinguishable from one describing this turn, so a stale hand-over is worse than
     *       silence on an administrative record.
     * WHY : ⚠️ Refactoring Rationale: BOTH of this key's destinations now collect, and this guard admits
     *       both. It used to admit the browse alone, on the recorded ground that
     *       `ui/src/screens/admin/index.tsx` "has no collector, so an operator who deep-links to a row,
     *       saves and exits still reads nothing" and that "that half is reported rather than reached
     *       for". That statement was true when it was written and is now false: the administrative menu
     *       carries the collector, and it carries the stronger form of it -- it takes the claim on mount
     *       AND subscribes, because a write measured landing 7 to 12 ms after the route change is
     *       already too late for a mount-only check. Leaving the guard at the browse alone would keep
     *       the whole finding open for exactly the operator it was reported about: the one who reaches
     *       this route by its address, where `app/cbl/COUSR02C.cbl` L113-L118 falls back to `'COADM01C'`
     *       because no calling program is named.
     * WHY : ⚠️ Assumptions: the set is enumerated as the two routes that collect, and it is deliberately
     *       NOT widened to "every destination". Retention stays conditional for the reason above -- an
     *       uncollected claim is durable and would be taken later by whichever screen does collect -- so
     *       the guard has to name collectors rather than approve destinations. These are also exhaustive
     *       for this key by construction: {@link originDestination} answers a validated in-application
     *       origin or the administrative menu, and the only origin any screen hands this route is the
     *       browse's own route.
     * WHY : Alternatives Considered: retaining unconditionally and letting whichever screen collects
     *       paint whatever it finds whenever it next mounts. Rejected for the staleness above. Also
     *       considered: sending every exit to the browse so the sentence always has a reader. Rejected
     *       as a parity break -- the reference's PF3 arm prefers the calling program and falls back to
     *       the administrative menu, and rewriting where a key goes to suit a message is the message
     *       dictating navigation.
     */
    if (
      published !== null &&
      (destination === USER_LIST_ROUTE || destination === ADMIN_MENU_ROUTE)
    ) {
      /*
       * WHY : Assumptions: the discriminator says the HANDOVER completed, not that the write succeeded.
       *       The write's outcome is already in `severity` -- `success` for the committed sentence,
       *       `error` for every refusal -- so mapping a refusal onto `settled: 'FAILED'` would encode the
       *       same fact twice and give a destination two answers that could disagree. `FAILED` in that
       *       type carries `failure: unknown`, which is a raised error and not a sentence; this screen
       *       has already reduced its failures to sentences by the time it reaches here, and re-raising
       *       one would ask the destination to reduce it a second time.
       */
      retainOutcomeAcrossNavigation<UserUpdateSaveHandover>(USER_UPDATE_SAVE_CLAIM, {
        settled: 'COMPLETED',
        value: published,
      });
    }

    navigateSafely(navigate, destination);
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
   *
   * ⚠️ Refactoring Rationale: every control other than the user type is now clamped to its DECLARED
   * width by {@link clampToDeclaredWidth}, where the value was stored as typed and bounded only by
   * `maxLength`. The WHY block above that function records the measurement and the reason a UTF-16 bound
   * is neither of the bounds a fixed-width record has. `maxLength` is KEPT on the control alongside
   * this, because stopping the keystroke is the affordance a 3270 had and this only corrects the unit.
   *
   * ⚠️ Assumptions: the identifier is clamped like the two names, even though it is the KEY the fetch is
   * issued on. Its declared width is eight in both units (`SEC-USR-ID PIC X(08)`,
   * `app/cpy/CSUSR01Y.cpy` L18) and the service refuses a wider value, so clamping it can only prevent
   * a read that would be refused; and a key silently truncated by `maxLength` at eight UTF-16 units
   * would be a DIFFERENT key from the one the operator typed, which is the worst of the three failures
   * available here.
   *
   * ⚠️ Assumptions: the user type is normalised and NOT additionally clamped, and the order matters.
   * {@link normaliseUserType} returns `''`, `'A'` or `'U'` -- a domain whose every member is one ASCII
   * character, so it already satisfies the one-position width in both units and a clamp after it could
   * only be a no-op. Running the clamp FIRST instead would be worse than redundant: it would truncate a
   * two-character paste to one character and hand the domain check a value the operator never intended,
   * where discarding the whole entry is what the reference's own field length did.
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
          [field]:
            field === 'userType'
              ? normaliseUserType(edited)
              : clampToDeclaredWidth(edited, USER_UPDATE_FIELD_WIDTHS[field]),
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
   *       does not mean what it means everywhere else.
   * WHY : ⚠️ Refactoring Rationale: every entry now declares its `risk`, and the note that stood here --
   *       that PF3's "button emphasis is unaffected: emphasis is keyed by AID through
   *       `PRIMARY_ACTION_AIDS`, which lists ENTER and PF5, so PF3 stays a default button exactly as the
   *       design-system mapping requires" -- is withdrawn. It described the mechanism accurately and drew
   *       exactly the wrong conclusion from it. A rendering pass measured the consequence: this bar
   *       carried TWO primary-blue controls at once, `ENTER=Fetch` and `F5=Save`, while `F3=Save&&Exit`
   *       -- a key that performs `PUT /api/v1/auth/users/{id}` before it leaves -- was the visually
   *       WEAKEST of the three. So the emphasis distinguished neither the writing keys nor the reading
   *       one. No AID-keyed table could have fixed that here, because the same `PFK03` is `F3=Back` on
   *       nine other mapsets; the input has to be what the key DOES.
   * WHY : ⚠️ Assumptions: the five classifications are read off this mapset's own legend literal at
   *       `app/bms/COUSR02.bms` L163-L164, `ENTER=Fetch  F3=Save&&Exit  F4=Clear  F5=Save  F12=Cancel`.
   *       `ENTER=Fetch` reads one row and writes nothing, so read-only. `F3=Save&&Exit` and `F5=Save`
   *       both reach `UPDATE-USER-INFO` (L112 and L121), so both mutating -- and `'mutating'` rather
   *       than `'destructive'` because an operator can come straight back and edit the row again, which
   *       is the distinction the union draws. `F4=Clear` empties controls on the client and issues no
   *       request, so read-only. `F12=Cancel` navigates (L124-L125) and writes nothing, so read-only.
   * WHY : ⚠️ Assumptions: `read-only` is stated EXPLICITLY on ENTER rather than omitted, and that is
   *       load-bearing. The fallback lists `ENTER`, so omitting it would leave the fetch key primary
   *       beside the two saves and reproduce the measured "two primary controls" bar with a third added.
   *       Saying "this key reads" has to be able to LOWER emphasis or it says nothing.
   * WHY : ⚠️ Refactoring Rationale: the three keys that OPEN a turn -- ENTER, PF3 and PF5 -- report
   *       `busy` where they reported `disabled`. `disabled` WITHDREW the control: the legend greyed it
   *       out, so a key whose own legend is on the glass in front of the operator became unavailable for
   *       the duration of its own turn, and an operator who pressed it got nothing and no explanation
   *       (this screen already silences the disabled rejection, so not even a sentence). `busy` leaves
   *       the control present, enabled, focusable and named and declines the press SILENTLY, which is
   *       the 3270's input-inhibit behaviour and is also what makes the affordance visible where the
   *       operator's attention already is -- on the control they pressed. The width-neutral slot
   *       `PfKeyBar` reserves is why the appearing affordance does not move the bar.
   * WHY : Assumptions: the guards inside the three handlers are kept as well rather than replaced. They
   *       are what a test can exercise deterministically, and two independent refusals of a second
   *       concurrent turn are cheaper than one.
   * WHY : ⚠️ Assumptions: PF4 and PF12 stay OUT of the busy channel entirely rather than reporting
   *       `busy: false`. PF4 is the abort of the outstanding turn, not a competitor for it, and PF12
   *       leaves the screen -- so both must stay live while a turn is in flight, and per `PfKeyBinding`
   *       an absent member reserves no affordance box while `false` reserves one. Omitting them is
   *       therefore the statement that these two keys never report busy at all.
   */
  const keyHandlers: PfKeyHandlerMap = {
    ENTER: {
      onInvoke: handleFetch,
      label: USER_UPDATE_KEY_LABELS.ENTER,
      risk: 'read-only',
      busy,
    },
    PFK03: {
      onInvoke: handleSaveAndExit,
      label: USER_UPDATE_KEY_LABELS.PFK03,
      action: 'save',
      risk: 'mutating',
      busy,
    },
    PFK04: { onInvoke: clearScreen, label: USER_UPDATE_KEY_LABELS.PFK04, risk: 'read-only' },
    PFK05: {
      onInvoke: handleSave,
      label: USER_UPDATE_KEY_LABELS.PFK05,
      risk: 'mutating',
      busy,
    },
    PFK12: { onInvoke: exitToAdminMenu, label: USER_UPDATE_KEY_LABELS.PFK12, risk: 'read-only' },
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
      /*
       * WHY : ⚠️ Refactoring Rationale: every control now carries the CEILING its copybook width
       *       declares, where only the identifier carried a style at all and that one was the
       *       fixed-pitch face. A rendering review measured what the absence cost: an eight-character
       *       input rendered 1172 pixels wide and the blank-field asterisk this screen writes at the
       *       field's right-hand edge landed at x≈1211, roughly 1150 pixels from the value it
       *       qualifies -- and the one-position user type rendered at the same full width as a
       *       twenty-character name, so the control said nothing about how much it would take.
       *       `ui/src/layout/recordLayout.ts` records the same measurement and states the conclusion
       *       this adopts: the marker cannot be brought to the value by moving the marker, so the field
       *       has to stop being many times wider than the data it holds.
       * WHY : ⚠️ Assumptions: the measure is spread onto the DESIGN-SYSTEM CONTROL and not onto the
       *       `Form.Item` or a wrapper, which that module records as a measured constraint rather than
       *       a preference: the theme scopes its custom properties to component class scopes, so
       *       `--ant-control-padding-horizontal` resolves on an `.ant-input` and returns the empty
       *       string on an arbitrary element. Spread here, the padding term resolves; spread on a
       *       wrapper it would not, and the `calc()` would be dropped at computed-value time.
       * WHY : Assumptions: the width style is merged UNDER the fixed-pitch face rather than replacing
       *       it, so the identifier keeps both -- the monospaced face that makes two eight-character
       *       keys render at equal width, and the eight-column ceiling. The two describe different
       *       properties and neither overwrites a member of the other; the merge order is the idiom
       *       `ui/src/screens/reports/index.tsx` L2611-L2613 already uses for the same pair.
       * WHY : Trade-offs: the helper publishes a MAXIMUM alongside `inlineSize: '100%'`, so a field
       *       declared wider than the viewport still shrinks to fit rather than forcing the page to
       *       scroll sideways. The declared width is therefore a ceiling and not a fixed size, which
       *       departs from the terminal -- where every field was exactly its declared width because the
       *       display was exactly 80 columns. AAP gap G1 already records that departure for POSITION;
       *       this is the same trade for SIZE, and it is what keeps the narrow viewports the same review
       *       measured free of horizontal overflow.
       */
      /*
       * WHY : ⚠️ Refactoring Rationale: the marker slot is declared to the measure, and only on the turn
       *       the marker is rendered. With a suffix present the design system sizes the affix WRAPPER,
       *       whose space the value and the slot then share, so a maximum computed for the value alone
       *       leaves the value short by whatever the slot takes -- measured on a sibling screen's
       *       two-character field as a record key that rendered as one glyph and a sliver. The narrowest
       *       marker-bearing control here is the one-character user type, where the shortfall is larger
       *       still.
       *       Assumptions: the allowance is CONDITIONAL on the very test that renders the suffix below,
       *       so an accepted field keeps exactly the measure it has always had. Widening every field
       *       unconditionally would change the whole form to fix a state none of its fields are in.
       */
      style: {
        ...(presentation.fixedPitch === true ? fixedPitchStyle : {}),
        ...copybookFieldWidthStyle(
          USER_UPDATE_FIELD_WIDTHS[field],
          cssVar,
          refusal?.state === 'BLANK' ? BLANK_FIELD_MARKER_CHARACTERS : 0,
        ),
      },
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
       * WHY : ⚠️ Refactoring Rationale: the screen ANNOUNCES its outstanding turn, where it previously
       *       only showed one. A review found `aria-busy` on no button anywhere and no live region
       *       naming the wait, so an operator who could not see the in-flight affordance had nothing at
       *       all: the controls stayed reachable, the request was in flight, and the screen said nothing
       *       about it. `busyAnnouncement` renders one visually hidden `role="status"` region -- always
       *       mounted, empty while idle, because a live region has to be in the accessibility tree
       *       BEFORE its content changes for the first change to be announced.
       * WHY : Assumptions: the sentence is `REQUEST_IN_PROGRESS` from the message catalogue, which is
       *       AUTHORED rather than transcribed, and taking an authored sentence here does not weaken
       *       Rule T8. The reference has no equivalent to carry: a 3270 turn simply locked the keyboard,
       *       so there is no mapset literal this could be displacing. Every sentence on this screen that
       *       DOES have a mainframe source is still that source's, verbatim.
       */}
      {busyAnnouncement(busy ? REQUEST_IN_PROGRESS : undefined)}
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
