package com.carddemo.batch.repository;

import com.carddemo.batch.domain.DisclosureGroup;
import java.util.Optional;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the interest rate the accrual step applies, by a disclosure group's whole three-part key.
 *
 * <p>The table behind this interface is {@code reference.disclosure_groups}, and one row of it
 * carries the rate that applies to one account group, transaction type and transaction category
 * taken together. The reference context OWNS and seeds that table; this module reads it as a
 * projection and holds no authority over it whatsoever. The provenance is a single VSAM cluster
 * mounted for the interest job: {@code app/jcl/INTCALC.jcl:35-36} supplies
 * {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS} under the data-definition name {@code DISCGRP}, and
 * {@code app/cbl/CBACT04C.cbl:47-51} declares it {@code ORGANIZATION IS INDEXED} with
 * {@code ACCESS MODE IS RANDOM} and {@code RECORD KEY IS FD-DISCGRP-KEY} -- a keyed store, reached
 * one key at a time.</p>
 *
 * <h2>Why the base type is narrowed rather than inherited</h2>
 *
 * <p>Alternatives Considered: {@code JpaRepository} and {@code CrudRepository} were both evaluated
 * and both rejected, because each inherits {@code save}, {@code saveAll}, {@code delete},
 * {@code deleteAll} and -- for the former -- {@code flush}. This module holds NO WRITE GRANT ON THE
 * {@code reference} SCHEMA: its database role's cross-schema write grants are scoped to
 * {@code ledger} and {@code account} only, and the local entity is a read-only projection of a
 * table the reference context owns and seeds. An inherited mutator would therefore compile cleanly,
 * pass code review, and fail AT THE DATABASE partway through a nightly batch step, with earlier
 * steps of the chain already committed. Narrowing the base interface moves that failure from run
 * time to non-existence, because the method is never surfaced at all.
 * {@code org.springframework.data.repository.Repository} contributes no member of its own while
 * still giving Spring Data enough to build a proxy, so the reachable surface is exactly the one
 * method declared below.</p>
 *
 * <p>Trade-offs: the compromise accepted is that convenience methods must be declared explicitly
 * rather than inherited, which is a small amount of extra declaration in exchange for making an
 * unavailable capability unreachable. The narrow base is what turns the read-only guarantee into a
 * structural property rather than a convention that a later edit could relax by adding one call.</p>
 *
 * <p>Assumptions: the owning context has itself declined to model any write path reachable from
 * here, so no capability is being withheld that the mapping would otherwise support.
 * {@link DisclosureGroup} carries Hibernate's immutability marker and declares no version column,
 * and its three key components are mapped non-updatable. A mutator on this interface would offer a
 * capability that neither the grant nor the mapping admits.</p>
 *
 * <h2>The composite key, and the one paragraph that appears to contradict it</h2>
 *
 * <p>Assumptions: the key is three parts in a fixed physical order -- the account group identifier,
 * then the transaction type code, then the transaction category code. THREE INDEPENDENT SOURCES
 * agree, which is why this is stated as fact rather than inferred.
 * {@code app/cpy/CVTRA02Y.cpy:5-8} declares them in that order inside the {@code DIS-GROUP-KEY}
 * group as {@code PIC X(10)}, {@code PIC X(02)} and {@code PIC 9(04)};
 * {@code app/jcl/DISCGRP.jcl:40} defines the cluster with {@code KEYS(16 0)}, a sixteen-byte key at
 * offset zero, which is exactly ten plus two plus four; and the owning migration
 * {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} declares
 * {@code pk_disclosure_groups} over {@code (acct_group_id, tran_type_cd, tran_cat_cd)} in that same
 * sequence.</p>
 *
 * <p>Assumptions: ONE REFERENCE PARAGRAPH READS EXACTLY LIKE A CONTRADICTION OF THAT ORDER, and it
 * is recorded here so the order below is not re-ordered to match it.
 * {@code app/cbl/CBACT04C.cbl:210-212} populates the key fields in the order group identifier, then
 * CATEGORY code, then TYPE code -- the reverse of the physical order for the last two. That is an
 * artefact of the sequence of {@code MOVE} statements inside a paragraph and not a statement about
 * the key at all: all three fields are populated before the read is issued at
 * {@code app/cbl/CBACT04C.cbl:416}, and the key is a contiguous group whose layout the copybook
 * fixes independently of the order in which its parts are filled in. Anyone reading only that
 * paragraph would conclude the key order is group, category, type, and would be wrong.</p>
 *
 * <h2>The default-group fallback, which this interface deliberately does not implement</h2>
 *
 * <p>Trade-offs: the two-step fallback is control flow, and control flow belongs to the service
 * layer, so no {@code findDefault} or {@code findWithFallback} convenience method is declared here.
 * The migration plan places it in {@code com.carddemo.batch.service.InterestCalculationService},
 * which calls the one method below twice -- once with the requested key and, on absence, once with a
 * substituted group identifier. The compromise accepted is that the caller writes two lines instead
 * of one. Hiding the retry behind a single repository method would conceal a branch that the
 * golden-master comparison observes, and would make it impossible to tell from a caller's code
 * whether a rate came from the account's own group or from the fallback -- which is precisely the
 * distinction that caller's own result type exists to report.</p>
 *
 * <p>Assumptions: the reference retries EXACTLY ONCE. {@code app/cbl/CBACT04C.cbl:436-439} detects
 * the record-not-found file status, substitutes the default-group literal into the group-identifier
 * portion of the key, and re-reads. The retry key is therefore the substituted group identifier with
 * the ORIGINAL transaction type and category codes carried through unchanged.</p>
 *
 * <p>Assumptions: the group-identifier component is TEN CHARACTERS WIDE and the value a caller
 * hands this method must be the space-padded form. The reference establishes the width:
 * {@code app/cpy/CVTRA02Y.cpy:6} declares {@code PIC X(10)}, so the seven-character literal moved at
 * {@code app/cbl/CBACT04C.cbl:437} is space-padded to ten by the {@code MOVE} itself. The target
 * agrees -- the owning migration declares {@code acct_group_id CHAR(10)} and argues that the padding
 * is part of the key rather than incidental -- and a stored value there occupies ten bytes.</p>
 *
 * <p>Assumptions: the layer at which that padding is load-bearing is worth stating exactly, because
 * the obvious guess is wrong and a reader who tests only the obvious guess will conclude the padding
 * is unnecessary. It is NOT the SQL comparison. Because the column is {@code CHAR}, PostgreSQL
 * compares it with blank-padded semantics and pads the shorter operand, so a short group identifier
 * still matches the stored row and this lookup is forgiving of it. The padding is load-bearing ONE
 * LAYER UP, in Java: what this method returns carries the group identifier in its padded form, and
 * the accruing service compares the key it asked for against the key that answered -- by record
 * equality -- in order to report whether the rate came from the account's own group or from the
 * fallback. A short identifier compares unequal to the padded one it reads back, which would make
 * that report wrong while every row involved was correct. This is why the caller's key type refuses
 * a short group identifier outright rather than padding it silently, and why this method documents
 * the requirement instead of normalising the value on arrival: normalising here would make the two
 * forms indistinguishable again at precisely the point the distinction is needed.</p>
 *
 * <p>Assumptions: the required seed shape follows from that retry key. Because the retry keeps the
 * original type and category codes, a single default-group row is NOT sufficient: the seed must
 * supply one row per distinct type-and-category pair that any account can present. Those rows are
 * authored by the reference context's reference-data seed migration
 * {@code services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql}, which
 * carries seventeen of them in the padded form. Their existence is therefore a CROSS-SERVICE
 * PRECONDITION of this repository's caller rather than a defect this module could remedy -- and
 * this module must not seed them, on start-up or through a migration of its own.</p>
 *
 * <p>Assumptions: the fallback is one retry deep and then fatal, and BOTH HALVES of that matter
 * here. On the first read {@code app/cbl/CBACT04C.cbl:422} accepts the record-not-found status
 * alongside success and only a third status abends, at
 * {@code app/cbl/CBACT04C.cbl:431-434}; on the retry {@code app/cbl/CBACT04C.cbl:444} issues the
 * read with NO invalid-key handler at all and {@code app/cbl/CBACT04C.cbl:446} accepts only a clean
 * status, so a second absence reaches the abend at {@code app/cbl/CBACT04C.cbl:455-458}. The
 * asymmetry lives entirely in the caller: this repository reports absence NEUTRALLY with an empty
 * optional on both reads and raises on neither, and treating the second absence as fatal is the
 * caller's obligation.</p>
 *
 * <h2>The rate this interface returns</h2>
 *
 * <p>Assumptions: the rate is exact fixed point. {@code app/cpy/CVTRA02Y.cpy:9} declares
 * {@code DIS-INT-RATE PIC S9(04)V99} -- signed, four integer digits and two fractional -- so the
 * column is a two-scale numeric and {@link DisclosureGroup} holds it as a {@code BigDecimal} at
 * scale two. The migration plan's transformation rule T3 forbids {@code float}, {@code double} and
 * their wrappers anywhere in the money path, and rule A4 of
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * fails the build on a binary floating-point type in any member position, so the prohibition is
 * executable rather than advisory.</p>
 *
 * <p>Assumptions: a ZERO RATE IS MEANINGFUL DATA, not a missing row.
 * {@code app/cbl/CBACT04C.cbl:214} tests the rate against zero and skips the interest computation
 * when it is zero, so a caller must distinguish "no row" from "a row whose rate is zero". That is
 * the second reason the method below returns an optional of the ENTITY rather than an optional of
 * the rate: collapsing the two would make an explicitly seeded zero-rate group indistinguishable
 * from an absent one, and the seed does carry such groups.</p>
 *
 * <p>Assumptions: the computation that consumes this rate at
 * {@code app/cbl/CBACT04C.cbl:464-465} multiplies before dividing and carries NO rounding phrase,
 * so the reference truncates. The arithmetic and its rounding mode belong to the accruing service
 * under the migration plan's transformation rules T3 and T4, not to this interface, which performs
 * no arithmetic on the rate at all. The constraint is recorded beside the rate so that a future
 * reader does not conclude the scale is arbitrary, and so that the multiply-before-divide order is
 * not re-ordered anywhere: dividing first changes intermediate precision and therefore changes
 * cents.</p>
 *
 * <h2>What this interface deliberately does not declare</h2>
 *
 * <p>Refactoring Rationale: the reference reaches this table with a keyed read and with nothing
 * else, and under the migration plan's transformation rule T5 that verb category maps to a
 * repository method -- and to no more than one. Every access across
 * {@code app/cbl/CBACT04C.cbl:415-460} is such a read; there is no browse, no sequential walk and
 * no positioning verb anywhere against this file. So there is no by-group listing here, no
 * by-rate query, no count and no ordered walk. Adding a scan would invent an access path the
 * reference does not have. The contrast inside the same program is what makes the absence a
 * measurement rather than an omission: that program opens the category-balance cluster
 * sequentially and walks it, and it opens this one for random keyed access only.</p>
 *
 * <p>Alternatives Considered: a native query, which is a standing temptation in a batch module
 * because a nightly pass is naturally set-shaped. Rejected on the timing of the failure it admits.
 * This module runs the persistence provider with schema handling set to validation, and that pass
 * compares MAPPING METADATA against the deployed table; it never parses the text of a native
 * statement. A property path that resolves to a column the schema does not have is therefore
 * reported at start-up, before a row is read, whereas a mistyped physical column inside a native
 * statement stays invisible until that statement executes -- which for this module means part-way
 * through a nightly chain. The method below is bound to a property {@link DisclosureGroup}
 * declares, so no physical column name appears anywhere in this file.</p>
 *
 * <p>Assumptions: the reference context declares an interface of the SAME SIMPLE NAME over the same
 * table, and the duplication must not be resolved by reaching for it. That is barred twice over.
 * It is typed on a {@code com.carddemo.reference.domain} entity, so importing it would take a
 * dependency on a foreign {@code domain} class and fail rule A3 of the layering test cited above,
 * which is enforced as a test rather than as a review comment; and the reference context is not a
 * Maven dependency of this module at all, so that type is not on this module's compile classpath to
 * be imported in the first place. Mapping this table locally is the deliberate consequence of that
 * boundary, not an oversight to be tidied away.</p>
 *
 * <p>Assumptions: the physical shape of this table belongs to the owning context's migrations, so
 * this interface declares no index, no constraint and no schema object of any kind. The primary key
 * cited above already serves the only query declared here, and a second declaration from a module
 * holding no authority over the {@code reference} schema would be a duplicate that nothing could
 * detect drifting from the first.</p>
 *
 * <h2>How the citations above are to be read</h2>
 *
 * <p>Assumptions: every {@code app/} path cited above is reference material, read for provenance
 * only. Nothing under {@code app/} is read at run time and nothing under it is modified by this
 * migration: the reference programs remain byte-identical and keep running, which is precisely what
 * lets them serve as the oracle this module is measured against. Line numbers refer to the source
 * as committed, and columns 73 to 80 of a COBOL or JCL line carry a sequence field that is not part
 * of the statement. Where the two implementations differ, the permitted framing is settled and
 * narrow: the reference program does one thing, the migrated job does another, and the difference
 * is recorded in the divergence register at
 * {@code docs/architecture/cobol-to-service-traceability.md}. Nothing here is described as amending
 * or improving upon a reference behaviour, because that behaviour is the specification this work is
 * compared against.</p>
 *
 * @see DisclosureGroup
 */
