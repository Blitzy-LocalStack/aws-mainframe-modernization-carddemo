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
  MAIN_MENU_OPTIONS,
  MESSAGE_TEMPLATES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  formatMessageTemplate,
} from '../../messages/messages';
import type { MainMenuOption } from '../../messages/messages';
import { SIGN_ON_ROUTE } from '../../routes/guards';
import { navigateSafely } from '../../routes/navigation';
import { TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/** CICS transaction identifier this screen replaces, from `app/cbl/COMEN01C.cbl` L37. */
export const MAIN_MENU_TRANSACTION_ID = 'CM00';

/** Source program name, from `app/cbl/COMEN01C.cbl` L36. */
export const MAIN_MENU_PROGRAM_NAME = 'COMEN01C';

/** Mapset this screen stands in, which fixes the message band's rendered width. */
export const MAIN_MENU_MAPSET = 'COMEN01';

/** Row-3 sub-title, from the `DFHMDF ... INITIAL='Main Menu'` at `app/bms/COMEN01.bms` L77-L79. */
export const MAIN_MENU_SUBTITLE = 'Main Menu';

/** Prompt beside the option control, from `app/bms/COMEN01.bms` L142-L144. */
export const MAIN_MENU_PROMPT = 'Please select an option :';

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
 * The two legend labels this mapset paints on row 24, from `app/bms/COMEN01.bms` L160-L162.
 *
 * Assumptions: the field's `INITIAL` is the single literal `'ENTER=Continue  F3=Exit'`, so the two
 * labels are split at the double space the reference used as its separator rather than being reworded.
 */
export const MAIN_MENU_KEY_LABELS = {
  /** Enter continues into the chosen option. */
  ENTER: 'ENTER=Continue',
  /** PF3 leaves the application, which for this program means signing off. */
  PFK03: 'F3=Exit',
} as const;

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
 * absent, and this delivery reuses it rather than inventing a "coming soon" or disabling the control.
 *
 * Two different reasons produce a `null`, and they are worth separating even though they share one
 * sentence. Four options name screens this delivery has not authored at all -- the transaction list,
 * the transaction view, the reports screen and bill payment. Two more name screens that ARE authored,
 * the card detail and card update screens, but which can only be addressed by an opaque selector that
 * `ui/src/routes/cards.ts` seals and only the card browse screen mints; `ui/src/api/cards.ts` records
 * why a card number may not travel in a path at all, so there is no selector-free address for the menu
 * to send an operator to.
 *
 * Alternatives Considered: sending those two to the card browse screen instead, on the ground that the
 * reference's own first turn of `COCRDSLC` is an empty screen the operator types a key into and the
 * browse screen is the migrated equivalent of that turn. Rejected because it answers a DIFFERENT option
 * than the operator chose -- an operator selecting "Credit Card View" would arrive at "Credit Card
 * List" with nothing saying why -- and because option 3 already leads there, so the menu would have
 * three entries with one destination.
 */
export const MAIN_MENU_DESTINATIONS: Readonly<Record<string, string | null>> = Object.freeze({
  COACTVWC: '/account/view',
  COACTUPC: '/account/update',
  COCRDLIC: '/cards',
  COCRDSLC: null,
  COCRDUPC: null,
  COTRN00C: null,
  COTRN01C: null,
  COTRN02C: '/transactions/new',
  CORPT00C: null,
  COBIL00C: null,
  COPAUS0C: '/authorizations',
});

/** Matches an entry consisting only of decimal digits, which is COBOL's `IS NUMERIC` on a `PIC X`. */
const DIGITS_ONLY = /^[0-9]+$/u;

/** One outcome of interpreting a typed option number. */
interface OptionOutcome {
  /** Route to enter, or `null` when the entry produced a message instead. */
  readonly destination: string | null;
  /** Message to paint on row 23, or `null` when the entry is being acted on. */
  readonly message: string | null;
  /** Severity the reference painted that message in. */
  readonly severity: MessageBandSeverity;
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
 * Refactoring Rationale: the reference's own normalisation is NOT reproduced, and the reason is that it
 * is not determinable rather than that it is inconvenient. `app/cbl/COMEN01C.cbl` L117-L124 scans the
 * two-position field from its END for the first non-space, copies that prefix into a `PIC X(02)`, and
 * then replaces every remaining blank with a `'0'` -- so a single typed digit becomes that digit
 * followed by a zero IF CICS padded the unfilled position with a blank, and becomes a non-numeric value
 * IF it left the position as low-values. The two readings disagree about what typing `1` means: one
 * selects option 10, which is Bill Payment, and the other refuses the entry. A browser `Input` has
 * neither padding nor low-values -- it yields exactly the characters typed -- so there is no third
 * reading to be faithful to, and the ambiguity is resolved toward the one interpretation that cannot
 * act on an option the operator did not choose: the digits typed are the option number.
 * Trade-offs: an operator who typed a single digit on the terminal and expects the zero-suffixed
 * reading gets the digit they typed instead. That is the safer half of an ambiguity involving a
 * money-moving screen, and the ambiguity itself is recorded here so no reader mistakes the difference
 * for a transcription slip.
 * @param {string} entry - What the operator typed into the two-position option control.
 * @param {boolean} isAdmin - Whether the signed-on operator holds the administrative group.
 * @returns {OptionOutcome} The route to enter, or the message to paint and its severity.
 */
export function resolveMenuOption(entry: string, isAdmin: boolean): OptionOutcome {
  const typed = entry.trim();
  const number = DIGITS_ONLY.test(typed) ? Number.parseInt(typed, 10) : Number.NaN;
  if (Number.isNaN(number) || number === 0 || number > MAIN_MENU_OPTIONS.length) {
    return {
      destination: null,
      message: SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER,
      severity: 'error',
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
    return {
      destination: null,
      message: SHARED_MESSAGES.PLEASE_ENTER_A_VALID_OPTION_NUMBER,
      severity: 'error',
    };
  }
  if (!isAdmin && option.userType === 'A') {
    return {
      destination: null,
      message: PROGRAM_MESSAGES.COMEN01C.NO_ACCESS_ADMIN_ONLY_OPTION,
      severity: 'error',
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
    };
  }
  return { destination, message: null, severity: 'error' };
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
   * @returns {void} Completion is represented by a route change or by this screen's own state.
   */
  function enterSelectedOption(): void {
    const outcome = resolveMenuOption(entry, isAdmin);
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

  return (
    <Flex vertical gap="large">
      <ScreenHeader
        transactionId={MAIN_MENU_TRANSACTION_ID}
        programName={MAIN_MENU_PROGRAM_NAME}
        now={paintedAt}
      />
      <Typography.Title level={3}>{MAIN_MENU_SUBTITLE}</Typography.Title>
      {/*
        Trade-offs: the options are a LIST of text lines rather than a control each, and that is design
        gap G1 taken deliberately. The reference paints twelve protected 40-character fields and takes
        the selection through one separate numeric control, so the lines are not activatable on the
        terminal either -- turning them into buttons would add an affordance the reference never had and
        would leave the numeric control it does have with nothing to do. Each line is composed through
        the catalog's own `MENU_OPTION_LINE` template, so the number, the separator and the padded name
        are assembled exactly as `app/cbl/COMEN01C.cbl` L269-L271 assembles them.
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
      <Flex align="center" gap="small">
        {/*
          Assumptions: the prompt is associated with the control through `htmlFor` rather than by
          proximity, because the terminal identified this field by its position at row 22 and position
          names nothing to a screen reader.
        */}
        <Typography.Text id="main-menu-option-label">{MAIN_MENU_PROMPT}</Typography.Text>
        <Input
          id="main-menu-option"
          aria-labelledby="main-menu-option-label"
          ref={optionControl}
          value={entry}
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
