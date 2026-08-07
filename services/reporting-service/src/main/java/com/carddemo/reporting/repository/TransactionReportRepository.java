package com.carddemo.reporting.repository;

import com.carddemo.common.money.Money;
import com.carddemo.reporting.domain.ReportTransactionView;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The report-side query surface, standing in for the four-way join {@code app/cbl/CBTRN03C.cbl}
 * opens.
 *
 * <h2>Purpose</h2>
 *
 * <p>The report path joins four participants, evidenced by the data definitions at
 * {@code app/jcl/TRANREPT.jcl} L65-L72 and corroborated by the {@code COPY} statements at
 * {@code app/cbl/CBTRN03C.cbl} L93, L98, L103 and L108: the transaction record, the card
 * cross-reference, the transaction type and the transaction category. The parameter file at
 * {@code TRANREPT.jcl} L73-L74 supplies the reporting date range and is not a join participant, which
 * is why the range arrives here as two arguments rather than as a fifth relation.
 *
 * <h2>Assumptions: the date predicate is a half-open instant range, and it means an inclusive
 * date range</h2>
 *
 * <p>{@code app/jcl/TRANREPT.jcl} L47-L48 carries
 * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND, TRAN-PROC-DT,LE,PARM-END-DATE)}, and
 * {@code app/cbl/CBTRN03C.cbl} L173-L174 corroborates it independently by comparing the first ten
 * characters of the processing timestamp against the two endpoints with the same operators. Both
 * bounds are inclusive and both act on the <b>date part alone</b>. Register entry <b>R5</b> records
 * that this is a record-selection predicate and becomes a {@code WHERE} clause, never a control-flow
 * branch.
 *
 * <p>Alternatives Considered: comparing a date extracted from the timestamp column, which is the
 * literal transcription of the baseline's ten-character comparison. Rejected on two independent
 * grounds. A function applied to the column makes the index over that column unusable, so a report
 * over one day would scan the whole relation; and the extraction has to agree with the engine's
 * notion of a date boundary, which introduces a time-zone question the baseline does not have. The
 * shape adopted instead is a half-open instant range -- greater than or equal to the start of the
 * first day, strictly less than the start of the day <b>after</b> the last -- which selects exactly
 * the same rows as an inclusive date range, keeps the predicate on the bare column, and has no
 * boundary ambiguity at all. The conversion from two dates to those two instants belongs to the
 * caller, which is where the business dates arrive; the two parameters here are instants so that this
 * interface cannot be handed a date and silently truncate a day.
 *
 * <h2>Assumptions: the join is an inner join plus an integrity reconciliation</h2>
 *
 * <p>Register entry <b>R10</b> settles this and the reasoning is not restated here, only its
 * consequence for this interface: an inner join alone would <b>discard</b> a transaction whose type,
 * category or cross-reference is absent, where the baseline <b>abends</b> -- three paragraphs at
 * {@code app/cbl/CBTRN03C.cbl} L484-L492, L494-L502 and L504-L512 each read with an invalid-key
 * branch that displays the offending key and then calls the abend service. Discarding produces
 * different bytes, different account and grand totals and different page boundaries, which is a silent
 * parity failure. This interface therefore exposes <b>two counts as well as the join</b>: the driving
 * count the date predicate admits, and the count the join yields. A caller compares them and raises on
 * any shortfall, which is where the unresolved key is named.
 *
 * <h2>Assumptions: the ordering carries a stable secondary key the baseline does not declare</h2>
 *
 * <p>Register entry <b>R6</b> records that {@code app/jcl/TRANREPT.jcl} L46 is
 * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} -- one key with no equal-records qualifier -- so the relative
 * order of two rows sharing a card number is whatever the sort happens to produce, and reproducing that
 * literally would leave the output non-deterministic. Every ordering below therefore adds the
 * transaction identifier behind the card number. That two of the baseline's own sorts are already total
 * is what shows the single-key declaration to be an omission in that one member rather than a house
 * style: {@code app/jcl/CREASTMT.JCL} L53 declares two keys and {@code app/jcl/PRTCATBL.jcl} L52
 * declares three.
 *
 * <h2>Assumptions: the join row is a nested projection and not an eighth file</h2>
 *
 * <p>Alternatives Considered: declaring the joined row as its own type in the sibling
 * {@code domain} package. Rejected because that package's charter states a closed set of eight files
 * and a ninth would be a defect rather than an addition -- and because a joined row is not a
 * projection of one relation, which is what every type in that package is. Alternatives Considered: a
 * sixth file in this package. Rejected for the same reason at this level: the charter beside this file
 * states a closed set of six. The joined row is therefore declared as a nested closed interface
 * projection below, which adds no file to either package and keeps the row's shape beside the query
 * that produces it.
 *
 * <h2>Assumptions: extending the marker interface is the read-only mechanism</h2>
 *
 * <p>Alternatives Considered: extending {@code CrudRepository} or {@code JpaRepository}. Rejected for
 * the reason the sibling account role records -- either would inherit five write methods onto a type
 * whose whole contract is that it has none.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The decision register that governs every choice in this package is authored once in
 * {@code package-info.java} beside this file, and entries are cited by identifier rather than
 * restated. Every member below carries a docstring because user-specified Rule 1 (Explainability)
 * attaches its presence clause to every function and names no visibility.
 */
