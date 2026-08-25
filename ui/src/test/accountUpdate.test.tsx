/**
 * @file Component test for the account-update screen, the migration target of BMS mapset
 * `COACTUP` / map `CACTUPA` and program `app/cbl/COACTUPC.cbl`.
 *
 * Purpose
 * -------
 * WHAT: this file is the whole verification of the largest screen in the system -- 128
 * `DFHMDF` definitions and 54 symbolic-map inputs -- and of the one genuine
 * optimistic-concurrency contract in the migration. It asserts five things the reference
 * fixes and nothing else can: the declared width of every input, the verbatim spelling of
 * every sentence and label the program carries, the attention identifiers the program
 * dispatches, the per-field refusal contract, and the two-step validate-then-commit
 * ordering that ends in a concurrency refusal.
 *
 * Parameters, returns and exceptions (module analogues)
 * ----------------------------------------------------
 * A test module takes no parameters and returns no value. Its inputs are the fixtures and
 * transport answers each case arranges below; its output is the pass/fail verdict Vitest
 * reports; and it raises exactly one class of exception deliberately -- an assertion
 * failure -- plus the guard in {@link control}, which throws when a control the reference
 * declares is absent, because a silent `null` there would degrade a missing-control defect
 * into a confusing type error further down.
 *
 * ⚠️ Why this file is the only verification this screen gets
 * ---------------------------------------------------------
 * Assumptions: there is NO golden master for `COACTUPC` and there cannot be one.
 * `tests/README.md` section 1.1 records the limitation verbatim: "Online `CO*` CICS
 * programs cannot run end-to-end without a CICS runtime (absent on the runner); only their
 * extractable field-validation logic is unit-tested." The COBOL suite is the parity oracle
 * for the batch chain only, so nothing outside this file compares this screen against the
 * reference. Every constant asserted below is therefore traced to a file and a line in the
 * baseline, because a citation is the only oracle available.
 *
 * How this file satisfies Rule 1, and why that is not a second convention
 * ----------------------------------------------------------------------
 * Rule 1 (Explainability) requires a docstring on every module entry point and function
 * stating purpose, parameters, returns and exceptions, and an inline comment justifying
 * every non-obvious decision under one of four named categories. `tests/README.md` section
 * 12 imposes the identical obligation on "every new test, fixture builder, helper, mock,
 * and runner routine" and calls it "a hard review gate". The two AGREE, so this file
 * extends an established house convention rather than importing a competing one, and it
 * conforms to `docs/CODE_DOCUMENTATION_STANDARD.md` for the written form of both halves.
 *
 * Five places where the specification and the landed tree disagree
 * ---------------------------------------------------------------
 * ⚠️ Each is resolved toward the mechanically enforced artifact, and each is recorded here
 * because a reader who checks this file against the specification will otherwise read a
 * defect where there is a decision.
 *
 * 1. Runner symbols are IMPORTED. `ui/vitest.config.ts` does set `globals: true`, but
 *    `ui/tsconfig.json` sets `"types": []`, so no ambient test declaration exists and
 *    `tsc --noEmit` -- which CI runs over `src` -- fails on the first bare `describe`.
 *    `ui/src/test/setup.ts` states this in its own header and every sibling test file in
 *    the package imports the same four symbols. Trade-offs: the import line is redundant at
 *    run time; the alternative is a type error in the gate that must pass.
 * 2. Rationale labels are PLURAL. `docs/CODE_DOCUMENTATION_STANDARD.md` fixes exactly four
 *    labels -- `Alternatives Considered:`, `Refactoring Rationale:`, `Assumptions:`,
 *    `Trade-offs:` -- and records that the singular forms "are not permitted abbreviations".
 *    `config/rule1/rule1_gate.py` is a fail-closed lexical gate that treats the singular
 *    stems as prohibited tokens in every `.tsx` file, so the plural is what passes.
 * 3. `WHAT:` appears in this header block ONLY. The same gate forbids a statement-level
 *    `WHAT:` comment, on the ground that a comment sitting above a statement and naming it
 *    restates the code. Statement-level rationale below is introduced with `WHY :`.
 * 4. The component is a NAMED export. There is no default export in
 *    `ui/src/screens/accountUpdate/index.tsx`; `ui/src/router.tsx` adapts the name to a
 *    default at the lazy boundary, and that module's closing comment records the removal.
 * 5. The account key travels in the request BODY, not a URL path segment. Every operation
 *    in `ui/src/api/accounts.ts` posts to a static path. The property AAP section 0.7.1
 *    actually requires -- selection context is request-scoped rather than session-scoped --
 *    holds either way, and is what {@link theAccountKeyTravelsWithEveryRequest} asserts.
 *
 * One sentence in the reference is deliberately not asserted
 * ---------------------------------------------------------
 * Assumptions: `'File Error: '` at `app/cbl/COACTUPC.cbl` L391 has no catalogue entry
 * anywhere in `ui/src`, and it is not a band sentence -- it is a `FILLER` prefix inside
 * `WS-FILE-ERROR-MESSAGE`, concatenated with an operation name and a file name to build a
 * diagnostic. Asserting it would mean retyping a literal the catalogue does not carry,
 * which is the one thing the message-fidelity discipline forbids, so it is recorded here
 * instead of being asserted wrongly.
 */

// WHY : see the first numbered divergence in the header block: `ui/tsconfig.json` declares no
//       ambient test types, so these four symbols are imported rather than assumed.
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  isConflictFailure,
  readAccountView,
  updateAccount,
  validateAccountUpdate,
} from '../api/accounts';
import type { AccountUpdateResponse, AccountViewResponse, CustomerDetail } from '../api/accounts';
import { ApiRequestError } from '../api/client';
import type { ApiError, FieldError } from '../api/types';
import { BLANK_FIELD_MARKER_CHARACTERS } from '../layout/fieldHelp';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, PRIMARY_ACTION_AIDS } from '../layout/PfKeyBar';
import {
  ACCOUNT_UPDATE_FIELD_LABEL_SOURCES,
  ACCOUNT_UPDATE_FIELD_LABELS,
  STATUS_MESSAGES,
} from '../messages/messages';
/*
 * WHY : Assumptions: the address is imported from the ROUTE TABLE rather than spelled here, so this
 *       file opens the screen at the same address the application mounts it at and a moved route
 *       fails here instead of silently testing a route nobody visits. `ui/src/router.tsx` is the
 *       module that owns the table, which is why the constant is taken from there rather than from
 *       the navigation helpers that also re-declare it.
 */
import { ACCOUNT_UPDATE_PATH } from '../router';
import { SECTION_HEADING_LEVEL } from '../layout/ScreenTitle';
import {
  ACCOUNT_UPDATE_FIELD_LABELS_PAINTED,
  ACCOUNT_UPDATE_FIELD_WIDTHS,
  ACCOUNT_UPDATE_HEADINGS,
  ACCOUNT_UPDATE_KEY_LABELS,
  ACCOUNT_UPDATE_PART_NAMES,
  ACCOUNT_UPDATE_STORED_STATE_LABEL,
  ADDRESS_LINE_2_NAME,
  AccountUpdateScreen,
  accountUpdateGridGutter,
  clampToDeclaredWidth,
  fieldDomId,
  groupCaptionDomId,
  headlineForFieldErrors,
  isAccountUpdateField,
} from '../screens/accountUpdate';
import type { AccountUpdateFieldName } from '../screens/accountUpdate';
import { FIELD_ERROR_TOKENS } from '../theme/tokens';
import { conflictProblem, expectMaxLength, pressPfKey, renderInAppShell } from './setup';

/**
 * Builds the mocked surface of the account transport module.
 *
 * Assumptions: this is a hoisted function DECLARATION rather than an inline factory held in
 * a `const`. Vitest lifts every `vi.mock` call above the imports, so a `const` factory
 * would sit in its temporal dead zone at registration time and the mock would fail to
 * register at all.
 *
 * Assumptions: all FOUR members the screen imports are stubbed, including
 * `isConflictFailure`. The screen classifies a rejected write through that predicate, and
 * the real one narrows on `instanceof ApiRequestError`; leaving it out would make the
 * member `undefined` and turn the concurrency path into a `TypeError`. Each case that
 * cares sets its own answer, which is also how the sibling suite
 * `ui/src/screens/accountScreens.test.tsx` drives it.
 * @returns {Record<string, unknown>} The transport surface, each member a fresh spy.
 */
function mockAccountTransportModule(): Record<string, unknown> {
  return {
    isConflictFailure: vi.fn(),
    readAccountView: vi.fn(),
    updateAccount: vi.fn(),
    validateAccountUpdate: vi.fn(),
  };
}

/*
 * Alternatives Considered: intercepting HTTP instead of mocking this module. Rejected on
 * two grounds. `msw` is absent from `ui/package.json`, so there is no request-level
 * interceptor to reach for; and these cases assert what the SCREEN does with an outcome, so
 * the shortest honest seam is the function the screen calls. Going through the client would
 * additionally exercise the interceptor chain and make a presentation regression
 * indistinguishable from a transport one.
 */
vi.mock('../api/accounts', mockAccountTransportModule);

/** Catalogued sentences of the program under test, aliased so no case re-reaches for the group. */
const MESSAGES = STATUS_MESSAGES.COACTUPC;

/**
 * An eleven-digit account key that passes the screen's own filter.
 *
 * WHY : Assumptions: eleven digits and not all zeroes, because `accountFilterError` in
 *       `ui/src/screens/accountUpdate/index.tsx` refuses both a short key and the all-zeroes
 *       key -- the latter transcribing `88 SEARCHED-ACCT-ZEROES` at `app/cbl/COACTUPC.cbl`
 *       L493-L494. The width is the one `ACCTSIDI PIC X(11)` declares at
 *       `app/cpy-bms/COACTUP.CPY` L60.
 */
const ACCOUNT_ID = '00000000011';

/*
 * WHY : Assumptions: seven is the count a browser accessibility audit reported and the count the
 *       mapset paints -- one caption over each of the four dates, the national identifier and both
 *       telephone numbers. Naming it means the case fails if a group is added or removed rather than
 *       silently checking six.
 */

/** Part groups on this screen, each one caption over two or three boxes. */
const PART_GROUP_COUNT = 7;

/*
 * WHY : ⚠️ Assumptions: the surface is asserted as the design system's own custom-property REFERENCE
 *       rather than as a resolved colour. An inline style holding a literal hue would satisfy a colour
 *       assertion while having opted the control out of the theme, which is the failure the token
 *       bridge exists to prevent -- so the reference is what proves the token was used.
 * WHY : Assumptions: the digit-free token name needs no hyphenation beyond the word boundaries, so the
 *       reference is written out here rather than derived, and the derivation helper this file's
 *       sibling suite carries is not duplicated for one constant.
 */

/** The reference antd emits for the surface an unavailable control is painted on. */
const UNAVAILABLE_SURFACE_REFERENCE = 'var(--ant-color-bg-container-disabled)';

/** The seven painted captions, as the label map declares them, punctuation included. */
const PAINTED_CAPTIONS: readonly string[] = [
  ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.openDate,
  ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.expirationDate,
  ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.reissueDate,
  ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.dateOfBirth,
  ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.ssn,
  ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.phone1,
  ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.phone2,
];

/*
 * WHY : Assumptions: the status is written as a named constant rather than as the number, matching
 *       how this file already names the conflict status at its own refusal sites.
 */

/** HTTP status a validation refusal carrying field errors arrives with. */
const BAD_REQUEST_STATUS = 400;

/**
 * The concurrency token a read hands back and a write must quote.
 *
 * WHY : Assumptions: any non-empty string serves. `updateAccount` in `ui/src/api/accounts.ts`
 *       rejects an empty revision outright, and the screen refuses to write when it holds
 *       none, so the value only has to be present -- it is the JPA `@Version` column's
 *       transport form, opaque to the browser.
 */
const REVISION = 'W/"1"';

/**
 * The marker a service publishes in place of a protected identifier.
 *
 * WHY : Assumptions: the literal is `[REDACTED]`, fixed by the guard at
 *       `ui/src/api/accounts.ts` L234, which refuses any answer whose identifier members are
 *       not exactly that. It is a WIRE value rather than a user-visible sentence, so spelling
 *       it here does not breach the catalogue-only discipline that governs message text.
 */
const REDACTED = '[REDACTED]';

/**
 * A national identifier in the clear, used only to prove it never reaches the DOM.
 *
 * WHY : Assumptions: this value is never given to the screen. It is the probe
 *       {@link theProtectedIdentifiersRenderOnlyAsMarkers} searches the rendered tree for, so
 *       that "the unmasked value is absent" is asserted against a concrete string rather than
 *       against the absence of a shape.
 */
const UNMASKED_NATIONAL_IDENTIFIER = '123456789';

/**
 * One row of the symbolic-map width inventory.
 *
 * Assumptions: the copybook field name and line are carried ALONGSIDE the width rather than
 * being left in a comment, so that a failure names the exact declaration a reader must open.
 */
interface MapFieldWidth {
  /** Field name as `app/cpy-bms/COACTUP.CPY` declares it. */
  readonly mapField: string;
  /** 1-based line of the `PIC` clause in that copybook. */
  readonly line: number;
  /** Character width the `PIC X(n)` clause declares. */
  readonly width: number;
}

/**
 * One row of the inventory for an input the screen owns as a form control.
 *
 * WHY : Assumptions: the tables below are ARRAYS keyed by a typed `field` member rather than
 *       objects keyed by field name. `ui/tsconfig.json` sets `noUncheckedIndexedAccess`, so
 *       walking an object's keys yields `string` and indexing the screen's own map with one
 *       requires a cast -- and a cast is exactly the construct that would let a renamed field
 *       pass this file silently. An array of typed rows needs no cast anywhere.
 */
interface FormFieldWidth extends MapFieldWidth {
  /** Form member the control edits. */
  readonly field: AccountUpdateFieldName;
}

/**
 * The declared width of every input the screen owns, transcribed from the symbolic map.
 *
 * WHY : Assumptions: `app/cpy-bms/COACTUP.CPY` is the SINGLE source of every width here, and
 *       this table exists so the screen's own `ACCOUNT_UPDATE_FIELD_WIDTHS` map is checked
 *       against the copybook rather than against itself. Asserting the screen's map with the
 *       screen's map would pass for any pair of matching wrong numbers.
 *
 * Alternatives Considered: 43 hand-written cases, one per field. Rejected because a table
 * driven from one transcription is both shorter and more faithful -- a reviewer compares one
 * block against one copybook, and a field added to the map with no row here fails
 * {@link theInventoryAccountsForEverySymbolicMapInput} rather than passing unnoticed.
 */
const FORM_FIELD_WIDTHS: readonly FormFieldWidth[] = [
  { field: 'accountId', mapField: 'ACCTSIDI', line: 60, width: 11 },
  { field: 'activeStatus', mapField: 'ACSTTUSI', line: 66, width: 1 },
  { field: 'openDateYear', mapField: 'OPNYEARI', line: 72, width: 4 },
  { field: 'openDateMonth', mapField: 'OPNMONI', line: 78, width: 2 },
  { field: 'openDateDay', mapField: 'OPNDAYI', line: 84, width: 2 },
  { field: 'creditLimit', mapField: 'ACRDLIMI', line: 90, width: 15 },
  { field: 'expirationDateYear', mapField: 'EXPYEARI', line: 96, width: 4 },
  { field: 'expirationDateMonth', mapField: 'EXPMONI', line: 102, width: 2 },
  { field: 'expirationDateDay', mapField: 'EXPDAYI', line: 108, width: 2 },
  { field: 'cashCreditLimit', mapField: 'ACSHLIMI', line: 114, width: 15 },
  { field: 'reissueDateYear', mapField: 'RISYEARI', line: 120, width: 4 },
  { field: 'reissueDateMonth', mapField: 'RISMONI', line: 126, width: 2 },
  { field: 'reissueDateDay', mapField: 'RISDAYI', line: 132, width: 2 },
  { field: 'currentBalance', mapField: 'ACURBALI', line: 138, width: 15 },
  { field: 'currentCycleCredit', mapField: 'ACRCYCRI', line: 144, width: 15 },
  { field: 'groupId', mapField: 'AADDGRPI', line: 150, width: 10 },
  { field: 'currentCycleDebit', mapField: 'ACRCYDBI', line: 156, width: 15 },
  { field: 'customerId', mapField: 'ACSTNUMI', line: 162, width: 9 },
  { field: 'ssnPart1', mapField: 'ACTSSN1I', line: 168, width: 3 },
  { field: 'ssnPart2', mapField: 'ACTSSN2I', line: 174, width: 2 },
  { field: 'ssnPart3', mapField: 'ACTSSN3I', line: 180, width: 4 },
  { field: 'dateOfBirthYear', mapField: 'DOBYEARI', line: 186, width: 4 },
  { field: 'dateOfBirthMonth', mapField: 'DOBMONI', line: 192, width: 2 },
  { field: 'dateOfBirthDay', mapField: 'DOBDAYI', line: 198, width: 2 },
  { field: 'ficoCreditScore', mapField: 'ACSTFCOI', line: 204, width: 3 },
  { field: 'firstName', mapField: 'ACSFNAMI', line: 210, width: 25 },
  { field: 'middleName', mapField: 'ACSMNAMI', line: 216, width: 25 },
  { field: 'lastName', mapField: 'ACSLNAMI', line: 222, width: 25 },
  { field: 'addressLine1', mapField: 'ACSADL1I', line: 228, width: 50 },
  { field: 'stateCode', mapField: 'ACSSTTEI', line: 234, width: 2 },
  { field: 'addressLine2', mapField: 'ACSADL2I', line: 240, width: 50 },
  { field: 'zipCode', mapField: 'ACSZIPCI', line: 246, width: 5 },
  { field: 'city', mapField: 'ACSCITYI', line: 252, width: 50 },
  { field: 'countryCode', mapField: 'ACSCTRYI', line: 258, width: 3 },
  { field: 'phone1AreaCode', mapField: 'ACSPH1AI', line: 264, width: 3 },
  { field: 'phone1Prefix', mapField: 'ACSPH1BI', line: 270, width: 3 },
  { field: 'phone1LineNumber', mapField: 'ACSPH1CI', line: 276, width: 4 },
  { field: 'governmentIssuedId', mapField: 'ACSGOVTI', line: 282, width: 20 },
  { field: 'phone2AreaCode', mapField: 'ACSPH2AI', line: 288, width: 3 },
  { field: 'phone2Prefix', mapField: 'ACSPH2BI', line: 294, width: 3 },
  { field: 'phone2LineNumber', mapField: 'ACSPH2CI', line: 300, width: 4 },
  { field: 'eftAccountId', mapField: 'ACSEFTCI', line: 306, width: 10 },
  { field: 'primaryCardHolderIndicator', mapField: 'ACSPFLGI', line: 312, width: 1 },
];

/**
 * The symbolic-map inputs this screen does NOT own as form controls.
 *
 * WHY : ⚠️ Assumptions: these eleven exist and are enumerated because the map declares 54
 *       inputs while the screen owns 43, and the difference is not a shortfall -- six are the
 *       header band the shared frame paints from delegated identity, two are the message
 *       lines at rows 22 and 23, and three are the function-key legend at row 24. Counting
 *       only the 43 would under-report the map; counting all 54 as form controls would demand
 *       eleven controls the reference never made editable.
 */
const DELEGATED_MAP_FIELDS: readonly MapFieldWidth[] = [
  { mapField: 'TRNNAMEI', line: 24, width: 4 },
  { mapField: 'TITLE01I', line: 30, width: 40 },
  { mapField: 'CURDATEI', line: 36, width: 8 },
  { mapField: 'PGMNAMEI', line: 42, width: 8 },
  { mapField: 'TITLE02I', line: 48, width: 40 },
  { mapField: 'CURTIMEI', line: 54, width: 8 },
  { mapField: 'INFOMSGI', line: 318, width: 45 },
  { mapField: 'ERRMSGI', line: 324, width: 78 },
  { mapField: 'FKEYSI', line: 330, width: 21 },
  { mapField: 'FKEY05I', line: 336, width: 7 },
  { mapField: 'FKEY12I', line: 342, width: 10 },
];

