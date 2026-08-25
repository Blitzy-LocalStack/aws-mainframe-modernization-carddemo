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
 * Assumptions: FOUR anonymous fields are BMS plumbing with no target analogue and are dropped rather
 * than rendered — the zero-length attribute stoppers at `POS=(19,52)` (L161-L164) and `POS=(20,52)`
 * (L181-L184), the one-character `ATTRB=(DRK,UNPROT)` stopper at `POS=(20,61)` (L190-L193), and the
 * zero-length field at `POS=(20,63)` (L194-L196). None has an entry in `app/cpy-bms/COSGN00.CPY`, so
 * the reference program cannot read or write any of them: they exist to terminate the attribute run
 * of the field before them on a character-cell display. The drop is recorded because an absence of
 * four fields from a screen that claims 37 otherwise reads as an oversight.
 *
 * Refactoring Rationale: ⚠️ that count read "six" while the same sentence enumerated four fields and
 * the sentence after it called them four, so the paragraph contradicted itself twice over. Four is
 * the measured value: `app/bms/COSGN00.bms` declares three `LENGTH=0` fields, at L163, L183 and L195,
 * plus the one-character dark stopper at L190. It is corrected rather than left because a count that
 * disagrees with its own enumeration is exactly the kind of documentation a reader stops trusting.
 *
 * Assumptions: `CTRL=(ALARM,FREEKB)` at L19 rings the terminal alarm on every send. No web analogue
 * is added — an audible alert on a validation failure would be intrusive rather than faithful. The
 * keyboard-lock half of that operand DOES have a partial analogue and it is implemented: while an
 * exchange is in flight the Enter binding is marked disabled, so the legend entry greys out and the
 * physical key does nothing, which is the property `FREEKB` gave the terminal for free by holding the
 * keyboard until the task re-sent the map.
 *
 * Assumptions: two of the mapset's paintable strings are not painted unconditionally, and both
 * dispositions are recorded rather than taken silently. The second `(8 Char)` width hint at `POS=(20,52)` describes a
 * retired column and is registered as `D-SIGNON-RETIRED-WIDTH-HINT` — see
 * {@link SIGN_ON_FIELD_WIDTH_HINT}. The nine-line banknote is omitted below the design system's medium
 * breakpoint, where it cannot fit without pushing the sign-on form sideways.
 *
 * Values held, and for how long
 * -----------------------------
 * Assumptions: three values live in component state and two of them are credentials. Both credentials
 * are discarded at every settlement of the exchange that needed them — success, refusal or exit — so
 * neither outlives its exchange in state or in the control that renders it. The challenge handle is
 * retained only while it is still usable: a policy refusal keeps it, because the operator corrects
 * their proposed password and answers again, and a refused session retires it and restores the ordinary
 * sign-on form, because every further answer against it would be refused for the same reason.
 *
 * Message fidelity
 * ----------------
 * Assumptions: every sentence an operator reads here is a catalog constant from
 * `ui/src/messages/messages.ts`, and the wording is never this screen's. The five sentences that have
 * a program source are taken verbatim from it, with the trailing ellipsis and the interior spacing
 * intact. The painted labels and the decoration are the other half of the split that module documents
 * at L153-L182: a screen's own field labels and its mapset decoration belong to the screen that
 * renders them, so they are declared here rather than catalogued centrally.
 *
 * Refactoring Rationale: ⚠️ this paragraph read "taken verbatim from the originating program" of
 * EVERY sentence, and three of the sentences this screen now publishes have no originating program at
 * all — the arrival explanation for a guard's bounce, the replacement-credential explanation, and the
 * in-flight announcement. Each describes a condition a 3270 program could not observe: a browser
 * session being evicted, a credential the provider requires replaced, and a request crossing a
 * network while the operator watches. All three are AUTHORED, all three are registered in that
 * module's `AUTHORED_OPERATOR_SENTENCES` so the register can tell them from transcriptions, and the
 * rule that matters here is unchanged and is why they are catalogued rather than written inline: a
 * user-visible string may only be born in the catalog.
 *
 * Export surface
 * --------------
 * Refactoring Rationale: the component is exported under its NAME ONLY. It also carried a default
 * export, justified as "the shape a route element is conventionally reached by" — but no route in this
 * tree is declared that way, so the second key had no caller, and AAP section 0.6.2.1 fixes the
 * discipline as named imports with the named-to-default adapter held in `ui/src/router.tsx`.
 * `ui/src/router.tsx` imports the NAME (`import { SignOnScreen } from './screens/signon'`), so removing
 * the named export unmounts the route. The spelling is `SignOnScreen` with a capital `O` because that is
 * the identifier the router imports — a screen whose name disagrees with its only consumer does not
 * mount at all.
 *
 * Alternatives Considered: declaring the eleven transcribed constants and the pure helpers
 * module-private instead of exported, since nothing outside this file imports them today. Rejected on
 * a specific ground rather than on taste: the constants ARE the transformation-rule-T8 transcription,
 * and the per-screen test tree the architecture places at `ui/src/test/**` cannot assert a verbatim
 * string it cannot import — it would have to retype `Type your User ID and Password, then press
 * ENTER:` and the nine 42-character banknote lines, which puts a byte-exact transcription in two
 * places and makes silent drift between them possible. The helpers are exported for the narrower
 * reason that each encodes one decision worth asserting in isolation: the blank test that treats an
 * all-blanks field as empty, the refusal-to-sentence mapping, the per-control refusal split, the
 * cursor-destination choice, the discriminator that tells a refused challenge SESSION from a refused
 * proposed password, and the reading of the guard's bounce reason. Exporting them keeps those
 * assertable without a test having to drive the whole screen to reach one branch.
 *
 * Refactoring Rationale: ⚠️ this paragraph counted "the four pure helpers" and enumerated four while
 * the module exported six, so it under-reported by two — the session-refusal discriminator and now
 * {@link signOnArrivalMessage}. The count is dropped rather than corrected to a number: a figure in
 * prose beside a list that carries the same information goes stale on the next addition, and this one
 * already had.
 */

import { LoginOutlined } from '@ant-design/icons';
import { Button, Card, Flex, Form, Grid, Input, Space, Typography, theme } from 'antd';
import type { InputRef } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent, CSSProperties, ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router';

