/**
 * Anti-corruption layer for the card bounded context.
 *
 * <h2>The directory, measured rather than remembered</h2>
 *
 * <p>Two compilation units sit in this directory and no third: this charter, and
 * {@code CardMapper}, which holds the whole of the translation described below.
 * Every inventory, file name, class name and count stated here is a measurement
 * of that directory, and the marker line below is re-measured on every build by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * which counts the {@code .java} files beside this charter and holds the figures
 * to them. A count published this way cannot drift unnoticed, because drifting is
 * what fails the build:</p>
 *
 * <pre>
 * this directory: 2 java files = 1 classes + 1 charter
 * </pre>
 *
 * <p>Assumptions: the marker's wording is the check's and not this charter's, which
 * is why it reads "1 classes" rather than the English singular. The pattern the
 * check matches fixes every token of that line, so rewording it to read naturally
 * would stop it matching, and a marker that no longer matches is not a wrong count
 * -- it is a count nobody measures. The awkward plural is the visible cost of
 * having the figure enforced rather than merely asserted.</p>
 *
 * <p>Assumptions: the roster is closed rather than nominally closed, so a second
 * mapper added beside {@code CardMapper} without amending this charter would be
 * an ungoverned exception to it. Keeping the set closed is what lets the
 * paragraphs below say that these concerns appear here and nowhere else in the
 * module and mean it literally: a mapping written outside this package would
 * move a masking or suppression decision away from the one charter that accounts
 * for it, and the decision would then be enforced by nothing but habit.</p>
 *
 * <p>Purpose: this package translates between the shape of the baseline card
 * record and the shape the rest of card-service works with, and it is the only
 * place in this module where copybook representation concerns are allowed to
 * appear. Everything downstream of it stays free of them:
 * {@code com.carddemo.card.domain.Card} and the request and response records
 * under {@code com.carddemo.card.dto} carry no padding field, no baseline
 * spelling, no unmasked primary account number and no card verification value,
 * because every one of those concerns is resolved here instead.
 * {@code CardMapper} is the single class in this package and it holds the whole
 * of that translation.</p>
 *
 * <p>The concerns that belong here, and nowhere else in this module, are:</p>
 *
 * <ul>
 *   <li>Dropping the 59-byte {@code FILLER} declared at
 *       app/cpy/CVACT02Y.cpy:11. The six data fields of the 150-byte
 *       {@code CARD-RECORD} occupy 91 bytes between them, so that field is
 *       padding to a fixed record length rather than data, and it reaches
 *       neither the entity nor any response.</li>
 *   <li>Reading the field declared at app/cpy/CVACT02Y.cpy:9, whose baseline
 *       name carries a misspelling, into a target member named
 *       {@code expirationDate}. The rename is a target-side naming decision and
 *       nothing more: the baseline tree is reference material that is never
 *       modified and keeps its own spelling, and the pairing is recorded in
 *       docs/architecture/data-model-and-schema-mapping.md so the lineage stays
 *       traceable from either side.</li>
 *   <li>Masking the primary account number to its last four digits on every
 *       response except the administrative card detail route, which is the one
 *       caller authorised to receive it in full.</li>
 *   <li>Suppressing the card verification value completely. Its stored form is
 *       encrypted, and no response body on any route carries it, masked or
 *       otherwise.</li>
 *   <li>Transporting the account identifier and the card number as digits-only
 *       strings rather than as JSON numbers. The baseline treats both the same
 *       way: app/cbl/COCRDSLC.cbl:73-81 declares each of them as a character
 *       field with a numeric {@code REDEFINES} laid over it, so characters are
 *       the transport form and the number is only what arithmetic and
 *       validation ever see. A 16-digit card number can also fall above 2^53,
 *       the largest integer an IEEE-754 double represents exactly, so a client
 *       that parsed it as a JSON number would silently lose trailing digits of
 *       the value it then displays.</li>
 * </ul>
 *
 * <p>That charter is what makes the neighbouring boundary legible.
 * {@code com.carddemo.card.domain.Card} imports nothing beyond
 * {@code jakarta.persistence} and the JDK, not even from
 * {@code com.carddemo.common}: the entity describes storage and nothing else,
 * precisely because masking, suppression and representation conversion are this
 * package's responsibility rather than its own. Reading the two together shows
 * where one ends and the other begins.</p>
 *
 * <p>Alternatives Considered: this layer is hand-written by design, and the two
 * code generators that would conventionally replace it were each rejected for a
 * specific reason. MapStruct was rejected twice over. Its most recent published
 * release is a beta; and, independently of that, the mapping it would generate
 * is not a mechanical field copy, because every step listed above is a
 * judgement rather than a copy: padding is dropped, a baseline field is
 * renamed, the primary account number is masked to four digits, the
 * verification value is suppressed outright and never leaves its encrypted
 * storage form, and two identifiers are carried as digits-only strings rather
 * than as numbers. Each of those needs its justification recorded at the
 * mapping site, and a generated method is the one place that text cannot live.
 * Lombok was rejected because members generated during annotation processing
 * cannot carry the Javadoc that Rule 1 (Explainability) requires at clause L15,
 * so adopting it would leave only two outcomes: a module that fails its own
 * validate phase, or a documentation gate weakened until it ignores generated
 * code. Java 21 record types with explicit constructors give the same brevity
 * while leaving every member somewhere a comment can be attached. Recording the
 * decision once at package scope is deliberate, so that {@code CardMapper} can
 * rely on it rather than restate it and can keep its own comments on the
 * per-site decisions that only it can explain.</p>
 *
 * <p>Assumptions: a reader arriving here from
 * config/checkstyle/suppressions.xml should note that its never-suppress block
 * names this directory. The word mapper invites the assumption that whatever
 * lies beneath it is generated, and so beyond the reach of a documentation
 * gate; the opposite holds. Nothing in this package is generated, and the
 * decisions recorded at its mapping sites exist in no other artifact, which is
 * what makes it the highest-value documentation target in this module rather
 * than a candidate for exemption.</p>
 *
 * <p>Assumptions: the mappings here are held to the baseline by transcription
 * fidelity against the programs cited above and by this module's own tests, not
 * by a recorded output comparison. tests/README.md:83-85 records that the
 * baseline's online programs cannot be exercised end to end without a CICS
 * runtime, so no such comparison exists for any card screen, and none should be
 * claimed for one.</p>
 *
 * <p>Trade-offs: the three remaining docstring elements that Rule 1 names at
 * clauses L19 to L21 are deliberately absent rather than filled in. A package
 * declaration accepts no parameter, returns no value and raises nothing, so no
 * at-clause here could carry a true description, and an empty parameter, return
 * or exception tag added to look complete would both assert something false and
 * be reported by the NonEmptyAtclauseDescription check. The purpose and the
 * decision rationale that clauses L18 and L40 do require are stated above,
 * which is what the gate at clause L43 asks of a file that declares no member.
 * The prose convention is docs/CODE_DOCUMENTATION_STANDARD.md and the
 * mechanical gate is config/checkstyle/checkstyle.xml, whose JavadocPackage and
 * MissingJavadocPackage checks are the two that require this file to exist and
 * to carry this comment.</p>
 */
package com.carddemo.card.mapper;
