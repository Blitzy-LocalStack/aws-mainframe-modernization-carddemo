/**
 * @file The card update screen, migrated from `app/cbl/COCRDUPC.cbl` and its mapset
 * `app/bms/COCRDUP.bms` (34 `DFHMDF` fields), reached at the card edit route.
 *
 * Purpose
 * -------
 * Render the reference screen's fetch-then-replace workflow: read the current card, let an operator
 * edit the fields the reference permits, and submit the replacement behind an explicit confirmation.
 * It replaces CICS transaction CCUP, which `app/csd/CARDDEMO.CSD` L368-L369 binds to that program,
 * and it publishes the field labels, the active-status options and the guidance strings the screen
 * tests assert against.
 *
 * Concurrency
 * -----------
 * Assumptions: a conflicting concurrent change is reported to the operator rather than silently
 * overwritten. The reference performs this itself across the pseudo-conversational gap by comparing
 * a before-image of the record it read; the migrated service expresses the same guarantee with an
 * optimistic version column and answers HTTP 409, so this screen surfaces the data-changed message
 * and leaves the operator's edits in place to re-submit.
 *
 * Confirmation
 * ------------
 * Assumptions: the save is confirmed before it is sent, which is the browser form of the reference's
 * re-key-to-confirm convention. Losing it would make an accidental keypress a committed write.
 */

import { Button, Flex, Form, Input, Result, Select, Spin } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';

import { getCard, updateCard } from '../../api/cards';
import type { CardDetail, CardUpdateRequest } from '../../api/cards';
import { useShellSlot } from '../../layout/AppShell';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { useServerInstant } from '../../hooks/useServerInstant';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap } from '../../layout/usePfKeys';
import { STATUS_MESSAGES } from '../../messages/messages';
import { cardDetailPath, isCardSelector, requireCardSelector } from '../../routes/cards';
import { navigateSafely, navigationHandler } from '../../routes/navigation';
import { ScreenTitle } from '../../layout/ScreenTitle';

/** Screen-level messages this screen renders, taken verbatim from the catalog keyed by its program. */
const CARD_UPDATE_MESSAGES = STATUS_MESSAGES.COCRDUPC;

/** CICS transaction identifier this screen replaces, as `app/csd/CARDDEMO.CSD` L368-L369 defines it. */
export const CARD_UPDATE_TRANSACTION_ID = 'CCUP';

/** Source program name, rendered in the header band exactly as the 3270 screen did. */
export const CARD_UPDATE_PROGRAM_NAME = 'COCRDUPC';

/** Screen title, verbatim from the mapset's own title field at `app/bms/COCRDUP.bms` L78. */
export const CARD_UPDATE_TITLE = 'Update Credit Card Details';

/**
 * The five field labels and the expiry separator, verbatim from `app/bms/COCRDUP.bms`.
 *
 * Assumptions: the interior padding and the trailing space on two of them are part of the value, for
 * the reason the sibling detail screen records: the mapset pads each label so the colons align down
 * the column, and the transcription rule for this tree is byte-exact.
 */
export const CARD_UPDATE_FIELD_LABELS = {
  /** `app/bms/COCRDUP.bms` L83. */
  accountNumber: 'Account Number    :',
  /** `app/bms/COCRDUP.bms` L95. */
  cardNumber: 'Card Number       :',
  /** `app/bms/COCRDUP.bms` L106. */
  nameOnCard: 'Name on card      :',
  /** `app/bms/COCRDUP.bms` L116. */
  cardActive: 'Card Active Y/N   : ',
  /** `app/bms/COCRDUP.bms` L126. */
  expiryDate: 'Expiry Date       : ',
  /** `app/bms/COCRDUP.bms` L134 -- the separator painted between the expiry month and year. */
  expirySeparator: '/',
} as const;

