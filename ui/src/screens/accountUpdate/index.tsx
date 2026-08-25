/**
 * @file The account update screen, migrated from `app/cbl/COACTUPC.cbl` (4,236 lines) and its mapset
 * `app/bms/COACTUP.bms`, map `CACTUPA`. Mounted at `/account/update`.
 *
 * Purpose
 * -------
 * Reproduce the reference screen's fetch-then-edit-then-confirm workflow over both records the
 * baseline edits together: read one account with its customer, let an operator change the forty
 * fields the reference unprotects, validate on Enter, and write only after an explicit F5
 * confirmation. It replaces CICS transaction `CAUP`, and it is the largest screen in this tree -- the
 * mapset declares 128 `DFHMDF` fields, 54 named and 74 anonymous, the highest count of all
 * twenty-one mapsets. Six of the named fields are the shared title band, three are the row-24 legend
 * and two are the message lines, leaving forty-three data fields that this module renders.
 *
 * The seven-value screen state
 * ---------------------------
 * Assumptions: the reference holds a single change-action character in its passed structure and
 * branches on seven declared conditions of it. That character is what makes the two-turn
 * confirmation, the read-only interval before the write and the two conditionally-painted legend
 * fields expressible at all, so it is modelled here as {@link ChangeAction} rather than collapsed.
 * AAP section 0.7.1 removes the structure that carried it between pseudo-conversational turns, so
 * the browser holds it -- the same relocation the navigation and selection fields underwent.
 *
 * Concurrency
 * -----------
 * Assumptions: a concurrent change is reported, never overwritten. The reference snapshots a
 * complete pre-edit before-image into `05 ACUP-OLD-DETAILS.` at L669 and carries
 * `05 WS-DATACHANGED-FLAG PIC X(1).` at L168, re-reads both records under an update lock and
 * compares them in `9700-CHECK-CHANGE-IN-REC` before rewriting. The migrated service expresses the
 * same guarantee with an optimistic version column published as a weak entity tag, so this screen
 * carries the revision its read returned and discriminates HTTP 409 through the transport module's
 * own predicate.
 *
 * What this screen deliberately does not do
 * -----------------------------------------
 * Trade-offs: it runs no business validation of its own, and it does not need to. The reference edits
 * its fields in a fixed order on the Enter turn and writes only on the later F5 turn, and the contract
 * now publishes an operation for EACH turn: `POST /api/v1/accounts/update/validate` runs every edit and
 * writes nothing, and `POST /api/v1/accounts/update` writes under a revision precondition. Enter calls
 * the first and moves to the confirmation state only on its answer; F5 calls the second. Both turns run
 * the same `editMapInputs`, so the service remains the single authority on what is acceptable and every
 * sentence is the reference's own because the response body supplies it.
 *
 * ⚠️ Refactoring Rationale: this paragraph recorded a DIFFERENT arrangement, and it was accurate when
 * written: no validate-only address existed, so Enter performed the reference's change detection alone,
 * announced `Changes validated.Press F5 to save`, protected the form, and left the field refusals to
 * arrive on the write turn. A review found what that cost -- an operator was told a thirty-six-field
 * submission had been validated when nothing had validated it, and was then instructed to press the save
 * key. The two readings the paragraph weighed were re-implementing the edits in the browser or deferring
 * them to the write; the third, which is the one taken, was to publish the turn the reference already
 * has. Re-implementing them here remains rejected for the reason recorded then, and
 * `ui/src/api/types.ts` still states it.
 */

import {
  Button,
  Card,
  Col,
  Descriptions,
  Flex,
  Form,
  Input,
  Popconfirm,
  Result,
  Row,
  Space,
  Spin,
  Typography,
  theme,
} from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { CSSProperties, ReactElement, ReactNode } from 'react';
/*
 * WHY : Assumptions: the grid's gutter type is imported from the design system rather than restated
 *       here, because it is a union the component owns -- a number, a per-screen-name record, or a pair
 *       of either -- and a local restatement would silently stop matching the component the day the
 *       union gained a member. Nothing is imported at runtime by this line.
 */
import type { RowProps } from 'antd';
import { useNavigate } from 'react-router';

import {
  isConflictFailure,
  readAccountView,
  updateAccount,
  validateAccountUpdate,
} from '../../api/accounts';
import type {
  AccountUpdateResponse,
  AccountUpdateValidationResponse,
  AccountViewResponse,
  CustomerDetail,
  SensitiveAccountUpdateRequest,
} from '../../api/accounts';
/*
 * WHY : ⚠️ Assumptions: the TRANSIENCE predicate is imported beside the narrowing one, because the
 *       transport module is where that classification belongs and it already performs it -- it marks a
 *       timeout and the four retryable statuses transient and a network failure not. Re-deriving it
 *       here from a status list would put a second, screen-local opinion beside the module's own, and
 *       the screen that disagreed would tell an operator to try again for a failure repeating cannot
 *       clear.
 */
import { isApiRequestError, isTransientFailure } from '../../api/client';
import { applyMoneyEditMask, stripMoneyEditMask } from '../../format/money';
/*
 * WHY : Assumptions: this ONE type is imported from the shapes module while every other shape on this
 *       screen comes through `../../api/accounts`. That module re-exports the twelve account shapes so
 *       a screen has a single import site for the calls and the bodies they carry, and `FieldError` is
 *       not among them -- it is a shared shape every context's problem document carries, declared once
 *       beside the rest in `../../api/types`. Re-declaring it here to avoid the second import site
 *       would create a second definition of a wire shape, which is the drift the contract gate exists
 *       to prevent.
 */
import type { FieldError } from '../../api/types';
import { useShellSlot } from '../../layout/AppShell';

/*
 * WHY : ⚠️ Refactoring Rationale: only the SEVERITY type is imported now. The component itself was
 *       imported to paint this screen's row-22 line inside the form, and both message rows are
 *       delegated to the frame's pinned zone as of the band-placement change recorded at the removal
 *       site -- so importing the component would leave a dependency nothing renders, which is how a
 *       withdrawn arrangement quietly comes back.
 */
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap, PfKeyRejection } from '../../layout/usePfKeys';
import { useServerInstant } from '../../hooks/useServerInstant';
/*
 * WHY : ⚠️ Assumptions: the three AUTHORED sentences are imported from the same catalog as the
 *       transcribed ones, and that is what keeps them lawful. Transformation rule T8 forbids a screen
 *       from inventing operator wording; these three describe conditions the reference has no wording
 *       for -- a request still outstanding, and a failure that never reached the service at all -- so
 *       they are declared once in the catalog, registered and width-bounded there, and only published
 *       here.
 */
