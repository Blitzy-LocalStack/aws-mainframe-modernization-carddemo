/**
 * @file Contract tests for the message catalogue's width and Unicode helpers.
 *
 * Purpose: two properties of this module are invisible in review and expensive in production. The
 * first is that a declared field width means three different numbers once operator-supplied text
 * leaves ASCII — code units, code points and UTF-8 bytes — and the platform's own `maxLength`
 * counts the one that matches neither the database column nor the fixed-width record. The second is
 * that a sentence this application AUTHORS, as opposed to one it transcribes from `app/**`, still
 * has to fit the message field the service publishes it through; a sentence that overruns is
 * truncated at the band with no indication that anything was lost.
 *
 * Assumptions: the transcribed catalogue is deliberately NOT length-checked here. Its literals are
 * verbatim under the migration's Rule T8 — including the ones carrying a missing space, a double
 * space or trailing spaces — so a bound that failed on one of them would be asking for the wrong
 * fix. Only strings this application composed itself are in scope, and they are enumerated
 * explicitly rather than discovered, so adding one is a deliberate act.
 */

import { describe, expect, it } from 'vitest';

import {
  AUTHORED_OPERATOR_SENTENCES,
  MESSAGE_BAND,
  PERSISTENT_FAILURE_REPORT_IT,
  REQUEST_IN_PROGRESS,
  SIGN_ON_NEW_PASSWORD_REQUIRED,
  SIGN_ON_SESSION_REQUIRED,
  TRANSIENT_FAILURE_TRY_AGAIN,
  codePointLength,
  fitsDeclaredWidth,
  normaliseForWire,
  padToDeclaredWidth,
  utf8ByteLength,
} from './messages';

/**
 * The string the audit measured, carrying two astral characters.
 *
 * Assumptions: kept as a module constant so every case measures the same value the audit did rather
 * than a paraphrase of it. Sixteen characters; the two emoji are one code point each and two code
 * units each, so it is eighteen code units.
 */
const AUDITED_ASTRAL_VALUE = 'Ünïcödé Émoji 🎉🏦';

/*
 * WHY : Assumptions: both constants are annotated `string` rather than left to inference. TypeScript
 *       otherwise narrows each to its own literal TYPE and reports the inequality assertion below as
 *       a comparison between two types with no overlap -- which is true of the types and is exactly
 *       the runtime fact being asserted, so the annotation keeps the check rather than deleting it.
 */

/** The same accented letter composed as one code point. */
const COMPOSED_ACCENT: string = 'Jos\u00E9';

/** The same accented letter decomposed as a base letter plus a combining acute. */
const DECOMPOSED_ACCENT: string = 'Jose\u0301';

