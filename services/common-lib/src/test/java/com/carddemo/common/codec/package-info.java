/**
 * Byte-level verification of the fixed-format codecs in {@code com.carddemo.common.codec}.
 *
 * <p>Purpose: hold the tests that pin each codec to the exact bytes an immutable reference artifact
 * declares or emits. Every expectation in this package is a byte string or a length taken from a
 * copybook line or a COBOL statement under {@code app/}, cited at the expectation, so that a reader
 * can re-derive it without leaving the test.</p>
 *
 * <p>Assumptions: a codec test asserts BYTES and not round-trip symmetry alone. A round trip proves
 * that this code base agrees with itself, which is exactly the property a wrong width or a wrong
 * edit mask preserves: encode and decode can share one mistaken assumption and still agree. The
 * authorization CSV contract is the case in point -- an earlier revision of {@code CsvAuthCodec}
 * round-tripped perfectly while emitting a request amount one character too wide for the reference
 * program's receiver and a reply amount with a {@code +} where COBOL emits a blank. Only a golden
 * byte vector taken from the reference source catches that, so golden vectors are the primary
 * assertion form here and round trips are a secondary check.</p>
 *
 * <p>Trade-offs: these tests live in the shared kernel rather than in each consuming service. The
 * codecs are the kernel's own contract with the baseline, so a per-service copy would assert the
 * same bytes several times over and would leave no single place that fails when a contract changes.
 * The cost accepted is that a service reading a codec cannot see its byte expectations from its own
 * module; the citation in each test names the artifact instead.</p>
 *
 * <p>Assumptions: no test in this package reads a clock, a file under {@code app/}, an environment
 * variable or a network resource. The expectations are literals transcribed from the reference
 * source under review, which is what lets the whole package run on a machine with no database, no
 * emulator and no COBOL compiler.</p>
 *
 * <p>Assumptions: these tests additionally assert three SECURITY properties of the same
 * boundary, because a codec is where untrusted bytes become values and each property is
 * invisible in a round trip.</p>
 *
 * <p><strong>Work is bounded before it is done.</strong> A payload arriving from a queue is
 * attacker-influenced in both content and length, and splitting is proportional to length. The tests
 * assert that a payload beyond the declared bound is refused on its length, before it is scanned,
 * counted, sized into an array or split -- so a malformed message costs a constant amount of work
 * rather than an amount its sender chooses.</p>
 *
 * <p><strong>Control characters do not pass.</strong> Every field the reference authorization contract
 * declares is a display picture, so no control character is representable in one. The tests assert that
 * a payload carrying one is refused, because such a character reaching a line-oriented log forges or
 * splits a record, and reaching a terminal or log viewer is interpreted rather than displayed.</p>
 *
 * <p><strong>A diagnostic discloses nothing it describes.</strong> Every refusal these codecs raise
 * names a length, a position, a field name or one of the codec's own declared constants, and never the
 * characters that arrived. The tests assert the absence, which is the only way an absence stays absent:
 * the values passing through here include primary account numbers, and an exception message becomes a
 * log line, an alert and an error body.</p>
 *
 * <p>Alongside those, the correlation and ordering identities are asserted to be stable, scoped and
 * opaque -- equal for equal inputs so that pairing and first-in-first-out ordering still work, unequal
 * across purposes so two systems cannot be joined on one token, and carrying no part of the value they
 * stand for.</p>
 *
 *
 * <p>Assumptions: the correlation and ordering identities are asserted to be stable, scoped
 * and opaque -- equal for equal inputs so that pairing and first-in-first-out ordering still
 * work, unequal across purposes so two systems cannot be joined on one token, and carrying no
 * part of the value they stand for.</p>
 *
 * <p>Assumptions: key material in these tests is a fixed literal of the required width. A generated key
 * would make a stability assertion untestable across two instances, and a key drawn from the environment
 * would make the tests pass or fail according to what the environment held.</p>
 *
 * <p>Parameters, return values and exceptions at package level: declared inapplicable. A package
 * declaration accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. The inapplicability is stated rather than left silent
 * because the project's single user-specified rule, Explainability, names a docstring that omits
 * parameters, return values or purpose among its forbidden patterns, and a reader has to be able to
 * tell a declared inapplicability from an oversight.</p>
 */
package com.carddemo.common.codec;
