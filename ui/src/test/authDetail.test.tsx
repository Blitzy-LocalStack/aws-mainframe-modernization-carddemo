/**
 * @file Proves the pending-authorization detail screen against the artifacts that specify it: the
 * `COPAU01` symbolic map's declared field widths, the ten-entry decline-reason table `COPAUS1C`
 * declares, the four attention identifiers it binds, the fraud transition its fifth key submits, and
 * the exposure and session rules the migration plan imposes on the route it is mounted at.
 *
 * Purpose
 * -------
 * `ui/src/screens/authDetail/index.tsx` replaces
 * `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` and its mapset
 * `app/app-authorization-ims-db2-mq/bms/COPAU01.bms`, mounted by `ui/src/router.tsx` at
 * `/authorizations/:key`. `tests/README.md` §1.1 states the position this file is written from
 * verbatim: "Online `CO*` CICS programs cannot run end-to-end without a CICS runtime (absent on the
 * runner); only their extractable field-validation logic is unit-tested." This screen is worse served
 * than the base screens, because its extension tree additionally needs IMS and Db2, which the runner
 * also lacks -- so **no golden master exists for `COPAUS1C` at all**, and no COBOL layer of the parity
 * suite covers it. The user's acceptance criteria nonetheless name "the three MQ extensions" among the
 * flows that must work end to end, so these cases and the sibling file named below are the only
 * front-end evidence for the fraud-marking half of one of them. Nothing here can be deferred to a
 * comparison against a recorded run, because there is no recorded run.
 *
 * Module parameters, returns and exceptions
 * ----------------------------------------
 * A test module takes no parameters and returns no value, so Rule 1's Parameters and Return clauses are
 * satisfied at the module level by their analogues, stated once here. What this module takes is the
 * frozen baseline it cites -- the mapset, the program, the two authorization copybooks and the message
 * catalog -- together with the harness in `./setup`, which supplies the render path, the operator, the
 * key press, the identity and the problem-document builders. What it returns is a verdict per case,
 * reported by Vitest. What it raises is an assertion failure and nothing else: no case writes to disk,
 * reaches a network, or reads a credential, and `vi.mock` replaces the whole transport module so no
 * request is composed at all.
 *
 * Relationship to the sibling screen file
 * --------------------------------------
 * `ui/src/screens/authDetailScreen.test.tsx` covers this screen from the other side -- it renders with
 * Testing Library's own `render` and asserts the read, the two fraud directions, the stale-target
 * refusal and the dead end. These cases deliberately do NOT restate those. They take the artifacts as
 * the subject: the widths the mapset declares, the codes the table declares, the register of
 * diagnostics the target withholds, the emphasis and the legend the design-system mapping fixes, and
 * the route's own access class. The two files are complementary by construction, and a duplicate case
 * here would cost a second maintenance site for one guarantee.
 *
 * Rule 1, and the house convention it extends
 * ------------------------------------------
 * User Rule 1 (Explainability) requires a docstring on every function stating purpose, parameters,
 * returns and exceptions, and an inline comment justifying every non-obvious decision under one of
 * Alternatives Considered, Refactoring Rationale, Assumptions or Trade-offs. `tests/README.md` §12
 * imposes the identical obligation on "every new test, fixture builder, helper, mock, and runner
 * routine" and calls it "a hard review gate". The two agree completely, so this file extends an
 * established house convention rather than importing a new one, and it is written to
 * `docs/CODE_DOCUMENTATION_STANDARD.md`.
 *
 * Assumptions: the four rationale labels are written in the PLURAL canonical form the repository
 * enforces -- `config/rule1/rule1_gate.py` declares exactly `Assumptions`, `Trade-offs`,
 * `Alternatives Considered` and `Refactoring Rationale`, rejects the singular stems as a `singular`
 * violation, and runs as a required step in `.github/workflows/services-ci.yml` and `infra-ci.yml`.
 * Rule 1's own text names them in the same plural form. The singular spelling would add gate findings
 * to a tree that has none of that class.
 *
 * Assumptions: no comment below opens with the token that gate calls a statement-level restatement of
 * the code beneath it, which the same gate permits only inside a file's leading header block. Every
 * inline note here is therefore a `WHY :` carrying one of the four labels, which is the form every
 * module in `ui/src` already uses.
 *
 * Assumptions: `describe`, `it`, `expect` and `vi` are IMPORTED from Vitest rather than taken as
 * globals, even though `ui/vitest.config.ts` sets `globals: true`. `ui/tsconfig.json` sets
 * `compilerOptions.types` to an empty array and records that this "obliges a test to import
 * `describe`, `it` and `expect` from 'vitest' by name"; nothing in the package references
 * `vitest/globals`, so the ambient declarations do not exist and an omitted import fails
 * `npm run typecheck` on the symbol it omitted. Every other test in this package imports them.
 */

