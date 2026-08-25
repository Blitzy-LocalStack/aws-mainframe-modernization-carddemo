/**
 * @file Verbatim catalog of the CardDemo user-visible strings whose source is a COBOL
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
 * The static text PAINTED BY THE BMS MAPS is the other half, and this module holds
 * it for every screen that imports its strings from here. AAP section 0.2.1.5 states
 * the rule for `ui/src/screens/**` - "every user-visible string from the message
 * catalog" - so a screen's headings, field labels and per-screen legend text belong
 * here beside the copybook constants and program literals, transcribed from the
 * mapset and carrying the mapset line each was read from. See "Not in this catalog"
 * below for what remains outside it, how large that group is, and which module owns
 * it instead. Two groups make the two halves easy to tell apart: the note above
 * {@link ACCOUNT_UPDATE_FIELD_LABELS} records that those entries are program
 * literals rather than painted labels, while
 * {@link ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS} and its three companions are painted
 * labels, cited to `app/bms/COACTVW.bms` line by line.
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
 * Assumptions: all three mechanisms are enumerated rather than only the first, because
 * an extraction that matches `MOVE '<literal>'` alone is complete for finished sentences
 * and blind to the other two forms - it leaves out 121 condition-name messages and 56
 * composition fragments. A catalog missing those while claiming to own every
 * user-visible string is worse than an obviously partial one: a screen author who cannot
 * find a string here inlines it, which is exactly the failure this module exists to
 * prevent.
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
 * Trade-offs: the diagnostics are redacted rather than transcribed. Carrying them
 * verbatim would satisfy the centralisation rule and simultaneously ship the schema's
 * cursor names, a physical table name and raw SQL codes into a bundle any browser can
 * read, which is an information-disclosure defect the migration plan's data-exposure
 * narrowing exists to prevent. Redaction costs message specificity on failure paths
 * only, and every replacement chosen is itself a verbatim baseline string, so no
 * invented wording enters the product. This is an intentional behavioural divergence
 * and {@link REDACTED_DIAGNOSTICS} is its register.
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
 * Assumptions: this class is documented rather than silently skipped. The only way to
 * audit "this module owns every user-visible string" is to re-run an extraction and
 * account for every literal it finds. Two of the three outcomes - operator text and
 * diagnostics - already have a home. Without this third list the remainder looks like a
 * shortfall, and the reviewer's only recourse is to open each COBOL site by hand to
 * discover that a `COPY` operand is not a sentence.
 *
 * Widths
 * ------
 * Four different widths govern one message on its way to a screen - a 75-byte
 * shared work area, a program-local 80- or 75-byte composition buffer, and a
 * 78- or 80-character display field depending on the mapset. {@link MESSAGE_BAND}
 * models all of them; do not assume a single number.
 * Not in this catalog
 * -------------------
 * This catalog holds the painted text of NINE mapsets - `app/bms/COACTVW.bms` and
 * `app/bms/COMEN01.bms`, whose screens import their strings from here, plus
 * `COTRN00`, `COTRN01`, `COBIL00`, `CORPT00`, `COUSR00`, `COUSR01` and `COUSR03`,
 * whose groups are published ahead of the screen edits that consume them - and the
 * rest of the painted text is held by the module that renders it. That
 * remainder is real and it is large, so it is measured rather than waved at. The
 * measurement has two halves. The BMS half is one command:
 *
 * ```text
 * grep -ho "INITIAL='[^']*'" $(find app -name '*.bms')
 * ```
 *
 * Across the 21 mapsets (17 base under `app/bms` plus 4 extension mapsets) that
 * yields 665 occurrences of 213 distinct literals. 194 of the 213 contain at least
 * one alphanumeric character; the remaining 19 are blank or pure punctuation used
 * to rule a line. The containment half then tests each of those 194 against this
 * module's CODE - its exported values, with this file's own doc comments excluded -
 * and finds 101: the title-band, menu-option and message text this module already owned
 * and the map merely re-paints, plus the account-view, main-menu and seven-screen painted
 * strings it now owns outright. That leaves **93 distinct literals, 205 occurrences, that
 * this module does not hold**.
 *
 * Refactoring Rationale: the containment half excludes this file's comments, and stating
 * it that precisely is not pedantry. A whole-file substring test is shorter to describe
 * and is wrong here for a definite reason: the four kinds listed below QUOTE the very
 * literals they describe as absent, so `ENTER=Continue  F3=Exit` and its two companions
 * counted as held purely because this paragraph names them, and the list below therefore
 * contradicted the figure above it. Excluding comments makes the figure and the examples
 * agree, and all three legends are outside the 101.
 *
 * Refactoring Rationale: the three figures read 31, 163 and 344 and were re-measured
 * rather than adjusted, because three independent things had moved since they were
 * written: entries were added to this catalog by later work; the account-view group below
 * moved 26 literals in and the main-menu group moved 3 (`Main Menu`,
 * `Please select an option :` and `F3=Exit`); and the containment test changed as just
 * described. Re-running the command and the containment test is the whole method, so a
 * reader who doubts a figure can settle it in one step instead of reconciling three
 * edits.
 *
 * Refactoring Rationale: they were re-measured once more, from 55, 139 and 301, when the
 * seven per-screen painted-text groups below were added. Adjusting them by hand would have
 * been guesswork twice over: containment is a SUBSTRING test, so several of the strings
 * added there were already held inside a longer value - `Transaction Reports` and
 * `Bill Payment` sit inside 35-character menu-option names in {@link MAIN_MENU_OPTIONS} -
 * while others are painted by two mapsets at once, so neither the count of new entries nor
 * the count of new occurrences predicts the delta. Re-running the two commands is the only
 * method that yields a figure a reader can reproduce.
 *
 * Refactoring Rationale: the continued-literal figure in the next paragraph read `18`
 * and was replaced rather than adjusted, because it counted the wrong thing twice
 * over. It was an occurrence count offered as a count of literals - 21 sites carry 16
 * values, three of them painted by more than one mapset - and it undercounted even the
 * sites by exactly three, because a doubled apostrophe closes the pattern's character
 * class early: at `COTRN00` line 448, `COUSR00` line 447 and `COPAU00` line 501 the
 * pattern matches `INITIAL='Type '` and each prompt reads as a closed one-line
 * literal. Those three are therefore absent from the blind-spot count AND present in
 * the 213 as the fragment `Type `, which is the sharpest available reason to measure
 * this half by rejoining the lines rather than by pattern: on those three the pattern
 * does not merely miss a literal, it reports a different one in its place.
 *
 * The command above deliberately matches only a literal that opens and closes on one
 * physical line, which is why its output is exact and repeatable; it therefore cannot
 * see a literal BMS continues across a line boundary - a non-blank in column 72, the
 * text resuming in column 16. Rejoining those gives **21 occurrence sites carrying 16
 * distinct literals** (for example `app/bms/COSGN00.bms` line 149,
 * `app/bms/COTRN02.bms` line 279 and `app/bms/COUSR02.bms` line 163). Three of the 16
 * are pure punctuation ruling a line, so 13 survive the same alphanumeric filter the
 * 194 above survived. FOUR of those 13 are now in this catalog - the row-21 selection
 * prompts on `COTRN00` and `COUSR00`, the bill-payment confirmation on `COBIL00` and
 * the report-submission confirmation on `CORPT00`, each rejoined and each cited to
 * both of its lines - leaving 9 distinct literals at 11 occurrences that this module
 * does not hold. No value occurs in both halves, so the two add: **102 distinct
 * literals, 216 occurrences**, against the 93 and 205 the one command reports. Four
 * kinds account for all of them:
 *
 * - Static field labels, one per input or display field: `User ID     :`,
 *   `Password    :`, `Account Number    :`, `Name on card      :`,
 *   `Match Status:`. Two earlier examples, `Merchant ID:` and `Amount:`, are held by
 *   {@link TRANSACTION_DETAIL_FIELD_LABELS} now and were swapped out so that this
 *   bullet cannot contradict the figure above it.
 * - The four status-line prompt words every screen paints in its top two rows,
 *   `Tran:`, `Date:`, `Prog:`, `Time:` (20 occurrences each), together with the
 *   `mm/dd/yy` and `hh:mm:ss` placeholder patterns (21 and 20) and `AppID:` /
 *   `SysID:`. `Date:` is the one entry in this bullet the containment test scores as
 *   held, and it is a substring artefact rather than a move: `Orig Date:` and
 *   `Proc Date:` in {@link TRANSACTION_DETAIL_FIELD_LABELS} contain it. Ownership is
 *   unchanged - `ui/src/layout/ScreenHeader.tsx` owns the status-line prompt, and
 *   only the two `COTRN01` labels belong here.
 * - Function-key legends, painted as one literal per screen rather than assembled
 *   from parts: `ENTER=Sign-on  F3=Exit`, `ENTER=Process F3=Exit`,
 *   `ENTER=Continue  F3=Exit`. The longest of them are among the 16 continued
 *   literals, which is why a legend cannot be recovered reliably by grep alone.
 * - Pure decoration with no counterpart in a browser, chiefly the eight-line
 *   ASCII-art dollar note on `app/bms/COSGN00.bms`.
 *
 * Two of those four kinds have a single source of truth that is NOT this module,
 * and both are shared rather than per-screen: the four status-line prompts belong to
 * `ui/src/layout/ScreenHeader.tsx`, and the function-key legends whose wording is
 * uniform across mapsets belong to `ui/src/layout/PfKeyBar.tsx`, which derives its
 * key semantics from `app/cpy/CSSTRPFY.cpy` rather than from the painted legend
 * text. The decoration has no target at all. A screen's own headings, field labels
 * and per-screen legend text belong HERE, which is what
 * {@link ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS} and its three companions deliver for the
 * account-view mapset and {@link MAIN_MENU_HEADINGS} and its companion deliver for the
 * main-menu mapset.
 *
 * Assumptions: this is a description of the delivered tree, not a plan for it.
 * `ui/src/layout/ScreenHeader.tsx` declares the four status-line prompts and
 * `ui/src/layout/PfKeyBar.tsx` declares the three uniform legend labels; the
 * account-view and main-menu groups below hold every painted string of their two
 * mapsets, and the two screens that render those mapsets take them from here rather
 * than declaring copies - which their own suites assert, by comparing what is rendered
 * against these entries rather than against a literal typed into the test; and the
 * screens authored before that rule was applied still declare their own painted
 * labels beside the controls they name. A string absent from this catalog is
 * therefore evidence that a `.bms` file holds it and that its renderer has not been
 * brought under this rule yet - it is not evidence that no module owns it.
 *
 * Assumptions: for SEVEN of those screens - the transaction browse and detail, bill
 * payment, reports and the three user screens - the painted text is held here already,
 * published ahead of the edits that make them import it, so each of those strings has
 * two spellings in the tree until that edit lands. Presence in this catalog is
 * consequently not by itself evidence that the renderer takes its text from here; the
 * account-view and main-menu suites are what establish that, for the two mapsets where
 * it currently holds.
 *
 * Alternatives Considered: leaving every painted label in its screen, which is where
 * the delivered tree began. It was rejected because AAP section 0.2.1.5 assigns the
 * strings a screen renders to this catalog, and because the byte-exactness invariant
 * below is reviewed HERE - a label transcribed in a screen is a value nobody
 * re-checks against the mapset. The positional objection is real and is answered
 * rather than dismissed: a field label is meaningless apart from the field it sits
 * beside, so {@link ACCOUNT_VIEW_PAINTED_TEXT_SOURCES} carries the mapset line of
 * every entry and the group order is the mapset's declaration order, which keeps the
 * label one lookup from its field instead of one file.
 * Trade-offs: two kinds of painted text stay outside this module, and that split is
 * accepted rather than argued away - a status-line prompt and a uniform legend are
 * SHARED text whose renderer is shared too, so cataloguing them per screen would
 * create 21 copies of one string. "Which module owns this string?" is therefore
 * answered by asking whether it is a screen's own text (here) or shell text (the
 * shell component).
 *
 * Invariant
 * ---------
 * Transcription is byte-exact. Trailing spaces, doubled interior spaces,
 * inconsistent spacing before an ellipsis, case-only variants of otherwise
 * identical sentences, and outright grammatical defects in the baseline are all
 * reproduced exactly as the COBOL holds them. None of them is a typo, because
 * `app/` is reference-only and is the behavioural oracle for the migration: the
 * golden-master tests compare this text against mainframe output, so a spelling or
 * spacing improvement made here would register as a parity failure.
 *
 * Assumptions: the strings are grouped by originating copybook or program rather than
 * by screen. A downstream screen author works from a COBOL source location, so a key
 * must be derivable from "which program emitted this". Grouping by screen would require
 * knowing the program-to-route mapping, which lives in `router.tsx`, to find a string
 * at all.
 *
 * Alternatives Considered: no internationalisation library. `react-intl` and `i18next`
 * are the reflexive choices for anything called a message catalog, and both were
 * rejected. The requirement is byte-exact reproduction of one locale, not translation,
 * and an i18n runtime adds a message-compilation step that can normalise whitespace -
 * the single thing this module must never do. It would also breach the pinned
 * dependency set in `ui/package.json`, which contains no i18n package.
 *
 * This module imports nothing and performs no work at load time, so it can be
 * consumed by any layer without creating a cycle.
 * @module messages
 */

/**
 * A location in the immutable COBOL baseline that a catalogued string was read
 * from.
 *
 * Trade-offs: provenance is structured data rather than a prose comment above each
 * entry. With this many entries, a comment per entry would be ~150 lines of text
 * restating values, which the project's explainability rule explicitly forbids. As data
 * it is also machine-checkable - the validation harness asserts that the cited line
 * still contains the string.
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
 * Assumptions: `text` and `declaredWidth` are separate. Several baseline literals are
 * shorter than the field they initialise - both `CSMSG01Y` messages are 49 characters
 * against `PIC X(50)` - so COBOL pads the runtime field with trailing spaces that the
 * source text does not contain. Trade-offs: storing the padded form would fabricate
 * bytes that are not in the source; storing only the text would lose the width contract
 * a renderer needs. Keeping both lets `padToDeclaredWidth` reproduce the runtime value
 * on demand while the transcription stays honest.
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
 * Assumptions: `field` is recorded alongside `declaredWidth`. The same program declares
 * conditions on two different fields at two different widths - `WS-INFO-MSG` is `PIC
 * X(40)` or `X(45)` and carries the guidance line, while `WS-RETURN-MSG` /
 * `WS-ERROR-MSG` is `PIC X(75)` and carries the error line. A renderer that padded a
 * 40-character guidance message to 75 would put it in the wrong region, so the field
 * name is kept as the discriminator rather than inferred from the width.
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
 * Assumptions: the delimiter is modelled rather than dropped. COBOL's `DELIMITED BY` is
 * not decoration - it decides how much of a sending field is transferred. `DELIMITED BY
 * SIZE` sends the whole field; `DELIMITED BY SPACE` sends only the characters before
 * the first space, which truncates a padded 35-character menu name to its first word;
 * `DELIMITED BY ' '` sends the characters before the first double space, which trims
 * the same field's padding without truncating it. Two compositions in `COMEN01C` differ *only*
 * in that operand and therefore produce different text from the same input. A model without
 * the delimiter cannot reproduce either of them.
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
       * Alternatives Considered: the third member is the two-space literal rather than
       * `string`. Widening it to `string` was rejected because it absorbs the two named
       * operands, so the type would then permit any text and describe none of it.
       * Measured across the baseline, the message compositions use exactly three
       * delimiter forms - `SIZE`, `SPACE` and the literal `' '` in `COMEN01C` - so the
       * union enumerates them and a fourth form has to be added deliberately rather
       * than arriving unnoticed.
       */
      readonly delimitedBy: 'SIZE' | 'SPACE' | '  ';
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
 * Trade-offs: these are classification identifiers and not the vendor names they stand
 * for. The value of a register row is that it names the class of detail being withheld,
 * and a class can be named without naming the utility, the cursor or the table that
 * produced it. The guarantee this module offers is therefore about its **exported
 * values**: no exported string in this module contains a cursor name, a table name, or
 * an `SQLCODE`, `SQLSTATE` or diagnostic-utility identifier, and that holds in every
 * build mode because it is a property of the data rather than of the toolchain. The
 * precise COBOL data-names appear only in comments, which the production build's
 * minifier strips - measured as zero occurrences in a minified bundle - though an
 * unminified development bundle retains some of them, which is acceptable because a
 * development bundle is not a shipped artifact.
 */
export type DiagnosticDetailKind =
  | 'cics-response-and-reason'
  | 'db2-cursor-name'
  | 'db2-sqlcode'
  | 'db2-sqlcode-and-sqlstate'
  | 'db2-sqlcode-and-sqlerrm'
  | 'db2-sqlcode-and-diagnostic-text'
  | 'db2-table-name'
  | 'ims-status-code'
  | 'persistence-mechanism';

/**
 * A baseline message that is deliberately **not** exported as displayable text.
 *
 * Each entry is a register row for one intentional behavioural divergence: the
 * baseline showed an internal value to the operator, and the target shows
 * {@link RedactedDiagnostic.replacement} instead while the detail goes to a
 * server-side structured log. Provenance is retained in full so the original
 * wording remains findable in the baseline, which is the behavioural oracle.
 *
 * Trade-offs: the withheld text is not stored here even as a comment. The point of the
 * redaction is that the message does not reach a browser bundle, and a string literal
 * reaches it in every build mode while a comment survives only in an unminified one.
 * Storing the text "for reference" would reintroduce exactly what the entry exists to
 * remove, and the cited lines already point at the authoritative copy in the baseline,
 * so nothing is lost by leaving it there.
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
 * Trade-offs: this is exported as its own constant as well as being carried on every
 * entry. A renderer that lays the menu out in a fixed-width column needs the width
 * once, not seventeen times, and a test that asserts the padding needs something to
 * assert against. Carrying it on the entries too makes the interface refuse an option
 * that has not declared its width, so a future entry cannot be added with its padding
 * undocumented.
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
  readonly userType: 'A' | 'U';
  /** Where the option name was read from. */
  readonly source: SourceRef;
}

/**
 * One selectable entry on the admin menu, from `app/cpy/COADM02Y.cpy`.
 *
 * Assumptions: this has no `userType` while {@link MainMenuOption} does. The admin-menu
 * copybook genuinely omits the `USRTYPE` subfield. The admin menu is only reachable
 * after the administrator check has already passed, so a per-option user type would be
 * redundant. Adding one here to make the two interfaces symmetrical would invent a
 * field the baseline does not have.
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
    text: '      AWS Mainframe Modernization       ',
    declaredWidth: 40,
    source: { file: 'app/cpy/COTTL01Y.cpy', lines: [19] },
  },
  /**
   * `CCDA-TITLE02` - the application line of the title band.
   *
   * Assumptions: the commented-out alternative on line 21 is not catalogued. Line 21
   * holds a disabled variant reading "Credit Card Demo Application (CCDA)" which is *also*
   * exactly 40 characters, so it is indistinguishable from the live value by length or by
   * shape. Only the `*` in column 7 marks it dead.
   *
   * Refactoring Rationale: the same trap appears three more times in the baseline
   * - `COMEN02Y` line 69, `COADM02Y` line 21 and `COACTUPC` line 1614 - so the
   * extraction that produced this file discards any line whose column-7 indicator
   * is `*` or `/` before looking at its content, rather than filtering by content.
   */
  TITLE02: {
    text: '              CardDemo                  ',
    declaredWidth: 40,
    source: { file: 'app/cpy/COTTL01Y.cpy', lines: [22] },
  },
  /**
   * `CCDA-THANK-YOU` - the sign-off line, which names the application "CCDA".
   *
   * Alternatives Considered: this is kept separate from {@link
   * COMMON_MESSAGES.THANK_YOU}. The two look like one sentence stored at two widths,
   * and de-duplicating them - either directly or by comparing their trimmed forms - is
   * the obvious tidy-up. It loses text. This one says "CCDA"; the `CSMSG01Y` one says
   * "CardDemo". Neither word appears in the other string, so they are two different
   * sentences and both are displayed. They are also 40 and 49 characters against
   * declared widths of 40 and 50, so a width-based merge would not reconcile them
   * either.
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
 * Assumptions: this group carries widths instead of text. All four fields of
 * `ABEND-DATA` are declared `VALUE SPACES`, so the copybook contributes a structure
 * that the failing program fills at run time, not any message. The displayed wording
 * comes from the programs and is catalogued in {@link SHARED_MESSAGES} as
 * `UNEXPECTED_ABEND_OCCURRED` and `UNEXPECTED_DATA_SCENARIO`. Treating this copybook as
 * a source of text would yield four empty strings and hide where the real wording
 * lives.
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
 * Assumptions: two adjacent screen regions are deliberately *not* modelled here. They
 * are not the band, and folding them in would make this constant mean "any text width",
 * which is the ambiguity it exists to remove. `COMEN01C` and `COADM01C` compose their
 * menu lines into a 40-character `WS-MENU-OPT-TXT` / `WS-ADMIN-OPT-TXT` and move them
 * to the menu-row fields `OPTN001O`-`OPTN012O` (`COMEN01C.cbl:48,276`), which is a list
 * region; and `COTRTLIC` sends an 800-character `WS-LONG-MSG` as a full-screen
 * diagnostic (`COTRTLIC.cbl:235,2087`), whose content is redacted and therefore never
 * rendered at all. Both are recorded so their absence reads as a decision.
 *
 * Refactoring Rationale: all five widths are modelled rather than the work area
 * alone. The 75 of `CVCRD01Y` describes the *work area*, not the region a
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
  emptySentinel: 'LOW-VALUES',
  /** Where each stage of the contract is declared. */
  sources: {
    /** The shared work area and its off-condition. */
    workArea: { file: 'app/cpy/CVCRD01Y.cpy', lines: [28, 29, 30] },
    /** One representative 78-character display declaration, input and output. */
    displayStandard: { file: 'app/cpy-bms/COSGN00.CPY', lines: [84, 152] },
    /** The 80-character display declarations, input and output. */
    displayCardDetail: { file: 'app/cpy-bms/COCRDSL.CPY', lines: [102, 194] },
    /** One representative 80-character composition buffer. */
    compositionBufferWide: { file: 'app/cbl/COSGN00C.cbl', lines: [38] },
    /** One representative 75-character composition buffer. */
    compositionBufferNarrow: { file: 'app/cbl/COACTUPC.cbl', lines: [479] },
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
 * Assumptions: both names are carried. They are never the same string - all 21 differ -
 * and the difference follows two unrelated conventions. Fourteen drop the mapset's
 * trailing digit and append `A` (`COSGN00` defines `COSGN0A`); the other seven drop the
 * `O` of the `CO` prefix instead (`COACTUP` defines `CACTUPA`, `COCRDLI` defines
 * `CCRDLIA`). A reader who assumes one rule and chases a file called `CACTUPA` will not
 * find one, so the mapping is stored rather than derived.
 *
 * Assumptions: the exception list is two entries rather than a rule. `COCRDSL` and
 * `COCRDUP` are 80 and the other nineteen are 78, and nothing in the baseline explains
 * why - the two card screens simply declare a wider field. There is no pattern to
 * derive, so the table is exhaustive rather than computed.
 */
export const MESSAGE_BAND_BY_MAPSET = {
  COACTUP: { map: 'CACTUPA', displayWidth: 78 },
  COACTVW: { map: 'CACTVWA', displayWidth: 78 },
  COADM01: { map: 'COADM1A', displayWidth: 78 },
  COBIL00: { map: 'COBIL0A', displayWidth: 78 },
  COCRDLI: { map: 'CCRDLIA', displayWidth: 78 },
  COCRDSL: { map: 'CCRDSLA', displayWidth: 80 },
  COCRDUP: { map: 'CCRDUPA', displayWidth: 80 },
  COMEN01: { map: 'COMEN1A', displayWidth: 78 },
  COPAU00: { map: 'COPAU0A', displayWidth: 78 },
  COPAU01: { map: 'COPAU1A', displayWidth: 78 },
  CORPT00: { map: 'CORPT0A', displayWidth: 78 },
  COSGN00: { map: 'COSGN0A', displayWidth: 78 },
  COTRN00: { map: 'COTRN0A', displayWidth: 78 },
  COTRN01: { map: 'COTRN1A', displayWidth: 78 },
  COTRN02: { map: 'COTRN2A', displayWidth: 78 },
  COTRTLI: { map: 'CTRTLIA', displayWidth: 78 },
  COTRTUP: { map: 'CTRTUPA', displayWidth: 78 },
  COUSR00: { map: 'COUSR0A', displayWidth: 78 },
  COUSR01: { map: 'COUSR1A', displayWidth: 78 },
  COUSR02: { map: 'COUSR2A', displayWidth: 78 },
  COUSR03: { map: 'COUSR3A', displayWidth: 78 },
} as const satisfies Record<string, { readonly map: string; readonly displayWidth: 78 | 80 }>;

/** Name of a mapset whose message-band width this module records. */
export type MapsetName = keyof typeof MESSAGE_BAND_BY_MAPSET;

/**
 * Number of populated main-menu slots, from `CDEMO-MENU-OPT-COUNT`.
 *
 * Assumptions: the count is exported alongside the array. The baseline declares
 * `CDEMO-MENU-OPT OCCURS 12 TIMES` but populates only 11 slots, so the twelfth is
 * uninitialised storage. COBOL callers iterate the count, never the `OCCURS`. {@link
 * MAIN_MENU_OPTIONS} holds exactly the populated entries, so the two agree by
 * construction; the count is exported so a reader who checks this against the copybook
 * does not mistake the 11/12 gap for a dropped option.
 */
export const MAIN_MENU_OPTION_COUNT = 11 as const;

/**
 * The main-menu options from `app/cpy/COMEN02Y.cpy`, in display order. Each name is
 * padded by the baseline to exactly 35 characters and is stored that way.
 *
 * Assumptions: every entry is `userType: 'U'`. The copybook sets `OPT-USRTYPE` to `'U'`
 * for all eleven options, so nothing on the main menu is administrator-only in the live
 * configuration. The copybook's line 69 holds a commented-out variant of option 8 whose
 * wording carries "(Admin Only)", a restriction no live entry asserts; it is excluded on
 * the strength of its column-7 comment marker alone, because it is exactly 35 characters
 * and so indistinguishable from the live label by length.
 */
export const MAIN_MENU_OPTIONS = [
  {
    optionNumber: 1,
    name: 'Account View                       ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COACTVWC',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [27] },
  },
  {
    optionNumber: 2,
    name: 'Account Update                     ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COACTUPC',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [33] },
  },
  {
    optionNumber: 3,
    name: 'Credit Card List                   ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COCRDLIC',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [39] },
  },
  {
    optionNumber: 4,
    name: 'Credit Card View                   ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COCRDSLC',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [45] },
  },
  {
    optionNumber: 5,
    name: 'Credit Card Update                 ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COCRDUPC',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [51] },
  },
  {
    optionNumber: 6,
    name: 'Transaction List                   ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COTRN00C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [57] },
  },
  {
    optionNumber: 7,
    name: 'Transaction View                   ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COTRN01C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [63] },
  },
  {
    optionNumber: 8,
    name: 'Transaction Add                    ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COTRN02C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [70] },
  },
  {
    optionNumber: 9,
    name: 'Transaction Reports                ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'CORPT00C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [76] },
  },
  {
    optionNumber: 10,
    name: 'Bill Payment                       ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COBIL00C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [82] },
  },
  {
    optionNumber: 11,
    name: 'Pending Authorization View         ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COPAUS0C',
    userType: 'U',
    source: { file: 'app/cpy/COMEN02Y.cpy', lines: [88] },
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
    name: 'User List (Security)               ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COUSR00C',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [28] },
  },
  {
    optionNumber: 2,
    name: 'User Add (Security)                ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COUSR01C',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [33] },
  },
  {
    optionNumber: 3,
    name: 'User Update (Security)             ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COUSR02C',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [38] },
  },
  {
    optionNumber: 4,
    name: 'User Delete (Security)             ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COUSR03C',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [43] },
  },
  {
    optionNumber: 5,
    name: 'Transaction Type List/Update (Db2) ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COTRTLIC',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [48] },
  },
  {
    optionNumber: 6,
    name: 'Transaction Type Maintenance (Db2) ',
    nameDeclaredWidth: MENU_OPTION_DECLARED_WIDTH,
    programName: 'COTRTUPC',
    source: { file: 'app/cpy/COADM02Y.cpy', lines: [52] },
  },
] as const satisfies readonly AdminMenuOption[];

