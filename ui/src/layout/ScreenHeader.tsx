/**
 * The persistent CardDemo screen title band - the browser replacement for the
 * status header that every 3270 mapset paints above its content.
 *
 * Purpose
 * -------
 * Every screen in the baseline opens with the same band: a transaction slot and
 * a program slot on the left, the two shared application titles in the centre,
 * and the paint-time date and time on the right. That band is constant across
 * the mapsets, so it is authored once here and composed by the app shell rather
 * than repeated in each of the 21 screen routes. AAP section 0.4.4 fixes this
 * split: the title band, the message line and the function-key legend are the
 * three shared shell elements, and this module is the first of them.
 *
 * Provenance
 * ----------
 * All geometry below was measured from the reference-only baseline, never
 * estimated. Sources, read and never modified:
 *
 * - `app/bms/COSGN00.bms` L29-L93 - the widest form of the band, and the only
 *   mapset carrying a third row.
 * - `app/cpy-bms/COSGN00.CPY` L17-L66 - the symbolic field set that fixes each
 *   slot's declared width: `TRNNAME PIC X(4)`, `TITLE01 PIC X(40)`,
 *   `CURDATE PIC X(8)`, `PGMNAME PIC X(8)`, `TITLE02 PIC X(40)`,
 *   `CURTIME PIC X(9)`, `APPLID PIC X(8)`, `SYSID PIC X(8)`.
 * - `app/cpy/COTTL01Y.cpy` L17-L24 - the two title constants, held by
 *   `ui/src/messages/messages.ts` and imported from there.
 * - `app/cbl/COSGN00C.cbl` L177-L204 (`POPULATE-HEADER-INFO`) and
 *   `app/cbl/COMEN01C.cbl` L238-L257 - the two variants of the routine that
 *   fills the band.
 * - `app/cpy/CSDAT01Y.cpy` L17-L41 - the working-storage date and time
 *   structures whose edited forms reach the two right-hand slots.
 *
 * Assumptions: the band is TWO rows, not three, and the measurement rather than
 * the shape of any one mapset is what establishes that. Parsing rows 1 to 3 of
 * all 17 base mapsets yields three distinct signatures: 16 mapsets paint a
 * two-row band with 5-character prompts (`Tran:`) and their value slots at
 * column 7, while `app/bms/COSGN00.bms` alone paints 6-character prompts
 * (`Tran :`), value slots at column 8, and a third row carrying `APPLID` and
 * `SYSID`. The migration plan's shorthand describes that single outlier, so
 * reproducing it literally would give 16 of 17 screens a row the baseline never
 * showed them. See {@link RETIRED_HEADER_FIELDS} for the third row's
 * disposition and {@link HEADER_PROMPT_LABELS} for the prompt spelling.
 *
 * What this module owns, and what it must not
 * -------------------------------------------
 * It owns the four status-line prompt words and the two paint-time display
 * formats. `ui/src/messages/messages.ts` L158-L172 delegates the prompts here
 * explicitly - it holds every other user-visible string in the tree and names
 * this file as the single source of truth for `Tran:`, `Date:`, `Prog:` and
 * `Time:` - so declaring them here honours that catalog's boundary instead of
 * breaching it. Everything else stays where it already lives: the title
 * constants in the message catalog, design values in `ui/src/theme/tokens.ts`,
 * the theme object in `ui/src/theme/antdTheme.ts`, its injection in
 * `ui/src/App.tsx`, the message line in `ui/src/layout/MessageBand.tsx`,
 * function-key semantics in `ui/src/layout/usePfKeys.ts`, and identity in
 * `ui/src/hooks/useAuth.ts`. This module therefore holds no identity state, no
 * message text and no navigation, and it instantiates no `ConfigProvider`.
 *
 * One obligation runs the other way, from this module OUT to whoever composes it:
 * the app shell must pass {@link ScreenHeaderProps.now} a SERVER-derived instant.
 * It is stated here as well as on the prop because a shell author reads a module's
 * contract before its prop list, and this is the one input whose omission changes
 * observable behaviour rather than only appearance - the band silently falls back
 * to the browser's clock and zone, where the baseline read one region clock in one
 * zone for every terminal. That fallback is a registered divergence, D-7 in
 * section 7.2 of `docs/architecture/cobol-to-service-traceability.md`, and the
 * prop is the whole of its remedy. The obligation is deliberately NOT enforced by
 * making the prop required: this component must stay renderable in isolation for
 * tests, and a required clock input would make every test that does not care about
 * time supply one anyway. The trade is that the contract is documented rather than
 * type-checked, which is why it is written in both places.
 *
 * WHY (non-obvious design decisions)
 * ----------------------------------
 * Assumptions: the two title constants are imported rather than written here,
 * and that indirection is load-bearing rather than tidiness. `COTTL01Y.cpy`
 * declares three `PIC X(40)` constants, and the value of the middle one is NOT
 * on the line after its `PIC` clause: line 21 holds a variant reading
 * "Credit Card Demo Application (CCDA)" that is disabled by a `*` in column 7,
 * and the live value is on line 22. Both are exactly 40 characters, so neither
 * width nor shape distinguishes them and only the column-7 indicator does.
 * `ui/src/messages/messages.ts` resolves that trap once - its `TITLE02` entry
 * records `lines: [22]` and documents why line 21 is not catalogued - so
 * importing from it means the file cannot be re-introduced here by a later
 * editor reading the copybook top-down.
 *
 * Assumptions: no sign-off text is rendered by this module, and the reason is
 * worth stating because the baseline puts a tempting third constant next to the
 * two titles. Two different sign-off strings exist and they differ in three
 * ways at once. `CCDA-THANK-YOU` (`app/cpy/COTTL01Y.cpy` L23-L24, `PIC X(40)`,
 * "Thank you for using CCDA application... ") sits in the same 01-level group
 * as both titles but is referenced by ZERO programs - grepping the whole `app`
 * tree outside its own copybook returns nothing - so painting it into the band
 * because it is the third `X(40)` constant in the group would show text the
 * baseline never showed. `CCDA-MSG-THANK-YOU` (`app/cpy/CSMSG01Y.cpy` L18-L19,
 * `PIC X(50)`, literal 49 characters, "Thank you for using CardDemo
 * application...      ") is live at exactly one site,
 * `app/cbl/COSGN00C.cbl:89` - and there it is moved to `WS-MESSAGE` and emitted
 * by `SEND-PLAIN-TEXT` (L162-L172), an `EXEC CICS SEND TEXT ... ERASE` that
 * clears the screen and returns. It is a full-screen sign-off surface, not a
 * band element. Neither string belongs here; both are preserved separately and
 * unmerged in the message catalog as `THANK_YOU_CCDA` and `THANK_YOU_CARDDEMO`.
 *
 * Alternatives Considered: laying the three zones out with `Row` and `Col`
 * rather than with nested `Flex` alone. `Col` was chosen because the 24-column
 * grid maps the measured geometry exactly: the prompts occupy columns 1-20 of
 * 80, the 40-wide title field columns 21-60, and the right-hand slots columns
 * 61-80, which is 20/40/20 and therefore spans of 6/12/6. A nested `Flex` with
 * `justify="space-between"` was the alternative and was rejected because it
 * distributes free space instead of reserving a centre band, so the title stops
 * being centred as soon as the side slots hold strings of unequal length - which
 * they always do, the left holding a route id and the right a date. `Col` also
 * supplies the responsive behaviour design gap G1 calls for; see the note on the
 * breakpoint below.
 *
 * Trade-offs: the declared slot widths - `X(4)` for the transaction slot, `X(8)`
 * for the program slot - are documented but NOT enforced by truncation. In the
 * baseline the width is the field, so a longer value could not be shown; in a
 * proportional-font browser column, truncating to 4 or 8 characters would
 * discard information to imitate a constraint that no longer exists. Design gap
 * G1 already records that pixel-for-character positioning is deliberately not
 * preserved, and enforcing a character count while abandoning the character grid
 * would keep the cost of the old geometry without its benefit. Long values wrap
 * or ellipsise under the layout instead.
 */