/**
 * Reports whether one delegated field is part of the function-key legend.
 *
 * WHY : Assumptions: the three legend fields are the ones whose name begins `FKEY`, which is the
 *       mapset's own naming and not a convention invented here -- `FKEYSI`, `FKEY05I` and
 *       `FKEY12I` at `app/cpy-bms/COACTUP.CPY` L330, L336 and L342.
 * @param {MapFieldWidth} row - A delegated inventory row.
 * @returns {boolean} `true` for a legend field.
 */
function isLegendField(row: MapFieldWidth): boolean {
  return row.mapField.startsWith('FKEY');
}

/**
 * Reads the declared width from an inventory row.
 * @param {MapFieldWidth} row - An inventory row.
 * @returns {number} Its declared character width.
 */
function widthOf(row: MapFieldWidth): number {
  return row.width;
}

/**
 * Count of `02 <name>I PIC X(n)` declarations in the symbolic map.
 *
 * WHY : Assumptions: 54, measured directly from `app/cpy-bms/COACTUP.CPY` rather than taken
 *       from a summary. It is asserted as the sum of the two tables above so that a field
 *       added to one without the other cannot leave the inventory quietly short.
 */
const SYMBOLIC_MAP_INPUT_COUNT = 54;

/**
 * The baseline line of every sentence the program declares, keyed by catalogue member.
 *
 * WHY : Assumptions: `app/cbl/COACTUPC.cbl` declares each of these as a `VALUE` on an `88`
 *       level, and `ui/src/messages/messages.ts` records the line on every entry. Asserting
 *       the line as well as the text is what makes the citation checkable: a sentence
 *       re-transcribed from the wrong condition would keep its spelling and lose its
 *       provenance, and nothing else in the tree would notice.
 */
/**
 * One catalogued sentence and the baseline line that declares it.
 */
interface SentenceLine {
  /** Catalogue member holding the sentence. */
  readonly key: keyof typeof MESSAGES;
  /** 1-based line of the `VALUE` literal in `app/cbl/COACTUPC.cbl`. */
  readonly line: number;
}

const SENTENCE_LINES: readonly SentenceLine[] = [
  { key: 'FOUND_ACCOUNT_DATA', line: 467 },
  { key: 'PROMPT_FOR_SEARCH_KEYS', line: 469 },
  { key: 'PROMPT_FOR_CHANGES', line: 471 },
  { key: 'PROMPT_FOR_CONFIRMATION', line: 473 },
  { key: 'CONFIRM_UPDATE_SUCCESS', line: 475 },
  { key: 'INFORM_FAILURE', line: 477 },
  { key: 'WS_EXIT_MESSAGE', line: 482 },
  { key: 'WS_PROMPT_FOR_ACCT', line: 484 },
  { key: 'WS_PROMPT_FOR_LASTNAME', line: 486 },
  { key: 'WS_NAME_MUST_BE_ALPHA', line: 488 },
  { key: 'NO_SEARCH_CRITERIA_RECEIVED', line: 490 },
  { key: 'NO_CHANGES_DETECTED', line: 492 },
  { key: 'SEARCHED_ACCT_ZEROES', line: 494 },
  { key: 'SEARCHED_ACCT_NOT_NUMERIC', line: 496 },
  { key: 'DID_NOT_FIND_ACCT_IN_CARDXREF__L498', line: 498 },
  { key: 'DID_NOT_FIND_ACCT_IN_ACCTDAT', line: 500 },
  { key: 'DID_NOT_FIND_CUST_IN_CUSTDAT', line: 502 },
  { key: 'ACCT_STATUS_MUST_BE_YES_NO', line: 504 },
  { key: 'CRED_LIMIT_IS_BLANK', line: 506 },
  { key: 'CRED_LIMIT_IS_NOT_VALID', line: 508 },
  { key: 'THIS_MONTH_NOT_VALID', line: 510 },
  { key: 'THIS_YEAR_NOT_VALID', line: 512 },
  { key: 'DID_NOT_FIND_ACCT_IN_CARDXREF__L514', line: 514 },
  { key: 'DID_NOT_FIND_ACCTCARD_COMBO', line: 516 },
  { key: 'COULD_NOT_LOCK_ACCT_FOR_UPDATE', line: 518 },
  { key: 'COULD_NOT_LOCK_CUST_FOR_UPDATE', line: 520 },
  { key: 'DATA_WAS_CHANGED_BEFORE_UPDATE', line: 522 },
  { key: 'LOCKED_BUT_UPDATE_FAILED', line: 524 },
  { key: 'XREF_READ_ERROR', line: 526 },
  { key: 'CODING_TO_BE_DONE', line: 528 },
];

/**
 * The seven field labels the program moves into `WS-EDIT-VARIABLE-NAME`, with their lines.
 *
 * WHY : Assumptions: these are PROGRAM literals rather than mapset labels -- the mapset paints
 *       `'Credit Limit        :'` while `app/cbl/COACTUPC.cbl` L1484 moves `'Credit Limit'`
 *       into the edit-variable name that composes a refusal. Both are user-visible and AAP
 *       rule T8 carries both across character-for-character, so both are asserted, from their
 *       own catalogues, and never conflated.
 */
/**
 * One catalogued field label and the baseline line that declares it.
 */
interface LabelLine {
  /** Catalogue member holding the label. */
  readonly key: keyof typeof ACCOUNT_UPDATE_FIELD_LABELS;
  /** 1-based line of the `MOVE` in `app/cbl/COACTUPC.cbl`. */
  readonly line: number;
}

const LABEL_LINES: readonly LabelLine[] = [
  { key: 'ACCOUNT_STATUS', line: 1472 },
  { key: 'OPEN_DATE', line: 1478 },
  { key: 'CREDIT_LIMIT', line: 1484 },
  { key: 'EXPIRY_DATE', line: 1490 },
  { key: 'CASH_CREDIT_LIMIT', line: 1496 },
  { key: 'REISSUE_DATE', line: 1503 },
  { key: 'CURRENT_BALANCE', line: 1509 },
];

/**
 * Builds the answer a successful read returns, with every amount as a string.
 *
 * WHY : ⚠️ Trade-offs: every monetary member below is a STRING and none is a number. A JSON
 *       number is parsed into an IEEE-754 double by every browser, which cannot represent
 *       `5000.00` and `250.00` as exact cents -- and the exactness is the contract:
 *       `ACCT-CURR-BAL PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L7 is zoned decimal, held as
 *       `NUMERIC(12,2)` in the database and as `BigDecimal` at scale 2 in the service. The
 *       cost of the string is that no arithmetic can be done in the browser, which is the
 *       point rather than the price.
 * @returns {AccountViewResponse} A populated account and customer at screen widths.
 */
function accountView(): AccountViewResponse {
  return {
    accountId: ACCOUNT_ID,
    account: {
      activeStatus: 'Y',
      openDate: '2020-01-01',
      creditLimit: '5000.00',
      expirationDate: '2026-01-01',
      cashCreditLimit: '1000.00',
      reissueDate: '2024-01-01',
      currentBalance: '250.00',
      currentCycleCredit: '0.00',
      groupId: 'ZEROBAL',
      currentCycleDebit: '0.00',
    },
    customer: customerDetail(),
    informationMessage: null,
    returnMessage: null,
  };
}

/**
 * Builds the customer half of an answer, with both protected identifiers masked.
 *
 * WHY : Assumptions: this is a function of its own rather than an object literal inside
 *       {@link accountView}, because a read publishes `CustomerDetail | null` -- the reference
 *       has a path where the account is found and the customer is not, reported at
 *       `app/cbl/COACTUPC.cbl` L502 -- while a committed write always echoes a customer. Reusing
 *       the nullable member for the write shape is a type error, and the honest fix is one
 *       builder with a non-null return that both shapes draw on.
 * @returns {CustomerDetail} A populated customer at screen widths.
 */
function customerDetail(): CustomerDetail {
  return {
    customerId: '000000011',
    ssnMasked: REDACTED,
    dateOfBirth: '1980-01-01',
    ficoCreditScore: '750',
    firstName: 'ADA',
    middleName: null,
    lastName: 'LOVELACE',
    addressLine1: '1 ANALYTICAL WAY',
    stateCode: 'NY',
    addressLine2: 'APT 4B',
    zipCode: '10001',
    city: 'NEW YORK',
    countryCode: 'USA',
    phoneNumber1: '(212)5550101',
    governmentIssuedIdMasked: REDACTED,
    phoneNumber2: null,
    eftAccountId: '00000000000',
    primaryCardHolderIndicator: 'Y',
  };
}

/**
 * Builds the answer a committed write returns.
 * @param {string | null} returnMessage - Sentence the service reports, or nothing.
 * @param {readonly FieldError[]} fieldErrors - Refusals the write itself raised.
 * @returns {AccountUpdateResponse} The stored state after the attempt.
 */
function accountUpdated(
  returnMessage: string | null,
  fieldErrors: readonly FieldError[] = [],
): AccountUpdateResponse {
  const view = accountView();
  return {
    accountId: view.accountId,
    informationMessage: null,
    returnMessage,
    fieldErrors,
    account: view.account,
    // WHY : Assumptions: a committed write always echoes a customer, so the member is the
    //       populated builder rather than the read's nullable one. The partial-read shape belongs
    //       to the read path, where the reference reports L502 instead.
    customer: customerDetail(),
  };
}

/**
 * Builds the failure a refused request rejects with.
 *
 * WHY : Assumptions: a real {@link ApiRequestError} is constructed rather than a plain object,
 *       because the screen narrows a rejection with `isApiRequestError`, which tests
 *       `instanceof`. A duck-typed stand-in would fall through to the generic arm and the case
 *       would assert the wrong branch while still going green.
 * @param {number} status - HTTP status the service answered with.
 * @param {string | null} message - Sentence the problem document carries.
 * @param {readonly FieldError[]} fieldErrors - Per-field refusals the document names.
 * @returns {ApiRequestError} The rejection a mocked operation can throw.
 */
function refusal(
  status: number,
  message: string | null,
  fieldErrors: readonly FieldError[] = [],
): ApiRequestError {
  const problem: ApiError =
    status === 409 && message !== null
      ? { ...conflictProblem(message), fieldErrors }
      : {
          code: 'CARDDEMO-0400',
          secondaryCode: '',
          message,
          severity: 'WARNING',
          subsystem: 'APPLICATION',
          status,
          correlationId: '00000000-0000-4000-8000-000000000000',
          path: '/api/v1/accounts/update',
          timestamp: '2022-07-18 22:10:31.000000',
          fieldErrors,
          abend: null,
        };

  return new ApiRequestError('PROBLEM', status, problem, `PROBLEM ${String(status)}`);
}

/**
 * Collapses whitespace runs the way Testing Library's default normaliser does.
 *
 * WHY : Assumptions: a catalogued sentence carries its declared pad -- `WS_EXIT_MESSAGE` is
 *       `'PF03 pressed.Exiting'` followed by fourteen spaces to fill `PIC X(75)` -- while the
 *       DOM text a query returns has been normalised. Comparing the two without collapsing
 *       fails on the pad rather than on the wording, so the pad is asserted separately, on the
 *       catalogue entry itself, by {@link theFragileSpellingsSurviveCharacterForCharacter}.
 * @param {string} value - Catalogued literal, pad included.
 * @returns {string} The same text with whitespace runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/**
 * Returns the control the screen renders for one form field.
 *
 * WHY : Alternatives Considered: querying by accessible label instead. Rejected because the
 *       split groups share their part names by design -- `ACCOUNT_UPDATE_PART_NAMES.year`
 *       names the year of all four dates -- so a label query is ambiguous for 18 of the 43
 *       fields, whereas the identifier `fieldDomId` mints is unique by construction.
 * @param {AccountUpdateFieldName} field - Form member whose control is wanted.
 * @returns {HTMLElement} The rendered control.
 * @throws {Error} When the screen rendered no control for that field, so the failure names
 *   the missing field rather than surfacing as a null dereference further down the call chain.
 */
function control(field: AccountUpdateFieldName): HTMLElement {
  const element = document.getElementById(fieldDomId(field));
  if (element === null) {
    throw new Error(`the screen rendered no control for '${field}'`);
  }
  return element;
}

/**
 * Returns the element one field's control is SIZED on.
 *
 * WHY : ⚠️ Assumptions: that is not always the input itself. The one marker-bearing field carries an
 *       antd `suffix` in every state -- deliberately, so the affix never appears or disappears under a
 *       focused cursor -- and an affix changes the component's rendered root from a bare `input` to a
 *       wrapping element, which is where the design system then puts `style`. The wrapper is also the
 *       correct box to size, because it is the one with the visible border.
 * WHY : Assumptions: the input is preferred and the wrapper is the fallback, rather than the wrapper
 *       being looked for first, so the 42 fields without an affix are asserted on the element they
 *       actually carry and a future affix on any of them is picked up without editing this helper.
 * @param {AccountUpdateFieldName} field - Form member whose sized box is wanted.
 * @returns {HTMLElement} The input, or the affix wrapper that holds its size.
 */
function sizedBox(field: AccountUpdateFieldName): HTMLElement {
  const input = control(field);

  if ((input.getAttribute('style') ?? '') !== '') {
    return input;
  }

  return input.closest<HTMLElement>('.ant-input-affix-wrapper') ?? input;
}

/**
 * Renders the screen inside the real shared frame.
 *
 * WHY : ⚠️ Assumptions: the shell is MANDATORY, not decoration. The screen publishes its
 *       identity, its row-23 message line and its row-24 legend through `useShellSlot`, so
 *       the frame paints all three; rendering the screen alone would leave every message and
 *       every legend assertion below with nothing to find. `renderInAppShell` composes the
 *       pathless layout route the application's own route table declares, and supplies the
 *       design system's provider with the BMS token bridge -- which is why it is used in
 *       preference to a hand-assembled provider stack.
 * @returns {Promise<UserEvent>} The operator bound to the rendered tree.
 */
async function renderScreen(): Promise<UserEvent> {
  const rendered = await renderInAppShell(<AccountUpdateScreen />, {
    initialEntries: [ACCOUNT_UPDATE_PATH],
    routePath: ACCOUNT_UPDATE_PATH,
  });
  return rendered.user;
}

/**
 * Reads the row-22 informational line the screen paints itself.
 * @returns {Promise<HTMLElement>} The informational band, once it is present.
 */
async function informationBand(): Promise<HTMLElement> {
  return screen.findByTestId(INFORMATION_BAND_TEST_ID);
}

/**
 * Reads the row-23 message line the shared frame paints from the screen's delegated statement.
 * @returns {Promise<HTMLElement>} The message band, once it is present.
 */
