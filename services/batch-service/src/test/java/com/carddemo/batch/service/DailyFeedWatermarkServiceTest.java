package com.carddemo.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.domain.DailyFeedWatermark;
import com.carddemo.batch.repository.DailyFeedWatermarkRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the rules that make the feed's consumed position safe to advance.
 *
 * <p>Refactoring Rationale: this class exists because the position it governs did not, and its
 * absence was a correctness defect rather than a missing convenience. The reference's posting job
 * reads its feed as a flat dataset that {@code app/jcl/POSTTRAN.jcl:30-31} presents fresh on every
 * run, so {@code app/cbl/CBTRN02C.cbl:202-219} reading it to end of file reads exactly one night's
 * transactions. The target's feed is a table that ACCUMULATES, because its rows are what the three
 * verification passes compare against, so a walk that began at the first row re-posted every
 * earlier night on every later night -- adding those amounts to account balances a second time and
 * inserting a second posted row for each. Every one of those postings is individually valid, so no
 * reject was written and no return code changed.</p>
 *
 * <p>Assumptions: these cases drive the REAL service over a mocked repository, because every rule
 * worth asserting here is the service's own -- the position an absent row reports, the refusal to
 * move backwards, the no-op that leaves the diagnostic columns alone, and which of the two reads
 * takes a lock. None of those is a property of the table.</p>
 */
@DisplayName("the daily feed's consumed position")
class DailyFeedWatermarkServiceTest {

    /** The feed every case addresses, named through the constant production code uses. */
    private static final String FEED = DailyFeedWatermarkService.DAILY_TRANSACTION_FEED;

    /** The moment the fixed clock reports, so an advance's recorded timestamp is assertable. */
    private static final LocalDateTime ADVANCED_AT = LocalDateTime.of(2022, 7, 18, 1, 2, 3);

    /** The business-date token every case records, in the separated layout the chain injects. */
    private static final String BUSINESS_DATE = "2022-07-18";

    /** The orchestrator execution every case records. */
    private static final String RUN_ID = "carddemo-daily-batch-2022-07-18";

    /** The stored positions, mocked so each case states what the feed had already consumed. */
    private DailyFeedWatermarkRepository watermarks;

    /** The service under test, over a clock fixed so the recorded moment is exact. */
    private DailyFeedWatermarkService service;