import { Col, Flex, Row, Typography, theme } from "antd";
import dayjs from "dayjs";
import { useId } from "react";
import type { CSSProperties, ReactElement } from "react";

import {
  APP_ORGANISATION_TITLE_DISPLAY,
  APP_TITLE_DISPLAY,
} from "../messages/messages";
import { BMS_COLOR_TOKENS, TYPOGRAPHY_TOKENS } from "../theme/tokens";

/**
 * The four status-line prompt words the band paints beside its value slots,
 * verbatim from the mapsets.
 *
 * Refactoring Rationale: these four strings live here rather than in
 * `ui/src/messages/messages.ts`, and that is a ratified split rather than an
 * oversight in the catalog's coverage. The catalog is the single owner of every
 * user-visible string this tree carries across, and it draws its own boundary
 * explicitly: at L157-L160 it names `Tran:`, `Date:`, `Prog:` and `Time:` among
 * the literals it does NOT hold, and at L168-L171 it assigns them to this module
 * by name, on the stated ground that a `.bms` `INITIAL=` value belongs to the
 * component that renders it while a copybook constant or program literal belongs
 * to the catalog. `ui/src/layout/PfKeyBar.tsx` carries the same reciprocal
 * citation for the function-key legends, which the catalog delegates in the same
 * sentence. The citation is repeated here, on the symbol itself, because the
 * module header alone is not where a reader who greps for one of these four
 * strings lands - and without it the split reads as a gap in the catalog rather
 * than as its boundary.
 *
 * Trade-offs: what is accepted is that the answer to "which module owns this
 * string?" depends on where the baseline holds it, so no single module is total.
 * The alternative - absorbing the mapset text into the catalog - was rejected by
 * the catalog itself for a reason that applies directly to these four: a prompt
 * word is positional, meaningless apart from the slot it sits beside, so
 * cataloguing it centrally separates it from the only thing that gives it meaning
 * and invites the prompt and its value slot to drift apart. The risk this leaves
 * is duplication, and it is contained by the citation above: both modules point at
 * the same delegation, so a future editor moving one has to read the other.
 *
 * Assumptions: this is the 16-of-17 spelling, and the comparison is exact because
 * all 17 base mapsets carry these four literals on the SAME four lines - the
 * `INITIAL=` operands at L33, L46, L56 and L69 - so the only difference between
 * them is the operand text and its declared width. 16 mapsets declare `LENGTH=5`
 * with no space before the colon (`app/bms/COACTVW.bms` L31/L33, L44/L46,
 * L54/L56, L67/L69); `app/bms/COSGN00.bms` alone declares `LENGTH=6` on the same
 * four lines, reading `Tran :`, `Prog :`, `Date :` and `Time :`. The two forms
 * differ only by one padding space in front of the colon, which carries no meaning
 * in either the 3270 or the browser rendering, so the majority spelling is adopted
 * for the one shared component rather than parameterising the band on which
 * mapset it is standing in for.
 *
 * Trade-offs: the trailing colon is kept inside each value instead of being
 * appended at the render site. It is part of the measured literal, so keeping it
 * means a test can compare these strings to the mapset bytes directly; the cost
 * is that a future non-colon presentation would have to change the data rather
 * than the template.
 */
