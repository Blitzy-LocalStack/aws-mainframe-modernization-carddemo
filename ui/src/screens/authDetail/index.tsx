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
 * This screen holds no session state
 * ---------------------------------
 * Assumptions: the reference carries the selected authorization in `CDEMO-CPVS-PAU-SELECTED` and the
 * calling program in `CDEMO-TO-PROGRAM`, and neither survives. The selection is the route parameter,
 * the return destination is the summary route, and the re-entry discriminator has no counterpart --
 * this screen reads its record from the route on mount and after each transition rather than
 * remembering which turn it is on.
 */

import { Button, Descriptions, Flex, Popconfirm, Result, Spin, Typography, theme } from 'antd';
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
import type { FraudAction } from '../../api/types';
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
import { TYPOGRAPHY_TOKENS } from '../../theme/tokens';

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
 * The eighteen field labels this mapset paints, in the order it paints them.
 *
 * Assumptions: each is the mapset's own `INITIAL` literal, carried character for character including
 * the trailing colon and, for `Source   :`, the internal padding the mapset uses to align its colon
 * with the label above it. Trimming that padding would be a second spelling of a painted literal.
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
  /** `COPAU01.bms` L133. */
  authCode: 'Auth Code:',
  /** `COPAU01.bms` L143. */
  amount: 'Amount:',
  /** `COPAU01.bms` L153. */
  posEntryMode: 'POS Entry Mode:',
  /** `COPAU01.bms` L163, whose internal padding is part of the painted literal. */
  source: 'Source   :',
  /** `COPAU01.bms` L173. */
  merchantCategoryCode: 'MCC Code:',
  /** `COPAU01.bms` L183. */
  cardExpiryDate: 'Card Exp. Date:',
  /** `COPAU01.bms` L193. */
  authType: 'Auth Type:',
  /** `COPAU01.bms` L203. */
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
  /** PF8 steps to the next authorization for the same account. */
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

/** The two sentences the fraud transition reports, from `COPAUS1C.cbl` L74-L75 through the catalog. */
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
 * @param {string} fraudMark - The fraud status as the service rendered it for the screen.
 * @returns {FraudAction} The transition to request.
 */
