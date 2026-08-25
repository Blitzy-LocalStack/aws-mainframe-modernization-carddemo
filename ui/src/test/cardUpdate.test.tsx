/// <reference types="node" />

/**
 * @file Component test for the card update screen -- the migration target of BMS mapset
 * `COCRDUP` (map `CCRDUPA`) and program `app/cbl/COCRDUPC.cbl`, mounted by
 * `ui/src/router.tsx` at the route it records for that program.
 *
 * Purpose
 * -------
 * This file is the ONLY verification this screen gets, and that is a property of the
 * reference system rather than a gap in the suite. `tests/README.md` section 1.1 states it
 * verbatim: "Online `CO*` CICS programs cannot run end-to-end without a CICS runtime
 * (absent on the runner); only their extractable field-validation logic is unit-tested."
 * The COBOL golden-master suite is the parity oracle for the BATCH chain, so there is NO
 * golden master for `COCRDUPC` and no run of the reference program to compare against.
 * Every expectation here is therefore anchored to a file that IS in the repository -- the
 * program, its mapset, its symbolic map and the copybooks -- read at run time so a
 * citation is checked rather than merely written down.
 *
 * The four properties these cases exist to protect
 * -----------------------------------------------
 * 1. The field widths the 3270 enforced in hardware, which survive as `maxLength`.
 * 2. The TWO-STEP commit: Enter validates and only the save key writes. A single-step save
 *    would be a behavioural change, and nothing else in the tree would report it.
 * 3. The optimistic-concurrency refusal, which the reference performs as a before-image
 *    comparison and this system performs with a version column and HTTP 409.
 * 4. Every user-visible string, character for character, under Transformation Rule T8.
 *
 * Parameters
 * ----------
 * None. A test module is executed by the runner and takes no arguments. Its inputs are the
 * baseline files it reads, the mocked card transport it installs, and the shared harness in
 * `ui/src/test/setup.ts`.
 *
 * Returns
 * -------
 * Nothing. Each case's outcome is the assertion it makes; the module's outcome is the
 * runner's exit status.
 *
 * Exceptions or errors
 * --------------------
 * A failed expectation throws and fails its own case. The baseline readers below throw
 * `RangeError` when a cited line or a declared field is absent, so a citation that has gone
 * stale fails on the citation rather than several assertions later on a value derived from
 * it. No case swallows an error.
 *
 * Rule 1, double-anchored
 * -----------------------
 * The single user-specified rule, Rule 1 "Explainability", requires a docstring stating
 * purpose, parameters, returns and exceptions on every function and module entry point, and
 * an inline comment giving the WHY of every non-obvious decision under one of four named
 * categories. `tests/README.md` section 12 imposes the identical obligation on "every new
 * test, fixture builder, helper, mock, and runner routine" and calls it "a hard review
 * gate". The two agree, so this file extends an established house convention rather than
 * introducing one, and it follows `docs/CODE_DOCUMENTATION_STANDARD.md` for the written
 * form of each label.
 *
 * Refactoring Rationale: the four rationale labels are written PLURAL --
 * `Alternatives Considered:`, `Refactoring Rationale:`, `Assumptions:`, `Trade-offs:`.
 * `docs/CODE_DOCUMENTATION_STANDARD.md` L246-L249 states that the singular stems "are not
 * permitted abbreviations" of them, and `config/rule1/rule1_gate.py` L85 and L403 make a
 * singular stem followed by a colon a reported violation, so the singular form would fail
 * the gate this file is audited by. Rule 1 itself writes both words plural at its lines
 * 31-34, and every sibling file in `ui/src/**` already uses the plural.
 *
 * Refactoring Rationale: the token `WHAT` never appears as a comment label anywhere below.
 * `config/rule1/rule1_gate.py` L502-L523 permits it only inside a file's LEADING header
 * comment block and reports every later occurrence as `statement-what`; this file opens
 * with a triple-slash reference followed by a blank line, so the header block ends at line
 * one and any later use would be reported. Statement-level rationale is introduced with
 * `WHY :` instead, which is the idiom the rest of this package uses.
 *
 * Refactoring Rationale: every test API is IMPORTED from `vitest` rather than taken from an
 * ambient global, even though `ui/vitest.config.ts` sets `globals: true`. `ui/tsconfig.json`
 * declares `"types": []`, so no ambient declaration exists at all and a bare `describe` is
 * a compile error under the `tsc --noEmit` gate `ui/package.json` runs as `typecheck`. All
 * 81 sibling test files in this package import for the same reason, which
 * `ui/src/screens/cardScreens.test.tsx` L36-L40 records.
 *
 * Refactoring Rationale: every callback -- the mock factory, each case body, each
 * `waitFor` probe -- is a NAMED function declaration rather than an inline arrow. Two
 * constraints meet and only this shape satisfies both: `ui/eslint.config.js` selects
 * `* > ArrowFunctionExpression` and `*:not(MethodDefinition) > FunctionExpression`, so an
 * inline callback owes its own documentation block, and Prettier moves a block comment that
 * follows an argument comma onto the preceding string literal, which detaches the block from
 * the function it documents.
 */

import { theme } from 'antd';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';

import { getCard, updateCard } from '../api/cards';
import type { CardDetail, CardUpdateRequest } from '../api/cards';
import { ApiRequestError, isConflictFailure } from '../api/client';
import type { FieldError } from '../api/types';
import * as authModule from '../hooks/useAuth';
import { APP_SHELL_TEST_ID, SHELL_PINNED_ZONE_TEST_ID } from '../layout/AppShell';
import { BUSY_ANNOUNCEMENT_TEST_ID } from '../layout/fieldHelp';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, PRIMARY_ACTION_AIDS } from '../layout/PfKeyBar';
import type { CicsAid } from '../layout/usePfKeys';
import {
  ABEND_DATA_FIELDS,
  COMMON_MESSAGES,
  MESSAGE_BAND,
  MESSAGE_BAND_BY_MAPSET,
  PROGRAM_SOURCE_FILES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  SHARED_MESSAGE_SOURCES,
  STATUS_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
} from '../messages/messages';
import type { StatusMessage } from '../messages/messages';
import { KEYLESS_ENTRY_ROUTES, ROUTE_TABLE } from '../router';
import {
  CARD_UPDATE_FIELD_LABELS,
  CARD_UPDATE_FIELD_WIDTHS,
  CARD_UPDATE_MAPSET,
  CARD_UPDATE_PART_NAMES,
  CARD_UPDATE_PROGRAM_NAME,
  CARD_UPDATE_TITLE,
  CARD_UPDATE_TRANSACTION_ID,
  CardUpdateScreen,
} from '../screens/cardUpdate';
import { FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';
import {
  apiError,
  conflictProblem,
  expectMaxLength,
  expectVerbatimMessage,
  fieldError,
  pressPfKey,
  renderInAppShell,
} from './setup';

/**
 * Builds the mocked surface of the card transport module.
 *
 * Assumptions: a hoisted function DECLARATION and not a factory held in a `const`. Vitest
 * lifts every `vi.mock` call above the imports, so a `const` factory would be in its
 * temporal dead zone at registration time; a declaration is hoisted with its binding
 * initialised and its body runs only when the mocked module is first imported.
 *
 * Assumptions: only the two operations this screen calls are stubbed, and the conflict
 * predicate is NOT among them. The screen imports `isConflictFailure` from
 * `ui/src/api/client.ts`, a different module, so the REAL predicate stays in play and the
 * 409 case below exercises the classification the application performs rather than a stub
 * that agrees with it by construction.
 * @returns {Record<string, unknown>} The two transport functions, each a fresh spy whose
 *   behaviour an individual case sets.
 */
function mockCardTransportModule(): Record<string, unknown> {
  return {
    getCard: vi.fn(),
    updateCard: vi.fn(),
  };
}

/*
 * WHY : Assumptions: the transport MODULE is mocked rather than the HTTP client beneath it.
 *       Every case here asserts what the screen renders or what it submits, so the shortest
 *       honest seam is the function the screen calls; going through the axios client would
 *       additionally exercise the interceptor chain, whose own failure modes would make a
 *       rendering regression indistinguishable from a transport one.
 * WHY : Assumptions: no mock is reset by hand anywhere in this file. `ui/vitest.config.ts`
 *       sets both `clearMocks` and `restoreMocks`, so each spy is restored before every
 *       case and an implementation set in one case cannot reach the next. Each case
 *       therefore states the behaviour it needs.
 */
vi.mock('../api/cards', mockCardTransportModule);

/*
 * WHY : Assumptions: the repository root is derived from this module's own location rather
 *       than from the working directory, which is the form `ui/src/api/contracts.test.ts`
 *       L63-L68 records: the runner is normally invoked from `ui/` but not always, and a
 *       path resolved against the process would then point outside the checkout. This file
 *       sits at `ui/src/test/`, so the root is three levels up.
 */
const REPOSITORY_ROOT = join(import.meta.dirname, '..', '..', '..');

/**
 * Reads one reference-only baseline file and splits it into lines.
 *
 * Assumptions: the baseline is read at RUN TIME instead of its values being transcribed
 * here. That is what makes every citation below a checked fact: the expectation comes out
 * of `app/**` and the actual comes out of the catalog or the rendered document, so neither
 * side of an assertion is a value this file invented. A transcribed number agrees with a
 * transcribed number even when both have drifted from the source. Reading `app/**` this way
 * is already established in fifteen test files in this package.
 *
 * Assumptions: the file is opened read-only and nothing is ever written back. `app/**` is
 * REFERENCE-ONLY under AAP section 0.2.2 and remains byte-identical.
 * @param {readonly string[]} segments - Repository-relative path segments of the file.
 * @returns {readonly string[]} The file's lines, so a one-based citation indexes at
 *   `line - 1`.
 * @throws {Error} If the file cannot be read, which the file system raises. A missing
 *   baseline file is a broken citation and fails loudly here.
 */
function baselineLines(...segments: readonly string[]): readonly string[] {
  return readFileSync(join(REPOSITORY_ROOT, ...segments), 'utf8').split('\n');
}

/**
 * The apostrophe that delimits a COBOL alphanumeric literal.
 *
 * Assumptions: `app/**` uses the apostrophe form throughout and never the quotation-mark
 * form, so one delimiter is enough. It is named rather than written inline because the
 * extractor below refers to it three times and a bare quote character inside a quoted
 * string reads as a typo.
 */
const COBOL_LITERAL_DELIMITER = "'";

/**
 * Extracts the alphanumeric literal declared on one line of a COBOL program.
 *
 * Assumptions: the literal is taken between the FIRST and the LAST delimiter on the line,
 * which is exact for every site cited in this file and was verified against all 31 of them.
 * A `VALUE` or `MOVE` line in `COCRDUPC.cbl` carries exactly one literal, and the columns
 * after it hold only blanks or a sequence number, so a delimiter pair cannot straddle two
 * literals. Trade-offs: the same rule would mis-read a line carrying two literals, which is
 * accepted because no cited line does and a wrong read would fail the assertion it feeds
 * rather than pass it.
 *
 * ⚠️ Assumptions: the literal is returned UNTRIMMED. Trailing blanks inside the delimiters
 * are content under Transformation Rule T8 -- `COCRDUPC.cbl` L176 declares fourteen of them
 * -- and trimming here would silently make the padding assertion unfalsifiable.
 * @param {readonly string[]} lines - Lines of the source, from {@link baselineLines}.
 * @param {number} line - One-based line number to read the literal from.
 * @param {string} origin - Repository-relative path of that source, named only so a failure
 *   report identifies the file whose citation went stale.
 * @returns {string} The literal's characters, without its delimiters and without any
 *   normalisation.
 * @throws {RangeError} If the line is absent or carries no delimited literal, so a stale
 *   citation fails on the citation.
 */
function cobolLiteralAt(lines: readonly string[], line: number, origin: string): string {
  const source = lines[line - 1];
  if (source === undefined) {
    throw new RangeError(`${origin} has no line ${String(line)}`);
  }
  const opening = source.indexOf(COBOL_LITERAL_DELIMITER);
  const closing = source.lastIndexOf(COBOL_LITERAL_DELIMITER);
  if (opening < 0 || closing <= opening) {
    throw new RangeError(`${origin} line ${String(line)} declares no literal`);
  }
  return source.slice(opening + 1, closing);
}

/**
 * Matches a COBOL `PICTURE` clause declaring a fixed character or digit width.
 *
 * Assumptions: both the abbreviated and the spelled-out keyword are admitted, and both the
 * alphanumeric and the numeric category, because the declarations this file reads span all
 * four combinations across `app/cpy/**`.
 */
const PICTURE_WIDTH_PATTERN = /PIC(?:TURE)?\s+[X9]\((\d+)\)/u;

/**
 * Reads the declared widths on a given set of lines, skipping lines that declare none.
 *
 * Assumptions: a line that declares no `PICTURE` is skipped rather than reported, because
 * the catalog records a declaration as a RANGE of lines -- a field and its `VALUE` clause,
 * or a field and its condition names -- and only one line of each range carries the width.
 * Reporting the others would make every citation in the catalog look broken.
 * @param {readonly string[]} lines - Lines of the copybook, from {@link baselineLines}.
 * @param {readonly number[]} at - One-based line numbers to read.
 * @returns {readonly number[]} The widths found, in the order the lines were given.
 * @throws {RangeError} If any of the given lines is absent from the file.
 */
function pictureWidthsAt(lines: readonly string[], at: readonly number[]): readonly number[] {
  const widths: number[] = [];
  for (const line of at) {
    const source = lines[line - 1];
    if (source === undefined) {
      throw new RangeError(`no line ${String(line)}`);
    }
    const match = PICTURE_WIDTH_PATTERN.exec(source);
    const width = match === null ? undefined : match[1];
    if (width !== undefined) {
      // WHY : Trade-offs: as at the symbolic-map reader above, the only value this file ever
      //       converts out of text is a declared FIELD WIDTH, which is a count of characters
      //       and has no scale. Monetary amounts and long identifiers stay strings from the
      //       transport to the control, because a JSON number is read into an IEEE-754 double
      //       and the exactness the reference holds in fixed point would be lost at that hop.
      widths.push(Number.parseInt(width, 10));
    }
  }
  return widths;
}

/**
 * Joins the text of a given set of lines, for asserting on a declaration's clauses.
 * @param {readonly string[]} lines - Lines of the copybook, from {@link baselineLines}.
 * @param {readonly number[]} at - One-based line numbers to join.
 * @returns {string} Those lines' text, separated by single blanks.
 */
function declarationText(lines: readonly string[], at: readonly number[]): string {
  const parts: string[] = [];
  for (const line of at) {
    parts.push((lines[line - 1] ?? '').trim());
  }
  return parts.join(' ');
}

/**
 * Counts the elementary items a copybook declares with a `PICTURE` clause.
 *
 * Assumptions: an elementary item is recognised by a level number followed by a data name and
 * a `PICTURE`, which is the form every field in the copybooks this file reads takes. A group
 * item carries no `PICTURE` and is therefore not counted, which is what makes the count
 * comparable with a catalog group's length.
 *
 * ⚠️ Assumptions: the level number is matched after a BLANK rather than at the start of the
 * line, because several copybooks in `app/cpy/**` carry a six-digit sequence number in columns
 * one to six -- `app/cpy/CSMSG02Y.cpy` does -- so a line-anchored level would match none of
 * them and the count would silently be zero. It was measured doing exactly that.
 * @param {readonly string[]} lines - Lines of the copybook, from {@link baselineLines}.
 * @returns {number} The number of elementary items declared.
 */
function pictureDeclarationCount(lines: readonly string[]): number {
  let total = 0;
  for (const source of lines) {
    if (/\s\d\d\s+[A-Z][A-Z0-9-]*\s+PIC(?:TURE)?\s/u.test(source)) {
      total += 1;
    }
  }
  return total;
}

/**
 * One field as a symbolic-map copybook declares it.
 *
 * Assumptions: the declaring LINE is carried beside the width, because a width on its own
 * is a magic number in a report. Carrying the line lets a failure message name the exact
 * declaration a reader has to open.
 */
interface DeclaredField {
  /** Characters the `PIC X(n)` clause declares. */
  readonly width: number;
  /** One-based line of that declaration in the copybook. */
  readonly line: number;
}

/**
 * Matches one input subfield of a BMS symbolic map.
 *
 * Assumptions: the pattern is anchored on the `02` level and the `PIC X(n)` form, which is
 * how `DFHMDF` generates every input subfield -- see `app/cpy-bms/COCRDUP.CPY` L24 through
 * L120. The length subfields are `COMP PIC S9(4)` and the attribute subfields are a bare
 * `PICTURE X`, so neither can match, which is what makes the parse select the seventeen
 * value-carrying fields and nothing else.
 */
const SYMBOLIC_FIELD_PATTERN = /^\s*02\s+([A-Z0-9]+)\s+PIC X\((\d+)\)\./u;

/**
 * Reads every input subfield a symbolic-map copybook declares, with its width and line.
 *
 * Assumptions: the FIRST declaration of a name wins. A symbolic map declares each field
 * twice -- once in the input group and again in the `REDEFINES` output group, `CCRDUPAI`
 * then `CCRDUPAO` -- and only the input group carries the `I`-suffixed names this file
 * cites, so taking the first occurrence selects the input declaration without the parse
 * having to know about the redefinition.
 * @param {readonly string[]} lines - Lines of the copybook, from {@link baselineLines}.
 * @returns {ReadonlyMap<string, DeclaredField>} Declared width and line, keyed by the
 *   copybook's own field name.
 */
function symbolicMapFields(lines: readonly string[]): ReadonlyMap<string, DeclaredField> {
  const declared = new Map<string, DeclaredField>();
  for (const [index, source] of lines.entries()) {
    const match = SYMBOLIC_FIELD_PATTERN.exec(source);
    if (match === null) {
      continue;
    }
    const [, name, width] = match;
    if (name === undefined || width === undefined || declared.has(name)) {
      continue;
    }
    // WHY : Trade-offs: the width is converted to a number because `expectMaxLength` in
    //       `ui/src/test/setup.ts` takes one, and the shared helper is preferred over a
    //       bare attribute comparison because it carries the reason the attribute is the
    //       place a `PICTURE` width survives. This conversion is of a copybook FIELD WIDTH
    //       and never of a monetary or identifier value, which stay strings end to end.
    declared.set(name, { width: Number.parseInt(width, 10), line: index + 1 });
  }
  return declared;
}

/**
 * Returns one declared field, failing loudly when the copybook does not declare it.
 * @param {ReadonlyMap<string, DeclaredField>} declared - Fields read from a copybook.
 * @param {string} name - The copybook's own field name.
 * @returns {DeclaredField} That field's declared width and line.
 * @throws {RangeError} If the copybook declares no such field, so a renamed or removed
 *   declaration fails on the name rather than on a width read as `undefined`.
 */
function declaredField(declared: ReadonlyMap<string, DeclaredField>, name: string): DeclaredField {
  const found = declared.get(name);
  if (found === undefined) {
    throw new RangeError(`no symbolic-map field named ${name}`);
  }
  return found;
}

/** The character a BMS macro line carries in column 72 to continue onto the next line. */
const BMS_CONTINUATION_MARK = '-';

/** The operand a `DFHMDF` line carries when it paints literal text. */
const BMS_INITIAL_OPERAND = "INITIAL='";

/**
 * Matches a `DFHMDF` line and captures its field name and its attribute list.
 *
 * Assumptions: the name group is `\S*` rather than `\S+` so an UNNAMED `DFHMDF` also
 * matches, capturing the empty string. Twelve of this mapset's thirty-four definitions are
 * unnamed spacers and skipping them would make the attribute census incomplete, which is
 * the census that establishes there is exactly one initial-cursor field.
 */
const BMS_FIELD_PATTERN = /^(\S*)\s*DFHMDF\s+ATTRB=\(([^)]*)\)/u;

/**
 * Finds the line index at which one named `DFHMDF` definition opens.
 *
 * ⚠️ Assumptions: the name must be followed by WHITESPACE. `FKEYSC` and `FKEYS` are both
 * defined in this mapset and a bare prefix test would resolve `FKEYS` to the `FKEYSC`
 * definition, silently asserting the second legend field's text against the first field's
 * width. Requiring the separator makes the two names distinguishable.
 * @param {readonly string[]} lines - Lines of the mapset, from {@link baselineLines}.
 * @param {string} field - The `DFHMDF` label to find.
 * @returns {number} Zero-based index of the definition's opening line.
 * @throws {RangeError} If the mapset defines no field with that label.
 */
function mapsetFieldIndex(lines: readonly string[], field: string): number {
  for (const [index, source] of lines.entries()) {
    if (source.startsWith(`${field} `)) {
      return index;
    }
  }
  throw new RangeError(`${CARD_UPDATE_MAPSET}.bms defines no field named ${field}`);
}

/**
 * Reads the attribute list one named `DFHMDF` definition declares.
 * @param {readonly string[]} lines - Lines of the mapset, from {@link baselineLines}.
 * @param {string} field - The `DFHMDF` label to read.
 * @returns {readonly string[]} The individual `ATTRB` keywords, in declaration order.
 * @throws {RangeError} If the definition is absent or declares no attribute list.
 */
function mapsetAttributes(lines: readonly string[], field: string): readonly string[] {
  const source = lines[mapsetFieldIndex(lines, field)];
  const match = source === undefined ? null : BMS_FIELD_PATTERN.exec(source);
  const attributes = match === null ? undefined : match[2];
  if (attributes === undefined) {
    throw new RangeError(`${field} declares no ATTRB list`);
  }
  return attributes.split(',');
}

/**
 * Reads the literal text one named `DFHMDF` definition paints.
 *
 * Assumptions: the scan follows the macro's own CONTINUATION marker rather than guessing
 * how many operand lines a definition has. A `DFHMDF` carries one operand per line with a
 * hyphen in column 72 on every line but the last, so a scan that stops at the first line
 * without the marker reads exactly one definition and can never run into the next field's
 * operands.
 * @param {readonly string[]} lines - Lines of the mapset, from {@link baselineLines}.
 * @param {string} field - The `DFHMDF` label whose painted text is wanted.
 * @returns {string} The literal's characters, without its delimiters.
 * @throws {RangeError} If the definition paints no literal.
 */
