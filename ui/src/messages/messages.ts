/**
 * Verbatim catalog of the CardDemo user-visible strings whose source is a COBOL
 * copybook constant or a COBOL program literal.
 *
 * Purpose
 * -------
 * This module is the sole owner of one clearly bounded half of the SPA's
 * user-visible text: every string the baseline holds as a copybook constant or as
 * a literal inside an online program. Screens, layout components, hooks and tests
 * import THOSE strings from here and never inline one of their own, so the "text
 * is byte-identical to the mainframe" guarantee is enforced for that half by one
 * reviewable module instead of by discipline spread across 21 screen
 * implementations.
 *
 * The other half - the static text PAINTED BY THE BMS MAPS - is deliberately not
 * here. That boundary is drawn by the migration plan itself, which gives this
 * module a different source list from the one it gives the screen and layout
 * components: this module is transcribed from `app/cpy/CSMSG01Y.cpy`,
 * `app/cpy/CSMSG02Y.cpy`, `app/cpy/COTTL01Y.cpy` and the online programs, whereas
 * `app/bms/*.bms` is the source for `ui/src/screens/**`, `ScreenHeader` and
 * `PfKeyBar`. See "Not in this catalog" below for what that leaves out, how large
 * it is, and which module owns it instead. The body of this file already applies
 * the same boundary in the one place it is easy to mistake - see the note above
 * {@link ACCOUNT_UPDATE_FIELD_LABELS}, which is catalogued precisely BECAUSE those
 * entries are program literals rather than map-painted labels.
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
 * The three emission mechanisms
 * -----------------------------
 * The baseline puts text on a screen in three structurally different ways, and a
 * catalog that models only one of them is incomplete however exhaustively it
 * covers that one. All three are represented here, and the mechanism a string
 * arrives by determines which group holds it:
 *
 * 1. `MOVE '<literal>' TO <message field>` - a finished sentence moved in one
 *    step. {@link SHARED_MESSAGES} and {@link PROGRAM_MESSAGES} hold these,
 *    keyed by text because the text is all the program identifies them by.
 * 2. `SET <condition-name> TO TRUE`, where the condition name is an `88` level
 *    declared on the message field and its `VALUE` *is* the message text.
 *    {@link STATUS_MESSAGES} holds these, keyed by condition name.
 * 3. `STRING <parts> DELIMITED BY <delimiter> INTO <message field>` - a sentence
 *    composed at run time from literals and record values.
 *    {@link MESSAGE_TEMPLATES} holds the operator-facing compositions as ordered
 *    parts, {@link FIELD_VALIDATION_SUFFIXES} holds the label-plus-suffix family,
 *    and {@link REDACTED_DIAGNOSTICS} accounts for the compositions whose payload
 *    is a machine-level status value.
 *
 * WHY all three mechanisms are enumerated rather than only the first
 * Refactoring Rationale: the first version of this module was produced by an
 * extraction that matched `MOVE '<literal>'` only. That is a complete method for
 * finished sentences and blind to the other two forms, so 121 condition-name
 * messages and 56 composition fragments were absent while the module still
 * claimed to own every user-visible string. The claim, not the extraction, was
 * the defect: a screen author who could not find a string here would inline it,
 * which is exactly the failure the module exists to prevent.
 *
 * Audience: operator text versus diagnostics
 * -----------------------------------------
 * Some baseline messages exist to carry a machine-level status value to the
 * screen - a Db2 `SQLCODE` and `SQLSTATE`, a `DSNTIAC` diagnostic payload, an IMS
 * status code, a CICS response and reason pair, a declared cursor name, a
 * physical table name. A string is classified **diagnostic** when it (a) carries
 * such a value or identifier, (b) names a persistence-layer mechanism, or (c) is
 * a fragment that only completes when such a value is appended. Everything else
 * is **operator** text.
 *
 * Operator text is carried verbatim and is what the browser renders. Diagnostic
 * text is **not exported as displayable text at all**: {@link REDACTED_DIAGNOSTICS}
 * records where the baseline composes it, what class of internal value it
 * carried, and which verbatim baseline message the target shows instead. The
 * detail itself belongs in a server-side structured log keyed by the correlation
 * identifier the API layer already propagates.
 *
 * WHY the diagnostics are redacted rather than transcribed. Trade-offs: carrying
 * them verbatim would satisfy the centralisation rule and simultaneously ship the
 * schema's cursor names, a physical table name and raw SQL codes into a bundle any
 * browser can read, which is an information-disclosure defect the migration plan's
 * data-exposure narrowing exists to prevent. Redaction costs message specificity
 * on failure paths only, and every replacement chosen is itself a verbatim
 * baseline string, so no invented wording enters the product. This is an
 * intentional behavioural divergence and {@link REDACTED_DIAGNOSTICS} is its
 * register.
 *
 * Quoted baseline operands that are not messages
 * ----------------------------------------------
 * A third class exists and is excluded from this module entirely, because its
 * members are never rendered on any screen. An extraction that collects quoted
 * literals will pick all of them up, so they are enumerated here by kind - not by
 * value - so that finding one absent proves it was excluded rather than missed:
 *
 * - Copybook and program names in a `COPY '<book>'` or `CALL '<program>'` operand.
 * - Program-name sentinels compared with `IF ... = 'DUMMY'`, which select the
 *   unavailable-option branch whose *message* is catalogued in
 *   {@link MESSAGE_TEMPLATES} even though the sentinel itself is not text.
 * - SQL `LIKE` wildcards composed into a query filter.
 * - Timestamp and date format patterns passed to a conversion routine.
 * - Operands of a `DISPLAY` statement, which in a CICS program writes to the
 *   system log rather than to a 3270 field, so nothing reaches the operator.
 *
 * WHY this class is documented rather than silently skipped. Assumptions: the
 * only way to audit "this module owns every user-visible string" is to re-run an
 * extraction and account for every literal it finds. Two of the three outcomes -
 * operator text and diagnostics - already have a home. Without this third list the
 * remainder looks like a shortfall, and the reviewer's only recourse is to open
 * each COBOL site by hand to discover that a `COPY` operand is not a sentence.
 *
 * Widths
 * ------
 * Four different widths govern one message on its way to a screen - a 75-byte
 * shared work area, a program-local 80- or 75-byte composition buffer, and a
 * 78- or 80-character display field depending on the mapset. {@link MESSAGE_BAND}
 * models all of them; do not assume a single number.
 * Not in this catalog
 * -------------------
 * The static text a BMS map paints onto a screen is NOT catalogued here, and no
 * entry below is sourced from a `.bms` file. That group is real and it is large,
 * so it is measured rather than waved at. Method, reproducible with one command:
 *
 * ```text
 * grep -ho "INITIAL='[^']*'" $(find app -name '*.bms')
 * ```
 *
 * Across the 21 mapsets (17 base under `app/bms` plus 4 extension mapsets) that
 * yields 665 occurrences of 213 distinct literals. 194 of the 213 contain at least
 * one alphanumeric character; the remaining 19 are blank or pure punctuation used
 * to rule a line. Of those 194, exactly 31 already appear as substrings of this
 * catalog - they are the title-band, menu-option and message text this module owns
 * and the map merely re-paints - which leaves **163 distinct literals, 344
 * occurrences, that this module does not hold**. The command above deliberately
 * matches only a literal that opens and closes on one physical line, which is why
 * its output is exact and repeatable; it therefore cannot see the further **18
 * literals that BMS continues across a line boundary** with a non-blank in column
 * 72 (for example `app/bms/COSGN00.bms` line 149, `app/bms/COTRN02.bms` line 279
 * and `app/bms/COUSR02.bms` line 163). None of those 18 is in this catalog either,
 * so the true size of the group is at least 181 distinct literals. Four kinds
 * account for all of them:
 *
 * - Static field labels, one per input or display field: `User ID     :`,
 *   `Password    :`, `Account Number    :`, `Merchant ID:`, `Amount:`.
 * - The four status-line prompt words every screen paints in its top two rows,
 *   `Tran:`, `Date:`, `Prog:`, `Time:` (20 occurrences each), together with the
 *   `mm/dd/yy` and `hh:mm:ss` placeholder patterns (21 and 20) and `AppID:` /
 *   `SysID:`.
 * - Function-key legends, painted as one literal per screen rather than assembled
 *   from parts: `ENTER=Sign-on  F3=Exit`, `ENTER=Process F3=Exit`,
 *   `ENTER=Continue  F3=Exit`. The longest of them are among the 18 continued
 *   literals, which is why a legend cannot be recovered reliably by grep alone.
 * - Pure decoration with no counterpart in a browser, chiefly the eight-line
 *   ASCII-art dollar note on `app/bms/COSGN00.bms`.
 *
 * Their single source of truth is the module that renders them, each of which the
 * migration plan sources from the mapsets directly: a screen's own field labels
 * belong to that screen under `ui/src/screens/**`; the four status-line prompts
 * belong to `ui/src/layout/ScreenHeader.tsx`; and the function-key legends belong
 * to `ui/src/layout/PfKeyBar.tsx`, which derives its key semantics from
 * `app/cpy/CSSTRPFY.cpy` rather than from the painted legend text. None of those
 * modules exists at this checkpoint; all three are authored at later indexes of
 * the same plan, so this section states where that text will live rather than
 * describing modules that hold it today.
 *
 * Alternatives Considered: the boundary is drawn here rather than by absorbing the
 * BMS text into this module. Absorbing it was the obvious alternative and
 * would make one module literally total. It was rejected on three grounds. First,
 * a field label is positional - it is meaningless apart from the field it sits
 * beside - so cataloguing it centrally separates it from the only thing that gives
 * it meaning and invites the label and the input to drift apart. Second, the
 * decoration has no target at all: an eight-line ASCII-art bank note exists
 * because a 24x80 character cell canvas had space to fill, and AAP gap G1 already
 * records that absolute character positioning is not reproduced, so transcribing
 * it would create catalog entries no component may render. Third, a legend such as
 * `ENTER=Sign-on  F3=Exit` is a RENDERING of the key bindings, not their
 * definition; the definition is `app/cpy/CSSTRPFY.cpy`, and duplicating the
 * rendered form here would create a second place for the two to disagree.
 * Trade-off accepted: no single module is total, so "which module owns this
 * string?" has to be answered by asking where the baseline holds it - a copybook
 * constant or program literal here, a `.bms` `INITIAL=` value in the renderer.
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
 * screen -- Assumptions: a downstream screen author works from a COBOL source
 * location, so a key must be derivable from "which program emitted this". Grouping
 * by screen would require knowing the program-to-route mapping, which lives in
 * `router.tsx`, to find a string at all.
 *
 * WHY no internationalisation library -- Alternatives Considered: `react-intl` and
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
 * Trade-offs: with this many entries, a comment per entry would be ~150 lines of
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
 * WHY `text` and `declaredWidth` are separate.
 * Assumptions: several baseline literals are shorter than the field they
 * initialise - both `CSMSG01Y` messages are 49 characters against `PIC X(50)` -
 * so COBOL pads the runtime field with trailing spaces that the source text does
 * not contain.
 * Trade-offs: storing the padded form would fabricate bytes that are not in the
 * source; storing only the text would lose the width contract a renderer needs.
 * Keeping both lets `padToDeclaredWidth` reproduce the runtime value on demand
 * while the transcription stays honest.
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

/**
 * A message the baseline sets by condition name rather than by moving a literal.
 *
 * The declaring programs hold these as `88` levels on a message field, so
 * `SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE` both selects the state *and* places
 * the text in the field. The condition name is therefore the identity of the
 * message, which is why {@link STATUS_MESSAGES} keys by it.
 *
 * WHY `field` is recorded alongside `declaredWidth`. Assumptions: the same
 * program declares conditions on two different fields at two different widths -
 * `WS-INFO-MSG` is `PIC X(40)` or `X(45)` and carries the guidance line, while
 * `WS-RETURN-MSG` / `WS-ERROR-MSG` is `PIC X(75)` and carries the error line. A
 * renderer that padded a 40-character guidance message to 75 would put it in the
 * wrong region, so the field name is kept as the discriminator rather than
 * inferred from the width.
 */
export interface StatusMessage {
  /** The literal exactly as the `88` level declares it, unpadded and untrimmed. */
  readonly text: string;
  /** COBOL data-name of the field the condition is declared on. */
  readonly field: string;
  /** Width of that field's `PIC X(n)` clause. */
  readonly declaredWidth: number;
  /** 1-based line of the `VALUE` literal in the declaring program. */
  readonly line: number;
}

/**
 * One part of a `STRING` composition: either a literal from the source, or a
 * runtime value the program inserts.
 *
 * WHY the delimiter is modelled rather than dropped. Assumptions: COBOL's
 * `DELIMITED BY` is not decoration - it decides how much of a sending field is
 * transferred. `DELIMITED BY SIZE` sends the whole field; `DELIMITED BY SPACE`
 * sends only the characters before the first space, which truncates a padded
 * 35-character menu name to its first word; `DELIMITED BY '  '` sends the
 * characters before the first double space, which trims the same field's padding
 * without truncating it. Two compositions in `COMEN01C` differ *only* in that
 * operand and therefore produce different text from the same input. A model
 * without the delimiter cannot reproduce either of them.
 */
export type MessageTemplatePart =
  | {
      /** Literal text, transferred whole, exactly as the source holds it. */
      readonly literal: string;
    }
  | {
      /** COBOL data-name of the value the program inserts at this position. */
      readonly value: string;
      /**
       * The statement's `DELIMITED BY` operand for this part: `'SIZE'` for the
       * whole field, `'SPACE'` for everything before the first space, or a literal
       * delimiter.
       *
       * WHY the third member is the two-space literal rather than `string`
       * Alternatives Considered: widening it to `string` was rejected because it
       * absorbs the two named operands, so the type would then permit any text and
       * describe none of it. Measured across the baseline, the message compositions
       * use exactly three delimiter forms - `SIZE`, `SPACE` and the literal `'  '`
       * in `COMEN01C` - so the union enumerates them and a fourth form has to be
       * added deliberately rather than arriving unnoticed.
       */
      readonly delimitedBy: "SIZE" | "SPACE" | "  ";
    };

/**
 * A message the baseline composes with a `STRING` statement, kept as ordered
 * parts rather than as a pre-joined sentence.
 */
export interface MessageTemplate {
  /** The parts in statement order; compose with {@link formatMessageTemplate}. */
  readonly parts: readonly MessageTemplatePart[];
  /** Where the composition and its literals are declared. */
  readonly source: SourceRef;
}

