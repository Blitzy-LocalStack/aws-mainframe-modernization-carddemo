/**
 * Verbatim catalog of every user-visible string in the CardDemo single-page
 * application.
 *
 * Purpose
 * -------
 * This module is the sole owner of user-visible text in the SPA. Screens, layout
 * components, hooks and tests import their strings from here and never inline a
 * literal of their own, so the "text is byte-identical to the mainframe" guarantee
 * is enforced by one reviewable module instead of by discipline spread across 21
 * screen implementations.
 *
 * Provenance
 * ----------
 * Every string below is transcribed from the immutable COBOL baseline under
 * `app/`, which is reference-only and is the behavioural oracle for the
 * migration. Six copybooks supply the fixed-width screen constants:
 *
 * - `app/cpy/COTTL01Y.cpy` - the three `PIC X(40)` title-band constants
 * - `app/cpy/CSMSG01Y.cpy` - the two `PIC X(50)` common messages
 * - `app/cpy/CSMSG02Y.cpy` - the abend surface (four field widths, no text)
 * - `app/cpy/COMEN02Y.cpy` - the eleven main-menu option names
 * - `app/cpy/COADM02Y.cpy` - the six admin-menu option names
 * - `app/cpy/CVCRD01Y.cpy` - the 75-character message-band width contract
 *
 * Twenty-two online programs supply the message literals: the seventeen base
 * `app/cbl/CO*.cbl` screens plus `COPAUS0C`, `COPAUS1C`, `COPAUS2C` from the
 * authorization extension and `COTRTLIC`, `COTRTUPC` from the transaction-type
 * extension.
 *
 * Invariant
 * ---------
 * Transcription is byte-exact. Trailing spaces, doubled interior spaces,
 * inconsistent spacing before an ellipsis, case-only variants of otherwise
 * identical sentences, and outright grammatical defects in the baseline are all
 * reproduced exactly as the COBOL holds them. None of them is a typo to be
 * fixed here: the migration plan forbids correcting baseline defects, and the
 * golden-master tests compare this text against mainframe output.
 *
 * WHY the strings are grouped by originating copybook or program rather than by
 * screen (Assumptions): a downstream screen author works from a COBOL source
 * location, so a key must be derivable from "which program emitted this". Grouping
 * by screen would require knowing the program-to-route mapping, which lives in
 * `router.tsx`, to find a string at all.
 *
 * WHY no internationalisation library (Alternatives Considered): `react-intl` and
 * `i18next` are the reflexive choices for anything called a message catalog, and
 * both were rejected. The requirement is byte-exact reproduction of one locale,
 * not translation, and an i18n runtime adds a message-compilation step that can
 * normalise whitespace - the single thing this module must never do. It would also
 * breach the pinned dependency set in `ui/package.json`, which contains no i18n
 * package.
 *
 * This module imports nothing and performs no work at load time, so it can be
 * consumed by any layer without creating a cycle.
 * @module messages
 */

/**
 * A location in the immutable COBOL baseline that a catalogued string was read
 * from.
 *
 * WHY provenance is structured data rather than a prose comment above each entry
 * (Trade-offs): with this many entries, a comment per entry would be ~150 lines of
 * text restating values, which the project's explainability rule explicitly
 * forbids. As data it is also machine-checkable - the validation harness asserts
 * that the cited line still contains the string.
 */
export interface SourceRef {
  /** Repository-relative path of the COBOL source file. */
  readonly file: string;
  /** Every 1-based line in that file whose columns 7-72 contain the literal. */
  readonly lines: readonly number[];
}

/**
 * A screen constant whose COBOL `PICTURE` clause declares a fixed field width.
 *
 * WHY `text` and `declaredWidth` are separate (Trade-offs, Assumptions): several
 * baseline literals are shorter than the field they initialise - both
 * `CSMSG01Y` messages are 49 characters against `PIC X(50)` - so COBOL pads the
 * runtime field with trailing spaces that the source text does not contain.
 * Storing the padded form would fabricate bytes that are not in the source;
 * storing only the text would lose the width contract a renderer needs. Keeping
 * both lets `padToDeclaredWidth` reproduce the runtime value on demand while the
 * transcription stays honest.
 */
export interface FixedWidthText {
  /** The literal exactly as it appears in the copybook, unpadded and untrimmed. */
  readonly text: string;
  /** Field width from the `PIC X(n)` clause. */
  readonly declaredWidth: number;
  /** Where the literal was read from. */
  readonly source: SourceRef;
}

/**
 * A fixed-width field that carries no compile-time text because the baseline
 * initialises it to `VALUE SPACES` and fills it at run time.
 */
export interface FixedWidthField {
  /** The COBOL data-name, retained so the mapping back to the copybook is exact. */
  readonly field: string;
  /** Field width from the `PIC X(n)` clause. */
  readonly declaredWidth: number;
  /** Where the field was declared. */
  readonly source: SourceRef;
}

/** One selectable entry on the main menu, from `app/cpy/COMEN02Y.cpy`. */
export interface MainMenuOption {
  /** Value of `CDEMO-MENU-OPT-NUM`, the number the operator types. */
  readonly optionNumber: number;
  /** `CDEMO-MENU-OPT-NAME`, padded by the baseline to exactly 35 characters. */
  readonly name: string;
  /** `CDEMO-MENU-OPT-PGMNAME`; the transfer target, mapped to a route by the router. */
  readonly programName: string;
  /** `CDEMO-MENU-OPT-USRTYPE`: `'A'` restricts the option to administrators. */
  readonly userType: 'A' | 'U';
  /** Where the option name was read from. */
  readonly source: SourceRef;
}

/**
 * One selectable entry on the admin menu, from `app/cpy/COADM02Y.cpy`.
 *
 * WHY this has no `userType` while {@link MainMenuOption} does (Assumptions): the
 * admin-menu copybook genuinely omits the `USRTYPE` subfield. The admin menu is
 * only reachable after the administrator check has already passed, so a per-option
 * user type would be redundant. Adding one here to make the two interfaces
 * symmetrical would invent a field the baseline does not have.
 */
export interface AdminMenuOption {
  /** Value of `CDEMO-ADMIN-OPT-NUM`, the number the operator types. */
  readonly optionNumber: number;
  /** `CDEMO-ADMIN-OPT-NAME`, padded by the baseline to exactly 35 characters. */
  readonly name: string;
  /** `CDEMO-ADMIN-OPT-PGMNAME`; the transfer target, mapped to a route by the router. */
  readonly programName: string;
  /** Where the option name was read from. */
  readonly source: SourceRef;
}

/**
 * The three title-band constants shared by every 3270 screen, each declared
 * `PIC X(40)` in `app/cpy/COTTL01Y.cpy`.
 *
 * `TITLE02` is also the value rendered in the browser tab, so `ui/index.html` and
 * this constant must agree; this module is the runtime source of truth.
 */
export const SCREEN_TITLES = {
  /** `CCDA-TITLE01` - the organisation line of the title band. */
  TITLE01: {
    text: '      AWS Mainframe Modernization       ',
    declaredWidth: 40,
    source: { file: 'app/cpy/COTTL01Y.cpy', lines: [19] },
  },
  /**
   * `CCDA-TITLE02` - the application line of the title band.
   *
   * WHY the commented-out alternative on line 21 is not catalogued (Refactoring
   * Rationale, Assumptions): line 21 holds a disabled variant reading
   * "Credit Card Demo Application (CCDA)" which is *also* exactly 40 characters,
   * so it is indistinguishable from the live value by length or by shape. Only
   * the `*` in column 7 marks it dead. The same trap appears three more times in
   * the baseline - `COMEN02Y` line 69, `COADM02Y` line 21 and `COACTUPC` line
   * 1614 - so the extraction that produced this file discards any line whose
   * column-7 indicator is `*` or `/` before looking at its content.
   */
  TITLE02: {
    text: '              CardDemo                  ',
    declaredWidth: 40,
    source: { file: 'app/cpy/COTTL01Y.cpy', lines: [22] },
  },
  /**
   * `CCDA-THANK-YOU` - the sign-off line, which names the application "CCDA".
   *
   * WHY this is kept separate from {@link COMMON_MESSAGES.THANK_YOU}
   * (Alternatives Considered, Assumptions): the two look like one sentence stored
   * at two widths, and de-duplicating them - either directly or by comparing their
   * trimmed forms - is the obvious tidy-up. It loses text. This one says "CCDA";
   * the `CSMSG01Y` one says "CardDemo". Neither word appears in the other string,
   * so they are two different sentences and both are displayed. They are also 40
   * and 49 characters against declared widths of 40 and 50, so a width-based merge
   * would not reconcile them either.
   */
  THANK_YOU: {
    text: 'Thank you for using CCDA application... ',
    declaredWidth: 40,
    source: { file: 'app/cpy/COTTL01Y.cpy', lines: [24] },
  },
} as const satisfies Record<string, FixedWidthText>;

/**
 * The two common messages from `app/cpy/CSMSG01Y.cpy`, both declared
 * `PIC X(50)`.
 *
 * Both source literals are 49 characters long, one short of the declared width.
 * The unpadded text is stored; see {@link FixedWidthText} for why.
 */
