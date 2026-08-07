/**
 * Verifies the codec anti-corruption boundary that keeps COBOL record geometry and numeric
 * representation out of the migrated service code.
 *
 * <p><b>Purpose.</b> This package holds the tests for the five classes of
 * {@code com.carddemo.common.codec}, the one place in the migrated Java permitted to know how a
 * reference COBOL record is physically laid out. What is under test is therefore narrower and harder
 * than "the codecs work": it is the boundary itself, in both directions. Inward, every expectation is
 * a byte string, a byte offset, a declared width or a record length transcribed from an immutable
 * reference artifact and cited at the expectation, so a field's geometry or a value's representation
 * cannot change meaning without a named test failing. Outward, nothing past this boundary may see a
 * byte offset, a sign overpunch, a packed nibble, a padding span or a baseline field spelling, so the
 * tests equally pin what the codecs decline to expose. A service reads a scale-bearing decimal, a
 * date and a trimmed string; that it can read nothing else is a tested property and not a
 * convention.</p>
 *
 * <p>Alternatives Considered: asserting round-trip symmetry, which was rejected as the primary form
 * and kept only as a secondary check. A round trip proves that this code base agrees with itself,
 * which is exactly the property a wrong width or a wrong edit mask PRESERVES: encode and decode can
 * share one mistaken assumption and still agree, so a codec can round-trip perfectly while emitting a
 * field one character too wide for the reference program's receiver, or a sign character where COBOL
 * emits a blank. Only a golden byte vector taken from the reference source catches that. Golden
 * vectors are therefore the primary assertion form in this package and round trips are the secondary
 * one.</p>
 *
 * <h2>The five production classes under test</h2>
 *
 * <p>The package under test holds five classes, and each owns one part of the boundary. Their roles
 * do not overlap, so a test belongs to exactly one of them:</p>
 *
 * <pre>
 * class               role at the boundary
 * CopybookLayout      structural layout registry: the record and field geometry every other class reads
 * ZonedDecimalCodec   zoned display codec: one printable digit per byte, sign folded into the last
 * PackedDecimalCodec  packed and binary numeric codec: COMP-3 nibbles and COMP storage widths
 * FixedWidthCodec     fixed-width record composer and dispatcher: whole records, decoded field by field
 * CsvAuthCodec        delimiter-positional authorization CSV codec: the request and the reply payload
 * </pre>
 *
 * <p>Assumptions: every named refusal and every carrier type these tests construct, catch or read is
 * NESTED inside one of those five classes and is not a top-level file of its own, so an import in a
 * test names the enclosing class rather than a type that has no file of its own.</p>
 *
 * <h2>Geometry is declared, never inferred</h2>
 *
 * <p>Assumptions: the normative rule these tests hold the codecs to is that a field's
 * {@code PICTURE} clause together with its {@code USAGE} clause determines its byte geometry, and
 * that nothing infers a width, an offset or a regime from the data in front of it. The two clauses
 * are jointly necessary and neither is sufficient. {@code PIC S9(10)V99} occupies twelve bytes with
 * no usage clause and seven as {@code COMP-3}; {@code PIC 9(03)} occupies three bytes as display and
 * two as {@code COMP}. A test that quoted a picture without its usage would therefore be asserting a
 * width the declaration does not fix.</p>
 *
 * <p>Trade-offs: the descriptors retain structural padding rather than omitting it, and the tests
 * depend on that choice in a way worth stating. Retaining a padding field costs a descriptor entry
 * that no domain object will ever carry, and it is what makes two proofs expressible at all: that a
 * record's fields are contiguous, and that their lengths sum to the declared record length. Omitting
 * padding would leave both unprovable and would also leave an encode unable to rebuild a record's
 * trailing bytes, which matters because the parity oracle compares output byte for byte after
 * timestamp normalisation, so a re-encoded record differing only in its padding is a failed
 * comparison. Dropping the padding is therefore deferred to the domain projection, one layer further
 * out, and each record's trailing pad is named in the descriptor so the drop is recorded rather than
 * implicit.</p>
 *
 * <p>Assumptions: two constructs spelled {@code FILLER} are not padding, and a test that treated
 * them as padding would assert a record longer than the record is. An overlay alias declared as
 * {@code FILLER REDEFINES} advances ZERO bytes, and it recurs in the baseline rather than appearing
 * once: {@code app/cbl/COCRDLIC.cbl} line 254 declares one over a 196-byte field whose overlay is
 * seven repetitions of an eleven, sixteen and one byte triple, which is seven times 28 and therefore
 * exactly 196, so counting it as padding would add a second 196 bytes the record does not contain;
 * {@code app/cbl/CBTRN02C.cbl} line 160 and {@code app/cbl/CBACT04C.cbl} line 151 each declare
 * another over a timestamp. Separately, a {@code FILLER} carrying a {@code VALUE} is CONTENT, because
 * the literal is the data: {@code app/cpy/CVTRA07Y.cpy} declares 22 such items and every one of the
 * 22 carries a value, those literals being the column headings and rule lines of the 133-column
 * report.</p>
 *
 * <p>Assumptions: the converse case occurs too, which is why padding is judged by role and never by
 * name. {@code app/cpy/CSUSR01Y.cpy} line 23 declares {@code 05 SEC-USR-FILLER PIC X(23).}, which is
 * padding in role despite not being spelled {@code FILLER}, and it is declared under its own name
 * because the copybook-is-normative rule permits no renaming beyond the three documented
 * misspellings recorded below.</p>
 *
 * <h2>Four representation regimes, and one exactness rule over all of them</h2>
 *
 * <p>Assumptions: a monetary value is an exact decimal carrying its declared scale at every point in
 * this package, and there is no inexact path anywhere in it. No amount is routed through a machine
 * binary approximation of a decimal fraction, no assertion compares an amount within a tolerance, and
 * a rounding decision is always explicit rather than a property of a numeric type. The rule is stated
 * here once because it is the property whose violation is silent: an amount that is wrong in its
 * cents is still a plausible amount, and it survives every check except an exact one.</p>
 *
 * <p>Assumptions: four representation regimes reach these tests, they are genuinely distinct, and a
 * vector written for one is wrong for another. The regime is always taken from the declaration and
 * never guessed from the bytes:</p>
 *
 * <pre>
 * regime               declared as                          what a test asserts about it
 * zoned display        PIC S9(n)V99, no usage clause        one printable digit per byte, sign in the last
 * packed decimal       USAGE COMP-3                         two digits per byte, sign in the low nibble
 * binary               USAGE COMP or BINARY                 a width tier by digit count, not one byte each
 * edited display text  PIC +9(10).99, PIC -zzzzzzzzz9.99    a rendered token carrying its own separators
 * </pre>
 *
 * <p>Assumptions: the binary width is a step function of the digit count rather than an arithmetic
 * expression of it, so its boundaries are asserted as named tiers: four digits or fewer occupy two
 * bytes, five through nine occupy four, and ten through eighteen occupy eight. The three tiers are
 * all reachable in one reference record. {@code app/cpy/CVEXPORT.cpy} is the only copybook under
 * {@code app/cpy} declaring a computational usage at all, and it declares seven {@code COMP} fields
 * and four {@code COMP-3} fields inside a single 500-byte image, so that image is the vector for
 * proving a per-field dispatch really is per field.</p>
 *
 * <p>Assumptions: the packed regime reaches persisted target data in exactly one bounded context.
 * The two authorization segment layouts use packed decimal for money throughout, so the authorization
 * schema is where those nibbles land; for the export record they are decoded at the loading edge and
 * the packed bytes are never stored. A test asserting a packed value therefore proves a storage
 * contract in the first case and a transport contract in the second, and the two are not
 * interchangeable.</p>
 *
 * <h2>The eleven base-master contracts, by registry key</h2>
 *
 * <p>Eleven records are transcribed directly from a copybook defining a persistent dataset. These are
 * the contracts a test names, and the left column is the exact key the registry answers to. The record
 * length is a sum of elementary picture widths and never a value parsed from a banner; the key length
 * is carried alongside it because a record length sizes a fixed-width file while a key length is what
 * a keyed read and an indexed load depend on:</p>
 *
 * <pre>
 * registry key   record bytes   key bytes   transcribed from
 * SECUSER                  80           8   app/cpy/CSUSR01Y.cpy
 * ACCOUNT                 300          11   app/cpy/CVACT01Y.cpy
 * CARD                    150          16   app/cpy/CVACT02Y.cpy
 * CUSTOMER                500           9   app/cpy/CVCUS01Y.cpy
 * XREF                     50          16   app/cpy/CVACT03Y.cpy
 * DALYTRAN                350          16   app/cpy/CVTRA06Y.cpy
 * TRAN                    350          16   app/cpy/CVTRA05Y.cpy
 * DISGROUP                 50          16   app/cpy/CVTRA02Y.cpy
 * TRANCAT                  60           6   app/cpy/CVTRA04Y.cpy
 * TRANTYPE                 60           2   app/cpy/CVTRA03Y.cpy
 * TCATBAL                  50          17   app/cpy/CVTRA01Y.cpy
 * </pre>
 *
 * <p>Assumptions: a registry key is not a dataset name, and confusing the two is the likeliest way a
 * test fails to resolve a layout it correctly identified. Nine of the eleven keys above are spelled
 * differently from the dataset name the baseline gives the same record; only {@code DALYTRAN} and
 * {@code TRANTYPE} coincide. A dataset spelling is therefore never a valid argument to the registry,
 * however familiar it reads from a job stream, and the eleven keys above are the only spellings that
 * answer.</p>
 *
 * <p>Assumptions: three further records are DERIVED rather than transcribed, and the distinction is
 * recorded because it is not a shade of meaning. A derived record's geometry is built from a base
 * master, either by appending fields to it or by altering one field's flags, which is what keeps the
 * shared prefix single-sourced:</p>
 *
 * <pre>
 * registry key   record bytes   derived how
 * TRNX                    350   transcribed key structure from app/cpy/COSTM01.CPY, 32-byte key
 * REJECT                  430   DALYTRAN extended with the reject reason fields
 * INTTRAN                 350   TRAN geometry with one timestamp flag altered
 * </pre>
 *
 * <p>Assumptions: there are two elevens here and they are not the same eleven, which is the one
 * counting error this section exists to prevent. The reference registry at
 * {@code tests/helpers/record_codec.py} holds eleven layouts, and there are also eleven base masters,
 * but eight of the registry's entries are base masters and the other three are the derived records
 * above, while three base masters, {@code SECUSER}, {@code TRANCAT} and {@code TRANTYPE}, have no
 * entry in it at all. Reading the two figures as one produces a registry that omits three masters
 * while appearing complete, so provenance is asserted explicitly and never inferred from a count.</p>
 *
 * <h2>The sign convention, and the naming trap inside it</h2>
 *
 * <p>Assumptions: the compiler setting {@code -fsign=EBCDIC} names the TRAILING-SIGN CONVENTION and
 * not the character set of the bytes, and the whole zoned regime rests on that distinction. The
 * convention, the two overpunch alphabets and the naming trap between the two character-set names are
 * stated once on {@code ZonedDecimalCodec} and are not restated here; a test asserts against that one
 * implemented mode, so there is no setting here to get wrong. The shared kernel consumes the
 * seven-bit ASCII fixture bytes the reference invocation produces and performs no conversion through
 * IBM code page 037 in the zoned path.</p>
 *
 * <p>Assumptions: code page 037 does enter the module, at one narrow place that is not this one, and
 * the separation is asserted rather than assumed. The whole-record entry points default to US-ASCII,
 * matching the seed datasets the fixtures derive from, and a code page 037 record is supplied through
 * an explicit charset overload and decoded ONE FIELD AT A TIME. A charset never reaches a packed or a
 * binary span at all, because storage kind is consulted before any charset is used. Decoding a whole
 * record as text is what turns sign bytes and packed nibbles into replacement characters, which is
 * why no path here does it.</p>

 *
 * <h2>Three boundaries a test in this package must not cross</h2>
 *
 * <p>Assumptions: the authorization CSV contract is delimiter-positional and is deliberately
 * INDEPENDENT of the record codec and the layout registry. It reaches neither of them, in either
 * direction, and that independence is a property of the design rather than an accident of the current
 * code: a message is a sequence of tokens separated by a delimiter, so a token's position is its
 * identity and a byte offset means nothing there. A test that reached for a record descriptor to
 * describe a CSV field would assert a coupling the class does not have and would break the moment the
 * message grew a field. The two payloads are an eighteen-field request, declared across lines 19 to 36
 * of {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy}, and a six-field reply, declared across
 * lines 19 to 24 of {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy}.</p>
 *
 * <p>Assumptions: money in that contract is EDITED DISPLAY TEXT and not a byte span, so an expectation
 * about it is a character token. The request declares {@code PIC +9(10).99} at line 27 of
 * {@code CCPAURQY.cpy}, a mask that carries an explicit sign and an embedded decimal point, while the
 * reply is rendered through a zero-suppressing mask that emits leading blanks and no sign character
 * for a positive value. The two are not the same token for the same amount, which is why the encode
 * direction is asserted against the exact mask rather than against a trimmed value.</p>
 *
 * <p>Assumptions: the sensitivity flag on a field descriptor MARKS the field and masks nothing at all.
 * Masking a primary account number to its last four digits, suppressing a card verification value from
 * a response, and encrypting a national or government identifier at rest all belong to the
 * anti-corruption mapper layer in each service, one layer further out. What this package does assert is
 * the narrower property the flag exists for: that a refusal raised here names a length, a position, a
 * field name or a declared constant, and never the characters that arrived, because such a message
 * becomes a log line and the values passing through include primary account numbers. A test asserting a
 * masked VALUE here would be asserting behaviour this package does not implement.</p>
 *
 * <p>Assumptions: the timestamp-normalisation flag likewise MARKS a field and formats nothing. It
 * records which spans a run generates, so the parity comparison knows what to blank before comparing;
 * it never causes a codec to reformat, reorder or normalise content. Producing the exact
 * twenty-six-character timestamp form belongs to the formatter in {@code com.carddemo.common.time}, and
 * a test in this package that asserted a rendered timestamp would be testing that class through the
 * wrong one.</p>
 *
 * <h2>The three baseline misspellings, carried as lineage only</h2>
 *
 * <p>Assumptions: three baseline field names are misspelled, and the descriptors carry them EXACTLY as
 * the baseline declares them, because a descriptor whose names did not match the copybook would no
 * longer be a transcription of it. The baseline declares the name shown in the left column and
 * {@code app/**} is untouched; the target independently chooses the name in the right column for its
 * own database column or Java member, and that naming divergence is registered in
 * {@code docs/architecture/data-model-and-schema-mapping.md}. The correspondence is recorded here so a
 * test's expectation can never be mistaken for a typing error:</p>
 *
 * <pre>
 * baseline name                 declared at                    target name
 * ACCT-EXPIRAION-DATE           app/cpy/CVACT01Y.cpy line 11   expiration_date
 * CARD-EXPIRAION-DATE           app/cpy/CVACT02Y.cpy line  9   expiration_date
 * PA-MERCHANT-CATAGORY-CODE     CIPAUDTY.cpy line 36           merchant_category_code
 * PA-RQ-MERCHANT-CATAGORY-CODE  CCPAURQY.cpy line 28           merchantCategoryCode
 * </pre>
 *
 * <p>Assumptions: the third misspelling has two baseline spellings sharing one target name, and both
 * are listed so that a search for either finds this note. The bare form is declared in the
 * authorization detail segment and the form carrying a request infix in the request payload. NO OTHER
 * field is renamed anywhere in the migration, and that closure is what makes the list auditable: a
 * target name differing from its baseline name and absent from the four rows above is an error rather
 * than a judgement call.</p>
 *
 * <h2>What this package contains, and what it never will</h2>
 *
 * <p>Assumptions: this package holds one test class per production class and this charter, and the
 * one-to-one pairing is the contract rather than any particular file count -- a second test class for
 * one production class has to argue why its subject is not already owned.</p>
 *
 * <p>Trade-offs: the whole-record test continues to exercise the registry through a decode and an
 * encode, so the two classes overlap deliberately rather than by oversight. The overlap is worth its
 * cost because the two prove different things and fail differently. Geometry asserted THROUGH a
 * round trip proves the declaration is consumable but localises poorly -- a gap in any of the fourteen
 * layouts surfaces as one record-level mismatch. Geometry asserted directly names the offending layout
 * and field, and covers layouts for which no whole-record vector is carried here. The registry's
 * refusals remain exercised in both places: at the codec that rejects a malformed descriptor, and at
 * the registry itself for an unknown layout name and a digit count beyond the platform maximum.</p>
 *
 * <p>Assumptions: nothing else belongs here -- no helper, base or parameter-source class, no
 * integration-test class whose name ends in the reactor's integration suffix, no test resource, no
 * copy of a fixture and no nested folder. Parameterised cases are supplied by methods and annotations
 * on the class that consumes them. Every expectation is a literal transcribed from a reference
 * artifact, so no test here reads a clock, a file under {@code app}, an environment variable, a
 * database or a network resource, and the package therefore runs on a machine with no emulator and no
 * COBOL compiler.</p>
 */
package com.carddemo.common.codec;