/**
 * The class of internal value a redacted baseline message carried to the screen.
 *
 * Recorded per entry so that a reader can see *what* was withheld without the
 * withheld text being present, and so the server-side logging requirement is
 * specific rather than general.
 *
 * WHY these are classification identifiers and not the vendor names they stand for
 * Trade-offs: the value of a register row is that it says what class of detail was
 * removed, and the class can be named without naming the utility, the cursor or the
 * table that produced it. The guarantee this module offers is therefore about its
 * **exported values**: no exported string in this module contains a cursor name, a
 * table name, or an `SQLCODE`, `SQLSTATE` or diagnostic-utility identifier, and that
 * holds in every build mode because it is a property of the data rather than of the
 * toolchain. The precise COBOL data-names appear only in comments, which the
 * production build's minifier strips - measured as zero occurrences in a minified
 * bundle - though an unminified development bundle retains some of them, which is
 * acceptable because a development bundle is not a shipped artifact.
 */
export type DiagnosticDetailKind =
  | "cics-response-and-reason"
  | "db2-cursor-name"
  | "db2-sqlcode"
  | "db2-sqlcode-and-sqlstate"
  | "db2-sqlcode-and-sqlerrm"
  | "db2-sqlcode-and-diagnostic-text"
  | "db2-table-name"
  | "ims-status-code"
  | "persistence-mechanism";

/**
 * A baseline message that is deliberately **not** exported as displayable text.
 *
 * Each entry is a register row for one intentional behavioural divergence: the
 * baseline showed an internal value to the operator, and the target shows
 * {@link RedactedDiagnostic.replacement} instead while the detail goes to a
 * server-side structured log. Provenance is retained in full so the original
 * wording remains findable in the baseline, which is the behavioural oracle.
 *
 * WHY the withheld text is not stored here even as a comment. Trade-offs: the
 * point of the redaction is that the message does not reach a browser bundle, and
 * a string literal reaches it in every build mode while a comment survives only in
 * an unminified one. Storing the text "for reference" would reintroduce exactly
 * what the entry exists to remove, and the cited lines already point at the
 * authoritative copy in the baseline, so nothing is lost by leaving it there.
 */
export interface RedactedDiagnostic {
  /** Program that composes the message, keyed into {@link PROGRAM_SOURCE_FILES}. */
  readonly program: ProgramName;
  /** Repository-relative file the composition lives in. */
  readonly file: string;
  /** 1-based lines of the composition in that file. */
  readonly lines: readonly number[];
  /** What the operator was doing when the baseline showed it. */
  readonly condition: string;
  /** The class of internal value the baseline appended. */
  readonly detail: DiagnosticDetailKind;
  /** The verbatim baseline message the target shows in its place. */
  readonly replacement: string;
}

/**
 * Declared width of every menu option name, from the `PIC X(35)` clause both menu
 * copybooks give `CDEMO-MENU-OPT-NAME` and `CDEMO-ADMIN-OPT-NAME`.
 *
 * WHY this is exported as its own constant as well as being carried on every entry
 * Trade-offs: a renderer that lays the menu out in a fixed-width column needs
 * the width once, not seventeen times, and a test that asserts the padding needs
 * something to assert against. Carrying it on the entries too makes the interface
 * refuse an option that has not declared its width, so a future entry cannot be
 * added with its padding undocumented.
 */
export const MENU_OPTION_DECLARED_WIDTH = 35 as const;

/** One selectable entry on the main menu, from `app/cpy/COMEN02Y.cpy`. */
export interface MainMenuOption {
  /** Value of `CDEMO-MENU-OPT-NUM`, the number the operator types. */
  readonly optionNumber: number;
  /** `CDEMO-MENU-OPT-NAME`, padded by the baseline to exactly 35 characters. */
  readonly name: string;
  /** Width of the `PIC X(35)` clause `name` is padded to. */
  readonly nameDeclaredWidth: typeof MENU_OPTION_DECLARED_WIDTH;
  /** `CDEMO-MENU-OPT-PGMNAME`; the transfer target, mapped to a route by the router. */
  readonly programName: string;
  /** `CDEMO-MENU-OPT-USRTYPE`: `'A'` restricts the option to administrators. */
  readonly userType: "A" | "U";
  /** Where the option name was read from. */
  readonly source: SourceRef;
}

/**
 * One selectable entry on the admin menu, from `app/cpy/COADM02Y.cpy`.
 *
 * WHY this has no `userType` while {@link MainMenuOption} does -- Assumptions: the
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
  /** Width of the `PIC X(35)` clause `name` is padded to. */
  readonly nameDeclaredWidth: typeof MENU_OPTION_DECLARED_WIDTH;
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
    text: "      AWS Mainframe Modernization       ",
    declaredWidth: 40,
    source: { file: "app/cpy/COTTL01Y.cpy", lines: [19] },
  },
  /**
   * `CCDA-TITLE02` - the application line of the title band.
   *
   * WHY the commented-out alternative on line 21 is not catalogued.
   * Assumptions: line 21 holds a disabled variant reading
   * "Credit Card Demo Application (CCDA)" which is *also* exactly 40 characters,
   * so it is indistinguishable from the live value by length or by shape. Only
   * the `*` in column 7 marks it dead.
   *
   * Refactoring Rationale: the same trap appears three more times in the baseline
   * - `COMEN02Y` line 69, `COADM02Y` line 21 and `COACTUPC` line 1614 - so the
   * extraction that produced this file discards any line whose column-7 indicator
   * is `*` or `/` before looking at its content, rather than filtering by content.
   */
  TITLE02: {
    text: "              CardDemo                  ",
    declaredWidth: 40,
    source: { file: "app/cpy/COTTL01Y.cpy", lines: [22] },
  },
  /**
   * `CCDA-THANK-YOU` - the sign-off line, which names the application "CCDA".
   *
   * WHY this is kept separate from {@link COMMON_MESSAGES.THANK_YOU}.
   * Alternatives Considered: the two look like one sentence stored
   * at two widths, and de-duplicating them - either directly or by comparing their
   * trimmed forms - is the obvious tidy-up. It loses text. This one says "CCDA";
   * the `CSMSG01Y` one says "CardDemo". Neither word appears in the other string,
   * so they are two different sentences and both are displayed. They are also 40
   * and 49 characters against declared widths of 40 and 50, so a width-based merge
   * would not reconcile them either.
   */
  THANK_YOU: {
    text: "Thank you for using CCDA application... ",
    declaredWidth: 40,
    source: { file: "app/cpy/COTTL01Y.cpy", lines: [24] },
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
    text: "Thank you for using CardDemo application...      ",
    declaredWidth: 50,
    source: { file: "app/cpy/CSMSG01Y.cpy", lines: [19] },
  },
  /** `CCDA-MSG-INVALID-KEY` - shown when an unmapped attention key is pressed. */
  INVALID_KEY: {
    text: "Invalid key pressed. Please see below...         ",
    declaredWidth: 50,
    source: { file: "app/cpy/CSMSG01Y.cpy", lines: [21] },
  },
} as const satisfies Record<string, FixedWidthText>;

/**
 * The abend surface from `app/cpy/CSMSG02Y.cpy`, expressed as field widths.
 *
 * WHY this group carries widths instead of text -- Assumptions: all four fields of
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
    field: "ABEND-CODE",
    declaredWidth: 4,
    source: { file: "app/cpy/CSMSG02Y.cpy", lines: [22, 23] },
  },
  {
    field: "ABEND-CULPRIT",
    declaredWidth: 8,
    source: { file: "app/cpy/CSMSG02Y.cpy", lines: [24, 25] },
  },
  {
    field: "ABEND-REASON",
    declaredWidth: 50,
    source: { file: "app/cpy/CSMSG02Y.cpy", lines: [26, 27] },
  },
  {
    field: "ABEND-MSG",
    declaredWidth: 72,
    source: { file: "app/cpy/CSMSG02Y.cpy", lines: [28, 29] },
  },
] as const satisfies readonly FixedWidthField[];

/**
 * Every width contract a message passes through on its way to the message band -
 * the single-line error and status region every screen carries.
 *
 * A band message is not governed by one width. It is composed in a program-local
 * buffer, may cross the pseudo-conversational boundary in a shared work area, and
 * is finally moved into the mapset's display field - and those three stages
 * declare different `PICTURE` clauses. All of them are recorded because a renderer
 * that truncates or pads to the wrong one changes what the operator sees.
 *
 * | Stage | Field | Width | Where |
 * |---|---|---|---|
 * | Program-local composition, 14 programs | `WS-MESSAGE` | 80 | e.g. `COSGN00C.cbl:38` |
 * | Program-local composition, 7 programs | `WS-RETURN-MSG`, or `WS-ERROR-MSG` in `COCRDLIC` | 75 | e.g. `COACTUPC.cbl:479` |
 * | Shared work area across the COMMAREA | `CCARD-ERROR-MSG`, `CCARD-RETURN-MSG` | 75 | `CVCRD01Y.cpy:28-29` |
 * | Display field, 19 of 21 mapsets | `ERRMSGI` / `ERRMSGO` | 78 | e.g. `COSGN00.CPY:84,152` |
 * | Display field, `COCRDSL` and `COCRDUP` | `ERRMSGI` / `ERRMSGO` | 80 | `COCRDSL.CPY:102,194` |
 *
 * The two composition buffers partition the catalogued programs cleanly: no
 * program declares both, 14 use the 80-byte form and 7 the 75-byte form, and the
 * only catalogued program declaring neither is `COPAUS2C`, which composes into a
 * 50-character `WS-FRD-ACT-MSG` that `COPAUS1C` then moves into `WS-MESSAGE`
 * (`COPAUS1C.cbl:257`) - an intermediate stage, not a fourth band width.
 *
 * WHY two adjacent screen regions are deliberately *not* modelled here
 * Assumptions: they are not the band, and folding them in would make this
 * constant mean "any text width", which is the ambiguity it exists to remove.
 * `COMEN01C` and `COADM01C` compose their menu lines into a 40-character
 * `WS-MENU-OPT-TXT` / `WS-ADMIN-OPT-TXT` and move them to the menu-row fields
 * `OPTN001O`-`OPTN012O` (`COMEN01C.cbl:48,276`), which is a list region; and
 * `COTRTLIC` sends an 800-character `WS-LONG-MSG` as a full-screen diagnostic
 * (`COTRTLIC.cbl:235,2087`), whose content is redacted and therefore never
 * rendered at all. Both are recorded so their absence reads as a decision.
 *
 * WHY all five are modelled rather than the work area alone (Refactoring
 * Rationale): the 75 of `CVCRD01Y` describes the *work area*, not the region a
 * message is rendered in. Measured across the baseline there are 38 `PIC X(78)`
 * `ERRMSGI`/`ERRMSGO` declarations spanning 19 mapsets and 4 `PIC X(80)`
 * declarations in `COCRDSL` and `COCRDUP`, and the `LENGTH=` operand of every
 * corresponding `DFHMDF` agrees. Treating 75 as the rendering constraint would
 * clip three characters from every screen and five from two of them; treating 80
 * as universal would let a message overrun the region on nineteen.
 *
 * WHY the 78 and 80 fields are wider than the 75-byte work area. Assumptions: the
 * extra characters are display slack, not data. A program moves a 75-byte work
 * area into a 78-character field and COBOL pads the remainder with spaces, so the
 * content limit stays 75 for any message that crosses the work area, while a
 * message composed straight into the 80-byte `WS-MESSAGE` buffer and moved
 * directly to the map can use 78 or 80. Both limits are real; which applies
 * depends on the program's own path, and {@link MESSAGE_BAND_BY_MAPSET} records
 * the display half of it per screen.
 *
 * Refactoring Rationale: this is attributed to `CVCRD01Y` and not `COCOM01Y` -- the
 * migration plan attributes the 75-character contract to `COCOM01Y.cpy`, but that
 * copybook declares no `PIC X(75)` field and in fact carries no message field at all -
 * it holds navigation, identity and selection context. The two 75-byte message fields
 * are `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` in `CVCRD01Y`, and that is what this
 * constant records.
 *
 * Assumptions: `emptySentinel` is recorded -- the baseline marks "no message"
 * with `88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES` - binary zeros, not spaces. A
 * band that treats an all-spaces value as "has a message" would render an empty
 * alert on screens that are simply quiet, so the distinction is carried across and
 * {@link isMessageBandEmpty} honours it.
 */
export const MESSAGE_BAND = {
  /**
   * Width of `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG`: the content limit for any
   * message that crosses the shared work area.
   */
  workAreaWidth: 75,
  /** Width of the `ERRMSGI`/`ERRMSGO` display field on 19 of the 21 mapsets. */
  displayWidthStandard: 78,
  /** Width of the same display field on `COCRDSL` and `COCRDUP` only. */
  displayWidthCardDetail: 80,
  /** Width of the `WS-MESSAGE` composition buffer used by 14 online programs. */
  compositionBufferWidthWide: 80,
  /**
   * Width of the narrower composition buffer used by the other 7 online programs:
   * `WS-RETURN-MSG` in six of them and `WS-ERROR-MSG` in `COCRDLIC`.
   */
  compositionBufferWidthNarrow: 75,
  /** The COBOL figurative constant that means "no message is set". */
  emptySentinel: "LOW-VALUES",
  /** Where each stage of the contract is declared. */
  sources: {
    /** The shared work area and its off-condition. */
    workArea: { file: "app/cpy/CVCRD01Y.cpy", lines: [28, 29, 30] },
    /** One representative 78-character display declaration, input and output. */
    displayStandard: { file: "app/cpy-bms/COSGN00.CPY", lines: [84, 152] },
    /** The 80-character display declarations, input and output. */
    displayCardDetail: { file: "app/cpy-bms/COCRDSL.CPY", lines: [102, 194] },
    /** One representative 80-character composition buffer. */
    compositionBufferWide: { file: "app/cbl/COSGN00C.cbl", lines: [38] },
    /** One representative 75-character composition buffer. */
    compositionBufferNarrow: { file: "app/cbl/COACTUPC.cbl", lines: [479] },
  },
} as const;

/**
 * The display-field width that governs each of the 21 mapsets, with the map name
 * the mapset defines.
 *
 * Keys are mapset names, which are also the base names of the `.bms` file and of
 * the symbolic-map copybook; `map` is the seven-character map name inside the
 * mapset, verified against the `DFHMDI` label in the corresponding `.bms`.
 *
 * WHY both names are carried. Assumptions: they are never the same string - all
 * 21 differ - and the difference follows two unrelated conventions. Fourteen drop
 * the mapset's trailing digit and append `A` (`COSGN00` defines `COSGN0A`); the
 * other seven drop the `O` of the `CO` prefix instead (`COACTUP` defines
 * `CACTUPA`, `COCRDLI` defines `CCRDLIA`). A reader who assumes one rule and
 * chases a file called `CACTUPA` will not find one, so the mapping is stored
 * rather than derived.
 *
 * WHY the exception list is two entries rather than a rule. Assumptions:
 * `COCRDSL` and `COCRDUP` are 80 and the other nineteen are 78, and nothing in the
 * baseline explains why - the two card screens simply declare a wider field. There
 * is no pattern to derive, so the table is exhaustive rather than computed.
 */
