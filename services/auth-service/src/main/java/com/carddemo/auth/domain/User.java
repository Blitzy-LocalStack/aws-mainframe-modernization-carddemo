package com.carddemo.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of {@code auth.users}: the local identity record of the AUTH bounded context.
 *
 * <p>This is the only entity the context declares, and it maps the only table the context owns. It
 * carries five persistent members and no sixth. Four of them are transcribed from the baseline
 * security record {@code 01 SEC-USER-DATA}, declared at {@code app/cpy/CSUSR01Y.cpy} lines 17 to
 * 23; the fifth, {@code cognitoSub}, has no baseline counterpart and exists to resolve an
 * already-authenticated subject to the authorities this row describes.</p>
 *
 * <h2>The record this mapping answers to</h2>
 *
 * <p>Assumptions: the copybook is the width authority, and its arithmetic is self-checking. The six
 * declared fields are {@code SEC-USR-ID PIC X(08)} at line 18, {@code SEC-USR-FNAME PIC X(20)} at
 * line 19, {@code SEC-USR-LNAME PIC X(20)} at line 20, {@code SEC-USR-PWD PIC X(08)} at line 21,
 * {@code SEC-USR-TYPE PIC X(01)} at line 22 and {@code SEC-USR-FILLER PIC X(23)} at line 23. Their
 * widths sum to 8 + 20 + 20 + 8 + 1 + 23, which is exactly the 80 bytes the dataset stores, so a
 * width stated here that disagrees with that copybook is wrong by construction rather than by
 * opinion. Two of the six are deliberately absent from this class, and each absence is justified
 * beside the member region below rather than left for a reader to infer.</p>
 *
 * <p>Assumptions: the byte positions above are the specification this mapping was derived FROM, not
 * state it carries. Nothing in this class handles a byte offset, a declared pad, a trailing blank or
 * a sign convention. Those belong to {@code com.carddemo.common.codec} and to the mapper layer, on
 * the far side of the anti-corruption boundary the package charter describes; a member here sees a
 * decoded value and a column.</p>
 *
 * <h2>The physical contract, and which artifact owns it</h2>
 *
 * <p>Assumptions: {@code services/auth-service/src/main/resources/db/migration/V1__auth.sql} creates
 * this table and is the binding authority for every column type and width named in this class. It
 * declares exactly five columns: {@code user_id CHAR(8) PRIMARY KEY}, {@code first_name VARCHAR(20)
 * NOT NULL}, {@code last_name VARCHAR(20) NOT NULL}, {@code user_type CHAR(1) NOT NULL} with a check
 * constraint admitting two values, and {@code cognito_sub UUID NOT NULL UNIQUE}. Where the published
 * interface contract under {@code src/main/resources/openapi/} and that migration could ever
 * disagree, the migration decides, because it is what the database will actually enforce.</p>
 *
 * <p>Assumptions: this class creates nothing. The schema, its roles and its grants come from
 * {@code data-migration/sql/V0__schemas_and_roles.sql}, and the table from the migration named
 * above, so the {@code auth} schema and the table both pre-exist any use of this type. That is why
 * every column mapping below describes an existing definition and none attempts to specify one.</p>
 *
 * <h2>Documentation obligations this class is written against</h2>
 *
 * <p>Assumptions: every member below carries a docstring and every non-obvious decision carries an
 * adjacent rationale under one of four canonical labels, because the project's Explainability rule
 * requires both halves and its validation gate fails work missing either one. The written convention
 * is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated here. The
 * mechanical half is {@code config/checkstyle/checkstyle.xml}, which audits this file at the Maven
 * validate phase and therefore on every local build rather than in CI alone.</p>
 */
// WHY : Assumptions: the annotations below describe a table this class has no authority to create,
//       and that read-only posture is what shapes several choices in this file. Hibernate is
//       configured to validate the mapping against the deployed schema rather than to generate it,
//       so a declared width or JDBC binding that disagrees with V1__auth.sql fails at startup
//       rather than silently reshaping storage. Every @Column below is consequently a description
//       of an existing column, never a specification of a new one.
// WHY : Trade-offs: the class is a mutable JavaBean with a no-argument constructor rather than an
//       immutable value type, and the mutability is a requirement rather than a preference. The JPA
//       provider instantiates an entity reflectively and then populates its members, and it needs
//       write access to a mapped member to do so; the alternative shapes are examined beside the
//       constructors below. The cost is that an instance can be mutated after load, and it is
//       contained by keeping this type free of behaviour: a transcribed COBOL paragraph becomes a
//       method in com.carddemo.auth.service, so no caller reaches for logic here.
@Entity
@Table(name = "users", schema = "auth")
public class User {