public interface TransactionReportRepository extends Repository<ReportTransactionView, String> {

    /**
     * One fully resolved report line: a transaction with its three joined dimensions.
     *
     * <p>Assumptions: this is a <b>closed</b> interface projection, so the query below has to supply an
     * alias for every accessor declared here and the engine fetches exactly those columns. An open
     * projection -- one carrying a derived accessor -- would fetch the whole root entity behind the
     * scenes and would then hide which columns the report actually depends on, which is the one thing
     * this type exists to make visible.</p>
     *
     * <p>Assumptions: the accessors follow the get-prefixed form rather than the record-component form,
     * because Spring Data resolves an interface projection's accessors by property name and the
     * prefixed form is the one it resolves without configuration. The sibling payload type in the
     * mapper package uses the record form for the same values, and the two are not interchangeable:
     * that one is a serialisation contract and this one is a query result.</p>
     */
    interface ReportLine {

        /**
         * Returns the transaction identifier, which is also the ordering key behind the card number.
         *
         * @return the sixteen-character identifier declared at {@code app/cpy/CVTRA05Y.cpy} L5
         */
        String getTransactionId();

        /**
         * Returns the account the transaction's card is issued against.
         *
         * <p>Assumptions: this is the one member of a report line that comes from the cross-reference
         * rather than from the transaction, which is why the cross-reference is a join participant at
         * all. {@code app/cbl/CBTRN03C.cbl} resolves it at {@code 1500-A-LOOKUP-XREF} L484-L492.</p>
         *
         * @return the account identifier declared {@code PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy}
         *     L7
         */
        Long getAccountId();

        /**
         * Returns the two-character transaction type code.
         *
         * @return the code declared at {@code app/cpy/CVTRA05Y.cpy} L6
         */
        String getTypeCd();

        /**
         * Returns the description of that transaction type.
         *
         * <p>Assumptions: this is the TYPE description declared at {@code app/cpy/CVTRA03Y.cpy} L6 and
         * is a different value from the CATEGORY description below, declared at
         * {@code app/cpy/CVTRA04Y.cpy} L8. Both are fifty characters, so width cannot distinguish
         * them, and {@code app/cbl/CBTRN03C.cbl} moves them to two different report fields at L366 and
         * L368.</p>
         *
         * @return the fifty-character type description
         */
        String getTypeDescription();

        /**
         * Returns the four-character transaction category code.
         *
         * @return the code declared at {@code app/cpy/CVTRA05Y.cpy} L7, leading zeros intact
         */
        String getCategoryCd();

        /**
         * Returns the description of that transaction category.
         *
         * @return the fifty-character category description declared at {@code app/cpy/CVTRA04Y.cpy}
         *     L8
         */
        String getCategoryDescription();

        /**
         * Returns the ten-character origin of the transaction.
         *
         * @return the source declared at {@code app/cpy/CVTRA05Y.cpy} L8
         */
        String getSource();

        /**
         * Returns the transaction amount as an exact decimal.
         *
         * <p>Assumptions: the type is the shared exact decimal and never a binary floating point
         * quantity. {@code TRAN-AMT} is declared {@code PIC S9(09)V99} at
         * {@code app/cpy/CVTRA05Y.cpy} L10, which IEEE-754 binary floating point cannot represent
         * exactly, and the prohibition is asserted for the whole reactor by the layering rules held in
         * {@code LayeringRulesTest}.</p>
         *
         * @return the amount at scale 2
         */
        Money getAmount();
    }

    /**
     * Reads the first page of report lines inside a processing-date range.
     *
     * <p>Assumptions: the range is expressed as two instants forming a half-open interval, for the
     * reason the type-level charter gives, and the caller converts the two business dates into them.
     * Both joins are inner, and a shortfall against {@link #countDrivingRows} is what tells the caller
     * that a dimension was unresolvable.</p>
     *
     * @param rangeStart the start of the first day in the range, inclusive; must not be {@code null}
     * @param rangeEnd the start of the day after the last day in the range, exclusive; must not be
     *     {@code null}
     * @param limit the maximum number of rows to return, which the caller sizes one row beyond the
     *     page it intends to render; must not be {@code null}
     * @return the resolved report lines in card-first order with the transaction identifier behind it,
     *     at most {@code limit} of them, possibly empty; never {@code null}
     */
    @Query("""
            select t.transactionId as transactionId,
                   x.accountId as accountId,
                   t.typeCd as typeCd,
                   ty.typeDescription as typeDescription,
                   t.categoryCd as categoryCd,
                   c.categoryDescription as categoryDescription,
                   t.source as source,
                   t.amount as amount
            from ReportTransactionView t
            join CardXrefView x on x.cardNum = t.cardNum
            join TransactionTypeView ty on ty.typeCd = t.typeCd
            join TransactionCategoryView c
                on c.key.typeCode = t.typeCd and c.key.categoryCode = t.categoryCd
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
            order by t.cardNum asc, t.transactionId asc
            """)
    List<ReportLine> findReportLines(
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd,
            Limit limit);