/**
 * Repository-relative path of every online program this catalog transcribes,
 * keyed by program name.
 *
 * Trade-offs: the path is held once here instead of on each entry. Repeating it on
 * every entry - there are several hundred across the message, status and template
 * groups - would add no information and would let two entries for the same program
 * disagree. Provenance entries therefore carry only line numbers and resolve their file
 * through this map. A count is deliberately not quoted here: it would be one more
 * number to keep in step with the groups below, and it is derivable from them.
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
 * Trade-offs: these are a group of their own rather than filed under one program. The
 * catalog is keyed by origin, and these strings have several origins, so filing each
 * under a single program would mean picking one arbitrarily and leaving the other call
 * sites pointing at a key that names the wrong program. A shared group keeps the "keyed
 * by origin" rule honest, and the provenance below lists every site.
 *
 * Alternatives Considered: the five page-navigation messages are five entries and not
 * one. "already at the top", "already at the bottom", "at the top", "reached the top"
 * and "reached the bottom" are near-duplicates that look like accidental drift, and
 * folding them into one paging message would delete four distinct sentences the
 * operator actually sees at four different moments - two describe a refused key press,
 * three describe an arrival.
 */
export const SHARED_MESSAGES = {
  ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER:
    'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER',
  ACCOUNT_ID_NOT_FOUND: 'Account ID NOT found...',
  CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER:
    'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER',
  FIRST_NAME_CAN_NOT_BE_EMPTY: 'First Name can NOT be empty...',
  INVALID_SELECTION_VALID_VALUE_IS_S: 'Invalid selection. Valid value is S',
  INVALID_VALUE_VALID_VALUES_ARE_Y_N: 'Invalid value. Valid values are (Y/N)...',
  LAST_NAME_CAN_NOT_BE_EMPTY: 'Last Name can NOT be empty...',
  /**
   * Refactoring Rationale: ⚠️ NO SPA SCREEN EMITS THIS STRING, and it is kept anyway.
   * Both baseline sites are password controls the migration removed rather than
   * reproduced: the update screen's, withdrawn under divergence `D-10`, and the create
   * screen's, withdrawn under `D-RUNTIME-CREDENTIAL-HANDOVER` because the field was
   * collected and never transmitted -- a control that appeared to set a credential and
   * could not. The credential is generated by the service and returned once instead, so
   * there is no field left for an emptiness check to guard.
   *
   * Assumptions: the entry survives because this catalog is a record of the baseline's
   * strings and not an index of the ones currently reachable. Deleting it would erase the
   * evidence that two programs emitted this sentence at
   * `app/cbl/COUSR01C.cbl` L138 and `app/cbl/COUSR02C.cbl` L200, which is what a reader
   * comparing the two systems needs and what the divergence entries cite. Two screen
   * tests assert this sentence is ABSENT, so the constant is still read -- by the checks
   * that prove the controls are gone.
   */
  PASSWORD_CAN_NOT_BE_EMPTY: 'Password can NOT be empty...',
  /**
   * Assumptions: this sits in the shared group rather than under `COTRTLIC`, because
   * two programs compose it. The transaction-type maintenance
   * program composes the same sentence at `COTRTUPC.cbl:1641`, and a group keyed by
   * one program cannot express a second file - the per-program index carries line
   * numbers and resolves its file from the group name. Filing it here keeps the
   * "keyed by origin" rule honest and lets both sites be cited; the key a consumer
   * uses is unchanged.
   */
  PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST: 'Please delete associated child records first:',
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
  YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE: 'You are already at the bottom of the page...',
  YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE: 'You are already at the top of the page...',
  YOU_ARE_AT_THE_TOP_OF_THE_PAGE: 'You are at the top of the page...',
  YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE: 'You have reached the bottom of the page...',
  YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE: 'You have reached the top of the page...',
} as const;

/**
 * Every baseline site that emits each {@link SHARED_MESSAGES} entry.
 *
 * The `satisfies` clause makes the key set structurally identical to
 * {@link SHARED_MESSAGES}, so a message added there without provenance - or provenance
 * left standing for a message that is not declared there - is a compile error rather
 * than a silent gap.
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
 * Refactoring Rationale: the group holds 27 entries when the migration inventory counts
 * 23. The inventory was produced by a scan that only accepted literals of six
 * characters or more, which is a reasonable screen for finding whole sentences but
 * wrong for this class. Four live labels - `SSN`, `State`, `Zip` and `City` - fall
 * under that threshold and were dropped by it. This class is defined by the field it
 * targets, not by length, so all 27 live labels are present; the 23 that clear six
 * characters are still exactly the inventory's set. A twenty-eighth label, `Address
 * Line 2`, is commented out at line 1614 and is excluded.
 *
 * Assumptions: this group is the single definition of these literals, and
 * `PROGRAM_MESSAGES.COACTUPC` is a reference to it rather than a second copy. Declaring
 * 23 of these labels independently in both places would leave nothing to keep them
 * equal: a corrected transcription in one would leave the other silently wrong, and the
 * byte-exactness invariant would then be true of one export and false of the other. The
 * declaration has to precede {@link PROGRAM_MESSAGES} for that reference to resolve - a
 * `const` reached from an earlier initialiser is a temporal-dead-zone error at module
 * load, not a compile-time warning.
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
  /**
   * Assumptions: the ampersand is a bare character. The baseline holds a plain `&`, not
   * an HTML entity. React escapes text children when rendering, so this must stay `&`
   * here; writing `&amp;` would display the entity literally.
   */
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
} as const satisfies Record<keyof typeof ACCOUNT_UPDATE_FIELD_LABELS, readonly number[]>;

/** Mapset file the account-view screen's painted text is transcribed from. */
export const ACCOUNT_VIEW_MAPSET_SOURCE_FILE = 'app/bms/COACTVW.bms';

/** Mapset file the main-menu screen's painted text is transcribed from. */
export const MAIN_MENU_MAPSET_SOURCE_FILE = 'app/bms/COMEN01.bms';

/**
 * The two headings `app/bms/COMEN01.bms` paints on the main-menu screen, verbatim.
 *
 * Assumptions: the option NAMES are not here - they are {@link MAIN_MENU_OPTIONS}, read
 * from `app/cpy/COMEN02Y.cpy`, because the mapset paints each option row as
 * `INITIAL=' '` and the program fills it at run time from that copybook. Only the two
 * literals the map itself carries are transcribed here.
 */
export const MAIN_MENU_HEADINGS = {
  /** Row-4 screen heading, `LENGTH=9` at `POS=(4,35)`, `COLOR=NEUTRAL` with `BRT`. */
  SCREEN: 'Main Menu',
  /** Row-20 prompt beside the option field, `LENGTH=25` at `POS=(20,15)`. */
  OPTION_PROMPT: 'Please select an option :',
} as const;

/**
 * The two key legends `app/bms/COMEN01.bms` paints, split at the run of spaces.
 *
 * Assumptions: the mapset paints ONE literal, `ENTER=Continue  F3=Exit` at `LENGTH=23`
 * in `COLOR=YELLOW`, and it is stored here as its two entries because
 * `ui/src/layout/PfKeyBar.tsx` renders one control per key. The arithmetic confirms the
 * split against the declared width: 14 + 2 + 7 = 23, the two spaces being the
 * separator rather than part of either label.
 */
export const MAIN_MENU_KEY_LABELS = {
  ENTER: 'ENTER=Continue',
  PFK03: 'F3=Exit',
} as const;

/** The mapset line each main-menu painted string is transcribed from. */
export const MAIN_MENU_PAINTED_TEXT_SOURCES = {
  headings: {
    SCREEN: 79,
    OPTION_PROMPT: 144,
  },
  keyLabels: {
    ENTER: 162,
    PFK03: 162,
  },
} as const satisfies {
  readonly headings: Record<keyof typeof MAIN_MENU_HEADINGS, number>;
  readonly keyLabels: Record<keyof typeof MAIN_MENU_KEY_LABELS, number>;
};

/**
 * The two headings `app/bms/COACTVW.bms` paints on the account-view screen, verbatim.
 *
 * Assumptions: both are `INITIAL=` literals on unnamed `DFHMDF` definitions, so neither has
 * a symbolic-map field and neither can ever arrive in a response - they are painted text, and
 * the screen renders them from here rather than declaring them beside its controls.
 *
 * Refactoring Rationale: this group, {@link ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS},
 * {@link ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS} and {@link ACCOUNT_VIEW_KEY_LABELS} were
 * declared inside `ui/src/screens/accountView/index.tsx` on the ground that a map-painted
 * label is positional and belongs beside the field it names. AAP section 0.2.1.5 settles it
 * the other way for `ui/src/screens/**` - every user-visible string a screen renders is
 * imported from this catalog - so the 31 strings moved here and the screen imports them. The
 * positional argument is answered rather than dismissed: each entry below carries the mapset
 * line its literal sits on, so the field a label names is still one lookup away, and the
 * byte-exactness guarantee now covers these strings in the one module that is reviewed for it.
 */
export const ACCOUNT_VIEW_HEADINGS = {
  /** Row-4 screen heading, `LENGTH=12` at `POS=(4,33)`, `COLOR=NEUTRAL`. */
  ACCOUNT: 'View Account',
  /** Row-11 block heading, `LENGTH=16` at `POS=(11,32)`, `COLOR=NEUTRAL`. */
  CUSTOMER: 'Customer Details',
} as const;

/**
 * The eleven account-block field labels `app/bms/COACTVW.bms` paints, in declaration order.
 *
 * Assumptions: the interior runs of spaces and the trailing spaces are part of the value and
 * are not formatting. The mapset pads each label to a fixed cell width so the colons line up
 * down the column - `Credit Limit        :` and `Current Cycle Debit :` are both `LENGTH=21` -
 * and the transcription rule for this catalog is byte-exact. A renderer may collapse the runs
 * visually; nothing may discard them.
 */
export const ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS = {
  /** Label of the account filter, the block's only unprotected field. */
  ACCOUNT_NUMBER: 'Account Number :',
  ACTIVE_STATUS: 'Active Y/N: ',
  OPEN_DATE: 'Opened:',
  CREDIT_LIMIT: 'Credit Limit        :',
  EXPIRATION_DATE: 'Expiry:',
  CASH_CREDIT_LIMIT: 'Cash credit Limit   :',
  REISSUE_DATE: 'Reissue:',
  CURRENT_BALANCE: 'Current Balance     :',
  CURRENT_CYCLE_CREDIT: 'Current Cycle Credit:',
  GROUP_ID: 'Account Group:',
  CURRENT_CYCLE_DEBIT: 'Current Cycle Debit :',
} as const;

/**
 * The eighteen customer-block field labels `app/bms/COACTVW.bms` paints, in declaration order.
 *
 * Assumptions: `ADDRESS_LINE_2` is the empty string because the mapset paints NO label for
 * `ACSADL2`. That field sits at `POS=(17,10)`, directly beneath `ACSADL1` at `POS=(16,10)`, and
 * the only label on either row is the single `Address:` at `POS=(16,1)`; the second line was
 * identified to a terminal operator by sitting under the first. That positional identification
 * is exactly what AAP gap G1 gives up, and inventing a label - or repeating `Address:` - would
 * claim the mapset paints text it does not. The empty value is therefore recorded deliberately
 * so a reader does not read it as an omission, and it is the one entry with no source line.
 */
export const ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS = {
  CUSTOMER_ID: 'Customer id  :',
  SSN: 'SSN:',
  DATE_OF_BIRTH: 'Date of birth:',
  FICO_CREDIT_SCORE: 'FICO Score:',
  FIRST_NAME: 'First Name',
  MIDDLE_NAME: 'Middle Name: ',
  LAST_NAME: 'Last Name : ',
  ADDRESS_LINE_1: 'Address:',
  STATE_CODE: 'State ',
  /** No label is painted; see the note on this group. */
  ADDRESS_LINE_2: '',
  ZIP_CODE: 'Zip',
  CITY: 'City ',
  COUNTRY_CODE: 'Country',
  PHONE_NUMBER_1: 'Phone 1:',
  GOVERNMENT_ISSUED_ID: 'Government Issued Id Ref    : ',
  PHONE_NUMBER_2: 'Phone 2:',
  EFT_ACCOUNT_ID: 'EFT Account Id: ',
  PRIMARY_CARD_HOLDER_INDICATOR: 'Primary Card Holder Y/N:',
} as const;

/**
 * The single row-24 legend label `app/bms/COACTVW.bms` paints, verbatim.
 *
 * Assumptions: the mapset paints exactly `'  F3=Exit '` - two leading spaces and one trailing
 * space, ten characters inside a `LENGTH=60` `COLOR=TURQUOISE` field - and that is the whole
 * legend. ENTER is deliberately NOT advertised even though `app/cbl/COACTVWC.cbl` L307-L308
 * admits it, so binding a label to ENTER would paint a key the terminal did not. This is why
 * the uniform legend labels `ui/src/layout/PfKeyBar.tsx` exports are not used on that screen:
 * none of PF4, PF7 or PF8 is bound there, and ENTER's wording is not uniform across the
 * mapsets in any case.
 */
export const ACCOUNT_VIEW_KEY_LABELS = {
  /** Legend for the one attention identifier this mapset paints. */
  PFK03: '  F3=Exit ',
} as const;

/**
 * The mapset line each account-view painted string is transcribed from.
 *
 * Trade-offs: provenance is structured data keyed by the same names, rather than a comment
 * above each entry, for the reason {@link SourceRef} records - with 31 entries a comment per
 * entry would restate the values, which the project's explainability rule forbids, and as data
 * the citation is machine-checkable. Each number is the line carrying the `INITIAL=` clause
 * itself, not the line the `DFHMDF` opens on, because the clause is where the bytes are.
 */
export const ACCOUNT_VIEW_PAINTED_TEXT_SOURCES = {
  headings: {
    ACCOUNT: 78,
    CUSTOMER: 202,
  },
  accountFields: {
    ACCOUNT_NUMBER: 83,
    ACTIVE_STATUS: 96,
    OPEN_DATE: 106,
    CREDIT_LIMIT: 116,
    EXPIRATION_DATE: 127,
    CASH_CREDIT_LIMIT: 137,
    REISSUE_DATE: 148,
    CURRENT_BALANCE: 158,
    CURRENT_CYCLE_CREDIT: 170,
    GROUP_ID: 181,
    CURRENT_CYCLE_DEBIT: 191,
  },
  customerFields: {
    CUSTOMER_ID: 206,
    SSN: 215,
    DATE_OF_BIRTH: 224,
    FICO_CREDIT_SCORE: 233,
    FIRST_NAME: 242,
    MIDDLE_NAME: 246,
    LAST_NAME: 250,
    ADDRESS_LINE_1: 267,
    STATE_CODE: 276,
    ADDRESS_LINE_2: null,
    ZIP_CODE: 290,
    CITY: 300,
    COUNTRY_CODE: 309,
    PHONE_NUMBER_1: 318,
    GOVERNMENT_ISSUED_ID: 325,
    PHONE_NUMBER_2: 334,
    EFT_ACCOUNT_ID: 341,
    PRIMARY_CARD_HOLDER_INDICATOR: 350,
  },
  keyLabels: {
    PFK03: 373,
  },
} as const satisfies {
  readonly headings: Record<keyof typeof ACCOUNT_VIEW_HEADINGS, number>;
  readonly accountFields: Record<keyof typeof ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS, number>;
  readonly customerFields: Record<keyof typeof ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS, number | null>;
  readonly keyLabels: Record<keyof typeof ACCOUNT_VIEW_KEY_LABELS, number>;
};

/*
 * WHY : Refactoring Rationale: the groups below carry the painted text of seven further mapsets -
 *       `COTRN00`, `COTRN01`, `COBIL00`, `CORPT00`, `COUSR00`, `COUSR01` and `COUSR03`. Each of
 *       those screens transcribed this text into its own module beside the controls it names, which
 *       AAP section 0.2.1.5 assigns to this catalog instead, and which rule T8 makes a reviewable
 *       property of ONE module rather than of seven. Nothing about the values changed in the move:
 *       leading, interior and trailing runs of spaces are still part of each string, because each
 *       string is a `DFHMDF INITIAL=` operand filling a declared cell width, and the arithmetic
 *       proving that is recorded per entry.
 * WHY : Assumptions: these groups are published AHEAD of their consumers. The seven screens still
 *       declare their own copies today and are moved onto these exports separately, so a group here
 *       that no module imports yet is a published contract rather than dead text - the state
 *       {@link MAIN_MENU_PAINTED_TEXT_SOURCES} is already in. Trade-offs: that leaves one window in
 *       which two spellings of a string exist. It is accepted because it makes the transcription and
 *       the seven screen edits reviewable independently, and the transcription is the half that has
 *       to be checked character by character against `app/bms/**`.
 * WHY : Trade-offs: the member keys are the SCREENS' own - `title`, `pageLabel`, `firstNameColumn` -
 *       and are deliberately not renamed to the upper-case form {@link ACCOUNT_VIEW_HEADINGS} uses,
 *       so this file now holds two key conventions. That cost is taken because a consumer then
 *       replaces a local declaration with an import and touches no use site; the alternative renames
 *       roughly fifty members across seven screens, which is precisely the edit that drops a string
 *       on the way.
 * WHY : Assumptions: a legend label whose wording is UNIFORM across the measured mapsets is absent
 *       from every `_KEY_LABELS` group below. `F4=Clear`, `F7=Backward` and `F8=Forward` are owned
 *       by `ui/src/layout/PfKeyBar.tsx` - the "Not in this catalog" note above states that boundary -
 *       so a screen binding one of those keys assembles its legend from both sources, and the AIDs
 *       absent from a group are named in that group's own note. Alternatives Considered: transcribing
 *       the three here as well, so that one group held a screen's whole legend. Rejected on two
 *       independent grounds: it would put a second spelling of three strings in the tree with nothing
 *       keeping the two equal, which is the drift this module exists to remove; and this module
 *       imports nothing at load time, so it cannot read them from the module that owns them either.
 * WHY : Assumptions: identical text painted by two different `DFHMDF` fields is TWO entries and
 *       never one. `ENTER=Continue` is painted by four of these seven mapsets and `F3=Back` by all
 *       seven; `ENTER=Fetch`, `First Name:`, `Last Name:`, `User Type: `, `(A=Admin, U=User)`,
 *       `(Y/N)`, `Page:` and `Sel` are each painted by two of them, and `(8 Char)` twice within one.
 *       Every entry cites its own field, so a later correction lands on the field that needs it,
 *       and no mapset's text depends on another mapset's - which matters most for ENTER, painted six
 *       different ways across the population.
 * WHY : Assumptions: each source citation is an ARRAY of lines rather than a single line, as
 *       {@link ACCOUNT_UPDATE_FIELD_LABEL_SOURCES} already is. Four of the strings below are
 *       literals BMS continues across a line boundary with a non-blank in column 72, so they occupy
 *       two lines and a single number could not cite them; the array form states one shape for all
 *       entries instead of two.
 */

/** Mapset file the transaction-browse screen's painted text is transcribed from. */
export const TRANSACTION_LIST_MAPSET_SOURCE_FILE = 'app/bms/COTRN00.bms';

/**
 * The four captions and prompts `app/bms/COTRN00.bms` paints on the transaction browse.
 *
 * Assumptions: `selectionPrompt` holds single apostrophes and one space before `list`, where the
 * mapset holds `Type ''S'' to View Transaction details from the-` continued as ` list`. Both the
 * doubled apostrophes and the continuation are BMS source encoding rather than content - a terminal
 * displays one apostrophe - and the arithmetic settles the space: the sentence is exactly the 50
 * characters the field declares as `LENGTH=50`, which it reaches only with that space present.
 */