export const COMMON_MESSAGES = {
  /**
   * `CCDA-MSG-THANK-YOU` - the sign-off message, which names the application
   * "CardDemo". Distinct from {@link SCREEN_TITLES.THANK_YOU}; see the note there.
   */
  THANK_YOU: {
    text: 'Thank you for using CardDemo application...      ',
    declaredWidth: 50,
    source: { file: 'app/cpy/CSMSG01Y.cpy', lines: [19] },
  },
  /** `CCDA-MSG-INVALID-KEY` - shown when an unmapped attention key is pressed. */
  INVALID_KEY: {
    text: 'Invalid key pressed. Please see below...         ',
    declaredWidth: 50,
    source: { file: 'app/cpy/CSMSG01Y.cpy', lines: [21] },
  },
} as const satisfies Record<string, FixedWidthText>;

/**
 * The abend surface from `app/cpy/CSMSG02Y.cpy`, expressed as field widths.
 *
 * WHY this group carries widths instead of text (Assumptions): all four fields of
 * `ABEND-DATA` are declared `VALUE SPACES`, so the copybook contributes a
 * structure that the failing program fills at run time, not any message. The
 * displayed wording comes from the programs and is catalogued in
 * {@link SHARED_MESSAGES} as `UNEXPECTED_ABEND_OCCURRED` and
 * `UNEXPECTED_DATA_SCENARIO`. Treating this copybook as a source of text would
 * yield four empty strings and hide where the real wording lives.
 *
 * The migration plan cites these declarations at lines 45-53; that range does not
 * exist, because the file is only 35 lines long. The declarations are at lines
 * 21-29 and the provenance below records the verified positions.
 */
export const ABEND_DATA_FIELDS = [
  {
    field: 'ABEND-CODE',
    declaredWidth: 4,
    source: { file: 'app/cpy/CSMSG02Y.cpy', lines: [22, 23] },
  },
  {
    field: 'ABEND-CULPRIT',
    declaredWidth: 8,
    source: { file: 'app/cpy/CSMSG02Y.cpy', lines: [24, 25] },
  },
  {
    field: 'ABEND-REASON',
    declaredWidth: 50,
    source: { file: 'app/cpy/CSMSG02Y.cpy', lines: [26, 27] },
  },
  {
    field: 'ABEND-MSG',
    declaredWidth: 72,
    source: { file: 'app/cpy/CSMSG02Y.cpy', lines: [28, 29] },
  },
] as const satisfies readonly FixedWidthField[];

/**
 * The message-band width contract from `app/cpy/CVCRD01Y.cpy`.
 *
 * WHY this is attributed to `CVCRD01Y` and not `COCOM01Y` (Refactoring Rationale):
 * the migration plan attributes the 75-character contract to `COCOM01Y.cpy`, but
 * that copybook declares no `PIC X(75)` field and in fact carries no message field
 * at all - it holds navigation, identity and selection context. The two 75-byte
 * message fields are `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` in `CVCRD01Y`, and
 * that is what this constant records.
 *
 * WHY `emptySentinel` is recorded (Assumptions): the baseline marks "no message"
 * with `88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES` - binary zeros, not spaces. A
 * band that treats an all-spaces value as "has a message" would render an empty
 * alert on screens that are simply quiet, so the distinction is carried across.
 */
export const MESSAGE_BAND = {
  /** Width of `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG`, in characters. */
  declaredWidth: 75,
  /** The COBOL figurative constant that means "no message is set". */
  emptySentinel: 'LOW-VALUES',
  /** Where the two message fields and the off-condition are declared. */
  source: { file: 'app/cpy/CVCRD01Y.cpy', lines: [28, 29, 30] },
} as const;

/**
 * Number of populated main-menu slots, from `CDEMO-MENU-OPT-COUNT`.
 *
 * WHY the count is exported alongside the array (Assumptions): the baseline
 * declares `CDEMO-MENU-OPT OCCURS 12 TIMES` but populates only 11 slots, so the
 * twelfth is uninitialised storage. COBOL callers iterate the count, never the
 * `OCCURS`. {@link MAIN_MENU_OPTIONS} holds exactly the populated entries, so the
 * two agree by construction; the count is exported so a reader who checks this
 * against the copybook does not mistake the 11/12 gap for a dropped option.
 */
export const MAIN_MENU_OPTION_COUNT = 11 as const;

/**
 * The main-menu options from `app/cpy/COMEN02Y.cpy`, in display order. Each name is
 * padded by the baseline to exactly 35 characters and is stored that way.
 *
 * WHY every entry is `userType: 'U'` (Assumptions): the copybook sets
 * `OPT-USRTYPE` to `'U'` for all eleven options, so nothing on the main menu is
 * administrator-only in the live configuration. This is also why the
 * "(Admin Only)" wording on option 8 is commented out at line 69 - the
 * restriction was removed and the label went with it. The disabled label is
 * exactly 35 characters, identical in length to the live one, so it is excluded on
 * the strength of its column-7 marker alone.
 */
export const MAIN_MENU_OPTIONS = [
  {
    optionNumber: 1,
    name: 'Account View                       ',
    programName: 'COACTVWC',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [27] },
  },
  {
    optionNumber: 2,
    name: 'Account Update                     ',
    programName: 'COACTUPC',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [33] },
  },
  {
    optionNumber: 3,
    name: 'Credit Card List                   ',
    programName: 'COCRDLIC',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [39] },
  },
  {
    optionNumber: 4,
    name: 'Credit Card View                   ',
    programName: 'COCRDSLC',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [45] },
  },
  {
    optionNumber: 5,
    name: 'Credit Card Update                 ',
    programName: 'COCRDUPC',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [51] },
  },
  {
    optionNumber: 6,
    name: 'Transaction List                   ',
    programName: 'COTRN00C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [57] },
  },
  {
    optionNumber: 7,
    name: 'Transaction View                   ',
    programName: 'COTRN01C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [63] },
  },
  {
    optionNumber: 8,
    name: 'Transaction Add                    ',
    programName: 'COTRN02C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [70] },
  },
  {
    optionNumber: 9,
    name: 'Transaction Reports                ',
    programName: 'CORPT00C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [76] },
  },
  {
    optionNumber: 10,
    name: 'Bill Payment                       ',
    programName: 'COBIL00C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [82] },
  },
  {
    optionNumber: 11,
    name: 'Pending Authorization View         ',
    programName: 'COPAUS0C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [88] },
  },
] as const satisfies readonly MainMenuOption[];

/**
 * Number of populated admin-menu slots, from `CDEMO-ADMIN-OPT-COUNT`.
 *
 * WHY the value is 6 and not 4 (Refactoring Rationale, Assumptions): the copybook
 * holds two declarations of this field. The one at line 21 is commented out and
 * says 4; the live one at line 22 says 6. The count was raised when the Db2
 * release added options 5 and 6, which the surrounding
 * "Option added for Db2 V1 release" markers at lines 45 and 54 bracket. As with
 * the main menu, `OCCURS 9` overstates the populated slots and the count governs.
 */
export const ADMIN_MENU_OPTION_COUNT = 6 as const;

/**
 * The admin-menu options from `app/cpy/COADM02Y.cpy`, in display order. Each name
 * is padded by the baseline to exactly 35 characters and is stored that way.
 */
export const ADMIN_MENU_OPTIONS = [
  {
    optionNumber: 1,
    name: 'User List (Security)               ',
    programName: 'COUSR00C',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [28] },
  },
  {
    optionNumber: 2,
    name: 'User Add (Security)                ',
    programName: 'COUSR01C',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [33] },
  },
  {
    optionNumber: 3,
    name: 'User Update (Security)             ',
    programName: 'COUSR02C',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [38] },
  },
  {
    optionNumber: 4,
    name: 'User Delete (Security)             ',
    programName: 'COUSR03C',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [43] },
  },
  {
    optionNumber: 5,
    name: 'Transaction Type List/Update (Db2) ',
    programName: 'COTRTLIC',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [48] },
  },
  {
    optionNumber: 6,
    name: 'Transaction Type Maintenance (Db2) ',
    programName: 'COTRTUPC',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [52] },
  },
] as const satisfies readonly AdminMenuOption[];

/**
 * Repository-relative path of every online program this catalog transcribes,
 * keyed by program name.
 *
 * WHY the path is held once here instead of on each entry (Trade-offs): repeating
 * it on all 147 entries would add no information and would let two entries for the
 * same program disagree. Provenance entries therefore carry only line numbers and
 * resolve their file through this map.
 */
export const PROGRAM_SOURCE_FILES = {
  COACTUPC: 'app/cbl/COACTUPC.cbl',
  COACTVWC: 'app/cbl/COACTVWC.cbl',
  COADM01C: 'app/cbl/COADM01C.cbl',
  COBIL00C: 'app/cbl/COBIL00C.cbl',
  COCRDLIC: 'app/cbl/COCRDLIC.cbl',
  COCRDSLC: 'app/cbl/COCRDSLC.cbl',
  COCRDUPC: 'app/cbl/COCRDUPC.cbl',
  COMEN01C: 'app/cbl/COMEN01C.cbl',
  COPAUS0C: 'app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl',
  COPAUS1C: 'app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl',
  COPAUS2C: 'app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl',
  CORPT00C: 'app/cbl/CORPT00C.cbl',
  COSGN00C: 'app/cbl/COSGN00C.cbl',
  COTRN00C: 'app/cbl/COTRN00C.cbl',
  COTRN01C: 'app/cbl/COTRN01C.cbl',
  COTRN02C: 'app/cbl/COTRN02C.cbl',
  COTRTLIC: 'app/app-transaction-type-db2/cbl/COTRTLIC.cbl',
  COTRTUPC: 'app/app-transaction-type-db2/cbl/COTRTUPC.cbl',
  COUSR00C: 'app/cbl/COUSR00C.cbl',
  COUSR01C: 'app/cbl/COUSR01C.cbl',
  COUSR02C: 'app/cbl/COUSR02C.cbl',
  COUSR03C: 'app/cbl/COUSR03C.cbl',
} as const;