export const HEADER_PROMPT_LABELS = {
  /** `Tran:` - `app/bms/COACTVW.bms` L33, beside the transaction slot. */
  transaction: "Tran:",
  /** `Prog:` - `app/bms/COACTVW.bms` L56, beside the program slot. */
  program: "Prog:",
  /** `Date:` - `app/bms/COACTVW.bms` L46, beside the paint-time date. */
  date: "Date:",
  /** `Time:` - `app/bms/COACTVW.bms` L69, beside the paint-time time. */
  time: "Time:",
} as const satisfies Record<
  "transaction" | "program" | "date" | "time",
  string
>;

/**
 * `dayjs` format producing the baseline's 8-character `mm/dd/yy` date exactly.
 *
 * Assumptions: the two-digit year is the baseline's own choice and is preserved
 * rather than modernised. `POPULATE-HEADER-INFO` builds the value with
 * `MOVE WS-CURDATE-YEAR(3:2) TO WS-CURDATE-YY`
 * (`app/cbl/COSGN00C.cbl:188`), taking the last two characters of a `PIC 9(04)`
 * year into the `WS-CURDATE-MM-DD-YY` group of `app/cpy/CSDAT01Y.cpy` L30-L35,
 * whose `FILLER`s supply the two slashes. That group is exactly 8 characters
 * wide, matching the `CURDATE` field's `LENGTH=8`.
 *
 * Trade-offs: a four-digit year would be an improvement in isolation and is
 * deliberately not adopted, because AAP section 0.9.1 requires functional parity
 * and treats a change to a user-visible field as an intentional divergence that
 * must be registered before it ships. Widening the year here would produce a
 * 10-character value in a slot the baseline sized at 8 and would need such a
 * registration; keeping `MM/DD/YY` needs none, so the ambiguity of a two-digit
 * year is accepted as the faithful reading.
 */