async function messageBand(): Promise<HTMLElement> {
  return screen.findByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Returns the legend region the shared key bar paints.
 * @returns {HTMLElement} The legend landmark.
 */
function legendRegion(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Reads one legend control's painted label, normalised for comparison.
 *
 * WHY : Assumptions: the reader is shared by the three places that identify a legend control --
 *       listing the legend, clicking one and reading one's emphasis -- so all three agree on what
 *       a control's label IS. Three private spellings of "collapse its text" is how one of them
 *       comes to disagree with the others over a control that renders an icon beside its text.
 * @param {HTMLButtonElement} button - A control painted in the legend.
 * @returns {string} Its label with whitespace runs collapsed.
 */
function labelOfLegendControl(button: HTMLButtonElement): string {
  return collapse(button.textContent ?? '');
}

/**
 * Reads the legend's controls as collapsed labels, in painted order.
 * @returns {readonly string[]} One label per painted control.
 */
function legendLabels(): readonly string[] {
  return Array.from(legendRegion().querySelectorAll('button')).map(labelOfLegendControl);
}

/**
 * Reads the legend control painting one label.
 *
 * WHY : Assumptions: the control is located by its painted label rather than by position, because the
 *       legend's contents are state-dependent -- PF5 is admitted only once changes validate -- so an
 *       index would name a different key on a different turn.
 * @param {string} label - The painted label, exactly as the legend renders it.
 * @returns {HTMLButtonElement} The control painting that label.
 * @throws {Error} When the legend paints no such label, which is a failure and not an empty result.
 */
function legendControlFor(label: string): HTMLButtonElement {
  const found = Array.from(legendRegion().querySelectorAll('button')).find(
    /**
     * Tests one legend control for the wanted label.
     * @param {HTMLButtonElement} candidate - The control under test.
     * @returns {boolean} `true` when it paints the wanted label.
     */
    (candidate: HTMLButtonElement): boolean => labelOfLegendControl(candidate) === label,
  );

  if (found === undefined) {
    throw new Error(`the legend paints no control labelled ${label}`);
  }

  return found;
}

/**
 * Builds the expectation that the legend paints one label.
 *
 * WHY : Assumptions: a closure factory rather than an inline callback, because the legend is
 *       state-dependent -- PF5 appears only once changes validate -- so every case that reaches
 *       for a key has to wait for it, and a named expectation makes the failure read as "the
 *       legend never painted F5=Save" instead of as an anonymous timeout.
 * @param {string} label - Painted label to wait for.
 * @returns {() => void} The expectation, ready for a wait utility.
 */
function expectLegendToPaint(label: string): () => void {
  return assertPainted;

  /**
   * Asserts the legend currently paints the wanted label.
   * @returns {void} Nothing; the assertion runs for its effect.
   */
  function assertPainted(): void {
    expect(legendLabels()).toContain(collapse(label));
  }
}

/**
 * Builds a predicate matching a legend control by its collapsed label.
 * @param {string} wanted - Collapsed label to match.
 * @returns {(button: HTMLButtonElement) => boolean} The predicate.
 */
function matchesLegendLabel(wanted: string): (button: HTMLButtonElement) => boolean {
  return matches;

  /**
   * Reports whether one control carries the wanted label.
   * @param {HTMLButtonElement} button - Control to test.
   * @returns {boolean} `true` when its collapsed label matches.
   */
  function matches(button: HTMLButtonElement): boolean {
    return labelOfLegendControl(button) === wanted;
  }
}

/**
 * Activates a legend control by its painted label.
 *
 * WHY : Assumptions: the control is looked up inside the legend landmark rather than by role
 *       across the whole tree, because `'F5=Save'` is painted in up to three places at once --
 *       the legend, the confirmation's trigger and the confirmation's accept control -- and a
 *       tree-wide query would resolve to whichever came first in document order.
 * @param {UserEvent} user - The operator driving the tree.
 * @param {string} label - Painted label of the wanted control.
 * @returns {Promise<void>} Resolves once the control has been clicked.
 * @throws {Error} When the legend paints no control with that label.
 */
async function clickLegendKey(user: UserEvent, label: string): Promise<void> {
  const wanted = collapse(label);
  await waitFor(expectLegendToPaint(label));
  const target = Array.from(legendRegion().querySelectorAll('button')).find(
    matchesLegendLabel(wanted),
  );
  if (target === undefined) {
    throw new Error(`the legend paints no control labelled '${wanted}'`);
  }
  await user.click(target);
}

/**
 * Arranges a read that succeeds and hands back a revision.
 *
 * WHY : Assumptions: the revision must be non-null for the screen to treat the record as
 *       loaded -- it refuses to write without one, and a null revision drives the
 *       partial-read arm instead. It is the transport form of the JPA `@Version` column.
 * @returns {void} Nothing; the transport spy is armed as a side effect.
 */
function arrangeSuccessfulRead(): void {
  vi.mocked(isConflictFailure).mockReturnValue(false);
  vi.mocked(readAccountView).mockResolvedValue({ account: accountView(), revision: REVISION });
}

/**
 * Fills one control in a single gesture.
 *
 * WHY : ⚠️ Trade-offs: entry is raised as ONE change event rather than typed character by
 *       character, and the reason is cost rather than taste. Every keystroke re-renders a form of
 *       43 controls, and this file fills a field in nearly every case, so per-character entry put
 *       this one file at roughly 900s of a shared four-worker pool -- which starved the pool and
 *       pushed borderline cases in four OTHER files past their own wait budgets. Measured on this
 *       file's concurrency group, typing cost 98s, a paste 98s and one change event 72s. What is
 *       given up is per-keystroke behaviour, and no assertion here depends on it: the screen's
 *       character-level rules are the digit filter and the marker overwrite, both of which are
 *       reachable from `digitsOnly` and `recordEntry` without a tree. The KEYBOARD contract that
 *       does matter -- the attention identifiers -- is still driven through real key events by
 *       `pressPfKey`, and is never simulated.
 *
 * WHY : Assumptions: the event is act-wrapped by the library's own `fireEvent`, so the resulting
 *       state update has flushed by the time this returns and no caller needs to await it.
 * @param {AccountUpdateFieldName} field - Field to fill.
 * @param {string} value - Value to place in it.
 * @returns {void} Nothing; the control holds the value once this returns.
 */
function fill(field: AccountUpdateFieldName, value: string): void {
  fireEvent.change(control(field), { target: { value } });
}

/**
 * Loads an account onto the screen and waits until its details are shown.
 *
 * WHY : Assumptions: the key is typed and ENTER is raised, because that is the only route to a
 *       loaded record -- the screen issues no read on mount, mirroring `COACTUPC`, which
 *       paints its prompt and waits for an attention identifier. The wait is on the
 *       informational line reaching the prompt-for-changes sentence, which the screen sets
 *       only after the read resolves and it enters the show-details action.
 * @param {UserEvent} user - The operator driving the tree.
 * @returns {Promise<void>} Resolves once the loaded record is on screen.
 */
async function loadAccount(user: UserEvent): Promise<void> {
  fill('accountId', ACCOUNT_ID);
  await pressPfKey(user, 'ENTER');
  await waitFor(expectInformationLineToRead(MESSAGES.PROMPT_FOR_CHANGES.text));
}

/**
 * Builds the expectation that the informational line reads one sentence.
 *
 * WHY : Assumptions: the informational line is the screen's own row-22 band and it is never
 *       empty -- the reference derives it from the change action on every send -- so waiting on
 *       its TEXT is how a case observes that an action has been entered. Waiting on a spy call
 *       instead would resolve before the state that spy's answer produces had rendered.
 * @param {string} sentence - Catalogued sentence the line must read.
 * @returns {() => Promise<void>} The expectation, ready for a wait utility.
 */
function expectInformationLineToRead(sentence: string): () => Promise<void> {
  return assertReads;

  /**
   * Asserts the informational line currently reads the sentence.
   * @returns {Promise<void>} Resolves once the band has been read and compared.
   */
  async function assertReads(): Promise<void> {
    expect(await informationBand()).toHaveTextContent(collapse(sentence));
  }
}

/**
 * Edits one unprotected text field so the screen holds a genuine change.
 *
 * WHY : Assumptions: one change event replaces a clear followed by an entry, because a change event
 *       carries the field's WHOLE new value rather than an edit to it -- which is what the control's
 *       own handler receives either way.
 *
 * WHY : Assumptions: `firstName` is chosen because it is editable in the show-details action --
 *       `NEVER_EDITABLE_FIELDS` protects only the account key, the customer key and the
 *       country code -- and because it carries no edit mask, so what is typed is what the
 *       change detector compares. An amount field would additionally exercise the money mask
 *       and make a validation assertion depend on formatting.
 * @param {string} value - Replacement value to place in the field.
 * @returns {void} Nothing; the field holds the new value once this returns.
 */
function editFirstName(value: string): void {
  fill('firstName', value);
}

/**
 * Loads an account, changes a field and drives the validation turn to its verdict.
 *
 * WHY : Assumptions: the verdict is delivered through `validateAccountUpdate` and NOT through
 *       `updateAccount`, because ENTER validates and only PF5 commits. That separation is the
 *       behaviour {@link validationAsksForTheSaveKeyRatherThanCommitting} asserts, and every
 *       case that needs a validated screen depends on it holding.
 * @param {UserEvent} user - The operator driving the tree.
 * @returns {Promise<void>} Resolves once the validation verdict has been applied.
 */
async function loadAndValidate(user: UserEvent): Promise<void> {
  vi.mocked(validateAccountUpdate).mockResolvedValue({
    fieldErrors: [],
    message: null,
    inputError: false,
    noChangesFound: false,
  });

  await loadAccount(user);
  editFirstName('GRACE');
  await pressPfKey(user, 'ENTER');
  await waitFor(expectInformationLineToRead(MESSAGES.PROMPT_FOR_CONFIRMATION.text));
}

/**
 * Every width the screen applies is the width the symbolic map declares.
 *
 * WHY : Assumptions: this compares the screen's own `ACCOUNT_UPDATE_FIELD_WIDTHS` against the
 *       transcription in {@link FORM_FIELD_WIDTHS}, which is read from
 *       `app/cpy-bms/COACTUP.CPY`. The two are independent, so a width edited on one side
 *       fails here -- whereas asserting the screen's map against itself would accept any pair
 *       of matching wrong numbers.
 * @returns {void} Nothing; the assertions run for their effect.
 */
function everyDeclaredWidthMatchesTheSymbolicMap(): void {
  for (const row of FORM_FIELD_WIDTHS) {
    expect(
      ACCOUNT_UPDATE_FIELD_WIDTHS[row.field],
      `${row.mapField} is PIC X(${String(row.width)}) at app/cpy-bms/COACTUP.CPY L${String(row.line)}`,
    ).toBe(row.width);
  }
}

/**
 * The inventory accounts for all fifty-four inputs the symbolic map declares.
 *
 * WHY : ⚠️ Assumptions: 43 form controls plus 11 delegated fields is 54, the measured count of
 *       `02 <name>I PIC X(n)` declarations in `app/cpy-bms/COACTUP.CPY`. The arithmetic is
 *       asserted rather than trusted because the two tables are maintained separately, and a
 *       field moved from one to the other -- or added to neither -- is exactly the drift that
 *       leaves an input unasserted while every individual row still passes.
 * @returns {void} Nothing; the assertions run for their effect.
 */
function theInventoryAccountsForEverySymbolicMapInput(): void {
  expect(FORM_FIELD_WIDTHS).toHaveLength(Object.keys(ACCOUNT_UPDATE_FIELD_WIDTHS).length);
  expect(FORM_FIELD_WIDTHS.length + DELEGATED_MAP_FIELDS.length).toBe(SYMBOLIC_MAP_INPUT_COUNT);

  // WHY : Assumptions: the three legend fields are declared exactly as wide as the text they
  //       carry -- `FKEYSI PIC X(21)` for `'ENTER=Process F3=Exit'` at `app/bms/COACTUP.bms`
  //       L497, `FKEY05I PIC X(7)` for `'F5=Save'` at L502 and `FKEY12I PIC X(10)` for
  //       `'F12=Cancel'` at L507. That correspondence is what identifies these three as the
  //       legend rather than as three unexplained inputs, so it is asserted rather than
  //       asserted about.
  const legendWidths = DELEGATED_MAP_FIELDS.filter(isLegendField).map(widthOf);
  expect(legendWidths).toStrictEqual([
    `${ACCOUNT_UPDATE_KEY_LABELS.ENTER} ${ACCOUNT_UPDATE_KEY_LABELS.PFK03}`.length,
    ACCOUNT_UPDATE_KEY_LABELS.PFK05.length,
    ACCOUNT_UPDATE_KEY_LABELS.PFK12.length,
  ]);
}

/**
 * The account key is eleven characters wide, as both mapsets declare it differently.
 *
 * WHY : ⚠️ Assumptions: the two mapsets spell the SAME field two ways -- `ACCTSIDI PIC X(11)`
 *       at `app/cpy-bms/COACTUP.CPY` L60 against `ACCTSIDI PIC 99999999999` at
 *       `app/cpy-bms/COACTVW.CPY` L60. Eleven characters against eleven digits is the same
 *       width and a different type, and the update screen follows the character form: the key
 *       transports as a digits-only STRING. That is the same reasoning the reference applies
 *       to its own before-image, where every numeric is held as `PIC X(n)` with a numeric
 *       `REDEFINES` over it, so a value is characters on the wire and a number only in
 *       arithmetic.
 * @returns {void} Nothing; the assertions run for their effect.
 */
function theAccountKeyKeepsElevenCharactersAcrossBothMapsets(): void {
  expect(ACCOUNT_UPDATE_FIELD_WIDTHS.accountId).toBe(11);
  expect(ACCOUNT_ID).toHaveLength(ACCOUNT_UPDATE_FIELD_WIDTHS.accountId);
}

/**
 * Every rendered control bounds its entry to its declared width.
 *
 * WHY : Assumptions: the 3270 field length was a hard bound -- a terminal accepted no
 *       character beyond the field -- so `maxLength` is the browser's equivalent and is
 *       asserted on all 43 controls rather than on a sample. The controls render before any
 *       read, protected but present, which is why no account is loaded first.
 * @returns {Promise<void>} Resolves once every control has been checked.
 */
async function everyControlBoundsEntryToItsDeclaredWidth(): Promise<void> {
  arrangeSuccessfulRead();
  await renderScreen();

  for (const row of FORM_FIELD_WIDTHS) {
    expectMaxLength(control(row.field), row.width);
  }
}

/**
 * The initial cursor lands on the account key and on nothing else.
 *
 * WHY : ⚠️ Assumptions: `app/bms/COACTUP.bms` carries exactly ONE `IC` attribute, measured
 *       across all 128 `DFHMDF` definitions, and it is on `ACCTSID` at L84. Focus is asserted
 *       through `document.activeElement` rather than through an attribute because React
 *       applies `autoFocus` by focusing the element on mount and reflects no attribute, so the
 *       landing cursor is the only observable form the single `IC` takes.
 * @returns {Promise<void>} Resolves once focus has been checked.
 */
async function theInitialCursorLandsOnTheAccountKey(): Promise<void> {
  arrangeSuccessfulRead();
  await renderScreen();

  expect(document.activeElement).toBe(control('accountId'));
}

/**
 * Each split date renders three independent parts at their own widths.
 *
 * WHY : ⚠️ Assumptions: the split is a CONTRACT, not an accident of layout. The map declares
 *       year, month and day as three fields with three widths -- 4, 2 and 2 -- for each of the
 *       four dates, and the reference's own before-image redefines each eight-character date
 *       into exactly those three parts at `app/cbl/COACTUPC.cbl` L684-L695. One combined
 *       control would render the same information and lose three per-part bounds, so the parts
 *       are asserted as separate controls carrying separate widths.
 * @returns {Promise<void>} Resolves once all four date groups have been checked.
 */
async function eachSplitDateRendersThreeIndependentParts(): Promise<void> {
  arrangeSuccessfulRead();
  await renderScreen();

  // WHY : ⚠️ Assumptions: each group is paired with the NAME TOKEN the reference moves into
  //       `WS-EDIT-VARIABLE-NAME` for it -- `'Open Date'` at `app/cbl/COACTUPC.cbl` L1478,
  //       `'Expiry Date'` at L1490, `'Reissue Date'` at L1503 and `'Date of Birth'` at L1533 --
  //       because the screen composes each part's accessible name from that token and the part
  //       name. Pairing them here is what ties the accessible name back to the baseline literal
  //       rather than to a label invented for the browser.
  const groups: readonly (readonly [
    AccountUpdateFieldName,
    AccountUpdateFieldName,
    AccountUpdateFieldName,
    string,
  ])[] = [
    ['openDateYear', 'openDateMonth', 'openDateDay', ACCOUNT_UPDATE_FIELD_LABELS.OPEN_DATE],
    [
      'expirationDateYear',
      'expirationDateMonth',
      'expirationDateDay',
      ACCOUNT_UPDATE_FIELD_LABELS.EXPIRY_DATE,
    ],
    [
      'reissueDateYear',
      'reissueDateMonth',
      'reissueDateDay',
      ACCOUNT_UPDATE_FIELD_LABELS.REISSUE_DATE,
    ],
    [
      'dateOfBirthYear',
      'dateOfBirthMonth',
      'dateOfBirthDay',
      ACCOUNT_UPDATE_FIELD_LABELS.DATE_OF_BIRTH,
    ],
  ];

  for (const [year, month, day, token] of groups) {
    // WHY : Assumptions: the three controls are bound to named constants rather than indexed out
    //       of an array, because `noUncheckedIndexedAccess` types an index read as possibly
    //       undefined and the only ways past that are a cast or a guard -- and a cast here would
    //       be the one construct able to hide a missing control behind a passing assertion.
    const yearPart = control(year);
    const monthPart = control(month);
    const dayPart = control(day);
    expect(new Set([yearPart, monthPart, dayPart]).size).toBe(3);
    expectMaxLength(yearPart, 4);
    expectMaxLength(monthPart, 2);
    expectMaxLength(dayPart, 2);

    // WHY : Assumptions: each part is named individually, because the mapset paints ONE label for
    //       the whole group -- `'Opened :'` sits beside three controls -- and a control with no
    //       name of its own is unusable without sight. The name is asserted to resolve to the
    //       exact control, which is what makes it unambiguous across four dates that share the
    //       three part words.
    expect(screen.getByLabelText(`${token} ${ACCOUNT_UPDATE_PART_NAMES.year}`)).toBe(yearPart);
    expect(screen.getByLabelText(`${token} ${ACCOUNT_UPDATE_PART_NAMES.month}`)).toBe(monthPart);
    expect(screen.getByLabelText(`${token} ${ACCOUNT_UPDATE_PART_NAMES.day}`)).toBe(dayPart);
  }
}

/**
 * The national identifier renders as three parts of three, two and four characters.
 *
 * WHY : ⚠️ Assumptions: 3/2/4 is what the map declares -- `ACTSSN1I PIC X(3)` L168,
 *       `ACTSSN2I PIC X(2)` L174 and `ACTSSN3I PIC X(4)` L180 of
 *       `app/cpy-bms/COACTUP.CPY` -- and the mapset paints a single `'SSN:'` label for all
 *       three. Collapsing them into one nine-character control would accept a value the
 *       terminal could not, because the terminal enforced each part separately.
 * @returns {Promise<void>} Resolves once the three parts have been checked.
 */
async function theNationalIdentifierRendersThreeIndependentParts(): Promise<void> {
  arrangeSuccessfulRead();
  await renderScreen();

  expectMaxLength(control('ssnPart1'), 3);
  expectMaxLength(control('ssnPart2'), 2);
  expectMaxLength(control('ssnPart3'), 4);
  expect(new Set([control('ssnPart1'), control('ssnPart2'), control('ssnPart3')]).size).toBe(3);

  // WHY : ⚠️ Assumptions: these three parts are named by the reference's OWN literals rather than
  //       by a composed phrase -- `'SSN: First 3 chars'` at `app/cbl/COACTUPC.cbl` L2439,
  //       `'SSN 4th & 5th chars'` at L2469 and `'SSN Last 4 chars'` at L2481 -- because the
  //       program names each part separately where it names each date only by group. Asserting
  //       them from the catalogue keeps the accessible names identical to the baseline's wording,
  //       including the abbreviation and the ampersand.
  expect(screen.getByLabelText(ACCOUNT_UPDATE_FIELD_LABELS.SSN_FIRST_3_CHARS)).toBe(
    control('ssnPart1'),
  );
  expect(screen.getByLabelText(ACCOUNT_UPDATE_FIELD_LABELS.SSN_4TH_AND_5TH_CHARS)).toBe(
    control('ssnPart2'),
  );
  expect(screen.getByLabelText(ACCOUNT_UPDATE_FIELD_LABELS.SSN_LAST_4_CHARS)).toBe(
    control('ssnPart3'),
  );
}

/**
 * Each telephone number renders as three parts of three, three and four characters.
 *
 * WHY : ⚠️ Assumptions: 3/3/4 twice over, from `ACSPH1AI`/`ACSPH1BI`/`ACSPH1CI` at
 *       `app/cpy-bms/COACTUP.CPY` L264-L276 and `ACSPH2AI`/`ACSPH2BI`/`ACSPH2CI` at L288-L300.
 *       The area code is a part in its own right because the reference validates it on its own,
 *       against the area-code allow-list in `app/cpy/CSLKPCDY.cpy`.
 * @returns {Promise<void>} Resolves once both telephone groups have been checked.
 */
async function eachTelephoneRendersThreeIndependentParts(): Promise<void> {
  arrangeSuccessfulRead();
  await renderScreen();

  const groups: readonly (readonly [
    AccountUpdateFieldName,
    AccountUpdateFieldName,
    AccountUpdateFieldName,
    string,
  ])[] = [
    [
      'phone1AreaCode',
      'phone1Prefix',
      'phone1LineNumber',
      ACCOUNT_UPDATE_FIELD_LABELS.PHONE_NUMBER_1,
    ],
    [
      'phone2AreaCode',
      'phone2Prefix',
      'phone2LineNumber',
      ACCOUNT_UPDATE_FIELD_LABELS.PHONE_NUMBER_2,
    ],
  ];

  for (const [area, prefix, line, token] of groups) {
    const areaPart = control(area);
    const prefixPart = control(prefix);
    const linePart = control(line);
    expectMaxLength(areaPart, 3);
    expectMaxLength(prefixPart, 3);
    expectMaxLength(linePart, 4);
    expect(new Set([areaPart, prefixPart, linePart]).size).toBe(3);

    // WHY : Assumptions: the two numbers are told apart by their own name tokens --
    //       `'Phone Number 1'` at `app/cbl/COACTUPC.cbl` L1632 and `'Phone Number 2'` at L1640 --
    //       so an area code refused on the second number cannot be reported against the first.
    expect(screen.getByLabelText(`${token} ${ACCOUNT_UPDATE_PART_NAMES.areaCode}`)).toBe(areaPart);
    expect(screen.getByLabelText(`${token} ${ACCOUNT_UPDATE_PART_NAMES.prefix}`)).toBe(prefixPart);
    expect(screen.getByLabelText(`${token} ${ACCOUNT_UPDATE_PART_NAMES.lineNumber}`)).toBe(
      linePart,
    );
  }
}

/**
 * Every control the screen paints is a design-system control.
 *
 * WHY : Assumptions: the design-system rule is "library components over raw HTML", so a bare
 *       `input`, `button` or `table` in the screen body would be a compliance failure even
 *       where it rendered identically. The check is by class rather than by role because the
 *       class is what proves the element came from the library: `ant-input` and `ant-btn` are
 *       applied by the components themselves and by nothing else in this tree.
 * @returns {Promise<void>} Resolves once the body has been swept.
 */
async function everyControlIsADesignSystemControl(): Promise<void> {
  arrangeSuccessfulRead();
  await renderScreen();

  const body = screen.getByRole('main');
  for (const input of Array.from(body.querySelectorAll('input'))) {
    expect(input.className).toContain('ant-input');
  }
  for (const button of Array.from(body.querySelectorAll('button'))) {
    expect(button.className).toContain('ant-btn');
  }
  // WHY : Assumptions: the reference paints no tabular data on this screen -- all 128 fields are
  //       positioned singly -- so a table here would be an invention rather than a migration.
  expect(body.querySelectorAll('table')).toHaveLength(0);
}

/**
 * The account-status control admits one character and its label carries the Y/N domain.
 *
 * WHY : ⚠️ Assumptions: the domain is two values and the width is one character.
 *       `ACSTTUSI PIC X(1)` at `app/cpy-bms/COACTUP.CPY` L66 fixes the width, the mapset paints
 *       `'Active Y/N: '` beside it, and `app/cbl/COACTUPC.cbl` L504 refuses anything else with
 *       `'Account Active Status must be Y or N'`. The same reasoning covers `ACSPFLGI PIC X(1)`
 *       at L312, the primary-card-holder flag, so both single-character flags are asserted here.
 * @returns {Promise<void>} Resolves once both flag controls have been checked.
 */
async function theStatusFlagsAdmitOneCharacterFromATwoValueDomain(): Promise<void> {
  arrangeSuccessfulRead();
  await renderScreen();

  expectMaxLength(control('activeStatus'), 1);
  expectMaxLength(control('primaryCardHolderIndicator'), 1);

  // WHY : Assumptions: both painted labels are read from the screen's own transcription of the
  //       mapset rather than retyped here. A label retyped in a test and paraphrased in the
  //       screen would still agree if BOTH were wrong, which is precisely the failure the
  //       catalogue-only discipline exists to prevent.
  const body = screen.getByRole('main');
  expect(body).toHaveTextContent(collapse(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.activeStatus));
  expect(body).toHaveTextContent(
    collapse(ACCOUNT_UPDATE_FIELD_LABELS_PAINTED.primaryCardHolderIndicator),
  );
  expect(MESSAGES.ACCT_STATUS_MUST_BE_YES_NO.line).toBe(504);
}

/**
 * Every catalogued sentence carries the baseline line that declares it.
 *
 * WHY : ⚠️ Assumptions: the catalogue is the single source of every sentence this screen shows,
 *       and each entry records the line of its `VALUE` clause in `app/cbl/COACTUPC.cbl`. Both
 *       halves are asserted because they fail independently: a sentence can keep its wording
 *       and lose its provenance if it is re-transcribed from the wrong `88` level, and nothing
 *       else in the tree compares the two. Thirty entries are checked, which is every sentence
 *       the program declares between L467 and L528.
 * @returns {void} Nothing; the assertions run for their effect.
 */
function everyCatalogedSentenceCarriesItsBaselineLine(): void {
  expect(SENTENCE_LINES).toHaveLength(Object.keys(MESSAGES).length);

  for (const row of SENTENCE_LINES) {
    const entry = MESSAGES[row.key];
    expect(entry.line, `${row.key} is declared at app/cbl/COACTUPC.cbl L${String(row.line)}`).toBe(
      row.line,
    );
    // WHY : Assumptions: a sentence is never empty and never pre-trimmed. The declared pad is
    //       part of the literal -- see the exit sentence below -- so trimming at transcription
    //       time would silently change the data this file exists to protect.
    expect(entry.text.length).toBeGreaterThan(0);
  }
}

/**
 * The four fragile spellings survive character for character.
 *
 * WHY : ⚠️ Assumptions: these are the four spellings a paraphrase destroys without changing the
 *       meaning, so each is asserted as a character-level property of the catalogued literal
 *       rather than by comparison with a retyped copy -- a retyped copy would carry the same
 *       mistake as the transcription it is checking. The exit sentence at
 *       `app/cbl/COACTUPC.cbl` L482 is padded to 34 characters with 14 trailing spaces; the
 *       confirmation prompt at L473 has NO space after its full stop; the placeholder at L528
 *       has FOUR dots; and the two prompts at L471 and L492 end in a full stop.
 * @returns {void} Nothing; the assertions run for their effect.
 */
function theFragileSpellingsSurviveCharacterForCharacter(): void {
  const exitSentence = MESSAGES.WS_EXIT_MESSAGE.text;
  expect(exitSentence).toHaveLength(34);
  expect(exitSentence.length - exitSentence.trimEnd().length).toBe(14);

  const confirmationPrompt = MESSAGES.PROMPT_FOR_CONFIRMATION.text;
  expect(confirmationPrompt).not.toContain('. ');
  expect(confirmationPrompt).toHaveLength(34);

  const placeholder = MESSAGES.CODING_TO_BE_DONE.text;
  expect(placeholder).toContain('....');
  expect(placeholder).not.toContain('.....');

  expect(MESSAGES.PROMPT_FOR_CHANGES.text.endsWith('.')).toBe(true);
  expect(MESSAGES.NO_CHANGES_DETECTED.text.endsWith('.')).toBe(true);
}

/**
 * The account-key refusal is one sentence declared on two separate conditions.
 *
 * WHY : ⚠️ Assumptions: the duplication is DELIBERATE in the reference, not a transcription
 *       slip. `app/cbl/COACTUPC.cbl` declares the identical literal twice, on
 *       `88 SEARCHED-ACCT-ZEROES` at L494 and on `88 SEARCHED-ACCT-NOT-NUMERIC` at L496, so an
 *       all-zeroes key and a non-numeric key are distinguishable conditions that report
 *       identically. Collapsing them to one catalogue member would lose a condition the
 *       reference can still be seen to have.
 * @returns {void} Nothing; the assertions run for their effect.
 */
function theAccountKeyRefusalIsTwoConditionsWithOneSentence(): void {
  expect(MESSAGES.SEARCHED_ACCT_ZEROES.text).toBe(MESSAGES.SEARCHED_ACCT_NOT_NUMERIC.text);
  expect(MESSAGES.SEARCHED_ACCT_ZEROES.line).toBe(494);
  expect(MESSAGES.SEARCHED_ACCT_NOT_NUMERIC.line).toBe(496);

  // WHY : Assumptions: the two cross-reference refusals are the same shape of pair and are
  //       checked with them -- L498 names the account/card cross-reference file and L514 names
  //       the cards database, two different sentences on one repeated condition name, which is
  //       why the catalogue disambiguates them by line rather than by wording.
  expect(MESSAGES.DID_NOT_FIND_ACCT_IN_CARDXREF__L498.line).toBe(498);
  expect(MESSAGES.DID_NOT_FIND_ACCT_IN_CARDXREF__L514.line).toBe(514);
  expect(MESSAGES.DID_NOT_FIND_ACCT_IN_CARDXREF__L498.text).not.toBe(
    MESSAGES.DID_NOT_FIND_ACCT_IN_CARDXREF__L514.text,
  );
}

/**
 * Every field label the program composes refusals from carries its own program line.
 *
 * WHY : ⚠️ Assumptions: these seven are PROGRAM literals moved into `WS-EDIT-VARIABLE-NAME`,
 *       distinct from the labels the mapset paints -- the program says `'Credit Limit'` at
 *       L1484 while the mapset paints `'Credit Limit        :'`. Both are user-visible, AAP
 *       rule T8 carries both across character-for-character, and conflating them would lose
 *       one of the two. Only provenance is asserted here because these labels reach the screen
 *       inside a composed refusal rather than as painted text of their own.
 * @returns {void} Nothing; the assertions run for their effect.
 */
function everyComposedLabelCarriesItsProgramLine(): void {
  for (const row of LABEL_LINES) {
    expect(
      ACCOUNT_UPDATE_FIELD_LABEL_SOURCES[row.key],
      `${row.key} is moved at app/cbl/COACTUPC.cbl L${String(row.line)}`,
    ).toContain(row.line);
    expect(ACCOUNT_UPDATE_FIELD_LABELS[row.key].length).toBeGreaterThan(0);
  }
}

/**
 * Returns the open confirmation bubble, or nothing when none is open.
 *
 * WHY : Assumptions: the bubble is located by the design system's own class rather than by its
 *       title text, because the title IS the confirmation prompt at `app/cbl/COACTUPC.cbl`
 *       L473 and that same sentence is already painted in the informational band -- so a query
 *       by text matches two elements and proves nothing about the bubble.
 * @returns {HTMLElement | null} The bubble's root, or `null` when it is not open.
 */
function confirmationPopup(): HTMLElement | null {
  return document.body.querySelector<HTMLElement>('.ant-popconfirm');
}

/**
 * Asserts a confirmation bubble is currently open.
 * @returns {void} Nothing; the assertion runs for its effect.
 */
function expectConfirmationToBeOpen(): void {
  expect(confirmationPopup()).not.toBeNull();
}

/**
 * Raises the save key and accepts the confirmation it opens.
 *
 * WHY : Assumptions: the write is reached ONLY through the confirmation, so a case that wants a
 *       committed or refused write has to pass through the bubble rather than calling the
 *       handler. Driving it this way is what keeps every outcome case honest about the ordering
 *       the reference imposes: validate, confirm, then write.
 * @param {UserEvent} user - The operator driving the tree.
 * @returns {Promise<void>} Resolves once the accept control has been clicked.
 * @throws {Error} When the save key opened no confirmation, so the failure names the missing
 *   bubble instead of surfacing as a null dereference.
 */
async function acceptTheConfirmation(user: UserEvent): Promise<void> {
  await pressPfKey(user, 'PFK05');
  await waitFor(expectConfirmationToBeOpen);

  const bubble = confirmationPopup();
  if (bubble === null) {
    throw new Error('the save key opened no confirmation, so there is nothing to accept');
  }
  await user.click(within(bubble).getByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.PFK05 }));
}

