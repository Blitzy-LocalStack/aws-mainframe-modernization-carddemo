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
 * ⚠️ Refactoring Rationale: the 29 field labels, the two section headings and the row-24 legend were
 * declared HERE, and they now come from `ui/src/messages/messages.ts` like every other user-visible
 * string. The justification for declaring them locally rested on a boundary sentence in that
 * catalog's own overview -- that BMS-painted static text was "deliberately not here" -- and that
 * sentence was the thing at fault, not this screen: AAP section 0.2.1.5 assigns the strings a screen
 * renders to the catalog without exception, so the boundary was reworded and the strings moved rather
 * than the rule being read down to fit them. What is bought is that one reviewable module holds every
 * value whose byte-exactness has to be checked against a mapset, with the mapset LINE recorded beside
 * each entry; what is paid is one import and one indirection per label.
 *
 * Assumptions: the twelve message SENTENCES this program emits are program literals and `88`-level
 * condition values, so those come from the catalog too, and none of them is written inline below.
 * Nothing user-visible is declared in this file.
 *
 * ⚠️ Assumptions: the six header fields the mapset paints in rows 1 and 2 -- `TRNNAME`, `TITLE01`,
 * `CURDATE`, `PGMNAME`, `TITLE02` and `CURTIME` -- are not composed here, and neither is the row-22
 * informational line, the row-23 error line nor the row-24 legend. All FOUR zones are DELEGATED to the
 * one `AppShell` that `ui/src/App.tsx` mounts, by publishing this screen's transaction identifier,
 * program name, paint instant, advisory sentence, error sentence and resolved key bindings through
 * `useShellSlot`. Nothing persistent stays in the body.
 *
 * ⚠️ Refactoring Rationale: the row-22 line was the one exception to that, on the reading that
 * `INFOMSG` at `POS=(22,23)` sits inside the screen's own field area. A browser measurement is what
 * withdrew the exception: the body-composed band's rect top was 1270.39 in an 860-pixel viewport, so
 * the operator's acknowledgement was painted 410 pixels below the fold while the frame's own row-22
 * zone stood reserved and empty. The delegation site records the full reasoning.
 *
 * Refactoring Rationale: an earlier revision of this paragraph claimed the same division of labour
 * while the module published nothing and no shell was mounted, so the delivered screen had no title
 * band at all. The two facts that make the claim true now are the publication below and the mount in
 * `ui/src/App.tsx`; the shell paints a zone if and only if a screen has delegated it, so the claim is
 * only ever as true as the call.
 *
 * Stateless re-expression of a pseudo-conversational program
 * ---------------------------------------------------------
 * Assumptions: the reference carries every scrap of continuity between screen turns in one
 * passed structure -- `app/cpy/COCOM01Y.cpy` L19-L44 -- and this screen carries none of it. Navigation
 * moves to the router, identity to the signed token the API client attaches, and the selected account
 * to an explicit request member. The re-entry discriminator `CDEMO-PGM-CONTEXT` disappears outright:
 * a handler that answers with a field-error array has no first-entry-versus-re-entry distinction left
 * to make, which is why the error highlight below is driven purely by state derived from a response.
 */

import { Descriptions, Flex, Form, Input, Result, Spin, Typography, theme } from 'antd';
import { useCallback, useRef, useState } from 'react';
import type { ChangeEvent, ComponentProps, CSSProperties, ReactElement } from 'react';
import { useLocation, useNavigate } from 'react-router';

/*
 * WHY : ⚠️ Refactoring Rationale: the single money entry point is imported, where only the mask was.
 *       The mask returns TEXT and nothing else, so every caller had to decide the sign's appearance and
 *       the whitespace mode for itself -- and this screen decided neither, which is why a padded amount
 *       painted with its padding collapsed. `renderMoney` returns the text, the sign, the token the
 *       sign is painted in and the whitespace mode the padding requires, so the decision is made once
 *       for every screen instead of once per call site.
 */
import { MONEY_PICTURES, renderMoney } from '../../format/money';
import { readAccountView } from '../../api/accounts';
import type { AccountDetail, AccountViewResponse, CustomerDetail } from '../../api/accounts';
import type { AbendDetail, ApiError, FieldError, FieldValidationState } from '../../api/types';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
import { busyAnnouncement, fieldAriaProps, fieldErrorHelp } from '../../layout/fieldHelp';
import { RECORD_VIEW_COLUMNS } from '../../layout/recordLayout';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyRejection } from '../../layout/usePfKeys';
/*
 * WHY : ⚠️ Assumptions: the three AUTHORED sentences are imported from the same catalog as the
 *       transcribed ones, which is what keeps them lawful under transformation rule T8 -- a screen may
 *       not invent operator wording, and these are declared, registered and width-checked in that
 *       module. They describe conditions this program has no wording for: a request still outstanding,
 *       and a request that never reached the service at all. A 3270 inhibited the keyboard rather than
 *       narrating, and `app/cbl/COACTVWC.cbl` composes a CICS `RESP`/`REAS` diagnostic for a failed
 *       read, which has no analogue here.
 */