/** Name of an online program that contributes strings to this catalog. */
export type ProgramName = keyof typeof PROGRAM_SOURCE_FILES;

/**
 * Messages emitted verbatim by more than one program.
 *
 * WHY these are a group of their own rather than filed under one program
 * (Trade-offs): the catalog is keyed by origin, and these strings have several
 * origins, so filing each under a single program would mean picking one
 * arbitrarily and leaving the other call sites pointing at a key that names the
 * wrong program. A shared group keeps the "keyed by origin" rule honest, and the
 * provenance below lists every site.
 *
 * WHY the five page-navigation messages are five entries and not one
 * (Alternatives Considered): "already at the top", "already at the bottom", "at the
 * top", "reached the top" and "reached the bottom" are near-duplicates that look
 * like accidental drift, and folding them into one paging message would delete four
 * distinct sentences the operator actually sees at four different moments - two
 * describe a refused key press, three describe an arrival.
 */
export const SHARED_MESSAGES = {
  ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER:
    'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER',
  ACCOUNT_ID_NOT_FOUND: 'Account ID NOT found...',
  CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER:
    'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER',
  FIRST_NAME_CAN_NOT_BE_EMPTY: 'First Name can NOT be empty...',
  INVALID_SELECTION_VALID_VALUE_IS_S: 'Invalid selection. Valid value is S',
  INVALID_VALUE_VALID_VALUES_ARE_Y_N:
    'Invalid value. Valid values are (Y/N)...',
  LAST_NAME_CAN_NOT_BE_EMPTY: 'Last Name can NOT be empty...',
  PASSWORD_CAN_NOT_BE_EMPTY: 'Password can NOT be empty...',
  PLEASE_ENTER_A_VALID_OPTION_NUMBER: 'Please enter a valid option number...',
  TRANSACTION_DESC: 'Transaction Desc',
  TRANSACTION_ID_NOT_FOUND: 'Transaction ID NOT found...',
  TRAN_ID_ALREADY_EXIST: 'Tran ID already exist...',
  UNABLE_TO_LOOKUP_TRANSACTION: 'Unable to lookup Transaction...',
  UNABLE_TO_LOOKUP_USER: 'Unable to lookup User...',
  UNABLE_TO_UPDATE_USER: 'Unable to Update User...',
  UNEXPECTED_ABEND_OCCURRED: 'UNEXPECTED ABEND OCCURRED.',
  UNEXPECTED_DATA_SCENARIO: 'UNEXPECTED DATA SCENARIO',
  USER_ID_CAN_NOT_BE_EMPTY: 'User ID can NOT be empty...',
  USER_ID_NOT_FOUND: 'User ID NOT found...',
  USER_TYPE_CAN_NOT_BE_EMPTY: 'User Type can NOT be empty...',
  YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE:
    'You are already at the bottom of the page...',
  YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE:
    'You are already at the top of the page...',
  YOU_ARE_AT_THE_TOP_OF_THE_PAGE: 'You are at the top of the page...',
  YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE:
    'You have reached the bottom of the page...',
  YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE:
    'You have reached the top of the page...',
} as const;

/**
 * Every baseline site that emits each {@link SHARED_MESSAGES} entry.
 *
 * The `satisfies` clause makes the key set structurally identical to
 * {@link SHARED_MESSAGES}, so a message added there without provenance - or
 * provenance left behind for a message that was removed - is a compile error
 * rather than a silent gap.
 */
export const SHARED_MESSAGE_SOURCES = {
  ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER: [
    { file: PROGRAM_SOURCE_FILES.COCRDLIC, lines: [1022] },
    { file: PROGRAM_SOURCE_FILES.COCRDSLC, lines: [670] },
    { file: PROGRAM_SOURCE_FILES.COCRDUPC, lines: [745] },
  ],
  ACCOUNT_ID_NOT_FOUND: [
    { file: PROGRAM_SOURCE_FILES.COBIL00C, lines: [361, 392, 425] },
    { file: PROGRAM_SOURCE_FILES.COTRN02C, lines: [593] },
  ],
  CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER: [
    { file: PROGRAM_SOURCE_FILES.COCRDLIC, lines: [1058] },
    { file: PROGRAM_SOURCE_FILES.COCRDSLC, lines: [711] },
    { file: PROGRAM_SOURCE_FILES.COCRDUPC, lines: [789] },
  ],
  FIRST_NAME_CAN_NOT_BE_EMPTY: [
    { file: PROGRAM_SOURCE_FILES.COUSR01C, lines: [120] },
    { file: PROGRAM_SOURCE_FILES.COUSR02C, lines: [188] },
  ],
  INVALID_SELECTION_VALID_VALUE_IS_S: [
    { file: PROGRAM_SOURCE_FILES.COPAUS0C, lines: [328] },
    { file: PROGRAM_SOURCE_FILES.COTRN00C, lines: [199] },
  ],
  INVALID_VALUE_VALID_VALUES_ARE_Y_N: [
    { file: PROGRAM_SOURCE_FILES.COBIL00C, lines: [187] },
    { file: PROGRAM_SOURCE_FILES.COTRN02C, lines: [184] },
  ],
  LAST_NAME_CAN_NOT_BE_EMPTY: [
    { file: PROGRAM_SOURCE_FILES.COUSR01C, lines: [126] },
    { file: PROGRAM_SOURCE_FILES.COUSR02C, lines: [194] },
  ],
  PASSWORD_CAN_NOT_BE_EMPTY: [
    { file: PROGRAM_SOURCE_FILES.COUSR01C, lines: [138] },
    { file: PROGRAM_SOURCE_FILES.COUSR02C, lines: [200] },
  ],
  PLEASE_ENTER_A_VALID_OPTION_NUMBER: [
    { file: PROGRAM_SOURCE_FILES.COADM01C, lines: [135] },
    { file: PROGRAM_SOURCE_FILES.COMEN01C, lines: [131] },
  ],
  TRANSACTION_DESC: [
    { file: PROGRAM_SOURCE_FILES.COTRTLIC, lines: [1083] },
    { file: PROGRAM_SOURCE_FILES.COTRTUPC, lines: [758] },
  ],
  TRANSACTION_ID_NOT_FOUND: [
    { file: PROGRAM_SOURCE_FILES.COBIL00C, lines: [456] },
    { file: PROGRAM_SOURCE_FILES.COTRN01C, lines: [285] },
    { file: PROGRAM_SOURCE_FILES.COTRN02C, lines: [657] },
  ],
  TRAN_ID_ALREADY_EXIST: [
    { file: PROGRAM_SOURCE_FILES.COBIL00C, lines: [536] },
    { file: PROGRAM_SOURCE_FILES.COTRN02C, lines: [738] },
  ],
  UNABLE_TO_LOOKUP_TRANSACTION: [
    { file: PROGRAM_SOURCE_FILES.COBIL00C, lines: [463, 492] },
    { file: PROGRAM_SOURCE_FILES.COTRN01C, lines: [292] },
    { file: PROGRAM_SOURCE_FILES.COTRN02C, lines: [664, 693] },
  ],
  UNABLE_TO_LOOKUP_USER: [
    { file: PROGRAM_SOURCE_FILES.COUSR00C, lines: [610, 644, 678] },
    { file: PROGRAM_SOURCE_FILES.COUSR02C, lines: [349] },
    { file: PROGRAM_SOURCE_FILES.COUSR03C, lines: [296] },
  ],
  UNABLE_TO_UPDATE_USER: [
    { file: PROGRAM_SOURCE_FILES.COUSR02C, lines: [386] },
    { file: PROGRAM_SOURCE_FILES.COUSR03C, lines: [332] },
  ],
  UNEXPECTED_ABEND_OCCURRED: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [4206] },
    { file: PROGRAM_SOURCE_FILES.COACTVWC, lines: [919] },
    { file: PROGRAM_SOURCE_FILES.COCRDSLC, lines: [860] },
    { file: PROGRAM_SOURCE_FILES.COCRDUPC, lines: [1534] },
    { file: PROGRAM_SOURCE_FILES.COTRTUPC, lines: [1678] },
  ],
  UNEXPECTED_DATA_SCENARIO: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2637] },
    { file: PROGRAM_SOURCE_FILES.COACTVWC, lines: [379] },
    { file: PROGRAM_SOURCE_FILES.COCRDSLC, lines: [377] },
    { file: PROGRAM_SOURCE_FILES.COCRDUPC, lines: [1023] },
    { file: PROGRAM_SOURCE_FILES.COTRTUPC, lines: [1077] },
  ],
  USER_ID_CAN_NOT_BE_EMPTY: [
    { file: PROGRAM_SOURCE_FILES.COUSR01C, lines: [132] },
    { file: PROGRAM_SOURCE_FILES.COUSR02C, lines: [148, 182] },
    { file: PROGRAM_SOURCE_FILES.COUSR03C, lines: [147, 179] },
  ],
  USER_ID_NOT_FOUND: [
    { file: PROGRAM_SOURCE_FILES.COUSR02C, lines: [342, 379] },
    { file: PROGRAM_SOURCE_FILES.COUSR03C, lines: [289, 325] },
  ],
  USER_TYPE_CAN_NOT_BE_EMPTY: [
    { file: PROGRAM_SOURCE_FILES.COUSR01C, lines: [144] },
    { file: PROGRAM_SOURCE_FILES.COUSR02C, lines: [206] },
  ],
  YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE: [
    { file: PROGRAM_SOURCE_FILES.COPAUS0C, lines: [409] },
    { file: PROGRAM_SOURCE_FILES.COTRN00C, lines: [270] },
    { file: PROGRAM_SOURCE_FILES.COUSR00C, lines: [273] },
  ],
  YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE: [
    { file: PROGRAM_SOURCE_FILES.COPAUS0C, lines: [381] },
    { file: PROGRAM_SOURCE_FILES.COTRN00C, lines: [248] },
    { file: PROGRAM_SOURCE_FILES.COUSR00C, lines: [251] },
  ],
  YOU_ARE_AT_THE_TOP_OF_THE_PAGE: [
    { file: PROGRAM_SOURCE_FILES.COTRN00C, lines: [608] },
    { file: PROGRAM_SOURCE_FILES.COUSR00C, lines: [603] },
  ],
  YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE: [
    { file: PROGRAM_SOURCE_FILES.COTRN00C, lines: [642] },
    { file: PROGRAM_SOURCE_FILES.COUSR00C, lines: [637] },
  ],
  YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE: [
    { file: PROGRAM_SOURCE_FILES.COTRN00C, lines: [676] },
    { file: PROGRAM_SOURCE_FILES.COUSR00C, lines: [671] },
  ],
} as const satisfies Record<keyof typeof SHARED_MESSAGES, readonly SourceRef[]>;

