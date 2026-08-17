/**
 * @file The administrative menu screen, migrated from `app/cbl/COADM01C.cbl` and its mapset
 * `app/bms/COADM01.bms` (map `COADM1A`, `DFHMDI ... SIZE=(24,80)`), mounted at the `/admin` route.
 *
 * Purpose
 * -------
 * Paint the six administrative options, take an option number, and enter the screen that option
 * names. It replaces CICS transaction `CA00`, which `app/cbl/COADM01C.cbl` L37 declares as this
 * program's own transaction identifier and `app/csd/CARDDEMO.CSD` L327-L328 maps to the program.
 *
 * Field reconciliation against the mapset
 * --------------------------------------
 * `app/bms/COADM01.bms` declares exactly 28 `DFHMDF` fields -- 20 named and 8 anonymous -- and every
 * one is accounted for below, either rendered here, owned by a named component, or dropped with a
 * reason. The split is 10 + 1 + 1 + 1 + 15 = 28:
 *
 * - 10 fields, the rows 1-2 title band: the `'Tran:'`, `'Date:'`, `'Prog:'` and `'Time:'` captions at
 *   L29, L42, L52 and L65 with `TRNNAME` L34, `TITLE01` L38, `CURDATE` L47, `PGMNAME` L57,
 *   `TITLE02` L61 and `CURTIME` L70. Rendered by `ui/src/layout/ScreenHeader.tsx` from the identity
 *   and paint instant this screen hands it; none is re-declared here.
 * - 1 field, `ERRMSG` at L154, `POS=(23,1) LENGTH=78 COLOR=RED`: rendered by
 *   `ui/src/layout/MessageBand.tsx`, which takes the text and the severity this screen decides.
 * - 1 field, the row-24 legend at L158-L162: rendered by `ui/src/layout/PfKeyBar.tsx` from the
 *   bindings `ui/src/layout/usePfKeys.ts` resolves.
 * - 1 field, the anonymous `LENGTH=0 POS=(20,44) COLOR=GREEN` spacer at L150-L153: DROPPED. A
 *   zero-length field carries no content and reserves one character cell on a fixed grid, and a
 *   browser layout reserves space through its own spacing primitives, so there is nothing to render.
 * - 15 fields rendered here: the `'Admin Menu'` caption at L75-L79, the twelve 40-character option
 *   slots `OPTN001`-`OPTN012` at L80-L139 -- six of which carry text, for the reason recorded beside
 *   the option list in the render below -- the `'Please select an option :'` prompt at L140-L144, and
 *   the `OPTION` entry control at L145-L149.
 *
 * How this screen differs from the main menu, and why
 * -------------------------------------------------
 * Assumptions: three differences from `ui/src/screens/menu/index.tsx` are the reference's own and none
 * of them is a simplification. There is NO per-option user-type check, because `app/cpy/COADM02Y.cpy`
 * genuinely omits the `USRTYPE` subfield the main-menu copybook carries at its own L98 -- this menu is
 * only reachable once the administrator check has passed, so a per-option type would be redundant, and
 * the `'No access - Admin Only option... '` sentence the main menu holds has no caller here and is not
 * borrowed. The unavailable-option sentence names NO option, because the two lines that would have
 * inserted `CDEMO-ADMIN-OPT-NAME` are commented out at both composition sites,
 * `app/cbl/COADM01C.cbl` L153-L154 and L274-L275. And it is painted in `DFHGREEN` rather than
 * `DFHRED` -- L151 and L272 against the main menu's L162 -- so the colour is a property of the
 * program rather than of the wording.
 *
 * This screen holds no session state
 * ---------------------------------
 * Refactoring Rationale: the reference is pseudo-conversational and switches on `CDEMO-PGM-CONTEXT`
 * at `app/cbl/COADM01C.cbl` L91-L96 to tell a first entry from a later turn, painting the map on the
 * first and receiving it on the rest. That discriminator has NO counterpart here, and its absence is
 * the point rather than an omission: a stateless screen that answers with a message has no turn count
 * to consult, so what is displayed follows from the last action alone. `CDEMO-FROM-TRANID`,
 * `CDEMO-FROM-PROGRAM` and `CDEMO-TO-PROGRAM`, which the reference sets immediately before each
 * transfer at L101 and L142-L144, are likewise gone: navigation is the router's history, identity is
 * the validated token's claim, and the administrative gate is `RequireAdmin` in
 * `ui/src/routes/guards.tsx`. This is AAP section 0.7.1 applied to this screen.
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
import { ScreenTitle } from '../../layout/ScreenTitle';
import { usePfKeys } from '../../layout/usePfKeys';
import {
  ADMIN_MENU_OPTIONS,
  INVALID_KEY_PRESSED,
  MESSAGE_TEMPLATES,
  SHARED_MESSAGES,
  formatMessageTemplate,
} from '../../messages/messages';
import type { AdminMenuOption } from '../../messages/messages';
import { SIGN_ON_ROUTE } from '../../routes/guards';
import { navigateSafely } from '../../routes/navigation';
import { TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/** CICS transaction identifier this screen replaces, from `app/cbl/COADM01C.cbl` L37. */
export const ADMIN_MENU_TRANSACTION_ID = 'CA00';

