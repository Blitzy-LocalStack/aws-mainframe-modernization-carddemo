/**
 * Persistence mapping for the AUTH bounded context: one JPA entity, {@code User}, over the single
 * table {@code auth.users}, derived from a baseline COBOL record through an anti-corruption
 * boundary that this package deliberately does not implement itself.
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
 * <h2>Why this charter exists, and the form it takes</h2>
 *
 * <p>Assumptions: the project Explainability rule requires a docstring on every module entry point,
 * and in Java the entry point of a package is its package declaration, which only
 * {@code package-info.java} can carry -- so this file is load-bearing rather than decorative. Two
 * Checkstyle modules enforce that independently and neither is redundant: {@code JavadocPackage}
 * inspects the file set and requires this file to exist in any directory holding an audited source
 * file, while {@code MissingJavadocPackage} inspects the parsed tree and requires it to carry Javadoc.
 * A charter reduced to a bare package statement satisfies the first and fails the second, which is
 * why prose is the deliverable and the file's mere existence is not.
 *
 * <p>Assumptions: this compilation unit holds one statement, so the rationale the rule's
 * inline-comment half asks for has no adjacent executable line to sit beside and is carried inside
 * this block under the four canonical labels -- the only placement a package makes available. No
 * parameter, return or exception at-clause appears, because a package declaration accepts no
 * argument, yields no value and raises nothing, and {@code NonEmptyAtclauseDescription} would report
 * an invented tag with an empty body; omitting them is therefore the compliant reading of the rule
 * rather than a departure from it. The written convention every block here follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.
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
 * anti-corruption boundary. That boundary is the codecs in
 * {@code com.carddemo.common.codec}, which exist, together with
 * {@code com.carddemo.auth.mapper}, which the plan assigns but which is <b>not yet authored</b> --
 * the qualification is stated here rather than left to the reader because a bare "implemented by"
 * would name a package that cannot be opened. A type in this package sees a decoded value and a
 * column, never a byte range.</p>
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
 */
package com.carddemo.auth.domain;