/**
 * A code-point count is what a declared width limits, and it is not the platform's count.
 *
 * Purpose: this is the measurement V255 and V101 rest on. `String.prototype.length` reports eighteen
 * for the audited value because each emoji costs two code units, so a control carrying
 * `maxLength={20}` on a twenty-position field stops accepting input two characters early — and
 * would stop ten characters early on a value made entirely of emoji. The helper reports sixteen,
 * which is what the field actually holds.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function countsCodePointsRatherThanCodeUnits(): void {
  expect(
    AUDITED_ASTRAL_VALUE.length,
    'the platform must still count code units, or the defect being corrected has moved',
  ).toBe(18);
  expect(codePointLength(AUDITED_ASTRAL_VALUE), 'the helper must count characters').toBe(16);
  expect(
    codePointLength(AUDITED_ASTRAL_VALUE),
    'the two measures must disagree, which is the whole reason the helper exists',
  ).toBeLessThan(AUDITED_ASTRAL_VALUE.length);
}

/**
 * An emoji costs two code units and four bytes, so neither count substitutes for the other.
 *
 * Purpose: the byte count is the measure a `PIC X(n)` record position answers to, and it is the one
 * that usually binds. The audited value is sixteen characters, eighteen code units and — because
 * each accented letter costs two bytes and each emoji four — well over twenty bytes, so it does not
 * fit a twenty-position field even though it is only sixteen characters long.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function countsWireBytesSeparatelyFromCharacters(): void {
  expect(utf8ByteLength('ASCII'), 'ASCII must cost one byte per character').toBe(5);
  expect(utf8ByteLength('\u00E9'), 'a composed accent must cost two bytes').toBe(2);
  expect(utf8ByteLength('e\u0301'), 'a decomposed accent must cost three').toBe(3);
  expect(utf8ByteLength('\u{1F389}'), 'an astral character must cost four').toBe(4);
  expect(
    utf8ByteLength(AUDITED_ASTRAL_VALUE),
    'the audited value must exceed its own character count in bytes',
  ).toBeGreaterThan(codePointLength(AUDITED_ASTRAL_VALUE));
}

/**
 * The two Unicode forms of one name converge on the wire and are distinct before it.
 *
 * Purpose: this is the second half of V255. Both spellings render identically, both round-trip
 * through this application unchanged today, and they differ in code-point count and in byte count —
 * so on a fixed-width record they are two different values that no operator can tell apart.
 * Composing on the way out makes them one value.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function convergesTheTwoCanonicalFormsOnTheWire(): void {
  expect(
    COMPOSED_ACCENT === DECOMPOSED_ACCENT,
    'the two forms must be distinct before normalisation, or the hazard has gone away',
  ).toBe(false);
  expect(codePointLength(COMPOSED_ACCENT), 'the composed form must be four characters').toBe(4);
  expect(codePointLength(DECOMPOSED_ACCENT), 'the decomposed form must be five').toBe(5);
  expect(
    normaliseForWire(DECOMPOSED_ACCENT),
    'normalisation must converge the decomposed form onto the composed one',
  ).toBe(COMPOSED_ACCENT);
  expect(normaliseForWire(COMPOSED_ACCENT), 'normalisation must be idempotent').toBe(
    COMPOSED_ACCENT,
  );
  expect(
    utf8ByteLength(normaliseForWire(DECOMPOSED_ACCENT)),
    'the normalised forms must occupy the same number of record positions',
  ).toBe(utf8ByteLength(normaliseForWire(COMPOSED_ACCENT)));
}

/**
 * Normalisation is canonical and therefore never folds a character into a different one.
 *
 * Purpose: NFC was chosen over NFKC, and the difference is that NFKC rewrites what the operator
 * typed — a full-width digit becomes an ASCII digit, a ligature becomes its letters. This pins the
 * choice: a compatibility character must survive, because silently rewriting an operator's input is
 * a behaviour this application does not have and should not acquire.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function leavesCompatibilityCharactersAlone(): void {
  expect(normaliseForWire('\uFF11'), 'a full-width digit must not be folded to ASCII').toBe(
    '\uFF11',
  );
  expect(normaliseForWire('\uFB01'), 'a ligature must not be decomposed into its letters').toBe(
    '\uFB01',
  );
}

/**
 * The width check requires the value to fit in characters AND in bytes.
 *
 * Purpose: this is the validator a form must refuse on, and the case that matters is the value that
 * satisfies one measure and fails the other. Ten composed accented letters are ten characters and
 * twenty bytes, so they fit a twenty-position field by one reading and exactly fill it by the other;
 * eleven of them fit by characters and overflow by bytes, and that is the value a character-only
 * check would have let through to a service that refuses it.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function refusesAValueThatFitsOnlyOneOfTheTwoMeasures(): void {
  const tenAccents = '\u00E9'.repeat(10);
  const elevenAccents = '\u00E9'.repeat(11);

  expect(fitsDeclaredWidth('ASCII NAME', 20), 'ASCII within the width must fit').toBe(true);
  expect(fitsDeclaredWidth(tenAccents, 20), 'ten accents must exactly fill twenty bytes').toBe(
    true,
  );
  expect(
    codePointLength(elevenAccents),
    'eleven accents must be within the width by characters',
  ).toBeLessThanOrEqual(20);
  expect(
    fitsDeclaredWidth(elevenAccents, 20),
    'and must still be refused, because they overflow the record positions',
  ).toBe(false);
  /*
   * WHY : Assumptions: the two canonical forms are asserted to reach the SAME verdict rather than a
   *       particular one, because that is the property normalising before measuring buys. The
   *       decomposed form is five code points and three bytes for its accent; the composed form is
   *       four and two. Without normalisation the same name is refused or accepted depending on how
   *       the operator's keyboard produced the accent, which is precisely the inconsistency V255
   *       measured, and no single width makes both readings agree.
   * WHY : Trade-offs: `José` is four characters and FIVE bytes, so it is refused by a four-position
   *       field and accepted by a five-position one. That looks surprising next to a character count
   *       and it is the correct answer for a fixed-width record, which is why the byte measure is
   *       part of the check rather than an alternative to it.
   */
  for (const width of [3, 4, 5, 6]) {
    expect(
      fitsDeclaredWidth(DECOMPOSED_ACCENT, width),
      `both canonical forms must reach the same verdict at width ${String(width)}`,
    ).toBe(fitsDeclaredWidth(COMPOSED_ACCENT, width));
  }
  expect(
    fitsDeclaredWidth(COMPOSED_ACCENT, 4),
    'four characters costing five bytes must not fit a four-position field',
  ).toBe(false);
  expect(fitsDeclaredWidth(COMPOSED_ACCENT, 5), 'and must fit a five-position one').toBe(true);
}

