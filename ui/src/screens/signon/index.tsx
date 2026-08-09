/**
 * @file The sign-on screen, migrated from `app/cbl/COSGN00C.cbl` and its mapset
 * `app/bms/COSGN00.bms` (37 `DFHMDF` fields), reached at route `/signon`.
 *
 * Purpose
 * -------
 * Exchange a user identifier and a password for a token, answer the replacement-password challenge
 * when the identity provider issues one, and route onward to the administrative or the main menu.
 * It replaces CICS transaction CC00 and is the one route outside the authentication guard, because
 * it is the route that establishes the credential every other route requires.
 *
 * What replaced the reference's own comparison
 * -------------------------------------------
 * Assumptions: the reference reads the security file and compares an eight-character password held
 * in clear at `app/cpy/CSUSR01Y.cpy:21`, and that field is not carried forward anywhere in the
 * target. Sign-on here is a token exchange against the managed identity provider, so this screen
 * transmits the credential in a request BODY and never holds it after the exchange. The onward
 * branch is taken from the SIGNED group claim rather than from a field the client supplied, which is
 * the security property the migration gains: the reference branched on a value echoed back to it in
 * the communication area.
 *
 * Message fidelity
 * ----------------
 * Assumptions: every string an operator reads here is a catalog constant taken verbatim from the
 * originating program, trailing ellipses included, so the wording is the reference's and not this
 * screen's.
 */

import { Button, Card, Flex, Form, Input, Space } from 'antd';
import { useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate } from 'react-router';

