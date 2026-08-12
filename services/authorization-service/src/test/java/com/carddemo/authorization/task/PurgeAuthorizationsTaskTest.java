package com.carddemo.authorization.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.service.PurgeJob;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Holds the purge task to passing an operator's stated control values through, and to falling back to the
 * defaults the job publishes when they are unstated.
 *
 * <p>Purpose: the reference program takes an expiry threshold, a commit frequency and a display frequency on
 * its control card at {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L98 to L108. This class
 * exists because the task passed the three published defaults unconditionally, so those three values were
 * unreachable from the only runtime entry point the service has -- and with them, the job's own
 * {@code MAX_EXPIRY_DAYS} and {@code MAX_CARD_FREQUENCY} ceilings and its documented refusal of a zero
 * threshold.</p>
 *
 * <p>Assumptions: the purge itself is a mock and what is asserted is the PARAMETER OBJECT it receives.
 * Running a real purge here would exercise the walk, which {@code PurgeJobTest} already owns; what this
 * class decides is only which four values reach it.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
class PurgeAuthorizationsTaskTest {

    /** The business date every case runs for, stated rather than read from a clock. */
    private static final String BUSINESS_DATE = "2022-07-18";

    /**
     * With no control value stated, the three defaults the job publishes are the ones used.
     *
     * <p>Assumptions: the defaults are named through the job's own constants rather than as literals, so a
     * change to either publishes to this assertion instead of silently disagreeing with it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an omitted control value falls back to the default the job publishes")
    void omittedControlValuesFallBackToThePublishedDefaults() {
        PurgeJob purge = mock(PurgeJob.class);
        when(purge.purge(any())).thenReturn(new PurgeJob.PurgeOutcome(0, 0, 0, 0));

        new PurgeAuthorizationsTask(purge).run(Map.of(
                MaintenanceTaskRunner.BUSINESS_DATE_PARAMETER, BUSINESS_DATE));

        ArgumentCaptor<PurgeJob.PurgeParameters> parameters =
                ArgumentCaptor.forClass(PurgeJob.PurgeParameters.class);
        verify(purge).purge(parameters.capture());
        assertThat(parameters.getValue()).isEqualTo(new PurgeJob.PurgeParameters(
                LocalDate.parse(BUSINESS_DATE), PurgeJob.DEFAULT_EXPIRY_DAYS,
                PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY, PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY));
    }

    /**
     * Every stated control value reaches the purge, and each reaches its own component.
     *
     * <p>Assumptions: all three are stated with DIFFERENT values, and none of them equals its default. Equal
     * values would let a transposition of the two frequencies pass, and a value equal to a default would let
     * a fall-through pass, which are the two mistakes this routing can make.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a stated expiry threshold and both frequencies each reach their own component")
    void statedControlValuesReachTheirOwnComponents() {
        PurgeJob purge = mock(PurgeJob.class);
        when(purge.purge(any())).thenReturn(new PurgeJob.PurgeOutcome(0, 0, 0, 0));

        new PurgeAuthorizationsTask(purge).run(Map.of(
                MaintenanceTaskRunner.BUSINESS_DATE_PARAMETER, BUSINESS_DATE,
                MaintenanceTaskRunner.EXPIRY_DAYS_PARAMETER, "30",
                MaintenanceTaskRunner.CHECKPOINT_FREQUENCY_PARAMETER, "250",
                MaintenanceTaskRunner.PROGRESS_LOG_FREQUENCY_PARAMETER, "4"));

        ArgumentCaptor<PurgeJob.PurgeParameters> parameters =
                ArgumentCaptor.forClass(PurgeJob.PurgeParameters.class);
        verify(purge).purge(parameters.capture());
        assertThat(parameters.getValue()).isEqualTo(new PurgeJob.PurgeParameters(
                LocalDate.parse(BUSINESS_DATE), 30, 250, 4));
    }

    /**
     * A stated value above the reference card field's width is refused by the purge's own parameter type.
     *
     * <p>Assumptions: this asserts the routing reaches that refusal rather than re-testing the bound. The
     * ceiling is a property of the card field's width and is stated once, on the parameter type; what was
     * broken was that no entry point could deliver a value to it, so a run could not be refused for one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a control value above its card field's width is refused rather than silently clamped")
    void anOverWideControlValueIsRefused() {
        PurgeJob purge = mock(PurgeJob.class);

        assertThatThrownBy(() -> new PurgeAuthorizationsTask(purge).run(Map.of(
                MaintenanceTaskRunner.BUSINESS_DATE_PARAMETER, BUSINESS_DATE,
                MaintenanceTaskRunner.EXPIRY_DAYS_PARAMETER,
                String.valueOf(PurgeJob.MAX_EXPIRY_DAYS + 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiryDays");
    }
}