export const TRANSACTION_LIST_LABELS = {
  /** Row-4 sub-heading, `LENGTH=17` at `POS=(4,30)`, `COLOR=NEUTRAL` with `BRT`. */
  title: 'List Transactions',
  /** Row-4 page-ordinal prompt, `LENGTH=5` at `POS=(4,65)`, `COLOR=TURQUOISE` with `BRT`. */
  pageLabel: 'Page:',
  /** Row-6 label of the starting-identifier field, `LENGTH=15` at `POS=(6,5)`, `COLOR=TURQUOISE`. */
  filterLabel: 'Search Tran ID:',
  /** Row-21 selection prompt, `LENGTH=50` at `POS=(21,12)`, `COLOR=NEUTRAL` with `BRT`. */
  selectionPrompt: "Type 'S' to View Transaction details from the list",
} as const;

/**
 * The five row-8 column headings `app/bms/COTRN00.bms` paints, in the order it paints them.
 *
 * Assumptions: the padding inside these values is the mapset centring each heading over the column
 * beneath it, and it is kept. Each literal fills its field exactly - `' Transaction ID '` is
 * 1 + 14 + 1 against `LENGTH=16` and `'     Description          '` is 5 + 11 + 10 against
 * `LENGTH=26` - so trimming them would read as tidier while discarding the alignment the terminal
 * had. A renderer may collapse the runs visually; nothing may discard them from the value.
 */
export const TRANSACTION_LIST_COLUMN_HEADERS = {
  /** Over the `SEL000n` selection cells, `LENGTH=3` at `POS=(8,2)`, `COLOR=NEUTRAL`. */
  selection: 'Sel',
  /** Over the `TRNIDnn` cells, `LENGTH=16` at `POS=(8,8)`, `COLOR=NEUTRAL`. */
  transactionId: ' Transaction ID ',
  /** Over the `TDATEnn` cells, `LENGTH=8` at `POS=(8,27)`, `COLOR=NEUTRAL`. */
  date: '  Date  ',
  /** Over the `TDESCnn` cells, `LENGTH=26` at `POS=(8,38)`, `COLOR=NEUTRAL`. */
  description: '     Description          ',
  /** Over the `TAMT00n` cells, `LENGTH=12` at `POS=(8,67)`, `COLOR=NEUTRAL`. */
  amount: '   Amount   ',
} as const;

/**
 * The two screen-owned parts of the row-24 legend `app/bms/COTRN00.bms` paints.
 *
 * Assumptions: the mapset paints ONE literal, `ENTER=Continue  F3=Back  F7=Backward  F8=Forward`,
 * `LENGTH=48` in `COLOR=YELLOW`, and the separator between parts is TWO spaces: 14 + 2 + 7 + 2 + 11
 * + 2 + 10 = 48 confirms the split against the declared width. PFK07 and PFK08 are absent because
 * their wording is uniform across every paging mapset and `ui/src/layout/PfKeyBar.tsx` owns it; a
 * renderer of this screen takes those two from there and these two from here.
 */
export const TRANSACTION_LIST_KEY_LABELS = {
  /** Submits the entry field and re-reads from the first page. */
  ENTER: 'ENTER=Continue',
  /** Returns to the main menu, which `app/cbl/COTRN00C.cbl` L123 names as the transfer target. */
  PFK03: 'F3=Back',
} as const;

/** The mapset lines each transaction-browse painted string is transcribed from. */
export const TRANSACTION_LIST_PAINTED_TEXT_SOURCES = {
  labels: {
    title: [79],
    pageLabel: [84],
    filterLabel: [94],
    selectionPrompt: [448, 449],
  },
  columnHeaders: {
    selection: [107],
    transactionId: [112],
    date: [117],
    description: [122],
    amount: [127],
  },
  keyLabels: {
    ENTER: [458, 459],
    PFK03: [458, 459],
  },
} as const satisfies {
  readonly labels: Record<keyof typeof TRANSACTION_LIST_LABELS, readonly number[]>;
  readonly columnHeaders: Record<keyof typeof TRANSACTION_LIST_COLUMN_HEADERS, readonly number[]>;
  readonly keyLabels: Record<keyof typeof TRANSACTION_LIST_KEY_LABELS, readonly number[]>;
};

/** Mapset file the transaction-detail screen's painted text is transcribed from. */
export const TRANSACTION_DETAIL_MAPSET_SOURCE_FILE = 'app/bms/COTRN01.bms';

/**
 * Row-4 caption `app/bms/COTRN01.bms` paints on the transaction-detail screen.
 *
 * Assumptions: this is the map's own `LENGTH=16` field at `POS=(4,30)` in `COLOR=NEUTRAL` with
 * `BRT`, and not the main menu's option name. The nearest catalogued neighbour is
 * {@link MAIN_MENU_OPTIONS} entry 7, `'Transaction View                   '` - a different word
 * order padded to the 35-character option width - so neither trimming nor reordering it could
 * produce this caption, and deriving one field's text from another field's padding is what this
 * separate entry avoids.
 */
export const TRANSACTION_DETAIL_TITLE = 'View Transaction';

/** Row-6 label of the lookup field, `LENGTH=14` at `POS=(6,6)`, `COLOR=TURQUOISE`. */
export const TRANSACTION_DETAIL_LOOKUP_LABEL = 'Enter Tran ID:';

/**
 * The thirteen data-field labels `app/bms/COTRN01.bms` paints, in declaration order.
 *
 * Assumptions: thirteen, and the trailing colon on each is part of the literal. Three independent
 * readings of the baseline agree on the count - the mapset paints thirteen `COLOR=TURQUOISE` labels
 * against thirteen `COLOR=BLUE` output fields, `app/cpy-bms/COTRN01.CPY` declares thirteen matching
 * `...I` members, and `app/cbl/COTRN01C.cbl` names exactly thirteen in both of its own field lists.
 *
 * Assumptions: the keys are the detail record's member names rather than the mapset's field names,
 * so a renderer reaches a label and the value it labels with one key. The mapset field each label
 * belongs to is named per entry, which keeps the correspondence auditable in the direction a reader
 * checks it - from a rendered label back to the `DFHMDF` that painted it.
 */
export const TRANSACTION_DETAIL_FIELD_LABELS = {
  /** Beside `TRNID`, `LENGTH=15` at `POS=(10,6)`. */
  transactionId: 'Transaction ID:',
  /** Beside `CARDNUM`, `LENGTH=12` at `POS=(10,45)`. */
  cardNumber: 'Card Number:',
  /** Beside `TTYPCD`, `LENGTH=8` at `POS=(12,6)`. */
  typeCode: 'Type CD:',
  /** Beside `TCATCD`, `LENGTH=12` at `POS=(12,23)`. */
  categoryCode: 'Category CD:',
  /** Beside `TRNSRC`, `LENGTH=7` at `POS=(12,46)`. */
  source: 'Source:',
  /** Beside `TDESC`, `LENGTH=12` at `POS=(14,6)`. */
  description: 'Description:',
  /** Beside `TRNAMT`, `LENGTH=7` at `POS=(16,6)`. */
  amount: 'Amount:',
  /** Beside `TORIGDT`, `LENGTH=10` at `POS=(16,31)`. */
  originTimestamp: 'Orig Date:',
  /** Beside `TPROCDT`, `LENGTH=10` at `POS=(16,57)`. */
  processTimestamp: 'Proc Date:',
  /** Beside `MID`, `LENGTH=12` at `POS=(18,6)`. */
  merchantId: 'Merchant ID:',
  /** Beside `MNAME`, `LENGTH=14` at `POS=(18,33)`. */
  merchantName: 'Merchant Name:',
  /** Beside `MCITY`, `LENGTH=14` at `POS=(20,6)`. */
  merchantCity: 'Merchant City:',
  /** Beside `MZIP`, `LENGTH=13` at `POS=(20,53)`. */
  merchantZip: 'Merchant Zip:',
} as const;

/**
 * The three screen-owned parts of the row-24 legend `app/bms/COTRN01.bms` paints.
 *
 * Assumptions: the mapset paints ONE literal, `ENTER=Fetch  F3=Back  F4=Clear  F5=Browse Tran.`,
 * `LENGTH=47` in `COLOR=YELLOW`, with TWO spaces between parts: 11 + 2 + 7 + 2 + 8 + 2 + 15 = 47.
 * PFK04 is absent because `F4=Clear` is uniform across the population and
 * `ui/src/layout/PfKeyBar.tsx` owns it. `app/cbl/COTRN01C.cbl` L112-L127 admits exactly these four
 * attention identifiers, so the painted legend and the accepted key set agree and no fifth entry is
 * missing.
 *
 * Assumptions: `PFK05` reads `Browse Tran.` including the full stop, and it is NAVIGATION rather
 * than a save even though PF5 saves on most other CardDemo screens - `app/cbl/COTRN01C.cbl`
 * L125-L127 transfers to `COTRN00C`, the browse program. The painted label is the independent
 * confirmation of that, which is why the full stop is not tidied away.
 */
export const TRANSACTION_DETAIL_KEY_LABELS = {
  /** Reads the record the lookup field addresses. */
  ENTER: 'ENTER=Fetch',
  /** Returns to the caller. */
  PFK03: 'F3=Back',
  /** Transfers to the transaction browse. */
  PFK05: 'F5=Browse Tran.',
} as const;

/** The mapset lines each transaction-detail painted string is transcribed from. */
export const TRANSACTION_DETAIL_PAINTED_TEXT_SOURCES = {
  title: [79],
  lookupLabel: [84],
  fieldLabels: {
    transactionId: [104],
    cardNumber: [117],
    typeCode: [131],
    categoryCode: [143],
    source: [155],
    description: [167],
    amount: [179],
    originTimestamp: [191],
    processTimestamp: [203],
    merchantId: [215],
    merchantName: [227],
    merchantCity: [239],
    merchantZip: [251],
  },
  keyLabels: {
    ENTER: [267, 268],
    PFK03: [267, 268],
    PFK05: [267, 268],
  },
} as const satisfies {
  readonly title: readonly number[];
  readonly lookupLabel: readonly number[];
  readonly fieldLabels: Record<keyof typeof TRANSACTION_DETAIL_FIELD_LABELS, readonly number[]>;
  readonly keyLabels: Record<keyof typeof TRANSACTION_DETAIL_KEY_LABELS, readonly number[]>;
};

/** Mapset file the bill-payment screen's painted text is transcribed from. */
export const BILL_PAY_MAPSET_SOURCE_FILE = 'app/bms/COBIL00.bms';

/**
 * Row-4 caption `app/bms/COBIL00.bms` paints on the bill-payment screen.
 *
 * Assumptions: the map paints this as its own `LENGTH=12` field at `POS=(4,35)` with
 * `ATTRB=(ASKIP,BRT)` and `COLOR=NEUTRAL`, and the twelve characters here are that literal exactly.
 *
 * Alternatives Considered: `PROGRAM_MESSAGES.COBIL00C.BILL_PAYMENT`, which reads like the obvious
 * source and is the wrong one - that entry is `'BILL PAYMENT'` in upper case and is the merchant
 * NAME the program writes into the transaction row at `app/cbl/COBIL00C.cbl` L227, a data value on a
 * record rather than a caption on a screen. Also considered and rejected: trimming
 * {@link MAIN_MENU_OPTIONS} entry 10, `'Bill Payment                       '`, which would make this
 * caption depend on the 35-character padding of a different field.
 */
export const BILL_PAY_TITLE = 'Bill Payment';

/**
 * The three literals `app/bms/COBIL00.bms` paints beside this screen's fields.
 *
 * Assumptions: the trailing spaces are part of the values and are not incidental. Each `INITIAL`
 * fills its declared field exactly - 14, 25 and 53 characters - so the balance label ends with a
 * space and the confirmation prompt ends with a space, because on the terminal the value followed
 * immediately in the next column. An editor action that strips trailing whitespace inside these
 * quotes changes what the screen paints while still reading as correct English.
 *
 * Assumptions: `confirmPrompt` ends its first sentence with a FULL STOP and not a question mark,
 * and it is a BMS continuation across two lines. Both read like transcription slips and neither is:
 * rule T8 carries user-visible text across character for character, so the stop is never corrected
 * and the continuation is rejoined without inserting anything the field does not hold - the 53
 * characters here are the `LENGTH=53` the field declares.
 */
export const BILL_PAY_FIELD_LABELS = {
  /** Beside `ACTIDIN`, `LENGTH=14` at `POS=(6,6)`, `COLOR=GREEN`. */
  accountId: 'Enter Acct ID:',
  /** Beside `CURBAL`, `LENGTH=25` at `POS=(11,6)`, `COLOR=TURQUOISE`; trailing space included. */
  currentBalance: 'Your current balance is: ',
  /** Beside `CONFIRM`, `LENGTH=53` at `POS=(15,6)`, `COLOR=TURQUOISE`; trailing space included. */
  confirmPrompt: 'Do you want to pay your balance now. Please confirm: ',
} as const;

/**
 * The domain hint `app/bms/COBIL00.bms` paints after the confirmation field, `LENGTH=5` at
 * `POS=(15,63)` in `COLOR=NEUTRAL`.
 *
 * Assumptions: this five-character literal is carried even though the single-character field it
 * annotated is replaced by a confirmation dialogue, because it still names the two answers that
 * dialogue offers. Its text is byte-identical to {@link REPORTS_CAPTIONS}`.confirmDomainHint` and is
 * a separate entry for that reason - two mapsets, two fields, two citations.
 */
export const BILL_PAY_CONFIRM_DOMAIN_HINT = '(Y/N)';

/**
 * The two screen-owned parts of the row-24 legend `app/bms/COBIL00.bms` paints.
 *
 * Assumptions: the mapset paints ONE `LENGTH=33` literal in `COLOR=YELLOW`,
 * `ENTER=Continue  F3=Back  F4=Clear`, whose parts are separated by TWO spaces:
 * 14 + 2 + 7 + 2 + 8 = 33. PFK04 is absent because `F4=Clear` is uniform across the population and
 * `ui/src/layout/PfKeyBar.tsx` owns it. The legend advertises three keys and no others, and
 * `app/cbl/COBIL00C.cbl` L125-L142 dispatches exactly those three before answering everything else
 * with the shared invalid-key sentence, so the absence of PF5, PF7, PF8 and PF12 here is the
 * baseline's own and not an omission.
 *
 * Assumptions: ENTER reads `Continue` on this mapset where others paint `Process`, and PF3 reads
 * `Back` where nine others paint `Exit`, which is why both are screen-owned rather than shared.
 */
export const BILL_PAY_KEY_LABELS = {
  /** Reads the account and, once a balance is shown, offers the payment. */
  ENTER: 'ENTER=Continue',
  /** Returns to the caller. */
  PFK03: 'F3=Back',
} as const;

/** The mapset lines each bill-payment painted string is transcribed from. */
export const BILL_PAY_PAINTED_TEXT_SOURCES = {
  title: [79],
  confirmDomainHint: [126],
  fieldLabels: {
    accountId: [84],
    currentBalance: [102],
    confirmPrompt: [113, 114],
  },
  keyLabels: {
    ENTER: [135],
    PFK03: [135],
  },
} as const satisfies {
  readonly title: readonly number[];
  readonly confirmDomainHint: readonly number[];
  readonly fieldLabels: Record<keyof typeof BILL_PAY_FIELD_LABELS, readonly number[]>;
  readonly keyLabels: Record<keyof typeof BILL_PAY_KEY_LABELS, readonly number[]>;
};

/** Mapset file the transaction-reports screen's painted text is transcribed from. */
export const REPORTS_MAPSET_SOURCE_FILE = 'app/bms/CORPT00.bms';

/**
 * Row-4 caption `app/bms/CORPT00.bms` paints on the transaction-reports screen, `LENGTH=19` at
 * `POS=(4,30)` in `COLOR=NEUTRAL` with `BRT`.
 *
 * Assumptions: this is a separate entry from {@link MAIN_MENU_OPTIONS} entry 9,
 * `'Transaction Reports                '`, even though the two carry the same nineteen characters.
 * They are different fields in different files - a menu option name in `app/cpy/COMEN02Y.cpy` that
 * the program moves into a menu row, and a caption the map paints - so deriving this one by trimming
 * the other would tie a heading to the 35-character option width it has nothing to do with.
 */
export const REPORTS_TITLE = 'Transaction Reports';

/**
 * The three report-type selector captions `app/bms/CORPT00.bms` paints, in the order the program
 * evaluates them.
 *
 * ⚠️ Assumptions: these are the CAPTIONS an operator reads beside each one-character selector and
 * they are NOT the report names. Each is its own `LENGTH=23 COLOR=TURQUOISE ATTRB=(ASKIP,BRT)`
 * field, and only `monthly` fills all 23 characters - `yearly` is 21 and `custom` is 19, so COBOL
 * pads those two at run time. The bare words the program moves into `WS-REPORT-NAME` and
 * interpolates into two of its sentences are `PROGRAM_MESSAGES.CORPT00C.MONTHLY`, `.YEARLY` and
 * `.CUSTOM`; both sets exist in the baseline and neither is derivable from the other, so conflating
 * them would either put `Monthly (Current Month)` inside a message the program spells `Monthly` or
 * strip the caption an operator uses to choose.
 *
 * Assumptions: the key order is load-bearing rather than cosmetic. `app/cbl/CORPT00C.cbl` L212-L256
 * is one `EVALUATE TRUE` testing monthly, then yearly, then custom, so the first non-blank mark wins
 * and the operator's own reading order down rows 7, 9 and 11 is the precedence order.
 */
export const REPORT_TYPE_PROMPTS = {
  /** Row-7 caption, `POS=(7,15)`; fills its `LENGTH=23` field exactly. */
  monthly: 'Monthly (Current Month)',
  /** Row-9 caption, `POS=(9,15)`; 21 characters in a `LENGTH=23` field. */
  yearly: 'Yearly (Current Year)',
  /** Row-11 caption, `POS=(11,15)`; 19 characters in a `LENGTH=23` field. */
  custom: 'Custom (Date Range)',
} as const;

/**
 * The captions and hints `app/bms/CORPT00.bms` paints around the two date bounds and the
 * confirmation.
 *
 * ⚠️ Assumptions: `endDate` carries TWO LEADING spaces. Its literal is `'  End Date :'`, declared
 * `LENGTH=12` exactly as `'Start Date :'` is, and the two spaces are the mapset's own
 * right-alignment of the shorter caption against the longer one. Trimming them would be a
 * one-character-class edit to text a screen suite reads byte for byte.
 *
 * Trade-offs: those two spaces reach the DOM verbatim and are then collapsed by HTML's default
 * whitespace handling, so the caption reads `End Date :` on screen while the string still measures
 * twelve characters. That is deliberate: the spaces are character-cell alignment on a fixed-pitch
 * 24x80 grid, which AAP gap G1 declines to reproduce, and forcing them to paint with a preformatted
 * whitespace rule would indent this caption against its sibling in a proportional face instead of
 * aligning the two colons. The verbatim guarantee is about the string this module carries, and that
 * is preserved; the terminal's column arithmetic is not.
 *
 * Assumptions: `confirmation` also ends with a space and is a BMS continuation across two lines. It
 * is a `LENGTH=59 COLOR=TURQUOISE` field, and the trailing blank separated the sentence from the
 * one-character input that followed it on the same row.
 *
 * Assumptions: `dateFormatHint` is ONE entry for a literal the mapset paints TWICE, once per bound
 * at `POS=(13,46)` and `POS=(14,46)`. Both fields are `LENGTH=12 COLOR=BLUE` and carry identical
 * text, and a bound is rendered once with its own hint, so one entry citing both lines states the
 * fact without implying the two could differ - which they cannot, being the same mask.
 */
export const REPORTS_CAPTIONS = {
  /** Row-13 caption, `LENGTH=12` at `POS=(13,15)`, `COLOR=TURQUOISE`. */
  startDate: 'Start Date :',
  /** Row-14 caption, `LENGTH=12` at `POS=(14,15)`, `COLOR=TURQUOISE`; two leading spaces in source. */
  endDate: '  End Date :',
  /** Painted once per bound, `LENGTH=12` at `POS=(13,46)` and `POS=(14,46)`, `COLOR=BLUE`. */
  dateFormatHint: '(MM/DD/YYYY)',
  /** Row-19 prompt, `LENGTH=59` at `POS=(19,6)`, `COLOR=TURQUOISE`; trailing space in source. */
  confirmation: 'The Report will be submitted for printing. Please confirm: ',
  /** Row-19 domain hint, `LENGTH=5` at `POS=(19,69)`, `COLOR=NEUTRAL`. */
  confirmDomainHint: '(Y/N)',
} as const;

/**
 * The whole row-24 legend `app/bms/CORPT00.bms` paints, split into its two parts.
 *
 * ⚠️ Assumptions: the row-24 field is ONE `LENGTH=23 COLOR=YELLOW` literal,
 * `ENTER=Continue  F3=Back`, and the two spaces between the parts are in the source:
 * 14 + 2 + 7 = 23. Splitting it lets each part sit on the control that performs it.
 *
 * Assumptions: exactly TWO entries, and unlike the other six groups in this block nothing is absent
 * from this one - the legend advertises two keys and `EVALUATE EIBAID` at `app/cbl/CORPT00C.cbl`
 * L184-L195 honours exactly `DFHENTER` and `DFHPF3`, sending every other attention identifier to the
 * shared invalid-key message. `F4=Clear` is deliberately NOT taken from the uniform set here,
 * because this mapset does not paint it and this program does not dispatch it.
 */
export const REPORTS_KEY_LABELS = {
  /** Submits the selected report type and, for a custom range, the six keyed date parts. */
  ENTER: 'ENTER=Continue',
  /** Returns to the main menu. */
  PFK03: 'F3=Back',
} as const;

/** The mapset lines each transaction-reports painted string is transcribed from. */
export const REPORTS_PAINTED_TEXT_SOURCES = {
  title: [79],
  typePrompts: {
    monthly: [93],
    yearly: [107],
    custom: [121],
  },
  captions: {
    startDate: [126],
    endDate: [165],
    dateFormatHint: [160, 199],
    confirmation: [204, 205],
    confirmDomainHint: [217],
  },
  keyLabels: {
    ENTER: [226],
    PFK03: [226],
  },
} as const satisfies {
  readonly title: readonly number[];
  readonly typePrompts: Record<keyof typeof REPORT_TYPE_PROMPTS, readonly number[]>;
  readonly captions: Record<keyof typeof REPORTS_CAPTIONS, readonly number[]>;
  readonly keyLabels: Record<keyof typeof REPORTS_KEY_LABELS, readonly number[]>;
};

/** Mapset file the user-browse screen's painted text is transcribed from. */
export const USER_LIST_MAPSET_SOURCE_FILE = 'app/bms/COUSR00.bms';

/**
 * Row-4 caption `app/bms/COUSR00.bms` paints on the user browse, `LENGTH=10` at `POS=(4,35)` in
 * `COLOR=NEUTRAL` with `BRT`.
 */
export const USER_LIST_TITLE = 'List Users';

/**
 * The eight static literals `app/bms/COUSR00.bms` paints on the user browse, with their padding.
 *
 * ⚠️ Assumptions: the padding IS part of each value and none of it may be discarded. `userIdColumn`
 * carries one TRAILING space and the two name headings are padded on both sides, because the mapset
 * sizes each heading to the twenty-character column beneath it - `'     First Name     '` is
 * 5 + 10 + 5 and `'     Last Name      '` is 5 + 9 + 6, both against `LENGTH=20`. The values are
 * therefore stored padded and only a renderer trims, so a reader comparing this file with the mapset
 * finds the same bytes.
 *
 * Assumptions: `rowActionPrompt` holds single apostrophes. The mapset source reads
 * `Type ''U'' to Update or ''D'' to Delete a User from the` continued as ` list`, and the doubled
 * apostrophes are BMS literal escaping rather than content - a terminal displays one apostrophe.
 * Reproducing the doubled form would be a transcription defect that this module's byte-exactness
 * rule would then protect; the 56 characters here are the `LENGTH=56` the field declares.
 */
