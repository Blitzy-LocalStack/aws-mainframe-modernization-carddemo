/**
 * @file The transaction add screen, migrated from `app/cbl/COTRN02C.cbl` and its mapset
 * `app/bms/COTRN02.bms` (61 `DFHMDF` fields, of which 21 are named), reached at `/transactions/new`.
 *
 * Purpose
 * -------
 * Capture one new transaction against an account or a card and write it behind an explicit
 * confirmation. It replaces CICS transaction CT02, and it publishes the field labels, the field
 * widths, the format hints and the legend parts this screen paints, because `ui/src/messages/messages.ts`
 * deliberately excludes BMS static text and assigns a screen's own mapset literals to the screen.
 *
 * Validation order is observable
 * ------------------------------
 * Assumptions: the reference program runs five sequential `EVALUATE TRUE` blocks, two date-utility
 * calls and one trailing `IF`, and every one of those blocks is first-match-wins with a single message
 * and a single cursor position. The order is therefore part of the contract rather than an
 * implementation detail, which is why this screen runs its own short-circuiting chain instead of
 * declaring Ant Design `Form` rules -- see {@link keyFieldFailure} and {@link dataFieldFailure}.
 *
 * The whole chain runs before the operator is asked
 * -------------------------------------------------
 * ⚠️ Refactoring Rationale: every step of `VALIDATE-INPUT-DATA-FIELDS` runs in the browser, including
 * the two that were once held to be unreachable from one. The section this replaces recorded the
 * amount's edit-mask re-render (`app/cbl/COTRN02C.cbl` L383-L386) and each date's `CSUTLDTC` evaluation
 * (L389-L427) as arriving on an UNCONFIRMED wire turn, because "two of the reference's steps cannot run
 * in a browser". They can: the re-render is {@link canonicaliseKeyedAmount} and the calendar verdicts
 * are {@link namesARealCalendarDate}, both pure over the keyed text, both inside
 * {@link dataFieldFailure}. So no request is needed to reach a verdict, and consequently no request is
 * sent before the confirmation is answered -- which is the reference's own shape, since its blank and
 * invalid confirmation arms at L176-L187 re-display the screen without touching a file.
 *
 * ⚠️ Assumptions: the service remains the authority on the two verdicts a browser genuinely cannot
 * reach -- whether the key pair resolves, and whether the identifier is free -- and it reaches them on
 * the CONFIRMING turn, which is the only turn this screen sends. `DateEditValidator` still runs there
 * too, as the second reader of the same rules; the browser's copy exists to refuse an unreal date before
 * the operator is asked, not to replace it.
 *
 * Money
 * -----
 * Assumptions: the amount is a string on every path in this file. Transformation rule T3 forbids
 * `float` and `double` in the money path, and JavaScript has one numeric type which is an IEEE-754
 * double, so `Number`, `parseFloat` and arithmetic on the amount are all absent by construction.
 */

/*
 * WHY : ⚠️ Refactoring Rationale: the confirmation surface is a CONTROLLED `Popconfirm` rather than an
 *       uncontrolled one, and that single change is what closes the write path this screen had. An
 *       uncontrolled overlay opens on its trigger's click, so the trigger could raise it before anything
 *       had been validated and both of its answers submitted a turn of their own -- including the
 *       declining answer, which a runtime sweep measured issuing `POST /api/v1/transactions` carrying
 *       `"confirmation":"N"` and then reporting the add as FAILED to an operator who had prevented it.
 *       Controlling `open` moves the decision to raise the surface into {@link requestSubmit}, which is
 *       the one gate every submitting surface passes through, and lets the declining answer be a purely
 *       local dismissal with no wire turn at all -- which is what `app/cbl/COTRN02C.cbl` L176-L181 does.
 * WHY : Alternatives Considered: an antd `Modal`, which is viewport-anchored and so cannot occlude the
 *       control it asks about. Rejected on evidence rather than on taste: the confirmation contract this
 *       screen is held to is expressed in the DOM -- `ui/src/screens/transactionAdd/transactionAddTurns.test.tsx`
 *       identifies the open surface by `.ant-popover` and reaches it through an in-content control whose
 *       accessible name carries {@link TRANSACTION_ADD_TITLE} -- so a `Modal` would satisfy the report
 *       and break the contract. The two properties a `Modal` was wanted for are obtained on the popover
 *       instead: `placement` keeps it clear of the field it names, and both of its one-character
 *       controls are sized to the AA floor rather than being left at the library's `small` default.
 * WHY : Alternatives Considered: attaching the overlay to the legend's own Enter control. Rejected
 *       because that control is rendered by `ui/src/layout/PfKeyBar.tsx` from the bindings this screen
 *       delegates, so this screen has no element to wrap.
 */
import { Button, Col, Divider, Flex, Form, Input, Popconfirm, Row, Typography, theme } from 'antd';
// Assumptions: the grid's gutter TYPE is imported from the design system rather than restated here,
// because it is a union the component owns -- a number, a per-screen-name record, or a pair of
// either -- and a local restatement would silently stop matching the component the day the union
// gained a member. Nothing is imported at runtime by this line.
import type { InputRef, RowProps } from 'antd';
import { useEffect, useId, useRef, useState } from 'react';
import type { ChangeEvent, CSSProperties, ReactElement } from 'react';
// Assumptions: routing comes from `react-router`, never from `react-router-dom`, which is the import
// most React code reaches for and is deliberately ABSENT from `ui/package.json`. No 8.x of the
// companion package exists: it is a thin shim that depends on `react-router@7`, so importing it would
// silently pin routing a major version behind the one this tree declares. `ui/eslint.config.js` makes
// the choice enforceable rather than conventional by listing it under `no-restricted-imports`, so the
// build fails rather than resolving two routers.
import { useNavigate } from 'react-router';