/**
 * Messages emitted by exactly one program, grouped under that program's name.
 *
 * Access is `PROGRAM_MESSAGES.<PROGRAM>.<KEY>`, so a screen author who knows which
 * COBOL program a screen replaces can reach its strings without searching this
 * file. Keys are derived mechanically from the text: upper-cased, with runs of
 * non-alphanumeric characters folded to a single underscore, `&` spelled `AND` and
 * `#` spelled `NUM`.
 */
export const PROGRAM_MESSAGES = {
  /** account update - `app/cbl/COACTUPC.cbl` (23 messages). */
  COACTUPC: {
    ACCOUNT_STATUS: 'Account Status',
    OPEN_DATE: 'Open Date',
    CREDIT_LIMIT: 'Credit Limit',
    EXPIRY_DATE: 'Expiry Date',
    CASH_CREDIT_LIMIT: 'Cash Credit Limit',
    REISSUE_DATE: 'Reissue Date',
    CURRENT_BALANCE: 'Current Balance',
    CURRENT_CYCLE_CREDIT_LIMIT: 'Current Cycle Credit Limit',
    CURRENT_CYCLE_DEBIT_LIMIT: 'Current Cycle Debit Limit',
    DATE_OF_BIRTH: 'Date of Birth',
    FICO_SCORE: 'FICO Score',
    FIRST_NAME: 'First Name',
    MIDDLE_NAME: 'Middle Name',
    LAST_NAME: 'Last Name',
    ADDRESS_LINE_1: 'Address Line 1',
    COUNTRY: 'Country',
    PHONE_NUMBER_1: 'Phone Number 1',
    PHONE_NUMBER_2: 'Phone Number 2',
    EFT_ACCOUNT_ID: 'EFT Account Id',
    PRIMARY_CARD_HOLDER: 'Primary Card Holder',
    SSN_FIRST_3_CHARS: 'SSN: First 3 chars',
    /**
     * WHY the ampersand is a bare character (Assumptions): the baseline holds a plain
     * `&`, not an HTML entity. React escapes text children when rendering, so this
     * must stay `&` here; writing `&amp;` would display the entity literally.
     */
    SSN_4TH_AND_5TH_CHARS: 'SSN 4th & 5th chars',
    SSN_LAST_4_CHARS: 'SSN Last 4 chars',
  },
  /** account view - `app/cbl/COACTVWC.cbl` (1 message). */
  COACTVWC: {
    /**
     * WHY there are two spaces between "must" and "be" (Assumptions): the baseline
     * literal contains a doubled space. Any whitespace-collapsing edit destroys it
     * silently - a global space-squeezing substitution, a reformat that rejoins the
     * string, an editor "trim whitespace" action - because the result still reads as
     * correct English.
     */
    ACCOUNT_FILTER_MUST_BE_A_NON_ZERO_11_DIGIT_NUMBER:
      'Account Filter must  be a non-zero 11 digit number',
  },
  /** bill payment - `app/cbl/COBIL00C.cbl` (10 messages). */
  COBIL00C: {
    ACCT_ID_CAN_NOT_BE_EMPTY: 'Acct ID can NOT be empty...',
    YOU_HAVE_NOTHING_TO_PAY: 'You have nothing to pay...',
    POS_TERM: 'POS TERM',
    BILL_PAYMENT_ONLINE: 'BILL PAYMENT - ONLINE',
    BILL_PAYMENT: 'BILL PAYMENT',
    CONFIRM_TO_MAKE_A_BILL_PAYMENT: 'Confirm to make a bill payment...',
    UNABLE_TO_LOOKUP_ACCOUNT: 'Unable to lookup Account...',
    UNABLE_TO_UPDATE_ACCOUNT: 'Unable to Update Account...',
    UNABLE_TO_LOOKUP_XREF_AIX_FILE: 'Unable to lookup XREF AIX file...',
    UNABLE_TO_ADD_BILL_PAY_TRANSACTION: 'Unable to Add Bill pay Transaction...',
  },
  /** credit-card list - `app/cbl/COCRDLIC.cbl` (3 messages). */
  COCRDLIC: {
    /**
     * WHY this survives alongside `COTRTLIC.NO_PREVIOUS_PAGES_TO_DISPLAY` (Alternatives
     * Considered): the two differ only in letter case. Any de-duplication that compares
     * case-insensitively would keep one and drop the other, changing what one of the two
     * screens renders.
     */
    NO_PREVIOUS_PAGES_TO_DISPLAY: 'NO PREVIOUS PAGES TO DISPLAY',
    /**
     * WHY this is not merged with `COTRTLIC.NO_MORE_PAGES_TO_DISPLAY` (Alternatives
     * Considered): the two differ only in letter case, so a case-insensitive
     * de-duplication would collapse them. The card-list screen shows this upper-case
     * form and the transaction-type screen shows a mixed-case form; collapsing them
     * would change what one of the two screens renders.
     */
    NO_MORE_PAGES_TO_DISPLAY: 'NO MORE PAGES TO DISPLAY',
    NO_MORE_RECORDS_TO_SHOW: 'NO MORE RECORDS TO SHOW',
  },
  /** main menu - `app/cbl/COMEN01C.cbl` (1 message). */
  COMEN01C: {
    /**
     * WHY the trailing space is part of the value (Assumptions, Alternatives
     * Considered): the COBOL literal ends with a space, and `router.tsx` compares
     * against this constant when it blocks a non-administrator, so the byte is load
     * bearing. Trimming it reads as tidier and silently breaks that comparison and
     * the screen test that asserts the rendered text.
     */
    NO_ACCESS_ADMIN_ONLY_OPTION: 'No access - Admin Only option... ',
  },
  /** pending-authorization summary - `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` (2 messages). */
  COPAUS0C: {
    PLEASE_ENTER_ACCT_ID: 'Please enter Acct Id...',
    ACCT_ID_MUST_BE_NUMERIC: 'Acct Id must be Numeric ...',
  },
  /** pending-authorization detail - `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` (3 messages). */
  COPAUS1C: {
    ALREADY_AT_THE_LAST_AUTHORIZATION: 'Already at the last Authorization...',
    AUTH_FRAUD_REMOVED: 'AUTH FRAUD REMOVED...',
    AUTH_MARKED_FRAUD: 'AUTH MARKED FRAUD...',
  },
  /** authorization fraud marking - `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl` (2 messages). */
  COPAUS2C: {
    ADD_SUCCESS: 'ADD SUCCESS',
    UPDT_SUCCESS: 'UPDT SUCCESS',
  },
  /** transaction reports - `app/cbl/CORPT00C.cbl` (19 messages). */
  CORPT00C: {
    MONTHLY: 'Monthly',
    YEARLY: 'Yearly',
    START_DATE_MONTH_CAN_NOT_BE_EMPTY: 'Start Date - Month can NOT be empty...',
    START_DATE_DAY_CAN_NOT_BE_EMPTY: 'Start Date - Day can NOT be empty...',
    START_DATE_YEAR_CAN_NOT_BE_EMPTY: 'Start Date - Year can NOT be empty...',
    END_DATE_MONTH_CAN_NOT_BE_EMPTY: 'End Date - Month can NOT be empty...',
    END_DATE_DAY_CAN_NOT_BE_EMPTY: 'End Date - Day can NOT be empty...',
    END_DATE_YEAR_CAN_NOT_BE_EMPTY: 'End Date - Year can NOT be empty...',
    START_DATE_NOT_A_VALID_MONTH: 'Start Date - Not a valid Month...',
    START_DATE_NOT_A_VALID_DAY: 'Start Date - Not a valid Day...',
    START_DATE_NOT_A_VALID_YEAR: 'Start Date - Not a valid Year...',
    END_DATE_NOT_A_VALID_MONTH: 'End Date - Not a valid Month...',
    END_DATE_NOT_A_VALID_DAY: 'End Date - Not a valid Day...',
    END_DATE_NOT_A_VALID_YEAR: 'End Date - Not a valid Year...',
    START_DATE_NOT_A_VALID_DATE: 'Start Date - Not a valid date...',
    END_DATE_NOT_A_VALID_DATE: 'End Date - Not a valid date...',
    CUSTOM: 'Custom',
    SELECT_A_REPORT_TYPE_TO_PRINT_REPORT:
      'Select a report type to print report...',
    UNABLE_TO_WRITE_TDQ_JOBS: 'Unable to Write TDQ (JOBS)...',
  },
  /** sign-on - `app/cbl/COSGN00C.cbl` (5 messages). */
  COSGN00C: {
    PLEASE_ENTER_USER_ID: 'Please enter User ID ...',
    PLEASE_ENTER_PASSWORD: 'Please enter Password ...',
    WRONG_PASSWORD_TRY_AGAIN: 'Wrong Password. Try again ...',
    USER_NOT_FOUND_TRY_AGAIN: 'User not found. Try again ...',
    UNABLE_TO_VERIFY_THE_USER: 'Unable to verify the User ...',
  },
  /** transaction list - `app/cbl/COTRN00C.cbl` (2 messages). */
  COTRN00C: {
    TRAN_ID_MUST_BE_NUMERIC: 'Tran ID must be Numeric ...',
    /**
     * WHY the lower-case "transaction" is preserved (Assumptions): the shared
     * `SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION` spells it with a capital T at
     * its three sites, while this program spells it lower-case at all three of its
     * own. Normalising either way alters text that a golden-master comparison reads.
     */
    UNABLE_TO_LOOKUP_TRANSACTION: 'Unable to lookup transaction...',
  },
  /** transaction view - `app/cbl/COTRN01C.cbl` (1 message). */
  COTRN01C: {
    TRAN_ID_CAN_NOT_BE_EMPTY: 'Tran ID can NOT be empty...',
  },
  /** transaction add - `app/cbl/COTRN02C.cbl` (27 messages). */
  COTRN02C: {
    CONFIRM_TO_ADD_THIS_TRANSACTION: 'Confirm to add this transaction...',
    ACCOUNT_ID_MUST_BE_NUMERIC: 'Account ID must be Numeric...',
    CARD_NUMBER_MUST_BE_NUMERIC: 'Card Number must be Numeric...',
    ACCOUNT_OR_CARD_NUMBER_MUST_BE_ENTERED:
      'Account or Card Number must be entered...',
    TYPE_CD_CAN_NOT_BE_EMPTY: 'Type CD can NOT be empty...',
    CATEGORY_CD_CAN_NOT_BE_EMPTY: 'Category CD can NOT be empty...',
    SOURCE_CAN_NOT_BE_EMPTY: 'Source can NOT be empty...',
    DESCRIPTION_CAN_NOT_BE_EMPTY: 'Description can NOT be empty...',
    AMOUNT_CAN_NOT_BE_EMPTY: 'Amount can NOT be empty...',
    ORIG_DATE_CAN_NOT_BE_EMPTY: 'Orig Date can NOT be empty...',
    PROC_DATE_CAN_NOT_BE_EMPTY: 'Proc Date can NOT be empty...',
    MERCHANT_ID_CAN_NOT_BE_EMPTY: 'Merchant ID can NOT be empty...',
    MERCHANT_NAME_CAN_NOT_BE_EMPTY: 'Merchant Name can NOT be empty...',
    MERCHANT_CITY_CAN_NOT_BE_EMPTY: 'Merchant City can NOT be empty...',
    MERCHANT_ZIP_CAN_NOT_BE_EMPTY: 'Merchant Zip can NOT be empty...',
    TYPE_CD_MUST_BE_NUMERIC: 'Type CD must be Numeric...',
    CATEGORY_CD_MUST_BE_NUMERIC: 'Category CD must be Numeric...',
    AMOUNT_SHOULD_BE_IN_FORMAT_99999999_99:
      'Amount should be in format -99999999.99',
    ORIG_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD:
      'Orig Date should be in format YYYY-MM-DD',
    PROC_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD:
      'Proc Date should be in format YYYY-MM-DD',
    ORIG_DATE_NOT_A_VALID_DATE: 'Orig Date - Not a valid date...',
    PROC_DATE_NOT_A_VALID_DATE: 'Proc Date - Not a valid date...',
    MERCHANT_ID_MUST_BE_NUMERIC: 'Merchant ID must be Numeric...',
    UNABLE_TO_LOOKUP_ACCT_IN_XREF_AIX_FILE:
      'Unable to lookup Acct in XREF AIX file...',
    CARD_NUMBER_NOT_FOUND: 'Card Number NOT found...',
    /**
     * WHY the hash is left as-is (Assumptions): it is a literal character in the baseline
     * standing for the word "number", not a substitution marker. Treating it as a
     * placeholder and interpolating a card number into it would invent text the mainframe
     * never displays - and would leak a card number into an error message.
     */
    UNABLE_TO_LOOKUP_CARD_NUM_IN_XREF_FILE:
      'Unable to lookup Card # in XREF file...',
    UNABLE_TO_ADD_TRANSACTION: 'Unable to Add Transaction...',
  },
  /** transaction-type list and update - `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` (16 messages). */
  COTRTLIC: {
    TYPE_CODE_FILTER_IF_SUPPLIED_MUST_BE_A_2_DIGIT_NUMBER:
      'TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER',
    NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS:
      'No Records found for these filter conditions',
    NO_PREVIOUS_PAGES_TO_DISPLAY: 'No previous pages to display',
    NO_MORE_PAGES_TO_DISPLAY: 'No more pages to display',
    C_TR_TYPE_FORWARD_FETCH: 'C-TR-TYPE-FORWARD fetch',
    C_TR_TYPE_FORWARD_CLOSE: 'C-TR-TYPE-FORWARD close',
    ERROR_ON_FETCH_CURSOR_C_TR_TYPE_BACKWARD:
      'Error on fetch Cursor C-TR-TYPE-BACKWARD',
    /**
     * WHY this ends with a space (Assumptions): the trailing space is in the baseline
     * literal, and this string is concatenated with an SQLCODE by `formatDb2Message`, so
     * the space is the separator. Trimming it would run the table name into the code.
     */
    ERROR_READING_TRANSACTION_TYPE_TABLE:
      'Error reading TRANSACTION_TYPE table ',
    /**
     * WHY there is a space on both sides of the question mark (Assumptions): both are in
     * the baseline literal. The trailing one separates this text from the SQLCODE that
     * `formatDb2Message` appends; the one before the mark is the baseline house style,
     * shared with the deadlock message below.
     */
    RECORD_NOT_FOUND_DELETED_BY_OTHERS:
      'Record not found. Deleted by others ? ',
    /**
     * WHY there is a space before the question mark (Assumptions): it is in the baseline
     * literal. Unlike the record-not-found message above, this one has no trailing space,
     * so the inconsistency between the two is itself faithful and must not be evened out.
     */
    DEADLOCK_SOMEONE_ELSE_UPDATING: 'Deadlock. Someone else updating ?',
    UPDATE_FAILED_WITH: 'Update failed with',
    PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST:
      'Please delete associated child records first:',
    DELETE_FAILED_WITH_MESSAGE: 'Delete failed with message:',
    C_TR_TYPE_FORWARD_OPEN: 'C-TR-TYPE-FORWARD Open',
    C_TR_TYPE_BACKWARD_OPEN: 'C-TR-TYPE-BACKWARD Open',
    C_TR_TYPE_BACKWARD_CLOSE: 'C-TR-TYPE-BACKWARD close',
  },
  /** transaction-type maintenance - `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` (1 message). */
  COTRTUPC: {
    TRAN_TYPE_CODE: 'Tran Type code',
  },
  /** user list - `app/cbl/COUSR00C.cbl` (1 message). */
  COUSR00C: {
    INVALID_SELECTION_VALID_VALUES_ARE_U_AND_D:
      'Invalid selection. Valid values are U and D',
  },
  /** user add - `app/cbl/COUSR01C.cbl` (2 messages). */
  COUSR01C: {
    USER_ID_ALREADY_EXIST: 'User ID already exist...',
    UNABLE_TO_ADD_USER: 'Unable to Add User...',
  },
  /** user update - `app/cbl/COUSR02C.cbl` (2 messages). */
  COUSR02C: {
    PLEASE_MODIFY_TO_UPDATE: 'Please modify to update ...',
    PRESS_PF5_KEY_TO_SAVE_YOUR_UPDATES:
      'Press PF5 key to save your updates ...',
  },
  /** user delete - `app/cbl/COUSR03C.cbl` (1 message). */
  COUSR03C: {
    PRESS_PF5_KEY_TO_DELETE_THIS_USER: 'Press PF5 key to delete this user ...',
  },
} as const;

