package com.carddemo.batch.dto;

/**
 * The closed domain of the batch container's {@code --job=} argument: seven job tokens, and no
 * eighth.
 *
 * <h2>Purpose: one string, three separately authored artifacts</h2>
 *
 * <p><b>Purpose.</b> This type models the set of jobs this module runs, together with the exact
 * spelling by which each one is asked for. A token declared here is not an internal label. It is
 * written into an orchestration state definition, carried onto a container command line as a
 * process argument, and matched against the name a job bean registers under, so three artifacts
 * authored in three different languages have to agree on one string. Nothing in Java reports a
 * disagreement: the module compiles, the image builds, the task starts, and the run then fails
 * inside the state machine with an unresolved-job error.</p>
 *
 * <p>Assumptions: the seven tokens are not chosen here, and this type is not free to restyle them.
 * They are the argument contract already declared by {@code com.carddemo.batch.BatchApplication},
 * whose {@code JOB_NAMES} list at lines 281 to 288 spells them
 * {@code preflight-daily-transactions}, {@code post-transactions}, {@code calculate-interest},
 * {@code backup-transactions}, {@code combine-transactions}, {@code export} and {@code import},
 * and whose validator at lines 738 to 748 admits nothing else. The orchestrator supplies the token
 * through the container overrides of a run-task state, so the spelling has to survive intact from
 * a state definition this repository's Java cannot inspect, through a process argument list, to
 * that validator. A token altered here and not in both of the other two places leaves a state that
 * cannot start its job, and leaves it failing at run time rather than at build time.</p>
 *
 * <h2>Two of the seven are bare words, and that asymmetry is deliberate</h2>
 *
 * <p>Assumptions: five tokens are kebab-case verb phrases and two -- {@code export} and
 * {@code import} -- are bare single words. They are not {@code export-data} and
 * {@code import-data}, and they are not to be regularised into the majority shape. The contract
 * cited above spells them as bare words, and the two jobs they name sit outside the nightly chain,
 * so the inconsistency is visible in the argument list of exactly the two operator-invoked jobs. An
 * author who tidied them for symmetry would produce a type that reads better and rejects the two
 * commands an operator actually sends.</p>
 *
 * <h2>The constant names are not the contract; the tokens are</h2>
 *
 * <p>Trade-offs: {@code IMPORT} carries the token {@code import}, and the two cannot be spelled
 * alike. {@code import} is a Java reserved word, so it is unavailable as an identifier and the
 * constant has to differ from the string it carries. The divergence is accepted in that direction
 * rather than the other because only one of the two is externally observable: the token crosses
 * into infrastructure, while the constant name is read only by Java in this repository. The same
 * reasoning applies uniformly to the other six, whose upper-snake-case names are likewise a Java
 * convention rather than a wire form -- {@code PREFLIGHT_DAILY_TRANSACTIONS} is never the string
 * that travels. Every consumer that needs the contract calls {@link #token()}, and no consumer
 * derives a token from {@link #name()}, which would produce {@code IMPORT} where {@code import} is
 * required.</p>
 *
 * <h2>Declaration order, and why {@link #ordinal()} is not part of the contract</h2>
 *
 * <p>Trade-offs: the constants are declared in nightly execution order -- preflight, post,
 * interest, backup, combine -- and then the two unscheduled jobs, so a maintainer reading this file
 * top to bottom maps it straight onto states 3 through 7 of the eleven-state chain without holding
 * a separate ordering in mind. The cost of encoding an order in a declaration is that the order
 * becomes reachable as a number: <b>{@link #ordinal()} is NOT part of any contract this type
 * carries, and must never be persisted, transmitted, stored in the durable step ledger or written
 * into an orchestration definition.</b> Inserting a state, or moving the two unscheduled jobs, would
 * silently renumber every ordinal already written down, whereas a token renamed the same way fails
 * loudly at the validator cited above. The risk is closed by stating the prohibition here rather
 * than by declaring the constants in an arbitrary order and losing the readability.</p>
 *
 * <h2>Resolution is exact, and case-sensitive</h2>
 *
 * <p>Assumptions: {@link #resolve(String)} compares tokens byte for byte and accepts no case
 * variation, so {@code POST-TRANSACTIONS} does not resolve. The orchestration module supplies
 * lowercase tokens, and the validator at {@code com.carddemo.batch.BatchApplication} lines 743 to
 * 746 tests membership of its own lowercase list, which is likewise case-sensitive. Folding case
 * here would make this type accept a command that the process entry point has already rejected, so
 * the same argument would get two different answers depending on which of the two happened to read
 * it first.</p>
 *
 * <h2>What this type deliberately does not have</h2>
 *
 * <p>Alternatives Considered: a default constant, so that an absent or unreadable
 * {@code --job=} argument would still select something. Rejected, because the contract declares no
 * default job: the orchestrator always supplies the argument explicitly, so an absent argument
 * means the command was assembled wrongly, and there is no job that is legitimately the one to run
 * when nobody said which. A default would turn a malformed command into a silent, successful run of
 * the wrong job against production data, which is the failure mode with no diagnostic attached to
 * it. {@link #resolve(String)} therefore raises rather than falling back, and this type has no
 * member that could serve as a fallback.</p>
 *
 * <p>Assumptions: this file declares no import at all, and the absence is a decision. Everything it
 * needs is a string literal and the enumeration facilities the language itself supplies. The
 * charter of this package fixes the import discipline as an absolute -- no other service module's
 * domain package, no cloud provider software development kit type, no web or servlet type, no batch
 * framework internal -- and the batch module carries no request-handling layer at all, so no
 * request-body binding, response-entity wrapper or interface-documentation annotation belongs on
 * this type either. A serialization annotation would earn its place only if a shape crossed a wire,
 * and this one does not: the token reaches Java as a process argument and leaves through
 * {@link #token()}, which is public.</p>
 *
 * <h2>Baseline lineage: provenance only</h2>
 *
 * <p>Each constant below cites the reference program it re-expresses and the job that drove that
 * program. The citations are provenance: nothing under {@code app/**} is read at run time, and
 * nothing under it is altered by this migration -- the reference implementation is the behavioural
 * oracle and stays byte-identical. Where migrated behaviour differs from the reference, the
 * reference does one thing, the Java does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. No claim is made anywhere in this file
 * that the reference itself was altered, because it was not. Line numbers refer to the source as
 * committed, and columns 73 to 80 of a COBOL or JCL line carry a sequence field that is not part of
 * the statement.</p>
 *
 * <h2>Parameters, return values and exceptions at type level: declared inapplicable</h2>
 *
 * <p>An enumeration declaration accepts no parameter, yields no value and raises nothing, so this
 * block carries no parameter, return or exception at-clause, and no authorship, availability or
 * revision at-clause either. The inapplicability is stated rather than left silent because the
 * project's single user-specified rule, Explainability, names at its line 39 a docstring that omits
 * parameters, return values or purpose among its forbidden patterns, and a reader has to be able to
 * tell a declared inapplicability from an oversight. The three elements that do apply to a type are
 * discharged above; the fourth is discharged on each member below.</p>
 */
