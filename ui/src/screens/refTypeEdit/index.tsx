/**
 * @file The transaction-type maintenance screen, migrated from
 * `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` (1702 lines) and its mapset
 * `app/app-transaction-type-db2/bms/COTRTUP.bms` (map `CTRTUPA`, `DFHMDI ... SIZE=(24,80)`), mounted
 * by `ui/src/router.tsx` at `/reference/transaction-types/:cd`.
 *
 * Purpose
 * -------
 * Take a two-character transaction-type code, show the row it names, and let an administrator add,
 * change or delete it. It replaces CICS transaction `CTTU`, which the administrative menu lists as
 * option 6, "Transaction Type add/edit" (`app/app-transaction-type-db2/README.md` L38).
 *
 * One screen, one mode machine
 * ----------------------------
 * Alternatives Considered: four components -- fetch, add, update and delete -- each owning one
 * operation. Rejected because the reference is ONE program serving all four, and it decides between
 * them from a single status field: `10 TTUP-CHANGE-ACTION PIC X(1)` at L296, carrying sixteen
 * `88`-level states at L298-L327. Splitting it would fragment one observable screen into four, and
 * would duplicate the validation chain the four share -- so the two-press delete confirmation and the
 * validate-then-save handshake would each have to be reproduced twice. {@link RefTypeEditMode}
 * transcribes all sixteen states and {@link refTypeEditReducer} is the only place they change.
 *
 * The keyboard vocabulary the mapset paints and the one the program honours DISAGREE
 * ---------------------------------------------------------------------------------
 * Assumptions: the mapset declares five function-key legends and the program implements four. Grepping
 * the whole program for `FKEY06`, `PFK06` and `F6=` returns ZERO matches, `3391-SETUP-PFKEY-ATTRS`
 * (L1397-L1423) brightens only `FKEYS`, `FKEY04A`, `FKEY05A` and `FKEY12A`, and `0001-CHECK-PFKEYS`
 * (L577-L621) carries no `CCARD-AID-PFK06` term. So `'F6=Add'` is dead legend text: it is rendered so
 * the screen's vocabulary is complete, and it is disabled in every mode so no press can reach an
 * action the reference has no code for. The real add path is PF5 from `TTUP-DETAILS-NOT-FOUND` into
 * `TTUP-CREATE-NEW-RECORD`, then PF5 again.
 *
 * Trade-offs: rendering a legend the terminal never lit is the accepted cost of that completeness.
 * `FKEY06` is `ATTRB=(ASKIP,DRK)` at `COTRTUP.bms` L126-L130 and nothing ever brightens it, so on the
 * glass the operator saw no `F6=Add` at all. Omitting it here would match the terminal pixel for pixel
 * while dropping a label the mapset author wrote, and enabling it would invent behaviour and violate
 * Transformation Rule T9. A disabled control is the only rendering that keeps the text discoverable
 * and the action absent, which is the pair that matters for parity review.
 *
 * Two message lines, never merged
 * -------------------------------
 * Assumptions: this mapset declares BOTH message fields -- `INFOMSG` at row 22 carrying
 * `WS-INFO-MSG PIC X(40)`, the mode prompt, and `ERRMSG` at row 23 carrying `WS-RETURN-MSG PIC X(75)`,
 * the rejection or outcome. They are independent: `3250-SETUP-INFOMSG` (L1210-L1266) sets the prompt
 * from the mode alone and then moves the return message to the other field at L1264, so one turn can
 * paint a prompt and a rejection at once. Which line a message belongs to is not decided here either:
 * every entry in `STATUS_MESSAGES.COTRTUPC` carries the `field` name it was declared under, and
 * {@link messageChannel} reads it, so the routing is the catalog's own record rather than a second
 * opinion about it.
 *
 * This screen holds no session state
 * ----------------------------------
 * Refactoring Rationale: the reference keeps its mode, its before-image `TTUP-OLD-DETAILS` (L328-L331)
 * and its calling program in the passed communication area, echoed to the terminal between turns. None
 * of the three survives as session state, per AAP section 0.7.1: the mode is reducer state for one
 * mount, the before-image is the record last read together with the version the service issued -- so a
 * concurrent change is caught by the service rather than by a remembered copy -- and the return
 * destination arrives in the router's own transition state. The re-entry discriminator
 * `CDEMO-PGM-CONTEXT` disappears entirely, which is why every field highlight below is driven by a
 * refusal that is present now rather than by a remembered turn count.
 */

import {
  Button,
  ConfigProvider,
  Descriptions,
  Flex,
  Form,
  Input,
  Modal,
  Result,
  Typography,
  theme,
} from 'antd';
import type { DescriptionsProps, InputRef } from 'antd';
import { useCallback, useEffect, useReducer, useRef } from 'react';
import type { CSSProperties, ChangeEvent, ReactElement, ReactNode } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';