/**
 * Every baseline line that emits each {@link PROGRAM_MESSAGES} entry.
 *
 * The mapped `satisfies` type ties this index to {@link PROGRAM_MESSAGES} group by
 * group and key by key, so the two cannot drift apart without failing type-check.
 */
export const PROGRAM_MESSAGE_SOURCES = {
  COACTUPC: {
    ACCOUNT_STATUS: [1472],
    OPEN_DATE: [1478],
    CREDIT_LIMIT: [1484],
    EXPIRY_DATE: [1490],
    CASH_CREDIT_LIMIT: [1496],
    REISSUE_DATE: [1503],
    CURRENT_BALANCE: [1509],
    CURRENT_CYCLE_CREDIT_LIMIT: [1515],
    CURRENT_CYCLE_DEBIT_LIMIT: [1522],
    DATE_OF_BIRTH: [1533],
    FICO_SCORE: [1545],
    FIRST_NAME: [1560],
    MIDDLE_NAME: [1568],
    LAST_NAME: [1576],
    ADDRESS_LINE_1: [1584],
    COUNTRY: [1623],
    PHONE_NUMBER_1: [1632],
    PHONE_NUMBER_2: [1640],
    EFT_ACCOUNT_ID: [1648],
    PRIMARY_CARD_HOLDER: [1657],
    SSN_FIRST_3_CHARS: [2439],
    SSN_4TH_AND_5TH_CHARS: [2469],
    SSN_LAST_4_CHARS: [2481],
  },
  COACTVWC: {
    ACCOUNT_FILTER_MUST_BE_A_NON_ZERO_11_DIGIT_NUMBER: [672],
  },
  COBIL00C: {
    ACCT_ID_CAN_NOT_BE_EMPTY: [161],
    YOU_HAVE_NOTHING_TO_PAY: [201],
    POS_TERM: [222],
    BILL_PAYMENT_ONLINE: [223],
    BILL_PAYMENT: [227],
    CONFIRM_TO_MAKE_A_BILL_PAYMENT: [237],
    UNABLE_TO_LOOKUP_ACCOUNT: [368],
    UNABLE_TO_UPDATE_ACCOUNT: [399],
    UNABLE_TO_LOOKUP_XREF_AIX_FILE: [432],
    UNABLE_TO_ADD_BILL_PAY_TRANSACTION: [543],
  },
  COCRDLIC: {
    NO_PREVIOUS_PAGES_TO_DISPLAY: [903],
    NO_MORE_PAGES_TO_DISPLAY: [908],
    NO_MORE_RECORDS_TO_SHOW: [1219, 1239],
  },
  COMEN01C: {
    NO_ACCESS_ADMIN_ONLY_OPTION: [140],
  },
  COPAUS0C: {
    PLEASE_ENTER_ACCT_ID: [269],
    ACCT_ID_MUST_BE_NUMERIC: [278],
  },
  COPAUS1C: {
    ALREADY_AT_THE_LAST_AUTHORIZATION: [283],
    AUTH_FRAUD_REMOVED: [535],
    AUTH_MARKED_FRAUD: [537],
  },
  COPAUS2C: {
    ADD_SUCCESS: [201],
    UPDT_SUCCESS: [232],
  },
  CORPT00C: {
    MONTHLY: [214],
    YEARLY: [240],
    START_DATE_MONTH_CAN_NOT_BE_EMPTY: [261],
    START_DATE_DAY_CAN_NOT_BE_EMPTY: [268],
    START_DATE_YEAR_CAN_NOT_BE_EMPTY: [275],
    END_DATE_MONTH_CAN_NOT_BE_EMPTY: [282],
    END_DATE_DAY_CAN_NOT_BE_EMPTY: [289],
    END_DATE_YEAR_CAN_NOT_BE_EMPTY: [296],
    START_DATE_NOT_A_VALID_MONTH: [331],
    START_DATE_NOT_A_VALID_DAY: [340],
    START_DATE_NOT_A_VALID_YEAR: [348],
    END_DATE_NOT_A_VALID_MONTH: [357],
    END_DATE_NOT_A_VALID_DAY: [366],
    END_DATE_NOT_A_VALID_YEAR: [374],
    START_DATE_NOT_A_VALID_DATE: [400],
    END_DATE_NOT_A_VALID_DATE: [420],
    CUSTOM: [433],
    SELECT_A_REPORT_TYPE_TO_PRINT_REPORT: [438],
    UNABLE_TO_WRITE_TDQ_JOBS: [531],
  },
  COSGN00C: {
    PLEASE_ENTER_USER_ID: [120],
    PLEASE_ENTER_PASSWORD: [125],
    WRONG_PASSWORD_TRY_AGAIN: [242],
    USER_NOT_FOUND_TRY_AGAIN: [249],
    UNABLE_TO_VERIFY_THE_USER: [254],
  },
  COTRN00C: {
    TRAN_ID_MUST_BE_NUMERIC: [214],
    UNABLE_TO_LOOKUP_TRANSACTION: [615, 649, 683],
  },
  COTRN01C: {
    TRAN_ID_CAN_NOT_BE_EMPTY: [149],
  },
  COTRN02C: {
    CONFIRM_TO_ADD_THIS_TRANSACTION: [178],
    ACCOUNT_ID_MUST_BE_NUMERIC: [199],
    CARD_NUMBER_MUST_BE_NUMERIC: [213],
    ACCOUNT_OR_CARD_NUMBER_MUST_BE_ENTERED: [226],
    TYPE_CD_CAN_NOT_BE_EMPTY: [254],
    CATEGORY_CD_CAN_NOT_BE_EMPTY: [260],
    SOURCE_CAN_NOT_BE_EMPTY: [266],
    DESCRIPTION_CAN_NOT_BE_EMPTY: [272],
    AMOUNT_CAN_NOT_BE_EMPTY: [278],
    ORIG_DATE_CAN_NOT_BE_EMPTY: [284],
    PROC_DATE_CAN_NOT_BE_EMPTY: [290],
    MERCHANT_ID_CAN_NOT_BE_EMPTY: [296],
    MERCHANT_NAME_CAN_NOT_BE_EMPTY: [302],
    MERCHANT_CITY_CAN_NOT_BE_EMPTY: [308],
    MERCHANT_ZIP_CAN_NOT_BE_EMPTY: [314],
    TYPE_CD_MUST_BE_NUMERIC: [325],
    CATEGORY_CD_MUST_BE_NUMERIC: [331],
    AMOUNT_SHOULD_BE_IN_FORMAT_99999999_99: [345],
    ORIG_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD: [360],
    PROC_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD: [375],
    ORIG_DATE_NOT_A_VALID_DATE: [401],
    PROC_DATE_NOT_A_VALID_DATE: [421],
    MERCHANT_ID_MUST_BE_NUMERIC: [432],
    UNABLE_TO_LOOKUP_ACCT_IN_XREF_AIX_FILE: [600],
    CARD_NUMBER_NOT_FOUND: [626],
    UNABLE_TO_LOOKUP_CARD_NUM_IN_XREF_FILE: [633],
    UNABLE_TO_ADD_TRANSACTION: [745],
  },
  COTRTLIC: {
    TYPE_CODE_FILTER_IF_SUPPLIED_MUST_BE_A_2_DIGIT_NUMBER: [1116],
    NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS: [1264],
    NO_PREVIOUS_PAGES_TO_DISPLAY: [1534],
    NO_MORE_PAGES_TO_DISPLAY: [1539],
    C_TR_TYPE_FORWARD_FETCH: [1686],
    C_TR_TYPE_FORWARD_CLOSE: [1712, 1986],
    ERROR_ON_FETCH_CURSOR_C_TR_TYPE_BACKWARD: [1784],
    ERROR_READING_TRANSACTION_TYPE_TABLE: [1826],
    RECORD_NOT_FOUND_DELETED_BY_OTHERS: [1864],
    DEADLOCK_SOMEONE_ELSE_UPDATING: [1874],
    UPDATE_FAILED_WITH: [1883],
    PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST: [1919],
    DELETE_FAILED_WITH_MESSAGE: [1929],
    C_TR_TYPE_FORWARD_OPEN: [1958],
    C_TR_TYPE_BACKWARD_OPEN: [2013],
    C_TR_TYPE_BACKWARD_CLOSE: [2042],
  },
  COTRTUPC: {
    TRAN_TYPE_CODE: [826],
  },
  COUSR00C: {
    INVALID_SELECTION_VALID_VALUES_ARE_U_AND_D: [212],
  },
  COUSR01C: {
    USER_ID_ALREADY_EXIST: [263],
    UNABLE_TO_ADD_USER: [270],
  },
  COUSR02C: {
    PLEASE_MODIFY_TO_UPDATE: [239],
    PRESS_PF5_KEY_TO_SAVE_YOUR_UPDATES: [336],
  },
  COUSR03C: {
    PRESS_PF5_KEY_TO_DELETE_THIS_USER: [283],
  },
} as const satisfies {
  readonly [P in keyof typeof PROGRAM_MESSAGES]: Record<
    keyof (typeof PROGRAM_MESSAGES)[P],
    readonly number[]
  >;
};