import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import {
  getNextPendingAuthorization,
  getPendingAuthorizationScreen,
  listPendingAuthorizations,
  setAuthorizationFraudState,
} from '../api/authorization';
import { ApiRequestError, claimRetainedOutcome } from '../api/client';
import type { RetainedOutcome } from '../api/client';
import { MONEY_PICTURES, applyMoneyEditMask } from '../format/money';
import type {
  AuthFraudFlag,
  FraudAction,
  FraudMarkResponse,
  MatchStatus,
  PendingAuthDetail,
  PendingAuthDetailScreen,
} from '../api/types';
import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP } from '../hooks/useAuth';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import {
  PF_KEY_BAR_REGION_LABEL,
  PRIMARY_ACTION_AIDS,
  UNIFORM_PF_KEY_LABELS,
  decodeBmsLegendText,
} from '../layout/PfKeyBar';
import type { CicsAid } from '../layout/usePfKeys';
import {
  COMMON_MESSAGES,
  INVALID_KEY_PRESSED,
  PROGRAM_MESSAGES,
  PROGRAM_MESSAGE_SOURCES,
  PROGRAM_SOURCE_FILES,
  REDACTED_DIAGNOSTICS,
  REQUEST_IN_PROGRESS,
  TRANSIENT_FAILURE_TRY_AGAIN,
  UNEXPECTED_ABEND_OCCURRED,
  normaliseMessageBandValue,
} from '../messages/messages';
import { AUTH_DETAIL_PATH, AUTH_SUMMARY_PATH, ROUTE_TABLE } from '../router';
import AuthDetailScreen, {
  AUTHORIZATION_DETAIL_ROUTE,
  AUTHORIZATION_SUMMARY_ROUTE,
  AUTH_DETAIL_FIELD_LABELS,
  AUTH_DETAIL_KEY_LABELS,
  AUTH_DETAIL_MERCHANT_HEADING,
  AUTH_DETAIL_SUBTITLE,
  FRAUD_CONFIRMATION_RECORD_ID,
  FRAUD_REPORTED,
  FRAUD_WITHDRAWN,
  nextFraudAction,
} from '../screens/authDetail';
import type { FraudTransitionHandover } from '../screens/authDetail';
import { BMS_TEXT_COLOR_TOKENS, FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';

import {
  apiError,
  expectVerbatimMessage,
  fieldError,
  pressPfKey,
  renderInAppShell,
  seedSession,
  shellLandmark,
} from './setup';
import type { HarnessRenderResult } from './setup';

/**
 * Builds the mocked surface of the authorization transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts
 * every `vi.mock` call above the imports and a factory held in a `const` is not initialised by then.
 * This is the same shape `ui/src/screens/authDetailScreen.test.tsx` uses.
 *
 * Assumptions: the paging operation is mocked ALONGSIDE the three this screen calls, and it is
 * deliberately not omitted. One case asserts that the eighth key steps to the next RECORD and invokes
 * no paging helper, and an unmocked export cannot be asserted absent -- a spy that does not exist
 * cannot report zero calls.
 *
 * Alternatives Considered: Mock Service Worker, which would let the real client compose real requests.
 * Rejected because `msw` is absent from `ui/package.json`, and adding a dependency to a test would put
 * a transport in front of assertions whose whole subject is which function the screen called with what.
 * @returns {Record<string, unknown>} The four transport functions, each a fresh spy.
 */
function mockAuthorizationTransportModule(): Record<string, unknown> {
  return {
    getPendingAuthorizationScreen: vi.fn(),
    getNextPendingAuthorization: vi.fn(),
    setAuthorizationFraudState: vi.fn(),
    listPendingAuthorizations: vi.fn(),
  };
}

vi.mock('../api/authorization', mockAuthorizationTransportModule);

/** The three sentences this program paints, from the single catalog that owns them. */
const DETAIL_MESSAGES = PROGRAM_MESSAGES.COPAUS1C;

/** Baseline lines each of those three sentences is written at, from the same catalog. */
const DETAIL_MESSAGE_LINES = PROGRAM_MESSAGE_SOURCES.COPAUS1C;

/*
 * WHY : Assumptions: the account identifier, the authorization date and the authorization time are
 *       declared separately and then composed into one selector, because the property under test is
 *       that the WHOLE composite travels. `app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy` L19 to
 *       L21 declares `PA-AUTHORIZATION-KEY` as two packed fields -- `PA-AUTH-DATE-9C PIC S9(05)
 *       COMP-3` and `PA-AUTH-TIME-9C PIC S9(09) COMP-3` -- beneath an account, which is the composite
 *       primary key the migration plan carries into `authorization.pending_auth_detail`. A case that
 *       used one opaque blob could not tell a screen that sent the whole key from one that sent the
 *       account identifier alone.
 * WHY : Trade-offs: a real selector is OPAQUE and sealed by the service, and this synthetic one is
 *       readable. That is accepted because `ui/src/api/authorization.ts` records that the token is
 *       passed back verbatim and never parsed, so its shape is immaterial to the screen -- while a
 *       readable one lets the assertion below name exactly which component would be missing from a
 *       truncated value. It is deliberately URL-safe, because the eighth key composes the next address
 *       with `encodeURIComponent` and a value needing escaping would test the encoder rather than the
 *       screen.
 */

/** Account the authorization under review hangs beneath. */
const SELECTED_ACCOUNT_ID = '00000000011';

/** Date component of the composite key, as the packed `PA-AUTH-DATE-9C` field carries it. */
const SELECTED_AUTH_DATE = '25002';

/** Time component of the composite key, as the packed `PA-AUTH-TIME-9C` field carries it. */
const SELECTED_AUTH_TIME = '036672000';

/** Synthetic sealed selector carrying all three components of the composite key. */
const SELECTOR = `${SELECTED_ACCOUNT_ID}-${SELECTED_AUTH_DATE}-${SELECTED_AUTH_TIME}-synthetic`;

/** Synthetic sealed selector of the authorization the eighth key steps to. */
const NEXT_SELECTOR = `${SELECTED_ACCOUNT_ID}-${SELECTED_AUTH_DATE}-036673000-synthetic`;

/** Concrete address the route opens at. */
const DETAIL_ENTRY = `/authorizations/${SELECTOR}`;

/*
 * WHY : Assumptions: the card number in every fixture below is the MASKED rendering, twelve asterisks
 *       and the last four digits. `ui/src/api/authorization.ts` refuses an unmasked value on arrival
 *       for both the member and the screen projection, and the migration plan grants the unmasked
 *       exception only to the administrative card-detail endpoint, which this screen is not. A fixture
 *       carrying sixteen digits would describe a response the client rejects.
 */

/** The masked rendering every non-administrative read of this card returns. */
const MASKED_CARD = '************0011';

/** Last four digits the mask preserves, which is the only part of the number that may render. */
const CARD_LAST_FOUR = '0011';

/** Sixteen-digit number the mask hides, held only so a case can assert it never reaches the glass. */
const UNMASKED_CARD_NEVER_RENDERED = '4111111111110011';

/**
 * One rendered authorization, as the screen projection delivers it.
 *
 * Assumptions: every money member is a `string` and none is a `number`, which the contract fixes and
 * this fixture therefore cannot weaken. `PA-APPROVED-AMT` is `PIC S9(10)V99 COMP-3` at
 * `cpy/CIPAUDTY.cpy` L35 -- packed decimal, exact to the cent -- and the authorization context is the
 * only place packed decimal reaches persisted data in the target at all.
 *
 * Assumptions: `matchStatus` is typed `MatchStatus` and `fraudMark` carries the composed tag rather
 * than a bare flag, both because the contract declares them so: the four match statuses are the
 * condition names at `cpy/CIPAUDTY.cpy` L46 to L49 and the fraud tag is composed at
 * `cbl/COPAUS1C.cbl` L344 to L350 as the flag, a hyphen and the report date, or a lone hyphen when the
 * row carries no mark.
 *
 * Assumptions: `authResponse` is the single approval character and not the stored two-character code.
 * `cbl/COPAUS1C.cbl` L311 tests the stored code and moves `'A'` at L312 or `'D'` at L315, so the code
 * never reaches a browser.
 */
const DETAIL: PendingAuthDetailScreen = {
  transactionName: 'CPVD',
  title01: 'AWS Mainframe Modernization',
  currentDate: '01/02/25',
  programName: 'COPAUS1C',
  title02: 'CardDemo',
  currentTime: '10:11:12',
  cardNumber: MASKED_CARD,
  authDate: '01/02/25',
  authTime: '10:11:12',
  authResponse: 'A',
  authResponseReason: '0000-APPROVED',
  processingCode: '000000',
  approvedAmount: '12345678.90',
  posEntryMode: '01',
  messageSource: 'POS',
  merchantCategoryCode: '5411',
  cardExpiry: '01/27',
  authType: 'AUTHORIZATN',
  transactionId: 'TRAN00000000001',
  matchStatus: 'P',
  fraudMark: '-',
  merchantName: 'EXAMPLE GROCER OF TOWN',
  merchantId: 'MERCH000000001',
  merchantCity: 'EXAMPLE CITY',
  merchantState: 'TX',
  merchantZip: '75001-000',
  message: null,
};

/**
 * The same authorization once a reviewer has reported it as fraud.
 *
 * Assumptions: the mark is the COMPOSED form and not the bare flag, so the transition the screen
 * derives from it is the one the reference derives. `nextFraudAction` reads position one, which is the
 * flag `cbl/COPAUS1C.cbl` L345 writes before the separator and the report date, so a bare `'F'` and
 * `'F-01/02/25'` must both resolve to a withdrawal.
 */
const REPORTED_DETAIL: PendingAuthDetailScreen = {
  ...DETAIL,
  fraudMark: `${FRAUD_REPORTED}-01/02/25`,
};

/**
 * The same authorization carrying an amount wider than the mapset's own display field.
 *
 * Assumptions: thirteen characters against a `LENGTH=12` field, which is a value the terminal could
 * not have shown in full. It exists to pin a registered divergence rather than to describe a normal
 * reading, and the case that uses it records why the divergence is taken.
 */
const LARGE_AMOUNT_DETAIL: PendingAuthDetailScreen = {
  ...DETAIL,
  approvedAmount: '1234567890.12',
};

/**
 * One field the mapset paints a value into, paired with what the contract calls it.
 *
 * Assumptions: the width is the mapset's `LENGTH=` operand and the citation is its `DFHMDF` line, so
 * each entry is a citation rather than a chosen number. The 3270 enforced a field's width in hardware
 * and this is where that constraint survives on a display-only screen: not as a `maxLength`, because
 * there is nothing to type into, but as a bound on what the service may render into the slot.
 *
 * Assumptions: `member` is keyed to `PendingAuthDetailScreen` rather than left a free string, so a
 * contract member renamed on one side fails the compiler here instead of silently asserting nothing.
 */
interface DeclaredField {
  /** Contract member the screen renders into this slot. */
  readonly member: keyof PendingAuthDetailScreen;
  /** The mapset's own field name, for the reader comparing this table against the source. */
  readonly mapsetField: string;
  /** One-based `DFHMDF` line of that field in `app/app-authorization-ims-db2-mq/bms/COPAU01.bms`. */
  readonly mapsetLine: number;
  /** The field's `LENGTH=` operand, which is the width the terminal enforced. */
  readonly declaredWidth: number;
  /** The mapset's painted label for the slot, or `null` for the six unlabelled header fields. */
  readonly label: string | null;
}

/*
 * WHY : Assumptions: TWENTY entries and not eighteen. The mapset paints twenty named value fields
 *       across the two blocks this screen renders, counted from its own `DFHMDF` definitions, and the
 *       two easiest to miss are the last two -- `MERST` at L269 and `MERZIP` at L279. The screen's own
 *       `AUTH_DETAIL_FIELD_LABELS` records the same count and records that its description once said
 *       eighteen while a rendering was in fact missing a field, so an undercount here is a defect this
 *       screen has already had.
 * WHY : Trade-offs: the widths are the MAPSET's and are in three places wider than the copybook field
 *       behind them -- `MERZIP` is `LENGTH=10` above `PA-MERCHANT-ZIP PIC X(09)`, `MERNAME` is
 *       `LENGTH=25` above `PIC X(22)`, `MERCITY` is `LENGTH=25` above `PIC X(13)`. The mapset is the
 *       right authority for a rendering bound because it is what the operator's screen enforced; the
 *       copybook bound belongs to the ETL and the schema, which assert it where the record is decoded.
 */

/** The twenty value fields of the two record blocks, in the order the mapset paints them. */
const DECLARED_RECORD_FIELDS: readonly DeclaredField[] = [
  {
    member: 'cardNumber',
    mapsetField: 'CARDNUM',
    mapsetLine: 85,
    declaredWidth: 16,
    label: AUTH_DETAIL_FIELD_LABELS.cardNumber,
  },
  {
    member: 'authDate',
    mapsetField: 'AUTHDT',
    mapsetLine: 94,
    declaredWidth: 10,
    label: AUTH_DETAIL_FIELD_LABELS.authDate,
  },
  {
    member: 'authTime',
    mapsetField: 'AUTHTM',
    mapsetLine: 104,
    declaredWidth: 10,
    label: AUTH_DETAIL_FIELD_LABELS.authTime,
  },
  {
    member: 'authResponse',
    mapsetField: 'AUTHRSP',
    mapsetLine: 114,
    declaredWidth: 1,
    label: AUTH_DETAIL_FIELD_LABELS.authResponse,
  },
  {
    member: 'authResponseReason',
    mapsetField: 'AUTHRSN',
    mapsetLine: 124,
    declaredWidth: 20,
    label: AUTH_DETAIL_FIELD_LABELS.responseReason,
  },
  {
    member: 'processingCode',
    mapsetField: 'AUTHCD',
    mapsetLine: 134,
    declaredWidth: 6,
    label: AUTH_DETAIL_FIELD_LABELS.authCode,
  },
  {
    member: 'approvedAmount',
    mapsetField: 'AUTHAMT',
    mapsetLine: 144,
    declaredWidth: 12,
    label: AUTH_DETAIL_FIELD_LABELS.amount,
  },
  {
    member: 'posEntryMode',
    mapsetField: 'POSEMD',
    mapsetLine: 154,
    declaredWidth: 4,
    label: AUTH_DETAIL_FIELD_LABELS.posEntryMode,
  },
  {
    member: 'messageSource',
    mapsetField: 'AUTHSRC',
    mapsetLine: 164,
    declaredWidth: 10,
    label: AUTH_DETAIL_FIELD_LABELS.source,
  },
  {
    member: 'merchantCategoryCode',
    mapsetField: 'MCCCD',
    mapsetLine: 174,
    declaredWidth: 4,
    label: AUTH_DETAIL_FIELD_LABELS.merchantCategoryCode,
  },
  {
    member: 'cardExpiry',
    mapsetField: 'CRDEXP',
    mapsetLine: 184,
    declaredWidth: 5,
    label: AUTH_DETAIL_FIELD_LABELS.cardExpiryDate,
  },
  {
    member: 'authType',
    mapsetField: 'AUTHTYP',
    mapsetLine: 194,
    declaredWidth: 14,
    label: AUTH_DETAIL_FIELD_LABELS.authType,
  },
  {
    member: 'transactionId',
    mapsetField: 'TRNID',
    mapsetLine: 204,
    declaredWidth: 15,
    label: AUTH_DETAIL_FIELD_LABELS.transactionId,
  },
  {
    member: 'matchStatus',
    mapsetField: 'AUTHMTC',
    mapsetLine: 214,
    declaredWidth: 1,
    label: AUTH_DETAIL_FIELD_LABELS.matchStatus,
  },
  {
    member: 'fraudMark',
    mapsetField: 'AUTHFRD',
    mapsetLine: 224,
    declaredWidth: 10,
    label: AUTH_DETAIL_FIELD_LABELS.fraudStatus,
  },
  {
    member: 'merchantName',
    mapsetField: 'MERNAME',
    mapsetLine: 239,
    declaredWidth: 25,
    label: AUTH_DETAIL_FIELD_LABELS.merchantName,
  },
  {
    member: 'merchantId',
    mapsetField: 'MERID',
    mapsetLine: 249,
    declaredWidth: 15,
    label: AUTH_DETAIL_FIELD_LABELS.merchantId,
  },
  {
    member: 'merchantCity',
    mapsetField: 'MERCITY',
    mapsetLine: 259,
    declaredWidth: 25,
    label: AUTH_DETAIL_FIELD_LABELS.merchantCity,
  },
  {
    member: 'merchantState',
    mapsetField: 'MERST',
    mapsetLine: 269,
    declaredWidth: 2,
    label: AUTH_DETAIL_FIELD_LABELS.merchantState,
  },
  {
    member: 'merchantZip',
    mapsetField: 'MERZIP',
    mapsetLine: 279,
    declaredWidth: 10,
    label: AUTH_DETAIL_FIELD_LABELS.merchantZip,
  },
];

/**
 * The six unlabelled header-band fields the mapset paints on rows 1 and 2.
 *
 * Assumptions: these carry no label because the mapset paints none -- the row-1 and row-2 fields sit
 * beside static captions the shell owns -- so `label` is `null` for all six and the case asserting
 * them checks widths and the two the screen delegates, not labels.
 */
const DECLARED_HEADER_FIELDS: readonly DeclaredField[] = [
  {
    member: 'transactionName',
    mapsetField: 'TRNNAME',
    mapsetLine: 34,
    declaredWidth: 4,
    label: null,
  },
  { member: 'title01', mapsetField: 'TITLE01', mapsetLine: 38, declaredWidth: 40, label: null },
  { member: 'currentDate', mapsetField: 'CURDATE', mapsetLine: 47, declaredWidth: 8, label: null },
  { member: 'programName', mapsetField: 'PGMNAME', mapsetLine: 57, declaredWidth: 8, label: null },
  { member: 'title02', mapsetField: 'TITLE02', mapsetLine: 61, declaredWidth: 40, label: null },
  { member: 'currentTime', mapsetField: 'CURTIME', mapsetLine: 70, declaredWidth: 8, label: null },
];

/**
 * One entry of the decline-reason table `COPAUS1C` declares.
 *
 * Assumptions: the code and the description are held SEPARATELY here while the baseline stores them
 * concatenated in one `PIC X(20) VALUE` clause, because the split is the property under test. The
 * redefinition at `cbl/COPAUS1C.cbl` L68 to L73 fixes where it falls: `DECL-CODE PIC X(4)` then
 * `DECL-DESC PIC X(16)`, so a target that splits anywhere but at four characters produces a different
 * code and a different description from the same literal.
 */
interface DeclineReason {
  /** One-based line of the `VALUE` clause in `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl`. */
  readonly line: number;
  /** The four-character reason code, which is `DECL-CODE`. */
  readonly code: string;
  /** The description, which is `DECL-DESC` at its content length rather than space-padded. */
  readonly description: string;
}

/*
 * WHY : Assumptions: this table is an INDEPENDENT transcription of the baseline's `VALUE` clauses and
 *       is emphatically NOT a client-side copy of the lookup. `ui/src/api/authorization.ts` records
 *       that the composed reason arrives already resolved and that "a client copy of that table would
 *       be a second definition of one lookup, and the two would drift the first time a reason code was
 *       added on one side only". The service owns the lookup, in
 *       `services/authorization-service/src/main/java/com/carddemo/authorization/service/PendingAuthDetailService.java`,
 *       and asserts it against its own transcription. What lives here is a TEST EXPECTATION about what
 *       the browser must render when the service sends each composed form -- it is read by no
 *       production module and is imported by nothing.
 * WHY : Assumptions: TEN entries and not nine. `OCCURS 10 TIMES` at L69 states the arity, and the
 *       tenth clause is `'9000UNKNOWN'` at L67 -- the code the reference resolves for a reason it has
 *       no entry for, distinct from the `'9999'`/`'ERROR'` pair the `AT END` arm at L320 to L323
 *       composes when the search itself fails. Stopping at nine would leave the one entry that exists
 *       for the unknown case out of the set that proves the unknown case renders.
 * WHY : Trade-offs: the two misspelled descriptions are transcribed EXACTLY -- `'INSUFFICNT FUND'` at
 *       L60, missing the second `E` of "INSUFFICIENT" and singular where "FUNDS" would be idiomatic,
 *       and `'EXCED DAILY LMT'` at L63, missing an `E` from "EXCEED" and abbreviating "LIMIT" without
 *       punctuation. Transformation rule T8 carries user-visible strings across character for
 *       character, so both are deliberate preservations rather than typos to correct, and correcting
 *       either would change what an operator has read for the life of the system.
 * WHY : Assumptions: that preservation sits beside the OPPOSITE treatment of a misspelling one file
 *       away, and the difference is the whole rule. The migration plan corrects exactly three baseline
 *       misspellings -- `ACCT-EXPIRAION-DATE`, `CARD-EXPIRAION-DATE` and
 *       `PA-MERCHANT-CATAGORY-CODE` -- and corrects them only in IDENTIFIERS: column names and
 *       contract members. Identifiers are code; these descriptions are text a human reads. An author
 *       who "fixed" `INSUFFICNT` would be applying the identifier rule to a display string.
 */

/** The ten entries of `WS-DECLINE-REASON-TABLE`, from `COPAUS1C.cbl` L58 to L67. */
const DECLINE_REASONS: readonly DeclineReason[] = [
  { line: 58, code: '0000', description: 'APPROVED' },
  { line: 59, code: '3100', description: 'INVALID CARD' },
  { line: 60, code: '4100', description: 'INSUFFICNT FUND' },
  { line: 61, code: '4200', description: 'CARD NOT ACTIVE' },
  { line: 62, code: '4300', description: 'ACCOUNT CLOSED' },
  { line: 63, code: '4400', description: 'EXCED DAILY LMT' },
  { line: 64, code: '5100', description: 'CARD FRAUD' },
  { line: 65, code: '5200', description: 'MERCHANT FRAUD' },
  { line: 66, code: '5300', description: 'LOST CARD' },
  { line: 67, code: '9000', description: 'UNKNOWN' },
];

/** Width `DECL-CODE` declares at `COPAUS1C.cbl` L72, which is where the composed form splits. */
const REASON_CODE_WIDTH = 4;

/** Width `DECL-DESC` declares at `COPAUS1C.cbl` L73. */
const REASON_DESCRIPTION_WIDTH = 16;

/** Separator L322 and L326 move into position five of the composed reason. */
const REASON_SEPARATOR = '-';

/** Separator the mapset uses between the three labels of its row-24 legend. */
const LEGEND_SEPARATOR = '  ';

/*
 * WHY : ⚠️ Assumptions: the invalid-key sentence is compared against the catalog value put through the
 *       band's OWN normaliser rather than against the catalog value directly, and the difference is the
 *       declared-width padding. `app/cpy/CSMSG01Y.cpy` L20 to L21 declares `CCDA-MSG-INVALID-KEY` as
 *       `PIC X(50)` and the catalog carries the padding as content, because the padding is part of the
 *       declared value; `ui/src/layout/MessageBand.tsx` then renders the value through
 *       `normaliseMessageBandValue`, which strips the low-values sentinel and the boundary whitespace,
 *       so the DOM never holds the trailing spaces. Deriving the expectation through the same function
 *       is what lets this file assert the rendered text without trimming anything itself -- the
 *       normalisation is the band's decision and is asserted as the band's, not repeated here.
 */

/** The invalid-key sentence as the band renders it, normalised exactly as the band normalises it. */
const INVALID_KEY_AS_RENDERED = normaliseMessageBandValue(INVALID_KEY_PRESSED);

/**
 * Length of the row-24 legend's `INITIAL=` operand at `COPAU01.bms` L292, measured from the source.
 *
 * Assumptions: forty-four characters inside a `LENGTH=45` field, so the operand leaves one pad column
 * on the grid. Both numbers are recorded because they answer different questions: the operand's length
 * is what the three labels plus their separators plus the grid offset must reconstruct, and the field's
 * length is the slot the terminal reserved.
 */
const LEGEND_OPERAND_LENGTH = 44;

/** The `LENGTH=` operand of that legend field, one column wider than the text it holds. */
const LEGEND_FIELD_WIDTH = 45;

/**
 * Converts a design-token name into the CSS custom property antd 6 emits for it.
 *
 * Purpose
 * -------
 * `ui/src/theme/tokens.ts` exports token NAMES, never values, and the screen writes
 * `cssVar[tokenName]` into a style attribute -- which reaches the DOM as `var(--ant-…)`. An assertion
 * therefore needs the variable name, and deriving it from the exported token constant is what keeps
 * this file free of a literal font, colour or spacing value: change the token in the bridge and this
 * assertion follows it.
 *
 * Assumptions: the emitted name is the token's camel case lowered to kebab case under an `--ant-`
 * prefix, which was measured against the pinned version rather than read from documentation --
 * `colorPrimaryTextActive` renders as `--ant-color-primary-text-active` and `fontFamilyCode` as
 * `--ant-font-family-code` in this screen's own output.
 *
 * Alternatives Considered: asserting the style attribute contains the token NAME. Rejected because it
 * would pass against a screen that wrote the resolved value instead of the variable, which is the
 * exact regression `PfKeyBar` records as defeating the CSS-variable theme surface.
 * @param {string} tokenName - The token name, taken from an exported constant and never retyped.
 * @returns {string} The CSS custom property name antd emits for that token.
 */
function antdCssVariable(tokenName: string): string {
  /**
   * Lowers one upper-case letter into a hyphen-prefixed lower-case segment.
   * @param {string} letter - The matched upper-case character.
   * @returns {string} That character in lower case, preceded by a hyphen.
   */
  function toKebabSegment(letter: string): string {
    return `-${letter.toLowerCase()}`;
  }

  return `--ant-${tokenName.replace(/[A-Z]/gu, toKebabSegment)}`;
}

/**
 * Builds the composed reason string the service sends for one table entry.
 *
 * Assumptions: the composition is the reference's own, taken from `cbl/COPAUS1C.cbl` L324 to L327 --
 * the four-character code into positions one to four, the separator into position five, the
 * description from position six. It is built here rather than written out per row so that the ten
 * expectations cannot disagree about the shape while agreeing about the content.
 * @param {DeclineReason} reason - The table entry to compose.
 * @returns {string} The composed reason, at content length rather than padded to the declared twenty.
 */
function composedReason(reason: DeclineReason): string {
  return `${reason.code}${REASON_SEPARATOR}${reason.description}`;
}

/**
 * Arranges one read and renders the screen inside the application shell at a detail address.
 *
 * Purpose
 * -------
 * The screen delegates its title band, its row-23 message line and its row-24 legend to the single
 * `AppShell` that `ui/src/App.tsx` mounts, so a bare mount would leave all three rendered by nothing
 * and every band and legend assertion below would find no element. `renderInAppShell` reproduces the
 * layout-route arrangement the router builds, filling the shell's outlet the way a real screen does.
 *
 * Assumptions: the route PATTERN is supplied as well as the address, because this screen reads its
 * selector with `useParams` and an unmatched pattern resolves it to `undefined` with no diagnostic --
 * which renders the screen's dead end instead of the subject. The harness refuses a pattern that
 * cannot match the address, so a mismatch fails here rather than several assertions later.
 *
 * Assumptions: the subject is awaited on its own caption rather than on a timer, so each case begins
 * from a settled first read.
 * @param {PendingAuthDetailScreen} detail - The rendering the first read answers with.
 * @param {string} [entry] - Address the router opens at, defaulting to the standard detail address.
 * @returns {Promise<HarnessRenderResult>} The render result and the operator that drives it.
 * @throws {Error} If the caption never appears, which Testing Library reports, or if the route pattern
 *   cannot match the address, which the harness reports.
 */
async function renderDetail(
  detail: PendingAuthDetailScreen,
  entry: string = DETAIL_ENTRY,
): Promise<HarnessRenderResult> {
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue(detail);
  const rendered = await renderInAppShell(<AuthDetailScreen />, {
    initialEntries: [entry],
    routePath: AUTHORIZATION_DETAIL_ROUTE,
  });
  expect(await screen.findByText(AUTH_DETAIL_SUBTITLE)).toBeInTheDocument();
  return rendered;
}

/**
 * Locates the value cell antd renders beside one painted label.
 *
 * Purpose
 * -------
 * `Descriptions` in its bordered form emits a `th` label and a `td` content cell as SIBLINGS, and puts
 * two such pairs in one row at the medium breakpoint -- so the value belonging to a label is the
 * label's next element sibling and emphatically not "the content cell in this row".
 *
 * Assumptions: the label is found through the harness's verbatim matcher rather than through a plain
 * text query, and that is load-bearing for exactly one label. `AUTH_DETAIL_FIELD_LABELS.source` is
 * `'Source   :'` with the internal padding the mapset uses to align its colon, and Testing Library's
 * default normaliser collapses whitespace runs in the DOM before comparing -- so a plain query for the
 * padded string finds nothing at all while a query for the collapsed one would accept a screen that
 * had dropped the padding. Alternatives Considered: passing `collapseWhitespace: false` to the query
 * directly, which the installed types still accept but mark as superseded by an explicit normaliser;
 * the harness helper already builds that normaliser, so using it keeps one spelling of the rule.
 * @param {string} label - The mapset's painted label, from the screen's own label constants.
 * @returns {HTMLElement} The content cell rendering that label's value.
 * @throws {Error} If no element carries the label verbatim, which the matcher reports, or if the label
 *   is not inside a description label cell followed by a content cell, which means the record is no
 *   longer laid out as a `Descriptions` block.
 */
function recordValueCellFor(label: string): HTMLElement {
  const labelText = expectVerbatimMessage(label);
  const labelCell = labelText.closest('th.ant-descriptions-item-label');
  const valueCell = labelCell?.nextElementSibling;
  if (
    !(valueCell instanceof HTMLElement) ||
    !valueCell.matches('td.ant-descriptions-item-content')
  ) {
    throw new Error(
      `The label ${label} is not followed by a Descriptions content cell, so the record is no longer rendered as a description list.`,
    );
  }
  return valueCell;
}

/**
 * Reads the row-23 message band the screen delegates to the shell.
 *
 * Assumptions: the band is queried by the identifier `ui/src/layout/MessageBand.tsx` publishes rather
 * than by role, because that module assigns the role from the SEVERITY -- so a query by role would have
 * to know which severity the case expects before it could find the element whose text decides it.
 * @returns {HTMLElement} The band element, present whether or not it holds a sentence.
 * @throws {Error} If no band is rendered, which means the subject was mounted without the shell.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Locates the row-24 legend's control for one advertised key.
 *
 * Assumptions: the search is scoped to the legend's own navigation landmark, because this screen
 * renders a SECOND control for the fifth and eighth keys beside the record -- the fraud trigger and the
 * step button -- carrying the identical accessible name. An unscoped query for either name matches two
 * elements and fails on the ambiguity rather than on the contract.
 * @param {string} label - The legend label, from the screen's own key-label constants.
 * @returns {HTMLElement} The legend control carrying that label.
 * @throws {Error} If the legend renders no such control, which Testing Library reports.
 */
function keyLegendControl(label: string): HTMLElement {
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  return within(legend).getByRole('button', { name: label });
}

/**
 * Confirms the fraud prompt that the fifth key opens.
 *
 * Assumptions: the accept is located INSIDE the dialog, because the accept carries the mapset's own
 * row-24 legend text and so shares its accessible name with the trigger beside the record and with the
 * legend's own control. Scoping the query to the dialog is what distinguishes the three, and it is also
 * an assertion in its own right: a screen offering the accept outside a dialog would not be found here.
 * @param {HarnessRenderResult} rendered - The render result whose operator performs the click.
 * @returns {Promise<void>} Resolves once the confirmation has been accepted and settled.
 * @throws {Error} If no confirmation is offered, which means the write was not gated.
 */
async function confirmFraudPrompt(rendered: HarnessRenderResult): Promise<void> {
  const dialog = await screen.findByRole('dialog');
  await rendered.user.click(
    within(dialog).getByRole('button', { name: AUTH_DETAIL_KEY_LABELS.PFK05 }),
  );
}

/**
 * Dismisses the fraud prompt without confirming it.
 * @param {HarnessRenderResult} rendered - The render result whose operator performs the click.
 * @returns {Promise<void>} Resolves once the prompt has been dismissed and settled.
 * @throws {Error} If no prompt is open, which means the fifth key did not raise one.
 */
async function dismissFraudPrompt(rendered: HarnessRenderResult): Promise<void> {
  const dialog = await screen.findByRole('dialog');
  await rendered.user.click(within(dialog).getByRole('button', { name: /^Cancel$/u }));
}

/**
 * Reads one member of the screen projection as the text the fixture carries.
 *
 * Assumptions: a nullable member is reported as the empty string, matching the screen's own
 * normalisation -- the reference paints an empty field for an absent value rather than a word, so a
 * case comparing rendered text against a fixture has to normalise the same way.
 * @param {PendingAuthDetailScreen} detail - The fixture to read.
 * @param {keyof PendingAuthDetailScreen} member - The member to read.
 * @returns {string} That member as text, or the empty string when it carried nothing.
 */
function fixtureText(
  detail: PendingAuthDetailScreen,
  member: keyof PendingAuthDetailScreen,
): string {
  const value = detail[member];
  return value ?? '';
}

/**
 * Asserts every value field the mapset paints renders beside its label, within its declared width.
 *
 * Assumptions: the amount is the ONE member excluded from the width bound, and the exclusion is a
 * registered divergence rather than an oversight. `cbl/COPAUS1C.cbl` L52 declares the display mask
 * `WS-AUTH-AMT PIC -zzzzzzz9.99` -- twelve characters holding only eight integer positions against a
 * ten-digit packed source -- so the terminal truncated a large amount. Money is the one class of value
 * this migration will not misstate for fidelity's sake, so the screen renders the exact figure and the
 * separate case below asserts it does.
 * @returns {Promise<void>} Resolves once all twenty fields have been found.
 */
async function declaresEveryPaintedFieldAtItsMapsetWidth(): Promise<void> {
  await renderDetail(DETAIL);

  expect(DECLARED_RECORD_FIELDS).toHaveLength(20);
  for (const field of DECLARED_RECORD_FIELDS) {
    const label = field.label;
    if (label === null) {
      throw new Error(
        `${field.mapsetField} is a record field and must carry the mapset's own label.`,
      );
    }
    const value = fixtureText(DETAIL, field.member);
    const cell = recordValueCellFor(label);
    expect(cell).toHaveTextContent(value);
    if (field.member !== 'approvedAmount') {
      expect(value.length).toBeLessThanOrEqual(field.declaredWidth);
    }
  }
}

/**
 * Asserts the six header-band fields are declared at the mapset's widths and reach the shell's band.
 *
 * Assumptions: only two of the six are asserted in the DOM, and that is the screen's own arrangement
 * rather than a gap in this case. The screen delegates the transaction name and the program name to
 * `AppShell` and deliberately does not delegate an instant, because the shell renders its own date and
 * time and painting the service's alongside them would put two clocks on one screen -- so the other
 * four members are a contract obligation this case holds to their declared widths without a rendered
 * counterpart to find.
 * @returns {Promise<void>} Resolves once the delegated identifiers have been found in the title band.
 */
async function declaresTheHeaderBandAtItsMapsetWidths(): Promise<void> {
  await renderDetail(DETAIL);

  expect(DECLARED_HEADER_FIELDS).toHaveLength(6);
  for (const field of DECLARED_HEADER_FIELDS) {
    expect(field.label).toBeNull();
    expect(fixtureText(DETAIL, field.member).length).toBeLessThanOrEqual(field.declaredWidth);
  }

  const titleBand = shellLandmark('titleBand');
  expect(titleBand).toHaveTextContent(DETAIL.transactionName);
  expect(titleBand).toHaveTextContent(DETAIL.programName);
}

/**
 * Asserts the record is rendered as description lists and offers nothing to type into.
 *
 * Assumptions: every one of the fifty-four `DFHMDF` definitions on this map is `ASKIP` -- the mapset
 * carries no `UNPROT` operand anywhere, and `CARDNUM` at L85 is `ATTRB=(ASKIP,NORM)` -- so the whole
 * screen is display-only and the design-system mapping assigns `Descriptions` plus a fraud-mark
 * `Popconfirm` to exactly this shape. A rendered input would be a control the terminal did not have.
 *
 * Assumptions: the count of label cells is asserted as well as the absence of inputs, because the two
 * fail independently: a screen could drop the whole block and still have no inputs.
 *
 * Trade-offs: the assertion reaches for antd's own class names, which is a coupling to the library's
 * internal markup. It is accepted because the alternative -- a description list has no ARIA role of
 * its own -- would leave the block's structure unassertable, and the class names are the same handles
 * the sibling screen tests already use.
 * @returns {Promise<void>} Resolves once the record's structure has been measured.
 */
async function rendersTheRecordAsDescriptionsAndNotAsInputs(): Promise<void> {
  const rendered = await renderDetail(DETAIL);

  expect(
    rendered.container.ownerDocument.querySelectorAll('.ant-descriptions-item-label'),
  ).toHaveLength(DECLARED_RECORD_FIELDS.length);
  expect(screen.queryAllByRole('textbox')).toHaveLength(0);
  expect(screen.queryAllByRole('spinbutton')).toHaveLength(0);
  expect(screen.queryAllByRole('combobox')).toHaveLength(0);
  expect(rendered.container.ownerDocument.querySelectorAll('input')).toHaveLength(0);
  expect(rendered.container.ownerDocument.querySelectorAll('dl')).toHaveLength(0);
  expect(screen.getByText(AUTH_DETAIL_MERCHANT_HEADING)).toBeInTheDocument();
}

/**
 * Asserts nothing on the screen takes the initial cursor and nothing is a non-display field.
 *
 * Assumptions: `app/app-authorization-ims-db2-mq/bms/COPAU01.bms` declares ZERO `IC`, `DRK` and `NUM`
 * attribute operands -- measured across all fifty-four of its `DFHMDF` definitions -- so there is no
 * initial-cursor field to autofocus and no non-display field to mask. A naive search for `NUM` appears
 * to hit `CARDNUM DFHMDF ATTRB=(ASKIP,NORM)` at L85, but that is a match on the FIELD NAME: the
 * attribute list there is `ASKIP,NORM` and carries no `NUM` operand at all. The false positive is named
 * here so a later reader does not "restore" an autofocus the mapset never asked for.
 *
 * Assumptions: this screen is one of four with no initial cursor, the others being the transaction
 * list, the user list and the authorization summary -- all of them screens an operator reads rather
 * than types into.
 * @returns {Promise<void>} Resolves once the absences have been measured.
 */
async function carriesNoInitialCursorAndNoNonDisplayField(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  const owner = rendered.container.ownerDocument;

  expect(owner.querySelectorAll('[autofocus]')).toHaveLength(0);
  expect(owner.querySelectorAll('input[type="password"]')).toHaveLength(0);
  expect(owner.querySelectorAll('.ant-input-password')).toHaveLength(0);
  expect(owner.activeElement).toBe(owner.body);
}

/**
 * Asserts every entry of the decline-reason table renders its own description, verbatim.
 *
 * Purpose
 * -------
 * This is the table-driven half of the decode contract: ten separate renders, one per declared entry,
 * each asserting that the composed reason the service sends reaches the glass unchanged. The two
 * misspelled descriptions are asserted exactly as the baseline writes them, which is what makes this
 * case a guard against a well-meaning correction rather than a formality.
 *
 * Assumptions: each entry is rendered in its own arrangement rather than all ten in one, because a
 * single record carries one reason -- and a case that rendered ten records would be asserting a screen
 * this application never builds.
 * @returns {Promise<void>} Resolves once all ten entries have been rendered and found.
 */
async function decodesEveryDeclineReasonCode(): Promise<void> {
  expect(DECLINE_REASONS).toHaveLength(10);

  for (const reason of DECLINE_REASONS) {
    const composed = composedReason(reason);
    const rendered = await renderDetail({ ...DETAIL, authResponseReason: composed });
    const cell = recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.responseReason);
    expect(cell).toHaveTextContent(composed);
    expect(cell).toHaveTextContent(reason.description);
    rendered.unmount();
    vi.mocked(getPendingAuthorizationScreen).mockReset();
  }
}