import {
  ABEND_DATA_FIELDS,
  ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS,
  ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS,
  ACCOUNT_VIEW_HEADINGS,
  ACCOUNT_VIEW_KEY_LABELS,
  PERSISTENT_FAILURE_REPORT_IT,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  STATUS_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
  UNEXPECTED_ABEND_OCCURRED,
  UNEXPECTED_DATA_SCENARIO,
} from '../../messages/messages';
import {
  inApplicationRoute,
  MAIN_MENU_ROUTE,
  navigateSafely,
  screenTransitionState,
} from '../../routes/navigation';
import { FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';
import { SECTION_HEADING_LEVEL, ScreenTitle } from '../../layout/ScreenTitle';

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

/*
 * WHY : Refactoring Rationale: ⚠️ a `const ACCOUNT_ID_PREFILL_PARAM = 'accountId'` stood here, read
 *       through `useSearchParams` to seed the filter, and it has been WITHDRAWN. It reasoned that the
 *       reference reaches this screen with a selected account already in the shared communication area
 *       -- `CDEMO-ACCT-ID` at `app/cpy/COCOM01Y.cpy` L38, moved into the map field at
 *       `app/cbl/COACTVWC.cbl` L465-L469 -- and that a query member was the remaining carrier for it,
 *       the route declaring no path parameter. The premise was right and the conclusion contradicted
 *       the reason the route declares no parameter in the first place.
 *       An account identifier is one of the values this migration keeps out of a request LINE, which is
 *       why the composed read posts it in a body rather than in a path: a value in a request line is
 *       written to whatever access log the edge keeps, and that log outlives the request. A browser
 *       query member is the same disclosure with a longer tail -- it enters session history, it is sent
 *       as the `Referer` on any onward navigation, it is written to the edge log again on every reload,
 *       and a copied or bookmarked URL carries a real customer's account number to whoever receives it.
 *       Keeping the identifier out of the API request line while accepting it in the browser URL
 *       protects the shorter-lived of the two records and leaves the longer-lived one open.
 * WHY : Assumptions: nothing in this repository produced such a URL -- no screen, no route table entry
 *       and no test, the main menu navigating to the bare path -- so the withdrawal removes a reachable
 *       disclosure and no working behaviour. The
 *       screen now starts with an empty filter, which is exactly the state `IF EIBCALEN = 0` at
 *       `app/cbl/COACTVWC.cbl` L462-L463 gives the reference on first entry.
 * WHY : Alternatives Considered: keeping the hand-over and moving it into router transition state,
 *       which is the report's other option and the closer analogue of the communication area. Rejected
 *       on the disclosure above rather than on availability: the single validated navigation seam this
 *       tree transitions through takes a destination and no state, so a state-carrying hand-over would
 *       mean bypassing that seam. ⚠️ The reasoning that stood here added that "no caller exists to hand
 *       anything over -- this screen is not yet reachable from another", and that was false when it was
 *       written: `MAIN_MENU_DESTINATIONS` in `ui/src/screens/menu/index.tsx` maps `COACTVWC` to
 *       `/account/view`, so the main menu reaches this screen exactly as `app/cbl/COMEN01C.cbl` reaches
 *       `COACTVWC`. What the menu hands over is the DESTINATION and nothing else, which is the point:
 *       the reference's own `CDEMO-ACCT-ID` hand-over is not reproduced, and an operator arriving from
 *       the menu keys the account here, so the empty filter above is what EVERY arrival sees rather
 *       than only a direct one.
 */

/*
 * WHY : Refactoring Rationale: withdrawing the seed does NOT withdraw the transition state itself. A
 *       competing remedy for the same disclosure moved the identifier out of the query string and into
 *       router state, and its diagnosis was right -- a query string is part of the request line, so it
 *       reaches the edge access log and the browser's history. Withdrawing the seed outright resolves
 *       that disclosure more completely than relocating it, so the seed is gone; what remains carried in
 *       router state is the ORIGIN the exit key returns to, which is the migrated form of
 *       `CDEMO-FROM-TRANID` and `CDEMO-FROM-PROGRAM` and is no account identifier at all.
 */

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

/*
 * WHY : ⚠️ Refactoring Rationale: four groups of painted text stood here -- the two `COLOR=NEUTRAL`
 *       section headings, the eleven account-block labels, the eighteen customer-block labels and the
 *       single row-24 legend label -- each transcribed from `app/bms/COACTVW.bms` with its own mapset
 *       line and declared width. All four now live in `ui/src/messages/messages.ts` as
 *       `ACCOUNT_VIEW_HEADINGS`, `ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS`,
 *       `ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS` and `ACCOUNT_VIEW_KEY_LABELS`, with the per-entry mapset
 *       lines in `ACCOUNT_VIEW_PAINTED_TEXT_SOURCES`, and are imported above. AAP section 0.2.1.5
 *       assigns the strings a screen renders to that catalog, and moving them is also what makes their
 *       byte-exactness reviewable in one place: `Credit Limit        :` and `Current Cycle Debit :` are
 *       both `LENGTH=21` and differ only in interior spacing, which is exactly the kind of value a
 *       reviewer cannot check while it is spread across twenty-nine sites in a component.
 * WHY : Assumptions: nothing about the values changed in the move. The interior and trailing runs of
 *       spaces are still part of each value, the empty `ADDRESS_LINE_2` entry is still recorded
 *       deliberately -- the mapset paints no label for `ACSADL2`, which sat under `ACSADL1` and was
 *       identified positionally, the one identification gap G1 gives up -- and the legend is still the
 *       ten characters `'  F3=Exit '` with ENTER unadvertised, because binding a label to ENTER would
 *       paint a key this mapset does not.
 */

/**
 * The twelve message literals this program declares, keyed by their `88`-level condition names.
 *
 * Assumptions: reached through the catalog rather than transcribed, so the byte-exactness guarantee
 * is enforced in one reviewable module. Each entry carries the declaring field and its `PICTURE`
 * width alongside the text, which is how the information and error channels below stay distinguishable.
 */
const ACCOUNT_VIEW_MESSAGES = STATUS_MESSAGES.COACTVWC;

/**
 * Characters in the widest label either record block paints.
 *
 * Purpose: give both blocks ONE label measure, so their grids line up with each other.
 *
 * ⚠️ Refactoring Rationale: neither block used to state a label width, so each sized its own label
 * column to its own longest label and the two disagreed -- a browser review measured the two grids'
 * label columns differing by 28 pixels. The cause is in the data: the account block's longest label is
 * `'Credit Limit        :'` at 21 characters and the customer block's is
 * `'Government Issued Id Ref    : '` at 30, so content-driven sizing could not have agreed.
 *
 * ⚠️ Assumptions: it is COMPUTED from the two label maps rather than written as a number, so a label
 * added or lengthened in `ui/src/messages/messages.ts` moves this measure with it. A literal would be
 * correct on the day it was written and silently wrong afterwards, which is the failure the measured
 * 28-pixel gap already is.
 */
const RECORD_LABEL_MEASURE_CHARACTERS = Math.max(
  ...Object.values(ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS).map(
    /**
     * Reads one label's character count.
     * @param {string} label - The painted label, padding included.
     * @returns {number} Its length in characters.
     */
    (label: string): number => label.length,
  ),
  ...Object.values(ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS).map(
    /**
     * Reads one label's character count.
     * @param {string} label - The painted label, padding included.
     * @returns {number} Its length in characters.
     */
    (label: string): number => label.length,
  ),
);

/*
 * WHY : ⚠️ Assumptions: the measure is expressed in `ch` -- the design system's own mechanism for
 *       sizing from a declared character count, which `ui/src/layout/recordLayout.ts` uses for every
 *       transcribed field on the sibling screen. The labels are set in a proportional face, so a
 *       character is narrower than a `ch` and the measure binds as a floor for both blocks rather
 *       than clipping either; an auto-layout table treats it as a minimum, so a label can never be
 *       cut off by it.
 * WHY : Alternatives Considered: `tableLayout: 'fixed'` with the same width, which would make the
 *       column EXACTLY this measure in both blocks instead of at least it. Rejected because fixed
 *       layout also equalises the two VALUE columns, and the values are not equal -- a
 *       fifty-character address line shares a row with a two-character state code -- so it would
 *       trade a label-column asymmetry for wrapped or clipped values.
 * WHY : Alternatives Considered: `whiteSpace: 'pre'` on the labels, so the mapset's own padding spaces
 *       survive and the padded colons align. Rejected on the data: the padding is NOT uniform. The
 *       five money labels are each padded to exactly 21 characters with the colon last, but their
 *       neighbours run from `'Opened:'` at 7 to `'Account Number :'` at 16, and the customer block
 *       from `''` to 30 -- so preserving the runs would align one group of five and open ragged
 *       internal gaps in every other label. The strings themselves are carried verbatim in the DOM
 *       either way, which is what the fidelity contract asks; only their rendered whitespace collapses.
 */

/** One shared label-column measure, applied to both record blocks. */
const RECORD_LABEL_STYLE: CSSProperties = {
  inlineSize: `${String(RECORD_LABEL_MEASURE_CHARACTERS)}ch`,
};

/*
 * WHY : ⚠️ Refactoring Rationale: the response's return message is routed by MEANING from here on,
 *       where it was published on the refusal line unconditionally. A browser review measured the
 *       consequence: the service answered a successful read with `informationMessage: null` and
 *       `returnMessage: 'Details of selected account shown above'`, and the screen painted that
 *       ACKNOWLEDGEMENT in `ant-alert-error` styling inside an assertive `role="alert"` -- so a screen
 *       reader interrupted itself to announce a success as a failure, in the refusal colour.
 * WHY : ⚠️ Assumptions: the routing consults the CATALOG rather than a rule about which response
 *       member a sentence arrived in, and the catalog is a real oracle for it: every entry carries the
 *       COBOL data name of the field its `88` level is declared on, so `WS-INFO-MSG` and
 *       `WS-RETURN-MSG` say which of the mapset's two lines the reference paints that sentence on.
 *       `app/cbl/COACTVWC.cbl` L110 to L137 declares the whole set, and the split is not incidental:
 *       the two advisories are on the information field and every refusal is on the return field.
 * WHY : ⚠️ Assumptions: one sentence in the set is declared by the SIBLING program rather than by this
 *       one -- `FOUND_ACCOUNT_DATA` at `app/cbl/COACTUPC.cbl` L467, also on `WS-INFO-MSG` -- and it
 *       belongs here because the account read is ONE service operation serving both screens, so its
 *       acknowledgement can reach this screen. What decides the channel is the field the sentence is
 *       declared on, which does not change with the program that declares it.
 * WHY : Alternatives Considered: scanning every program's block for a matching text and routing on
 *       whatever field it finds. Rejected as too loose in both directions -- a sentence two programs
 *       declare on two different fields would route by whichever block was scanned first, and a screen
 *       would silently accept advisory text from a program that cannot answer it. Naming the entries
 *       keeps the set auditable and lets a case assert that every member really is an information-line
 *       entry, which is the property the routing depends on.
 */

/** Catalogued entries whose own declaring field puts them on the row-22 information line. */
const ADVISORY_ENTRIES = [
  ACCOUNT_VIEW_MESSAGES.WS_PROMPT_FOR_INPUT,
  ACCOUNT_VIEW_MESSAGES.WS_INFORM_OUTPUT,
  STATUS_MESSAGES.COACTUPC.FOUND_ACCOUNT_DATA,
] as const;

/** COBOL data name of the field the reference paints on row 22. */
export const INFORMATION_LINE_FIELD = 'WS-INFO-MSG';

/** The advisory sentences, indexed for lookup by the router below. */
export const ACCOUNT_VIEW_ADVISORY_SENTENCES: ReadonlySet<string> = new Set(
  ADVISORY_ENTRIES.map(
    /**
     * Reads one catalogued entry's text.
     * @param {(typeof ADVISORY_ENTRIES)[number]} entry - The catalogued message.
     * @returns {string} Its literal text.
     */
    (entry: (typeof ADVISORY_ENTRIES)[number]): string => entry.text,
  ),
);

/** The declaring field of each advisory entry, so a case can check the set against the catalog. */
export const ACCOUNT_VIEW_ADVISORY_FIELDS: readonly string[] = ADVISORY_ENTRIES.map(
  /**
   * Reads one catalogued entry's declaring field.
   * @param {(typeof ADVISORY_ENTRIES)[number]} entry - The catalogued message.
   * @returns {string} The COBOL data name the entry's `88` level is declared on.
   */
  (entry: (typeof ADVISORY_ENTRIES)[number]): string => entry.field,
);

/** Which line each of a response's two sentences belongs on, once routed by meaning. */
export interface AccountViewChannels {
  /** The row-22 advisory to paint, which the reference guarantees is never empty. */
  readonly information: string;
  /** The row-23 refusal to paint, or `null` when the turn produced none. */
  readonly refusal: string | null;
}

/**
 * Routes a read's two sentences onto the two lines the reference declares them on.
 *
 * Purpose: keep an acknowledgement off the refusal line, and keep one sentence from being painted on
 * both lines at once.
 *
 * Assumptions: an advisory arriving in the return member is moved to the information line rather than
 * dropped, because the reference paints that sentence somewhere and the operator is entitled to read
 * it -- what is wrong is only WHICH line carries it.
 *
 * Assumptions: an information member the service supplied WINS over an advisory in the return member,
 * because the service named the channel explicitly there; the return member's advisory is then
 * dropped rather than painted twice, which is the duplicate the review reported as contradictory.
 *
 * Trade-offs: a sentence the catalog does not know is treated as a REFUSAL. That is the conservative
 * reading in a browser: an unrecognised sentence painted as an advisory would be announced politely
 * and could be a genuine failure, whereas one painted as a refusal is announced and is at worst
 * over-emphatic. Every sentence this operation can answer with is catalogued, so the fallback is
 * reached only if the service starts saying something new.
 * @param {string | null} informationMessage - The response's information member, as published.
 * @param {string | null} returnMessage - The response's return member, as published.
 * @param {string} promptFallback - The sentence the reference guarantees on row 22 when neither
 *   member supplies one.
 * @returns {AccountViewChannels} The sentence for each line, deduplicated across the two.
 */
export function routeAccountViewChannels(
  informationMessage: string | null,
  returnMessage: string | null,
  promptFallback: string,
): AccountViewChannels {
  const returnIsAdvisory =
    returnMessage !== null && ACCOUNT_VIEW_ADVISORY_SENTENCES.has(returnMessage);
  const information =
    informationMessage ??
    (returnIsAdvisory && returnMessage !== null ? returnMessage : promptFallback);

  return {
    information,
    refusal: returnIsAdvisory || returnMessage === information ? null : returnMessage,
  };
}

/*
 * WHY : Assumptions: the two labels beside the abend detail are the baseline's OWN data names, read out
 *       of the catalog's `ABEND-DATA` table rather than written as prose here, so the diagnostic surface
 *       names the fields an operator quoting it would find in `app/cpy/CSMSG02Y.cpy`. The table is a
 *       four-entry tuple in declaration order -- code, culprit, reason, message -- and the first two are
 *       the members that need a label, the other two being rendered as the surface's own title and
 *       subtitle. The account-UPDATE screen derives its labels from the same two entries, which is what
 *       keeps the two abend surfaces naming one set of fields.
 */

/** Field names the catalog records for the two abend members this screen labels. */
const ABEND_LABELS = {
  abendCode: ABEND_DATA_FIELDS[0].field,
  abendCulprit: ABEND_DATA_FIELDS[1].field,
} as const;

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
 * Assumptions: the rows are DATA rather than markup, so the account and customer fields are declared
 * once as ordered arrays and rendered by one code path. The alternative -- a hand-written
 * `Descriptions.Item` element per field -- would put the declaration order that this screen must
 * preserve into the shape of the JSX, where nothing can assert it; as an array the order is a value a
 * test can compare against the mapset.
 */
interface RecordRow {
  readonly key: string;
  readonly label: string;
  readonly value: string;
  /**
   * A second line rendered under {@link RecordRow.value} within the same labelled item.
   *
   * Assumptions: exactly one row uses this, and it exists because exactly one pair of mapset fields
   * shares a single painted label -- `ACSADL1` at `POS=(16,10)` and `ACSADL2` at `POS=(17,10)` under
   * the one `Address:` at `POS=(16,1)`. Modelling that as a continuation of one row rather than as two
   * rows is what lets the painted label name both lines without a label being invented for the second
   * or the first being repeated; the same reasoning is recorded on `customerBlockRows` below, which is
   * where the painted labels are now held -- a `CUSTOMER_BLOCK_FIELD_LABELS` map that carried them
   * separately is withdrawn, the labels being data on the rows themselves.
   */
  readonly continuation?: string;
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
  readonly validateStatus?: 'error';
  /**
   * Refusal text rendered beneath the control, wrapped in the element the control describes itself by.
   *
   * Assumptions: an element rather than a bare string, and the difference is the whole point. The
   * design system renders either, but only an element can carry the stable identifier that
   * `aria-describedby` on the control points at -- an unnamed form item contributes no identifier of
   * its own, so the sentence was rendered and associated with nothing. `fieldErrorHelp` in
   * `ui/src/layout/fieldHelp.tsx` builds the wrapper and derives the identifier from the control's own.
   */
  readonly help?: ReactElement;
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
  readonly state: FieldValidationState;
  readonly message: string;
  /*
   * Assumptions: the ORIGIN of a refusal decides whether its sentence is repeated beneath the
   *       control, which is why it is carried rather than discarded once the refusal is held. The
   *       reference gives a locally rejected field ONE message channel: `app/cbl/COACTVWC.cbl` L532
   *       moves `WS-RETURN-MSG` into `ERRMSGO`, and `app/cpy/CSSETATY.cpy` L17-L27 gives the field
   *       itself only a COLOUR and, when blank, the `'*'` marker -- never per-field text. A response
   *       is the other case: it supplies a per-field sentence genuinely distinct from its
   *       screen-level one, which is the mapping the migration's design-system section specifies for
   *       a field-error array, so there the help text adds information rather than repeating it.
   */
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

  // Assumptions: the all-zeroes case is tested by comparing against a run of zeroes at the
  // declared width rather than by converting the entry to a number. `app/cpy/CVCRD01Y.cpy` L34
  // declares `CC-ACCT-ID PIC X(11)` with L36 redefining it as `PIC 9(11)`, so the identifier is
  // characters on the wire and a number only inside arithmetic; converting it here would discard
  // the leading zeros that belong to the declared width and would put an eleven-digit value
  // through an IEEE-754 double on the way.
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
 * Assumptions: `fieldErrors` is the discriminating member and the test is that it is an ARRAY,
 * nothing finer. The contract declares it required on every problem document, so an object carrying
 * an array under that key is a problem document; element shapes are not inspected here, and
 * `accountIdRefusalFrom` reads only the entries whose `field` it recognises.
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
 * Assumptions: the refusal is taken from the RESPONSE and is not recomputed here, and there
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
 * Assumptions: the service-supplied sentence is rendered and NO CICS diagnostic is
 * reconstructed. What the reference shows on these paths is a composed diagnostic --
 * `'Account:… not found in Cross ref file.  Resp:… Reas:…'` at `app/cbl/COACTVWC.cbl` L747-L757, the
 * account-master and customer-master equivalents at L797-L802 and L847-L852, and
 * `WS-FILE-ERROR-MESSAGE` at L86-L105 appending `RESP` and `RESP2` -- and a CICS response code has no
 * analogue in the target at all. Its four friendly `88`-level alternatives
 * (`DID-NOT-FIND-ACCT-IN-CARDXREF`, `DID-NOT-FIND-ACCT-IN-ACCTDAT`, `DID-NOT-FIND-CUST-IN-CUSTDAT`
 * and `XREF-READ-ERROR`) are declared at L129-L136 and never `SET`, because the statements that would
 * have set them are commented out at L792 and L842, so the `IF DID-NOT-FIND-*` tests at L704 and L713
 * can never fire. Rendering the service's sentence is therefore a documented DIVERGENCE and an
 * improvement, not parity, and it also declines to disclose which of the three reads failed. It is
 * registered as **D-11** in `docs/architecture/cobol-to-service-traceability.md` section 7.2, which
 * carries the full comparison; the identifier is stated here so that a reader who wants the register
 * entry can search for it rather than having to recognise this paragraph in it.
 *
 * Trade-offs: the fallback is `Did not find this account in account master file` rather than an
 * invented sentence. `ui/src/api/accounts.ts` records that a missing account, a missing
 * cross-reference row and a missing customer row are all reported as one status, so no finer branch
 * can be established here; that literal is the reference's own wording for the commonest of the three
 * and introduces no new text.
 * ⚠️ Refactoring Rationale: a second fallback stands between the supplied sentence and the
 * account-master one, and until it did, a request that never reached the service was reported as a
 * missing account. Only the `PROBLEM` kind is a service answering: the client synthesises a document
 * for a timeout, for a dropped connection and for a body it cannot read, and those documents carry a
 * null message -- so all three fell through to the account-master literal and told an operator their
 * account does not exist when what happened is that nothing answered. That is a false statement about
 * their data, and it sends them to check an identifier that was correct.
 * @param {ApiError} problem - The normalised problem document the read rejected with.
 * @param {string | null} noAnswerSentence - The authored sentence for a request that reached no
 *   service, or `null` when the rejection came from one.
 * @returns {string} The sentence to render in the error channel of the message band.
 */
function screenMessageFrom(problem: ApiError, noAnswerSentence: string | null): string {
  const supplied = problem.message ?? '';

  /*
   * WHY : Assumptions: the ORDER is the catalog's stated one -- a service-supplied sentence verbatim,
   *       then the authored no-answer sentence, then the reference's own literal. The supplied sentence
   *       comes first unconditionally, so a service that does answer is never overruled by a
   *       classification made from its transport status.
   */
  if (supplied.trim().length > 0) {
    return supplied;
  }

  return noAnswerSentence ?? ACCOUNT_VIEW_MESSAGES.DID_NOT_FIND_ACCT_IN_ACCTDAT.text;
}

/**
 * Chooses the authored sentence for a rejection that never reached the service, if it is one.
 *
 * ⚠️ Alternatives Considered: `isApiRequestError` and `isTransientFailure` from `ui/src/api/client.ts`,
 * which is where that classification belongs and which the sibling `/account/update` screen uses.
 * Rejected here for the reason {@link problemFrom} records at length: both are `instanceof` checks, and
 * the suites under `ui/src/test/**` reject with hand-assembled doubles that carry the shape without the
 * prototype, so a screen narrowing that way answers `false` for every failure a test can build. The
 * test is therefore structural, against the two members the client publishes, exactly as the sibling
 * narrowing in this module is.
 *
 * ⚠️ Assumptions: the CLASSIFICATION itself is not re-derived. The client decides which conditions may
 * clear -- a timeout and four retryable statuses do, a dropped connection does not -- and this reads its
 * answer off the failure rather than testing a status list of its own. A second opinion here is how a
 * screen comes to invite a repeat for a failure that repeating cannot clear.
 *
 * Assumptions: a `kind` of `PROBLEM` returns `null` rather than a sentence, because that kind means the
 * service answered and its own document owns the wording.
 *
 * Assumptions: the transient sentence invites a repeat and this screen offers no control for one, which
 * is deliberate. A repeat control belongs behind the transport module's `isRepeatableFailure` and not
 * behind transience -- the two differ exactly where it matters -- and the repeat here is a key the
 * operator already has, since Enter re-reads. Nothing consults that predicate on this screen and
 * anything added later must.
 * @param {unknown} reason - The value a rejected read settled with.
 * @returns {string | null} The authored sentence for a no-answer failure, or `null` when the rejection
 *   carries no such classification or came from the service itself.
 */
function noAnswerSentenceFrom(reason: unknown): string | null {
  if (typeof reason !== 'object' || reason === null) {
    return null;
  }

  const { kind, transient } = reason as { readonly kind?: unknown; readonly transient?: unknown };

  if (typeof kind !== 'string' || kind === 'PROBLEM' || typeof transient !== 'boolean') {
    return null;
  }

  return transient ? TRANSIENT_FAILURE_TRY_AGAIN : PERSISTENT_FAILURE_REPORT_IT;
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
      label: ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.ACTIVE_STATUS,
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
      label: ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.OPEN_DATE,
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
      label: ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.CREDIT_LIMIT,
      value: detail.creditLimit,
      monetary: true,
    },
    {
      key: 'expirationDate',
      label: ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.EXPIRATION_DATE,
      value: detail.expirationDate,
      monetary: false,
    },
    {
      key: 'cashCreditLimit',
      label: ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.CASH_CREDIT_LIMIT,
      value: detail.cashCreditLimit,
      monetary: true,
    },
    {
      key: 'reissueDate',
      label: ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.REISSUE_DATE,
      value: detail.reissueDate,
      monetary: false,
    },
    {
      key: 'currentBalance',
      label: ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.CURRENT_BALANCE,
      value: detail.currentBalance,
      monetary: true,
    },
    {
      key: 'currentCycleCredit',
      label: ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.CURRENT_CYCLE_CREDIT,
      value: detail.currentCycleCredit,
      monetary: true,
    },
    {
      key: 'groupId',
      label: ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.GROUP_ID,
      value: detail.groupId,
      monetary: false,
    },
    {
      key: 'currentCycleDebit',
      label: ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.CURRENT_CYCLE_DEBIT,
      value: detail.currentCycleDebit,
      monetary: true,
    },
  ];
}