/**
 * The 27 field labels the account-update program moves into
 * `WS-EDIT-VARIABLE-NAME` (`PIC X(25)`) before its shared edit routine composes a
 * validation message from them.
 *
 * WHY these are catalogued even though they read like field names rather than
 * sentences (Trade-offs, Assumptions): they are not screen labels painted by a BMS
 * map - they are program literals that the edit routine concatenates with a suffix
 * to build the text the operator reads, so they are user-visible and belong here.
 * Leaving them out would push them inline into the account-update screen and break
 * the guarantee that this module owns every displayed string. Compose them with
 * {@link formatFieldValidationMessage}.
 *
 * WHY the group holds 27 entries when the migration inventory counts 23
 * (Refactoring Rationale): the inventory was produced by a scan that only accepted
 * literals of six characters or more, which is a reasonable screen for finding
 * whole sentences but wrong for this class. Four live labels - `SSN`, `State`,
 * `Zip` and `City` - fall under that threshold and were dropped by it. This class
 * is defined by the field it targets, not by length, so all 27 live labels are
 * present; the 23 that clear six characters are still exactly the inventory's set.
 * A twenty-eighth label, `Address Line 2`, is commented out at line 1614 and is
 * excluded.
 */
export const ACCOUNT_UPDATE_FIELD_LABELS = {
  ACCOUNT_STATUS: 'Account Status',
  OPEN_DATE: 'Open Date',
  CREDIT_LIMIT: 'Credit Limit',
  EXPIRY_DATE: 'Expiry Date',
  CASH_CREDIT_LIMIT: 'Cash Credit Limit',
  REISSUE_DATE: 'Reissue Date',
  CURRENT_BALANCE: 'Current Balance',
  CURRENT_CYCLE_CREDIT_LIMIT: 'Current Cycle Credit Limit',
  CURRENT_CYCLE_DEBIT_LIMIT: 'Current Cycle Debit Limit',
  SSN: 'SSN',
  DATE_OF_BIRTH: 'Date of Birth',
  FICO_SCORE: 'FICO Score',
  FIRST_NAME: 'First Name',
  MIDDLE_NAME: 'Middle Name',
  LAST_NAME: 'Last Name',
  ADDRESS_LINE_1: 'Address Line 1',
  STATE: 'State',
  ZIP: 'Zip',
  CITY: 'City',
  COUNTRY: 'Country',
  PHONE_NUMBER_1: 'Phone Number 1',
  PHONE_NUMBER_2: 'Phone Number 2',
  EFT_ACCOUNT_ID: 'EFT Account Id',
  PRIMARY_CARD_HOLDER: 'Primary Card Holder',
  SSN_FIRST_3_CHARS: 'SSN: First 3 chars',
  SSN_4TH_AND_5TH_CHARS: 'SSN 4th & 5th chars',
  SSN_LAST_4_CHARS: 'SSN Last 4 chars',
} as const;

