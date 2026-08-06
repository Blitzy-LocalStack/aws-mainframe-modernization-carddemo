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
 * stopped existing. A trim performed in any of them would be a second implementation of a rule
 * that has exactly one owner here, and nothing in the build would compare the two, so they would
 * diverge and the divergence would surface in returned data rather than in a failing step.</p>
 *
 * <h2>The four concerns that live here and only here</h2>
 *
 * <p>1. Trailing padding is removed on the way out of storage. Two columns of {@code auth.users}
 * are declared with a blank-padded fixed-width character type, {@code user_id CHAR(8)} and
 * {@code user_type CHAR(1)}, so a value shorter than its declared width arrives from PostgreSQL
 * carrying blanks that are record padding rather than data. Only that contractual trailing padding
 * is removed. A leading blank is passed through exactly as stored, because it sits inside the key
 * rather than after it, and removing one would change which row the key denotes. The two name
 * columns are declared {@code VARCHAR(20)}, which stores no padding of its own, so a name read
 * from a column needs no treatment on this path; a name read from a fixed-width record image
 * instead arrives padded out to its declared 20 bytes like every other field in that image, and it
 * is at that record boundary, not at the column, that its padding is contractual.</p>
 *
 * <p>2. Nothing is space-padded on the way into storage. A {@code CHAR(n)} column pads a short
 * value out to its declared width itself, so padding a value here as well would store the padding
 * twice and leave a value longer than the column declares. The failure that follows is quiet
 * rather than loud: an equality comparison against a correctly padded value stops matching, and a
 * key lookup returns no row instead of reporting an error. Write-side handling is therefore the
 * deliberate absence of an operation, which is recorded here precisely because an absent operation
 * leaves nothing in the source for a reader to find and question.</p>
 *
 * <p>3. The record's named padding field is dropped, and the drop is recorded rather than left to
 * inference. {@code app/cpy/CSUSR01Y.cpy} L23 declares {@code 05 SEC-USR-FILLER PIC X(23).}, which
 * is 23 bytes at zero-based offset 57 of an 80-byte record. It exists to pad the 57 bytes the
 * named fields occupy out to the length the dataset stores; no baseline program assigns it, and it
 * has no counterpart in any symbolic map this context renders, so it carries no content for a
 * mapping to preserve. Writing the drop down is what lets a reader comparing this package against
 * the record layout find exactly one unmapped field and see that it was assessed as padding rather
 * than overlooked.</p>
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
 *       {@code userType}. This copybook fixes that field's width and nothing beyond it: it
 *       declares no condition-name level anywhere, so it is not the authority for the two values
 *       the column admits, and reading a value domain out of it would attribute one to a
 *       declaration that does not carry it.</li>
 *   <li>{@code SEC-USR-FILLER PIC X(23)}, L23, zero-based offset 57, 23 bytes, not carried across,
 *       for the reason given as the third concern above.</li>
 * </ul>
 *
 * <p>The layout appears above as a citation and is never restated as a constant here, so the
 * copybook remains the single place a width is declared and a change to the layout is a change to
 * one file. Turning a fixed-width image into fields is the work of the shared codecs under
 * {@code com.carddemo.common.codec}, which this package consumes rather than reimplements; what
 * this package owns is the narrower question of which value reaches a caller, in what shape, and
 * which value never leaves at all.</p>
 *
 * <h2>Why this charter exists</h2>
 *
 * <p>The project's Explainability rule requires a docstring on every module entry point, and in
 * Java the entry point of a package is its package declaration, which only a
 * {@code package-info.java} compilation unit can carry. That makes this file load-bearing rather
 * than decorative. Two Checkstyle modules then divide the obligation, and each enforces only half
 * of it: {@code JavadocPackage} inspects the file set and requires this file to exist in any
 * directory holding an audited source file, while {@code MissingJavadocPackage} inspects the
 * parsed tree and requires the file to carry Javadoc. A charter reduced to a bare package
 * statement therefore satisfies the first module and fails the second, which is why prose is the
 * deliverable here and the file's existence is not. No parameter, return or exception clause
 * appears above, because a package declaration accepts no argument, yields no value and raises
 * nothing, so those elements of the rule have no counterpart to describe. The written convention
 * this block follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never
 * restated. The rationale for each individual mapping decision sits beside the code that
 * implements it rather than in this charter, which is where a reader who has just read that code
 * is standing.</p>
 */
package com.carddemo.auth.mapper;
