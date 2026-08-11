// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/service/ReferenceBatchUpdateServiceTest.java
// -----------------------------------------------------------------------------
// Purpose:
//      Unit cases over ReferenceBatchUpdateService, the migrated form of the
//      batch reference-update program. They pin the one property that program
//      is most often read backwards -- that a refused record is a soft reject
//      and the run carries on -- together with the 53-byte record geometry, the
//      five-way action dispatch, the verbatim display literals, the typed
//      integrity classification the baseline cannot express, and the bind
//      fidelity of the two host variables the update statement carries. Every
//      collaborator is a double; nothing here starts a container, a Spring
//      context or a job repository.
//
// WHY (non-obvious design decisions):
//  (1) Assumptions: THE PARAGRAPH NAMED 9999-ABEND DOES NOT ABEND, and this is
//      the single most consequential fact these cases rest on. Its whole body at
//      app/app-transaction-type-db2/cbl/COBTUPDT.cbl lines 230 to 233 is DISPLAY
//      WS-RETURN-MSG, then MOVE 4 TO RETURN-CODE, then EXIT. It issues no
//      STOP RUN, no GOBACK and no call to a Language Environment abend routine,
//      so control returns through 1003-TREAT-RECORD into the
//      PERFORM UNTIL LASTREC = 'Y' loop at lines 93 to 96, the next record is
//      read at line 95, and the run closes through 2001-CLOSE-STOP at line 234
//      reporting condition code 4. The program's one STOP RUN sits at line 99,
//      unreachable behind the EXIT at line 98, and is not on this path.
//      Therefore "refuse the record and carry on" is FAITHFUL behaviour and not
//      a departure from it, and no sentence in this file claims otherwise.
//  (2) Assumptions: two paragraphs in this corpus are named on the same word and
//      sit at OPPOSITE severity tiers, so the name is a trap rather than a clue.
//      The soft one is the paragraph described above. The other is
//      9999-ABEND-PROGRAM at app/cbl/CBACT04C.cbl lines 628 to 632, which
//      displays ABENDING PROGRAM, moves 0 into its timing argument and 999 into
//      its code argument, and then calls CEE3ABD at line 632, ending that run
//      outright. It is that second paragraph, not the first, that
//      tests/README.md line 422 names when illustrating its fatal tier. The
//      package charter beside this file records the same trap at package scope;
//      it is restated here in one sentence because a reader who opens only this
//      file still has to be warned before reading any assertion below.
//  (3) Assumptions: condition code 4 is a VALUE UNDER TEST here and never a
//      verdict. It is asserted as BatchUpdateResult#returnCode and is mapped
//      onto no JUnit outcome, no Surefire outcome and no Maven exit status. The
//      graded worst-wins rubric that gives 4 its warning meaning belongs to the
//      reference-only suite, which documents it at tests/README.md lines 415 to
//      423, and the charter beside this file quarantines it there. Every gate
//      acting on this class is binary: it passes or it fails.
//  (4) Refactoring Rationale: the migrated classification of a refused
//      constraint is TYPED where the baseline's is not, and the asymmetry is
//      per-program rather than general. The delete paragraph at lines 196 to 226
//      evaluates exactly three arms -- SQLCODE zero at line 208, +100 at line
//      210, and less than zero at line 216 -- and a search of the whole program
//      for -532 returns no hit at all, so a referential refusal is
//      indistinguishable there from any other negative code. The insert
//      paragraph at lines 151 to 163 evaluates only two arms, zero and less than
//      zero, so a duplicate key is likewise indistinguishable. Its two sibling
//      screens do discriminate the referential case, at COTRTUPC.cbl line 1638
//      and COTRTLIC.cbl line 1914, which is why the gap is stated for this
//      program alone and not generalised across the three. The migrated form
//      reports SQLSTATE 23503 and 23505 as their own reasons, and the divergence
//      is registered in docs/architecture/cobol-to-service-traceability.md, a
//      document this file references and does not author.
//  (5) Assumptions: every baseline citation in this file is a PHYSICAL line
//      number, verified by reading the line at that address. It is never the
//      legacy sequence value the source carries in columns 73 to 80. The two
//      disagree in both programs cited: COBTUPDT.cbl line 72 prints 00592033,
//      and COTRTLIC.cbl line 1914 prints 191800, so a citation taken from the
//      printed field names the wrong line in exactly the regions quoted here.
//  (6) Trade-offs: this class overlaps a sibling in this package, deliberately
//      and within a bounded margin. ReferenceWriteBehaviourTest groups a section
//      on the maintenance batch that already asserts continuation after a single
//      refusal, the aggregation of the worst code, the five dispatch branches and
//      the two integrity states as distinct reasons. What is accepted is that a
//      handful of properties are asserted in two places. What it buys is that the
//      class named after the service under test is the one that carries the
//      program's primary evidence, and that this class asserts what the sibling
//      cannot reach: the declared record geometry including the two key operands,
//      refusal of a wrong-length record at the single-record entry point, an
//      unreadable source, continuation across all four refusal KINDS rather than
//      one, the independence of the aggregate from the per-record list, the
//      untyped-state fallthrough, the bind fidelity of both host variables, the
//      declared collaborator set, and the absence of chunk-oriented batch
//      machinery. The margin is bounded on purpose: no assertion here restates
//      the module-scope prohibition on inexact monetary or rate arithmetic, which
//      the charter reserves to a single module-scope class, because a prohibition
//      asserted twice can be satisfied once and reported as satisfied twice.
//  (7) Assumptions: the four rationale labels are TYPED here and never copied
//      out of tests/README.md. That file is the sole carrier of the non-breaking
//      hyphen in the reference-only trees, using it 106 times across 77 of its
//      lines, and its own list of the four categories at line 548 renders the
//      compromise label with that character together with a typographic dash.
//      Both are indistinguishable from their ASCII forms on screen while
//      behaving differently in a search, so a copied label becomes a token that
//      a search for the label cannot find. This file is pure ASCII, which puts
//      the hazard out of reach rather than relying on care. The singular
//      spellings that appear in the reference-only suite's configuration headers
//      denote these same four categories; that equivalence is declared here once
//      so nobody later rewrites one form into the other, it governs those
//      reference-only artifacts alone, and this file uses the plural
//      exclusively.
//  (8) Trade-offs: two cases read a private static member by reflection. That
//      couples them to a member name, which is accepted for one specific reason:
//      the record's key length and key offset are declared on the layout and are
//      invisible to every observable behaviour, because nothing in the migrated
//      path retrieves by key. A case restricted to the public surface could
//      therefore assert the three field boundaries but never the two key
//      operands, and the operands are exactly what a careless edit of the layout
//      would disturb. Reflection is available because no compilation unit in this
//      reactor declares a module descriptor and these cases sit in the member's
//      own package.
// =============================================================================