/**
 * Function-key legend labels, split from this mapset's TWO legend fields.
 *
 * Assumptions: `app/bms/COCRDUP.bms` paints two fields on row 24, not one. `FKEYS` at L158-L162 is
 * `ATTRB=(ASKIP,NORM)` and always visible, carrying `ENTER=Process F3=Exit`; `FKEYSC` at L163-L167 is
 * `ATTRB=(ASKIP,DRK)` -- non-display -- carrying `F5=Save F12=Cancel`, and `app/cbl/COCRDUPC.cbl`
 * L1315-L1317 un-darkens it only under `PROMPT-FOR-CONFIRMATION`. So two of the four keys are painted
 * from the first turn and two appear only once edits have been validated, which is what the
 * conditional labels below reproduce.
 *
 * Assumptions: PF12's VALIDITY and its VISIBILITY differ in the source and both are preserved.
 * `app/cbl/COCRDUPC.cbl` L418 admits PF12 whenever the record has been fetched, while its legend is
 * painted only at the confirmation turn -- so the binding is registered throughout with an empty
 * label until then, which `usePfKeys` defines as a keyboard-only handler.
 */
const CARD_UPDATE_KEY_LABELS = {
  ENTER: 'ENTER=Process',
  PFK03: 'F3=Exit',
  PFK05: 'F5=Save',
  PFK12: 'F12=Cancel',
} as const;

/**
 * The two values the card-active flag admits, as `app/cbl/COCRDUPC.cbl` L196 states them.
 *
 * Alternatives Considered: `Active` and `Inactive`, which an earlier revision of this screen offered
 * as the two option labels. They are rejected because no COBOL source holds either word and because
 * they contradicted the label beside the control, which names the domain as `Card Active Y/N`. The
 * source's refusal sentence names the same two characters: `Card Active Status must be Y or N`.
 */
export const CARD_ACTIVE_OPTIONS = [
  { label: 'Y', value: 'Y' },
  { label: 'N', value: 'N' },
] as const;

/**
 * Guidance shown beside the refused-link result, which is a new string.
 *
 * Assumptions: the heading is the baseline's own `No input received`, declared at
 * `app/cbl/COCRDUPC.cbl` L186 for a turn that carried no usable key, and an absent or malformed
 * selector is exactly that. Only the guidance is new, because the selector is a target construct and
 * the baseline has no sentence about a value it never had.
 */
export const CARD_UPDATE_INVALID_LINK_GUIDANCE =
  'Return to the card list and select the record again.';

interface CardFormValues {
  readonly embossedName: string;
  readonly expirationMonth: string;
  readonly expirationYear: string;
  readonly activeStatus: 'Y' | 'N';
}

/*
 * WHY : Refactoring Rationale: the form edits the expiry MONTH and YEAR and offers no day field, where
 *       an earlier revision edited one ISO date. The baseline's update screen edits only those two
 *       parts -- its day input is rendered non-display at app/cbl/COCRDUPC.cbl:1285 and redisplayed
 *       from the pre-edit snapshot at :1123 -- so a date field let an operator change a value the
 *       screen it replaces does not expose. The day the stored date keeps is now the day it already
 *       held, supplied by the service, and no browser can influence it.
 * WHY : Assumptions: the two parts are seeded by SPLITTING the stored date, which is safe because the
 *       response constrains that value to exactly ten ISO characters -- four digits, a hyphen, two
 *       digits, a hyphen, two digits. Splitting by position rather than by parsing a date keeps the
 *       characters the service sent, with no locale or time zone able to shift them.
 */
const EXPIRATION_MONTH_PATTERN = /^(0[1-9]|1[0-2])$/u;

/*
 * WHY : Assumptions: the alphabet is the source's own, character for character.
 *       `app/cbl/COCRDUPC.cbl` L255-L257 declares the 52 characters it accepts as
 *       `ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz`, converts every one of them to a space
 *       at L823-L826 and then accepts the value only if what remains trims to nothing (L828) -- so a
 *       space passes and nothing else does. The class here is the ASCII letters and the space and
 *       deliberately NOT a Unicode letter property: `\p{L}` would admit accented and non-Latin letters
 *       the source rejects, which would let a value through the browser that the service then refuses.
 */
const EMBOSSED_NAME_PATTERN = /^[A-Za-z ]*$/u;

const EXPIRATION_YEAR_PATTERN = /^(19[5-9][0-9]|20[0-9]{2})$/u;

const EXPIRATION_YEAR_END = 4;

const EXPIRATION_MONTH_START = 5;

const EXPIRATION_MONTH_END = 7;

/**
 * Renders the card update form addressed by the opaque selector in its route.
 *
 * Assumptions: the route parameter is validated before any request is issued, for the same reason
 * recorded on the detail screen: a value that cannot address a card must not become a request.
 * @returns {ReactElement} The card update screen or a bounded invalid-link result.
 */