/** Source program name, from `app/cbl/COADM01C.cbl` L36. */
export const ADMIN_MENU_PROGRAM_NAME = 'COADM01C';

/** Mapset this screen stands in, which fixes the message band's rendered width. */
export const ADMIN_MENU_MAPSET = 'COADM01';

/*
 * WHY : Assumptions: the three painted literals below are transcribed from THIS mapset and are
 *       deliberately not taken from `ui/src/messages/messages.ts`, which owns every message the
 *       PROGRAM computes -- the refusal sentence, the unavailable-option sentence, the invalid-key
 *       sentence and the six option names all come from there. What the catalog carries for a menu's
 *       own painted text is keyed to the mapset it was read from: `MAIN_MENU_HEADINGS` and
 *       `MAIN_MENU_KEY_LABELS` cite `app/bms/COMEN01.bms` L79, L144 and L162, and there is no
 *       admin-keyed group beside them.
 * WHY : Assumptions: two of these three are BYTE-IDENTICAL to the main menu's, and reusing that
 *       screen's constants for them is exactly the de-duplication Transformation Rule T8 forbids.
 *       `app/bms/COADM01.bms` L144 and L162 are their own `DFHMDF` definitions in their own mapset;
 *       an admin screen reading a constant whose provenance says `COMEN01.bms` would misattribute
 *       them, and a later edit to one mapset would silently move the other screen. So each is
 *       declared here against the line it was read from, and the caption -- which is NOT identical --
 *       cannot be shared at all.
 */

/** Row-4 caption, from the `DFHMDF ... INITIAL='Admin Menu'` at `app/bms/COADM01.bms` L75-L79. */
export const ADMIN_MENU_SUBTITLE = 'Admin Menu';

/**
 * Prompt beside the option control, from `app/bms/COADM01.bms` L140-L144.
 *
 * Assumptions: the space before the colon is the mapset's own and is part of the 25 characters
 * `LENGTH=25` declares, so it is preserved rather than tidied away.
 */
export const ADMIN_MENU_PROMPT = 'Please select an option :';

/** Declared width of the `OPTION` control, from `LENGTH=2` at `app/bms/COADM01.bms` L145-L149. */
export const ADMIN_MENU_OPTION_WIDTH = 2;

/**
 * Digits the composed option line renders the option number in.
 *
 * Assumptions: declared separately from the option FIELD's width above even though both are two, and
 * deliberately so. That one is the entry control's `maxLength`, from the mapset's `PIC X(02)` input
 * field; this one is the display width of `app/cpy/COADM02Y.cpy` L57's `PIC 9(02)`, which the
 * reference emits with `STRING ... DELIMITED BY SIZE`. Folding them into one constant would tie a
 * rendered width to an accepted width, and a change to either would silently move the other.
 */
const OPTION_NUMBER_DIGITS = 2;

/**
 * Digit an unfilled option position is read as.
 *
 * Assumptions: this is the replacement character of `INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'` at
 * `app/cbl/COADM01C.cbl` L127, and it is what turns an untouched field into the zero the refusal
 * test at L133 rejects.
 */
const BLANK_POSITION_FILL = '0';