    // WHY : Refactoring Rationale: this class declares NO credential member, and that is the one
    //       place where parity with the baseline is declined on purpose rather than preserved. It is
    //       recorded as divergence D-4. The baseline holds the credential inside the record itself:
    //       app/cpy/CSUSR01Y.cpy L21 declares SEC-USR-PWD PIC X(08), an eight-character field in
    //       the clear at zero-based offset 48 of the 80-byte record, and app/cbl/COSGN00C.cbl L223
    //       -- the point line inside the READ-USER-SEC-FILE block that spans L211 to L256 --
    //       authenticates by testing IF SEC-USR-PWD = WS-USER-PWD directly against the value read
    //       out of that record. In the target a managed identity pool performs that comparison, so
    //       the whole class of defect that a stored cleartext credential creates is removed rather
    //       than relocated: there is no local column for a query, a backup, a replica or a log of
    //       this table to disclose, because the value is not in the table at all. cognitoSub below
    //       is the entire identity linkage the row retains.
    // WHY : Refactoring Rationale: the same omission reaches four further baseline sites, named
    //       here so the absence is legible rather than silent. app/cbl/COUSR01C.cbl L157 moves
    //       PASSWDI OF COUSR1AI into SEC-USR-PWD, writing the value to the record on create.
    //       app/cbl/COUSR02C.cbl L169 moves SEC-USR-PWD back into PASSWDI OF COUSR2AI, echoing the
    //       stored value to the terminal on the update screen. app/cbl/COUSR02C.cbl L227 to L229
    //       compares a submitted value against the stored one and marks the row modified when the
    //       two differ. And app/cpy-bms/COUSR02.CPY presents the field on the map twice, as the
    //       input face 02 PASSWDI PIC X(8) at L78 and the output face 02 PASSWDO PIC X(8) at L152.
    //       With no member to carry, none of the four has a target analogue here. Recording them is
    //       what the Explainability rule asks for at its lines 38 to 41, which forbid leaving a
    //       non-obvious choice undocumented while a reasonable alternative exists -- and carrying
    //       the field across unchanged was precisely that alternative.
    // WHY : Trade-offs: the concrete cost is that user administration can no longer read a stored
    //       credential back onto a form the way app/cbl/COUSR02C.cbl L169 does, so a forgotten
    //       credential becomes a reset through the identity provider instead of a screen field. All
    //       six baseline sites still declare and use SEC-USR-PWD exactly as they always did and the
    //       baseline keeps running unchanged; the Java encodes the managed-provider arrangement
    //       instead, and the divergence is documented here and in the traceability register.