import { PASSWORD_MAX_LENGTH, SIGN_ON_CHALLENGE, USER_ID_MAX_LENGTH } from '../../api/auth';
import { SIGN_ON_NEW_PASSWORD_LABEL, SIGN_ON_SUBMIT_LABEL } from '../../messages/messages';
import type { SignOnChallenge } from '../../api/auth';
import { isApiRequestError } from '../../api/client';
import type { ApiError, FieldError } from '../../api/types';
import { ADMIN_GROUP, groupsFromIdToken, useAuth } from '../../hooks/useAuth';
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
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyRejection } from '../../layout/usePfKeys';
import {
  INVALID_KEY_PRESSED,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SIGN_ON_NEW_PASSWORD_REQUIRED,
  SIGN_ON_SESSION_REQUIRED,
  THANK_YOU_CARDDEMO,
} from '../../messages/messages';
import {
  ADMIN_MENU_ROUTE,
  MAIN_MENU_ROUTE,
  navigateSafely,
  signOnBounceState,
} from '../../routes/navigation';
import { BMS_TEXT_COLOR_TOKENS, BREAKPOINT_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';

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

/*
 * WHY : ⚠️ Refactoring Rationale: `SIGN_ON_NEW_PASSWORD_LABEL` was declared here AND imported from
 *       `ui/src/messages/messages.ts`, which the compiler reports as one name with two declarations. The
 *       catalogued one is kept: every user-visible string in this tree is catalogued in one place, and the
 *       reasoning that stood here -- that the wording is this screen's OWN because `USRSEC` has no
 *       replacement-credential field to transcribe, so it must be declared apart from the transcribed
 *       labels -- is recorded on the catalog entry, which is where a reader comparing the mapset against
 *       the screen looks. Two declarations of one label are two places for a rewording to be applied to
 *       only one.
 */

/**
 * The width hint painted beside the IDENTIFIER control, verbatim from `app/bms/COSGN00.bms` L169.
 *
 * Assumptions: `LENGTH=8` and `COLOR=BLUE` at `POS=(19,52)`. The mapset paints the same eight
 * characters a second time at `POS=(20,52)` (L189), beside the credential; that occurrence is
 * deliberately NOT rendered, and the paragraph below is the whole of the reason.
 *
 * Assumptions: this constant is now painted beside the IDENTIFIER only, where it remains true -- that
 * control is capped at eight, because `SEC-USR-ID PIC X(08)` at `app/cpy/CSUSR01Y.cpy` L18 still bounds
 * it and the migration did not change it. The credential carries NO hint at all -- a replacement hint
 * naming the enforced bound was drafted and withdrawn, for the reason recorded in the note below.
 *
 * Refactoring Rationale: this hint used to be painted beside BOTH controls, on the ground that the
 * mapset paints it twice and a transcribed string must never be silently redrafted. The reasoning was
 * sound about transcription and wrong about the reader. Beside the credential the string described a
 * bound the screen deliberately does not enforce and the provider actively refuses -- an eight-character
 * password cannot be accepted by any policy this repository can express, since
 * `infra/modules/cognito/variables.tf` defaults `password_minimum_length` to 14 and its own validation
 * refuses anything below 12. So the hint was not merely inaccurate: it named the one length guaranteed
 * to fail, on the control where a failure costs the operator their sign-on. Preserving a transcription
 * is a fidelity goal; instructing an operator to enter a value the system will reject is a defect, and
 * where the two meet the operator wins. The transcription is not lost -- it is still painted, still
 * verbatim, beside the control it is still true of.
 *
 * Assumptions: this is a divergence rather than a correction to the baseline, and it is registered as
 * `D-SIGNON-RETIRED-WIDTH-HINT` in `docs/architecture/cobol-to-service-traceability.md` alongside
 * `D-SIGNON-CASE-SENSITIVE-PASSWORD` and `D-PASSWORD-CHALLENGE`, the two entries recording the same
 * root cause: the credential field has no successor in the target, so every constraint the baseline
 * expressed about it describes a field that no longer exists.
 */
export const SIGN_ON_FIELD_WIDTH_HINT = '(8 Char)';

/*
 * WHY : ⚠️ Refactoring Rationale: a `SIGN_ON_PASSWORD_HINT` of `'(min 12 Char)'` was declared here and
 *       painted beside the credential control, and both are withdrawn. Two revisions remedied the same
 *       finding -- that the mapset's second `(8 Char)` hint describes a bound the screen deliberately does
 *       not enforce -- in incompatible ways: one replaced it with the pool policy's floor, the other
 *       withheld the hint entirely and registered the omission. The register is the authority and it
 *       records the second, `D-SIGNON-RETIRED-WIDTH-HINT` in
 *       `docs/architecture/cobol-to-service-traceability.md`, which names this exact alternative and
 *       refuses it on the ground the service uses for declining to restate the policy in
 *       `SignOnChallengeRequest`: the number is configured per environment, so a copy painted here becomes
 *       wrong the first time an environment tightens it -- and `password_minimum_length` already DEFAULTS
 *       to 14, so the floor of twelve is wrong in the default deployment on the day it was written.
 * WHY : Assumptions: the operator is not left without guidance. The provider reports the exact
 *       requirement when it refuses, and that sentence reaches the replacement control as a field
 *       refusal, so the number an operator needs arrives from the authority that owns it.
 */

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
 * Every value this screen can render a refusal against, named as the contracts name them.
 *
 * Assumptions: the spellings are exactly the request components of the two operations this screen
 * calls, so a `FieldError` from a problem document addresses a control without a translation table
 * between the wire name and the screen name. `userId` and `password` are the components of
 * `SignOnRequest`, whose own field order is declared `List.of("userId", "password")` in
 * `services/auth-service/src/main/java/com/carddemo/auth/dto/SignOnRequest.java`; `newPassword` is
 * the third component of `SignOnChallengeRequest` in the same package.
 *
 * Refactoring Rationale: ⚠️ `newPassword` was ABSENT from this list, and its absence silently
 * discarded the one refusal the challenge exchange is most likely to produce. The challenge answer's
 * 400 is declared to name `userId`, `session` or `newPassword` and to carry "the pool's own reason"
 * for a policy refusal — a password too short, or missing a character class — and
 * `CognitoIdentityService` publishes that key as `FIELD_NEW_PASSWORD = "newPassword"`. With the list
 * holding two names, {@link signOnFieldRefusals} dropped every such entry as unrecognised, so an
 * operator whose replacement password was refused for a stated reason saw the band's general sentence
 * and no reason at all — the actionable half of the refusal reached the browser and was thrown away.
 *
 * Assumptions: `session` is deliberately NOT a member, and the omission is not the same kind of gap.
 * It names no control: it is the opaque continuation value this screen echoes back from the challenge
 * body and never renders, so there is no input to attach text to and no cursor position to move to. A
 * refusal naming it is reported in the band and, because such a refusal means the session cannot be
 * used again, it is handled as an exchange the operator must restart rather than a field they can
 * correct — see {@link SignOnScreen}'s refusal reporting.
 */
const SIGN_ON_FIELDS = ['userId', 'password', 'newPassword'] as const;

/** One of the values this screen can render a refusal against. */
type SignOnField = (typeof SIGN_ON_FIELDS)[number];

/** Identifier of the operator-identifier control, declared so its label can be associated with it. */
const USER_ID_FIELD_ID = 'signon-user-id';

/**
 * Identifier of the password control, whether it currently holds the current or the replacement one.
 *
 * Assumptions: one identifier serves both because the two are mutually exclusive renderings of the
 * same row — the challenge replaces the password control rather than adding a second one — so the
 * document never carries two elements with this identifier. It follows that the derived help
 * identifiers are unambiguous too: at most one element in the document ever carries
 * `fieldErrorId(PASSWORD_FIELD_ID)`, whichever of the two credentials is currently mounted.
 */
const PASSWORD_FIELD_ID = 'signon-password';

/**
 * The breakpoint below which the decorative banknote is not rendered.
 *
 * Assumptions: the two members are two halves of ONE breakpoint and are declared together so that
 * neither can drift from the other. `Grid.useBreakpoint` keys its map by screen name, and antd's
 * responsive observer builds that screen's query as `(min-width: ${token.screenMD}px)` — so the key
 * selects the threshold and the token names where the threshold lives. No pixel value appears here or
 * anywhere else in this file: the number stays in the design system, which is the same discipline
 * every colour on this screen follows.
 *
 * Assumptions: exported so that `signon.test.tsx` can assert the two halves still correspond, deriving
 * one from the other rather than restating either. That is what turns the correspondence into a
 * checked property instead of a comment a token rename could invalidate.
 */
export const SIGN_ON_ART_BREAKPOINT = {
  /** Screen name to read from `Grid.useBreakpoint`. */
  screenKey: 'md',
  /** Design-system token holding that screen's threshold. */
  token: BREAKPOINT_TOKENS.medium,
} as const;

/**
 * Transport status the challenge exchange refuses an unusable session with.
 *
 * Assumptions: named rather than written inline at the comparison, so the one place the number
 * appears is the one place it is explained. It is the status `auth-api.yaml` declares for the
 * challenge operation's refused session, and the sign-on operation answers a refused CREDENTIAL with
 * the same status — which is why the comparison is only ever made about a refused challenge answer.
 */
const SESSION_REFUSED_STATUS = 401;

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
 *       ordinary `if (...) { return ...; }` form, using conditional expressions instead. Each helper
 *       answers exactly one question and has exactly one result, so a single expression states that
 *       directly where a guard clause would spread one decision over three lines and two returns.
 *       Trade-offs: a conditional expression is marginally denser to read; the alternative considered
 *       and rejected was moving the helpers below the component, which would put every definition
 *       after its use.
 * WHY : Refactoring Rationale: an earlier revision of this note gave a different and now-obsolete
 *       reason - that a two-space `if (` anywhere above the component would be mistaken for the
 *       component's own first early return by `ui/src/layout/screenHeaderClock.test.tsx`. That was
 *       true of the search that test used and is no longer: it now locates the component declaration
 *       first and searches only inside it, so a module-level helper may carry a guard clause without
 *       disturbing the rules-of-hooks assertion. The shape below is kept on its own merits rather
 *       than to work around a test.
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
 * Reports whether a problem document's field name addresses a value this screen renders.
 *
 * Refactoring Rationale: ⚠️ this was documented as covering "the two controls this screen renders",
 * which was both an inaccurate description of the domain and a description of the wrong domain. The
 * screen renders two controls at any instant but owns THREE named values across its two exchanges,
 * because the challenge mounts a distinct control whose wire name is `newPassword` — see
 * {@link SIGN_ON_FIELDS}, where the third member and the deliberate absence of `session` are both
 * recorded. The count is stated as the list's membership rather than as a number here so that the
 * documentation cannot fall behind the list again.
 * @param {string} name - Field name as the problem document spells it.
 * @returns {boolean} `true` when the name is a member of {@link SIGN_ON_FIELDS}.
 */
function isSignOnField(name: string): name is SignOnField {
  return (SIGN_ON_FIELDS as readonly string[]).includes(name);
}

/**
 * Chooses the screen-level sentence a refused exchange reports.
 *
 * Assumptions: the sentence is taken from the problem document rather than composed here, because the
 * service is what decides which refusal applies. `ui/src/api/auth.ts` states the contract directly —
 * a verbatim sentence arrives as the `message` of a problem document on a 401 and "a caller renders
 * that message unchanged under transformation rule T8" — so the sentence an operator reads is the one
 * the service sent and not one this screen guessed at.
 *
 * Assumptions: exactly which sentences are REACHABLE is worth stating, because the catalog holds three
 * credential refusals and the service can send only one of them.
 * `Wrong Password. Try again ...` (`app/cbl/COSGN00C.cbl` L242) is the one it sends, and it sends it for
 * BOTH of the baseline's two credential failures. `User not found. Try again ...` (L249) is
 * **unreachable through this screen**: the app client fixes `PreventUserExistenceErrors = "ENABLED"` at
 * L609 of `infra/modules/cognito/main.tf`, so the provider answers an unknown identifier and a wrong
 * password identically and the service has nothing to distinguish them with. That is deliberate — the
 * two sentences differ precisely in whether they disclose that an identifier exists, and answering an
 * unauthenticated caller differently for the two is an enumeration oracle. The divergence is registered
 * as `D-SIGNON-EXISTENCE-UNIFORM` in `docs/architecture/cobol-to-service-traceability.md`. The catalog
 * still carries the unreachable sentence, because transformation rule T8 carries every message constant
 * across whether or not a path reaches it, and deleting it would misrepresent the baseline.
 *
 * Assumptions: two further sentences do reach the band from a service, and neither is a credential
 * refusal. `Please sign on again ...` arrives on a 401 from the challenge exchange, where it means the
 * continuation rather than the credential was refused — the arm that selects it reads the status
 * inline, a withdrawn `refusalEndsTheChallenge` predicate having been folded into it. A
 * validation refusal arrives as a 400 whose aggregate sentence the service composes, with the specific
 * text carried per field.
 *
 * Assumptions: the fallback is `Unable to verify the User ...` (L254), which is the reference's own
 * `WHEN OTHER` arm — the sentence it shows for any response it cannot classify. A refusal that carries
 * no problem document at all, such as a transport failure, is exactly that case, so the fallback is a
 * transcription rather than an invention, and it is the only one of the three the CLIENT ever selects.
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

/*
 * WHY : ⚠️ Refactoring Rationale: a `CHALLENGE_PASSWORD_FIELD` of `'newPassword'` stood here, and
 *       both it and the branch of {@link signOnFieldRefusals} that read it are withdrawn as
 *       UNREACHABLE. It dated from a revision in which the challenge member was absent from
 *       {@link SIGN_ON_FIELDS}, so a refusal naming it had to be recognised separately and was
 *       redirected onto the `password` slot -- the two being one row and one control identifier. Two
 *       remedies then landed for the same finding: the member was added to the list, and the challenge
 *       item was re-keyed onto `refusals.newPassword`. With the member in the list the recognising
 *       predicate matches it first, so the redirect could never run; and with the item reading its own
 *       key, running it would have written the refusal to the slot the mounted control does NOT read.
 * WHY : Assumptions: nothing is lost by the withdrawal, because the knowledge it carried is recorded
 *       where it is used. {@link SIGN_ON_FIELDS} names the challenge body's three components and why
 *       `session` is not among them, and {@link PASSWORD_FIELD_ID} records that one identifier serves
 *       both renderings of the row -- which is what makes the shared cursor target and the shared help
 *       identifier correct while the refusal slots stay distinct.
 */

/*
 * WHY : Refactoring Rationale: an `UNAUTHENTICATED_STATUS` constant of 401 stood here beside
 *       {@link SESSION_REFUSED_STATUS}, which is the same number for the same reason. Two names for one
 *       status let a reader believe the screen distinguishes two conditions when it distinguishes one.
 */

/*
 * WHY : Refactoring Rationale: a `refusalEndsTheChallenge` predicate stood here and is withdrawn as a
 *       duplicate of {@link isSessionRefusal}, which tests the same status through the same helper. Its
 *       own finding is preserved and is what the surviving arm in `reportRefusal` acts on: a refused
 *       CONTINUATION returns the screen to the sign-on form, because the challenge session is single-use
 *       and once the pool has refused it every further answer is refused identically -- so the screen
 *       offered a replacement control that could not succeed with the identifier disabled beside it, and
 *       the only recovery was reloading the page.
 */

/**
 * Maps a refusal's per-field entries onto the controls they name.
 *
 * Assumptions: this mapping lives in the screen because it cannot live in the band.
 * `ui/src/layout/MessageBand.tsx` takes a sentence and a severity and deliberately refuses an
 * `ApiError`, on the stated ground that per-field text belongs to `Form.Item validateStatus="error"`
 * — so teaching the band about the API layer was rejected upstream, and the screen that awaited the
 * call is the only component holding both the refusal and the controls it addresses.
 *
 * Assumptions: an entry naming anything other than the three values in {@link SIGN_ON_FIELDS} is
 * dropped rather than surfaced somewhere arbitrary. The band already carries the screen-level sentence for that refusal,
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
      // Assumptions: the entry is written under the name the service used, and the item that renders
      //   the addressed control reads that same name -- `refusals.password` while the credential is
      //   mounted, `refusals.newPassword` while the challenge has replaced it. No translation happens
      //   here, which is the property {@link SIGN_ON_FIELDS} exists to give.
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
 * Reports whether a refused exchange means the challenge session can no longer be used.
 *
 * Assumptions: the discriminator is the transport status and NOT the sentence the body carries. The
 * challenge operation's 401 has exactly one published meaning — `auth-api.yaml` states that the
 * session "has expired, has already been used, was altered, or was not issued for the identifier
 * supplied", with the remedy "to sign on again" — so a 401 from that operation is unambiguous
 * regardless of wording, whereas the sentence itself is authored text with no reference line to pin
 * it and both refusals of this service publish the same `code`.
 *
 * Alternatives Considered: comparing the body's message against a copy of the service's
 * `MESSAGE_SESSION_REFUSED` sentence held here. Rejected because the contract does not publish that
 * sentence as a value — it is not an example, an enum or a schema default anywhere in the committed
 * document — so a copy in this file would be checkable against nothing, and a service reword would
 * silently turn the recovery below back into the defect it fixes.
 *
 * Assumptions: this predicate is asked only about a refused CHALLENGE answer. A 401 from the sign-on
 * exchange means a refused credential, which is an ordinary retry on the same form and must not clear
 * anything; the call site is what keeps the two apart, because the state it branches on is which form
 * was submitted.
 * @param {unknown} failure - Whatever the challenge-answer call rejected with.
 * @returns {boolean} `true` when the exchange was refused with the status that retires the session.
 */
export function isSessionRefusal(failure: unknown): boolean {
  return isApiRequestError(failure) && failure.status === SESSION_REFUSED_STATUS;
}

/**
 * Severity the arrival explanation is published at when a guard turned the operator away.
 *
 * Assumptions: `error`, which is the band's ASSERTIVE channel, and not the polite `info` the
 * farewell uses. Two grounds. The row this stands in for is `ERRMSG` at `app/bms/COSGN00.bms` L197,
 * declared `COLOR=RED ATTRB=BRT` — the band's `error` severity is the one that resolves to that
 * treatment — and `ui/src/layout/MessageBand.tsx` reserves its polite channel for guidance "the
 * operator has not asked to be interrupted by". This is the opposite case: the operator asked for a
 * screen, was refused it, and is looking at a form they did not navigate to, so an interruption is
 * exactly what the sentence is for.
 *
 * Alternatives Considered: `info`, on the reading that an evicted session is not the operator's
 * mistake and red text implies fault. Rejected because the band's severities are chosen for URGENCY
 * rather than for blame — every one of the 21 mapsets paints row 23 red, including for conditions no
 * operator caused — and a polite region announced on a screen the operator did not ask for is the
 * announcement most likely to be missed.
 */
const SIGN_ON_ARRIVAL_SEVERITY: MessageBandSeverity = 'error';

/**
 * Severity the replacement-credential explanation is published at.
 *
 * Assumptions: `info`, the polite channel, because nothing has been refused. The provider accepted
 * the credential and is asking for a replacement, so painting the sentence in the refusal channel
 * would tell an operator their password was wrong at the exact moment it was right — which is the
 * confusion `auth-api.yaml` cites when it refuses to reuse `Wrong Password. Try again ...` for this
 * outcome.
 */
const SIGN_ON_CHALLENGE_SEVERITY: MessageBandSeverity = 'info';

/**
 * Reports the sentence explaining why the operator is looking at sign-on, if anything explains it.
 *
 * Purpose: close the operator-visible half of the silent bounce. `ui/src/routes/guards.tsx` already
 * redirects an operator holding no session to this screen and already hands the history entry a
 * reason, and nothing read it — so an audit measured the band present and EMPTY on all nineteen
 * guarded routes, with a bounce out of `/users/USER0100/delete` indistinguishable from a first visit.
 *
 * Assumptions: the reason is read through `signOnBounceState` rather than off the state object here,
 * and that is what keeps this screen out of the trust decision. `useLocation().state` is whatever the
 * previous entry wrote, including a hand-edited value, so the reader in
 * `ui/src/routes/navigation.ts` validates it structurally and admits only its own literal; an
 * unrecognised value reads as no reason at all, which is the same as a first visit.
 *
 * Assumptions: only the BOUNCE reason produces a sentence here. The same channel also carries the
 * deliberate sign-off reason, and that one is claimed by `ui/src/layout/AppShell.tsx`, which replaces
 * the whole frame with its own acknowledgement surface — so a sentence for it here would either
 * duplicate that surface or paint an explanation behind it. `signOnBounceState` narrows to the bounce
 * literal alone, which is why this function does not have to make that distinction itself.
 *
 * Assumptions: the attempted destination the same state carries is deliberately NOT rendered. It is a
 * value the address bar supplied, so echoing it would paint an operator's mistyped account identifier
 * or card number onto the screen and into any screenshot of it — the withholding
 * `ui/src/messages/messages.ts` records for the rejected path on the not-found surface. Resuming the
 * destination after a successful sign-on is a separate behaviour and is not attempted here.
 * @param {unknown} entryState - The `state` member of this screen's location, of any shape.
 * @returns {string | null} The catalogued sentence to publish, or `null` when nothing put the
 *   operator here.
 */
export function signOnArrivalMessage(entryState: unknown): string | null {
  return signOnBounceState(entryState).reason === undefined ? null : SIGN_ON_SESSION_REQUIRED;
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
  /*
   * WHY : Assumptions: the viewport is read through the design system's own responsive observer rather
   *       than from `window.innerWidth` or a media-query listener written here, so the threshold this
   *       screen reacts to is the same one every `Row`, `Col` and `Descriptions` in the application
   *       reacts to. Reading the width directly would put a pixel literal in this file and would drift
   *       the moment a theme changed the breakpoint scale.
   * WHY : Trade-offs: the hook returns an empty map on the very first render and fills it in a layout
   *       effect, so one paint happens before any screen is known. That is why the test below is
   *       written as "hide only when the observer AFFIRMATIVELY reports narrow" rather than "show only
   *       when it reports wide": the inverted form would flash the decoration out of existence on
   *       every mount at every width, and it would hide it permanently in any environment whose
   *       `matchMedia` reports nothing.
   */
  const screens = Grid.useBreakpoint();
  const artFitsViewport = screens[SIGN_ON_ART_BREAKPOINT.screenKey] !== false;
  /*
   * WHY : Assumptions: the location's `state` is read as a PROPERTY through an `unknown` cast rather
   *       than destructured, which is the pattern `ui/src/layout/AppShell.tsx` uses on the same value
   *       and for the same reason: the routing package types `state` as `any` because any page may
   *       write anything onto a history entry, and destructuring an `any` member spreads that `any`
   *       through this file. {@link signOnArrivalMessage} is the only reader and it narrows
   *       structurally, so nothing here ever reads a member off an untrusted value.
   */
  const arrival = signOnArrivalMessage((useLocation() as { readonly state?: unknown }).state);
  /*
   * WHY : ⚠️ Refactoring Rationale: the band opens with the arrival explanation where it opened
   *       EMPTY, which is the operator-visible half of the silent bounce. A guard turning an operator
   *       away has always handed this screen a reason; nothing rendered it, so the screen presented an
   *       ordinary cold sign-on that had silently discarded what the operator asked for.
   * WHY : Alternatives Considered: deriving the band value each render as `message ?? arrival`, with
   *       no seeded state. Rejected because every submission clears the band before dispatching, so a
   *       derived arrival sentence would REAPPEAR under the operator's own in-flight sign-on and read
   *       as a second eviction. Seeding makes it what it is -- a first-entry sentence, replaced by the
   *       first thing this screen has to say for itself -- which is also how the reference's own
   *       first-entry message behaved.
   * WHY : Assumptions: a lazily-seeded initial value is sufficient BECAUSE a bounce always mounts this
   *       screen fresh. The guard is the route element for every guarded path, so a bounce unmounts the
   *       screen the operator was on and mounts this one; there is no path on which this component
   *       stays mounted while the reason on its own entry changes.
   */
  const [message, setMessage] = useState<ScreenMessage | null>(
    arrival === null ? null : { text: arrival, severity: SIGN_ON_ARRIVAL_SEVERITY },
  );
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
     *
     * Assumptions: two of the three names in {@link SIGN_ON_FIELDS} resolve to the SAME ref, and that
     * is not a gap. `password` and `newPassword` are two mutually exclusive renderings of one row
     * sharing one identifier and one ref, so the cursor lands on whichever of them is mounted — which
     * is exactly the behaviour wanted, since a refusal naming either always arrives while that one is
     * the control on screen.
     * @returns {void} Nothing; the browser's focus is the outcome.
     */
    function moveCursorToRequestedField(): void {
      if (focusRequest === null) {
        return;
      }

      // Assumptions: three field NAMES resolve to two DOM controls, because `password` and
      //   `newPassword` are mutually exclusive renderings of the same row and both carry the same ref.
      //   Whichever is mounted is the one this focuses, which is why the challenge turn can point at
      //   its own control without a third ref.
      const control =
        focusRequest.field === 'userId' ? userIdControl.current : passwordControl.current;
      control?.focus();
    },
    [focusRequest],
  );

  /**
   * Requests that the cursor move to one of the named controls.
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
   * Assumptions: no per-control text is attached for any of the three blank-field refusals that reach
   * this helper — a blank identifier, a blank credential, a blank replacement — and therefore no
   * `aria-invalid` appears on the control either. That is consistent rather than a gap in the ARIA
   * wiring: `aria-invalid` is emitted from the presence of a refusal, and on this path there is no
   * field-level refusal to emit it from. What tells the operator is the band, which carries
   * `role="alert"` while it holds a sentence, together with the cursor arriving in the field named — so
   * a screen-reader user is announced the sentence and then lands on the control it is about. The
   * omission is the reference's. `CSSETATY` is not copied by this program, so a blank field produces a band sentence
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
   * Reports a refused exchange, discarding BOTH credentials whichever one was submitted.
   *
   * Assumptions: the credentials are cleared here rather than left in their controls. The reference
   * re-sends the map with `ERASE` on every refusal (L151-L157), so the 3270 field was blank again on
   * the next turn; and a credential must not outlive the exchange it was submitted for, which is why
   * each is dropped from component state at the first moment it is no longer needed. Neither is ever
   * echoed back from a response either — that is what the reference's own update screen did, moving
   * `SEC-USR-PWD` into the map at `app/cbl/COUSR02C.cbl` L169, and no response this screen reads
   * carries a password at all.
   *
   * Refactoring Rationale: ⚠️ this cleared `password` alone, so a REPLACEMENT password that the pool
   * refused survived in component state and in the masked control that renders it — a refused
   * credential retained after the exchange that needed it had settled, which is the plaintext-retention
   * weakness CWE-316 and CWE-522 name and which the sibling credential was already protected from on
   * this same line. Both are cleared unconditionally rather than only the one belonging to the visible
   * form, because the cost of clearing an already-empty value is nothing while the cost of choosing
   * wrongly is retaining a credential.
   *
   * Refactoring Rationale: ⚠️ a refused SESSION now also retires the challenge, where before the
   * challenge stayed outstanding forever. The challenge session is single-use and, once the pool has
   * refused it, every subsequent answer is refused for the same reason — so the screen kept offering a
   * replacement-password control that could not succeed, with the identifier control disabled beside
   * it and no gesture on the screen able to reach a fresh sign-on: not the submit control, which
   * re-answered the dead session, and not the exit key, which signs off rather than restarting. The
   * only recovery was to reload the page. Restoring the ordinary form is what turns the published
   * remedy — "sign on again" — into something the operator can actually do.
   *
   * Assumptions: the challenge is retired ONLY on that status. A 400 refusal means the pool judged the
   * proposed password against its policy and said why, so the session is still usable and the operator
   * corrects the value in place: the challenge handle is retained, the per-field reason is attached to
   * the replacement control, and the cursor returns to it. That split is the report's own division
   * between an expired session and a retryable policy failure.
   * @param {unknown} failure - Whatever the sign-on or challenge call rejected with.
   * @returns {void} Nothing; the band, the per-control refusals, the retired challenge and the focus
   *   carry the outcome.
   */
  function reportRefusal(failure: unknown): void {
    const text = signOnFailureMessage(failure);
    const answeringChallenge = challenge !== null;
    const sessionRetired = answeringChallenge && isSessionRefusal(failure);
    setMessage({ text, severity: 'error' });
    // Refactoring Rationale: BOTH credential controls are cleared, where this cleared only the current
    //   password. The rule stated above — a credential must not outlive the exchange it was submitted
    //   for — applies to a replacement exactly as it does to the one being replaced, and a refused
    //   replacement was being left in component state for the rest of the screen's life. It is also the
    //   value most likely to be wrong, since the commonest refusal here is that it breaks the pool's
    //   policy, so leaving it in place invited a second submission of the same rejected value.
    setPassword('');
    setNewPassword('');

    /*
     * WHY : ⚠️ Refactoring Rationale: the terminal-refusal test is `sessionRetired`, computed once above,
     *       and this arm tested `challenge !== null && refusalEndsTheChallenge(failure)`. The two were the
     *       same predicate under two names -- `isSessionRefusal` reads `failure.status === 401` and
     *       `refusalEndsTheChallenge` reads `failure.problem.status === 401`, from two constants both
     *       declared as 401 -- authored by two revisions remedying the same finding. One name is kept so
     *       the split this arm depends on cannot be changed in one place and not the other: a 401 retires
     *       the challenge, and a 400 leaves it usable so the operator corrects the value in place.
     */
    if (sessionRetired) {
      // Refactoring Rationale: a refused CONTINUATION now returns the screen to the sign-on form, where
      //   it previously left the operator in the challenge holding a session the pool had rejected. Every
      //   further submission reused that dead session and was refused identically, so the screen was
      //   stuck: nothing on it could reach a working state and the only way out was to reload the
      //   application. Restoring the form is what the sentence the service sends for this status —
      //   `Please sign on again ...` — actually instructs.
      setChallenge(null);
      setRefusals({});
      focusField('userId');
      return;
    }

    setRefusals(signOnFieldRefusals(failure));
    // Refactoring Rationale: while a challenge is outstanding the cursor goes to the password control
    //   unconditionally, where it previously went wherever {@link signOnFocusTarget} pointed — which for
    //   any sentence other than the wrong-password one is the identifier. That control is rendered
    //   `disabled` during a challenge, so the focus request landed on an element that cannot take it and
    //   the cursor stayed where it was. The replacement-password input is the only control the operator
    //   can act on at that moment, which makes it the only correct target.
    focusField(challenge === null ? signOnFocusTarget(text) : 'password');
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
        /*
         * ⚠️ Refactoring Rationale: the turn now EXPLAINS itself, where it transformed the form in
         * silence. The identifier is disabled with the operator's own value still in it, the
         * credential label changes to `New Password` and the field is cleared -- and an audit
         * measured the band empty through all of it, so an operator was asked for a replacement with
         * nothing on screen saying that the password they had just typed was accepted, that it is
         * temporary or expired, or that setting a permanent one is what completes the sign-on.
         * Assumptions: the sentence is the catalogued {@link SIGN_ON_NEW_PASSWORD_REQUIRED} and not
         * the provider's `challengeName`. `NEW_PASSWORD_REQUIRED` is the pool's vocabulary and names
         * a mechanism rather than an action, and this screen renders no service identifier anywhere
         * else either.
         * Alternatives Considered: painting the explanation beneath the replacement control as its
         * `extra` text instead of in the band. Rejected because it would give this screen a second
         * announcing surface for one condition -- the band is already published through
         * `useShellSlot` and is the region an operator reads for what just happened -- and a
         * subsequent policy refusal has to be able to REPLACE the explanation, which it does here by
         * writing the same band.
         */
        setMessage({ text: SIGN_ON_NEW_PASSWORD_REQUIRED, severity: SIGN_ON_CHALLENGE_SEVERITY });
        // Assumptions: the accepted credential is dropped as soon as the provider has consumed it,
        //   so the replacement it is now asking for is entered into an empty control. The replacement
        //   slot is cleared alongside it: an operator who was challenged, had the session refused, and
        //   signed on again reaches this line with a value already typed into it, and seeding the new
        //   challenge with the previous attempt would both prefill a credential and re-offer one the
        //   pool has already judged.
        setPassword('');
        setNewPassword('');
        focusField('newPassword');
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
   * — an empty password control. The refusal names `newPassword` rather than `password`, which is the
   * value actually being judged and the name the service would have used for it; the cursor lands in
   * the same control either way, so the change is to the truthfulness of the domain rather than to
   * what the operator sees.
   * @returns {Promise<void>} Resolves once the outcome has been applied to screen state. It does not
   *   reject: every refusal is converted into a band sentence by {@link reportRefusal}.
   */
  async function submitChallenge(): Promise<void> {
    if (challenge === null) {
      return;
    }
    if (isBlankEntry(newPassword)) {
      refuseEntry('newPassword', SIGN_ON_MESSAGES.PLEASE_ENTER_PASSWORD);
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
   * Assumptions: the session is discarded through `useAuth`'s own `signOut` rather than from here, so
   * the identity that hook holds and the bearer `ui/src/api/client.ts` holds are discarded together by
   * their owners. Neither is in browser storage to clear: both are module variables, for the reason
   * each records at its declaration, so a screen could not clear them even if it tried.
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
   * Assumptions: a submission already in flight is dropped rather than queued, and this guard is now
   * the SECOND of two rather than the only one — the Enter binding is marked disabled while a
   * submission is in flight, so the key and the legend entry are unavailable before this runs.
   *
   * Refactoring Rationale: ⚠️ the guard used to be the only defence, on the argument that a binding
   * marked `disabled` reports `CCDA-MSG-INVALID-KEY` through `onInvalidKey` and would tell an operator
   * their Enter key was invalid while their sign-on was succeeding. The argument was sound about the
   * message and wrong about the conclusion: leaving the binding enabled meant the legend's `ENTER=Sign-on`
   * stayed lit and the physical key stayed live while both silently did nothing, so the pointer control
   * — which antd disables from `loading` — and the keyboard disagreed about whether the screen was
   * accepting input. The message is suppressed at its source instead: `onInvalidKey` distinguishes the
   * two rejection reasons the hook reports and stays silent for `disabled`, so the binding can express
   * unavailability without inventing a refusal. This guard is kept because it also covers the pointer
   * path if antd's own disabling ever stops applying, and because `invoke` can be called from the bar.
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
        /*
         * WHY : Assumptions: unavailability while an exchange is in flight is expressed on the BINDING
         *       and not only inside the handler, because the binding is what the legend renders from --
         *       `ui/src/layout/PfKeyBar.tsx` passes `disabled={!binding.enabled}` -- so this is the one
         *       declaration that makes the legend entry, the physical key and the submit control agree.
         *       The 3270 screen had the equivalent property for free: a task holding the terminal left
         *       the keyboard locked until it re-sent the map, so an operator could not re-press Enter
         *       into a running transaction at all. Nothing in a browser locks the keyboard, so the
         *       equivalent has to be stated.
         * WHY : Trade-offs: a boolean is passed rather than the predicate form the hook also accepts.
         *       The predicate exists for state the hook must re-read at dispatch time; `busy` is
         *       render state, so a boolean read at the same render as the legend keeps the two exactly
         *       in step, where a predicate could report one thing while the bar rendered another.
         */
        disabled: busy,
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
       *
       * Assumptions: a key rejected because it is currently DISABLED is not that condition and is
       * reported nowhere. The hook reports both reasons through this one callback, and only `unmapped`
       * corresponds to `WHEN OTHER`: the operator pressed a key this screen binds, and the reason
       * nothing happened is that the screen is mid-exchange, which the greyed legend entry and the
       * loading submit control already say. Announcing `CCDA-MSG-INVALID-KEY` for it would state
       * something false about a key the screen does support, and it would overwrite whatever the
       * in-flight exchange is about to report.
       * @param {PfKeyRejection} rejection - The hook's account of which key was refused and why.
       * @returns {void} Nothing; the band carries the outcome, or nothing does when the key was merely
       *   unavailable.
       */
      onInvalidKey: (rejection: PfKeyRejection): void => {
        if (rejection.reason === 'disabled') {
          return;
        }
        setMessage({ text: INVALID_KEY_PRESSED, severity: 'error' });
      },
    },
  );

  /*
   * WHY : Refactoring Rationale: the title band, the row-23 message line and the row-24 legend are
   *       DELEGATED to the single `AppShell` that `ui/src/App.tsx` mounts, where this screen used to
   *       compose all three itself. Composing them per screen was how the application worked before
   *       the shell was wired in, and it is what left the frame rebuilt once per screen with nothing
   *       guaranteeing that a screen authored later shipped one at all.
   * WHY : Assumptions: the shell paints a zone if and only if it has been delegated one, so the three
   *       members below are the whole of what this screen surrenders; everything the mapset paints
   *       between rows 5 and 21 stays here. The message severity falls back to `error` when there is no
   *       sentence, because row 23 of the mapset is `COLOR=RED` with `ATTRB=BRT` and the value is then
   *       unused anyway.
   * WHY : ⚠️ Assumptions: this screen's own `usePfKeys` result is handed over rather than
   *       re-derived by the shell, so what the shell receives is a legend to RENDER while this screen
   *       keeps the keyboard: an activation of a rendered control is forwarded straight back to `invoke`.
   *       The claim that stood here, that a published slot "makes the shell stand its own sign-off key
   *       down", is withdrawn: the shell installs NO keyboard listener at all and offers sign-off as a
   *       rendered control, for the reason recorded at `SHELL_SIGN_OFF_LABEL`. That REMOVES the concern
   *       this screen raised rather than answering it -- there was never a shell key here to be active or
   *       inactive, signed on or not.
   */
  useShellSlot({
    screen: { transactionId: SIGN_ON_TRANSACTION_ID, programName: SIGN_ON_PROGRAM_NAME },
    now: paintedAt,
    message: {
      text: message?.text ?? null,
      severity: message?.severity ?? 'error',
      mapset: SIGN_ON_MAPSET,
    },
    pfKeys: { keys: bindings, onInvoke: invoke },
  });

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

  /*
   * WHY : Refactoring Rationale: every colour below resolves through
   *       `BMS_TEXT_COLOR_TOKENS` and no longer through `BMS_COLOR_TOKENS`, because each of
   *       these declarations paints TEXT and the hue map's entries are fill-grade anchors. The
   *       two roles this screen uses measured 2.205:1 for the turquoise prompt and labels and
   *       4.104:1 for the blue hints and banknote, where WCAG AA asks 4.5:1 for normal text.
   *       The measured source roles are unchanged -- `COLOR=TURQUOISE` on the prompt at
   *       `app/bms/COSGN00.bms` L149 and on both labels at L155 and L174, `COLOR=BLUE` on the
   *       two width hints at L169 and L189 -- and `ui/src/theme/tokens.ts` records which of
   *       the eight roles kept its hue family and which had to snap out of it, with the
   *       measured ratio each was snapped away from.
   */
  const introductionStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] };
  const promptStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] };
  const labelStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] };
  const hintStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.BLUE] };
  const banknoteStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.BLUE],
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
       * WHY : Refactoring Rationale: the art is rendered only at or above the design system's medium
       *       breakpoint, where it was previously rendered unconditionally. Each of its nine lines is
       *       42 characters of fixed-pitch text held together by `white-space: pre`, which cannot wrap
       *       and cannot shrink — so on a narrow viewport it set the width of the whole column and
       *       pushed a horizontal scrollbar under the sign-on form, making the form itself something
       *       an operator had to scroll sideways to use.
       * WHY : Alternatives Considered: scaling the art down with a transform, or letting it clip inside
       *       an overflow container, both of which keep it on screen. Both were rejected for the same
       *       reason: they trade a broken form for broken decoration, since a 42-column note scaled to
       *       a 375-pixel viewport is unreadable and a clipped one is a fragment of a border. Omitting
       *       it costs nothing an operator can act on — it is `aria-hidden` decoration that
       *       `ui/src/messages/messages.ts` L163-L164 classifies as exactly that — and the report's
       *       instruction is explicit that the form must not be compromised for it.
       */}
      {artFitsViewport ? (
        <Typography.Text aria-hidden="true" style={banknoteStyle}>
          {SIGN_ON_BANKNOTE_ART.join('\n')}
        </Typography.Text>
      ) : null}
      <Typography.Text style={promptStyle}>{SIGN_ON_PROMPT}</Typography.Text>
      <Card>
        <Form layout="vertical">
          <Form.Item
            label={
              <Typography.Text style={labelStyle}>{SIGN_ON_FIELD_LABELS.userId}</Typography.Text>
            }
            htmlFor={USER_ID_FIELD_ID}
            extra={
              <Typography.Text id={fieldHintId(USER_ID_FIELD_ID)} style={hintStyle}>
                {SIGN_ON_FIELD_WIDTH_HINT}
              </Typography.Text>
            }
            {...(refusals.userId === undefined
              ? {}
              : {
                  validateStatus: 'error' as const,
                  help: fieldErrorHelp(USER_ID_FIELD_ID, refusals.userId),
                })}
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
            {/*
             * WHY : Refactoring Rationale: the two ARIA members are spread from
             *       `ui/src/layout/fieldHelp.tsx` rather than left to antd, and that module records the
             *       whole reason: `Form.Item` injects them only for a NAMED field, and every item on
             *       this screen is unnamed because the values live in component state. Without them a
             *       refusal was red text a sighted operator could read and a screen-reader user was
             *       told nothing about -- neither that the control was invalid nor what was wrong with
             *       it. The hint is described as well as the refusal, so the parenthetical width a
             *       sighted operator reads beside the control is announced to one who cannot.
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
              {...fieldAriaProps(USER_ID_FIELD_ID, {
                invalid: refusals.userId !== undefined,
                hasError: refusals.userId !== undefined,
                hasHint: true,
              })}
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
              {...(refusals.password === undefined
                ? {}
                : {
                    validateStatus: 'error' as const,
                    help: fieldErrorHelp(PASSWORD_FIELD_ID, refusals.password),
                  })}
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
               *       `LENGTH=8`. See `D-SIGNON-RETIRED-WIDTH-HINT` for the full argument; the short
               *       form is that the provider accepts nothing shorter than twelve characters, so an
               *       eight-character control would admit only credentials guaranteed to be refused.
               *       NO width hint is painted beside this control for the same reason: the mapset paints
               *       its `(8 Char)` twice and the second copy would name the one length that cannot be
               *       accepted, so the omission is registered as `D-SIGNON-RETIRED-WIDTH-HINT` and the
               *       provider's own refusal sentence carries the requirement instead.
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
                {...fieldAriaProps(PASSWORD_FIELD_ID, {
                  invalid: refusals.password !== undefined,
                  hasError: refusals.password !== undefined,
                  hasHint: false,
                })}
              />
            </Form.Item>
          ) : (
            <Form.Item
              label={
                <Typography.Text style={labelStyle}>{SIGN_ON_NEW_PASSWORD_LABEL}</Typography.Text>
              }
              htmlFor={PASSWORD_FIELD_ID}
              {...(refusals.newPassword === undefined
                ? {}
                : {
                    validateStatus: 'error' as const,
                    help: fieldErrorHelp(PASSWORD_FIELD_ID, refusals.newPassword),
                  })}
            >
              {/*
               * WHY : Refactoring Rationale: ⚠️ this item keyed its error state off `refusals.password`
               *       and now keys it off `refusals.newPassword`, which is the name the value it holds
               *       is actually judged under. The challenge answer's 400 names `newPassword` and
               *       carries the pool's own reason for refusing it -- too short, or missing a required
               *       character class -- so under the old key that reason was addressed to a control not
               *       mounted while a challenge is outstanding, and the operator was told a password was
               *       unacceptable without being told what about it was. The domain change that makes
               *       this key resolvable at all is recorded on {@link SIGN_ON_FIELDS}.
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
                {...fieldAriaProps(PASSWORD_FIELD_ID, {
                  invalid: refusals.newPassword !== undefined,
                  hasError: refusals.newPassword !== undefined,
                  hasHint: false,
                })}
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
            {/*
             * WHY : ⚠️ Refactoring Rationale: the control now carries an `icon`, and the reason is
             *       measurement rather than decoration. With no icon the design system inserts its
             *       loading glyph through a motion that animates the slot FROM ZERO WIDTH -- measured in
             *       `node_modules/antd/es/button/DefaultLoadingIcon.js`, whose `onAppearStart` returns
             *       `{ width: 0 }` and whose `onAppearActive` returns the glyph's own `scrollWidth` -- so
             *       the control grew from 78.5 to about 99 pixels, a 26 per cent jump, at the instant the
             *       operator pressed it. `Button.js` L262 passes `existIcon: !!icon`, and L273-L278 puts
             *       the loading glyph in the icon's own slot when one exists, which removes the motion
             *       entirely: the slot is the same width in both states and the glyph is simply swapped.
             * WHY : ⚠️ Alternatives Considered: reserving the slot with an invisible placeholder, and
             *       fixing the control's width outright. The placeholder works and was rejected as an
             *       empty element carrying no meaning, present solely to hold a measurement; a fixed
             *       width would be a pixel value this screen is not allowed to hold, and would have to be
             *       remeasured for every theme and every translation of the label. A real icon costs one
             *       glyph and removes the jump for the same reason the placeholder would.
             * WHY : Assumptions: the glyph is a sign-on glyph and not an arbitrary one, and it is
             *       additive -- the mapset paints no iconography, and AAP section 0.3.2 admits
             *       `@ant-design/icons` on that basis.
             * WHY : ⚠️ Assumptions: `aria-hidden` on the glyph is load-bearing and not defensive. Every
             *       `@ant-design/icons` export renders `role="img"` with `aria-label` set to the icon's
             *       own name -- `node_modules/@ant-design/icons/es/components/AntdIcon.js` L48-L50 -- so
             *       an unhidden glyph CONTRIBUTES its name to the button's, and this control announced
             *       "login Sign on". That makes one control read twice and breaks every query that names
             *       it by its label. Hiding it leaves the accessible name exactly the catalog's
             *       `Sign on`, which is all a decorative glyph should ask for. Measured on the sibling
             *       menu screens, where the same omission failed eighteen cases in one run.
             * WHY : ⚠️ Refactoring Rationale: `aria-busy` is STATED on the control, through the shared
             *       helper every other screen in this delivery states it with. A review found the screen
             *       carrying two busy vocabularies for one action in a single frame -- this control
             *       spinning while the legend's own ENTER entry greyed out -- and found `aria-busy` on no
             *       button anywhere. The legend entry stays disabled deliberately, because that is the
             *       3270 keyboard lock's only equivalent and the binding above records why; what was
             *       missing was the control the operator actually pressed saying, in the one vocabulary
             *       the rest of the application uses, that it is working.
             * WHY : ⚠️ Refactoring Rationale: the audible half IS added now, and the reason it was
             *       absent has been removed rather than argued around. This block recorded that
             *       `busyAnnouncement` requires a SENTENCE, that every operator sentence belongs to
             *       `ui/src/messages/messages.ts`, and that the catalog declared no busy sentence -- so
             *       the gap was reported rather than filled, because inventing wording in a screen
             *       would breach transformation rule T8. The catalog now declares
             *       {@link REQUEST_IN_PROGRESS} as an AUTHORED sentence, registered and width-bounded
             *       where a screen-local literal could never be, so the sentence comes from the one
             *       place a user-visible string may be born and this screen only publishes it.
             * WHY : Assumptions: the condition genuinely has no reference wording to transcribe, which
             *       is why the sentence had to be authored rather than found. `CTRL=(ALARM,FREEKB)` at
             *       `app/bms/COSGN00.bms` L19 held the terminal's keyboard until the task re-sent the
             *       map, so the 3270 screen said nothing while a task ran because the operator
             *       physically could not type into it. Nothing in a browser locks the keyboard, so the
             *       property has to be stated in words.
             * WHY : Trade-offs: the announcement is VISUALLY HIDDEN and adds no third busy vocabulary
             *       to the frame. A review already found this screen showing two visible ones for one
             *       action -- the control's spinner and the legend's greyed ENTER entry -- so the
             *       missing half was never a second visible indicator; it was that a screen-reader
             *       user was told nothing at all when the key they pressed started work.
             */}
            {busyAnnouncement(busy ? REQUEST_IN_PROGRESS : undefined)}
            <Button
              type="primary"
              icon={<LoginOutlined aria-hidden />}
              loading={busy}
              onClick={runSubmit}
              {...busyProps(busy)}
            >
              {SIGN_ON_SUBMIT_LABEL}
            </Button>
          </Space>
        </Form>
      </Card>
      {/*
       * WHY : Refactoring Rationale: the key legend that used to close this body is delegated to the
       *       shell in the `useShellSlot` call above, together with the title band that opened it and
       *       the row-23 message line. Rendering the bar here as well would put a second named legend
       *       region on the screen and a second `PfKeyBar` handle where callers expect one.
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
