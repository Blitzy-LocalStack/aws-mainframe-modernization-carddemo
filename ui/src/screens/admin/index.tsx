/**
 * @file The administrative menu screen, migrated from `app/cbl/COADM01C.cbl` and its mapset
 * `app/bms/COADM01.bms` (map `COADM1A`, `DFHMDI ... SIZE=(24,80)`), mounted at the `/admin` route.
 *
 * Purpose
 * -------
 * Paint the six administrative options, take an option number, and enter the screen that option names.
 * It replaces CICS transaction `CA00`, which `app/cbl/COADM01C.cbl` L37 declares as this program's own
 * transaction identifier and `app/csd/CARDDEMO.CSD` L327-L328 maps to the program.
 *
 * Why this screen had to exist
 * ---------------------------
 * Refactoring Rationale: the sign-on screen and the reference-data screens both navigated to `/admin`
 * and nothing was mounted there, so an administrator signing on landed on the router's not-found result.
 * `ui/src/routes/navigation.ts` names the destination at its `ADMIN_MENU_ROUTE` constant; this module
 * occupies it. The option table was already transcribed -- `ADMIN_MENU_OPTIONS` in
 * `ui/src/messages/messages.ts` carries all six entries from `app/cpy/COADM02Y.cpy` -- so nothing about
 * the menu's CONTENT is authored here.
 *
 * How this screen differs from the main menu, and why
 * -------------------------------------------------
 * Assumptions: three differences from `ui/src/screens/menu/index.tsx` are the reference's own and none
 * of them is a simplification. There is NO per-option user-type check, because `app/cpy/COADM02Y.cpy`
 * genuinely omits the `USRTYPE` subfield the main-menu copybook carries -- this menu is only reachable
 * once the administrator check has passed, so a per-option type would be redundant. The
 * unavailable-option sentence names NO option, because the two lines that would have inserted
 * `CDEMO-ADMIN-OPT-NAME` are commented out at both composition sites, `app/cbl/COADM01C.cbl` L153-L154
 * and L274-L275. And it is painted in `DFHGREEN` rather than `DFHRED` -- L149 against the main menu's
 * L162 -- so the colour is a property of the program rather than of the wording.
 *
 * This screen holds no session state
 * ---------------------------------
 * Assumptions: as with the main menu, `CDEMO-FROM-TRANID`, `CDEMO-FROM-PROGRAM` and
 * `CDEMO-PGM-CONTEXT` are set immediately before each `XCTL` in the reference and none of the three has
 * a counterpart here. Navigation is the router's history, identity is the validated token's claim, and
 * the administrative gate is `RequireAdmin` in `ui/src/routes/guards.tsx` rather than a byte this screen
 * is told.
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
  ADMIN_MENU_OPTIONS,
  INVALID_KEY_PRESSED,
  MESSAGE_TEMPLATES,
  SHARED_MESSAGES,
  formatMessageTemplate,
} from '../../messages/messages';
import { SIGN_ON_ROUTE } from '../../routes/guards';
import { navigateSafely } from '../../routes/navigation';
import { TYPOGRAPHY_TOKENS } from '../../theme/tokens';
import { ScreenTitle } from '../../layout/ScreenTitle';

/** CICS transaction identifier this screen replaces, from `app/cbl/COADM01C.cbl` L37. */
export const ADMIN_MENU_TRANSACTION_ID = 'CA00';

/** Source program name, from `app/cbl/COADM01C.cbl` L36. */
export const ADMIN_MENU_PROGRAM_NAME = 'COADM01C';

/** Mapset this screen stands in, which fixes the message band's rendered width. */
export const ADMIN_MENU_MAPSET = 'COADM01';

/** Row-3 sub-title, from the `DFHMDF ... INITIAL='Admin Menu'` at `app/bms/COADM01.bms` L77-L79. */
export const ADMIN_MENU_SUBTITLE = 'Admin Menu';

/** Prompt beside the option control, from `app/bms/COADM01.bms` L142-L144. */
export const ADMIN_MENU_PROMPT = 'Please select an option :';

/** Declared width of the `OPTION` control, from `app/bms/COADM01.bms` L145-L148. */
export const ADMIN_MENU_OPTION_WIDTH = 2;