public enum BatchJobName {

    /**
     * State 3 of the nightly chain: the pre-posting validation pass over the daily transaction feed,
     * token {@code preflight-daily-transactions}.
     *
     * <p>Re-expresses {@code app/cbl/CBTRN01C.cbl}, 494 lines, which reads the sequential daily
     * transaction feed and resolves each record against the card cross-reference, the customer
     * record and the account record before any balance is touched.</p>
     *
     * <p>Assumptions: <b>this program has no JCL driver anywhere in the reference baseline.</b> None
     * of the thirty-eight files in {@code app/jcl/} names it, and neither {@code app/proc/} nor
     * {@code app/scheduler/} references it either; the only thing that drives it is an integration
     * test in the parity oracle suite, at {@code tests/integration/test_cbtrn01c_prepost.py}. It is
     * migrated regardless, and it is wired into the chain as state 3, because the validation pass it
     * performs is a real step of the daily cycle whose absence from the job library is an omission in
     * the reference rather than evidence that the step is unwanted. This is the one constant here
     * whose driver column is empty, and the emptiness is recorded so that a reader who greps
     * {@code app/jcl/} for the program name and finds nothing knows the omission is known rather than
     * a citation this file got wrong.</p>
     */
    PREFLIGHT_DAILY_TRANSACTIONS("preflight-daily-transactions"),

