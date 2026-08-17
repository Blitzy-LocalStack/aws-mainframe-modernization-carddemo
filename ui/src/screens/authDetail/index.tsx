/**
 * @file The pending-authorization detail screen, migrated from
 * `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` and its fraud-marking sibling `COPAUS2C.cbl`,
 * over the mapset `app/app-authorization-ims-db2-mq/bms/COPAU01.bms` (map `COPAU1A`,
 * `DFHMDI ... SIZE=(24,80)`), mounted at the `/authorizations/:key` route.
 *
 * Purpose
 * -------
 * Render one pending authorization as a read-only record, let a reviewer mark it as fraud or withdraw
 * that marking, and step to the next authorization for the same account. It replaces CICS transaction
 * `CPVD`, which `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` L36 declares as this program's own.
 *
 * Why this screen had to exist
 * ---------------------------
 * Refactoring Rationale: `ui/src/screens/authSummary/index.tsx` builds this screen's path with its
 * exported `authorizationDetailPath` and navigates there whenever a reviewer selects a row, and nothing
 * was mounted at that path -- so the one action the summary screen exists to offer resolved to the
 * router's not-found result. Selecting a row is also the ONLY way to reach this screen, because the
 * route parameter is the sealed selector that summary row carried; nothing here composes one.
 *
 * Two reference programs, one screen
 * ---------------------------------
 * Assumptions: the fraud transition is `COPAUS2C`'s work in the reference and is part of this screen
 * here, and that is the reference's own shape rather than a merge. `COPAUS1C` L187-L189 performs
 * `MARK-AUTH-FRAUD` on PF5 and paints the SAME map afterwards -- the transition never reaches a screen
 * of its own -- so `COPAUS2C` is a called program rather than a second screen, and its two success
 * sentences, `'ADD SUCCESS'` and `'UPDT SUCCESS'`, are reported on this map's message line. The service
 * answers the sentence for whichever write path ran, so neither is composed here.
 *
 * This screen renders no input, and that is fidelity rather than omission
 * ----------------------------------------------------------------------
 * ⚠️ Assumptions: there is deliberately NO `<input>`, no antd `Input`, no `Form` and no `autoFocus`
 * anywhere below, and a reviewer should read that as the mapset's own shape rather than as unfinished
 * work. Every one of the 27 named fields in `app/app-authorization-ims-db2-mq/bms/COPAU01.bms` is
 * `ATTRB=(ASKIP,NORM)` -- `ERRMSG` alone adds `BRT,FSET` -- so the operand that makes a 3270 field
 * enterable, `UNPROT`, appears nowhere in the mapset, and neither does `NUM` or `HILIGHT`. The mapset
 * also declares no `IC`, which is why this is one of only four screens in the application with no
 * initial cursor position at all.
 *
 * Assumptions: `SEND-AUTHVIEW-SCREEN` does `MOVE -1 TO CARDNUML` and sends with the `CURSOR` option at
 * `cbl/COPAUS1C.cbl` L378 to L394, which places the terminal cursor on `CARDNUM` -- a PROTECTED field.
 * A cursor resting on a protected field offers no data-entry affordance, so the browser equivalent is
 * not an autofocused control; it is no control. Alternatives Considered: focusing the first value on
 * mount to mirror the cursor move. Rejected because moving focus to a non-interactive element on load
 * announces it as though something were expected of the reader and steals the focus ring from the one
 * place it is useful here, the function-key bar. Keyboard operation is unaffected: `usePfKeys` binds at
 * the document, so every action this screen offers is reachable without focusing anything.
 *
 * This screen holds no session state
 * ---------------------------------
 * Assumptions: the reference carries the selected authorization in `CDEMO-CPVS-PAU-SELECTED` and the
 * calling program in `CDEMO-TO-PROGRAM`, and neither survives. The selection is the route parameter,
 * the return destination is the summary route, and the re-entry discriminator has no counterpart --
 * this screen reads its record from the route on mount and after each transition rather than
 * remembering which turn it is on.
 */