/*
 * WHY : Assumptions: the eighteen customer values are BLANK rather than absent when the customer master
 *       holds no matching row, and this constant is what the row builder is given on that arm. The map
 *       transmits the customer region's 35 unnamed `INITIAL=` literals unconditionally -- they are part
 *       of the map, not output of the program -- while `app/cbl/COACTVWC.cbl` L493 guards only the
 *       eighteen NAMED value fields, so the terminal shows every label with an empty value. Passing a
 *       blank projection through the same builder is what reproduces that with one set of labels, one
 *       ordering and one code path.
 * WHY : Alternatives Considered: rendering the block from a nullable parameter and writing
 *       `detail === null ? '' : detail.x` at each of the eighteen rows. Rejected because it puts the same
 *       ternary in eighteen places, where a nineteenth field added later would be the one that forgets
 *       it; the substitution belongs at the single point where the absent half is turned into a
 *       renderable one.
 * WHY : Trade-offs: the two nullable name and address members are `null` here rather than the empty
 *       string, so they travel the same {@link displayText} path a present-but-empty value travels. The
 *       alternative -- empty strings -- would render identically today and would diverge the moment that
 *       helper distinguished the two.
 */

/**
 * The customer region as the map transmits it when no customer row was located: labels, no values.
 *
 * WHY : Refactoring Rationale: this is EXPORTED, and it was local. The sibling account-update screen
 *       resolves the same asymmetry -- an account read whose customer member is null, rendered by
 *       readers written against a non-null customer -- and it substitutes this same blank projection at
 *       the point the answer is received. It imports the constant rather than declaring a second one so
 *       that one definition answers "what does an unpopulated customer look like" for both screens; two
 *       would be two places for a nineteenth field added later to be forgotten.
 *       Trade-offs: this is the one screen-to-screen import in the tree, and the direction is deliberate.
 *       The view screen is the one that OWNS this projection -- the substitution and its reasoning were
 *       authored here, against `app/cbl/COACTVWC.cbl`'s own behaviour of painting the customer labels
 *       with empty values when `9500-GETCUSTDATA-BYCUST` finds no row -- so importing it is a smaller
 *       deviation than either duplicating a data constant or inventing a module for one frozen record
 *       that neither screen's layer would obviously own.
 */
export const UNPOPULATED_CUSTOMER: CustomerDetail = {
  customerId: '',
  ssnMasked: '',
  dateOfBirth: '',
  ficoCreditScore: '',
  firstName: '',
  middleName: null,
  lastName: '',
  addressLine1: '',
  stateCode: '',
  addressLine2: null,
  zipCode: '',
  city: '',
  countryCode: '',
  phoneNumber1: '',
  governmentIssuedIdMasked: '',
  phoneNumber2: null,
  eftAccountId: '',
  primaryCardHolderIndicator: '',
};