export const USER_LIST_LABELS = {
  /** Row-4 page-ordinal prompt, `LENGTH=5` at `POS=(4,65)`, `COLOR=TURQUOISE` with `BRT`. */
  pageIndicator: 'Page:',
  /** Row-6 label of the search field, `LENGTH=15` at `POS=(6,5)`, `COLOR=TURQUOISE`. */
  searchUserId: 'Search User ID:',
  /** Row-8 heading of the action column, `LENGTH=3` at `POS=(8,5)`, `COLOR=NEUTRAL`. */
  selColumn: 'Sel',
  /** Row-8 heading, `LENGTH=8` at `POS=(8,12)`, `COLOR=NEUTRAL`; one trailing space. */
  userIdColumn: 'User ID ',
  /** Row-8 heading, `LENGTH=20` at `POS=(8,24)`, `COLOR=NEUTRAL`; padded to its column. */
  firstNameColumn: '     First Name     ',
  /** Row-8 heading, `LENGTH=20` at `POS=(8,48)`, `COLOR=NEUTRAL`; padded to its column. */
  lastNameColumn: '     Last Name      ',
  /** Row-8 heading of the user-type column, `LENGTH=4` at `POS=(8,72)`, `COLOR=NEUTRAL`. */
  typeColumn: 'Type',
  /** Row-21 action prompt, `LENGTH=56` at `POS=(21,12)`, `COLOR=NEUTRAL` with `BRT`. */
  rowActionPrompt: "Type 'U' to Update or 'D' to Delete a User from the list",
} as const;

/**
 * The two screen-owned parts of the row-24 legend `app/bms/COUSR00.bms` paints.
 *
 * Assumptions: the mapset paints ONE `LENGTH=48 COLOR=YELLOW` literal,
 * `ENTER=Continue  F3=Back  F7=Backward  F8=Forward`, with TWO spaces between parts, and it names
 * exactly the four keys `app/cbl/COUSR00C.cbl` L121-L133 dispatches - there is no PF4, PF5 or PF12
 * on this screen. PFK07 and PFK08 are absent from this group because their wording is uniform across
 * every paging mapset and `ui/src/layout/PfKeyBar.tsx` owns it; ENTER and PF3 are here because
 * neither key's wording is uniform, that module recording ENTER painted six ways and PF3 three.
 *
 * Assumptions: this group's two values are byte-identical to
 * {@link TRANSACTION_LIST_KEY_LABELS} and are still a separate entry, because they are a separate
 * mapset's field with its own citation - the same rule the two `Page:` and `Sel` entries follow.
 */
export const USER_LIST_KEY_LABELS = {
  /** Applies the search field and any marked action cell, then re-reads the opening page. */
  ENTER: 'ENTER=Continue',
  /** Returns to the administrative menu, replacing `XCTL PROGRAM('COADM01C')`. */
  PFK03: 'F3=Back',
} as const;

/** The mapset lines each user-browse painted string is transcribed from. */
export const USER_LIST_PAINTED_TEXT_SOURCES = {
  title: [79],
  labels: {
    pageIndicator: [84],
    searchUserId: [94],
    selColumn: [107],
    userIdColumn: [112],
    firstNameColumn: [117],
    lastNameColumn: [122],
    typeColumn: [127],
    rowActionPrompt: [447, 448],
  },
  keyLabels: {
    ENTER: [457, 458],
    PFK03: [457, 458],
  },
} as const satisfies {
  readonly title: readonly number[];
  readonly labels: Record<keyof typeof USER_LIST_LABELS, readonly number[]>;
  readonly keyLabels: Record<keyof typeof USER_LIST_KEY_LABELS, readonly number[]>;
};

/** Mapset file the add-user screen's painted text is transcribed from. */
export const USER_ADD_MAPSET_SOURCE_FILE = 'app/bms/COUSR01.bms';

/**
 * Row-4 caption `app/bms/COUSR01.bms` paints on the add-user screen.
 *
 * Assumptions: this is a BODY field and not part of the title band. It is painted at `POS=(4,35)`
 * with `ATTRB=(ASKIP,BRT)` and `COLOR=NEUTRAL` over `LENGTH=9`, below the two 40-character title
 * fields the shell owns on rows 1 and 2, so it belongs to the screen rather than to the shell. The
 * literal is eight characters in that nine-character field, so COBOL pads it with one trailing space
 * at run time that the source does not contain and this entry therefore does not carry.
 */
export const USER_ADD_CAPTION = 'Add User';

/**
 * The five field labels `app/bms/COUSR01.bms` paints on the add-user screen.
 *
 * Assumptions: the trailing space on `userType` is part of the value. That literal is declared
 * `'User Type: '` at `LENGTH=11` where the visible text is ten characters, so the eleventh is a
 * space the terminal painted, and trimming it here would be a silent edit to a user-visible string.
 *
 * Assumptions: `firstName`, `lastName` and `userType` are byte-identical to their counterparts in
 * {@link USER_DELETE_FIELD_LABELS} and are separate entries all the same, because they are a
 * different mapset's fields with their own citations - `First Name:` sits at `POS=(8,6)` here and at
 * `POS=(11,6)` there, on screens that do different things with it.
 */
export const USER_ADD_FIELD_LABELS = {
  /** `LENGTH=11` at `POS=(8,6)`, `COLOR=TURQUOISE`. */
  firstName: 'First Name:',
  /** `LENGTH=10` at `POS=(8,45)`, `COLOR=TURQUOISE`. */
  lastName: 'Last Name:',
  /** `LENGTH=8` at `POS=(11,6)`, `COLOR=TURQUOISE`. */
  userId: 'User ID:',
  /** `LENGTH=9` at `POS=(11,45)`, `COLOR=TURQUOISE`. */
  password: 'Password:',
  /** `LENGTH=11` at `POS=(14,6)`, `COLOR=TURQUOISE`; the trailing space is in source. */
  userType: 'User Type: ',
} as const;

/**
 * The three hints `app/bms/COUSR01.bms` paints beside a control, all in `COLOR=BLUE`.
 *
 * Assumptions: the two width hints are BOTH carried and they are separate entries even though their
 * text is identical, because the mapset paints two distinct fields - one after the identifier at
 * `POS=(11,24)` and one after the credential at `POS=(11,64)`. Collapsing them into one shared value
 * would make a later edit to either silently move both.
 *
 * Assumptions: the user-type hint is the ONLY place the `'A'`/`'U'` domain is advertised to an
 * operator on this screen, because `app/cbl/COUSR01C.cbl` carries no domain check for that field -
 * L142 tests it for blank and nothing else. Dropping the hint would leave the domain undiscoverable.
 */
export const USER_ADD_FIELD_HINTS = {
  /** `LENGTH=8` at `POS=(11,24)`, beside the identifier control. */
  userId: '(8 Char)',
  /** `LENGTH=8` at `POS=(11,64)`, beside the credential control. */
  password: '(8 Char)',
  /** `LENGTH=17` at `POS=(14,19)`, beside the user-type control. */
  userType: '(A=Admin, U=User)',
} as const;

/**
 * The three screen-owned parts of the row-24 legend `app/bms/COUSR01.bms` paints.
 *
 * Assumptions: the mapset paints ONE `LENGTH=43 COLOR=YELLOW` literal,
 * `ENTER=Add User  F3=Back  F4=Clear  F12=Exit`, with TWO spaces between parts:
 * 14 + 2 + 7 + 2 + 8 + 2 + 8 = 43. PFK04 is absent because `F4=Clear` is uniform across the
 * population and `ui/src/layout/PfKeyBar.tsx` owns it, while `ENTER=Add User` is unique to this
 * mapset - inheriting a generic submit label would mislabel the one control on the screen that
 * writes.
 */
export const USER_ADD_KEY_LABELS = {
  /** Creates the user from the four submitted values. */
  ENTER: 'ENTER=Add User',
  /** Returns to the administrative menu. */
  PFK03: 'F3=Back',
  /**
   * Painted by the mapset in normal intensity, and answered as an unaccepted key.
   *
   * Assumptions: the label is carried because `app/bms/COUSR01.bms` L155-L159 paints the whole row-24
   * legend as ONE `ATTRB=(ASKIP,NORM)` field that includes it, so it is on the glass on every turn.
   * The KEY, though, is not dispatched: `app/cbl/COUSR01C.cbl` L90-L102 has arms for `DFHENTER`,
   * `DFHPF3` and `DFHPF4` only, so `DFHPF12` reaches `WHEN OTHER` and is answered with
   * `CCDA-MSG-INVALID-KEY`. ⚠️ Refactoring Rationale: this member previously read "Signs off", which
   * described a behaviour the screen used to have and the reference never had; the value is unchanged
   * and only the claim about it is corrected.
   */
  PFK12: 'F12=Exit',
} as const;

/** The mapset lines each add-user painted string is transcribed from. */
export const USER_ADD_PAINTED_TEXT_SOURCES = {
  caption: [79],
  fieldLabels: {
    firstName: [83],
    lastName: [96],
    userId: [110],
    password: [125],
    userType: [140],
  },
  fieldHints: {
    userId: [120],
    password: [135],
    userType: [150],
  },
  keyLabels: {
    ENTER: [159],
    PFK03: [159],
    PFK12: [159],
  },
} as const satisfies {
  readonly caption: readonly number[];
  readonly fieldLabels: Record<keyof typeof USER_ADD_FIELD_LABELS, readonly number[]>;
  readonly fieldHints: Record<keyof typeof USER_ADD_FIELD_HINTS, readonly number[]>;
  readonly keyLabels: Record<keyof typeof USER_ADD_KEY_LABELS, readonly number[]>;
};

/** Mapset file the delete-user screen's painted text is transcribed from. */
export const USER_DELETE_MAPSET_SOURCE_FILE = 'app/bms/COUSR03.bms';

/**
 * Row-4 caption `app/bms/COUSR03.bms` paints on the delete-user screen.
 *
 * Assumptions: this is a BODY field and not part of the title band. It is painted at `POS=(4,35)`
 * with `ATTRB=(ASKIP,BRT)` and `COLOR=NEUTRAL` over `LENGTH=11`, which the eleven characters fill
 * exactly, below the two 40-character title fields the shell owns on rows 1 and 2.
 */
export const USER_DELETE_CAPTION = 'Delete User';

/**
 * The four field labels `app/bms/COUSR03.bms` paints on the delete-user screen.
 *
 * Assumptions: the trailing space on `userType` is part of the value, declared `'User Type: '` at
 * `LENGTH=11` where the visible text is ten characters. Rule T8 is character-exact, so trimming it
 * would be a silent edit to a user-visible string.
 *
 * Assumptions: `userId` reads `Enter User ID:` and is painted in `COLOR=GREEN` rather than the
 * `COLOR=TURQUOISE` of the three display labels, because it names the one enterable control on the
 * screen. That distinction is recorded per entry so a renderer resolves the two colour roles from
 * the mapset rather than from the group.
 */
export const USER_DELETE_FIELD_LABELS = {
  /** Beside `USRIDIN`, `LENGTH=14` at `POS=(6,6)`, `COLOR=GREEN`. */
  userId: 'Enter User ID:',
  /** Beside `FNAME`, `LENGTH=11` at `POS=(11,6)`, `COLOR=TURQUOISE`. */
  firstName: 'First Name:',
  /** Beside `LNAME`, `LENGTH=10` at `POS=(13,6)`, `COLOR=TURQUOISE`. */
  lastName: 'Last Name:',
  /** Beside `USRTYPE`, `LENGTH=11` at `POS=(15,6)`, `COLOR=TURQUOISE`; trailing space in source. */
  userType: 'User Type: ',
} as const;

/**
 * The one hint `app/bms/COUSR03.bms` paints beside a value, `LENGTH=17` at `POS=(15,19)` in
 * `COLOR=BLUE`.
 *
 * Assumptions: this is the ONLY place the `'A'`/`'U'` domain is named to an operator on this screen.
 * `app/cbl/COUSR03C.cbl` performs no domain check on the user type anywhere - the field is read from
 * the record and displayed - so dropping the hint would leave the two characters undecodable to
 * somebody reading the screen. Its text is byte-identical to
 * {@link USER_ADD_FIELD_HINTS}`.userType` and is a separate entry for the reason that group records.
 */
export const USER_DELETE_USER_TYPE_HINT = '(A=Admin, U=User)';

/**
 * The three screen-owned parts of the row-24 legend `app/bms/COUSR03.bms` paints.
 *
 * Assumptions: the mapset paints ONE `COLOR=YELLOW` literal,
 * `ENTER=Fetch  F3=Back  F4=Clear  F5=Delete`, whose 41 characters sit in a `LENGTH=58` field with
 * TWO spaces between parts: 11 + 2 + 7 + 2 + 8 + 2 + 9 = 41. PFK04 is absent because `F4=Clear` is
 * uniform across the population and `ui/src/layout/PfKeyBar.tsx` owns it; ENTER, PF3 and PF5 are
 * here because this mapset says `F3=Back` where its update sibling says `F3=Save&&Exit` and
 * `F5=Delete` where that one says `F5=Save`, so inheriting a default for either would mislabel a
 * control.
 *
 * Assumptions: there is NO PF12 label, and the absence is the baseline's. `app/cbl/COUSR03C.cbl`
 * L123-L125 binds `DFHPF12` while the row-24 literal advertises only four actions, so the key works
 * and is not advertised - a renderer registers that binding with no label rather than inventing one.
 *
 * Assumptions: these three values are the DECODED form, which for this mapset is the source form -
 * BMS is assembler macro source in which `&` opens a variable symbol, so a legend containing an
 * ampersand is written doubled there, and this legend contains none. `decodeBmsLegendText` in
 * `ui/src/layout/PfKeyBar.tsx` is documented as idempotent, so a renderer may keep applying it to
 * these values uniformly and still get these bytes; this module cannot apply it itself, because it
 * imports nothing at load time.
 */
export const USER_DELETE_KEY_LABELS = {
  /** Reads the user the identifier control addresses. */
  ENTER: 'ENTER=Fetch',
  /** Returns to the caller. */
  PFK03: 'F3=Back',
  /** Confirms and performs the deletion. */
  PFK05: 'F5=Delete',
} as const;

/** The mapset lines each delete-user painted string is transcribed from. */
export const USER_DELETE_PAINTED_TEXT_SOURCES = {
  caption: [79],
  userTypeHint: [139],
  fieldLabels: {
    userId: [84],
    firstName: [102],
    lastName: [115],
    userType: [129],
  },
  keyLabels: {
    ENTER: [148],
    PFK03: [148],
    PFK05: [148],
  },
} as const satisfies {
  readonly caption: readonly number[];
  readonly userTypeHint: readonly number[];
  readonly fieldLabels: Record<keyof typeof USER_DELETE_FIELD_LABELS, readonly number[]>;
  readonly keyLabels: Record<keyof typeof USER_DELETE_KEY_LABELS, readonly number[]>;
};

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
     * Alternatives Considered: de-duplicating this against
     * `COTRTLIC.NO_PREVIOUS_PAGES_TO_DISPLAY`. The two differ only in letter case. Any de-duplication that compares
     * case-insensitively would keep one and drop the other, changing what one of the two
     * screens renders.
     */
    NO_PREVIOUS_PAGES_TO_DISPLAY: 'NO PREVIOUS PAGES TO DISPLAY',
    /**
     * Alternatives Considered: merging this with
     * `COTRTLIC.NO_MORE_PAGES_TO_DISPLAY`. The two differ only in letter case, so a case-insensitive
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
     * Assumptions: the trailing space is part of the value, because the COBOL literal at
     * `app/cbl/COMEN01C.cbl` L140 ends with one. Two places render this constant and
     * neither trims it: `ui/src/routes/guards.tsx` shows it when `RequireAdmin` refuses
     * an authenticated non-administrator a route, and `ui/src/screens/menu/index.tsx`
     * shows it when the main menu refuses a non-administrator an admin-only OPTION --
     * which is the arm the baseline itself wrote it for.
     *
     * Refactoring Rationale: an earlier note said `router.tsx` "compares against" this
     * constant. Nothing compares against it anywhere; it is rendered. The distinction
     * matters, because a reader who believed a comparison existed could reasonably trim
     * the byte here and "fix" the comparison at its imagined call site, leaving two
     * rendered surfaces silently changed instead. Alternatives Considered: trimming reads
     * as tidier and breaks the two screen tests that assert the rendered text.
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
    SELECT_A_REPORT_TYPE_TO_PRINT_REPORT: 'Select a report type to print report...',
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
     * Assumptions: the lower-case "transaction" is preserved. The shared
     * `SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION` spells it with a capital T at its
     * three sites, while this program spells it lower-case at all three of its own.
     * Normalising either way alters text that a golden-master comparison reads.
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
    ACCOUNT_OR_CARD_NUMBER_MUST_BE_ENTERED: 'Account or Card Number must be entered...',
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
    AMOUNT_SHOULD_BE_IN_FORMAT_99999999_99: 'Amount should be in format -99999999.99',
    ORIG_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD: 'Orig Date should be in format YYYY-MM-DD',
    PROC_DATE_SHOULD_BE_IN_FORMAT_YYYY_MM_DD: 'Proc Date should be in format YYYY-MM-DD',
    ORIG_DATE_NOT_A_VALID_DATE: 'Orig Date - Not a valid date...',
    PROC_DATE_NOT_A_VALID_DATE: 'Proc Date - Not a valid date...',
    MERCHANT_ID_MUST_BE_NUMERIC: 'Merchant ID must be Numeric...',
    UNABLE_TO_LOOKUP_ACCT_IN_XREF_AIX_FILE: 'Unable to lookup Acct in XREF AIX file...',
    CARD_NUMBER_NOT_FOUND: 'Card Number NOT found...',
    /**
     * Assumptions: the hash is left as-is. It is a literal character in the baseline
     * standing for the word "number", not a substitution marker. Treating it as a
     * placeholder and interpolating a card number into it would invent text the
     * mainframe never displays - and would leak a card number into an error message.
     */
    UNABLE_TO_LOOKUP_CARD_NUM_IN_XREF_FILE: 'Unable to lookup Card # in XREF file...',
    UNABLE_TO_ADD_TRANSACTION: 'Unable to Add Transaction...',
  },
  /**
   * transaction-type list and update -
   * `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` (5 messages exclusive to it).
   *
   * Trade-offs: this group holds 5 of the program's 16 message literals. Ten of the
   * other eleven exist to carry a Db2 diagnostic to the screen - three declared cursor
   * names, the physical `TRANSACTION_TYPE` table name, a fetch failure naming a cursor,
   * the word "Deadlock", and two sentence fragments that only complete once an
   * `SQLCODE` is appended. Under the audience policy in the module header those are
   * diagnostics, so they are registered in {@link REDACTED_DIAGNOSTICS} with the
   * verbatim baseline message the target shows instead, and none of their text is
   * exported to a browser bundle. The eleventh, the child-records instruction, is
   * operator text that a second program also emits, so it moved to {@link
   * SHARED_MESSAGES}. The five kept here name no schema object and stand as complete
   * sentences on their own.
   */
  COTRTLIC: {
    TYPE_CODE_FILTER_IF_SUPPLIED_MUST_BE_A_2_DIGIT_NUMBER:
      'TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER',
    NO_RECORDS_FOUND_FOR_THESE_FILTER_CONDITIONS: 'No Records found for these filter conditions',
    NO_PREVIOUS_PAGES_TO_DISPLAY: 'No previous pages to display',
    NO_MORE_PAGES_TO_DISPLAY: 'No more pages to display',
    /**
     * Assumptions: there is a space on both sides of the question mark. Both are in the
     * baseline literal. The trailing one separated this text from the `SQLCODE` the
     * baseline appended; the target appends nothing, and the space is still transcribed
     * because the invariant of this module is the source bytes, not the bytes the
     * target happens to need.
     */
    RECORD_NOT_FOUND_DELETED_BY_OTHERS: 'Record not found. Deleted by others ? ',
  },
  /** transaction-type maintenance - `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` (1 message). */
  COTRTUPC: {
    TRAN_TYPE_CODE: 'Tran Type code',
  },
  /** user list - `app/cbl/COUSR00C.cbl` (1 message). */
  COUSR00C: {
    INVALID_SELECTION_VALID_VALUES_ARE_U_AND_D: 'Invalid selection. Valid values are U and D',
  },
  /** user add - `app/cbl/COUSR01C.cbl` (2 messages). */
  COUSR01C: {
    USER_ID_ALREADY_EXIST: 'User ID already exist...',
    UNABLE_TO_ADD_USER: 'Unable to Add User...',
  },
  /** user update - `app/cbl/COUSR02C.cbl` (2 messages). */
  COUSR02C: {
    PLEASE_MODIFY_TO_UPDATE: 'Please modify to update ...',
    PRESS_PF5_KEY_TO_SAVE_YOUR_UPDATES: 'Press PF5 key to save your updates ...',
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
 * Trade-offs: the suffixes are modelled separately from the labels rather than as
 * pre-joined sentences. The baseline pairs labels and suffixes at run time according to
 * which edit failed, so enumerating every pairing would produce hundreds of
 * combinations, most of which the program can never emit. Keeping the two axes apart
 * reproduces the same set of reachable messages with the same pieces the COBOL uses.
 *
 * Assumptions: the capital "A" in the three "must be A n digit number." suffixes is
 * transcribed as the baseline holds it, as is the upper-case wording of the filter
 * messages. Both read as typing slips and neither is one to repair here: `app/**` is the
 * oracle these strings are compared against, so a tidier spelling would diverge from
 * it.
 */
/**
 * The copybook that holds the date edits, cited by the date suffixes' provenance entries.
 *
 * Assumptions: it is a copybook and not a program, so it cannot be named through
 * {@link PROGRAM_SOURCE_FILES} -- that index maps ONLINE PROGRAM names to their files and a
 * copybook has no transaction behind it. The date edits are procedure-division code the four
 * date fields share, so citing the copybook is what lets one entry serve all four call sites.
 */
const DATE_EDIT_COPYBOOK = 'app/cpy/CSUTLDPY.cpy';

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
  PREFIX_CODE_MUST_BE_A_3_DIGIT_NUMBER: ': Prefix code must be A 3 digit number.',
  PREFIX_CODE_CANNOT_BE_ZERO: ': Prefix code cannot be zero',
  LINE_NUMBER_CODE_MUST_BE_SUPPLIED: ': Line number code must be supplied.',
  LINE_NUMBER_CODE_MUST_BE_A_4_DIGIT_NUMBER: ': Line number code must be A 4 digit number.',
  LINE_NUMBER_CODE_CANNOT_BE_ZERO: ': Line number code cannot be zero',
  SHOULD_NOT_BE_000_666_OR_BETWEEN_900_AND_999: ': should not be 000, 666, or between 900 and 999',
  IS_NOT_A_VALID_STATE_CODE: ': is not a valid state code',
  SHOULD_BE_BETWEEN_300_AND_850: ': should be between 300 and 850',
  MUST_BE_NUMERIC: ' must be numeric.',
  /*
   * WHY : Assumptions: the eleven date suffixes below come from `app/cpy/CSUTLDPY.cpy` rather
   *       than from a program, because the date edits are a copybook of PROCEDURE code that
   *       `COACTUPC` performs for each of its four dates -- so one transcription serves all
   *       four, exactly as one copybook serves all four in the baseline.
   *       Assumptions: their punctuation is wildly inconsistent and every inconsistency is
   *       transcribed. Three carry a space before the colon, two carry none, the day sentence
   *       spells `day` in lower case where its siblings capitalise, and the future-date
   *       sentence ends in a trailing space. Each reads as a typing slip and none is one to
   *       repair here, for the reason the group note above gives: `app/**` is the oracle these
   *       strings are compared against.
   */
  YEAR_MUST_BE_SUPPLIED: ' : Year must be supplied.',
  MUST_BE_4_DIGIT_NUMBER: ' must be 4 digit number.',
  CENTURY_IS_NOT_VALID: ' : Century is not valid.',
  MONTH_MUST_BE_SUPPLIED: ' : Month must be supplied.',
  MONTH_MUST_BE_A_NUMBER_BETWEEN_1_AND_12: ': Month must be a number between 1 and 12.',
  DAY_MUST_BE_SUPPLIED: ' : Day must be supplied.',
  DAY_MUST_BE_A_NUMBER_BETWEEN_1_AND_31: ':day must be a number between 1 and 31.',
  CANNOT_HAVE_31_DAYS_IN_THIS_MONTH: ':Cannot have 31 days in this month.',
  CANNOT_HAVE_30_DAYS_IN_THIS_MONTH: ':Cannot have 30 days in this month.',
  NOT_A_LEAP_YEAR_CANNOT_HAVE_29_DAYS_IN_THIS_MONTH:
    ':Not a leap year.Cannot have 29 days in this month.',
  CANNOT_BE_IN_THE_FUTURE: ':cannot be in the future ',
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
  CAN_HAVE_ALPHABETS_ONLY: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [1941, 2047] }],
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
  AREA_CODE_MUST_BE_SUPPLIED: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2254] }],
  AREA_CODE_MUST_BE_A_3_DIGIT_NUMBER: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2272] }],
  AREA_CODE_CANNOT_BE_ZERO: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2286] }],
  NOT_VALID_NORTH_AMERICA_GENERAL_PURPOSE_AREA_CODE: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2306] },
  ],
  PREFIX_CODE_MUST_BE_SUPPLIED: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2325] }],
  PREFIX_CODE_MUST_BE_A_3_DIGIT_NUMBER: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2343] }],
  PREFIX_CODE_CANNOT_BE_ZERO: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2357] }],
  LINE_NUMBER_CODE_MUST_BE_SUPPLIED: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2378] }],
  LINE_NUMBER_CODE_MUST_BE_A_4_DIGIT_NUMBER: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2396] },
  ],
  LINE_NUMBER_CODE_CANNOT_BE_ZERO: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2410] }],
  SHOULD_NOT_BE_000_666_OR_BETWEEN_900_AND_999: [
    { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2457] },
  ],
  IS_NOT_A_VALID_STATE_CODE: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2503] }],
  SHOULD_BE_BETWEEN_300_AND_850: [{ file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2523] }],
  MUST_BE_NUMERIC: [{ file: PROGRAM_SOURCE_FILES.COTRTUPC, lines: [943] }],
  YEAR_MUST_BE_SUPPLIED: [{ file: DATE_EDIT_COPYBOOK, lines: [33] }],
  MUST_BE_4_DIGIT_NUMBER: [{ file: DATE_EDIT_COPYBOOK, lines: [49] }],
  CENTURY_IS_NOT_VALID: [{ file: DATE_EDIT_COPYBOOK, lines: [73] }],
  MONTH_MUST_BE_SUPPLIED: [{ file: DATE_EDIT_COPYBOOK, lines: [92] }],
  MONTH_MUST_BE_A_NUMBER_BETWEEN_1_AND_12: [{ file: DATE_EDIT_COPYBOOK, lines: [108, 136] }],
  DAY_MUST_BE_SUPPLIED: [{ file: DATE_EDIT_COPYBOOK, lines: [155] }],
  DAY_MUST_BE_A_NUMBER_BETWEEN_1_AND_31: [{ file: DATE_EDIT_COPYBOOK, lines: [174, 188] }],
  CANNOT_HAVE_31_DAYS_IN_THIS_MONTH: [{ file: DATE_EDIT_COPYBOOK, lines: [212] }],
  CANNOT_HAVE_30_DAYS_IN_THIS_MONTH: [{ file: DATE_EDIT_COPYBOOK, lines: [226] }],
  NOT_A_LEAP_YEAR_CANNOT_HAVE_29_DAYS_IN_THIS_MONTH: [{ file: DATE_EDIT_COPYBOOK, lines: [253] }],
  CANNOT_BE_IN_THE_FUTURE: [{ file: DATE_EDIT_COPYBOOK, lines: [354] }],
} as const satisfies Record<keyof typeof FIELD_VALIDATION_SUFFIXES, readonly SourceRef[]>;

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
 * Assumptions: the two `DID-NOT-FIND-ACCT-IN-CARDXREF` keys carry a line suffix because
 * `COACTUPC` declares that condition name **twice** on the same field, at lines 498 and
 * 514, with different text. Both literals are reachable, so neither may be dropped, and
 * one key cannot hold both; the declaration line disambiguates them and the `line` field
 * on each entry records which is which. `app/**` is reference-only, so the duplication
 * is transcribed rather than reconciled.
 */