// Assumptions: the two confirmation characters are imported from the API client rather than declared
// here, because they are a WIRE value before they are a control label -- every mutating screen in this
// tree sends the same pair, and `ui/src/api/client.ts` owns them for that reason. Declaring a local
// `'Y'` beside the overlay is how the two copies drift: one screen's label and another screen's request
// body would then be able to disagree with nothing comparing them.
import { CONFIRMATION_ANSWERS, isApiRequestError } from '../../api/client';
import { addTransaction, copyLastTransaction } from '../../api/transactions';
import type {
  CopiedTransactionData,
  CopyLastTransactionRequest,
  TransactionAddOutcome,
  TransactionCreateRequest,
} from '../../api/transactions';
import type { ApiError, FieldValidationState } from '../../api/types';
// Assumptions: the money edit mask and its reverse are IMPORTED as a pair, from the module that measured
// this program's own `+99999999.99` picture -- `MONEY_PICTURES.transactionAmount` cites
// `app/cbl/COTRN02C.cbl` L53 and L59 among its five sources. This screen already carried a local mask
// renderer for the service's answer; what it lacked was the REVERSE, which is what lets an operator's
// own spelling of an amount be read before it is re-rendered. Adding a second local reverse would have
// put a money parser in a screen, where the format module owns one that five reference programs share.
import { MONEY_PICTURES, applyMoneyEditMask, stripMoneyEditMask } from '../../format/money';
import { useServerInstant } from '../../hooks/useServerInstant';
import { useShellSlot } from '../../layout/AppShell';
// Assumptions: `busyAnnouncement` is taken from the shared helper rather than composed here, because
//   that helper is the one place deciding the region's shape -- always mounted, `role="status"`, empty
//   when idle -- and a locally composed span would drift from every other screen's.
import {
  busyAnnouncement,
  fieldAriaProps,
  fieldErrorHelp,
  fieldHintId,
} from '../../layout/fieldHelp';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { UNIFORM_PF_KEY_LABELS } from '../../layout/PfKeyBar';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap, PfKeyRejection } from '../../layout/usePfKeys';
// Assumptions: `PERSISTENT_FAILURE_REPORT_IT` is an AUTHORED sentence and is imported alongside the
// baseline ones, because the failure it names has no baseline site to be verbatim from. The reference
// has no arm for "the service answered with a body that violates its own contract" -- a 3270 region
// either replies or the terminal times out -- so borrowing one of its sentences for that case would put
// the reference's words on a failure the reference cannot have.
import {
  INVALID_KEY_PRESSED,
  MESSAGE_TEMPLATES,
  PERSISTENT_FAILURE_REPORT_IT,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  formatMessageTemplate,
  padToDeclaredWidth,
} from '../../messages/messages';
import { MAIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import {
  BMS_TEXT_COLOR_TOKENS,
  FIELD_ERROR_TOKENS,
  SPACING_TOKENS,
  TARGET_SIZE_AA_MINIMUM,
  TYPOGRAPHY_TOKENS,
} from '../../theme/tokens';
import { ScreenTitle } from '../../layout/ScreenTitle';

/*
 * WHY : Assumptions: every sentence this screen renders is imported rather than retyped, because
 *       `ui/src/messages/messages.ts` is the single owner of baseline message text and carries each
 *       string's declared width and originating line beside it. Retyping one would put a second copy in
 *       the tree with nothing to compare it against, and the failure mode is a single character -- a
 *       dropped ellipsis dot or a doubled space -- which reads as correct in review and registers as a
 *       golden-master parity failure. Twenty-seven of the sentences are this program's own; five are
 *       shared with other programs and are filed under the shared group for that reason.
 */
const ADD_MESSAGES = PROGRAM_MESSAGES.COTRN02C;

/** CICS transaction identifier this screen replaces, from `WS-TRANID` at `app/cbl/COTRN02C.cbl` L37. */
export const TRANSACTION_ADD_TRANSACTION_ID = 'CT02';

/** Source program name, from `WS-PGMNAME` at `app/cbl/COTRN02C.cbl` L36. */
export const TRANSACTION_ADD_PROGRAM_NAME = 'COTRN02C';

/**
 * Mapset this screen stands in, which selects the message band's display width.
 *
 * Assumptions: `MESSAGE_BAND_BY_MAPSET` records `COTRN02` at 78 characters, matching
 * `ERRMSG ... LENGTH=78` at `app/bms/COTRN02.bms` L293-L296. The band is sized from the mapset rather
 * than from the route because the route is a target shape this migration chose while the mapset is the
 * reference identity the width is a property of.
 */
export const TRANSACTION_ADD_MAPSET = 'COTRN02';

/**
 * Screen title, verbatim from the row-4 heading at `app/bms/COTRN02.bms` L75-L79.
 *
 * Assumptions: the field is `ATTRB=(ASKIP,BRT)`, and brightness resolves to font weight rather than to
 * colour through `TYPOGRAPHY_TOKENS.brightEmphasis` -- all 37 bright fields in the base mapsets already
 * carry a colour, so expressing brightness as colour would collide on every one of them.
 */
export const TRANSACTION_ADD_TITLE = 'Add Transaction';

/**
 * The thirteen field labels and the confirmation prompt, verbatim from `app/bms/COTRN02.bms`.
 *
 * Assumptions: each value is the mapset's `INITIAL=` literal character for character, colon included,
 * because transformation rule T8 carries user-visible text across unchanged and the screen tests
 * assert each label byte-for-byte. The hash in `Card #:` is a literal character standing for the word
 * "number" and is not a substitution marker.
 */
export const TRANSACTION_ADD_FIELD_LABELS = {
  /** `app/bms/COTRN02.bms` L80-L84. */
  accountId: 'Enter Acct #:',
  /** `app/bms/COTRN02.bms` L99-L103. */
  cardNumber: 'Card #:',
  /** `app/bms/COTRN02.bms` L117-L121. */
  typeCode: 'Type CD:',
  /** `app/bms/COTRN02.bms` L130-L134. */
  categoryCode: 'Category CD:',
  /** `app/bms/COTRN02.bms` L143-L147. */
  source: 'Source:',
  /** `app/bms/COTRN02.bms` L156-L160. */
  description: 'Description:',
  /** `app/bms/COTRN02.bms` L169-L173. */
  amount: 'Amount:',
  /** `app/bms/COTRN02.bms` L182-L186. */
  originDate: 'Orig Date:',
  /** `app/bms/COTRN02.bms` L195-L199. */
  processDate: 'Proc Date:',
  /** `app/bms/COTRN02.bms` L223-L227. */
  merchantId: 'Merchant ID:',
  /** `app/bms/COTRN02.bms` L236-L240. */
  merchantName: 'Merchant Name:',
  /** `app/bms/COTRN02.bms` L249-L253. */
  merchantCity: 'Merchant City:',
  /** `app/bms/COTRN02.bms` L262-L266. */
  merchantZip: 'Merchant Zip:',
  /** `app/bms/COTRN02.bms` L275-L280, declared `LENGTH=55`; the space before the colon is in the source. */
  confirmation: 'You are about to add this transaction. Please confirm :',
} as const;

/*
 * WHY : Assumptions: every width below is the SYMBOLIC MAP's declaration in
 *       `app/cpy-bms/COTRN02.CPY`, never the 350-byte record's in `app/cpy/CVTRA05Y.cpy`, and the two
 *       disagree in six places. The map is narrower at all six: description 60 against
 *       `TRAN-DESC PIC X(100)` (L9), merchant name 30 against `PIC X(50)` (L12), merchant city 25
 *       against `PIC X(50)` (L13), and both dates 10 against the 26-character timestamps
 *       `TRAN-ORIG-TS` and `TRAN-PROC-TS` (L16-L17) -- the map captures a date where the record stores
 *       an instant. The amount is the sixth and the subtlest: the field is 12 characters and the edit
 *       mask `WS-TRAN-AMT-E PIC +99999999.99` (`app/cbl/COTRN02C.cbl` L59) spends them on a sign, EIGHT
 *       integer digits, a point and two decimals, while the record declares
 *       `TRAN-AMT PIC S9(09)V99` (L10) with NINE integer digits. The map width is what governs here
 *       because it is the constraint the operator actually meets: the terminal refused the 61st
 *       description character, so a browser that accepted 100 would let an operator key a value the
 *       screen it replaces could not hold, and the service would then either refuse it or store text no
 *       3270 turn could have produced. The consequence is recorded rather than silently resolved: the
 *       ninth integer digit of the record is unreachable from this screen in the baseline too.
 */

/**
 * Declared width of each editable field, from the symbolic map `COTRN2AI` in `app/cpy-bms/COTRN02.CPY`.
 *
 * Assumptions: these are the `maxLength` values, so a field cannot accept more characters than the
 * 3270 field held. ADR-006 states that an input's maximum length is its copybook picture width.
 */
export const TRANSACTION_ADD_FIELD_WIDTHS = {
  /** `ACTIDINI PIC X(11)`, `app/cpy-bms/COTRN02.CPY` L60. */
  accountId: 11,
  /** `CARDNINI PIC X(16)`, `app/cpy-bms/COTRN02.CPY` L66. */
  cardNumber: 16,
  /** `TTYPCDI PIC X(2)`, `app/cpy-bms/COTRN02.CPY` L72. */
  typeCode: 2,
  /** `TCATCDI PIC X(4)`, `app/cpy-bms/COTRN02.CPY` L78. */
  categoryCode: 4,
  /** `TRNSRCI PIC X(10)`, `app/cpy-bms/COTRN02.CPY` L84. */
  source: 10,
  /** `TDESCI PIC X(60)`, `app/cpy-bms/COTRN02.CPY` L90. */
  description: 60,
  /** `TRNAMTI PIC X(12)`, `app/cpy-bms/COTRN02.CPY` L96. */
  amount: 12,
  /** `TORIGDTI PIC X(10)`, `app/cpy-bms/COTRN02.CPY` L102. */
  originDate: 10,
  /** `TPROCDTI PIC X(10)`, `app/cpy-bms/COTRN02.CPY` L108. */
  processDate: 10,
  /** `MIDI PIC X(9)`, `app/cpy-bms/COTRN02.CPY` L114. */
  merchantId: 9,
  /** `MNAMEI PIC X(30)`, `app/cpy-bms/COTRN02.CPY` L120. */
  merchantName: 30,
  /** `MCITYI PIC X(25)`, `app/cpy-bms/COTRN02.CPY` L126. */
  merchantCity: 25,
  /** `MZIPI PIC X(10)`, `app/cpy-bms/COTRN02.CPY` L132. */
  merchantZip: 10,
  /** `CONFIRMI PIC X(1)`, `app/cpy-bms/COTRN02.CPY` L138. */
  confirmation: 1,
} as const;

/**
 * The three protected format hints the mapset paints on row 15, verbatim.
 *
 * Assumptions: all three are `COLOR=BLUE` `ATTRB=(ASKIP,NORM)` fields sitting directly beneath the
 * inputs they describe (`app/bms/COTRN02.bms` L208-L222), so they are guidance and never editable
 * values. The parentheses are part of each literal.
 */
export const TRANSACTION_ADD_FORMAT_HINTS = {
  /** `app/bms/COTRN02.bms` L208-L212, `LENGTH=14`. */
  amount: '(-99999999.99)',
  /** `app/bms/COTRN02.bms` L213-L217, `LENGTH=12`. */
  originDate: '(YYYY-MM-DD)',
  /** `app/bms/COTRN02.bms` L218-L222, `LENGTH=12`. */
  processDate: '(YYYY-MM-DD)',
} as const;

/**
 * The word the mapset paints between the two key fields, verbatim from `app/bms/COTRN02.bms` L94-L98.
 *
 * Assumptions: it is a `COLOR=NEUTRAL` `LENGTH=4` field, and it carries real meaning rather than
 * decoration -- it states that the account identifier and the card number are alternatives, which is
 * exactly what the ordered `EVALUATE TRUE` at `app/cbl/COTRN02C.cbl` L195-L230 implements.
 */
export const TRANSACTION_ADD_ALTERNATIVE_KEY_LABEL = '(or)';

/**
 * The domain hint painted beside the confirmation field, verbatim from `app/bms/COTRN02.bms` L288-L292.
 */
export const TRANSACTION_ADD_CONFIRM_DOMAIN_HINT = '(Y/N)';

/*
 * WHY : ⚠️ Refactoring Rationale: the field rows are a `Row`/`Col` grid and were `Flex` rows whose
 *       children each carried `flex: 1 1 0`. A zero flex-basis is why the arity never collapsed: every
 *       column always "fits", so `wrap` had nothing to act on, and a responsive sweep measured the
 *       arities `[2,3,1,3,2,2,1]` holding at EVERY width -- at 375 the three-up rows gave 114-pixel
 *       columns, which is about eight monospace characters for the amount and both dates, fields whose
 *       declared widths are twelve and ten characters. The values were unreadable while the layout
 *       reported no overflow.
 * WHY : Assumptions: `Col` breakpoint props are the design system's own expression of this and are used
 *       instead of a media query or a minimum width on the flex children. AAP section 0.3.2 requires
 *       layout to go through the system's primitives, and `ui/src/screens/accountUpdate/index.tsx`
 *       already lays its two field grids out this way -- so the collapse behaves identically on both
 *       screens rather than being invented twice.
 */

/**
 * Resolves the two-axis gutter this screen's field grid is laid out with.
 *
 * ⚠️ Purpose: keep the HORIZONTAL gutter -- and therefore the negative inline margin the design system
 * derives from it -- off the widths at which every column is full-width. `antd`'s `Row` implements a
 * gutter by giving itself a negative inline margin of half the gutter and each `Col` a matching padding,
 * so a `Row` laid out in a container as wide as the viewport hangs half a gutter past each edge. A
 * responsive review measured exactly that escape on `/account/update` -- `scrollWidth` 385 against an
 * `innerWidth` of 375 -- and one small pan then clipped the first character off every label. Zero is the
 * right value below the medium breakpoint rather than a smaller number, because every column there is
 * `xs={24}`, a full row of its own, so there is no pair of side-by-side columns for a horizontal gutter
 * to separate and its only remaining effect is the escape.
 *
 * Assumptions: the VERTICAL gutter keeps the same step at every width. Stacked single-column fields need
 * row separation more at a phone width than at a desktop one, and a block-axis negative margin cannot be
 * exposed by a horizontal scroll.
 *
 * Assumptions: only the three narrow screen names are stated. The design system resolves a responsive
 * gutter by walking its screen names widest-first and taking the first that both matches and is present,
 * so the value given at `md` also governs `lg`, `xl`, `xxl` and `xxxl`; naming those four as well would
 * be four more places for one decision to be edited.
 *
 * Assumptions: the caller passes the RESOLVED spacing step as a number rather than the token's
 * `var(--...)` reference, because the gutter is a component prop the design system divides by two itself.
 * Reading the resolved member is safe for this one value precisely because it never reaches a style
 * attribute, so nothing about today's spacing scale is baked into an element.
 * @param {number} sectionGap - The spacing step from the token bridge's medium section gap.
 * @returns {RowProps['gutter']} The horizontal gutter per screen name, paired with the vertical gutter.
 */
export function transactionAddGridGutter(sectionGap: number): RowProps['gutter'] {
  return [{ xs: 0, sm: 0, md: sectionGap }, sectionGap];
}

/**
 * Column spans for a row of three fields: one per row on a phone, two on a tablet, three from medium up.
 *
 * ⚠️ Assumptions: the intermediate two-up step exists for the amount-and-dates row specifically. Those
 * three fields are fixed-pitch and twelve, ten and ten characters wide, so a three-up arrangement inside
 * a 576-pixel viewport gives each about a third of it and cuts the value; two-up gives each half, which
 * holds all three declared widths. `sm` is 576 and `md` is 768 in the design system's own scale.
 */
const THREE_UP_SPANS = { xs: 24, sm: 12, md: 8 } as const;

/** Column spans for a row of two fields: one per row on a phone, two from medium up. */
const TWO_UP_SPANS = { xs: 24, md: 12 } as const;

/**
 * Column spans for the key row's two entry fields, which share their row with the `(or)` label.
 *
 * Assumptions: eleven of the grid's twenty-four columns each, leaving two for the label between them.
 * The label is three characters, so it needs the narrowest usable share and the entries need the rest.
 */
const KEY_FIELD_SPANS = { xs: 24, md: 11 } as const;

/** Column span for the `(or)` label between the two key entry fields. */
const ALTERNATIVE_LABEL_SPANS = { xs: 24, md: 2 } as const;

/** Column span for a field that occupies its whole row at every width. */
const FULL_WIDTH_SPANS = { xs: 24 } as const;

/*
 * WHY : ⚠️ Refactoring Rationale: the row-8 rule is a `Divider` and was seventy literal hyphens in a
 *       fixed-pitch `Typography.Text`. Two independent captures measured what the literal produced: a
 *       rule spanning x54 to x640 and stopping about 586 pixels short of the content edge at 1280, while
 *       `/account/update`'s rule runs the full width -- and at 375 and 576 the same seventy characters
 *       wrapped onto two ragged lines. A rule that stops two-thirds of the way across does not read as a
 *       section boundary, and one that wraps reads as content.
 * WHY : ⚠️ Assumptions: the seventy-character width carried no meaning worth preserving. It is
 *       `LENGTH=70` at `app/bms/COTRN02.bms` L111-L116 -- seventy of the terminal's eighty columns --
 *       which is a fact about a fixed 24x80 character grid, and AAP section 0.3.4 gap G1 abandons that
 *       grid deliberately: field grouping, reading order and tab order are preserved, character
 *       positioning is not. So the rule's JOB is preserved (it divides the two key fields from the
 *       transaction fields) and its character count is not.
 * WHY : Assumptions: `Divider` is also what the rest of this tree already uses for a mapset rule --
 *       `ui/src/screens/billPay/index.tsx`, `userUpdate`, `userDelete` and `transactionDetail` each
 *       render one with a `borderColor` from the same token map -- so this removes a one-screen
 *       exception rather than introducing a new idiom.
 * WHY : Assumptions: the two constants that built the literal are DELETED rather than left exported. No
 *       file read them but this one, and an exported constant with no consumer is the kind of dead
 *       provenance a later reader mistakes for a contract; the source line is cited here instead.
 * WHY : Assumptions: no `aria-hidden` is needed any more. `Divider` renders an element the accessibility
 *       tree treats as a separator, so it conveys the boundary without a screen reader announcing
 *       seventy hyphens -- which is exactly what the literal had to be hidden to avoid.
 */

/*
 * WHY : Assumptions: only PF4's wording is imported. `UNIFORM_PF_KEY_LABELS` holds the three legends
 *       whose text is identical across every mapset that paints them, and `F4=Clear` is one of them, so
 *       importing it keeps this screen from becoming a second place that spelling could drift. ENTER,
 *       PF3 and PF5 are declared here because their wording is per-screen: PF3 reads `Back` here and
 *       `Exit` on nine other mapsets, and PF5 reads `Copy Last Tran.` here where other screens paint
 *       `Save` or `Delete`.
 * WHY : Assumptions: PF5 is bound to copying the last transaction and NOT to saving, and the legend is
 *       what settles it. AAP section 0.3.3 records a uniform PF5=save reading measured across the
 *       online programs, and this screen contradicts it in two independent places -- the row-24 legend
 *       at `app/bms/COTRN02.bms` L297-L302 paints `F5=Copy Last Tran.`, and the dispatch at
 *       `app/cbl/COTRN02C.cbl` L146-L147 performs `COPY-LAST-TRAN-DATA` for `DFHPF5`. A screen's own
 *       legend and its own dispatch outrank a convention derived from other screens; binding a save to
 *       PF5 here would write a transaction on a key the operator was told copies one.
 */

/**
 * Legend labels for the four keys this screen paints, split by who owns each spelling.
 *
 * Assumptions: the row-24 field is one literal, `ENTER=Continue  F3=Back  F4=Clear  F5=Copy Last Tran.`
 * (`app/bms/COTRN02.bms` L297-L302, declared `LENGTH=53`), and the two spaces between each segment are
 * in the source. Splitting it into four labels is what lets each one sit on the control that performs
 * it, and the parts still join back to the declared 53 characters.
 */
export const TRANSACTION_ADD_KEY_LABELS = {
  ENTER: 'ENTER=Continue',
  PFK03: 'F3=Back',
  PFK04: UNIFORM_PF_KEY_LABELS.PFK04,
  PFK05: 'F5=Copy Last Tran.',
} as const;

/** One editable field of the transaction add screen, named as the service contract names it. */
export type TransactionAddField = keyof typeof TRANSACTION_ADD_FIELD_WIDTHS;

/** The value of every editable field, each held as the text the operator keyed. */
export type TransactionAddValues = Record<TransactionAddField, string>;

/**
 * One field the screen is reporting a refusal against.
 *
 * Assumptions: `state` distinguishes a blank field from an otherwise-refused one because
 * `app/cpy/CSSETATY.cpy` L17-L27 draws that distinction: it moves the error colour in for either, and
 * nests the additional `MOVE '*'` inside the blank test alone.
 */
export interface TransactionAddFieldError {
  /** Field the refusal names. */
  readonly field: TransactionAddField;
  /** Verbatim sentence to render beneath the control. */
  readonly message: string;
  /** Whether the field was blank, which alone earns the asterisk marker. */
  readonly state: FieldValidationState;
}

/**
 * What the service resolved on the last turn it answered, and the token that binds a confirmation to it.
 *
 * Purpose
 * -------
 * `app/cbl/COTRN02C.cbl` L193-L229 resolves whichever key the operator did not supply and writes BOTH
 * fields: the account arm reads the cross-reference at L208 and moves the card number into `CARDNINI` at
 * L209, the card arm reads at L219 and moves the account identifier into `ACTIDINI` at L221. This is that
 * pair, as the service reported it, so the confirmation surface can name the record being committed.
 *
 * ⚠️ Refactoring Rationale: the card number is carried MASKED and this shape held sixteen digits,
 * defended on the ground that a masked suffix cannot distinguish two cards on one account. The premise is
 * true and the conclusion was not available: AAP section 0.4.1.9 masks a primary account number in every
 * response but the administrative card-detail read. The distinction the digits were for is drawn by
 * {@link ResolvedKeys.confirmationToken} instead -- the service compares the token against its own
 * resolution on the confirming turn -- so the browser never holds a primary account number for this
 * screen and the guarantee is stronger rather than weaker, because it no longer depends on the client
 * choosing to submit what it was shown.
 */
interface ResolvedKeys {
  /** The account identifier the service resolved, zero-filled to its eleven declared positions. */
  readonly accountId: string;
  /** The card number the cross-reference resolved, as twelve asterisks and its last four digits. */
  readonly cardNumberMasked: string;
  /**
   * The opaque token the confirming turn replays so the write is bound to this resolution.
   *
   * Assumptions: it is held for the life of the exchange and never rendered, parsed or compared. It
   * seals the resolved card number, so displaying it would display a sealed primary account number and
   * comparing it would be asserting something only the service can decide.
   */
  readonly confirmationToken: string;
}

/**
 * Every field blank, which is the state `INITIALIZE-ALL-FIELDS` leaves the screen in.
 *
 * Assumptions: `app/cbl/COTRN02C.cbl` L762-L779 moves spaces into all thirteen data fields, both key
 * fields and the confirmation, and additionally sets the account field's cursor. That paragraph is
 * reached from two places -- the PF4 arm through `CLEAR-CURRENT-SCREEN` (L754-L757) and the successful
 * write (L725) -- so both of this screen's clears are the same operation and share this one value.
 */
const BLANK_VALUES: TransactionAddValues = {
  accountId: '',
  cardNumber: '',
  typeCode: '',
  categoryCode: '',
  source: '',
  description: '',
  amount: '',
  originDate: '',
  processDate: '',
  merchantId: '',
  merchantName: '',
  merchantCity: '',
  merchantZip: '',
  confirmation: '',
};

/** Matches a run of ASCII digits and nothing else, used for the COBOL numeric class test. */
const DIGITS_ONLY = /^[0-9]+$/u;

/**
 * Matches one character the free-text fields may NOT carry, for stripping as an operator keys.
 *
 * Assumptions: the admitted domain is printable US-ASCII, code points `0x20` to `0x7E` inclusive, and
 * this expression is its complement so that a filter can be written as one replacement. It is the same
 * domain the service publishes as the `^[\x20-\x7E]*$` pattern on `TransactionSource`,
 * `TransactionDescription`, `MerchantName`, `MerchantCity` and `MerchantZip` in
 * `services/transaction-service/src/main/resources/openapi/transaction-api.yaml`, so the control admits
 * exactly what the operation admits.
 *
 * Assumptions: the domain is derived from the migration's own fixed-width codecs rather than chosen.
 * Anything above `0x7E` cannot be encoded US-ASCII, so it is stored and then refused by a later batch or
 * reporting run; anything below `0x20`, and `0x7F`, encodes cleanly and is copied verbatim into the
 * plain-text statement's 80-column bands and the transaction report's 133-column records, where a
 * carriage return or line feed becomes a second record no reader can tell from a real one. The space is
 * inside the domain, which these fields need: the reference's own source value is `POS TERM`.
 */
const NON_PRINTABLE_TEXT = /[^\x20-\x7E]/gu;

/**
 * The fields whose characters an operator authors freely, and which are therefore filtered.
 *
 * Assumptions: these five are the screen's `PIC X` fields with no other composition rule. Every other
 * editable field already has one -- the two keys, the type, category and merchant identifier are numeric,
 * the amount carries the edited picture, the two dates carry the `YYYY-MM-DD` mask and the confirmation
 * is a single letter -- so a filter on those would be a second authority on a value one rule already
 * decides.
 */
const FREE_TEXT_FIELDS: readonly TransactionAddField[] = [
  'source',
  'description',
  'merchantName',
  'merchantCity',
  'merchantZip',
];

/** Matches a monetary amount the service may return: an optional sign, integral digits and two decimals. */
const SERVICE_MONEY = /^-?[0-9]+\.[0-9]{2}$/u;

/** Matches an integral-and-fractional pair whose digits are all zero, so the edit mask emits a plus. */
const ALL_ZERO_DIGITS = /^0+$/u;

/** Integer digits the `+99999999.99` edit mask holds, from `WS-TRAN-AMT-E` at `app/cbl/COTRN02C.cbl` L59. */
const AMOUNT_MASK_INTEGER_DIGITS = 8;

/** Fractional digits the same mask holds. */
const AMOUNT_MASK_FRACTION_DIGITS = 2;

/*
 * WHY : ⚠️ Refactoring Rationale: there is no `CARD_NUMBER_VISIBLE_DIGITS` here any more, and no
 *       client-side masker beside it. Both existed because the capture preview published the resolved
 *       card as sixteen digits and this screen reduced them before rendering -- which is the shape
 *       `ui/src/api/masking.ts` names as the exposure to avoid, because a caller that masks has to hold
 *       the unmasked value first. The service now publishes the masked rendering itself, so the value
 *       that reaches the browser is already reduced and there is nothing left here to reduce.
 */

/**
 * Reports whether a fixed-width field satisfies COBOL's numeric class test.
 *
 * Assumptions: the test is applied to the WHOLE declared field and not to the characters an operator
 * happened to key, which is why the width is a parameter. A 3270 `RECEIVE MAP` moves the transmitted
 * characters in left-justified and pads the remainder of the field with spaces, and a space is not a
 * numeric character, so `IF ACTIDINI OF COTRN2AI IS NOT NUMERIC` at `app/cbl/COTRN02C.cbl` L197 refuses
 * a partly-keyed account identifier exactly as it refuses a letter. Reproducing that means a numeric
 * field is refused unless it is filled to its declared width with digits.
 *
 * Alternatives Considered: testing only the keyed characters -- `/^[0-9]*$/` against the raw value --
 * which is the reflexive reading of "must be numeric" and is what a browser form usually enforces.
 * Rejected because it accepts a value the reference refuses: `123` keyed into the eleven-character
 * account field would pass here and then be sent as an account identifier the service must reject,
 * moving a refusal the operator used to see immediately to the far side of a round trip.
 * @param {string} value - Characters the operator keyed, unpadded.
 * @param {number} declaredWidth - The field's `PICTURE` width from the symbolic map.
 * @returns {boolean} `true` when the field, padded to its declared width, is composed only of digits.
 */
export function isNumericField(value: string, declaredWidth: number): boolean {
  return DIGITS_ONLY.test(padToDeclaredWidth(value, declaredWidth));
}

/**
 * Reports whether a field is blank, which is the reference's `= SPACES OR LOW-VALUES` test.
 *
 * Assumptions: both figurative constants collapse to one browser condition. `LOW-VALUES` is the state
 * `MOVE LOW-VALUES TO COTRN2AO` (`app/cbl/COTRN02C.cbl` L122) leaves an untransmitted field in and
 * `SPACES` is the state a cleared one holds, and neither has a counterpart in a DOM input whose value is
 * simply the empty string or whitespace.
 * @param {string} value - Characters the operator keyed.
 * @returns {boolean} `true` when the field carries no non-blank character.
 */
export function isBlankField(value: string): boolean {
  return value.trim() === '';
}

/**
 * Drops every character a free-text field may not carry, leaving printable US-ASCII.
 *
 * Assumptions: this mirrors what the terminal did PHYSICALLY. `TRNSRC` at `app/bms/COTRN02.bms` L148 and
 * its four siblings are unprotected 3270 fields on a single-byte code page, so a keyboard could not
 * transmit a control character or a supplementary code point into them at all -- an operator pressing a
 * key the field could not hold simply saw nothing appear. A browser input accepts anything, so the
 * equivalent of "nothing appears" is to strip it here.
 *
 * Trade-offs: an inadmissible character is DROPPED rather than reported, which is deliberate and is the
 * same treatment the control already gives an over-long paste: `maxLength` truncates it silently. The
 * alternative -- accepting the character and marking the field -- was rejected because it teaches the
 * operator nothing the terminal would have taught them and defers a refusal they can see immediately to
 * a round trip. The service refuses the same domain regardless, so a value that reaches it another way
 * still draws a field-level refusal; this only makes the control agree with the operation.
 *
 * Assumptions: only the five free-text fields are routed through this. A digit, date or amount field has
 * its own composition rule, and filtering those here would put two authorities on one value.
 * @param {string} edited - Characters the control now holds, as the operator keyed or pasted them.
 * @returns {string} The same characters with every code point outside `0x20` to `0x7E` removed.
 */
export function retainPrintableText(edited: string): string {
  return edited.replace(NON_PRINTABLE_TEXT, '');
}

/**
 * Reports whether the amount has the shape the reference's twelve-character predicate accepts.
 *
 * Assumptions: the four `WHEN` branches at `app/cbl/COTRN02C.cbl` L340-L343 are reference-modified with
 * COBOL's `(offset:length)` form, so `TRNAMTI(2:8)` spans positions two THROUGH NINE rather than two
 * through eight. Read that way the predicate covers all twelve characters with no gap -- position one
 * is the sign, two to nine are the eight integer digits, ten is the point and eleven and twelve are the
 * decimals -- which is exactly the width the `+99999999.99` edit mask spends. The offsets below are
 * therefore the reference's own, converted to zero-based slices, and nothing is tightened or relaxed.
 *
 * Trade-offs: the sign is MANDATORY, so `100.00` is refused where `+100.00` is accepted, and that
 * strictness is the reference's rather than a choice made here -- `TRNAMTI(1:1) NOT EQUAL '-' AND '+'`
 * admits no third character. The hint the mapset paints beneath the field, `(-99999999.99)`, is what
 * tells the operator so.
 * @param {string} value - Characters the operator keyed into the amount field.
 * @returns {boolean} `true` when the padded field is a sign, eight digits, a point and two digits.
 */
export function hasBaselineAmountShape(value: string): boolean {
  const field = padToDeclaredWidth(value, TRANSACTION_ADD_FIELD_WIDTHS.amount);
  const sign = field.slice(0, 1);
  const integerDigits = field.slice(1, 1 + AMOUNT_MASK_INTEGER_DIGITS);
  const point = field.slice(9, 10);
  const fractionDigits = field.slice(10, 10 + AMOUNT_MASK_FRACTION_DIGITS);

  if (sign !== '-' && sign !== '+') {
    return false;
  }
  if (!DIGITS_ONLY.test(integerDigits)) {
    return false;
  }
  if (point !== '.') {
    return false;
  }
  return DIGITS_ONLY.test(fractionDigits);
}

/*
 * WHY : Refactoring Rationale: ONE predicate serves both dates, where the reference carries two
 *       `EVALUATE TRUE` blocks -- `app/cbl/COTRN02C.cbl` L353-L366 for the originating date and
 *       L368-L381 for the processing date. The two blocks are character-identical apart from the field
 *       they read and the sentence they raise, so a second copy of the predicate would be a second
 *       place for one rule to drift while the messages, which genuinely differ, stay separate at the
 *       two call sites. What is preserved is the ORDER: the originating date is checked before the
 *       processing date, because the blocks run in that sequence and each ends the turn.
 */

/**
 * Reports whether a date field has the shape the reference's ten-character predicate accepts.
 *
 * Assumptions: this checks SHAPE and not validity. The reference performs both -- five reference-
 * modified branches for the shape, then a `CALL 'CSUTLDTC'` for the calendar -- and only the first can
 * run in a browser, so `2024-02-31` passes here and is refused by the service's date evaluator, whose
 * verdict arrives as a field error on this same field.
 * @param {string} value - Characters the operator keyed into a date field.
 * @returns {boolean} `true` when the padded field is four digits, a hyphen, two digits, a hyphen and
 *   two digits.
 */
export function hasBaselineIsoDateShape(value: string): boolean {
  const field = padToDeclaredWidth(value, TRANSACTION_ADD_FIELD_WIDTHS.originDate);
  const year = field.slice(0, 4);
  const firstSeparator = field.slice(4, 5);
  const month = field.slice(5, 7);
  const secondSeparator = field.slice(7, 8);
  const day = field.slice(8, 10);

  if (!DIGITS_ONLY.test(year)) {
    return false;
  }
  if (firstSeparator !== '-') {
    return false;
  }
  if (!DIGITS_ONLY.test(month)) {
    return false;
  }
  if (secondSeparator !== '-') {
    return false;
  }
  return DIGITS_ONLY.test(day);
}

/**
 * Re-renders an amount through the reference's `+99999999.99` edit mask.
 *
 * Assumptions: the reference converts the keyed amount and writes the converted value BACK into the
 * screen field -- `COMPUTE WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI)`, `MOVE WS-TRAN-AMT-N TO
 * WS-TRAN-AMT-E`, `MOVE WS-TRAN-AMT-E TO TRNAMTI` at `app/cbl/COTRN02C.cbl` L383-L386 -- so the operator
 * sees the canonical form after a turn rather than what they keyed. Two observable transformations come
 * out of that mask and both are reproduced: the integer part is zero-filled to eight digits, and the
 * sign is a PLUS for any value that is not negative, so a keyed `-00000000.00` is redisplayed as
 * `+00000000.00` because zero is not negative.
 *
 * Assumptions: the conversion is performed on TEXT, digit by digit, and never through a numeric type.
 * Transformation rule T3 forbids `float` and `double` in the money path, and JavaScript's only numeric
 * type is an IEEE-754 double, so `Number('0.1')` cannot be part of a money conversion that has to
 * agree with a `NUMERIC(12,2)` column to the cent.
 *
 * Assumptions: two input shapes are accepted because two callers need it. The screen's own twelve-
 * character field arrives as a sign, eight digits, a point and two decimals; the service's normalised
 * `Money` string arrives as an optional minus, one to ten integral digits, a point and two decimals --
 * unsigned when positive and unpadded -- so the mask is what brings the two into one rendering.
 * @param {string} value - Either the screen's shaped twelve-character amount or a `Money` string.
 * @returns {string | null} The value rendered through the mask, or `null` when it is neither shape or
 *   when its integer part needs more than the eight digits the mask holds -- which the service's own
 *   ninth digit can reach even though this screen's field cannot key it.
 */
export function toEditMaskAmount(value: string): string | null {
  const trimmed = value.trim();
  const shaped = hasBaselineAmountShape(value);
  const money = SERVICE_MONEY.test(trimmed);

  if (!shaped && !money) {
    return null;
  }

  const negative = trimmed.startsWith('-');
  const unsigned = shaped || negative || trimmed.startsWith('+') ? trimmed.slice(1) : trimmed;
  const [integerPart = '', fractionPart = ''] = unsigned.split('.');

  if (integerPart.length > AMOUNT_MASK_INTEGER_DIGITS) {
    return null;
  }

  const integerDigits = integerPart.padStart(AMOUNT_MASK_INTEGER_DIGITS, '0');
  const sign = ALL_ZERO_DIGITS.test(`${integerDigits}${fractionPart}`) || !negative ? '+' : '-';
  return `${sign}${integerDigits}.${fractionPart}`;
}

/**
 * Re-renders an amount the operator KEYED into the reference's `+99999999.99` field form.
 *
 * ⚠️ Purpose: this closes the defect that made the acceptance flow in AAP section 0.9.4 unrunnable.
 * Browser validation drove eighteen amounts through this field and only three were accepted -- `-` plus
 * exactly eight digits plus `.` plus two digits -- so `100.00`, `123.45`, `-100.00` and `00000100.00`
 * were all refused, and the tester concluded that no positive amount could be entered at all. The
 * conclusion is narrowly wrong -- `hasBaselineAmountShape` admits `'+'` in the sign position, because
 * `TRNAMTI(1:1) NOT EQUAL '-' AND '+'` at `app/cbl/COTRN02C.cbl` L340 does -- but `+00000100.00` is not
 * a form anybody types, so the practical effect was as reported: an operator had no reachable spelling of
 * a positive amount, and every human spelling of any amount was refused.
 *
 * ⚠️ Refactoring Rationale: the field is given a NORMALISER rather than a looser predicate, and the
 * distinction is what keeps parity intact. Relaxing `hasBaselineAmountShape` to accept `100.00` would
 * make this screen accept a twelve-position field the reference refuses, which is a parity divergence in
 * the validation itself. A normaliser changes only what the CONTROL holds: the operator keys `100.00`,
 * the field comes to hold `+00000100.00`, and the predicate then judges exactly the string the reference
 * would have received. AAP section 0.3.2 assigns this shape explicitly -- a money field is an `Input`
 * with a `Form.Item` normalizer -- so it is the specified treatment rather than an invention.
 *
 * Assumptions: the reference performs the same rendering ONE TURN LATER. L383-L386 converts the keyed
 * amount with `NUMVAL-C`, moves it through `WS-TRAN-AMT-E PIC +99999999.99` and moves that back into
 * `TRNAMTI`, so the canonical form is what the operator sees after a turn. Doing it at the edit boundary
 * shows them the same string before the turn instead of after it; the mask is the reference's own.
 *
 * Assumptions: an unreadable value is returned UNCHANGED so the refusal still names it. `abc`, `1.234`
 * and a nine-digit integer part all leave here as they arrived, and `dataFieldFailure` then raises
 * `Amount should be in format -99999999.99` against what the operator actually typed -- which is more
 * use to them than a field silently rewritten to something they did not mean.
 *
 * Assumptions: the width test is how "unreadable" is decided, because both helpers return their input
 * unchanged when they cannot parse it. A rendering that is not exactly the picture's twelve characters
 * either failed to parse or needed a ninth integer digit the mask has no position for, and neither can
 * be accepted.
 * @param {string} keyed - The amount exactly as the operator left it in the control.
 * @returns {string} The same value rendered through `+99999999.99`, or unchanged when it does not fit.
 */
export function canonicaliseKeyedAmount(keyed: string): string {
  if (isBlankField(keyed)) {
    return keyed;
  }

  /*
   * WHY : ⚠️ Assumptions: a value carrying NO DIGIT is never normalised, and this guard is load-bearing
   *       rather than defensive. `stripMoneyEditMask` reads an absent integer run as zero -- deliberately,
   *       so that the mask's own zero-suppressed rendering round-trips -- so a lone `'-'` would otherwise
   *       arrive here, become `+00000000.00`, satisfy every edit and post a zero-dollar transaction for an
   *       operator who had typed one character and pressed Enter. The reference refuses a lone sign:
   *       `app/cbl/COTRN02C.cbl` L341-L342 requires `TRNAMTI(2:8)` and `TRNAMTI(10:2)` to be numeric, and
   *       for `'-'` in a space-filled field both are blanks. Refusing it here keeps the two in agreement.
   * WHY : Trade-offs: the normaliser therefore admits a spelling the reference refuses -- `100.00` and
   *       `.56` both reach it as valid where a space-filled `TRNAMTI` would not -- and that IS a
   *       behavioural divergence, recorded as one. It is the divergence the fix requires: browser
   *       validation established that no positive amount was enterable at all, which made the transaction
   *       add flow in AAP section 0.9.4 unrunnable, and AAP section 0.3.2 specifies a `Form.Item`
   *       normalizer for exactly this field. What is NOT relaxed is the predicate: a value with no digits,
   *       three decimal places, a ninth integer digit or a stray letter is still refused by the reference's
   *       own sentence.
   */
  if (!/[0-9]/u.test(keyed)) {
    return keyed;
  }

  const rendered = applyMoneyEditMask(stripMoneyEditMask(keyed), MONEY_PICTURES.transactionAmount);
  return rendered.length === MONEY_PICTURES.transactionAmount.width ? rendered : keyed;
}

/** Days each month holds in a common year, indexed from January, per the Gregorian calendar. */
const COMMON_YEAR_MONTH_LENGTHS = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

/** February's length in a leap year, which is the single value the leap rule changes. */
const LEAP_FEBRUARY_LENGTH = 29;

/** Ordinal of February among the months, used to apply the leap rule to that month alone. */
const FEBRUARY_ORDINAL = 2;

/**
 * Reports whether a shape-valid `YYYY-MM-DD` field names a date that exists.
 *
 * ⚠️ Purpose: browser validation keyed `2022-13-45` into the originating date, saw NO message of any
 * kind, and watched it reach the service as `"originDate":"2022-13-45"` for a 200. Only the field's
 * SHAPE was ever checked. `app/cbl/COTRN02C.cbl` L388-L407 refuses such a value with
 * `Orig Date - Not a valid date...`, a sentence `ui/src/messages/messages.ts` already carries against
 * L401 and which was unreachable.
 *
 * ⚠️ Assumptions: this check is strictly INSIDE what the reference refuses, and establishing that is the
 * whole reason it is safe to make locally. The reference refuses a date when `CSUTLDTC`'s severity is
 * not `'0000'` AND its message number is not `'2513'` -- one tolerated complaint. `2513` is
 * `FC-UNSUPP-RANGE`, which `app/cbl/CSUTLDTC.cbl` L66 and L137-L138 render as `Unsupp. Range`: a date
 * the callable service cannot compute a Lilian day number for because it falls outside its supported
 * range. Such a date IS a real calendar date -- `1500-01-01`, for instance -- so the tolerance can never
 * apply to an impossible one. An impossible date draws `FC-BAD-DATE-VALUE` or `FC-INVALID-MONTH`
 * (L64, L67), both of which the reference refuses. Refusing only the impossible therefore cannot reject
 * a date the reference accepts.
 *
 * ⚠️ Assumptions: the RANGE half of the utility's verdict is still delegated, and the delegation the
 * file records elsewhere stands for it. A browser cannot know which range the callable service supports,
 * so a range rule reimplemented here could refuse a date the reference tolerates -- which is the parity
 * failure that reasoning was protecting against. What it over-protected was the impossible date, where
 * no tolerance exists to violate. So the two halves are split: existence is decided here, range at the
 * service, and `com.carddemo.common.validation.DateEditValidator` remains the authority on the second.
 *
 * Assumptions: the arithmetic is done on the digits and never through `Date`. `new Date('2022-13-45')`
 * either rolls over into a different, valid date or yields an invalid one depending on the runtime's
 * parsing, and a rollover would ACCEPT the value the reference refuses.
 *
 * Assumptions: the year is not judged. A year of `0000` is refused by the callable service as
 * `FC-YEAR-IN-ERA-ZERO`, but "which era a year sits in" is the utility's domain rather than the
 * calendar's, so it stays delegated with the range rule.
 * @param {string} value - A date field already known to satisfy {@link hasBaselineIsoDateShape}.
 * @returns {boolean} `true` when the month and day name a day that exists in that year.
 */
export function namesARealCalendarDate(value: string): boolean {
  const field = padToDeclaredWidth(value, TRANSACTION_ADD_FIELD_WIDTHS.originDate);
  const year = Number.parseInt(field.slice(0, 4), 10);
  const month = Number.parseInt(field.slice(5, 7), 10);
  const day = Number.parseInt(field.slice(8, 10), 10);

  if (month < 1 || month > COMMON_YEAR_MONTH_LENGTHS.length) {
    return false;
  }

  /*
   * WHY : Assumptions: the leap rule is the full Gregorian one -- divisible by four, except centuries,
   *       except every fourth century -- rather than the divisible-by-four shorthand. The shorthand
   *       accepts `1900-02-29`, which is not a date, and the reference's own callable service applies
   *       the Gregorian rule, so the shorthand would accept a value the reference refuses.
   */
  const leapYear = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
  const monthLength =
    month === FEBRUARY_ORDINAL && leapYear
      ? LEAP_FEBRUARY_LENGTH
      : (COMMON_YEAR_MONTH_LENGTHS[month - 1] ?? 0);

  return day >= 1 && day <= monthLength;
}

/**
 * Normalises a keyed key field to its declared width the way the reference's numeric `MOVE` does.
 *
 * Assumptions: the reference does not merely read the key field, it rewrites it. `MOVE WS-ACCT-ID-N TO
 * XREF-ACCT-ID, ACTIDINI` at `app/cbl/COTRN02C.cbl` L206-L207 moves a `PIC 9(11)` item into the
 * eleven-character screen field, and a numeric-to-alphanumeric `MOVE` of that kind is zero-filled, so
 * an operator who keys `00000000123` and one who keys the same digits after a conversion both see the
 * padded form. The card branch does the same with `WS-CARD-NUM-N PIC 9(16)` at L220-L221.
 * @param {string} value - Digits the operator keyed, already known to satisfy the numeric class test.
 * @param {number} declaredWidth - The field's `PICTURE` width.
 * @returns {string} The digits left-padded with zeros to the declared width.
 */
export function toZeroFilledKey(value: string, declaredWidth: number): string {
  return value.trim().padStart(declaredWidth, '0');
}

/*
 * WHY : Assumptions: the eleven blank checks are an ORDERED TABLE and the order is the contract. The
 *       reference expresses them as eleven `WHEN` branches of one `EVALUATE TRUE` at
 *       `app/cbl/COTRN02C.cbl` L251-L320, and an `EVALUATE TRUE` selects the FIRST matching branch, so
 *       a submission with three blank fields names exactly one of them -- whichever comes first in this
 *       sequence. The order is therefore observable to an operator correcting a form field by field, and
 *       the screen tests assert it, so the sequence below is transcribed rather than sorted into the
 *       order the fields are laid out in or the order the contract declares them.
 * WHY : Alternatives Considered: Ant Design's `Form` `rules` with `required`, which is the idiomatic way
 *       to express a mandatory field and is not used anywhere in this screen's validation. Rejected
 *       because the form validates every rule it holds and renders every failure at once, which would
 *       show eleven refusals where the reference shows one, and would leave the screen with no single
 *       sentence to place in the message band and no single field to move the cursor to. A short-
 *       circuiting chain is what reproduces one message, one marker and one cursor position.
 */

/**
 * The eleven fields the reference tests for emptiness, in its own order, with the sentence each raises.
 *
 * Assumptions: the two key fields are absent from this table on purpose. Their emptiness is not an error
 * on its own -- either one satisfies the other's absence -- so it is decided by {@link keyFieldFailure}
 * before this table is read, exactly as `VALIDATE-INPUT-KEY-FIELDS` runs before
 * `VALIDATE-INPUT-DATA-FIELDS` at `app/cbl/COTRN02C.cbl` L166-L167.
 */
const BLANK_CHECK_SEQUENCE: readonly (readonly [TransactionAddField, string])[] = [
  ['typeCode', ADD_MESSAGES.TYPE_CD_CAN_NOT_BE_EMPTY],
  ['categoryCode', ADD_MESSAGES.CATEGORY_CD_CAN_NOT_BE_EMPTY],
  ['source', ADD_MESSAGES.SOURCE_CAN_NOT_BE_EMPTY],
  ['description', ADD_MESSAGES.DESCRIPTION_CAN_NOT_BE_EMPTY],
  ['amount', ADD_MESSAGES.AMOUNT_CAN_NOT_BE_EMPTY],
  ['originDate', ADD_MESSAGES.ORIG_DATE_CAN_NOT_BE_EMPTY],
  ['processDate', ADD_MESSAGES.PROC_DATE_CAN_NOT_BE_EMPTY],
  ['merchantId', ADD_MESSAGES.MERCHANT_ID_CAN_NOT_BE_EMPTY],
  ['merchantName', ADD_MESSAGES.MERCHANT_NAME_CAN_NOT_BE_EMPTY],
  ['merchantCity', ADD_MESSAGES.MERCHANT_CITY_CAN_NOT_BE_EMPTY],
  ['merchantZip', ADD_MESSAGES.MERCHANT_ZIP_CAN_NOT_BE_EMPTY],
];

/**
 * Reports which key field a submission is addressed by, or the failure that stops it.
 *
 * Assumptions: the account identifier takes PRECEDENCE over the card number, and that is the ordered
 * `EVALUATE TRUE` at `app/cbl/COTRN02C.cbl` L195-L230 rather than a preference chosen here: its first
 * branch fires whenever the account field carries anything, so a submission naming both is resolved by
 * the account and the keyed card number is overwritten from the cross-reference at L209. Both branches
 * write a field back -- the account branch fills the card number and the card branch fills the account
 * identifier -- so neither key is merely read.
 *
 * Refactoring Rationale: the cross-reference resolution itself is the SERVICE's and is not attempted
 * here. The reference reads `CXACAIX` or `CCXREF` in this paragraph, and the migrated equivalent is one
 * request that carries whichever key was supplied and resolves the other while it posts, so the browser
 * makes one round trip where a client-side lookup would make two and would need a second service's
 * client this screen does not depend on. The four sentences that resolution can raise are surfaced from
 * the response by {@link screenMessageForFailure}, and which of the account-flavoured or card-flavoured
 * pair applies is decided by the key this function reports.
 * @param {TransactionAddValues} values - Current field values as the operator keyed them.
 * @returns {{ failure: TransactionAddFieldError } | { key: 'accountId' | 'cardNumber' }} The refusal
 *   that stops the submission, or which of the two keys addresses it.
 */
export function keyFieldFailure(
  values: TransactionAddValues,
): { failure: TransactionAddFieldError } | { key: 'accountId' | 'cardNumber' } {
  if (!isBlankField(values.accountId)) {
    if (!isNumericField(values.accountId, TRANSACTION_ADD_FIELD_WIDTHS.accountId)) {
      return {
        failure: {
          field: 'accountId',
          message: ADD_MESSAGES.ACCOUNT_ID_MUST_BE_NUMERIC,
          state: 'NOT_OK',
        },
      };
    }
    return { key: 'accountId' };
  }

  if (!isBlankField(values.cardNumber)) {
    if (!isNumericField(values.cardNumber, TRANSACTION_ADD_FIELD_WIDTHS.cardNumber)) {
      return {
        failure: {
          field: 'cardNumber',
          message: ADD_MESSAGES.CARD_NUMBER_MUST_BE_NUMERIC,
          state: 'NOT_OK',
        },
      };
    }
    return { key: 'cardNumber' };
  }

  /*
   * WHY : Assumptions: the refusal names the ACCOUNT field even though neither key was supplied, and the
   *       state is blank rather than not-OK. The reference's `WHEN OTHER` branch moves -1 into
   *       `ACTIDINL` at `app/cbl/COTRN02C.cbl` L228, which is how a pseudo-conversational screen places
   *       the cursor, so the account field is where an operator is sent to correct this -- and it is
   *       genuinely empty, which is the condition `app/cpy/CSSETATY.cpy` L23-L26 nests the asterisk
   *       marker inside.
   */
  return {
    failure: {
      field: 'accountId',
      message: ADD_MESSAGES.ACCOUNT_OR_CARD_NUMBER_MUST_BE_ENTERED,
      state: 'BLANK',
    },
  };
}

/**
 * Runs the data-field chain and reports the first refusal, in the reference's own order.
 *
 * Assumptions: the sequence is eleven blank tests, then two numeric tests, then the amount shape, then
 * the originating-date shape, then the processing-date shape, and LAST the merchant-identifier numeric
 * test. That last position is the reference's and is preserved deliberately: the merchant test is not
 * part of the numeric `EVALUATE` at `app/cbl/COTRN02C.cbl` L322-L337 but a standalone `IF` at L430-L436
 * placed after BOTH date-utility calls, so it is the final check before the confirmation character is
 * read. Grouping it with the other two numeric tests would report it earlier than the reference does.
 *
 * ⚠️ Refactoring Rationale: this chain reaches every step the reference's `VALIDATE-INPUT-DATA-FIELDS`
 * reaches, including the two it used not to. The note this replaces recorded the amount's canonical
 * re-render (L383-L386) and the two `CSUTLDTC` calendar evaluations (L389-L427) as belonging to the
 * service, "so they arrive with a response rather than before the request", and stated the consequence:
 * a submission naming an unreal date such as `2024-02-31` alongside a non-numeric merchant identifier
 * reported the merchant identifier where the reference reports the date. Neither step needs a round trip.
 * The re-render is {@link canonicaliseKeyedAmount}, applied by {@link canonicalValues} before this chain
 * reads the field, and the calendar verdicts are {@link namesARealCalendarDate}, called below in the
 * reference's own position -- after both shape tests, before the merchant identifier. So this chain now
 * reports the same field as the reference for every combination, and an unreal date is refused before the
 * operator is asked to confirm, which is where L389-L427 refuses it.
 * @param {TransactionAddValues} values - Current field values as the operator keyed them.
 * @returns {TransactionAddFieldError | null} The first refusal, or `null` when every checkable rule
 *   passes.
 */
export function dataFieldFailure(values: TransactionAddValues): TransactionAddFieldError | null {
  for (const [field, message] of BLANK_CHECK_SEQUENCE) {
    if (isBlankField(values[field])) {
      return { field, message, state: 'BLANK' };
    }
  }

  if (!isNumericField(values.typeCode, TRANSACTION_ADD_FIELD_WIDTHS.typeCode)) {
    return { field: 'typeCode', message: ADD_MESSAGES.TYPE_CD_MUST_BE_NUMERIC, state: 'NOT_OK' };
  }
  if (!isNumericField(values.categoryCode, TRANSACTION_ADD_FIELD_WIDTHS.categoryCode)) {
    return {
      field: 'categoryCode',
      message: ADD_MESSAGES.CATEGORY_CD_MUST_BE_NUMERIC,
      state: 'NOT_OK',
    };
  }

  /*
   * WHY : Assumptions: the sentence is the reference's format string verbatim,
   *       `Amount should be in format -99999999.99`, and it is neither localised nor reworded. It reads
   *       as guidance rather than as a refusal, and that is precisely why it must not be replaced by a
   *       locale-aware hint: the string IS the contract -- it states the sign, the eight integer digits,
   *       the point and the two decimals that `hasBaselineAmountShape` tests for -- and a golden-master
   *       comparison reads it character for character.
   */
  if (!hasBaselineAmountShape(values.amount)) {
    return {
      field: 'amount',
      message: ADD_MESSAGES.AMOUNT_SHOULD_BE_IN_FORMAT_99999999_99,
      state: 'NOT_OK',
    };
  }

  if (!hasBaselineIsoDateShape(values.originDate)) {
    return {
      field: 'originDate',
      message: ADD_MESSAGES.ORIG_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD,
      state: 'NOT_OK',
    };
  }
  if (!hasBaselineIsoDateShape(values.processDate)) {
    return {
      field: 'processDate',
      message: ADD_MESSAGES.PROC_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD,
      state: 'NOT_OK',
    };
  }

  /*
   * WHY : ⚠️ Assumptions: both calendar checks sit HERE -- after both shape tests and before the merchant
   *       identifier -- because that is the reference's order and the order decides which sentence an
   *       operator reads when two fields are wrong at once. `app/cbl/COTRN02C.cbl` tests the originating
   *       date's shape at L353-L366, the processing date's at L368-L381, then calls `CSUTLDTC` for the
   *       originating date at L389-L407 and for the processing date at L409-L427, and only then reaches
   *       the merchant identifier at L430-L436. So a malformed processing date outranks an impossible
   *       originating one, which reversing these two blocks would silently change.
   * WHY : Assumptions: the shape test having already passed is what lets {@link namesARealCalendarDate}
   *       read fixed offsets out of the field. It is called nowhere else, so the precondition holds by
   *       construction rather than by convention.
   */
  if (!namesARealCalendarDate(values.originDate)) {
    return {
      field: 'originDate',
      message: ADD_MESSAGES.ORIG_DATE_NOT_A_VALID_DATE,
      state: 'NOT_OK',
    };
  }
  if (!namesARealCalendarDate(values.processDate)) {
    return {
      field: 'processDate',
      message: ADD_MESSAGES.PROC_DATE_NOT_A_VALID_DATE,
      state: 'NOT_OK',
    };
  }

  if (!isNumericField(values.merchantId, TRANSACTION_ADD_FIELD_WIDTHS.merchantId)) {
    return {
      field: 'merchantId',
      message: ADD_MESSAGES.MERCHANT_ID_MUST_BE_NUMERIC,
      state: 'NOT_OK',
    };
  }

  return null;
}

/** Leading zeros the wire form drops, kept only where a digit follows so `0.00` survives. */
const LEADING_ZEROS = /^0+(?=[0-9])/u;

/** The four confirmation letters the contract admits on the wire, in both cases. */
const WIRE_CONFIRMATION = /^[YyNn]$/u;

/**
 * Converts the screen's edit-mask amount into the form the service contract accepts.
 *
 * Assumptions: the display form and the wire form are DIFFERENT and both are constrained. The screen
 * holds `+99999999.99` -- a mandatory sign and eight zero-filled integer digits -- while
 * `transaction-api.yaml` declares the amount as `^-?[0-9]{1,9}\.[0-9]{2}$`, which admits no plus sign
 * and no leading zeros. Sending the displayed value unchanged would be refused by the contract before
 * the service ever read it, so this conversion is required rather than cosmetic.
 *
 * Assumptions: the conversion runs through {@link toEditMaskAmount} first so that both callers agree on
 * one canonical value, which is also what makes negative zero collapse: that function emits a plus for
 * any all-zero amount, so `-00000000.00` reaches the wire as `0.00` rather than as `-0.00`.
 * @param {string} value - The amount as the operator keyed it, or as the mask displays it.
 * @returns {string | null} The contract's monetary form, or `null` when the value is not an amount this
 *   screen can express.
 */
export function toWireAmount(value: string): string | null {
  const masked = toEditMaskAmount(value);
  if (masked === null) {
    return null;
  }

  const negative = masked.startsWith('-');
  const [integerDigits = '', fractionDigits = ''] = masked.slice(1).split('.');
  const significantDigits = integerDigits.replace(LEADING_ZEROS, '');
  return `${negative ? '-' : ''}${significantDigits}.${fractionDigits}`;
}

/**
 * Builds the submission body from the screen's values and the key that addresses it.
 *
 * Assumptions: exactly ONE key member is sent, never both, and the contract requires precisely that --
 * `TransactionCreateRequest` declares both members optional under a pair of `required` alternatives, and
 * the service's own request record states that "whichever key is supplied, the other is a lookup result
 * rather than an input". Sending both would assert a pairing the browser has not verified and that the
 * cross-reference is the only authority on.
 *
 * Assumptions: the key is zero-filled to its declared width because the contract demands the full width
 * -- `^[0-9]{11}$` for an account identifier and `^[0-9]{16}$` for a card number -- and because the
 * reference's numeric `MOVE` back into the screen field produces the same padded value.
 *
 * Trade-offs: the descriptive fields are trimmed and the fixed-width padding a 3270 turn carried is not
 * reproduced. A trailing blank in a `PIC X(60)` description is padding rather than data, which is why
 * AAP section 0.4.1.3 maps descriptive character fields to `VARCHAR`, and sending the padding would
 * store bytes that differ from the same value keyed on a narrower field.
 * @param {TransactionAddValues} values - Current field values, already past {@link dataFieldFailure}.
 * @param {'accountId' | 'cardNumber'} key - Which key field addresses the submission.
 * ⚠️ Assumptions: the binding token is carried BACK on a confirming turn and omitted when the screen
 * holds none. It is the service's own opaque record of which card the previous turn resolved, and
 * returning it is what lets the service prove the write lands on that same card without this browser
 * having been given the sixteen digits to re-send. A first turn has no token and sends none, which is the
 * single-turn arm `app/cbl/COTRN02C.cbl` L166 to L181 performs; the service treats an absent token as that
 * arm and resolves the card from the key alone.
 * @param {string} confirmation - The confirmation character as keyed; sent only when it is one of the
 *   four letters the contract admits, and omitted otherwise so an unconfirmed turn is spelled by absence.
 * @param {string | null} confirmationToken - The opaque binding the previous turn's preview published, or
 *   `null` on a turn that has no preview behind it.
 * @returns {TransactionCreateRequest | null} The body to submit, or `null` when the amount cannot be
 *   expressed on the wire, which the validation chain has already excluded.
 */
export function buildCreateRequest(
  values: TransactionAddValues,
  key: 'accountId' | 'cardNumber',
  confirmation: string,
  confirmationToken: string | null,
): TransactionCreateRequest | null {
  const amount = toWireAmount(values.amount);
  if (amount === null) {
    return null;
  }

  const fields = {
    typeCode: values.typeCode.trim(),
    categoryCode: values.categoryCode.trim(),
    source: values.source.trim(),
    description: values.description.trim(),
    amount,
    originDate: values.originDate.trim(),
    processDate: values.processDate.trim(),
    merchantId: values.merchantId.trim(),
    merchantName: values.merchantName.trim(),
    merchantCity: values.merchantCity.trim(),
    merchantZip: values.merchantZip.trim(),
  };

  const addressed: TransactionCreateRequest =
    key === 'accountId'
      ? {
          ...fields,
          accountId: toZeroFilledKey(values.accountId, TRANSACTION_ADD_FIELD_WIDTHS.accountId),
        }
      : {
          ...fields,
          cardNumber: toZeroFilledKey(values.cardNumber, TRANSACTION_ADD_FIELD_WIDTHS.cardNumber),
        };

  /*
   * WHY : Assumptions: the token is attached whenever the screen holds one, INCLUDING on a turn whose
   *       confirmation is blank or declining. The service refuses a token that does not name the card it
   *       resolves, so sending it on every turn behind a preview is what makes a key edited between the
   *       preview and the confirmation refusable rather than silently written against a different card --
   *       and a declining turn that carries it still describes the same record in its answer.
   *       Trade-offs: an operator whose token has expired sees a refusal naming the card number and has to
   *       press Enter again, where a browser holding the sixteen digits would have re-sent them and written
   *       without asking. That is the exchange AAP section 0.4.1.9 asks for: the digits do not reach the
   *       browser, so the only thing that can go stale is a token whose refusal is recoverable in one turn.
   */
  const bound: TransactionCreateRequest =
    confirmationToken === null ? addressed : { ...addressed, confirmationToken };

  const keyed = confirmation.trim();
  return WIRE_CONFIRMATION.test(keyed) ? { ...bound, confirmation: keyed } : bound;
}

/**
 * Builds the copy-last submission, which carries a key and a confirmation and no data member.
 *
 * ⚠️ Refactoring Rationale: this builder exists because the copy turn used to send a full capture body,
 * whose eleven data members are each required -- so the action could only be submitted from a screen that
 * was ALREADY filled in, and pressing the key on a blank one was refused locally with the first blank
 * field's sentence before any request left the browser. That is the opposite of what the key is for.
 * `app/cbl/COTRN02C.cbl` L473 performs `VALIDATE-INPUT-KEY-FIELDS` and nothing else before the read, and
 * L481 to L492 then fill the eleven fields, so they are the action's OUTPUT.
 *
 * Assumptions: the key is chosen and zero-filled exactly as {@link buildCreateRequest} chooses and fills
 * it, and for the same two reasons -- one key is sent because the other is a lookup result rather than an
 * input, and the width is filled because the contract requires the declared width.
 *
 * Assumptions: the confirmation is carried under the same rule the capture body applies: sent when it is
 * one of the four letters the contract admits and omitted otherwise, so an unconfirmed turn is spelled by
 * absence. Carrying it preserves the reference's fall-through at L495 -- a copy pressed with an
 * affirmative answer already keyed copies and writes in one turn.
 * @param {TransactionAddValues} values - Current field values, already past {@link keyFieldFailure}.
 * @param {'accountId' | 'cardNumber'} key - Which key field addresses the submission.
 * @param {string} confirmation - The confirmation character as keyed.
 * @returns {CopyLastTransactionRequest} The body to submit.
 */
export function buildCopyRequest(
  values: TransactionAddValues,
  key: 'accountId' | 'cardNumber',
  confirmation: string,
): CopyLastTransactionRequest {
  const addressed: CopyLastTransactionRequest =
    key === 'accountId'
      ? { accountId: toZeroFilledKey(values.accountId, TRANSACTION_ADD_FIELD_WIDTHS.accountId) }
      : { cardNumber: toZeroFilledKey(values.cardNumber, TRANSACTION_ADD_FIELD_WIDTHS.cardNumber) };

  const keyed = confirmation.trim();
  return WIRE_CONFIRMATION.test(keyed) ? { ...addressed, confirmation: keyed } : addressed;
}

/**
 * Adopts the copied record over the form, exactly as the reference's copy block does.
 *
 * Assumptions: ELEVEN values come from the copy block, whose reach is exactly that. `app/cbl/COTRN02C.cbl`
 * L481 to L492 moves the type code, category code, source, amount, description, the two dates and the four
 * merchant columns, and moves nothing into either key field or into the confirmation.
 *
 * ⚠️ Refactoring Rationale: the two KEY fields are nevertheless repainted from the RESOLVED pair, and
 * leaving them untouched -- which two authored shapes of this function did, on the reading that the copy
 * block moves nothing into them -- loses a step the reference performs on the same turn. The copy paragraph
 * begins at L473 by performing `VALIDATE-INPUT-KEY-FIELDS`, and that paragraph writes BOTH key fields: the
 * account arm reads the cross-reference at L208 and moves the card number it found into `CARDNINI` at L209,
 * the card arm reads at L219 and moves the account identifier it found into `ACTIDINI` at L221, and each
 * arm moves its own numeric value back into its own field first (L206, L220) so the field ends zero-filled
 * to its declared width. The screen is then re-sent, so after a copy the operator is looking at the
 * resolved pair. A browser that left a keyed card standing while the service resolved a different one from
 * the account would show the operator one card and write another -- the disagreement the reference cannot
 * reach, because L209 and the `SEND MAP` share one piece of state.
 *
 * Assumptions: repainting the keys does NOT change which account the capture lands against, which is the
 * property the untouched-keys reading was protecting. The service resolves account-first and answers with
 * the pair it resolved, so the account written is the account the operator keyed; what changes is that the
 * card field stops disagreeing with it.
 *
 * Assumptions: this is a REPLACEMENT and not a merge, so a field the operator had already filled in
 * loses its value. That is the reference behaviour -- the eleven moves are unconditional once the row is
 * read -- and it is why the key press is worth pressing at all.
 *
 * ⚠️ Refactoring Rationale: the AMOUNT arrives separately, in `normalisedAmount`, rather than as a member
 * of `copied`. Two shapes were authored for the copied record, one carrying the amount and one not, and
 * the one without it is what transaction-service publishes: line 481 renders the stored figure through
 * `WS-TRAN-AMT-E PIC +99999999.99` and line 485 moves THAT edited rendering, which is the same value the
 * preview's own amount member already carries, so a member here would publish one figure twice in one
 * body and invite the two copies to be read as different things. Taking it as a parameter keeps this one
 * function the single place the eleven moves are expressed while leaving the amount's single source of
 * truth where the contract puts it.
 *
 * ⚠️ Assumptions: the resolved pair is a parameter of its own and NOT a member of `copied`, because the
 * service reports it on every withheld answer while `copied` arrives on a copy turn alone. That is what
 * lets an ordinary capture repaint its account key too, which is what the reference does -- L166 performs
 * `VALIDATE-INPUT-KEY-FIELDS` for the Enter arm exactly as L473 does for the copy arm. Only the ACCOUNT
 * half is written into a control; the card half is masked and is rendered as protected text, for the
 * reason recorded at the assignment below.
 *
 * Assumptions: each input is applied independently and an absent one suppresses nothing else. A preview
 * whose amount the screen's mask cannot express leaves the amount as it stands rather than blanking it, and
 * an ordinary capture's preview carries no copied record at all.
 *
 * Assumptions: the copied text is painted VERBATIM and is NOT put through `retainPrintableText`, unlike a
 * value the operator keys. The two cases differ in what a change would mean: filtering a keystroke
 * reproduces what the terminal did to a key it could not transmit, whereas filtering a STORED value would
 * silently show the operator a record that differs from the row they asked to copy. The service refuses
 * such a row by name -- its copy path applies the same domain to a capture assembled in-process -- so the
 * turn answers with a field-level refusal this screen already renders, and the operator sees which field
 * to re-key rather than an altered value they did not author.
 * @param {TransactionAddValues} previous - Values as they stand, whose untouched fields survive.
 * @param {CopiedTransactionData | null} copied - The ten values the service read from the stored row,
 *   or `null` on a turn that copied nothing.
 * @param {ResolvedKeys | null} resolved - The account and card the service resolved, or `null` when this
 *   turn reported none.
 * @param {string | null} normalisedAmount - The preview's amount rendered through this screen's edit
 *   mask, or `null` when the mask cannot express it.
 * @returns {TransactionAddValues} The values the form now holds, ready for the confirmation turn.
 */
export function paintCopiedValues(
  previous: TransactionAddValues,
  copied: CopiedTransactionData | null,
  resolved: ResolvedKeys | null,
  normalisedAmount: string | null,
): TransactionAddValues {
  return {
    ...previous,
    // WHY : ⚠️ Refactoring Rationale: only the ACCOUNT key is repainted from the resolution, and the card
    //       control is left exactly as the operator left it. Both were repainted while the answer carried
    //       sixteen digits; the answer now carries the masked rendering, and writing that into the card
    //       control would put a value into an editable key field that the service refuses as non-numeric
    //       on the next turn -- turning a display fix into a submission that cannot succeed. The resolved
    //       card is shown beside the form instead, as protected text, and the confirming turn is bound by
    //       the token rather than by what the control holds.
    ...(resolved === null ? {} : { accountId: resolved.accountId }),
    ...(copied === null
      ? {}
      : {
          typeCode: copied.typeCode,
          categoryCode: copied.categoryCode,
          source: copied.source,
          description: copied.description,
          originDate: copied.originDate,
          processDate: copied.processDate,
          merchantId: copied.merchantId,
          merchantName: copied.merchantName,
          merchantCity: copied.merchantCity,
          merchantZip: copied.merchantZip,
        }),
    ...(normalisedAmount === null ? {} : { amount: normalisedAmount }),
  };
}

/**
 * Reports whether a name the service attributed a refusal to is one of this screen's fields.
 *
 * Assumptions: the lookup is restricted to the table's OWN properties, and the restriction is
 * load-bearing rather than defensive style. The width table is a plain object, so it still inherits from
 * `Object.prototype`, and a bare index would resolve `"constructor"` and `"toString"` to functions and
 * `"__proto__"` to an object -- so a response naming one of those would otherwise narrow to a field this
 * screen would then try to focus.
 * @param {string} name - Field name as the service's problem document spells it.
 * @returns {boolean} `true` when the name is one of the fourteen fields, narrowing it to that union.
 */
export function isTransactionAddField(name: string): name is TransactionAddField {
  return Object.hasOwn(TRANSACTION_ADD_FIELD_WIDTHS, name);
}

/**
 * The refusals whose sentence this screen words itself rather than reading from the response.
 *
 * Assumptions: only the two date fields appear. Every other refusal the service can raise on this
 * request is a shape or class rule that {@link dataFieldFailure} already applied before dispatch, so
 * its sentence is one this screen chose; the calendar verdict is the one rule that needs the server,
 * because a real Gregorian calendar cannot be consulted from the shape alone -- `2022-02-30` satisfies
 * `hasBaselineIsoDateShape` and is still not a date. `app/cbl/COTRN02C.cbl` L389-L427 calls `CSUTLDTC`
 * for exactly that and words the outcome itself at L401 and L421.
 *
 * ⚠️ Assumptions: the reference's calendar rule is SPLIT, and this map is the half that stays at the
 * service. L389-L427 refuses a date only when the utility's severity is not `'0000'` AND its message
 * number is not `'2513'`, so one specific complaint is tolerated and its date accepted. Reading
 * `app/cbl/CSUTLDTC.cbl` settles which: L66 declares `2513` as `FC-UNSUPP-RANGE` and L137-L138 render
 * it `Unsupp. Range`, so the tolerated complaint means "this is a real date the utility cannot compute a
 * day number for" -- a date outside its supported range, such as `1500-01-01`. That is a property of the
 * utility's own limits rather than of the calendar, a browser cannot know which range it supports, and a
 * local approximation of it could refuse a date the reference accepts. So the range half is delegated,
 * `com.carddemo.common.validation.DateEditValidator` owns it, and these two sentences render its verdict.
 *
 * ⚠️ Refactoring Rationale: the EXISTENCE half is now applied locally by
 * {@link namesARealCalendarDate}, and the same reading is what makes that safe. An impossible date draws
 * `FC-BAD-DATE-VALUE` or `FC-INVALID-MONTH` -- `app/cbl/CSUTLDTC.cbl` L64 and L67 -- never `2513`, so no
 * tolerance can ever apply to one and refusing it locally cannot diverge. The previous shape of this
 * comment declined the whole rule on the strength of the exemption without establishing what the
 * exemption covered, and browser validation measured the cost of that caution: `2022-13-45` drew no
 * message at all and reached the service verbatim, because nothing between the shape test and the wire
 * looked at it.
 *
 * Trade-offs: a date that exists but sits outside the utility's range still costs one round trip, where
 * an impossible one now costs none. That is the right side of the trade: the round trip buys correctness
 * on the only half where a local rule could be wrong.
 */
const CATALOG_OWNED_VERDICTS: Partial<Record<TransactionAddField, string>> = {
  originDate: ADD_MESSAGES.ORIG_DATE_NOT_A_VALID_DATE,
  processDate: ADD_MESSAGES.PROC_DATE_NOT_A_VALID_DATE,
};

/**
 * Maps a problem document's per-field entries onto this screen's controls.
 *
 * ⚠️ Assumptions: entries naming something this screen does not render are DROPPED rather than rendered
 * loose, because there is no control to attach them to -- and the DROP IS ONLY SAFE because
 * {@link screenMessageForFailure} bands the first such entry's own sentence and suppresses the cursor
 * move. It was not safe before: browser validation answered a submission with a document refusing two
 * fields belonging to another screen, every entry was dropped here, and the operator was shown the
 * document's summary line `Please fix the highlighted fields` over a form with nothing highlighted and a
 * cursor parked on an innocent field. Neither refusal sentence appeared anywhere in the document. This
 * function is unchanged by that fix; what changed is that its output being empty is now handled rather
 * than assumed benign.
 *
 * Trade-offs: the array is kept whole where the reference has at most one. The service accumulates its
 * violations in one pass -- its request record documents that it "accumulates" where "the reference
 * short-circuits" -- so a response can legitimately name several fields, and discarding all but the
 * first would hide refusals the operator has to fix. The band still carries exactly one sentence, which
 * is the reference's observable behaviour, and the first entry is the one the cursor is moved to.
 *
 * Assumptions: a refusal naming either date field is rendered with THIS tree's catalog sentence rather
 * than the one the response carried, and the substitution is safe because only one refusal can reach
 * here. `app/cbl/COTRN02C.cbl` refuses a malformed date at L353-L381 and an unreal one at L389-L427, and
 * the shape half of that pair is already spent: {@link hasBaselineIsoDateShape} enforces exactly the
 * pattern the service's own contract declares, so a request that was dispatched at all cannot be
 * refused for its shape. What survives is the calendar verdict, whose two sentences the reference words
 * at L401 and L421. Pinning them locally makes `ui/src/messages/messages.ts` the sole owner of every
 * sentence this screen can paint -- the module states that role for itself -- so a reworded service
 * cannot change what the operator reads. The two strings agree today, which is why this changes no
 * observable text; it removes the dependency on their continuing to agree.
 * @param {ApiError} problem - The normalised problem document carried by a rejected request.
 * @returns {readonly TransactionAddFieldError[]} One entry per refusal this screen can render.
 */
export function resolveApiFieldErrors(problem: ApiError): readonly TransactionAddFieldError[] {
  const resolved: TransactionAddFieldError[] = [];
  for (const entry of problem.fieldErrors) {
    if (isTransactionAddField(entry.field)) {
      resolved.push({
        field: entry.field,
        message: CATALOG_OWNED_VERDICTS[entry.field] ?? entry.message,
        state: entry.state,
      });
    }
  }
  return resolved;
}

/** Which submission a failure came out of, so the sentence names the reference's own failure site. */
export interface TransactionAddFailureContext {
  /** Key field that addressed the submission, which selects the account- or card-flavoured sentence. */
  readonly key: 'accountId' | 'cardNumber';
  /** Whether the request was the copy-last action, whose reference site is the transaction browse. */
  readonly copying: boolean;
  /** Whether the request carried a confirming answer, so its reference site is the file write. */
  readonly writing: boolean;
}

/** What a rejected submission puts on the screen: one sentence, any field refusals, and where to focus. */
export interface TransactionAddFailureReport {
  /** Verbatim sentence for the message band. */
  readonly message: string;
  /** Refusals to render beneath the controls they name. */
  readonly fieldErrors: readonly TransactionAddFieldError[];
  /**
   * Field to move the cursor to, the analogue of the reference's `MOVE -1 TO <field>L`, or `null`.
   *
   * ⚠️ Assumptions: `null` means LEAVE THE CURSOR ALONE, and the member became nullable because there is
   * a real failure with no field to name. Browser validation answered a submission with a document
   * refusing two fields this screen does not render, and the cursor was moved to `Enter Acct #` -- a
   * field with nothing wrong with it -- which tells the operator the refusal is there. The reference only
   * ever moves the cursor to a field it has just refused, so when it has refused none, it moves none.
   */
  readonly focus: TransactionAddField | null;
}

/** HTTP status the service answers when no account, card or transaction carries the key. */
const NOT_FOUND_STATUS = 404;

/** HTTP status the service answers when the assigned identifier is already present. */
const CONFLICT_STATUS = 409;

/** HTTP status the service answers when it refused a field. */
const BAD_REQUEST_STATUS = 400;

/**
 * Turns a rejected request into the sentence, refusals and cursor position the reference would show.
 *
 * Assumptions: the reference raises EIGHT different sentences across four failure sites, and one HTTP
 * status can stand in front of more than one of them, so the site is reconstructed from the context
 * rather than from the status alone. The account-flavoured and card-flavoured pairs are selected by the
 * key that addressed the submission, which is the same discrimination the reference makes by having read
 * either `CXACAIX` or `CCXREF`. The two transaction-browse sentences are reachable only from the
 * copy-last action, which is the migrated form of the `STARTBR`/`READPREV` pair at
 * `app/cbl/COTRN02C.cbl` L475-L478, and `Unable to Add Transaction...` only from a confirmed write,
 * which is the `WHEN OTHER` arm of `WRITE-TRANSACT-FILE` at L742-L748.
 *
 * ⚠️ Refactoring Rationale: a rejection that is NOT an `ApiRequestError` is reported as an authored
 * "that request did not complete" rather than as one of the reference's failure sentences, and this
 * corrects a real misclassification. `ui/src/api/client.ts` normalises EVERY transport failure into
 * `ApiRequestError` before it reaches a screen, so the only rejection that can arrive here as something
 * else is a resource function's own contract check raising `RangeError` -- `requireTransactionAddPreview`
 * enforcing the confirmation token's shape, for instance. That is a SUCCESSFUL exchange whose answer the
 * client refused to interpret. The arm this replaces reported it with `Unable to Add Transaction...` on
 * every non-copy turn, which tells the operator a write failed when the client never got far enough to
 * know whether one happened, and on a turn that made no write at all told them one had failed. Neither
 * of the reference's two sentences is available for that case, because the reference has no such case.
 *
 * Assumptions: a transport failure that carries no problem document IS still reported with the write's
 * own sentence when the turn was writing -- that path is the `context.writing` arm far below, reached
 * only for an `ApiRequestError`, and it is unchanged. The operator's question at that moment is whether
 * the transaction was added, and the reference answers it with exactly that sentence when the write did
 * not succeed for a reason it cannot name.
 * @param {unknown} failure - The rejected promise's reason, which is an `ApiRequestError` for every
 *   transport failure and may be any thrown value otherwise.
 * @param {TransactionAddFailureContext} context - Which submission produced it.
 * @returns {TransactionAddFailureReport} The sentence, the field refusals and the field to focus.
 */
export function screenMessageForFailure(
  failure: unknown,
  context: TransactionAddFailureContext,
): TransactionAddFailureReport {
  const addressedByAccount = context.key === 'accountId';

  if (!isApiRequestError(failure)) {
    /*
     * WHY : Assumptions: the cursor is NOT moved. No field was refused -- the answer was unreadable, not
     *       wrong -- so every field this could move to is an innocent one, and moving to the addressing
     *       key asserts a refusal the service never made. This is the same reasoning the unattributable
     *       400 arm below applies for the same reason.
     */
    return { message: PERSISTENT_FAILURE_REPORT_IT, fieldErrors: [], focus: null };
  }

  const fieldErrors = resolveApiFieldErrors(failure.problem);
  const firstRefusal = fieldErrors[0];
  const focus = firstRefusal === undefined ? context.key : firstRefusal.field;

  if (failure.status === CONFLICT_STATUS) {
    return { message: SHARED_MESSAGES.TRAN_ID_ALREADY_EXIST, fieldErrors, focus };
  }

  if (failure.status === NOT_FOUND_STATUS) {
    /*
     * WHY : Assumptions: an empty ledger and an unknown key are both answered 404, and the copy-last
     *       contract says so explicitly -- it answers that status "for an unknown card or account or for
     *       an empty table with no row to copy". They are told apart by whether the document attributed
     *       the refusal to a field: a refusal naming a key field is a key that does not resolve, and one
     *       naming nothing on a copy is the browse finding no row, which is the reference's
     *       `Transaction ID NOT found...` at `app/cbl/COTRN02C.cbl` L655-L660.
     */
    if (context.copying && firstRefusal === undefined) {
      return { message: SHARED_MESSAGES.TRANSACTION_ID_NOT_FOUND, fieldErrors, focus };
    }
    return {
      message: addressedByAccount
        ? SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND
        : ADD_MESSAGES.CARD_NUMBER_NOT_FOUND,
      fieldErrors,
      focus,
    };
  }

  if (failure.status === BAD_REQUEST_STATUS && firstRefusal !== undefined) {
    /*
     * WHY : Assumptions: the band carries the FIRST refusal's own sentence rather than the document's
     *       summary line, because that is what the reference puts there. Every refusal in the reference
     *       moves its field's sentence into `WS-MESSAGE` and that one string reaches row 23, so the
     *       screen-level line and the field-level line are the same text -- which is also how a
     *       golden-master comparison of the message field reads.
     */
    return { message: firstRefusal.message, fieldErrors, focus };
  }

  if (failure.status === BAD_REQUEST_STATUS) {
    /*
     * WHY : ⚠️ Purpose: this arm handles a refusal the screen cannot ATTRIBUTE, and it is the arm the worst
     *       error-handling outcome of the run came out of. Browser validation answered a submission with
     *       a conforming 400 whose two `fieldErrors` named fields belonging to a different screen. Every
     *       entry was dropped by {@link resolveApiFieldErrors} -- correctly, there is no control to hang
     *       them on -- and what reached the operator was the document's SUMMARY line,
     *       `Please fix the highlighted fields`, over a form with nothing highlighted: no
     *       `ant-form-item-has-error`, no `aria-invalid`, no asterisk, and neither refusal sentence
     *       anywhere in the document. The reason for the rejection was unavailable through any surface.
     * WHY : ⚠️ Refactoring Rationale: the document's own FIELD sentence is preferred over its summary line
     *       whenever the document named a field, and this inversion is the fix. A summary line is written
     *       to be read beside the highlights it refers to; with no highlights it is not merely useless but
     *       false. The field sentence is the one piece of text that states what was actually wrong, and
     *       putting it in the band is the same thing the renderable arm above does -- so the two arms now
     *       agree about where a refusal's own words go, and differ only in whether a control also carries
     *       them.
     * WHY : Assumptions: the cursor is NOT moved when the document named a field the screen cannot render,
     *       because every candidate would be innocent. The measured behaviour moved it to `Enter Acct #`,
     *       which asserts a refusal on a field the service never mentioned.
     * WHY : Assumptions: a 400 that names NO field keeps the document's summary line and keeps moving the
     *       cursor to the addressing key, because that line is then the document's only statement and the
     *       addressing key is the field the turn was about. Nothing about that case is misleading.
     */
    const unattributable = failure.problem.fieldErrors[0];
    if (unattributable !== undefined) {
      return { message: unattributable.message, fieldErrors, focus: null };
    }

    return {
      message: failure.problem.message ?? ADD_MESSAGES.UNABLE_TO_ADD_TRANSACTION,
      fieldErrors,
      focus,
    };
  }

  /*
   * WHY : ⚠️ Refactoring Rationale: the SERVICE'S aggregate sentence is preferred over anything this
   *       screen would choose, and that inversion is what keeps all four of the reference's
   *       unrecoverable-failure sentences reachable. `transaction-api.yaml` documents the 500 on this
   *       operation as carrying "one of `Unable to Add Transaction...` verbatim from
   *       `app/cbl/COTRN02C.cbl` L745 when the write itself failed, `Unable to lookup Acct in XREF AIX
   *       file...` from L600 when the account-id form could not be resolved through the
   *       cross-reference, `Unable to lookup Card # in XREF file...` from L633 when the card-number form
   *       could not be, or `Unable to lookup Transaction...` from L664 and L693" -- so the service knows
   *       WHICH step failed and says so, and the client does not and cannot.
   * WHY : ⚠️ Refactoring Rationale: this replaces a selection by turn kind that had just become
   *       unreachable in one of its arms. The two cross-reference sentences were chosen locally for a
   *       turn that was neither copying nor writing, which was the unconfirmed preview -- and that turn
   *       no longer exists, because a capture is now sent only for a confirming answer. Selecting them
   *       from the document instead makes them reachable again on the turn the reference reaches them
   *       on: the confirming turn, whose `VALIDATE-INPUT-KEY-FIELDS` at L166 performs exactly those two
   *       reads before `ADD-TRANSACTION` at L189.
   * WHY : Assumptions: the fallback is the write's own sentence for a capture and the browse's for a
   *       copy, because those name the operation the turn attempted when the document names nothing.
   *       The operator's question after a confirming turn is whether the transaction was added, and
   *       `Unable to Add Transaction...` is the reference's answer to it when the write did not happen
   *       for a reason it cannot name.
   */
  return {
    message:
      failure.problem.message ??
      (context.copying
        ? SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION
        : ADD_MESSAGES.UNABLE_TO_ADD_TRANSACTION),
    fieldErrors,
    focus,
  };
}

/** The two answers that confirm, both cases, as `app/cbl/COTRN02C.cbl` L170-L171 accepts them. */
const CONFIRMING_ANSWER = /^[Yy]$/u;

/** The two answers that decline, both cases, as `app/cbl/COTRN02C.cbl` L173-L174 accepts them. */
const DECLINING_ANSWER = /^[Nn]$/u;

/**
 * Minimum box the confirmation overlay's two controls occupy.
 *
 * ⚠️ Assumptions: this exists because both labels are a SINGLE character -- `'Y'` and `'N'`, the answers
 * `app/bms/COTRN02.bms` L288-L292 names in its `(Y/N)` domain hint -- and a button sized by a
 * one-character label is the smallest control this tree renders. A runtime sweep measured the previous
 * pair at 28x22 and 26x22 device pixels, below the floor {@link TARGET_SIZE_AA_MINIMUM} records.
 *
 * Assumptions: the floor is the WCAG 2.5.8 AA figure the token bridge carries, not the larger 2.5.5 AAA
 * one; `CONTROL_SCALE_DECISION` in `ui/src/theme/tokens.ts` records why this tree does not adopt AAA.
 */
const CONFIRMATION_CONTROL_STYLE: CSSProperties = {
  minInlineSize: TARGET_SIZE_AA_MINIMUM,
  minBlockSize: TARGET_SIZE_AA_MINIMUM,
};

/**
 * One protected hint the mapset paints beside a field, with the colour role it paints it in.
 *
 * Assumptions: the tone is carried rather than fixed because this screen paints hints in TWO measured
 * colours: the three format hints on row 15 are `COLOR=BLUE` (`app/bms/COTRN02.bms` L208-L222) and the
 * confirmation's domain hint on row 21 is `COLOR=NEUTRAL` (L288-L292). A single hint style would collapse
 * a distinction the mapset makes.
 */
interface FieldHint {
  /** The literal, verbatim from the mapset. */
  readonly text: string;
  /** Measured BMS colour role, resolved to a text-grade token through `BMS_TEXT_COLOR_TOKENS`. */
  readonly tone: keyof typeof BMS_TEXT_COLOR_TOKENS;
}

/** Presentation options a field needs beyond its label, width and value. */
interface FieldPresentation {
  /** Protected format hint the mapset paints beneath the control, when it paints one. */
  readonly hint?: FieldHint;
  /** Whether this is the mapset's single `IC` field, which is the only one that takes initial focus. */
  readonly initialCursor?: boolean;
  /** Whether the control accepts digits only, which selects the numeric soft keyboard. */
  readonly numeric?: boolean;
  /** Whether the value is rendered in the fixed-pitch face so its columns align. */
  readonly fixedPitch?: boolean;
  /**
   * Re-renders the operator's own spelling into the field form the reference holds, on leaving it.
   *
   * ⚠️ Assumptions: only the amount declares one, and it is declared per field rather than applied to
   * every control because only the amount has a canonical form that differs from what a person types.
   * A blanket normaliser would rewrite the eleven-position account identifier the moment the operator
   * tabbed out of a partly-typed one, which is a different and worse defect.
   */
  readonly normalise?: (value: string) => string;
}

/**
 * Renders the transaction add screen.
 *
 * Assumptions: this screen takes no props. Every value it needs is either its own state or comes from a
 * hook -- there is no route parameter, because the reference reaches this screen with nothing selected
 * (`app/cbl/COTRN02C.cbl` L115-L130 opens on an empty map and places the cursor in the account field),
 * and no selection travels in the path the way it does for the card screens.
 *
 * Assumptions: this route is deliberately NOT admin-gated. `app/cpy/COMEN02Y.cpy` L68-L72 carries the
 * text `'Transaction Add (Admin Only)       '` as a COBOL COMMENT, with the asterisk in column 7, and the
 * live value on the next line is `'Transaction Add                    '` with no such suffix; every one
 * of the eleven main-menu options declares `CDEMO-MENU-OPT-USRTYPE PIC X(01) VALUE 'U'`. Adding an
 * administrator check here would therefore withhold from ordinary operators an option the reference menu
 * offers them, on the authority of a line the compiler never read.
 * @returns {ReactElement} The screen: the shared title band, the heading, the message band, the capture
 *   form with its confirmation control, and the function-key legend.
 * @throws {unknown} Nothing is thrown from render. Every submission failure is caught and rendered --
 *   a refused field as a per-control refusal, a rejected request through
 *   {@link screenMessageForFailure}, which covers HTTP 400 for a field the service refused, 404 for a
 *   key that does not resolve or an empty ledger with no row to copy, 409 for an identifier already
 *   present, and any other status or a transport failure as the reference's own unnameable-failure
 *   sentence.
 */
export function TransactionAddScreen(): ReactElement {
  const navigate = useNavigate();
  /*
   * WHY : Assumptions: the instant is read from a SERVER-anchored hook and not from `dayjs()`. The
   *       reference re-reads one region clock on every `SEND MAP` -- `POPULATE-HEADER-INFO` runs
   *       `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA` at `app/cbl/COTRN02C.cbl` L554 -- so every
   *       terminal of a region saw the same wall clock. Falling through to the browser's clock would
   *       substitute both the instant and the zone, and two operators looking at one capture could read
   *       two different dates across midnight.
   */
  const paintedAt = useServerInstant();
  /*
   * WHY : Alternatives Considered: the `token` member of this same hook, which returns RESOLVED values --
   *       `colorInfo` comes back as a literal hex string. Rejected because writing a resolved value into
   *       a `style` attribute copies today's palette into the element and takes it off the CSS-variable
   *       surface antd 6 themes through, so a later token change leaves this one screen behind with
   *       nothing failing to say so. `cssVar` returns the `var(--...)` reference form of the same names.
   *       Assumptions: the names themselves come from `ui/src/theme/tokens.ts`, so no colour, spacing or
   *       font value is written in this file, and no `ConfigProvider` is instantiated here -- the theme is
   *       injected once by `ui/src/App.tsx` and this hook reads it.
   */
  /*
   * WHY : Assumptions: the RESOLVED token record is read alongside the variable-reference record, and it
   *       is read for exactly one value -- the grid's gutter, which is a component prop the design system
   *       divides by two itself rather than a style attribute. Every colour, face and size on this screen
   *       still goes through `cssVar`, so nothing about today's scale is baked into an element.
   */
  const { cssVar, token } = theme.useToken();

  const [values, setValues] = useState<TransactionAddValues>(BLANK_VALUES);
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');
  const [fieldErrors, setFieldErrors] = useState<readonly TransactionAddFieldError[]>([]);
  /*
   * WHY : Purpose: what the SERVICE resolved on the last turn -- the account identifier, the MASKED card
   *       number and the opaque binding token -- held so the confirmation surface can describe the record
   *       being committed rather than merely ask about it, and so the confirming turn can name the same
   *       card. The reference's confirming turn redisplays the whole populated map -- L176 to L181 moves
   *       `Confirm to add this transaction...` and performs `SEND-TRNADD-SCREEN` after
   *       `VALIDATE-INPUT-KEY-FIELDS` has already overwritten both key fields -- so an operator
   *       confirming can see the resolved pair. A surface that showed only a question would take that
   *       away.
   * WHY : ⚠️ Assumptions: this is held SEPARATELY from `values`, and the separation carries more weight
   *       now that the card control is NOT repainted from the answer. The controls are editable, so their
   *       contents state what WOULD be sent; this states what the service actually resolved, and it is
   *       cleared the moment either key is edited. Reading the summary off the controls instead would
   *       describe an unresolved pair with the authority of a service answer -- and for the card it would
   *       describe the operator's own entry, which the account arm discards at L209.
   * WHY : Alternatives Considered: holding the whole resolved capture -- the ten data values as well as
   *       the pair -- which an earlier shape did. Withdrawn as duplication: the ten values are painted
   *       into the fourteen controls, so the screen already holds them, and a second copy could disagree
   *       with what the operator is looking at.
   */
  const [resolvedKeys, setResolvedKeys] = useState<ResolvedKeys | null>(null);
  const [busy, setBusy] = useState(false);
  /*
   * WHY : Purpose: whether the confirmation overlay is raised. It is a SEPARATE flag from `busy` because
   *       the two describe different states: `busy` means a request is in flight and the keyboard is
   *       locked, while this means the screen has resolved the record and is waiting for an answer with no
   *       request outstanding at all.
   * WHY : ⚠️ Assumptions: this flag is raised only from a POINTER, never from a settled turn, and it is the
   *       only surface that can produce a confirming answer by mouse.
   *       Runtime validation found the previous shape submitting a turn from the DECLINING control -- a
   *       `POST /api/v1/transactions` carrying `"confirmation":"N"` -- so an operator who cancelled was
   *       told the add had failed when they had in fact prevented it. The oracle is unambiguous that a
   *       decline has no wire turn: `app/cbl/COTRN02C.cbl` L176-L181 answers `N`, `SPACES` and
   *       `LOW-VALUES` by moving `Confirm to add this transaction...` into the message and re-sending the
   *       map, and only L189-L191's `Y`/`y` arm performs `ADD-TRANSACTION`. Holding the answer in a flag
   *       here, rather than in the confirmation FIELD, is what keeps a decline from stamping a literal
   *       `'N'` into a control the operator never typed into.
   */
  const [confirming, setConfirming] = useState(false);

  /*
   * WHY : ⚠️ Refactoring Rationale: there is NO "a copy is pending" flag beside `busy` any more, and the
   *       flag this block replaces was the mechanism of a reported write hazard rather than a piece of
   *       book-keeping. It recorded that the last submission had been a copy so that the CONFIRMING turn
   *       could be routed back through the copy operation -- which re-resolves "the most recently stored
   *       transaction", so a row appended between the operator's preview and their confirmation silently
   *       replaced what they had been shown and was written instead. The reasoning that stood here was
   *       that the copy and the confirmation are "two turns of one action", which is true of the reference
   *       and does not imply a second read: `COPY-LAST-TRAN-DATA` at `app/cbl/COTRN02C.cbl` L480 to L493
   *       moves the eleven values into the operator's own unprotected MAP FIELDS and L495 then performs
   *       `PROCESS-ENTER-KEY`, so the row is read exactly once and every later turn writes what is on the
   *       glass. The copy operation now publishes those eleven values, this screen adopts them into its
   *       own fields, and the confirming turn goes through the ordinary capture operation -- which is the
   *       same one read, in the same place, as the reference performs. The withdrawn flag also carried a
   *       defect no test caught: typing the confirmation cleared it, so the confirming turn submitted the
   *       PRE-COPY values.
   *       Trade-offs: the earlier arrangement's one genuine merit is preserved without the flag. It
   *       cleared itself on any field edit so that an edited screen was written rather than re-copied;
   *       here every turn after a copy writes the screen, edited or not, so there is nothing to clear and
   *       no state left that can disagree with the controls the operator is looking at.
   */

  /*
   * WHY : Refactoring Rationale: the "a turn is already running" guard is this REF, not the `busy` state
   *       member above -- the state member is what the controls and the key bindings RENDER from, and the
   *       ref is what the guard READS. State alone cannot serialise turns: every handler closes over the
   *       `busy` value of the render it was created in, so two key presses arriving before React commits
   *       the next render both read `false` and both submit -- writing two transactions where the operator
   *       asked for one, which is precisely what the guard exists to prevent. A ref is mutated
   *       synchronously and read through the same object by every closure, so the second press sees the
   *       first.
   *       Trade-offs: two members now describe one condition and must be set together, which is the price
   *       of a guard that holds across a render boundary; keeping the state member alone would re-render
   *       correctly and admit the double write, and keeping the ref alone would guard correctly and leave
   *       the controls enabled while the turn ran.
   */
  const inFlight = useRef(false);

  /*
   * WHY : ⚠️ Purpose: the element the asking surface is ANCHORED to, held so the surface can be brought
   *       onto the display before it is raised. The overlay is positioned against this control, and the
   *       control sits at the very foot of a form long enough to overflow the screen body -- so on a turn
   *       taken from the function-key legend, which needs no pointer and therefore no scrolling, the
   *       anchor is routinely outside the visible region when the question is asked.
   *       Refactoring Rationale: a browser pass measured the consequence precisely. Taking the turn from
   *       the legend with the body unscrolled put the anchor at `top: 934.67` in a 900-pixel display, and
   *       the overlay -- which the design system flips above an anchor it cannot fit below -- landed at
   *       `bottom: 923`, twenty-three pixels past the foot of the display, with the lower eleven pixels of
   *       BOTH answers cut off. They stayed clickable by a single pixel, which is the sort of margin that
   *       is a defect rather than a near miss. The same overlay raised while the anchor was in view
   *       measured `bottom: 892`, fully inside, so the anchor's position is the whole of it.
   *       Alternatives Considered: (1) constraining the overlay to the display instead of moving the
   *       anchor. The design system already does that -- `autoAdjustOverflow` is what flipped it above the
   *       anchor in the first place -- and it cannot help, because an anchor BELOW the display has no
   *       side that is inside it. (2) Rendering the overlay against the screen body rather than the
   *       document, so the frame's own clipping would contain it. Rejected: the frame clips with
   *       `overflow: hidden`, so a contained overlay would be cut off rather than repositioned, which
   *       trades a partly visible question for an invisible one. (3) Anchoring the question to the legend
   *       control instead, which is always in view. Rejected because the legend is not where the question
   *       belongs -- the surface names the record the in-content control commits, and the reference asks
   *       for the confirmation in the form's own confirmation field, next to that control.
   */
  const confirmationAnchor = useRef<HTMLButtonElement | null>(null);

  /*
   * WHY : Assumptions: one ref object holding a control per field, rather than fourteen separate refs.
   *       Focus is the browser analogue of the reference's `MOVE -1 TO <field>L`, which appears at
   *       fifteen sites in `app/cbl/COTRN02C.cbl` and can name any of the fields, so the destination is
   *       data rather than a fixed choice -- a lookup keyed by field name is what lets
   *       {@link screenMessageForFailure} return a field and have the cursor follow it.
   */
  const controls = useRef<Partial<Record<TransactionAddField, InputRef | null>>>({});

  /*
   * WHY : Assumptions: control identifiers are derived from a per-instance value rather than written as
   *       constants, because the identifier's only job is to bind a label to its control and a constant
   *       would collide if the shell ever rendered this screen twice -- behind an overlay, for instance --
   *       leaving the duplicate label pointing at whichever control appeared first in the document.
   */
  const idPrefix = useId();

  /*
   * WHY : Refactoring Rationale: the cursor destination is held as STATE and applied by the effect
   *       below, rather than by calling `.focus()` at the point the refusal is decided. Calling it
   *       inline is the obvious shape and it silently loses the cursor twice over, both times because
   *       the call runs before React has committed the very render that the refusal causes.
   *       First: the blank marker is an antd Input `suffix`, and adding one changes the control's root
   *       element from a bare `input` to a `span.ant-input-affix-wrapper` that wraps a NEW `input`. The
   *       old node is unmounted, so a focus applied to it before the commit is discarded and the cursor
   *       falls back to `body`. That is invisible whenever the refused field is the account field --
   *       which carries `autoFocus` and is therefore re-focused on remount -- and shows up the moment a
   *       refusal moves to any OTHER field, which is exactly the case the reference's fifteen
   *       `MOVE -1 TO <field>L` sites exist to serve.
   *       Second: every control is `disabled` while a turn is in flight, and a submission that ends in
   *       a refusal clears that flag and names the field in the same handler. React batches the two, so
   *       an inline call would run while the element is still disabled, and a disabled element cannot
   *       take focus -- the browser refuses silently, with no error to notice.
   *       Trade-offs: this costs one extra render per cursor move, which is the price of the cursor
   *       actually landing where the reference puts it. Alternatives Considered: a `setTimeout` or
   *       `requestAnimationFrame` would also outlive the commit, but both leave the cursor position
   *       depending on a timer rather than on the render that caused it, so a slow commit would
   *       reintroduce the same bug non-deterministically.
   */
  const [pendingFocus, setPendingFocus] = useState<TransactionAddField | null>(null);

  /**
   * Requests the cursor move that mirrors the reference's symbolic cursor placement.
   *
   * Assumptions: the request is recorded rather than performed, because the control that must receive
   * the cursor may not exist yet -- see the block above. `app/cbl/COTRN02C.cbl` sets `MOVE -1 TO
   * <field>L` and only then sends the map, so the reference likewise decides the destination before the
   * screen carrying it is painted.
   * @param {TransactionAddField} field - Field the refusal names.
   * @returns {void} Completion is the recorded request; the effect below applies it after the commit.
   */
  function focusField(field: TransactionAddField): void {
    setPendingFocus(field);
  }

  useEffect(
    /**
     * Applies a recorded cursor move once the render that caused it has been committed.
     * @returns {void} Completion is the focused control; a field not currently rendered is a no-op,
     *   and the request is cleared either way so it can never be replayed on a later render.
     */
    function applyPendingFocus(): void {
      if (pendingFocus === null) {
        return;
      }
      controls.current[pendingFocus]?.focus();
      setPendingFocus(null);
    },
    [pendingFocus],
  );

  /**
   * Publishes one refused field as the reference would: one sentence, one marker and one cursor move.
   * @param {TransactionAddFieldError} failure - The refusal to render.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function reportFieldFailure(failure: TransactionAddFieldError): void {
    setMessage(failure.message);
    setSeverity('error');
    setFieldErrors([failure]);
    focusField(failure.field);
  }

  /**
   * Returns the screen to the state `INITIALIZE-ALL-FIELDS` leaves it in.
   *
   * Assumptions: this clears the message as well as the fields, because L779 lists `WS-MESSAGE` among the
   * items it moves spaces into, and it moves the cursor to the account field per L764.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function clearScreen(): void {
    setValues(BLANK_VALUES);
    setResolvedKeys(null);
    setMessage(null);
    setSeverity('error');
    setFieldErrors([]);
    focusField('accountId');
  }

  /**
   * Reads the confirmation character and reports what the reference does with it.
   *
   * Assumptions: a DECLINING answer and a BLANK one produce the SAME sentence, and that grouping is the
   * reference's own -- `app/cbl/COTRN02C.cbl` L173-L176 puts `'N'`, `'n'`, `SPACES` and `LOW-VALUES` in
   * one arm reached by fall-through, so all four ask for another turn with
   * `Confirm to add this transaction...`. Only a value that is none of those six spellings reaches the
   * `WHEN OTHER` arm at L182 and earns `Invalid value. Valid values are (Y/N)...`, which is why declining
   * cannot be reported as an invalid value.
   * @param {string} answer - The confirmation character as submitted.
   * @returns {string} The verbatim sentence for a non-confirming answer.
   */
  function unconfirmedSentence(answer: string): string {
    const keyed = answer.trim();
    return keyed === '' || DECLINING_ANSWER.test(keyed)
      ? ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION
      : SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N;
  }

  /**
   * Applies the reference's two validation paragraphs, in its order, and reports the first refusal.
   *
   * ⚠️ Purpose: `PROCESS-ENTER-KEY` performs `VALIDATE-INPUT-KEY-FIELDS` at `app/cbl/COTRN02C.cbl` L166
   * and `VALIDATE-INPUT-DATA-FIELDS` at L167, and evaluates `CONFIRMI` only at L169. Each paragraph ends
   * a refused turn with `SEND-TRNADD-SCREEN`, whose last statement is `EXEC CICS RETURN`, so the
   * reference cannot reach ANY confirmation arm -- affirming, declining or otherwise -- while a field is
   * still refusable. Running both edits ahead of every answer is what reproduces that.
   *
   * ⚠️ Refactoring Rationale: both submitting surfaces call this rather than each carrying a copy of the
   * pair, which is what makes "one gate" a property of the code rather than of a convention. A runtime
   * sweep measured what two copies produce: the pointer surface opened over a form nothing had checked,
   * took a confirmation, and only then reported the refusal -- so an operator confirmed a submission the
   * screen already knew would be refused.
   *
   * Assumptions: both functions are pure over the values they are handed, so honouring the reference's
   * order costs no request and this can be called from a pointer handler as cheaply as from a keystroke.
   * @returns {TransactionAddFieldError | null} The first refusal the reference would report, or `null`
   *   when the screen is submittable.
   */
  function localRefusal(): TransactionAddFieldError | null {
    const current = canonicalValues();
    const keyed = keyFieldFailure(current);
    if ('failure' in keyed) {
      return keyed.failure;
    }
    return dataFieldFailure(current);
  }

  /**
   * The screen's values with the amount rendered into the reference's field form.
   *
   * ⚠️ Purpose: the blur normaliser alone is not enough, and this is the gap it leaves. Pressing Enter
   * with the caret still in the amount does not blur the control -- a browser fires no `blur` for a key
   * press -- so an operator who types `100.00` and reaches straight for Enter would have been refused by
   * a screen that had already been taught to accept that spelling. Every gate reads the values through
   * here instead, so the two routes cannot disagree about what an amount means.
   *
   * Assumptions: the SAME object is returned when nothing changed, so a canonical or blank amount adds no
   * allocation and no state write on the overwhelmingly common turn.
   *
   * Assumptions: only the amount is canonicalised, because it is the only field whose stored form differs
   * from what an operator types; the two key fields are zero-filled by {@link toZeroFilledKey} inside
   * `keyFieldFailure`, where the reference does it, rather than here.
   * @returns {TransactionAddValues} The values a gate should validate and dispatch.
   */
  function canonicalValues(): TransactionAddValues {
    const canonical = canonicaliseKeyedAmount(values.amount);
    return canonical === values.amount ? values : { ...values, amount: canonical };
  }

  /**
   * Prepares the CAPTURE submission, or reports the refusal that stops it.
   *
   * Assumptions: the data-field chain runs here and nowhere else, so it applies to a capture and not to a
   * copy. The reference validates the data fields at `app/cbl/COTRN02C.cbl` L167, which the copy path
   * reaches only through its re-entry at L495 -- that is, after L481 to L492 have filled them.
   *
   * Assumptions: the chain reads the SUBMITTED values rather than the component's `values`, for the reason
   * {@link runTurn} records for the same choice -- {@link submitTurn} composes the answer into the values
   * and submits in one task, where state set in that task is not yet readable.
   *
   * ⚠️ Assumptions: the binding token is read from the component's `resolvedKeys` and NOT from the
   * submitted values, and that is safe where reading `values` would not be. The pair is set by a PREVIOUS
   * turn's completion, so the render this handler closed over already holds it, and it is discarded the
   * moment either key field is edited -- so a token can only be sent alongside the keys it was resolved
   * from.
   * @param {'accountId' | 'cardNumber'} key - Which key field addresses the submission.
   * @param {string} answer - The confirmation character as keyed.
   * @param {TransactionAddValues} submitted - The values this turn validates and sends.
   * @returns {(() => Promise<TransactionAddOutcome>) | null} A thunk that dispatches the capture, or
   *   `null` when a field was refused and this turn is over.
   */
  function captureDispatch(
    key: 'accountId' | 'cardNumber',
    answer: string,
    submitted: TransactionAddValues,
  ): (() => Promise<TransactionAddOutcome>) | null {
    const refusal = dataFieldFailure(submitted);
    if (refusal !== null) {
      reportFieldFailure(refusal);
      return null;
    }

    const request = buildCreateRequest(
      submitted,
      key,
      answer,
      resolvedKeys?.confirmationToken ?? null,
    );
    if (request === null) {
      /*
       * WHY : Assumptions: unreachable in practice and handled anyway. `dataFieldFailure` has already
       *       accepted the amount's shape, so the wire conversion cannot fail -- but the two checks live
       *       in different functions, and a total handler here means a future change to either one
       *       surfaces the reference's own format sentence instead of dispatching a request with no body.
       */
      reportFieldFailure({
        field: 'amount',
        message: ADD_MESSAGES.AMOUNT_SHOULD_BE_IN_FORMAT_99999999_99,
        state: 'NOT_OK',
      });
      return null;
    }

    /**
     * Dispatches the capture with the body built above.
     * @returns {Promise<TransactionAddOutcome>} The service's outcome for this turn.
     */
    return (): Promise<TransactionAddOutcome> => addTransaction(request);
  }

  /**
   * Prepares the COPY submission, which needs only the key this turn already validated.
   *
   * Assumptions: nothing can refuse this turn locally beyond the key chain the caller has already run, so
   * the return type carries no null arm of its own -- the shape it shares with {@link captureDispatch} is
   * what lets one caller treat the two alike.
   * @param {'accountId' | 'cardNumber'} key - Which key field addresses the submission.
   * @param {string} answer - The confirmation character as keyed, which decides copy-only against
   *   copy-and-write in this same turn.
   * @param {TransactionAddValues} submitted - The values whose key field addresses the copy.
   * @returns {() => Promise<TransactionAddOutcome>} A thunk that dispatches the copy.
   */
  function copyDispatch(
    key: 'accountId' | 'cardNumber',
    answer: string,
    submitted: TransactionAddValues,
  ): () => Promise<TransactionAddOutcome> {
    const request = buildCopyRequest(submitted, key, answer);

    /**
     * Dispatches the copy with the body built above.
     * @returns {Promise<TransactionAddOutcome>} The service's outcome for this turn.
     */
    return (): Promise<TransactionAddOutcome> => copyLastTransaction(request);
  }

  /*
   * WHY : Assumptions: the whole chain runs BEFORE the confirmation character is read, on every turn,
   *       including the turn that only declines. `PROCESS-ENTER-KEY` performs
   *       `VALIDATE-INPUT-KEY-FIELDS` and `VALIDATE-INPUT-DATA-FIELDS` at `app/cbl/COTRN02C.cbl`
   *       L166-L167 and evaluates `CONFIRMI` at L169 only afterwards, so a form with a blank type code
   *       and a blank confirmation reports the type code and never mentions the confirmation.
   * WHY : ⚠️ Refactoring Rationale: the unconfirmed turn is ANSWERED LOCALLY and is no longer sent. It
   *       used to be, defended on the ground that two of the reference's steps live on it and neither can
   *       run in a browser -- the amount's `+99999999.99` re-render (L383-L386) and each date's
   *       `CSUTLDTC` evaluation (L389-L427). Both run here now, in {@link canonicaliseKeyedAmount} and
   *       {@link namesARealCalendarDate}, so the operator sees the normalised amount and an unreal date
   *       such as `2024-02-31` is refused BEFORE the confirmation is asked for, which is where L389-L427
   *       refuses it. What sending it cost was measurable and is gone: a filled form with a blank
   *       confirmation put a `confirmation`-less body on `POST /api/v1/transactions` before any
   *       confirmation surface had ever been visible.
   * WHY : ⚠️ Trade-offs: the two verdicts a browser genuinely cannot reach -- whether the key pair
   *       resolves against the cross-reference, and whether a real date falls outside the utility's
   *       supported range, `2513` at `app/cbl/CSUTLDTC.cbl` L137-L138 -- now arrive on the CONFIRMING
   *       turn rather than on the turn before it. The reference reports both at L166 and L389-L427,
   *       ahead of the confirmation, so an operator keying an unresolvable account is asked to confirm
   *       and only then told the account does not exist. That is a one-turn ordering difference with
   *       nothing written either way, and it is the price of not inventing a wire turn: no read
   *       operation in this tree resolves the pair AND mints the confirmation token, and
   *       `transaction-api.yaml` L470-L474 states that adding one "would invent an endpoint the baseline
   *       does not have".
   */

  /**
   * Marks a turn as begun, reporting whether it may proceed.
   *
   * Assumptions: the REF is the gate and the state member only follows it, for the reason the ref's own
   * declaration records: a handler closes over the `busy` of its own render, so two presses arriving in
   * one render both see `false`. Reading and setting the ref in one function is what makes the check and
   * the claim inseparable.
   * @returns {boolean} `true` when this turn owns the screen, `false` when one is already in flight.
   */
  function beginTurn(): boolean {
    if (inFlight.current) {
      return false;
    }
    inFlight.current = true;
    setBusy(true);
    return true;
  }

  /**
   * Marks the in-flight turn as settled, releasing the screen.
   *
   * Assumptions: the ref is cleared SYNCHRONOUSLY, which is what lets the copy key press continue into
   * its confirmation turn within the same task. Nothing can interleave between the two, because a single
   * task runs to completion, so releasing and re-taking the gate cannot admit a second operator press.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function endTurn(): void {
    inFlight.current = false;
    setBusy(false);
  }

  /**
   * Runs one turn: the validation chain, then the submission, then the confirmation evaluation.
   *
   * ⚠️ Refactoring Rationale: `copying` selects the copy-last operation, and the chain that runs before it
   * is now the KEY chain alone. The note this replaces recorded the full chain running for a copy too, as
   * "a documented divergence rather than an oversight", on the ground that the copy operation was declared
   * over the capture's request schema with all eleven data members required. That was a description of a
   * defect: the reference validates the key fields at L473 and then FILLS the eleven fields at L481 to
   * L492, so requiring them on the way in made the copy key unusable -- pressing it on a blank screen was
   * refused with the first blank field's own sentence and sent no request at all. The operation now has a
   * request shape of its own carrying the key and the confirmation, which is what the reference reads.
   *
   * Assumptions: `copying` is true for the PF5 arm ALONE and for no turn that follows it. A copy turn's
   * withheld answer carries the eleven values it copied, this screen adopts them into its own fields, and
   * every turn afterwards is an ordinary capture of what is on the screen -- so "the most recently stored
   * transaction" is resolved exactly once per action, as it is at L475 to L478. See the state declaration
   * above for the hazard that a second resolution produced.
   *
   * ⚠️ Assumptions: the values to submit are a PARAMETER and not read from state, and that is what keeps
   * every confirmation surface submitting the same turn. {@link submitTurn} records the answer in the
   * confirmation field and submits in one task, and React state set in a task is not readable in it, so a
   * turn reading `values` would submit the PREVIOUS answer -- which is the divergence between the overlay's
   * path and the Enter path that one dispatcher exists to remove.
   * @param {string} answer - The confirmation character to submit, which decides preview against write.
   * @param {TransactionAddValues} submitted - The values this turn validates and submits, which carry the
   *   answer this turn is giving rather than the one the previous turn gave.
   * @param {boolean} copying - Whether to submit the copy-last operation instead of the capture.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function runTurn(answer: string, submitted: TransactionAddValues, copying: boolean): void {
    /*
     * WHY : Assumptions: a turn arriving while one is in flight is dropped. A terminal turn is serialised
     *       by the hardware -- a 3270 keyboard locks until the region replies -- so the reference needs no
     *       such guard, and without one here a doubled Enter could submit the same confirmed capture
     *       twice and write two transactions where the operator asked for one.
     */
    if (inFlight.current) {
      return;
    }

    const keyed = keyFieldFailure(submitted);
    if ('failure' in keyed) {
      reportFieldFailure(keyed.failure);
      return;
    }

    /*
     * WHY : ⚠️ Refactoring Rationale: the data-field chain runs for a CAPTURE and is skipped for a COPY,
     *       and running it for both was what made the copy key unusable. The reference validates the data
     *       fields at `app/cbl/COTRN02C.cbl` L167, which the copy path reaches only through the re-entry at
     *       L495 -- that is, AFTER L481 to L492 have filled them. Running the chain before the copy
     *       therefore validated fields the action was about to supply, so pressing the key on a blank
     *       screen was refused with `Type CD can NOT be empty...` and no request was ever sent.
     * WHY : Assumptions: the KEY chain still runs for both, because the reference runs it for both -- L473
     *       performs `VALIDATE-INPUT-KEY-FIELDS` for the copy arm and L166 for the Enter arm -- so a copy
     *       with neither key filled in is refused here rather than at the service.
     * WHY : Assumptions: the copied values are validated on the turn that follows, not on this one, which
     *       is the reference's own sequence: the copy fills the fields and the re-entry at L495 then puts
     *       them through the whole chain, so a copied row carrying a value this screen would refuse is
     *       reported on the confirming turn exactly as the reference reports it.
     */
    const dispatch = copying
      ? copyDispatch(keyed.key, answer, submitted)
      : captureDispatch(keyed.key, answer, submitted);
    if (dispatch === null) {
      return;
    }

    const writing = CONFIRMING_ANSWER.test(answer.trim());
    beginTurn();
    setMessage(null);
    setFieldErrors([]);

    dispatch().then(
      /**
       * Renders the outcome: the written capture's acknowledgement, or the normalised preview.
       * @param {TransactionAddOutcome} outcome - Which of the two outcomes the service reported.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (outcome: TransactionAddOutcome): void => {
        endTurn();

        if (outcome.outcome === 'CREATED') {
          /*
           * WHY : Assumptions: the fields are cleared BEFORE the acknowledgement is composed, and the
           *       order is the reference's -- `WRITE-TRANSACT-FILE` performs `INITIALIZE-ALL-FIELDS` at
           *       `app/cbl/COTRN02C.cbl` L725 and only then builds the sentence at L728-L733, so the
           *       operator is left on an empty form with the identifier of what they just wrote.
           * WHY : Assumptions: the severity is `success` and not the band's default. L727 moves `DFHGREEN`
           *       into the message field's colour attribute, which is the only place in this program that
           *       overrides the field's declared `COLOR=RED`, so this one sentence is green and every
           *       other sentence this screen shows is red.
           * WHY : Assumptions: the sentence is composed by the catalog template and not by string
           *       concatenation here. Its two literals are `'Transaction added successfully. '` and
           *       `' Your Tran ID is '` -- the first ends with a space and the second begins with one, so
           *       the rendered text carries TWO spaces between `successfully.` and `Your`. The template
           *       holds both literals as the `STRING` statement at L728-L732 declares them, and building
           *       the sentence here would be the one place that doubled space could be silently
           *       normalised away.
           */
          setValues(BLANK_VALUES);
          setResolvedKeys(null);
          setFieldErrors([]);
          setMessage(
            formatMessageTemplate(MESSAGE_TEMPLATES.TRANSACTION_ADDED_SUCCESSFULLY, {
              'TRAN-ID': outcome.created.transactionId,
            }),
          );
          setSeverity('success');
          focusField('accountId');
          return;
        }

        /*
         * WHY : Assumptions: the amount is written back from the PREVIEW and re-rendered through the
         *       screen's own mask, because the two forms differ. The service answers with its `Money`
         *       form -- unsigned when positive and never zero-filled -- while the field displays
         *       `+99999999.99`, so adopting the response verbatim would replace a twelve-character
         *       display value with a shorter one the field's own predicate would then refuse.
         * WHY : ⚠️ Refactoring Rationale: on a COPY turn the other TEN copied values are adopted too, and
         *       adopting the amount alone is the defect this closes. `COPY-LAST-TRAN-DATA` moves eleven
         *       values into the operator's own map fields at `app/cbl/COTRN02C.cbl` L481 to L492, so after
         *       a copy the reference's screen holds the copied record in full; a screen holding one of the
         *       eleven showed the operator ten stale fields beside one copied figure, and then had to
         *       re-copy on the confirming turn to write anything faithful -- which re-resolved which row
         *       is last. The values are adopted here, in the one place that receives them, and the
         *       confirming turn writes them.
         * WHY : Assumptions: the CONFIRMATION is left exactly as the operator left it, because the
         *       published shape carries no such member and the copy block moves nothing into it. The
         *       ACCOUNT key field is repainted from the resolved pair, for the reason
         *       {@link paintCopiedValues} records: the copy paragraph performs
         *       `VALIDATE-INPUT-KEY-FIELDS` at L473 before it reads, and that paragraph writes both key
         *       fields from the cross-reference. A copy therefore still lands against the account the
         *       operator was already working on -- the resolved account IS that account. The card control
         *       is NOT repainted, because the resolved card now arrives masked and a masked rendering is
         *       not a value this screen may submit; it is shown as protected text beside the form.
         * WHY : Assumptions: the copied row's own identifier is validated on arrival by
         *       `ui/src/api/transactions.ts` and deliberately NOT retained here. No field of this mapset
         *       renders it -- the reference paints no such field, and inventing one would put text on this
         *       screen the reference never publishes -- and the property it establishes, that one stored
         *       row was copied and that the same row is the one being written, is established by these
         *       values having been adopted from it.
         */
        const normalised = toEditMaskAmount(outcome.preview.amount);
        const copied = outcome.preview.copied;
        /*
         * WHY : ⚠️ Assumptions: the pair is recorded on EVERY withheld answer and not on a copy turn
         *       alone, because the service resolves it on every turn -- `app/cbl/COTRN02C.cbl` L166
         *       performs `VALIDATE-INPUT-KEY-FIELDS` for the Enter arm as L473 does for the copy arm --
         *       and the reference repaints both key fields each time before re-sending the screen.
         */
        /*
         * WHY : ⚠️ Refactoring Rationale: the card half is adopted as the service's MASKED rendering and
         *       is accompanied by the opaque token that names the resolved card on the confirming turn.
         *       The member read here was the full sixteen-digit number, which meant an ordinary
         *       cardholder's primary account number reached the browser -- and stayed in component state
         *       for as long as the screen lived -- on a route that is not the administrative card
         *       endpoint. AAP §0.4.1.9 masks the number everywhere except that endpoint, and the
         *       contract's own `CardNumber` schema says responses carry the masked form. Nothing on this
         *       screen needed the digits: the summary renders them masked, and the confirming turn now
         *       proves it is committing the same card by returning the token rather than by re-sending a
         *       number the browser was trusted to keep.
         */
        const resolved: ResolvedKeys = {
          accountId: outcome.preview.resolvedAccountId,
          cardNumberMasked: outcome.preview.resolvedCardNumberMasked,
          confirmationToken: outcome.preview.confirmationToken,
        };
        setResolvedKeys(resolved);
        setValues(
          /**
           * Adopts the copied record, and the canonical amount, leaving the keys and answer as keyed.
           *
           * Assumptions: the eleven moves are expressed by {@link paintCopiedValues} rather than inline
           * here, so the one function that states which fields the copy block reaches is also the one a
           * test can exercise without rendering the screen.
           * @param {TransactionAddValues} previous - Values as they stand.
           * @returns {TransactionAddValues} The same values carrying whatever this turn supplied.
           */
          (previous: TransactionAddValues): TransactionAddValues =>
            paintCopiedValues(previous, copied, resolved, normalised),
        );

        if (writing) {
          /*
           * WHY : Assumptions: a confirming answer that came back unwritten is reported as a failed
           *       write rather than read as a preview. The contract makes 201 the written outcome and 200
           *       the unwritten one, so this combination contradicts it, and falling through to the
           *       confirmation evaluation below would tell an operator who answered `Y` that their answer
           *       was invalid. `Unable to Add Transaction...` is the reference's own sentence for a write
           *       that did not happen for a reason it cannot name.
           */
          setSeverity('error');
          setMessage(ADD_MESSAGES.UNABLE_TO_ADD_TRANSACTION);
          focusField('accountId');
          return;
        }

        setSeverity('error');
        setMessage(unconfirmedSentence(answer));
        focusField('confirmation');
      },
      /**
       * Renders a rejected submission as the reference's own sentence for that failure site.
       * @param {unknown} failure - The rejection reason, an `ApiRequestError` for a transport failure.
       * @returns {void} Completion is represented by the screen's own state.
       */
      (failure: unknown): void => {
        endTurn();
        /*
         * WHY : ⚠️ Assumptions: `copying` is passed THROUGH rather than fixed false, and which of the two
         *       it is decides which sentences the context may select. A copy turn's work includes the
         *       browse of the row to copy, so `Unable to lookup Transaction...` from L664 and L693 and
         *       `Transaction ID NOT found...` from L655 to L660 are the reference's own words for a
         *       failure on that turn; a capture turn makes no such read, so naming them there would
         *       attribute a failed capture to a read the turn never made. Fixing the flag false was
         *       correct only while the copy ran through a function of its own.
         */
        const report = screenMessageForFailure(failure, {
          key: keyed.key,
          copying,
          writing,
        });
        setSeverity('error');
        setMessage(report.message);
        setFieldErrors(report.fieldErrors);
        /*
         * WHY : Assumptions: a `null` field leaves the cursor where the operator put it. See the member's
         *       own note: the only failure that produces one is a refusal naming fields this screen does
         *       not render, and every field it could move to would be an innocent one.
         */
        if (report.focus !== null) {
          focusField(report.focus);
        }
      },
    );
  }

  /**
   * The ONE dispatcher every confirmation surface goes through.
   *
   * ⚠️ Purpose: physical Enter, the legend's ENTER control and the overlay's confirming control all reach
   * this and
   * nothing else, so no surface can submit a different turn from another. They previously did not: the
   * overlay recorded its answer and then submitted, while Enter submitted the confirmation field as it
   * stood, and the two consequently disagreed about which values were in play -- typing `Y` into the
   * field cleared the copy state that the overlay's path preserved, so the same answer given two ways
   * produced two different submissions. One function is what makes that class of divergence impossible
   * rather than merely absent.
   *
   * Assumptions: the answer is recorded in the confirmation FIELD as well as submitted, so the screen
   * shows what was sent. For physical Enter the recorded value is the one already there, which makes the
   * write a no-op rather than a special case -- and a special case is what the two paths used to be.
   *
   * Assumptions: the submitted values are composed here rather than read back from state, because state
   * set in this task is not readable in it. Composing them is also what keeps the recorded answer and the
   * submitted answer the same character; reading state would submit the previous one.
   * @param {string} answer - The confirmation character to submit: the field's own value for Enter, or
   *   the character the overlay's confirming control stands for.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function submitTurn(answer: string): void {
    const submitted: TransactionAddValues = { ...canonicalValues(), confirmation: answer };
    setValues(submitted);
    /*
     * WHY : Assumptions: the operation is the CAPTURE and never the copy, whichever turn preceded this
     *       one. A copy has already put its ten values and its amount on this screen, so confirming
     *       writes what the operator is looking at; reaching the copy operation again would re-resolve
     *       which row is last and could write a row the operator never saw.
     */
    runTurn(answer, submitted, false);
  }

  /**
   * Runs the copy key press: the reference's `COPY-LAST-TRAN-DATA`, as one turn.
   *
   * ⚠️ Purpose: this is `COPY-LAST-TRAN-DATA` at `app/cbl/COTRN02C.cbl` L471 to L495, in the order the
   * paragraph performs it. L473 validates the KEY FIELDS ONLY, L475 to L478 read the most recent row,
   * L480 to L493 move eleven of its columns into the map's input fields, and L495 then performs
   * `PROCESS-ENTER-KEY` -- so the operator ends on a populated screen being asked to confirm.
   *
   * ⚠️ Refactoring Rationale: this is ONE call and not two. A read-then-submit pair was authored for it,
   * which fetched the eleven values, painted them and then submitted the painted form in the same task;
   * transaction-service publishes a single operation that copies, validates and answers with the preview
   * carrying what it copied, so the pair described a call that does not exist and made a second round trip
   * out of the reference's own fall-through at L495.
   *
   * Assumptions: the confirmation is passed through as keyed rather than forced, so pressing this key with
   * the field blank lands on `Confirm to add this transaction...` and pressing it with a `Y` already keyed
   * copies and writes in the one turn -- which is exactly what the reference's L495 re-entry does with
   * `CONFIRMI` as it stands.
   *
   * Assumptions: the values are passed as they are, without composing the answer into them, because this
   * key press does not change the confirmation field. {@link submitTurn} composes because the overlay's
   * controls stand for an answer the field does not yet hold; this key press submits the field as keyed.
   * @param {string} answer - The confirmation character as keyed, which the service reads at L495.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function copyLastTurn(answer: string): void {
    runTurn(answer, values, true);
  }

  /**
   * Dismisses the confirmation without committing and WITHOUT issuing any request.
   *
   * Purpose: this is the reference's declining arm. `app/cbl/COTRN02C.cbl` L176-L181 answers `'N'`,
   * `'n'`, `SPACES` and `LOW-VALUES` by moving `Confirm to add this transaction...` into `WS-MESSAGE`,
   * moving `-1` into `CONFIRML` and performing `SEND-TRNADD-SCREEN` -- it re-displays the populated map
   * and asks again. Only the `'Y'`/`'y'` arm at L189-L191 performs `ADD-TRANSACTION`, so a decline has no
   * write and, because nothing is read either, no wire turn at all.
   *
   * ⚠️ Refactoring Rationale: the control this replaces submitted a turn. A runtime sweep recorded one
   * `POST /api/v1/transactions` carrying `"confirmation":"N"` per decline, answered 200 with nothing
   * written, presented to the operator as a red band -- so cancelling looked like a failed add, and the
   * confirmation field was left holding a literal `'N'` the operator never typed. Both are gone: no
   * request leaves, the field returns to blank, and the sentence published is the reference's own
   * question rather than a failure.
   *
   * Assumptions: the operator's data is KEPT -- every field, and the resolved pair the service returned
   * -- because the reference re-displays the populated map rather than clearing it. Clearing is PF4's
   * job (`INITIALIZE-ALL-FIELDS`, L762-L779) and an operator who declined a confirmation has not asked
   * for it.
   *
   * Assumptions: this function performs NO validation of its own, because {@link localRefusal} has
   * already run on every route that can reach it -- {@link requestSubmit} for a keyed `'N'` and
   * {@link requestConfirmation} for the overlay that raised the declining control. That is the
   * reference's order, and re-running the edits here would report on a decline a refusal the reference
   * reports on the turn BEFORE it.
   * @returns {void} Nothing; the dismissal is published through this screen's own state.
   */
  function declineLocally(): void {
    setConfirming(false);

    /*
     * WHY : ⚠️ Assumptions: NO validation runs here, and the omission is the fix rather than a shortcut.
     *       A runtime sweep measured the declining control raising `Category CD must be Numeric...`
     *       against a field the operator had already been told nothing about, because the decline was
     *       routed through the same validation pass a submission takes. The reference's order is what
     *       settles it: `PROCESS-ENTER-KEY` performs `VALIDATE-INPUT-KEY-FIELDS` and
     *       `VALIDATE-INPUT-DATA-FIELDS` at `app/cbl/COTRN02C.cbl` L166-L167 and only THEN evaluates the
     *       confirmation at L169, and each validation paragraph ends its turn with
     *       `SEND-TRNADD-SCREEN`, whose last statement is `EXEC CICS RETURN`. So the reference cannot
     *       reach its declining arm with a refusable field at all -- validation has already ended the
     *       turn. {@link requestSubmit} runs both edits before this function is reachable by either
     *       route, which reproduces that order; running them again here would report a refusal the
     *       reference reports on the PRECEDING turn, not on the decline.
     */
    setValues(
      /**
       * Returns the confirmation to blank, leaving every other value as the operator left it.
       *
       * ⚠️ Assumptions: the answer is returned to BLANK rather than set to `'N'`. Both take the same arm
       * of the reference's `EVALUATE` at `app/cbl/COTRN02C.cbl` L173-L181, so the sentence and the cursor
       * position are identical either way, and blank is the state the field is actually in -- the
       * operator answered an overlay, not this control. Writing `'N'` into a control nobody keyed into
       * was measured, and it left the next Enter carrying an answer the operator never gave.
       * @param {TransactionAddValues} previous - The values as the surface was raised over them.
       * @returns {TransactionAddValues} The same values with the confirmation blanked.
       */
      (previous: TransactionAddValues): TransactionAddValues => ({ ...previous, confirmation: '' }),
    );
    setFieldErrors([]);
    /*
     * WHY : Assumptions: the severity is the same one every other sentence on this screen carries, and
     *       it is not a report of failure. `app/bms/COTRN02.bms` declares ONE message field -- `ERRMSG`
     *       at row 23, painted `COLOR=RED` -- and `COTRN02C` never overrides that colour, so the
     *       reference paints this question in exactly the colour it paints a refusal. The thing a
     *       decline must not do is report the add as having FAILED, and it does not: the sentence is the
     *       reference's own request to answer the confirmation, not `Unable to Add Transaction...`.
     */
    setSeverity('error');
    setMessage(ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION);
    focusField('confirmation');
  }

  /*
   * WHY : ⚠️ Refactoring Rationale: `Escape` withdraws the confirmation, and controlling the overlay is
   *       what took that away. An UNCONTROLLED `Popconfirm` closes on `Escape` for itself; an overlay
   *       opened by state and declaring `trigger={[]}` does not, so without this listener the only ways
   *       out of a raised confirmation are its own two controls -- and a keyboard operator who wanted
   *       neither would be trapped in front of a surface offering to write a record. AAP section 0.4.4
   *       makes keyboard fidelity a requirement rather than a nicety, because the screen this replaces
   *       is operated entirely from the keyboard. `ui/src/screens/cardUpdate/index.tsx` records the same
   *       measurement for the same idiom, so this is that screen's remedy applied here rather than a
   *       second invention.
   * WHY : Assumptions: `Escape` DECLINES and can never accept. It routes to the same withdrawal the
   *       overlay's own declining control uses, so no key press on this surface can commit a write.
   * WHY : Assumptions: the listener is installed only while the overlay stands, so `Escape` is left to
   *       the browser and to any other overlay on every other turn of this screen.
   * WHY : Alternatives Considered: registering `Escape` through `usePfKeys` beside the four attention
   *       identifiers this screen binds. Rejected because `KEYBOARD_KEY_TO_AID` maps only `Enter` and the
   *       function keys -- `Escape` is deliberately not an attention identifier, since the 3270 `CLEAR`,
   *       `PA1` and `PA2` keys it might stand for carry meanings `COTRN02C` never uses. Widening that
   *       shared map to serve one screen's overlay would put a browser concern into the hook that
   *       transcribes the reference's key contract.
   */
  useEffect(
    /**
     * Listens for `Escape` only while the confirmation stands, and withdraws it.
     * @returns {(() => void) | undefined} The listener's removal, or `undefined` on the turns where no
     *   listener was installed because no confirmation is open.
     */
    (): (() => void) | undefined => {
      if (!confirming) {
        return undefined;
      }

      /**
       * Withdraws the confirmation when `Escape` is pressed, leaving every other key alone.
       * @param {KeyboardEvent} event - The key press as the document saw it.
       * @returns {void} Completion is represented by the screen's own state.
       */
      function withdrawOnEscape(event: KeyboardEvent): void {
        if (event.key !== 'Escape') {
          return;
        }
        event.preventDefault();
        declineLocally();
      }

      document.addEventListener('keydown', withdrawOnEscape);
      /**
       * Removes the `Escape` listener when the confirmation is no longer standing.
       *
       * Assumptions: the SAME function reference is removed that was added, which is what makes this a
       * removal rather than a leak. The handler is declared inside the effect body for that reason: a
       * handler re-created between add and remove would leave the first one listening for the lifetime
       * of the document, and every subsequent raised overlay would add another.
       * @returns {void} Completion is represented by the document having no listener from this effect.
       */
      return (): void => {
        document.removeEventListener('keydown', withdrawOnEscape);
      };
    },
    // Assumptions: the dependency list names only `confirming`, not `declineLocally`. The handler is
    // re-created on every render along with the component body, and the listener is installed for the
    // lifetime of one raised overlay -- during which nothing this handler reads can change, because the
    // gate refuses every other route while `confirming` holds. Naming the handler would reinstall the
    // listener on every keystroke in the form for no behavioural difference.
    [confirming],
  );

  /**
   * The ONE gate every submitting surface passes through, and the only route to a write.
   *
   * Purpose: reproduce `PROCESS-ENTER-KEY` (`app/cbl/COTRN02C.cbl` L162-L191) as a single decision so
   * that no surface can submit a turn another surface would not. ⚠️ Refactoring Rationale: there were
   * TWO independent submitting paths before this existed. The legend's Enter called the turn directly
   * with whatever the confirmation field held -- so it committed nothing, asked nothing and omitted the
   * `confirmation` member from the request body entirely -- while the in-content control opened a
   * popover whose two answers each submitted a turn of their own, including the declining one. A runtime
   * sweep found both: one path that never asked, and one that mutated when told not to.
   *
   * The arms, in the reference's own order, and every one of them behind {@link localRefusal}:
   * - `'N'`/`'n'` is a dismissal and is handled locally by {@link declineLocally}. No request.
   * - `'Y'`/`'y'` is the operator's own affirmative and commits at once, which is the reference's
   *   `ADD-TRANSACTION` arm at L189-L191. Typing the character the mapset's `(Y/N)` field names IS the
   *   confirmation, so demanding a second one through an overlay would refuse a keyboard workflow the
   *   source supports.
   * - blank raises the ONE asking surface, locally, through {@link requestConfirmation}. No request.
   * - anything else non-blank is refused locally with `Invalid value. Valid values are (Y/N)...`, which
   *   is the reference's `WHEN OTHER` arm at L182-L187. No request.
   *
   * So exactly ONE of the four arms reaches the wire, and it is the one carrying the operator's
   * affirmative. ⚠️ Refactoring Rationale: two of them used to. The blank arm ran a "resolving turn"
   * -- `POST /api/v1/transactions` with the `confirmation` member ABSENT, because
   * {@link buildCreateRequest} attaches it only for the four letters the contract admits -- and the
   * `WHEN OTHER` arm dispatched the same confirmation-less body, on the ground that the service is the
   * authority on which non-answer it is. A browser sweep measured the consequence with a read-only
   * `MutationObserver`: a filled form with a blank confirmation, the footer legend `ENTER=Continue`
   * activated once and nothing else, produced a request on the WRITE endpoint at t0+3212ms with
   * `popEverVisible === false` -- no confirmation surface had ever been on the screen. The pointer
   * control on the same filled form asked first and issued nothing until answered. One screen, one
   * action, two confirmation idioms, and only one of them asked.
   *
   * ⚠️ Assumptions: the reference's blank arm is a LOCAL re-display and not a wire turn, which is what
   * makes the local ask the faithful reading rather than merely the safe one. L176-L181 moves a sentence
   * into `WS-MESSAGE`, moves `-1` into `CONFIRML` and performs `SEND-TRNADD-SCREEN`; L182-L187 does the
   * same with a different sentence. Neither touches a file. The reads the reference DOES perform on that
   * turn belong to `VALIDATE-INPUT-KEY-FIELDS` at L166, which resolves the key pair through
   * `READ-CXACAIX-FILE` or `READ-CCXREF-FILE` -- and the service performs exactly those two reads for
   * itself on the confirming turn, per `transaction-api.yaml`'s own account-precedence description, so
   * nothing is lost by not asking for them a turn early.
   *
   * ⚠️ Alternatives Considered: keeping the resolving turn and expressing it as a READ rather than as a
   * POST to the mutation endpoint, so the key pair could still be named on the asking surface. Rejected
   * because no such operation exists to call: `services/transaction-service/src/main/resources/openapi/
   * transaction-api.yaml` L470-L474 states that "splitting the unconfirmed turn into its own operation
   * would invent an endpoint the baseline does not have", and the contract is not this checkpoint's to
   * edit. The consequence is bounded and visible: the surface names the resolved pair only after a PF5
   * copy has resolved one, which is what {@link requestConfirmation} already documents and what
   * `offersNoSummaryBeforeAPreview` in `ui/src/screens/transactionAdd/transactionAddTurns.test.tsx`
   * already requires.
   *
   * ⚠️ Alternatives Considered: keeping the resolving turn on the ground that two of the reference's
   * steps live on it -- the amount's canonical re-render at L383-L386 and the two `CSUTLDTC` calendar
   * evaluations at L389-L427. That was the standing rationale and it has expired: {@link
   * canonicalValues} re-renders the amount through the same `+99999999.99` mask before any answer is
   * read, and {@link namesARealCalendarDate} decides both calendar verdicts inside
   * {@link dataFieldFailure}, so an unreal date is now refused BEFORE the operator is asked, which is
   * where L389-L427 refuses it. Sending a turn to obtain verdicts the screen already has would be a
   * round trip for nothing.
   *
   * ⚠️ Assumptions: validation cannot be skipped by any path to a write. {@link requestConfirmation} is
   * the pointer route and applies the same {@link localRefusal} in the same place, so no surface can
   * reach an answer over a form the screen already knows is refusable -- which is the state the previous
   * popover trigger opened from.
   * @returns {void} Nothing; every outcome is published through this screen's own state.
   */
  function requestSubmit(): void {
    /*
     * WHY : Assumptions: a submission arriving while the overlay stands is dropped as well as one
     *       arriving mid-request. The overlay's two controls call {@link declineLocally} and
     *       {@link confirmAndCommit} directly, so nothing legitimate reaches here while it is open --
     *       but the key bindings listen on the document, so without this an Enter keystroke would start a
     *       second resolving turn behind the surface Enter had just raised. Refusing it here is also what
     *       makes "a bare Enter never commits" true: with the overlay open, Enter reaches neither arm.
     */
    if (inFlight.current || confirming) {
      return;
    }

    const refusal = localRefusal();
    if (refusal !== null) {
      reportFieldFailure(refusal);
      return;
    }

    const answer = values.confirmation.trim();

    if (DECLINING_ANSWER.test(answer)) {
      declineLocally();
      return;
    }

    /*
     * WHY : Assumptions: `'Y'` and `'y'` are the ONLY answers that reach the wire, which is the
     *       reference's `ADD-TRANSACTION` arm at `app/cbl/COTRN02C.cbl` L189-L191 and nothing else.
     *       Typing the character the mapset's `(Y/N)` field names IS the confirmation, so demanding a
     *       second one through an overlay would refuse a keyboard workflow the source supports.
     */
    if (CONFIRMING_ANSWER.test(answer)) {
      submitTurn(answer);
      return;
    }

    if (answer === '') {
      /*
       * WHY : ⚠️ Refactoring Rationale: a BLANK answer raises the ONE asking surface, locally, by
       *       calling the pointer route's own function. It used to run a "resolving turn" instead --
       *       `POST /api/v1/transactions` carrying the whole capture with the `confirmation` member
       *       absent -- on the reading that the reference's L176-L181 arm asks in row 23 and that the
       *       turn "writes nothing". Two things were wrong with that. The reference's arm performs no
       *       I/O at all: it moves a sentence into `WS-MESSAGE`, moves `-1` into `CONFIRML` and
       *       performs `SEND-TRNADD-SCREEN`, so translating it into a request to the mutation endpoint
       *       invents a wire turn the source does not have. And it left the screen with two
       *       confirmation idioms for one action, only one of which asked: a browser sweep measured the
       *       footer legend `ENTER=Continue` putting a confirmation-less body on the write endpoint
       *       with no surface ever having been visible, while the pointer control on the same filled
       *       form asked and issued nothing. Calling {@link requestConfirmation} -- rather than
       *       duplicating what it does -- is what makes "one gate" a property of the code.
       * WHY : Assumptions: re-entering that function repeats the two guards and {@link localRefusal},
       *       and that repetition is deliberate. All three are pure over state this task has not
       *       changed, so the repeat costs no request and cannot disagree with what was just judged,
       *       while a private "raise it now" helper called from both would be a second place the
       *       raising conditions are stated.
       */
      requestConfirmation();
      return;
    }

    /*
     * WHY : ⚠️ Refactoring Rationale: an answer that is neither confirming nor declining is refused
     *       HERE, with no request, which is the reference's `WHEN OTHER` arm at
     *       `app/cbl/COTRN02C.cbl` L182-L187: `Invalid value. Valid values are (Y/N)...` into the
     *       message line, `-1` into `CONFIRML`, `SEND-TRNADD-SCREEN`, and no file touched. It used to be
     *       dispatched as keyed, on the ground that the service is the authority on which non-answer it
     *       is -- but {@link buildCreateRequest} attaches the `confirmation` member only for the four
     *       letters the contract admits, so a keyed `Q` did not reach the service AS a `Q`: it arrived
     *       as the same confirmation-less body the blank arm sent, which is a request asking to be
     *       asked, over a screen the operator had just answered. The verdict is local, unambiguous and
     *       cheap, and the reference reaches it without a turn.
     * WHY : Assumptions: the sentence comes from {@link unconfirmedSentence} rather than being named
     *       here, so the one function that decides which of the two non-confirming sentences an answer
     *       earns keeps deciding it for every route.
     * WHY : Assumptions: the refusal is reported as `NOT_OK` and not `BLANK`. `app/cpy/CSSETATY.cpy`
     *       L17-L27 nests the `MOVE '*'` inside the blank test alone, and this field is not blank --
     *       it holds a character the reference rejects, so it earns the colour and not the marker.
     */
    reportFieldFailure({
      field: 'confirmation',
      message: unconfirmedSentence(answer),
      state: 'NOT_OK',
    });
  }

  /**
   * Raises the confirmation overlay from a pointer, issuing NO request.
   *
   * Purpose: the pointer operator's route to the same gate. It applies the same two edits in the same
   * order and then opens the overlay locally, so a pointer and a keystroke cannot disagree about whether
   * a submission is admissible.
   *
   * ⚠️ Assumptions: opening issues NO request, and that is a contract rather than an optimisation.
   * `ui/src/screens/transactionAdd/transactionAdd.test.tsx` clears the confirmation, opens the overlay,
   * confirms, and asserts that exactly ONE capture reached the service carrying a body identical to the
   * keyed answer's -- so an opening turn would be a second, differing dispatch. The consequence is
   * accepted deliberately: a surface raised before any resolving turn has no service-resolved pair to
   * name, and it names NOTHING rather than repeating the keyed values with a service's authority.
   *
   * Assumptions: the edits are re-applied here rather than trusted from an earlier turn, because the
   * operator may have edited a field since one. They are pure functions over the current values, so
   * honouring the reference's order costs no request.
   * @returns {void} Nothing; the raised surface is published through this screen's own state.
   */
  function requestConfirmation(): void {
    if (inFlight.current || confirming) {
      return;
    }

    const refusal = localRefusal();
    if (refusal !== null) {
      reportFieldFailure(refusal);
      return;
    }

    /*
     * WHY : ⚠️ Assumptions: the band is emptied as the surface is raised. The overlay's title is the
     *       reference's confirmation sentence, so leaving the band's copy of it in place would put one
     *       verbatim string on the screen twice at once -- which is exactly what a runtime sweep measured
     *       between the previous overlay's title and the on-screen prompt. `declineLocally` re-publishes
     *       it the moment the surface closes, which is the reference's own post-decline state, so the
     *       sentence is deferred rather than lost.
     */
    /*
     * WHY : ⚠️ Assumptions: the canonical values are stored BEFORE the surface is raised, so the amount the
     *       surface names is the amount the control shows and the amount the write will carry. Without
     *       this the surface could name `+00000100.00` over a control still reading `100.00`, which is the
     *       shape of defect the cross-screen umbrella names outright: no outcome may be presented while a
     *       contradicting value is displayed beside it.
     */
    setValues(canonicalValues());
    setMessage('');
    setFieldErrors([]);
    /*
     * WHY : ⚠️ Assumptions: the anchor is brought onto the display BEFORE the surface is raised, and the
     *       order is the whole of it. The overlay measures its anchor when it opens, so scrolling
     *       afterwards would leave it positioned against where the anchor used to be. `block: 'nearest'`
     *       is the least movement that can work and is a no-op when the anchor is already fully visible,
     *       which is the pointer operator's case -- they just clicked it -- so a route that never had the
     *       defect is not given a jolt to fix it. See {@link confirmationAnchor} for the measurement.
     *       Trade-offs: the form scrolls under the operator on a legend-taken turn. That is the intended
     *       effect rather than a side effect: the question names the record about to be written and both
     *       of its answers must be reachable and whole, which is worth moving the view for.
     */
    confirmationAnchor.current?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    setConfirming(true);
  }

  /**
   * Commits the capture the overlay described, which is the reference's confirming turn.
   *
   * Assumptions: the overlay is dismissed BEFORE the turn is dispatched, so the busy state and any
   * refusal land on the form the operator is returned to rather than behind a raised surface.
   *
   * ⚠️ Assumptions: the affirmative is dispatched through {@link submitTurn} -- the SAME function a keyed
   * `'Y'` uses -- rather than through a request this handler composes. That is what makes the pointer
   * route and the keyed route produce byte-identical bodies, which is the property the previous two
   * independent submitting paths could not have. `submitTurn` also records the answer in the
   * confirmation field, which is correct here and not on a decline: the reference's confirming turn
   * genuinely has `'Y'` in `CONFIRMI`, and a write that is then refused leaves it there for the retry,
   * which is the state `app/cbl/COTRN02C.cbl` L189 evaluates.
   * @returns {void} Nothing; the outcome is published through this screen's own state.
   */
  function confirmAndCommit(): void {
    setConfirming(false);
    submitTurn(CONFIRMATION_ANSWERS.CONFIRM);
  }

  /*
   * WHY : Assumptions: exactly FOUR keys are registered, and the absence of PF7, PF8 and PF12 is
   *       measured rather than assumed. `app/cbl/COTRN02C.cbl` L133-L152 evaluates `EIBAID` with arms for
   *       `DFHENTER`, `DFHPF3`, `DFHPF4` and `DFHPF5` and a `WHEN OTHER` that refuses everything else, and
   *       the row-24 legend paints those same four. Registering a paging key here would answer a keystroke
   *       the reference refuses -- and `usePfKeys` routes an unregistered key to the invalid-key channel,
   *       which is exactly the coercion the `WHEN OTHER` arm performs.
   * WHY : Assumptions: PF13 through PF24 need no handling here. `app/cpy/CSSTRPFY.cpy` L54-L77 aliases them
   *       onto PF01 through PF12, and `usePfKeys` already applies that table through `PF_KEY_ALIASES`, so
   *       re-implementing the aliasing in this screen would create a second copy of one mapping.
   * WHY : ⚠️ Refactoring Rationale: every binding declares `disabled` while a turn is in flight, where none
   *       did. The controls and the overlay already reflected the busy state, so the SCREEN said a turn was
   *       running while the legend's four controls stayed lit and a key press was accepted and then
   *       silently dropped by the guard inside the turn -- an operator pressing Enter twice saw nothing
   *       happen and no reason why. One declaration covers all three surfaces: `usePfKeys` refuses the
   *       dispatch before calling `onInvoke`, and `PfKeyBar` renders the control disabled and routes its
   *       clicks through that same dispatch, so the visible state, the pointer and the keyboard agree.
   *       Trade-offs: PF3 and PF4 are disabled too, though neither writes. Leaving them live would let an
   *       operator navigate away or clear the form while a capture is in flight, and the settlement would
   *       then paint a message about a submission whose screen no longer exists -- the terminal has no such
   *       state, because its keyboard is locked until the region replies.
   * WHY : ⚠️ Assumptions: every binding declares its `risk`, and the two that carry `mutating` are the
   *       two the DISPATCH can write from rather than the two an attention identifier would suggest.
   *       `PfKeyBar`'s `PRIMARY_ACTION_AIDS` fallback happens to emphasise the same pair here, so this
   *       declaration changes no paint on this screen -- it states the reason the paint is right, which
   *       the fallback cannot. `ENTER=Continue` reaches `ADD-TRANSACTION` at `app/cbl/COTRN02C.cbl`
   *       L189-L191 whenever `CONFIRMI` holds an affirmative, and `F5=Copy Last Tran.` falls straight
   *       through into `PROCESS-ENTER-KEY` at L495, so a copy pressed with an affirmative already keyed
   *       copies AND writes in the one turn.
   * WHY : ⚠️ Trade-offs: PF5's label names a COPY and its risk names a write, and the taxonomy asks for
   *       the label's reading. The dispatch is the stronger evidence and it is cited rather than
   *       glossed: L495 is an unconditional `PERFORM PROCESS-ENTER-KEY`, so the key genuinely can
   *       commit. What the taxonomy forbids is inferring risk from WHICH key carries the action -- the
   *       same `PFK05` is delete, save and browse on three other mapsets -- and this reads the program's
   *       own dispatch for this mapset instead, which is the opposite of that mistake.
   * WHY : Assumptions: `busy` is deliberately NOT substituted for `disabled` on these four entries.
   *       `busy` declines a press SILENTLY while leaving the control present, enabled and focusable,
   *       which is the right primitive for a key that is momentarily early; `disabled` additionally
   *       states unavailability on the control itself, which is what the Refactoring Rationale above
   *       was written to obtain and what this screen's own paint already reflects. Substituting it would
   *       re-enable four controls during an in-flight turn, and no finding in this group asks for that
   *       -- so the silent-decline half is kept through `onInvalidKey`'s `'disabled'` arm instead, which
   *       reaches the same outcome for the keyboard without changing the legend.
   */
  const keyHandlers: PfKeyHandlerMap = {
    ENTER: {
      /**
       * Runs one turn with the confirmation as keyed, which is the reference's Enter arm.
       *
       * Assumptions: it goes through {@link requestSubmit}, the ONE gate every submitting surface passes
       * through, so a keyed answer and a clicked one cannot take different arms. ⚠️ Refactoring
       * Rationale: this arm used to call the turn DIRECTLY with whatever the confirmation field held,
       * which made it a second submitting path that never asked -- a runtime sweep recorded it
       * submitting immediately with no confirmation surface and with the `confirmation` member absent
       * from the request body altogether.
       * Assumptions: this arm always submits the CAPTURE and never the copy, including the Enter that
       * confirms a copy. `PROCESS-ENTER-KEY` writes the map fields, and after a copy those fields hold the
       * copied record because the copy block put them there at `app/cbl/COTRN02C.cbl` L481 to L492 -- so
       * the faithful confirming turn is a capture of the screen, and reaching the copy operation again
       * would resolve "the most recently stored transaction" a second time.
       * @returns {void} Completion is represented by the screen's own state.
       */
      onInvoke: requestSubmit,
      label: TRANSACTION_ADD_KEY_LABELS.ENTER,
      risk: 'mutating',
      disabled: busy || confirming,
    },
    PFK03: {
      /**
       * Returns to the main menu, which is where the reference sends an operator with no recorded caller.
       *
       * Assumptions: the destination is the main menu and not the sign-on screen. `app/cbl/COTRN02C.cbl`
       * L137-L142 moves `'COMEN01C'` into the target program when the caller is blank and otherwise
       * returns to the caller, and the browser's own history is what carries the caller here -- so the
       * menu is the fallback this arm expresses.
       * @returns {void} Completion is a route change.
       */
      onInvoke: (): void => {
        navigateSafely(navigate, MAIN_MENU_ROUTE);
      },
      label: TRANSACTION_ADD_KEY_LABELS.PFK03,
      risk: 'read-only',
      disabled: busy || confirming,
    },
    PFK04: {
      /**
       * Clears every field, the confirmation and the message, which is the reference's PF4 arm.
       * @returns {void} Completion is represented by the screen's own state.
       */
      onInvoke: clearScreen,
      label: TRANSACTION_ADD_KEY_LABELS.PFK04,
      risk: 'read-only',
      disabled: busy || confirming,
    },
    PFK05: {
      /**
       * Copies the stored last transaction and falls straight through into the Enter processing.
       *
       * Assumptions: the fall-through is the reference's, not an addition. `COPY-LAST-TRAN-DATA` ends with
       * `PERFORM PROCESS-ENTER-KEY` at `app/cbl/COTRN02C.cbl` L495, so pressing this key with the
       * confirmation still blank lands on `Confirm to add this transaction...` rather than on a screen
       * that merely filled itself in.
       * @returns {void} Completion is represented by the screen's own state.
       */
      onInvoke: (): void => {
        copyLastTurn(values.confirmation);
      },
      label: TRANSACTION_ADD_KEY_LABELS.PFK05,
      risk: 'mutating',
      disabled: busy || confirming,
    },
  };

  const { bindings, invoke } = usePfKeys(keyHandlers, {
    /**
     * Reports the shared invalid-key sentence and returns the cursor to the initial-cursor field.
     *
     * Assumptions: the sentence is the shared `CCDA-MSG-INVALID-KEY`, which the `WHEN OTHER` arm moves
     * into the message at `app/cbl/COTRN02C.cbl` L150 before re-sending the screen. Its trailing spaces are
     * part of the declared `PIC X(50)` width and the catalog carries them, so the value is passed on
     * unchanged rather than trimmed.
     *
     * Assumptions: the cursor goes to the account field, which is where the reference leaves it. That arm
     * sets no field's length to -1, and `SEND-TRNADD-SCREEN` sends with `CURSOR` and no value at
     * L522-L528, so symbolic positioning falls back to the mapset's single `IC` attribute -- which
     * `app/bms/COTRN02.bms` L85 declares on the account field and nowhere else.
     * ⚠️ Assumptions: only an UNMAPPED key earns the sentence. A key this screen binds but has momentarily
     * disabled -- every one of the four, while a request is in flight or the confirmation overlay stands
     * -- arrives here too, and reporting it as invalid would be two separate lies: the reference binds
     * that key, and the operator's press was refused by this screen's own state rather than by
     * `COTRN02C`'s `WHEN OTHER` arm. It would also move the cursor, which is what makes it more than
     * cosmetic: an Enter pressed while the overlay stands would publish `invalid key`, yank focus out of
     * the overlay and into the account field, and leave a surface open that the operator could no longer
     * reach from the keyboard. A disabled key is therefore absorbed silently, which is what a terminal
     * with a task in flight does.
     * @param {PfKeyRejection} rejection - Which key was refused, and whether it was unmapped or disabled.
     * @returns {void} Completion is represented by the screen's own state.
     */
    onInvalidKey: (rejection: PfKeyRejection): void => {
      if (rejection.reason === 'disabled') {
        return;
      }
      setSeverity('error');
      setMessage(INVALID_KEY_PRESSED);
      focusField('accountId');
    },
  });

  /*
   * WHY : Refactoring Rationale: the title band, the row-23 message line and the row-24 legend are
   *       DELEGATED to the one `AppShell` that `ui/src/App.tsx` mounts, where this screen composed all
   *       three itself. Per-screen composition is what this tree did before the shell was wired in;
   *       keeping it afterwards would render a second title band, a second message line and a second
   *       named legend region on the screen. What stays here is everything the mapset paints between rows
   *       4 and 21 -- the title, the fourteen fields and the confirmation control.
   * WHY : ⚠️ Assumptions: delegating `pfKeys` hands the shell the bindings to RENDER and leaves
   *       this screen owning the keyboard; an activation of a rendered legend control is forwarded
   *       straight back to `invoke`. The claim that stood here is withdrawn in its second half: it is
   *       true that `usePfKeys` installs one document listener per call site, but the shell installs NONE
   *       of them -- it offers sign-off as a rendered control, for the reason recorded at
   *       `SHELL_SIGN_OFF_LABEL`. There is nothing to make stand down, so the publication buys the
   *       painted legend rather than sole ownership of the keyboard. No legend colour is delegated because
   *       `app/bms/COTRN02.bms` L297-L302 paints the row-24 field `COLOR=YELLOW`, the slot's own default.
   */
  useShellSlot({
    screen: {
      transactionId: TRANSACTION_ADD_TRANSACTION_ID,
      programName: TRANSACTION_ADD_PROGRAM_NAME,
    },
    now: paintedAt,
    message: { text: message, severity, mapset: TRANSACTION_ADD_MAPSET },
    pfKeys: { keys: bindings, onInvoke: invoke },
  });

  /*
   * WHY : Refactoring Rationale: the text colours below resolve through
   *       `BMS_TEXT_COLOR_TOKENS` rather than through the hue map `BMS_COLOR_TOKENS`. The
   *       measured source roles are unchanged -- `COLOR=NEUTRAL` on the screen title and the
   *       domain hints, `COLOR=TURQUOISE` on the fourteen field labels -- but the hue map's
   *       entries are fill-grade anchors, and read as text the turquoise one measures 2.205:1
   *       and the blue one 4.104:1 against the surface the shell paints, where WCAG AA asks
   *       4.5:1 for normal text. `ui/src/theme/tokens.ts` records the per-role measurement and
   *       which roles kept their hue family.
   */
  const titleStyle: CSSProperties = {
    color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL],
    fontWeight: cssVar[TYPOGRAPHY_TOKENS.brightEmphasis],
  };
  const labelStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.TURQUOISE] };
  const neutralStyle: CSSProperties = { color: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] };
  const fixedPitchStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };
  /*
   * WHY : Assumptions: the rule's colour is set through `borderColor` rather than `color`, because a
   *       `Divider` draws itself with a border where the withdrawn `Typography.Text` drew itself with
   *       glyphs. The measured source role is unchanged -- `COLOR=NEUTRAL` at `app/bms/COTRN02.bms`
   *       L111-L116 -- and it resolves through the same text-grade map every other colour on this screen
   *       reads, so the rule is painted the same shade as the labels it separates.
   */
  const ruleStyle: CSSProperties = { borderColor: cssVar[BMS_TEXT_COLOR_TOKENS.NEUTRAL] };
  const blankMarkerStyle: CSSProperties = { color: cssVar[FIELD_ERROR_TOKENS.errorColor] };
  const responsiveGridGutter = transactionAddGridGutter(token[SPACING_TOKENS.sectionGapMedium]);

  /**
   * Records one field's value.
   *
   * ⚠️ Assumptions: nothing about a previous copy is remembered or discarded here, and nothing needs to be.
   * The copy key press paints its eleven values INTO these same fields, so after it runs the form holds
   * the copied record and an edit to any field simply changes what the next turn submits -- which is
   * exactly the reference's behaviour, its L495 re-entry reading the fields as they then stand. The flag
   * this handler used to clear existed only because the confirming turn re-invoked a copy operation, and
   * clearing it here is what made a typed `Y` submit the pre-copy values.
   *
   * Assumptions: the refusal marker and the message are NOT cleared here. AAP section 0.7.1 removes the
   * re-entry discriminator the reference gated its highlighting on, and the replacement is that the error
   * state is driven purely by the response body -- so it changes when a turn produces a new one, exactly
   * as row 23 and the field attributes only changed on a `SEND MAP`.
   * @param {TransactionAddField} field - Field being edited.
   * @returns {(event: ChangeEvent<HTMLInputElement>) => void} Change handler for that field's control.
   */
  function changeHandler(
    field: TransactionAddField,
  ): (event: ChangeEvent<HTMLInputElement>) => void {
    /**
     * Stores the control's current value against its field.
     * @param {ChangeEvent<HTMLInputElement>} event - Change event carrying the edited value.
     * @returns {void} Completion is represented by the screen's own state.
     */
    return (event: ChangeEvent<HTMLInputElement>): void => {
      /*
       * WHY : Assumptions: a free-text field's characters are filtered on the way IN, and only that
       *       field's are. The five fields this covers are the ones whose characters an operator authors
       *       with no other composition rule, and the terminal enforced their domain physically -- a
       *       single-byte 3270 keyboard could not transmit a control character or a supplementary code
       *       point into them. Filtering here is that same "nothing appears", and it is done at the
       *       handler rather than at submission so the operator learns at the control instead of from a
       *       400. `retainPrintableText` carries the derivation of the domain.
       */
      const filtered = FREE_TEXT_FIELDS.includes(field)
        ? retainPrintableText(event.target.value)
        : event.target.value;
      /*
       * WHY : ⚠️ Assumptions: the value is CLAMPED to the field's declared width here, and the clamp is
       *       the fix for a class of defect rather than defensive tidying. `maxLength` stops a keyboard
       *       and a paste, but it does not stop a programmatic assignment, and browser validation reached
       *       exactly that: fifteen characters were placed in the eleven-character account field and
       *       thirteen in the twelve-character amount field. Two things followed. The control rendered
       *       antd's `ant-input-out-of-range`, which recolours the VALUE and nothing else -- no border, no
       *       `aria-invalid`, no help text, no band -- so the only signal was a colour, which WCAG 1.4.1
       *       forbids as a sole carrier and assistive technology cannot see at all. And the refusals
       *       never fired: every width predicate on this screen reads
       *       `padToDeclaredWidth`, which TRUNCATES, so a thirteen-character amount was judged on its
       *       first twelve characters, passed, and the thirteenth went to the service unexamined.
       * WHY : Assumptions: the over-long characters are DROPPED rather than reported, which is the
       *       reference's own physics rather than a convenience. `EXEC CICS RECEIVE MAP` moves at most a
       *       field's declared length out of the inbound datastream, so a 3270 could not transmit a
       *       thirteenth character into a twelve-position field -- there was no such state to report, and
       *       `ui/src/messages/messages.ts` accordingly carries no over-length sentence for this program.
       *       It is also the treatment `retainPrintableText` already documents for an inadmissible
       *       character and that `maxLength` already gives an over-long paste, so all three agree.
       * WHY : Alternatives Considered: leaving the value whole and adding an exact-width predicate to
       *       `dataFieldFailure` so the operator saw a refusal. Rejected because there is no baseline
       *       sentence to raise -- authoring one would put a string on this screen that no program
       *       produces, which rule T8 forbids -- and because it would leave the control in the
       *       colour-only state that V139 reports for as long as the value stood.
       */
      const edited = filtered.slice(0, TRANSACTION_ADD_FIELD_WIDTHS[field]);
      /*
       * WHY : Assumptions: editing either KEY field discards the resolved pair, and only a key field does.
       *       The pair states what the service resolved FROM those two values, so once one of them changes
       *       the pair describes a resolution that no longer follows from what is on the screen -- and the
       *       confirmation surface would then describe a record with the authority of a service answer
       *       while naming an account the next turn will not use. Editing a DATA field leaves it standing,
       *       because the resolution does not depend on the data fields: L193-L229 reads only the keys.
       */
      if (field === 'accountId' || field === 'cardNumber') {
        setResolvedKeys(null);
      }
      setValues(
        /**
         * Replaces one field's value, leaving the rest as they stand.
         * @param {TransactionAddValues} previous - Values as they stand.
         * @returns {TransactionAddValues} The same values carrying the edit.
         */
        (previous: TransactionAddValues): TransactionAddValues => ({
          ...previous,
          [field]: edited,
        }),
      );
    };
  }

  /**
   * Builds the handler that re-renders a field into its canonical form when the operator leaves it.
   *
   * ⚠️ Purpose: this is what makes a human spelling of an amount usable. `100.00` typed into a field
   * whose form is `+99999999.99` becomes `+00000100.00` on the way out of the control, so the value the
   * edits then judge is the value the reference would have received -- without the edits themselves being
   * loosened, which would have been a parity divergence rather than a fix.
   *
   * ⚠️ Assumptions: the normalisation happens on BLUR and never on change. Rewriting on every keystroke
   * would fight the operator: keying `1`, `0`, `0` would see the first digit expand to `+00000001.00` and
   * the caret jump, so the second digit would land somewhere neither of them intended. Blur is also where
   * the 3270 did its own equivalent -- the reference re-renders the amount on the TURN, at L383-L386,
   * which is later still.
   *
   * Assumptions: a normalisation that changes nothing writes no state, so leaving an untouched or already
   * canonical field cannot cause a render. The screen has fifteen controls and the operator tabs through
   * all of them.
   *
   * Assumptions: the resolved pair is NOT discarded here, unlike an edit to a key field. Only the amount
   * declares a normaliser and the resolution reads neither it nor any other data field -- L193-L229 reads
   * the two keys alone -- so nothing the pair asserts can be invalidated by this.
   * @param {TransactionAddField} field - The field whose control is being left.
   * @param {(value: string) => string} normalise - The field's own canonical rendering.
   * @returns {() => void} A blur handler for that control.
   */
  function normaliseHandler(
    field: TransactionAddField,
    normalise: (value: string) => string,
  ): () => void {
    /**
     * Re-renders the field's stored value, when the rendering differs from what is stored.
     * @returns {void} Completion is represented by the screen's own state.
     */
    return (): void => {
      setValues(
        /**
         * Replaces the field's value with its canonical rendering, or leaves the values untouched.
         * @param {TransactionAddValues} previous - Values as they stand.
         * @returns {TransactionAddValues} The same object when nothing changed, so React skips the render.
         */
        (previous: TransactionAddValues): TransactionAddValues => {
          const canonical = normalise(previous[field]);
          return canonical === previous[field] ? previous : { ...previous, [field]: canonical };
        },
      );
    };
  }

  /**
   * Registers one control so a refusal can move the cursor to it.
   * @param {TransactionAddField} field - Field the control renders.
   * @returns {(control: InputRef | null) => void} Callback ref that records the control.
   */
  function registerControl(field: TransactionAddField): (control: InputRef | null) => void {
    /**
     * Records the mounted control, or forgets it on unmount.
     * @param {InputRef | null} control - The control, or `null` while it is being detached.
     * @returns {void} Completion is the updated lookup.
     */
    return (control: InputRef | null): void => {
      controls.current[field] = control;
    };
  }

  /**
   * Renders one editable field with its label, width, refusal state and hint.
   *
   * Assumptions: every field goes through this one function, so the label association, the width, the
   * refusal rendering and the cursor registration cannot differ between fields. Fourteen hand-written
   * blocks would be fourteen chances for one of those four to be omitted, and the omission that matters
   * most is the width: it is the copybook picture and the screen tests assert every one of them.
   *
   * Assumptions: the label is bound to the control with an explicit identifier rather than left to the
   * form to infer, because these controls are managed by this screen rather than by the form store. The
   * association is what lets an operator using a screen reader hear the mapset's own label, and what lets
   * the screen tests find a control by the label literal.
   *
   * ⚠️ Refactoring Rationale: the refusal text and the format hint are now bound to the control
   * PROGRAMMATICALLY, through the shared `fieldHelp` renderer, where they were rendered beside it and
   * linked to nothing. `Form.Item` positions its `help` and `extra` containers visually and gives them
   * no identifier, so an operator using a screen reader heard the field's label and its value and never
   * the sentence explaining why the value was refused -- on all fourteen controls, which is every
   * control this screen has. `aria-invalid` states the refusal itself and `aria-describedby` names the
   * sentence and the hint in the order antd paints them, so what a sighted operator reads under the
   * control is what an operator using assistive technology hears with it.
   * @param {TransactionAddField} field - Field to render.
   * @param {FieldPresentation} presentation - Hint, initial-cursor, numeric and fixed-pitch options.
   * @returns {ReactElement} The labelled control, sized to share its row with its siblings.
   */
  function renderField(
    field: TransactionAddField,
    presentation: FieldPresentation = {},
  ): ReactElement {
    const refusal = fieldErrors.find(
      /**
       * Selects the refusal naming this field, if the last turn produced one.
       * @param {TransactionAddFieldError} entry - One refusal from the last turn.
       * @returns {boolean} `true` when the refusal names this field.
       */
      (entry: TransactionAddFieldError): boolean => entry.field === field,
    );
    const controlId = `${idPrefix}${field}`;

    return (
      /*
       * WHY : Assumptions: the wrapper no longer carries `flex: 1 1 0`, and dropping it is part of the
       *       responsive fix rather than tidying. Every call site is now a `Col`, which is not a flex
       *       container, so the term had no effect there -- and while the call sites WERE flex rows it was
       *       the reason the arity never collapsed: a zero flex-basis makes every column fit at every
       *       width, so `wrap` had nothing to act on and a three-up row stayed three-up at 375 pixels.
       */
      <Flex key={field} vertical>
        <Form.Item
          label={
            <Typography.Text style={labelStyle}>
              {TRANSACTION_ADD_FIELD_LABELS[field]}
            </Typography.Text>
          }
          htmlFor={controlId}
          {...(refusal === undefined
            ? {}
            : {
                validateStatus: 'error' as const,
                help: fieldErrorHelp(controlId, refusal.message),
              })}
          {...(presentation.hint === undefined
            ? {}
            : {
                extra: (
                  <Typography.Text
                    id={fieldHintId(controlId)}
                    style={{ color: cssVar[BMS_TEXT_COLOR_TOKENS[presentation.hint.tone]] }}
                  >
                    {presentation.hint.text}
                  </Typography.Text>
                ),
              })}
        >
          <Input
            id={controlId}
            ref={registerControl(field)}
            value={values[field]}
            maxLength={TRANSACTION_ADD_FIELD_WIDTHS[field]}
            onChange={changeHandler(field)}
            disabled={busy}
            autoFocus={presentation.initialCursor === true}
            {...fieldAriaProps(controlId, {
              invalid: refusal !== undefined,
              hasError: refusal !== undefined,
              hasHint: presentation.hint !== undefined,
            })}
            {...(presentation.numeric === true ? { inputMode: 'numeric' as const } : {})}
            {...(presentation.fixedPitch === true ? { style: fixedPitchStyle } : {})}
            {...(presentation.normalise === undefined
              ? {}
              : { onBlur: normaliseHandler(field, presentation.normalise) })}
            {...(refusal?.state === 'BLANK'
              ? {
                  suffix: (
                    <Typography.Text aria-hidden="true" style={blankMarkerStyle}>
                      {FIELD_ERROR_TOKENS.blankMarker}
                    </Typography.Text>
                  ),
                }
              : {})}
          />
        </Form.Item>
      </Flex>
    );
  }

  /*
   * WHY : Trade-offs: the mapset's absolute geometry is NOT reproduced, and this is AAP gap G1 taken
   *       deliberately. `DFHMDI SIZE=(24,80)` fixes a 24-row by 80-column cell grid and all 61 field
   *       definitions carry an absolute `POS=(row,column)`, so a faithful rendering would need character
   *       cells at fixed coordinates. What is preserved is what survives translation: the GROUPING, the
   *       READING ORDER and the TAB ORDER. Each row below is one of the mapset's own rows -- 6 for the two
   *       key alternatives, 10 for the type, category and source, 12 for the description, 14 for the
   *       amount and the two dates, 16 and 18 for the merchant, 21 for the confirmation -- and the fields
   *       appear within each row in ascending column order, which is the order an operator tabbed through
   *       them. What is given up is pixel-for-character positioning, which no browser can hold across
   *       viewport widths and which would be hostile to an operator using a screen reader or magnification.
   * WHY : Alternatives Considered: `Row` and `Col` with a `gutter`, which is antd's idiomatic form grid and
   *       is what an earlier draft of this screen used. Rejected because `gutter` takes a PIXEL NUMBER, and
   *       AAP section 0.3.2 admits only values that resolve to a design token -- so the idiomatic choice
   *       would have put a hardcoded spacing literal on every row. `Flex` takes antd's semantic sizes,
   *       which resolve through the theme's spacing scale, and its `flex` prop lets each field share its
   *       row without a width literal.
   */
  return (
    <Flex vertical gap="large">
      {/*
       * ⚠️ Assumptions: the rank comes from `ui/src/layout/ScreenTitle.tsx` and the SIZE from the
       * bridge entries it applies, where this site read `level={4}` and relied on the component to
       * supply both at once. Ten other screens painting the same row-4 caption reasoned the same way and
       * arrived at `level={3}`, which outranked the application title above them, so the two decisions
       * are now stated separately and every caption shares one rank at one size. The field's
       * `ATTRB=(ASKIP,BRT)` is carried by the weight token rather than by a colour, which is what keeps
       * brightness and colour independent the way the mapset has them -- this heading is also
       * `COLOR=NEUTRAL`.
       */}
      <ScreenTitle style={titleStyle}>{TRANSACTION_ADD_TITLE}</ScreenTitle>
      {/*
       * Refactoring Rationale: the message line that used to sit here is delegated to the shell, which
       * paints it at row 23 -- below the fields, which is where the reference paints it. Composing it
       * above the form was this tree's earlier convention and it inverted the source's order; the band
       * reserves its space at all times either way, so the layout stability that reservation exists for is
       * unaffected by the move.
       */}
      <Form layout="vertical">
        <Row gutter={responsiveGridGutter}>
          <Col {...KEY_FIELD_SPANS}>
            {renderField('accountId', { initialCursor: true, numeric: true, fixedPitch: true })}
          </Col>
          {/*
           * Assumptions: this word is NOT decorative and is therefore not hidden from assistive
           * technology, unlike the rule below it. It states that the two key fields are alternatives,
           * which is the whole of the ordered branch at `app/cbl/COTRN02C.cbl` L195-L230, so an operator
           * who cannot see it needs it read to them.
           * Assumptions: it keeps a column of its own rather than being folded into either field's, so
           * that when the row collapses to one field per line it stacks BETWEEN them and still reads as
           * joining the two. Folded into the account field's column it would read as that field's hint.
           */}
          <Col {...ALTERNATIVE_LABEL_SPANS}>
            <Typography.Text style={neutralStyle}>
              {TRANSACTION_ADD_ALTERNATIVE_KEY_LABEL}
            </Typography.Text>
          </Col>
          <Col {...KEY_FIELD_SPANS}>
            {renderField('cardNumber', { numeric: true, fixedPitch: true })}
          </Col>
        </Row>
        <Divider style={ruleStyle} />
        <Row gutter={responsiveGridGutter}>
          <Col {...THREE_UP_SPANS}>
            {renderField('typeCode', { numeric: true, fixedPitch: true })}
          </Col>
          <Col {...THREE_UP_SPANS}>
            {renderField('categoryCode', { numeric: true, fixedPitch: true })}
          </Col>
          <Col {...THREE_UP_SPANS}>{renderField('source')}</Col>
        </Row>
        <Row gutter={responsiveGridGutter}>
          <Col {...FULL_WIDTH_SPANS}>{renderField('description')}</Col>
        </Row>
        <Row gutter={responsiveGridGutter}>
          {/*
           * Assumptions: the amount carries the mapset's own format hint and is rendered in the
           * fixed-pitch face, and both follow from it being money. The hint is the reference's
           * `(-99999999.99)` at `app/bms/COTRN02.bms` L208-L212, and the face is what keeps the sign, the
           * eight integer digits and the two decimals in the same columns from one capture to the next.
           */}
          {/*
           * WHY : ⚠️ Assumptions: the amount is the ONE control that declares a normaliser, and it needs
           *       one because its field form is not a form anybody types. Browser validation drove
           *       eighteen spellings through it and only `-` plus eight digits plus `.` plus two digits
           *       was accepted, so `100.00`, `123.45` and `-100.00` were all refused and the tester
           *       reported that no positive amount could be entered at all -- which made the transaction
           *       add flow in AAP section 0.9.4 unrunnable. `canonicaliseKeyedAmount` carries the
           *       derivation and the reason the predicate was not loosened instead.
           */}
          <Col {...THREE_UP_SPANS}>
            {renderField('amount', {
              hint: { text: TRANSACTION_ADD_FORMAT_HINTS.amount, tone: 'BLUE' },
              fixedPitch: true,
              normalise: canonicaliseKeyedAmount,
            })}
          </Col>
          <Col {...THREE_UP_SPANS}>
            {renderField('originDate', {
              hint: { text: TRANSACTION_ADD_FORMAT_HINTS.originDate, tone: 'BLUE' },
              fixedPitch: true,
            })}
          </Col>
          <Col {...THREE_UP_SPANS}>
            {renderField('processDate', {
              hint: { text: TRANSACTION_ADD_FORMAT_HINTS.processDate, tone: 'BLUE' },
              fixedPitch: true,
            })}
          </Col>
        </Row>
        <Row gutter={responsiveGridGutter}>
          <Col {...TWO_UP_SPANS}>
            {renderField('merchantId', { numeric: true, fixedPitch: true })}
          </Col>
          <Col {...TWO_UP_SPANS}>{renderField('merchantName')}</Col>
        </Row>
        <Row gutter={responsiveGridGutter}>
          <Col {...TWO_UP_SPANS}>{renderField('merchantCity')}</Col>
          <Col {...TWO_UP_SPANS}>{renderField('merchantZip')}</Col>
        </Row>
        <Row gutter={responsiveGridGutter}>
          {/*
           * Assumptions: the single-character control is RETAINED alongside the overlay, and that is what
           * keeps `Invalid value. Valid values are (Y/N)...` reachable. The overlay can only produce the
           * two answers its controls stand for, so a screen with no keyed field would have no way to
           * submit a third character and the reference's `WHEN OTHER` sentence at
           * `app/cbl/COTRN02C.cbl` L182-L187 would become dead text.
           * Assumptions: the domain hint is `COLOR=NEUTRAL` at `app/bms/COTRN02.bms` L288-L292, unlike the
           * three blue format hints above, so it resolves to a different token and the difference is the
           * mapset's rather than an inconsistency here.
           */}
          <Col {...FULL_WIDTH_SPANS}>
            {renderField('confirmation', {
              hint: { text: TRANSACTION_ADD_CONFIRM_DOMAIN_HINT, tone: 'NEUTRAL' },
            })}
          </Col>
        </Row>
        {/*
         * WHY : Purpose: the pointer operator's route to the confirmation. It stands where the reference
         *       has nothing, because the reference's confirmation IS the field above -- a 3270 had no
         *       overlay to raise, so a whole screen turn plus a keyed character was the cheapest
         *       confirmation the terminal could express.
         * WHY : ⚠️ Refactoring Rationale: the overlay is CONTROLLED and the trigger opens it through
         *       {@link requestConfirmation} rather than through the library's own `click` trigger, and
         *       that single change is what removes this screen's write-on-decline. An uncontrolled
         *       overlay opens on its trigger's click -- before anything is validated -- and BOTH of its
         *       answers submitted a turn of their own, including the declining one: a runtime sweep
         *       recorded one `POST /api/v1/transactions` carrying `"confirmation":"N"` per decline,
         *       answered 200 with nothing written, and painted `Unable to Add Transaction...` at an
         *       operator who had just prevented the write. Emptying `trigger` moves the decision to open
         *       into the one gate, and `onCancel` is then a purely local dismissal with no wire turn --
         *       which is what L176-L181 does.
         * WHY : ⚠️ Assumptions: the trigger sits in its OWN row at the end of the form and is left-aligned
         *       with the fields above it, and it is NOT `type="primary"`. The sweep measured it
         *       bottom-RIGHT and solid-primary, which made it the third primary control in a frame that
         *       already carries the legend's Enter and F5 and put the screen's own action on the opposite
         *       side from every other screen's.
         * WHY : Assumptions: `placement` opens the overlay BELOW its trigger and flush with its leading
         *       edge. The sweep measured the previous overlay covering 179 pixels of the very
         *       confirmation input it asked about and sitting four pixels from the viewport edge; opening
         *       downward from a control that is itself below that input covers neither.
         * WHY : Assumptions: the overlay's TITLE is `Confirm to add this transaction...`, the sentence
         *       L176-L181 moves into the message line on exactly this turn, and NOT the on-screen prompt
         *       beside the control above. The two were the same string before, which put one verbatim
         *       sentence on the screen twice and made it both the overlay's name and the input's
         *       accessible name; the sweep recorded both.
         */}
        <Flex gap="middle" wrap align="flex-start">
          <Popconfirm
            open={confirming}
            trigger={[]}
            placement="bottomLeft"
            title={ADD_MESSAGES.CONFIRM_TO_ADD_THIS_TRANSACTION}
            okText={CONFIRMATION_ANSWERS.CONFIRM}
            cancelText={CONFIRMATION_ANSWERS.DECLINE}
            /*
             * WHY : ⚠️ Assumptions: the DECLINING control takes focus, so the keystroke an overlay trains
             *       an operator to press -- a bare Enter on a freshly raised surface -- dismisses rather
             *       than commits. This surface writes a money-bearing record, and a surface whose default
             *       answer is the irreversible one turns a reflex keystroke into a write.
             * WHY : Alternatives Considered: focusing the confirming control, which is antd's own default
             *       and what an earlier draft relied on. Rejected for the reason above; the cost is one
             *       extra keystroke on the affirmative path, which is the cheaper side of the trade.
             */
            cancelButtonProps={{ autoFocus: true, style: CONFIRMATION_CONTROL_STYLE }}
            okButtonProps={{ style: CONFIRMATION_CONTROL_STYLE }}
            /*
             * WHY : ⚠️ Refactoring Rationale: a dismissed surface is DESTROYED rather than kept hidden,
             *       which is what makes the `autoFocus` above true on every opening rather than only the
             *       first. The platform applies that attribute when a control ENTERS the document, and
             *       the design system keeps a dismissed overlay mounted, so a re-opening re-shows a
             *       declining control that never left and no mount re-applies it. A browser pass caught
             *       exactly this asymmetry on this screen: raised from the legend on a fresh mount the
             *       focus was on the declining answer, and raised again by pointer after a dismissal it
             *       stayed on the anchor -- so the two routes to one gate disagreed about where the next
             *       keystroke would land, on the screen whose whole point is that they agree.
             *       Trade-offs: one extra mount per question, on a path already behind a network write.
             *       The dismissal still animates -- the flag reaches the leave motion as `removeOnLeave`
             *       in `@rc-component/dialog/lib/Dialog/Content/index.js`, so removal waits for it.
             */
            destroyOnHidden
            {...(resolvedKeys === null
              ? {}
              : {
                  /*
                   * WHY : Purpose: NAME the record being committed. The reference's confirming turn
                   *       re-displays the whole populated map -- `VALIDATE-INPUT-KEY-FIELDS` has already
                   *       written the resolved pair back into both key fields at L209 and L223 -- so an
                   *       operator confirming could see what they were committing.
                   * WHY : ⚠️ Assumptions: the pair is read from `resolvedKeys`, the SERVICE's answer, and
                   *       never from the two key controls. Reading the controls would describe whatever
                   *       is keyed, including a card the operator typed that the service discards in
                   *       favour of the account's own -- which is exactly what L209 overwrites -- and
                   *       would do so with the authority of a service answer.
                   * WHY : Assumptions: the card number is the service's MASKED rendering, adopted
                   *       verbatim; no masking happens in this file. AAP section 0.4.1.9 reduces a
                   *       primary account number everywhere except the administrative card-detail
                   *       endpoint, so the sixteen digits never reach this browser to be reduced.
                   * WHY : Assumptions: this member is ABSENT rather than empty when no turn has resolved
                   *       the pair, which a pointer can reach because opening issues no request. Naming
                   *       the keyed values instead would present an unresolved card with a service's
                   *       authority behind it.
                   */
                  description: (
                    <Flex vertical>
                      <Typography.Text style={fixedPitchStyle}>
                        {`${TRANSACTION_ADD_FIELD_LABELS.accountId} ${resolvedKeys.accountId}`}
                      </Typography.Text>
                      <Typography.Text style={fixedPitchStyle}>
                        {`${TRANSACTION_ADD_FIELD_LABELS.cardNumber} ${resolvedKeys.cardNumberMasked}`}
                      </Typography.Text>
                      <Typography.Text style={fixedPitchStyle}>
                        {`${TRANSACTION_ADD_FIELD_LABELS.amount} ${values.amount}`}
                      </Typography.Text>
                    </Flex>
                  ),
                })}
            onConfirm={confirmAndCommit}
            onCancel={declineLocally}
          >
            <Button ref={confirmationAnchor} onClick={requestConfirmation} disabled={busy}>
              {TRANSACTION_ADD_TITLE}
            </Button>
          </Popconfirm>
        </Flex>
        {/*
         * WHY : ⚠️ Purpose: an in-flight turn is stated to an operator who cannot see the screen. This
         *       screen already reports it VISUALLY and thoroughly -- every control and every key
         *       binding carries `disabled` while `busy` -- and none of that is announced: a disabled
         *       control reads as unavailable with no reason, and the reason is the whole message.
         * WHY : Assumptions: the region is rendered on every turn and holds the empty string while
         *       idle, which is what `busyAnnouncement` produces for an undefined announcement. A live
         *       region has to be in the accessibility tree before its content changes for the change
         *       to be announced, so rendering it only while busy would lose the transition into the
         *       busy state, which is the one that matters.
         * WHY : Assumptions: `busy` is the flag and `confirming` is deliberately NOT, even though the
         *       controls are disabled for both. `confirming` means the screen is WAITING FOR THE
         *       OPERATOR, and the overlay it raises is itself a dialog that takes focus and states its
         *       question -- announcing "working on your request" over an unanswered question would be
         *       false.
         */}
        {busyAnnouncement(busy ? REQUEST_IN_PROGRESS : undefined)}
      </Form>
      {/*
       * Assumptions: the legend the shell paints from this screen's delegated bindings dispatches through
       * the same `invoke` a real key press does, so a clicked legend control and its key cannot diverge.
       */}
    </Flex>
  );
}

/*
 * WHY : Assumptions: this module publishes the component under BOTH keys, and each has a caller. The
 *       DEFAULT export is the shape this file's own contract fixes, and the NAMED export is the shape
 *       `ui/src/router.tsx` consumes -- its lazy loaders read a named export off the imported module and
 *       republish it under `default`, which is the only shape `React.lazy` accepts, so the three card
 *       screens are all reached that way. Publishing one alias of one component satisfies both without
 *       creating the re-export barrel the screen conventions forbid, and there is only ever one component
 *       to keep in step because the second key is the same binding rather than a second declaration.
 */
export default TransactionAddScreen;
