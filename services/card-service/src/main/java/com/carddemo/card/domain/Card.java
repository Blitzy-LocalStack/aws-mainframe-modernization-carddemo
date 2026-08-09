package com.carddemo.card.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * The one persistent card record of this bounded context.
 *
 * <p>This is the migrated form of {@code 01 CARD-RECORD}, declared at
 * {@code app/cpy/CVACT02Y.cpy:4}, whose header at {@code :2} announces a 150-byte record. Six of the
 * seven members below carry a named field of that record and the seventh carries an
 * optimistic-concurrency counter the baseline maintains by hand; the record's trailing padding
 * carries neither and its omission is recorded beside the member list.</p>
 *
 * <p>Assumptions: the authoritative column list is the migration at
 * {@code services/card-service/src/main/resources/db/migration/V1__card.sql}, and every mapping here
 * mirrors that file rather than deriving the layout from the copybook a second time. Deriving both
 * sides independently is the alternative and is what this avoids: two derivations disagree the first
 * time only one is edited, and nothing reports it, because the provider emits no data definitions
 * and asserts no mapping at start-up in this module -- {@code application.yml:510} holds
 * {@code ddl-auto} at {@code none} -- so a member describing a column that does not exist surfaces
 * at the first query rather than at boot. Mirroring one file removes that class of divergence
 * outright. Where the two sources could be read differently, that migration decides.</p>
 *
 * <p>Assumptions: the widths, nullability and the closed status domain are declared once, in that
 * migration, and are not restated as validation annotations on these members. This type carries no
 * constraint annotation of any kind. Request-shaped checking belongs to
 * {@code com.carddemo.card.dto} and {@code com.carddemo.card.api}, which reject a malformed request
 * before it reaches persistence, while the migration refuses a malformed row however it arrives --
 * including from the bulk load, which never runs application code at all. Annotating the members as
 * well would put one rule in three places and make none of them authoritative.</p>
 *
 * <p>Alternatives Considered: generating the accessors and the value-based methods below with
 * Lombok, and generating the record-to-served-shape conversion with MapStruct. Neither is on the
 * classpath and neither is added. The project documentation rule requires a docstring on every
 * method, and a generated method cannot carry one, so Lombok would trade eleven hand-written
 * accessors for a build that fails its own documentation gate. MapStruct is declined for a separate
 * reason that belongs to {@code com.carddemo.card.mapper} rather than here: that conversion is not
 * mechanical, because it masks the primary account number, suppresses the verification value
 * outright and drops the record's padding, and each of those needs a justification at the point it
 * happens.</p>
 *
 * <p>Alternatives Considered: annotating the two fixed-character members with the provider's JDBC
 * type override, which is what several sibling contexts do for a {@code CHAR} column so that a
 * {@code String} is not inferred as {@code VARCHAR}. It is deliberately absent here, on two grounds
 * that hold together. Nothing in this module compares a mapping against the live column list --
 * {@code ddl-auto} is {@code none} in the base profile and in
 * {@code src/test/resources/application-test.yml} alike -- so the override would have no assertion
 * to satisfy; and both fixed-character columns here always hold exactly their declared width, a
 * sixteen-digit card number and a one-character status, so no value is ever stored padded and the
 * fixed-character and variable-character bindings cannot produce a different comparison. The
 * contexts that do carry the override need it for the opposite case, a declared width wider than the
 * value stored in it. The cost accepted is that these two members state their width through
 * {@code length} alone; the migration remains the place their exact type is settled.</p>
 *
 * <p>Trade-offs: this type imports nothing from {@code com.carddemo.common}, reaching only for
 * {@code jakarta.persistence} and the JDK, which is deliberate in a service that uses the shared
 * kernel throughout its other layers. Every concern that would have pulled it in belongs to
 * {@code com.carddemo.card.mapper}: the digits-only string transport of both identifiers, masking
 * the primary account number to its last four digits except on the one route authorised to receive
 * it in full, and suppressing the verification value. Keeping them out means this type says only
 * which column each field lands on, and it can be constructed in a test with no shared-kernel
 * behaviour present. The cost is that reading a card end to end means reading two packages rather
 * than one.</p>
 *
 * <p>Assumptions: no member represents the {@code FILLER PIC X(59)} at
 * {@code app/cpy/CVACT02Y.cpy:11}. Those 59 bytes pad {@code CARD-RECORD} out to the constant length
 * that {@code RECORDSIZE(150 150)} at {@code app/jcl/CARDFILE.jcl:55} requires and that
 * {@code app/cbl/CBACT02C.cbl:38-40} confirms from the other side as 134 bytes of data behind a
 * 16-byte key. They name no field and no program reads them, and a relational row has no constant
 * length for them to pad out, so they become no column. The arithmetic a reader needs in order to
 * reconcile six named fields against a 150-byte record is that those six occupy 91 bytes between
 * them: 16, 11, 3, 50, 10 and 1. Re-emitting a byte-identical 150-byte record is the record codec's
 * work, and it pads from the layout declaration rather than from padding some row had stored.</p>
 *
 * <p>Assumptions: this context handles no money, so the project-wide exact-decimal invariant has no
 * subject in this type. The record declares two numeric fields and neither is an amount: one is an
 * eleven-digit account identifier and the other a three-digit verification code. The absence is
 * written down rather than left implicit because the cheapest way to breach that invariant is for a
 * type believed to hold no amount to acquire one convenient approximate numeric member later.</p>
 *
 * <p>Trade-offs: no golden-master oracle exists for the paths this type serves. The card screens
 * cannot run end to end without a CICS runtime, as recorded at {@code tests/README.md:83-85}, so
 * parity for them rests on the transcribed rules and on this module's own tests rather than on a
 * byte comparison against recorded output. This mapping is the part least exposed by that gap,
 * because a column name, a width and a nullability setting are each checkable directly against the
 * copybook and the migration. Every baseline path cited here is reference material: read, never
 * modified, and still running.</p>
 */