/**
 * Waits until the screen has left the route, which is how an exit key is observed.
 *
 * WHY : Alternatives Considered: asserting the destination screen instead. Rejected because the
 *       destination is the main menu and mounting it here would pull a second screen and its
 *       transport into a file about this one -- and the route table's own suite already asserts
 *       where the exit leads. What belongs here is that the key LEAVES, which the disappearance
 *       of the account key control establishes.
 * @returns {Promise<void>} Resolves once the screen has unmounted.
 */
async function waitForScreenToClose(): Promise<void> {
  await waitFor(expectTheScreenToBeGone);
}

/**
 * Asserts the screen is no longer mounted.
 * @returns {void} Nothing; the assertion runs for its effect.
 */
function expectTheScreenToBeGone(): void {
  expect(document.getElementById(fieldDomId('accountId'))).toBeNull();
}

/**
 * The opening legend offers processing and exit, and neither save nor cancel.
 *
 * WHY : ⚠️ Assumptions: the reference gates PF5 and PF12 on screen state rather than painting
 *       them always -- `app/cbl/COACTUPC.cbl` L906-L911 admits PF5 only with changes awaiting
 *       confirmation and PF12 only once details have been fetched. So an opening legend of two
 *       keys is the contract, and a four-key opening legend would offer a save with nothing to
 *       save.
 * @returns {Promise<void>} Resolves once the opening legend has been read.
 */
async function theOpeningLegendOffersProcessAndExitOnly(): Promise<void> {
  arrangeSuccessfulRead();
  await renderScreen();

  const labels = legendLabels();
  expect(labels).toContain(collapse(ACCOUNT_UPDATE_KEY_LABELS.ENTER));
  expect(labels).toContain(collapse(ACCOUNT_UPDATE_KEY_LABELS.PFK03));
  expect(labels).not.toContain(collapse(ACCOUNT_UPDATE_KEY_LABELS.PFK05));
  expect(labels).not.toContain(collapse(ACCOUNT_UPDATE_KEY_LABELS.PFK12));
}

/**
 * Validation asks for the save key rather than committing.
 *
 * WHY : ⚠️⚠️ Assumptions: this is the two-step ordering the reference's own sentences encode.
 *       ENTER validates and reports `'Changes validated.Press F5 to save'` at
 *       `app/cbl/COACTUPC.cbl` L473; only PF5 writes. A single-step save would be a
 *       behavioural change, and it would be invisible in any assertion that only checked the
 *       end state -- so the write spy is asserted UNCALLED at the point the prompt appears.
 * @returns {Promise<void>} Resolves once the validated, uncommitted state has been checked.
 */
async function validationAsksForTheSaveKeyRatherThanCommitting(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);

  expect(await informationBand()).toHaveTextContent(
    collapse(MESSAGES.PROMPT_FOR_CONFIRMATION.text),
  );
  expect(vi.mocked(validateAccountUpdate)).toHaveBeenCalled();
  expect(vi.mocked(updateAccount)).not.toHaveBeenCalled();
}

/**
 * A second processing key after validation still commits nothing.
 *
 * WHY : Assumptions: the reference's re-entry into the confirmation state re-paints the prompt
 *       and does not write -- `processEnter` reaches an arm that only restates the information
 *       line. This is asserted separately from the case above because the two fail differently:
 *       one would write on the first key, the other on the second, and an operator pressing
 *       ENTER twice out of habit is the likelier of the two.
 * WHY : ⚠️ Assumptions: the VALIDATION spy's call count is asserted as well, and it is asserted to be
 *       unchanged. A responsive review reported this turn as a defect on the ground that it issued no
 *       request; it issues none because the reference issues none --
 *       `WHEN ACUP-CHANGES-OK-NOT-CONFIRMED ... CONTINUE` at `app/cbl/COACTUPC.cbl` L2618 to L2620 is
 *       an arm of its own, distinct from the one above it that additionally requires PF05, so Enter on
 *       this turn re-sends the same protected screen. Pinning the count here is what keeps a later
 *       change from "fixing" the report by adding a round trip the reference does not make; what the
 *       review was actually seeing is that the restated prompt sat below the fold, which is a band
 *       placement matter and is addressed as one.
 * @returns {Promise<void>} Resolves once the second key has been checked.
 */
async function aSecondProcessingKeyAfterValidationCommitsNothing(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);

  await pressPfKey(user, 'ENTER');

  expect(await informationBand()).toHaveTextContent(
    collapse(MESSAGES.PROMPT_FOR_CONFIRMATION.text),
  );
  expect(vi.mocked(updateAccount)).not.toHaveBeenCalled();
  expect(vi.mocked(validateAccountUpdate)).toHaveBeenCalledTimes(1);
}

/**
 * The save and cancel keys appear once changes have validated.
 *
 * WHY : Assumptions: the legend is the mapset's three descriptors and no more --
 *       `'ENTER=Process F3=Exit'`, `'F5=Save'` and `'F12=Cancel'` at `app/bms/COACTUP.bms`
 *       L497, L502 and L507. Once changes are validated all four keys are live, so the full
 *       legend is asserted here and the opening legend's absence of two of them is asserted
 *       above; between them the state-dependence is pinned from both sides.
 * @returns {Promise<void>} Resolves once the validated legend has been read.
 */
async function theSaveAndCancelKeysAppearOnceChangesValidate(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);

  await waitFor(expectLegendToPaint(ACCOUNT_UPDATE_KEY_LABELS.PFK05));
  const labels = legendLabels();
  expect(labels).toContain(collapse(ACCOUNT_UPDATE_KEY_LABELS.ENTER));
  expect(labels).toContain(collapse(ACCOUNT_UPDATE_KEY_LABELS.PFK03));
  expect(labels).toContain(collapse(ACCOUNT_UPDATE_KEY_LABELS.PFK12));
}

/**
 * The one key that writes takes the primary emphasis; the three that do not, do not.
 *
 * WHY : ⚠️ Refactoring Rationale: this case asserted ENTER emphasised as well, on the strength of the
 *       shared `PRIMARY_ACTION_AIDS` fallback, and now asserts it plain. The screen declares each
 *       key's RISK, and risk follows what the label says the action does: `ENTER=Process` fetches a
 *       record or asks the service's non-writing check, while the rewrite is reached from the F5 arm
 *       alone -- `app/cbl/COACTUPC.cbl` L905 to L916 admits PF05 only in the validated action. Two
 *       emphasised controls, one of which writes, is emphasis that marks position rather than
 *       consequence.
 * WHY : ⚠️ Assumptions: the fallback's own membership is asserted BESIDE the plain ENTER, and that
 *       pairing is the point. It proves the declared risk OVERRIDES the fallback rather than merely
 *       agreeing with it -- without it, a screen that had simply stopped binding ENTER would satisfy
 *       every other assertion here.
 * WHY : Assumptions: the save key is also asserted free of the danger treatment. The shared primitive
 *       reserves that for a DESTRUCTIVE action, and this one replaces a record rather than removing
 *       one, so an alarming save control would overstate what the key does.
 * @returns {Promise<void>} Resolves once the emphasis of all four controls has been checked.
 */
async function onlyTheWritingKeyCarriesThePrimaryEmphasis(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);
  await waitFor(expectLegendToPaint(ACCOUNT_UPDATE_KEY_LABELS.PFK05));

  expect(PRIMARY_ACTION_AIDS).toContain('ENTER');
  expect(emphasisOf(ACCOUNT_UPDATE_KEY_LABELS.ENTER)).not.toContain('ant-btn-primary');

  expect(emphasisOf(ACCOUNT_UPDATE_KEY_LABELS.PFK05)).toContain('ant-btn-primary');
  expect(emphasisOf(ACCOUNT_UPDATE_KEY_LABELS.PFK05)).not.toContain('danger');
  expect(emphasisOf(ACCOUNT_UPDATE_KEY_LABELS.PFK03)).not.toContain('ant-btn-primary');
  expect(emphasisOf(ACCOUNT_UPDATE_KEY_LABELS.PFK12)).not.toContain('ant-btn-primary');
}

/**
 * Reads the class list of one legend control, which carries its emphasis.
 * @param {string} label - Painted label of the wanted control.
 * @returns {string} The control's class list.
 * @throws {Error} When the legend paints no control with that label.
 */
function emphasisOf(label: string): string {
  const target = Array.from(legendRegion().querySelectorAll('button')).find(
    matchesLegendLabel(collapse(label)),
  );
  if (target === undefined) {
    throw new Error(`the legend paints no control labelled '${collapse(label)}'`);
  }
  return target.className;
}

/**
 * The exit key closes the screen.
 *
 * WHY : ⚠️ Alternatives Considered: asserting the legend control alone, which is what a
 *       mouse-driven suite would do. Rejected because the 3270 original was KEYBOARD-ONLY: the
 *       legend is a painted reminder of a key, so a legend that worked while its key did not
 *       would break the original workflow entirely while every visible control still responded.
 *       The key is therefore driven here and the painted control in the case below -- two cases
 *       rather than two renders in one, because each render mounts its own shared frame and a
 *       second frame would make every landmark query ambiguous.
 * @returns {Promise<void>} Resolves once the key has closed the screen.
 */
async function theExitKeyClosesTheScreen(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();

  await pressPfKey(user, 'PFK03');

  await waitForScreenToClose();
}

/**
 * The legend control the exit key is painted as closes the screen too.
 *
 * WHY : Assumptions: the control dispatches through the same `invoke` the key does -- the screen
 *       delegates one binding table to the frame -- so this case and the one above prove the two
 *       routes cannot diverge. Asserting only one of them would leave the other free to break.
 * @returns {Promise<void>} Resolves once the painted control has closed the screen.
 */
async function theExitLegendControlClosesTheScreen(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();

  await clickLegendKey(user, ACCOUNT_UPDATE_KEY_LABELS.PFK03);

  await waitForScreenToClose();
}

/**
 * The save key raises a confirmation and writes nothing until it is accepted.
 *
 * WHY : Assumptions: the confirmation replaces the 3270 re-key-to-confirm convention, so it
 *       stands between the save key and the write rather than beside it. AAP section 0.4.1.4
 *       maps this screen to `Form` + `Row`/`Col` + `Input` + `Popconfirm`, and the bubble's
 *       accept and reject controls are the same two legend descriptors the mapset paints, which
 *       is why they are asserted from the same constants.
 * @returns {Promise<void>} Resolves once the raised, unwritten state has been checked.
 */
async function theSaveKeyRaisesAConfirmationBeforeWriting(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);

  await pressPfKey(user, 'PFK05');

  await waitFor(expectConfirmationToBeOpen);
  const bubble = confirmationPopup();
  expect(bubble).not.toBeNull();
  if (bubble !== null) {
    expect(bubble).toHaveTextContent(collapse(MESSAGES.PROMPT_FOR_CONFIRMATION.text));
    expect(
      within(bubble).getByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.PFK05 }),
    ).toBeInTheDocument();
    expect(
      within(bubble).getByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.PFK12 }),
    ).toBeInTheDocument();
  }
  expect(vi.mocked(updateAccount)).not.toHaveBeenCalled();
}

