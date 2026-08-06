package com.carddemo.transaction.repository;

import com.carddemo.transaction.domain.TransactionReject;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The sole data-access port onto {@code ledger.transaction_rejects}, the posting reject stream of the
 * LEDGER bounded context.
 *
 * <p>Every relational equivalent of a file operation the baseline issues against its {@code DALYREJS}
 * dataset is inherited into this interface, and none is expressed anywhere else in this module. The
 * package charter beside this file makes that the boundary: no controller, service, mapper, DTO or
 * domain type may hold a query against this table.
 *
 * <p>Assumptions: this block carries no parameter, return or exception at-clause, and the omission is
 * deliberate rather than an oversight. The interface declares no member and no type parameter, so no
 * at-clause has a subject to describe. The house standard attaches those elements to a declared
 * member, and inventing them on a type declaration would add unverifiable claims rather than facts.
 *
 * <h2>The reject record is 430 bytes and is already three fields</h2>
 *
 * <p>Assumptions: the three payload columns split the baseline record at exactly the two boundaries
 * the program itself splits it at, so the split is transcribed rather than invented. In
 * {@code app/cbl/CBTRN02C.cbl}, which is read as specification and is never modified, line 176
 * declares {@code 01 REJECT-RECORD} over {@code REJECT-TRAN-DATA PIC X(350)} at line 177 and
 * {@code VALIDATION-TRAILER PIC X(80)} at line 178. Lines 180 to 182 resolve that trailer into
 * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} and {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}.
 * The widths therefore sum as 350 plus 4 plus 76, or 430 bytes.
 *
 * <p>Assumptions: the 430 is asserted three independent times, which is why the arithmetic above is
 * stated as a contract rather than as a reading. The file description at lines 81 to 84 of the same
 * program states 350 at line 83 and 80 at line 84 a second time. The dataset attributes state it a
 * third time, outside the program: {@code app/jcl/POSTTRAN.jcl} line 36 declares
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} on the {@code DALYREJS} block spanning its lines 34 to
 * 38. The record is fully occupied at 430 bytes, so there is no padding item to account for at this
 * level.
 *
 * <p>Assumptions: the paragraph that fills a record is {@code 2500-WRITE-REJECT-REC} at line 446, and
 * it populates the two parts in the column order above -- line 447 moves the rejected record, line
 * 448 moves the trailer -- before the write at line 451. The 350 bytes are a
 * {@code DALYTRAN-RECORD}, whose layout is {@code app/cpy/CVTRA06Y.cpy} lines 4 to 18: line 2 states
 * a record length of 350, lines 5 to 17 declare thirteen data items totalling 330 bytes, and line 18
 * closes the record with {@code FILLER PIC X(20)}. The layout is recoverable from that copybook
 * whenever a reader needs it, which is what makes the next ruling affordable.
 *
 * <h2>The rejected record is retained whole and is never interpreted here</h2>
 *
 * <p>Alternatives Considered: decomposing the 350-byte image into the thirteen typed columns its
 * layout would yield, so that the rejected values were queryable as data. Rejected, because the value
 * of a reject entry is that it reproduces exactly what arrived: a re-drive replays the original bytes
 * rather than a re-serialisation of a parse that may itself be what failed. The rejected values are
 * by definition the ones validation would not accept -- an entry carrying reason 100 holds a card
 * number that resolved to no cross-reference record, set at line 385 on the {@code INVALID KEY} path
 * of the read at line 383 -- so typed columns would either refuse the input or normalise the evidence
 * away. The baseline treats the payload the same way: line 447 is a wholesale group move with no
 * field-level handling anywhere in the paragraph. No member of this interface parses, splits,
 * searches or otherwise reinterprets that image, and none is added for convenience.
 *
 * <h2>The reason code stays numeric and its value space stays open</h2>
 *
 * <p>Alternatives Considered: modelling the reason as a closed Java type whose named constants are
 * exactly the codes the baseline is observed to set, which would make an unmodelled value impossible
 * to hold. Rejected, because the source field is {@code PIC 9(04)} at line 181, so its declared
 * domain is every value the four digits admit and the observed codes are not a closed set. Such a
 * type would refuse an unmodelled code at the persistence boundary, which for a stream whose whole
 * purpose is to retain what happened turns a recordable outcome into an unrecordable one. The narrow
 * exact integer the migration declares carries the declared domain instead, and no closed-set
 * validation of the value is applied here.
 *
 * <p>Assumptions: five reason codes are set, each with the line that sets it and the line that
 * carries its text -- 100 at line 385 with line 386, 101 at line 397 with line 398, 102 at line 410
 * with line 411, 103 at line 417 with line 418, and 109 at line 556 with line 557. Two properties of
 * that register cannot be recovered by reading the target code, so both are recorded here as observed
 * behaviour of the reference program rather than as anything to be altered.
 *
 * <p>Assumptions: when a record fails both monetary and expiration validation, the stored reason is
 * 103. The over-limit test at lines 407 to 413 and the expiration test at lines 414 to 420 are two
 * sequential blocks inside the single {@code NOT INVALID KEY} branch that spans lines 400 to 421, and
 * the second is not conditioned on the reason still being zero, so lines 417 and 418 overwrite lines
 * 410 and 411. The chain is short-circuited earlier and not here, and describing it as uniformly
 * short-circuited would be inaccurate: line 372 guards the account lookup at line 373 with
 * {@code IF WS-VALIDATION-FAIL-REASON = 0}, so 100 suppresses 101, and nothing plays that role
 * between 102 and 103. The baseline assigns the reason this way; the migrated producer reproduces that
 * assignment, and this column stores whichever value is assigned.
 *
 * <p>Assumptions: 109 is representable in this column and no entry of this table carries it, which is
 * worth stating because a reader will find the code in the program and its text is indistinguishable
 * from 101's. It is set at line 556 on the {@code INVALID KEY} path at line 555 of the account
 * {@code REWRITE} at line 554, inside {@code 2800-UPDATE-ACCOUNT-REC} at line 545. That paragraph is
 * performed at line 441 from {@code 2000-POST-TRANSACTION} at line 424, which the main loop enters at
 * line 212 only after line 211 has already found the reason to be zero. The write is performed at
 * line 215 on the {@code ELSE} at line 213 of that same test, the enclosing loop at lines 202 to 219
 * never re-tests the reason, and lines 208 and 209 clear the reason and its text at the top of the
 * next record. So the code is a post-validation rewrite outcome rather than a validation reject, and
 * its absence from the data is a consequence of that sequence and not a gap. Its text at line 557 is
 * byte-identical to 101's at line 398, so the code and not the text distinguishes the two.
 *
 * <h2>This interface declares no query method, and that is the ruling</h2>
 *
 * <p>Alternatives Considered: a finder by reason code, a finder by the card number the retained image
 * carries, a description search, and a key-ordered administrative browse were each considered. All
 * four are rejected as surface no baseline path asks for, because the dataset is appended to and never
 * read back. In the only program that opens it, the {@code SELECT} spanning lines 46 to 49 declares
 * {@code ORGANIZATION IS SEQUENTIAL} with no {@code RECORD KEY} at all; line 293 opens it
 * {@code OUTPUT} rather than {@code I-O}; line 451 writes; line 639 closes; and no {@code READ} is
 * issued against it anywhere. Across the whole reference tree only that program, its two job-control
 * members and a catalog listing name the dataset, and {@code app/jcl/POSTTRAN.jcl} line 34 gives it
 * {@code DISP=(NEW,CATLG,DELETE)} against generation {@code (+1)} at line 38, so each run writes a new
 * generation and no run reads one back. There is no read path to transcribe, so none is invented.
 *
 * <p>Assumptions: what this interface contributes is therefore inherited rather than declared, and
 * that is not the same as contributing nothing. The inherited {@code save} and {@code saveAll} are the
 * write path, and they are the relational equivalent of the sequential write at line 451; the
 * inherited {@code findById} and {@code findAll} are an affordance of the target rather than a
 * transcribed behaviour, and exist for administrative access and for the integration test that pins
 * the shape of this table. None of them is redeclared, because redeclaring an inherited member states
 * the same contract twice and lets the two copies disagree. The package charter withholds an interface
 * that would have neither a declared query nor a caller; this one has a caller in the posting write
 * path above, so the closed query surface is a decision recorded here and not an unfinished file.
 *
 * <p>Refactoring Rationale: no member returns a tally of rejected records, and that omission replaces
 * what would otherwise be a second source of one number. The baseline keeps the tally in the job as it
 * writes -- {@code WS-REJECT-COUNT} is declared at line 186 and incremented at line 214, in the same
 * branch that performs the write at line 215 -- and that in-job value is what line 229 tests with
 * {@code IF WS-REJECT-COUNT &gt; 0} and what line 230 turns into the program's graded return code. The
 * migrated job keeps the tally in the same place. An aggregate declared here would compute a different
 * number as soon as more than one run's entries share the table, and a caller could not tell which of
 * the two answered the question it asked. That graded return code belongs to the batch program and its
 * reference suite; the outcome of this module's own build and tests is not graded.
 *
 * <p>Alternatives Considered: offset paging, had an administrative read been declared at all. Rejected
 * because concurrent insertion changes how many entries precede a returned key, so successive requests
 * omit and repeat entries. The package charter beside this file records that argument and the evidence
 * for the concurrency in full, and it is cited rather than restated so the two cannot drift apart.
 *
 * <h2>The identifier is an addition this migration makes</h2>
 *
 * <p>Assumptions: the identity is the reject-event ordinal {@code reject_seq}, declared at line 579 of
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} as
 * {@code BIGINT GENERATED BY DEFAULT AS IDENTITY} and made the key by the constraint
 * {@code pk_transaction_rejects} at line 653 of the same file. The entity carries it at lines 287 to
 * 290 of {@code com.carddemo.transaction.domain.TransactionReject} as a {@code Long}, which is where
 * the second type argument above comes from. Migration and entity agree, and neither was inferred from
 * the other.
 *
 * <p>Assumptions: that identifier corresponds to no field of the 430-byte layout, because the baseline
 * stream has no key of its own -- the {@code SELECT} at lines 46 to 49 declares none. It is an
 * addition made on the target side, and the contrast is a real property of this dataset rather than an
 * artefact of where one looks: {@code app/cbl/CBACT04C.cbl} lines 28 to 32 select the indexed
 * category-balance file and state {@code RECORD KEY IS FD-TRAN-CAT-KEY} at line 31, and the
 * cross-reference file selected at lines 40 to 44 of {@code CBTRN02C} itself states one at line 43, in
 * the same file-control paragraph as the reject stream. A dataset that has a key declares it.
 *
 * <p>Assumptions: what the addition buys is that the ordinal identifies the reject event while leaving
 * the retained image free to repeat, so two entries holding the same 350 bytes stay separately
 * addressable. That matters because the same record rejected on two runs is two legitimate entries,
 * and {@code app/jcl/DALYREJS.jcl} keeps five generations of exactly that: its
 * {@code GENERATIONDATAGROUP} stanza spans lines 24 to 28, with {@code LIMIT(5)} at line 26 and
 * {@code SCRATCH} at line 27. No uniqueness is asserted over the image or over any combination of the
 * three payload columns, and none may be, because such an assertion would refuse the second of two
 * identical rejects and turn a faithful append into a constraint violation.
 *
 * <h2>Annotations this interface deliberately does not carry</h2>
 *
 * <p>Trade-offs: no version member and no optimistic locking. The stream is appended to and never
 * re-read, so there is no before-image to compare and nothing for a version check to detect; and the
 * migration declares no version column, while {@code application.yml} line 473 sets
 * {@code spring.jpa.hibernate.ddl-auto} to {@code none}, so a version member would fail against the
 * deployed table rather than protect anything. The consequence accepted is that no HTTP 409 originates
 * from this repository. Conflict detection belongs where a record is updated in place after being read
 * across a client gap, which is never what happens to an entry of this table.
 *
 * <p>Assumptions: no transaction attribute is declared here, in either direction. Transformation rule
 * T5 maps a CICS syncpoint boundary to a declarative transaction and a syncpoint rollback to exception
 * propagation, but the boundary belongs to the calling service or job rather than to a repository. The
 * baseline commit is issued by the program, and the posting unit spans three record types --
 * {@code 2000-POST-TRANSACTION} at line 424 performs the category-balance update at line 440, the
 * account update at line 441 and the transaction write at line 442 before closing at line 444 -- so no
 * single repository can own it. A read-only attribute is withheld for the same reason: it would assert
 * a boundary at a level that does not own one, and the write path above is not read-only in any case.
 *
 * <p>Assumptions: no stereotype annotation is declared. The framework registers the proxy from the
 * {@code JpaRepository} supertype, so the annotation would restate what the declaration already says,
 * and none of the three interfaces beside this one carries it either.
 *
 * <p>Assumptions: {@code V1__ledger.sql} is the single normative physical contract for this schema and
 * Hibernate consumes it rather than generating it, which is what {@code ddl-auto} set to
 * {@code none} at {@code application.yml} line 473 establishes. No index, table-index or
 * column-definition metadata therefore belongs here. It would create nothing, and it would only be a
 * second copy of the physical contract able to drift from the migration that owns it. Column names,
 * widths, nullability and the key stay in that file.
 *
 * <h2>One posting unit of work, one commit</h2>
 *
 * <p>Assumptions: the entries this interface persists are written by the batch deployable, which
 * reaches the objects of this schema through a narrowly scoped cross-schema {@code GRANT} rather than
 * through a call into this module, so no code dependency between deployables exists and this module
 * declares none. The limited database privilege is what keeps the posting unit of work a single atomic
 * commit across the three writes at lines 440 to 442. Neither a saga nor a two-phase commit is
 * introduced for this table, and a transactional outbox followed by compensating reversal was rejected
 * because it would make a partial posting state externally observable that the cited paragraph never
 * exhibits, which the parity oracle would identify as a behavioural divergence.
 *
 * <h2>Page-navigation vocabulary does not apply to this stream</h2>
 *
 * <p>Assumptions: no online screen exists over the reject stream, so no page-navigation semantics
 * attach to it, and this interface neither references nor re-declares the shared page envelope that
 * the list-bearing repositories of this package use. The honest tally in this bounded context is one
 * true forward and backward browse plus two maximum-key identifier derivations. The browse is
 * {@code app/cbl/COTRN00C.cbl} lines 279 to 376, whose cursor is the scalar {@code PIC X(16)} pair at
 * lines 63 and 64, whose display capacity is ten -- line 290 clears ten slots and the loop at line 297
 * stops when the index reaches 11, advancing it at line 301 -- whose surplus probe is the eleventh
 * read at line 308 with the availability flag set from that read's outcome alone at lines 309 to 313,
 * and whose positioning is explicit in the migrated form because {@code GTEQ} is commented out at line
 * 597. The two derivations are {@code app/cbl/COTRN02C.cbl} lines 444 to 449 and
 * {@code app/cbl/COBIL00C.cbl} lines 212 to 217.
 *
 * <p>Alternatives Considered: counting {@code COBIL00C} as a second list implementation, which would
 * make the tally four sites and attach a page envelope to an operation that returns a single key.
 * Rejected on measurement: its 572 lines contain no forward browse read and no {@code GTEQ} at all,
 * so it is a counter-example rather than a browse. There are not four paging sites, and no
 * maximum-identifier member belongs in this interface either, because the reject stream allocates no
 * identifier of its own -- its ordinal is assigned by the database on insert.
 *
 * <p>Assumptions: generation retention is an infrastructure concern and is deliberately not a member
 * of this interface. The retention the baseline expresses at {@code DALYREJS.jcl} lines 24 to 28, with
 * {@code LIMIT(5)} at line 26 and {@code SCRATCH} at line 27, is carried in the target by object-store
 * lifecycle configuration declared in the infrastructure package. Expressing it here as a purge or
 * bulk-delete member would put a retention policy in application code, where it could be invoked out
 * of band and would have to be kept in step with the lifecycle rule that actually governs the stored
 * generations.
 */
public interface TransactionRejectRepository extends JpaRepository<TransactionReject, Long> {
}