/**
 * Asserts the composed reason splits at four characters and preserves both misspellings.
 *
 * Assumptions: this case is PURE -- it renders nothing -- because the property it states belongs to
 * the composition rather than to the DOM, and the case above already proves the composed form reaches
 * the glass. Splitting the two concerns keeps a failure legible: a broken split fails here, a broken
 * render fails there.
 *
 * Assumptions: `AUTHRSP` is `LENGTH=1` at L114 while the decode key is four characters wide, and the
 * two are reconciled rather than contradictory. The narrow field carries the summarised approval
 * indicator the program derives at L311 to L317 -- `'A'` or `'D'` -- and the four-character code drives
 * the twenty-character reason text in `AUTHRSN` at L124. Two fields, two widths, one response.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function splitsTheComposedReasonAtFourCharacters(): void {
  for (const reason of DECLINE_REASONS) {
    const composed = composedReason(reason);
    expect(reason.code).toHaveLength(REASON_CODE_WIDTH);
    expect(composed.slice(0, REASON_CODE_WIDTH)).toBe(reason.code);
    expect(composed.charAt(REASON_CODE_WIDTH)).toBe(REASON_SEPARATOR);
    expect(composed.slice(REASON_CODE_WIDTH + 1)).toBe(reason.description);
    expect(reason.description.length).toBeLessThanOrEqual(REASON_DESCRIPTION_WIDTH);
  }

  /*
   * WHY : Assumptions: the two misspellings are pinned by INDEX into the declared table rather than by
   *       a search for their text, so the assertion also fixes WHICH code each belongs to. A search
   *       would still pass if the two descriptions were swapped between `4100` and `4400`, which is a
   *       plausible transcription slip and a wrong reason on a declined authorization.
   * WHY : Assumptions: the two locals are named for the CONDITION each code reports rather than for the
   *       description text, so that no correctly-spelled form of either misspelled string appears in
   *       this file's code. The corrected spellings are named in the table's own block above, where
   *       they are needed to explain what was preserved and why -- prose that has to say which letter
   *       is missing, and a name that must not.
   */
  const fundsShortfall = DECLINE_REASONS[2];
  const dailyCeiling = DECLINE_REASONS[5];
  expect(fundsShortfall).toEqual({ line: 60, code: '4100', description: 'INSUFFICNT FUND' });
  expect(dailyCeiling).toEqual({ line: 63, code: '4400', description: 'EXCED DAILY LMT' });

  const declaredLines = DECLINE_REASONS.map(lineOfReason);
  expect(declaredLines).toEqual([58, 59, 60, 61, 62, 63, 64, 65, 66, 67]);
}

/**
 * Reports the baseline line one table entry is declared at.
 *
 * Assumptions: a named declaration rather than an inline arrow, because the documentation gate requires
 * a block on a function expression in any position and Prettier detaches a block comment attached to an
 * inline argument.
 * @param {DeclineReason} reason - The entry to read.
 * @returns {number} Its one-based line in the baseline program.
 */
function lineOfReason(reason: DeclineReason): number {
  return reason.line;
}

/**
 * Asserts money crosses the boundary as an exact decimal string and renders in the code face.
 *
 * Trade-offs: money is transported as a JSON STRING and never as a JSON number, and the mechanism is
 * worth naming precisely because the failure it prevents is silent. A JSON number is parsed into an
 * IEEE-754 binary64 double by essentially every client, and binary64 cannot represent most scale-two
 * decimal fractions exactly -- so a cent the service computed as `NUMERIC(12,2)` and carried through
 * Java as a `BigDecimal` at scale two with `HALF_UP` could render as a different cent. The result is a
 * plausible figure rather than an error, which is why this is the highest-risk area in the migration
 * and why no arithmetic, no `Number`, no `parseFloat`, no unary plus and no locale formatting is
 * applied to a money value anywhere in this file or in the screen.
 *
 * Refactoring Rationale: the packed representation is decoded ONCE, at the anti-corruption boundary --
 * `com.carddemo.common.codec.PackedDecimalCodec` on the service side and
 * `data-migration/src/carddemo_migration/copybook/packed.py` in the extract loader -- and never in the
 * browser. `PA-APPROVED-AMT` is `PIC S9(10)V99 COMP-3` at `cpy/CIPAUDTY.cpy` L35 and this context is
 * the only place packed decimal reaches persisted data at all, so a nibble arriving in the client
 * would mean a decoder had been skipped. This case asserts the negative directly: the rendered
 * document carries no byte outside the printable range.
 *
 * Assumptions: the code face is asserted through the CSS variable the theme emits for
 * `TYPOGRAPHY_TOKENS.fixedPitchData`, so the assertion follows the token bridge rather than naming a
 * font. The token exists because the 3270 cell grid aligned columns for free and a proportional face
 * would not.
 * @returns {Promise<void>} Resolves once the amount's type, text and face have been measured.
 */
async function carriesMoneyAsAnExactDecimalStringInTheCodeFace(): Promise<void> {
  await renderDetail(DETAIL);

  expect(typeof DETAIL.approvedAmount).toBe('string');
  expect(DETAIL.approvedAmount).toMatch(/^-?\d+\.\d{2}$/u);

  const cell = recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.amount);
  /*
   * WHY : ⚠️ Refactoring Rationale: the amount is located by its MASKED form, and it used to be
   *       located by the wire string. The screen now renders every amount through
   *       `MONEY_PICTURES.transactionAmount`, which is the money finding's own resolution -- browser
   *       validation counted four mutually incompatible money renderings across the application and
   *       named this screen's bare `123.45` as one of them.
   * WHY : ⚠️ Assumptions: the expectation is COMPUTED by the same helper the screen calls rather
   *       than written out as `+12345678.90`. A literal would keep passing if the screen swapped to a
   *       different picture of the same width, and it would state the mask's output in a second place
   *       where it could drift; the computed form measures that the screen used THIS picture on THIS
   *       value. The digits themselves are still asserted unmasked immediately below, which is the
   *       property that actually matters -- the mask decorates, it never re-computes.
   */
  const masked = applyMoneyEditMask(DETAIL.approvedAmount, MONEY_PICTURES.transactionAmount);
  expect(cell).toHaveTextContent(DETAIL.approvedAmount);

  const amountText = screen.getByText(masked);
  expect(amountText.getAttribute('style')).toContain(
    `var(${antdCssVariable(TYPOGRAPHY_TOKENS.fixedPitchData)})`,
  );

  /*
   * WHY : ⚠️ Assumptions: `white-space: pre` is asserted beside the face because the two together
   *       are what make a column. The mask pads to a fixed width, and under normal white-space
   *       handling a browser collapses a run of spaces -- so without `pre` the fixed-pitch face would
   *       align nothing. `renderMoney` returns the property for that reason.
   */
  expect(amountText.style.whiteSpace).toBe('pre');

  /*
   * WHY : Assumptions: the whole rendered document is scanned rather than the amount alone, because a
   *       skipped decoder would not confine its output to the money field -- the same packed fields
   *       carry the transaction amount and the composite key, so any of them could surface. The range
   *       tested is the C0 control block plus the delete character, which is what a nibble pair or a
   *       raw sign byte would present as; ordinary text on this screen is printable throughout.
   */
  const renderedText = screen.getByRole('main').textContent ?? '';
  expect(renderedText).not.toMatch(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u);
}

/**
 * Asserts an amount wider than the mapset's display field renders in full rather than truncated.
 *
 * Trade-offs: this is a REGISTERED DIVERGENCE from the terminal's behaviour and it is taken
 * deliberately. `cbl/COPAUS1C.cbl` L52 declares `WS-AUTH-AMT PIC -zzzzzzz9.99`, twelve characters with
 * only eight integer positions, against a source declared `PIC S9(10)V99 COMP-3` -- so an amount above
 * ninety-nine million was silently truncated on the glass. Reproducing that would misstate money,
 * which the migration will not do for fidelity's sake, so the exact figure is rendered and the
 * divergence is recorded rather than silent.
 *
 * ⚠️ Assumptions: the divergence SURVIVES the screen now masking its amount, and that is a property
 * of `applyMoneyEditMask` rather than of this screen -- it preserves a value too wide for its picture
 * instead of clipping it to the picture's width. This case is therefore what proves the mask cannot
 * reintroduce the terminal's truncation, which is the one outcome that would make the money finding's
 * fix worse than the defect it fixes.
 *
 * Assumptions: the fixture's amount is thirteen characters against the field's declared twelve, so the
 * case fails if a later change reinstated the terminal's own mask.
 * @returns {Promise<void>} Resolves once the untruncated amount has been found.
 */
async function rendersAnAmountWiderThanTheMapsetFieldInFull(): Promise<void> {
  await renderDetail(LARGE_AMOUNT_DETAIL);

  expect(LARGE_AMOUNT_DETAIL.approvedAmount.length).toBeGreaterThan(12);
  const cell = recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.amount);
  expect(cell).toHaveTextContent(LARGE_AMOUNT_DETAIL.approvedAmount);
}

/**
 * Asserts the coded members stay inside the domains the reference's condition names declare.
 *
 * Assumptions: both are TYPED unions rather than bare strings, and the typing is what this case
 * verifies at compile time as much as at run time -- the two local annotations below fail
 * `npm run typecheck` if either union is widened. `MatchStatus` is the four condition names at
 * `cpy/CIPAUDTY.cpy` L46 to L49 and `AuthFraudFlag` is the two at L51 and L52, which the migration
 * carries into the `pending_auth_detail` and fraud check constraints; an untagged authorization
 * satisfies neither fraud condition name and is therefore an absence rather than a third value.
 *
 * Assumptions: the fraud MARK the screen renders is a composed display field and not the flag itself,
 * so its first character is what the domain applies to. `cbl/COPAUS1C.cbl` L344 to L350 writes the flag,
 * a hyphen and the report date into a ten-character field, or a lone hyphen when the row carries none.
 * @returns {Promise<void>} Resolves once both coded values have been measured.
 */