import {
  isApiRequestError,
  isConflictFailure,
  isRepeatableFailure,
  isTransientFailure,
} from '../../api/client';
import {
  createTransactionType,
  deleteTransactionType,
  getTransactionType,
  replaceTransactionType,
} from '../../api/reference';
import type { TransactionType } from '../../api/reference';
import type { AbendDetail, FieldError, FieldValidationState } from '../../api/types';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import {
  BLANK_FIELD_MARKER_CHARACTERS,
  busyAnnouncement,
  busyProps,
  fieldAriaProps,
  fieldRefusalRendering,
} from '../../layout/fieldHelp';
import type { MessageBandChannel } from '../../layout/MessageBand';
import { copybookFieldWidthStyle } from '../../layout/recordLayout';
import { ScreenTitle } from '../../layout/ScreenTitle';
import { usePfKeys } from '../../layout/usePfKeys';
import type {
  CicsAid,
  PfKeyHandlerEntry,
  PfKeyHandlerMap,
  PfKeyRejection,
  PfKeyRisk,
} from '../../layout/usePfKeys';
import {
  ABEND_DATA_FIELDS,
  ACCESS_DENIED_NOT_AUTHORIZED,
  FIELD_VALIDATION_SUFFIXES,
  PERSISTENT_FAILURE_REPORT_IT,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  STATUS_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
  fitsDeclaredWidth,
  normaliseForWire,
} from '../../messages/messages';
import {
  ADMIN_MENU_ROUTE,
  inApplicationRoute,
  isReferenceTypeCode,
  navigateSafely,
  referenceTypeEditRoute,
  screenTransitionState,
} from '../../routes/navigation';
import { destructiveFocusTheme } from '../../theme/antdTheme';
import { BMS_TEXT_COLOR_TOKENS, FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/*
 * WHY : Alternatives Considered: declaring the ADD destination here as well, beside the pattern below.
 *       Rejected because `ui/src/screens/refTypeList/index.tsx` already owns it as `REF_TYPE_ADD_ROUTE`
 *       and is the module that navigates to it, so a second declaration would be two places for one
 *       path to drift apart -- and this module cannot import that one, because each screen is a separate
 *       lazily loaded chunk and a module-scope import between them would merge the two. What this module
 *       owns instead is the SENTINEL it has to recognise, which is the only part of that path this screen
 *       reads; `ui/src/routerRoutes.test.tsx` holds the two halves in agreement.
 */

/**
 * Route pattern that enters this screen, whose parameter is a key or the add sentinel.
 *
 * Assumptions: the parameter is spelled `cd`, which is the name AAP section 0.4.1.4 fixes for this
 * screen's route -- `/reference/transaction-types/:cd` -- and which `ui/src/router.tsx` mounts this
 * screen under. The spelling is load-bearing rather than cosmetic: `useParams` resolves an unmatched
 * name to `undefined` with no diagnostic, so a near-miss such as `typeCd` or `code` would leave this
 * screen permanently prompting for a key the route already carried, with nothing reporting why.
 *
 * ⚠️ Refactoring Rationale: the parameter WAS spelled `typeCd`, matching the transport property the
 * service publishes. That agreement was the wrong one to keep: the route table is fixed by the AAP and
 * this constant is compared against the router's own declaration by `ui/src/routerRoutes.test.tsx`, so
 * the two names had to be brought together on the AAP's spelling rather than on the DTO's. The
 * property name on the wire is unaffected -- `typeCd` is what `ui/src/api/reference.ts` sends and
 * receives -- and only the route segment's label changed.
 */
export const REF_TYPE_EDIT_ROUTE = '/reference/transaction-types/:cd';

/**
 * Route segment that means "no key selected", which is the sentinel the list screen navigates to.
 *
 * Assumptions: a three-character sentinel cannot be mistaken for a real key, because `TR_TYPE` is
 * `CHAR(2)` in `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L2 -- so no code can ever be `new`. That
 * is the same reasoning `ui/src/screens/refTypeList/index.tsx` records at the constant it navigates by,
 * restated here because this is the module that has to recognise it.
 */
export const REF_TYPE_NEW_SENTINEL = 'new';

/**
 * CICS transaction identifier this screen replaces.
 *
 * Assumptions: `CTTU`, which `05 LIT-THISTRANID PIC X(4) VALUE 'CTTU'.` fixes at `COTRTUPC.cbl`
 * L203-L204 and which `app/app-transaction-type-db2/README.md` L38 and L79 both name in the
 * transaction table and the `DEFINE TRANSACTION` statement. It is user-visible -- `ScreenHeader`
 * paints it in the row-1 `Tran:` field -- so it is read out of the program's own literal rather than
 * inferred from the route.
 */
export const REF_TYPE_EDIT_TRANSACTION_ID = 'CTTU';

/** Source program name, from `05 LIT-THISPGM PIC X(8) VALUE 'COTRTUPC'.` at `COTRTUPC.cbl` L201-L202. */
export const REF_TYPE_EDIT_PROGRAM_NAME = 'COTRTUPC';

/** Mapset this screen stands in, which fixes the message bands' rendered width. */
export const REF_TYPE_EDIT_MAPSET = 'COTRTUP';

/**
 * Row-7 caption, from the anonymous `COLOR=NEUTRAL LENGTH=25` field at `COTRTUP.bms` L75-L78.
 *
 * Assumptions: mapset `INITIAL=` literals live at the render site rather than in
 * `ui/src/messages/messages.ts`. That catalog's boundary is the program's own message literals -- the
 * `88`-level values on `WS-INFO-MSG` and `WS-RETURN-MSG` -- and a field caption is neither; the
 * sibling `ui/src/screens/refTypeList/index.tsx` carries its captions the same way.
 */
export const REF_TYPE_EDIT_SUBTITLE = 'Maintain Transaction Type';

/**
 * The two field labels this mapset paints, carried with their internal padding.
 *
 * Assumptions: the padding before each colon is part of the painted literal -- the mapset writes
 * `'Transaction Type  :'` with two spaces at L83 and `'Description       :'` with seven at L93, so
 * that both colons fall in the same column of the character grid. It is carried rather than trimmed
 * because Transformation Rule T8 carries a user-visible string character for character, and because
 * trimming it here would make this module a second spelling of the mapset's own literal.
 */
/**
 * Column count for the record the destructive confirmation names.
 *
 * WHY : ⚠️ Refactoring Rationale: this replaces the element identifier both confirmation controls used to
 *       take their accessible description from, and the replacement is a consequence of the primitive
 *       rather than a reduction. That identifier existed because the previous overlay carried NO ARIA
 *       role, so focus moving to the safe choice announced `Cancel, button` and nothing else and the
 *       record had to be attached to each control by hand. A `Modal` is announced by name and its
 *       contents are read on open, so the same fact reaches the same operator through the surface's own
 *       semantics; keeping both would state one thing twice, and the hand-maintained identifier is the
 *       half that can rot.
 *
 *       Assumptions: ONE column, because the two rows are a key and a fifty-character description and a
 *       side-by-side pair would put the description in half the dialogue's width. The sibling list
 *       dialogue names its row the same way for the same reason.
 */
const DELETE_CONFIRMATION_RECORD_COLUMNS = 1;

/**
 * Identifier of the element naming the record inside the delete confirmation.
 *
 * ⚠️ Purpose: both of the confirmation's actions point at this element with `aria-describedby`, so the
 * record being destroyed is announced with whichever action the operator lands on, rather than only if
 * they happen to read the surface as a whole. That distinction is not theoretical here: browser
 * measurement found focus placed on the DECLINING control the moment this dialogue opens, so the record
 * naming has to travel with the control or it is never heard.
 *
 * ⚠️ Assumptions: the identifier is put on the record block rather than on the dialogue, and the
 * dialogue keeps `aria-labelledby` pointing at its own question. A description and a label are different
 * things -- the label says what is being asked and the description says which record it is being asked
 * about -- so collapsing them onto one element would lose one of the two.
 *
 * ⚠️ Refactoring Rationale: this screen was the outlier rather than the pattern.
 * `ui/src/screens/cardUpdate/index.tsx` and `ui/src/screens/authDetail/index.tsx` already wire their own
 * confirmations exactly this way, and cardUpdate's own note claimed this file did too -- a claim that was
 * false when written and is made true here rather than deleted.
 */
const DELETE_CONFIRMATION_RECORD_ID = 'ref-type-edit-delete-confirmation-record';

export const REF_TYPE_EDIT_FIELD_LABELS = {
  /** `COTRTUP.bms` L79-L83. */
  typeCode: 'Transaction Type  :',
  /** `COTRTUP.bms` L90-L93. */
  description: 'Description       :',
} as const;

/*
 * WHY : Assumptions: both widths are corroborated TWICE before being relied on, because a `maxLength`
 *       that is too generous is invisible until a service rejects a row. `TRTYPCD` is `LENGTH=2` at
 *       `COTRTUP.bms` L86 and `TRTYPCDI PIC X(2)` at `cpy-bms/COTRTUP.cpy` L60, and the column behind
 *       it is `TR_TYPE CHAR(2)` at `ddl/TRNTYPE.ddl` L2; `TRTYDSC` is `LENGTH=50` at L96, `TRTYDSCI PIC
 *       X(50)` at L66, and `TR_DESCRIPTION VARCHAR(50)` at L3. Mapset and table agree, so neither is
 *       being guessed from the other.
 */

/** Declared width of `TRTYPCD`, from `COTRTUP.bms` L84-L87 and `TR_TYPE CHAR(2)`. */
export const TYPE_CODE_WIDTH = 2;

/** Declared width of `TRTYDSC`, from `COTRTUP.bms` L94-L97 and `TR_DESCRIPTION VARCHAR(50)`. */
export const DESCRIPTION_WIDTH = 50;

/*
 * WHY : Assumptions: each control carries an EXPLICIT identifier and its label an explicit `htmlFor`,
 *       rather than letting the design system infer the association. The system wires a label to a
 *       control only for a form item holding one named field, and each item here holds two children --
 *       the control and the literal blank marker the baseline's templated highlight writes -- so nothing
 *       is inferred and the label would name no control at all. On the terminal the association WAS the
 *       position, at `POS=(12,26)` and `POS=(14,26)` beside their labels; design gap G1 gives that up, so
 *       the association has to be stated or it is lost rather than merely moved.
 */

/** Identifier joining the type-code label to its control. */
export const TYPE_CODE_CONTROL_ID = 'ref-type-edit-type-code';

/** Identifier joining the description label to its control. */
export const DESCRIPTION_CONTROL_ID = 'ref-type-edit-description';

/**
 * Occupies a control's suffix slot on every turn the blank marker is not due.
 *
 * Purpose: keep the two entry controls' DOM SHAPE constant, so the input element is never unmounted
 * and remounted while the operator is typing into it.
 *
 * ⚠ WHY : Refactoring Rationale: adopting the shared blank marker as the control's `suffix` introduced
 *       a defect that adopting it as a sibling element did not, and this constant is the remedy antd
 *       itself prescribes. `antd/lib/input/Input.js` L116 warns in development that "dynamic add or
 *       remove prefix / suffix will make it lose focus caused by dom structure change", and the cause
 *       is structural rather than incidental: `@rc-component/input/lib/BaseInput.js` returns the bare
 *       `<input>` when no affix is present and wraps it in a `<span>` when one is, so the element at
 *       that position changes type and React unmounts the input.
 *
 *       Measured, before this constant existed: the blank-description refusal was raised, the operator
 *       typed a ten-character replacement, and the control held ONE character -- the first keystroke
 *       cleared the refusal, the marker went, the input was replaced, and `isConnected` on the element
 *       that had focus was false. Every keystroke after the first reached a detached node. In a browser
 *       that is an operator correcting a refused field and watching nine of ten characters vanish.
 *
 *       Assumptions: the placeholder is a {@link Typography.Text} and not a bare element, so both
 *       states of the slot render the SAME component and React updates attributes rather than replacing
 *       anything -- `blankFieldMarker` in `ui/src/layout/fieldHelp.tsx` builds the marker from that
 *       component too.
 *
 *       Assumptions: it carries `aria-hidden` and no text, so an empty slot announces nothing. It is
 *       not the marker's absence made visible; it is the marker's absence made STRUCTURALLY equal to
 *       its presence.
 *
 *       Alternatives Considered: (1) rendering the marker beside the control instead of inside it,
 *       which is where this screen had it and which has no shape change at all. Rejected on fidelity:
 *       `app/cpy/CSSETATY.cpy` L24 MOVEs the asterisk into the screen FIELD, so it occupied the field's
 *       own columns, which a suffix inside the control's box corresponds to and an element after the
 *       box does not. (2) Leaving the marker mounted always and hiding it, which reserves the same
 *       space and additionally leaves a character in the DOM that is not true of the turn. (3) Keying
 *       the control so React reuses it -- a key does not help, because the element TYPE at that
 *       position is what changes.
 *
 *       Trade-offs: `.ant-input-suffix` is now present on both controls on every turn, so its inline
 *       margin is reserved whether or not a marker occupies it. Accepted: the alternative is an input
 *       that discards typing, and the controls are already sized as a CEILING by
 *       `copybookFieldWidthStyle` rather than to an exact column count, so a constant few pixels inside
 *       that ceiling changes nothing an operator can act on.
 */
const MARKER_SLOT_UNOCCUPIED: ReactElement = <Typography.Text aria-hidden="true" />;

/**
 * The mode this screen is in, transcribed one-to-one from the reference's `TTUP-*` condition names.
 *
 * Assumptions: all SIXTEEN states the reference declares at `COTRTUPC.cbl` L298-L327 are carried, not
 * the subset an operator visibly acts from. Four of them are terminal outcomes the reference resets on
 * the next turn (L405-L419), and it would be tempting to model those as messages rather than modes --
 * but the reset is a rule about a mode, `3250-SETUP-INFOMSG` selects a prompt from each of them, and
 * `3300-SETUP-SCREEN-ATTRS` protects the fields differently in each. Dropping them would move all
 * three of those decisions somewhere the reference does not make them.
 * @see REF_TYPE_EDIT_MODE_VALUES for the `TTUP-CHANGE-ACTION` byte each name carries.
 */
export type RefTypeEditMode =
  /** `TTUP-DETAILS-NOT-FETCHED`: waiting for a key to be typed. */
  | 'notFetched'
  /** `TTUP-INVALID-SEARCH-KEYS`: the key failed validation. */
  | 'invalidSearchKeys'
  /** `TTUP-DETAILS-NOT-FOUND`: the key was valid and named no row. */
  | 'detailsNotFound'
  /** `TTUP-SHOW-DETAILS`: the row was read and is on the glass. */
  | 'showDetails'
  /** `TTUP-CREATE-NEW-RECORD`: the operator accepted that a new row will be added. */
  | 'createNewRecord'
  /** `TTUP-REVIEW-NEW-RECORD`: declared by the reference and reached by no arm of it. */
  | 'reviewNewRecord'
  /** `TTUP-CONFIRM-DELETE`: a delete was asked for and awaits confirmation. */
  | 'confirmDelete'
  /** `TTUP-START-DELETE`: the delete was issued. */
  | 'startDelete'
  /** `TTUP-DELETE-DONE`: the delete committed. */
  | 'deleteDone'
  /** `TTUP-DELETE-FAILED`: the delete was refused. */
  | 'deleteFailed'
  /** `TTUP-CHANGES-NOT-OK`: edits are present and validation refused them. */
  | 'changesNotOk'
  /** `TTUP-CHANGES-OK-NOT-CONFIRMED`: edits passed validation and await a save. */
  | 'changesOkNotConfirmed'
  /** `TTUP-CHANGES-OKAYED-LOCK-ERROR`: the row could not be locked for update. */
  | 'changesOkayedLockError'
  /** `TTUP-CHANGES-OKAYED-BUT-FAILED`: the write was refused. */
  | 'changesOkayedButFailed'
  /** `TTUP-CHANGES-OKAYED-AND-DONE`: the write committed. */
  | 'changesOkayedAndDone'
  /** `TTUP-CHANGES-BACKED-OUT`: the pending change was cancelled. */
  | 'changesBackedOut';

/**
 * The `TTUP-CHANGE-ACTION` byte behind each mode, recorded so the transcription is auditable.
 *
 * Assumptions: the initial state is `LOW-VALUES`, which the reference treats as equal to `SPACES`
 * through `88 TTUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES.` at L298-L300 -- so one mode name
 * stands for two byte values and the entry records the declared initialiser at L297.
 */
export const REF_TYPE_EDIT_MODE_VALUES: Readonly<Record<RefTypeEditMode, string>> = {
  notFetched: 'LOW-VALUES',
  invalidSearchKeys: 'K',
  detailsNotFound: 'X',
  showDetails: 'S',
  createNewRecord: 'R',
  reviewNewRecord: 'V',
  confirmDelete: '9',
  startDelete: '8',
  deleteDone: '7',
  deleteFailed: '6',
  changesNotOk: 'E',
  changesOkNotConfirmed: 'N',
  changesOkayedLockError: 'L',
  changesOkayedButFailed: 'F',
  changesOkayedAndDone: 'C',
  changesBackedOut: 'B',
};

/*
 * WHY : Assumptions: the three groupings below are the reference's OWN multi-valued `88`-levels, not
 *       convenience sets invented here, and each is used by more than one paragraph -- which is exactly
 *       why the reference grouped them. Writing the member lists out at each use site would put the same
 *       three sets in four places, and the reference already demonstrates the alternative it preferred.
 */

/**
 * Reports whether a delete is under way, transcribing `88 TTUP-DELETE-IN-PROGRESS VALUES '9', '8',
 * '7', '6'.` at `COTRTUPC.cbl` L307-L309.
 * @param {RefTypeEditMode} mode - The mode the screen is in.
 * @returns {boolean} `true` for the confirm, issued, done and failed delete modes.
 */
export function isDeleteInProgress(mode: RefTypeEditMode): boolean {
  return (
    mode === 'confirmDelete' ||
    mode === 'startDelete' ||
    mode === 'deleteDone' ||
    mode === 'deleteFailed'
  );
}

/**
 * Reports whether edits are pending or were attempted, transcribing `88 TTUP-CHANGES-MADE VALUES 'E',
 * 'N', 'L', 'F'.` at `COTRTUPC.cbl` L315-L317.
 * @param {RefTypeEditMode} mode - The mode the screen is in.
 * @returns {boolean} `true` for the refused, validated, lock-failed and write-failed modes.
 */
export function hasChangesMade(mode: RefTypeEditMode): boolean {
  return (
    mode === 'changesNotOk' ||
    mode === 'changesOkNotConfirmed' ||
    mode === 'changesOkayedLockError' ||
    mode === 'changesOkayedButFailed'
  );
}

/**
 * Reports whether an accepted change then failed, transcribing `88 TTUP-CHANGES-FAILED VALUES 'L',
 * 'F'.` at `COTRTUPC.cbl` L322.
 * @param {RefTypeEditMode} mode - The mode the screen is in.
 * @returns {boolean} `true` when the row could not be locked or the write was refused.
 */
export function haveChangesFailed(mode: RefTypeEditMode): boolean {
  return mode === 'changesOkayedLockError' || mode === 'changesOkayedButFailed';
}

/**
 * The five attention identifiers this screen's mapset paints a legend for, plus Enter.
 *
 * Assumptions: `PFK06` is a member even though nothing dispatches it, because the mapset declares
 * `FKEY06 ... INITIAL='F6=Add'` at `COTRTUP.bms` L126-L130 and the bar renders one control per member.
 * Its availability is settled in {@link refTypeEditKeyMatrix}, where it is refused in every mode.
 */
export const REF_TYPE_EDIT_AIDS = ['ENTER', 'PFK03', 'PFK04', 'PFK05', 'PFK06', 'PFK12'] as const;

/** One of the six attention identifiers this screen carries a legend for. */
export type RefTypeEditKeyAid = (typeof REF_TYPE_EDIT_AIDS)[number];

/**
 * The four legend labels this mapset paints on row 24, plus the two the first field packs together.
 *
 * Assumptions: the row-24 fields are read out of `COTRTUP.bms` L111-L135 exactly as spelled there.
 * `FKEYS` holds TWO labels in one literal, `'ENTER=Process F3=Exit'` at L115, separated by a single
 * space rather than the double space other mapsets use -- so it is split at that space and both halves
 * are carried as the mapset spells them. The remaining four are fields of their own.
 *
 * ⚠ Assumptions: `PFK04` is `'F4=Delete'`, and `ui/src/layout/PfKeyBar.tsx` exports
 * `UNIFORM_PF_KEY_LABELS` whose `PFK04` entry reads `'F4=Clear'`. That default is right for the screens
 * whose mapsets paint it and wrong here: this mapset paints `'F4=Delete'` at L120 and the program
 * dispatches `CCARD-AID-PFK04` to the delete confirmation at L482-L498, so the application-wide
 * PF4-clears convention does not hold on this screen. The uniform constant is deliberately NOT imported
 * anywhere in this module -- inheriting it would relabel a destructive action as a harmless one.
 */
export const REF_TYPE_EDIT_KEY_LABELS: Readonly<Record<RefTypeEditKeyAid, string>> = {
  /** First half of `FKEYS`: Enter validates what was typed and decides the next mode. */
  ENTER: 'ENTER=Process',
  /** Second half of `FKEYS`: PF3 leaves the screen. */
  PFK03: 'F3=Exit',
  /** `FKEY04`, `COTRTUP.bms` L116-L120. */
  PFK04: 'F4=Delete',
  /** `FKEY05`, `COTRTUP.bms` L121-L125. */
  PFK05: 'F5=Save',
  /** `FKEY06`, `COTRTUP.bms` L126-L130 -- painted, never live. */
  PFK06: 'F6=Add',
  /** `FKEY12`, `COTRTUP.bms` L131-L135. */
  PFK12: 'F12=Cancel',
};

/**
 * The risk each of this mapset's six legends carries, read from what its LABEL says the key does.
 *
 * ⚠ Purpose: `ui/src/layout/PfKeyBar.tsx` paints a control's emphasis from this declaration, and the
 * declaration exists because the alternative -- emphasis chosen from the attention IDENTIFIER -- cannot be
 * right on this screen and the sibling list screen at once. `PFK04` deletes a stored row here and clears a
 * form on the screens whose mapsets paint `'F4=Clear'`; `PFK10` saves on the list screen and pages
 * elsewhere. One table keyed on the identifier must therefore be wrong for one of them, so the risk is
 * declared per screen, from the label the mapset paints.
 *
 * ⚠ Assumptions: `PFK04` is the only DESTRUCTIVE entry, and it is destructive in every mode -- the label
 * is verbatim `'F4=Delete'` in both the arming and the confirming mode, because `COTRTUP.bms` L116-L120
 * paints one literal and rule T8 carries it character for character, so the control cannot say which
 * press this is. The dialogue the confirming mode raises is what says so instead, and the emphasis is what
 * says the key destroys something in both.
 *
 * Assumptions: `PFK05` is MUTATING because its label promises a write and it performs two of them --
 * `2000-DECIDE-ACTION` at `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` replaces a stored row from
 * `changesOkNotConfirmed` and inserts a new one from the not-found stage, which is the real add path.
 *
 * Assumptions: `PFK06` is declared MUTATING from its label even though `0001-CHECK-PFKEYS` carries no
 * `CCARD-AID-PFK06` term and the key is refused in every mode. The declaration states what the legend
 * PROMISES, which is what an operator reads; that the promise is never honoured is carried by the
 * control's disabled state, and the design system's disabled styling is what an operator sees.
 * Alternatives Considered: omitting the entry so the key falls back to the bar's identifier-driven
 * default. Rejected because an absent entry reads as "nobody decided", where this one records that the
 * label was read and what it says.
 *
 * Assumptions: the three remaining entries are READ-ONLY, and none of them writes. `'ENTER=Process'`
 * validates what was typed and fetches the row -- the reference requires PF5 for the save, so processing
 * is not a write here; `'F3=Exit'` transfers; and `'F12=Cancel'` backs out local edits or withdraws a
 * pending delete, which is the reference's L1000-L1002 arm and touches no stored row.
 */
export const REF_TYPE_EDIT_KEY_RISKS: Readonly<Record<RefTypeEditKeyAid, PfKeyRisk>> = {
  ENTER: 'read-only',
  PFK03: 'read-only',
  PFK04: 'destructive',
  PFK05: 'mutating',
  PFK06: 'mutating',
  PFK12: 'read-only',
};

/**
 * Whether one key is dispatched, and whether its legend is on the glass, in one mode.
 *
 * Assumptions: the two are SEPARATE flags because the reference separates them. A `DFHBMDAR` attribute
 * makes a field non-display; it does not stop the terminal accepting the key, so a legend can be dark
 * while the press still works. `PFK05` is the case that proves it and `PFK03` is the case that matters.
 */
export interface RefTypeEditKeyState {
  /** Whether `0001-CHECK-PFKEYS` sets `PFK-VALID` for this key in this mode. */
  readonly accepted: boolean;
  /** Whether `3391-SETUP-PFKEY-ATTRS` brightens this key's legend field in this mode. */
  readonly revealed: boolean;
}

/** Acceptance and visibility for every key this screen carries, in one mode. */
export type RefTypeEditKeyMatrix = Readonly<Record<RefTypeEditKeyAid, RefTypeEditKeyState>>;

/*
 * WHY : Alternatives Considered: two selectors, one transcribing `0001-CHECK-PFKEYS` for dispatch and
 *       one transcribing `3391-SETUP-PFKEY-ATTRS` for the legend. Rejected on the reference's own
 *       evidence: L579-L580 comments `Should mirror logic in PFKey attribut para 3391-PFKEY-ATTRS` and
 *       L1398 comments the converse, so the program itself carries two hand-maintained copies of one
 *       rule and warns the reader about it. Two copies here would inherit that hazard -- and they have
 *       already drifted once in the reference, which reveals PF5 in two modes while accepting it in
 *       three. ONE matrix carrying both flags is what makes that asymmetry a recorded fact rather than
 *       a discrepancy waiting to be "tidied up".
 */

/**
 * Transcribes `0001-CHECK-PFKEYS` and `3391-SETUP-PFKEY-ATTRS` into one table for one mode.
 *
 * Assumptions: `ENTER` and `PFK03` share ONE legend field, `FKEYS`, so the reveal is bidirectional
 * rather than a fixed legend with four additions -- L1400-L1404 moves `DFHBMDAR` into `FKEYSA` while a
 * delete is being confirmed and `DFHBMASB` into it otherwise, so `'ENTER=Process F3=Exit'` DISAPPEARS in
 * that one mode. Their acceptance still differs: L582-L583 accepts `PFK03` unconditionally and `ENTER`
 * in every mode but that one, so the exit works while its label is hidden and the operator is never
 * trapped in the confirmation.
 *
 * Assumptions: the other three legends start dark. `FKEY04`, `FKEY05`, `FKEY06` and `FKEY12` are all
 * `ATTRB=(ASKIP,DRK)` in the mapset, and L1406-L1422 brightens three of them per mode, so their
 * hidden-then-shown state is observable behaviour tied to the mode rather than a static footer.
 * @param {RefTypeEditMode} mode - The mode the screen is in.
 * @returns {RefTypeEditKeyMatrix} Acceptance and visibility for each of the six keys.
 */
export function refTypeEditKeyMatrix(mode: RefTypeEditMode): RefTypeEditKeyMatrix {
  const confirmingDelete = mode === 'confirmDelete';
  const deletable = mode === 'showDetails' || confirmingDelete;
  const savable = mode === 'changesOkNotConfirmed' || mode === 'detailsNotFound';
  const cancellable =
    mode === 'changesOkNotConfirmed' ||
    mode === 'showDetails' ||
    mode === 'detailsNotFound' ||
    confirmingDelete ||
    mode === 'createNewRecord';

  return {
    // Assumptions: L583 -- `CCARD-AID-ENTER AND NOT TTUP-CONFIRM-DELETE`; L1400-L1404 darkens the field
    //   that carries its label in the same one mode, so the two flags agree here.
    ENTER: { accepted: !confirmingDelete, revealed: !confirmingDelete },
    // Assumptions: L582 accepts `CCARD-AID-PFK03` with no condition at all, while its label lives in
    //   the field L1400-L1404 darkens -- the one place acceptance and visibility diverge for a key the
    //   operator needs. Refusing the exit to match the dark legend would make the confirmation a dead
    //   end the reference does not have.
    PFK03: { accepted: true, revealed: !confirmingDelete },
    // Assumptions: L584-L586 and L1406-L1409 name the same two modes, so both flags follow one set.
    PFK04: { accepted: deletable, revealed: deletable },
    // ⚠ Assumptions: the asymmetry is PRESERVED, not smoothed. L588-L593 accepts `PFK05` for
    //   `TTUP-CHANGES-OK-NOT-CONFIRMED`, `TTUP-DETAILS-NOT-FOUND` OR `TTUP-DELETE-IN-PROGRESS`, while
    //   L1411-L1414 reveals it for only the first two. So during a delete the key is live with no
    //   legend. Widening the reveal to match, or narrowing the acceptance, would each change behaviour
    //   the reference exhibits on the glass.
    PFK05: { accepted: savable || isDeleteInProgress(mode), revealed: savable },
    // Assumptions: refused in EVERY mode. `0001-CHECK-PFKEYS` has no `CCARD-AID-PFK06` term, so PF6
    //   falls to its `ELSE` and is reported as an invalid key; `3391` has no `FKEY06A` arm, so the
    //   legend is never brightened. Both halves are transcribed: the label renders, the press does not
    //   dispatch. The file overview records why the label is rendered at all.
    PFK06: { accepted: false, revealed: true },
    // Assumptions: L594-L601 and L1416-L1422 name the same five modes.
    PFK12: { accepted: cancellable, revealed: cancellable },
  };
}

/** Which of the four conditional legends one mode reveals, as `3391-SETUP-PFKEY-ATTRS` sets them. */
export interface StageKeyAvailability {
  /** Whether the `FKEYS` field carrying `'ENTER=Process F3=Exit'` is brightened. */
  readonly enter: boolean;
  /** Whether the `FKEY04` legend is brightened. */
  readonly delete: boolean;
  /** Whether the `FKEY05` legend is brightened. */
  readonly save: boolean;
  /** Whether the `FKEY12` legend is brightened. */
  readonly cancel: boolean;
}

/**
 * Projects {@link refTypeEditKeyMatrix}'s visibility flags into the four-legend view.
 *
 * Assumptions: this reports what the terminal SHOWS, not what it accepts, which is why `save` is
 * `false` in the delete modes that nonetheless dispatch PF5 -- {@link RefTypeEditKeyState} keeps the
 * two apart and this projection reads only one of them. A caller deciding whether to dispatch a key
 * must read the matrix instead.
 *
 * Alternatives Considered: transcribing `3391` again here rather than projecting. Rejected for the
 * reason recorded at the matrix: a second copy of a paragraph the reference itself warns about drifting
 * is the defect, not the convenience.
 * @param {RefTypeEditMode} mode - The mode the screen is in.
 * @returns {StageKeyAvailability} Which of the four conditional legends this mode reveals.
 */
export function stageKeyAvailability(mode: RefTypeEditMode): StageKeyAvailability {
  const matrix = refTypeEditKeyMatrix(mode);
  return {
    enter: matrix.ENTER.revealed,
    delete: matrix.PFK04.revealed,
    save: matrix.PFK05.revealed,
    cancel: matrix.PFK12.revealed,
  };
}

/**
 * Narrows an attention identifier to the six this screen carries.
 *
 * Assumptions: `usePfKeys` reports a rejection for any of the sixteen `CICS_AIDS`, including the ten
 * this mapset paints no legend for, so a press of PF9 arrives here as well as a press of PF6. Both are
 * refused, and this guard is what lets the refusal read the matrix for the six it describes without
 * widening the matrix to sixteen entries the mapset does not declare.
 * @param {CicsAid} aid - The identifier `usePfKeys` resolved from the press.
 * @returns {boolean} `true` when the mapset paints a legend for this identifier.
 */
export function isRefTypeEditAid(aid: CicsAid): aid is RefTypeEditKeyAid {
  return (REF_TYPE_EDIT_AIDS as readonly CicsAid[]).includes(aid);
}

/** The prompts and outcomes this program declares as condition names on its two message fields. */
const EDIT_STATUS = STATUS_MESSAGES.COTRTUPC;

/** The one message this program declares as a literal of its own, which is its key field's label. */
const EDIT_MESSAGES = PROGRAM_MESSAGES.COTRTUPC;

/** Field names the catalog records for the two abend members this screen labels. */
const ABEND_LABELS = {
  abendCode: ABEND_DATA_FIELDS[0].field,
  abendCulprit: ABEND_DATA_FIELDS[1].field,
} as const;

/**
 * Transport status the contract declares for a resource that could not be obtained now.
 *
 * Assumptions: this stands in for Db2 `SQLCODE -911`, the lock timeout at `COTRTUPC.cbl` L1561. The
 * literal is named rather than compared inline so the mapping is stated once, which is the same reason
 * `ui/src/api/client.ts` exports `isConflictFailure` instead of letting each screen compare with 409.
 */
const SERVICE_UNAVAILABLE_STATUS = 503;

/** Matches an entry consisting only of decimal digits, which is what `TEST-NUMVAL` admits here. */
const DIGITS_ONLY = /^[0-9]+$/u;

/** Matches an entry of letters, digits and spaces only, which is the reference's alphanumeric edit. */
const ALPHANUMERIC_OR_SPACE = /^[A-Za-z0-9 ]*$/u;

/** Strips everything but decimal digits from a typed key. */
const NON_DIGITS = /[^0-9]/gu;

/** Which of the two controls a refusal or the cursor belongs to. */
export type RefTypeEditField = 'typeCode' | 'description';

/**
 * One refused control, carrying the sentence and WHICH of the two refusals it is.
 *
 * Assumptions: `state` is the distinction `app/cpy/CSSETATY.cpy` L17-L27 turns on. Its outer test is
 * `FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK` and sets the colour for either, while its INNER test
 * is `IF FLG-(TESTVAR1)-BLANK` and additionally writes a literal `'*'` into the field. So a malformed
 * entry reddens and a missing one reddens AND is marked, and collapsing the two into one boolean would
 * lose the marker's condition. It reuses the service's own vocabulary,
 * {@link FieldValidationState} = `'NOT_OK' | 'BLANK'`, so a locally composed refusal and one delivered
 * in a problem document are the same shape.
 */
export interface RefTypeEditRefusal {
  /** The control the refusal belongs to. */
  readonly field: RefTypeEditField;
  /** Whether the entry was missing or merely malformed. */
  readonly state: FieldValidationState;
  /** The composed sentence, verbatim from the catalog's two halves. */
  readonly message: string;
}

/*
 * WHY : Assumptions: every refusal sentence is COMPOSED from a catalogued label and a catalogued
 *       suffix, never written out joined, because that is how the reference builds it. `1230-EDIT-
 *       ALPHANUM-REQD` and `1245-EDIT-NUM-REQD` each run `STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
 *       <suffix> DELIMITED BY SIZE INTO WS-RETURN-MSG` at L864-L869, L891-L896, L941-L946 and L959-L964,
 *       so the label is trimmed and the suffix carries its own leading space. Holding the joined result
 *       would be a third spelling of a value neither half owns, and the suffixes are shared with four
 *       other programs.
 */

/**
 * Composes one refusal from the catalogued field label and the catalogued suffix.
 * @param {RefTypeEditField} field - The control being refused.
 * @param {string} label - Catalogued field name, trimmed as `FUNCTION TRIM` trims it.
 * @param {string} suffix - Catalogued sentence ending, carrying its own leading space.
 * @param {FieldValidationState} state - Whether the entry was missing or malformed.
 * @returns {RefTypeEditRefusal} The refusal, carrying the joined sentence.
 */
function refuse(
  field: RefTypeEditField,
  label: string,
  suffix: string,
  state: FieldValidationState,
): RefTypeEditRefusal {
  return { field, state, message: `${label.trim()}${suffix}` };
}

/**
 * The control each name the service may use in a field error belongs to.
 *
 * Assumptions: the service names the transport property, `typeCd` or `description`, which is the
 * vocabulary `TransactionTypeCreateRequest` publishes -- so the mapping is a translation between the
 * response body's names and this screen's two controls rather than a guess at either. A name outside the
 * table resolves to nothing and its sentence still reaches the message line, which is how
 * `ui/src/screens/accountUpdate/index.tsx` treats the same case: this module does not get to decide what
 * the service is allowed to name.
 */
const SERVICE_FIELD_CONTROLS: Readonly<Record<string, RefTypeEditField>> = {
  typeCd: 'typeCode',
  description: 'description',
};

/*
 * WHY : Refactoring Rationale: the three helpers below exist so that no array-method callback in this
 *       module is an inline arrow. `ui/eslint.config.js` requires a documentation block on a function
 *       expression in ANY position, and Prettier detaches a block comment that follows an argument comma
 *       -- so an inline predicate either fails the gate or has its explanation moved away from it. Naming
 *       the three predicates once also puts the "one refusal per control" invariant in one place instead
 *       of at each of the six sites that assume it.
 */

/**
 * Reports whether a refusal is in force on one control.
 * @param {readonly RefTypeEditRefusal[]} refusals - The refusals to search.
 * @param {RefTypeEditField} field - The control to look for.
 * @returns {boolean} `true` when a refusal names that control.
 */
function hasRefusalOn(refusals: readonly RefTypeEditRefusal[], field: RefTypeEditField): boolean {
  return findRefusalOn(refusals, field) !== undefined;
}

/**
 * Finds the refusal in force on one control.
 * @param {readonly RefTypeEditRefusal[]} refusals - The refusals to search.
 * @param {RefTypeEditField} field - The control to look for.
 * @returns {RefTypeEditRefusal | undefined} The refusal naming that control, or `undefined`.
 */
function findRefusalOn(
  refusals: readonly RefTypeEditRefusal[],
  field: RefTypeEditField,
): RefTypeEditRefusal | undefined {
  for (const refusal of refusals) {
    if (refusal.field === field) {
      return refusal;
    }
  }
  return undefined;
}

/**
 * Keeps only the refusals naming one control, or only those naming any other.
 * @param {readonly RefTypeEditRefusal[]} refusals - The refusals to filter.
 * @param {RefTypeEditField} field - The control to select on.
 * @param {boolean} keep - `true` to keep refusals on that control, `false` to drop them.
 * @returns {readonly RefTypeEditRefusal[]} The selected refusals, order preserved.
 */
function selectRefusals(
  refusals: readonly RefTypeEditRefusal[],
  field: RefTypeEditField,
  keep: boolean,
): readonly RefTypeEditRefusal[] {
  const selected: RefTypeEditRefusal[] = [];
  for (const refusal of refusals) {
    if ((refusal.field === field) === keep) {
      selected.push(refusal);
    }
  }
  return selected;
}

/**
 * Translates a response's field errors into refusals this screen can mark.
 *
 * Assumptions: the FIRST entry for a control wins, because the array's order is the order the service
 * validated in and the reference's guarded setters mean the earliest failure is the one an operator
 * reads. The `state` member travels through unchanged, which is what lets a service-reported blank field
 * carry the same literal marker a locally detected one carries.
 * @param {readonly FieldError[]} errors - The array exactly as the response carried it.
 * @returns {readonly RefTypeEditRefusal[]} At most one refusal per control, first occurrence retained.
 */
export function toRefusals(errors: readonly FieldError[]): readonly RefTypeEditRefusal[] {
  const refusals: RefTypeEditRefusal[] = [];
  for (const error of errors) {
    const field = SERVICE_FIELD_CONTROLS[error.field];
    if (field !== undefined && !hasRefusalOn(refusals, field)) {
      refusals.push({ field, state: error.state, message: error.message });
    }
  }
  return refusals;
}

/**
 * Validates a typed type code the way `1210-EDIT-TRANTYPE` validates it.
 *
 * ⚠ Assumptions: the key is NUMERIC, not alphanumeric. `1210-EDIT-TRANTYPE` sets the field name to
 * `'Tran Type code'` at L826 and then performs `1245-EDIT-NUM-REQD` at L829, NOT the
 * `1230-EDIT-ALPHANUM-REQD` the description uses -- so a code of `'A1'` is refused here and would have
 * been accepted by the other edit. The three refusals are, in the order that paragraph tests them:
 * missing at L912-L929, `FUNCTION TEST-NUMVAL ... NOT = 0` at L934-L948, and `FUNCTION NUMVAL ... = 0`
 * at L954-L966.
 * @param {string} entry - The type code as typed.
 * @returns {RefTypeEditRefusal | null} The refusal, or `null` when the entry is acceptable.
 */
export function refuseTypeCode(entry: string): RefTypeEditRefusal | null {
  const typed = entry.trim();
  if (typed === '') {
    return refuse(
      'typeCode',
      EDIT_MESSAGES.TRAN_TYPE_CODE,
      FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED,
      'BLANK',
    );
  }
  // WHY : Assumptions: a positive character-class match rather than `Number.isNaN(Number(entry))`,
  //       because `Number('1e1')` and `Number(' 1')` are both numbers to JavaScript while
  //       `FUNCTION TEST-NUMVAL` on a two-byte `PIC X` field accepts only digit characters. The class
  //       match reproduces that domain; the numeric coercion would widen it and admit `'1e'`.
  if (!DIGITS_ONLY.test(typed)) {
    return refuse(
      'typeCode',
      EDIT_MESSAGES.TRAN_TYPE_CODE,
      FIELD_VALIDATION_SUFFIXES.MUST_BE_NUMERIC,
      'NOT_OK',
    );
  }
  if (Number.parseInt(typed, 10) === 0) {
    return refuse(
      'typeCode',
      EDIT_MESSAGES.TRAN_TYPE_CODE,
      FIELD_VALIDATION_SUFFIXES.MUST_NOT_BE_ZERO,
      'NOT_OK',
    );
  }
  return null;
}

/**
 * Validates a typed description the way `1230-EDIT-ALPHANUM-REQD` validates it.
 *
 * ⚠ Assumptions: DIGITS ARE ALLOWED. The paragraph converts every character in
 * `LIT-ALL-ALPHANUM-FROM-X` -- the twenty-six upper, twenty-six lower AND ten digits assembled at
 * L230-L237 -- to a space at L878-L880 and then requires the remainder to be empty, so a space
 * survives by being what everything else becomes. The sentence it emits says so: `' can have numbers
 * or alphabets only.'` at L893. The program separately DECLARES
 * `'Name can only contain alphabets and spaces'` at L173, which would imply a stricter rule; that
 * condition name has no set-site anywhere in the program, so no letters-only path is invented here.
 * @param {string} entry - The description as typed.
 * @returns {RefTypeEditRefusal | null} The refusal, or `null` when the entry is acceptable.
 */
export function refuseDescription(entry: string): RefTypeEditRefusal | null {
  const typed = entry.trim();
  if (typed === '') {
    return refuse(
      'description',
      SHARED_MESSAGES.TRANSACTION_DESC,
      FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED,
      'BLANK',
    );
  }
  if (!ALPHANUMERIC_OR_SPACE.test(typed)) {
    return refuse(
      'description',
      SHARED_MESSAGES.TRANSACTION_DESC,
      FIELD_VALIDATION_SUFFIXES.CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY,
      'NOT_OK',
    );
  }
  return null;
}

/**
 * Reports the refusal sentence a typed type code earns, or `null` when it is acceptable.
 *
 * Assumptions: this is the sentence-only projection of {@link refuseTypeCode}, kept as its own export
 * because the refusal's `state` matters to the renderer and not to a caller that only paints a message.
 * @param {string} entry - The type code as typed.
 * @returns {string | null} The composed sentence, or `null` when the entry is acceptable.
 */
export function validateTypeCode(entry: string): string | null {
  return refuseTypeCode(entry)?.message ?? null;
}

/**
 * Reports the refusal sentence a typed description earns, or `null` when it is acceptable.
 * @param {string} entry - The description as typed.
 * @returns {string | null} The composed sentence, or `null` when the entry is acceptable.
 */
export function validateDescription(entry: string): string | null {
  return refuseDescription(entry)?.message ?? null;
}

/**
 * Normalises an accepted type code to the two-digit, zero-filled form the reference stores.
 *
 * ⚠ Assumptions: the key that reaches the service is NOT the key as typed. On a valid edit,
 * L834-L842 runs `COMPUTE WS-EDIT-NUMERIC-2 = FUNCTION NUMVAL(...)` into a `PIC 9(02)`, moves that to a
 * `PIC X(02)` and then `INSPECT ... REPLACING ALL SPACES BY ZEROS` -- so `'1'` becomes `'01'` and
 * `' 5'` becomes `'05'`. Sending the typed form instead would look up a different key than the
 * reference does, and `TR_TYPE` being `CHAR(2)` means `'1 '` and `'01'` are different rows.
 * @param {string} entry - A type code that {@link refuseTypeCode} accepted.
 * @returns {string} The code as two digits, zero-filled on the left.
 */
export function normaliseTypeCode(entry: string): string {
  return String(Number.parseInt(entry.trim(), 10)).padStart(TYPE_CODE_WIDTH, '0');
}

/*
 * WHY : Assumptions: the mode-to-prompt table below is `3250-SETUP-INFOMSG` (L1213-L1247) arm for arm,
 *       INCLUDING its fall-throughs, which is where a reading of it goes wrong. A COBOL `EVALUATE` arm
 *       with no statements of its own runs the next arm's block, and this paragraph uses that four times.
 *       The consequential one is `WHEN TTUP-SHOW-DETAILS` at L1221: it has no statements, so it falls
 *       into L1225 and sets `PROMPT-FOR-SEARCH-KEYS`. A row with a fetched record therefore prompts for
 *       a key again rather than announcing itself.
 * WHY : ⚠ Trade-offs: `'Selected transaction type shown above'` is consequently NEVER displayed. It is
 *       declared as `88 FOUND-TRANTYPE-DATA` at L145-L146 and would be the natural prompt for a shown
 *       row, but grepping the program for that condition name finds only the declaration -- the `SET`
 *       statements at L528 and L1488 name `FOUND-TRANTYPE-IN-TABLE`, a DIFFERENT flag declared at L127
 *       recording that the SQL found a row. Two similarly named flags, one live and one dead. It stays
 *       catalogued and unused here rather than being wired to the mode it appears to describe, because
 *       inventing that path would add a message the terminal never showed. The same holds for
 *       `'Looks Good.... so far'` (L195-L196) and `'Invalid Key pressed. '` (L171-L172), whose only
 *       set-site is inside the commented-out block at L611-L616.
 */

/**
 * Selects the row-22 prompt for one mode, transcribing `3250-SETUP-INFOMSG`.
 *
 * Assumptions: the paragraph's last arm is `WHEN WS-NO-INFO-MESSAGE` at L1245-L1246, which sets the
 * search-key prompt whenever nothing above matched -- so the prompt is never empty and the two modes no
 * arm names, `startDelete` and `reviewNewRecord`, resolve through it rather than through a gap. That is
 * why this returns `string` and not `string | null`.
 * @param {RefTypeEditMode} mode - The mode the screen is in.
 * @param {boolean} hasOldType - Whether the before-image carries a type code, which is the
 *   `TTUP-OLD-TTYP-TYPE = LOW-VALUES OR = SPACES` test at L1223-L1224 inverted.
 * @returns {string} The catalogued prompt this mode paints on row 22.
 */
export function modeInfoMessage(mode: RefTypeEditMode, hasOldType: boolean): string {
  switch (mode) {
    case 'detailsNotFound':
      // Assumptions: L1219-L1220. The prompt is zero-padded -- `'Press F05 to add. F12 to cancel'`
      //   spells the function key `F05` and the other `F12`, which is how the mapset author wrote it.
      return EDIT_STATUS.PROMPT_CREATE_NEW_RECORD.text;
    case 'changesNotOk':
      return EDIT_STATUS.PROMPT_FOR_CHANGES.text;
    case 'changesBackedOut':
      // Assumptions: L1222-L1228. A backed-out change with a before-image prompts for the change again;
      //   with a blank one it falls into the search-key arm, because there is no longer a row to change.
      return hasOldType
        ? EDIT_STATUS.PROMPT_FOR_CHANGES.text
        : EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text;
    case 'confirmDelete':
      return EDIT_STATUS.PROMPT_DELETE_CONFIRM.text;
    case 'deleteFailed':
    case 'changesOkayedLockError':
    case 'changesOkayedButFailed':
      // Assumptions: L1231-L1232 and L1241-L1244 all set `INFORM-FAILURE`, so all three failure modes
      //   share one prompt while their row-23 sentences differ.
      return EDIT_STATUS.INFORM_FAILURE.text;
    case 'deleteDone':
      return EDIT_STATUS.CONFIRM_DELETE_SUCCESS.text;
    case 'createNewRecord':
      return EDIT_STATUS.PROMPT_FOR_NEWDATA.text;
    case 'changesOkNotConfirmed':
      // Assumptions: `'Changes validated.Press F5 to save'` has NO space after the period. It is
      //   transcribed as declared at L161 rather than repaired.
      return EDIT_STATUS.PROMPT_FOR_CONFIRMATION.text;
    case 'changesOkayedAndDone':
      return EDIT_STATUS.CONFIRM_UPDATE_SUCCESS.text;
    case 'notFetched':
    case 'invalidSearchKeys':
    case 'showDetails':
    case 'reviewNewRecord':
    case 'startDelete':
      // Assumptions: L1216-L1218 for the first two, the L1221 fall-through for `showDetails`, and the
      //   L1245 catch-all for the last two.
      return EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text;
    default:
      return EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text;
  }
}

/**
 * Reports which of the mapset's two message lines a catalogued sentence belongs to.
 *
 * Assumptions: the catalog records the COBOL field each sentence was declared under -- `'WS-INFO-MSG'`
 * for the row-22 prompts and `'WS-RETURN-MSG'` for the row-23 outcomes -- so the routing is read from
 * the declaration rather than decided here. `COMMON-RETURN` at L560 moves `WS-RETURN-MSG` to
 * `CCARD-ERROR-MSG`, and `3250` at L1264 moves it to `ERRMSGO`, which is what fixes the second line.
 * @param {string} field - The `field` member the catalog entry carries.
 * @returns {MessageBandChannel} The band the sentence is painted on.
 */
export function messageChannel(field: string): MessageBandChannel {
  return field === 'WS-INFO-MSG' ? 'information' : 'error';
}

/*
 * WHY : ⚠ Trade-offs: none of this program's five Db2 diagnostic sentences is imported, because none of
 *       them is exported as displayable text. `ui/src/messages/messages.ts` applies an audience policy
 *       and registers them in `REDACTED_DIAGNOSTICS`, each row naming the baseline site, the condition
 *       and the `replacement` the target shows instead -- L1499 read-failed, L1569 update-failed, L1609
 *       insert-failed, L1641 delete-restricted and L1654 delete-failed. The withheld halves are
 *       `'Error accessing:'` + `' TRANSACTION_TYPE table. SQLCODE:'`, `'Error updating:'` +
 *       `' TRANSACTION_TYPE Table. SQLCODE:'` (the two casings of "table" are DISTINCT strings and are
 *       registered as such, never normalised), `'Error inserting record into:'`, `'SQLCODE :'` and
 *       `'Delete failed with message:'`, each of which the baseline completed with the numeric SQLCODE
 *       and `SQLERRM` -- twice over in the `-532` arm at L1645-L1646, which is a baseline quirk rather
 *       than a contract. The cost accepted is that an operator no longer sees the code: PostgreSQL has
 *       no SQLCODE to show, a schema object name and a driver diagnostic in a browser are a disclosure
 *       with no parity value, and the correlation identifier the client already sends is what ties a
 *       report back to the server-side record. The four functions below therefore return the registered
 *       replacement and nothing else.
 */

/**
 * HTTP status the transport answers a bearer-carrying request whose session is no longer valid.
 *
 * Assumptions: the shared client ends the session on this status, so the route guard replaces this
 * screen with the sign-on screen within the same paint. Nothing is painted on row 23 for it.
 */
const SESSION_REFUSED_STATUS = 401;

/**
 * HTTP status the transport answers a request whose token carries neither CardDemo group.
 *
 * Assumptions: `services/reference-service/src/main/resources/openapi/reference-api.yaml` declares
 * `x-required-authority: carddemo-admin` on this screen's writes and `carddemo-user` on its read, so a
 * refusal is an ordinary authority outcome and never a system fault.
 */
const AUTHORITY_REFUSED_STATUS = 403;

/**
 * Reports whether a failure is a REFUSAL the operator can act on rather than a fault.
 *
 * WHY : ⚠️ Purpose: this is the distinction the abend surface turns on. `app/cpy/CSMSG02Y.cpy` L21-L29
 *       declares the `ABEND-DATA` fields that surface paints, and the reference reaches it only from an
 *       abend routine -- a condition the program cannot continue from. An authority refusal is not
 *       that: the program never ran, the data is intact, and the operator has an action available. So a
 *       refusal is reported on row 23 and never escalated to the surface reserved for a fault.
 * @param {unknown} failure - Whatever a call rejected with.
 * @returns {boolean} Whether the failure is a session or authority refusal.
 */
function isRefusal(failure: unknown): boolean {
  return (
    isApiRequestError(failure) &&
    (failure.status === SESSION_REFUSED_STATUS || failure.status === AUTHORITY_REFUSED_STATUS)
  );
}

/**
 * Reports the structured abend a failure should surface, or `null` when it must not surface one.
 *
 * WHY : ⚠️ Refactoring Rationale: the abend surface used to be painted for ANY read failure that
 *       carried abend data, and the reducer took that data straight off the problem document. Two
 *       conditions reached it that are not abends. An authority refusal is one -- see {@link isRefusal}
 *       -- and a transient failure is the other: a timeout or a gateway that gave up is a condition
 *       that may clear on its own, which is the opposite of the state that surface exists to report.
 *       The reference has no analogue of either, because a CICS task refused by RACF never entered the
 *       program and a task talking to Db2 does not time out and continue.
 *
 *       Assumptions: BOTH published predicates are consulted rather than only the first, because they
 *       differ on exactly the case that matters -- `ui/src/api/client.ts` records that a gateway
 *       failure on a write is transient but NOT repeatable. Withholding the fault surface for anything
 *       either predicate admits keeps the surface for the condition it names, and a failure that is
 *       transient but unrepeatable is still not a fault.
 * @param {unknown} failure - Whatever a call rejected with.
 * @returns {AbendDetail | null} The abend to surface, or `null` to leave the screen intact.
 */
function abendToSurface(failure: unknown): AbendDetail | null {
  if (!isApiRequestError(failure) || isRefusal(failure)) {
    return null;
  }

  /*
   * WHY : Assumptions: the narrowed failure is bound to a second name BEFORE the two predicates are
   *       consulted, and that is a type-system necessity rather than a preference. Both predicates are
   *       TYPE PREDICATES narrowing to the failure class, so testing them in a returning condition --
   *       whether inline or through an aliased boolean, which TypeScript also narrows -- leaves the
   *       parameter as `never` on the path that continues, and the document could not then be read.
   *       Binding first keeps the published classification in the condition where it reads correctly.
   */
  const raised = failure;

  if (isTransientFailure(failure) || isRepeatableFailure(failure)) {
    return null;
  }
  return raised.problem.abend;
}

/**
 * Transport status the shared client raises when no response arrived at all.
 *
 * Assumptions: `ui/src/api/client.ts` declares its own `NO_HTTP_STATUS = 0` and does not export it, so the
 * value is restated here rather than reached for. It is part of the PUBLISHED shape of `ApiRequestError` --
 * its `status` member documents `0` when nothing answered -- so restating it copies a documented contract
 * and not an implementation detail. The sibling list screen restates the same constant for the same reason.
 */
const NO_TRANSPORT_STATUS = 0;

/**
 * Chooses the authored sentence a failure with no baseline analogue is reported with.
 *
 * WHY : ⚠️ Purpose: three of this screen's failure paths ended in a sentence transcribed from a Db2
 *       arm -- the cursor's abend replacement, `'Update of record failed'`, `'Changes unsuccessful'` and
 *       `'Record delete failed'` -- and each of those states that the TABLE refused the work. A request
 *       that timed out, was throttled, or never reached a service did not get that far, so reporting one
 *       of those sends an operator, and anyone they call, to look for a database failure that never
 *       happened. These two sentences say what actually occurred, and they are catalogued in
 *       `ui/src/messages/messages.ts` rather than authored here.
 * WHY : ⚠️ Assumptions: the classification is read through the client's OWN published predicate rather
 *       than by comparing statuses, because the client is where the classification is defined -- it lists
 *       408, 429, 502, 503 and 504 beside a timeout -- and a screen that re-derived it would drift from
 *       it silently.
 * WHY : ⚠️ Assumptions: `null` is returned when neither arm applies, and the CALLER then paints its own
 *       baseline sentence. That keeps every transcribed sentence reachable for the condition it was
 *       transcribed for: a plain 500 is excluded from the transient set on the stated ground that it is
 *       what a service answers for a defect it has already recorded, which is exactly the condition
 *       `COTRTUPC`'s Db2 arms compose their diagnostics for.
 * WHY : Assumptions: a failure that reached nothing and is NOT transient is the dropped connection alone
 *       -- the timed-out one is answered by the transient arm above it -- so it earns the persistent
 *       sentence rather than an invitation to try again immediately.
 * WHY : Trade-offs: no REPEAT control is offered on either arm, so `isRepeatableFailure` gates nothing
 *       here. This screen's own keys are the repeat mechanism: the operator presses ENTER, F5 or F4
 *       again, which is the only repeat the reference offers. A control that repeated the last call by
 *       itself would have to be gated on that predicate -- the client records that a gateway failure on
 *       a write is transient but NOT repeatable, because the write may have been applied -- and the
 *       reason none is added is that re-pressing a key the operator chose is the reference's own answer,
 *       not that the distinction is unimportant.
 * @param {unknown} failure - Whatever the call rejected with.
 * @returns {string | null} The authored sentence, or `null` when the caller's baseline sentence applies.
 */
function authoredFailureMessage(failure: unknown): string | null {
  if (!isApiRequestError(failure)) {
    return null;
  }
  /*
   * WHY : ⚠ Assumptions: a sentence the SERVICE supplied is preferred over each classification below and
   *       rendered exactly as received, and the preference is scoped to THESE TWO ARMS rather than
   *       applied to every failure. Within them it is the better sentence: both are classifications of
   *       a transport outcome and say nothing about what was asked, so a service that named the
   *       condition said more. Outside them the catalogued sentence is the better one, because every
   *       remaining arm on this screen is a NAMED condition transcribed from the program for exactly
   *       that condition -- the concurrency refusal, the lock refusal, the child-record instruction and
   *       the cursor's abend replacement -- and each says something a general sentence cannot.
   *       Alternatives Considered: preferring the member for every failure, which is what several
   *       sibling screens do. Rejected on measured evidence from this screen's own siblings: the list
   *       screen classifies a 400 filtered-empty refusal by the FIELD ENTRIES the document carries and
   *       not by its prose, and a control case there proves a 400 naming `cursor` must report the fault
   *       sentence. Preferring prose ahead of that classification made the control case report the
   *       refusal sentence for a genuine fault, so the scope is narrow by evidence rather than by
   *       preference.
   *       Assumptions: `ui/src/api/client.ts` mints its own document for the two failures that reached
   *       no service with `message: null`, so a non-empty member here is always a service's own words
   *       and never the transport's diagnostic. Trade-offs: this screen cannot verify the prose it
   *       renders; the redaction obligation sits with the service, and this screen's own duty -- never
   *       composing a sentence from a status, a SQL code or a response body -- is unaffected.
   */
  const message = failure.problem.message;
  const supplied = message !== null && message.trim() !== '' ? message : null;
  /*
   * WHY : ⚠ Assumptions: the status is read into a local BEFORE the classification, and the order is
   *       forced by the predicate's declared shape rather than chosen. `isTransientFailure` narrows to
   *       `ApiRequestError` -- the same type its argument already has here -- so TypeScript computes the
   *       false branch as `Exclude<ApiRequestError, ApiRequestError>`, which is `never`, and a member
   *       read after the branch fails to compile with `Property 'status' does not exist on type
   *       'never'`. Reading the number first keeps it out of the narrowing entirely.
   *       Alternatives Considered: re-testing `isApiRequestError` inside the second arm to widen the
   *       type back. Rejected because it asserts the same fact twice and reads as though the value might
   *       have changed between two statements, which it cannot.
   */
  const status = failure.status;
  if (isTransientFailure(failure)) {
    return supplied ?? TRANSIENT_FAILURE_TRY_AGAIN;
  }
  if (status === NO_TRANSPORT_STATUS) {
    return supplied ?? PERSISTENT_FAILURE_REPORT_IT;
  }
  return null;
}

/**
 * Chooses the row-23 sentence a rejected read is reported with.
 *
 * Assumptions: a 404 is not a failure and never reaches here -- `9100-GET-TRANSACTION-TYPE` treats
 * `SQLCODE +100` as its own outcome at L1489-L1494 with the sentence
 * `'No record found for this key in database'`, which the reducer paints when it moves to
 * `detailsNotFound`. Everything else is the L1495 arm, whose registered replacement is the generic
 * abend sentence.
 *
 * WHY : ⚠️ Refactoring Rationale: an authority refusal is now reported AS a refusal. It used to reach
 *       the L1495 arm and paint `'UNEXPECTED ABEND OCCURRED.'`, which told an operator the system had
 *       failed when in fact their token carried no CardDemo group -- so they could not act on the one
 *       thing they could have acted on, and a real authority problem was hidden behind a sentence that
 *       invites a support call. A 401 paints nothing, because the client ends the session on it and the
 *       guard swaps this screen for the sign-on screen in the same paint.
 *
 *       ⚠️ Refactoring Rationale: a failure that never reached the service no longer earns the
 *       registered abend replacement. This comment recorded that it had to, because the catalogue
 *       carried no authored sentence for a transient condition and this screen may not author one -- and
 *       it now carries two, so {@link authoredFailureMessage} answers those conditions and the abend
 *       sentence is left for the Db2 arm it was transcribed from. The other half of that shortfall was
 *       already closed by {@link abendToSurface}, which withholds the fault SURFACE for the same
 *       conditions; this closes the SENTENCE, and the two now agree.
 * @param {unknown} failure - Whatever the read rejected with.
 * @returns {string} The sentence to paint on row 23, empty when the outcome is reported elsewhere.
 */
export function readFailureMessage(failure: unknown): string {
  if (isApiRequestError(failure)) {
    if (failure.status === SESSION_REFUSED_STATUS) {
      return '';
    }
    if (failure.status === AUTHORITY_REFUSED_STATUS) {
      return ACCESS_DENIED_NOT_AUTHORIZED;
    }
  }

  /*
   * WHY : ⚠ Assumptions: a message the SERVICE supplied is preferred over anything chosen here, and it
   *       is rendered verbatim. It is the only sentence in this chain that can name the actual
   *       condition -- `ABEND-ROUTINE` at `COTRTUPC.cbl` L1677-L1682 fills the generic text only when
   *       the specific text is empty, so a filled message is the specific one and replacing it with a
   *       classification would discard the more informative of the two.
   */
  const problem = isApiRequestError(failure) ? failure.problem : null;
  if (problem !== null && problem.abend !== null && problem.abend.abendMsg.trim() !== '') {
    return problem.abend.abendMsg;
  }
  return authoredFailureMessage(failure) ?? SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED;
}

/**
 * Chooses the row-23 sentence a rejected write is reported with.
 *
 * ⚠ Refactoring Rationale: a 409 is reported with `'Record changed by some one else. Please review'`,
 * which is a branch the baseline DECLARES and never reaches. `88 DATA-WAS-CHANGED-BEFORE-UPDATE` is
 * declared at L183-L184 and tested at L1585, and no statement anywhere sets it -- because the CICS
 * read-for-update lock was never held across the operator's think-time, so the program had no way to
 * observe a concurrent change. The target does: the version the service issued travels with the
 * replace, and `OptimisticLockException` comes back as 409. Activating the branch is what makes the
 * declared sentence reachable, and `9600-WRITE-PROCESSING` L1585-L1586 already prescribes the response
 * -- return to `TTUP-SHOW-DETAILS` so the row can be re-read and reviewed.
 *
 * Assumptions: the lock refusal keeps its own sentence, reported on 503. `SQLCODE -911` is a lock
 * timeout at L1561-L1566 carrying `'Could not lock record for update'`, and
 * `services/reference-service/src/main/resources/openapi/reference-api.yaml` declares no 423 for this
 * operation -- its statuses are 400, 401, 403, 404, 405, 406, 409, 413, 415, 500 and 503. Of those, 503
 * is the only one meaning "the resource could not be obtained now, retry", so it carries the condition
 * the baseline named. Alternatives Considered: folding 503 into the generic failure. Rejected because
 * the condition still occurs in the target -- a contended row still times out -- and folding it in would
 * make a declared sentence unreachable for a reason that has nothing to do with the target's behaviour.
 *
 * Assumptions: which generic sentence a non-conflict failure earns depends on WHICH write failed, which
 * is why the operation is a parameter. `REDACTED_DIAGNOSTICS` registers the replacement per baseline
 * site: the `UPDATE` arm at L1569 shows `'Update of record failed'` and the `INSERT` arm at L1609 shows
 * `'Changes unsuccessful'`. Reporting one sentence for both would misreport one of the two.
 * @param {unknown} failure - Whatever the write rejected with.
 * @param {'create' | 'replace'} [operation] - Which write failed; defaults to the insert path, whose
 *   registered replacement is the generic failure sentence.
 * @returns {string} The sentence to paint on row 23.
 */
export function writeFailureMessage(failure: unknown, operation?: 'create' | 'replace'): string {
  if (isConflictFailure(failure)) {
    return EDIT_STATUS.DATA_WAS_CHANGED_BEFORE_UPDATE.text;
  }
  /*
   * WHY : ⚠ Assumptions: the lock refusal is tested BEFORE the classification, and the order is
   *       deliberate. 503 is a transient status by the client's own list, so an authored
   *       `'The service is not available at the moment.'` would capture it and the baseline's
   *       `'Could not lock record for update'` -- the sentence `SQLCODE -911` earns at L1561-L1566 --
   *       would become unreachable. A named condition beats a classification: both tell the operator to
   *       try again, and only one says what stopped them. Every OTHER transient status carries no such
   *       baseline sentence, which is what the arm below answers.
   */
  if (isApiRequestError(failure) && failure.status === SERVICE_UNAVAILABLE_STATUS) {
    return EDIT_STATUS.COULD_NOT_LOCK_REC_FOR_UPDATE.text;
  }
  /*
   * WHY : ⚠ Refactoring Rationale: a throttle, a gateway failure, a timeout or a dropped connection
   *       used to be reported with whichever Db2 arm's sentence matched the operation -- `'Update of
   *       record failed'` or `'Changes unsuccessful'` -- both of which state that the table refused the
   *       write. For a request that never arrived, that is a false report of a database failure. The two
   *       arms below stay exactly as they were for the condition they were transcribed for.
   */
  const authored = authoredFailureMessage(failure);
  if (authored !== null) {
    return authored;
  }
  return operation === 'replace'
    ? EDIT_STATUS.TABLE_UPDATE_FAILED.text
    : EDIT_STATUS.INFORM_FAILURE.text;
}

/**
 * Chooses the row-23 sentence a rejected delete is reported with.
 *
 * ⚠ Refactoring Rationale: a 409 here is the `ON DELETE RESTRICT` refusal, NOT a version conflict, and
 * the two 409s are told apart by which operation raised them rather than by a code in the problem
 * document. That is the baseline's own division: `9800-DELETE-PROCESSING` tests `SQLCODE -532` at L1638
 * -- the referential-constraint violation the foreign key at `ddl/TRNTYCAT.ddl` L6-L7 declares with
 * `ON DELETE RESTRICT` -- while the version conflict belongs to the update path. So this reports
 * `'Please delete associated child records first:'` and {@link writeFailureMessage} reports the
 * record-changed sentence, and neither can be reached by the other's failure.
 *
 * Assumptions: the trailing colon is kept. The baseline appended the SQLCODE after it at L1641-L1648
 * and the target appends nothing, but the colon is transcribed because the invariant is the source
 * bytes rather than the bytes the target happens to need -- the same rule
 * `ui/src/messages/messages.ts` applies to `COTRTLIC`'s trailing space.
 * @param {unknown} failure - Whatever the delete rejected with.
 * @returns {string} The sentence to paint on row 23.
 */
export function deleteFailureMessage(failure: unknown): string {
  if (isConflictFailure(failure)) {
    return SHARED_MESSAGES.PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST;
  }
  /*
   * WHY : ⚠ Assumptions: a delete that never reached the service is reported as such rather than as
   *       `'Record delete failed'`, which is the `9800-DELETE-PROCESSING` arm at L1650-L1657 and states
   *       that the DELETE was attempted and refused. The distinction matters more on this path than on
   *       any other on this screen: an operator told the delete failed will re-arm and press F4 again,
   *       and for a dropped connection the row's fate is genuinely unknown -- `'That request did not
   *       complete. Report it if it happens again.'` invites the re-read that answers it, where the
   *       baseline sentence invites a second attempt at destroying a row that may already be gone.
   *       Trade-offs: the honest fix for that ambiguity is an idempotency key on the operation, which
   *       lives in the service contract this change may not edit; it is reported to the dispatcher
   *       instead.
   */
  return authoredFailureMessage(failure) ?? EDIT_STATUS.RECORD_DELETE_FAILED.text;
}

/** Abend code the reference raises when its action `EVALUATE` reaches a mode it does not describe. */
export const UNEXPECTED_DATA_SCENARIO_ABEND_CODE = '0001';

/** Abend code `ABEND-ROUTINE` stamps on everything else, from `COTRTUPC.cbl` L1682. */
export const UNEXPECTED_ABEND_CODE = '9999';

/**
 * Builds the abend the reference raises from the `WHEN OTHER` arm of `2000-DECIDE-ACTION`.
 *
 * Assumptions: all four members of `ABEND-DATA` are filled, because L1074-L1078 fills three of them
 * explicitly -- culprit `LIT-THISPGM`, code `'0001'`, reason `SPACES`, message
 * `'UNEXPECTED DATA SCENARIO'` -- and `ABEND-ROUTINE` at L1677-L1682 supplies the generic message and
 * the `'9999'` code only when the message is empty. Reproducing the empty reason rather than inventing
 * prose for it keeps the surface naming the fields an operator would find at `app/cpy/CSMSG02Y.cpy`
 * L45-L53.
 * @returns {AbendDetail} The structured abend for an undescribed mode.
 */
export function unexpectedDataScenario(): AbendDetail {
  return {
    abendCode: UNEXPECTED_DATA_SCENARIO_ABEND_CODE,
    abendCulprit: REF_TYPE_EDIT_PROGRAM_NAME,
    abendReason: '',
    abendMsg: SHARED_MESSAGES.UNEXPECTED_DATA_SCENARIO,
  };
}

/**
 * Chooses the heading for the abend surface from the abend's own fields.
 *
 * Assumptions: the two sentences are selected by the abend CODE rather than used interchangeably, which
 * is the division `ABEND-ROUTINE` draws: `'0001'` is the undescribed-mode abend and every other code
 * arrives with `'UNEXPECTED ABEND OCCURRED.'` filled in at L1677-L1678. A service-supplied message is
 * preferred when it carries one, because it explains more than either constant can.
 * @param {AbendDetail} abend - The structured abend to render.
 * @returns {string} The heading to render on the abend result.
 */
export function abendHeading(abend: AbendDetail): string {
  if (abend.abendMsg.trim() !== '') {
    return abend.abendMsg;
  }
  return abend.abendCode.trim() === UNEXPECTED_DATA_SCENARIO_ABEND_CODE
    ? SHARED_MESSAGES.UNEXPECTED_DATA_SCENARIO
    : SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED;
}

/** The two values one turn carries, standing in for `TTUP-OLD-DETAILS` and `TTUP-NEW-DETAILS`. */
export interface RefTypeEditRecord {
  /** `TTUP-*-TTYP-TYPE PIC X(02)`. */
  readonly typeCode: string;
  /** `TTUP-*-TTYP-TYPE-DESC PIC X(50)`. */
  readonly description: string;
}

/** An empty pair, standing in for the `INITIALIZE` the reference performs at L1519. */
const EMPTY_RECORD: RefTypeEditRecord = { typeCode: '', description: '' };

/**
 * Cuts text down to what a declared fixed-width field can carry, in canonical form.
 *
 * Purpose: a `PIC X(n)` field is n BYTES on the record, and the browser's own guard counts neither
 * bytes nor characters -- `maxLength` counts UTF-16 units, so `'🎉'` spends two of them for one
 * character and `'é'` typed as a base letter plus a combining accent spends two for one as well. This
 * answers the question the control cannot: what of this value does the field actually hold.
 *
 * WHY : Assumptions: the value is normalised to NFC BEFORE it is measured, through
 *       {@link normaliseForWire}, because the two spellings of an accented letter differ in length and
 *       only one of them is what a fixed-width record should carry. Measuring first and normalising
 *       afterwards could cut a value that would have fitted once composed.
 *
 *       Assumptions: the width is checked with {@link fitsDeclaredWidth}, which requires the value to
 *       fit in code points AND in UTF-8 bytes. That helper records why both are needed; the short of it
 *       is that the byte measure is the one a fixed-width record enforces and the character measure is
 *       the one an operator sees.
 *
 *       Trade-offs: this screen's own alphanumeric edit -- `1210-EDIT-TTYPE` at
 *       `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L893 -- refuses anything but letters, digits and
 *       spaces, so for a value the operator TYPED the normalisation is identity and the cut is
 *       unreachable. It is applied anyway, because the values this screen holds do not all come from the
 *       keyboard: a response row is assigned straight into state, where no control guard of any kind
 *       runs.
 * @param {string} text - Text as it arrived, from an operator or from a response body.
 * @param {number} declaredWidth - The field's `PIC X(n)` width; a non-negative integer.
 * @returns {string} The normalised text, cut on a character boundary until it fits both measures.
 * @throws {RangeError} If `declaredWidth` is negative or not an integer, raised by
 *   {@link fitsDeclaredWidth} because the caller then did not pass a COBOL field width.
 */
export function holdInField(text: string, declaredWidth: number): string {
  let held = normaliseForWire(text);

  /*
   * WHY : Assumptions: the cut walks CODE POINTS from the end, using the array spread rather than
   *       `slice`, because `slice` indexes UTF-16 units and would be able to cut a surrogate pair in
   *       half -- producing a lone surrogate, which is not text at all and which `TextEncoder` answers
   *       with a replacement character. Walking code points can only ever remove whole characters.
   *
   *       Assumptions: it loops rather than computing a length, because the predicate is a BYTE budget
   *       as well as a character budget and one code point can cost between one and four bytes. There is
   *       no index arithmetic that answers "how many characters fit in n bytes" without measuring, so
   *       the measurement is the loop condition.
   *
   *       Trade-offs: the loop is bounded by the code-point count of the value, so its cost is linear in
   *       a field that is fifty characters wide. A binary search would be asymptotically better and is
   *       not worth the reader's time at this size.
   */
  while (held !== '' && !fitsDeclaredWidth(held, declaredWidth)) {
    const points = [...held];
    points.pop();
    held = points.join('');
  }
  return held;
}

/**
 * Adopts a row the service answered with as this screen's record pair.
 *
 * ⚠ Trade-offs: each member is COERCED to a string rather than trusted, even though
 * {@link TransactionType} declares both as `string`. A response body is data crossing a boundary, and a
 * body that is merely SHAPED like a row -- `{}` is what `ui/src/test/apiHarness.ts` answers with by
 * default when a case queues no body -- would otherwise put `undefined` into a field this screen trims,
 * compares and renders, and the resulting `TypeError` takes the whole route down with it rather than
 * showing an empty field. Per design rule UI8 a view that renders `undefined` is already wrong; one that
 * crashes on it is worse. The cost is two type guards that a well-behaved service makes redundant, which
 * is the anti-corruption layer AAP section 0.4.3 puts at exactly this seam.
 *
 * WHY : ⚠ Refactoring Rationale: each member is now cut to its declared width through
 *       {@link holdInField}, and both were adopted whole. `maxLength` on a control governs TYPING and
 *       does nothing to a value assigned programmatically, so a row whose description exceeded the field
 *       arrived intact, was displayed in a field that cannot hold it, and was offered back on the next
 *       save to a `CHAR(50)` column -- `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L4, matching
 *       `TRAN-TYPE-DESC PIC X(50)` at `app/cpy/CVTRA03Y.cpy` L6. This is the seam to do it at, because it
 *       is the one place a response row becomes screen state.
 * @param {TransactionType} row - The row as the response carried it.
 * @returns {RefTypeEditRecord} The pair, each member normalised and cut to the width its field declares,
 *   with any absent member as the empty string.
 */
export function adoptRow(row: TransactionType): RefTypeEditRecord {
  return {
    typeCode: typeof row.typeCd === 'string' ? holdInField(row.typeCd, TYPE_CODE_WIDTH) : '',
    description:
      typeof row.description === 'string' ? holdInField(row.description, DESCRIPTION_WIDTH) : '',
  };
}

/**
 * Reports whether the glass differs from the before-image, transcribing `1205-COMPARE-OLD-NEW`.
 *
 * ⚠ Assumptions: THREE comparisons, not one. L786-L797 requires the upper-cased type codes to match,
 * the upper-cased trimmed descriptions to match AND the trimmed description LENGTHS to match. The third
 * looks redundant beside the second and is not: `FUNCTION TRIM` in COBOL removes leading and trailing
 * spaces, so two descriptions differing only in interior spacing compare equal on the second test and
 * differ in length on the third. Dropping it would silently accept `'PAYMENT  REVERSAL'` as unchanged
 * from `'PAYMENT REVERSAL'` and refuse to save a real edit.
 *
 * Assumptions: the comparison is case-INSENSITIVE because both sides are wrapped in
 * `FUNCTION UPPER-CASE`, so re-casing a description alone is not a change the reference will save.
 * @param {RefTypeEditRecord} pending - What is on the glass now.
 * @param {RefTypeEditRecord} original - The before-image last read from the service.
 * @returns {boolean} `true` when all three comparisons match, which is `NO-CHANGES-FOUND`.
 */
export function hasNoChanges(pending: RefTypeEditRecord, original: RefTypeEditRecord): boolean {
  const pendingDescription = pending.description.trim();
  const originalDescription = original.description.trim();
  return (
    pending.typeCode.toUpperCase() === original.typeCode.toUpperCase() &&
    pendingDescription.toUpperCase() === originalDescription.toUpperCase() &&
    pendingDescription.length === originalDescription.length
  );
}

/**
 * Reports which single control one mode leaves editable, transcribing `3300-SETUP-SCREEN-ATTRS`.
 *
 * ⚠ Refactoring Rationale: the key becomes READ-ONLY once a row has been fetched, which is a rule about
 * the mode and not about whether a row is loaded. L1272-L1273 protects every field first via
 * `3310-PROTECT-ALL-ATTRS`, and the `EVALUATE` at L1276-L1298 then unprotects exactly one: the key for
 * the four key-entry modes, the description alone for the four review modes via
 * `3320-UNPROTECT-FEW-ATTRS`, and neither for the three modes where a write or a delete is in flight.
 * Deriving it from `stored !== null` instead -- the obvious shortcut -- gets `createNewRecord` wrong,
 * where no row is stored and yet only the description may be typed.
 *
 * Assumptions: the `WHEN OTHER` arm at L1296-L1297 unprotects the key, which is what the three modes no
 * arm names -- `reviewNewRecord`, `changesOkayedLockError` and `changesOkayedButFailed` -- resolve
 * through.
 * @param {RefTypeEditMode} mode - The mode the screen is in.
 * @param {boolean} hasOldType - Whether the before-image carries a type code.
 * @returns {RefTypeEditField | null} The one editable control, or `null` when both are protected.
 */
export function editableField(mode: RefTypeEditMode, hasOldType: boolean): RefTypeEditField | null {
  switch (mode) {
    case 'notFetched':
    case 'invalidSearchKeys':
    case 'detailsNotFound':
      return 'typeCode';
    case 'changesBackedOut':
      // Assumptions: L1280-L1282 sends a backed-out change with a BLANK before-image to the key-entry
      //   arm and L1288 sends one with a before-image to the description arm, so this mode appears in
      //   both and the before-image decides.
      return hasOldType ? 'description' : 'typeCode';
    case 'showDetails':
    case 'changesNotOk':
    case 'createNewRecord':
      return 'description';
    case 'changesOkNotConfirmed':
    case 'changesOkayedAndDone':
    case 'confirmDelete':
    case 'startDelete':
    case 'deleteDone':
    case 'deleteFailed':
      // Assumptions: L1291-L1295 -- these plus `TTUP-DELETE-IN-PROGRESS` keep every field protected.
      return null;
    case 'reviewNewRecord':
    case 'changesOkayedLockError':
    case 'changesOkayedButFailed':
      return 'typeCode';
    default:
      return 'typeCode';
  }
}

/**
 * Reports which control the cursor lands on, transcribing the `EVALUATE` at `COTRTUPC.cbl` L1303-L1325.
 *
 * ⚠ Refactoring Rationale: focus is MODE-DEPENDENT, so the mapset's single `IC` attribute on `TRTYPCD`
 * at `COTRTUP.bms` L84 is only the initial-entry default rather than the whole rule. The program moves
 * the cursor itself with `MOVE -1 TO ...L` on every send: to the key for the key-entry modes and after a
 * committed change, and to the DESCRIPTION for a new record, an unchanged comparison, a refused
 * description and any mode in `TTUP-CHANGES-MADE`. Fixing the focus on the key permanently -- which
 * reading the mapset alone would suggest -- would put the cursor on a protected control in seven of the
 * sixteen modes. The caller applies this answer imperatively on every turn rather than through React's
 * `autoFocus` prop, which fires only on the initial mount; see the effect that consumes it.
 *
 * Assumptions: a refusal outranks the mode, because L1307-L1308 and L1317-L1318 test the validation
 * flags inside the same `EVALUATE` and the key arms precede the description arms. So a refused key
 * pulls the cursor even in a mode whose editable control is the description.
 *
 * Assumptions: `WHEN NO-CHANGES-DETECTED` at L1316 is a test on the MESSAGE, not on a separate flag --
 * the condition name is an `88`-level value of `WS-RETURN-MSG` declared at L179-L180 -- so comparing the
 * current return message against that catalogued constant is the transcription of it rather than an
 * approximation.
 * @param {RefTypeEditMode} mode - The mode the screen is in.
 * @param {readonly RefTypeEditRefusal[]} refusals - Refusals in force for this render.
 * @param {boolean} hasOldType - Whether the before-image carries a type code.
 * @param {string | null} returnMessage - The row-23 sentence in force, or `null` when there is none.
 * @returns {RefTypeEditField} The control that receives the cursor.
 */
export function focusedField(
  mode: RefTypeEditMode,
  refusals: readonly RefTypeEditRefusal[],
  hasOldType: boolean,
  returnMessage: string | null,
): RefTypeEditField {
  if (hasRefusalOn(refusals, 'typeCode')) {
    return 'typeCode';
  }
  if (
    mode === 'notFetched' ||
    mode === 'detailsNotFound' ||
    mode === 'invalidSearchKeys' ||
    mode === 'changesOkayedAndDone' ||
    (mode === 'changesBackedOut' && !hasOldType)
  ) {
    return 'typeCode';
  }
  if (
    mode === 'createNewRecord' ||
    mode === 'changesBackedOut' ||
    mode === 'showDetails' ||
    hasChangesMade(mode) ||
    returnMessage === EDIT_STATUS.NO_CHANGES_DETECTED.text ||
    hasRefusalOn(refusals, 'description')
  ) {
    return 'description';
  }
  return 'typeCode';
}

/**
 * Applies the early exit at `3300-SETUP-SCREEN-ATTRS` L1342-L1350 to the refusals in force.
 *
 * Assumptions: the description's highlight block is SKIPPED entirely -- rather than merely not
 * triggered -- when the mode is `notFetched`, `detailsNotFound` or `invalidSearchKeys`, or when the key
 * itself is blank or malformed. The paragraph performs `GO TO 3300-SETUP-SCREEN-ATTRS-EXIT` before
 * reaching the `COPY CSSETATY` at L1358, so a description refusal delivered in the same turn as a key
 * refusal marks only the key. Without this, a problem document naming both fields would redden two
 * controls where the reference reddens one.
 * @param {RefTypeEditMode} mode - The mode the screen is in.
 * @param {readonly RefTypeEditRefusal[]} refusals - Every refusal the turn produced.
 * @returns {readonly RefTypeEditRefusal[]} The refusals the screen actually marks.
 */
export function markableRefusals(
  mode: RefTypeEditMode,
  refusals: readonly RefTypeEditRefusal[],
): readonly RefTypeEditRefusal[] {
  const keyRefused = hasRefusalOn(refusals, 'typeCode');
  const keyEntryMode =
    mode === 'notFetched' || mode === 'detailsNotFound' || mode === 'invalidSearchKeys';
  if (keyRefused || keyEntryMode) {
    return selectRefusals(refusals, 'typeCode', true);
  }
  return refusals;
}

/**
 * Reports whether the key control is reddened by a failed delete rather than by a refusal.
 *
 * Assumptions: L1331-L1334 moves `DFHRED` into the key's colour attribute when
 * `FLG-TRANFILTER-NOT-OK` OR `TTUP-DELETE-FAILED`, so a delete the table refused reddens the key even
 * though the key itself was accepted. It is a distinct condition from a refusal and produces no
 * marker, because L1336-L1340 gates the literal asterisk on the BLANK flag alone.
 * @param {RefTypeEditMode} mode - The mode the screen is in.
 * @returns {boolean} `true` when the mode alone reddens the key control.
 */
export function keyReddenedByMode(mode: RefTypeEditMode): boolean {
  return mode === 'deleteFailed';
}

/** One call the screen has decided to make, which the effect below performs. */
export type RefTypeEditRequest =
  /** `9100-GET-TRANSACTION-TYPE`, the keyed `SELECT` at `COTRTUPC.cbl` L1475-L1482. */
  | { readonly kind: 'read'; readonly typeCd: string }
  /** `9700-INSERT-RECORD`, the `INSERT` at L1597-L1602. */
  | { readonly kind: 'create'; readonly typeCd: string; readonly description: string }
  /** `9600-WRITE-PROCESSING`, the `UPDATE` at L1544-L1548. */
  | {
      readonly kind: 'replace';
      readonly typeCd: string;
      readonly description: string;
      readonly version: number;
    }
  /** `9800-DELETE-PROCESSING`, the `DELETE` at L1627-L1630. */
  | { readonly kind: 'delete'; readonly typeCd: string };

/**
 * Names the key whose turn is outstanding, from the call that turn decided on.
 *
 * ⚠️ Purpose: `ui/src/layout/PfKeyBar.tsx` paints the in-flight affordance on the entry declared `busy`,
 * and the primitive's contract is that the entry is the one whose OWN turn is waiting -- not every entry
 * on a screen that happens to have a call outstanding. This screen issues calls from three different
 * keys, so the outstanding one cannot be a constant and is derived from the request instead.
 *
 * ⚠️ Assumptions: a `read` is attributed to ENTER even though the route can start one without a press.
 * `'ENTER=Process'` at `app/app-transaction-type-db2/bms/COTRTUP.bms` L101-L105 is the legend for the
 * action that fetches a row, and a route arrival performs the same fetch -- `answerRouteArrival` reads the
 * key out of the address instead of out of the control. So the progress affordance appears on the control
 * that names the work in progress, whichever way the work was started.
 *
 * Assumptions: `create` and `replace` are both attributed to PF5, because `'F5=Save'` at L121-L125 is the
 * one legend covering both writes -- `2000-DECIDE-ACTION` at `COTRTUPC.cbl` routes a save to `UPDATE`
 * from `changesOkNotConfirmed` and to `INSERT` from the not-found stage, and the mapset paints no separate
 * insert legend.
 *
 * Alternatives Considered: recording the initiating identifier in the state as a separate member. Rejected
 * because it would be a second field that has to agree with `request` on every transition, and the two
 * could then disagree; the request already says which action is outstanding, so the attribution is derived
 * rather than stored.
 * @param {RefTypeEditRequest | null} request - The call in flight, or `null` when none is.
 * @returns {RefTypeEditKeyAid | null} The identifier whose turn is outstanding, or `null` when none is.
 */
export function outstandingKeyAid(request: RefTypeEditRequest | null): RefTypeEditKeyAid | null {
  if (request === null) {
    return null;
  }
  switch (request.kind) {
    case 'read':
      return 'ENTER';
    case 'create':
    case 'replace':
      return 'PFK05';
    case 'delete':
      return 'PFK04';
    default:
      return null;
  }
}

/**
 * Everything one turn of this screen carries, standing in for the reference's own communication area.
 *
 * Assumptions: `request` and `requestSeq` are a pair. The reducer decides WHETHER a call is needed
 * because that decision is part of the transition it transcribes, and the component performs it -- so
 * the reducer stays pure and testable while the decision stays in one place. The sequence number is
 * what makes the effect that performs it fire exactly once per decision: `ui/src/main.tsx` L61-L75
 * mounts the tree inside `StrictMode`, which deliberately invokes every effect twice in development, and
 * a second invocation of a `create` would post the row twice.
 */
export interface RefTypeEditState {
  /** `TTUP-CHANGE-ACTION`, the one field the reference keeps its screen state in. */
  readonly mode: RefTypeEditMode;
  /** `TTUP-OLD-DETAILS`, the before-image the change comparison reads. */
  readonly oldRecord: RefTypeEditRecord;
  /** `TTUP-NEW-DETAILS`, what is on the glass now. */
  readonly newRecord: RefTypeEditRecord;
  /** The row and the version the service last issued, or `null` when none is held. */
  readonly stored: TransactionType | null;
  /** `WS-RETURN-MSG`, the row-23 sentence, or `null` when the line is clear. */
  readonly returnMessage: string | null;
  /** Refusals in force, at most one per control. */
  readonly refusals: readonly RefTypeEditRefusal[];
  /** The structured abend replacing the form, or `null` when none is in force. */
  readonly abend: AbendDetail | null;
  /** Whether a call is in flight, which no reference mode expresses and the browser needs. */
  readonly busy: boolean;
  /** The call the last transition decided on, or `null` when it decided on none. */
  readonly request: RefTypeEditRequest | null;
  /** Monotonic identifier of {@link RefTypeEditState.request}, so each is performed once. */
  readonly requestSeq: number;
}

/** Everything that can move this screen from one turn to the next. */
export type RefTypeEditAction =
  /** The route carried a key, so the row is read without a press. */
  | { readonly type: 'routeKeyReceived'; readonly typeCd: string }
  /** The route carried the add sentinel, so this turn is the screen's first turn. */
  | { readonly type: 'addRouteEntered' }
  /** The route carried something the key field cannot hold, so it is refused rather than read. */
  | {
      readonly type: 'routeKeyRefused';
      readonly entry: string;
      readonly refusal: RefTypeEditRefusal;
    }
  /** The operator typed into one of the two controls. */
  | { readonly type: 'fieldChanged'; readonly field: RefTypeEditField; readonly value: string }
  /** One of the six keys this screen honours was pressed. */
  | { readonly type: 'keyPressed'; readonly aid: RefTypeEditKeyAid }
  /** A key arrived that this mode does not accept, or that the mapset paints no legend for. */
  | { readonly type: 'keyRejected'; readonly aid: CicsAid }
  /** The read found the row. */
  | { readonly type: 'readSucceeded'; readonly row: TransactionType }
  /** The read found no row, which is `SQLCODE +100`. */
  | { readonly type: 'readMissed' }
  /** The read failed for any other reason. */
  | { readonly type: 'readFailed'; readonly failure: unknown }
  /** The create or replace committed. */
  | { readonly type: 'writeSucceeded'; readonly row: TransactionType }
  /** The create or replace was refused. */
  | { readonly type: 'writeFailed'; readonly failure: unknown }
  /** The delete committed. */
  | { readonly type: 'deleteSucceeded' }
  /** The delete was refused. */
  | { readonly type: 'deleteFailed'; readonly failure: unknown };

/**
 * The turn this screen begins on, standing in for the reference's first-entry path.
 *
 * Assumptions: the initial mode is `notFetched` and the row-23 line is CLEAR, because `0000-MAIN` sets
 * `WS-RETURN-MSG-OFF` at L362 before deciding anything -- the row-22 prompt is derived from the mode by
 * {@link modeInfoMessage} rather than stored, so no initial message is held here at all.
 */
export const REF_TYPE_EDIT_INITIAL_STATE: RefTypeEditState = {
  mode: 'notFetched',
  oldRecord: EMPTY_RECORD,
  newRecord: EMPTY_RECORD,
  stored: null,
  returnMessage: null,
  refusals: [],
  abend: null,
  busy: false,
  request: null,
  requestSeq: 0,
};

/**
 * Attaches a decided call to a turn, advancing the sequence so the effect performs it once.
 * @param {RefTypeEditState} state - The turn the call was decided in.
 * @param {RefTypeEditRequest} request - The call to perform.
 * @returns {RefTypeEditState} The turn carrying the call, marked busy.
 */
function withRequest(state: RefTypeEditState, request: RefTypeEditRequest): RefTypeEditState {
  return { ...state, request, requestSeq: state.requestSeq + 1, busy: true };
}

/**
 * Clears the decided call and the busy flag once a result has arrived.
 * @param {RefTypeEditState} state - The turn a result arrived in.
 * @returns {RefTypeEditState} The turn with no call outstanding.
 */
function settled(state: RefTypeEditState): RefTypeEditState {
  return { ...state, request: null, busy: false };
}

/**
 * Applies the re-entry reset at `COTRTUPC.cbl` L405-L419 before a press is decided.
 *
 * ⚠ Assumptions: the reference performs this BEFORE its action `EVALUATE`, and it is a real behaviour
 * rather than housekeeping: after a committed change, a failed change, a completed delete or a failed
 * delete, and on PF12 out of the three modes that arm names, it forces `CDEMO-PGM-ENTER` and
 * `TTUP-DETAILS-NOT-FETCHED`. So the turn AFTER an outcome starts from key entry with the key editable
 * again, which is why an operator can type a second code without pressing anything else first. Omitting
 * it would leave a committed change sitting in a mode whose controls are all protected.
 *
 * Assumptions: the outcome arms do not test the key at all -- they reset on ANY press, including Enter
 * -- while the PF12 arm at L406-L409 resets only for `showDetails`, `createNewRecord` and
 * `detailsNotFound`. The two other PF12 destinations, backing out a change and cancelling a delete, are
 * decided later by `2000-DECIDE-ACTION` and must not be reset here.
 * @param {RefTypeEditState} state - The turn as the press arrived.
 * @param {RefTypeEditKeyAid} aid - The key pressed.
 * @returns {RefTypeEditState} The turn the press is decided from.
 */
export function applyReentryReset(
  state: RefTypeEditState,
  aid: RefTypeEditKeyAid,
): RefTypeEditState {
  const outcomeReached =
    state.mode === 'changesOkayedAndDone' ||
    haveChangesFailed(state.mode) ||
    state.mode === 'deleteDone' ||
    state.mode === 'deleteFailed' ||
    (state.mode === 'changesBackedOut' && state.oldRecord.typeCode.trim() === '');
  const cancelledOut =
    aid === 'PFK12' &&
    (state.mode === 'showDetails' ||
      state.mode === 'createNewRecord' ||
      state.mode === 'detailsNotFound');

  if (!outcomeReached && !cancelledOut) {
    return state;
  }

  return {
    ...state,
    mode: 'notFetched',
    oldRecord: EMPTY_RECORD,
    newRecord: EMPTY_RECORD,
    stored: null,
    refusals: [],
    // ⚠ Assumptions: the row-23 line IS cleared, and following the reset through is what shows why.
    //   Setting `CDEMO-PGM-ENTER` and `TTUP-DETAILS-NOT-FETCHED` together makes the very next arm of the
    //   action `EVALUATE` match -- `WHEN CDEMO-PGM-ENTER AND TTUP-DETAILS-NOT-FETCHED` at L469-L470 --
    //   which runs `INITIALIZE WS-THIS-PROGCOMMAREA WS-MISC-STORAGE` at L471-L473 and returns without
    //   reaching `2000-DECIDE-ACTION` at all. `WS-RETURN-MSG` is declared at L167 INSIDE
    //   `01 WS-MISC-STORAGE`, which spans L35 to L199, so that `INITIALIZE` blanks it. The turn after an
    //   outcome is therefore a clean prompt, not an outcome sentence with a cleared mode beneath it.
    returnMessage: null,
    abend: null,
  };
}

/**
 * Reads one control's value the way `1150-STORE-MAP-IN-NEW` reads it.
 *
 * ⚠ Assumptions: a lone asterisk means BLANK, not an asterisk. L667-L673 and L678-L684 both move
 * `LOW-VALUES` into the field when the received value is `'*'` or spaces -- because the templated
 * highlight at `app/cpy/CSSETATY.cpy` L23-L25 WRITES an asterisk into a blank field, the terminal sends
 * it back on the next turn, and the program must not then treat its own marker as an entry. Omitting
 * this would make a second Enter on a blank key report that the key is non-numeric rather than missing.
 * @param {string} value - The value as the control holds it.
 * @returns {string} The trimmed value, or the empty string when it is blank or the blank marker.
 */
export function readControl(value: string): string {
  const trimmed = value.trim();
  return trimmed === FIELD_ERROR_TOKENS.blankMarker ? '' : trimmed;
}

/**
 * Runs `1000-PROCESS-INPUTS` -- store the glass, edit it, and settle the mode -- for an Enter press.
 *
 * Assumptions: the paragraph order is the reference's and the order matters. `1150-STORE-MAP-IN-NEW`
 * keeps the previous values rather than restoring them when the key is unchanged in
 * `TTUP-DETAILS-NOT-FOUND` and the press is not PF5 (L654-L658), which is what lets a second Enter on
 * the same key re-read rather than re-validate. `1200-EDIT-MAP-INPUTS` then skips the KEY edit entirely
 * for `createNewRecord` and `changesOkNotConfirmed` (L712-L714), because in those two modes the key is
 * already settled and is not on an editable control.
 *
 * ⚠ Assumptions: a malformed key ends in `notFetched`, NOT in `invalidSearchKeys`. L728-L731 performs
 * `SET TTUP-INVALID-SEARCH-KEYS TO TRUE` and then `SET TTUP-DETAILS-NOT-FETCHED TO TRUE` on the very
 * next line -- two `SET`s against the same one-byte field, so the second wins and the `'K'` state is
 * shadowed the instant it is set. It is transcribed as written rather than as apparently intended,
 * which is why {@link RefTypeEditMode} carries `invalidSearchKeys` and nothing here ever produces it.
 * @param {RefTypeEditState} state - The turn the press arrived in.
 * @returns {RefTypeEditState} The turn after the edits, before `2000-DECIDE-ACTION` runs.
 */
function processInputs(state: RefTypeEditState): RefTypeEditState {
  const glass = state.newRecord;
  const keyUnchanged =
    state.mode === 'detailsNotFound' && glass.typeCode === state.oldRecord.typeCode;

  if (keyUnchanged) {
    // Assumptions: L698-L710 -- the same key in the not-found mode is accepted without re-editing and
    //   the mode drops to key entry, so `2000-DECIDE-ACTION` re-reads it below.
    return { ...state, mode: 'notFetched', refusals: [] };
  }

  if (state.mode !== 'createNewRecord' && state.mode !== 'changesOkNotConfirmed') {
    const keyRefusal = refuseTypeCode(glass.typeCode);
    if (keyRefusal !== null) {
      // Assumptions: L720-L732 -- both the blank and the malformed arms end in key entry with the
      //   composed sentence in force. `'No input received'` at L722 is set only `IF WS-RETURN-MSG-OFF`,
      //   and the edit that produced the blank has already filled that field, so it never wins here.
      return {
        ...state,
        mode: 'notFetched',
        refusals: [keyRefusal],
        returnMessage: state.returnMessage ?? keyRefusal.message,
      };
    }
    const normalised = normaliseTypeCode(glass.typeCode);
    const settledKey: RefTypeEditState = {
      ...state,
      newRecord: { ...glass, typeCode: normalised },
      refusals: [],
    };
    if (state.mode === 'notFetched') {
      // Assumptions: L734-L736 -- a valid key in key-entry mode leaves the edits immediately, so the
      //   description is not validated against a row that has not been read yet.
      return settledKey;
    }
    return editDescription(settledKey);
  }

  return editDescription({ ...state, refusals: [] });
}

/**
 * Runs the change comparison and the description edit, which is the tail of `1200-EDIT-MAP-INPUTS`.
 *
 * Assumptions: the unchanged test comes BEFORE the description edit, at L743-L751, so an unchanged
 * description is reported as unchanged rather than validated -- and the three modes L746-L748 lists
 * short-circuit with the non-key flags cleared, which is why a re-pressed Enter after a committed change
 * reports nothing new.
 * @param {RefTypeEditState} state - The turn with its key already settled.
 * @returns {RefTypeEditState} The turn after the description edit.
 */
function editDescription(state: RefTypeEditState): RefTypeEditState {
  if (
    hasNoChanges(state.newRecord, state.oldRecord) ||
    state.mode === 'changesOkNotConfirmed' ||
    state.mode === 'changesOkayedAndDone'
  ) {
    return {
      ...state,
      refusals: [],
      returnMessage: state.returnMessage ?? EDIT_STATUS.NO_CHANGES_DETECTED.text,
    };
  }

  const refusal = refuseDescription(state.newRecord.description);
  if (refusal !== null) {
    // Assumptions: L753 sets `TTUP-CHANGES-NOT-OK` before the edit runs, so a refused description leaves
    //   the mode at "changes present, not acceptable" rather than at the mode the press arrived in.
    return {
      ...state,
      mode: 'changesNotOk',
      refusals: [refusal],
      returnMessage: state.returnMessage ?? refusal.message,
    };
  }

  // Assumptions: L772-L776 -- the green light for the save handshake is given only when no edit failed.
  return { ...state, mode: 'changesOkNotConfirmed', refusals: [] };
}

/**
 * Runs `2000-DECIDE-ACTION` (L978-L1081) over a turn whose edits have completed.
 *
 * ⚠ Assumptions: the `WHEN OTHER` arm ABENDS, at L1073-L1079, and it is reachable rather than defensive.
 * Two presses reach it. PF5 during a delete is accepted by `0001-CHECK-PFKEYS` (L588-L593 lists
 * `TTUP-DELETE-IN-PROGRESS`) and matched by no arm of either `EVALUATE`, and Enter after a delete the
 * table refused arrives in `startDelete`, which no arm names either -- because the `SQLCODE -532` arm at
 * L1638-L1649 sets the message and, unlike the arm below it at L1650-L1652, does NOT set
 * `TTUP-DELETE-FAILED`. So the mode stays `startDelete` and the next press abends. Both paths are
 * transcribed, which is what makes the abend surface a reproduction rather than an invention.
 * @param {RefTypeEditState} state - The turn after `1000-PROCESS-INPUTS`.
 * @param {RefTypeEditKeyAid} aid - The key that was pressed.
 * @returns {RefTypeEditState} The turn to render, carrying a decided call where one is needed.
 */
function decideAction(state: RefTypeEditState, aid: RefTypeEditKeyAid): RefTypeEditState {
  switch (state.mode) {
    case 'notFetched': {
      // Assumptions: L984-L1010 -- key entry falls through into the PF12 arm's body, whose valid-key
      //   branch clears the message and reads the row. An invalid key took the refusal path above and
      //   is reported without a call, which is the `ELSE` at L998-L1008 reaching its `WHEN OTHER`.
      if (state.refusals.length > 0) {
        return state;
      }
      return withRequest(
        { ...state, returnMessage: null },
        { kind: 'read', typeCd: state.newRecord.typeCode },
      );
    }
    case 'detailsNotFound':
      // Assumptions: L1053-L1055 -- PF5 on a key that named no row accepts the add.
      return aid === 'PFK05'
        ? { ...state, mode: 'createNewRecord', returnMessage: null, refusals: [] }
        : state;
    case 'showDetails':
      // Assumptions: L1023-L1030 -- a shown row with acceptable edits moves to the save handshake, and
      //   `processInputs` has already made that move when it applied. Reaching here means an edit was
      //   refused, nothing changed, or a key was rejected, all three of which the arm CONTINUEs on.
      return state;
    case 'changesBackedOut':
      // Assumptions: L1041-L1042 -- a backed-out change returns to the refused-changes mode so the
      //   description stays editable and the row-22 prompt asks for the change again.
      return { ...state, mode: 'changesNotOk' };
    case 'changesNotOk':
    case 'changesOkNotConfirmed':
    case 'invalidSearchKeys':
      // Assumptions: L1035-L1036, L1046-L1047 and L1060-L1061 all CONTINUE.
      return state;
    case 'changesOkayedAndDone':
      // Assumptions: L1065-L1066 -- a committed change is shown again. The selection fields the arm
      //   clears at L1067-L1072 belong to the shared communication area, which this screen does not
      //   carry, so there is nothing here to clear.
      return { ...state, mode: 'showDetails' };
    default:
      // ⚠ Assumptions: `confirmDelete` and `startDelete` reach HERE rather than having arms of their
      //   own, and that is the reference's behaviour rather than an omission in this transcription.
      //   L1016-L1017 names `TTUP-CONFIRM-DELETE` only in combination with PF12, which
      //   {@link decideCancel} answers before this function runs, so a PF5 arriving during either mode
      //   -- accepted by `0001-CHECK-PFKEYS` and revealed by nothing -- matches no arm and abends. See
      //   the note on this function for the two presses that get here.
      return { ...state, abend: unexpectedDataScenario() };
  }
}

/**
 * Decides a PF12 that the reset did not already answer, from the arm at `COTRTUPC.cbl` L999-L1008.
 *
 * Assumptions: this arm is reached WITHOUT the edits having run. L524-L530 performs
 * `2000-DECIDE-ACTION` directly and never `1000-PROCESS-INPUTS`, so the key-validity flag is still the
 * task's initial value and the `IF FLG-TRANFILTER-ISVALID` at L989 takes its `ELSE` -- which is the only
 * reason the two cancellation sentences below are reachable at all.
 *
 * Assumptions: the two cancels report DIFFERENT sentences and land in different modes -- a pending
 * delete is cancelled with `'Delete was cancelled'` back to key entry (L1000-L1002), and a validated
 * change with `'Update was cancelled'` into the backed-out mode (L1003-L1005), which keeps the
 * description editable so the operator can revise it. The third destination the arm names, cancelling
 * out of a shown row, is answered by {@link applyReentryReset} before this runs.
 * @param {RefTypeEditState} state - The turn the cancel arrived in.
 * @returns {RefTypeEditState} The turn after the cancel.
 */
function decideCancel(state: RefTypeEditState): RefTypeEditState {
  if (state.mode === 'confirmDelete') {
    return {
      ...state,
      mode: 'notFetched',
      refusals: [],
      returnMessage: EDIT_STATUS.WS_DELETE_WAS_CANCELLED.text,
    };
  }
  if (state.mode === 'changesOkNotConfirmed') {
    return {
      ...state,
      mode: 'changesBackedOut',
      refusals: [],
      // Assumptions: the glass is restored to the before-image, because the reference re-sends
      //   `TTUP-NEW-DETAILS` and a cancelled change must not leave the rejected value on an editable
      //   control -- the operator would otherwise re-submit what they just abandoned.
      newRecord: state.oldRecord,
      returnMessage: EDIT_STATUS.WS_UPDATE_WAS_CANCELLED.text,
    };
  }
  // Assumptions: the arm's own `WHEN OTHER` at L1006-L1007 returns to key entry.
  return { ...state, mode: 'notFetched', refusals: [] };
}

/**
 * Records a key this mode does not accept, from the `ELSE` of `0001-CHECK-PFKEYS` (L603-L608).
 *
 * ⚠ Assumptions: the sentence is `'Invalid key pressed'` -- nineteen characters, LOWER-case k -- and not
 * `'Invalid Key pressed. '`, which is twenty-one characters with a capital K and a trailing space. Both
 * are declared, at L193-L194 and L171-L172, and they are different strings the catalog keeps apart. Only
 * the first is live: the sole `SET` for the second is inside the commented-out block at L611-L616.
 * Merging them, or trimming the dead one and reusing it, would put a string on the glass that this
 * program never displays.
 *
 * Assumptions: the setter is GUARDED. L605-L607 sets it only `IF WS-RETURN-MSG-OFF`, so a validation
 * sentence already in force wins over the invalid-key report -- the operator is told what is wrong with
 * their entry rather than which key they should not have pressed.
 * @param {RefTypeEditState} state - The turn the press arrived in.
 * @returns {RefTypeEditState} The turn carrying the invalid-key sentence where none was pending.
 */
function rejectKey(state: RefTypeEditState): RefTypeEditState {
  if (state.returnMessage !== null && state.returnMessage.trim() !== '') {
    return state;
  }
  return { ...state, returnMessage: EDIT_STATUS.WS_INVALID_KEY_PRESSED.text };
}

/**
 * Decides one key press, in the order the reference decides it.
 *
 * Assumptions: the order below is `0000-MAIN`'s and each step is a separate construct in it --
 * `0001-CHECK-PFKEYS` at L400, the re-entry reset `EVALUATE` at L405-L419, then the action `EVALUATE` at
 * L423-L556 whose arms are tested top to bottom. Deciding the mode arms before the key arms, which
 * reads more naturally, would answer a PF12 during a delete confirmation with the confirmation itself
 * instead of with the cancellation the reference reports.
 * @param {RefTypeEditState} state - The turn the press arrived in.
 * @param {RefTypeEditKeyAid} aid - The key pressed.
 * @returns {RefTypeEditState} The turn to render, carrying a decided call where one is needed.
 */
function decideKeyPress(state: RefTypeEditState, aid: RefTypeEditKeyAid): RefTypeEditState {
  // WHY : ⚠️ Refactoring Rationale: the row-23 sentence is CLEARED here, at the top of the turn, and
  //       this one line is what makes the two message lines independent rather than one accumulating
  //       log. `WS-RETURN-MSG` is declared at L167 INSIDE `01 WS-MISC-STORAGE`, which is
  //       `WORKING-STORAGE` (L34) and NOT the COMMAREA -- `WS-THIS-PROGCOMMAREA` (L294) carries only
  //       the mode byte, `TTUP-OLD-DETAILS` and `TTUP-NEW-DETAILS`. CICS gives every pseudo-
  //       conversational task a fresh copy of `WORKING-STORAGE`, and `0000-MAIN` then blanks it twice
  //       over: `INITIALIZE ... WS-MISC-STORAGE` at L352-L354 and, under the reference's own comment
  //       "Ensure error message is cleared", `SET WS-RETURN-MSG-OFF TO TRUE` at L362. So the sentence
  //       cannot outlive the turn that set it.
  //       Assumptions: the `IF WS-RETURN-MSG-OFF` guards at L605, L721, L799, L805 and L863 are
  //       therefore WITHIN-turn first-wins guards -- they let a validation sentence beat the later
  //       invalid-key report in the SAME turn -- and not a carry-over across turns. Reading them as
  //       carry-over is what produced the observed defect: after a refused blank description, a
  //       following valid Enter left row 23 saying `'Transaction Desc must be supplied.'` while row 22
  //       simultaneously said `'Changes validated.Press F5 to save'`, so the screen contradicted
  //       itself. Clearing here restores the reference's behaviour and leaves every downstream
  //       `?? `-guarded setter untouched, because each now guards against a freshly blank value.
  const opening: RefTypeEditState =
    state.returnMessage === null ? state : { ...state, returnMessage: null };
  const accepted = refTypeEditKeyMatrix(opening.mode)[aid].accepted;
  const turn = accepted ? opening : rejectKey(opening);

  // Assumptions: L429-L460 is the FIRST arm, so the exit is answered before any reset or edit. The
  //   navigation itself belongs to the component; there is no state change to make for it.
  if (aid === 'PFK03') {
    return turn;
  }

  const reset = applyReentryReset(turn, aid);
  if (reset !== turn) {
    return reset;
  }

  // Assumptions: L539-L542 -- a rejected key re-paints the screen and decides nothing else, which is why
  //   the sentence set above is the whole outcome of the turn.
  if (!accepted) {
    return turn;
  }

  if (aid === 'PFK04') {
    if (turn.mode === 'confirmDelete') {
      // Assumptions: L482-L489 -- the second press issues the delete against the BEFORE-IMAGE key,
      //   which is what `9800-DELETE-PROCESSING` moves to the host variable at L1625. Using the glass
      //   value instead would delete whatever key was typed after the confirmation was asked for.
      return withRequest(
        { ...turn, mode: 'startDelete' },
        { kind: 'delete', typeCd: turn.oldRecord.typeCode },
      );
    }
    // Assumptions: L493-L498 -- the first press only asks.
    return { ...turn, mode: 'confirmDelete', refusals: [], returnMessage: null };
  }

  if (aid === 'PFK05') {
    if (turn.mode === 'detailsNotFound') {
      // Assumptions: L503-L508 -- this is the real add path, and it is PF5 rather than the PF6 the
      //   mapset advertises.
      return { ...turn, mode: 'createNewRecord', refusals: [], returnMessage: null };
    }
    if (turn.mode === 'changesOkNotConfirmed') {
      // Assumptions: L514-L520 and L1538-L1542 -- the description is TRIMMED before the write, and the
      //   key written is the normalised one held in `TTUP-NEW-TTYP-TYPE`. Which call is issued follows
      //   from whether a row was read, mirroring `9600-WRITE-PROCESSING` falling into
      //   `9700-INSERT-RECORD` on `SQLCODE +100` at L1558-L1560.
      /*
       * WHY : Assumptions: TRIM first and then hold to the declared width, in that order. The reference
       *       trims at L1538-L1542 and the field is `PIC X(50)`, so a value whose surplus is trailing
       *       blanks fits once trimmed -- holding first would cut characters the trim was about to make
       *       room for.
       *
       *       Trade-offs: this repeats a guarantee {@link adoptRow} already makes for a row that came
       *       from the service, and the repetition is deliberate: this is the last line before the value
       *       becomes a request body, and it is the only place that holds for a value however it reached
       *       state. For every reachable input today the call is identity, because the alphanumeric edit
       *       admits only ASCII and the control's `maxLength` stops the fifty-first keystroke.
       */
      const description = holdInField(turn.newRecord.description.trim(), DESCRIPTION_WIDTH);
      return withRequest(
        turn,
        turn.stored === null
          ? { kind: 'create', typeCd: turn.newRecord.typeCode, description }
          : {
              kind: 'replace',
              typeCd: turn.stored.typeCd,
              description,
              version: turn.stored.version,
            },
      );
    }
  }

  if (aid === 'PFK12') {
    return decideCancel(turn);
  }

  return decideAction(processInputs(turn), aid);
}

/**
 * Applies the outcome `EVALUATE` of `9600-WRITE-PROCESSING` (L1580-L1589) to a refused write.
 *
 * ⚠ Refactoring Rationale: the version-conflict arm returns the screen to `TTUP-SHOW-DETAILS`, exactly as
 * L1585-L1586 prescribes, so the row can be re-read and reviewed rather than re-saved over someone
 * else's change. That arm is DECLARED and unreachable in the baseline -- nothing sets
 * `DATA-WAS-CHANGED-BEFORE-UPDATE` -- because the CICS read-for-update lock was never held across the
 * operator's think-time, so the program could not observe a concurrent change. The target can, and
 * activating the arm is what makes the sentence the baseline declared reachable.
 *
 * Assumptions: the other two arms are kept apart. A lock refusal becomes
 * `TTUP-CHANGES-OKAYED-LOCK-ERROR` (L1581-L1582) and any other refusal
 * `TTUP-CHANGES-OKAYED-BUT-FAILED` (L1583-L1584); both share the row-22 `'Changes unsuccessful'` prompt
 * through {@link modeInfoMessage} while their row-23 sentences differ, which is the division the
 * reference draws between the two message lines.
 * @param {RefTypeEditState} state - The turn the refusal arrived in.
 * @param {unknown} failure - Whatever the write rejected with.
 * @returns {RefTypeEditState} The turn reporting the refusal.
 */
function applyWriteFailure(state: RefTypeEditState, failure: unknown): RefTypeEditState {
  const operation = state.stored === null ? 'create' : 'replace';
  const message = writeFailureMessage(failure, operation);

  if (isConflictFailure(failure)) {
    return { ...state, mode: 'showDetails', refusals: [], returnMessage: message };
  }
  if (isApiRequestError(failure) && failure.status === SERVICE_UNAVAILABLE_STATUS) {
    return { ...state, mode: 'changesOkayedLockError', refusals: [], returnMessage: message };
  }

  /*
   * Assumptions: a refusal naming fields is reported on the controls it names as well as on the message
   * line, and the service's field vocabulary is translated by {@link SERVICE_FIELD_CONTROLS}. Entries
   * naming something outside this form -- the `version` a conflict may carry -- resolve to no control and
   * are dropped, which keeps this function from deciding what the service is allowed to name.
   */
  const fieldErrors = isApiRequestError(failure) ? failure.problem.fieldErrors : [];
  const refusals = toRefusals(fieldErrors);
  return {
    ...state,
    mode: refusals.length > 0 ? 'changesNotOk' : 'changesOkayedButFailed',
    refusals,
    returnMessage: refusals[0]?.message ?? message,
  };
}

/**
 * Applies the outcome `EVALUATE` of `9800-DELETE-PROCESSING` (L1634-L1662) to a refused delete.
 *
 * ⚠ Assumptions: the referential-constraint arm leaves the mode at `startDelete`. L1638-L1649 sets
 * `RECORD-DELETE-FAILED` and composes the child-records sentence but -- unlike the arm below it at
 * L1650-L1652 -- does NOT set `TTUP-DELETE-FAILED`, so the screen stays in the issued-delete mode with
 * every control protected. That is what makes the next press reach the undescribed-mode abend, and it is
 * transcribed rather than corrected because correcting it would remove a path the baseline exhibits.
 * @param {RefTypeEditState} state - The turn the refusal arrived in.
 * @param {unknown} failure - Whatever the delete rejected with.
 * @returns {RefTypeEditState} The turn reporting the refusal.
 */
function applyDeleteFailure(state: RefTypeEditState, failure: unknown): RefTypeEditState {
  return {
    ...state,
    mode: isConflictFailure(failure) ? 'startDelete' : 'deleteFailed',
    refusals: [],
    returnMessage: deleteFailureMessage(failure),
  };
}

/**
 * Moves this screen from one turn to the next, transcribing `0000-MAIN` and the paragraphs it performs.
 *
 * Assumptions: this is the ONLY place the mode changes, which is what keeps the sixteen states and the
 * rules over them in one auditable place -- the reference keeps them in one field for the same reason.
 * It is pure: a decided call is recorded in {@link RefTypeEditState.request} and performed by the
 * component, so every transition can be exercised without a transport.
 * @param {RefTypeEditState} state - The turn the action arrived in.
 * @param {RefTypeEditAction} action - What happened.
 * @returns {RefTypeEditState} The next turn.
 */
export function refTypeEditReducer(
  state: RefTypeEditState,
  action: RefTypeEditAction,
): RefTypeEditState {
  switch (action.type) {
    case 'routeKeyReceived': {
      // Assumptions: a key on the route is treated as a key already typed and accepted, because the
      //   reference is transferred to with `CDEMO-*` selection context already set and reads it without
      //   a press. It is normalised on the way in for the reason recorded at
      //   {@link normaliseTypeCode}, and normalised ONCE into a local rather than at both sites, so the
      //   value shown on the glass and the value read can never diverge.
      const routeKey = normaliseTypeCode(action.typeCd);

      return withRequest(
        {
          ...state,
          newRecord: { ...state.newRecord, typeCode: routeKey },
          refusals: [],
          returnMessage: null,
        },
        { kind: 'read', typeCd: routeKey },
      );
    }
    case 'addRouteEntered':
      /*
       * WHY : ⚠️ Refactoring Rationale: arriving at the add sentinel used to leave the turn EXACTLY as it
       *       was, because the effect simply returned without dispatching. That is wrong whenever the
       *       screen is already showing a record: the address then says a new record is being added while
       *       the glass still carries the previous one, its before-image and its version -- so a save
       *       would have replaced a record the operator believed they were creating. The reference cannot
       *       reach that state at all, because a transfer with no key enters a fresh task whose
       *       `WORKING-STORAGE` is initialised (`0000-MAIN` at L362 clears the message line before
       *       deciding anything), so returning to the declared first turn IS the transcription.
       *
       *       Alternatives Considered: clearing only the two record members and leaving the mode. Rejected
       *       because the mode is what the prompt, the legend and the delete arm are all derived from, so
       *       a cleared record in `showDetails` would offer a delete for a record that is no longer named.
       */
      return REF_TYPE_EDIT_INITIAL_STATE;
    case 'routeKeyRefused':
      /*
       * WHY : Assumptions: the refusal is reported exactly as a TYPED key of the same shape is reported --
       *       key-entry mode, the sentence on row 23, the control marked -- which is `1210-EDIT-TTYPE`
       *       reached through `processInputs` at L720-L732. An address is one more way a key arrives, so
       *       an unusable one earns the answer the operator already recognises rather than a second
       *       vocabulary invented for the address bar.
       */
      return {
        ...REF_TYPE_EDIT_INITIAL_STATE,
        newRecord: { ...EMPTY_RECORD, typeCode: action.entry },
        refusals: [action.refusal],
        returnMessage: action.refusal.message,
      };
    case 'fieldChanged':
      // Assumptions: typing clears nothing but the refusal on the field being typed into, because the
      //   reference re-derives every flag from the received map on the next turn and the operator is
      //   mid-correction until they press a key.
      return {
        ...state,
        newRecord: { ...state.newRecord, [action.field]: action.value },
        refusals: selectRefusals(state.refusals, action.field, false),
      };
    case 'keyPressed':
      return decideKeyPress(state, action.aid);
    case 'keyRejected':
      // Assumptions: an identifier this mapset paints no legend for -- PF9, PA1, Clear -- takes the same
      //   `ELSE` as one this mode refuses, because `0001-CHECK-PFKEYS` tests for the keys it accepts
      //   rather than for the keys it knows.
      return rejectKey(state);
    case 'readSucceeded':
      // Assumptions: L1488 and L1517-L1526 -- a found row becomes the BEFORE-IMAGE as well as the glass,
      //   so the change comparison has something to compare against, and the version travels with it so
      //   a later replace can be refused.
      return settled({
        ...state,
        mode: 'showDetails',
        stored: action.row,
        oldRecord: adoptRow(action.row),
        newRecord: adoptRow(action.row),
        refusals: [],
        returnMessage: null,
        abend: null,
      });
    case 'readMissed':
      // Assumptions: L1489-L1494 -- `SQLCODE +100` is an outcome rather than a failure, and it reports
      //   `'No record found for this key in database'` while the row-22 prompt offers the add.
      return settled({
        ...state,
        mode: 'detailsNotFound',
        stored: null,
        oldRecord: { typeCode: state.newRecord.typeCode, description: '' },
        refusals: [],
        returnMessage: EDIT_STATUS.WS_RECORD_NOT_FOUND.text,
      });
    case 'readFailed':
      // Assumptions: L1495-L1508 -- anything else leaves the screen at key entry with the registered
      //   replacement for the withheld Db2 diagnostic, and surfaces the structured abend when the
      //   response carried one.
      return settled({
        ...state,
        mode: 'notFetched',
        stored: null,
        refusals: [],
        returnMessage: readFailureMessage(action.failure),
        abend: abendToSurface(action.failure),
      });
    case 'writeSucceeded':
      // Assumptions: L1556-L1557 and L1587-L1588 -- a committed write syncpoints and reports
      //   `'Changes committed to database'`, and the row it stored becomes the new before-image so the
      //   next comparison is against what is now in the table.
      return settled({
        ...state,
        mode: 'changesOkayedAndDone',
        stored: action.row,
        oldRecord: adoptRow(action.row),
        newRecord: adoptRow(action.row),
        refusals: [],
        returnMessage: null,
      });
    case 'writeFailed':
      return settled(applyWriteFailure(state, action.failure));
    case 'deleteSucceeded':
      // Assumptions: L1635-L1637 -- the delete syncpoints and the row-22 prompt reports it; the glass is
      //   emptied because the row it showed no longer exists.
      return settled({
        ...state,
        mode: 'deleteDone',
        stored: null,
        oldRecord: EMPTY_RECORD,
        newRecord: EMPTY_RECORD,
        refusals: [],
        returnMessage: null,
      });
    case 'deleteFailed':
      return settled(applyDeleteFailure(state, action.failure));
    default:
      return state;
  }
}

/**
 * Renders the transaction-type maintenance screen for one code.
 *
 * Assumptions: the ADMIN guard is not re-implemented here. `ui/src/router.tsx` gates every
 * administrative route on the signed `carddemo-admin` group claim, so a second check in this component
 * would be a second authority over one decision -- and the one that drifted would either lock out an
 * administrator or admit a user, with nothing failing to say which. The reference's own equivalent,
 * `SET CDEMO-USRTYP-ADMIN TO TRUE` at L448, is a value it writes into the shared area rather than a test
 * it performs.
 * @returns {ReactElement} The maintenance screen.
 */
export function RefTypeEditScreen(): ReactElement {
  const navigate = useNavigate();
  const location = useLocation();
  // WHY : Refactoring Rationale: `cssVar` rather than `token`, for the reason
  //       `ui/src/layout/PfKeyBar.tsx` records at its own call -- `token` holds the values the theme
  //       resolves to right now while `cssVar` holds `var(--...)` references, so a resolved value copied
  //       into an inline style stops tracking the variable and a later theme change reaches every
  //       component styled by class but not this one.
  const { cssVar } = theme.useToken();
  const paintedAt = useServerInstant();
  /*
   * WHY : Assumptions: the parameter is read under the name `cd`, which React Router resolves by NAME
   *       and not by position -- so this identifier and {@link REF_TYPE_EDIT_ROUTE}'s segment have to
   *       agree letter for letter or every arrival reads `undefined` and this screen prompts for a key
   *       the address already supplied, silently. It is renamed locally to `routeTypeCd` because the
   *       VALUE is a transaction-type code and the reducer names it that; the two-letter route label is
   *       the router's spelling of it, not the domain's.
   */
  const { cd: routeTypeCd } = useParams<{ cd: string }>();
  const [state, dispatch] = useReducer(refTypeEditReducer, REF_TYPE_EDIT_INITIAL_STATE);

  /*
   * WHY : Assumptions: the sequence number, not the request object, is what the effect below keys on, and
   *       a ref records the last one performed. `ui/src/main.tsx` L61-L75 mounts the tree inside
   *       `StrictMode`, which invokes every effect twice on purpose to surface missing cleanup -- and a
   *       second invocation of the create path would post the row twice. A ref survives that double
   *       invocation where a piece of state would not, because the reducer is not re-run between them.
   */
  const performedSeq = useRef(0);

  /**
   * The address segment the arrival effect has already answered, or `null` before the first arrival.
   *
   * WHY : ⚠️ Refactoring Rationale: a ref rather than a dependency comparison, and it exists because the
   *       screen now REPLACES its own address once a record is loaded. That replacement changes the route
   *       parameter, which re-runs the arrival effect -- and without a record of what has already been
   *       answered, the effect would issue a second read of the row just read, clearing the row-22 prompt
   *       and the row-23 sentence the first read produced. Recording the answered segment makes the
   *       effect answer each arrival once.
   *
   *       Assumptions: it also absorbs `StrictMode`'s deliberate double invocation, for the same reason
   *       {@link performedSeq} does -- `ui/src/main.tsx` mounts the tree inside it, and two dispatches of
   *       one arrival would advance the request sequence twice and read twice.
   */
  const answeredRouteKey = useRef<string | null>(null);

  /**
   * Handle on the transaction-type-code control, so the cursor can be placed on it per turn.
   *
   * Assumptions: a ref rather than a DOM query, because the control is antd's and its inner `<input>`
   * is an implementation detail this screen must not reach past its declared API to reach.
   */
  const typeCodeRef = useRef<InputRef>(null);

  /**
   * Handle on the description control, serving the same per-turn cursor placement.
   */
  const descriptionRef = useRef<InputRef>(null);

  const perform = useCallback(
    /**
     * Performs one decided call and dispatches its outcome.
     *
     * Assumptions: each promise is settled with two handlers rather than a `catch` after a `then`, so a
     * rejection raised INSIDE the success handler cannot be reported as a transport failure -- which
     * would paint a refusal sentence for a rendering fault.
     *
     * ⚠ WHY : Alternatives Considered: `isConfirmingAnswer` from `ui/src/api/client.ts`. Not
     *       applicable, and recorded so the absence does not read as an oversight: this screen's
     *       confirmation is a SECOND press of the same key rather than a character an operator types.
     *       `0001-CHECK-PFKEYS` at `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L588-L593 accepts
     *       PF4 in both the detail and the confirming mode and `2000-DECIDE-ACTION` arms on the first
     *       press and deletes on the second, and `COTRTUP.bms` declares no confirmation field for an
     *       answer to be collected into. There is no `Y`/`N` to classify.
     *
     * ⚠ WHY : Alternatives Considered: `requireConditionalOn`, which guards a precondition passed as
     *       its own argument. Not applicable here: the version travels as a MEMBER of the replace
     *       body, and `replaceTransactionType` in `ui/src/api/reference.ts` validates that body
     *       through `requireWithinPublishedWidths('TransactionTypeReplaceRequest', ...)`. Calling the
     *       precondition guard from this screen as well would put a second authority over one member's
     *       validity in the layer that does not own the contract, and the version this screen sends is
     *       never composed -- it is the one `state.stored` was delivered with, which is what makes the
     *       optimistic-concurrency refusal reachable at all.
     *
     * ⚠ WHY : Alternatives Considered: `withoutConcurrentDuplicate` around the deletion. Rejected as a
     *       duplicate guard -- `deleteTransactionType` already wraps itself in it at
     *       `ui/src/api/reference.ts`, keyed on the method and target, which is the layer that
     *       composes that target.
     *
     * ⚠ WHY : Alternatives Considered: retaining an outcome with `retainOutcome` for a later
     *       `claimRetainedOutcome` or `subscribeToRetainedOutcomes`. Nothing to retain: `withRequest`
     *       raises `busy` for the whole duration of a call and every key entry is built `disabled`
     *       while it is raised, PF3 included, so this screen cannot be left mid-write. A retained
     *       outcome exists for the screen that unmounted before its write settled, which this one
     *       cannot do.
     * @param {RefTypeEditRequest} request - The call the reducer decided on.
     * @returns {void} Completion is represented by the action this dispatches.
     */
    (request: RefTypeEditRequest): void => {
      if (request.kind === 'read') {
        getTransactionType(request.typeCd).then(
          /**
           * Publishes the row the read found.
           * @param {TransactionType} row - The row the service returned.
           * @returns {void} Completion is represented by the dispatched action.
           */
          (row) => {
            dispatch({ type: 'readSucceeded', row });
          },
          /**
           * Reports a missing row as its own outcome and anything else as a failure.
           * @param {unknown} failure - Whatever the read rejected with.
           * @returns {void} Completion is represented by the dispatched action.
           */
          (failure: unknown) => {
            const notFound = 404;
            if (isApiRequestError(failure) && failure.status === notFound) {
              dispatch({ type: 'readMissed' });
              return;
            }
            dispatch({ type: 'readFailed', failure });
          },
        );
        return;
      }

      if (request.kind === 'delete') {
        deleteTransactionType(request.typeCd).then(
          /**
           * Reports a committed delete.
           * @returns {void} Completion is represented by the dispatched action.
           */
          () => {
            dispatch({ type: 'deleteSucceeded' });
          },
          /**
           * Reports a refused delete, which includes the child-records refusal.
           * @param {unknown} failure - Whatever the delete rejected with.
           * @returns {void} Completion is represented by the dispatched action.
           */
          (failure: unknown) => {
            dispatch({ type: 'deleteFailed', failure });
          },
        );
        return;
      }

      const written =
        request.kind === 'create'
          ? createTransactionType({ typeCd: request.typeCd, description: request.description })
          : replaceTransactionType(request.typeCd, {
              description: request.description,
              version: request.version,
            });
      written.then(
        /**
         * Reports a committed write and adopts the row as stored.
         * @param {TransactionType} row - The row as stored after the write.
         * @returns {void} Completion is represented by the dispatched action.
         */
        (row) => {
          dispatch({ type: 'writeSucceeded', row });
        },
        /**
         * Reports a refused write, leaving the typed values on the glass to be reviewed.
         * @param {unknown} failure - Whatever the write rejected with.
         * @returns {void} Completion is represented by the dispatched action.
         */
        (failure: unknown) => {
          dispatch({ type: 'writeFailed', failure });
        },
      );
    },
    [dispatch],
  );

  useEffect(
    /**
     * Performs the call the last transition decided on, exactly once.
     * @returns {void} Completion is represented by the actions {@link perform} dispatches.
     */
    function performDecidedRequest(): void {
      if (state.request === null || state.requestSeq === performedSeq.current) {
        return;
      }
      performedSeq.current = state.requestSeq;
      perform(state.request);
    },
    [perform, performedSeq, state.request, state.requestSeq],
  );

  /**
   * The key of the record currently on the glass, or `null` when the glass holds none.
   *
   * Assumptions: the key comes from the STORED row rather than from the control, so the address can only
   * ever name a record the service resolved. The control holds whatever the operator is part-way through
   * typing, and an address minted from that would name a record that does not exist yet.
   */
  const storedKey = state.stored === null ? null : state.stored.typeCd;

  useEffect(
    /**
     * Answers the address: reads the row it names, prompts at the add sentinel, refuses anything else.
     *
     * Assumptions: the sentinel is recognised rather than read, so entering at the add sentinel issues no
     * request at all -- which is what the reference does when it is transferred to with no key: the edits
     * find nothing to fetch and the screen prompts for one. It now also RESETS the turn, for the reason
     * recorded on the `addRouteEntered` arm.
     *
     * WHY : ⚠️ Refactoring Rationale: the segment is VALIDATED before it is normalised, and it was not.
     *       `normaliseTypeCode` is `Number.parseInt` followed by `padStart`, so an address ending in a
     *       non-numeric segment produced the three characters `NaN` -- which the screen then displayed in
     *       a two-character field and sent to the service as a key. An operator following a stale or
     *       mistyped link was shown a key nobody had typed and a failure for a record nobody had asked
     *       for. The value the field can hold is refused the way a typed value of the same shape is
     *       refused, through the screen's own transcription of `1210-EDIT-TTYPE`.
     *
     *       Assumptions: the segment is first cut to the field's declared width, because that is the
     *       width the key HAS: `app/cpy/CVTRA03Y.cpy` L5 declares `TRAN-TYPE PIC X(02)` and
     *       `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L2 declares `CHAR(2)`, and the reference
     *       receives its key through a two-byte field that longer text cannot reach. So `'123'` is
     *       answered for the key it can carry rather than refused for a length the reference has no
     *       sentence for -- and the address is then replaced with the key actually read, so what the
     *       operator sees and what was fetched agree.
     *
     *       Alternatives Considered: gating on {@link isReferenceTypeCode} alone and refusing everything
     *       else. Rejected because it is stricter than the field: `'5'` is a key the screen accepts when
     *       typed, normalises to `'05'` and reads, so refusing it from an address would answer one
     *       arrival differently from the other for no reason the reference states. That predicate is
     *       instead used where the canonical form genuinely matters -- building the address below.
     * @returns {void} Completion is represented by the dispatched action.
     */
    function answerRouteArrival(): void {
      if (routeTypeCd === undefined || answeredRouteKey.current === routeTypeCd) {
        return;
      }
      answeredRouteKey.current = routeTypeCd;

      if (routeTypeCd === REF_TYPE_NEW_SENTINEL) {
        dispatch({ type: 'addRouteEntered' });
        return;
      }

      const carried = routeTypeCd.slice(0, TYPE_CODE_WIDTH);
      const refusal = refuseTypeCode(carried);

      if (refusal !== null) {
        dispatch({ type: 'routeKeyRefused', entry: carried, refusal });
        return;
      }
      dispatch({ type: 'routeKeyReceived', typeCd: carried });
    },
    [routeTypeCd],
  );

  useEffect(
    /**
     * Replaces the address with the canonical route of the record now on the glass.
     *
     * WHY : ⚠️ Refactoring Rationale: keying a code at the add sentinel loaded the record IN PLACE while
     *       the address stayed on `new`, so a fetched record was not addressable -- a reload lost it, a
     *       bookmark could not name it, and the browser's back control returned to an address that had
     *       never described what was on the screen. `ui/src/routes/navigation.ts` records this repair as
     *       two halves and owns the other one: the route exists and validates its key there, and this is
     *       the half that belongs to the screen.
     *
     *       Assumptions: the entry is REPLACED and not pushed. A record loading is not a place the
     *       operator navigated to, so pushing would make the exit key and the browser's back control
     *       disagree -- back would return to the same screen with a different address rather than to the
     *       caller, which is the behaviour `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L429-L443
     *       states for the exit.
     *
     *       Assumptions: the caller is carried across the replacement, because the exit destination is
     *       read from it -- dropping it on the replacement would strand an operator who arrived from the
     *       list screen at the administrative menu instead.
     *
     *       Trade-offs: a DELETED record leaves the address alone, because the delete clears the stored
     *       row rather than storing another one. Reloading that address then reports the record as not
     *       found, which is what it is; rewriting the address to the add sentinel on a delete is the
     *       alternative, and it would discard the address before the operator had read the sentence
     *       confirming what the address referred to.
     * @returns {void} Completion is the replaced history entry.
     */
    function useCanonicalAddress(): void {
      if (storedKey === null || !isReferenceTypeCode(storedKey)) {
        return;
      }
      const canonical = referenceTypeEditRoute(storedKey);

      if (location.pathname === canonical) {
        return;
      }
      answeredRouteKey.current = storedKey;
      navigateSafely(navigate, canonical, screenTransitionState(location.state), { replace: true });
    },
    [location.pathname, location.state, navigate, storedKey],
  );

  /*
   * WHY : Assumptions: the exit destination is the REFERRER when the transition named one, and the
   *       administrative menu otherwise, which is L429-L443 -- `CDEMO-FROM-TRANID` and
   *       `CDEMO-FROM-PROGRAM` are preferred and `LIT-ADMINTRANID`/`LIT-ADMINPGM` are the fallback.
   * WHY : ⚠️ Refactoring Rationale: this resolution now has two reachable arms, and until the sibling
   *       list screen was fixed it had one. The claim recorded here -- that the screen is entered from
   *       the administrative menu AND from the list screen, so a fixed destination would strand a
   *       list-screen operator -- described a flow the tree did not implement: administrative option 6
   *       hands over no origin because the menu IS the fallback, and the list screen's F2 arm handed
   *       over none either, so this expression could only ever resolve to the menu. That the reference
   *       has both callers is explicit in it: its own first-entry arms test
   *       `CDEMO-FROM-PROGRAM EQUAL LIT-ADMINPGM` at L466 and `EQUAL LIT-LISTTPGM` at L468, declared
   *       `'COADM01C'` at L209-L210 and `'COTRTLIC'` at L217-L218. The list screen's `openAddScreen`
   *       now hands over `REFERENCE_TYPE_LIST_ROUTE`, which is the state the sentence always
   *       described, and `ui/src/screens/refTypeEditScreen.test.tsx` pins both arms so neither can
   *       become unreachable again without a case failing.
   * WHY : Assumptions: the referrer is VALIDATED by `inApplicationRoute` rather than navigated to as
   *       supplied. Router state is writable through a hand-edited history entry, so an unchecked
   *       path-shaped value would either reach the not-found result or, protocol-relative, leave the
   *       application entirely on a key the operator believes returns them one screen.
   */
  const exitDestination =
    inApplicationRoute(screenTransitionState(location.state).from) ?? ADMIN_MENU_ROUTE;

  const matrix = refTypeEditKeyMatrix(state.mode);
  const refusals = markableRefusals(state.mode, state.refusals);
  const editable = editableField(state.mode, state.oldRecord.typeCode.trim() !== '');
  const focused = focusedField(
    state.mode,
    refusals,
    state.oldRecord.typeCode.trim() !== '',
    state.returnMessage,
  );
  const infoMessage = modeInfoMessage(state.mode, state.oldRecord.typeCode.trim() !== '');

  // WHY : ⚠️ Refactoring Rationale: the cursor is placed IMPERATIVELY, on every turn, because React's
  //       `autoFocus` prop applies only on the initial mount. `autoFocus` was bound to
  //       {@link focusedField} here first, and runtime inspection showed why that is not equivalent:
  //       the mount happens BEFORE the record arrives, so `document.activeElement` stayed `<body>` for
  //       every subsequent mode -- including the cancelled-delete turn where the key field becomes
  //       editable again and the reference explicitly puts the cursor in it. The reference re-places the
  //       cursor on EVERY turn: `3300-SETUP-SCREEN-ATTRS` executes `MOVE -1 TO <field>L` at L1303-L1325
  //       before each `SEND MAP`, so cursor position is per-turn output, not a one-off initial state.
  //       Assumptions: focus is attempted ONLY on the field this mode leaves editable. A 3270 cursor may
  //       rest on a protected field, whereas a disabled browser control cannot take focus at all, so the
  //       modes that protect both fields ({@link editableField} answering `'none'`) leave focus where it
  //       is rather than pretending to move it. That is the same class of documented divergence as gap
  //       G1: the reading order and the field the operator lands in are preserved, the character-cell
  //       cursor is not.
  useEffect(
    /**
     * Places the cursor on the control this mode leaves editable, once per turn.
     *
     * Assumptions: the guard is `focused !== editable` rather than a check on each mode, because
     * {@link focusedField} and {@link editableField} already answer independently and a disagreement
     * between them means the reference is pointing at a protected field -- which a browser cannot focus.
     * Nothing is done while a call is outstanding, since both controls are protected for its duration.
     * @returns {void} Nothing; the effect's whole result is the cursor position.
     */
    function placeCursorForTurn(): void {
      if (state.busy || focused !== editable) {
        return;
      }
      const target = focused === 'typeCode' ? typeCodeRef.current : descriptionRef.current;
      target?.focus();
    },
    [focused, editable, state.busy, state.mode],
  );

  /**
   * Reports the refusal in force on one control, or `undefined` when it has none.
   * @param {RefTypeEditField} field - The control being rendered.
   * @returns {RefTypeEditRefusal | undefined} The refusal to mark it with.
   */
  function refusalFor(field: RefTypeEditField): RefTypeEditRefusal | undefined {
    return findRefusalOn(refusals, field);
  }

  /**
   * Renders the pointer route to the delete: one dangerous control that raises PF4.
   *
   * ⚠ Refactoring Rationale: this renders the CONTROL alone. The confirmation moved to
   * {@link renderDeleteConfirmation}, which is mounted beside it rather than around it, because a `Modal`
   * is anchored to the viewport and needs no trigger to align against. Two earlier revisions are
   * superseded and recorded so neither is reintroduced. One wrapped the control in an overlay in BOTH
   * modes while the accept invoked PF4 exactly once, so accepting from `showDetails` merely ARMED the
   * delete and dismissed a dialogue that had just asked `'Delete this record ? Press F4 to confirm'` and
   * been told yes -- measured in a browser: the record survived and the operator had to press again
   * through a control that no longer looked armed. The other gave the click handler to the arming mode
   * only, because while the overlay was click-opened a handler on both paths would have committed the
   * delete with the very click meant to reveal the confirmation -- which left a danger-styled control
   * labelled `'F4=Delete'` inert.
   *
   * ⚠ Assumptions: the control means the SAME thing in both modes and the mode decides what PF4 does,
   * exactly as the reference does. `0001-CHECK-PFKEYS` accepts PF4 in `TTUP-SHOW-DETAILS` and in
   * `TTUP-CONFIRM-DELETE` (`app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L588-L593), and
   * `2000-DECIDE-ACTION` L479-L498 arms on the first press and deletes on the second. One control, one
   * legend, two presses.
   * @returns {ReactElement} The dangerous arming-and-confirming button.
   */
  function renderDeleteControl(): ReactElement {
    const unavailable = !matrix.PFK04.accepted || state.busy;
    /*
     * WHY : ⚠️ Assumptions: the legend is verbatim in BOTH modes and the click carries the press the
     *       legend promises in both. `'F4=Delete'` is what `COTRTUP.bms` L116-L120 paints, and rule T8 carries
     *       it character for character, so the control cannot be relabelled to say which press this is --
     *       which is exactly why the dialogue beside it exists to say so instead.
     *       Alternatives Considered: keeping the click inert in the confirming mode and requiring the
     *       dialogue's accept. Rejected because a danger-styled control that ignores a click is the
     *       defect an earlier revision introduced while arming, read the other way round.
     */
    const trigger = (
      <Button
        danger
        disabled={unavailable}
        onClick={
          /**
           * Raises PF4, which arms the delete on the first press and commits it on the second.
           * @returns {void} Completion is represented by the dispatched action.
           */
          (): void => {
            invokeAid('PFK04');
          }
        }
      >
        {REF_TYPE_EDIT_KEY_LABELS.PFK04}
      </Button>
    );

    /*
     * WHY : ⚠️ Refactoring Rationale: the nested provider carries `destructiveFocusTheme` and wraps the
     *       CONTROL rather than the screen, which is the usage that module prescribes. The global focus
     *       ring in `ui/src/theme/antdTheme.ts` is hue-neutral because the design system derives one
     *       outline for every button variant, so a destructive control asserted danger at rest and on
     *       hover and none of it on focus -- the weakest signal at the moment of commitment. That
     *       module records the measurement behind the replacement ring: 10.718:1 against the painted
     *       surface where the destructive hover is 7.748:1, so rest -> hover -> focus is strictly
     *       increasing.
     *       Assumptions: a nested provider MERGES per component name rather than replacing, verified
     *       there against the pinned package, so this control keeps every other decision the
     *       application theme makes and changes only its ring.
     */
    return <ConfigProvider theme={destructiveFocusTheme}>{trigger}</ConfigProvider>;
  }

  /**
   * Names the record the armed delete would destroy, in the mapset's own vocabulary.
   *
   * ⚠ Assumptions: the record is named with the mapset's OWN field labels and the BEFORE-IMAGE's values,
   * and nothing here is authored. `REF_TYPE_EDIT_FIELD_LABELS` transcribes
   * `app/app-transaction-type-db2/bms/COTRTUP.bms` L79-L83 and L90-L93, which is the vocabulary the two
   * protected fields behind the dialogue are already labelled with, so the operator checks the record
   * against the words they were reading. The before-image is the authority rather than the current
   * entries because a delete removes the STORED row: `2000-DECIDE-ACTION` at `COTRTUPC.cbl` L479-L498
   * arms on the fetched record, and an edited description in the control is not what would be destroyed.
   *
   * Assumptions: the key is rendered in the fixed-pitch face and the description is not, which is the
   * same distinction the two controls draw -- a two-character key is column data and a fifty-character
   * description is proportional text with nothing to align against.
   *
   * Assumptions: an empty list is returned outside the confirming mode rather than the dialogue being
   * rendered conditionally, because `open` already answers whether the surface exists and the design
   * system keeps a closed dialogue's content unmounted.
   *
   * ⚠ Refactoring Rationale: this is a FUNCTION rather than the `const` it was first written as, and the
   * difference is not stylistic. Every one of the thirteen delete cases across both suites failed with
   * `ReferenceError: Cannot access 'fixedPitchStyle' before initialization`, because a `const` in the
   * component body is evaluated where it is written and `fixedPitchStyle` is declared several hundred
   * lines further down, inside the same temporal dead zone. A function declaration is hoisted and its
   * body runs only when {@link renderDeleteConfirmation} calls it from the returned tree, by which point
   * every binding it reads exists. Alternatives Considered: moving the `const` below `fixedPitchStyle`.
   * Rejected because it would separate the record naming from the dialogue that presents it purely to
   * satisfy an initialisation order, and the next edit that moves either one would reintroduce the fault.
   * ⚠️ Assumptions: the return type EXCLUDES `undefined`, which the design system's own member permits.
   * `exactOptionalPropertyTypes` is on, so an optional prop and a prop that may be `undefined` are two
   * different types -- handing the permissive member straight to `items` fails to compile with
   * `Type 'undefined' is not assignable to type 'DescriptionsItemType[]'`. Both arms below return an
   * array, so excluding it states what this function already does.
   * @returns {NonNullable<DescriptionsProps['items']>} The two labelled rows, or an empty list when
   *   nothing is armed.
   */
  function deleteConfirmationItems(): NonNullable<DescriptionsProps['items']> {
    if (state.mode !== 'confirmDelete') {
      return [];
    }

    return [
      {
        key: 'typeCode',
        label: REF_TYPE_EDIT_FIELD_LABELS.typeCode,
        children: (
          <Typography.Text style={fixedPitchStyle}>{state.oldRecord.typeCode}</Typography.Text>
        ),
      },
      {
        key: 'description',
        label: REF_TYPE_EDIT_FIELD_LABELS.description,
        children: <Typography.Text>{state.oldRecord.description}</Typography.Text>,
      },
    ];
  }

  /**
   * Renders the destructive confirmation for an armed delete.
   *
   * ⚠ Refactoring Rationale: the primitive is a `Modal` where it was an anchored `Popconfirm`, and it is
   * mounted at SCREEN level where it wrapped the `'F4=Delete'` control. The primitive changed because a
   * `Popconfirm` cannot be a dialogue at this package version: its overlay is the tooltip primitive and
   * `ui/node_modules/@rc-component/tooltip/lib/Popup.js` writes `role: 'tooltip'` onto the surface with
   * no prop that overrides it, so the confirmation guarding a `DELETE` was announced as a tooltip,
   * carried no `aria-modal`, trapped no focus, and left Tab walking the page behind it. `Modal` renders
   * through the dialog primitive, which sets `role="dialog"`, `aria-modal="true"` and `aria-labelledby`
   * from the title at `ui/node_modules/@rc-component/dialog/lib/Dialog/Content/Panel.js` L113-L115,
   * locks focus inside itself while open, and restores focus on close.
   *
   * It moved out of the control because a modal is anchored to the VIEWPORT: it no longer needs a trigger
   * to align against, which also retires the whole `trigger={[]}` arrangement that existed to stop a
   * click on the `'F4=Delete'` button being read as a dismissal.
   *
   * ⚠ Assumptions: everything the previous revision earned is carried across, and each piece is listed so
   * a later edit cannot drop one silently. The surface is OPEN for as long as the delete is armed, so the
   * confirmation is on the glass on the KEYBOARD path too -- an F4 from the key bar used to arm the delete
   * and show only the row-22 prompt, leaving the operator one further F4 from a committed delete with
   * nothing focused. Its title is the same catalogued row-22 sentence the arming press put on the band, so
   * one question has one wording. It NAMES the record, because `'Delete this record ?'` identifies the row
   * only by what the glass happens to show. Initial focus is on the DECLINING choice. Nothing focuses
   * anything after the dialogue opens -- {@link editableField} answers `null` for `confirmDelete`, so the
   * per-turn cursor effect returns early and cannot pull focus off Cancel.
   *
   * ⚠ Assumptions: BOTH dismissal routes now arrive at ONE handler, and the `onOpenChange` arrangement the
   * previous revision needed is retired rather than lost. `Popconfirm` never called `onCancel` for
   * Escape -- the portal detects it (`@rc-component/portal/lib/useEscKeyDown.js` L25-L32) and the trigger
   * routes it through `onOpenChange` -- so the withdrawal had to hang off that callback, and `onCancel` had
   * to be omitted to stop the cancel BUTTON dispatching PF12 twice. `Modal` has no such split: its own
   * Escape handling and its cancel button both call `onCancel` exactly once
   * (`@rc-component/dialog/lib/Dialog/index.js` `onInternalClose`), so one callback covers both and cannot
   * double-dispatch. That matters here rather than being tidiness: `TTUP-DETAILS-NOT-FETCHED` is absent
   * from the PF12 list at `COTRTUPC.cbl` L594-L601, so a second PF12 would be refused as an invalid key and
   * `'Invalid key pressed'` would replace `'Delete was cancelled'` on row 23.
   *
   * Assumptions: PF12 is the transition dispatched for a withdrawal, because the reference already owns it
   * -- L594-L601 accepts PF12 while confirming and its cancel arm answers a pending delete with
   * `'Delete was cancelled'` back to key entry at L1000-L1002. PF4 is dispatched for an acceptance, which
   * is the reference's own SECOND press.
   *
   * ⚠ Trade-offs: the accept keeps the design system's stock `OK` rather than taking the confirming key's
   * legend. `COTRTUP.bms` declares no literal for a dialogue accept -- a 3270 has no dialogue -- so rule
   * T8 has no verbatim string to preserve here, and authoring one would put invented operator-visible text
   * beside a title that is transcribed. The key's own legend `'F4=Delete'` stays verbatim on the control
   * behind the dialogue, which is where the reference puts it.
   * @returns {ReactElement} The confirmation surface, closed unless a delete is armed.
   */
  function renderDeleteConfirmation(): ReactElement {
    return (
      <Modal
        cancelButtonProps={{ autoFocus: true, 'aria-describedby': DELETE_CONFIRMATION_RECORD_ID }}
        /*
         * WHY : ⚠️ Refactoring Rationale: a withdrawn confirmation is DESTROYED rather than kept hidden,
         *       so that the `autoFocus` on the line above keeps working past the first opening.
         *       `autoFocus` is applied when a control ENTERS the document, and the design system keeps a
         *       closed dialogue mounted at `display:none`; a re-opening therefore re-shows controls that
         *       never left, and nothing re-applies the attribute. Measured on the sibling authorization
         *       screen: focus on `Cancel` for the first opening, and on the dialogue's own container
         *       element for every later one -- which on a DELETE leaves the operator off the answer that
         *       walks away. A note further down this module previously rejected this flag on the grounds
         *       that the panel would VANISH rather than fade; that reasoning was wrong on the fact.
         *       `@rc-component/dialog/lib/Dialog/Content/index.js` passes it straight through as the
         *       motion's `removeOnLeave`, so the panel is removed when the leave animation COMPLETES and
         *       the design system's own withdrawal affordance is untouched.
         *       Trade-offs: one extra mount per confirmation. It does NOT replace the idle guard below --
         *       that guard exists because a closing panel holds a stale closure for the length of its
         *       animation, which is exactly the window this flag leaves in place.
         */
        destroyOnHidden
        /*
         * WHY : Assumptions: the accept carries `danger` on top of the default primary type rather than
         *       the legacy `okType="danger"` this surface used before. `convertLegacyProps` maps that
         *       operand to `danger` with the DEFAULT variant, which renders the destructive control as
         *       the quieter of the two buttons -- emphasis inverted against risk. The pair renders the
         *       solid dangerous variant instead.
         * WHY : Assumptions: `disabled` accompanies `loading` and is not redundant. The design system's
         *       `Button` returns early from its own click handler while loading, so the accept was
         *       already inert during the `DELETE`; `disabled` is what states that unavailability to
         *       assistive technology, which a spinner does not.
         */
        okButtonProps={{
          danger: true,
          'aria-describedby': DELETE_CONFIRMATION_RECORD_ID,
          disabled: state.busy,
          loading: state.busy,
        }}
        open={state.mode === 'confirmDelete'}
        title={EDIT_STATUS.PROMPT_DELETE_CONFIRM.text}
        /*
         * WHY : ⚠ Assumptions: BOTH handlers go through {@link whenIdle}, so neither is accepted while the
         *       `DELETE` this dialogue authorised is still outstanding. The accept is additionally inert
         *       through its `okButtonProps`, and the guard is what covers CANCEL -- which carries no
         *       loading state and would otherwise write `'Delete was cancelled'` over a delete in
         *       progress.
         */
        onCancel={whenIdle(
          /**
           * Withdraws the armed delete, from Escape and from the declining control alike.
           * @returns {void} Completion is represented by the dispatched action.
           */
          (): void => {
            invokeAid('PFK12');
          },
        )}
        onOk={whenIdle(
          /**
           * Dispatches the reference's confirming second press.
           * @returns {void} Completion is represented by the dispatched action.
           */
          (): void => {
            invokeAid('PFK04');
          },
        )}
        footer={
          /**
           * Composes the footer so only the accept carries the destructive focus ring.
           *
           * Assumptions: the two stock controls are placed by hand rather than restyled in place, because
           * the nested provider has to wrap ONE of them. Wrapping the whole dialogue would put the
           * error-ramp ring around the cancel control as well, and a focus ring is a risk signal -- cancel
           * is the one control on this surface that risks nothing.
           * Assumptions: the `controls` members are typed as element-returning functions rather than as
           * the design system's own `ComponentType` name, which is not imported here; importing a type
           * solely to mention it in a doc comment trades one lint failure for another, so the structural
           * form is written instead.
           * @param {ReactNode} _stockFooter - The stock pair, unused; both members are placed below.
           * @param {{ OkBtn: () => ReactElement, CancelBtn: () => ReactElement }} controls - The stock
           *   buttons, each of which reads this dialogue's own button props and handlers.
           * @returns {ReactElement} Cancel first, then the destructive accept.
           */
          (_stockFooter: ReactNode, controls): ReactElement => (
            <>
              {/*
               * WHY : Assumptions: cancel is rendered FIRST, which is both the stock order and the safe
               *       one -- the first control a keyboard reaches is the one that changes nothing.
               */}
              <controls.CancelBtn />
              {/*
               * WHY : Assumptions: the nested provider carries `destructiveFocusTheme` so the accept's
               *       focus ring sits in the error ramp. `ui/src/theme/antdTheme.ts` records the
               *       measurement: the destructive hover is 7.748:1 against the painted surface and this
               *       ring is 10.718:1, so rest -> hover -> focus is strictly increasing, where the
               *       global hue-neutral ring left the strongest signal missing at exactly the moment of
               *       commitment. A nested provider MERGES per component name, so nothing else about
               *       this control changes.
               */}
              <ConfigProvider theme={destructiveFocusTheme}>
                <controls.OkBtn />
              </ConfigProvider>
            </>
          )
        }
      >
        <Descriptions
          column={DELETE_CONFIRMATION_RECORD_COLUMNS}
          id={DELETE_CONFIRMATION_RECORD_ID}
          items={deleteConfirmationItems()}
          size="small"
        />
      </Modal>
    );
  }

  /**
   * The outstanding-call flag, in a form a stale closure can still read correctly.
   *
   * ⚠️ Purpose: {@link whenIdle} is attached to controls the design system may hold in a snapshot -- a
   * dialogue that has been told to close stays mounted for its leave animation and is not re-rendered
   * with its parent -- so a handler on that panel closes over the state as it was when the panel last
   * rendered. Reading the flag through a ref makes the guard answer with the CURRENT value, because the
   * closure captures the ref object rather than a boolean.
   *
   * Alternatives Considered: `destroyOnHidden` on the dialogue, so a closing panel is unmounted and its
   * controls cannot be reached at all. That flag IS now set, for an unrelated reason recorded at the
   * dialogue itself, and it does NOT make this ref redundant. The flag reaches the design system as the
   * leave motion's `removeOnLeave` -- see `@rc-component/dialog/lib/Dialog/Content/index.js` -- so the
   * panel is removed when the animation COMPLETES, which means the stale-closure window this ref covers
   * is exactly the window the flag leaves open. An earlier revision of this paragraph rejected the flag
   * on the belief that it would make the panel vanish instead of fade; that was wrong on the fact, and
   * it is corrected here rather than deleted so a reader who remembers the old claim can see it settled.
   */
  const busyRef = useRef(false);

  useEffect(
    /**
     * Keeps the outstanding-call flag in step with the turn that carries it.
     * @returns {void} Nothing; the ref is updated in place.
     */
    (): void => {
      busyRef.current = state.busy;
    },
    [state.busy],
  );

  /**
   * Wraps a handler so it runs only while no call is outstanding.
   *
   * ⚠️ Purpose: the confirmation dialogue's own controls are reached by pointer WITHOUT going through the
   * key hook, so the `disabled` declarations {@link keyEntry} makes cannot cover them. The reachable case
   * is CANCEL: it carries no loading state of its own, so it stayed clickable during the `DELETE` it had
   * just confirmed, and a withdrawal accepted mid-call would put `'Delete was cancelled'` on row 23 for a
   * row the service was in the middle of destroying. The accept is already inert -- its `okButtonProps`
   * carry `disabled` and `loading` -- and this guard covers both routes with one statement.
   *
   * Assumptions: a press arriving during a call is declined SILENTLY rather than reported. That is the
   * same treatment `usePfKeys` gives a `busy` entry and the same treatment the terminal gave a key pressed
   * while the keyboard was inhibited: a valid key pressed early is not an invalid key, and inventing
   * `'Invalid key pressed'` for it would misreport the reference.
   *
   * ⚠️ Assumptions: the flag is read from {@link busyRef} and NOT from `state.busy`, and the first form of
   * this guard read the state -- which did not work. Measured: with the delete held outstanding, a click
   * on the closing panel's cancel control still produced `'Invalid key pressed'` on row 23, and the
   * panel's own accept control still rendered enabled. The design system keeps a closing dialogue mounted
   * for its leave animation and does not re-render it with the parent, so every prop and every handler on
   * that panel is the SNAPSHOT taken while the delete had not yet been decided -- in which `state.busy`
   * was false. A ref survives that, because the closure captures the ref OBJECT and reads its current
   * value when the click arrives.
   *
   * Assumptions: the exclusion has to be written at all because the reference gets it for free -- a CICS
   * task holds the terminal until it returns, so no second attention identifier can arrive mid-transaction.
   * @param {() => void} turn - The handler to run when nothing is outstanding.
   * @returns {() => void} A handler that runs `turn` only while this screen is idle.
   */
  function whenIdle(turn: () => void): () => void {
    /**
     * Runs the wrapped turn unless a call is outstanding.
     * @returns {void} Nothing; the turn is skipped while a call is in flight.
     */
    return (): void => {
      if (busyRef.current) {
        return;
      }
      turn();
    };
  }

  /**
   * Dispatches one of the six keys this screen honours.
   *
   * Assumptions: PF3 is the one identifier that leaves, and it leaves for {@link exitDestination} --
   * the validated caller when the transition named one and the administrative menu when it did not.
   * Both arms are reachable: the list screen's add transfer names this screen's caller and
   * administrative option 6 names none.
   *
   * Assumptions: the reducer is dispatched BEFORE the transition on that arm, matching every other
   * key, so the mode the screen unmounts in is the mode the key produced. The reference does the same
   * -- its PF3 arm writes the navigation and session fields at `COTRTUPC.cbl` L445-L451 and only then
   * issues `EXEC CICS XCTL` at L457-L460 -- and a dispatch after the transition would run against an
   * unmounted reducer.
   * @param {CicsAid} aid - The identifier `usePfKeys` resolved, or the bar's button reported.
   * @returns {void} Completion is represented by the dispatched action, and by the transition on PF3.
   */
  function invokeAid(aid: CicsAid): void {
    if (!isRefTypeEditAid(aid)) {
      dispatch({ type: 'keyRejected', aid });
      return;
    }
    if (aid === 'PFK03') {
      dispatch({ type: 'keyPressed', aid });
      navigateSafely(navigate, exitDestination);
      return;
    }
    dispatch({ type: 'keyPressed', aid });
  }

  /**
   * Builds one entry of the key map, carrying the mapset's label, risk and this mode's availability.
   *
   * Assumptions: `disabled` follows ACCEPTANCE and not visibility, so PF5 during a delete stays live
   * with no legend, and a call in flight makes every key that is NOT the outstanding one unavailable --
   * which no reference mode expresses and a browser needs, because a pseudo-conversational task held the
   * terminal for the whole turn and a browser does not.
   *
   * WHY : ⚠ Refactoring Rationale: the outstanding key reports BUSY where a call in flight used to
   *       disable everything, and the two are not interchangeable. A disabled control is announced as
   *       unavailable and `ui/src/layout/usePfKeys.ts` reports a disabled press to `onInvalidKey` -- so
   *       pressing PF4 twice during its own `DELETE` was classified with the baseline's
   *       `'Invalid key pressed'` text for a key the reference accepts. `busy` says the true thing: the
   *       control stays present, enabled, focusable and named, the second press is declined SILENTLY,
   *       and the bar paints the in-flight affordance on the control the operator pressed. That is the
   *       3270 input-inhibit analogue -- a valid key pressed early.
   * WHY : ⚠ Assumptions: exactly ONE entry carries `busy`, chosen by {@link outstandingKeyAid} from the
   *       call in flight, and the other five stay `disabled` while it runs. Marking all six busy would
   *       claim each of them is waiting on an answer, when five of them are different actions this
   *       screen genuinely cannot perform mid-call -- it cannot read a second row, save, or transfer
   *       while a write is outstanding. `disabled` is the honest classification for those, and the
   *       progress affordance belongs on the key whose turn it is.
   * @param {RefTypeEditKeyAid} aid - The identifier to bind.
   * @returns {PfKeyHandlerEntry} The entry for that identifier.
   */
  function keyEntry(aid: RefTypeEditKeyAid): PfKeyHandlerEntry {
    const outstanding = state.busy && aid === outstandingKeyAid(state.request);

    return {
      /**
       * Dispatches this identifier.
       * @returns {void} Completion is represented by the dispatched action.
       */
      onInvoke: () => {
        invokeAid(aid);
      },
      label: REF_TYPE_EDIT_KEY_LABELS[aid],
      risk: REF_TYPE_EDIT_KEY_RISKS[aid],
      disabled: !matrix[aid].accepted || (state.busy && !outstanding),
      busy: outstanding,
    };
  }

  const handlers: PfKeyHandlerMap = {
    ENTER: keyEntry('ENTER'),
    PFK03: keyEntry('PFK03'),
    PFK04: keyEntry('PFK04'),
    PFK05: keyEntry('PFK05'),
    PFK06: keyEntry('PFK06'),
    PFK12: keyEntry('PFK12'),
  };

  const { bindings, invoke } = usePfKeys(handlers, {
    /**
     * Reports a key this mode refuses with the program's own sentence.
     *
     * Assumptions: BOTH rejection reasons are reported, not only the unmapped one. `usePfKeys` reports
     * `'disabled'` for a bound-but-refused key and `'unmapped'` for one with no entry, while
     * `0001-CHECK-PFKEYS` makes no such distinction -- its `ELSE` covers every key the mode does not
     * accept, which is how PF6 and PF4-outside-a-shown-row both earn the sentence. A press refused only
     * because a call is in flight is deliberately silent, since that condition has no counterpart in the
     * reference and reporting it would invent a message.
     * @param {PfKeyRejection} rejection - Why the key was not dispatched.
     * @returns {void} Completion is represented by the dispatched action.
     */
    onInvalidKey: (rejection: PfKeyRejection) => {
      const refusedByMode = !isRefTypeEditAid(rejection.aid) || !matrix[rejection.aid].accepted;
      if (refusedByMode) {
        dispatch({ type: 'keyRejected', aid: rejection.aid });
      }
    },
  });

  /*
   * WHY : Assumptions: only the legends this mode REVEALS reach the bar, which is the second half of
   *       `3391-SETUP-PFKEY-ATTRS`. `PfKeyBar` renders one control per binding and has no hidden state,
   *       so filtering the bindings is how a darkened legend is expressed -- and it leaves the key itself
   *       bound in the map above, which is what preserves the reference's acceptance-without-a-legend
   *       cases.
   */
  const revealedBindings = bindings.filter(
    /**
     * Keeps a binding when this mode brightens its legend field.
     * @param {(typeof bindings)[number]} binding - One binding `usePfKeys` produced.
     * @returns {boolean} `true` when the legend is on the glass in this mode.
     */
    (binding) => !isRefTypeEditAid(binding.aid) || matrix[binding.aid].revealed,
  );

  /*
   * WHY : ⚠ Refactoring Rationale: the title band, the row-23 message line and the row-24 legend are
   *       DELEGATED to the one `AppShell` that `ui/src/router.tsx` mounts as a layout route, not composed
   *       here. Composing them would paint a second header, a second row-23 band and a second legend on
   *       top of the shell's own -- two live regions announcing one message, and a duplicate
   *       `message-band` test handle where every caller expects exactly one. `ui/src/layout/AppShell.tsx`
   *       records at {@link useShellSlot} that all ten delivered screens opt in and none composes those
   *       three itself.
   * WHY : ⚠ Refactoring Rationale: the row-22 INFORMATION band is delegated too, and it was composed in
   *       the body. That is why it rendered roughly two hundred pixels BELOW the row-23 line the shell
   *       pins -- the two adjacent mapset fields appeared in the wrong order and the further one fell
   *       below the fold at every width, while the shell's own row-22 strip stood empty and
   *       `aria-hidden` above it. `ui/src/layout/AppShell.tsx` owns both terminal rows in one pinned
   *       zone and renders `message.information` immediately above `message.text`, which is the order
   *       `INFOMSG` at `POS=(22,23)` and `ERRMSG` at `POS=(23,1)` are declared in at
   *       `app/app-transaction-type-db2/bms/COTRTUP.bms`. Publishing is therefore the only arrangement
   *       that puts them adjacent and in order; composing one of them here cannot.
   * WHY : Assumptions: the information member names no severity, so the band's own table answers it.
   *       `MESSAGE_BAND_CHANNELS` in `ui/src/layout/MessageBand.tsx` records row 22 as `COLOR=NEUTRAL`
   *       and row 23 as `COLOR=RED`, and {@link messageChannel} reads the catalogue's own `field` to
   *       confirm this screen's prompts are declared under `WS-INFO-MSG` -- so the channel and its
   *       colour are both the mapset's record rather than a choice made here. Naming `neutral` again
   *       would put a second authority beside that table.
   * WHY : Assumptions: the row-23 severity IS named, and named as a constant rather than derived from a
   *       response. `ERRMSG` is `COLOR=RED ATTRB=(ASKIP,BRT,FSET)` at `COTRTUP.bms` L107 on every turn,
   *       so the line's colour is a mapset attribute. `messageBandSeverityForApiSeverity` is used on the
   *       sibling list screen instead, where the band reports a browse failure that carries a severity
   *       of its own; here every row-23 sentence is a refusal or an outcome this screen composed.
   * WHY : Assumptions: `legendColor` is passed explicitly as `YELLOW` rather than left to the bar's
   *       default, because it is a per-mapset attribute this screen owns -- all five row-24 fields are
   *       `COLOR=YELLOW` at `COTRTUP.bms` L112, L117, L122, L127 and L132, where the sibling list screen's
   *       are turquoise and it passes that.
   */
  useShellSlot({
    screen: {
      transactionId: REF_TYPE_EDIT_TRANSACTION_ID,
      programName: REF_TYPE_EDIT_PROGRAM_NAME,
    },
    now: paintedAt,
    message: {
      text: state.returnMessage,
      severity: 'error',
      mapset: REF_TYPE_EDIT_MAPSET,
      information: { text: infoMessage },
    },
    pfKeys: { keys: revealedBindings, onInvoke: invoke, legendColor: 'YELLOW' },
  });

  /*
   * WHY : Assumptions: the type code is rendered in the code face because it is a fixed-width numeric
   *       key and the description is not, because it is proportional text with nothing to align against
   *       -- the distinction `TYPOGRAPHY_TOKENS.fixedPitchData` records.
   */
  const fixedPitchStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

  /*
   * WHY : ⚠ Refactoring Rationale: the refusal rendering and the blank marker are taken from
   *       `ui/src/layout/fieldHelp.tsx` where this screen composed its own. That module records that the
   *       same nine lines had been authored independently on three of eight forms and were missing from
   *       five, and it carries two properties this screen's copy did not: the marker is `aria-hidden`,
   *       because a lone asterisk announced beside a value conveys nothing to a listener and the same
   *       fact already reaches assistive technology through `aria-invalid` and the row-23 sentence; and
   *       it carries a stable handle, so a case can tell this marker from the design system's always-on
   *       required asterisk, which is the same glyph meaning something else.
   *
   *       Assumptions: the copybook's asymmetry is unchanged and is now enforced in one place --
   *       `app/cpy/CSSETATY.cpy` L18-L22 moves the colour for either refused state and L23-L26 writes
   *       the asterisk for the BLANK state only.
   */
  const typeCodeRefusal = fieldRefusalRendering(refusalFor('typeCode')?.state, cssVar);
  const descriptionRefusal = fieldRefusalRendering(refusalFor('description')?.state, cssVar);

  /*
   * WHY : ⚠ Refactoring Rationale: the protected key control now states its value in the DEFAULT text
   *       colour, and it inherited the design system's disabled grey. Measured on the delivered build,
   *       a real record key rendered `rgba(0, 0, 0, 0.25)` on `rgba(0, 0, 0, 0.04)` -- so `'01'`, the
   *       row actually loaded, read exactly like placeholder text. The reference protects this field
   *       and does not darken it: `3310-PROTECT-ALL-ATTRS` at
   *       `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L1368-L1371 moves `DFHBMPRF`, which is
   *       protected plus modified-data-tag, and nothing anywhere moves a dark attribute to `TRTYPCDA`.
   *       The field's own colour is the terminal default, because `TRTYPCD` at
   *       `app/app-transaction-type-db2/bms/COTRTUP.bms` L84-L87 declares `ATTRB=(IC,UNPROT)` with no
   *       `COLOR` operand -- which the bridge resolves through `BMS_TEXT_COLOR_TOKENS.DEFAULT`.
   *
   *       Alternatives Considered: rendering the protected control `readOnly` instead of `disabled`,
   *       which is the closer HTML analogue of a protected field and would restore the contrast on its
   *       own. Rejected as the larger change: `readOnly` keeps the control in the tab order, so it
   *       would add a stop that answers nothing on every turn where the key is protected, and the
   *       greyed BACKGROUND that `disabled` supplies is the only affordance distinguishing an
   *       enterable field from a protected one on this form.
   *
   *       Trade-offs: the background stays the design system's disabled fill. That is accepted: what
   *       was measured as ambiguous is the VALUE reading as placeholder text, and a value at full
   *       contrast on a grey fill cannot be mistaken for one.
   */
  const protectedValueStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.DEFAULT],
  };

  /*
   * WHY : Assumptions: the two field labels take the TURQUOISE text token, because both are
   *       `COLOR=TURQUOISE` at `COTRTUP.bms` L80 and L90 and `BMS_TEXT_COLOR_TOKENS.TURQUOISE` is the
   *       label-text token the bridge snaps that colour to -- turquoise has no semantic equivalent in
   *       the design system, which design gap G3 records.
   */
  const labelStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE],
  };

  /*
   * WHY : Assumptions: the row-7 caption takes the NEUTRAL text token, because the mapset declares that
   *       field `COLOR=NEUTRAL` at L75. `ScreenTitle` owns the SIZE for every screen and the caller owns
   *       the colour, which is the split that module documents at its `style` prop.
   */
  const captionStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL],
  };

  return (
    <Flex vertical gap="large">
      {/*
        ⚠️ Purpose: a call in flight is ANNOUNCED as well as painted. The key bar's in-flight affordance and
        each control's `aria-busy` state are both properties of a control the operator has to be looking at
        or focused on; a screen reader driven by row 23 hears nothing at all between the press and the
        answer, because the reference had nothing to say there -- a pseudo-conversational task held the
        terminal, so the wait needed no sentence.

        ⚠️ Refactoring Rationale: the sentence is `REQUEST_IN_PROGRESS` from
        `ui/src/messages/messages.ts`, and an earlier revision of this screen recorded that the catalogue
        carried no sentence for this condition and that a screen may not author one. It now carries one, so
        the shortfall is closed rather than restated.

        Assumptions: the live region is mounted on EVERY turn and emptied when idle, not mounted with the
        sentence, because a region added to the tree at the moment it acquires text is not reliably
        announced -- the platform has to have been watching it.
      */}
      {busyAnnouncement(state.busy ? REQUEST_IN_PROGRESS : undefined)}
      <ScreenTitle style={captionStyle}>{REF_TYPE_EDIT_SUBTITLE}</ScreenTitle>
      {/*
        Assumptions: the abend surface REPLACES the form rather than sitting beside it, because the
        reference replaces the whole screen -- `ABEND-ROUTINE` at L1684-L1697 issues `EXEC CICS SEND
        FROM(ABEND-DATA) ... ERASE` and then abends the task, so no map is sent at all. The two labels are
        the baseline's OWN data names, read out of `ABEND_DATA_FIELDS` rather than written as prose, so the
        surface names the fields an operator would find at `app/cpy/CSMSG02Y.cpy` L45-L53.
      */}
      {state.abend === null ? (
        <>
          {/*
            Trade-offs: the two controls are laid out by GROUPING rather than at the mapset's
            `POS=(12,26)` and `POS=(14,26)`, which is documented design gap G1. What is kept is the pair's
            order, the label-to-control association -- which position carried on the terminal and
            `Form.Item` carries here -- and the tab order, which follows the DOM order. What is given up
            is pixel-for-character positioning, deliberately: reproducing a fixed 24x80 character grid in
            a browser is hostile to a screen reader and cannot reflow.
          */}
          <Form layout="vertical">
            {/*
              Alternatives Considered: carrying the refusal sentence in each item's `help` slot, which is
              what the design-system mapping assigns to a per-field error. Rejected HERE because the
              reference writes every refusal to `WS-RETURN-MSG` -- rendered by the row-23 `ERRMSG` field
              below -- while marking the field itself with colour and, for a blank field, the literal
              asterisk. Putting the sentence in `help` as well would render one sentence twice on one
              screen. So each item carries the MARK and the band carries the SENTENCE, which is the
              division the reference draws between its two message channels.
            */}
            <Form.Item
              label={
                <Typography.Text style={labelStyle}>
                  {REF_TYPE_EDIT_FIELD_LABELS.typeCode}
                </Typography.Text>
              }
              htmlFor={TYPE_CODE_CONTROL_ID}
              validateStatus={
                refusalFor('typeCode') !== undefined || keyReddenedByMode(state.mode) ? 'error' : ''
              }
            >
              <Input
                id={TYPE_CODE_CONTROL_ID}
                ref={typeCodeRef}
                value={state.newRecord.typeCode}
                maxLength={TYPE_CODE_WIDTH}
                /*
                 * WHY : ⚠ Assumptions: the WIDTH goes on `style` and the FACE goes on `styles.input`,
                 *       and the split is not cosmetic. With a suffix present -- which is now every
                 *       turn, per {@link MARKER_SLOT_UNOCCUPIED} -- `@rc-component/input` puts `style`
                 *       on the affix WRAPPER (`BaseInput.js` L124 and L137-L142) and `styles.input` on
                 *       the input itself (`Input.js` L171). The width belongs on the wrapper, because
                 *       that is the bordered box `copybookFieldWidthStyle` sizes. The colour does not:
                 *       `.ant-input` carries `color: token.colorText` of its own
                 *       (`antd/lib/input/style/index.js` L74 through L322), so a colour on the wrapper
                 *       is overridden on the very element whose text it was meant to change -- the
                 *       refusal red and the protected default would both be lost.
                 *       Assumptions: sizing the key control to two characters is also the other half of
                 *       the blank marker's remedy. `ui/src/layout/recordLayout.ts` records the marker
                 *       measured roughly 1150 pixels from the value it qualified on a full-width
                 *       control; no suffix can be brought to a value while the field is far wider than
                 *       the data.
                 *       Assumptions: within the face, the refusal colour is merged LAST so it wins over
                 *       the field's own colour for as long as the refusal stands, which is what
                 *       `fieldRefusalRendering` asks its callers to do.
                 */
                /*
                 * WHY : ⚠️ Refactoring Rationale: the marker slot is declared to the measure, and this
                 *       control is where the omission was measured. With the slot always occupied per
                 *       {@link MARKER_SLOT_UNOCCUPIED}, a maximum computed for two characters alone
                 *       gave this input a 12-pixel content box for a 17-pixel value, and the record's
                 *       own key `01` rendered as `0:` at 375, 768, 1280 and 1920 alike -- a fixed cap
                 *       does not vary with the viewport. The key is authoritative data on this screen,
                 *       so a key an operator cannot read is a correctness failure and not a cosmetic
                 *       one. Its fifty-character sibling below never showed the fault because there the
                 *       container bound the width long before the cap did.
                 */
                style={copybookFieldWidthStyle(
                  TYPE_CODE_WIDTH,
                  cssVar,
                  BLANK_FIELD_MARKER_CHARACTERS,
                )}
                styles={{
                  input: {
                    ...fixedPitchStyle,
                    ...(editable === 'typeCode' ? {} : protectedValueStyle),
                    ...typeCodeRefusal.style,
                  },
                }}
                suffix={typeCodeRefusal.suffix ?? MARKER_SLOT_UNOCCUPIED}
                {...fieldAriaProps(TYPE_CODE_CONTROL_ID, {
                  invalid: refusalFor('typeCode') !== undefined || keyReddenedByMode(state.mode),
                  /*
                   * WHY : Assumptions: `hasError` is false although the field IS refused, and
                   *       `fieldAriaProps` documents exactly this pairing. The reference writes every
                   *       refusal to `WS-RETURN-MSG`, painted by the row-23 line, and marks the field
                   *       itself with colour and the blank asterisk -- so rendering the sentence under
                   *       the control as well would put one refusal on the glass twice. No element
                   *       carrying this control's error identifier is rendered, so naming one would be
                   *       a dangling reference.
                   */
                  hasError: false,
                  hasHint: false,
                })}
                {...busyProps(state.busy)}
                disabled={editable !== 'typeCode' || state.busy}
                inputMode="numeric"
                onChange={
                  /**
                   * Keeps only the digits the reference's numeric edit admits.
                   *
                   * Assumptions: non-digits are dropped at entry rather than refused at validation,
                   * because the terminal field was `NUM` on the mapsets that declare one and a browser
                   * text control has no equivalent. The validator still refuses a non-numeric entry, so
                   * the sentence remains reachable through a pasted value.
                   * @param {ChangeEvent<HTMLInputElement>} event - The entry event.
                   * @returns {void} Completion is represented by the dispatched action.
                   */
                  (event: ChangeEvent<HTMLInputElement>) => {
                    dispatch({
                      type: 'fieldChanged',
                      field: 'typeCode',
                      value: event.target.value.replace(NON_DIGITS, ''),
                    });
                  }
                }
              />
              {/*
                ⚠ Assumptions: the literal asterisk is written for the BLANK case ONLY, and it is now
                the control's own `suffix` rather than an element beside it. The templated highlight
                `app/cpy/CSSETATY.cpy` sets the colour for either refusal at L18-L22 and then gates the
                marker on an INNER `IF FLG-(TESTVAR1)-BLANK` at L23-L26 -- so a malformed entry reddens
                without a marker and a missing one is marked as well. The reference wrote its asterisk
                INTO the field's own columns, which is what a suffix inside the control's box
                corresponds to and what an element after the control did not.
              */}
            </Form.Item>
            <Form.Item
              label={
                <Typography.Text style={labelStyle}>
                  {REF_TYPE_EDIT_FIELD_LABELS.description}
                </Typography.Text>
              }
              htmlFor={DESCRIPTION_CONTROL_ID}
              validateStatus={refusalFor('description') !== undefined ? 'error' : ''}
            >
              <Input
                id={DESCRIPTION_CONTROL_ID}
                ref={descriptionRef}
                value={state.newRecord.description}
                maxLength={DESCRIPTION_WIDTH}
                /*
                 * WHY : Assumptions: the slot is declared here too although this field is wide enough
                 *       that the container binds before the cap does, so the allowance changes nothing
                 *       an operator can see. It is declared for consistency of contract rather than for
                 *       effect: the slot IS occupied on every turn on this control, and a measure that
                 *       states the truth about its own box cannot become wrong if the declared width is
                 *       ever reduced.
                 */
                style={copybookFieldWidthStyle(
                  DESCRIPTION_WIDTH,
                  cssVar,
                  BLANK_FIELD_MARKER_CHARACTERS,
                )}
                styles={{
                  input: {
                    ...(editable === 'description' ? {} : protectedValueStyle),
                    ...descriptionRefusal.style,
                  },
                }}
                suffix={descriptionRefusal.suffix ?? MARKER_SLOT_UNOCCUPIED}
                {...fieldAriaProps(DESCRIPTION_CONTROL_ID, {
                  invalid: refusalFor('description') !== undefined,
                  hasError: false,
                  hasHint: false,
                })}
                {...busyProps(state.busy)}
                disabled={editable !== 'description' || state.busy}
                onChange={
                  /**
                   * Records the typed description unaltered, so the reference's own edit decides on it.
                   * @param {ChangeEvent<HTMLInputElement>} event - The entry event.
                   * @returns {void} Completion is represented by the dispatched action.
                   */
                  (event: ChangeEvent<HTMLInputElement>) => {
                    dispatch({
                      type: 'fieldChanged',
                      field: 'description',
                      value: event.target.value,
                    });
                  }
                }
              />
            </Form.Item>
          </Form>
          {/*
            ⚠ Assumptions: the delete is additionally confirmed through a `Modal`, which is a browser
            addition ON TOP OF the reference's own two-press confirmation rather than a replacement for
            it. A pointer can activate a control by accident where a function key cannot. The key path is
            unaffected: PF4 still asks on the first press and deletes on the second, and the prompt the
            dialogue carries is the same catalogued sentence the row-22 band shows.

            ⚠ Refactoring Rationale: the control and the confirmation are rendered SEPARATELY, where the
            confirmation used to wrap the control. A `Popconfirm` had to wrap its trigger because it
            aligned against it; a `Modal` is anchored to the viewport, so the two are independent and the
            control cannot be mistaken for the dialogue's opener. See {@link renderDeleteConfirmation}
            for why the primitive changed.
          */}
          <Flex gap="small">{renderDeleteControl()}</Flex>
          {renderDeleteConfirmation()}
        </>
      ) : (
        <Result
          status="error"
          title={abendHeading(state.abend)}
          subTitle={state.abend.abendReason}
          extra={
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label={ABEND_LABELS.abendCode}>
                {state.abend.abendCode}
              </Descriptions.Item>
              <Descriptions.Item label={ABEND_LABELS.abendCulprit}>
                {state.abend.abendCulprit}
              </Descriptions.Item>
            </Descriptions>
          }
        />
      )}
      {/*
        ⚠ Assumptions: TWO bands, never merged -- and NEITHER is composed here any more. This mapset
        declares two message fields that carry different things at the same time: `INFOMSG` at
        `POS=(22,23)` is the mode prompt and `ERRMSG` at `POS=(23,1)` is the rejection or outcome.
        Collapsing them into one line would drop whichever message the other overwrote. Both are
        published on the shell slot above, which is what keeps them adjacent and in the declared order;
        the rationale for the move is recorded there.
      */}
    </Flex>
  );
}

export default RefTypeEditScreen;