export const STATUS_MESSAGES = {
  COACTUPC: {
    FOUND_ACCOUNT_DATA: {
      text: 'Details of selected account shown above',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 467,
    },
    PROMPT_FOR_SEARCH_KEYS: {
      text: 'Enter or update id of account to update',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 469,
    },
    PROMPT_FOR_CHANGES: {
      text: 'Update account details presented above.',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 471,
    },
    PROMPT_FOR_CONFIRMATION: {
      text: 'Changes validated.Press F5 to save',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 473,
    },
    CONFIRM_UPDATE_SUCCESS: {
      text: 'Changes committed to database',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 475,
    },
    INFORM_FAILURE: {
      text: 'Changes unsuccessful. Please try again',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 477,
    },
    WS_EXIT_MESSAGE: {
      text: 'PF03 pressed.Exiting              ',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 482,
    },
    WS_PROMPT_FOR_ACCT: {
      text: 'Account number not provided',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 484,
    },
    WS_PROMPT_FOR_LASTNAME: {
      text: 'Last name not provided',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 486,
    },
    WS_NAME_MUST_BE_ALPHA: {
      text: 'Name can only contain alphabets and spaces',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 488,
    },
    NO_SEARCH_CRITERIA_RECEIVED: {
      text: 'No input received',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 490,
    },
    NO_CHANGES_DETECTED: {
      text: 'No change detected with respect to values fetched.',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 492,
    },
    SEARCHED_ACCT_ZEROES: {
      text: 'Account number must be a non zero 11 digit number',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 494,
    },
    SEARCHED_ACCT_NOT_NUMERIC: {
      text: 'Account number must be a non zero 11 digit number',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 496,
    },
    DID_NOT_FIND_ACCT_IN_CARDXREF__L498: {
      text: 'Did not find this account in account card xref file',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 498,
    },
    DID_NOT_FIND_ACCT_IN_ACCTDAT: {
      text: 'Did not find this account in account master file',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 500,
    },
    DID_NOT_FIND_CUST_IN_CUSTDAT: {
      text: 'Did not find associated customer in master file',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 502,
    },
    ACCT_STATUS_MUST_BE_YES_NO: {
      text: 'Account Active Status must be Y or N',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 504,
    },
    CRED_LIMIT_IS_BLANK: {
      text: 'Credit Limit must be supplied',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 506,
    },
    CRED_LIMIT_IS_NOT_VALID: {
      text: 'Credit Limit is not valid',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 508,
    },
    THIS_MONTH_NOT_VALID: {
      text: 'Card expiry month must be between 1 and 12',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 510,
    },
    THIS_YEAR_NOT_VALID: {
      text: 'Invalid card expiry year',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 512,
    },
    DID_NOT_FIND_ACCT_IN_CARDXREF__L514: {
      text: 'Did not find this account in cards database',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 514,
    },
    DID_NOT_FIND_ACCTCARD_COMBO: {
      text: 'Did not find cards for this search condition',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 516,
    },
    COULD_NOT_LOCK_ACCT_FOR_UPDATE: {
      text: 'Could not lock account record for update',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 518,
    },
    COULD_NOT_LOCK_CUST_FOR_UPDATE: {
      text: 'Could not lock customer record for update',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 520,
    },
    DATA_WAS_CHANGED_BEFORE_UPDATE: {
      text: 'Record changed by some one else. Please review',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 522,
    },
    LOCKED_BUT_UPDATE_FAILED: {
      text: 'Update of record failed',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 524,
    },
    XREF_READ_ERROR: {
      text: 'Error reading Card Data File',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 526,
    },
    CODING_TO_BE_DONE: {
      text: 'Looks Good.... so far',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 528,
    },
  },
  COACTVWC: {
    WS_PROMPT_FOR_INPUT: {
      text: 'Enter or update id of account to display',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 114,
    },
    WS_INFORM_OUTPUT: {
      text: 'Displaying details of given Account',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 116,
    },
    WS_EXIT_MESSAGE: {
      text: 'PF03 pressed.Exiting              ',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 120,
    },
    WS_PROMPT_FOR_ACCT: {
      text: 'Account number not provided',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 122,
    },
    NO_SEARCH_CRITERIA_RECEIVED: {
      text: 'No input received',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 124,
    },
    SEARCHED_ACCT_ZEROES: {
      text: 'Account number must be a non zero 11 digit number',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 126,
    },
    SEARCHED_ACCT_NOT_NUMERIC: {
      text: 'Account number must be a non zero 11 digit number',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 128,
    },
    DID_NOT_FIND_ACCT_IN_CARDXREF: {
      text: 'Did not find this account in account card xref file',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 130,
    },
    DID_NOT_FIND_ACCT_IN_ACCTDAT: {
      text: 'Did not find this account in account master file',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 132,
    },
    DID_NOT_FIND_CUST_IN_CUSTDAT: {
      text: 'Did not find associated customer in master file',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 134,
    },
    XREF_READ_ERROR: {
      text: 'Error reading account card xref File',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 136,
    },
    CODING_TO_BE_DONE: {
      text: 'Looks Good.... so far',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 138,
    },
  },
  COCRDLIC: {
    WS_INFORM_REC_ACTIONS: {
      text: 'TYPE S FOR DETAIL, U TO UPDATE ANY RECORD',
      field: 'WS-INFO-MSG',
      declaredWidth: 45,
      line: 116,
    },
    WS_EXIT_MESSAGE: {
      text: 'PF03 PRESSED.EXITING',
      field: 'WS-ERROR-MSG',
      declaredWidth: 75,
      line: 120,
    },
    WS_NO_RECORDS_FOUND: {
      text: 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.',
      field: 'WS-ERROR-MSG',
      declaredWidth: 75,
      line: 122,
    },
    WS_MORE_THAN_1_ACTION: {
      text: 'PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE',
      field: 'WS-ERROR-MSG',
      declaredWidth: 75,
      line: 124,
    },
    WS_INVALID_ACTION_CODE: {
      text: 'INVALID ACTION CODE',
      field: 'WS-ERROR-MSG',
      declaredWidth: 75,
      line: 126,
    },
  },
  COCRDSLC: {
    FOUND_CARDS_FOR_ACCOUNT: {
      text: '   Displaying requested details',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 130,
    },
    WS_PROMPT_FOR_INPUT: {
      text: 'Please enter Account and Card Number',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 132,
    },
    WS_EXIT_MESSAGE: {
      text: 'PF03 pressed.Exiting              ',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 137,
    },
    WS_PROMPT_FOR_ACCT: {
      text: 'Account number not provided',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 139,
    },
    WS_PROMPT_FOR_CARD: {
      text: 'Card number not provided',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 141,
    },
    NO_SEARCH_CRITERIA_RECEIVED: {
      text: 'No input received',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 143,
    },
    SEARCHED_ACCT_ZEROES: {
      text: 'Account number must be a non zero 11 digit number',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 145,
    },
    SEARCHED_ACCT_NOT_NUMERIC: {
      text: 'Account number must be a non zero 11 digit number',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 147,
    },
    SEARCHED_CARD_NOT_NUMERIC: {
      text: 'Card number if supplied must be a 16 digit number',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 149,
    },
    DID_NOT_FIND_ACCT_IN_CARDXREF: {
      text: 'Did not find this account in cards database',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 152,
    },
    DID_NOT_FIND_ACCTCARD_COMBO: {
      text: 'Did not find cards for this search condition',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 154,
    },
    XREF_READ_ERROR: {
      text: 'Error reading Card Data File',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 156,
    },
    CODING_TO_BE_DONE: {
      text: 'Looks Good.... so far',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 158,
    },
  },
  COCRDUPC: {
    FOUND_CARDS_FOR_ACCOUNT: {
      text: 'Details of selected card shown above',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 161,
    },
    PROMPT_FOR_SEARCH_KEYS: {
      text: 'Please enter Account and Card Number',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 163,
    },
    PROMPT_FOR_CHANGES: {
      text: 'Update card details presented above.',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 165,
    },
    PROMPT_FOR_CONFIRMATION: {
      text: 'Changes validated.Press F5 to save',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 167,
    },
    CONFIRM_UPDATE_SUCCESS: {
      text: 'Changes committed to database',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 169,
    },
    INFORM_FAILURE: {
      text: 'Changes unsuccessful. Please try again',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 171,
    },
    WS_EXIT_MESSAGE: {
      text: 'PF03 pressed.Exiting              ',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 176,
    },
    WS_PROMPT_FOR_ACCT: {
      text: 'Account number not provided',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 178,
    },
    WS_PROMPT_FOR_CARD: {
      text: 'Card number not provided',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 180,
    },
    WS_PROMPT_FOR_NAME: {
      text: 'Card name not provided',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 182,
    },
    WS_NAME_MUST_BE_ALPHA: {
      text: 'Card name can only contain alphabets and spaces',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 184,
    },
    NO_SEARCH_CRITERIA_RECEIVED: {
      text: 'No input received',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 186,
    },
    NO_CHANGES_DETECTED: {
      text: 'No change detected with respect to values fetched.',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 188,
    },
    SEARCHED_ACCT_ZEROES: {
      text: 'Account number must be a non zero 11 digit number',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 190,
    },
    SEARCHED_ACCT_NOT_NUMERIC: {
      text: 'Account number must be a non zero 11 digit number',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 192,
    },
    SEARCHED_CARD_NOT_NUMERIC: {
      text: 'Card number if supplied must be a 16 digit number',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 194,
    },
    CARD_STATUS_MUST_BE_YES_NO: {
      text: 'Card Active Status must be Y or N',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 196,
    },
    CARD_EXPIRY_MONTH_NOT_VALID: {
      text: 'Card expiry month must be between 1 and 12',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 198,
    },
    CARD_EXPIRY_YEAR_NOT_VALID: {
      text: 'Invalid card expiry year',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 200,
    },
    DID_NOT_FIND_ACCT_IN_CARDXREF: {
      text: 'Did not find this account in cards database',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 202,
    },
    DID_NOT_FIND_ACCTCARD_COMBO: {
      text: 'Did not find cards for this search condition',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 204,
    },
    COULD_NOT_LOCK_FOR_UPDATE: {
      text: 'Could not lock record for update',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 206,
    },
    DATA_WAS_CHANGED_BEFORE_UPDATE: {
      text: 'Record changed by some one else. Please review',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 208,
    },
    LOCKED_BUT_UPDATE_FAILED: {
      text: 'Update of record failed',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 210,
    },
    XREF_READ_ERROR: {
      text: 'Error reading Card Data File',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 212,
    },
    CODING_TO_BE_DONE: {
      text: 'Looks Good.... so far',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 214,
    },
  },
  COTRTLIC: {
    WS_INFORM_REC_ACTIONS: {
      text: 'Type U to update, D to delete any record',
      field: 'WS-INFO-MSG',
      declaredWidth: 45,
      line: 240,
    },
    WS_INFORM_DELETE: {
      text: 'Delete HIGHLIGHTED row ? Press F10 to confirm',
      field: 'WS-INFO-MSG',
      declaredWidth: 45,
      line: 242,
    },
    WS_INFORM_UPDATE: {
      text: 'Update HIGHLIGHTED row. Press F10 to save',
      field: 'WS-INFO-MSG',
      declaredWidth: 45,
      line: 244,
    },
    WS_INFORM_DELETE_SUCCESS: {
      text: 'HIGHLIGHTED row deleted.Hit Enter to continue',
      field: 'WS-INFO-MSG',
      declaredWidth: 45,
      line: 246,
    },
    WS_INFORM_UPDATE_SUCCESS: {
      text: 'HIGHLIGHTED row was updated',
      field: 'WS-INFO-MSG',
      declaredWidth: 45,
      line: 248,
    },
    WS_EXIT_MESSAGE: {
      text: 'PF03 pressed. Exiting',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 252,
    },
    WS_MESG_NO_RECORDS_FOUND: {
      text: 'No records found for this search condition.',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 254,
    },
    WS_MESG_NO_MORE_RECORDS: {
      text: 'No more pages for these search conditions',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 256,
    },
    WS_MESG_MORE_THAN_1_ACTION: {
      text: 'Please select only 1 action',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 258,
    },
    WS_MESG_INVALID_ACTION_CODE: {
      text: 'Action code selected is invalid',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 260,
    },
    WS_MESG_NO_CHANGES_DETECTED: {
      text: 'No change detected with respect to database values.',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 262,
    },
  },
  COTRTUPC: {
    FOUND_TRANTYPE_DATA: {
      text: 'Selected transaction type shown above',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 146,
    },
    PROMPT_FOR_SEARCH_KEYS: {
      text: 'Enter transaction type to be maintained',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 148,
    },
    PROMPT_CREATE_NEW_RECORD: {
      text: 'Press F05 to add. F12 to cancel',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 150,
    },
    PROMPT_DELETE_CONFIRM: {
      text: 'Delete this record ? Press F4 to confirm',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 152,
    },
    CONFIRM_DELETE_SUCCESS: {
      text: 'Delete successful.',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 154,
    },
    PROMPT_FOR_CHANGES: {
      text: 'Update transaction type details shown.',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 156,
    },
    PROMPT_FOR_NEWDATA: {
      text: 'Enter new transaction type details.',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 158,
    },
    PROMPT_FOR_CONFIRMATION: {
      text: 'Changes validated.Press F5 to save',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 161,
    },
    CONFIRM_UPDATE_SUCCESS: {
      text: 'Changes committed to database',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 163,
    },
    INFORM_FAILURE: {
      text: 'Changes unsuccessful',
      field: 'WS-INFO-MSG',
      declaredWidth: 40,
      line: 165,
    },
    WS_EXIT_MESSAGE: {
      text: 'PF03 pressed.Exiting              ',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 170,
    },
    WS_INVALID_KEY: {
      text: 'Invalid Key pressed. ',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 172,
    },
    WS_NAME_MUST_BE_ALPHA: {
      text: 'Name can only contain alphabets and spaces',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 174,
    },
    WS_RECORD_NOT_FOUND: {
      text: 'No record found for this key in database',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 176,
    },
    NO_SEARCH_CRITERIA_RECEIVED: {
      text: 'No input received',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 178,
    },
    NO_CHANGES_DETECTED: {
      text: 'No change detected with respect to values fetched.',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 180,
    },
    COULD_NOT_LOCK_REC_FOR_UPDATE: {
      text: 'Could not lock record for update',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 182,
    },
    DATA_WAS_CHANGED_BEFORE_UPDATE: {
      text: 'Record changed by some one else. Please review',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 184,
    },
    WS_UPDATE_WAS_CANCELLED: {
      text: 'Update was cancelled',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 186,
    },
    TABLE_UPDATE_FAILED: {
      text: 'Update of record failed',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 188,
    },
    RECORD_DELETE_FAILED: {
      text: 'Delete of record failed',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 190,
    },
    WS_DELETE_WAS_CANCELLED: {
      text: 'Delete was cancelled',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 192,
    },
    WS_INVALID_KEY_PRESSED: {
      text: 'Invalid key pressed',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 194,
    },
    CODING_TO_BE_DONE: {
      text: 'Looks Good.... so far',
      field: 'WS-RETURN-MSG',
      declaredWidth: 75,
      line: 196,
    },
  },
} as const satisfies Partial<Record<ProgramName, Record<string, StatusMessage>>>;

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
 * Trade-offs: these are parts rather than finished sentences. Each one interleaves
 * source literals with a record value - a transaction id, a user id, a menu option name
 * - so there is no single string to store. Storing a printf-style pattern instead was
 * rejected because the `DELIMITED BY` operand differs per part and a pattern has
 * nowhere to put it, and because a pattern invents punctuation that the source
 * expresses as separate literals.
 */