    // WHY : Refactoring Rationale: this class declares NO version member and NO optimistic-lock
    //       annotation, because auth.users declares no version column under any name and no system
    //       column pressed into that role. Mapping one anyway would describe a column the migration
    //       does not create, and that failure arrives at first use rather than in review.
    // WHY : Assumptions: the absence rests on three witnesses in the baseline rather than on
    //       inference, and each was read directly. First, app/cbl/COUSR02C.cbl re-reads the record
    //       inside the update action at L215 to L217 and then compares screen input against it; its
    //       EXEC CICS READ at L322 to L331 carries RIDFLD (SEC-USR-ID) at L326 AND the UPDATE
    //       option at L328, so the row is read under an exclusive lock, and the EXEC CICS REWRITE
    //       at L360 to L366 -- DATASET L361, FROM L362, LENGTH L363, RESP L364, RESP2 L365 --
    //       carries neither RIDFLD nor KEYLENGTH because it rewrites the row that read already
    //       holds. Second, app/cbl/COUSR03C.cbl L190 and L191 are literally adjacent statements,
    //       PERFORM READ-USER-SEC-FILE then PERFORM DELETE-USER-SEC-FILE, with no intervening
    //       logic at all; its READ at L269 to L278 likewise carries RIDFLD at L273 and UPDATE at
    //       L275, and its EXEC CICS DELETE at L307 to L311 carries no record identifier for the
    //       same reason. Third, app/csd/CARDDEMO.CSD defines the file itself pessimistically, as
    //       DSNAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS) RLSACCESS(NO) at L89 and UPDATEMODEL(LOCKING)
    //       LOAD(NO) RECORDFORMAT(V) ADD(YES) at L93. No SYNCPOINT appears anywhere in any of the
    //       five auth programs, so each lock is taken and released inside one task and none is ever
    //       held across the pseudo-conversational gap. There is therefore no baseline concurrency
    //       behaviour for a version member to reproduce.
    // WHY : Assumptions: the contrast with the account, customer and card entities is what makes
    //       this a decision rather than an oversight, and those entities genuinely do need what
    //       this one does not. app/cbl/COACTUPC.cbl implements a hand-rolled before-image check
    //       across that same gap: L168 declares 05 WS-DATACHANGED-FLAG PIC X(1), L669 opens the
    //       05 ACUP-OLD-DETAILS snapshot group whose members pair a display field with a numeric
    //       REDEFINES -- ACUP-OLD-ACCT-ID-X PIC X(11) at L671 redefined at L672 to L673,
    //       ACUP-OLD-ACTIVE-STATUS PIC X(01) at L674, ACUP-OLD-CURR-BAL PIC X(12) at L675
    //       redefined as PIC S9(10)V99 at L676 to L677 -- and L521 to L522 names the condition
    //       DATA-WAS-CHANGED-BEFORE-UPDATE with the operator message 'Record changed by some one
    //       else. Please review'. That program also owns a lock-failure vocabulary those flows
    //       need and this one has no analogue for: COULD-NOT-LOCK-ACCT-FOR-UPDATE at L517 to L518,
    //       COULD-NOT-LOCK-CUST-FOR-UPDATE at L519 to L520 and LOCKED-BUT-UPDATE-FAILED at L523 to
    //       L524. Those entities consequently DO receive a version member and DO surface a
    //       conflict as HTTP 409; this one receives neither, because nothing in the five auth
    //       programs contains any equivalent construct.
    // WHY : Assumptions: the four-arm block at app/cbl/COUSR02C.cbl L219 to L234 is not a
    //       concurrency check and is not migrated as one. It compares submitted screen input
    //       against the record read moments earlier at L217 under the L328 UPDATE lock, inside a
    //       single task, so it detects whether the operator actually changed anything. Its
    //       not-modified message at L239, 'Please modify to update ...', asks the operator to make
    //       a change and is the semantic opposite of a conflict report. Reading it as optimistic
    //       concurrency would introduce a 409 outcome the baseline cannot produce.
    // WHY : Trade-offs: without a version member, two concurrent administrative updates to one row
    //       resolve last-writer-wins within the database's row lock instead of one of them being
    //       refused. That is accepted because it is the baseline's own outcome -- the lock is held
    //       only for the duration of the mutation in both designs -- and because inventing a
    //       refusal here would be a new observable behaviour rather than a migrated one.

    /**
     * The eight-character user identifier, the primary key.
     *
     * <p>Transcribed from {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} line 18,
     * zero-based offset 0 of the record, onto {@code user_id CHAR(8) PRIMARY KEY}.</p>
     */
    // WHY : Assumptions: the width of eight is contractual rather than incidental. It is inherited
    //       from SEC-USR-ID PIC X(08) at app/cpy/CSUSR01Y.cpy L18, and V1__auth.sql declares the
    //       column as CHAR(8) PRIMARY KEY to match. Fixed-width character storage is what the
    //       extract loader, the terminal field and the request-length validation all depend on
    //       simultaneously, and a varying-width column would accept a ninth character without
    //       complaint while quietly ceasing to be the same contract -- a divergence that surfaces
    //       in migrated data rather than in a build.
    // WHY : Alternatives Considered: relying on the declared length alone was evaluated and
    //       rejected, because a Java String otherwise selects the JDBC VARCHAR binding and schema
    //       validation then rejects this schema's fixed-character column even though both widths
    //       agree. Spelling the physical type into a columnDefinition was rejected too: that
    //       duplicates vendor DDL inside a mapping which has no authority to create the table, so
    //       the definition would then exist in two places that no build compares. The JDBC type
    //       code selects the standard fixed-character binding for reads, writes and validation
    //       while leaving the physical definition wholly with V1__auth.sql, and it is the same
    //       mechanism the batch, transaction and authorization contexts use for the identical
    //       reason.
    // WHY : Assumptions: updatable is false because a primary key identifies the row rather than
    //       describing it. The baseline agrees at both mutation sites: app/cbl/COUSR02C.cbl L216
    //       moves the identifier in only to locate the record, and the four-arm block that follows
    //       at L219 to L234 compares and replaces the two names and the type and never the
    //       identifier. Renaming a user is therefore a delete and an insert, exactly as it is in
    //       the baseline, rather than an update that would silently orphan any reference.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 8, nullable = false, updatable = false)
    private String userId;

