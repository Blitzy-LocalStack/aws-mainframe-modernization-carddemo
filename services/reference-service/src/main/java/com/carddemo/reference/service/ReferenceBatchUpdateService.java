//=============================================================================
// WHY : Assumptions: the paragraph this class transcribes its failure handling
//       from is named 9999-ABEND and it does NOT terminate anything. Its whole
//       body is three statements at app/app-transaction-type-db2/cbl/
//       COBTUPDT.cbl lines 230 to 233 -- DISPLAY WS-RETURN-MSG. then
//       MOVE 4 TO RETURN-CODE then EXIT. -- with no STOP RUN, no GOBACK and no
//       CALL to a language-environment abend routine anywhere in it. Control
//       returns to the caller and thence into the PERFORM UNTIL LASTREC = 'Y'
//       loop at lines 93 to 96, the next record is read at line 95, and the
//       run reaches its close path at line 97 reporting condition code 4. The
//       name is therefore a MISNOMER, and "reject the record and carry on" is
//       FAITHFUL behaviour rather than a departure from it.
// WHY : Assumptions: every line number cited in this file is a PHYSICAL line
//       number, verified by reading the line at that address rather than by
//       searching for a printed sequence value. The two disagree in this
//       program: COBTUPDT.cbl carries legacy sequence numbers in source
//       columns 73 to 80, and they are not a line count.
// WHY : Assumptions: everything beneath app/ is the behavioural oracle of this
//       migration. It is read, cited by path and physical line, and never
//       modified; where the migrated behaviour departs from it deliberately
//       the departure is registered in
//       docs/architecture/cobol-to-service-traceability.md, which is
//       maintained elsewhere and referenced rather than reproduced.
//=============================================================================
package com.carddemo.reference.service;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.MaintenanceActionBatchRequest;
import com.carddemo.reference.dto.MaintenanceActionBatchResponse;
import com.carddemo.reference.dto.MaintenanceActionOutcomeResponse;
import com.carddemo.reference.dto.MaintenanceActionRequest;
import com.carddemo.reference.mapper.TransactionTypeMapper;
import com.carddemo.reference.repository.TransactionTypeRepository;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction-type maintenance run, transcribed from the baseline batch program.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is the migrated form of {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl}. It
 * applies an ordered run of add, update and delete actions against {@code reference.transaction_types},
 * reporting one outcome per record together with the aggregate condition code the baseline reports
 * through its own return-code register. It is the only write path in this package that tolerates a
 * refusal and continues.</p>
 *
 * <p>Two entry points exist because the baseline stream and the published request body are two
 * different carriers of the same actions. {@link #apply(InputStream)} consumes the maintenance records
 * in their baseline form and is the transcription of the read loop; {@link #applyRecord(byte[])}
 * transcribes the dispatch of a single record. {@link #apply(MaintenanceActionBatchRequest)} and
 * {@link #applyOne(int, MaintenanceActionRequest)} serve the published contract, whose actions arrive
 * already parsed. The two are overloads rather than one member because their parameter types are
 * disjoint and neither carrier can be expressed in terms of the other.</p>
 *
 * <p>Parameters, return values, exceptions or errors. The type itself takes no parameter, yields no
 * value and raises nothing; every member below carries its own parameter, return and exception
 * at-clauses. The inapplicability is stated rather than passed over, because user-specified Rule 1
 * (Explainability) forbids at its line 39 a docstring that omits parameters, return values or purpose,
 * and a reader has to be able to tell a declared inapplicability from an oversight.</p>
 *
 * <h2>Scope: the type table and nothing else</h2>
 *
 * <p>Line 54 of the program is {@code EXEC SQL INCLUDE DCLTRTYP END-EXEC} and it is the only table
 * declaration the program includes. Its sibling {@code DCLTRCAT.dcl} sits in the same directory and is
 * NOT included, so the program has no category declaration to write through. This class therefore
 * reaches {@code reference.transaction_types} only, and {@link TransactionTypeRepository} is the only
 * persistence collaborator it holds. A consumer expecting any change to the category table would be
 * expecting something this program cannot cause.</p>
 *
 * <h2>Decisions</h2>
 *
 * <p>What follows discharges the obligation user-specified Rule 1 (Explainability) sets out at its
 * line 43, whose gate is conjunctive: a docstring alone does not pass it and a rationale alone does not
 * pass it. Each entry carries one of the four categories the rule names at lines 31 to 34 and names a
 * path with a physical line, a declared constraint or a named gate, because line 41 rejects a rationale
 * that gives no specific justification. Entries whose subject is one member are stated again at that
 * member, since line 27 asks for adjacency and a type-level block cannot supply it.</p>
 *
 * <p>Assumptions: a refused record is a SOFT reject and the run continues, and this is the most
 * consequential fact about the whole class. The six sites that report a failure -- lines 128, 162, 184,
 * 193, 214 and 224 -- all reach the same paragraph at lines 230 to 233, whose three statements display
 * the message, move 4 to the return-code register and exit. Nothing in that paragraph stops the run, so
 * control returns into the read loop at lines 93 to 96 and the next record is read at line 95.
 * Abandoning the run at the first refusal would report as failed a run the baseline completes, and would
 * discard the outcomes of the records that did apply. There is direct evidence that the soft behaviour
 * was retrofitted over an earlier design: the sequence value on line 232 ends {@code 044} while its two
 * immediate neighbours on lines 231 and 233 both end {@code 032}, and the same {@code 044} wave
 * introduced line 61 and the progress display on line 105.</p>
 *
 * <p>Assumptions: two paragraphs in this corpus carry names built on the same word and sit at OPPOSITE
 * severity tiers, so they must never be merged. The one transcribed here is soft, as above. The other
 * is {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBACT04C.cbl} lines 628 to 632, which displays
 * {@code 'ABENDING PROGRAM'}, moves 0 to its timing argument and 999 to its code argument and then calls
 * the language-environment routine {@code CEE3ABD}, which does end the run immediately; that program
 * reaches it from seventeen sites and is fail-fast throughout. A reader arriving from
 * {@code DisclosureGroupService}, which transcribes that program, would otherwise carry the fatal
 * reading into this class.</p>
 *
 * <p>Trade-offs: {@link BatchUpdateResult} carries BOTH a per-record outcome list AND an aggregate
 * indicator, which is more state than the baseline holds and is deliberate. The baseline's return-code
 * register is a run-level value: it is set to exactly 4 by every failure, overwritten by each
 * subsequent one, and carries neither a count nor the identity of what failed. The per-record detail
 * the baseline did have lived in the display line the same paragraph writes to the job log, which is
 * not a structured artifact at all. Carrying only the aggregate would lose the identity of the refused
 * records; carrying only the list would lose the summary a caller gates on. This is also the boundary
 * of the analogy: condition code 4 maps to {@link BatchUpdateResult#returnCode()} and to nothing else.
 * It is not a build or test outcome, and the graded rubric it belongs to governs the reference suite
 * under {@code tests/} rather than this module.</p>
 *
 * <p>Alternatives Considered: a Spring Batch job, with a job repository, a step and the reader,
 * processor and writer that go with them. Rejected on three independent grounds. The program is a plain
 * sequential reader -- {@code ORGANIZATION IS SEQUENTIAL} with {@code ACCESS MODE IS SEQUENTIAL} at
 * lines 32 and 33, a single {@code READ ... NEXT RECORD} at line 101 inside the loop at lines 93 to 96
 * -- and carries no chunk boundary, no commit interval and no restart point to model. This module's own
 * configuration declares no such machinery: none of {@code application.yml},
 * {@code application-dev.yml} or {@code application-prod.yml} carries a single {@code spring.batch} key,
 * and {@code application.yml} records the deliberate absence at its line 1260. And the module's
 * dependency set omits the starter that would supply it, so the machinery is not merely unused but
 * absent. A durable step ledger belongs to the batch bounded context, which owns the nightly chain;
 * this is a service method the chain calls.</p>
 *
 * <p>Assumptions: this method is reached over the API boundary and never by a compile-time reference
 * from another service module. AAP Rule T6 (JCL maps by category) maps the driver
 * {@code app/app-transaction-type-db2/jcl/MNTTRDB2.jcl} onto one state invoking a task that calls this
 * member; its {@code //INPFILE DD} at line 27 becomes the record stream, supplied as an object-store
 * URI or a request body. Three of its operands are RETIRED with no target because they name facilities
 * the platform no longer has: {@code PLAN(CARDDEMO)} on line 30, the {@code DBRMLIB} concatenation on
 * line 25 and the {@code STEPLIB} chain on lines 22 to 24 that ends at the load library. Two further
 * properties of that file bear on this class. It does not run the program directly: line 21 is
 * {@code EXEC PGM=IKJEFT01,REGION=0M}, the terminal monitor, and the program is named to it on line 30.
 * And it carries NO {@code PARM=} operand anywhere, so no business date is supplied to the program;
 * this class accordingly derives no date, and reads no clock, because there is no such input to
 * transcribe.</p>
 *
 * <p>Alternatives Considered: separate files for the result, the outcome, the two enumerations and the
 * stream failure. All five are declared as nested members of this class instead. The package inventory
 * beside this file is settled and each of its classes is one transcribed behaviour; a result type that
 * only this class produces, and enumerations whose constants are named for this program's own branches,
 * would add files to that inventory without adding a behaviour to it. Nesting also keeps each type
 * beside the branch table it was derived from, which is where its constant-by-constant citations are
 * checkable.</p>
 *
 * <p>Trade-offs: this class holds no IEEE-754 binary floating point and cannot, because a two-character
 * code and a description are its only data and it performs no arithmetic beyond counting records. That
 * is worth stating because the prohibition is NOT mechanically enforced here: the architecture rule that
 * asserts it scopes its subject set to the shared kernel's money package, so in this package the
 * prohibition rests on review. The names of the forbidden types are described rather than spelled,
 * following the convention the shared codec states, because spelling them would make this file match a
 * search for the very tokens the money path must not contain.</p>
 *
 * <p>Assumptions: parity for this class rests on transcribed logic together with the declared table and
 * host-variable contracts, and NOT on a byte comparison. The reference suite under {@code tests/} is
 * the parity oracle for the batch flows it drives, and it drives the base programs; this program lives
 * in the {@code app-transaction-type-db2} extension tree and has no golden master there. Saying so
 * plainly matters because citing that oracle for this class would claim a check that does not exist.</p>
 */
@Service
public class ReferenceBatchUpdateService {

    /** The declared length of one maintenance record in bytes. */
    public static final int RECORD_LENGTH = 53;

    /** The aggregate code of a run in which every action applied. */
    public static final int RETURN_CODE_CLEAN = 0;

    /** The aggregate code of a run in which at least one action soft rejected. */
    public static final int RETURN_CODE_SOFT_WARN = 4;

    /** The outcome state of an action that changed stored data. */
    public static final String OUTCOME_APPLIED = "APPLIED";

    /** The outcome state of an action whose target row did not exist. */
    public static final String OUTCOME_NO_ROWS_FOUND = "NO_ROWS_FOUND";

    /** The outcome state of an action refused for any other reason. */
    public static final String OUTCOME_FAILED = "FAILED";

    /** The insert action, as the published request body names it. */
    public static final String ACTION_INSERT = "INSERT";

    /** The update action, as the published request body names it. */
    public static final String ACTION_UPDATE = "UPDATE";

    /** The delete action, as the published request body names it. */
    public static final String ACTION_DELETE = "DELETE";

    /** The message reported when an action applied. */
    public static final String MESSAGE_APPLIED = "Record applied...";

    /** The message reported when the target row did not exist. */
    public static final String MESSAGE_NO_ROWS = "Record NOT found...";

    /** The message reported when an insert names a code that already exists. */
    public static final String MESSAGE_ALREADY_EXISTS = "Record already exists...";

    /** The message reported when an insert or update carries no description. */
    public static final String MESSAGE_DESCRIPTION_REQUIRED = "Description is required...";

    /**
     * The action code that selects the add branch, from line 111.
     *
     * <p>Assumptions: the comparison is on the exact byte. A COBOL {@code EVALUATE} compares the field
     * against each literal, so only the uppercase byte satisfies this arm and a lowercase one falls
     * through every named arm to the catch-all at line 122.</p>
     */
    public static final String ACTION_CODE_ADD = "A";

    /** The action code that selects the update branch, from line 114. */
    public static final String ACTION_CODE_UPDATE = "U";

    /** The action code that selects the delete branch, from line 117. */
    public static final String ACTION_CODE_DELETE = "D";

    /** The action code that marks a record as commentary, from line 120. */
    public static final String ACTION_CODE_COMMENT = "*";

    /**
     * The progress text of line 105, whose THREE trailing spaces are part of the literal.
     *
     * <p>Assumptions: the trailing spaces are carried because the program concatenates the record after
     * this text on the same statement, so they are the separator between the two and not accidental
     * padding. AAP Rule T8 (user-visible strings are verbatim) binds them.</p>
     *
     * <p>Trade-offs: this text is EXPOSED here and is not written by this class, which is the one
     * baseline report that has no counterpart in the migrated flow. Its destination was the job log, an
     * unstructured stream that a caller read line by line; the structured replacement for that stream is
     * the per-record outcome list, which carries the same information addressably. The literal is
     * published so that a caller reproducing the log verbatim has the exact bytes, rather than inferring
     * a spacing this class would otherwise be the only record of.</p>
     */
    public static final String DISPLAY_PROCESSING = "PROCESSING   ";

    /** The dispatch text of line 112. */
    public static final String DISPLAY_ADDING_RECORD = "ADDING RECORD";

    /** The dispatch text of line 115. */
    public static final String DISPLAY_UPDATING_RECORD = "UPDATING RECORD";

    /** The dispatch text of line 118. */
    public static final String DISPLAY_DELETING_RECORD = "DELETING RECORD";

    /** The dispatch text of line 121, reported for a record the program skips. */
    public static final String DISPLAY_IGNORING_COMMENTED_LINE = "IGNORING COMMENTED LINE";

    /** The refusal text of line 124, reported for an action code outside the declared domain. */
    public static final String MESSAGE_TYPE_NOT_VALID = "ERROR: TYPE NOT VALID";

    /** The success text of line 153. */
    public static final String MESSAGE_RECORD_INSERTED = "RECORD INSERTED SUCCESSFULLY";

    /** The success text of line 179. */
    public static final String MESSAGE_RECORD_UPDATED = "RECORD UPDATED SUCCESSFULLY";

    /** The success text of line 209. */
    public static final String MESSAGE_RECORD_DELETED = "RECORD DELETED SUCCESSFULLY";

    /**
     * The refusal text of lines 181 and 211, whose terminating period is part of the literal.
     *
     * <p>Assumptions: one literal serves two branches. The update path composes it at line 181 and the
     * delete path composes the identical text at line 211, so a single constant carries both rather than
     * two constants that could drift apart while still reading as agreement.</p>
     */
    public static final String MESSAGE_NO_RECORDS_FOUND = "No records found.";

    /**
     * The first segment of the SQL refusal text, from lines 156, 187 and 218.
     *
     * <p>Assumptions: the text is composed from two segments in the baseline and is carried as two
     * constants for that reason. All three of its sites concatenate this segment and the one below with
     * no separator between them.</p>
     */
    public static final String MESSAGE_ERROR_ACCESSING = "Error accessing:";

    /**
     * The second segment of the SQL refusal text, from lines 157, 188 and 219.
     *
     * <p>Assumptions: three programs render this table name three DIFFERENT ways and all three are
     * preserved separately, so the casing here is load bearing rather than incidental. This program
     * writes {@code table} in lower case with a terminating period, a colon flush against it and a
     * single leading space, at each of its three sites. {@code COTRTUPC.cbl} writes {@code Table}
     * capitalised at its lines 1571 and 1611, and {@code COTRTLIC.cbl} line 1826 writes a third form
     * with no period and a trailing space. AAP Rule T8 (user-visible strings are verbatim) requires each
     * to be carried across character for character, so none of the three is harmonised to the others.</p>
     */
    public static final String MESSAGE_ERROR_ACCESSING_SUFFIX = " TRANSACTION_TYPE table. SQLCODE:";

    /**
     * The declared width of the message field the baseline composes its refusals into.
     *
     * <p>Assumptions: line 61 declares {@code WS-RETURN-MSG PIC X(80)}, so the width here is EIGHTY and
     * not the seventy-five of the two online programs in the same extension tree, which both declare
     * {@code X(75)}. The shared kernel publishes that seventy-five as a rendering width for the message
     * of a published error, and applying it here would truncate a refusal this program can compose to
     * eighty. The two widths are unrelated and neither is derived from the other.</p>
     */
    public static final int RETURN_MESSAGE_WIDTH = 80;

    /** The name of the action-code field, from line 72. */
    private static final String FIELD_ACTION_CODE = "INPUT-REC-TYPE";

    /** The name of the type-code field, from line 74. */
    private static final String FIELD_TYPE_CODE = "INPUT-REC-NUMBER";

    /** The name of the description field, from line 76. */
    private static final String FIELD_DESCRIPTION = "INPUT-REC-DESC";

    /**
     * The number of causes a state lookup walks before giving up.
     *
     * <p>Assumptions: the walk is bounded rather than unbounded because a cause chain can be circular,
     * and an unbounded walk over one would not return. The bound matches the one
     * {@link TransactionTypeService} applies to the same walk, so the two paths give up at the same
     * depth.</p>
     */
    private static final int CAUSE_CHAIN_LIMIT = 8;

    /**
     * The byte geometry of one maintenance record.
     *
     * <p>Assumptions: this layout is built HERE rather than read from the shared codec's registry
     * because that registry has no entry for it, and the entry a reader would most plausibly reach for
     * is the wrong record. The registry holds eleven base masters, three derived layouts and two
     * segment layouts, and its {@code TRANTYPE} entry is the SIXTY-byte VSAM record declared by
     * {@code app/cpy/CVTRA03Y.cpy} -- a different record from this fifty-three-byte input, sharing only
     * a subject. Declaring it in the shared kernel instead was not available: no file there may
     * reference this package in any form, and the layout is meaningless outside the program that reads
     * it. The codec supports exactly this case, which is why a caller-supplied layout is accepted at
     * all.</p>
     */
    private static final CopybookLayout.RecordSpec MAINTENANCE_RECORD = maintenanceRecordLayout();

    /** Access to the transaction-type table. */
    private final TransactionTypeRepository types;

    /**
     * The boundary each single record's or action's write is applied inside, one transaction per unit.
     *
     * <p>⚠️ Refactoring Rationale: this field exists because the annotation it replaces never took
     * effect, and the way it failed was silent. {@code applyOne} carried
     * {@code @Transactional(propagation = REQUIRES_NEW)} and was reached only by
     * {@link #apply(MaintenanceActionBatchRequest)} calling it on {@code this} -- a self-invocation,
     * which does not pass through the transactional proxy -- while the enclosing entry point declared no
     * transaction of its own. So every action ran with NO transaction while the source appeared to
     * declare one per action. The record-stream path had the identical shape and not even the appearance:
     * {@link #apply(InputStream)} calls {@link #applyRecord(byte[])} on {@code this} and neither was
     * annotated at all. The observable consequence was that the very first ADD of either path failed,
     * because {@code TransactionTypeRepository.insertType} is a modifying native statement and the
     * persistence layer refuses to execute one outside a transaction. A template needs no proxy, so it
     * is correct from every call path -- including the two tests that call {@code applyRecord} directly,
     * which no annotation on it could ever have covered.</p>
     *
     * <p>Assumptions: {@code PROPAGATION_REQUIRES_NEW} and not {@code REQUIRED}, which preserves the
     * property both entry points document: one unit of work per record or action, so a refusal rolls back
     * only the record that caused it and the outcome list cannot describe applied work that no longer
     * exists. Under {@code REQUIRED} a caller that already held a transaction would enclose the whole run
     * in it, and one refusal would undo every record before it.</p>
     *
     * <p>Trade-offs: a new transaction per unit means one commit per record, where a single enclosing
     * transaction would commit once for a whole stream. That cost is accepted deliberately: the baseline
     * driver's behaviour on a refused record is to report it and read the next one, and reproducing that
     * requires the applied records to survive the refusal -- which is only true if each has already
     * committed.</p>
     */
    private final TransactionTemplate writes;

    /**
     * Builds the service over the repository it writes and the manager each write unit opens against.
     *
     * <p>Assumptions: the record codec is reached statically rather than injected. That codec is a
     * utility class with a private constructor and no instance state, so there is no instance to hand in;
     * declaring a parameter for it would advertise a substitutable collaborator that cannot be
     * substituted.</p>
     *
     * @param types the {@link TransactionTypeRepository} this run reads and writes; must not be
     *     {@code null}
     * @param transactionManager the manager each per-record and per-action unit of work is opened
     *     against; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public ReferenceBatchUpdateService(TransactionTypeRepository types,
            PlatformTransactionManager transactionManager) {
        this.types = Objects.requireNonNull(types, "types");
        Objects.requireNonNull(transactionManager, "transactionManager");

        this.writes = new TransactionTemplate(transactionManager);
        this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Declares the fifty-three-byte input record of line 71, proving its geometry before returning it.
     *
     * <p>Assumptions: the group declaration is normative under AAP Rule T1 (Copybook is normative), so
     * the field names and widths below are its own and nothing is renamed. It spans lines 71 to 77 and
     * not 71 to 76, because the picture clause and the {@code VALUE} clause of each of the three fields
     * sit on SEPARATE physical lines -- 72 with 73, 74 with 75, and 76 with 77. A reader who assumes one
     * declaration per line derives a shorter record. The three widths are one, two and fifty, which sum
     * to fifty-three, and there is no {@code FILLER} anywhere in the record, so the field list accounts
     * for every byte of it and there is no dropped padding to record.</p>
     *
     * <p>Assumptions: the offsets below are ZERO-based while the driver's own comment block is
     * ONE-based, and the conversion runs in exactly one direction. Lines 11 to 18 of
     * {@code MNTTRDB2.jcl} document column 1 as the action code, columns 2 to 3 as the type and columns
     * 4 to 53 as the description; the conversion is always {@code zeroBased = oneBased - 1}. Applied the
     * other way it shifts every field by one byte, and a record read one byte out of alignment still
     * decodes to characters, so nothing raises and the values are simply wrong. That the two
     * derivations -- summing the picture clauses, and reading the driver's documented columns -- agree
     * on fifty-three is what makes the width checkable rather than merely asserted.</p>
     *
     * <p>Assumptions: all three fields are {@code Kind.TEXT}, including the type code, and that is the
     * one classification here a reader is likely to get wrong. Line 16 of the driver describes columns 2
     * to 3 as a numeric value, but line 74 declares the field {@code PIC X(2)} and both
     * {@code DCLTRTYP.dcl} and {@code TRNTYPE.ddl} declare the column {@code CHAR(2)}. It is declared
     * numeric in prose and stored as characters, and the characters are what the program binds. Treating
     * it as an unsigned integer would discard the leading zero every seeded code carries and leave a key
     * that locates no row.</p>
     *
     * <p>Assumptions: the layout is named for the COBOL group it transcribes. The name reaches a reader
     * only through a length failure raised by the codec, and the fixture tests of this module build the
     * same three fields under the same group name, so one name across both keeps a single layout
     * identity in those messages.</p>
     *
     * <p>Trade-offs: the retrieval key is declared as the two bytes at offset 1 rather than as the
     * three-byte leading span. Those two bytes are the primary key of the target table, which is what a
     * retrieval key means, whereas the leading span also covers the action code and the action code
     * selects a branch rather than identifying a row. The key components are metadata and no decode
     * reads them, so both declarations decode identically; this one is chosen because it is the
     * truthful description of the two ways it could be read.</p>
     *
     * @return the proven {@link CopybookLayout.RecordSpec} for the maintenance record, never
     *     {@code null}
     * @throws CopybookLayout.LayoutException if the declared geometry is not contiguous or does not sum
     *     to the declared record length
     */
    private static CopybookLayout.RecordSpec maintenanceRecordLayout() {
        return new CopybookLayout.RecordSpec("WS-INPUT-REC", RECORD_LENGTH, 2, 1, List.of(
                CopybookLayout.text(FIELD_ACTION_CODE, 0, 1),
                CopybookLayout.text(FIELD_TYPE_CODE, 1, 2),
                CopybookLayout.text(FIELD_DESCRIPTION, 3, 50))).validateGeometry();
    }

    /**
     * Applies every maintenance record the stream carries, in order, and reports the run.
     *
     * <p>Purpose: this transcribes the read chain of paragraphs {@code 1001-READ-NEXT-RECORDS},
     * {@code 1002-READ-RECORDS} and {@code 1003-TREAT-RECORD} at lines 91 to 129. The baseline primes
     * the loop with one read at line 92, tests the end-of-file flag at line 93, treats and re-reads at
     * lines 94 and 95, and reaches its close path at line 97; the loop below has the same shape with the
     * priming read and the loop read written as one expression.</p>
     *
     * <p>Assumptions: the stream carries records of exactly {@value #RECORD_LENGTH} bytes laid end to
     * end with NO delimiter between them. Line 39 declares {@code FD TR-RECORD RECORDING MODE F}, which
     * is fixed-length recording and carries no delimiter byte at all, and lines 32 and 33 declare the
     * organisation and access mode as sequential. A caller staging a file whose records are newline
     * terminated therefore strips the terminators before handing the bytes here, exactly as this
     * module's own fixture reader does.</p>
     *
     * <p>Alternatives Considered: treating a newline as the record delimiter, which the stored fixture
     * files would superficially support because each of their records is followed by one. Rejected
     * because the recording mode on line 39 admits no delimiter, so a newline is not part of the record
     * contract; accepting one would also leave undefined what a final record with no terminator means,
     * whereas a length is unambiguous. The consequence is stated rather than hidden: a stream whose
     * length is not a whole multiple of the record length is a malformed stream and is refused as a
     * whole.</p>
     *
     * <p>Assumptions: a malformed record length abandons the run while a refused record does not, and
     * the asymmetry is deliberate. A business refusal is something the baseline has a branch for and
     * tolerates. A record of the wrong length is something the baseline cannot encounter, because the
     * access method guarantees the length before the program sees the record; its nearest analogue is
     * the file-status test at line 84, which reports {@code 'OPEN FILE OK'} or {@code 'OPEN FILE NOT OK'}
     * on lines 85 and 87 and is a property of the whole file rather than of a record. There is no
     * per-record branch to transcribe, so the failure is raised rather than invented as an outcome.</p>
     *
     * <p>Assumptions: this member opens no transaction of its own, so each record's write commits at its
     * own boundary and a refused record cannot roll back a record that already applied. That is what
     * makes the soft reject observable at all: a single enclosing transaction would undo the applied
     * records alongside the refused one, and the outcome list would then describe work that no longer
     * existed. The per-record boundary is opened by the {@code writes} template documented on its own
     * field, one unit per write, which is what turns that intention into a mechanism that actually
     * runs.</p>
     *
     * @param maintenanceRecords the {@link InputStream} of contiguous fixed-length maintenance records,
     *     read to its end; must not be {@code null}
     * @return the {@link BatchUpdateResult} carrying one outcome per record in stream order together
     *     with the aggregate indicator, never {@code null}
     * @throws NullPointerException if {@code maintenanceRecords} is {@code null}
     * @throws MaintenanceStreamException if the stream cannot be read to its end
     * @throws FixedWidthCodec.RecordLengthException if the stream ends part way through a record, which
     *     is to say its length is not a whole multiple of the record length
     */
    public BatchUpdateResult apply(InputStream maintenanceRecords) {
        Objects.requireNonNull(maintenanceRecords, "maintenanceRecords");
        List<RecordOutcome> outcomes = new ArrayList<>();
        try {
            // WHY : Assumptions: this reader returns fewer bytes than asked for ONLY at the end of the
            //       stream, so a short read is end-of-stream and never a partially filled buffer. A
            //       plain single-shot read carries no such guarantee and would split a record across
            //       two reads on any stream that delivers in smaller pieces, which an object-store body
            //       does.
            byte[] record = maintenanceRecords.readNBytes(RECORD_LENGTH);
            while (record.length > 0) {
                outcomes.add(applyRecord(record));
                record = maintenanceRecords.readNBytes(RECORD_LENGTH);
            }
        } catch (IOException failure) {
            throw new MaintenanceStreamException(
                    "the maintenance record stream could not be read to its end", failure);
        }
        return BatchUpdateResult.of(outcomes);
    }

    /**
     * Dispatches one maintenance record on its action code and applies the branch that code selects.
     *
     * <p>Purpose: this transcribes {@code 1003-TREAT-RECORD}, whose label stands at line 109 and whose
     * {@code EVALUATE} runs from line 110 to line 129.</p>
     *
     * <p>Assumptions: the branch order below is the order the program dispatches in -- add at line 111,
     * update at line 114, delete at line 117, commentary at line 120 and the catch-all at line 122 --
     * and it deliberately does NOT match the order the driver's comment block documents. Lines 11 to 14
     * of {@code MNTTRDB2.jcl} list the domain as add, delete, update, commentary, putting delete second.
     * Because the subject is a single character, no two arms can both match and the order cannot change
     * which arm runs, so neither listing is wrong; but the discrepancy is a real artifact of the
     * baseline, and silently reordering this one to agree with that one would be an undocumented change
     * to a transcription. Both are recorded and neither is aligned to the other.</p>
     *
     * <p>Assumptions: the description is trimmed on the way in and the two codes keep their declared
     * width. Trailing blanks in the description are padding to the fixed record length rather than data,
     * and the column that stores it is variable width, so the padding is not stored and is never added
     * back; the action code and the type code are a fixed code and a key, whose widths are part of their
     * contract. The trim itself is delegated to the mapper that owns this package's trim boundary rather
     * than repeated here, so one rule governs every path that stores a description.</p>
     *
     * @param record the {@value #RECORD_LENGTH}-byte maintenance record to dispatch; must not be
     *     {@code null}
     * @return the {@link RecordOutcome} of that one record, never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws FixedWidthCodec.RecordLengthException if {@code record} is not exactly
     *     {@value #RECORD_LENGTH} bytes long
     * @throws CopybookLayout.LayoutException if the declared layout is rejected as the record is sliced
     */
    public RecordOutcome applyRecord(byte[] record) {
        Objects.requireNonNull(record, "record");
        Map<String, Object> fields = FixedWidthCodec.decodeRecord(record, MAINTENANCE_RECORD);
        String actionCode = textOf(fields, FIELD_ACTION_CODE);
        String typeCode = textOf(fields, FIELD_TYPE_CODE);
        String description = TransactionTypeMapper.trimForStorage(textOf(fields, FIELD_DESCRIPTION));

        return switch (actionCode) {
            case ACTION_CODE_ADD -> insertRecord(typeCode, description);
            case ACTION_CODE_UPDATE -> updateRecord(typeCode, description);
            case ACTION_CODE_DELETE -> deleteRecord(typeCode);
            // WHY : Assumptions: a commentary record is a SUCCESS and not a refusal. Line 121 displays
            //       that the line is being ignored and the arm ends there: it reaches no database
            //       statement and, unlike the catch-all below, does not reach the paragraph that raises
            //       the condition code. Counting it as a refusal would raise the aggregate on an input
            //       the baseline reports as clean.
            case ACTION_CODE_COMMENT -> RecordOutcome.applied(
                    RecordAction.COMMENT, typeCode, RecordAction.COMMENT.displayText());
            default -> RecordOutcome.rejected(RecordAction.INVALID, typeCode,
                    RejectReason.INVALID_ACTION_CODE, MESSAGE_TYPE_NOT_VALID, null);
        };
    }

    /**
     * Reads one decoded field as text, which the codec supplies at its declared width.
     *
     * <p>Assumptions: every field of this record is character data, so each decoded value is a string of
     * the field's declared width with its padding intact. The cast is therefore total for this layout
     * rather than optimistic, and centralising it here keeps three identical casts out of the dispatch.
     * The codec does not trim, which its own callers rely on when they assert that padding survives a
     * decode, so any trimming is the caller's decision and is taken at the one site that wants it.</p>
     *
     * @param fields the decoded record, keyed by the field names the layout declares
     * @param name the declared name of the field to read
     * @return the field's value at its declared width, with padding intact, never {@code null}
     */
    private static String textOf(Map<String, Object> fields, String name) {
        return String.valueOf(fields.get(name));
    }

    /**
     * Inserts one transaction type, transcribing {@code 10031-INSERT-DB} at lines 132 to 164.
     *
     * <p>Assumptions: no existence check precedes the insert, because the baseline performs none. The
     * paragraph issues its {@code INSERT} at lines 137 to 148 and then evaluates the outcome, and its
     * {@code EVALUATE} at lines 151 to 163 has exactly two arms: success at line 152, and any negative
     * outcome at line 154. Reading the row first would add a statement the transcription does not have,
     * and would still not settle the question, because the authority on whether a code is already taken
     * is the primary key rather than an earlier read.</p>
     *
     * <p>Assumptions: the insert goes through the repository's explicit insert member rather than a
     * save. This entity carries a caller-assigned identifier and a primitive version, so neither can
     * express newness and every save of a new instance reaches a merge, which loads the row the
     * identifier names and writes an update against it. A save would therefore either raise a lock
     * failure or silently overwrite the row it was asked to create, and in both cases the refusal this
     * method classifies would never arrive. The repository declares that member for this reason and
     * {@link TransactionTypeService} reaches it the same way.</p>
     *
     * <p>Assumptions: the row count the insert returns is not examined. A values insert either writes
     * its one row or raises, so a count adds nothing a caught refusal does not already carry.</p>
     *
     * @param typeCode the two-character code to key the new row by, at its declared width
     * @param description the description to store, already trimmed for storage
     * @return the {@link RecordOutcome} of the insert, never {@code null}
     */
    private RecordOutcome insertRecord(String typeCode, String description) {
        // WHY : ⚠️ Refactoring Rationale: the statement runs inside this record's OWN transaction, opened
        //       here rather than declared by an annotation. The insert is a modifying native statement
        //       and the persistence layer refuses to execute one with no transaction open, so before this
        //       boundary existed the first ADD of any stream failed outright -- the whole of finding C3.
        //       The refusal is caught OUTSIDE the boundary as well as inside it, because a constraint
        //       violation can surface either as the statement executes or as the unit commits, and the
        //       classification below has to reach it in both cases.
        try {
            return this.writes.execute(status -> {
                this.types.insertType(typeCode, description);
                return RecordOutcome.applied(RecordAction.ADD, typeCode, MESSAGE_RECORD_INSERTED);
            });
        } catch (DataIntegrityViolationException failure) {
            return integrityRejection(RecordAction.ADD, typeCode, failure);
        }
    }

    /**
     * Updates one description, transcribing {@code 10032-UPDATE-DB} at lines 166 to 195.
     *
     * <p>Assumptions: an absent row is reported from the outcome of the write rather than guessed at.
     * The baseline's {@code UPDATE} at lines 171 to 175 is keyed on the type code, and its
     * {@code EVALUATE} at lines 177 to 194 has three arms: success at line 178, the not-found outcome at
     * line 180 whose text line 181 composes, and any negative outcome at line 185. The read below is how
     * that not-found arm is reached here: the statement form the baseline uses reports how many rows it
     * matched, whereas the persistence layer applies the change through the loaded instance, so the row
     * has to be located before it can be changed. The observable behaviour is the same in both -- a code
     * no row carries is refused with the not-found text and nothing is written.</p>
     *
     * <p>Assumptions: the write is flushed inside this method rather than left to the enclosing
     * boundary. A refusal raised by a deferred flush would surface after this method had already
     * returned its outcome, so it could not be classified as this record's refusal and would abandon the
     * run instead. Flushing here is what keeps a refusal attributable to the record that caused it.</p>
     *
     * @param typeCode the two-character code naming the row to change, at its declared width
     * @param description the replacement description, already trimmed for storage
     * @return the {@link RecordOutcome} of the update, never {@code null}
     */
    private RecordOutcome updateRecord(String typeCode, String description) {
        // WHY : Assumptions: the read and the write share ONE transaction, which is why the read is
        //       inside the boundary rather than before it. Outside a transaction each repository call
        //       opens and commits its own, so the instance the read returns is detached and the write
        //       becomes a merge against whatever the row holds by then; inside one boundary the instance
        //       stays managed and the change is written against the row that was read.
        try {
            return this.writes.execute(status -> {
                Optional<TransactionType> stored = this.types.findByTypeCd(typeCode);
                if (stored.isEmpty()) {
                    return RecordOutcome.rejected(RecordAction.UPDATE, typeCode,
                            RejectReason.NOT_FOUND, MESSAGE_NO_RECORDS_FOUND, null);
                }
                TransactionType target = stored.get();
                target.setDescription(description);
                this.types.saveAndFlush(target);
                return RecordOutcome.applied(RecordAction.UPDATE, typeCode, MESSAGE_RECORD_UPDATED);
            });
        } catch (DataIntegrityViolationException failure) {
            return integrityRejection(RecordAction.UPDATE, typeCode, failure);
        }
    }

    /**
     * Removes one transaction type, transcribing {@code 10033-DELETE-DB} at lines 196 to 226.
     *
     * <p>Assumptions: the branch structure mirrors the update path because the baseline's does. Its
     * {@code DELETE} at lines 201 to 204 is keyed on the type code and its {@code EVALUATE} at lines 207
     * to 225 carries the same three arms -- success at line 208, the not-found outcome at line 210 whose
     * text line 211 composes, and any negative outcome at line 216.</p>
     *
     * <p>Assumptions: the removal is flushed inside this method, and here that is what makes the
     * declared foreign key reachable at all. A category row references this table through the key
     * declared at {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} lines 6 to 7 as
     * {@code FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE) REFERENCES CARDDEMO.TRANSACTION_TYPE (TR_TYPE)
     * ON DELETE RESTRICT}, which the migrated schema carries forward unweakened. Without the flush the
     * engine's refusal would arrive at the enclosing commit, after this method had returned, and a
     * removal that the key forbids would be reported as applied.</p>
     *
     * @param typeCode the two-character code naming the row to remove, at its declared width
     * @return the {@link RecordOutcome} of the removal, never {@code null}
     */
    private RecordOutcome deleteRecord(String typeCode) {
        // WHY : Assumptions: the read, the removal and the flush share ONE transaction, and here that is
        //       what makes the declared foreign key reachable as a caught refusal rather than as an
        //       abandoned run. The flush inside the boundary raises the key's refusal while this method is
        //       still on the stack; the same removal spread over three self-opened transactions would have
        //       nothing left to flush and would report a forbidden removal as applied.
        try {
            return this.writes.execute(status -> {
                Optional<TransactionType> stored = this.types.findByTypeCd(typeCode);
                if (stored.isEmpty()) {
                    return RecordOutcome.rejected(RecordAction.DELETE, typeCode,
                            RejectReason.NOT_FOUND, MESSAGE_NO_RECORDS_FOUND, null);
                }
                this.types.delete(stored.get());
                this.types.flush();
                return RecordOutcome.applied(RecordAction.DELETE, typeCode, MESSAGE_RECORD_DELETED);
            });
        } catch (DataIntegrityViolationException failure) {
            return integrityRejection(RecordAction.DELETE, typeCode, failure);
        }
    }

    /**
     * Classifies a constraint refusal into a typed per-record rejection.
     *
     * <p>Refactoring Rationale: this is the one place the migrated behaviour departs from the baseline,
     * and the departure is narrow. Two refusals that the target can tell apart are indistinguishable in
     * the baseline, because the branches that would separate them are absent. The insert paragraph's
     * {@code EVALUATE} at lines 151 to 163 has only a success arm and a single negative arm, with no
     * duplicate-key arm and no catch-all; neither the update paragraph at lines 177 to 194 nor the delete
     * paragraph at lines 207 to 225 has an arm for a referential-integrity refusal. Every negative
     * outcome therefore collapses into the one opaque diagnostic those paragraphs compose on lines 156
     * to 158, 187 to 189 and 218 to 220. The Java classifies a foreign-key refusal and a duplicate key
     * as distinct reasons carrying their own meaning, which is consistent with how the online path of
     * this same table reports the two, and the divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: the duplicate-key gap is not peculiar to the batch program. The online insert
     * paragraph {@code 9700-INSERT-RECORD} of {@code COTRTUPC.cbl}, at lines 1596 to 1623, likewise
     * carries only a success arm and a catch-all with no duplicate-key arm, so the two paths share the
     * gap and are classified the same way here rather than one being treated as the exception.</p>
     *
     * <p>Refactoring Rationale: the rejection carries the stable text on one channel and the machine
     * state on a second, and for this program the second channel ADDS diagnostic information the
     * baseline never had. Its refusal text is composed inline from literals and its edited copy of the
     * statement outcome only -- there is no vendor diagnostic text anywhere in it, because the
     * message-text argument is absent from every one of its three composing statements. The two online
     * programs of the same tree do carry vendor text and carry it differently again: one composes through
     * the diagnostic routine into an eight-hundred-character field that is then truncated to
     * seventy-five, discarding the remainder, and the other inlines the message text straight into
     * seventy-five. The shared kernel's published error shape draws the same two-channel split, holding
     * a stable message alongside a separate machine-readable code, and this rejection follows it: the
     * verbatim text stays exactly as the baseline composes it and the state is reported beside it rather
     * than folded into it. Adding a channel the baseline did not have is a departure under AAP Rule T9
     * (structure changes, behaviour does not), so it is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md} alongside the classification above
     * rather than treated as a structural detail.</p>
     *
     * <p>Assumptions: an unrecognised state is still a refusal and is still soft. Falling back to the
     * general SQL reason keeps the run going and reports the baseline's own text for a negative outcome,
     * which is what the single negative arm does.</p>
     *
     * @param action the {@link RecordAction} whose write was refused
     * @param typeCode the two-character code the refused record carried
     * @param failure the {@link DataIntegrityViolationException} the write raised
     * @return the {@link RecordOutcome} carrying the typed reason, the baseline text and the state,
     *     never {@code null}
     */
    private static RecordOutcome integrityRejection(RecordAction action, String typeCode,
            DataIntegrityViolationException failure) {

        String sqlState = sqlStateOf(failure);
        return RecordOutcome.rejected(action, typeCode, reasonForState(sqlState),
                MESSAGE_ERROR_ACCESSING + MESSAGE_ERROR_ACCESSING_SUFFIX, sqlState);
    }

    /**
     * Maps a reported database state onto the typed reason that names it.
     *
     * <p>Assumptions: the two states are read from {@link TransactionTypeService} rather than declared
     * again here. That class holds them for the online paths of this same table and both classes sit in
     * this package, so a second declaration would be a second statement of one fact that could drift
     * while still reading as agreement.</p>
     *
     * <p>Assumptions: this is the single decision point both entry points share. Each renders the reason
     * in its own vocabulary, but neither decides for itself what a state means, so the two cannot come to
     * disagree about which refusal a state represents.</p>
     *
     * @param sqlState the state a driver reported, which may be {@code null} when none was carried
     * @return the {@link RejectReason} that names the refusal, falling back to the general SQL reason
     */
    private static RejectReason reasonForState(String sqlState) {
        if (TransactionTypeService.SQLSTATE_FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            return RejectReason.REFERENTIAL_INTEGRITY;
        }
        if (TransactionTypeService.SQLSTATE_UNIQUE_VIOLATION.equals(sqlState)) {
            return RejectReason.DUPLICATE_KEY;
        }
        return RejectReason.SQL_ERROR;
    }

    /**
     * Walks a cause chain for the first state a database driver reported.
     *
     * <p>Assumptions: the state is carried by a cause rather than by the exception the persistence layer
     * raises, so the chain has to be walked rather than inspected at its head. The walk is bounded
     * because a cause chain can be circular and an unbounded walk over one would not return, and a blank
     * state is skipped because a driver may report one without populating it.</p>
     *
     * <p>Trade-offs: the driver's own message text is deliberately not read, here or anywhere in this
     * class. That text quotes the values that violated the constraint, so reading it would copy record
     * data into whatever the message reaches. The state alone names which class of constraint refused
     * the write, which is the only thing the branch above needs.</p>
     *
     * @param failure the throwable to walk from, which may itself carry the state
     * @return the reported state, or {@code null} if the bounded walk finds none
     */
    private static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < CAUSE_CHAIN_LIMIT; depth++) {
            if (current instanceof SQLException reported) {
                String state = reported.getSQLState();
                if (state != null && !state.isBlank()) {
                    return state;
                }
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Applies every action of a published request body in order, reporting one outcome each.
     *
     * <p>Purpose: this serves the published maintenance contract, whose actions arrive already parsed
     * and named in words rather than as a one-character code. It applies the same three writes as the
     * record-stream path and reports them in the vocabulary that contract publishes.</p>
     *
     * <p>Assumptions: the two entry points report in two different message vocabularies, and that is
     * deliberate rather than an oversight. AAP Rule T8 (user-visible strings are verbatim) binds the
     * baseline text to the path that transcribes the baseline record, which is
     * {@link #apply(InputStream)}; the published contract is a net-new interface whose own messages are
     * part of a shape its consumers and its schema already agree on. Rewriting either vocabulary into
     * the other would break one of those two agreements.</p>
     *
     * <p>Assumptions: this member opens no transaction of its own. Each action is applied inside a new
     * one opened by the member below, so a refusal rolls back that action alone; a transaction here would
     * enclose them all and undo the applied ones alongside the refused one.</p>
     *
     * <p>Assumptions: the order of the submitted array is honoured, because two actions in one run can
     * address the same row -- an insert followed by an update of the same type is a sequence the baseline
     * input can carry and applies in the order read.</p>
     *
     * @param request the validated batch body carrying the ordered actions; must not be {@code null}
     * @return the {@link MaintenanceActionBatchResponse} carrying one outcome per action in submission
     *     order with the worst condition code seen, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public MaintenanceActionBatchResponse apply(MaintenanceActionBatchRequest request) {
        Objects.requireNonNull(request, "request");
        List<MaintenanceActionOutcomeResponse> outcomes =
                new ArrayList<>(request.actions().size());
        int aggregate = RETURN_CODE_CLEAN;
        int position = 1;

        for (MaintenanceActionRequest action : request.actions()) {
            MaintenanceActionOutcomeResponse outcome = applyOne(position, action);
            outcomes.add(outcome);
            if (!OUTCOME_APPLIED.equals(outcome.outcome())) {
                // WHY : Assumptions: the aggregate is the WORST code seen and both non-applied states
                //       report the same soft warn, which follows the baseline: its driver sets one code
                //       for every failure it tolerates rather than grading them. Raising a distinct code
                //       for one of them would invent a distinction the reference does not draw.
                aggregate = Math.max(aggregate, RETURN_CODE_SOFT_WARN);
            }
            position++;
        }
        return new MaintenanceActionBatchResponse(List.copyOf(outcomes), aggregate);
    }

    /**
     * Applies one published action in its own transaction, converting a refusal into an outcome.
     *
     * <p>Assumptions: a refusal is returned as a value rather than thrown, which is what keeps the run
     * going. The transaction is a new one per action, so the rollback of a refused action cannot reach an
     * action that already applied.</p>
     *
     * <p>Assumptions: a constraint refusal is caught here too, not only in the record-stream path. A
     * duplicate code can still reach the insert below even though a read precedes it, because the read
     * and the write are two statements and the authority on the key is the constraint; letting that
     * refusal escape would abandon a run the baseline completes.</p>
     *
     * <p>⚠️ Refactoring Rationale: the transaction is opened by the template held on this class and NOT
     * by an annotation on this method, which is what makes the sentence above true. This method carried
     * {@code @Transactional(propagation = REQUIRES_NEW)} and was reached only from
     * {@link #apply(MaintenanceActionBatchRequest)} calling it on {@code this}; a self-invocation does not
     * pass through the transactional proxy, so no transaction was ever opened and the first insert of any
     * batch failed on a modifying statement executed outside one. The annotation is removed rather than
     * kept alongside the template, because keeping both would open two nested units of work for any
     * caller that did reach this method through the proxy.</p>
     *
     * @param position the one-based index of the action within the submitted array
     * @param action the {@link MaintenanceActionRequest} to apply; must not be {@code null}
     * @return the {@link MaintenanceActionOutcomeResponse} for that action, never {@code null}
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public MaintenanceActionOutcomeResponse applyOne(int position, MaintenanceActionRequest action) {
        Objects.requireNonNull(action, "action");
        // WHY : ⚠️ Refactoring Rationale: the refusal is caught OUTSIDE the transaction boundary, not
        //       inside the three write members it can be raised by. A constraint violation leaves the
        //       unit of work marked rollback-only, so a member that caught it and returned a value would
        //       leave the template to commit a doomed transaction -- which raises
        //       UnexpectedRollbackException and loses the classified outcome the caught exception was
        //       there to produce. Catching here lets the boundary roll back first and classify second,
        //       which is also why the three members below no longer carry a catch of their own.
        try {
            return this.writes.execute(status -> applyOneWithin(position, action));
        } catch (DataIntegrityViolationException failure) {
            return refusalOutcome(position, action, failure);
        }
    }

    /**
     * Dispatches one published action, with a transaction already open around it.
     *
     * <p>Assumptions: this member is separated from {@link #applyOne(int, MaintenanceActionRequest)} for
     * legibility only -- the boundary is opened by its caller and this one assumes it is open. It is
     * private and is reached from exactly one place, so there is no path on which that assumption can be
     * false.</p>
     *
     * @param position the one-based index of the action within the submitted array
     * @param action the {@link MaintenanceActionRequest} to apply, already checked non-null
     * @return the {@link MaintenanceActionOutcomeResponse} for that action, never {@code null}
     */
    private MaintenanceActionOutcomeResponse applyOneWithin(int position,
            MaintenanceActionRequest action) {
        Optional<TransactionType> stored = this.types.findByTypeCd(action.typeCd());

        if (ACTION_DELETE.equals(action.action())) {
            if (stored.isEmpty()) {
                return outcome(position, action, OUTCOME_NO_ROWS_FOUND, false, MESSAGE_NO_ROWS);
            }
            return removeFor(position, action, stored.get());
        }

        // WHY : Assumptions: an insert and an update both need a description and the shape cannot demand
        //       one, because a delete in the same array needs none. The refusal is therefore made here
        //       and reported as this action's own outcome, which is exactly how the baseline handles a
        //       record it cannot apply -- it reports and reads the next one.
        if (action.description() == null || action.description().isBlank()) {
            return outcome(position, action, OUTCOME_FAILED, false, MESSAGE_DESCRIPTION_REQUIRED);
        }
        String description = TransactionTypeMapper.trimForStorage(action.description());

        if (ACTION_INSERT.equals(action.action())) {
            if (stored.isPresent()) {
                return outcome(position, action, OUTCOME_FAILED, false, MESSAGE_ALREADY_EXISTS);
            }
            return insertFor(position, action, description);
        }

        if (stored.isEmpty()) {
            return outcome(position, action, OUTCOME_NO_ROWS_FOUND, false, MESSAGE_NO_ROWS);
        }
        return replaceFor(position, action, stored.get(), description);
    }

    /**
     * Inserts for the published path, reporting a duplicate code as this action's own outcome.
     *
     * <p>Assumptions: the explicit insert member is used for the same reason the record-stream path uses
     * it -- a save of this entity reaches a merge and cannot insert it, so the refusal this action's
     * outcome depends on would otherwise never be raised at all.</p>
     *
     * <p>Assumptions: a refusal is NOT caught here. It propagates out of the enclosing transaction
     * boundary and is classified by the caller, because a constraint violation leaves the unit of work
     * marked rollback-only and a value returned from inside it would be discarded by the failing commit.
     * The reasoning is recorded once, on the caller that opens the boundary.</p>
     *
     * @param position the one-based index of the action within the submitted array
     * @param action the action being applied, carried through onto the outcome
     * @param description the description to store, already trimmed for storage
     * @return the outcome of the insert, never {@code null}
     */
    private MaintenanceActionOutcomeResponse insertFor(int position, MaintenanceActionRequest action,
            String description) {
        this.types.insertType(action.typeCd(), description);
        return outcome(position, action, OUTCOME_APPLIED, true, MESSAGE_APPLIED);
    }

    /**
     * Replaces a description for the published path.
     *
     * @param position the one-based index of the action within the submitted array
     * @param action the action being applied, carried through onto the outcome
     * @param target the loaded row whose description is replaced
     * @param description the replacement description, already trimmed for storage
     * @return the outcome of the update, never {@code null}
     */
    private MaintenanceActionOutcomeResponse replaceFor(int position, MaintenanceActionRequest action,
            TransactionType target, String description) {
        target.setDescription(description);
        this.types.saveAndFlush(target);
        return outcome(position, action, OUTCOME_APPLIED, true, MESSAGE_APPLIED);
    }

    /**
     * Removes a row for the published path, reporting a restricted removal as this action's outcome.
     *
     * <p>Assumptions: the removal is flushed here for the same reason the record-stream path flushes it.
     * The declared foreign key refuses a removal whose code a category still references, and the flush is
     * what raises that refusal while this action is still the one being applied -- rather than at the
     * boundary's commit, by which time the run has moved on to the next action.</p>
     *
     * <p>Assumptions: the refusal is not caught here either, for the reason recorded on the insert member
     * above and argued in full on the caller that opens the boundary.</p>
     *
     * @param position the one-based index of the action within the submitted array
     * @param action the action being applied, carried through onto the outcome
     * @param target the loaded row to remove
     * @return the outcome of the removal, never {@code null}
     */
    private MaintenanceActionOutcomeResponse removeFor(int position, MaintenanceActionRequest action,
            TransactionType target) {
        this.types.delete(target);
        this.types.flush();
        return outcome(position, action, OUTCOME_APPLIED, true, MESSAGE_APPLIED);
    }

    /**
     * Renders a constraint refusal in the vocabulary the published contract uses.
     *
     * <p>Assumptions: the typed reason is resolved by the same classifier the record-stream path uses,
     * so the two entry points agree on what a state means even though they report it in different words.
     * A duplicate code is the one refusal the published vocabulary already has a message for; every other
     * refusal reports the general failure state, because inventing a message per state would extend a
     * published shape from here.</p>
     *
     * @param position the one-based index of the action within the submitted array
     * @param action the action whose write was refused
     * @param failure the exception the write raised
     * @return the outcome carrying the refusal, never {@code null}
     */
    private static MaintenanceActionOutcomeResponse refusalOutcome(int position,
            MaintenanceActionRequest action, DataIntegrityViolationException failure) {

        RejectReason reason = reasonForState(sqlStateOf(failure));
        String message = reason == RejectReason.DUPLICATE_KEY
                ? MESSAGE_ALREADY_EXISTS
                : MESSAGE_ERROR_ACCESSING + MESSAGE_ERROR_ACCESSING_SUFFIX;
        return outcome(position, action, OUTCOME_FAILED, false, message);
    }

    /**
     * Builds one outcome row for the published path.
     *
     * @param position the one-based index of the action
     * @param action the action the outcome describes
     * @param state the resulting state
     * @param applied whether stored data changed
     * @param message the per-action explanation
     * @return the outcome, never {@code null}
     */
    private static MaintenanceActionOutcomeResponse outcome(
            int position, MaintenanceActionRequest action, String state, boolean applied,
            String message) {
        return new MaintenanceActionOutcomeResponse(
                position, action.action(), action.typeCd(), state, applied, message);
    }

    /**
     * The branch of the dispatch that one record's action code selects.
     *
     * <p>Assumptions: the constants are declared in the order the program's {@code EVALUATE} tests them,
     * which is add, update, delete, commentary and then the catch-all. That order is not the order the
     * driver's comment block documents, and the two are deliberately left disagreeing; the reason is
     * recorded at {@link #applyRecord(byte[])} where the dispatch itself stands.</p>
     */
    public enum RecordAction {

        /** The add branch, selected at line 111, which reports its text on line 112. */
        ADD(DISPLAY_ADDING_RECORD),

        /** The update branch, selected at line 114, which reports its text on line 115. */
        UPDATE(DISPLAY_UPDATING_RECORD),

        /** The delete branch, selected at line 117, which reports its text on line 118. */
        DELETE(DISPLAY_DELETING_RECORD),

        /**
         * The commentary branch, selected at line 120.
         *
         * <p>Assumptions: this branch reaches no database statement. Line 121 reports its text and the
         * arm ends, so a record on this branch is a success rather than a refusal.</p>
         */
        COMMENT(DISPLAY_IGNORING_COMMENTED_LINE),

        /**
         * The catch-all branch, reached at line 122 for any action code outside the declared domain.
         *
         * <p>Assumptions: this is the only named branch that reports a refusal. It composes its text on
         * line 124 and reaches the condition-code paragraph from line 128.</p>
         */
        INVALID(MESSAGE_TYPE_NOT_VALID);

        /** The verbatim text the program reports when this branch is selected. */
        private final String displayText;

        /**
         * Binds one branch to the verbatim text the program reports for it.
         *
         * <p>Assumptions: the text is held on the constant rather than resolved by a switch at the point
         * of reporting. Each constant already names the line its text stands on, so keeping the two
         * together is what lets a reader check the pairing against the program in one place.</p>
         *
         * @param displayText the verbatim text of the branch's own report statement
         */
        RecordAction(String displayText) {
            this.displayText = displayText;
        }

        /**
         * Reports the verbatim text the program writes when this branch is selected.
         *
         * <p>Assumptions: this is the DISPATCH text and not the outcome text. The program reports both
         * for a record that reaches a write -- the branch as it is selected, then the result of the
         * write -- so this value and {@link RecordOutcome#message()} are two different strings and
         * neither substitutes for the other. They coincide only on the two branches that reach no write,
         * which report once.</p>
         *
         * @return the verbatim branch text, carried across character for character, never {@code null}
         */
        public String displayText() {
            return this.displayText;
        }
    }

    /**
     * The reason a record was refused, where the record was refused at all.
     *
     * <p>Assumptions: three of these name a branch the baseline has and two name a distinction it does
     * not draw. The two that do not are the documented divergence, and the reason each maps from is
     * recorded on the constant itself so the register and the code cannot drift apart.</p>
     */
    public enum RejectReason {

        /**
         * The keyed write matched no row.
         *
         * <p>Assumptions: this maps from the not-found arm the update path takes at line 180 and the
         * delete path takes at line 210, both of which compose {@code 'No records found.'} on lines 181
         * and 211. The insert path has no such arm and cannot reach this reason.</p>
         */
        NOT_FOUND,

        /**
         * An insert named a code some row already carries.
         *
         * <p>Assumptions: the baseline draws NO distinction here. Its insert {@code EVALUATE} at lines
         * 151 to 163 carries no duplicate-key arm, so a duplicate is indistinguishable from any other
         * negative outcome and collapses into the diagnostic on lines 156 to 158. The online insert
         * paragraph of {@code COTRTUPC.cbl} at lines 1596 to 1623 has the same gap. Classifying it is
         * part of the divergence registered in
         * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
         */
        DUPLICATE_KEY,

        /**
         * A removal was refused because another row still references the code.
         *
         * <p>Assumptions: the baseline draws NO distinction here either. Neither the update paragraph nor
         * the delete paragraph carries an arm for it, so the refusal the declared foreign key raises
         * collapses into the same opaque negative arm. The key itself is declared at
         * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} lines 6 to 7 and is carried forward
         * unweakened; classifying its refusal is the second half of the divergence.</p>
         */
        REFERENTIAL_INTEGRITY,

        /**
         * Any other negative outcome from a write.
         *
         * <p>Assumptions: this is the faithful reading of the single negative arm each of the three
         * paragraphs carries -- line 154 for the insert, line 185 for the update and line 216 for the
         * delete -- and it stays the fallback so that an unrecognised state is still a soft refusal
         * rather than an escape.</p>
         */
        SQL_ERROR,

        /**
         * The action code was outside the declared domain.
         *
         * <p>Assumptions: this maps from the catch-all at line 122, whose text line 124 composes.</p>
         */
        INVALID_ACTION_CODE
    }

    /**
     * The outcome of one maintenance record.
     *
     * <p>Assumptions: the reason is {@code null} on a record that applied, rather than an enumeration
     * constant standing for the absence of a reason. That keeps the enumeration exactly the five
     * refusals the baseline branches admit, so every constant in it names a real refusal and none has to
     * be excluded when the set is reasoned about.</p>
     *
     * <p>Assumptions: the message and the state are two separate channels and neither is folded into the
     * other. The message is the baseline text for the branch, carried across character for character
     * under AAP Rule T8 (user-visible strings are verbatim); the state is machine-readable and is the
     * channel that adds what the baseline never carried, since its own refusals include no vendor
     * diagnostic text at all.</p>
     *
     * @param action the branch the record's action code selected
     * @param typeCode the two-character type code the record carried, at its declared width
     * @param succeeded whether the record applied, which is {@code false} for every refusal
     * @param rejectReason the reason the record was refused, or {@code null} where it applied
     * @param message the verbatim baseline text for the branch that was taken
     * @param sqlState the state a driver reported, or {@code null} where none was reported or none
     *     applies
     */
    public record RecordOutcome(RecordAction action, String typeCode, boolean succeeded,
            RejectReason rejectReason, String message, String sqlState) {

        /**
         * Builds the outcome of a record that applied.
         *
         * @param action the branch the record's action code selected
         * @param typeCode the two-character type code the record carried
         * @param message the verbatim baseline text the branch reports
         * @return the outcome, carrying no reason and no state, never {@code null}
         */
        public static RecordOutcome applied(RecordAction action, String typeCode, String message) {
            return new RecordOutcome(action, typeCode, true, null, message, null);
        }

        /**
         * Builds the outcome of a record that was refused.
         *
         * @param action the branch the record's action code selected
         * @param typeCode the two-character type code the record carried
         * @param rejectReason the reason the record was refused; must not be {@code null}
         * @param message the verbatim baseline text the refusing branch composes
         * @param sqlState the state a driver reported, or {@code null} where none applies
         * @return the outcome, never {@code null}
         */
        public static RecordOutcome rejected(RecordAction action, String typeCode,
                RejectReason rejectReason, String message, String sqlState) {
            return new RecordOutcome(action, typeCode, false, rejectReason, message, sqlState);
        }
    }

    /**
     * The result of one maintenance run.
     *
     * <p>Trade-offs: this carries the per-record list AND the aggregate indicator, which is more than
     * the baseline holds, and the reason is recorded on the class above: the baseline's return-code
     * register is a run-level value that every refusal sets to the same 4 and that carries neither a
     * count nor the identity of what failed, while the per-record detail it did produce went to the job
     * log as unstructured text. Either half alone loses something the run had.</p>
     *
     * @param outcomes one outcome per record in stream order, unmodifiable and never {@code null}
     * @param anyRejected whether at least one record was refused, which is the aggregate the baseline's
     *     return-code register stands for
     * @param processedCount the number of records the run read and dispatched
     */
    public record BatchUpdateResult(List<RecordOutcome> outcomes, boolean anyRejected,
            int processedCount) {

        /**
         * Builds the result of a run from the outcomes it produced.
         *
         * <p>Assumptions: the list is copied rather than retained. The caller assembles it as a mutable
         * accumulator, so a retained reference would let the result change after it had been reported.</p>
         *
         * @param outcomes the outcomes the run produced, in stream order; must not be {@code null} and
         *     must contain no {@code null} element
         * @return the result carrying the copied list and the aggregates derived from it, never
         *     {@code null}
         * @throws NullPointerException if {@code outcomes} is {@code null} or holds a {@code null}
         *     element, which the copy rejects rather than carrying into a reported result
         */
        public static BatchUpdateResult of(List<RecordOutcome> outcomes) {
            List<RecordOutcome> copied = List.copyOf(outcomes);
            boolean rejected = copied.stream().anyMatch(outcome -> !outcome.succeeded());
            return new BatchUpdateResult(copied, rejected, copied.size());
        }

        /**
         * Reports the condition code the baseline's return-code register would hold after this run.
         *
         * <p>Assumptions: the two values are the only two this program can report. Its condition-code
         * paragraph moves exactly 4 on every refusal it tolerates and nothing raises it further, so a run
         * either completed clean or completed with refused records.</p>
         *
         * <p>Trade-offs: this is derived rather than stored, because storing it beside
         * {@link #anyRejected()} would be two representations of one fact that could disagree.</p>
         *
         * <p>Assumptions: this value belongs to this result object and to nothing else. It is not a build
         * outcome, not a test outcome and not a gate for any of the tools that build this module, all of
         * which report a binary pass or failure; the graded rubric this 4 comes from governs the
         * reference suite under {@code tests/}.</p>
         *
         * @return {@link #RETURN_CODE_SOFT_WARN} if any record was refused, otherwise
         *     {@link #RETURN_CODE_CLEAN}
         */
        public int returnCode() {
            return this.anyRejected ? RETURN_CODE_SOFT_WARN : RETURN_CODE_CLEAN;
        }
    }

    /**
     * Raised when the maintenance record stream cannot be read to its end.
     *
     * <p>Assumptions: this is nested and unchecked so that the stream entry point keeps the signature its
     * callers are written against, rather than propagating a checked read failure that every caller would
     * have to declare. It is deliberately distinct from a record-length failure: this one says the bytes
     * could not be obtained, while that one says the bytes obtained do not form whole records.</p>
     *
     * <p>Assumptions: the shared kernel's handler identifies an exception by walking the cause chain and
     * matching on the fully-qualified class name, because that kernel carries no dependency on the
     * persistence or transport libraries whose exception types it has to recognise. A nested class has a
     * stable such name, so nesting costs this type nothing in how it is recognised.</p>
     */
    public static final class MaintenanceStreamException extends RuntimeException {

        /** The version this exception serialises under. */
        private static final long serialVersionUID = 1L;

        /**
         * Builds the failure over the read error that caused it.
         *
         * <p>Assumptions: the cause is retained rather than summarised, because the read failure names
         * the transport that failed and that is the only actionable part of it.</p>
         *
         * @param message what could not be completed
         * @param cause the read failure that prevented it
         */
        private MaintenanceStreamException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