export const MESSAGE_TEMPLATES = {
  /**
   * `COACTUPC` account-filter rejection.
   *
   * Assumptions: two literals rather than one. The baseline splits the sentence across
   * two literals whose join has no space - `'...must be a 11 digit'` then `' Non-Zero
   * Number'` - so the leading space of the second literal is the word break. Merging
   * them by hand would work here and would hide that the join is load bearing.
   */
  ACCOUNT_NUMBER_MUST_BE_11_DIGIT_NON_ZERO: {
    parts: [
      { literal: 'Account Number if supplied must be a 11 digit' },
      { literal: ' Non-Zero Number' },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [1807, 1808] },
  },
  /** `COACTUPC` state-and-ZIP cross-check rejection; one literal, no inserted value. */
  INVALID_ZIP_CODE_FOR_STATE: {
    parts: [{ literal: 'Invalid zip code for state' }],
    source: { file: PROGRAM_SOURCE_FILES.COACTUPC, lines: [2550] },
  },
  /**
   * `COADM01C` unavailable-option message, composed at two sites.
   *
   * Assumptions: no option name is inserted. The two lines that would insert
   * `CDEMO-ADMIN-OPT-NAME` are commented out at both sites - `COADM01C.cbl:153-154` and
   * `:274-275` - so the admin menu says "This option is not installed ..." without
   * naming the option, while the main menu names it. That asymmetry is baseline
   * behaviour, not an omission here.
   */
  ADMIN_OPTION_NOT_INSTALLED: {
    parts: [{ literal: 'This option ' }, { literal: 'is not installed ...' }],
    source: {
      file: PROGRAM_SOURCE_FILES.COADM01C,
      lines: [152, 155, 273, 276],
    },
  },
  /**
   * `COADM01C` admin-menu line: option number, separator, option name.
   *
   * Assumptions: this is not a message-band string. The baseline composes it into the
   * 40-character `WS-ADMIN-OPT-TXT` and moves it to a menu-row field, not to `ERRMSGO`,
   * so {@link MESSAGE_BAND} does not govern it and padding it to 78 would be wrong. See
   * the note on {@link MESSAGE_BAND} for why that region is modelled separately.
   */
  ADMIN_MENU_OPTION_LINE: {
    parts: [
      { value: 'CDEMO-ADMIN-OPT-NUM', delimitedBy: 'SIZE' },
      { literal: '. ' },
      { value: 'CDEMO-ADMIN-OPT-NAME', delimitedBy: 'SIZE' },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COADM01C, lines: [236, 237, 238] },
  },
  /**
   * `COMEN01C` unavailable-option message.
   *
   * Assumptions: the option name is delimited by a double space. The name is a
   * 35-character padded field, and `DELIMITED BY ' '` stops at the first double space,
   * which strips the padding without cutting the name. This is the one place in the
   * baseline where that operand appears, and it is what makes this message read
   * correctly while the coming-soon message below does not.
   */
  MENU_OPTION_NOT_INSTALLED: {
    parts: [
      { literal: 'This option ' },
      { value: 'CDEMO-MENU-OPT-NAME', delimitedBy: '  ' },
      { literal: ' is not installed...' },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COMEN01C, lines: [163, 165, 166] },
  },
  /**
   * `COMEN01C` coming-soon message, shown for an option whose target program name
   * begins `DUMMY`.
   *
   * Assumptions: this one truncates the option name. It delimits by a single space, so
   * `'Account View '` contributes only `'Account'`, and the next literal has no leading
   * space - the rendered text is "This option Accountis coming soon ...". That reads as
   * a slip, and the neighbouring not-installed message shows the author knew the
   * double-space form, but `app/**` is the behavioural oracle so the single space is
   * transcribed as it stands.
   */
  MENU_OPTION_COMING_SOON: {
    parts: [
      { literal: 'This option ' },
      { value: 'CDEMO-MENU-OPT-NAME', delimitedBy: 'SPACE' },
      { literal: 'is coming soon ...' },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COMEN01C, lines: [172, 174, 175] },
  },
  /**
   * `COMEN01C` main-menu line: option number, separator, option name.
   *
   * Assumptions: this is not a message-band string. The baseline composes it into the
   * 40-character `WS-MENU-OPT-TXT` and moves it to one of the menu-row fields
   * `OPTN001O`-`OPTN012O`, not to `ERRMSGO`. The two unavailable-option messages above *do*
   * reach the band, because those go to `WS-MESSAGE` instead - so the same program feeds two
   * different regions and only one of them is governed by {@link MESSAGE_BAND}.
   */
  MENU_OPTION_LINE: {
    parts: [
      { value: 'CDEMO-MENU-OPT-NUM', delimitedBy: 'SIZE' },
      { literal: '. ' },
      { value: 'CDEMO-MENU-OPT-NAME', delimitedBy: 'SIZE' },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COMEN01C, lines: [269, 270, 271] },
  },
  /** `CORPT00C` acknowledgement after the report job is submitted. */
  REPORT_SUBMITTED_FOR_PRINTING: {
    parts: [
      { value: 'WS-REPORT-NAME', delimitedBy: 'SPACE' },
      { literal: ' report submitted for printing ...' },
    ],
    source: { file: PROGRAM_SOURCE_FILES.CORPT00C, lines: [449, 450] },
  },
  /** `CORPT00C` confirmation prompt before the report job is submitted. */
  PLEASE_CONFIRM_TO_PRINT_REPORT: {
    parts: [
      { literal: 'Please confirm to print the ' },
      { value: 'WS-REPORT-NAME', delimitedBy: 'SPACE' },
      { literal: ' report...' },
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
      { value: 'CONFIRMI', delimitedBy: 'SPACE' },
      { literal: '" is not a valid value to confirm...' },
    ],
    source: { file: PROGRAM_SOURCE_FILES.CORPT00C, lines: [486, 487, 488] },
  },
  /**
   * `COBIL00C` bill-payment success message.
   *
   * Assumptions: the doubled space survives. The first literal ends with a space and
   * the second begins with one, so the rendered text reads "Payment successful. Your
   * Transaction ID is ...". Both spaces are in the source and neither is removed.
   */
  PAYMENT_SUCCESSFUL: {
    parts: [
      { literal: 'Payment successful. ' },
      { literal: ' Your Transaction ID is ' },
      { value: 'TRAN-ID', delimitedBy: 'SPACE' },
      { literal: '.' },
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
      { literal: 'Transaction added successfully. ' },
      { literal: ' Your Tran ID is ' },
      { value: 'TRAN-ID', delimitedBy: 'SPACE' },
      { literal: '.' },
    ],
    source: {
      file: PROGRAM_SOURCE_FILES.COTRN02C,
      lines: [728, 730, 731, 732],
    },
  },
  /** `COUSR01C` user-added success message. */
  USER_HAS_BEEN_ADDED: {
    parts: [
      { literal: 'User ' },
      { value: 'SEC-USR-ID', delimitedBy: 'SPACE' },
      { literal: ' has been added ...' },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COUSR01C, lines: [255, 256, 257] },
  },
  /** `COUSR02C` user-updated success message. */
  USER_HAS_BEEN_UPDATED: {
    parts: [
      { literal: 'User ' },
      { value: 'SEC-USR-ID', delimitedBy: 'SPACE' },
      { literal: ' has been updated ...' },
    ],
    source: { file: PROGRAM_SOURCE_FILES.COUSR02C, lines: [372, 373, 374] },
  },
  /** `COUSR03C` user-deleted success message. */
  USER_HAS_BEEN_DELETED: {
    parts: [
      { literal: 'User ' },
      { value: 'SEC-USR-ID', delimitedBy: 'SPACE' },
      { literal: ' has been deleted ...' },
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
 * Trade-offs: a register exists rather than the diagnostics simply being absent,
 * because an omission is indistinguishable from an oversight. Those 44 sites are missing from
 * the displayable catalog on purpose, and without this list the next author would
 * either reinstate them - reintroducing the disclosure - or conclude the extraction was
 * incomplete and re-run it. The rows carry provenance and no withheld text, so the
 * baseline copy stays findable while the bundle stays clean.
 *
 * Assumptions: the `COTRTLIC` rows point at `CSDB2RPY.cpy` for their composition. The
 * ten redacted `COTRTLIC` literals are action labels moved into
 * `WS-DB2-CURRENT-ACTION`, and the copybook the program includes performs the join
 * `STRING FUNCTION TRIM(WS-DB2-CURRENT-ACTION) ' SQLCODE:' WS-DISP-SQLCODE ' '
 * WS-DSNTIAC-FMTD-TEXT`. The label is meaningless without that join, which is precisely
 * why redacting the label rather than only the code is the correct cut.
 */
export const REDACTED_DIAGNOSTICS = [
  {
    program: 'COACTUPC',
    file: PROGRAM_SOURCE_FILES.COACTUPC,
    lines: [3674],
    condition: 'account not found in the card cross-reference file',
    detail: 'cics-response-and-reason',
    replacement: STATUS_MESSAGES.COACTUPC.DID_NOT_FIND_ACCT_IN_CARDXREF__L498.text,
  },
  {
    program: 'COACTUPC',
    file: PROGRAM_SOURCE_FILES.COACTUPC,
    lines: [3723],
    condition: 'account not found in the account master file',
    detail: 'cics-response-and-reason',
    replacement: STATUS_MESSAGES.COACTUPC.DID_NOT_FIND_ACCT_IN_ACCTDAT.text,
  },
  {
    program: 'COACTUPC',
    file: PROGRAM_SOURCE_FILES.COACTUPC,
    lines: [3773],
    condition: 'customer not found in the customer master file',
    detail: 'cics-response-and-reason',
    replacement: STATUS_MESSAGES.COACTUPC.DID_NOT_FIND_CUST_IN_CUSTDAT.text,
  },
  {
    program: 'COACTVWC',
    file: PROGRAM_SOURCE_FILES.COACTVWC,
    lines: [747],
    condition: 'account not found in the card cross-reference file',
    detail: 'cics-response-and-reason',
    replacement: STATUS_MESSAGES.COACTVWC.DID_NOT_FIND_ACCT_IN_CARDXREF.text,
  },
  {
    program: 'COACTVWC',
    file: PROGRAM_SOURCE_FILES.COACTVWC,
    lines: [796],
    condition: 'account not found in the account master file',
    detail: 'cics-response-and-reason',
    replacement: STATUS_MESSAGES.COACTVWC.DID_NOT_FIND_ACCT_IN_ACCTDAT.text,
  },
  {
    program: 'COACTVWC',
    file: PROGRAM_SOURCE_FILES.COACTVWC,
    lines: [846],
    condition: 'customer not found in the customer master file',
    detail: 'cics-response-and-reason',
    replacement: STATUS_MESSAGES.COACTVWC.DID_NOT_FIND_CUST_IN_CUSTDAT.text,
  },
  {
    program: 'COPAUS0C',
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [476],
    condition: 'reading an authorization detail segment failed',
    detail: 'ims-status-code',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS0C',
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [509],
    condition: 'repositioning to an authorization detail segment failed',
    detail: 'ims-status-code',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS0C',
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [836],
    condition: 'account not found in the cross-reference file',
    detail: 'cics-response-and-reason',
    replacement: SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND,
  },
  {
    program: 'COPAUS0C',
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [851],
    condition: 'reading the cross-reference file failed',
    detail: 'cics-response-and-reason',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS0C',
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [886],
    condition: 'account not found in the account master file',
    detail: 'cics-response-and-reason',
    replacement: SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND,
  },
  {
    program: 'COPAUS0C',
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [901],
    condition: 'reading the account master file failed',
    detail: 'cics-response-and-reason',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS0C',
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [937],
    condition: 'customer not found in the customer master file',
    detail: 'cics-response-and-reason',
    replacement: STATUS_MESSAGES.COACTVWC.DID_NOT_FIND_CUST_IN_CUSTDAT.text,
  },
  {
    program: 'COPAUS0C',
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [952],
    condition: 'reading the customer master file failed',
    detail: 'cics-response-and-reason',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS0C',
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [988],
    condition: 'reading the authorization summary segment failed',
    detail: 'ims-status-code',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS0C',
    file: PROGRAM_SOURCE_FILES.COPAUS0C,
    lines: [1022],
    condition: 'scheduling the IMS program specification block failed',
    detail: 'ims-status-code',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS1C',
    file: PROGRAM_SOURCE_FILES.COPAUS1C,
    lines: [455],
    condition: 'reading the authorization summary segment failed',
    detail: 'ims-status-code',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS1C',
    file: PROGRAM_SOURCE_FILES.COPAUS1C,
    lines: [481],
    condition: 'reading an authorization detail segment failed',
    detail: 'ims-status-code',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS1C',
    file: PROGRAM_SOURCE_FILES.COPAUS1C,
    lines: [510],
    condition: 'reading the next authorization segment failed',
    detail: 'ims-status-code',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS1C',
    file: PROGRAM_SOURCE_FILES.COPAUS1C,
    lines: [544],
    condition: 'marking an authorization as fraud failed and was rolled back',
    detail: 'ims-status-code',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS1C',
    file: PROGRAM_SOURCE_FILES.COPAUS1C,
    lines: [595],
    condition: 'scheduling the IMS program specification block failed',
    detail: 'ims-status-code',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS2C',
    file: PROGRAM_SOURCE_FILES.COPAUS2C,
    lines: [211],
    condition: 'inserting the fraud row failed',
    detail: 'db2-sqlcode-and-sqlstate',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COPAUS2C',
    file: PROGRAM_SOURCE_FILES.COPAUS2C,
    lines: [239],
    condition: 'updating the fraud row failed',
    detail: 'db2-sqlcode-and-sqlstate',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COTRTUPC',
    file: PROGRAM_SOURCE_FILES.COTRTUPC,
    lines: [1499],
    condition: 'reading the transaction-type row failed',
    detail: 'db2-table-name',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COTRTUPC',
    file: PROGRAM_SOURCE_FILES.COTRTUPC,
    lines: [1569],
    condition: 'updating the transaction-type row failed',
    detail: 'db2-table-name',
    replacement: STATUS_MESSAGES.COTRTUPC.TABLE_UPDATE_FAILED.text,
  },
  {
    program: 'COTRTUPC',
    file: PROGRAM_SOURCE_FILES.COTRTUPC,
    lines: [1609],
    condition: 'inserting a transaction-type row failed',
    detail: 'db2-table-name',
    replacement: STATUS_MESSAGES.COTRTUPC.INFORM_FAILURE.text,
  },
  {
    program: 'COTRTUPC',
    file: PROGRAM_SOURCE_FILES.COTRTUPC,
    lines: [1641],
    condition: 'delete refused because child category rows reference the type',
    detail: 'db2-sqlcode-and-sqlerrm',
    replacement: SHARED_MESSAGES.PLEASE_DELETE_ASSOCIATED_CHILD_RECORDS_FIRST,
  },
  {
    program: 'COTRTUPC',
    file: PROGRAM_SOURCE_FILES.COTRTUPC,
    lines: [1654],
    condition: 'deleting the transaction-type row failed',
    detail: 'db2-sqlcode-and-sqlerrm',
    replacement: STATUS_MESSAGES.COTRTUPC.RECORD_DELETE_FAILED.text,
  },
  {
    program: 'COTRTLIC',
    file: 'app/app-transaction-type-db2/cpy/CSDB2RPY.cpy',
    lines: [40, 70, 74, 76, 78],
    condition:
      'any Db2 failure on the transaction-type list screen; the included copybook joins the action label to the SQL code and to the formatted diagnostic text',
    detail: 'db2-sqlcode-and-diagnostic-text',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COTRTLIC',
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1686, 1712, 1958, 1986, 2013, 2042],
    condition: 'opening, fetching from or closing either declared browse cursor failed',
    detail: 'db2-cursor-name',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COTRTLIC',
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1784],
    condition: 'fetching from the backward browse cursor failed',
    detail: 'db2-cursor-name',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COTRTLIC',
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1826],
    condition: 'reading the transaction-type table failed',
    detail: 'db2-table-name',
    replacement: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  },
  {
    program: 'COTRTLIC',
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1874],
    condition: 'the update was refused because another unit of work held the row',
    detail: 'persistence-mechanism',
    replacement: STATUS_MESSAGES.COTRTUPC.DATA_WAS_CHANGED_BEFORE_UPDATE.text,
  },
  {
    program: 'COTRTLIC',
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1883],
    condition: 'updating the transaction-type row failed',
    detail: 'db2-sqlcode',
    replacement: STATUS_MESSAGES.COTRTUPC.TABLE_UPDATE_FAILED.text,
  },
  {
    program: 'COTRTLIC',
    file: PROGRAM_SOURCE_FILES.COTRTLIC,
    lines: [1929],
    condition: 'deleting the transaction-type row failed',
    detail: 'db2-sqlcode',
    replacement: STATUS_MESSAGES.COTRTUPC.RECORD_DELETE_FAILED.text,
  },
] as const satisfies readonly RedactedDiagnostic[];

/**
 * The shape a correlation reference must have before {@link formatDb2Message} will
 * render it: 8 to 36 characters of ASCII letters, digits and hyphens.
 *
 * Assumptions: the token is validated rather than trusted, because the parameter it
 * guards is the one route by which raw database diagnostics could still reach the band.
 * A caller could otherwise pass a `SQLCODE` and its `DSNTIAC` detail as the
 * "reference", and the rendered band would carry exactly the text this module withholds
 * -- the disclosure would have moved one parameter to the left rather than gone.
 *
 * Assumptions: the 8-to-36 range spans the two identifier forms the platform
 * actually produces. `CorrelationIdFilter` in the shared kernel accepts a
 * caller-supplied identifier and otherwise generates a UUID, whose canonical form is 36
 * characters of hexadecimal and hyphens; a shortened trace or request identifier is
 * typically 8 to 32. The 8-character floor rejects a value too short to identify one
 * request among many, and the character class admits nothing that could carry a
 * sentence, a quoted identifier or a statement fragment - a space, a colon, a comma, a
 * quote and a parenthesis are all outside it.
 */
export const DB2_CORRELATION_REFERENCE_PATTERN = /^[A-Za-z0-9-]{8,36}$/;

/**
 * The browser-safe rendering of a data-tier failure: catalogued business text, a
 * public error code, and the correlation identifier that ties the request to the
 * server-side record holding the actual diagnostics.
 *
 * Alternatives Considered: this is three fields rather than one string. Returning one
 * pre-joined sentence was the shape the baseline used and was rejected, because the
 * three parts have three different destinations in the user interface. `text` belongs
 * in the 75-character message band, so it has to stay separable to respect that width
 * contract; `code` and `correlationId` belong in a small, copyable footer, because
 * their only purpose is to be quoted to support. Joining them would force every screen
 * to split the string apart again by searching for a separator.
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
 * Assumptions: the shape is enforced rather than trusted. `code` is a string parameter,
 * so nothing but a check stops a caller passing the raw SQL code, or the `DSNTIAC`
 * text, or a whole exception message through it - which would reinstate the disclosure
 * this module was changed to prevent, through the very field that replaced it. The
 * pattern admits an upper-case context and a numeric sequence and nothing else, so
 * vendor text cannot satisfy it.
 */
const PUBLIC_ERROR_CODE_PATTERN = /^CARDDEMO-[A-Z]{2,12}-\d{4}$/;

/**
 * Matches the correlation-identifier shape this module will render.
 *
 * Assumptions: the correlation identifier is shape-checked for the same reason the
 * error code is. The pattern is deliberately permissive about WHICH identifier scheme is
 * used - any 8 to 64 character
 * run of unreserved URL characters passes, which covers a UUID, an AWS request id and
 * an X-Ray trace id - and strict about what an identifier is NOT: it admits no space,
 * colon, comma or bracket, so a sentence of diagnostic text cannot pass as one.
 */
const CORRELATION_ID_PATTERN = /^[A-Za-z0-9._~-]{8,64}$/;

/**
 * Builds the browser-safe rendering of a data-tier failure: the catalogued action
 * text, a public error code, and the correlation identifier that locates the
 * withheld diagnostics on the server.
 *
 * Assumptions: this deliberately does NOT reproduce `CSDB2RPY.cpy`. The copybook joins
 * a `sqlCode` and a `detail` onto the action text, which is faithful to a 3270 screen
 * and wrong for a browser. `detail`
 * is the output of the `DSNTIAC` utility, and what that utility produces is the
 * engine's own message text - typically the SQL code, the SQLSTATE, the failing
 * statement's object names and, for a constraint violation, the constraint and index
 * names. Rendering it handed every authenticated user a running description of the data
 * tier: which engine it is, which tables and columns back the screen they are on, which
 * constraints and cursors exist, and on a deadlock which other unit of work it collided
 * with. That is reconnaissance, and it is delivered by the application itself to anyone
 * who can provoke an error - which, for a validation-adjacent failure, is anyone who
 * can submit the form. The raw SQL code is withheld for a weaker version of the same
 * reason: it is an engine-specific number whose value maps to a known
 * vendor-documented condition, so publishing it fingerprints the engine and narrows
 * what an attacker has to guess.
 *
 * Alternatives Considered: this function accepts no diagnostic-detail parameter at all,
 * rather than accepting one and sanitising it. Filtering the detail here - stripping
 * object names, truncating, allowing a safe subset - was rejected: a filter has to
 * anticipate every shape the engine can emit, it is applied at the last moment before
 * rendering where a mistake is invisible, and it leaves a parameter a caller can keep
 * filling with diagnostics. Admitting no such parameter makes the disclosure
 * unrepresentable - there is no argument through which vendor text can arrive, and the
 * two arguments that do exist are shape-checked so neither can be used to smuggle it.
 *
 * Assumptions: nothing is lost operationally. The diagnostics are not discarded, they
 * are relocated. The owning service logs the SQL code, the SQLSTATE and the
 * `DSNTIAC`-equivalent text against the same correlation identifier returned here,
 * where access is governed by the log group's IAM policy instead of by having provoked
 * the error. So an operator diagnosing the failure has strictly more than the 3270
 * screen gave them - the full diagnostic plus the request that produced it - and the
 * user has what they can act on: what failed, and the identifier to quote.
 *
 * Assumptions: this is registered as an intentional divergence. The project carries
 * user-visible strings across verbatim, and this narrows one. The narrowing is
 * deliberate and belongs in `docs/architecture/cobol-to-service-traceability.md` beside
 * the other documented divergences, under the same heading as the baseline's plaintext
 * password field, which is likewise not carried forward. The action text itself is
 * still verbatim; only the vendor tail is withheld.
 *
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
  // Assumptions: COBOL's FUNCTION TRIM removes leading and trailing spaces, which
  // String.prototype.trim also does, and the baseline field holds no other
  // whitespace, so the two agree byte for byte. A field carrying a tab or a newline
  // would diverge, and none does. Trimming is not cosmetic here: the catalogued
  // literals carry a trailing space precisely because the baseline appended a code
  // to them, so without it that now-unused separator renders as a stray space at
  // the end of the sentence.
  const text = action.trim();
  if (text.length === 0) {
    throw new RangeError(
      'action must be a non-empty catalogued message; an empty action would render a message band containing only an error code.',
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
  // Trade-offs: the two refusals above describe a rejected value by its LENGTH and
  // never echo it, which costs a caller the quickest route to seeing what they sent.
  // Echoing it is the alternative and it is refused: a caller that wrongly passed
  // engine diagnostic text would have that text placed into an exception message,
  // which is itself a browser-visible surface the moment an error boundary renders
  // it - reinstating the very disclosure this check exists to prevent.
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
 * Assumptions: the input is a CATALOGUED literal, which is why measuring it with
 * `String.prototype.length` is correct here. Every string this catalog holds is
 * transcribed from `app/**`, where the source character set has no character outside
 * US-ASCII, so for these inputs code units, code points and UTF-8 bytes are all the
 * same number and the three possible readings of "width" cannot disagree. That is NOT
 * true of operator-supplied text, where an accented letter or an emoji makes them
 * differ; text arriving from a form is measured with {@link codePointLength} and
 * {@link utf8ByteLength} and checked with {@link fitsDeclaredWidth} instead, and the
 * two concerns are kept in separate functions precisely so that neither borrows the
 * other's assumption.
 * @param {string} text - The literal exactly as transcribed from the copybook.
 * @param {number} declaredWidth - Field width from the `PIC X(n)` clause; must be a
 *   non-negative integer.
 * @returns {string} The value padded with trailing spaces to `declaredWidth`, or truncated
 *   to `declaredWidth` when `text` is longer.
 * @throws {RangeError} If `declaredWidth` is negative or not an integer, which
 *   would mean the caller passed something that is not a COBOL field width.
 */
export function padToDeclaredWidth(text: string, declaredWidth: number): string {
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
    : text + ' '.repeat(declaredWidth - text.length);
}

/**
 * Counts the CODE POINTS in a string, which is what a declared field width limits.
 *
 * Purpose: JavaScript's `String.prototype.length` counts UTF-16 code units, and every character
 * outside the Basic Multilingual Plane costs two of them. A control carrying `maxLength={20}` on a
 * `PIC X(20)` field therefore stops accepting input after 18 characters if two of them are emoji,
 * and after 10 if all of them are — the operator sees a field that silently holds half of what its
 * hint says. Measured: `Ünïcödé Émoji 🎉🏦` is **16 characters and 18 code units**, so it consumes
 * 18 of a 20-position field.
 *
 * Assumptions: code points and not grapheme clusters. A grapheme count is what a person would call
 * "characters" — a flag emoji or a family emoji is one grapheme and several code points — but the
 * limit being measured against is a database column and a fixed-width record, and both count code
 * points. Measuring graphemes would produce a friendlier number that permits a value the storage
 * layer then refuses, which is the worse of the two failures.
 *
 * Alternatives Considered: `Intl.Segmenter` with granularity `grapheme`. Rejected for the reason
 * above and one more: it would make the measurement locale-sensitive, and a field width is not.
 * @param {string} text - Any string, including one carrying astral characters.
 * @returns {number} The number of Unicode code points in `text`.
 */
export function codePointLength(text: string): number {
  /*
   * WHY : Assumptions: spread iteration is used rather than a loop over indices because the string
   *       iterator yields whole code points, pairing surrogates automatically. Counting with
   *       `text.length` is the defect this function exists to correct, so it cannot appear here.
   */
  return [...text].length;
}

/**
 * Counts the bytes a string occupies once encoded for the wire.
 *
 * Purpose: the storage this application feeds is fixed-width at the record level — the copybooks
 * declare `PIC X(n)` and the ETL reads at byte offsets — and a code-point count does not answer how
 * many BYTES a value takes. The two diverge for every non-ASCII character, and they diverge
 * differently for the two Unicode forms of the same accented letter: `é` composed is one code point
 * and two bytes, while `é` decomposed is two code points and three bytes. A name field that accepts
 * either therefore has two different capacities depending on how the operator's keyboard produced
 * the accent.
 *
 * Assumptions: UTF-8, because that is what a JSON request body is encoded as. `TextEncoder` is used
 * rather than a hand-rolled counter: it is a platform API, it is exact, and a manual count over
 * code-point ranges is a thing to get wrong with no upside.
 * @param {string} text - Any string.
 * @returns {number} The number of UTF-8 bytes `text` encodes to.
 */
export function utf8ByteLength(text: string): number {
  return new TextEncoder().encode(text).length;
}

/**
 * Normalises operator-supplied text to the one Unicode form the wire should carry.
 *
 * Purpose: two strings that look identical on screen can differ byte for byte — `é` as one code
 * point, or `e` followed by a combining acute — and both round-trip through this application
 * unchanged today. On a system whose storage is fixed-width and whose keys are compared as
 * characters, that means two records an operator cannot tell apart, a search that finds one of them,
 * and a field width that holds a different number of letters depending on which form was typed.
 * Composing on the way out removes the ambiguity at the one boundary where it can still be removed.
 *
 * Assumptions: NFC and not NFD. Both are canonical, so neither loses information, and either would
 * make the two forms converge; NFC is chosen because it is the shorter of the two for Latin text —
 * one code point per accented letter rather than two — so it is the form that fits the most letters
 * into a declared width, and it is the form the web platform recommends for interchange.
 *
 * Alternatives Considered: NFKC, which additionally folds compatibility characters — a full-width
 * digit to an ASCII digit, a ligature to its letters. Rejected because it is LOSSY with respect to
 * the operator's input: it would silently rewrite what was typed, and the baseline's own validation
 * refuses characters it does not want rather than rewriting them. Also considered: normalising at
 * the transport layer instead, which would catch every field with no per-screen adoption. Rejected
 * because the transport layer must send what it was given for the request signature and correlation
 * to mean anything; normalisation belongs where the value is composed, not where it is posted.
 *
 * Trade-offs: normalising OUT and not IN means a value already stored in a decomposed form keeps
 * that form until it is next edited. Rewriting values on the way in was considered and rejected as
 * out of scope for a presentation layer — it would change what the operator is shown relative to
 * what the record holds, and the reconciliation belongs to the data-migration verification step.
 * @param {string} text - Text as the operator typed it, in either canonical form.
 * @returns {string} The same text in Normalization Form C.
 */