/**
 * Builds a thunk that measures a value against one declared width, so a refusal can be asserted.
 *
 * Assumptions: a named factory rather than an inline thunk at each assertion, matching the convention
 * `ui/src/api/client.test.ts` established -- `ui/eslint.config.js` selects a function expression in
 * every position with `publicOnly: false`, so an inline thunk would owe its own block at every
 * specimen.
 * @param {number} declaredWidth - Copybook PICTURE width to measure against, valid or not.
 * @returns {() => boolean} A thunk invoking the published width predicate.
 */
function measuringAgainstWidth(declaredWidth: number): () => boolean {
  /**
   * Invokes the width predicate for the captured width.
   * @returns {boolean} Whether the value fits, when the width is accepted.
   */
  return function measureOne(): boolean {
    return fitsDeclaredWidth('x', declaredWidth);
  };
}

/**
 * The width check refuses a caller that passed something other than a field width.
 *
 * Purpose: the same guard `padToDeclaredWidth` carries, for the same reason — a fractional or
 * negative width means the caller has computed it rather than read it from a `PICTURE` clause, and
 * failing loudly is cheaper than padding to a nonsense length.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function refusesAWidthThatIsNotAFieldWidth(): void {
  for (const rejected of [-1, 1.5]) {
    expect(measuringAgainstWidth(rejected), `${rejected} is not a field width`).toThrow(RangeError);
  }
  expect(fitsDeclaredWidth('', 0), 'an empty value must fit a zero-width field').toBe(true);
}

/**
 * The catalogue's own padding helper is unaffected, because its inputs are ASCII.
 *
 * Purpose: `padToDeclaredWidth` measures with the platform's own count, and that is correct for the
 * transcribed literals it exists to render — for ASCII the three readings of "width" are one
 * number. This pins that scope: the helper's behaviour on a catalogued literal is unchanged, and the
 * new helpers are what operator-supplied text goes through instead.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function leavesTheAsciiPaddingHelperUnchanged(): void {
  const padded = padToDeclaredWidth('OK', 5);

  expect(padded, 'a short ASCII literal must pad on the right').toBe('OK   ');
  expect(codePointLength(padded), 'and its three width readings must agree').toBe(5);
  expect(utf8ByteLength(padded), 'and its three width readings must agree').toBe(5);
}

/**
 * Every sentence this application authored fits the message field it is published through.
 *
 * Purpose: this is the regression bound for V175. The service publishes an operator-facing sentence
 * through a field of {@link MESSAGE_BAND.workAreaWidth} characters, and the band renders a box that
 * wide; a longer sentence is truncated with nothing to indicate that it was. Because these strings
 * are AUTHORED rather than transcribed, the bound is enforceable — there is no COBOL oracle whose
 * wording it would contradict.
 *
 * Assumptions: measured in code points rather than code units, for consistency with the field width
 * everything else here is measured against. Every one of these sentences is ASCII, so the two agree
 * today; measuring code points means the bound stays correct if one ever is not.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function keepsEveryAuthoredSentenceInsideTheMessageField(): void {
  expect(
    AUTHORED_OPERATOR_SENTENCES.length,
    'the register must not be empty, or the bound is asserting nothing',
  ).toBeGreaterThan(0);

  for (const sentence of AUTHORED_OPERATOR_SENTENCES) {
    expect(
      codePointLength(sentence),
      `authored sentence overruns the ${String(MESSAGE_BAND.workAreaWidth)}-character message field: ${sentence}`,
    ).toBeLessThanOrEqual(MESSAGE_BAND.workAreaWidth);
    expect(sentence.trim(), `authored sentence must not be blank: ${sentence}`).not.toBe('');
  }
}

/**
 * Every sentence authored for a condition the reference application could not have had, with the
 * width each one measured when it was written.
 *
 * Assumptions: the expected character count is stated PER ENTRY rather than only bounded, and the
 * two assertions it supports are different. The bound proves the sentence fits the field; the exact
 * count proves the sentence is still the one that was measured and reviewed, so a later reword that
 * happens to stay under seventy-five characters is reported rather than absorbed silently. That is the
 * property the register-wide bound below cannot give, because it is satisfied by any short string.
 *
 * Assumptions: the export NAME is carried beside each sentence so a failure names the constant a
 * reader has to open, not just the text. Five sentences that all read like operator instructions are
 * otherwise hard to tell apart in an assertion message.
 */