    /**
     * State 4 of the nightly chain: posting the daily transactions, token {@code post-transactions}.
     *
     * <p>Re-expresses {@code app/cbl/CBTRN02C.cbl}, 731 lines, driven by
     * {@code app/jcl/POSTTRAN.jcl:23}, {@code //STEP15 EXEC PGM=CBTRN02C}. That step carries no
     * {@code PARM} and supplies nine data definitions; one of them is the reject stream, created new
     * at {@code app/jcl/POSTTRAN.jcl:36} with {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} into the
     * next generation of its dataset.</p>
     *
     * <p>Assumptions: this is the only one of the seven that can legitimately finish in the soft-warn
     * tier rather than clean. The reference decides that itself, at
     * {@code app/cbl/CBTRN02C.cbl:229-231}, where {@code IF WS-REJECT-COUNT > 0} selects
     * {@code MOVE 4 TO RETURN-CODE}: a run that correctly rejected transactions has done its job and
     * must not read as a failure, while the downstream state still needs to know rejects were
     * written. Every other token here reports either clean or failed, so a warn tier arriving from
     * any of them is a defect rather than a business outcome.</p>
     */
    POST_TRANSACTIONS("post-transactions"),

    /**
     * State 5 of the nightly chain: monthly interest accrual, token {@code calculate-interest}.
     *
     * <p>Re-expresses {@code app/cbl/CBACT04C.cbl}, 652 lines, driven by
     * {@code app/jcl/INTCALC.jcl:22}, {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}. The
     * accrual itself is at {@code app/cbl/CBACT04C.cbl:464-465}, where line 464 reads
     * {@code COMPUTE WS-MONTHLY-INT} and line 465 reads
     * {@code = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. Its output is a newly created generation of
     * the system transaction dataset, declared at {@code app/jcl/INTCALC.jcl:37-41} at
     * {@code LRECL=350}.</p>
     *
     * <p>Assumptions: this is the only one of the seven that requires a business date, and the
     * requirement comes from that {@code PARM}. The reference receives the date as a ten-character
     * argument rather than reading a clock, which is what makes a rerun of one night's accrual
     * reproduce that night's figures instead of the figures for the day it happens to be rerun on.
     * The migrated job therefore takes {@code --business-date=} alongside this token, and a
     * consumer that supplied this token without one would run an accrual against an unspecified
     * date.</p>
     */
    CALCULATE_INTEREST("calculate-interest"),

    /**
     * State 6 of the nightly chain: copying the posted transaction master to a new dataset
     * generation, token {@code backup-transactions}.
     *
     * <p>Re-expresses {@code app/jcl/TRANBKP.jcl}. <b>No COBOL program stands behind this one.</b>
     * The reference step is a utility invocation: the copy runs at
     * {@code app/jcl/TRANBKP.jcl:23} as {@code //STEP05R EXEC PROC=REPROC}, a catalogued procedure
     * rather than a program, writing the next generation of the backup dataset through the data
     * definition at lines 29 to 33. The migrated job exports to a new object-store generation
     * instead, so the constant names a step rather than a translated program, and a reader should
     * not go looking in {@code app/cbl/} for a source file that does not exist.</p>
     *
     * <p>Assumptions: the soft-warn continuation gate of the whole reference job library lives in
     * this job, at {@code app/jcl/TRANBKP.jcl:51}, {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)}. A
     * JCL condition code is a SKIP predicate, so that clause skips the step when 4 is less than the
     * preceding return code, which is to say it runs the step for any code of 4 or lower and
     * therefore lets the soft-warn tier through. The migrated orchestration expresses the same
     * decision as a choice predicate with the sense inverted, and the inversion is the single
     * easiest thing in this migration to write backwards: read as a run predicate, the same clause
     * would stop the chain on exactly the outcome the reference lets continue.</p>
     */
    BACKUP_TRANSACTIONS("backup-transactions"),