/**
 * Builds the customer rows in the mapset's declaration order.
 *
 * Assumptions: the order is `app/bms/COACTVW.bms`'s -- the address fields are declared `ACSADL1`,
 * `ACSSTTE`, `ACSADL2`, `ACSZIPC`, `ACSCITY`, `ACSCTRY` -- and it is preserved as READING order rather
 * than as a claim about the grid.
 *
 * Refactoring Rationale: an earlier revision claimed the left-to-right, top-to-bottom fill "reproduces
 * the terminal's own rows 16, 17 and 18 exactly". Two changes have made that claim false and it is
 * withdrawn rather than defended. The two address lines are now one item, so the fill no longer pairs
 * `ACSADL2` with `ACSZIPC`; and the grid collapses to a single column below the design system's medium
 * breakpoint, so at a narrow width there are no pairs at all. Both are deliberate: the pairing was
 * positional identification, which gap G1 surrenders, and protecting it had cost an unlabelled cell for
 * every assistive technology and a table wider than a phone viewport. Reading order and grouping are
 * what G1 commits to preserving, and both are intact.
 * @param {CustomerDetail} detail - The customer exactly as the service sent it, with both protected
 *   identifiers already masked, or {@link UNPOPULATED_CUSTOMER} when no customer row was located.
 * @returns {readonly RecordRow[]} The seventeen rows to render, in reading order -- one fewer than the
 *   eighteen fields the mapset declares, because the two address lines share the one item their single
 *   painted label names.
 */
