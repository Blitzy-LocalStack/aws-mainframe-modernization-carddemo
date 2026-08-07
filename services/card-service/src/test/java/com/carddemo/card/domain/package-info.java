/**
 * Evidence that the card entity cannot hold a plaintext card verification value, and cannot disclose
 * its own key or account through a diagnostic rendering.
 *
 * <h2>What this package asserts, and why it exists at all</h2>
 *
 * <p><b>Purpose.</b> Two classes execute here and both are about one property: the baseline holds
 * {@code CARD-CVV-CD} as three display digits in the clear, at {@code app/cpy/CVACT02Y.cpy} line 7, and
 * the migrated column {@code cvv_encrypted BYTEA} is meant to hold ciphertext instead. A column name and a
 * byte type do not encrypt anything, and the entity's attribute was a bare {@code byte[]} named for what
 * it was supposed to contain -- so those three ASCII bytes were valid state for the column, and the first
 * writer authored would have had nothing to be refused by. {@code EncryptedCvvTest} asserts the envelope
 * framing that makes the plaintext inexpressible, and
 * {@code EncryptedCvvPersistenceBoundaryTest} asserts the same property at the boundary the persistence
 * provider actually uses.</p>
 *
 * <p>Assumptions: the two are separate classes rather than one, because they assert at different levels
 * and a failure should say which. One is about a value type's own contract -- framing, immutability, the
 * length each part is held to, and a rendering that discloses nothing. The other is about the mapping: that
 * the attribute is typed, that the conversion is declared on it, that the read direction refuses plaintext
 * already sitting in the column, and that no public member of the entity accepts raw bytes.</p>
 *
 * <p>Assumptions: neither class starts a database. What decides which values can cross the boundary is the
 * attribute's type and the declared converter, both settled before any statement is issued, so a container
 * would add a dependency without adding evidence. The mapping itself is asserted by reflection instead,
 * because it is the half the converter cases cannot see: they would all pass with the converter detached,
 * and the provider would then store raw bytes on the only path that matters.</p>
 *
 * <p>Assumptions: the plaintext value {@code 123} appears in both classes as the refused input, and its
 * ABSENCE from every refusal message is asserted alongside the refusal. A value reaching one of these
 * branches may be payment data, so a message that quoted it would write the data into the log line
 * reporting its rejection -- which would turn a check into a disclosure.</p>
 *
 * <h2>The diagnostic-rendering property, asserted by the third class</h2>
 *
 * <p><b>Purpose.</b> {@code DiagnosticRenderingTest} covers the one property no schema check reaches.
 * {@link com.carddemo.card.domain.Card} may reach a log line without any call site choosing to put it
 * there -- through a wrapped exception message, a framework's own reporting of a failed flush, or an
 * assertion failure -- so what its rendering carries is a disclosure decision rather than a formatting
 * preference. This context makes that decision unusually consequential: the entity's own primary key is
 * a primary account number, and its second member is the account identifier that the by-account access
 * path returns whole card lists on, so a rendering emitted per returned row accumulates into a
 * searchable copy of the card master and of the cross-reference this context reads but does not own.</p>
 *
 * <p>Assumptions: that property needs a test at all precisely because {@code toString} is invoked
 * implicitly. Nothing in the build fails when the override is deleted, nor when a member is added back
 * into it, and the resulting disclosure appears only in a log file that no other test reads. An assertion
 * on the rendered string is the only mechanism that turns that silent regression into a build failure.</p>
 *
 * <p>Alternatives Considered: asserting the rendering is merely non-blank, or that it opens with the type
 * name. Rejected, because neither assertion can disagree with the defect it exists to prevent: a rendering
 * that emitted every member of the record would satisfy both. The assertions there name the values that
 * must be ABSENT, so they fail exactly when a withheld value returns.</p>
 *
 * <h2>What may not be added here</h2>
 *
 * <p>Assumptions: this package holds tests for the {@code domain} package of this context and nothing
 * else. The context maps exactly one entity, {@code Card}, onto exactly one table, so a test here that
 * needed a second entity would mean a second table had come into existence outside the migration meant to
 * create it. A test needing a live schema belongs to a repository integration test under
 * {@code com.carddemo.card.repository}; a test of the encipherment itself belongs beside the cipher in
 * {@code com.carddemo.card.service}, which is where the key-management client is reached and where this
 * package deliberately does not go.</p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This charter exists because {@code JavadocPackage} in {@code config/checkstyle/checkstyle.xml} audits
 * any directory holding a source file the gate processes, and that gate includes test sources. Parameters,
 * return values and exceptions are inapplicable to a package declaration rather than omitted from it:
 * there is no callable member here to document. The inapplicability is stated because a docstring that
 * silently omits them is one of the patterns user-specified Rule 1 forbids, and a reader has to be able to
 * tell a declared inapplicability from an oversight.</p>
 */
package com.carddemo.card.domain;
