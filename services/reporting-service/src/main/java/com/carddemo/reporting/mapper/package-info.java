/**
 * Byte-exact artifact assembly and API projection for the CardDemo reporting and statement
 * bounded context.
 *
 * <h2>What this package contains</h2>
 *
 * <p>Eight compilation units sit in this directory: this charter and the seven types it governs --
 * {@code CobolEditMask}, {@code ReportBandLayouts}, {@code ReportingDtoMapper},
 * {@code StatementBandLayouts}, {@code StatementHtmlMapper}, {@code StatementTextMapper} and
 * {@code TransactionReportMapper}. Every type name, band inventory and count below is a measurement
 * of that directory and of those files, so the seam described here is an observation of compiled
 * code and not only a contract to be honoured. The shared-kernel types named further down are
 * likewise all authored.</p>
 *
 * <p>Assumptions: this charter has to exist for the directory to build at all, which is why it is
 * maintained rather than treated as commentary. The {@code JavadocPackage} check at line 245 of
 * {@code config/checkstyle/checkstyle.xml} is a file-set check reporting a missing
 * {@code package-info.java} for any directory holding a processed {@code .java} file, and
 * {@code services/pom.xml} binds the gate to the {@code validate} phase, so a sibling authored here
 * without this charter in place fails the build before the compiler runs.</p>
 *
 * <h2>What this package is for</h2>
 *
 * <p>This package is the anti-corruption layer of the reporting context. It is the one place
 * in this module where copybook representation concerns are permitted to appear: declared
 * field widths, trailing blanks, {@code FILLER}, zero-padding, truncation, sign placement and
 * masking. Everything downstream of it -- the read-only view projections and the API types --
 * stays clean of those concerns and carries values rather than layouts. That charter is not
 * invented here; the module-level Javadoc at line 122 of
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/package-info.java}
 * already names this package as the sole boundary at which padding, trailing blanks,
 * {@code FILLER}, truncation, zero-padding, masking and numeric edit masks may appear, and
 * this file is the detailed statement of that same boundary.</p>
 *
 * <p>Confining the crossing to one package is what makes the boundary real rather than
 * nominal. A padding, truncation, masking or edit-mask decision then has exactly one site at
 * which it may legally occur, and a reviewer auditing for such a decision has exactly one
 * place to look. A helper of this kind placed in a service class or a controller would be
 * reachable from code that has no business knowing a declared width, and the boundary would
 * become a naming convention instead of a constraint.</p>
 *
 * <h2>The eight files, and what each one owns</h2>
 *
 * <ul>
 *   <li><b>{@code package-info}</b> -- this charter, and the package's documentation
 *       contract.</li>
 *   <li><b>{@code CobolEditMask}</b> -- all seven display and edit regimes this module emits,
 *       enumerated below. Each regime formats a value into a {@code String}; none of them
 *       places bytes.</li>
 *   <li><b>{@code ReportBandLayouts}</b> -- locally declared
 *       {@code com.carddemo.common.codec.CopybookLayout.FieldSpec} and {@code RecordSpec}
 *       descriptors for the seven report bands of {@code app/cpy/CVTRA07Y.cpy}, declared once
 *       here rather than inline at each call site.</li>
 *   <li><b>{@code TransactionReportMapper}</b> -- byte-exact emission of the 133-column daily
 *       transaction report, band by band.</li>
 *   <li><b>{@code StatementBandLayouts}</b> -- descriptors for the seventeen {@code ST-LINE}
 *       bands declared in {@code app/cbl/CBSTM03A.CBL}.</li>
 *   <li><b>{@code StatementTextMapper}</b> -- byte-exact emission of the 80-byte plain-text
 *       statement, and owner of the prepared-field set that the HTML mapper shares.</li>
 *   <li><b>{@code StatementHtmlMapper}</b> -- byte-exact emission of the 100-byte HTML
 *       statement, including its fragment table and emission sequence.</li>
 *   <li><b>{@code ReportingDtoMapper}</b> -- the JSON API representations, and the sole home
 *       of data-exposure narrowing.</li>
 * </ul>
 *
 * <p>Alternatives Considered: fewer files, by folding the masks into the mappers that use them
 * and by merging the two descriptor holders. Rejected on three separate grounds, one per
 * seam. {@code CobolEditMask} stays a single type because the defining property of the seven
 * regimes is that they <em>coexist</em> and are never interchangeable -- the same report emits
 * a leading-minus amount at line 30 of {@code app/cpy/CVTRA07Y.cpy} and a leading-plus total
 * at lines 54, 60 and 66 of the same copybook -- and splitting them across the files that call
 * them would scatter the one table where that non-interchangeability is visible. The two
 * descriptor holders stay separate because their padding obligations are opposite rather than
 * merely different, as set out below, and a single holder would invite a shared padding helper
 * that is wrong for one of the two artifacts. {@code StatementHtmlMapper} keeps its fragment
 * table in the same file as its emission sequence because the fragment names themselves embed
 * the original output line numbers -- {@code HTML-L22-35} at line 173 of
 * {@code app/cbl/CBSTM03A.CBL} and {@code HTML-L30-42} at line 176 -- and they are named that
 * way precisely because one fragment is emitted at several positions, so the table and the
 * sequence are two halves of one fact and separating them would hide it. The accepted cost is
 * seven files where three would compile.</p>
 *
 * <h2>Three declared record lengths, none of them a sum of field widths</h2>
 *
 * <p>Assumptions: this package emits three artifacts, and each has a <b>declared record
 * length</b> that is a property of the file it is written to rather than an arithmetic result.
 * Each is anchored to more than one source so the number is checkable:</p>
 *
 * <pre>
 * artifact           declared   declared at                          corroborated by
 * report                  133   app/cpy/CVTRA07Y.cpy line 48         app/cbl/CBTRN03C.cbl
 *                                                                    lines 85 and 133;
 *                                                                    app/jcl/TRANREPT.jcl
 *                                                                    line 78
 * statement text           80   app/cbl/CBSTM03A.CBL line 45         app/jcl/CREASTMT.JCL
 *                                                                    line 89
 * statement HTML          100   app/cbl/CBSTM03A.CBL line 47         app/jcl/CREASTMT.JCL
 *                                                                    line 94; CBSTM03A.CBL
 *                                                                    line 149 and lines 221
 *                                                                    to 223
 * </pre>
 *
 * <p>Assumptions: the report's 133 is a declared length and must never be presented as a sum
 * of the detail line's field widths. The two figures are genuinely different numbers. Line 48
 * of {@code app/cpy/CVTRA07Y.cpy} declares an elementary level-01 item of 133 characters whose
 * value is a row of hyphens, and lines 85 and 133 of {@code app/cbl/CBTRN03C.cbl} declare the
 * output record and a blank line at the same width, while the data-set attributes at line 78
 * of {@code app/jcl/TRANREPT.jcl} name the same 133 as the logical record length. Summing the
 * declared widths of the detail band at lines 15 to 31 of {@code app/cpy/CVTRA07Y.cpy} yields
 * <b>114</b>. A reader who derives the record length from the detail band therefore arrives at
 * a number nineteen characters short, and every band written after it lands at the wrong
 * length. The declared length governs; the sums are used only to decide how much padding each
 * band needs.</p>
 *
 * <h2>The seven display and edit regimes this package emits</h2>
 *
 * <p>Assumptions: the axis of this enumeration is <em>the display and edit regimes this module
 * emits</em>, and it is deliberately not the axis of money representations present in the
 * system. The shared codec package counts its own renderings on that other axis, and the two
 * counts are not meant to agree; reading either list as the other produces a search for a
 * class that was never planned. On this module's axis there are seven:</p>
 *
 * <pre>
 * #  regime                width    behaviour                     declared at
 * 1  -ZZZ,ZZZ,ZZZ.ZZ       15       leading minus, zeros blanked  app/cpy/CVTRA07Y.cpy
 *                                                                 line 30
 * 2  +ZZZ,ZZZ,ZZZ.ZZ       15       leading sign always printed   app/cpy/CVTRA07Y.cpy
 *                                                                 lines 54, 60, 66
 * 3  unsigned 9(nn)         4       leading zeros preserved       app/cpy/CVTRA07Y.cpy
 *                                                                 line 24
 * 4  EDIT=(TTTTTTTTT.TT)   12       zeros preserved, no sign      app/jcl/PRTCATBL.jcl
 *                                                                 line 56
 * 5  +99999999.99          12       8 digits, sign always         app/cbl/CORPT00C.cbl
 *                                                                 line 77
 * 6  9(9).99-              13       trailing sign, zeros kept     app/cbl/CBSTM03A.CBL
 *                                                                 line 113
 * 7  Z(9).99-              13       trailing sign, zeros blanked  app/cbl/CBSTM03A.CBL
 *                                                                 lines 137, 142
 * </pre>
 *
 * <p>Assumptions: none of these seven regimes is owned by the shared kernel, so no caller
 * should go looking for a mask helper there. The codec package owns exactly three numeric and
 * wire contracts -- sign-overpunch display in {@code ZonedDecimalCodec}, packed
 * {@code COMP-3} and binary {@code COMP} in {@code PackedDecimalCodec}, and the two edited
 * forms of the authorization payloads in {@code CsvAuthCodec} -- the fourteen-character reply
 * mask {@code PIC -zzzzzzzzz9.99} and the thirteen-character request token its receiver holds
 * -- and each of the three is scoped to a named corpus that excludes print formatting. Regimes 1, 2
 * and 4 are enumerated in that package's own rendering table for lineage, and claimed by none
 * of its classes. Regimes 3, 5, 6 and 7 are named by no codec at all: four of the seven have no
 * counterpart anywhere outside this package. Regime 5 is worth distinguishing by hand from the
 * codec's reply mask, because the two look alike and are not the same width -- the codec's
 * mask carries ten integer digit positions, while the regime declared at line 77 of
 * {@code app/cbl/CORPT00C.cbl} carries eight. This package therefore implements all seven
 * itself.</p>
 *
 * <p>Assumptions: regimes 6 and 7 place the sign <b>after</b> the digits, and they differ from
 * each other only in whether a leading zero prints. The balance at line 113 of
 * {@code app/cbl/CBSTM03A.CBL} preserves leading zeros; the transaction amount at line 137 and
 * the total at line 142 blank them. Both are thirteen characters wide, so a mask that emitted
 * the sign in front would still fill the field and still misstate every negative value in the
 * statement, which is the kind of divergence a byte comparison catches only after the
 * fact.</p>
 *
 * <h2>The seam with the shared codec package</h2>
 *
 * <p>Assumptions: an edit mask is not a field kind. {@code CopybookLayout.Kind} carries
 * exactly five constants -- {@code TEXT}, {@code UINT}, {@code ZONED}, {@code PACKED} and
 * {@code BINARY} -- and there is deliberately no sixth for a print mask. This package
 * therefore formats a value into a {@code String} first and hands the result to the codec as
 * {@code Kind=TEXT}, which places and pads it. Expecting the codec to apply a mask produces
 * either a compilation failure, when no such constant is found, or silent corruption, when the
 * nearest numeric kind is chosen and re-encodes an already-edited string as digits.</p>
 *
 * <p>Assumptions: the descriptor's component lists are closed and positional.
 * {@code FieldSpec} carries exactly nine components, in the order {@code name},
 * {@code start}, {@code length}, {@code kind}, {@code intDigits}, {@code decDigits},
 * {@code signed}, {@code normalizeTs} and {@code sensitive}; {@code RecordSpec} carries
 * exactly five, {@code name}, {@code reclen}, {@code keyLength}, {@code keyOffset} and
 * {@code fields}. The {@code start} component is <b>zero-based</b>. That last point deserves
 * its own sentence because the baseline states the same offsets one-based: the sort control at
 * lines 41 and 42 of {@code app/jcl/TRANREPT.jcl} names the card number at position 263 and
 * the processing date at 305, which are the zero-based offsets 262 and 304. Copying a
 * one-based figure into a {@code start} component shifts a field by one character and every
 * field after it with it.</p>
 *
 * <p>Assumptions: the descriptor registry does not contain either band family, which is the
 * whole reason this package declares its own. The registry holds <b>fourteen</b> layouts in two
 * labelled populations, and the distinction between fourteen and eleven is load-bearing because
 * eleven is the answer to a different question about the same registry.
 * The eleven BASE MASTERS, each transcribed from a copybook that defines a persistent data set,
 * are {@code ACCOUNT}, {@code CARD}, {@code CUSTOMER}, {@code XREF}, {@code DALYTRAN},
 * {@code TRAN}, {@code DISGROUP}, {@code TCATBAL}, {@code SECUSER}, {@code TRANCAT} and
 * {@code TRANTYPE}. The three DERIVED layouts, built from a base master rather than transcribed
 * from a data-set copybook, are {@code TRNX}, {@code REJECT} and {@code INTTRAN}. Both
 * {@code CopybookLayout} in the shared kernel and
 * {@code data-migration/src/carddemo_migration/copybook/layouts.py} register the same fourteen
 * names with the same two labels, and each records why: the reference codec at
 * {@code tests/helpers/record_codec.py} lines 1349 to 1361 registers ELEVEN entries, the
 * migration has ELEVEN base masters, and only EIGHT names appear in both -- so treating the two
 * elevens as one population drops three records with nothing reporting the loss. All fourteen
 * are record layouts read from or written to data sets. The seven report bands and the seventeen
 * statement bands are print bands assembled into a line, so they were never registry entries,
 * and the public {@code FieldSpec} and {@code RecordSpec} types exist precisely so that a caller
 * can declare a layout the registry does not carry.</p>
 *
 * <p>Assumptions: {@code FixedWidthCodec.encodeRecord} returns a {@code byte[]} whose length
 * is exactly {@code spec.reclen()}, padding a short band out to the declared length and
 * restoring elided {@code FILLER} positions as blanks. All padding in this package goes
 * through it, and no mapper pads a column by hand. A per-column padding helper would
 * reproduce, once per band, a decision the codec already makes once, and the two would drift
 * the first time a band's width changed.</p>
 *
 * <p>Assumptions: the {@code sensitive} component of {@code FieldSpec} is a marker and nothing
 * more. It records that a field carries data whose exposure is narrowed somewhere; it does not
 * narrow it. Masking and suppression live in this package instead, because a codec that masked on
 * encode cannot round-trip -- a decode followed by an encode would return masked bytes where the
 * original stood, and the byte comparison that the parity oracle performs would fail on the codec's
 * own output rather than on a mapper's.</p>
 *
 * <h2>Two padding obligations, opposite by construction</h2>
 *
 * <p>Assumptions: the report bands and the statement bands stand in opposite relations to
 * their declared lengths, and the two situations must never be generalised to one another.
 * Six of the seven report bands are <b>short</b> of the declared 133 and must be blank-padded
 * out to it; only the separator at line 48 of {@code app/cpy/CVTRA07Y.cpy} is natively the
 * full width. The native widths, summed from the declared pictures, are:</p>
 *
 * <pre>
 * band                        native   declared at
 * REPORT-NAME-HEADER             115   app/cpy/CVTRA07Y.cpy lines 4 to 13
 * TRANSACTION-DETAIL-REPORT      114   app/cpy/CVTRA07Y.cpy lines 15 to 31
 * TRANSACTION-HEADER-1           114   app/cpy/CVTRA07Y.cpy lines 33 to 46
 * TRANSACTION-HEADER-2           133   app/cpy/CVTRA07Y.cpy line 48
 * REPORT-PAGE-TOTALS             112   app/cpy/CVTRA07Y.cpy lines 50 to 54
 * REPORT-ACCOUNT-TOTALS          112   app/cpy/CVTRA07Y.cpy lines 56 to 60
 * REPORT-GRAND-TOTALS            112   app/cpy/CVTRA07Y.cpy lines 62 to 66
 * </pre>
 *
 * <p>Assumptions: every one of the seventeen {@code ST-LINE} bands declared in
 * {@code app/cbl/CBSTM03A.CBL} is natively <b>exactly</b> 80 characters and must not be padded
 * at all. The seventeen are {@code ST-LINE0} through {@code ST-LINE15} together with
 * {@code ST-LINE14A}, whose subordinate pictures each sum to the declared 80 of line 45.
 * Padding one of them would overrun the record; padding a report band is mandatory. This is
 * the reason {@code ReportBandLayouts} and {@code StatementBandLayouts} are two types rather
 * than one: a single holder would present two opposite obligations behind one interface, and
 * the natural next step -- one shared padding helper -- is correct for neither artifact.</p>
 *
 * <p>Assumptions: in {@code app/cpy/CVTRA07Y.cpy} the {@code FILLER} items are <b>content</b>
 * and not padding. All twenty-two of them carry a {@code VALUE}, so each must be emitted with
 * its literal content and none may be blanked. Two of them are single hyphens that join a code
 * to its description, at lines 21 and 25. Those two survive every record in the baseline
 * because line 362 of {@code app/cbl/CBTRN03C.cbl} opens the detail paragraph with an
 * {@code INITIALIZE} of the band, and that statement leaves {@code FILLER} untouched, so the
 * joiners are written once and never cleared. An assembly that rebuilds the band field by
 * field has no such carry-over and must emit those two characters explicitly. The same reading
 * applies to the {@code INITIALIZE} at line 459 of {@code app/cbl/CBSTM03A.CBL}.</p>
 *
 * <p>Assumptions: the two baseline sources order their clauses oppositely, so a reader moving
 * between them cannot rely on one shape. {@code app/cpy/CVTRA07Y.cpy} writes the picture
 * before the value, as at line 5; {@code app/cbl/CBSTM03A.CBL} writes the value before the
 * picture, as at line 69 and throughout its seventeen bands. Any transcription that assumes a
 * single order reads one of the two wrongly.</p>
 *
 * <h2>Not owned here</h2>
 *
 * <p>This package owns no schema, no table, no index and no migration artifact, and it holds
 * no data-definition statement of any kind. The reporting context reads through read-only
 * cross-schema views under a {@code SELECT}-only role, so there is nothing here to define and
 * nothing to write. A mapper reaching for a data-definition statement would be asserting an
 * ownership this context does not have.</p>
 *
 * <p>Assumptions: no mapper here reads the wall clock. Every timestamp a mapper emits is
 * supplied by its caller. {@code com.carddemo.common.time.TimestampFormatter} is deliberately
 * shaped to make that the only option: it exposes no no-argument {@code format()} and no
 * {@code now()}, and each of its entry points takes its time as an argument, either a
 * {@code LocalDateTime} or a {@code Clock} the caller supplies. That shaping is recorded at its
 * own point of use, in lines 147 to 151 of
 * {@code services/common-lib/src/main/java/com/carddemo/common/time/TimestampFormatter.java},
 * which sets out why a self-reading entry point was rejected: such a method cannot be pinned by
 * a test. That is also the reason a business date travels as a parameter instead of being read
 * at the point of use.</p>
 *
 * <p>Assumptions: this package performs no rounding. It formats values at the scale they
 * already carry. Rounding is a decision about a monetary result and belongs where the result
 * is computed; a formatter that also rounded would change a value while claiming to render it,
 * and the change would be invisible in the rendered output.</p>
 *
 * <p>Data-exposure narrowing -- masking a primary account number to its last four characters
 * -- belongs to {@code ReportingDtoMapper} alone, and to none of the byte-exact mappers. The
 * reason is counter-intuitive enough to state outright: <b>no card number is emitted by any
 * byte-exact artifact this package produces</b>. The report detail band at lines 15 to 31 of
 * {@code app/cpy/CVTRA07Y.cpy} carries a transaction identifier, an account identifier, a type
 * code and its description, a category code and its description, a source and an amount, and
 * no card number among them. In {@code app/cbl/CBSTM03A.CBL} the card number appears only in
 * roles that are not output: a table key at line 227, a read-key restore at line 421 and a
 * table populate at line 827, alongside the control-break comparand at line 69. Masking inside
 * a byte-exact mapper would therefore not protect anything, and it would rewrite bytes that the
 * comparison expects untouched, so it would break parity while appearing prudent.</p>
 *
 * <h2>Money arrives in two representations, both exact</h2>
 *
 * <p>On the API side money is {@code com.carddemo.common.money.Money}: a decimal at scale two
 * with half-up rounding, backed by a {@code NUMERIC(p,2)} column in the views and serialised
 * as a JSON <b>string</b> by the shared Jackson module. The string is not fastidiousness. A
 * JSON number is parsed into IEEE-754 binary floating point by most clients, which destroys
 * exactness at the boundary the user actually sees, and the loss is silent because the value
 * still looks like money. On the artifact side the same amount is emitted through the COBOL
 * edit mask instead. Two representations, both exact, and neither derived from the other.</p>
 *
 * <p>Assumptions: the two view scales are distinct and must never be unified. A transaction
 * amount is {@code NUMERIC(11,2)}, from the picture {@code S9(09)V99} declared at line 29 of
 * {@code app/cpy/COSTM01.CPY} and at line 10 of {@code app/cpy/CVTRA05Y.cpy}. An account
 * balance is {@code NUMERIC(12,2)}, from {@code S9(10)V99} at line 7 of
 * {@code app/cpy/CVACT01Y.cpy}. Widening the transaction amount to match the balance would
 * accept a value the baseline cannot hold, and narrowing the balance to match the amount would
 * reject one it does hold, so a single shared precision is wrong in one direction whichever
 * one is picked.</p>
 *
 * <h2>Why every citation names its owning copybook</h2>
 *
 * <p>Assumptions: a data name in this corpus does not identify a field on its own, so every
 * citation in this package names the copybook that declares it. The collisions are real, not
 * hypothetical. {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD} are declared at lines 6 and 7 of
 * {@code app/cpy/CVTRA05Y.cpy} and again at lines 6 and 7 of {@code app/cpy/CVTRA04Y.cpy}.
 * {@code TRAN-CAT-KEY} is six characters at line 5 of {@code app/cpy/CVTRA04Y.cpy} and
 * seventeen at line 5 of {@code app/cpy/CVTRA01Y.cpy} -- the same name, a different width, and
 * a silent field shift for anyone who resolves it by name alone. The same two-character type
 * code is called {@code TRAN-TYPE} at line 5 of {@code app/cpy/CVTRA03Y.cpy} but
 * {@code TRAN-TYPE-CD} at line 6 of {@code app/cpy/CVTRA04Y.cpy}. The baseline itself
 * disambiguates rather than trusting the name: {@code app/cbl/CBTRN03C.cbl} qualifies with an
 * explicit record name at lines 189, 191, 193, 365 and 367. A single global field-name map is
 * therefore impossible to build correctly, and this package does not attempt one.</p>
 *
 * <p>Assumptions: one control break in the report is named for a different field than the one
 * it tests. The band titled for an account total actually breaks on the card number: line 181
 * of {@code app/cbl/CBTRN03C.cbl} compares the saved value declared at line 137, which is
 * sixteen characters wide, against the card number of the current record. An assembly that
 * broke on the account identifier because the band's title says so would group rows
 * differently and would emit a different number of total lines.</p>
 *
 * <h2>What the parity oracle covers, and what it does not</h2>
 *
 * <p>Coverage is uneven across the programs this package migrates, and both halves are worth
 * stating. The three batch programs -- {@code app/cbl/CBTRN03C.cbl},
 * {@code app/cbl/CBSTM03A.CBL} and {@code app/cbl/CBSTM03B.CBL} -- do have golden-master
 * coverage, because lines 40 to 46 of {@code tests/README.md} record that ten of the twelve
 * batch programs run standalone and are fully automatable. Their output can be compared
 * artifact against artifact after timestamp normalisation, which is why byte-exactness is a
 * meaningful obligation here and not an aspiration. {@code app/cbl/CORPT00C.cbl} is an online
 * program and has no such coverage: the same lines, and lines 83 to 85 of the same file,
 * record that the online programs cannot run end to end without a terminal-monitor runtime,
 * so only their extractable field-validation logic is unit-tested. The mask that program
 * declares at its line 77 therefore has to be transcribed from its declaration rather than
 * confirmed against a captured artifact, and it is cited above for exactly that reason.</p>
 *
 * <p>Assumptions: {@code app/cbl/CBSTM03B.CBL} contributes record geometry and no formatting.
 * It is a called subroutine, entered through a parameter area at its line 114, and it performs
 * input and output rather than editing. It is cited in this package for geometry alone, and
 * never as the source of a mask or a band.</p>
 *
 * <p>The graded return-code rubric that the COBOL oracle uses, and the aggregate warn-level
 * result it treats as its green state, belong to that suite alone. They do not cross into this
 * module. This module's gate is binary: the build either has zero violations or it fails, and
 * describing a Java build in the oracle's terms would import a tolerance this side does not
 * have.</p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The band descriptors are declared once, in {@code ReportBandLayouts} and
 * {@code StatementBandLayouts}, rather than restated at each call site, following the house
 * convention that a layout is never duplicated but kept single-sourced. A duplicated layout is not
 * merely untidy: it is the failure mode where two copies of one geometry disagree and the artifact
 * they build shifts by a character.</p>
 *
 * <p>Trade-offs: the mapping in this package is written by hand, and the compromise accepted is
 * verbosity in exchange for a justification carried at each mapping site. A generated mapper would
 * be shorter, and it was rejected because the decisions made here are not mechanical -- which
 * {@code FILLER} is content and which is padding, which of the seven regimes a column takes, which
 * artifact pads and which must not, and which field has its exposure narrowed -- and a generated
 * member has nowhere to record why. The cost is real: more lines to read, and a mapping that has to
 * be updated by hand when a layout changes. The compensating property is that every one of those
 * decisions is auditable at the point where it takes effect.</p>
 *
 * <p>Assumptions: suppression is not available in this package and must not be introduced.
 * {@code config/checkstyle/suppressions.xml} is scoped to generated sources and test fixtures and
 * names a mapper package as something never to suppress, and the rule set enables none of the three
 * in-code suppression filters, so a suppression comment or annotation has no effect here even where
 * one is written. A violation in this package is resolved by documenting the code, not by narrowing
 * the gate. The written convention itself is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 *
 * <h2>Baseline framing</h2>
 *
 * <p>Everything under {@code app/} is reference material and remains byte-identical; no
 * statement in this file describes an edit to it. Where this module's behaviour differs from the
 * baseline's, the difference is entered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which owns that register. This
 * package defines exactly one entry there, {@code D-STMT-HTML-ESCAPING}, and cites the
 * rest. No baseline field is renamed in this package, and every emitted column is named from
 * the field it carries at the width that field declares.</p>
 *
 * <h2>Assumptions: the two statement artifacts treat a markup character differently, on purpose</h2>
 *
 * <p>The one entry this package defines is the reason its two statement mappers are deliberately
 * asymmetric. {@code StatementHtmlMapper} routes every value it embeds through
 * {@code com.carddemo.common.security.HtmlTextEncoder}, because that artifact is opened by a browser
 * where a value carrying a tag opens a tag; {@code StatementTextMapper} routes nothing through it,
 * because that artifact is not markup and is the side a byte comparison against the recorded golden
 * output runs on. A reader who found encoding in one mapper and not the other would reasonably suspect
 * an oversight, so the asymmetry is stated here as well as at both sites: the plain-text file is the
 * complete record, the markup file is the safe rendering of it, and neither is the other's draft.</p>
 */
package com.carddemo.reporting.mapper;