export const MESSAGE_BAND_BY_MAPSET = {
  COACTUP: { map: "CACTUPA", displayWidth: 78 },
  COACTVW: { map: "CACTVWA", displayWidth: 78 },
  COADM01: { map: "COADM1A", displayWidth: 78 },
  COBIL00: { map: "COBIL0A", displayWidth: 78 },
  COCRDLI: { map: "CCRDLIA", displayWidth: 78 },
  COCRDSL: { map: "CCRDSLA", displayWidth: 80 },
  COCRDUP: { map: "CCRDUPA", displayWidth: 80 },
  COMEN01: { map: "COMEN1A", displayWidth: 78 },
  COPAU00: { map: "COPAU0A", displayWidth: 78 },
  COPAU01: { map: "COPAU1A", displayWidth: 78 },
  CORPT00: { map: "CORPT0A", displayWidth: 78 },
  COSGN00: { map: "COSGN0A", displayWidth: 78 },
  COTRN00: { map: "COTRN0A", displayWidth: 78 },
  COTRN01: { map: "COTRN1A", displayWidth: 78 },
  COTRN02: { map: "COTRN2A", displayWidth: 78 },
  COTRTLI: { map: "CTRTLIA", displayWidth: 78 },
  COTRTUP: { map: "CTRTUPA", displayWidth: 78 },
  COUSR00: { map: "COUSR0A", displayWidth: 78 },
  COUSR01: { map: "COUSR1A", displayWidth: 78 },
  COUSR02: { map: "COUSR2A", displayWidth: 78 },
  COUSR03: { map: "COUSR3A", displayWidth: 78 },
} as const satisfies Record<
  string,
  { readonly map: string; readonly displayWidth: 78 | 80 }
>;

/** Name of a mapset whose message-band width this module records. */
export type MapsetName = keyof typeof MESSAGE_BAND_BY_MAPSET;

/**
 * Number of populated main-menu slots, from `CDEMO-MENU-OPT-COUNT`.
 *
 * WHY the count is exported alongside the array -- Assumptions: the baseline
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
 * WHY every entry is `userType: 'U'` -- Assumptions: the copybook sets
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
    name: "Account View                       ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COACTVWC",
    userType: "U",
    source: { file: "app/cpy/COMEN02Y.cpy", lines: [27] },
  },
  {
    optionNumber: 2,
    name: "Account Update                     ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COACTUPC",
    userType: "U",
    source: { file: "app/cpy/COMEN02Y.cpy", lines: [33] },
  },
  {
    optionNumber: 3,
    name: "Credit Card List                   ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COCRDLIC",
    userType: "U",
    source: { file: "app/cpy/COMEN02Y.cpy", lines: [39] },
  },
  {
    optionNumber: 4,
    name: "Credit Card View                   ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COCRDSLC",
    userType: "U",
    source: { file: "app/cpy/COMEN02Y.cpy", lines: [45] },
  },
  {
    optionNumber: 5,
    name: "Credit Card Update                 ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COCRDUPC",
    userType: "U",
    source: { file: "app/cpy/COMEN02Y.cpy", lines: [51] },
  },
  {
    optionNumber: 6,
    name: "Transaction List                   ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COTRN00C",
    userType: "U",
    source: { file: "app/cpy/COMEN02Y.cpy", lines: [57] },
  },
  {
    optionNumber: 7,
    name: "Transaction View                   ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COTRN01C",
    userType: "U",
    source: { file: "app/cpy/COMEN02Y.cpy", lines: [63] },
  },
  {
    optionNumber: 8,
    name: "Transaction Add                    ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COTRN02C",
    userType: "U",
    source: { file: "app/cpy/COMEN02Y.cpy", lines: [70] },
  },
  {
    optionNumber: 9,
    name: "Transaction Reports                ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "CORPT00C",
    userType: "U",
    source: { file: "app/cpy/COMEN02Y.cpy", lines: [76] },
  },
  {
    optionNumber: 10,
    name: "Bill Payment                       ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COBIL00C",
    userType: "U",
    source: { file: "app/cpy/COMEN02Y.cpy", lines: [82] },
  },
  {
    optionNumber: 11,
    name: "Pending Authorization View         ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COPAUS0C",
    userType: "U",
    source: { file: "app/cpy/COMEN02Y.cpy", lines: [88] },
  },
] as const satisfies readonly MainMenuOption[];

/**
 * Number of populated admin-menu slots, from `CDEMO-ADMIN-OPT-COUNT`.
 *
 * Assumptions: the value is 6 and not 4 because the copybook holds two
 * declarations of this field. The one at line 21 is commented out and says 4; the
 * live one at line 22 says 6. As with the main menu, `OCCURS 9` overstates the
 * populated slots and the count governs.
 *
 * Refactoring Rationale: the count was raised when the Db2 release added options
 * 5 and 6, which the surrounding "Option added for Db2 V1 release" markers at
 * lines 45 and 54 bracket, so 4 is the superseded value rather than a competing
 * one.
 */
export const ADMIN_MENU_OPTION_COUNT = 6 as const;

/**
 * The admin-menu options from `app/cpy/COADM02Y.cpy`, in display order. Each name
 * is padded by the baseline to exactly 35 characters and is stored that way.
 */
export const ADMIN_MENU_OPTIONS = [
  {
    optionNumber: 1,
    name: "User List (Security)               ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COUSR00C",
    source: { file: "app/cpy/COADM02Y.cpy", lines: [28] },
  },
  {
    optionNumber: 2,
    name: "User Add (Security)                ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COUSR01C",
    source: { file: "app/cpy/COADM02Y.cpy", lines: [33] },
  },
  {
    optionNumber: 3,
    name: "User Update (Security)             ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COUSR02C",
    source: { file: "app/cpy/COADM02Y.cpy", lines: [38] },
  },
  {
    optionNumber: 4,
    name: "User Delete (Security)             ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COUSR03C",
    source: { file: "app/cpy/COADM02Y.cpy", lines: [43] },
  },
  {
    optionNumber: 5,
    name: "Transaction Type List/Update (Db2) ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COTRTLIC",
    source: { file: "app/cpy/COADM02Y.cpy", lines: [48] },
  },
  {
    optionNumber: 6,
    name: "Transaction Type Maintenance (Db2) ",
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: "COTRTUPC",
    source: { file: "app/cpy/COADM02Y.cpy", lines: [52] },
  },
] as const satisfies readonly AdminMenuOption[];

/**
 * Repository-relative path of every online program this catalog transcribes,
 * keyed by program name.
 *
 * WHY the path is held once here instead of on each entry. Trade-offs: repeating
 * it on every entry - there are several hundred across the message, status and
 * template groups - would add no information and would let two entries for the same
 * program disagree. Provenance entries therefore carry only line numbers and resolve
 * their file through this map. A count is deliberately not quoted here: it would be
 * one more number to keep in step with the groups below, and it is derivable from
 * them.
 */
export const PROGRAM_SOURCE_FILES = {
  COACTUPC: "app/cbl/COACTUPC.cbl",
  COACTVWC: "app/cbl/COACTVWC.cbl",
  COADM01C: "app/cbl/COADM01C.cbl",
  COBIL00C: "app/cbl/COBIL00C.cbl",
  COCRDLIC: "app/cbl/COCRDLIC.cbl",
  COCRDSLC: "app/cbl/COCRDSLC.cbl",
  COCRDUPC: "app/cbl/COCRDUPC.cbl",
  COMEN01C: "app/cbl/COMEN01C.cbl",
  COPAUS0C: "app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl",
  COPAUS1C: "app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl",
  COPAUS2C: "app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl",
  CORPT00C: "app/cbl/CORPT00C.cbl",
  COSGN00C: "app/cbl/COSGN00C.cbl",
  COTRN00C: "app/cbl/COTRN00C.cbl",
  COTRN01C: "app/cbl/COTRN01C.cbl",
  COTRN02C: "app/cbl/COTRN02C.cbl",
  COTRTLIC: "app/app-transaction-type-db2/cbl/COTRTLIC.cbl",
  COTRTUPC: "app/app-transaction-type-db2/cbl/COTRTUPC.cbl",
  COUSR00C: "app/cbl/COUSR00C.cbl",
  COUSR01C: "app/cbl/COUSR01C.cbl",
  COUSR02C: "app/cbl/COUSR02C.cbl",
  COUSR03C: "app/cbl/COUSR03C.cbl",
} as const;

/** Name of an online program that contributes strings to this catalog. */
export type ProgramName = keyof typeof PROGRAM_SOURCE_FILES;

/**
 * Messages emitted verbatim by more than one program.
 *
 * WHY these are a group of their own rather than filed under one program
 * Trade-offs: the catalog is keyed by origin, and these strings have several
 * origins, so filing each under a single program would mean picking one
 * arbitrarily and leaving the other call sites pointing at a key that names the
 * wrong program. A shared group keeps the "keyed by origin" rule honest, and the
 * provenance below lists every site.
 *
 * WHY the five page-navigation messages are five entries and not one
 * Alternatives Considered: "already at the top", "already at the bottom", "at the
 * top", "reached the top" and "reached the bottom" are near-duplicates that look
 * like accidental drift, and folding them into one paging message would delete four
 * distinct sentences the operator actually sees at four different moments - two
 * describe a refused key press, three describe an arrival.
 */
