/**
 * Persistence mapping for the AUTH bounded context: one JPA entity, {@code User}, over the single
 * table {@code auth.users}, derived from a baseline COBOL record through an anti-corruption
 * boundary that this package deliberately does not implement itself.
 *
 * <h2>Target contract, not an inventory of this directory</h2>
 *
 * <p>Assumptions: the entity named above and described below is this package's <b>target
 * contract</b> as the migration plan assigns it, not a listing of the files that happen to sit
 * beside this one. The migration lands its artifacts in plan order and this charter is authored
 * first, so at the checkpoint that authored it this directory holds this charter and nothing else.
 * {@code User} is consequently <b>planned</b> rather than absent, and every statement below about
 * what it may and may not carry reads as a constraint on whoever authors that file, not as a
 * description of code already present.</p>
 *
 * <p>Alternatives Considered: withholding this charter until {@code User} exists. Rejected,
 * because the charter is exactly what the author of that entity works from -- which table it may
 * map, which two declared fields of the baseline record it may not carry across, which annotations
 * are out of bounds -- so writing it afterwards would leave the package unconstrained during the
 * one interval in which the constraint is what decides the code. The accepted cost is that an
 * inventory authored first reads as present tense unless the distinction is declared, and
 * declaring it is the whole of what this section does.</p>
 *
 * <h2>Purpose</h2>
 *
 * <p>The package root is {@code com.carddemo.auth} and {@code domain} is its persistence layer. A
 * type here is a mapping from a Java object onto a physical row and nothing else. It carries no
 * business rule, because a transcribed COBOL paragraph becomes a named method in
 * {@code com.carddemo.auth.service}; it carries no request or response shape, because those live
 * in {@code com.carddemo.auth.dto}; and it carries no query, because keyed and paged access lives
 * in {@code com.carddemo.auth.repository}. Exactly one decision is left for a type here to make,
 * and constraining that decision is why this charter exists: which physical column, of which type
 * and width, in which schema, each field of the baseline user record lands on.</p>
 *
 * <p>The single entity is {@code User}, mapping {@code auth.users}. That table is created by
 * {@code services/auth-service/src/main/resources/db/migration/V1__auth.sql} and is the only
 * object this bounded context owns. It is five columns wide: {@code user_id CHAR(8)} as the
 * primary key, {@code first_name} and {@code last_name} as {@code VARCHAR(20)},
 * {@code user_type CHAR(1)} constrained to the two admitted values, and
 * {@code cognito_sub UUID NOT NULL UNIQUE}. Four of the five are transcribed from the baseline
 * record; the fifth has no baseline counterpart and exists to link a local row to the managed
 * identity provider that authenticates the person it describes.</p>
 *
 * <p>Assumptions: the schema itself is created neither here nor by that migration. Schema, role
 * and grant bootstrap belongs to {@code data-migration/sql/V0__schemas_and_roles.sql}, so every
 * mapping in this package assumes the {@code auth} schema already exists by the time the migration
 * runs and never attempts to create it.</p>
 *
 * <h2>How this charter meets the four docstring elements</h2>
 *
 * <p>The user-specified Explainability rule -- the single rule governing this project, and the
 * reason this file exists at all -- attaches at its line 15 the docstring duty to every module
 * entry point, and in Java a package declaration is that entry point and can carry a docstring
 * only inside a {@code package-info.java}. The Javadoc block is therefore not decoration on this
 * file; it is the file's entire reason to exist, and a bare {@code package} statement would be a
 * failure rather than a minimum. That rule enumerates four docstring elements at its lines 18 to
 * 21 and names a docstring omitting any of them among its forbidden patterns at its line 39, so a
 * reader has to be able to tell a declared inapplicability from an oversight:</p>
 *
 * <ul>
 *   <li><b>Purpose</b> is the section above.</li>
 *   <li><b>Parameters</b> -- a package accepts none, so the element is carried by the external
 *       contracts every mapping here is bound to and may not restate: the copybook layout that
 *       fixes each field's declared width, the migration that creates the table and its columns,
 *       and the schema bootstrap that must already have run. Those are the sections on the record
 *       this mapping derives from and on the anti-corruption boundary.</li>
 *   <li><b>Return values</b> -- a package yields none, so the element is carried by what this
 *       package exposes to its consumers: one mapping over one owned table, and four attributes
 *       that deliberately do not exist on it. That is the section on what this package does not
 *       hold.</li>
 *   <li><b>Exceptions</b> -- a package raises none. The element is carried by the two failure
 *       modes a reader has to plan for, both named in the sections below: an attribute mapped onto
 *       a column the migration does not create, which fails on first use rather than in review;
 *       and an import that crosses a layering boundary, which fails the architecture test instead
 *       of reaching production.</li>
 * </ul>
 *
 * <p>Assumptions: the mapping above is declared rather than left implicit because inventing
 * at-clauses instead would be worse than useless. Javadoc has no parameter, return or exception
 * concept for a package, and the repository rule set audits at-clause bodies for emptiness, so a
 * fabricated clause would either be discarded by the tool or reported by it. This charter
 * consequently carries no at-clause of any kind, and none for authorship, availability or revision
 * either, neither of which the rule nor the house convention asks for.</p>
 *
 * <h2>The record this mapping is derived from</h2>
 *
 * <p>The baseline record is {@code 01 SEC-USER-DATA}, declared at {@code app/cpy/CSUSR01Y.cpy}
 * lines 17 to 23. Assumptions: the copybook is normative -- a field's picture clause is what
 * decides its column type, its Java type and its byte position, so the list below is the
 * specification this mapping answers to and not a provenance note appended after the fact. The
 * record is 80 bytes wide and declares six fields, and because 8 + 20 + 20 + 8 + 1 + 23 sums to
 * exactly 80, every declared byte is accounted for and no field is left unexamined:</p>
 *
 * <dl>
 *   <dt>{@code SEC-USR-ID PIC X(08)}, line 18, zero-based offset 0</dt>
 *   <dd>Becomes {@code user_id}, the primary key, as {@code CHAR(8)} and not a varying-width type.
 *       Assumptions: the declared width is part of a fixed-length record contract that the extract
 *       loader, the screen field and the request-shape length validation all depend on
 *       simultaneously. A varying-width column would accept a ninth character without complaint
 *       and would quietly stop being the same contract, which is a divergence that surfaces in the
 *       data rather than in a build.</dd>
 *
 *   <dt>{@code SEC-USR-FNAME PIC X(20)}, line 19, zero-based offset 8</dt>
 *   <dd>Becomes {@code first_name} as {@code VARCHAR(20)}.</dd>
 *
 *   <dt>{@code SEC-USR-LNAME PIC X(20)}, line 20, zero-based offset 28</dt>
 *   <dd>Becomes {@code last_name} as {@code VARCHAR(20)}. Trade-offs: the two name columns vary in
 *       width while the identifier above is fixed, and the inconsistency is deliberate. Trailing
 *       blanks on a name are record padding rather than name data, so preserving them would make
 *       every comparison and every rendered value carry filler the baseline never intended as
 *       content; the identifier's blanks, by contrast, are inside a key whose width other systems
 *       rely on.</dd>
 *
 *   <dt>{@code SEC-USR-PWD PIC X(08)}, line 21, zero-based offset 48</dt>
 *   <dd><b>Not carried across.</b> This is the divergence recorded first in the next section.</dd>
 *
 *   <dt>{@code SEC-USR-TYPE PIC X(01)}, line 22, zero-based offset 56</dt>
 *   <dd>Becomes {@code user_type} as {@code CHAR(1)}. Assumptions: this copybook fixes the field's
 *       WIDTH and nothing beyond it. It declares no condition-name level anywhere, so it is not
 *       the authority for the administrator and ordinary-user values the column admits; that
 *       authority is {@code app/cpy/COCOM01Y.cpy} lines 26 to 28, where the two condition names
 *       carry the values themselves. The distinction is drawn explicitly because reading the value
 *       domain out of this record layout would attribute it to a declaration that does not contain
 *       it, and the resulting check constraint would be a guess wearing a citation.</dd>
 *
 *   <dt>{@code SEC-USR-FILLER PIC X(23)}, line 23, zero-based offset 57</dt>
 *   <dd><b>Not carried across.</b> It is what pads the 57 bytes the named fields occupy out to the
 *       80 bytes the dataset stores. No baseline program assigns it, and it has no named
 *       counterpart in any symbolic map this context renders, so it carries no logical content for
 *       a mapping to preserve. Assumptions: the omission is stated rather than left to inference,
 *       because a reader diffing this mapping against the record layout finds exactly one unmapped
 *       field and needs to know it was assessed as padding rather than overlooked. Carrying it as
 *       an attribute would put 23 blanks on every instance that no migrated code path ever
 *       reads.</dd>
 * </dl>
 *
 * <h2>The anti-corruption boundary, and why it is not in this package</h2>
 *
 * <p>The widths and byte positions listed above are the specification this mapping is derived
 * FROM. They are emphatically not state that this package carries. Every representation concern of
 * the fixed-length record -- byte positions, declared widths, blank padding to the right of a
 * short value, the sign and zone conventions a display-numeric field would use, the dropped
 * padding field and the baseline field-name spellings -- belongs on the far side of an
 * anti-corruption boundary implemented by {@code com.carddemo.auth.mapper} and by the codecs in
 * {@code com.carddemo.common.codec}. A type in this package sees a decoded value and a column,
 * never a byte range.</p>
 *
 * <p>Alternatives Considered: letting the entity hold its own offsets and widths, so that one type
 * describes both the row and the record it came from. Rejected on the precedent the existing test
 * suite sets for exactly this problem: it resolves record layouts through the compiler's copybook
 * include path rather than restating them, on the stated ground that a layout must never be
 * duplicated and must stay single-sourced from {@code app/cpy/}. A second copy of a width inside
 * an entity is a second source of truth that no build compares against the first, so the two drift
 * silently and the drift is discovered in migrated data. Keeping the boundary in the mapper leaves
 * the copybook the only place a width is stated.</p>
 *
 * <p>Trade-offs: the cost of that separation is an extra hop -- a record cannot be turned into a
 * row without passing through a mapper -- and a reader of this package alone cannot see how a
 * field was decoded. The gain is that a change to the physical record layout touches the mapper
 * and the loader, never an entity, and that this charter can name the widths above as citations
 * without making the entity depend on them.</p>
 *
 * <h2>What this package does not hold, and why each absence is a decision</h2>
 *
 * <p>Four attributes are absent from this package by rule rather than by omission. An absence in
 * source is invisible, so each one is named here with the evidence for it; a reader who goes
 * looking for a missing field finds the reason it is missing instead of concluding it was
 * forgotten.</p>
 *
 * <dl>
 *   <dt>No credential attribute, and nothing that could shadow one</dt>
 *   <dd>Refactoring Rationale: this is the one place where parity with the baseline is declined
 *       deliberately instead of preserved, and it is recorded as divergence D-4. The baseline
 *       keeps the credential inside the record itself: {@code app/cpy/CSUSR01Y.cpy} line 21
 *       declares {@code SEC-USR-PWD PIC X(08)}, an eight-character field held in the clear, and
 *       {@code app/cbl/COSGN00C.cbl} line 223 authenticates by comparing that stored field
 *       directly against the value the user typed, as {@code IF SEC-USR-PWD = WS-USER-PWD}. In the
 *       target, authentication is owned by the managed identity provider rather than by this
 *       table, so there is nothing a local credential attribute could be compared against;
 *       {@code cognito_sub} is the whole of the identity linkage the row keeps, and its role is to
 *       resolve an already-authenticated subject to its local authorities. The concrete gain is
 *       that no query, backup, replica or log of this table can disclose a credential, because the
 *       value is not in it to disclose. Trade-offs: the equally concrete cost is that user
 *       administration can no longer read a stored credential back out onto a screen the way the
 *       baseline update path does, so a forgotten credential becomes a reset through the identity
 *       provider instead of a field on a form. Both cited artifacts still declare and use
 *       {@code SEC-USR-PWD} exactly as they always did and the baseline keeps running unchanged;
 *       the Java encodes the managed-provider design instead, and the divergence is written down
 *       here rather than taking effect as a silent drop of a declared field.</dd>
 *
 *   <dt>No version attribute and no optimistic-lock annotation</dt>
 *   <dd>Refactoring Rationale: {@code auth.users} declares no version column, no row-version
 *       column under any other name, and no system column pressed into that role, so there is
 *       nothing for a version attribute on {@code User} to map onto. Declaring one anyway would
 *       make the entity describe a column the migration does not create, and that failure arrives
 *       at first use rather than in review. The absence is evidenced rather than assumed: both
 *       baseline user mutations acquire their lock and release it inside a single task, so no
 *       comparison of a pre-edit snapshot ever crosses user think-time in this context and there
 *       is no baseline concurrency behaviour for a version attribute to reproduce. Assumptions:
 *       the contrast with the account, customer and card entities is what makes this a decision
 *       rather than an oversight. Those flows genuinely do compare a snapshot of a pre-edit record
 *       across the pseudo-conversational gap, which is an optimistic-concurrency check expressed
 *       in COBOL, and they consequently do receive a version attribute and do surface a conflict
 *       as HTTP 409. This context receives neither. The full evidence chain belongs in
 *       {@code User}, beside the field that is absent, which is where a reader hunting for the
 *       missing attribute will be standing.</dd>
 *
 *   <dt>No timestamp, audit or soft-delete attribute</dt>
 *   <dd>Assumptions: the table declares no created, updated, audited or logically-deleted column,
 *       so none of the four has a column to map onto. This package therefore imports nothing at
 *       all from {@code com.carddemo.common.time}: the shared 26-character timestamp formatter
 *       exists for the contexts whose baseline records genuinely declare a timestamp field, and
 *       this record declares none of the six as one. A deletion in this context is a real row
 *       deletion, which matches the baseline delete path rather than introducing a
 *       retained-but-hidden state the baseline never had and no migrated query would filter
 *       on.</dd>
 *
 *   <dt>No monetary attribute, and no numeric type that would approximate one</dt>
 *   <dd>Assumptions: the AUTH bounded context handles no money whatsoever. Its record declares six
 *       character fields and not one display-numeric or packed-decimal amount, so the shared
 *       fixed-point money type and its serialisation module are neither imported here nor
 *       redeclared, and no IEEE-754 binary numeric type appears anywhere in this package. Stating
 *       the prohibition in a package that has no money may read as redundant, and it is written
 *       down anyway for a specific reason: the project-wide invariant is that an amount stays
 *       exact fixed point at every hop, and the cheapest way to breach it is for a context
 *       believed to have no money to acquire one convenient numeric field later, in a package
 *       whose charter never said it could not.</dd>
 * </dl>
 *
 * <h2>Boundaries this package may not cross</h2>
 *
 * <p>Assumptions: {@code common-lib} is the only sibling module this service depends on, and this
 * package imports none of its types. That is not an oversight in a service that does use the
 * shared kernel: the error model, the correlation filter, the paged-response envelope, the
 * group-to-authority converter and the codecs are consumed by the api, service, mapper and config
 * layers, which is precisely why a persistence mapping needs none of them. The shared starters
 * that module exposes are declared optional there and re-declared by this service's own build
 * file, so nothing here relies on a dependency arriving transitively.</p>
 *
 * <p>Three import prohibitions bind every type in this package, and they are enforced mechanically
 * rather than by convention: a {@code domain} type may not import an AWS service client, may not
 * import a web or servlet type, and may not import another service's {@code domain} package. The
 * enforcement lives once, in the architecture test under
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/}.
 * Alternatives Considered: expressing the same three rules as an import-control block in the
 * Checkstyle rule set, or as a local copy of that test inside this service. Both were rejected for
 * one reason: a layering rule duplicated per module can be tightened in one copy and forgotten in
 * the other eight, and no build log would ever report the divergence. A single owner, tested once,
 * is what stops the rule rotting; a local copy would be the second source of truth this charter
 * argues against everywhere else.</p>
 *
 * <p>Trade-offs: every member of the entity in this package is written out by hand, and no
 * accessor is produced for it by an annotation processor. The cost is more lines than a generated
 * accessor pair. The gain is the only one that decides the question under this project's single
 * rule: a generated member cannot carry the docstring the rule requires of it. The provenance note
 * each field needs -- which copybook line, which byte position, which declared width, and for the
 * two fields above why nothing is there at all -- has to be attached to the member itself, and
 * there is no member to attach it to until it is written out.</p>
 *
 * <h2>How this charter is audited</h2>
 *
 * <p>Assumptions: the prose convention these paragraphs follow is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} and the mechanical gate is
 * {@code config/checkstyle/checkstyle.xml} with its companion
 * {@code config/checkstyle/suppressions.xml}, bound to the Maven {@code validate} phase by
 * {@code services/pom.xml} so that it runs before compilation on every local build and not in
 * continuous integration alone. Where the governing rule, that configuration and that standard
 * could be read differently, the rule decides and the other two are read up to it rather than the
 * rule read down to them.</p>
 *
 * <p>Assumptions: two of that rule set's checks bear on this file, and they are a deliberate pair
 * split across the two levels of the configuration. One inspects the directory and asserts that a
 * {@code package-info.java} is present wherever an audited source file sits; the other inspects
 * this comment and asserts that the file carries Javadoc. A file holding nothing but a
 * {@code package} statement satisfies the first and fails the second, which is exactly the outcome
 * the pairing is built to produce. No escape written into this source can waive either finding:
 * the rule set configures a file-based suppression filter and no comment-based or annotation-based
 * one, that filter is set to fail when its companion file is absent rather than to pass having
 * suppressed nothing, and the companion is scoped to generated sources and test fixtures alone, so
 * nothing under a module's main source tree is suppressible at all. The rule's own validation gate
 * at its line 43 is conjunctive: it closes by stating that code missing either the docstring or
 * the decision rationale fails review, so the two obligations are independently fatal and neither
 * one compensates for the other.</p>
 *
 * <p>Assumptions: the justification labels used above -- {@code Alternatives Considered:},
 * {@code Refactoring Rationale:}, {@code Assumptions:} and {@code Trade-offs:} -- are spelled as
 * the governing rule presents them at its lines 31 to 34 and as
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes them: plural, unparenthesised, each closed by
 * a colon, and carrying no emphasis markup. Trade-offs: this file is written in plain seven-bit
 * characters throughout, and phrases drawn from existing repository documentation are paraphrased
 * rather than copied out of it, because some of that prose punctuates with non-breaking hyphens
 * and dashes outside the ASCII range. A label copied from such a line looks correct, greps wrong,
 * and silently fails an audit searching for the canonical spelling; the cost is typographically
 * plainer prose and the gain is that every label and every quoted symbol here is
 * byte-predictable.</p>
 *
 * <p>Assumptions: {@code app/**} is read as this migration's specification and is never modified.
 * Every copybook and program line cited above still declares exactly what it always declared, and
 * the baseline continues to run unchanged; the existing test suite states that policy for the
 * whole tree, naming the COBOL programs, copybooks, job control, maps and seed data as reference
 * material. Nothing in this package is a source of truth for a record layout. The copybook is, and
 * it stays single-sourced there.</p>
 *
 * <p>Assumptions: this file declares a package, imports nothing and annotates nothing, and the
 * emptiness is load bearing. No import statement appears here, and no package-level annotation
 * either -- neither a nullability default nor a persistence-wide setting. An annotation would pull
 * a type into a compilation unit whose only function is documentation, and a persistence-wide
 * default declared here would silently govern every future attribute of the entity from a file
 * nobody opens while editing it. A reader who finds this file short on code has found it
 * correct.</p>
 */