/**
 * Digits the composed option line renders the option number in.
 *
 * Assumptions: declared separately from the option FIELD's width above even though both are two, and
 * deliberately so. That one is the entry control's `maxLength`, from the mapset's `PIC X(02)` input
 * field; this one is the display width of `app/cpy/COADM02Y.cpy L57`'s `PIC 9(02)`, which the reference emits
 * with `STRING ... DELIMITED BY SIZE`. Folding them into one constant would tie a rendered width to an
 * accepted width, and a change to either would silently move the other.
 */
const OPTION_NUMBER_DIGITS = 2;

/** The two legend labels this mapset paints on row 24, from `app/bms/COADM01.bms` L160-L162. */
export const ADMIN_MENU_KEY_LABELS = {
  /** Enter continues into the chosen option. */
  ENTER: 'ENTER=Continue',
  /** PF3 leaves the application, which for this program means signing off. */
  PFK03: 'F3=Exit',
} as const;

/**
 * Route each administrative option's target program is entered at, or `null` when it is not mounted.
 *
 * Assumptions: three of the six are entered and three answer with the reference's own
 * unavailable-option sentence, and the reference establishes that answer rather than this delivery
 * inventing one -- `app/cbl/COADM01C.cbl` L146-L156 composes exactly
 * `'This option ' 'is not installed ...'` whenever the target program name begins `DUMMY`, so an
 * absent target already has a defined behaviour on this screen.
 *
 * Assumptions: the user-update option is entered at a path carrying NO identifier, which is the
 * reference's own first-entry shape rather than a gap. `app/cbl/COUSR02C.cbl` L99-L104 uses the
 * selection carrier only when it is present and otherwise leaves the screen waiting for a typed
 * identifier, and `ui/src/screens/userUpdate/index.tsx` reproduces exactly that -- its mount effect
 * returns without reading when the route names no row. The identifier-bearing form of the same route
 * exists for a caller that HAS a selection, which is what the carrier was for.
 */
export const ADMIN_MENU_DESTINATIONS: Readonly<Record<string, string | null>> = Object.freeze({
  COUSR00C: null,
  COUSR01C: null,
  COUSR02C: '/users/edit',
  COUSR03C: null,
  COTRTLIC: '/reference/transaction-types',
  COTRTUPC: '/reference/transaction-types/new',
});

/** Matches an entry consisting only of decimal digits, which is COBOL's `IS NUMERIC` on a `PIC X`. */
const DIGITS_ONLY = /^[0-9]+$/u;

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
 * Interprets one typed option number against the administrative option table and the route map.
 *
 * Assumptions: the validation is the reference's, in its order -- not numeric, greater than the
 * populated count, or zero all answer `'Please enter a valid option number...'`
 * (`app/cbl/COADM01C.cbl` L132-L138). The bound is the length of {@link ADMIN_MENU_OPTIONS}, which the
 * catalog keeps equal to `CDEMO-ADMIN-OPT-COUNT`, so it is the copybook's figure rather than a literal.
 *
 * Assumptions: the entry normalisation deliberately does not reproduce the reference's own scan-and-
 * replace sequence at `app/cbl/COADM01C.cbl` L121-L128, for the reason recorded in full at
 * `resolveMenuOption` in `ui/src/screens/menu/index.tsx`: the sequence's result depends on whether CICS
 * padded the unfilled position of the two-position field with a blank or left it low-values, a browser
 * control has neither, and the two readings disagree about what a single typed digit means. The digits
 * typed are the option number here, on both screens, so the two menus cannot come to disagree.
 * @param {string} entry - What the operator typed into the two-position option control.
 * @returns {AdminOptionOutcome} The route to enter, or the message to paint and its severity.
 */