async function keepsCodedValuesInsideTheirDeclaredDomains(): Promise<void> {
  const matchStatuses: readonly MatchStatus[] = ['P', 'D', 'E', 'M'];
  const fraudFlags: readonly AuthFraudFlag[] = [FRAUD_REPORTED, FRAUD_WITHDRAWN];
  const fraudActions: readonly FraudAction[] = [FRAUD_REPORTED, FRAUD_WITHDRAWN];

  expect(matchStatuses).toContain(DETAIL.matchStatus);
  expect(fraudFlags).toHaveLength(2);
  expect(fraudActions).toHaveLength(2);

  await renderDetail(REPORTED_DETAIL);
  const cell = recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.fraudStatus);
  expect(cell).toHaveTextContent(REPORTED_DETAIL.fraudMark);
  expect(fraudFlags).toContain(REPORTED_DETAIL.fraudMark.charAt(0));
  expect(recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.matchStatus)).toHaveTextContent(
    DETAIL.matchStatus,
  );
}

/**
 * Asserts the merchant category code is spelled correctly and the baseline's misspelling is gone.
 *
 * Refactoring Rationale: `cpy/CIPAUDTY.cpy` L36 declares `PA-MERCHANT-CATAGORY-CODE PIC X(04)` and
 * `cpy/CCPAURQY.cpy` L28 repeats the same misspelling on the message payload as
 * `PA-RQ-MERCHANT-CATAGORY-CODE`. The migration corrects it to `merchantCategoryCode` because an
 * IDENTIFIER is code rather than text a human reads, and the correction is registered in
 * `docs/architecture/data-model-and-schema-mapping.md` so the lineage stays unambiguous.
 *
 * Assumptions: this is the deliberate OPPOSITE of the treatment the two misspelled decline-reason
 * descriptions get in the same context -- same repository, same commit, opposite decision -- and the
 * difference is exactly whether a human reads the string. That is why both cases exist rather than one.
 * @returns {Promise<void>} Resolves once the member name and its rendering have been measured.
 */
async function namesTheMerchantCategoryCodeWithoutTheBaselineMisspelling(): Promise<void> {
  await renderDetail(DETAIL);

  expect(DETAIL).toHaveProperty('merchantCategoryCode');
  const misspelled = Object.keys(DETAIL).filter(mentionsTheBaselineMisspelling);
  expect(misspelled).toEqual([]);

  const cell = recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.merchantCategoryCode);
  expect(cell).toHaveTextContent(fixtureText(DETAIL, 'merchantCategoryCode'));
}

/**
 * Reports whether one contract member name carries the baseline's misspelling of "category".
 *
 * Assumptions: the needle is assembled from two fragments rather than written out, so this file
 * contains no occurrence of the misspelled identifier for a later search to mistake for a real one --
 * the same technique `config/rule1/rule1_gate.py` uses to keep its own prohibited tokens out of its
 * source.
 * @param {string} member - The contract member name to test.
 * @returns {boolean} `true` when the name carries the misspelling.
 */
function mentionsTheBaselineMisspelling(member: string): boolean {
  return member.toLowerCase().includes(`cat${'a'}gory`);
}

/*
 * WHY : Assumptions: each fragment below is assembled from pieces rather than written whole, for the
 *       same reason `config/rule1/rule1_gate.py` assembles its own prohibited tokens: a repository-wide
 *       search for a card verification value must not land on the one file that exists to prove the
 *       value is absent. The list is the exposure rule of the migration plan's security section stated
 *       as data -- the verification value is returned by no endpoint, and the national and
 *       government-issued identifiers are stored encrypted and returned masked, so none of them has a
 *       member on this projection at all.
 */

/** Member-name fragments no authorization projection may carry. */
const FORBIDDEN_MEMBER_FRAGMENTS: readonly string[] = [
  `c${'v'}v`,
  'ssn',
  'social',
  'government',
  'govtissued',
  'password',
];

/**
 * Asserts the card number is masked and no other sensitive member exists to expose.
 *
 * Assumptions: this screen is NOT the administrative card-detail endpoint, which is the only reading
 * the migration plan grants the unmasked exception to, so the full sixteen digits may not render here
 * at all. `ui/src/api/authorization.ts` refuses an unmasked value on arrival for both the member and
 * the screen projection, so a rendered unmasked number would mean the mask had been removed between
 * the guard and the glass.
 *
 * Assumptions: the negative is asserted two ways because they fail independently -- the specific
 * number the fixture's mask hides must not appear, and no run of sixteen consecutive digits may appear
 * from any source at all.
 * @returns {Promise<void>} Resolves once the masking and the absences have been measured.
 */
async function masksTheCardNumberAndCarriesNoOtherSensitiveMember(): Promise<void> {
  await renderDetail(DETAIL);

  const cell = recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.cardNumber);
  expect(cell).toHaveTextContent(MASKED_CARD);
  expect(MASKED_CARD).toMatch(/^\*{12}\d{4}$/u);
  expect(MASKED_CARD.endsWith(CARD_LAST_FOUR)).toBe(true);

  const renderedText = screen.getByRole('main').textContent ?? '';
  expect(renderedText).not.toContain(UNMASKED_CARD_NEVER_RENDERED);
  expect(renderedText).not.toMatch(/\d{16}/u);

  const members = Object.keys(DETAIL).map(toLowerCaseMember);
  for (const fragment of FORBIDDEN_MEMBER_FRAGMENTS) {
    expect(members.some(containsFragment(fragment))).toBe(false);
  }
}

/**
 * Lowers one member name so a fragment test is case-insensitive without a regular expression.
 * @param {string} member - The member name.
 * @returns {string} That name in lower case.
 */
function toLowerCaseMember(member: string): string {
  return member.toLowerCase();
}

/**
 * Builds a predicate reporting whether a lowered member name contains one fragment.
 *
 * Assumptions: a factory returning a NAMED function rather than an inline arrow at the call site,
 * because `ui/eslint.config.js` sets `jsdoc/require-jsdoc` with `publicOnly: false` and selects a
 * function expression in every position, and Prettier moves a block comment attached to an inline
 * argument onto the preceding expression.
 * @param {string} fragment - The lowered fragment to look for.
 * @returns {(member: string) => boolean} A predicate over lowered member names.
 */
function containsFragment(fragment: string): (member: string) => boolean {
  /**
   * Reports whether one lowered member name contains the captured fragment.
   * @param {string} member - The lowered member name.
   * @returns {boolean} `true` when the fragment occurs in it.
   */
  function test(member: string): boolean {
    return member.includes(fragment);
  }

  return test;
}

/**
 * Asserts the catalog holds this program's three sentences at the lines the baseline writes them.
 *
 * Assumptions: the sentences are asserted through the catalog and its provenance rather than by being
 * retyped here, which is what makes the assertion mean "the screen agrees with the catalog" rather than
 * "the screen agrees with this test". Transformation rule T8 carries each across character for
 * character, and `ui/src/messages/messages.ts` is the one place they live.
 *
 * Assumptions: the mixed-case and upper-case spellings of the same word are held as SEPARATE catalog
 * entries and must stay separate. This program writes `'AUTH'` upper case in its two fraud
 * confirmations at L535 and L537, and its three withheld read diagnostics write `'Auth'` mixed case,
 * while the sibling summary program `COPAUS0C` writes `'AUTH'` upper case in the corresponding
 * positions -- three capitalisations across two adjacent programs. Merging any two of them would
 * corrupt one screen to tidy another, so this case asserts the two programs' catalog entries are
 * disjoint.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function holdsThisProgramsSentencesAtTheirBaselineLines(): void {
  expect(Object.keys(DETAIL_MESSAGES)).toHaveLength(3);
  expect(DETAIL_MESSAGE_LINES.ALREADY_AT_THE_LAST_AUTHORIZATION).toEqual([283]);
  expect(DETAIL_MESSAGE_LINES.AUTH_FRAUD_REMOVED).toEqual([535]);
  expect(DETAIL_MESSAGE_LINES.AUTH_MARKED_FRAUD).toEqual([537]);

  /*
   * WHY : Assumptions: the case of each sentence is asserted rather than assumed, because the three
   *       differ from one another and from the sibling program's. The two fraud confirmations are
   *       upper case throughout; the end-of-set sentence capitalises only `Authorization`.
   */
  expect(DETAIL_MESSAGES.AUTH_FRAUD_REMOVED).toBe(DETAIL_MESSAGES.AUTH_FRAUD_REMOVED.toUpperCase());
  expect(DETAIL_MESSAGES.AUTH_MARKED_FRAUD).toBe(DETAIL_MESSAGES.AUTH_MARKED_FRAUD.toUpperCase());
  const endOfSet: string = DETAIL_MESSAGES.ALREADY_AT_THE_LAST_AUTHORIZATION;
  expect(endOfSet).not.toBe(endOfSet.toUpperCase());
  expect(endOfSet).toContain('Authorization');

  const summarySentences: readonly string[] = Object.values(PROGRAM_MESSAGES.COPAUS0C);
  for (const sentence of Object.values(DETAIL_MESSAGES)) {
    expect(summarySentences).not.toContain(sentence);
  }

  /*
   * WHY : Assumptions: the invalid-key sentence is catalogued from the copybook that DECLARES it and is
   *       emitted by this program at `cbl/COPAUS1C.cbl` L196, which moves `CCDA-MSG-INVALID-KEY` into
   *       `WS-MESSAGE` on the `WHEN OTHER` arm. The declared width is asserted from the catalog's own
   *       provenance rather than counted here, because `app/cpy/CSMSG01Y.cpy` L20 declares the
   *       `PIC X(50)` clause and L21 holds the padded value -- so the padding is content, and the
   *       rendered form is shorter for the reason recorded beside `INVALID_KEY_AS_RENDERED`.
   */
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
  expect(INVALID_KEY_PRESSED.length).toBeLessThanOrEqual(COMMON_MESSAGES.INVALID_KEY.declaredWidth);
  expect(INVALID_KEY_PRESSED.length).toBeGreaterThan(INVALID_KEY_AS_RENDERED.length);
  expect(COMMON_MESSAGES.INVALID_KEY.source.file).toBe('app/cpy/CSMSG01Y.cpy');
  expect(COMMON_MESSAGES.INVALID_KEY.source.lines).toEqual([21]);
  expect(INVALID_KEY_PRESSED).toBe(COMMON_MESSAGES.INVALID_KEY.text);
}

/**
 * Asserts the five diagnostics this program composes are registered as withheld, not displayed.
 *
 * Purpose
 * -------
 * The reference appends an internal IMS status code to five composed sentences, one of which is the
 * only user-visible rollback message in the whole application. The target withholds all five and shows
 * the shared abend sentence in their place, sending the status code to a server-side structured log --
 * so what a browser test can honestly assert is the REGISTER, not a rendered sentence.
 *
 * Assumptions: the withheld text is deliberately not reproduced here, not even as a comment.
 * `REDACTED_DIAGNOSTICS` records the reason in its own note: the point of the redaction is that the
 * message does not reach a browser bundle, a string literal reaches it in every build mode, and the
 * cited baseline lines already point at the authoritative copy. Restating the sentences to "make the
 * test more explicit" would reintroduce exactly what the register exists to remove.
 *
 * Assumptions: the register cites each composition at its `STRING` statement line -- 455, 481, 510, 544
 * and 595 -- while each literal operand sits one line below it, at 456, 482, 511, 545 and 596. Both
 * readings name the same composition; the register's is used because the register is what is being
 * asserted. Three properties of those baseline lines are worth a reader's attention and are recorded
 * here rather than measured, because measuring them would mean reproducing them: every fragment opens
 * with a LEADING SPACE, which is content and not formatting; the fragment at L545 ends with a literal
 * DOUBLE PIPE before the status code is appended -- in COBOL that is part of the string literal and
 * not a concatenation operator, so an author who read it as an operator would silently shorten the
 * message; and the fragment at L596 names a `PSB`, an IMS program specification block.
 *
 * Trade-offs: that last one is a message that has outlived its machinery. The migration collapses the
 * IMS segments and the Db2 tables into one PostgreSQL schema and eliminates the distributed
 * transaction rather than emulating it, so there is no program specification block left to schedule --
 * yet the sentence would survive verbatim under rule T8 if it were displayed at all. Modernising its
 * wording would be a behavioural divergence needing its own registration in
 * `docs/architecture/cobol-to-service-traceability.md`; withholding it, which is what actually
 * happens, is registered here.
 * @returns {void} Nothing; failure is reported by the expectations.
 */
function registersEveryWithheldDiagnosticOfThisProgram(): void {
  const registered = REDACTED_DIAGNOSTICS.filter(isDiagnosticOfThisProgram);

  expect(registered.map(firstLineOfDiagnostic)).toEqual([455, 481, 510, 544, 595]);
  for (const entry of registered) {
    expect(entry.file).toBe(PROGRAM_SOURCE_FILES.COPAUS1C);
    expect(entry.detail).toBe('ims-status-code');
    expect(entry.replacement).toBe(UNEXPECTED_ABEND_OCCURRED);
    expect(entry.condition.length).toBeGreaterThan(0);
  }

  /*
   * WHY : Assumptions: the rollback entry is identified by its registered line rather than by its
   *       condition text, so the assertion survives a rewording of the condition while still pinning
   *       that the fraud transition's failure path is one of the five withheld compositions. It is the
   *       only rollback the operator ever saw a message for.
   */
  const rollback = registered.find(isTheRollbackDiagnostic);
  expect(rollback).toBeDefined();
  expect(rollback?.condition).toContain('rolled back');
}

/**
 * One row of the register of baseline diagnostics the target withholds.
 *
 * Assumptions: the type is derived from the exported register rather than restated, so a member added
 * to `RedactedDiagnostic` reaches these helpers without an edit here.
 */
type WithheldDiagnostic = (typeof REDACTED_DIAGNOSTICS)[number];

/**
 * Reports whether one register row belongs to the detail program.
 * @param {WithheldDiagnostic} entry - The register row to test.
 * @returns {boolean} `true` when the row's program is `COPAUS1C`.
 */
function isDiagnosticOfThisProgram(entry: WithheldDiagnostic): boolean {
  return entry.program === 'COPAUS1C';
}

/**
 * Reports the first baseline line one register row cites.
 * @param {WithheldDiagnostic} entry - The register row to read.
 * @returns {number | undefined} Its first cited line, or `undefined` for a row citing none.
 */
function firstLineOfDiagnostic(entry: WithheldDiagnostic): number | undefined {
  return entry.lines[0];
}

/**
 * Reports whether one register row is the fraud transition's rolled-back write.
 *
 * Assumptions: the row is identified by the line the register cites for it, read through the accessor
 * above rather than by testing the `lines` tuple directly -- the register is declared `as const`, so
 * each row's tuple carries its own literal element type and a membership test written against one
 * number would not type-check across the union.
 * @param {WithheldDiagnostic} entry - The register row to test.
 * @returns {boolean} `true` when the row is the one registered at the rollback composition.
 */
function isTheRollbackDiagnostic(entry: WithheldDiagnostic): boolean {
  return isDiagnosticOfThisProgram(entry) && firstLineOfDiagnostic(entry) === 544;
}

/**
 * Asserts the row-24 legend advertises three keys and reconstructs the mapset's own operand.
 *
 * Assumptions: the legend is asserted by RECONSTRUCTION rather than against a retyped literal. The
 * mapset's `INITIAL=` operand at `COPAU01.bms` L292 is one string of forty-four characters in a
 * `LENGTH=45` field, and the screen carries it as three labels split at the double spaces the mapset
 * uses as its separator -- so joining the three with that separator and restoring the single grid
 * offset blank reproduces the operand exactly, and its length is the assertion.
 *
 * Trade-offs: the operand's LEADING SPACE is not carried into the first label, and that is a
 * deliberate, recorded decision rather than a lost byte. It is a position offset on the fixed
 * character grid, and design gap G1 gives up that grid -- at one column per row there is nothing left
 * to offset against, so a preserved leading space would read as a typo rather than as alignment. The
 * offset is accounted for here instead, in the reconstruction, so the byte is still counted.
 *
 * Trade-offs: the fourth bound key advertises NOTHING, and both ways of "fixing" that are refused.
 * `cbl/COPAUS1C.cbl` binds exactly four attention identifiers -- `DFHENTER` at L181, `DFHPF3` at L184,
 * `DFHPF5` at L187 and `DFHPF8` at L190 -- so L181 binds Enter while the legend carries no `ENTER=`
 * descriptor at
 * all -- the third advertise-versus-bind mismatch in the repository, alongside `COUSR01.bms`, which
 * advertises `F12` and binds none, and `COUSR03.bms`, which binds PF12 and advertises none. Adding an
 * `ENTER=` descriptor would alter a user-visible string rule T8 protects; unbinding Enter would remove
 * behaviour the baseline has. So Enter works from the keyboard and renders no control, and the
 * asymmetry is registered rather than resolved.
 * @returns {Promise<void>} Resolves once the legend has been measured.
 */
async function advertisesThreeKeysAndRendersNoEnterDescriptor(): Promise<void> {
  await renderDetail(DETAIL);

  const advertised = [
    AUTH_DETAIL_KEY_LABELS.PFK03,
    AUTH_DETAIL_KEY_LABELS.PFK05,
    AUTH_DETAIL_KEY_LABELS.PFK08,
  ];
  const reconstructed = ` ${advertised.join(LEGEND_SEPARATOR)}`;
  expect(reconstructed).toHaveLength(LEGEND_OPERAND_LENGTH);
  expect(LEGEND_OPERAND_LENGTH).toBeLessThan(LEGEND_FIELD_WIDTH);
  expect(decodeBmsLegendText(reconstructed)).toBe(reconstructed);

  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  expect(within(legend).getAllByRole('button')).toHaveLength(advertised.length);
  for (const label of advertised) {
    expect(keyLegendControl(label)).toBeInTheDocument();
  }
  expect(within(legend).queryByRole('button', { name: /Enter/u })).not.toBeInTheDocument();
}

/**
 * Asserts the legend's emphasis follows each key's declared RISK rather than its attention identifier.
 *
 * ⚠️ Refactoring Rationale: this case used to read `PRIMARY_ACTION_AIDS` and assert the emphasis that
 * mapping produces, and it now asserts the emphasis the screen's own risk declarations produce. The two
 * differ on the one key that matters: the identifier mapping puts Enter and the fifth key on the same
 * primary emphasis, so the key that re-reads a record and the key that reports a live authorization as
 * fraud rendered identically. Declaring `destructive` on the fifth key and `read-only` on the others
 * makes the writing key the solid DANGEROUS control, and `ant-btn-dangerous` is the class that
 * distinguishes it -- a regression to the identifier mapping would still be primary and would fail here.
 *
 * Assumptions: the identifier mapping is still read and asserted, because it remains the fallback for a
 * binding that declares no risk and this case is the record of why this screen declines it. Enter's own
 * emphasis is unobservable here for the reason the previous case records -- it advertises no control --
 * so the two facts are asserted together: it is named in the mapping, and it renders nothing.
 * @returns {Promise<void>} Resolves once the emphasis classes have been measured.
 */