    /**
     * The user's first name.
     *
     * <p>Transcribed from {@code SEC-USR-FNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy} line 19,
     * zero-based offset 8 of the record, onto {@code first_name VARCHAR(20) NOT NULL}.</p>
     */
    // WHY : Assumptions: the two name members are varying-width while the identifier above is
    //       fixed, and the inconsistency is deliberate rather than an oversight. Trailing blanks on
    //       a name are record padding, not name data, so preserving them would make every
    //       comparison and every rendered value carry filler the baseline never intended as
    //       content. The identifier's blanks sit inside a key whose exact width other systems rely
    //       on, which is why only it keeps fixed-character semantics. Trimming itself happens in
    //       the mapper, never here.
    // WHY : Assumptions: nullable is false because the fixed-length record cannot represent an
    //       absent name -- a 20-byte field is always present and at worst all blanks -- so a null
    //       would be a state with no baseline counterpart. Emptiness is a validation concern of the
    //       request shape and the service chain, not a storage concern of this member.
    @Column(name = "first_name", length = 20, nullable = false)
    private String firstName;

    /**
     * The user's last name.
     *
     * <p>Transcribed from {@code SEC-USR-LNAME PIC X(20)} at {@code app/cpy/CSUSR01Y.cpy} line 20,
     * zero-based offset 28 of the record, onto {@code last_name VARCHAR(20) NOT NULL}.</p>
     */
    @Column(name = "last_name", length = 20, nullable = false)
    private String lastName;

    /**
     * The one-character user type, admitting an administrator or an ordinary user.
     *
     * <p>Transcribed from {@code SEC-USR-TYPE PIC X(01)} at {@code app/cpy/CSUSR01Y.cpy} line 22,
     * zero-based offset 56 of the record, onto {@code user_type CHAR(1) NOT NULL}.</p>
     */
    // WHY : Assumptions: app/cpy/COCOM01Y.cpy is the SOLE authority for the two admissible values,
    //       and the distinction matters because the obvious citation is the wrong one. That file
    //       declares 10 CDEMO-USER-TYPE PIC X(01) at L26 and names the domain immediately beneath
    //       it, as 88 CDEMO-USRTYP-ADMIN VALUE 'A' at L27 and 88 CDEMO-USRTYP-USER VALUE 'U' at
    //       L28, with both literals quoted because the parent picture is character. The record
    //       layout at app/cpy/CSUSR01Y.cpy L22 declares the same one-character width and contains
    //       no condition-name level anywhere in the file, so it settles the width and cannot settle
    //       the domain; citing it for the value set would name a declaration that does not contain
    //       one. The two are bridged at run time by app/cbl/COSGN00C.cbl L227, which moves
    //       SEC-USR-TYPE into CDEMO-USER-TYPE, and the condition name is then actually tested at
    //       L230 to choose the administrator path -- so the record supplies the value and the
    //       commarea copybook supplies its meaning.
    // WHY : Assumptions: the same file demonstrates the hazard that makes the quoting worth
    //       stating. Its L30 and L31 declare 88 CDEMO-PGM-ENTER VALUE 0 and 88 CDEMO-PGM-REENTER
    //       VALUE 1 with BARE numerics, because that parent picture at L29 is numeric rather than
    //       character. app/cbl/COACTUPC.cbl L169 and L170 show the converse independently, quoting
    //       '0' and '1' under a PIC X(1) parent. A one-character value under a character picture is
    //       therefore a quoted literal, which is why the two values this member admits are
    //       characters and not digits.
    // WHY : Alternatives Considered: a Java enumeration over the two values was evaluated and
    //       rejected, so this member stays a one-character String. The declared width is part of
    //       what the contract exposes -- SEC-USR-TYPE occupies exactly one position, and the
    //       published interface declares a minimum length of one, a maximum of one and the
    //       two-member value set -- and a one-character String reproduces all of that without
    //       introducing a type the contract does not name. The domain is already enforced three
    //       times over: by the request-shape constraint in com.carddemo.auth.dto, by the ordered
    //       validation chain in com.carddemo.auth.service that decides which field reports the
    //       first error, and by CHECK (user_type IN ('A','U')) on the table, which holds for every
    //       write path including one that never passes through this service. An enumeration here
    //       would add a fourth spelling of the same domain without closing any route to storage,
    //       and mapping it would need a converter whose failure mode on an unexpected byte is an
    //       exception in the persistence layer rather than a reportable validation error.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_type", length = 1, nullable = false)
    private String userType;