/** The two legend labels this mapset paints on row 24, from `app/bms/COADM01.bms` L158-L162. */
export const ADMIN_MENU_KEY_LABELS = {
  /** Enter continues into the chosen option. */
  ENTER: 'ENTER=Continue',
  /** PF3 leaves the application, which for this program means signing off. */
  PFK03: 'F3=Exit',
} as const;

/**
 * Route each administrative option's target program is entered at, or `null` when it is not mounted.
 *
 * Assumptions: five of the six are entered and one answers with the reference's own
 * unavailable-option sentence, and the reference establishes that answer rather than this delivery
 * inventing one -- `app/cbl/COADM01C.cbl` L141-L157 composes exactly
 * `'This option ' 'is not installed ...'` whenever the target program name begins `DUMMY`, and its
 * `PGMIDERR` handler at L270-L283 composes the same sentence when the named program cannot be
 * loaded, so a target that is absent already has a defined behaviour on this screen. Option 4 alone
 * takes that path, and for the reason recorded on its entry below: its screen IS mounted, but only at a
 * route that requires a user identifier this screen has not got.
 *
 * Refactoring Rationale: the value is a ROUTE where the reference held a program name.
 * `EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))` at `app/cbl/COADM01C.cbl` L145-L148
 * becomes a client-side route change under Transformation Rule T5, so there is no `CDEMO-TO-PROGRAM`
 * field, no `COMMAREA` and no server-side "next program" anywhere in the target. The program name
 * survives only as this map's KEY, which is what keeps the traceability matrix able to line each
 * option up with the program it replaces.
 *
 * Assumptions: option 3 is entered at a path carrying NO identifier, and that is the reference's own
 * first-entry shape rather than a gap. The reference's `XCTL` landed on a screen that PROMPTED for
 * the key, and a typed route table needs the key in the path, so the two are reconciled by entering
 * the selector-free form -- `ui/src/router.tsx` declares it for exactly this caller and names this
 * screen at its `USER_UPDATE_PATH` constant, while `ui/src/screens/userUpdate/index.tsx` reads
 * nothing on mount when the route carries no row. Trade-offs: a placeholder identifier such as
 * `/users/0/edit` would have kept one uniform parameterised shape and was rejected, because it asks
 * the next screen to read a record the operator never selected.
 *
 * ⚠️ Refactoring Rationale: the user-list option was one of the unavailable three and is now entered,
 * because the screen it names became mounted. `COUSR00C: null` was CORRECT while `/users` was not in the
 * route table -- naming an unmounted path would have sent the operator to a route that resolves to
 * nothing -- and became stale the moment `ui/src/screens/userList/index.tsx` was delivered and
 * `ui/src/router.tsx` registered its path. Leaving the null would have made a delivered screen
 * unreachable from inside the application: the reference enters it from exactly here
 * (`app/cbl/COUSR00C.cbl` L124-L125 returns to `COADM01C` on PF3, so the admin menu is its caller), and
 * a browser reload cannot substitute because the session is memory-only and any hard load of an admin
 * route bounces to sign-on. `everyMenuDestinationIsRegistered` in `ui/src/routerRoutes.test.tsx` checks
 * every non-null destination against the registered paths, so this entry is now verified rather than
 * asserted.
 *
 * Assumptions: the user-update option is entered at a path carrying NO identifier, which is the
 * reference's own first-entry shape rather than a gap. `app/cbl/COUSR02C.cbl` L99-L104 uses the
 * selection carrier only when it is present and otherwise leaves the screen waiting for a typed
 * identifier, and `ui/src/screens/userUpdate/index.tsx` reproduces exactly that -- its mount effect
 * returns without reading when the route names no row. The identifier-bearing form of the same route
 * exists for a caller that HAS a selection, which is what the carrier was for.
 */
