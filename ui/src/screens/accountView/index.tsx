/**
 * @file The account view screen, migrated from `app/cbl/COACTVWC.cbl` and its mapset
 * `app/bms/COACTVW.bms` (map `CACTVWA`, `DFHMDI ... SIZE=(24,80)`, 100 `DFHMDF` definitions of which
 * 37 are named), mounted at the `/account/view` route.
 *
 * Purpose
 * -------
 * Take an eleven-digit account identifier from the operator, read that account together with its
 * customer, and render both as a read-only record view. It replaces CICS transaction `CAVW`, which
 * `app/cbl/COACTVWC.cbl` L145-L146 names as this program's own transaction identifier, and it
 * publishes the field labels, section headings and legend text the screen tests assert against.
 *
 * What this module owns, and what it does not
 * -------------------------------------------
 * Assumptions: the 29 field labels, the two section headings and the row-24 legend are declared HERE
 * rather than in `ui/src/messages/messages.ts`, because that catalog draws the boundary itself: its
 * file overview states that the static text painted by the BMS maps is "deliberately not here" and
 * that "`app/bms/*.bms` is the source for `ui/src/screens/**`, `ScreenHeader` and `PfKeyBar`". The
 * twelve message SENTENCES this program emits are program literals and `88`-level condition values,
 * so those come from the catalog and none of them is written inline below.
 *
 * Assumptions: the six header fields the mapset paints in rows 1 and 2 -- `TRNNAME`, `TITLE01`,
 * `CURDATE`, `PGMNAME`, `TITLE02` and `CURTIME` -- are NOT rendered here. `ui/src/layout/AppShell.tsx`
 * mounts `ui/src/layout/ScreenHeader.tsx` in the shell's header region, and neither that component nor
 * the server-clock hook it needs is among this module's declared dependencies, which is the contract
 * saying the band is not this screen's to compose.
 *
 * Stateless re-expression of a pseudo-conversational program
 * ---------------------------------------------------------
 * Refactoring Rationale: the reference carries every scrap of continuity between screen turns in one
 * passed structure -- `app/cpy/COCOM01Y.cpy` L19-L44 -- and this screen carries none of it. Navigation
 * moves to the router, identity to the signed token the API client attaches, and the selected account
 * to an explicit request member. The re-entry discriminator `CDEMO-PGM-CONTEXT` disappears outright:
 * a handler that answers with a field-error array has no first-entry-versus-re-entry distinction left
 * to make, which is why the error highlight below is driven purely by state derived from a response.
 */

import { Descriptions, Flex, Form, Input, Result, Spin, Typography, theme } from 'antd';
import { useCallback, useRef, useState } from 'react';
import type { ChangeEvent, ComponentProps, CSSProperties, ReactElement } from 'react';
import { useNavigate, useSearchParams } from 'react-router';