/** Baseline line of each {@link ACCOUNT_UPDATE_FIELD_LABELS} entry. */
export const ACCOUNT_UPDATE_FIELD_LABEL_SOURCES = {
  ACCOUNT_STATUS: [1472],
  OPEN_DATE: [1478],
  CREDIT_LIMIT: [1484],
  EXPIRY_DATE: [1490],
  CASH_CREDIT_LIMIT: [1496],
  REISSUE_DATE: [1503],
  CURRENT_BALANCE: [1509],
  CURRENT_CYCLE_CREDIT_LIMIT: [1515],
  CURRENT_CYCLE_DEBIT_LIMIT: [1522],
  SSN: [1529],
  DATE_OF_BIRTH: [1533],
  FICO_SCORE: [1545],
  FIRST_NAME: [1560],
  MIDDLE_NAME: [1568],
  LAST_NAME: [1576],
  ADDRESS_LINE_1: [1584],
  STATE: [1592],
  ZIP: [1605],
  CITY: [1615],
  COUNTRY: [1623],
  PHONE_NUMBER_1: [1632],
  PHONE_NUMBER_2: [1640],
  EFT_ACCOUNT_ID: [1648],
  PRIMARY_CARD_HOLDER: [1657],
  SSN_FIRST_3_CHARS: [2439],
  SSN_4TH_AND_5TH_CHARS: [2469],
  SSN_LAST_4_CHARS: [2481],
} as const satisfies Record<
  keyof typeof ACCOUNT_UPDATE_FIELD_LABELS,
  readonly number[]
>;

/**
 * The 21 suffixes the shared edit routine appends to a trimmed field label to
 * form a complete validation message.
 *
 * The baseline builds the text with
 * `STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) <suffix> DELIMITED BY SIZE INTO WS-RETURN-MSG`,
 * so each suffix already carries its own leading space or colon and must not be
 * given another one at the join. {@link formatFieldValidationMessage} performs the
 * concatenation.
 *
 * WHY the suffixes are modelled separately from the labels rather than as
 * pre-joined sentences (Trade-offs): the baseline pairs labels and suffixes at run
 * time according to which edit failed, so enumerating every pairing would produce
 * hundreds of combinations, most of which the program can never emit. Keeping the
 * two axes apart reproduces the same set of reachable messages with the same
 * pieces the COBOL uses.
 *
 * The capital "A" in the three "must be A n digit number." suffixes is a
 * grammatical defect in the baseline and is reproduced, as are the counterpart
 * defects in the upper-case filter messages.
 */
export const FIELD_VALIDATION_SUFFIXES = {
  MUST_BE_SUPPLIED: ' must be supplied.',
  MUST_BE_Y_OR_N: ' must be Y or N.',
  CAN_HAVE_ALPHABETS_ONLY: ' can have alphabets only.',
  CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY: ' can have numbers or alphabets only.',
  MUST_BE_ALL_NUMERIC: ' must be all numeric.',
  MUST_NOT_BE_ZERO: ' must not be zero.',
  IS_NOT_VALID: ' is not valid',
  AREA_CODE_MUST_BE_SUPPLIED: ': Area code must be supplied.',
  AREA_CODE_MUST_BE_A_3_DIGIT_NUMBER: ': Area code must be A 3 digit number.',
  AREA_CODE_CANNOT_BE_ZERO: ': Area code cannot be zero',
  NOT_VALID_NORTH_AMERICA_GENERAL_PURPOSE_AREA_CODE:
    ': Not valid North America general purpose area code',
  PREFIX_CODE_MUST_BE_SUPPLIED: ': Prefix code must be supplied.',
  PREFIX_CODE_MUST_BE_A_3_DIGIT_NUMBER:
    ': Prefix code must be A 3 digit number.',
  PREFIX_CODE_CANNOT_BE_ZERO: ': Prefix code cannot be zero',
  LINE_NUMBER_CODE_MUST_BE_SUPPLIED: ': Line number code must be supplied.',
  LINE_NUMBER_CODE_MUST_BE_A_4_DIGIT_NUMBER:
    ': Line number code must be A 4 digit number.',
  LINE_NUMBER_CODE_CANNOT_BE_ZERO: ': Line number code cannot be zero',
  SHOULD_NOT_BE_000_666_OR_BETWEEN_900_AND_999:
    ': should not be 000, 666, or between 900 and 999',
  IS_NOT_A_VALID_STATE_CODE: ': is not a valid state code',
  SHOULD_BE_BETWEEN_300_AND_850: ': should be between 300 and 850',
  MUST_BE_NUMERIC: ' must be numeric.',
} as const;

/** Baseline sites of each {@link FIELD_VALIDATION_SUFFIXES} entry. */
export const FIELD_VALIDATION_SUFFIX_SOURCES = {
  MUST_BE_SUPPLIED: [
    {
      file: PROGRAM_SOURCE_FILES.COACTUPC,
      lines: [1841, 1869, 1915, 1972, 2126, 2191],
    },
    { file: PROGRAM_SOURCE_FILES.COTRTLIC, lines: [1198] },
    { file: PROGRAM_SOURCE_FILES.COTRTUPC, lines: [866, 924] },
  ],
  MUST_BE_Y_OR_N: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [1886] }],
  CAN_HAVE_ALPHABETS_ONLY: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [1941, 2047] },
  ],
  CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [1999, 2095] },
    { file: PROGRAM_SOURCE_FILES.COTRTLIC, lines: [1225] },
    { file: PROGRAM_SOURCE_FILES.COTRTUPC, lines: [893] },
  ],
  MUST_BE_ALL_NUMERIC: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2146] }],
  MUST_NOT_BE_ZERO: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2163] },
    { file: PROGRAM_SOURCE_FILES.COTRTUPC, lines: [961] },
  ],
  IS_NOT_VALID: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2209] }],
  AREA_CODE_MUST_BE_SUPPLIED: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2254] },
  ],
  AREA_CODE_MUST_BE_A_3_DIGIT_NUMBER: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2272] },
  ],
  AREA_CODE_CANNOT_BE_ZERO: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2286] },
  ],
  NOT_VALID_NORTH_AMERICA_GENERAL_PURPOSE_AREA_CODE: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2306] },
  ],
  PREFIX_CODE_MUST_BE_SUPPLIED: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2325] },
  ],
  PREFIX_CODE_MUST_BE_A_3_DIGIT_NUMBER: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2343] },
  ],
  PREFIX_CODE_CANNOT_BE_ZERO: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2357] },
  ],
  LINE_NUMBER_CODE_MUST_BE_SUPPLIED: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2378] },
  ],
  LINE_NUMBER_CODE_MUST_BE_A_4_DIGIT_NUMBER: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2396] },
  ],
  LINE_NUMBER_CODE_CANNOT_BE_ZERO: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2410] },
  ],
  SHOULD_NOT_BE_000_666_OR_BETWEEN_900_AND_999: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2457] },
  ],
  IS_NOT_A_VALID_STATE_CODE: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2503] },
  ],
  SHOULD_BE_BETWEEN_300_AND_850: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2523] },
  ],
  MUST_BE_NUMERIC: [{ file: PROGRAM_SOURCE_FILES.COTRTUPC, lines: [943] }],
} as const satisfies Record<
  keyof typeof FIELD_VALIDATION_SUFFIXES,
  readonly SourceRef[]