async function emphasisesTheFifthKeyAndNotTheOtherAdvertisedKeys(): Promise<void> {
  await renderDetail(DETAIL);

  const primaryAids: readonly CicsAid[] = PRIMARY_ACTION_AIDS;
  expect(primaryAids).toContain('ENTER');
  expect(primaryAids).toContain('PFK05');
  expect(primaryAids).not.toContain('PFK03');
  expect(primaryAids).not.toContain('PFK08');

  expect(keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK05).className).toContain('ant-btn-primary');
  expect(keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK05).className).toContain('ant-btn-dangerous');
  expect(keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK03).className).toContain('ant-btn-default');
  expect(keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK03).className).not.toContain(
    'ant-btn-dangerous',
  );
  expect(keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK08).className).toContain('ant-btn-default');
  expect(keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK08).className).not.toContain(
    'ant-btn-dangerous',
  );
}

/**
 * Asserts Enter re-reads the authorization on display, which is this program's own Enter path.
 *
 * Assumptions: Enter is exercised through a REAL key event and not through a control, because it has
 * no control -- the contract is a keyboard contract, which is what the 3270 original was. The
 * observable effect is a second read of the same selector: `PROCESS-ENTER-KEY` at
 * `cbl/COPAUS1C.cbl` L208 to L216 erases the map and re-reads the addressed record.
 *
 * Assumptions: no key has been clicked before the press, so focus is still on the document body. The
 * key hook deliberately yields Enter to a focused activatable control, so pressing it after a click
 * would activate that control instead and measure the browser's default rather than the binding.
 * @returns {Promise<void>} Resolves once the second read has been observed.
 */
async function reReadsTheAuthorizationOnEnter(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  expect(vi.mocked(getPendingAuthorizationScreen)).toHaveBeenCalledTimes(1);

  await pressPfKey(rendered.user, 'ENTER');

  expect(vi.mocked(getPendingAuthorizationScreen)).toHaveBeenCalledTimes(2);
  expect(vi.mocked(getPendingAuthorizationScreen)).toHaveBeenLastCalledWith(SELECTOR);
  expect(messageBand()).not.toHaveTextContent(INVALID_KEY_AS_RENDERED);
}

/**
 * Asserts the third key returns to the summary screen the selection was made on.
 *
 * Assumptions: the destination is read from the router's own path constant as well as the screen's, and
 * the two are asserted equal, because `EXEC CICS XCTL PROGRAM('COPAUS0C')` at `cbl/COPAUS1C.cbl` L185
 * to L186 becomes a client route change under rule T5 -- and a route change is only correct if both
 * modules agree on the address.
 * @returns {Promise<void>} Resolves once the screen has left the detail route.
 */
async function returnsToTheSummaryOnTheThirdKey(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  expect(AUTHORIZATION_SUMMARY_ROUTE).toBe(AUTH_SUMMARY_PATH);

  await rendered.user.click(keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK03));

  expect(screen.queryByText(AUTH_DETAIL_SUBTITLE)).not.toBeInTheDocument();
}

/**
 * Asserts the eighth key steps to the next authorization RECORD and pages nothing.
 *
 * Assumptions: this key does not mean on this screen what it means on the screen beside it, and the
 * divergence is the point of the case. `UNIFORM_PF_KEY_LABELS.PFK08` reads `F8=Forward` because the
 * eighth key pages a browse list on every screen that was measured; here `cbl/COPAUS1C.cbl` L190
 * dispatches `PROCESS-PF8-KEY`, which L268 to L289 implements as "read the current authorization, then
 * read the one after it" -- a step to the next record, and this screen shows no list to page. The
 * mapset settles the wording independently at L292 with `F8=Next Auth`. `PfKeyBar` takes a per-screen
 * descriptor array precisely so the two can differ.
 *
 * Assumptions: the paging operation is asserted UNCALLED as well as the step operation called, because
 * the two failures are different: a screen could step correctly and also list, or list instead of
 * stepping. The step is additionally asserted to carry ONE argument, which is what distinguishes
 * addressing a row from addressing a page -- a cursor and a direction would be a second argument.
 * @returns {Promise<void>} Resolves once the step has been observed.
 */
async function stepsToTheNextAuthorizationRatherThanPagingForward(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  const screenLabel: string = AUTH_DETAIL_KEY_LABELS.PFK08;
  expect(screenLabel).not.toBe(UNIFORM_PF_KEY_LABELS.PFK08);

  vi.mocked(getNextPendingAuthorization).mockResolvedValue({
    authorization: { ...nextRowFor(NEXT_SELECTOR) },
    endOfData: false,
    message: null,
  });
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValue({
    ...DETAIL,
    transactionId: 'TRAN00000000002',
  });

  await pressPfKey(rendered.user, 'PFK08');

  expect(vi.mocked(getNextPendingAuthorization)).toHaveBeenCalledWith(SELECTOR);
  expect(vi.mocked(getNextPendingAuthorization).mock.calls[0]).toHaveLength(1);
  expect(vi.mocked(listPendingAuthorizations)).not.toHaveBeenCalled();
  expect(await screen.findByText('TRAN00000000002')).toBeInTheDocument();
  expect(vi.mocked(getPendingAuthorizationScreen)).toHaveBeenLastCalledWith(NEXT_SELECTOR);
}

/**
 * Asserts the end of an account's authorizations is reported with the program's own sentence.
 *
 * Assumptions: the sentence is the one written at `cbl/COPAUS1C.cbl` L283, which the catalog holds and
 * whose provenance the sentence case above asserts, so it is taken from the catalog here rather than
 * retyped.
 *
 * Assumptions: the boundary is a SUCCESSFUL answer carrying an end-of-data indicator rather than a
 * refusal, which is both the contract's shape and the reference's behaviour -- `PROCESS-PF8-KEY` at
 * L281 to L284 moves the sentence into the message field and leaves the error flag alone, where every
 * genuine refusal on this screen sets that flag first. The screen stays on the row it is showing.
 * @returns {Promise<void>} Resolves once the boundary sentence is on the band.
 */
async function reportsTheEndOfTheAuthorizationSet(): Promise<void> {
  const rendered = await renderDetail(DETAIL);

  vi.mocked(getNextPendingAuthorization).mockResolvedValue({
    authorization: null,
    endOfData: true,
    message: null,
  });

  await pressPfKey(rendered.user, 'PFK08');

  expect(
    expectVerbatimMessage(DETAIL_MESSAGES.ALREADY_AT_THE_LAST_AUTHORIZATION),
  ).toBeInTheDocument();
  expect(messageBand()).toHaveTextContent(DETAIL_MESSAGES.ALREADY_AT_THE_LAST_AUTHORIZATION);
  expect(recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.transactionId)).toHaveTextContent(
    DETAIL.transactionId,
  );
}

/**
 * Asserts the keys this program does not bind are refused with the baseline's own sentence.
 *
 * Assumptions: the fourth, seventh and twelfth keys are exercised, which is the complete set of keys
 * the other screens of this application bind and this one does not. `cbl/COPAUS1C.cbl` L180 to L197
 * admits exactly four attention identifiers and its `WHEN OTHER` arm at L193 to L197 both re-runs the
 * Enter path AND reports `CCDA-MSG-INVALID-KEY` -- an unusual combination among these programs and the
 * reference's own, which is why the read count rises as well as the sentence appearing.
 *
 * Assumptions: the absence of a seventh key is the notable half. There is no "previous authorization"
 * counterpart to the eighth key's step, so the navigation is deliberately one-way; inventing a PF7
 * would add behaviour the baseline does not have.
 * @returns {Promise<void>} Resolves once each refusal has been observed.
 */
async function refusesTheKeysTheProgramDoesNotBind(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  const unbound: readonly CicsAid[] = ['PFK04', 'PFK07', 'PFK12'];

  for (const aid of unbound) {
    await pressPfKey(rendered.user, aid);
    expect(messageBand()).toHaveTextContent(INVALID_KEY_AS_RENDERED);
  }

  expect(vi.mocked(getNextPendingAuthorization)).not.toHaveBeenCalled();
  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();
}

/**
 * Asserts each advertised key dispatches identically from its legend control and from its real key.
 *
 * Alternatives Considered: asserting only the control, which is how a browser-first test would be
 * written. Rejected because the 3270 original had no pointer at all, so the keyboard binding is the
 * fidelity-bearing path -- a suite that only clicked would pass in full while every keyboard binding
 * in the application was broken. Asserting only the key was also rejected: the controls exist because
 * the workflow was undiscoverable to anyone who did not already know it, so both paths carry weight
 * and both are measured against the same observable.
 *
 * Assumptions: the eighth key is the one measured both ways, because it is the only advertised key
 * whose effect is a transport call this case can count without leaving the screen -- the third key
 * navigates away and the fifth opens a prompt whose own case covers both of its entry points.
 * @returns {Promise<void>} Resolves once both dispatch paths have been observed.
 */
async function dispatchesTheEighthKeyFromBothPaths(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  vi.mocked(getNextPendingAuthorization).mockResolvedValue({
    authorization: null,
    endOfData: true,
    message: null,
  });

  await pressPfKey(rendered.user, 'PFK08');
  expect(vi.mocked(getNextPendingAuthorization)).toHaveBeenCalledTimes(1);

  await rendered.user.click(keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK08));
  expect(vi.mocked(getNextPendingAuthorization)).toHaveBeenCalledTimes(2);
  expect(vi.mocked(getNextPendingAuthorization)).toHaveBeenLastCalledWith(SELECTOR);
}

/**
 * Builds the member record the paging operation answers with for one selector.
 *
 * Assumptions: the full member shape is built out rather than cast, so a contract member added later
 * cannot appear with no test noticing. The card number carries the masked rendering every
 * non-administrative read returns, because the transport refuses an unmasked one on arrival.
 * @param {string} key - The sealed selector the returned row is addressed by.
 * @returns {PendingAuthDetail} The member record, in the shape the paging operation returns.
 */
function nextRowFor(key: string): PendingAuthDetail {
  return {
    key,
    accountId: SELECTED_ACCOUNT_ID,
    authDate: 25002,
    authTime: 36673000,
    authOrigDate: '250102',
    authOrigTime: '101113',
    cardNum: MASKED_CARD,
    authType: DETAIL.authType,
    cardExpiryDate: '0127',
    messageType: '0100',
    messageSource: DETAIL.messageSource,
    authIdCode: '000001',
    authRespCode: '00',
    authRespReason: '0000',
    processingCode: DETAIL.processingCode,
    transactionAmt: DETAIL.approvedAmount,
    approvedAmt: DETAIL.approvedAmount,
    merchantCategoryCode: DETAIL.merchantCategoryCode,
    acqrCountryCode: '840',
    posEntryMode: DETAIL.posEntryMode,
    merchantId: DETAIL.merchantId,
    merchantName: DETAIL.merchantName,
    merchantCity: DETAIL.merchantCity,
    merchantState: DETAIL.merchantState,
    merchantZip: DETAIL.merchantZip,
    transactionId: 'TRAN00000000002',
    matchStatus: DETAIL.matchStatus,
    authFraud: null,
    fraudRptDate: null,
  };
}

/**
 * Asserts the fraud transition is gated by a dangerous confirmation that writes exactly once.
 *
 * Assumptions: the confirmation is a browser ADDITION rather than a reference behaviour -- the
 * terminal's fifth key wrote immediately -- and it is added because a pointer can activate a control by
 * accident where a function key cannot, and reporting a live authorization as fraud is not reversible
 * without a second write.
 *
 * ⚠️ Refactoring Rationale: the accept is asserted SOLID dangerous -- `ant-btn-dangerous` together with
 * `ant-btn-primary` -- where it previously asserted the dangerous class alone. The legacy
 * `okType="danger"` this surface used resolved to `danger: true` with the DEFAULT variant, which
 * rendered the destructive control as the quieter of the two buttons and inverted emphasis against
 * risk. Asserting both classes is what makes that regression fail rather than pass.
 *
 * Assumptions: "exactly once" is asserted rather than "at least once", and on a toggle the difference
 * is material rather than pedantic: the direction is derived from the mark on the glass, so a second
 * invocation of one confirmation would ask for the opposite transition and flip the row back.
 * @returns {Promise<void>} Resolves once the dismissal and the confirmation have both been observed.
 */
async function gatesTheFraudTransitionBehindADangerousConfirmation(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'ADDED',
    message: PROGRAM_MESSAGES.COPAUS2C.ADD_SUCCESS,
  });

  await pressPfKey(rendered.user, 'PFK05');
  const dialog = await screen.findByRole('dialog');
  const confirmControl = within(dialog).getByRole('button', {
    name: AUTH_DETAIL_KEY_LABELS.PFK05,
  });
  expect(confirmControl.className).toContain('ant-btn-dangerous');
  expect(confirmControl.className).toContain('ant-btn-primary');

  await dismissFraudPrompt(rendered);
  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();

  await pressPfKey(rendered.user, 'PFK05');
  await confirmFraudPrompt(rendered);

  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledTimes(1);
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledWith(SELECTOR, {
    action: FRAUD_REPORTED,
  });
}

/**
 * The confirmation names the authorization it acts on, and both its controls point at that naming.
 *
 * ⚠️ Purpose: this is the regression this case exists for. Browser validation reported that no
 * confirmation on this screen carried a description, that none named the record it acted on, and that
 * the fraud prompt did not distinguish marking from removing -- its only text was the fifth key's own
 * legend echoed back. On a screen whose eighth key replaces the record UNDER an open prompt, "this one"
 * is not an identification.
 *
 * ⚠️ Assumptions: the four identifying values asserted are the ones that name the ROW --
 * `cpy/CIPAUDTY.cpy` L19 to L54 keys the detail by account with the authorization date and time, and
 * the mapset paints the card number and the fifteen-character transaction identifier beside them. The
 * fifth assertion is the tag that WOULD be written, which is how marking is distinguished from removing
 * without a sentence for either: `F` here because the fixture's mark is the lone separator.
 *
 * Assumptions: `aria-describedby` is asserted on BOTH controls rather than on the confirming one alone.
 * The safe control is the one that holds focus when the prompt opens, so it is the control whose
 * description a reviewer hears first, and a naming reachable only from the dangerous control would be
 * announced only to a reviewer already about to write.
 * @returns {Promise<void>} Resolves once the naming and both associations have been asserted.
 */
async function namesTheAuthorizationInTheConfirmation(): Promise<void> {
  const rendered = await renderDetail(DETAIL);

  await pressPfKey(rendered.user, 'PFK05');
  const dialog = await screen.findByRole('dialog');

  const naming = document.getElementById(FRAUD_CONFIRMATION_RECORD_ID);
  expect(naming).not.toBeNull();
  expect(naming?.textContent).toBe(
    `${AUTH_DETAIL_FIELD_LABELS.cardNumber} ${MASKED_CARD} ` +
      `${AUTH_DETAIL_FIELD_LABELS.authDate} ${DETAIL.authDate} ` +
      `${AUTH_DETAIL_FIELD_LABELS.authTime} ${DETAIL.authTime} ` +
      `${AUTH_DETAIL_FIELD_LABELS.transactionId} ${DETAIL.transactionId} ` +
      `${AUTH_DETAIL_FIELD_LABELS.fraudStatus} ${FRAUD_REPORTED}`,
  );

  /*
   * WHY : Assumptions: both controls are located INSIDE the dialog, because the accept carries the
   *       mapset's fifth-key legend and so shares its accessible name with the trigger beside the record
   *       and with the legend's own control. The scoping is what distinguishes them, and it also asserts
   *       that the described controls are the confirmation's own rather than any control that happens to
   *       carry the name.
   */
  expect(
    within(dialog).getByRole('button', { name: AUTH_DETAIL_KEY_LABELS.PFK05 }),
  ).toHaveAttribute('aria-describedby', FRAUD_CONFIRMATION_RECORD_ID);
  expect(within(dialog).getByRole('button', { name: /^Cancel$/u })).toHaveAttribute(
    'aria-describedby',
    FRAUD_CONFIRMATION_RECORD_ID,
  );
}

/**
 * The confirmation names the WITHDRAWAL when the record already carries a report.
 *
 * ⚠️ Assumptions: this is the half of the finding that a single-direction case cannot reach. The prompt
 * has one title for both directions -- the mapset paints one legend, `F5=Mark/Remove Fraud` -- so the
 * only thing that tells a reviewer which way the write goes is the tag in the naming. Asserting `R`
 * here against `F` in the case above is what makes the distinction observable.
 * @returns {Promise<void>} Resolves once the withdrawing tag has been asserted.
 */
async function namesTheWithdrawalOnAReportedAuthorization(): Promise<void> {
  const rendered = await renderDetail(REPORTED_DETAIL);

  await pressPfKey(rendered.user, 'PFK05');
  await screen.findByRole('dialog');

  expect(document.getElementById(FRAUD_CONFIRMATION_RECORD_ID)?.textContent).toContain(
    `${AUTH_DETAIL_FIELD_LABELS.fraudStatus} ${FRAUD_WITHDRAWN}`,
  );
}

/**
 * The confirmation is published as a modal DIALOG, from the pointer route and from the key route.
 *
 * ⚠️ Purpose: this is the measured finding. Browser validation reported the confirmation as a
 * `role="tooltip"` overlay with no `aria-modal` -- the tooltip primitive `Popconfirm` renders through
 * hardcodes that role with no prop path to change it -- so a reviewer using a screen reader was told a
 * hint had appeared rather than that a question was being asked. Three properties are asserted here and
 * none of them is authored by this screen: the role, the modal flag, and the accessible NAME resolving
 * through `aria-labelledby` to the mapset's own fifth-key legend. A regression to any anchored popup
 * primitive fails on the first of them.
 *
 * ⚠️ Assumptions: BOTH entry routes are exercised, because the finding was measured on both. The pointer
 * route is the trigger beside the record; the key route is the real `F5` attention identifier, which
 * reaches the same capture site. A screen that raised a dialog from one and a balloon from the other
 * would pass a single-route case.
 * @returns {Promise<void>} Resolves once both routes have published the dialog.
 */
