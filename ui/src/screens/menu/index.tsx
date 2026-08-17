/**
 * @file The main menu screen, migrated from `app/cbl/COMEN01C.cbl` and its mapset
 * `app/bms/COMEN01.bms` (map `COMEN1A`, `DFHMDI ... SIZE=(24,80)`), mounted at the `/menu` route.
 *
 * Purpose
 * -------
 * Paint the eleven ordinary-operator options, take an option number, and enter the screen that option
 * names. It replaces CICS transaction `CM00`, which `app/cbl/COMEN01C.cbl` L37 declares as this
 * program's own transaction identifier and `app/csd/CARDDEMO.CSD` L399-L400 maps to the program.
 *
 * Why this screen had to exist
 * ---------------------------
 * Refactoring Rationale: seven authored screens navigated to `/menu` and nothing was mounted there, so
 * every PF3 in the application resolved to the router's not-found result. `ui/src/routes/navigation.ts`
 * recorded the gap honestly at its `MAIN_MENU_ROUTE` constant -- naming the reference's real destination
 * and noting that no screen occupied it yet -- and this module closes it. The option table itself was
 * already transcribed: `MAIN_MENU_OPTIONS` in `ui/src/messages/messages.ts` carries all eleven entries
 * from `app/cpy/COMEN02Y.cpy` with their padded names, their target program names and their user types,
 * so nothing about the menu's CONTENT is authored here.
 *
 * What replaces the option dispatch
 * --------------------------------
 * Assumptions: the reference dispatches with `EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(...))` at
 * `app/cbl/COMEN01C.cbl` L157-L160 and L186-L189, which Transformation Rule T5 maps to a client route
 * change. The program name is therefore resolved to a route through {@link MAIN_MENU_DESTINATIONS}
 * rather than being sent anywhere, and an option whose screen this delivery does not mount answers with
 * the reference's OWN unavailable-option sentence rather than with an invented one -- see that constant
 * for which options those are and why the reference's sentence is the right one to borrow.
 *
 * This screen holds no session state
 * ---------------------------------
 * Assumptions: the reference is pseudo-conversational and carries `CDEMO-FROM-TRANID`,
 * `CDEMO-FROM-PROGRAM` and `CDEMO-PGM-CONTEXT` across every turn -- it sets all three immediately
 * before each `XCTL` -- and none of that survives. Navigation is the router's history, identity is the
 * validated token, and the re-entry discriminator has no counterpart at all: a stateless screen that
 * answers with a message has no first-entry-versus-later-turn distinction to draw. The two moves at
 * `app/cbl/COMEN01C.cbl` L181-L182 that would have re-asserted identity on dispatch are commented out
 * in the reference itself, which is the corroborating evidence that identity was carried rather than
 * re-derived; here it is re-derived from the signed claim on every request.
 */

