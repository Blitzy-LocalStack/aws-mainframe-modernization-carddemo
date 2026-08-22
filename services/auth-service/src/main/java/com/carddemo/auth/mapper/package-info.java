/**
 * Anti-corruption boundary for the AUTH bounded context, and the sole place within it where the
 * representation concerns of a fixed-width mainframe record are allowed to appear.
 *
 * <h2>Purpose</h2>
 *
 * <p>A type in this package converts between the persistence entity of
 * {@code com.carddemo.auth.domain} and the request and response records of
 * {@code com.carddemo.auth.dto}. It is the one package in this bounded context permitted to know
 * that the user data it carries originated in an 80-byte fixed-length record: declared field
 * widths, blank padding to the right of a short value, and a named padding field that no baseline
 * program addresses are admitted here, and are admitted nowhere else.</p>
 *
 * <h2>Containment, stated as a prohibition on every other package</h2>
 *
 * <p>What this charter is worth is what it relieves the rest of the context of, so the boundary is
 * named from the outside in. {@code com.carddemo.auth.api} receives and returns values that are
 * already the width a caller sees. {@code com.carddemo.auth.service} compares and branches on
 * those values without first stripping anything from them.
 * {@code com.carddemo.auth.repository} issues keyed and keyset queries whose bind values need no
 * adjustment. {@code com.carddemo.auth.dto} declares transport shapes and never a byte position.
 * And {@code com.carddemo.auth.domain} maps a field to a column, never a field to a byte range.
 * Each of those five stays clean because this package absorbs the concern, not because the concern
 * stopped existing.</p>
 *
 * <p>Alternatives Considered: letting each of those five packages trim what it happened to need, which
 * is the arrangement that requires no boundary at all and no charter to describe one. It is rejected on
 * a specific consequence rather than on tidiness. A trim performed in any of them would be a second
 * implementation of a rule that has exactly one owner here, nothing in the build compares two
 * implementations of it, and the two disagree the moment one of them is changed -- so the divergence
 * arrives as returned data that is right on one path and wrong on another, never as a failing step. One
 * owner costs an extra hop through this package on every conversion and buys a single place a reviewer
 * has to read to know what the trim rule is.</p>
 *
 * <h2>The four concerns that live here and only here</h2>
 *
 * <p>1. Trailing padding is removed on the way out of storage. Two columns of {@code auth.users}
 * are declared with a blank-padded fixed-width character type, {@code user_id CHAR(8)} and
 * {@code user_type CHAR(1)}, so a value shorter than its declared width arrives from PostgreSQL
 * carrying blanks that are record padding rather than data. Only that contractual trailing padding
 * is removed. A leading blank is passed through exactly as stored. The two name
 * columns are declared {@code VARCHAR(20)}, which stores no padding of its own, so a name read
 * from a column needs no treatment on this path; a name read from a fixed-width record image
 * instead arrives padded out to its declared 20 bytes like every other field in that image, and it
 * is at that record boundary, not at the column, that its padding is contractual.</p>
 *
 * <p>Assumptions: a trailing blank on either of those two columns is padding the storage engine added
 * and never a character an operator supplied, and the whole asymmetry rests on that. Treat one as data
 * instead and an eight-character identifier column answers {@code "USER01  "} where the caller sent
 * {@code "USER01"}: the two compare unequal in Java, a keyset cursor built from the padded form no
 * longer addresses the row it was read from, and a rendered value carries two blanks a client then
 * stores. The converse case is why only the trailing side is touched -- a LEADING blank sits inside the
 * key rather than after it, so stripping one would change which row the key denotes, which is a wrong
 * row returned rather than a wrong width.</p>
 *
 * <p>2. Nothing is space-padded on the way into storage. Write-side handling is the deliberate absence
 * of an operation, which is recorded here precisely because an absent operation leaves nothing in the
 * source for a reader to find and question.</p>
 *
 * <p>Alternatives Considered: padding a short value out to the column width here, which is the
 * symmetry a reader arriving from the trim on the read side will expect and is what a fixed-width
 * record image genuinely requires. It is rejected because the two destinations are not the same: a
 * {@code CHAR(n)} column pads a short value out to its declared width ITSELF, so padding here as well
 * stores the padding twice and leaves a value longer than the column declares. The failure that
 * follows is quiet rather than loud -- an equality comparison against a correctly padded value stops
 * matching and a key lookup returns no row instead of reporting an error -- so the defect surfaces as
 * a user who cannot be found rather than as an insert that was refused.</p>
 *
 * <p>3. The record's named padding field is dropped, and the drop is recorded rather than left to
 * inference. {@code app/cpy/CSUSR01Y.cpy} L23 declares {@code 05 SEC-USR-FILLER PIC X(23).}, which
 * is 23 bytes at zero-based offset 57 of an 80-byte record. Writing the drop down is what lets a
 * reader comparing this package against the record layout find exactly one unmapped field and see
 * that it was assessed as padding rather than overlooked.</p>
 *
 * <p>Assumptions: those 23 bytes are structural padding and carry no value, and three independent
 * observations of the baseline are what that rests on. The field exists to pad the 57 bytes the named
 * fields occupy out to the 80 the dataset stores; no baseline program assigns it; and it has no
 * counterpart in any symbolic map this context renders. Carry it forward on the strength of "the
 * record declares it" and the consequence is a member and a column holding 23 blanks on every row,
 * which a later reader cannot distinguish from a value that is merely always blank -- so it acquires
 * validation, a width constraint and a place in a response body, and the migration has published a
 * field with no meaning as part of its contract. The transformation rule this follows requires the
 * drop to be recorded per record, and this paragraph is that record for this one.</p>
 *
 * <p>4. No credential crosses this boundary in either direction, and one field crosses it that the
 * baseline record does not declare. {@code CSUSR01Y.cpy} L21 declares
 * {@code 05 SEC-USR-PWD PIC X(08).} at zero-based offset 48, and the baseline holds that field in
 * the clear and compares it in application code. The baseline does that; the Java encodes
 * authentication as the responsibility of a managed identity provider and keeps only a subject
 * reference, the column {@code cognito_sub} mapped as a {@code UUID cognitoSub}; the divergence is
 * documented. Two consequences bind every mapping written here. There is no field for a mapping to
 * read that value from or write it to, in either direction and under any name. And the subject
 * reference is the one member on this boundary with no baseline field behind it, so its value
 * originates with the identity provider rather than with the record, which is why no byte position
 * is cited for it below.</p>
 *
 * <p>Refactoring Rationale: the alternative was the faithful one -- carry {@code SEC-USR-PWD} across as
 * an eight-character member and an eight-character column, so that the record maps field for field --
 * and it is the single place in this context where parity with the baseline is declined on purpose. What
 * carrying it forward would have meant is concrete. Every read path through this package would hold a
 * live credential in an object that a response serialiser, a diagnostic rendering or a log statement can
 * reach, so the eight characters would leave the boundary the first time anyone added a member to a
 * response or a value to a message; the eight-byte width would cap a real password at eight characters
 * for the migrated system as well as the reference one; and comparing the value in application code
 * would keep verification here rather than at the provider that owns it, which is what makes a rotation,
 * a lockout or a password policy something this service would have to implement. Keeping only the
 * subject reference removes the field rather than protecting it: there is no value to leak, to bound or
 * to compare, and the mapping cannot be extended to move one because no member on either side declares
 * it.</p>
 *
 * <p>Assumptions: the subject reference is issued by the identity provider and is meaningless without
 * it, which is why this boundary treats it as an opaque identity and never derives anything from its
 * text. It is the one member here with no byte position, and reading it as though the record were still
 * the authority -- looking for it at some offset, or minting one locally when it is absent -- would
 * produce a row whose reference resolves to no principal, which fails at sign-on rather than at the
 * mapping that invented it.</p>
 *
 * <h2>The normative record</h2>
 *
 * <p>Every width and position above derives from a single declaration,
 * {@code 01 SEC-USER-DATA} at {@code app/cpy/CSUSR01Y.cpy} L17. That copybook is normative under
 * the migration's first transformation rule: a field's picture clause is what decides its
 * PostgreSQL type, its Java type and its byte position, and a dropped filler is recorded per
 * record. The record declares six elementary fields across L18 to L23, and because
 * 8 + 20 + 20 + 8 + 1 + 23 sums to exactly 80, every declared byte is accounted for and no field
 * escapes examination. That sum is the only cross-check available, because the copybook carries no
 * record-length banner to compare it against:</p>
 *
 * <ul>
 *   <li>{@code SEC-USR-ID PIC X(08)}, L18, zero-based offset 0, 8 bytes, mapped to
 *       {@code userId}.</li>
 *   <li>{@code SEC-USR-FNAME PIC X(20)}, L19, zero-based offset 8, 20 bytes, mapped to
 *       {@code firstName}.</li>
 *   <li>{@code SEC-USR-LNAME PIC X(20)}, L20, zero-based offset 28, 20 bytes, mapped to
 *       {@code lastName}.</li>
 *   <li>{@code SEC-USR-PWD PIC X(08)}, L21, zero-based offset 48, 8 bytes, not carried across in
 *       either direction, for the reason given as the fourth concern above.</li>
 *   <li>{@code SEC-USR-TYPE PIC X(01)}, L22, zero-based offset 56, 1 byte, mapped to
 *       {@code userType}. Assumptions: this copybook fixes that field's width and nothing beyond
 *       it, because it declares no condition-name level anywhere. It is therefore not the
 *       authority for the two values the column admits, and citing it as one would attribute a
 *       value domain to a declaration that does not carry it -- so a reader auditing where
 *       {@code 'A'} and {@code 'U'} are enforced would be sent to a file that never mentions
 *       them, instead of to the check constraint and the group mapping that do.</li>
 *   <li>{@code SEC-USR-FILLER PIC X(23)}, L23, zero-based offset 57, 23 bytes, not carried across,
 *       for the reason given as the third concern above.</li>
 * </ul>
 *
 * <p>Alternatives Considered: declaring those six widths as constants in this package, so that a
 * mapping could name a width instead of a reader having to open the copybook. Rejected because the
 * copybook is normative under the migration's first transformation rule, and a second declaration of a
 * normative width is a second thing to change: the two agree until one is edited, after which the
 * mapping pads or narrows to a width the record does not have and the record image it produces is
 * rejected by whatever reads it. The layout therefore appears above as a citation only, so a change to
 * the layout stays a change to one file. Turning a fixed-width image into fields is the work of the
 * shared codecs under {@code com.carddemo.common.codec}, which this package consumes rather than
 * reimplements; what this package owns is the narrower question of which value reaches a caller, in
 * what shape, and which value never leaves at all.</p>
 *
 * <h2>Why this charter exists</h2>
 *
 * <p>The project's Explainability rule requires a docstring on every module entry point, and in
 * Java the entry point of a package is its package declaration, which only a
 * {@code package-info.java} compilation unit can carry. That makes this file load-bearing rather
 * than decorative.</p>
 *
 * <p>Assumptions: two Checkstyle modules divide that obligation and each enforces only half of it,
 * which is why prose is the deliverable here and the file's existence is not.
 * {@code JavadocPackage} inspects the file set and requires this file to exist in any directory
 * holding an audited source file, while {@code MissingJavadocPackage} inspects the parsed tree and
 * requires the file to carry Javadoc. A charter reduced to a bare package statement therefore
 * satisfies the first module and fails the build on the second -- so the file cannot be reduced to a
 * placeholder that keeps the directory compliant while explaining nothing.</p>
 *
 * <p>Assumptions: no parameter, return or exception clause appears above, and the absence is declared
 * rather than left to be noticed. A package declaration accepts no argument, yields no value and
 * raises nothing, so three of the four docstring elements the rule enumerates have no subject in this
 * compilation unit; a fabricated at-clause would be an empty one, which the ruleset reports. The
 * written convention this block follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path
 * and never restated.</p>
 *
 * <p>Trade-offs: the rationale for each individual mapping decision sits beside the statement that
 * implements it rather than in this charter, and this file carries only what is true of the boundary as
 * a whole. The cost is that a reader wanting one mapping's reasoning has to open the mapper; what it
 * buys is that the reasoning is where a reader who has just read that code is standing, rather than in
 * a charter that would have to be re-read and re-checked whenever a single conversion changed.</p>
 */
package com.carddemo.auth.mapper;