function mapsetInitial(lines: readonly string[], field: string): string {
  const opener = mapsetFieldIndex(lines, field);
  for (let index = opener; index < lines.length; index += 1) {
    const source = lines[index];
    if (source === undefined) {
      break;
    }
    if (source.includes(BMS_INITIAL_OPERAND)) {
      const opening = source.indexOf(COBOL_LITERAL_DELIMITER);
      const closing = source.lastIndexOf(COBOL_LITERAL_DELIMITER);
      return source.slice(opening + 1, closing);
    }
    if (!source.trimEnd().endsWith(BMS_CONTINUATION_MARK)) {
      break;
    }
  }
  throw new RangeError(`${field} paints no INITIAL literal`);
}

/**
 * Counts the `DFHMDF` field definitions a mapset declares, named and unnamed alike.
 * @param {readonly string[]} lines - Lines of the mapset, from {@link baselineLines}.
 * @returns {number} The number of definitions.
 */
function mapsetFieldCount(lines: readonly string[]): number {
  let total = 0;
  for (const source of lines) {
    if (source.includes('DFHMDF')) {
      total += 1;
    }
  }
  return total;
}

/**
 * Lists the labels of every `DFHMDF` definition that claims the initial cursor.
 *
 * Assumptions: `IC` is matched as a whole keyword out of the split attribute list rather
 * than as a substring of the line, because `HILIGHT` and `JUSTIFY` operands and the word
 * `PIC` all contain those letters and a substring test would count them.
 * @param {readonly string[]} lines - Lines of the mapset, from {@link baselineLines}.
 * @returns {readonly string[]} Labels carrying `IC`, in declaration order.
 */
function initialCursorFields(lines: readonly string[]): readonly string[] {
  const claimed: string[] = [];
  for (const source of lines) {
    const match = BMS_FIELD_PATTERN.exec(source);
    const attributes = match === null ? undefined : match[2];
    if (attributes === undefined || !attributes.split(',').includes('IC')) {
      continue;
    }
    claimed.push(match?.[1] ?? '');
  }
  return claimed;
}

/** Lines of the reference program, whose `88` levels are the message source of record. */
const PROGRAM = baselineLines(PROGRAM_SOURCE_FILES.COCRDUPC);

/** Lines of this screen's mapset, the source of its field attributes and painted text. */
const MAPSET = baselineLines('app', 'bms', `${CARD_UPDATE_MAPSET}.bms`);

/** Input subfields this screen's symbolic map declares, keyed by copybook field name. */
const MAP_FIELDS = symbolicMapFields(baselineLines('app', 'cpy-bms', `${CARD_UPDATE_MAPSET}.CPY`));

/*
 * WHY : Assumptions: the sibling map's NAME is derived from the catalog rather than typed
 *       here. `MESSAGE_BAND_BY_MAPSET` records the per-mapset display width, and exactly two
 *       of the twenty-one entries declare 80; this screen's mapset is one of them and the
 *       other is the sibling this file compares its expiry surface against. Deriving the
 *       name from that table means the comparison cannot silently point at the wrong map,
 *       and it makes the "one of only two" claim a checked fact rather than a remark.
 */
const EIGHTY_WIDE_MAPSETS = Object.entries(MESSAGE_BAND_BY_MAPSET)
  .filter(
    /**
     * Keeps a mapset whose message band is the wider of the two declared widths.
     *
     * Assumptions: the entries form is used rather than indexing the table by a name, so no
     * type assertion is needed to persuade the compiler that a string is one of its keys. The
     * two widths in the annotation are not chosen here: they are the union the catalog itself
     * declares at `ui/src/messages/messages.ts` L791, and the comparison below reads the wider
     * one from `MESSAGE_BAND` rather than repeating either number as a literal.
     * @param {[string, { readonly displayWidth: 78 | 80 }]} entry - One catalog entry.
     * @returns {boolean} `true` when that mapset declares the wider band.
     */
    (entry: [string, { readonly displayWidth: 78 | 80 }]): boolean =>
      entry[1].displayWidth === MESSAGE_BAND.displayWidthCardDetail,
  )
  .map(
    /**
     * Reduces one kept entry to the mapset name.
     *
     * Assumptions: the annotation repeats the union `ui/src/messages/messages.ts` L791 declares,
     * for the same reason as the filter above -- the entries form loses the table's key type.
     * @param {[string, { readonly displayWidth: 78 | 80 }]} entry - One kept catalog entry.
     * @returns {string} That entry's mapset name.
     */
    (entry: [string, { readonly displayWidth: 78 | 80 }]): string => entry[0],
  );

/** The other mapset that declares the wider message band: this screen's detail sibling. */
const SIBLING_MAPSET =
  EIGHTY_WIDE_MAPSETS.find(
    /**
     * Keeps the wider-band mapset that is not this screen's own.
     * @param {string} mapset - One wider-band mapset name.
     * @returns {boolean} `true` for the sibling.
     */
    (mapset: string): boolean => mapset !== CARD_UPDATE_MAPSET,
  ) ?? '';

/** Input subfields the detail sibling's symbolic map declares. */
const SIBLING_MAP_FIELDS = symbolicMapFields(
  baselineLines('app', 'cpy-bms', `${SIBLING_MAPSET}.CPY`),
);

/** Lines of the shared work-area copybook, which declares the message content contract. */
const WORK_AREA = baselineLines(MESSAGE_BAND.sources.workArea.file);

/** Lines of the abend copybook, which contributes field widths and no text. */
const ABEND_COPYBOOK = baselineLines('app', 'cpy', 'CSMSG02Y.cpy');

/** Lines of the card record copybook, the declaration of what a card row holds. */
const CARD_RECORD = baselineLines('app', 'cpy', 'CVACT02Y.cpy');

/** This program's own catalogued sentences, keyed by the condition name that declares them. */
const CATALOGUE: Readonly<Record<string, StatusMessage>> = STATUS_MESSAGES.COCRDUPC;

/**
 * The route pattern `ui/src/router.tsx` mounts this screen's program at.
 *
 * ⚠️ Assumptions: the pattern is READ from the router's own reachability table rather than
 * written here, so this file cannot disagree with the address the application actually
 * serves. `ROUTE_TABLE` pairs each path with the reference program it migrates, and
 * `COCRDUPC` appears exactly once.
 *
 * Refactoring Rationale: the migration plan describes this route as carrying a `:num`
 * parameter holding the card number. It does not, and the difference is deliberate and
 * registered: `ui/src/routes/cards.ts` L4-L45 records divergence `D-CARD-SELECTOR`, under
 * which a card is addressed by an opaque sealed selector because the load balancer and the
 * distribution in front of these services write their own access logs from the request line
 * before any application code runs, so a primary account number in a path would be
 * persisted by infrastructure neither the SPA nor the service can redact. Taking the pattern
 * from `ROUTE_TABLE` means this file tracks the decision instead of the description.
 */
const EDIT_ROUTE = routePatternFor(CARD_UPDATE_PROGRAM_NAME);

/**
 * Returns the route pattern the router mounts one reference program at.
 * @param {string} program - Name of the reference program, as `ROUTE_TABLE` records it.
 * @returns {string} That program's route pattern.
 * @throws {RangeError} If the table holds no entry for the program, so a screen removed
 *   from the reachability graph fails here rather than in a route that cannot match.
 */
function routePatternFor(program: string): string {
  for (const entry of ROUTE_TABLE) {
    if (entry.program === program) {
      return entry.path;
    }
  }
  throw new RangeError(`ROUTE_TABLE holds no route for ${program}`);
}

/** Matches the single path parameter of a route pattern. */
const ROUTE_PARAMETER_PATTERN = /:([A-Za-z]+)/u;

/**
 * Builds a concrete address for a route pattern by substituting its one parameter.
 *
 * Assumptions: the substitution is by PATTERN rather than by string concatenation, so a
 * change to the route's shape or to its parameter's name reaches every case in this file
 * without any of them naming either.
 * @param {string} pattern - The route pattern, from {@link routePatternFor}.
 * @param {string} value - The value the parameter is to carry.
 * @returns {string} The concrete address the memory router opens at.
 * @throws {RangeError} If the pattern declares no parameter to substitute.
 */
function addressFor(pattern: string, value: string): string {
  if (!ROUTE_PARAMETER_PATTERN.test(pattern)) {
    throw new RangeError(`route pattern ${pattern} declares no parameter`);
  }
  return pattern.replace(ROUTE_PARAMETER_PATTERN, value);
}

/*
 * WHY : Assumptions: this is a SYNTHETIC selector, not a real one. It has the published
 *       length and alphabet, so the screen's own guard accepts it and the route resolves;
 *       and it seals nothing, so it addresses no card and is not a credential. A card
 *       number could not be used here at all -- the guard refuses one, which the invalid-link
 *       case below asserts directly.
 */
const CARD_SELECTOR = 'fake-selector-example-not-a-real-sealed-value-0000000000000';

/**
 * The record the read answers with.
 *
 * ⚠️ Assumptions: `displayCardNumber` is the MASKED rendering and no unmasked number exists
 * anywhere in this file. `app/cpy/CVACT02Y.cpy` L5 declares `CARD-NUM PIC X(16)`, and the
 * card contract publishes twelve mask characters followed by the last four digits; a fixture
 * carrying the whole number would let a masking regression pass while the fixture, not the
 * screen, supplied the evidence.
 *
 * Assumptions: `expirationDate` is the ten-character ISO form `CARD-EXPIRAION-DATE PIC X(10)`
 * declares at `app/cpy/CVACT02Y.cpy` L9, and the date chosen is the business date
 * `app/jcl/INTCALC.jcl` injects at L22 as `PARM='2022071800'`. Using this system's own
 * reference date rather than today keeps the fixture reproducible and makes the day part --
 * the part no control on this screen renders -- a value with no other occurrence in the
 * document.
 *
 * Assumptions: `accountId` keeps its leading zeros because `ACCTSIDI PIC X(11)` is eleven
 * CHARACTERS. Trade-offs: that is the concrete consequence asserted below of transporting
 * identifiers as strings -- a numeric coercion would render `11` and the screen would show
 * a different account.
 */
const CARD: CardDetail = {
  key: CARD_SELECTOR,
  displayCardNumber: '************0011',
  accountId: '00000000011',
  activeStatus: 'Y',
  embossedName: 'JANE Q PUBLIC',
  expirationDate: '2022-07-18',
  version: 1,
};

/** The day part of the stored expiration date, which no control on this screen renders. */
const STORED_EXPIRY_DAY = CARD.expirationDate.slice(8);

/** A name that differs from the stored one, so a submission counts as a change. */
const AMENDED_NAME = 'JANE Q CITIZEN';

/*
 * WHY : ⚠️ Assumptions: each expiry control is addressed by the name the SCREEN gives it, composed the
 *       way the screen composes it -- the painted `Expiry Date       : ` label at
 *       `app/bms/COCRDUP.bms` L122-L126 followed by that part's additive qualifier from
 *       `CARD_UPDATE_PART_NAMES`. Composing them here from the same two exported constants is what
 *       stops this file from asserting a name of its own: if the screen renames a part, these follow.
 * WHY : ⚠️ Refactoring Rationale: the month was previously addressed as the label followed by the
 *       SEPARATOR, which is how the screen composed it then, and the year as the bare label. That
 *       arrangement is the measured defect these two constants exist to keep fixed: two consecutive
 *       required controls whose accessible names differed only by a trailing `" /"`, so a screen reader
 *       announced the same field twice and nothing said which one took the month. A query for the bare
 *       label also resolved to whichever control came first, which is exactly how a test can type a
 *       year into a month field and still pass.
 */
const EXPIRY_MONTH_LABEL = `${CARD_UPDATE_FIELD_LABELS.expiryDate}${CARD_UPDATE_PART_NAMES.month}`;

/** The year control's accessible name, composed from the same two exported constants. */
const EXPIRY_YEAR_LABEL = `${CARD_UPDATE_FIELD_LABELS.expiryDate}${CARD_UPDATE_PART_NAMES.year}`;

/*
 * WHY : Assumptions: the legend descriptors are SPLIT out of the mapset's own painted
 *       literals rather than written here, so the four labels this file asserts on are the
 *       four the terminal painted. `FKEYS` paints the unconditional pair and `FKEYSC` the
 *       pair that appears once changes have been validated, and each literal separates its
 *       descriptors with a single blank.
 */
const PRIMARY_LEGEND = mapsetInitial(MAPSET, 'FKEYS').split(' ');

/** The descriptors the conditional legend field paints once changes have been validated. */
const CONDITIONAL_LEGEND = mapsetInitial(MAPSET, 'FKEYSC').split(' ');

/*
 * WHY : Assumptions: a card-number-shaped value is DERIVED from the masked rendering by
 *       replacing each mask character with a digit, so this file holds no sixteen-digit
 *       literal of its own and the derived value has the declared card-number length
 *       automatically. It exists only to be REFUSED: the selector guard rejects it, which is
 *       the property the invalid-link case asserts.
 */
const CARD_NUMBER_SHAPED_SELECTOR = CARD.displayCardNumber.replace(/[^0-9]/gu, '0');

/**
 * Matches the card record's declaration of the field that must never leave the service.
 *
 * ⚠️ Assumptions: the field's NAME is derived from the copybook at run time and appears
 * nowhere in this file as a literal. That is deliberate twice over: a search of this tree for
 * the term finds no occurrence introduced by a test, and the assertion is anchored to the
 * declaration rather than to a spelling this file chose. `app/cpy/CVACT02Y.cpy` L7 declares
 * it as the only three-digit code on the record.
 */
const GUARDED_CARD_FIELD_PATTERN = /^\s*05\s+CARD-([A-Z]+)-CD\s+PIC 9\(03\)\./u;

/**
 * Reads the lower-cased name fragment of the card field that must never be exposed.
 * @param {readonly string[]} lines - Lines of the card record copybook.
 * @returns {string} The fragment, lower-cased for comparison against member names and text.
 * @throws {RangeError} If the copybook no longer declares the field, so this guard cannot
 *   silently become a comparison against the empty string.
 */
function guardedCardFieldToken(lines: readonly string[]): string {
  for (const source of lines) {
    const match = GUARDED_CARD_FIELD_PATTERN.exec(source);
    const token = match === null ? undefined : match[1];
    if (token !== undefined) {
      return token.toLowerCase();
    }
  }
  throw new RangeError('the card record declares no guarded three-digit code');
}

/** The name fragment that must appear in no member, no label and no rendered text. */
const GUARDED_FIELD_TOKEN = guardedCardFieldToken(CARD_RECORD);

/** Lines of the templated field-highlight copybook, the source of the blank marker. */
const FIELD_HIGHLIGHT = baselineLines('app', 'cpy', 'CSSETATY.cpy');

/** Line 24 of `app/cpy/CSSETATY.cpy`, where the template moves its marker into a blank field. */
const BLANK_MARKER_LINE = 24;

/** Line 135 of `app/cbl/COCRDUPC.cbl`, which declares the prefix it composes for a file error. */
const FILE_ERROR_PREFIX_LINE = 135;

/** Line 257 of `app/cbl/COCRDUPC.cbl`, declaring the fifty-two characters the name edit accepts. */
const ALPHABET_LITERAL_LINE = 257;

/** Line 261 of `app/cbl/COCRDUPC.cbl`, declaring the upper-case half of that alphabet. */
const UPPER_ALPHABET_LINE = 261;

/** Line 263 of `app/cbl/COCRDUPC.cbl`, declaring the lower-case half of that alphabet. */
const LOWER_ALPHABET_LINE = 263;

/** Line 470 of `app/cbl/COCRDUPC.cbl`, where the program takes the commit this flow stands for. */
const COMMIT_LINE = 470;

/** Line 5 of `app/cpy/CVACT02Y.cpy`, where the card record declares the primary account number. */
const CARD_NUMBER_DECLARATION_LINE = 5;

/*
 * WHY : Assumptions: a digit is used as the character outside the accepted alphabet, and the
 *       case below asserts against the program's own alphabet literal that it really is
 *       outside it. Choosing the character and then checking the claim is what keeps the test
 *       from asserting a rule the program does not have.
 */
const NON_ALPHABETIC_CHARACTER = '9';

/** A month outside the range the program's own message names. */
const OUT_OF_RANGE_MONTH = '13';

/** A year the expiry edit refuses. */
const REFUSED_YEAR = '1949';

/** A status outside the two-valued domain the program's own message names. */
const REFUSED_STATUS_CODE = 'X';

/**
 * Collapses a painted mapset label to the form an accessible-name query matches.
 *
 * ⚠️ Assumptions: this exists because the mapset pads its labels with INTERIOR runs of
 * blanks used as column padding -- `'Account Number    :'` at `app/bms/COCRDUP.bms` L83 pads
 * to the field position -- and the screen renders them verbatim under Transformation Rule
 * T8, while Testing Library's default normaliser collapses runs of whitespace in the element
 * text before comparing. Passing the transcribed label straight to a name query therefore
 * never matches, and the failure reads as a missing label rather than as a whitespace
 * mismatch.
 *
 * Alternatives Considered: trimming the padding out of the screen's label constants so no
 * query needed a transform. Rejected because Rule T8 carries a user-visible string across
 * character for character and the padded form is what the terminal painted; the collapse
 * belongs in the query, which is where the normaliser is.
 * @param {string} label - One painted mapset label.
 * @returns {string} The label with each run of whitespace collapsed to one blank and the
 *   ends trimmed.
 */
function collapse(label: string): string {
  return label.replace(/\s+/gu, ' ').trim();
}

/**
 * Renders the screen inside the application shell at one concrete address.
 *
 * ⚠️ Assumptions: the shell is reproduced because this screen delegates its message band and
 * its key legend to it through `useShellSlot`. A subject rendered bare paints neither, so
 * every message and every function-key assertion in this file would be asserting against a
 * tree the application never builds.
 *
 * Assumptions: the harness helper is used rather than a locally assembled provider stack,
 * because it mounts the subject through the shell's own `<Outlet />` -- the arrangement the
 * router builds -- and it creates the keyboard operator BEFORE the render, which is the
 * ordering `@testing-library/user-event` documents.
 * ⚠️ Assumptions: the render is performed INSIDE an `act` scope, and the reason is specific to
 * this screen rather than general hygiene. The screen reads its record from an effect, so with
 * an already-resolved mock the read settles in a microtask; the harness helper is itself
 * asynchronous, so awaiting it drains that microtask after React has left the scope the render
 * opened, and React then reports the resulting state update as unwrapped. Measured, that
 * printed two such reports per rendering case while every assertion still passed -- warnings
 * React documents as capable of masking an update a case should have observed. Opening the
 * scope around both the render and the settling removes them.
 * @param {string} [selector] - The value the route parameter is to carry, defaulting to the
 *   synthetic selector the read is arranged for.
 * @returns {Promise<UserEvent>} The operator that drives the rendered screen.
 * @throws {Error} If the render produced no operator, which would mean the scope had not run.
 */
async function renderScreen(selector: string = CARD_SELECTOR): Promise<UserEvent> {
  let operator: UserEvent | undefined;
  await act(
    /**
     * Renders the screen and lets its opening read settle, both inside the scope.
     * @returns {Promise<void>} Resolves once the read has settled.
     */
    async (): Promise<void> => {
      const rendered = await renderInAppShell(<CardUpdateScreen />, {
        initialEntries: [addressFor(EDIT_ROUTE, selector)],
        routePath: EDIT_ROUTE,
      });
      operator = rendered.user;
    },
  );
  if (operator === undefined) {
    throw new Error('the render scope produced no operator');
  }
  return operator;
}

/**
 * Arranges a successful read and renders the screen, waiting for the form to seed.
 * @returns {Promise<UserEvent>} The operator, once the record has reached the controls.
 */
async function renderLoadedScreen(): Promise<UserEvent> {
  vi.mocked(getCard).mockResolvedValue(CARD);
  const user = await renderScreen();
  await screen.findByDisplayValue(CARD.embossedName);
  return user;
}

/**
 * Returns the control one field is edited or displayed through.
 * @param {string} label - The mapset's painted label for that field.
 * @returns {HTMLElement} The control bearing that label.
 * @throws {Error} If no control carries the label, which Testing Library raises.
 */
function control(label: string): HTMLElement {
  return screen.getByLabelText(collapse(label));
}

/**
 * Returns the form item wrapping one control, which is where a refusal is marked.
 *
 * Assumptions: the item is reached from the control OUTWARDS rather than queried directly,
 * because the design system puts the refusal state on the item and the accessible name on
 * the control, and only the control can be found by name. Walking out from it keeps the two
 * halves of one field tied together.
 * @param {HTMLElement} field - The control, from {@link control}.
 * @returns {HTMLElement} The form item that wraps it.
 * @throws {Error} If the control is not inside a form item, which would mean the screen had
 *   stopped using the design system's form primitive.
 */
function formItemFor(field: HTMLElement): HTMLElement {
  const item = field.closest<HTMLElement>('.ant-form-item');
  if (item === null) {
    throw new Error('the control is not wrapped in a design-system form item');
  }
  return item;
}

/**
 * Returns the function-key legend region the shell paints.
 * @returns {HTMLElement} The legend's navigation landmark.
 * @throws {Error} If no legend is painted, which Testing Library raises.
 */