export const HEADER_DATE_FORMAT = "MM/DD/YY" as const;

/**
 * `dayjs` format producing the baseline's 8-character 24-hour `hh:mm:ss` time.
 *
 * Assumptions: `HH` is 24-hour and that is a fidelity requirement, not a
 * preference. The value comes from `WS-CURTIME-HOURS`, a `PIC 9(02)` filled from
 * `FUNCTION CURRENT-DATE` (`app/cbl/COSGN00C.cbl:179`), which returns a 24-hour
 * hour; `hh` would render 13:05 as 01:05 and silently lose the distinction from
 * 01:05, with nothing failing.
 *
 * Assumptions: the `Ahh:mm:ss` initial value on `app/bms/COSGN00.bms` L74 is a
 * design-time placeholder that never reaches a user, so the leading `A` is not
 * reproduced. That mapset sizes `CURTIME` at `LENGTH=9`, but the program moves
 * `WS-CURTIME-HH-MM-SS` into it - a group of exactly 8 characters
 * (`app/cpy/CSDAT01Y.cpy` L36-L41: two digits, colon, two digits, colon, two
 * digits) - so at run time the field holds 8 characters and one trailing space
 * and the `A` is overwritten before display. The other 16 mapsets size the same
 * field at `LENGTH=8` with an `hh:mm:ss` initial value, which corroborates the
 * 8-character reading independently.
 */
export const HEADER_TIME_FORMAT = "HH:mm:ss" as const;

/**
 * A baseline header slot that this component deliberately does not render.
 *
 * Purpose: records a dropped slot as data rather than only as prose, so that a
 * test can assert the drop was decided and a reader can tell an intentional
 * omission from an oversight.
 */
export interface RetiredHeaderField {
  /** The BMS field name, so the mapping back to the mapset stays exact. */
  readonly field: string;
  /** Width from the symbolic map's `PIC X(n)` clause. */
  readonly declaredWidth: number;
  /** Where the field is declared, and how widely it occurs. */
  readonly source: string;
  /** What the field carried in the baseline. */
  readonly baselineMeaning: string;
  /** Why no target value is rendered in its place. */
  readonly disposition: string;
}