export const SHARED_MESSAGES = {
  ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER:
    "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER",
  ACCOUNT_ID_NOT_FOUND: "Account ID NOT found...",
  CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER:
    "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER",
  FIRST_NAME_CAN_NOT_BE_EMPTY: "First Name can NOT be empty...",
  INVALID_SELECTION_VALID_VALUE_IS_S: "Invalid selection. Valid value is S",
  INVALID_VALUE_VALID_VALUES_ARE_Y_N:
    "Invalid value. Valid values are (Y/N)...",
  LAST_NAME_CAN_NOT_BE_EMPTY: "Last Name can NOT be empty...",
  PASSWORD_CAN_NOT_BE_EMPTY: "Password can NOT be empty...",
  /**
   * WHY this sits in the shared group rather than under `COTRTLIC` (Refactoring
   * Rationale): it was filed as COTRTLIC-only, but the transaction-type maintenance
   * program composes the same sentence at `COTRTUPC.cbl:1641`, and a group keyed by
   * one program cannot express a second file - the per-program index carries line
   * numbers and resolves its file from the group name. Filing it here keeps the
   * "keyed by origin" rule honest and lets both sites be cited; the key a consumer
   * uses is unchanged.
   */
  PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST:
    "Please delete associated child records first:",
  PLEASE_ENTER_A_VALID_OPTION_NUMBER: "Please enter a valid option number...",
  TRANSACTION_DESC: "Transaction Desc",
  TRANSACTION_ID_NOT_FOUND: "Transaction ID NOT found...",
  TRAN_ID_ALREADY_EXIST: "Tran ID already exist...",
  UNABLE_TO_LOOKUP_TRANSACTION: "Unable to lookup Transaction...",
  UNABLE_TO_LOOKUP_USER: "Unable to lookup User...",
  UNABLE_TO_UPDATE_USER: "Unable to Update User...",
  UNEXPECTED_ABEND_OCCURRED: "UNEXPECTED ABEND OCCURRED.",
  UNEXPECTED_DATA_SCENARIO: "UNEXPECTED DATA SCENARIO",
  USER_ID_CAN_NOT_BE_EMPTY: "User ID can NOT be empty...",
  USER_ID_NOT_FOUND: "User ID NOT found...",
  USER_TYPE_CAN_NOT_BE_EMPTY: "User Type can NOT be empty...",
  YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE:
    "You are already at the bottom of the page...",
  YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE:
    "You are already at the top of the page...",
  YOU_ARE_AT_THE_TOP_OF_THE_PAGE: "You are at the top of the page...",
  YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE:
    "You have reached the bottom of the page...",
  YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE:
    "You have reached the top of the page...",
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
  PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST: [
    { file: PROGRAM_SOURCE_FILES.COTRTLIC, lines: [1919] },
    { file: PROGRAM_SOURCE_FILES.COTRTUPC, lines: [1641] },
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
 * The 27 field labels the account-update program moves into
 * `WS-EDIT-VARIABLE-NAME` (`PIC X(25)`) before its shared edit routine composes a
 * validation message from them.
 *
 * Assumptions: these are catalogued even though they read like field names rather
 * than sentences, because they are not screen labels painted by a BMS map - they are
 * program literals that the edit routine concatenates with a suffix to build the text
 * the operator reads, so they are user-visible and belong here. Compose them with
 * {@link formatFieldValidationMessage}.
 *
 * Trade-offs: cataloguing a field name here costs a reader one indirection to find
 * text that reads like a label rather than a sentence. Leaving them out would push
 * them inline into the account-update screen and break the guarantee that this module
 * owns every displayed string, which is the more expensive of the two.
 *
 * WHY the group holds 27 entries when the migration inventory counts 23
 * Refactoring Rationale: the inventory was produced by a scan that only accepted
 * literals of six characters or more, which is a reasonable screen for finding
 * whole sentences but wrong for this class. Four live labels - `SSN`, `State`,
 * `Zip` and `City` - fall under that threshold and were dropped by it. This class
 * is defined by the field it targets, not by length, so all 27 live labels are
 * present; the 23 that clear six characters are still exactly the inventory's set.
 * A twenty-eighth label, `Address Line 2`, is commented out at line 1614 and is
 * excluded.
 *
 * Refactoring Rationale: this group is declared before {@link PROGRAM_MESSAGES} and
 * is the single definition of these literals. The two objects previously
 * held 23 of these labels as independent string literals, with nothing to keep them
 * equal - a corrected transcription in one would have left the other silently
 * wrong, and the byte-exactness invariant would then be true of one export and
 * false of the other. `PROGRAM_MESSAGES.COACTUPC` is now this object, so there is
 * one definition and one provenance index. The declaration therefore has to precede
 * `PROGRAM_MESSAGES`: a `const` referenced from an earlier initialiser would be a
 * temporal-dead-zone error at module load, not a compile-time warning.
 */
export const ACCOUNT_UPDATE_FIELD_LABELS = {
  ACCOUNT_STATUS: "Account Status",
  OPEN_DATE: "Open Date",
  CREDIT_LIMIT: "Credit Limit",
  EXPIRY_DATE: "Expiry Date",
  CASH_CREDIT_LIMIT: "Cash Credit Limit",
  REISSUE_DATE: "Reissue Date",
  CURRENT_BALANCE: "Current Balance",
  CURRENT_CYCLE_CREDIT_LIMIT: "Current Cycle Credit Limit",
  CURRENT_CYCLE_DEBIT_LIMIT: "Current Cycle Debit Limit",
  SSN: "SSN",
  DATE_OF_BIRTH: "Date of Birth",
  FICO_SCORE: "FICO Score",
  FIRST_NAME: "First Name",
  MIDDLE_NAME: "Middle Name",
  LAST_NAME: "Last Name",
  ADDRESS_LINE_1: "Address Line 1",
  STATE: "State",
  ZIP: "Zip",
  CITY: "City",
  COUNTRY: "Country",
  PHONE_NUMBER_1: "Phone Number 1",
  PHONE_NUMBER_2: "Phone Number 2",
  EFT_ACCOUNT_ID: "EFT Account Id",
  PRIMARY_CARD_HOLDER: "Primary Card Holder",
  SSN_FIRST_3_CHARS: "SSN: First 3 chars",
  /**
   * WHY the ampersand is a bare character. Assumptions: the baseline holds a plain
   * `&`, not an HTML entity. React escapes text children when rendering, so this
   * must stay `&` here; writing `&amp;` would display the entity literally.
   */
  SSN_4TH_AND_5TH_CHARS: "SSN 4th & 5th chars",
  SSN_LAST_4_CHARS: "SSN Last 4 chars",
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
 * Messages emitted by exactly one program, grouped under that program's name.
 *
 * Access is `PROGRAM_MESSAGES.<PROGRAM>.<KEY>`, so a screen author who knows which
 * COBOL program a screen replaces can reach its strings without searching this
 * file. Keys are derived mechanically from the text: upper-cased, with runs of
 * non-alphanumeric characters folded to a single underscore, `&` spelled `AND` and
 * `#` spelled `NUM`.
 */
export const PROGRAM_MESSAGES = {
  /**
   * account update - `app/cbl/COACTUPC.cbl` (27 field-label literals).
   *
   * This group *is* {@link ACCOUNT_UPDATE_FIELD_LABELS} rather than a copy of it;
   * see the rationale there. Accessing it through either name yields the same
   * object, so the account-update screen may use whichever reads better at the call
   * site without risking two different transcriptions of one literal.
   */
  COACTUPC: ACCOUNT_UPDATE_FIELD_LABELS,
  /** account view - `app/cbl/COACTVWC.cbl` (1 message). */
  COACTVWC: {
    /**
     * Assumptions: there are two spaces between "must" and "be" -- the baseline
     * literal contains a doubled space. Any whitespace-collapsing edit destroys it
     * silently - a global space-squeezing substitution, a reformat that rejoins the
     * string, an editor "trim whitespace" action - because the result still reads as
     * correct English.
     */
    ACCOUNT_FILTER_MUST_BE_A_NON_ZERO_11_DIGIT_NUMBER:
      "Account Filter must  be a non-zero 11 digit number",
  },
  /** bill payment - `app/cbl/COBIL00C.cbl` (10 messages). */
  COBIL00C: {
    ACCT_ID_CAN_NOT_BE_EMPTY: "Acct ID can NOT be empty...",
    YOU_HAVE_NOTHING_TO_PAY: "You have nothing to pay...",
    POS_TERM: "POS TERM",
    BILL_PAYMENT_ONLINE: "BILL PAYMENT - ONLINE",
    BILL_PAYMENT: "BILL PAYMENT",
    CONFIRM_TO_MAKE_A_BILL_PAYMENT: "Confirm to make a bill payment...",
    UNABLE_TO_LOOKUP_ACCOUNT: "Unable to lookup Account...",
    UNABLE_TO_UPDATE_ACCOUNT: "Unable to Update Account...",
    UNABLE_TO_LOOKUP_XREF_AIX_FILE: "Unable to lookup XREF AIX file...",
    UNABLE_TO_ADD_BILL_PAY_TRANSACTION: "Unable to Add Bill pay Transaction...",
  },
  /** credit-card list - `app/cbl/COCRDLIC.cbl` (3 messages). */
  COCRDLIC: {
    /**
     * WHY this survives alongside `COTRTLIC.NO_PREVIOUS_PAGES_TO_DISPLAY` (Alternatives
     * Considered): the two differ only in letter case. Any de-duplication that compares
     * case-insensitively would keep one and drop the other, changing what one of the two
     * screens renders.
     */
    NO_PREVIOUS_PAGES_TO_DISPLAY: "NO PREVIOUS PAGES TO DISPLAY",
    /**
     * WHY this is not merged with `COTRTLIC.NO_MORE_PAGES_TO_DISPLAY` (Alternatives
     * Considered): the two differ only in letter case, so a case-insensitive
     * de-duplication would collapse them. The card-list screen shows this upper-case
     * form and the transaction-type screen shows a mixed-case form; collapsing them
     * would change what one of the two screens renders.
     */
    NO_MORE_PAGES_TO_DISPLAY: "NO MORE PAGES TO DISPLAY",
    NO_MORE_RECORDS_TO_SHOW: "NO MORE RECORDS TO SHOW",
  },
  /** main menu - `app/cbl/COMEN01C.cbl` (1 message). */
  COMEN01C: {
    /**
     * WHY the trailing space is part of the value.
     * Assumptions: the COBOL literal ends with a space, and `router.tsx` compares
     * against this constant when it blocks a non-administrator, so the byte is load
     * bearing.
     * Alternatives Considered: trimming it reads as tidier and silently breaks that
     * comparison and the screen test that asserts the rendered text.
     */
    NO_ACCESS_ADMIN_ONLY_OPTION: "No access - Admin Only option... ",
  },
  /** pending-authorization summary - `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` (2 messages). */
  COPAUS0C: {
    PLEASE_ENTER_ACCT_ID: "Please enter Acct Id...",
    ACCT_ID_MUST_BE_NUMERIC: "Acct Id must be Numeric ...",
  },
  /** pending-authorization detail - `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` (3 messages). */
  COPAUS1C: {
    ALREADY_AT_THE_LAST_AUTHORIZATION: "Already at the last Authorization...",
    AUTH_FRAUD_REMOVED: "AUTH FRAUD REMOVED...",
    AUTH_MARKED_FRAUD: "AUTH MARKED FRAUD...",
  },
  /** authorization fraud marking - `app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl` (2 messages). */
  COPAUS2C: {
    ADD_SUCCESS: "ADD SUCCESS",
    UPDT_SUCCESS: "UPDT SUCCESS",
  },
  /** transaction reports - `app/cbl/CORPT00C.cbl` (19 messages). */
  CORPT00C: {
    MONTHLY: "Monthly",
    YEARLY: "Yearly",
    START_DATE_MONTH_CAN_NOT_BE_EMPTY: "Start Date - Month can NOT be empty...",
    START_DATE_DAY_CAN_NOT_BE_EMPTY: "Start Date - Day can NOT be empty...",
    START_DATE_YEAR_CAN_NOT_BE_EMPTY: "Start Date - Year can NOT be empty...",
    END_DATE_MONTH_CAN_NOT_BE_EMPTY: "End Date - Month can NOT be empty...",
    END_DATE_DAY_CAN_NOT_BE_EMPTY: "End Date - Day can NOT be empty...",
    END_DATE_YEAR_CAN_NOT_BE_EMPTY: "End Date - Year can NOT be empty...",
    START_DATE_NOT_A_VALID_MONTH: "Start Date - Not a valid Month...",
    START_DATE_NOT_A_VALID_DAY: "Start Date - Not a valid Day...",
    START_DATE_NOT_A_VALID_YEAR: "Start Date - Not a valid Year...",
    END_DATE_NOT_A_VALID_MONTH: "End Date - Not a valid Month...",
    END_DATE_NOT_A_VALID_DAY: "End Date - Not a valid Day...",
    END_DATE_NOT_A_VALID_YEAR: "End Date - Not a valid Year...",
    START_DATE_NOT_A_VALID_DATE: "Start Date - Not a valid date...",
    END_DATE_NOT_A_VALID_DATE: "End Date - Not a valid date...",
    CUSTOM: "Custom",
    SELECT_A_REPORT_TYPE_TO_PRINT_REPORT:
      "Select a report type to print report...",
    UNABLE_TO_WRITE_TDQ_JOBS: "Unable to Write TDQ (JOBS)...",
  },
  /** sign-on - `app/cbl/COSGN00C.cbl` (5 messages). */
  COSGN00C: {
    PLEASE_ENTER_USER_ID: "Please enter User ID ...",
    PLEASE_ENTER_PASSWORD: "Please enter Password ...",
    WRONG_PASSWORD_TRY_AGAIN: "Wrong Password. Try again ...",
    USER_NOT_FOUND_TRY_AGAIN: "User not found. Try again ...",
    UNABLE_TO_VERIFY_THE_USER: "Unable to verify the User ...",
  },
  /** transaction list - `app/cbl/COTRN00C.cbl` (2 messages). */
  COTRN00C: {
    TRAN_ID_MUST_BE_NUMERIC: "Tran ID must be Numeric ...",
    /**
     * WHY the lower-case "transaction" is preserved -- Assumptions: the shared
     * `SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION` spells it with a capital T at
     * its three sites, while this program spells it lower-case at all three of its
     * own. Normalising either way alters text that a golden-master comparison reads.
     */
    UNABLE_TO_LOOKUP_TRANSACTION: "Unable to lookup transaction...",
  },
  /** transaction view - `app/cbl/COTRN01C.cbl` (1 message). */
  COTRN01C: {
    TRAN_ID_CAN_NOT_BE_EMPTY: "Tran ID can NOT be empty...",
  },
  /** transaction add - `app/cbl/COTRN02C.cbl` (27 messages). */
  COTRN02C: {
    CONFIRM_TO_ADD_THIS_TRANSACTION: "Confirm to add this transaction...",
    ACCOUNT_ID_MUST_BE_NUMERIC: "Account ID must be Numeric...",
    CARD_NUMBER_MUST_BE_NUMERIC: "Card Number must be Numeric...",
    ACCOUNT_OR_CARD_NUMBER_MUST_BE_ENTERED:
      "Account or Card Number must be entered...",
    TYPE_CD_CAN_NOT_BE_EMPTY: "Type CD can NOT be empty...",
    CATEGORY_CD_CAN_NOT_BE_EMPTY: "Category CD can NOT be empty...",
    SOURCE_CAN_NOT_BE_EMPTY: "Source can NOT be empty...",
    DESCRIPTION_CAN_NOT_BE_EMPTY: "Description can NOT be empty...",
    AMOUNT_CAN_NOT_BE_EMPTY: "Amount can NOT be empty...",
    ORIG_DATE_CAN_NOT_BE_EMPTY: "Orig Date can NOT be empty...",
    PROC_DATE_CAN_NOT_BE_EMPTY: "Proc Date can NOT be empty...",
    MERCHANT_ID_CAN_NOT_BE_EMPTY: "Merchant ID can NOT be empty...",
    MERCHANT_NAME_CAN_NOT_BE_EMPTY: "Merchant Name can NOT be empty...",
    MERCHANT_CITY_CAN_NOT_BE_EMPTY: "Merchant City can NOT be empty...",
    MERCHANT_ZIP_CAN_NOT_BE_EMPTY: "Merchant Zip can NOT be empty...",
    TYPE_CD_MUST_BE_NUMERIC: "Type CD must be Numeric...",
    CATEGORY_CD_MUST_BE_NUMERIC: "Category CD must be Numeric...",
    AMOUNT_SHOULD_BE_IN_FORMAT_99999999_99:
      "Amount should be in format -99999999.99",
    ORIG_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD:
      "Orig Date should be in format YYYY-MM-DD",
    PROC_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD:
      "Proc Date should be in format YYYY-MM-DD",
    ORIG_DATE_NOT_A_VALID_DATE: "Orig Date - Not a valid date...",
    PROC_DATE_NOT_A_VALID_DATE: "Proc Date - Not a valid date...",
    MERCHANT_ID_MUST_BE_NUMERIC: "Merchant ID must be Numeric...",
    UNABLE_TO_LOOKUP_ACCT_IN_XREF_AIX_FILE:
      "Unable to lookup Acct in XREF AIX file...",
    CARD_NUMBER_NOT_FOUND: "Card Number NOT found...",
    /**
     * WHY the hash is left as-is -- Assumptions: it is a literal character in the baseline
     * standing for the word "number", not a substitution marker. Treating it as a
     * placeholder and interpolating a card number into it would invent text the mainframe
     * never displays - and would leak a card number into an error message.
     */
    UNABLE_TO_LOOKUP_CARD_NUM_IN_XREF_FILE:
      "Unable to lookup Card # in XREF file...",
    UNABLE_TO_ADD_TRANSACTION: "Unable to Add Transaction...",
  },
  /**
   * transaction-type list and update -
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` (5 messages exclusive to it).
   *
   * WHY this group holds 5 of the program's 16 message literals. Trade-offs: ten of
   * the other eleven exist to carry a Db2 diagnostic to the screen - three declared
   * cursor names, the physical `TRANSACTION_TYPE` table name, a fetch failure naming
   * a cursor, the word "Deadlock", and two sentence fragments that only complete once
   * an `SQLCODE` is appended. Under the audience policy in the module header those
   * are diagnostics, so they are registered in {@link REDACTED_DIAGNOSTICS} with the
   * verbatim baseline message the target shows instead, and none of their text is
   * exported to a browser bundle. The eleventh, the child-records instruction, is
   * operator text that a second program also emits, so it moved to
   * {@link SHARED_MESSAGES}. The five kept here name no schema object and stand as
   * complete sentences on their own.
   */
  COTRTLIC: {
    TYPE_CODE_FILTER_IF_SUPPLIED_MUST_BE_A_2_DIGIT_NUMBER:
      "TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER",
    NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS:
      "No Records found for these filter conditions",
    NO_PREVIOUS_PAGES_TO_DISPLAY: "No previous pages to display",
    NO_MORE_PAGES_TO_DISPLAY: "No more pages to display",
    /**
     * WHY there is a space on both sides of the question mark. Assumptions: both are
     * in the baseline literal. The trailing one separated this text from the `SQLCODE`
     * the baseline appended; the target appends nothing, and the space is still
     * transcribed because the invariant of this module is the source bytes, not the
     * bytes the target happens to need.
     */
    RECORD_NOT_FOUND_DELETED_BY_OTHERS:
      "Record not found. Deleted by others ? ",
  },
  /** transaction-type maintenance - `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` (1 message). */
  COTRTUPC: {
    TRAN_TYPE_CODE: "Tran Type code",
  },
  /** user list - `app/cbl/COUSR00C.cbl` (1 message). */
  COUSR00C: {
    INVALID_SELECTION_VALID_VALUES_ARE_U_AND_D:
      "Invalid selection. Valid values are U and D",
  },
  /** user add - `app/cbl/COUSR01C.cbl` (2 messages). */
  COUSR01C: {
    USER_ID_ALREADY_EXIST: "User ID already exist...",
    UNABLE_TO_ADD_USER: "Unable to Add User...",
  },
  /** user update - `app/cbl/COUSR02C.cbl` (2 messages). */
  COUSR02C: {
    PLEASE_MODIFY_TO_UPDATE: "Please modify to update ...",
    PRESS_PF5_KEY_TO_SAVE_YOUR_UPDATES:
      "Press PF5 key to save your updates ...",
  },
  /** user delete - `app/cbl/COUSR03C.cbl` (1 message). */
  COUSR03C: {
    PRESS_PF5_KEY_TO_DELETE_THIS_USER: "Press PF5 key to delete this user ...",
  },
} as const;

/**
 * Every baseline line that emits each {@link PROGRAM_MESSAGES} entry.
 *
 * The mapped `satisfies` type ties this index to {@link PROGRAM_MESSAGES} group by
 * group and key by key, so the two cannot drift apart without failing type-check.
 */
export const PROGRAM_MESSAGE_SOURCES = {
  /** Aliases {@link ACCOUNT_UPDATE_FIELD_LABEL_SOURCES}, for the reason given there. */
  COACTUPC: ACCOUNT_UPDATE_FIELD_LABEL_SOURCES,
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
    RECORD_NOT_FOUND_DELETED_BY_OTHERS: [1864],
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
 * pre-joined sentences -- Trade-offs: the baseline pairs labels and suffixes at run
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
  MUST_BE_SUPPLIED: " must be supplied.",
  MUST_BE_Y_OR_N: " must be Y or N.",
  CAN_HAVE_ALPHABETS_ONLY: " can have alphabets only.",
  CAN_HAVE_NUMBERS_OR_ALPHABETS_ONLY: " can have numbers or alphabets only.",
  MUST_BE_ALL_NUMERIC: " must be all numeric.",
  MUST_NOT_BE_ZERO: " must not be zero.",
  IS_NOT_VALID: " is not valid",
  AREA_CODE_MUST_BE_SUPPLIED: ": Area code must be supplied.",
  AREA_CODE_MUST_BE_A_3_DIGIT_NUMBER: ": Area code must be A 3 digit number.",
  AREA_CODE_CANNOT_BE_ZERO: ": Area code cannot be zero",
  NOT_VALID_NORTH_AMERICA_GENERAL_PURPOSE_AREA_CODE:
    ": Not valid North America general purpose area code",
  PREFIX_CODE_MUST_BE_SUPPLIED: ": Prefix code must be supplied.",
  PREFIX_CODE_MUST_BE_A_3_DIGIT_NUMBER:
    ": Prefix code must be A 3 digit number.",
  PREFIX_CODE_CANNOT_BE_ZERO: ": Prefix code cannot be zero",
  LINE_NUMBER_CODE_MUST_BE_SUPPLIED: ": Line number code must be supplied.",
  LINE_NUMBER_CODE_MUST_BE_A_4_DIGIT_NUMBER:
    ": Line number code must be A 4 digit number.",
  LINE_NUMBER_CODE_CANNOT_BE_ZERO: ": Line number code cannot be zero",
  SHOULD_NOT_BE_000_666_OR_BETWEEN_900_AND_999:
    ": should not be 000, 666, or between 900 and 999",
  IS_NOT_A_VALID_STATE_CODE: ": is not a valid state code",
  SHOULD_BE_BETWEEN_300_AND_850: ": should be between 300 and 850",
  MUST_BE_NUMERIC: " must be numeric.",
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
 * Messages the baseline holds as `88`-level condition names on a message field,
 * grouped by the program that declares them.
 *
 * Seven of the twenty-two online programs use this mechanism, and between them
 * they declare 121 message constants that no `MOVE '<literal>'` scan can see:
 * `SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE` places
 * `'Did not find this account in account master file'` in `WS-RETURN-MSG` in one
 * statement, because the condition's `VALUE` is the text.
 *
 * Alternatives Considered: this group is keyed by condition name while every other
 * group is keyed by text. A text-derived key was tried first, for
 * consistency with {@link PROGRAM_MESSAGES}, and it cannot express this class. Four
 * programs declare `SEARCHED-ACCT-ZEROES` and `SEARCHED-ACCT-NOT-NUMERIC` with
 * byte-identical text, so a text key collapses two distinct program states into one
 * entry and a screen reproducing the branch cannot say which it is reproducing;
 * `COTRTUPC` separately declares `'Invalid Key pressed. '` and
 * `'Invalid key pressed'`, which differ only in case and trailing punctuation and
 * therefore fold to one key. The condition name is what the program sets and what a
 * screen author reads in the source, so it is the identity that survives
 * translation.
 *
 * WHY the two `DID-NOT-FIND-ACCT-IN-CARDXREF` keys carry a line suffix
 * Assumptions: `COACTUPC` declares that condition name **twice** on the same
 * field, at lines 498 and 514, with different text - a defect in the immutable
 * baseline. Both literals are reachable text, so neither may be dropped, and a
 * single key cannot hold both; the declaration line disambiguates them and the
 * `line` field on each entry records which is which. The defect is not corrected
 * here because `app/**` is reference-only.
 */
export const STATUS_MESSAGES = {
  COACTUPC: {
    FOUND_ACCOUNT_DATA: {
      text: "Details of selected account shown above",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 467,
    },
    PROMPT_FOR_SEARCH_KEYS: {
      text: "Enter or update id of account to update",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 469,
    },
    PROMPT_FOR_CHANGES: {
      text: "Update account details presented above.",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 471,
    },
    PROMPT_FOR_CONFIRMATION: {
      text: "Changes validated.Press F5 to save",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 473,
    },
    CONFIRM_UPDATE_SUCCESS: {
      text: "Changes committed to database",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 475,
    },
    INFORM_FAILURE: {
      text: "Changes unsuccessful. Please try again",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 477,
    },
    WS_EXIT_MESSAGE: {
      text: "PF03 pressed.Exiting              ",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 482,
    },
    WS_PROMPT_FOR_ACCT: {
      text: "Account number not provided",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 484,
    },
    WS_PROMPT_FOR_LASTNAME: {
      text: "Last name not provided",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 486,
    },
    WS_NAME_MUST_BE_ALPHA: {
      text: "Name can only contain alphabets and spaces",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 488,
    },
    NO_SEARCH_CRITERIA_RECEIVED: {
      text: "No input received",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 490,
    },
    NO_CHANGES_DETECTED: {
      text: "No change detected with respect to values fetched.",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 492,
    },
    SEARCHED_ACCT_ZEROES: {
      text: "Account number must be a non zero 11 digit number",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 494,
    },
    SEARCHED_ACCT_NOT_NUMERIC: {
      text: "Account number must be a non zero 11 digit number",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 496,
    },
    DID_NOT_FIND_ACCT_IN_CARDXREF__L498: {
      text: "Did not find this account in account card xref file",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 498,
    },
    DID_NOT_FIND_ACCT_IN_ACCTDAT: {
      text: "Did not find this account in account master file",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 500,
    },
    DID_NOT_FIND_CUST_IN_CUSTDAT: {
      text: "Did not find associated customer in master file",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 502,
    },
    ACCT_STATUS_MUST_BE_YES_NO: {
      text: "Account Active Status must be Y or N",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 504,
    },
    CRED_LIMIT_IS_BLANK: {
      text: "Credit Limit must be supplied",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 506,
    },
    CRED_LIMIT_IS_NOT_VALID: {
      text: "Credit Limit is not valid",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 508,
    },
    THIS_MONTH_NOT_VALID: {
      text: "Card expiry month must be between 1 and 12",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 510,
    },
    THIS_YEAR_NOT_VALID: {
      text: "Invalid card expiry year",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 512,
    },
    DID_NOT_FIND_ACCT_IN_CARDXREF__L514: {
      text: "Did not find this account in cards database",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 514,
    },
    DID_NOT_FIND_ACCTCARD_COMBO: {
      text: "Did not find cards for this search condition",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 516,
    },
    COULD_NOT_LOCK_ACCT_FOR_UPDATE: {
      text: "Could not lock account record for update",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 518,
    },
    COULD_NOT_LOCK_CUST_FOR_UPDATE: {
      text: "Could not lock customer record for update",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 520,
    },
    DATA_WAS_CHANGED_BEFORE_UPDATE: {
      text: "Record changed by some one else. Please review",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 522,
    },
    LOCKED_BUT_UPDATE_FAILED: {
      text: "Update of record failed",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 524,
    },
    XREF_READ_ERROR: {
      text: "Error reading Card Data File",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 526,
    },
    CODING_TO_BE_DONE: {
      text: "Looks Good.... so far",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 528,
    },
  },
  COACTVWC: {
    WS_PROMPT_FOR_INPUT: {
      text: "Enter or update id of account to display",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 114,
    },
    WS_INFORM_OUTPUT: {
      text: "Displaying details of given Account",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 116,
    },
    WS_EXIT_MESSAGE: {
      text: "PF03 pressed.Exiting              ",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 120,
    },
    WS_PROMPT_FOR_ACCT: {
      text: "Account number not provided",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 122,
    },
    NO_SEARCH_CRITERIA_RECEIVED: {
      text: "No input received",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 124,
    },
    SEARCHED_ACCT_ZEROES: {
      text: "Account number must be a non zero 11 digit number",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 126,
    },
    SEARCHED_ACCT_NOT_NUMERIC: {
      text: "Account number must be a non zero 11 digit number",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 128,
    },
    DID_NOT_FIND_ACCT_IN_CARDXREF: {
      text: "Did not find this account in account card xref file",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 130,
    },
    DID_NOT_FIND_ACCT_IN_ACCTDAT: {
      text: "Did not find this account in account master file",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 132,
    },
    DID_NOT_FIND_CUST_IN_CUSTDAT: {
      text: "Did not find associated customer in master file",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 134,
    },
    XREF_READ_ERROR: {
      text: "Error reading account card xref File",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 136,
    },
    CODING_TO_BE_DONE: {
      text: "Looks Good.... so far",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 138,
    },
  },
  COCRDLIC: {
    WS_INFORM_REC_ACTIONS: {
      text: "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD",
      field: "WS-INFO-MSG",
      declaredWidth: 45,
      line: 116,
    },
    WS_EXIT_MESSAGE: {
      text: "PF03 PRESSED.EXITING",
      field: "WS-ERROR-MSG",
      declaredWidth: 75,
      line: 120,
    },
    WS_NO_RECORDS_FOUND: {
      text: "NO RECORDS FOUND FOR THIS SEARCH CONDITION.",
      field: "WS-ERROR-MSG",
      declaredWidth: 75,
      line: 122,
    },
    WS_MORE_THAN_1_ACTION: {
      text: "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE",
      field: "WS-ERROR-MSG",
      declaredWidth: 75,
      line: 124,
    },
    WS_INVALID_ACTION_CODE: {
      text: "INVALID ACTION CODE",
      field: "WS-ERROR-MSG",
      declaredWidth: 75,
      line: 126,
    },
  },
  COCRDSLC: {
    FOUND_CARDS_FOR_ACCOUNT: {
      text: "   Displaying requested details",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 130,
    },
    WS_PROMPT_FOR_INPUT: {
      text: "Please enter Account and Card Number",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 132,
    },
    WS_EXIT_MESSAGE: {
      text: "PF03 pressed.Exiting              ",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 137,
    },
    WS_PROMPT_FOR_ACCT: {
      text: "Account number not provided",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 139,
    },
    WS_PROMPT_FOR_CARD: {
      text: "Card number not provided",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 141,
    },
    NO_SEARCH_CRITERIA_RECEIVED: {
      text: "No input received",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 143,
    },
    SEARCHED_ACCT_ZEROES: {
      text: "Account number must be a non zero 11 digit number",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 145,
    },
    SEARCHED_ACCT_NOT_NUMERIC: {
      text: "Account number must be a non zero 11 digit number",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 147,
    },
    SEARCHED_CARD_NOT_NUMERIC: {
      text: "Card number if supplied must be a 16 digit number",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 149,
    },
    DID_NOT_FIND_ACCT_IN_CARDXREF: {
      text: "Did not find this account in cards database",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 152,
    },
    DID_NOT_FIND_ACCTCARD_COMBO: {
      text: "Did not find cards for this search condition",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 154,
    },
    XREF_READ_ERROR: {
      text: "Error reading Card Data File",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 156,
    },
    CODING_TO_BE_DONE: {
      text: "Looks Good.... so far",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 158,
    },
  },
  COCRDUPC: {
    FOUND_CARDS_FOR_ACCOUNT: {
      text: "Details of selected card shown above",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 161,
    },
    PROMPT_FOR_SEARCH_KEYS: {
      text: "Please enter Account and Card Number",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 163,
    },
    PROMPT_FOR_CHANGES: {
      text: "Update card details presented above.",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 165,
    },
    PROMPT_FOR_CONFIRMATION: {
      text: "Changes validated.Press F5 to save",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 167,
    },
    CONFIRM_UPDATE_SUCCESS: {
      text: "Changes committed to database",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 169,
    },
    INFORM_FAILURE: {
      text: "Changes unsuccessful. Please try again",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 171,
    },
    WS_EXIT_MESSAGE: {
      text: "PF03 pressed.Exiting              ",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 176,
    },
    WS_PROMPT_FOR_ACCT: {
      text: "Account number not provided",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 178,
    },
    WS_PROMPT_FOR_CARD: {
      text: "Card number not provided",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 180,
    },
    WS_PROMPT_FOR_NAME: {
      text: "Card name not provided",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 182,
    },
    WS_NAME_MUST_BE_ALPHA: {
      text: "Card name can only contain alphabets and spaces",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 184,
    },
    NO_SEARCH_CRITERIA_RECEIVED: {
      text: "No input received",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 186,
    },
    NO_CHANGES_DETECTED: {
      text: "No change detected with respect to values fetched.",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 188,
    },
    SEARCHED_ACCT_ZEROES: {
      text: "Account number must be a non zero 11 digit number",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 190,
    },
    SEARCHED_ACCT_NOT_NUMERIC: {
      text: "Account number must be a non zero 11 digit number",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 192,
    },
    SEARCHED_CARD_NOT_NUMERIC: {
      text: "Card number if supplied must be a 16 digit number",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 194,
    },
    CARD_STATUS_MUST_BE_YES_NO: {
      text: "Card Active Status must be Y or N",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 196,
    },
    CARD_EXPIRY_MONTH_NOT_VALID: {
      text: "Card expiry month must be between 1 and 12",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 198,
    },
    CARD_EXPIRY_YEAR_NOT_VALID: {
      text: "Invalid card expiry year",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 200,
    },
    DID_NOT_FIND_ACCT_IN_CARDXREF: {
      text: "Did not find this account in cards database",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 202,
    },
    DID_NOT_FIND_ACCTCARD_COMBO: {
      text: "Did not find cards for this search condition",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 204,
    },
    COULD_NOT_LOCK_FOR_UPDATE: {
      text: "Could not lock record for update",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 206,
    },
    DATA_WAS_CHANGED_BEFORE_UPDATE: {
      text: "Record changed by some one else. Please review",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 208,
    },
    LOCKED_BUT_UPDATE_FAILED: {
      text: "Update of record failed",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 210,
    },
    XREF_READ_ERROR: {
      text: "Error reading Card Data File",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 212,
    },
    CODING_TO_BE_DONE: {
      text: "Looks Good.... so far",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 214,
    },
  },
  COTRTLIC: {
    WS_INFORM_REC_ACTIONS: {
      text: "Type U to update, D to delete any record",
      field: "WS-INFO-MSG",
      declaredWidth: 45,
      line: 240,
    },
    WS_INFORM_DELETE: {
      text: "Delete HIGHLIGHTED row ? Press F10 to confirm",
      field: "WS-INFO-MSG",
      declaredWidth: 45,
      line: 242,
    },
    WS_INFORM_UPDATE: {
      text: "Update HIGHLIGHTED row. Press F10 to save",
      field: "WS-INFO-MSG",
      declaredWidth: 45,
      line: 244,
    },
    WS_INFORM_DELETE_SUCCESS: {
      text: "HIGHLIGHTED row deleted.Hit Enter to continue",
      field: "WS-INFO-MSG",
      declaredWidth: 45,
      line: 246,
    },
    WS_INFORM_UPDATE_SUCCESS: {
      text: "HIGHLIGHTED row was updated",
      field: "WS-INFO-MSG",
      declaredWidth: 45,
      line: 248,
    },
    WS_EXIT_MESSAGE: {
      text: "PF03 pressed. Exiting",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 252,
    },
    WS_MESG_NO_RECORDS_FOUND: {
      text: "No records found for this search condition.",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 254,
    },
    WS_MESG_NO_MORE_RECORDS: {
      text: "No more pages for these search conditions",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 256,
    },
    WS_MESG_MORE_THAN_1_ACTION: {
      text: "Please select only 1 action",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 258,
    },
    WS_MESG_INVALID_ACTION_CODE: {
      text: "Action code selected is invalid",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 260,
    },
    WS_MESG_NO_CHANGES_DETECTED: {
      text: "No change detected with respect to database values.",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 262,
    },
  },
  COTRTUPC: {
    FOUND_TRANTYPE_DATA: {
      text: "Selected transaction type shown above",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 146,
    },
    PROMPT_FOR_SEARCH_KEYS: {
      text: "Enter transaction type to be maintained",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 148,
    },
    PROMPT_CREATE_NEW_RECORD: {
      text: "Press F05 to add. F12 to cancel",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 150,
    },
    PROMPT_DELETE_CONFIRM: {
      text: "Delete this record ? Press F4 to confirm",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 152,
    },
    CONFIRM_DELETE_SUCCESS: {
      text: "Delete successful.",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 154,
    },
    PROMPT_FOR_CHANGES: {
      text: "Update transaction type details shown.",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 156,
    },
    PROMPT_FOR_NEWDATA: {
      text: "Enter new transaction type details.",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 158,
    },
    PROMPT_FOR_CONFIRMATION: {
      text: "Changes validated.Press F5 to save",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 161,
    },
    CONFIRM_UPDATE_SUCCESS: {
      text: "Changes committed to database",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 163,
    },
    INFORM_FAILURE: {
      text: "Changes unsuccessful",
      field: "WS-INFO-MSG",
      declaredWidth: 40,
      line: 165,
    },
    WS_EXIT_MESSAGE: {
      text: "PF03 pressed.Exiting              ",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 170,
    },
    WS_INVALID_KEY: {
      text: "Invalid Key pressed. ",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 172,
    },
    WS_NAME_MUST_BE_ALPHA: {
      text: "Name can only contain alphabets and spaces",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 174,
    },
    WS_RECORD_NOT_FOUND: {
      text: "No record found for this key in database",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 176,
    },
    NO_SEARCH_CRITERIA_RECEIVED: {
      text: "No input received",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 178,
    },
    NO_CHANGES_DETECTED: {
      text: "No change detected with respect to values fetched.",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 180,
    },
    COULD_NOT_LOCK_REC_FOR_UPDATE: {
      text: "Could not lock record for update",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 182,
    },
    DATA_WAS_CHANGED_BEFORE_UPDATE: {
      text: "Record changed by some one else. Please review",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 184,
    },
    WS_UPDATE_WAS_CANCELLED: {
      text: "Update was cancelled",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 186,
    },
    TABLE_UPDATE_FAILED: {
      text: "Update of record failed",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 188,
    },
    RECORD_DELETE_FAILED: {
      text: "Delete of record failed",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 190,
    },
    WS_DELETE_WAS_CANCELLED: {
      text: "Delete was cancelled",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 192,
    },
    WS_INVALID_KEY_PRESSED: {
      text: "Invalid key pressed",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 194,
    },
    CODING_TO_BE_DONE: {
      text: "Looks Good.... so far",
      field: "WS-RETURN-MSG",
      declaredWidth: 75,
      line: 196,
    },
  },
} as const satisfies Partial<
  Record<ProgramName, Record<string, StatusMessage>>
>;

/**
 * Messages the baseline composes with a `STRING` statement, kept as ordered parts.
 *
 * These are the sixteen operator-facing `STRING` statements across nine programs,
 * held as fifteen entries because `COADM01C` composes its unavailable-option
 * message identically at two sites. The
 * label-plus-suffix family that `COACTUPC`, `COTRTLIC` and `COTRTUPC` build from
 * {@link ACCOUNT_UPDATE_FIELD_LABELS} and {@link FIELD_VALIDATION_SUFFIXES} is
 * modelled by those two objects instead, and the compositions whose payload is a
 * machine-level status value are registered in {@link REDACTED_DIAGNOSTICS}.
 * Compose an entry with {@link formatMessageTemplate}.
 *
 * WHY these are parts rather than finished sentences. Trade-offs: each one
 * interleaves source literals with a record value - a transaction id, a user id, a
 * menu option name - so there is no single string to store. Storing a printf-style
 * pattern instead was rejected because the `DELIMITED BY` operand differs per part
 * and a pattern has nowhere to put it, and because a pattern invents punctuation
 * that the source expresses as separate literals.
 */
export const MESSAGE_TEMPLATES = {
  /**
   * `COACTUPC` account-filter rejection.
   *
   * WHY two literals rather than one. Assumptions: the baseline splits the
   * sentence across two literals whose join has no space -
   * `'...must be a 11 digit'` then `' Non-Zero Number'` - so the leading space of
   * the second literal is the word break. Merging them by hand would work here and
   * would hide that the join is load bearing.
   */
  ACCOUNT_NUMBER_MUST_BE_11_DIGIT_NON_ZERO: {
    parts: [
      { literal: "Account Number if supplied must be a 11 digit" },
      { literal: " Non-Zero Number" },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [1807, 1808] },
  },
  /** `COACTUPC` state-and-ZIP cross-check rejection; one literal, no inserted value. */
  INVALID_ZIP_CODE_FOR_STATE: {
    parts: [{ literal: "Invalid zip code for state" }],
    source: { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2550] },
  },
  /**
   * `COADM01C` unavailable-option message, composed at two sites.
   *
   * WHY no option name is inserted. Assumptions: the two lines that would insert
   * `CDEMO-ADMIN-OPT-NAME` are commented out at both sites - `COADM01C.cbl:153-154`
   * and `:274-275` - so the admin menu says "This option is not installed ..."
   * without naming the option, while the main menu names it. That asymmetry is
   * baseline behaviour, not an omission here.
   */
  ADMIN_OPTION_NOT_INSTALLED: {
    parts: [{ literal: "This option " }, { literal: "is not installed ..." }],
    source: {
      file: PROGRAM_SOURCE_FILES.COADM01C,
      lines: [152, 155, 273, 276],
    },
  },
  /**
   * `COADM01C` admin-menu line: option number, separator, option name.
   *
   * WHY this is not a message-band string. Assumptions: the baseline composes it
   * into the 40-character `WS-ADMIN-OPT-TXT` and moves it to a menu-row field, not
   * to `ERRMSGO`, so {@link MESSAGE_BAND} does not govern it and padding it to 78
   * would be wrong. See the note on {@link MESSAGE_BAND} for why that region is
   * modelled separately.
   */
  ADMIN_MENU_OPTION_LINE: {
    parts: [
      { value: "CDEMO-ADMIN-OPT-NUM", delimitedBy: "SIZE" },
      { literal: ". " },
      { value: "CDEMO-ADMIN-OPT-NAME", delimitedBy: "SIZE" },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COADM01C, lines: [236, 237, 238] },
  },
  /**
   * `COMEN01C` unavailable-option message.
   *
   * WHY the option name is delimited by a double space. Assumptions: the name is a
   * 35-character padded field, and `DELIMITED BY '  '` stops at the first double
   * space, which strips the padding without cutting the name. This is the one place
   * in the baseline where that operand appears, and it is what makes this message
   * read correctly while the coming-soon message below does not.
   */
  MENU_OPTION_NOT_INSTALLED: {
    parts: [
      { literal: "This option " },
      { value: "CDEMO-MENU-OPT-NAME", delimitedBy: "  " },
      { literal: " is not installed..." },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COMEN01C, lines: [163, 165, 166] },
  },
  /**
   * `COMEN01C` coming-soon message, shown for an option whose target program name
   * begins `DUMMY`.
   *
   * WHY this one truncates the option name. Assumptions: it delimits by a single
   * space, so `'Account View                       '` contributes only `'Account'`,
   * and the next literal has no leading space - the rendered text is
   * "This option Accountis coming soon ...". That is a defect in the immutable
   * baseline, reproduced rather than corrected because `app/**` is the behavioural
   * oracle; the neighbouring not-installed message proves the author knew the
   * double-space form.
   */
  MENU_OPTION_COMING_SOON: {
    parts: [
      { literal: "This option " },
      { value: "CDEMO-MENU-OPT-NAME", delimitedBy: "SPACE" },
      { literal: "is coming soon ..." },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COMEN01C, lines: [172, 174, 175] },
  },
  /**
   * `COMEN01C` main-menu line: option number, separator, option name.
   *
   * WHY this is not a message-band string. Assumptions: the baseline composes it
   * into the 40-character `WS-MENU-OPT-TXT` and moves it to one of the menu-row
   * fields `OPTN001O`-`OPTN012O`, not to `ERRMSGO`. The two unavailable-option
   * messages above *do* reach the band, because those go to `WS-MESSAGE` instead -
   * so the same program feeds two different regions and only one of them is
   * governed by {@link MESSAGE_BAND}.
   */
  MENU_OPTION_LINE: {
    parts: [
      { value: "CDEMO-MENU-OPT-NUM", delimitedBy: "SIZE" },
      { literal: ". " },
      { value: "CDEMO-MENU-OPT-NAME", delimitedBy: "SIZE" },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COMEN01C, lines: [269, 270, 271] },
  },
  /** `CORPT00C` acknowledgement after the report job is submitted. */
  REPORT_SUBMITTED_FOR_PRINTING: {
    parts: [
      { value: "WS-REPORT-NAME", delimitedBy: "SPACE" },
      { literal: " report submitted for printing ..." },
    ],
    source: { file: PROGRAM_SOURCE_FILES.CORPT00C, lines: [449, 450] },
  },
  /** `CORPT00C` confirmation prompt before the report job is submitted. */
  PLEASE_CONFIRM_TO_PRINT_REPORT: {
    parts: [
      { literal: "Please confirm to print the " },
      { value: "WS-REPORT-NAME", delimitedBy: "SPACE" },
      { literal: " report..." },
    ],
    source: { file: PROGRAM_SOURCE_FILES.CORPT00C, lines: [466, 468, 469] },
  },
  /**
   * `CORPT00C` rejection of an unrecognised confirmation keystroke.
   *
   * The quoted value is the character the operator typed, echoed back between two
   * double-quote literals.
   */
  NOT_A_VALID_VALUE_TO_CONFIRM: {
    parts: [
      { literal: '"' },
      { value: "CONFIRMI", delimitedBy: "SPACE" },
      { literal: '" is not a valid value to confirm...' },
    ],
    source: { file: PROGRAM_SOURCE_FILES.CORPT00C, lines: [486, 487, 488] },
  },
  /**
   * `COBIL00C` bill-payment success message.
   *
   * WHY the doubled space survives. Assumptions: the first literal ends with a
   * space and the second begins with one, so the rendered text reads
   * "Payment successful.  Your Transaction ID is ...". Both spaces are in the
   * source and neither is removed.
   */
  PAYMENT_SUCCESSFUL: {
    parts: [
      { literal: "Payment successful. " },
      { literal: " Your Transaction ID is " },
      { value: "TRAN-ID", delimitedBy: "SPACE" },
      { literal: "." },
    ],
    source: {
      file: PROGRAM_SOURCE_FILES.COBIL00C,
      lines: [527, 528, 529, 530],
    },
  },
  /**
   * `COTRN02C` transaction-added success message.
   *
   * The same doubled-space join as the bill-payment message, and a different
   * spelling of the identifier - "Tran ID" here against "Transaction ID" there.
   * Both are transcribed as they are.
   */
  TRANSACTION_ADDED_SUCCESSFULLY: {
    parts: [
      { literal: "Transaction added successfully. " },
      { literal: " Your Tran ID is " },
      { value: "TRAN-ID", delimitedBy: "SPACE" },
      { literal: "." },
    ],
    source: {
      file: PROGRAM_SOURCE_FILES.COTRN02C,
      lines: [728, 730, 731, 732],
    },
  },
  /** `COUSR01C` user-added success message. */
  USER_HAS_BEEN_ADDED: {
    parts: [
      { literal: "User " },
      { value: "SEC-USR-ID", delimitedBy: "SPACE" },
      { literal: " has been added ..." },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COUSR01C, lines: [255, 256, 257] },
  },
  /** `COUSR02C` user-updated success message. */
  USER_HAS_BEEN_UPDATED: {
    parts: [
      { literal: "User " },
      { value: "SEC-USR-ID", delimitedBy: "SPACE" },
      { literal: " has been updated ..." },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COUSR02C, lines: [372, 373, 374] },
  },
  /** `COUSR03C` user-deleted success message. */
  USER_HAS_BEEN_DELETED: {
    parts: [
      { literal: "User " },
      { value: "SEC-USR-ID", delimitedBy: "SPACE" },
      { literal: " has been deleted ..." },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COUSR03C, lines: [318, 319, 320] },
  },
} as const satisfies Record<string, MessageTemplate>;

/**
 * The register of baseline messages the target deliberately does not display.
 *
 * Each row is one intentional behavioural divergence, recorded so the change is
 * auditable rather than invisible: the baseline put an internal value on the
 * screen, and the target shows `replacement` - itself a verbatim baseline string -
 * while the withheld detail goes to a server-side structured log keyed by the
 * correlation identifier. The audience rule that decides membership is stated in
 * the module header.
 *
 * The register holds 35 rows citing 44 baseline sites - 31 of them inside a
 * `STRING` statement and 13 inside a `MOVE` - drawn from seven programs and eight
 * source files. Every figure here is derivable from the rows themselves by
 * counting them, so it can be re-checked rather than trusted.
 *
 * WHY a register exists at all rather than the diagnostics simply being absent
 * Trade-offs: an omission is indistinguishable from an oversight. Those 44 sites
 * are missing from the displayable catalog on purpose, and without this list the
 * next author would either reinstate them - reintroducing the disclosure - or
 * conclude the extraction was incomplete and re-run it. The rows carry provenance
 * and no withheld text, so the baseline copy stays findable while the bundle stays
 * clean.
 *
 * WHY the `COTRTLIC` rows point at `CSDB2RPY.cpy` for their composition
 * Assumptions: the ten redacted `COTRTLIC` literals are action labels moved into
 * `WS-DB2-CURRENT-ACTION`, and the copybook the program includes performs the join
 * `STRING FUNCTION TRIM(WS-DB2-CURRENT-ACTION) ' SQLCODE:' WS-DISP-SQLCODE ' '
 * WS-DSNTIAC-FMTD-TEXT`. The label is meaningless without that join, which is
 * precisely why redacting the label rather than only the code is the correct cut.
 */
export const REDACTED_DIAGNOSTICS = [
  {
    program: "COACTUPC",
    file: PROGRAM_SOURCE_FILES.COACTUPC,
    lines: [3674],
    condition: "account not found in the card cross-reference file",
    detail: "cics-response-and-reason",
    replacement:
      STATUS_MESSAGES.COACTUPC.DID_NOT_FIND_ACCT_IN_CARDXREF__L498.text,
  },
  {
    program: "COACTUPC",
    file: PROGRAM_SOURCE_FILES.COACTUPC,
    lines: [3723],
    condition: "account not found in the account master file",
    detail: "cics-response-and-reason",
    replacement: STATUS_MESSAGES.COACTUPC.DID_NOT_FIND_ACCT_IN_ACCTDAT.text,
  },
  {
    program: "COACTUPC",
    file: PROGRAM_SOURCE_FILES.COACTUPC,
    lines: [3773],
    condition: "customer not found in the customer master file",
    detail: "cics-response-and-reason",
    replacement: STATUS_MESSAGES.COACTUPC.DID_NOT_FIND_CUST_IN_CUSTDAT.text,
  },
  {
    program: "COACTVWC",
    file: PROGRAM_SOURCE_FILES.COACTVWC,
    lines: [747],
    condition: "account not found in the card cross-reference file",
    detail: "cics-response-and-reason",
    replacement: STATUS_MESSAGES.COACTVWC.DID_NOT_FIND_ACCT_IN_CARDXREF.text,
  },
  {
    program: "COACTVWC",
    file: PROGRAM_SOURCE_FILES.COACTVWC,
    lines: [796],
    condition: "account not found in the account master file",
    detail: "cics-response-and-reason",
    replacement: STATUS_MESSAGES.COACTVWC.DID_NOT_FIND_ACCT_IN_ACCTDAT.text,
  },
  {
    program: "COACTVWC",
    file: PROGRAM_SOURCE_FILES.COACTVWC,
    lines: [846],
    condition: "customer not found in the customer master file",
    detail: "cics-response-and-reason",
    replacement: STATUS_MESSAGES.COACTVWC.DID_NOT_FIND_CUST_IN_CUSTDAT.text,
  },
  {
    program: "COPAUS0C",
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [476],
    condition: "reading an authorization detail segment failed",
    detail: "ims-status-code",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS0C",
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [509],
    condition: "repositioning to an authorization detail segment failed",
    detail: "ims-status-code",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS0C",
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [836],
    condition: "account not found in the cross-reference file",
    detail: "cics-response-and-reason",
    replacement: SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND,
  },
  {
    program: "COPAUS0C",
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [851],
    condition: "reading the cross-reference file failed",
    detail: "cics-response-and-reason",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS0C",
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [886],
    condition: "account not found in the account master file",
    detail: "cics-response-and-reason",
    replacement: SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND,
  },
  {
    program: "COPAUS0C",
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [901],
    condition: "reading the account master file failed",
    detail: "cics-response-and-reason",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS0C",
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [937],
    condition: "customer not found in the customer master file",
    detail: "cics-response-and-reason",
    replacement: STATUS_MESSAGES.COACTVWC.DID_NOT_FIND_CUST_IN_CUSTDAT.text,
  },
  {
    program: "COPAUS0C",
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [952],
    condition: "reading the customer master file failed",
    detail: "cics-response-and-reason",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS0C",
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [988],
    condition: "reading the authorization summary segment failed",
    detail: "ims-status-code",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS0C",
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [1022],
    condition: "scheduling the IMS program specification block failed",
    detail: "ims-status-code",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS1C",
    file: PROGRAM_SOURCE_FILES.COPAUS1C,
    lines: [455],
    condition: "reading the authorization summary segment failed",
    detail: "ims-status-code",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS1C",
    file: PROGRAM_SOURCE_FILES.COPAUS1C,
    lines: [481],
    condition: "reading an authorization detail segment failed",
    detail: "ims-status-code",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS1C",
    file: PROGRAM_SOURCE_FILES.COPAUS1C,
    lines: [510],
    condition: "reading the next authorization segment failed",
    detail: "ims-status-code",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS1C",
    file: PROGRAM_SOURCE_FILES.COPAUS1C,
    lines: [544],
    condition: "marking an authorization as fraud failed and was rolled back",
    detail: "ims-status-code",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS1C",
    file: PROGRAM_SOURCE_FILES.COPAUS1C,
    lines: [595],
    condition: "scheduling the IMS program specification block failed",
    detail: "ims-status-code",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS2C",
    file: PROGRAM_SOURCE_FILES.COPAUS2C,
    lines: [211],
    condition: "inserting the fraud row failed",
    detail: "db2-sqlcode-and-sqlstate",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COPAUS2C",
    file: PROGRAM_SOURCE_FILES.COPAUS2C,
    lines: [239],
    condition: "updating the fraud row failed",
    detail: "db2-sqlcode-and-sqlstate",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COTRTUPC",
    file: PROGRAM_SOURCE_FILES.COTRTUPC,
    lines: [1499],
    condition: "reading the transaction-type row failed",
    detail: "db2-table-name",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COTRTUPC",
    file: PROGRAM_SOURCE_FILES.COTRTUPC,
    lines: [1569],
    condition: "updating the transaction-type row failed",
    detail: "db2-table-name",
    replacement: STATUS_MESSAGES.COTRTUPC.TABLE_UPDATE_FAILED.text,
  },
  {
    program: "COTRTUPC",
    file: PROGRAM_SOURCE_FILES.COTRTUPC,
    lines: [1609],
    condition: "inserting a transaction-type row failed",
    detail: "db2-table-name",
    replacement: STATUS_MESSAGES.COTRTUPC.INFORM_FAILURE.text,
  },
  {
    program: "COTRTUPC",
    file: PROGRAM_SOURCE_FILES.COTRTUPC,
    lines: [1641],
    condition: "delete refused because child category rows reference the type",
    detail: "db2-sqlcode-and-sqlerrm",
    replacement: SHARED_MESSAGES.PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST,
  },
  {
    program: "COTRTUPC",
    file: PROGRAM_SOURCE_FILES.COTRTUPC,
    lines: [1654],
    condition: "deleting the transaction-type row failed",
    detail: "db2-sqlcode-and-sqlerrm",
    replacement: STATUS_MESSAGES.COTRTUPC.RECORD_DELETE_FAILED.text,
  },
  {
    program: "COTRTLIC",
    file: "app/app-transaction-type-db2/cpy/CSDB2RPY.cpy",
    lines: [40, 70, 74, 76, 78],
    condition:
      "any Db2 failure on the transaction-type list screen; the included copybook joins the action label to the SQL code and to the formatted diagnostic text",
    detail: "db2-sqlcode-and-diagnostic-text",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COTRTLIC",
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1686, 1712, 1958, 1986, 2013, 2042],
    condition:
      "opening, fetching from or closing either declared browse cursor failed",
    detail: "db2-cursor-name",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COTRTLIC",
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1784],
    condition: "fetching from the backward browse cursor failed",
    detail: "db2-cursor-name",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COTRTLIC",
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1826],
    condition: "reading the transaction-type table failed",
    detail: "db2-table-name",
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: "COTRTLIC",
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1874],
    condition:
      "the update was refused because another unit of work held the row",
    detail: "persistence-mechanism",
    replacement: STATUS_MESSAGES.COTRTUPC.DATA_WAS_CHANGED_BEFORE_UPDATE.text,
  },
  {
    program: "COTRTLIC",
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1883],
    condition: "updating the transaction-type row failed",
    detail: "db2-sqlcode",
    replacement: STATUS_MESSAGES.COTRTUPC.TABLE_UPDATE_FAILED.text,
  },
  {
    program: "COTRTLIC",
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1929],
    condition: "deleting the transaction-type row failed",
    detail: "db2-sqlcode",
    replacement: STATUS_MESSAGES.COTRTUPC.RECORD_DELETE_FAILED.text,
  },
] as const satisfies readonly RedactedDiagnostic[];