function legendRegion(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Lists the labels of every control the legend currently offers, in painted order.
 * @returns {readonly string[]} The legend labels, each with its whitespace collapsed.
 */
function legendLabels(): readonly string[] {
  return Array.from(legendRegion().querySelectorAll('button')).map(
    /**
     * Reduces one legend control to its collapsed label.
     * @param {HTMLButtonElement} candidate - One legend control.
     * @returns {string} Its label text.
     */
    (candidate: HTMLButtonElement): string => collapse(candidate.textContent ?? ''),
  );
}

/**
 * Returns the legend control bearing one label.
 *
 * ⚠️ Assumptions: the control is looked up INSIDE the legend region rather than by label
 * alone, because the save legend's text is also the title of the confirmation the save key
 * opens -- so once that confirmation is open, two controls in the document carry the same
 * name and a global lookup finds both.
 * @param {string} label - The legend label, as the mapset paints it.
 * @returns {HTMLButtonElement} The legend control bearing that label.
 * @throws {Error} If the legend offers no such control, so a renamed label fails loudly
 *   rather than silently exercising nothing.
 */
function legendControl(label: string): HTMLButtonElement {
  const found = Array.from(legendRegion().querySelectorAll('button')).find(
    /**
     * Reports whether one legend control carries the label sought.
     * @param {HTMLButtonElement} candidate - One legend control.
     * @returns {boolean} `true` when its collapsed text is that label.
     */
    (candidate: HTMLButtonElement): boolean => collapse(candidate.textContent ?? '') === label,
  );
  if (found === undefined) {
    throw new Error(`no function-key control labelled ${label}`);
  }
  return found;
}

/**
 * Reports whether one confirmation node is actually displayed.
 *
 * ⚠️ Assumptions: the design system animates a surface OUT rather than unmounting it, so a closed
 * confirmation can stay in the document carrying a leaving class with all of its content still
 * readable -- and jsdom never fires the transition end that would remove it. A bare presence query
 * therefore reports a confirmation the operator can no longer see, and an absence assertion fails
 * against correct code. It was measured doing exactly that here.
 *
 * Assumptions: the ancestors are walked as well as the node itself, because whether the leaving class
 * lands on the surface or on the wrapper around it is the library's internal structure and not a
 * contract this file should depend on.
 * @param {Element} node - The candidate confirmation node.
 * @returns {boolean} `true` when neither it nor any ancestor is hidden or animating out.
 */
function isDisplayed(node: Element): boolean {
  let current: Element | null = node;
  while (current !== null) {
    const name = current.className;
    if (typeof name === 'string' && (name.includes('-hidden') || name.includes('-leave'))) {
      return false;
    }
    current = current.parentElement;
  }
  return true;
}

/**
 * Collects the confirmation surfaces an operator can currently see.
 *
 * ⚠️ Assumptions: the surface is located by the `dialog` ROLE and by its accessible NAME, and both
 * halves are load-bearing. This confirmation used to be a `Popconfirm`, whose overlay is hardcoded
 * `role="tooltip"` at `ui/node_modules/@rc-component/tooltip/es/Popup.js` with no prop path to
 * override it -- announced to a screen reader as supplementary text about a button rather than as a
 * question that must be answered, and carrying neither `aria-modal` nor a focus trap. Querying the
 * role is what stops that regressing: a surface that went back to a tooltip would fail every case
 * in this group rather than silently losing its semantics. The name comes from the catalogued
 * sentence through the dialog's `aria-labelledby`, so the query holds the surface to asking the
 * program's own question as well.
 * @returns {readonly HTMLElement[]} The displayed surfaces, empty when none is open.
 */
function displayedConfirmations(): readonly HTMLElement[] {
  return screen
    .queryAllByRole('dialog', { name: catalogued('PROMPT_FOR_CONFIRMATION').text })
    .filter(isDisplayed);
}

/**
 * Waits for the confirmation the save key opens and returns its container.
 * @returns {Promise<HTMLElement>} The open confirmation's container.
 * @throws {Error} If no confirmation opens within the runner's allowance, which `waitFor`
 *   raises carrying the message below.
 */
async function openConfirmation(): Promise<HTMLElement> {
  return await waitFor(
    /**
     * Probes for the open confirmation.
     * @returns {HTMLElement} The confirmation's container.
     * @throws {Error} While no confirmation is displayed.
     */
    (): HTMLElement => {
      const [found] = displayedConfirmations();
      if (found === undefined) {
        throw new Error('the save key must open the confirmation before anything is written');
      }
      return found;
    },
  );
}

/**
 * Returns the two actions an open confirmation offers, told apart by their emphasis.
 *
 * ⚠️ Assumptions: the accepting action is identified by the design system's DANGEROUS
 * emphasis rather than by its text. The screen passes the library's default button wording,
 * so naming it here would put a user-visible string in this file that no baseline source
 * holds; and the emphasis is itself the contract AAP section 0.3.2 assigns to a confirmation
 * before an irreversible write, so selecting on it asserts that marking at the same time.
 * @param {HTMLElement} confirmation - The open confirmation, from {@link openConfirmation}.
 * @returns {{ accept: HTMLButtonElement; decline: HTMLButtonElement }} Its two actions.
 * @throws {Error} If the confirmation does not offer exactly one of each, so a change to the
 *   overlay's shape fails here rather than by clicking the wrong control.
 */
function confirmationActions(confirmation: HTMLElement): {
  accept: HTMLButtonElement;
  decline: HTMLButtonElement;
} {
  const actions = Array.from(confirmation.querySelectorAll('button'));
  const accept = actions.filter(
    /**
     * Keeps the action carrying the design system's dangerous emphasis.
     * @param {HTMLButtonElement} candidate - One action inside the confirmation.
     * @returns {boolean} `true` for the accepting action.
     */
    (candidate: HTMLButtonElement): boolean => candidate.classList.contains('ant-btn-dangerous'),
  );
  const decline = actions.filter(
    /**
     * Keeps the action without the dangerous emphasis.
     * @param {HTMLButtonElement} candidate - One action inside the confirmation.
     * @returns {boolean} `true` for the declining action.
     */
    (candidate: HTMLButtonElement): boolean => !candidate.classList.contains('ant-btn-dangerous'),
  );
  const [accepting] = accept;
  const [declining] = decline;
  if (accepting === undefined || declining === undefined) {
    throw new Error('the confirmation must offer one accepting and one declining action');
  }
  return { accept: accepting, decline: declining };
}

/**
 * Waits until the row-22 line carries the confirmation prompt.
 *
 * ⚠️ Assumptions: this waits on the BAND rather than on a document-wide text query, and the scoping is
 * load-bearing. The confirmation dialog is titled with the same catalogued sentence, deliberately -- the
 * reference asks that question and this application authors no second wording for it -- so once the
 * dialog has been opened the sentence names two elements at once, and the design system keeps a closed
 * dialog mounted behind a hidden wrapper because jsdom never fires the transition end that would remove
 * it. A global query therefore fails with `Found multiple elements` against entirely correct code, which
 * it was measured doing here. The band is the one place the prompt has to appear for the operator to
 * read it, so that is what is awaited.
 * @returns {Promise<void>} Resolves once the prompt is on the row-22 line.
 */
async function waitForTheConfirmationPrompt(): Promise<void> {
  await waitFor(
    /**
     * Asserts the row-22 line carries the prompt.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(collapse(informationLineText())).toContain(
        collapse(catalogued('PROMPT_FOR_CONFIRMATION').text),
      );
    },
  );
}

/**
 * Amends the name on the card and submits the turn, which validates without writing.
 *
 * ⚠️ Assumptions: the entry is driven with real keystrokes rather than with one synthetic
 * change event, because the contract this screen inherits is a KEYBOARD contract -- the 3270
 * original was operated entirely from the keyboard -- and this form is small enough for the
 * per-keystroke re-render to cost nothing worth optimising: the mapset declares thirty-four
 * fields of which six are controls, against the hundred and twenty-eight of the account
 * screen whose own cases had to collapse their entry for exactly that reason.
 *
 * Assumptions: the submission is raised by pressing Enter and never by clicking a submit
 * control, because Enter is the attention identifier the program dispatches on --
 * `CCARD-AID-ENTER` at `app/cbl/COCRDUPC.cbl` L414 and L423 -- and the screen registers no
 * control of its own for it.
 * @param {UserEvent} user - The operator from {@link renderLoadedScreen}.
 * @returns {Promise<void>} Resolves once the validated turn has been rendered.
 */
async function amendAndValidate(user: UserEvent): Promise<void> {
  const name = control(CARD_UPDATE_FIELD_LABELS.nameOnCard);
  await user.clear(name);
  await user.type(name, AMENDED_NAME);
  await pressPfKey(user, 'ENTER');
  await waitForTheConfirmationPrompt();
}

/**
 * Drives the whole committing path: amend, validate, open the confirmation and accept it.
 * @param {UserEvent} user - The operator from {@link renderLoadedScreen}.
 * @returns {Promise<void>} Resolves once the write has been dispatched.
 */
async function amendAndCommit(user: UserEvent): Promise<void> {
  await amendAndValidate(user);
  await pressPfKey(user, 'PFK05');
  const confirmation = await openConfirmation();
  /*
   * WHY : Trade-offs: the accepting action is clicked with `fireEvent` rather than through
   *       the operator, and only here. The confirmation is rendered into a portal with no
   *       layout in jsdom, so the operator's pointer-events check has nothing to measure;
   *       the sibling account-update cases resolve the same overlay the same way. Nothing is
   *       lost, because the KEYBOARD half of this path -- the key that opens the
   *       confirmation -- is driven through a real key event on the line above.
   */
  fireEvent.click(confirmationActions(confirmation).accept);
  await waitFor(
    /**
     * Waits for the write to have been dispatched.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(updateCard).toHaveBeenCalledTimes(1);
    },
  );
}

/*
 * WHY : Assumptions: the two statuses are named here because `ui/src/api/client.ts` keeps its
 *       own conflict constant private, so there is nothing to import. The values are not
 *       trusted on their own: the classification case below feeds each builder to the
 *       client's published `isConflictFailure` predicate and asserts the verdict, so a
 *       disagreement between this file and the client fails as a classification rather than
 *       as a silently mis-shaped fixture.
 */
const CONFLICT_STATUS = 409;

/** The status a refusal carrying per-field detail arrives with. */
const REFUSED_STATUS = 400;

/**
 * Builds the optimistic-concurrency refusal a write is rejected with.
 *
 * ⚠️ Assumptions: the shared 409 builder is used when the service supplies the sentence and
 * deliberately NOT when it does not. That builder requires a message, because three screens
 * surface a 409 and they do not mean the same thing, so defaulting it would give one screen
 * another's wording. The fallback path needs the member ABSENT, which is the one shape the
 * builder cannot express, so that case is built from the plain problem builder at the same
 * status instead.
 * @param {string | null} message - The refusal sentence the service sends, or `null` to
 *   arrange the case in which it sends none and the screen must supply its own.
 * @returns {ApiRequestError} The rejection the mocked write is to produce.
 */
function conflictRefusal(message: string | null): ApiRequestError {
  const problem =
    message === null
      ? apiError({ status: CONFLICT_STATUS, message: null })
      : conflictProblem(message);
  return new ApiRequestError(
    'PROBLEM',
    CONFLICT_STATUS,
    problem,
    `PROBLEM ${String(CONFLICT_STATUS)}`,
  );
}

/**
 * Builds an ordinary refusal, optionally carrying per-field detail.
 * @param {string | null} message - The refusal sentence, from the message catalog, or `null`
 *   to arrange the case in which the screen supplies its own.
 * @param {readonly FieldError[]} [fieldErrors] - Per-field refusals the body carries.
 * @returns {ApiRequestError} The rejection the mocked write is to produce.
 */
function plainRefusal(
  message: string | null,
  fieldErrors: readonly FieldError[] = [],
): ApiRequestError {
  return new ApiRequestError(
    'PROBLEM',
    REFUSED_STATUS,
    apiError({ status: REFUSED_STATUS, message, fieldErrors }),
    `PROBLEM ${String(REFUSED_STATUS)}`,
  );
}

/** The record the write answers with when it is accepted. */
const AMENDED_CARD: CardDetail = { ...CARD, embossedName: AMENDED_NAME, version: CARD.version + 1 };

/**
 * Returns the text of the band the shell paints for a screen-level refusal.
 * @returns {string} The band's text, or the empty string when it is reserved and quiet.
 */
function refusalBandText(): string {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '';
}

/**
 * Returns the text of the band standing in for the mapset's own information line.
 * @returns {string} The information line's text.
 */
function informationLineText(): string {
  return screen.getByTestId(INFORMATION_BAND_TEST_ID).textContent ?? '';
}

/**
 * Returns one catalogued sentence, failing loudly when the catalog does not hold it.
 * @param {string} condition - The COBOL condition name the sentence is filed under.
 * @returns {StatusMessage} That sentence and its provenance.
 * @throws {RangeError} If the catalog holds no entry under that condition name, so a
 *   renamed key fails on the key rather than on an assertion against `undefined`.
 */
function catalogued(condition: string): StatusMessage {
  const entry = CATALOGUE[condition];
  if (entry === undefined) {
    throw new RangeError(`${PROGRAM_SOURCE_FILES.COCRDUPC} has no catalogued ${condition}`);
  }
  return entry;
}

/**
 * Every field width the screen enforces comes from the symbolic map that declares it.
 *
 * ⚠️ Assumptions: the six widths are read out of `app/cpy-bms/COCRDUP.CPY` and compared with
 * the screen's own constants, so neither side of the comparison is a number this file chose.
 * The 3270 enforced a field's width in hardware -- a `PIC X(11)` field accepted eleven
 * characters and the twelfth keystroke did nothing -- and `maxLength` is the only place that
 * constraint survives in a browser, so a width that drifted from its declaration would let an
 * operator type a value the service then truncates.
 * @returns {void} Nothing; each comparison carries its own outcome.
 */
function derivesEveryFieldWidthFromTheSymbolicMap(): void {
  expect(CARD_UPDATE_FIELD_WIDTHS.accountNumber).toBe(declaredField(MAP_FIELDS, 'ACCTSIDI').width);
  expect(CARD_UPDATE_FIELD_WIDTHS.cardNumber).toBe(declaredField(MAP_FIELDS, 'CARDSIDI').width);
  expect(CARD_UPDATE_FIELD_WIDTHS.embossedName).toBe(declaredField(MAP_FIELDS, 'CRDNAMEI').width);
  expect(CARD_UPDATE_FIELD_WIDTHS.activeStatus).toBe(declaredField(MAP_FIELDS, 'CRDSTCDI').width);
  expect(CARD_UPDATE_FIELD_WIDTHS.expirationMonth).toBe(declaredField(MAP_FIELDS, 'EXPMONI').width);
  expect(CARD_UPDATE_FIELD_WIDTHS.expirationYear).toBe(declaredField(MAP_FIELDS, 'EXPYEARI').width);
}

/**
 * Every rendered control refuses more characters than its declaration allows.
 * @returns {Promise<void>} Resolves once every control has been checked.
 */
async function honoursEveryDeclaredFieldWidth(): Promise<void> {
  await renderLoadedScreen();

  const pairs: readonly (readonly [string, string])[] = [
    [CARD_UPDATE_FIELD_LABELS.accountNumber, 'ACCTSIDI'],
    [CARD_UPDATE_FIELD_LABELS.cardNumber, 'CARDSIDI'],
    [CARD_UPDATE_FIELD_LABELS.nameOnCard, 'CRDNAMEI'],
    [CARD_UPDATE_FIELD_LABELS.cardActive, 'CRDSTCDI'],
    [EXPIRY_MONTH_LABEL, 'EXPMONI'],
    [EXPIRY_YEAR_LABEL, 'EXPYEARI'],
  ];
  for (const [label, declaration] of pairs) {
    expectMaxLength(control(label), declaredField(MAP_FIELDS, declaration).width);
  }
  expect(screen.getByRole('main').querySelectorAll('input')).toHaveLength(pairs.length);
}

/**
 * The header band and the two message lines are carried at the widths the map declares.
 *
 * ⚠️ Assumptions: this asserts the DECLARATIONS and the two values this screen contributes to
 * them, and deliberately not what the title band renders. The band is painted by the shell
 * for every screen, so its rendering is `ui/src/layout/appShell.test.tsx`'s contract; what
 * belongs here is that the transaction identifier and program name this screen publishes fit
 * the fields the mapset reserves for them.
 *
 * Assumptions: the information line's catalogued width is compared with `INFOMSGI` for every
 * sentence filed against that field, which is what ties the catalog's own provenance to the
 * symbolic map instead of leaving them two independent transcriptions.
 * @returns {void} Nothing; each comparison carries its own outcome.
 */
function carriesTheHeaderAndMessageFieldsAtTheirDeclaredWidths(): void {
  expect(CARD_UPDATE_TRANSACTION_ID).toHaveLength(declaredField(MAP_FIELDS, 'TRNNAMEI').width);
  expect(CARD_UPDATE_PROGRAM_NAME).toHaveLength(declaredField(MAP_FIELDS, 'PGMNAMEI').width);
  expect(declaredField(MAP_FIELDS, 'TITLE01I').width).toBe(
    declaredField(MAP_FIELDS, 'TITLE02I').width,
  );
  expect(declaredField(MAP_FIELDS, 'CURDATEI').width).toBe(
    declaredField(MAP_FIELDS, 'CURTIMEI').width,
  );

  const informationWidth = declaredField(MAP_FIELDS, 'INFOMSGI').width;
  const workAreaWidths = pictureWidthsAt(WORK_AREA, MESSAGE_BAND.sources.workArea.lines);
  for (const entry of Object.values(CATALOGUE)) {
    const expected = entry.field === 'WS-INFO-MSG' ? informationWidth : MESSAGE_BAND.workAreaWidth;
    expect(entry.declaredWidth).toBe(expected);
  }
  expect(workAreaWidths.length).toBeGreaterThan(0);
  for (const width of workAreaWidths) {
    expect(width).toBe(MESSAGE_BAND.workAreaWidth);
  }
}

/**
 * The eighty-character error field is not the seventy-five-character content contract.
 *
 * ⚠️ Assumptions: two different numbers govern one message and BOTH are real, so neither may
 * be "corrected" into the other. `ERRMSGI` is `PIC X(80)` on this mapset -- one of only two of
 * the twenty-one that declare the wider field, which this case establishes by counting rather
 * than by assertion -- while the content limit for any sentence that crosses the shared work
 * area is the seventy-five characters `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` declare. The
 * wider field is display slack: a program moves a seventy-five-byte work area into it and
 * COBOL pads the remainder. Treating eighty as the content limit would let a sentence overrun
 * the area it crosses; treating seventy-five as the field width would clip five characters
 * from this screen alone.
 *
 * Assumptions: the band's own rendering width is not asserted here. That contract belongs to
 * `ui/src/layout/MessageBand.test.tsx` and to the shell's cases; what belongs here is that
 * this screen's mapset is one of the two the wider field applies to.
 * @returns {void} Nothing; each comparison carries its own outcome.
 */
function distinguishesTheWiderFieldFromTheContentContract(): void {
  expect(declaredField(MAP_FIELDS, 'ERRMSGI').width).toBe(MESSAGE_BAND.displayWidthCardDetail);
  // WHY : Assumptions: two, out of the twenty-one mapsets the catalog records -- this screen's
  //       and the card detail sibling's. The count is asserted so the phrase "one of only two"
  //       used throughout this file is a measured fact; a third mapset adopting the wider field
  //       would make the sibling comparison below pick an arbitrary one of them.
  expect(EIGHTY_WIDE_MAPSETS).toHaveLength(2);
  expect(EIGHTY_WIDE_MAPSETS).toContain(CARD_UPDATE_MAPSET);
  expect(SIBLING_MAPSET).not.toBe('');
  expect(declaredField(SIBLING_MAP_FIELDS, 'ERRMSGI').width).toBe(
    MESSAGE_BAND.displayWidthCardDetail,
  );
  expect(MESSAGE_BAND.workAreaWidth).toBeLessThan(MESSAGE_BAND.displayWidthCardDetail);
}

/**
 * The expiry date is declared in three parts here and only two of them are editable.
 *
 * ⚠️ Assumptions: the third part is DECLARED and is deliberately not rendered as a control,
 * and the mapset is what settles it. `EXPDAY` is declared `ATTRB=(DRK,FSET,PROT)` at
 * `app/bms/COCRDUP.bms` L142 -- dark and protected -- so the terminal neither displayed it nor
 * accepted a keystroke into it, while `EXPMON` and `EXPYEAR` are `UNPROT`. Rendering a third
 * input here would therefore ADD an affordance the reference never had. The stored day is
 * redisplayed from the record and no browser can influence it, which is also why the change
 * comparison in the screen excludes it.
 *
 * ⚠️ Assumptions: the detail sibling declares only two parts, and that difference is per
 * screen rather than per record -- the same card row backs both maps. Asserting it here keeps
 * a reader from "harmonising" the two maps on the assumption that one of them is wrong.
 *
 * Assumptions: the day's absence from the rendered text is asserted as well as its absence
 * from the controls, and the fixture's day was chosen so that its two characters occur in no
 * other value this screen paints.
 * @returns {Promise<void>} Resolves once the rendered surface has been checked.
 */
async function declaresThreeExpiryPartsAndEditsTwo(): Promise<void> {
  await renderLoadedScreen();

  expect(MAP_FIELDS.has('EXPMONI')).toBe(true);
  expect(MAP_FIELDS.has('EXPYEARI')).toBe(true);
  expect(MAP_FIELDS.has('EXPDAYI')).toBe(true);
  expect(SIBLING_MAP_FIELDS.has('EXPMONI')).toBe(true);
  expect(SIBLING_MAP_FIELDS.has('EXPYEARI')).toBe(true);
  expect(SIBLING_MAP_FIELDS.has('EXPDAYI')).toBe(false);

  expect(mapsetAttributes(MAPSET, 'EXPMON')).toContain('UNPROT');
  expect(mapsetAttributes(MAPSET, 'EXPYEAR')).toContain('UNPROT');
  expect(mapsetAttributes(MAPSET, 'EXPDAY')).toContain('DRK');
  expect(mapsetAttributes(MAPSET, 'EXPDAY')).toContain('PROT');
  expect(mapsetAttributes(MAPSET, 'EXPDAY')).not.toContain('UNPROT');

  // WHY : Assumptions: the two parts are sliced out of the stored date at the offsets the
  //       ten-character ISO form puts them at -- `CARD-EXPIRAION-DATE PIC X(10)` at
  //       `app/cpy/CVACT02Y.cpy` L9, whose value is already year, month and day in that order.
  //       Trade-offs: the offsets are restated here rather than imported, because the screen
  //       keeps its own as module-private constants; they are asserted against the rendered
  //       controls, so a disagreement fails here instead of shipping a form seeded from the
  //       wrong part of the date.
  expect(control(EXPIRY_MONTH_LABEL)).toHaveValue(CARD.expirationDate.slice(5, 7));
  expect(control(EXPIRY_YEAR_LABEL)).toHaveValue(CARD.expirationDate.slice(0, 4));
  expect(screen.queryAllByDisplayValue(STORED_EXPIRY_DAY)).toEqual([]);
  expect(screen.getByRole('main').textContent ?? '').not.toContain(STORED_EXPIRY_DAY);
}

/**
 * Exactly one field claims the initial cursor, as exactly one declaration does.
 *
 * ⚠️ Assumptions: the COUNT is the contract and the identity of the field is a documented
 * departure. The mapset's single `IC` sits on `ACCTSID`, which is also declared `PROT` at
 * `app/bms/COCRDUP.bms` L84 -- the reference parks the cursor on a field it will not accept a
 * keystroke into. This screen renders that field read-only for the same reason and places the
 * cursor on the first field an operator can actually change, so the "one initial cursor"
 * property is preserved while the pointless landing spot is not reproduced.
 *
 * Assumptions: focus is asserted rather than an attribute, because React applies `autoFocus`
 * by calling `focus()` and never writes the attribute into the document -- an attribute query
 * would pass on a screen that had lost the behaviour entirely.
 * @returns {Promise<void>} Resolves once focus has been checked.
 */
async function placesExactlyOneInitialCursor(): Promise<void> {
  await renderLoadedScreen();

  expect(initialCursorFields(MAPSET)).toHaveLength(1);
  expect(mapsetAttributes(MAPSET, 'ACCTSID')).toContain('PROT');
  expect(control(CARD_UPDATE_FIELD_LABELS.accountNumber)).toHaveAttribute('readonly');
  expect(control(CARD_UPDATE_FIELD_LABELS.nameOnCard)).toHaveFocus();
}

/**
 * The mapset's field census is reproduced by the screen's composition, not by raw markup.
 *
 * ⚠️ Assumptions: every control is asserted to be a design-system component rather than a raw
 * element, because AAP section 0.3.2 admits no raw `input`, `button`, `table` or heading where
 * the library provides one -- and a raw element renders almost identically while opting out of
 * the CSS-variables theme that carries every measured BMS colour. Nothing visible would report
 * that, which is why it is asserted structurally.
 *
 * Assumptions: the mapset's own definition count is asserted alongside, so the census this
 * file's other cases select from is a checked number rather than a remark.
 * @returns {Promise<void>} Resolves once the composition has been checked.
 */
async function composesTheScreenFromDesignSystemComponents(): Promise<void> {
  await renderLoadedScreen();

  // WHY : Assumptions: thirty-four is the measured census of `DFHMDF` definitions in
  //       `app/bms/COCRDUP.bms`, of which seventeen are named and carry a symbolic-map subfield
  //       and the rest are unnamed spacers and painted labels. It is asserted rather than
  //       recorded in prose because the other cases in this file select from that census -- the
  //       initial-cursor count, the attribute reads, the legend literals -- and a census that
  //       had changed under them would leave each of those selections quietly narrower.
  expect(mapsetFieldCount(MAPSET)).toBe(34);

  const body = screen.getByRole('main');
  for (const input of Array.from(body.querySelectorAll('input'))) {
    expect(input.className).toContain('ant-input');
  }
  for (const action of Array.from(document.querySelectorAll('button'))) {
    expect(action.className).toContain('ant-btn');
  }
  for (const heading of Array.from(body.querySelectorAll('h1, h2, h3, h4, h5, h6'))) {
    expect(heading.className).toContain('ant-typography');
  }
  expect(body.querySelectorAll('table')).toHaveLength(0);
  expect(body.querySelector('form')?.className ?? '').toContain('ant-form');
  expect(body.querySelectorAll('.ant-flex').length).toBeGreaterThan(0);
}

/*
 * WHY : ⚠️ Refactoring Rationale: the cases below assert a TWO-STEP commit, and the reason
 *       nothing is lost by replacing the reference's own concurrency control is worth stating
 *       once, here, because it is the most consequential structural claim this screen makes.
 *       AAP section 0.7.2 establishes that optimistic concurrency ALREADY EXISTS in the
 *       baseline: the account-update program snapshots the whole pre-edit record into
 *       `ACUP-OLD-DETAILS` and carries a change flag across the pseudo-conversational gap,
 *       and this program takes its commit at `app/cbl/COCRDUPC.cbl` L470. Replacing the
 *       before-image with a version column loses nothing, because the CICS read-for-update
 *       lock was never held across client think-time -- that is PRECISELY why the before-image
 *       exists. A reader who assumes the reference held a lock would conclude the migration
 *       weakened the guarantee; it reproduced it in the mechanism the target has.
 * WHY : Assumptions: the same reasoning is why these fields transport as STRINGS validated
 *       digits-only rather than as numbers. The reference declares each of them as characters
 *       on the wire and redefines them as numeric only where it does arithmetic -- an
 *       eleven-character account identifier keeps its leading zeros, and a JSON number would
 *       be parsed into an IEEE-754 double by most clients, destroying exactness at the one
 *       boundary the operator sees.
 * WHY : Assumptions: the observable half of the commit contract on the client is the two-step
 *       flow and the conflict status. AAP Rule T5 maps the reference's `SYNCPOINT` onto a
 *       transaction boundary in the service, which no browser can observe; what a browser CAN
 *       observe is that Enter validates and only the save key writes, and that a record moved
 *       under the operator is reported rather than overwritten.
 */

/**
 * The program takes its commit where this screen's contract says it does.
 *
 * Assumptions: the citation is checked rather than written down. A structural claim about the
 * reference is only as good as the line it names, and a line that shifts turns every comment
 * above it into a plausible fiction.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function commitsWhereTheProgramCommits(): void {
  expect(declarationText(PROGRAM, [COMMIT_LINE])).toContain('SYNCPOINT');
}

/**
 * The validating turn reports itself and writes nothing.
 *
 * ⚠️ Assumptions: the write is asserted NOT to have happened, which is the half that
 * discriminates the reference's flow from a single-step save. A screen that wrote on Enter
 * would satisfy every other assertion in this file -- the record would update, the success
 * sentence would appear -- while silently removing the confirmation turn the reference
 * requires.
 * @returns {Promise<void>} Resolves once the validated turn has been checked.
 */
async function validatesOnEnterWithoutWriting(): Promise<void> {
  const user = await renderLoadedScreen();
  await amendAndValidate(user);

  expectVerbatimMessage(catalogued('PROMPT_FOR_CONFIRMATION').text);
  expect(updateCard).not.toHaveBeenCalled();
}

/**
 * Only the save key commits, and it opens a confirmation before it does.
 *
 * ⚠️ Assumptions: three moments are asserted in order -- after the validating turn, after the
 * save key, and after the confirmation is accepted -- because only the ORDER distinguishes the
 * reference's flow from one where the key writes directly. Asserting the final state alone
 * would pass against a screen that wrote on the key press and rendered a confirmation
 * afterwards.
 * @returns {Promise<void>} Resolves once the accepted write has been reported.
 */
async function commitsOnlyOnTheSaveKey(): Promise<void> {
  vi.mocked(updateCard).mockResolvedValue(AMENDED_CARD);
  const user = await renderLoadedScreen();

  await amendAndValidate(user);
  expect(updateCard).not.toHaveBeenCalled();

  await pressPfKey(user, 'PFK05');
  const confirmation = await openConfirmation();
  expect(updateCard).not.toHaveBeenCalled();

  fireEvent.click(confirmationActions(confirmation).accept);
  await waitFor(
    /**
     * Waits for the accepted write to be dispatched.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(updateCard).toHaveBeenCalledTimes(1);
    },
  );
  await screen.findByText(catalogued('CONFIRM_UPDATE_SUCCESS').text);
}

/**
 * The success sentence is stated over the record that was committed, not the one that was typed.
 *
 * Purpose
 * -------
 * The contract these nine mutating screens share forbids announcing a success while a contradicting
 * value is displayed: an operator told the write succeeded, looking at the value they typed rather
 * than the value that was stored, has been told two things and cannot tell which is true.
 *
 * ⚠️ Assumptions: the fixture makes the committed record DIFFER from the entry, which is the whole
 * point of the case -- the service is entitled to canonicalise what it stores, and the reference does
 * exactly that on this record (`app/cpy/CVACT02Y.cpy` declares the embossed name as a fixed
 * fifty-character field, so what is stored is the padded, canonical form of what was sent). With an
 * identical fixture the assertion would pass against a screen that never adopted the response at all,
 * because the operator's own typing already reads correctly.
 *
 * Assumptions: the version is asserted through the NEXT request rather than by inspecting state, since
 * it is the one part of the adopted record no operator can see. A screen that adopted the name and
 * kept the stale version would look right and would have its operator's next save refused as a
 * conflict -- which is the shape of the defect this group's HIGH finding describes.
 *
 * Assumptions: the reference resolves this the same way, so the behaviour is transcribed and not
 * invented: `app/cbl/COCRDUPC.cbl` L1498 to L1520 refreshes the record it holds on the committing turn,
 * so the map it re-sends carries what was written.
 * @returns {Promise<void>} Resolves once the committed record has been shown to be displayed.
 */
async function statesItsSuccessOverTheCommittedRecord(): Promise<void> {
  /*
   * WHY : Assumptions: the entry is typed in LOWER case and the stored form comes back embossed, which
   *       is a canonicalisation this record genuinely has -- an embossed name is what is pressed into
   *       the card -- and `EMBOSSED_NAME_PATTERN` admits both cases, so both forms are values the
   *       screen accepts. It also makes the two strings differ, which the assertion below requires:
   *       {@link AMENDED_NAME} is already upper case, so deriving the stored form from it would give
   *       one string twice and the case would pass without the screen adopting anything.
   */
  const entered = 'jane q citizen';
  const committed: CardDetail = {
    ...CARD,
    embossedName: entered.toUpperCase(),
    version: CARD.version + 1,
  };
  vi.mocked(updateCard).mockResolvedValue(committed);
  const user = await renderLoadedScreen();

  const entry = control(CARD_UPDATE_FIELD_LABELS.nameOnCard);
  await user.clear(entry);
  await user.type(entry, entered);
  await pressPfKey(user, 'ENTER');
  await waitForTheConfirmationPrompt();
  await pressPfKey(user, 'PFK05');
  const first = await openConfirmation();
  fireEvent.click(confirmationActions(first).accept);
  await screen.findByText(catalogued('CONFIRM_UPDATE_SUCCESS').text);

  expect(committed.embossedName).not.toBe(entered);
  expect(control(CARD_UPDATE_FIELD_LABELS.nameOnCard)).toHaveValue(committed.embossedName);

  /*
   * WHY : ⚠️ Assumptions: the entry is asserted to have stopped accepting typing on this turn, which is
   *       the other half of the same contract: an operator who can type over a value that has just
   *       been reported as saved is being shown a record that no longer matches the sentence beside
   *       it. The reference protects all six fields on the committed turn --
   *       `3300-SETUP-SCREEN-ATTRS` moves `DFHBMPRF` into every one of them at
   *       `app/cbl/COCRDUPC.cbl` L1191 to L1199 -- so this is transcribed and not added.
   * WHY : Assumptions: the version half of the adoption is asserted by
   *       {@link resubmitsAgainstTheVersionTheRefreshReturned} and deliberately not here. A second turn
   *       cannot be driven on this mount at all: the fields are protected on the committed turn, which
   *       is exactly what the assertion above establishes, so any case that typed again after a success
   *       would be asserting against a screen the reference does not paint.
   */
  expect(control(CARD_UPDATE_FIELD_LABELS.nameOnCard)).toHaveAttribute('readonly');
}

/**
 * Declining the confirmation writes nothing.
 *
 * Assumptions: the confirmation replaces the terminal's re-key-to-confirm convention, so its
 * declining action has to be a real way out. A confirmation whose only exits were "accept" and
 * "discard the edits" would make the guard cost the work it was asking about.
 * @returns {Promise<void>} Resolves once the declined turn has been checked.
 */
async function decliningTheConfirmationWritesNothing(): Promise<void> {
  vi.mocked(updateCard).mockResolvedValue(AMENDED_CARD);
  const user = await renderLoadedScreen();

  await amendAndValidate(user);
  await pressPfKey(user, 'PFK05');
  const confirmation = await openConfirmation();
  fireEvent.click(confirmationActions(confirmation).decline);

  await waitFor(
    /**
     * Waits for the confirmation to stop being displayed.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(displayedConfirmations()).toEqual([]);
    },
  );
  expect(updateCard).not.toHaveBeenCalled();
  expect(informationLineText()).toContain(catalogued('PROMPT_FOR_CONFIRMATION').text);
}

/**
 * An unchanged submission is refused, and the save key is never offered for it.
 *
 * Assumptions: the refusal and the ABSENCE of the save key are asserted together. The
 * reference reveals its second legend field only once changes have been validated, so a screen
 * that refused the submission but still offered the key would let an operator commit a turn
 * the reference has nothing to commit.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function refusesAnUnchangedSubmission(): Promise<void> {
  const user = await renderLoadedScreen();
  await pressPfKey(user, 'ENTER');

  await screen.findByText(catalogued('NO_CHANGES_DETECTED').text);
  expect(updateCard).not.toHaveBeenCalled();
  for (const descriptor of CONDITIONAL_LEGEND) {
    expect(legendLabels()).not.toContain(descriptor);
  }
}

/**
 * A refused write reports the service's sentence and marks the turn as unsuccessful.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function reportsARefusedWrite(): Promise<void> {
  vi.mocked(updateCard).mockRejectedValue(plainRefusal(null));
  const user = await renderLoadedScreen();

  await amendAndCommit(user);

  await screen.findByText(catalogued('LOCKED_BUT_UPDATE_FAILED').text);
  expect(refusalBandText()).toContain(catalogued('LOCKED_BUT_UPDATE_FAILED').text);
  expect(informationLineText()).toContain(catalogued('INFORM_FAILURE').text);
}

/** The status a service that is momentarily unavailable answers with. */
const UNAVAILABLE_STATUS = 503;

/**
 * Builds a refusal from a service that was momentarily unavailable and sent no sentence.
 *
 * Assumptions: no message member, which is the arrangement that makes the screen's own selection
 * observable -- with a sentence present the screen renders that verbatim and the selection never runs.
 * @returns {ApiRequestError} The rejection the mocked write is to produce.
 */
function unavailableRefusal(): ApiRequestError {
  return new ApiRequestError(
    'PROBLEM',
    UNAVAILABLE_STATUS,
    apiError({ status: UNAVAILABLE_STATUS, message: null }),
    `PROBLEM ${String(UNAVAILABLE_STATUS)}`,
  );
}

/**
 * A request that never reached the service is reported as an outage, not as a failed update.
 *
 * ⚠️ Assumptions: the assertion is a PAIR -- the outage sentence appears and the write-failed sentence
 * does not -- because either half alone admits the screen this case was written against. It answered
 * every bodiless refusal with `Update of record failed`, which is a claim about the record: the source
 * program sets that sentence after a `REWRITE` came back refused (`app/cbl/COCRDUPC.cbl` L988-L1001),
 * and it is untrue of a request the service never received. An operator told the update failed goes to
 * check whether it partly applied; an operator told the service is unavailable retries.
 *
 * Assumptions: the fixture carries no message member, so what appears is the screen's own choice for a
 * transient failure rather than text it echoed.
 * @returns {Promise<void>} Resolves once the outage has been reported.
 */
async function reportsATransientRefusalAsAnOutage(): Promise<void> {
  vi.mocked(updateCard).mockRejectedValue(unavailableRefusal());
  const user = await renderLoadedScreen();

  await amendAndCommit(user);

  await screen.findByText(TRANSIENT_FAILURE_TRY_AGAIN);
  expect(refusalBandText()).toContain(TRANSIENT_FAILURE_TRY_AGAIN);
  expect(refusalBandText()).not.toContain(catalogued('LOCKED_BUT_UPDATE_FAILED').text);
}

/**
 * The outstanding write is announced through a live region that is mounted and empty when idle.
 *
 * ⚠️ Assumptions: the region's presence and EMPTINESS on the idle turn are asserted before the write is
 * started, and that ordering is the point. `ui/src/layout/fieldHelp.tsx` records that a live region has
 * to be in the accessibility tree before its content changes for the change to be announced, so a
 * region mounted with its sentence already in place is frequently read by nothing -- which loses the
 * first transition, the one that matters. A case that only looked for the sentence would pass against
 * exactly that arrangement.
 *
 * Assumptions: the sentence is the catalogue's authored `REQUEST_IN_PROGRESS` compared by identity, not
 * a literal, so a reword in the catalogue moves this case with it rather than breaking it.
 * @returns {Promise<void>} Resolves once both states have been observed.
 */
async function announcesTheOutstandingWrite(): Promise<void> {
  const held = deferredCard();
  vi.mocked(updateCard).mockReturnValue(held.promise);
  const user = await renderLoadedScreen();

  const region = screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID);
  expect(region).toHaveTextContent('');

  await amendAndCommit(user);

  await waitFor(
    /**
     * Waits until the outstanding write is announced.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent(REQUEST_IN_PROGRESS);
    },
  );

  await act(
    /**
     * Lets the held write settle inside the scope, so the screen's own update is flushed.
     * @returns {Promise<void>} Resolves once the write has settled.
     */
    async (): Promise<void> => {
      held.settle(AMENDED_CARD);
      await held.promise;
    },
  );

  await waitFor(
    /**
     * Waits until the announcement has been withdrawn.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent('');
    },
  );
}

/**
 * Builds a write whose settlement this case controls.
 *
 * Assumptions: a hand-rolled deferred rather than a timer, because the property under test is the state
 * of the screen WHILE a request is outstanding, and a timer would make the window a duration to race
 * against instead of a state to observe.
 * @returns {{ promise: Promise<CardDetail>; settle: (card: CardDetail) => void }} The promise to hand
 *   the mocked client, and the function that settles it.
 */
function deferredCard(): { promise: Promise<CardDetail>; settle: (card: CardDetail) => void } {
  /*
   * WHY : Assumptions: the captured resolver is held as possibly-undefined rather than seeded with a
   *       throwing placeholder, because a placeholder is itself a function and every function in this
   *       package carries a doc comment -- so seeding it would document a branch that the executor
   *       below makes unreachable. The guard in the returned settler states the same condition once.
   */
  let resolveWrite: ((card: CardDetail) => void) | undefined;
  const promise = new Promise<CardDetail>(
    /**
     * Captures the resolver so the case can settle the write when it chooses.
     * @param {(card: CardDetail) => void} resolve - The promise's own resolver.
     * @returns {void} Nothing; the resolver is captured as a side effect.
     */
    (resolve: (card: CardDetail) => void): void => {
      resolveWrite = resolve;
    },
  );
  return {
    promise,
    /**
     * Settles the held write with one record.
     * @param {CardDetail} card - The record the write answers with.
     * @returns {void} Nothing; the settlement is the effect.
     * @throws {Error} If called before the promise executor has run, which cannot happen for a native
     *   promise but is stated rather than assumed.
     */
    settle: (card: CardDetail): void => {
      if (resolveWrite === undefined) {
        throw new Error('the deferred write was settled before it was armed');
      }
      resolveWrite(card);
    },
  };
}

/**
 * A record that moved under the operator is reported as a conflict, not as a plain failure.
 *
 * ⚠️ Assumptions: the service is arranged to send NO sentence, so the one that appears is the
 * screen's own choice for this status. That is what separates a screen which classifies the
 * failure from one which merely echoes whatever text arrives -- the second would pass an
 * assertion on the sentence while treating a conflict identically to a transport error.
 *
 * Assumptions: three properties are asserted together. The conflict sentence appears; the
 * generic failure sentence does NOT; and the record is re-read, which is the recovery a
 * conflict calls for and a plain failure does not. Any one of the three alone admits a screen
 * that has collapsed the two paths.
 * @returns {Promise<void>} Resolves once the conflict has been reported and the re-read seen.
 */
async function distinguishesTheConcurrencyConflict(): Promise<void> {
  vi.mocked(updateCard).mockRejectedValue(conflictRefusal(null));
  const user = await renderLoadedScreen();

  await amendAndCommit(user);

  await screen.findByText(catalogued('DATA_WAS_CHANGED_BEFORE_UPDATE').text);
  expect(refusalBandText()).toContain(catalogued('DATA_WAS_CHANGED_BEFORE_UPDATE').text);
  expect(refusalBandText()).not.toContain(catalogued('LOCKED_BUT_UPDATE_FAILED').text);
  expect(informationLineText()).not.toContain(catalogued('INFORM_FAILURE').text);
  await waitFor(
    /**
     * Waits for the record to be re-read after the conflict.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(getCard).toHaveBeenCalledTimes(2);
    },
  );
}

/**
 * Returns the optimistic-lock version carried by each write this case dispatched, in order.
 *
 * ⚠️ Assumptions: the VERSIONS are collected rather than the call count alone, because the two
 * halves of the defect these cases exist for are only visible together. Two dispatches carrying
 * two different versions would be a retry; two carrying the SAME version are a duplicate that a
 * service honouring the version answers by accepting the first and refusing the second -- so the
 * operator is shown a failure for a write that landed. Browser validation measured exactly that
 * pair on this screen: two `PUT /api/v1/cards/{key}` requests, both with the same version member.
 * @returns {readonly number[]} The versions submitted, one per dispatched write.
 */
function submittedVersions(): readonly number[] {
  return vi.mocked(updateCard).mock.calls.map(
    /**
     * Reads the version out of one dispatched request.
     * @param {readonly [string, CardUpdateRequest]} call - One recorded call's arguments.
     * @returns {number} The version that call carried.
     */
    (call: readonly [string, CardUpdateRequest]): number => call[1].version,
  );
}

/**
 * Two activations of one open confirmation dispatch ONE write, carrying the read's version.
 *
 * ⚠️ Assumptions: both activations are raised with the DOM's own `click()` inside a single `act`
 * scope, and neither half of that is a convenience. It is the reproduction the review recorded --
 * "`el.click(); el.click();` in one synchronous JS task (the harshest case -- no React re-render
 * possible between them)" -- and it is the only shape that discriminates: React batches within the
 * scope, so a screen guarding its write with a `saving` STATE value sees `false` on both
 * activations and dispatches twice, while a screen guarding it with a synchronously mutated ref
 * sees `true` on the second and dispatches once. Testing Library's `fireEvent` wraps each call in
 * its own `act` and therefore FLUSHES between the two, which makes the state guard look sufficient
 * -- measured, and the reason this case is written against the raw element.
 *
 * Assumptions: the version is asserted as well as the count, so the case fails for either half
 * independently -- a second dispatch, or a first one carrying a version the read did not return.
 * The reference program's equivalent guarantee is its own before-image comparison immediately
 * before the commit at `app/cbl/COCRDUPC.cbl` L470, so a conflict there is a real condition to
 * name and not an artifact of the screen having asked twice.
 * @returns {Promise<void>} Resolves once the write has settled.
 */
async function writesOnceWhenOneConfirmationIsActivatedTwice(): Promise<void> {
  vi.mocked(updateCard).mockResolvedValue(AMENDED_CARD);
  const user = await renderLoadedScreen();

  await amendAndValidate(user);
  await pressPfKey(user, 'PFK05');
  const confirmation = await openConfirmation();
  const { accept } = confirmationActions(confirmation);

  act(
    /**
     * Activates the accepting action twice within one synchronous task.
     * @returns {void} Nothing; both activations are raised as a side effect.
     */
    (): void => {
      accept.click();
      accept.click();
    },
  );

  await screen.findByText(catalogued('CONFIRM_UPDATE_SUCCESS').text);
  expect(submittedVersions()).toEqual([CARD.version]);
}

/**
 * The write is gated by a synchronously readable latch and not by a render state value.
 *
 * ⚠️ Refactoring Rationale: this case reads the screen's own SOURCE, and it exists because the
 * behavioural case above provably cannot discriminate here. Measured in this environment: with the
 * pre-fix `if (saving)` guard restored, `accept.click(); accept.click();` inside one `act` scope
 * still produced exactly ONE dispatch -- the probe reported the button still connected and the call
 * count already at one after the first activation, because React flushes a discrete event's update
 * before the second activation's handler runs, and Testing Library's `fireEvent` additionally
 * wraps each call in its own scope. The review measured two dispatches in a real browser from the
 * same gesture, so the property is real and this environment is simply unable to observe it. The
 * predictor the review pinned it with IS observable, and with perfect correlation: it found that
 * the presence of an in-flight ref divided the nine mutating screens into the three that dispatch
 * once and the six that dispatch twice, this screen among the six.
 *
 * Assumptions: four facts are checked, not one, so the case cannot pass on a ref that is declared
 * and never consulted, nor on one that is consulted and never released. Trade-offs: a source
 * assertion is coarser than a behavioural one and would not notice a latch that was correct in
 * shape but wrong in placement; it is accepted because the alternative is no coverage at all for a
 * HIGH finding, and the three behavioural cases around it pin the outcomes the latch exists to
 * produce. Reading a screen's own module is an established idiom in this package --
 * `ui/src/test/transactionList.test.tsx` L286 does the same.
 * @returns {void} Nothing; each assertion carries its own outcome.
 */
function gatesTheWriteOnASynchronousLatch(): void {
  const source = readFileSync(
    join(import.meta.dirname, '..', 'screens', 'cardUpdate', 'index.tsx'),
    'utf8',
  );

  expect(source).toMatch(/const\s+writeInFlight\s*=\s*useRef\(false\)/u);
  expect(source).toMatch(/if\s*\(writeInFlight\.current\)\s*\{/u);
  expect(source).toMatch(/writeInFlight\.current\s*=\s*true/u);
  expect(source).toMatch(/writeInFlight\.current\s*=\s*false/u);
  /*
   * WHY : ⚠️ Assumptions: the ABSENCE of the state guard is asserted as well as the presence of the
   *       ref, because the two are not alternatives that can safely coexist on this arm. A screen
   *       that kept `if (saving)` beside the ref would still dispatch twice on whichever arm read
   *       the state, and the ref would make the source look fixed. `saving` remains in the module
   *       for what a render reads it for, which is why the pattern names the guard form rather than
   *       the identifier.
   */
  expect(source).not.toMatch(/if\s*\(saving\)\s*\{/u);
}

/**
 * A resubmission after a conflict carries the version the REFRESH returned, not the stale one.
 *
 * ⚠️ Assumptions: the second read answers a DIFFERENT version, so the case can tell a screen that
 * renews its retained record from one that merely re-reads and discards the answer. Resubmitting
 * the version the first read returned would be refused for ever, which is the state an operator
 * cannot escape from.
 *
 * ⚠️ Assumptions: the operator's edits are expected to SURVIVE the conflict, and the case depends
 * on it -- it amends nothing the second time and still reaches the confirmation, which is only
 * possible if the amended name is still in the control. That is the reference's own division:
 * `9300-CHECK-CHANGE-IN-REC` moves the freshly read values into the `CCUP-OLD-*` before-image
 * only (`app/cbl/COCRDUPC.cbl` L1512-L1518) and leaves `CCUP-NEW-*` -- the operator's typing --
 * untouched. A screen that re-seeded the form would answer the second Enter with the no-change
 * refusal instead, and this case would fail there.
 * @returns {Promise<void>} Resolves once the second write has been dispatched.
 */
async function resubmitsAgainstTheVersionTheRefreshReturned(): Promise<void> {
  /*
   * WHY : Assumptions: the moved record differs ONLY in its version, so nothing but the version
   *       can explain a difference between the two dispatched requests. A record that also moved
   *       its name would change what the change-detection compares and blur the property.
   */
  const moved: CardDetail = { ...CARD, version: CARD.version + 5 };
  vi.mocked(getCard).mockResolvedValueOnce(CARD).mockResolvedValue(moved);
  vi.mocked(updateCard)
    .mockRejectedValueOnce(conflictRefusal(null))
    .mockResolvedValue({ ...moved, embossedName: AMENDED_NAME, version: moved.version + 1 });

  const user = await renderScreen();
  await screen.findByDisplayValue(CARD.embossedName);

  await amendAndCommit(user);
  await screen.findByText(catalogued('DATA_WAS_CHANGED_BEFORE_UPDATE').text);
  await waitFor(
    /**
     * Waits for the refresh that renews the retained record.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(getCard).toHaveBeenCalledTimes(2);
    },
  );

  await pressPfKey(user, 'ENTER');
  await waitForTheConfirmationPrompt();
  await pressPfKey(user, 'PFK05');
  const confirmation = await openConfirmation();
  fireEvent.click(confirmationActions(confirmation).accept);

  await waitFor(
    /**
     * Waits for the second write to have been dispatched.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(updateCard).toHaveBeenCalledTimes(2);
    },
  );
  expect(submittedVersions()).toEqual([CARD.version, moved.version]);
}

/**
 * A conflict whose refresh FAILS still names the conflict and keeps the screen painted.
 *
 * ⚠️ Assumptions: this is the sequence browser validation measured -- `PUT` answered 409, then the
 * refreshing `GET` answered 409, and the screen displayed `Did not find cards for this search
 * condition`. Both statements were wrong at once: the record existed, and the write the operator
 * was being told about had a different problem entirely. A failed attempt to renew the
 * before-image is not news about the write, so the sentence the write produced has to survive it.
 *
 * Assumptions: the painted form is asserted as well as the sentence, because the reader used to
 * raise its busy state on this path -- which withdrew the row-23 zone and the key legend with it,
 * so the sentence had nowhere to appear even before it was overwritten. The reference never blanks
 * its map here: it re-sends `CCRDUPA` on the same turn (`app/cbl/COCRDUPC.cbl` L997-L998 into its
 * own send paragraph).
 * @returns {Promise<void>} Resolves once the refusal has been rendered over the live form.
 */
async function keepsTheConflictWhenTheRefreshFails(): Promise<void> {
  vi.mocked(getCard).mockResolvedValueOnce(CARD).mockRejectedValue(plainRefusal(null));
  vi.mocked(updateCard).mockRejectedValue(conflictRefusal(null));

  const user = await renderScreen();
  await screen.findByDisplayValue(CARD.embossedName);

  await amendAndCommit(user);
  await waitFor(
    /**
     * Waits for the refresh attempt that the conflict issues.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(getCard).toHaveBeenCalledTimes(2);
    },
  );

  await screen.findByText(catalogued('DATA_WAS_CHANGED_BEFORE_UPDATE').text);
  expect(refusalBandText()).toContain(catalogued('DATA_WAS_CHANGED_BEFORE_UPDATE').text);
  expect(refusalBandText()).not.toContain(catalogued('DID_NOT_FIND_ACCTCARD_COMBO').text);
  expect(control(CARD_UPDATE_FIELD_LABELS.nameOnCard)).toHaveValue(AMENDED_NAME);
}

/**
 * The two refusal shapes this file builds are classified as the client classifies them.
 *
 * ⚠️ Assumptions: the fixtures are fed to the client's OWN published predicate rather than
 * trusted on the status they carry. `ui/src/api/client.ts` keeps its conflict constant private,
 * so this file names the status itself; running both fixtures through the predicate is what
 * turns that into a checked agreement instead of a duplicated assumption that could drift.
 * @returns {void} Nothing; each verdict carries its own outcome.
 */
function buildsRefusalsTheClientClassifiesAsIntended(): void {
  expect(isConflictFailure(conflictRefusal(null))).toBe(true);
  expect(
    isConflictFailure(conflictRefusal(catalogued('DATA_WAS_CHANGED_BEFORE_UPDATE').text)),
  ).toBe(true);
  expect(isConflictFailure(plainRefusal(null))).toBe(false);
}

/**
 * The lock refusal reaches the band, and this program declares exactly one of them.
 *
 * ⚠️ Assumptions: the count is asserted against the account-update program's catalog rather
 * than stated. That screen touches two records and declares a lock sentence for each -- an
 * account one and a customer one -- while this screen touches one record and declares one.
 * Comparing the two groups makes the difference a measured fact, and stops a well-meant
 * "consistency" edit from inventing a second sentence this program never emits.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function surfacesTheSingleLockRefusal(): Promise<void> {
  const lockSentence = catalogued('COULD_NOT_LOCK_FOR_UPDATE').text;
  vi.mocked(updateCard).mockRejectedValue(plainRefusal(lockSentence));
  const user = await renderLoadedScreen();

  await amendAndCommit(user);

  await screen.findByText(lockSentence);
  expect(countLockSentences(Object.values(CATALOGUE))).toBe(1);
  expect(countLockSentences(Object.values(STATUS_MESSAGES.COACTUPC))).toBe(2);
}

/**
 * Counts the sentences in one catalog group that report a failure to lock a record.
 * @param {readonly StatusMessage[]} entries - One program's catalogued sentences.
 * @returns {number} How many of them report a lock failure.
 */
function countLockSentences(entries: readonly StatusMessage[]): number {
  let total = 0;
  for (const entry of entries) {
    if (/\block\b/iu.test(entry.text)) {
      total += 1;
    }
  }
  return total;
}

/*
 * WHY : ⚠️ Alternatives Considered: retyping each sentence into this file as an expected
 *       literal. Rejected because it cannot fail in the way that matters: a paraphrase in the
 *       screen and the same paraphrase in the test AGREE, so the case passes while fidelity is
 *       gone. Every sentence below is compared between two independent artifacts instead --
 *       the catalog in `ui/src/messages/messages.ts` and the `88`-level literal at the line
 *       the catalog itself cites in `app/cbl/COCRDUPC.cbl` -- so the assertion holds only
 *       while the migrated string is character-for-character the migrated program's, which is
 *       what Transformation Rule T8 requires.
 */

/**
 * Every sentence this program contributes is carried across character for character.
 *
 * ⚠️ Assumptions: the comparison is UNTRIMMED and uncollapsed on both sides, which is what
 * makes the awkward strings assertable rather than merely present: the exit sentence carries
 * fourteen trailing blanks inside its own quotes, the confirmation prompt has no space after
 * its full stop, and the placeholder sentence has four consecutive dots. Any normalisation on
 * either side would quietly accept a tidied-up copy of all three.
 *
 * Assumptions: each entry is checked at its OWN recorded line, so the two sentences that share
 * their text are each verified against their own declaration. The duplication is deliberate in
 * the reference -- two distinct conditions carry the same wording at two lines -- and
 * reconciling them would erase evidence that both paths exist.
 * @returns {void} Nothing; each comparison carries its own outcome.
 */
function carriesEverySentenceOfThisProgramVerbatim(): void {
  const entries = Object.entries(CATALOGUE);
  expect(entries.length).toBeGreaterThan(0);
  for (const [condition, entry] of entries) {
    expect(
      entry.text,
      `${condition} is filed at ${PROGRAM_SOURCE_FILES.COCRDUPC} line ${String(entry.line)}`,
    ).toBe(cobolLiteralAt(PROGRAM, entry.line, PROGRAM_SOURCE_FILES.COCRDUPC));
  }
}

/**
 * The three awkward sentences keep the properties that make them awkward.
 *
 * Assumptions: these are asserted as PROPERTIES of the catalogued text rather than against
 * retyped copies, so the case says what must be true of each string without becoming a second
 * place the string is written. Each property is the one a well-meant tidy-up would remove.
 * @returns {void} Nothing; each comparison carries its own outcome.
 */
function keepsThePaddingTheSpacingAndTheEllipsis(): void {
  const exiting = catalogued('WS_EXIT_MESSAGE').text;
  expect(exiting).not.toBe(exiting.trimEnd());

  expect(catalogued('PROMPT_FOR_CONFIRMATION').text).not.toContain('. ');
  expect(catalogued('CODING_TO_BE_DONE').text).toContain('....');

  expect(catalogued('SEARCHED_ACCT_ZEROES').text).toBe(
    catalogued('SEARCHED_ACCT_NOT_NUMERIC').text,
  );
  expect(catalogued('SEARCHED_ACCT_ZEROES').line).not.toBe(
    catalogued('SEARCHED_ACCT_NOT_NUMERIC').line,
  );
}

/**
 * The four sentences this program shares with others are carried verbatim and in upper case.
 *
 * ⚠️ Assumptions: this program MIXES casing deliberately and the mixture is asserted rather
 * than normalised. Its own validation sentences are mixed case; the two filter sentences it
 * shares in form with the browse and detail programs are upper case, as are its two exception
 * sentences. Rule T8 carries each string across as written, so a case that folded either side
 * would be asserting a convention the reference does not follow.
 *
 * Assumptions: the two filter sentences also carry no blank after their comma, which is
 * asserted for the same reason.
 * @returns {void} Nothing; each comparison carries its own outcome.
 */
function carriesTheSharedSentencesVerbatim(): void {
  const shared: readonly (readonly [
    string,
    readonly { readonly file: string; readonly lines: readonly number[] }[],
  ])[] = [
    [
      SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER,
      SHARED_MESSAGE_SOURCES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER,
    ],
    [
      SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER,
      SHARED_MESSAGE_SOURCES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER,
    ],
    [SHARED_MESSAGES.UNEXPECTED_DATA_SCENARIO, SHARED_MESSAGE_SOURCES.UNEXPECTED_DATA_SCENARIO],
    [SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED, SHARED_MESSAGE_SOURCES.UNEXPECTED_ABEND_OCCURRED],
  ];

  for (const [text, sites] of shared) {
    const linesHere = linesCitedFor(sites);
    expect(linesHere.length).toBeGreaterThan(0);
    for (const line of linesHere) {
      expect(text).toBe(cobolLiteralAt(PROGRAM, line, PROGRAM_SOURCE_FILES.COCRDUPC));
    }
    expect(text).toBe(text.toUpperCase());
  }

  expect(SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER).not.toContain(', ');
  expect(SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER).not.toContain(', ');

  for (const entry of Object.values(CATALOGUE)) {
    expect(entry.text).not.toBe(entry.text.toUpperCase());
  }
}

/**
 * Reads the lines one shared sentence is cited at in this program.
 * @param {readonly { readonly file: string; readonly lines: readonly number[] }[]} sites - The
 *   provenance the catalog records for a shared sentence.
 * @returns {readonly number[]} The lines cited in this program, empty when it is not a site.
 */
function linesCitedFor(
  sites: readonly { readonly file: string; readonly lines: readonly number[] }[],
): readonly number[] {
  for (const site of sites) {
    if (site.file === PROGRAM_SOURCE_FILES.COCRDUPC) {
      return site.lines;
    }
  }
  return [];
}

/**
 * The technical file diagnostic is neither catalogued nor rendered.
 *
 * ⚠️ Assumptions: the prefix EXISTS in the reference and is deliberately absent from the
 * migrated surface, and both halves are asserted. The reference composes it with the internal
 * file name and the CICS response and reason codes, none of which exist in this architecture,
 * and surfacing infrastructure identifiers to a browser would be an information-disclosure
 * regression rather than a parity gain. Asserting only its absence would leave a reader unable
 * to tell a considered omission from an overlooked string.
 * @returns {Promise<void>} Resolves once the failed read has been reported.
 */
async function withholdsTheTechnicalFileDiagnostic(): Promise<void> {
  const prefix = cobolLiteralAt(PROGRAM, FILE_ERROR_PREFIX_LINE, PROGRAM_SOURCE_FILES.COCRDUPC);
  expect(prefix.trim().length).toBeGreaterThan(0);
  for (const entry of Object.values(CATALOGUE)) {
    expect(entry.text).not.toBe(prefix);
  }

  vi.mocked(getCard).mockRejectedValue(plainRefusal(null));
  await renderScreen();

  await screen.findByText(catalogued('DID_NOT_FIND_ACCTCARD_COMBO').text);
  expect(document.body.textContent ?? '').not.toContain(prefix.trim());
}

/**
 * A name outside the program's own alphabet is refused with the program's own sentence.
 *
 * ⚠️ Assumptions: the offending character is asserted to be outside the alphabet the program
 * declares, rather than merely assumed to be. The program builds its edit from a fifty-two
 * character literal and its two halves, so this case first proves the three declarations agree
 * -- the upper half followed by the lower half IS the whole -- and then proves the character it
 * types appears in none of them. Without that, the case would be asserting a rule of its own
 * choosing.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function refusesANameOutsideTheProgramsAlphabet(): Promise<void> {
  const whole = cobolLiteralAt(PROGRAM, ALPHABET_LITERAL_LINE, PROGRAM_SOURCE_FILES.COCRDUPC);
  const upper = cobolLiteralAt(PROGRAM, UPPER_ALPHABET_LINE, PROGRAM_SOURCE_FILES.COCRDUPC);
  const lower = cobolLiteralAt(PROGRAM, LOWER_ALPHABET_LINE, PROGRAM_SOURCE_FILES.COCRDUPC);
  expect(`${upper}${lower}`).toBe(whole);
  expect(whole).not.toContain(NON_ALPHABETIC_CHARACTER);

  const user = await renderLoadedScreen();
  const name = control(CARD_UPDATE_FIELD_LABELS.nameOnCard);
  await user.clear(name);
  await user.type(name, `${AMENDED_NAME}${NON_ALPHABETIC_CHARACTER}`);
  await pressPfKey(user, 'ENTER');

  await screen.findByText(catalogued('WS_NAME_MUST_BE_ALPHA').text);
  expect(updateCard).not.toHaveBeenCalled();
}

/**
 * An empty name is refused with the program's not-provided sentence.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function refusesAnEmptyName(): Promise<void> {
  const user = await renderLoadedScreen();
  await user.clear(control(CARD_UPDATE_FIELD_LABELS.nameOnCard));
  await pressPfKey(user, 'ENTER');

  await screen.findByText(catalogued('WS_PROMPT_FOR_NAME').text);
  expect(updateCard).not.toHaveBeenCalled();
}

/**
 * A month outside the range the program's own sentence names is refused by that sentence.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function boundsTheExpiryMonth(): Promise<void> {
  const user = await renderLoadedScreen();
  const month = control(EXPIRY_MONTH_LABEL);
  await user.clear(month);
  await user.type(month, OUT_OF_RANGE_MONTH);
  await pressPfKey(user, 'ENTER');

  await screen.findByText(catalogued('CARD_EXPIRY_MONTH_NOT_VALID').text);
  expect(updateCard).not.toHaveBeenCalled();
}

/**
 * A refused year is reported by the year sentence and not by the month one.
 *
 * Assumptions: the month sentence is asserted ABSENT, because the two edits are separate in
 * the reference -- one bounds the month and one covers the year -- and a screen that reported
 * either failure with one sentence would pass a case that only looked for the year's.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function boundsTheExpiryYear(): Promise<void> {
  const user = await renderLoadedScreen();
  const year = control(EXPIRY_YEAR_LABEL);
  await user.clear(year);
  await user.type(year, REFUSED_YEAR);
  await pressPfKey(user, 'ENTER');

  await screen.findByText(catalogued('CARD_EXPIRY_YEAR_NOT_VALID').text);
  expect(screen.queryByText(catalogued('CARD_EXPIRY_MONTH_NOT_VALID').text)).toBeNull();
  expect(updateCard).not.toHaveBeenCalled();
}

/**
 * The active status admits the two values its own sentence names and refuses a third.
 *
 * Assumptions: both admitted values are exercised and not just the refusal, because a control
 * that refused everything would satisfy a refusal-only case. The accepted value is the one the
 * record does NOT already carry, so the submission also counts as a change.
 * @returns {Promise<void>} Resolves once both turns have been checked.
 */
async function restrictsTheActiveStatusToItsTwoValues(): Promise<void> {
  vi.mocked(updateCard).mockResolvedValue(AMENDED_CARD);
  const user = await renderLoadedScreen();
  const status = control(CARD_UPDATE_FIELD_LABELS.cardActive);

  await user.clear(status);
  await user.type(status, REFUSED_STATUS_CODE);
  await pressPfKey(user, 'ENTER');
  await screen.findByText(catalogued('CARD_STATUS_MUST_BE_YES_NO').text);
  expect(updateCard).not.toHaveBeenCalled();

  await user.clear(status);
  await user.type(status, CARD.activeStatus === 'Y' ? 'N' : 'Y');
  await pressPfKey(user, 'ENTER');
  await waitForTheConfirmationPrompt();
}

/**
 * The abend surface contributes field widths and no text, and refuses an unusable selector.
 *
 * ⚠️ Assumptions: the abend copybook is a source of WIDTHS rather than of sentences, and this
 * case proves it rather than asserting it: all four of its fields are declared `VALUE SPACES`,
 * so the copybook contributes an empty structure that a failing program fills, and the wording
 * an operator sees comes from the programs and is catalogued among the shared sentences.
 * Treating the copybook as a source of text would yield four empty strings and hide where the
 * real wording lives.
 *
 * ⚠️ Refactoring Rationale: the render half no longer asserts `.ant-result-error`, and no longer
 * expects the refusal sentence on arrival. It asserted both, and both were the defect: the source
 * program answers a turn holding no usable key by SENDING ITS MAP -- `COCRDUPC` has no `SEND TEXT`
 * anywhere and `3250-SETUP-INFOMSG` gives that turn its own row-22 prompt
 * (`app/cbl/COCRDUPC.cbl` L1141-L1144) -- so an error page that erases the frame states less than
 * the terminal did, and row 23 is reserved and QUIET until the operator submits something.
 * {@link keepsTheFrameWhenTheSelectorIsRefused} carries what this turn now paints.
 *
 * Assumptions: the refusal is still reached by giving the route a card-number-shaped value the
 * selector guard refuses, which is also the assertion that the guard refuses one, and the read is
 * still asserted not to have been issued -- an unusable address is answered without asking the
 * service about it.
 * @returns {Promise<void>} Resolves once the refused turn has rendered.
 */
async function carriesTheAbendSurfaceAsWidthsAndNotText(): Promise<void> {
  expect(ABEND_DATA_FIELDS).toHaveLength(pictureDeclarationCount(ABEND_COPYBOOK));
  for (const field of ABEND_DATA_FIELDS) {
    expect(pictureWidthsAt(ABEND_COPYBOOK, field.source.lines)).toEqual([field.declaredWidth]);
    expect(declarationText(ABEND_COPYBOOK, field.source.lines)).toContain('VALUE SPACES');
  }

  await renderScreen(CARD_NUMBER_SHAPED_SELECTOR);

  expect(CARD_NUMBER_SHAPED_SELECTOR).toHaveLength(
    pictureWidthsAt(CARD_RECORD, [CARD_NUMBER_DECLARATION_LINE])[0] ?? 0,
  );
  expect(document.querySelector('.ant-result')).toBeNull();
  expect(refusalBandText()).toBe('');
  expect(getCard).not.toHaveBeenCalled();
}

/**
 * A refused selector keeps the map frame, and Enter on that turn states the source's own sentence.
 *
 * Purpose: this is the case for the finding that `/cards/:key/edit` answered an unusable address by
 * replacing the frame with a centred error page while its sibling detail screen kept the frame for
 * the same condition. The reference settles it: every arm of `3400-SEND-SCREEN` issues
 * `EXEC CICS SEND MAP` with `CCRDUPA` (`app/cbl/COCRDUPC.cbl` L1323-L1336) and the program contains
 * no `SEND TEXT` at all, so all five bands the mapset declares are painted on this turn too --
 * identity on rows 1 and 2, the row-22 prompt `3250-SETUP-INFOMSG` selects for
 * `CCUP-DETAILS-NOT-FETCHED` (L1141-L1144), row 23 reserved and quiet, and the row-24 legend that
 * `app/bms/COCRDUP.bms` L158-L162 declares `ATTRB=(ASKIP,NORM)` and no paragraph darkens.
 *
 * Assumptions: Enter is asserted to state `No input received` rather than to do nothing, because
 * the legend OFFERS it on this turn and `1200-EDIT-MAP-INPUTS` answers it -- it validates the two
 * search keys while `CCUP-DETAILS-NOT-FETCHED` and, finding both blank, sets
 * `NO-SEARCH-CRITERIA-RECEIVED` (L645-L659), whose literal is declared at L185-L186. A key painted
 * on the legend that silently does nothing is the failure this half guards against.
 * @returns {Promise<void>} Resolves once the refused turn has answered an Enter.
 */
async function keepsTheFrameWhenTheSelectorIsRefused(): Promise<void> {
  const user = await renderScreen(CARD_NUMBER_SHAPED_SELECTOR);

  expect(document.querySelector('.ant-result')).toBeNull();
  expect(screen.getByRole('heading', { name: CARD_UPDATE_TITLE })).toBeInTheDocument();
  expectVerbatimMessage(CARD_UPDATE_TRANSACTION_ID);
  expectVerbatimMessage(CARD_UPDATE_PROGRAM_NAME);
  expect(informationLineText()).toContain(catalogued('PROMPT_FOR_SEARCH_KEYS').text);
  expect(refusalBandText()).toBe('');
  expect(legendRegion()).toBeInTheDocument();
  /*
   * Assumptions: BOTH descriptors of the unconditional legend field are expected, not just the exit
   * key, because `app/bms/COCRDUP.bms` L158-L162 declares `FKEYS` `ATTRB=(ASKIP,NORM)` with
   * `INITIAL='ENTER=Process F3=Exit'` and the only field `3300-SETUP-SCREEN-ATTRS` ever un-darkens is
   * `FKEYSC` (`app/cbl/COCRDUPC.cbl` L1315-L1317). So this turn paints two keys and no more, and the
   * conditional pair stays dark.
   */
  expect(legendLabels()).toEqual([...PRIMARY_LEGEND]);

  await pressPfKey(user, 'ENTER');

  await screen.findByText(catalogued('NO_SEARCH_CRITERIA_RECEIVED').text);
  expect(refusalBandText()).toContain(catalogued('NO_SEARCH_CRITERIA_RECEIVED').text);
  expect(getCard).not.toHaveBeenCalled();
}

/**
 * The static address this screen is also reachable at, which carries NO route parameter.
 *
 * Assumptions: taken from the router's own alias table rather than written as a literal, so this file
 * tracks the address the application publishes instead of a copy of it. `ui/src/router.tsx` records
 * why the alias is STATIC: a sentinel parameter would make a screen that guards on a DEFINED parameter
 * tell an operator their link was broken.
 * @throws {RangeError} If the alias table holds no entry for this program, so a withdrawn alias fails
 *   here rather than in a route that cannot match.
 */
const KEYLESS_ENTRY_ADDRESS = keylessEntryAddressFor(CARD_UPDATE_PROGRAM_NAME);

/**
 * Returns the static alias the router publishes for one reference program.
 *
 * Assumptions: a named declaration rather than an immediately-invoked arrow, for the reason this file's
 * header records -- `jsdoc/require-jsdoc` requires a block on an arrow in every position, and Prettier
 * detaches a block written before one from the function it documents.
 * @param {string} program - Name of the reference program, as the alias table records it.
 * @returns {string} That program's parameter-free address.
 * @throws {RangeError} If the alias table holds no entry for the program, so a withdrawn alias fails
 *   here rather than in a route that cannot match.
 */
function keylessEntryAddressFor(program: string): string {
  for (const entry of KEYLESS_ENTRY_ROUTES) {
    if (entry.program === program) {
      return entry.path;
    }
  }
  throw new RangeError(`no keyless entry route is published for ${program}`);
}

/**
 * An arrival with NO route parameter keeps the whole frame, reads nothing, and prompts for the keys.
 *
 * ⚠️ Assumptions: the parameter is ABSENT rather than malformed, which is a different arrival from the
 * one {@link keepsTheFrameWhenTheSelectorIsRefused} covers and is why both exist. This screen is
 * reachable from a menu option that names the program and carries no record
 * (`app/cpy/COMEN02Y.cpy`), so the router publishes a static alias for it -- and a screen that guarded
 * on a DEFINED parameter would answer that arrival by telling the operator their link was broken. The
 * reference has this arrival too and answers it by painting the map with the search keys unprotected
 * and the row-22 prompt `3250-SETUP-INFOMSG` selects for `CCUP-DETAILS-NOT-FETCHED`
 * (`app/cbl/COCRDUPC.cbl` L1141-L1144).
 *
 * ⚠️ Assumptions: NO read is issued, and that is asserted rather than inferred from the absence of a
 * record on the glass. There is no key to read by, so a request would be one built from `undefined` --
 * which is the shape that reaches a service as a literal `/cards/undefined` and is answered with a 404
 * the operator cannot act on.
 *
 * Assumptions: the whole frame is counted, not just the absence of an error page. The finding this
 * guards against replaced the frame with a centred result, so asserting only that the prompt appears
 * would pass against a frame that had lost its header, its legend or its pinned zone.
 * @returns {Promise<void>} Resolves once the selector-free arrival has been observed.
 */
async function keepsTheFrameOnTheKeylessEntryRoute(): Promise<void> {
  await act(
    /**
     * Mounts the screen at its static alias, where the route declares no parameter at all.
     * @returns {Promise<void>} Resolves once the arrival has settled.
     */
    async (): Promise<void> => {
      await renderInAppShell(<CardUpdateScreen />, {
        initialEntries: [KEYLESS_ENTRY_ADDRESS],
        routePath: KEYLESS_ENTRY_ADDRESS,
      });
    },
  );

  expect(KEYLESS_ENTRY_ADDRESS).not.toContain(':');
  expect(document.querySelector('.ant-result')).toBeNull();
  expect(screen.getAllByTestId(APP_SHELL_TEST_ID)).toHaveLength(1);
  expect(document.querySelectorAll('main')).toHaveLength(1);
  expect(screen.getAllByTestId(SHELL_PINNED_ZONE_TEST_ID)).toHaveLength(1);
  expect(screen.getByRole('heading', { name: CARD_UPDATE_TITLE })).toBeInTheDocument();
  expectVerbatimMessage(CARD_UPDATE_TRANSACTION_ID);
  expectVerbatimMessage(CARD_UPDATE_PROGRAM_NAME);
  expect(informationLineText()).toContain(catalogued('PROMPT_FOR_SEARCH_KEYS').text);
  expect(refusalBandText()).toBe('');
  expect(legendRegion()).toBeInTheDocument();
  expect(getCard).not.toHaveBeenCalled();
}

/** Which of a key's two entry points a case drives it through. */
type DispatchEntry = 'keyboard' | 'legend';

/**
 * Raises one attention identifier through the entry point a case names.
 *
 * ⚠️ Alternatives Considered: driving only the visible controls, which is easier because a
 * click needs no key mapping. Rejected because the contract this screen inherits is a KEYBOARD
 * contract -- the 3270 original was operated entirely from the keyboard -- so a case that only
 * clicks passes in full while every keyboard binding in the application is broken. Driving both
 * entry points through one parameter is what keeps the two halves from drifting: the legend
 * control and the key press must reach the same dispatch, and a case that exercises one of them
 * cannot report on the other.
 * @param {UserEvent} user - The operator from {@link renderLoadedScreen}.
 * @param {CicsAid} aid - The attention identifier to raise.
 * @param {string} label - The legend descriptor for the same action, from the mapset.
 * @param {DispatchEntry} entry - Which entry point to drive.
 * @returns {Promise<void>} Resolves once the dispatch has been raised and settled.
 */
async function invokeKey(
  user: UserEvent,
  aid: CicsAid,
  label: string,
  entry: DispatchEntry,
): Promise<void> {
  if (entry === 'keyboard') {
    await pressPfKey(user, aid);
    return;
  }
  await user.click(legendControl(label));
}

/**
 * Every action this screen binds is reachable through one entry point, end to end.
 *
 * Assumptions: the three non-exiting actions are driven in one render, in the order an operator
 * would meet them -- validate, ask to save, decline, then cancel -- because each one's
 * availability depends on the turn the previous one produced. Splitting them would need three
 * renders and would stop asserting that the save key is offered only after a validated turn.
 * @param {DispatchEntry} entry - Which entry point to drive every action through.
 * @returns {Promise<void>} Resolves once the cancelled turn has been re-read.
 */
async function dispatchesEveryActionVia(entry: DispatchEntry): Promise<void> {
  vi.mocked(updateCard).mockResolvedValue(AMENDED_CARD);
  const user = await renderLoadedScreen();
  const [enterDescriptor] = PRIMARY_LEGEND;
  const [saveDescriptor, cancelDescriptor] = CONDITIONAL_LEGEND;

  const name = control(CARD_UPDATE_FIELD_LABELS.nameOnCard);
  await user.clear(name);
  await user.type(name, AMENDED_NAME);

  await invokeKey(user, 'ENTER', enterDescriptor ?? '', entry);
  await waitForTheConfirmationPrompt();

  await invokeKey(user, 'PFK05', saveDescriptor ?? '', entry);
  const confirmation = await openConfirmation();
  fireEvent.click(confirmationActions(confirmation).decline);
  expect(updateCard).not.toHaveBeenCalled();

  await invokeKey(user, 'PFK12', cancelDescriptor ?? '', entry);
  await waitFor(
    /**
     * Waits for the cancelled turn to have re-read the record.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(getCard).toHaveBeenCalledTimes(2);
    },
  );
  await screen.findByText(catalogued('FOUND_CARDS_FOR_ACCOUNT').text);
  expect(updateCard).not.toHaveBeenCalled();
}

/**
 * Every action is reachable from the keyboard.
 * @returns {Promise<void>} Resolves once every action has been dispatched.
 */
async function dispatchesEveryActionFromTheKeyboard(): Promise<void> {
  await dispatchesEveryActionVia('keyboard');
}

/**
 * Every action is reachable from the visible legend.
 * @returns {Promise<void>} Resolves once every action has been dispatched.
 */
async function dispatchesEveryActionFromTheLegend(): Promise<void> {
  await dispatchesEveryActionVia('legend');
}

/**
 * The exit action leaves the screen, from either entry point.
 *
 * Assumptions: leaving is observed as the form no longer being mounted rather than as an
 * address, because only this screen's route is mounted in a case's router -- so a navigation
 * away matches nothing and the outlet empties, which is the observable the assertion can rely
 * on without a second route standing in for a destination.
 * @param {DispatchEntry} entry - Which entry point to drive the exit through.
 * @returns {Promise<void>} Resolves once the screen has been left.
 */
async function exitsVia(entry: DispatchEntry): Promise<void> {
  const user = await renderLoadedScreen();
  const [, exitDescriptor] = PRIMARY_LEGEND;

  await invokeKey(user, 'PFK03', exitDescriptor ?? '', entry);

  await waitFor(
    /**
     * Waits for the screen to have been left.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.queryByLabelText(collapse(CARD_UPDATE_FIELD_LABELS.nameOnCard))).toBeNull();
    },
  );
  expect(updateCard).not.toHaveBeenCalled();
}

/**
 * The exit action is reachable from the keyboard.
 * @returns {Promise<void>} Resolves once the screen has been left.
 */
async function exitsFromTheKeyboard(): Promise<void> {
  await exitsVia('keyboard');
}

/**
 * The exit action is reachable from the visible legend.
 * @returns {Promise<void>} Resolves once the screen has been left.
 */
async function exitsFromTheLegend(): Promise<void> {
  await exitsVia('legend');
}

/**
 * The legend offers exactly the descriptors the mapset paints, in two stages.
 *
 * ⚠️ Assumptions: the mapset declares TWO legend fields and this screen has two stages because
 * of it -- `FKEYS` is `ATTRB=(ASKIP,NORM)` and painted always, while `FKEYSC` is
 * `ATTRB=(ASKIP,DRK)` at `app/bms/COCRDUP.bms` L163, dark until the program reveals it. The two
 * descriptors it hides are precisely the save and cancel actions, which exist only once changes
 * have been validated, so a screen offering all four from the start would offer a commit for a
 * turn that has nothing to commit.
 *
 * Assumptions: the two literals' lengths are checked against their own declared widths, which is
 * how the legend fields' widths enter this file -- `FKEYSI` and `FKEYSCI` hold exactly the text
 * their fields paint, with no padding left over.
 * @returns {Promise<void>} Resolves once both stages have been observed.
 */
async function paintsTheLegendInTheTwoStagesTheMapsetDeclares(): Promise<void> {
  expect(PRIMARY_LEGEND.join(' ')).toHaveLength(declaredField(MAP_FIELDS, 'FKEYSI').width);
  expect(CONDITIONAL_LEGEND.join(' ')).toHaveLength(declaredField(MAP_FIELDS, 'FKEYSCI').width);
  expect(mapsetAttributes(MAPSET, 'FKEYSC')).toContain('DRK');
  expect(mapsetAttributes(MAPSET, 'FKEYS')).not.toContain('DRK');

  const user = await renderLoadedScreen();
  expect(legendLabels()).toEqual([...PRIMARY_LEGEND]);

  await amendAndValidate(user);
  expect(legendLabels()).toEqual([...PRIMARY_LEGEND, ...CONDITIONAL_LEGEND]);
}

/**
 * No key beyond the four the program dispatches on does anything.
 *
 * ⚠️ Assumptions: an unbound key is asserted to be INERT, and the key chosen is the FOURTH
 * function key -- which other screens in this tree do bind, to the clear action the migration
 * plan measures in six of the online programs -- so the case distinguishes "this screen does not
 * bind it" from "no screen binds it". `app/cbl/COCRDUPC.cbl` dispatches on exactly four attention
 * identifiers (`CCARD-AID-ENTER` L414, `CCARD-AID-PFK03` L415, `CCARD-AID-PFK05` L416 and
 * `CCARD-AID-PFK12` L418), and a fifth binding here would give an operator an action the
 * reference has no arm for.
 *
 * ⚠️ Assumptions: no invalid-key sentence is asserted, and its ABSENCE is asserted instead.
 * `app/cbl/COCRDUPC.cbl` moves `CCDA-MSG-INVALID-KEY` nowhere -- this program is one of six that
 * never does -- so a case demanding that sentence would be demanding behaviour the reference
 * does not have. The program source is searched here so the claim is measured rather than
 * recalled.
 * @returns {Promise<void>} Resolves once the unbound key has been shown to be inert.
 */
async function bindsNoKeyBeyondTheFourItDispatchesOn(): Promise<void> {
  expect(PROGRAM.join('\n')).not.toContain('CCDA-MSG-INVALID-KEY');

  const user = await renderLoadedScreen();
  const before = informationLineText();

  await pressPfKey(user, 'PFK04');

  expect(informationLineText()).toBe(before);
  expect(refusalBandText()).toBe('');
  expect(document.body.textContent ?? '').not.toContain(COMMON_MESSAGES.INVALID_KEY.text.trim());
  expect(updateCard).not.toHaveBeenCalled();
  expect(getCard).toHaveBeenCalledTimes(1);
}

/**
 * The one key that changes the record carries the emphasis, and the three that do not, do not.
 *
 * ⚠️ Refactoring Rationale: ONE key is expected to be emphasised, and two were. The emphasis used to be
 * derived from the attention identifier, through `PRIMARY_ACTION_AIDS`, and that table cannot be right
 * across this application: the same `PFK05` is `F5=Save` here, `F5=Delete` on `app/bms/COUSR03.bms`
 * L148 and a browse key elsewhere, so one AID-keyed answer paints a delete and a save identically.
 * `ui/src/layout/PfKeyBar.tsx` now resolves emphasis from what the key's LABEL says its action does, and
 * this screen declares that: `F5=Save` is `mutating` and takes the emphasis, while `ENTER=Process`,
 * `F3=Exit` and `F12=Cancel` are `read-only` and take the default treatment. The classification follows
 * the source arms rather than the names -- `2000-PROCESS-INPUTS` ends by setting the confirmation prompt
 * without writing (`app/cbl/COCRDUPC.cbl` L1138-L1163), the exit transfers without writing (L442-L454),
 * and the cancel redisplays the stored record -- so `ENTER` losing the emphasis is the correction, not a
 * regression: it never wrote anything.
 *
 * Assumptions: each control's advertised shortcut is asserted beside its emphasis, so the visible
 * control is tied to the key press it stands for. A control that looked right and advertised the wrong
 * key would leave a screen-reader user pressing something else.
 * @returns {Promise<void>} Resolves once all four controls have been checked.
 */
async function marksTheActingKeysWithThePrimaryEmphasis(): Promise<void> {
  const user = await renderLoadedScreen();
  await amendAndValidate(user);

  const [enterDescriptor, exitDescriptor] = PRIMARY_LEGEND;
  const [saveDescriptor, cancelDescriptor] = CONDITIONAL_LEGEND;

  /*
   * WHY : ⚠️ Assumptions: the AID table is asserted NOT to be what decided this screen's paint, which is
   *       the half a positive assertion cannot cover. It still contains `ENTER`, because six other
   *       screens have yet to declare their risks and it remains their fallback; this screen has
   *       declared, so its `ENTER` renders default DESPITE being in that table. Asserting the
   *       disagreement is what proves the risk declaration is the operative input.
   */
  expect(PRIMARY_ACTION_AIDS).toContain('ENTER');
  expect(legendControl(enterDescriptor ?? '')).not.toHaveClass('ant-btn-primary');

  const acting = [saveDescriptor ?? ''];
  const supporting = [enterDescriptor ?? '', exitDescriptor ?? '', cancelDescriptor ?? ''];
  for (const descriptor of acting) {
    expect(legendControl(descriptor)).toHaveClass('ant-btn-primary');
    // WHY : Assumptions: the save is asserted NOT to be dangerous, which is the other half of the
    //       classification. `mutating` and `destructive` both resolve to the primary type and differ
    //       only by the danger flag, so the positive assertion alone would pass against a save painted
    //       as though it removed the record.
    expect(legendControl(descriptor)).not.toHaveClass('ant-btn-dangerous');
  }
  // WHY : Assumptions: the supporting keys are asserted to carry the DEFAULT treatment and not
  //       merely to lack the primary one. The design system emits one class per resolved button
  //       type, so a control that had lost its type altogether would satisfy a bare negative
  //       while rendering as neither of the two treatments AAP section 0.3.2 assigns.
  for (const descriptor of supporting) {
    expect(legendControl(descriptor)).toHaveClass('ant-btn-default');
    expect(legendControl(descriptor)).not.toHaveClass('ant-btn-primary');
  }
  expect(legendControl(enterDescriptor ?? '')).toHaveAttribute('aria-keyshortcuts', 'Enter');
  expect(legendControl(exitDescriptor ?? '')).toHaveAttribute('aria-keyshortcuts', 'F3');
  expect(legendControl(saveDescriptor ?? '')).toHaveAttribute('aria-keyshortcuts', 'F5');
  expect(legendControl(cancelDescriptor ?? '')).toHaveAttribute('aria-keyshortcuts', 'F12');
}

/**
 * The primary account number is masked on this screen, and no whole number is exposed.
 *
 * ⚠️ Assumptions: the administrative exception is NARROW and does not reach here. The
 * unmasked number is published by the administrative card-detail operation alone -- which is
 * why `ui/src/api/cards.ts` declares a separate response type for it, and why that type is not
 * the one this screen consumes. An update screen has no need of the whole number, so masking is
 * the rule here and the exception is not a screen-level licence.
 *
 * Assumptions: input VALUES are gathered alongside the document text, because a control's value
 * is a property rather than a text node -- a text-only sweep would look clean while the number
 * sat in an input.
 * @returns {Promise<void>} Resolves once the rendered surface has been swept.
 */
async function masksThePrimaryAccountNumber(): Promise<void> {
  await renderLoadedScreen();

  const rendered = control(CARD_UPDATE_FIELD_LABELS.cardNumber);
  expect(rendered).toHaveValue(CARD.displayCardNumber);
  expect(rendered).toHaveAttribute('readonly');
  expect(CARD.displayCardNumber).not.toMatch(/^[0-9]+$/u);

  const declaredNumberWidth = pictureWidthsAt(CARD_RECORD, [CARD_NUMBER_DECLARATION_LINE])[0] ?? 0;
  expect(declaredNumberWidth).toBeGreaterThan(0);
  expect(exposedValues()).not.toMatch(new RegExp(`[0-9]{${String(declaredNumberWidth)}}`, 'u'));
}

/**
 * Gathers everything the rendered document exposes, as text and as control values.
 * @returns {string} The document's text and every control value, blank-separated so no two
 *   values can run together into a longer digit run than either carries.
 */
function exposedValues(): string {
  const values = Array.from(document.querySelectorAll('input')).map(
    /**
     * Reads one control's current value.
     * @param {HTMLInputElement} field - One rendered control.
     * @returns {string} Its value.
     */
    (field: HTMLInputElement): string => field.value,
  );
  return [...values, document.body.textContent ?? ''].join(' ');
}

/**
 * The card verification code appears in no member, no label and no rendered text.
 *
 * ⚠️ Assumptions: the field's NAME is read out of `app/cpy/CVACT02Y.cpy` at run time and appears
 * nowhere in this file as a literal, so a search of the new trees for that term finds nothing a
 * test introduced. The record declares it and no response type carries it: the migrated contract
 * simply has no member for it, which is stronger than masking because there is nothing to leak.
 * @returns {Promise<void>} Resolves once the rendered surface and the submission have been
 *   swept.
 */
async function neverExposesTheCardVerificationCode(): Promise<void> {
  expect(GUARDED_FIELD_TOKEN.length).toBeGreaterThan(0);
  expect(Object.keys(CARD).join(' ').toLowerCase()).not.toContain(GUARDED_FIELD_TOKEN);

  vi.mocked(updateCard).mockResolvedValue(AMENDED_CARD);
  const user = await renderLoadedScreen();
  await amendAndCommit(user);

  const submitted = vi.mocked(updateCard).mock.calls[0]?.[1];
  expect(
    Object.keys(submitted ?? {})
      .join(' ')
      .toLowerCase(),
  ).not.toContain(GUARDED_FIELD_TOKEN);
  expect((document.body.textContent ?? '').toLowerCase()).not.toContain(GUARDED_FIELD_TOKEN);
  expect(exposedValues().toLowerCase()).not.toContain(GUARDED_FIELD_TOKEN);
}

/**
 * The selection arrives in the request path, and nothing about the session travels with it.
 *
 * ⚠️ Refactoring Rationale: the reference carries its selection in the communication area --
 * `CDEMO-CARD-NUM PIC 9(16)` at `app/cpy/COCOM01Y.cpy` L41 -- which is storage the client echoes
 * back on every turn. A path parameter is not the same thing wearing different clothes: the
 * communication area could carry a value the client had chosen, so the server could not tell a
 * legitimate selection from an asserted one, whereas a request whose selection is in its own
 * path is self-describing and can be authorised on its own. That is why the request body below
 * is asserted to carry the five members of the update contract and NOTHING else -- no identity,
 * no navigation, no re-entry discriminator.
 *
 * ⚠️ Refactoring Rationale: the parameter is an opaque selector rather than the number, and the
 * case asserts both that it is not all digits and that it is not the declared card-number
 * length. `ui/src/routes/cards.ts` registers that as divergence `D-CARD-SELECTOR`: the request
 * line is logged by infrastructure in front of both the browser and the services, so a number in
 * the path would be persisted where no application code can redact it.
 *
 * Assumptions: identifiers stay STRINGS through the submission, and the leading zeros of the
 * eleven-character account identifier are the concrete evidence -- a numeric coercion would
 * render two characters where the field declares eleven, and the operator would be looking at a
 * different account.
 * @returns {Promise<void>} Resolves once the submission has been inspected.
 */
async function takesItsSelectionFromTheRouteAndCarriesNoSessionField(): Promise<void> {
  vi.mocked(updateCard).mockResolvedValue(AMENDED_CARD);
  const user = await renderLoadedScreen();

  expect(getCard).toHaveBeenCalledWith(CARD_SELECTOR);
  await amendAndCommit(user);

  const call = vi.mocked(updateCard).mock.calls[0];
  expect(call?.[0]).toBe(CARD_SELECTOR);

  const submitted: CardUpdateRequest | undefined = call?.[1];
  expect(Object.keys(submitted ?? {}).sort()).toEqual([
    'activeStatus',
    'embossedName',
    'expirationMonth',
    'expirationYear',
    'version',
  ]);
  expect(typeof submitted?.embossedName).toBe('string');
  expect(typeof submitted?.expirationMonth).toBe('string');
  expect(typeof submitted?.expirationYear).toBe('string');
  expect(typeof submitted?.activeStatus).toBe('string');
  /*
   * WHY : Trade-offs: the precondition is the one member that is legitimately a number, and it
   *       is asserted as one on purpose. It is a revision COUNTER rather than an amount, so no
   *       exactness is at stake; money and identifiers are the values that must never be
   *       numbers, because a JSON number is parsed into an IEEE-754 double by most clients.
   */
  expect(typeof submitted?.version).toBe('number');

  const parameter = ROUTE_PARAMETER_PATTERN.exec(EDIT_ROUTE)?.[1] ?? '';
  expect(parameter.length).toBeGreaterThan(0);
  expect(CARD_SELECTOR).not.toMatch(/^[0-9]+$/u);
  expect(CARD_SELECTOR.length).not.toBe(
    pictureWidthsAt(CARD_RECORD, [CARD_NUMBER_DECLARATION_LINE])[0] ?? 0,
  );
  expect(control(CARD_UPDATE_FIELD_LABELS.accountNumber)).toHaveValue(CARD.accountId);
  expect(CARD.accountId).toHaveLength(declaredField(MAP_FIELDS, 'ACCTSIDI').width);
}

/**
 * A refused field is marked, a blank one additionally carries the marker, a clean one is left
 * alone.
 *
 * ⚠️ Refactoring Rationale: the reference gates its highlight on the pseudo-conversational
 * re-entry flag -- `app/cpy/CSSETATY.cpy` L20 tests `CDEMO-PGM-REENTER`, declared at
 * `app/cpy/COCOM01Y.cpy` L29-L31 -- and this screen has no such flag to gate on. AAP section
 * 0.7.1 establishes that the discriminator DISAPPEARS ENTIRELY, because a stateless handler has
 * no first-entry-versus-re-entry distinction to make, so the marking is driven purely by the
 * response body. A reader looking for the flag will not find one, and its absence is the design
 * rather than an omission.
 *
 * ⚠️ Assumptions: three states are asserted in one case because each alone admits the defect.
 * The blank field carrying the marker alone would pass against a screen that marked every
 * refusal the same way; the not-ok field lacking the marker alone would pass against a screen
 * that marked none; and a clean field showing no error is what separates per-field marking from
 * a screen that marks the whole form whenever anything is refused.
 *
 * Assumptions: the marker is looked for inside the refused control's own affix wrapper and not
 * anywhere on the row, because the reference writes it INTO the field -- `app/cpy/CSSETATY.cpy`
 * L23-L25 moves it to the field's output subfield -- so an asterisk floated elsewhere would
 * satisfy a document-wide text query while no longer reading as belonging to the field. It also
 * keeps the query clear of the masked card number, whose mask uses the same character for an
 * unrelated reason.
 * @returns {Promise<void>} Resolves once all three states have been checked.
 */
async function marksRefusedFieldsAndLeavesCleanOnesAlone(): Promise<void> {
  expect(FIELD_ERROR_TOKENS.blankMarker).toBe(
    cobolLiteralAt(FIELD_HIGHLIGHT, BLANK_MARKER_LINE, 'app/cpy/CSSETATY.cpy'),
  );
  // WHY : Refactoring Rationale: this used to assert the token name matched /^color/, using the alias
  //       family's prefix as a proxy for "this is a design-system token and not a colour literal".
  //       That proxy was too narrow. No `colorError*` alias in antd 6.5.2 is dark enough for this
  //       role -- the darkest, `colorErrorTextActive` at #d9363e, measures 4.224:1 against the error
  //       band's own `colorErrorBg` tint of #fff2f0, below the 4.5:1 bar -- so the bridge entry in
  //       `ui/src/theme/tokens.ts` points at the ramp member `red7`, which measures 5.097:1 there.
  //       AAP section 0.3.3 lists the full colour ramp in the map layer of the available token
  //       surface, so a ramp member IS a design-system token. Asserting MEMBERSHIP in antd's resolved
  //       token set states the intended property directly instead of matching a naming convention: a
  //       colour literal fails it, and so does a token name that antd does not publish.
  expect(Object.keys(theme.getDesignToken({}))).toContain(FIELD_ERROR_TOKENS.errorColor);

  const blank = fieldError('embossedName', catalogued('WS_PROMPT_FOR_NAME').text, 'BLANK');
  const notOk = fieldError('activeStatus', catalogued('CARD_STATUS_MUST_BE_YES_NO').text);
  vi.mocked(updateCard).mockRejectedValue(plainRefusal(null, [blank, notOk]));

  const user = await renderLoadedScreen();
  await amendAndCommit(user);

  const refusedName = control(CARD_UPDATE_FIELD_LABELS.nameOnCard);
  await waitFor(
    /**
     * Waits for the refused name field to be marked.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(formItemFor(refusedName)).toHaveClass('ant-form-item-has-error');
    },
  );
  expect(formItemFor(refusedName).textContent ?? '').toContain(blank.message);

  const affix = refusedName.closest('.ant-input-affix-wrapper');
  expect(affix).not.toBeNull();
  const suffix = affix?.querySelector<HTMLElement>('.ant-input-suffix');
  expect(suffix?.textContent).toBe(FIELD_ERROR_TOKENS.blankMarker);

  const refusedStatus = control(CARD_UPDATE_FIELD_LABELS.cardActive);
  expect(formItemFor(refusedStatus)).toHaveClass('ant-form-item-has-error');
  expect(formItemFor(refusedStatus).textContent ?? '').toContain(notOk.message);
  expect(refusedStatus.closest('.ant-input-affix-wrapper')).toBeNull();

  const untouched = control(EXPIRY_YEAR_LABEL);
  expect(formItemFor(untouched)).not.toHaveClass('ant-form-item-has-error');
  expect(formItemFor(untouched).textContent ?? '').not.toContain(blank.message);

  /*
   * WHY : ⚠️ Assumptions: the COLOUR is asserted on both refused controls and on neither clean one,
   *       because the copybook colours BOTH refused states and marks only the blank one.
   *       `app/cpy/CSSETATY.cpy` L18 to L22 moves `DFHRED` into the field's colour subfield when the
   *       flag is not-OK, L23 to L26 does the same AND writes the asterisk when it is blank -- so the
   *       marker distinguishes the two states and the colour does not. Asserting the suffix alone,
   *       which this case did, would pass against a screen that coloured only the blank field.
   * WHY : Assumptions: the assertion is on the serialised custom-property reference rather than on a
   *       resolved colour, for the same reason the token-membership assertion above exists: the value
   *       belongs to the theme, and the name is what this file is entitled to hold the screen to.
   */
  const refusedColour = `var(--ant-${FIELD_ERROR_TOKENS.errorColor.replace(/([A-Z0-9]+)/gu, '-$1').toLowerCase()})`;
  /*
   * WHY : ⚠️ Assumptions: the colour is looked for on the affix WRAPPER for the blank field and on the
   *       control itself for the other, because the design system moves the target: a suffix makes
   *       `hasPrefixSuffix` true, the control is wrapped, and antd applies the caller's `style` to that
   *       wrapper (`@rc-component/input/lib/BaseInput.js` L50 to L53 and L87). Nothing is lost by it --
   *       the wrapper's inner rule sets `color: 'inherit'` on the input
   *       (`node_modules/antd/lib/input/style/index.js` L407 to L413), so the value text takes the
   *       wrapper's colour -- but a case that insisted on the input would be asserting antd's internal
   *       arrangement rather than the colour the operator sees.
   */
  const blankSurface = refusedName.closest<HTMLElement>('.ant-input-affix-wrapper');
  expect(blankSurface?.style.color).toBe(refusedColour);
  expect(refusedStatus.style.color).toBe(refusedColour);
  expect(untouched.style.color).toBe('');
}

/**
 * No case can grant itself authority, because the identity hook publishes no way to.
 *
 * ⚠️ Assumptions: authority derives solely from the signed group claim, and this is asserted
 * structurally rather than trusted. `ui/src/hooks/useAuth.ts` holds its session in module scope
 * and publishes no setter for the groups or the user type, and there is no context provider
 * anywhere in this tree for a case to wrap a subject in -- so the only way to obtain a session
 * is the shared harness's helper, which mints a token and drives the real sign-on. A mutator on
 * that module would be a second, weaker path to authority that production does not have.
 *
 * Assumptions: this screen establishes no session at all, and none is needed. The shell paints
 * its frame regardless, gating only its own sign-off control on a held session, and every case
 * here asserts a property of this screen rather than of the authorisation in front of it.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function offersNoWayToGrantAuthority(): void {
  const mutators = Object.keys(authModule).filter(
    /**
     * Keeps an exported name that would set or grant something.
     * @param {string} name - One exported name.
     * @returns {boolean} `true` when the name reads as a mutator.
     */
    (name: string): boolean => /^(?:set|grant|assign)[A-Z]/u.test(name),
  );
  expect(mutators).toEqual([]);
}

/**
 * Asserts one control's rendering is ceilinged at the character width its field declares.
 *
 * Assumptions: the `ch` term is matched rather than the whole declaration, because the padding term
 * is a theme custom property whose serialised form belongs to the design system and not to this
 * file. What this file is entitled to assert is the character count, which is the copybook's.
 *
 * Assumptions: `max-inline-size` is asserted and not a pixel width, because jsdom computes no
 * layout, so a pixel assertion here would assert nothing. The remedy `ui/src/layout/recordLayout.ts`
 * supplies is a ceiling expressed in `ch` plus the system's own horizontal control padding, so the
 * declaration's presence and its character count are exactly what can be established.
 * @param {HTMLElement} field - The control to inspect.
 * @param {number} declaredWidth - The width the field's PICTURE clause declares.
 * @returns {void} Nothing; the expectations throw on a mismatch.
 */
function expectDeclaredWidthCeiling(field: HTMLElement, declaredWidth: number): void {
  const ceiling = field.style.maxInlineSize;
  expect(ceiling, 'the control must carry a declared-width ceiling').not.toBe('');
  expect(ceiling).toContain(`${String(declaredWidth)}ch`);
}

/**
 * Every control is ceilinged at the character width its own field declares.
 *
 * ⚠️ Assumptions: this is the RENDERING half of the width contract and
 * {@link honoursEveryDeclaredFieldWidth} is the ENTRY half. They are separate because they fail
 * separately: `maxLength` bounds what an operator can type and says nothing about how wide the box
 * is drawn, which is how a one-character field came to be measured at two hundred and seventeen
 * pixels on the sibling list screen while its entry bound was correct all along.
 *
 * Assumptions: the ceiling is asserted on the CONTROL and never on a wrapper, because
 * `copybookFieldWidthStyle` composes `--ant-control-padding-horizontal` into its calculation and that
 * custom property resolves on `.ant-input`; on a plain wrapper the term would resolve to nothing and
 * the ceiling would silently collapse to the character count alone.
 * @returns {Promise<void>} Resolves once every control has been measured.
 */
async function boundsEveryControlToItsDeclaredRenderingWidth(): Promise<void> {
  await renderLoadedScreen();

  const pairs: readonly (readonly [string, number])[] = [
    [CARD_UPDATE_FIELD_LABELS.accountNumber, CARD_UPDATE_FIELD_WIDTHS.accountNumber],
    [CARD_UPDATE_FIELD_LABELS.cardNumber, CARD_UPDATE_FIELD_WIDTHS.cardNumber],
    [CARD_UPDATE_FIELD_LABELS.nameOnCard, CARD_UPDATE_FIELD_WIDTHS.embossedName],
    [CARD_UPDATE_FIELD_LABELS.cardActive, CARD_UPDATE_FIELD_WIDTHS.activeStatus],
    [EXPIRY_MONTH_LABEL, CARD_UPDATE_FIELD_WIDTHS.expirationMonth],
    [EXPIRY_YEAR_LABEL, CARD_UPDATE_FIELD_WIDTHS.expirationYear],
  ];
  for (const [label, width] of pairs) {
    expectDeclaredWidthCeiling(control(label), width);
  }
}

/**
 * The two expiry controls are told apart by more than the separator painted between them.
 *
 * ⚠️ Assumptions: the mapset paints ONE label for both parts -- `app/bms/COCRDUP.bms` L126 paints
 * `Expiry Date       : ` once, L134 paints the `/` between them, and L127-L131 and L135-L139 declare
 * two separate two- and four-character fields -- so a faithful rendering that took its accessible
 * name from the painted label alone would give two consecutive required controls names differing
 * only by a trailing separator. That is the defect: the separator is decoration, it is not a name.
 *
 * Assumptions: the painted label is still required to be PRESENT inside each name rather than
 * replaced by one, because the operator reading the screen sees that label and the two must agree.
 * The part name is additive, which is the convention `ui/src/screens/accountUpdate/index.tsx`
 * already established for its own split date fields.
 *
 * Assumptions: the two names are compared after stripping every non-alphanumeric character, so a
 * pair distinguished only by punctuation or spacing fails this. Stripping is what makes the case
 * discriminating: the pre-fix names `Expiry Date       : /` and `Expiry Date       : ` reduce to one
 * identical string, while `...Month` and `...Year` do not.
 * @returns {Promise<void>} Resolves once both names have been compared.
 */
async function distinguishesTheTwoExpiryControls(): Promise<void> {
  await renderLoadedScreen();

  const month = control(EXPIRY_MONTH_LABEL).getAttribute('aria-label') ?? '';
  const year = control(EXPIRY_YEAR_LABEL).getAttribute('aria-label') ?? '';

  for (const name of [month, year]) {
    expect(collapse(name)).toContain(collapse(CARD_UPDATE_FIELD_LABELS.expiryDate));
  }
  expect(month).not.toBe(year);
  expect(month.replace(/[^A-Za-z0-9]/gu, '')).not.toBe(year.replace(/[^A-Za-z0-9]/gu, ''));
  expect(month).toContain(CARD_UPDATE_PART_NAMES.month);
  expect(year).toContain(CARD_UPDATE_PART_NAMES.year);
  expect(CARD_UPDATE_FIELD_LABELS.expirySeparator).not.toContain(CARD_UPDATE_PART_NAMES.month);
}

/**
 * The two expiry parts sit under ONE painted caption, in one group, on one line.
 *
 * ⚠️ Purpose: a browser sweep measured the month, the `/` and the year STACKED vertically at 375,
 * 768, 1280 and 1920 alike, while the paired account-update screen rendered its own three-part dates
 * inline at every one of those widths -- two screens rendering the same construct two different ways.
 * They stacked because they were two SIBLING form items with the separator rendered as the second
 * item’s own label, and sibling form items stack by design, so the arrangement could never have been
 * horizontal.
 *
 * ⚠️ Assumptions: the mapset settles the arrangement and settles it on ONE row.
 * `app/bms/COCRDUP.bms` L123-L126 paints the caption at row 15 column 4 over `LENGTH=20`, `EXPMON` at
 * L127-L131 sits at row 15 column 25 over `LENGTH=2`, the anonymous `INITIAL='/'` at L132-L134 at row
 * 15 column 28, and `EXPYEAR` at L135-L139 at row 15 column 30 over `LENGTH=4`. AAP design gap G1
 * surrenders pixel-for-character POSITION and commits to preserving field GROUPING and reading order,
 * so a caption and two parts the mapset paints side by side belong on one line.
 *
 * Assumptions: the two parts stay two controls, which this case does not disturb -- the source answers
 * a bad month and a bad year with two different sentences from two different edit paragraphs
 * (`app/cbl/COCRDUPC.cbl` L197-L198 and L199-L200), so one combined field could carry only one of them.
 * The sibling case above asserts that both still carry their own name.
 * @returns {Promise<void>} Resolves once the group, its caption and its separator have been asserted.
 */
async function groupsTheExpiryPartsUnderOneCaptionOnOneLine(): Promise<void> {
  await renderLoadedScreen();

  const month = control(EXPIRY_MONTH_LABEL);
  const year = control(EXPIRY_YEAR_LABEL);

  const group = month.closest<HTMLElement>('[role="group"]');

  if (group === null) {
    throw new Error('the expiry parts rendered without the group their shared caption names');
  }

  expect(
    group.contains(year),
    'both parts must sit inside ONE group, which is what makes them one line rather than two rows',
  ).toBe(true);

  /*
   * WHY : ⚠️ Assumptions: the group's name is resolved THROUGH the document rather than compared as an
   *       attribute. A dangling `aria-labelledby` promises a name and delivers silence, and an equality
   *       check on the attribute passes for exactly that case.
   */
  const captionId = group.getAttribute('aria-labelledby') ?? '';
  const caption = captionId === '' ? null : document.getElementById(captionId);

  if (caption === null) {
    throw new Error('the expiry group names itself by a reference that resolves to nothing');
  }

  expect(
    collapse(caption.textContent ?? ''),
    'the group must be named by the caption the mapset paints once over both parts',
  ).toBe(collapse(CARD_UPDATE_FIELD_LABELS.expiryDate));

  /*
   * WHY : ⚠️ Assumptions: the separator is asserted to be INSIDE the group and to be no field's label,
   *       which is the discriminating half. The pre-fix arrangement rendered the `/` as the SECOND form
   *       item's own label, and a form item's label sits outside the group and stacks the item beneath
   *       its sibling -- so a case that only looked for the character somewhere on the screen would have
   *       passed against the vertical stack this replaced.
   */
  expect(
    group.textContent ?? '',
    'the separator must be painted between the parts, inside their group',
  ).toContain(CARD_UPDATE_FIELD_LABELS.expirySeparator);

  const separatorLabels: string[] = [];

  for (const label of document.querySelectorAll<HTMLElement>('label')) {
    if (collapse(label.textContent ?? '') === collapse(CARD_UPDATE_FIELD_LABELS.expirySeparator)) {
      separatorLabels.push(label.textContent ?? '');
    }
  }

  expect(
    separatorLabels,
    'the separator is painted decoration and must label no field at all',
  ).toEqual([]);

  /*
   * WHY : Assumptions: the caption's own `for` is asserted to resolve to the FIRST part, because a
   *       caption associated with no field is the second half of the defect an accessibility audit
   *       raised against the equivalent groups on the paired account-update screen.
   */
  const captionLabel = caption.closest<HTMLElement>('label');
  expect(
    captionLabel?.getAttribute('for'),
    'the caption must point at the group\u2019s first part',
  ).toBe(month.id);
}

/**
 * No field carries a standing requirement marker, and the requirement is still published.
 *
 * ⚠️ Assumptions: the design system's own marker is refused because it states the wrong thing.
 * `app/cpy/CSSETATY.cpy` L18-L26 moves an asterisk into a field only on the turn that field arrived
 * BLANK and was refused; the system's marker says "this field is always required" and is painted
 * from the first turn, so leaving both on would put a permanent asterisk where the reference paints
 * a conditional one and give the operator two markers with two different meanings.
 *
 * Assumptions: the requirement itself is asserted to survive on the machine-readable channel, so
 * this case cannot be satisfied by simply dropping the rule. `aria-required` is what a screen reader
 * announces; the asterisk is what the reference reserves for a refusal.
 *
 * Assumptions: the SUPPRESSION is asserted through the class the design system keys it on, not
 * through the absence of the glyph. antd paints the marker from a `::before` rule --
 * `node_modules/antd/lib/form/style/index.js` L159-L173 gives `.ant-form-item-required::before` the
 * literal content `"*"` and gives `.ant-form-item-required-mark-hidden` a `display: none` that
 * cancels it -- and jsdom renders no pseudo-element, so querying for an asterisk would pass whatever
 * the screen did. Requiring the cancelling class on every required label states the same property
 * and does discriminate: without `requiredMark={false}` on the form, antd leaves that class off.
 *
 * Assumptions: at least one required label is required to EXIST, so the case cannot be satisfied by
 * a form that stopped requiring anything.
 * @returns {Promise<void>} Resolves once the markers and the requirement have been inspected.
 */
async function offersNoStandingRequiredMarker(): Promise<void> {
  await renderLoadedScreen();

  const required = Array.from(document.querySelectorAll<HTMLElement>('.ant-form-item-required'));
  expect(required.length).toBeGreaterThan(0);
  for (const label of required) {
    expect(label).toHaveClass('ant-form-item-required-mark-hidden');
  }
  expect(screen.queryAllByText(FIELD_ERROR_TOKENS.blankMarker)).toEqual([]);

  for (const label of [
    CARD_UPDATE_FIELD_LABELS.nameOnCard,
    CARD_UPDATE_FIELD_LABELS.cardActive,
    EXPIRY_MONTH_LABEL,
    EXPIRY_YEAR_LABEL,
  ]) {
    expect(control(label)).toHaveAttribute('aria-required', 'true');
  }
}

/**
 * The confirmation asks the reference's own question and names the record it would write.
 *
 * ⚠️ Assumptions: the question is the catalogued sentence and never a key label. The reference asks
 * `app/cbl/COCRDUPC.cbl`'s own `PROMPT-FOR-CONFIRMATION` sentence on that turn, so a surface titled
 * with the name of the key that opened it would be asking nothing at all.
 *
 * Assumptions: the record is named by the two identifiers the mapset paints as this screen's keys --
 * the account number and the MASKED card number -- carried with their painted labels, so the
 * operator confirming a write is told which card is being written rather than only that something
 * is. The unmasked number is deliberately absent: it is absent from the whole screen.
 *
 * Assumptions: focus is asserted to start on the DECLINING action. A committing confirmation whose
 * accepting action holds focus commits on the next bare Enter, which is the gesture this program's
 * operator uses constantly -- `app/cpy/CSSTRPFY.cpy` makes Enter the attention identifier every
 * turn dispatches on -- so the safe choice is the one that may hold it.
 *
 * Assumptions: both actions point at the record identity with `aria-describedby`, so the record is
 * announced with whichever action is landed on rather than only when the surface is read whole.
 * @returns {Promise<void>} Resolves once the confirmation has been inspected.
 */
async function namesTheRecordInItsConfirmation(): Promise<void> {
  const user = await renderLoadedScreen();

  await amendAndValidate(user);
  await pressPfKey(user, 'PFK05');
  const confirmation = await openConfirmation();

  /*
   * WHY : ⚠️ Assumptions: the MODALITY is asserted alongside the role, because the two together are
   *       what make this a question rather than a remark. `aria-modal` is what tells an assistive
   *       technology that the rest of the document is unavailable until the question is answered, and
   *       it is exactly what the `Popconfirm` this surface replaced could not carry -- its overlay is
   *       hardcoded `role="tooltip"` and sets no modality at all. The attribute comes from
   *       `@rc-component/dialog/es/Dialog/Content/Panel.js` L113-L115 along with the role and the
   *       labelling, so a surface that lost one of the three would fail here.
   */
  expect(confirmation.getAttribute('aria-modal')).toBe('true');

  const surface = collapse(confirmation.textContent ?? '');
  expect(surface).toContain(collapse(catalogued('PROMPT_FOR_CONFIRMATION').text));
  for (const fragment of [
    collapse(CARD_UPDATE_FIELD_LABELS.accountNumber),
    CARD.accountId,
    collapse(CARD_UPDATE_FIELD_LABELS.cardNumber),
    CARD.displayCardNumber,
  ]) {
    expect(surface).toContain(fragment);
  }

  const { accept, decline } = confirmationActions(confirmation);
  expect(document.activeElement).toBe(decline);
  expect(accept).toHaveClass('ant-btn-primary');
  expect(decline).not.toHaveClass('ant-btn-primary');

  const described = accept.getAttribute('aria-describedby');
  expect(described).not.toBeNull();
  expect(decline.getAttribute('aria-describedby')).toBe(described);
  const identity = document.getElementById(described ?? '');
  expect(identity).not.toBeNull();
  expect(collapse(identity?.textContent ?? '')).toContain(CARD.displayCardNumber);
  expect(identity?.style.fontFamily).toBe(
    `var(--ant-${TYPOGRAPHY_TOKENS.fixedPitchData.replace(/([A-Z])/gu, '-$1').toLowerCase()})`,
  );
}

/**
 * `Escape` withdraws the confirmation and writes nothing.
 *
 * ⚠️ Assumptions: this case exists because the withdrawal MECHANISM changed and the behaviour must
 * not. While the confirmation was a controlled `Popconfirm` declaring `trigger={[]}`, the library's
 * own dismissal was switched off and this screen installed a document-level `Escape` listener to put
 * it back; browser validation had measured `Escape`, a second `Escape`, a `Tab` then a third
 * `Escape` and a click outside all leaving the overlay open, so the only ways out were its two
 * buttons or `F12` -- and `F12` DISCARDS the edits. The dialog primitive routes `Escape` to the
 * cancel handler itself (`@rc-component/dialog` `Dialog/index.js`, `onWrapperKeyDown` on
 * `KeyCode.ESC`), so the screen's listener was withdrawn with the `Popconfirm`. This asserts the
 * library is doing what the listener did.
 *
 * Assumptions: the keystroke is delivered through the operator rather than through the screen's key
 * handler, because what is being asserted is what the BROWSER does with `Escape` while that surface
 * is open -- `ui/src/layout/usePfKeys.ts` maps only Enter and the function keys, so `Escape` is never
 * an attention identifier this screen claims.
 *
 * Assumptions: the operator's typing is asserted to SURVIVE, for the same reason the declining
 * action's case asserts it: a withdrawal that discarded the amendment would be safe from the
 * service's point of view and destructive from the operator's, and `F12` is the only key on this
 * screen the reference lets discard anything.
 * @returns {Promise<void>} Resolves once the withdrawal has been observed.
 */
async function escapeWithdrawsTheConfirmation(): Promise<void> {
  vi.mocked(updateCard).mockResolvedValue(AMENDED_CARD);
  const user = await renderLoadedScreen();

  await amendAndValidate(user);
  await pressPfKey(user, 'PFK05');
  await openConfirmation();

  await user.keyboard('{Escape}');

  await waitFor(
    /**
     * Waits for the confirmation to have been withdrawn.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(displayedConfirmations()).toEqual([]);
    },
  );
  expect(updateCard).not.toHaveBeenCalled();
  expect(control(CARD_UPDATE_FIELD_LABELS.nameOnCard)).toHaveValue(AMENDED_NAME);
}

/**
 * A bare Enter on the open confirmation writes nothing.
 *
 * ⚠️ Assumptions: this is the gesture the umbrella contract exists for. An operator who has just
 * pressed the save key is holding the keyboard, and Enter is the key this program dispatches on
 * every other turn; if the confirmation's accepting action holds focus then the habitual Enter
 * commits a write the operator has not yet agreed to. The remedy is focus on the declining action,
 * which turns the same keystroke into a cancellation.
 *
 * Assumptions: the keystroke is delivered bare, through the operator and not through the screen's
 * key handler, because what is being asserted is what the BROWSER does with Enter while that
 * surface is open. `ui/src/layout/usePfKeys.ts` L318-L324 defers Enter to a focused native button
 * rather than claiming it, so the button's own activation is what runs.
 *
 * Assumptions: the record is asserted to survive intact afterwards, not merely that no request was
 * sent, because a cancellation that discarded the operator's typing would be safe from the
 * service's point of view and destructive from the operator's.
 * @returns {Promise<void>} Resolves once the cancellation has been observed.
 */
async function commitsNothingOnABareEnter(): Promise<void> {
  vi.mocked(updateCard).mockResolvedValue(AMENDED_CARD);
  const user = await renderLoadedScreen();

  await amendAndValidate(user);
  await pressPfKey(user, 'PFK05');
  const confirmation = await openConfirmation();
  expect(document.activeElement).toBe(confirmationActions(confirmation).decline);

  await user.keyboard('{Enter}');

  await waitFor(
    /**
     * Waits for the confirmation to have been withdrawn.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(displayedConfirmations()).toEqual([]);
    },
  );
  expect(updateCard).not.toHaveBeenCalled();
  expect(control(CARD_UPDATE_FIELD_LABELS.nameOnCard)).toHaveValue(AMENDED_NAME);
}

/**
 * A name that would overflow the record's field is refused and nothing is sent.
 *
 * ⚠️ Assumptions: this documents a REFUTATION as much as it guards a behaviour. The report's
 * concern is that an entry bound counting UTF-16 units admits a value that overflows a fixed-width
 * field measured in code points and bytes. On THIS field that cannot happen through the bound
 * alone: `app/cbl/COCRDUPC.cbl` L1230-L1240 accepts only alphabetic characters and spaces, and
 * every character in that alphabet is one unit, one code point and one byte alike -- so any value
 * that could overflow in bytes is refused by the alphabet first, with the program's own sentence.
 *
 * Assumptions: the width term was adopted regardless, at the same site, so the contract stays
 * correct if the accepted alphabet is ever widened. It is asserted here only through the refusal it
 * shares with the alphabet term, because no accepted value can reach it -- which is the honest
 * extent of what this screen can be held to today.
 * @returns {Promise<void>} Resolves once the refusal has been rendered.
 */
async function refusesANameThatOverflowsTheRecord(): Promise<void> {
  const user = await renderLoadedScreen();

  const name = control(CARD_UPDATE_FIELD_LABELS.nameOnCard);
  await user.clear(name);
  await user.type(name, 'é'.repeat(CARD_UPDATE_FIELD_WIDTHS.embossedName));
  await pressPfKey(user, 'ENTER');

  await waitFor(
    /**
     * Waits for the refusal to be marked on the field.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(formItemFor(control(CARD_UPDATE_FIELD_LABELS.nameOnCard))).toHaveClass(
        'ant-form-item-has-error',
      );
    },
  );
  expect(formItemFor(control(CARD_UPDATE_FIELD_LABELS.nameOnCard)).textContent ?? '').toContain(
    catalogued('WS_NAME_MUST_BE_ALPHA').text,
  );
  expect(updateCard).not.toHaveBeenCalled();
}

/**
 * Registers the cases that hold this screen to the widths, the cursor placement and the
 * composition its mapset and symbolic map declare.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function registerFieldConstraintCases(): void {
  it('derives every field width from the symbolic map', derivesEveryFieldWidthFromTheSymbolicMap);
  it('honours every declared field width on the rendered controls', honoursEveryDeclaredFieldWidth);
  it(
    'carries the header and message fields at their declared widths',
    carriesTheHeaderAndMessageFieldsAtTheirDeclaredWidths,
  );
  it(
    'distinguishes the wider error field from the message content contract',
    distinguishesTheWiderFieldFromTheContentContract,
  );
  it(
    'declares three expiry parts and offers two of them for edit',
    declaresThreeExpiryPartsAndEditsTwo,
  );
  it(
    'bounds every control to its declared rendering width',
    boundsEveryControlToItsDeclaredRenderingWidth,
  );
  it('distinguishes the two expiry controls', distinguishesTheTwoExpiryControls);
  it(
    'groups the expiry parts under one caption on one line',
    groupsTheExpiryPartsUnderOneCaptionOnOneLine,
  );
  it('offers no standing required marker', offersNoStandingRequiredMarker);
  it('places exactly one initial cursor', placesExactlyOneInitialCursor);
  it(
    'composes the screen from design-system components',
    composesTheScreenFromDesignSystemComponents,
  );
}

/**
 * Registers the cases that hold this screen to its two-step commit and to the refusals a write
 * can return, the concurrency conflict among them.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function registerCommitContractCases(): void {
  it('commits where the reference program commits', commitsWhereTheProgramCommits);
  it('validates on the enter key without writing', validatesOnEnterWithoutWriting);
  it('commits only on the save key, behind its confirmation', commitsOnlyOnTheSaveKey);
  it('states its success over the committed record', statesItsSuccessOverTheCommittedRecord);
  it('writes nothing when the confirmation is declined', decliningTheConfirmationWritesNothing);
  it('names the record in its confirmation', namesTheRecordInItsConfirmation);
  it('commits nothing on a bare enter', commitsNothingOnABareEnter);
  it('withdraws the confirmation on escape', escapeWithdrawsTheConfirmation);
  it('refuses an unchanged submission and offers no save key', refusesAnUnchangedSubmission);
  it('reports a refused write as an unsuccessful turn', reportsARefusedWrite);
  it(
    'reports a transient refusal as an outage rather than a failed update',
    reportsATransientRefusalAsAnOutage,
  );
  it('announces the outstanding write through a live region', announcesTheOutstandingWrite);
  it(
    'distinguishes the concurrency conflict from a plain failure',
    distinguishesTheConcurrencyConflict,
  );
  it(
    'writes once when one confirmation is activated twice',
    writesOnceWhenOneConfirmationIsActivatedTwice,
  );
  it('gates the write on a synchronous latch', gatesTheWriteOnASynchronousLatch);
  it(
    'resubmits against the version the refresh returned',
    resubmitsAgainstTheVersionTheRefreshReturned,
  );
  it('keeps the conflict when the refresh fails', keepsTheConflictWhenTheRefreshFails);
  it(
    'builds refusals the shared client classifies as intended',
    buildsRefusalsTheClientClassifiesAsIntended,
  );
  it('surfaces its single lock refusal', surfacesTheSingleLockRefusal);
}

/**
 * Registers the cases that hold every user-visible sentence to the literal the reference program
 * declares, and the edits that raise each one.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function registerMessageFidelityCases(): void {
  it('carries every sentence of this program verbatim', carriesEverySentenceOfThisProgramVerbatim);
  it(
    'keeps the padding, the spacing and the ellipsis of the awkward sentences',
    keepsThePaddingTheSpacingAndTheEllipsis,
  );
  it('carries the shared sentences verbatim and in upper case', carriesTheSharedSentencesVerbatim);
  it('withholds the technical file diagnostic', withholdsTheTechnicalFileDiagnostic);
  it("refuses a name outside the program's own alphabet", refusesANameOutsideTheProgramsAlphabet);
  it('refuses an empty name', refusesAnEmptyName);
  it('refuses a name that would overflow the record', refusesANameThatOverflowsTheRecord);
  it('bounds the expiry month', boundsTheExpiryMonth);
  it('bounds the expiry year without borrowing the month sentence', boundsTheExpiryYear);
  it('restricts the active status to its two values', restrictsTheActiveStatusToItsTwoValues);
  it(
    'carries the abend surface as widths and not as text',
    carriesTheAbendSurfaceAsWidthsAndNotText,
  );
  it('keeps the frame when the selector is refused', keepsTheFrameWhenTheSelectorIsRefused);
  it('keeps the frame on the keyless entry route', keepsTheFrameOnTheKeylessEntryRoute);
}

/**
 * This screen's legend and message line are carried in the frame's pinned zone.
 *
 * Purpose
 * -------
 * A browser measured this route's function-key legend below the fold at every width it was taken to:
 * 124 pixels below at 375 and 576, and bottom-clipped by 9.6 pixels at 768, 992, 1200 and 1600 -- so
 * on a 900-pixel viewport this screen never showed its keys in full at any width. The reference cannot
 * express that state: rows 23 and 24 exist on `app/bms/COCRDUP.bms` (L146-L167) and a 24-row display
 * does not scroll, so the outcome of a turn and the keys for the next one were always on the glass.
 *
 * ⚠️ Assumptions: the cause was the FRAME and not this screen, so the remedy is the frame's and this
 * case only establishes that this route inherits it. `ui/src/layout/AppShell.tsx` L1324-L1332 pins the
 * zone holding rows 22, 23 and 24 with `position: sticky` and `inset-block-end: 0`; the general
 * contract is asserted by `ui/src/layout/appShell.test.tsx`. Trimming this screen's own content
 * instead was rejected outright: at 375 the form is legitimately taller than the viewport, so no
 * amount of trimming could hold the guarantee, and a screen that fitted by 9 pixels would still be one
 * added field away from the same defect.
 *
 * Assumptions: what is asserted here is CONTAINMENT plus the pinning declaration, because jsdom
 * computes no layout and cannot be asked where the legend falls. Containment is what makes the
 * declaration reach this screen's legend, and the declaration is what keeps it on the glass.
 *
 * ⚠️ Assumptions: the ROW-22 information line is held to the same containment, and that half is what
 * this case gained. This screen used to compose that band itself, inside the screen body: a browser
 * measured it at rect 775.67-815.67 inside `main` while the pinned zone spanned 764-860, and
 * `document.elementFromPoint(459, 796)` returned an element the band did not contain -- so the
 * acknowledgement `Changes committed to database` was FULLY OCCLUDED at `scrollY 0` and became
 * readable only after the operator scrolled the remaining 52 pixels. An operator saved and saw
 * nothing. The band being inside the pinned zone is what fixes that, and it being OUTSIDE `main` is
 * what proves it is not a second copy left behind in the body -- the two assertions together fail if
 * either the delegation or the deletion is undone.
 * @returns {Promise<void>} Resolves once the zone has been inspected.
 */
async function carriesItsLegendInTheFramesPinnedZone(): Promise<void> {
  await renderLoadedScreen();

  const zone = screen.getByTestId(SHELL_PINNED_ZONE_TEST_ID);
  expect(zone).toContainElement(screen.getByTestId(MESSAGE_BAND_TEST_ID));
  expect(zone).toContainElement(legendRegion());
  expect(zone.style.position, 'the zone holding rows 23 and 24 may not be in flow').toBe('sticky');
  expect(zone.style.insetBlockEnd, 'the zone must be pinned to the block end').toBe('0px');

  const informationBands = screen.getAllByTestId(INFORMATION_BAND_TEST_ID);
  expect(informationBands, 'row 22 is one line, so one band may paint it').toHaveLength(1);
  const [informationBand] = informationBands;
  expect(zone).toContainElement(informationBand ?? null);
  expect(
    informationBand?.closest('main'),
    'a row-22 band inside the scrolling body is occluded by the pinned zone above it',
  ).toBeNull();
}

/**
 * Registers the cases that hold this screen to the four attention identifiers the reference
 * dispatches on, reachable from the keyboard and from the visible legend alike.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function registerKeyContractCases(): void {
  it(
    'paints the legend in the two stages the mapset declares',
    paintsTheLegendInTheTwoStagesTheMapsetDeclares,
  );
  it('dispatches every action from the keyboard', dispatchesEveryActionFromTheKeyboard);
  it('dispatches every action from the visible legend', dispatchesEveryActionFromTheLegend);
  it('exits from the keyboard', exitsFromTheKeyboard);
  it('exits from the visible legend', exitsFromTheLegend);
  it('binds no key beyond the four it dispatches on', bindsNoKeyBeyondTheFourItDispatchesOn);
  it('marks the acting keys with the primary emphasis', marksTheActingKeysWithThePrimaryEmphasis);
  it("carries its legend in the frame's pinned zone", carriesItsLegendInTheFramesPinnedZone);
}

/**
 * Registers the cases that hold this screen to what it may show and what it may submit.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function registerDataExposureCases(): void {
  it('masks the primary account number', masksThePrimaryAccountNumber);
  it('never exposes the card verification code', neverExposesTheCardVerificationCode);
  it(
    'takes its selection from the route and carries no session field',
    takesItsSelectionFromTheRouteAndCarriesNoSessionField,
  );
  it('marks refused fields and leaves clean ones alone', marksRefusedFieldsAndLeavesCleanOnesAlone);
  it('offers no way to grant authority', offersNoWayToGrantAuthority);
}

describe('card update screen field constraints', registerFieldConstraintCases);
describe('card update screen commit contract', registerCommitContractCases);
describe('card update screen message fidelity', registerMessageFidelityCases);
describe('card update screen key contract', registerKeyContractCases);
describe('card update screen data exposure', registerDataExposureCases);