import { readAccountView } from '../../api/accounts';
import type { AccountDetail, AccountViewResponse, CustomerDetail } from '../../api/accounts';
import type { AbendDetail, ApiError, FieldError, FieldValidationState } from '../../api/types';
import { MessageBand } from '../../layout/MessageBand';
import { PfKeyBar } from '../../layout/PfKeyBar';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyRejection } from '../../layout/usePfKeys';
import {
  PROGRAM_MESSAGES,
  STATUS_MESSAGES,
  UNEXPECTED_ABEND_OCCURRED,
  UNEXPECTED_DATA_SCENARIO,
} from '../../messages/messages';
import { MAIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import { FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/**
 * Mapset this screen stands in, which selects the width the message band is sized to.
 *
 * Assumptions: `COACTVW` is one of the nineteen mapsets declaring `ERRMSGI`/`ERRMSGO` at `PIC X(78)`
 * -- confirmed at `app/cpy-bms/COACTVW.CPY` L240 -- so it takes the band's default width. The name is
 * passed anyway rather than omitted, because omitting it makes the correct rendering depend on that
 * default continuing to match this mapset, and nothing would fail if it stopped.
 */
const ACCOUNT_VIEW_MAPSET = 'COACTVW';

/** CICS transaction identifier this screen replaces, from `app/cbl/COACTVWC.cbl` L145-L146. */
export const ACCOUNT_VIEW_TRANSACTION_ID = 'CAVW';

/** Source program name, from `app/cbl/COACTVWC.cbl` L143-L144. */
export const ACCOUNT_VIEW_PROGRAM_NAME = 'COACTVWC';

/**
 * Identifier joining the account filter's label to its control.
 *
 * Assumptions: the association is explicit rather than left to label nesting, so the field has an
 * accessible name a query can find by label text. The 3270 original was operated entirely from the
 * keyboard and identified this field by its position at row 5 column 38, which a browser cannot
 * reproduce once gap G1 abandons absolute positioning.
 */
const ACCOUNT_ID_FIELD_ID = 'account-view-account-id';

/**
 * Declared width of the account filter, in characters.
 *
 * Assumptions: eleven is the width the reference declares in three independent places, so it is a
 * contract rather than a preference. `app/cpy-bms/COACTVW.CPY` L60 declares the receiving field
 * `ACCTSIDI PIC 99999999999`; `app/bms/COACTVW.bms` L84-L90 declares the map field `LENGTH=11` with
 * `PICIN='99999999999'` and `VALIDN=(MUSTFILL)`, which is the map-level statement of the same rule;
 * and `app/cpy/COCOM01Y.cpy` L38 declares the carried selection `CDEMO-ACCT-ID PIC 9(11)`.
 */
const ACCOUNT_ID_DECLARED_WIDTH = 11;

/**
 * Matches a filter entry that is exactly the declared width and entirely decimal digits.
 *
 * Assumptions: this is the browser-side statement of the two refusals at `app/cbl/COACTVWC.cbl` L666
 * -- `IF CC-ACCT-ID IS NOT NUMERIC OR CC-ACCT-ID EQUAL ZEROES` -- with the all-zeroes half applied
 * separately below so the two conditions stay individually readable.
 */
const ACCOUNT_ID_PATTERN = /^[0-9]{11}$/u;

/**
 * Query-string member this screen accepts as a pre-populated filter value.
 *
 * Assumptions: this is a DESIGN DECISION and not something the reference hands over, so it is named
 * here rather than inferred. The reference reaches this screen with a selected account already in the
 * shared communication area -- `CDEMO-ACCT-ID` at `app/cpy/COCOM01Y.cpy` L38 -- and
 * `app/cbl/COACTVWC.cbl` L465-L469 moves it into the map field when the filter flag is not blank. The
 * route carries no path parameter, and an account identifier is one of the values this migration
 * keeps out of a request LINE, so a query member is the remaining carrier that a sibling screen can
 * populate.
 *
 * Trade-offs: the value pre-fills the field and does NOT trigger a read. Reading on arrival was the
 * alternative and is rejected because `app/cbl/COACTVWC.cbl` L353-L360 sends the map and nothing else
 * on first entry -- the read happens only on the re-entry arm at L361-L373 -- so fetching here would
 * add a request the reference does not make and would report a not-found account before the operator
 * had done anything.
 */
const ACCOUNT_ID_PREFILL_PARAM = 'accountId';

/**
 * Abend code the reference moves for the dispatch state it treats as impossible.
 *
 * Assumptions: `app/cbl/COACTVWC.cbl` L376-L379 is the only place this program sets an abend code of
 * its own: it moves `LIT-THISPGM` to `ABEND-CULPRIT`, `'0001'` to `ABEND-CODE`, `SPACES` to
 * `ABEND-REASON` and `'UNEXPECTED DATA SCENARIO'` to the message field. That combination -- this code
 * with no reason text -- is what distinguishes it from the generic abend at L919, and it is why the
 * two abend sentences below are selected by the code rather than used interchangeably.
 */
const UNEXPECTED_DATA_SCENARIO_ABEND_CODE = '0001';

/**
 * Response member the service names when it refuses the submitted filter.
 *
 * Assumptions: `ui/src/api/accounts.ts` documents that a rejected read answers with a `fieldErrors`
 * entry keyed `accountId`, carrying this program's own refusal wording from
 * `app/cbl/COACTVWC.cbl` L672. Matching on that key is what routes a server-side refusal to the same
 * control the browser-side refusal marks, so the operator sees one behaviour from two sources.
 */
const ACCOUNT_ID_RESPONSE_FIELD = 'accountId';

/**
 * The two `COLOR=NEUTRAL` headings the mapset paints, verbatim.
 *
 * Assumptions: both are `INITIAL=` literals on unnamed `DFHMDF` definitions, so neither has a
 * symbolic-map field and neither can arrive in a response -- they are painted text belonging to this
 * screen. `View Account` is at `app/bms/COACTVW.bms` L75-L78, `LENGTH=12` at `POS=(4,33)`;
 * `Customer Details` is at L199-L202, `LENGTH=16` at `POS=(11,32)`.
 */
export const ACCOUNT_VIEW_SECTION_TITLES = {
  account: 'View Account',
  customer: 'Customer Details',
} as const;

/**
 * The eleven account-block field labels, verbatim from `app/bms/COACTVW.bms`, in declaration order.
 *
 * Assumptions: the interior runs of spaces and the trailing spaces are part of the value and not
 * formatting. The mapset pads each label to a fixed cell width so the colons line up down the column
 * -- `Credit Limit        :` and `Current Cycle Debit :` are both `LENGTH=21` -- and the transcription
 * rule for this tree is byte-exact. A renderer may collapse the runs visually; nothing here may
 * discard them.
 */
export const ACCOUNT_BLOCK_FIELD_LABELS = {
  /** `app/bms/COACTVW.bms` L79-L83, `LENGTH=16` at `POS=(5,19)`. */
  accountNumber: 'Account Number :',
  /** `app/bms/COACTVW.bms` L93-L96, `LENGTH=12` at `POS=(5,57)`. */
  activeStatus: 'Active Y/N: ',
  /** `app/bms/COACTVW.bms` L103-L106, `LENGTH=7` at `POS=(6,8)`. */
  openDate: 'Opened:',
  /** `app/bms/COACTVW.bms` L112-L116, `LENGTH=21` at `POS=(6,39)`. */
  creditLimit: 'Credit Limit        :',
  /** `app/bms/COACTVW.bms` L124-L127, `LENGTH=7` at `POS=(7,8)`. */
  expirationDate: 'Expiry:',
  /** `app/bms/COACTVW.bms` L133-L137, `LENGTH=21` at `POS=(7,39)`. */
  cashCreditLimit: 'Cash credit Limit   :',
  /** `app/bms/COACTVW.bms` L145-L148, `LENGTH=8` at `POS=(8,8)`. */
  reissueDate: 'Reissue:',
  /** `app/bms/COACTVW.bms` L154-L158, `LENGTH=21` at `POS=(8,39)`. */
  currentBalance: 'Current Balance     :',
  /** `app/bms/COACTVW.bms` L166-L170, `LENGTH=21` at `POS=(9,39)`. */
  currentCycleCredit: 'Current Cycle Credit:',
  /** `app/bms/COACTVW.bms` L178-L181, `LENGTH=14` at `POS=(10,8)`. */
  groupId: 'Account Group:',
  /** `app/bms/COACTVW.bms` L187-L191, `LENGTH=21` at `POS=(10,39)`. */
  currentCycleDebit: 'Current Cycle Debit :',
} as const;

/**
 * The eighteen customer-block field labels, verbatim from `app/bms/COACTVW.bms`, in declaration
 * order.
 *
 * Assumptions: `addressLine2` is the empty string because the mapset paints NO label for `ACSADL2`.
 * The field sits at `POS=(17,10)`, directly beneath `ACSADL1` at `POS=(16,10)`, and the only label on
 * either row is the single `Address:` at `POS=(16,1)`; the second line was identified to a terminal
 * operator by sitting under the first. That positional identification is exactly what gap G1 gives
 * up, and inventing a label -- or repeating `Address:` -- would claim the mapset paints text it does
 * not. The empty value is therefore recorded deliberately so a reader does not read it as an omission.
 */
export const CUSTOMER_BLOCK_FIELD_LABELS = {
  /** `app/bms/COACTVW.bms` L203-L206, `LENGTH=14` at `POS=(12,8)`. */
  customerId: 'Customer id  :',
  /** `app/bms/COACTVW.bms` L212-L215, `LENGTH=4` at `POS=(12,49)`. */
  ssn: 'SSN:',
  /** `app/bms/COACTVW.bms` L221-L224, `LENGTH=14` at `POS=(13,8)`. */
  dateOfBirth: 'Date of birth:',
  /** `app/bms/COACTVW.bms` L230-L233, `LENGTH=11` at `POS=(13,49)`. */
  ficoCreditScore: 'FICO Score:',
  /** `app/bms/COACTVW.bms` L239-L242, `LENGTH=10` at `POS=(14,1)`. */
  firstName: 'First Name',
  /** `app/bms/COACTVW.bms` L243-L246, `LENGTH=13` at `POS=(14,28)`. */
  middleName: 'Middle Name: ',
  /** `app/bms/COACTVW.bms` L247-L250, `LENGTH=12` at `POS=(14,55)`. */
  lastName: 'Last Name : ',
  /** `app/bms/COACTVW.bms` L264-L267, `LENGTH=8` at `POS=(16,1)`. */
  addressLine1: 'Address:',
  /** `app/bms/COACTVW.bms` L273-L276, `LENGTH=6` at `POS=(16,63)`. */
  stateCode: 'State ',
  /** No label is painted; see the note on this group. */
  addressLine2: '',
  /** `app/bms/COACTVW.bms` L287-L290, `LENGTH=3` at `POS=(17,63)`. */
  zipCode: 'Zip',
  /** `app/bms/COACTVW.bms` L297-L300, `LENGTH=5` at `POS=(18,1)`. */
  city: 'City ',
  /** `app/bms/COACTVW.bms` L306-L309, `LENGTH=7` at `POS=(18,63)`. */
  countryCode: 'Country',
  /** `app/bms/COACTVW.bms` L315-L318, `LENGTH=8` at `POS=(19,1)`. */
  phoneNumber1: 'Phone 1:',
  /** `app/bms/COACTVW.bms` L322-L325, `LENGTH=30` at `POS=(19,24)`. */
  governmentIssuedId: 'Government Issued Id Ref    : ',
  /** `app/bms/COACTVW.bms` L331-L334, `LENGTH=8` at `POS=(20,1)`. */
  phoneNumber2: 'Phone 2:',
  /** `app/bms/COACTVW.bms` L338-L341, `LENGTH=16` at `POS=(20,24)`. */
  eftAccountId: 'EFT Account Id: ',
  /** `app/bms/COACTVW.bms` L347-L350, `LENGTH=24` at `POS=(20,53)`. */
  primaryCardHolderIndicator: 'Primary Card Holder Y/N:',
} as const;

/**
 * The single legend label this mapset paints, verbatim.
 *
 * Assumptions: `app/bms/COACTVW.bms` L369-L373 paints exactly `'  F3=Exit '` -- two leading spaces
 * and one trailing space, ten characters inside a `LENGTH=60` `COLOR=TURQUOISE` field -- and it is the
 * whole legend. ENTER is deliberately NOT advertised even though `app/cbl/COACTVWC.cbl` L307-L308
 * admits it, so binding a label to ENTER here would paint a key the terminal did not. That is why the
 * uniform legend labels `PfKeyBar` exports are not used: none of PF4, PF7 or PF8 is bound on this
 * screen, and ENTER's wording is not uniform across the mapsets in any case.
 */
export const ACCOUNT_VIEW_KEY_LABELS = {
  PFK03: '  F3=Exit ',
} as const;

/**
 * The twelve message literals this program declares, keyed by their `88`-level condition names.
 *
 * Assumptions: reached through the catalog rather than transcribed, so the byte-exactness guarantee
 * is enforced in one reviewable module. Each entry carries the declaring field and its `PICTURE`
 * width alongside the text, which is how the information and error channels below stay distinguishable.
 */
const ACCOUNT_VIEW_MESSAGES = STATUS_MESSAGES.COACTVWC;

/**
 * The refusal sentence for a filter that is present but malformed.
 *
 * Assumptions: this is a THIRD, distinct string and not a duplicate of the two `88`-level values
 * `SEARCHED_ACCT_ZEROES` and `SEARCHED_ACCT_NOT_NUMERIC`, which both read
 * `Account number must be a non zero 11 digit number` with "non zero" unhyphenated. The one the
 * program actually moves is at `app/cbl/COACTVWC.cbl` L672, reads
 * `Account Filter must  be a non-zero 11 digit number` with a DOUBLE SPACE after "must" and
 * "non-zero" hyphenated, and is the one an operator sees -- the two condition names are declared and
 * never `SET`. Merging the three would silently change what the screen displays, so the catalog holds
 * them apart and this screen reads the moved literal.
 */
const ACCOUNT_FILTER_REFUSAL =
  PROGRAM_MESSAGES.COACTVWC.ACCOUNT_FILTER_MUST_BE_A_NON_ZERO_11_DIGIT_NUMBER;

/**
 * One rendered row of a record block: a painted label and the value beneath it.
 *
 * Assumptions: the rows are DATA rather than markup, so the eleven account fields and the eighteen
 * customer fields are declared once as ordered arrays and rendered by one code path. The alternative
 * -- twenty-nine hand-written `Descriptions.Item` elements -- would put the declaration order that
 * this screen must preserve into the shape of the JSX, where nothing can assert it; as an array the
 * order is a value a test can compare against the mapset.
 */
interface RecordRow {
  /** Stable key, being the response member the row renders. */
  readonly key: string;
  /** Painted label, verbatim from the mapset, or the empty string where none is painted. */
  readonly label: string;
  /** Value as the service sent it, already reduced to displayable text. */
  readonly value: string;
  /**
   * Whether the value is one of the five monetary amounts and takes the fixed-pitch treatment.
   *
   * Assumptions: exactly five fields carry `PICOUT='+ZZZ,ZZZ,ZZZ.99'` with `JUSTIFY=(RIGHT)` in this
   * mapset, and `ui/src/theme/tokens.ts` records that all five of the baseline's money fields are in
   * `COACTVW`. The flag is carried per row rather than inferred from the label, because a label is
   * text and would tie a rendering decision to wording.
   */
  readonly monetary: boolean;
}

/**
 * The subset of `Form.Item` props that carry a field-level refusal.
 *
 * Assumptions: this is declared as an object to be spread rather than as two attributes passed
 * directly, because `ui/tsconfig.json` enables `exactOptionalPropertyTypes` -- under which passing
 * `validateStatus={undefined}` is an error rather than an omission, so the no-error case has to be
 * expressed as an absent property instead of an undefined one.
 */
interface FieldRefusalProps {
  /** Present only when the field is refused; `Form.Item` renders its error treatment. */
  readonly validateStatus?: 'error';
  /** Refusal text rendered beneath the control. */
  readonly help?: string;
}

/**
 * A field-level refusal as this screen holds it, from either source.
 *
 * Assumptions: the shape mirrors `FieldError` from the API types minus the member naming the field,
 * because this screen has exactly one control and the routing has already happened by the time a
 * refusal is held. Keeping the validation STATE rather than only the text is what preserves the
 * baseline's two distinct outcomes: `app/cpy/CSSETATY.cpy` L17-L27 colours a not-ok field red and
 * additionally writes a literal `'*'` into a BLANK one.
 */
interface AccountIdRefusal {
  /** Which of the baseline's two rejection flags this refusal corresponds to. */
  readonly state: FieldValidationState;
  /** Sentence describing the refusal. */
  readonly message: string;
  /*
   * WHY : Refactoring Rationale: the ORIGIN of a refusal is carried, because it decides whether the
   *       sentence is repeated beneath the control. Without this member both refusal kinds rendered
   *       their sentence twice -- once in the band and once as field help -- which was measured in the
   *       rendered DOM and is not what either source does. The reference has ONE message channel for a
   *       locally rejected field: `app/cbl/COACTVWC.cbl` L532 moves `WS-RETURN-MSG` into `ERRMSGO`,
   *       and `app/cpy/CSSETATY.cpy` L17-L27 gives the field itself only a COLOUR and, when blank, the
   *       `'*'` marker -- never per-field text. A response is the other case: it supplies a per-field
   *       sentence that is genuinely distinct from its screen-level one, which is the mapping the
   *       migration's design-system section specifies for a field-error array, so there the help text
   *       adds information rather than repeating it.
   */
  /** Whether this screen's own edits produced the refusal, or a response did. */
  readonly source: 'local' | 'response';
}

/**
 * Reduces a nullable response member to text that is safe to render.
 *
 * Assumptions: three customer members are declared nullable by the contract -- `middleName`,
 * `addressLine2` and `phoneNumber2` -- and a null must never reach the DOM as the word "null". The
 * baseline has no null: `app/cpy/CVCUS01Y.cpy` declares every one of them as a fixed-width `PIC X(n)`
 * that is blank-filled when unused, so the empty string is the faithful rendering of an absent value
 * rather than merely a safe one.
 * @param {string | null} value - The member as the service sent it.
 * @returns {string} The value, or the empty string when the service sent none.
 */
function displayText(value: string | null): string {
  return value ?? '';
}

/**
 * Applies the reference's blank sentinel to a raw filter entry.
 *
 * Assumptions: a lone `'*'` counts as no input. `app/cbl/COACTVWC.cbl` L628-L633 tests
 * `IF ACCTSIDI = '*' OR ACCTSIDI = SPACES` and moves `LOW-VALUES` into the working field, so the two
 * are one case. The sentinel exists because the screen WRITES `'*'` into this field when it rejects it
 * as blank -- L563 -- and the next turn therefore reads its own marker back; both halves of that
 * round trip have to survive, or an operator who presses Enter twice would be told the marker is a
 * malformed account number.
 *
 * Trade-offs: surrounding blanks are trimmed as well, which the reference cannot do because a 3270
 * field is space-filled to its declared width and `MUSTFILL` refuses a partially typed one outright.
 * Trimming is the browser equivalent of that refusal being impossible to trigger: a pasted value with
 * a trailing space would otherwise fail the digits test with a message about digits, which would
 * report the wrong reason.
 * @param {string} raw - The value as the control currently holds it.
 * @returns {string} The entry with the sentinel and surrounding blanks removed, so an empty result
 *   means no input was supplied.
 */
function normaliseAccountIdEntry(raw: string): string {
  const trimmed = raw.trim();
  return trimmed === FIELD_ERROR_TOKENS.blankMarker ? '' : trimmed;
}

/**
 * Applies the reference's own field edits to a filter entry, in its order and with its precedence.
 *
 * Assumptions: the two branches and the message each selects are transcribed from
 * `app/cbl/COACTVWC.cbl` L649-L680, and the precedence between them is the reference's rather than a
 * choice made here. The blank branch at L653-L661 sets `WS-PROMPT-FOR-ACCT`
 * (`Account number not provided`) guarded by `IF WS-RETURN-MSG-OFF`, and then the cross-field edit at
 * L640-L642 replaces it UNCONDITIONALLY with `NO-SEARCH-CRITERIA-RECEIVED` (`No input received`).
 * `No input received` is therefore what an operator actually sees for an empty field, and
 * `Account number not provided` is set and immediately overwritten -- so this function returns the
 * former. Every other message in this program is first-wins; that final override is the one exception.
 *
 * Assumptions: the malformed branch at L666-L680 refuses a value that is `NOT NUMERIC` or
 * `EQUAL ZEROES` and moves the L672 literal. `NOT NUMERIC` covers a wrong width too, because the
 * receiving field is `PIC 9(11)`: a shorter entry cannot fill it. Both halves are applied here, the
 * width and digit test through {@link ACCOUNT_ID_PATTERN} and the all-zeroes test separately.
 * @param {string} entry - A filter entry already reduced by {@link normaliseAccountIdEntry}.
 * @returns {AccountIdRefusal | null} The refusal to render, or `null` when the entry may be read.
 */
function validateAccountIdEntry(entry: string): AccountIdRefusal | null {
  if (entry.length === 0) {
    return {
      state: 'BLANK',
      message: ACCOUNT_VIEW_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text,
      source: 'local',
    };
  }

  // WHY : Assumptions: the all-zeroes case is tested by comparing against a run of zeroes at the
  //       declared width rather than by converting the entry to a number. `app/cpy/CVCRD01Y.cpy` L34
  //       declares `CC-ACCT-ID PIC X(11)` with L36 redefining it as `PIC 9(11)`, so the identifier is
  //       characters on the wire and a number only inside arithmetic; converting it here would discard
  //       the leading zeros that belong to the declared width and would put an eleven-digit value
  //       through an IEEE-754 double on the way.
  if (!ACCOUNT_ID_PATTERN.test(entry) || entry === '0'.repeat(ACCOUNT_ID_DECLARED_WIDTH)) {
    return { state: 'NOT_OK', message: ACCOUNT_FILTER_REFUSAL, source: 'local' };
  }

  return null;
}

/**
 * Narrows an unknown rejection to the normalised problem document the API client raises.
 *
 * Alternatives Considered: `isApiRequestError` from `ui/src/api/client.ts`, which is the obvious
 * candidate. It is rejected twice over. It is an `instanceof` check, so it answers `false` for a
 * hand-assembled response double -- and the test harness under `ui/src/test/**` builds exactly those,
 * a hazard `ui/src/layout/MessageBand.tsx` records for header casing in the same situation. And
 * `client.ts` is not among this module's declared dependencies, whereas `ui/src/api/types.ts` is, so
 * narrowing structurally against the shape that module publishes keeps the dependency surface as
 * declared while accepting strictly more of the values that legitimately carry a problem.
 *
 * Assumptions: `fieldErrors` is the discriminating member. The contract declares it as a required
 * array on every problem document, so its presence with the right element shape identifies one
 * without this function having to test all eleven members.
 * @param {unknown} reason - The value a rejected read settled with.
 * @returns {ApiError | null} The problem document, or `null` when the rejection carried none -- a
 *   `RangeError` from the client's own argument check, or a thrown value of any other shape.
 */
function problemFrom(reason: unknown): ApiError | null {
  if (typeof reason !== 'object' || reason === null) {
    return null;
  }

  const candidate = (reason as { readonly problem?: unknown }).problem ?? reason;

  if (typeof candidate !== 'object' || candidate === null) {
    return null;
  }

  const { fieldErrors } = candidate as { readonly fieldErrors?: unknown };

  return Array.isArray(fieldErrors) ? (candidate as ApiError) : null;
}

/**
 * Extracts the refusal a problem document raised against the account filter.
 *
 * Refactoring Rationale: the refusal is taken from the RESPONSE and is not recomputed here, and there
 * is no re-entry flag gating it. `app/cpy/CSSETATY.cpy` L18-L27 and `app/cbl/COACTVWC.cbl` L561-L565
 * both gate the red highlight and the `'*'` marker on `CDEMO-PGM-REENTER`, because the reference had
 * to remember that a turn was a second turn in order to know an error was worth showing. A stateless
 * handler answers with the refusal or it does not, so the discriminator has nothing left to
 * discriminate and is gone -- which is why this function needs no argument beyond the document.
 * @param {ApiError} problem - The normalised problem document the read rejected with.
 * @returns {AccountIdRefusal | null} The refusal to render against the control, or `null` when the
 *   document named no member this screen owns.
 */
function accountIdRefusalFrom(problem: ApiError): AccountIdRefusal | null {
  const named: FieldError | undefined = problem.fieldErrors.find(
    /**
     * Reports whether one entry names this screen's only control.
     * @param {FieldError} entry - One member of the document's field-error array.
     * @returns {boolean} `true` when the entry names the account filter.
     */
    (entry: FieldError): boolean => entry.field === ACCOUNT_ID_RESPONSE_FIELD,
  );

  return named === undefined
    ? null
    : { state: named.state, message: named.message, source: 'response' };
}

/**
 * Chooses the sentence the message band renders for a failed read.
 *
 * Refactoring Rationale: the service-supplied sentence is rendered and NO CICS diagnostic is
 * reconstructed. What the reference actually shows on these paths is a composed diagnostic --
 * `'Account:… not found in Cross ref file.  Resp:… Reas:…'` at `app/cbl/COACTVWC.cbl` L747-L757, the
 * account-master and customer-master equivalents at L797-L802 and L847-L852, and
 * `WS-FILE-ERROR-MESSAGE` at L86-L105 appending `RESP` and `RESP2` -- and a CICS response code has no
 * analogue in the target at all. Its four friendly `88`-level alternatives
 * (`DID-NOT-FIND-ACCT-IN-CARDXREF`, `DID-NOT-FIND-ACCT-IN-ACCTDAT`, `DID-NOT-FIND-CUST-IN-CUSTDAT`
 * and `XREF-READ-ERROR`) are declared at L129-L136 and never `SET`, because the statements that would
 * have set them are commented out at L792 and L842, so the `IF DID-NOT-FIND-*` tests at L704 and L713
 * can never fire. Rendering the service's sentence is therefore a documented DIVERGENCE and an
 * improvement, not parity, and it also declines to disclose which of the three reads failed.
 *
 * Trade-offs: the fallback is `Did not find this account in account master file` rather than an
 * invented sentence. `ui/src/api/accounts.ts` records that a missing account, a missing
 * cross-reference row and a missing customer row are all reported as one status, so no finer branch
 * can be established here; that literal is the reference's own wording for the commonest of the three
 * and introduces no new text.
 * @param {ApiError} problem - The normalised problem document the read rejected with.
 * @returns {string} The sentence to render in the error channel of the message band.
 */
function screenMessageFrom(problem: ApiError): string {
  const supplied = problem.message ?? '';

  return supplied.trim().length > 0
    ? supplied
    : ACCOUNT_VIEW_MESSAGES.DID_NOT_FIND_ACCT_IN_ACCTDAT.text;
}

/**
 * Chooses the heading for the abend surface from the abend's own fields.
 *
 * Assumptions: the two sentences are selected by the abend CODE rather than used interchangeably,
 * because the reference itself distinguishes the states. `app/cbl/COACTVWC.cbl` L376-L379 moves
 * `'0001'` with a blank reason and the sentence `UNEXPECTED DATA SCENARIO` for the dispatch state it
 * treats as impossible; L919 moves `UNEXPECTED ABEND OCCURRED.` under code `'9999'` for everything
 * else. Preferring the abend's own message when it carries one keeps a service-supplied explanation
 * ahead of either default.
 * @param {AbendDetail} abend - The structured abend the problem document carried.
 * @returns {string} The heading to render on the abend result.
 */
function abendHeading(abend: AbendDetail): string {
  if (abend.abendMsg.trim().length > 0) {
    return abend.abendMsg;
  }

  return abend.abendCode.trim() === UNEXPECTED_DATA_SCENARIO_ABEND_CODE
    ? UNEXPECTED_DATA_SCENARIO
    : UNEXPECTED_ABEND_OCCURRED;
}

/**
 * Ordered record entries in the shape the design system's record view accepts.
 *
 * Assumptions: the type is derived from the component's own props rather than written out, so a
 * change to the pinned version's item shape fails compilation here instead of silently accepting an
 * array the component no longer reads. This mirrors the defensive typing `ui/src/theme/tokens.ts`
 * applies to token names for the same reason.
 */
type RecordDescriptionItems = NonNullable<ComponentProps<typeof Descriptions>['items']>;

/**
 * The composed read's resolved shape.
 *
 * Assumptions: this is derived from the client function rather than written out, for the same reason
 * `ui/src/api/accounts.ts` declares no wire shape of its own -- a second description of one contract
 * is free to drift from it, and a hand-written `{ account: AccountViewResponse }` here would also
 * hide the revision member the read genuinely returns, making the resolved value look narrower than
 * it is.
 */
type AccountViewReadResult = Awaited<ReturnType<typeof readAccountView>>;

/**
 * Builds the ten account rows in the mapset's declaration order.
 *
 * Assumptions: the order is `app/bms/COACTVW.bms`'s and is not rearranged -- active status, open
 * date, credit limit, expiry, cash credit limit, reissue date, current balance, current cycle credit,
 * account group, current cycle debit. The account filter is deliberately absent: it is the only one
 * of the eleven account-block fields that is an input (`ATTRB=(FSET,IC,NORM,UNPROT)` at L84-L90) and
 * it is rendered as the control above these rows, not as a row.
 *
 * Assumptions: `expirationDate` is the response member for the field the mapset names `AEXPDT` and
 * the record declares `ACCT-EXPIRAION-DATE` at `app/cpy/CVACT01Y.cpy`. The misspelling in the
 * baseline is corrected in the target column and DTO name, which is one of the three renames the
 * migration's data-model mapping registers, so the two names differing here is deliberate.
 * @param {AccountDetail} detail - The account exactly as the service sent it, every amount a string.
 * @returns {readonly RecordRow[]} The ten rows to render, in reading order.
 */
function accountBlockRows(detail: AccountDetail): readonly RecordRow[] {
  return [
    {
      key: 'activeStatus',
      label: ACCOUNT_BLOCK_FIELD_LABELS.activeStatus,
      // WHY : Assumptions: the flag renders as the stored character, `Y` or `N`, because that is what
      //       the terminal displayed -- `ACSTTUS` is `PIC X(1)` at `app/cpy-bms/COACTVW.CPY` L66 and
      //       its label names the domain, `Active Y/N`. Expanding it to `Active`/`Inactive` would
      //       introduce two words no COBOL source in this application holds and would contradict the
      //       label beside it.
      value: detail.activeStatus,
      monetary: false,
    },
    {
      key: 'openDate',
      label: ACCOUNT_BLOCK_FIELD_LABELS.openDate,
      // WHY : Assumptions: the date renders as one combined value and is NOT split into year, month
      //       and day parts. `app/cbl/COACTVWC.cbl` L487 moves `ACCT-OPEN-DATE` whole into a single
      //       `PIC X(10)` field, whereas the account-UPDATE twin splits the same value across three
      //       controls so each part can be edited. Splitting it here would offer a structure this
      //       read-only screen does not have.
      value: detail.openDate,
      monetary: false,
    },
    {
      key: 'creditLimit',
      label: ACCOUNT_BLOCK_FIELD_LABELS.creditLimit,
      value: detail.creditLimit,
      monetary: true,
    },
    {
      key: 'expirationDate',
      label: ACCOUNT_BLOCK_FIELD_LABELS.expirationDate,
      value: detail.expirationDate,
      monetary: false,
    },
    {
      key: 'cashCreditLimit',
      label: ACCOUNT_BLOCK_FIELD_LABELS.cashCreditLimit,
      value: detail.cashCreditLimit,
      monetary: true,
    },
    {
      key: 'reissueDate',
      label: ACCOUNT_BLOCK_FIELD_LABELS.reissueDate,
      value: detail.reissueDate,
      monetary: false,
    },
    {
      key: 'currentBalance',
      label: ACCOUNT_BLOCK_FIELD_LABELS.currentBalance,
      value: detail.currentBalance,
      monetary: true,
    },
    {
      key: 'currentCycleCredit',
      label: ACCOUNT_BLOCK_FIELD_LABELS.currentCycleCredit,
      value: detail.currentCycleCredit,
      monetary: true,
    },
    {
      key: 'groupId',
      label: ACCOUNT_BLOCK_FIELD_LABELS.groupId,
      value: detail.groupId,
      monetary: false,
    },
    {
      key: 'currentCycleDebit',
      label: ACCOUNT_BLOCK_FIELD_LABELS.currentCycleDebit,
      value: detail.currentCycleDebit,
      monetary: true,
    },
  ];
}

/**
 * Builds the eighteen customer rows in the mapset's declaration order.
 *
 * Assumptions: the order is `app/bms/COACTVW.bms`'s, which pairs cleanly under a two-column grid --
 * the address rows are declared `ACSADL1`, `ACSSTTE`, `ACSADL2`, `ACSZIPC`, `ACSCITY`, `ACSCTRY`, so
 * a left-to-right, top-to-bottom fill reproduces the terminal's own rows 16, 17 and 18 exactly.
 * @param {CustomerDetail} detail - The customer exactly as the service sent it, with both protected
 *   identifiers already masked.
 * @returns {readonly RecordRow[]} The eighteen rows to render, in reading order.
 */
function customerBlockRows(detail: CustomerDetail): readonly RecordRow[] {
  return [
    {
      key: 'customerId',
      label: CUSTOMER_BLOCK_FIELD_LABELS.customerId,
      // WHY : Assumptions: the identifier stays TEXT. `app/cpy/CVCUS01Y.cpy` declares
      //       `CUST-ID PIC 9(09)` and `app/cpy/CVCRD01Y.cpy` L40-L42 declares the carried form as
      //       `CC-CUST-ID PIC X(09)` redefined `PIC 9(9)`, so it is characters on the wire and a
      //       number only in arithmetic -- and a nine-digit value with leading zeros loses them the
      //       moment it becomes one.
      value: detail.customerId,
      monetary: false,
    },
    {
      key: 'ssnMasked',
      label: CUSTOMER_BLOCK_FIELD_LABELS.ssn,
      /*
       * WHY : Refactoring Rationale: the value is rendered EXACTLY as the service sent it and this
       *       screen applies no formatting of its own. The reference composes the full identifier
       *       into dashed form itself -- `app/cbl/COACTVWC.cbl` L496-L504 does
       *       `STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4) INTO ACSTSSNO` over
       *       `CUST-SSN PIC 9(09)` -- because it holds the digits. The target does not: this
       *       migration's data-exposure narrowing stores the national identifier encrypted and
       *       returns it MASKED, so re-running that substring composition would slice a mask into
       *       groups of 3, 2 and 4 characters of `*` and present the result as though it were a
       *       formatted number.
       *       Assumptions: the same holds for the government-issued identifier below. Neither value
       *       may be unmasked, reconstructed or reformatted here.
       */
      value: detail.ssnMasked,
      monetary: false,
    },
    {
      key: 'dateOfBirth',
      label: CUSTOMER_BLOCK_FIELD_LABELS.dateOfBirth,
      value: detail.dateOfBirth,
      monetary: false,
    },
    {
      key: 'ficoCreditScore',
      label: CUSTOMER_BLOCK_FIELD_LABELS.ficoCreditScore,
      value: detail.ficoCreditScore,
      monetary: false,
    },
    {
      key: 'firstName',
      label: CUSTOMER_BLOCK_FIELD_LABELS.firstName,
      value: detail.firstName,
      monetary: false,
    },
    {
      key: 'middleName',
      label: CUSTOMER_BLOCK_FIELD_LABELS.middleName,
      value: displayText(detail.middleName),
      monetary: false,
    },
    {
      key: 'lastName',
      label: CUSTOMER_BLOCK_FIELD_LABELS.lastName,
      value: detail.lastName,
      monetary: false,
    },
    {
      key: 'addressLine1',
      label: CUSTOMER_BLOCK_FIELD_LABELS.addressLine1,
      value: detail.addressLine1,
      monetary: false,
    },
    {
      key: 'stateCode',
      label: CUSTOMER_BLOCK_FIELD_LABELS.stateCode,
      value: detail.stateCode,
      monetary: false,
    },
    {
      key: 'addressLine2',
      label: CUSTOMER_BLOCK_FIELD_LABELS.addressLine2,
      value: displayText(detail.addressLine2),
      monetary: false,
    },
    {
      key: 'zipCode',
      label: CUSTOMER_BLOCK_FIELD_LABELS.zipCode,
      value: detail.zipCode,
      monetary: false,
    },
    {
      key: 'city',
      label: CUSTOMER_BLOCK_FIELD_LABELS.city,
      // WHY : Assumptions: the city comes from the customer's THIRD address line, because the record
      //       has no city field at all -- `app/cbl/COACTVWC.cbl` L513 moves `CUST-ADDR-LINE-3` into
      //       `ACSCITYO`, and `app/cpy/CVCUS01Y.cpy` declares three numbered address lines with no
      //       named city among them. The service publishes the member as `city`, so the mapping has
      //       already happened server-side; this note exists so a reader does not go looking for a
      //       city column in the copybook and conclude the binding is wrong.
      value: detail.city,
      monetary: false,
    },
    {
      key: 'countryCode',
      label: CUSTOMER_BLOCK_FIELD_LABELS.countryCode,
      value: detail.countryCode,
      monetary: false,
    },
    {
      key: 'phoneNumber1',
      label: CUSTOMER_BLOCK_FIELD_LABELS.phoneNumber1,
      // WHY : Assumptions: the number renders as one combined value, not as area code and subscriber
      //       parts. `app/cbl/COACTVWC.cbl` L517 moves `CUST-PHONE-NUM-1` whole into a single
      //       `PIC X(13)` field; the account-update twin is the screen that splits it, because it has
      //       to validate the area code against the seeded lookup table.
      value: detail.phoneNumber1,
      monetary: false,
    },
    {
      key: 'governmentIssuedIdMasked',
      label: CUSTOMER_BLOCK_FIELD_LABELS.governmentIssuedId,
      value: detail.governmentIssuedIdMasked,
      monetary: false,
    },
    {
      key: 'phoneNumber2',
      label: CUSTOMER_BLOCK_FIELD_LABELS.phoneNumber2,
      value: displayText(detail.phoneNumber2),
      monetary: false,
    },
    {
      key: 'eftAccountId',
      label: CUSTOMER_BLOCK_FIELD_LABELS.eftAccountId,
      value: detail.eftAccountId,
      monetary: false,
    },
    {
      key: 'primaryCardHolderIndicator',
      label: CUSTOMER_BLOCK_FIELD_LABELS.primaryCardHolderIndicator,
      value: detail.primaryCardHolderIndicator,
      monetary: false,
    },
  ];
}

/**
 * Converts record rows into the design system's record entries.
 *
 * Assumptions: monetary values are rendered as STRINGS in the fixed-pitch token and are never put
 * through `Number`, `toFixed` or a locale number formatter. They arrive as text because every one of
 * them is a zoned-decimal amount server-side -- `ACCT-CURR-BAL PIC S9(10)V99` at
 * `app/cpy/CVACT01Y.cpy` L7 among the five -- held as `NUMERIC(p,2)` and put on the wire as text
 * precisely so no client routes an exact decimal through an IEEE-754 double. Formatting one here
 * would require parsing it first, which is the conversion the whole contract exists to avoid, and the
 * baseline's own presentation is the `PICOUT='+ZZZ,ZZZ,ZZZ.99'` edit mask the service already applies.
 *
 * Trade-offs: the alignment is applied by wrapping the value in a layout primitive rather than by
 * setting a text-align property on the cell. All five monetary fields carry `JUSTIFY=(RIGHT)` in
 * `app/bms/COACTVW.bms`, so the alignment is a measured source value and not decoration -- but a text
 * component renders inline, and an alignment property has no effect on an inline box. The primitive
 * supplies the block context the alignment needs while keeping the spacing and layout rules satisfied,
 * where a bespoke wrapper element with its own CSS would not.
 * @param {readonly RecordRow[]} rows - The rows to render, already in reading order.
 * @param {CSSProperties} monetaryStyle - Style carrying the fixed-pitch font token, applied only to
 *   monetary values so the decimal points line up down the column as they did on the terminal.
 * @returns {RecordDescriptionItems} The entries to hand to the record view, in the order given.
 */
function toDescriptionItems(
  rows: readonly RecordRow[],
  monetaryStyle: CSSProperties,
): RecordDescriptionItems {
  return rows.map(
    /**
     * Renders one row as a record entry.
     * @param {RecordRow} row - The row to render.
     * @returns {RecordDescriptionItems[number]} The entry for that row.
     */
    (row: RecordRow): RecordDescriptionItems[number] => ({
      key: row.key,
      label: row.label,
      children: row.monetary ? (
        <Flex justify="flex-end">
          <Typography.Text style={monetaryStyle}>{row.value}</Typography.Text>
        </Flex>
      ) : (
        <Typography.Text>{row.value}</Typography.Text>
      ),
    }),
  );
}

/**
 * Keeps a filter entry to the characters the reference's receiving field can hold.
 *
 * Assumptions: digits and the blank marker are the whole accepted alphabet, and the marker is
 * accepted because the screen writes it into this very field when it rejects the field as blank
 * (`app/cbl/COACTVWC.cbl` L563) and reads it back as "no input" on the next turn (L628-L633).
 * Stripping it would break the second half of that round trip and report the screen's own marker as a
 * malformed account number.
 *
 * Trade-offs: this filters on the way in as well as declaring `maxLength` on the control. The
 * declared maximum is what a keyboard obeys, but it does not constrain a PASTE of mixed characters,
 * and the reference field cannot receive one at all -- `PICIN='99999999999'` with
 * `VALIDN=(MUSTFILL)` at `app/bms/COACTVW.bms` L84-L90 makes a non-numeric or partial entry
 * unenterable rather than merely invalid. Filtering is the browser equivalent of that field
 * behaviour; without it, a pasted value would reach the refusal path and report a digit problem the
 * operator did not create.
 * @param {string} raw - The value the control is proposing, as typed or pasted.
 * @returns {string} The value reduced to at most eleven digits, or the blank marker unchanged.
 */
function acceptAccountIdKeystrokes(raw: string): string {
  if (raw === FIELD_ERROR_TOKENS.blankMarker) {
    return raw;
  }

  return raw.replace(/[^0-9]/gu, '').slice(0, ACCOUNT_ID_DECLARED_WIDTH);
}

/**
 * Renders the account view screen: one account filter, and the account and customer it resolves to.
 *
 * The screen takes no props. It is mounted directly as the element of the `/account/view` route, which
 * declares no path parameter, so every input it has comes from the control the operator types into or
 * from the optional {@link ACCOUNT_ID_PREFILL_PARAM} query member.
 *
 * Error paths it surfaces, all four of them distinctly:
 * - a filter that is blank or is the screen's own blank marker is refused locally with
 *   `No input received`, and the marker is written back into the control;
 * - a filter that is not eleven digits, or is all zeroes, is refused locally with the reference's
 *   `Account Filter must  be a non-zero 11 digit number`;
 * - a rejected read renders the service's sentence in the error channel of the message band, and any
 *   field-level entry it names against the filter on the control itself;
 * - a rejection carrying a structured abend replaces the record blocks with the abend surface.
 * @returns {ReactElement} The screen: the account filter, the two record blocks once a read succeeds,
 *   the information and error message lines, and the function-key legend.
 */
export function AccountViewScreen(): ReactElement {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { cssVar } = theme.useToken();

  /*
   * WHY : Assumptions: the query member seeds the control's INITIAL value only, through a lazy
   *       initialiser, so a later change to the query string does not overwrite what the operator has
   *       since typed. The reference behaves the same way: `app/cbl/COACTVWC.cbl` L465-L469 populates
   *       the map field from the carried selection while composing the map, and from then on the
   *       field's content is whatever the terminal last sent.
   */
  const [entry, setEntry] = useState<string>(
    /**
     * Reads the pre-populated filter value once, on first render.
     * @returns {string} The query member reduced to acceptable characters, or the empty string.
     */
    (): string => acceptAccountIdKeystrokes(searchParams.get(ACCOUNT_ID_PREFILL_PARAM) ?? ''),
  );

  const [refusal, setRefusal] = useState<AccountIdRefusal | null>(null);
  const [view, setView] = useState<AccountViewResponse | null>(null);
  const [abend, setAbend] = useState<AbendDetail | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  /*
   * WHY : Assumptions: the information line starts on the reference's prompt because that is the
   *       state the reference guarantees. `app/cbl/COACTVWC.cbl` L462-L463 sets
   *       `WS-PROMPT-FOR-INPUT` when no data was passed, and L528-L530 sets it again whenever the
   *       information field is empty -- so the prompt is the information line's floor, not merely its
   *       first value.
   */
  const [infoMessage, setInfoMessage] = useState<string>(
    ACCOUNT_VIEW_MESSAGES.WS_PROMPT_FOR_INPUT.text,
  );

  /*
   * WHY : Trade-offs: reads are sequenced by a token held in a ref so a slow earlier response cannot
   *       overwrite a faster later one. The reference cannot have this problem -- a pseudo-conversational
   *       task holds the terminal until it returns, so a second Enter is not deliverable until the
   *       first turn has finished -- and a browser has no such lock. Disabling the control while a
   *       read is in flight was the alternative and is rejected because it would make an unmapped key
   *       arrive as a `disabled` rejection rather than being coerced into Enter, changing the key
   *       behaviour below to work around a race. The cost is one ref and one comparison per response.
   */
  const requestSequence = useRef(0);

  const submit = useCallback(
    /**
     * Applies the reference's field edits and, when they pass, reads the account and its customer.
     *
     * Assumptions: the edits run BEFORE any request, in the reference's own order, so a malformed
     * filter is refused without a round trip exactly as `app/cbl/COACTVWC.cbl` L636-L642 refuses it
     * before reaching `9000-READ-ACCT`. The service applies the same edits authoritatively; this is
     * the immediate feedback, not the authority.
     *
     * Assumptions: one call reads both records. `readAccountView` is the composed read the account
     * contract publishes, which is the server-side equivalent of the reference's three-stage chain --
     * the cross-reference by account at L723-L735, the account master at L777 onward and the customer
     * master at L820 onward, each short-circuiting on failure at L697, L704 and L713. Issuing three
     * browser requests to mirror those stages was never available: the contract tags the standalone
     * cross-reference and customer reads `internal`, so no browser token reaches them.
     * @returns {void} Completion is represented by this screen's own state.
     */
    (): void => {
      const normalised = normaliseAccountIdEntry(entry);
      const localRefusal = validateAccountIdEntry(normalised);

      if (localRefusal !== null) {
        setRefusal(localRefusal);
        setErrorMessage(localRefusal.message);
        setView(null);
        setAbend(null);
        setLoading(false);
        setInfoMessage(ACCOUNT_VIEW_MESSAGES.WS_PROMPT_FOR_INPUT.text);

        /*
         * WHY : Assumptions: the blank marker is written back INTO the control, which is the visible
         *       half of the reference's blank treatment -- `app/cbl/COACTVWC.cbl` L561-L565 moves
         *       `'*'` into the field's output subfield and `DFHRED` into its colour subfield. Only the
         *       colour half is expressible through the form control's own error state, so the marker
         *       has to be placed in the value; omitting it would drop an observable behaviour that a
         *       returning operator recognises. The re-entry gate the reference puts on both halves is
         *       not reproduced, for the reason recorded on {@link accountIdRefusalFrom}.
         */
        if (localRefusal.state === 'BLANK') {
          setEntry(FIELD_ERROR_TOKENS.blankMarker);
        }

        return;
      }

      setEntry(normalised);
      setRefusal(null);
      setErrorMessage(null);
      setAbend(null);
      setLoading(true);

      const token = requestSequence.current + 1;
      requestSequence.current = token;

      readAccountView(normalised).then(
        /**
         * Publishes the account and its customer, together with the channels the response carries.
         *
         * Assumptions: the two message channels are taken from the response and are kept apart,
         * because the mapset declares two independent lines -- `INFOMSGO PIC X(45)` at row 22 and
         * `ERRMSGO PIC X(78)` at row 23, per `app/cpy-bms/COACTVW.CPY` L234 and L240 -- and
         * `app/cbl/COACTVWC.cbl` L532-L534 fills them from two different working fields. Collapsing
         * them into one band would make a confirmation and a rejection compete for one line.
         * @param {AccountViewReadResult} result - The composed read's result. Only its record is used
         *   here; the revision it also carries is the precondition an EDIT submits under, so a
         *   read-only screen has no use for it and deliberately does not retain it.
         * @returns {void} Completion is represented by this screen's own state.
         */
        (result: AccountViewReadResult): void => {
          if (requestSequence.current !== token) {
            return;
          }

          setView(result.account);
          setErrorMessage(result.account.returnMessage);
          setLoading(false);

          /*
           * WHY : Refactoring Rationale: the successful state shows `Displaying details of given
           *       Account` when the response supplies no information line of its own, and this IS a
           *       documented divergence rather than parity. The sentence is declared at
           *       `app/cbl/COACTVWC.cbl` L115-L116 as the `88`-level value `WS-INFORM-OUTPUT` and is
           *       never `SET` anywhere in the program, and L528-L530 then forces the prompt back
           *       whenever the information field is empty -- so the reference's information line is
           *       effectively always the prompt, even while a record is on display. Using the
           *       sentence for the state its own condition name describes tells the operator the read
           *       succeeded, which the reference leaves to the record appearing; the divergence is
           *       recorded here because it is a behavioural change and not a transcription.
           */
          setInfoMessage(
            result.account.informationMessage ?? ACCOUNT_VIEW_MESSAGES.WS_INFORM_OUTPUT.text,
          );
        },
        /**
         * Reports a rejected read on the channel that matches what the rejection carried.
         *
         * Assumptions: the record blocks are cleared. The reference's screen composition is gated on
         * its read flags -- the account block on `FOUND-ACCT-IN-MASTER OR FOUND-CUST-IN-MASTER` at
         * `app/cbl/COACTVWC.cbl` L471-L472 and the customer block on `FOUND-CUST-IN-MASTER` at L493 --
         * so a failed read paints neither. That the two gates DIFFER is why a partially resolved read
         * is a legitimate state, which the render below honours by testing each block's data
         * separately rather than requiring both.
         * @param {unknown} reason - The value the read rejected with: the normalised problem document
         *   for a refused or failed request, or a `RangeError` from the client's own argument check.
         * @returns {void} Completion is represented by this screen's own state.
         */
        (reason: unknown): void => {
          if (requestSequence.current !== token) {
            return;
          }

          const problem = problemFrom(reason);
          setView(null);
          setLoading(false);
          setInfoMessage(ACCOUNT_VIEW_MESSAGES.WS_PROMPT_FOR_INPUT.text);

          if (problem === null) {
            /*
             * WHY : Assumptions: a rejection carrying no problem document is reported with the
             *       reference's own read-failure sentence and never with the thrown value's message.
             *       The client throws a `RangeError` for an argument its own check refuses, and its
             *       text is developer-facing; the account identifier is also a value this migration
             *       keeps out of any durable diagnostic, so relaying a thrown message is the one way
             *       an identifier could reach a log through this screen.
             */
            setRefusal(null);
            setAbend(null);
            setErrorMessage(ACCOUNT_VIEW_MESSAGES.XREF_READ_ERROR.text);
            return;
          }

          setRefusal(accountIdRefusalFrom(problem));
          setAbend(problem.abend);
          setErrorMessage(screenMessageFrom(problem));
        },
      );
    },
    [entry],
  );

  const exit = useCallback(
    /**
     * Leaves the screen for the menu the reference's PF3 arm transfers to.
     *
     * Assumptions: the destination is the main menu unconditionally. `app/cbl/COACTVWC.cbl` L328-L339
     * prefers `CDEMO-FROM-TRANID`/`CDEMO-FROM-PROGRAM` and falls back to `LIT-MENUTRANID`/`LIT-MENUPGM`
     * -- `CM00` and `COMEN01C` -- but the preferred arm reads a value a CALLING program deliberately
     * placed there, and the router publishes no equivalent named caller: a history entry is not a
     * named program and may not even be inside this application. The fallback is therefore the only
     * arm with a target analogue, and it is the reference's own default.
     *
     * Trade-offs: the exit sentence is set on this screen's error channel immediately before the
     * transition, so it is genuinely transient. Handing it to the destination as router state was the
     * alternative -- the direct analogue of `MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG` before
     * `EXEC CICS XCTL ... COMMAREA(...)` -- and is rejected twice: the single validated navigation
     * seam this tree transitions through takes a destination and no state, so using it would mean
     * bypassing that seam, and the menu screen is not authored yet so nothing would read the state.
     * Setting it keeps the transition truthful and observable rather than silently dropping a message
     * the reference emits.
     * @returns {void} Completion is the requested route transition.
     */
    (): void => {
      setErrorMessage(ACCOUNT_VIEW_MESSAGES.WS_EXIT_MESSAGE.text);
      navigateSafely(navigate, MAIN_MENU_ROUTE);
    },
    [navigate],
  );

  /*
   * WHY : Assumptions: exactly the two attention identifiers `app/cbl/COACTVWC.cbl` L307-L308 admits
   *       are bound, and only PF3 carries a label. The hook applies the PF13-to-PF24 folding that
   *       `app/cpy/CSSTRPFY.cpy` L54-L77 performs, so that aliasing is not restated per screen, and an
   *       unlabelled binding is keyboard-only -- which is what keeps ENTER working while leaving the
   *       legend showing the one key the mapset paints.
   */
  const { bindings, invoke } = usePfKeys(
    {
      ENTER: {
        /**
         * Submits the filter, which is the reference's Enter arm: edit the inputs, then read.
         * @returns {void} Completion is represented by this screen's own state.
         */
        onInvoke: (): void => {
          submit();
        },
      },
      PFK03: {
        /**
         * Leaves the screen, which is the reference's PF3 arm.
         * @returns {void} Completion is the requested route transition.
         */
        onInvoke: (): void => {
          exit();
        },
        label: ACCOUNT_VIEW_KEY_LABELS.PFK03,
      },
    },
    {
      /**
       * Coerces an unrecognised key into Enter, showing no message at all.
       *
       * Assumptions: this is precisely what the reference does. `app/cbl/COACTVWC.cbl` L306-L314 sets
       * `PFK-INVALID`, promotes only ENTER and PF03 to `PFK-VALID`, and then, `IF PFK-INVALID`, does
       * `SET CCARD-AID-ENTER TO TRUE` -- so an unmapped key is re-labelled rather than reported. This
       * program never moves `CCDA-MSG-INVALID-KEY`, unlike the sign-on screen, so surfacing the
       * invalid-key sentence the rejection carries would invent a message this screen never shows.
       *
       * Trade-offs: only the `unmapped` reason is coerced. A `disabled` rejection cannot arise here
       * because neither binding is ever disabled, so coercing it as well would be unreachable code
       * dressed as fidelity.
       * @param {PfKeyRejection} rejection - Why the key was not dispatched, whose `reason` separates
       *   an unmapped key from a disabled binding.
       * @returns {void} Completion is the coerced submission, or nothing.
       */
      onInvalidKey: (rejection: PfKeyRejection): void => {
        if (rejection.reason === 'unmapped') {
          submit();
        }
      },
    },
  );

  /*
   * WHY : Assumptions: the fixed-pitch font is applied by NAME through the token bridge and resolved
   *       to its CSS-variable reference rather than to a resolved value. `ui/src/theme/tokens.ts`
   *       records that all five of the baseline's `PICOUT='+ZZZ,ZZZ,ZZZ.99'` money fields are in this
   *       mapset and that a proportional face would stop their decimal positions lining up. Reading
   *       the hook's `token` member instead would copy today's resolved font stack into the element's
   *       inline style, which under CSS-variable theming opts the element out of the theme silently.
   */
  const monetaryStyle: CSSProperties = { fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData] };

  /*
   * WHY : Assumptions: the refusal is spread as an object rather than passed as two attributes,
   *       because `ui/tsconfig.json` enables `exactOptionalPropertyTypes`: an explicitly `undefined`
   *       attribute is an error there rather than an omission, so the no-refusal case has to be an
   *       absent property.
   * WHY : Refactoring Rationale: the error STATE is applied for either origin but the help text only
   *       for a response, so a locally rejected filter is coloured without its sentence being printed
   *       a second time under the control. Both kinds carried help text before this, and rendering the
   *       DOM showed the same sentence in `ant-form-item-explain-error` and in the band at once -- one
   *       message read twice, where the reference paints the sentence on row 23 and gives the field
   *       nothing but `DFHRED`. See the note on {@link AccountIdRefusal.source} for the citations.
   */
  const refusalProps: FieldRefusalProps =
    refusal === null
      ? {}
      : refusal.source === 'local'
        ? { validateStatus: 'error' }
        : { validateStatus: 'error', help: refusal.message };

  return (
    <Flex vertical gap="large">
      {/*
       * WHY : Assumptions: level 3 matches the level the sibling screens give their own row-4 title,
       *       leaving level 4 to the application heading the shell's header band renders, so the two
       *       do not compete for one slot. The secondary tone is not a colour choice made here: the
       *       mapset paints this heading `COLOR=NEUTRAL`, and `BMS_COLOR_TOKENS.NEUTRAL` in
       *       `ui/src/theme/tokens.ts` resolves NEUTRAL to `colorTextSecondary` -- which is exactly
       *       the token this prop selects, so the prop IS the bridge's decision rather than a bypass
       *       of it.
       */}
      <Typography.Title level={3} type="secondary">
        {ACCOUNT_VIEW_SECTION_TITLES.account}
      </Typography.Title>
      {/*
       * WHY : Alternatives Considered: rendering the whole screen as a form. Of the 37 named
       *       `DFHMDF` definitions in this mapset exactly ONE is an input -- `ACCTSID`, the only field
       *       carrying `UNPROT` -- and the other 36 are protected, so a form would offer an
       *       editability this transaction does not have, and the account-UPDATE screen is where that
       *       affordance belongs. The form element here wraps the single filter alone, purely so the
       *       field's refusal can be carried by the design system's own field-error treatment.
       */}
      <Form layout="vertical">
        <Form.Item
          label={ACCOUNT_BLOCK_FIELD_LABELS.accountNumber}
          htmlFor={ACCOUNT_ID_FIELD_ID}
          {...refusalProps}
        >
          {/*
           * WHY : Assumptions: `maxLength` is eleven because three independent declarations in the
           *       reference say so -- the receiving field `ACCTSIDI PIC 99999999999` at
           *       `app/cpy-bms/COACTVW.CPY` L60, the map field's `LENGTH=11` with
           *       `PICIN='99999999999'` and `VALIDN=(MUSTFILL)` at `app/bms/COACTVW.bms` L84-L90, and
           *       the carried selection `CDEMO-ACCT-ID PIC 9(11)` at `app/cpy/COCOM01Y.cpy` L38.
           * WHY : Assumptions: `autoFocus` is on this control and on no other, because `ACCTSID`
           *       carries the mapset's single `IC` attribute and because
           *       `app/cbl/COACTVWC.cbl` L546-L552 moves `-1` into `ACCTSIDL` in EVERY branch of its
           *       cursor `EVALUATE` -- the cursor is on this field whatever happened on the previous
           *       turn, so focusing it is transcription rather than a choice.
           * WHY : Alternatives Considered: the design system's numeric input with `controls={false}`
           *       and `stringMode`. Rejected because the numeric control cannot hold this field's
           *       blank marker: `app/cbl/COACTVWC.cbl` L563 writes a literal `'*'` into this very
           *       field and L628 reads it back, and a numeric control would discard it. A text control
           *       with digit filtering keeps both halves of that round trip and needs no `stringMode`
           *       to stay clear of an IEEE-754 double, because it never holds a number at all.
           */}
          {/*
           * WHY : Refactoring Rationale: `aria-invalid` is set explicitly, because the design system's
           *       field-error treatment does NOT set it for a control associated by `htmlFor`. Its form
           *       item only contributes the described-by wiring when it owns a NAMED field, which this
           *       one is not -- the screen holds the value itself -- so the refused state was conveyed
           *       to a sighted operator by colour and to an assistive technology not at all. Rendering
           *       the DOM with the field refused confirmed the attribute was absent. It is invisible,
           *       so it cannot conflict with the mapset, and the 3270 original was operated entirely
           *       from the keyboard, which makes programmatic determinability a fidelity concern here
           *       rather than an embellishment.
           *       Assumptions: the attribute alone is sufficient because the SENTENCE is already
           *       announced -- the error band renders the design system's alert with `role="alert"`,
           *       an assertive live region, so the refusal text reaches a screen reader without this
           *       control having to describe it a second time.
           */}
          <Input
            id={ACCOUNT_ID_FIELD_ID}
            autoFocus
            aria-invalid={refusal !== null}
            inputMode="numeric"
            maxLength={ACCOUNT_ID_DECLARED_WIDTH}
            value={entry}
            onChange={
              /**
               * Records the proposed filter value, reduced to the characters the field accepts.
               * @param {ChangeEvent<HTMLInputElement>} event - Change event from the control, whose
               *   target value is the text the operator typed or pasted.
               * @returns {void} Completion is represented by this screen's own state.
               */
              (event: ChangeEvent<HTMLInputElement>): void => {
                setEntry(acceptAccountIdKeystrokes(event.target.value));
              }
            }
          />
        </Form.Item>
      </Form>
      {/*
       * WHY : Assumptions: the abend surface REPLACES the record blocks rather than sitting beside
       *       them, because the reference replaces the whole screen -- its `WHEN OTHER` arm at
       *       `app/cbl/COACTVWC.cbl` L375-L382 performs `SEND-PLAIN-TEXT` instead of sending the map.
       *       The legend below stays rendered so the operator can still leave, which the reference's
       *       plain-text path achieves by ending the task.
       * WHY : Trade-offs: only the abend's heading and its reason are rendered, not its code or the
       *       program it names. Both of those are internal identifiers, and this migration's
       *       redaction register keeps that class of value out of the browser; the code is still used,
       *       to choose between the reference's two abend sentences, without being displayed.
       */}
      {abend === null ? null : (
        <Result status="error" title={abendHeading(abend)} subTitle={abend.abendReason} />
      )}
      {/*
       * WHY : Assumptions: the spinner is ADDITIVE and is not a fidelity claim. A pseudo-conversational
       *       task holds the terminal for the whole turn, so the 3270 screen had nothing to show
       *       during a read and needs nothing carried across; a browser leaves the previous paint on
       *       screen, so without this an operator cannot tell a slow read from a screen that ignored
       *       the key.
       */}
      {loading ? <Spin size="large" /> : null}
      {abend !== null || loading || view === null ? null : (
        <>
          {/*
           * WHY : Trade-offs: the two-column record view stands in for the mapset's absolute row and
           *       column positions, which is documented gap G1. Reading order, grouping and tab order
           *       are preserved because the rows are emitted in `DFHMDF` declaration order and filled
           *       left to right, and on the customer block that reproduces the terminal's own rows
           *       exactly -- `ACSADL1`/`ACSSTTE`, `ACSADL2`/`ACSZIPC`, `ACSCITY`/`ACSCTRY` are rows
           *       16, 17 and 18. What is NOT preserved is row-for-row alignment on the account block,
           *       where `ACRCYCR` occupies the right half of row 9 with nothing in the left half, so a
           *       two-up grid necessarily closes that hole; and pixel-for-character positioning
           *       nowhere, which a browser cannot offer responsively or accessibly.
           */}
          <Descriptions
            bordered
            column={2}
            items={toDescriptionItems(accountBlockRows(view.account), monetaryStyle)}
          />
          {/*
           * WHY : Assumptions: level 4 sits one below the screen heading above, so the customer block
           *       reads as subordinate to it and the heading order skips nothing. The mapset states
           *       the same subordination positionally -- `Customer Details` is painted at row 11,
           *       inside the body, where `View Account` is painted at row 4 above the first field.
           */}
          <Typography.Title level={4} type="secondary">
            {ACCOUNT_VIEW_SECTION_TITLES.customer}
          </Typography.Title>
          <Descriptions
            bordered
            column={2}
            items={toDescriptionItems(customerBlockRows(view.customer), monetaryStyle)}
          />
        </>
      )}
      {/*
       * WHY : Assumptions: TWO bands are rendered, in this order, because the mapset declares two
       *       independent message lines and this is where it puts them -- `INFOMSG` at `POS=(22,23)`,
       *       `ATTRB=(PROT) COLOR=NEUTRAL`, `PIC X(45)`, and `ERRMSG` at `POS=(23,1)`,
       *       `ATTRB=(ASKIP,BRT,FSET) COLOR=RED`, `PIC X(78)`. They sit after the record blocks and
       *       before the legend so the reading order matches rows 22, 23 and 24, which is the part of
       *       the fixed grid gap G1 does preserve. The informational band takes the `info` severity
       *       and the error band takes the component's own default, which is the appearance its source
       *       field always had.
       */}
      <MessageBand mapset={ACCOUNT_VIEW_MAPSET} severity="info" message={infoMessage} />
      <MessageBand mapset={ACCOUNT_VIEW_MAPSET} message={errorMessage} />
      {/*
       * WHY : Assumptions: the legend colour is passed explicitly and is not left at the default. This
       *       mapset is one of only TWO whose row-24 legend is `COLOR=TURQUOISE` rather than the
       *       15-of-17 majority `COLOR=YELLOW` -- `app/bms/COACTVW.bms` L369-L372 -- so omitting it
       *       would render this screen's legend in the wrong measured colour.
       */}
      <PfKeyBar keys={bindings} onInvoke={invoke} legendColor="TURQUOISE" />
    </Flex>
  );
}

/*
 * WHY : Trade-offs: the component is published BOTH ways, and the duplication is deliberate. The
 *       named export is what `ui/src/router.tsx` reads -- its lazy adapters republish
 *       `module.<ScreenName>` under the `default` key, which is the only shape `React.lazy` accepts --
 *       and it is the form every sibling screen already uses, so a named export is what keeps this
 *       screen mountable by the existing route table. The default export is this module's declared
 *       contract, and it additionally lets `lazy(() => import('./screens/accountView'))` resolve with
 *       no adapter at all. Both name one function, so the two cannot drift; and the discipline this
 *       tree bans is a default-export BARREL -- a re-export file standing between a screen and its
 *       importers -- which this is not, there being no second file in this folder.
 */
export default AccountViewScreen;
