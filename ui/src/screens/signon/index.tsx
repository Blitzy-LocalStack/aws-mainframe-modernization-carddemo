/**
 * @file The sign-on screen, migrated from `app/cbl/COSGN00C.cbl` and its mapset
 * `app/bms/COSGN00.bms`, mounted at route `/signon` by `ui/src/router.tsx`.
 *
 * Purpose
 * -------
 * Exchange an operator identifier and a password for a token set, answer the replacement-password
 * challenge when the identity provider raises one, and route onward to the administrative or the
 * ordinary menu. It replaces CICS transaction `CC00` and is the one route outside the sign-on guard,
 * because it is the route that establishes the credential every other route requires.
 *
 * What replaced the reference program's own comparison
 * ---------------------------------------------------
 * Assumptions: the reference reads `USRSEC` at `app/cbl/COSGN00C.cbl` L211-L219 and compares an
 * eight-character password held in clear — `05 SEC-USR-PWD PIC X(08).` at `app/cpy/CSUSR01Y.cpy`
 * L21 — at L223. Neither the column nor the comparison is carried forward. Sign-on here is a token
 * exchange against the managed provider, so this screen places the credential in a request body and
 * retains it no longer than the exchange; and the onward branch is taken from the SIGNED group claim
 * rather than from a field the client supplied. That is the security property the migration gains:
 * the reference branched on `CDEMO-USER-TYPE` (`app/cpy/COCOM01Y.cpy` L26-L28), which lived in a
 * communication area the terminal echoed back between turns.
 *
 * What this screen renders, and what it deliberately does not
 * ----------------------------------------------------------
 * Assumptions: the mapset paints 37 `DFHMDF` fields, 11 named and 26 anonymous. Eight of the named
 * fields belong to the shared header band and are rendered by `ui/src/layout/ScreenHeader.tsx`;
 * `ERRMSG` (L197, `LENGTH=78`, `COLOR=RED`, `ATTRB=BRT`) is rendered by
 * `ui/src/layout/MessageBand.tsx`; and `USERID` (L156) and `PASSWD` (L175) are this screen's two
 * controls. The anonymous fields carry the static screen text transcribed in the constants below.
 *
 * Assumptions: six anonymous fields are BMS plumbing with no target analogue and are dropped rather
 * than rendered — the zero-length attribute stoppers at `POS=(19,52)` (L161-L164) and `POS=(20,52)`
 * (L181-L184), the one-character `ATTRB=(DRK,UNPROT)` stopper at `POS=(20,61)` (L190-L193), and the
 * zero-length field at `POS=(20,63)` (L194-L196). None has an entry in `app/cpy-bms/COSGN00.CPY`, so
 * the reference program cannot read or write any of them: they exist to terminate the attribute run
 * of the field before them on a character-cell display. The drop is recorded because an absence of
 * four fields from a screen that claims 37 otherwise reads as an oversight.
 *
 * Assumptions: `CTRL=(ALARM,FREEKB)` at L19 rings the terminal alarm on every send. No web analogue
 * is added — an audible alert on a validation failure would be intrusive rather than faithful, and
 * the browser has no equivalent of the keyboard-lock that `FREEKB` releases.
 *
 * Message fidelity
 * ----------------
 * Assumptions: every sentence an operator reads here is a catalog constant from
 * `ui/src/messages/messages.ts`, taken verbatim from the originating program with its trailing
 * ellipsis and interior spacing intact, so the wording is the reference's and never this screen's.
 * The painted labels and the decoration are the other half of the split that module documents at
 * L153-L182: a screen's own field labels and its mapset decoration belong to the screen that renders
 * them, so they are declared here rather than catalogued centrally.
 *
 * Export surface
 * --------------
 * Assumptions: the component is exported BOTH ways deliberately, and neither is redundant.
 * `ui/src/router.tsx` L38 imports the NAME (`import { SignOnScreen } from './screens/signon'`), so
 * removing the named export unmounts the route; the default export is the shape a route element is
 * conventionally reached by, and it costs one line to satisfy both callers rather than forcing one of
 * them to change. The spelling is `SignOnScreen` with a capital `O` because that is the identifier the
 * router already imports — a screen whose name disagrees with its only consumer does not mount at all.
 *
 * Alternatives Considered: declaring the eleven transcribed constants and the four pure helpers
 * module-private instead of exported, since nothing outside this file imports them today. Rejected on
 * a specific ground rather than on taste: the constants ARE the transformation-rule-T8 transcription,
 * and the per-screen test tree the architecture places at `ui/src/test/**` cannot assert a verbatim
 * string it cannot import — it would have to retype `Type your User ID and Password, then press
 * ENTER:` and the nine 42-character banknote lines, which puts a byte-exact transcription in two
 * places and makes silent drift between them possible. The four helpers are exported for the narrower
 * reason that each encodes one reference decision worth asserting in isolation: the blank test that
 * treats an all-blanks field as empty, the refusal-to-sentence mapping, the per-control refusal split
 * and the cursor-destination choice. Exporting them keeps those assertable without a test having to
 * drive the whole screen to reach one branch.
 */