public interface DisclosureGroupRepository
        extends Repository<DisclosureGroup, DisclosureGroup.DisclosureGroupId> {

    /**
     * Reads one disclosure group by its whole three-part key.
     *
     * <p>This is the entire access path this module has to the rate table, and it is the method the
     * accruing service calls for BOTH of the reference's two reads -- once with the key built from
     * the account's own group, and, when that returns empty, once more with the group identifier
     * replaced by the padded default-group value. The substitution is the caller's; this method
     * simply answers whichever key it is handed.</p>
     *
     * @param id the whole composite key as a {@code DisclosureGroup.DisclosureGroupId} -- the
     *     ten-character account group identifier, the two-character transaction type code and the
     *     four-character transaction category code that together form the sixteen-byte group at
     *     {@code app/cpy/CVTRA02Y.cpy:5-8}; the group identifier must arrive space-padded to its
     *     full declared width, for the reason recorded on this interface; must not be {@code null}
     * @return the one matching row as an {@code Optional<DisclosureGroup>}, or an EMPTY optional
     *     when no group is keyed by that combination. <b>An empty result is a NORMAL outcome and
     *     not an error</b>: on the first read it is what triggers the fallback, and on the retry it
     *     is what the caller escalates. This method reports absence and never raises on it
     * @throws org.springframework.dao.DataAccessException if the read itself cannot be carried out
     *     -- most usefully, a permission failure when the {@code SELECT} grant recorded on this
     *     interface is missing, which surfaces here rather than at start-up because a privilege is
     *     checked when a statement executes and not when the mapping is validated
     */
    // Assumptions: the name is inherited from a caller contract rather than chosen freshly here.
    //     The accruing service and its tests already bind to this spelling, and the package's other
    //     composite-key interface, TransactionCategoryBalanceRepository, spells its equivalent read
    //     the same way, so one idiom covers both of the module's composite keys. The parameter shape
    //     is the decision that actually mattered: the whole declared identity type is taken as ONE
    //     argument rather than restating its three parts as three parameters, because the identity
    //     is already a type with equality and a fixed component order, and a three-argument finder
    //     would let a caller transpose two same-width components silently. The identifier is written
    //     in qualified DisclosureGroup.DisclosureGroupId form rather than imported bare, matching
    //     the sibling above, so that it is visibly the entity's OWN nested type rather than a
    //     same-named type from somewhere else.
    // Alternatives Considered: the propagation attribute the package attaches to its streaming
    //     reads, Propagation.MANDATORY, was evaluated for this method and deliberately NOT applied.
    //     It is correct there because a Stream holds an open server-side cursor and must not outlive
    //     the transaction that opened it, so a stream read with no surrounding transaction is a
    //     defect worth refusing outright. This read returns one detached row and completes before it
    //     returns, so it has no such lifetime requirement; MANDATORY would only add a failure mode,
    //     refusing a legitimate call made outside a transaction. The default propagation joins the
    //     step's transaction when there is one and opens its own when there is not, and both are
    //     correct for a single-row read.
    // Trade-offs: readOnly is retained even though it is not what enforces read-only access -- the
    //     narrow base type above and the absent write grant are. It earns its place by stating the
    //     method's intent at the method, and by setting the flush mode to manual so the surrounding
    //     persistence context cannot be flushed as a side effect of this lookup when the call does
    //     open its own transaction. The compromise accepted is that the attribute is silently
    //     ignored when this read joins an existing read-write transaction, which is the common case
    //     inside a batch step; it is kept because a misleading absence would read as a decision that
    //     read-only was inappropriate here.
    @Transactional(readOnly = true)
    // Assumptions: the single-result shape is a claim about the SCHEMA rather than a convenience of
    //     the return type. The owning migration keys this table on exactly these three columns as
    //     pk_disclosure_groups, so the key matches at most one row and the query is provably single
    //     valued. Were that constraint absent, this same signature would instead be a latent runtime
    //     failure, raised the first time a second row matched.
    Optional<DisclosureGroup> findByIdIs(DisclosureGroup.DisclosureGroupId id);
}