    /**
     * State 7 of the nightly chain: merging the backed-up and system-generated transactions into one
     * ordered dataset, token {@code combine-transactions}.
     *
     * <p>Re-expresses {@code app/jcl/COMBTRAN.jcl}. <b>No COBOL program stands behind this one
     * either.</b> The merge step is {@code app/jcl/COMBTRAN.jcl:22},
     * {@code //STEP05R  EXEC PGM=SORT}, a sort utility invocation reading two concatenated inputs --
     * the current generation of the backup dataset at line 24 and the current generation of the
     * system transaction dataset at line 26 -- and writing the next generation of the combined
     * dataset at line 37.</p>
     *
     * <p>Assumptions: the ordering is the whole content of the step, and it is fully declared in the
     * reference. The symbol definition at {@code app/jcl/COMBTRAN.jcl:28} reads
     * {@code TRAN-ID,1,16,CH}, fixing the key as the leading sixteen characters of the record, and
     * the control statement at line 30 reads {@code SORT FIELDS=(TRAN-ID,A)}, fixing the direction
     * as ascending. The migrated job expresses that as an ordered query over the same key rather than
     * as a sort of a flat file, which is why the constant names a step and not a program.</p>
     */
    COMBINE_TRANSACTIONS("combine-transactions"),

    /**
     * Outside the nightly chain: the operator-invoked multi-record customer data export, token
     * {@code export}.
     *
     * <p>Re-expresses {@code app/cbl/CBEXPORT.cbl}, 582 lines, driven by
     * {@code app/jcl/CBEXPORT.jcl:43}, {@code //STEP02 EXEC PGM=CBEXPORT}, which carries no
     * {@code PARM}. Every record it writes is 500 bytes wide: the preparatory step at
     * {@code app/jcl/CBEXPORT.jcl:24} defines the target cluster with
     * {@code RECORDSIZE(500 500)} at line 33 and a four-byte key at offset 28 at line 32, and the
     * program reads five separate indexed inputs -- customer, account, cross-reference, transaction
     * and card -- into that one multi-record layout, whose monetary fields are packed decimal rather
     * than the zoned decimal of the base masters.</p>
     *
     * <p>Assumptions: this token is unscheduled, not undriven, and the two are worth keeping apart.
     * It has a driver at the line cited above and is simply not wired into the nightly sequence, so
     * a reader who notices its absence from the chain has not found a missing driver. It is invoked
     * on demand.</p>
     *
     * <p>Assumptions: the reference pair behind this token and the next declares an FD
     * {@code RECORD KEY} naming a field that exists only in working storage and not in the file
     * record -- {@code app/cbl/CBEXPORT.cbl:68} and {@code app/cbl/CBIMPORT.cbl:40} both read
     * {@code RECORD KEY IS EXPORT-SEQUENCE-NUM}, while the copybook that declares that field is
     * included into working storage. The consequence is recorded in the parity oracle suite's
     * known-limitations section: the pair does not compile under the open-source compiler the suite
     * uses, so its integration test is skipped and only ten of the twelve reference batch programs
     * build there. The baseline declares it that way and keeps it; the Java implements the keying
     * that the 500-byte layout actually carries; and the divergence is registered in the
     * traceability document named on this type. Nothing about the reference source is changed by
     * this migration.</p>
     */
    EXPORT("export"),

    /**
     * Outside the nightly chain: the inverse round-trip that splits an export file back into
     * normalised datasets, token {@code import}.
     *
     * <p>Re-expresses {@code app/cbl/CBIMPORT.cbl}, 487 lines, driven by
     * {@code app/jcl/CBIMPORT.jcl:22}, {@code //STEP01 EXEC PGM=CBIMPORT}, which carries no
     * {@code PARM}. It reads the 500-byte multi-record export file produced under {@link #EXPORT}
     * and writes four normalised outputs plus an error stream, declared at
     * {@code app/jcl/CBIMPORT.jcl:33-60} at record lengths 500, 300, 50, 350 and 132 -- which is
     * what makes the pair a round-trip rather than two unrelated utilities. The record-key
     * observation stated on {@link #EXPORT} applies to this program equally, at
     * {@code app/cbl/CBIMPORT.cbl:40}.</p>
     *
     * <p>Trade-offs: this constant is named {@code IMPORT} while its token is {@code import},
     * because {@code import} is a Java reserved word and cannot be an identifier. The type-level
     * documentation records why the divergence is accepted in that direction; the practical
     * consequence at this member is narrow and worth stating where it bites: a consumer that derived
     * the token by lower-casing {@link #name()} would get {@code import} here by luck and
     * {@code preflight_daily_transactions} elsewhere, so {@link #token()} is the only supported way
     * to obtain any of the seven.</p>
     */
    IMPORT("import");