export const ADMIN_MENU_DESTINATIONS: Readonly<Record<string, string | null>> = Object.freeze({
  COUSR00C: '/users',
  /*
   * WHY : Refactoring Rationale: option 2 names a route where it previously carried `null`. The null was
   *       correct while no add-user screen existed; `ui/src/screens/userAdd/index.tsx` is now authored
   *       and `ui/src/router.tsx` mounts it at this literal path, so leaving the null would answer the
   *       option with the not-installed sentence for a screen that IS installed -- making a delivered
   *       screen unreachable from inside the application, which matters here because the session is
   *       memory-only and any hard load of an administrative route bounces to sign-on.
   */
  COUSR01C: '/users/new',
  COUSR02C: '/users/edit',
  /*
   * WHY : Assumptions: option 4 stays `null` even though `ui/src/router.tsx` mounts its screen, and the
   *       reason is the route's SHAPE rather than a missing delivery. The delete screen is mounted at
   *       `/users/:id/delete`, which cannot be entered without an identifier, and a menu option carries
   *       no selection -- so the only honest answer from here is the baseline's own not-installed
   *       sentence. The identifier-bearing route exists for the user browse, which is where a row is
   *       selected. `COTRN01C` is omitted from `ui/src/routes/programRoutes.ts` for the same reason.
   */
  COUSR03C: null,
  COTRTLIC: '/reference/transaction-types',
  COTRTUPC: '/reference/transaction-types/new',
});

/** Matches an entry consisting only of decimal digits, which is COBOL's `IS NUMERIC` on a `PIC X`. */
const DIGITS_ONLY = /^[0-9]+$/u;

/** Matches any character the mapset's `NUM` attribute would not admit into the option control. */
const NON_DIGITS = /[^0-9]/gu;

/** One outcome of interpreting a typed administrative option number. */
interface AdminOptionOutcome {
  /** Route to enter, or `null` when the entry produced a message instead. */
  readonly destination: string | null;
  /** Message to paint on row 23, or `null` when the entry is being acted on. */
  readonly message: string | null;
  /** Severity the reference painted that message in. */
  readonly severity: MessageBandSeverity;
}

/**
 * Normalises a typed option entry the way the terminal and the program normalise it together.
 *
 * Purpose: return the two-digit form the reference holds in `WS-OPTION PIC 9(02)` and echoes back
 * into the option field, so that both the validation below and the redisplay read the same value.
 *
 * Assumptions: the mapset's own `JUSTIFY=(RIGHT,ZERO)` at `app/bms/COADM01.bms` L147 is what makes
 * this determinable. CICS delivers the field right-justified and zero-filled, so a single typed `6`
 * arrives as `'06'` and an untouched field arrives as two blanks. The program's sequence at
 * `app/cbl/COADM01C.cbl` L121-L128 then scans for the last non-blank, copies the prefix into a
 * `PIC X(02) JUST RIGHT` -- which pads on the LEFT, and truncates on the left if it ever had to --
 * and replaces every remaining blank with `'0'`. Right-padding to two characters therefore reproduces
 * both readings at once: the digits typed ARE the option number, and a blank field reads as `'00'`,
 * which L133's `WS-OPTION = ZEROS` test refuses.
 *
 * Assumptions: a non-digit reaches this function only through the exported surface, never through the
 * screen, because the control strips non-digits on entry. It is returned padded rather than rejected
 * here so that the single refusal decision stays in {@link resolveAdminOption}, where the reference
 * also keeps it, at L131-L138.
 * @param {string} entry - What the operator typed into the two-position option control.
 * @returns {string} The entry as exactly {@link OPTION_NUMBER_DIGITS} characters, left-padded with
 *   `'0'` and left-truncated, matching `WS-OPTION-X PIC X(02) JUST RIGHT` after its `INSPECT`.
 */
export function normaliseAdminOptionEntry(entry: string): string {
  return entry
    .trim()
    .padStart(OPTION_NUMBER_DIGITS, BLANK_POSITION_FILL)
    .slice(-OPTION_NUMBER_DIGITS);
}

/**
 * Composes one administrative option line exactly as the reference composes it.
 *
 * Assumptions: the number is ZERO-FILLED to two characters because the field it stands for is
 * `app/cpy/COADM02Y.cpy` L57's `CDEMO-ADMIN-OPT-NUM PIC 9(02)`, and `app/cbl/COADM01C.cbl` L236-L239
 * emits it with `STRING ... DELIMITED BY SIZE`, which takes the whole two characters of a display
 * numeric. The terminal therefore paints `01. ` through `06. `, and a line composed with `String(n)`
 * alone would be one character narrower on every option below ten and would stop aligning on the
 * separator. The `'. '` between the two and the 35-character padding of the name are the template's,
 * so the whole line is assembled where the reference assembles it rather than typed out six times.
 * @param {AdminMenuOption} option - One entry of the transcribed administrative option table.
 * @returns {string} The composed line: two-digit number, `'. '`, then the padded option name.
 * @throws {Error} Propagated from `formatMessageTemplate` if the template names a value this call
 *   does not supply, which would mean the catalog's admin line template had changed shape.
 */