/**
 * The shape a correlation reference must have before {@link formatDb2Message} will
 * render it: 8 to 36 characters of ASCII letters, digits and hyphens.
 *
 * WHY the token is validated at all. Refactoring Rationale: the parameter it
 * guards replaced two parameters that carried raw database diagnostics, and a
 * guard is what stops the same text arriving through the replacement. Without it a
 * caller could pass a `SQLCODE` and its `DSNTIAC` detail as the "reference" and
 * the rendered band would be exactly what the fix removed - the defect would have
 * moved one parameter to the left rather than gone.
 *
 * WHY these bounds. Assumptions: the range spans the two identifier forms the
 * platform actually produces. `CorrelationIdFilter` in the shared kernel accepts a
 * caller-supplied identifier and otherwise generates a UUID, whose canonical form
 * is 36 characters of hexadecimal and hyphens; a shortened trace or request
 * identifier is typically 8 to 32. The 8-character floor rejects a value too short
 * to identify one request among many, and the character class admits nothing that
 * could carry a sentence, a quoted identifier or a statement fragment - a space,
 * a colon, a comma, a quote and a parenthesis are all outside it.
 */
export const DB2_CORRELATION_REFERENCE_PATTERN = /^[A-Za-z0-9-]{8,36}$/;

/**
 * The browser-safe rendering of a data-tier failure: catalogued business text, a
 * public error code, and the correlation identifier that ties the request to the
 * server-side record holding the actual diagnostics.
 *
 * WHY this is three fields rather than one string. Alternatives Considered:
 * returning one pre-joined sentence was the shape the baseline used and was
 * rejected, because the three parts have three different destinations in the user
 * interface. `text` belongs in the 75-character message band, so it has to stay
 * separable to respect that width contract; `code` and `correlationId` belong in a
 * small, copyable footer, because their only purpose is to be quoted to support.
 * Joining them would force every screen to split the string apart again by
 * searching for a separator.
 */