import { Button, Flex, Input, Typography, theme } from 'antd';
import type { InputRef } from 'antd';
import { useRef, useState } from 'react';
import type { CSSProperties, ChangeEvent, ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { useAuth } from '../../hooks/useAuth';
import { useServerInstant } from '../../hooks/useServerInstant';
import { MessageBand } from '../../layout/MessageBand';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { PfKeyBar } from '../../layout/PfKeyBar';
import { ScreenHeader } from '../../layout/ScreenHeader';
import { usePfKeys } from '../../layout/usePfKeys';
import {
  INVALID_KEY_PRESSED,
  MAIN_MENU_HEADINGS,
  MAIN_MENU_KEY_LABELS as CATALOGUED_MAIN_MENU_KEY_LABELS,
  MAIN_MENU_OPTIONS,
  MAIN_MENU_OPTION_COUNT,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  formatMessageTemplate,
} from '../../messages/messages';
import type { MainMenuOption } from '../../messages/messages';
import { SIGN_ON_ROUTE } from '../../routes/guards';
import { navigateSafely } from '../../routes/navigation';
import { routeForProgram } from '../../routes/programRoutes';
import { TYPOGRAPHY_TOKENS } from '../../theme/tokens';
import { ScreenTitle } from '../../layout/ScreenTitle';

/** CICS transaction identifier this screen replaces, from `app/cbl/COMEN01C.cbl` L37. */
export const MAIN_MENU_TRANSACTION_ID = 'CM00';

/** Source program name, from `app/cbl/COMEN01C.cbl` L36. */
export const MAIN_MENU_PROGRAM_NAME = 'COMEN01C';

/** Mapset this screen stands in, which fixes the message band's rendered width. */
export const MAIN_MENU_MAPSET = 'COMEN01';

/*
 * WHY : ⚠️ Refactoring Rationale: the three painted strings below are now ALIASES of the message
 *       catalog's own entries, where each was previously an inlined literal repeating text
 *       `ui/src/messages/messages.ts` already owned. Two of them duplicated
 *       `MAIN_MENU_HEADINGS.SCREEN` and `.OPTION_PROMPT`, and the third declared a local
 *       `MAIN_MENU_KEY_LABELS` that SHADOWED the catalog export of exactly the same name -- so
 *       `ui/src/screens/menuScreens.test.tsx` imported the screen's copy while
 *       `ui/src/screens/menu/menu.test.tsx` imported the catalog's, and the two agreed only because
 *       their literals happened to match. A correction applied to one spelling would have left the
 *       other asserting the old text and one of the two suites would still have passed, which is the
 *       divergence the single-owner rule exists to prevent.
 *       Alternatives Considered: deleting these re-exports and having each caller read the catalog
 *       directly. Rejected because three test modules import `MAIN_MENU_SUBTITLE`,
 *       `MAIN_MENU_PROMPT` and `MAIN_MENU_KEY_LABELS` from THIS module by name, and the screen is
 *       lazily loaded -- `ui/src/routerRoutes.test.tsx` records that importing a constant from a
 *       lazily-loaded screen is deliberately avoided in production code, so the names stay published
 *       here for the tests while the TEXT has exactly one owner.
 */

/** Row-4 sub-title, from the `DFHMDF ... INITIAL='Main Menu'` at `app/bms/COMEN01.bms` L75-L79. */
export const MAIN_MENU_SUBTITLE = MAIN_MENU_HEADINGS.SCREEN;

/** Prompt beside the option control, from `app/bms/COMEN01.bms` L140-L144. */
export const MAIN_MENU_PROMPT = MAIN_MENU_HEADINGS.OPTION_PROMPT;

/**
 * Declared width of the `OPTION` control, from `app/bms/COMEN01.bms` L145-L148.
 *
 * Assumptions: two positions, and the control is `ATTRB=(FSET,IC,NORM,NUM,UNPROT)` -- so it is
 * numeric-only, it holds the initial cursor, and it cannot accept a third character. All three
 * properties are carried below rather than only the width.
 */
export const MAIN_MENU_OPTION_WIDTH = 2;

/**
 * Digits the composed option line renders the option number in.
 *
 * Assumptions: declared separately from the option FIELD's width above even though both are two, and
 * deliberately so. That one is the entry control's `maxLength`, from the mapset's `PIC X(02)` input
 * field; this one is the display width of `app/cpy/COMEN02Y.cpy L95`'s `PIC 9(02)`, which the reference emits
 * with `STRING ... DELIMITED BY SIZE`. Folding them into one constant would tie a rendered width to an
 * accepted width, and a change to either would silently move the other.
 */
const OPTION_NUMBER_DIGITS = 2;

/**
 * The two legend labels this mapset paints on row 24, from `app/bms/COMEN01.bms` L158-L162.
 *
 * Assumptions: the mapset paints ONE literal, `'ENTER=Continue  F3=Exit'` at `LENGTH=23`, and the
 * catalog stores it already split at the double space the reference used as its separator, because
 * `ui/src/layout/PfKeyBar.tsx` renders one control per key. The split is the catalog's so that the
 * arithmetic tying the two halves back to the 23-character field is checked in one place.
 */
export const MAIN_MENU_KEY_LABELS = CATALOGUED_MAIN_MENU_KEY_LABELS;

/**
 * Route each option's target program is entered at, or `null` when this delivery cannot enter it.
 *
 * Assumptions: the map is keyed by PROGRAM NAME rather than by option number, because the program name
 * is what `app/cpy/COMEN02Y.cpy` stores and what the reference dispatches on. Keying it by number would
 * make the table disagree with the copybook the moment an option was renumbered.
 *
 * Assumptions: a `null` answers with the reference's own unavailable-option sentence, and the reference
 * establishes that this is the right answer rather than an invented one. `app/cbl/COMEN01C.cbl`
 * L146-L167 issues `EXEC CICS INQUIRE PROGRAM` before transferring to `COPAUS0C` and, when the program
 * is not installed in the region, composes exactly `'This option ' <name> ' is not installed...'` into
 * the message field. So the reference already has a defined behaviour for an option whose target is
 * absent, and this delivery reuses it rather than inventing one or disabling the control.
 *
 * ⚠️ Refactoring Rationale: this map is now DERIVED from `ui/src/routes/programRoutes.ts` rather than
 * written out here, and the correction fixed two defects at once. It was previously a second,
 * hand-maintained table of program-to-route pairs, which is exactly what that module's own docstring
 * says must not exist -- it states the resolution is "declared once here rather than inside either
 * menu, because both menus need it, `ui/src/router.tsx` needs the same set of destinations to declare
 * its routes, and a route-closure test needs to compare the two". Worse, the two tables DISAGREED:
 * `PROGRAM_ROUTES` resolves `COCRDSLC` and `COCRDUPC` to the card browse, while the copy here answered
 * `null` for both, so options 4 and 5 reported themselves not installed even though the delivery
 * carries a screen that serves them. Deriving the map makes that class of disagreement unrepresentable
 * instead of merely fixed, and it retires the dead code -- nothing imported `programRoutes.ts` at all
 * before this, so the module documented as the single source of truth had no callers.
 *
 * Two different reasons produce a `null`, and they are worth separating even though they share one
 * sentence. The first is a screen this delivery does not carry at all. The second, which is now the only
 * one in force, is a screen that IS carried but is addressable only by a value the menu has not got:
 * the card detail and card update screens sit behind an opaque selector that `ui/src/routes/cards.ts`
 * seals and only the card browse mints -- `ui/src/api/cards.ts` records why a card number may not travel
 * in a path at all -- and the transaction view sits behind a transaction identifier. A menu option
 * carries no selection, so for those there is no selector-free address to send an operator to.
 *
 * Trade-offs: `COCRDSLC` and `COCRDUPC` therefore both enter the card BROWSE rather than a single-card
 * path, which is the registered divergence `D-CARD-SELECTOR` recorded on `PROGRAM_ROUTES`. The
 * published contract keys one card by an opaque selector the service mints, so `/cards/:cardKey` and
 * `/cards/:cardKey/edit` -- both of which `ui/src/router.tsx` does register -- cannot be addressed
 * before a card number has been exchanged for a selector, and the browse is where that exchange
 * happens. Sending these options to the owning list route is what the reference does in substance: its
 * own first turn of `COCRDSLC` is an empty map the operator types an account and card number into, and
 * the browse is the migrated equivalent of that turn. The cost accepted is that three options share one
 * destination.
 *
 * Assumptions: option 7 is the single option whose program `PROGRAM_ROUTES` still omits, and it keeps
 * answering the not-installed sentence. Its screen IS mounted -- `ui/src/router.tsx` registers
 * `/transactions/:id` -- but that path takes a transaction identifier and a menu option has none, so
 * registering the pattern here would send an operator to a literal `:id` segment and thence to the
 * router's own not-found result, which is a worse answer than the reference's own words. The transaction
 * list, the reports screen and bill payment were once omitted for the other reason and no longer are:
 * each is authored, each is mounted at a literal path, and each is registered.
 */
export const MAIN_MENU_DESTINATIONS: Readonly<Record<string, string | null>> = Object.freeze(
  Object.fromEntries(
    MAIN_MENU_OPTIONS.map(
      /**
       * Pairs one option's target program with the route this delivery reaches it at.
       * @param {MainMenuOption} option - One entry of the transcribed option table.
       * @returns {readonly [string, string | null]} The program name and its route, or `null` when
       *   this delivery carries no screen for that program.
       */
      (option) => [option.programName, routeForProgram(option.programName)] as const,
    ),
  ),
);

/** Matches an entry consisting only of decimal digits, which is COBOL's `IS NUMERIC` on a `PIC X`. */
const DIGITS_ONLY = /^[0-9]+$/u;

/** The blank the reference's `INSPECT` replaces, and the character it replaces it with. */
const ENTRY_PAD_CHARACTER = ' ';

/** The digit the reference's `INSPECT ... REPLACING ALL ' ' BY '0'` substitutes for each blank. */
const ENTRY_ZERO_CHARACTER = '0';

/**
 * Program-name prefix marking an option the reference reports as not yet delivered.
 *
 * Assumptions: five characters, because `app/cbl/COMEN01C.cbl` L169 tests
 * `CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) = 'DUMMY'` -- a reference-substring comparison against the
 * first five positions of the `PIC X(08)` program name, so a name such as `DUMMY01` matches while a
 * real program name never does.
 */
const COMING_SOON_PROGRAM_PREFIX = 'DUMMY';

/**
 * Character-widths added to the option control's declared width for its own chrome.
 *
 * Assumptions: an antd `Input` spends horizontal space on its internal padding, its border and the
 * caret, none of which is part of the field's declared capacity. Sizing the control to exactly its
 * character count would therefore clip the second digit rather than show it. Six is the smallest
 * allowance that keeps both digits and the caret visible at the theme's `controlHeight` padding, and it
 * is expressed in the same `ch` unit as the width so it scales with the font rather than fixing a pixel
 * gap that would stop being right at another font size.
 */
const OPTION_CONTROL_CHROME_CH = 6;

/**
 * Keeps the 25-character prompt on the one line the mapset places it on.
 *
 * Assumptions: `white-space` is set through a style rather than through a `Typography.Text` prop
 * because antd 6 publishes no `nowrap` prop on that component -- its `ellipsis` prop truncates the text
 * instead of preserving it, which would drop characters from a string Transformation Rule T8 requires
 * verbatim. `nowrap` is a structural CSS keyword rather than a design value, so it carries none of the
 * colour, spacing, radius or typography concerns the zero-hardcoded-values rule governs; the prompt's
 * colour and size still come entirely from the theme.
 */
const OPTION_PROMPT_STYLE: CSSProperties = { whiteSpace: 'nowrap' };

/**
 * Normalises a typed option entry exactly as `app/cbl/COMEN01C.cbl` L117-L124 normalises its field.
 *
 * Purpose: reproduce the reference's four-step preparation of `OPTIONI` so that every spelling of one
 * option resolves to the same option number, and so that the two-digit form the reference echoes back
 * into the field is available to the caller.
 *
 * The reference performs, in order: a backward scan from `LENGTH OF OPTIONI` for the last non-blank
 * with a floor of position 1; a `MOVE` of that leading prefix into `WS-OPTION-X`, which L45 declares
 * `PIC X(02) JUST RIGHT`, so the prefix is RIGHT-justified into two positions; an
 * `INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'`; and a `MOVE` to `WS-OPTION PIC 9(02)`.
 *
 * Assumptions: `JUST RIGHT` is the load-bearing clause and the whole rule turns on it. The behaviour
 * was verified empirically rather than reasoned about -- the exact L117-L124 sequence was compiled and
 * run under GnuCOBOL 3.2.0, the compiler and `--std=ibm-strict` dialect this repository's own suite
 * uses, and it produced: `'1 '`→`'01'`, `' 1'`→`'01'`, `'01'`→`'01'`, `'11'`→`'11'`, `'  '`→`'00'`,
 * `'12'`→`'12'`, `'2 '`→`'02'`, `'10'`→`'10'`. So a single typed digit selects THAT digit's option,
 * not the digit followed by a zero. `app/bms/COMEN01.bms` L147 independently declares
 * `JUSTIFY=(RIGHT,ZERO)` on the same field, so the mapset and the program agree and this rule is not
 * optional.
 *
 * ⚠️ Refactoring Rationale: an earlier revision of this module did not transcribe the rule at all. It
 * trimmed the entry and parsed it, and justified the omission by asserting the reference's intent was
 * "not determinable" -- that a single typed digit "becomes that digit followed by a zero", so typing
 * `1` might mean option 10, Bill Payment. That reading omits `JUST RIGHT` from L45, and the measurement
 * above disproves it outright: there is no ambiguity to resolve. The trimming implementation happened
 * to agree with the reference on every entry the option domain admits, so no observable behaviour was
 * wrong, but the stated reason was false and the two-digit echo it never produced is a real omission.
 * Transcribing the rule replaces a false rationale with a measured one and supplies that echo.
 *
 * Trade-offs: the prefix is right-justified with `padStart` rather than by modelling a COBOL `MOVE`
 * into a justified field in general. This helper serves one two-position field, so a general
 * fixed-width justifier would be unused generality; `ui/src/messages/messages.ts` already owns the
 * one other COBOL string operation this screen needs, `DELIMITED BY`, and a second such primitive
 * belongs beside it rather than in a screen if a second caller ever appears.
 * @param {string} raw - What the operator typed into the two-position option control. May be shorter
 *   than the field, longer than it, blank, or contain characters the `NUM` attribute would refuse.
 * @returns {string} Exactly {@link MAIN_MENU_OPTION_WIDTH} characters, right-justified and
 *   blank-filled with zeros -- the value the reference holds in `WS-OPTION-X` and echoes as `OPTIONO`.
 *   Non-digit input is preserved so the caller's numeric test can refuse it, which is what the
 *   reference's `IS NOT NUMERIC` test does with the same value.
 */
export function normaliseOptionEntry(raw: string): string {
  /*
   * WHY : Assumptions: the value is first fitted to the FIELD, because the reference scans a
   *       fixed-width `PIC X(02)` rather than a variable-length string. CICS delivers a short entry in
   *       the leading positions with the remainder blank, which is why `'1'` is scanned as `'1 '`; a
   *       `MOVE` of an over-long value to `PIC X(02)` truncates on the right. An `Input` bounded by
   *       `maxLength` cannot exceed the width, so the truncation is unreachable from the screen and is
   *       kept only because this function is exported and tested directly.
   */
  const field = raw
    .slice(0, MAIN_MENU_OPTION_WIDTH)
    .padEnd(MAIN_MENU_OPTION_WIDTH, ENTRY_PAD_CHARACTER);

  /*
   * WHY : Assumptions: the scan floors at the first position rather than reporting "no data found",
   *       which is what `OR WS-IDX = 1` does in the reference's `PERFORM VARYING ... BY -1`. An
   *       all-blank field therefore yields the one-character prefix `' '`, which the zero-fill below
   *       turns into `'00'` -- the value the reference's own `WS-OPTION = ZEROS` arm refuses. Treating
   *       a blank field as a separate empty case would need a second refusal path where the reference
   *       has one.
   */
  let lastNonBlank = 1;
  for (let position = MAIN_MENU_OPTION_WIDTH; position >= 1; position -= 1) {
    if (field.charAt(position - 1) !== ENTRY_PAD_CHARACTER) {
      lastNonBlank = position;
      break;
    }
  }

  return field
    .slice(0, lastNonBlank)
    .padStart(MAIN_MENU_OPTION_WIDTH, ENTRY_PAD_CHARACTER)
    .split(ENTRY_PAD_CHARACTER)
    .join(ENTRY_ZERO_CHARACTER);
}

/** One outcome of interpreting a typed option number. */
interface OptionOutcome {
  /** Route to enter, or `null` when the entry produced a message instead. */
  readonly destination: string | null;
  /** Message to paint on row 23, or `null` when the entry is being acted on. */
  readonly message: string | null;
  /** Severity the reference painted that message in. */
  readonly severity: MessageBandSeverity;
  /**
   * The normalised two-digit entry the reference writes back into the field.
   *
   * Assumptions: it is carried on the outcome rather than recomputed by the caller, so the value
   * displayed and the value acted on are the same string by construction. `app/cbl/COMEN01C.cbl` L125
   * moves `WS-OPTION` to `OPTIONO` BEFORE any validation, so the echo happens on every turn including
   * a refused one -- see the render site for why that ordering is preserved.
   */
  readonly echo: string;
}

/**
 * Interprets one typed option number against the option table and this delivery's route map.
 *
 * Assumptions: the entry is validated as the reference validates it, in the reference's order -- not
 * numeric, or greater than the populated count, or zero, all answer
 * `'Please enter a valid option number...'` (`app/cbl/COMEN01C.cbl` L128-L134). The count is the
 * length of {@link MAIN_MENU_OPTIONS}, which `ui/src/messages/messages.ts` keeps equal to the
 * reference's own `CDEMO-MENU-OPT-COUNT`, so the bound is the copybook's rather than a literal here.
 *
 * Assumptions: the administrator-only refusal at `app/cbl/COMEN01C.cbl` L136-L143 is transcribed and is
 * currently unreachable, and both halves of that are deliberate. It is transcribed because the
 * reference tests `CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'` on every dispatch and the check belongs
 * with the dispatch; it is unreachable because the live copybook sets every one of the eleven entries
 * to `'U'`, as `MAIN_MENU_OPTIONS` records. Dropping it would mean re-deriving it if an option were
 * ever restricted, and would leave the one refusal the catalog holds for this program with no caller.
 *
 * ⚠️ Refactoring Rationale: the entry is normalised through {@link normaliseOptionEntry}, which
 * transcribes `app/cbl/COMEN01C.cbl` L117-L124. An earlier revision trimmed and parsed instead, on the
 * stated ground that the reference's rule was "not determinable"; that claim is false and the helper's
 * docstring records the GnuCOBOL measurement that disproves it. The observable option numbers are
 * unchanged -- both readings agree on every entry the domain admits -- but the normalised form is now
 * available to echo back, which the reference does and the previous revision did not.
 *
 * Refactoring Rationale: the refusal RETURNS rather than falling through. The reference does not
 * short-circuit: L127-L134 sets the error flag and sends the screen, then L136-L143 still evaluates
 * `CDEMO-MENU-OPT-USRTYPE(WS-OPTION)` with a subscript that may be zero or past the table, and only
 * `IF NOT ERR-FLG-ON` at L145 keeps it from dispatching. Returning early is observably identical --
 * the operator sees one refusal either way -- and removes a subscript overrun that survives in the
 * reference only because its table happens to sit next to addressable storage.
 *
 * Assumptions: the count bound is `MAIN_MENU_OPTION_COUNT`, which is the copybook's own
 * `CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 11` at `app/cpy/COMEN02Y.cpy` L21, rather than the length of
 * the option array. The two agree at eleven, but they mean different things: the `REDEFINES` at L93-L98
 * declares `OCCURS 12 TIMES` while only eleven entries are populated, so the COUNT is the bound the
 * reference tests against and the ARITY is the storage it was carved from. Testing against the array
 * length would silently start admitting a twelfth option if the array ever grew to match the arity.
 * @param {string} entry - What the operator typed into the two-position option control.
 * @param {boolean} isAdmin - Whether the signed-on operator holds the administrative group.
 * @returns {OptionOutcome} The route to enter, or the message to paint and its severity, together with
 *   the normalised two-digit entry to echo back into the field.
 */
export function resolveMenuOption(entry: string, isAdmin: boolean): OptionOutcome {
  const normalised = normaliseOptionEntry(entry);
  /*
   * WHY : Assumptions: the numeric test is applied to the NORMALISED value, not to the typed one,
   *       because that is the item the reference tests -- L127's `WS-OPTION IS NOT NUMERIC` reads the
   *       `PIC 9(02)` that L124 has just been moved into. The distinction is observable for a non-digit
   *       entry: `'x'` normalises to `'0x'`, which fails the test, whereas trimming and parsing would
   *       have refused `'x'` on a different ground and reached the same refusal by luck.
   */
  const number = DIGITS_ONLY.test(normalised) ? Number.parseInt(normalised, 10) : Number.NaN;
  if (Number.isNaN(number) || number === 0 || number > MAIN_MENU_OPTION_COUNT) {
    /*
     * WHY : Assumptions: one sentence for all three arms, because `app/cbl/COMEN01C.cbl` L127-L134
     *       tests not-numeric, above-count and zero as a SINGLE condition emitting one message.
     *       Splitting them into three would tell the operator more than the reference does and would
     *       imply three rules where the reference has one.
     */
    return {
      destination: null,
      message: SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER,
      severity: 'error',
      echo: normalised,
    };
  }
  /*
   * WHY : Assumptions: the entry is read through the declared `MainMenuOption` type rather than through
   *       the literal type the `as const` table infers, and the annotation is load-bearing rather than
   *       decorative. Every live entry carries `'U'`, so the inferred type of `userType` is the single
   *       literal `'U'` and the administrator-only comparison below would be rejected by the compiler as
   *       provably false -- which is the compiler correctly observing what this function's own docstring
   *       records. Reading through the interface restores the field's DECLARED domain, which
   *       `app/cpy/COMEN02Y.cpy` states as `'A'` or `'U'`, so the transcribed check compiles and stays
   *       correct for an entry a later revision restricts.
   */
  const option: MainMenuOption | undefined = MAIN_MENU_OPTIONS[number - 1];
  if (option === undefined) {
    /*
     * WHY : Assumptions: this arm exists because the bound above is the copybook's COUNT while the
     *       array is a separate object, so a number inside the count but past a shortened array would
     *       otherwise index `undefined`. It answers the same refusal as the count test rather than a
     *       distinct one, because from the operator's side it is the same fault: an option number the
     *       menu does not offer. It is unreachable while the array holds all eleven entries.
     */
    return {
      destination: null,
      message: SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER,
      severity: 'error',
      echo: normalised,
    };
  }
  if (!isAdmin && option.userType === 'A') {
    return {
      destination: null,
      message: PROGRAM_MESSAGES.COMEN01C.NO_ACCESS_ADMIN_ONLY_OPTION,
      severity: 'error',
      echo: normalised,
    };
  }
  /*
   * WHY : Assumptions: the coming-soon arm is transcribed from `app/cbl/COMEN01C.cbl` L169-L176, which
   *       tests `CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) = 'DUMMY'` -- a reference-name prefix rather
   *       than a route condition, so it is tested against the option's own program name here too. No
   *       live entry in `app/cpy/COMEN02Y.cpy` names a `DUMMY` program, so this arm cannot fire today.
   *       Refactoring Rationale: it is transcribed anyway, for the same reason as the administrator
   *       refusal below it -- the reference evaluates it on every dispatch, the catalog holds a distinct
   *       sentence for it, and omitting it would drop observable behaviour that a later copybook
   *       revision could reach while leaving that catalogued sentence with no caller at all.
   * WHY : ⚠️ Trade-offs: the two unavailable-option sentences are NOT unified, and the asymmetry is the
   *       reference's. The not-installed composer delimits the option name by TWO spaces and its
   *       closing literal carries a LEADING space; this one delimits by a SINGLE space and its closing
   *       literal has NO leading space, so it renders as "This option Accountis coming soon ..." --
   *       both the truncation at the first word and the missing word break are what L172-L175 emits.
   *       Repairing either would be more readable and would break verbatim fidelity, which
   *       Transformation Rule T8 forbids; the catalog carries both forms separately for this reason.
   * WHY : ⚠️ Assumptions: the severity is `success`, not a warning, even though the sentence reads like
   *       one. L171 moves `DFHGREEN` into `ERRMSGC` for this message while L162 moves `DFHRED` for the
   *       not-installed message, so the colour is a per-message property of the reference and
   *       `MessageBand`'s `'success'` variant is the token bridge's mapping of `DFHGREEN`.
   */
  if (option.programName.startsWith(COMING_SOON_PROGRAM_PREFIX)) {
    return {
      destination: null,
      message: formatMessageTemplate(MESSAGE_TEMPLATES.MENU_OPTION_COMING_SOON, {
        'CDEMO-MENU-OPT-NAME': option.name,
      }),
      severity: 'success',
      echo: normalised,
    };
  }
  const destination = MAIN_MENU_DESTINATIONS[option.programName] ?? null;
  if (destination === null) {
    /*
     * WHY : Assumptions: the option NAME is inserted through the catalog's template rather than
     *       concatenated here, so the double-space delimiter the reference uses to strip the name's
     *       35-character padding is applied by the one function that models `DELIMITED BY`. Writing
     *       the sentence inline would either carry the padding into the band or trim it with a rule
     *       the reference does not use.
     * WHY : Assumptions: the severity is `error`, because `app/cbl/COMEN01C.cbl` L162 moves `DFHRED`
     *       into the message field's colour attribute for this sentence. The admin menu composes a
     *       near-identical sentence in `DFHGREEN`, so the colour is per-program rather than per-message
     *       and cannot be inferred from the wording.
     */
    return {
      destination: null,
      message: formatMessageTemplate(MESSAGE_TEMPLATES.MENU_OPTION_NOT_INSTALLED, {
        'CDEMO-MENU-OPT-NAME': option.name,
      }),
      severity: 'error',
      echo: normalised,
    };
  }
  return { destination, message: null, severity: 'error', echo: normalised };
}

/**
 * Renders the eleven ordinary-operator options and enters the one selected.
 * @returns {ReactElement} The main menu screen.
 */
export function MainMenuScreen(): ReactElement {
  const navigate = useNavigate();
  const { isAdmin, signOut } = useAuth();
  /*
   * WHY : Assumptions: `cssVar` rather than `token` from the same hook, for the reason every screen in
   *       this tree records -- `token` holds RESOLVED values, so writing one into a style attribute
   *       bakes today's palette into the element and opts it out of the CSS-variable surface antd 6
   *       themes through, while `cssVar` holds `var(--...)` references that keep following the theme.
   */
  const { cssVar } = theme.useToken();
  // WHY : Assumptions: read unconditionally at the top of the component, because the rules of hooks
  //       require it and because the band shows a PAINT-time instant -- which is the property the
  //       reference had, since `POPULATE-HEADER-INFO` re-read the clock on each `SEND MAP` rather than
  //       on a timer.
  const paintedAt = useServerInstant();
  const optionControl = useRef<InputRef | null>(null);

  const [entry, setEntry] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');

  /**
   * Interprets the typed option and either enters its screen or paints the reference's message.
   *
   * ⚠️ Assumptions: the normalised two-digit value is written BACK into the field, which
   * `app/cbl/COMEN01C.cbl` L125 does with `MOVE WS-OPTION TO OPTIONO` before it validates anything.
   * That makes the echo observable on every turn -- an operator who types `1` and submits sees the
   * field redisplay as `01`, and a refused entry is redisplayed in the same normalised form rather than
   * as it was typed. An earlier revision of this module omitted the echo entirely, so the field kept
   * whatever had been typed and the two-digit form the reference shows never appeared.
   * Trade-offs: the echo is applied even when the outcome is a route change, where the field is about
   * to unmount and nobody will see it. Suppressing it on that path would need a second branch to save
   * a state write on a component that is leaving, and would put the field and the acted-on value out of
   * step for the one frame between them.
   * @returns {void} Completion is represented by a route change or by this screen's own state.
   */
  function enterSelectedOption(): void {
    const outcome = resolveMenuOption(entry, isAdmin);
    setEntry(outcome.echo);
    setMessage(outcome.message);
    setSeverity(outcome.severity);
    if (outcome.destination !== null) {
      navigateSafely(navigate, outcome.destination);
    }
  }

  /**
   * Ends the session and returns to sign-on, which is what this program's PF3 does.
   *
   * Assumptions: the session is discarded as well as the route changed, because
   * `app/cbl/COMEN01C.cbl` L196-L203 transfers to `COSGN00C` with `XCTL PROGRAM(CDEMO-TO-PROGRAM)`
   * carrying NO `COMMAREA` clause -- the one transfer in the estate that omits it, so the next program
   * starts with `EIBCALEN = 0` and no identity at all. A route change alone would leave the token in
   * place and the guards would send the operator straight back.
   * @returns {void} Completion is represented by the discarded session and the route change.
   */
  function signOffToSignOn(): void {
    signOut();
    navigateSafely(navigate, SIGN_ON_ROUTE);
  }

  /*
   * WHY : Assumptions: exactly the two attention identifiers `app/cbl/COMEN01C.cbl` L96-L106 admits are
   *       bound, and every other one takes the `WHEN OTHER` arm, which moves `CCDA-MSG-INVALID-KEY`
   *       into the message field -- so an unmapped key is REPORTED on this screen, unlike the card
   *       detail screen where the same arm silently coerces the key into Enter.
   */
  const { bindings, invoke } = usePfKeys(
    {
      ENTER: {
        /**
         * Enters the selected option, which is this program's `PROCESS-ENTER-KEY`.
         * @returns {void} Completion is represented by a route change or by the painted message.
         */
        onInvoke: () => {
          enterSelectedOption();
        },
        label: MAIN_MENU_KEY_LABELS.ENTER,
      },
      PFK03: {
        /**
         * Signs off, which is this program's `RETURN-TO-SIGNON-SCREEN`.
         * @returns {void} Completion is represented by the discarded session and the route change.
         */
        onInvoke: () => {
          signOffToSignOn();
        },
        label: MAIN_MENU_KEY_LABELS.PFK03,
      },
    },
    {
      /**
       * Reports an unmapped key with the reference's own sentence.
       * @param {object} rejection - Why the key was not dispatched.
       * @returns {void} Completion is represented by the painted message.
       */
      onInvalidKey: (rejection) => {
        if (rejection.reason === 'unmapped') {
          setMessage(INVALID_KEY_PRESSED);
          setSeverity('error');
        }
      },
    },
  );

  /*
   * WHY : Assumptions: the option lines are rendered in the code face, because the reference paints
   *       them into twelve fixed 40-character fields on a character grid where every line started at
   *       the same column for free. A proportional face gives digits different advance widths, so the
   *       numbers and the names stop lining up down the list.
   */
  const optionLineStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

  /*
   * WHY : ⚠️ Refactoring Rationale: the option control is sized to the field it stands for, where it
   *       previously took whatever width the row had left. Runtime measurement found it rendering about
   *       1050 CSS pixels wide for a field `app/bms/COMEN01.bms` L145-L148 declares as `LENGTH=2`, which
   *       invites an operator to expect far more than the two characters `maxLength` will accept and
   *       leaves the prompt beside it too little room to stay on one line.
   *       Assumptions: the width is expressed in `ch` and DERIVED from `MAIN_MENU_OPTION_WIDTH`, so it
   *       is the copybook's own field width rather than a value chosen here -- the same constant that
   *       fixes `maxLength`. `1ch` is the advance width of the digit zero in the element's own font, so
   *       the control measures its declared character count plus an allowance for the control's internal
   *       padding and caret, and it follows the theme's font rather than freezing a pixel size. This is
   *       the ONE dimension design gap G1 keeps from the mapset: G1 abandons absolute row and column
   *       POSITIONING, and a field whose rendered size contradicts its declared capacity is a different
   *       defect from the positioning G1 gives up.
   *       Alternatives Considered: a `Col` span or a token-derived pixel width. Both were rejected
   *       because a span resolves against the row rather than against the field, and a pixel width
   *       stops matching the character count as soon as the theme's font size changes -- while `ch`
   *       tracks the font, which is what a character-cell field means.
   */
  const optionControlStyle: CSSProperties = {
    width: `${String(MAIN_MENU_OPTION_WIDTH + OPTION_CONTROL_CHROME_CH)}ch`,
  };

  /*
   * WHY : Alternatives Considered: how the three shared zones this mapset declares -- the rows 1-3
   *       title band, the row-23 message line and the row-24 key legend -- reach the frame. The
   *       alternative is `useShellSlot` from `ui/src/layout/AppShell.tsx`, which ten of this tree's
   *       screens use to DELEGATE those zones upward, and it was measured rather than assumed before
   *       being set aside. That shell renders each zone only when a screen has delegated it: the header
   *       is guarded by `activeScreen === undefined ? null :`, the band by
   *       `activeMessage === undefined ? null :`, and the legend is rendered unconditionally but
   *       `PfKeyBar` returns `null` for an empty binding list. Because this screen delegates nothing,
   *       the shell contributes none of the three and the components composed here are the only
   *       instances in the document -- so composing them locally does NOT produce the two message bands
   *       or two key legends that delegation exists to prevent, which was the risk worth checking.
   *       Trade-offs: composing locally keeps this screen inconsistent with the ten that delegate, and
   *       that cost is accepted for two reasons. Its own suites mount it WITHOUT the shell -- both
   *       `menu.test.tsx` and `menuScreens.test.tsx` render the bare screen under a `MemoryRouter` and
   *       assert on the band and the legend -- so delegating would leave those zones rendered by
   *       nothing and unassertable. And `ui/src/screens/admin/index.tsx`, the 167-line twin this mapset
   *       shares its shape, selector attributes and footer with, composes them the same way, so the two
   *       menus stay alike. Delegation is the better long-term shape and belongs in one change that
   *       moves both twins and their suites together, not in a change to one of them.
   */
  return (
    <Flex vertical gap="large">
      <ScreenHeader
        transactionId={MAIN_MENU_TRANSACTION_ID}
        programName={MAIN_MENU_PROGRAM_NAME}
        now={paintedAt}
      />
      <ScreenTitle>{MAIN_MENU_SUBTITLE}</ScreenTitle>
      {/*
        Trade-offs: the options are a LIST of text lines rather than a control each, and that is design
        gap G1 taken deliberately. The reference paints twelve protected 40-character fields and takes
        the selection through one separate numeric control, so the lines are not activatable on the
        terminal either -- turning them into buttons would add an affordance the reference never had and
        would leave the numeric control it does have with nothing to do. Each line is composed through
        the catalog's own `MENU_OPTION_LINE` template, so the number, the separator and the padded name
        are assembled exactly as `app/cbl/COMEN01C.cbl` L269-L271 assembles them.

        Assumptions: ELEVEN lines are rendered into a mapset that declares TWELVE slots, and the empty
        twelfth is not reproduced as a blank line. `app/bms/COMEN01.bms` L80-L139 defines `OPTN001`
        through `OPTN012` at rows 6 to 17, and `app/cpy/COMEN02Y.cpy` L93-L98 carves the option table as
        `OCCURS 12 TIMES` -- but L21 sets `CDEMO-MENU-OPT-COUNT` to 11, and `app/cbl/COMEN01C.cbl`
        L264-L265 loops `FROM 1 BY 1 UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT`, so the `WHEN 12` arm at
        L297-L298 is unreachable and `OPTN012O` keeps the `LOW-VALUES` L89 moved into the map. A 3270
        field holding low-values paints nothing, so the terminal shows eleven lines and one row of
        blanks that is indistinguishable from the gap above the prompt. Rendering an empty line here
        would add a visible element the terminal does not show; the arity is recorded instead, so a
        reader does not mistake the 11-of-12 gap for a dropped option.

        Assumptions: the anonymous zero-length field at `app/bms/COMEN01.bms` L150-L153 --
        `ATTRB=(ASKIP,NORM) COLOR=GREEN LENGTH=0 POS=(20,44)` -- is dropped with no counterpart. A
        zero-length 3270 field carries no data and exists only to terminate the unprotected option field
        that precedes it at `POS=(20,41)`, so that typing cannot run past two positions. `maxLength` on
        the control below enforces that bound directly, which leaves the terminator with nothing to do.
      */}
      <Flex vertical>
        {MAIN_MENU_OPTIONS.map(
          /**
           * Renders one option line exactly as the reference composes it.
           * @param {object} option - One entry of the transcribed option table.
           * @returns {ReactElement} The rendered line.
           */
          (option) => (
            <Typography.Text key={option.optionNumber} style={optionLineStyle}>
              {formatMessageTemplate(MESSAGE_TEMPLATES.MENU_OPTION_LINE, {
                /*
                 * WHY : ⚠️ Refactoring Rationale: the number is ZERO-FILLED to two characters, where it was
                 *       rendered with `String(...)` alone. The field it stands for is
                 *       `app/cpy/COMEN02Y.cpy L95` `PIC 9(02)`, and the reference emits it with
                 *       `STRING ... DELIMITED BY SIZE`, which takes the whole two characters of a display
                 *       numeric -- so the terminal paints `01. ` through `09. ` and this screen painted
                 *       `1. ` through `9. `. Nine of the eleven lines therefore differed from the reference
                 *       by one character and the list no longer aligned on the separator, which is the
                 *       whole reason the line is composed in the code face.
                 */
                'CDEMO-MENU-OPT-NUM': String(option.optionNumber).padStart(
                  OPTION_NUMBER_DIGITS,
                  '0',
                ),
                'CDEMO-MENU-OPT-NAME': option.name,
              })}
            </Typography.Text>
          ),
        )}
      </Flex>
      <Flex align="center" gap="small" wrap={false}>
        {/*
          Assumptions: the prompt is associated with the control through `aria-labelledby` rather than
          by proximity, because the terminal identified this field by its position at row 20 and
          position names nothing to a screen reader.

          ⚠️ Refactoring Rationale: the row is `wrap={false}` and the prompt carries `nowrap`, where
          neither was set before. Runtime measurement at 1280 and 1440 CSS pixels showed the 25-character
          prompt breaking across two visual lines -- rendering as "Please select an" / "option :" --
          because the control beside it claimed the whole remaining row and left the label a narrower
          measured width than its own text. `app/bms/COMEN01.bms` L140-L149 places the prompt and the
          field side by side on row 20, so a two-line prompt is a layout artifact rather than anything
          the mapset declares.
        */}
        <Typography.Text id="main-menu-option-label" style={OPTION_PROMPT_STYLE}>
          {MAIN_MENU_PROMPT}
        </Typography.Text>
        <Input
          id="main-menu-option"
          aria-labelledby="main-menu-option-label"
          ref={optionControl}
          value={entry}
          style={optionControlStyle}
          maxLength={MAIN_MENU_OPTION_WIDTH}
          // Assumptions: `autoFocus` carries the mapset's `IC` attribute, which names the one field
          // that holds the cursor on entry. This mapset sets it on the option control.
          autoFocus
          // Assumptions: `inputMode` is `numeric` rather than the control being an `InputNumber`,
          // because the field is `PIC X(02)` scanned as characters and its `NUM` attribute restricts
          // what may be TYPED rather than converting the value to a number. An `InputNumber` would
          // strip a leading zero, which the reference's own two-digit entries carry.
          inputMode="numeric"
          onChange={
            /**
             * Keeps only the digits the mapset's `NUM` attribute admits.
             * @param {ChangeEvent<HTMLInputElement>} event - The entry event.
             * @returns {void} Completion is represented by this screen's own state.
             */
            (event: ChangeEvent<HTMLInputElement>) => {
              setEntry(event.target.value.replace(/[^0-9]/gu, ''));
            }
          }
          onPressEnter={
            /**
             * Enters the selected option, so the keyboard workflow needs no function key.
             * @returns {void} Completion is represented by a route change or by the painted message.
             */
            () => {
              enterSelectedOption();
            }
          }
        />
        {/*
          Assumptions: a submit control is rendered beside the field as well as the Enter binding,
          because the legend the reference paints on row 24 is a legend rather than a control and a
          browser operator reaching this screen with a pointer has no other way to act on the entry.
          It carries the mapset's own `ENTER=Continue` label so no new wording is introduced.
        */}
        <Button type="primary" onClick={enterSelectedOption}>
          {MAIN_MENU_KEY_LABELS.ENTER}
        </Button>
      </Flex>
      <MessageBand mapset={MAIN_MENU_MAPSET} message={message} severity={severity} />
      <PfKeyBar keys={bindings} onInvoke={invoke} />
    </Flex>
  );
}

export default MainMenuScreen;
