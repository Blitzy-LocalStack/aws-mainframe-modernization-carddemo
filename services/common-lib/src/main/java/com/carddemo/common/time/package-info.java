/**
 * Owns the exact twenty-six character timestamp contract carried by the migrated CardDemo services.
 *
 * <h2>What this package holds</h2>
 *
 * <p>Assumptions: this charter describes what this package holds, and the description is a measurement
 * of the directory rather than a forward statement. The one production class it names,
 * {@code TimestampFormatter}, is present beside it.</p>
 *
 * <p>Refactoring Rationale: this section opened by declaring every inventory here a target contract
 * rather than a measurement, and recorded that {@code TimestampFormatter} was the only production
 * class existing anywhere in the shared kernel. The second half is long superseded -- the kernel
 * now holds production classes in every one of its eleven subpackages -- and once it was, the
 * disclaimer did active harm: it instructed a reader to distrust an inventory that had become
 * accurate, so a genuine discrepancy in this file would have read as the expected condition rather
 * than as a defect.</p>
 *
 * <p>A single canonical rendering serves every origination and processing timestamp that crosses a
 * persistence, batch or API boundary in the migrated system:
 * {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'}. Read left to right that is ten characters of ISO date, then
 * one SPACE rather than the ISO 8601 {@code T}, then eight characters of time whose components are
 * separated by COLONS, then a PERIOD, then exactly six fractional-second digits: twenty-six
 * characters in total, never twenty-five and never twenty-seven. The
 * {@code java.time.format.DateTimeFormatter} pattern that realises it is
 * {@code uuuu-MM-dd HH:mm:ss.SSSSSS}, resolved strictly, and the contract binds three declarations
 * of one value together, namely {@code PIC X(26)} in the reference COBOL, {@code TIMESTAMP(6)} in
 * the target column and {@code java.time.LocalDateTime} in the target Java type. Assumptions: the
 * proleptic year letter and strict resolution are a coupled pair and are what make reading a value
 * back reject an impossible date rather than adjust it into a nearby one; the rendered
 * twenty-six-character form is identical either way, and the reasoning is recorded on the formatter
 * constant in {@code TimestampFormatter}.</p>
 *
 * <p>Two properties of that pattern are enforced by the class rather than left to the type it
 * renders, because {@code java.time.LocalDateTime} is wider than this contract in both directions.
 * Its year is restricted to the range 1000 through 9999 inclusive: below and above that range the
 * year field stops being four characters -- year 999 renders {@code 0999} and holds the width, but
 * year 10000 renders {@code +10000} and produces twenty-eight characters, which a {@code PIC X(26)}
 * receiving field truncates rather than rejects, so a rejected record would become a silently
 * corrupted one. And an impossible calendar date is a failure rather than a correction: strict
 * resolution is what makes the thirtieth of February, the twenty-ninth of a non-leap February, the
 * thirty-first of April and an hour of twenty-four each raise
 * {@code java.time.format.DateTimeParseException} instead of being moved to a neighbouring valid
 * value, while a genuine leap day is accepted unchanged. The default resolver does move all four,
 * which was measured rather than assumed and is recorded on the class.</p>
 *
 * <p>The leading ten characters are locked to {@code uuuu-MM-dd}. Because that prefix is of constant
 * width, zero padded and ordered most significant component first, comparing two prefixes
 * lexicographically returns the same answer as comparing them chronologically. That equivalence is
 * not an incidental property of the encoding to be enjoyed where convenient: the reference baseline
 * relies on it in production, in the character mode range comparison cited below, so this package
 * guarantees the layout that keeps it true rather than merely happening to produce it.</p>
 *
 * <h2>Why the width is twenty-six and not a rounded figure</h2>
 *
 * <p>Six independent artefacts in the reference baseline agree on the width, which is why it is
 * treated here as a contract rather than as a formatting preference:</p>
 *
 * <ul>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} line 17 declares {@code TRAN-PROC-TS PIC X(26)}. The record's
 *       fourteen fields sum to exactly 350 bytes, which places that field at zero-based offset
 *       304.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} line 159 declares {@code 01 DB2-FORMAT-TS PIC X(26)} and line
 *       160 redefines it; the component split at lines 161 to 174 sums
 *       {@code 4+1+2+1+2+1+2+1+2+1+2+1+2+4 = 26}, proving the width a second time by arithmetic
 *       rather than by assertion.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} lines 150 to 165 carry the same declaration and the same
 *       redefinition, so the width is a shared contract and not one program's local choice.</li>
 *   <li>{@code app/cpy/CVEXPORT.cpy} line 11 declares {@code EXPORT-TIMESTAMP PIC X(26)} and
 *       redefines it at line 12 into a ten-byte date at line 13, a one-byte separator at line 14
 *       and a fifteen-byte time at line 15, which is {@code 10 + 1 + 15 = 26}. That the separator
 *       is modelled as its own named one-byte field is itself evidence that punctuation is a
 *       formatting choice while the geometry is the contract.</li>
 *   <li>{@code tests/fixtures/posting/happy_path/dailytran.txt} supplies a live vector: the
 *       twenty-six columns 279 through 304 of its first record hold
 *       {@code 2022-06-10 19:27:53.000000}, exhibiting the space separator, the colon time
 *       separators and six fractional digits in one observable value.</li>
 *   <li>{@code app/jcl/TRANREPT.jcl} line 42 declares the sort symbol
 *       {@code TRAN-PROC-DT,305,10,CH}, whose one-based position 305 is that same zero-based offset
 *       304, corroborating the placement from an artefact that shares no code with the
 *       copybooks.</li>
 * </ul>
 *
 * <h2>What the reference baseline does, and where the migrated code diverges</h2>
 *
 * <p>Punctuation. {@code app/cbl/CBTRN02C.cbl} line 149 carries the mask
 * {@code EEEE-MM-DD-UU.MM.SS.HH0000}, and that mask accurately describes what the baseline writes:
 * the paragraph at lines 692 to 705 moves a hyphen into all three separator positions at line 702
 * and a period into all three remaining positions at line 703, which is native Db2 punctuation. The
 * baseline emits that form; the migrated Java deliberately emits the ISO space and colon form; both
 * are exactly twenty-six characters at the same field position, and both decompose identically into
 * the ten-byte date, one-byte separator and fifteen-byte time modelled at
 * {@code app/cpy/CVEXPORT.cpy} lines 13 to 15. The divergence is deliberate and is documented.</p>
 *
 * <p>Fractional resolution. The only wall clock reads in the reference baseline are
 * {@code app/cbl/CBTRN02C.cbl} line 693 and {@code app/cbl/CBACT04C.cbl} line 614, both
 * {@code MOVE FUNCTION CURRENT-DATE TO COBOL-TS}. That intrinsic yields twenty-one characters whose
 * fractional component is two digits of centiseconds, and each paragraph then moves the literal
 * {@code '0000'} into the four remaining fractional positions, at line 701 and at line 622
 * respectively. The last four fractional digits are therefore structurally zero in the baseline, and
 * what the contract carries is the {@code PIC X(26)} width rather than the fractional resolution.
 * The baseline populates two fractional digits; the migrated Java emits six for width fidelity; the
 * divergence is documented.</p>
 *
 * <p>Absence of a zone. Of the eight components of the baseline clock structure only seven are ever
 * moved into the formatted result. {@code COB-REST PIC X(05)}, which carries the offset from
 * Greenwich, is never moved ({@code app/cbl/CBTRN02C.cbl} lines 693 to 700 and
 * {@code app/cbl/CBACT04C.cbl} lines 614 to 621). The baseline value therefore carries no zone
 * information at all, and that single observation is the entire justification for the target type
 * being {@code java.time.LocalDateTime} rather than {@code java.time.OffsetDateTime} or
 * {@code java.time.ZonedDateTime}: an offset-bearing type would have to invent an offset the source
 * never recorded, and every comparison downstream would then depend on the invention.</p>
 *
 * <h2>Consumers that read only the ten-character date prefix</h2>
 *
 * <p>Two production consumers slice the prefix out of a twenty-six character value instead of
 * parsing the value, which is why the prefix layout belongs to this package's contract and is not an
 * internal detail. {@code app/cbl/CBTRN02C.cbl} line 414 evaluates
 * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}, reading the first ten bytes of a
 * timestamp in order to compare a date; the spelling of that expiration field is the baseline's own
 * and is reproduced here exactly as it stands. {@code app/jcl/TRANREPT.jcl} lines 47 and 48 then
 * apply {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND, TRAN-PROC-DT,LE,PARM-END-DATE)} to
 * the symbol declared {@code CH}, that is character mode, at line 42, so a production date range
 * filter is executed as a lexicographic comparison and is correct only because of the ordering
 * equivalence stated above. That filter is a live external dependency on this layout.</p>
 *
 * <h2>How callers are expected to use the value</h2>
 *
 * <p>Format once per unit of work and reuse the result. {@code app/cbl/CBTRN02C.cbl} lines 437 and
 * 438 build one timestamp and move it into the record, and lines 440 to 442 then drive three
 * separate writes from that one value, updating the category balance, the account record and the
 * transaction file inside a single unit of work. Re-reading the clock per write would let those
 * three records disagree about when the work happened, which the baseline structurally cannot
 * do.</p>
 *
 * <p>Reduce the value to microseconds once, through this package, before either rendering it or
 * binding it to a database parameter. The two sides of the persistence boundary dispose of a seventh
 * fractional digit differently, and the difference was measured on JDK 21.0.11 with PostgreSQL JDBC
 * 42.7.13 against a real {@code TIMESTAMP(6)} column rather than taken on trust: the
 * {@code .SSSSSS} fraction field of this package TRUNCATES a seventh digit, while the driver ROUNDS
 * it to the nearest microsecond before binding. A value of {@code 19:27:53.123456500} therefore
 * rendered as {@code .123456} and was stored as {@code .123457}, and the carry case crossed a second:
 * {@code 19:27:53.999999500} rendered as {@code 53.999999} and was stored as {@code 54.000000}. At
 * the end of a day the carry moves the ten-character date prefix too, which was measured as well --
 * {@code 2023-06-10 23:59:59.999999} rendered, {@code 2023-06-11 00:00:00.000000} stored -- and that
 * prefix is the part every production consumer of this field reads. Truncating once, deliberately,
 * leaves the driver nothing to round, and the same cases re-measured after truncation had the
 * rendered string and the stored column agreeing exactly. A caller that renders one value into a
 * report and binds another into a column has produced two records of one event, which a
 * byte-deterministic parity comparison will correctly report as different.</p>
 *
 * <p>Keep the ambient processing stamp distinct from an injected business date.
 * {@code app/jcl/INTCALC.jcl} line 22 runs {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'},
 * supplying a business date from outside the program into {@code PARM-DATE PIC X(10)} at
 * {@code app/cbl/CBACT04C.cbl} line 178, while the processing stamp of that same run is read from
 * the wall clock at line 614. Those are two different sources and must remain two different sources,
 * because injecting the business date is what lets a rerun reproduce its output. The injected value
 * is also compact and unpunctuated, being a year, month, day and hour with no separators at all, so
 * it is not this package's twenty-six character punctuated form and must never be conflated with
 * it.</p>
 *
 * <h2>Decision record</h2>
 *
 * <p>Refactoring Rationale: the timestamp building paragraph {@code Z-GET-DB2-FORMAT-TIMESTAMP}
 * exists twice in the reference baseline, byte for byte identical, at
 * {@code app/cbl/CBTRN02C.cbl} lines 692 to 705 and at {@code app/cbl/CBACT04C.cbl} lines 613 to
 * 626, which is the same fourteen lines at a constant offset of seventy-nine. Two programs each
 * holding a private copy of one wire format is the duplication this package removes, and it is
 * measurable rather than notional. The house precedent points the same way:
 * {@code tests/README.md} line 268 shows the reference programs compiled against one copybook
 * include path, {@code -I app/cpy}, and lines 540 to 542 state the doctrine that a record layout is
 * never duplicated and is kept single-sourced from that one directory. This library is the Java
 * analogue of that single include path, and this package is the reason no service re-declares the
 * timestamp format for itself.</p>
 *
 * <p>Alternatives Considered: a per-service timestamp formatter was evaluated and rejected. Shared
 * concerns in this migration, among them the money type, the copybook codecs, the error model and
 * the validation flags as well as this format, are declared only in this library and are never
 * restated inside a service. Declaring the format per service would reproduce exactly the
 * duplication recorded immediately above, and would let two services drift apart on a value that a
 * persisted column, a batch sort symbol and an API payload all read; a drift of one character in
 * twenty-six is enough to invalidate the offset arithmetic that {@code app/jcl/TRANREPT.jcl} line 42
 * depends on.</p>
 *
 * <p>Assumptions: this package depends on five external contracts that it does not own. The declared
 * width {@code PIC X(26)} ({@code app/cpy/CVTRA05Y.cpy} line 17). The zero-based offset 304 of that
 * field within the 350-byte record declared by the same copybook, corroborated by the sort symbol at
 * {@code app/jcl/TRANREPT.jcl} line 42. The equivalence of lexicographic and chronological ordering
 * over the ten-character ISO date prefix, without which the character mode range comparison at
 * {@code app/jcl/TRANREPT.jcl} lines 47 and 48 would silently select the wrong records. The
 * centisecond real resolution of the baseline clock read ({@code app/cbl/CBTRN02C.cbl} line 693 and
 * {@code app/cbl/CBACT04C.cbl} line 614), padded out to microsecond width. And the absence of any
 * zone component, {@code COB-REST PIC X(05)} never being moved into the result.</p>
 *
 * <p>Trade-offs: three compromises are accepted knowingly. Six fractional digits are emitted although
 * the baseline clock can populate only two of them, so the four trailing zeros are structural rather
 * than incidental; width fidelity against {@code PIC X(26)} and {@code TIMESTAMP(6)} is bought at
 * the cost of a rendering that implies a resolution the source never had. And the ISO space and
 * colon punctuation is emitted rather than the native Db2 punctuation described at
 * {@code app/cbl/CBTRN02C.cbl} line 149, because the target column type renders natively in the
 * space and colon form while the only production consumer of this field's punctuation reads just the
 * ten-character date prefix ({@code app/jcl/TRANREPT.jcl} line 42), which is byte identical under
 * both forms. That compromise is therefore paid in cosmetic divergence and not in consumer
 * behaviour. And the pattern is spelled with the proleptic year letter {@code uuuu} rather than the
 * year-of-era letter {@code yyyy} the prose contract above uses, so the pattern string is no longer a
 * character-for-character echo of the prose; the two render identically for every year this contract
 * admits, and what is bought is the strict resolution that rejects a date the calendar does not have,
 * since strict resolution paired with {@code yyyy} leaves the era unresolved and fails on every value
 * including the ones this package itself writes.</p>
 *
 * <h2>Contents of this package</h2>
 *
 * <p>This package holds exactly one production class, {@code TimestampFormatter}, which owns the
 * pattern named above; with this descriptor beside it, the directory contains exactly two
 * {@code .java} files. Assumptions: that is the invariant this section exists to state, and it is
 * stated without reference to a module-wide total. Refactoring Rationale: a target count for the
 * whole of {@code com.carddemo.common} stood here -- twenty-one production classes and thirty
 * files, broken down per subpackage -- and it is removed rather than corrected. It was wrong when
 * the review measured it and it will be wrong again after the next class lands, because it is a
 * figure about eight sibling packages restated inside a ninth; nothing in this package's contract
 * depends on it, and {@code services/common-lib} is the authority for its own inventory.</p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>The project Explainability rule requires a docstring on every module entry point at its line
 * 15, and a Java package declaration is that entry point; {@code package-info.java} is the only
 * compilation unit in which package-level Javadoc can be carried, so this file is required rather
 * than decorative. Two build gates enforce the same requirement independently, and neither is
 * redundant: the Checkstyle {@code JavadocPackage} module requires that this file exist in any
 * directory holding an audited source file, while the {@code MissingJavadocPackage} module requires
 * that the file carry a Javadoc block on its package declaration, so an empty descriptor would
 * satisfy the first and fail the second.</p>
 *
 * <p>Because this compilation unit contains a single statement, the decision rationale that the
 * rule's validation gate at its line 43 requires alongside the docstring has no adjacent executable
 * code to sit beside; it is therefore carried inside this block under the rule's own category
 * labels, which is the only placement the language makes available. The parameter, return value and
 * exception elements of the rule's docstring specification describe callable code and do not apply
 * to a package declaration, so they are omitted deliberately rather than stated empty: an at-clause
 * with no description would itself be a violation.</p>
 */
package com.carddemo.common.time;