export function composeAdminOptionLine(option: AdminMenuOption): string {
  return formatMessageTemplate(MESSAGE_TEMPLATES.ADMIN_MENU_OPTION_LINE, {
    'CDEMO-ADMIN-OPT-NUM': String(option.optionNumber).padStart(
      OPTION_NUMBER_DIGITS,
      BLANK_POSITION_FILL,
    ),
    'CDEMO-ADMIN-OPT-NAME': option.name,
  });
}

/**
 * Interprets one typed option number against the administrative option table and the route map.
 *
 * Assumptions: the validation is the reference's, in its order -- not numeric, greater than the
 * populated count, or zero all answer `'Please enter a valid option number...'`
 * (`app/cbl/COADM01C.cbl` L131-L138). The bound is the length of `ADMIN_MENU_OPTIONS`, which the
 * catalog keeps equal to `CDEMO-ADMIN-OPT-COUNT`, so it is the copybook's figure rather than a
 * literal repeated here.
 *
 * Assumptions: that count is SIX and not four. `app/cpy/COADM02Y.cpy` holds two declarations of the
 * field -- L21 says four and is commented out, L22 says six and is live -- and the two extra entries
 * are the Db2 transaction-type options the `Option added for Db2 V1 release` markers at L45 and L54
 * bracket. Four is the superseded value rather than a competing one, and reading the commented line
 * would drop two options that the copybook, the map and this menu all still carry.
 * @param {string} entry - What the operator typed into the two-position option control.
 * @returns {AdminOptionOutcome} The route to enter, or the message to paint and its severity.
 * @throws {Error} Propagated from `formatMessageTemplate` if the unavailable-option template names a
 *   value this call does not supply.
 */
export function resolveAdminOption(entry: string): AdminOptionOutcome {
  const normalised = normaliseAdminOptionEntry(entry);
  const number = DIGITS_ONLY.test(normalised) ? Number.parseInt(normalised, 10) : Number.NaN;
  if (Number.isNaN(number) || number === 0 || number > ADMIN_MENU_OPTIONS.length) {
    return {
      destination: null,
      message: SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER,
      severity: 'error',
    };
  }
  const option = ADMIN_MENU_OPTIONS[number - 1];
  if (option === undefined) {
    return {
      destination: null,
      message: SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER,
      severity: 'error',
    };
  }
  const destination = ADMIN_MENU_DESTINATIONS[option.programName] ?? null;
  if (destination === null) {
    /*
     * WHY : Assumptions: the template takes no value, because the reference's own insertion of the
     *       option name is commented out at both of its composition sites -- `app/cbl/COADM01C.cbl`
     *       L153-L154 and L274-L275. Passing the name anyway would make this screen name an option
     *       the terminal never named. The main menu's sentence at `app/cbl/COMEN01C.cbl` L163-L167
     *       DOES insert it, and also carries a leading space and no space before its ellipsis, so the
     *       two read differently on purpose; Transformation Rule T8 keeps them as two catalog entries
     *       and neither the wording nor the spacing of either is reconciled with the other.
     * WHY : Assumptions: the severity is `success` where the refusal above is `error`, and the
     *       asymmetry is the program's. `app/cbl/COADM01C.cbl` L151 and L272 both move `DFHGREEN`
     *       into the message field's colour attribute for this sentence, overriding the `COLOR=RED`
     *       the map gives `ERRMSG` at L154-L155 -- the same sentence the main menu paints under
     *       `DFHRED`. AAP section 0.3.3 maps `GREEN` to `colorSuccess`, so the colour is carried
     *       across as a role rather than as a literal.
     */
    return {
      destination: null,
      message: formatMessageTemplate(MESSAGE_TEMPLATES.ADMIN_OPTION_NOT_INSTALLED),
      severity: 'success',
    };
  }
  return { destination, message: null, severity: 'error' };
}