    /**
     * Builds the service over a fresh mock and a fixed clock before each case.
     */
    @BeforeEach
    void setUp() {
        this.watermarks = mock(DailyFeedWatermarkRepository.class);
        this.service = new DailyFeedWatermarkService(this.watermarks,
                Clock.fixed(ADVANCED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    /**
     * An absent row reports the position a first pass walks from.
     *
     * <p>Assumptions: absence is the documented spelling of "never consumed", because the migration
     * seeds no row -- a seeded row would have to invent a run identifier, a business date and a
     * timestamp that no run produced. So the value an absent row reports is a rule of this service,
     * and it has to be the exclusive bound below the feed's first ordinal: the feed's own identity
     * column starts at one.</p>
     */
    @Test
    @DisplayName("report nothing consumed when no row exists")
    void anAbsentRowReportsNothingConsumed() {
        when(this.watermarks.findByFeedName(FEED)).thenReturn(Optional.empty());
        when(this.watermarks.findById(FEED)).thenReturn(Optional.empty());

        assertThat(this.service.consumedThroughForConsumer(FEED))
                .isEqualTo(DailyFeedWatermarkService.NOTHING_CONSUMED);
        assertThat(this.service.consumedThroughForReader(FEED))
                .isEqualTo(DailyFeedWatermarkService.NOTHING_CONSUMED);
    }

    /**
     * The consuming read locks the row and the reporting read does not.
     *
     * <p>Assumptions: the difference between the two reads is the LOCK and nothing else, so it can
     * only be asserted by which repository method each one reaches -- the locked finder is declared
     * separately from the inherited one precisely because a lock annotation cannot be attached to an
     * inherited method. A reporting pass that took the lock would block the pass that actually
     * consumes, for no benefit, since it writes nothing.</p>
     */
    @Test
    @DisplayName("lock the row for a consumer and not for a reader")
    void onlyTheConsumingReadLocksTheRow() {
        when(this.watermarks.findByFeedName(FEED)).thenReturn(Optional.empty());
        when(this.watermarks.findById(FEED)).thenReturn(Optional.empty());

        this.service.consumedThroughForConsumer(FEED);
        verify(this.watermarks, times(1)).findByFeedName(FEED);
        verify(this.watermarks, never()).findById(anyString());

        this.service.consumedThroughForReader(FEED);
        verify(this.watermarks, times(1)).findById(FEED);
        verify(this.watermarks, times(1)).findByFeedName(FEED);
    }

    /**
     * A first advance inserts the row carrying the ordinal, the run and the injected date.
     */
    @Test
    @DisplayName("insert the row on a first advance")
    void aFirstAdvanceInsertsTheRow() {
        when(this.watermarks.findByFeedName(FEED)).thenReturn(Optional.empty());

        assertThat(this.service.recordConsumedThrough(FEED, 300L, RUN_ID, BUSINESS_DATE)).isTrue();

        ArgumentCaptor<DailyFeedWatermark> written =
                ArgumentCaptor.forClass(DailyFeedWatermark.class);
        verify(this.watermarks).save(written.capture());
        assertThat(written.getValue().getFeedName()).isEqualTo(FEED);
        assertThat(written.getValue().getLastIngestSeq()).isEqualTo(300L);
        assertThat(written.getValue().getRunId()).isEqualTo(RUN_ID);
        assertThat(written.getValue().getBusinessDate()).isEqualTo(BUSINESS_DATE);
        // WHY : the timestamp is asserted against the FIXED clock rather than against a range,
        //       because the value stored has to be the one the service read once -- a second reading
        //       inside the entity would differ by however long the write took, invisibly.
        assertThat(written.getValue().getUpdatedAt()).isEqualTo(ADVANCED_AT);
    }

    /**
     * A later advance moves the existing row rather than replacing it.
     *
     * <p>Assumptions: the existing instance is MUTATED, and that is asserted rather than treated as
     * an implementation detail, because the read that produced it holds a pessimistic write lock. An
     * implementation that saved a fresh instance instead would discard the locked managed instance,
     * which would make the lock's protection depend on how {@code save} happens to be implemented.</p>
     */
    @Test
    @DisplayName("move the existing row on a later advance")
    void aLaterAdvanceMovesTheExistingRow() {
        DailyFeedWatermark stored = new DailyFeedWatermark(FEED, 300L, "earlier-run",
                "2022-07-17", ADVANCED_AT.minusDays(1));
        when(this.watermarks.findByFeedName(FEED)).thenReturn(Optional.of(stored));

        assertThat(this.service.recordConsumedThrough(FEED, 600L, RUN_ID, BUSINESS_DATE)).isTrue();

        assertThat(stored.getLastIngestSeq()).isEqualTo(600L);
        assertThat(stored.getRunId()).isEqualTo(RUN_ID);
        assertThat(stored.getBusinessDate()).isEqualTo(BUSINESS_DATE);
        assertThat(stored.getUpdatedAt()).isEqualTo(ADVANCED_AT);
        verify(this.watermarks, never()).save(any(DailyFeedWatermark.class));
    }

    /**
     * A request that would not advance the position changes nothing at all.
     *
     * <p>Assumptions: BOTH the equal case and the lower case are exercised, because they arise
     * differently and a guard written with the wrong comparison passes one and fails the other. A
     * pass that consumed nothing reports the position it started from, which is the EQUAL case; a
     * redriven pass that the ledger let re-run may report a position an earlier attempt had already
     * passed, which is the LOWER case. Refusing either would turn a correct rerun into a failed
     * step.</p>
     *
     * <p>Assumptions: the assertion is on the RUN IDENTIFIER as well as the position, because the
     * position would be unchanged whether the service skipped the write or performed it with the same
     * value -- and only the run identifier can tell those apart. That column is what an operator reads
     * to learn which run consumed a range, so moving it onto a run that consumed nothing would be a
     * misleading record rather than a harmless write.</p>
     */
    @Test
    @DisplayName("change nothing when the request does not advance the position")
    void aNonAdvancingRequestIsANoOp() {
        DailyFeedWatermark stored = new DailyFeedWatermark(FEED, 600L, "earlier-run",
                "2022-07-17", ADVANCED_AT.minusDays(1));
        when(this.watermarks.findByFeedName(FEED)).thenReturn(Optional.of(stored));

        assertThat(this.service.recordConsumedThrough(FEED, 600L, RUN_ID, BUSINESS_DATE)).isFalse();
        assertThat(this.service.recordConsumedThrough(FEED, 599L, RUN_ID, BUSINESS_DATE)).isFalse();

        assertThat(stored.getLastIngestSeq()).isEqualTo(600L);
        assertThat(stored.getRunId()).isEqualTo("earlier-run");
        assertThat(stored.getBusinessDate()).isEqualTo("2022-07-17");
        verify(this.watermarks, never()).save(any(DailyFeedWatermark.class));
    }

    /**
     * A negative ordinal is refused before anything is read or written.
     *
     * <p>Assumptions: refusing here rather than letting the column's check constraint refuse means
     * the diagnosis names the offending VALUE. The advance happens at the end of a posting pass, so a
     * constraint violation would arrive attached to the commit of a night's work rather than to the
     * arithmetic that produced it.</p>
     */
    @Test
    @DisplayName("refuse a negative ordinal without touching the table")
    void aNegativeOrdinalIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> this.service.recordConsumedThrough(FEED, -1L, RUN_ID,
                        BUSINESS_DATE))
                .withMessageContaining("-1");

        verify(this.watermarks, never()).findByFeedName(anyString());
        verify(this.watermarks, never()).save(any(DailyFeedWatermark.class));
    }

    /**
     * A blank feed name is refused, because it would address a row the primary key forbids.
     */
    @Test
    @DisplayName("refuse a blank or absent feed name")
    void aBlankFeedNameIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> this.service.consumedThroughForConsumer("   "));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> this.service.consumedThroughForReader(""));
        assertThatNullPointerException()
                .isThrownBy(() -> this.service.recordConsumedThrough(null, 1L, RUN_ID,
                        BUSINESS_DATE));

        verify(this.watermarks, never()).findByFeedName(anyString());
        verify(this.watermarks, never()).findById(anyString());
    }

    /**
     * A business-date token of the wrong width is refused, and the compact layout is admitted.
     *
     * <p>Assumptions: the token is checked for WIDTH and not for a date layout, which is exactly what
     * {@code com.carddemo.batch.dto.BusinessDate} does and for the same reason: the reference injects
     * both layouts. {@code app/jcl/INTCALC.jcl:22} supplies {@code PARM='2022071800'}, ten characters
     * with no separators, so a layout check here would refuse a token the reference itself produces.</p>
     */
    @Test
    @DisplayName("admit either ten-character business-date layout and refuse any other width")
    void theBusinessDateTokenIsCheckedForWidthOnly() {
        when(this.watermarks.findByFeedName(FEED)).thenReturn(Optional.empty());

        assertThat(this.service.recordConsumedThrough(FEED, 1L, RUN_ID, "2022071800")).isTrue();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> this.service.recordConsumedThrough(FEED, 2L, RUN_ID, "2022-07"));
    }
}