async function publishesTheConfirmationAsAModalDialog(): Promise<void> {
  const rendered = await renderDetail(DETAIL);

  await rendered.user.click(fraudTriggerBesideTheRecord());
  const fromPointer = await screen.findByRole('dialog');
  expect(fromPointer).toHaveAttribute('aria-modal', 'true');
  expect(fromPointer).toHaveAccessibleName(AUTH_DETAIL_KEY_LABELS.PFK05);

  await rendered.user.keyboard('{Escape}');
  await waitForTheFraudConfirmationToClose();

  await pressPfKey(rendered.user, 'PFK05');
  const fromKey = await screen.findByRole('dialog');
  expect(fromKey).toHaveAttribute('aria-modal', 'true');
  expect(fromKey).toHaveAccessibleName(AUTH_DETAIL_KEY_LABELS.PFK05);
  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();
}

/**
 * How many tab presses the containment probe makes.
 *
 * Assumptions: five, which is one more than the confirmation's own control count -- the close control,
 * the decline and the accept, plus the step through the document that jsdom's tab wrap takes. Probing
 * one press would measure only the first move and would miss the return path, which is the half of the
 * lock that a screen without one fails.
 */
const TAB_PROBE_STEPS = 5;

/**
 * Keyboard focus stays INSIDE the confirmation while it is open, and nothing steals it on open.
 *
 * ⚠️ Purpose: the second and third halves of the measured finding. Focus was measured resting on the
 * destructive trigger itself immediately after activation, again after a 900 ms settle and again through
 * the key route; and one `Tab` from there landed on `F8=Next Auth`, with
 * `popconfirm.contains(activeElement) === false`. So the surface took neither initial focus nor
 * containment, and a reviewer tabbing away from a question they had not answered could step the record
 * out from under it.
 *
 * ⚠️ Assumptions: the settle is awaited before focus is read, because the deleted defect was an ORDERING
 * -- an explicit `focus()` call that the browser applied after the overlay's own autofocus. Reading focus
 * only on the synchronous turn could pass against a screen that stole it one task later, which is
 * precisely what was measured. Waiting for the assertion to hold, then re-asserting, covers both.
 *
 * Assumptions: containment is probed with several tabs rather than one, and the legend's eighth-key
 * control is named in the assertion because it is the exact element the browser probe landed on.
 * @returns {Promise<void>} Resolves once focus has been probed on arrival and after tabbing.
 */
async function containsKeyboardFocusInsideTheConfirmation(): Promise<void> {
  const rendered = await renderDetail(DETAIL);

  await pressPfKey(rendered.user, 'PFK05');
  const dialog = await screen.findByRole('dialog');
  const cancel = within(dialog).getByRole('button', { name: /^Cancel$/u });

  await waitFor(
    /**
     * Asserts the declining choice holds focus once the surface has settled.
     * @returns {void} Nothing; the expectation throws until focus has arrived.
     */
    function theDecliningChoiceHoldsFocus(): void {
      expect(cancel).toHaveFocus();
    },
  );
  expect(document.activeElement).not.toBe(fraudTriggerBesideTheRecord());
  expect(document.activeElement).not.toBe(keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK05));

  /*
   * WHY : ⚠️ Assumptions: the containment probe needs a DOM that reports boxes, and this is an
   *       accommodation for the test environment rather than a statement about the screen. The dialog
   *       primitive's focus lock is real -- `@rc-component/dialog/es/Dialog/Content/Panel.js` L41 calls
   *       `useLockFocus`, gated on the wrapper computing `position: fixed`, which the design system's
   *       injected stylesheet satisfies here (measured) -- but the lock forces focus back by walking
   *       `getFocusNodeList`, and that filter drops every element `isVisible` rejects
   *       (`@rc-component/util/es/Dom/focus.js`, `Dom/isVisible.js`). jsdom performs no layout, so every
   *       box is zero, every element is judged invisible, and the lock finds nothing to return focus to.
   *       Installing a coherent box makes the lock's own list non-empty, which is what turns the browser
   *       finding into something measurable in this runner.
   * WHY : Alternatives Considered: asserting the lock's ENABLING conditions instead -- that the wrapper
   *       computes `position: fixed` and that the primitive defaults `focusTrap` to true. Rejected as an
   *       assertion about the library's configuration rather than about where focus goes, which is the
   *       property the finding measured and the property a reviewer depends on.
   */
  const restoreLayout = installMeasurableBoxes();
  try {
    const background = [
      fraudTriggerBesideTheRecord(),
      keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK03),
      keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK05),
      keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK08),
    ];
    let landingsInsideTheDialog = 0;

    for (let step = 0; step < TAB_PROBE_STEPS; step += 1) {
      await rendered.user.tab();
      const landed = document.activeElement;
      /*
       * WHY : ⚠️ Assumptions: the assertion is that focus never lands on a control BEHIND the dialog,
       *       and `document.body` is admitted as a landing because it is not a control at all. jsdom's
       *       tab implementation wraps through the document and parks focus on the body on the way
       *       round, and focusing the body fires no `focusin` -- so the primitive's lock, which returns
       *       focus from a `focusin` listener, cannot see that step. A real browser passes through its
       *       own chrome at the same point. Every step that lands on an ELEMENT is the measurable one,
       *       and each of those is asserted inside the dialog.
       */
      expect(background).not.toContain(landed);
      if (landed === document.body) {
        continue;
      }
      expect(dialog.contains(landed)).toBe(true);
      landingsInsideTheDialog += 1;
    }

    /*
     * WHY : Assumptions: a count is asserted as well as the per-step containment, because a screen that
     *       parked focus on the body for every step would satisfy every individual assertion above while
     *       containing nothing. Requiring the cycle to come back INTO the dialog is what makes the lock's
     *       return path the thing being measured.
     */
    expect(landingsInsideTheDialog).toBeGreaterThanOrEqual(3);
  } finally {
    restoreLayout();
  }

  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();
}

/**
 * Makes every attached element report a non-degenerate box, and returns the undo.
 *
 * Purpose: jsdom runs no layout engine, so `getBoundingClientRect` answers zeroes for everything and any
 * library that filters by visibility sees an empty document. A single coherent box is enough for the
 * dialog primitive's focus lock to have a list of controls to return focus to.
 *
 * Assumptions: the box is the same for every element and its numbers carry no meaning -- nothing in this
 * case measures a position or a size, only whether an element is judged visible at all. A case that did
 * measure geometry would need per-element boxes and would say so.
 * @returns {() => void} The function that restores the real, layout-free behaviour.
 */
function installMeasurableBoxes(): () => void {
  const spy = vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(
    /**
     * Answers one plausible box for any element the document contains.
     * @returns {DOMRect} A 120 by 24 box at the origin.
     */
    function measurableBox(this: Element): DOMRect {
      const width = this.isConnected ? 120 : 0;
      const height = this.isConnected ? 24 : 0;
      return {
        x: 0,
        y: 0,
        width,
        height,
        top: 0,
        left: 0,
        right: width,
        bottom: height,
        /**
         * Answers the box as a plain value, which the DOM interface requires.
         * @returns {object} The box's own numbers.
         */
        toJSON: (): object => ({ width, height }),
      };
    },
  );
  /**
   * Restores the real, layout-free `getBoundingClientRect`.
   * @returns {void} Nothing; the spy is removed as a side effect.
   */
  function restoreMeasurableBoxes(): void {
    spy.mockRestore();
  }
  return restoreMeasurableBoxes;
}

/**
 * Escape withdraws the confirmation and writes nothing.
 *
 * ⚠️ Purpose: Escape is the keyboard's own decline and it did not exist as a tested path before, because
 * the tooltip primitive the confirmation used had no dialog semantics to dismiss. The property asserted
 * is the pair: the question goes away, and the store is untouched -- browser validation measured zero
 * requests matching the fraud path after a dismissal and that is what this pins.
 *
 * ⚠️ Assumptions: the captured target is asserted DISCARDED rather than merely hidden, which is measured
 * by pressing the fifth key again afterwards and finding a fresh dialog that still writes nothing. A
 * surviving capture would be a write a later confirmation could deliver against a record nobody was
 * asked about, and the dismissal path is where that leak would live.
 * @returns {Promise<void>} Resolves once the withdrawal and the absence of a write are asserted.
 */
async function writesNothingWhenTheConfirmationIsWithdrawnByEscape(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'ADDED',
    message: PROGRAM_MESSAGES.COPAUS2C.ADD_SUCCESS,
  });

  await pressPfKey(rendered.user, 'PFK05');
  await screen.findByRole('dialog');

  await rendered.user.keyboard('{Escape}');
  await waitForTheFraudConfirmationToClose();

  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();
  expect(recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.fraudStatus)).toHaveTextContent(
    DETAIL.fraudMark,
  );

  await pressPfKey(rendered.user, 'PFK05');
  expect(await screen.findByRole('dialog')).toBeInTheDocument();
  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();
}

/**
 * The record announces the outstanding write, and the legend's keys stay present while it runs.
 *
 * ⚠️ Purpose: an in-flight write used to be reported by DISABLING the fifth and eighth keys, and a key
 * disabled in this hook is answered through its invalid-key channel -- so pressing the fifth key during
 * its own write painted `CCDA-MSG-INVALID-KEY`, a verbatim sentence that means something else entirely.
 * The keys now declare `busy` instead: they stay present, enabled and named, an early press is declined
 * silently, and the shared `REQUEST_IN_PROGRESS` sentence says what is happening. That is the terminal's
 * own input-inhibit behaviour, which announced a running task and withdrew nothing.
 *
 * Assumptions: the write is held UNSETTLED, because the announcement exists only while a request is
 * outstanding and a settled write would leave nothing to measure.
 * @returns {Promise<void>} Resolves once the announcement and the keys have been measured.
 */
async function announcesAnOutstandingWriteWithoutWithdrawingItsKeys(): Promise<void> {
  const held = heldWrite();
  const rendered = await renderDetail(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockReturnValue(held.promise);

  expect(screen.queryByText(REQUEST_IN_PROGRESS)).not.toBeInTheDocument();

  await pressPfKey(rendered.user, 'PFK05');
  await confirmFraudPrompt(rendered);

  expect(await screen.findByText(REQUEST_IN_PROGRESS)).toBeInTheDocument();
  expect(keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK05)).toBeEnabled();
  expect(keyLegendControl(AUTH_DETAIL_KEY_LABELS.PFK08)).toBeEnabled();
  expect(messageBand()).not.toHaveTextContent(INVALID_KEY_AS_RENDERED);

  /*
   * WHY : Assumptions: the busy key is pressed while the write is outstanding and the refusal is
   *       asserted SILENT -- no second write and no invalid-key sentence. That pair is the whole of the
   *       distinction between `busy` and `disabled` on this hook.
   */
  await pressPfKey(rendered.user, 'PFK05');
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledTimes(1);
  expect(messageBand()).not.toHaveTextContent(INVALID_KEY_AS_RENDERED);

  held.settle({ updateStatus: 'ADDED', message: PROGRAM_MESSAGES.COPAUS2C.ADD_SUCCESS });
  await waitFor(expectTheRecordReread);
  await waitFor(
    /**
     * Asserts the announcement is withdrawn once the write has settled.
     * @returns {void} Nothing; the expectation throws until the sentence has gone.
     */
    function theAnnouncementIsWithdrawn(): void {
      expect(screen.queryByText(REQUEST_IN_PROGRESS)).not.toBeInTheDocument();
    },
  );
}

/**
 * The safe choice holds focus when the confirmation opens, so a bare Enter cannot commit.
 *
 * ⚠️ Purpose: Enter is a working key on this application's every other surface, so a reviewer arrives
 * at this prompt with the habit of pressing it. If focus rested on the dangerous control, that habit
 * would report a live authorization as fraud with one keystroke and no reading. Both halves are
 * asserted: where focus lands, and that a bare Enter from there writes nothing.
 *
 * Assumptions: the write's absence is asserted rather than the prompt's closure, because closing is the
 * cancel control's own behaviour and the property that matters is that nothing was written.
 * @returns {Promise<void>} Resolves once focus and the absence of a write have been asserted.
 */
async function focusesTheSafeChoiceAndRefusesABareEnter(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'ADDED',
    message: PROGRAM_MESSAGES.COPAUS2C.ADD_SUCCESS,
  });

  await pressPfKey(rendered.user, 'PFK05');
  const cancel = await screen.findByRole('button', { name: /^Cancel$/u });

  expect(cancel).toHaveFocus();

  await rendered.user.keyboard('{Enter}');

  expect(vi.mocked(setAuthorizationFraudState)).not.toHaveBeenCalled();
}

/**
 * A second confirmation cannot be raised while the first write is still outstanding.
 *
 * ⚠️ Purpose: on a toggle the second write is the INVERSE of the first, because the direction is derived
 * from the mark -- so a duplicated confirmation does not merely repeat a write, it undoes one. The
 * screen must therefore admit exactly one write per confirmation and none at all while one is in
 * flight.
 *
 * Assumptions: the write is held UNSETTLED across the second attempt, because that is the only window
 * in which the duplicate is reachable at all -- once the write settles the record is re-read and the
 * transition is legitimately available again.
 * @returns {Promise<void>} Resolves once the single write has been asserted across both attempts.
 */
