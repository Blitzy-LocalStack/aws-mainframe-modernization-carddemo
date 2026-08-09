package com.carddemo.reporting.repository;

import com.carddemo.reporting.domain.CustomerView;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.data.repository.Repository;

/**
 * Keyed single-row read of the customer relation a statement heading is built from.
 *
 * <h2>Purpose</h2>
 *
 * <p>This role replaces one data definition and one operation code. The definition is
 * {@code //CUSTFILE DD} at {@code app/jcl/CREASTMT.JCL} L86, resolving to
 * {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}, the last of the four input definitions at L83-L86
 * supplied to {@code //STEP040 EXEC PGM=CBSTM03A,COND=(0,NE)} at L79. The file itself is declared
 * {@code FD CUST-FILE.} at {@code app/cbl/CBSTM03B.CBL} L70, whose record is
 * {@code 01 FD-CUSTFILE-REC.} at L71 over two members, {@code 05 FD-CUST-ID PIC X(09).} at L72 and
 * {@code 05 FD-CUST-DATA PIC X(491).} at L73, which sum to the 500-byte record the banner comment at
 * {@code app/cpy/CUSTREC.cpy} L2 states. The caller reads it once per cross-reference row, at
 * {@code 2000-CUSTFILE-GET.} L368 of {@code app/cbl/CBSTM03A.CBL}, and this interface exposes that one
 * read and nothing else.
 *
 * <h2>Assumptions: the operation code is a keyed single-row read, not a browse</h2>
 *
 * <p>Two sources disagree on what {@code LK-M03B-KEY-LN}, declared {@code PIC S9(4)} at
 * {@code app/cbl/CBSTM03B.CBL} L111, actually is, and both are named here rather than one being
 * overwritten in silence. A peer brief for {@code services/reporting-service/README.md} reads it as a
 * significant-key length, which would make the operation a generic browse and would justify keyset
 * positioning from inside the statement read path. The first-hand COBOL reads it as a substring length
 * on a whole key. <b>The first-hand COBOL is authoritative</b>, on the same precedent the shared
 * authorization codec contract sets for its own comma defect, where the program text supersedes a
 * derived architecture document. Register entry <b>R3</b> in the charter beside this file is the
 * package-wide home of that finding; the proofs are restated here because this interface is the
 * member whose whole shape depends on them.
 *
 * <p>Five independent proofs settle it. First, the two {@code SELECT} clauses that matter declare
 * {@code ACCESS MODE IS RANDOM}, at L45 for the customer file and L51 for the account file, and a
 * COBOL indexed file so declared cannot be browsed at all, while L33 and L39 declare
 * {@code SEQUENTIAL} for the other two definitions. Second, the callee body at L188-L193 is a
 * reference-modified move followed by a plain keyed read,
 * {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID} then
 * {@code READ CUST-FILE INTO LK-M03B-FLDT}, with no greater-or-equal qualifier anywhere; L213-L218 is
 * the same shape for the account file. Third, a verb census across all 230 lines of that program
 * returns zero for {@code KEY IS GREATER}, {@code READ NEXT}, {@code READNEXT}, {@code READPREV},
 * {@code STARTBR} and {@code ENDBR}, the only bare {@code START} hit being the paragraph label
 * {@code 0000-START.} at L116. Fourth, the caller computes the whole key every time:
 * {@code app/cbl/CBSTM03A.CBL} L373 zeroes the length and L374 sets it from
 * {@code LENGTH OF XREF-CUST-ID}, giving 9, and L397-L398 does the same for the account identifier,
 * giving 11, which match {@code app/cpy/CVACT03Y.cpy} L6 {@code PIC 9(09)} and L7 {@code PIC 9(11)}
 * exactly. Fifth, the paragraph names separate the two shapes themselves:
 * {@code 2000-CUSTFILE-GET.} at L368 and {@code 3000-ACCTFILE-GET.} at L392 against
 * {@code 1000-XREFFILE-GET-NEXT.} at L345 for the one sequential definition. The consequence is
 * direct: this role is a lookup by whole key that returns at most one row, and it needs no traversal
 * state of any kind.
 *
 * <p>Assumptions: one width mismatch on that path is harmless and is recorded so a reader does not
 * mistake it for evidence of a prefix. {@code LK-M03B-KEY} is declared {@code PIC X(25)} at L110,
 * far wider than the nine digits this key occupies, and the surplus never signifies because the
 * caller supplies the length explicitly on every call rather than letting the callee infer it from
 * the declared width.
 *
 * <h2>Assumptions: the identifier type is derived from the record, not copied from the file
 * definition</h2>
 *
 * <p>The two declarations of this key disagree on class, and the domain type follows the record
 * rather than the file definition. The file definition's key is {@code FD-CUST-ID PIC X(09)} at
 * {@code app/cbl/CBSTM03B.CBL} L72, which is <b>character</b>; the record member it is read into is
 * {@code CUST-ID PIC 9(09)} at {@code app/cpy/CUSTREC.cpy} L5, and the cross-reference member that
 * supplies every value is {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy} L6, both
 * <b>numeric</b>. The character declaration is an artifact of how an indexed file compares a key --
 * a record key is matched as bytes -- and not a statement about the domain, so it does not govern the
 * type here. Under the mapping the target design fixes for a numeric identity, nine digits become a
 * {@code BIGINT} column and a {@code Long} in Java, which is the identifier type
 * {@link CustomerView} declares on the member it marks as its identity. This interface takes that
 * type and does not introduce a second one.
 *
 * <h2>Assumptions: an absent customer row aborts the run rather than omitting a statement</h2>
 *
 * <p>The baseline treats an unresolvable customer as fatal, and the target's policy rests on that
 * rather than on what SQL would do by default. The cross-reference read tolerates end-of-file
 * through its {@code WHEN '10'} arm at {@code app/cbl/CBSTM03A.CBL} L353-L362, but the customer read
 * at L379-L386 carries only {@code WHEN '00'} and {@code WHEN OTHER} -- <b>there is no end-of-file arm
 * at all</b> -- so a cross-reference row naming a customer that is not present reaches
 * {@code PERFORM 9999-ABEND-PROGRAM} at L385 and abends the whole run at {@code 9999-ABEND-PROGRAM.}
 * L921, whose two-statement body is {@code DISPLAY 'ABENDING PROGRAM'} at L922 and
 * {@code CALL 'CEE3ABD'} at L923. That asymmetry between the two reads is the entire ground for the
 * contract below.
 *
 * <p>Of the two shapes that reproduce it, the target adopts <b>an inner join plus an integrity
 * assertion that fails the job</b>, which register entry <b>R10</b> records for the package. The
 * alternative shape, an outer join that admits a null dimension and then aborts on it, was weighed
 * and declined: it would force every dimension member on the projection to be nullable purely to
 * model a state the baseline treats as fatal, after which that nullability would read identically to
 * a genuinely optional column and the type would no longer state which absences are legal. The
 * hazard both shapes exist to avoid is a naive inner join used <i>alone</i>, with no assertion over
 * it: SQL would <b>silently discard</b> the unresolvable row instead of failing, yielding different
 * statement bytes and different totals, and a statement that is quietly missing rather than a run
 * that stops. The baseline abends; the target raises an explicit refusal that names the unresolved
 * key; the divergence is documented in
 * {@code docs/architecture/cobol-to-service-traceability.md}. This interface implements the
 * keyed-lookup half of that policy only: it reports absence as an empty result and leaves the
 * refusal to the caller, which is what lets the caller name the key it could not resolve, exactly as
 * the baseline lookups display the offending key before abending.
 *
 * <h2>Assumptions: the two protected identifier members are never selected</h2>
 *
 * <p>A statement heading needs a name and an address, so the relation behind this role selects
 * eleven columns and withholds seven of the eighteen members the record declares. Two of the seven
 * are the national identifier declared {@code PIC 9(09)} at {@code app/cpy/CUSTREC.cpy} L17 and the
 * government-issued identifier declared {@code PIC X(20)} at L18; the remaining five are both
 * telephone numbers at L15 and L16, the transfer-account reference at L20, the cardholder indicator
 * at L21 and the credit score at L22. In the target those two identifiers are held enciphered as
 * byte columns and returned masked, which is the narrowing of data exposure at the mapping layer the
 * target design sets out at its section 0.7.8. They are <b>absent from the relation rather than
 * masked within it</b>, so the login role this module connects as cannot read them even by issuing
 * its own query, and no accessor on {@link CustomerView} could expose what the relation never
 * selects. Those two columns are described here and deliberately not named: the migration that
 * declares the relation is guarded by a test asserting that neither identifier appears anywhere in
 * it, comments included, so that no protected column can be uncommented into a projection, and
 * naming them here would weaken the same discipline one file away.
 *
 * <h2>Trade-offs: a read-only reach across a schema boundary this context does not own</h2>
 *
 * <p>The relation this role reads is derived from a table in the {@code account} schema, and the
 * target design records this context's own tables as "(none)" at its section 0.4.1.3. The reach is
 * therefore a privilege and not a code dependency, which register entry <b>R11</b> states for the
 * package: the schema resolution and the pooled read-only login come from
 * {@link com.carddemo.reporting.config.DataSourceConfig}, which also pins schema generation off, and
 * the relation and the read grant are established in two artifacts:
 * {@code data-migration/sql/V0__schemas_and_roles.sql} L1275 conveys read on every table in the
 * {@code account} schema to the relation's owner, and
 * {@code data-migration/sql/V1__reporting_views.sql} L478-L492 defines the eleven-column relation over
 * that schema while its L559 conveys select on that one relation to the login role this module
 * authenticates as. Those two artifacts are owned elsewhere, so when a relation or a grant this role
 * needs is missing at run time the only correct action from here is to report a defect against the
 * data-migration package; creating it from here would put one object under two owners, and this login
 * could not create it in any case. The boundary is worth
 * stating in the form it is enforced: cross-schema reach is by {@code GRANT}, <b>never by a build
 * dependency on a sibling service module</b>, and the only module inside this build this one depends
 * on is the shared library. What the compromise buys is that a write issued from here fails at the
 * database rather than succeeding against data another context is accountable for.
 *
 * <p>Trade-offs: reads go to the writer rather than to a replica, which register entry <b>R12</b>
 * records on the target design's own stated ground, that "a replica adds cost and replica-lag
 * semantics for no parity benefit". The behavioural cost of the alternative is concrete rather than
 * general: the baseline job reads the live clusters named at {@code app/jcl/CREASTMT.JCL} L83-L86
 * directly, so it cannot omit a transaction the online path has already accepted, whereas a
 * statement built from a lagging replica can.
 *
 * <h2>Assumptions: a query boundary returns a fully populated projection or nothing</h2>
 *
 * <p>This is hygiene the baseline already practises, adopted rather than invented, and register entry
 * <b>R13</b> is its package-wide statement. The keyed read at {@code app/cbl/CBSTM03A.CBL} L377 is
 * bracketed on both sides: it is preceded by the clear-before pair
 * {@code MOVE ZERO TO WS-M03B-RC.} and {@code MOVE SPACES TO WS-M03B-FLDT.} at L375-L376, and
 * followed by {@code MOVE WS-M03B-FLDT TO CUSTOMER-RECORD.} at L388, which copies the result into a
 * record whose members are declared. The generic 1000-byte buffer that carries the bytes,
 * {@code LK-M03B-FLDT PIC X(1000)} at {@code app/cbl/CBSTM03B.CBL} L112, is never read directly by
 * the caller. The equivalent here is that this lookup yields a fully populated typed projection or
 * yields nothing at all, never a half-filled instance a caller would have to inspect member by
 * member to discover whether the read succeeded.
 *
 * <h2>Assumptions: this is one of four collaborators, not a job</h2>
 *
 * <p>{@code app/cbl/CBSTM03B.CBL} is a subprogram, declaring
 * {@code PROCEDURE DIVISION USING LK-M03B-AREA.} at L114, and it owns all four input file
 * definitions, at L58, L65, L70 and L75, while {@code app/cbl/CBSTM03A.CBL} declares none of them --
 * only two output definitions at L44-L47. It is reached from thirteen call sites in that caller, at
 * L351, L377, L401, L734, L746, L769, L787, L805, L835, L860, L877, L893 and L909, of which three
 * belong to this definition: the open issued from {@code 8300-CUSTFILE-OPEN.} L783 at L787, the read
 * issued from {@code 2000-CUSTFILE-GET.} L368 at L377, and the close issued from
 * {@code 9300-CUSTFILE-CLOSE.} L889 at L893. This interface is therefore one of four collaborators a
 * statement generation service composes, and is neither a job with an entry point of its own nor a
 * service class. Dispatch in the baseline is by definition name first, {@code EVALUATE LK-M03B-DD} at
 * L118, and only then by operation code, and support per definition is asymmetric: no file supports
 * both the plain read and the keyed read, which is why a lookup and a traversal are separate types
 * here rather than two operation codes on one.
 *
 * <h2>Refactoring Rationale: an unsupported request raises rather than returning a stale status</h2>
 *
 * <p>The construct being replaced returns a value that is not an answer.
 * {@code app/cbl/CBSTM03B.CBL} L103-L108 declares six operation codes as condition names, but the
 * write and rewrite codes at L107 and L108 are implemented in no paragraph -- a census for their two
 * condition names across the whole program returns exactly two hits, which are those declarations
 * themselves. Such a request therefore falls through every {@code IF} in its per-definition
 * paragraph and lands on that paragraph's exit, whose only act for this definition is
 * {@code MOVE CUSTFILE-STATUS TO LK-M03B-RC.} at L201, so the caller receives the status of the
 * <i>previous</i> operation on the file and cannot distinguish a silent no-operation from a success.
 * An unrecognised definition name is worse: {@code WHEN OTHER} at L127-L128 branches straight to the
 * goback, leaving the return code never assigned. Because this module is read-only, only the open,
 * the close and the keyed read are ever exercised through it, and there is no write member on this
 * interface for such a request to fall through in the first place; register entry <b>R4</b> carries
 * the package-wide form of this reasoning.
 *
 * <h2>Alternatives Considered: the marker interface, and the name this lookup carries</h2>
 *
 * <p>Alternatives Considered: extending {@code CrudRepository} or {@code JpaRepository}, the reflex
 * base for a Spring Data interface, is refused here. Either one would inherit {@code save},
 * {@code saveAll}, {@code delete}, {@code deleteAll} and {@code deleteById} onto a type whose entire
 * contract is that it has none, so the read-only posture would become a convention the type's own
 * surface contradicts: a caller could reach a write through code completion and the refusal would
 * arrive from the database at run time instead of from the compiler. Extending the bare marker
 * inherits nothing, so the surface is exactly the one member declared below, and those five write
 * names resolve on no reachable method of this type. The database privilege is the other half of the
 * control and neither half is redundant -- this one fails at compile time, and the privilege fails in
 * production because {@code data-migration/sql/V1__reporting_views.sql} L559 conveys select and
 * nothing else on the relation behind it.
 *
 * <p>Alternatives Considered: naming the lookup for the property, as
 * {@code findByCustomerId}, which is the spelling the sibling account and cross-reference roles in
 * this package use. It is declined here for a reason specific to this relation: the member it would
 * query is the very member {@link CustomerView} marks as its identity, so a derived query over it
 * would duplicate the lookup the Spring Data base fragment already provides. Declaring
 * {@code findById} on the bare marker is the documented way to expose one read of that fragment
 * selectively, and it resolves to a fetch by identity rather than to a generated query -- which is
 * the closer counterpart of a read against a file declared {@code ACCESS MODE IS RANDOM} at
 * {@code app/cbl/CBSTM03B.CBL} L45, since that verb also addresses a record by its key rather than
 * evaluating a predicate over the file. The sibling spelling is named rather than left implicit
 * because the two differ, and a reader comparing the roles is owed the reason.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The decision register that governs every choice in this package is authored once in
 * {@code package-info.java} beside this file, and entries are cited by identifier rather than
 * restated, except where this interface is itself the member the finding is about. Every member below
 * carries a docstring because user-specified Rule 1 (Explainability) attaches its presence clause to
 * every function and names no visibility, and the written form is the one
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes.
 */