import {
  ABEND_DATA_FIELDS,
  ACCOUNT_UPDATE_FIELD_LABELS,
  MESSAGE_TEMPLATES,
  PERSISTENT_FAILURE_REPORT_IT,
  REQUEST_IN_PROGRESS,
  STATUS_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
  UNEXPECTED_ABEND_OCCURRED,
  UNEXPECTED_DATA_SCENARIO,
  fitsDeclaredWidth,
  formatMessageTemplate,
  normaliseForWire,
} from '../../messages/messages';
import type { MapsetName } from '../../messages/messages';
import {
  BMS_TEXT_COLOR_TOKENS,
  FIELD_ERROR_TOKENS,
  SPACING_TOKENS,
  TYPOGRAPHY_TOKENS,
} from '../../theme/tokens';
import { MAIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
/*
 * WHY : Refactoring Rationale: this screen no longer resolves the two reference lookups itself, and the
 *       imports that did -- `getUsState`, `getUsStateZipPrefix`, `editAccountUpdateInputs`,
 *       `stateZipLookupKey` and the two chain types -- are withdrawn from it. Two designs for this step
 *       were authored: a LOCAL edit chain that consulted the reference service for the state code and
 *       the state-and-postal-prefix pairing, and a call to the account context's own
 *       `/accounts/update/validate` operation. The service call is retained because it is the one with
 *       an endpoint behind it -- account-service publishes the operation and asserts it, and this
 *       screen's own cases stub it -- and because the service is the authority that will refuse the
 *       write in any case, so a second opinion computed here could disagree with the one that decides.
 * WHY : Assumptions: nothing is lost from the local chain's work. It lives in `./edits` as one pure
 *       function over the form values and is exercised directly by `./edits.test.ts`, which is a
 *       stronger test of twenty-four ordered edits than driving them through a rendered screen; the
 *       module is retained for that reason rather than deleted with its wiring.
 */
/*
 * WHY : Assumptions: the edit chain lives in a sibling module rather than in this file. It is one pure
 *       function over the form values with no React in it, and this module is already the screen's
 *       largest -- keeping the twenty-four edits beside the rendering would bury them, and separating
 *       them is what lets the chain be exercised directly rather than only through a rendered screen.
 */
import {
  BLANK_FIELD_MARKER_CHARACTERS,
  busyAnnouncement,
  busyProps,
  fieldAriaProps,
  fieldErrorHelp,
} from '../../layout/fieldHelp';
import { copybookFieldWidthStyle } from '../../layout/recordLayout';
import { UNPOPULATED_CUSTOMER } from '../accountView/index';
import { SECTION_HEADING_LEVEL, ScreenTitle } from '../../layout/ScreenTitle';

/*
 * WHY : Assumptions: every user-visible SENTENCE on this screen resolves through
 *       `ui/src/messages/messages.ts` and every design value through `../../theme/tokens`, and those
 *       two modules are the single owners of their respective classes. That is what makes the
 *       byte-exactness guarantee and the zero-hardcoded-values rule mechanically true here rather
 *       than a matter of this author's discipline: a sentence cannot drift from the baseline without
 *       the catalog changing, and a colour cannot leave the CSS-variable theme without the bridge
 *       changing.
 * WHY : Assumptions: one discrepancy in the baseline must survive and is recorded here so nobody
 *       normalises it. The declared condition at `app/cbl/COACTUPC.cbl` L506 holds
 *       `Credit Limit must be supplied` with NO trailing period, while the sentence the same program
 *       composes at run time through `1250-EDIT-SIGNED-9V2` is the name token followed by the suffix
 *       ` must be supplied.` WITH one. Both exist in the reference. This screen renders whichever the
 *       response body supplies and never rewrites either, so the two stay distinguishable.
 * WHY : Assumptions: `HILIGHT=UNDERLINE` appears on all forty of the mapset's editable fields and
 *       maps to NO token, which is design gap G4 rather than an oversight. The design system carries
 *       an input's affordance structurally, in the `Input` component's own border, so a token here
 *       would add a second expression of one affordance.
 */

/** CICS transaction identifier this screen replaces, from `LIT-THISTRANID` at `COACTUPC.cbl` L535. */
export const ACCOUNT_UPDATE_TRANSACTION_ID = 'CAUP';

/** Source program name, from `LIT-THISPGM`, rendered in the header band as the 3270 screen did. */
export const ACCOUNT_UPDATE_PROGRAM_NAME = 'COACTUPC';

/**
 * Mapset this screen stands in, which selects the message band's display width.
 *
 * Assumptions: typed as {@link MapsetName} so the value is checked against the catalog's own table
 * rather than passed as free text; `COACTUP` resolves there to map `CACTUPA` at 78 characters.
 */
export const ACCOUNT_UPDATE_MAPSET: MapsetName = 'COACTUP';

/**
 * The screen and section headings, verbatim from the mapset's two `COLOR=NEUTRAL` anonymous fields.
 *
 * Assumptions: both are declared with no `ATTRB=` operand at all, so they inherit the protected
 * default, and both are `COLOR=NEUTRAL` where every surrounding label is `COLOR=TURQUOISE` -- which
 * is what marks them as headings rather than labels and is why they resolve to a different token.
 */
export const ACCOUNT_UPDATE_HEADINGS = {
  /** `app/bms/COACTUP.bms` L75-L78, `LENGTH=14` at row 4. */
  screen: 'Update Account',
  /** `app/bms/COACTUP.bms` L246-L249, `LENGTH=16` at row 11. */
  customerSection: 'Customer Details',
} as const;

/**
 * Every field label the mapset paints, verbatim including interior and trailing padding.
 *
 * Assumptions: the padding inside and after these values is CONTENT and not formatting. The mapset
 * pads `Credit Limit        :` and its four siblings to `LENGTH=21` so the colons align down the
 * right-hand column, and pads `Active Y/N: `, `Middle Name: `, `Last Name : `, `State `, `City ` and
 * `EFT Account Id: ` with a trailing space; the transcription rule for this tree is byte-exact, so a
 * whitespace-collapsing edit here is a defect.
 *
 * Assumptions: these are declared HERE rather than imported, and the reason is a real gap rather
 * than a preference. `ui/src/messages/messages.ts` owns the 147 message literals and, under
 * `ACCOUNT_UPDATE_FIELD_LABELS`, the 27 NAME TOKENS this program moves into
 * `WS-EDIT-VARIABLE-NAME` to compose a refusal -- a different class from a painted screen label,
 * carrying different text: the token is `Credit Limit` where the painted label is
 * `Credit Limit        :`. No catalog export holds the painted labels of any mapset, and the
 * authored sibling `ui/src/screens/cardUpdate/index.tsx` L64-L77 sets the precedent for this class by
 * declaring them in the screen module with a per-label mapset citation. That precedent is followed
 * so one class is not filed under two conventions.
 */
export const ACCOUNT_UPDATE_FIELD_LABELS_PAINTED = {
  /** `app/bms/COACTUP.bms` L79-L83, `LENGTH=16`. */
  accountId: 'Account Number :',
  /** `app/bms/COACTUP.bms` L90-L93, `LENGTH=12`, trailing space is content. */
  activeStatus: 'Active Y/N: ',
  /** `app/bms/COACTUP.bms` L100-L103, `LENGTH=8`. */
  openDate: 'Opened :',
  /** `app/bms/COACTUP.bms` L127-L131, `LENGTH=21`. */
  creditLimit: 'Credit Limit        :',
  /** `app/bms/COACTUP.bms` L138-L141, `LENGTH=8`. */
  expirationDate: 'Expiry :',
  /** `app/bms/COACTUP.bms` L165-L169, `LENGTH=21`. */
  cashCreditLimit: 'Cash credit Limit   :',
  /** `app/bms/COACTUP.bms` L176-L179, `LENGTH=8`. */
  reissueDate: 'Reissue:',
  /** `app/bms/COACTUP.bms` L203-L207, `LENGTH=21`. */
  currentBalance: 'Current Balance     :',
  /** `app/bms/COACTUP.bms` L214-L218, `LENGTH=21`. */
  currentCycleCredit: 'Current Cycle Credit:',
  /** `app/bms/COACTUP.bms` L225-L228, `LENGTH=14`. */
  groupId: 'Account Group:',
  /** `app/bms/COACTUP.bms` L235-L239, `LENGTH=21`. */
  currentCycleDebit: 'Current Cycle Debit :',
  /** `app/bms/COACTUP.bms` L250-L253, `LENGTH=14`, two interior spaces are content. */
  customerId: 'Customer id  :',
  /** `app/bms/COACTUP.bms` L260-L263, `LENGTH=4` -- the mapset's ONLY label for all three parts. */
  ssn: 'SSN:',
  /** `app/bms/COACTUP.bms` L287-L290, `LENGTH=14`. */
  dateOfBirth: 'Date of birth:',
  /** `app/bms/COACTUP.bms` L314-L317, `LENGTH=11`. */
  ficoCreditScore: 'FICO Score:',
  /** `app/bms/COACTUP.bms` L324-L327, `LENGTH=10`. */
  firstName: 'First Name',
  /** `app/bms/COACTUP.bms` L328-L331, `LENGTH=13`, trailing space is content. */
  middleName: 'Middle Name: ',
  /** `app/bms/COACTUP.bms` L332-L335, `LENGTH=12`, trailing space is content. */
  lastName: 'Last Name : ',
  /** `app/bms/COACTUP.bms` L352-L355, `LENGTH=8` -- painted once, beside address line 1. */
  address: 'Address:',
  /** `app/bms/COACTUP.bms` L362-L365, `LENGTH=6`, trailing space is content. */
  stateCode: 'State ',
  /** `app/bms/COACTUP.bms` L378-L381, `LENGTH=3`. */
  zipCode: 'Zip',
  /** `app/bms/COACTUP.bms` L388-L391, `LENGTH=5`, trailing space is content. */
  city: 'City ',
  /** `app/bms/COACTUP.bms` L398-L401, `LENGTH=7`. */
  countryCode: 'Country',
  /** `app/bms/COACTUP.bms` L408-L411, `LENGTH=8`. */
  phone1: 'Phone 1:',
  /** `app/bms/COACTUP.bms` L429-L432, `LENGTH=30`, four interior and one trailing space. */
  governmentIssuedId: 'Government Issued Id Ref    : ',
  /** `app/bms/COACTUP.bms` L439-L442, `LENGTH=8`. */
  phone2: 'Phone 2:',
  /** `app/bms/COACTUP.bms` L460-L463, `LENGTH=16`, trailing space is content. */
  eftAccountId: 'EFT Account Id: ',
  /** `app/bms/COACTUP.bms` L470-L473, `LENGTH=24`. */
  primaryCardHolderIndicator: 'Primary Card Holder Y/N:',
} as const;

/**
 * The row-24 legend text, split from the mapset's THREE legend fields.
 *
 * Assumptions: the mapset paints three fields on row 24, not one. `FKEYS` at L493-L497 is
 * `ATTRB=(ASKIP,NORM)` and always visible, carrying `ENTER=Process F3=Exit` in a single `LENGTH=21`
 * field; `FKEY05` at L498-L502 and `FKEY12` at L503-L507 are both `ATTRB=(ASKIP,DRK)` -- non-display
 * -- carrying `F5=Save` and `F12=Cancel`, and `3390-SETUP-INFOMSG-ATTRS` at L3566-L3583 un-darkens
 * them by state. The always-visible field is split into its two keys because the design system
 * renders one control per key; the two conditional fields are already one key each.
 */
export const ACCOUNT_UPDATE_KEY_LABELS = {
  ENTER: 'ENTER=Process',
  PFK03: 'F3=Exit',
  PFK05: 'F5=Save',
  PFK12: 'F12=Cancel',
} as const;

/**
 * The mapset's own `INITIAL=` hints on the three national-identifier parts.
 *
 * Assumptions: these are HINTS and not values. `ACTSSN1` carries `INITIAL='999'`, `ACTSSN2`
 * `INITIAL='99'` and `ACTSSN3` `INITIAL='9999'` at `app/bms/COACTUP.bms` L268, L276 and L284, which a
 * 3270 paints into an empty field to show its shape. They render as the design system's
 * `placeholder`, which is the modern equivalent -- carried as an attribute so the value is never
 * submitted, where the mapset's initial WAS submitted unless overtyped.
 */
export const SSN_PART_PLACEHOLDERS = {
  ssnPart1: '999',
  ssnPart2: '99',
  ssnPart3: '9999',
} as const;

/**
 * The separator the mapset paints between the parts of every date, verbatim.
 *
 * Assumptions: this is content the mapset declares, not punctuation chosen here. Each of the four
 * dates is followed by two anonymous `LENGTH=1` fields carrying `INITIAL='-'` -- at
 * `app/bms/COACTUP.bms` L109-L111 and L117-L119 for the open date, and in the same shape for the
 * expiry, reissue and birth dates -- so the terminal painted a hyphen between the parts.
 */
export const DATE_PART_SEPARATOR = '-';

/*
 * WHY : ⚠️ Purpose: the two protected identifiers are the one place this screen CANNOT reproduce what the
 *       terminal showed. The reference paints the government-issued identifier in the clear --
 *       `MOVE ACUP-NEW-CUST-GOVT-ISSUED-ID TO ACSGOVTO` at `app/cbl/COACTUPC.cbl` L2946 -- and this
 *       system's read operations return neither identifier in the clear at all: `CustomerDetail` in
 *       `services/account-service/src/main/resources/openapi/account-api.yaml` carries `ssnMasked` and
 *       `governmentIssuedIdMasked` and no clear counterpart, because both columns are enciphered and
 *       `CustomerMapper` publishes the FIXED marker `[REDACTED]` for each. So there is no stored value
 *       to paint, and this caption is what stands in its place.
 * WHY : Refactoring Rationale: the two masked members were DISCARDED by the seeding path while the
 *       comment above it said they were "offered beside the control", so the screen showed nothing at
 *       all beside four blank boxes. An operator could not tell what leaving them blank
 *       does -- which is the one operative fact here, and it is NOT the same fact for both members: the
 *       two captions below state each one separately, for the measured reason recorded there.
 * WHY : Assumptions: this text is ADDITIVE and has no counterpart in the mapset, so it is authored
 *       rather than transcribed, and transformation rule T8 does not reach it -- there is no reference
 *       literal to carry. It is worded to claim NOTHING about the stored value, deliberately: the
 *       marker is a constant that discloses neither a fragment of the value, nor its length, nor -- for
 *       the optional government-issued identifier -- whether the row holds one at all, so a caption
 *       reading "stored value" or "on file" would assert existence the marker cannot support.
 * WHY : Alternatives Considered: correcting the comment to say the masked members are not offered, and
 *       rendering nothing. Rejected because it would leave the capability gap unmarked on screen: the
 *       operator would still have no way to know what blank does on either member, and the difference
 *       between a member blank preserves and a member blank is refused on is not one to leave to
 *       inference.
 */

/*
 * WHY : ⚠️ Refactoring Rationale: the two identifiers get DIFFERENT captions, where one shared sentence
 *       reading "leave blank to keep it" was written for both. It is true of the government-issued
 *       identifier and false of the national identifier, which was established by submitting a changing
 *       edit with the three parts blank to the running service: it answered 400 naming `ssnPart1`,
 *       `ssnPart2` and `ssnPart3` each in the `BLANK` state. The cause is that the two obligations sit in
 *       different layers. `CustomerMapper.nationalIdentifierUpdate` does select preserve for a
 *       never-supplied value, but `AccountUpdateService.editMapInputs` runs `editNationalIdentifier`
 *       first and its three `editNumericRequired` edits refuse a blank part outright -- so on any
 *       submission that changed something, blank is refused before the mapper is ever consulted. The
 *       government-issued identifier has no such edit, so blank reaches
 *       `CustomerMapper.governmentIdentifierUpdate` and preserves.
 * WHY : Assumptions: the national-identifier caption therefore states the OBLIGATION rather than a
 *       preservation promise. Telling an operator that blank keeps the stored value, on the one field
 *       where blank is refused, sends them into a refusal the caption told them to expect not to get --
 *       and on a form of forty editable fields that refusal reads as arbitrary.
 * WHY : Assumptions: both captions still claim NOTHING about the stored value itself. The marker is a
 *       constant that discloses neither a fragment of the value, nor its length, nor -- for the optional
 *       government-issued identifier -- whether the row holds one at all, so neither sentence asserts
 *       existence the marker cannot support.
 */

/**
 * Builds the caption rendered beside the three national-identifier parts.
 * @param {string} marker - The redaction marker the read returned, rendered exactly as received.
 * @returns {string} The caption text: the marker followed by the obligation the edits impose.
 */
export function nationalIdentifierCaption(marker: string): string {
  return `${marker} — existing value is not displayed; retype all three parts to save a change`;
}

/**
 * Builds the caption rendered beside the government-issued identifier.
 * @param {string} marker - The redaction marker the read returned, rendered exactly as received.
 * @returns {string} The caption text: the marker followed by what leaving the control blank does.
 */
export function governmentIdentifierCaption(marker: string): string {
  return `${marker} — existing value is not displayed; leave blank to keep it`;
}

/** Identifier of the caption standing beside the three national-identifier parts. */
export const SSN_STANDING_ID = 'carddemo-account-update-ssn-standing';

/** Identifier of the caption standing beside the government-issued identifier. */
export const GOVERNMENT_ID_STANDING_ID = 'carddemo-account-update-government-id-standing';

/** The two masked identifiers a read returned, held for display and never for submission. */
export interface ProtectedIdentifierDisplay {
  /** The marker published in place of the national identifier, exactly as received. */
  readonly ssnMasked: string;
  /** The marker published in place of the government-issued identifier, exactly as received. */
  readonly governmentIssuedIdMasked: string;
}

/*
 * WHY : Assumptions: these six names are ADDITIVE and have no painted counterpart in the mapset, which
 *       paints ONE label for each split group and relies on the operator seeing three adjacent boxes.
 *       A screen reader announces one control at a time, so without a name per part the three boxes
 *       under `Opened :` are indistinguishable -- and the 3270 original was keyboard-only, so keeping
 *       every control identifiable is a fidelity requirement rather than an addition. They are
 *       accessible names only: no name below is rendered visibly, so none of them changes what the
 *       screen displays.
 *       Assumptions: the three telephone words are the reference's OWN vocabulary rather than new
 *       terms. `Area code`, `Prefix code` and `Line number code` each appear verbatim inside the
 *       refusal suffixes the catalog holds for these very parts, so a marked part is named with the
 *       same words as the sentence beside it. The three date words name the parts the map splits each
 *       date into and have no equivalent source, which is why they are declared here and not imported.
 *       Alternatives Considered: using the whole refusal suffix as the accessible name, which needs no
 *       new string at all. Rejected because the suffixes are sentences -- `: Area code must be
 *       supplied.` -- so a screen reader would announce a refusal as the name of a control that has
 *       not been refused.
 */

/*
 * WHY : ⚠️ Refactoring Rationale: the two protected identifiers now render their STORED state beside
 *       their replacement control, where previously both members of the response were read and
 *       discarded and neither appeared anywhere on the screen. The module's own explanation said the
 *       masked value "is offered beside the control instead of inside it" -- a description of a surface
 *       that did not exist. The cost was concrete rather than cosmetic: the reference paints the whole
 *       identifier into these controls, `MOVE ACUP-NEW-CUST-GOVT-ISSUED-ID TO ACSGOVTO` at
 *       `app/cbl/COACTUPC.cbl` L2946 and the SSN parts at L2921 to L2931, so an operator arriving at
 *       the reference screen can SEE what is stored and decide whether to change it. Rendering nothing
 *       left a blank box that could equally mean stored-and-hidden or never-supplied, and an operator
 *       cannot decide not to change a value they cannot see any trace of.
 * WHY : Assumptions: the stored state is rendered from `ssnMasked` and `governmentIssuedIdMasked`
 *       EXACTLY as the service sent them, in a read-only position, and never inside the control. AAP
 *       section 0.4.1.9 stores both encrypted and returns both masked, so the whole value the reference
 *       painted does not exist in the browser to paint; and seeding the mask into the control would
 *       submit the mask, which the service would then store as the identifier.
 * WHY : Alternatives Considered: rendering the masked value as the control's `placeholder`, which needs
 *       no new string and sits inside the field the way the reference's value did. Rejected because a
 *       placeholder disappears the instant anything is typed -- so it would vanish at exactly the
 *       moment an operator is deciding whether their replacement is right -- and because a placeholder
 *       reads to assistive technology as a hint about what to enter rather than as the value on file.
 *       Trade-offs: the caption below is ADDITIVE and has no painted counterpart, so it is the one
 *       VISIBLE string on this screen that is not transcribed from the mapset. It is accepted because
 *       the alternative to naming the value is showing an unlabelled run of asterisks, which states
 *       even less than the blank control it sits under.
 * WHY : Assumptions: it is declared HERE rather than in `ui/src/messages/messages.ts`, which is that
 *       module's own boundary rather than a gap in it -- the catalog owns every string carried ACROSS
 *       from the baseline, and invented chrome belongs with its renderer. `SKIP_TO_CONTENT_LABEL` in
 *       `ui/src/layout/AppShell.tsx` sets that precedent for exactly this class and records the same
 *       reasoning, so this follows an existing convention rather than opening a second one.
 */

/** Names the stored, masked state shown beside a protected control. Additive: no painted counterpart. */
export const ACCOUNT_UPDATE_STORED_STATE_LABEL = 'On file:' as const;

/** Accessible names for the parts of each split group, additive and never rendered visibly. */
export const ACCOUNT_UPDATE_PART_NAMES = {
  year: 'Year',
  month: 'Month',
  day: 'Day',
  areaCode: 'Area code',
  prefix: 'Prefix code',
  lineNumber: 'Line number code',
} as const;

/*
 * WHY : ⚠️ Purpose: address line 2 is the one editable control on this screen with NO painted label at
 *       all, and it had no accessible name either -- the mapset paints `Address:` at row 16 for the
 *       block and puts `ACSADL2` bare at row 17, so a sighted operator reads the label above while a
 *       screen reader announced an unnamed text box. Positional association is exactly what design-system
 *       gap G1 gives up, so the name has to be stated rather than inferred from where the box sits.
 * WHY : Assumptions: the words are the REFERENCE'S OWN and are not invented here. `app/cbl/COACTUPC.cbl`
 *       L1614 holds `MOVE 'Address Line 2' TO WS-EDIT-VARIABLE-NAME` commented out on the line above
 *       L1615, with the comment at L1613 recording why -- "Address Line 2 is optional" -- so the token
 *       exists in the source and is simply never moved. Taking it is what keeps this name in the same
 *       vocabulary as the twenty-seven the catalog carries.
 * WHY : Alternatives Considered: adding a twenty-eighth member to `ACCOUNT_UPDATE_FIELD_LABELS` in
 *       `ui/src/messages/messages.ts`. Rejected because that group transcribes the tokens the program
 *       MOVES, and a member for one it deliberately does not move would misdescribe the catalog. Also
 *       considered: painting a visible `Address Line 2` label. Rejected as a visual divergence -- the
 *       mapset paints none, and adding one would put a label on this screen that the terminal did not.
 */

/** Accessible name for the unlabelled second address line, from the reference's own token. */
export const ADDRESS_LINE_2_NAME = 'Address Line 2';

/**
 * The seven declared conditions of the reference's change-action character.
 *
 * Assumptions: exactly these seven and no others, transcribed from the `88` levels the reference
 * declares on `10 ACUP-CHANGE-ACTION PIC X(1)` in `app/cbl/COACTUPC.cbl`. `DETAILS_NOT_FETCHED` is
 * the low-values-or-spaces condition and is spelled as an explicit member here rather than as an
 * absent value, so the union is exhaustive and a `switch` over it can be checked. Two further
 * conditions the reference declares -- `ACUP-CHANGES-MADE` over `'E'`, `'N'`, `'C'`, `'L'`, `'F'` and
 * `ACUP-CHANGES-FAILED` over `'L'` and `'F'` -- are GROUPS over these members rather than members,
 * so they are expressed as the predicates {@link isChangesMade} and {@link isChangesFailed}.
 */
export type ChangeAction =
  | 'DETAILS_NOT_FETCHED'
  | 'SHOW_DETAILS'
  | 'CHANGES_NOT_OK'
  | 'CHANGES_OK_NOT_CONFIRMED'
  | 'CHANGES_OKAYED_AND_DONE'
  | 'CHANGES_OKAYED_LOCK_ERROR'
  | 'CHANGES_OKAYED_BUT_FAILED';

/**
 * The two attention identifiers whose arms perform this screen's record read.
 *
 * ⚠️ Purpose: name the OWNER of an outstanding read so a busy affordance can be attached to the one
 * control responsible for it. `app/cbl/COACTUPC.cbl` reaches `9000-READ-ACCT` from two arms -- the
 * fetch turn and the PF12 cancel turn at L2572 to L2580 -- and both land in one reader here, so
 * without this the screen could only report that SOMETHING was outstanding.
 *
 * Assumptions: the members are AID names rather than turn names, so they compare directly against the
 * keys of the handler map below and no translation table stands between the owner and the control it
 * describes.
 */
type ReadTurnOwner = 'ENTER' | 'PFK12';

/**
 * Every editable and displayed value the map carries, one member per named data field.
 *
 * Assumptions: forty-three members, which is the mapset's 54 named fields less the six shared
 * title-band fields, the two message lines and the three legend fields -- each of those eleven is
 * rendered by a shell component instead. Member NAMES are the wire names of
 * `AccountUpdateRequest` and `SensitiveAccountUpdateFields` rather than the mapset's eight-character
 * field names, so a `FieldError` the service returns binds to a control without a translation table
 * in between; the mapset name of each is cited on the member.
 *
 * Assumptions: every member is a `string`, including the five amounts and every identifier. The
 * reference holds each of these as characters and reinterprets it as a number only inside an
 * arithmetic statement -- `ACUP-OLD-CURR-BAL PIC X(12)` redefined `PIC S9(10)V99` at
 * `app/cbl/COACTUPC.cbl` L675 to L677 is the pattern, repeated for the credit limit and the cash
 * credit limit -- and `ui/src/api/types.ts` requires the same of the wire shape.
 */
export interface AccountUpdateFormValues {
  /** `ACCTSID`, mapset width 11, for `ACCT-ID PIC 9(11)`. */
  readonly accountId: string;
  /** `ACSTTUS`, width 1, for `ACCT-ACTIVE-STATUS PIC X(01)`. */
  readonly activeStatus: string;
  /** `OPNYEAR`, width 4, first part of `ACCT-OPEN-DATE PIC X(10)`. */
  readonly openDateYear: string;
  /** `OPNMON`, width 2, second part of `ACCT-OPEN-DATE`. */
  readonly openDateMonth: string;
  /** `OPNDAY`, width 2, third part of `ACCT-OPEN-DATE`. */
  readonly openDateDay: string;
  /** `ACRDLIM`, width 15, for `ACCT-CREDIT-LIMIT PIC S9(10)V99`. */
  readonly creditLimit: string;
  /** `EXPYEAR`, width 4, first part of the misspelled `ACCT-EXPIRAION-DATE PIC X(10)`. */
  readonly expirationDateYear: string;
  /** `EXPMON`, width 2, second part of that date. */
  readonly expirationDateMonth: string;
  /** `EXPDAY`, width 2, third part of that date. */
  readonly expirationDateDay: string;
  /** `ACSHLIM`, width 15, for `ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99`. */
  readonly cashCreditLimit: string;
  /** `RISYEAR`, width 4, first part of `ACCT-REISSUE-DATE PIC X(10)`. */
  readonly reissueDateYear: string;
  /** `RISMON`, width 2, second part of `ACCT-REISSUE-DATE`. */
  readonly reissueDateMonth: string;
  /** `RISDAY`, width 2, third part of `ACCT-REISSUE-DATE`. */
  readonly reissueDateDay: string;
  /** `ACURBAL`, width 15, for `ACCT-CURR-BAL PIC S9(10)V99`. */
  readonly currentBalance: string;
  /** `ACRCYCR`, width 15, for `ACCT-CURR-CYC-CREDIT PIC S9(10)V99`. */
  readonly currentCycleCredit: string;
  /** `AADDGRP`, width 10, for `ACCT-GROUP-ID PIC X(10)`. */
  readonly groupId: string;
  /** `ACRCYDB`, width 15, for `ACCT-CURR-CYC-DEBIT PIC S9(10)V99`. */
  readonly currentCycleDebit: string;
  /** `ACSTNUM`, width 9, for `CUST-ID PIC 9(09)`; protected by the reference in every state. */
  readonly customerId: string;
  /** `ACTSSN1`, width 3, first group of `CUST-SSN PIC 9(09)`. */
  readonly ssnPart1: string;
  /** `ACTSSN2`, width 2, second group of `CUST-SSN`. */
  readonly ssnPart2: string;
  /** `ACTSSN3`, width 4, third group of `CUST-SSN`. */
  readonly ssnPart3: string;
  /** `DOBYEAR`, width 4, first part of `CUST-DOB-YYYY-MM-DD PIC X(10)`. */
  readonly dateOfBirthYear: string;
  /** `DOBMON`, width 2, second part of the date of birth. */
  readonly dateOfBirthMonth: string;
  /** `DOBDAY`, width 2, third part of the date of birth. */
  readonly dateOfBirthDay: string;
  /** `ACSTFCO`, width 3, for `CUST-FICO-CREDIT-SCORE PIC 9(03)`. */
  readonly ficoCreditScore: string;
  /** `ACSFNAM`, width 25, for `CUST-FIRST-NAME PIC X(25)`. */
  readonly firstName: string;
  /** `ACSMNAM`, width 25, for `CUST-MIDDLE-NAME PIC X(25)`; optional in the reference's edits. */
  readonly middleName: string;
  /** `ACSLNAM`, width 25, for `CUST-LAST-NAME PIC X(25)`. */
  readonly lastName: string;
  /** `ACSADL1`, width 50, for `CUST-ADDR-LINE-1 PIC X(50)`. */
  readonly addressLine1: string;
  /** `ACSSTTE`, width 2, for `CUST-ADDR-STATE-CD PIC X(02)`. */
  readonly stateCode: string;
  /** `ACSADL2`, width 50, for `CUST-ADDR-LINE-2 PIC X(50)`; unvalidated, see the note below. */
  readonly addressLine2: string;
  /** `ACSZIPC`, mapset width 5 against the wider stored `CUST-ADDR-ZIP PIC X(10)`. */
  readonly zipCode: string;
  /** `ACSCITY`, width 50, for `CUST-ADDR-LINE-3 PIC X(50)`, which the map names City. */
  readonly city: string;
  /** `ACSCTRY`, width 3, for `CUST-ADDR-COUNTRY-CD PIC X(03)`; protected by the reference. */
  readonly countryCode: string;
  /** `ACSPH1A`, width 3, area code of `CUST-PHONE-NUM-1 PIC X(15)`. */
  readonly phone1AreaCode: string;
  /** `ACSPH1B`, width 3, prefix of the first telephone number. */
  readonly phone1Prefix: string;
  /** `ACSPH1C`, width 4, line number of the first telephone number. */
  readonly phone1LineNumber: string;
  /** `ACSGOVT`, width 20, for `CUST-GOVT-ISSUED-ID PIC X(20)`; read back masked. */
  readonly governmentIssuedId: string;
  /** `ACSPH2A`, width 3, area code of `CUST-PHONE-NUM-2 PIC X(15)`. */
  readonly phone2AreaCode: string;
  /** `ACSPH2B`, width 3, prefix of the second telephone number. */
  readonly phone2Prefix: string;
  /** `ACSPH2C`, width 4, line number of the second telephone number. */
  readonly phone2LineNumber: string;
  /** `ACSEFTC`, width 10, for `CUST-EFT-ACCOUNT-ID PIC X(10)`. */
  readonly eftAccountId: string;
  /** `ACSPFLG`, width 1, for `CUST-PRI-CARD-HOLDER-IND PIC X(01)`. */
  readonly primaryCardHolderIndicator: string;
}

/** Name of one member of {@link AccountUpdateFormValues}, used to key the tables below. */
export type AccountUpdateFieldName = keyof AccountUpdateFormValues;

/*
 * WHY : Assumptions: every entry below is the mapset's `LENGTH=` operand and NOT the copybook
 *       `PICTURE` width, and the two differ in six places. `ACSZIPC` is `LENGTH=5` against the stored
 *       `CUST-ADDR-ZIP PIC X(10)`, and the five amounts are `LENGTH=15` each against
 *       `PIC S9(10)V99`. The 3270 field length is what bounded what an operator could type, so it is
 *       the binding constraint on the control; the reference confirms the narrower reading for the
 *       postal code by editing exactly five numeric characters through `1245-EDIT-NUM-REQD` with
 *       `MOVE 5 TO WS-EDIT-ALPHANUM-LENGTH`, and it accepts fifteen characters of free text for each
 *       amount precisely so a malformed entry can be received and reported rather than truncated
 *       into a different number.
 */

/** Mapset `LENGTH=` operand of every data field, which is each control's maximum entry length. */
export const ACCOUNT_UPDATE_FIELD_WIDTHS = {
  accountId: 11,
  activeStatus: 1,
  openDateYear: 4,
  openDateMonth: 2,
  openDateDay: 2,
  creditLimit: 15,
  expirationDateYear: 4,
  expirationDateMonth: 2,
  expirationDateDay: 2,
  cashCreditLimit: 15,
  reissueDateYear: 4,
  reissueDateMonth: 2,
  reissueDateDay: 2,
  currentBalance: 15,
  currentCycleCredit: 15,
  groupId: 10,
  currentCycleDebit: 15,
  customerId: 9,
  ssnPart1: 3,
  ssnPart2: 2,
  ssnPart3: 4,
  dateOfBirthYear: 4,
  dateOfBirthMonth: 2,
  dateOfBirthDay: 2,
  ficoCreditScore: 3,
  firstName: 25,
  middleName: 25,
  lastName: 25,
  addressLine1: 50,
  stateCode: 2,
  addressLine2: 50,
  zipCode: 5,
  city: 50,
  countryCode: 3,
  phone1AreaCode: 3,
  phone1Prefix: 3,
  phone1LineNumber: 4,
  governmentIssuedId: 20,
  phone2AreaCode: 3,
  phone2Prefix: 3,
  phone2LineNumber: 4,
  eftAccountId: 10,
  primaryCardHolderIndicator: 1,
} as const satisfies Record<AccountUpdateFieldName, number>;

/**
 * Decides whether a name the service used identifies a control this screen paints.
 *
 * Purpose: the service may name any field in its own request model, and this screen paints one
 * mapset's worth of them. A name it does not paint has no control to mark and no words an operator on
 * this screen can act on.
 *
 * Assumptions: membership is read from {@link ACCOUNT_UPDATE_FIELD_WIDTHS}, whose `satisfies` clause
 * makes its keys exactly the form's fields -- so a field added to the form is a field this test knows
 * about, with nothing to keep in step by hand. A separate list was the alternative and is precisely
 * the thing that goes stale.
 * @param {string} name - The field name as the response carried it.
 * @returns {boolean} `true` when this screen paints a control for that name.
 */
export function isAccountUpdateField(name: string): name is AccountUpdateFieldName {
  return Object.hasOwn(ACCOUNT_UPDATE_FIELD_WIDTHS, name);
}

/**
 * Chooses the sentence a refusal carrying field errors should publish on the band.
 *
 * Purpose: publish a refusal the operator can act on, and never one about a field that is not on the
 * screen in front of them.
 *
 * ⚠️ Refactoring Rationale: this used to be `const [first] = fieldErrors` at the call site, taking the
 * array's first entry whatever it named. A browser review drove a refusal whose first entry named a
 * field belonging to a different screen and measured the result: that sentence became this screen's
 * only headline, naming a control the screen does not paint, with the on-screen field's own refusal
 * present but unpromoted. The operator was shown a refusal they had no way to satisfy.
 *
 * Assumptions: the reference cannot reach that state, which is why filtering is a transcription rather
 * than a policy. `1200-EDIT-MAP-INPUTS` composes `WS-RETURN-MSG` from its own per-field edits over the
 * fields this mapset paints -- `app/cbl/COACTUPC.cbl` L484 onward -- so the sentence on its message
 * line always names a field on its screen.
 *
 * Assumptions: entries naming fields off this screen stay in the array and continue to reach
 * {@link indexFieldErrors}, where they match no control and mark nothing. Only the choice of HEADLINE
 * is filtered, because dropping them would have this screen decide what the service may report and
 * would lose a diagnostic that a later screen or a log may want.
 *
 * Alternatives Considered: publishing the problem-level message whenever any entry names an unpainted
 * field. Rejected because it discards a perfectly actionable on-screen refusal -- the review's own case
 * had one -- in favour of a generic sentence, which trades a wrong headline for an uninformative one.
 * @param {readonly FieldError[]} fieldErrors - The field errors exactly as the response carried them.
 * @param {string | null} problemMessage - The problem-level sentence, used when no entry is on screen.
 * @returns {string | null} The sentence to publish, or `null` when there is none to publish.
 */
export function headlineForFieldErrors(
  fieldErrors: readonly FieldError[],
  problemMessage: string | null,
): string | null {
  const onScreen = fieldErrors.find(
    /**
     * Tests one entry for membership of this screen.
     * @param {FieldError} candidate - The entry under test.
     * @returns {boolean} `true` when this screen paints the field it names.
     */
    (candidate: FieldError): boolean => isAccountUpdateField(candidate.field),
  );

  return onScreen === undefined ? problemMessage : onScreen.message;
}

/*
 * WHY : ⚠️ Refactoring Rationale: the grid's HORIZONTAL gutter is resolved per screen name and is zero
 *       below the medium breakpoint, where it used to be one number at every width. The design system
 *       implements a gutter by giving the `Row` a NEGATIVE inline margin of half the gutter and each
 *       `Col` a matching positive padding -- `node_modules/antd/lib/grid/row.js` sets
 *       `rowStyle.marginInline` to `gutter / -2` -- so a `Row` laid out in a container exactly as wide
 *       as the viewport hangs half a gutter past each edge. A responsive review measured exactly that
 *       on this screen and nowhere else in the application: `documentElement.scrollWidth` 385 against
 *       an `innerWidth` of 375, 586 against 576, 778 against 768, and zero overflow from 992 upward.
 *       One ten-pixel pan then clipped the first character off every label -- `Update Account` read as
 *       `pdate Account` and `Account Number :` as `ccount Number :` -- which is a functional loss and
 *       not a cosmetic one, because the operator cannot see which field they are in.
 * WHY : Assumptions: zero is the right value below that breakpoint rather than a smaller number,
 *       because every `Col` in both grids is `xs={24}` -- a full row of its own -- so there is no pair
 *       of side-by-side columns for a horizontal gutter to separate. It buys nothing at those widths
 *       and its only effect is the escape.
 * WHY : Assumptions: the VERTICAL gutter keeps the same spacing step at every width. Stacked
 *       single-column fields need row separation more at a phone width than at a desktop one, and a
 *       block-axis negative margin cannot be exposed by a horizontal scroll.
 * WHY : Alternatives Considered: `overflow-x: hidden` on the content wrapper, which the review itself
 *       suggested. Rejected on two counts. It belongs to `ui/src/layout/AppShell.tsx` rather than to a
 *       screen, so a screen cannot apply it without reaching outside its own tree; and it HIDES the
 *       escape rather than removing it, leaving the content laid out ten pixels wider than the viewport
 *       with the overhanging column unreachable instead of merely invisible.
 * WHY : Alternatives Considered: dropping the gutter entirely and spacing the columns with `Flex` gap
 *       inside each `Col`. Rejected because the two-up arrangement from the medium breakpoint upward is
 *       what the mapset's paired left/right fields become, and `Row`/`Col` is the design system's own
 *       expression of it -- replacing it would hand-roll a grid the system already provides.
 * WHY : Assumptions: this is the screen's own half of a two-layer remedy, and it is not made redundant
 *       by the frame's. `AppShell` insets every zone by `paddingLG` and clamps the content column,
 *       which absorbs a half-gutter that escapes; this removes the escape where it has nothing to buy.
 *       Each holds on its own, so neither depends on the other's value staying where it is.
 * WHY : Assumptions: only the three narrow screen names are stated, for the reason
 *       `ui/src/layout/recordLayout.ts` gives for stating three: the design system resolves a
 *       responsive gutter by walking its screen names widest-first and taking the first that both
 *       matches and is present, so the value given at `md` also governs `lg`, `xl`, `xxl` and `xxxl`.
 *       Naming those four as well would be four more places for one decision to be edited.
 */

/**
 * Resolves the two-axis gutter both field grids on this screen are laid out with.
 *
 * Purpose: keep the horizontal gutter -- and therefore the negative inline margin the design system
 * derives from it -- off the widths at which every column is full-width, so the grid cannot lay out
 * wider than the viewport it is given.
 *
 * Assumptions: the caller passes the RESOLVED spacing step as a number rather than the token's
 * `var(--...)` reference, because the gutter is a component prop the design system divides by two
 * itself and it accepts a number. Reading the resolved member is safe for this one value precisely
 * because it is never written into a style attribute, so nothing about today's spacing scale is baked
 * into an element.
 * @param {number} sectionGap - The spacing step from the token bridge's medium section gap.
 * @returns {RowProps['gutter']} The horizontal gutter per screen name, paired with the vertical gutter.
 */
export function accountUpdateGridGutter(sectionGap: number): RowProps['gutter'] {
  return [{ xs: 0, sm: 0, md: sectionGap }, sectionGap];
}

/*
 * WHY : ⚠️ Refactoring Rationale: entry is now measured against the field's DECLARED width in the two
 *       units the record is stated in, where the control's `maxLength` was the only limit. A
 *       responsive review reported an entry of `12345` into a three-wide telephone part arriving
 *       truncated with nothing said about it, and `maxLength` is why: it stops the keystroke, which is
 *       the right interaction, but it counts UTF-16 code units -- a unit that is neither of the units
 *       a `PIC X(n)` field is declared in. It therefore lets through a value the record cannot hold
 *       and refuses one it could, and in both directions the operator is told nothing. This measures
 *       code points AND bytes, which is the check `fitsDeclaredWidth` exists for and which its own
 *       contract says to run alongside `maxLength` rather than instead of it.
 * WHY : ⚠️ Assumptions: the two measures differ, and the byte one usually binds. `firstName` is
 *       `PIC X(25)` at `app/cpy-bms/COACTUP.CPY`, which is twenty-five BYTES on the record, so
 *       twenty-five accented letters are twenty-five code points and fifty bytes -- a value
 *       `maxLength` admits and the field cannot store. Conversely one astral character is a single
 *       code point and two UTF-16 units, so `maxLength` counts it twice and refuses a character the
 *       field has room for. Clamping on the record's own units removes both errors.
 * WHY : ⚠️ Trade-offs: an over-long entry is CLAMPED rather than refused with a sentence, and the
 *       reason is that no sentence exists to say it in. `COACTUPC` declares no over-length refusal --
 *       a 3270 field of length n physically cannot hold n+1 characters, so the condition never arises
 *       for it to report -- and the catalog is transcribed from the program, so stating one here would
 *       mean authoring operator prose the reference never wrote. Clamping is the terminal's own
 *       behaviour: the keyboard simply stops accepting into a full field. What is added on top is the
 *       control being sized to its declared width, so the boundary the clamp enforces is visible
 *       before it is reached rather than only afterwards.
 * WHY : Alternatives Considered: marking the control and publishing a borrowed sentence from another
 *       program's block -- `COTRN02C` has a family of `... can NOT be empty...` refusals and a browser
 *       could be made to speak one. Rejected outright: every catalogued sentence records the program
 *       and line it was transcribed from, and rendering one program's words on another program's
 *       screen breaks that correspondence for the sake of filling a gap the reference does not have.
 *       The gap is reported instead.
 */

/**
 * Clamps one entry to a declared field width, measured as the record measures it.
 *
 * Purpose: keep the value the operator sees identical to the value the field can store, so nothing
 * can be validated as acceptable and then be unstorable.
 *
 * Assumptions: the entry is normalised to Normalization Form C FIRST and the clamp then walks whole
 * CODE POINTS, never UTF-16 units, so a surrogate pair is kept or dropped as one character and can
 * never be cut in half into a lone surrogate -- which would be a value no byte measure could make
 * sense of.
 *
 * Trade-offs: the fit is re-tested per candidate length rather than computed from a byte count in one
 * step. That is a loop over at most the entry's own length, on a keystroke, and it buys the property
 * that this function and the validator agree by CONSTRUCTION because they consult the same predicate
 * -- where a byte arithmetic of its own here would be a second implementation of the same rule.
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

/** Matches a run of decimal digits, or nothing, used to reject a non-digit in a numeric-only part. */
const NON_DIGITS = /[^0-9]/gu;

/** The all-zeroes filter the reference refuses, as the eleven characters it is. */
const ZEROED_ACCOUNT_ID = '0'.repeat(ACCOUNT_UPDATE_FIELD_WIDTHS.accountId);

/**
 * Strips every character that is not a decimal digit.
 *
 * Assumptions: applied to the numeric-only parts -- the twelve date parts, the three identifier
 * groups, the six telephone groups, the credit score, both identifiers and the postal code -- because
 * the mapset declares those fields right-justified numeric entry and the reference edits each with a
 * numeric test. It is NOT applied to the five amounts, which must be able to receive a malformed
 * entry so the service can report it in the reference's own words.
 * @param {string} entry - The raw value as the control reports it.
 * @returns {string} The same value with every non-digit removed.
 */
export function digitsOnly(entry: string): string {
  return entry.replace(NON_DIGITS, '');
}

/*
 * WHY : Assumptions: the reference protects THREE data fields in every state and this set names them.
 *       `3310-PROTECT-ALL-ATTRS` protects all forty-three, and `3320-UNPROTECT-FEW-ATTRS` then
 *       unprotects forty of them -- leaving the account identifier, the customer identifier and the
 *       country code, the last with the program's own explanation beside it: "Since most of the edits
 *       are USA specific protected country". The account identifier is unprotected separately, by the
 *       fetch arm of `3300-SETUP-SCREEN-ATTRS`, which is why it appears here and is still typable
 *       before a record has been read.
 */

/** Fields `3320-UNPROTECT-FEW-ATTRS` leaves protected, so the edit turn cannot reach them. */
const NEVER_EDITABLE_FIELDS: readonly AccountUpdateFieldName[] = [
  'accountId',
  'customerId',
  'countryCode',
];

/*
 * WHY : Assumptions: the order is the reference's cursor table at `app/cbl/COACTUPC.cbl` L3009 to
 *       L3167, walked top to bottom, and it is a SCREEN order rather than the validation order. Its
 *       one visible consequence is which control receives focus when several fail at once, so it
 *       cannot be replaced by the order the edits run in: the state code is validated between the
 *       last name and the postal code but is painted to the right of address line 1, and the cursor
 *       table follows the paint. The reference's own comment at L3121 says so -- "State (appears next
 *       to Line 2 on screen before city)".
 * WHY : ⚠️ Refactoring Rationale: `customerId` is in this list and it is the ONE entry the reference's
 *       cursor table does not have. That table runs from the current cycle debit at L3074 to L3076
 *       straight to the first identifier part at L3077 to L3079 with no arm between them, because the
 *       reference has no customer-key refusal to position on -- nothing there edits the value. THIS
 *       system does: `editMapInputs` records `editCustomerKeyNamesRow` under `customerId` at
 *       `services/account-service/src/main/java/com/carddemo/account/service/AccountUpdateService.java`
 *       L1039 and returns the key verdict before any other edit runs, so a submission naming the wrong
 *       customer is refused with that one entry and nothing else. Omitted from this list, such a
 *       refusal marked its control and moved the cursor NOWHERE, leaving the operator a highlighted
 *       field the keyboard never reached. The position is not invented either: the symbolic map
 *       declares `ACSTNUMI` between `ACRCYDBI` and `ACTSSN1I` at `app/cpy-bms/COACTUP.CPY` L157 to
 *       L162, so the paint order the rest of this list follows puts it exactly here.
 * WHY : Assumptions: reaching it also requires the control to be focusable, which is why a protected
 *       field renders `readOnly` rather than `disabled` -- recorded at the control itself. Two of the
 *       three never-editable fields were already in this list, so the gap was this one entry rather
 *       than a policy of leaving protected fields out.
 */

/**
 * The five amounts the reference renders through its `+ZZZ,ZZZ,ZZZ.99` edit mask.
 *
 * Assumptions: named as a set so the mask is applied and reversed at ONE place each, driven by
 * membership rather than by five call sites that could disagree. Every member is an editable control --
 * all five carry `ATTRB=(FSET,UNPROT)` at `app/bms/COACTUP.bms` L132, L170, L208, L219 and L240 -- which
 * is why the reversal exists at all.
 */
const MASKED_AMOUNT_FIELDS: ReadonlySet<AccountUpdateFieldName> = new Set([
  'creditLimit',
  'cashCreditLimit',
  'currentBalance',
  'currentCycleCredit',
  'currentCycleDebit',
]);

/**
 * Re-applies the edit mask to the amounts a verdict accepted, leaving the refused ones as typed.
 *
 * Assumptions: this is `3203-SHOW-UPDATED-VALUES`, whose arms are per amount and per validation flag.
 * For each it moves the numeric value through `WS-EDIT-CURRENCY-9-2-F` when the field's flag says valid
 * -- `IF FLG-CRED-LIMIT-ISVALID ... MOVE WS-EDIT-CURRENCY-9-2-F TO ACRDLIMO` at `app/cbl/COACTUPC.cbl`
 * L2874 onward -- and otherwise moves the character form the operator submitted straight back, so a
 * rejected entry is echoed verbatim rather than reformatted.
 *
 * Assumptions: an accepted value is stripped BEFORE being masked, so the function is idempotent. It runs
 * on values that may already carry the mask from a previous turn as well as on freshly typed ones, and
 * masking an already-masked value without stripping first would read its blanks as part of the number.
 *
 * ⚠️ Refactoring Rationale: a refused amount is now STRIPPED rather than left as the form holds it, and
 * the two differ whenever the refusal follows an accepted turn. The reference's else-arm moves
 * `ACUP-NEW-CREDIT-LIMIT-X` -- the CHARACTER form received from the map -- straight back into the output
 * field, so what an operator sees after a refusal is what they submitted. This screen's stored value may
 * already carry the mask a previous accepted turn applied, so leaving it alone echoed
 * `+      6,000.00` at an operator whose submission was `6000.00`, decorating the very value it was
 * reporting as wrong. Stripping is idempotent on a freshly typed entry, so a value the operator has just
 * written is untouched.
 *
 * Trade-offs: a refused amount keeps the operator's own characters even where those characters are
 * merely differently decorated -- `1234.5` stays `1234.5` rather than becoming `1,234.50`. That is the
 * reference's behaviour and it is the useful one: reformatting a value while reporting it as wrong would
 * change what the operator is being asked to look at.
 * @param {AccountUpdateFormValues} values - The form as the operator left it.
 * @param {ReadonlyMap<string, FieldError>} refusals - The verdict's entries, keyed by field.
 * @returns {AccountUpdateFormValues} The form with every accepted amount re-masked.
 */
export function remaskAcceptedAmounts(
  values: AccountUpdateFormValues,
  refusals: ReadonlyMap<string, FieldError>,
): AccountUpdateFormValues {
  const redisplayed: Record<string, string> = { ...values };
  for (const field of MASKED_AMOUNT_FIELDS) {
    const submitted = stripMoneyEditMask(values[field]);
    redisplayed[field] = refusals.has(field) ? submitted : applyMoneyEditMask(submitted);
  }
  return redisplayed as unknown as AccountUpdateFormValues;
}

/** Screen order the reference places its cursor in, used to focus the first field in error. */
const CURSOR_ORDER: readonly AccountUpdateFieldName[] = [
  'accountId',
  'activeStatus',
  'openDateYear',
  'openDateMonth',
  'openDateDay',
  'creditLimit',
  'expirationDateYear',
  'expirationDateMonth',
  'expirationDateDay',
  'cashCreditLimit',
  'reissueDateYear',
  'reissueDateMonth',
  'reissueDateDay',
  'currentBalance',
  'currentCycleCredit',
  'currentCycleDebit',
  'customerId',
  'ssnPart1',
  'ssnPart2',
  'ssnPart3',
  'dateOfBirthYear',
  'dateOfBirthMonth',
  'dateOfBirthDay',
  'ficoCreditScore',
  'firstName',
  'middleName',
  'lastName',
  'addressLine1',
  'stateCode',
  'zipCode',
  'city',
  'countryCode',
  'phone1AreaCode',
  'phone1Prefix',
  'phone1LineNumber',
  'phone2AreaCode',
  'phone2Prefix',
  'phone2LineNumber',
  'eftAccountId',
  'primaryCardHolderIndicator',
];

/*
 * WHY : ⚠️ Refactoring Rationale: FOUR helpers of the withdrawn local edit chain stood here, and one
 *       note now records all four rather than four notes recording them one at a time -- two of which
 *       were byte-identical, so the file stated the same withdrawal twice and a reader could not tell
 *       whether two different things had been withdrawn or one thing had been described twice. The four
 *       were: a constant standing in for 'both lookups unresolved'; a wrapper that turned a lookup
 *       promise into a three-valued answer; the status code those two read, which described a reference
 *       lookup answering 'no such row'; and a predicate deciding whether a lookup's refusal meant 'no
 *       such row' rather than 'the lookup failed'. None had any other caller.
 *       Assumptions: the note is kept at all, rather than deleted with the code, because the
 *       DISTINCTION the four drew still matters -- it is drawn by the service operation that replaced
 *       them, whose preference is argued at the withdrawn imports above -- so a reader looking for the
 *       chain finds where it went instead of finding nothing.
 */

const MESSAGES = STATUS_MESSAGES.COACTUPC;

/*
 * WHY : Assumptions: this module imports the 27 NAME TOKENS and neither the 22 refusal SUFFIXES nor
 *       the composer that joins them, and the asymmetry follows from where each is used. The reference
 *       composes most of its refusals at run time as the trimmed name token followed by a suffix --
 *       `1250-EDIT-SIGNED-9V2` builds `<name> must be supplied.` and `<name> is not valid` that way,
 *       and eight further edit paragraphs do the same -- and in the migrated system it is the SERVICE
 *       that runs those edits and composes those sentences, so this screen renders the composed text
 *       the response body carries and never assembles one. The tokens are still needed here because
 *       they name a split GROUP for a screen reader, which is a labelling job rather than a message
 *       one.
 */

/** The 27 name tokens the reference moves into `WS-EDIT-VARIABLE-NAME` to compose a refusal. */
const NAME_TOKENS = ACCOUNT_UPDATE_FIELD_LABELS;

/*
 * WHY : ⚠️ Refactoring Rationale: this admitted ONE to eleven digits and now admits exactly eleven,
 *       which is what the comment beside it already claimed and the code did not. The reference's own
 *       test is `IF CC-ACCT-ID IS NOT NUMERIC OR CC-ACCT-ID-N EQUAL ZEROS` at `app/cbl/COACTUPC.cbl`
 *       L1802 and L1803, applied to `CC-ACCT-ID PIC X(11)`: a shorter value reaches that field padded
 *       with spaces, so `NOT NUMERIC` refuses it, and a terminal cannot send a short value anyway
 *       because `app/bms/COACTUP.bms` declares the map field `LENGTH=11`. A browser control CAN hold
 *       fewer, so the width has to be asserted here instead of inherited from the transport.
 * WHY : Assumptions: the sibling screen states the same rule the same way -- `ACCOUNT_ID_PATTERN` in
 *       `ui/src/screens/accountView/index.tsx` is `/^[0-9]{11}$/u` -- and the two agreeing is the point:
 *       one screen accepting a filter the other refuses would send the same operator to two different
 *       answers for one value.
 */

/**
 * Matches an account identifier the reference's own key edit accepts: exactly eleven digits.
 *
 * Refactoring Rationale: the quantifier was `{1,11}` while this very comment said "eleven", so the
 * screen accepted a partial key its own description ruled out and the service then refused. The
 * authority is `1300-EDIT-ACCOUNT`, whose refusal is composed from the two literals that read
 * `Account number must be an 11 digit non-zero number` -- an eleven-digit test, not an up-to-eleven
 * one. Accepting fewer digits locally did not make them acceptable; it moved the refusal one round
 * trip later and reported it as a server rejection rather than as the field problem it is.
 */
const ACCEPTABLE_ACCOUNT_ID = /^[0-9]{11}$/u;

/** First character position of the month inside a ten-character ISO date. */
const ISO_MONTH_START = 5;

/** Position after the month inside a ten-character ISO date. */
const ISO_MONTH_END = 7;

/** First character position of the day inside a ten-character ISO date. */
const ISO_DAY_START = 8;

/** Width of the four-digit year at the head of a ten-character ISO date. */
const ISO_YEAR_WIDTH = 4;

/** Position of the area code inside the stored `(999)999-9999` telephone rendering. */
const PHONE_AREA_START = 1;

/** Position after the area code inside that rendering, being the closing bracket. */
const PHONE_AREA_END = 4;

/** Position of the prefix inside that rendering. */
const PHONE_PREFIX_START = 5;

/** Position after the prefix inside that rendering, being the hyphen. */
const PHONE_PREFIX_END = 8;

/** Position of the line number inside that rendering. */
const PHONE_LINE_START = 9;

/** Position after the line number, being the end of the thirteen-character rendering. */
const PHONE_LINE_END = 13;

/**
 * Reports whether an action is one of the five the reference groups as changes having been made.
 *
 * Assumptions: five members and not two. `88 ACUP-CHANGES-MADE` is declared over `'E'`, `'N'`, `'C'`,
 * `'L'` and `'F'` in `app/cbl/COACTUPC.cbl`, so it includes the committed action and both failure
 * actions as well as the two mid-edit ones. The distinction is observable: `F12=Cancel` is revealed
 * by `3390-SETUP-INFOMSG-ATTRS` for this group less the committed action, so the legend appears on
 * the two failure turns too, and a narrower reading would hide it there.
 * @param {ChangeAction} action - The screen's current change action.
 * @returns {boolean} `true` for the five actions that group holds.
 */
export function isChangesMade(action: ChangeAction): boolean {
  return (
    action === 'CHANGES_NOT_OK' ||
    action === 'CHANGES_OK_NOT_CONFIRMED' ||
    action === 'CHANGES_OKAYED_AND_DONE' ||
    action === 'CHANGES_OKAYED_LOCK_ERROR' ||
    action === 'CHANGES_OKAYED_BUT_FAILED'
  );
}

/**
 * Reports whether an action is one of the two the reference groups as a failed write.
 *
 * Assumptions: `88 ACUP-CHANGES-FAILED` is declared over `'L'` and `'F'` only -- a lock that could
 * not be taken and a rewrite that was refused. The reference's own main branch resets the screen to
 * its opening state on the turn after either, so the group is what selects that reset.
 * @param {ChangeAction} action - The screen's current change action.
 * @returns {boolean} `true` for the lock-error and write-failed actions.
 */
export function isChangesFailed(action: ChangeAction): boolean {
  return action === 'CHANGES_OKAYED_LOCK_ERROR' || action === 'CHANGES_OKAYED_BUT_FAILED';
}

/**
 * Reports whether one field can be typed into in the given action.
 *
 * Assumptions: the four arms are `3300-SETUP-SCREEN-ATTRS` at `app/cbl/COACTUPC.cbl` L2986 to L3006,
 * transcribed. The confirmation and committed actions reach that paragraph's `CONTINUE` arm, which
 * leaves every field protected by `3310-PROTECT-ALL-ATTRS` -- so the form is genuinely READ-ONLY
 * while a save awaits confirmation, and that is behaviour rather than decoration: an operator
 * confirming a validated set cannot alter it between the validation and the write, which is what
 * makes the confirmed values the validated values.
 * @param {ChangeAction} action - The screen's current change action.
 * @param {AccountUpdateFieldName} field - The field being rendered.
 * @returns {boolean} `true` when the control accepts entry in this action.
 */
export function isFieldEditable(action: ChangeAction, field: AccountUpdateFieldName): boolean {
  if (action === 'CHANGES_OK_NOT_CONFIRMED' || action === 'CHANGES_OKAYED_AND_DONE') {
    return false;
  }
  if (action === 'SHOW_DETAILS' || action === 'CHANGES_NOT_OK') {
    return !NEVER_EDITABLE_FIELDS.includes(field);
  }

  // Assumptions: the remaining three actions -- not fetched, and the two failure actions -- all
  //   reach an arm that unprotects the account identifier alone, the first arm explicitly and the
  //   two failure actions through `WHEN OTHER`, so one branch covers all three.
  return field === 'accountId';
}

/**
 * Selects the information line the reference paints for one action.
 *
 * Assumptions: the mapping is `3250-SETUP-INFOMSG` at `app/cbl/COACTUPC.cbl` L2955 to L2977, and the
 * six sentences it can select are the whole of `WS-INFO-MSG`'s declared value set. Both failure
 * actions select the same sentence, which is why this returns six values for seven actions.
 * @param {ChangeAction} action - The screen's current change action.
 * @returns {string} The information sentence, verbatim from the catalog.
 */
export function informationLineFor(action: ChangeAction): string {
  switch (action) {
    case 'DETAILS_NOT_FETCHED':
      return MESSAGES.PROMPT_FOR_SEARCH_KEYS.text;
    case 'SHOW_DETAILS':
    case 'CHANGES_NOT_OK':
      return MESSAGES.PROMPT_FOR_CHANGES.text;
    case 'CHANGES_OK_NOT_CONFIRMED':
      return MESSAGES.PROMPT_FOR_CONFIRMATION.text;
    case 'CHANGES_OKAYED_AND_DONE':
      return MESSAGES.CONFIRM_UPDATE_SUCCESS.text;
    case 'CHANGES_OKAYED_LOCK_ERROR':
    case 'CHANGES_OKAYED_BUT_FAILED':
      return MESSAGES.INFORM_FAILURE.text;
    default:
      return MESSAGES.PROMPT_FOR_SEARCH_KEYS.text;
  }
}

/*
 * WHY : ⚠️ Refactoring Rationale: a `digitsOnly` coercion USED to sit here, applied on entry to every
 *       field this module marks numeric -- the twelve date parts, the three identifier groups, the six
 *       telephone groups, the credit score, both identifiers and the postal code -- and it has been
 *       WITHDRAWN outright rather than narrowed. Its premise was that the mapset declares those fields
 *       numeric entry, and the mapset does not: `app/bms/COACTUP.bms` declares `ATTRB=...NUM` on ZERO
 *       of its 128 fields, so the terminal accepts any character into any of them and the program's own
 *       edit is what reports it.
 * WHY : Assumptions: the consequence of stripping was not cosmetic. It made whole branches of the
 *       reference unreachable and changed the sentence an operator reads: typing `A` into the credit
 *       score left the control EMPTY, so the refusal that arrived was the absence sentence rather than
 *       `Credit Score must be a non zero 3 digit number`, and the operator was told a field they had
 *       filled in was blank. Every numeric edit in
 *       `services/account-service/.../AccountUpdateService.java` keeps absence, non-numeric and zero on
 *       separate sentences precisely so those three cases stay distinguishable, and a client that
 *       silently erased the middle one collapsed two of them.
 * WHY : Alternatives Considered: keeping the coercion and reporting the discarded characters, and
 *       rejecting the keystroke instead of erasing it. Both were rejected for the same reason: they
 *       leave the browser deciding what a field may hold, which is the decision the reference gives to
 *       the edit that owns the wording. What remains is `inputMode="numeric"`, which is a keyboard
 *       HINT -- it asks a touch device for a numeric keypad and constrains nothing -- and `maxLength`,
 *       which is a genuine field-width constraint the mapset does declare.
 */

/**
 * Splits a ten-character ISO date into the three parts the mapset paints.
 *
 * Assumptions: split by POSITION rather than parsed as a date, because the contract constrains the
 * value to exactly `YYYY-MM-DD` and a parse would admit a locale or a time zone able to shift the
 * characters the service sent. A value of any other length yields three empty parts rather than
 * slices of the wrong thing.
 * @param {string | null} iso - The stored date as the response carries it, or `null`.
 * @returns {{ year: string; month: string; day: string }} The three parts, each empty when the value
 *   is absent or is not ten characters.
 */
export function splitIsoDate(iso: string | null): {
  readonly year: string;
  readonly month: string;
  readonly day: string;
} {
  const blank = { year: '', month: '', day: '' };
  if (iso === null || iso.length !== ISO_DAY_START + 2) {
    return blank;
  }

  return {
    year: iso.slice(0, ISO_YEAR_WIDTH),
    month: iso.slice(ISO_MONTH_START, ISO_MONTH_END),
    day: iso.slice(ISO_DAY_START),
  };
}

/**
 * Splits the stored telephone rendering into the three groups the mapset paints.
 *
 * Assumptions: the stored form is `(999)999-9999`, which the reference states in a comment above
 * `1260-EDIT-US-PHONE-NUM` -- "The database stores date in X(15) format (999)999-9999" -- and
 * assembles with a `STRING` statement at L4027 to L4041. Splitting by position reverses exactly that
 * assembly. A value of any other length yields three empty groups, which covers the nullable second
 * number and any stored value that never went through the assembly.
 * @param {string | null} stored - The telephone number as the response carries it, or `null`.
 * @returns {{ areaCode: string; prefix: string; lineNumber: string }} The three groups, each empty
 *   when the value is absent or is not the thirteen-character rendering.
 */
export function splitPhoneNumber(stored: string | null): {
  readonly areaCode: string;
  readonly prefix: string;
  readonly lineNumber: string;
} {
  const blank = { areaCode: '', prefix: '', lineNumber: '' };
  if (stored === null || stored.trim().length !== PHONE_LINE_END) {
    return blank;
  }
  const rendering = stored.trim();

  return {
    areaCode: rendering.slice(PHONE_AREA_START, PHONE_AREA_END),
    prefix: rendering.slice(PHONE_PREFIX_START, PHONE_PREFIX_END),
    lineNumber: rendering.slice(PHONE_LINE_START, PHONE_LINE_END),
  };
}

/**
 * Builds the empty form the screen opens on, before any record has been read.
 *
 * Assumptions: every member is the empty string rather than absent, because the controls are
 * controlled and a member holding `undefined` would make one of them uncontrolled for a render. The
 * reference reaches the same state by `INITIALIZE WS-THIS-PROGCOMMAREA` on its opening turn.
 * @returns {AccountUpdateFormValues} Every field empty.
 */
export function blankFormValues(): AccountUpdateFormValues {
  return {
    accountId: '',
    activeStatus: '',
    openDateYear: '',
    openDateMonth: '',
    openDateDay: '',
    creditLimit: '',
    expirationDateYear: '',
    expirationDateMonth: '',
    expirationDateDay: '',
    cashCreditLimit: '',
    reissueDateYear: '',
    reissueDateMonth: '',
    reissueDateDay: '',
    currentBalance: '',
    currentCycleCredit: '',
    groupId: '',
    currentCycleDebit: '',
    customerId: '',
    ssnPart1: '',
    ssnPart2: '',
    ssnPart3: '',
    dateOfBirthYear: '',
    dateOfBirthMonth: '',
    dateOfBirthDay: '',
    ficoCreditScore: '',
    firstName: '',
    middleName: '',
    lastName: '',
    addressLine1: '',
    stateCode: '',
    addressLine2: '',
    zipCode: '',
    city: '',
    countryCode: '',
    phone1AreaCode: '',
    phone1Prefix: '',
    phone1LineNumber: '',
    governmentIssuedId: '',
    phone2AreaCode: '',
    phone2Prefix: '',
    phone2LineNumber: '',
    eftAccountId: '',
    primaryCardHolderIndicator: '',
  };
}

/*
 * WHY : Assumptions: the four protected identifier members are seeded EMPTY, and the masked value the
 *       read returned is rendered read-only BESIDE the control by {@link renderStoredState} rather than
 *       inside it. The reference could seed them because a terminal was shown the whole value -- `MOVE
 *       ACUP-NEW-CUST-GOVT-ISSUED-ID TO ACSGOVTO` at `app/cbl/COACTUPC.cbl` L2946 -- whereas
 *       `ui/src/api/types.ts` declares only `ssnMasked` and `governmentIssuedIdMasked` on the read
 *       shape, because no read operation returns either in the clear. Seeding a mask into the control
 *       would submit the mask, and the service would then store it.
 *       Trade-offs: an operator who intends no change to these four leaves them blank, and the
 *       service reads an absent member as PRESERVE rather than as clear -- `nationalIdentifierUpdate`
 *       and `governmentIdentifierUpdate` in
 *       `services/account-service/src/main/java/com/carddemo/account/mapper/CustomerMapper.java` both
 *       select preserve for a never-supplied value, and `customerRegionUnchanged` counts a
 *       never-supplied identifier as unchanged. So blank means unedited on both sides of the wire,
 *       and the removal marker the reference itself uses is what the service reads as clear.
 */

/**
 * Seeds the form from one account-view response.
 *
 * Assumptions: this is `3200-SETUP-SCREEN-VARS`, which moves each stored value into its map field, in
 * the two directions the response splits: the ten account members and the eighteen customer members.
 * The three dates and the two telephone numbers are decomposed because the map paints a control per
 * part, and the four protected identifier members are left empty for the reason recorded above.
 * Assumptions: the parameter requires the customer half to be PRESENT, which the account-view shape
 * itself no longer guarantees -- `ui/src/api/types.ts` publishes it as nullable, because the reference
 * paints an account whose customer master holds no row. This screen cannot seed from that arm: the
 * eighteen customer controls have no values to carry, and the reference behaves the same way rather
 * than seeding blanks. `9000-READ-ACCT` at `app/cbl/COACTUPC.cbl` L3636 to L3638 leaves the paragraph
 * before `9500-STORE-FETCHED-DATA` when `DID-NOT-FIND-CUST-IN-CUSTDAT`, and L2577 shows the details
 * only `IF FOUND-CUST-IN-MASTER`. Narrowing the parameter is what makes that refusal a COMPILE-time
 * obligation on every caller instead of a runtime read of a null.
 * @param {AccountViewResponse & { readonly customer: CustomerDetail }} view - The account and its
 *   customer, as the read returned them, with the customer half established to be present.
 * @returns {AccountUpdateFormValues} Every control's value, ready to render.
 */
export function formValuesFrom(
  view: AccountViewResponse & { readonly customer: CustomerDetail },
): AccountUpdateFormValues {
  const openDate = splitIsoDate(view.account.openDate);
  const expirationDate = splitIsoDate(view.account.expirationDate);
  const reissueDate = splitIsoDate(view.account.reissueDate);
  const dateOfBirth = splitIsoDate(view.customer.dateOfBirth);
  const phone1 = splitPhoneNumber(view.customer.phoneNumber1);
  const phone2 = splitPhoneNumber(view.customer.phoneNumber2);

  return {
    accountId: view.accountId,
    activeStatus: view.account.activeStatus,
    openDateYear: openDate.year,
    openDateMonth: openDate.month,
    openDateDay: openDate.day,
    creditLimit: applyMoneyEditMask(view.account.creditLimit),
    expirationDateYear: expirationDate.year,
    expirationDateMonth: expirationDate.month,
    expirationDateDay: expirationDate.day,
    cashCreditLimit: applyMoneyEditMask(view.account.cashCreditLimit),
    reissueDateYear: reissueDate.year,
    reissueDateMonth: reissueDate.month,
    reissueDateDay: reissueDate.day,
    currentBalance: applyMoneyEditMask(view.account.currentBalance),
    currentCycleCredit: applyMoneyEditMask(view.account.currentCycleCredit),
    groupId: view.account.groupId,
    currentCycleDebit: applyMoneyEditMask(view.account.currentCycleDebit),
    customerId: view.customer.customerId,
    ssnPart1: '',
    ssnPart2: '',
    ssnPart3: '',
    dateOfBirthYear: dateOfBirth.year,
    dateOfBirthMonth: dateOfBirth.month,
    dateOfBirthDay: dateOfBirth.day,
    ficoCreditScore: view.customer.ficoCreditScore,
    firstName: view.customer.firstName,
    middleName: view.customer.middleName ?? '',
    lastName: view.customer.lastName,
    addressLine1: view.customer.addressLine1,
    stateCode: view.customer.stateCode,
    addressLine2: view.customer.addressLine2 ?? '',
    zipCode: view.customer.zipCode,
    city: view.customer.city,
    countryCode: view.customer.countryCode,
    phone1AreaCode: phone1.areaCode,
    phone1Prefix: phone1.prefix,
    phone1LineNumber: phone1.lineNumber,
    governmentIssuedId: '',
    phone2AreaCode: phone2.areaCode,
    phone2Prefix: phone2.prefix,
    phone2LineNumber: phone2.lineNumber,
    eftAccountId: view.customer.eftAccountId,
    primaryCardHolderIndicator: view.customer.primaryCardHolderIndicator,
  };
}

/**
 * Re-seeds the form from an update response, which carries the state as stored.
 *
 * Assumptions: the update response repeats the whole of both records rather than acknowledging the
 * write, which is `3203-SHOW-UPDATED-VALUES` -- the reference redisplays every field from the record
 * it has just written. The two shapes differ only in the three members the update response adds, so
 * one seeding path serves both once the account identifier and the two record members are lifted out.
 * @param {AccountUpdateResponse} applied - The state as stored, as the update returned it.
 * @returns {AccountUpdateFormValues} Every control's value, ready to re-render.
 */
export function formValuesFromApplied(applied: AccountUpdateResponse): AccountUpdateFormValues {
  return formValuesFrom({
    accountId: applied.accountId,
    account: applied.account,
    customer: applied.customer,
    informationMessage: applied.informationMessage,
    returnMessage: applied.returnMessage,
  });
}

/**
 * The stored state of the two protected identifiers, as the service published it.
 *
 * Assumptions: both members hold a MASKED rendering and nothing else. `ui/src/api/types.ts` names them
 * `ssnMasked` and `governmentIssuedIdMasked` for exactly that reason, and this shape repeats the naming
 * so no consumer of it can come to believe it holds a whole value.
 */
export interface StoredProtectedIdentifiers {
  /** The national identifier as stored, masked. `ACSTSSN1/2/3` at `app/bms/COACTUP.bms` L262 to L288. */
  readonly ssnMasked: string;
  /** The government-issued identifier as stored, masked. `ACSTGOVT` at `app/bms/COACTUP.bms` L440. */
  readonly governmentIssuedIdMasked: string;
}

/**
 * Lifts the two masked identifiers out of one customer block.
 *
 * Assumptions: this reads from the same block `formValuesFrom` seeds the rest of the customer region
 * from, so both the read response and the update response supply it -- {@link AccountUpdateResponse}
 * repeats the whole customer record rather than acknowledging the write, so the state shown beside the
 * controls stays the state on file after a write as well as after a read.
 * @param {CustomerDetail} customer - The customer block, exactly as the service published it.
 * @returns {StoredProtectedIdentifiers} The two masked renderings, unchanged.
 */
export function storedIdentifiersFrom(customer: CustomerDetail): StoredProtectedIdentifiers {
  return {
    ssnMasked: customer.ssnMasked,
    governmentIssuedIdMasked: customer.governmentIssuedIdMasked,
  };
}

/**
 * Composes the account identifier's own key refusal, which is the one edit that runs before a read.
 *
 * Assumptions: `1210-EDIT-ACCOUNT` is the whole of the reference's validation on the opening turn,
 * and it produces two distinguishable outcomes. A blank filter sets the blank flag and its own
 * sentence, which `1200-EDIT-MAP-INPUTS` then OVERWRITES unconditionally with `No input received` --
 * so the sentence an operator actually reads for a blank filter is that one, not
 * `Account number not provided`, and the guard the other setters carry is absent from that
 * statement. A non-numeric or all-zero filter composes the two-literal sentence instead.
 * @param {string} entry - The account identifier exactly as typed.
 * @returns {FieldError | null} The refusal to render, or `null` when the value is acceptable.
 */
export function accountFilterError(entry: string): FieldError | null {
  const typed = entry.trim();
  if (typed.length === 0) {
    return {
      field: 'accountId',
      state: 'BLANK',
      message: MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text,
    };
  }
  if (!ACCEPTABLE_ACCOUNT_ID.test(typed) || typed === ZEROED_ACCOUNT_ID) {
    return {
      field: 'accountId',
      state: 'NOT_OK',
      message: formatMessageTemplate(MESSAGE_TEMPLATES.ACCOUNT_NUMBER_MUST_BE_11_DIGIT_NON_ZERO),
    };
  }

  return null;
}

/**
 * Composes the request body from the form, omitting the four identifier members left untouched.
 *
 * Assumptions: the composite values are sent in PARTS and are not recombined here, because the
 * contract declares a member per part and the service composes them itself -- the telephone numbers
 * as `'(' + area + ')' + prefix + '-' + line` at `app/cbl/COACTUPC.cbl` L4027 to L4041, the dates as
 * `year + '-' + month + '-' + day` at L4047 to L4052 and the national identifier as the nine-digit
 * concatenation at L4044. Recombining them in the browser would discard which part a field error
 * refers to, which is the whole reason the map paints them separately.
 *
 * Assumptions: each of the four identifier members is SPREAD in only when the operator typed
 * something, never assigned as an empty string. `ui/tsconfig.json` enables
 * `exactOptionalPropertyTypes`, so an absent member and a member holding nothing are different
 * states, and only the absent one means preserve to the service.
 * @param {AccountUpdateFormValues} values - The form exactly as the operator left it.
 * @returns {SensitiveAccountUpdateRequest} The body to submit.
 */
export function updateRequestFrom(values: AccountUpdateFormValues): SensitiveAccountUpdateRequest {
  return {
    accountId: values.accountId,
    activeStatus: values.activeStatus,
    /*
     * WHY : Assumptions: each amount has the edit mask's decoration REMOVED here, because the control it
     *       came from is editable and the masked text is therefore also the submitted text. The service
     *       parser trims and strips a sign and group separators but not the interior blanks zero
     *       suppression produces, so a masked value sent verbatim would be refused as not-a-number. The
     *       reversal is total on anything it cannot read, so a value the operator typed badly still
     *       reaches the service and is refused there in the reference's own words rather than here.
     */
    creditLimit: stripMoneyEditMask(values.creditLimit),
    cashCreditLimit: stripMoneyEditMask(values.cashCreditLimit),
    currentBalance: stripMoneyEditMask(values.currentBalance),
    currentCycleCredit: stripMoneyEditMask(values.currentCycleCredit),
    currentCycleDebit: stripMoneyEditMask(values.currentCycleDebit),
    openDateYear: values.openDateYear,
    openDateMonth: values.openDateMonth,
    openDateDay: values.openDateDay,
    expirationDateYear: values.expirationDateYear,
    expirationDateMonth: values.expirationDateMonth,
    expirationDateDay: values.expirationDateDay,
    reissueDateYear: values.reissueDateYear,
    reissueDateMonth: values.reissueDateMonth,
    reissueDateDay: values.reissueDateDay,
    groupId: values.groupId,
    customerId: values.customerId,
    dateOfBirthYear: values.dateOfBirthYear,
    dateOfBirthMonth: values.dateOfBirthMonth,
    dateOfBirthDay: values.dateOfBirthDay,
    ficoCreditScore: values.ficoCreditScore,
    firstName: values.firstName,
    middleName: values.middleName,
    lastName: values.lastName,
    addressLine1: values.addressLine1,
    addressLine2: values.addressLine2,
    city: values.city,
    stateCode: values.stateCode,
    countryCode: values.countryCode,
    zipCode: values.zipCode,
    phone1AreaCode: values.phone1AreaCode,
    phone1Prefix: values.phone1Prefix,
    phone1LineNumber: values.phone1LineNumber,
    phone2AreaCode: values.phone2AreaCode,
    phone2Prefix: values.phone2Prefix,
    phone2LineNumber: values.phone2LineNumber,
    eftAccountId: values.eftAccountId,
    primaryCardHolderIndicator: values.primaryCardHolderIndicator,
    ...(values.ssnPart1 === '' ? {} : { ssnPart1: values.ssnPart1 }),
    ...(values.ssnPart2 === '' ? {} : { ssnPart2: values.ssnPart2 }),
    ...(values.ssnPart3 === '' ? {} : { ssnPart3: values.ssnPart3 }),
    ...(values.governmentIssuedId === '' ? {} : { governmentIssuedId: values.governmentIssuedId }),
  };
}

/**
 * Reports whether the form differs from the record as it was read.
 *
 * Assumptions: this is `1205-COMPARE-OLD-NEW` at `app/cbl/COACTUPC.cbl` L1690 onward, and two of its
 * comparisons disregard case where the rest do not: the active status is compared through
 * `FUNCTION UPPER-CASE` on both sides, and the group identifier through `UPPER-CASE` of `TRIM`.
 * Comparing those two with regard to case would report a change the reference does not see, and the
 * reference's own answer to no change is a sentence rather than a write.
 *
 * Assumptions: the four identifier members are compared against the empty baseline they were seeded
 * with, so typing into any of them IS a change and leaving all four blank is not -- which is the same
 * answer `customerRegionUnchanged` reaches server-side by testing each for never-supplied.
 * @param {AccountUpdateFormValues} values - The form as the operator left it.
 * @param {AccountUpdateFormValues} baseline - The form as the read seeded it.
 * @returns {boolean} `true` when at least one field differs.
 */
export function hasChanges(
  values: AccountUpdateFormValues,
  baseline: AccountUpdateFormValues,
): boolean {
  const caseInsensitive: readonly AccountUpdateFieldName[] = ['activeStatus', 'groupId'];

  return (Object.keys(values) as AccountUpdateFieldName[]).some(
    /**
     * Compares one field, disregarding case for the two the reference upper-cases.
     * @param {AccountUpdateFieldName} field - The field under comparison.
     * @returns {boolean} `true` when this field differs from its baseline.
     */
    (field: AccountUpdateFieldName): boolean => {
      const current = values[field];
      const original = baseline[field];
      if (caseInsensitive.includes(field)) {
        return current.trim().toUpperCase() !== original.trim().toUpperCase();
      }

      return current !== original;
    },
  );
}

/**
 * Indexes a response's field errors by the field each names.
 *
 * Assumptions: the FIRST entry for a field wins, because the array's order is the order the service
 * validated in and the reference's guarded setters mean the earliest failure is the one an operator
 * reads. Entries naming something outside the form -- the `version` entry a conflict carries, for
 * instance -- are kept in the map and simply match no control, which is harmless and avoids this
 * function deciding what the service is allowed to name.
 * @param {readonly FieldError[]} errors - The array exactly as the response carried it.
 * @returns {ReadonlyMap<string, FieldError>} One entry per named field, first occurrence retained.
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
 * Selects the first field in screen order that the response marked, so focus can be placed on it.
 *
 * Assumptions: this reproduces `MOVE -1 TO <field>L`, the reference's cursor idiom, and it walks
 * {@link CURSOR_ORDER} rather than the error array -- because the array's order is the VALIDATION
 * order and the cursor table's order is the PAINT order, and the two differ for the state code.
 * @param {ReadonlyMap<string, FieldError>} errors - The indexed field errors.
 * @returns {AccountUpdateFieldName | null} The field to focus, or `null` when none was marked.
 */
export function firstFieldInError(
  errors: ReadonlyMap<string, FieldError>,
): AccountUpdateFieldName | null {
  for (const field of CURSOR_ORDER) {
    if (errors.has(field)) {
      return field;
    }
  }

  return null;
}

/** One screen-level statement: the sentence to render and the appearance its source field had. */
export interface BandStatement {
  /** The sentence, or `null` to clear the band. */
  readonly message: string | null;
  /** Appearance matching the source field: `info` for `WS-INFO-MSG`, `error` for `WS-RETURN-MSG`. */
  readonly severity: MessageBandSeverity;
}

/** What one rejected save turned out to be, which selects the action the screen moves to. */
export interface SaveRejection {
  /** The change action the reference sets for this outcome. */
  readonly action: ChangeAction;
  /** The statement to render in the band. */
  readonly statement: BandStatement;
  /** The field errors to mark controls with, empty when the rejection named none. */
  readonly fieldErrors: readonly FieldError[];
  /*
   * WHY : ⚠️ Refactoring Rationale: this member states WHAT the rejection was where it used to state
   *       what to do about it -- it was `reread`, and its one consumer re-read the record. A
   *       responsive review then drove a refused save and measured the consequence: 42 of the 43
   *       controls came back empty, so the operator's typed work was gone and the sentence asking them
   *       to review a changed record stood over a blank form. Naming the OUTCOME leaves the remedy to
   *       the caller, which is where the reference puts it -- `2000-DECIDE-ACTION` sets an action for
   *       this outcome and nothing else, at `app/cbl/COACTUPC.cbl` L2610 to L2612.
   */
  /** Whether the refusal was the optimistic-concurrency one, which no other outcome is. */
  readonly concurrency: boolean;
}

/*
 * WHY : Assumptions: exactly ONE screen-level sentence is rendered at a time however many controls are
 *       marked, and that is the reference's behaviour rather than a simplification. Every setter of
 *       `WS-RETURN-MSG` in `app/cbl/COACTUPC.cbl` is wrapped in `IF WS-RETURN-MSG-OFF`, so the first
 *       failure to be reached is the one that reaches the 78-character field and every later failure
 *       sets its validation flag and finds the sentence already taken. The consequence is
 *       first-error-wins on the band and all-errors-shown on the fields, which is what
 *       {@link MessageBand} taking one nullable sentence and `Form.Item` taking its own state
 *       expresses.
 * WHY : Refactoring Rationale: this mapping lives HERE rather than in `MessageBand`, because that
 *       component's own contract refuses to accept a problem document -- it states that taking one
 *       would invite a caller to pour a field-error array into the single reserved line, and that it
 *       never imports from `ui/src/api`. So the screen owns the translation from a normalised failure
 *       to a sentence and a severity, which is also where the knowledge of WHICH sentence belongs to
 *       which outcome sits.
 */

/**
 * Classifies a rejected save into the change action and sentence the reference sets for it.
 *
 * Assumptions: the four outcomes are `2000-DECIDE-ACTION`'s inner `EVALUATE` at
 * `app/cbl/COACTUPC.cbl` L2604 to L2614, transcribed. A lock that could not be taken sets the
 * lock-error action; a rewrite that was refused sets the write-failed action; a record another user
 * changed sets the screen BACK to showing details; anything else is the committed action.
 *
 * ⚠️ Refactoring Rationale: the show-details arm no longer asks its caller to re-read, and the
 * reference is the reason rather than a preference. Its concurrency arm at `app/cbl/COACTUPC.cbl`
 * L2610 to L2612 does exactly two things -- `SET ACUP-SHOW-DETAILS TO TRUE` and move the changed-record
 * sentence -- and performs NO read; the explicit `9000-READ-ACCT` in that same `EVALUATE` belongs to
 * the PF12 arm at L2572 to L2580. The screen the reference then sends is painted by
 * `3202-SHOW-ORIGINAL-VALUES`, which redisplays the `ACUP-OLD-*` before-image it captured at the read.
 * So the reference neither re-reads on this outcome nor blanks the screen, and the re-read this arm
 * used to request was a browser invention that emptied the form.
 *
 * Assumptions: the concurrency refusal is recognised through `isConflictFailure`, the predicate the
 * transport module exports, and never by comparing a status here. That module records why: a literal
 * `409` in each screen that writes is a fact encoded once per screen, and the screen that omitted it
 * would render a concurrent change as an ordinary failure.
 *
 * Trade-offs: a lock failure and a rewrite failure are not distinguishable over this contract, and
 * the write-failed action is chosen for every non-conflict rejection. The reference distinguishes
 * them by the CICS response code of a `READ ... UPDATE` against that of a `REWRITE`, and neither code
 * crosses the wire; the service answers a refused write with a single problem document. The
 * write-failed sentence is chosen because it is true of both -- the update did not happen -- whereas
 * naming a lock would assert a cause this screen has not established. Both lock sentences remain in
 * the catalog and are rendered when the service's own document supplies one.
 * @param {unknown} failure - The caught value from the update call, of unknown provenance.
 * @returns {SaveRejection} The action to move to, the sentence to render, any field errors and
 *   whether the refusal was the concurrency one.
 */
export function classifySaveRejection(failure: unknown): SaveRejection {
  if (isConflictFailure(failure)) {
    return {
      action: 'SHOW_DETAILS',
      statement: {
        message: failure.problem.message ?? MESSAGES.DATA_WAS_CHANGED_BEFORE_UPDATE.text,
        severity: 'error',
      },
      fieldErrors: [],
      concurrency: true,
    };
  }
  if (isApiRequestError(failure) && failure.problem.fieldErrors.length > 0) {
    return {
      action: 'CHANGES_NOT_OK',
      statement: {
        message: headlineForFieldErrors(failure.problem.fieldErrors, failure.problem.message),
        severity: 'error',
      },
      fieldErrors: failure.problem.fieldErrors,
      concurrency: false,
    };
  }

  /*
   * WHY : ⚠️ Alternatives Considered: selecting the authored transient sentence here as well, the way
   *       {@link readFailureMessage} now does for a read that never reached the service. DECLINED, and
   *       the asymmetry is deliberate: a read that timed out changed nothing, so `Try again shortly`
   *       is both true and safe, whereas a WRITE whose answer never arrived has an unknown outcome --
   *       the service may have committed it -- and inviting a retry is inviting a second write of the
   *       same record. The reference's own sentence for a refused rewrite is kept because it is the
   *       conservative statement: the operator is told the update did not happen and is left to
   *       re-read, which is what the following turn's Enter or F12 does.
   * WHY : Trade-offs: an operator whose write timed out is told slightly less than the truth -- "did
   *       not complete" would be more precise than "failed" -- and that cost is accepted against the
   *       alternative, which is a duplicate money-affecting write. The service's own sentence still
   *       wins whenever it supplies one.
   */
  return {
    action: 'CHANGES_OKAYED_BUT_FAILED',
    statement: {
      message:
        isApiRequestError(failure) && failure.problem.message !== null
          ? failure.problem.message
          : MESSAGES.LOCKED_BUT_UPDATE_FAILED.text,
      severity: 'error',
    },
    fieldErrors: [],
    concurrency: false,
  };
}

/**
 * Selects the sentence a failed READ should render.
 *
 * Assumptions: the reference distinguishes a missing account from a missing customer and from a
 * missing cross-reference row, at `app/cbl/COACTUPC.cbl` L500, L502 and L498 respectively, and the
 * contract deliberately answers all three with one status so that the answer does not disclose which
 * of the three was absent. The service's own sentence is therefore rendered when it supplies one, and
 * the account-master sentence is the fallback because it is the first of the three the reference
 * reaches.
 * @param {unknown} failure - The caught value from the read call, of unknown provenance.
 * @returns {string} The sentence to render in the band.
 */
export function readFailureMessage(failure: unknown): string {
  if (isApiRequestError(failure) && failure.problem.message !== null) {
    return failure.problem.message;
  }

  /*
   * WHY : ⚠️ Refactoring Rationale: a failure that never reached the service is reported as one, and it
   *       used to be reported as a missing account. The transport module distinguishes four failures
   *       and only `PROBLEM` is the service answering -- a timeout, a network failure and an
   *       unreadable body all carry a SYNTHESISED document whose `message` is null, so they fell
   *       through to the account-master sentence and told the operator their account does not exist
   *       when what happened is that nothing answered. That is a false statement about their data, and
   *       it sends them to check an identifier that was correct.
   * WHY : ⚠️ Assumptions: the two authored sentences are selected on the module's own transience
   *       classification rather than on a status list restated here, and the service's own sentence
   *       still wins over both -- the branch above returns first. The order is the catalog's stated
   *       one: a supplied sentence verbatim, then transient, then persistent.
   * WHY : Trade-offs: the account-master sentence remains the fallback for a service document that
   *       carries no message, which is the case it was written for. `ui/src/api/accounts.ts` records
   *       that a missing account, a missing cross-reference row and a missing customer row are all
   *       answered as one status, so a document-carrying refusal with no words is still most likely
   *       that miss; only the no-answer cases are moved off it.
   * WHY : Assumptions: the transient sentence INVITES a repeat and offers no control for one, and that
   *       is deliberate rather than unfinished. A repeat control belongs behind `isRepeatableFailure`
   *       and not behind transience -- the transport module records that the two differ precisely
   *       where it matters, since a gateway failure on a write is transient and not repeatable -- and
   *       this screen's repeat is a key the operator already has: Enter re-reads. Rendering a second
   *       control for it would duplicate an advertised key, so nothing here consults that predicate and
   *       anything added later must.
   */
  if (isApiRequestError(failure) && failure.kind !== 'PROBLEM') {
    return isTransientFailure(failure) ? TRANSIENT_FAILURE_TRY_AGAIN : PERSISTENT_FAILURE_REPORT_IT;
  }

  return MESSAGES.DID_NOT_FIND_ACCT_IN_ACCTDAT.text;
}

/*
 * WHY : Assumptions: the abend surface is reached only from the branch the reference itself calls
 *       unreachable. `2000-DECIDE-ACTION`'s `WHEN OTHER` at `app/cbl/COACTUPC.cbl` L2632 to L2640
 *       moves `'0001'` into `ABEND-CODE`, the program name into `ABEND-CULPRIT` and
 *       `UNEXPECTED DATA SCENARIO` into `ABEND-MSG` before performing `ABEND-ROUTINE`, which also
 *       carries the shared `UNEXPECTED ABEND OCCURRED.` sentence. Both strings come from the catalog
 *       so the pair cannot drift, and the four members are the structured equivalent of the
 *       `ABEND-DATA` group at `app/cpy/CSMSG02Y.cpy` L45 to L53.
 */

/*
 * WHY : Assumptions: the two labels beside the abend detail are the baseline's OWN data names, read
 *       out of the catalog's `ABEND-DATA` table rather than written as prose here, so the diagnostic
 *       surface names the fields an operator quoting it would find in `app/cpy/CSMSG02Y.cpy`. The
 *       table is a four-entry tuple in declaration order -- code, culprit, reason, message -- and the
 *       first two are the members this abend populates with anything other than spaces.
 */

/** Field names the catalog records for the two abend members this screen renders. */
const ABEND_LABELS = {
  abendCode: ABEND_DATA_FIELDS[0].field,
  abendCulprit: ABEND_DATA_FIELDS[1].field,
} as const;

/** The abend the reference raises when its change action reaches no branch. */
const UNEXPECTED_DATA_SCENARIO_ABEND = {
  abendCode: '0001',
  abendCulprit: ACCOUNT_UPDATE_PROGRAM_NAME,
  abendReason: '',
  abendMsg: UNEXPECTED_DATA_SCENARIO,
} as const;

/*
 * WHY : Alternatives Considered: letting the design system compose each control's element identifier
 *       from the form name, and holding a ref per control. The first is rejected because the
 *       cursor-placement effect has to find ONE control among forty-three by name, and composing that
 *       name would depend on an internal of the form component rather than on a value this module
 *       states; the second is rejected because forty-three refs cannot be created by a loop without
 *       breaking the rules of hooks, and a map of callback refs adds a closure per control per render
 *       for a value used only when a save is refused. A stated identifier per field is read by the
 *       effect, is what the label's `for` attribute resolves to, and is assertable from a test.
 */

/**
 * Composes the element identifier of one control.
 * @param {AccountUpdateFieldName} field - The form member the control edits.
 * @returns {string} A per-field element identifier, stable across renders.
 */
export function fieldDomId(field: AccountUpdateFieldName): string {
  return `carddemo-account-update-${field}`;
}

/**
 * Composes the element identifier of one part group's painted caption.
 *
 * Purpose: give the caption an identifier so the group of parts beneath it can name itself by
 * REFERENCE, which is what makes the painted words and the announced words provably the same words.
 * @param {AccountUpdateFieldName} firstPart - The group's first part, which names the group.
 * @returns {string} A per-group element identifier, stable across renders.
 */
export function groupCaptionDomId(firstPart: AccountUpdateFieldName): string {
  return `${fieldDomId(firstPart)}-group-caption`;
}

/** The ARIA wiring one part group needs: a named group, and a caption bound to its first part. */
export interface PartGroupAria {
  /** Props for the group element, naming it by reference to the painted caption. */
  readonly group: { readonly role: 'group'; readonly 'aria-labelledby': string };
  /** The control the painted caption's `for` attribute resolves to. */
  readonly captionFor: string;
  /** Identifier carried by the caption itself. */
  readonly captionId: string;
}

/**
 * Wires one part group's caption to its parts, so the visible grouping is not semantically inert.
 *
 * Purpose: seven captions on this screen title a GROUP of two or three boxes rather than one box --
 * the four dates, the national identifier and both telephone numbers -- and a browser accessibility
 * audit reported all seven as labels with nothing associated. The parts were each individually named
 * already; what was missing was the group.
 *
 * ⚠️ Assumptions: the group is named by `aria-labelledby` rather than by a copy of the caption text in
 * an `aria-label`. Referencing the painted element means the announced name IS the painted caption
 * and cannot drift from it, where a copy is a second string that a later edit can leave behind.
 *
 * ⚠️ Assumptions: the caption additionally takes a `for` pointing at the group's FIRST part. Two
 * separate things are wrong with a caption that points at nothing and only one of them is the missing
 * group: the audit's own finding is a label with no associated field, and a caption that cannot be
 * clicked to reach a control is a pointer affordance every other label on this screen has. Pointing it
 * at the first part gives it both, and costs the part nothing -- its own `aria-label` still supplies
 * its accessible name, because an explicit name takes precedence over an associated label.
 *
 * Alternatives Considered: a `fieldset` with a `legend`, which the audit also admits. Rejected on two
 * counts -- it is raw markup where the design system supplies the grouping element, which AAP section
 * 0.3.2 forbids, and `legend` carries its own block layout that would break the caption out of the
 * form item's own label column, changing the measured reading order to fix an announcement.
 * @param {AccountUpdateFieldName} firstPart - The group's first part, which names the group.
 * @returns {PartGroupAria} The group props, the caption's target and the caption's identifier.
 */
export function partGroupAria(firstPart: AccountUpdateFieldName): PartGroupAria {
  const captionId = groupCaptionDomId(firstPart);

  return {
    group: { role: 'group', 'aria-labelledby': captionId },
    captionFor: fieldDomId(firstPart),
    captionId,
  };
}

/*
 * WHY : ⚠️ Assumptions: the national identifier's wiring is resolved ONCE at module scope, where the
 *       date and telephone groups resolve theirs inside their own renderer. The difference is that
 *       those two are renderers called with a different first part each time -- four dates and two
 *       numbers -- while this group is written out inline and has exactly one first part, so a value
 *       recomputed on every render would be the same value every time.
 * WHY : Assumptions: it sits AFTER the function it calls rather than beside the other identifier
 *       constants above. A `const` initialiser runs where it is written, and although a `function`
 *       declaration is hoisted and would in fact be callable from there, relying on that would make
 *       the ordering of two apparently unrelated declarations load-bearing.
 */

/** The national-identifier group's ARIA wiring, whose first part is the leading three characters. */
export const SSN_GROUP_ARIA = partGroupAria('ssnPart1');

/**
 * The form members for which the character `'*'` is a real update instruction rather than a marker.
 *
 * ⚠️ Purpose: this exists because the blank marker and a submittable value collide on exactly one field.
 * `com.carddemo.account.mapper.CustomerMapper.governmentIdentifierUpdate` tests
 * `IDENTIFIER_REMOVAL_MARKER.equals(submitted)` FIRST and answers `ProtectedValueUpdate.clear()`, so a
 * submitted `'*'` on that field DELETES the stored ciphertext. That mapper's own documentation records the
 * choice and its reason: the column is nullable, so all three intents are available, and the marker is
 * used for removal specifically because it is the character the reference already gives a user for the
 * purpose.
 *
 * ⚠️ Refactoring Rationale: {@link renderField} writes the blank marker into the control's VALUE, and the
 * note in that position defended it on the ground that the marker "round-trips faithfully" because "the
 * service's shared never-supplied test folds the same character into the same answer". That reading is
 * right for every field except this one and wrong for this one, because the never-supplied test is not the
 * first test the government-identifier mapping applies -- the removal test is. Written into the value, a
 * blank refusal on that field would leave a `'*'` in the control for the operator's next submit to carry,
 * and {@link updateRequestFrom} sends the member whenever it is not empty, so the mapping would
 * read an instruction to clear a stored identifier the operator never saw and did not ask to remove.
 *
 * Assumptions: today no validation marks that member BLANK -- `AccountUpdateService` reads it only through
 * the shared never-supplied test, when deciding whether the customer region changed -- so the collision is
 * latent rather than live. It is closed here anyway, because which character a field's value may safely
 * carry is a property of what the service does with that field, not of which of its validations happen to
 * be wired at the moment, and a later refusal added on the service side would otherwise turn a rendering
 * detail into silent data loss.
 *
 * Alternatives Considered: rendering the marker beside the control for EVERY field, so no exception list
 * were needed. Rejected because the marker's position inside the field is part of what a returning
 * operator recognises -- `app/cpy/CSSETATY.cpy` L23 to L26 moves the asterisk into the field's own OUTPUT
 * subfield -- and giving that up on twenty-odd fields to defend one would trade a real fidelity for a
 * uniformity nothing needs.
 *
 * Alternatives Considered: choosing a different removal character in the mapper, so the collision were
 * removed at the source. Rejected because the mapper's reasoning is sound and is not this screen's to
 * overturn: the marker is the character the reference itself uses for removal, and inventing a second
 * convention would put a value on the wire that no baseline screen produces.
 */
const MARKER_BEARING_FIELDS: ReadonlySet<AccountUpdateFieldName> = new Set<AccountUpdateFieldName>([
  'governmentIssuedId',
]);

/** Every input the field renderer needs, passed as one object so no member is positional. */
export interface FieldRenderSpec {
  /** Which member of the form this control edits. */
  readonly field: AccountUpdateFieldName;
  /** Label rendered against the control, or the empty string when a group label names it instead. */
  readonly label: string;
  /**
   * Whether the control asks a touch keyboard for digits, which every numeric-only part does.
   *
   * Refactoring Rationale: this member USED to mean "entry is coerced to decimal digits" and now
   * means only the keyboard hint. The coercion was withdrawn for the reason recorded where it used to
   * live: `app/bms/COACTUP.bms` declares `ATTRB=...NUM` on none of its 128 fields, so filtering
   * characters here made the service's non-numeric refusals unreachable and reported a filled field as
   * blank.
   */
  readonly numeric?: boolean | undefined;

  /*
   * WHY : Assumptions: this member is OPTIONAL and holds the ID of a standing hint element rather than
   *       the hint text. Three of the twenty-seven fields share one standing description -- the two
   *       protected identifier parts and the government-issued identifier point at the same sentence --
   *       so an id is what lets one element be referenced by several controls instead of the sentence
   *       being repeated in each control's own description.
   * WHY : Refactoring Rationale: the member was ABSENT while three call sites already passed it, so the
   *       renderer composed `aria-describedby` from a property the type did not admit. Declaring it is
   *       what makes those three standing hints reach assistive technology.
   */
  readonly describedBy?: string | undefined;
  /** Whether the control renders in the fixed-pitch face, which the amounts and identifiers use. */
  readonly fixedPitch?: boolean | undefined;
  /** Placeholder text, used only for the three parts whose mapset field declares an initial. */
  readonly placeholder?: string | undefined;
  /** Accessible name, supplied where a shared label cannot name one part of a split group. */
  readonly ariaLabel?: string | undefined;
  /** Whether this control takes initial focus, true for the mapset's single `IC` field only. */
  readonly autoFocus?: boolean | undefined;
  /**
   * Supplementary read-only content rendered under the control, used only by the two protected
   * identifiers to show their stored masked state. It is never an input and never submitted.
   */
  readonly extra?: ReactNode | undefined;
}

/**
 * Renders the account update screen: one account and its customer, edited together.
 *
 * The screen opens asking for an account identifier, reads that account with its customer, presents
 * the forty fields the reference unprotects, validates on Enter and writes only after F5 confirms.
 *
 * Assumptions: no selection state travels from the server and none is remembered there. The account
 * identifier is what an operator types into the mapset's own `ACCTSID` field, and it becomes a member
 * of each request body; the operator's identity comes from the signed token the transport module
 * attaches. AAP section 0.7.1 removes the passed structure that carried both in the reference, along
 * with its re-entry discriminator, so nothing here distinguishes a first turn from a later one.
 * @returns {ReactElement} The account update screen, or the abend surface when the change action
 *   reaches no branch.
 */
export function AccountUpdateScreen(): ReactElement {
  const navigate = useNavigate();
  const paintedAt = useServerInstant();
  const { cssVar, token } = theme.useToken();

  const [action, setAction] = useState<ChangeAction>('DETAILS_NOT_FETCHED');
  const [values, setValues] = useState<AccountUpdateFormValues>(blankFormValues);
  const [baseline, setBaseline] = useState<AccountUpdateFormValues | null>(null);
  const [revision, setRevision] = useState<string | null>(null);

  /*
   * WHY : Assumptions: the two markers live in their OWN state and never in `values`, so nothing that
   *       composes the request body can reach them. `updateRequestFrom` takes `values` alone, which is
   *       what makes "a marker cannot be submitted" a property of the shape rather than of a habit.
   */
  const [protectedValues, setProtectedValues] = useState<ProtectedIdentifierDisplay | null>(null);

  /*
   * WHY : ⚠️ Refactoring Rationale: the save confirmation is CONTROLLED from here, where it used to be
   *       opened by the design system from its own trigger alone. That left one advertised key with two
   *       behaviours: the physical F5 and the legend's F5 button invoked the write directly while the
   *       pointer control beside them opened a confirmation first, so the keyboard bypassed a gate the
   *       pointer could not. Holding the open state here lets both paths reach the same gate and the same
   *       single write action.
   */
  const [confirmingSave, setConfirmingSave] = useState(false);
  /*
   * WHY : Assumptions: the stored masked identifiers are held SEPARATELY from the form values rather
   *       than as two more members of it, because they are not editable and are never submitted. Every
   *       member of `AccountUpdateFormValues` is read by `updateRequestFrom`, so a masked value living
   *       there would be one omission away from being sent as the identifier; keeping it outside makes
   *       that impossible rather than merely unintended. `null` is the never-read state, which is what
   *       an unfetched screen and a refused read both are.
   */
  const [storedIdentifiers, setStoredIdentifiers] = useState<StoredProtectedIdentifiers | null>(
    null,
  );
  const [fieldErrors, setFieldErrors] = useState<ReadonlyMap<string, FieldError>>(new Map());
  const [statement, setStatement] = useState<BandStatement>({ message: null, severity: 'error' });

  /*
   * WHY : ⚠️ Refactoring Rationale: the screen carries TWO message channels where it carried one. The
   *       mapset declares two independent fields at two different rows -- `INFOMSG` at `POS=(22,23)`,
   *       `ATTRB=(PROT) COLOR=NEUTRAL`, `PIC X(45)` per `app/cpy-bms/COACTUP.CPY` L318, and `ERRMSG` at
   *       `POS=(23,1)`, `ATTRB=(ASKIP,BRT) COLOR=RED`, `PIC X(78)` per L324 -- and the program fills them
   *       from two different working fields: `3250-SETUP-INFOMSG` derives `WS-INFO-MSG` from the change
   *       action on every send, while `WS-RETURN-MSG` latches a refusal. The terminal shows both AT ONCE.
   *       With one band they competed, so every arm below had to choose a winner and three of them chose
   *       the refusal -- which meant a concurrency refusal erased the prompt telling the operator what to
   *       do next, and a validated submission erased nothing only because it happened to write last.
   * WHY : Assumptions: the ACTION drives this channel and nothing else does, which is what
   *       `3250-SETUP-INFOMSG` does -- it recomputes the line from the action on every screen send, so
   *       the two can never disagree. `enterAction` is therefore the only writer.
   */
  const [information, setInformation] = useState<string>(informationLineFor('DETAILS_NOT_FETCHED'));
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [validating, setValidating] = useState(false);
  const [abended, setAbended] = useState(false);

  /*
   * WHY : ⚠️ Purpose: WHICH key's press is responsible for the read currently outstanding. It exists
   *       because two keys reach one reader -- the Enter fetch and the F12 cancel both perform the
   *       reference's `9000-READ-ACCT` -- so the in-flight flag alone cannot say whose turn it is, and
   *       the busy affordance is a claim about a specific control.
   * WHY : ⚠️ Assumptions: it is set beside the in-flight flag and cleared beside it, at exactly the two
   *       places that flag moves, so the pair cannot disagree. Deriving the owner from the change
   *       action instead would answer wrongly on the cancel turn: cancel is admitted only from an
   *       action the fetch also leaves the screen in, so both turns would look like Enter's.
   * WHY : Trade-offs: `null` while nothing is outstanding rather than a third owner value. The absence
   *       of a read is not a kind of read, and a `'NONE'` member would have to be excluded at every
   *       comparison below.
   */
  const [readOwner, setReadOwner] = useState<ReadTurnOwner | null>(null);

  /*
   * WHY : ⚠️ Purpose: whether the LAST settled write was refused because another user had changed the
   *       record, which is the one refusal that leaves the operator holding an edit the screen cannot
   *       retry as it stands. It exists to paint the cancel legend on that turn and for nothing else.
   * WHY : ⚠️ Assumptions: it is a flag of its own rather than a reading of the change action, because
   *       the action the reference sets for this outcome -- show-details -- is the same action a local
   *       no-change refusal and a plain fetch both set, and only ONE of those three needs the legend.
   *       Deriving it from the action would light the legend on the other two as well, which would
   *       advertise a discard on a turn that has nothing to discard.
   * WHY : Alternatives Considered: an additional change action, say a concurrency-refused one. Rejected
   *       because {@link ChangeAction} is a transcription of `ACUP-CHANGE-ACTION`'s `88` levels at
   *       `app/cbl/COACTUPC.cbl` L654 to L668 and the reference declares no such level -- adding one
   *       would put a value in a transcribed type that its source does not have, and every `switch` over
   *       it would then carry an arm with no oracle behind it.
   */
  const [concurrencyRefused, setConcurrencyRefused] = useState(false);

  /*
   * WHY : ⚠️ Purpose: the control the cursor belongs on for the turn just settled, when the reference
   *       names one that is not a refused field. It exists because `3300-SETUP-SCREEN-ATTRS`'s cursor
   *       table has SEVEN arms and only five of them are field refusals -- the first two are outcomes,
   *       and they were the two this screen did not transcribe.
   * WHY : ⚠️ Assumptions: it is held as state rather than derived from the change action, because the
   *       reference keys this table on the MESSAGE condition and not on the action: `FOUND-ACCOUNT-DATA`
   *       and `NO-CHANGES-DETECTED` both set the show-details action, and so does a plain re-entry that
   *       the table gives no cursor to at all. Reading the action would place a cursor on turns the
   *       reference leaves alone.
   */
  const [cursorField, setCursorField] = useState<AccountUpdateFieldName | null>(null);

  /*
   * WHY : ⚠️ Refactoring Rationale: every asynchronous turn on this screen is now sequenced by a token,
   *       where none of them was. The screen issues three kinds of request -- a read, a no-write
   *       validation and a write -- and each applied its outcome unconditionally, so any pair of them
   *       could land out of order and the LAST to arrive won regardless of which the operator asked
   *       for most recently. Three concrete losses followed. A read for account B issued while account
   *       A's read was outstanding could be overwritten by A, leaving A's values in the form under
   *       B's key -- and because the form's values are what the write submits, the next save would
   *       write A's values as though they were the operator's edits to B. A validation verdict for a
   *       superseded submission could advance the screen to `Changes validated.Press F5 to save` for
   *       values the operator had since changed. And leaving the screen with any of the three
   *       outstanding applied state to a component that had gone.
   * WHY : Assumptions: ONE counter sequences all three kinds rather than one per kind, because the
   *       question every outcome has to answer is the same -- is this still the turn the operator is
   *       waiting on -- and any new turn supersedes any older one whatever its kind. Per-kind counters
   *       would let a fresh read leave a stale validation live, which is precisely the pairing that
   *       advances the screen for values nobody submitted.
   * WHY : Trade-offs: requests are not ABORTED, only ignored. Neither the account client nor the
   *       validation client accepts an abort signal, so adding one would change the transport contract
   *       for every caller; the cost of ignoring instead is a response body already on the wire being
   *       discarded, which is invisible to the operator and cannot produce a wrong screen. The
   *       competing controls are additionally DISABLED while a turn is in flight -- see
   *       {@link turnInFlight} -- so the ordinary way of reaching these races is closed as well as
   *       guarded against.
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
   * Assumptions: this is what an EDIT does. Changing a value means the outstanding question is about a
   * submission the operator has left behind, so its answer must not be applied -- but no request is
   * being issued, so nothing should be waiting on a new token either.
   * @returns {void} Completion is the advanced sequence.
   */
  function invalidateTurnsInFlight(): void {
    turnSequence.current += 1;
  }

  /*
   * WHY : Assumptions: the cleanup invalidates rather than cancels, for the reason recorded on the
   *       trade-off above. Trade-offs: this half is DEFENSIVE and is not observable in the React
   *       version this bundle pins -- a state update on an unmounted component is a silent no-op in
   *       React 19, verified rather than assumed -- so no test distinguishes it. It is kept because it
   *       completes the invariant at no runtime cost and because the guarantee it leans on belongs to
   *       React rather than to this screen.
   */
  useEffect(
    /**
     * Registers the unmount invalidation.
     * @returns {() => void} The cleanup that makes an outstanding turn inert.
     */
    (): (() => void) => invalidateTurnsInFlight,
    [],
  );

  /**
   * Whether a request this screen issued is still outstanding.
   *
   * Assumptions: the three flags are combined here ONCE so that every control which must stand down
   * during a turn reads one value. Testing them separately at each control is how one of them comes to
   * be forgotten at a single site, which is the state that leaves a competing action reachable.
   */
  const turnInFlight = loading || validating || saving;

  /*
   * WHY : ⚠️ Purpose: which KEY owns the outstanding turn, split three ways so each legend control can
   *       say whether the request in the air is its own. The combined flag above answers "a turn is
   *       outstanding" and every control read it, so all four went inert together and none of them told
   *       the operator which one they were waiting on.
   * WHY : ⚠️ Assumptions: the validation request belongs to ENTER and the write to F5, which is this
   *       screen's own two-turn ordering -- Enter validates, F5 writes -- so neither needs an owner
   *       recorded; only the READ is reachable from two keys and only it consults {@link readOwner}.
   * WHY : Assumptions: a control whose own turn is outstanding reports BUSY and a control whose turn
   *       belongs to another key reports DISABLED, which is the distinction
   *       `ui/src/layout/usePfKeys.ts` draws: busy stays present, enabled, focusable and named and
   *       declines the press silently -- the terminal's input-inhibit -- while disabled greys the
   *       control and reports the rejection. An operator therefore sees one working control and three
   *       unavailable ones rather than four identical greyed ones.
   */
  const enterTurnIsOutstanding = validating || (loading && readOwner === 'ENTER');
  const cancelTurnIsOutstanding = loading && readOwner === 'PFK12';

  /*
   * WHY : Assumptions: the marker's colour is resolved by NAME through the token bridge to its
   *       CSS-variable reference, matching how `ui/src/screens/userUpdate/index.tsx` renders the same
   *       marker. Reading the resolved value instead would copy today's palette into an inline style and
   *       opt the element out of the theme silently.
   */
  /*
   * WHY : Refactoring Rationale: a `blankMarkerStyle` declared at this scope is withdrawn; the renderer
   *       declares its own beside the control it styles. One style object for the blank marker is the
   *       point -- two of them, at different scopes, is how the marker's colour comes to differ between
   *       the in-field and the beside-the-field placement.
   */

  /**
   * Publishes one sentence on the MESSAGE channel, the mapset's row-23 line.
   *
   * ⚠️ Assumptions: this channel is the reference's `WS-RETURN-MSG` and carries refusals only. BOTH
   * channels are delegated to the shell, which paints row 22 and row 23 adjacent as the mapset
   * declares them -- the claim that the information channel is painted by this screen's own body was
   * true of an earlier revision and the removal site records why it stopped being true.
   * @param {string | null} message - The sentence to render, or `null` to clear the channel.
   * @param {MessageBandSeverity} severity - Appearance for the sentence: `error` for a refusal and
   *   `success` for the one acknowledgement this channel carries.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function report(message: string | null, severity: MessageBandSeverity): void {
    setStatement({ message, severity });
  }

  /**
   * Moves to one change action and states the INFORMATION line the reference paints for it.
   *
   * Assumptions: the two always move together, because `3250-SETUP-INFOMSG` derives the information
   * line FROM the change action on every screen send -- so an action set without its line would leave
   * the previous action's prompt on screen.
   *
   * Refactoring Rationale: this writes the information channel and NO LONGER touches the message
   * channel, which is what lets both lines stand at once as the terminal showed them. Previously it
   * wrote the single band, so moving to an action erased whatever refusal was on screen -- and a refusal
   * written afterwards erased the prompt that told the operator what to do about it.
   * @param {ChangeAction} next - The action to move to.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function enterAction(next: ChangeAction): void {
    setAction(next);
    setInformation(informationLineFor(next));
  }

  const readAccount = useCallback(
    /**
     * Reads one account with its customer and seeds the form from it.
     *
     * Assumptions: this is `9000-READ-ACCT`, which two callers reach in the reference -- the fetch
     * turn and the cancel turn, the latter at `app/cbl/COACTUPC.cbl` L2572 to L2580, which re-reads
     * rather than restoring its snapshot so a cancel shows the record as it stands now.
     * @param {string} accountId - The identifier to read, as the operator typed it.
     * @param {ReadTurnOwner} owner - Which key's arm asked for this read, so the busy affordance can
     *   name the one control responsible for it.
     * @returns {Promise<boolean>} Resolves `true` when the record was read and the form seeded, and
     *   `false` when the read was refused, in which case the band already carries the refusal.
     */
    async (accountId: string, owner: ReadTurnOwner): Promise<boolean> => {
      /*
       * WHY : Assumptions: the turn opens BEFORE the request and every state change below is gated on
       *       it still being current. Seeding the form is the most damaging outcome to apply late,
       *       because the form's values are what a later save SUBMITS -- so a superseded read landing
       *       last would not merely show the wrong record, it would arm the next write with it.
       */
      const token = beginTurn();
      setLoading(true);
      /*
       * WHY : Assumptions: the owner is recorded here, beside the flag it qualifies, rather than by
       *       each caller before it calls. One writer for a pair of values that describe one fact is
       *       what stops them disagreeing -- a caller that set the owner and then took an early return
       *       would leave an owner with no read.
       */
      setReadOwner(owner);
      setFieldErrors(new Map());
      try {
        const read = await readAccountView(accountId);
        if (!isCurrentTurn(token)) {
          return false;
        }

        /*
         * WHY : Refactoring Rationale: this screen reads `read.account.customer` in three places and the member
         *       is NULLABLE -- the account master can hold a row whose customer identifier matches nothing,
         *       and `app/cbl/COACTVWC.cbl` L493 guards only the eighteen NAMED value fields, so the terminal
         *       shows every customer label with an empty value rather than refusing the screen. The three
         *       readers were written against a non-null customer, which is a compile error here and would have
         *       been a crashed render there.
         * WHY : Assumptions: the substitution happens ONCE, at the point the answer is received, rather than as
         *       a ternary at each reader. ui/src/screens/accountView/index.tsx resolves the same asymmetry the
         *       same way and records the reasoning: a per-reader ternary is the form a nineteenth field added
         *       later forgets.
         * WHY : Assumptions: the blank projection is composed from the shared constant the sibling screen
         *       publishes rather than declared again here, so one definition answers "what does an unpopulated
         *       customer look like" for both screens.
         */
        const populated = {
          ...read.account,
          customer: read.account.customer ?? UNPOPULATED_CUSTOMER,
        };

        /*
         * WHY : ⚠️ Refactoring Rationale: a read that answered NO REVISION is treated as a refused read
         *       on this screen, and it used to seed the form as though it had succeeded. The service
         *       withholds the entity tag on exactly one arm -- the account row was located and the
         *       customer master holds no matching row -- so the screen presented forty editable fields
         *       whose save could not possibly be issued: `saveEdits` requires a revision to form the
         *       precondition, so the operator's only route out was a refusal, and the refusal it reached
         *       was `No input received`, which is false of a screen they had just filled in.
         *       The reference does not reach that state either, and its own handling is what this now
         *       reproduces: `9400-GETCUSTDATA-BYCUST` sets `INPUT-ERROR` with `FLG-CUSTFILTER-NOT-OK` on
         *       a customer miss and composes a `not found in customer master` sentence, `9000-READ-ACCT`
         *       then branches to its exit at `app/cbl/COACTUPC.cbl` L3636 to L3638 BEFORE
         *       `9500-STORE-FETCHED-DATA` runs, and the cancel arm at L2577 sets the show-details action
         *       only `IF FOUND-CUST-IN-MASTER`. So the baseline stores nothing, presents nothing and
         *       states the miss -- which is a failed read, not a read-only one.
         *       Alternatives Considered: seeding the form and rendering it read-only, which is what the
         *       ACCOUNT VIEW screen does with the same response. Rejected here because the two screens
         *       answer different questions: the view screen exists to display, so a half-populated
         *       display is its partial success, while this screen exists to update, and a record it can
         *       never write is not a partial success of updating -- it is the read failing. Presenting it
         *       would also mean carrying a fourth display-only action through a state machine transcribed
         *       from `3300-SETUP-SCREEN-ATTRS`, which declares four arms and no such state.
         *       Trade-offs: the operator is not shown the account half they might have wanted to look at.
         *       The view screen shows exactly that, from the same response, and is one route change away.
         */
        if (read.revision === null) {
          setValues({ ...blankFormValues(), accountId });
          setBaseline(null);
          setRevision(null);
          setStoredIdentifiers(null);
          setProtectedValues(null);
          /*
           * WHY : Assumptions: the sentence is the RESPONSE'S own and is only defaulted when the response
           *       carried none. The service latches `Did not find associated customer in master file` on
           *       this arm, which is `DID-NOT-FIND-CUST-IN-CUSTDAT` at `app/cbl/COACTUPC.cbl` L501
           *       verbatim, so reading it from the answer keeps one wording rather than two -- and the
           *       catalog constant behind the default is that same declared sentence, so the fallback
           *       cannot say anything the reference does not.
           */
          report(read.account.returnMessage ?? MESSAGES.DID_NOT_FIND_CUST_IN_CUSTDAT.text, 'error');
          return false;
        }

        const seeded = formValuesFrom(populated);
        setValues(seeded);
        setBaseline(seeded);
        setRevision(read.revision);
        setStoredIdentifiers(storedIdentifiersFrom(populated.customer));

        // WHY : Assumptions: the two markers are stored EXACTLY as received and are never re-derived
        //       here. `ui/src/api/accounts.ts` has already refused any answer whose members are not the
        //       published marker, so what reaches this line is the marker; composing one locally would
        //       put a second definition of it in the tree and let the two disagree.
        setProtectedValues({
          ssnMasked: populated.customer.ssnMasked,
          governmentIssuedIdMasked: populated.customer.governmentIssuedIdMasked,
        });

        /*
         * WHY : ⚠️ Refactoring Rationale: the cursor is placed on the account status field once the read
         *       has seeded the form, and it was left wherever the re-render dropped it. A browser review
         *       measured the consequence -- `document.activeElement` was the document BODY after the
         *       fetch turn -- so a keyboard operator was returned to the top of the document and had to
         *       traverse the skip link, the sign-off control and the whole title band to reach the forty
         *       fields the read had just unlocked.
         * WHY : ⚠️ Assumptions: the field is not a choice made here. `3300-SETUP-SCREEN-ATTRS`'s cursor
         *       table opens with `WHEN FOUND-ACCOUNT-DATA ... MOVE -1 TO ACSTTUSL` at
         *       `app/cbl/COACTUPC.cbl` L3008 to L3011, and `ACSTTUS` is the account status field --
         *       `app/bms/COACTUP.bms` L94, the first `UNPROT` field the read unlocks. So the reference
         *       places the cursor on exactly this control for exactly this outcome, and the five refusal
         *       arms this screen already transcribed sit BELOW it in the same table.
         */
        setCursorField('activeStatus');

        return true;
      } catch (failure: unknown) {
        if (!isCurrentTurn(token)) {
          return false;
        }
        /*
         * WHY : Assumptions: the account identifier the operator typed is retained on the form while
         *       everything else is cleared, which is what `1100-RECEIVE-MAP` does -- it moves the
         *       received filter into the new details and then `INITIALIZE ACUP-NEW-DETAILS` has
         *       already blanked the rest. Retaining it lets the operator correct one digit instead of
         *       retyping the whole value.
         */
        setValues({ ...blankFormValues(), accountId });
        setBaseline(null);
        setRevision(null);
        /*
         * WHY : Assumptions: the stored state is cleared with the rest of the record rather than left
         *       standing. It describes the customer the refused read failed to reach, so keeping it
         *       would caption a blank form with the masked identifiers of whichever account was read
         *       LAST -- attributing one customer's identifiers to another operator's key.
         * WHY : ⚠️ Refactoring Rationale: BOTH holders of the masked identifiers are cleared, and
         *       clearing one of them was the defect. `storedIdentifiers` feeds the `extra` slot beside
         *       each control and `protectedValues` feeds the caption BELOW it, and only the first was
         *       cleared here -- so a refused read left the previous customer's markers captioning a
         *       blank form under a different account number, which is exactly the attribution the note
         *       above says this clearing exists to prevent. Two pieces of state describing one fact must
         *       be retired together; they are set together at the fulfilment arm above.
         */
        setStoredIdentifiers(null);
        setProtectedValues(null);
        report(readFailureMessage(failure), 'error');

        return false;
      } finally {
        /*
         * WHY : Assumptions: the in-flight flag is cleared UNCONDITIONALLY, unlike the state changes
         *       above. It describes this screen's own outstanding request rather than the answer, so
         *       leaving it set on a superseded turn would disable the keys permanently -- the operator
         *       would be locked out by a request they had already replaced.
         * WHY : Assumptions: the owner is retired here for the same reason and in the same breath. It
         *       qualifies the flag, so an owner outliving it would leave a control claiming to be
         *       working after its turn had answered.
         */
        setLoading(false);
        setReadOwner(null);
      }
    },
    [],
  );

  useEffect(
    /**
     * States the opening prompt once, which is the information line of the not-fetched action.
     *
     * Assumptions: the reference paints this on its own first turn, through the `CDEMO-PGM-ENTER` arm
     * of `3250-SETUP-INFOMSG`. It is stated from an effect rather than as the initial state so the
     * band's opening content and every later content come from one place.
     * @returns {void} Completion is represented by the screen's own state.
     */
    function statePromptOnMount(): void {
      setInformation(informationLineFor('DETAILS_NOT_FETCHED'));
    },
    [],
  );

  useEffect(
    /**
     * Places the cursor where the turn just settled says it belongs.
     *
     * Assumptions: this runs after the render that shows the marks, which is why it is an effect and
     * not part of the handler that set them -- the control cannot be focused before it carries its
     * error state. It reproduces `MOVE -1 TO <field>L`, and it addresses the control by the
     * identifier this module states rather than by a name the design system composes.
     *
     * ⚠️ Refactoring Rationale: it covers the whole of the reference's cursor table rather than only
     * its refusal arms. It used to consider marked controls alone, so the two arms keyed on an OUTCOME
     * placed no cursor at all -- and the successful fetch is one of them, which is why a keyboard
     * operator was dropped at the top of the document after the turn that unlocked forty fields.
     * @returns {void} Completion is the focus change, or nothing when the table names no control.
     */
    function placeCursorForTheSettledTurn(): void {
      /*
       * WHY : ⚠️ Refactoring Rationale: the named outcome is consulted BEFORE the refusal map, which is
       *       the order `3300-SETUP-SCREEN-ATTRS` states -- `FOUND-ACCOUNT-DATA` and
       *       `NO-CHANGES-DETECTED` are the first two arms of its cursor `EVALUATE` at
       *       `app/cbl/COACTUPC.cbl` L3008 to L3011 and every field arm follows them. The order is
       *       transcribed rather than reasoned about: the two cannot in fact co-occur, since neither
       *       outcome carries field refusals, so following the source costs nothing and leaves nothing
       *       to re-derive if a later arm is added.
       */
      const target = cursorField ?? firstFieldInError(fieldErrors);
      if (target === null) {
        return;
      }
      const element = document.getElementById(fieldDomId(target));
      if (element !== null) {
        element.focus();
      }
    },
    [cursorField, fieldErrors],
  );

  /**
   * Records one control's entry exactly as it was typed.
   *
   * Assumptions: the value is stored VERBATIM. No character class is filtered and no case is folded,
   * because the mapset constrains no field to digits and the service's edits own every refusal --
   * the reasoning is recorded where the coercion this replaced used to live.
   * ⚠️ Refactoring Rationale: the third parameter, a `numeric` flag, is gone with the coercion it
   * selected. Keeping it would leave a caller passing a value nothing reads, which is how a withdrawn
   * decision quietly comes back.
   * @param {AccountUpdateFieldName} field - The form member being edited.
   * @param {string} entry - The value the control reported, stored unchanged.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function recordEntry(field: AccountUpdateFieldName, entry: string): void {
    /*
     * WHY : Assumptions: an edit invalidates any outcome still in flight, because the answer would be
     *       about a submission the operator has left behind. This is what makes the guard cover more
     *       than request-versus-request: without it a validation verdict for the values as they were
     *       could still advance the screen after they had changed.
     */
    invalidateTurnsInFlight();

    /*
     * WHY : ⚠️ Refactoring Rationale: the field's own refusal is CLEARED as soon as it is edited. It was
     *       retained until the next turn answered, and combined with the value being forced to the blank
     *       marker that made a correction impossible to make: the control displayed the marker whatever
     *       the operator typed, so each keystroke arrived as the marker plus the new character and was
     *       recorded into the form invisibly -- the submitted value silently acquired an asterisk
     *       prefix while the screen showed nothing but the asterisk. Clearing the entry is also what the
     *       reference does, though it does it differently: its marker lives in the field's OUTPUT
     *       subfield and the terminal sends back whatever the operator typed over it, so the marker
     *       cannot survive an edit there either.
     * WHY : Trade-offs: the refusal SENTENCE disappears from the control as soon as editing begins,
     *       before the correction has been judged. The alternative -- keeping it until the next verdict
     *       -- leaves a stale refusal attached to a value it was never about, which is worse: the
     *       operator cannot tell whether the sentence describes what they have now typed. The band
     *       still carries the screen-level sentence, so the outcome is not silent.
     */
    if (fieldErrors.has(field)) {
      setFieldErrors(
        /**
         * Drops this field's entry, leaving every other refusal in place.
         * @param {ReadonlyMap<string, FieldError>} previous - The refusals as the last turn left them.
         * @returns {ReadonlyMap<string, FieldError>} The refusals without this field's.
         */
        (previous: ReadonlyMap<string, FieldError>): ReadonlyMap<string, FieldError> => {
          const remaining = new Map(previous);
          remaining.delete(field);
          return remaining;
        },
      );
    }

    /*
     * WHY : ⚠️ Refactoring Rationale: a leading blank MARKER is stripped from the entry, and without this
     *       the marker became data on the operator's very first keystroke. The refused control DISPLAYS
     *       the asterisk -- `app/cpy/CSSETATY.cpy` L23-L26 moves it into the field's own OUTPUT subfield,
     *       so the terminal shows it inside the field -- and a browser's caret sits after the displayed
     *       text rather than at the field's first column, so typing `9` over a displayed `*` produced
     *       `*9` where a terminal produces `9`. Clearing the refusal above makes the field editable
     *       again; it cannot un-capture a character the change event has already carried.
     * WHY : Assumptions: only a LEADING run is stripped, and only while this field is the one carrying a
     *       blank refusal. A marker cannot appear anywhere else in a displayed value -- the render
     *       substitutes the marker for the whole value or not at all -- so a trailing or interior
     *       asterisk is the operator's own input and is left alone.
     * WHY : Assumptions: the fields in {@link MARKER_BEARING_FIELDS} are exempt, because their marker is
     *       rendered beside the control and never enters the displayed value, so an asterisk typed there
     *       is a real instruction to the service rather than a marker being typed over. That is the same
     *       exception, for the same reason, that decides where the marker is painted.
     */
    const typedOverMarker =
      fieldErrors.get(field)?.state === 'BLANK' &&
      !MARKER_BEARING_FIELDS.has(field) &&
      entry.startsWith(FIELD_ERROR_TOKENS.blankMarker);
    const overwritten = typedOverMarker
      ? entry.replace(new RegExp(`^\\${FIELD_ERROR_TOKENS.blankMarker}+`, 'u'), '')
      : entry;

    /*
     * WHY : ⚠️ Refactoring Rationale: the entry is recorded VERBATIM, and a `numeric ? digitsOnly(entry)`
     *       coercion stood here defending itself on the ground that "the mapset declares the identifier,
     *       date part, credit-score and phone-part fields right-justified numeric entry". It does not:
     *       `app/bms/COACTUP.bms` declares `ATTRB=...NUM` on ZERO of its 128 fields -- the credit score is
     *       `ATTRB=(UNPROT)` with an underline highlight and nothing else -- so the terminal accepts any
     *       character into any of them and `1245-EDIT-NUM-REQD` is what reports a non-numeric one. The
     *       withdrawal, its consequence and the two rejected alternatives are recorded in full where the
     *       coercion's own declaration used to live; this site is where it was still being applied.
     */
    /*
     * WHY : ⚠️ Refactoring Rationale: the entry is clamped to the field's DECLARED width here, where the
     *       control's `maxLength` was the only limit and this line read `const accepted = overwritten`.
     *       The clamp's own declaration records why `maxLength` alone is not enough -- it counts UTF-16
     *       units, which is neither of the units a `PIC X(n)` field is declared in -- and this is the
     *       single funnel every control's entry passes through, so applying it here covers all 43
     *       rather than each render site remembering to.
     * WHY : Assumptions: no character CLASS is filtered, which is unchanged. A stale comment on this
     *       site claimed the stored value was "narrowed" to digits for the identifier, date-part,
     *       credit-score and telephone-part fields; it described a coercion that had already been
     *       withdrawn, and the withdrawal's own reasoning stands where the coercion was declared --
     *       `app/bms/COACTUP.bms` puts `ATTRB=...NUM` on none of its 128 fields, so the terminal
     *       accepts any character into any of them and `1245-EDIT-NUM-REQD` is what refuses one. Length
     *       is a property of the FIELD and is enforced by the terminal; character class is a property
     *       of the EDIT and is refused by the program. Only the first is done here.
     */
    const accepted = clampToDeclaredWidth(overwritten, ACCOUNT_UPDATE_FIELD_WIDTHS[field]);
    setValues(
      /**
       * Replaces one member of the previous form values.
       * @param {AccountUpdateFormValues} previous - The form as the last render left it.
       * @returns {AccountUpdateFormValues} The form with this one member replaced.
       */
      (previous: AccountUpdateFormValues): AccountUpdateFormValues => ({
        ...previous,
        [field]: accepted,
      }),
    );
  }

  /**
   * Reads the account named in the filter, which is the Enter arm of the not-fetched action.
   *
   * Assumptions: the filter is edited before any request is issued, because that is the ONE validation
   * the reference performs before a read -- `1210-EDIT-ACCOUNT`, reached from the not-fetched branch
   * of `1200-EDIT-MAP-INPUTS`. Its refusal marks the control and states the sentence without a round
   * trip; the service applies the same edit again and is the authority.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function fetchAccount(): void {
    const refusal = accountFilterError(values.accountId);
    if (refusal !== null) {
      setFieldErrors(indexFieldErrors([refusal]));
      report(refusal.message, 'error');
      return;
    }

    readThenShowDetails('ENTER');
  }

  /**
   * Reads the account currently named in the filter and moves to the details action.
   *
   * Assumptions: the information line the reference states on this turn is
   * `Update account details presented above.` rather than `Details of selected account shown above` --
   * `3250-SETUP-INFOMSG` selects the former for the show-details action, and the latter is selected by
   * the account-view screen instead.
   *
   * Assumptions: the rejection arm is a genuine safety net rather than a formality, and the reader it
   * calls is what makes it so. `readAccount` reports every refused read itself and resolves `false`, so
   * this arm is reached only by something escaping that reader -- and reporting the read-failure
   * sentence there leaves the operator with a stated outcome instead of a screen that silently never
   * populated.
   * @param {ReadTurnOwner} owner - Which key's arm is performing this turn, handed straight to the
   *   reader so the busy affordance names the control the operator pressed.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function readThenShowDetails(owner: ReadTurnOwner): void {
    readAccount(values.accountId, owner).then(
      /**
       * Moves to the details action once the read has seeded the form.
       * @param {boolean} read - Whether the read succeeded.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (read: boolean): void => {
        if (read) {
          enterAction('SHOW_DETAILS');
        }
      },
      /**
       * Reports a failure that escaped the reader's own handling.
       * @param {unknown} failure - The caught value from the read call.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (failure: unknown): void => {
        report(readFailureMessage(failure), 'error');
      },
    );
  }

  /*
   * WHY : Assumptions: the two-turn confirmation is preserved rather than collapsed into one
   *       submission, because `Changes validated.Press F5 to save` is a DISTINCT observable state --
   *       `88 ACUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'` -- and not a cosmetic extra step. Collapsing
   *       the turns would make Enter and F5 do the same thing, leave the mapset's two non-display
   *       legend fields with nothing to reveal, protect no field between the validation and the write,
   *       and drop three sentences an operator reads: the confirmation prompt, the no-change refusal
   *       and the committed acknowledgement.
   * WHY : ⚠️ Refactoring Rationale: preserving the two turns is not the same as validating on the first
   *       one, and only the first of those was true here. The Enter turn announced
   *       `Changes validated.Press F5 to save` and protected the form having run no field edit at all, so
   *       the state was observable and the words were wrong: the thirty-six edits ran on the WRITE turn,
   *       which is where the refusals arrived -- after the operator had been instructed to press the save
   *       key. The turn now calls the service's own non-writing check, so the sentence is earned.
   */

  /**
   * Runs every service-side edit and asks for confirmation, which is the Enter arm of the details action.
   *
   * Assumptions: this writes nothing at all, and the operation it calls writes nothing either --
   * `POST /api/v1/accounts/update/validate` loads the two rows, runs the edits and returns.
   * `2000-DECIDE-ACTION`'s show-details arm at `app/cbl/COACTUPC.cbl` L2582 to L2590 moves to the
   * confirmation action when the edits passed and something changed, and otherwise leaves the action
   * where it is; only the later F5 turn performs `9600-WRITE-PROCESSING`.
   *
   * Refactoring Rationale: it used to move to the confirmation action having checked only that a record
   * had been read and that something had changed -- see the guard below for what that cost. The move now
   * happens on the service's answer.
   *
   * Assumptions: an unchanged submission is refused with the reference's own sentence and the action
   * does not move, which `1205-COMPARE-OLD-NEW` reaching L1769 produces. That sentence goes to the
   * MESSAGE channel rather than the information channel, because the reference latches it into
   * `WS-RETURN-MSG`; the validation acknowledgement goes to the information channel for the same reason
   * in reverse, since `3250-SETUP-INFOMSG` derives that one from the action.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function validateEdits(): void {
    if (baseline === null) {
      report(MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text, 'error');
      return;
    }

    /*
     * WHY : ⚠️ Refactoring Rationale: the verdict is now obtained from the SERVICE before the screen
     *       advances, where this turn previously advanced on the strength of two local checks -- that a
     *       record had been fetched, and that something had changed -- and no business rule at all. All
     *       twenty-four edits ran only during the later write, so an operator was shown
     *       `Changes validated.Press F5 to save` for a submission that had been validated by nothing,
     *       and the refusals then arrived one turn later than the sentence promising there were none.
     *       The reference does not work that way: `2000-DECIDE-ACTION`'s show-details arm reads
     *       `IF INPUT-ERROR OR NO-CHANGES-DETECTED` and performs `CONTINUE`, leaving the action where
     *       it is, and only otherwise sets `ACUP-CHANGES-OK-NOT-CONFIRMED`
     *       (`app/cbl/COACTUPC.cbl` L2584 to L2591) -- so the edits have already run when that
     *       decision is taken.
     * WHY : Alternatives Considered: reproducing the twenty-four rules in the browser, which would put
     *       the authority in two places and let them disagree; and calling the WRITE on this turn,
     *       which is the one thing the first turn must not do. The service publishes a no-write
     *       validation operation for exactly this turn, so neither compromise is needed.
     * WHY : Assumptions: the local no-change check is KEPT as well, and it runs first. It answers
     *       without a round trip in the case an operator reaches most often -- pressing Enter having
     *       changed nothing -- and the service reports the same condition independently in
     *       `noChangesFound`, so the two agree rather than compete. Dropping it would add a request per
     *       accidental Enter; trusting it alone is what this fix removes.
     */
    if (!hasChanges(values, baseline)) {
      setFieldErrors(new Map());
      report(MESSAGES.NO_CHANGES_DETECTED.text, 'error');
      /*
       * WHY : ⚠️ Assumptions: the no-change refusal places the cursor on the account status field, and
       *       it is the SECOND arm of the same table the fetch turn's placement comes from --
       *       `WHEN NO-CHANGES-DETECTED` shares the `MOVE -1 TO ACSTTUSL` at `app/cbl/COACTUPC.cbl`
       *       L3008 to L3011 with `WHEN FOUND-ACCOUNT-DATA`. Both are outcomes that leave the whole
       *       record editable with no field singled out, so the reference returns the operator to the
       *       first editable field rather than to nothing. All three places this refusal is published
       *       set it, because the reference's table is keyed on the sentence and not on which path
       *       produced it.
       */
      setCursorField('activeStatus');
      return;
    }

    const token = beginTurn();
    setValidating(true);

    validateAccountUpdate(updateRequestFrom(values)).then(
      /**
       * Advances only when the service reports nothing refused and something changed.
       *
       * Assumptions: the decision is taken from `inputError` and `noChangesFound` rather than from the
       * entry array being empty, because a no-change verdict carries no entries either -- so an
       * emptiness test would advance on the one outcome the reference explicitly refuses to advance on.
       * @param {AccountUpdateValidationResponse} verdict - The verdict those edits reached.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (verdict: AccountUpdateValidationResponse): void => {
        if (!isCurrentTurn(token)) {
          return;
        }
        setValidating(false);
        const refusals = indexFieldErrors(verdict.fieldErrors);
        setFieldErrors(refusals);

        /*
         * WHY : Assumptions: the accepted amounts are re-masked on this turn, which is where the
         *       reference re-applies its own edit field. A refused amount is left exactly as the
         *       operator typed it, so the value they are being asked to correct is the value they see.
         */
        setValues(
          /**
           * Re-masks the accepted amounts in the form as the verdict left it.
           * @param {AccountUpdateFormValues} previous - The form at the time the verdict arrived.
           * @returns {AccountUpdateFormValues} The form with accepted amounts re-masked.
           */
          (previous: AccountUpdateFormValues): AccountUpdateFormValues =>
            remaskAcceptedAmounts(previous, refusals),
        );

        if (verdict.inputError) {
          report(verdict.message ?? MESSAGES.INFORM_FAILURE.text, 'error');
          enterAction('CHANGES_NOT_OK');
          return;
        }
        if (verdict.noChangesFound) {
          report(verdict.message ?? MESSAGES.NO_CHANGES_DETECTED.text, 'error');
          // WHY : Assumptions: the same cursor arm as the local no-change refusal above, for the same
          //       reason and from the same table line.
          setCursorField('activeStatus');
          return;
        }
        enterAction('CHANGES_OK_NOT_CONFIRMED');
      },
      /**
       * Reports a validation request that could not be answered, leaving the action where it is.
       *
       * Assumptions: the action does NOT advance on a transport failure, which is the same disposition
       * a refusal gets. An unanswered question is not a passed validation, and advancing would show the
       * confirmation prompt on the strength of a request that never completed.
       * @param {unknown} reason - The value the request rejected with.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (reason: unknown): void => {
        if (!isCurrentTurn(token)) {
          return;
        }
        setValidating(false);

        /*
         * WHY : Assumptions: the shared classifier is reused rather than a second one written here, so
         *       a refused request reports the same sentence on this turn as it does on the write. It
         *       returns an action as well, and that member is deliberately IGNORED: its actions are
         *       the write's outcomes, and this turn has not written, so adopting one would move the
         *       screen to a state that misdescribes what happened.
         */
        const rejection = classifySaveRejection(reason);
        setFieldErrors(indexFieldErrors(rejection.fieldErrors));
        report(rejection.statement.message, 'error');
      },
    );
  }

  /**
   * Writes the confirmed edits, which is the F5 arm of the confirmation action.
   *
   * Refactoring Rationale: the revision the read returned is submitted as the precondition, and HTTP
   * 409 surfaces as the verbatim `Record changed by some one else. Please review` rather than as a
   * generic failure. What is being replaced is the reference's own before-image comparison: it
   * snapshots the whole pre-edit pair into `ACUP-OLD-DETAILS` and re-compares it under an update lock
   * in `9700-CHECK-CHANGE-IN-REC` before rewriting either record. That comparison exists precisely
   * BECAUSE the read-for-update lock was never held across the operator's think time -- a terminal
   * turn ended between the read and the write -- so replacing it with a version column loses nothing
   * that was ever guaranteed, and gains the check being made by the same transaction that writes.
   * Treating the refusal as its own case is what keeps the specific sentence, where a generic failure
   * would drop behaviour the parity requirement protects.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function saveEdits(): void {
    /*
     * WHY : Assumptions: a second confirmation arriving while a write is in flight is ignored. The guard
     *       is kept even though the F5 binding is now also marked `disabled` while saving, because
     *       `invoke` can be called from the legend bar and the pointer control has its own path; the
     *       reference needs neither, because a terminal turn is serialised.
     *       Refactoring Rationale: this used to be the ONLY guard, and its note argued a disabled binding
     *       was unusable here because this screen's invalid-key channel coerces to the Enter arm. The
     *       argument was right about the coercion and wrong about the conclusion: the coercion is now
     *       suppressed for the `disabled` reason at its source, so the binding can express unavailability
     *       and the legend can render it, instead of staying lit while the handler silently returned.
     */
    if (saving) {
      return;
    }
    setConfirmingSave(false);
    /*
     * WHY : ⚠️ Refactoring Rationale: this guard now reports the reference's own read-failure sentence
     *       and no longer reports `No input received`, which was false of every screen that could reach
     *       it. A revision is absent only when no record was read -- the reader refuses the one answer
     *       that arrives without an entity tag, so this state is now unreachable through the read path
     *       and the guard is a safety net rather than a route an operator takes. It is kept because the
     *       write cannot form its precondition without one, and a silent return would leave a pressed
     *       key with no outcome at all; the sentence names the record's absence, which is the condition,
     *       instead of naming absent input, which is not.
     *       Assumptions: the sentence is `DID-NOT-FIND-ACCT-IN-ACCTDAT` at `app/cbl/COACTUPC.cbl` L500
     *       rather than the customer-miss sentence, because what this guard establishes is that NO
     *       record stands behind the form -- the read arm above states the customer miss itself, where
     *       it knows the miss is the reason.
     */
    if (revision === null) {
      report(MESSAGES.DID_NOT_FIND_ACCT_IN_ACCTDAT.text, 'error');
      return;
    }

    const token = beginTurn();
    setSaving(true);
    updateAccount(updateRequestFrom(values), revision)
      .then(
        /**
         * Redisplays the state as stored and acknowledges the write.
         *
         * Assumptions: this path answers 200 in THREE distinguishable ways and each gets its own arm,
         * because the service latches two of the reference's own texts here. A response carrying field
         * refusals is a refusal; a response carrying the no-change sentence performed no write at all
         * and must not be announced as one; anything else committed. Collapsing the last two -- by
         * rendering `returnMessage` whenever the response supplies it -- would report `Looks Good....
         * so far`, the reference's VALIDATION acknowledgement, in place of its commit confirmation, and
         * would also claim a commit on the turn the service found nothing to write.
         * @param {{ account: AccountUpdateResponse; revision: string | null }} applied - The stored
         *   state and the new revision, as the update operation returned them.
         * @param {AccountUpdateResponse} applied.account - Both records as stored, the two message
         *   channels and the per-field error array.
         * @param {string | null} applied.revision - The revision a consecutive edit must submit, or
         *   `null` when the response carried no entity tag.
         * @returns {void} Completion is represented by the screen's own state.
         */
        (applied: {
          readonly account: AccountUpdateResponse;
          readonly revision: string | null;
        }) => {
          /*
           * WHY : Assumptions: the outstanding-request flag is cleared UNCONDITIONALLY and the outcome is
           *       gated behind the turn check, for the reason recorded on the read's own settlement arm:
           *       the flag describes this screen's in-flight request, so leaving it set on a superseded
           *       turn would disable the function keys for good. Everything after the gate reseeds the
           *       form and moves the action, which a superseded write must not do.
           */
          setSaving(false);
          if (!isCurrentTurn(token)) {
            return;
          }
          const stored = formValuesFromApplied(applied.account);
          setValues(stored);
          setBaseline(stored);
          setRevision(applied.revision);
          setFieldErrors(indexFieldErrors(applied.account.fieldErrors));
          if (applied.account.fieldErrors.length > 0) {
            /*
             * WHY : ⚠️ Refactoring Rationale: the action move goes through `enterAction`, so the row-22
             *       prompt for the failed action is stated alongside the refusal instead of being replaced
             *       by it, and the message channel carries the response's own sentence with NO fallback.
             *       The fallback used to substitute the information line when the response omitted one,
             *       which was necessary while a single band had to say something and is wrong now: it
             *       would paint the identical sentence into rows 22 and 23 at once. A response naming
             *       failing fields always latches a sentence -- `refuseWhenAnyEditFailed` carries the
             *       first refusal's wording -- so the null case clears the channel rather than inventing
             *       text for it.
             */
            enterAction('CHANGES_NOT_OK');
            report(applied.account.returnMessage ?? null, 'error');
            return;
          }
          /*
           * WHY : Assumptions: a 200 whose message line is the no-change sentence WROTE NOTHING, so it
           *       returns to showing details rather than to the committed action. The service runs its
           *       own field-by-field comparison and can reach that answer on a turn this screen's local
           *       comparison passed -- a value differing only in padding is the case that survives one
           *       and not the other -- and the reference keeps the show-details action for it at
           *       `app/cbl/COACTUPC.cbl` L1769 rather than advancing. Reporting it as a commit would
           *       tell the operator their edit was stored when no row changed.
           */
          if (applied.account.returnMessage === MESSAGES.NO_CHANGES_DETECTED.text) {
            enterAction('SHOW_DETAILS');
            report(MESSAGES.NO_CHANGES_DETECTED.text, 'error');
            // WHY : Assumptions: the same cursor arm as the two no-change refusals above, for the same
            //       reason and from the same table line.
            setCursorField('activeStatus');
            return;
          }

          /*
           * WHY : ⚠️ Refactoring Rationale: BOTH sentences are painted, each on the channel the reference
           *       puts it on, where the screen used to paint only the commit confirmation. The service
           *       answers a stored write with `Looks Good.... so far`, which is the reference's own text
           *       and belongs to its VALIDATION step -- `WS-RETURN-MSG` set by `1200-EDIT-MAP-INPUTS`
           *       when every edit passed -- while the sentence the reference paints once a write has
           *       committed is the information line `3250-SETUP-INFOMSG` selects for
           *       `ACUP-CHANGES-OKAYED-AND-DONE`. The terminal showed both at once in two separate
           *       fields; this screen now has both fields, so choosing between them is no longer
           *       necessary and no longer correct. `enterAction` states the confirmation on row 22 and
           *       the response's own sentence goes to row 23.
           * WHY : Assumptions: the row-23 sentence is taken from the RESPONSE rather than restated from a
           *       constant, so the acknowledgement an operator reads is the one the service actually
           *       latched. Its appearance is `success` rather than `error` even though the mapset paints
           *       `ERRMSG` `COLOR=RED` unconditionally, because this is the one sentence that channel
           *       carries which is not a refusal, and rendering an acknowledgement in the refusal colour
           *       would tell an operator their committed write had failed.
           */
          enterAction('CHANGES_OKAYED_AND_DONE');
          report(applied.account.returnMessage ?? null, 'success');
        },
        /**
         * Classifies the refusal and moves to the action the reference sets for it.
         * @param {unknown} failure - The caught value from the update call.
         * @returns {void} Completion is represented by the screen's own state.
         */
        (failure: unknown) => {
          /*
           * WHY : Assumptions: the outstanding-request flag is cleared UNCONDITIONALLY and the outcome is
           *       gated behind the turn check, for the reason recorded on the read's own settlement arm:
           *       the flag describes this screen's in-flight request, so leaving it set on a superseded
           *       turn would disable the function keys for good. Everything after the gate reseeds the
           *       form and moves the action, which a superseded write must not do.
           */
          setSaving(false);
          if (!isCurrentTurn(token)) {
            return;
          }
          const rejection = classifySaveRejection(failure);
          const writeRefusals = indexFieldErrors(rejection.fieldErrors);
          setFieldErrors(writeRefusals);
          /*
           * WHY : ⚠️ Refactoring Rationale: the amounts are RE-DISPLAYED on this path too, and only the
           *       validation path did it. `3203-SHOW-UPDATED-VALUES` runs before every send and is not
           *       specific to which step produced the flags, so a write refusal left the accepted amounts
           *       unmasked and the refused one decorated with the mask an earlier accepted turn had
           *       applied -- the exact inverse of the reference on both counts.
           */
          setValues(
            /**
             * Re-displays the amounts as the write's own verdict leaves them.
             * @param {AccountUpdateFormValues} previous - The form at the time the refusal arrived.
             * @returns {AccountUpdateFormValues} The form with each amount displayed for its own flag.
             */
            (previous: AccountUpdateFormValues): AccountUpdateFormValues =>
              remaskAcceptedAmounts(previous, writeRefusals),
          );

          /*
           * WHY : ⚠️ Refactoring Rationale: the action move goes through `enterAction`, so the row-22
           *       prompt for the action the reference sets is stated as well as the refusal. On the
           *       concurrency path that matters most: `2000-DECIDE-ACTION` sets the show-details action, so
           *       the terminal paints `Update account details presented above.` on row 22 while
           *       `WS-RETURN-MSG` holds the changed-record sentence on row 23 -- the second says what went
           *       wrong and the first says what the values on screen now are. Setting the action alone left
           *       the previous turn's prompt standing beside the new refusal.
           */
          enterAction(rejection.action);
          report(rejection.statement.message, rejection.statement.severity);
          /*
           * WHY : ⚠️ Refactoring Rationale: a concurrency refusal now leaves the form EXACTLY as the
           *       operator typed it, where it used to re-read the record and reseed every control from
           *       the answer. A responsive review drove one and measured the result: 42 of 43 controls
           *       came back empty, `F5=Save` and `F12=Cancel` were both withdrawn, and the sentence
           *       asking the operator to review a changed record stood over a blank form -- so the turn
           *       destroyed the work it was reporting on and offered no way to redo it. Both halves of
           *       that were the re-read's doing: `readAccount`'s own failure arm blanks every value but
           *       the account identifier, and its SUCCESS arm reseeds from the answer, so the edit was
           *       lost whichever way the second request settled.
           * WHY : ⚠️ Assumptions: not re-reading is what the reference does, and the citation is in
           *       {@link classifySaveRejection}: the concurrency arm of `2000-DECIDE-ACTION` at
           *       `app/cbl/COACTUPC.cbl` L2610 to L2612 sets the show-details action and the sentence and
           *       performs no read, and `9600-WRITE-PROCESSING` at L3947 to L3951 leaves its
           *       `ACUP-OLD-*` before-image untouched on the way out. Deleting the re-read therefore
           *       removes a browser invention rather than dropping a transcribed step.
           * WHY : ⚠️ Trade-offs: the values left standing are the operator's EDITS, where the reference
           *       would repaint its captured before-image through `3202-SHOW-ORIGINAL-VALUES`. This is a
           *       deliberate divergence and the trade is stated plainly: a terminal operator re-keyed a
           *       24-row screen from a printed source and the reference could afford to discard their
           *       entry, whereas discarding typed work in a browser is irreversible and is what the
           *       review reported as the defect. The record as it now stands remains ONE keystroke away
           *       -- `F12=Cancel` re-reads it -- so nothing the reference showed has become unreachable.
           * WHY : Alternatives Considered: silently refreshing the revision from a background read while
           *       keeping the operator's values, which would let the very next save succeed. Rejected as
           *       unsafe: the operator would overwrite a change they were told about but never SAW, and
           *       neither the reference nor this screen ever admits a write whose concurrency token came
           *       from a record the operator had not been shown. The cancel turn is the path that shows
           *       it to them, and it is now labelled.
           */
          setConcurrencyRefused(rejection.concurrency);
        },
      )
      /*
       * WHY : ⚠️ Assumptions: the four members carrying a personal identifier IN THE CLEAR are blanked
       *       however the write settles, and this is a security obligation the type they belong to states
       *       outright: `SensitiveAccountUpdateFields` in `ui/src/api/types.ts` records that no read
       *       operation returns any of them, so a value assigned there is the only place in the browser it
       *       exists, and that "a screen must clear these fields after a submission resolves rather than
       *       retaining them to prefill a retry". The REJECTION arm is what was missing: it leaves every
       *       other typed value on screen so the operator can correct one field, and it used to leave the
       *       three identifier parts and the government-issued identifier live in React state and in four
       *       mounted controls for as long as the screen stayed open.
       * WHY : Assumptions: it runs in a settlement callback rather than in each arm, so no future arm can
       *       be added without it. The fulfilment arm already re-seeds every value from the stored answer,
       *       which blanks these four as well -- `formValuesFrom` seeds them empty -- so this is that
       *       arm's belt and the rejection arm's only braces.
       * WHY : Trade-offs: an operator retrying after a refusal must re-type the identifier they had
       *       supplied, which is deliberate. The alternative is holding a national identifier in memory
       *       across an indefinite correction cycle to save four keystrokes, and the service reads an
       *       absent member as PRESERVE, so a retry that does not re-supply one changes nothing rather
       *       than clearing anything.
       */
      .finally(clearSubmittedIdentifiers);
  }

  /**
   * Blanks the four members that carry a personal identifier in the clear.
   *
   * Assumptions: the two masked markers in `protectedValues` are NOT touched, because they disclose
   * nothing and are what tells the operator that leaving a box empty preserves the stored value. Only
   * the members `updateRequestFrom` would submit are cleared.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function clearSubmittedIdentifiers(): void {
    setValues(
      /**
       * Replaces the four identifier members of the previous form values with blanks.
       * @param {AccountUpdateFormValues} previous - The form as the last render left it.
       * @returns {AccountUpdateFormValues} The form with the four identifier members blank.
       */
      (previous: AccountUpdateFormValues): AccountUpdateFormValues => ({
        ...previous,
        ssnPart1: '',
        ssnPart2: '',
        ssnPart3: '',
        governmentIssuedId: '',
      }),
    );
  }

  /**
   * Discards uncommitted edits and redisplays the stored record, which is the F12 arm.
   *
   * Assumptions: the reference RE-READS rather than restoring its snapshot -- `2000-DECIDE-ACTION`'s
   * PF12 arm clears the message and performs `9000-READ-ACCT` again before setting the show-details
   * action -- so a cancel shows the record as it stands now, including a change another user has
   * committed while this operator was typing.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function cancelEdits(): void {
    clearBandForNewTurn();
    readThenShowDetails('PFK12');
  }

  /*
   * WHY : Refactoring Rationale: this function was also named `beginTurn`, colliding with the
   *       turn-TOKEN generator above it -- two different concerns under one name, and only one of them
   *       could be called. They are both kept because both are real: the generator seals a turn so a
   *       settled request from a superseded one can be discarded, while this one clears the message
   *       band so the previous turn's sentence does not stand beside this turn's outcome. The name
   *       states which of the two this is.
   */
  /**
   * Clears the MESSAGE channel, which every turn does before it decides anything.
   *
   * ⚠️ Purpose: this is the reference's own first act on every task. `app/cbl/COACTUPC.cbl` L873 to
   * L876 carries the comment `Ensure error message is cleared` and performs
   * `SET WS-RETURN-MSG-OFF TO TRUE` in `MAIN-PARA`, before the commarea is even examined -- so the
   * row-23 line a task paints is always the one THAT task computed, never an inheritance.
   *
   * ⚠️ Refactoring Rationale: the channel used to be cleared on the cancel turn alone. Every other
   * turn wrote it only when it had something to say, so a turn that SUCCEEDED left the previous
   * turn's refusal standing: a failed lookup followed by a good one showed the freshly-loaded record
   * beneath `Did not find this account in account card xref file`, in a band whose ARIA role asserts
   * it. Clearing per turn rather than on each success path is both the reference's shape and the
   * narrower fix -- a success path added later cannot forget to do it.
   *
   * Assumptions: only the MESSAGE channel is cleared. The information channel is DERIVED from the
   * change action by `enterAction`, exactly as `3250-SETUP-INFOMSG` derives it on every send, so it
   * is never stale and clearing it would blank row 22 for the rest of the turn.
   *
   * ⚠️ Assumptions: the concurrency standing is cleared HERE too, and it belongs here for the same
   * reason the sentence does -- it describes the turn that produced it, and the reference's own first
   * act on every task is to drop what the previous task latched. Clearing it in each arm that settles
   * a turn instead would leave the arms that settle none -- the cancel turn among them -- advertising
   * a discard for an edit that had already been discarded.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function clearBandForNewTurn(): void {
    report(null, 'error');
    setConcurrencyRefused(false);
    /*
     * WHY : ⚠️ Assumptions: the cursor target is dropped here for the same reason the sentence is --
     *       it describes the turn that produced it. It also has to be dropped BEFORE the new turn
     *       settles: the effect that acts on it also watches the refusal map, so a target left over
     *       from a fetch would win against the refusals of the turn after it and pull the cursor off
     *       the field the operator has to correct.
     */
    setCursorField(null);
  }

  /**
   * Runs the Enter arm for whichever action the screen is in.
   *
   * Assumptions: the arms are `2000-DECIDE-ACTION`'s outer `EVALUATE`, and the two failure actions
   * plus the committed action all reset the screen to its opening state on the next turn -- which the
   * reference does in its main branch at `app/cbl/COACTUPC.cbl` L968 to L989 by re-initialising its
   * work areas and setting the not-fetched action. The final arm is the branch the reference itself
   * treats as unreachable and abends on.
   * @returns {void} Completion is represented by the screen's own state, and nothing happens at all
   *   while a request is outstanding.
   */
  function processEnter(): void {
    /*
     * WHY : ⚠️ Refactoring Rationale: the arm declines itself while a request is outstanding, and it did
     *       not -- the key DESCRIPTOR declined, which is a different thing, because two of the three
     *       ways into this arm never pass through it. The form's own `onFinish` is one: an Enter pressed
     *       inside a field is claimed by the focused control, so `ui/src/layout/usePfKeys.ts` returns
     *       before its handler lookup and the browser's native submit runs this directly. The
     *       invalid-key coercion above is the other. The guard therefore belongs at the arm, where all
     *       three paths meet.
     * WHY : ⚠️ Assumptions: the whole in-flight flag is tested rather than Enter's own share of it. A
     *       second Enter would duplicate the request this one already has outstanding, and an Enter
     *       during ANOTHER key's turn would settle this screen's action over an answer still arriving --
     *       which is the race `beginTurn` exists to catch and this declines before it starts.
     * WHY : ⚠️ Assumptions: it returns SILENTLY, before `clearBandForNewTurn`. A press that starts no turn
     *       must not blank the sentence the outstanding turn is about to answer with, and it must not
     *       report anything either: this is the 3270 input-inhibit state, where a key pressed while the
     *       terminal was working was absorbed rather than refused, which is the same treatment the busy
     *       affordance on the key gives the descriptor path.
     */
    if (turnInFlight) {
      return;
    }

    clearBandForNewTurn();

    switch (action) {
      case 'DETAILS_NOT_FETCHED':
        fetchAccount();
        return;
      case 'SHOW_DETAILS':
      case 'CHANGES_NOT_OK':
        validateEdits();
        return;
      case 'CHANGES_OK_NOT_CONFIRMED':
        /*
         * WHY : Assumptions: Enter on the confirmation turn does NOTHING, which is the reference's
         *       `WHEN ACUP-CHANGES-OK-NOT-CONFIRMED ... CONTINUE` arm at `app/cbl/COACTUPC.cbl`
         *       L2618 to L2620. It is a distinct arm from the one above it, which requires PF5 as
         *       well, so pressing Enter re-sends the same protected screen with the same prompt --
         *       the operator must press F5 or F12.
         */
        setInformation(informationLineFor(action));
        return;
      case 'CHANGES_OKAYED_AND_DONE':
      case 'CHANGES_OKAYED_LOCK_ERROR':
      case 'CHANGES_OKAYED_BUT_FAILED':
        setValues(blankFormValues());
        setBaseline(null);
        setRevision(null);
        /*
         * WHY : ⚠️ Assumptions: both holders of the masked identifiers are retired here, where only
         *       `storedIdentifiers` was. This arm returns the screen to its opening state -- the
         *       reference re-initialises its work areas at `app/cbl/COACTUPC.cbl` L968 to L989 -- and a
         *       caption sourced from `protectedValues` outlived that reset, so the blank opening form
         *       carried the committed customer's markers under an empty account field. The caption
         *       renders only when the state is non-null precisely so that an unfetched screen shows
         *       none; leaving it set defeated the guard rather than the guard defeating it.
         */
        setStoredIdentifiers(null);
        setProtectedValues(null);
        setFieldErrors(new Map());
        enterAction('DETAILS_NOT_FETCHED');
        return;
      default:
        setAbended(true);
        return;
    }
  }

  /*
   * WHY : Alternatives Considered: attaching this screen's own `keydown` listener, and importing
   *       `ui/src/hooks/usePagedQuery.ts`. The listener is rejected because `usePfKeys` owns the
   *       attention-identifier vocabulary and the PF13-to-PF24 aliasing that `app/cpy/CSSTRPFY.cpy`
   *       L21 to L78 declares, so a local listener would be a second, narrower normalisation and a
   *       clicked legend button would then dispatch differently from a pressed key. The paging hook is
   *       rejected because it is a browse construct and this screen displays no list -- the mapset
   *       paints no row area and the program performs no `STARTBR`.
   * WHY : Assumptions: PF5 is registered only in the confirmation action and PF12 only once a record
   *       has been read, which is the validity test at `app/cbl/COACTUPC.cbl` L905 to L916 --
   *       `CCARD-AID-PFK05 AND ACUP-CHANGES-OK-NOT-CONFIRMED`, and
   *       `CCARD-AID-PFK12 AND NOT ACUP-DETAILS-NOT-FETCHED`. Registering them unconditionally would
   *       offer a write key on a turn the reference refuses it.
   * WHY : Assumptions: a legend LABEL is separate from a key's VALIDITY, and both are preserved. The
   *       mapset paints `F5=Save` and `F12=Cancel` in non-display fields that
   *       `3390-SETUP-INFOMSG-ATTRS` un-darkens by state, so PF12 is valid across five actions while
   *       its legend appears in four of them -- every action of the changes-made group except the
   *       committed one. An empty label is how `usePfKeys` expresses a handler with no painted legend,
   *       and `PfKeyBar` renders no control for one.
   */
  /**
   * Asks for the save confirmation, which is what the F5 key and the F5 control both do.
   *
   * Assumptions: this is the ONLY way the confirmation opens, so the physical key, the legend button the
   * shell paints from this screen's bindings and the pointer control beside the form all reach the same
   * gate and then the same single write. One advertised key with two behaviours -- a direct write from the
   * keyboard and a confirmation from the pointer -- is what this replaces.
   *
   * Assumptions: the two-turn confirmation the reference already has is NOT what this gate duplicates.
   * That one is a screen turn: Enter validates and paints `Changes validated.Press F5 to save`, and F5
   * writes. This gate is the additive re-key-to-confirm gesture the design system expresses as an inline
   * confirmation, which AAP section 0.4.1.4 names for this screen; what it adds is a second deliberate
   * act on a form of forty editable fields, and what matters is that it is now reached identically
   * whichever control the operator uses.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function requestSaveConfirmation(): void {
    if (saving) {
      return;
    }
    clearBandForNewTurn();
    /*
     * WHY : ⚠️ Assumptions: the anchor is brought onto the display BEFORE the gate is opened, and the
     *       order is the whole of it. The design system measures its anchor at the moment it opens, so
     *       scrolling afterwards would leave the surface positioned against where the anchor used to be.
     *       Refactoring Rationale: a browser pass measured what the absence cost. This screen is the
     *       longest in the application -- its body measured 3567 pixels tall at a 375-pixel width against
     *       a 486-pixel visible band -- and the trigger sits below all forty editable fields, at
     *       `top: 2468`. Taking the turn from the function-key legend needs no pointer and therefore no
     *       scrolling, so the surface opened at `top: 2379`, roughly fifteen hundred pixels below a
     *       900-pixel display, with BOTH answers outside it and the focus placed on a Cancel control the
     *       operator could not see. Pressing the save key produced no visible change at all. The same
     *       surface raised with the trigger in view measured fully inside the display, so the anchor's
     *       position is the whole of it.
     *       Assumptions: `block: 'nearest'` is the least movement that can work and is a no-op when the
     *       anchor is already fully visible -- the pointer operator's case, since they just clicked it --
     *       so a route that never had the defect is not given a jolt to fix it.
     *       Alternatives Considered: leaving it to the design system's own overflow handling.
     *       `autoAdjustOverflow` is already on and cannot help, because an anchor BELOW the display has no
     *       side that is inside it. The sibling `ui/src/screens/transactionAdd/index.tsx` reached the same
     *       conclusion from the same measurement, which is why both screens now scroll the anchor first.
     */
    saveConfirmationAnchor.current?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    setConfirmingSave(true);
  }

  /**
   * Abandons the save confirmation and discards the uncommitted edits, the confirmation's cancel action.
   *
   * Assumptions: cancelling closes the gate AND performs the screen's own cancel turn, because the
   * control is labelled with the reference's `F12=Cancel` legend and that key's arm re-reads the record.
   * A gate whose cancel only closed the gate would carry a label promising something else.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function abandonSaveConfirmation(): void {
    setConfirmingSave(false);
    cancelEdits();
  }

  /*
   * WHY : ⚠️ Purpose: holds the control the save gate anchors itself to, so the gate can be brought onto
   *       the display before it is asked. {@link requestSaveConfirmation} records the measurement that
   *       makes this necessary and why the design system's own overflow handling cannot substitute.
   * WHY : Assumptions: the ref is on the in-content trigger rather than on the legend key, because the
   *       trigger is what the design system positions the surface against -- the legend key opens the
   *       same gate but is not its anchor, so scrolling to the legend would move the view without moving
   *       the surface.
   */
  const saveConfirmationAnchor = useRef<HTMLButtonElement | null>(null);

  const cancelIsValid = action !== 'DETAILS_NOT_FETCHED';
  const saveIsValid = action === 'CHANGES_OK_NOT_CONFIRMED';
  /*
   * WHY : ⚠️ Refactoring Rationale: the cancel legend is painted on a concurrency-refused turn as well
   *       as across the changes-made group, where the group alone used to decide it. The key was ALWAYS
   *       bound on that turn -- `cancelIsValid` holds for every action but the unfetched one, which is
   *       the reference's own test at `app/cbl/COACTUPC.cbl` L905 to L916 -- so what was missing was the
   *       label and not the behaviour, and a bound key with no label is a key an operator cannot find.
   *       A responsive review reported exactly that as the refusal leaving "no way forward".
   * WHY : ⚠️ Trade-offs: the mapset would leave this legend dark on that turn, because
   *       `3390-SETUP-INFOMSG-ATTRS` un-darkens it for the changes-made group and the concurrency
   *       outcome sets show-details instead. The divergence is taken deliberately and it is narrow: a
   *       3270 operator worked from a printed keyboard template on which PF12 was cancel everywhere, so
   *       a dark legend cost them nothing, whereas in a browser the legend bar is the ONLY place a key
   *       is advertised. Painting a label for a handler that already exists adds no behaviour.
   * WHY : Alternatives Considered: ARMING `F5=Save` on that turn too, which the review also asked for.
   *       Declined, and the ordering is the reason: the fields are editable again in show-details, so a
   *       save key on the same turn would write entries no validation step had seen -- the reference
   *       admits PF05 only in the validated action, at L905 to L916, and this screen's own two-turn
   *       ordering exists to keep that true. The save key is re-ARMED on the next Enter, which is the
   *       turn that validates what the operator is about to write. ⚠️ Painting it is a separate question
   *       from arming it and is answered separately, immediately below.
   */
  const cancelLegendIsPainted =
    (isChangesMade(action) && action !== 'CHANGES_OKAYED_AND_DONE') || concurrencyRefused;

  /*
   * WHY : ⚠️ Refactoring Rationale: the save key is PAINTED on a concurrency-refused turn and it used to
   *       be withdrawn from the screen entirely, because its whole descriptor was registered inside
   *       `saveIsValid ? { PFK05: ... } : {}`. A browser review measured the consequence: after the 409
   *       the legend held `F12=Cancel` enabled and `F5=Save` was ABSENT from both the legend and the
   *       in-screen confirmation area. A key that disappears tells the operator the action does not
   *       exist, where the truth is that it is not available on this turn -- and it is the one key that
   *       finishes the work they had just been refused, so its absence is what makes the refusal read as
   *       a dead end.
   * WHY : ⚠️ Assumptions: the remedy is DISABLED and not removed, which is the contract this tree already
   *       states. `ui/src/layout/PfKeyBar.tsx` records it against `app/cbl/COACTUPC.cbl` L905 to L916:
   *       the reference admits an invalid attention key and reduces it to a screen refresh rather than
   *       reporting it, and a disabled control is the closest browser equivalent -- present, labelled,
   *       inert. The BEHAVIOUR the note above declines is unchanged: `disabled` keeps the key from
   *       reaching `requestSaveConfirmation`, so nothing is written and no confirmation can open until an
   *       Enter turn has re-validated the entries.
   * WHY : ⚠️ Trade-offs: the mapset would leave this legend dark on that turn, exactly as it would the
   *       cancel legend -- `app/bms/COACTUP.bms` L498 to L507 declares `FKEY05` and `FKEY12` as
   *       `ATTRB=(ASKIP,DRK)` and `3390-SETUP-INFOMSG-ATTRS` at `app/cbl/COACTUPC.cbl` L3566 to L3584
   *       reveals `FKEY05` only for `PROMPT-FOR-CONFIRMATION`, while the 409 arm at L2607 to L2612 sets
   *       the show-details action. So this is the SAME narrow divergence already declared for the cancel
   *       legend above and taken for the same reason: on a terminal the key was advertised on a printed
   *       template whatever the field's attribute, and in a browser the legend bar is the only place it
   *       is advertised at all. `PfKeyBar` deliberately supports both treatments -- omit the descriptor
   *       for a legend that must vanish, disable it for one that must stay -- so choosing is a stated
   *       decision rather than an invented one.
   * WHY : Assumptions: the predicate is the CANCEL predicate's counterpart and not a copy of it. The
   *       save key is advertised on the turn the mapset reveals it and on the refused turn that follows
   *       a save the operator has already asked for; it is NOT advertised before a record is fetched or
   *       on a turn with nothing pending, because there was never anything for the operator to watch
   *       vanish there and a disabled control on an untouched form advertises work that does not exist.
   */
  const saveLegendIsPainted = saveIsValid || concurrencyRefused;

  /*
   * WHY : ⚠️ Assumptions: the three keys that ISSUE a request stand down while one is outstanding, and
   *       PF3 deliberately does not. Enter, save and cancel each start a turn, so admitting a second
   *       one before the first has answered is how an operator reaches the races the token above
   *       guards against -- the token makes a superseded answer inert, and this makes the ordinary way
   *       of producing one unavailable. PF3 only leaves, which needs no answer and must stay reachable:
   *       a screen that trapped an operator until a slow request finished would be worse than the race.
   * WHY : Alternatives Considered: disabling the FIELDS instead, so no edit could be made mid-turn.
   *       Rejected because the reference's fields are not protected on this turn -- they carry
   *       `ATTRB=(FSET,UNPROT)` throughout -- and because it would make an unmapped key arrive as a
   *       `disabled` rejection, changing the key behaviour to work around a race.
   */
  const keyHandlers: PfKeyHandlerMap = {
    ENTER: {
      /**
       * Runs the arm for the current action, the mapset's `ENTER=Process` action.
       *
       * Assumptions: it is unavailable while EITHER request is in flight. The Enter arm of the details
       * action now issues the validation request, and the arm of the committed actions resets the screen,
       * so allowing a second press mid-flight would either issue a duplicate validation or reset a form
       * whose answer is still arriving.
       */
      onInvoke: processEnter,
      label: ACCOUNT_UPDATE_KEY_LABELS.ENTER,
      /*
       * WHY : ⚠️ Refactoring Rationale: this key reports its own outstanding turn as BUSY where it used
       *       to report it as `disabled`, and the two are different claims. Disabled says the action is
       *       unavailable; busy says it is running -- which is what is true of the key the operator has
       *       just pressed, and which the design system renders as a spinner on that one control while
       *       the others grey. The double-submit the `disabled` flag existed to prevent is still
       *       prevented: `ui/src/layout/usePfKeys.ts` declines a busy key silently and the design
       *       system's own button refuses a click while it is loading.
       * WHY : ⚠️ Assumptions: `disabled` now means "a turn that is NOT mine is outstanding". Enter's arm
       *       must not run while the write it queued is still in the air, and it must not run while the
       *       cancel turn is re-reading -- either would settle a turn over an answer still arriving --
       *       so the exclusion is of the other keys' turns rather than of all of them.
       * WHY : Assumptions: risk is `read-only` because that is what the ACTION does, not what the key
       *       is called. `ENTER=Process` fetches on the not-fetched action and asks the service's
       *       non-writing check on the details actions; the write is behind F5 alone, which is the
       *       ordering `app/cbl/COACTUPC.cbl` L905 to L916 enforces by admitting PF05 only in the
       *       validated action. The visible consequence is intended: the emphasis fallback treats ENTER
       *       as a primary action for a screen that has not opted in, and this screen now leaves the
       *       single primary emphasis on the one control that mutates.
       */
      risk: 'read-only',
      busy: enterTurnIsOutstanding,
      disabled: turnInFlight && !enterTurnIsOutstanding,
    },
    PFK03: {
      /**
       * Leaves the screen, the mapset's `F3=Exit` action.
       *
       * Assumptions: the destination is the main menu. The reference resolves it from the two caller
       * fields of its passed structure and falls back to `LIT-MENUPGM` when both are blank
       * (`app/cbl/COACTUPC.cbl` L925 to L941); AAP section 0.7.1 removes those fields, so the
       * fallback is the only destination that survives and the browser's own history carries the rest.
       */
      onInvoke: () => {
        navigateSafely(navigate, MAIN_MENU_ROUTE);
      },
      label: ACCOUNT_UPDATE_KEY_LABELS.PFK03,
      /*
       * WHY : ⚠️ Refactoring Rationale: the exit key stands down while a WRITE is in flight, and it was
       *       the one key on this screen with no such condition -- Enter, save and cancel all carried one
       *       already. A browser review measured it enabled and undimmed during the mutating request, so
       *       an operator could leave for the menu while the record was being written and never learn
       *       whether it was: the answer arrives at a screen that has unmounted, and the outcome is
       *       neither shown nor recoverable.
       * WHY : ⚠️ Trade-offs: it is gated on the write alone rather than on the combined in-flight flag
       *       the other three keys use, and the difference is deliberate. Leaving during a read or a
       *       validation abandons nothing -- neither mutates -- and the checkpoint's own contract for
       *       this key is that it goes back WITHOUT mutation, so withdrawing it from turns that cannot
       *       mutate would take away an escape while protecting nothing. The narrower gate closes the
       *       measured defect and leaves the operator a way out of every other turn.
       * WHY : Assumptions: risk is `read-only` and no busy channel is opened. This key issues no
       *       request at all -- it navigates -- so it has no turn of its own to report, and a screen
       *       that reported busy here would reserve the affordance's box on a control that can never
       *       fill it.
       */
      risk: 'read-only',
      disabled: saving,
    },
    ...(saveLegendIsPainted
      ? {
          PFK05: {
            /**
             * Asks for the save confirmation, the mapset's `F5=Save` action.
             *
             * Refactoring Rationale: this invoked the write DIRECTLY while the pointer control beside the
             * form opened a confirmation, so the keyboard bypassed a gate the pointer could not. Both now
             * enter through one request and leave through one write.
             */
            onInvoke: requestSaveConfirmation,
            label: ACCOUNT_UPDATE_KEY_LABELS.PFK05,
            /*
             * WHY : ⚠️ Assumptions: the descriptor is registered whenever the legend is painted and its
             *       AVAILABILITY is carried here, which is the disabled-not-removed remedy recorded at
             *       {@link saveLegendIsPainted}. The `!saveIsValid` term is what covers the
             *       concurrency-refused turn -- the only turn on which the legend is painted without the
             *       action being admitted -- and the second term stands the key down while ANOTHER
             *       key's turn is outstanding.
             * WHY : ⚠️ Assumptions: risk is `mutating` because this is the one key on the screen whose
             *       action writes -- `app/bms/COACTUP.bms` L502 paints it `F5=Save` and
             *       `app/cbl/COACTUPC.cbl` reaches its rewrite from this arm alone. The emphasis
             *       primitive resolves `mutating` to the solid primary treatment, so with ENTER now
             *       declared read-only this is the single emphasised control on the bar and the emphasis
             *       marks the consequence rather than the position.
             * WHY : ⚠️ Assumptions: its OWN turn -- the write -- is reported through `busy` rather than
             *       through `disabled`, so the control the operator pressed to commit stays present and
             *       spins instead of greying out at the moment they most need to know it is working.
             *       The second press is still refused, on both the key path and the pointer path.
             */
            risk: 'mutating',
            busy: saving,
            disabled: !saveIsValid || (turnInFlight && !saving),
          },
        }
      : {}),
    ...(cancelIsValid
      ? {
          PFK12: {
            /**
             * Discards uncommitted edits and re-reads, the mapset's `F12=Cancel` action.
             *
             * Assumptions: unavailable while a write is in flight, because it re-reads the record and a
             * re-read racing a write in progress would show the operator whichever state won.
             */
            onInvoke: cancelEdits,
            label: cancelLegendIsPainted ? ACCOUNT_UPDATE_KEY_LABELS.PFK12 : '',
            /*
             * WHY : Assumptions: risk is `read-only`, which is what `F12=Cancel` DOES here rather than
             *       what the word cancel suggests. The reference's PF12 arm at `app/cbl/COACTUPC.cbl`
             *       L2572 to L2580 discards the uncommitted entry and performs `9000-READ-ACCT`, so
             *       nothing is written and nothing durable is destroyed -- the destructive treatment
             *       would assert a consequence this key does not have.
             * WHY : ⚠️ Assumptions: its own re-read is reported through `busy`, which is the reason
             *       {@link readOwner} exists: this key and ENTER reach one reader, so without the owner
             *       a cancel in flight would spin ENTER's control instead of this one.
             */
            risk: 'read-only',
            busy: cancelTurnIsOutstanding,
            disabled: turnInFlight && !cancelTurnIsOutstanding,
          },
        }
      : {}),
  };

  const { bindings, invoke } = usePfKeys(keyHandlers, {
    /**
     * Coerces an unregistered or currently invalid attention key into the Enter arm, silently.
     *
     * Assumptions: THIS screen coerces where others refuse, and the difference is in the source.
     * `app/cbl/COACTUPC.cbl` L905 to L916 sets an invalid flag for any key outside its four and then
     * `SET CCARD-AID-ENTER TO TRUE`, so the invalid-key sentence is never reached here -- unlike the
     * menu programs, which surface `CCDA-MSG-INVALID-KEY` from an explicit `WHEN OTHER`. The hook
     * reports the rejection rather than coercing it itself, and records why: coercing an unknown
     * browser key into a submit inside shared code could perform a write on a screen whose Enter arm
     * writes. On this screen Enter never writes -- the write is behind F5 -- so the coercion is safe
     * exactly here, which is why it is expressed at the screen and not in the hook.
     *
     * Assumptions: it coerces an UNMAPPED key and stays silent for a DISABLED one, which are the two
     * rejections the hook distinguishes. The reasoning for the second is recorded at the guard below.
     * @param {PfKeyRejection} rejection - What the hook could not dispatch, and why.
     * @returns {void} Nothing; the coerced arm runs for its own effects.
     */
    onInvalidKey: (rejection: PfKeyRejection): void => {
      /*
       * WHY : ⚠️ Refactoring Rationale: the coercion is skipped for the `disabled` reason. It is what makes
       *       a disabled binding usable on this screen at all: coercing a suppressed F5 into the Enter arm
       *       would run a screen turn the operator did not ask for -- on the confirmation action, the arm
       *       that merely restates the prompt, and on a committed action, the arm that blanks the form.
       *       The rejection is left silent rather than reported because an unavailable key is not an
       *       invalid one, and `CCDA-MSG-INVALID-KEY` is a sentence this screen's source never reaches.
       */
      if (rejection.reason === 'disabled') {
        return;
      }
      /*
       * WHY : ⚠️ Assumptions: no in-flight guard stands here, because the arm it coerces into carries its
       *       own -- recorded at {@link processEnter}. It matters that the guard is THERE and not here:
       *       this is one of three ways into that arm, and the other two are the key descriptor and the
       *       form's own submit, so a guard on this path alone would leave the other two open.
       */
      processEnter();
    },
  });

  /*
   * WHY : ⚠️ Refactoring Rationale: this screen DELEGATES its title band and its key legend to
   *       the shell instead of painting them itself. `ui/src/layout/AppShell.tsx` is mounted as
   *       the authenticated layout route, so the frame is painted once above the outlet rather
   *       than rebuilt per screen; a screen that also painted them would show two title bands
   *       and two legends. BOTH message rows are delegated below as well -- row 22 and row 23 -- so
   *       exactly one element paints each of the four rows and the two adjacent lines are painted
   *       adjacent, which is the arrangement the mapset declares and the reason recorded at the
   *       removal site.
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
  useShellSlot({
    screen: {
      transactionId: ACCOUNT_UPDATE_TRANSACTION_ID,
      programName: ACCOUNT_UPDATE_PROGRAM_NAME,
    },
    now: paintedAt,
    /*
     * WHY : ⚠️ Refactoring Rationale: the row-23 MESSAGE is delegated here too, where the note above
     *       said it "stays local" and a second `<MessageBand>` painted it inside the body. Both
     *       statements were once true of two different revisions of this screen and they cannot both
     *       be true of one: the shell paints a zone if and only if it is delegated, so publishing
     *       nothing here while rendering a band below produced a row-23 line INSIDE the field area and
     *       an unpainted line where the mapset puts it -- `app/bms/COACTUP.bms` declares `ERRMSG` at
     *       `POS=(23,1)`, below the informational line and above the legend, which is precisely the
     *       zone this frame owns. The sibling account-view screen delegates the same way, and the
     *       claim that this screen's message "is bound to controls in its own body" describes the
     *       row-22 informational line, which is the one band that remains local.
     * WHY : Assumptions: the severity travels with the text rather than being fixed at `error`,
     *       because this channel carries the stored-write acknowledgement as well as the refusals --
     *       `3250` writes both -- and painting a committed write in the refusal colour would tell an
     *       operator their write had failed.
     */
    message: {
      text: statement.message,
      severity: statement.severity,
      mapset: ACCOUNT_UPDATE_MAPSET,
      /*
       * WHY : ⚠️ Refactoring Rationale: the row-22 INFORMATION line is delegated as well, where this
       *       screen rendered its own band for it inside the form. The band's removal site records what a
       *       responsive review measured -- the screen's advisory roughly 1688 to 3040 pixels down the
       *       document with the frame's reserved row-22 zone standing empty above it -- and this is the
       *       other half of that change.
       * WHY : Assumptions: the severity is stated as `info` rather than left to the slot's own default of
       *       `neutral`. The mapset declares `INFOMSG` `COLOR=NEUTRAL`, which is what that default is
       *       measured from, and this screen's line is nonetheless an ADVISORY the operator is meant to
       *       act on -- `3250-SETUP-INFOMSG` derives it from the change action and it reads as an
       *       instruction on every turn -- so the appearance the band carried while it was local is kept
       *       rather than quietly changed by the move. Keeping it also means the move is observable in
       *       exactly one property, the band's position, which is what was wrong with it.
       * WHY : Assumptions: no `mapset` is passed here, because the slot declares none: the display width
       *       belongs to the mapset and a screen stands in for exactly one, so the width published on the
       *       message slot governs both bands.
       */
      information: {
        text: information,
        severity: 'info',
      },
    },
    pfKeys: {
      keys: bindings,
      onInvoke: invoke,
    },
  });

  /**
   * Renders the stored masked state of one protected identifier, read-only, beside its control.
   *
   * Assumptions: the value is rendered EXACTLY as the service published it, with no reformatting
   * whatever. The reference composes the national identifier into dashed thirds itself -- three moves at
   * `app/cbl/COACTUPC.cbl` L2921 to L2931 over `CUST-SSN PIC 9(09)` -- because it holds the digits.
   * This screen does not: re-running that composition over a mask would slice runs of asterisks into
   * groups of three, two and four and present the result as though it were a formatted number.
   *
   * Assumptions: nothing renders until a record has been read. Before that the pair is `null`, and a
   * caption naming an empty value would assert that the identifier on file is blank -- which is a
   * different claim from not yet having looked.
   * @param {string | undefined} masked - The masked rendering, or `undefined` when none has been read.
   * @returns {ReactNode | undefined} The read-only caption, or `undefined` to render no caption at all.
   */
  function renderStoredState(masked: string | undefined): ReactNode | undefined {
    if (masked === undefined || masked === '') {
      return undefined;
    }

    /*
     * WHY : Assumptions: the label and the value are separate text nodes inside one `Space`, so the
     *       value keeps the fixed-pitch face the rest of the identifier column uses while the label
     *       does not. Concatenating them into a single string would put the caption words into the
     *       monospaced face and, more importantly, would make the value unaddressable by a test that
     *       needs to assert the masked rendering exactly. The gap is the design system's own named
     *       size rather than a token reference, because `Space` accepts the named sizes directly and
     *       resolves them from the theme itself -- passing a resolved CSS-variable reference where a
     *       named size is expected would be a literal in the shape of a token.
     */
    return (
      <Space size="small">
        <Typography.Text type="secondary">{ACCOUNT_UPDATE_STORED_STATE_LABEL}</Typography.Text>
        <Typography.Text
          type="secondary"
          style={{ fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData] }}
        >
          {masked}
        </Typography.Text>
      </Space>
    );
  }

  /**
   * Renders one control with its label, error state and the reference's blank marker.
   *
   * Assumptions: the marker is rendered only for the blank state, which is exactly what
   * `app/cpy/CSSETATY.cpy` L18 to L27 does -- it moves the error colour into the field's colour subfield
   * when the flag is not-OK OR blank, and moves a literal asterisk into the field's OUTPUT subfield only
   * when it is blank.
   *
   * ⚠️ Assumptions: WHERE it is rendered depends on the field, and {@link MARKER_BEARING_FIELDS} carries
   * the reason. For most fields it goes into the control's VALUE, which destroys nothing because a blank
   * field is what the state means and the reference reads the marker back as an unfilled field at
   * `1100-RECEIVE-MAP`. For a field on which the service reads `'*'` as an instruction it goes BESIDE the
   * control instead, because putting it in the value would have the operator's next submit carry an
   * instruction they never gave. An earlier revision of this note claimed the value form round-trips for
   * every field "because the service's shared never-supplied test folds the same character into the same
   * answer"; that test is not the first one the government-identifier mapping applies, so the claim was
   * false for exactly the field where being wrong deletes data.
   *
   * Refactoring Rationale: the error state is driven PURELY by the response body, and the condition
   * that has been REMOVED is the reason this needed saying. The reference's highlight is gated on
   * `CDEMO-PGM-REENTER` as well as on the field's flag, so a flag set on a first turn painted nothing
   * -- and that discriminator lived in the passed structure AAP section 0.7.1 deletes outright,
   * because a stateless handler has no turn to remember and no place to remember it. Porting the gate
   * would therefore mean inventing a turn counter to reproduce a suppression whose only purpose was to
   * avoid marking a screen the operator had not yet filled in. What is left is the field-error array,
   * which is why dropping, reordering or padding it changes what this screen marks.
   * @param {FieldRenderSpec} spec - Which field to render and how.
   * @returns {ReactElement} The labelled control, marked when the response named it.
   */
  function renderField(spec: FieldRenderSpec): ReactElement {
    const error = fieldErrors.get(spec.field);
    /*
     * WHY : ⚠️ Refactoring Rationale: a write in flight protects the control, where the action alone used
     *       to decide it. A browser review measured every record field reporting `disabled: false` and a
     *       text cursor while the mutating request was in the air, so a value could be retyped after the
     *       body carrying the previous value had already left -- the operator then reads their new entry
     *       on screen while the record stores the old one, with nothing to tell them the two differ.
     * WHY : ⚠️ Assumptions: this is the terminal's own behaviour rather than an addition. A CICS
     *       pseudo-conversational turn inhibits the keyboard from the send until the next receive, so the
     *       reference has no in-flight state in which typing is possible at all -- there is no arm of
     *       `3300-SETUP-SCREEN-ATTRS` to cite because the situation cannot arise there. Protecting for
     *       the duration of the turn is what makes the browser match it.
     * WHY : ⚠️ Trade-offs: it is gated on the WRITE alone and not on the combined in-flight flag the keys
     *       use. A read seeds the form and a validation is already invalidated by any keystroke --
     *       `recordEntry` discards turns in flight, so a verdict for superseded values cannot land -- and
     *       neither loses an operator's work if they keep typing. Only the write composes its body up
     *       front and cannot be recalled, so only the write needs the fields held still. Locking on all
     *       three would freeze forty controls during a read that has nothing to protect.
     */
    const editable = isFieldEditable(action, spec.field) && !saving;
    const isBlankRefusal = error !== undefined && error.state === 'BLANK';
    const markerBesideControl = MARKER_BEARING_FIELDS.has(spec.field);
    const numeric = spec.numeric === true;
    /*
     * WHY : ⚠️ Refactoring Rationale: every control is now sized from the character width its own
     *       copybook PICTURE clause declares, where all 43 shared one full-width rule. A browser review
     *       measured what that produced: `Opened :`, `Expiry :` and `Reissue:` each painted three boxes
     *       of IDENTICAL width -- 146, 147 and 146 pixels -- so the four-digit year box and the
     *       two-digit month box were the same size, and `Active Y/N:` painted a 446-pixel box at 992
     *       and a 723-pixel box at 1600 for ONE character, dimensionally identical to the five
     *       twelve-character money fields beside it. The defect grew with the viewport, because nothing
     *       in the rule referred to the data.
     * WHY : ⚠️ Assumptions: the width comes from {@link ACCOUNT_UPDATE_FIELD_WIDTHS}, which is the
     *       transcribed record layout this module already keys `maxLength` and the clamp on -- so the
     *       box a field paints and the value it can hold are now stated ONCE. Sizing them separately is
     *       what let a one-character field paint at 45 per cent of the viewport.
     * WHY : Assumptions: the helper returns a MAXIMUM beside `inlineSize: '100%'`, not a fixed size, so
     *       the two fifty-character address lines still shrink to fit a phone rather than forcing the
     *       page to scroll sideways. Design gap G1 in AAP section 0.3.4 already surrenders
     *       pixel-for-character positioning; this keeps relative SIZE, which is what makes a year box
     *       read as wider than a month box.
     */
    /*
     * WHY : ⚠️ Refactoring Rationale: the marker slot is declared to the measure for the marker-BEARING
     *       fields and not for the others, which is the same split the suffix itself follows below. With
     *       a suffix present the design system sizes the affix WRAPPER, whose space the value and the
     *       slot then share, so a maximum computed for the value alone leaves the value short by
     *       whatever the slot takes -- measured on a sibling screen's two-character field as a record
     *       key that rendered as one glyph and a sliver.
     *       Assumptions: the allowance is UNCONDITIONAL for a marker-bearing field rather than keyed to
     *       the refusal, because the trade-off recorded below holds that field's suffix element in every
     *       state so the control does not lose focus when the marker appears. The box therefore shares
     *       its space in every state too, and a measure that only allowed for the slot on a refused turn
     *       would be wrong on the turns in between. Every other field writes the marker INTO its value
     *       and carries no suffix at all, so it reserves nothing.
     */
    const controlStyle: CSSProperties = {
      ...copybookFieldWidthStyle(
        ACCOUNT_UPDATE_FIELD_WIDTHS[spec.field],
        cssVar,
        markerBesideControl ? BLANK_FIELD_MARKER_CHARACTERS : 0,
      ),
      ...(spec.fixedPitch === true ? { fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData] } : {}),
      /*
       * WHY : ⚠️ Refactoring Rationale: a control the operator cannot type into is painted on the design
       *       system's own unavailable surface, where it used to keep the same white background as an
       *       editable one. A browser review measured the consequence twice over: the read-only fields
       *       were `rgb(255,255,255)`, indistinguishable from editable except by border, and the
       *       read-only account number was reported as the one field that looked ACTIVE beside its
       *       editable neighbours -- so the single field that cannot be edited was the one advertising
       *       that it could.
       * WHY : ⚠️ Trade-offs: the surface is borrowed from the disabled state while the control stays
       *       `readOnly`, deliberately, and the two halves answer different requirements. `disabled`
       *       would give the same surface for free but removes the control from the focus order and from
       *       the accessibility tree, which is exactly why this screen stopped using it -- a refusal
       *       keyed to one of the three never-editable fields would mark a control the keyboard cannot
       *       reach and a screen reader does not announce. Painting the surface without taking the
       *       reachability keeps both properties instead of trading one for the other.
       * WHY : Assumptions: the token is read as a `cssVar` member rather than through a named map in
       *       `ui/src/theme/tokens.ts`, which is the established form for a token no BMS role maps to --
       *       `ui/src/layout/MessageBand.tsx` and `ui/src/layout/ScreenTitle.tsx` both read theirs the
       *       same way. It is still a design-system token and not a literal colour.
       */
      ...(editable ? {} : { backgroundColor: cssVar.colorBgContainerDisabled }),
    };
    const blankMarkerStyle: CSSProperties = { color: cssVar[FIELD_ERROR_TOKENS.errorColor] };

    /*
     * WHY : ⚠️ Refactoring Rationale: a protected control renders `readOnly` where it used to render
     *       `disabled`, and the difference is reachability. A disabled input is removed from the focus
     *       order and from the accessibility tree, so a refusal keyed to one of the three never-editable
     *       fields marked a control the keyboard could not reach and a screen reader did not announce --
     *       and the service does key one: `editMapInputs` records `editCustomerKeyNamesRow` under
     *       `customerId` and returns that verdict before any other edit runs. A 3270 protected field is
     *       readable and cursor-addressable and only refuses TYPING -- `MOVE -1 TO <field>L` positions the
     *       cursor on one -- so `readOnly` is the faithful mapping and `disabled` was a stronger claim
     *       than the mapset makes.
     * WHY : Assumptions: it also covers the confirmation turn, where `isFieldEditable` protects EVERY
     *       field. Greying out all forty-three controls would put the values being confirmed behind the
     *       disabled foreground colour, which is the one moment on this screen an operator is asked to
     *       read them.
     */

    /*
     * WHY : Assumptions: each optional attribute is SPREAD in rather than assigned a value that may be
     *       nothing, because `ui/tsconfig.json` enables `exactOptionalPropertyTypes` -- under which an
     *       omitted property and one holding `undefined` are different states, and the design system's
     *       own prop types admit only the omission. Assigning the union would compile the error state
     *       and the no-error state into the same call, which is exactly the distinction the setting
     *       exists to keep.
     */
    const controlId = fieldDomId(spec.field);

    return (
      <Form.Item
        {...(spec.label === '' ? {} : { label: spec.label })}
        htmlFor={fieldDomId(spec.field)}
        /*
          WHY : ⚠️ Refactoring Rationale: the refusal is passed as `fieldErrorHelp` rather than as the bare
                sentence, because `fieldAriaProps` below points the control's `aria-describedby` at
                `fieldErrorId(controlId)` and nothing rendered an element carrying that identifier. A
                dangling reference is worse than none: a screen reader following it announces nothing, so
                the control reported that it had a description and then had none, and the refusal was
                readable only by sighted operators. `ui/src/layout/fieldHelp.tsx` exists to keep the two
                halves in one place, and this renderer was using only the half that names the target.
        */
        {...(error === undefined
          ? {}
          : {
              validateStatus: 'error' as const,
              help: fieldErrorHelp(fieldDomId(spec.field), error.message),
            })}
        {...(spec.extra === undefined ? {} : { extra: spec.extra })}
      >
        {/*
         * WHY : ⚠️ Assumptions: the marker is rendered as an antd `suffix` for a marker-bearing field and
         *       written into the value for every other, and the two are never both applied. A suffix sits
         *       inside the control's own border, so the marker still reads as belonging to the field the
         *       reference put it in, while the control's value stays exactly what the operator typed --
         *       which for a blank refusal is nothing, so their next submit carries nothing rather than an
         *       instruction to clear a stored identifier.
         * WHY : ⚠️ Trade-offs: the suffix is present on a marker-bearing field in EVERY state, carrying
         *       the marker or an empty string, rather than appearing with the refusal. The design system
         *       warns that adding or removing an affix while the control is focused makes it lose focus,
         *       because the affix changes the control's rendered root from a bare `input` to a wrapping
         *       element -- so an affix that came and went would move the cursor off the very field the
         *       operator was correcting. Holding the element and changing only its text keeps the input's
         *       identity, and with it the identifier the screen's cursor effect looks the control up by.
         *       The cost is one empty inline element per marker-bearing field, which is one.
         */}
        <Input
          id={fieldDomId(spec.field)}
          value={
            isBlankRefusal && !markerBesideControl
              ? FIELD_ERROR_TOKENS.blankMarker
              : values[spec.field]
          }
          {...(markerBesideControl
            ? {
                suffix: (
                  <Typography.Text style={blankMarkerStyle}>
                    {isBlankRefusal ? FIELD_ERROR_TOKENS.blankMarker : ''}
                  </Typography.Text>
                ),
              }
            : {})}
          maxLength={ACCOUNT_UPDATE_FIELD_WIDTHS[spec.field]}
          {...(editable ? {} : { readOnly: true })}
          /*
            WHY : ⚠️ Assumptions: the busy state is announced on the control as well as being enforced by
                  the protection above, because the two answer different questions. `readOnly` stops the
                  typing; `aria-busy` is what tells an assistive technology WHY the field it was just
                  editing has stopped accepting characters. Without it the control goes quietly inert,
                  which reads as a broken field rather than as a turn in progress.
            WHY : Assumptions: it is composed by `ui/src/layout/fieldHelp.tsx` rather than written as an
                  attribute here, so the one screen that needed it does not become the one screen that
                  spells it differently.
          */
          {...busyProps(saving)}
          autoFocus={spec.autoFocus === true}
          /*
            WHY : ⚠️ Refactoring Rationale: a SECOND, unconditional `suffix` stood here, rendering the
                  marker beside EVERY refused control and overriding the one above it for the one field
                  that needs it. Two spreads of the same prop into the same element means only the later
                  one applies, so the exception list above was inert and every blank refusal painted the
                  marker twice -- once inside the control's displayed value and once beside it. The
                  reference settles which of the two survives: `app/cpy/CSSETATY.cpy` L23-L26 moves the
                  asterisk into the field's own OUTPUT subfield, so it appears IN the field, and
                  {@link MARKER_BEARING_FIELDS} records why exactly one field is exempt and what a
                  submitted asterisk would mean there. The withdrawn design's own point is kept: the
                  surviving marker for that one field is hidden from assistive technology by the same
                  reasoning, since the refusal sentence is already announced through the form item's
                  error text.
            WHY : Assumptions: the displayed marker does not become the submitted value. The control's
                  `value` is computed for the render while `values[field]` is untouched, and
                  `updateRequestFrom` reads the latter -- so an operator who presses Enter again submits
                  what they typed, and one who edits the marked field replaces the asterisk exactly as
                  they would on the terminal.
          */
          {...(spec.placeholder === undefined ? {} : { placeholder: spec.placeholder })}
          {...(spec.ariaLabel === undefined ? {} : { 'aria-label': spec.ariaLabel })}
          {...(numeric ? { inputMode: 'numeric' as const } : {})}
          {...fieldAriaProps(controlId, {
            invalid: error !== undefined,
            hasError: error !== undefined,
            hasHint: spec.describedBy !== undefined,
            ...(spec.describedBy === undefined ? {} : { hintId: spec.describedBy }),
          })}
          style={controlStyle}
          onChange={
            /**
             * Records the entry exactly as the control reports it.
             * @param {{ target: { value: string } }} event - The control's change event.
             * @param {{ value: string }} event.target - The control the event came from.
             * @param {string} event.target.value - The entry exactly as the control reports it.
             * @returns {void} Completion is represented by the screen's own state.
             */
            (event: { readonly target: { readonly value: string } }): void => {
              /*
               * WHY : Assumptions: the third argument is the field's OWN numeric flag from its render
               *       spec, and it has to be passed: the narrowing that keeps a separator or a letter
               *       out of a numeric-only field is decided per field, and the five amounts must be
               *       able to receive a malformed entry so the service refuses it in the reference's own
               *       words. Omitting it left every field taking the same path.
               */
              recordEntry(spec.field, event.target.value);
            }
          }
        />
      </Form.Item>
    );
  }

  /*
   * WHY : Assumptions: every amount is a plain text control with no numeric input component and no
   *       conversion of any kind, and the reference is why. `1250-EDIT-SIGNED-9V2` checks for a blank
   *       value FIRST and only then applies `FUNCTION TEST-NUMVAL-C` -- character before number -- so
   *       the field must be able to hold text that is not a number in order for that first check to
   *       have anything to reject. A numeric control would additionally route the value through a
   *       JavaScript number, which is an IEEE-754 double, and the cent it loses would produce a
   *       plausible balance rather than an error.
   * WHY : ⚠️ Refactoring Rationale: this note observed that "none of the five declares a `PICOUT`
   *       operand" and concluded "so nothing formats them on output either". The observation is true --
   *       `app/bms/COACTUP.bms` carries no `PICOUT` anywhere -- and the conclusion drawn from it was
   *       false, because on THIS screen the formatting is done by the PROGRAM rather than by the map:
   *       `app/cbl/COACTUPC.cbl` L371 declares `WS-EDIT-CURRENCY-9-2-F PIC +ZZZ,ZZZ,ZZZ.99` and moves
   *       the edited result into the unedited `PIC X(15)` field, at L2797 to L2811 for the stored values
   *       and L2874 onward for redisplayed ones. The sibling account-view screen reaches the SAME
   *       presentation the other way round, through `PICOUT` on its own mapset. So the mask was missing
   *       here while the one place a reader would look explained why it was absent.
   *       The mask is now applied on seeding and on redisplay, and REVERSED on submission -- see
   *       {@link remaskAcceptedAmounts} and `updateRequestFrom` -- which is what keeps the control
   *       plain text end to end while still painting what the terminal painted.
   *       Trade-offs: the operator gets no stepper, and gains grouping that matches the terminal, an
   *       amount that never passes through a number, and a refusal in the reference's own words.
   */

  /**
   * Renders one amount, which is a fifteen-character text control in the fixed-pitch face.
   * @param {AccountUpdateFieldName} field - Which amount to render.
   * @param {string} label - The painted label, verbatim from the mapset.
   * @returns {ReactElement} The labelled amount control.
   */
  function renderAmount(field: AccountUpdateFieldName, label: string): ReactElement {
    return renderField({ field, label, fixedPitch: true });
  }

  /*
   * WHY : Assumptions: each of the four dates, the national identifier and both telephone numbers stays
   *       SPLIT into the parts the mapset paints, and is never composed into one control. The mapset
   *       declares a separate `DFHMDF` per part -- `OPNYEAR`, `OPNMON` and `OPNDAY` for the open date,
   *       `ACTSSN1`, `ACTSSN2` and `ACTSSN3` for the identifier, and the same shape for the rest -- and
   *       the reference edits and marks each part independently: its cursor table at
   *       `app/cbl/COACTUPC.cbl` L3009 to L3167 carries an arm per part, and its composed refusals name
   *       the part rather than the whole, as `: Area code must be supplied.` does. Per-part maximum
   *       entry length and per-part marking are therefore observable behaviour, and one combined control
   *       could carry neither.
   */

  /**
   * Renders one three-part date group under a single painted label.
   *
   * Assumptions: the hyphens between the parts are the mapset's own anonymous `LENGTH=1` fields, each
   * carrying `INITIAL='-'`, so they are painted separators rather than characters of a value.
   * @param {string} label - The painted label for the group, verbatim from the mapset.
   * @param {AccountUpdateFieldName} yearField - The four-digit year part.
   * @param {AccountUpdateFieldName} monthField - The two-digit month part.
   * @param {AccountUpdateFieldName} dayField - The two-digit day part.
   * @param {string} nameToken - The catalog name token that identifies this date in a refusal, used
   *   to give each part an accessible name the shared label cannot supply.
   * @returns {ReactElement} The three labelled parts in one row.
   */
  function renderDateParts(
    label: string,
    yearField: AccountUpdateFieldName,
    monthField: AccountUpdateFieldName,
    dayField: AccountUpdateFieldName,
    nameToken: string,
  ): ReactElement {
    const aria = partGroupAria(yearField);

    return (
      <Form.Item htmlFor={aria.captionFor} label={<span id={aria.captionId}>{label}</span>}>
        <Space.Compact {...aria.group}>
          {renderField({
            field: yearField,
            label: '',
            numeric: true,
            fixedPitch: true,
            ariaLabel: `${nameToken} ${ACCOUNT_UPDATE_PART_NAMES.year}`,
          })}
          <Typography.Text type="secondary">{DATE_PART_SEPARATOR}</Typography.Text>
          {renderField({
            field: monthField,
            label: '',
            numeric: true,
            fixedPitch: true,
            ariaLabel: `${nameToken} ${ACCOUNT_UPDATE_PART_NAMES.month}`,
          })}
          <Typography.Text type="secondary">{DATE_PART_SEPARATOR}</Typography.Text>
          {renderField({
            field: dayField,
            label: '',
            numeric: true,
            fixedPitch: true,
            ariaLabel: `${nameToken} ${ACCOUNT_UPDATE_PART_NAMES.day}`,
          })}
        </Space.Compact>
      </Form.Item>
    );
  }

  /**
   * Renders one three-part telephone group under a single painted label.
   *
   * Assumptions: the three parts are the area code, the prefix and the line number, in the order the
   * reference's own composition assembles them, and each carries the accessible name its refusal
   * suffix uses -- `: Area code must be supplied.`, `: Prefix code must be supplied.` and
   * `: Line number code must be supplied.` -- so a marked part is identifiable by the same words the
   * sentence beside it uses.
   * @param {string} label - The painted label for the group, verbatim from the mapset.
   * @param {AccountUpdateFieldName} areaField - The three-digit area code part.
   * @param {AccountUpdateFieldName} prefixField - The three-digit prefix part.
   * @param {AccountUpdateFieldName} lineField - The four-digit line number part.
   * @param {string} nameToken - The catalog name token that identifies this number in a refusal.
   * @returns {ReactElement} The three labelled parts in one row.
   */
  function renderPhoneParts(
    label: string,
    areaField: AccountUpdateFieldName,
    prefixField: AccountUpdateFieldName,
    lineField: AccountUpdateFieldName,
    nameToken: string,
  ): ReactElement {
    const aria = partGroupAria(areaField);

    return (
      <Form.Item htmlFor={aria.captionFor} label={<span id={aria.captionId}>{label}</span>}>
        <Space.Compact {...aria.group}>
          {renderField({
            field: areaField,
            label: '',
            numeric: true,
            fixedPitch: true,
            ariaLabel: `${nameToken} ${ACCOUNT_UPDATE_PART_NAMES.areaCode}`,
          })}
          {renderField({
            field: prefixField,
            label: '',
            numeric: true,
            fixedPitch: true,
            ariaLabel: `${nameToken} ${ACCOUNT_UPDATE_PART_NAMES.prefix}`,
          })}
          {renderField({
            field: lineField,
            label: '',
            numeric: true,
            fixedPitch: true,
            ariaLabel: `${nameToken} ${ACCOUNT_UPDATE_PART_NAMES.lineNumber}`,
          })}
        </Space.Compact>
      </Form.Item>
    );
  }

  /*
   * WHY : Assumptions: the abend surface is rendered before anything else and replaces the screen
   *       entirely, because the reference's `ABEND-ROUTINE` ends the task -- there is no screen left
   *       underneath to return to. The four members are the structured equivalent of the `ABEND-DATA`
   *       group, and both sentences come from the catalog so the pair cannot drift.
   */
  if (abended) {
    return (
      <Result
        status="error"
        title={UNEXPECTED_ABEND_OCCURRED}
        subTitle={UNEXPECTED_DATA_SCENARIO_ABEND.abendMsg}
        extra={
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label={ABEND_LABELS.abendCode}>
              {UNEXPECTED_DATA_SCENARIO_ABEND.abendCode}
            </Descriptions.Item>
            <Descriptions.Item label={ABEND_LABELS.abendCulprit}>
              {UNEXPECTED_DATA_SCENARIO_ABEND.abendCulprit}
            </Descriptions.Item>
          </Descriptions>
        }
      />
    );
  }

  /*
   * WHY : Alternatives Considered: the `cssVar` member of the same hook, which every colour and font
   *       value on this screen goes through. It cannot serve here, and the reason is the shape of the
   *       value rather than a preference: the grid's gutter is a component PROP that the design system
   *       converts into padding itself, and it accepts a number, whereas `cssVar` yields the
   *       `var(--...)` reference form. So the resolved member is read for this one value, which is safe
   *       precisely because it is not written into a style attribute -- nothing about today's palette
   *       is baked into an element, and the value still comes from the bridge's own spacing entry
   *       rather than from a literal.
   */
  const responsiveGridGutter = accountUpdateGridGutter(token[SPACING_TOKENS.sectionGapMedium]);

  /*
   * WHY : Refactoring Rationale: every text colour on this screen resolves through
   *       `BMS_TEXT_COLOR_TOKENS` and not through the hue map `BMS_COLOR_TOKENS`. The measured
   *       source roles are unchanged, and so is the bridge that assigns each `COLOR=` operand its
   *       semantic role; what changed is that the hue map's entries are mid-ramp FILL anchors, and
   *       read as text the turquoise role measures 2.205:1 and the blue role 4.104:1 against the
   *       surface the shell paints, where WCAG AA asks 4.5:1 for normal text.
   *       `ui/src/theme/tokens.ts` records, per role, the in-family shade that was measured and the
   *       text-grade token that replaced it.
   */
  const titleStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL],
    fontSize: cssVar[TYPOGRAPHY_TOKENS.screenTitleSize],
    lineHeight: cssVar[TYPOGRAPHY_TOKENS.screenTitleLineHeight],
  };
  const sectionStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] };

  return (
    <Flex vertical gap="large">
      {/*
       * Assumptions: the header band is composed here because both of its screen-specific values are
       * this screen's own -- the transaction identifier and the program name that rows 1 and 2 of the
       * 3270 screen painted into `TRNNAME` and `PGMNAME` -- and the instant comes from the server
       * clock hook rather than the browser's, which is what reproduces the single region clock
       * `FUNCTION CURRENT-DATE` gave every terminal.
       */}
      {/*
       * ⚠️ Assumptions: the heading carries the mapset's own 14-character `COLOR=NEUTRAL` field,
       * and its colour comes from the bridge while its RANK now comes from
       * `ui/src/layout/ScreenTitle.tsx`. The rank was a literal 4 here, which happened to be correct --
       * ten of the thirteen screens painting a caption chose 3 and outranked the application title
       * above them -- so this site changes to remove the LITERAL rather than to correct a value, and it
       * is the same change: the outline is a property of one module or of thirteen, and thirteen is how
       * it drifted. The size reasoning is unchanged: the bridge maps the fourth heading step because a
       * larger step costs vertical space on a screen of 128 fields.
       */}
      <ScreenTitle style={titleStyle}>{ACCOUNT_UPDATE_HEADINGS.screen}</ScreenTitle>
      {/*
       * WHY : ⚠️ Purpose: the ONE sentence an operator who cannot see the spinner hears while a request
       *       is outstanding. Every visible sign this screen gives that it is working is visual -- the
       *       overlay across the form, the spinner on the key that owns the turn -- so without this the
       *       screen went silent for exactly as long as the service took and then spoke only its answer.
       * WHY : ⚠️ Assumptions: it is mounted UNCONDITIONALLY and empty while idle, which
       *       `ui/src/layout/fieldHelp.tsx` records as load-bearing: a live region has to be in the
       *       accessibility tree before its text changes, so one that appeared already populated would
       *       announce nothing on the first turn -- the one turn that matters most.
       * WHY : ⚠️ Assumptions: the whole in-flight flag drives it rather than the per-key ownership derived
       *       above. Ownership answers WHICH control is working, which is a visual question about one
       *       control; this answers WHETHER the screen is working, and an operator waiting on a read, a
       *       validation or a write is waiting on the same thing. One sentence for all three also means
       *       the region never re-announces when a turn hands over.
       * WHY : Assumptions: the sentence is `REQUEST_IN_PROGRESS` from the catalog and is not composed
       *       here. The reference has no wording for a request still outstanding -- a 3270 terminal
       *       inhibited input instead of narrating -- so this is an AUTHORED sentence, declared,
       *       registered and width-checked in `ui/src/messages/messages.ts` under transformation rule T8.
       */}
      {busyAnnouncement(turnInFlight ? REQUEST_IN_PROGRESS : undefined)}
      {/*
       * Refactoring Rationale: the message line that used to sit here is delegated to the shell, which
       * renders it unconditionally and reserves its space at all times -- what row 23 of a 24-row
       * terminal did for free. On THIS screen that reservation matters more than on most: the reference
       * re-sends the same map with row 23 populated, so a band that appeared and disappeared would move
       * the very control an operator is correcting, mid-correction, on a form of forty editable fields.
       */}
      {/*
       * Assumptions: the spinner covers the read and the write without unmounting the form, so the
       * values an operator typed survive a refused write. Replacing the form with the spinner would
       * discard them, and the reference discards nothing -- it re-sends the same map.
       */}
      <Spin spinning={loading || saving}>
        {/*
         * Refactoring Rationale: `onFinish` runs the VALIDATE arm and never the write. The reference's
         * Enter key edits the fields and asks for confirmation, and only the later F5 turn performs
         * `9600-WRITE-PROCESSING`, so submitting the form is the first of those two.
         * Assumptions: `layout="vertical"` rather than a horizontal label column, because the mapset
         * paints its labels above or beside its fields at 128 absolute positions and a single label
         * column cannot reproduce either; the vertical layout keeps every label adjacent to its own
         * control at every width.
         */}
        <Form<AccountUpdateFormValues>
          layout="vertical"
          onFinish={processEnter}
          component="form"
          name="accountUpdate"
        >
          {/*
           * WHY : Trade-offs: the 128 absolute `POS=(row,column)` operands of the mapset become a
           *       responsive grid, which is design gap G1 and the one intentional, documented
           *       deviation on this screen. Reproducing a fixed 24-by-80 character grid in a browser
           *       can only scale or clip, never reflow, and absolute coordinates give assistive
           *       technology no reliable reading order -- so what is preserved is the field GROUPING,
           *       the reading order and the tab order, and what is given up is pixel-for-character
           *       positioning. Every row below carries the fields the corresponding mapset row paints,
           *       in the order it paints them, so an operator reads the same fields in the same
           *       sequence.
           */}
          <Row gutter={responsiveGridGutter}>
            {/* Mapset row 5: the account identifier and the active status. */}
            <Col xs={24} md={12}>
              {renderField({
                field: 'accountId',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.accountId,
                numeric: true,
                fixedPitch: true,
                /*
                 * WHY : Assumptions: this is the ONLY control on the screen that takes initial focus,
                 *       because `ACCTSID` is the only field of the 128 declared with the `IC` operand
                 *       -- `ATTRB=(IC,UNPROT)` at `app/bms/COACTUP.bms` L84 to L87. A second
                 *       `autoFocus` would race this one and the winner would be render order rather
                 *       than the mapset.
                 */
                autoFocus: true,
              })}
            </Col>
            <Col xs={24} md={12}>
              {renderField({
                field: 'activeStatus',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.activeStatus,
              })}
            </Col>

            {/* Mapset row 6: the open date on the left, the credit limit on the right. */}
            <Col xs={24} md={12}>
              {renderDateParts(
                ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.openDate,
                'openDateYear',
                'openDateMonth',
                'openDateDay',
                NAME_TOKENS.OPEN_DATE,
              )}
            </Col>
            <Col xs={24} md={12}>
              {renderAmount('creditLimit', ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.creditLimit)}
            </Col>

            {/* Mapset row 7: the expiry date and the cash credit limit. */}
            <Col xs={24} md={12}>
              {renderDateParts(
                ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.expirationDate,
                'expirationDateYear',
                'expirationDateMonth',
                'expirationDateDay',
                NAME_TOKENS.EXPIRY_DATE,
              )}
            </Col>
            <Col xs={24} md={12}>
              {renderAmount('cashCreditLimit', ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.cashCreditLimit)}
            </Col>

            {/* Mapset row 8: the reissue date and the current balance. */}
            <Col xs={24} md={12}>
              {renderDateParts(
                ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.reissueDate,
                'reissueDateYear',
                'reissueDateMonth',
                'reissueDateDay',
                NAME_TOKENS.REISSUE_DATE,
              )}
            </Col>
            <Col xs={24} md={12}>
              {renderAmount('currentBalance', ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.currentBalance)}
            </Col>

            {/*
             * WHY : ⚠️ Refactoring Rationale: an EMPTY `Col` stood here, rendered purely to hold the
             *       left half of mapset row 9 open so that every later field kept the mapset's own
             *       left/right pairing. A browser review measured the cost: a void in the left column,
             *       present even in the empty pre-read state -- so a static grid artefact rather than a
             *       data one -- where "the eye jumps a full row height". At 992 the right column had
             *       five money rows against the left's four, which is the arithmetic the empty cell
             *       existed to paper over.
             * WHY : ⚠️ Trade-offs: removing it buys the void's removal at the price of the visual
             *       PAIRING for the two fields after it -- the cycle credit moves to the left half and
             *       the account group to the right. That is the trade AAP section 0.3.4 gap G1 already
             *       makes: it surrenders pixel-for-character positioning and keeps field grouping,
             *       reading order and tab order. Only position changes here. The DOM order is
             *       untouched, so the keyboard traversal and the announced order are byte-identical to
             *       before, and the mapset's row 9 asymmetry -- which is a POSITIONAL fact about a
             *       fixed 24x80 display -- is the one thing G1 does not undertake to reproduce.
             * WHY : Alternatives Considered: reordering the fields so the two counts match, which would
             *       close the void and keep the money fields in one column. Rejected because the
             *       reference's tab order follows mapset field-definition order, which is row-major, so
             *       reordering the DOM would trade a positional divergence G1 permits for a traversal
             *       divergence it does not.
             */}
            <Col xs={24} md={12}>
              {renderAmount(
                'currentCycleCredit',
                ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.currentCycleCredit,
              )}
            </Col>

            {/* Mapset order, after the cycle credit: the account group and the cycle debit. */}
            <Col xs={24} md={12}>
              {renderField({
                field: 'groupId',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.groupId,
              })}
            </Col>
            <Col xs={24} md={12}>
              {renderAmount(
                'currentCycleDebit',
                ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.currentCycleDebit,
              )}
            </Col>
          </Row>

          {/*
           * WHY : ⚠️ Refactoring Rationale: this heading is now a real heading element, ranked by
           *       `SECTION_HEADING_LEVEL`, where it was emphasised text inside a `Divider`. A browser
           *       review reported the section-heading pattern as diverging from the PAIRED
           *       `/account/view`, and the divergence was not merely stylistic: that screen paints the
           *       same string with `Typography.Title level={SECTION_HEADING_LEVEL}`, so on one of the
           *       two screens that edit this record the section is in the document's heading
           *       hierarchy and on the other it is not -- a keyboard or screen-reader operator could
           *       jump to `Customer Details` on the view and not on the update.
           * WHY : ⚠️ Assumptions: the rank is READ from `ui/src/layout/ScreenTitle.tsx` and not written
           *       as a literal, for the reason that module states -- a literal here would silently
           *       become a PEER of the screen caption if the caption's own rank ever moved.
           * WHY : Assumptions: the colour is unchanged in value. `type="secondary"` on the sibling and
           *       `BMS_TEXT_COLOR_TOKENS.NEUTRAL` here both resolve to `colorTextSecondary`, so the
           *       BMS `COLOR=NEUTRAL` operand the mapset gives this heading is still what is painted;
           *       the token is named explicitly rather than implied by the prop so the mapping stays
           *       traceable from the source operand.
           * WHY : ⚠️ Trade-offs: the `Divider` is dropped rather than kept around the heading. Its own
           *       note claimed it stood in for mapset row 11 -- a whole terminal row given to
           *       separating the two records -- and the heading's own block spacing carries that
           *       separation. Keeping both would give this screen a rule the paired screen does not
           *       have, which is the same inconsistency in the other direction.
           */}
          <Typography.Title level={SECTION_HEADING_LEVEL} style={sectionStyle}>
            {ACCOUNT_UPDATE_HEADINGS.customerSection}
          </Typography.Title>

          <Row gutter={responsiveGridGutter}>
            {/* Mapset row 12: the customer identifier and the three identifier groups. */}
            <Col xs={24} md={12}>
              {renderField({
                field: 'customerId',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.customerId,
                numeric: true,
                fixedPitch: true,
              })}
            </Col>
            <Col xs={24} md={12}>
              {/*
               * Assumptions: the mapset paints ONE label, `SSN:`, for all three parts, and the
               * catalog holds three per-part name tokens the reference composes its refusals from --
               * `SSN: First 3 chars`, `SSN 4th & 5th chars` and `SSN Last 4 chars`. They become the
               * per-part accessible names, so each box is identifiable by the same words a refusal
               * naming it would use. The three placeholders are the mapset's own `INITIAL=` hints.
               */}
              {/*
               * Assumptions: the stored state captions the GROUP rather than each of its three parts,
               * because the service publishes one masked rendering of the whole identifier and the
               * split into three is the mapset's entry shape, not the record's. Captioning each part
               * would need the mask cut into thirds, which is exactly the reformatting
               * {@link renderStoredState} refuses to do.
               */}
              <Form.Item
                extra={renderStoredState(storedIdentifiers?.ssnMasked)}
                htmlFor={SSN_GROUP_ARIA.captionFor}
                label={
                  <span id={SSN_GROUP_ARIA.captionId}>
                    {ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.ssn}
                  </span>
                }
              >
                <Space.Compact {...SSN_GROUP_ARIA.group}>
                  {renderField({
                    field: 'ssnPart1',
                    label: '',
                    numeric: true,
                    fixedPitch: true,
                    placeholder: SSN_PART_PLACEHOLDERS.ssnPart1,
                    ariaLabel: NAME_TOKENS.SSN_FIRST_3_CHARS,
                    describedBy: SSN_STANDING_ID,
                  })}
                  <Typography.Text type="secondary">{DATE_PART_SEPARATOR}</Typography.Text>
                  {renderField({
                    field: 'ssnPart2',
                    label: '',
                    numeric: true,
                    fixedPitch: true,
                    placeholder: SSN_PART_PLACEHOLDERS.ssnPart2,
                    ariaLabel: NAME_TOKENS.SSN_4TH_AND_5TH_CHARS,
                    describedBy: SSN_STANDING_ID,
                  })}
                  <Typography.Text type="secondary">{DATE_PART_SEPARATOR}</Typography.Text>
                  {renderField({
                    field: 'ssnPart3',
                    label: '',
                    numeric: true,
                    fixedPitch: true,
                    placeholder: SSN_PART_PLACEHOLDERS.ssnPart3,
                    ariaLabel: NAME_TOKENS.SSN_LAST_4_CHARS,
                    describedBy: SSN_STANDING_ID,
                  })}
                </Space.Compact>
                {/*
                 * WHY : Assumptions: ONE caption serves all three boxes, which is why each part
                 *       references it by identifier rather than carrying its own. The mapset paints one
                 *       `SSN:` label for the group, and a caption repeated three times would be read out
                 *       three times by an assistive technology walking the parts.
                 * WHY : Assumptions: it renders only once a record has been read. Before that there is no
                 *       marker to show, and a caption about preserving a stored value would describe a
                 *       record the screen does not have.
                 */}
                {protectedValues === null ? null : (
                  <Typography.Text id={SSN_STANDING_ID} type="secondary">
                    {nationalIdentifierCaption(protectedValues.ssnMasked)}
                  </Typography.Text>
                )}
              </Form.Item>
            </Col>

            {/* Mapset row 13: the date of birth and the credit score. */}
            <Col xs={24} md={12}>
              {renderDateParts(
                ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.dateOfBirth,
                'dateOfBirthYear',
                'dateOfBirthMonth',
                'dateOfBirthDay',
                NAME_TOKENS.DATE_OF_BIRTH,
              )}
            </Col>
            <Col xs={24} md={12}>
              {renderField({
                field: 'ficoCreditScore',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.ficoCreditScore,
                numeric: true,
                fixedPitch: true,
              })}
            </Col>

            {/* Mapset rows 14 and 15: the three names, whose labels are painted a row above them. */}
            <Col xs={24} md={8}>
              {renderField({
                field: 'firstName',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.firstName,
              })}
            </Col>
            <Col xs={24} md={8}>
              {renderField({
                field: 'middleName',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.middleName,
              })}
            </Col>
            <Col xs={24} md={8}>
              {renderField({
                field: 'lastName',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.lastName,
              })}
            </Col>

            {/* Mapset row 16: address line 1 with the state code to its right. */}
            <Col xs={24} md={16}>
              {renderField({
                field: 'addressLine1',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.address,
              })}
            </Col>
            <Col xs={24} md={8}>
              {renderField({
                field: 'stateCode',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.stateCode,
              })}
            </Col>

            {/*
             * WHY : Assumptions: address line 2 carries NO label of its own and no validation, and both
             *       are the mapset's and the program's arrangement rather than omissions here.
             *       `ACSADL2` is painted at row 17 column 10 with the row-16 `Address:` label standing
             *       for the block, and `app/cbl/COACTUPC.cbl` L1613 to L1615 comments the
             *       `'Address Line 2'` name token out with "Address Line 2 is optional", validating
             *       `'City'` against `ACUP-NEW-CUST-ADDR-LINE-3` on the following statement -- so this
             *       control can never be marked, and `City` on the row below edits the third address
             *       line rather than a fourth field. The catalog's `CITY` token is what names that
             *       validation, which is why the label below reads City while the value is line 3.
             */}
            {/*
             * WHY : ⚠️ Refactoring Rationale: this control now paints a VISIBLE label, where it painted
             *       an empty one and carried its name only for assistive technology. A browser review
             *       measured the consequence at 576 and below: the input was orphaned -- no visible
             *       label, landing between `State` holding `VA` and `Zip` holding `12546` -- because
             *       its only association with `Address:` was that the two-column grid put it directly
             *       beneath address line 1.
             * WHY : ⚠️ Assumptions: that association was POSITIONAL and nothing else. The mapset paints
             *       one `Address:` literal at `app/bms/COACTUP.bms` L355 over `ACSADL1` at (16,10) and
             *       leaves `ACSADL2` at (17,10) unlabelled, relying on the two sitting in the same
             *       column of a fixed 24x80 display. AAP section 0.3.4 gap G1 surrenders exactly that,
             *       so the association has to be carried by something G1 keeps -- and a label is what
             *       it keeps.
             * WHY : ⚠️ Assumptions: the words are the PROGRAM's own and are not authored here.
             *       `app/cbl/COACTUPC.cbl` L1614 holds `MOVE 'Address Line 2' TO
             *       WS-EDIT-VARIABLE-NAME`, commented out on the line above because the field is
             *       optional -- so this is the reference's name for the field, already carried in this
             *       module as {@link ADDRESS_LINE_2_NAME} for the accessible name. Promoting it to the
             *       visible label additionally makes the two agree, which is the property a visible
             *       label and an accessible name are required to share.
             * WHY : Alternatives Considered: revealing the label only below the medium breakpoint, so
             *       the wide layout keeps the mapset's unlabelled look. Rejected on two counts -- it
             *       makes one field's labelling depend on viewport width, which no other control on
             *       this screen does, and the wide layout's implicit association is exactly the
             *       positional one G1 declines to guarantee, so protecting it protects nothing stated.
             */}
            {/* Mapset row 17: address line 2 with the postal code to its right. */}
            <Col xs={24} md={16}>
              {renderField({ field: 'addressLine2', label: ADDRESS_LINE_2_NAME })}
            </Col>
            <Col xs={24} md={8}>
              {renderField({
                field: 'zipCode',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.zipCode,
                numeric: true,
                fixedPitch: true,
              })}
            </Col>

            {/* Mapset row 18: the city line with the country code to its right. */}
            <Col xs={24} md={16}>
              {renderField({ field: 'city', label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.city })}
            </Col>
            <Col xs={24} md={8}>
              {renderField({
                field: 'countryCode',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.countryCode,
              })}
            </Col>

            {/* Mapset row 19: the first telephone number with the government identifier. */}
            <Col xs={24} md={12}>
              {renderPhoneParts(
                ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.phone1,
                'phone1AreaCode',
                'phone1Prefix',
                'phone1LineNumber',
                NAME_TOKENS.PHONE_NUMBER_1,
              )}
            </Col>
            <Col xs={24} md={12}>
              {/*
               * WHY : ⚠️ Refactoring Rationale: `describedBy` names this control's OWN caption, and it
               *       named nothing. The three national-identifier parts each point at their shared
               *       standing caption and this one pointed at neither, so the sentence stating that the
               *       stored government identifier is preserved when the field is left blank was visible
               *       and unannounced -- and it is the one caption whose absence changes what an operator
               *       believes a blank submission does.
               */}
              {renderField({
                field: 'governmentIssuedId',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.governmentIssuedId,
                describedBy: GOVERNMENT_ID_STANDING_ID,
                extra: renderStoredState(storedIdentifiers?.governmentIssuedIdMasked),
              })}
              {/*
               * WHY : Assumptions: this control gets its OWN caption rather than sharing the one above,
               *       because it is a different value with a different preserve decision -- the service
               *       compares and preserves the two identifiers independently.
               */}
              {protectedValues === null ? null : (
                <Typography.Text id={GOVERNMENT_ID_STANDING_ID} type="secondary">
                  {governmentIdentifierCaption(protectedValues.governmentIssuedIdMasked)}
                </Typography.Text>
              )}
            </Col>

            {/* Mapset row 20: the second telephone number, the funds-transfer account and the flag. */}
            <Col xs={24} md={12}>
              {renderPhoneParts(
                ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.phone2,
                'phone2AreaCode',
                'phone2Prefix',
                'phone2LineNumber',
                NAME_TOKENS.PHONE_NUMBER_2,
              )}
            </Col>
            <Col xs={24} md={6}>
              {renderField({
                field: 'eftAccountId',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.eftAccountId,
                numeric: true,
                fixedPitch: true,
              })}
            </Col>
            <Col xs={24} md={6}>
              {renderField({
                field: 'primaryCardHolderIndicator',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.primaryCardHolderIndicator,
              })}
            </Col>
          </Row>
        </Form>
      </Spin>

      {/*
       * WHY : Alternatives Considered: writing straight from the F5 binding with no further prompt, and
       *       a modal dialog. The bare write is rejected because the 3270 convention this replaces is a
       *       re-key-to-confirm gesture -- an operator had to press a specific key a second time -- and
       *       losing it would make one keystroke a committed write on a form of forty editable fields.
       *       A modal is rejected because it takes focus away from the values being confirmed, where
       *       the design system's inline confirmation keeps them on screen behind it. The confirmation
       *       cannot OPEN on a turn the reference refuses the key -- the guard for that is the `disabled`
       *       pair recorded below rather than the control's absence -- and AAP section 0.4.1.4 names this
       *       component for this screen.
       * WHY : ⚠️ Refactoring Rationale: the gate is CONTROLLED -- its visibility is this screen's state and
       *       `requestSaveConfirmation` is the only thing that opens it. Uncontrolled, the design system
       *       opened it from this trigger alone, so the physical F5 and the legend's F5 button wrote
       *       directly while only a pointer on this control passed through the gate: one advertised key
       *       with two behaviours, and the keyboard path was the one that skipped the extra deliberate
       *       act. `onOpenChange` keeps the pointer path working THROUGH that state rather than around it.
       * WHY : Assumptions: `onConfirm` is `saveEdits` and nothing else, so there remains exactly ONE write
       *       call site on this screen. `saveEdits` closes the gate itself, which is why no separate close
       *       runs here -- a close in two places could disagree about the order.
       * WHY : Assumptions: it is rendered ABOVE the information line rather than below it, so the row-22
       *       band stays the last element of the body. The confirmation control has no mapset row of its
       *       own, and putting it after the band would place an additive control between rows 22 and 23.
       */}
      {/*
       * WHY : ⚠️ Refactoring Rationale: this control is rendered whenever the save legend is painted and
       *       it used to be rendered only while the action ADMITTED the save, so it vanished on a
       *       concurrency-refused turn exactly as the legend key did -- the browser review measured
       *       `F5=Save` absent from both places at once. It is now present and DISABLED there, which is
       *       the same remedy the legend takes and is recorded in full at {@link saveLegendIsPainted}.
       * WHY : ⚠️ Assumptions: `disabled` is passed to the design system's confirmation as well as to its
       *       trigger, and both are needed. The trigger being disabled stops a pointer press reaching
       *       `onOpenChange`, and `Popconfirm`'s own `disabled` short-circuits the open path the
       *       component can otherwise reach for itself -- `ui/node_modules/antd/lib/popconfirm/index.js`
       *       returns from `onInternalOpenChange` before it sets state. The gate therefore cannot open on
       *       a turn the action is not admitted on, so there is no path from this control to a write.
       */}
      {saveLegendIsPainted ? (
        <Card size="small">
          <Popconfirm
            title={MESSAGES.PROMPT_FOR_CONFIRMATION.text}
            okText={ACCOUNT_UPDATE_KEY_LABELS.PFK05}
            cancelText={ACCOUNT_UPDATE_KEY_LABELS.PFK12}
            okType="primary"
            /*
             * WHY : ⚠️ Refactoring Rationale: a withdrawn prompt is DESTROYED rather than kept hidden,
             *       and this is what makes the `autoFocus` reasoned about below true on the SECOND
             *       opening as well as the first. That paragraph is right that the platform applies
             *       the attribute because the control is rendered into a portal on open -- and the
             *       unstated half is that the platform applies it when the control ENTERS the
             *       document, which happens only once unless the surface is destroyed in between. The
             *       design system keeps a dismissed overlay mounted by default, so without this flag
             *       a re-opened prompt re-shows a cancel control that never left, no mount occurs,
             *       and the focus stays wherever the operator's gesture put it. A browser pass
             *       measured both halves of that on sibling screens: focus on the declining choice on
             *       a first opening, and focus left on the invoking control on a re-opening of the
             *       same surface. On a screen that commits forty fields, a prompt whose safe answer
             *       is merely VISIBLE rather than focused is the formality this confirmation exists
             *       to prevent.
             *       Trade-offs: the overlay is rebuilt per opening. The withdrawal still animates --
             *       the flag reaches the leave motion as `removeOnLeave`, so removal waits for the
             *       animation rather than replacing it.
             */
            destroyOnHidden
            /*
             * WHY : ⚠️ Purpose: the focus lands on the SAFE choice when the prompt opens, so the very
             *       next Enter or Space withdraws the question rather than committing the record. A
             *       prompt that opens with focus on its accept control turns a confirmation into a
             *       formality -- one further keystroke, which is the keystroke an operator has already
             *       pressed once to get here, writes forty fields.
             * WHY : ⚠️ Assumptions: nothing takes the focus back afterwards, and what makes that true is
             *       WHEN the other focus mover runs rather than whether it runs. The cursor effect above
             *       places the cursor for the turn that settled -- on the validated turn it moves focus
             *       to the control the reference's cursor table names, which a removal probe confirmed by
             *       leaving the focus on `activeStatus` -- and its dependencies are the cursor target and
             *       the refusal map, neither of which changes when this prompt opens. So it has already
             *       run by the time the prompt exists and does not run again. The measured trap on
             *       another screen was the opposite order: a focus call placed AFTER the prompt opened
             *       took the focus back and left the operator on the committing control while the safe
             *       one merely appeared selected.
             * WHY : Assumptions: `autoFocus` on the design system's own cancel control rather than an
             *       effect of ours. The control is rendered inside a portal on open, so the platform's
             *       own mount-time focus is both the earliest and the only moment guaranteed to be after
             *       it exists.
             * WHY : ⚠️ Assumptions: ESCAPE needs nothing added here, and this was verified rather than
             *       assumed. The dismissal is handled two layers down, by
             *       `ui/node_modules/@rc-component/portal/lib/useEscKeyDown.js`, which keeps a stack of
             *       open portals on a window `keydown` listener and withdraws the top one; a
             *       screen-level listener was drafted, and removing it left the prompt closing on Escape
             *       just the same, so it was withdrawn as a second handler for one key. What that layer
             *       does is CLOSE the portal, which reaches this screen as `onOpenChange(false)` and
             *       nothing else -- it does not run `onCancel` -- so Escape withdraws the question and
             *       leaves the entry standing, where this prompt's own cancel control additionally
             *       performs the reference's PF12 arm and discards it. Both write nothing, which is the
             *       property that matters; only Escape is safe to press without losing work. The
             *       behaviour is asserted in `ui/src/test/accountUpdate.test.tsx` so that a library
             *       change cannot take it away silently.
             */
            cancelButtonProps={{ autoFocus: true }}
            /*
             * WHY : ⚠️ Refactoring Rationale: the surface opens DOWNWARD, and the design system's default
             *       of upward is what a browser pass measured the cost of. The trigger sits immediately
             *       below the last form row, and the surface needs roughly ninety pixels of clear space
             *       above that trigger to fit. At a 1280-pixel width the gap is just wide enough and the
             *       surface clipped only the bottom 4.39 pixels of the three `Phone 2` boxes -- their
             *       padding and border, not their digits. At 375 the single-column stack closes that gap,
             *       and the surface covered the `Primary Card Holder Y/N:` control across 100 per cent of
             *       its width and 88.7 per cent of its height, with `elementFromPoint` at that control's
             *       centre returning the surface's own title element: the operator was asked to commit a
             *       record while one of its values was painted over. Downward, the surface opens into the
             *       space beneath the form, which no field occupies at any width.
             * WHY : Assumptions: the space beneath the form is the frame's pinned zone, and the only
             *       thing the surface overlays there is this screen's own message line -- measured, it
             *       covers NO legend key at either width, its foot landing two pixels above the legend
             *       row at 1280 and clear of all four keys at 375. Nothing is lost even from the line it
             *       does cover: the surface's title IS the sentence that line is showing, both reading
             *       `MESSAGES.PROMPT_FOR_CONFIRMATION`, so it covers only a restatement of its own
             *       question. Covering a field's value costs the operator a datum they were asked about;
             *       covering a restatement of the question costs nothing.
             * WHY : Alternatives Considered: (1) replacing the component with a dialog, which positions
             *       itself against the viewport and so cannot collide with a field at all. REJECTED on
             *       AAP grounds: section 0.4.1.4 names `Popconfirm` for this screen, and the block above
             *       already records that decision. (2) Constraining the surface's width so it cannot reach
             *       across the column. Rejected because the width is set by the catalogued sentence it
             *       titles, so narrowing it rewraps a verbatim string. (3) Leaving it upward and moving
             *       the trigger further from the form. Rejected because the trigger's position is itself
             *       reasoned about above -- it is the last element of the body so the row-22 band stays
             *       adjacent to row 23 -- and inserting space to accommodate an overlay would put layout
             *       at the service of a popup.
             * WHY : Assumptions: the horizontal clamp the same pass measured is NOT corrected here and is
             *       not a defect. At 375 the design system pins the surface's left edge at 0 because a
             *       fixed-width card cannot be centred on a trigger whose centre is 79.6 pixels in; the
             *       card stays wholly inside the display, 0 through 267 of 375, with both answers visible.
             *       That is the overflow handling keeping the question reachable, which is what it is for.
             */
            placement="bottom"
            disabled={!saveIsValid || saving}
            open={confirmingSave}
            onOpenChange={
              /**
               * Routes the design system's own open and close gestures through this screen's state.
               * @param {boolean} open - Whether the component is asking to be shown.
               * @returns {void} Completion is represented by the screen's own state.
               */
              (open: boolean): void => {
                if (open) {
                  requestSaveConfirmation();

                  return;
                }
                setConfirmingSave(false);
              }
            }
            onConfirm={saveEdits}
            onCancel={abandonSaveConfirmation}
          >
            <Button
              ref={saveConfirmationAnchor}
              type="primary"
              loading={saving}
              disabled={!saveIsValid || saving}
            >
              {ACCOUNT_UPDATE_KEY_LABELS.PFK05}
            </Button>
          </Popconfirm>
        </Card>
      ) : null}

      {/*
       * WHY : ⚠️ Refactoring Rationale: the row-22 INFORMATION band is no longer rendered here. It was,
       *       on the ground that the mapset declares `INFOMSG` at `POS=(22,23)` -- inside the screen's
       *       own field area rather than in the frame's zone -- and that reading is what a responsive
       *       review measured the cost of: the screen's real advisory sat between roughly 1688 and 3040
       *       pixels down the document, below the fold at every width tested, while the frame's own
       *       row-22 zone stood at the reserved height with nothing in it. A line at `POS=(22,23)` on a
       *       24-row display is one row above the message line and two above the legend, which in this
       *       frame is the pinned zone; being inside the field AREA does not put it inside the scrolling
       *       CONTENT. It is delegated in the `useShellSlot` call above, beside the row-23 line it is
       *       declared next to, and the frame paints the two adjacent as the terminal did.
       * WHY : Assumptions: nothing about the band's identity changes for a reader or a suite. The frame
       *       renders `MessageBand` with `channel="information"`, which carries
       *       `INFORMATION_BAND_TEST_ID` exactly as this element did, and it renders that zone if and
       *       only if a screen delegates the slot -- so publishing here and rendering there leaves
       *       exactly one row-22 band on the document rather than two.
       */}

      {/*
       * WHY : ⚠️ Refactoring Rationale: a SECOND band stood here, on the `error` channel, and it is
       *       withdrawn in favour of the delegation in `useShellSlot` above. The finding it was written
       *       for is real and is preserved: `report` writes this screen's refusals and its one
       *       acknowledgement into state, and before either remedy nothing rendered them, so all
       *       twenty-four field refusals, the concurrency sentence and the stored-write acknowledgement
       *       were computed and discarded. Rendering it here fixed that but put row 23 inside the
       *       screen's own field area, one row above where the mapset declares `ERRMSG` and below the
       *       row this frame reserves; delegating the same text and the same severity fixes it in the
       *       zone that owns the row. The suites that query the two lines apart are unaffected, because
       *       the band the shell paints carries `MESSAGE_BAND_TEST_ID` and the informational band above
       *       carries `INFORMATION_BAND_TEST_ID` exactly as before.
       */}
      {/*
       * Assumptions: the legend the shell paints from this screen's delegated bindings dispatches through
       * the same `invoke` a real key press does, so a clicked legend control and its key cannot diverge.
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