async function refusesASecondTransitionWhileOneIsOutstanding(): Promise<void> {
  const held = heldWrite();
  const rendered = await renderDetail(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockReturnValue(held.promise);

  await pressPfKey(rendered.user, 'PFK05');
  await confirmFraudPrompt(rendered);
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledTimes(1);

  /*
   * WHY : ⚠️ Refactoring Rationale: the withdrawal of the prompt is now asserted DIRECTLY, and the note
   *       that stood here explained why it could not be. `Popconfirm` left its closed popup mounted and
   *       hid it through a class whose rule lives in the design system's stylesheet, which this
   *       environment does not load -- so a query for the confirming control found it whether the prompt
   *       was showing or not. The dialog primitive parks its own markup once its leave animation reports
   *       finishing, which {@link waitForTheFraudConfirmationToClose} drives, so the confirmation's
   *       absence is now a measurable property of the screen.
   * WHY : Assumptions: the trigger's `disabled` attribute and the number of writes issued are still
   *       both asserted, because the three facts answer three different questions -- the prompt closed,
   *       the pointer path is withheld, and the second key press wrote nothing.
   */
  await waitForTheFraudConfirmationToClose();
  await pressPfKey(rendered.user, 'PFK05');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(fraudTriggerBesideTheRecord()).toBeDisabled();
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledTimes(1);

  held.settle({ updateStatus: 'ADDED', message: PROGRAM_MESSAGES.COPAUS2C.ADD_SUCCESS });
  await waitFor(expectTheRecordReread);
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledTimes(1);
}

/**
 * Waits for the fraud confirmation to leave the document, ending its leave animation by hand.
 *
 * ⚠️ Assumptions: the closing ANIMATION has to be ended here, and that is an accommodation for the test
 * DOM rather than a statement about the screen. The dialog primitive parks its markup only once the
 * leave animation reports finishing, and it listens for a native end event on the panel
 * (`ui/node_modules/@rc-component/motion/es/hooks/useDomMotionEvents.js` L21 to L22). jsdom applies the
 * class that starts the animation but never runs one and never fires that event, so the panel would sit
 * in `ant-zoom-leave-active` for the whole of a wait window and the case would fail on a timeout
 * describing a dialog the application has already closed.
 *
 * ⚠️ Assumptions: the event fired is `transitionend` and NOT `animationend`, which the migrated
 * user-delete confirmation measured before this one. The animation library resolves its own event names
 * by probing for a constructor and a style property, and jsdom exposes no `AnimationEvent` -- so the
 * animation name it settles on is the vendor-prefixed `webkitAnimationEnd`, which Testing Library's
 * helper does not emit, while `TransitionEvent` does exist and keeps the transition name unprefixed.
 * Both events reach the one handler and it does not inspect the event's type.
 *
 * Assumptions: the event is fired on every RETRY rather than once beforehand, because the library only
 * accepts an end event once its own step queue has reached the active step and that step is scheduled
 * through `requestAnimationFrame` -- so a single event fired immediately after the accept can arrive
 * while the leave is still starting and be discarded. Re-firing costs nothing once the panel has gone,
 * because the query then answers null.
 *
 * Alternatives Considered: asserting the leave CLASS instead of the closed state. Rejected as an
 * assertion about the animation library rather than about the screen. Also considered: asserting only
 * that no write was issued -- rejected because "the question is gone" is exactly the property a reviewer
 * depends on, and the `Popconfirm` form of these cases never asserted it, which is how a permanently
 * mounted confirmation went unnoticed.
 * @returns {Promise<void>} Resolves once no dialog is in the document.
 */
async function waitForTheFraudConfirmationToClose(): Promise<void> {
  await waitFor(
    /**
     * Ends the leave animation if one is still running, then asserts the confirmation has gone.
     * @returns {void} Nothing; the expectation throws until the dialog is unreachable.
     */
    function theConfirmationIsClosed(): void {
      const leaving = screen.queryByRole('dialog');

      if (leaving !== null) {
        fireEvent.transitionEnd(leaving);
      }

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    },
  );
}

/**
 * Locates the fraud trigger rendered beside the record rather than the legend's or the dialog's.
 *
 * ⚠️ Assumptions: THREE controls now carry the identical accessible name -- the mapset paints one
 * legend for the fifth key, this screen renders a second control for it beside the record, and the
 * confirmation's accept carries the same legend text so the control that writes says what it writes.
 * The one wanted is identified by sitting outside both the legend landmark and any open dialog, which
 * is a structural test rather than a positional one.
 * @returns {HTMLElement} The fraud trigger beside the record.
 * @throws {Error} If no such control is rendered, which means the record lost its own trigger.
 */
function fraudTriggerBesideTheRecord(): HTMLElement {
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const dialog = screen.queryByRole('dialog');
  const beside = screen.getAllByRole('button', { name: AUTH_DETAIL_KEY_LABELS.PFK05 }).find(
    /**
     * Reports whether one control sits outside both the legend landmark and the confirmation.
     * @param {HTMLElement} control - A control carrying the fifth key's name.
     * @returns {boolean} `true` when neither the legend nor an open dialog contains it.
     */
    function sitsBesideTheRecord(control: HTMLElement): boolean {
      return !legend.contains(control) && (dialog === null || !dialog.contains(control));
    },
  );
  if (beside === undefined) {
    throw new Error('the record renders no fraud trigger of its own');
  }
  return beside;
}

/**
 * Asserts the record has been read a second time, which is what follows a settled transition.
 * @returns {void} Nothing; the assertion either passes or the `waitFor` retries.
 */
function expectTheRecordReread(): void {
  expect(vi.mocked(getPendingAuthorizationScreen)).toHaveBeenCalledTimes(2);
}

/**
 * A write whose settlement the case controls.
 *
 * Assumptions: the resolver is captured out of the executor rather than taken from a deferred
 * construct, because the executor runs synchronously inside the `Promise` constructor -- so the resolver
 * is in place before this function returns and a case can never settle nothing.
 * @returns {{ promise: Promise<FraudMarkResponse>; settle: (value: FraudMarkResponse) => void }} The
 *   unsettled write and its settler.
 */
function heldWrite(): {
  readonly promise: Promise<FraudMarkResponse>;
  readonly settle: (value: FraudMarkResponse) => void;
} {
  /** Resolver of the promise below, replaced the moment the executor runs. */
  let capture: (value: FraudMarkResponse) => void = refuseUncapturedSettlement;

  /**
   * Records the promise's resolver so the case can reach it.
   * @param {(value: FraudMarkResponse) => void} resolve - The resolver the promise supplies.
   * @returns {void} Nothing; the resolver is recorded above.
   */
  function captureResolver(resolve: (value: FraudMarkResponse) => void): void {
    capture = resolve;
  }

  /**
   * Settles the held write.
   * @param {FraudMarkResponse} value - What the write answers with.
   * @returns {void} Nothing; the promise settles.
   */
  function settle(value: FraudMarkResponse): void {
    capture(value);
  }

  return { promise: new Promise<FraudMarkResponse>(captureResolver), settle };
}

/**
 * Stands in for the resolver until the executor supplies the real one.
 * @returns {void} Nothing; it is never reached, because the executor runs synchronously.
 * @throws {Error} Always, because reaching it means a case settled a write whose resolver the promise
 *   executor had not yet handed over -- which would be a defect in the fixture rather than in a screen.
 */
function refuseUncapturedSettlement(): void {
  throw new Error('the held write was settled before its resolver was captured');
}

/**
 * Asserts each direction of the toggle reports its own sentence and calls the fraud operation.
 *
 * Assumptions: the two sentences are DISTINCT and neither stands in for the other, because the branch
 * at `cbl/COPAUS1C.cbl` L534 to L538 is explicit -- the removal sentence at L535 when the row ended
 * withdrawn and the report sentence at L537 when it ended reported. One sentence for both directions
 * would tell a reviewer the opposite of what happened half the time.
 *
 * Assumptions: the direction is derived from the mark already on the row rather than from a remembered
 * toggle, which is how `MARK-AUTH-FRAUD` decides it -- so an unmarked row asks for a report and a row
 * already reported asks for a withdrawal, and the two fixtures differ only in that mark.
 *
 * Assumptions: the write goes to the FRAUD operation and not to the detail read, which the migration
 * plan assigns to a controller of its own sourced from `COPAUS2C`, "Mark Authorization Message Fraud".
 * The read operation is called too -- twice, for the first read and the re-read the reference performs
 * after the write -- so the case asserts which function received the transition rather than that only
 * one function was called.
 * @returns {Promise<void>} Resolves once both directions have been observed.
 */
async function reportsEachFraudDirectionWithItsOwnSentence(): Promise<void> {
  const marking = await renderDetail(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'ADDED',
    message: PROGRAM_MESSAGES.COPAUS2C.ADD_SUCCESS,
  });
  answersTheNextReadWith(REPORTED_DETAIL);

  await pressPfKey(marking.user, 'PFK05');
  await confirmFraudPrompt(marking);

  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenLastCalledWith(SELECTOR, {
    action: FRAUD_REPORTED,
  });
  expect(await screen.findByText(DETAIL_MESSAGES.AUTH_MARKED_FRAUD)).toBeInTheDocument();
  expect(screen.queryByText(DETAIL_MESSAGES.AUTH_FRAUD_REMOVED)).not.toBeInTheDocument();

  marking.unmount();
  vi.mocked(getPendingAuthorizationScreen).mockReset();
  vi.mocked(setAuthorizationFraudState).mockReset();

  const withdrawing = await renderDetail(REPORTED_DETAIL);
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'UPDATED',
    message: PROGRAM_MESSAGES.COPAUS2C.UPDT_SUCCESS,
  });
  answersTheNextReadWith(DETAIL);

  await pressPfKey(withdrawing.user, 'PFK05');
  await confirmFraudPrompt(withdrawing);

  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenLastCalledWith(SELECTOR, {
    action: FRAUD_WITHDRAWN,
  });
  expect(await screen.findByText(DETAIL_MESSAGES.AUTH_FRAUD_REMOVED)).toBeInTheDocument();
  expect(screen.queryByText(DETAIL_MESSAGES.AUTH_MARKED_FRAUD)).not.toBeInTheDocument();
}

/**
 * The status that leaves a write's fate unknown: the condition may clear, and repeating is not safe.
 *
 * Assumptions: 504, which `TRANSIENT_STATUSES` in `ui/src/api/client.ts` L223 lists and
 * `REPEATABLE_METHODS` L235 refuses for a `PUT`. The pair is the point -- `remedyFor` L969 to L985
 * records that a gateway failure on a write is transient AND not repeatable "because the write may
 * already have been applied", which is the one arrangement that can leave the glass contradicting the
 * store.
 */
const UNCERTAIN_WRITE_STATUS = 504;

/** The status a service answers when it has DESCRIBED the refusal, so nothing was applied. */
const DESCRIBED_REFUSAL_STATUS = 400;

/** The sentence a described refusal carries, standing in for a service-authored one. */
const DESCRIBED_REFUSAL_SENTENCE = 'Unable to Update Authorization ...';

/**
 * The claim the detail screen hands a fraud outcome over under, composed as both screens compose it.
 *
 * Assumptions: built from the exported summary route and the same `#fraud` suffix rather than written
 * out as `'/authorizations#fraud'`, so a case cannot pass against a screen that retained under a name
 * the summary does not collect.
 */
const FRAUD_HANDOVER_CLAIM = `${AUTHORIZATION_SUMMARY_ROUTE}#fraud`;

/**
 * Answers the NEXT read with a given rendering, leaving later reads to the standing answer.
 *
 * ⚠️ Purpose: a successful fraud write is followed by a re-read, and the row that comes back is what
 * the operator is about to look at. `renderDetail` installs one standing answer for every read, so
 * without this the re-read returns the row as it was BEFORE the write -- a service that ignored its own
 * contract, since `services/authorization-service/src/main/resources/openapi/authorization-api.yaml`
 * L2881 to L2895 composes `fraudMark` from the persistent status character and therefore cannot report
 * an unmarked row once a mark has been stored. Queuing the applied row is what makes the fixture
 * describe the service the contract describes.
 *
 * Assumptions: `mockResolvedValueOnce` and not a replacement of the standing answer, so the case still
 * measures one specific read rather than changing every read that follows it.
 * @param {PendingAuthDetailScreen} applied - The rendering the service would answer the re-read with.
 * @returns {void} Nothing; the answer is queued on the mock as a side effect.
 */
function answersTheNextReadWith(applied: PendingAuthDetailScreen): void {
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValueOnce(applied);
}

/**
 * A sentence the service carries on a row, standing in for whatever it says about one.
 *
 * Assumptions: a service-shaped sentence rather than a catalogued one, because the case needs the ROW's
 * own message to be distinguishable from the write's confirmation and `PROGRAM_MESSAGES.COPAUS1C`
 * carries only the two write confirmations and a boundary sentence, none of which would prove which of
 * the two sources the band took.
 */
const ROW_CARRIED_SENTENCE = 'Authorization is not marked fraudulent ...';

/**
 * Asserts a success is NOT announced while the row on the glass contradicts it.
 *
 * ⚠️ Purpose: this is the cross-screen confirmation rule with teeth, and this is the screen it has
 * teeth on -- `Fraud Status:` is one of the twelve captions, so the value the sentence would contradict
 * is displayed rather than merely held. A write that answers success and a re-read that comes back
 * unmarked is not a contrived pairing: the write and the read are two requests, and only the second one
 * describes what an operator is about to look at. Without this case a screen could paint
 * `AUTH MARKED FRAUD...` above a row reading `-` and every other assertion in this file would pass.
 *
 * Assumptions: the contradiction is built by answering the RE-READ with the unchanged mark, which is
 * exactly what a write that did not take would produce, rather than by stubbing the predicate. The
 * write is answered successfully, so nothing in the failure paths is exercised and the case cannot pass
 * for the wrong reason.
 *
 * Assumptions: `mockResolvedValueOnce` is queued AFTER the opening read, so it answers the second call
 * only -- a once-value queued before rendering would answer the opening read and the case would measure
 * a first render rather than a re-read.
 * @returns {Promise<void>} Resolves once the band has been shown to carry the row's sentence, not the
 *   write's.
 */
async function refusesToConfirmAWriteTheRowContradicts(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'ADDED',
    message: PROGRAM_MESSAGES.COPAUS2C.ADD_SUCCESS,
  });
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValueOnce({
    ...DETAIL,
    message: ROW_CARRIED_SENTENCE,
  });

  await pressPfKey(rendered.user, 'PFK05');
  await confirmFraudPrompt(rendered);

  await waitFor(expectTheRecordReread);
  expect(await screen.findByText(ROW_CARRIED_SENTENCE)).toBeInTheDocument();
  expect(messageBand()).toHaveTextContent(ROW_CARRIED_SENTENCE);
  expect(messageBand()).not.toHaveTextContent(DETAIL_MESSAGES.AUTH_MARKED_FRAUD);
  expect(recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.fraudStatus)).toHaveTextContent(
    DETAIL.fraudMark,
  );
}

/**
 * Asserts the confirmation IS announced when the row agrees with the write.
 *
 * ⚠️ Purpose: the other half of the test above, and the reason it is a pair. A screen that simply
 * stopped announcing write confirmations would satisfy the refusal case perfectly and would have lost
 * the two verbatim sentences `COPAUS1C.cbl` L531 to L538 paints -- so the refusal has to be shown to be
 * conditional on the disagreement rather than unconditional.
 *
 * Assumptions: the agreeing row is {@link REPORTED_DETAIL}, the composed mark this file already uses
 * for a reported authorization, and the agreement is ASSERTED through `nextFraudAction` rather than
 * assumed -- so the fixture cannot silently encode a second reading of the composed tag format.
 * @returns {Promise<void>} Resolves once the confirmation has been seen on the band.
 */
async function confirmsAWriteTheRowAgreesWith(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  expect(nextFraudAction(DETAIL.fraudMark)).toBe(FRAUD_REPORTED);
  expect(nextFraudAction(REPORTED_DETAIL.fraudMark)).toBe(FRAUD_WITHDRAWN);
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'ADDED',
    message: PROGRAM_MESSAGES.COPAUS2C.ADD_SUCCESS,
  });
  vi.mocked(getPendingAuthorizationScreen).mockResolvedValueOnce({
    ...REPORTED_DETAIL,
    message: ROW_CARRIED_SENTENCE,
  });

  await pressPfKey(rendered.user, 'PFK05');
  await confirmFraudPrompt(rendered);

  await waitFor(expectTheRecordReread);
  expect(await screen.findByText(DETAIL_MESSAGES.AUTH_MARKED_FRAUD)).toBeInTheDocument();
  expect(messageBand()).toHaveTextContent(DETAIL_MESSAGES.AUTH_MARKED_FRAUD);
  expect(messageBand()).not.toHaveTextContent(ROW_CARRIED_SENTENCE);
}

/**
 * Asserts a refusal that may have landed causes the row to be RE-READ rather than left as it was.
 *
 * ⚠️ Purpose: this is the contradiction a confirmation surface exists to prevent, and it is the one
 * failure mode a "leave the rendering alone" refusal path cannot avoid. A gateway that gives up after
 * the service committed answers 504 while the row is now reported -- so a screen that only paints a
 * sentence leaves `Fraud Status:` reading the OLD tag beside a message about a failure, and a reviewer
 * reads that as "nothing happened" for a row that has been reported.
 *
 * Assumptions: the discriminator asserted is the pair transient-and-not-repeatable, which is why this
 * case uses 504 and its sibling below uses 400. Both are refusals and both reject; what differs is
 * whether the service DESCRIBED the outcome, and the shared predicates are what carry that difference
 * into this screen rather than a status list copied into it.
 *
 * Assumptions: the re-read is counted rather than inferred from the rendering, because the fixture
 * answers the re-read with the same record -- so the repainted values are indistinguishable from the
 * unchanged ones, and only the call count says the screen went and asked.
 * @returns {Promise<void>} Resolves once the re-read and the sentence have been observed.
 */
async function reReadsTheRowWhenTheRefusalMayHaveLanded(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockRejectedValue(
    new ApiRequestError(
      'PROBLEM',
      UNCERTAIN_WRITE_STATUS,
      apiError({ status: UNCERTAIN_WRITE_STATUS }),
      'the gateway gave up on the write',
    ),
  );

  await pressPfKey(rendered.user, 'PFK05');
  await confirmFraudPrompt(rendered);

  await waitFor(expectTheRecordReread);
  /*
   * WHY : ⚠️ Refactoring Rationale: the sentence expected here was the shared abend replacement and is
   *       now `TRANSIENT_FAILURE_TRY_AGAIN`. The screen used to report every message-less refusal as an
   *       abend because the catalogue declared no sentence about reaching the service; it now declares
   *       two, and 504 is on the client's transient list -- so the operator is told the service is
   *       momentarily unavailable rather than that the program failed. The status is the same status
   *       this case already chose for the re-read behaviour, so one fixture measures both.
   */
  expect(messageBand()).toHaveTextContent(TRANSIENT_FAILURE_TRY_AGAIN);
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledTimes(1);
  expect(screen.queryByText(DETAIL_MESSAGES.AUTH_MARKED_FRAUD)).not.toBeInTheDocument();
}

/**
 * Asserts a refusal the service described leaves the row alone and paints the service's own sentence.
 *
 * ⚠️ Purpose: the other half of the discriminator above, and the reason it is a pair rather than a
 * single rule. Re-reading after EVERY refusal would be the simpler screen and a worse one: the re-read
 * carries its own announcement, so a service answering 400 for one field and then failing the re-read
 * would replace its own field refusal with the read's failure sentence -- and the reviewer would lose
 * the only sentence that said what was wrong.
 *
 * Assumptions: the sentence asserted is the SERVICE's, not the shared replacement, which is what
 * establishes that a described refusal is passed through rather than redacted. The read count staying
 * at one is what establishes that nothing was re-read to produce it.
 * @returns {Promise<void>} Resolves once the sentence and the absent re-read have been observed.
 */
async function leavesTheRowUnreadWhenTheServiceDescribesTheRefusal(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockRejectedValue(
    new ApiRequestError(
      'PROBLEM',
      DESCRIBED_REFUSAL_STATUS,
      apiError({ status: DESCRIBED_REFUSAL_STATUS, message: DESCRIBED_REFUSAL_SENTENCE }),
      'the service refused the write',
    ),
  );

  await pressPfKey(rendered.user, 'PFK05');
  await confirmFraudPrompt(rendered);

  expect(await screen.findByText(DESCRIBED_REFUSAL_SENTENCE)).toBeInTheDocument();
  expect(messageBand()).toHaveTextContent(DESCRIBED_REFUSAL_SENTENCE);
  expect(vi.mocked(getPendingAuthorizationScreen)).toHaveBeenCalledTimes(1);
  expect(recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.fraudStatus)).toHaveTextContent(
    DETAIL.fraudMark,
  );
}

/**
 * Asserts a fraud outcome that settles after the reviewer has left is HANDED ON, not discarded.
 *
 * ⚠️ Purpose: PF3 is not refused while the write is outstanding, so leaving mid-write is a reachable
 * operator action and not a contrived one. Every setter in the continuation then runs against a
 * component React has discarded, silently -- which is exactly the defect
 * `retainOutcomeAcrossNavigation` in `ui/src/api/client.ts` was added for, measured on four separate
 * writes. Without this case a screen could drop the hand-over and every visible assertion would still
 * pass, because there is nothing on the glass to look at.
 *
 * Assumptions: the write is HELD and settled after the unmount, which is the only ordering that
 * reproduces the defect. Settling first and unmounting afterwards would let the mounted screen paint
 * the sentence and the retention would never be reached.
 *
 * Assumptions: the outcome is collected inside `waitFor` rather than after it. Collection REMOVES the
 * entry, so a failed attempt takes nothing and the successful one takes it exactly once -- which is the
 * property that makes the mechanism safe to poll.
 * @returns {Promise<void>} Resolves once the retained outcome has been collected and read.
 */
async function handsTheFraudOutcomeOnWhenNobodyIsLeftToReadIt(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  const write = heldWrite();
  vi.mocked(setAuthorizationFraudState).mockReturnValue(write.promise);

  await pressPfKey(rendered.user, 'PFK05');
  await confirmFraudPrompt(rendered);

  rendered.unmount();
  write.settle({ updateStatus: 'ADDED', message: PROGRAM_MESSAGES.COPAUS2C.ADD_SUCCESS });

  let handed: RetainedOutcome<FraudTransitionHandover> | undefined;
  await waitFor(
    /**
     * Claims the handed-on outcome once the unmounted screen has published it.
     *
     * Assumptions: the claim is kept in the enclosing binding with `??=`, because a claim consumes the
     * outcome -- a second successful claim would answer `undefined` and the retry would then fail on an
     * outcome it had already seen.
     * @returns {void} Nothing; the expectation throws until an outcome has been claimed.
     */
    function theOutcomeHasBeenHandedOn(): void {
      handed ??= claimRetainedOutcome<FraudTransitionHandover>(FRAUD_HANDOVER_CLAIM);
      expect(handed).toBeDefined();
    },
  );

  expect(handed?.settled).toBe('COMPLETED');
  expect(handed?.settled === 'COMPLETED' ? handed.value.text : null).toBe(
    DETAIL_MESSAGES.AUTH_MARKED_FRAUD,
  );
  expect(handed?.settled === 'COMPLETED' ? handed.value.severity : null).toBe('success');
}