/**
 * Dismissing the confirmation writes nothing.
 *
 * WHY : Assumptions: rejecting the bubble must be inert, because the reference's confirmation
 *       step exists precisely so that a change can be abandoned after it has validated. A
 *       dismissal that wrote anyway would be the worst available failure -- the operator has
 *       just said no -- and it is invisible unless the write spy is asserted uncalled after the
 *       rejection rather than merely before it.
 * @returns {Promise<void>} Resolves once the dismissal has been checked.
 * @throws {Error} When the save key opened no confirmation, so a case that cannot reach its own
 *   premise reports that rather than reporting a passing write-spy assertion it never earned.
 */
async function dismissingTheConfirmationWritesNothing(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);
  await pressPfKey(user, 'PFK05');
  await waitFor(expectConfirmationToBeOpen);

  const bubble = confirmationPopup();
  if (bubble === null) {
    throw new Error('the confirmation did not open, so there is nothing to dismiss');
  }
  await user.click(within(bubble).getByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.PFK12 }));

  expect(vi.mocked(updateAccount)).not.toHaveBeenCalled();
}

/*
 * ⚠️⚠️ Refactoring Rationale: the cases below replace a BEFORE-IMAGE comparison with a version
 * column, and nothing is lost in the exchange. The reference snapshots the whole pre-edit record
 * into `ACUP-OLD-DETAILS` at `app/cbl/COACTUPC.cbl` L669 -- holding each numeric twice, as
 * `PIC X(n)` with a numeric `REDEFINES` over it, so `ACUP-OLD-CURR-BAL` is a twelve-character
 * field at L675-L677 and a `S9(10)V99` at the same address -- carries the outcome in
 * `WS-DATACHANGED-FLAG` at L168, and on a refused rewrite sets a locked-but-failed state and
 * issues `EXEC CICS SYNCPOINT ROLLBACK` at L4100 before leaving through the write exit. The
 * reason that machinery exists at all is that the screen is PSEUDO-CONVERSATIONAL: the CICS task
 * ends at every turn, so the read-for-update lock was NEVER held across the operator's think
 * time. The before-image is how the program detects, after the fact, that someone else committed
 * during the gap. A JPA `@Version` column detects exactly the same thing by exactly the same
 * logic -- compare what was read against what is stored, refuse when they differ -- so the
 * replacement preserves the semantic and discards only the hand-rolled snapshot. Trade-offs: the
 * comparison moves server-side and the browser can no longer say WHICH field changed underneath
 * it; the reference could not either, because it reports one sentence for the whole record.
 *
 * Assumptions: every value in that snapshot travels as a digits-only STRING rather than as a
 * number, and the reference's own declaration is the reason. An `X`-over-`9` `REDEFINES` pair
 * treats a value as characters on the wire and as a number only inside arithmetic, which is the
 * same discipline the migration applies end to end: `NUMERIC(12,2)` in the database,
 * `BigDecimal` at scale 2 in the service, a JSON string on the wire.
 */

/**
 * A concurrent change is reported in the reference's own sentence.
 *
 * WHY : ⚠️⚠️ Assumptions: HTTP 409 is the migration's form of the before-image refusal, and the
 *       sentence is `'Record changed by some one else. Please review'` at
 *       `app/cbl/COACTUPC.cbl` L522 -- the `VALUE` on `88 DATA-WAS-CHANGED-BEFORE-UPDATE`. The
 *       screen recognises the status through the `isConflictFailure` predicate its transport
 *       module re-exports, so the discrimination is a contract between two modules rather than a
 *       status comparison spelled out on the screen.
 * @returns {Promise<void>} Resolves once the refusal has been reported.
 */
async function aConcurrentChangeIsReportedInTheReferenceSentence(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);

  vi.mocked(isConflictFailure).mockReturnValue(true);
  vi.mocked(updateAccount).mockRejectedValue(
    refusal(409, MESSAGES.DATA_WAS_CHANGED_BEFORE_UPDATE.text),
  );
  await acceptTheConfirmation(user);

  expect(await messageBand()).toHaveTextContent(
    collapse(MESSAGES.DATA_WAS_CHANGED_BEFORE_UPDATE.text),
  );
}

/**
 * A concurrent change is distinguished from a write that merely failed.
 *
 * WHY : ⚠️⚠️ Assumptions: the two outcomes carry DIFFERENT sentences and lead to different
 *       states, so conflating them would tell an operator to review a record when the write had
 *       simply failed, or to retry when someone else's change is waiting to be read. A generic
 *       refusal reports `'Update of record failed'` at `app/cbl/COACTUPC.cbl` L524 and moves to
 *       the failure action, whose information line is `'Changes unsuccessful. Please try again'`
 *       at L477; the concurrency refusal reports L522 instead. Both bands are asserted because
 *       the sentences land on different rows.
 * @returns {Promise<void>} Resolves once the generic failure has been reported.
 */
async function aFailedWriteIsDistinguishedFromAConcurrentChange(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);

  vi.mocked(isConflictFailure).mockReturnValue(false);
  vi.mocked(updateAccount).mockRejectedValue(refusal(500, null));
  await acceptTheConfirmation(user);

  expect(await messageBand()).toHaveTextContent(collapse(MESSAGES.LOCKED_BUT_UPDATE_FAILED.text));
  expect(await informationBand()).toHaveTextContent(collapse(MESSAGES.INFORM_FAILURE.text));
  expect(await messageBand()).not.toHaveTextContent(
    collapse(MESSAGES.DATA_WAS_CHANGED_BEFORE_UPDATE.text),
  );
}

/**
 * A turn that changed nothing is refused without troubling the service.
 *
 * WHY : ⚠️ Assumptions: this is the `WS-DATACHANGED-FLAG` analogue at `app/cbl/COACTUPC.cbl`
 *       L168, and the sentence is `'No change detected with respect to values fetched.'` at
 *       L492. The screen compares the form against the baseline it read and reports locally, so
 *       the validation spy is asserted UNCALLED -- a round trip that answered "nothing changed"
 *       would produce the same sentence and a different, chattier program.
 * @returns {Promise<void>} Resolves once the local refusal has been checked.
 */
async function aTurnThatChangedNothingIsRefusedLocally(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAccount(user);

  await pressPfKey(user, 'ENTER');

  expect(await messageBand()).toHaveTextContent(collapse(MESSAGES.NO_CHANGES_DETECTED.text));
  expect(vi.mocked(validateAccountUpdate)).not.toHaveBeenCalled();
  expect(vi.mocked(updateAccount)).not.toHaveBeenCalled();
}

/**
 * Builds a case asserting that one lock refusal reaches the message line verbatim.
 *
 * WHY : Assumptions: the reference declares TWO lock refusals, one per record it takes for
 *       update -- the account at `app/cbl/COACTUPC.cbl` L518 and the customer at L520 -- because
 *       the update spans two files and either can be held. One parameterised case per sentence
 *       keeps both assertions independent, where a single case checking both would pass while
 *       reporting the account sentence for a customer lock.
 * @param {string} sentence - The catalogued refusal the service answers with.
 * @returns {() => Promise<void>} The case body, ready to register.
 */
function aLockRefusalReachesTheMessageLine(sentence: string): () => Promise<void> {
  return assertReported;

  /**
   * Drives a save that the service refuses with the given lock sentence.
   * @returns {Promise<void>} Resolves once the sentence has been asserted.
   */
  async function assertReported(): Promise<void> {
    arrangeSuccessfulRead();
    const user = await renderScreen();
    await loadAndValidate(user);

    // WHY : Assumptions: the predicate answers true and the problem document carries the lock
    //       sentence, which together prove the screen prefers the SERVICE's wording over its own
    //       fallback. A refusal that fell back to the default would report a concurrent change
    //       for a held lock -- two different conditions the reference reports differently.
    vi.mocked(isConflictFailure).mockReturnValue(true);
    vi.mocked(updateAccount).mockRejectedValue(refusal(409, sentence));
    await acceptTheConfirmation(user);

    expect(await messageBand()).toHaveTextContent(collapse(sentence));
  }
}

/**
 * A committed write reports success on the information line.
 *
 * WHY : ⚠️ Assumptions: success is `'Changes committed to database'` at
 *       `app/cbl/COACTUPC.cbl` L475, which the reference paints after its `EXEC CICS SYNCPOINT`
 *       at L953 -- so the sentence means the unit of work committed and not merely that the
 *       request returned. The write is asserted to have carried the revision the read handed
 *       back, because that value IS the concurrency check: a write that dropped it would commit
 *       unconditionally and the sentence would then be a lie.
 * @returns {Promise<void>} Resolves once the commitment has been checked.
 */
async function aCommittedWriteReportsSuccess(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);

  vi.mocked(updateAccount).mockResolvedValue({
    account: accountUpdated(null),
    revision: 'W/"2"',
  });
  await acceptTheConfirmation(user);

  expect(await informationBand()).toHaveTextContent(collapse(MESSAGES.CONFIRM_UPDATE_SUCCESS.text));
  // WHY : ⚠️ Assumptions: the write is asserted to carry BOTH the account key and the revision the
  //       read handed back. The revision IS the concurrency check -- a write that dropped it would
  //       commit unconditionally and this sentence would then be a lie -- and the key travelling in
  //       the request body is what makes the call self-describing rather than dependent on ambient
  //       state. Both are asserted here, on a flow this case already drives, rather than in a case
  //       of their own that would repeat the whole validate-confirm-commit chain.
  expect(vi.mocked(updateAccount)).toHaveBeenCalledWith(
    expect.objectContaining({ accountId: ACCOUNT_ID }),
    REVISION,
  );
}

/**
 * Returns the form item wrapping one field's control.
 *
 * WHY : Assumptions: the error state is carried by the form ITEM rather than by the control, so
 *       the item is what has to be read. The library applies `ant-form-item-has-error` when
 *       `validateStatus` is `error`, which makes the class the observable form of the prop.
 * @param {AccountUpdateFieldName} field - Form member whose item is wanted.
 * @returns {HTMLElement} The item element wrapping that control.
 * @throws {Error} When the control is not inside a form item, which would mean the screen had
 *   stopped using the library's form primitives.
 */
function formItem(field: AccountUpdateFieldName): HTMLElement {
  const item = control(field).closest<HTMLElement>('.ant-form-item');
  if (item === null) {
    throw new Error(`the control for '${field}' is not inside a design-system form item`);
  }
  return item;
}

/**
 * Builds a distinct, deliberately synthetic refusal sentence for one field.
 *
 * WHY : Assumptions: the text is synthetic ON PURPOSE and is not drawn from the catalogue. What
 *       this case asserts is the ROUTING of a refusal to the control the service named, which
 *       needs each sentence to be distinguishable from the others; a catalogued sentence reused
 *       across nine fields would let a screen-wide smear pass. Verbatim wording is asserted where
 *       it belongs, against the catalogue, in {@link everyCatalogedSentenceCarriesItsBaselineLine}.
 * @param {AccountUpdateFieldName} field - Field the probe is addressed to.
 * @returns {string} A sentence unique to that field.
 */
function refusalProbeFor(field: AccountUpdateFieldName): string {
  return `refusal probe for ${field}`;
}

/**
 * Builds the expectation that one field's form item carries the error state.
 * @param {AccountUpdateFieldName} field - Field whose item must be marked.
 * @returns {() => void} The expectation, ready for a wait utility.
 */
function expectFieldToCarryTheErrorState(field: AccountUpdateFieldName): () => void {
  return assertMarked;

  /**
   * Asserts the field's item currently carries the error state.
   * @returns {void} Nothing; the assertion runs for its effect.
   */
  function assertMarked(): void {
    expect(formItem(field).className).toContain('ant-form-item-has-error');
  }
}

/**
 * Fields chosen to cover one member of every group in the symbolic map.
 *
 * WHY : ⚠️ Assumptions: nine fields across nine groups rather than all 43, because what is being
 *       asserted is the MAPPING from a named refusal to a named control, and that mapping is
 *       written once for every field. One field per group -- flag, amount, split date part,
 *       name, split identifier part, address, split telephone part, protected identifier and
 *       trailing flag -- exercises every shape of control the renderer produces. The blanket
 *       claim the 43 would support is instead covered by the width sweep, which does visit all
 *       of them.
 */
const REPRESENTATIVE_FIELDS: readonly AccountUpdateFieldName[] = [
  'activeStatus',
  'creditLimit',
  'expirationDateMonth',
  'lastName',
  'ssnPart1',
  'city',
  'phone1AreaCode',
  'governmentIssuedId',
  'primaryCardHolderIndicator',
];

/**
 * Builds one field's synthetic refusal, as a problem document would carry it.
 * @param {AccountUpdateFieldName} field - Field the refusal names.
 * @returns {FieldError} The refusal, in the shared wire shape.
 */
function refusalProbeAgainst(field: AccountUpdateFieldName): FieldError {
  return { field, state: 'NOT_OK', message: refusalProbeFor(field) };
}

/**
 * Each refused field carries its own error state and its own sentence.
 *
 * WHY : ⚠️ Assumptions: this is the migration of the templated highlight copybook
 *       `app/cpy/CSSETATY.cpy`, whose L17-L27 move `DFHRED` into ONE field's colour attribute
 *       when that field's own flag is not-OK. The refusal is therefore per-field, and the
 *       sentence travels with the field rather than being pooled into the message line.
 *
 * WHY : ⚠️ Refactoring Rationale: the reference gates that highlight on `CDEMO-PGM-REENTER`
 *       (`app/cpy/COCOM01Y.cpy` L29-L31), which existed only because a pseudo-conversational
 *       task could not otherwise tell a first entry from a re-display. AAP section 0.7.1
 *       removes that discriminator entirely -- a stateless handler has no turn to remember -- so
 *       the highlight is driven purely by the response body and there is no re-entry flag for
 *       this case to set or assert.
 * @returns {Promise<void>} Resolves once every representative field has been checked.
 */
async function eachRefusedFieldCarriesItsOwnErrorState(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  vi.mocked(validateAccountUpdate).mockResolvedValue({
    fieldErrors: REPRESENTATIVE_FIELDS.map(refusalProbeAgainst),
    message: null,
    inputError: true,
    noChangesFound: false,
  });

  await loadAccount(user);
  editFirstName('GRACE');
  await pressPfKey(user, 'ENTER');

  await waitFor(expectFieldToCarryTheErrorState('creditLimit'));
  // WHY : Assumptions: the loop walks the TYPED constant rather than reading the field name back
  //       off the refusals it just built. `FieldError.field` is a `string` on the wire, as it must
  //       be -- every context's problem document shares that shape -- so reading it back would
  //       need a cast to index this screen's own field union, and the cast could then outlive a
  //       renamed field.
  for (const field of REPRESENTATIVE_FIELDS) {
    const item = formItem(field);
    expect(item.className, `${field} must carry the error state`).toContain(
      'ant-form-item-has-error',
    );
    expect(item, `${field} must carry its own sentence`).toHaveTextContent(refusalProbeFor(field));
  }
}

/**
 * A field the service did not name carries no error state.
 *
 * WHY : ⚠️ Assumptions: this is what proves the mapping is PER-FIELD rather than screen-wide. A
 *       renderer that marked every control whenever any refusal arrived would satisfy every
 *       assertion in the case above and still be wrong -- the terminal reddened one field at a
 *       time, and an operator reading a screen where everything is refused cannot tell what to
 *       correct.
 * @returns {Promise<void>} Resolves once the unnamed field has been checked.
 */
async function aFieldTheServiceDidNotNameCarriesNoErrorState(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  vi.mocked(validateAccountUpdate).mockResolvedValue({
    fieldErrors: [
      { field: 'creditLimit', state: 'NOT_OK', message: MESSAGES.CRED_LIMIT_IS_NOT_VALID.text },
    ],
    message: null,
    inputError: true,
    noChangesFound: false,
  });

  await loadAccount(user);
  editFirstName('GRACE');
  await pressPfKey(user, 'ENTER');

  await waitFor(expectFieldToCarryTheErrorState('creditLimit'));
  expect(formItem('city').className).not.toContain('ant-form-item-has-error');
  expect(formItem('lastName').className).not.toContain('ant-form-item-has-error');
}

/**
 * A blank entry is marked with the asterisk the reference writes into the field.
 *
 * WHY : ⚠️ Assumptions: the marker is an asterisk and it goes INSIDE the control, because
 *       `app/cpy/CSSETATY.cpy` L24 moves `'*'` into the field's own output subfield when the
 *       field is blank -- a distinct action from the colour change two lines above it, and one
 *       that only fires for blankness rather than for any refusal. The sentence that accompanies
 *       it here is `'No input received'` at `app/cbl/COACTUPC.cbl` L490, which the screen's own
 *       filter raises before any request, so this case needs no transport answer at all.
 * @returns {Promise<void>} Resolves once the marker and its sentence have been checked.
 */
async function aBlankEntryIsMarkedWithTheAsterisk(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();

  await pressPfKey(user, 'ENTER');

  await waitFor(expectTheBlankMarkerOnTheAccountKey);
  expect(await messageBand()).toHaveTextContent(
    collapse(MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text),
  );
  expect(vi.mocked(readAccountView)).not.toHaveBeenCalled();
}

/**
 * Asserts the account-key control currently displays the blank marker.
 * @returns {void} Nothing; the assertion runs for its effect.
 */
function expectTheBlankMarkerOnTheAccountKey(): void {
  expect(control('accountId')).toHaveValue(FIELD_ERROR_TOKENS.blankMarker);
}

/**
 * A name the service refuses is reported against that name's own control.
 *
 * WHY : ⚠️ Assumptions: the alphabet is enforced by the SERVICE, not by the browser. The
 *       reference tests a name against `LIT-UPPER` at `app/cbl/COACTUPC.cbl` L589 and
 *       `LIT-LOWER` at L591 -- the 26 upper-case and 26 lower-case letters, spaces admitted --
 *       and reports `'Name can only contain alphabets and spaces'` at L488. Re-implementing that
 *       alphabet in the browser would put a second copy of a business rule in the tree, so the
 *       screen's obligation is narrower and is what this case asserts: surface the refusal
 *       against the field the service named.
 * @returns {Promise<void>} Resolves once the refusal has been reported.
 */
async function aRefusedNameIsReportedAgainstItsOwnControl(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  vi.mocked(validateAccountUpdate).mockResolvedValue({
    fieldErrors: [
      { field: 'lastName', state: 'NOT_OK', message: MESSAGES.WS_NAME_MUST_BE_ALPHA.text },
    ],
    message: MESSAGES.WS_NAME_MUST_BE_ALPHA.text,
    inputError: true,
    noChangesFound: false,
  });

  await loadAccount(user);
  fill('lastName', 'LOVELACE1');
  await pressPfKey(user, 'ENTER');

  await waitFor(expectFieldToCarryTheErrorState('lastName'));
  expect(formItem('lastName')).toHaveTextContent(collapse(MESSAGES.WS_NAME_MUST_BE_ALPHA.text));
  expect(MESSAGES.WS_NAME_MUST_BE_ALPHA.line).toBe(488);
}

/**
 * The protected identifiers reach the screen only as markers.
 *
 * WHY : ⚠️ Assumptions: the national identifier and the government-issued identifier are stored
 *       encrypted and returned MASKED, so the value the screen receives is the published marker
 *       and the three entry parts stay empty until an operator retypes them. The unmasked probe
 *       is searched for across the whole document rather than in the controls, because a leak
 *       through a title, a hidden field or a caption would be just as much a leak.
 * @returns {Promise<void>} Resolves once the masked state has been checked.
 */
async function theProtectedIdentifiersRenderOnlyAsMarkers(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAccount(user);

  const body = screen.getByRole('main');
  expect(body).toHaveTextContent(ACCOUNT_UPDATE_STORED_STATE_LABEL);
  expect(screen.getAllByText(REDACTED).length).toBeGreaterThanOrEqual(2);
  expect(document.body.textContent ?? '').not.toContain(UNMASKED_NATIONAL_IDENTIFIER);
  expect(control('ssnPart1')).toHaveValue('');
  expect(control('ssnPart2')).toHaveValue('');
  expect(control('ssnPart3')).toHaveValue('');
  expect(control('governmentIssuedId')).toHaveValue('');
}