export function normaliseForWire(text: string): string {
  return text.normalize('NFC');
}

/**
 * Reports whether operator-supplied text fits a declared field width once normalised.
 *
 * Purpose: this is the check a form control's own `maxLength` cannot perform, and it is meant to be
 * used ALONGSIDE it rather than instead of it. `maxLength` stops the keystroke, which is the right
 * interaction, but it counts the wrong unit; this counts the right ones and is what a validator
 * should refuse on.
 *
 * Assumptions: BOTH measures are required to fit, and the byte measure is the one that usually
 * binds. A `PIC X(20)` field is twenty bytes on the record, so twenty accented letters are twenty
 * code points and forty bytes — a value that satisfies a character count and overflows the field.
 * Checking only code points would let it through to a service that refuses it with a message the
 * operator cannot act on; checking only bytes would reject a value the database column would have
 * accepted. Requiring both is the conservative reading, and it is the one that matches a
 * fixed-width record.
 * @param {string} text - Text as the operator typed it.
 * @param {number} declaredWidth - Field width from the `PIC X(n)` clause; a non-negative integer.
 * @returns {boolean} True when the normalised text fits the width in both code points and bytes.
 * @throws {RangeError} If `declaredWidth` is negative or not an integer, which would mean the caller
 *   passed something that is not a COBOL field width.
 */
export function fitsDeclaredWidth(text: string, declaredWidth: number): boolean {
  if (!Number.isInteger(declaredWidth) || declaredWidth < 0) {
    throw new RangeError(
      `declaredWidth must be a non-negative integer, received ${String(declaredWidth)}`,
    );
  }
  const normalised = normaliseForWire(text);
  return (
    codePointLength(normalised) <= declaredWidth && utf8ByteLength(normalised) <= declaredWidth
  );
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
export function formatFieldValidationMessage(label: string, suffix: string): string {
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
        if ('literal' in part) {
          return part.literal;
        }
        const supplied = values[part.value];
        if (supplied === undefined) {
          throw new Error(`formatMessageTemplate: no value supplied for ${part.value}`);
        }
        if (part.delimitedBy === 'SIZE') {
          return supplied;
        }
        // Alternatives Considered: a whitespace regular expression was rejected for
        // the SPACE case. A COBOL delimiter is matched literally and SPACE is the
        // figurative constant for exactly one blank, so an index-of on the delimiter
        // text is the faithful operation; `\s` would additionally match a tab, which
        // the mainframe cannot produce and which would therefore truncate a value at
        // a character the reference program passes through.
        const delimiter = part.delimitedBy === 'SPACE' ? ' ' : part.delimitedBy;
        const at = supplied.indexOf(delimiter);
        return at === -1 ? supplied : supplied.slice(0, at);
      },
    )
    .join('');
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
export function normaliseMessageBandValue(value: string | null | undefined): string {
  if (value === null || value === undefined) {
    return '';
  }
  // Alternatives Considered: stripping the wider C0 control range, rather than
  // U+0000 alone. Rejected because U+0000 is the wire form of LOW-VALUES and no
  // baseline message field can hold any other control character, so a broader
  // filter would silently alter text it was never meant to touch instead of failing
  // loudly on it - and a message this function returns is rendered verbatim.
  return value.replaceAll('\u0000', '').trim();
}

/**
 * Reports whether a message-band value means "no message is set".
 *
 * Assumptions: an all-spaces value counts as empty as well as a zero-length one. The
 * baseline's off-condition is `VALUE LOW-VALUES`, and the programs also clear the field
 * with `MOVE SPACES`. A transport that carries either form arrives as a string that is
 * empty or all spaces, and both mean the band should stay hidden. Testing only for a
 * zero-length string would leave the band rendering a blank alert on every quiet
 * screen.
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
 * Assumptions: a trimmed alias is published beside the verbatim one. Character-cell
 * centring only centres in a
 * monospaced 80-column grid. In a proportional-font flex layout the leading spaces
 * centre nothing and instead offset the text, so a renderer needs the trimmed form -
 * and if this module does not provide it, every consumer calls `.trim()` itself and the
 * module stops owning what is displayed.
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
 * Assumptions: this is aliased at the top level. The router guards admin routes with
 * it, and a router should not have to know that the string originates in the main-menu
 * program. The trailing space is part of the value - see the note on the underlying
 * entry.
 */
export const ACCESS_DENIED_ADMIN_ONLY = PROGRAM_MESSAGES.COMEN01C.NO_ACCESS_ADMIN_ONLY_OPTION;

/**
 * Headline the route guard's refusal surface renders above {@link ACCESS_DENIED_ADMIN_ONLY}.
 *
 * Purpose
 * -------
 * `ui/src/routes/guards.tsx` refuses an authenticated non-administrator an administrative route with
 * a full-surface result rather than a one-line message, because there is no screen behind the refusal
 * to carry a message band. A result surface needs a heading as well as an explanation, and the
 * baseline has no heading to transcribe: `app/cbl/COMEN01C.cbl` L140 refuses the same caller by moving
 * one sentence into the row-23 message field of the menu that stays on screen, so the sentence IS the
 * whole refusal there.
 *
 * Refactoring Rationale: ⚠️ this string is AUTHORED, and it is catalogued here rather than written at
 * the guard's call site where a review found it as a literal and named it a Transformation Rule T8
 * violation. It is authored for the same reason {@link NOT_FOUND_MESSAGES} is -- the surface it labels
 * has no 3270 counterpart -- and it is deliberately declared beside the transcribed sentence it
 * introduces, so a reader comparing the two can see at a glance which of the pair the baseline supplied
 * and which this migration had to write.
 *
 * Assumptions: no {@link SourceRef}, for the reason {@link NOT_FOUND_MESSAGES} records: every
 * transcribed entry in this module cites a file and a line, and inventing a citation for a string that
 * has none would be worse than omitting one. The omission is what marks it as authored.
 *
 * Assumptions: the heading states the OUTCOME and the sentence beneath it states the reason, so the two
 * do not repeat each other. Repeating "Admin Only" in the heading would show one fact twice on a
 * surface whose whole content is two lines.
 */
export const ACCESS_DENIED_HEADING = 'Access denied';

/**
 * Headline for the abend surface, rendered by the application error boundary.
 *
 * Aliased because the error boundary is not associated with any one program even
 * though five programs emit this text.
 */
export const UNEXPECTED_ABEND_OCCURRED = SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED;

/** Headline for the unexpected-data surface, also rendered by the error boundary. */
export const UNEXPECTED_DATA_SCENARIO = SHARED_MESSAGES.UNEXPECTED_DATA_SCENARIO;

/*
 * ---------------------------------------------------------------------------
 * Additive shell text: strings this delivery needs that no baseline field carries
 * ---------------------------------------------------------------------------
 *
 * Assumptions: the entries below are the ONLY strings in this catalogue that are not
 * transcribed from a copybook literal or a program's `MOVE`, and they are grouped and
 * labelled so that the distinction survives. Transformation rule T8 governs baseline
 * text: it is carried across character for character and every entry above cites the
 * field it came from. These strings have no such origin, because the surfaces that need
 * them have no 3270 counterpart at all -- a terminal had no address bar to mistype, no
 * sign-off control that was not a function key, and no notion of a screen that exists in
 * a later delivery.
 *
 * Assumptions: they live in this catalogue rather than at their point of use for the
 * reason every other entry does. `ui/src/router.tsx` and `ui/src/layout/AppShell.tsx`
 * previously spelled them inline, which put user-visible text in two kinds of place and
 * left a reader auditing the catalogue for completeness unable to tell whether a missing
 * string was additive or forgotten. One home, one audit.
 *
 * Alternatives Considered: composing them from nearby baseline text so that every string
 * in the file had a citation. Rejected as worse than an honest additive group -- it would
 * have put words in the baseline's mouth, and a reader checking a citation would have
 * found a field that says something else.
 */

/**
 * Heading of the surface shown when a browser path names no delivered screen.
 *
 * Assumptions: it says the SCREEN is unavailable rather than that the address is wrong,
 * because both causes reach it -- a mistyped path and a navigation to one of the eleven
 * screens the migration plan lists that this delivery does not yet author -- and the
 * operator can act on neither by being told which one it was. The rejected path is
 * deliberately not echoed: reflecting request text into a rendered surface is the shape
 * of a reflected-injection defect, and it tells an operator nothing they did not type.
 */
export const SCREEN_NOT_AVAILABLE_TITLE = 'Screen not available';

/**
 * Explanation beneath {@link SCREEN_NOT_AVAILABLE_TITLE}.
 *
 * Assumptions: it names the delivery boundary explicitly rather than implying a fault,
 * because the commonest way to arrive here is a function key transferring to a menu the
 * reference application has and this delivery has not. An operator who reads "not
 * available" as "broken" raises a defect; one who reads it as "not in this build" does
 * not.
 *
 * ⚠️ Refactoring Rationale: shortened from 86 characters to 68. A sentence this length
 * overruns the {@link MESSAGE_BAND.workAreaWidth}-character field a service publishes an
 * operator-facing message through, and the band renders a box that wide, so the tail was
 * lost with nothing on screen to indicate that anything had been. "The requested CardDemo
 * screen" became "This screen": the surface already names the product in its shell title,
 * and "requested" restated what an operator had just done. Both halves of the meaning the
 * assumption above argues for -- the delivery boundary, and the one action available --
 * survive intact, which is the test any shortening here has to pass. Bounded by
 * {@link AUTHORED_OPERATOR_SENTENCES} so it cannot creep back.
 */
export const SCREEN_NOT_AVAILABLE_DETAIL =
  'This screen is not part of this delivery. Use a listed screen below.';

/**
 * Explanation shown when a service refuses an ORDINARY operation on authority grounds.
 *
 * Purpose
 * -------
 * A 403 answered to an operation every signed-on operator may reach means the presented token carries
 * NEITHER CardDemo group. `services/card-service/src/main/resources/openapi/card-api.yaml` declares
 * `x-required-authority: carddemo-user` on the card lookup and the card read, and its own authority
 * model records that `carddemo-user` "means any authenticated caller may reach it, because every user of
 * this system holds one of the two groups" -- so a refusal of one of those operations says nothing about
 * administrative authority at all.
 *
 * ⚠️ Refactoring Rationale: this exists because {@link ACCESS_DENIED_ADMIN_ONLY} was being shown
 * for it. That sentence is `app/cbl/COMEN01C.cbl` L140's, transcribed, and it is exactly right where the
 * baseline used it -- a non-administrator choosing an administrative menu option -- and wrong here: an
 * operator refused a card read was told the function is administrator-only when it is not, which sends
 * them to ask for administrative rights they must not be given and hides the real cause, a token
 * carrying no CardDemo group. Only `/api/v1/admin/cards/{cardKey}` and the administrative routes guarded
 * in `ui/src/routes/guards.tsx` are administrator-only, and those keep the transcribed sentence.
 *
 * Assumptions: AUTHORED, with no {@link SourceRef}, for the reason this whole group records: the
 * baseline had one authority refusal and it is already transcribed above, so inventing a citation for a
 * second sentence it never held would be worse than declaring the absence. The omission is what marks it
 * as authored.
 *
 * Assumptions: it names no group, no claim and no endpoint. An operator cannot act on a group name and a
 * refused caller must not be told which authority would have succeeded -- the contract's own `Forbidden`
 * response withholds even whether the row exists for the same reason -- so the sentence states the
 * outcome and the one action available.
 *
 * ⚠️ Refactoring Rationale: shortened from 86 characters to 64, for the field-width reason recorded on
 * {@link SCREEN_NOT_AVAILABLE_DETAIL}. "for this CardDemo function" became "here": the operator is
 * looking at the function, so naming it added no information, and the sentence still names no group,
 * no claim and no endpoint -- the withholding this constant exists for is untouched. Bounded by
 * {@link AUTHORED_OPERATOR_SENTENCES}.
 */
export const ACCESS_DENIED_NOT_AUTHORIZED =
  'Your sign-on is not authorized here. Contact your administrator.';

/** Label of the control on the not-available surface that returns to the card browse. */
export const OPEN_CARD_BROWSE_LABEL = 'Open card browse';

/**
 * Label of the shell's visible sign-off control.
 *
 * Assumptions: the label names the ACTION and not a function key, because this control is
 * not a function key. The baseline ended a session with PF12 from a menu screen; this
 * shell offers a button instead, for the reason recorded at the control itself -- a
 * document-level PF12 binding in the shell would fire alongside the same key's `cancel`
 * binding on the three update screens. The key legend a screen publishes still shows
 * whatever that screen binds, so nothing about the baseline's key contract is hidden.
 */
export const SIGN_OFF_CONTROL_LABEL = 'Sign off';

/**
 * Label of the sign-on screen's replacement-credential control.
 *
 * Assumptions: additive rather than transcribed. `app/bms/COSGN00.bms` declares two input
 * fields and no third, because the baseline had no credential-replacement turn at all --
 * the security record held the password in plain text and `app/cbl/COSGN00C.cbl` compared
 * it directly, so there was nothing to replace and no screen state to replace it in. The
 * turn exists here because the user pool can answer a sign-on with a challenge, which is
 * the mechanism that removed the plaintext field, so the label names a control the
 * baseline could not have had rather than diverging from one it did.
 *
 * Assumptions: spelled distinguishably from the transcribed password label, so an
 * accessible-name search separates the two controls in a test rather than matching both.
 */
export const SIGN_ON_NEW_PASSWORD_LABEL = 'New Password';

/**
 * Label of the sign-on screen's submit control.
 *
 * Assumptions: additive, and deliberately NOT the function-key legend's `ENTER=Sign-on`.
 * The legend names a key and this names an action, which is the distinction that lets the
 * screen offer a pointer affordance without implying a second behaviour: the control and
 * the key run the same function. The baseline needed no such control because a 3270
 * operator knows Enter submits, which a browser visitor has no way to discover.
 */
export const SIGN_ON_SUBMIT_LABEL = 'Sign on';

/**
 * Label of the card detail screen's control that opens the update screen for the card on display.
 *
 * Assumptions: additive rather than transcribed. `app/bms/COCRDSL.bms` paints no such key
 * -- its legend field at L147-L152 carries exactly `ENTER=Search Cards  F3=Exit` -- because
 * the baseline reached the update program by typing `U` beside a row on the list screen
 * rather than from the detail screen at all. The control is offered because removing it
 * would leave an operator looking at a card unable to edit it without returning to the
 * list, which is a step the baseline did not impose either.
 *
 * Refactoring Rationale: it lives here rather than beside the control. It was declared in
 * `ui/src/screens/cardDetail/index.tsx` on the catalogue's own exclusion -- the catalogue
 * "excludes strings that no COBOL source holds" -- but that exclusion is about `INITIAL=`
 * field LABELS, which are positional and meaningless apart from the control they sit
 * beside. This is a control's accessible name, not a positional label, and a reader
 * auditing the catalogue for completeness could not tell a missing additive string from a
 * forgotten one while it lived at its point of use.
 */
export const CARD_DETAIL_EDIT_CONTROL_LABEL = 'Edit';

/**
 * Guidance shown on the card detail screen when the address it was reached by names no card.
 *
 * Assumptions: additive, because the selector is a target construct -- see **D-CARD-SELECTOR**
 * in the divergence register -- and the baseline has no sentence about a value it never had.
 * The sentence beside it IS the baseline's: `app/cbl/COCRDSLC.cbl` L143 declares
 * `No input received` for a turn that carried no usable search key, and an unusable selector
 * is exactly that turn.
 *
 * Assumptions: it names the browse screen rather than telling the operator to correct the
 * address, because the address holds an opaque selector a card response published and an
 * operator has no way to compose one. Returning to the browse and selecting the record again
 * is the only action available, so it is the only action offered.
 */
export const CARD_DETAIL_INVALID_LINK_GUIDANCE =
  'Return to the card list and select the record again.';

/**
 * Explanation shown when the code for a screen could not be loaded or the screen failed to render.
 *
 * Purpose
 * -------
 * The browser fetches each screen's code on first navigation to it, and that fetch can fail for
 * reasons no screen can answer for: a redeploy replaces the hashed asset names while a tab is still
 * holding the previous document, a proxy answers the request with an error page, or the network drops.
 * The reference application had no equivalent state -- a 3270 program was either in the load library
 * or the transaction was not defined -- so this sentence is AUTHORED and carries no {@link SourceRef}.
 *
 * Assumptions: it states the one action available and nothing about the cause. The cause is a module
 * path, an HTTP status or a JavaScript error, and each is internal: an operator cannot act on any of
 * them, and rendering one would publish deployment detail onto a screen and into every screenshot of
 * it. The detail goes to the browser console instead, exactly as `ui/src/main.tsx` splits a start-up
 * failure between the two surfaces.
 *
 * Assumptions: it is shown BENEATH the transcribed abend sentence rather than replacing it. The
 * abend wording is what the baseline says for a failure it cannot continue past, and this adds the
 * recovery a browser makes possible and a terminal did not.
 *
 * ⚠️ Refactoring Rationale: shortened from 96 characters to 53, for the field-width reason recorded on
 * {@link SCREEN_NOT_AVAILABLE_DETAIL}. The clause dropped -- "the rest of the application is
 * unaffected" -- was reassurance rather than instruction, and it was the part that overran, so it was
 * the part an operator never saw anyway. What remains is the one action available, which the assumption
 * above states is the whole point of the sentence. Bounded by {@link AUTHORED_OPERATOR_SENTENCES}.
 */
export const SCREEN_LOAD_FAILED_DETAIL = 'This screen could not be loaded. Reload to try again.';

/**
 * Label of the control on the failed-screen surface that reloads the document.
 *
 * Assumptions: the label names a RELOAD and not a bare retry, because that is what the control does
 * and the difference is visible to the operator -- the page is re-fetched and any typed input on the
 * failed screen is gone. Calling it "Try again" would understate that.
 *
 * Alternatives Considered: a control that re-renders the screen in place, which is the smaller action
 * and reads as the friendlier one. It is not offered because it cannot work for the failure it would
 * be offered for: React caches a rejected lazy import permanently -- `lazyInitializer` in
 * `react@19.2.8` sets the payload's status to rejected and re-throws the stored error on every later
 * render -- so a remount presents the same surface again, and for the commonest trigger the asset URL
 * itself is stale, so only a fresh document can pick up the new one.
 */
export const RELOAD_SCREEN_LABEL = 'Reload this screen';

/**
 * The two strings the shell's failed-screen surface renders, as one group.
 *
 * Assumptions: composed from the constants above and from the transcribed abend sentence rather than
 * restating any literal, for the reason {@link NOT_FOUND_MESSAGES} records: one operator-visible
 * sentence must have one literal, or the copies drift the first time one is corrected.
 */
export const SCREEN_LOAD_FAILURE_MESSAGES = {
  /** Headline. Transcribed: the baseline's own wording for a failure it cannot continue past. */
  TITLE: SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED,
  /** Explanation beneath the headline. Authored; the baseline has no chunk-load state. */
  EXPLANATION: SCREEN_LOAD_FAILED_DETAIL,
  /** Label of the single control. Authored. */
  RELOAD_CONTROL: RELOAD_SCREEN_LABEL,
} as const;

/**
 * The three strings the router's not-found surface renders, as one group.
 *
 * Purpose
 * -------
 * A browser can be asked for an address no screen answers -- a bookmark to a withdrawn path, a typed
 * URL, a stale link -- and the reference application had no equivalent state to transcribe: a 3270
 * operator chose from a menu and could not name a screen that did not exist. These strings are
 * therefore AUTHORED rather than transcribed, and each states that absence of provenance where it is
 * declared above rather than carrying a {@link SourceRef} nobody could cite.
 *
 * Refactoring Rationale: ⚠️ this group is COMPOSED from the three individual exports above rather than
 * restating their text. Two shapes for these strings were authored independently -- a group for the
 * surface that renders all three together, and individual constants for the router's own imports and
 * for the tests that assert one sentence at a time -- and both have callers. Duplicating the literals
 * would have satisfied both while creating the defect this catalogue exists to prevent: two copies of
 * one operator-visible sentence, which drift the first time one is corrected. Composition keeps a
 * single literal per string and leaves both call shapes working.
 *
 * Assumptions: the wording deliberately does NOT echo the rejected path, and the constants are phrased
 * so that a caller cannot. A path arrives from the address bar, so echoing it would paint text the
 * operator's browser supplied -- an account identifier or a card number mistyped into a URL would be
 * rendered straight back onto the screen and into any screenshot of it.
 */
export const NOT_FOUND_MESSAGES = {
  /** Headline of the surface. Authored: the baseline has no not-found state to transcribe. */
  TITLE: SCREEN_NOT_AVAILABLE_TITLE,
  /** Explanation beneath the headline. Authored, in the baseline's own vocabulary. */
  EXPLANATION: SCREEN_NOT_AVAILABLE_DETAIL,
  /**
   * Label of the single control. Authored.
   *
   * Assumptions: it names the destination the control actually reaches, and the two must agree -- a
   * label reading "Open cards" above a control that returns to the menu is worse than either.
   */
  RETURN_CONTROL: OPEN_CARD_BROWSE_LABEL,
} as const;

/**
 * The five strings the created-user credential surface renders, as one group.
 *
 * Purpose
 * -------
 * A user created at runtime is handed a generated one-time password in the create response, and the
 * operator has to read it, pass it on, and dismiss it. Nothing in the reference application corresponds
 * to that moment: its administrator TYPED a password into the create screen -- `PASSWDI` at
 * `app/cpy-bms/COUSR01.CPY` L78, checked for emptiness at `app/cbl/COUSR01C.cbl` L138 -- so the value
 * was already known to the person entering it and no screen ever had to disclose one back. Every string
 * below is therefore AUTHORED, and none carries a {@link SourceRef} because no COBOL source holds it.
 *
 * Refactoring Rationale: they live in this catalogue rather than beside the surface that renders them,
 * on the same reasoning recorded for {@link CARD_DETAIL_EDIT_CONTROL_LABEL}: a reader auditing this file
 * for completeness cannot tell a deliberately additive string from a forgotten one while it sits at its
 * point of use.
 *
 * Assumptions: the explanation states the credential's three operational properties -- shown once, not
 * retrievable, changed at first sign-on -- because each is a consequence the operator cannot discover
 * from the screen and would otherwise learn by losing an account. It does NOT mention the managed-secret
 * entry the value was archived to: recovering it from there needs a grant a browser session does not
 * hold, so naming it would offer this operator an action they cannot take.
 *
 * Assumptions: ⚠️ no string here interpolates the credential, and none may. These are static labels
 * around the value, so the value itself lives only in component state and reaches only the element that
 * displays it -- a message composed WITH it would put a live credential into a catalogue constant, a
 * shell message band, and any test snapshot that rendered either.
 */