/**
 * The two header slots that are dropped, with the evidence for dropping them.
 *
 * Alternatives Considered: repurposing both slots to carry deployment identity -
 * the environment name in `APPLID` and a build or task identifier in `SYSID` -
 * was the obvious way to keep two populated slots. It was rejected on two
 * independent grounds. First, this component cannot read that identity without
 * acquiring a dependency it should not have: `ui/tsconfig.json` deliberately
 * sets `types: []` at L157 so `process.env` is unavailable, and reaching for
 * `import.meta.env` would make a shared presentational component the owner of
 * deployment configuration. Second, and more importantly, it would put a
 * different kind of value in a slot whose baseline meaning was the CICS region
 * and system identity, which reads as parity while delivering something else -
 * a worse outcome than an honest omission.
 *
 * Trade-offs: what is given up is the at-a-glance "which region am I on" cue,
 * and the loss is smaller than it first appears. Both slots exist on exactly ONE
 * of the 17 base mapsets, and `EXEC CICS ASSIGN APPLID` occurs in exactly one
 * program in the whole repository - `app/cbl/COSGN00C.cbl` L198-L204 - so 16 of
 * 17 screens never showed either value. `app/cbl/COMEN01C.cbl` L238-L257 is the
 * same routine with those two statements absent, which is the direct evidence.
 * An environment banner is the right home for that cue if it is wanted later;
 * the per-screen title band is not.
 */
export const RETIRED_HEADER_FIELDS = [
  {
    field: "APPLID",
    declaredWidth: 8,
    source:
      "app/bms/COSGN00.bms L80-L83 and app/cpy-bms/COSGN00.CPY L55-L60; 1 of 17 base mapsets.",
    baselineMeaning:
      "CICS region application identifier, obtained by EXEC CICS ASSIGN APPLID at app/cbl/COSGN00C.cbl L198-L200.",
    disposition:
      "Dropped. The CICS region has no target analogue; AAP section 0.2.2 retires the region's own artifacts rather than porting them.",
  },
  {
    field: "SYSID",
    declaredWidth: 8,
    source:
      "app/bms/COSGN00.bms L89-L93 and app/cpy-bms/COSGN00.CPY L61-L66; 1 of 17 base mapsets.",
    baselineMeaning:
      "CICS system identifier, obtained by EXEC CICS ASSIGN SYSID at app/cbl/COSGN00C.cbl L202-L204.",
    disposition:
      "Dropped for the same reason as APPLID. Its BMS initial value is already eight blanks, so the slot renders empty until CICS fills it.",
  },
] as const satisfies readonly RetiredHeaderField[];

/**
 * Props for {@link ScreenHeader}.
 *
 * Alternatives Considered: deriving the two left-hand slots from the router with
 * `useLocation` instead of accepting them as props. Rejected because it would
 * couple a presentational shell component to a router context, making it
 * unrenderable in isolation and untestable without a provider, and because the
 * screen that owns a route is the component that knows what to call itself. The
 * shared titles go the other way and are NOT props: they are identical on all 21
 * screens, so passing them in would invite 21 opportunities to pass something
 * else.
 */