export interface Db2DiagnosticMessage {
  /**
   * The catalogued action text, trimmed. Verbatim from the baseline literal, so
   * the sentence the user reads is unchanged from the mainframe.
   */
  readonly text: string;
  /**
   * A stable, public CardDemo error code assigned by the service, of the form
   * `CARDDEMO-<CONTEXT>-<NNNN>`. It classifies the failure without naming the
   * engine, its SQL code, or any table, column or cursor.
   */
  readonly code: string;
  /**
   * The request's correlation identifier, as issued by the shared kernel's
   * correlation filter and echoed in the response header. Quoting this to support
   * is what retrieves the withheld diagnostics from the server log.
   */
  readonly correlationId: string;
}

/**
 * Matches the public error-code shape this module will render.
 *
 * WHY the shape is enforced rather than trusted. Assumptions: `code` is a string
 * parameter, so nothing but a check stops a caller passing the raw SQL code, or
 * the `DSNTIAC` text, or a whole exception message through it - which would
 * reinstate the disclosure this module was changed to prevent, through the very
 * field that replaced it. The pattern admits an upper-case context and a numeric
 * sequence and nothing else, so vendor text cannot satisfy it.
 */
const PUBLIC_ERROR_CODE_PATTERN = /^CARDDEMO-[A-Z]{2,12}-\d{4}$/;

