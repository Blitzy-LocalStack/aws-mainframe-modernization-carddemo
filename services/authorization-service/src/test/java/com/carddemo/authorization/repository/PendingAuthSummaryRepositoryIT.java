package com.carddemo.authorization.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins the schema truth of {@code pending_auth_summary} onto a real engine, key arity first.
 *
 * <p>Purpose: this class asserts the five properties of the migrated IMS root segment that only a
 * live engine can answer, and it is the narrowest of the four tables in this schema, which is why the
 * container arrangement the siblings follow is established here. The five are: the primary key is
 * {@code account_id} and nothing else; a lookup for an account with no row is an ordinary empty
 * result rather than a raised failure; the money precision here is genuinely narrower than the
 * sibling tables' and refuses a value they accept; two things a reader would expect to find are
 * deliberately absent -- no check constraint over {@code auth_status} and no index object beyond the
 * primary key's own; and the copybook's trailing {@code FILLER} reaches no column while the recorded
 * image still measures its declared hundred bytes.
 *
 * <p>Assumptions: S1, the primary key is {@code account_id} ALONE. Four readings of the baseline and
 * one of the target agree, and all five are asserted or cited rather than summarised. First,
 * {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} L28-L29 declares
 * {@code SEGM NAME=PAUTSUM0,PARENT=0,BYTES=100} and its L30 declares
 * {@code FIELD NAME=(ACCNTID,SEQ,U),START=1,BYTES=6,TYPE=P} -- one sequence field, unique, six packed
 * bytes at offset one, which leaves no room for a second component inside the key. Second,
 * {@code cbl/COPAUS0C.cbl} L973-L977 reads the segment with a single qualification,
 * {@code WHERE (ACCNTID = PA-ACCT-ID)}, and a single-field qualification on a {@code SEQ,U} field is
 * a whole-key read; the alternative key move on the intervening L972 is commented out, so it is dead
 * scaffolding rather than a second access path. Third,
 * {@code cpy/CIPAUSMY.cpy} L19 declares {@code PA-ACCT-ID PIC S9(11) COMP-3}, which occupies offsets
 * zero to five -- exactly the six bytes the database definition designates. Fourth, the recorded
 * unload images carry their records in opposite orders, {@code unload-gsam-summary-100.bin} ascending
 * and {@code unload-prefixed-summary-100.bin} descending, which is only sound because a summary
 * record is addressed by its account alone and file position therefore carries no key information.
 * Fifth, from the target side, {@code fk_pending_auth_detail_summary} in
 * {@code V1__authorization.sql} references {@code pending_auth_summary (account_id)}, and a foreign
 * key can name only the exact column list of a unique constraint.
 *
 * <p>Alternatives Considered: S1, a composite {@code (account_id, auth_date)} key was evaluated for
 * this table and rejected. The composite belongs to the CHILD segment and to it alone:
 * {@code DBPAUTP0.dbd} L36-L37 gives {@code PAUTDTL1} its own eight-byte sequence field, and the
 * program specification block's declared key length of fourteen is that eight added to the parent's
 * six rather than a wider parent key. Widening the parent would put a value in its key that the
 * baseline's own read at {@code COPAUS0C.cbl} L973-L977 never supplies, so a lookup faithful to the
 * baseline could not be expressed at all.
 *
 * <p>Alternatives Considered: S2, raising a not-found failure when no summary exists was rejected in
 * favour of an empty {@link Optional}. {@code cbl/COPAUS0C.cbl} L979-L996 moves the status into a
 * return code and evaluates exactly three branches: found sets a found flag; segment-not-found sets a
 * not-found flag and does nothing else at all, building no message and sending no screen; and only
 * the remaining branch sets the error flag, builds
 * {@code ' System error while reading AUTH Summary: Code:'} and repaints. Absence is therefore an
 * ordinary outcome of that evaluate and a raised failure here would promote one of its three normal
 * branches into an error path, which is an observable behavioural change rather than a stylistic one.
 *
 * <p>Assumptions: S3, the money columns on THIS table are {@code NUMERIC(11,2)} and not
 * {@code NUMERIC(12,2)}. All six amounts in the summary segment are declared
 * {@code PIC S9(09)V99 COMP-3} -- {@code cpy/CIPAUSMY.cpy} L23-L26 for the two limits and two
 * balances and its L29-L30 for the two accumulators -- so nine integer digits and two fractional
 * digits give a precision of eleven. The detail segment is wider at {@code CIPAUDTY.cpy} L34-L35 with
 * {@code PIC S9(10)V99 COMP-3}, and the migrated fraud table is wider again at
 * {@code ddl/AUTHFRDS.ddl} L12-L13 with {@code DECIMAL(12,2)}. This split is asserted as a pair
 * across two classes and neither half means much alone: the detail class asserts that twelve digits
 * are ACCEPTED there, and {@link #aTwelveDigitAmountIsRefusedByTheNarrowerSummaryPrecision()} here
 * asserts the same value is REFUSED, which is what makes the difference a contract rather than a
 * coincidence.
 *
 * <p>Alternatives Considered: S4, one {@code CHAR(2)[]} array column for
 * {@code PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES} at {@code cpy/CIPAUSMY.cpy} L22 was rejected in
 * favour of five discrete columns. An array column accepts any length, so the arity of exactly five
 * that the copybook fixes would survive only as a convention in application code instead of being
 * refused by the schema. Trade-offs: five column names are more verbose than one array column and
 * five assertions are more verbose than one, and both costs are accepted because the recorded image
 * carries five DISTINCT slot values, so an off-by-one or a transposition in the ten-byte interval
 * fails an assertion here instead of surviving into a stored row.
 *
 * <p>Assumptions: S5, {@code auth_status} carries NO check constraint and the absence is deliberate.
 * {@code cpy/CIPAUSMY.cpy} L21 declares {@code PA-AUTH-STATUS PIC X(01)} and no {@code 88}-level
 * condition name follows it -- the next declaration is {@code PA-ACCOUNT-STATUS} on L22. The check
 * constraints elsewhere in this schema are promotions of closed {@code 88}-level domains, which
 * {@code CIPAUDTY.cpy} L46-L52 supplies for the detail segment's match status and fraud flag; with no
 * such condition names here there is no domain to promote, and inventing one would refuse a value the
 * baseline can produce. The absence is asserted positively in
 * {@link #anArbitraryAuthStatusCharacterIsAccepted()} rather than left implicit, because an
 * unasserted absence is indistinguishable from an oversight and invites a later reader to complete
 * the schema.
 *
 * <p>Assumptions: S6, this table needs NO secondary index and none is asserted.
 * {@code DBPAUTP0.dbd} L31-L32 declares {@code LCHILD NAME=(PAUTINDX,DBPAUTX0), POINTER=INDX}, which
 * reads like an alternate access path and is not one:
 * {@code ims/DBPAUTX0.dbd} L18 declares {@code ACCESS=(INDEX,VSAM,PROT)}, its L27-L28 a six-byte
 * segment, its L29 {@code FIELD NAME=(INDXSEQ,SEQ,U),START=1,BYTES=6,TYPE=P} and its L30-L31
 * {@code LCHILD NAME=(PAUTSUM0,DBPAUTP0), INDEX=ACCNTID}. It indexes the ROOT sequence field, so it
 * is the primary index that a hierarchical direct-access organisation structurally requires, and a
 * primary key on {@code account_id} reproduces it exactly. The contrast that makes the sibling
 * class's opposite finding legible is direction: {@code DBPAUTX0} is single-column and ascending, so
 * one index object suffices here, whereas the fraud table's {@code ddl/XAUTHFRD.ddl} carries a
 * DESCENDING column that a primary key's always-ascending backing index cannot express, which is why
 * that table needs two objects where this one needs one.
 *
 * <p>Assumptions: S7, {@code FILLER PIC X(34)} at {@code cpy/CIPAUSMY.cpy} L31 is dropped and the
 * record still reconciles to one hundred bytes. Four independent readings give that length: the
 * PICTURE clauses of L19 to L31 summed field by field; {@code DBPAUTP0.dbd} L28's {@code BYTES=100};
 * {@code ims/PASFLDBD.DBD} L22's sequential-access definition with its L27
 * {@code DSG001 DATASET DD1=PASFILIP,DD2=PASFILOP,RECORD=(100),RECFM=F}; and the recorded images
 * themselves, every one of which measures exactly one hundred bytes per record with no trailing
 * newline. The field layout this class depends on, offsets zero-based, is
 * {@code PA-ACCT-ID} 0-5 packed, {@code PA-CUST-ID} 6-14 unsigned display,
 * {@code PA-AUTH-STATUS} 15, {@code PA-ACCOUNT-STATUS} 16-25 as five two-byte slots beginning at 16,
 * 18, 20, 22 and 24, {@code PA-CREDIT-LIMIT} 26-31, {@code PA-CASH-LIMIT} 32-37,
 * {@code PA-CREDIT-BALANCE} 38-43, {@code PA-CASH-BALANCE} 44-49 all packed,
 * {@code PA-APPROVED-AUTH-CNT} 50-51 and {@code PA-DECLINED-AUTH-CNT} 52-53 as signed big-endian
 * halfwords, {@code PA-APPROVED-AUTH-AMT} 54-59 and {@code PA-DECLINED-AUTH-AMT} 60-65 packed, and
 * {@code FILLER} 66-99.
 *
 * <p>Trade-offs: S8, every case uses its OWN account identifiers, produced by adding a per-case
 * offset to the identifier the image carries. The recorded images all carry the same two accounts, so
 * without the offsets two cases would contend for one row and the outcome of each would depend on the
 * order they ran in. The cost is a little arithmetic at each call site and one offset constant per
 * case; what it buys is that any case may be run alone or in any order, and that no cleanup has to be
 * sequenced. Sharing one row and deleting it between cases is the cheaper way to write the same
 * fixtures and is rejected because it makes execution order a hidden input.
 *
 * <p>Assumptions: the poisoned-transaction behaviour of this engine is the easiest way to write a
 * case here that fails for the wrong reason, so each refusal owns its transaction. Once a statement
 * inside a transaction has raised, every later statement in it is refused until rollback -- so a case
 * that asserted a refusal and then, in the same transaction, asserted an acceptance would fail the
 * second assertion because the transaction was already aborted, and the failure would name the
 * aborted transaction rather than the schema. Both refusals here therefore live in cases of their own
 * and assert nothing after the refusal, and every unit of work runs through
 * {@link #inTransaction(Function)}, which opens a persistence context, commits or rolls back, and
 * closes it.
 *
 * <p>Trade-offs: a real engine in a container is used rather than an in-memory database, at the cost
 * of container start-up on every run and of a container runtime the host must provide -- so these
 * cases cannot run where that runtime is absent. The cost is accepted because every property above is
 * specific to the target engine: a range check, a declared numeric precision that refuses an
 * over-wide value, and the catalogue itself, which is what two of these cases interrogate. An
 * in-memory engine would either reject the migration outright or accept it with different semantics,
 * so the faster option is not a cheaper version of this assertion but a much weaker one.
 *
 * <p>Alternatives Considered: the module's Spring application context is deliberately NOT started,
 * and a minimal persistence unit is assembled instead. Starting it was evaluated and rejected on two
 * specific grounds. Its base profile resolves nine placeholders that carry no default -- the
 * datasource and migration credentials, the token issuer, the client identifier, two queue
 * references, a signing key, a message authentication key and a peer service address -- which the
 * test profile lists at its foot as deliberate omissions precisely because a committed file may hold
 * none of them. And the security configuration builds its decoder from the issuer location eagerly,
 * so a context refresh performs discovery against a live endpoint; supplying one would mean running
 * an HTTP stand-in inside a repository test. The persistence unit assembled in
 * {@link #startEngineAndPersistence()} maps exactly one entity and exposes exactly one repository,
 * so what boots is the subject and nothing else.
 *
 * <p>Alternatives Considered: the container is wired by hand rather than through the Spring Boot
 * service-connection annotation, because that annotation's module is not on this module's test class
 * path at all -- the declared Testcontainers dependencies are the engine, its JUnit integration and
 * the cloud emulator, and no Spring Boot Testcontainers artifact accompanies them. The dynamic
 * property mechanism was the documented second choice, and it is also unnecessary once no application
 * context is refreshed: the container's own coordinates are handed straight to the pool and to the
 * migration.
 *
 * <p>Assumptions: the schema under test comes from the module's own migration, applied inside the
 * container by {@link #startEngineAndPersistence()}, and automatic schema generation stays off.
 * Generated definitions would emit tables and columns from entity metadata and would emit neither
 * {@code ck_pending_auth_summary_counts} nor the catalogue shape two of these cases interrogate, so a
 * green run against them would establish nothing about the schema that is deployed. Schema creation
 * is permitted for the migration alone, because a container started for a test has never had the
 * bootstrap that owns the eight schemas and their roles applied to it; no case here creates a schema,
 * a role or a grant of its own.
 *
 * <p>Assumptions: every native statement below names its table WITHOUT a schema qualifier and
 * resolves it through the connection search path that {@link #SEARCH_PATH_PIN} sets, which is the
 * same statement the deployed pool runs on every connection. Qualifying a table name in a test query
 * is the one form of this assertion that cannot detect the failure it appears to guard: it passes
 * while the pin is correct and goes on passing once the pin is removed. The two catalogue queries are
 * the deliberate exception, since asking which constraint or index exists means naming the schema as
 * a value -- and that value is quoted nowhere and doubled nowhere because it is a string rather than
 * an identifier.
 *
 * <p>Assumptions: {@code pautsum0-negative-zero-decode-only.bin} is present in the same directory as
 * the six images this class loads and is deliberately NOT loaded. A packed negative zero is a codec
 * concern and the shared kernel proves it exhaustively, including that only a sign-preserving encode
 * reproduces its bytes; loading it here would restate that proof in a second suite, so one change
 * would fail twice and a reader could not tell which assertion was authoritative. The exclusion is
 * recorded so that nobody completes the set by adding it.
 */
@Testcontainers
class PendingAuthSummaryRepositoryIT {

    /**
     * The engine image, pinned by content digest rather than by tag.
     *
     * <p>Assumptions: a tag is mutable and a digest is not, so pinning the digest is what makes two
     * runs of this class run against the same engine build. The sibling integration test in the
     * fixtures package pins this same digest, which keeps one image in the host's cache for both.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The engine every case in this class runs against, started once for the class. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * The schema name, as a VALUE for the catalogue queries rather than as an identifier.
     *
     * <p>Assumptions: it is undoubled and unquoted here on purpose. The migration creates every
     * object unqualified because this schema's name is a reserved word, so an identifier occurrence
     * would have to be double-quoted -- but a catalogue predicate compares it as text, where quoting
     * would make the comparison fail to match anything.</p>
     */
    private static final String SCHEMA_NAME = "authorization";

    /**
     * The statement that pins the search path, reproduced from the deployed pool's configuration.
     *
     * <p>Assumptions: the deployed service sets exactly this on every connection, so running it here
     * is what lets an unqualified table name in a test resolve the way it resolves in production.
     * The schema name IS double-quoted here, because in this statement it is an identifier.</p>
     */
    private static final String SEARCH_PATH_PIN = "SET search_path TO \"authorization\"";

    /** The registered layout name for the hundred-byte root segment. */
    private static final String LAYOUT = "PAUTSUM0";

    /** The declared record length of that segment, from {@code DBPAUTP0.dbd} L28. */
    private static final int SEGMENT_LENGTH = 100;

    /** The table whose schema truth this class asserts. */
    private static final String TABLE = "pending_auth_summary";

    /** The primary-key constraint's declared name, from {@code V1__authorization.sql}. */
    private static final String PRIMARY_KEY = "pk_pending_auth_summary";

    /** The counter range check's declared name, from {@code V1__authorization.sql}. */
    private static final String COUNTS_CHECK = "ck_pending_auth_summary_counts";

    /** The state this engine raises when a value exceeds a declared numeric precision. */
    private static final String NUMERIC_OVERFLOW = "22003";

    /** The state this engine raises when a check constraint refuses a value. */
    private static final String CHECK_VIOLATION = "23514";

    /**
     * The bound every additive statement saturates its running total at.
     *
     * <p>Assumptions: the bound is taken from the domain type rather than written as a literal here, so a
     * case cannot pass a ceiling the entity would disagree with. The production callers pass this same
     * constant.</p>
     */
    private static final BigDecimal CEILING = PendingAuthSummary.MONEY_MAX_MAGNITUDE;

    /** The negative bound the subtracting statement saturates at, being the negation of {@link #CEILING}. */
    private static final BigDecimal FLOOR = CEILING.negate();

    /**
     * The offsets that keep each case's rows to itself, one per case.
     *
     * <p>Trade-offs: eleven named constants are more verbose than one stride multiplied by a case
     * ordinal, and they are preferred because an ordinal would have to be looked up to be read while
     * a name says at its call site which case owns the row. Each case consumes at most four
     * consecutive identifiers, so a spacing of one hundred cannot collide.</p>
     */
    private static final long OFFSET_CANONICAL = 100L;

    /** The offset owned by the absent-account case, whose row is never inserted. */
    private static final long OFFSET_ABSENT = 200L;

    /** The offset owned by the account-status slot case. */
    private static final long OFFSET_STATUS_SLOTS = 300L;

    /** The offset owned by the halfword counter case, which inserts two rows. */
    private static final long OFFSET_COUNTERS = 400L;

    /** The offset owned by the arbitrary authorization-status case. */
    private static final long OFFSET_AUTH_STATUS = 500L;

    /** The offset owned by the over-wide amount refusal. */
    private static final long OFFSET_MONEY_REFUSAL = 600L;

    /** The offset owned by the counter range refusal. */
    private static final long OFFSET_COUNTS_REFUSAL = 700L;

    /** The offset owned by the dropped-filler case. */
    private static final long OFFSET_FILLER = 800L;

    /** The offset owned by the parentage case, which also inserts two child rows. */
    private static final long OFFSET_PARENTAGE = 900L;

    /** The offset owned by the unload-shape case, which inserts four rows. */
    private static final long OFFSET_UNLOAD = 1000L;

    /** The offset owned by the approved-contribution case. */
    private static final long OFFSET_CONTRIBUTION = 1100L;

    /** The offset owned by the declined-contribution case. */
    private static final long OFFSET_DECLINE = 1200L;

    /** The offset owned by the absent-account contribution case, whose row is never inserted. */
    private static final long OFFSET_ABSENT_CONTRIBUTION = 1300L;

    /** The offset owned by the reversal case. */
    private static final long OFFSET_REVERSAL = 1400L;

    /** The offset owned by the presence-query case, which inserts the one account it finds. */
    private static final long OFFSET_PRESENCE = 1500L;

    /** The offset owned by the presence query's absent account, which is never inserted. */
    private static final long OFFSET_PRESENCE_ABSENT = 1600L;

    /** The offset owned by the insert-if-absent case, which offers three rows over two identifiers. */
    private static final long OFFSET_INSERT_IF_ABSENT = 1700L;

    /** The offset owned by the cash-balance assignment case, whose row starts with a non-zero one. */
    private static final long OFFSET_CASH_BALANCE = 1800L;

    /**
     * The offset owned by the interleaved-contribution case, whose single row is written twice.
     */
    // WHY : Assumptions: the case takes an offset of its own even though it writes ONE row, because two
    //       persistence contexts are open over that row at the same time. A row shared with any other
    //       case would let this case's second commit be attributed to that case's write, and the whole
    //       property being asserted is which of two writes reached the row.
    private static final long OFFSET_INTERLEAVED = 1900L;

    /**
     * The offset owned by the guarded-reservation case, whose single row has its headroom spent in steps.
     */
    // WHY : Assumptions: it takes its own offset rather than sharing the approved-contribution case's row,
    //       because it reserves against that row FOUR times and leaves the balance equal to the limit.
    //       Sharing would make the other case's assertions depend on whether this one had already run,
    //       which is exactly the coupling the per-case offsets exist to remove.
    private static final long OFFSET_GUARDED_RESERVATION = 1900L;

    /**
     * The offset owned by the additive-saturation case, whose totals begin just short of the bound.
     */
    // WHY : Assumptions: it takes its own offset because it seeds money values UNLIKE every other case's
    //       -- both running totals within one accumulation of the column's greatest magnitude -- so a
    //       shared row would make another case's amount assertions depend on whether this one had run.
    //       Assumptions: both saturation offsets sit BELOW the two ordered walks, whose highest case
    //       asserts its own third row is the greatest identifier the table holds.
    private static final long OFFSET_SATURATION = 2000L;

    /**
     * The offset owned by the reversal-saturation case, whose totals are driven below the negative bound.
     */
    // WHY : Assumptions: the subtracting statement takes an offset separate from the additive one because
    //       it leaves both totals at the NEGATIVE bound and both counters below zero, which is a row state
    //       no other case's assertions would survive sharing.
    private static final long OFFSET_SATURATION_REVERSAL = 2100L;

    /**
     * The offset owned by the checkpoint-walk case, which inserts three consecutive accounts.
     */
    // WHY : Assumptions: this offset sits above every KEYED case and the three rows it produces are
    //       consecutive from it. Every keyed case addresses one row, so a neighbouring row is invisible
    //       to it; an ORDERED walk is the one shape here that would see another case's rows, which is
    //       why the two walks take the top of the identifier space between them.
    // WHY : Assumptions: a second ordered case, the key-projected walk, owns a HIGHER offset than this
    //       one, so this is not the top of the identifier space. Both walks bound every read by a LIMIT
    //       that stops inside their own three rows, which is what keeps each independent of the other
    //       whichever order the two run in.
    private static final long OFFSET_WALK = 5000L;

    /**
     * The offset owned by the key-projected walk, which inserts three consecutive accounts.
     */
    // WHY : Assumptions: this offset is the HIGHEST in the class, because that case asserts its third
    //       row is the greatest identifier the table holds -- which is how it shows the projection is
    //       reading the top of the ordering rather than an arbitrary page of it. A case added later must
    //       take an offset below this one, or move this one up.
    private static final long OFFSET_KEY_WALK = 6000L;

    /** The reference hundred-byte record, whose five status slots are all distinct. */
    private static final String CANONICAL_FIXTURE = "fixtures/pautsum0-canonical.bin";

    /** The parent of two detail rows, counters and accumulators already non-zero. */
    private static final String PURGE_PARENT_FIXTURE = "fixtures/pautsum0-purge-parent.bin";

    /** Two records whose halfword counters are built from line-terminator and high bytes. */
    private static final String TERMINATOR_FIXTURE = "fixtures/pautsum0-line-terminator-bytes.bin";

    /** One record carrying thirty-four bytes of content in the interval that is dropped. */
    private static final String FILLER_FIXTURE = "fixtures/pautsum0-filler-nonblank.bin";

    /** The bare sequential unload shape, records ascending by account. */
    private static final String UNLOAD_GSAM_FIXTURE = "fixtures/unload-gsam-summary-100.bin";

    /** The prefixed unload shape, the same records descending by account. */
    private static final String UNLOAD_PREFIXED_FIXTURE = "fixtures/unload-prefixed-summary-100.bin";

    /** The decoded field name of the packed key at offsets 0 to 5. */
    private static final String ACCOUNT_ID_FIELD = "PA-ACCT-ID";

    /** The decoded field name of the unsigned display customer identifier at offsets 6 to 14. */
    private static final String CUSTOMER_ID_FIELD = "PA-CUST-ID";

    /** The decoded field name of the single unconstrained status character at offset 15. */
    private static final String AUTH_STATUS_FIELD = "PA-AUTH-STATUS";

    /** The decoded field name of the ten-byte interval holding the five two-byte slots. */
    private static final String ACCOUNT_STATUS_FIELD = "PA-ACCOUNT-STATUS";

    /** The decoded field name of the mirrored credit limit at offsets 26 to 31. */
    private static final String CREDIT_LIMIT_FIELD = "PA-CREDIT-LIMIT";

    /** The decoded field name of the mirrored cash credit limit at offsets 32 to 37. */
    private static final String CASH_LIMIT_FIELD = "PA-CASH-LIMIT";

    /** The decoded field name of the running authorization balance at offsets 38 to 43. */
    private static final String CREDIT_BALANCE_FIELD = "PA-CREDIT-BALANCE";

    /** The decoded field name of the cash balance at offsets 44 to 49. */
    private static final String CASH_BALANCE_FIELD = "PA-CASH-BALANCE";

    /** The decoded field name of the approved halfword counter at offsets 50 to 51. */
    private static final String APPROVED_COUNT_FIELD = "PA-APPROVED-AUTH-CNT";

    /** The decoded field name of the declined halfword counter at offsets 52 to 53. */
    private static final String DECLINED_COUNT_FIELD = "PA-DECLINED-AUTH-CNT";

    /** The decoded field name of the approved accumulator at offsets 54 to 59. */
    private static final String APPROVED_AMOUNT_FIELD = "PA-APPROVED-AUTH-AMT";

    /** The decoded field name of the declined accumulator at offsets 60 to 65. */
    private static final String DECLINED_AMOUNT_FIELD = "PA-DECLINED-AUTH-AMT";

    /** The decoded field name of the trailing interval at offsets 66 to 99, which maps to no column. */
    private static final String FILLER_FIELD = "FILLER";

    /** The width of one account-status slot, from {@code PIC X(02)} at {@code CIPAUSMY.cpy} L22. */
    private static final int SLOT_WIDTH = 2;

    /**
     * The date component the two child rows share.
     *
     * <p>Assumptions: the detail table bounds this column to a five-digit year-and-day shape whose day
     * part lies between 1 and 366, so an arbitrary integer would be refused by a constraint this class
     * does not assert and does not own. This value satisfies that shape.</p>
     */
    private static final int CHILD_AUTH_DATE = 23001;

    /**
     * The time component that distinguishes the first child row.
     *
     * <p>Assumptions: the detail table bounds this column so that its minute and second components
     * each stay at or below 59, so a value has to be a real time of day rather than any integer below
     * the upper bound. This is 10:15:30 with no milliseconds.</p>
     */
    private static final int FIRST_CHILD_AUTH_TIME = 101530000;

    /** The time component that distinguishes the second child row, one second later. */
    private static final int SECOND_CHILD_AUTH_TIME = 101531000;

    /**
     * The card number both child rows carry.
     *
     * <p>Assumptions: it is a synthetic sixteen-character value and is neither masked nor asserted
     * here. Masking is the mapper's concern and no assertion in this class reads this value; it is
     * present only because the detail table declares the column required.</p>
     */
    private static final String CHILD_CARD_NUMBER = "4000000000000001";

    /**
     * The catalogue question that settles key arity: which columns the primary key names, in order.
     *
     * <p>Assumptions: the schema is a bound VALUE and the table names in the {@code FROM} clause are
     * catalogue views, which is the one place a schema may be named in this class. Ordering by
     * ordinal position is what makes the result a key SHAPE rather than an unordered set, so a
     * two-column key could not pass by returning its columns in a convenient order.</p>
     */
    private static final String PRIMARY_KEY_COLUMNS = """
            SELECT kcu.column_name
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON kcu.constraint_schema = tc.constraint_schema
               AND kcu.constraint_name = tc.constraint_name
             WHERE tc.table_schema = ?
               AND tc.table_name = ?
               AND tc.constraint_type = 'PRIMARY KEY'
             ORDER BY kcu.ordinal_position
            """;

    /** The catalogue question that settles what the primary-key constraint is called. */
    private static final String PRIMARY_KEY_NAME = """
            SELECT tc.constraint_name
              FROM information_schema.table_constraints tc
             WHERE tc.table_schema = ?
               AND tc.table_name = ?
               AND tc.constraint_type = 'PRIMARY KEY'
            """;

    /**
     * The catalogue question that enumerates every check constraint on the table.
     *
     * <p>Assumptions: the provider catalogue is read rather than the portable view, because only the
     * former distinguishes a check from the other constraint kinds by a single type code. Restricting
     * to that code is what lets the result be read as the COMPLETE set of checks, which is the form
     * the deliberate absence of a check over {@code auth_status} has to be asserted in.</p>
     */
    private static final String CHECK_CONSTRAINTS = """
            SELECT con.conname
              FROM pg_constraint con
              JOIN pg_class rel ON rel.oid = con.conrelid
              JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
             WHERE nsp.nspname = ?
               AND rel.relname = ?
               AND con.contype = 'c'
             ORDER BY con.conname
            """;

    /** The catalogue question that enumerates every index object on the table. */
    private static final String INDEX_NAMES = """
            SELECT indexname
              FROM pg_indexes
             WHERE schemaname = ?
               AND tablename = ?
             ORDER BY indexname
            """;

    /** Every column of one summary row, used to prove no column received a filler byte. */
    private static final String SELECT_WHOLE_ROW =
            "SELECT * FROM pending_auth_summary WHERE account_id = ?";

    /**
     * A summary insert naming only the two required columns plus one counter.
     *
     * <p>Assumptions: this statement exists because the entity refuses an out-of-domain counter
     * before the engine ever sees it, and the subject of the case that uses it is the SCHEMA's half of
     * that invariant. Going through the entity would assert the Java guard and leave the check
     * constraint unexercised, which is exactly the split the migration itself records: two paths write
     * these columns, so an invariant asserted in one cannot bind the other.</p>
     */
    private static final String INSERT_SUMMARY_COUNTER = """
            INSERT INTO pending_auth_summary (account_id, customer_id, approved_auth_cnt)
            VALUES (?, ?, ?)
            """;

    /**
     * A detail insert naming exactly the columns that table declares as required.
     *
     * <p>Assumptions: eight columns is the whole {@code NOT NULL} set of
     * {@code pending_auth_detail}, so this is the narrowest child row that table will accept. The
     * remaining columns are left unset deliberately: this class's subject is the parent key, and
     * supplying optional child columns would drag the detail table's own domain checks into a case
     * that does not assert them and that a sibling class owns.</p>
     */
    private static final String INSERT_DETAIL_CHILD = """
            INSERT INTO pending_auth_detail (
                account_id, auth_date, auth_time, card_num, transaction_amt, approved_amt,
                transaction_id, match_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** The child rows hanging off one parent, addressed by the parent's single key column. */
    private static final String SELECT_CHILD_TIMES = """
            SELECT auth_time::text
              FROM pending_auth_detail
             WHERE account_id = ?
             ORDER BY auth_time
            """;

    /** Every stored account identifier, ascending, used by the unload-shape case. */
    private static final String SELECT_ACCOUNT_IDS = """
            SELECT account_id::text
              FROM pending_auth_summary
             WHERE account_id BETWEEN ? AND ?
             ORDER BY account_id
            """;

    /**
     * The highest stored account identifier, used to prove the walk case owns the top of the ordering.
     *
     * <p>Assumptions: the walk case asserts EXACT pages, so it can only do so from a position above which
     * nothing else in this class stores a row. This statement is how that requirement is checked rather
     * than assumed, because the cases share one table and JUnit does not order them.</p>
     */
    private static final String SELECT_MAX_ACCOUNT_ID = """
            SELECT max(account_id)::text
              FROM pending_auth_summary
            """;

    /**
     * The pool the persistence unit and every native statement share.
     *
     * <p>Assumptions: one pool rather than two is what keeps the search-path pin uniform. A second
     * connection route opened straight from the driver would not run {@link #SEARCH_PATH_PIN}, so an
     * unqualified name would resolve in one route and fail in the other for reasons unrelated to the
     * schema.</p>
     */
    private static HikariDataSource dataSource;

    /** The single-entity persistence unit this class assembles, held for teardown. */
    private static LocalContainerEntityManagerFactoryBean persistenceUnit;

    /** The factory every persistence context in this class is created from. */
    private static EntityManagerFactory entityManagerFactory;

    /**
     * Applies the migration, then assembles the one-entity persistence unit the cases share.
     *
     * <p>Assumptions: the order matters and is not interchangeable. The migration runs first so that
     * the tables exist before any persistence context opens; automatic schema generation is off, so
     * nothing else would create them. Schema creation is enabled for the migration although the
     * deployed configuration disables it, because deployment relies on the bootstrap that owns the
     * eight schemas having run first and a bare container has had no bootstrap.</p>
     *
     * <p>Trade-offs: a migration failure is deliberately not caught. It aborts the class before any
     * case runs, which reports the migration as the cause instead of letting ten cases each fail on a
     * missing table and leaving a reader to infer why.</p>
     *
     * <p>Assumptions: the persistence unit is given exactly one managed type. Scanning the domain
     * package would map four entities and would then require the three repositories that read them,
     * none of which this class asserts and none of whose tables it interrogates; mapping one type
     * makes the unit's contents identical to this class's subject.</p>
     */
    @BeforeAll
    static void startEngineAndPersistence() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA_NAME)
                .defaultSchema(SCHEMA_NAME)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setConnectionInitSql(SEARCH_PATH_PIN);
        dataSource.setMaximumPoolSize(4);

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        // WHY : Assumptions: the provider is told not to emit any definition, which restates the
        //       deployed profile's own setting rather than relying on a default. Emitting one would
        //       replace the migrated table -- and with it the range check and the catalogue shape two
        //       cases below read -- with a table derived from entity metadata.
        adapter.setGenerateDdl(false);

        persistenceUnit = new LocalContainerEntityManagerFactoryBean();
        persistenceUnit.setDataSource(dataSource);
        persistenceUnit.setPersistenceUnitName("carddemo-authorization-summary-it");
        persistenceUnit.setManagedTypes(
                PersistenceManagedTypes.of(PendingAuthSummary.class.getName()));
        persistenceUnit.setJpaVendorAdapter(adapter);
        // WHY : Assumptions: no default schema is handed to the provider, which mirrors the deployed
        //       configuration's deliberate omission of one. The pin belongs to the connection, so a
        //       provider-level default would give this schema a second resolution route that could
        //       stay correct while the connection's own pin was wrong.
        persistenceUnit.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        persistenceUnit.afterPropertiesSet();
        entityManagerFactory = persistenceUnit.getObject();
    }

    /**
     * Releases the persistence unit and the pool once every case has run.
     *
     * <p>Assumptions: the container is released by the Testcontainers extension and is deliberately
     * not closed here, whereas the pool and the persistence unit are this class's own and would keep
     * non-daemon threads alive after the last case if they were left open.</p>
     */
    @AfterAll
    static void stopPersistence() {
        if (persistenceUnit != null) {
            persistenceUnit.destroy();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Confirms the primary key names exactly one column, {@code account_id}, and is called what the
     * migration calls it.
     *
     * <p>Assumptions: S1 as recorded on this class. The live catalogue is interrogated rather than the
     * migration text, because the migration text is the input to this schema and reading it back would
     * assert only that a file says what it says. The identifier type of the repository is asserted in
     * the same case because it is the same fact seen from the Java side: a repository over a single
     * scalar identifier cannot address a row whose key is a tuple, so the two assertions corroborate
     * one another and separating them would leave each looking like a matter of taste.</p>
     *
     * @throws SQLException if the container refuses a connection or either catalogue query fails,
     *     which is a setup fault rather than the property under test and must surface as itself
     */
    @Test
    @DisplayName("the primary key is exactly one column, account_id, under its declared name")
    void thePrimaryKeyIsExactlyOneColumnNamedAccountId() throws SQLException {
        assertThat(queryText(PRIMARY_KEY_COLUMNS, SCHEMA_NAME, TABLE))
                .as("one sequence field at DBPAUTP0.dbd L30 admits exactly one key column")
                .containsExactly("account_id");
        assertThat(queryText(PRIMARY_KEY_NAME, SCHEMA_NAME, TABLE))
                .containsExactly(PRIMARY_KEY);

        ResolvableType repositoryType =
                ResolvableType.forClass(JpaRepository.class, PendingAuthSummaryRepository.class);
        assertThat(repositoryType.getGeneric(0).resolve())
                .as("the aggregate the repository addresses")
                .isEqualTo(PendingAuthSummary.class);
        assertThat(repositoryType.getGeneric(1).resolve())
                .as("a single scalar identifier, which a composite key could not be expressed as")
                .isEqualTo(Long.class);
    }

    /**
     * Confirms the canonical image is stored and retrieved by its account with every column intact.
     *
     * <p>Assumptions: {@code pautsum0-canonical.bin} is ONE hundred-byte record with no trailing
     * newline, and this case reads the offsets recorded on this class for the account, the customer,
     * the authorization status, the ten-byte status interval, the four packed money fields at 26, 32,
     * 38 and 44, the two halfword counters at 50 and 52, and the two packed accumulators at 54 and 60.
     * Its credit balance is NEGATIVE at {@code -100.00}: the low nibble of the last byte of that
     * packed field is {@code D}, which is the negative sign, so a reader that ignored the sign would
     * return a positive hundred and every length and scale assertion here would still pass. Asserting
     * the sign is what separates a correct packed reader from a plausible one, and it is why money
     * never touches a binary floating-point type at any hop of this migration.</p>
     *
     * <p>Assumptions: every amount is compared by VALUE rather than by equality. Equality on an exact
     * decimal is scale-sensitive, so {@code 100.00} is unequal to {@code 100.0} while both are the
     * same quantity; comparing by value asserts the quantity, and the separate scale assertion below
     * asserts the storage contract of a two-place column on its own terms.</p>
     *
     * <p>Trade-offs: the row is read back through a persistence context that never saw it written,
     * which costs one more context per case. A read through the writing context could be answered from
     * that context's own identity map, so it would assert the object that was handed in rather than
     * the row the engine stored -- and this case exists to assert the row.</p>
     */
    @Test
    @DisplayName("the canonical row is retrieved by its account with every column intact")
    void theCanonicalRowIsRetrievedByItsAccountWithEveryColumnIntact() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        PendingAuthSummary stored = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_CANONICAL)));
        long accountId = stored.getAccountId();

        PendingAuthSummary reread = read(accountId).orElseThrow();
        assertThat(reread.getAccountId()).isEqualTo(accountId);
        assertThat(reread.getCustomerId()).isEqualTo(451L);
        assertThat(reread.getAuthStatus()).isEqualTo("A");
        assertThat(reread.getCreditLimit()).isEqualByComparingTo("5000.00");
        assertThat(reread.getCashLimit()).isEqualByComparingTo("1000.00");
        assertThat(reread.getCreditBalance())
                .as("the packed sign nibble is D, so this balance survives storage as NEGATIVE")
                .isEqualByComparingTo("-100.00")
                .isNegative();
        assertThat(reread.getCashBalance()).isEqualByComparingTo("0.00");
        assertThat(reread.getApprovedAuthCount()).isEqualTo((short) 42);
        assertThat(reread.getDeclinedAuthCount()).isEqualTo((short) 7);
        assertThat(reread.getApprovedAuthAmount()).isEqualByComparingTo("4200.00");
        assertThat(reread.getDeclinedAuthAmount()).isEqualByComparingTo("700.00");
        assertThat(reread.getCreditBalance().scale())
                .as("a NUMERIC(11,2) column returns two places, whatever scale went in")
                .isEqualTo(2);
    }

    /**
     * Confirms an account with no summary yields an empty result rather than raising.
     *
     * <p>Assumptions: S2 as recorded on this class -- the reference program's three-way evaluate at
     * {@code cbl/COPAUS0C.cbl} L979-L996 treats a missing segment as an ordinary outcome and reserves
     * its error path for an unexpected status. The account used here is never inserted by any case,
     * which is what makes the absence a property of the schema rather than of execution order.</p>
     *
     * <p>Assumptions: both halves are asserted and neither implies the other. That nothing was raised
     * is asserted first, because a lookup that threw would fail the emptiness assertion for the wrong
     * reason and the failure would name the exception rather than the contract.</p>
     */
    @Test
    @DisplayName("an account with no summary yields an empty result and raises nothing")
    void anAccountWithNoSummaryYieldsAnEmptyResultRatherThanRaising() {
        long absentAccount = accountOf(decode(bytes(CANONICAL_FIXTURE)), OFFSET_ABSENT);

        assertThatCode(() -> read(absentAccount)).doesNotThrowAnyException();
        assertThat(read(absentAccount))
                .as("no pending authorization for an account is a normal outcome, not a fault")
                .isEmpty();
    }

    /**
     * Confirms each of the five account-status columns holds the value from its own two-byte slot.
     *
     * <p>Assumptions: S4 as recorded on this class. The registered layout carries
     * {@code PA-ACCOUNT-STATUS} as ONE ten-byte text field beginning at offset 16, because that is
     * what {@code PIC X(02) OCCURS 5 TIMES} occupies; the five slots are therefore taken from that
     * decoded interval at character positions 0, 2, 4, 6 and 8, matching record offsets 16, 18, 20, 22
     * and 24. {@code pautsum0-canonical.bin} carries five DISTINCT values there, {@code A1} through
     * {@code E5}, which is the property that makes this case able to fail: with five equal values a
     * transposition or an off-by-one in the interval would pass unnoticed.</p>
     */
    @Test
    @DisplayName("each of the five account-status columns holds the value from its own slot")
    void eachOfTheFiveAccountStatusColumnsHoldsItsOwnSlot() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        PendingAuthSummary stored = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_STATUS_SLOTS)));

        PendingAuthSummary reread = read(stored.getAccountId()).orElseThrow();
        assertThat(reread.getAccountStatus1()).isEqualTo("A1");
        assertThat(reread.getAccountStatus2()).isEqualTo("B2");
        assertThat(reread.getAccountStatus3()).isEqualTo("C3");
        assertThat(reread.getAccountStatus4()).isEqualTo("D4");
        assertThat(reread.getAccountStatus5()).isEqualTo("E5");
    }

    /**
     * Confirms the two counters store as signed halfwords, negatives included.
     *
     * <p>Assumptions: {@code pautsum0-line-terminator-bytes.bin} is TWO hundred-byte records with no
     * trailing newline, and this case reads the two {@code PIC S9(04) COMP} fields at offsets 50 and
     * 52 as signed big-endian halfwords. The first record's bytes are {@code 0D 0A} and {@code 0A 0A},
     * which are 3338 and 2570; the second record's are {@code FF 0A} and {@code FF 0D}, which as
     * SIGNED halfwords are -246 and -243 and which an unsigned reader would report as large positives
     * instead.</p>
     *
     * <p>Assumptions: those byte pairs were chosen BECAUSE they are line-terminator sequences -- a
     * carriage return followed by a line feed, and two line feeds -- so any text-mode or
     * newline-normalising read of the image corrupts them before a single assertion runs. The image is
     * therefore read as bytes and sliced at a fixed stride, and its length is asserted with no
     * allowance for a trailing newline.</p>
     *
     * <p>Assumptions: a negative counter is reachable rather than hypothetical, which is why the
     * second record matters. The purge program subtracts from these fields as each aged authorization
     * is removed -- {@code cbl/CBPAUP0C.cbl} L288 from the approved count and its L291 from the
     * declined count -- so an unsigned target would be wrong the first time a total was reduced. All
     * four values also lie inside the four-digit domain, so {@code ck_pending_auth_summary_counts}
     * admits every one of them and this case asserts storage rather than refusal.</p>
     */
    @Test
    @DisplayName("the two counters store as signed halfwords, negatives included")
    void theTwoCountersStoreAsSignedHalfwordsNegativesIncluded() {
        byte[] image = bytes(TERMINATOR_FIXTURE);
        assertThat(image)
                .as("two records at a hundred bytes each, with no newline between or after them")
                .hasSize(2 * SEGMENT_LENGTH);

        Map<String, Object> first = decode(segment(image, 0));
        Map<String, Object> second = decode(segment(image, 1));
        PendingAuthSummary storedFirst = inTransaction(
                repository -> repository.save(rehydrate(first, OFFSET_COUNTERS)));
        PendingAuthSummary storedSecond = inTransaction(
                repository -> repository.save(rehydrate(second, OFFSET_COUNTERS)));

        PendingAuthSummary rereadFirst = read(storedFirst.getAccountId()).orElseThrow();
        assertThat(rereadFirst.getApprovedAuthCount())
                .as("0x0D0A, a carriage return and a line feed read as the halfword they are")
                .isEqualTo((short) 3338);
        assertThat(rereadFirst.getDeclinedAuthCount())
                .as("0x0A0A, two line feeds")
                .isEqualTo((short) 2570);

        PendingAuthSummary rereadSecond = read(storedSecond.getAccountId()).orElseThrow();
        assertThat(rereadSecond.getApprovedAuthCount())
                .as("0xFF0A is negative as a signed halfword, which an unsigned reader would miss")
                .isEqualTo((short) -246);
        assertThat(rereadSecond.getDeclinedAuthCount()).isEqualTo((short) -243);
        assertThat(rereadSecond.getCreditBalance())
                .as("the widest negative balance the eleven-digit column carries at full scale")
                .isEqualByComparingTo("-12345678.90");
    }

    /**
     * Confirms an arbitrary authorization-status character is accepted, because no check bounds it.
     *
     * <p>Assumptions: S5 as recorded on this class -- {@code cpy/CIPAUSMY.cpy} L21 declares
     * {@code PA-AUTH-STATUS PIC X(01)} with no {@code 88}-level condition name after it, whereas
     * {@code CIPAUDTY.cpy} L46-L52 supplies four and then two for the detail segment's two flags,
     * which is where this schema's check constraints come from. With no closed domain to promote there
     * is nothing to enforce, and a value the baseline can write must therefore be stored.</p>
     *
     * <p>Assumptions: the case has two halves and each closes a gap the other leaves. The catalogue
     * half asserts the COMPLETE set of check constraints on the table, so it fails if any check is
     * added over this column under any name; the behavioural half stores a character no enumeration
     * would contain, so it fails if a check is added that the catalogue half somehow satisfied. A
     * behavioural assertion alone would pass against a check whose domain happened to include the
     * chosen character.</p>
     *
     * @throws SQLException if the container refuses a connection or the catalogue query fails, which
     *     is a setup fault rather than the property under test
     */
    @Test
    @DisplayName("an arbitrary authorization-status character is accepted, because no check bounds it")
    void anArbitraryAuthStatusCharacterIsAccepted() throws SQLException {
        assertThat(queryText(CHECK_CONSTRAINTS, SCHEMA_NAME, TABLE))
                .as("the counter range is the ONLY check on this table; auth_status carries none")
                .containsExactly(COUNTS_CHECK);

        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        PendingAuthSummary stored = inTransaction(repository -> repository.save(
                rehydrate(fields, OFFSET_AUTH_STATUS, "Z", amount(fields, CREDIT_LIMIT_FIELD))));

        assertThat(read(stored.getAccountId()).orElseThrow().getAuthStatus())
                .as("a character no enumeration would list, stored because none is enforced")
                .isEqualTo("Z");
    }

    /**
     * Confirms the only index object on this table is the primary key's own backing index.
     *
     * <p>Assumptions: S6 as recorded on this class. What looks like a secondary path in
     * {@code DBPAUTP0.dbd} L31-L32 is resolved by {@code ims/DBPAUTX0.dbd}, whose L29 sequences on a
     * six-byte packed field and whose L30-L31 name {@code INDEX=ACCNTID} -- the ROOT sequence field.
     * That is the primary index a hierarchical direct-access database requires structurally, and a
     * primary key over the same single ascending column reproduces it, so one index object is the
     * correct count and a second would be an invention.</p>
     *
     * <p>Assumptions: the assertion is exact rather than a containment test, because a containment
     * test cannot express a deliberate absence -- it would keep passing after a secondary index was
     * added, and this case exists precisely so that adding one is a failure a maintainer has to read
     * this rationale to resolve.</p>
     *
     * @throws SQLException if the container refuses a connection or the catalogue query fails, which
     *     is a setup fault rather than the property under test
     */
    @Test
    @DisplayName("the only index object on this table is the primary key's own")
    void theOnlyIndexObjectOnThisTableIsThePrimaryKeys() throws SQLException {
        assertThat(queryText(INDEX_NAMES, SCHEMA_NAME, TABLE))
                .as("DBPAUTX0 is the HIDAM primary index, which a primary key already supplies")
                .containsExactly(PRIMARY_KEY);
    }

    /**
     * Confirms a twelve-digit amount is refused by this table's narrower money precision.
     *
     * <p>Assumptions: S3 as recorded on this class. Nine integer digits and two fractional digits at
     * {@code cpy/CIPAUSMY.cpy} L23-L26 and L29-L30 give a precision of eleven here, where
     * {@code CIPAUDTY.cpy} L34-L35 and {@code ddl/AUTHFRDS.ddl} L12-L13 give twelve on the sibling
     * tables. This is the REFUSING half of a cross-class pair whose accepting half asserts the same
     * value is stored on the detail table; neither half establishes the split alone, because one shows
     * only that a wide value fits somewhere and the other only that it fits nowhere here.</p>
     *
     * <p>Assumptions: the value reaches the engine at all because the entity's own guard bounds SCALE
     * and not precision -- it refuses a third decimal place and widens a shorter one, and says nothing
     * about how many integer digits precede the point. That is what makes this case an assertion about
     * the COLUMN rather than about the entity, and it also means the entity's declared precision of
     * eleven does not silently truncate on the way through.</p>
     *
     * <p>Assumptions: the identity of the refusal is asserted and not merely that something was
     * refused. A bare failed-write assertion would also be satisfied by a duplicate key or a missing
     * required value, so the state code is unwrapped from the provider's exception chain and compared
     * to the one this engine reserves for a value outside a declared numeric precision -- the same
     * code the migration's own header names as the intended outcome for an over-wide decoded
     * quantity.</p>
     *
     * <p>Assumptions: this case asserts nothing after the refusal and shares its transaction with no
     * other assertion, because this engine refuses every later statement in a transaction that has
     * already raised. An acceptance assertion added below would fail on the aborted transaction and
     * would report a fault that has nothing to do with precision.</p>
     */
    @Test
    @DisplayName("a twelve-digit amount is refused by the narrower summary precision")
    void aTwelveDigitAmountIsRefusedByTheNarrowerSummaryPrecision() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        BigDecimal twelveDigits = new BigDecimal("9999999999.99");

        assertThatThrownBy(() -> inTransaction(repository -> repository.save(
                rehydrate(fields, OFFSET_MONEY_REFUSAL, text(fields, AUTH_STATUS_FIELD),
                        twelveDigits))))
                .as("ten integer digits do not fit a nine-integer-digit column")
                .satisfies(failure -> assertThat(sqlStateOf(failure)).isEqualTo(NUMERIC_OVERFLOW));
    }

    /**
     * Confirms a counter beyond four decimal digits is refused by the declared range check.
     *
     * <p>Assumptions: {@code PIC S9(04) COMP} at {@code cpy/CIPAUSMY.cpy} L27-L28 holds -9999 through
     * 9999, which is narrower than the halfword that stores it, and these are running totals that the
     * decision path increments and the purge path decrements -- so without the check a busy account
     * would store a count the baseline's own field could not represent, and a reader narrowing it back
     * would see the value wrap negative. The refusal is therefore the only outcome that neither loses
     * the count nor stores an unrepresentable one.</p>
     *
     * <p>Alternatives Considered: writing this counter through the entity was rejected, because the
     * entity refuses the value first and the assertion would then prove only that the Java guard
     * works. A native statement is the sole way to present the value to the check constraint, and
     * presenting it is the point: the migration records that two paths write these columns, so an
     * invariant asserted in one of them cannot bind the other.</p>
     *
     * <p>Assumptions: the constraint is asserted BY NAME as well as by state code. The state code
     * alone would be satisfied by any check on the table, and while there is only one today, naming it
     * is what keeps this case correct if another is ever added.</p>
     *
     * <p>Assumptions: this case asserts nothing after the refusal and shares its transaction with no
     * other assertion, for the aborted-transaction reason recorded on this class.</p>
     */
    @Test
    @DisplayName("a counter beyond four decimal digits is refused by the declared range check")
    void aCounterBeyondFourDecimalDigitsIsRefusedByTheRangeCheck() {
        long accountId = accountOf(decode(bytes(CANONICAL_FIXTURE)), OFFSET_COUNTS_REFUSAL);

        assertThatThrownBy(() -> execute(INSERT_SUMMARY_COUNTER, accountId, 451L, 10_000))
                .as("10000 fits the halfword and leaves the four digits the picture declares")
                .satisfies(failure -> {
                    assertThat(sqlStateOf(failure)).isEqualTo(CHECK_VIOLATION);
                    assertThat(constraintOf(failure)).isEqualTo(COUNTS_CHECK);
                });
    }

    /**
     * Confirms the trailing filler reaches no column while the record still measures a hundred bytes.
     *
     * <p>Assumptions: S7 as recorded on this class. {@code pautsum0-filler-nonblank.bin} is ONE
     * hundred-byte record with no trailing newline whose {@code FILLER} interval at offsets 66 to 99
     * carries thirty-four bytes of CONTENT rather than blanks, which is the only way the drop can be
     * demonstrated: a blank filler is indistinguishable from a dropped one once stored. The record
     * still reconciles to the hundred bytes {@code DBPAUTP0.dbd} L28 and {@code ims/PASFLDBD.DBD} L27
     * both declare, so the drop is a mapping decision and not a truncation.</p>
     *
     * <p>Assumptions: the image is deliberately NOT re-encoded here. Its non-blank filler exists to
     * prove the drop is real, and a byte-exact rewrite is a codec property that the shared kernel
     * already proves; asserting it again in this package would make one change fail twice.</p>
     *
     * <p>Assumptions: both halves are needed. The column-name half asserts the row has no filler
     * column under any name, so a column added later fails here; the value half asserts no column that
     * DOES exist received any of the filler bytes, so a filler smuggled into a text column of the
     * right width fails too. Either half alone leaves the other route open.</p>
     *
     * @throws SQLException if the container refuses a connection or the row query fails, which is a
     *     setup fault rather than the property under test
     */
    @Test
    @DisplayName("the filler bytes reach no column while the record still measures one hundred")
    void theFillerBytesReachNoColumnWhileTheRecordStillMeasuresOneHundred() throws SQLException {
        byte[] image = bytes(FILLER_FIXTURE);
        assertThat(image)
                .as("one record of the declared length, with no newline allowance")
                .hasSize(SEGMENT_LENGTH);
        Map<String, Object> fields = decode(image);
        assertThat(fields.get(FILLER_FIELD))
                .as("the image genuinely carries content where a blank filler would carry blanks")
                .isEqualTo("FILLERFILLERFILLERFILLERFILLER1234");

        PendingAuthSummary stored = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_FILLER)));
        Map<String, String> row = wholeRow(stored.getAccountId());

        assertThat(row.keySet())
                .as("sixteen columns: twelve carried fields, the status array expanded to five, "
                        + "and no filler among them")
                .containsExactly("account_id", "customer_id", "auth_status",
                        "account_status_1", "account_status_2", "account_status_3",
                        "account_status_4", "account_status_5",
                        "credit_limit", "cash_limit", "credit_balance", "cash_balance",
                        "approved_auth_cnt", "declined_auth_cnt",
                        "approved_auth_amt", "declined_auth_amt");
        assertThat(row.values())
                .as("no column received any part of the thirty-four dropped bytes")
                .noneMatch(value -> value.contains("FILLER"));
    }

    /**
     * Confirms the purge parent is addressable by its account alone and its two children hang off it.
     *
     * <p>Assumptions: {@code pautsum0-purge-parent.bin} is ONE hundred-byte record with no trailing
     * newline whose counters are 2 and 2 and whose accumulators are {@code 300.00} and {@code 150.00}
     * -- a parent that HAS children, which is what makes it the right image for this case. Only the
     * addressability and the parentage are asserted here; the arithmetic that a purge performs on
     * those counters belongs to the job and service cases that own it, and asserting it here would
     * duplicate their subject.</p>
     *
     * <p>Assumptions: the two children are written by a native statement with literal key components
     * rather than decoded from the paired detail image. The detail layout, its date and time
     * complement domains and its uniqueness rule are a sibling class's subject, and decoding that
     * image here would make this case fail whenever any of them changed, for reasons that have nothing
     * to do with the parent key. The two child keys differ only in their time component, so both hang
     * off one parent, which is the shape the parentage claim needs.</p>
     *
     * <p>Assumptions: the child rows are addressed by {@code account_id} and NOTHING else, which is
     * the assertion. The foreign key on the detail table references
     * {@code pending_auth_summary (account_id)}, and a foreign key can only name the exact column list
     * of a unique constraint -- so a parent key of any other shape could not have been referenced this
     * way. Its refusal behaviour is deliberately not asserted here; that constraint belongs to the
     * sibling class that owns the detail table.</p>
     *
     * @throws SQLException if the container refuses a connection, a child insert fails or the child
     *     query fails, which is a setup fault rather than the property under test
     */
    @Test
    @DisplayName("the purge parent is addressable by its account alone and its two children hang off it")
    void thePurgeParentIsAddressableByItsAccountAloneAndItsTwoChildrenHangOffIt() throws SQLException {
        Map<String, Object> fields = decode(bytes(PURGE_PARENT_FIXTURE));
        PendingAuthSummary parent = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_PARENTAGE)));
        long parentAccount = parent.getAccountId();

        execute(INSERT_DETAIL_CHILD, parentAccount, CHILD_AUTH_DATE, FIRST_CHILD_AUTH_TIME,
                CHILD_CARD_NUMBER, new BigDecimal("150.00"), new BigDecimal("150.00"),
                "TXN000000000001", "P");
        execute(INSERT_DETAIL_CHILD, parentAccount, CHILD_AUTH_DATE, SECOND_CHILD_AUTH_TIME,
                CHILD_CARD_NUMBER, new BigDecimal("150.00"), new BigDecimal("150.00"),
                "TXN000000000002", "P");

        assertThat(read(parentAccount))
                .as("a single key value is the WHOLE key, so one argument addresses the parent")
                .isPresent();
        assertThat(queryText(SELECT_CHILD_TIMES, parentAccount))
                .as("both children are reached by the parent's account alone")
                .containsExactly(String.valueOf(FIRST_CHILD_AUTH_TIME),
                        String.valueOf(SECOND_CHILD_AUTH_TIME));
        assertThat(read(parentAccount).orElseThrow().getApprovedAuthCount())
                .as("the parent arrives with children counted; reducing that count is the purge's")
                .isEqualTo((short) 2);
    }

    /**
     * Confirms both recorded unload shapes insert, whichever order their records arrive in.
     *
     * <p>Assumptions: {@code unload-gsam-summary-100.bin} and
     * {@code unload-prefixed-summary-100.bin} are each TWO hundred-byte records with no trailing
     * newline, at the record length {@code ims/PASFLDBD.DBD} L27 declares as
     * {@code RECORD=(100),RECFM=F}. The two files hold the same two accounts in OPPOSITE orders --
     * ascending in the first and descending in the second -- and that is sound only because a summary
     * record is keyed by its account alone, so file position carries no key information. It is the
     * fourth of the five readings of S1 recorded on this class.</p>
     *
     * <p>Assumptions: BOTH shapes insert, and the symmetry is itself the finding. A summary record is
     * self-contained -- it references no other row and no foreign key constrains it -- so neither
     * shape needs a companion loaded first, which is not true of the detail pair, whose rows are
     * unreachable without their parent. A reader who knew only the detail asymmetry would expect one
     * of these two shapes to need setup, and it does not.</p>
     *
     * <p>Assumptions: the four accounts are read back by a bounded range rather than one at a time,
     * because the claim is that all four landed and a per-account assertion would pass while another
     * silently did not. The bound is this case's own offset window, which is what keeps the query from
     * seeing another case's rows.</p>
     *
     * @throws SQLException if the container refuses a connection or the range query fails, which is a
     *     setup fault rather than the property under test
     */
    @Test
    @DisplayName("both recorded unload shapes insert, whichever order their records arrive in")
    void bothRecordedUnloadShapesInsert() throws SQLException {
        byte[] sequential = bytes(UNLOAD_GSAM_FIXTURE);
        byte[] prefixed = bytes(UNLOAD_PREFIXED_FIXTURE);
        assertThat(sequential).hasSize(2 * SEGMENT_LENGTH);
        assertThat(prefixed).hasSize(2 * SEGMENT_LENGTH);

        List<Long> inserted = new ArrayList<>();
        for (int ordinal = 0; ordinal < 2; ordinal++) {
            Map<String, Object> ascending = decode(segment(sequential, ordinal));
            Map<String, Object> descending = decode(segment(prefixed, ordinal));
            // WHY : Assumptions: the two shapes are separated by two further offsets because the same
            //       two accounts appear in both files. Without the separation the second shape would
            //       collide with the first on the primary key, and the case would report a duplicate
            //       key where it means to report an insert.
            inserted.add(inTransaction(repository ->
                    repository.save(rehydrate(ascending, OFFSET_UNLOAD))).getAccountId());
            inserted.add(inTransaction(repository ->
                    repository.save(rehydrate(descending, OFFSET_UNLOAD + 2L))).getAccountId());
        }

        long windowStart = inserted.stream().mapToLong(Long::longValue).min().orElseThrow();
        long windowEnd = inserted.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(queryText(SELECT_ACCOUNT_IDS, windowStart, windowEnd))
                .as("four rows from two shapes, so neither shape needed a companion row first")
                .hasSize(4);
    }


    /**
     * The insert-if-absent statement writes a fresh account and leaves a taken one exactly as it stood.
     *
     * <p>Purpose: the extract loader tolerates a duplicate root -- the reference program counts it and reads
     * the next record at {@code cbl/PAUDBLOD.CBL} L256 to L258 -- and it now does so with one statement
     * rather than a probe followed by a save. Two properties of that statement need an engine: the row
     * count it reports, which is how the loader tells the two outcomes apart, and that a conflict leaves the
     * stored row UNTOUCHED rather than merging anything into it.
     *
     * <p>Assumptions: the untouched half is asserted on a column the second attempt carries a DIFFERENT
     * value in, so the assertion can fail. Re-offering an identical row would satisfy an equality assertion
     * whatever the statement did on conflict, which is the assertion a merge would slip past.
     *
     * <p>Assumptions: the conflict is asserted not to poison the transaction, by writing a further row
     * through the same context afterwards. That is the property the loader depends on to keep its whole run
     * in one unit of work: a refused insert that aborted the transaction would make the duplicate tolerance
     * useless, because the records after the duplicate could not then be written.
     */
    @Test
    @DisplayName("insert-if-absent writes a fresh account, reports zero for a taken one, and merges nothing")
    void insertIfAbsentWritesOnceAndLeavesATakenAccountAlone() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        PendingAuthSummary first = rehydrate(fields, OFFSET_INSERT_IF_ABSENT);
        long accountId = first.getAccountId();

        // WHY : Assumptions: each row count is bound to an int local before it is asserted. The transaction
        //       helper is generic, so asserting on its call directly leaves the assertion overload to be
        //       chosen by inference and the compiler reports it as ambiguous rather than choosing.
        int freshWrite = inTransaction(repository -> Integer.valueOf(repository.insertSummaryIfAbsent(first)));
        assertThat(freshWrite)
                .as("a fresh account is written and the statement says so")
                .isEqualTo(1);

        PendingAuthSummary second = rehydrate(fields, OFFSET_INSERT_IF_ABSENT, "Z",
                new BigDecimal("7777.77"));
        int takenWrite = inTransaction(
                repository -> Integer.valueOf(repository.insertSummaryIfAbsent(second)));
        assertThat(takenWrite)
                .as("a taken account writes no row, which is what the loader counts as already present")
                .isEqualTo(0);

        PendingAuthSummary stored = read(accountId).orElseThrow();
        assertThat(stored.getAuthStatus())
                .as("the conflict must merge nothing, so the first row's status stands")
                .isEqualTo(first.getAuthStatus())
                .isNotEqualTo("Z");
        assertThat(stored.getCreditLimit()).isEqualByComparingTo(first.getCreditLimit());

        int afterConflict = inTransaction(repository -> Integer.valueOf(
                repository.insertSummaryIfAbsent(rehydrate(fields, OFFSET_INSERT_IF_ABSENT + 1L))));
        assertThat(afterConflict)
                .as("the conflict left the connection usable, which one transaction per load depends on")
                .isEqualTo(1);
    }

    /**
     * The key-projected walk returns ascending identifiers, strictly above the position, capped by the
     * limit.
     *
     * <p>Purpose: the purge walks this table with a query that returns identifiers rather than summaries,
     * so that the summary it then modifies is loaded for the first time under its row lock. Three
     * properties of that query can only be answered by an engine: that the comparison EXCLUDES the
     * position handed in, that the ordering is ascending, and that the {@link Limit} is applied as a row
     * cap rather than ignored. All three are asserted here on three adjacent identifiers.</p>
     *
     * <p>Assumptions: the limit is asserted with a page that is FULL and then with a page that is short,
     * because the caller decides the walk is finished by receiving fewer rows than it asked for. A query
     * that ignored the limit would answer the first call with all three, which the caller would read as
     * a short page and treat as the end of the walk -- a defect that shows up as summaries silently never
     * visited, not as an error.</p>
     *
     * <p>Alternatives Considered: asserting the projection through the entity walk instead, on the
     * grounds that both are ordered the same way. Rejected because the projection is a hand-written
     * query rather than a derived one, so nothing about the derived method's behaviour tells us anything
     * about this one -- the ordering, the comparison and the parameter binding are all stated by hand
     * here and each is a place a hand-written query can be wrong.</p>
     *
     * @throws SQLException if the container refuses a connection or the maximum-identifier query fails,
     *     which is a setup fault rather than the property under test
     */
    @Test
    @DisplayName("the key-projected walk is ascending, strictly above the position, and honours the limit")
    void theKeyProjectedWalkIsAscendingStrictAndLimited() throws SQLException {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        long first = inTransaction(repository ->
                repository.save(rehydrate(fields, OFFSET_KEY_WALK))).getAccountId();
        long second = inTransaction(repository ->
                repository.save(rehydrate(fields, OFFSET_KEY_WALK + 1L))).getAccountId();
        long third = inTransaction(repository ->
                repository.save(rehydrate(fields, OFFSET_KEY_WALK + 2L))).getAccountId();
        assertThat(List.of(first, second, third))
                .as("the three fixtures must be adjacent and ordered for the comparison to be observable")
                .containsExactly(first, first + 1L, first + 2L);
        assertThat(queryText(SELECT_MAX_ACCOUNT_ID).getFirst())
                .as("this case asserts exact pages, so its own offset must be the highest in the class;"
                        + " a case added above OFFSET_KEY_WALK must move this one rather than leave it")
                .isEqualTo(String.valueOf(third));

        List<Long> fullPage = walk(first - 1L, 2);
        assertThat(fullPage)
                .as("ascending, capped at two, and beginning above the position rather than at it")
                .containsExactly(first, second);

        List<Long> shortPage = walk(second, 2);
        assertThat(shortPage)
                .as("the position is EXCLUDED, so the row it names is not returned a second time")
                .containsExactly(third);

        assertThat(walk(third, 2))
                .as("a walk past the last identifier is empty, which is how the caller ends the run")
                .isEmpty();
    }

    /**
     * Runs the key-projected walk once, above a position, capped at a row count.
     *
     * @param startAfterAccountId the identifier the walk seeks strictly above
     * @param window the row cap the caller applies
     * @return the identifiers returned, in the order the engine returned them
     */
    private static List<Long> walk(long startAfterAccountId, int window) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return new JpaRepositoryFactory(entityManager)
                    .getRepository(PendingAuthSummaryRepository.class)
                    .findAccountIdsAboveOrderByAccountIdAsc(
                            Long.valueOf(startAfterAccountId), Limit.of(window));
        } finally {
            entityManager.close();
        }
    }

    /**
     * Reads one recorded image as raw bytes.
     *
     * <p>Assumptions: the stream is read whole and nothing is decoded as text, because every image
     * this class loads carries packed nibbles, binary halfwords and, in one case, deliberate
     * line-terminator bytes inside its fields. A character-oriented read would normalise or replace
     * those before any assertion could see them.</p>
     *
     * @param name the resource name below the test class-path root
     * @return the resource's bytes, exactly as stored
     * @throws AssertionError if the resource is absent from the test class path, which is a broken
     *     test rather than a failing assertion
     * @throws UncheckedIOException if the resource cannot be read once opened
     */
    private static byte[] bytes(String name) {
        try (InputStream stream =
                PendingAuthSummaryRepositoryIT.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new AssertionError("fixture " + name + " is not on the test class path");
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("fixture " + name + " could not be read", failure);
        }
    }

    /**
     * Slices one record out of an image that holds several.
     *
     * <p>Assumptions: the stride is the declared record length and there is no separator between
     * records, so an ordinal times that length lands on a record boundary. A search for a newline
     * would find one inside a field of the terminator image and would slice in the wrong place.</p>
     *
     * @param image the whole recorded image
     * @param ordinal the zero-based record ordinal within it
     * @return exactly {@link #SEGMENT_LENGTH} bytes beginning at that ordinal's offset
     */
    private static byte[] segment(byte[] image, int ordinal) {
        int start = ordinal * SEGMENT_LENGTH;
        return Arrays.copyOfRange(image, start, start + SEGMENT_LENGTH);
    }

    /**
     * Decodes one record through the layout the shared kernel registers for this segment.
     *
     * <p>Assumptions: the registered descriptor is used rather than one built here, so that this class
     * cannot disagree with the kernel about an offset. The descriptor carries the whole account-status
     * array as ONE ten-byte text field, which is why {@link #slot(Map, int)} exists.</p>
     *
     * @param record exactly one record of the registered length
     * @return the decoded fields, keyed by copybook field name
     */
    private static Map<String, Object> decode(byte[] record) {
        return FixedWidthCodec.decodeRecord(record, CopybookLayout.layout(LAYOUT));
    }

    /**
     * Returns the account identifier a decoded record carries, moved into one case's own range.
     *
     * <p>Assumptions: the packed key decodes to an exact decimal rather than to a whole number,
     * because the field is {@code PIC S9(11) COMP-3}; taking its long value is exact because the
     * declared scale is zero.</p>
     *
     * @param fields the decoded record
     * @param accountOffset the offset that keeps one case's rows to itself
     * @return the recorded account identifier with the offset added
     */
    private static long accountOf(Map<String, Object> fields, long accountOffset) {
        return amount(fields, ACCOUNT_ID_FIELD).longValueExact() + accountOffset;
    }

    /**
     * Rebuilds the entity from a decoded record, faithfully, into one case's own account range.
     *
     * @param fields the decoded record
     * @param accountOffset the offset that keeps one case's rows to itself
     * @return the entity the load path would build from that record
     */
    private static PendingAuthSummary rehydrate(Map<String, Object> fields, long accountOffset) {
        return rehydrate(fields, accountOffset, text(fields, AUTH_STATUS_FIELD),
                amount(fields, CREDIT_LIMIT_FIELD));
    }

    /**
     * Rebuilds the entity from a decoded record with only the cash balance overridden.
     *
     * <p>Assumptions: a third override is introduced rather than widening the two-parameter form,
     * because that form's own charter states which two members are parameterised and why, and adding a
     * third to it would let every existing case stop asserting the recorded cash balance. This overload
     * is used by one case, whose whole subject is a cash balance the record does not carry.</p>
     *
     * @param fields the decoded record
     * @param accountOffset the offset that keeps one case's rows to itself
     * @param cashBalance the cash balance to store in place of the recorded one
     * @return the entity the load path would build, with that one member replaced
     */
    private static PendingAuthSummary withCashBalance(Map<String, Object> fields, long accountOffset,
            BigDecimal cashBalance) {
        return PendingAuthSummary.rehydrated(
                accountOf(fields, accountOffset),
                (Long) fields.get(CUSTOMER_ID_FIELD),
                text(fields, AUTH_STATUS_FIELD),
                slot(fields, 0), slot(fields, 1), slot(fields, 2), slot(fields, 3), slot(fields, 4),
                amount(fields, CREDIT_LIMIT_FIELD),
                amount(fields, CASH_LIMIT_FIELD),
                amount(fields, CREDIT_BALANCE_FIELD),
                cashBalance,
                amount(fields, APPROVED_COUNT_FIELD).shortValueExact(),
                amount(fields, DECLINED_COUNT_FIELD).shortValueExact(),
                amount(fields, APPROVED_AMOUNT_FIELD),
                amount(fields, DECLINED_AMOUNT_FIELD));
    }

    /**
     * Rebuilds the entity from a decoded record with the status character and credit limit overridden.
     *
     * <p>Assumptions: exactly these two members are parameterised and the other fourteen are taken
     * from the record. They are the only two any case needs to vary -- one to present a character no
     * enumeration would list, the other to present an amount wider than the column -- and widening the
     * override surface further would let a case silently stop asserting the recorded values.</p>
     *
     * <p>Assumptions: the entity's load-path factory is used rather than a native insert, because that
     * factory is what the migration's own load path calls. Its guards therefore apply here as they do
     * there, which is what makes an acceptance in this class evidence about the deployed path and not
     * merely about the column.</p>
     *
     * @param fields the decoded record
     * @param accountOffset the offset that keeps one case's rows to itself
     * @param authStatus the one-character status to store in place of the recorded one
     * @param creditLimit the credit limit to store in place of the recorded one
     * @return the entity the load path would build, with those two members replaced
     */
    private static PendingAuthSummary rehydrate(Map<String, Object> fields, long accountOffset,
            String authStatus, BigDecimal creditLimit) {
        return PendingAuthSummary.rehydrated(
                accountOf(fields, accountOffset),
                (Long) fields.get(CUSTOMER_ID_FIELD),
                authStatus,
                slot(fields, 0), slot(fields, 1), slot(fields, 2), slot(fields, 3), slot(fields, 4),
                creditLimit,
                amount(fields, CASH_LIMIT_FIELD),
                amount(fields, CREDIT_BALANCE_FIELD),
                amount(fields, CASH_BALANCE_FIELD),
                amount(fields, APPROVED_COUNT_FIELD).shortValueExact(),
                amount(fields, DECLINED_COUNT_FIELD).shortValueExact(),
                amount(fields, APPROVED_AMOUNT_FIELD),
                amount(fields, DECLINED_AMOUNT_FIELD));
    }

    /**
     * Returns one two-byte account-status slot out of the ten-byte interval that holds all five.
     *
     * <p>Assumptions: the slots are adjacent and equal width, so slot {@code n} begins at
     * {@code n} times two within the decoded interval -- record offsets 16, 18, 20, 22 and 24. That
     * arithmetic is written once here rather than at five call sites, because five open-coded index
     * pairs are five chances to transpose a slot.</p>
     *
     * @param fields the decoded record
     * @param ordinal the zero-based slot ordinal, from 0 through 4
     * @return exactly two characters, the slot's own value
     */
    private static String slot(Map<String, Object> fields, int ordinal) {
        String interval = text(fields, ACCOUNT_STATUS_FIELD);
        int start = ordinal * SLOT_WIDTH;
        return interval.substring(start, start + SLOT_WIDTH);
    }

    /**
     * Returns one decoded field as text.
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's decoded characters
     */
    private static String text(Map<String, Object> fields, String name) {
        return (String) fields.get(name);
    }

    /**
     * Returns one decoded numeric field as an exact decimal.
     *
     * <p>Assumptions: every packed and binary field of this segment decodes to an exact decimal, so
     * this cast is total for them. It is deliberately not applied to the customer identifier, which is
     * unsigned display and decodes to a whole number instead.</p>
     *
     * @param fields the decoded record
     * @param name the copybook field name
     * @return the field's value as an exact decimal
     */
    private static BigDecimal amount(Map<String, Object> fields, String name) {
        return (BigDecimal) fields.get(name);
    }

    /**
     * An approved contribution advances exactly the three members the reference consumer adds to.
     *
     * <p>Purpose: this is {@code cbl/COPAUA0C.cbl} L814 to L817 -- one added to the approved count, the
     * approved amount accumulated, and the SAME amount added to the credit balance. The statement under
     * test performs that arithmetic in the database rather than reading, mutating and writing back, so
     * what has to be asserted against a real engine is that the three columns move and that the other
     * nine do not.
     *
     * Assumptions: this case and the five below exercise the repository's custom declarations
     * against a real engine, which is the only place they can be checked. An arithmetic statement
     * can name the wrong column, move the wrong number of them or match no row at all, and neither
     * a schema-shape assertion nor the inherited save and find would notice; a mocked repository
     * cannot cover them either, because the arithmetic is the statement's and a mock has none.
     *
     * <p>Assumptions: the row is re-read through a context that never wrote it. A modifying query
     * bypasses the persistence context, so an instance the writing context still holds would answer
     * from its identity map with the PRE-update values -- the one reading that cannot see the update
     * being the one taken beside it.
     *
     * <p>Assumptions: the return value is asserted as well as the columns, because it is how the caller
     * learns the account existed. A statement that matched nothing returns zero and raises nothing, so a
     * caller that ignored the count would treat a missing summary as a successful accumulation.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an approved contribution advances the count, the amount and the credit balance")
    void anApprovedContributionAdvancesExactlyThreeColumns() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        long accountId = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_CONTRIBUTION))).getAccountId();

        int updated = inTransaction(repository -> repository
                .reserveApprovedAuthorization(accountId, new BigDecimal("25.50"), CEILING));

        assertThat(updated).as("the account had a summary, so exactly one row moved").isEqualTo(1);
        PendingAuthSummary reread = read(accountId).orElseThrow();
        assertThat(reread.getApprovedAuthCount())
                .as("L814 adds one")
                .isEqualTo((short) 43);
        assertThat(reread.getApprovedAuthAmount())
                .as("L815 accumulates the approved amount")
                .isEqualByComparingTo("4225.50");
        assertThat(reread.getCreditBalance())
                .as("L817 adds the SAME amount to the credit balance, which starts negative here")
                .isEqualByComparingTo("-74.50");
        assertThat(reread.getDeclinedAuthCount())
                .as("an approval touches neither declined member")
                .isEqualTo((short) 7);
        assertThat(reread.getDeclinedAuthAmount()).isEqualByComparingTo("700.00");
        assertThat(reread.getCashBalance())
                .as("L818 assigns zero, which this fixture's already-zero column cannot distinguish"
                        + " from an omission -- the seeded case below is the one that can")
                .isEqualByComparingTo("0.00");
    }

    /**
     * A declined contribution advances two members and deliberately leaves the credit balance alone.
     *
     * <p>Purpose: this is the ELSE arm at {@code cbl/COPAUA0C.cbl} L820 to L821, which adds to the
     * declined count and the declined amount and does NOT touch the credit balance. The asymmetry with
     * the approved arm is the whole point of asserting it: a declined authorization reserves nothing, so
     * a statement that moved the balance here would over-reserve an account on every refusal.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a declined contribution advances two columns and never the credit balance")
    void aDeclinedContributionLeavesTheCreditBalanceAlone() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        long accountId = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_DECLINE))).getAccountId();

        int updated = inTransaction(repository -> repository
                .addDeclinedAuthorization(accountId, new BigDecimal("30.00"), CEILING));

        assertThat(updated).isEqualTo(1);
        PendingAuthSummary reread = read(accountId).orElseThrow();
        assertThat(reread.getDeclinedAuthCount()).isEqualTo((short) 8);
        assertThat(reread.getDeclinedAuthAmount()).isEqualByComparingTo("730.00");
        assertThat(reread.getCreditBalance())
                .as("no reference statement adds a declined amount to the balance")
                .isEqualByComparingTo("-100.00");
        assertThat(reread.getApprovedAuthCount()).isEqualTo((short) 42);
        assertThat(reread.getApprovedAuthAmount()).isEqualByComparingTo("4200.00");
    }

    /**
     * An approved contribution assigns zero to a cash balance a seeded row carried, and a decline does
     * not.
     *
     * <p>Purpose: this pins {@code MOVE 0 TO PA-CASH-BALANCE} at {@code cbl/COPAUA0C.cbl} L818, which is
     * the fourth statement of that paragraph's approved arm and the only one of the four that ASSIGNS
     * rather than accumulates. The canonical fixture carries a zero cash balance, so the sibling case
     * above cannot tell an assignment from an omission -- both leave the column reading zero. This case
     * seeds a NON-ZERO cash balance so the two outcomes differ, which is the only arrangement in which
     * the statement is observable at all.
     *
     * <p>Assumptions: the seeded value is what makes the case meaningful rather than incidental. A
     * summary reaches this state through the extract load, whose factory accepts and stores whatever
     * cash balance the stored segment carried; a summary the online path created starts at zero and
     * could never distinguish the two behaviours. So the seeded row is the deployed path being asserted,
     * not a contrivance.
     *
     * <p>Assumptions: the declined arm is asserted in the same case, because the reference program's
     * declined arm at L819 to L821 carries no balance statement of any kind. Asserting only the approved
     * arm would leave a statement that zeroed the column on BOTH arms indistinguishable from the
     * reference, and that implementation would clear a seeded value on a refusal the reference leaves
     * alone.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an approved contribution assigns zero to the cash balance and a decline leaves it")
    void anApprovedContributionAssignsZeroToTheCashBalance() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        BigDecimal seededCashBalance = new BigDecimal("321.45");

        long approvedAccount = inTransaction(repository -> repository.save(
                withCashBalance(fields, OFFSET_CASH_BALANCE, seededCashBalance))).getAccountId();
        long declinedAccount = inTransaction(repository -> repository.save(
                withCashBalance(fields, OFFSET_CASH_BALANCE + 1L, seededCashBalance))).getAccountId();

        assertThat(read(approvedAccount).orElseThrow().getCashBalance())
                .as("the seeded value is stored, so the case starts from a state the load path produces")
                .isEqualByComparingTo(seededCashBalance);

        inTransaction(repository -> repository
                .reserveApprovedAuthorization(approvedAccount, new BigDecimal("25.50"), CEILING));
        inTransaction(repository -> repository
                .addDeclinedAuthorization(declinedAccount, new BigDecimal("25.50"), CEILING));

        assertThat(read(approvedAccount).orElseThrow().getCashBalance())
                .as("L818 assigns zero on the approved arm")
                .isEqualByComparingTo("0.00");
        assertThat(read(declinedAccount).orElseThrow().getCashBalance())
                .as("the declined arm at L820 to L821 carries no balance statement, so it is untouched")
                .isEqualByComparingTo(seededCashBalance);
    }

    /**
     * A reservation the account's own limit no longer admits changes nothing and reports that it changed none.
     *
     * <p>Purpose: this is the guard that makes an approval safe under concurrency, exercised directly against
     * the engine. The statement carries the same credit check the decision service makes -- available amount
     * is the credit limit minus the credit balance, and an amount is admitted when it is not GREATER than that
     * difference -- and the engine re-evaluates it against the row as it stands, so an amount that no longer
     * fits is refused rather than applied.</p>
     *
     * <p>Refactoring Rationale: this case exists because the statement carried no guard. A queue grouped on
     * card number delivers two requests for two DIFFERENT cards of one account concurrently, so both read the
     * same headroom, both approve, and both contributions then landed -- leaving a credit balance above the
     * credit limit that no reference program can produce, because the reference decides one message at a
     * time. Nothing in the row recorded that a limit had been breached.</p>
     *
     * <p>Assumptions: the SEQUENCE is what proves the property, not a single refused statement. The first
     * reservation is admitted and consumes the headroom; the second, for an amount that fitted before the
     * first ran, is then refused. A case that only refused an obviously oversized amount would pass against a
     * statement that compared the amount against the LIMIT rather than against the remaining headroom.</p>
     *
     * <p>Assumptions: every column is re-read after the refusal, not just the count. A statement that
     * advanced the counters and skipped only the balance would also report one row, and a statement that
     * reported zero while having written something would be worse than one that wrote nothing.</p>
     *
     * <p>Assumptions: the EXACT boundary is asserted as admitted, because the reference declines only when the
     * requested amount is STRICTLY GREATER than the available amount at L668 and L676 of
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}. A strict predicate in the statement would
     * refuse a request for exactly the remaining headroom, which the reference approves.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a reservation beyond the remaining headroom moves no row and reports zero")
    void aReservationBeyondTheRemainingHeadroomReportsZero() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        long accountId = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_GUARDED_RESERVATION)))
                .getAccountId();

        PendingAuthSummary seeded = read(accountId).orElseThrow();
        BigDecimal headroom = seeded.getCreditLimit().subtract(seeded.getCreditBalance());
        BigDecimal firstReservation = headroom.subtract(new BigDecimal("10.00"));

        // WHY : Assumptions: every row count is bound to a typed local before it is asserted. AssertJ
        //       declares both an IntPredicate overload and a generic Predicate overload of assertThat, so a
        //       lambda whose body returns a boxed integer makes the call ambiguous at compile time. The
        //       local resolves the type before the assertion sees it.
        int firstAdmitted = inTransaction(repository -> Integer.valueOf(
                repository.reserveApprovedAuthorization(accountId, firstReservation, CEILING)));
        assertThat(firstAdmitted)
                .as("the limit admitted the first amount, so exactly one row moved")
                .isEqualTo(1);

        PendingAuthSummary afterFirst = read(accountId).orElseThrow();
        assertThat(afterFirst.getCreditLimit().subtract(afterFirst.getCreditBalance()))
                .as("the first reservation consumed all but ten of the headroom")
                .isEqualByComparingTo("10.00");

        // WHY : Assumptions: the second amount is the one the FIRST reservation consumed -- it fitted before
        //       that statement ran and does not fit now. That is exactly the shape of the concurrent pair
        //       this guard exists for: two decisions measured against one headroom.
        int refused = inTransaction(repository -> Integer.valueOf(
                repository.reserveApprovedAuthorization(accountId, firstReservation, CEILING)));
        assertThat(refused)
                .as("the headroom is gone, so the reservation must be refused")
                .isZero();

        PendingAuthSummary afterRefusal = read(accountId).orElseThrow();
        assertThat(afterRefusal.getApprovedAuthCount())
                .as("a refused reservation advances no counter")
                .isEqualTo(afterFirst.getApprovedAuthCount());
        assertThat(afterRefusal.getApprovedAuthAmount())
                .as("a refused reservation accumulates no amount")
                .isEqualByComparingTo(afterFirst.getApprovedAuthAmount());
        assertThat(afterRefusal.getCreditBalance())
                .as("a refused reservation moves no balance, so no limit is breached")
                .isEqualByComparingTo(afterFirst.getCreditBalance());

        // WHY : Assumptions: the remaining ten is then reserved EXACTLY, which proves the predicate is
        //       inclusive. A strict comparison would refuse this and would decline a request the reference
        //       approves at precisely the boundary both are written around.
        int boundaryAdmitted = inTransaction(repository -> Integer.valueOf(
                repository.reserveApprovedAuthorization(accountId, new BigDecimal("10.00"), CEILING)));
        assertThat(boundaryAdmitted)
                .as("exactly the remaining headroom is admitted, matching the reference's strict decline")
                .isEqualTo(1);
        PendingAuthSummary exhausted = read(accountId).orElseThrow();
        assertThat(exhausted.getCreditBalance())
                .as("the balance now equals the limit and has not passed it")
                .isEqualByComparingTo(exhausted.getCreditLimit());
        int oneCentOver = inTransaction(repository -> Integer.valueOf(
                repository.reserveApprovedAuthorization(accountId, new BigDecimal("0.01"), CEILING)));
        assertThat(oneCentOver)
                .as("one cent beyond an exhausted limit is refused")
                .isZero();
    }

    /**
     * A contribution naming an account with no summary changes nothing and reports that it changed none.
     *
     * <p>Assumptions: this is asserted for BOTH arms in one case, because the two statements share the
     * predicate that decides it and a case covering one would leave the other's predicate unproven.
     *
     * <p>Assumptions: the table is checked to be unchanged as well as the count being zero. A statement
     * that inserted a row for an unknown account would also report one row affected, so the count alone
     * cannot distinguish "no such account" from "created one".
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a contribution to an account with no summary moves no row and reports zero")
    void aContributionToAnAbsentAccountReportsZero() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        long absent = accountOf(fields, OFFSET_ABSENT_CONTRIBUTION);

        int approved = inTransaction(repository -> repository
                .reserveApprovedAuthorization(absent, new BigDecimal("10.00"), CEILING));
        int declined = inTransaction(repository -> repository
                .addDeclinedAuthorization(absent, new BigDecimal("10.00"), CEILING));

        assertThat(approved).isZero();
        assertThat(declined).isZero();
        assertThat(read(absent))
                .as("an accumulation is not an insert, so no summary may appear")
                .isEmpty();
    }

    /**
     * Two interleaved transactions each running the consumer's own refresh-then-increment sequence both
     * land, and neither displaces the other.
     *
     * <p><b>Purpose.</b> This is the regression case for the lost update the consumer used to commit. It
     * runs the EXACT production sequence twice over one row -- read the summary, refresh the two limits
     * from the account master, then add one authorization's contribution -- with both transactions
     * holding their own read of the row BEFORE either writes. Under the previous arrangement the refresh
     * was a field assignment on the loaded instance followed by the inherited {@code save}, so the
     * second transaction's flush -- forced by its own additive query, because the provider flushes
     * before a bulk operation -- rewrote every column from its load-time snapshot and discarded the
     * first transaction's contribution before adding its own. The expected count below would then have
     * been one greater than the base rather than two, which is what makes this case discriminating
     * rather than merely exercising.
     *
     * <p>Assumptions: the reads are taken FIRST and deliberately kept, because a snapshot older than the
     * other party's commit is the whole precondition. A case that read the row after the first commit
     * would pass against the defective arrangement too, since the snapshot it flushed would already
     * carry the other contribution.
     *
     * <p>Assumptions: the interleaving is expressed as two persistence contexts open at once and
     * committed in a fixed order, NOT as two threads. Alternatives Considered: two threads with a
     * latch, which is the shape a reader expects for a concurrency case. Rejected because the property
     * under test is about which snapshot a write carries rather than about timing, and two threads make
     * the ordering a matter of scheduling -- so the case could pass on a run that never interleaved and
     * report nothing. Two contexts committed in a stated order reproduce the same snapshot relationship
     * on every run, and the engine's own row serialisation is not being tested here.
     *
     * <p>Assumptions: the second transaction's own loaded instance is asserted to be STALE, which is the
     * mechanism rather than a side observation. It shows that a whole-row write from that instance would
     * have carried the pre-update counters, so the case names the exact defect it guards against instead
     * of only its symptom.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("two interleaved refresh-then-increment sequences both land on the same summary")
    void twoInterleavedContributionsBothLand() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        long accountId = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_INTERLEAVED))).getAccountId();
        short baseCount = read(accountId).orElseThrow().getApprovedAuthCount();

        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        PendingAuthSummary secondSnapshot;
        try {
            PendingAuthSummaryRepository firstRepository =
                    new JpaRepositoryFactory(first).getRepository(PendingAuthSummaryRepository.class);
            PendingAuthSummaryRepository secondRepository =
                    new JpaRepositoryFactory(second).getRepository(PendingAuthSummaryRepository.class);

            first.getTransaction().begin();
            second.getTransaction().begin();
            PendingAuthSummary firstSnapshot =
                    firstRepository.findByAccountId(accountId).orElseThrow();
            secondSnapshot = secondRepository.findByAccountId(accountId).orElseThrow();
            assertThat(firstSnapshot.getApprovedAuthCount())
                    .as("both transactions start from the same stored count")
                    .isEqualTo(baseCount)
                    .isEqualTo(secondSnapshot.getApprovedAuthCount());

            assertThat(firstRepository.refreshStoredLimits(accountId,
                    new BigDecimal("7000.00"), new BigDecimal("700.00")))
                    .as("the first transaction's limit refresh reaches the row")
                    .isEqualTo(1);
            assertThat(firstRepository.reserveApprovedAuthorization(accountId, new BigDecimal("10.00"),
                    CEILING))
                    .as("the first transaction's contribution reaches the row")
                    .isEqualTo(1);
            first.flush();
            first.getTransaction().commit();

            assertThat(secondSnapshot.getApprovedAuthCount())
                    .as("the second transaction's instance is now STALE: a whole-row write from it"
                            + " would carry the pre-update counters, which is the defect this case"
                            + " guards against")
                    .isEqualTo(baseCount);

            assertThat(secondRepository.refreshStoredLimits(accountId,
                    new BigDecimal("8000.00"), new BigDecimal("800.00")))
                    .as("the second transaction's limit refresh reaches the row")
                    .isEqualTo(1);
            assertThat(secondRepository.reserveApprovedAuthorization(accountId, new BigDecimal("20.00"),
                    CEILING))
                    .as("the second transaction's contribution reaches the row")
                    .isEqualTo(1);
            second.flush();
            second.getTransaction().commit();
        } finally {
            // WHY : Assumptions: both contexts are closed whatever happened, and an active transaction
            //       is rolled back first. A failed assertion between the two commits would otherwise
            //       leave a transaction holding the row's lock for the rest of the class, and every
            //       later case addressing any row would then fail on a pool timeout rather than on its
            //       own subject.
            rollbackAndClose(second);
            rollbackAndClose(first);
        }

        PendingAuthSummary reread = read(accountId).orElseThrow();
        assertThat(reread.getApprovedAuthCount())
                .as("BOTH contributions land: the count advances by two, not by one")
                .isEqualTo((short) (baseCount + 2));
        assertThat(reread.getApprovedAuthAmount())
                .as("both amounts accumulate, so neither transaction's total was displaced")
                .isEqualByComparingTo("4230.00");
        assertThat(reread.getCreditLimit())
                .as("the limits are ASSIGNED, so the last writer wins and nothing is lost by that")
                .isEqualByComparingTo("8000.00");
        assertThat(reread.getCashLimit()).isEqualByComparingTo("800.00");
    }

    /**
     * Rolls back an entity manager's transaction if one is still active, then closes it.
     *
     * @param entityManager the context to release; must not be {@code null}
     */
    private static void rollbackAndClose(EntityManager entityManager) {
        try {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
        } finally {
            entityManager.close();
        }
    }

    /**
     * The reversal subtracts all four accumulators and leaves the credit balance where it stood.
     *
     * <p>Purpose: this is {@code cbl/CBPAUP0C.cbl} L287 to L292, which subtracts from the same four
     * members the consumer adds to -- and from no fifth. That the credit balance is NOT restored is the
     * reference's behaviour, not an omission here, and it is asserted so that a later reader who notices
     * the asymmetry with the approved arm finds it pinned rather than plausible.
     *
     * <p>Assumptions: both pairs are supplied non-zero in one call, because the statement subtracts both
     * pairs unconditionally and a caller supplies zero for the arm that does not apply; a case that
     * exercised one pair would leave the other's expression unproven.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the reversal subtracts all four accumulators and not the credit balance")
    void theReversalSubtractsAllFourAccumulators() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        long accountId = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_REVERSAL))).getAccountId();

        int updated = inTransaction(repository -> repository.reverseExpiredAuthorizations(accountId,
                2, new BigDecimal("200.00"), 1, new BigDecimal("100.00"), CEILING, FLOOR));

        assertThat(updated).isEqualTo(1);
        PendingAuthSummary reread = read(accountId).orElseThrow();
        assertThat(reread.getApprovedAuthCount()).isEqualTo((short) 40);
        assertThat(reread.getApprovedAuthAmount()).isEqualByComparingTo("4000.00");
        assertThat(reread.getDeclinedAuthCount()).isEqualTo((short) 6);
        assertThat(reread.getDeclinedAuthAmount()).isEqualByComparingTo("600.00");
        assertThat(reread.getCreditBalance())
                .as("L287 to L292 subtract from four members and the balance is not one of them")
                .isEqualByComparingTo("-100.00");
    }

    /**
     * An accumulation that would pass the column's precision saturates at the bound instead of failing.
     *
     * <p>⚠️ Purpose: this is the engine-level half of the money-domain reduction, and it can only be
     * asserted here. The two running totals are {@code NUMERIC(11,2)}, derived under transformation rule T1
     * from {@code PIC S9(09)V99 COMP-3} at {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} L29 and
     * L30, while the amounts accumulated into them come from a column one decimal order wider. Unbounded,
     * the additive statement raised {@code 22003} -- the same refusal
     * {@link #aTwelveDigitAmountIsRefusedByTheNarrowerSummaryPrecision()} asserts on the insert path -- and
     * because that refusal aborts the whole transaction, the message's decision was rolled back and the
     * requester received NO answer. The queue redelivered the request four more times and dead-lettered it.
     * </p>
     *
     * <p>Assumptions: the seeded totals are one accumulation SHORT of the bound rather than already at it,
     * so the addend crossing the boundary is what the case exercises. A row seeded at the bound would be
     * saturated before the statement ran and would pass against an implementation that never accumulated at
     * all.</p>
     *
     * <p>Assumptions: the CREDIT BALANCE is asserted to be the exact un-saturated sum. The approved
     * statement bounds three members and only two of them are near the bound here, so asserting the balance
     * separately is what shows the {@code case} expressions are per-column rather than a blanket clamp on
     * everything the statement writes.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an accumulation past the column's precision saturates at the bound and does not fail")
    void anAccumulationPastTheColumnPrecisionSaturates() {
        long accountId = inTransaction(repository -> repository.save(nearTheBound(OFFSET_SATURATION)))
                .getAccountId();

        int approved = inTransaction(repository -> repository
                .reserveApprovedAuthorization(accountId, new BigDecimal("5000.00"), CEILING));
        int declined = inTransaction(repository -> repository
                .addDeclinedAuthorization(accountId, new BigDecimal("5000.00"), CEILING));

        assertThat(approved)
                .as("the account's own headroom admits the amount, so the reservation is applied")
                .isEqualTo(1);
        assertThat(declined).isEqualTo(1);
        PendingAuthSummary reread = read(accountId).orElseThrow();
        assertThat(reread.getApprovedAuthAmount())
                .as("999999000.00 plus 5000.00 exceeds the column, so the bound is stored")
                .isEqualByComparingTo(CEILING);
        assertThat(reread.getDeclinedAuthAmount()).isEqualByComparingTo(CEILING);
        assertThat(reread.getCreditBalance())
                .as("this member was nowhere near the bound, so it carries the exact sum")
                .isEqualByComparingTo("5000.00");
        assertThat(reread.getApprovedAuthCount())
                .as("the counters advance whether the totals saturated or not")
                .isEqualTo((short) 1);
        assertThat(reread.getDeclinedAuthCount()).isEqualTo((short) 1);
    }

    /**
     * A reversal that would pass the column's negative precision saturates at the negative bound.
     *
     * <p>⚠️ Purpose: the expiry sweep subtracts amounts read from {@code pending_auth_detail}, whose money
     * columns are {@code PIC S9(10)V99} at {@code cpy/CIPAUDTY.cpy} L34 and L35 -- one decimal order wider
     * than the totals they are subtracted from. Unbounded, one such authorization drove the total past the
     * column's NEGATIVE bound and raised {@code 22003}, which abended the whole run: windows already
     * committed stayed committed, so the table was left partly purged, and no later run could complete while
     * such a row existed. The sweep could never finish.</p>
     *
     * <p>Assumptions: the reversal is allowed to reach a NEGATIVE total rather than being floored at zero,
     * which is divergence D-G recorded on {@code PurgeJob}. The bound asserted is therefore the column's own
     * negative extreme and not zero -- flooring at zero here would refuse a reversal the reference program
     * performs without complaint.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a reversal past the column's negative precision saturates at the negative bound")
    void aReversalPastTheColumnPrecisionSaturates() {
        long accountId = inTransaction(
                repository -> repository.save(nearTheBound(OFFSET_SATURATION_REVERSAL))).getAccountId();
        BigDecimal widestDetailAmount = new BigDecimal("9999999999.99");

        int updated = inTransaction(repository -> repository.reverseExpiredAuthorizations(accountId,
                1, widestDetailAmount, 1, widestDetailAmount, CEILING, FLOOR));

        assertThat(updated).isEqualTo(1);
        PendingAuthSummary reread = read(accountId).orElseThrow();
        assertThat(reread.getApprovedAuthAmount())
                .as("999999000.00 less 9999999999.99 is below the column, so the negative bound is stored")
                .isEqualByComparingTo(FLOOR);
        assertThat(reread.getDeclinedAuthAmount()).isEqualByComparingTo(FLOOR);
        assertThat(reread.getApprovedAuthCount())
                .as("the counters are signed four-digit fields, so minus one is a representable state")
                .isEqualTo((short) -1);
        assertThat(reread.getDeclinedAuthCount()).isEqualTo((short) -1);
        assertThat(reread.getCreditBalance())
                .as("the reversal releases no balance, saturated or not")
                .isEqualByComparingTo("0.00");
    }

    /**
     * Builds a summary whose two running totals sit one accumulation short of the column's bound.
     *
     * <p>Assumptions: the entity's rehydration factory is used rather than the record-decoding helper
     * beside it, because the committed fixture's amounts are ordinary four-figure values and these two
     * cases need values the fixture does not carry. The optional status members are left absent, which the
     * factory admits and which keeps the row's subject to the money columns alone.</p>
     *
     * <p>Assumptions: the credit limit is seeded AT the bound so the approved statement's own headroom guard
     * admits the addend. Without that the reservation would be refused and the case would pass while
     * asserting nothing about saturation.</p>
     *
     * @param accountOffset the offset that keeps one case's row to itself
     * @return the entity to store, with both totals at {@code 999999000.00} and both counters at zero
     */
    private static PendingAuthSummary nearTheBound(long accountOffset) {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        BigDecimal justShort = new BigDecimal("999999000.00");
        BigDecimal zero = new BigDecimal("0.00");
        return PendingAuthSummary.rehydrated(
                accountOf(fields, accountOffset),
                (Long) fields.get(CUSTOMER_ID_FIELD),
                null, null, null, null, null, null,
                CEILING, CEILING, zero, zero,
                Short.valueOf((short) 0), Short.valueOf((short) 0), justShort, justShort);
    }

    /**
     * The presence query answers only the accounts that have a summary, and never invents one.
     *
     * <p>Purpose: this is the query that replaced one probe per record in the extract load, so what it
     * has to answer correctly is the SUBSET: a present account included, an absent one excluded, and no
     * value returned that was not asked about. An implementation returning every identifier it was
     * handed would make the load accept a child whose parent is missing, which is the refusal that query
     * exists to drive.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the presence query returns exactly the accounts that have a summary")
    void thePresenceQueryReturnsOnlyPresentAccounts() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        long present = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_PRESENCE))).getAccountId();
        long absent = accountOf(fields, OFFSET_PRESENCE_ABSENT);

        List<Long> found = inTransaction(repository -> repository
                .findExistingAccountIds(List.of(present, absent)));

        assertThat(found).containsExactly(present);
        // WHY : Assumptions: the second reading is assigned to a TYPED local before being asserted. The
        //       unit-of-work helper is generic and the assertion entry point is overloaded, so handing
        //       the call straight to it leaves the lambda with no target type and the overload
        //       ambiguous; naming the type is what resolves both.
        List<Long> nothingFound = inTransaction(
                repository -> repository.findExistingAccountIds(List.of(absent)));
        assertThat(nothingFound)
                .as("asking only about an absent account answers nothing rather than raising")
                .isEmpty();
    }

    /**
     * The checkpoint walk is strictly past its anchor, ascending, and bounded by the limit it is given.
     *
     * <p>Purpose: this is the purge's resumption read, and all three properties are load-bearing. A
     * non-strict bound would re-read the anchor account on every window and the purge would never
     * advance past it; a descending or unordered walk would make the anchor meaningless, since the next
     * window resumes from the highest identifier the last one saw; and an unbounded read would defeat
     * the window cap the purge exists to respect.
     *
     * <p>Assumptions: the three accounts are given identifiers ABOVE every other case's offset, so the
     * walk from an anchor just below the first can see only this case's rows. This class shares one
     * table across its cases by design -- each owns an offset rather than cleaning up -- so an ordered
     * read is the one shape that has to be given the top of that space.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the checkpoint walk is strict, ascending and bounded")
    void theCheckpointWalkIsStrictAscendingAndBounded() {
        Map<String, Object> fields = decode(bytes(CANONICAL_FIXTURE));
        long first = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_WALK))).getAccountId();
        long second = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_WALK + 1))).getAccountId();
        long third = inTransaction(
                repository -> repository.save(rehydrate(fields, OFFSET_WALK + 2))).getAccountId();

        List<Long> fromBelowTheFirst = walkFrom(first - 1, 3);
        // WHY : Assumptions: this read is capped at TWO rather than three. Its own three rows can answer
        //       at most two above the first, and asking for three would reach into the rows of the
        //       key-projected walk above this offset -- making the case depend on whether that one has
        //       run. The property asserted is unchanged: anchoring on a row excludes that row.
        List<Long> fromTheFirst = walkFrom(first, 2);
        List<Long> boundedToOne = walkFrom(first - 1, 1);

        assertThat(fromBelowTheFirst)
                .as("ascending, and the anchor being below the first admits all three")
                .containsExactly(first, second, third);
        assertThat(fromTheFirst)
                .as("the bound is STRICT, so anchoring on a row excludes that row")
                .containsExactly(second, third);
        assertThat(boundedToOne)
                .as("the limit bounds the read rather than being advisory")
                .containsExactly(first);
    }

    /**
     * Walks the summaries past one anchor account, ascending, taking at most the rows asked for.
     *
     * @param anchorExclusive the account the walk resumes past, which the walk must not return
     * @param atMost how many rows the walk may take
     * @return the accounts the walk returned, in the order it returned them
     */
    private static List<Long> walkFrom(long anchorExclusive, int atMost) {
        return inTransaction(repository -> repository
                .findByAccountIdGreaterThanOrderByAccountIdAsc(anchorExclusive, Limit.of(atMost))
                .stream().map(PendingAuthSummary::getAccountId).toList());
    }

    /**
     * Runs one unit of work against the repository in a transaction of its own.
     *
     * <p>Assumptions: a persistence context is opened and closed per call, and the work is flushed
     * before the commit. Flushing inside the call is what makes a refused write surface as this call's
     * failure rather than at some later, unrelated boundary; opening a context per call is what gives
     * each refusal a transaction nothing else shares, which is the whole mitigation for this engine's
     * aborted-transaction behaviour.</p>
     *
     * <p>Assumptions: the repository proxy is built per call from that context. Building it once and
     * reusing it would bind it to a single context and defeat the per-call isolation above.</p>
     *
     * @param <R> the type the unit of work returns
     * @param work the unit of work, handed the repository under test
     * @return whatever the unit of work returned, after the commit
     * @throws RuntimeException if the work or the commit fails, rethrown after the rollback so that a
     *     refusal assertion can read the provider's own exception chain
     */
    private static <R> R inTransaction(Function<PendingAuthSummaryRepository, R> work) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            PendingAuthSummaryRepository repository = new JpaRepositoryFactory(entityManager)
                    .getRepository(PendingAuthSummaryRepository.class);
            entityManager.getTransaction().begin();
            try {
                R result = work.apply(repository);
                entityManager.flush();
                entityManager.getTransaction().commit();
                return result;
            } catch (RuntimeException failure) {
                if (entityManager.getTransaction().isActive()) {
                    entityManager.getTransaction().rollback();
                }
                throw failure;
            }
        } finally {
            entityManager.close();
        }
    }

    /**
     * Reads one summary by its account through a persistence context that never wrote it.
     *
     * <p>Assumptions: a fresh context is the point of this helper. A context that had written the row
     * could answer the lookup from its own identity map, so the result would be the object handed in
     * rather than the row the engine stored -- and every case that calls this is asserting the row.</p>
     *
     * @param accountId the single key value that is the whole key
     * @return the stored summary, or an empty result when the account has none
     */
    private static Optional<PendingAuthSummary> read(long accountId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return new JpaRepositoryFactory(entityManager)
                    .getRepository(PendingAuthSummaryRepository.class)
                    .findById(accountId);
        } finally {
            entityManager.close();
        }
    }

    /**
     * Runs one native statement and lets any failure reach the caller unchanged.
     *
     * <p>Assumptions: the failure is deliberately NOT wrapped. One case asserts the identity of a
     * refusal by unwrapping the provider's exception, and a wrapper of this class's own would add a
     * frame that the unwrapping would then have to know about.</p>
     *
     * @param sql the statement to run, naming its table unqualified
     * @param parameters the positional parameters, in the statement's own order
     * @throws SQLException if the connection cannot be opened or the statement is refused
     */
    private static void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    /**
     * Runs one query and returns its first column as text, row by row.
     *
     * <p>Assumptions: every query in this class selects exactly one column and every one of those is
     * either already text or is cast to text in the statement, so a single text accessor covers all of
     * them and no per-query type handling is needed.</p>
     *
     * @param sql the query to run
     * @param parameters the positional parameters, in the query's own order
     * @return the first column of every row, in the order the query returned them
     * @throws SQLException if the connection cannot be opened or the query is refused
     */
    private static List<String> queryText(String sql, Object... parameters) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    /**
     * Returns every column of one summary row, keyed by column name in declaration order.
     *
     * <p>Assumptions: the column list is taken from the result-set metadata rather than written out in
     * the query, because the case that uses this asserts which columns EXIST -- and a query naming
     * them would assert only that the names it named came back.</p>
     *
     * <p>Assumptions: each value is rendered as text and a null becomes an empty string rather than
     * the four letters of a null literal. The one assertion over these values looks for a substring of
     * the dropped filler, and a literal rendering would introduce text no column actually holds.</p>
     *
     * @param accountId the single key value of the row to read
     * @return every column of that row, name to text rendering, in declaration order
     * @throws SQLException if the connection cannot be opened, the query is refused, or the row is
     *     absent, which would mean the case's own insert did not land
     */
    private static Map<String, String> wholeRow(long accountId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_WHOLE_ROW)) {
            statement.setLong(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("no pending_auth_summary row for the account under test");
                }
                ResultSetMetaData metaData = rows.getMetaData();
                Map<String, String> row = new LinkedHashMap<>();
                for (int column = 1; column <= metaData.getColumnCount(); column++) {
                    String value = rows.getString(column);
                    row.put(metaData.getColumnName(column), value == null ? "" : value);
                }
                return row;
            }
        }
    }

    /**
     * Binds positional parameters onto a prepared statement.
     *
     * @param statement the statement to bind onto
     * @param parameters the positional parameters, in the statement's own order
     * @throws SQLException if the driver refuses a value at its position
     */
    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    /**
     * Returns the engine's own state code for a failure, from anywhere in its cause chain.
     *
     * <p>Assumptions: the driver's exception is nested inside the provider's and, for a write through
     * the repository, inside a translated data-access exception as well, so the chain has to be walked
     * rather than inspected one level down. The state code is preferred over the message text because
     * it is a stable identity, whereas message text varies with the engine's locale and release.</p>
     *
     * @param failure the exception a refused statement produced
     * @return the driver's state code
     * @throws AssertionError if no driver exception is present in the chain, which means the failure
     *     was not a refusal by the engine and the case would otherwise assert nothing
     */
    private static String sqlStateOf(Throwable failure) {
        return driverFailureIn(failure).getSQLState();
    }

    /**
     * Returns the name of the constraint the engine reported for a failure.
     *
     * @param failure the exception a refused statement produced
     * @return the constraint's declared name
     * @throws AssertionError if no driver exception is present in the chain, or if the engine attached
     *     no server message to it and there is therefore no constraint to name
     */
    private static String constraintOf(Throwable failure) {
        PSQLException driverFailure = driverFailureIn(failure);
        if (driverFailure.getServerErrorMessage() == null) {
            throw new AssertionError("the engine attached no server message to " + driverFailure);
        }
        return driverFailure.getServerErrorMessage().getConstraint();
    }

    /**
     * Finds the driver's own exception in a cause chain.
     *
     * @param failure the exception a refused statement produced
     * @return the first driver exception found, walking causes outward in
     * @throws AssertionError if the chain holds none, which means the engine refused nothing
     */
    private static PSQLException driverFailureIn(Throwable failure) {
        for (Throwable candidate = failure; candidate != null; candidate = candidate.getCause()) {
            if (candidate instanceof PSQLException driverFailure) {
                return driverFailure;
            }
        }
        throw new AssertionError("no driver exception in the chain of " + failure);
    }
}