const NEWLY_AUTHORED_SENTENCES = [
  { name: 'SIGN_ON_SESSION_REQUIRED', sentence: SIGN_ON_SESSION_REQUIRED, characters: 50 },
  {
    name: 'SIGN_ON_NEW_PASSWORD_REQUIRED',
    sentence: SIGN_ON_NEW_PASSWORD_REQUIRED,
    characters: 65,
  },
  { name: 'REQUEST_IN_PROGRESS', sentence: REQUEST_IN_PROGRESS, characters: 55 },
  { name: 'TRANSIENT_FAILURE_TRY_AGAIN', sentence: TRANSIENT_FAILURE_TRY_AGAIN, characters: 62 },
  { name: 'PERSISTENT_FAILURE_REPORT_IT', sentence: PERSISTENT_FAILURE_REPORT_IT, characters: 61 },
] as const;

/**
 * Every newly authored sentence fits the message field, at the width it was measured at.
 *
 * Purpose: the five sentences above were authored for conditions no mapset describes — a browser
 * session ending, a credential-replacement turn, a request in flight across a network, and the two
 * halves of a transport failure — so each is bounded by the field the shell renders rather than
 * exempt from it under the migration's rule T8. This is the check that replaces counting characters
 * by hand, which is how the nine overruns the audit found reached a screen in the first place.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function keepsEachNewlyAuthoredSentenceInsideTheMessageField(): void {
  for (const entry of NEWLY_AUTHORED_SENTENCES) {
    expect(
      codePointLength(entry.sentence),
      `${entry.name} is no longer the sentence that was measured: ${entry.sentence}`,
    ).toBe(entry.characters);
    expect(
      fitsDeclaredWidth(entry.sentence, MESSAGE_BAND.workAreaWidth),
      `${entry.name} overruns the ${String(MESSAGE_BAND.workAreaWidth)}-character message field`,
    ).toBe(true);
  }
}

/**
 * Every newly authored sentence is in the provenance register, and each one is distinct.
 *
 * Purpose: the register is what tells authored text from transcribed text, and it is enumerated by
 * hand — so a sentence added to this module and not to the register is unbounded until somebody
 * notices, which the register's own note records as its one real gap. This closes that gap for these
 * five: an unregistered sentence fails here by name.
 *
 * Assumptions: distinctness is asserted as well as membership. Two constants holding the same
 * literal would each satisfy membership while giving one operator sentence two names, which is the
 * duplication this catalogue exists to prevent — the audit found one sentence written three ways in
 * three files.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function registersEveryNewlyAuthoredSentence(): void {
  for (const entry of NEWLY_AUTHORED_SENTENCES) {
    expect(
      AUTHORED_OPERATOR_SENTENCES,
      `${entry.name} must be registered as authored, or nothing bounds its width`,
    ).toContain(entry.sentence);
  }

  /*
   * Assumptions: the set is filled by a loop rather than by `.map`, because
   * `ui/eslint.config.js` selects an arrow function in every position with `publicOnly: false` -- so
   * a one-expression callback would owe its own documentation block. A loop states the same thing
   * with nothing to document.
   */
  const distinct = new Set<string>();
  for (const entry of NEWLY_AUTHORED_SENTENCES) {
    distinct.add(entry.sentence);
  }

  expect(distinct.size, 'each authored sentence must be its own wording').toBe(
    NEWLY_AUTHORED_SENTENCES.length,
  );
}