/**
 * Renders the six administrative options and enters the one selected.
 *
 * Purpose: the screen component the router mounts at `/admin`. It takes no props: everything it needs
 * is the transcribed option table, the signed session and the router, so a prop would be a second
 * source for something already single-sourced.
 *
 * Assumptions: no authority is re-verified here. `ui/src/router.tsx` nests every administrative route
 * inside `RequireAdmin`, so reaching this component already means the signed token carried the
 * administrative group. Refactoring Rationale: repeating the check here is how two copies of one
 * authorization rule drift apart, and it is also what the reference got wrong -- `CDEMO-USER-TYPE` in
 * `app/cpy/COCOM01Y.cpy` L19-L44 is storage the client echoes back, so the terminal asserted its own
 * user type; a signed claim cannot be asserted by its holder, which is the property that makes one
 * check at the route boundary sufficient.
 * @returns {ReactElement} The administrative menu screen.
 * @throws {unknown} Message-composition failures from {@link resolveAdminOption} propagate to the
 *   application error boundary rather than being shown as a menu message.
 */
export function AdminMenuScreen(): ReactElement {
  const navigate = useNavigate();
  const { signOut } = useAuth();
  const { cssVar } = theme.useToken();
  const paintedAt = useServerInstant();
  const optionControl = useRef<InputRef | null>(null);

  const [entry, setEntry] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');

  /**
   * Interprets the typed option and either enters its screen or paints the reference's message.
   * @returns {void} Completion is represented by a route change or by this screen's own state.
   */
  function enterSelectedOption(): void {
    const normalised = normaliseAdminOptionEntry(entry);

    /*
     * WHY : Assumptions: the normalised value is echoed BACK into the control before the entry is
     *       acted on, because `app/cbl/COADM01C.cbl` L129 does exactly that -- `MOVE WS-OPTION TO
     *       OPTIONO OF COADM1AO` puts the `PIC 9(02)` value into the map's output field, so a
     *       terminal operator who typed `6` sees `06` on the turn that follows. It is set before the
     *       decision rather than after, matching L129 sitting above the refusal test at L131, so the
     *       redisplay is the same on the accepted and the refused path.
     * WHY : Assumptions: the value stays a STRING here and everywhere below. The field is
     *       `OPTIONI PIC X(2)` at `app/cpy-bms/COADM01.CPY` L132 and `OPTIONO PIC X(2)` at L254 --
     *       character fields, not numeric ones -- so holding it as a number would lose the leading
     *       zero this echo exists to show and would have to reconstruct it to render.
     */
    setEntry(normalised);

    const outcome = resolveAdminOption(normalised);
    setMessage(outcome.message);
    setSeverity(outcome.severity);
    if (outcome.destination !== null) {
      navigateSafely(navigate, outcome.destination);
      return;
    }

    /*
     * WHY : Assumptions: the cursor returns to the option control on a turn that produced a message,
     *       because the mapset sets `IC` on that control -- `app/bms/COADM01.bms` L145 -- and the
     *       reference re-sends the whole map through `SEND-MENU-SCREEN` at L175-L187 on every one of
     *       those turns, so the terminal placed the cursor there again each time. `autoFocus` below
     *       carries `IC` for the first paint only; without this the focus would stay on whichever
     *       control was activated and the operator would have to reach the field again by hand.
     */
    optionControl.current?.focus();
  }

  /**
   * Ends the session and returns to sign-on, which is what this program's PF3 does.
   *
   * Assumptions: the session is discarded as well as the route changed. `app/cbl/COADM01C.cbl`
   * L100-L102 moves `'COSGN00C'` into `CDEMO-TO-PROGRAM` and `RETURN-TO-SIGNON-SCREEN` at L163-L170
   * transfers there with NO `COMMAREA` clause, so the next program starts with no identity at all.
   * @returns {void} Completion is represented by the discarded session and the route change.
   */
  function signOffToSignOn(): void {
    signOut();
    navigateSafely(navigate, SIGN_ON_ROUTE);
  }

  /*
   * WHY : Assumptions: exactly the two attention identifiers `app/cbl/COADM01C.cbl` L97-L107 admits
   *       are bound, and its `WHEN OTHER` arm at L103-L106 moves `CCDA-MSG-INVALID-KEY` into the
   *       message field -- so an unmapped key is reported here rather than coerced into one of the
   *       two. The legend this screen supplies below names only these two for the same reason: the
   *       mapset paints `'ENTER=Continue  F3=Exit'` at L158-L162 and nothing else, so no F4, F7, F8
   *       or F12 descriptor belongs on this screen.
   * WHY : Assumptions: PF13-PF24 are not handled here. `app/cpy/CSSTRPFY.cpy` L21-L78 aliases them
   *       onto PF01-PF12, and `ui/src/layout/usePfKeys.ts` owns that aliasing in its own
   *       `PF_KEY_ALIASES`, so PF15 reaches the `PFK03` entry below already normalised. Re-declaring
   *       the aliases here would give the mapping a second home that could disagree with the first.
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
        label: ADMIN_MENU_KEY_LABELS.ENTER,
      },
      PFK03: {
        /**
         * Signs off, which is this program's `RETURN-TO-SIGNON-SCREEN`.
         * @returns {void} Completion is represented by the discarded session and the route change.
         */
        onInvoke: () => {
          signOffToSignOn();
        },
        label: ADMIN_MENU_KEY_LABELS.PFK03,
      },
    },
    {
      /**
       * Reports an unmapped key with the reference's own sentence.
       * @param {object} rejection - Why the key was not dispatched.
       * @param {string} rejection.reason - Either `'unmapped'` or `'disabled'`.
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
   * WHY : Assumptions: the option lines are rendered in the code face because the reference paints
   *       them into fixed 40-character fields on a character grid -- `app/bms/COADM01.bms` L80-L139 --
   *       and a proportional face gives digits different advance widths, so the `01. ` through `06. `
   *       prefixes would stop lining up under each other. The family is read from the token map, so
   *       no font name appears here.
   */
  const optionLineStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

  /*
   * WHY : Assumptions: the control is sized to the two characters the mapset declares --
   *       `LENGTH=2` at `app/bms/COADM01.bms` L148 -- expressed in `ch` units. A declared field width
   *       is a DATA contract rather than a design value, so it resolves to no design token and needs
   *       none; this is the same treatment `ui/src/layout/MessageBand.tsx` applies to its own 78- and
   *       80-character mapset widths and `ui/src/screens/authSummary/index.tsx` to its money columns.
   *       The control's own chrome is added from `controlHeight` rather than from a literal, so the
   *       glyph box keeps its two characters once the border and the horizontal padding are taken.
   * WHY : Refactoring Rationale: an explicit inline size is set because `.ant-input` carries
   *       `width: 100%`, and browser validation of this screen measured the consequence -- a
   *       two-character field rendered 1046 px wide, which then squeezed the 25-character prompt
   *       beside it into a 115 px column and wrapped it onto two lines. Neither is what the mapset
   *       paints: `OPTION` is two columns at `POS=(20,41)` and the prompt is one unbroken 25-column
   *       field at `POS=(20,15)`. Trade-offs: a `Form.Item`-driven layout would also have bounded the
   *       control, and was rejected because this screen has one control and no field-level validation
   *       state to render, so a form wrapper would add a layer that carries nothing.
   */
  const optionControlStyle: CSSProperties = {
    inlineSize: `calc(${String(ADMIN_MENU_OPTION_WIDTH)}ch + ${cssVar.controlHeight})`,
  };

  return (
    <Flex vertical gap="large">
      <ScreenHeader
        transactionId={ADMIN_MENU_TRANSACTION_ID}
        programName={ADMIN_MENU_PROGRAM_NAME}
        now={paintedAt}
      />
      {/*
        Assumptions: the caption is rendered through `ScreenTitle`, which owns the heading RANK for
        every screen, and it is given neither a colour nor a grade. `app/bms/COADM01.bms` L75-L79
        pairs `COLOR=NEUTRAL` with `ATTRB=(ASKIP,BRT)`, which on a 3270 is the most prominent text on
        the screen rather than a de-emphasised one, and `Typography.Title` already carries
        `fontWeightStrong` for that `BRT`. Trade-offs: AAP section 0.3.3 snaps `NEUTRAL` to
        `colorTextSecondary`, and passing that grade here would render the screen's own title greyer
        than the body beneath it -- inverting the emphasis `BRT` asserts -- so the snap is declined for
        this one field and the heading colour token is kept. The de-emphasis role the snap describes is
        applied where the mapset uses `NEUTRAL` WITHOUT `BRT`.
      */}
      <ScreenTitle>{ADMIN_MENU_SUBTITLE}</ScreenTitle>
      {/*
        Alternatives Considered: an antd `Menu` with `mode="vertical"`, which AAP section 0.4.1.4
        designates for this screen, and a `List` of `Button` items. Both were rejected on the
        mapset's own attributes: `OPTN001`-`OPTN012` are `ATTRB=(ASKIP,FSET,NORM)` at
        `app/bms/COADM01.bms` L80-L139, and `ASKIP` makes them protected and unselectable -- the
        terminal takes the selection ONLY through the separate `NUM` control at L145-L149. Activatable
        menu items would therefore add a second way to choose an option that the reference never
        offered, and AAP Rule T9 admits structural change but not behavioural change. The lines stay
        library components rather than raw markup: `Typography.Text` inside `Flex`, never a `<ul>`,
        an `<li>` or a `<div>` carrying spacing of its own. Keyboard operation is unaffected, because
        the control that actually takes the selection is focused on arrival.
      */}
      {/*
        Trade-offs: SIX lines are rendered where the mapset declares twelve slots at
        `app/bms/COADM01.bms` L80-L139 and the copybook declares `CDEMO-ADMIN-OPT OCCURS 9 TIMES` at
        `app/cpy/COADM02Y.cpy` L56. `BUILD-MENU-OPTIONS` loops `1` through `CDEMO-ADMIN-OPT-COUNT`
        only -- `app/cbl/COADM01C.cbl` L231-L232 -- so six slots receive text and the rest keep the
        `INITIAL=' '` the map painted; its `EVALUATE` at L241-L264 does not even have an arm for slots
        eleven and twelve. Twelve reserved slots are terminal geometry, because a fixed 24-row grid has
        to commit its rows in advance, and that is design gap G1; a browser list has no such need. The
        cost of rendering the reserved-but-unused slots would be six empty lines a screen reader
        announces as blank items, so they are accounted for in this file's header instead.
      */}
      <Flex vertical>
        {ADMIN_MENU_OPTIONS.map(
          /**
           * Renders one administrative option line.
           * @param {AdminMenuOption} option - One entry of the transcribed option table.
           * @returns {ReactElement} The rendered line.
           */
          (option) => (
            <Typography.Text key={option.optionNumber} style={optionLineStyle}>
              {composeAdminOptionLine(option)}
            </Typography.Text>
          ),
        )}
      </Flex>
      <Flex align="center" gap="small">
        <Typography.Text id="admin-menu-option-label">{ADMIN_MENU_PROMPT}</Typography.Text>
        <Input
          id="admin-menu-option"
          aria-labelledby="admin-menu-option-label"
          ref={optionControl}
          value={entry}
          style={optionControlStyle}
          maxLength={ADMIN_MENU_OPTION_WIDTH}
          // Assumptions: `autoFocus` carries the mapset's `IC` attribute, which `app/bms/COADM01.bms`
          //   L145 sets on this control and on no other field, so this is the screen's only one.
          autoFocus
          inputMode="numeric"
          onChange={
            /**
             * Keeps only the digits the mapset's `NUM` attribute admits.
             *
             * Assumptions: non-digits are dropped on entry rather than refused afterwards, because
             * `ATTRB=(...,NUM,...)` at `app/bms/COADM01.bms` L145 makes the control numeric-only on
             * the terminal -- a letter could not be keyed into it at all, so there was never a
             * message for one. The two-character limit is the same field's `LENGTH=2` and the
             * symbolic map's `OPTIONI PIC X(2)` at `app/cpy-bms/COADM01.CPY` L132.
             * @param {ChangeEvent<HTMLInputElement>} event - The entry event.
             * @returns {void} Completion is represented by this screen's own state.
             */
            (event: ChangeEvent<HTMLInputElement>) => {
              setEntry(event.target.value.replace(NON_DIGITS, ''));
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
        <Button type="primary" onClick={enterSelectedOption}>
          {ADMIN_MENU_KEY_LABELS.ENTER}
        </Button>
      </Flex>
      <MessageBand mapset={ADMIN_MENU_MAPSET} message={message} severity={severity} />
      <PfKeyBar keys={bindings} onInvoke={invoke} />
    </Flex>
  );
}

export default AdminMenuScreen;
