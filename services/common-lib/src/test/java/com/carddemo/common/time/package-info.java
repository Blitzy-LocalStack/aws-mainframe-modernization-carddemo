/**
 * Verifies the shared kernel timestamp formatter against its exact twenty-six character contract
 * and its deterministic conversion behaviour.
 *
 * <p>The one rendering asserted here is {@code YYYY-MM-DD HH:MM:SS.mmmmmm}. Read left to right that
 * is ten characters of date, then a single SPACE where ISO 8601 would place a {@code T}, then a
 * time whose hour, minute and second are separated by COLONS, then one PERIOD, then exactly six
 * fractional-second digits: twenty-six characters in total, never twenty-five and never
 * twenty-seven. The width is the same {@code 26} that appears as {@code PIC X(26)} in the reference
 * record layouts and as {@code TIMESTAMP(6)} in the target column. Those properties are asserted as
 * separate expectations rather than as one equality against a whole string, because the width, the
 * date-to-time separator, the time punctuation, the count of fractional digits and the position of
 * the sole period fail independently of one another, and a single string comparison reports only
 * that something differed without saying which of the five it was.</p>
 *
 * <h2>The ten-character date prefix is part of the contract</h2>
 *
 * <p>The leading ten characters are {@code yyyy-MM-dd}. That prefix is constant width, zero padded
 * and ordered most significant component first, so comparing two prefixes lexicographically answers
 * the same question as comparing them chronologically. Two consumers in the reference baseline
 * slice the prefix out of a twenty-six character value instead of parsing the value, which is why
 * an assertion on the prefix here exercises a live external dependency rather than an internal
 * detail. {@code app/cbl/CBTRN02C.cbl} line 414 evaluates
 * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}, reading the first ten bytes of a
 * timestamp in order to compare a date; the spelling of that expiration field is the baseline's own
 * and is reproduced as it stands. {@code app/jcl/TRANREPT.jcl} line 42 declares the sort symbol
 * {@code TRAN-PROC-DT,305,10,CH}, that is character mode over ten bytes, and lines 47 and 48 then
 * range compare that symbol against two character literals, so a production date-range filter is
 * executed as a lexicographic comparison over this prefix and is correct only because of the
 * ordering equivalence just stated.</p>
 *
 * <h2>The value carries no zone, and that is asserted rather than assumed</h2>
 *
 * <p>The target type is {@code java.time.LocalDateTime}, and the expectations here are written to a
 * zone-less contract on the strength of one observation in the reference source. The baseline clock
 * structure holds eight components and only seven of them ever reach the formatted result:
 * {@code COB-REST PIC X(05)}, which carries the offset remainder returned by
 * {@code FUNCTION CURRENT-DATE}, is never moved into the twenty-six character value at
 * {@code app/cbl/CBTRN02C.cbl} lines 692 to 705, nor in the byte-equivalent paragraph at
 * {@code app/cbl/CBACT04C.cbl} lines 613 to 626. No offset is recorded anywhere in the source, so
 * an expectation that admitted an offset-bearing or zone-bearing value would be asserting
 * information the migrated system has no way to obtain, and every comparison downstream would then
 * depend on the invention.</p>
 *
 * <h2>A processing timestamp is not a business date</h2>
 *
 * <p>{@code app/jcl/INTCALC.jcl} line 22 runs {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'},
 * supplying a business date into the job from outside it. That parameter is ten characters of year,
 * month, day and hour carrying no punctuation at all, so it shares neither the width, nor the
 * separators, nor the fractional component of the form under test. This package keeps the two apart
 * with explicit expectations, because the compact parameter and the ambient processing stamp are
 * two different sources of two different values, and treating them as interchangeable is what would
 * let a rerun's injected date be read as the moment the rerun happened. The reference suite rests
 * its reproducibility on the same separation, injecting business dates rather than reading the wall
 * clock and normalising processing stamps before comparison ({@code tests/README.md} lines 485 to
 * 488).</p>
 *
 * <h2>Three distinct inputs, each with a verified vector</h2>
 *
 * <p>The populated vector is {@code 2022-06-10 19:27:53.000000}, held in columns 279 through 304 of
 * {@code tests/fixtures/posting/happy_path/dailytran.txt}, where the space separator, the colon
 * time separators and the six fractional digits are all observable in one value. The blank vector
 * is equally concrete: the processing-timestamp columns 305 through 330 of
 * {@code tests/fixtures/export/happy_path/trandata.txt} hold exactly twenty-six spaces. Blank
 * fixed-width text, populated fixed-width text and a null or absent input are three distinct
 * concepts and are exercised as three distinct cases. A fixed-width record has no way to express
 * absence, so twenty-six spaces is a value that is present and means unstamped, while a null
 * reaches the formatter only from Java callers; folding either one into the other would erase a
 * distinction the 350-byte layout draws between a record awaiting processing and a record already
 * processed.</p>
 *
 * <h2>What the reference baseline emits, and what the migrated Java emits</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl} line 149 carries the mask {@code EEEE-MM-DD-UU.MM.SS.HH0000}, and
 * that mask accurately describes native Db2 punctuation: the paragraph moves a hyphen into all
 * three separator positions at line 702 and a period into the three remaining positions at line
 * 703. The baseline emits that native form; the migrated Java emits the documented canonical form
 * with a space and colons; both are exactly twenty-six characters and both open with the same
 * ten-character date prefix. The divergence is deliberate and is documented. Expectations in this
 * package are therefore written against the canonical target form, and the native form is cited as
 * the source the contract derives from rather than as a value the code under test is expected to
 * return.</p>
 *
 * <h2>Decision record</h2>
 *
 * <p>Assumptions: this descriptor exists because the Java documentation gate audits test sources on
 * the same terms as main sources. {@code services/pom.xml} sets {@code includeTestSourceDirectory}
 * on the Checkstyle execution it binds to the Maven {@code validate} phase, and
 * {@code mvn -f services/common-lib/pom.xml help:effective-pom} resolves that setting to
 * {@code true}, so {@code config/checkstyle/checkstyle.xml} governs this directory in full. Two of
 * its checks act on this file as a pair and neither is redundant: {@code JavadocPackage} requires a
 * {@code package-info.java} to exist in any directory holding an audited source, while
 * {@code MissingJavadocPackage} requires that file to carry Javadoc on its package declaration, so
 * an empty descriptor would satisfy the first and fail the second. A package declaration is also
 * the module entry point the project Explainability rule names, and {@code package-info.java} is
 * the only compilation unit able to carry package-level Javadoc, so this file is required rather
 * than decorative. That rule's parameter, return value and exception elements describe callable
 * code and have no counterpart on a package declaration, so they are omitted here deliberately
 * rather than written out empty; an at-clause carrying no description would itself be reported by
 * {@code NonEmptyAtclauseDescription}.</p>
 *
 * <p>Assumptions: the expectations gathered here depend on four external contracts that this
 * package does not own. The declared width {@code PIC X(26)}, at {@code app/cpy/CVTRA05Y.cpy} line
 * 17 for the transaction record and {@code app/cpy/CVTRA06Y.cpy} line 17 for the daily transaction
 * record. The placement of that field at one-based column 305 within a 350-byte record,
 * corroborated by an artefact that shares no code with the copybooks, the sort symbol at
 * {@code app/jcl/TRANREPT.jcl} line 42. The equivalence of lexicographic and chronological ordering
 * over the ten-character prefix, without which the character-mode range filter at
 * {@code app/jcl/TRANREPT.jcl} lines 47 and 48 would silently select the wrong records. And the
 * absence of a zone component. A change to any one of them invalidates the expectations here rather
 * than the code under test, which is why each is named at this boundary instead of being left
 * implicit inside a fixture string.</p>
 *
 * <p>Alternatives Considered: a posting-specific timestamp test package beside an interest-specific
 * one was evaluated and rejected. The constructing paragraph exists twice in the reference
 * baseline, byte for byte identical, at {@code app/cbl/CBTRN02C.cbl} lines 692 to 705 and at
 * {@code app/cbl/CBACT04C.cbl} lines 613 to 626, so two suites would assert one contract twice and
 * could then drift apart on it while both stayed green; a divergence of one character in twenty-six
 * is enough to invalidate the offset arithmetic that {@code app/jcl/TRANREPT.jcl} line 42 depends
 * on. The migrated concern is single-sourced in {@code common-lib} for that reason, so its
 * verification is single-sourced with it and each expectation is written exactly once. The house
 * precedent points the same way, {@code tests/README.md} lines 540 to 542 stating that a record
 * layout is never duplicated and is kept single-sourced from one directory.</p>
 *
 * <p>Trade-offs: two compromises are accepted knowingly. The vectors above are pinned to the
 * literal bytes of the reference fixtures rather than derived at run time from a clock, which costs
 * a manual update if a fixture is ever re-cut and gives this package no way to detect that drift by
 * itself; determinism is bought with that cost, and a clock-derived expectation could not assert a
 * fixed fractional-digit count at all. And six fractional digits are asserted although the baseline
 * clock populates only two of them, the remaining four being the literal {@code '0000'} moved in at
 * {@code app/cbl/CBTRN02C.cbl} line 701 and {@code app/cbl/CBACT04C.cbl} line 622, so these
 * expectations pin the width the reference layouts and the target column both declare and cannot
 * detect a change in the real resolution of a clock read.</p>
 *
 * <h2>Contents of this package</h2>
 *
 * <p>Two {@code .java} files and no others: this descriptor, and {@code TimestampFormatterTest},
 * which exercises the single production class held in the {@code com.carddemo.common.time} package
 * of the main source tree. Fixture bytes are quoted in the expectations rather than copied into a
 * test resource directory, so this package owns no resources.</p>
 *
 * <h2>Relationship to the reference test suite</h2>
 *
 * <p>The tests in this tree are additive Java unit tests and are not the reference COBOL parity
 * oracle. That oracle lives under {@code tests/} at the repository root with its own runners and
 * its own pinned toolchain, it is read here as evidence and never modified, and nothing in this
 * package stands in for it. The relationship runs one way only: the fixture columns cited above are
 * read as observations of the contract, and the expectations derived from them live here.</p>
 */
package com.carddemo.common.time;