export function resolveAdminOption(entry: string): AdminOptionOutcome {
  const typed = entry.trim();
  const number = DIGITS_ONLY.test(typed) ? Number.parseInt(typed, 10) : Number.NaN;
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
     *       option name is commented out at both of its composition sites. Passing the name anyway
     *       would make this screen name an option the terminal never named, and the catalog's own
     *       entry records the asymmetry with the main menu for exactly this reason.
     * WHY : Assumptions: the severity is `success` because `app/cbl/COADM01C.cbl` L149 moves `DFHGREEN`
     *       into the message field's colour attribute for this sentence -- the same sentence the main
     *       menu paints in `DFHRED`. The band maps the reference's green to its success variant, so the
     *       colour is carried across as a role rather than as a literal.
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
 * @returns {ReactElement} The administrative menu screen.
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
    const outcome = resolveAdminOption(entry);
    setMessage(outcome.message);
    setSeverity(outcome.severity);
    if (outcome.destination !== null) {
      navigateSafely(navigate, outcome.destination);
    }
  }

  /**
   * Ends the session and returns to sign-on, which is what this program's PF3 does.
   *
   * Assumptions: the session is discarded as well as the route changed, for the reason the main menu
   * records -- `app/cbl/COADM01C.cbl` transfers to `COSGN00C` with no `COMMAREA` clause, so the next
   * program starts with no identity at all.
   * @returns {void} Completion is represented by the discarded session and the route change.
   */
  function signOffToSignOn(): void {
    signOut();
    navigateSafely(navigate, SIGN_ON_ROUTE);
  }

  /*
   * WHY : Assumptions: exactly the two attention identifiers `app/cbl/COADM01C.cbl` L98-L108 admits are
   *       bound, and its `WHEN OTHER` arm moves `CCDA-MSG-INVALID-KEY` into the message field -- so an
   *       unmapped key is reported here rather than coerced.
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
   * WHY : Assumptions: the option lines are rendered in the code face for the reason the main menu
   *       records -- the reference paints them into fixed 40-character fields on a character grid, and
   *       a proportional face gives digits different advance widths so the numbers stop aligning.
   */
  const optionLineStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

  return (
    <Flex vertical gap="large">
      <ScreenHeader
        transactionId={ADMIN_MENU_TRANSACTION_ID}
        programName={ADMIN_MENU_PROGRAM_NAME}
        now={paintedAt}
      />
      <ScreenTitle>{ADMIN_MENU_SUBTITLE}</ScreenTitle>
      {/*
        Trade-offs: the options are text lines rather than a control each, which is design gap G1 taken
        deliberately and for the same reason as on the main menu -- the reference's option fields are
        protected and the selection is taken through one separate numeric control, so activatable lines
        would be an affordance the terminal never offered. Each line is composed through the catalog's
        `ADMIN_MENU_OPTION_LINE` template, so the number, the separator and the padded name are
        assembled exactly as `app/cbl/COADM01C.cbl` L236-L238 assembles them.
      */}
      <Flex vertical>
        {ADMIN_MENU_OPTIONS.map(
          /**
           * Renders one administrative option line exactly as the reference composes it.
           * @param {object} option - One entry of the transcribed option table.
           * @returns {ReactElement} The rendered line.
           */
          (option) => (
            <Typography.Text key={option.optionNumber} style={optionLineStyle}>
              {formatMessageTemplate(MESSAGE_TEMPLATES.ADMIN_MENU_OPTION_LINE, {
                /*
                 * WHY : ⚠️ Refactoring Rationale: the number is ZERO-FILLED to two characters, where it was
                 *       rendered with `String(...)` alone. The field it stands for is
                 *       `app/cpy/COADM02Y.cpy L57` `PIC 9(02)`, and the reference emits it with
                 *       `STRING ... DELIMITED BY SIZE`, which takes the whole two characters of a display
                 *       numeric -- so the terminal paints `01. ` through `09. ` and this screen painted
                 *       `1. ` through `9. `. Nine of the eleven lines therefore differed from the reference
                 *       by one character and the list no longer aligned on the separator, which is the
                 *       whole reason the line is composed in the code face.
                 */
                'CDEMO-ADMIN-OPT-NUM': String(option.optionNumber).padStart(
                  OPTION_NUMBER_DIGITS,
                  '0',
                ),
                'CDEMO-ADMIN-OPT-NAME': option.name,
              })}
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
          maxLength={ADMIN_MENU_OPTION_WIDTH}
          // Assumptions: `autoFocus` carries the mapset's `IC` attribute, which this mapset sets on the
          // option control exactly as the main menu's does.
          autoFocus
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