import { Button, Descriptions, Flex, Popconfirm, Result, Spin, Typography, theme } from 'antd';
import type { DescriptionsProps } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import type { CSSProperties, ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';

import {
  getPendingAuthorizationScreen,
  getNextPendingAuthorization,
  setAuthorizationFraudState,
} from '../../api/authorization';
import type { PendingAuthDetailScreen } from '../../api/authorization';
import { isApiRequestError } from '../../api/client';
import type { ApprovalStatus, FraudAction } from '../../api/types';
import { MessageBand } from '../../layout/MessageBand';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { PfKeyBar } from '../../layout/PfKeyBar';
import { ScreenHeader } from '../../layout/ScreenHeader';
import { usePfKeys } from '../../layout/usePfKeys';
import {
  INVALID_KEY_PRESSED,
  PROGRAM_MESSAGES,
  UNEXPECTED_ABEND_OCCURRED,
} from '../../messages/messages';
import { navigateSafely } from '../../routes/navigation';
import {
  BMS_TEXT_COLOR_TOKENS,
  DFH_RUNTIME_COLOR_TOKENS,
  TYPOGRAPHY_TOKENS,
} from '../../theme/tokens';
import type { AntdTokenName } from '../../theme/tokens';
import { SECTION_HEADING_LEVEL, ScreenTitle } from '../../layout/ScreenTitle';

/** Route pattern this screen is mounted at, whose parameter is the summary row's sealed selector. */
export const AUTHORIZATION_DETAIL_ROUTE = '/authorizations/:key';

/** Route PF3 returns to, which is the summary screen the selection was made on. */
export const AUTHORIZATION_SUMMARY_ROUTE = '/authorizations';

/** CICS transaction identifier this screen replaces, from `COPAUS1C.cbl` L36. */
export const AUTH_DETAIL_TRANSACTION_ID = 'CPVD';

/** Source program name, from `COPAUS1C.cbl` L33. */
export const AUTH_DETAIL_PROGRAM_NAME = 'COPAUS1C';

/** Mapset this screen stands in, which fixes the message band's rendered width. */
export const AUTH_DETAIL_MAPSET = 'COPAU01';

/** Row-3 sub-title, from `app/app-authorization-ims-db2-mq/bms/COPAU01.bms` L79. */
export const AUTH_DETAIL_SUBTITLE = 'View Authorization Details';

/**
 * Heading of the merchant block, from `COPAU01.bms` L232.
 *
 * Assumptions: the mapset's own literal is the words followed by a rule of hyphens that fills the
 * remainder of the 80-column row. Only the words are carried: the hyphens were a character-grid
 * device for drawing a horizontal rule, and design gap G1 gives up that grid, so reproducing them
 * would render a run of hyphens whose length means nothing in a reflowing layout.
 */
export const AUTH_DETAIL_MERCHANT_HEADING = 'Merchant Details';

/**
 * The twenty field labels this mapset paints, in the order it paints them.
 *
 * Assumptions: each is the mapset's own `INITIAL` literal, carried character for character including
 * the trailing colon and, for `Source   :`, the internal padding the mapset uses to align its colon
 * with the label above it. Trimming that padding would be a second spelling of a painted literal.
 *
 * ⚠️ Refactoring Rationale: the count in this description was `eighteen` while the object declared
 * twenty members, and the miscount was not merely cosmetic -- it agreed with a render that had only
 * nineteen fields on the glass, so the description corroborated the omission instead of exposing it.
 * `app/app-authorization-ims-db2-mq/bms/COPAU01.bms` paints twenty labelled value fields in the two
 * blocks this screen renders, counted from its own `DFHMDF` definitions, and every one of them now has
 * both a label here and a rendered value below.
 */
export const AUTH_DETAIL_FIELD_LABELS = {
  /** `COPAU01.bms` L84. */
  cardNumber: 'Card #:',
  /** `COPAU01.bms` L93. */
  authDate: 'Auth Date:',
  /** `COPAU01.bms` L103. */
  authTime: 'Auth Time:',
  /** `COPAU01.bms` L113. */
  authResponse: 'Auth Resp:',
  /** `COPAU01.bms` L123. */
  responseReason: 'Resp Reason:',
  /*
   * WHY : ⚠️ Assumptions: this label reads `Auth Code:` and the value beneath it is the PROCESSING
   *       code, not the authorization identification code. That mismatch is the reference's own:
   *       `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` L331 moves `PA-PROCESSING-CODE` into
   *       `AUTHCDO`, while `PA-AUTH-ID-CODE` -- declared at `cpy/CIPAUDTY.cpy` L29 and the field this
   *       label actually names -- is read from the segment and never painted by this map. It is
   *       preserved rather than corrected because the label is user-visible text carried across
   *       verbatim under transformation rule T8, and rebinding the value to `PA-AUTH-ID-CODE` would
   *       silently change which datum an operator has been reading for the life of the system.
   *       Alternatives Considered: relabelling the row `Processing Code:` so label and value agree.
   *       Rejected on the same rule -- that string appears in no baseline source, so introducing it
   *       would be inventing screen text rather than migrating it.
   */
  /** `COPAU01.bms` L133, painted above the processing code rather than the authorization id code. */
  authCode: 'Auth Code:',
  /** `COPAU01.bms` L143. */
  amount: 'Amount:',
  /** `COPAU01.bms` L153. */
  posEntryMode: 'POS Entry Mode:',
  /*
   * WHY : Assumptions: the three spaces before this colon are carried into the DOM verbatim and are
   *       deliberately NOT protected from HTML whitespace collapsing, so the label reads `Source :` on
   *       screen while `Source   :` is what the document holds -- browser validation measured both, at
   *       50.25px rendered against 57.2px with `white-space: pre`. The padding exists in the mapset to
   *       align this colon with the one above it on a fixed character grid, and design gap G1 gives up
   *       that grid: at one column per row the labels are left-aligned with nothing to align against,
   *       so a preserved run of spaces would read as a typo rather than as alignment.
   *       Alternatives Considered: `white-space: pre-wrap` on the label to reproduce the run visually.
   *       Rejected because it would defend a column geometry this screen does not have, while
   *       transformation rule T8's requirement -- that the literal be carried across character for
   *       character -- is met by the DOM text, which is what a test and a reader both compare.
   */
  /** `COPAU01.bms` L163, whose internal padding is part of the painted literal. */
  source: 'Source   :',
  /** `COPAU01.bms` L173. */
  merchantCategoryCode: 'MCC Code:',
  /** `COPAU01.bms` L183. */
  cardExpiryDate: 'Card Exp. Date:',
  /** `COPAU01.bms` L193. */
  authType: 'Auth Type:',
  /*
   * WHY : Assumptions: the value beneath this label is FIFTEEN characters wide here, not the sixteen
   *       the transaction screens use, and the two widths must not be unified. `COPAU01.bms` L204 to
   *       L208 declares `TRNID` with `LENGTH=15`, `cpy/CIPAUDTY.cpy` L44 declares
   *       `PA-TRANSACTION-ID PIC X(15)`, and `cpy/CCPAURQY.cpy` L36 declares the same width on the
   *       message payload -- three independent declarations agreeing on fifteen. The contract member
   *       this row renders is bounded to `maxLength: 15` for that reason. Widening it to the sixteen
   *       used elsewhere would admit an identifier no authorization record can hold.
   */
  /** `COPAU01.bms` L203, above a fifteen-character identifier. */
  transactionId: 'Tran Id:',
  /** `COPAU01.bms` L213. */
  matchStatus: 'Match Status:',
  /** `COPAU01.bms` L223. */
  fraudStatus: 'Fraud Status:',
  /** `COPAU01.bms` L238. */
  merchantName: 'Name:',
  /** `COPAU01.bms` L248. */
  merchantId: 'Merchant ID:',
  /** `COPAU01.bms` L258. */
  merchantCity: 'City:',
  /** `COPAU01.bms` L268. */
  merchantState: 'State:',
  /** `COPAU01.bms` L278. */
  merchantZip: 'Zip:',
} as const;

/**
 * The three legend labels this mapset paints on row 24, from `COPAU01.bms` L288-L292.
 *
 * Assumptions: the field's `INITIAL` is the single literal `' F3=Back  F5=Mark/Remove Fraud  F8=Next
 * Auth'`, split at the double spaces the mapset uses as its separator. The leading blank of the whole
 * literal is a position offset on the character grid rather than part of the first label, so it is not
 * carried into a label -- design gap G1 gives up the grid this screen would need it for.
 */
export const AUTH_DETAIL_KEY_LABELS = {
  /** PF3 returns to the summary screen. */
  PFK03: 'F3=Back',
  /** PF5 marks the authorization as fraud, or withdraws that marking. */
  PFK05: 'F5=Mark/Remove Fraud',
  /*
   * WHY : ⚠️ Assumptions: PF8 is declared here rather than taken from
   *       `UNIFORM_PF_KEY_LABELS.PFK08` in `ui/src/layout/PfKeyBar.tsx`, and the difference is
   *       behavioural rather than cosmetic. That shared constant reads `F8=Forward` because the eighth
   *       key pages a browse list on every screen it was measured on; on THIS screen it does something
   *       else. `COPAUS1C.cbl` L190 dispatches `PROCESS-PF8-KEY`, which L268 to L289 implements as
   *       "read the current authorization, then read the one after it" -- a step to the next RECORD,
   *       not to the next page of a list, and this screen shows no list to page. Taking the shared
   *       default would therefore have painted a legend that misnames the action, and the mapset
   *       settles the wording independently: `COPAU01.bms` L292 paints `F8=Next Auth`.
   *       Trade-offs: a screen-local label forgoes the single-definition benefit the shared constant
   *       exists for. That is accepted because the shared constant is only correct where the semantics
   *       are, and `PfKeyBar` deliberately supplies defaults for the three uniform keys alone.
   */
  /** PF8 steps to the next authorization for the same account, which is not a page-forward. */
  PFK08: 'F8=Next Auth',
} as const;

/**
 * The fraud tag character the reference writes for a confirmed report.
 *
 * Assumptions: the two transitions are the reference's own condition names on `PA-AUTH-FRAUD`, which
 * `app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy` declares at L50 with its confirmed and withdrawn
 * states at L51 and L52. They are named here rather than written inline at the toggle below so the two
 * characters are declared once each.
 */
export const FRAUD_REPORTED: FraudAction = 'F';

/** The fraud tag character the reference writes when a reviewer withdraws a report. */
export const FRAUD_WITHDRAWN: FraudAction = 'R';

/**
 * Column counts for the two record blocks, per viewport width.
 *
 * ⚠️ Refactoring Rationale: this is a BREAKPOINT MAP and was the bare number `2`, which antd treats as
 * a fixed count at every width -- so the record kept two label/value pairs per row however narrow the
 * viewport became. Browser validation at 500px measured the consequence: cells collapsed to 85-152px
 * and values broke mid-token, `************0011` splitting after twelve asterisks, `000000000000123`
 * after eleven digits, and even the label `Name:` breaking as `Nam` / `e:`. Nothing overflowed -- the
 * document measured `scrollWidth` 500 against `clientWidth` 500 -- so this never presented as a layout
 * error; it was simply unreadable. A datum broken across two lines misstates it, and the fields worst
 * affected were the identifiers and the masked card number, which are exactly the ones that must be
 * read exactly.
 *
 * Assumptions: the collapse is expressed through antd's OWN responsive mechanism -- `Descriptions`
 * accepts this map and resolves it against its internal breakpoints -- rather than through a media
 * query written here. Design-system rule DS3-c requires the library's primitive where one exists, and
 * `Descriptions` publishes no other way to vary its column count.
 *
 * Assumptions: two columns is the DESIGNED arrangement and is unchanged at that width. The mapset
 * paints these fields two and three to a row on an 80-column grid, so two is what is being reproduced;
 * one column applies only at widths the mapset never described, where the migration plan's precedence
 * puts responsive behaviour above a layout no baseline source specifies.
 */
const RECORD_COLUMNS = { xs: 1, sm: 1, md: 2 } as const;

/**
 * The three sentences this program paints, from the single catalog that owns them.
 *
 * ⚠️ Refactoring Rationale: this was described as "the two sentences the fraud transition reports, from
 * `COPAUS1C.cbl` L74-L75". Both halves were wrong and the citation was the more misleading of the two:
 * L64 to L67 are entries of the decline-reason table -- `'5100CARD FRAUD'` and `'5200MERCHANT FRAUD'`
 * are response-reason descriptions, not confirmations -- so the line range pointed a reader at a lookup
 * table when looking for message text. The catalog holds THREE entries for this program: the two fraud
 * confirmations, written at L535 and L537, and the end-of-set sentence written at L283.
 */
const DETAIL_MESSAGES = PROGRAM_MESSAGES.COPAUS1C;

/**
 * Decides which transition PF5 asks for, from the tag the screen is currently showing.
 *
 * Assumptions: the decision is taken from the RENDERED fraud status rather than from a remembered
 * toggle, because the reference decides it the same way -- `MARK-AUTH-FRAUD` reads the row's current
 * tag and writes the other one. Holding a toggle in component state would let a reviewer's second press
 * ask for a transition the row had already been moved out of by another reviewer.
 *
 * Assumptions: the rendered status is compared against the confirmed character rather than tested for
 * emptiness, so an untagged row and a withdrawn row both ask for a report. That is the reference's
 * behaviour: only a row already reported can be withdrawn.
 *
 * ⚠️ Refactoring Rationale: the comparison is against the mark's FIRST character, and it used to be
 * against the whole string -- which made the withdrawal unreachable for every real record. The member
 * this reads is a COMPOSED field, not the stored flag: `cbl/COPAUS1C.cbl` L344 tests the flag, L345 to
 * L347 then write the flag, a hyphen and the eight-character report date into one ten-character display
 * field, and L349 writes a bare hyphen when there is no mark. The service reproduces exactly that, so a
 * reported authorization arrives as `F-` followed by its date and never as the bare `F` that the whole
 * string was being compared with. The consequence was that this function answered "report it" for a row
 * that was already reported, so pressing PF5 on a marked authorization reported it a second time and no
 * reviewer could ever take a report back. Reading position one restores the reference's own test, which
 * is on the flag alone. Alternatives Considered: splitting on the hyphen and taking the first part.
 * Rejected because it presumes the separator is present, and the unmarked value IS the separator -- so
 * a bare `-` would split to an empty leading part and the reasoning would depend on that accident
 * rather than on the field layout.
 * @param {string} fraudMark - The fraud status as the service composed it: a bare hyphen when the
 *   authorization carries no mark, otherwise the flag character followed by a hyphen and the report
 *   date.
 * @returns {FraudAction} The transition to request.
 */
export function nextFraudAction(fraudMark: string): FraudAction {
  const flag = fraudMark.trim().charAt(0).toUpperCase();
  return flag === FRAUD_REPORTED ? FRAUD_WITHDRAWN : FRAUD_REPORTED;
}

/**
 * Reports the sentence the reference paints once a fraud transition has been written.
 *
 * ⚠️ Refactoring Rationale: the sentence is chosen by the ACTION that was submitted, and this screen
 * used to paint the sentence the SERVICE returned instead -- which put `'ADD SUCCESS'` or
 * `'UPDT SUCCESS'` on the message line, and those two never reach the message line in the reference at
 * all. Following the reference's own path settles it: `COPAUS1C.cbl` L253 to L262 moves
 * `WS-FRD-ACT-MSG` -- the field `COPAUS2C` writes those two strings into -- into `WS-MESSAGE` only on
 * the FAILURE arm at L257, immediately before rolling back. On the success arm at L255 it performs
 * `UPDATE-AUTH-DETAILS`, and L531 to L538 there write `'AUTH FRAUD REMOVED...'` when the row ended
 * withdrawn and `'AUTH MARKED FRAUD...'` when it ended reported. So the two `COPAUS2C` strings are
 * diagnostics of which SQL path ran and are visible to an operator only when the transition failed.
 * They stay catalogued in `PROGRAM_MESSAGES.COPAUS2C` for a reader, and are not rendered.
 *
 * Assumptions: the action is a sufficient basis for the choice, so nothing about the write path is
 * needed. `ui/src/api/authorization.ts` records the same conclusion from the other side: the
 * insert-versus-update distinction rides on the status code and is "a difference no screen needs to
 * branch on because the confirmation the reference displays is chosen by the ACTION".
 * @param {FraudAction} action - The transition that was submitted and succeeded.
 * @returns {string} The reference program's own confirmation for that transition.
 */
export function fraudOutcomeMessage(action: FraudAction): string {
  return action === FRAUD_WITHDRAWN
    ? DETAIL_MESSAGES.AUTH_FRAUD_REMOVED
    : DETAIL_MESSAGES.AUTH_MARKED_FRAUD;
}

/**
 * Reads a message from a rejected request, falling back to the shared abend sentence.
 *
 * Assumptions: the service's own sentence is preferred and nothing is composed here, because a problem
 * document carries the reference program's wording for the condition it reports. A rejection with no
 * document is reported with `UNEXPECTED ABEND OCCURRED.`, which is the sentence the reference uses when
 * it cannot say more -- inventing a sentence for a failure the service did not describe would put text
 * on the band that no program owns.
 * @param {unknown} failure - Whatever the request rejected with.
 * @returns {string} The sentence to paint on the message line.
 */
export function detailFailureMessage(failure: unknown): string {
  /*
   * WHY : Assumptions: the document's own message is checked for being ABSENT as well as blank, because
   *       `ApiError.message` is declared nullable -- the 78-character band field the reference paints is
   *       empty on a response that carries no sentence, and the contract models that as `null` rather
   *       than as an empty string. Testing only the trimmed length would dereference a null.
   */
  if (isApiRequestError(failure)) {
    const reported = failure.problem.message;
    if (reported !== null && reported.trim().length > 0) {
      return reported;
    }
  }
  return UNEXPECTED_ABEND_OCCURRED;
}

/**
 * The theme's CSS-variable references, as `theme.useToken` hands them out.
 *
 * Assumptions: derived from the hook's own return type rather than imported by name, because antd 6
 * publishes no exported name for it. This is the same alias `ui/src/screens/authSummary/index.tsx`
 * declares for the same reason, so the two screens of this bounded context take the same shape.
 */
type AntdCssVariables = ReturnType<typeof theme.useToken>['cssVar'];

/**
 * One entry of a record block, as antd's items form of `Descriptions` declares it.
 *
 * Assumptions: derived from `DescriptionsProps` rather than imported by name, because antd 6
 * re-exports `DescriptionsProps` from the package root but not `DescriptionsItemType`.
 */
type AuthDetailDescriptionItem = NonNullable<DescriptionsProps['items']>[number];

/**
 * A token name drawn from one of the two colour maps this screen is allowed to resolve through.
 *
 * Assumptions: the union is narrowed to the members of `BMS_TEXT_COLOR_TOKENS` and
 * `DFH_RUNTIME_COLOR_TOKENS` rather than left as the whole `AntdTokenName` surface, and the narrowing
 * is load-bearing rather than tidiness. `cssVar` is indexed by token name and its value type spans
 * every token the library publishes, including the numeric ones -- durations, radii, z-indices -- so
 * indexing it with the wide name yields `string | number | object`, which no `color` property accepts.
 * Constraining the union to the colour maps is what makes `cssVar[tone]` type-check as a colour, and it
 * simultaneously forecloses this screen resolving a colour through anything but the two audited maps.
 * The `Extract` against `AntdTokenName` keeps that guarantee honest: a map entry that ever stopped
 * naming a real antd token would empty this union and fail the build here.
 */
type ColorTokenName = Extract<
  | (typeof BMS_TEXT_COLOR_TOKENS)[keyof typeof BMS_TEXT_COLOR_TOKENS]
  | (typeof DFH_RUNTIME_COLOR_TOKENS)[keyof typeof DFH_RUNTIME_COLOR_TOKENS],
  AntdTokenName
>;

/**
 * The approval character the reference paints for an authorization that was approved.
 *
 * Assumptions: the service, not this screen, decides the character. `COPAUS1C.cbl` L311 tests the
 * stored two-character response code against `'00'` and moves `'A'` at L312 or `'D'` at L315, and the
 * contract publishes the result bounded to `^[AD]$` -- so the stored code never reaches a browser and
 * this constant is compared against an already-derived value.
 */
const AUTH_DETAIL_APPROVED: ApprovalStatus = 'A';

/**
 * One rendered row of a record block: the painted label, the value, and the value's measured tone.
 *
 * Assumptions: the rows are DATA rather than markup, so each block is declared once as an ordered
 * array and rendered by one code path. The alternative -- a hand-written `Descriptions.Item` element
 * per field -- is what this screen used to do, and it is how the `Auth Code:` row came to have a label
 * with no value anywhere in the JSX: with the fields spelled out as elements, a missing one is a line
 * that was never typed, which nothing can detect. As an array the field set is a value a test can
 * compare against the mapset.
 */
export interface AuthDetailRow {
  /** React list identity, and the contract member name the row renders. */
  readonly key: string;
  /** The mapset's own painted label for this row. */
  readonly label: string;
  /** The value as the service rendered it, already normalised away from `null`. */
  readonly value: string;
  /** Token for the value's measured `COLOR=` operand, resolved by the caller against the theme. */
  readonly tone: ColorTokenName;
  /** Whether the value is a fixed-width datum whose columns must align. */
  readonly fixedPitch: boolean;
}

/**
 * Normalises a nullable contract member into text that is safe to render.
 *
 * Assumptions: many members of this projection are declared nullable because the underlying columns
 * are, and the reference paints an empty field for each of them rather than a word. Returning the
 * empty string reproduces that; passing the member through unchanged would risk the literal `null`
 * reaching the DOM as visible text the moment it is interpolated into a template rather than rendered
 * as a lone child.
 * @param {string | null} value - The member as the service sent it.
 * @returns {string} The member, or the empty string when it carried nothing.
 */
function displayText(value: string | null): string {
  return value ?? '';
}

/**
 * Resolves the colour token the reference program writes over this field at run time.
 *
 * ⚠️ Assumptions: the approval indicator is the one field on this map whose colour is NOT the colour
 * the mapset declares. `COPAU01.bms` L114 to L118 paints `AUTHRSP` `COLOR=PINK`, but
 * `COPAUS1C.cbl` L311 to L317 overwrites the symbolic map's colour subfield on every send -- `MOVE
 * DFHGREEN TO AUTHRSPC` beside the `'A'`, `MOVE DFHRED TO AUTHRSPC` beside the `'D'` -- so the static
 * pink never reaches a terminal for this field and rendering it pink here would show a colour the
 * reference does not. The two runtime constants resolve through `DFH_RUNTIME_COLOR_TOKENS`, which
 * covers exactly the five constants the programs move, rather than through the static `COLOR=` map.
 * @param {string} authResponse - The single approval character the service derived, `A` or `D`.
 * @returns {ColorTokenName} `DFHGREEN`'s token for an approval and `DFHRED`'s for anything else.
 */
export function approvalToneToken(authResponse: string): ColorTokenName {
  return authResponse === AUTH_DETAIL_APPROVED
    ? DFH_RUNTIME_COLOR_TOKENS.DFHGREEN
    : DFH_RUNTIME_COLOR_TOKENS.DFHRED;
}

/**
 * Builds the fifteen rows of the authorization block, in the order the mapset paints them.
 *
 * Assumptions: fifteen rows, one per named value field the mapset paints in rows 7 to 15 -- card
 * number, authorization date and time on row 7; approval, response reason and authorization code on
 * row 9; amount, entry mode and source on row 11; category code, expiry and type on row 13;
 * identifier, match status and fraud status on row 15. Reading order is preserved even though
 * absolute position is not, which is the half of design gap **G1** that is kept.
 *
 * ⚠️ Assumptions: the `Auth Code:` row is present here and was absent from the rendering this replaces.
 * `COPAUS1C.cbl` L331 paints it from `PA-PROCESSING-CODE`, so a reviewer reading the migrated screen
 * saw fourteen of the fifteen values the terminal shows; the label had been declared and simply never
 * used. Its own label entry above records why the value does not match the label's wording.
 *
 * ⚠️ Assumptions: the three fields the mapset paints `COLOR=PINK` -- card number, authorization date
 * and authorization time -- resolve through `BMS_TEXT_COLOR_TOKENS.PINK` and are NOT recoloured here.
 * `COLOR=PINK` occurs exactly four times in the whole repository and all four are in this mapset
 * (L86, L95, L105 and L115), which is why the migration plan's token table does not cover it;
 * `ui/src/theme/tokens.ts` resolved it once to a heading text token, having rejected `colorPrimary`
 * and `colorInfo` there because either would collapse these values into the blue frame or into their
 * own turquoise labels. Choosing a colour here would fork that resolution.
 *
 * Assumptions: text colour resolves through `BMS_TEXT_COLOR_TOKENS` and not through the hue map
 * `BMS_COLOR_TOKENS`, which is the rule every screen in this tree follows -- the hue map answers which
 * ROLE a `COLOR=` operand carries, and the text map answers which shade of that role clears the
 * contrast threshold against the one surface this shell paints on.
 * @param {PendingAuthDetailScreen} detail - The rendering the service returned.
 * @returns {readonly AuthDetailRow[]} The block's rows, ordered as the mapset paints them.
 */
export function authorizationRows(detail: PendingAuthDetailScreen): readonly AuthDetailRow[] {
  const pink = BMS_TEXT_COLOR_TOKENS.PINK;
  const blue = BMS_TEXT_COLOR_TOKENS.BLUE;
  /*
   * WHY : Assumptions: match status and fraud status take the error token because the mapset paints
   *       both `COLOR=RED` -- `AUTHMTC` at L214 to L218 and `AUTHFRD` at L224 to L228 -- and red is a
   *       standing attribute of those two fields rather than a condition. They are therefore always
   *       red, on a matched authorization as much as on a declined one; treating red as a state and
   *       colouring them conditionally would be this screen inventing a rule the mapset does not have.
   */
  const red = BMS_TEXT_COLOR_TOKENS.RED;
  return [
    {
      key: 'cardNumber',
      label: AUTH_DETAIL_FIELD_LABELS.cardNumber,
      value: detail.cardNumber,
      tone: pink,
      fixedPitch: true,
    },
    {
      key: 'authDate',
      label: AUTH_DETAIL_FIELD_LABELS.authDate,
      value: displayText(detail.authDate),
      tone: pink,
      fixedPitch: true,
    },
    {
      key: 'authTime',
      label: AUTH_DETAIL_FIELD_LABELS.authTime,
      value: displayText(detail.authTime),
      tone: pink,
      fixedPitch: true,
    },
    {
      key: 'authResponse',
      label: AUTH_DETAIL_FIELD_LABELS.authResponse,
      value: detail.authResponse,
      tone: approvalToneToken(detail.authResponse),
      fixedPitch: false,
    },
    {
      key: 'authResponseReason',
      label: AUTH_DETAIL_FIELD_LABELS.responseReason,
      value: detail.authResponseReason,
      tone: blue,
      fixedPitch: false,
    },
    {
      key: 'processingCode',
      label: AUTH_DETAIL_FIELD_LABELS.authCode,
      value: displayText(detail.processingCode),
      tone: blue,
      fixedPitch: true,
    },
    {
      key: 'approvedAmount',
      label: AUTH_DETAIL_FIELD_LABELS.amount,
      value: detail.approvedAmount,
      tone: blue,
      fixedPitch: true,
    },
    {
      key: 'posEntryMode',
      label: AUTH_DETAIL_FIELD_LABELS.posEntryMode,
      value: displayText(detail.posEntryMode),
      tone: blue,
      fixedPitch: false,
    },
    {
      key: 'messageSource',
      label: AUTH_DETAIL_FIELD_LABELS.source,
      value: displayText(detail.messageSource),
      tone: blue,
      fixedPitch: false,
    },
    {
      key: 'merchantCategoryCode',
      label: AUTH_DETAIL_FIELD_LABELS.merchantCategoryCode,
      value: displayText(detail.merchantCategoryCode),
      tone: blue,
      fixedPitch: true,
    },
    {
      key: 'cardExpiry',
      label: AUTH_DETAIL_FIELD_LABELS.cardExpiryDate,
      value: displayText(detail.cardExpiry),
      tone: blue,
      fixedPitch: true,
    },
    {
      key: 'authType',
      label: AUTH_DETAIL_FIELD_LABELS.authType,
      value: displayText(detail.authType),
      tone: blue,
      fixedPitch: false,
    },
    {
      key: 'transactionId',
      label: AUTH_DETAIL_FIELD_LABELS.transactionId,
      value: detail.transactionId,
      tone: blue,
      fixedPitch: true,
    },
    {
      key: 'matchStatus',
      label: AUTH_DETAIL_FIELD_LABELS.matchStatus,
      value: detail.matchStatus,
      tone: red,
      fixedPitch: false,
    },
    {
      key: 'fraudMark',
      label: AUTH_DETAIL_FIELD_LABELS.fraudStatus,
      value: detail.fraudMark,
      tone: red,
      fixedPitch: false,
    },
  ];
}

/**
 * Builds the five rows of the merchant block, in the order the mapset paints them.
 *
 * Assumptions: every one of these five is painted `COLOR=BLUE` -- `MERNAME` at `COPAU01.bms` L239 to
 * L243 through `MERZIP` at L279 to L283 -- so the block carries one tone rather than a per-row one.
 *
 * Assumptions: the name, city and state are NOT rendered in the code face while the identifier and
 * postal code are. The three excluded are proportional free text with nothing to align against, and a
 * fixed-pitch face would only make them wider; the two included are fixed-width data whose columns
 * the character grid used to align for nothing.
 * @param {PendingAuthDetailScreen} detail - The rendering the service returned.
 * @returns {readonly AuthDetailRow[]} The block's rows, ordered as the mapset paints them.
 */
export function merchantRows(detail: PendingAuthDetailScreen): readonly AuthDetailRow[] {
  const blue = BMS_TEXT_COLOR_TOKENS.BLUE;
  return [
    {
      key: 'merchantName',
      label: AUTH_DETAIL_FIELD_LABELS.merchantName,
      value: displayText(detail.merchantName),
      tone: blue,
      fixedPitch: false,
    },
    {
      key: 'merchantId',
      label: AUTH_DETAIL_FIELD_LABELS.merchantId,
      value: displayText(detail.merchantId),
      tone: blue,
      fixedPitch: true,
    },
    {
      key: 'merchantCity',
      label: AUTH_DETAIL_FIELD_LABELS.merchantCity,
      value: displayText(detail.merchantCity),
      tone: blue,
      fixedPitch: false,
    },
    {
      key: 'merchantState',
      label: AUTH_DETAIL_FIELD_LABELS.merchantState,
      value: displayText(detail.merchantState),
      tone: blue,
      fixedPitch: false,
    },
    {
      key: 'merchantZip',
      label: AUTH_DETAIL_FIELD_LABELS.merchantZip,
      value: displayText(detail.merchantZip),
      tone: blue,
      fixedPitch: true,
    },
  ];
}

/**
 * Turns declared rows into the entries antd's `Descriptions` renders.
 *
 * Assumptions: the label is wrapped in `Typography.Text` so it can carry the turquoise the mapset
 * paints every one of these labels -- `COLOR=TURQUOISE` on all twenty, from L80 to L82 through L274 to
 * L278 -- rather than inheriting the component's own header colour. The design system supplies the
 * component and the token; neither the element nor the colour is written by hand here.
 * @param {readonly AuthDetailRow[]} rows - The block's declared rows.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @returns {AuthDetailDescriptionItem[]} The entries, ready for the `items` prop.
 */
function toDescriptionItems(
  rows: readonly AuthDetailRow[],
  tokens: AntdCssVariables,
): AuthDetailDescriptionItem[] {
  const labelStyle: CSSProperties = { color: tokens[BMS_TEXT_COLOR_TOKENS.TURQUOISE] };
  return rows.map(
    /**
     * Renders one declared row as a description entry.
     * @param {AuthDetailRow} row - The row to render.
     * @returns {AuthDetailDescriptionItem} That row as a description entry.
     */
    (row: AuthDetailRow): AuthDetailDescriptionItem => ({
      key: row.key,
      label: <Typography.Text style={labelStyle}>{row.label}</Typography.Text>,
      children: <Typography.Text style={valueCellStyle(tokens, row)}>{row.value}</Typography.Text>,
    }),
  );
}

/**
 * Builds the style for one value cell from its measured tone and width class.
 *
 * ⚠️ Trade-offs: the amount reaches this function as an exact decimal STRING and is rendered
 * unchanged, in the fixed-pitch face. `PA-APPROVED-AMT` is `PIC S9(10)V99 COMP-3` at
 * `cpy/CIPAUDTY.cpy` L35 -- packed decimal -- so any pass through a JavaScript number would put it
 * through an IEEE-754 binary64 double, which cannot represent most scale-two fractions exactly, and a
 * cent the service computed could render as a different cent. That failure mode is the worst available
 * here because it produces a plausible figure rather than an error, which is why no arithmetic, no
 * `Number`, no `parseFloat` and no locale formatting is applied. What the fixed-pitch face buys back is
 * the column alignment the character grid gave away for free.
 *
 * ⚠️ Trade-offs: the reference's own display mask is `WS-AUTH-AMT PIC -zzzzzzz9.99` at
 * `COPAUS1C.cbl` L52 -- twelve characters holding only EIGHT integer positions against a
 * ten-digit source -- so a sufficiently large amount is truncated on the terminal. This screen
 * renders the exact value the service sent and deliberately does not reproduce that truncation:
 * reproducing it would misstate money, which is the one class of divergence this migration will not
 * carry across for fidelity's sake. The divergence is registered rather than silent.
 *
 * Assumptions: long text is allowed to break within a word, because the merchant name and city are
 * free text wide enough to push out of a cell on a narrow viewport.
 * @param {AntdCssVariables} tokens - The theme's CSS-variable references.
 * @param {AuthDetailRow} row - The row whose style is wanted.
 * @returns {CSSProperties} The style for that row's value.
 */
function valueCellStyle(tokens: AntdCssVariables, row: AuthDetailRow): CSSProperties {
  const base: CSSProperties = { color: tokens[row.tone], overflowWrap: 'break-word' };
  return row.fixedPitch ? { ...base, fontFamily: tokens[TYPOGRAPHY_TOKENS.fixedPitchData] } : base;
}

/**
 * A sentence to paint once a read completes, in place of the row's own.
 *
 * Assumptions: the severity travels with the text rather than being inferred from it, because the
 * reference decides it per SENTENCE and not per wording, and this screen paints three severities
 * whose text carries no marker distinguishing them. A completed fraud write is a success -- reached
 * only from the `STATUS-OK` arm at `COPAUS1C.cbl` L531 to L538. The end of the authorization set is
 * information -- `PROCESS-PF8-KEY` at L281 to L284 leaves `WS-ERR-FLG` alone. Everything else is a
 * refusal, each of which sets that flag first. Inferring severity from the string would mean matching
 * on message text, which breaks the moment a sentence is reworded in its catalog.
 *
 * ⚠️ Refactoring Rationale: this description cited `L74-L75` for the fraud sentences and called success
 * the screen's ONLY non-error severity. Both were wrong: those lines are entries of the decline-reason
 * table, and the end-of-set boundary is a second non-error severity that this screen was reporting as
 * an error.
 */
interface ScreenAnnouncement {
  /** The sentence to paint on the message line. */
  readonly text: string;
  /** The severity the reference painted that sentence in. */
  readonly severity: MessageBandSeverity;
}

/**
 * Renders one pending authorization and the two transitions a reviewer may make from it.
 * @returns {ReactElement} The authorization detail screen, or a bounded result when the route names no
 *   authorization.
 */
export function AuthDetailScreen(): ReactElement {
  const navigate = useNavigate();
  /*
   * WHY : Assumptions: `cssVar` rather than `token`, for the reason every screen in this tree records --
   *       `token` holds RESOLVED values, so writing one into a style attribute bakes today's palette
   *       into the element and opts it out of the CSS-variable surface antd 6 themes through.
   */
  const { cssVar } = theme.useToken();
  /*
   * WHY : Assumptions: the parameter is spelled `key`, which is the segment
   *       `authorizationDetailPath` in `ui/src/screens/authSummary/index.tsx` fills and the name
   *       {@link AUTHORIZATION_DETAIL_ROUTE} declares. The spelling is load-bearing: `useParams`
   *       resolves an unmatched name to `undefined` with no diagnostic, so a near-miss would render
   *       this screen's no-selection result on every visit -- a screen permanently empty for a reason
   *       nothing reports.
   * WHY : Assumptions: what it carries is an opaque SELECTOR and is passed back to the service
   *       unchanged, never parsed. `authorizationDetailPath` records why: the token stands for a
   *       composite row key held as two integer columns, so any structure inferred from its bytes would
   *       be inference about an encoding no screen owns.
   */
  const { key: selector } = useParams<{ key: string }>();

  const [detail, setDetail] = useState<PendingAuthDetailScreen | null>(null);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');
  const [busy, setBusy] = useState(false);
  /*
   * WHY : ⚠️ Refactoring Rationale: whether the fraud confirmation is showing is STATE here, where the
   *       `Popconfirm` used to manage its own visibility from its trigger. Browser validation found the
   *       consequence: the confirmation guarded only the one button it wrapped, while the function-key
   *       bar's own F5 button -- equally pointer-activatable, and carrying the identical accessible
   *       name -- wrote immediately, as did the F5 key. Two controls with one name and two different
   *       safety semantics is worse than either alone, and it contradicted this screen's own recorded
   *       reason for the guard: that a POINTER can activate a control by accident. Lifting the
   *       visibility into state lets every entry point open the same prompt, so there is exactly one
   *       write path and it is guarded. Alternatives Considered: giving the bar's F5 an empty label so
   *       it renders no button, leaving the wrapped one as the only pointer path. Rejected because the
   *       mapset paints `F5=Mark/Remove Fraud` on row 24 and dropping it would lose a legend field the
   *       terminal shows.
   */
  const [fraudPromptOpen, setFraudPromptOpen] = useState(false);

  const load = useCallback(
    /**
     * Reads the screen-shaped rendering of the addressed authorization.
     *
     * Assumptions: the SCREEN representation is read rather than the member record, because this screen
     * paints the reference's own rendered forms -- the masked card number, the edited amount, the
     * formatted date and time and the fraud status -- and the service derives every one of them from
     * its own constants and clock. Reading the member record and formatting here would put a second
     * implementation of those renderings in the client.
     *
     * Alternatives Considered: reading `getPendingAuthorization` and transcribing each derivation into
     * this module -- the `YYMMDD` to `MM/DD/YY` and `HHMMSS` to `hh:mm:ss` edits at
     * `cbl/COPAUS1C.cbl` L297 to L306, the `MMYY` to `MM/YY` edit at L336 to L338, the `'A'`/`'D'` test
     * at L311 to L317, the composed fraud tag at L344 to L350, and the `SEARCH ALL` over the ten-entry
     * decline-reason table at L57 to L73 and L319 to L328. Rejected because the contract already
     * publishes every one of them, citing those same lines: `authResponse` is bounded to `^[AD]$`,
     * `authResponseReason` is the composed twenty-character code-separator-description including the
     * `'9999'`/`'ERROR'` no-match arm, and `fraudMark` is the composed tag or the lone separator. A
     * client copy of that table would be a second definition of one lookup, and the two would drift the
     * first time a reason code was added on one side only -- with the browser silently answering
     * `'9999-ERROR'` for a code the service knows perfectly well.
     * Refactoring Rationale: the second parameter exists because without it a re-read ERASED the outcome
     * it was performed to reflect. The fraud transition below reports the write's own sentence and then
     * re-reads the row, and this handler set the message from the row it read -- which carries no
     * sentence -- so the operator saw the sentence for one frame and then an empty band. The reference
     * does the opposite: `MARK-AUTH-FRAUD` moves its sentence into the message field and only THEN
     * re-sends the map, so the repopulated map still carries it. Passing the announcement into the read
     * reproduces that order.
     *
     * Alternatives Considered: setting the message after the read resolved, at the call site. Rejected
     * because the read is asynchronous and the call site would have to know when it had finished, which
     * is knowledge this function already has -- and a call site that guessed would restore exactly the
     * race that produced the empty band.
     * @param {string} rowKey - The sealed selector to read.
     * @param {ScreenAnnouncement} [announce] - Sentence to paint once the read completes, in place of
     *   whatever sentence the row itself carries. Omitted for a plain read.
     * @returns {void} Completion is represented by this screen's own state.
     */
    (rowKey: string, announce?: ScreenAnnouncement): void => {
      setLoading(true);
      getPendingAuthorizationScreen(rowKey).then(
        /**
         * Publishes the rendering, together with the announcement or the row's own sentence.
         * @param {PendingAuthDetailScreen} screen - The rendering the service returned.
         * @returns {void} Completion is represented by this screen's own state.
         */
        (screen) => {
          setDetail(screen);
          setMessage(announce === undefined ? screen.message : announce.text);
          setSeverity(announce === undefined ? 'error' : announce.severity);
          setLoading(false);
        },
        /**
         * Reports a failed read on the message line.
         * @param {unknown} failure - Whatever the read rejected with.
         * @returns {void} Completion is represented by this screen's own state.
         */
        (failure: unknown) => {
          setMessage(detailFailureMessage(failure));
          setSeverity('error');
          setLoading(false);
        },
      );
    },
    [],
  );

  useEffect(
    /**
     * Reads the addressed authorization on mount, and again when the route names a different one.
     * @returns {void} Completion is represented by this screen's own state.
     */
    function loadAddressedAuthorization(): void {
      if (selector === undefined) {
        setLoading(false);
        return;
      }
      load(selector);
    },
    [load, selector],
  );

  /**
   * Submits the fraud transition PF5 asks for and reports the sentence the service answers with.
   *
   * Assumptions: the record is re-read after a successful transition rather than being patched in
   * place, because the reference paints the same map again after `MARK-AUTH-FRAUD` and that map is
   * repopulated from the row. Patching the rendered tag locally would leave every other rendered value
   * -- including the report date the write sets -- describing the row as it was before the write.
   * @returns {void} Completion is represented by this screen's own state.
   */
  function submitFraudTransition(): void {
    if (selector === undefined || detail === null || busy) {
      return;
    }
    setBusy(true);
    setFraudPromptOpen(false);
    /*
     * WHY : ⚠️ Refactoring Rationale: exactly ONE call is issued, where the reference issues two writes
     *       against two resource managers. `MARK-AUTH-FRAUD` replaces the authorization detail segment
     *       through `UPDATE-AUTH-DETAILS` at L520 to L552 and reaches `COPAUS2C` by `EXEC CICS LINK` at
     *       L248 to L252, which inserts a fraud row and falls back to an update on `SQLCODE = -803`;
     *       the two are joined by a two-phase commit. Both records live in one PostgreSQL schema in
     *       the target, so that distributed transaction is eliminated rather than emulated and the
     *       service performs both writes in a single local transaction. Alternatives Considered:
     *       mirroring the reference by calling a detail update and a fraud update in sequence from
     *       here. Rejected because a client-side two-step reintroduces a partial state that no longer
     *       exists -- a detail segment moved while the fraud row was not -- and it would put a
     *       transaction boundary in a browser, where a closed tab is an abandoned commit.
     * WHY : Assumptions: no report date is sent, and the request carries only the action. `COPAUS2C.cbl`
     *       L91 to L101 derives the date from `EXEC CICS FORMATTIME` and its SQL uses `CURRENT DATE`,
     *       so the stamp is the server's; accepting one from a caller would let a browser decide when a
     *       fraud report was made.
     */
    const action = nextFraudAction(detail.fraudMark);
    setAuthorizationFraudState(selector, { action }).then(
      /**
       * Reports the reference program's own confirmation for the submitted transition and re-reads.
       * @returns {void} Completion is represented by this screen's own state.
       */
      () => {
        setBusy(false);
        /*
         * WHY : Assumptions: the severity is `success`, the one place on this screen a non-error
         *       severity is used, because `COPAUS1C.cbl` L531 to L538 reaches those two sentences only
         *       from the `STATUS-OK` arm after taking a syncpoint -- they report a completed write
         *       rather than a refusal. The sentence itself is chosen by `fraudOutcomeMessage`, whose
         *       block records why the service's own outcome sentence is deliberately not painted.
         */
        load(selector, { text: fraudOutcomeMessage(action), severity: 'success' });
      },
      /**
       * Reports a refused transition on the message line, leaving the rendering as it was.
       * @param {unknown} failure - Whatever the write rejected with.
       * @returns {void} Completion is represented by this screen's own state.
       */
      (failure: unknown) => {
        setBusy(false);
        setMessage(detailFailureMessage(failure));
        setSeverity('error');
      },
    );
  }

  /**
   * Opens the fraud confirmation, which is the single guarded entry to the write.
   *
   * Assumptions: a named declaration rather than an inline arrow, because the documentation gate
   * requires a block on a function expression in any position. It exists at all so the button beside
   * the record and the function-key bar's button reach the prompt by the same call rather than one
   * relying on the component's own trigger and the other on this screen's state.
   * @returns {void} Completion is represented by this screen's own state.
   */
  function openFraudPrompt(): void {
    if (detail !== null && !busy) {
      setFraudPromptOpen(true);
    }
  }

  /**
   * Steps to the next authorization for the same account, which is this program's PF8.
   *
   * Assumptions: the end of the account's authorizations is reported with the reference's own sentence
   * `'Already at the last Authorization...'` and the screen stays on the row it is showing, which is
   * what `PROCESS-PF8-KEY` does -- it does not clear the map. The service reports the condition through
   * its own `endOfData` member and its own sentence, so neither is decided here.
   * @returns {void} Completion is represented by a route change or by this screen's own state.
   */
  function stepToNextAuthorization(): void {
    if (selector === undefined || busy) {
      return;
    }
    setBusy(true);
    getNextPendingAuthorization(selector).then(
      /**
       * Enters the next authorization, or reports that there is none.
       * @param {object} next - The next authorization, or the end-of-data report.
       * @returns {void} Completion is represented by a route change or by this screen's own state.
       */
      (next) => {
        setBusy(false);
        if (next.authorization === null) {
          setMessage(next.message ?? DETAIL_MESSAGES.ALREADY_AT_THE_LAST_AUTHORIZATION);
          /*
           * WHY : ⚠️ Refactoring Rationale: the end of the set is reported as INFORMATION, and it used
           *       to be reported as an error. The reference distinguishes the two explicitly:
           *       `PROCESS-PF8-KEY` at L281 to L284 sets `SEND-ERASE-NO` and moves the sentence into
           *       `WS-MESSAGE` while leaving `WS-ERR-FLG` alone, whereas every genuine refusal on this
           *       screen -- L453, L479, L508, L542, L593 -- moves `'Y'` into that flag first. Reaching
           *       the oldest authorization beneath an account is a boundary a reviewer walks into
           *       normally, so colouring it as a failure would report ordinary navigation as a fault.
           *       The service agrees: it answers this with a success carrying `endOfData`, not a 404.
           */
          setSeverity('info');
          return;
        }
        /*
         * WHY : Assumptions: the step is a ROUTE change rather than a state replacement, so the address
         *       bar names the authorization on the glass. The reference re-sends the same map with the
         *       next row's values because a terminal has no address; a browser does, and leaving the
         *       previous selector in the path would make a reload show a different row than the screen
         *       was showing.
         */
        navigateSafely(navigate, `/authorizations/${encodeURIComponent(next.authorization.key)}`);
      },
      /**
       * Reports a failed step on the message line.
       * @param {unknown} failure - Whatever the read rejected with.
       * @returns {void} Completion is represented by this screen's own state.
       */
      (failure: unknown) => {
        setBusy(false);
        setMessage(detailFailureMessage(failure));
        setSeverity('error');
      },
    );
  }

  /*
   * WHY : Assumptions: exactly the four attention identifiers `COPAUS1C.cbl` L180-L197 admits are bound
   *       -- Enter, PF3, PF5 and PF8 -- and the `WHEN OTHER` arm both re-runs the Enter path AND
   *       reports `CCDA-MSG-INVALID-KEY`, which is why the rejection handler below re-reads as well as
   *       painting the sentence. That combination is unusual among these programs and is the
   *       reference's own: the card detail screen coerces silently, the menus report without re-running.
   */
  const { bindings, invoke } = usePfKeys(
    {
      ENTER: {
        /**
         * Re-reads the authorization on display, which is this program's `PROCESS-ENTER-KEY`.
         * @returns {void} Completion is represented by this screen's own state.
         */
        onInvoke: () => {
          if (selector !== undefined) {
            load(selector);
          }
        },
      },
      PFK03: {
        /**
         * Returns to the summary screen the selection was made on.
         * @returns {void} Completion is represented by the route change.
         */
        onInvoke: () => {
          navigateSafely(navigate, AUTHORIZATION_SUMMARY_ROUTE);
        },
        label: AUTH_DETAIL_KEY_LABELS.PFK03,
      },
      PFK05: {
        /**
         * Asks for confirmation of the fraud transition, which is the only path that writes.
         *
         * Assumptions: this OPENS the prompt rather than writing, so the key, the bar's button and the
         * button beside the record all reach the write through one guarded path. The reference's PF5
         * writes immediately; the confirmation is the documented browser addition, and it applies here
         * too because a screen cannot tell an intended key press from a mistaken one either.
         * @returns {void} Completion is represented by this screen's own state.
         */
        onInvoke: () => {
          if (detail !== null && !busy) {
            setFraudPromptOpen(true);
          }
        },
        label: AUTH_DETAIL_KEY_LABELS.PFK05,
        disabled: detail === null || busy,
      },
      PFK08: {
        /**
         * Steps to the next authorization for the same account.
         * @returns {void} Completion is represented by a route change or by the painted message.
         */
        onInvoke: () => {
          stepToNextAuthorization();
        },
        label: AUTH_DETAIL_KEY_LABELS.PFK08,
        disabled: busy,
      },
    },
    {
      /**
       * Reports an unmapped key and re-reads the row, which is the reference's combined arm.
       * @param {object} rejection - Why the key was not dispatched.
       * @returns {void} Completion is represented by this screen's own state.
       */
      onInvalidKey: (rejection) => {
        if (rejection.reason !== 'unmapped') {
          return;
        }
        if (selector !== undefined) {
          load(selector);
        }
        setMessage(INVALID_KEY_PRESSED);
        setSeverity('error');
      },
    },
  );

  /*
   * WHY : Assumptions: a route naming no authorization renders a bounded result WITHOUT the screen
   *       chrome, which is the same decision `ui/src/screens/cardDetail/index.tsx` records for its own
   *       unaddressable case: there is no record, no valid selector and nothing the function keys could
   *       act on, so rendering the frame around the message would imply a usable screen behind it. The
   *       one control offered is the exit the reference offers from the same dead end.
   */
  if (selector === undefined) {
    return (
      <Result
        status="error"
        title={UNEXPECTED_ABEND_OCCURRED}
        extra={
          <Button
            onClick={
              /**
               * Returns the reviewer to the summary screen.
               * @returns {void} Completion is represented by the route change.
               */
              () => {
                navigateSafely(navigate, AUTHORIZATION_SUMMARY_ROUTE);
              }
            }
          >
            {AUTH_DETAIL_KEY_LABELS.PFK03}
          </Button>
        }
      />
    );
  }

  if (loading) {
    return <Spin size="large" />;
  }

  /*
   * WHY : Assumptions: the two structural strings the mapset paints `COLOR=NEUTRAL` -- the caption at
   *       L75 to L79 and the merchant block heading at L229 to L233 -- take the de-emphasis token,
   *       which is the same resolution `ui/src/screens/authSummary/index.tsx` applies to its own
   *       neutral caption. `BMS_COLOR_TOKENS` maps NEUTRAL away from `colorText` deliberately, because
   *       neutral is a de-emphasis ROLE in the baseline while base text is not a role at all.
   */
  const structuralStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] };

  return (
    <Flex vertical gap="large">
      {/*
        Assumptions: the band's two identifiers and its instant all come from the SERVICE's rendering
        rather than from constants here or from a client clock. This is the one screen whose contract
        publishes them -- `PendingAuthDetailScreen` carries `transactionName`, `programName`,
        `currentDate` and `currentTime` -- because the reference program populates those six header
        slots itself. The exported constants above are the same values for a caller that needs them
        without a reading, and the rendering prefers the service's so a screen and its service cannot
        disagree about which program is on the glass.
        Assumptions: `now` is deliberately NOT passed. `ScreenHeader` renders its own date and time from
        that instant, and this service already rendered both into `currentDate` and `currentTime`; the
        two are shown as the service's own values in the record below rather than being re-derived, so
        passing an instant here as well would paint two clocks on one screen.
      */}
      <ScreenHeader
        transactionId={detail?.transactionName ?? AUTH_DETAIL_TRANSACTION_ID}
        programName={detail?.programName ?? AUTH_DETAIL_PROGRAM_NAME}
      />
      {/*
        Assumptions: the caption is rendered through `ScreenTitle` and carries the mapset's own
        `COLOR=NEUTRAL`, which the shared component would not apply on its own.
      */}
      <ScreenTitle style={structuralStyle}>{AUTH_DETAIL_SUBTITLE}</ScreenTitle>
      {detail === null ? null : (
        <>
          {/*
            Trade-offs: the record is laid out by GROUPING rather than by the mapset's absolute
            coordinates, which is documented gap G1. All 54 `DFHMDF` definitions on this map carry a
            `POS=(row,col)` on a fixed 24x80 grid and none of that survives; reproducing character
            positioning in a browser is both hostile to assistive technology, which reads DOM order
            rather than coordinates, and impossible to reflow. What is kept is the two blocks the
            mapset separates with its own rule, the reading order within each, and the tab order,
            which follows the DOM order the row arrays below fix.
            Assumptions: the card number is emitted exactly as the service sent it -- this screen
            neither masks nor unmasks. The reference painted `PA-CARD-NUM` in full at L295; the target
            masks every rendering to the last four digits, and `ui/src/api/authorization.ts` asserts
            on arrival that the value HAS been masked and rejects an unmasked one. Re-masking here
            would hide a server-side masking fault the client is positioned to report.
          */}
          <Descriptions
            bordered
            column={RECORD_COLUMNS}
            items={toDescriptionItems(authorizationRows(detail), cssVar)}
          />
          {/*
           * ⚠️ Assumptions: the rank is READ from `SECTION_HEADING_LEVEL` rather than written as
           *       a literal, so this block heading stays one below the screen caption above it. The
           *       literal was 4, which was subordinate only while this screen's caption was a literal 3;
           *       `ui/src/layout/ScreenTitle.tsx` now ranks the caption, and a literal here would have
           *       become its peer. The mapset states the subordination positionally -- the merchant
           *       fields are painted below the authorization panel, not beside the caption.
           * WHY : Assumptions: this heading is the words of the mapset's literal and NOT the rule of
           *       hyphens that follows them to column 76 at L232 to L233. The hyphens were a character-
           *       grid device for drawing a horizontal rule, and gap G1 gives up that grid, so a run of
           *       them would render a length that means nothing in a reflowing layout. The boundary
           *       they drew is carried by this heading plus the second bordered block beneath it.
           */}
          <Typography.Title level={SECTION_HEADING_LEVEL} style={structuralStyle}>
            {AUTH_DETAIL_MERCHANT_HEADING}
          </Typography.Title>
          <Descriptions
            bordered
            column={RECORD_COLUMNS}
            items={toDescriptionItems(merchantRows(detail), cssVar)}
          />
          {/*
            Assumptions: the fraud transition is confirmed before it is submitted, and the confirmation
            is a browser addition rather than a reference behaviour -- the terminal's PF5 wrote
            immediately. It is added because a pointer can activate a control by accident where a
            function key cannot, and marking a live authorization as fraud is not reversible without a
            second write. The design-system mapping assigns `Popconfirm` to exactly this role.
            Assumptions: the control's label is the mapset's own row-24 legend text, so no new wording
            is introduced for it.
          */}
          <Flex gap="small">
            <Popconfirm
              title={AUTH_DETAIL_KEY_LABELS.PFK05}
              okType="danger"
              open={fraudPromptOpen}
              onOpenChange={setFraudPromptOpen}
              onConfirm={submitFraudTransition}
            >
              <Button danger disabled={busy} onClick={openFraudPrompt}>
                {AUTH_DETAIL_KEY_LABELS.PFK05}
              </Button>
            </Popconfirm>
            <Button disabled={busy} onClick={stepToNextAuthorization}>
              {AUTH_DETAIL_KEY_LABELS.PFK08}
            </Button>
          </Flex>
        </>
      )}
      {/*
        Assumptions: the shared band is used at its own 75-character contract even though this map's
        `ERRMSG` is `LENGTH=78` at L284 to L287 and the program's `WS-MESSAGE` is `PIC X(80)` at L37.
        The band implements the `CCARD-ERROR-MSG` / `CCARD-RETURN-MSG` width that `app/cpy/CVCRD01Y.cpy`
        L38 to L40 declares, which is the width the majority of these screens carry, and it is passed
        this mapset's name so it can account for the difference in one place. Forking a second band for
        three characters would give one contract two implementations free to drift apart, and no
        sentence this screen paints approaches even 75 characters.
        ⚠️ Assumptions: `'RPT DT: '` is deliberately absent from everything this band can show.
        `cbl/COPAUS1C.cbl` L523 emits it with a COBOL `DISPLAY`, which writes to the job log and not to
        the map -- `ERRMSG` is only ever loaded from `WS-MESSAGE` at L377 -- so it is an operator-side
        diagnostic of the service rather than screen text. Its datum is not lost to the reader: the
        report date arrives inside the composed `fraudMark` value the record block renders. Routing it
        to the band would put text on the glass that the reference never displays there.
      */}
      <MessageBand mapset={AUTH_DETAIL_MAPSET} message={message} severity={severity} />
      <PfKeyBar keys={bindings} onInvoke={invoke} />
    </Flex>
  );
}

export default AuthDetailScreen;