public interface StatementCustomerRepository extends Repository<CustomerView, Long> {

    /**
     * Reads the customer row for one complete customer identifier.
     *
     * <p>Assumptions: the result is an optional rather than a nullable row, which is register entry
     * <b>R13</b> applied here -- a query boundary yields a fully populated projection or nothing, and
     * never a half-filled instance a caller has to inspect member by member to find out whether the
     * read succeeded. The baseline brackets its own read the same way, clearing the return code and
     * the data buffer at {@code app/cbl/CBSTM03A.CBL} L375-L376 before the call and copying the result
     * into a record whose members are declared at L388 after it.</p>
     *
     * <p>Assumptions: an empty result is <b>not a benign miss</b>. Every value reaching this lookup
     * comes from a cross-reference row that exists, so an absent customer is a referential-integrity
     * violation, and the caller must abort the statement run rather than omit the statement. The
     * evidence is the missing end-of-file arm: the customer read at {@code app/cbl/CBSTM03A.CBL}
     * L379-L386 carries only {@code WHEN '00'} and {@code WHEN OTHER}, so it abends at
     * {@code 9999-ABEND-PROGRAM.} L921, whereas the cross-reference read at L353-L362 does carry a
     * {@code WHEN '10'} arm. The refusal is raised by the caller rather than from here so that it can
     * name the identifier it could not resolve, which is what the baseline lookups do when they
     * display the offending key before abending.</p>
     *
     * @param customerId the complete customer identifier, nine digits wide as declared by
     *     {@code CUST-ID PIC 9(09)} at {@code app/cpy/CUSTREC.cpy} L5 and supplied by
     *     {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy} L6; must not be
     *     {@code null}, and a partial identifier is not a valid argument because this path has no
     *     prefix semantics
     * @return an {@link Optional} holding the customer projection for that identifier when the
     *     relation has such a row, or an empty {@link Optional} when it has none, in which case the
     *     caller treats the absence as fatal for the reason recorded above; never {@code null}
     * @throws DataAccessException when the read cannot be completed -- the relation is absent, the
     *     login role holds no privilege to select from it, or the connection fails. A missing
     *     relation or grant is a defect to report against the data-migration package rather than
     *     something this module can resolve, which register entry <b>R11</b> records
     */
    Optional<CustomerView> findById(Long customerId);
}