export function nextFraudAction(fraudMark: string): FraudAction {
  return fraudMark.trim().toUpperCase() === FRAUD_REPORTED ? FRAUD_WITHDRAWN : FRAUD_REPORTED;
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
 * A sentence to paint once a read completes, in place of the row's own.
 *
 * Assumptions: the severity travels with the text rather than being inferred from it, because this
 * screen's one non-error severity is a completed fraud write and the reference decides that per message
 * rather than per wording -- `COPAUS1C.cbl` L74-L75 report a completed write, while every other sentence
 * this screen paints is a refusal.
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

  const load = useCallback(
    /**
     * Reads the screen-shaped rendering of the addressed authorization.
     *
     * Assumptions: the SCREEN representation is read rather than the member record, because this screen
     * paints the reference's own rendered forms -- the masked card number, the edited amount, the
     * formatted date and time and the fraud status -- and the service derives every one of them from
     * its own constants and clock. Reading the member record and formatting here would put a second
     * implementation of those renderings in the client.
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
    setAuthorizationFraudState(selector, { action: nextFraudAction(detail.fraudMark) }).then(
      /**
       * Reports the reference program's own success sentence and re-reads the row.
       * @param {object} outcome - The success outcome, carrying that sentence.
       * @returns {void} Completion is represented by this screen's own state.
       */
      (outcome) => {
        setBusy(false);
        /*
         * WHY : Assumptions: the sentence is the SERVICE's rather than one chosen here, because which
         *       of `'ADD SUCCESS'` and `'UPDT SUCCESS'` applies depends on whether the write inserted
         *       a fraud row or updated one -- a distinction only the write path knows.
         *       `PROGRAM_MESSAGES.COPAUS2C` holds both for a reader, and neither is composed here.
         * WHY : Assumptions: the severity is `success`, which is the one place on this screen a
         *       non-error severity is used. The reference's own fraud sentences at `COPAUS1C.cbl`
         *       L74-L75 report a completed write rather than a refusal.
         */
        load(selector, { text: outcome.message, severity: 'success' });
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
          setSeverity('error');
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
         * Marks the authorization as fraud, or withdraws that marking.
         * @returns {void} Completion is represented by this screen's own state.
         */
        onInvoke: () => {
          submitFraudTransition();
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
   * WHY : Assumptions: the fixed-width values are rendered in the code face and the free-text ones are
   *       not, which is the distinction `TYPOGRAPHY_TOKENS.fixedPitchData` records -- the character grid
   *       aligned every column for free, and a proportional face gives digits different advance widths.
   *       The merchant name, city and state are excluded deliberately: they are proportional text with
   *       nothing to align against.
   */
  const fixedPitchValueStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

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
      <Typography.Title level={3}>{AUTH_DETAIL_SUBTITLE}</Typography.Title>
      {detail === null ? null : (
        <>
          {/*
            Trade-offs: the record is laid out by GROUPING rather than by the mapset's absolute
            coordinates, which is documented gap G1. Every field on this map carries a `POS=(row,col)`
            on a fixed 24x80 grid and none of that survives; what is kept is the two blocks the mapset
            separates with its own rule, the reading order within each, and the tab order, which
            follows the DOM order below.
          */}
          <Descriptions bordered column={2}>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.cardNumber}>
              {/*
                Assumptions: the rendering is emitted exactly as the service sent it -- this screen
                neither masks nor unmasks. `ui/src/api/authorization.ts` asserts on arrival that the
                value HAS been masked and rejects an unmasked one, so re-masking here would hide a
                server-side masking fault the client is positioned to report.
              */}
              <Typography.Text style={fixedPitchValueStyle}>{detail.cardNumber}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.transactionId}>
              <Typography.Text style={fixedPitchValueStyle}>{detail.transactionId}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.authDate}>
              <Typography.Text style={fixedPitchValueStyle}>{detail.authDate}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.authTime}>
              <Typography.Text style={fixedPitchValueStyle}>{detail.authTime}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.authResponse}>
              {detail.authResponse}
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.responseReason}>
              {detail.authResponseReason}
            </Descriptions.Item>
            {/*
              Assumptions: the amount is rendered in the code face and is never reformatted. The service
              answers it as an edited string because money is fixed point end to end and a JSON number
              would be parsed into a double by the client -- so this screen displays the string it was
              given, which is also why no currency symbol is added.
            */}
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.amount}>
              <Typography.Text style={fixedPitchValueStyle}>
                {detail.approvedAmount}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.posEntryMode}>
              {detail.posEntryMode}
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.source}>
              {detail.messageSource}
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.merchantCategoryCode}>
              <Typography.Text style={fixedPitchValueStyle}>
                {detail.merchantCategoryCode}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.cardExpiryDate}>
              <Typography.Text style={fixedPitchValueStyle}>{detail.cardExpiry}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.authType}>
              {detail.authType}
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.matchStatus}>
              {detail.matchStatus}
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.fraudStatus}>
              {detail.fraudMark}
            </Descriptions.Item>
          </Descriptions>
          <Typography.Title level={4}>{AUTH_DETAIL_MERCHANT_HEADING}</Typography.Title>
          <Descriptions bordered column={2}>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.merchantName}>
              {detail.merchantName}
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.merchantId}>
              <Typography.Text style={fixedPitchValueStyle}>{detail.merchantId}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.merchantCity}>
              {detail.merchantCity}
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.merchantState}>
              {detail.merchantState}
            </Descriptions.Item>
            <Descriptions.Item label={AUTH_DETAIL_FIELD_LABELS.merchantZip}>
              <Typography.Text style={fixedPitchValueStyle}>{detail.merchantZip}</Typography.Text>
            </Descriptions.Item>
          </Descriptions>
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
              onConfirm={submitFraudTransition}
            >
              <Button danger disabled={busy}>
                {AUTH_DETAIL_KEY_LABELS.PFK05}
              </Button>
            </Popconfirm>
            <Button disabled={busy} onClick={stepToNextAuthorization}>
              {AUTH_DETAIL_KEY_LABELS.PFK08}
            </Button>
          </Flex>
        </>
      )}
      <MessageBand mapset={AUTH_DETAIL_MAPSET} message={message} severity={severity} />
      <PfKeyBar keys={bindings} onInvoke={invoke} />
    </Flex>
  );
}

export default AuthDetailScreen;
