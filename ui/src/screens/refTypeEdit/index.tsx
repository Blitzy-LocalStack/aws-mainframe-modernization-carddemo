/**
 * @file The transaction-type maintenance screen, migrated from
 * `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` and its mapset
 * `app/app-transaction-type-db2/bms/COTRTUP.bms` (map `CTRTUPA`, `DFHMDI ... SIZE=(24,80)`), mounted at
 * both `/reference/transaction-types/new` and `/reference/transaction-types/:typeCd`.
 *
 * Purpose
 * -------
 * Take a two-character transaction-type code, show the row it names, and let an administrator add,
 * change or delete it. It replaces the reference program the administrative menu lists as option 6,
 * "Transaction Type Maintenance (Db2)".
 *
 * Why this screen had to exist
 * ---------------------------
 * Refactoring Rationale: `ui/src/screens/refTypeList/index.tsx` binds PF2 to
 * `REF_TYPE_ADD_ROUTE = '/reference/transaction-types/new'` and nothing was mounted there, so the add
 * key resolved to the router's not-found result. That path is served by this screen through the
 * {@link REF_TYPE_NEW_SENTINEL} segment of {@link REF_TYPE_EDIT_ROUTE}. That screen's own comment records why the destination
 * is a sentinel segment rather than a code -- the reference transfers to this program with no key set
 * and lets it prompt -- and this module is the screen that does the prompting.
 *
 * The stage machine is the reference's, not an interpretation of it
 * ---------------------------------------------------------------
 * Assumptions: the reference drives this screen through the `TTUP-*` condition names on one status
 * field, and {@link RefTypeEditStage} carries exactly the five of them that decide what the operator
 * may do next. Which function keys are live in each stage is not a judgement made here either:
 * `3391-SETUP-PFKEY-ATTRS` at `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` L1397-L1423 brightens or
 * darkens each legend field per stage, and {@link stageKeyAvailability} transcribes that paragraph line
 * for line. A stage-to-key table written from the prose of the program's comments would have been an
 * interpretation; this one is a transcription of the code that paints the terminal.
 *
 * Assumptions: `F6=Add` is deliberately NOT bound, and its absence is measured rather than assumed. The
 * mapset declares `FKEY06` as `ATTRB=(ASKIP,DRK)` -- non-display -- at `COTRTUP.bms` L127-L134, the
 * program never brightens it (L1397-L1423 reveals only `FKEY04A`, `FKEY05A` and `FKEY12A`), and it tests
 * `CCARD-AID-PFK06` nowhere at all. So the terminal never showed that legend and no key press could
 * reach it; binding one here would invent an action, and painting the legend would promise one.
 *
 * This screen holds no session state
 * ---------------------------------
 * Assumptions: the reference keeps its stage, its before-image `TTUP-OLD-DETAILS` and its calling
 * program in the passed communication area, and none of the three survives as session state. The stage
 * is component state for the duration of one mount, the before-image is the record last read from the
 * service together with the version the service issued -- so a concurrent change is caught by the
 * service rather than by a remembered copy -- and the return destination is the administrative menu
 * route the reference falls back to when it has no caller recorded.
 */