>;

/**
 * The literal parts of the Db2 diagnostic message that the transaction-type
 * screens display.
 *
 * The composition lives in `app/app-transaction-type-db2/cpy/CSDB2RPY.cpy`, which
 * `COTRTLIC` includes, and reads
 * `STRING FUNCTION TRIM(WS-DB2-CURRENT-ACTION) ' SQLCODE:' WS-DISP-SQLCODE ' ' WS-DSNTIAC-FMTD-TEXT`.
 * The action half of that expression is a catalogued message - the
 * `C_TR_TYPE_*`, `UPDATE_FAILED_WITH` and `DELETE_FAILED_WITH_MESSAGE` entries of
 * `PROGRAM_MESSAGES.COTRTLIC` are exactly the values moved into
 * `WS-DB2-CURRENT-ACTION` - so only the connective text is recorded here.
 * {@link formatDb2Message} performs the join.
 *
 * WHY the connectives are separate constants rather than a template string
 * (Assumptions): `SQLCODE` is rendered by a COBOL edited picture and the trailing
 * detail comes from the `DSNTIAC` utility, so the caller supplies both as already
 * formatted text. Holding the connectives as data keeps the exact spacing -
 * `' SQLCODE:'` has a leading space and no trailing one - visible and testable.
 */
export const DB2_MESSAGE_PARTS = {
  /** Separator between the action label and the SQL code. */
  SQLCODE_SEPARATOR: ' SQLCODE:',
  /** Separator between the SQL code and the formatted diagnostic text. */
  DETAIL_SEPARATOR: ' ',
  /** Prefix used when the `DSNTIAC` formatting utility itself returns non-zero. */
  DSNTIAC_ERROR_PREFIX: 'DSNTIAC CD: ',
  /** Action label used when the Db2 connectivity priming query fails. */
  ACCESS_FAILURE_ACTION: 'Db2 access failure. ',
  /** Where the composition and its literals are declared. */
  source: {
    file: 'app/app-transaction-type-db2/cpy/CSDB2RPY.cpy',
    lines: [40, 70, 76, 78],
  },
} as const;

/**
 * Pads a catalogued literal out to the width its COBOL `PICTURE` clause declares,
 * reproducing what a `MOVE` into that field would have produced at run time.
 *
 * COBOL pads a short sending item on the right with spaces and truncates an
 * over-long one on the right, and this mirrors both behaviours so a caller that
 * needs the exact runtime field value - a fixed-width export, or a test comparing
 * against mainframe output - can obtain it without the catalog storing fabricated
 * bytes.
 * @param text - The literal exactly as transcribed from the copybook.
 * @param declaredWidth - Field width from the `PIC X(n)` clause; must be a
 *   non-negative integer.
 * @returns The value padded with trailing spaces to `declaredWidth`, or truncated
 *   to `declaredWidth` when `text` is longer.
 * @throws {RangeError} If `declaredWidth` is negative or not an integer, which
 *   would mean the caller passed something that is not a COBOL field width.
 */
export function padToDeclaredWidth(
  text: string,
  declaredWidth: number,
): string {
  if (!Number.isInteger(declaredWidth) || declaredWidth < 0) {
    throw new RangeError(
      `declaredWidth must be a non-negative integer, received ${String(declaredWidth)}`,
    );
  }
  // Truncating on the right rather than throwing matches COBOL, which silently
  // drops the overflow on an alphanumeric MOVE. Throwing here would turn a
  // faithful reproduction into a runtime failure the mainframe never had.
  return text.length >= declaredWidth
    ? text.slice(0, declaredWidth)
    : text + ' '.repeat(declaredWidth - text.length);
}

/**
 * Builds a field-validation message the way the shared COBOL edit routine does.
 *
 * The baseline trims the 25-character label field before concatenating, so a label
 * shorter than the field does not leave a run of spaces in the middle of the
 * sentence. Only the label is trimmed; the suffix is appended exactly as
 * catalogued, because it already carries its own leading space or colon.
 * @param label - A value from {@link ACCOUNT_UPDATE_FIELD_LABELS}, or any label the
 *   baseline moves into `WS-EDIT-VARIABLE-NAME`.
 * @param suffix - A value from {@link FIELD_VALIDATION_SUFFIXES}.
 * @returns The composed message, trimmed label followed immediately by the suffix.
 */
export function formatFieldValidationMessage(
  label: string,
  suffix: string,
): string {
  // FUNCTION TRIM removes leading and trailing spaces, which String.prototype.trim
  // also does; the baseline field holds no other whitespace, so the two agree.
  return label.trim() + suffix;
}

/**
 * Builds a Db2 diagnostic message the way `CSDB2RPY.cpy` does.
 * @param action - The action label that was in `WS-DB2-CURRENT-ACTION`, normally an
 *   entry of `PROGRAM_MESSAGES.COTRTLIC`.
 * @param sqlCode - The SQL code as already-formatted display text, matching the
 *   baseline's edited `WS-DISP-SQLCODE` picture.
 * @param detail - The formatted diagnostic text from the `DSNTIAC` utility; pass an
 *   empty string when no detail is available.
 * @returns The composed diagnostic message.
 */
export function formatDb2Message(
  action: string,
  sqlCode: string,
  detail: string,
): string {
  return (
    action.trim() +
    DB2_MESSAGE_PARTS.SQLCODE_SEPARATOR +
    sqlCode +
    DB2_MESSAGE_PARTS.DETAIL_SEPARATOR +
    detail
  );
}

/**
 * Reports whether a message-band value means "no message is set".
 *
 * WHY an all-spaces value counts as empty as well as a zero-length one
 * (Assumptions): the baseline's off-condition is `VALUE LOW-VALUES`, and the
 * programs also clear the field with `MOVE SPACES`. A transport that carries either
 * form arrives as a string that is empty or all spaces, and both mean the band
 * should stay hidden. Testing only for a zero-length string would leave the band
 * rendering a blank alert on every quiet screen.
 * @param value - The message-band text received from a service response, or
 *   `null`/`undefined` when the response omitted it.
 * @returns `true` when the band should render nothing.
 */
export function isMessageBandEmpty(value: string | null | undefined): boolean {
  return value === null || value === undefined || value.trim().length === 0;
}

/**
 * The organisation line of the title band, unpadded.
 *
 * Provided as a flat alias because the app shell renders it on every screen; it is
 * the same value as `SCREEN_TITLES.TITLE01.text`.
 */
export const APP_ORGANISATION_TITLE = SCREEN_TITLES.TITLE01.text;

/** The application line of the title band, unpadded. Same value as `SCREEN_TITLES.TITLE02.text`. */
export const APP_TITLE = SCREEN_TITLES.TITLE02.text;

/** Sign-off text naming the application "CCDA". Same value as `SCREEN_TITLES.THANK_YOU.text`. */
export const THANK_YOU_CCDA = SCREEN_TITLES.THANK_YOU.text;

/** Sign-off text naming the application "CardDemo". Same value as `COMMON_MESSAGES.THANK_YOU.text`. */
export const THANK_YOU_CARDDEMO = COMMON_MESSAGES.THANK_YOU.text;

/** Unmapped-attention-key message. Same value as `COMMON_MESSAGES.INVALID_KEY.text`. */
export const INVALID_KEY_PRESSED = COMMON_MESSAGES.INVALID_KEY.text;

/**
 * Text shown when a non-administrator reaches an administrator-only route.
 *
 * WHY this is aliased at the top level (Assumptions): the router guards admin
 * routes with it, and a router should not have to know that the string originates
 * in the main-menu program. The trailing space is part of the value - see the note
 * on the underlying entry.
 */
export const ACCESS_DENIED_ADMIN_ONLY =
  PROGRAM_MESSAGES.COMEN01C.NO_ACCESS_ADMIN_ONLY_OPTION;

/**
 * Headline for the abend surface, rendered by the application error boundary.
 *
 * Aliased because the error boundary is not associated with any one program even
 * though five programs emit this text.
 */
export const UNEXPECTED_ABEND_OCCURRED =
  SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED;

/** Headline for the unexpected-data surface, also rendered by the error boundary. */
export const UNEXPECTED_DATA_SCENARIO =
  SHARED_MESSAGES.UNEXPECTED_DATA_SCENARIO;