    /**
     * The wire token this constant is asked for by, exactly as the argument contract spells it.
     *
     * <p>Assumptions: the field is final and the string it holds is immutable, so a constant cannot
     * be re-pointed at a different token after class initialisation and every reader of
     * {@link #token()} sees the same bytes for the life of the process. Holding the token in a field
     * populated from each constant's argument list, rather than deriving it from {@link #name()} by
     * a case transformation, is what lets {@code export} and {@code import} be bare words while the
     * other five are kebab-case verb phrases: no single transformation of the constant names
     * produces all seven tokens, and one that produced five of them would fail on exactly the two
     * that the type-level documentation records as the contract's deliberate asymmetry.</p>
     */
    private final String token;

    /**
     * Binds one constant to the token it is asked for by.
     *
     * @param token the wire token for this constant, supplied as a literal in the constant's own
     *     argument list above and required to be byte-identical to the corresponding entry of the
     *     argument contract declared by {@code com.carddemo.batch.BatchApplication}
     */
    private BatchJobName(String token) {
        // WHY : Trade-offs: the argument is stored as received, with no trimming, case folding or
        //       normalisation of any kind. Every one of those would make this constructor capable of
        //       silently adjusting a token that had been mistyped in the argument list, and a
        //       silently adjusted token is worse than a rejected one here: the module would compile
        //       and run while asking the orchestrator for a job under a spelling no state definition
        //       uses.
        //       The tokens are literals eight lines apart in one file, so a mismatch is visible to a
        //       reader and is additionally asserted by test against the entry point's own list.
        this.token = token;
    }

    /**
     * Returns the wire token this constant is asked for by.
     *
     * @return the token, byte-identical to its entry in the argument contract; never {@code null} and
     *     never derived from {@link #name()}
     */
    public String token() {
        return token;
    }

    /**
     * Resolves a token received on the container command line to the job it names.
     *
     * <p>The comparison is exact and case-sensitive, for the reason recorded on this type: the
     * process entry point tests membership of its own lowercase list, so folding case here would make
     * this type accept an argument that entry point has already rejected.</p>
     *
     * @param candidateToken the token to resolve, as received from the {@code --job=} argument;
     *     {@code null}, an empty string and a blank string are all accepted as input and all
     *     rejected as values, because none of them is one of the seven
     * @return the job the token names, never {@code null}
     * @throws IllegalArgumentException if {@code candidateToken} is not byte-identical to one of the
     *     seven tokens, including when it is {@code null} or blank; the message names the offending
     *     token so that a container log identifies the malformed command without access to this
     *     source
     */
    public static BatchJobName resolve(String candidateToken) {
        // WHY : Alternatives Considered: returning null, returning an empty optional, or falling back
        //       to a default were all evaluated and all rejected. The argument contract declares no
        //       default job -- the orchestrator always supplies --job= explicitly through its
        //       container overrides -- so there is no job that is legitimately the one to run when
        //       the token is unrecognised, and a fallback would run the wrong job to completion
        //       against production data with nothing in the log marking it as wrong. Null and an
        //       empty optional both push the same decision onto every caller, and a caller that
        //       forgot to make it would fail later, further from the malformed argument that caused
        //       it. Raising here is the only outcome that stops the task at the boundary where the
        //       bad command entered.
        for (BatchJobName job : values()) {
            // WHY : Assumptions: the receiver of the comparison is the constant's own token, so a
            //       null argument answers false seven times and falls through to the rejection below
            //       rather than raising a null-pointer failure from inside the loop. That ordering is
            //       load-bearing: a null token means the argument list was assembled wrongly, which
            //       is the same class of fault as a misspelled token and deserves the same message,
            //       whereas a null-pointer failure would read as a defect in this class.
            if (job.token.equals(candidateToken)) {
                return job;
            }
        }
        // WHY : Trade-offs: the accepted set is spelled into the message from values() rather than
        //       from a second literal list. A literal list would declare the contract twice in one
        //       file and could drift from the constants above without failing anything, which is the
        //       failure this type exists to prevent. The accepted cost is that the message is built
        //       on the failure path only, in a method that then raises, so the extra work is never
        //       done by a run that resolves successfully.
        StringBuilder accepted = new StringBuilder();
        for (BatchJobName job : values()) {
            if (accepted.length() > 0) {
                accepted.append(", ");
            }
            accepted.append('\'').append(job.token).append('\'');
        }
        throw new IllegalArgumentException("unrecognised batch job token: '" + candidateToken
                + "'; expected exactly one of " + accepted);
    }
}