package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.repository.TransactionTypeRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the batch reference-update behaviours the baseline records and a later edit could smooth away.
 *
 * <p>Purpose: the cases below assert seven families of property. First, the declared geometry of the
 * 53-byte maintenance record, including the two key operands that no observable behaviour reveals, and
 * the refusal of a record whose length is wrong rather than a silent mis-decode. Second, that a refused
 * record is a soft reject and the run continues, asserted across all four kinds of refusal rather than
 * one, because a single-refusal case cannot show that the continuation is a property of the loop rather
 * than of one branch. Third, that the aggregate indicator and the per-record list are independent
 * carriers, proven with two runs whose aggregates agree while their lists differ. Fourth, that the
 * integrity states the baseline cannot tell apart are reported as separate reasons here, asserted
 * together with an unrelated state so that both halves of the contract are shown. Fifth, that the
 * display literals are carried character for character, including a trailing-blank run that is invisible
 * on screen. Sixth, that both host variables of the update statement keep the bind fidelity the baseline
 * gives them. Seventh, that the transcription reaches one table through one collaborator and carries no
 * chunk-oriented batch machinery.
 *
 * <p>Assumptions: the surface exercised is the one actually authored, read at authoring time rather than
 * assumed. {@link ReferenceBatchUpdateService} publishes two carriers of the same actions: the pair
 * {@link ReferenceBatchUpdateService#apply(InputStream)} and
 * {@link ReferenceBatchUpdateService#applyRecord(byte[])} for the baseline record stream, and a second
 * overload with its per-action companion for the published request body. The cases here exercise the
 * stream pair, because that pair is the transcription of the read loop and the dispatch. Its refusals
 * are values on the returned {@code RecordOutcome} rather than thrown exceptions, which is precisely the
 * property that makes continuation expressible.
 *
 * <p>Assumptions: the record contract is the one the fixture charter at
 * {@code services/reference-service/src/test/resources/fixtures/README.md} declares in its section 3.6,
 * whose offsets are 0-based, whose byte count is 1 plus 2 plus 50, and which records that this layout
 * drops no {@code FILLER} because it has none. That charter is named here and not restated, since a
 * second statement of an offset is a second thing to keep correct and the one that drifts is not
 * necessarily the one a reader consults.
 *
 * <p>Alternatives Considered: driving these cases through a Spring Batch job launcher, which is the
 * reflex for anything called a batch. Declined on evidence rather than preference. The program declares
 * {@code ORGANIZATION IS SEQUENTIAL} with {@code ACCESS MODE IS SEQUENTIAL} at lines 32 and 33 of
 * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} and performs a single
 * {@code READ ... NEXT RECORD} at line 101 inside the loop at lines 93 to 96, so there is no chunk
 * boundary, no commit interval and no restart point for a job repository to hold. This module declares
 * no batch starter either, a deliberate absence its own build file records, so a job launcher is not
 * merely unnecessary here but unavailable. A case written around one would fail to compile rather than
 * fail informatively, which is the worse of the two failures.
 *
 * <p>Alternatives Considered: asserting the refusal reasons through a database container, so that a real
 * constraint rather than a stubbed exception produced each state. Declined because the property under
 * test is this class's own classification of a state, not the database's production of one; the
 * container-backed half of that contract is owned by the repository package, whose integration classes
 * carry the {@code IT} suffix and are asserted at the {@code verify} phase. A case that could only run
 * where a container starts would not run on the builds where a smoothed-over classification is
 * introduced, which is when it is most needed.
 *
 * <p>Trade-offs: no case here asserts a status code, so no web slice is started and no exception-handling
 * advice is imported. The shared kernel's advice is the only one declared in this reactor and a slice
 * does not import it automatically, so a status-code assertion written without it would silently observe
 * the framework's default handling and pass while proving nothing. Declining the slice altogether avoids
 * that failure mode instead of guarding against it, and the routing and refusal-disclosure classes in the
 * sibling api package own the published-status contract.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause. The inapplicability is stated rather than passed over, because user-specified Rule 1
 * (Explainability) forbids at its line 39 a docstring that omits parameters, return values or purpose,
 * and a reader has to be able to tell a declared inapplicability from an oversight.
 */
@DisplayName("the reference batch update service")
class ReferenceBatchUpdateServiceTest {

    /** The action byte that selects the insert paragraph, from line 111 of the program. */
    private static final String ACTION_ADD = "A";

    /** The action byte that selects the update paragraph, from line 114 of the program. */
    private static final String ACTION_UPDATE = "U";

    /** The action byte that selects the delete paragraph, from line 117 of the program. */
    private static final String ACTION_DELETE = "D";

    /** The action byte that marks a program-recognised comment row, from line 120 of the program. */
    private static final String ACTION_COMMENT = "*";

    /** A seeded two-character type code, the first the reference seed file carries. */
    private static final String SEEDED_TYPE_CD = "01";

    /** The description the seed file stores against {@link #SEEDED_TYPE_CD}. */
    private static final String SEEDED_DESCRIPTION = "Purchase";

    /** A code no seeded row uses, for the paths that must report a miss. */
    private static final String ABSENT_TYPE_CD = "55";

    /** The state a database reports when a restricted foreign key refuses a removal. */
    private static final String STATE_FOREIGN_KEY = "23503";

    /** The state a database reports when a unique key refuses an insert. */
    private static final String STATE_UNIQUE = "23505";

    /**
     * A state outside the two classified above, standing in for any unrelated refusal.
     *
     * <p>Assumptions: the value is a real serialisation-failure state rather than an invented one, so the
     * case reads as a plausible production condition. Nothing about the assertion depends on which
     * unclassified state is used, only that it is not one of the two the classifier names.
     */
    private static final String STATE_UNRELATED = "40001";

    /**
     * A distinctive phrase carried on the driver-level exception and on nothing else.
     *
     * <p>Assumptions: its only purpose is to be searched for in the reported message. The baseline's three
     * error strings append the SQLCODE display field and no driver text, so a migrated message that
     * contained this marker would have grown a component the baseline never emitted, and a marker unique
     * in this file is what makes that detectable.
     */
    private static final String DRIVER_REASON = "carddemo-driver-reason-marker";

    /** The mocked type repository, the service's only persistence collaborator. */
    private TransactionTypeRepository types;

    /** The service under test, reconstructed per case so no stubbing leaks between them. */
    private ReferenceBatchUpdateService service;

    /**
     * Builds a fresh double and a fresh service before each case.
     *
     * <p>Assumptions: the service holds no mutable state of its own, so a shared instance would have been
     * safe; it is rebuilt anyway because the double it wraps is not, and rebuilding both together keeps
     * the two lifetimes identical rather than leaving one reader to work out that only one of them
     * resets.
     *
     * <p>It takes no parameter and returns no value.
     */
    @BeforeEach
    void setUp() {
        this.types = mock(TransactionTypeRepository.class);
        this.service = new ReferenceBatchUpdateService(this.types);
    }

    /**
     * Composes one maintenance record, right-padded with blanks to its declared length.
     *
     * <p>Assumptions: blank is the fill byte for all three fields, which the fixture charter's section 3.6
     * states and the program's own declarations at lines 72 to 77 carry as {@code VALUE SPACES} on each.
     * The padding is applied to the composed record rather than to the description alone so that a
     * caller may hand in a description of exactly 50 characters and receive it unaltered, which is what
     * the boundary probe needs.
     *
     * @param actionByte the single byte occupying offset 0, one of the four the dispatch names or any
     *     other byte to reach the invalid arm
     * @param typeCode the two bytes occupying offsets 1 and 2, passed through exactly as given so that a
     *     case may present an unpadded or non-numeric code
     * @param description the bytes from offset 3 onward, right-padded here if shorter than 50
     * @return a byte array of exactly {@link ReferenceBatchUpdateService#RECORD_LENGTH} bytes in
     *     US-ASCII, never {@code null}
     * @throws IllegalArgumentException if the three parts compose to more than the declared record
     *     length, which is a fault in the case rather than in the service and is surfaced immediately
     */
    private static byte[] maintenanceRecord(String actionByte, String typeCode, String description) {
        String composed = actionByte + typeCode + description;
        if (composed.length() > ReferenceBatchUpdateService.RECORD_LENGTH) {
            throw new IllegalArgumentException("a maintenance record cannot exceed "
                    + ReferenceBatchUpdateService.RECORD_LENGTH + " bytes but this one composed to "
                    + composed.length());
        }
        String padded = composed
                + " ".repeat(ReferenceBatchUpdateService.RECORD_LENGTH - composed.length());

        // WHY : Assumptions: US-ASCII is asserted rather than left to the platform default. The migrated
        //       codec decodes with a declared charset, and a default of any single-byte encoding would
        //       agree with it on these bytes while a multi-byte default would not, so naming the charset
        //       here removes a dependency on the machine the case happens to run on.
        return padded.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Joins whole records into one contiguous stream, with no separator between them.
     *
     * <p>Assumptions: the migrated reader consumes fixed-length records back to back, reading exactly
     * {@link ReferenceBatchUpdateService#RECORD_LENGTH} bytes at a time, so a separator of any kind would
     * shift every record after the first. That is why this joiner inserts nothing, and why the fixture
     * reader below strips the newline the stored files carry.
     *
     * @param records the records to concatenate in the order the run should see them, each already of the
     *     declared length
     * @return a stream positioned at the first byte of the first record, never {@code null}
     */
    private static InputStream maintenanceStream(byte[]... records) {
        int reclen = ReferenceBatchUpdateService.RECORD_LENGTH;
        byte[] joined = new byte[records.length * reclen];
        for (int index = 0; index < records.length; index++) {
            System.arraycopy(records[index], 0, joined, index * reclen, reclen);
        }
        return new ByteArrayInputStream(joined);
    }

    /**
     * Reads a stored fixture and returns its rows with the line terminators removed.
     *
     * <p>Assumptions: the fixture charter's section 5.6 fixes every record file as LF only with exactly one
     * trailing newline, so a file of N rows measures N times the record length plus N bytes. Both facts are
     * asserted here rather than trusted, because a reader that keeps the terminator pulls a 54th byte into
     * the first record and shifts every field after it -- and the resulting failure presents as a
     * field-offset fault rather than as a line-ending one, which sends a reader to the wrong file.
     *
     * @param scenario the fixture scenario directory beneath {@code fixtures/batch_reference_update}
     * @param expectedRecords the number of rows the file is required to hold, asserted before any slicing
     * @return the rows concatenated with no separator, ready for the reader under test, never {@code null}
     * @throws IOException if the fixture cannot be read to its end, which fails the case rather than
     *     letting it pass against an empty array
     */
    private static byte[] storedFixtureRecords(String scenario, int expectedRecords)
            throws IOException {
        int reclen = ReferenceBatchUpdateService.RECORD_LENGTH;
        String resource = "fixtures/batch_reference_update/" + scenario + "/trtype-update.txt";
        byte[] stored;
        try (InputStream source = ReferenceBatchUpdateServiceTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(source).as("the %s fixture must be on the test classpath", scenario).isNotNull();
            stored = source.readAllBytes();
        }

        assertThat(stored)
                .as("a %d-row 53-byte fixture measures %d bytes under section 5.6 of the fixture charter",
                        expectedRecords, expectedRecords * (reclen + 1))
                .hasSize(expectedRecords * (reclen + 1));

        byte[] contiguous = new byte[expectedRecords * reclen];
        for (int index = 0; index < expectedRecords; index++) {
            int rowStart = index * (reclen + 1);
            assertThat(stored[rowStart + reclen])
                    .as("row %d must terminate with a single LF and no carriage return", index + 1)
                    .isEqualTo((byte) '\n');
            System.arraycopy(stored, rowStart, contiguous, index * reclen, reclen);
        }
        return contiguous;
    }

    /**
     * Wraps a SQLSTATE in the integrity violation a provider would raise for it.
     *
     * <p>Assumptions: the state is carried on a {@link SQLException} in the cause chain rather than in the
     * wrapper's own text, because that is where the service reads it from -- it walks the chain for the one
     * interface that is part of the platform and reports the state it finds. A wrapper whose message merely
     * mentioned the digits would not be classified at all, so a case built that way would assert the
     * fallthrough while appearing to assert the classification.
     *
     * @param sqlState the five-character state the database reported
     * @return an integrity violation whose cause reports that state, never {@code null}
     */
    private static DataIntegrityViolationException integrityViolation(String sqlState) {
        return new DataIntegrityViolationException(
                "the constraint refused the statement", new SQLException(DRIVER_REASON, sqlState));
    }

    /**
     * Returns a stored row for the seeded code, matching the reference seed file.
     *
     * @return a transaction type carrying {@link #SEEDED_TYPE_CD} and {@link #SEEDED_DESCRIPTION}, never
     *     {@code null}
     */
    private static TransactionType seededType() {
        return new TransactionType(SEEDED_TYPE_CD, SEEDED_DESCRIPTION);
    }

    /**
     * Reads the layout the service declares for its input record.
     *
     * <p>Trade-offs: this reaches a private static member, which is the only way to observe the record's key
     * length and key offset. The reasoning is recorded on the class block above and is not repeated per
     * case.
     *
     * @return the declared record specification, never {@code null}
     * @throws ReflectiveOperationException if the member is renamed or retyped without these cases being
     *     updated, which fails them rather than letting them pass by omission
     */
    private static CopybookLayout.RecordSpec declaredLayout() throws ReflectiveOperationException {
        Field declared = ReferenceBatchUpdateService.class.getDeclaredField("MAINTENANCE_RECORD");
        declared.setAccessible(true);
        return (CopybookLayout.RecordSpec) declared.get(null);
    }

    /**
     * Collects the value of every static string constant the service declares.
     *
     * <p>Assumptions: gathering the whole set is what makes an absence assertable. A case naming one field
     * could only show that a particular constant does not hold a particular literal, whereas the question
     * being asked is whether the literal appears anywhere in the class at all.
     *
     * @return every static string value the class declares, in declaration order, never {@code null}
     * @throws ReflectiveOperationException if a declared static string cannot be read, which fails the case
     *     rather than silently shortening the set it checks
     */
    private static List<String> declaredStringConstants() throws ReflectiveOperationException {
        List<String> values = new ArrayList<>();
        for (Field declared : ReferenceBatchUpdateService.class.getDeclaredFields()) {
            if (Modifier.isStatic(declared.getModifiers()) && declared.getType() == String.class) {
                declared.setAccessible(true);
                values.add((String) declared.get(null));
            }
        }
        return values;
    }

    /**
     * An input stream that refuses every read, standing in for a source that cannot be consumed.
     *
     * <p>Alternatives Considered: an anonymous subclass declared inline at the one case that needs it. A
     * named nested class was chosen because the documentation audit governing this tree examines methods
     * inside anonymous classes as well, so the inline form would carry the same obligation with less room
     * to discharge it legibly.
     *
     * <p>Assumptions: overriding the single-byte read is sufficient to make a bulk read fail. The platform's
     * default bulk read performs its first single-byte read outside the block that swallows later ones, so
     * a refusal on the first byte propagates to the caller rather than being reported as a short read.
     */
    private static final class UnreadableStream extends InputStream {

        /** The text the refusal carries, so a case can identify the cause it produced. */
        private static final String REASON = "the maintenance source could not be read";

        /**
         * Refuses the read instead of returning a byte.
         *
         * @return never returns normally, because every call raises instead
         * @throws IOException always, which is the entire purpose of this stand-in
         */
        @Override
        public int read() throws IOException {
            throw new IOException(REASON);
        }
    }

    /**
     * Cases over the declared geometry of the 53-byte maintenance record and over what a malformed one does.
     *
     * <p>Assumptions: the record is program working storage rather than a copybook, declared at lines 71 to
     * 77 of {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} as a one-byte action, a two-byte type code
     * and a fifty-byte description. It carries no {@code FILLER} at all, so 1 plus 2 plus 50 accounts for
     * every byte and neither padding regime the fixture charter describes applies to it. That exhaustiveness
     * is asserted here rather than assumed, because it is the property that makes every offset below
     * derivable instead of merely stated.
     */
    @Nested
    @DisplayName("on the maintenance record layout")
    class OnTheMaintenanceRecordLayout {

        /**
         * The declared layout names 53 bytes, three contiguous text fields and no padding.
         *
         * <p>Assumptions: the two key operands are asserted here and nowhere else in this package. The
         * migrated path retrieves nothing by key -- it slices a record it was handed -- so a key length of 2
         * at offset 1 has no observable consequence whatever, and an edit that disturbed either operand
         * would leave every behavioural case in this file green. Asserting the declaration directly is what
         * closes that gap, and it also proves the two operands still designate the type code rather than
         * having drifted onto the action byte.
         *
         * <p>Assumptions: contiguity and exhaustiveness are asserted as arithmetic over the declared fields
         * rather than as three fixed numbers. A layout whose lengths still summed to 53 but whose starts had
         * shifted would satisfy a length check alone, so each field is required to begin exactly where its
         * predecessor ends and the last is required to end at the record length.
         *
         * <p>It takes no parameter and returns no value.
         *
         * @throws ReflectiveOperationException if the declared layout member cannot be read
         */
        @Test
        @DisplayName("declare 53 bytes as three contiguous text fields with no filler")
        void declareFiftyThreeBytesAsThreeContiguousTextFields() throws ReflectiveOperationException {
            CopybookLayout.RecordSpec layout = declaredLayout();

            assertThat(layout.name())
                    .as("the record is named at line 71 of the program")
                    .isEqualTo("WS-INPUT-REC");
            assertThat(layout.reclen())
                    .as("1 plus 2 plus 50 accounts for every byte, so nothing is left for padding")
                    .isEqualTo(53)
                    .isEqualTo(ReferenceBatchUpdateService.RECORD_LENGTH);
            assertThat(layout.keyLength())
                    .as("the retrieval key is the two-byte type code the delete statement binds at line 203")
                    .isEqualTo(2);
            assertThat(layout.keyOffset())
                    .as("that key begins after the one-byte action, so at 0-based offset 1")
                    .isEqualTo(1);

            List<CopybookLayout.FieldSpec> fields = layout.fields();
            assertThat(fields).as("lines 72, 74 and 76 declare three fields and no more").hasSize(3);
            assertThat(fields).allSatisfy(field -> assertThat(field.kind())
                    .as("all three are PIC X(n), so none of them is a numeric kind")
                    .isEqualTo(CopybookLayout.Kind.TEXT));
            assertThat(fields).extracting(CopybookLayout.FieldSpec::name).containsExactly(
                    "INPUT-REC-TYPE", "INPUT-REC-NUMBER", "INPUT-REC-DESC");
            assertThat(fields).extracting(CopybookLayout.FieldSpec::start).containsExactly(0, 1, 3);
            assertThat(fields).extracting(CopybookLayout.FieldSpec::length).containsExactly(1, 2, 50);

            // WHY : Assumptions: a name-based check is kept alongside the arithmetic because the codec drops
            //       a blank padding field only when the field is NAMED as filler AND the record is one the
            //       shared registry owns. This layout is built locally and is absent from that registry, so
            //       nothing here can be dropped; the name check records that the first condition fails too,
            //       which is why every one of the three keys is always present in a decoded record.
            assertThat(fields).extracting(CopybookLayout.FieldSpec::name)
                    .as("a filler-named field would introduce a droppable descriptor into a record with none")
                    .noneMatch(name -> String.valueOf(name).contains("FILLER"));

            int cursor = 0;
            for (CopybookLayout.FieldSpec field : fields) {
                assertThat(field.start())
                        .as("field %s must begin exactly where its predecessor ends", field.name())
                        .isEqualTo(cursor);
                cursor += field.length();
            }
            assertThat(cursor)
                    .as("the declared fields must account for the whole record, leaving no unmapped byte")
                    .isEqualTo(layout.reclen());
        }

        /**
         * Each field is read from its own byte range, proven with markers on the outermost bytes.
         *
         * <p>Assumptions: the probe is built so that a boundary off by one is visible in the assertion rather
         * than merely making it fail. The type code is two digits, the description opens on a third digit and
         * closes on a fourth, and the description is exactly 50 characters so the composer pads nothing. A
         * description starting at offset 2 would carry the second type digit and leave a one-character type
         * code; one starting at offset 4 would drop the leading marker. Both mis-readings therefore change
         * the reported values rather than only the outcome.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("read the action, the code and the description from offsets zero, one and three")
        void readEachFieldFromItsOwnByteRange() {
            String description = "9" + " ".repeat(48) + "7";
            assertThat(description).as("the probe must fill the description field exactly").hasSize(50);

            ReferenceBatchUpdateService.RecordOutcome outcome =
                    service.applyRecord(maintenanceRecord(ACTION_ADD, "42", description));

            assertThat(outcome.action())
                    .as("byte 0 alone selects the branch, so the action must be read from it")
                    .isEqualTo(ReferenceBatchUpdateService.RecordAction.ADD);
            assertThat(outcome.typeCode())
                    .as("bytes 1 and 2 are the whole code, neither one byte nor three")
                    .isEqualTo("42");

            // WHY : Assumptions: the trailing marker also proves the field is 50 bytes rather than fewer. The
            //       migrated path trims trailing blanks, so a shorter field would silently shed the marker
            //       and every blank behind it, and the bind would then be reported as a bare leading digit.
            verify(types).insertType("42", description);
        }

        /**
         * A record of the wrong length is refused, and the refusal names the widths involved.
         *
         * <p>Alternatives Considered: recovering the fields a short record does carry and reporting a partial
         * outcome. Declined because a plausible partial map hides a shifted field, and on a reference table
         * whose two-byte code is a primary key a shift turns one type into another rather than into anything
         * obviously wrong. Refusing on the length comparison gives up partial recovery and gains a failure
         * that names the expected and actual widths.
         *
         * <p>Assumptions: the check happens before the first slice, so no collaborator is reached. That is
         * asserted as well as the refusal, because a refusal raised after a write had already been issued
         * would leave the table altered by a record the run rejected.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("refuse a record one byte short and one byte long without touching the table")
        void refuseARecordOfTheWrongLength() {
            byte[] oneShort = new byte[ReferenceBatchUpdateService.RECORD_LENGTH - 1];
            byte[] oneLong = new byte[ReferenceBatchUpdateService.RECORD_LENGTH + 1];

            assertThatThrownBy(() -> service.applyRecord(oneShort))
                    .as("52 bytes cannot be sliced into 1 plus 2 plus 50")
                    .isInstanceOf(FixedWidthCodec.RecordLengthException.class)
                    .hasMessageContaining("WS-INPUT-REC")
                    .hasMessageContaining("53")
                    .hasMessageContaining("52");

            assertThatThrownBy(() -> service.applyRecord(oneLong))
                    .as("54 bytes is refused as squarely as 52, rather than being read and truncated")
                    .isInstanceOf(FixedWidthCodec.RecordLengthException.class)
                    .hasMessageContaining("53")
                    .hasMessageContaining("54");

            verifyNoInteractions(types);
        }

        /**
         * A source that cannot be read is reported as a stream failure carrying its cause.
         *
         * <p>Assumptions: this is the one condition on the stream path that is a genuine failure rather than a
         * per-record refusal, and the two are deliberately reported by different mechanisms. A record the
         * dispatch rejects becomes an outcome in the returned list, because the baseline reads the next record
         * after it; a source that cannot be read yields no record at all, so there is nothing for the loop to
         * carry on to and no outcome to report. Raising in that case rather than returning an empty result is
         * what stops an unreadable input being indistinguishable from an empty one.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("report an unreadable source as a stream failure rather than an empty run")
        void reportAnUnreadableSourceAsAStreamFailure() {
            assertThatThrownBy(() -> service.apply(new UnreadableStream()))
                    .isInstanceOf(ReferenceBatchUpdateService.MaintenanceStreamException.class)
                    .hasCauseInstanceOf(IOException.class);

            verifyNoInteractions(types);
        }
    }

    /**
     * Cases proving a refused record is a soft reject and the run carries on to the records behind it.
     *
     * <p>Assumptions: this is the section the whole class exists for, and the reading it rests on is the one
     * recorded at the head of this file: the paragraph named for termination at lines 230 to 233 of
     * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} displays a message, moves 4 into the return-code
     * register and exits, and control resumes in the loop at lines 93 to 96. Continuation is therefore what
     * the baseline does, and these cases assert fidelity rather than an improvement on it.
     */
    @Nested
    @DisplayName("on the continuation after a soft reject")
    class OnTheContinuationAfterASoftReject {

        /**
         * The stored soft-reject fixture is carried to its end, with the record behind the bad one applied.
         *
         * <p>Assumptions: the fixture is authored for exactly this reading and its own charter says so. Its
         * first row carries a lower-case action byte, which a COBOL {@code EVALUATE} compares byte for byte
         * and so does not match the upper-case literal at line 111, sending it to the {@code WHEN OTHER} arm
         * at line 122. Its second row is an entirely valid update. Using the stored bytes rather than
         * composing them here is what ties this assertion to the artifact a maintainer would edit, so a
         * fixture altered to remove the case fails this rather than passing unnoticed.
         *
         * <p>Trade-offs: this is the only case in the class that reads from the classpath, which makes it the
         * only one that can fail for a packaging reason rather than a behavioural one. That is accepted
         * because the stored fixture is the artifact the scenario is documented against, and the reader above
         * asserts the file's size and terminators first so a packaging fault reports as one.
         *
         * <p>It takes no parameter and returns no value.
         *
         * @throws IOException if the stored fixture cannot be read to its end
         */
        @Test
        @DisplayName("apply the valid record that follows an unrecognised action byte")
        void applyTheValidRecordFollowingAnUnrecognisedActionByte() throws IOException {
            when(types.findByTypeCd("02")).thenReturn(Optional.of(new TransactionType("02", "Payment")));

            ReferenceBatchUpdateService.BatchUpdateResult result = service.apply(
                    new ByteArrayInputStream(storedFixtureRecords("invalid_type_soft_reject", 2)));

            assertThat(result.processedCount())
                    .as("both rows are read, so the run does not stop on the first refusal")
                    .isEqualTo(2);
            assertThat(result.outcomes()).hasSize(2);

            ReferenceBatchUpdateService.RecordOutcome refused = result.outcomes().get(0);
            assertThat(refused.action())
                    .isEqualTo(ReferenceBatchUpdateService.RecordAction.INVALID);
            assertThat(refused.succeeded()).isFalse();
            assertThat(refused.rejectReason())
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.INVALID_ACTION_CODE);
            assertThat(refused.message())
                    .as("the text is built at line 124 and carried verbatim under rule T8")
                    .isEqualTo("ERROR: TYPE NOT VALID");
            assertThat(refused.typeCode())
                    .as("the refused row is identified, so the report says WHICH record failed")
                    .isEqualTo(SEEDED_TYPE_CD);

            ReferenceBatchUpdateService.RecordOutcome applied = result.outcomes().get(1);
            assertThat(applied.action()).isEqualTo(ReferenceBatchUpdateService.RecordAction.UPDATE);
            assertThat(applied.succeeded()).isTrue();
            assertThat(applied.typeCode()).isEqualTo("02");
            assertThat(applied.message()).isEqualTo("RECORD UPDATED SUCCESSFULLY");

            // WHY : Assumptions: the write is verified as well as the outcome. An implementation that
            //       reported the second record as applied while skipping its statement would satisfy every
            //       assertion above, and that is precisely the shape a stop-on-first-refusal change takes
            //       when its result object is adjusted afterwards to keep the reports looking right.
            verify(types).saveAndFlush(any(TransactionType.class));

            assertThat(result.anyRejected()).isTrue();
            assertThat(result.returnCode())
                    .as("line 232 moves 4 into the return-code register, and that value lands here alone")
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_SOFT_WARN);
        }

        /**
         * Every one of the four kinds of refusal is followed by a record that still applies.
         *
         * <p>Alternatives Considered: asserting continuation once, after a single refusal, which is what a
         * minimal case would do. Declined because continuation reached that way is a property of one branch
         * rather than of the loop: an implementation that carried on after a missing row but abandoned the run
         * on a refused constraint would pass a single-refusal case and fail in production on the first
         * restricted removal. Interleaving a good record behind each of the four kinds is what makes the loop
         * itself the subject.
         *
         * <p>Assumptions: the four kinds are the whole set the dispatch and the write paths can produce -- an
         * unrecognised action byte, a row that is not there, a removal a foreign key refuses, and an insert a
         * unique key refuses. The fifth reason the service declares is reached by the unclassified state
         * asserted in the section below.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("carry on past an invalid byte, a missing row, a restricted removal and a duplicate key")
        void carryOnPastEveryKindOfRefusal() {
            when(types.findByTypeCd(ABSENT_TYPE_CD)).thenReturn(Optional.empty());
            when(types.findByTypeCd(SEEDED_TYPE_CD)).thenReturn(Optional.of(seededType()));
            doThrow(integrityViolation(STATE_FOREIGN_KEY)).when(types).flush();
            when(types.insertType(SEEDED_TYPE_CD, SEEDED_DESCRIPTION))
                    .thenThrow(integrityViolation(STATE_UNIQUE));

            ReferenceBatchUpdateService.BatchUpdateResult result = service.apply(maintenanceStream(
                    maintenanceRecord("z", SEEDED_TYPE_CD, SEEDED_DESCRIPTION),
                    maintenanceRecord(ACTION_ADD, "11", "Behind the invalid byte"),
                    maintenanceRecord(ACTION_UPDATE, ABSENT_TYPE_CD, "No such row"),
                    maintenanceRecord(ACTION_ADD, "12", "Behind the missing row"),
                    maintenanceRecord(ACTION_DELETE, SEEDED_TYPE_CD, ""),
                    maintenanceRecord(ACTION_ADD, "13", "Behind the restricted removal"),
                    maintenanceRecord(ACTION_ADD, SEEDED_TYPE_CD, SEEDED_DESCRIPTION),
                    maintenanceRecord(ACTION_ADD, "14", "Behind the duplicate key")));

            assertThat(result.processedCount()).isEqualTo(8);
            assertThat(result.outcomes()).hasSize(8);
            assertThat(result.outcomes())
                    .extracting(ReferenceBatchUpdateService.RecordOutcome::succeeded)
                    .as("the four refusals and the four records behind them alternate, in stream order")
                    .containsExactly(false, true, false, true, false, true, false, true);
            assertThat(result.outcomes())
                    .extracting(ReferenceBatchUpdateService.RecordOutcome::rejectReason)
                    .as("each refusal keeps its own reason rather than collapsing into one")
                    .containsExactly(
                            ReferenceBatchUpdateService.RejectReason.INVALID_ACTION_CODE, null,
                            ReferenceBatchUpdateService.RejectReason.NOT_FOUND, null,
                            ReferenceBatchUpdateService.RejectReason.REFERENTIAL_INTEGRITY, null,
                            ReferenceBatchUpdateService.RejectReason.DUPLICATE_KEY, null);

            verify(types).insertType("11", "Behind the invalid byte");
            verify(types).insertType("12", "Behind the missing row");
            verify(types).insertType("13", "Behind the restricted removal");
            verify(types).insertType("14", "Behind the duplicate key");

            assertThat(result.anyRejected()).isTrue();
            assertThat(result.returnCode())
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_SOFT_WARN);
        }

        /**
         * The aggregate indicator and the per-record list are independent carriers of different facts.
         *
         * <p>Alternatives Considered: collapsing the two into one member, which the baseline's own shape
         * invites. Its return-code register is a run-level value: line 232 moves exactly 4 into it on every
         * refusal, so a second refusal overwrites the first with the same number and the register carries
         * neither a count nor the identity of what failed. The per-record detail the baseline did have went to
         * the job log as a display line, which is not a structured artifact. Keeping only the aggregate would
         * lose which records were refused; keeping only the list would lose the summary a caller gates on.
         * This case is what proves the two are not redundant: the aggregate is identical across a run with one
         * refusal and a run with two, while the lists differ.
         *
         * <p>Assumptions: the aggregate is a warning indicator rather than an accumulator. That is asserted
         * positively, because a reader who has met the graded rubric in the reference-only suite may expect
         * severities to add up, and here they deliberately do not.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("report the same aggregate for one refusal and for two while the lists differ")
        void reportTheSameAggregateForOneRefusalAndForTwo() {
            when(types.findByTypeCd(ABSENT_TYPE_CD)).thenReturn(Optional.empty());

            ReferenceBatchUpdateService.BatchUpdateResult oneRefusal = service.apply(maintenanceStream(
                    maintenanceRecord(ACTION_ADD, "11", "Applied"),
                    maintenanceRecord(ACTION_UPDATE, ABSENT_TYPE_CD, "No such row"),
                    maintenanceRecord(ACTION_ADD, "12", "Applied")));

            ReferenceBatchUpdateService.BatchUpdateResult twoRefusals = service.apply(maintenanceStream(
                    maintenanceRecord(ACTION_UPDATE, ABSENT_TYPE_CD, "No such row"),
                    maintenanceRecord(ACTION_ADD, "13", "Applied"),
                    maintenanceRecord(ACTION_DELETE, ABSENT_TYPE_CD, "")));

            assertThat(oneRefusal.returnCode())
                    .as("a single refusal reaches the warning tier")
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_SOFT_WARN);
            assertThat(twoRefusals.returnCode())
                    .as("a second refusal overwrites the first with the same value and never accumulates")
                    .isEqualTo(oneRefusal.returnCode());
            assertThat(oneRefusal.anyRejected()).isTrue();
            assertThat(twoRefusals.anyRejected()).isTrue();

            assertThat(oneRefusal.outcomes())
                    .extracting(ReferenceBatchUpdateService.RecordOutcome::succeeded)
                    .as("the list places the single refusal in the middle of the run")
                    .containsExactly(true, false, true);
            assertThat(twoRefusals.outcomes())
                    .extracting(ReferenceBatchUpdateService.RecordOutcome::succeeded)
                    .as("the list distinguishes two refusals from one where the aggregate cannot")
                    .containsExactly(false, true, false);
        }

        /**
         * A clean run and an empty run both report the clean code while the list stays faithful to the input.
         *
         * <p>Assumptions: the list is populated from the input rather than from the failures, so a clean run
         * still reports one outcome per record and an empty stream reports none. Asserting both together is
         * what shows the two members answer different questions: the aggregate is derived from whether
         * anything was refused, and the list length is derived from how many records arrived.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("report one outcome per record on a clean run and none at all on an empty stream")
        void reportTheCleanCodeForACleanRunAndForAnEmptyStream() {
            ReferenceBatchUpdateService.BatchUpdateResult clean = service.apply(maintenanceStream(
                    maintenanceRecord(ACTION_ADD, "11", "First"),
                    maintenanceRecord(ACTION_COMMENT, "12", "a commented row is read and not written"),
                    maintenanceRecord(ACTION_ADD, "13", "Third")));

            assertThat(clean.processedCount()).isEqualTo(3);
            assertThat(clean.outcomes())
                    .extracting(ReferenceBatchUpdateService.RecordOutcome::succeeded)
                    .containsExactly(true, true, true);
            assertThat(clean.anyRejected()).isFalse();
            assertThat(clean.returnCode())
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_CLEAN);

            // WHY : Assumptions: a commented row is a TYPED record and not a skipped one. Line 121 displays
            //       that it is being ignored and performs no database action, so it belongs in the list as a
            //       successful outcome while issuing no write -- which is what separates it from the
            //       unrecognised byte, whose row is reported AND moves the return code.
            verify(types, never()).insertType("12", "a commented row is read and not written");

            ReferenceBatchUpdateService.BatchUpdateResult empty =
                    service.apply(new ByteArrayInputStream(new byte[0]));

            assertThat(empty.outcomes()).isEmpty();
            assertThat(empty.processedCount()).isZero();
            assertThat(empty.anyRejected()).isFalse();
            assertThat(empty.returnCode())
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_CLEAN);
        }

        /**
         * A run in which every record is refused still returns a result rather than raising.
         *
         * <p>Assumptions: this is where the boundary of the return-code analogy is asserted rather than only
         * described. The warning value is a field on the returned result, so the caller decides what to do
         * with it; it is not a thrown exception, not a JUnit verdict and not a process exit status. A run of
         * nothing but refusals is the sharpest form of the case, because it is the one where an implementation
         * tempted to escalate would do so.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("return a result carrying the warning value even when no record applied")
        void returnAResultEvenWhenNoRecordApplied() {
            assertThatCode(() -> service.apply(maintenanceStream(
                    maintenanceRecord("z", SEEDED_TYPE_CD, "First bad byte"),
                    maintenanceRecord("1", SEEDED_TYPE_CD, "Second bad byte"))))
                    .as("a refusal is a value on the result, so nothing propagates out of the run")
                    .doesNotThrowAnyException();

            ReferenceBatchUpdateService.BatchUpdateResult result = service.apply(maintenanceStream(
                    maintenanceRecord("z", SEEDED_TYPE_CD, "First bad byte"),
                    maintenanceRecord("1", SEEDED_TYPE_CD, "Second bad byte")));

            assertThat(result.processedCount()).isEqualTo(2);
            assertThat(result.outcomes())
                    .extracting(ReferenceBatchUpdateService.RecordOutcome::succeeded)
                    .containsExactly(false, false);
            assertThat(result.returnCode())
                    .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_SOFT_WARN);
            verify(types, never()).insertType(anyString(), anyString());
        }
    }

    /**
     * Cases over the typed classification of a refused constraint, which the baseline cannot express.
     *
     * <p>Refactoring Rationale: the baseline's delete paragraph at lines 196 to 226 of
     * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} evaluates three arms and no more, and a search of
     * the program for {@code -532} returns no hit, so a removal a foreign key refuses lands on the same
     * catch-all arm at line 216 as a connection fault and is reported through the same string at lines 218 and
     * 219. Its insert paragraph at lines 151 to 163 evaluates only two arms, so a duplicate key is
     * undifferentiated in the same way. What was wrong with that is not the message but the loss of
     * information: a caller cannot tell a refusal it could act on from one it could not, and the two need
     * opposite responses. The migrated form reports each state as its own reason, and the divergence is
     * registered in {@code docs/architecture/cobol-to-service-traceability.md}.
     *
     * <p>Assumptions: the gap is stated for this program only. Its two sibling screens do discriminate the
     * referential case, at {@code COTRTUPC.cbl} line 1638 and {@code COTRTLIC.cbl} line 1914, so the baseline
     * is uneven across the three programs rather than uniformly silent, and a claim generalised from one to
     * the others would be false of two of them.
     */
    @Nested
    @DisplayName("on the typed integrity classification")
    class OnTheTypedIntegrityClassification {

        /**
         * A restricted removal and an unrelated refusal on the same path classify differently.
         *
         * <p>Assumptions: both halves are asserted, and asserting only the first would prove half the
         * contract. A classifier that answered with the referential reason for every state would satisfy a
         * case that tested the restricted removal alone, and it is the inequality between the two outcomes
         * that rules that out. Both refusals are driven through the delete path so that the state is the only
         * thing that differs between them.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("separate a restricted removal from an unrelated refusal on the delete path")
        void separateARestrictedRemovalFromAnUnrelatedRefusal() {
            when(types.findByTypeCd(SEEDED_TYPE_CD)).thenReturn(Optional.of(seededType()));
            doThrow(integrityViolation(STATE_FOREIGN_KEY)).when(types).flush();

            ReferenceBatchUpdateService.RecordOutcome restricted =
                    service.applyRecord(maintenanceRecord(ACTION_DELETE, SEEDED_TYPE_CD, ""));

            doThrow(integrityViolation(STATE_UNRELATED)).when(types).flush();

            ReferenceBatchUpdateService.RecordOutcome unrelated =
                    service.applyRecord(maintenanceRecord(ACTION_DELETE, SEEDED_TYPE_CD, ""));

            assertThat(restricted.rejectReason())
                    .as("the removal the category foreign key refuses is actionable and is named as such")
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.REFERENTIAL_INTEGRITY);
            assertThat(restricted.sqlState()).isEqualTo(STATE_FOREIGN_KEY);
            assertThat(restricted.succeeded()).isFalse();

            assertThat(unrelated.rejectReason())
                    .as("a state outside the two classified falls to the general reason")
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.SQL_ERROR);
            assertThat(unrelated.sqlState()).isEqualTo(STATE_UNRELATED);

            assertThat(restricted.rejectReason())
                    .as("the two must not collapse, which is the half a one-sided case cannot show")
                    .isNotEqualTo(unrelated.rejectReason());
        }

        /**
         * A duplicate key on the add path is its own reason and not the referential one.
         *
         * <p>Assumptions: the two states arrive through different paths in the migrated code -- the unique
         * violation from the insert statement and the referential one from the flush that follows a removal --
         * so this case pins the add path specifically. The table's own definition is what makes the state
         * reachable: {@code app/app-transaction-type-db2/ddl/TRNTYPE.ddl} declares the two-character type as
         * the primary key, so a second insert of a seeded code is refused rather than duplicated.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("name a duplicate key on the add path as its own reason")
        void nameADuplicateKeyOnTheAddPathAsItsOwnReason() {
            when(types.insertType(SEEDED_TYPE_CD, SEEDED_DESCRIPTION))
                    .thenThrow(integrityViolation(STATE_UNIQUE));

            ReferenceBatchUpdateService.RecordOutcome duplicate =
                    service.applyRecord(maintenanceRecord(ACTION_ADD, SEEDED_TYPE_CD, SEEDED_DESCRIPTION));

            assertThat(duplicate.action()).isEqualTo(ReferenceBatchUpdateService.RecordAction.ADD);
            assertThat(duplicate.succeeded()).isFalse();
            assertThat(duplicate.rejectReason())
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.DUPLICATE_KEY);
            assertThat(duplicate.sqlState()).isEqualTo(STATE_UNIQUE);
            assertThat(duplicate.rejectReason())
                    .as("a duplicate key is not a referential refusal, and the two arrive by different paths")
                    .isNotEqualTo(ReferenceBatchUpdateService.RejectReason.REFERENTIAL_INTEGRITY);
        }

        /**
         * A refusal that carries no state at all falls to the general reason with no state reported.
         *
         * <p>Assumptions: a wrapper can reach the classifier with nothing to classify. The service looks for a
         * platform-level exception in the cause chain and reads the state from it, so a violation raised with
         * no such cause leaves the state absent. Reporting the general reason and an absent state is the only
         * honest answer there, and it is asserted so that the absent state is a documented outcome rather than
         * something a caller meets for the first time in production.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("report the general reason and no state when the refusal carries none")
        void reportTheGeneralReasonWhenTheRefusalCarriesNoState() {
            when(types.insertType("11", "Carries no state"))
                    .thenThrow(new DataIntegrityViolationException("no platform cause is attached"));

            ReferenceBatchUpdateService.RecordOutcome outcome =
                    service.applyRecord(maintenanceRecord(ACTION_ADD, "11", "Carries no state"));

            assertThat(outcome.succeeded()).isFalse();
            assertThat(outcome.rejectReason())
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.SQL_ERROR);
            assertThat(outcome.sqlState())
                    .as("nothing in the chain reported a state, so none is invented")
                    .isNull();
        }

        /**
         * The two classified states are the standard values, and the five reasons are distinct.
         *
         * <p>Assumptions: the constants are asserted against their literal values rather than against each
         * other, because they are the contract with the database rather than an internal choice. Both are
         * standard integrity-violation states, so a value drifting to a provider-specific code would silently
         * stop matching what the database reports and every refusal would fall to the general reason -- a
         * failure that presents as an unclassified error rather than as a wrong constant.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("declare the standard integrity states and five distinct reasons")
        void declareTheStandardIntegrityStatesAndFiveDistinctReasons() {
            assertThat(TransactionTypeService.SQLSTATE_FOREIGN_KEY_VIOLATION)
                    .as("the state a restricted foreign key reports")
                    .isEqualTo(STATE_FOREIGN_KEY);
            assertThat(TransactionTypeService.SQLSTATE_UNIQUE_VIOLATION)
                    .as("the state a unique key reports")
                    .isEqualTo(STATE_UNIQUE);

            assertThat(ReferenceBatchUpdateService.RejectReason.values())
                    .as("a reason folded into another would make two refusals indistinguishable again")
                    .hasSize(5)
                    .doesNotHaveDuplicates()
                    .containsExactly(
                            ReferenceBatchUpdateService.RejectReason.NOT_FOUND,
                            ReferenceBatchUpdateService.RejectReason.DUPLICATE_KEY,
                            ReferenceBatchUpdateService.RejectReason.REFERENTIAL_INTEGRITY,
                            ReferenceBatchUpdateService.RejectReason.SQL_ERROR,
                            ReferenceBatchUpdateService.RejectReason.INVALID_ACTION_CODE);
        }
    }

    /**
     * Cases pinning every display literal the program emits, character for character.
     *
     * <p>Assumptions: these strings are contracts rather than cosmetics, because an operator reads the job log
     * and a downstream reader may match on it, so the migration carries them across unchanged including their
     * blanks and their punctuation. Two of them are impossible to verify by eye, which is why they are asserted
     * structurally here: a run of trailing blanks inside a literal, and a single lower-case letter that differs
     * between two programs emitting otherwise identical text.
     */
    @Nested
    @DisplayName("on the verbatim baseline literals")
    class OnTheVerbatimBaselineLiterals {

        /**
         * All five dispatch branches carry their display text, and the progress literal keeps three blanks.
         *
         * <p>Assumptions: the trailing blanks are asserted by length and by slice rather than by writing the
         * literal out again. The program's line 105 emits a progress literal followed by the record, and the
         * blanks are what separate the two in the log; a literal written out in an assertion is exactly as
         * easy to get wrong as the one under test, so counting the run is what makes the case independent of
         * the author's eye.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("carry the five branch texts and the three blanks of the progress literal")
        void carryTheFiveBranchTextsAndTheProgressBlanks() {
            assertThat(ReferenceBatchUpdateService.RecordAction.ADD.displayText())
                    .as("line 112")
                    .isEqualTo("ADDING RECORD");
            assertThat(ReferenceBatchUpdateService.RecordAction.UPDATE.displayText())
                    .as("line 115")
                    .isEqualTo("UPDATING RECORD");
            assertThat(ReferenceBatchUpdateService.RecordAction.DELETE.displayText())
                    .as("line 118")
                    .isEqualTo("DELETING RECORD");
            assertThat(ReferenceBatchUpdateService.RecordAction.COMMENT.displayText())
                    .as("line 121")
                    .isEqualTo("IGNORING COMMENTED LINE");
            assertThat(ReferenceBatchUpdateService.RecordAction.INVALID.displayText())
                    .as("line 124")
                    .isEqualTo("ERROR: TYPE NOT VALID");

            String progress = ReferenceBatchUpdateService.DISPLAY_PROCESSING;
            assertThat(progress).startsWith("PROCESSING");
            assertThat(progress)
                    .as("ten letters and three blanks, so thirteen bytes and not twelve or fourteen")
                    .hasSize(13);
            assertThat(progress.substring("PROCESSING".length()))
                    .as("the separating blanks of line 105, counted rather than eyeballed")
                    .isEqualTo("   ")
                    .hasSize(3);
        }

        /**
         * The three success texts and the not-found text are carried unchanged.
         *
         * <p>Assumptions: the not-found text keeps its terminating period, which the baseline's own literal
         * carries at lines 181 and 211 while none of the three success texts does. That asymmetry is the kind
         * of detail a rewrite tidies away, so it is asserted rather than trusted.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("carry the three success texts and the not-found text unchanged")
        void carryTheSuccessTextsAndTheNotFoundText() {
            assertThat(ReferenceBatchUpdateService.MESSAGE_RECORD_INSERTED)
                    .as("line 153")
                    .isEqualTo("RECORD INSERTED SUCCESSFULLY");
            assertThat(ReferenceBatchUpdateService.MESSAGE_RECORD_UPDATED)
                    .as("line 179")
                    .isEqualTo("RECORD UPDATED SUCCESSFULLY");
            assertThat(ReferenceBatchUpdateService.MESSAGE_RECORD_DELETED)
                    .as("line 209")
                    .isEqualTo("RECORD DELETED SUCCESSFULLY");
            assertThat(ReferenceBatchUpdateService.MESSAGE_NO_RECORDS_FOUND)
                    .as("lines 181 and 211, whose period the three texts above do not carry")
                    .isEqualTo("No records found.")
                    .endsWith(".");
        }

        /**
         * The error text uses this program's lower-case wording and appends no driver detail.
         *
         * <p>Assumptions: the single lower-case letter is the whole point of this case. This program writes the
         * table word in lower case at lines 157, 188 and 219, behind the prefix at lines 156, 187 and 218,
         * whereas {@code COTRTUPC.cbl} writes the same word capitalised at line 1571 behind a different prefix
         * at line 1570 and again at line 1611 behind the prefix at line 1610. Two literals that differ by one
         * letter are the ones most likely to be merged by someone consolidating duplicates, so both forms are
         * preserved and this case asserts that this path holds the lower-case one.
         *
         * <p>Assumptions: the absence of appended driver text is a second, independent difference between the
         * two programs. The sibling screen appends the provider's own message text to its string; this program
         * appends only its SQLCODE display field. The marker carried on the cause is searched for in the
         * reported message, so growing that component would be detected rather than merely looking plausible.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("use the lower-case table wording and append no driver text")
        void useTheLowerCaseTableWordingAndAppendNoDriverText() {
            assertThat(ReferenceBatchUpdateService.MESSAGE_ERROR_ACCESSING)
                    .as("lines 156, 187 and 218")
                    .isEqualTo("Error accessing:")
                    .isNotEqualTo("Error updating:")
                    .isNotEqualTo("Error inserting record into:");
            assertThat(ReferenceBatchUpdateService.MESSAGE_ERROR_ACCESSING_SUFFIX)
                    .as("lines 157, 188 and 219, whose table word is lower case in this program")
                    .isEqualTo(" TRANSACTION_TYPE table. SQLCODE:")
                    .contains(" table.")
                    .doesNotContain(" Table.");

            when(types.insertType("11", "Refused")).thenThrow(integrityViolation(STATE_UNIQUE));

            ReferenceBatchUpdateService.RecordOutcome refused =
                    service.applyRecord(maintenanceRecord(ACTION_ADD, "11", "Refused"));

            assertThat(refused.message())
                    .as("the reported text is the two literals joined and nothing further")
                    .isEqualTo("Error accessing: TRANSACTION_TYPE table. SQLCODE:")
                    .doesNotContain(DRIVER_REASON)
                    .doesNotContain("SQLERRM");
        }

        /**
         * The return message is eighty characters wide here, not the seventy-five used elsewhere.
         *
         * <p>Assumptions: this program declares its own message field at line 61 as eighty characters, which
         * differs from the seventy-five of {@code COTRTUPC.cbl} at line 167 and from the two screen fields in
         * {@code app/cpy/CVCRD01Y.cpy} at lines 28 and 29. Nothing forces the three to agree, so the width is
         * a per-path fact and the batch path keeps its own. Asserting the inequality as well as the value is
         * what would catch a consolidation onto the screen width, which is the likelier direction of the
         * mistake because seventy-five is the value that appears more often across the corpus.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("declare the eighty-character return message width of this path")
        void declareTheEightyCharacterReturnMessageWidth() {
            assertThat(ReferenceBatchUpdateService.RETURN_MESSAGE_WIDTH)
                    .as("line 61 declares eighty characters for this program's message field")
                    .isEqualTo(80)
                    .isNotEqualTo(75);
        }

        /**
         * No file-open display is published, because the migrated entry point is handed an open source.
         *
         * <p>Assumptions: the two literals at lines 85 and 87 belong to a paragraph that opens a dataset and
         * reports whether the open succeeded. The migrated entry point receives a stream its caller already
         * opened, so there is no open to report on and carrying the strings across would publish a message no
         * code path could ever emit. Their absence is asserted rather than left silent so that a reader
         * comparing the literal inventory against the program finds the two accounted for instead of assuming
         * they were overlooked; the condition they reported is now the unreadable-source failure asserted
         * above.
         *
         * <p>It takes no parameter and returns no value.
         *
         * @throws ReflectiveOperationException if a declared static string cannot be read
         */
        @Test
        @DisplayName("publish no file-open display because the source arrives already open")
        void publishNoFileOpenDisplay() throws ReflectiveOperationException {
            List<String> constants = declaredStringConstants();

            assertThat(constants)
                    .as("the inventory must be non-empty, or the absence below would prove nothing")
                    .isNotEmpty()
                    .contains(ReferenceBatchUpdateService.DISPLAY_PROCESSING);
            assertThat(constants)
                    .as("lines 85 and 87 report an open this entry point never performs")
                    .doesNotContain("OPEN FILE OK", "OPEN FILE NOT OK");
        }
    }

    /**
     * Cases over the two host variables the update statement binds, and what the migration does with each.
     *
     * <p>Assumptions: the update statement at lines 171 to 175 of
     * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} binds the description at line 173 and the type
     * code at line 174 straight out of the record, with no editing between the read and the bind. The batch
     * path therefore has no editor, while the maintenance screen does; the two are separate programs and the
     * migration keeps them separate rather than lending one the other's validation.
     */
    @Nested
    @DisplayName("on the bind fidelity of the two host variables")
    class OnTheBindFidelityOfTheTwoHostVariables {

        /**
         * A type code is bound as read, neither checked for digits nor padded to width.
         *
         * <p>Assumptions: the batch path deliberately carries no editor. The screen program reaches its
         * required-numeric edit at {@code COTRTUPC.cbl} lines 829 and 830, which refuses a blank at line 924, a
         * non-numeric value at line 943 and a zero at line 961, and separately pads a single digit to two at
         * lines 834 to 841. None of that exists in the batch program: line 174 binds the two bytes as they were
         * read. Lending the batch path the screen's editor would make it refuse input the baseline accepts,
         * which is a behavioural change in the direction that silently drops rows a maintainer expected to
         * load.
         *
         * <p>Trade-offs: this leaves the batch path accepting codes the screen would refuse, and that is the
         * compromise taken. Fidelity to the baseline was chosen over uniformity across the two paths, because
         * the table's own column is a fixed two-character type with no numeric domain declared in
         * {@code app/app-transaction-type-db2/ddl/TRNTYPE.ddl}, so the database accepts these codes too and
         * refusing them here would put the migration at odds with both the baseline and the schema. The
         * divergence available to a caller who wants the stricter behaviour is the published request path,
         * whose constraints the sibling api package asserts.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("bind a non-numeric, a zero and an unpadded type code exactly as read")
        void bindATypeCodeExactlyAsRead() {
            service.applyRecord(maintenanceRecord(ACTION_ADD, "AB", "Alphabetic code"));
            service.applyRecord(maintenanceRecord(ACTION_ADD, "00", "Zero code"));
            service.applyRecord(maintenanceRecord(ACTION_ADD, " 1", "Unpadded code"));

            verify(types).insertType("AB", "Alphabetic code");
            verify(types).insertType("00", "Zero code");
            verify(types).insertType(" 1", "Unpadded code");

            // WHY : Assumptions: the unpadded code is asserted twice over, once for what it IS and once for
            //       what it is NOT. Zero-padding is the transformation the screen program applies at its lines
            //       834 to 841, so a migration that reused that editor would turn this code into the seeded
            //       one and write to a different row than the record named -- a silent mis-target rather than
            //       a visible refusal, which is why the negative assertion is the load-bearing one.
            verify(types, never()).insertType(SEEDED_TYPE_CD, "Unpadded code");
        }

        /**
         * A description is stored with its trailing blanks removed and its leading blanks intact.
         *
         * <p>Refactoring Rationale: the baseline binds the description untrimmed at line 173, so the fifty-byte
         * field reaches the column with its padding attached. What was wrong with carrying that across is that
         * the target column is variable-length rather than fixed, so the padding would be stored as data and
         * every comparison, every ordering and every rendered value would carry blanks the record's author
         * never typed. The trailing blanks are therefore dropped at the one shared boundary the reference
         * mappers use, and the divergence is registered in
         * {@code docs/architecture/cobol-to-service-traceability.md}.
         *
         * <p>Assumptions: only TRAILING blanks are dropped. A leading blank is indistinguishable from padding
         * by position but not by intent, since the fill rule pads on the right alone, so trimming both ends
         * would discard a character the author placed deliberately. Asserting the leading blank survives is
         * what pins the asymmetry.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("store a description trailing-trimmed with its leading blank intact")
        void storeADescriptionTrailingTrimmed() {
            service.applyRecord(maintenanceRecord(ACTION_ADD, "11", " Leading and trailing  "));

            verify(types).insertType("11", " Leading and trailing");
            verify(types, never()).insertType("11", "Leading and trailing");
        }
    }

    /**
     * Cases over the reach of the transcription: one table, one collaborator, and no batch machinery.
     *
     * <p>Assumptions: line 54 of {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} is the program's only
     * table declaration and it names the type table. Its sibling declaration for the category table sits in the
     * same directory and is not included, and a search of the program for that name returns no hit, so the
     * program has no category structure to write through. A consumer expecting this run to alter the category
     * table would be expecting something the baseline cannot cause.
     */
    @Nested
    @DisplayName("on the reach of the transcription")
    class OnTheReachOfTheTranscription {

        /**
         * The service declares one constructor taking the type repository and nothing else.
         *
         * <p>Assumptions: asserting the whole declared collaborator set is what makes the category table's
         * absence provable. A case that merely refrained from stubbing a category repository would pass whether
         * or not one were injected, whereas a constructor carrying a second repository fails this and has to be
         * reasoned about -- which is the behaviour wanted from a guard on scope.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("take the type repository as its only collaborator")
        void takeTheTypeRepositoryAsItsOnlyCollaborator() {
            Constructor<?>[] declared = ReferenceBatchUpdateService.class.getDeclaredConstructors();

            assertThat(declared)
                    .as("a second constructor could admit a collaborator this program has no declaration for")
                    .hasSize(1);
            assertThat(declared[0].getParameterTypes())
                    .as("line 54 declares the type table alone, so one repository is the whole set")
                    .containsExactly(TransactionTypeRepository.class);
        }

        /**
         * Neither entry point on the record-stream path declares a transaction of its own.
         *
         * <p>Assumptions: the baseline declares no unit of work anywhere on this path. Searching the program
         * for a syncpoint, a commit or a rollback returns no hit of any kind, so each statement stands on
         * whatever the surrounding environment provides and no record is grouped with another. Declaring a
         * boundary here would invent one the baseline never had, and a boundary spanning the run would be the
         * damaging version of that invention: a single refused record would roll back every record that had
         * already applied, which is the opposite of the continuation the whole class asserts.
         *
         * <p>It takes no parameter and returns no value.
         *
         * @throws NoSuchMethodException if either entry point is renamed or its signature changes without
         *     these cases being updated
         */
        @Test
        @DisplayName("declare no transaction boundary on either record-stream entry point")
        void declareNoTransactionBoundaryOnTheRecordStreamPath() throws NoSuchMethodException {
            Method apply = ReferenceBatchUpdateService.class.getMethod("apply", InputStream.class);
            Method applyRecord =
                    ReferenceBatchUpdateService.class.getMethod("applyRecord", byte[].class);

            assertThat(apply.getAnnotation(Transactional.class))
                    .as("a run-wide boundary would roll back the records that applied before a refusal")
                    .isNull();
            assertThat(applyRecord.getAnnotation(Transactional.class))
                    .as("the baseline groups no statement with another on this path")
                    .isNull();
            assertThat(ReferenceBatchUpdateService.class.getAnnotation(Transactional.class))
                    .as("a class-level declaration would reach both methods above by inheritance")
                    .isNull();
        }

        /**
         * No chunk-oriented batch type is reachable from this module at all.
         *
         * <p>Alternatives Considered: modelling this program as a chunk-oriented job with a job repository,
         * which is the reflex for anything named a batch. Declined because there is nothing for such a job to
         * hold: the program declares a sequential organisation and access mode at lines 32 and 33, performs one
         * read per iteration at line 101, and defines no chunk boundary, no commit interval and no restart
         * point. This module accordingly declares no batch starter, an absence its own build file records
         * deliberately, and that absence is asserted here rather than described so that adding the dependency
         * fails a case and prompts the reasoning again.
         *
         * <p>Assumptions: a class-loading probe is the right instrument because the guarantee wanted is about
         * the classpath rather than about this file's imports. A source-level check would only show that this
         * class avoids those types, which is already visible; the probe shows that no class in the module could
         * use them.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("expose no chunk-oriented batch type on the module classpath")
        void exposeNoChunkOrientedBatchType() {
            assertThatThrownBy(() -> Class.forName("org.springframework.batch.core.Job"))
                    .as("a job type on this classpath would mean the batch starter had been added")
                    .isInstanceOf(ClassNotFoundException.class);
            assertThatThrownBy(
                    () -> Class.forName("org.springframework.batch.test.context.SpringBatchTest"))
                    .as("the batch testing annotation is unavailable for the same reason")
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    /**
     * The one case holding this program's answer to a missing row apart from the other two in the module.
     *
     * <p>Assumptions: three programs in this bounded context answer "the row was not there" in three different
     * ways, and the module's whole reason for asserting write behaviour is to keep the three from collapsing
     * into one. The inquiry screen reports the miss and writes nothing, the maintenance screen inserts on the
     * miss, and this batch program refuses the single record and reads the next one. The other two answers are
     * asserted by the transaction-type classes in this package; this case asserts only that the batch answer is
     * a per-record refusal and is therefore neither of them.
     */
    @Nested
    @DisplayName("on the third answer to a missing row")
    class OnTheThirdAnswerToAMissingRow {

        /**
         * An update of an absent code refuses the record without raising and without writing anything.
         *
         * <p>Assumptions: all three properties are asserted because each rules out a different one of the
         * sibling behaviours. Returning rather than raising is what separates this from the inquiry screen's
         * answer, which surfaces the miss as a refusal the caller must catch. Writing nothing is what separates
         * it from the maintenance screen's answer, which would create the row. And the run continuing past it
         * is what makes it a per-record outcome rather than a run-level one. Asserting only the message would
         * leave all three indistinguishable, since all three report the same text.
         *
         * <p>It takes no parameter and returns no value.
         */
        @Test
        @DisplayName("refuse the record, write nothing and read the next one")
        void refuseTheRecordWriteNothingAndReadTheNextOne() {
            when(types.findByTypeCd(ABSENT_TYPE_CD)).thenReturn(Optional.empty());

            assertThatCode(() -> service.applyRecord(
                    maintenanceRecord(ACTION_UPDATE, ABSENT_TYPE_CD, "No such row")))
                    .as("the miss is a value on the outcome, not something the caller has to catch")
                    .doesNotThrowAnyException();

            ReferenceBatchUpdateService.BatchUpdateResult result = service.apply(maintenanceStream(
                    maintenanceRecord(ACTION_UPDATE, ABSENT_TYPE_CD, "No such row"),
                    maintenanceRecord(ACTION_ADD, "11", "Read after the miss")));

            ReferenceBatchUpdateService.RecordOutcome missed = result.outcomes().get(0);
            assertThat(missed.action()).isEqualTo(ReferenceBatchUpdateService.RecordAction.UPDATE);
            assertThat(missed.succeeded()).isFalse();
            assertThat(missed.rejectReason())
                    .isEqualTo(ReferenceBatchUpdateService.RejectReason.NOT_FOUND);
            assertThat(missed.message())
                    .as("lines 180 and 181 build this text before reaching the soft-reject paragraph")
                    .isEqualTo("No records found.");

            assertThat(result.outcomes().get(1).succeeded())
                    .as("the loop at lines 93 to 96 reads the record behind the miss")
                    .isTrue();

            verify(types, never()).saveAndFlush(any(TransactionType.class));
            verify(types, never()).insertType(ABSENT_TYPE_CD, "No such row");
        }
    }
}