/**
 * Matches the correlation-identifier shape this module will render.
 *
 * WHY it is checked too. Assumptions: the same argument as the error code. The
 * pattern is deliberately permissive about WHICH identifier scheme is used - any
 * 8 to 64 character run of unreserved URL characters passes, which covers a UUID,
 * an AWS request id and an X-Ray trace id - and strict about what an identifier is
 * NOT: it admits no space, colon, comma or bracket, so a sentence of diagnostic
 * text cannot pass as one.
 */
const CORRELATION_ID_PATTERN = /^[A-Za-z0-9._~-]{8,64}$/;

/**
 * Builds the browser-safe rendering of a data-tier failure: the catalogued action
 * text, a public error code, and the correlation identifier that locates the
 * withheld diagnostics on the server.
 *
 * WHY this no longer reproduces `CSDB2RPY.cpy`. Refactoring Rationale: this
 * function used to take a `sqlCode` and a `detail` and join them onto the action
 * exactly as the copybook does, which was faithful to a 3270 screen and wrong for
 * a browser. `detail` is the output of the `DSNTIAC` utility, and what that
 * utility produces is the engine's own message text - typically the SQL code, the
 * SQLSTATE, the failing statement's object names and, for a constraint violation,
 * the constraint and index names. Rendering it handed every authenticated user a
 * running description of the data tier: which engine it is, which tables and
 * columns back the screen they are on, which constraints and cursors exist, and
 * on a deadlock which other unit of work it collided with. That is reconnaissance,
 * and it is delivered by the application itself to anyone who can provoke an
 * error - which, for a validation-adjacent failure, is anyone who can submit the
 * form. The raw SQL code went the same way for a weaker version of the same
 * reason: it is an engine-specific number whose value maps to a known
 * vendor-documented condition, so publishing it fingerprints the engine and
 * narrows what an attacker has to guess.
 *
 * WHY the parameter was REMOVED rather than sanitised inside this function
 * Alternatives Considered: filtering the detail here - stripping object names,
 * truncating, allowing a safe subset - was considered and rejected. A filter has
 * to anticipate every shape the engine can emit, it is applied at the last moment
 * before rendering where a mistake is invisible, and it leaves a parameter that a
 * caller can keep filling with diagnostics. Deleting the parameter makes the
 * disclosure unrepresentable: there is no argument through which vendor text can
 * arrive, and the two arguments that remain are shape-checked so neither can be
 * used to smuggle it.
 *
 * WHY nothing is lost operationally. Assumptions: the diagnostics are not
 * discarded, they are relocated. The owning service logs the SQL code, the
 * SQLSTATE and the `DSNTIAC`-equivalent text against the same correlation
 * identifier returned here, where access is governed by the log group's IAM policy
 * instead of by having provoked the error. So an operator diagnosing the failure
 * has strictly more than the 3270 screen gave them - the full diagnostic plus the
 * request that produced it - and the user has what they can act on: what failed,
 * and the identifier to quote.
 *
 * WHY this is registered as an intentional divergence. Assumptions: the project
 * carries user-visible strings across verbatim, and this narrows one. The narrowing
 * is deliberate and belongs in
 * `docs/architecture/cobol-to-service-traceability.md` beside the other documented
 * divergences, under the same heading as the baseline's plaintext password field,
 * which is likewise not carried forward. The action text itself is still verbatim;
 * only the vendor tail is withheld.
 * @param {string} action - The action label that was in `WS-DB2-CURRENT-ACTION`, normally an
 *   entry of `PROGRAM_MESSAGES.COTRTLIC`. Rendered verbatim after trimming.
 * @param {object} diagnostic - The public identifiers for this failure. Taken as one object
 *   rather than two positional strings because the pair is meaningless split up: a
 *   code with no correlation identifier cannot be looked up, and an identifier with
 *   no code says nothing about what failed.
 * @param {string} diagnostic.code - A stable public CardDemo error code of the form
 *   `CARDDEMO-<CONTEXT>-<NNNN>`, assigned by the owning service. Never the engine's
 *   SQL code.
 * @param {string} diagnostic.correlationId - The identifier under which the owning service
 *   logged the withheld diagnostics, as issued by the shared kernel's correlation
 *   filter and echoed in the response header.
 * @returns {Db2DiagnosticMessage} The three parts the user interface renders, with `text` trimmed.
 * @throws {RangeError} If `action` is blank, if `code` is not a
 *   `CARDDEMO-<CONTEXT>-<NNNN>` public code, or if `correlationId` is not a plain
 *   identifier of 8 to 64 unreserved characters. Each rejection exists to stop
 *   engine diagnostics reaching the browser through a field meant for an
 *   identifier.
 */