function customerBlockRows(detail: CustomerDetail): readonly RecordRow[] {
  return [
    {
      key: 'customerId',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.CUSTOMER_ID,
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
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.SSN,
      /*
       * Assumptions: the value is rendered EXACTLY as the service sent it and this
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
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.DATE_OF_BIRTH,
      value: detail.dateOfBirth,
      monetary: false,
    },
    {
      key: 'ficoCreditScore',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.FICO_CREDIT_SCORE,
      value: detail.ficoCreditScore,
      monetary: false,
    },
    {
      key: 'firstName',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.FIRST_NAME,
      value: detail.firstName,
      monetary: false,
    },
    {
      key: 'middleName',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.MIDDLE_NAME,
      value: displayText(detail.middleName),
      monetary: false,
    },
    {
      key: 'lastName',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.LAST_NAME,
      value: detail.lastName,
      monetary: false,
    },
    {
      // WHY : Refactoring Rationale: the two address lines are ONE row under the one label the mapset
      //       paints, where they were two rows and the second carried an empty label -- an unlabelled
      //       cell, which is what the review found. The reading order of the underlying fields is
      //       unchanged: line 1 then line 2, in `DFHMDF` declaration order, both inside the item the
      //       first one's label names.
      key: 'addressLine1',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.ADDRESS_LINE_1,
      value: detail.addressLine1,
      // WHY : Assumptions: an absent second line contributes NOTHING rather than an empty line, so the
      //       item is one line high for a customer who has no second address line. The member is
      //       nullable because the column is, and `displayText` would turn a null into an empty string
      //       that still occupied a rendered line.
      ...(detail.addressLine2 === null ? {} : { continuation: detail.addressLine2 }),
      monetary: false,
    },
    {
      key: 'stateCode',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.STATE_CODE,
      value: detail.stateCode,
      monetary: false,
    },
    {
      key: 'zipCode',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.ZIP_CODE,
      value: detail.zipCode,
      monetary: false,
    },
    {
      key: 'city',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.CITY,
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
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.COUNTRY_CODE,
      value: detail.countryCode,
      monetary: false,
    },
    {
      key: 'phoneNumber1',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.PHONE_NUMBER_1,
      // WHY : Assumptions: the number renders as one combined value, not as area code and subscriber
      //       parts. `app/cbl/COACTVWC.cbl` L517 moves `CUST-PHONE-NUM-1` whole into a single
      //       `PIC X(13)` field; the account-update twin is the screen that splits it, because it has
      //       to validate the area code against the seeded lookup table.
      value: detail.phoneNumber1,
      monetary: false,
    },
    {
      key: 'governmentIssuedIdMasked',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.GOVERNMENT_ISSUED_ID,
      value: detail.governmentIssuedIdMasked,
      monetary: false,
    },
    {
      key: 'phoneNumber2',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.PHONE_NUMBER_2,
      value: displayText(detail.phoneNumber2),
      monetary: false,
    },
    {
      key: 'eftAccountId',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.EFT_ACCOUNT_ID,
      value: detail.eftAccountId,
      monetary: false,
    },
    {
      key: 'primaryCardHolderIndicator',
      label: ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.PRIMARY_CARD_HOLDER_INDICATOR,
      value: detail.primaryCardHolderIndicator,
      monetary: false,
    },
  ];
}

/*
 * WHY : ⚠️ Refactoring Rationale: the monetary value is a COMPONENT rather than three properties
 *       spread inline at the render site, and the reason is the token. `renderMoney` returns a token
 *       NAME so that the colour question stays in the theme -- resolving it needs the design system's
 *       own token accessor, which is a hook, and the render site is inside a plain mapping function
 *       with no component boundary of its own. A component is the smallest thing that can hold a hook.
 * WHY : Alternatives Considered: threading the resolved token map down as a fourth parameter of the
 *       mapping function, which needs no component. Rejected because every one of that function's
 *       callers would then have to carry a value only one branch of it uses, and the resolved map is
 *       already available at the one place that needs it -- inside a component.
 */

/** What one monetary value needs to paint itself: the wire amount and the fixed-pitch style. */
interface MonetaryValueProps {
  /** Money exactly as the service published it, as a decimal string. */
  readonly wireAmount: string;
  /** Style carrying the fixed-pitch font token, so a column of amounts aligns. */
  readonly style: CSSProperties;
}

/**
 * Paints one monetary value with its picture, its pad preserved and its sign expressed.
 *
 * Purpose: render an amount the way the terminal did -- right-aligned inside a fixed field by a run
 * of pad blanks -- and make the three sign cases distinguishable, which the AAP requires of the
 * rendering rather than of each screen.
 *
 * Assumptions: the whitespace mode comes from the rendering rather than being written here, because
 * `renderMoney` returns it precisely so no screen has to know which pictures pad with blanks and
 * which pad with zeroes.
 * @param {MonetaryValueProps} props - The wire amount and the fixed-pitch style.
 * @returns {ReactElement} The painted amount.
 */
function MonetaryValue({ wireAmount, style }: MonetaryValueProps): ReactElement {
  /*
   * WHY : ⚠️ Assumptions: the token is resolved through `cssVar` and never through the resolved
   *       `token` map, so what reaches the element is a `var(--ant-...)` reference rather than the
   *       colour that variable currently holds. Reading the resolved value would bake today's palette
   *       into an inline style -- it would stop following a theme change, and it would put a rendered
   *       design value in the DOM where the token bridge is what the review checks. Every other colour
   *       on this screen is resolved the same way.
   */
  const { cssVar } = theme.useToken();
  const rendered = renderMoney(wireAmount, MONEY_PICTURES.accountGrouped);
  /*
   * WHY : ⚠️ Refactoring Rationale: the lookup is NARROWED by a type test where it used to be widened by
   *       `String(...)`, and the difference is that the old form was a lint error rather than a
   *       preference: `@typescript-eslint/no-base-to-string` rejects stringifying a value whose type
   *       admits an object, because such a value reaches the DOM as the literal `[object Object]`. The
   *       union here does admit one -- `renderMoney` types its token as `keyof GlobalToken`, so indexing
   *       the accessor with it yields the value type of EVERY design token, not just the colours.
   * WHY : ⚠️ Assumptions: the test is `typeof === 'string'`, which is exactly what a CSS-variable
   *       reference is, so the branch that paints is the only branch reachable for a colour token and
   *       the other exists solely to make that statement checkable. A cast was the alternative and is
   *       worse in the same way it was before: a cast asserts the fact to the compiler where this one
   *       establishes it.
   * WHY : Trade-offs: the narrowing is local, because the wide type's ORIGIN is `colorToken` on
   *       `MoneyRendering` in `ui/src/format/money.ts`, which this group may not edit. Declaring that
   *       member as the union of the three sign tokens it can actually hold would remove the need for
   *       any test at all here and at every other call site; it is reported rather than reached for.
   */
  const colourReference = cssVar[rendered.colorToken];

  return (
    <Typography.Text
      style={{
        ...style,
        whiteSpace: rendered.whiteSpace,
        /*
         * WHY : Assumptions: the colour is SPREAD in rather than assigned a value that may be nothing,
         *       because `ui/tsconfig.json` enables `exactOptionalPropertyTypes` -- under which an
         *       optional style property will not accept an explicit `undefined`.
         */
        ...(typeof colourReference === 'string' ? { color: colourReference } : {}),
      }}
    >
      {rendered.text}
    </Typography.Text>
  );
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
      /*
       * WHY : Assumptions: a continuation is stacked with `Flex vertical` rather than with a line
       *       break or a `white-space` rule, because the design system's own layout primitive is what
       *       this tree uses for every stack -- no bespoke CSS on a raw element -- and because two
       *       separate text nodes keep the two mapset fields separately addressable in the DOM, which a
       *       single joined string would not.
       */
      children: row.monetary ? (
        <Flex justify="flex-end">
          {/*
           * WHY : ⚠️ Refactoring Rationale: the baseline's `+ZZZ,ZZZ,ZZZ.99` presentation is applied
           *       HERE, and this render passed `row.value` through untouched while the module still
           *       imported the mask -- so the operator saw the raw wire text, `-1234.56`, where the
           *       terminal showed `-      1,234.56`. Money crosses the boundary through `MoneyModule`,
           *       which writes `Money.toPlainString()`, so the service cannot have applied it and
           *       nothing else did. The mask is a string transformation on the digits, so applying it
           *       parses nothing and keeps the exactness the string representation exists to protect.
           * WHY : ⚠️ Refactoring Rationale: the rendering now comes from `renderMoney` and the two
           *       properties it returns BESIDE the text are applied. Applying the mask alone was not
           *       enough and a browser review measured why: the DOM held `+      5,000.00` and computed
           *       `white-space: normal`, so the run of pad blanks collapsed to one and the amount
           *       painted as `+ 5,000.00`. The pad IS the alignment -- a suppressed picture right-aligns
           *       by padding the integer field with blanks -- so collapsing it puts two amounts of
           *       different magnitudes at different offsets down one column. Equal-width masks measured
           *       84.016 pixels with the pad preserved against 67.219 without it.
           * WHY : ⚠️ Assumptions: the SIGN's token is applied as well, from the same call. The mapset's
           *       mask is signed, so the leading `+` or `-` is in the text whatever colour is resolved
           *       -- colour is redundant here and never the sole carrier, which is what keeps this on
           *       the right side of the use-of-colour criterion -- but a credit and a debit painted in
           *       one colour is a distinction the AAP asks the rendering to make and this screen was
           *       not making.
           * WHY : Assumptions: the picture is named explicitly rather than left to the function's
           *       default, even though the default IS this picture. The account mask is
           *       `+ZZZ,ZZZ,ZZZ.99`, width 15, sourced from `app/bms/COACTVW.bms` -- five `PICOUT`
           *       occurrences -- and from `app/cbl/COACTUPC.cbl` L371; naming it means this call states
           *       which measurement it is relying on rather than inheriting whichever one the formatter
           *       currently defaults to.
           * WHY : Assumptions: it is applied at the single point every monetary row is rendered rather
           *       than at each of the five sites that build those rows. One call site cannot then
           *       disagree with another, and the `monetary` flag that selects the fixed-pitch font
           *       already marks exactly the fields the mapset gives a `PICOUT` to.
           */}
          <MonetaryValue style={monetaryStyle} wireAmount={row.value} />
        </Flex>
      ) : row.continuation === undefined ? (
        <Typography.Text>{row.value}</Typography.Text>
      ) : (
        <Flex vertical>
          <Typography.Text>{row.value}</Typography.Text>
          <Typography.Text>{row.continuation}</Typography.Text>
        </Flex>
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

/*
 * WHY : Refactoring Rationale: a frozen `SHELL_IDENTITY_SLOT` constant stood here, published by an
 *       identity-only `useShellSlot` call, and both are withdrawn in favour of the single complete
 *       publication in the component body. Its stated reason for delegating the identity ALONE was
 *       that this mapset declares two message lines while the shell paints one, so delegating either
 *       would drop the other -- and that reasoning is preserved and acted on, just not by withholding
 *       the whole slot: the row-23 `ERRMSG` line IS delegated, because the shell's single band is that
 *       row, and the row-22 `INFOMSG` line stays in the body where the mapset puts it, inside the
 *       screen's own field area. Nothing is dropped, and the legend goes with the band rather than
 *       being composed twice.
 * WHY : Trade-offs: the surviving publication is an inline object rather than a frozen constant, which
 *       gives up the explicit allocation-stability the constant made visible. It has to be inline,
 *       because it names this screen's own `bindings`, `invoke`, `paintedAt` and `errorMessage` -- all
 *       of which change with state -- and the publisher compares structurally on every commit, so a
 *       constant could not carry them.
 */

/**
 * Renders the account view screen: one account filter, and the account and customer it resolves to.
 *
 * The screen takes no props. It is mounted directly as the element of the `/account/view` route, which
 * declares no path parameter, so the only input it TYPES is the control the operator uses. It reads no
 * query member -- a deliberate withdrawal recorded above the filter's own constants -- and the one thing
 * it does read from a handover is the origin its exit key returns to, declared on
 * `ScreenTransitionState` in `ui/src/routes/navigation.ts`.
 *
 * Error paths it surfaces, all four of them distinctly:
 * - a filter that is blank, or that holds the reference's blank marker, is refused locally with
 *   `No input received`, and the marker is rendered beside the control as a decoration rather than
 *   written into its value;
 * - a filter that is not eleven digits, or is all zeroes, is refused locally with the reference's
 *   `Account Filter must  be a non-zero 11 digit number`;
 * - a rejected read renders the service's sentence in the error channel of the message band, and any
 *   field-level entry it names against the filter on the control itself;
 * - a rejection carrying a structured abend replaces the record blocks with the abend surface.
 * @returns {ReactElement} The screen: the account filter, the two record blocks once a read succeeds,
 *   the information and error message lines, and the function-key legend.
 */
export function AccountViewScreen(): ReactElement {
  /*
   * WHY : ⚠️ Refactoring Rationale: this screen publishes ONE shell slot, and three publications stood
   *       here at once -- a frozen identity-only constant, an identity-plus-instant literal, and the
   *       complete slot further down. Each was a later generation of the same remedy for the same
   *       finding (the band was unpainted because nothing delegated it, so `ACCOUNT_VIEW_TRANSACTION_ID`
   *       and `ACCOUNT_VIEW_PROGRAM_NAME` were exported and read by nothing), and keeping all three
   *       published three slots per render from one screen: the last one wins for every member it names,
   *       so the earlier two contributed nothing except two extra hook calls and, worse, an appearance
   *       of disagreement about which zones this screen delegates.
   * WHY : Assumptions: the surviving publication is the LAST one, and it has to be, because it names
   *       `bindings` and `invoke` from this screen's own `usePfKeys` call and those do not exist until
   *       after it. Its four members are the whole delegation -- identity, paint instant, the row-23
   *       error line and the key legend with this mapset's measured `COLOR=TURQUOISE` -- and what stays
   *       in the body is the row-22 informational line, which is the one message field the mapset puts
   *       inside the screen's own field area.
   * WHY : Assumptions: the paint instant is read HERE and handed up rather than read inside the shell.
   *       `ShellSlot.now` documents that as required: `ui/src/hooks/useServerInstant.ts` reads
   *       `ui/src/api/serverClock.ts`, and the shell is required to carry no dependency on the API
   *       layer. It is server-derived rather than `new Date()` because the baseline read one region
   *       clock for every terminal, so two operators reading one account across midnight must not be
   *       shown two dates -- the divergence registered as D-7.
   * WHY : Assumptions: the two identities are this screen's own constants rather than values derived
   *       from the route. `CAVW` and `COACTVWC` are what the reference painted -- `COACTVWC.cbl`
   *       L145-L146 for the transaction and L144 for the program -- and deriving them from a path
   *       would make a renamed route silently change what the band reports about the reference.
   */
  const navigate = useNavigate();
  const location = useLocation();
  const { cssVar } = theme.useToken();
  const paintedAt = useServerInstant();

  /*
   * WHY : Assumptions: the filter starts EMPTY and is seeded from nothing -- not a query member, not a
   *       path parameter, not storage. That is the reference's own first-entry state: `IF EIBCALEN = 0`
   *       at `app/cbl/COACTVWC.cbl` L462-L463 sets the prompt and sends the map with the field
   *       untouched, and `CC-ACCT-ID` -- the value L468 paints back into it -- is written from the
   *       RECEIVED map field at L632 and from nowhere else, so no carried selection ever reaches it.
   *       The withdrawn query seed is recorded above, with the disclosure that removed it.
   */
  const [entry, setEntry] = useState('');

  /*
   * WHY : Refactoring Rationale: the exit destination is resolved ONCE on entry and held, rather than
   *       recomputed on the exit key. It is the migrated form of `CDEMO-FROM-TRANID` and
   *       `CDEMO-FROM-PROGRAM`, which the reference reads at `app/cbl/COACTVWC.cbl` L328-L339 from
   *       storage a CALLING program wrote before transferring - so the value is fixed at the moment of
   *       entry by construction, and resolving it later would let a `replace` transition performed by
   *       this screen change where its own exit key goes.
   * WHY : Assumptions: the claim is resolved through `inApplicationRoute`, so only one of this
   *       application's own parameterless routes can be honoured and anything else falls back to the
   *       menu - which is the reference's own default arm, `LIT-MENUTRANID` and `LIT-MENUPGM` at L336
   *       and L337. Router state is writable by a hand-edited history entry, and an unchecked value
   *       there could send the exit key off this application entirely.
   */
  const [exitDestination] = useState<string>(
    /**
     * Resolves the origin the exit key returns to, once, on first render.
     * @returns {string} The handed-over origin when this application serves it, otherwise the main
     *   menu, which is the reference's own fallback.
     */
    (): string => inApplicationRoute(screenTransitionState(location.state).from) ?? MAIN_MENU_ROUTE,
  );

  const [refusal, setRefusal] = useState<AccountIdRefusal | null>(null);
  const [view, setView] = useState<AccountViewResponse | null>(null);
  const [abend, setAbend] = useState<AbendDetail | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  /*
   * Assumptions: the information line starts on the reference's prompt because that is the
   * state the reference guarantees. `app/cbl/COACTVWC.cbl` L462-L463 sets
   * `WS-PROMPT-FOR-INPUT` when no data was passed, and L528-L530 sets it again whenever the
   * information field is empty -- so the prompt is the information line's floor, not merely its
   * first value.
   */
  const [infoMessage, setInfoMessage] = useState<string>(
    ACCOUNT_VIEW_MESSAGES.WS_PROMPT_FOR_INPUT.text,
  );

  /*
   * WHY : Trade-offs: reads are sequenced by a token held in a ref so a slow earlier response cannot
   *       overwrite a faster later one, and so that a response cannot overwrite a refusal the operator
   *       has since earned. The reference cannot have either problem -- a pseudo-conversational task
   *       holds the terminal until it returns, so a second Enter is not deliverable until the first
   *       turn has finished -- and a browser has no such lock. Disabling the control while a
   *       read is in flight was the alternative and is rejected because it would make an unmapped key
   *       arrive as a `disabled` rejection rather than being coerced into Enter, changing the key
   *       behaviour below to work around a race. The cost is one ref and one comparison per response.
   *       Assumptions: EVERY submission advances the token, including one the field edits refuse, and
   *       the advance is the first thing `submit` does. That placement is the whole of the
   *       cancellation contract -- the reasoning is recorded at the advance itself, because it is the
   *       statement order that carries it and not this declaration.
   */
  const requestSequence = useRef(0);

  const invalidateInFlightRead = useCallback(
    /**
     * Retires whatever read is outstanding and clears the record it would have published.
     *
     * ⚠️ Refactoring Rationale: this exists because the token was advanced ONLY when a new read
     * started, which left two paths that changed what the screen claims to be showing without
     * retiring the read still in flight. Editing the filter was the first: an operator who submitted
     * account A, then typed B while A was still outstanding, was shown A's record beneath the filter
     * reading B -- a record correctly labelled by nothing on the screen. A local refusal was the
     * second: the band said `No input received` while A's account and customer stayed on display
     * beneath it. Both are states the reference cannot reach, because its task holds the terminal for
     * the whole turn, so neither is a divergence to document; they are defects of the mechanism that
     * replaced that lock.
     *
     * Assumptions: the record blocks are cleared as well as the token being advanced, and clearing is
     * the half that matters to an operator. Advancing the token alone stops the outstanding response
     * being APPLIED but leaves the previous one rendered, so the stale pairing persists until
     * something else replaces it.
     *
     * Assumptions: the abend surface is cleared too, because it replaces the record blocks and would
     * otherwise outlive the read that raised it. The message channels are NOT touched here: each
     * caller sets the sentence its own outcome carries, and the reference clears its channels once per
     * turn rather than once per keystroke.
     * @returns {void} Completion leaves no read outstanding and no record on display.
     */
    (): void => {
      requestSequence.current += 1;
      setView(null);
      setAbend(null);
      setLoading(false);
    },
    [],
  );

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
      /*
       * WHY : Assumptions: the token advances HERE, before the field edits run, because advancing it
       *       is what invalidates whatever read is already in flight -- both callbacks below compare
       *       against the token their own request captured and return when it is no longer current.
       *       Every submission supersedes the previous one, and a locally refused submission
       *       supersedes it just as a valid one does: the operator has pressed Enter on a filter this
       *       screen rejects, and the answer to the filter they typed before it is no longer the
       *       answer to the question on screen.
       *       Refactoring Rationale: this pair of statements stood AFTER the edits, on the valid path
       *       only, so a refused submission left the earlier request current. The sequence
       *       valid-submit, then invalid-submit, then the first response arriving produced a screen
       *       showing the account records and `Displaying details of given Account` while the operator
       *       was looking at a filter the screen had just refused -- the refusal silently overwritten
       *       by an answer to a superseded question. Moving the advance ahead of the edits fixes it
       *       without a second mechanism: the refusal arm needs no cancellation logic of its own,
       *       because the token it advanced is already the reason the late callback returns.
       *       Alternatives Considered: aborting the in-flight request through an `AbortController` on
       *       the client. Rejected as a larger change for the same observable outcome -- the response
       *       is discarded either way, and the read is a published client function whose signature
       *       accepts no signal, so this would push a cancellation parameter through the API layer to
       *       save one already-issued request. What it WOULD add is the release of the connection
       *       slot, which matters at a request rate this screen cannot reach: one operator pressing
       *       one key.
       */
      const normalised = normaliseAccountIdEntry(entry);
      const localRefusal = validateAccountIdEntry(normalised);

      if (localRefusal !== null) {
        /*
         * WHY : Assumptions: a local refusal RETIRES any outstanding read as well as reporting itself.
         *       Without that, the record from a read still in flight arrived after the refusal and
         *       replaced it, so a screen whose band said `No input received` finished by displaying an
         *       account -- and the operator had no way to tell which of the two outcomes was current.
         */
        invalidateInFlightRead();
        setRefusal(localRefusal);
        setErrorMessage(localRefusal.message);
        setInfoMessage(ACCOUNT_VIEW_MESSAGES.WS_PROMPT_FOR_INPUT.text);

        /*
         * WHY : Refactoring Rationale: the marker is NO LONGER written into the control's value. It
         *       was, on the argument that `app/cbl/COACTVWC.cbl` L561-L565 moves `'*'` into the
         *       field's output subfield and that only the colour half of that treatment is expressible
         *       through the form control's error state. The argument was right about the reference and
         *       wrong about where to put the marker: the value of an input is the field's DATA, so
         *       writing a decoration there made the marker the account number as far as everything
         *       reading the control was concerned -- it is what an assistive technology announces as
         *       the field's content, what a credential manager or an autofill would store, and what a
         *       copy of the field yields. The reference has no such conflation available to it,
         *       because a 3270 field has separate output-data and attribute subfields and the marker
         *       goes in the one the program writes for display.
         * WHY : Assumptions: the marker is rendered instead as an `aria-hidden` adornment beside the
         *       control -- see the `suffix` on the input below -- driven by this same refusal state. A
         *       sighted operator sees the same `'*'` in the same place; an assistive technology reads
         *       the field as empty, which it is, and hears the refusal sentence from the message band's
         *       live region. The re-entry gate the reference puts on both halves is still not
         *       reproduced, for the reason recorded on {@link accountIdRefusalFrom}.
         */
        return;
      }

      /*
       * WHY : Assumptions: a NEW read retires the outstanding one first, through the same helper the
       *       other two paths use, so the record on display is cleared while the replacement is
       *       fetched rather than lingering under a filter that no longer describes it. The reference's
       *       own turn behaves this way by construction: it re-sends the whole map, so nothing from the
       *       previous turn survives into the next one.
       */
      invalidateInFlightRead();
      setEntry(normalised);
      setRefusal(null);
      setErrorMessage(null);
      setLoading(true);

      const token = requestSequence.current;

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
          setLoading(false);

          /*
           * WHY : ⚠️ Refactoring Rationale: both lines are decided by ONE routing call, where the
           *       return member used to be assigned to the refusal line directly. A browser review
           *       drove a successful read and measured what that produced: the service answered
           *       `informationMessage: null` with `returnMessage: 'Details of selected account shown
           *       above'`, and the screen painted that acknowledgement in `ant-alert-error` styling
           *       inside an assertive `role="alert"` -- announcing a success as a failure. Routing by
           *       the catalog's own declaring field puts it on row 22, which is the line
           *       `app/cbl/COACTUPC.cbl` L467 declares it on.
           * WHY : Assumptions: the prompt is still the floor for row 22 and the citation for that is
           *       unchanged -- `WS-INFORM-OUTPUT` at `app/cbl/COACTVWC.cbl` L115-L116 is declared and
           *       never `SET`, and L528-L530 forces the prompt back whenever the information field is
           *       empty -- so it is passed IN as the fallback rather than being chosen here.
           */
          const channels = routeAccountViewChannels(
            result.account.informationMessage,
            result.account.returnMessage,
            ACCOUNT_VIEW_MESSAGES.WS_PROMPT_FOR_INPUT.text,
          );

          setErrorMessage(channels.refusal);

          /*
           * WHY : ⚠️ Assumptions: the sentence `Displaying details of given Account` is still NOT
           *       substituted here when the response supplies no information line, and the reason is
           *       unchanged by the routing: it is the `88`-level value `WS-INFORM-OUTPUT` at
           *       `app/cbl/COACTVWC.cbl` L115-L116, which the program declares and never `SET`s, while
           *       L528-L530 forces the PROMPT back whenever the information field is empty. So the
           *       reference's information line is the prompt on every turn, record on display or not,
           *       and the prompt is the floor -- which is what was passed as the fallback above. The
           *       fallback is written out rather than dropped because the contract declares the member
           *       nullable and a null must not reach the band as an empty line where the reference
           *       paints text.
           */
          setInfoMessage(channels.information);
        },
        /**
         * Reports a rejected read on the channel that matches what the rejection carried.
         *
         * Assumptions: the record blocks are cleared. The reference's screen composition is gated on
         * its read flags -- the account block on `FOUND-ACCT-IN-MASTER OR FOUND-CUST-IN-MASTER` at
         * `app/cbl/COACTVWC.cbl` L471-L472 and the customer block on `FOUND-CUST-IN-MASTER` at L493 --
         * so a failed read paints neither, and clearing the view reproduces that.
         *
         * ⚠️ Assumptions: the two blocks are NOT all-or-nothing, and clearing them both here is a
         * property of a FAILED read rather than a narrowing of the reference's two gates. A located
         * account whose customer master holds no matching row is a SUCCESS, answered as a 200 whose
         * `customer` member is null and whose return message carries the reference's own sentence naming
         * the miss: `AccountViewService.readAccountView` composes exactly that, the contract declares
         * `customer` as `oneOf` a customer and the null type, `ui/src/api/types.ts` types it
         * `CustomerDetail | null`, and the render below supplies the blank customer block for it. Only an
         * absent ACCOUNT is a 404, which is the reference's own asymmetry -- with no account row both of
         * its gates are false and neither region is painted -- so this handler, which runs on the
         * rejection, clears both blocks and the partial arm never reaches it.
         *
         * ⚠️ Refactoring Rationale: the note this replaces asserted the opposite of the delivered
         * contract in three places at once -- that the service "answers 404 when either half is absent",
         * that `account` and `customer` are "both required members" in the sense of being non-nullable,
         * and that the render "reads both halves" of one non-null view. None of the three is true, and
         * the second is the kind of claim that reads as verified: `customer` IS a required member, and it
         * is required to be PRESENT while permitted to be null, which is the distinction the sentence
         * elided. A reader who trusted it would conclude the null arm was unreachable, which is precisely
         * the conclusion the browser client had already drawn -- its redaction guard refused every
         * null-customer response until that was corrected.
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
             * Assumptions: a rejection carrying no problem document is reported with the
             * reference's own read-failure sentence and never with the thrown value's message.
             * The client throws a `RangeError` for an argument its own check refuses, and its
             * text is developer-facing; the account identifier is also a value this migration
             * keeps out of any durable diagnostic, so relaying a thrown message is the one way
             * an identifier could reach a log through this screen.
             */
            setRefusal(null);
            setAbend(null);
            setErrorMessage(ACCOUNT_VIEW_MESSAGES.XREF_READ_ERROR.text);
            return;
          }

          setRefusal(accountIdRefusalFrom(problem));
          setAbend(problem.abend);
          setErrorMessage(screenMessageFrom(problem, noAnswerSentenceFrom(reason)));
        },
      );
    },
    [entry, invalidateInFlightRead],
  );

  const exit = useCallback(
    /**
     * Leaves the screen for the origin it was entered from, or for the menu when it has none.
     *
     * ⚠️ Refactoring Rationale: BOTH arms of `app/cbl/COACTVWC.cbl` L328-L339 are now expressed, and
     * only the fallback was before. The reference prefers `CDEMO-FROM-TRANID` and
     * `CDEMO-FROM-PROGRAM` and falls back to `LIT-MENUTRANID` and `LIT-MENUPGM` -- `CM00` and
     * `COMEN01C` -- and taking the fallback unconditionally returned an operator to the menu even when
     * a different screen had sent them here, which is a behaviour the reference does not have. The
     * earlier note argued that the router publishes no named caller, and that is true of the browser's
     * HISTORY but not of the transition: a departing screen hands its own route over on
     * `ScreenTransitionState`, exactly as a calling program wrote the two fields before its `XCTL`,
     * and `inApplicationRoute` refuses anything that is not one of this application's own routes.
     *
     * Refactoring Rationale: this wrote `ACCOUNT_VIEW_MESSAGES.WS_EXIT_MESSAGE.text` --
     * `PF03 pressed.Exiting` -- to the error channel immediately before navigating, and the write has
     * been REMOVED. The old note defended it as "truthful and observable" while conceding that the
     * destination could not read it; the review found it was not observable at all, because React
     * batches the write with the transition and the route unmounts before any paint. The deeper fault is
     * that the sentence should never have been shown: `WS-EXIT-MESSAGE` is declared at
     * `app/cbl/COACTVWC.cbl` L119 with L120 as an `88`-level value of `WS-RETURN-MSG` and is `SET`
     * NOWHERE in the program. Its PF3 arm at L323-L345 moves navigation fields and transfers control and
     * emits no message at all, so the reference shows nothing on exit and removing the write is parity
     * rather than a loss.
     *
     * Assumptions: this is the SAME defect as the information line above, and the shared root cause is
     * worth naming once: two of this program's `88`-level condition names -- `WS-INFORM-OUTPUT` and
     * `WS-EXIT-MESSAGE` -- are declared and never set, and this screen had treated a declared condition
     * name as a sentence the program emits. A condition name is a value the program COULD write, and the
     * catalog necessarily holds every one of them; only the program says which are written. Both are now
     * driven by what the program does rather than by what it declares, and both catalog entries stay
     * where they are, because transformation rule T8 keeps the transcription complete whether or not a
     * given sentence is reachable.
     * @returns {void} Completion is the requested route transition.
     *
     * Assumptions: the DESTINATION is still the handed-over origin rather than the menu
     * unconditionally, which is the other half of this arm and is unaffected by withdrawing the
     * sentence. `app/cbl/COACTVWC.cbl` L323-L345 transfers to the program the caller named and falls
     * back to the menu only when it named none, so the fallback is the default arm and not the rule.
     */
    (): void => {
      navigateSafely(navigate, exitDestination);
    },
    [exitDestination, navigate],
  );

  /*
   * Assumptions: exactly the two attention identifiers `app/cbl/COACTVWC.cbl` L307-L308 admits
   * are bound, and only PF3 carries a label. The hook applies the PF13-to-PF24 folding that
   * `app/cpy/CSSTRPFY.cpy` L54-L77 performs, so that aliasing is not restated per screen, and an
   * unlabelled binding is keyboard-only -- which is what keeps ENTER working while leaving the
   * legend showing the one key the mapset paints.
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
        /*
         * WHY : ⚠️ Assumptions: risk is declared by what the ACTION does, and every action on this screen
         *       reads. `app/cbl/COACTVWC.cbl` admits exactly two attention identifiers, at L307 to L308,
         *       and neither writes anything -- this one edits the filter and performs the three reads,
         *       and the other transfers control. The declaration changes no paint here, because the
         *       emphasis fallback it displaces applies to a control this screen does not paint: this
         *       binding carries no label, so it is keyboard-only. It is declared anyway so the screen's
         *       own statement of consequence is on the record rather than inferred from a shared default.
         * WHY : ⚠️ Alternatives Considered: opening a `busy` channel here as the sibling `/account/update`
         *       screen does. DECLINED, and the reason is this screen's own tested contract: a later
         *       submission SUPERSEDES an outstanding read -- `invalidateInFlightRead` and the sequence
         *       token exist for exactly that -- so a busy predicate would silently decline the press that
         *       is meant to replace the request in the air, and the operator would have to wait out a
         *       read they had already abandoned. The screen still reports the outstanding request, on the
         *       live region below and on the form's own overlay, which is the half that was missing.
         */
        risk: 'read-only',
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
        /*
         * WHY : Assumptions: risk is `read-only` and no busy channel is opened, for the same reason as
         *       above and one more: this key issues no request at all, so it has no turn of its own to
         *       report and a reserved busy affordance here could never fill.
         */
        risk: 'read-only',
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
   * WHY : Refactoring Rationale: three of this screen's four persistent zones are DELEGATED to the one
   *       mounted `AppShell` rather than composed here, and until this call existed the delegation was
   *       an assertion in a comment with nothing behind it. The file overview said the title band was
   *       the shell's to paint, and because nothing published a slot and no shell was mounted, the band
   *       was simply missing from the rendered screen. Publishing is what makes that statement true:
   *       the shell paints a zone if and ONLY if a screen has delegated it, so a screen gets the frame
   *       by asking for it.
   * WHY : ⚠️ Refactoring Rationale: ALL THREE persistent lines go up to the shell -- row 22, row 23 and
   *       row 24 -- where row 22 used to stay in the body below. The argument for keeping it was that the
   *       split is the mapset's own: `INFOMSG` is at `POS=(22,23)` inside the screen's own field area
   *       while `ERRMSG` at `POS=(23,1)` is the last line before the legend. That reading of the mapset
   *       is correct and the conclusion drawn from it was not, which a browser measurement settled: the
   *       body-composed band's rect top was 1270.39 in an 860-pixel viewport -- the operator's
   *       confirmation painted 410 pixels BELOW the fold -- while the frame's own row-22 zone stood
   *       reserved and empty, the pinned zone holding only `["message-band","FOOTER"]`. A field at
   *       `POS=(22,23)` on a 24-row display is one row above the message line and two above the legend,
   *       which in this frame is the pinned zone; being inside the field AREA does not put it inside the
   *       scrolling CONTENT. The sibling `/account/update` screen reached the same conclusion from the
   *       same measurement and its removal site records it, so both account screens now publish the line
   *       rather than paint it.
   * WHY : Assumptions: `information` is a MEMBER of the message slot rather than a sibling of it, and it
   *       carries no `mapset` of its own. The display width is a property of the mapset and this screen
   *       stands in for exactly one, so the width already published beside the row-23 text governs both
   *       lines -- `ShellInformationSlot` in `ui/src/layout/AppShell.tsx` records that a second mapset
   *       member here would let one screen claim two widths.
   * WHY : Assumptions: the severity is stated as `neutral` rather than left to the slot's default, even
   *       though the default resolves to the same value. `app/bms/COACTVW.bms` declares `INFOMSG` as
   *       `COLOR=NEUTRAL`, which `ui/src/theme/tokens.ts` resolves to `colorTextSecondary`; naming it
   *       keeps this screen's measured colour recorded at the screen rather than inherited from a
   *       default that a later reader would have to go and check.
   * WHY : Assumptions: the line is published on EVERY turn, which is what reserves the row and gives the
   *       no-layout-shift guarantee. `infoMessage` is a non-null string whose floor is the reference's
   *       own prompt -- `app/cbl/COACTVWC.cbl` L528-L530 forces `WS-PROMPT-FOR-INPUT` back whenever the
   *       information field is empty -- so there is no turn on which this screen has nothing to publish
   *       and the `information: { text: null }` reservation form is not needed here.
   * WHY : Assumptions: the resolved `bindings` and `invoke` from this screen's own `usePfKeys` call are
   *       handed over unchanged. The shell never re-derives a binding, so this screen remains the single
   *       owner of the document key listener; and because a published `pfKeys` slot makes the shell stand
   *       its own function key down, exactly one listener is installed while this screen is mounted.
   */
  useShellSlot({
    screen: { transactionId: ACCOUNT_VIEW_TRANSACTION_ID, programName: ACCOUNT_VIEW_PROGRAM_NAME },
    now: paintedAt,
    message: {
      text: errorMessage,
      mapset: ACCOUNT_VIEW_MAPSET,
      information: { text: infoMessage, severity: 'neutral' },
    },
    /*
     * WHY : Assumptions: the legend colour is delegated explicitly and is not left to the slot's
     *       default. This mapset is one of only TWO whose row-24 legend is `COLOR=TURQUOISE` rather
     *       than the 15-of-17 majority `COLOR=YELLOW` -- `app/bms/COACTVW.bms` L369-L372 -- so omitting
     *       it would render this screen's legend in the wrong measured colour.
     */
    pfKeys: { keys: bindings, onInvoke: invoke, legendColor: 'TURQUOISE' },
  });

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
   * WHY : Assumptions: the marker takes the same colour token the refusal text does, which is the one
   *       decision `FIELD_ERROR_TOKENS` exists to hold: `app/cpy/CSSETATY.cpy` L17-L27 moves `DFHRED`
   *       into the colour subfield and `'*'` into the output subfield of the SAME field, so the two
   *       halves of that treatment share one colour by construction. Reading it through the token
   *       rather than naming a red here is what keeps that one decision in one place.
   */
  const blankMarkerStyle: CSSProperties = { color: cssVar[FIELD_ERROR_TOKENS.errorColor] };

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
        : {
            validateStatus: 'error',
            help: fieldErrorHelp(ACCOUNT_ID_FIELD_ID, refusal.message),
          };

  /*
   * WHY : Refactoring Rationale: the reference's blank marker is rendered as an adornment on the
   *       control rather than written into its value, which is the change recorded at the refusal site
   *       above. `aria-hidden` is what makes the substitution safe rather than merely different: the
   *       marker is a visual echo of a refusal an assistive technology is already told about through
   *       the band's live region, so announcing it a second time as part of the field would report a
   *       punctuation character where the operator expects to hear their account number.
   * WHY : Assumptions: the adornment is present ONLY for the blank refusal, not for every refusal.
   *       `app/cbl/COACTVWC.cbl` L561-L565 writes the marker on the blank path alone -- the templated
   *       treatment at `app/cpy/CSSETATY.cpy` L23-L25 makes the same distinction -- so a malformed
   *       eleven-digit entry is coloured and unmarked, exactly as it is on the terminal.
   */
  const blankMarkerProps =
    refusal?.state === 'BLANK'
      ? {
          suffix: (
            <Typography.Text aria-hidden="true" style={blankMarkerStyle}>
              {FIELD_ERROR_TOKENS.blankMarker}
            </Typography.Text>
          ),
        }
      : {};

  return (
    <Flex vertical gap="large">
      {/*
       * WHY : Assumptions: level 3 matches the level the sibling screens give their own row-4 title,
       *       leaving level 4 to the application heading the shell's header band renders, so the two
       *       do not compete for one slot. The secondary tone is not a colour choice made here: the
       *       mapset paints this heading `COLOR=NEUTRAL`, and `BMS_TEXT_COLOR_TOKENS.NEUTRAL` in
       *       `ui/src/theme/tokens.ts` resolves NEUTRAL to `colorTextSecondary` -- which is exactly
       *       the token this prop selects, so the prop IS the bridge's decision rather than a bypass
       *       of it.
       */}
      <ScreenTitle type="secondary">{ACCOUNT_VIEW_HEADINGS.ACCOUNT}</ScreenTitle>
      {/*
       * WHY : ⚠️ Purpose: the one sentence an operator who cannot see the overlay hears while the read is
       *       outstanding. Every other sign this screen gives that it is working is visual -- the
       *       spinner over the record blocks -- so the screen went silent for the length of the request
       *       and then spoke only its answer.
       * WHY : ⚠️ Assumptions: the region is mounted UNCONDITIONALLY and empty while idle, which
       *       `ui/src/layout/fieldHelp.tsx` records as load-bearing rather than defensive: a live region
       *       has to be in the accessibility tree before its text changes for the change to be
       *       announced, so one mounted only while busy would announce nothing on the first read.
       * WHY : Assumptions: it is driven by the read flag alone, because the read is the only request
       *       this screen makes -- the other admitted key navigates.
       */}
      {busyAnnouncement(loading ? REQUEST_IN_PROGRESS : undefined)}
      {/*
       * Alternatives Considered: rendering the whole screen as a form. Of the 37 named
       * `DFHMDF` definitions in this mapset exactly ONE is an input -- `ACCTSID`, the only field
       * carrying `UNPROT` -- and the other 36 are protected, so a form would offer an
       * editability this transaction does not have, and the account-UPDATE screen is where that
       * affordance belongs. The form element here wraps the single filter alone, purely so the
       * field's refusal can be carried by the design system's own field-error treatment.
       */}
      <Form layout="vertical">
        <Form.Item
          label={ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.ACCOUNT_NUMBER}
          htmlFor={ACCOUNT_ID_FIELD_ID}
          {...refusalProps}
        >
          {/*
           * Assumptions: `maxLength` is eleven because three independent declarations in the
           * reference say so -- the receiving field `ACCTSIDI PIC 99999999999` at
           * `app/cpy-bms/COACTVW.CPY` L60, the map field's `LENGTH=11` with
           * `PICIN='99999999999'` and `VALIDN=(MUSTFILL)` at `app/bms/COACTVW.bms` L84-L90, and
           * the carried selection `CDEMO-ACCT-ID PIC 9(11)` at `app/cpy/COCOM01Y.cpy` L38.
           * Assumptions: `autoFocus` is on this control and on no other, because `ACCTSID`
           * carries the mapset's single `IC` attribute and because
           * `app/cbl/COACTVWC.cbl` L546-L552 moves `-1` into `ACCTSIDL` in EVERY branch of its
           * cursor `EVALUATE` -- the cursor is on this field whatever happened on the previous
           * turn, so focusing it is transcription rather than a choice.
           * Alternatives Considered: the design system's numeric input with `controls={false}`
           * and `stringMode`. Rejected because the numeric control cannot hold this field's
           * blank marker: `app/cbl/COACTVWC.cbl` L563 writes a literal `'*'` into this very
           * field and L628 reads it back, and a numeric control would discard it. A text control
           * with digit filtering keeps both halves of that round trip and needs no `stringMode`
           * to stay clear of an IEEE-754 double, because it never holds a number at all.
           */}
          {/*
           * WHY : Refactoring Rationale: the ARIA members come from `ui/src/layout/fieldHelp.tsx`,
           *       where this control previously set a bare `aria-invalid` of its own. The design
           *       system contributes neither member for a control associated by `htmlFor` alone -- its
           *       form item wires them only for a NAMED field, and this screen holds the value itself
           *       -- so the state was conveyed to a sighted operator by colour and to an assistive
           *       technology not at all. The bare attribute closed half of that: it announced THAT the
           *       value was refused and never WHICH refusal, so a service-supplied sentence printed
           *       under the control reached the screen and not the operator who most needed it.
           * WHY : Assumptions: the three members are stated separately because this control is the one
           *       place in the tree where they genuinely differ. A LOCAL refusal is coloured and
           *       carries no help text -- the band already holds the sentence and printing it twice
           *       would have one refusal read in two places -- so `invalid` is true while `hasError`
           *       is false and no dangling `aria-describedby` is emitted. A refusal the SERVICE
           *       addressed to this field does print, so both are true and the description resolves.
           * WHY : Assumptions: `hasHint` is false because this control has no hint element; the mapset
           *       paints no width hint on this screen, unlike the sign-on mapset which paints one.
           */}
          <Input
            id={ACCOUNT_ID_FIELD_ID}
            autoFocus
            {...fieldAriaProps(ACCOUNT_ID_FIELD_ID, {
              invalid: refusal !== null,
              hasError: refusal?.source === 'response',
              hasHint: false,
            })}
            {...blankMarkerProps}
            inputMode="numeric"
            maxLength={ACCOUNT_ID_DECLARED_WIDTH}
            value={entry}
            onChange={
              /**
               * Records the proposed filter value and retires whatever read is outstanding.
               *
               * Assumptions: the record on display is cleared on every keystroke, because the moment
               * the filter stops naming the account beneath it the pairing is no longer true of
               * anything. The reference cannot show that pairing at all -- its task holds the terminal
               * for the whole turn, so the field and the record it sent are always one turn's worth of
               * the same state -- so clearing is what preserves that property rather than adding a
               * behaviour.
               * @param {ChangeEvent<HTMLInputElement>} event - Change event from the control, whose
               *   target value is the text the operator typed or pasted.
               * @returns {void} Completion is represented by this screen's own state.
               */
              (event: ChangeEvent<HTMLInputElement>): void => {
                invalidateInFlightRead();
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
       * WHY : ⚠️ Refactoring Rationale: all FOUR members of the abend group are rendered, and two of them
       *       were withheld before on the grounds that a code and a program name are internal
       *       identifiers the redaction register keeps out of the browser. That reading of the register
       *       was wrong on its own terms: `REDACTED_DIAGNOSTICS` in `ui/src/messages/messages.ts`
       *       withholds CICS response and reason codes and machine-level status values, replacing each
       *       with `UNEXPECTED ABEND OCCURRED.`, and it holds no entry for `ABEND-CODE` or
       *       `ABEND-CULPRIT`. The account-UPDATE screen renders both, from the same catalog table, so
       *       withholding them here also made two screens answer differently for one shared contract --
       *       and an operator quoting an abend to support had three of the four values the reference
       *       shows.
       * WHY : Assumptions: the four members are laid out exactly as the sibling screen lays them out --
       *       the message as the title, the reason beneath it and the code and culprit as a bordered
       *       two-row record view -- and the two row labels are the baseline's OWN data names, read out
       *       of `ABEND_DATA_FIELDS` rather than written as prose, so the surface names the fields an
       *       operator would find at `app/cpy/CSMSG02Y.cpy` L45-L53.
       * WHY : Assumptions: the abend surface still REPLACES the record blocks rather than sitting beside
       *       them, because the reference replaces the whole screen: its `WHEN OTHER` arm at
       *       `app/cbl/COACTVWC.cbl` L375-L382 performs `SEND-PLAIN-TEXT` instead of sending the map.
       */}
      {abend === null ? null : (
        <Result
          status="error"
          title={abendHeading(abend)}
          subTitle={abend.abendReason}
          extra={
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label={ABEND_LABELS.abendCode}>
                {abend.abendCode}
              </Descriptions.Item>
              <Descriptions.Item label={ABEND_LABELS.abendCulprit}>
                {abend.abendCulprit}
              </Descriptions.Item>
            </Descriptions>
          }
        />
      )}
      {/*
       * Assumptions: the spinner is ADDITIVE and is not a fidelity claim. A pseudo-conversational
       * task holds the terminal for the whole turn, so the 3270 screen had nothing to show
       * during a read and needs nothing carried across; a browser leaves the previous paint on
       * screen, so without this an operator cannot tell a slow read from a screen that ignored
       * the key.
       */}
      {loading ? <Spin size="large" /> : null}
      {abend !== null || loading || view === null ? null : (
        <>
          {/*
           * Trade-offs: the two-column record view stands in for the mapset's absolute row and
           * column positions, which is documented gap G1. Reading order, grouping and tab order
           * are preserved because the rows are emitted in `DFHMDF` declaration order and filled
           * left to right. ⚠️ Row-for-row alignment is NOT preserved, on either block, and this
           * note used to claim it was on the customer block -- naming `ACSADL1`/`ACSSTTE`,
           * `ACSADL2`/`ACSZIPC` and `ACSCITY`/`ACSCTRY` as the terminal's rows 16, 17 and 18. That
           * claim is withdrawn where it is made, at {@link customerBlockRows}, and repeating it here
           * left the file asserting both halves of a contradiction: the two address lines are now
           * ONE item, so the fill does not pair `ACSADL2` with `ACSZIPC`, and the grid collapses to
           * a single column below the design system's medium breakpoint, where there are no pairs
           * at all. On the account block the hole is structural too -- `ACRCYCR` occupies the right
           * half of row 9 with nothing in the left half, so a two-up grid necessarily closes it.
           * Pixel-for-character positioning is preserved nowhere, which a browser cannot offer
           * responsively or accessibly.
           */}
          <Descriptions
            bordered
            column={RECORD_VIEW_COLUMNS}
            items={toDescriptionItems(accountBlockRows(view.account), monetaryStyle)}
            styles={{ label: RECORD_LABEL_STYLE }}
          />
          {/*
           * WHY : ⚠️ Refactoring Rationale: the customer block takes its VALUES from the response's own
           *       nested member, and both blocks used to be filled together whenever a view existed. The
           *       prose here already claimed each block was tested separately while the code read both
           *       halves unconditionally -- and the claim could not be exercised in any case, because the
           *       service answered HTTP 404 whenever either half was absent, so the partial state the
           *       reference paints was unreachable end to end. The contract now publishes the customer
           *       half as nullable, the projection below is what renders it, and the sentence naming the
           *       miss arrives on the error band through `returnMessage`.
           * WHY : ⚠️ Refactoring Rationale: the heading and the eighteen labels are painted on the partial
           *       arm too, and this block was SUPPRESSED entirely on it for one turn -- on a note claiming
           *       the map's customer region is "suppressed as a whole" by the guard at L493. That claim
           *       does not survive reading the mapset. `app/bms/COACTVW.bms` holds 100 `DFHMDF`
           *       definitions of which 63 carry no name and only an `INITIAL=` literal, 35 of those 63
           *       sitting at row 11 or below -- the `Customer Details` heading at `POS=(11,32)` among
           *       them -- and a literal definition is part of the map, so it is transmitted on every
           *       send. What L493 guards is the eighteen NAMED value fields in rows 11 to 20 and nothing
           *       else. The terminal therefore shows the heading and every label with its value blank,
           *       and suppressing them here made the screen reflow on an arm where the reference's screen
           *       does not. Reproducing the blank region is parity; suppressing it was a divergence, and
           *       one no entry in `docs/architecture/cobol-to-service-traceability.md` registered.
           * WHY : Assumptions: the ACCOUNT block above needs no such treatment, and that is the contract
           *       rather than an oversight. `account-api.yaml` publishes the account half as required on
           *       this operation because the reference paints NEITHER region's values when no account row
           *       is located -- the disjunction at L471 and L472 is false as well -- so a 200 response
           *       always carries it and the operation answers 404 otherwise.
           */}
          {/*
           * WHY : ⚠️ Assumptions: the rank sits one below the screen caption above, so the customer
           *       block reads as subordinate to it and the heading order skips nothing -- and it is now
           *       READ from `SECTION_HEADING_LEVEL` rather than written as a literal here. The literal
           *       was 4, which was one below this screen's caption only while that caption was a
           *       literal 3; the caption is now ranked by `ui/src/layout/ScreenTitle.tsx`, so a literal
           *       here would have silently become a PEER of it. The mapset states the same subordination
           *       positionally -- `Customer Details` is painted at row 11, inside the body, where
           *       `View Account` is painted at row 4 above the first field.
           */}
          <Typography.Title level={SECTION_HEADING_LEVEL} type="secondary">
            {ACCOUNT_VIEW_HEADINGS.CUSTOMER}
          </Typography.Title>
          <Descriptions
            bordered
            column={RECORD_VIEW_COLUMNS}
            items={toDescriptionItems(
              customerBlockRows(view.customer ?? UNPOPULATED_CUSTOMER),
              monetaryStyle,
            )}
            styles={{ label: RECORD_LABEL_STYLE }}
          />
        </>
      )}
      {/*
       * WHY : ⚠️ Refactoring Rationale: the row-22 INFORMATION band is no longer rendered here, and it
       *       closed this body for two revisions. The reasoning that put it here is preserved on the
       *       delegation above together with the measurement that withdrew it -- the band's rect top was
       *       1270.39 in an 860-pixel viewport, so this screen's one acknowledgement sentence was painted
       *       410 pixels below the fold while the frame's own row-22 zone stood reserved and empty. The
       *       `neutral` severity that a second revision had wrongly rendered as `info` travels with the
       *       delegation, so the measured `COLOR=NEUTRAL` is still what this line resolves to.
       * WHY : Assumptions: nothing about the band's identity changes for a reader or a suite. The frame
       *       renders `MessageBand` with `channel="information"`, which carries
       *       `INFORMATION_BAND_TEST_ID` exactly as this element did, and it renders that zone if and
       *       only if a screen delegates the slot -- so publishing above and rendering there leaves
       *       exactly one row-22 band on the document rather than two, and `closest('main')` on it is now
       *       `null` because the frame paints it outside the scrolling content region.
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