import { PASSWORD_MAX_LENGTH, SIGN_ON_CHALLENGE, USER_ID_MAX_LENGTH } from '../../api/auth';
import type { SignOnChallenge } from '../../api/auth';
import { ADMIN_GROUP, groupsFromIdToken, useAuth } from '../../hooks/useAuth';
import { MessageBand } from '../../layout/MessageBand';
import { PfKeyBar } from '../../layout/PfKeyBar';
import { ScreenHeader } from '../../layout/ScreenHeader';
import { useServerInstant } from '../../hooks/useServerInstant';
import { usePfKeys } from '../../layout/usePfKeys';
import { INVALID_KEY_PRESSED, PROGRAM_MESSAGES, THANK_YOU_CCDA } from '../../messages/messages';
import { ADMIN_MENU_ROUTE, MAIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';

/** The five sign-on messages, taken verbatim from the catalog keyed by their source program. */
const SIGN_ON_MESSAGES = PROGRAM_MESSAGES.COSGN00C;

/** CICS transaction identifier this screen replaces, as `app/csd/CARDDEMO.CSD` defines it. */
const SIGN_ON_TRANSACTION_ID = 'CC00';

/** Source program name, rendered in the header band exactly as the 3270 screen did. */
const SIGN_ON_PROGRAM_NAME = 'COSGN00C';

/**
 * Function-key legend labels, taken from the source mapset's own legend literal.
 *
 * Assumptions: `app/bms/COSGN00.bms` paints exactly `ENTER=Sign-on  F3=Exit` on its legend row, so
 * these two aids are the complete key contract for this screen and no third is bound. A clear key was
 * bound here in an earlier revision of this screen on the assumption that the uniform legend applied;
 * the mapset does not paint one, and binding a key the source screen does not offer would invent
 * behaviour rather than migrate it.
 */
const SIGN_ON_KEY_LABELS = {
  ENTER: 'ENTER=Sign-on',
  PFK03: 'F3=Exit',
} as const;

/** Field identifiers, declared so each label is programmatically associated with its control. */
const USER_ID_FIELD_ID = 'signon-user-id';

/** Identifier of the password control, which is either the current or the replacement field. */
const PASSWORD_FIELD_ID = 'signon-password';

/**
 * Renders the sign-on screen migrated from `COSGN00C`.
 *
 * Assumptions: the two fields are the two the source screen had, at the widths its copybook
 * declares — eight characters of user identifier and a non-display password field. The password uses
 * `Input.Password` with the visibility toggle suppressed, which is gap G2 in the design-system
 * analysis.
 *
 * <p>Trade-offs: a 3270 non-display field renders truly blank, and this renders one dot per
 * character. The divergence is cosmetic and is accepted for a specific, stated mechanism rather
 * than because it looks better: the dots confirm that each keypress was ACCEPTED while revealing
 * none of the characters, so an operator who mistypes or whose keyboard drops a key sees the count
 * disagree with what they typed and can correct it before submitting. On the truly blank 3270 field
 * that same mistake is invisible until the exchange is refused, and the refusal message cannot say
 * which of the two fields was wrong. What is given up is the one property the blank field has that
 * dots do not — an observer beside the operator cannot read the credential's LENGTH off the screen —
 * and the visibility toggle is suppressed so that nothing beyond the length can be recovered from
 * the rendering at all. No behaviour differs: the same characters are submitted either way.
 *
 * Assumptions: every message rendered here is a catalog constant rather than a literal, so the
 * baseline's exact wording — including its trailing ellipses — is what an operator reads.
 * @returns {ReactElement} The sign-on screen.
 */
export function SignOnScreen(): ReactElement {
  const navigate = useNavigate();
  const { signIn, answerChallenge } = useAuth();
  // WHY : Assumptions: read here, at the top of the component and above every early return, because
  //       the rules of hooks require an unconditional call site -- the early returns below would make
  //       a later call conditional. Reading it during render is deliberate rather than incidental: the
  //       band displays a PAINT-time instant, which is the property the baseline had because
  //       `POPULATE-HEADER-INFO` re-read the clock on each `SEND MAP` rather than on a timer.
  const paintedAt = useServerInstant();
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [challenge, setChallenge] = useState<SignOnChallenge | null>(null);
  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');

  /**
   * Routes a signed-on operator to the surface their group claim admits.
   *
   * Assumptions: the destination is chosen from the claim rather than from anything the caller
   * supplied, which is the stateless replacement for the source program's transfer to either the
   * administrative or the ordinary menu.
   *
   * Refactoring Rationale: the claim is read from the identity token passed in, NOT from the hook's
   * `isAdmin`. Both are derived from the same claim, but the hook's copy is a render-time value and
   * this runs inside the promise that installed the token, before React has re-rendered — so
   * `isAdmin` still describes the previous session, and reading it sent every administrator to the
   * ordinary menu. Passing the token makes the decision use the value that was just issued.
   * @param {string} idToken - Identity token issued moments ago by sign-on or by a challenge answer.
   */
  function enterApplication(idToken: string): void {
    const admin = groupsFromIdToken(idToken).includes(ADMIN_GROUP);
    navigateSafely(navigate, admin ? ADMIN_MENU_ROUTE : MAIN_MENU_ROUTE);
  }

  /**
   * Validates both fields and exchanges them for tokens.
   *
   * Assumptions: the two blank checks run in the source program's own order — identifier first, then
   * password — so an operator who leaves both empty is told about the identifier, exactly as the
   * baseline does.
   * @returns {Promise<void>} Resolves once the outcome has been applied to screen state.
   */
  async function submitSignOn(): Promise<void> {
    if (userId.trim().length === 0) {
      setMessage(SIGN_ON_MESSAGES.PLEASE_ENTER_USER_ID);
      return;
    }
    if (password.length === 0) {
      setMessage(SIGN_ON_MESSAGES.PLEASE_ENTER_PASSWORD);
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const outcome = await signIn(userId.trim(), password);
      if (outcome.outcome === SIGN_ON_CHALLENGE) {
        setChallenge(outcome);
        return;
      }
      enterApplication(outcome.idToken);
    } catch {
      // WHY : Assumptions: one message covers every refusal, and it is the baseline's own
      //       verify-failure wording rather than a per-status message. The service deliberately does
      //       not distinguish an unknown identifier from a wrong password in its 401 — telling a
      //       caller which of the two failed is how an identifier is confirmed to exist — so a
      //       client that rendered two different messages would reintroduce exactly the
      //       enumeration the service refuses to permit.
      setMessage(SIGN_ON_MESSAGES.UNABLE_TO_VERIFY_THE_USER);
    } finally {
      setBusy(false);
    }
  }

  /**
   * Answers an outstanding challenge with a replacement password.
   * @returns {Promise<void>} Resolves once the outcome has been applied to screen state.
   */
  async function submitChallenge(): Promise<void> {
    if (challenge === null || newPassword.length === 0) {
      setMessage(SIGN_ON_MESSAGES.PLEASE_ENTER_PASSWORD);
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const tokens = await answerChallenge(challenge, newPassword);
      enterApplication(tokens.idToken);
    } catch {
      setMessage(SIGN_ON_MESSAGES.UNABLE_TO_VERIFY_THE_USER);
    } finally {
      setBusy(false);
    }
  }

  /**
   * Ends the session and reports the source program's own farewell.
   *
   * Assumptions: this is the migration of `WHEN DFHPF3`, which moves `CCDA-MSG-THANK-YOU` into the
   * message field and sends plain text — the source program's exit path. The browser has no task to
   * terminate, so the equivalent is to discard the entry fields and show the same sentence; the
   * operator remains on a screen that can sign on again, which is the closest reachable analogue of a
   * terminated transaction that could be restarted.
   */
  function exitApplication(): void {
    setUserId('');
    setPassword('');
    setNewPassword('');
    setChallenge(null);
    setMessage(THANK_YOU_CCDA);
  }

  const submit = challenge === null ? submitSignOn : submitChallenge;

  /**
   * Runs the visible form's submission with an explicit rejection handler.
   *
   * Assumptions: both submit functions already convert every failure into a message, so this handler
   * catches only a defect in that conversion. It still reports through the same band rather than
   * being empty, because an empty handler is how a real fault becomes invisible.
   */
  function runSubmit(): void {
    submit().catch(
      /**
       * Reports the source program's verification failure when the conversion above did not.
       * @returns {void} Nothing; the message band and the busy flag carry the outcome.
       */
      () => {
        setMessage(SIGN_ON_MESSAGES.UNABLE_TO_VERIFY_THE_USER);
        setBusy(false);
      },
    );
  }

  // WHY : Assumptions: the handler map is keyed by CICS attention identifier rather than by a
  //       browser key name, so the PF13-PF24 aliasing the source copybook performs is applied by
  //       the hook once instead of by every screen. Only the two aids this screen's source program
  //       acts on are bound, and they are the two its mapset paints: Enter signs on and PF03 exits.
  // WHY : Refactoring Rationale: this note previously said the two aids were "Enter submits and PF04
  //       clears", which described an earlier revision that had assumed the uniform legend applied
  //       here. `app/bms/COSGN00.bms` paints no clear key, so PF04 was replaced by PF03 in the map
  //       below; the sentence is corrected rather than deleted so a reader comparing the two
  //       revisions sees why the binding changed.
  const { bindings, invoke } = usePfKeys(
    {
      ENTER: {
        /** Submits the visible form, matching the source screen's Enter action. */
        onInvoke: () => {
          runSubmit();
        },
        label: SIGN_ON_KEY_LABELS.ENTER,
      },
      PFK03: {
        /** Ends the session with the source program's farewell message. */
        onInvoke: exitApplication,
        label: SIGN_ON_KEY_LABELS.PFK03,
      },
    },
    {
      /**
       * Reports the source program's own invalid-key sentence for an unbound key.
       *
       * Assumptions: an unbound key reports that sentence, which is what its `WHEN OTHER` branch
       * does — it sets the error flag, moves `CCDA-MSG-INVALID-KEY` into the message field and
       * re-sends the screen. Without this the hook would swallow the keystroke silently, and an
       * operator pressing a key that used to produce a message would get no feedback at all.
       * @returns {void} Nothing; the message band carries the outcome.
       */
      onInvalidKey: () => {
        setMessage(INVALID_KEY_PRESSED);
      },
    },
  );

  return (
    <Flex vertical gap="large">
      {/*
       * WHY : Refactoring Rationale: `now` is supplied. It was omitted here, and at every other call
       *       site, although `ScreenHeader`'s contract states that the composing caller must pass a
       *       SERVER-derived instant -- so the band fell through to `dayjs()` and displayed the
       *       BROWSER's clock. The baseline read one region clock for every terminal
       *       (`FUNCTION CURRENT-DATE` at `app/cbl/COSGN00C.cbl:179`), so the omission meant two
       *       operators looking at one record could read two different dates across midnight.
       *       Assumptions: the value comes from a hook rather than from a shell component. Both of the
       *       band's other inputs are this screen's own, as the note above records, so a shell could
       *       not render the band on this screen's behalf; the hook supplies the one value that is
       *       NOT screen-specific without inventing a component to hold it.
       */}
      <ScreenHeader
        transactionId={SIGN_ON_TRANSACTION_ID}
        programName={SIGN_ON_PROGRAM_NAME}
        now={paintedAt}
      />
      <MessageBand message={message} />
      <Card>
        <Form layout="vertical">
          <Form.Item label="User ID" htmlFor={USER_ID_FIELD_ID}>
            <Input
              id={USER_ID_FIELD_ID}
              autoFocus
              maxLength={USER_ID_MAX_LENGTH}
              value={userId}
              disabled={challenge !== null}
              onChange={
                /**
                 * Records the entered identifier.
                 * @param {{ target: { value: string } }} event - Change event from the field.
                 */
                (event) => {
                  setUserId(event.target.value);
                }
              }
            />
          </Form.Item>
          {challenge === null ? (
            <Form.Item label="Password" htmlFor={PASSWORD_FIELD_ID}>
              <Input.Password
                id={PASSWORD_FIELD_ID}
                visibilityToggle={false}
                maxLength={PASSWORD_MAX_LENGTH}
                value={password}
                onChange={
                  /**
                   * Records the entered password.
                   * @param {{ target: { value: string } }} event - Change event from the field.
                   */
                  (event) => {
                    setPassword(event.target.value);
                  }
                }
              />
            </Form.Item>
          ) : (
            <Form.Item label="New Password" htmlFor={PASSWORD_FIELD_ID}>
              <Input.Password
                id={PASSWORD_FIELD_ID}
                visibilityToggle={false}
                maxLength={PASSWORD_MAX_LENGTH}
                value={newPassword}
                onChange={
                  /**
                   * Records the replacement password answering the challenge.
                   * @param {{ target: { value: string } }} event - Change event from the field.
                   */
                  (event) => {
                    setNewPassword(event.target.value);
                  }
                }
              />
            </Form.Item>
          )}
          <Space>
            <Button
              type="primary"
              loading={busy}
              onClick={
                /** Submits the visible form. */
                () => {
                  runSubmit();
                }
              }
            >
              Sign on
            </Button>
          </Space>
        </Form>
      </Card>
      <PfKeyBar keys={bindings} onInvoke={invoke} />
    </Flex>
  );
}