/**
 * No authored sentence puts an internal identifier in front of an operator.
 *
 * Purpose: these sentences are read by a person, and every failure they describe carries a problem
 * code, a correlation identifier and a subsystem name alongside it. The audit's finding on this class
 * of wording is specific: a screen that told the operator to quote a correlation identifier it never
 * displayed asked for a value the operator did not have. This pins the register's voice — the
 * identifiers stay on the failure, where support reads them.
 *
 * Assumptions: the check is over the whole register rather than only the five new entries, because
 * the property is the register's and not this change's. It is expressed as two narrow patterns — a
 * `CARDDEMO-` code prefix and any run of four or more digits — rather than as a ban on digits, so a
 * sentence naming a length or a count stays permissible.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function keepsInternalIdentifiersOutOfAuthoredSentences(): void {
  for (const sentence of AUTHORED_OPERATOR_SENTENCES) {
    expect(sentence, `an authored sentence must not carry a problem code: ${sentence}`).not.toMatch(
      /CARDDEMO-/u,
    );
    expect(
      sentence,
      `an authored sentence must not carry an identifier-shaped digit run: ${sentence}`,
    ).not.toMatch(/[0-9]{4,}/u);
  }
}

/**
 * Registers the catalogue width and Unicode cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function registerCatalogueWidthCases(): void {
  it('counts code points rather than code units', countsCodePointsRatherThanCodeUnits);
  it('counts wire bytes separately from characters', countsWireBytesSeparatelyFromCharacters);
  it('converges the two canonical forms on the wire', convergesTheTwoCanonicalFormsOnTheWire);
  it('leaves compatibility characters alone', leavesCompatibilityCharactersAlone);
  it(
    'refuses a value that fits only one of the two measures',
    refusesAValueThatFitsOnlyOneOfTheTwoMeasures,
  );
  it('refuses a width that is not a field width', refusesAWidthThatIsNotAFieldWidth);
  it('leaves the ASCII padding helper unchanged', leavesTheAsciiPaddingHelperUnchanged);
  it(
    'keeps every authored sentence inside the message field',
    keepsEveryAuthoredSentenceInsideTheMessageField,
  );
  it(
    'keeps each newly authored sentence inside the message field',
    keepsEachNewlyAuthoredSentenceInsideTheMessageField,
  );
  it('registers every newly authored sentence', registersEveryNewlyAuthoredSentence);
  it(
    'keeps internal identifiers out of authored sentences',
    keepsInternalIdentifiersOutOfAuthoredSentences,
  );
}

describe('message catalogue field widths', registerCatalogueWidthCases);
