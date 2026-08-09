package com.carddemo.reporting.repository;

import com.carddemo.reporting.domain.AccountView;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Keyed single-row read of {@code reporting.v_accounts}, standing in for the random-access account
 * read the statement generator issues once per cross-reference row.
 *
 * <h2>Purpose</h2>
 *
 * <p>This role replaces exactly one data definition of one job step.
 * {@code app/jcl/CREASTMT.JCL} runs the statement generator as
 * {@code //STEP040  EXEC PGM=CBSTM03A,COND=(0,NE)} at L79 and supplies it four input definitions
 * across L83-L86. The third of those four is {@code //ACCTFILE} at L85, naming the dataset
 * {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}. {@code app/cbl/CBSTM03B.CBL} declares that definition
 * as {@code FD  ACCT-FILE.} at L75 over {@code 01  FD-ACCTFILE-REC.} at L76, whose two fields are
 * {@code FD-ACCT-ID} at L77 and {@code FD-ACCT-DATA PIC X(289)} at L78, so 11 plus 289 closes the
 * 300-byte record that {@code app/cpy/CVACT01Y.cpy} announces in its L2 header and that
 * {@code app/cbl/CBSTM03A.CBL} copies at L57.
 *
 * <p>Three of the thirteen calls into that subprogram carry this definition, and all three are
 * accounted for. {@code 8400-ACCTFILE-OPEN.} at L801 of {@code app/cbl/CBSTM03A.CBL} issues the open
 * at L805, and {@code 9400-ACCTFILE-CLOSE.} at L905 issues the close at L909; neither has a member
 * here, because the pooled connection that
 * {@code com.carddemo.reporting.config.DataSourceConfig} builds replaces an explicit open-and-close
 * bracket around a file. {@code 3000-ACCTFILE-GET.} at L392 issues the one read, at L401, and that
 * read is the whole of what this interface exposes.
 *
 * <h2>Assumptions: the operation code is a random keyed read, not a browse</h2>
 *
 * <p>{@code app/cbl/CBSTM03B.CBL} declares this definition {@code ACCESS MODE  IS RANDOM} at L51
 * with {@code RECORD KEY   IS FD-ACCT-ID} at L52, and a COBOL indexed file so declared cannot be
 * browsed at all. Its callee body at L213-L218 is shaped exactly like the customer one at
 * L188-L193: a guard on the keyed-read condition, then
 * {@code MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-ACCT-ID}, then
 * {@code READ ACCT-FILE INTO LK-M03B-FLDT}, then {@code END-READ}, then a branch to the exit. A
 * reference-modified move followed by a plain read is a random keyed read; there is no
 * greater-or-equal qualifier, no browse start, no read-next and no browse end anywhere in the
 * program. That is why this interface exposes a single-row lookup and nothing else, and why it
 * declares no cursor, no ordered query and no traversal: on this definition such a member would
 * have no baseline counterpart to stand in for.
 *
 * <p>Register entry <b>R3</b> in {@code package-info.java} beside this file holds the five
 * independent proofs and is cited rather than restated. It also records the disagreement this
 * paragraph must not resolve in silence: a peer brief for
 * {@code services/reporting-service/README.md} reads {@code LK-M03B-KEY-LN PIC S9(4)}, declared at
 * L111 of {@code app/cbl/CBSTM03B.CBL}, as a significant-key length -- a reading that would imply a
 * generic browse and would justify keyset positioning from inside the statement read path. Both
 * readings are named, both sources are named, and the first-hand COBOL is authoritative, on the
 * same precedent the shared authorization codec contract sets for its own comma defect. The length
 * is a substring length: it selects how much of the key buffer to move, and the buffer it selects
 * from is {@code LK-M03B-KEY PIC X(25)} at L110. That the buffer is 25 characters wide while the
 * key is 11 digits is a harmless artifact of one generic linkage area serving four definitions of
 * differing key widths, and it never matters, because the caller supplies the length on every call.
 *
 * <h2>Assumptions: the whole key is supplied, never a prefix</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} L396-L398 moves the cross-reference account identifier into the
 * key buffer, zeroes the length, and then computes the length from
 * {@code LENGTH OF XREF-ACCT-ID}, giving 11 -- which matches
 * {@code XREF-ACCT-ID PIC 9(11)} at L7 of {@code app/cpy/CVACT03Y.cpy} exactly. There is no prefix
 * and not even partial-key intent on this path, so the lookup takes one complete identifier and
 * yields at most one row. The paragraph names carry the same distinction independently: this one is
 * {@code 3000-ACCTFILE-GET.} at L392, a plain get, against
 * {@code 1000-XREFFILE-GET-NEXT.} at L345 for the definition that really is sequential.
 *
 * <h2>Assumptions: this is a collaborator, not a job</h2>
 *
 * <p>{@code app/cbl/CBSTM03B.CBL} is a subprogram, declaring
 * {@code PROCEDURE DIVISION USING LK-M03B-AREA.} at L114, and it owns all four input definitions,
 * at L58, L65, L70 and L75. {@code app/cbl/CBSTM03A.CBL} owns none of them; its only two file
 * declarations, at L44-L47, are outputs -- an 80-byte statement record and a 100-byte markup
 * record. Dispatch inside the subprogram is by definition name first,
 * {@code EVALUATE LK-M03B-DD} at L118, and only then by operation code, and support is asymmetric
 * per definition: no file accepts both the plain read and the keyed read. This interface is
 * therefore one of four collaborators the statement generation service holds, not a job of its own
 * and not a service class with an entry point. Register entry <b>R3</b> records the census that
 * establishes it.
 *
 * <h2>Assumptions: extending the marker interface is the read-only mechanism</h2>
 *
 * <p>Alternatives Considered: extending {@code CrudRepository} or {@code JpaRepository}, which is
 * the reflex base for a Spring Data interface and is refused here. Either one would inherit
 * {@code save}, {@code saveAll}, {@code delete}, {@code deleteAll} and {@code deleteById} onto this
 * type, so the read-only posture the package charter states would become a convention the type's
 * own surface contradicts: a caller could reach a mutator through code completion, and the refusal
 * would arrive from the database at run time instead of from the compiler. Extending the bare
 * marker inherits nothing, so the surface is exactly the one member declared below. The database
 * privilege is the other half of the control and neither half is redundant -- this one refuses at
 * compile time and the privilege refuses in production.
 *
 * <p>Alternatives Considered: naming the member for the property rather than for the identifier,
 * as {@code findByAccountId}. Declined because the column it reads on is this projection's own
 * identifier and nothing else -- {@code com.carddemo.reporting.domain.AccountView} annotates
 * {@code accountId} as the identifier over the column {@code account_id} -- so a property-derived
 * twin would supply a second name for one access path, which is the condition under which a caller
 * reaches for the wrong one. The name declared below resolves against the Spring Data
 * base implementation as an identity lookup rather than being parsed into a derived query, which
 * also lets the read be served from the persistence context already open on the calling
 * transaction. That is closer to the baseline shape than a derived query would be, because the
 * baseline reads this definition by record key and by nothing else.
 *
 * <h2>Refactoring Rationale: an unsupported request raises rather than returning a stale status</h2>
 *
 * <p>The code being stood in for returns a value that is not an answer.
 * {@code app/cbl/CBSTM03B.CBL} L103-L108 declares six operation codes as condition names, but the
 * write and rewrite codes at L107 and L108 are implemented in no paragraph: a census for their two
 * condition names across all 230 lines returns exactly two hits, which are those two declarations
 * themselves. A write request therefore falls through every guard in this definition's paragraph
 * and lands on its exit at L226, whose only act is to move the file status into the return code, so
 * the caller receives the status of the <i>previous</i> operation and cannot tell a silent
 * no-operation from a success. An unrecognised definition name is worse: {@code WHEN OTHER} at
 * L127-L128 branches straight to the goback, leaving the return code never assigned at all. Because
 * this module reads and never writes, no mutator exists on this interface for such a request to
 * fall through, and an unreachable request cannot be mistaken for a completed one. Register entry
 * <b>R4</b> holds the full argument.
 *
 * <h2>Assumptions: money precision is per record and is never unified</h2>
 *
 * <p>{@code ACCT-CURR-BAL PIC S9(10)V99} at L7 of {@code app/cpy/CVACT01Y.cpy} is twelve
 * significant digits at a scale of two and maps to {@code NUMERIC(12,2)}. The statement transaction
 * projection's amount is one digit narrower -- {@code TRNX-AMT PIC S9(09)V99} at L29 of
 * {@code app/cpy/COSTM01.CPY}, eleven digits, mapping to {@code NUMERIC(11,2)}. Both are carried as
 * {@code com.carddemo.common.money.Money} at scale two with half-up rounding, and <b>neither is
 * ever widened, aliased or harmonised to match the other</b>: two declared contracts differing by
 * one digit are two contracts, and a shared constant or a shared attribute converter reachable from
 * both is the exact mechanism by which one silently becomes the other. The baseline holds these
 * values as zoned decimal with the sign overpunched into the final byte, which is why the
 * compiler's sign convention is decisive at the decode edge and why the target carries exact fixed
 * point at every hop rather than IEEE-754 binary floating point, whose representation cannot hold
 * an exact cent and has no margin at all at twelve digits.
 *
 * <h2>Assumptions: one column name differs from the baseline field, deliberately</h2>
 *
 * <p>The baseline spells the field {@code ACCT-EXPIRAION-DATE}, at L11 of
 * {@code app/cpy/CVACT01Y.cpy}, and the relation this interface reads exposes it as
 * {@code expiration_date}. That is one of three documented baseline misspelling corrections
 * registered in {@code docs/architecture/data-model-and-schema-mapping.md}; the baseline itself is
 * reference-only and stays byte-identical, and the divergence is a change of column NAME recorded
 * in that document rather than any alteration of the COBOL. The original spelling is written out
 * here so that a reader tracing the copybook can find the field it came from. The baseline holds
 * the value as ten characters already in year-month-day order, so a lexical comparison and a date
 * comparison agree on it, which is why it maps to a real date column instead of to text.
 *
 * <h2>Assumptions: the identifier type is derived, not copied</h2>
 *
 * <p>Three declarations agree that this identifier is a magnitude rather than a string of
 * characters: {@code ACCT-ID PIC 9(11)} at L5 of {@code app/cpy/CVACT01Y.cpy},
 * {@code XREF-ACCT-ID PIC 9(11)} at L7 of {@code app/cpy/CVACT03Y.cpy}, which is the field the
 * statement generator keys this very read with, and {@code FD-ACCT-ID PIC 9(11)} at L77 of
 * {@code app/cbl/CBSTM03B.CBL}. Under the transformation rule that a numeric identity becomes a
 * 64-bit integer column, the target column is {@code BIGINT} and the Java type below is
 * {@code Long}. A file description's key is in general an access artifact rather than the domain
 * type -- the customer definition in the same program declares {@code FD-CUST-ID PIC X(09)} at L72,
 * character-typed, over a copybook field that is numeric -- so the type is taken from the copybook
 * on both paths and the file description is used only to corroborate the width.
 *
 * <h2>Assumptions: no optimistic-locking column is mapped or asserted</h2>
 *
 * <p>The relation behind this view is derived from a table that does carry an optimistic-locking
 * column, and that column belongs to the writable entity in the context that owns the table, where
 * it stands in for the before-image comparison the account update program performs across the
 * pseudo-conversational gap. A statement is a point-in-time read rather than a compare-and-swap,
 * so there is nothing here for a version to guard: this interface neither reads such a column nor
 * asserts one, and the omission is deliberate rather than an oversight. The projection it returns
 * is annotated immutable and declares every column neither insertable nor updatable, so the
 * absence cannot be mistaken for an unguarded write path.
 *
 * <h2>Trade-offs: reading relations this context does not own</h2>
 *
 * <p>The target design records this context's owned tables as "(none)": it declares no table, no
 * index and no schema-definition statement, carries no migration directory, and reads through
 * read-only cross-schema views under a role holding read privileges only. Two properties of that
 * arrangement are load-bearing and are stated rather than left to be inferred. First, the reach is
 * by privilege and never by a build dependency: the schema resolution path and the read-only pool
 * are pinned once by {@code com.carddemo.reporting.config.DataSourceConfig}, which also holds
 * schema generation by the persistence provider to none, and the privileges themselves are
 * conveyed by {@code data-migration/sql/V0__schemas_and_roles.sql} to a no-login view owner, with
 * the views executing under that owner's rights so that a view reads what its caller cannot. The
 * only intra-reactor dependency this module declares is the shared kernel; no service module
 * depends on another. Second, a relation absent at run time is a defect to report against the
 * data-migration package and never something to create from here, which register entry <b>R11</b>
 * records and which that file instructs directly. Register entry <b>R12</b> carries the companion
 * refusal, on the target design's own ground that "a replica adds cost and replica-lag semantics
 * for no parity benefit", so reads go to the writer through those views.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The decision register governing every choice in this package is authored once in
 * {@code package-info.java} beside this file, and entries are cited by identifier rather than
 * restated. The four rationale labels above are written in the plural, unparenthesised,
 * colon-terminated ASCII form that user-specified Rule 1 declares at its L31-L34 and that
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes as the one permitted written form; register
 * entry <b>R14</b> records why they were retyped from the rule rather than copied from the
 * reference suite. The member below carries a docstring because that rule attaches its presence
 * clause at L15 to every function and names no visibility to exempt, and it carries purpose,
 * parameter, return and exception elements because L18-L21 enumerate all four.
 */
public interface StatementAccountRepository extends Repository<AccountView, Long> {

    /**
     * Reads the one account row carrying a complete account identifier.
     *
     * <p>Assumptions: the result is an optional rather than a nullable row, which is register entry
     * <b>R13</b> applied here. The baseline practises the same hygiene and it is adopted rather
     * than invented: {@code app/cbl/CBSTM03A.CBL} clears both the return code and the shared data
     * buffer at L399-L400 before the read at L401, and copies the buffer into a typed record at
     * L412 afterwards, never reading the generic {@code LK-M03B-FLDT PIC X(1000)} buffer declared
     * at L112 of {@code app/cbl/CBSTM03B.CBL} directly. This method likewise yields a fully
     * populated typed projection or yields nothing, and never a half-filled instance a caller must
     * inspect field by field to learn whether the read succeeded.</p>
     *
     * <p>Assumptions: an empty result is a referential-integrity violation and is fatal on this
     * path, and the refusal belongs to the caller rather than to this method. Register entry
     * <b>R10</b> holds the evidence and fixes the policy. The cross-reference read at
     * {@code app/cbl/CBSTM03A.CBL} L353-L362 tolerates end-of-file through a {@code WHEN '10'}
     * arm; the account read at L403-L410 has no such arm at all, so a cross-reference row naming an
     * account that does not exist abends the entire run at {@code 9999-ABEND-PROGRAM.} L921, whose
     * two-statement body at L922-L923 displays and then calls the language-environment abend
     * service. The policy adopted against that asymmetry is the first of the two available shapes:
     * an inner join plus an integrity assertion at the query boundary that fails the run, rather
     * than an outer join admitting a null dimension and aborting on it, which would force every
     * dimension member nullable purely to model a state the baseline treats as fatal. It is the
     * same policy the sibling customer lookup states, because both derive from the one asymmetry.
     * The hazard being guarded against is specific: a naive inner join on its own would silently
     * DISCARD the unresolvable row instead of failing -- different bytes, different totals, and a
     * statement quietly absent rather than a run that stops -- which is the class of silent parity
     * failure the golden masters exist to catch. Returning an empty optional rather than raising
     * from here is what lets the caller name the unresolved identifier in its own refusal, which is
     * what the baseline lookup paragraphs do when they display the offending key before abending.</p>
     *
     * @param accountId the complete account identifier to read, as a {@code Long}, taken from the
     *     cross-reference row that drives the statement and declared {@code PIC 9(11)} at L5 of
     *     {@code app/cpy/CVACT01Y.cpy}; must not be {@code null}
     * @return the account projection carrying that identifier, as an
     *     {@code Optional<AccountView>}, or an EMPTY optional when the view holds no such row --
     *     which on this path is a referential-integrity violation the caller must treat as fatal
     *     and not as a benign miss; never {@code null}
     * @throws org.springframework.dao.DataAccessException if the projection cannot be read, which
     *     includes the view being absent or unreadable by this module's role -- a defect to report
     *     against the data-migration package, as register entry <b>R11</b> records, and never one
     *     to work around from here
     */
    Optional<AccountView> findById(Long accountId);
}