import { Button, Card, Flex, Form, Input, Space, Typography, theme } from 'antd';
import type { InputRef } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent, CSSProperties, ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { PASSWORD_MAX_LENGTH, SIGN_ON_CHALLENGE, USER_ID_MAX_LENGTH } from '../../api/auth';
import type { SignOnChallenge } from '../../api/auth';
import { isApiRequestError } from '../../api/client';
import type { ApiError, FieldError } from '../../api/types';
import { ADMIN_GROUP, groupsFromIdToken, useAuth } from '../../hooks/useAuth';
import { useServerInstant } from '../../hooks/useServerInstant';
import { MessageBand } from '../../layout/MessageBand';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { PfKeyBar } from '../../layout/PfKeyBar';
import { ScreenHeader } from '../../layout/ScreenHeader';
import { usePfKeys } from '../../layout/usePfKeys';
import { INVALID_KEY_PRESSED, PROGRAM_MESSAGES, THANK_YOU_CARDDEMO } from '../../messages/messages';
import { ADMIN_MENU_ROUTE, MAIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import { BMS_COLOR_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/** The five sign-on sentences, keyed by the program that emits them. */
const SIGN_ON_MESSAGES = PROGRAM_MESSAGES.COSGN00C;

/** CICS transaction identifier this screen answers to, `WS-TRANID` at `COSGN00C.cbl` L37. */
export const SIGN_ON_TRANSACTION_ID = 'CC00';

/** Reference program this screen carries across, `WS-PGMNAME` at `COSGN00C.cbl` L36. */
export const SIGN_ON_PROGRAM_NAME = 'COSGN00C';

/**
 * Mapset whose message-field width the band is sized to.
 *
 * Assumptions: `ERRMSG` is declared `LENGTH=78` at `app/bms/COSGN00.bms` L197 and `ERRMSGI PIC
 * X(78)` at `app/cpy-bms/COSGN00.CPY` L84, and the band resolves that width from this key rather
 * than from the 75-character `CCARD-ERROR-MSG` work area at `app/cpy/CVCRD01Y.cpy` L28. The two
 * differ and neither truncates anything here — every sentence this screen emits is at most 50
 * characters — so the key is passed to state the width the reference actually painted instead of
 * letting the band assume one.
 */
export const SIGN_ON_MAPSET = 'COSGN00';

/**
 * Row-5 introduction, verbatim from the `INITIAL=` operand at `app/bms/COSGN00.bms` L98-L99.
 *
 * Assumptions: `LENGTH=66` and `COLOR=NEUTRAL`. The literal is continued across two physical BMS
 * lines with a non-blank in column 72, so it reads `...for Main-` / `frame Modernization` in the
 * source and joins without a space — which is why it is transcribed here rather than recovered by a
 * line-oriented search of the mapset.
 */
export const SIGN_ON_INTRODUCTION =
  'This is a Credit Card Demo Application for Mainframe Modernization';

/**
 * Rows 7 to 15, the nine `LENGTH=42` `COLOR=BLUE` lines forming the reference screen's banknote.
 *
 * Assumptions: transcribed character for character from the `INITIAL=` operands at
 * `app/bms/COSGN00.bms` L104, L109, L114, L119, L124, L129, L134, L139 and L144. Nine lines, not the
 * eight that `ui/src/messages/messages.ts` L164 estimates in prose — rows 7 through 15 inclusive.
 * Each line is exactly 42 characters and every character is load-bearing, so the array is the
 * transcription unit rather than one joined string: joining would let a formatter or an editor
 * rewrap the art and lose the alignment silently.
 */
export const SIGN_ON_BANKNOTE_ART: readonly string[] = [
  '+========================================+',
  '|%%%%%%%  NATIONAL RESERVE NOTE  %%%%%%%%|',
  '|%(1)  THE UNITED STATES OF KICSLAND (1)%|',
  '|%$$              ___       ********  $$%|',
  '|%$    {x}       (o o)                 $%|',
  '|%$     ******  (  V  )      O N E     $%|',
  '|%(1)          ---m-m---             (1)%|',
  '|%%~~~~~~~~~~~ ONE DOLLAR ~~~~~~~~~~~~~%%|',
  '+========================================+',
];

/**
 * Row-17 instruction, verbatim from `app/bms/COSGN00.bms` L149-L150.
 *
 * Assumptions: `LENGTH=49` and `COLOR=TURQUOISE`. This is the second of the two literals on this
 * mapset that BMS continues across a line boundary, breaking mid-word at `ENTE-` / `R:`.
 */
export const SIGN_ON_PROMPT = 'Type your User ID and Password, then press ENTER:';

/**
 * The two painted field labels, verbatim from the `INITIAL=` operands that declare them.
 *
 * Assumptions: transcribed character for character including the interior padding and the trailing
 * colon, because Transformation Rule T8 carries user-visible text across unchanged. Both are
 * `LENGTH=13` and `COLOR=TURQUOISE`: `User ID     :` at `app/bms/COSGN00.bms` L155 pads with five
 * spaces and `Password    :` at L174 pads with four, which is what aligned both colons on column 41
 * of a character-cell display. The padding is preserved as transcription rather than as layout —
 * design-system gap G1 retires absolute positioning, so the colons align here because the label
 * text says so and not because a column is being reconstructed.
 */
export const SIGN_ON_FIELD_LABELS = {
  /** `app/bms/COSGN00.bms` L151-L155, `LENGTH=13`, `COLOR=TURQUOISE`. */
  userId: 'User ID     :',
  /** `app/bms/COSGN00.bms` L170-L174, `LENGTH=13`, `COLOR=TURQUOISE`. */
  password: 'Password    :',
} as const;

/**
 * Label for the replacement-password control the provider's challenge introduces.
 *
 * Alternatives Considered: reusing {@link SIGN_ON_FIELD_LABELS}.password, and transcribing a label
 * from another mapset. Both were rejected because the reference screen has no such field at all:
 * `USRSEC` stored one password and could not require a replacement, so there is no `INITIAL=`
 * operand to transcribe and no fidelity claim to make. The wording is therefore this screen's own,
 * and it is declared apart from the transcribed pair so that a reader cannot mistake an addition for
 * a transcription. It is spelled to remain distinguishable from the transcribed password label under
 * an accessible-name search, which is how the two controls stay separable in a test.
 */
export const SIGN_ON_NEW_PASSWORD_LABEL = 'New Password';

/**
 * The width hint painted beside each control, verbatim from `app/bms/COSGN00.bms` L169 and L189.
 *
 * Assumptions: `LENGTH=8` and `COLOR=BLUE`, painted once per control at `POS=(19,52)` and
 * `POS=(20,52)`. One constant serves both because the mapset declares the same eight characters
 * twice, and two constants holding one literal would be two places for it to drift.
 *
 * BLITZY [DESIGN_SOURCE_CONFLICT]: beside the IDENTIFIER this hint is still true -- that control is
 * capped at eight. Beside the CREDENTIAL it is not: that control is capped at
 * {@link PASSWORD_MAX_LENGTH} because the provider refuses anything under twelve characters, so the
 * painted hint now describes a bound the screen deliberately does not enforce.
 *
 * Trade-offs: the hint is nevertheless painted beside both controls, because the mapset is the
 * authoritative design source for this screen and it paints the hint twice -- the precedence rule is
 * to implement the source exactly and FLAG a value that reads wrongly, never to silently redraft it.
 * Suppressing it beside the credential was considered and rejected on a specific ground: it would
 * delete a transcribed string on the strength of a bound taken from a different layer, and the same
 * argument would then justify editing any other transcribed text whose subject the migration changed.
 * What is given up is that an operator reading the hint may believe an eight-character credential is
 * wanted; what is bought is that every one of the twenty-six transcribed strings on this screen is
 * traceable to a mapset operand without exception. This is flagged rather than resolved because the
 * resolution is a WORDING decision and wording is the designer's to make, not this screen's.
 */
export const SIGN_ON_FIELD_WIDTH_HINT = '(8 Char)';

/**
 * Function-key legend labels, split from the row-24 literal that declares them.
 *
 * Assumptions: `app/bms/COSGN00.bms` L201-L205 paints exactly `ENTER=Sign-on  F3=Exit` at
 * `LENGTH=22` in `COLOR=YELLOW`, with TWO spaces between the entries — 13 + 2 + 7 = 22 confirms the
 * declared width against the transcription. These two aids are therefore the complete key contract
 * for this screen and no third is bound; in particular the uniform `F4=Clear` that
 * `ui/src/layout/PfKeyBar.tsx` offers other screens is absent from this mapset, so binding it would
 * invent behaviour rather than migrate it.
 */
export const SIGN_ON_KEY_LABELS = {
  ENTER: 'ENTER=Sign-on',
  PFK03: 'F3=Exit',
} as const;

/**
 * Text of the explicit submit control.
 *
 * Alternatives Considered: painting no button at all and relying solely on the Enter key, which is
 * literally what the reference offered. Rejected because the terminal advertised its own submit
 * gesture on the legend row and a browser form does not: an operator who has never used the 3270
 * screen has no way to discover that Enter submits. The visible control and the key path run the
 * same function, so the addition is an affordance rather than a second behaviour, and its wording is
 * deliberately NOT the legend's `ENTER=Sign-on` — the legend names a key, this names an action.
 */
export const SIGN_ON_SUBMIT_LABEL = 'Sign on';

/*
 * WHY : Refactoring Rationale: this screen deliberately bounds its two controls ASYMMETRICALLY, and
 *       the asymmetry is the single most surprising thing about it. The identifier is capped at the
 *       reference width through `USER_ID_MAX_LENGTH`; the credential is capped at the SERVICE's bound
 *       through `PASSWORD_MAX_LENGTH`, not at the reference's `LENGTH=8`.
 *       An earlier revision of this file declared a local eight-character password bound, reasoning
 *       from the three places the reference states that width -- `LENGTH=8` on `PASSWD` at
 *       `app/bms/COSGN00.bms` L175, `02 PASSWDI PIC X(8).` at `app/cpy-bms/COSGN00.CPY` L78, and
 *       `05 SEC-USR-PWD PIC X(08).` at `app/cpy/CSUSR01Y.cpy` L21. The reasoning was wrong, and it was
 *       wrong in a way that a fidelity argument cannot see: all three declarations describe a field
 *       that THIS MIGRATION DOES NOT CARRY FORWARD. `auth.users` has no password column, the
 *       comparison at `app/cbl/COSGN00C.cbl` L223 has no successor, and the credential now travels
 *       only inside a request body on its way to the managed provider.
 *       The concrete consequence of capping it at eight is that sign-on becomes impossible, not merely
 *       stricter: `infra/modules/cognito/variables.tf` declares `password_minimum_length` with a
 *       default of 14 and refuses any configuration below 12, so no environment this repository can
 *       express accepts a credential of eight characters. An eight-character control admits ONLY
 *       credentials the provider is guaranteed to reject. The service's own
 *       `SignOnRequest` makes exactly this argument and bounds the property at 256 for exactly this
 *       reason, and `ui/src/api/auth.ts` exports that number stating it is for this input, so the
 *       screen matches the contract it posts to rather than a copybook the contract has retired.
 *       The divergence is of a piece with `D-SIGNON-CASE-SENSITIVE-PASSWORD` recorded in
 *       {@link SignOnScreen}'s submission helper: both follow from the credential having no successor
 *       in the target, so both decline a reference constraint on a field that no longer exists.
 */

/**
 * The two controls this screen owns, named as the sign-on contract names them.
 *
 * Assumptions: the two spellings are exactly the members of `SignOnRequest`, whose own field order
 * is declared `List.of("userId", "password")` in
 * `services/auth-service/src/main/java/com/carddemo/auth/dto/SignOnRequest.java`. Sharing the
 * spelling is what lets a `FieldError` from the problem document address a control without a
 * translation table between the wire name and the screen name.
 */
const SIGN_ON_FIELDS = ['userId', 'password'] as const;

/** One of the two controls this screen owns. */
type SignOnField = (typeof SIGN_ON_FIELDS)[number];

/** Identifier of the operator-identifier control, declared so its label can be associated with it. */
const USER_ID_FIELD_ID = 'signon-user-id';

/**
 * Identifier of the password control, whether it currently holds the current or the replacement one.
 *
 * Assumptions: one identifier serves both because the two are mutually exclusive renderings of the
 * same row — the challenge replaces the password control rather than adding a second one — so the
 * document never carries two elements with this identifier.
 */
const PASSWORD_FIELD_ID = 'signon-password';

/** A sentence to show in the band together with the severity it is shown at. */
interface ScreenMessage {
  /** The sentence itself, always a catalog constant or a sentence a service composed. */
  readonly text: string;
  /** Severity the band renders the sentence at. */
  readonly severity: MessageBandSeverity;
}

/** Per-control refusals rendered on the controls themselves rather than in the band. */
type FieldRefusals = Readonly<Partial<Record<SignOnField, string>>>;

/**
 * A pending cursor move, carrying the control to move to and a value that makes each request unique.
 *
 * Assumptions: the nonce exists so that two consecutive refusals naming the SAME control are two
 * distinct requests. Without it a repeated refusal would record an equal value, the effect that
 * performs the move would not re-run, and an operator who submitted a blank identifier twice would
 * see the cursor move only the first time.
 */
interface FocusRequest {
  /** The control the cursor is to move to. */
  readonly field: SignOnField;
  /** Monotonic value distinguishing this request from an identical earlier one. */
  readonly nonce: number;
}

/*
 * WHY : Alternatives Considered: the four helpers below are written WITHOUT guard clauses of the
 *       ordinary `if (...) { return ...; }` form, using conditional expressions instead. That is not a
 *       style preference and it must not be "simplified" back. A guard clause here would place a line
 *       matching `/^ {2}if \(/` above {@link SignOnScreen}, and
 *       `ui/src/layout/screenHeaderClock.test.tsx` uses exactly that pattern to locate a screen's
 *       first COMPONENT-level early return before asserting that `useServerInstant` is called above
 *       it. Its stated assumption is that a two-space `if (` is component-body level, so a
 *       module-level helper carrying one makes that search find the helper instead of the component
 *       and fails a rules-of-hooks assertion with a message naming a hook these helpers never call.
 *       `ui/src/screens/cardDetail/index.tsx` records the same hazard for the same reason. An
 *       expression has no `if` to find, so the helpers stay above their call sites in ordinary reading
 *       order without disturbing that contract. Trade-offs: a conditional expression is marginally
 *       denser than a guard clause; the alternative was moving the helpers below the component, which
 *       would put the definitions after their use and read worse than the density costs.
 */

/**
 * Reports whether an entered value counts as absent.
 *
 * Assumptions: a value of nothing but blanks is absent, because the reference compares the received
 * field against `SPACES` — `WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES` at
 * `app/cbl/COSGN00C.cbl` L118 — and a 3270 field the operator filled with the space bar arrives as
 * exactly that. Testing only for an empty string would send a request the reference never sent, and
 * the service would then answer a refusal where the reference answered a prompt.
 * @param {string} value - The value as entered in the control.
 * @returns {boolean} `true` when the value is empty or consists only of blanks.
 */
export function isBlankEntry(value: string): boolean {
  return value.trim().length === 0;
}

/**
 * Reports whether a problem document's field name addresses one of this screen's controls.
 * @param {string} name - Field name as the problem document spells it.
 * @returns {boolean} `true` when the name is one of the two controls this screen renders.
 */
function isSignOnField(name: string): name is SignOnField {
  return (SIGN_ON_FIELDS as readonly string[]).includes(name);
}

/**
 * Chooses the screen-level sentence a refused exchange reports.
 *
 * Assumptions: the sentence is taken from the problem document rather than composed here, because
 * the service is what decides which of the reference's three refusals applies. `ui/src/api/auth.ts`
 * states the contract directly — the three verbatim sentences arrive as the `message` of a problem
 * document on a 401 and "a caller renders that message unchanged under transformation rule T8" — so
 * `Wrong Password. Try again ...` (`app/cbl/COSGN00C.cbl` L242) and `User not found. Try again ...`
 * (L249) reach the operator because the service sent them, not because this screen guessed between
 * them. Guessing is precisely what a client must not do: choosing between the two requires knowing
 * whether the identifier exists, which is the fact the refusal is designed not to disclose.
 *
 * Assumptions: the fallback is `Unable to verify the User ...` (L254), which is the reference's own
 * `WHEN OTHER` arm — the sentence it shows for any response it cannot classify. A refusal that
 * carries no problem document at all, such as a transport failure, is exactly that case, so the
 * fallback is a transcription rather than an invention.
 * @param {unknown} failure - Whatever the sign-on or challenge call rejected with.
 * @returns {string} The sentence to render in the band, verbatim from the service or from the
 *   catalog when the refusal carried none.
 */
export function signOnFailureMessage(failure: unknown): string {
  // Assumptions: the problem shape is named from `ui/src/api/types.ts` rather than inferred, so this
  //   mapping site states the contract it reads. `message` is `string | null` there, and the null case
  //   is a document that reported a code without a sentence -- the fallback's case, not a reason to
  //   render an empty band.
  const problem: ApiError | null = isApiRequestError(failure) ? failure.problem : null;
  const reported = problem?.message ?? null;

  return reported !== null && !isBlankEntry(reported)
    ? reported
    : SIGN_ON_MESSAGES.UNABLE_TO_VERIFY_THE_USER;
}

/**
 * Maps a refusal's per-field entries onto the controls they name.
 *
 * Assumptions: this mapping lives in the screen because it cannot live in the band.
 * `ui/src/layout/MessageBand.tsx` takes a sentence and a severity and deliberately refuses an
 * `ApiError`, on the stated ground that per-field text belongs to `Form.Item validateStatus="error"`
 * — so teaching the band about the API layer was rejected upstream, and the screen that awaited the
 * call is the only component holding both the refusal and the controls it addresses.
 *
 * Assumptions: an entry naming anything other than this screen's two controls is dropped rather than
 * surfaced somewhere arbitrary. The band already carries the screen-level sentence for that refusal,
 * so nothing is lost, whereas attaching an unrecognised field's text to a control would label the
 * wrong input.
 * @param {unknown} failure - Whatever the sign-on or challenge call rejected with.
 * @returns {FieldRefusals} Refusal text per addressed control, empty when the refusal carried no
 *   problem document or named no control this screen renders.
 */
export function signOnFieldRefusals(failure: unknown): FieldRefusals {
  // Assumptions: the array is named as `readonly FieldError[]` from `ui/src/api/types.ts` rather than
  //   left inferred, so the members read below -- `field` and `message` -- are checked against the
  //   published contract at this site instead of wherever the inference happened to lead.
  const reported: readonly FieldError[] = isApiRequestError(failure)
    ? failure.problem.fieldErrors
    : [];
  const refusals: Partial<Record<SignOnField, string>> = {};
  for (const fieldError of reported) {
    if (isSignOnField(fieldError.field)) {
      refusals[fieldError.field] = fieldError.message;
    }
  }

  return refusals;
}

/**
 * Chooses the control the reference program pointed the cursor at for a given refusal.
 *
 * Assumptions: cursor placement is this screen's ONLY field-level feedback, and that is a property of
 * the reference rather than a simplification. The `COPY` list at `app/cbl/COSGN00C.cbl` L48-L58 names
 * `COCOM01Y`, `COSGN00`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y`, `DFHAID` and `DFHBMSCA` —
 * it does NOT include `CSSETATY`, so the templated red-highlight-and-asterisk contract at
 * `app/cpy/CSSETATY.cpy` L17-L27 is not applied on this screen and there is no highlight to
 * reproduce. What the program does instead is move −1 into the length field of the control it wants
 * the cursor in: `PASSWDL` for a wrong password at L244, and `USERIDL` for an unknown user at L250
 * and for an unclassified response at L255.
 *
 * Assumptions: the branch is decided by comparing against the catalog constant rather than by
 * inspecting the status, because the service does not disclose which of the two credentials failed
 * and the sentence it sends is the only thing that distinguishes them. Comparing against the constant
 * — not against a literal typed here — means a catalog entry that ever drifted from the baseline
 * would move the cursor wrongly rather than silently agree with the drift.
 * @param {string} message - The sentence about to be shown in the band.
 * @returns {SignOnField} The control to place the cursor in.
 */
export function signOnFocusTarget(message: string): SignOnField {
  return message === SIGN_ON_MESSAGES.WRONG_PASSWORD_TRY_AGAIN ? 'password' : 'userId';
}

/**
 * Renders the sign-on screen migrated from `COSGN00C`.
 *
 * Assumptions: the component mounts in the reference's own first-entry state — both controls empty,
 * no message and the cursor in the identifier — which is `IF EIBCALEN = 0` at `app/cbl/COSGN00C.cbl`
 * L80-L83 moving `LOW-VALUES` to the output map and −1 to `USERIDL`. There is no first-entry versus
 * re-entry distinction to make: `CDEMO-PGM-CONTEXT` and its `88 CDEMO-PGM-ENTER` / `88
 * CDEMO-PGM-REENTER` values at `app/cpy/COCOM01Y.cpy` L29-L31 have no target at all, so no state
 * here stands in for them.
 *
 * Assumptions: neither `APPLID` nor `SYSID` is rendered. Alternatives Considered: surfacing a
 * synthetic region identifier in their place, so the header keeps the shape the 3270 screen had.
 * Rejected because the values have no cloud analogue to synthesise from — `EXEC CICS ASSIGN APPLID` /
 * `SYSID` at L198-L204 name a CICS region and a system that no longer exist — and because
 * `ui/src/layout/ScreenHeader.tsx` already owns that disposition in its `RETIRED_HEADER_FIELDS`
 * register. Deciding it a second time here would let the two disagree.
 * @returns {ReactElement} The sign-on screen: the shared header band, the message band, the
 *   transcribed screen body with its two controls, and the function-key bar.
 */
export function SignOnScreen(): ReactElement {
  const navigate = useNavigate();
  const { signIn, answerChallenge, signOut } = useAuth();
  /*
   * WHY : Assumptions: every design value on this screen is read as a token NAME from
   *       ui/src/theme/tokens.ts and resolved through `cssVar`, never written as a colour or a font
   *       literal. `cssVar` yields the reference form -- `var(--ant-color-info)` -- so each element
   *       keeps following the theme antd 6 installs through CSS variables, whereas the sibling
   *       `token` member of the same hook returns a RESOLVED value and would bake today's palette
   *       into these elements and opt them out of later theme changes with nothing failing to say so.
   */
  const { cssVar } = theme.useToken();
  // WHY : Assumptions: read here, above every conditional, because the rules of hooks require an
  //       unconditional call site. Reading it during render is deliberate: the band shows a
  //       PAINT-time instant, which is the property the reference had because `POPULATE-HEADER-INFO`
  //       re-read the clock on each `SEND MAP` (`FUNCTION CURRENT-DATE` at L179) rather than on a
  //       timer.
  const paintedAt = useServerInstant();
  const [message, setMessage] = useState<ScreenMessage | null>(null);
  const [refusals, setRefusals] = useState<FieldRefusals>({});
  const [busy, setBusy] = useState(false);
  const [challenge, setChallenge] = useState<SignOnChallenge | null>(null);
  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');

  /*
   * WHY : Assumptions: two stable refs rather than one callback-ref registry, because the cursor
   *       destination is a fixed pair on this screen and a callback ref recreated each render detaches
   *       and reattaches the control on every one of them. A registry earns that churn on a screen
   *       with a variable field list; here it would buy nothing.
   */
  const userIdControl = useRef<InputRef | null>(null);
  const passwordControl = useRef<InputRef | null>(null);

  /*
   * WHY : Assumptions: the counter lives in a ref rather than in the requested value itself, so
   *       {@link focusField} can build a distinct request without reading the previous one through a
   *       state updater. A ref is the right home because the number is bookkeeping that no render
   *       displays.
   */
  const focusNonce = useRef(0);
  const [focusRequest, setFocusRequest] = useState<FocusRequest | null>(null);

  useEffect(
    /**
     * Moves the cursor to the control the most recent refusal named.
     *
     * Assumptions: the control is focused only if it is currently mounted. A request can name the
     * password control at the moment a challenge replaces it, and a request can name the identifier
     * while a challenge has it disabled, so an absent ref means the request no longer has a target
     * and is dropped rather than retried.
     * @returns {void} Nothing; the browser's focus is the outcome.
     */
    function moveCursorToRequestedField(): void {
      if (focusRequest === null) {
        return;
      }

      const control =
        focusRequest.field === 'userId' ? userIdControl.current : passwordControl.current;
      control?.focus();
    },
    [focusRequest],
  );

  /**
   * Requests that the cursor move to one of the two controls.
   *
   * Assumptions: this is the migration of `MOVE -1 TO <field>L`, which is the ONLY field-level
   * feedback this screen has — see {@link signOnFocusTarget} for why there is no highlight to
   * reproduce.
   *
   * Refactoring Rationale: the move is REQUESTED here and performed by the effect below, where an
   * earlier revision called `focus()` directly. Two reasons, one correctness and one observed. The
   * correctness one is ordering: a refusal can change which control is mounted and can add an error
   * state to the one that stays, and focusing from inside the event handler runs BEFORE React has
   * committed either — so the call either focused a control that was about to be replaced or focused
   * the right one before it had the state the operator is being pointed at. The observed one is that
   * moving focus synchronously inside a click dispatch re-entered the event sequence and left the
   * click never settling: driving the two blank-field refusals exceeded a five-second test timeout,
   * while every other path passed. Deferring to the commit fixes both, and the nonce is what makes a
   * second refusal on the SAME control request a fresh move rather than being coalesced away as an
   * unchanged value.
   * @param {SignOnField} field - The control to place the cursor in.
   * @returns {void} Nothing; the recorded request is the outcome, and the effect below performs it.
   */
  function focusField(field: SignOnField): void {
    focusNonce.current += 1;
    setFocusRequest({ field, nonce: focusNonce.current });
  }

  /**
   * Reports a client-side refusal in the band and points the cursor at the control that caused it.
   *
   * Assumptions: no per-control text is attached for these two refusals, and the omission is the
   * reference's. `CSSETATY` is not copied by this program, so a blank field produces a band sentence
   * and a cursor move and nothing else; attaching `help` text here would invent the highlight the
   * screen never had. The per-control channel is reserved for a refusal the SERVICE addressed to a
   * field — see {@link signOnFieldRefusals}.
   * @param {SignOnField} field - The control the cursor moves to.
   * @param {string} text - The catalog sentence to show in the band.
   * @returns {void} Nothing; the band, the per-control refusals and the focus carry the outcome.
   */
  function refuseEntry(field: SignOnField, text: string): void {
    setMessage({ text, severity: 'error' });
    setRefusals({});
    focusField(field);
  }

  /**
   * Reports a refused exchange, discarding the credential that was refused.
   *
   * Assumptions: the password is cleared here rather than left in the control. The reference re-sends
   * the map with `ERASE` on every refusal (L151-L157), so the 3270 field was blank again on the next
   * turn; and the credential must not outlive the exchange it was submitted for, which is why it is
   * dropped from component state at the first moment it is no longer needed. It is never echoed back
   * from a response either — that is what the reference's own update screen did, moving `SEC-USR-PWD`
   * into the map at `app/cbl/COUSR02C.cbl` L169, and no response this screen reads carries a password
   * at all.
   * @param {unknown} failure - Whatever the sign-on or challenge call rejected with.
   * @returns {void} Nothing; the band, the per-control refusals and the focus carry the outcome.
   */
  function reportRefusal(failure: unknown): void {
    const text = signOnFailureMessage(failure);
    setMessage({ text, severity: 'error' });
    setRefusals(signOnFieldRefusals(failure));
    setPassword('');
    focusField(signOnFocusTarget(text));
  }

  /**
   * Routes a signed-on operator to the surface their group claim admits.
   *
   * Assumptions: the destination is chosen from the SIGNED claim and never from a member of the
   * response body. This is the stateless replacement for `IF CDEMO-USRTYP-ADMIN` at
   * `app/cbl/COSGN00C.cbl` L230, which transfers to `COADM01C` or `COMEN01C` on a user type the
   * program had just moved out of the security record into the communication area at L227 — storage
   * the terminal echoed back on every subsequent turn, so a client could in principle have asserted
   * its own user type. `ui/src/api/auth.ts` records the consequent rule: no response type carries an
   * authoritative user type, and `useAuth` exposes no setter for the groups.
   *
   * Refactoring Rationale: the claim is decoded from the identity token passed in, NOT from the
   * hook's `isAdmin`. Both derive from the same claim, but the hook's copy is a render-time value and
   * this runs inside the promise that installed the token, before React has re-rendered — so
   * `isAdmin` still describes the previous session, and reading it sent every administrator to the
   * ordinary menu. `ui/src/hooks/useAuth.ts` exports the decoder for exactly this reason.
   * @param {string} idToken - Identity token issued moments ago by sign-on or by a challenge answer.
   * @returns {void} Nothing; the router navigation is the outcome.
   */
  function enterApplication(idToken: string): void {
    setPassword('');
    setNewPassword('');
    const admin = groupsFromIdToken(idToken).includes(ADMIN_GROUP);
    navigateSafely(navigate, admin ? ADMIN_MENU_ROUTE : MAIN_MENU_ROUTE);
  }

  /**
   * Validates both controls and exchanges their values for a token set.
   *
   * Assumptions: the two blank checks run in the reference's own order and short-circuit, because the
   * order is observable. `EVALUATE TRUE` at L117-L130 tests the identifier first and the password
   * second, so an operator who leaves BOTH blank is told about the identifier alone and never sees
   * the password sentence. Reporting both, or testing the password first, would be a behavioural
   * change rather than a tidier presentation.
   *
   * Assumptions: no request is sent when either check fails, matching `IF NOT ERR-FLG-ON` at
   * L138-L140, which is what gates the keyed read on the error flag being off.
   *
   * Assumptions: the identifier is folded to upper case and trimmed before submission; the password
   * is NOT folded. The reference folds both — `FUNCTION UPPER-CASE` at L132-L134 for the identifier
   * and L135-L136 for the credential — but only the identifier's fold has a successor. The
   * credential's fold existed to serve the direct comparison at L223, which no longer exists, and the
   * pool that performs the comparison now is case-sensitive: folding would refuse a mixed-case
   * credential that the characters as typed would have been accepted for. This is the registered
   * divergence `D-SIGNON-CASE-SENSITIVE-PASSWORD` in
   * `docs/architecture/cobol-to-service-traceability.md`, and
   * `services/auth-service/.../CognitoIdentityService.java` folds the identifier alone on the same
   * ground, so the client matches the service rather than contradicting it.
   *
   * Trade-offs: the fold is applied at submission rather than as the operator types. Folding in the
   * control would show the operator the value that will actually be sent, at the cost of rewriting
   * the field on every keystroke — which moves the caret to the end and makes mid-value correction
   * impossible. Submitting the folded value keeps both properties that matter: the request carries
   * what the service keys on, and the field stays editable.
   * @returns {Promise<void>} Resolves once the outcome has been applied to screen state. It does not
   *   reject: every refusal is converted into a band sentence by {@link reportRefusal}.
   */
  async function submitSignOn(): Promise<void> {
    if (isBlankEntry(userId)) {
      refuseEntry('userId', SIGN_ON_MESSAGES.PLEASE_ENTER_USER_ID);
      return;
    }
    if (isBlankEntry(password)) {
      refuseEntry('password', SIGN_ON_MESSAGES.PLEASE_ENTER_PASSWORD);
      return;
    }

    setBusy(true);
    setMessage(null);
    setRefusals({});
    try {
      const outcome = await signIn(userId.trim().toUpperCase(), password);
      if (outcome.outcome === SIGN_ON_CHALLENGE) {
        setChallenge(outcome);
        // Assumptions: the accepted credential is dropped as soon as the provider has consumed it,
        //   so the replacement it is now asking for is entered into an empty control.
        setPassword('');
        focusField('password');
        return;
      }
      enterApplication(outcome.idToken);
    } catch (failure: unknown) {
      reportRefusal(failure);
    } finally {
      setBusy(false);
    }
  }

  /**
   * Answers an outstanding challenge with a replacement password.
   *
   * Assumptions: the replacement is submitted exactly as typed — neither folded nor trimmed. It is a
   * credential being SET rather than one being compared, so altering it would silently store
   * something other than what the operator chose; and the reference has no challenge at all, so there
   * is no `FUNCTION UPPER-CASE` here to transcribe.
   *
   * Assumptions: a blank replacement is refused with the reference's own password prompt rather than
   * a sentence composed here. The catalog holds no challenge-specific text because the reference
   * emitted none, and `Please enter Password ...` is the sentence it uses for exactly this condition
   * — an empty password control.
   * @returns {Promise<void>} Resolves once the outcome has been applied to screen state. It does not
   *   reject: every refusal is converted into a band sentence by {@link reportRefusal}.
   */
  async function submitChallenge(): Promise<void> {
    if (challenge === null) {
      return;
    }
    if (isBlankEntry(newPassword)) {
      refuseEntry('password', SIGN_ON_MESSAGES.PLEASE_ENTER_PASSWORD);
      return;
    }

    setBusy(true);
    setMessage(null);
    setRefusals({});
    try {
      const tokens = await answerChallenge(challenge, newPassword);
      enterApplication(tokens.idToken);
    } catch (failure: unknown) {
      reportRefusal(failure);
    } finally {
      setBusy(false);
    }
  }

  /**
   * Ends the session and reports the reference program's own farewell.
   *
   * Assumptions: this is the migration of `WHEN DFHPF3` at L88-L90, and the sentence is the `PIC
   * X(50)` `CCDA-MSG-THANK-YOU` from `app/cpy/CSMSG01Y.cpy` L18-L19 — the one naming "CardDemo" —
   * because L89 is what selects it. The catalog holds a second, similar sentence: the `PIC X(40)`
   * `CCDA-THANK-YOU` at `app/cpy/COTTL01Y.cpy` L23-L24, which names "CCDA" and is exported as
   * `THANK_YOU_CCDA`. The two differ in both wording and declared width and are separate entries;
   * selecting the wrong one is a byte-level parity failure, which is why the constant is named here
   * with the copybook it was read from.
   *
   * Assumptions: exit does NOT navigate, although the legend reads `F3=Exit`. `SEND-PLAIN-TEXT` at
   * L162-L172 sends text with `ERASE FREEKB` and then issues a BARE `EXEC CICS RETURN` — no
   * `TRANSID`, no `COMMAREA` — which terminates the transaction and leaves the farewell on an
   * otherwise cleared terminal. There is no screen to route to, so the browser equivalent is to
   * discard the session and the entered values and leave the operator on a screen they can sign on
   * from again.
   *
   * Assumptions: the session is discarded through `useAuth`'s own `signOut` rather than by clearing
   * storage here, so the four session-storage slots it owns stay owned by one module.
   * @returns {void} Nothing; the cleared controls, the band sentence and the discarded session carry
   *   the outcome.
   */
  function exitApplication(): void {
    setUserId('');
    setPassword('');
    setNewPassword('');
    setChallenge(null);
    setRefusals({});
    // Assumptions: shown at `info` rather than `error`. The reference reaches this through
    //   `SEND TEXT` and not through the red `ERRMSG` field, so it is an outcome and not a refusal.
    setMessage({ text: THANK_YOU_CARDDEMO, severity: 'info' });
    signOut();
  }

  /**
   * Runs whichever submission the visible form represents.
   *
   * Assumptions: the challenge form replaces the sign-on form rather than sitting beside it, so one
   * dispatcher serves both the Enter key and the submit control and the visible state decides which
   * exchange runs.
   *
   * Assumptions: a submission already in flight is dropped rather than queued. Trade-offs: the guard
   * is here instead of on the key binding because a binding marked `disabled` reports
   * `CCDA-MSG-INVALID-KEY` through `onInvalidKey`, which would tell an operator their Enter key was
   * invalid while their sign-on was succeeding.
   * @returns {void} Nothing; the submission's own state changes carry the outcome.
   */
  function runSubmit(): void {
    if (busy) {
      return;
    }

    const submission = challenge === null ? submitSignOn() : submitChallenge();
    submission.catch(
      /**
       * Reports a refusal that the submission's own handler did not convert.
       *
       * Assumptions: both submissions convert every refusal into a band sentence already, so this
       * runs only on a defect in that conversion. It still reports rather than being empty, because
       * an empty rejection handler is how a real fault becomes invisible.
       * @param {unknown} failure - Whatever escaped the submission's own handling.
       * @returns {void} Nothing; the band and the cleared busy flag carry the outcome.
       */
      (failure: unknown): void => {
        reportRefusal(failure);
        setBusy(false);
      },
    );
  }

  /*
   * WHY : Assumptions: the map is keyed by CICS attention identifier rather than by a browser key
   *       name, so the PF13-PF24 aliasing at `app/cpy/CSSTRPFY.cpy` L54-L77 is applied once by the
   *       hook instead of by every screen. Only the two aids this mapset paints are bound: Enter
   *       signs on and PF03 exits.
   * WHY : Trade-offs: this screen therefore INHERITS that aliasing although its own program does not
   *       implement it. `COSGN00C` evaluates `EIBAID` directly at L85-L95, so `DFHPF15` would fall to
   *       its `WHEN OTHER` arm and report an invalid key, whereas here Shift+F3 behaves as F3. The
   *       divergence is accepted deliberately: the aliasing is an application-wide contract the
   *       copybook states, and suppressing it on this one screen would make one screen answer a
   *       shifted function key differently from the other twenty. It is recorded so a reader does not
   *       mistake the inherited behaviour for a defect.
   */
  const { bindings, invoke } = usePfKeys(
    {
      ENTER: {
        /**
         * Submits the visible form, matching the reference's Enter arm at L86-L87.
         * @returns {void} Nothing; the submission's own state changes carry the outcome.
         */
        onInvoke: (): void => {
          runSubmit();
        },
        label: SIGN_ON_KEY_LABELS.ENTER,
      },
      PFK03: {
        /**
         * Ends the session with the reference program's farewell, matching its PF3 arm at L88-L90.
         * @returns {void} Nothing; {@link exitApplication} carries the outcome.
         */
        onInvoke: (): void => {
          exitApplication();
        },
        label: SIGN_ON_KEY_LABELS.PFK03,
      },
    },
    {
      /**
       * Reports the reference program's own invalid-key sentence for an unbound key.
       *
       * Assumptions: the sentence is not composed here and the condition is not detected here. The
       * hook recognises an attention identifier this screen did not bind and reports it, which is
       * what `WHEN OTHER` at L91-L94 does — set the error flag, move `CCDA-MSG-INVALID-KEY` into the
       * message field and re-send the screen. All this callback supplies is the band to put it in.
       * @returns {void} Nothing; the band carries the outcome.
       */
      onInvalidKey: (): void => {
        setMessage({ text: INVALID_KEY_PRESSED, severity: 'error' });
      },
    },
  );

  /**
   * Records the identifier as entered.
   *
   * Assumptions: the value is stored unfolded. The fold belongs to submission — see
   * {@link submitSignOn} for why rewriting the control on each keystroke was rejected.
   * @param {ChangeEvent<HTMLInputElement>} event - Change event from the identifier control.
   * @returns {void} Nothing; the recorded value is the outcome.
   */
  function handleUserIdChange(event: ChangeEvent<HTMLInputElement>): void {
    setUserId(event.target.value);
  }

  /**
   * Records the password as entered.
   * @param {ChangeEvent<HTMLInputElement>} event - Change event from the password control.
   * @returns {void} Nothing; the recorded value is the outcome.
   */
  function handlePasswordChange(event: ChangeEvent<HTMLInputElement>): void {
    setPassword(event.target.value);
  }

  /**
   * Records the replacement password answering an outstanding challenge.
   * @param {ChangeEvent<HTMLInputElement>} event - Change event from the replacement control.
   * @returns {void} Nothing; the recorded value is the outcome.
   */
  function handleNewPasswordChange(event: ChangeEvent<HTMLInputElement>): void {
    setNewPassword(event.target.value);
  }

  const introductionStyle: CSSProperties = { color: cssVar[BMS_COLOR_TOKENS.NEUTRAL] };
  const promptStyle: CSSProperties = { color: cssVar[BMS_COLOR_TOKENS.TURQUOISE] };
  const labelStyle: CSSProperties = { color: cssVar[BMS_COLOR_TOKENS.TURQUOISE] };
  const hintStyle: CSSProperties = { color: cssVar[BMS_COLOR_TOKENS.BLUE] };
  const banknoteStyle: CSSProperties = {
    color: cssVar[BMS_COLOR_TOKENS.BLUE],
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
    /*
     * WHY : Assumptions: `pre` is a structural keyword and not a design value, so it is written
     *       directly rather than read from a token. It is required: each of the nine lines carries
     *       runs of interior spaces that position the art's glyphs, and the default white-space
     *       handling collapses every run to one space, which closes the note's borders onto its
     *       contents. The fixed-pitch face above is the other half of the same requirement -- a
     *       proportional face keeps the space count and still loses the column alignment.
     */
    whiteSpace: 'pre',
  };

  return (
    <Flex vertical gap="large">
      <ScreenHeader
        transactionId={SIGN_ON_TRANSACTION_ID}
        programName={SIGN_ON_PROGRAM_NAME}
        now={paintedAt}
      />
      {/*
       * WHY : Assumptions: the band receives a sentence and a severity, never the refusal itself.
       *       `ui/src/layout/MessageBand.tsx` states that it does not accept an `ApiError` and that
       *       callers do the mapping, so {@link signOnFailureMessage} and
       *       {@link signOnFieldRefusals} above are that mapping. The severity defaults to `error`
       *       when there is no message because the band's row is reserved either way and the value is
       *       then unused; row 23 of the mapset is `COLOR=RED` with `ATTRB=BRT`, which the band
       *       resolves to the error colour and the strong weight.
       */}
      <MessageBand
        message={message?.text ?? null}
        severity={message?.severity ?? 'error'}
        mapset={SIGN_ON_MAPSET}
      />
      {/*
       * WHY : Alternatives Considered: `Typography.Paragraph`, which is the obvious element for a
       *       sentence. Rejected because row 5 is a single `LENGTH=66` field rather than block prose,
       *       and because `Paragraph` carries the ellipsis-measurement machinery this line has no use
       *       for -- machinery that costs a DOM measurement per render in exchange for nothing here.
       */}
      <Typography.Text style={introductionStyle}>{SIGN_ON_INTRODUCTION}</Typography.Text>
      {/*
       * WHY : Trade-offs: the banknote is hidden from assistive technology. It is pure decoration --
       *       `ui/src/messages/messages.ts` L163-L164 classifies it as exactly that -- and announcing
       *       nine rows of forty-two punctuation characters would take a screen-reader user through
       *       roughly 378 spoken symbols before reaching the sign-on prompt, which is hostile. What is
       *       given up is that such a user is not told the screen carries an illustration; the
       *       alternative of describing it in a label was rejected because a description is text the
       *       reference never painted, and inventing prose is a worse fidelity loss than omitting
       *       decoration.
       * WHY : Refactoring Rationale: the nine lines are joined into ONE element here, where an earlier
       *       revision rendered one element per line inside a `Flex`. The transcription is unchanged --
       *       {@link SIGN_ON_BANKNOTE_ART} is still nine separately-addressable strings, which is
       *       where the line-by-line comparison against the mapset lives -- but the DOM does not need
       *       to mirror that. Nine design-system text nodes plus their measurement work made this
       *       screen roughly ten times slower to render and to drive, to the point that typing into a
       *       control and clicking a button exceeded a five-second test timeout. A newline-joined
       *       string under `white-space: pre` renders identically for decoration nobody reads.
       */}
      <Typography.Text aria-hidden="true" style={banknoteStyle}>
        {SIGN_ON_BANKNOTE_ART.join('\n')}
      </Typography.Text>
      <Typography.Text style={promptStyle}>{SIGN_ON_PROMPT}</Typography.Text>
      <Card>
        <Form layout="vertical">
          <Form.Item
            label={
              <Typography.Text style={labelStyle}>{SIGN_ON_FIELD_LABELS.userId}</Typography.Text>
            }
            htmlFor={USER_ID_FIELD_ID}
            extra={<Typography.Text style={hintStyle}>{SIGN_ON_FIELD_WIDTH_HINT}</Typography.Text>}
            {...(refusals.userId === undefined
              ? {}
              : { validateStatus: 'error' as const, help: refusals.userId })}
          >
            {/*
             * WHY : Assumptions: `autoFocus` is on this control and on NO other, because `IC` occurs
             *       exactly once in the whole mapset -- on `USERID` at L156 -- and the program
             *       corroborates it by moving −1 into `USERIDL` on first entry at L82. A second
             *       autofocused control would leave which one wins to render order.
             * WHY : Assumptions: `maxLength` comes from the shared `USER_ID_MAX_LENGTH`, which
             *       `ui/src/api/auth.ts` exports for this control specifically. It is 8, from
             *       `LENGTH=8` at L156, `02 USERIDI PIC X(8).` at `app/cpy-bms/COSGN00.CPY` L72 and
             *       `10 CDEMO-USER-ID PIC X(08).` at `app/cpy/COCOM01Y.cpy` L25 -- three declarations
             *       that agree, so the 3270 field width survives into the browser as a contract
             *       rather than as a re-derived guess.
             */}
            {/*
             * WHY : Assumptions: `autoComplete` is additive -- the terminal had no concept of a
             *       credential manager -- and it is applied because it is INVISIBLE accessibility: it
             *       changes no rendered pixel, and without it the browser cannot offer the stored
             *       identifier and emits a DOM advisory naming its absence. `username` is the value
             *       the specification defines for the account-identifier field of a sign-on form.
             */}
            <Input
              id={USER_ID_FIELD_ID}
              ref={userIdControl}
              autoFocus
              autoComplete="username"
              maxLength={USER_ID_MAX_LENGTH}
              value={userId}
              disabled={challenge !== null}
              onChange={handleUserIdChange}
            />
          </Form.Item>
          {challenge === null ? (
            <Form.Item
              label={
                <Typography.Text style={labelStyle}>
                  {SIGN_ON_FIELD_LABELS.password}
                </Typography.Text>
              }
              htmlFor={PASSWORD_FIELD_ID}
              extra={
                <Typography.Text style={hintStyle}>{SIGN_ON_FIELD_WIDTH_HINT}</Typography.Text>
              }
              {...(refusals.password === undefined
                ? {}
                : { validateStatus: 'error' as const, help: refusals.password })}
            >
              {/*
               * WHY : Trade-offs: a 3270 non-display field renders TRULY BLANK and this renders one
               *       dot per character. `PASSWD` at L175 is `ATTRB=(DRK,FSET,UNPROT)` -- an operand
               *       combination that occurs exactly three times in the application, here and on the
               *       two user-maintenance mapsets -- and this is documented design-system gap G2. The
               *       divergence is accepted for a specific mechanism rather than on taste: the dots
               *       confirm that each keystroke was ACCEPTED while revealing none of the characters,
               *       so an operator whose keyboard drops a key sees the count disagree with what they
               *       typed and can correct it before submitting, where on the blank field that
               *       mistake is invisible until the exchange is refused. What is given up is that an
               *       onlooker can read the credential's LENGTH off the screen, and
               *       `visibilityToggle={false}` is what keeps the length the only thing recoverable
               *       -- the default toggle would put the characters themselves one click away, which
               *       is a property the non-display field never had.
               * WHY : Assumptions: `maxLength` is the SERVICE's bound and not the reference's
               *       `LENGTH=8`. See the note above {@link SIGN_ON_FIELD_WIDTH_HINT}'s neighbourhood
               *       for the full argument; the short form is that the provider accepts nothing
               *       shorter than twelve characters, so an eight-character control would admit only
               *       credentials guaranteed to be refused.
               * WHY : Assumptions: `autoComplete="current-password"` -- rather than the bare `on` -- is
               *       what tells the browser this is an EXISTING credential being presented, so a
               *       credential manager offers the stored value rather than proposing a new one. It is
               *       applied for the same reason as its counterpart on the identifier: it is invisible
               *       accessibility that alters no rendered pixel, and its absence is the only DOM
               *       advisory this screen emits.
               */}
              <Input.Password
                id={PASSWORD_FIELD_ID}
                ref={passwordControl}
                visibilityToggle={false}
                autoComplete="current-password"
                maxLength={PASSWORD_MAX_LENGTH}
                value={password}
                onChange={handlePasswordChange}
              />
            </Form.Item>
          ) : (
            <Form.Item
              label={
                <Typography.Text style={labelStyle}>{SIGN_ON_NEW_PASSWORD_LABEL}</Typography.Text>
              }
              htmlFor={PASSWORD_FIELD_ID}
              {...(refusals.password === undefined
                ? {}
                : { validateStatus: 'error' as const, help: refusals.password })}
            >
              {/*
               * WHY : Assumptions: no width hint is painted beside this control, because the mapset
               *       paints none -- there is no replacement-password field on the 3270 screen to
               *       transcribe a hint from. The bound is the service's, for the same reason as the
               *       control it replaces: the provider's minimum is twelve characters, so a cap taken
               *       from the mapset would stop an operator entering the very replacement the
               *       provider is demanding.
               * WHY : Assumptions: `autoComplete="new-password"` and NOT `current-password`, even though
               *       this is the same DOM node id as the credential control it swaps out for. The value
               *       is what stops a credential manager auto-filling the password that was just
               *       refused into the box asking for its replacement, and it is what makes a manager
               *       offer to GENERATE and store one instead.
               */}
              <Input.Password
                id={PASSWORD_FIELD_ID}
                ref={passwordControl}
                visibilityToggle={false}
                autoComplete="new-password"
                maxLength={PASSWORD_MAX_LENGTH}
                value={newPassword}
                onChange={handleNewPasswordChange}
              />
            </Form.Item>
          )}
          <Space>
            {/*
             * WHY : Assumptions: `type="primary"` because Enter is a primary action --
             *       `ui/src/layout/PfKeyBar.tsx` names ENTER and PFK05 as the primary aids -- and the
             *       exit key is rendered by that bar rather than duplicated here, so this is the one
             *       control the form itself carries.
             */}
            <Button type="primary" loading={busy} onClick={runSubmit}>
              {SIGN_ON_SUBMIT_LABEL}
            </Button>
          </Space>
        </Form>
      </Card>
      <PfKeyBar keys={bindings} onInvoke={invoke} />
    </Flex>
  );
}

/*
 * WHY : Assumptions: the component is exported BOTH ways, and both are load-bearing. `ui/src/router.tsx`
 *       L38 imports the named `SignOnScreen` and mounts it at `SIGN_ON_ROUTE`, so removing the named
 *       export would break the only route an unauthenticated operator can reach; the default export is
 *       the screen convention the migration plan states, and it lets the route be moved behind
 *       `React.lazy` -- which requires a default export -- without editing this file. Both names
 *       resolve to one function, so the two cannot come to describe different components.
 */
export default SignOnScreen;