export const CREDENTIAL_HANDOVER_MESSAGES = {
  /**
   * Heading of the surface, which is also the accessible label of the value beneath it. Authored.
   */
  TITLE: 'One-time password',
  /**
   * The explanation beneath the heading. Authored.
   *
   * Assumptions: it opens with the action rather than the caveats, because the operator's next step is
   * to pass the value on and the caveats only matter once they have.
   *
   * ⚠️ Refactoring Rationale: shortened from 118 characters to 70, for the field-width reason recorded
   * on {@link SCREEN_NOT_AVAILABLE_DETAIL}. "cannot be retrieved afterwards" was dropped as a
   * restatement of "shown once" rather than a second fact, and "at first sign-on" became "at sign-on"
   * because a credential that must be changed at sign-on is necessarily changed at the first one. The
   * action still leads and both surviving caveats follow it. Bounded by
   * {@link AUTHORED_OPERATOR_SENTENCES}.
   */
  EXPLANATION: 'Give this to the new user now. Shown once; must be changed at sign-on.',
  /**
   * Accessible name of the copy control. Authored.
   *
   * Assumptions: it names the thing copied rather than reading "Copy", because it is the accessible
   * name of an icon-only control and a screen reader announcing "Copy" beside a row of names would not
   * say what would land on the clipboard.
   */
  COPY_CONTROL: 'Copy one-time password',
  /**
   * Confirmation shown on the copy control once it has been used. Authored.
   */
  COPIED_CONFIRMATION: 'Copied',
  /**
   * Label of the control that dismisses the surface. Authored.
   *
   * Assumptions: it reads as the operator's own completion rather than as "Hide" or "Close", because
   * dismissing is irreversible here -- the value is not recoverable once the surface is gone -- and a
   * label promising only to hide something would understate that.
   */
  DISMISS_CONTROL: 'Done',
} as const;

/**
 * The strings the report screen paints while it follows one submitted report run.
 *
 * Purpose
 * -------
 * Submitting a report starts an asynchronous run, and the baseline could report nothing at all about
 * one: `SUBMIT-JOB-TO-INTRDR` at `app/cbl/CORPT00C.cbl` L462 writes job-control records to the `JOBS`
 * transient data queue, and `app/csd/CARDDEMO.CSD` L499-L505 defines that queue with
 * `ERROROPTION(IGNORE)` -- so even a failed write was silent and an operator learned the outcome by
 * looking at the job log on a different system. Every string below is therefore AUTHORED and carries
 * no {@link SourceRef}: there is no reference literal to transcribe, because there was no state to
 * describe.
 *
 * Assumptions: none of these strings is painted on row 23. That line is a parity surface carrying
 * this program's own nineteen sentences, and `ui/src/screens/reports/index.tsx` already declines to
 * paint the service's text there for exactly that reason; authored lifecycle text has the same
 * problem, so it is rendered in the screen's body instead. The band still carries the submission
 * acknowledgement and every refusal, unchanged.
 *
 * Assumptions: the six status labels are the OPERATOR's vocabulary and the contract's six tokens are
 * the orchestration's. They are mapped rather than shown raw because `PENDING_REDRIVE` and
 * `ABORTED` name mechanisms rather than outcomes, and an operator acts on the outcome. The run's own
 * reference is rendered beside them, so an operator quoting a run to support still has the value the
 * status operation is addressed by.
 *
 * Alternatives Considered: one sentence covering all three failure outcomes. Rejected because
 * `services/reporting-service/src/main/resources/openapi/reporting-api.yaml` states that the
 * distinctions are ones an operator acts on -- a timed-out run is retried, an aborted run was stopped
 * deliberately -- so collapsing them would delete the only information the three statuses carry that
 * a single "it failed" does not.
 *
 * Alternatives Considered: declaring each string as its own export and composing this group from
 * them, which is what {@link NOT_FOUND_MESSAGES} and {@link SCREEN_LOAD_FAILURE_MESSAGES} do.
 * Rejected here because those two have consumers for BOTH shapes -- a surface that renders the group
 * and a module that imports one sentence -- while every string below has exactly one call site, so
 * decomposing would add thirteen exports that nothing imports and two names for each sentence.
 */
export const REPORT_RUN_MESSAGES = {
  /** Heading of the region, naming what it is about rather than repeating the screen title. */
  HEADING: 'Submitted report run',
  /** Caption of the run's own reference, which is the value a status read is addressed by. */
  REFERENCE_CAPTION: 'Run reference',
  /** Caption of the run's current state. */
  STATUS_CAPTION: 'Status',
  /**
   * Label of the control that reads the run's status now.
   *
   * Assumptions: it names a status read and not a resubmission, because the two are a long way
   * apart -- one costs nothing and one starts a second run over the same range.
   */
  REFRESH_CONTROL: 'Refresh status',
  /**
   * Label of the control that collects the document a succeeded run produced.
   *
   * Assumptions: it names a download because that is what the control does -- the bytes arrive as an
   * attachment and the browser writes them to the file system. Calling it "View" would promise a
   * rendering this application deliberately does not perform: the document is 133-column fixed-width
   * text whose edit masks a golden-master comparison reads byte for byte, so re-rendering it here
   * would make this a second renderer of a parity artifact.
   */
  DOWNLOAD_CONTROL: 'Download report',
  /** Operator-facing name of each of the six states the contract publishes for a run. */
  STATUS_LABELS: {
    /** The run is going. */
    RUNNING: 'Running',
    /** The run finished and its document is stored. */
    SUCCEEDED: 'Completed',
    /** The run stopped without producing a document. */
    FAILED: 'Failed',
    /** The run exceeded the time the orchestration allows it. */
    TIMED_OUT: 'Timed out',
    /** The run was stopped deliberately before it finished. */
    ABORTED: 'Stopped',
    /** The run has been redriven and is waiting to restart, so it is still going. */
    PENDING_REDRIVE: 'Restarting',
  },
  /** Sentence for a run that stopped without producing a document. */
  FAILED_DETAIL: 'This report run did not complete. Submit the report again to retry it.',
  /**
   * Sentence for a run the orchestration stopped for exceeding its time limit.
   *
   * ⚠️ Refactoring Rationale: shortened from 118 characters to 73, for the field-width reason recorded
   * on {@link SCREEN_NOT_AVAILABLE_DETAIL}. The conditional tail -- "if it covers a long period" --
   * went, because a run that exceeded its limit covers a long period by definition, so the condition
   * was always true and the advice is therefore unconditional. Bounded by
   * {@link AUTHORED_OPERATOR_SENTENCES}.
   */
  TIMED_OUT_DETAIL: 'Report run exceeded its time limit. Submit it again with a shorter range.',
  /**
   * Sentence for a run somebody stopped deliberately.
   *
   * ⚠️ Refactoring Rationale: shortened from 85 characters to 65, for the field-width reason recorded
   * on {@link SCREEN_NOT_AVAILABLE_DETAIL}. "Submit the report again to retry it" became "Submit it
   * again": the pronoun is unambiguous after a sentence whose subject is the report run, and "to retry
   * it" restated the verb. Bounded by {@link AUTHORED_OPERATOR_SENTENCES}.
   */
  ABORTED_DETAIL: 'This report run was stopped before it completed. Submit it again.',
  /**
   * Sentence for a completed run whose document is not in the store.
   *
   * Assumptions: this state is reachable and is not a defect. The contract states that the result
   * location is null after a lifecycle rule has expired what the run wrote, so a run that completed
   * days ago can report success with nothing left to collect.
   *
   * ⚠️ Refactoring Rationale: shortened from 106 characters to 75 -- exactly the field width, with no
   * margin -- for the reason recorded on {@link SCREEN_NOT_AVAILABLE_DETAIL}. "This report run" became
   * "Report" and "Submit the report again to produce it" became "Submit it again to produce it". Both
   * facts the assumption above turns on survive: that the run SUCCEEDED, and that its document is gone
   * rather than pending. Bounded by {@link AUTHORED_OPERATOR_SENTENCES}, which is what makes a sentence
   * sitting on the limit safe to leave there.
   */
  DOCUMENT_UNAVAILABLE:
    'Report completed, but its document is no longer available. Submit it again.',
  /**
   * Sentence for a status read that did not answer.
   *
   * Assumptions: it says nothing about why. The reason is an HTTP status or a network failure, and
   * an operator can act on neither; the one action available is to read the status again, so that is
   * the one action named.
   */
  STATUS_READ_FAILED: 'The status of this report run could not be read. Refresh to try again.',
  /**
   * Sentence for a document collection that did not answer, on the same terms as a failed read.
   *
   * ⚠️ Refactoring Rationale: shortened from 77 characters to 72, for the field-width reason recorded
   * on {@link SCREEN_NOT_AVAILABLE_DETAIL}. This one overran by two characters, which is the case that
   * makes the bound worth asserting rather than reviewing: nothing about the rendered sentence looked
   * wrong, and the two words it lost -- "and try" for "to retry" -- were invisible either way.
   * Bounded by {@link AUTHORED_OPERATOR_SENTENCES}.
   */
  DOCUMENT_COLLECTION_FAILED:
    'The report document could not be collected. Refresh the status to retry.',
  /**
   * Sentence shown once the screen has stopped reading the status on its own.
   *
   * Assumptions: the stop is announced rather than silent, which is the whole reason this string
   * exists. A screen that quietly stopped updating would show a running state indefinitely and an
   * operator would read it as a run that never finishes.
   */
  AUTOMATIC_UPDATES_STOPPED:
    'Automatic status updates have stopped. Refresh to read the current status.',
} as const;

/**
 * Sentence shown when a page of the card browse could not be read at all.
 *
 * Purpose: the source screen composes `WS-FILE-ERROR-MESSAGE` (`app/cbl/COCRDLIC.cbl` L153-L172,
 * moved to the message field at L1254) by appending the internal file name and the CICS response and
 * reason codes. That is the class of detail this catalogue's redaction register withholds everywhere
 * else, and for the same reason: an internal identifier must not reach a browser, a log line or a bug
 * report. So this sentence is AUTHORED and carries no {@link SourceRef}, and it names the correlation
 * identifier the operator already holds instead of the file the request touched.
 *
 * ⚠️ Refactoring Rationale: this lives here rather than in `ui/src/screens/cardList/index.tsx`, which
 * declared it locally at 119 characters. Two things were wrong with that. The sentence overran the
 * {@link MESSAGE_BAND.workAreaWidth}-character message field by 44 characters and was truncated on
 * screen with no indication, and being screen-local it was outside every check this module applies to
 * operator-facing text -- including the bound in {@link AUTHORED_OPERATOR_SENTENCES} that would have
 * caught it. It is now 72 characters and inside that bound. The screen must import it and delete its
 * local copy; `ui/src/layout/MessageBand.test.tsx` holds a third variant of the same sentence as a
 * fixture and should use this one too, so all three stop drifting apart.
 *
 * Alternatives Considered: leaving it screen-local and asserting its length from the screen's own
 * test. Rejected because the audit found the same sentence written three ways in three files, which is
 * what a string with no single home produces; the migration plan's own instruction that every
 * user-visible string come from the catalogue exists to prevent exactly that.
 */
export const CARD_LIST_PAGE_UNAVAILABLE =
  'Card data is temporarily unavailable. Report it with the correlation id.';

/**
 * Sentence shown on the sign-on screen when a guard turned the operator away from a guarded route.
 *
 * Purpose: say why the operator is looking at sign-on. `ui/src/routes/guards.tsx` redirects an
 * operator holding no session to `/signon` and hands the entry a reason through
 * {@link https://developer.mozilla.org/docs/Web/API/History/state | history state} --
 * `SIGN_ON_BOUNCE_REASON` in `ui/src/routes/navigation.ts` -- and until now nothing rendered a
 * sentence for it: an audit measured the band present and EMPTY on all nineteen guarded routes, so
 * being thrown out of `/users/USER0100/delete` was indistinguishable from opening the application for
 * the first time.
 *
 * Assumptions: this sentence is AUTHORED and carries no {@link SourceRef}, because the condition it
 * describes cannot have one. A 3270 session was held by the CICS region for the terminal's
 * duration and no program could observe it ending -- there was no browser tab to reload, no
 * memory-only token store to evict, and `app/cbl/COSGN00C.cbl` accordingly declares exactly five
 * message literals (L120, L125, L242, L249, L254), none of them about a session that has gone away.
 * Transcribing one of the five would have been worse than authoring this: `Unable to verify the
 * User ...` reports a verification that was attempted, and none was.
 *
 * Assumptions: it says nothing about WHERE the operator was going, although the guard also carries
 * the attempted path. Naming the destination in the sentence would paint a value the address bar
 * supplied -- a mistyped account identifier or card number would be rendered straight back onto the
 * screen and into any screenshot of it -- which is the same withholding
 * {@link NOT_FOUND_MESSAGES} records for the rejected path.
 *
 * Alternatives Considered: wording it as an expiry (`Your session has expired`). Rejected because the
 * commonest cause measured was not an expiry at all but a document reload evicting a deliberately
 * memory-only token store, and a sentence naming a timeout would send the operator looking for an
 * idle limit that does not exist. "Has ended" is true of both causes. Bounded by
 * {@link AUTHORED_OPERATOR_SENTENCES}: 50 characters.
 */
export const SIGN_ON_SESSION_REQUIRED = 'Your session has ended. Sign on again to continue.';

/**
 * Sentence explaining the replacement-credential turn the sign-on screen presents.
 *
 * Purpose: explain what is being asked and why. The identity provider can answer a sign-on with
 * `NEW_PASSWORD_REQUIRED`, at which point the screen disables the identifier, relabels the credential
 * control and clears it -- and an audit measured the band EMPTY through all of that, so the operator
 * was asked for a new password with no statement that their own was accepted, that it is temporary or
 * expired, or that setting a permanent one is what completes the sign-on.
 *
 * Assumptions: AUTHORED, with no {@link SourceRef}, and the absence is structural rather than an
 * omission. The reference compared a password held in clear -- `05 SEC-USR-PWD PIC X(08).` at
 * `app/cpy/CSUSR01Y.cpy` L21, compared at `app/cbl/COSGN00C.cbl` L223 -- so it had no notion of a
 * credential that must be changed before use and no screen state to change one in;
 * `app/bms/COSGN00.bms` paints two input fields and no third. The turn exists here only because the
 * managed provider is what removed the plaintext column, which is why
 * {@link SIGN_ON_NEW_PASSWORD_LABEL} is authored beside it for the same reason.
 *
 * Assumptions: it opens by saying the presented credential was ACCEPTED, which is the fact the
 * provider's contract turns on -- `services/auth-service/src/main/resources/openapi/auth-api.yaml`
 * states that `NEW_PASSWORD_REQUIRED` means "the credential presented was correct but is temporary or
 * expired". Without it an operator reads the replacement prompt as a rejection and retypes the
 * password that in fact worked, which is precisely why that document also refuses to reuse
 * `Wrong Password. Try again ...` for this outcome.
 *
 * Assumptions: it states NO policy requirement -- no minimum length, no character classes. The
 * numbers are configured per environment (`infra/modules/cognito/variables.tf` defaults
 * `password_minimum_length` to 14 and refuses anything below 12), so a copy painted here is wrong the
 * first time an environment tightens it; the provider reports its exact requirement when it refuses,
 * and that sentence reaches the replacement control as a field refusal. This is the same reasoning
 * the retired width hint `D-SIGNON-RETIRED-WIDTH-HINT` is registered on.
 *
 * Alternatives Considered: naming the challenge itself, as in "A NEW_PASSWORD_REQUIRED challenge was
 * raised". Rejected because the token is the provider's vocabulary and an operator can act on none of
 * it; the register's other sentences name the operator's next action, and this one does too. Bounded
 * by {@link AUTHORED_OPERATOR_SENTENCES}: 65 characters.
 */
export const SIGN_ON_NEW_PASSWORD_REQUIRED =
  'Your password was accepted but must be replaced. Enter a new one.';

/**
 * Announcement a screen publishes while a request it issued is still in flight.
 *
 * Purpose: give every screen ONE sentence to announce a turn with, so the audible half of a busy
 * state can be stated. `busyAnnouncement` in `ui/src/layout/fieldHelp.tsx` renders a polite live
 * region and requires a sentence, and four screens declined to call it -- `ui/src/screens/signon`,
 * `ui/src/screens/transactionList`, `ui/src/screens/authSummary` and `ui/src/screens/refTypeList` --
 * each recording the same reason: this catalogue held no busy sentence, and inventing wording in a
 * screen would breach transformation rule T8. An audit measured the consequence: `aria-busy` on six
 * of thirteen busy states, on no button anywhere, and no live region announcing a request starting or
 * ending on any screen.
 *
 * Assumptions: AUTHORED, and no source can exist for it. The 3270 equivalent of this state is the
 * keyboard lock `CTRL=(ALARM,FREEKB)` gave a mapset for free -- a task holding the terminal left the
 * keyboard locked until it re-sent the map, so the terminal SAID nothing because it did not have to;
 * the operator physically could not type. Nothing in a browser locks the keyboard, so the property has
 * to be stated in words that no mapset ever painted.
 *
 * Assumptions: ONE sentence serves a browse page turn, a save and a delete, rather than one per
 * action. It is deliberately silent about which request is outstanding: the screen the operator is
 * looking at, the control they just pressed and this catalogue's own per-screen legends already say
 * that, and a per-action variant would multiply into five sentences that differ only in a verb -- with
 * the drift between them invisible, since no two are ever on screen together.
 *
 * Trade-offs: it asks the operator to wait rather than offering to cancel. No screen here can abort an
 * in-flight request -- `ui/src/api/client.ts` dispatches none with an abort signal -- so a sentence
 * offering a cancel would name an action the application cannot perform. Bounded by
 * {@link AUTHORED_OPERATOR_SENTENCES}: 55 characters.
 */
export const REQUEST_IN_PROGRESS = 'Working on your request. Wait for the screen to answer.';

/**
 * Sentence for a failure whose condition may clear on its own, so the same turn may later succeed.
 *
 * Purpose: let a screen say "not available at the moment" instead of "that did not work".
 * `ui/src/api/client.ts` already classifies this -- `ApiFailureRemedy.transient` is true for a
 * timeout and for the transient statuses, and `isTransientFailure` publishes the predicate -- and an
 * audit found the distinction never reaching a screen: a timeout, a dropped connection and a 500 were
 * rendered identically on every route, and one screen described a 404 as a temporary availability
 * problem because nothing on the failure said which it was.
 *
 * Assumptions: AUTHORED, and the condition cannot have a reference sentence. The transport it
 * describes did not exist: a 3270 program did not cross a network to answer a screen, and the CICS
 * response and reason codes it did report for a file failure are the class of internal detail this
 * catalogue's redaction register withholds -- which is the same ground
 * {@link CARD_LIST_PAGE_UNAVAILABLE} is authored on.
 *
 * Assumptions: it invites a retry WITHOUT promising one is safe, and the division of labour is
 * deliberate. This sentence answers "may the condition clear"; whether repeating THIS request is safe
 * is the separate `ApiFailureRemedy.repeatable` judgement, because a gateway failure on a payment is
 * transient and still not repeatable -- the write may already have landed. A screen therefore renders
 * this sentence from `transient` and gates any repeat CONTROL on `isRepeatableFailure`.
 *
 * Alternatives Considered: naming the timeout ("The service did not answer in time"). Rejected
 * because the transient class is wider than a timeout -- it includes the statuses a gateway or a
 * load balancer answers while a service is restarting -- so the narrower wording would be false on
 * most of the paths that reach it. Bounded by {@link AUTHORED_OPERATOR_SENTENCES}: 62 characters.
 */
export const TRANSIENT_FAILURE_TRY_AGAIN =
  'The service is not available at the moment. Try again shortly.';

/**
 * Sentence for a failure that repeating will not clear, so the operator is told rather than looped.
 *
 * Purpose: the other half of the split {@link TRANSIENT_FAILURE_TRY_AGAIN} opens. A failure the
 * client classifies as NOT transient -- a service that answered with a refusal it described, an
 * unreachable host, a body that was not a problem document -- fails again identically, so a screen
 * that invites a retry sends the operator into a loop with no exit. This says the turn did not
 * happen and names the one action left.
 *
 * Assumptions: AUTHORED. Where a service DOES send a sentence, that sentence is rendered verbatim and
 * this one is not used -- every screen here publishes `message` from the problem document first. This
 * is for the failures that carry no document at all, which is a browser-side classification no
 * reference program could have made.
 *
 * Assumptions: it names no code, no correlation identifier and no subsystem, although the failure
 * carries all three. An operator reads this sentence; a code in it would be a value they must copy
 * accurately under no guidance, and the audit's own finding on this class of wording is that a screen
 * asking for a correlation identifier it does not display asks for something the operator does not
 * have. The identifiers stay on the failure, where support reads them.
 *
 * Alternatives Considered: "Repeating this will not help", which is the honest mechanical statement.
 * Rejected because it leaves the operator with no action at all, and this register's other sentences
 * each name one. "Report it if it happens again" is the escalation the reference had no analogue for
 * -- a 3270 operator reported an abend by its code from row 23 -- and it is deliberately conditional,
 * because a single occurrence is not worth a report. Bounded by
 * {@link AUTHORED_OPERATOR_SENTENCES}: 61 characters.
 */
export const PERSISTENT_FAILURE_REPORT_IT =
  'That request did not complete. Report it if it happens again.';

/**
 * Every operator-facing sentence this application AUTHORED, as the register its width bound is
 * asserted over.
 *
 * Purpose: a service publishes an operator-facing message through a field of
 * {@link MESSAGE_BAND.workAreaWidth} characters, and the shell renders a box that wide. A longer
 * sentence is cut at the boundary with nothing on screen to say so, and the audit that found this had
 * to measure nine of them by hand. `ui/src/messages/messages.test.ts` asserts the bound over this
 * array, so the next one fails a test instead.
 *
 * Assumptions: it lists AUTHORED sentences only, and that distinction is the whole reason a bound is
 * enforceable at all. A sentence transcribed from `app/**` is verbatim under the migration's Rule T8 --
 * including the ones this catalogue preserves with a missing space, a double space or trailing spaces --
 * so a width failure on one of those would be asking for a change nobody is permitted to make. The
 * only correct response there is a rendering that does not truncate, which is a shell concern.
 *
 * Assumptions: enumerated by hand rather than derived by walking the module's exports. A derived list
 * would have to decide from a value whether it is an operator-facing SENTENCE, and this module also
 * holds field labels, screen titles, control names, status words and function-key legends -- none of
 * which is published through the message field, several of which are transcribed, and one of which is
 * a single space. Enumeration makes adding a sentence to the bound a deliberate act; derivation would
 * make it an accident in either direction.
 *
 * Trade-offs: an authored sentence a later change forgets to register is unbounded until somebody
 * notices. That is a real gap and it is the lesser one: the alternative admits transcribed text into a
 * check that would demand it be edited, and a wrongly-bounded verbatim string is a fidelity defect
 * where a wrongly-unbounded authored string is a truncation an operator can report.
 */
export const AUTHORED_OPERATOR_SENTENCES: readonly string[] = [
  SCREEN_NOT_AVAILABLE_DETAIL,
  ACCESS_DENIED_NOT_AUTHORIZED,
  SCREEN_LOAD_FAILED_DETAIL,
  CREDENTIAL_HANDOVER_MESSAGES.EXPLANATION,
  REPORT_RUN_MESSAGES.FAILED_DETAIL,
  REPORT_RUN_MESSAGES.TIMED_OUT_DETAIL,
  REPORT_RUN_MESSAGES.ABORTED_DETAIL,
  REPORT_RUN_MESSAGES.DOCUMENT_UNAVAILABLE,
  REPORT_RUN_MESSAGES.STATUS_READ_FAILED,
  REPORT_RUN_MESSAGES.DOCUMENT_COLLECTION_FAILED,
  REPORT_RUN_MESSAGES.AUTOMATIC_UPDATES_STOPPED,
  CARD_LIST_PAGE_UNAVAILABLE,
  /*
   * Assumptions: the five sentences below are registered in the same act that declares them, which is
   * what the note above means by "adding a sentence to the bound a deliberate act". Each describes a
   * condition the reference could not have had -- a browser session ending, a credential replacement
   * turn, a request in flight across a network, and the two halves of a transport failure -- so each
   * is authored rather than transcribed and each is therefore inside this bound rather than exempt
   * from it under rule T8.
   */
  SIGN_ON_SESSION_REQUIRED,
  SIGN_ON_NEW_PASSWORD_REQUIRED,
  REQUEST_IN_PROGRESS,
  TRANSIENT_FAILURE_TRY_AGAIN,
  PERSISTENT_FAILURE_REPORT_IT,
] as const;