    /**
     * The identity provider's subject reference for this user.
     *
     * <p>This member has no baseline counterpart. It maps
     * {@code cognito_sub UUID NOT NULL UNIQUE}, the fifth column the migration adds, and it is the
     * whole of the identity linkage the row retains.</p>
     */
    // WHY : Alternatives Considered: storing a hashed credential in a local column was evaluated
    //       and rejected, and it is the alternative that makes this member a decision rather than
    //       an addition. Hashing would certainly improve on the cleartext field at
    //       app/cpy/CSUSR01Y.cpy L21, but it would retain a credential-bearing column in this
    //       table and with it the whole responsibility for credential handling -- rotation, work
    //       factor, reset, lockout and comparison -- inside a service whose subject is user
    //       administration. Delegating authentication to the managed identity pool removes the
    //       column and that responsibility together, and a subject reference is the minimum needed
    //       to resolve an authenticated principal to this row's authorities.
    // WHY : Assumptions: the column is NOT NULL and UNIQUE, so the mapping between a provider
    //       subject and a local row is total and one-to-one, which makes this a natural alternate
    //       key for the lookup the sign-on path performs. A nullable subject would describe a row
    //       no token could ever reach, and a duplicated one would resolve a single token to
    //       conflicting authorities.
    // WHY : Alternatives Considered: declaring this member as a String was rejected in favour of
    //       java.util.UUID. The database column is a native uuid, and Hibernate binds
    //       java.util.UUID to it directly, so no JDBC type override is needed here as it is for the
    //       fixed-character members above. A String would round-trip the value through text and
    //       would compare two spellings of one identifier -- differing only in letter case or in
    //       hyphenation -- as though they were different subjects, whereas the uuid type
    //       canonicalises the value before uniqueness is ever evaluated.
    @Column(name = "cognito_sub", nullable = false, unique = true)
    private UUID cognitoSub;

    // WHY : Alternatives Considered: Lombok was evaluated for the constructors and accessors below
    //       and rejected. A generated member cannot carry a docstring, and the project's
    //       Explainability rule requires one on every constructor and every method, naming each
    //       parameter with its type and description and describing every return value; a
    //       Lombok-built class therefore either fails the documentation gate or has to be
    //       suppressed out of it, and config/checkstyle/suppressions.xml admits only generated
    //       sources and test fixtures, so no suppression is available to this file in any case.
    //       Java 21 explicit constructors with hand-written accessors give the same brevity with
    //       members that can be documented, which is the only property that decides the question.
    // WHY : Alternatives Considered: a Java record was the other route to that brevity, and it is
    //       unavailable rather than merely rejected. A record is final and its components are
    //       final, whereas the JPA provider requires a no-argument constructor and write access to
    //       each mapped member so that it can instantiate the entity and then populate it. That is
    //       why every member below is written out by hand instead of being derived, and it is also
    //       why this type is a class while every transfer object in com.carddemo.auth.dto -- which
    //       no provider instantiates -- is a record.

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Callers should use the five-argument constructor instead; this one exists for the provider
     * and leaves every member null.</p>
     */
    // WHY : Assumptions: the JPA specification requires a no-argument constructor that the provider
    //       can reach, because it instantiates an entity reflectively before it has any values to
    //       supply and populates the mapped members afterwards. Without this declaration, adding
    //       the five-argument constructor below would remove the implicit default one and the
    //       provider would fail to instantiate the type at run time rather than at compile time.
    // WHY : Trade-offs: protected rather than public, which is the narrowest visibility the
    //       provider can still use. Making it public would advertise a half-built instance as a
    //       supported way to construct a user; making it private would put it out of reach of the
    //       proxy subclass the provider may generate for lazy loading.
    // WHY : Assumptions: the body is empty by design rather than unfinished. The provider assigns
    //       every mapped member directly after construction on each load, so initialising any of
    //       them here would write a value that is overwritten before a caller could observe it.
    protected User() {
        // Assumptions: empty by design; the provider assigns every mapped member after construction.
    }