export interface ScreenHeaderProps {
  /**
   * Short identifier for the current screen, rendered in the `TRNNAME` slot.
   *
   * Alternatives Considered: dropping this slot together with `APPLID` and
   * `SYSID`. It was kept because the slot's function and its carrier are
   * separable. The carrier is gone: `TRNNAME` held a CICS transaction id, a
   * compile-time literal such as `WS-TRANID PIC X(04) VALUE 'CC00'`
   * (`app/cbl/COSGN00C.cbl:37`), and AAP section 0.7.1 eliminates the
   * server-side transaction and program fields outright - "no server-side 'next
   * program' field exists at all in the target". The function survives: the band
   * told a user, and a support desk, which screen was on the glass. That is
   * orientation, it is still needed, and the honest substitute is an identifier
   * the calling screen supplies about itself rather than one echoed back from
   * session state - which is precisely the property section 0.7.1 is protecting.
   */
  readonly transactionId: string;
  /**
   * Name of the screen currently rendered, shown in the `PGMNAME` slot.
   *
   * Assumptions: the baseline value was a load-module name
   * (`WS-PGMNAME PIC X(08) VALUE 'COSGN00C'`, `app/cbl/COSGN00C.cbl:36`) and the
   * target has no load modules, so this carries the screen or route name that
   * plays the same role. It is documented on the same reasoning as
   * {@link ScreenHeaderProps.transactionId}.
   */
  readonly programName: string;
  /**
   * Instant to display in the date and time slots. Defaults to the current time.
   *
   * Assumptions: the baseline captures this once per screen paint -
   * `POPULATE-HEADER-INFO` runs `MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA`
   * on each `SEND MAP` (`app/cbl/COSGN00C.cbl:179`, reached from
   * `SEND-SIGNON-SCREEN` at L147) - so the value is the paint instant and does
   * not advance while the screen is displayed. A `Date` is accepted rather than
   * a `Dayjs` so that consumers and tests need no `dayjs` types of their own.
   *
   * Clock and timezone policy, stated because the default is NOT the faithful
   * value. The baseline reads one clock in one zone: `FUNCTION CURRENT-DATE`
   * returns the CICS region's local time, so every operator of a given region saw
   * the same wall clock whatever their own machine said. Omitting this prop reads
   * the BROWSER's clock instead, and formats it in the BROWSER's zone - two
   * substitutions rather than one, because a client machine can be both skewed and
   * in a different zone from the service. The consequence is user-visible in the
   * exact slot the source sized at 8 characters: two operators looking at one
   * record can read two different dates across a midnight boundary.
   *
   * Assumptions: the omitted case is therefore for isolated rendering - a test, or
   * a screen viewed before an instant is available - and the app shell that
   * composes this band is expected to pass a SERVER-derived instant, which is the
   * only value that reproduces the single-clock property the baseline had. This
   * component deliberately does not fetch one itself: a presentational shell
   * element that acquired a clock source would own configuration it has no other
   * reason to know about, and would become unrenderable in isolation.
   *
   * Trade-offs: the divergence is bounded rather than removed, and it is bounded
   * here rather than hidden. Defaulting to no value at all - rendering the slots
   * blank until a caller supplies an instant - was the alternative, and it was
   * rejected because the baseline never showed an empty date, so a blank slot
   * replaces a small, documented inaccuracy with a visible absence. Under AAP
   * section 0.9.1 this is an intentional behavioural change, so it is registered as
   * **D-7 - the header clock and the zone it is read in** in section 7.2 of
   * `docs/architecture/cobol-to-service-traceability.md`, which is the single
   * divergence register; that entry carries the baseline citations, the accepted
   * cost and the verification contract, and it records that this band has no golden
   * master because the online programs cannot run without a CICS runtime. The
   * formats themselves stay verbatim, so only the clock and the zone differ, never
   * the shape of what is rendered.
   */
  readonly now?: Date;
}

/**
 * Renders the shared two-row title band above a screen's content.
 *
 * The band reproduces the measured rows 1 and 2 of the base mapsets: the
 * transaction and program slots on the left, the two shared application titles
 * in the centre, and the paint-time date and time on the right.
 *
 * Assumptions: the clock is read once per render and never on an interval,
 * matching the baseline exactly. A ticking clock would be a behavioural change,
 * because `POPULATE-HEADER-INFO` runs per screen send and the displayed time is
 * therefore the paint instant; it would also re-render the whole band every
 * second for a value the baseline never refreshed.
 * @param {ScreenHeaderProps} props the component's props, destructured below
 * @param {string} props.transactionId short identifier for the current screen,
 *   rendered in the `TRNNAME` slot
 * @param {string} props.programName name of the screen currently rendered,
 *   shown in the `PGMNAME` slot
 * @param {Date | undefined} props.now instant to display in the date and time
 *   slots; the current time is used when omitted
 * @returns {ReactElement} the title-band element, exposed as a named region
 *   whose accessible name is the application title
 */