/**
 * The screen renders no control beyond the inventory the map declares.
 *
 * WHY : ⚠️ Assumptions: the set of identifiers is compared rather than the count, so the failure
 *       names the surplus or missing control instead of reporting two numbers. This is what
 *       forecloses an added field carrying data the reference never put on this screen -- a card
 *       security value, for instance, which `ui/src/api/types.ts` declares nowhere and which
 *       therefore has no control, no shape and no route to the DOM.
 * @returns {Promise<void>} Resolves once the rendered inventory has been compared.
 */
async function theScreenRendersNoControlBeyondItsInventory(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAccount(user);

  const rendered = Array.from(screen.getByRole('main').querySelectorAll('input'))
    .map(identifierOfInput)
    .sort();
  const declared = FORM_FIELD_WIDTHS.map(identifierOfRow).sort();
  expect(rendered).toStrictEqual(declared);
}

/**
 * Reads a rendered control's identifier.
 * @param {HTMLInputElement} input - A control in the screen body.
 * @returns {string} Its identifier.
 */
function identifierOfInput(input: HTMLInputElement): string {
  return input.id;
}

/**
 * Reads the identifier the screen mints for one inventory row's field.
 * @param {FormFieldWidth} row - An inventory row.
 * @returns {string} The identifier that row's control must carry.
 */
function identifierOfRow(row: FormFieldWidth): string {
  return fieldDomId(row.field);
}

/**
 * Amounts keep their cents, which is what proves no numeric coercion happened.
 *
 * WHY : ⚠️ Trade-offs: the discriminator is the surviving `.00`, and it is a real one. A value
 *       routed through a number -- by `Number()`, by a JSON number, or by any arithmetic -- comes
 *       back as `5000` and loses the two decimal places that `ACCT-CURR-BAL PIC S9(10)V99` at
 *       `app/cpy/CVACT01Y.cpy` L7 declares and that `NUMERIC(12,2)` stores. The control is also
 *       asserted not to be a numeric input, because a numeric input would hand the value to the
 *       platform's own parser and the same loss would happen below this file.
 * @returns {Promise<void>} Resolves once both amounts have been checked.
 */
async function amountsKeepTheirCents(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAccount(user);

  for (const field of ['creditLimit', 'currentBalance'] as const) {
    const rendered = control(field);
    expect(rendered).not.toHaveAttribute('type', 'number');
    const shown = rendered.getAttribute('value') ?? '';
    expect(shown.endsWith('.00'), `${field} must keep its cents`).toBe(true);
  }
  expect(typeof accountView().account.creditLimit).toBe('string');
}

/**
 * The account key travels with every request rather than in a session.
 *
 * WHY : ⚠️ Assumptions: AAP section 0.7.1 replaces `CDEMO-ACCT-ID PIC 9(11)`
 *       (`app/cpy/COCOM01Y.cpy` L38) -- a member of the structure the terminal echoed back
 *       between turns -- with selection context supplied per request. The screen therefore hands
 *       the key to the transport on every call, and holds no ambient copy for the service to
 *       trust. Trade-offs: `ui/src/api/accounts.ts` places it in the POST body rather than in a
 *       URL path segment, which is not the shape the specification describes; the property that
 *       matters is that it is request-scoped, and the body carries that as well as a path would.
 * @returns {Promise<void>} Resolves once both calls have been inspected.
 */
async function theAccountKeyTravelsWithEveryRequest(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();

  await loadAccount(user);

  // WHY : Assumptions: the read is asserted to receive the key as its ARGUMENT, which is the whole
  //       claim -- the screen supplies the selection context on the call rather than relying on
  //       anything the server remembers between turns. The write side of the same property is
  //       asserted on the success flow, which already drives a commit, so this case stays a read.
  expect(vi.mocked(readAccountView)).toHaveBeenCalledWith(ACCOUNT_ID);
  expect(vi.mocked(readAccountView)).toHaveBeenCalledTimes(1);
}

/**
 * Registers the width and inventory cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function fieldConstraintCases(): void {
  it(
    'applies the width the symbolic map declares to every field',
    everyDeclaredWidthMatchesTheSymbolicMap,
  );
  it(
    'accounts for all fifty-four symbolic-map inputs',
    theInventoryAccountsForEverySymbolicMapInput,
  );
  it(
    'keeps the account key eleven characters wide',
    theAccountKeyKeepsElevenCharactersAcrossBothMapsets,
  );
  it(
    'bounds every rendered control to its declared width',
    everyControlBoundsEntryToItsDeclaredWidth,
  );
  it('lands the initial cursor on the account key', theInitialCursorLandsOnTheAccountKey);
  it('clamps an over-long entry to the declared width', anOverLongEntryIsClampedToTheDeclaredWidth);
  it(
    'clamps a multi-byte entry on the width the record imposes',
    aMultiByteEntryIsClampedOnTheWidthTheRecordImposes,
  );
  it('keeps an astral character whole while clamping', anAstralCharacterIsKeptWholeWhileClamping);
}

/**
 * An entry longer than its field is shortened to the declared width.
 *
 * WHY : ⚠️ Assumptions: the entry is raised as a change event, which is precisely the path that
 *       `maxLength` does NOT police -- the attribute constrains typing and pasting in a browser and
 *       constrains nothing about a value assigned to the control. So this case observes the screen's
 *       own limit rather than the platform's, which is the limit that has to hold: a responsive review
 *       reported an over-long entry arriving truncated with nothing said about it.
 * @returns {Promise<void>} Resolves once the clamped value has been read.
 */
async function anOverLongEntryIsClampedToTheDeclaredWidth(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAccount(user);

  fill('lastName', 'A'.repeat(LAST_NAME_DECLARED_WIDTH + 5));

  expect(control('lastName')).toHaveValue('A'.repeat(LAST_NAME_DECLARED_WIDTH));
}

/**
 * A multi-byte entry is clamped on bytes, which is the measure the record is declared in.
 *
 * WHY : ⚠️ Assumptions: twenty-five accented letters are twenty-five code points and fifty bytes, and
 *       `lastName` is `PIC X(25)` -- twenty-five BYTES on the record. `maxLength` would admit all
 *       twenty-five because it counts UTF-16 units; the field could store twelve. The expected count
 *       is derived here rather than written as a number, so the case states the RULE and does not have
 *       to be edited if the width is ever restated from the mapset.
 * @returns {Promise<void>} Resolves once the clamped value has been read.
 */
async function aMultiByteEntryIsClampedOnTheWidthTheRecordImposes(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAccount(user);

  fill('lastName', ACCENTED_LETTER.repeat(LAST_NAME_DECLARED_WIDTH));

  const fitting = Math.floor(LAST_NAME_DECLARED_WIDTH / ACCENTED_LETTER_BYTES);
  expect(control('lastName')).toHaveValue(ACCENTED_LETTER.repeat(fitting));
}

/**
 * Clamping drops whole characters, never half of a surrogate pair.
 *
 * WHY : ⚠️ Assumptions: this is asserted against the exported function rather than through the tree,
 *       because what it pins is the UNIT the clamp walks and a rendered assertion could not tell a
 *       code-point walk from a UTF-16 one on any value a form realistically holds. An emoji is one
 *       code point, two UTF-16 units and four UTF-8 bytes, so a four-wide field holds exactly one --
 *       and a clamp that walked UTF-16 units would return a lone surrogate, which is not a character.
 * @returns {void} Nothing; the assertions run for their effect.
 */
function anAstralCharacterIsKeptWholeWhileClamping(): void {
  const clamped = clampToDeclaredWidth(`${ASTRAL_CHARACTER}${ASTRAL_CHARACTER}`, 4);

  expect(Array.from(clamped)).toStrictEqual([ASTRAL_CHARACTER]);
}

/**
 * Registers the split-group cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
/**
 * The seven part-group captions name a real group and point at a real control.
 *
 * WHY : ⚠️ Purpose: a browser accessibility audit reported seven form-field labels with nothing
 *       associated, and they were precisely these captions -- the four dates, the national
 *       identifier and both telephone numbers each paint ONE caption over two or three boxes. Each
 *       box was individually named already; what was missing was the group.
 * WHY : Assumptions: both halves are asserted, because the audit's finding and the grouping are two
 *       different defects. The caption's `for` is what stops it dangling; the group's `role` and its
 *       name are what make the visible grouping announceable.
 * @returns {Promise<void>} Resolves once all seven captions have been asserted.
 */
async function eachPartGroupCaptionNamesItsGroup(): Promise<void> {
  await renderScreen();

  const groups: readonly AccountUpdateFieldName[] = [
    'openDateYear',
    'expirationDateYear',
    'reissueDateYear',
    'dateOfBirthYear',
    'ssnPart1',
    'phone1AreaCode',
    'phone2AreaCode',
  ];

  /*
   * WHY : Assumptions: the expected caption is read from the painted-label map by the same key the
   *       screen renders it under, rather than retyped here. The captions carry contract punctuation --
   *       `Opened :` has a space before its colon and `Reissue:` does not -- and a retyped copy is
   *       exactly where that detail is lost.
   */
  expect(groups).toHaveLength(PART_GROUP_COUNT);

  for (const firstPart of groups) {
    const captionId = groupCaptionDomId(firstPart);
    const captionElement = document.getElementById(captionId);

    expect(captionElement, `no caption element carries ${captionId}`).not.toBeNull();
    expect(PAINTED_CAPTIONS).toContain(captionElement?.textContent ?? '');

    const label = captionElement?.closest('label');
    expect(label, `the caption for ${firstPart} is not inside a label`).not.toBeNull();
    expect(label?.getAttribute('for')).toBe(fieldDomId(firstPart));

    const group = control(firstPart).closest('[role="group"]');
    expect(group, `the parts beside ${firstPart} are in no group`).not.toBeNull();
    expect(group?.getAttribute('aria-labelledby')).toBe(captionId);
  }
}

/**
 * No caption is left pointing at a control that does not exist.
 *
 * WHY : Assumptions: this is the audit's own check, restated as a property of the whole rendered
 *       document rather than of the seven captions the case above walks. A caption added later with
 *       no target would pass that case by not being in its list, and fail this one.
 * @returns {Promise<void>} Resolves once every label has been checked.
 */
async function noPaintedCaptionPointsAtNothing(): Promise<void> {
  await renderScreen();

  const labels = Array.from(document.querySelectorAll('label'));
  expect(labels.length).toBeGreaterThan(0);

  for (const label of labels) {
    const target = label.getAttribute('for');

    /*
     * WHY : Assumptions: a label with no `for` at all is only acceptable when it WRAPS its control,
     *       which is the other association HTML admits. Accepting an unwrapped one would let the
     *       defect back in under a different shape.
     */
    if (target === null) {
      expect(
        label.querySelector('input, select, textarea'),
        `a label reading '${label.textContent ?? ''}' has neither a for nor a wrapped control`,
      ).not.toBeNull();
      continue;
    }

    expect(
      document.getElementById(target),
      `a label points at the absent '${target}'`,
    ).not.toBeNull();
  }
}

/**
 * The fetch turn leaves the cursor on the account status field.
 *
 * WHY : Assumptions: the field is the reference's, not a preference. The cursor table of
 *       `3300-SETUP-SCREEN-ATTRS` opens `WHEN FOUND-ACCOUNT-DATA ... MOVE -1 TO ACSTTUSL` at
 *       `app/cbl/COACTUPC.cbl` L3008 to L3011, and `ACSTTUS` is the account status field at
 *       `app/bms/COACTUP.bms` L94 -- the first field the read unlocks.
 * WHY : ⚠️ Purpose: a browser review measured `document.activeElement` as the document BODY after
 *       this turn, so a keyboard operator was returned to the top of the document and had to
 *       traverse the skip link, the sign-off control and the title band to reach the fields the read
 *       had just unlocked.
 * @returns {Promise<void>} Resolves once the focused element has been asserted.
 */