export function CardUpdateScreen(): ReactElement {
  const navigate = useNavigate();
  // WHY : Assumptions: read here, at the top of the component and above every early return, because
  //       the rules of hooks require an unconditional call site -- the early returns below would make
  //       a later call conditional. Reading it during render is deliberate rather than incidental: the
  //       band displays a PAINT-time instant, which is the property the baseline had because
  //       `POPULATE-HEADER-INFO` re-read the clock on each `SEND MAP` rather than on a timer.
  const paintedAt = useServerInstant();
  const { cardKey: routeIdentifier } = useParams<{
    cardKey: string;
  }>();
  const [form] = Form.useForm<CardFormValues>();
  const [card, setCard] = useState<CardDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [severity, setSeverity] = useState<MessageBandSeverity>('error');
  /*
   * WHY : Assumptions: this is the source program's `CCUP-CHANGES-OK-NOT-CONFIRMED` state, held here
   *       because the screen needs it and the server no longer can. `app/cbl/COCRDUPC.cbl` L414-L419
   *       admits PF5 ONLY in that state and PF12 only once the record has been fetched, so the two
   *       keys cannot be bound with the source's behaviour unless the state exists. In the baseline it
   *       lives in the passed structure between two pseudo-conversational turns; AAP section 0.7.1
   *       removes that structure, so the browser holds it -- which is the same relocation the
   *       navigation and selection fields underwent.
   *       Trade-offs: the two turns are preserved even though one request could validate and write
   *       together. Collapsing them would make Enter and PF5 do the same thing, leave the mapset's
   *       second legend field with nothing to reveal, and drop three sentences an operator reads --
   *       the confirmation prompt, the no-change refusal and the committed acknowledgement.
   */
  const [pendingValues, setPendingValues] = useState<CardFormValues | null>(null);
  /*
   * WHY : Assumptions: the read generation is a REF and not state, because nothing renders from it and
   *       it must be readable synchronously by a settlement that ran before the next render. A state
   *       value would be read from the closure of the render that opened the read, so every settlement
   *       would compare its own number against itself and every one of them would look current.
   */
  const readGeneration = useRef(0);
  const selector =
    routeIdentifier !== undefined && isCardSelector(routeIdentifier) ? routeIdentifier : null;

  /*
   * WHY : Refactoring Rationale: the read is a named callback because TWO callers need it -- the mount
   *       effect, and the PF12 arm which discards uncommitted edits by re-reading the record. The
   *       source has the same shape: `9000-READ-DATA` is one paragraph, performed on entry and again
   *       from the PF12 arm at `app/cbl/COCRDUPC.cbl` L484-L497. `useCallback` is what makes it usable
   *       as an effect dependency without re-running the effect on every render.
   * WHY : Assumptions: the band is cleared HERE, before the request is issued, and NOT in the
   *       resolution handler below. The difference is load-bearing for the cancel arm: it calls this
   *       reader and then states its own message synchronously, so a handler that cleared the band on
   *       arrival would blank a message the caller had already set. Clearing at the start leaves the
   *       caller's statement as the last write and needs no promise to be threaded back out.
   */
  const reload = useCallback(
    /**
     * Reads the record the form edits and seeds the form from it, discarding any uncommitted edit.
     *
     * ⚠️ Refactoring Rationale: each read opens a GENERATION and seeds the form only while that
     * generation is still the current one. Every read used to apply unconditionally, and two can be
     * outstanding at once for two ordinary reasons: a route change from one card to another re-creates
     * this callback and the effect re-runs it while the first request is in flight, and the cancel arm
     * re-reads on demand. Whichever settled LAST won, so following one row while a slower read of the
     * previous card was outstanding could seed this form -- and the version the save sends with it --
     * from the wrong card. That is worse here than on the detail screen: the retained record carries the
     * optimistic-lock value, so a stale seed would send a write against the version of a card the
     * operator is no longer editing.
     *
     * Alternatives Considered: `AbortController` threaded into `getCard`. Rejected because it would
     * widen the transport signature for every caller of that operation to fix an ordering property of
     * two screens, and because an aborted request still has to be prevented from applying -- so the
     * guard is needed either way.
     * @returns {void} Completion is represented by the screen's own state.
     */
    (): void => {
      if (selector === null) {
        setLoading(false);
        return;
      }

      const generation = readGeneration.current + 1;

      readGeneration.current = generation;
      setLoading(true);
      setPendingValues(null);
      setMessage(null);
      setSeverity('error');

      /**
       * Whether this read is still the one whose answer the form wants.
       * @returns {boolean} True while no later read has been opened.
       */
      const isCurrent = (): boolean => readGeneration.current === generation;

      getCard(selector).then(
        /**
         * Seeds the form with the three editable fields and retains the loaded
         * record, whose version carries the optimistic-lock value the save needs.
         * @param {CardDetail} selectedCard - The record the service returned.
         */
        (selectedCard) => {
          if (!isCurrent()) {
            return;
          }
          setCard(selectedCard);
          form.setFieldsValue({
            embossedName: selectedCard.embossedName,
            expirationYear: selectedCard.expirationDate.slice(0, EXPIRATION_YEAR_END),
            expirationMonth: selectedCard.expirationDate.slice(
              EXPIRATION_MONTH_START,
              EXPIRATION_MONTH_END,
            ),
            activeStatus: selectedCard.activeStatus,
          });
          setLoading(false);
        },
        /*
         * WHY : Trade-offs: one sentence covers every rejection, where the source branches on the file
         *       response -- `DFHRESP(NOTFND)` sets this sentence at `app/cbl/COCRDUPC.cbl`
         *       L1395-L1401 while any other response composes `WS-FILE-ERROR-MESSAGE` at L1402-L1411.
         *       The coarser one is chosen because the transport module reports a rejection without its
         *       status, and because the other branch's text appends the internal file name and the
         *       CICS response and reason codes, which are not carried across at all.
         *       Assumptions: the sentence names no card, so no identifier reaches the band or a log.
         */
        /*
         * WHY : ⚠️ Assumptions: a superseded FAILURE is discarded exactly as a superseded success is. It
         *       is the half that is easy to leave out, and leaving it out is worse than leaving out the
         *       other: the refusal sentence would appear over a form that had seeded correctly, so an
         *       operator would be told a card could not be read while editing it.
         */
        /** Reports a retrieval failure using the source program's own not-found sentence. */
        () => {
          if (!isCurrent()) {
            return;
          }
          setMessage(CARD_UPDATE_MESSAGES.DID_NOT_FIND_ACCTCARD_COMBO.text);
          setSeverity('error');
          setLoading(false);
        },
      );
    },
    [form, selector],
  );

  useEffect(
    /**
     * Reads the record once on mount and again whenever the validated selector changes.
     * @returns {void} Completion is represented by the screen's own state.
     */
    (): void => {
      reload();
    },
    [reload],
  );

  /**
   * Publishes one screen-level message with the appearance its source field had.
   *
   * Assumptions: the source screen has two message fields and this tree provides one band, so both
   * collapse onto it and the caller states which appearance applies. `INFOMSG` is a 40-character
   * `COLOR=NEUTRAL` field (`app/bms/COCRDUP.bms` L145-L149) carrying the prompts and the committed
   * acknowledgement; `ERRMSG` is an 80-character `COLOR=RED` field at row 23 (L152-L156) carrying the
   * refusals. The `field` member on each catalog entry records which of the two a sentence came from,
   * so the severity passed at each call site is read off the source rather than chosen.
   * @param {string | null} text - Sentence to render, or `null` to clear the band.
   * @param {MessageBandSeverity} appearance - Appearance matching the source field the sentence came
   *   from: `info` for `WS-INFO-MSG` and `error` for `WS-RETURN-MSG`.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function report(text: string | null, appearance: MessageBandSeverity): void {
    setMessage(text);
    setSeverity(appearance);
  }

  /**
   * Reports whether the submitted values differ from the record as it was read.
   *
   * Assumptions: the comparison is on the three editable members plus the expiry parts the form
   * exposes, because those are the whole of what this screen can change -- the day the stored
   * expiration date carries is redisplayed from the record and no browser can influence it. The source
   * makes the same comparison and refuses an unchanged submission with `No change detected with
   * respect to values fetched.` (`app/cbl/COCRDUPC.cbl` L188).
   * @param {CardFormValues} values - Values the form validated.
   * @param {CardDetail} loaded - The record as the service returned it.
   * @returns {boolean} `true` when at least one editable value differs.
   */
  function hasChanges(values: CardFormValues, loaded: CardDetail): boolean {
    return (
      values.embossedName !== loaded.embossedName ||
      values.activeStatus !== loaded.activeStatus ||
      values.expirationYear !== loaded.expirationDate.slice(0, EXPIRATION_YEAR_END) ||
      values.expirationMonth !==
        loaded.expirationDate.slice(EXPIRATION_MONTH_START, EXPIRATION_MONTH_END)
    );
  }

  /**
   * Validates the submitted edits and asks for confirmation, which is the source's Enter arm.
   *
   * Assumptions: this writes nothing. `app/cbl/COCRDUPC.cbl` edits the fields on Enter and, when they
   * are acceptable, reaches `PROMPT-FOR-CONFIRMATION` -- which sets the prompt at L167 and un-darkens
   * the second legend field at L1315-L1317 -- and only the later PF5 turn performs
   * `9200-WRITE-PROCESSING` (L988-L1001). Field-level refusals are raised by the form's own rules
   * before this runs, so what arrives here has already passed them.
   * @param {CardFormValues} values - Values validated by the Ant Design form.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function process(values: CardFormValues): void {
    /*
     * WHY : Assumptions: a submission arriving while a write is in flight is ignored, and the guard is
     *       here rather than expressed as a disabled binding. A disabled binding reports through the
     *       hook's invalid-key channel, and this screen's channel re-runs this arm -- so disabling it
     *       would route a suppressed key straight back into the thing it was suppressing. The source
     *       needs no such guard at all: a terminal turn is serialised, so a second Enter could not
     *       arrive while the first was still being processed.
     */
    if (saving) {
      return;
    }
    if (selector === null || card === null) {
      report(CARD_UPDATE_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text, 'error');
      return;
    }
    if (!hasChanges(values, card)) {
      setPendingValues(null);
      report(CARD_UPDATE_MESSAGES.NO_CHANGES_DETECTED.text, 'error');
      return;
    }
    setPendingValues(values);
    report(CARD_UPDATE_MESSAGES.PROMPT_FOR_CONFIRMATION.text, 'info');
  }

  /**
   * Writes the confirmed edits, which is the source's PF5 arm.
   *
   * Assumptions: the version travels with the request, so the service refuses a write against a record
   * another user has changed. The source holds the same guard as a before-image comparison across the
   * two turns and answers `Record changed by some one else. Please review`; here the comparison is the
   * service's and the refusal arrives as a rejected request.
   * @returns {void} Completion is represented by the screen's own state or a route change.
   */
  function save(): void {
    /* WHY : Assumptions: the same in-flight guard as `process`, so one confirmation writes once. */
    if (saving) {
      return;
    }
    if (selector === null || card === null || pendingValues === null) {
      report(CARD_UPDATE_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text, 'error');
      return;
    }

    const request: CardUpdateRequest = {
      ...pendingValues,
      version: card.version,
    };
    setSaving(true);
    report(null, 'error');
    updateCard(requireCardSelector(selector), request).then(
      /*
       * WHY : Assumptions: the return route is built from the selector the RESPONSE carries rather than
       *       from the one that addressed the card, and the two are the same value in every case this
       *       operation can produce -- a selector seals the primary key, an update cannot change that
       *       key, so the answer's selector equals the request's. Following the response's is chosen
       *       anyway because it costs nothing and keeps the screen correct if the sealing ever becomes
       *       key-rotating: a route built from a stale selector would resolve to nothing.
       */
      /**
       * Returns to the detail screen for the card that was addressed.
       * @param {CardDetail} updated - The card as the service reports it after the change, whose
       *   selector addresses the same row the request addressed.
       */
      (updated) => {
        setSaving(false);
        setPendingValues(null);
        navigateSafely(navigate, cardDetailPath(updated.key));
      },
      /*
       * WHY : Trade-offs: one sentence covers every rejected write, where the source distinguishes
       *       four outcomes -- `Could not lock record for update` (L206),
       *       `Update of record failed` (L210), `Record changed by some one else. Please review`
       *       (L208) and success. The transport module reports a rejection without its status, so
       *       naming a finer sentence would assert a cause this screen has not established; the
       *       coarsest of the three is true of all of them.
       *       Assumptions: the confirmation state is cleared, so a retry re-validates before writing
       *       again rather than replaying a stale set of values against a record that may have moved.
       */
      /** Reports a rejected write using the source program's own failure sentence. */
      () => {
        setSaving(false);
        setPendingValues(null);
        report(CARD_UPDATE_MESSAGES.LOCKED_BUT_UPDATE_FAILED.text, 'error');
      },
    );
  }

  /**
   * Discards uncommitted edits and redisplays the stored record, which is the source's PF12 arm.
   *
   * Assumptions: the source re-reads the record rather than restoring a snapshot --
   * `app/cbl/COCRDUPC.cbl` L484-L497 performs `9000-READ-DATA` again and re-sends the map with
   * `CCUP-SHOW-DETAILS` set -- so a cancel shows the record as it stands now, including a change
   * another user has committed in the meantime.
   * @returns {void} Completion is represented by the screen's own state.
   */
  function cancelEdits(): void {
    reload();
    report(CARD_UPDATE_MESSAGES.PROMPT_FOR_CHANGES.text, 'info');
  }

  /*
   * WHY : Assumptions: the handler map is BUILT rather than written as a literal, because two of its
   *       four members are conditional in the source. `app/cbl/COCRDUPC.cbl` L414-L419 admits PF5 only
   *       while validated edits await confirmation and PF12 only once the record has been fetched, so
   *       a fixed map would offer a write key on a turn the source refuses it -- and `usePfKeys`
   *       reports an unregistered key through the invalid-key channel, which is exactly the coercion
   *       the source performs for it.
   * WHY : Assumptions: PF12 carries a label only at the confirmation turn, which separates its validity
   *       from its visibility the way the mapset does -- `FKEYSC` is non-display until then, so the key
   *       works before its legend appears. An empty label is how `usePfKeys` expresses a handler with
   *       no painted legend.
   */
  const awaitingConfirmation = pendingValues !== null;

  const keyHandlers: PfKeyHandlerMap = {
    ENTER: {
      /** Validates the edits and asks for confirmation, the source's `ENTER=Process` action. */
      onInvoke: () => {
        form.submit();
      },
      label: CARD_UPDATE_KEY_LABELS.ENTER,
    },
    PFK03: {
      /**
       * Returns to the caller, which the source resolves to the screen it was reached from and
       * otherwise to the main menu (`app/cbl/COCRDUPC.cbl` L435-L477). The detail screen for the same
       * card is this route's caller, so it is the destination.
       */
      onInvoke: () => {
        navigateSafely(navigate, cardDetailPath(requireCardSelector(selector)));
      },
      label: CARD_UPDATE_KEY_LABELS.PFK03,
    },
    ...(awaitingConfirmation
      ? {
          PFK05: {
            /** Writes the confirmed edits, the source's `F5=Save` action. */
            onInvoke: save,
            label: CARD_UPDATE_KEY_LABELS.PFK05,
          },
        }
      : {}),
    ...(card === null
      ? {}
      : {
          PFK12: {
            /** Discards uncommitted edits and redisplays the record, the source's `F12=Cancel`. */
            onInvoke: cancelEdits,
            label: awaitingConfirmation ? CARD_UPDATE_KEY_LABELS.PFK12 : '',
          },
        }),
  };

  const { bindings, invoke } = usePfKeys(keyHandlers, {
    /**
     * Coerces an unrecognised or not-yet-valid key into the Enter arm, showing no message.
     *
     * Assumptions: an unrecognised or not-yet-valid key re-runs the Enter arm and shows no
     * message, which is what `app/cbl/COCRDUPC.cbl` L412-L423 does -- it sets an invalid flag
     * and then `SET CCARD-AID-ENTER TO TRUE`. That covers PF5 pressed before the edits have
     * been validated, which is precisely the case the source coerces rather than refusing.
     * @returns {void} Nothing; the coerced arm submits the form itself.
     */
    onInvalidKey: () => {
      form.submit();
    },
  });

  /*
   * WHY : ⚠️ Refactoring Rationale: this screen DELEGATES its title band and its key legend to
   *       the shell instead of painting them itself. `ui/src/layout/AppShell.tsx` is mounted as
   *       the authenticated layout route, so the frame is painted once above the outlet rather
   *       than rebuilt per screen; a screen that also painted them would show two title bands
   *       and two legends. The message band stays local, because the shell paints a zone only
   *       when it is delegated and this screen's message is bound to controls in its own body.
   * WHY : ⚠️ Assumptions: the legend is delegated rather than dropped, so the SCREEN keeps
   *       owning the keyboard -- `bindings` and `invoke` come from this screen's own `usePfKeys`
   *       call and travel up unchanged, and an activation of a rendered legend control is
   *       forwarded straight back to `invoke`. The claim that stood here, that the shell "adds its
   *       sign-off key beside them only when this screen leaves that attention identifier free,
   *       which is decided by AID in the shell", is withdrawn: the shell installs NO keyboard
   *       listener at all and offers sign-off as a rendered control, for the reason recorded at
   *       `SHELL_SIGN_OFF_LABEL`. So there is no second listener to stand down and no AID
   *       arbitration anywhere -- this screen's bindings are the only ones on the document while it
   *       is mounted.
   */
  /*
   * WHY : ⚠️ Refactoring Rationale: the row-23 message line is delegated WITH the title band and the
   *       legend. An earlier revision of this delegation withheld it, on the stated ground that this
   *       screen's message is bound to controls in its own body -- but this screen stopped composing a
   *       band when it stopped composing the header, so every sentence it reports through `report`
   *       had nowhere to be painted. Publishing it here restores the one row-23 field the mapset has.
   * WHY : Assumptions: the publication is unconditional and ABOVE both early returns, because a hook
   *       called after one of them would change hook order between renders. The two erased states
   *       publish an EMPTY key list and no header or message, which is what they rendered before the
   *       shell existed: the reference answers an unaddressable selector with `SEND TEXT ... ERASE`
   *       rather than by re-sending the map, and a read in flight has no counterpart in it at all.
   * WHY : ⚠️ Trade-offs: an empty list is published rather than the member being omitted, and
   *       the reason recorded here -- that this screen binds PF12 as `cancel` while the shell binds PF12
   *       as sign-off "whenever no screen has published keys", so omitting it would put both listeners on
   *       the document and one press would cancel the edit AND end the session -- is withdrawn. The shell
   *       installs NO keyboard listener at all and offers sign-off as a rendered control, for the reason
   *       recorded at `SHELL_SIGN_OFF_LABEL`, so this screen's PF12 is the only PF12 on the document
   *       either way. Both forms render the same legend, none: `PfKeyBar` returns `null` for an empty
   *       binding list. The member is kept in both arms so they differ only in which zones carry content.
   */
  useShellSlot(
    selector === null || loading
      ? { pfKeys: { keys: [], onInvoke: invoke } }
      : {
          screen: {
            transactionId: CARD_UPDATE_TRANSACTION_ID,
            programName: CARD_UPDATE_PROGRAM_NAME,
          },
          now: paintedAt,
          message: { text: message, severity },
          pfKeys: { keys: bindings, onInvoke: invoke },
        },
  );

  if (selector === null) {
    return (
      <Result
        status="error"
        title={CARD_UPDATE_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text}
        subTitle={CARD_UPDATE_INVALID_LINK_GUIDANCE}
        extra={
          <Button onClick={navigationHandler(navigate, '/cards')}>
            {CARD_UPDATE_KEY_LABELS.PFK03}
          </Button>
        }
      />
    );
  }
  if (loading) {
    return <Spin size="large" />;
  }

  return (
    <Flex vertical gap="large">
      {/*
       * Assumptions: the header band is composed here because both of its values belong to this
       * screen -- the transaction identifier and the program name rows 1 and 2 of the 3270 screen
       * painted -- and `ScreenHeaderProps` requires both, so no shell could supply them.
       */}
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
      <ScreenTitle>{CARD_UPDATE_TITLE}</ScreenTitle>
      {/*
       * Refactoring Rationale: `onFinish` runs the VALIDATE arm, not the write. The source screen's
       * Enter key edits the fields and asks for confirmation, and only the later PF5 turn writes, so
       * submitting the form is the first of those two and `save` is reached from the PF5 binding.
       */}
      <Form<CardFormValues> form={form} layout="vertical" onFinish={process} requiredMark>
        {/*
         * Refactoring Rationale: the length rule is gone and `maxLength` alone carries the constraint.
         * The rule could never fire -- the control refuses a 51st character -- so its message was
         * unreachable text with no source in the baseline, and ADR-006 already states that an input's
         * maximum length IS its copybook picture width, which `CARD-EMBOSSED-NAME PIC X(50)` fixes.
         * Assumptions: both remaining refusals are the source program's own sentences, and the
         * alphabetic rule is one the earlier revision omitted entirely.
         */}
        <Form.Item
          label={CARD_UPDATE_FIELD_LABELS.nameOnCard}
          name="embossedName"
          rules={[
            { required: true, message: CARD_UPDATE_MESSAGES.WS_PROMPT_FOR_NAME.text },
            {
              pattern: EMBOSSED_NAME_PATTERN,
              message: CARD_UPDATE_MESSAGES.WS_NAME_MUST_BE_ALPHA.text,
            },
          ]}
        >
          <Input maxLength={50} />
        </Form.Item>
        {/*
         * Assumptions: the month and the year are two fields answering to two different refusals, and
         * that is the baseline's own arrangement rather than a decomposition chosen here: it answers a
         * bad month with 'Card expiry month must be between 1 and 12' at app/cbl/COCRDUPC.cbl:197-198
         * and a bad year with 'Invalid card expiry year' at :199-200, from two separate edit
         * paragraphs. One combined field could carry only one of those two messages.
         */}
        {/*
         * Refactoring Rationale: all four refusal sentences are now the source program's own, and the
         * two that were closest are the reason this mattered: an earlier revision rendered
         * `Card expiry month must be between 1 and 12.` and `Invalid card expiry year.`, each with a
         * trailing full stop the COBOL literals at `app/cbl/COCRDUPC.cbl` L198 and L200 do not carry.
         * A one-character divergence reads as correct in review, which is exactly why the text is
         * taken from the catalog rather than retyped.
         * Assumptions: the month and the year stay two controls answering to two refusals, because the
         * source declares two separate sentences for them; one combined control could carry only one.
         * The separator the mapset paints between them is rendered between the two labels.
         */}
        <Form.Item
          label={`${CARD_UPDATE_FIELD_LABELS.expiryDate}${CARD_UPDATE_FIELD_LABELS.expirySeparator}`}
          name="expirationMonth"
          rules={[
            { required: true, message: CARD_UPDATE_MESSAGES.CARD_EXPIRY_MONTH_NOT_VALID.text },
            {
              pattern: EXPIRATION_MONTH_PATTERN,
              message: CARD_UPDATE_MESSAGES.CARD_EXPIRY_MONTH_NOT_VALID.text,
            },
          ]}
        >
          <Input maxLength={2} inputMode="numeric" />
        </Form.Item>
        <Form.Item
          label={CARD_UPDATE_FIELD_LABELS.expiryDate}
          name="expirationYear"
          rules={[
            { required: true, message: CARD_UPDATE_MESSAGES.CARD_EXPIRY_YEAR_NOT_VALID.text },
            {
              pattern: EXPIRATION_YEAR_PATTERN,
              message: CARD_UPDATE_MESSAGES.CARD_EXPIRY_YEAR_NOT_VALID.text,
            },
          ]}
        >
          <Input maxLength={4} inputMode="numeric" />
        </Form.Item>
        <Form.Item
          label={CARD_UPDATE_FIELD_LABELS.cardActive}
          name="activeStatus"
          rules={[
            { required: true, message: CARD_UPDATE_MESSAGES.CARD_STATUS_MUST_BE_YES_NO.text },
          ]}
        >
          <Select options={[...CARD_ACTIVE_OPTIONS]} />
        </Form.Item>
      </Form>
      {/*
       * Refactoring Rationale: the bespoke `Cancel` and `Save` controls are gone, and the delegated key
       * legend carries both as F12 and F5. Keeping them would have put two controls behind each action
       * with two chances to diverge, and the `Save` control specifically wrote without the
       * confirmation turn the source requires -- so the mapset's second legend field, which exists
       * only to reveal those two keys, would have had nothing to reveal.
       * Assumptions: no legend colour is delegated, which `app/bms/COCRDUP.bms` L159 and L164 confirm --
       * both of this screen's legend fields are `COLOR=YELLOW`, the majority the slot already defaults
       * to.
       */}
    </Flex>
  );
}