package com.carddemo.auth.domain;
// Alternatives Considered: two cheaper shapes for this file were evaluated, and both fail the
// build rather than merely reading thinner than the charter above. Omitting the file entirely
// fails the directory-level JavadocPackage check, a file-set check that requires a
// package-info.java wherever an audited source file sits, and User.java lands in this directory.
// Providing the file but leaving it comment-free clears that check and then fails
// MissingJavadocPackage, the tree-walking check that requires the file to carry Javadoc. The two
// form an interlock, so satisfying either one alone is the documented failure mode rather than a
// shortcut, and the block above is consequently this file's reason to exist and not ornament on
// it. Independently of both checks, the governing rule attaches the docstring duty to every module
// entry point at its line 15, and in Java a package declaration can carry a docstring only here.
//
// Alternatives Considered: placing this block ABOVE the package declaration, which is where a
// reader would expect a comment about the declaration to sit. Rejected because the position is
// forced rather than stylistic: MissingJavadocPackage recognises a package's documentation only
// when the Javadoc block is the declaration's IMMEDIATELY preceding sibling, so interposing these
// single-line comments between the block above and the declaration made that check report the
// Javadoc as missing outright and failed the build. The two obligations -- a Javadoc block the
// check can bind, and a rationale adjacent to the decision it explains -- are both satisfiable
// only with the prose above the declaration and this block below it. That ordering was established
// by running the gate and reading the violation, not by reasoning about it.