/**
 * Asserts a refused fraud write leaves the record as it was and issues no commit of its own.
 *
 * Refactoring Rationale: the baseline reaches this state with `EXEC CICS SYNCPOINT ROLLBACK` at
 * `cbl/COPAUS1C.cbl` L540, having joined a segment replacement and a fraud-table write with a
 * two-phase commit across IMS and Db2. Rule T5 maps a rollback to exception propagation, and the
 * migration collapses both records into one PostgreSQL schema so the distributed transaction is
 * ELIMINATED rather than emulated -- the service performs both writes in a single local transaction.
 * The client therefore has no notion of a commit or a rollback at all: it issues one call, and on a
 * refusal it paints a sentence and leaves the rendering untouched. The atomicity moved to the server;
 * only the message crossed over, and even that is withheld and replaced.
 *
 * Assumptions: the sentence asserted is the shared abend replacement rather than the baseline's
 * composed rollback text, because that composition is one of the five entries the register above
 * withholds. This is the rendered consequence of that register entry.
 *
 * Assumptions: the record is asserted UNCHANGED by re-reading the fraud status cell, which is the one
 * value the write would have moved. A case that asserted only the message would pass against a screen
 * that had patched its rendered tag optimistically before the refusal arrived.
 * @returns {Promise<void>} Resolves once the refusal has been observed.
 */
async function leavesTheRecordUnchangedWhenTheWriteIsRefused(): Promise<void> {
  const rendered = await renderDetail(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockRejectedValue(
    new Error('the fraud transition was refused'),
  );

  await pressPfKey(rendered.user, 'PFK05');
  await confirmFraudPrompt(rendered);

  expect(await screen.findByText(UNEXPECTED_ABEND_OCCURRED)).toBeInTheDocument();
  expect(messageBand()).toHaveTextContent(UNEXPECTED_ABEND_OCCURRED);
  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledTimes(1);
  expect(recordValueCellFor(AUTH_DETAIL_FIELD_LABELS.fraudStatus)).toHaveTextContent(
    DETAIL.fraudMark,
  );
  expect(screen.queryByText(DETAIL_MESSAGES.AUTH_MARKED_FRAUD)).not.toBeInTheDocument();
  expect(screen.queryByText(DETAIL_MESSAGES.AUTH_FRAUD_REMOVED)).not.toBeInTheDocument();
}

/**
 * Asserts a refused read paints the replacement sentence rather than a composed diagnostic.
 *
 * Assumptions: the rejection carries no problem document, which is the condition the shared abend
 * sentence exists for -- the screen prefers a sentence the service sent and falls back to this one when
 * it has none. That fallback is what the register's `replacement` member names, so this case is the
 * rendered half of the redaction: the operator learns the read failed and learns nothing about the IMS
 * status code, which goes to a server-side log keyed by the correlation identifier instead.
 * @returns {Promise<void>} Resolves once the replacement sentence is on the band.
 */
async function paintsTheReplacementSentenceWhenAReadIsRefused(): Promise<void> {
  vi.mocked(getPendingAuthorizationScreen).mockRejectedValue(new Error('the read was refused'));
  await renderInAppShell(<AuthDetailScreen />, {
    initialEntries: [DETAIL_ENTRY],
    routePath: AUTHORIZATION_DETAIL_ROUTE,
  });

  expect(await screen.findByText(UNEXPECTED_ABEND_OCCURRED)).toBeInTheDocument();
  expect(messageBand()).toHaveTextContent(UNEXPECTED_ABEND_OCCURRED);
}

/**
 * Asserts a signed-on non-administrator reaches this screen and may use its toggle.
 *
 * Assumptions: this screen is NOT administrative, which is measured rather than assumed.
 * `app/cpy/COMEN02Y.cpy` lists `'Pending Authorization View'` as main-menu option eleven at L86 to L88
 * and sets its user-type filler to `'U'` at L90 -- as it does for all eleven main-menu options, not one
 * of which is `'A'`. The route table states the same conclusion as data, so both are asserted: an
 * administrative access class here would be a behavioural divergence the baseline does not support.
 *
 * Assumptions: the session is established through the harness's identity helper and its groups are
 * passed through the token, because `ui/src/hooks/useAuth.ts` publishes no setter for the groups or the
 * user type and there is no context provider to mount -- authority derives solely from the signed
 * `cognito:groups` claim, so a test cannot grant itself administrative authority and does not try.
 * @returns {Promise<void>} Resolves once the non-administrator has completed a transition.
 */
async function admitsANonAdministrativeOperator(): Promise<void> {
  const session = await seedSession({ groups: [CARDDEMO_USER_GROUP] });
  expect(session.result.current.signedOn).toBe(true);
  expect(session.result.current.isAdmin).toBe(false);
  expect(session.result.current.groups).toContain(CARDDEMO_USER_GROUP);
  expect(session.result.current.groups).not.toContain(CARDDEMO_ADMIN_GROUP);

  const detailRoute = ROUTE_TABLE.find(isTheDetailRoute);
  expect(detailRoute?.access).toBe('authenticated');
  expect(detailRoute?.program).toBe('COPAUS1C');
  expect(AUTHORIZATION_DETAIL_ROUTE).toBe(AUTH_DETAIL_PATH);

  const rendered = await renderDetail(DETAIL);
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'ADDED',
    message: PROGRAM_MESSAGES.COPAUS2C.ADD_SUCCESS,
  });
  answersTheNextReadWith(REPORTED_DETAIL);

  await pressPfKey(rendered.user, 'PFK05');
  await confirmFraudPrompt(rendered);

  expect(vi.mocked(setAuthorizationFraudState)).toHaveBeenCalledWith(SELECTOR, {
    action: FRAUD_REPORTED,
  });
  expect(await screen.findByText(DETAIL_MESSAGES.AUTH_MARKED_FRAUD)).toBeInTheDocument();
}

/**
 * Reports whether one route-table row is this screen's route.
 * @param {(typeof ROUTE_TABLE)[number]} entry - The route-table row to test.
 * @returns {boolean} `true` when the row is the authorization detail route.
 */
function isTheDetailRoute(entry: (typeof ROUTE_TABLE)[number]): boolean {
  return entry.path === AUTH_DETAIL_PATH;
}

/**
 * Asserts the whole composite selector reaches the service and no session member carries it.
 *
 * Assumptions: the selector stands for a COMPOSITE row key -- an account, a packed authorization date
 * and a packed authorization time, declared together as `PA-AUTHORIZATION-KEY` at `cpy/CIPAUDTY.cpy`
 * L19 to L21 -- which is why the route parameter is named for a key rather than for an identifier. A
 * screen that sent only the account portion would address a whole account's worth of authorizations,
 * so the case asserts the argument is the whole value and is none of its parts.
 *
 * Assumptions: selection context is a REQUEST parameter and never a session member, which is the
 * structural half of eliminating the pseudo-conversational state: the baseline carried the selected
 * row in the passed session structure, where a client could echo whatever it liked, and the target
 * carries it in the path where every request is independently authorizable. This case asserts the
 * absence directly -- no value the session reading exposes is the selector.
 * @returns {Promise<void>} Resolves once the selector's travel and its absence have been measured.
 */
async function sendsTheWholeCompositeSelectorAndKeepsItOutOfTheSession(): Promise<void> {
  const session = await seedSession({ groups: [CARDDEMO_USER_GROUP] });
  const rendered = await renderDetail(DETAIL);
  vi.mocked(getNextPendingAuthorization).mockResolvedValue({
    authorization: null,
    endOfData: true,
    message: null,
  });
  vi.mocked(setAuthorizationFraudState).mockResolvedValue({
    updateStatus: 'ADDED',
    message: PROGRAM_MESSAGES.COPAUS2C.ADD_SUCCESS,
  });

  await pressPfKey(rendered.user, 'PFK08');
  await pressPfKey(rendered.user, 'PFK05');
  await confirmFraudPrompt(rendered);

  for (const spy of [
    vi.mocked(getPendingAuthorizationScreen),
    vi.mocked(getNextPendingAuthorization),
    vi.mocked(setAuthorizationFraudState),
  ]) {
    expect(spy).toHaveBeenCalled();
    expect(spy.mock.calls.every(addressesTheWholeSelector)).toBe(true);
  }

  expect(SELECTOR).toContain(SELECTED_ACCOUNT_ID);
  expect(SELECTOR).toContain(SELECTED_AUTH_DATE);
  expect(SELECTOR).toContain(SELECTED_AUTH_TIME);
  expect(SELECTOR).not.toBe(SELECTED_ACCOUNT_ID);

  const reading = session.result.current;
  expect(reading.userId).not.toBe(SELECTOR);
  expect(reading.groups).not.toContain(SELECTOR);
  expect(JSON.stringify(reading)).not.toContain(SELECTOR);
}

/**
 * Reports whether one recorded call addressed the whole composite selector.
 *
 * Assumptions: the FIRST argument is the address for all three operations of this screen, which the
 * transport module fixes -- each passes the selector through verbatim as one path value and reads
 * nothing from it.
 * @param {readonly unknown[]} call - One recorded argument list.
 * @returns {boolean} `true` when the call's first argument is the whole selector.
 */
function addressesTheWholeSelector(call: readonly unknown[]): boolean {
  return call[0] === SELECTOR;
}

/**
 * Asserts refusals surface on the message band and never as a field error on the record.
 *
 * Refactoring Rationale: `app/cpy/CSSETATY.cpy` L17 to L27 is the baseline's field-error template -- it
 * moves `DFHRED` into a field's colour attribute when that field's validation flag is not-OK or blank,
 * and additionally moves a literal asterisk into the field when it is blank at L24 -- and the target
 * renders that as a `Form.Item` carrying an error status, help text and the same marker. None of it
 * applies HERE, because the template is a per-field affordance and this screen has no field to type
 * into: every one of its fifty-four map fields is `ASKIP`. So the whole error surface is the row-23
 * band, and this case asserts the negative half of the contract rather than assuming it.
 *
 * Refactoring Rationale: the baseline additionally gates that highlight on the re-entry discriminator
 * at `app/cpy/COCOM01Y.cpy` L29 to L31, and the discriminator DISAPPEARS in the target -- a stateless
 * handler has no first-entry-versus-re-entry distinction to make, so error presentation is driven
 * purely by the response body. That is why the band is populated by a refusal alone here, with no
 * remembered turn count involved.
 *
 * Assumptions: the two builders exercised below are the harness's, and the blank state is the one that
 * carries the marker obligation -- which is precisely why the field-error builder defaults to the
 * not-OK state and a case wanting the blank state says so. Neither obligation has anywhere to land on
 * this screen, and the marker's own token is asserted present in the bridge so the contract stays
 * findable for the screens that do have fields.
 * @returns {Promise<void>} Resolves once the band and the absences have been measured.
 */
async function surfacesRefusalsOnTheBandAndNotOnTheRecord(): Promise<void> {
  const blankFieldRefusal = fieldError('cardNumber', INVALID_KEY_PRESSED, 'BLANK');
  const problem = apiError({
    status: 400,
    message: INVALID_KEY_PRESSED,
    fieldErrors: [blankFieldRefusal],
  });
  expect(blankFieldRefusal.state).toBe('BLANK');
  expect(problem.fieldErrors).toHaveLength(1);
  expect(FIELD_ERROR_TOKENS.blankMarker).toBe('*');
  expect(FIELD_ERROR_TOKENS.errorColor).toBe(BMS_TEXT_COLOR_TOKENS.RED);

  const rendered = await renderDetail(DETAIL);
  const owner = rendered.container.ownerDocument;

  await pressPfKey(rendered.user, 'PFK12');

  expect(messageBand()).toHaveTextContent(INVALID_KEY_AS_RENDERED);
  expect(owner.querySelectorAll('.ant-form-item-has-error')).toHaveLength(0);
  expect(owner.querySelectorAll('.ant-form-item-explain-error')).toHaveLength(0);
  expect(owner.querySelectorAll('[aria-invalid="true"]')).toHaveLength(0);
  for (const cell of owner.querySelectorAll('.ant-descriptions-item-content')) {
    expect(cell.textContent).not.toBe(FIELD_ERROR_TOKENS.blankMarker);
  }

  /*
   * WHY : Assumptions: the band's WIDTH is deliberately not asserted here, and the three widths in play
   *       are recorded instead so a reader is not left to rediscover them. This mapset's `ERRMSG` is
   *       `LENGTH=78` at `COPAU01.bms` L284 to L287 and the program's `WS-MESSAGE` is `PIC X(80)` at
   *       `cbl/COPAUS1C.cbl` L37, while the shared band implements the 75-character
   *       `CCARD-ERROR-MSG` / `CCARD-RETURN-MSG` contract that `app/cpy/CVCRD01Y.cpy` declares at L28
   *       and L29 -- the width the majority of these screens carry, with the off state at L30. The
   *       screen delegates the difference to the band by naming its mapset, so one contract keeps one
   *       implementation. Alternatives Considered: asserting the resolved width from this file.
   *       Rejected because the band's width contract belongs to the band's own test file, and a second
   *       site asserting it would make a deliberate change to the shared band fail in a screen test
   *       that has no opinion about it.
   */
}

/**
 * Registers every case of this file with the runner.
 *
 * Assumptions: a named function rather than an inline arrow, for the reason every file in this package
 * records -- the documentation gate requires a block on a function expression in any position, and
 * Prettier detaches a block comment attached to an inline argument.
 * @returns {void} Nothing; the registrations are the effect.
 */
function authorizationDetailCases(): void {
  it('declares every painted field at its mapset width', declaresEveryPaintedFieldAtItsMapsetWidth);
  it('declares the header band at its mapset widths', declaresTheHeaderBandAtItsMapsetWidths);
  it(
    'renders the record as descriptions and not as inputs',
    rendersTheRecordAsDescriptionsAndNotAsInputs,
  );
  it(
    'carries no initial cursor and no non-display field',
    carriesNoInitialCursorAndNoNonDisplayField,
  );
  it('decodes every decline-reason code', decodesEveryDeclineReasonCode);
  it('splits the composed reason at four characters', splitsTheComposedReasonAtFourCharacters);
  it(
    'carries money as an exact decimal string in the code face',
    carriesMoneyAsAnExactDecimalStringInTheCodeFace,
  );
  it(
    'renders an amount wider than the mapset field in full',
    rendersAnAmountWiderThanTheMapsetFieldInFull,
  );
  it(
    'keeps coded values inside their declared domains',
    keepsCodedValuesInsideTheirDeclaredDomains,
  );
  it(
    'names the merchant category code without the baseline misspelling',
    namesTheMerchantCategoryCodeWithoutTheBaselineMisspelling,
  );
  it(
    'masks the card number and carries no other sensitive member',
    masksTheCardNumberAndCarriesNoOtherSensitiveMember,
  );
  it(
    "holds this program's sentences at their baseline lines",
    holdsThisProgramsSentencesAtTheirBaselineLines,
  );
  it(
    'registers every withheld diagnostic of this program',
    registersEveryWithheldDiagnosticOfThisProgram,
  );
  it(
    'advertises three keys and renders no Enter descriptor',
    advertisesThreeKeysAndRendersNoEnterDescriptor,
  );
  it(
    'emphasises the fifth key and not the other advertised keys',
    emphasisesTheFifthKeyAndNotTheOtherAdvertisedKeys,
  );
  it('re-reads the authorization on Enter', reReadsTheAuthorizationOnEnter);
  it('returns to the summary on the third key', returnsToTheSummaryOnTheThirdKey);
  it(
    'steps to the next authorization rather than paging forward',
    stepsToTheNextAuthorizationRatherThanPagingForward,
  );
  it('reports the end of the authorization set', reportsTheEndOfTheAuthorizationSet);
  it('refuses the keys the program does not bind', refusesTheKeysTheProgramDoesNotBind);
  it('dispatches the eighth key from both paths', dispatchesTheEighthKeyFromBothPaths);
  it(
    'gates the fraud transition behind a dangerous confirmation',
    gatesTheFraudTransitionBehindADangerousConfirmation,
  );
  it(
    'reports each fraud direction with its own sentence',
    reportsEachFraudDirectionWithItsOwnSentence,
  );
  it('names the authorization in the confirmation', namesTheAuthorizationInTheConfirmation);
  it(
    'names the withdrawal on a reported authorization',
    namesTheWithdrawalOnAReportedAuthorization,
  );
  it('publishes the confirmation as a modal dialog', publishesTheConfirmationAsAModalDialog);
  it('contains keyboard focus inside the confirmation', containsKeyboardFocusInsideTheConfirmation);
  it(
    'writes nothing when the confirmation is withdrawn by Escape',
    writesNothingWhenTheConfirmationIsWithdrawnByEscape,
  );
  it(
    'announces an outstanding write without withdrawing its keys',
    announcesAnOutstandingWriteWithoutWithdrawingItsKeys,
  );
  it('focuses the safe choice and refuses a bare Enter', focusesTheSafeChoiceAndRefusesABareEnter);
  it('refuses to confirm a write the row contradicts', refusesToConfirmAWriteTheRowContradicts);
  it('confirms a write the row agrees with', confirmsAWriteTheRowAgreesWith);
  it('re-reads the row when the refusal may have landed', reReadsTheRowWhenTheRefusalMayHaveLanded);
  it(
    'leaves the row unread when the service describes the refusal',
    leavesTheRowUnreadWhenTheServiceDescribesTheRefusal,
  );
  it(
    'hands the fraud outcome on when nobody is left to read it',
    handsTheFraudOutcomeOnWhenNobodyIsLeftToReadIt,
  );
  it(
    'refuses a second transition while one is outstanding',
    refusesASecondTransitionWhileOneIsOutstanding,
  );
  it(
    'leaves the record unchanged when the write is refused',
    leavesTheRecordUnchangedWhenTheWriteIsRefused,
  );
  it(
    'paints the replacement sentence when a read is refused',
    paintsTheReplacementSentenceWhenAReadIsRefused,
  );
  it('admits a non-administrative operator', admitsANonAdministrativeOperator);
  it(
    'sends the whole composite selector and keeps it out of the session',
    sendsTheWholeCompositeSelectorAndKeepsItOutOfTheSession,
  );
  it(
    'surfaces refusals on the band and not on the record',
    surfacesRefusalsOnTheBandAndNotOnTheRecord,
  );
}

describe('pending-authorization detail screen contracts', authorizationDetailCases);