@Entity
// WHY : Assumptions: the table is named here and its owning namespace deliberately is not. That
//       namespace is configured once for the whole module, at application.yml:535, so naming it a
//       second time on this annotation would give one value two owners that no build compares --
//       the same divergence the column mirroring in the block above exists to prevent. A reader
//       asking where card.cards is qualified finds exactly one answer.
// WHY : Alternatives Considered: qualifying it on this annotation, which several sibling contexts
//       do. Declined here because the setting already exists and the three authorization entities
//       take this same unqualified form, so it is an established shape rather than a new one. The
//       cost accepted is that this annotation alone does not say where the table lives, which the
//       citation above resolves.
@Table(name = "cards")
public class Card {

    /**
     * The sixteen-character card number, and the whole of this mapping's primary key.
     *
     * <p>Transcribed from {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy} line 5, bytes 1
     * to 16 of the record, onto {@code card_num CHAR(16) NOT NULL} at
     * {@code V1__card.sql:175}.</p>
     */
    // WHY : Assumptions: characters at every hop, and a number only inside arithmetic, which is how
    //       the baseline itself holds the value. app/cpy/CVCRD01Y.cpy declares CC-CARD-NUM as
    //       PIC X(16) at :37-38 and overlays CC-CARD-NUM-N as PIC 9(16) on the same bytes by
    //       REDEFINES at :39; app/cbl/COCRDUPC.cbl repeats that pair at :110-112. Two consequences
    //       rule out a numeric member: a leading zero is significant here and every numeric type
    //       discards it, and sixteen significant digits exceed what a 64-bit binary floating-point
    //       number represents exactly, so a caller that routed the value through a JSON number would
    //       hand back a different card. The declared width is part of the contract rather than a
    //       ceiling --
    //       CARD-NUM is PIC X(16) at app/cpy/CVACT02Y.cpy:5, occupying bytes 1 to 16, and the base
    //       cluster keys on all sixteen of those bytes from offset zero.
    // WHY : Assumptions: an assigned key with no generator, because the key is a business value and
    //       not a surrogate. KEYS(16 0) at app/jcl/CARDFILE.jcl:54 keys the cluster on exactly this
    //       field, so the value arrives with the card and is never minted here. A generator would
    //       have to invent a sixteen-digit number that no baseline path would recognise.
    // WHY : Trade-offs: updatable = false, so the key leaves the UPDATE statement entirely. What is
    //       bought is that a re-keyed instance cannot silently move to another row while a
    //       hash-based collection is holding it under the old key, which matters because the
    //       equality below is keyed on this member alone. The cost is that correcting a mistyped
    //       card number is a delete and an insert rather than an update; no baseline path offers
    //       that correction either, since both maintenance programs address the card by this key.
    @Id
    // WHY : Assumptions: V1__card.sql declares this column CHAR(16), and length alone would leave the
    //       provider to infer VARCHAR(16). The difference is not cosmetic here, because this member is
    //       both the primary key and the keyset browse ordering key: PostgreSQL resolves a bpchar column
    //       against a varchar parameter under text rules, where the blank padding a CHAR column adds is
    //       significant, so a strict inequality over it would order and position against a value that is
    //       not the stored one. Stating the fixed-character binding is what keeps the browse predicate
    //       and the stored form the same. The alternative, columnDefinition, was rejected: it is a
    //       DDL-implying attribute and this mapping is DDL-passive, the schema being owned by Flyway.
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "card_num", length = 16, nullable = false, updatable = false)
    private String cardNum;

    /**
     * The account the card belongs to, and the column the by-account access path orders.
     *
     * <p>Transcribed from {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy} line 6,
     * bytes 17 to 27 of the record, onto {@code account_id BIGINT NOT NULL} at
     * {@code V1__card.sql:187}.</p>
     */
    // WHY : Assumptions: an integer member, because CARD-ACCT-ID is PIC 9(11) at
    //       app/cpy/CVACT02Y.cpy:6, occupying bytes 17 to 27, and is used throughout as an
    //       identifier rather than as a quantity. Eleven digits overflow a thirty-two-bit integer
    //       and sit well inside a sixty-four-bit one, which is why V1__card.sql:187 declares the
    //       column BIGINT and this member is a Long. It is non-nullable there, so a card always
    //       names an account.
    // WHY : Assumptions: a plain scalar column and not an association onto an account type. Two
    //       reasons apply and either alone settles it. The table such an association would point at
    //       is owned by another bounded context, so declaring it would create exactly the
    //       cross-context dependency the architecture test forbids. And the baseline does not reach
    //       an account's cards by joining anything: app/csd/CARDDEMO.CSD surfaces the base cluster
    //       as the file CARDDAT at :25 and its alternate-index path as the separate file CARDAIX at
    //       :13, so a program addresses them as two distinct files, and that second access path
    //       becomes a secondary index over this one table rather than a relationship between two.
    // WHY : Trade-offs: no immutability marker on this member, unlike the key above, even though no
    //       route changes it -- the before-image predicate at app/cbl/COCRDUPC.cbl:1503-1508
    //       compares the verification value, the embossed name, the three expiry components and the
    //       status, and this field is not among them. Marking it non-updatable would drop it from
    //       the UPDATE statement, so a write that should have been refused would instead be
    //       discarded in silence and the row would read back unchanged with no error raised. A
    //       silent loss is the worse failure of the two, so the column stays writable and the
    //       absence of a route that writes it is documented instead of enforced here.
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * The enciphered card verification value, held as opaque bytes.
     *
     * <p>Transcribed from {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy} line 7,
     * bytes 28 to 30 of the record, onto the one nullable column of this table,
     * {@code cvv_encrypted BYTEA} at {@code V1__card.sql:218}. It holds ciphertext rather than the
     * three display digits the baseline stores.</p>
     */
    // WHY : Refactoring Rationale: the baseline holds CARD-CVV-CD as three display digits in the
    //       clear at app/cpy/CVACT02Y.cpy:7, bytes 28 to 30, on files declared JOURNAL(NO) and
    //       RECOVERY(NONE) -- app/csd/CARDDEMO.CSD:19 and :21 for the alternate index, :31 and :33
    //       for the base cluster. That is a property of the storage platform and not a shortcoming
    //       of the programs written against it: a non-recoverable cluster has nowhere to keep
    //       cipher keys or an audit trail. The relational target holds the enciphered value
    //       instead, so it is unreadable at rest in a page image or a backup. A byte member follows
    //       directly from that, matching the BYTEA column at V1__card.sql:218, because ciphertext is
    //       opaque bytes of whatever length the cipher chooses and a three-character member could
    //       not hold it.
    // WHY : Assumptions: this type receives a value that is already enciphered and performs no
    //       cryptography itself. Enciphering here would mean reaching a key provider from a domain
    //       type, which is the dependency the architecture test refuses, and it would also make the
    //       plaintext reachable from an entity that a query can hydrate at will. The boundary that
    //       enciphers and deciphers is com.carddemo.card.service.CardVerificationValueCipher, and
    //       this member is the storage form on both sides of it.
    // WHY : Refactoring Rationale: the member is EncryptedCvv and no longer a bare byte array named
    //       for what it was supposed to contain. Nothing checked the name against the contents, so
    //       the three ASCII bytes of a plaintext verification value -- CARD-CVV-CD PIC 9(03) at
    //       app/cpy/CVACT02Y.cpy:7, in the clear -- were valid state for a column called
    //       cvv_encrypted, and the first mapper authored would have had nothing to be refused by. The
    //       value type carries a self-describing envelope whose shortest well-formed form is far
    //       longer than three bytes, so the plaintext is not merely discouraged from reaching the
    //       column: it cannot be expressed as this attribute's value.
    // WHY : Assumptions: the conversion is declared here rather than left to the provider's default
    //       byte-array handling, because the provider reads and writes a mapped attribute itself
    //       rather than through the accessors below -- so the framing check belongs on the
    //       conversion, where it binds the persistence path, and not only on the accessors, where it
    //       would bind whoever remembered to use them.
    // WHY : Assumptions: the only nullable member here, and deliberately so, matching the one
    //       nullable column in the migration. A deployment that retains no verification value at
    //       all then stores nothing rather than a placeholder that would afterwards have to be told
    //       apart from a genuine value. No route of this service selects this column, no request or
    //       response shape carries it, and the renderings at the foot of this type omit it.
    @Column(name = "cvv_encrypted")
    @Convert(converter = EncryptedCvvConverter.class)
    private EncryptedCvv cvvEncrypted;

    /**
     * The name embossed on the card.
     *
     * <p>Transcribed from {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy} line
     * 8, bytes 31 to 80 of the record, onto {@code embossed_name VARCHAR(50) NOT NULL} at
     * {@code V1__card.sql:233}.</p>
     */
    // WHY : Trade-offs: a variable-width column here, against fixed width for the key and the
    //       status, is the one place a PIC X width is read as a maximum instead of as data.
    //       CARD-EMBOSSED-NAME is PIC X(50) at app/cpy/CVACT02Y.cpy:8, bytes 31 to 80, and the
    //       reference extract is left-justified and space-padded to fill it, so those trailing
    //       blanks are the record format asserting itself rather than part of anybody's name. A
    //       fixed-width column would make them part of the value, and every equality test and every
    //       rendered field would carry them. The cost accepted is that V1__card.sql:233 no longer
    //       states the stored width on its face, which is why length is declared here; the declared
    //       width still binds, so an over-long name is refused rather than truncated.
    @Column(name = "embossed_name", length = 50, nullable = false)
    private String embossedName;

    /**
     * The date the card expires.
     *
     * <p>Transcribed from {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT02Y.cpy} line
     * 9, bytes 81 to 90 of the record, onto {@code expiration_date DATE NOT NULL} at
     * {@code V1__card.sql:258}. This is the one member whose name spells out a word the baseline
     * field name abbreviates.</p>
     */
    // WHY : Assumptions: a true date member from a PIC X(10) source field, because those ten bytes
    //       are demonstrably ISO year-month-day and not an arbitrary string.
    //       CARD-EXPIRAION-DATE is PIC X(10) at app/cpy/CVACT02Y.cpy:9, bytes 81 to 90, and three
    //       independent declarations inside app/cbl/COCRDUPC.cbl agree on its shape: the REDEFINES
    //       at :116-121 decomposes it 4-1-2-1-2 with the separators at bytes 5 and 8, the further
    //       REDEFINES at :122-123 declares all ten bytes as PIC 9(10), and the reference
    //       modification at :1505-1507 reads the year at (1:4), the month at (6:2) and the day at
    //       (9:2). Keeping the text form would preserve the bytes while giving up date arithmetic
    //       and range predicates; because ISO ordering makes a lexical comparison and a date
    //       comparison agree, a date member keeps the ordering the baseline relies on and makes
    //       those operations expressible as well.
    // WHY : Refactoring Rationale: this member's name spells out a word that the source field name
    //       abbreviates. The baseline field is named CARD-EXPIRAION-DATE and stays exactly as it
    //       reads -- it is reference material and no line of it is altered -- while the target
    //       column at V1__card.sql:258 and this member use the spelled-out form. That is a
    //       target-side naming decision taken at this one field and nowhere else in the record, and
    //       the correspondence between the two names is recorded in the field-by-field data-model
    //       and column-mapping document under docs/architecture/, so it is never left to inference.
    // WHY : Assumptions: a whole date and nothing finer. The year, month and day components the
    //       baseline splits out are not members here, because the split exists to serve a screen:
    //       the before-image group at app/cbl/COCRDUPC.cbl:297-300 snapshots eight bytes rather
    //       than ten, dropping the two separators, and :1467-1474 reassembles them with STRING when
    //       writing back. Decomposition and reassembly are the mapper's and the service's work, so
    //       adding component members here would give the same value two representations inside one
    //       type and leave which of them is authoritative unstated.
    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    /**
     * The one-character active-status code.
     *
     * <p>Transcribed from {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy} line
     * 10, byte 91 of the record, onto {@code active_status CHAR(1) NOT NULL} at
     * {@code V1__card.sql:270}, whose closed {@code 'Y'}/{@code 'N'} domain is enforced by the named
     * check constraint at {@code V1__card.sql:366}.</p>
     */
    // WHY : Assumptions: a code and not a boolean. CARD-ACTIVE-STATUS is PIC X(01) at
    //       app/cpy/CVACT02Y.cpy:10, byte 91, and the baseline tests it against the closed
    //       two-value domain declared at app/cbl/COCRDUPC.cbl:89-91, where FLG-YES-NO-CHECK holds
    //       PIC X(1) and the 88-level FLG-YES-NO-VALID admits 'Y' and 'N'. A boolean would read
    //       more naturally and would change two things that matter: the loader would have to
    //       translate every byte of every extract, and a third character arriving from one would be
    //       coerced into true or false instead of being refused. The domain is enforced by the named
    //       check constraint at V1__card.sql:366, which the bulk load also passes through, rather
    //       than by anything on this member.
    // WHY : Alternatives Considered: the char primitive and the boxed character. Both are declined
    //       because neither distinguishes a blank byte, which a one-character field in a
    //       fixed-width record can genuinely carry, from no value at all; a string preserves the
    //       difference and binds to the one-character column without a conversion.
    // WHY : Assumptions: CHAR(1) in V1__card.sql, so the same fixed-character binding recorded on the key
    //       above applies. The override is repeated rather than inherited because the provider infers the
    //       JDBC type independently for every member.
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "active_status", length = 1, nullable = false)
    private String activeStatus;

    /**
     * The optimistic-concurrency counter, and the one member carrying no copybook field.
     *
     * <p>It has no source in {@code app/cpy/CVACT02Y.cpy}; it maps onto
     * {@code version INTEGER NOT NULL DEFAULT 0} at {@code V1__card.sql:303} and subsumes the
     * six-field before-image comparison the baseline performs by hand.</p>
     */
    // WHY : Refactoring Rationale: the baseline already performs optimistic concurrency by hand, so
    //       this member expresses a discipline that exists rather than introducing one that does
    //       not. Because a CICS task ends at every screen turn, app/cbl/COCRDUPC.cbl cannot hold a
    //       record lock across the user's think time. It snapshots the record into CCUP-OLD-DETAILS
    //       at :291-301 before showing the screen -- the verification value at :294, the embossed
    //       name at :296, the expiry components at :297-300 and the status at :301 -- and
    //       re-compares on submit. Paragraph 9300-CHECK-CHANGE-IN-REC at :1498, reached from the
    //       guard at :1453-1457, tests six fields in the single predicate at :1503-1508 and
    //       abandons the write at :1511 if any of them moved, the whole sequence committing at the
    //       syncpoint at :469-471. One counter subsumes that entire comparison, including the
    //       verification value the screens never displayed, and it keeps holding when a column is
    //       added, which a hand-maintained field list does not. Nothing is lost, because the read
    //       lock was never held across the gap in the first place -- which is precisely why the
    //       before-image exists.
    // WHY : Alternatives Considered: re-comparing the whole before-image row, which is the literal
    //       translation of that predicate, and a last-updated timestamp. The row comparison has to
    //       carry every field out to the caller and back to be checked, which for this type means
    //       carrying the verification value out -- the one thing the member above exists to
    //       prevent. A timestamp is only safe while clock resolution stays finer than the update
    //       rate, a condition nothing here can enforce; a counter needs no such condition.
    // WHY : Assumptions: the primitive int, because V1__card.sql:303 declares this column INTEGER
    //       NOT NULL DEFAULT 0. A non-nullable column can never deliver an absent value, so a boxed
    //       member would add a null state the column forbids. The width is worth naming because
    //       this is the one column where this module differs from its siblings: the equivalent
    //       column is BIGINT in the account and reference migrations, so a wider member copied
    //       across from either of those would describe a column this table does not have. The
    //       provider owns the value; the default in the migration exists so the bulk load can
    //       insert a row from an extract that has no field for it.
    @Version
    @Column(name = "version", nullable = false)
    private int version;

    // WHY : Refactoring Rationale: this entity once carried a second identity beside the primary key --
    //       a durable random card_selector UUID, with a unique constraint, a generated column default
    //       and an accessor -- and it is REMOVED rather than wired up. It was introduced so a published
    //       route could address one card without carrying the card number in the request target, which
    //       is a real requirement; the defect was that a SECOND mechanism was independently built for
    //       the same requirement and only that one works. com.carddemo.card.mapper.CardMapper seals the
    //       card number into a keyed, purpose-scoped token and opens it back, and the opened value is
    //       the primary key, so findById serves the route end to end. The UUID had no reader at all:
    //       no repository method addressed the column, so no request could ever resolve to a row
    //       through it, and its own accessor documentation claimed it was "the value a published route
    //       carries" when the route carried the sealed token instead.
    // WHY : Alternatives Considered: keeping the UUID and making IT the single lookup identity, by
    //       adding a finder for the column and sealing the UUID instead of the card number. Declined on
    //       three grounds. It is the larger change, touching the mapper, the repository and every
    //       route, to arrive at the same capability. It is weaker on the very property the surrogate
    //       was introduced for: a durable UUID is a stable correlator across every purpose and every
    //       request for the lifetime of the row, whereas the sealed token is keyed and scoped by
    //       purpose, so the same card yields unrelated tokens in unrelated contexts and none of them
    //       outlives the key. And SealedSelector is already the convention this migration shares with
    //       the authorization context, so a second, differently-shaped identity in one service would
    //       have to be justified rather than merely permitted. The bulk load is unaffected, because a
    //       token that is derived on read needs no column to derive it from.


    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the provider instantiates a mapped type reflectively through its no-argument
     * constructor and then writes the members directly, so this constructor exists to satisfy that
     * contract and is not an application entry point. It is protected rather than public because the
     * provider reaches a non-public constructor without difficulty while application code cannot,
     * which keeps a half-populated instance -- one carrying no card number, on which the equality
     * below cannot tell one row from another -- out of the reach of callers that have the
     * six-argument form available instead.</p>
     */
    protected Card() {
        // WHY : Assumptions: nothing is assigned here and nothing is defaulted. The provider
        //       overwrites every member immediately after this call, so a value assigned here would
        //       never be read; and a default given to the status or the expiry would afterwards be
        //       indistinguishable from a value that had genuinely been loaded from a row.
    }

    /**
     * Creates a card from the six values the baseline record carries.
     *
     * <p>Assumptions: the concurrency counter is not a parameter. The provider assigns and advances
     * it, so accepting one here would let a caller present a counter the row never held and turn the
     * conflict check into whatever that caller happened to pass.</p>
     *
     * <p>Alternatives Considered: rejecting a null or malformed argument in this constructor.
     * Declined because the widths, the non-nullability and the closed status domain are already
     * declared in the migration, which refuses a bad row however it arrives, and the request shapes
     * reject a bad request before persistence is reached. A third check here would duplicate both
     * and would also make this constructor unusable for building a partially-known card in a test.
     * The trade accepted is that an instance can be constructed that the database will refuse; it is
     * refused at the flush, naming the constraint it broke.</p>
     *
     * @param cardNum the sixteen-character card number, which becomes this card's identity and
     *     cannot be reassigned afterwards
     * @param accountId the eleven-digit account identifier the card belongs to
     * @param cvvEncrypted the already-enciphered verification value, or null when none is retained;
     *     the value is immutable, so nothing a caller does afterwards reaches this card
     * @param embossedName the name embossed on the card, up to fifty characters
     * @param expirationDate the date the card expires
     * @param activeStatus the one-character active-status code, which the migration restricts to
     *     'Y' or 'N'
     */
    public Card(String cardNum, Long accountId, EncryptedCvv cvvEncrypted, String embossedName,
            LocalDate expirationDate, String activeStatus) {
        this.cardNum = cardNum;
        this.accountId = accountId;
        this.cvvEncrypted = cvvEncrypted;
        this.embossedName = embossedName;
        this.expirationDate = expirationDate;
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the card number that identifies this card.
     *
     * @return the sixteen-character card number, digits only and with leading zeros significant, or
     *     null on an instance the provider has not yet populated
     */
    public String getCardNum() {
        return this.cardNum;
    }

    /**
     * Returns the identifier of the account this card belongs to.
     *
     * <p>Alternatives Considered: a matching assignment method, so that every writable column had
     * one. Declined because the assignment methods on this type are exactly the fields a route
     * mutates, and no route mutates this one: the maintenance program's before-image predicate at
     * {@code app/cbl/COCRDUPC.cbl:1503-1508} compares the verification value, the embossed name, the
     * three expiry components and the status, and this field is not among them. A card is given its
     * account when it is constructed. Offering an assignment method anyway would advertise a
     * reassignment the baseline has no path for, and the column stays writable at the database for
     * the separate reason recorded on the member itself.</p>
     *
     * @return the eleven-digit account identifier, which a persisted row always carries
     */
    public Long getAccountId() {
        return this.accountId;
    }

    /**
     * Returns the enciphered verification value.
     *
     * <p>Assumptions: the caller is the boundary that deciphers, and no request or response shape of
     * this service carries this value in either direction. The value is immutable and its own
     * accessors copy the byte runs they expose, so it is returned directly: there is nothing a
     * caller can change in place that the next flush would write out.</p>
     *
     * @return the enciphered verification value, or null when the row retains none
     */
    public EncryptedCvv getCvvEncrypted() {
        return this.cvvEncrypted;
    }

    /**
     * Assigns the enciphered verification value.
     *
     * <p>Refactoring Rationale: this replaces a setter that accepted an arbitrary byte array. That
     * signature made a plaintext verification value assignable to the member whose whole purpose is
     * that it holds none, and the only thing standing against it was a sentence in this Javadoc
     * saying the argument was expected to be enciphered already. The parameter type now carries the
     * requirement, so the mistake the sentence warned about is a compilation failure rather than a
     * silent write of payment data.</p>
     *
     * <p>Assumptions: a setter is retained rather than removed outright, and the retention is about
     * re-keying rather than about ordinary writes. No route of this service writes this column --
     * the before-image predicate at {@code app/cbl/COCRDUPC.cbl:1503-1508} does not compare it and
     * no request shape carries it -- but a key rotation has to be able to replace an envelope
     * enciphered under a retired data key, and that is an assignment of an already-enciphered value,
     * which this signature admits and a plaintext one cannot reach.</p>
     *
     * @param cvvEncrypted the already-enciphered verification value to assign, or null to retain none
     */
    public void setCvvEncrypted(EncryptedCvv cvvEncrypted) {
        this.cvvEncrypted = cvvEncrypted;
    }

    /**
     * Returns the name embossed on the card.
     *
     * @return the embossed name, up to fifty characters and without the trailing blanks the
     *     fixed-width record carries, or null on an instance the provider has not yet populated
     */
    public String getEmbossedName() {
        return this.embossedName;
    }

    /**
     * Assigns the name embossed on the card.
     *
     * <p>Assumptions: the caller has already applied whatever case handling it intends. The
     * baseline upper-cases the value in place before comparing it across the screen gap, at
     * {@code app/cbl/COCRDUPC.cbl:1499-1501}, so a value written through here has that decision
     * behind it rather than in front of it; making this accessor upper-case its argument would apply
     * that rule to every write, including a load of history it was never applied to.</p>
     *
     * @param embossedName the embossed name to assign, of at most fifty characters
     */
    public void setEmbossedName(String embossedName) {
        this.embossedName = embossedName;
    }

    /**
     * Returns the date the card expires.
     *
     * @return the expiry date, which a persisted row always carries, or null on an instance the
     *     provider has not yet populated
     */
    public LocalDate getExpirationDate() {
        return this.expirationDate;
    }

    /**
     * Assigns the date the card expires.
     *
     * <p>Assumptions: the caller has already resolved the three components the baseline screen
     * collects separately into one date. The maintenance program reassembles them with a STRING
     * statement at {@code app/cbl/COCRDUPC.cbl:1467-1474} before writing, and doing the same
     * assembly here would need this type to accept the components, which is the representation
     * concern the mapper owns.</p>
     *
     * @param expirationDate the expiry date to assign
     */
    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * Returns the one-character active-status code.
     *
     * @return the status code, which the migration restricts to 'Y' or 'N', or null on an instance
     *     the provider has not yet populated
     */
    public String getActiveStatus() {
        return this.activeStatus;
    }

    /**
     * Assigns the one-character active-status code.
     *
     * <p>Assumptions: the closed domain is enforced by the named check constraint in the migration
     * and not by this accessor, so a third value assigned here is refused at the flush rather than
     * at the call. Rejecting it here as well would place the same rule in two places and would still
     * not cover the bulk load, which never runs this code at all.</p>
     *
     * @param activeStatus the one-character status code to assign, which the migration restricts to
     *     'Y' or 'N'
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the current value of the concurrency counter.
     *
     * <p>Assumptions: the provider owns this value, which is why there is no matching assignment
     * method. It is exposed because a caller that reads a card, hands it to a client and later
     * writes it back needs to carry the counter it read across that gap -- the same value the
     * baseline carries as a before-image across the screen turn.</p>
     *
     * @return the counter as last read or written by the provider, and zero on an instance that has
     *     never been persisted
     */
    public int getVersion() {
        return this.version;
    }

    /**
     * Compares this card with another on the card number alone.
     *
     * <p>Alternatives Considered: comparing every member. Declined because the card number is the
     * key the record contract declares, keyed at all sixteen bytes from offset zero by
     * {@code app/jcl/CARDFILE.jcl:54} and declared the primary key by the migration, so it already
     * determines the row. Comparing every member as well would make two reads of one row unequal the
     * moment a query populated a different subset of columns, and would break identity across a
     * flush, when the provider writes the advanced counter into an instance a collection is already
     * holding.</p>
     *
     * <p>Trade-offs: the enciphered verification value is excluded, and that exclusion is the point
     * rather than a side effect of the choice above. Comparing arrays of bytes would compare a
     * secret, and a comparison that returns as soon as two bytes differ takes a different amount of
     * time for a near-match than for an early mismatch, which is a side channel that does not need
     * to exist here. Excluding it costs nothing, because two rows with one card number are one card
     * whatever their verification values hold.</p>
     *
     * <p>Assumptions: an instance the provider has not yet populated carries no card number, so two
     * such instances compare equal -- the identifier is the only value compared and there is nothing
     * else to tell them apart.</p>
     *
     * @param other the object to compare with this card, which may be null or of any type
     * @return true when other is a card of this type carrying an equal card number, and false
     *     otherwise
     */
    @Override
    public boolean equals(Object other) {
        // WHY : Assumptions: a pattern match rather than a class comparison, because the provider
        //       may hand back an instrumented subclass for a lazy proxy and a strict class
        //       comparison would then report a row as unequal to itself. No subclass of this type is
        //       authored, so widening the test costs nothing.
        if (!(other instanceof Card that)) {
            return false;
        }
        return Objects.equals(this.cardNum, that.cardNum);
    }

    /**
     * Returns a hash consistent with the card-number-only equality above.
     *
     * <p>Assumptions: the card number is assigned once and its column is mapped so that it leaves
     * the UPDATE statement, so the value stays stable for the life of a persisted instance. That
     * stability is what makes this type safe to place in a hash-based collection. The enciphered
     * verification value is excluded here for the same reason it is excluded from the comparison,
     * and including it would additionally change an instance's hash whenever that value was
     * reassigned.</p>
     *
     * @return the hash of the card number, or the hash of an absent value on an instance the
     *     provider has not yet populated
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.cardNum);
    }

    /**
     * Returns a diagnostic rendering of this card for a log line or an assertion message.
     *
     * <p>Refactoring Rationale: THE ACCOUNT IDENTIFIER IS OMITTED, and an earlier revision rendered
     * it. That revision reasoned carefully about the two members it recognised as sensitive, the card
     * number and the enciphered verification value, and then treated everything left over as
     * unremarkable. The sensitive-data logging contract in
     * {@code docs/architecture/observability.md} does not work that way: it names account and
     * customer identifiers explicitly, in their own clause, alongside the primary account number
     * rather than beneath it. An eleven-digit account identifier is therefore protected on its own
     * terms, and it was protected nowhere in the earlier text -- which is the whole of the defect.
     * The reasoning below about the card number and the verification value was already right and is
     * kept unchanged.</p>
     *
     * <p>Trade-offs: this rendering names three of the seven members and deliberately omits the card
     * number, even though it is this type's own key. Every sibling entity's rendering names its key,
     * and this one cannot, because here the key is itself a primary account number. It is omitted
     * outright rather than abbreviated, because abbreviating a primary account number is masking,
     * and masking has one owner in this context, {@code com.carddemo.card.mapper}; a second and
     * slightly different masking rule here would give one value two renderings and make neither
     * authoritative. The same argument applies unchanged to the account identifier now omitted
     * beside it. The enciphered verification value is omitted on the stronger ground that it has
     * no safe rendering at all -- not its content, not its length, which would disclose the cipher's
     * output size, and not a hash of it, which would be a stable identifier for a secret. The cost
     * accepted is real: this rendering cannot identify which card it describes, nor whose account it
     * belongs to, so locating a specific card in a log means going through the masked form the
     * mapper produces. What is bought is absolute, in that no log line written from this type can
     * carry a primary account number, an account identifier or a verification value at all.</p>
     *
     * <p>Assumptions: the exposure this closes is the RETAINED LOG rather than any request path. The
     * by-account access path this table indexes returns every card an account holds, so one rendered
     * line per returned row turned a single card-list call into a log entry naming that account
     * repeatedly -- and across calls, into a second copy of the cross-reference this context reads
     * rather than owns. What remains is an expiry date, a status code and a counter: enough to say
     * what STATE a card was in when a line was written, and not enough to say which card or whose.
     * The cost is paid down elsewhere, because a request-scoped line already carries the correlation
     * identifier {@code com.carddemo.common.web.CorrelationIdFilter} publishes. The accessors above
     * return the omitted members to a caller that needs one, so nothing is unavailable to the
     * service itself.</p>
     *
     * @return a short single-line rendering naming the type, the expiry date, the active status and
     *     the concurrency counter, and carrying neither identifier of this record
     */
    @Override
    public String toString() {
        return "Card[expirationDate=" + this.expirationDate
                + ", activeStatus=" + this.activeStatus
                + ", version=" + this.version + ']';
    }
}
