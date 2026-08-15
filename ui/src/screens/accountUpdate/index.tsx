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
 * Trade-offs: it runs no business validation of its own. The reference edits twenty-four fields in a
 * fixed order on the Enter turn and writes only on the later F5 turn, and the migrated contract
 * publishes ONE operation that validates and writes together -- there is no validate-only address to
 * call on Enter. Two readings were available. Re-implementing the twenty-four edit paragraphs in the
 * browser would put one rule in two places, and `ui/src/api/types.ts` states why that is the wrong
 * one: a browser-side refusal produces a different first message than the reference does, and the
 * service is the authority. So Enter performs the reference's change detection and moves to the
 * confirmation state, and the service's own field errors surface on the F5 turn instead -- one turn
 * later than the terminal showed them, with no write performed and no entry lost, and every sentence
 * still the reference's own because the response body supplies it.
 */

import {
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
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
import { useCallback, useEffect, useState } from 'react';
import type { CSSProperties, ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { isConflictFailure, readAccountView, updateAccount } from '../../api/accounts';
import type {
  AccountUpdateResponse,
  AccountViewResponse,
  SensitiveAccountUpdateRequest,
} from '../../api/accounts';
import { isApiRequestError } from '../../api/client';
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
import { MessageBand } from '../../layout/MessageBand';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { PfKeyBar } from '../../layout/PfKeyBar';
import { ScreenHeader } from '../../layout/ScreenHeader';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap } from '../../layout/usePfKeys';
import { useServerInstant } from '../../hooks/useServerInstant';
import {
  ABEND_DATA_FIELDS,
  ACCOUNT_UPDATE_FIELD_LABELS,
  MESSAGE_TEMPLATES,
  STATUS_MESSAGES,
  UNEXPECTED_ABEND_OCCURRED,
  UNEXPECTED_DATA_SCENARIO,
  formatMessageTemplate,
} from '../../messages/messages';
import type { MapsetName } from '../../messages/messages';
import {
  BMS_COLOR_TOKENS,
  FIELD_ERROR_TOKENS,
  SPACING_TOKENS,
  TYPOGRAPHY_TOKENS,
} from '../../theme/tokens';
import { MAIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';

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

/** Accessible names for the parts of each split group, additive and never rendered visibly. */
export const ACCOUNT_UPDATE_PART_NAMES = {
  year: 'Year',
  month: 'Month',
  day: 'Day',
  areaCode: 'Area code',
  prefix: 'Prefix code',
  lineNumber: 'Line number code',
} as const;

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
 */

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

/** Screen-level sentences this screen renders, taken verbatim from the catalog keyed by its program. */
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

/** Matches a run of decimal digits, or nothing, used to reject a non-digit in a numeric-only part. */
const NON_DIGITS = /[^0-9]/gu;

/** Matches an account identifier the reference's own key edit accepts: eleven non-zero digits. */
const ACCEPTABLE_ACCOUNT_ID = /^[0-9]{1,11}$/u;

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
 * WHY : Assumptions: the four protected identifier members are seeded EMPTY and the masked value the
 *       read returned is offered beside the control instead of inside it. The reference could seed
 *       them because a terminal was shown the whole value -- `MOVE ACUP-NEW-CUST-GOVT-ISSUED-ID TO
 *       ACSGOVTO` at `app/cbl/COACTUPC.cbl` L2946 -- whereas `ui/src/api/types.ts` declares only
 *       `ssnMasked` and `governmentIssuedIdMasked` on the read shape, because no read operation
 *       returns either in the clear. Seeding a mask into the control would submit the mask, and the
 *       service would then store it.
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
 * @param {AccountViewResponse} view - The account and its customer, as the read returned them.
 * @returns {AccountUpdateFormValues} Every control's value, ready to render.
 */
export function formValuesFrom(view: AccountViewResponse): AccountUpdateFormValues {
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
    creditLimit: view.account.creditLimit,
    expirationDateYear: expirationDate.year,
    expirationDateMonth: expirationDate.month,
    expirationDateDay: expirationDate.day,
    cashCreditLimit: view.account.cashCreditLimit,
    reissueDateYear: reissueDate.year,
    reissueDateMonth: reissueDate.month,
    reissueDateDay: reissueDate.day,
    currentBalance: view.account.currentBalance,
    currentCycleCredit: view.account.currentCycleCredit,
    groupId: view.account.groupId,
    currentCycleDebit: view.account.currentCycleDebit,
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
  if (!ACCEPTABLE_ACCOUNT_ID.test(typed) || Number(typed) === 0) {
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
    creditLimit: values.creditLimit,
    cashCreditLimit: values.cashCreditLimit,
    currentBalance: values.currentBalance,
    currentCycleCredit: values.currentCycleCredit,
    currentCycleDebit: values.currentCycleDebit,
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
  /** Whether the record must be re-read, which only the concurrency refusal requires. */
  readonly reread: boolean;
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
 * changed sets the screen BACK to showing details, because the reference re-reads and redisplays it
 * rather than leaving the operator on a dead edit; anything else is the committed action.
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
 *   whether to re-read.
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
      reread: true,
    };
  }
  if (isApiRequestError(failure) && failure.problem.fieldErrors.length > 0) {
    const [first] = failure.problem.fieldErrors;

    return {
      action: 'CHANGES_NOT_OK',
      statement: {
        message: first === undefined ? failure.problem.message : first.message,
        severity: 'error',
      },
      fieldErrors: failure.problem.fieldErrors,
      reread: false,
    };
  }

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
    reread: false,
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

/** Every input the field renderer needs, passed as one object so no member is positional. */
export interface FieldRenderSpec {
  /** Which member of the form this control edits. */
  readonly field: AccountUpdateFieldName;
  /** Label rendered against the control, or the empty string when a group label names it instead. */
  readonly label: string;
  /** Whether entry is coerced to decimal digits, which every numeric-only part requires. */
  readonly numeric?: boolean | undefined;
  /** Whether the control renders in the fixed-pitch face, which the amounts and identifiers use. */
  readonly fixedPitch?: boolean | undefined;
  /** Placeholder text, used only for the three parts whose mapset field declares an initial. */
  readonly placeholder?: string | undefined;
  /** Accessible name, supplied where a shared label cannot name one part of a split group. */
  readonly ariaLabel?: string | undefined;
  /** Whether this control takes initial focus, true for the mapset's single `IC` field only. */
  readonly autoFocus?: boolean | undefined;
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
  const [fieldErrors, setFieldErrors] = useState<ReadonlyMap<string, FieldError>>(new Map());
  const [statement, setStatement] = useState<BandStatement>({ message: null, severity: 'error' });
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [abended, setAbended] = useState(false);

  /**
   * Publishes one screen-level statement, replacing whatever the band held.
   * @param {string | null} message - The sentence to render, or `null` to clear the band.
   * @param {MessageBandSeverity} severity - Appearance matching the source field the sentence came
   *   from: `info` for the reference's information line and `error` for its message line.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function report(message: string | null, severity: MessageBandSeverity): void {
    setStatement({ message, severity });
  }

  /**
   * Moves to one change action and states the information line the reference paints for it.
   *
   * Assumptions: the two always move together, because `3250-SETUP-INFOMSG` derives the information
   * line FROM the change action on every screen send -- so an action set without its line would leave
   * the previous action's prompt on screen.
   * @param {ChangeAction} next - The action to move to.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function enterAction(next: ChangeAction): void {
    setAction(next);
    report(informationLineFor(next), 'info');
  }

  const readAccount = useCallback(
    /**
     * Reads one account with its customer and seeds the form from it.
     *
     * Assumptions: this is `9000-READ-ACCT`, which two callers reach in the reference -- the fetch
     * turn and the cancel turn, the latter at `app/cbl/COACTUPC.cbl` L2572 to L2580, which re-reads
     * rather than restoring its snapshot so a cancel shows the record as it stands now.
     * @param {string} accountId - The identifier to read, as the operator typed it.
     * @returns {Promise<boolean>} Resolves `true` when the record was read and the form seeded, and
     *   `false` when the read was refused, in which case the band already carries the refusal.
     */
    async (accountId: string): Promise<boolean> => {
      setLoading(true);
      setFieldErrors(new Map());
      try {
        const read = await readAccountView(accountId);
        const seeded = formValuesFrom(read.account);
        setValues(seeded);
        setBaseline(seeded);
        setRevision(read.revision);

        return true;
      } catch (failure: unknown) {
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
        report(readFailureMessage(failure), 'error');

        return false;
      } finally {
        setLoading(false);
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
      report(informationLineFor('DETAILS_NOT_FETCHED'), 'info');
    },
    [],
  );

  useEffect(
    /**
     * Places the cursor on the first marked control in the mapset's paint order.
     *
     * Assumptions: this runs after the render that shows the marks, which is why it is an effect and
     * not part of the handler that set them -- the control cannot be focused before it carries its
     * error state. It reproduces `MOVE -1 TO <field>L`, and it addresses the control by the
     * identifier this module states rather than by a name the design system composes.
     * @returns {void} Completion is the focus change, or nothing when no control was marked.
     */
    function placeCursorOnFirstError(): void {
      const target = firstFieldInError(fieldErrors);
      if (target === null) {
        return;
      }
      const element = document.getElementById(fieldDomId(target));
      if (element !== null) {
        element.focus();
      }
    },
    [fieldErrors],
  );

  /**
   * Records one control's entry, coercing it to digits where the mapset declares numeric entry.
   * @param {AccountUpdateFieldName} field - The form member being edited.
   * @param {string} entry - The raw value the control reported.
   * @param {boolean} numeric - Whether this field accepts decimal digits only.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function recordEntry(field: AccountUpdateFieldName, entry: string, numeric: boolean): void {
    const accepted = numeric ? digitsOnly(entry) : entry;
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

    readThenShowDetails();
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
   * @returns {void} Completion is represented by the screen's own state.
   */
  function readThenShowDetails(): void {
    readAccount(values.accountId).then(
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
   */

  /**
   * Validates the edits and asks for confirmation, which is the Enter arm of the details action.
   *
   * Assumptions: this writes nothing at all. `2000-DECIDE-ACTION`'s show-details arm at
   * `app/cbl/COACTUPC.cbl` L2582 to L2590 moves to the confirmation action when the edits passed and
   * something changed, and otherwise leaves the action where it is; only the later F5 turn performs
   * `9600-WRITE-PROCESSING`.
   *
   * Assumptions: an unchanged submission is refused with the reference's own sentence and the action
   * does not move, which `1205-COMPARE-OLD-NEW` reaching L1769 produces. The band carries that
   * sentence in the message channel rather than the information channel, because the reference latches
   * it into `WS-RETURN-MSG`.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function validateEdits(): void {
    if (baseline === null) {
      report(MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text, 'error');
      return;
    }
    if (!hasChanges(values, baseline)) {
      setFieldErrors(new Map());
      report(MESSAGES.NO_CHANGES_DETECTED.text, 'error');
      return;
    }
    setFieldErrors(new Map());
    enterAction('CHANGES_OK_NOT_CONFIRMED');
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
     * WHY : Assumptions: a second confirmation arriving while a write is in flight is ignored, and the
     *       guard is here rather than expressed as a disabled binding -- a disabled binding reports
     *       through the hook's invalid-key channel, and this screen's channel coerces to the Enter
     *       arm, so disabling it would route a suppressed key back into the screen. The reference
     *       needs no such guard, because a terminal turn is serialised.
     */
    if (saving) {
      return;
    }
    if (revision === null) {
      report(MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text, 'error');
      return;
    }

    setSaving(true);
    updateAccount(updateRequestFrom(values), revision).then(
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
      (applied: { readonly account: AccountUpdateResponse; readonly revision: string | null }) => {
        setSaving(false);
        const stored = formValuesFromApplied(applied.account);
        setValues(stored);
        setBaseline(stored);
        setRevision(applied.revision);
        setFieldErrors(indexFieldErrors(applied.account.fieldErrors));
        if (applied.account.fieldErrors.length > 0) {
          setAction('CHANGES_NOT_OK');
          report(applied.account.returnMessage ?? informationLineFor('CHANGES_NOT_OK'), 'error');
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
          setAction('SHOW_DETAILS');
          report(MESSAGES.NO_CHANGES_DETECTED.text, 'error');
          return;
        }

        /*
         * WHY : Refactoring Rationale: the commit confirmation is taken from the ACTION and not from
         *       the response, and preferring the response here was the defect this replaced. The
         *       service answers a stored write with `Looks Good.... so far`, which is the reference's
         *       own text but belongs to its VALIDATION step -- `WS-RETURN-MSG` set by
         *       `1200-EDIT-MAP-INPUTS` when every edit passed -- whereas the sentence the reference
         *       paints once a write has committed is the information line `3250-SETUP-INFOMSG` selects
         *       for `ACUP-CHANGES-OKAYED-AND-DONE`. The terminal painted both at once in two separate
         *       fields; with one band the confirmation is the one that has to survive, because it is
         *       the only sentence that distinguishes a stored write from a passed validation.
         */
        setAction('CHANGES_OKAYED_AND_DONE');
        report(informationLineFor('CHANGES_OKAYED_AND_DONE'), 'success');
      },
      /**
       * Classifies the refusal and moves to the action the reference sets for it.
       * @param {unknown} failure - The caught value from the update call.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (failure: unknown) => {
        setSaving(false);
        const rejection = classifySaveRejection(failure);
        setFieldErrors(indexFieldErrors(rejection.fieldErrors));
        setAction(rejection.action);
        report(rejection.statement.message, rejection.statement.severity);
        if (rejection.reread) {
          /*
           * WHY : Assumptions: the record is re-read and the concurrency refusal is then RESTATED,
           *       because the reference shows both channels at once on this turn and this tree has one
           *       band. `2000-DECIDE-ACTION` sets the show-details action, so `3250-SETUP-INFOMSG`
           *       paints `Update account details presented above.` into the information line while
           *       `WS-RETURN-MSG` still holds the changed-record sentence in the message line. With one
           *       band the message line wins, because it is the sentence that tells the operator why
           *       the values on screen are not the ones they submitted.
           */
          readAccount(values.accountId).then(
            /**
             * Restates the concurrency refusal after the re-read has reseeded the form.
             * @returns {void} Completion is represented by the screen's own state.
             */
            (): void => {
              report(rejection.statement.message, rejection.statement.severity);
            },
            /**
             * Reports a failure that escaped the reader's own handling.
             * @param {unknown} readFailure - The caught value from the re-read.
             * @returns {void} Completion is represented by the screen's own state.
             */
            (readFailure: unknown): void => {
              report(readFailureMessage(readFailure), 'error');
            },
          );
        }
      },
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
    report(null, 'error');
    readThenShowDetails();
  }

  /**
   * Runs the Enter arm for whichever action the screen is in.
   *
   * Assumptions: the arms are `2000-DECIDE-ACTION`'s outer `EVALUATE`, and the two failure actions
   * plus the committed action all reset the screen to its opening state on the next turn -- which the
   * reference does in its main branch at `app/cbl/COACTUPC.cbl` L968 to L989 by re-initialising its
   * work areas and setting the not-fetched action. The final arm is the branch the reference itself
   * treats as unreachable and abends on.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function processEnter(): void {
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
        report(informationLineFor(action), 'info');
        return;
      case 'CHANGES_OKAYED_AND_DONE':
      case 'CHANGES_OKAYED_LOCK_ERROR':
      case 'CHANGES_OKAYED_BUT_FAILED':
        setValues(blankFormValues());
        setBaseline(null);
        setRevision(null);
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
  const cancelIsValid = action !== 'DETAILS_NOT_FETCHED';
  const saveIsValid = action === 'CHANGES_OK_NOT_CONFIRMED';
  const cancelLegendIsPainted = isChangesMade(action) && action !== 'CHANGES_OKAYED_AND_DONE';

  const keyHandlers: PfKeyHandlerMap = {
    ENTER: {
      /** Runs the arm for the current action, the mapset's `ENTER=Process` action. */
      onInvoke: processEnter,
      label: ACCOUNT_UPDATE_KEY_LABELS.ENTER,
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
    },
    ...(saveIsValid
      ? {
          PFK05: {
            /** Writes the confirmed edits, the mapset's `F5=Save` action. */
            onInvoke: saveEdits,
            label: ACCOUNT_UPDATE_KEY_LABELS.PFK05,
          },
        }
      : {}),
    ...(cancelIsValid
      ? {
          PFK12: {
            /** Discards uncommitted edits and re-reads, the mapset's `F12=Cancel` action. */
            onInvoke: cancelEdits,
            label: cancelLegendIsPainted ? ACCOUNT_UPDATE_KEY_LABELS.PFK12 : '',
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
     * @returns {void} Nothing; the coerced arm runs for its own effects.
     */
    onInvalidKey: () => {
      processEnter();
    },
  });

  /**
   * Renders one control with its label, error state and the reference's blank marker.
   *
   * Assumptions: the marker is written into the control's VALUE and only for the blank state, which is
   * exactly what `app/cpy/CSSETATY.cpy` L18 to L27 does -- it moves the error colour into the field's
   * colour subfield when the flag is not-OK OR blank, and moves a literal asterisk into the field's
   * OUTPUT subfield only when it is blank. Writing it into the value destroys nothing, because a blank
   * field is what the state means, and it round-trips faithfully: the reference reads the marker back
   * as an unfilled field at `1100-RECEIVE-MAP`, and the service's shared never-supplied test folds the
   * same character into the same answer.
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
    const editable = isFieldEditable(action, spec.field);
    const isBlankRefusal = error !== undefined && error.state === 'BLANK';
    const numeric = spec.numeric === true;
    const controlStyle: CSSProperties =
      spec.fixedPitch === true ? { fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData] } : {};

    /*
     * WHY : Assumptions: each optional attribute is SPREAD in rather than assigned a value that may be
     *       nothing, because `ui/tsconfig.json` enables `exactOptionalPropertyTypes` -- under which an
     *       omitted property and one holding `undefined` are different states, and the design system's
     *       own prop types admit only the omission. Assigning the union would compile the error state
     *       and the no-error state into the same call, which is exactly the distinction the setting
     *       exists to keep.
     */
    return (
      <Form.Item
        {...(spec.label === '' ? {} : { label: spec.label })}
        htmlFor={fieldDomId(spec.field)}
        {...(error === undefined ? {} : { validateStatus: 'error' as const, help: error.message })}
      >
        <Input
          id={fieldDomId(spec.field)}
          value={isBlankRefusal ? FIELD_ERROR_TOKENS.blankMarker : values[spec.field]}
          maxLength={ACCOUNT_UPDATE_FIELD_WIDTHS[spec.field]}
          disabled={!editable}
          autoFocus={spec.autoFocus === true}
          {...(spec.placeholder === undefined ? {} : { placeholder: spec.placeholder })}
          {...(spec.ariaLabel === undefined ? {} : { 'aria-label': spec.ariaLabel })}
          {...(numeric ? { inputMode: 'numeric' as const } : {})}
          style={controlStyle}
          onChange={
            /**
             * Records the entry, coercing it where the mapset declares numeric entry.
             * @param {{ target: { value: string } }} event - The control's change event.
             * @param {{ value: string }} event.target - The control the event came from.
             * @param {string} event.target.value - The entry exactly as the control reports it.
             * @returns {void} Completion is represented by the screen's own state.
             */
            (event: { readonly target: { readonly value: string } }): void => {
              recordEntry(spec.field, event.target.value, numeric);
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
   *       have anything to reject. The mapset agrees: none of the five declares a `PICOUT` operand,
   *       so nothing formats them on output either. A numeric control would additionally route the
   *       value through a JavaScript number, which is an IEEE-754 double, and the cent it loses would
   *       produce a plausible balance rather than an error.
   *       Trade-offs: the operator gets no stepper and no thousands grouping, and gains an amount that
   *       is the same characters end to end and a refusal in the reference's own words.
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
    return (
      <Form.Item label={label}>
        <Space.Compact>
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
    return (
      <Form.Item label={label}>
        <Space.Compact>
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
  const gridGutter = token[SPACING_TOKENS.sectionGapMedium];

  const titleStyle: CSSProperties = {
    color: cssVar[BMS_COLOR_TOKENS.NEUTRAL],
    fontSize: cssVar[TYPOGRAPHY_TOKENS.screenTitleSize],
    lineHeight: cssVar[TYPOGRAPHY_TOKENS.screenTitleLineHeight],
  };
  const sectionStyle: CSSProperties = { color: cssVar[BMS_COLOR_TOKENS.NEUTRAL] };

  return (
    <Flex vertical gap="large">
      {/*
       * Assumptions: the header band is composed here because both of its screen-specific values are
       * this screen's own -- the transaction identifier and the program name that rows 1 and 2 of the
       * 3270 screen painted into `TRNNAME` and `PGMNAME` -- and the instant comes from the server
       * clock hook rather than the browser's, which is what reproduces the single region clock
       * `FUNCTION CURRENT-DATE` gave every terminal.
       */}
      <ScreenHeader
        transactionId={ACCOUNT_UPDATE_TRANSACTION_ID}
        programName={ACCOUNT_UPDATE_PROGRAM_NAME}
        now={paintedAt}
      />
      {/*
       * Assumptions: the heading is a `Typography.Title` carrying the mapset's own 14-character
       * `COLOR=NEUTRAL` field, and its colour and size come from the bridge rather than from a literal
       * -- `NEUTRAL` resolves to the de-emphasis role and the title size to the fourth heading step,
       * which the bridge chose because a larger step costs vertical space on a screen of 128 fields.
       */}
      <Typography.Title level={4} style={titleStyle}>
        {ACCOUNT_UPDATE_HEADINGS.screen}
      </Typography.Title>
      {/*
       * Refactoring Rationale: the band is rendered unconditionally and reserves its space at all
       * times, which row 23 of a 24-row terminal did for free. On THIS screen that matters more than
       * on most: the reference re-sends the same map with row 23 populated, so a band that appeared and
       * disappeared would move the very control an operator is correcting, mid-correction, on a form
       * of forty editable fields.
       */}
      <MessageBand
        message={statement.message}
        severity={statement.severity}
        mapset={ACCOUNT_UPDATE_MAPSET}
      />
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
          <Row gutter={gridGutter}>
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

            {/* Mapset row 9: the right-hand column only -- the cycle credit. */}
            <Col xs={24} md={12} />
            <Col xs={24} md={12}>
              {renderAmount(
                'currentCycleCredit',
                ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.currentCycleCredit,
              )}
            </Col>

            {/* Mapset row 10: the account group and the cycle debit. */}
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
           * Assumptions: the divider stands in for mapset row 11, which paints the 16-character
           * `COLOR=NEUTRAL` heading `Customer Details` and nothing else -- a whole terminal row given
           * to separating the two records the screen edits together.
           */}
          <Divider titlePlacement="start">
            <Typography.Text strong style={sectionStyle}>
              {ACCOUNT_UPDATE_HEADINGS.customerSection}
            </Typography.Text>
          </Divider>

          <Row gutter={gridGutter}>
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
              <Form.Item label={ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.ssn}>
                <Space.Compact>
                  {renderField({
                    field: 'ssnPart1',
                    label: '',
                    numeric: true,
                    fixedPitch: true,
                    placeholder: SSN_PART_PLACEHOLDERS.ssnPart1,
                    ariaLabel: NAME_TOKENS.SSN_FIRST_3_CHARS,
                  })}
                  <Typography.Text type="secondary">{DATE_PART_SEPARATOR}</Typography.Text>
                  {renderField({
                    field: 'ssnPart2',
                    label: '',
                    numeric: true,
                    fixedPitch: true,
                    placeholder: SSN_PART_PLACEHOLDERS.ssnPart2,
                    ariaLabel: NAME_TOKENS.SSN_4TH_AND_5TH_CHARS,
                  })}
                  <Typography.Text type="secondary">{DATE_PART_SEPARATOR}</Typography.Text>
                  {renderField({
                    field: 'ssnPart3',
                    label: '',
                    numeric: true,
                    fixedPitch: true,
                    placeholder: SSN_PART_PLACEHOLDERS.ssnPart3,
                    ariaLabel: NAME_TOKENS.SSN_LAST_4_CHARS,
                  })}
                </Space.Compact>
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
            {/* Mapset row 17: address line 2 with the postal code to its right. */}
            <Col xs={24} md={16}>
              {renderField({ field: 'addressLine2', label: '' })}
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
              {renderField({
                field: 'governmentIssuedId',
                label: ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.governmentIssuedId,
              })}
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
       *       is offered only while the save key is valid, so it cannot appear on a turn the reference
       *       refuses the key.
       */}
      {saveIsValid ? (
        <Card size="small">
          <Popconfirm
            title={MESSAGES.PROMPT_FOR_CONFIRMATION.text}
            okText={ACCOUNT_UPDATE_KEY_LABELS.PFK05}
            cancelText={ACCOUNT_UPDATE_KEY_LABELS.PFK12}
            okType="primary"
            onConfirm={saveEdits}
            onCancel={cancelEdits}
          >
            <Button type="primary" loading={saving}>
              {ACCOUNT_UPDATE_KEY_LABELS.PFK05}
            </Button>
          </Popconfirm>
        </Card>
      ) : null}

      {/*
       * Assumptions: the legend colour is left at the bar's default, which `app/bms/COACTUP.bms` L493
       * to L507 confirms -- all three of this mapset's row-24 fields are `COLOR=YELLOW`, the majority
       * the bar already defaults to.
       */}
      <PfKeyBar keys={bindings} onInvoke={invoke} />
    </Flex>
  );
}

/*
 * WHY : Alternatives Considered: exporting this component under one name only. Both spellings are
 *       published deliberately. `ui/src/router.tsx` loads every screen with
 *       `lazy(async () => ({ default: module.<Named> }))`, so the NAMED export is what the route table
 *       reaches for and matches the four screens already mounted there; the DEFAULT export is what the
 *       file's own specification requires and what lets a route be declared as
 *       `lazy(() => import('./screens/accountUpdate'))` with no adapter. Publishing one would break
 *       whichever caller expected the other, and this is not the re-export barrel AAP section 0.6.2.1
 *       forbids -- that prohibition is on a module that re-exports somebody else's screen, whereas
 *       this is the screen's own module naming itself twice.
 */
export default AccountUpdateScreen;