import { Button, Flex, Form, Input, Popconfirm, Typography, theme } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import type { CSSProperties, ChangeEvent, ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';

import { isApiRequestError } from '../../api/client';
import {
  createTransactionType,
  deleteTransactionType,
  getTransactionType,
  replaceTransactionType,
} from '../../api/reference';
import type { TransactionType } from '../../api/reference';
import { useServerInstant } from '../../hooks/useServerInstant';
import { MessageBand } from '../../layout/MessageBand';
import type { MessageBandSeverity } from '../../layout/MessageBand';
import { PfKeyBar } from '../../layout/PfKeyBar';
import { ScreenHeader } from '../../layout/ScreenHeader';
import { usePfKeys } from '../../layout/usePfKeys';
import type { PfKeyHandlerMap } from '../../layout/usePfKeys';
import {
  FIELD_VALIDATION_SUFFIXES,
  PROGRAM_MESSAGES,
  SHARED_MESSAGES,
  STATUS_MESSAGES,
} from '../../messages/messages';
import { ADMIN_MENU_ROUTE, navigateSafely } from '../../routes/navigation';
import { BMS_COLOR_TOKENS, FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../../theme/tokens';

/*
 * WHY : Alternatives Considered: declaring the ADD destination here as well, beside the pattern below.
 *       Rejected because `ui/src/screens/refTypeList/index.tsx` already owns it as `REF_TYPE_ADD_ROUTE`
 *       and is the module that navigates to it, so a second declaration would be two places for one
 *       path to drift apart -- and this module cannot import that one, because each screen is a separate
 *       lazily loaded chunk and a module-scope import between them would merge the two. What this module
 *       owns instead is the SENTINEL it has to recognise, which is the only part of that path this screen
 *       reads; `ui/src/routerRoutes.test.tsx` holds the two halves in agreement.
 */

/** Route pattern that enters this screen, whose parameter is a key or the add sentinel. */
export const REF_TYPE_EDIT_ROUTE = '/reference/transaction-types/:typeCd';

/**
 * Route segment that means "no key selected", which is the sentinel the list screen navigates to.
 *
 * Assumptions: a three-character sentinel cannot be mistaken for a real key, because `TR_TYPE` is
 * `CHAR(2)` in `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` L2 -- so no code can ever be `new`. That
 * is the same reasoning `ui/src/screens/refTypeList/index.tsx` records at the constant it navigates by,
 * restated here because this is the module that has to recognise it.
 */
export const REF_TYPE_NEW_SENTINEL = 'new';

/** CICS transaction identifier this screen replaces, from `COTRTUPC.cbl` L44 and L358. */
export const REF_TYPE_EDIT_TRANSACTION_ID = 'CT03';

/** Source program name, from `COTRTUPC.cbl` L202. */
export const REF_TYPE_EDIT_PROGRAM_NAME = 'COTRTUPC';

/** Mapset this screen stands in, which fixes the message band's rendered width. */
export const REF_TYPE_EDIT_MAPSET = 'COTRTUP';

/** Row-7 sub-title, from `COTRTUP.bms` L76-L78. */
export const REF_TYPE_EDIT_SUBTITLE = 'Maintain Transaction Type';

/**
 * The two field labels this mapset paints, carried with their internal padding.
 *
 * Assumptions: the padding before each colon is part of the painted literal -- the mapset writes
 * `'Transaction Type  :'` and `'Description       :'` so that both colons fall in the same column of
 * the character grid. It is carried rather than trimmed because Transformation Rule T8 carries a
 * user-visible string character for character, and because trimming it would be a second spelling of a
 * literal the catalog's own boundary assigns to the renderer.
 */
export const REF_TYPE_EDIT_FIELD_LABELS = {
  /** `COTRTUP.bms` L79-L83. */
  typeCode: 'Transaction Type  :',
  /** `COTRTUP.bms` L89-L93. */
  description: 'Description       :',
} as const;

/** Declared width of `TRTYPCD`, from `COTRTUP.bms` L84-L86. */
export const TYPE_CODE_WIDTH = 2;

/** Declared width of `TRTYDSC`, from `COTRTUP.bms` L94-L96. */
export const DESCRIPTION_WIDTH = 50;

/*
 * WHY : Assumptions: each control carries an EXPLICIT identifier and its label an explicit `htmlFor`,
 *       rather than letting the design system infer the association. The system wires a label to a
 *       control only for a form item holding one named field, and each item here holds two children --
 *       the control and the literal blank marker the baseline's templated highlight writes -- so nothing
 *       is inferred and the label would name no control at all. On the terminal the association WAS the
 *       position, at `POS=(12,26)` and `POS=(14,26)` beside their labels; design gap G1 gives that up, so
 *       the association has to be stated or it is lost rather than merely moved.
 */

/** Identifier joining the type-code label to its control. */
export const TYPE_CODE_CONTROL_ID = 'ref-type-edit-type-code';

/** Identifier joining the description label to its control. */
export const DESCRIPTION_CONTROL_ID = 'ref-type-edit-description';

/**
 * The four legend labels this mapset paints on row 24, from `COTRTUP.bms` L111-L135.
 *
 * Assumptions: the first field carries two labels in one literal, `'ENTER=Process F3=Exit'`, separated
 * by a single space rather than the double space the other mapsets use -- so it is split at that space
 * and both halves are carried as the mapset spells them. The remaining three are fields of their own.
 * `F6=Add` is absent for the reason the file overview records.
 */
export const REF_TYPE_EDIT_KEY_LABELS = {
  /** Enter validates what was typed and decides the next stage. */
  ENTER: 'ENTER=Process',
  /** PF3 leaves the screen for the administrative menu. */
  PFK03: 'F3=Exit',
  /** PF4 asks for delete confirmation, and confirms it on a second press. */
  PFK04: 'F4=Delete',
  /** PF5 accepts a new row, and saves validated changes on a second press. */
  PFK05: 'F5=Save',
  /** PF12 cancels the action in progress. */
  PFK12: 'F12=Cancel',
} as const;

/**
 * The stage this screen is in, transcribed from the reference's `TTUP-*` condition names.
 *
 * Assumptions: five stages and not more. The reference declares several terminal ones -- changes done,
 * changes failed, delete done, delete failed -- and `app/app-transaction-type-db2/cbl/COTRTUPC.cbl`
 * L405-L419 resets every one of them straight back to the initial stage before the next turn is
 * decided, so they are outcomes reported on the message line rather than stages an operator acts from.
 * Modelling them as stages would add states no key behaves differently in.
 */
export type RefTypeEditStage =
  /** `TTUP-DETAILS-NOT-FETCHED`: waiting for a key to be typed. */
  | 'notFetched'
  /** `TTUP-SHOW-DETAILS`: the row was read and is on the glass. */
  | 'showDetails'
  /** `TTUP-DETAILS-NOT-FOUND`: the key named no row. */
  | 'detailsNotFound'
  /** `TTUP-CREATE-NEW-RECORD`: the operator accepted that a new row will be added. */
  | 'createNewRecord'
  /** `TTUP-CHANGES-OK-NOT-CONFIRMED`: what was typed passed validation and awaits a save. */
  | 'changesOkNotConfirmed'
  /** `TTUP-CONFIRM-DELETE`: a delete was asked for and awaits confirmation. */
  | 'confirmDelete';

/** Which of the five keys this stage offers, as the reference's own attribute paragraph sets them. */
export interface StageKeyAvailability {
  /** Whether Enter is live; the reference darkens it only while a delete is being confirmed. */
  readonly enter: boolean;
  /** Whether PF4 is live. */
  readonly delete: boolean;
  /** Whether PF5 is live. */
  readonly save: boolean;
  /** Whether PF12 is live. */
  readonly cancel: boolean;
}

/**
 * Transcribes `3391-SETUP-PFKEY-ATTRS` into the four availability flags for one stage.
 *
 * Assumptions: PF3 is not among the flags because the reference never darkens it -- `FKEYSA` carries
 * both the Enter and the PF3 labels in one field and the paragraph darkens that field only for the
 * delete confirmation, so PF3's own availability is unconditional while Enter's is not. Reproducing
 * that exactly would mean darkening the PF3 label whenever Enter is darkened, which is what the
 * terminal did; it is not reproduced because the two labels are separate controls here and refusing an
 * exit would trap an operator in the confirmation with no way out but the cancel key.
 * Trade-offs: the accepted cost is one legend that stays lit where the terminal dimmed it. The
 * alternative -- dimming the exit -- would make the confirmation stage a dead end on a screen whose
 * reference offered a way out of it, because on the terminal the darkened field still ACCEPTED the key.
 * @param {RefTypeEditStage} stage - The stage the screen is in.
 * @returns {StageKeyAvailability} Which of the four conditional keys are live.
 */
export function stageKeyAvailability(stage: RefTypeEditStage): StageKeyAvailability {
  return {
    // Assumptions: `COTRTUPC.cbl` L1400-L1404 -- Enter is darkened for the delete confirmation and
    //   brightened in every other stage.
    enter: stage !== 'confirmDelete',
    // Assumptions: L1406-L1409 -- PF4 is revealed for `TTUP-SHOW-DETAILS` or `TTUP-CONFIRM-DELETE`.
    delete: stage === 'showDetails' || stage === 'confirmDelete',
    // Assumptions: L1411-L1414 -- PF5 is revealed for `TTUP-CHANGES-OK-NOT-CONFIRMED` or
    //   `TTUP-DETAILS-NOT-FOUND`.
    save: stage === 'changesOkNotConfirmed' || stage === 'detailsNotFound',
    // Assumptions: L1416-L1422 -- PF12 is revealed for every stage but the initial one.
    cancel: stage !== 'notFetched',
  };
}

/** The prompts and outcomes this program declares as condition names on its message field. */
const EDIT_STATUS = STATUS_MESSAGES.COTRTUPC;

/** The one message this program declares as a literal, which is its key field's label. */
const EDIT_MESSAGES = PROGRAM_MESSAGES.COTRTUPC;

/** Matches an entry consisting only of decimal digits, which is COBOL's `IS NUMERIC` on a `PIC X`. */
const DIGITS_ONLY = /^[0-9]+$/u;

/** Matches an entry of letters, digits and spaces only, which is the reference's alphanumeric edit. */
const ALPHANUMERIC_OR_SPACE = /^[A-Za-z0-9 ]*$/u;

/**
 * Validates a typed type code the way the reference validates it.
 *
 * Assumptions: the sentences are COMPOSED from the field label and a suffix, both from the catalog,
 * exactly as `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` composes them -- the label `'Tran Type
 * code'` at L866 and L924 joined to `' must be supplied.'`, to `' must be numeric.'` at L943, and to
 * `' must not be zero.'` at L961. Nothing is written inline, so the joined result is verbatim without
 * this module holding either half.
 * @param {string} entry - The type code as typed.
 * @returns {string | null} The composed sentence, or `null` when the entry is acceptable.
 */
export function validateTypeCode(entry: string): string | null {
  const typed = entry.trim();
  if (typed === '') {
    return `${EDIT_MESSAGES.TRAN_TYPE_CODE}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`;
  }
  // WHY : Assumptions: a positive character-class match rather than `Number.isNaN(Number(entry))`,
  //       because `Number('1e1')` and `Number(' 1')` are both numbers to JavaScript while COBOL's
  //       `IS NOT NUMERIC` on a `PIC X(2)` accepts only digit characters. The class match reproduces
  //       the domain; the numeric coercion would widen it.
  if (!DIGITS_ONLY.test(typed)) {
    return `${EDIT_MESSAGES.TRAN_TYPE_CODE}${FIELD_VALIDATION_SUFFIXES.MUST_BE_NUMERIC}`;
  }
  if (Number.parseInt(typed, 10) === 0) {
    return `${EDIT_MESSAGES.TRAN_TYPE_CODE}${FIELD_VALIDATION_SUFFIXES.MUST_NOT_BE_ZERO}`;
  }
  return null;
}

/**
 * Validates a typed description the way the reference validates it.
 *
 * Assumptions: the permitted characters are letters, digits and the space, which is the same edit
 * `ui/src/screens/refTypeList/index.tsx` records for the sibling list screen: the reference converts
 * every alphanumeric character to a space and then requires the remainder to be empty, so a space
 * survives by being what everything else becomes. The label is the shared `'Transaction Desc'` the
 * catalog holds for both programs.
 * @param {string} entry - The description as typed.
 * @returns {string | null} The composed sentence, or `null` when the entry is acceptable.
 */
export function validateDescription(entry: string): string | null {
  const typed = entry.trim();
  if (typed === '') {
    return `${SHARED_MESSAGES.TRANSACTION_DESC}${FIELD_VALIDATION_SUFFIXES.MUST_BE_SUPPLIED}`;
  }
  if (!ALPHANUMERIC_OR_SPACE.test(typed)) {
    return `${SHARED_MESSAGES.TRANSACTION_DESC}${FIELD_VALIDATION_SUFFIXES.CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY}`;
  }
  return null;
}

/**
 * Chooses the sentence a rejected write is reported with.
 *
 * Assumptions: a version conflict is reported with the reference's own
 * `'Record changed by some one else. Please review'` rather than with the service's wording, because
 * that condition is one the reference names itself and the operator's next action -- re-read and review
 * -- is what the sentence tells them. Every other rejection falls back to the reference's
 * `'Changes unsuccessful'`, which is the sentence it uses when it cannot say more.
 * @param {unknown} failure - Whatever the write rejected with.
 * @returns {string} The sentence to paint on the message line.
 */
export function writeFailureMessage(failure: unknown): string {
  const conflict = 409;
  if (isApiRequestError(failure) && failure.status === conflict) {
    return EDIT_STATUS.DATA_WAS_CHANGED_BEFORE_UPDATE.text;
  }
  return EDIT_STATUS.INFORM_FAILURE.text;
}

/**
 * Renders the transaction-type maintenance screen for one code.
 * @returns {ReactElement} The maintenance screen.
 */
export function RefTypeEditScreen(): ReactElement {
  const navigate = useNavigate();
  // WHY : Refactoring Rationale: `cssVar` rather than `token`, for the reason
  //       `ui/src/screens/refTypeList/index.tsx` records at its own call -- `token` holds resolved
  //       values while `cssVar` holds `var(--...)` references, so a theme change reaches an inline
  //       style without this component re-rendering.
  const { cssVar } = theme.useToken();
  const paintedAt = useServerInstant();
  /*
   * WHY : Assumptions: the parameter is spelled `typeCd`, which is the name
   *       {@link REF_TYPE_EDIT_ROUTE} declares and `ui/src/router.tsx` mounts this screen under.
   *       `useParams` resolves an unmatched name to `undefined` with no diagnostic, so a near-miss
   *       would leave this screen permanently prompting for a key the route already carried.
   */
  const { typeCd: routeTypeCd } = useParams<{ typeCd: string }>();

  const [stage, setStage] = useState<RefTypeEditStage>('notFetched');
  const [typeCode, setTypeCode] = useState('');
  const [description, setDescription] = useState('');
  const [stored, setStored] = useState<TransactionType | null>(null);
  const [message, setMessage] = useState<string | null>(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text);
  const [severity, setSeverity] = useState<MessageBandSeverity>('info');
  const [fieldInError, setFieldInError] = useState<'typeCode' | 'description' | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(
    /**
     * Reads the row a key names and settles the stage from whether it exists.
     *
     * Assumptions: a 404 is a STAGE rather than a failure, which is the reference's own reading -- a
     * key that names no row moves it to `TTUP-DETAILS-NOT-FOUND`, where PF5 is revealed so the
     * operator can add it. Reporting the 404 as an error would refuse the add path this screen exists
     * to offer.
     * @param {string} code - The two-character key to read.
     * @returns {void} Completion is represented by this screen's own state.
     */
    (code: string): void => {
      setBusy(true);
      getTransactionType(code).then(
        /**
         * Publishes the row and moves to the stage that offers a change or a delete.
         * @param {TransactionType} row - The row the service returned.
         * @returns {void} Completion is represented by this screen's own state.
         */
        (row) => {
          setBusy(false);
          setStored(row);
          setTypeCode(row.typeCd);
          setDescription(row.description);
          setStage('showDetails');
          setMessage(EDIT_STATUS.FOUND_TRANTYPE_DATA.text);
          setSeverity('info');
          setFieldInError(null);
        },
        /**
         * Moves to the not-found stage for a missing row, and reports anything else as a failure.
         * @param {unknown} failure - Whatever the read rejected with.
         * @returns {void} Completion is represented by this screen's own state.
         */
        (failure: unknown) => {
          setBusy(false);
          setStored(null);
          const notFound = 404;
          if (isApiRequestError(failure) && failure.status === notFound) {
            setStage('detailsNotFound');
            setMessage(EDIT_STATUS.WS_RECORD_NOT_FOUND.text);
            setSeverity('error');
            return;
          }
          setStage('notFetched');
          setMessage(EDIT_STATUS.INFORM_FAILURE.text);
          setSeverity('error');
        },
      );
    },
    [],
  );

  useEffect(
    /**
     * Reads the row the route names, and prompts instead when the route carries the add sentinel.
     *
     * Assumptions: the sentinel is recognised rather than read, so entering at
     * the add sentinel issues no request at all -- which is what the reference does when it
     * is transferred to with no key: `1000-PROCESS-INPUTS` finds nothing to fetch and the screen
     * prompts for a key.
     * @returns {void} Completion is represented by this screen's own state.
     */
    function loadRouteType(): void {
      if (routeTypeCd === undefined || routeTypeCd === REF_TYPE_NEW_SENTINEL) {
        return;
      }
      setTypeCode(routeTypeCd);
      load(routeTypeCd);
    },
    [load, routeTypeCd],
  );

  /**
   * Validates what was typed and decides the next stage, which is this program's Enter path.
   *
   * Assumptions: the key is validated before the description, which is the order
   * `1000-PROCESS-INPUTS` runs its edits in -- a screen with no usable key has nothing to validate a
   * description against. The unchanged test comes before the description edit for the same reason the
   * list screen records: the reference sets `'No change detected...'` and leaves the paragraph before
   * any edit runs, so it is a validation outcome rather than an answer only the service can give.
   * @returns {void} Completion is represented by this screen's own state.
   */
  function processInputs(): void {
    const codeRefusal = validateTypeCode(typeCode);
    if (codeRefusal !== null) {
      setMessage(codeRefusal);
      setSeverity('error');
      setFieldInError('typeCode');
      return;
    }
    if (stage === 'notFetched' || stage === 'detailsNotFound') {
      setFieldInError(null);
      load(typeCode);
      return;
    }
    if (stored !== null && description.trim() === stored.description.trim()) {
      setMessage(EDIT_STATUS.NO_CHANGES_DETECTED.text);
      setSeverity('info');
      setFieldInError(null);
      return;
    }
    const descriptionRefusal = validateDescription(description);
    if (descriptionRefusal !== null) {
      setMessage(descriptionRefusal);
      setSeverity('error');
      setFieldInError('description');
      return;
    }
    setStage('changesOkNotConfirmed');
    setMessage(EDIT_STATUS.PROMPT_FOR_CONFIRMATION.text);
    setSeverity('info');
    setFieldInError(null);
  }

  /**
   * Writes the row, creating it when none was read and replacing it when one was.
   *
   * Assumptions: which write runs is decided by whether a row was READ rather than by which stage the
   * screen is in, because the stage records what the operator confirmed and the read records what
   * exists. The version last issued is sent with a replace, so a concurrent change is refused by the
   * service with a 409 -- the reference's own before-image comparison, expressed as the optimistic
   * concurrency the target carries natively.
   * @returns {void} Completion is represented by this screen's own state.
   */
  function saveChanges(): void {
    if (busy) {
      return;
    }
    setBusy(true);
    const written =
      stored === null
        ? createTransactionType({ typeCd: typeCode, description })
        : replaceTransactionType(stored.typeCd, { description, version: stored.version });
    written.then(
      /**
       * Reports the reference's own success sentence and shows the row as stored.
       * @param {TransactionType} row - The row as stored after the write.
       * @returns {void} Completion is represented by this screen's own state.
       */
      (row) => {
        setBusy(false);
        setStored(row);
        setTypeCode(row.typeCd);
        setDescription(row.description);
        setStage('showDetails');
        setMessage(EDIT_STATUS.CONFIRM_UPDATE_SUCCESS.text);
        setSeverity('success');
      },
      /**
       * Reports a refused write, leaving the typed values on the glass to be reviewed.
       * @param {unknown} failure - Whatever the write rejected with.
       * @returns {void} Completion is represented by this screen's own state.
       */
      (failure: unknown) => {
        setBusy(false);
        setMessage(writeFailureMessage(failure));
        setSeverity('error');
      },
    );
  }

  /**
   * Asks for delete confirmation, or performs the delete when it has already been asked for.
   *
   * Assumptions: the two presses are one handler because the reference binds them to one key -- PF4
   * with `TTUP-SHOW-DETAILS` asks, PF4 with `TTUP-CONFIRM-DELETE` deletes -- so the stage decides
   * which of the two a press means. Splitting them into two controls would offer a delete that needed
   * no confirmation.
   * @returns {void} Completion is represented by this screen's own state.
   */
  function deleteRow(): void {
    if (busy || stored === null) {
      return;
    }
    if (stage !== 'confirmDelete') {
      setStage('confirmDelete');
      setMessage(EDIT_STATUS.PROMPT_DELETE_CONFIRM.text);
      setSeverity('info');
      return;
    }
    setBusy(true);
    deleteTransactionType(stored.typeCd).then(
      /**
       * Reports the delete and returns the screen to its initial stage.
       * @returns {void} Completion is represented by this screen's own state.
       */
      () => {
        setBusy(false);
        setStored(null);
        setDescription('');
        setStage('notFetched');
        setMessage(EDIT_STATUS.CONFIRM_DELETE_SUCCESS.text);
        setSeverity('success');
      },
      /**
       * Reports a refused delete, which includes the categories-still-reference-it refusal.
       *
       * Assumptions: a 409 here is the `ON DELETE RESTRICT` foreign key the baseline declares in
       * `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` and the target preserves, so it is reported
       * with the service's own sentence when it carries one -- that sentence names the child records
       * -- and with the reference's `'Delete of record failed'` otherwise.
       * @param {unknown} failure - Whatever the delete rejected with.
       * @returns {void} Completion is represented by this screen's own state.
       */
      (failure: unknown) => {
        setBusy(false);
        const reported = isApiRequestError(failure) ? failure.problem.message : null;
        setMessage(
          reported !== null && reported.trim().length > 0
            ? reported
            : EDIT_STATUS.RECORD_DELETE_FAILED.text,
        );
        setSeverity('error');
      },
    );
  }

  /**
   * Cancels the action in progress, which is this program's PF12.
   *
   * Assumptions: the two cancel paths of the reference are both reproduced and they report different
   * sentences. `COTRTUPC.cbl` L406-L409 returns to the initial stage from `TTUP-SHOW-DETAILS`,
   * `TTUP-CREATE-NEW-RECORD` or `TTUP-DETAILS-NOT-FOUND`, while L524-L533 backs out a pending change
   * or a pending delete -- so a cancel from the delete confirmation reports `'Delete was cancelled'`
   * and a cancel from a validated change reports `'Update was cancelled'`.
   * @returns {void} Completion is represented by this screen's own state.
   */
  function cancelAction(): void {
    setFieldInError(null);
    if (stage === 'confirmDelete') {
      setStage('showDetails');
      setMessage(EDIT_STATUS.WS_DELETE_WAS_CANCELLED.text);
      setSeverity('info');
      return;
    }
    if (stage === 'changesOkNotConfirmed') {
      setDescription(stored?.description ?? '');
      setStage(stored === null ? 'detailsNotFound' : 'showDetails');
      setMessage(EDIT_STATUS.WS_UPDATE_WAS_CANCELLED.text);
      setSeverity('info');
      return;
    }
    setStored(null);
    setTypeCode('');
    setDescription('');
    setStage('notFetched');
    setMessage(EDIT_STATUS.PROMPT_FOR_SEARCH_KEYS.text);
    setSeverity('info');
  }

  /**
   * Accepts that a new row will be added, which is PF5 in the not-found stage.
   * @returns {void} Completion is represented by this screen's own state.
   */
  function acceptNewRecord(): void {
    setStage('createNewRecord');
    setMessage(EDIT_STATUS.PROMPT_FOR_NEWDATA.text);
    setSeverity('info');
  }

  const availability = stageKeyAvailability(stage);

  /*
   * WHY : Assumptions: exactly the four attention identifiers the reference tests are bound -- Enter,
   *       PF3, PF4 and PF5 plus PF12 -- and each conditional one carries the `disabled` flag its stage
   *       gives it, so the legend dims exactly where `3391-SETUP-PFKEY-ATTRS` darkens the field. A
   *       disabled binding is reported by `usePfKeys` as a `disabled` rejection rather than an unmapped
   *       one, which is why the handler below reports the reference's invalid-key sentence only for the
   *       unmapped case.
   */
  const handlers: PfKeyHandlerMap = {
    ENTER: {
      /**
       * Validates what was typed and decides the next stage.
       * @returns {void} Completion is represented by this screen's own state.
       */
      onInvoke: () => {
        processInputs();
      },
      label: REF_TYPE_EDIT_KEY_LABELS.ENTER,
      disabled: !availability.enter || busy,
    },
    PFK03: {
      /**
       * Leaves for the administrative menu, which is where the reference falls back to.
       * @returns {void} Completion is represented by the route change.
       */
      onInvoke: () => {
        navigateSafely(navigate, ADMIN_MENU_ROUTE);
      },
      label: REF_TYPE_EDIT_KEY_LABELS.PFK03,
    },
    PFK04: {
      /**
       * Asks for delete confirmation, or performs the delete once it has been asked for.
       * @returns {void} Completion is represented by this screen's own state.
       */
      onInvoke: () => {
        deleteRow();
      },
      label: REF_TYPE_EDIT_KEY_LABELS.PFK04,
      disabled: !availability.delete || busy,
    },
    PFK05: {
      /**
       * Accepts a new row in the not-found stage, and saves validated changes otherwise.
       * @returns {void} Completion is represented by this screen's own state.
       */
      onInvoke: () => {
        if (stage === 'detailsNotFound') {
          acceptNewRecord();
          return;
        }
        saveChanges();
      },
      label: REF_TYPE_EDIT_KEY_LABELS.PFK05,
      disabled: !availability.save || busy,
    },
    PFK12: {
      /**
       * Cancels the action in progress.
       * @returns {void} Completion is represented by this screen's own state.
       */
      onInvoke: () => {
        cancelAction();
      },
      label: REF_TYPE_EDIT_KEY_LABELS.PFK12,
      disabled: !availability.cancel || busy,
    },
  };

  const { bindings, invoke } = usePfKeys(handlers, {
    /**
     * Reports an unmapped key with the reference's own sentence for this program.
     *
     * Assumptions: this program declares its OWN invalid-key sentence rather than using the shared
     * one -- `'Invalid Key pressed. '` with its trailing space, which the catalog records separately
     * from `CCDA-MSG-INVALID-KEY` for exactly that reason. Using the shared constant here would paint
     * a sentence this program does not hold.
     * @param {object} rejection - Why the key was not dispatched.
     * @returns {void} Completion is represented by this screen's own state.
     */
    onInvalidKey: (rejection) => {
      if (rejection.reason === 'unmapped') {
        setMessage(EDIT_STATUS.WS_INVALID_KEY.text);
        setSeverity('error');
      }
    },
  });

  /*
   * WHY : Assumptions: the type code is rendered in the code face because it is a fixed-width numeric
   *       key, and the description is not because it is proportional text with nothing to align
   *       against -- the distinction `TYPOGRAPHY_TOKENS.fixedPitchData` records.
   */
  const fixedPitchStyle: CSSProperties = {
    fontFamily: cssVar[TYPOGRAPHY_TOKENS.fixedPitchData],
  };

  /*
   * WHY : Assumptions: the refused control is marked through `Form.Item`'s own error status plus the
   *       literal marker the reference writes, which is the target of the templated highlight copybook
   *       `app/cpy/CSSETATY.cpy` -- it moves `DFHRED` into the field's colour attribute and, for a
   *       blank field, additionally moves a literal asterisk into it. The colour comes from the design
   *       bridge's error token rather than from a literal red.
   */
  const refusedFieldStyle: CSSProperties = {
    color: cssVar[BMS_COLOR_TOKENS.RED],
  };

  return (
    <Flex vertical gap="large">
      <ScreenHeader
        transactionId={REF_TYPE_EDIT_TRANSACTION_ID}
        programName={REF_TYPE_EDIT_PROGRAM_NAME}
        now={paintedAt}
      />
      <Typography.Title level={3}>{REF_TYPE_EDIT_SUBTITLE}</Typography.Title>
      {/*
        Trade-offs: the two controls are laid out by grouping rather than at the mapset's `POS=(12,26)`
        and `POS=(14,26)`, which is documented gap G1. What is kept is the pair's order, the label-to-
        control association -- which position carried on the terminal and `Form.Item` carries here --
        and the tab order, which follows the DOM order.
      */}
      <Form layout="vertical">
        {/*
          Alternatives Considered: carrying the refusal sentence in this item's `help` slot, which is
          what the design-system mapping assigns to a per-field error and what the sibling list screen
          does. Rejected HERE because this screen's refusals are composed locally rather than delivered
          as a problem document's field-error array, and the reference writes each one to `WS-RETURN-MSG`
          -- rendered by the row-23 `ERRMSG` field -- while marking the field itself with colour and, for
          a blank field, the literal asterisk. Putting the sentence in `help` as well would render one
          sentence twice on one screen. So the item carries the MARK and the band carries the SENTENCE,
          which is the division the reference draws.
        */}
        <Form.Item
          label={REF_TYPE_EDIT_FIELD_LABELS.typeCode}
          htmlFor={TYPE_CODE_CONTROL_ID}
          validateStatus={fieldInError === 'typeCode' ? 'error' : ''}
        >
          <Input
            id={TYPE_CODE_CONTROL_ID}
            value={typeCode}
            maxLength={TYPE_CODE_WIDTH}
            style={fixedPitchStyle}
            // Assumptions: `autoFocus` carries the mapset's `IC` attribute, which `COTRTUP.bms` L84
            // sets on `TRTYPCD` -- the key control is where the cursor lands on entry.
            autoFocus
            // Assumptions: the key control is disabled once a row has been read, because the reference
            // re-fetches on every Enter from the key on the glass and a changed key with a stored row
            // still loaded would offer a save against a row the operator is no longer looking at. The
            // cancel key returns the screen to the stage where the key is editable again.
            disabled={stored !== null || busy}
            inputMode="numeric"
            onChange={
              /**
               * Keeps only the digits the reference's numeric edit admits.
               * @param {ChangeEvent<HTMLInputElement>} event - The entry event.
               * @returns {void} Completion is represented by this screen's own state.
               */
              (event: ChangeEvent<HTMLInputElement>) => {
                setTypeCode(event.target.value.replace(/[^0-9]/gu, ''));
              }
            }
          />
          {fieldInError === 'typeCode' ? (
            <Typography.Text style={refusedFieldStyle}>
              {FIELD_ERROR_TOKENS.blankMarker}
            </Typography.Text>
          ) : null}
        </Form.Item>
        <Form.Item
          label={REF_TYPE_EDIT_FIELD_LABELS.description}
          htmlFor={DESCRIPTION_CONTROL_ID}
          validateStatus={fieldInError === 'description' ? 'error' : ''}
        >
          <Input
            id={DESCRIPTION_CONTROL_ID}
            value={description}
            maxLength={DESCRIPTION_WIDTH}
            disabled={stage === 'notFetched' || stage === 'confirmDelete' || busy}
            onChange={
              /**
               * Records the typed description without editing it, so the reference's own edit decides.
               * @param {ChangeEvent<HTMLInputElement>} event - The entry event.
               * @returns {void} Completion is represented by this screen's own state.
               */
              (event: ChangeEvent<HTMLInputElement>) => {
                setDescription(event.target.value);
              }
            }
          />
          {fieldInError === 'description' ? (
            <Typography.Text style={refusedFieldStyle}>
              {FIELD_ERROR_TOKENS.blankMarker}
            </Typography.Text>
          ) : null}
        </Form.Item>
      </Form>
      {/*
        Assumptions: the delete is additionally confirmed through a `Popconfirm` when it is first asked
        for, which is a browser addition on top of the reference's own two-press confirmation rather
        than a replacement for it. A pointer can activate a control by accident where a function key
        cannot, and the design-system mapping assigns `Popconfirm` to exactly this role. The reference's
        second-press confirmation is unaffected: the key path still requires two presses.
      */}
      <Flex gap="small">
        <Button type="primary" disabled={!availability.enter || busy} onClick={processInputs}>
          {REF_TYPE_EDIT_KEY_LABELS.ENTER}
        </Button>
        <Button
          type="primary"
          disabled={!availability.save || busy}
          onClick={
            /**
             * Accepts a new row in the not-found stage, and saves validated changes otherwise.
             * @returns {void} Completion is represented by this screen's own state.
             */
            () => {
              if (stage === 'detailsNotFound') {
                acceptNewRecord();
                return;
              }
              saveChanges();
            }
          }
        >
          {REF_TYPE_EDIT_KEY_LABELS.PFK05}
        </Button>
        <Popconfirm
          title={EDIT_STATUS.PROMPT_DELETE_CONFIRM.text}
          okType="danger"
          disabled={!availability.delete || busy}
          onConfirm={deleteRow}
        >
          <Button danger disabled={!availability.delete || busy}>
            {REF_TYPE_EDIT_KEY_LABELS.PFK04}
          </Button>
        </Popconfirm>
        <Button disabled={!availability.cancel || busy} onClick={cancelAction}>
          {REF_TYPE_EDIT_KEY_LABELS.PFK12}
        </Button>
      </Flex>
      <MessageBand mapset={REF_TYPE_EDIT_MAPSET} message={message} severity={severity} />
      <PfKeyBar keys={bindings} onInvoke={invoke} />
    </Flex>
  );
}

export default RefTypeEditScreen;