    /**
     * Reads the page of report lines that follows a stated card and transaction identifier.
     *
     * <p>Assumptions: the continuation predicate compares the ordering pair lexicographically rather
     * than comparing the card number alone, so a card whose transactions span a page boundary
     * continues inside that card rather than skipping to the next one. Comparing the card number alone
     * would drop every remaining transaction of the card the page ended inside, which the report's
     * account total would then be short by.</p>
     *
     * @param cardNum the masked card number of the last row already returned; must not be {@code null}
     * @param transactionId the transaction identifier of the last row already returned; must not be
     *     {@code null}
     * @param rangeStart the start of the first day in the range, inclusive; must not be {@code null}
     * @param rangeEnd the start of the day after the last day in the range, exclusive; must not be
     *     {@code null}
     * @param limit the maximum number of rows to return, sized one beyond the page; must not be
     *     {@code null}
     * @return the following resolved report lines in the same order, at most {@code limit} of them,
     *     empty when the range has been exhausted; never {@code null}
     */
    @Query("""
            select t.transactionId as transactionId,
                   x.accountId as accountId,
                   t.typeCd as typeCd,
                   ty.typeDescription as typeDescription,
                   t.categoryCd as categoryCd,
                   c.categoryDescription as categoryDescription,
                   t.source as source,
                   t.amount as amount
            from ReportTransactionView t
            join CardXrefView x on x.cardNum = t.cardNum
            join TransactionTypeView ty on ty.typeCd = t.typeCd
            join TransactionCategoryView c
                on c.key.typeCode = t.typeCd and c.key.categoryCode = t.categoryCd
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
              and (t.cardNum > :cardNum
                   or (t.cardNum = :cardNum and t.transactionId > :transactionId))
            order by t.cardNum asc, t.transactionId asc
            """)
    List<ReportLine> findReportLinesAfter(
            @Param("cardNum") String cardNum,
            @Param("transactionId") String transactionId,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd,
            Limit limit);

    /**
     * Counts the transactions the date predicate admits, before any dimension is joined.
     *
     * <p>Assumptions: this is the first half of the integrity reconciliation register entry <b>R10</b>
     * prescribes. It counts the driving relation alone, so it is unaffected by whether a type, a
     * category or a cross-reference row exists for any given transaction.</p>
     *
     * @param rangeStart the start of the first day in the range, inclusive; must not be {@code null}
     * @param rangeEnd the start of the day after the last day in the range, exclusive; must not be
     *     {@code null}
     * @return the number of transactions inside the range, zero when the range holds none
     */
    @Query("""
            select count(t)
            from ReportTransactionView t
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
            """)
    long countDrivingRows(
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd);

    /**
     * Counts the report lines the three inner joins yield inside the same range.
     *
     * <p>Assumptions: this is the second half of the reconciliation. Its predicate and its joins are
     * character for character those of {@link #findReportLines}, because a count taken under any other
     * predicate would reconcile against a different population and would report a shortfall that did
     * not exist -- or, worse, fail to report one that did.</p>
     *
     * @param rangeStart the start of the first day in the range, inclusive; must not be {@code null}
     * @param rangeEnd the start of the day after the last day in the range, exclusive; must not be
     *     {@code null}
     * @return the number of fully resolved report lines inside the range, which equals
     *     {@link #countDrivingRows} exactly when every dimension resolved
     */
    @Query("""
            select count(t)
            from ReportTransactionView t
            join CardXrefView x on x.cardNum = t.cardNum
            join TransactionTypeView ty on ty.typeCd = t.typeCd
            join TransactionCategoryView c
                on c.key.typeCode = t.typeCd and c.key.categoryCode = t.categoryCd
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
            """)
    long countJoinedRows(
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd);

    /**
     * Reads the transactions the date predicate admits, without joining a dimension.
     *
     * <p>Assumptions: this exists so that a reconciliation shortfall can name the offending row rather
     * than only its count. Register entry <b>R10</b> requires the refusal to name the unresolved key,
     * which is what the three baseline lookup paragraphs do when they display the offending key before
     * abending, and a caller cannot name what it never read. It is a diagnostic path and is issued only
     * when the two counts disagree.</p>
     *
     * @param rangeStart the start of the first day in the range, inclusive; must not be {@code null}
     * @param rangeEnd the start of the day after the last day in the range, exclusive; must not be
     *     {@code null}
     * @param limit the maximum number of rows to return, which bounds the diagnostic rather than the
     *     report; must not be {@code null}
     * @return the driving transactions in the same order the report uses, at most {@code limit} of
     *     them, possibly empty; never {@code null}
     */
    @Query("""
            select t
            from ReportTransactionView t
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
            order by t.cardNum asc, t.transactionId asc
            """)
    List<ReportTransactionView> findDrivingRows(
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd,
            Limit limit);
}
