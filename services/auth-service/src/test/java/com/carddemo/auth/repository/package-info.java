/**
 * JPA keyed operations, four keyset browse queries in two directions, and the
 * identity-synchronisation ledger's own constraints. No offset paging.
 *
 * <h2>The data contract this package is built on</h2>
 *
 * <p>The {@code auth.users} table derives from the 80-byte {@code SEC-USER-DATA} record declared at
 * {@code app/cpy/CSUSR01Y.cpy} line 17. Of its six declared fields, four become columns, one is
 * dropped and one is deliberately absent:</p>
 *
 * <ul>
 *   <li>{@code SEC-USR-ID PIC X(08)} at line 18, at zero-based offset 0, becomes the
 *       {@code CHAR(8)} primary key.</li>
 *   <li>{@code SEC-USR-FNAME PIC X(20)} at line 19, at offset 8, becomes a
 *       {@code VARCHAR(20) NOT NULL} name column.</li>
 *   <li>{@code SEC-USR-LNAME PIC X(20)} at line 20, at offset 28, becomes the second
 *       {@code VARCHAR(20) NOT NULL} name column.</li>
 *   <li>{@code SEC-USR-TYPE PIC X(01)} at line 22, at offset 56, becomes the constrained
 *       {@code CHAR(1) NOT NULL} type column.</li>
 *   <li>{@code SEC-USR-FILLER PIC X(23)} at line 23, at offset 57, is padding out to the declared
 *       record length and is dropped rather than mapped; there is nothing in it to assert.</li>
 * </ul>
 *
 * <p>The sixth field sits at offset 48 and is the subject of the divergence recorded below. One
 * column has no counterpart in the record at all, namely {@code cognito_sub}. The six declared
 * widths sum to exactly 80, being 8 plus 20 plus 20 plus 8 plus 1 plus 23, which is the arithmetic
 * that makes the offsets above checkable rather than asserted.</p>
 *
 * <p>Every one of those fields is {@code PIC X(n)}, that is character data. The record therefore
 * carries no zoned decimal, no packed decimal, no money and no timestamp, and that single
 * observation is the whole reason no codec type from {@code com.carddemo.common.codec} belongs
 * anywhere in this package. A test here that reached for a decimal codec would be decoding a
 * representation this record does not contain, and it would imply to a later reader that the auth
 * context shares the sign-overpunch and packed-decimal concerns that the money-bearing contexts
 * genuinely do have.</p>
 *
 * <h2>Why four browse queries and not a page number</h2>
 *
 * <p>The reference user list transaction browses its file rather than paging by ordinal.
 * {@code app/cbl/COUSR00C.cbl} declares its screen array as {@code 02 USER-REC OCCURS 10 TIMES.} at
 * line 57 and drives the file through four paragraphs: {@code STARTBR-USER-SEC-FILE.} at line 586,
 * {@code READNEXT-USER-SEC-FILE.} at line 619, {@code READPREV-USER-SEC-FILE.} at line 653 and
 * {@code ENDBR-USER-SEC-FILE.} at line 687. Three of those four survive as queries. The two reads
 * become the two continuations, one per direction. The browse OPEN becomes two queries rather than
 * one, because line 218 gives it either of two values -- {@code LOW-VALUES} when the search field is
 * blank, at line 219, or the identifier typed into it, at line 221 -- and those are the unpositioned
 * opening read and the positioned one. The browse CLOSE has no target counterpart at all, because a
 * query carries its cursor position in its own predicate instead of in a server-side handle, so there
 * is no handle to release.</p>
 *
 * <p>⚠️ Refactoring Rationale: this section counted TWO queries, then three, and now four, and neither
 * later figure was reached by incrementing the earlier one -- each was re-measured from the interface.
 * The third was the unpositioned opening read: both continuations take a key and the first page has
 * none, so opening the browse was expressible only by passing a value chosen to sort below every
 * stored key, which leans on a collation detail to mean "before every key" and would silently omit a
 * row whose identifier were blank. The fourth is the POSITIONED opening read, and this section's own
 * earlier wording is what delayed it: it recorded the browse open as having no reference counterpart,
 * on the ground that the baseline establishes its position before its first read rather than being
 * passed a key. That is true of the value only when the search field is blank. When it is not, the
 * key the seek uses is the caller's, and serving it needed a query that compares INCLUSIVELY -- the
 * continuation's comparison is strict and would hide the row whose identifier was typed, which is the
 * row the reference puts at the top of the screen.</p>
 *
 * <p>An ordinal offset is deliberately not part of the contract. An offset recomputed against a
 * table that another transaction has inserted into or deleted from skips and repeats rows, whereas a
 * predicate keyed strictly after the last key seen, or strictly before the first, cannot. The
 * baseline never had the defect to inherit, because a browse resumes from a key and not from a
 * count, so introducing an offset here would not be a simplification of the reference behaviour but
 * a change to it.</p>
 *
 * <h2>The one field that is deliberately absent</h2>
 *
 * <p>The baseline stores an eight-character plaintext credential field at offset 48 of that 80-byte
 * record ({@code app/cpy/CSUSR01Y.cpy} line 21) and compares it directly during sign-on
 * ({@code app/cbl/COSGN00C.cbl} line 223). The migrated schema declares no such column, the entity
 * exposes no such property and no request or response component carries one: a managed identity
 * pool holds credentials, and {@code auth.users} retains only {@code cognito_sub} as the reference
 * to the identity that pool owns. The baseline compares the stored value; the Java encodes a schema
 * in which there is no stored value to compare; the divergence is documented in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>That divergence is restated here rather than left to the traceability document because it is
 * the reason the integration test carries a negative assertion against
 * {@code information_schema.columns}. An absence cannot be demonstrated by reading a row; it can
 * only be demonstrated by asking the catalogue which columns exist and confirming that this one is
 * not among them. Without that assertion the schema could regain the column and every positive test
 * in the package would still pass.</p>
 *
 * <h2>Decision record</h2>
 *
 * <p>Alternatives Considered: an in-memory database was evaluated for this package and rejected, and
 * H2 specifically. {@code V1__auth.sql} declares {@code cognito_sub UUID NOT NULL UNIQUE}, and
 * {@code UUID} is a PostgreSQL type rather than a portable one; the same statement declares
 * {@code user_type CHAR(1) NOT NULL CHECK (user_type IN ('A','U'))}. The integration test asserts
 * that the database itself rejects a {@code user_type} outside that two-value domain, and that it
 * rejects a duplicate {@code cognito_sub}. An in-memory substitute would not faithfully reproduce
 * either rejection, so both assertions would pass against a fiction and the package would report a
 * guarantee that the deployed engine had never been asked to make. A real PostgreSQL container is
 * therefore the only option under which those two assertions mean anything, which is the specific
 * reason for the choice and not a general preference for realism. The two-value domain is not
 * invented here either: the reference baseline declares it at {@code app/cpy/COCOM01Y.cpy} lines 26
 * to 28, where {@code CDEMO-USER-TYPE PIC X(01)} carries {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'.}
 * and {@code 88 CDEMO-USRTYP-USER VALUE 'U'.} as its only two condition names.</p>
 *
 * <p>Assumptions: this package depends on five contracts that it does not own. Flyway applies EIGHT
 * migrations for this service, {@code V1__auth.sql} through {@code V8__auth_addressable_user_id.sql},
 * of which two create a relation -- {@code V1} the {@code CREATE TABLE auth.users} that declares the
 * {@code CHAR(8)} primary key, the two name columns, the constrained type column and
 * {@code cognito_sub}, and {@code V2} the synchronisation ledger described below. The other six add
 * constraints to those two tables and nothing else: there is no third table, no view, no trigger, no
 * sequence and no seed insert for a test here to lean on.
 * ⚠️ Refactoring Rationale: this said ONE migration carrying ONE statement, which was true when this
 * package was written and had been false for six revisions by the time it was read -- the ledger this
 * same document describes two sections below arrived in the second of them. The figure is now taken
 * from the migration directory, and the integration test asserts the resulting SHAPE, namely two
 * tables and no other relation, which is the invariant that matters rather than the file count.
 * Hibernate runs with {@code ddl-auto: validate}, so the entity mapping is asserted against the
 * Flyway-applied schema rather than generated from it, and a drift between the entity and the
 * migration therefore fails at application context startup rather than diverging silently while
 * every test still reports success. The datasource is supplied by a Testcontainers
 * {@code @ServiceConnection} on the {@code PostgreSQLContainer} field declared in
 * {@code UserRepositoryIT}, which is why no connection URL, no database user name and no credential
 * literal appears anywhere in this package. Schema resolution depends on
 * {@code spring.jpa.properties.hibernate.default_schema} and {@code spring.flyway.default-schema}
 * being set explicitly in {@code application-test.yml}, because under {@code @ServiceConnection} the
 * connection URL is generated by the container and any {@code currentSchema} parameter carried by
 * the base profile's URL is unavailable to it. And the primary key column is {@code CHAR(8)}, which
 * PostgreSQL blank-pads, so a comparison between a shorter identifier and a value read back from
 * that column is a comparison between two strings of unequal length unless one side is trimmed.</p>
 *
 * <p>Trade-offs: starting a database container for this package costs real time on every build, and
 * it requires a build host able to run one at all. That cost is accepted because the two rejection
 * assertions described above are the entire reason the package exists and neither is expressible
 * without the engine that enforces them. The COBOL parity suite makes the same trade in the same
 * direction, loading flat fixtures into real indexed files before a program runs instead of
 * asserting against an in-process stand-in, so this choice follows the house precedent rather than
 * departing from it.</p>
 *
 * <h2>The second table this package asserts</h2>
 *
 * <p>{@code auth.identity_sync_task}, created by {@code V2__auth_identity_sync.sql}, derives from NO
 * baseline record at all, and the reason it exists is the reason it is asserted here. The baseline keeps its
 * users in one file, so a user change there is a single write that cannot be half done; the migrated context
 * writes to {@code auth.users} AND to a managed identity provider that is not a transaction participant, so
 * a change that must reach both cannot be made atomic. This table is the durable record of what the provider
 * still owes, committed in the same transaction as the row it describes.</p>
 *
 * <p>Assumptions: two properties of it are only assertable against a real engine, which is why they belong
 * in this package rather than in a substituted unit test. Its {@code CHECK} constraints must refuse an
 * operation or a status outside the sets the applier implements, because a row carrying a fourth value would
 * make the applier's programming-error branch reachable from DATA. And it must carry NO foreign key to
 * {@code auth.users}: a withdrawal intention is recorded in the same transaction that deletes the row it
 * names, so a reference would make that insert impossible, and under a cascade would delete the intention
 * along with the row -- the exact loss the table exists to prevent.</p>
 *
 * <h2>What this package may hold</h2>
 *
 * <p>Assumptions: this package holds the container-backed integration tests described above and
 * this descriptor, and nothing else. There is deliberately no abstract base class, no suite
 * aggregator and no separate fixture builder: introducing a base class for a single subclass would
 * spread one test's setup across two files and give a reader two places to look for it. The
 * boundary is stated as a rule about what may be added rather than as a roster of files.</p>
 */
package com.carddemo.auth.repository;