export function formatDb2Message(
  action: string,
  diagnostic: { readonly code: string; readonly correlationId: string },
): Db2DiagnosticMessage {
  // FUNCTION TRIM removes leading and trailing spaces, which String.prototype.trim
  // also does; the baseline field holds no other whitespace, so the two agree. The
  // catalogued literals carry a trailing space precisely because the baseline
  // appended a code to them, so trimming is what stops that now-unused separator
  // from showing as a stray space at the end of the sentence.
  const text = action.trim();
  if (text.length === 0) {
    throw new RangeError(
      "action must be a non-empty catalogued message; an empty action would render a message band containing only an error code.",
    );
  }
  if (!PUBLIC_ERROR_CODE_PATTERN.test(diagnostic.code)) {
    throw new RangeError(
      `diagnostic.code must be a public CardDemo error code of the form CARDDEMO-<CONTEXT>-<NNNN>, not an engine SQL code or diagnostic text; received a value of length ${String(diagnostic.code.length)}.`,
    );
  }
  if (!CORRELATION_ID_PATTERN.test(diagnostic.correlationId)) {
    throw new RangeError(
      `diagnostic.correlationId must be a plain identifier of 8 to 64 unreserved characters; received a value of length ${String(diagnostic.correlationId.length)}.`,
    );
  }
  // The rejected values are described by LENGTH and never echoed. A caller that
  // wrongly passed engine text would otherwise have that text placed into an
  // exception message, which is itself a browser-visible surface as soon as an
  // error boundary renders it - reinstating the disclosure inside the check that
  // exists to prevent it.
  return {
    text,
    code: diagnostic.code,
    correlationId: diagnostic.correlationId,
  };
}

/**
 * Pads a catalogued literal out to the width its COBOL `PICTURE` clause declares,
 * reproducing what a `MOVE` into that field would have produced at run time.
 *
 * COBOL pads a short sending item on the right with spaces and truncates an
 * over-long one on the right, and this mirrors both behaviours so a caller that
 * needs the exact runtime field value - a fixed-width export, or a test comparing
 * against mainframe output - can obtain it without the catalog storing fabricated
 * bytes.
 * @param {string} text - The literal exactly as transcribed from the copybook.
 * @param {number} declaredWidth - Field width from the `PIC X(n)` clause; must be a
 *   non-negative integer.
 * @returns {string} The value padded with trailing spaces to `declaredWidth`, or truncated
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
  // Alternatives Considered: truncating on the right rather than throwing matches
  // COBOL, which silently drops the overflow on an alphanumeric MOVE. Throwing
  // here was evaluated and rejected -- it would turn a faithful reproduction into
  // a runtime failure the mainframe never had.
  return text.length >= declaredWidth
    ? text.slice(0, declaredWidth)
    : text + " ".repeat(declaredWidth - text.length);
}

/**
 * Builds a field-validation message the way the shared COBOL edit routine does.
 *
 * The baseline trims the 25-character label field before concatenating, so a label
 * shorter than the field does not leave a run of spaces in the middle of the
 * sentence. Only the label is trimmed; the suffix is appended exactly as
 * catalogued, because it already carries its own leading space or colon.
 * @param {string} label - A value from {@link ACCOUNT_UPDATE_FIELD_LABELS}, or any label the
 *   baseline moves into `WS-EDIT-VARIABLE-NAME`.
 * @param {string} suffix - A value from {@link FIELD_VALIDATION_SUFFIXES}.
 * @returns {string} The composed message, trimmed label followed immediately by the suffix.
 */
export function formatFieldValidationMessage(
  label: string,
  suffix: string,
): string {
  // Assumptions: COBOL's FUNCTION TRIM removes leading and trailing spaces, which
  // String.prototype.trim also does, and the baseline field holds no other
  // whitespace, so the two agree byte for byte. A field carrying a tab or a
  // newline would diverge, and none does.
  return label.trim() + suffix;
}

/**
 * Composes a {@link MessageTemplate} the way the baseline's `STRING` statement
 * does, honouring each part's `DELIMITED BY` operand.
 *
 * Literal parts are transferred whole. A value part is transferred according to its
 * delimiter: `'SIZE'` sends the supplied string unchanged, `'SPACE'` sends the
 * characters before its first space, and any other delimiter sends the characters
 * before that delimiter's first occurrence. A value whose delimiter does not occur
 * is transferred whole, which is what COBOL does when the delimiter is absent from
 * the sending item.
 * @param {MessageTemplate} template - The template to compose, from {@link MESSAGE_TEMPLATES}.
 * @param {Readonly<Record<string, string>>} values - The runtime values, keyed by the COBOL data-name each value part
 *   names. A padded fixed-width value may be supplied as-is; the delimiter decides
 *   how much of it is used.
 * @returns {string} The composed message, with no trimming applied to the result.
 * @throws {Error} If a value part names a data-name that `values` does not supply,
 *   because silently substituting an empty string would produce a message that
 *   looks complete and is missing an identifier the operator needs.
 */
export function formatMessageTemplate(
  template: MessageTemplate,
  values: Readonly<Record<string, string>> = {},
): string {
  return template.parts
    .map(
      /**
       * Renders one template part into the text it contributes to the message.
       *
       * A literal part contributes its own text unchanged. A value part contributes as
       * much of its supplied value as its delimiter admits: `'SIZE'` the whole string,
       * `'SPACE'` the characters before the first blank, and any other delimiter the
       * characters before that delimiter's first occurrence. A value whose delimiter
       * does not occur contributes whole, which is what COBOL does when the delimiter is
       * absent from the sending item.
       * @param {MessageTemplatePart} part - One entry of {@link MessageTemplate.parts},
       *   either a literal part or a value part naming the COBOL data-name whose value
       *   it renders.
       * @returns {string} The text this part contributes, with no trimming applied.
       * @throws {Error} If the part is a value part naming a data-name that the enclosing
       *   call's `values` argument does not supply. It throws rather than substituting an
       *   empty string, because an empty substitution yields a message that looks
       *   complete while missing an identifier the operator needs.
       */
      (part) => {
        if ("literal" in part) {
          return part.literal;
        }
        const supplied = values[part.value];
        if (supplied === undefined) {
          throw new Error(
            `formatMessageTemplate: no value supplied for ${part.value}`,
          );
        }
        if (part.delimitedBy === "SIZE") {
          return supplied;
        }
        // Alternatives Considered: a whitespace regular expression was rejected for
        // the SPACE case. A COBOL delimiter is matched literally and SPACE is the
        // figurative constant for exactly one blank, so an index-of on the delimiter
        // text is the faithful operation; `\s` would additionally match a tab, which
        // the mainframe cannot produce and which would therefore truncate a value at
        // a character the reference program passes through.
        const delimiter = part.delimitedBy === "SPACE" ? " " : part.delimitedBy;
        const at = supplied.indexOf(delimiter);
        return at === -1 ? supplied : supplied.slice(0, at);
      },
    )
    .join("");
}

/**
 * Resolves the display-field width that governs a mapset's message band.
 * @param {MapsetName} mapset - A mapset name from {@link MESSAGE_BAND_BY_MAPSET}, which is also
 *   the base name of the `.bms` file and of its symbolic-map copybook.
 * @returns {78 | 80} 78 for nineteen of the mapsets, 80 for `COCRDSL` and `COCRDUP`.
 */
export function messageBandWidthForMapset(mapset: MapsetName): 78 | 80 {
  return MESSAGE_BAND_BY_MAPSET[mapset].displayWidth;
}

/**
 * Normalises a message-band value received from a service into the form the
 * renderer works with, resolving the baseline's two different "empty" conventions.
 *
 * The baseline clears a message field in two ways. `MOVE SPACES` fills it with
 * blanks, and the off-condition `88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES` fills it
 * with binary zeros. A field of low values that reaches a JSON transport arrives as
 * a run of `U+0000` code points, and those are the transport contract this function
 * defines: **the service layer may pass the field through untouched, and this
 * function is the one place that interprets it.**
 * @param {string | null | undefined} value - The raw message-band text from a service response, or
 *   `null`/`undefined` when the response omitted the field.
 * @returns {string} The text with `U+0000` code points removed and trailing field padding
 *   stripped from both ends, which is the empty string when the field carried no
 *   message under either convention.
 */
export function normaliseMessageBandValue(
  value: string | null | undefined,
): string {
  if (value === null || value === undefined) {
    return "";
  }
  // Only U+0000 is removed, and only because it is the wire form of LOW-VALUES.
  // Stripping the wider C0 range was rejected: no baseline message field can hold
  // any other control character, so a broader filter would silently alter text it
  // was never meant to touch rather than fail loudly on it.
  return value.replaceAll("\u0000", "").trim();
}

/**
 * Reports whether a message-band value means "no message is set".
 *
 * WHY an all-spaces value counts as empty as well as a zero-length one
 * Assumptions: the baseline's off-condition is `VALUE LOW-VALUES`, and the
 * programs also clear the field with `MOVE SPACES`. A transport that carries either
 * form arrives as a string that is empty or all spaces, and both mean the band
 * should stay hidden. Testing only for a zero-length string would leave the band
 * rendering a blank alert on every quiet screen.
 *
 * Assumptions: the low-values case needs its own handling rather than falling out of
 * the whitespace test, because `String.prototype.trim` removes whitespace and
 * `U+0000` is not whitespace - it is neither in the Unicode `White_Space` property
 * nor a JavaScript line terminator. An explicitly cleared field therefore survives
 * `trim()` with its length intact and would be rendered as a visible, blank alert,
 * which is precisely the case the sentinel exists to distinguish. Normalisation
 * happens in {@link normaliseMessageBandValue} so the emptiness test and the
 * renderer cannot disagree about what "empty" means.
 * @param {string | null | undefined} value - The message-band text received from a service response, or
 *   `null`/`undefined` when the response omitted it.
 * @returns {boolean} `true` when the band should render nothing.
 */
export function isMessageBandEmpty(value: string | null | undefined): boolean {
  return normaliseMessageBandValue(value).length === 0;
}

/**
 * The organisation line of the title band, **verbatim**: the whole 40-character
 * `PIC X(40)` literal, including the 6 leading and 7 trailing spaces that centre it
 * in a 40-column 3270 title field.
 *
 * Provided as a flat alias because the app shell renders it on every screen; it is
 * the same value as `SCREEN_TITLES.TITLE01.text`. Use
 * {@link APP_ORGANISATION_TITLE_DISPLAY} to render it.
 *
 * Refactoring Rationale: this constant was documented as "unpadded", which it never
 * was - the centring spaces are inside the literal. The description is corrected here
 * rather than the value, so the padded form keeps this name and the trimmed form is
 * the new one.
 *
 * Alternatives Considered: trimming the value under this name was the other candidate
 * fix and was rejected, because the catalog's invariant is that an exported value
 * equals its source bytes, and a test asserting the 40-character contract would then
 * have nothing to assert against. `ui/index.html` already draws the same distinction
 * for the same literal, rendering `<title>CardDemo</title>` while documenting the
 * padding.
 */
export const APP_ORGANISATION_TITLE = SCREEN_TITLES.TITLE01.text;

/**
 * The organisation line with the 3270 centring spaces removed, for rendering in a
 * browser where the layout does the centring.
 *
 * WHY a trimmed alias exists at all. Assumptions: character-cell centring only
 * centres in a monospaced 80-column grid. In a proportional-font flex layout the
 * leading spaces centre nothing and instead offset the text, so a renderer needs the
 * trimmed form - and if this module does not provide it, every consumer calls
 * `.trim()` itself and the module stops owning what is displayed.
 */
export const APP_ORGANISATION_TITLE_DISPLAY = APP_ORGANISATION_TITLE.trim();

/**
 * The application line of the title band, **verbatim**: the whole 40-character
 * literal, including its 14 leading and 18 trailing centring spaces. Same value as
 * `SCREEN_TITLES.TITLE02.text`. Use {@link APP_TITLE_DISPLAY} to render it.
 */
export const APP_TITLE = SCREEN_TITLES.TITLE02.text;

/** The application line with the centring spaces removed, for browser rendering. */
export const APP_TITLE_DISPLAY = APP_TITLE.trim();

/**
 * Sign-off text naming the application "CCDA", verbatim from a `PIC X(40)` field and
 * therefore ending in one trailing space. Same value as
 * `SCREEN_TITLES.THANK_YOU.text`.
 */
export const THANK_YOU_CCDA = SCREEN_TITLES.THANK_YOU.text;

/**
 * Sign-off text naming the application "CardDemo", verbatim from a `PIC X(50)` field
 * whose literal is 49 characters and ends in six trailing spaces. Same value as
 * `COMMON_MESSAGES.THANK_YOU.text`.
 */
export const THANK_YOU_CARDDEMO = COMMON_MESSAGES.THANK_YOU.text;

/**
 * Unmapped-attention-key message, verbatim from a `PIC X(50)` field and therefore
 * ending in trailing spaces. Same value as `COMMON_MESSAGES.INVALID_KEY.text`.
 */
export const INVALID_KEY_PRESSED = COMMON_MESSAGES.INVALID_KEY.text;

/**
 * Text shown when a non-administrator reaches an administrator-only route.
 *
 * WHY this is aliased at the top level -- Assumptions: the router guards admin
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
