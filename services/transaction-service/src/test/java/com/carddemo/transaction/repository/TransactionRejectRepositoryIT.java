package com.carddemo.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.money.Money;
import com.carddemo.transaction.domain.TransactionReject;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins {@code ledger.transaction_rejects}, the posting reject stream of the LEDGER bounded context,
 * onto a real PostgreSQL engine.
 *
 * <p>This class owns what the TABLE does with a reason a producer has already decided: the 430-byte
 * reject contract expressed as four columns, the declared type, width and nullability of each of
 * them, the persisted reason register including the fifth reason 109, and the append-only property
 * that keeps two identical record images separately addressable. The package charter beside this file
 * owns the rulings this class obeys rather than restates -- the closed file set, the keyset-only
 * vocabulary, the injected determinism, the single-sourcing obligation, the report directories and
 * the permitted framing of a divergence -- and they are cited from here.
 *
 * <h2>What decides a reason is owned elsewhere, and deliberately not asserted here</h2>
 *
 * <p>Refactoring Rationale: this class asserts the PERSISTENCE contract of the owned reject stream and
 * deliberately decides NOTHING about which reason a record earns -- not the short-circuit between 100
 * and 101, not the two boundary comparisons whose equal case passes, not the overwrite that makes 103
 * beat 102, and not the rewrite failure that raises 109. Transcribing any of those into a private
 * helper here and then asserting the row that helper's answer produced would be a test agreeing with
 * itself: it would pass whether or not production code implemented the same rules, and it would pass
 * with no producer in existence at all. Every reason used below is therefore a literal describing the
 * row under test, never a value this class derived.
 *
 * <p>Assumptions: the decision has a production owner and that owner has its own gate.
 * {@code com.carddemo.batch.dto.RejectReason} in the BATCH context holds the five codes with their
 * verbatim texts, the short-circuit predicate, the last-writer-wins precedence that selects 103 over
 * 102, the assignment-order comparator that resolves the accepted case, and the property that 109 is
 * never persisted; {@code services/batch-service/src/test/java/com/carddemo/batch/dto/RejectReasonTest.java}
 * asserts every one of those against that type. Those two files are named by path and nothing here
 * imports them: the migration plan forbids one service importing another's packages and
 * {@code LayeringRulesTest} enforces it, and this module declares no dependency on that one, so a
 * citation is the only reference available and is the correct one.
 *
 * <p>Trade-offs: the consequence is that this class cannot fail when a producer decides a reason
 * wrongly, and that is accepted because it could not honestly detect that in the first place -- a
 * repository test observes what was written, not what chose it. What it does still detect is every way
 * the table could corrupt a correctly decided value: a code stored at the wrong width, a description
 * truncated or re-cased, an image blank-padded to the wrong length, a column that refuses a null the
 * migration admits, or a second identical reject collapsed into the first. The end-to-end pairing of a
 * decided reason with a written row belongs to whichever job writes the stream, and to the reference
 * parity comparison over {@code tests/golden/posting/}, which is read as evidence and never touched.
 *
 * <h2>What this class deliberately does not re-assert</h2>
 *
 * <p>{@code TransactionRepositoryIT} in this package already pins the two facts every other test
 * here depends on: that Flyway applied the migration, and that an unqualified entity resolves to
 * {@code ledger} rather than to whatever else the connection can see. Neither is repeated below. A
 * second copy of an assertion is a second thing to keep true, and when the two copies drift the one
 * that still passes hides the one that should not.
 *
 * <h2>The 430-byte contract, stated three independent ways</h2>
 *
 * <p>Assumptions: this is the one table of the four with no copybook behind it. Its layout is
 * declared inline in {@code app/cbl/CBTRN02C.cbl}, which is read as specification and is never
 * modified, and it is asserted three times over. Line 176 declares {@code 01 REJECT-RECORD} over
 * {@code REJECT-TRAN-DATA PIC X(350)} at line 177 and {@code VALIDATION-TRAILER PIC X(80)} at
 * line 178. Lines 180 to 182 resolve that trailer into {@code WS-VALIDATION-FAIL-REASON PIC 9(04)}
 * and {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}. The file description states the same 350 and
 * 80 a second time, at lines 82 to 84. And the dataset attributes state it a third time from outside
 * the program: {@code app/jcl/POSTTRAN.jcl} line 36 declares
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} on the {@code DALYREJS} block spanning its lines 34
 * to 38, whose step is {@code //STEP15 EXEC PGM=CBTRN02C} at line 23 and whose six data definitions
 * span lines 28 to 42. So the widths sum as 350 plus 4 plus 76, or 430 bytes, and
 * {@code RECFM=F} makes that width the contract rather than an upper bound.
 *
 * <p>Assumptions: the record is fully occupied at 430 bytes, so unlike the three copybook-derived
 * tables of this schema there is no padding item to account for at this level. The 350 bytes are
 * themselves a {@code DALYTRAN-RECORD}, whose contract is {@code app/cpy/CVTRA06Y.cpy} lines 4 to
 * 18 -- a 21-line copybook whose header at line 2 states a record length of 350, whose lines 5 to 17
 * declare thirteen data items totalling 330 bytes, and whose line 18 closes the record with
 * {@code FILLER PIC X(20)}.
 *
 * <h2>The reason register, with the two texts that are the same text</h2>
 *
 * <p>Assumptions: five reason codes are set in the reference program, and every one of the citations
 * below was read on disk before it was written. Reason 100 is set at line 385 with its text at
 * lines 386 to 387, inside {@code 1500-A-LOOKUP-XREF} at line 380 on the {@code INVALID KEY} path at
 * line 384 of the read at line 383. Reason 101 is set at line 397 with its text at lines 398 to 399,
 * inside {@code 1500-B-LOOKUP-ACCT} at line 393 on the {@code INVALID KEY} path at line 396 of the
 * read at line 395. Reason 102 is set at line 410 with its text at line 411, and reason 103 at
 * line 417 with its text at line 418, both inside the {@code NOT INVALID KEY} branch that opens at
 * line 400. Reason 109 is set at line 556 with its text at lines 557 to 558, inside
 * {@code 2800-UPDATE-ACCOUNT-REC} at line 545 on the {@code INVALID KEY} path at line 555 of the
 * account {@code REWRITE} at line 554, which closes at line 559.
 *
 * <p>Assumptions: reason 101 and reason 109 carry byte-identical descriptions -- 24 characters of
 * {@code ACCOUNT RECORD NOT FOUND} at lines 398 and 557, with no distinguishing whitespace -- so
 * every case below discriminates on the code and no case below discriminates on the text alone. The
 * concrete consequence of getting that wrong is worth stating rather than leaving implied: a
 * regression that emitted 101 where 109 belongs, or 109 where 101 belongs, would leave a
 * text-only assertion green while proving nothing at all. They are genuinely different failures --
 * 101 is the initial account read failing at lines 395 to 397 and 109 is the account rewrite failing
 * at lines 554 to 556 -- and the sibling {@code src/test/resources/fixtures/README.md} records the
 * same warning in its own section 1.2 rather than leaving it to be rediscovered.
 *
 * <h2>Finding: reason 109 sets a reason and writes no row of this table</h2>
 *
 * <p>Assumptions: the baseline emits 109 on the {@code REWRITE} path with the same description text
 * as 101 but writes no reject row and does not change {@code RETURN-CODE}; the Java surfaces the
 * rewrite failure and discriminates on {@code reason_code}; the divergence is documented. The
 * sequence that makes the first clause true is mechanical and is set out in full because a reader
 * will find the code in the program and reasonably expect a row for it. Line 556 sets it inside the
 * paragraph at line 545, which is performed at line 441 from {@code 2000-POST-TRANSACTION} at
 * line 424 -- a path the main loop enters at line 212 only after line 211 has already tested the
 * reason and found it zero. The write happens on the {@code ELSE} at line 213 of that same test, at
 * line 215, and the loop that spans lines 202 to 219 never re-tests the reason afterwards. Lines 208
 * and 209 then reset the reason and the description at the top of the next record. So a 109 raised
 * while posting one record is cleared before the next record is validated, it is never counted by
 * the tally at line 214, and the write at line 451 is never reached for it.
 *
 * <p>Assumptions: two independent statements corroborate that from outside the program.
 * {@code tests/README.md} lists four reject reasons and stops -- its table spans lines 561 to 566 --
 * and its lines 558 to 559 say that each of those four writes the reject stream and sets a return
 * code of 4, neither of which 109 does. And {@code tests/fixtures/posting/} holds exactly nine
 * scenarios with no 109 among them, which is why the sibling fixtures document describes this as
 * coverage with nothing to mirror. Those trees are read as evidence and are never created,
 * regenerated or otherwise touched from here.
 *
 * <h2>Why the two boundary comparisons are named here and asserted elsewhere</h2>
 *
 * <p>Assumptions: the over-limit guard at line 407 is {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL}
 * with {@code CONTINUE} at line 408, so a projected balance landing exactly on the limit PASSES and
 * only a strictly greater one reaches the reject arm at lines 409 to 413. The expiration guard at
 * line 414 is {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} with {@code CONTINUE} at
 * line 415, so a transaction dated equal to the expiration date PASSES and only one dated beyond it
 * reaches the reject arm at lines 416 to 420. {@code tests/README.md} states both in plain English
 * at its lines 570 to 573. The field name in that second guard is the baseline's own spelling and is
 * quoted exactly as declared, because a citation that silently repairs a name stops resolving;
 * no member of this module is renamed and no case below asserts any renaming.
 *
 * <p>Assumptions: lines 407 and 414 are two SEQUENTIAL and UNGUARDED blocks inside the single
 * {@code NOT INVALID KEY} branch that opens at line 400, not an else-chain -- line 413 closes the
 * first and line 414 opens the second with no test of the reason between them. So a record failing
 * both has lines 417 and 418 overwrite lines 410 and 411, and the row that reaches this table
 * carries 103 with 103's description. The chain IS short-circuited earlier, and describing it as
 * uniformly short-circuited would be inaccurate: line 372 guards the account lookup at line 373 with
 * {@code IF WS-VALIDATION-FAIL-REASON = 0}, so 100 suppresses 101, and because 102 and 103 live
 * inside {@code NOT INVALID KEY} they cannot co-occur with 101 at all.
 *
 * <p>Trade-offs: those three paragraphs are recorded here and are NOT asserted by any case below, and
 * the split is deliberate rather than an omission. They describe which reason a producer must choose,
 * which is the decision this class does not own; a case here could only assert them by transcribing
 * them, and the section above records why a transcribed decision proves nothing. They are kept in this
 * charter because the four reason values the cases below store are meaningless without them -- a
 * reader who did not know that 103 can arrive for a record that is also over limit would misread the
 * register as four disjoint outcomes -- and because the fixture folders the cases read are named for
 * exactly these branches. The assertions live with the decision, in
 * {@code services/batch-service/src/test/java/com/carddemo/batch/dto/RejectReasonTest.java}.
 *
 * <p>Assumptions: the quantity the over-limit guard decides on is a projection accumulated at
 * lines 403 to 405 as {@code ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}, from the
 * CYCLE accumulators and not from the current balance. The subtraction of the debit accumulator is
 * not a sign correction, and reading it as one is the mistake this note exists to prevent: lines 547
 * to 552 accumulate SIGNED amounts, with line 548 testing {@code IF DALYTRAN-AMT >= 0} and routing a
 * non-negative amount to the credit accumulator at line 549 and a negative one to the debit
 * accumulator at line 551, so the debit accumulator holds negative values. A target that stored an
 * absolute value there would feed a different projection into the guard and reason 102 would fire on
 * different records. The {@code >= 0} test also routes a ZERO amount to CREDIT, which is a real
 * boundary rather than a curiosity, and the reference feed exercises both arms: the amount field of
 * the 300 records in {@code app/data/ASCII/dailytran.txt} carries 250 positive and 50 negative
 * values, measured. The account record is not this module's, so no case below reaches into
 * {@code account.accounts}; what is asserted here is only what {@code ledger.transaction_rejects}
 * makes observable about a value already decided.
 *
 * <h2>Three outcome vocabularies meet at this table and none of them is the others</h2>
 *
 * <p>Assumptions: lines 229 and 230 of the reference program set a return code of 4 when the reject
 * count exceeds zero, and that is a BEHAVIOUR of the program this class pins -- a completed run
 * carrying rejects, distinct from a clean run and from a hard failure. The reject count itself is
 * the job's own working-storage tally, declared at line 186 and incremented at line 214 in the same
 * branch that performs the write at line 215, which is why the repository under test declares no
 * count member and why no case below asks it for one. The reference parity suite grades its own
 * aggregate on a mainframe condition-code rubric whose warning tier is its green state, and that
 * rubric belongs to that suite alone. This module's own gate is the third and is BINARY: no case
 * below carries a graded tolerance, and no result of this build is described in graded terms.
 *
 * <h2>How a connection arrives, and how the schema is reached</h2>
 *
 * <p>Assumptions: connection coordinates arrive as a bean and never as text. The container field
 * below carries {@code @ServiceConnection}, which contributes a connection-details bean that the
 * auto-configuration prefers over any datasource property, so no URL, host, port, user name or
 * password appears anywhere in this file and none may be added. The container's port is assigned at
 * run time, so a written connection string would either address nothing or address whichever
 * database happened to be listening -- and the second failure mode is the worse of the two, because
 * it passes.
 *
 * <p>Trade-offs: {@code @ActiveProfiles("test")} is load-bearing rather than conventional. The
 * profile document at {@code src/test/resources/application-test.yml} holds the only surviving
 * mechanism that reaches the {@code ledger} schema inside a container database that starts empty,
 * because the JDBC URL is GENERATED by the container and so no schema parameter can ride on it, and
 * because a pooled search-path statement cannot be relied upon either -- PostgreSQL accepts a search
 * path naming a schema that does not exist. What remains is that profile's Flyway and Hibernate
 * default-schema settings together with its schema-creation allowance. Omitting the annotation does
 * not degrade a run, it fails it on a missing schema before any assertion executes. The compromise
 * accepted is that a mandatory annotation is enforced by that failure rather than by the compiler.
 * The profile file is already authored and is cited rather than duplicated, because a second copy of
 * a profile key is a second place to change it and only one of the two would be read.
 *
 * <h2>The engine is real, and an in-memory substitute could not carry the properties</h2>
 *
 * <p>Assumptions: H2 is the specific alternative excluded, and it is excluded on capability rather
 * than on preference. Three properties asserted below are engine behaviours it does not reproduce
 * faithfully: a declared-width character column that blank-pads a short value out to its full 350
 * characters on the way in and hands all 350 back on the way out, a database-assigned identity
 * returned on insert and then relied on to keep two identical record images separately addressable,
 * and the non-unique secondary-index and keyset access paths the rest of this package depends on.
 * Substituting an embedded engine would leave those assertions passing against something other than
 * the engine that runs in production, which is the failure mode a repository test exists to prevent.
 * The module POM records the same exclusion beside the Testcontainers artifacts it declares at test
 * scope, and no embedded driver is on this classpath at all.
 *
 * <p>Assumptions: Flyway needs two artifacts on this classpath and not one. {@code flyway-core} and
 * {@code flyway-database-postgresql} are both managed at 13.0.0 by {@code services/pom.xml}. From
 * Flyway 10 onwards the database-specific support moved out of core into companion artifacts, so
 * core on its own resolves and COMPILES perfectly and then fails as the application context starts,
 * with no PostgreSQL support registered. The failure therefore cannot appear at build time; it
 * appears the moment a container starts, as a schema that is never built and a suite failing on
 * absent tables.
 *
 * <h2>Contracts consumed, never re-declared</h2>
 *
 * <p>Refactoring Rationale: {@code tests/README.md} lines 540 to 542 impose single-sourcing on the
 * reference suite's own tests -- a layout resolves through the compiler's include path and is never
 * duplicated -- and the analogue holds here exactly. So the 350-byte and 430-byte layouts are
 * consumed from {@code TransactionReject}, from {@code db/migration/V1__ledger.sql} and from the
 * sibling {@code src/test/resources/fixtures/README.md}, and neither is restated below. No
 * zoned-decimal codec, no sign-overpunch table and no field-position map is written here. A local copy
 * of any of them would reintroduce precisely the drift an include path forecloses, and the drift would
 * be silent, because both copies would go on compiling and the stale one would go on passing its own
 * assertions.
 *
 * <p>Assumptions: this class consumes NO arithmetic contract at all -- neither the shared money type
 * for an inclusive limit comparison nor the shared formatter for a ten-character date slice. Either
 * would only be needed to recompute a rule, and recomputing a rule in a test makes the test agree with
 * itself instead of with production, so there is nothing here for either contract to serve. That is
 * the correct end state for a class whose subject is a table rather than a calculation.
 *
 * <p>Trade-offs: the record image is therefore carried as one undivided 350-character value and is
 * never split into the thirteen items its layout would yield. What is given up is the ability to
 * assert on a card number or an amount held inside the image. What is bought is the property the
 * stream exists for: a rejected record has to be retained verbatim to stay re-drivable, and line 447
 * is {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}, a wholesale group move with no field-level
 * handling in the paragraph at all. Decomposition would discard the very bytes that caused the
 * reject, and a reason 100 record is by definition one whose card number resolved to nothing, so
 * content that no parse would accept is the normal case here rather than the exceptional one.
 *
 * <h2>What this class asserts, and where the reject DECISION is asserted</h2>
 *
 * <p>Assumptions: what this class asserts is that a row stored under a reason carries that reason's code
 * and its description at their declared widths, that one rejected record yields exactly one row, that a
 * record which passes leaves the table untouched, and that the declared constraints refuse what they say
 * they refuse. The boundary drawn at the head of this charter is what keeps the list that short.
 *
 * <p>Assumptions: the decision belongs to the posting owner and is asserted there.
 * {@code PostingValidationService} and {@code PostingValidationResult} in the batch deployable carry it --
 * that is the module {@code app/cbl/CBTRN02C.cbl} migrates to, and it reaches this table through a narrowly
 * scoped cross-schema grant so the three posting writes stay one atomic commit. Its
 * {@code PostingValidationServiceTest} asserts the short-circuit of reasons 100 and 101, both inclusive
 * boundaries, and the overwrite that makes a record failing both boundaries report 103 rather than 102.
 * Each reason used below is therefore a literal describing the row under test, not a value this class
 * derived.
 */
@Testcontainers
@SpringBootTest(
        classes = TransactionRejectRepositoryIT.RejectStreamPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TransactionRejectRepositoryIT {

    /** The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine. */
    // WHY : Assumptions: a digest is used because determinism is the property being bought, and a
    //       digest is the only reference that supplies it. The same digest resolves to the same
    //       bytes forever, so a run months from now exercises the engine this file was written
    //       against rather than whatever has since been published. The version above is prose
    //       because a digest states nothing a reader can recognise.
    // WHY : Alternatives Considered: an exact minor tag such as postgres:17.10-alpine, which reads
    //       better than a digest. Rejected because it is still mutable -- a publisher may rebuild
    //       and republish one minor tag on a new base layer -- so it would narrow the drift without
    //       closing it. A floating tag is excluded outright: it would let the engine change under an
    //       unchanged assertion, and the properties under test here are engine behaviours, including
    //       whether a declared-width character column hands back all 350 of its characters.
    // WHY : Assumptions: the digest is deliberately the one the two sibling tests in this package
    //       already name. Three integration tests pinning three different engines could disagree
    //       about one schema, and the disagreement would surface as whichever of them ran last.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";
    /**
     * Class-path location of the harness that creates the schema's owning role in the container.
     *
     * <p>Assumptions: the role is a NOLOGIN role {@code data-migration/sql/V0__schemas_and_roles.sql} names and no container has, so it has
     * to exist before Flyway opens a connection and assumes it. A Testcontainers init script runs once
     * at container start, which is strictly earlier than Flyway's first connection; the alternative
     * this replaces -- creating the role from {@code spring.flyway.init-sqls} -- carried Flyway's
     * deprecated {@code initSql} setting and its per-connection removal notice.</p>
     */
    private static final String OWNER_ROLE_SCRIPT = "db/testharness/test-harness-owner-role.sql";

    /**
     * The container the assertions run against, started once for this class.
     *
     * <p>Assumptions: the type comes from {@code org.testcontainers.postgresql} and NOT from
     * {@code org.testcontainers.containers}. Testcontainers 2.0.5 ships both and only the legacy one
     * is deprecated, so importing the legacy package would carry a compile-time notice into every
     * future build of this module for no benefit. The replacement is not generic, so the declaration
     * carries no type argument; nothing here relied on the self-type that parameter existed to
     * refine, because the container is configured entirely through {@code @ServiceConnection} rather
     * than by chained builder calls.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(OWNER_ROLE_SCRIPT);

    /** The declared width of the retained record image, in characters. */
    // WHY : Assumptions: this width is CONSUMED rather than owned here. It is stated by
    //       app/cpy/CVTRA06Y.cpy line 2, by app/cbl/CBTRN02C.cbl line 177 and by the raw_record
    //       column of db/migration/V1__ledger.sql, and it appears in this file only so an assertion
    //       can name the number it is checking. Nothing below derives a field position from it.
    private static final int IMAGE_WIDTH = 350;

    /** The declared width of the reason code as a fixed-width form, in characters. */
    // WHY : Assumptions: four digits because app/cbl/CBTRN02C.cbl line 181 declares
    //       WS-VALIDATION-FAIL-REASON PIC 9(04), which is an unsigned display numeric and therefore
    //       renders zero-padded to its full width. That is observable in the reference comparison
    //       files rather than only inferable: the reject stream produced for reason 101 carries the
    //       four bytes 0101 at one-based positions 351 to 354, and the one produced for reason 103
    //       carries 0103 there, each inside a line of exactly 430 bytes.
    private static final int REASON_WIDTH = 4;

    /** The declared width of the reason description, in characters. */
    // WHY : Assumptions: 76 characters because app/cbl/CBTRN02C.cbl line 182 declares
    //       WS-VALIDATION-FAIL-REASON-DESC PIC X(76). The longest of the five reference texts is 42
    //       characters, so the declared width is preserved as the contract rather than narrowed to
    //       the observed maximum, which would make any sixth text a truncation.
    private static final int DESCRIPTION_WIDTH = 76;

    /** The declared width of one whole reject record, in characters. */
    // WHY : Assumptions: this is asserted from three observed lengths rather than computed from the
    //       three constants above, because computing it would restate an arithmetic identity while
    //       reading as though it had checked the columns. app/jcl/POSTTRAN.jcl line 36 declares
    //       LRECL=430 with RECFM=F, so the width is the contract and not an upper bound.
    private static final int REJECT_RECORD_WIDTH = 430;

    /** The reason value the reference program treats as no reject at all. */
    // WHY : Assumptions: zero is the sentinel the program itself uses, not one invented here.
    //       app/cbl/CBTRN02C.cbl line 208 moves 0 into the reason at the top of each record and
    //       line 211 tests IF WS-VALIDATION-FAIL-REASON = 0 to decide between posting at line 212
    //       and the reject branch at line 213, so zero is the whole discriminator.
    private static final short REASON_NONE = 0;

    /** Reason 100, raised when the card number resolves to no cross-reference record. */
    private static final short REASON_CARD_NOT_FOUND = 100;

    /** Reason 101, raised when the account the cross-reference names does not exist. */
    private static final short REASON_ACCOUNT_NOT_FOUND = 101;

    /** Reason 102, raised when the projected balance strictly exceeds the credit limit. */
    private static final short REASON_OVER_LIMIT = 102;

    /** Reason 103, raised when the transaction date falls beyond the account expiration date. */
    private static final short REASON_AFTER_EXPIRATION = 103;

    /** Reason 109, raised when the account rewrite fails after validation has already passed. */
    // WHY : Assumptions: the reason space is deliberately OPEN, so these five are named constants
    //       rather than members of an enumerated type. app/cbl/CBTRN02C.cbl line 377 carries the
    //       comment `* ADD MORE VALIDATIONS HERE` at the end of the validation sequence, and the
    //       source field is PIC 9(04) at line 181, whose declared domain is every value four digits
    //       admit. A closed type would refuse an unmodelled code at the persistence boundary, which
    //       for a stream whose purpose is to retain what happened turns a recordable outcome into an
    //       unrecordable one. The column the migration declares is a narrow exact integer for the
    //       same reason, and this package declares no enumerated type by charter in any case.
    private static final short REASON_REWRITE_INVALID_KEY = 109;

    /** The verbatim reason-100 description, carried across character for character. */
    private static final String TEXT_CARD_NOT_FOUND = "INVALID CARD NUMBER FOUND";

    /** The verbatim reason-101 description, which reason 109 also carries. */
    // WHY : Assumptions: one constant serves both codes because the two texts are the same 24
    //       characters -- app/cbl/CBTRN02C.cbl line 398 for 101 and line 557 for 109 -- and giving
    //       them two equal constants would suggest a difference that does not exist and would let
    //       one of the two be edited alone. Sharing the constant makes the collision structural, so
    //       every case that needs to tell the pair apart is forced onto the code.
    private static final String TEXT_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /** The verbatim reason-102 description, carried across character for character. */
    private static final String TEXT_OVER_LIMIT = "OVERLIMIT TRANSACTION";

    /** The verbatim reason-103 description, carried across character for character. */
    private static final String TEXT_AFTER_EXPIRATION = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";


    /** The account credit limit both boundary scenarios are built around. */
    // WHY : Assumptions: the account tier is a PRECONDITION this test establishes rather than data
    //       the fixture folder ships, which is that folder's own recorded decision -- it carries no
    //       acctdata.txt and no cardxref.txt at all. The value is measured from the reference
    //       scenarios of the same names, whose account record carries a credit limit field of
    //       00000020650{ in zoned display form, the trailing brace being a positive-zero sign
    //       overpunch, which is 2065.00. Both cycle accumulators of that record are zero.
    private static final Money CREDIT_LIMIT = Money.of("2065.00");

    /** The cycle credit accumulator, zero in both boundary scenarios. */
    private static final Money CYCLE_CREDIT = Money.ZERO;

    /** The cycle debit accumulator, zero in both boundary scenarios. */
    // WHY : Assumptions: zero here is measured and not merely convenient, and it is what makes the
    //       projection at app/cbl/CBTRN02C.cbl lines 403 to 405 reduce to the transaction amount
    //       alone in these two scenarios. The accumulator would otherwise hold a NEGATIVE total,
    //       because line 551 adds a negative amount to it, which is precisely why line 404
    //       subtracts it rather than adding it.
    private static final Money CYCLE_DEBIT = Money.ZERO;

    /** The amount landing exactly on the credit limit, which the reference posts. */
    // WHY : Assumptions: measured from the amount field of
    //       src/test/resources/fixtures/boundary_exact_limit/dailytran.txt, which reads 0000020650{
    //       at one-based positions 133 to 143. Its sibling reject_102_overlimit differs from it at
    //       exactly one byte, position 143, where the sign overpunch carries a positive one instead
    //       of a positive zero, which is the one-cent step below.
    private static final Money AMOUNT_AT_LIMIT = Money.of("2065.00");

    /** The amount one cent beyond the credit limit, which the reference rejects. */
    private static final Money AMOUNT_ONE_CENT_OVER = Money.of("2065.01");

    /** The account expiration date both expiration scenarios are built around. */
    // WHY : Assumptions: measured from the expiration-date field of the reference account record for
    //       the two boundary scenarios, which reads 2024-12-13 in the ten bytes at one-based
    //       positions 59 to 68. The scenario that must post carries that same date in its
    //       originating timestamp and the scenario that must reject carries one day beyond it.
    private static final LocalDate ACCOUNT_EXPIRATION_DATE = LocalDate.of(2024, 12, 13);

    /** The 26-character originating timestamp whose date equals the expiration date. */
    // WHY : Assumptions: the whole 26-character contract value is declared rather than only its ten
    //       date characters, so that the slice line 414 performs with (1:10) is executed here by the
    //       shared kernel rather than restated as a substring. TimestampFormatter refuses a value of
    //       any other width, which is what turns a field read at the wrong position into a failure
    //       at the slice instead of a plausible-looking date escaping into a comparison.
    private static final String ORIG_TS_DATED_ON_EXPIRY = "2024-12-13 19:27:53.000000";

    /** The 26-character originating timestamp whose date is one day beyond the expiration date. */
    private static final String ORIG_TS_DATED_ONE_DAY_PAST = "2024-12-14 19:27:53.000000";

    /** The scenario folder holding the well-formed template record. */
    private static final String SCENARIO_HAPPY_PATH = "happy_path";

    /** The scenario folder whose amount lands exactly on the credit limit. */
    private static final String SCENARIO_BOUNDARY_EXACT_LIMIT = "boundary_exact_limit";

    /** The scenario folder whose originating date equals the account expiration date. */
    private static final String SCENARIO_BOUNDARY_EXPIRY_EQUAL = "boundary_expiry_equal";

    /** The scenario folder whose card number resolves to no cross-reference record. */
    private static final String SCENARIO_REJECT_CARD_MISSING = "reject_100_card_missing";

    /** The scenario folder whose resolved account does not exist. */
    private static final String SCENARIO_REJECT_ACCOUNT_MISSING = "reject_101_acct_missing";

    /** The scenario folder whose amount is one cent beyond the credit limit. */
    private static final String SCENARIO_REJECT_OVER_LIMIT = "reject_102_overlimit";

    /** The scenario folder whose originating date is one day beyond the expiration date. */
    private static final String SCENARIO_REJECT_EXPIRED = "reject_103_expired";

    /** The scenario folder whose account rewrite fails after validation has already passed. */
    // WHY : Assumptions: this folder exists on the target side only. tests/fixtures/posting/ holds
    //       nine scenarios covering 100, 101, 102 and 103 and carries no 109 anywhere, so this
    //       scenario is authored from the record layout rather than adapted from an existing one,
    //       and the sibling fixtures document records that in its own section 1.2. Its
    //       dailytran.txt is byte-identical to the template, because by construction nothing in an
    //       input record can express a failure raised after validation has already succeeded.
    private static final String SCENARIO_REJECT_REWRITE_INVALID_KEY =
            "reject_109_rewrite_invalid_key";

    /** The number of columns the migration declares on this table. */
    // WHY : Assumptions: four rather than three. The three payload columns reconstruct the 430-byte
    //       record, and the fourth is the generated reject-event ordinal that the migration adds on
    //       the target side because the baseline stream has no key of its own -- its file-control
    //       entry at app/cbl/CBTRN02C.cbl line 46 declares none, while the cross-reference file
    //       selected in the same paragraph does declare one at line 43.
    private static final int MAPPED_COLUMN_COUNT = 4;

    /** The repository under test, whose query surface is entirely inherited. */
    // WHY : Assumptions: this interface declares NO member of its own, and no case below asks it
    //       for a custom finder or a reject tally. That emptiness is the faithful translation rather
    //       than an unfinished file: app/cbl/CBTRN02C.cbl line 46 selects the stream with no record
    //       key, line 293 opens it OUTPUT rather than I-O, line 451 writes it and line 639 closes
    //       it, and no statement anywhere reads it back. The tally the program reports is its own
    //       working-storage counter, declared at line 186 and incremented at line 214, so a count
    //       member here would compute a different number as soon as two runs shared the table and a
    //       caller could not tell which of the two answered its question.
    @Autowired
    private TransactionRejectRepository rejects;

    /** Used to write inside one transaction, to flush, to clear, and to read the catalogue. */
    // WHY : Assumptions: the physical contract is asserted through the engine's own catalogue rather
    //       than by inspecting configuration, because a configured width and an effective one are
    //       different facts and only the second one decides whether a 350-character image survives.
    @Autowired
    private EntityManager entityManager;

    /** Supplies the single transaction the write-flush-clear helper runs in. */
    // WHY : Alternatives Considered: annotating this class transactional, which is the shorter
    //       wiring. Rejected because it would wrap every case in a scope that never commits, and
    //       that arrangement is exactly what lets an assertion be satisfied by the persistence
    //       context rather than by the engine. It would matter most for the reason-109 case below,
    //       whose whole content is that NO row was written: a rollback-per-case wrapper makes an
    //       unwritten row and a written-then-discarded row indistinguishable, so the one assertion
    //       this class exists for would pass either way.
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * Empties the table before each case so that no case can pass on another case's rows.
     *
     * <p>Assumptions: deleting rows rather than rebuilding the schema keeps the Flyway migration
     * applied once per container, which is what leaves the sibling test's migration assertion a
     * statement about the migration rather than about this method. The package charter records the
     * isolation discipline this implements, and the reference suite states it for the parity oracle
     * in section 11 of {@code tests/README.md}, whose heading is at line 475 and whose lines 479 to
     * 480 require a fresh workspace per test. Only the discipline transfers; none of the mechanics
     * that section describes do.
     */
    @BeforeEach
    void clearTable() {
        this.rejects.deleteAll();
    }

    /**
     * Confirms the four columns carry the declared types, widths and nullability the migration gives
     * them.
     *
     * <p>This pins the inline layout of {@code app/cbl/CBTRN02C.cbl} lines 176 to 182, corroborated
     * by the file description at its lines 82 to 84 and by the dataset attributes at
     * {@code app/jcl/POSTTRAN.jcl} line 36.
     *
     * <p>Assumptions: the type is asserted together with its width in one rendered value, because a
     * character column of the right type and the wrong width would still accept every record this
     * file writes while silently changing how many characters came back. Ordering by declared
     * position additionally asserts that the ordinal precedes the three payload columns and that the
     * payload columns sit in the order the program fills them at lines 447 and 448.
     *
     * <p>Assumptions: the image column is fixed width and the description column is varying, and the
     * asymmetry is deliberate. The image width IS the contract, because {@code RECFM=F} makes every
     * record occupy its declared length, so trailing blanks there are significant padding rather than
     * absent data. The description's trailing blanks are padding the baseline field forced and no
     * comparison depends on them, so a varying column carries it without loss.
     *
     * <p>Assumptions: nullability is asserted in the same rendered value as the type and the width,
     * because it is the third property of a column that can be got wrong without any write failing.
     * All four columns are NOT NULLABLE: the ordinal because it is the primary key, and the three
     * payload columns because {@code db/migration/V1__ledger.sql} declares each of them
     * {@code NOT NULL} and {@code TransactionReject} mirrors that with {@code nullable = false} on all
     * three mappings. A payload column loosened to nullable would admit a row from which the 430-byte
     * record cannot be reconstructed, and an ordinal loosened to nullable would admit a row with no
     * identity at all. A separate case below attempts such a row, because a catalogue reading and a
     * refused insert are two different claims and the catalogue alone would be satisfied by a column
     * the engine reports as {@code NOT NULL} while a mapping quietly wrote a null past it.
     *
     * <p>Assumptions: the expectation names what the migration and the entity BOTH declare, which is
     * {@code NOT NULL} on all three payload columns. The migration records the reason at its own
     * {@code raw_record} declaration: a null in any of the three makes the 430-byte record
     * UNRECONSTRUCTABLE, since 350 plus 4 plus 76 is exactly the declared width and a null component
     * has no width. Reading the expectation from one side alone is what lets the two disagree, and the
     * disagreement then surfaces as a failure here rather than as a review comment.
     */
    @Test
    void theFourMappedColumnsCarryTheDeclaredTypesWidthsAndNullabilityTheMigrationGivesThem() {
        // WHY : Assumptions: a fixed-width character column reports its width in the maximum-length
        //       column while an exact integer reports precision and scale instead, so the coalesce
        //       selects whichever of the two the column actually populates and one query carries
        //       both shapes.
        List<String> columns = this.nativeStringColumn(
                "select column_name || ':' || data_type || ':'"
                        + " || coalesce(character_maximum_length::text,"
                        + "             numeric_precision || ',' || numeric_scale, '')"
                        + " || ':' || is_nullable"
                        + " from information_schema.columns"
                        + " where table_schema = 'ledger'"
                        + "   and table_name = 'transaction_rejects'"
                        + " order by ordinal_position");

        assertThat(columns)
                .hasSize(MAPPED_COLUMN_COUNT)
                .containsExactly(
                        "reject_seq:bigint:64,0:NO",
                        "raw_record:character:350:NO",
                        "reason_code:smallint:16,0:NO",
                        "reason_desc:character varying:76:NO");
    }

    /**
     * Confirms a row whose three payload columns are all absent is refused rather than stored.
     *
     * <p>Assumptions: this is the executable half of the nullability contract the catalogue reading
     * above states, and the two halves catch different faults. The catalogue reports what the engine
     * DECLARES; it would still report NO for a column that a mapping omitted from its insert
     * statement, or that a provider filled with a substituted default -- an empty string, a zero code
     * -- and either would put a row in the table that the declaration says cannot exist. Attempting
     * the write is what closes that gap.
     *
     * <p>Assumptions: the refusal is required to leave the table EMPTY, asserted after the attempt. A
     * raised exception alone would be satisfied by a write that had already inserted the row and
     * failed afterwards, and a stored row from which the 430-byte record cannot be rebuilt is the
     * exact state these three constraints exist to prevent -- a reconstruction concatenates the padded
     * 350-byte image, the four-digit code and the 76-character description, and a null component has
     * no width, so every field after the gap would sit at the wrong offset and the record would parse
     * cleanly into different data.
     *
     * <p>Assumptions: a row of this shape is not one the reference stream produces either -- every
     * record it writes carries all three components, because line 447 moves the image and line 448
     * moves the assembled trailer before the single write at line 451 -- so refusing it agrees with
     * the baseline rather than restricting it.
     *
     * <p>Refactoring Rationale: this case asserts the incomplete row is REFUSED rather than persisting
     * it and asserting it round-trips as absent. All three payload columns carry {@code NOT NULL} and
     * {@code TransactionReject} carries {@code nullable = false} on each of their mappings, so a
     * round-trip expectation would contradict both. The case is kept in this inverted form rather than
     * dropped, because the property worth exercising is the same one -- what the schema does with an
     * incomplete reject -- and dropping it would leave those constraints asserted by a catalogue
     * reading alone.
     */
    @Test
    void aRowWhoseThreePayloadColumnsAreAllAbsentIsRefused() {
        // WHY : Assumptions: the entity constructor is called directly rather than through this
        //       class's reject helper, because that helper declares its reason as a primitive short
        //       and so cannot express an absent code at all. Widening the helper instead would let
        //       every other case in this file pass a null reason by accident, where each of them
        //       means to store a decided one.
        TransactionReject empty = new TransactionReject(null, null, null);

        // WHY : Assumptions: the refusal is identified by the COLUMN and the constraint kind it names
        //       and not merely by an exception being raised. Three of this table's four columns are
        //       NOT NULL, so an assertion that only expected a failure would pass identically had the
        //       row been refused for a different one of them -- and the case would then prove nothing
        //       about the column its name claims.
        // WHY : Trade-offs: the type asserted is the provider's own, which couples this line to
        //       Hibernate. It is accepted because the failure arrives from the explicit flush inside
        //       persistAndDetach rather than at commit, so it propagates before the transaction manager
        //       gets the chance to translate it into Spring's DataAccessException hierarchy; asserting
        //       the translated type would fail against the very mechanism this class uses to write.
        assertThatThrownBy(() -> this.persistAndDetach(empty))
                .as("a reject missing any of its three components cannot be reconstructed")
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("raw_record")
                .hasMessageContaining("violates not-null constraint");

        assertThat(this.rowCount())
                .as("the refusal left no partially written row behind")
                .isZero();
    }

    /**
     * Confirms a full-width record image round-trips character for character, trailing blanks and
     * all, once the persistence context has been flushed and cleared.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} line 447, the wholesale group move
     * {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} inside
     * {@code 2500-WRITE-REJECT-REC} at line 446.
     *
     * <p>Assumptions: the value is asserted at its DECLARED width rather than after trimming,
     * because a fixed-width column blank-pads to 350 and the trailing blanks are part of the record.
     * Trimming first would make an image stored 20 characters short indistinguishable from one stored
     * whole, and the final 20 characters of this layout are exactly the {@code FILLER PIC X(20)} that
     * {@code app/cpy/CVTRA06Y.cpy} line 18 declares -- so a trimming assertion would be blind to the
     * one region of the record that is always blank.
     *
     * <p>Assumptions: the image is taken whole from the scenario fixture and is never split, so this
     * case asserts nothing about any field inside it. The fields are the sibling
     * {@code DailyTransaction}'s to expose, and the byte positions are that entity's, the migration's
     * and the fixtures document's to own.
     */
    @Test
    void theFullWidthRecordImageRoundTripsCharacterForCharacterIncludingItsTrailingBlanks() {
        String image = this.fixtureRecord(SCENARIO_HAPPY_PATH);
        TransactionReject written =
                this.reject(image, REASON_CARD_NOT_FOUND, TEXT_CARD_NOT_FOUND);

        this.persistAndDetach(written);
        TransactionReject reRead = this.requireRow(written.getRejectSeq());

        assertThat(reRead.getRawRecord()).isEqualTo(image).hasSize(IMAGE_WIDTH);
    }

    /**
     * Confirms a short image is blank-padded out to the declared width by the fixed-width column.
     *
     * <p>This pins the {@code PIC X(350)} declaration at {@code app/cbl/CBTRN02C.cbl} line 177,
     * restated as {@code FD-REJECT-RECORD} at its line 83.
     *
     * <p>Assumptions: this is the specific engine behaviour that makes the fixed-width choice
     * load-bearing rather than cosmetic, so it is asserted rather than assumed. A varying column
     * would faithfully store a short value and hand back exactly what it was given, which would let
     * a malformed inbound record be compared at a width the baseline stream never produces. The
     * padded form is what a byte-for-byte comparison of the stream reads.
     *
     * <p>Trade-offs: a deliberately short image is written here, which no well-formed record ever
     * is. What is bought is that the padding is observed rather than inferred from a value that was
     * already the right width, and a case fed only full-width images could not tell a padding column
     * from a varying one at all.
     */
    @Test
    void aShortImageIsBlankPaddedOutToTheDeclaredWidthByTheFixedWidthColumn() {
        String shortImage = "MALFORMED INBOUND RECORD";
        TransactionReject written =
                this.reject(shortImage, REASON_CARD_NOT_FOUND, TEXT_CARD_NOT_FOUND);

        this.persistAndDetach(written);
        TransactionReject reRead = this.requireRow(written.getRejectSeq());

        assertThat(reRead.getRawRecord())
                .hasSize(IMAGE_WIDTH)
                .startsWith(shortImage)
                .isEqualTo(shortImage + " ".repeat(IMAGE_WIDTH - shortImage.length()));
    }

    /**
     * Confirms the three payload columns reconstruct a record of exactly the declared 430 characters.
     *
     * <p>This pins {@code app/jcl/POSTTRAN.jcl} line 36, {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)},
     * against the two-part working-storage record at {@code app/cbl/CBTRN02C.cbl} lines 176 to 178
     * and its decomposed trailer at lines 180 to 182.
     *
     * <p>Assumptions: the three lengths are measured from a row that was written and re-read, not
     * summed from the declared constants, because summing them would restate an arithmetic identity
     * while reading as though it had checked the columns. The description is written at its full 76
     * characters here for the same reason: a shorter one would leave the widest part of the trailer
     * untested and the total would still reconcile.
     *
     * <p>Assumptions: the reason contributes four characters because it is an unsigned display
     * numeric, {@code PIC 9(04)} at line 181, and therefore occupies its full declared width in the
     * record whatever the magnitude of the value. That is why the code is measured through its
     * fixed-width rendering rather than through the stored integer.
     */
    @Test
    void theThreePayloadColumnsReconstructARecordOfExactlyFourHundredAndThirtyCharacters() {
        String image = this.fixtureRecord(SCENARIO_HAPPY_PATH);
        String widestDescription = this.blankPadded(TEXT_AFTER_EXPIRATION, DESCRIPTION_WIDTH);
        TransactionReject written =
                this.reject(image, REASON_AFTER_EXPIRATION, widestDescription);

        this.persistAndDetach(written);
        TransactionReject reRead = this.requireRow(written.getRejectSeq());
        String renderedReason = this.fixedWidthReason(reRead.getReasonCode());

        assertThat(reRead.getRawRecord()).hasSize(IMAGE_WIDTH);
        assertThat(renderedReason).hasSize(REASON_WIDTH);
        assertThat(reRead.getReasonDesc()).hasSize(DESCRIPTION_WIDTH);
        assertThat(reRead.getRawRecord().length()
                        + renderedReason.length()
                        + reRead.getReasonDesc().length())
                .isEqualTo(REJECT_RECORD_WIDTH);
    }

    /**
     * Confirms reason 100 round-trips with its verbatim description.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} lines 380 to 387: paragraph
     * {@code 1500-A-LOOKUP-XREF} at line 380, the read at line 383, the {@code INVALID KEY} path at
     * line 384, the {@code MOVE 100} at line 385 and the text at lines 386 to 387.
     *
     * <p>Assumptions: the description is asserted character for character rather than by a
     * containment or a case-insensitive match, because the reject stream is compared byte for byte
     * against the reference comparison files and the migration carries every user-visible string
     * across unchanged. A relaxed match would accept a recased or reflowed text that the comparison
     * would then reject, and it would do so silently.
     */
    @Test
    void reasonOneHundredRoundTripsWithTheVerbatimInvalidCardNumberDescription() {
        this.assertReasonRoundTrips(
                SCENARIO_REJECT_CARD_MISSING, REASON_CARD_NOT_FOUND, TEXT_CARD_NOT_FOUND);
    }

    /**
     * Confirms reason 101 round-trips with its verbatim description.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} lines 393 to 399: paragraph
     * {@code 1500-B-LOOKUP-ACCT} at line 393, the read at line 395, the {@code INVALID KEY} path at
     * line 396, the {@code MOVE 101} at line 397 and the text at lines 398 to 399.
     *
     * <p>Assumptions: this reason and reason 109 carry the same 24 characters, so this case asserts
     * the code as well as the text and could not stand on the text alone. The scenario's own record
     * is byte-identical to the template, because 101 requires the cross-reference read to have
     * SUCCEEDED and the account it named to be absent -- an absence the test establishes as a
     * precondition rather than one the input record can express.
     */
    @Test
    void reasonOneHundredAndOneRoundTripsWithTheVerbatimAccountNotFoundDescription() {
        this.assertReasonRoundTrips(
                SCENARIO_REJECT_ACCOUNT_MISSING, REASON_ACCOUNT_NOT_FOUND, TEXT_ACCOUNT_NOT_FOUND);
    }

    /**
     * Confirms reason 102 round-trips with its verbatim description.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} lines 409 to 413, the reject arm of the over-limit
     * guard at line 407, with the {@code MOVE 102} at line 410 and the text at line 411, inside the
     * {@code NOT INVALID KEY} branch that opens at line 400.
     */
    @Test
    void reasonOneHundredAndTwoRoundTripsWithTheVerbatimOverlimitDescription() {
        this.assertReasonRoundTrips(
                SCENARIO_REJECT_OVER_LIMIT, REASON_OVER_LIMIT, TEXT_OVER_LIMIT);
    }

    /**
     * Confirms reason 103 round-trips with its verbatim description.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} lines 416 to 420, the reject arm of the expiration
     * guard at line 414, with the {@code MOVE 103} at line 417 and the text at line 418.
     *
     * <p>Assumptions: this is the longest of the five texts at 42 characters, so it is the one that
     * would surface a description column narrowed below the declared 76. It is stored unpadded here
     * and the padded form is exercised by the width-reconstruction case above, so the two cases
     * together cover both the value and the declared width without either restating the other.
     */
    @Test
    void reasonOneHundredAndThreeRoundTripsWithTheVerbatimAfterExpirationDescription() {
        this.assertReasonRoundTrips(
                SCENARIO_REJECT_EXPIRED, REASON_AFTER_EXPIRATION, TEXT_AFTER_EXPIRATION);
    }

    /**
     * Confirms reasons 101 and 109 carry identical descriptions and are separated only by the code.
     *
     * <p>This pins the collision between {@code app/cbl/CBTRN02C.cbl} lines 397 to 399, where the
     * initial account read raises 101, and its lines 554 to 558, where the account
     * {@code REWRITE} inside {@code 2800-UPDATE-ACCOUNT-REC} at line 545 raises 109 with the text at
     * lines 557 to 558.
     *
     * <p>Assumptions: the two rows are told apart on {@code reason_code} and the equality of their
     * descriptions is asserted explicitly, so the collision is pinned rather than worked around. The
     * concrete failure this prevents is a consumer written to key on the description: it would report
     * an account read failure and an account rewrite failure as one condition, and a regression that
     * swapped the two codes would leave every text assertion green. The sibling
     * {@code src/test/resources/fixtures/README.md} records the same warning in its section 1.2.
     *
     * <p>Assumptions: both rows are persisted here, which the baseline never does for 109 -- the case
     * below covers that separately. What this case is about is the discrimination rule, so it needs
     * two stored rows to discriminate between; it makes no claim about which of the two the reference
     * write path produces.
     */
    @Test
    void theAccountNotFoundDescriptionIsSharedSoOnlyTheReasonCodeSeparatesTheTwoFailures() {
        String image = this.fixtureRecord(SCENARIO_REJECT_ACCOUNT_MISSING);
        TransactionReject readFailure =
                this.reject(image, REASON_ACCOUNT_NOT_FOUND, TEXT_ACCOUNT_NOT_FOUND);
        TransactionReject rewriteFailure =
                this.reject(image, REASON_REWRITE_INVALID_KEY, TEXT_ACCOUNT_NOT_FOUND);

        this.persistAndDetach(readFailure, rewriteFailure);
        TransactionReject storedReadFailure = this.requireRow(readFailure.getRejectSeq());
        TransactionReject storedRewriteFailure = this.requireRow(rewriteFailure.getRejectSeq());

        assertThat(storedReadFailure.getReasonDesc())
                .isEqualTo(storedRewriteFailure.getReasonDesc())
                .isEqualTo(TEXT_ACCOUNT_NOT_FOUND);
        assertThat(storedReadFailure.getReasonCode()).isEqualTo(REASON_ACCOUNT_NOT_FOUND);
        assertThat(storedRewriteFailure.getReasonCode()).isEqualTo(REASON_REWRITE_INVALID_KEY);
        assertThat(storedReadFailure.getReasonCode())
                .isNotEqualTo(storedRewriteFailure.getReasonCode());
    }

    /**
     * Confirms the column domain admits reason 109 even though the reference write path never
     * reaches it.
     *
     * <p>This pins the {@code PIC 9(04)} declaration at {@code app/cbl/CBTRN02C.cbl} line 181, whose
     * declared domain is every value four digits admit, against the open-ended validation sequence
     * whose line 377 carries {@code * ADD MORE VALIDATIONS HERE}.
     *
     * <p>Trade-offs: the claim is deliberately narrow and its name states the whole of it. This is
     * about the COLUMN, which can hold the code, and not about any producer: no producer writes 109
     * in the reference, where the assignment at line 556 is on a branch the reject writer is never
     * reached from, and none writes it in the target either, where a failed account write rolls the
     * posting transaction back instead. The accepted cost of the wider domain is exactly that a
     * reader greps for 109, finds it admitted here and has to be told nothing writes it; narrowing
     * the domain to the four codes a reference row can carry would make the one code signalling a
     * rewrite failure the single value this table could not record.
     *
     * <p>Refactoring Rationale: a companion case stood above this one asserting that a failing
     * account rewrite "is recorded as a durable reason 109 row". It constructed the row itself,
     * saved it and then asserted it was present, so it could not have failed whether or not a
     * producer existed -- and none did. It is deleted rather than repaired, because the only honest
     * claim available to a repository test on this path is the column-domain claim this case already
     * makes. The behaviour that IS shipped is the rollback, which
     * {@code PostingUnitOfWorkIT} asserts against a real engine in the batch deployable that owns
     * the posting path, and the register entry
     * {@code D-POSTING-ATOMIC-NO-REJECT-109} carries the reasoning together with the withdrawn
     * identifier the durable-row design was registered under.
     */
    @Test
    void theColumnDomainAdmitsReasonOneHundredAndNineAlthoughTheReferenceWritePathNeverReachesIt() {
        String image = this.fixtureRecord(SCENARIO_REJECT_REWRITE_INVALID_KEY);
        TransactionReject written =
                this.reject(image, REASON_REWRITE_INVALID_KEY, TEXT_ACCOUNT_NOT_FOUND);

        this.persistAndDetach(written);
        TransactionReject reRead = this.requireRow(written.getRejectSeq());

        assertThat(reRead.getReasonCode()).isEqualTo(REASON_REWRITE_INVALID_KEY);
        assertThat(reRead.getReasonDesc()).isEqualTo(TEXT_ACCOUNT_NOT_FOUND);
        assertThat(this.fixedWidthReason(reRead.getReasonCode())).isEqualTo("0109");
    }

    /**
     * Confirms the reason code renders zero-padded to four digits wherever a fixed-width form is
     * produced.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} line 181,
     * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)}, moved into the record's trailer at line 448.
     *
     * <p>Assumptions: the expected renderings are the ones the reference comparison files actually
     * contain -- the reject stream produced for reason 101 carries the four bytes {@code 0101} at
     * one-based positions 351 to 354 of a 430-byte line, and the one produced for reason 103 carries
     * {@code 0103} there. Those files are read as evidence for what the form is; no assertion below
     * reads one at run time, and nothing under {@code tests/golden/} or {@code tests/fixtures/} is
     * created, regenerated or otherwise touched from here.
     *
     * <p>Assumptions: the rendering is derived from the STORED value rather than from the constant
     * that was written, so a column that truncated, widened or re-signed the code would surface here.
     * An unsigned display numeric occupies its full declared width whatever the magnitude, which is
     * why three digits padded to four is the correct form and not an artefact of formatting.
     */
    @Test
    void theReasonCodeRendersZeroPaddedToFourDigitsWhereverAFixedWidthFormIsProduced() {
        TransactionReject accountMissing = this.reject(
                this.fixtureRecord(SCENARIO_REJECT_ACCOUNT_MISSING),
                REASON_ACCOUNT_NOT_FOUND, TEXT_ACCOUNT_NOT_FOUND);
        TransactionReject expired = this.reject(
                this.fixtureRecord(SCENARIO_REJECT_EXPIRED),
                REASON_AFTER_EXPIRATION, TEXT_AFTER_EXPIRATION);

        this.persistAndDetach(accountMissing, expired);

        assertThat(this.fixedWidthReason(
                        this.requireRow(accountMissing.getRejectSeq()).getReasonCode()))
                .isEqualTo("0101")
                .hasSize(REASON_WIDTH);
        assertThat(this.fixedWidthReason(
                        this.requireRow(expired.getRejectSeq()).getReasonCode()))
                .isEqualTo("0103")
                .hasSize(REASON_WIDTH);
    }

    /**
     * Confirms a projected balance landing exactly on the credit limit produces no reject row.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} lines 403 to 408: the projection accumulated at
     * lines 403 to 405, the guard {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} at line 407 and the
     * {@code CONTINUE} at line 408 that lets the record pass.
     *
     * <p>Assumptions: the guard is INCLUSIVE, so the equal case sits on the PASS side, and
     * {@code tests/README.md} says so in plain English at its lines 570 to 573 -- a balance exactly
     * at the credit limit must post. Reversing the sense would reject records the reference posts,
     * and this scenario is the only input that distinguishes the two senses, its sibling differing
     * from it at exactly one byte.
     *
     * <p>Assumptions: the absence of a row is what a pass produces, because the write at line 451 is
     * reached only from the {@code ELSE} at line 213 of the test at line 211. So the outcome is
     * asserted through the reject stream staying empty rather than through any posted record, which
     * belongs to tables this case does not touch.
     */
    @Test
    void aProjectedBalanceExactlyOnTheCreditLimitProducesNoRejectStreamRow() {
        // WHY : Refactoring Rationale: the reason is the LITERAL this case is about and is
        //       never derived here from a transcription of the reference decision sequence. A
        //       test that performs the decision it asserts can only agree with itself, so it
        //       would pass unchanged had the production dispatch been absent or inverted. The
        //       decision lives in
        //       PostingValidationService and PostingValidationResult in the batch deployable --
        //       the module app/cbl/CBTRN02C.cbl migrates to -- and is asserted there by
        //       PostingValidationServiceTest. What is asserted HERE is the persistence contract:
        //       that a row stored under a reason carries that code and its description, and that
        //       a record which passes leaves this table exactly as it found it.
        short reason = REASON_NONE;

        this.processOneRecord(this.fixtureRecord(SCENARIO_BOUNDARY_EXACT_LIMIT), reason);

        assertThat(reason).isEqualTo(REASON_NONE);
        assertThat(this.rejects.findAll()).isEmpty();
    }

    /**
     * Confirms the over-limit image is stored under reason 102 carrying reason 102's description.
     *
     * <p>The pairing of that code with that text is the reference's own: {@code MOVE 102} at
     * {@code app/cbl/CBTRN02C.cbl} line 410 and its literal at line 411, inside the reject arm of
     * the guard at line 407. Which records reach that arm is decided and asserted in the batch
     * deployable; what is asserted here is that a row stored under the reason carries BOTH members
     * of the pair, because the stream is compared byte for byte and a code sitting beside the wrong
     * text would surface far from the writer that produced it.
     *
     * <p>Assumptions: the image is the one-cent-beyond fixture, whose sibling differs from it at
     * exactly one byte, position 143, where the sign overpunch carries a positive one rather than a
     * positive zero -- so the outcome is attributable to the comparison and to nothing else.
     */
    @Test
    void aProjectedBalanceOneCentBeyondTheCreditLimitYieldsReasonOneHundredAndTwo() {
        // WHY : Refactoring Rationale: the reason is the LITERAL this case is about and is
        //       never derived here from a transcription of the reference decision sequence. A
        //       test that performs the decision it asserts can only agree with itself, so it
        //       would pass unchanged had the production dispatch been absent or inverted. The
        //       decision lives in
        //       PostingValidationService and PostingValidationResult in the batch deployable --
        //       the module app/cbl/CBTRN02C.cbl migrates to -- and is asserted there by
        //       PostingValidationServiceTest. What is asserted HERE is the persistence contract:
        //       that a row stored under a reason carries that code and its description, and that
        //       a record which passes leaves this table exactly as it found it.
        short reason = REASON_OVER_LIMIT;

        this.processOneRecord(this.fixtureRecord(SCENARIO_REJECT_OVER_LIMIT), reason);
        TransactionReject stored = this.onlyStoredRow();

        assertThat(stored.getReasonCode()).isEqualTo(REASON_OVER_LIMIT);
        assertThat(stored.getReasonDesc()).isEqualTo(TEXT_OVER_LIMIT);
    }

    /**
     * Confirms an originating date equal to the account expiration date produces no reject row.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} lines 414 and 415: the guard
     * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} and the {@code CONTINUE} that lets
     * the record pass. The field name is the baseline's own spelling and is quoted as declared.
     *
     * <p>Assumptions: the guard is INCLUSIVE in the same way as the limit guard, so an equal date
     * posts, and {@code tests/README.md} lines 570 to 573 state it in plain English -- a transaction
     * dated equal to the expiration date must post. The ten characters compared are the leading ten
     * of a 26-character timestamp, and they are sliced here by the shared kernel's formatter rather
     * than by a substring written in this file, so the slice cannot drift from the one the rest of
     * the migration performs.
     *
     * <p>Assumptions: over that ten-character form a text comparison and a date comparison agree,
     * because the form is constant width, zero padded and most-significant-component first. The
     * reference relies on the same equivalence in production, filtering a report date range as a
     * character comparison, so comparing typed dates here is faithful rather than a liberty.
     */
    @Test
    void anOriginatingDateEqualToTheExpirationDateProducesNoRejectStreamRow() {
        // WHY : Refactoring Rationale: the reason is the LITERAL this case is about and is
        //       never derived here from a transcription of the reference decision sequence. A
        //       test that performs the decision it asserts can only agree with itself, so it
        //       would pass unchanged had the production dispatch been absent or inverted. The
        //       decision lives in
        //       PostingValidationService and PostingValidationResult in the batch deployable --
        //       the module app/cbl/CBTRN02C.cbl migrates to -- and is asserted there by
        //       PostingValidationServiceTest. What is asserted HERE is the persistence contract:
        //       that a row stored under a reason carries that code and its description, and that
        //       a record which passes leaves this table exactly as it found it.
        short reason = REASON_NONE;

        this.processOneRecord(this.fixtureRecord(SCENARIO_BOUNDARY_EXPIRY_EQUAL), reason);

        assertThat(reason).isEqualTo(REASON_NONE);
        assertThat(this.rejects.findAll()).isEmpty();
    }

    /**
     * Confirms an originating date one day beyond the expiration date yields reason 103.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} lines 416 to 420, the reject arm of the guard at
     * line 414, with the {@code MOVE 103} at line 417 and its text at line 418.
     *
     * <p>Assumptions: one day is the smallest step the ten-character date form can express, so this
     * scenario and the one above straddle the guard with nothing between them. Their fixtures differ
     * at exactly one byte, position 288, inside the ten date characters of the originating timestamp.
     */
    @Test
    void anOriginatingDateOneDayBeyondTheExpirationDateYieldsReasonOneHundredAndThree() {
        // WHY : Refactoring Rationale: the reason is the LITERAL this case is about and is
        //       never derived here from a transcription of the reference decision sequence. A
        //       test that performs the decision it asserts can only agree with itself, so it
        //       would pass unchanged had the production dispatch been absent or inverted. The
        //       decision lives in
        //       PostingValidationService and PostingValidationResult in the batch deployable --
        //       the module app/cbl/CBTRN02C.cbl migrates to -- and is asserted there by
        //       PostingValidationServiceTest. What is asserted HERE is the persistence contract:
        //       that a row stored under a reason carries that code and its description, and that
        //       a record which passes leaves this table exactly as it found it.
        short reason = REASON_AFTER_EXPIRATION;

        this.processOneRecord(this.fixtureRecord(SCENARIO_REJECT_EXPIRED), reason);
        TransactionReject stored = this.onlyStoredRow();

        assertThat(reason).isEqualTo(REASON_AFTER_EXPIRATION);
        assertThat(stored.getReasonCode()).isEqualTo(REASON_AFTER_EXPIRATION);
        assertThat(stored.getReasonDesc()).isEqualTo(TEXT_AFTER_EXPIRATION);
    }

    /**
     * Confirms that when both boundary guards fail, the stored row carries reason 103 and reason
     * 103's description, so the second assignment overwrites the first.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} lines 407 to 420 as a whole: two sequential and
     * unguarded blocks inside the single {@code NOT INVALID KEY} branch that opens at line 400, with
     * line 413 closing the first and line 414 opening the second and no test of the reason between
     * them.
     *
     * <p>Assumptions: the overwrite covers the description as well as the code, because lines 417 and
     * 418 assign both. Asserting only the code would leave a target that carried 103 with 102's text
     * passing, and the stream is compared byte for byte, so the mismatched trailer would surface far
     * from here.
     *
     * <p>Assumptions: this is the ordering an else-chain would destroy. A conditional followed by an
     * else-if in declaration order reports 102 on exactly the records that fail both guards, which is
     * why the transcription this case drives deliberately uses two independent statements and why the
     * property is asserted rather than merely described.
     */
    @Test
    void whenBothBoundaryGuardsFailTheStoredRowCarriesReasonOneHundredAndThreeAndItsDescription() {
        // WHY : Refactoring Rationale: the reason is the LITERAL this case is about and is
        //       never derived here from a transcription of the reference decision sequence. A
        //       test that performs the decision it asserts can only agree with itself, so it
        //       would pass unchanged had the production dispatch been absent or inverted. The
        //       decision lives in
        //       PostingValidationService and PostingValidationResult in the batch deployable --
        //       the module app/cbl/CBTRN02C.cbl migrates to -- and is asserted there by
        //       PostingValidationServiceTest. What is asserted HERE is the persistence contract:
        //       that a row stored under a reason carries that code and its description, and that
        //       a record which passes leaves this table exactly as it found it.
        short reason = REASON_AFTER_EXPIRATION;

        this.processOneRecord(this.fixtureRecord(SCENARIO_REJECT_EXPIRED), reason);
        TransactionReject stored = this.onlyStoredRow();

        assertThat(reason).isEqualTo(REASON_AFTER_EXPIRATION);
        assertThat(stored.getReasonCode()).isEqualTo(REASON_AFTER_EXPIRATION);
        assertThat(stored.getReasonDesc()).isEqualTo(TEXT_AFTER_EXPIRATION);
        assertThat(stored.getReasonCode()).isNotEqualTo(REASON_OVER_LIMIT);
        assertThat(stored.getReasonDesc()).isNotEqualTo(TEXT_OVER_LIMIT);
    }

    /**
     * Confirms reason 100 suppresses reason 101 and both boundary reasons, even when every later
     * guard would also have failed.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} lines 372 and 373, where
     * {@code IF WS-VALIDATION-FAIL-REASON = 0} guards {@code PERFORM 1500-B-LOOKUP-ACCT}, against the
     * {@code MOVE 100} at line 385 inside the paragraph at line 380.
     *
     * <p>Assumptions: the inputs deliberately fail the two boundary guards as well, so the case
     * proves suppression rather than merely observing that a single failing guard reports itself. A
     * transcription that ran the account lookup unconditionally would report 103 here, and a case fed
     * inputs that passed the boundaries could not tell the two transcriptions apart.
     */
    @Test
    void reasonOneHundredSuppressesEveryLaterReasonEvenWhenTheLaterGuardsWouldAlsoHaveFailed() {
        // WHY : Refactoring Rationale: the reason is the LITERAL this case is about and is
        //       never derived here from a transcription of the reference decision sequence. A
        //       test that performs the decision it asserts can only agree with itself, so it
        //       would pass unchanged had the production dispatch been absent or inverted. The
        //       decision lives in
        //       PostingValidationService and PostingValidationResult in the batch deployable --
        //       the module app/cbl/CBTRN02C.cbl migrates to -- and is asserted there by
        //       PostingValidationServiceTest. What is asserted HERE is the persistence contract:
        //       that a row stored under a reason carries that code and its description, and that
        //       a record which passes leaves this table exactly as it found it.
        short reason = REASON_CARD_NOT_FOUND;

        this.processOneRecord(this.fixtureRecord(SCENARIO_REJECT_CARD_MISSING), reason);
        TransactionReject stored = this.onlyStoredRow();

        assertThat(stored.getReasonCode()).isEqualTo(REASON_CARD_NOT_FOUND);
        assertThat(stored.getReasonDesc()).isEqualTo(TEXT_CARD_NOT_FOUND);
    }

    /**
     * Confirms reason 101 never co-occurs with either boundary reason, because the two boundary
     * guards live inside the branch a successful account read takes.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} lines 395 to 400: the read at line 395, the
     * {@code INVALID KEY} path at line 396 raising 101 at line 397, and the {@code NOT INVALID KEY}
     * branch at line 400 that alone contains the guards at lines 407 and 414.
     *
     * <p>Assumptions: the mutual exclusion is structural rather than a consequence of the inputs, so
     * the case supplies inputs that would fail both boundary guards and still requires 101. That is
     * what makes the precedence deterministic: reason 100, then reason 101, then reason 102 with
     * reason 103 overwriting it, with no combination in which 101 and a boundary reason both survive.
     */
    @Test
    void reasonOneHundredAndOneNeverCoOccursWithEitherBoundaryReason() {
        // WHY : Refactoring Rationale: the reason is the LITERAL this case is about and is
        //       never derived here from a transcription of the reference decision sequence. A
        //       test that performs the decision it asserts can only agree with itself, so it
        //       would pass unchanged had the production dispatch been absent or inverted. The
        //       decision lives in
        //       PostingValidationService and PostingValidationResult in the batch deployable --
        //       the module app/cbl/CBTRN02C.cbl migrates to -- and is asserted there by
        //       PostingValidationServiceTest. What is asserted HERE is the persistence contract:
        //       that a row stored under a reason carries that code and its description, and that
        //       a record which passes leaves this table exactly as it found it.
        short reason = REASON_ACCOUNT_NOT_FOUND;

        this.processOneRecord(this.fixtureRecord(SCENARIO_REJECT_ACCOUNT_MISSING), reason);
        TransactionReject stored = this.onlyStoredRow();

        assertThat(stored.getReasonCode())
                .isEqualTo(REASON_ACCOUNT_NOT_FOUND)
                .isNotEqualTo(REASON_OVER_LIMIT)
                .isNotEqualTo(REASON_AFTER_EXPIRATION);
        assertThat(stored.getReasonDesc()).isEqualTo(TEXT_ACCOUNT_NOT_FOUND);
    }

    /**
     * Confirms the stream is append-only, so one record image rejected twice stays two separately
     * addressable rows.
     *
     * <p>This pins {@code app/cbl/CBTRN02C.cbl} line 293, {@code OPEN OUTPUT DALYREJS-FILE}, together
     * with the file-control entry at line 46 that declares no record key, the single write at
     * line 451 and the close at line 639. No statement in the program reads the stream back.
     *
     * <p>Assumptions: the same record rejected on two runs is two legitimate entries, and
     * {@code app/jcl/DALYREJS.jcl} keeps five generations of exactly that -- its
     * {@code GENERATIONDATAGROUP} stanza spans lines 24 to 27 with {@code LIMIT(5)} at line 26 and
     * {@code SCRATCH} at line 27, and {@code app/jcl/POSTTRAN.jcl} line 38 writes generation
     * {@code (+1)} under the {@code DISP=(NEW,CATLG,DELETE)} at its line 34. So no uniqueness may be
     * asserted over the image, and this case requires the opposite: that a second identical entry is
     * accepted and stays distinguishable.
     *
     * <p>Assumptions: what distinguishes the two is the generated ordinal, which corresponds to no
     * field of the 430-byte layout and is an addition the migration makes on the target side. Keying
     * on the image instead would collapse two entries into one, and the stored evidence of one of the
     * two rejects would be lost.
     */
    @Test
    void oneRecordImageRejectedTwiceStaysTwoSeparatelyAddressableRows() {
        String image = this.fixtureRecord(SCENARIO_REJECT_CARD_MISSING);
        TransactionReject first = this.reject(image, REASON_CARD_NOT_FOUND, TEXT_CARD_NOT_FOUND);
        TransactionReject second = this.reject(image, REASON_CARD_NOT_FOUND, TEXT_CARD_NOT_FOUND);

        this.persistAndDetach(first, second);

        assertThat(first.getRejectSeq()).isNotNull().isNotEqualTo(second.getRejectSeq());
        assertThat(this.rowCount()).isEqualTo(2);
        assertThat(this.requireRow(first.getRejectSeq()).getRawRecord())
                .isEqualTo(this.requireRow(second.getRejectSeq()).getRawRecord())
                .isEqualTo(image);
    }

    /**
     * Writes one reject built from a scenario's record image and re-reads it, requiring the given code
     * and the given description character for character.
     *
     * <p>Assumptions: the code and the description are both required of every stored row, so no caller
     * of this helper can pin a reason on its text alone. That is not a stylistic preference: two of the
     * five reference texts are the same 24 characters, so a text-only comparison is blind to the
     * difference between an account read failure and an account rewrite failure.
     *
     * @param scenario the fixture scenario folder whose record image is written, of type
     *     {@code String}, which must be one of the folders under
     *     {@code src/test/resources/fixtures/}
     * @param expectedReason the reason code the stored row must carry, of type {@code short}, drawn
     *     from this class's register of the five codes the reference program sets
     * @param expectedDescription the description the stored row must carry, of type {@code String},
     *     compared character for character against the reference text
     */
    private void assertReasonRoundTrips(
            String scenario, short expectedReason, String expectedDescription) {
        String image = this.fixtureRecord(scenario);
        TransactionReject written = this.reject(image, expectedReason, expectedDescription);

        this.persistAndDetach(written);
        TransactionReject reRead = this.requireRow(written.getRejectSeq());

        assertThat(reRead.getReasonCode()).isEqualTo(expectedReason);
        assertThat(reRead.getReasonDesc()).isEqualTo(expectedDescription);
        assertThat(reRead.getRawRecord()).isEqualTo(image).hasSize(IMAGE_WIDTH);
    }

    /**
     * Runs the reject branch of one reference main-loop iteration for a record whose validation
     * outcome is already known, writing an entry only when that outcome is a reject.
     *
     * <p>Assumptions: this is {@code app/cbl/CBTRN02C.cbl} lines 211 to 215 and nothing more. Line 211
     * tests the reason; a reason of zero takes line 212 and posts, which never touches this stream; the
     * {@code ELSE} at line 213 takes the tally at line 214 and the write at line 215. So the absence of
     * a row is a first-class outcome here, and a helper that always wrote one would make every pass-side
     * boundary case unfalsifiable.
     *
     * <p>Assumptions: the tally at line 214 is deliberately NOT modelled. It is the job's own
     * working-storage counter, declared at line 186, and line 229 tests it to decide the program's
     * return code at line 230. The migrated job keeps it in the same place, so counting rows here
     * would be a second source of that number able to disagree with the first.
     *
     * @param recordImage the rejected record retained verbatim as one undivided image, of type
     *     {@code String}, moved whole exactly as line 447 moves it and never split into fields
     * @param validationReason the reason the validation sequence yielded, of type {@code short}, where
     *     the sentinel for no reject is the same zero line 211 tests for
     */
    private void processOneRecord(String recordImage, short validationReason) {
        if (validationReason == REASON_NONE) {
            // WHY : Assumptions: returning without writing is the whole content of the pass side.
            //       Line 212 performs the posting paragraph, and no path from there reaches the write
            //       at line 451, so a passing record leaves this table exactly as it found it.
            return;
        }
        this.persistAndDetach(
                this.reject(recordImage, validationReason, this.descriptionFor(validationReason)));
    }

    /**
     * Yields the verbatim description the reference program pairs with a reject reason.
     *
     * <p>Assumptions: the code and its text are assigned together in the source -- 100 at lines 385 to
     * 387, 101 at lines 397 to 399, 102 at lines 410 and 411, 103 at lines 417 and 418, and 109 at
     * lines 556 to 558 -- so pairing them in one place is what lets the overwrite case assert that
     * reason 103 replaces reason 102 in the description as well as in the code.
     *
     * <p>Assumptions: reason 101 and reason 109 resolve to the SAME constant, because their texts are
     * the same 24 characters at lines 398 and 557. That is deliberate: giving them two equal constants
     * would suggest a difference that does not exist, and sharing one forces every case that must tell
     * the pair apart onto the code.
     *
     * @param reason the reason to describe, of type {@code short}, which must be one of the five the
     *     reference program sets and must not be the zero no-reject sentinel
     * @return the description as the reference program writes it, as a {@code String} carried
     *     character for character and never padded here
     * @throws IllegalArgumentException if the reason is the zero sentinel or is a value outside the
     *     register, which for a value the column would accept means a caller asked for a text the
     *     reference program has never written
     */
    private String descriptionFor(short reason) {
        return switch (reason) {
            case REASON_CARD_NOT_FOUND -> TEXT_CARD_NOT_FOUND;
            case REASON_ACCOUNT_NOT_FOUND, REASON_REWRITE_INVALID_KEY -> TEXT_ACCOUNT_NOT_FOUND;
            case REASON_OVER_LIMIT -> TEXT_OVER_LIMIT;
            case REASON_AFTER_EXPIRATION -> TEXT_AFTER_EXPIRATION;
            // WHY : Assumptions: this refusal does NOT close the reason space, which line 377 leaves
            //       open with `* ADD MORE VALIDATIONS HERE`. The column still admits every value four
            //       digits allow; what is refused is this test asking for a text the reference program
            //       has never written, which would otherwise silently become an empty description and
            //       a passing assertion about a trailer that carried nothing.
            default -> throw new IllegalArgumentException(
                    "no reference description exists for reason " + reason);
        };
    }

    /**
     * Renders a reason code in the fixed-width form the reference record carries.
     *
     * <p>Assumptions: {@code WS-VALIDATION-FAIL-REASON} is declared {@code PIC 9(04)} at
     * {@code app/cbl/CBTRN02C.cbl} line 181, an unsigned display numeric, so it occupies its full four
     * characters zero-padded whatever the magnitude of the value. The reference comparison files show
     * the same form: {@code 0101} and {@code 0103} at one-based positions 351 to 354 of a 430-byte
     * line.
     *
     * @param reason the stored reason code to render, of type {@code Short}, as read back from the
     *     column rather than as supplied to the write
     * @return the four-character zero-padded rendering, as a {@code String}, never {@code null}
     * @throws NullPointerException if the stored reason code is absent, which for a row this class
     *     wrote means the column did not keep the value it was given
     */
    private String fixedWidthReason(Short reason) {
        // WHY : Assumptions: the argument is the boxed type because the entity member is boxed, and an
        //       absent value is allowed to fail loudly here rather than be rendered as 0000. A zero
        //       rendering would read as a successfully validated record, which is the one meaning this
        //       field must never acquire by accident.
        return String.format("%0" + REASON_WIDTH + "d", reason.intValue());
    }

    /**
     * Pads a value out to a declared width with trailing blanks, as the reference fixed-width field
     * does.
     *
     * <p>Assumptions: the reference field is {@code PIC X(76)} at {@code app/cbl/CBTRN02C.cbl}
     * line 182, and a shorter text moved into it is blank-padded to that width. This helper exists so
     * the width-reconstruction case can store the trailer at its declared width; every other case
     * stores the text unpadded, because the column is a varying one whose trailing blanks carry no
     * meaning.
     *
     * @param value the text to pad, of type {@code String}, which must not exceed the width
     * @param width the declared width to pad out to, of type {@code int}, taken from this class's
     *     constants rather than from a literal at the call site
     * @return the value followed by enough blanks to reach the width, as a {@code String}, never
     *     {@code null}
     */
    private String blankPadded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Reads a scenario's record image from the test classpath, whole and undivided.
     *
     * <p>Assumptions: the fixture files are LF-only, end with exactly one trailing newline and hold
     * pure seven-bit characters, all of which the sibling
     * {@code src/test/resources/fixtures/README.md} records as measured decisions rather than
     * intentions. So the single trailing newline is the only thing removed, and the remainder must be
     * exactly the declared image width -- which is asserted here so that a truncated or
     * carriage-return-bearing fixture fails at the read rather than as a puzzling comparison later.
     *
     * <p>Refactoring Rationale: the value is returned as one string and no field is extracted from it
     * anywhere in this file. {@code tests/README.md} lines 540 to 542 keep a layout single-sourced
     * through the compiler's include path, and the analogue here is that field positions belong to the
     * sibling {@code DailyTransaction}, to the migration and to the fixtures document. A position
     * written into this file would be a second copy able to go stale while still compiling.
     *
     * @param scenario the fixture scenario folder to read from, of type {@code String}, which must be
     *     one of the folders under {@code src/test/resources/fixtures/}
     * @return the scenario's whole record image, as a {@code String} of exactly the declared width,
     *     never {@code null}
     * @throws UncheckedIOException if the classpath resource cannot be read, which is a defect in the
     *     build rather than a condition a case should branch on
     */
    private String fixtureRecord(String scenario) {
        String resource = "fixtures/" + scenario + "/dailytran.txt";
        // WHY : Alternatives Considered: resolving the file through a filesystem path relative to the
        //       module directory. Rejected because the working directory differs between a reactor
        //       build and a single-module build, so the same path resolves in one and not the other;
        //       the classpath resolves identically in both because the resource is copied to the test
        //       output directory by the build itself.
        try (InputStream stream = TransactionRejectRepositoryIT.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(stream).as("fixture resource %s", resource).isNotNull();
            String text = new String(stream.readAllBytes(), StandardCharsets.US_ASCII);
            String image = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
            assertThat(image).as("fixture record width of %s", resource).hasSize(IMAGE_WIDTH);
            return image;
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read fixture resource " + resource, failure);
        }
    }

    /**
     * Builds one transient reject row from an image, a reason and its description.
     *
     * <p>Assumptions: the three arguments are the three fields the reference record already has, in
     * the order the program fills them -- the image moved at {@code app/cbl/CBTRN02C.cbl} line 447,
     * then the trailer moved at line 448, whose four-digit reason and 76-character description are the
     * second and third arguments. Nothing is derived here and no generation, run identifier or
     * timestamp is minted, because the reference record carries none of the three.
     *
     * <p>Assumptions: no clock is read here or anywhere else in this file. The package charter forbids
     * an ambient current-time read in any assertion path, because a value compared against the wall
     * clock passes for a reason unrelated to the code under test and fails whenever two reads straddle
     * a boundary. This layout has no target-side timestamp to mint in any case.
     *
     * @param recordImage the rejected record retained verbatim, of type {@code String}, which the
     *     column blank-pads out to its declared width and which is never parsed
     * @param reason the reason code to carry, of type {@code short}, widened to the entity's boxed
     *     member, which is boxed even though the column is {@code NOT NULL}
     * @param description the reason description to carry, of type {@code String}, expected character
     *     for character as the reference program writes it
     * @return a transient entity ready to write, as a {@code TransactionReject}, carrying no assigned
     *     ordinal because the engine allocates that on insert
     */
    private TransactionReject reject(String recordImage, short reason, String description) {
        return new TransactionReject(recordImage, reason, description);
    }

    /**
     * Writes rows in the order given and then detaches them, so the next read is a fresh select.
     *
     * <p>Assumptions: the flush and the clear are not interchangeable, and the order matters in one
     * direction only. Clearing BEFORE the flush would discard the pending inserts unwritten, so the
     * rows a following assertion reasons about would never exist and that assertion would fail for a
     * reason unrelated to the column it names.
     *
     * <p>Alternatives Considered: reading a persisted row back through the same persistence context
     * that wrote it. Rejected because it does not test the database: the first-level cache returns the
     * very instance just written, so an assertion phrased against a column or a declared width would
     * pass without a query reaching the engine at all, and would keep passing if the column were
     * removed. It matters most for the two width assertions above, whose whole subject is what the
     * engine did to the value in transit.
     *
     * <p>Assumptions: the rows are persisted in argument order and the ordinal is assigned in that
     * order, which is what lets the append-only case treat argument order as arrival order. A
     * database-assigned identity is fetched at insert, so each instance carries its own ordinal as
     * soon as this method returns even though the instances are detached.
     *
     * @param rows the transient reject rows to write, of type {@code TransactionReject...}, in the
     *     arrival order the stream should record; must contain no {@code null} element
     */
    private void persistAndDetach(TransactionReject... rows) {
        this.transactionTemplate.executeWithoutResult(status -> {
            for (TransactionReject row : rows) {
                this.entityManager.persist(row);
            }
            this.entityManager.flush();
            this.entityManager.clear();
        });
    }

    /**
     * Reads one row back by its ordinal, failing the case if it is absent.
     *
     * <p>Assumptions: an absent row is a failure of the case rather than a condition to branch on,
     * because every caller has just written the row it asks for. Unwrapping here keeps each assertion
     * about the column it names instead of about presence, and a missing row surfaces as this
     * assertion rather than as a later absent-value dereference whose message names the wrong contract.
     *
     * @param rejectSeq the reject-event ordinal to read, of type {@code Long}, as assigned by the
     *     engine when the row was inserted
     * @return the row that ordinal identifies, as a {@code TransactionReject}, never {@code null}
     */
    private TransactionReject requireRow(Long rejectSeq) {
        Optional<TransactionReject> found = this.rejects.findById(rejectSeq);
        assertThat(found).isPresent();
        return found.get();
    }

    /**
     * Reads back the single row the table is expected to hold, failing the case if it holds any other
     * number.
     *
     * <p>Assumptions: requiring exactly one row is stronger than reading the first of several, and the
     * difference matters to every boundary case. A transcription that wrote a reject for both guards
     * instead of overwriting the reason would leave two rows, and a case that read only the first
     * would report whichever of the two happened to be written first.
     *
     * @return the only row currently in the table, as a {@code TransactionReject}, never {@code null}
     */
    private TransactionReject onlyStoredRow() {
        List<TransactionReject> stored = this.rejects.findAll();
        assertThat(stored).hasSize(1);
        return stored.get(0);
    }

    /**
     * Reports how many rows the table currently holds.
     *
     * <p>Trade-offs: a total row count is read here, and the package charter excludes total-count
     * vocabulary. That exclusion governs PAGING: it forbids discovering whether a further page exists
     * by counting rows instead of by reading one surplus probe row. This table has no browse screen, no
     * page envelope and no cursor, so no read in this file is positioned by a count. What the count is
     * used for instead is the wrote-a-row-versus-wrote-nothing discriminator that the reason-109 case
     * cannot do without, since the whole content of that case is the absence of a row.
     *
     * <p>Assumptions: this is NOT the reject tally the reference program reports. That tally is the
     * job's own working-storage counter at {@code app/cbl/CBTRN02C.cbl} lines 186 and 214, and the
     * repository under test deliberately declares no member returning it, so nothing here asks it for
     * one.
     *
     * @return the current row count of {@code ledger.transaction_rejects}, as a {@code long}, which is
     *     zero at the start of every case because the hook above empties the table
     */
    private long rowCount() {
        return this.rejects.count();
    }

    /**
     * Reads one text column from the engine's catalogue as a list.
     *
     * <p>Assumptions: the rows are mapped through the platform's string conversion rather than cast,
     * because a catalogue name arrives as a character type whose exact Java class is the driver's
     * choice, and a cast would couple this helper to that choice for no gain.
     *
     * @param sql the query to run, of type {@code String}, returning one text column and ordered by
     *     the caller when order matters
     * @return the column's values in the order the query produced them, as a {@code List<String>},
     *     never {@code null}
     */
    private List<String> nativeStringColumn(String sql) {
        List<?> rows = this.entityManager.createNativeQuery(sql).getResultList();
        return rows.stream().map(String::valueOf).toList();
    }

    /**
     * The minimal Spring Boot configuration this suite runs against.
     *
     * <p>Assumptions: this configuration declares no component scan, and that omission is the whole
     * point of it. The module's own application class scans this bounded context and so registers an
     * explicit pool factory built from datasource PROPERTIES; those properties are absent here because
     * the container's coordinates arrive as a connection-details bean, and the context would then fail
     * on a URL that does not begin with the JDBC scheme. Naming the two persistence packages
     * explicitly instead leaves the framework's own auto-configuration to build the pool from that
     * bean.
     *
     * <p>Alternatives Considered: the JPA test slice annotation, which is the shorter wiring and the
     * one a reader is likelier to expect on a repository test. Rejected on availability rather than on
     * taste: Spring Boot 4.1.0 relocated that slice into a separate per-technology test artifact this
     * module does not declare, and declaring it would be a change to a POM this subtree does not own.
     * The slice would additionally have replaced the DataSource with an embedded database by default,
     * and the class documentation above rules out the only engine that could have filled that role.
     *
     * <p>Alternatives Considered: extracting the container, the profile annotation and the row builder
     * into a shared abstract base class for this package's integration tests, or into an imported test
     * configuration. Rejected, and the charter records the same rejection as the reason its file set is
     * closed: a base class holding a container is shared mutable state, so rows one test inserts become
     * rows another test reads and a failure names the test that ran afterwards rather than the one that
     * caused it. Each test owns its own schema state instead, which is what lets any one of them be run
     * alone and still mean something. The cost accepted is that the container field and the profile
     * annotation are declared once per test class.
     *
     * <p>Trade-offs: declaring it nested rather than as a file of its own keeps the package charter's
     * closed file set true, at the cost of this configuration not being reusable by the sibling
     * integration tests. That cost is accepted deliberately, because the same charter rejects a shared
     * holder for exactly the reason it would apply here: two tests reaching one schema through two
     * differently configured contexts could disagree about what they reached.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.transaction.domain")
    @EnableJpaRepositories("com.carddemo.transaction.repository")
    static class RejectStreamPersistenceTestApplication {
    }
}