    /**
     * Creates a fully populated user row.
     *
     * <p>The parameter order is the record order of the 80-byte security record -- the identifier at
     * line 18, the two names at lines 19 and 20, then the type at line 22 -- with the credential at
     * line 21 and the trailing pad at line 23 absent for the reasons recorded above, and
     * {@code cognitoSub} appended last as the column the migration adds. That is also the column
     * order {@code V1__auth.sql} declares, so the two agree and neither is reordered to suit the
     * other.</p>
     *
     * <p>No argument is validated here. Assumptions: validation belongs to the request shape in
     * {@code com.carddemo.auth.dto} and to the ordered chain in {@code com.carddemo.auth.service},
     * which between them decide which field reports the first error and in what wording; a
     * constructor that threw instead would report a violation with no field attribution and would
     * duplicate a rule already stated twice. The table's own constraints remain the last guard for
     * any write path that never passes through this service.</p>
     *
     * @param userId the eight-character identifier, the primary key, from {@code SEC-USR-ID}
     * @param firstName the {@code String} first name, at most 20 characters, from
     *     {@code SEC-USR-FNAME}
     * @param lastName the {@code String} last name, at most 20 characters, from
     *     {@code SEC-USR-LNAME}
     * @param userType the one-character {@code String} type, {@code "A"} for an administrator or
     *     {@code "U"} for an ordinary user, from {@code SEC-USR-TYPE}
     * @param cognitoSub the {@code UUID} subject reference identifying this user to the identity
     *     provider
     */
    public User(
            String userId,
            String firstName,
            String lastName,
            String userType,
            UUID cognitoSub) {
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.userType = userType;
        this.cognitoSub = cognitoSub;
    }

    /**
     * Returns the eight-character user identifier that is this row's primary key.
     *
     * @return the {@code String} identifier, or null on an instance the provider has not populated
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Returns the user's first name.
     *
     * @return the {@code String} first name, at most 20 characters
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Replaces the user's first name.
     *
     * @param firstName the {@code String} first name to store, at most 20 characters
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the user's last name.
     *
     * @return the {@code String} last name, at most 20 characters
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Replaces the user's last name.
     *
     * @param lastName the {@code String} last name to store, at most 20 characters
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the one-character user type.
     *
     * @return the {@code String} type, {@code "A"} for an administrator or {@code "U"} for an
     *     ordinary user
     */
    public String getUserType() {
        return userType;
    }

    /**
     * Replaces the one-character user type.
     *
     * @param userType the one-character {@code String} type to store, {@code "A"} or {@code "U"}
     */
    public void setUserType(String userType) {
        this.userType = userType;
    }

    /**
     * Returns the identity provider's subject reference for this user.
     *
     * @return the {@code UUID} subject reference
     */
    public UUID getCognitoSub() {
        return cognitoSub;
    }

    /**
     * Replaces the identity provider's subject reference for this user.
     *
     * @param cognitoSub the {@code UUID} subject reference to store
     */
    public void setCognitoSub(UUID cognitoSub) {
        this.cognitoSub = cognitoSub;
    }

    // WHY : Assumptions: there is deliberately no setter for userId, and the asymmetry with the
    //       four setters above is the point rather than an omission. The member is mapped with
    //       updatable false for the reason recorded beside it, so a setter would offer callers a
    //       mutation the provider would then decline to persist -- a value changed in memory and
    //       silently absent from the next write. The baseline draws the same line: its update path
    //       at app/cbl/COUSR02C.cbl L216 moves the identifier in only to locate the record, and the
    //       block at L219 to L234 replaces the two names and the type and never the key.
    // WHY : Alternatives Considered: declaring equals, hashCode and toString was evaluated and
    //       rejected, so this class inherits all three. Two specific reasons decide it. An entity's
    //       identity here is already the primary key the provider manages, and a hand-written
    //       equality over a mutable key behaves differently before and after a flush, which is a
    //       subtler defect than having no override at all. And a generated-looking toString on this
    //       particular type would put cognitoSub into every log line and exception message that
    //       interpolates an instance; a subject reference is an identity assertion rather than a
    //       display value, so the safest rendering is the one this class does not write. A
    //       diagnostic rendering, if a caller ever needs one, belongs in the mapper layer where the
    //       masking obligations of this migration are already discharged.
}