async function theFetchTurnLeavesTheCursorOnTheFirstUnlockedField(): Promise<void> {
  const user = await renderScreen();
  await loadAccount(user);

  await waitFor(
    /**
     * Asserts the cursor rests on the first unlocked control once the fetch turn has painted.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(document.activeElement).toBe(control('activeStatus'));
    },
  );
}

/**
 * A write in flight holds every record field still and makes the exit key inert.
 *
 * WHY : ⚠️ Purpose: a browser review measured every record field reporting `disabled: false` with a
 *       text cursor, and `F3=Exit` enabled and undimmed, while the mutating request was in the air --
 *       so a value could be retyped after the body carrying the previous value had left, and the
 *       operator could leave for the menu without ever learning whether the write landed.
 * WHY : Assumptions: the write is held unresolved deliberately. The state under test exists only
 *       while the request is in flight, so a resolved mock would render it unobservable.
 * @returns {Promise<void>} Resolves once the in-flight state has been asserted.
 */
async function aWriteInFlightHoldsTheRecordStillAndStandsTheExitKeyDown(): Promise<void> {
  const user = await renderScreen();
  await loadAndValidate(user);

  vi.mocked(updateAccount).mockReturnValue(
    new Promise(
      /**
       * Holds the write outstanding by never settling, so the in-flight state can be read.
       * @returns {void} Nothing; the promise is deliberately left pending.
       */
      (): void => undefined,
    ),
  );
  await acceptTheConfirmation(user);

  await waitFor(
    /**
     * Asserts the record has gone read-only for the duration of the write.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(control('firstName')).toHaveAttribute('readonly');
    },
  );

  expect(control('firstName')).toHaveAttribute('aria-busy', 'true');

  /*
   * WHY : ⚠️ Assumptions: the exit key is asserted PRESENT AND INERT rather than withdrawn, and that
   *       is the contract `ui/src/layout/PfKeyBar.tsx` states rather than a concession to what the
   *       component happens to do. Its own reasoning cites `app/cbl/COACTUPC.cbl` L905 to L916: the
   *       reference sets `PFK-INVALID` for a key it does not admit and then resolves it with
   *       `SET CCARD-AID-ENTER TO TRUE`, so the key is accepted and reduced to a refresh -- present,
   *       labelled, inert. Withdrawing the descriptor is the OTHER treatment the reference expresses,
   *       through `ATTRB=(ASKIP,DRK)` at `app/bms/COACTUP.bms` L498 to L507, and this screen already
   *       uses it for the two keys the source darkens. Exit is not one of them.
   * WHY : Trade-offs: an inert control is the stronger feedback for the defect this closes. The review
   *       measured the exit key enabled and undimmed while the write was in the air, so the operator
   *       had no signal at all; a dimmed control is continuously visible BEFORE they commit, where a
   *       withdrawn one would also move every legend control after it and disturb focus mid-turn.
   */
  expect(legendLabels()).toContain(ACCOUNT_UPDATE_KEY_LABELS.PFK03);
  expect(legendControlFor(ACCOUNT_UPDATE_KEY_LABELS.PFK03)).toBeDisabled();
}

/**
 * A refusal naming a field this screen does not paint does not become its headline.
 *
 * WHY : ⚠️ Purpose: a browser review drove a refusal whose first entry named another screen's field
 *       and measured that sentence as this screen's only headline -- naming a control that is not
 *       present, while the on-screen field's own refusal went unpromoted. The operator was shown a
 *       refusal they had no way to satisfy.
 * WHY : Assumptions: the four outcomes are asserted on the chooser directly. Driving all four through
 *       the rendered screen would assert the same function four times through four turns, where the
 *       rendered case below establishes once that the screen uses it.
 * @returns {void} Nothing; the assertions run for their effect.
 */
function anOffScreenRefusalDoesNotBecomeTheHeadline(): void {
  /*
   * WHY : Assumptions: the state is `NOT_OK` because those are the only two states the wire admits --
   *       `ui/src/api/types.ts` L217 declares `FieldValidationState` as `'NOT_OK' | 'BLANK'`, which is
   *       the templated highlight copybook's own pair: `app/cpy/CSSETATY.cpy` L18-L26 colours a field
   *       for either condition and writes the asterisk for the blank one only. `NOT_OK` is the right
   *       one here because the headline choice under test is about WHICH field a refusal names, not
   *       about whether the field was left empty.
   */
  const offScreen: FieldError = {
    field: 'userId',
    message: 'off the screen',
    state: 'NOT_OK',
  };
  const onScreen: FieldError = {
    field: 'firstName',
    message: 'on the screen',
    state: 'NOT_OK',
  };

  expect(isAccountUpdateField('firstName')).toBe(true);
  expect(isAccountUpdateField('userId')).toBe(false);

  expect(headlineForFieldErrors([offScreen, onScreen], 'the problem')).toBe(onScreen.message);
  expect(headlineForFieldErrors([onScreen, offScreen], 'the problem')).toBe(onScreen.message);
  expect(headlineForFieldErrors([offScreen], 'the problem')).toBe('the problem');
  expect(headlineForFieldErrors([offScreen], null)).toBeNull();
}

/**
 * The screen publishes the on-screen refusal and marks its control.
 *
 * WHY : Assumptions: this is the rendered half of the case above -- it establishes that the screen
 *       routes its headline through the chooser, and that the off-screen entry marks nothing because
 *       no control bears its name.
 * @returns {Promise<void>} Resolves once the band and the marked control have been asserted.
 */
async function theOnScreenRefusalIsThePublishedHeadline(): Promise<void> {
  const user = await renderScreen();
  await loadAccount(user);

  vi.mocked(validateAccountUpdate).mockRejectedValue(
    refusal(BAD_REQUEST_STATUS, 'the problem sentence', [
      { field: 'userId', message: 'off the screen', state: 'NOT_OK' },
      { field: 'firstName', message: 'on the screen', state: 'NOT_OK' },
    ]),
  );

  editFirstName('GRACE');
  await pressPfKey(user, 'ENTER');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await waitFor(
    /**
     * Asserts the band has taken the sentence the turn reported.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(band).toHaveTextContent('on the screen');
    },
  );
  expect(band).not.toHaveTextContent('off the screen');
}

/**
 * Every control is sized from the character width its own copybook clause declares.
 *
 * WHY : ⚠️ Purpose: a browser review measured all 43 controls sharing one full-width rule, so
 *       `Opened :`, `Expiry :` and `Reissue:` each painted three boxes of identical width -- 146,
 *       147 and 146 pixels -- putting the four-digit year box and the two-digit month box at the same
 *       size, and `Active Y/N:` painted 446 pixels at 992 and 723 at 1600 for ONE character. The
 *       defect grew with the viewport because nothing in the rule referred to the data.
 * WHY : Assumptions: the declared width is read from the screen's own width map rather than retyped,
 *       so this asserts that the RENDERED size is derived from the same transcription `maxLength` and
 *       the entry clamp already use -- which is the property that stops a one-character field painting
 *       at 45 per cent of a viewport.
 * WHY : Assumptions: the inline maximum is asserted rather than a computed pixel width, because a
 *       jsdom layout has no real typography to measure. The declaration is what the screen controls
 *       and what a browser then resolves.
 * @returns {Promise<void>} Resolves once every control's declared size has been asserted.
 */
async function eachControlIsSizedFromItsDeclaredWidth(): Promise<void> {
  await renderScreen();

  const distinctWidths = new Set<string>();

  for (const row of FORM_FIELD_WIDTHS) {
    const declared = ACCOUNT_UPDATE_FIELD_WIDTHS[row.field];

    /*
     * WHY : ⚠️ Refactoring Rationale: the expectation now allows for the MARKER SLOT on the one field
     *       that carries one, where it used to assert the declared width alone for all of them. A
     *       browser review measured why: with a suffix present the design system sizes the affix
     *       WRAPPER, whose space the value and the marker then share, so a box measured for the value
     *       alone clips the value -- on a sibling screen's two-character key that showed as a record
     *       identity rendering as one glyph and a sliver. `copybookFieldWidthStyle` therefore takes the
     *       slot's width as an argument, and a field that reserves it is CORRECT rather than oversized.
     * WHY : ⚠️ Assumptions: whether a field carries the slot is read from the RENDERED tree rather than
     *       from a list retyped here. The design system moves a control into an affix wrapper exactly
     *       when it is given a suffix, so the wrapper's presence IS the question being asked, and a
     *       screen that later gave a second field a marker would be measured correctly without this
     *       case being edited.
     * WHY : Assumptions: the allowance is stated as the marker's own characters plus one, which is the
     *       contract `copybookFieldWidthStyle` documents -- the marker's glyphs, and one further cell
     *       because `ch` on the wrapper resolves in the theme's proportional face while the value
     *       renders in the wider fixed-pitch one. Stating it exactly rather than as a bound is what
     *       keeps the case discriminating: a measure that reserved a comfortable surplus instead of the
     *       slot would still be a measure not derived from the data.
     */
    const carriesMarkerSlot = control(row.field).closest('.ant-input-affix-wrapper') !== null;
    const reserved = carriesMarkerSlot ? declared + BLANK_FIELD_MARKER_CHARACTERS + 1 : declared;
    const style = sizedBox(row.field).getAttribute('style') ?? '';

    expect(style, `${row.field} is not sized from its declared width`).toContain(
      `${String(reserved)}ch`,
    );
    distinctWidths.add(`${String(declared)}ch`);
  }

  /*
   * WHY : Assumptions: more than one distinct measure is asserted as well as each field's own, because
   *       every assertion above would also pass if the map happened to declare one width for
   *       everything -- and a single shared measure is precisely the defect being closed.
   */
  expect(distinctWidths.size).toBeGreaterThan(1);
  expect(sizedBox('activeStatus').getAttribute('style') ?? '').toContain('1ch');
  expect(sizedBox('openDateYear').getAttribute('style') ?? '').toContain('4ch');
  expect(sizedBox('openDateMonth').getAttribute('style') ?? '').toContain('2ch');
}

/**
 * A control the operator cannot type into is painted on the unavailable surface.
 *
 * WHY : ⚠️ Purpose: a browser review measured the read-only controls at `rgb(255,255,255)` --
 *       indistinguishable from an editable one except by border -- and separately reported the
 *       read-only account number as the one field that looked ACTIVE beside its editable neighbours.
 *       The single field that cannot be edited was the one advertising that it could.
 * WHY : Assumptions: the control stays reachable while it is painted as unavailable, so both halves are
 *       asserted. `readOnly` is what keeps it in the focus order and the accessibility tree, which is
 *       why this screen does not use `disabled`; the surface is borrowed without the removal.
 * @returns {Promise<void>} Resolves once both states have been asserted.
 */
async function aProtectedControlIsPaintedAsUnavailable(): Promise<void> {
  const user = await renderScreen();

  const beforeRead = sizedBox('firstName');
  expect(beforeRead).toHaveAttribute('readonly');
  expect(beforeRead.getAttribute('style') ?? '').toContain(UNAVAILABLE_SURFACE_REFERENCE);

  await loadAccount(user);

  const editable = sizedBox('firstName');
  expect(editable).not.toHaveAttribute('readonly');
  expect(editable.getAttribute('style') ?? '').not.toContain(UNAVAILABLE_SURFACE_REFERENCE);

  /*
   * WHY : Assumptions: the account key is checked on the SAME turn as an editable field, because the
   *       review's complaint was a contrast between neighbours rather than a property of one control.
   */
  const stillProtected = sizedBox('accountId');
  expect(stillProtected).toHaveAttribute('readonly');
  expect(stillProtected.getAttribute('style') ?? '').toContain(UNAVAILABLE_SURFACE_REFERENCE);
}

/**
 * No cell of the record grid is rendered empty.
 *
 * WHY : ⚠️ Purpose: an empty grid cell stood in the account block, rendered only to hold the left half
 *       of one mapset row open so later fields kept their left/right pairing. A browser review
 *       measured the cost as a void in the left column, present even in the empty pre-read state --
 *       a static grid artefact rather than a data one -- where the eye jumps a full row height.
 * WHY : Assumptions: the pre-read state is the one asserted, which is where the review found it and
 *       where a data-driven explanation is unavailable.
 * @returns {Promise<void>} Resolves once every grid cell has been asserted non-empty.
 */
async function noGridCellIsRenderedEmpty(): Promise<void> {
  await renderScreen();

  /*
   * WHY : Assumptions: the document is queried rather than a render result, because `renderScreen`
   *       hands back the user-event instance this file drives every interaction through -- the shell
   *       harness owns the container.
   */
  const cells = Array.from(document.querySelectorAll('.ant-col'));
  expect(cells.length).toBeGreaterThan(0);

  for (const cell of cells) {
    expect(
      cell.childElementCount,
      `an empty grid cell is rendered: ${cell.outerHTML}`,
    ).toBeGreaterThan(0);
  }
}

/**
 * The second address line paints a visible label, and it is the program's own name for the field.
 *
 * WHY : ⚠️ Purpose: a browser review measured the control orphaned at 576 and below -- no visible
 *       label, landing between `State` and `Zip` -- because its only association with `Address:` was
 *       that the two-column grid put it directly beneath address line 1.
 * WHY : Assumptions: the visible label and the accessible name are asserted to be the SAME words. The
 *       association the mapset relied on was positional, which AAP section 0.3.4 gap G1 surrenders,
 *       and `app/cbl/COACTUPC.cbl` L1614 supplies the words -- so this checks that the name the
 *       control already announced is now also the name it paints.
 * @returns {Promise<void>} Resolves once the label and the name have been asserted.
 */
async function theSecondAddressLineIsLabelled(): Promise<void> {
  await renderScreen();

  const line2 = control('addressLine2');
  const label = document.querySelector<HTMLLabelElement>(
    `label[for="${fieldDomId('addressLine2')}"]`,
  );

  expect(label, 'the second address line paints no label').not.toBeNull();
  expect(collapse(label?.textContent ?? '')).toBe(collapse(ADDRESS_LINE_2_NAME));
  expect(line2).toHaveAccessibleName(ADDRESS_LINE_2_NAME);
}

/**
 * The section heading is a real heading, at the rank the paired view screen uses.
 *
 * WHY : ⚠️ Purpose: this string used to be emphasised text inside a rule, so the section was not in
 *       the document's heading hierarchy at all -- while `/account/view` paints the same string as a
 *       heading. A browser review reported the two patterns as divergent, and the divergence was
 *       navigational: an operator could jump to the section on the view screen and not on this one.
 * WHY : Assumptions: the rank is read from `ui/src/layout/ScreenTitle.tsx` rather than retyped, which
 *       is the property that keeps it one below the screen caption if the caption's rank ever moves.
 * @returns {Promise<void>} Resolves once the heading and its rank have been asserted.
 */
async function theSectionHeadingIsARealHeading(): Promise<void> {
  await renderScreen();

  const heading = screen.getByRole('heading', {
    name: ACCOUNT_UPDATE_HEADINGS.customerSection,
  });

  expect(heading.tagName.toLowerCase()).toBe(`h${String(SECTION_HEADING_LEVEL)}`);
}

/**
 * Registers the cases covering the split-part groups and the grid they sit in.
 * @returns {void} Nothing; the registrations are the effect.
 */
function splitGroupCases(): void {
  it('sizes each control from its declared width', eachControlIsSizedFromItsDeclaredWidth);
  it('paints a protected control as unavailable', aProtectedControlIsPaintedAsUnavailable);
  it('renders no empty grid cell', noGridCellIsRenderedEmpty);
  it('labels the second address line', theSecondAddressLineIsLabelled);
  it('renders the section heading as a real heading', theSectionHeadingIsARealHeading);
  it('names each part group by its painted caption', eachPartGroupCaptionNamesItsGroup);
  it('leaves no painted caption pointing at nothing', noPaintedCaptionPointsAtNothing);
  it(
    'leaves the cursor on the first unlocked field after a fetch',
    theFetchTurnLeavesTheCursorOnTheFirstUnlockedField,
  );
  it(
    'holds the record still and stands the exit key down while a write is in flight',
    aWriteInFlightHoldsTheRecordStillAndStandsTheExitKeyDown,
  );
  it(
    'does not headline a refusal naming an absent field',
    anOffScreenRefusalDoesNotBecomeTheHeadline,
  );
  it('publishes the on-screen refusal as its headline', theOnScreenRefusalIsThePublishedHeadline);
  it('renders each date as three independent parts', eachSplitDateRendersThreeIndependentParts);
  it(
    'renders the national identifier as three independent parts',
    theNationalIdentifierRendersThreeIndependentParts,
  );
  it(
    'renders each telephone as three independent parts',
    eachTelephoneRendersThreeIndependentParts,
  );
}

/**
 * Registers the design-system composition cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function compositionCases(): void {
  it('paints every control through the design system', everyControlIsADesignSystemControl);
  it(
    'admits one character from a two-value domain in each status flag',
    theStatusFlagsAdmitOneCharacterFromATwoValueDomain,
  );
}

/**
 * Registers the message-catalogue fidelity cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function catalogueFidelityCases(): void {
  it(
    'carries the baseline line of every catalogued sentence',
    everyCatalogedSentenceCarriesItsBaselineLine,
  );
  it(
    'keeps the four fragile spellings character for character',
    theFragileSpellingsSurviveCharacterForCharacter,
  );
  it(
    'keeps the account-key refusal as two conditions with one sentence',
    theAccountKeyRefusalIsTwoConditionsWithOneSentence,
  );
  it('carries the program line of every composed label', everyComposedLabelCarriesItsProgramLine);
}

/*
 * ⚠️ Assumptions: NO invalid-key sentence is asserted anywhere in this file, and the omission is
 * measured rather than assumed. `app/cbl/COACTUPC.cbl` never moves `CCDA-MSG-INVALID-KEY` -- a
 * search of the program returns zero occurrences -- which makes it one of six online programs
 * that answer an unrecognised attention identifier by simply redisplaying the screen. The other
 * five are `COACTVWC`, `COCRDLIC`, `COCRDSLC`, `COCRDUPC` and `COTRTLIC`. Asserting a sentence
 * here would therefore invent behaviour the reference does not have, and asserting its absence
 * would restate the shared hook's contract, which `ui/src/layout/usePfKeys.ts` owns and its own
 * suite covers. The alias collapse of PF13-PF24 onto PF01-PF12 is left to that suite for the same
 * reason: it is one table shared by every screen, not a property of this one.
 */

/**
 * Registers the attention-identifier cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function attentionIdentifierCases(): void {
  it('offers processing and exit on the opening legend', theOpeningLegendOffersProcessAndExitOnly);
  it(
    'asks for the save key rather than committing',
    validationAsksForTheSaveKeyRatherThanCommitting,
  );
  it(
    'commits nothing on a second processing key',
    aSecondProcessingKeyAfterValidationCommitsNothing,
  );
  it('offers save and cancel once changes validate', theSaveAndCancelKeysAppearOnceChangesValidate);
  it('opens the confirmation on the safe choice', theConfirmationOpensOnTheSafeChoice);
  it(
    'withdraws the confirmation on escape and keeps the entry',
    escapeWithdrawsTheConfirmationAndKeepsTheEntry,
  );
  it(
    'carries the primary emphasis on the saving key alone',
    onlyTheWritingKeyCarriesThePrimaryEmphasis,
  );
  it('closes the screen on the exit key', theExitKeyClosesTheScreen);
  it('closes the screen on the exit legend control', theExitLegendControlClosesTheScreen);
}

/**
 * Returns the in-content save control, which is the surface's anchor.
 *
 * WHY : Assumptions: it is found by ELIMINATION rather than by role and name, because `'F5=Save'` is
 *       painted in up to three places at once -- the legend, this anchor and the surface's own accept
 *       control -- and a tree-wide query resolves to whichever comes first in document order. Excluding
 *       the legend landmark and the surface leaves exactly one, and the count is asserted so a fourth
 *       copy appearing would fail here rather than silently changing which control the case measures.
 * @returns {HTMLButtonElement} The in-content save control.
 * @throws {Error} When elimination does not leave exactly one control, so the failure names the
 *   ambiguity instead of measuring an arbitrary member of it.
 */
function inContentSaveControl(): HTMLButtonElement {
  const wanted = collapse(ACCOUNT_UPDATE_KEY_LABELS.PFK05);
  const candidates = Array.from(document.querySelectorAll<HTMLButtonElement>('button')).filter(
    /**
     * Keeps a control that carries the label but sits in neither the legend nor the surface.
     * @param {HTMLButtonElement} button - Control to test.
     * @returns {boolean} `true` when it is the in-content copy.
     */
    (button: HTMLButtonElement): boolean =>
      collapse(button.textContent ?? '') === wanted &&
      !legendRegion().contains(button) &&
      button.closest('.ant-popconfirm') === null,
  );

  if (candidates.length !== 1) {
    throw new Error(
      `elimination left ${String(candidates.length)} in-content controls labelled '${wanted}', not one`,
    );
  }

  return candidates[0] as HTMLButtonElement;
}

/**
 * ⚠️ Asserts the save surface is brought onto the display before it asks, and opens downward.
 *
 * ⚠️ Purpose: close two browser-measured defects that no request-shape, focus or text case can see.
 * This is the longest screen in the application -- its body measured 3567 pixels against a 486-pixel
 * visible band at a 375-pixel width -- and the surface anchors to a control below all forty editable
 * fields, at `top: 2468`. A turn taken from the function-key legend needs no pointer and so no
 * scrolling, and with the body where the operator left it the surface opened at `top: 2379`: roughly
 * fifteen hundred pixels below a 900-pixel display, with BOTH answers outside it and focus placed on a
 * Cancel control that could not be seen. Pressing the save key produced no visible change at all.
 * Separately, the design system's default upward placement needs about ninety pixels of clear space
 * above that anchor; at 1280 the gap just fits and the surface clipped only 4.39 pixels of the three
 * `Phone 2` boxes, but at 375 the single-column stack closes it and the surface covered the `Primary
 * Card Holder Y/N:` control across 100 per cent of its width and 88.7 per cent of its height, with
 * `elementFromPoint` at that control's centre returning the surface's own title. The operator was asked
 * to commit a record while one of its values was painted over.
 *
 * ⚠️ Assumptions: the two halves are asserted independently because they are independent fixes with
 * independent failure modes -- an anchor scrolled into view still collides with a field if the surface
 * opens upward, and a downward surface is still invisible if the anchor is off the display. Each half
 * was negative-controlled on its own.
 *
 * ⚠️ Assumptions: neither half is a geometry check, and cannot be. jsdom performs no layout, lays out
 * no scrollport and -- as `ui/src/test/setup.ts` records -- does not implement `scrollIntoView` at all,
 * so the rectangles are measurable only in a real browser and are verified there. What is falsifiable
 * here is that the screen asks for the scroll before it opens, and that it names the direction; the
 * placement is read from the design system's OWN class rather than from this file's source text, so a
 * prop renamed by a library upgrade would fail here instead of passing on a stale literal.
 *
 * Assumptions: BOTH routes to the gate are exercised, because they run through one function by this
 * screen's own stated contract and a fix reaching only one of them would reintroduce the divergence
 * that contract exists to prevent.
 * @returns {Promise<void>} Resolves once both routes and the placement have been asserted.
 */
async function bringsTheSaveSurfaceIntoViewAndOpensItDownward(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);

  const anchor = inContentSaveControl();
  const surfaceStoodWhenScrolled: boolean[] = [];
  const scrolled = vi.spyOn(anchor, 'scrollIntoView').mockImplementation(
    /**
     * Records whether a surface already stood at the moment the anchor was scrolled.
     * @returns {void} Nothing; the recording is the observable.
     */
    (): void => {
      surfaceStoodWhenScrolled.push(confirmationPopup() !== null);
    },
  );

  for (const route of ['legend', 'pointer']) {
    surfaceStoodWhenScrolled.length = 0;
    if (route === 'legend') {
      await pressPfKey(user, 'PFK05');
    } else {
      await user.click(anchor);
    }
    await waitFor(expectConfirmationToBeOpen);

    expect(
      surfaceStoodWhenScrolled,
      `the ${route} route must bring the anchor into view exactly once, before the surface stands`,
    ).toEqual([false]);
    expect(
      confirmationPopup()?.className,
      `and the ${route} route's surface must open downward, away from the form's own fields`,
    ).toContain('ant-popover-placement-bottom');
    expect(
      vi.mocked(updateAccount),
      `and asking must still cost no write on the ${route} route`,
    ).not.toHaveBeenCalled();

    await user.keyboard('{Escape}');
    await waitFor(
      /**
       * Asserts the surface has been withdrawn, for use inside a `waitFor`.
       * @returns {void} Nothing; the assertion runs for its effect.
       */
      (): void => {
        expect(confirmationPopup()).toBeNull();
      },
    );
  }

  scrolled.mockRestore();
}

/**
 * Registers the confirmation cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function confirmationCases(): void {
  it('raises a confirmation before writing', theSaveKeyRaisesAConfirmationBeforeWriting);
  it(
    'brings the save surface into view and opens it downward',
    bringsTheSaveSurfaceIntoViewAndOpensItDownward,
  );
  it('writes nothing when the confirmation is dismissed', dismissingTheConfirmationWritesNothing);
}

/**
 * Drives a save the service refuses as a concurrency conflict, on a screen holding one edit.
 *
 * WHY : Assumptions: the arrangement is shared by the three cases below rather than repeated in
 *       each, because all three assert DIFFERENT consequences of the SAME turn -- what the form
 *       holds, what the two channels say and what the legend paints -- and three private
 *       arrangements is how one of them comes to drive a subtly different turn from the others.
 *       The edited value is returned so a case can assert against the value it typed rather than
 *       against a constant that has to be kept in step with the helper.
 * @param {UserEvent} user - The operator driving the tree.
 * @returns {Promise<string>} The value the operator left in the first-name control.
 */
async function refuseTheSaveAsAConflict(user: UserEvent): Promise<string> {
  const edited = 'GRACE';
  await loadAndValidate(user);

  vi.mocked(isConflictFailure).mockReturnValue(true);
  vi.mocked(updateAccount).mockRejectedValue(
    refusal(409, MESSAGES.DATA_WAS_CHANGED_BEFORE_UPDATE.text),
  );
  await acceptTheConfirmation(user);

  return edited;
}

/**
 * A concurrent change leaves the operator's entry on screen.
 *
 * WHY : ⚠️⚠️ Assumptions: this is the claim a responsive review reported as a blocking defect --
 *       it drove one refused save and measured 42 of the 43 controls come back EMPTY, so the turn
 *       destroyed the work whose refusal it was reporting. The screen used to re-read the record on
 *       this outcome, and both ways that request could settle lost the entry: the failure arm blanks
 *       every value but the account key, and the success arm reseeds from the answer.
 * WHY : ⚠️ Assumptions: the read spy is asserted to have been called exactly ONCE -- the load's own
 *       read -- because that is what makes this a claim about the re-read rather than about the
 *       fixture. The reference performs no read on this outcome either: its concurrency arm at
 *       `app/cbl/COACTUPC.cbl` L2610 to L2612 sets the show-details action and the sentence and
 *       nothing else, while the `9000-READ-ACCT` in that same `EVALUATE` belongs to the PF12 arm.
 * @returns {Promise<void>} Resolves once the preserved entry has been asserted.
 */
async function aConcurrentChangeLeavesTheOperatorEntryOnScreen(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  const edited = await refuseTheSaveAsAConflict(user);

  await waitFor(expectInformationLineToRead(MESSAGES.PROMPT_FOR_CHANGES.text));

  expect(control('firstName')).toHaveValue(edited);
  expect(control('accountId')).toHaveValue(ACCOUNT_ID);
  expect(vi.mocked(readAccountView)).toHaveBeenCalledTimes(1);
}

/**
 * A concurrent change names itself once, on the refusal channel alone.
 *
 * WHY : ⚠️ Assumptions: the review read the two populated bands as contradicting each other, and the
 *       resolution is that exactly one of them NAMES the outcome. Row 23 carries the changed-record
 *       sentence and row 22 carries the prompt the reference derives from the action it set --
 *       `3250-SETUP-INFOMSG` recomputes that line on every send, so a populated row 22 is the mapset's
 *       arrangement rather than a second opinion. What would be a contradiction is the refusal
 *       appearing on both channels, which is asserted against.
 * @returns {Promise<void>} Resolves once both channels have been read.
 */
async function aConcurrentChangeNamesItselfOnTheRefusalChannelAlone(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await refuseTheSaveAsAConflict(user);

  expect(await messageBand()).toHaveTextContent(
    collapse(MESSAGES.DATA_WAS_CHANGED_BEFORE_UPDATE.text),
  );
  expect(await informationBand()).toHaveTextContent(collapse(MESSAGES.PROMPT_FOR_CHANGES.text));
  expect(await informationBand()).not.toHaveTextContent(
    collapse(MESSAGES.DATA_WAS_CHANGED_BEFORE_UPDATE.text),
  );
}

/**
 * A concurrent change leaves the cancel key advertised.
 *
 * WHY : ⚠️ Assumptions: the review reported the refusal as leaving "no way forward", and the way
 *       forward is the cancel turn -- it re-reads the record, which is what the sentence's own
 *       'Please review' asks for. The key was always BOUND on this turn; what was missing was its
 *       label, so this asserts the painted legend and not the handler.
 * WHY : ⚠️ Refactoring Rationale: the save legend is asserted PRESENT AND DISABLED, and this case used
 *       to assert it ABSENT. Both readings agree on the behaviour -- the fields are editable again on
 *       this turn, so a save that wrote entries no validation step had seen would be wrong, and the
 *       reference admits PF05 in the validated action alone at `app/cbl/COACTUPC.cbl` L905 to L916 --
 *       and they disagree about what an operator is shown. A browser review measured the absence and
 *       reported it: after the 409 the legend held `F12=Cancel` enabled and `F5=Save` had vanished from
 *       both the legend and the confirmation area, which tells the operator the action does not exist
 *       rather than that it is not available yet. `ui/src/layout/PfKeyBar.tsx` states the remedy against
 *       the same L905-to-L916 citation: an unavailable key is rendered disabled, never removed.
 * @returns {Promise<void>} Resolves once the legend has been read.
 */
async function aConcurrentChangeLeavesTheCancelKeyAdvertised(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await refuseTheSaveAsAConflict(user);

  await waitFor(expectLegendToPaint(ACCOUNT_UPDATE_KEY_LABELS.PFK12));

  expect(legendLabels()).toContain(collapse(ACCOUNT_UPDATE_KEY_LABELS.PFK05));
  expect(legendControlFor(collapse(ACCOUNT_UPDATE_KEY_LABELS.PFK05))).toBeDisabled();
  expect(legendControlFor(collapse(ACCOUNT_UPDATE_KEY_LABELS.PFK12))).toBeEnabled();
}

/**
 * Opens the save confirmation and returns it, failing with a named error when it did not open.
 * @param {UserEvent} user - The interaction driver for this case.
 * @returns {Promise<HTMLElement>} The open confirmation.
 * @throws {Error} When the save key opened nothing.
 */
async function openTheConfirmation(user: UserEvent): Promise<HTMLElement> {
  await pressPfKey(user, 'PFK05');
  await waitFor(expectConfirmationToBeOpen);

  const bubble = confirmationPopup();
  if (bubble === null) {
    throw new Error('the save key opened no confirmation');
  }

  return bubble;
}

/**
 * The confirmation opens with the focus on its safe choice, and nothing takes it back.
 *
 * ⚠️ Purpose: a prompt that opens with the focus on its accept control is not a confirmation at all --
 * the next Enter or Space commits, and Enter is the key the operator has just pressed to get here. The
 * measured trap this guards is the second half: a focus call placed AFTER the prompt opened stole the
 * focus back on another screen and left it on the committing control while the safe one merely looked
 * selected.
 *
 * ⚠️ Assumptions: the focus is asserted after the prompt has been waited for and NOT re-queried later,
 * because this screen also focuses controls from an effect. If that effect ran after the prompt opened
 * it would have run by the time this assertion is reached, so a passing assertion here is a statement
 * about the settled DOM rather than about one moment in it.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theConfirmationOpensOnTheSafeChoice(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);

  const bubble = await openTheConfirmation(user);

  /*
   * WHY : ⚠️ Assumptions: the tree is SETTLED before the focus is read, and the settle is the half of
   *       this case that catches the measured trap. The trap is an ordering one -- a focus call that
   *       runs AFTER the prompt opened -- so an assertion taken on the same task as the opening would
   *       see the safe choice focused and pass while the very next effect took the focus back. Draining
   *       the queue inside `act` runs every effect the opening scheduled, so what is read below is the
   *       state the operator's keyboard actually meets.
   */
  await act(
    /**
     * Drains the microtask queue so every effect the opening scheduled has run.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );

  expect(
    within(bubble).getByRole('button', { name: ACCOUNT_UPDATE_KEY_LABELS.PFK12 }),
  ).toHaveFocus();
  expect(vi.mocked(updateAccount)).not.toHaveBeenCalled();
}

/**
 * Escape withdraws the confirmation, leaves the entry standing and writes nothing.
 *
 * ⚠️ Purpose: a keyboard operator must be able to leave a question they did not mean to open, and this
 * case is what states that they can. The dismissal itself comes from two layers below the design
 * system -- `ui/node_modules/@rc-component/portal/lib/useEscKeyDown.js` keeps a stack of open portals on
 * a window `keydown` listener and withdraws the top one -- so this asserts a property of the delivered
 * screen rather than of code written here, which is precisely why it is worth asserting: nothing else
 * would notice if a library change took it away.
 *
 * ⚠️ Assumptions: the entry is asserted to SURVIVE, which is the one behaviour that distinguishes
 * Escape from the prompt's own cancel control here. That control is painted `F12=Cancel` and therefore
 * owes the reference's PF12 arm, which re-reads the record and so discards the entry; Escape carries no
 * attention identifier and no reference meaning, so it withdraws the question and nothing else. The
 * absence of a second read is asserted for the same reason -- it is what proves no cancel turn ran.
 *
 * Assumptions: what makes the distinction hold is that the portal layer's dismissal reaches the screen
 * as an open-state change alone and never as `onCancel`, so routing Escape into the cancel arm is a
 * change a reader could make and this case would catch: the entry would be re-read away and a second
 * read would be dispatched.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function escapeWithdrawsTheConfirmationAndKeepsTheEntry(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await loadAndValidate(user);
  await openTheConfirmation(user);

  await user.keyboard('{Escape}');

  await waitFor(
    /**
     * Waits for the prompt to leave the accessibility tree.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(screen.queryByRole('tooltip')).toBeNull();
    },
  );

  expect(control('firstName')).toHaveValue('GRACE');
  expect(vi.mocked(updateAccount)).not.toHaveBeenCalled();
  expect(vi.mocked(readAccountView)).toHaveBeenCalledTimes(1);
}

/**
 * Reads the save control the screen paints beside the form, which is not the legend's.
 *
 * WHY : Assumptions: the control is identified by EXCLUSION rather than by a position or a container
 *       class, because THREE controls can paint this one label at once -- the legend key, the
 *       confirmation's trigger, and the accept control inside the bubble itself -- so a plain role
 *       query resolves to whichever comes first in the document. The legend is excluded by its own
 *       accessible region and the bubble by its component class, which leaves exactly the trigger and
 *       stays true if either of the other two is moved.
 * @returns {HTMLButtonElement} The confirmation trigger beside the form.
 * @throws {Error} When the screen paints no such control, so the failure names the missing trigger
 *   rather than surfacing as an undefined dereference.
 */
function saveControlBesideTheForm(): HTMLButtonElement {
  const legend = legendRegion();
  const found = Array.from(document.body.querySelectorAll('button')).find(
    /**
     * Tests one control for the save label and for standing outside the legend and the bubble.
     * @param {HTMLButtonElement} candidate - The control under test.
     * @returns {boolean} `true` when it is the trigger beside the form.
     */
    (candidate: HTMLButtonElement): boolean =>
      collapse(candidate.textContent ?? '') === collapse(ACCOUNT_UPDATE_KEY_LABELS.PFK05) &&
      !legend.contains(candidate) &&
      candidate.closest('.ant-popconfirm') === null,
  );

  if (found === undefined) {
    throw new Error('the screen paints no save control beside the form');
  }

  return found;
}

/**
 * A concurrent change leaves the save control beside the form present, inert and unable to write.
 *
 * WHY : ⚠️ Assumptions: this is the second half of the measured defect. A browser review found
 *       `F5=Save` absent from the legend AND from the in-screen confirmation area after the 409, so
 *       asserting the legend alone would leave the other half of the disappearance unguarded -- both
 *       controls were withdrawn by one predicate and both are now painted by one predicate.
 * WHY : ⚠️ Assumptions: the write count is the claim that the inert control cannot act, and it is the
 *       claim that matters -- the point of keeping the control is that the operator sees the action
 *       exists, and the point of disabling it is that the action cannot run until an Enter turn has
 *       re-validated. One call is the refused save the arrangement itself drove, so the count is
 *       asserted rather than the absence of calls.
 * WHY : Alternatives Considered: asserting the confirmation bubble is ABSENT after the presses.
 *       Declined because the design system retains a closed popup in the document -- the arrangement
 *       has already opened and accepted one -- so an absence assertion there would pass whatever the
 *       control did and would read as a guarantee it cannot give.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aConcurrentChangeLeavesTheSaveControlInert(): Promise<void> {
  arrangeSuccessfulRead();
  const user = await renderScreen();
  await refuseTheSaveAsAConflict(user);

  await waitFor(expectLegendToPaint(ACCOUNT_UPDATE_KEY_LABELS.PFK12));

  const beside = saveControlBesideTheForm();
  expect(beside).toBeDisabled();

  await user.click(beside);
  await pressPfKey(user, 'PFK05');

  expect(vi.mocked(updateAccount)).toHaveBeenCalledTimes(1);
}

/**
 * Registers the optimistic-concurrency cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function optimisticConcurrencyCases(): void {
  it(
    'reports a concurrent change in the reference sentence',
    aConcurrentChangeIsReportedInTheReferenceSentence,
  );
  it(
    'leaves the operator entry on screen after a concurrent change',
    aConcurrentChangeLeavesTheOperatorEntryOnScreen,
  );
  it(
    'names a concurrent change on the refusal channel alone',
    aConcurrentChangeNamesItselfOnTheRefusalChannelAlone,
  );
  it(
    'leaves the cancel key advertised after a concurrent change',
    aConcurrentChangeLeavesTheCancelKeyAdvertised,
  );
  it(
    'leaves the save control beside the form inert after a concurrent change',
    aConcurrentChangeLeavesTheSaveControlInert,
  );
  it(
    'distinguishes a failed write from a concurrent change',
    aFailedWriteIsDistinguishedFromAConcurrentChange,
  );
  it(
    'refuses a turn that changed nothing without calling the service',
    aTurnThatChangedNothingIsRefusedLocally,
  );
  it(
    'reports the account lock refusal verbatim',
    aLockRefusalReachesTheMessageLine(MESSAGES.COULD_NOT_LOCK_ACCT_FOR_UPDATE.text),
  );
  it(
    'reports the customer lock refusal verbatim',
    aLockRefusalReachesTheMessageLine(MESSAGES.COULD_NOT_LOCK_CUST_FOR_UPDATE.text),
  );
  it('reports success once the write commits', aCommittedWriteReportsSuccess);
}

/**
 * Registers the field-refusal cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function fieldRefusalCases(): void {
  it('carries an error state on each refused field', eachRefusedFieldCarriesItsOwnErrorState);
  it(
    'leaves a field the service did not name unmarked',
    aFieldTheServiceDidNotNameCarriesNoErrorState,
  );
  it('marks a blank entry with the asterisk', aBlankEntryIsMarkedWithTheAsterisk);
  it('reports a refused name against its own control', aRefusedNameIsReportedAgainstItsOwnControl);
}

/**
 * Registers the data-exposure and selection-context cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function exposureCases(): void {
  it(
    'renders the protected identifiers only as markers',
    theProtectedIdentifiersRenderOnlyAsMarkers,
  );
  it(
    'renders no control beyond its declared inventory',
    theScreenRendersNoControlBeyondItsInventory,
  );
  it('keeps the cents on every amount', amountsKeepTheirCents);
  it('sends the account key with every request', theAccountKeyTravelsWithEveryRequest);
}

/**
 * The phone width the responsive review measured this screen overflowing at.
 *
 * WHY : Assumptions: 375 is the narrowest width in the review's own sweep and the one where the
 *       overflow was largest relative to the viewport -- `documentElement.scrollWidth` 385 against an
 *       `innerWidth` of 375. The suite's `matchMedia` shim derives every breakpoint answer from
 *       `window.innerWidth`, so assigning it is how a case selects the narrow branch.
 */
const PHONE_VIEWPORT_WIDTH = 375;

/**
 * The declared width of the surname field, read from the screen's own transcription.
 *
 * WHY : Assumptions: the width is READ rather than restated, because this file already asserts that
 *       transcription against `app/cpy-bms/COACTUP.CPY` field by field -- so reading it here makes the
 *       clamp cases depend on one checked source instead of on a second copy of the same number.
 */
const LAST_NAME_DECLARED_WIDTH = ACCOUNT_UPDATE_FIELD_WIDTHS.lastName;

/** A letter that is one code point and two UTF-8 bytes, so the two measures disagree on it. */
const ACCENTED_LETTER = '\u00c9';

/** How many bytes {@link ACCENTED_LETTER} occupies once encoded. */
const ACCENTED_LETTER_BYTES = 2;

/** A character outside the basic multilingual plane: one code point, two UTF-16 units, four bytes. */
const ASTRAL_CHARACTER = '\u{1f600}';

/**
 * A width at which the mapset's paired columns render side by side.
 *
 * WHY : Assumptions: 1024 is jsdom's own default `innerWidth`, so the wide case asserts the branch
 *       every other case in this file already renders under rather than a width chosen for it.
 */
const DESKTOP_VIEWPORT_WIDTH = 1024;

/**
 * Reads every grid row the screen lays its fields out in.
 *
 * WHY : Assumptions: the query is the design system's own row class and is NOT narrowed to the two
 *       field grids, because the claim is about the whole rendered tree: no row anywhere on this screen
 *       may hang past its container. The design system also renders each `Form.Item` as a row, so this
 *       returns those too and they are part of what the claim covers.
 * @returns {readonly HTMLElement[]} Every rendered row element.
 * @throws {Error} If the screen rendered no row at all, which would make the assertion vacuous.
 */
function gridRows(): readonly HTMLElement[] {
  const rows = Array.from(document.querySelectorAll<HTMLElement>('.ant-row'));
  if (rows.length === 0) {
    throw new Error('the screen rendered no grid row, so the gutter claim would assert nothing');
  }
  return rows;
}

/**
 * Reads the inline margins the design system derived from a row's resolved gutter.
 * @param {readonly HTMLElement[]} rows - Rows to read.
 * @returns {readonly string[]} Each row's inline-axis margin, empty where it set none.
 */
function inlineMarginsOf(rows: readonly HTMLElement[]): readonly string[] {
  return rows.map(
    /**
     * Reads one row's inline-axis margin.
     * @param {HTMLElement} row - The row element.
     * @returns {string} Its `margin-inline` value, or the empty string when unset.
     */
    (row: HTMLElement): string => row.style.marginInline,
  );
}

/**
 * At a phone width no grid row hangs outside the column it is laid out in.
 *
 * WHY : Assumptions: the assertion is on the NEGATIVE inline margin rather than on a measured
 *       `scrollWidth`, because jsdom performs no layout and reports every box as zero -- so the only
 *       honest thing to assert here is the property that produced the measured overflow in a real
 *       browser. `node_modules/antd/lib/grid/row.js` sets `marginInline` to `gutter / -2` and skips the
 *       declaration entirely when the resolved gutter is falsy, so an absent margin is the resolved
 *       zero and a present one would be the escape.
 * @returns {Promise<void>} Resolves once the narrow rendering has been asserted.
 */
async function noGridRowHangsOutsideItsColumnAtAPhoneWidth(): Promise<void> {
  window.innerWidth = PHONE_VIEWPORT_WIDTH;

  await renderScreen();
  await informationBand();

  expect(inlineMarginsOf(gridRows())).not.toContain(expect.stringContaining('-'));
}

/**
 * From the medium breakpoint upward the paired columns keep their gutter.
 *
 * WHY : Assumptions: this half is asserted alongside the narrow one because the remedy could be passed
 *       by deleting the gutter outright, which would close the two columns up against each other at
 *       every width. Both halves together pin the gutter to the widths that have two columns to
 *       separate.
 * @returns {Promise<void>} Resolves once the wide rendering has been asserted.
 */
async function thePairedColumnsKeepTheirGutterFromTheMediumBreakpoint(): Promise<void> {
  window.innerWidth = DESKTOP_VIEWPORT_WIDTH;

  await renderScreen();
  await informationBand();

  expect(inlineMarginsOf(gridRows())).toContain('-10px');
}

/**
 * The resolver states no horizontal gutter below the medium breakpoint and the spacing step above it.
 *
 * WHY : Assumptions: the resolver is exercised directly as well as through the rendering, because the
 *       rendered assertion can only observe the ONE branch the current viewport selects. Reading both
 *       branches from the returned value is what proves the other one exists.
 * @returns {void} Nothing; the assertions run for their effect.
 */
function theGutterResolverSuppressesTheHorizontalGutterBelowTheBreakpoint(): void {
  const [horizontal, vertical] = accountUpdateGridGutter(20) as [Record<string, number>, number];

  expect(horizontal).toStrictEqual({ xs: 0, sm: 0, md: 20 });
  expect(vertical).toBe(20);
}

/**
 * Returns the viewport to jsdom's own default width.
 * @returns {void} Nothing; `window.innerWidth` is reassigned as a side effect.
 */
function restoreTheDefaultViewport(): void {
  window.innerWidth = DESKTOP_VIEWPORT_WIDTH;
}

/**
 * Registers the responsive-grid cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function responsiveGridCases(): void {
  /*
   * WHY : Assumptions: the viewport is restored after every case in this group, and only in this group.
   *       The suite's `matchMedia` shim answers from `window.innerWidth`, which is a global -- so a case
   *       that assigns a phone width and leaves it there would silently move every LATER case in the
   *       file onto the narrow branch, and a failure would surface far from the assignment that caused
   *       it. jsdom's own default is restored rather than a value spelled twice.
   */
  afterEach(restoreTheDefaultViewport);

  it(
    'hangs no grid row outside its column at a phone width',
    noGridRowHangsOutsideItsColumnAtAPhoneWidth,
  );
  it(
    'keeps the gutter where the columns are paired',
    thePairedColumnsKeepTheirGutterFromTheMediumBreakpoint,
  );
  it(
    'suppresses the horizontal gutter below the medium breakpoint',
    theGutterResolverSuppressesTheHorizontalGutterBelowTheBreakpoint,
  );
}

describe('AccountUpdateScreen responsive grid', responsiveGridCases);
describe('AccountUpdateScreen field constraints', fieldConstraintCases);
describe('AccountUpdateScreen split field groups', splitGroupCases);
describe('AccountUpdateScreen design-system composition', compositionCases);
describe('AccountUpdateScreen message catalogue fidelity', catalogueFidelityCases);
describe('AccountUpdateScreen attention identifiers', attentionIdentifierCases);
describe('AccountUpdateScreen save confirmation', confirmationCases);
describe('AccountUpdateScreen optimistic concurrency', optimisticConcurrencyCases);
describe('AccountUpdateScreen field refusals', fieldRefusalCases);
describe('AccountUpdateScreen data exposure and selection context', exposureCases);