export function ScreenHeader(props: ScreenHeaderProps): ReactElement {
  const { transactionId, programName, now } = props;

  // Assumptions: the token names come from ui/src/theme/tokens.ts and their
  // values from the theme installed by ui/src/App.tsx, so no colour or font
  // value is written here. useToken is the only way to read a token that no
  // component prop exposes, and BLUE is exactly that case: antd's Typography
  // type prop accepts secondary, success, warning and danger and has no primary
  // variant, while the mapsets paint every prompt and slot value COLOR=BLUE,
  // which the bridge maps to colorPrimary.
  //
  // Alternatives Considered: the `token` member of the same hook, which is the
  // obvious one to reach for and was measured before being rejected. It returns
  // RESOLVED values - `colorPrimary` comes back as `#1677ff` - so writing it into
  // a style attribute bakes today's palette into the element and takes it off the
  // CSS-variable surface that antd 6 themes through. tokens.ts states the
  // consequence precisely: a literal value does not merely duplicate a token, it
  // opts the component out of the theme silently, so a later token change leaves
  // it behind and nothing fails. `cssVar` returns the reference form instead -
  // `var(--ant-color-primary)` - so the element keeps following the theme, and
  // the scoping class antdTheme.ts requests through `cssVar: { key: "carddemo" }`
  // is what supplies the variable's value.
  const { cssVar } = theme.useToken();

  // Assumptions: useId supplies a value that is unique per mounted instance and
  // stable across renders, which aria-labelledby requires. A hard-coded id was
  // rejected because the app shell may legitimately render the band more than
  // once - a screen behind a modal, for instance - and duplicate ids would make
  // the association resolve to whichever element happened to come first.
  const titleId = useId();

  // Trade-offs: dayjs is called explicitly for the undefined case rather than
  // relying on dayjs(undefined) returning the current instant. The shorter form
  // works, but it makes correctness depend on a library's handling of a missing
  // argument, and a reader has to know that behaviour to see that the default
  // is intentional.
  const paintedAt = now === undefined ? dayjs() : dayjs(now);

  const promptStyle: CSSProperties = { color: cssVar[BMS_COLOR_TOKENS.BLUE] };

  // Assumptions: the value slots are rendered in the code face because the
  // baseline's cell grid aligned them for free and the two right-hand slots are
  // fixed-width numeric data - MM/DD/YY and HH:mm:ss. In a proportional face
  // the digits differ in advance width, so the two rows' colons and slashes
  // stop lining up vertically between row 1 and row 2.
  const valueStyle: CSSProperties = {
    color: cssVar[BMS_COLOR_TOKENS.BLUE],
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

  return (
    // Alternatives Considered: a bare <header> with aria-labelledby, and a
    // role="banner". Both were rejected. Per ARIA in HTML a <header> nested
    // inside main, section, article, aside or nav maps to a generic role rather
    // than to a landmark, and this band always renders inside the shell's
    // content region, so the element alone would expose no landmark at all;
    // banner was rejected because it is the page-level introductory landmark and
    // should appear once per document, whereas this band is per screen.
    // role="region" plus an accessible name is the landmark that matches what
    // the band is, and a region is only exposed to assistive technology when it
    // is named - which aria-labelledby supplies from the title heading below.
    // The header element is kept underneath so the DOM still says what the block
    // is to anyone reading or querying it.
    <Flex
      component="header"
      role="region"
      aria-labelledby={titleId}
      vertical
      // Assumptions: gap takes antd's semantic size rather than a number, so the
      // value resolves through the theme's spacing scale. A number here would be
      // a pixel literal and would opt this element out of the theme silently.
      gap="small"
    >
      <Row align="middle">
        {/*
         * Assumptions: the spans encode the measured geometry rather than a
         * visual guess. The prompts occupy columns 1-20 of the 80-column row,
         * the title field columns 21-60 and the right-hand slots columns 61-80,
         * so 20/40/20 of 80 becomes 6/12/6 of antd's 24. span applies at every
         * width and md overrides it at screenMD, which is the breakpoint design
         * gap G1 nominates; below it the three zones stack rather than crush,
         * which preserves reading order where the fixed grid could only clip.
         */}
        <Col span={24} md={6}>
          <Flex gap="small" align="baseline" wrap>
            <Typography.Text style={promptStyle}>
              {HEADER_PROMPT_LABELS.transaction}
            </Typography.Text>
            <Typography.Text style={valueStyle}>
              {transactionId}
            </Typography.Text>
          </Flex>
        </Col>
        <Col span={24} md={12}>
          <Flex justify="center">
            {/*
             * Assumptions: the organisation line is a Text and not a second
             * heading. Both title constants are 40-wide COLOR=YELLOW fields at
             * the same column and are visually equal in the baseline, but they
             * do not play the same role: TITLE01 names the AWS programme the
             * demo belongs to, which is attribution, while TITLE02 names the
             * application and is the value ui/index.html renders as its document
             * title. Rendering both as headings would put two same-level
             * headings in the band with no parent, and rendering this one as the
             * heading would name the region after the programme rather than the
             * application. ui/src/App.tsx already draws the same split.
             * The trimmed alias is used because the 40-character literal centres
             * its text with leading spaces for a monospaced cell grid; in this
             * proportional layout the Flex above does the centring and those
             * spaces would only offset it.
             */}
            <Typography.Text type="warning">
              {APP_ORGANISATION_TITLE_DISPLAY}
            </Typography.Text>
          </Flex>
        </Col>
        <Col span={24} md={6}>
          <Flex gap="small" align="baseline" justify="flex-end" wrap>
            <Typography.Text style={promptStyle}>
              {HEADER_PROMPT_LABELS.date}
            </Typography.Text>
            <Typography.Text style={valueStyle}>
              {paintedAt.format(HEADER_DATE_FORMAT)}
            </Typography.Text>
          </Flex>
        </Col>
      </Row>
      <Row align="middle">
        <Col span={24} md={6}>
          <Flex gap="small" align="baseline" wrap>
            <Typography.Text style={promptStyle}>
              {HEADER_PROMPT_LABELS.program}
            </Typography.Text>
            <Typography.Text style={valueStyle}>{programName}</Typography.Text>
          </Flex>
        </Col>
        <Col span={24} md={12}>
          <Flex justify="center">
            {/*
             * Assumptions: level 4 is a design-system mapping fixed by AAP
             * section 0.3.2, not a judgement about document outline depth. The
             * band occupies one of 24 rows in the baseline, so a larger heading
             * would consume vertical space on screens whose field count reaches
             * 128 - the reasoning tokens.ts records for screenTitleSize. Because
             * the level is chosen for size, the outline correctness is carried
             * separately: this is the only heading in the band, and its id names
             * the enclosing region through aria-labelledby, so the region is
             * announced by the application title rather than by its depth.
             * type="warning" is how COLOR=YELLOW reaches this element - antd
             * resolves it to the colorWarning token that the bridge maps
             * YELLOW to - so the mapping travels through a declared component
             * prop and needs no style of its own.
             * Alternatives Considered: setting fontSize and lineHeight here from
             * the bridge's screenTitleSize and screenTitleLineHeight entries.
             * Rejected because level={4} is already the instruction that makes
             * antd apply exactly those two tokens - fontSizeHeading4 and
             * lineHeightHeading4 - so restating them would duplicate the
             * component's own decision and, being a style on the element, would
             * override rather than configure it. That is why those two entries
             * are satisfied here without appearing as identifiers: the prop is
             * the reference. Writing them explicitly would also freeze the pair
             * at render time, so a later theme that re-derived the heading scale
             * would move the component's internal value and leave this override
             * behind, silently.
             */}
            <Typography.Title level={4} type="warning" id={titleId}>
              {APP_TITLE_DISPLAY}
            </Typography.Title>
          </Flex>
        </Col>
        <Col span={24} md={6}>
          <Flex gap="small" align="baseline" justify="flex-end" wrap>
            <Typography.Text style={promptStyle}>
              {HEADER_PROMPT_LABELS.time}
            </Typography.Text>
            <Typography.Text style={valueStyle}>
              {paintedAt.format(HEADER_TIME_FORMAT)}
            </Typography.Text>
          </Flex>
        </Col>
      </Row>
    </Flex>
  );
}
