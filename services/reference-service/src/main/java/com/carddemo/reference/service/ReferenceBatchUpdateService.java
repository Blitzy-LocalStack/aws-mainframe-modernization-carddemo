package com.carddemo.reference.service;

import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.MaintenanceActionBatchRequest;
import com.carddemo.reference.dto.MaintenanceActionBatchResponse;
import com.carddemo.reference.dto.MaintenanceActionOutcomeResponse;
import com.carddemo.reference.dto.MaintenanceActionRequest;
import com.carddemo.reference.mapper.TransactionTypeMapper;
import com.carddemo.reference.repository.TransactionTypeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reference-data maintenance batch, transcribed from the baseline batch driver.
 *
 * <p>Purpose: applies an ordered run of insert, update and delete actions against the transaction-type
 * table, reporting one outcome per action and an aggregate condition code. This is the third of the
 * three write behaviours the baseline distinguishes, and it is the only one that tolerates a failure and
 * carries on.</p>
 *
 * <p>Assumptions: a failed action is a SOFT reject and the run continues. The baseline driver routes
 * every failure to a paragraph whose name suggests an abend but which only displays the message and sets
 * return code 4, leaving the sequential read loop free to take the next record. Abandoning the run at the
 * first reject would report a batch as failed that the baseline completes, and would discard the outcomes
 * of the actions that did apply.</p>
 *
 * <p>Assumptions: each action commits in its OWN transaction rather than the run committing as one. That
 * is what makes the soft reject observable -- a run that rolled back on a late failure would have applied
 * nothing, so the per-action outcome list would describe work that no longer existed. The baseline has
 * the same shape: it writes record by record and its condition code is a summary rather than a gate.</p>
 *
 * <p>Assumptions: the order of the submitted array is honoured, because two actions in one run can
 * address the same row -- an insert followed by an update of the same type is a sequence the baseline
 * input file can contain and applies in the order read.</p>
 */
@Service
public class ReferenceBatchUpdateService {

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

    /** The insert action. */
    public static final String ACTION_INSERT = "INSERT";

    /** The update action. */
    public static final String ACTION_UPDATE = "UPDATE";

    /** The delete action. */
    public static final String ACTION_DELETE = "DELETE";

    /** The message reported when an action applied. */
    public static final String MESSAGE_APPLIED = "Record applied...";

    /** The message reported when the target row did not exist. */
    public static final String MESSAGE_NO_ROWS = "Record NOT found...";

    /** The message reported when an insert names a code that already exists. */
    public static final String MESSAGE_ALREADY_EXISTS = "Record already exists...";

    /** The message reported when an insert or update carries no description. */
    public static final String MESSAGE_DESCRIPTION_REQUIRED = "Description is required...";

    /** Access to the transaction-type table. */
    private final TransactionTypeRepository types;

    /**
     * Builds the service over the repository it writes.
     *
     * @param types access to the transaction-type table; must not be {@code null}
     */
    public ReferenceBatchUpdateService(TransactionTypeRepository types) {
        this.types = types;
    }

    /**
     * Applies every action in order, reporting one outcome each and the aggregate condition code.
     *
     * <p>Assumptions: this member carries no transaction of its own. Each action is applied by the
     * member below in a fresh transaction, so a reject rolls back that action alone; a transaction here
     * would enclose them all and undo the applied ones alongside the failed one.</p>
     *
     * @param request the validated batch body; must not be {@code null}
     * @return one outcome per action in submission order, with the worst condition code seen
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public MaintenanceActionBatchResponse apply(MaintenanceActionBatchRequest request) {
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
     * Applies one action in its own transaction, converting a refusal into an outcome.
     *
     * <p>Assumptions: a refusal is returned as a value rather than thrown, which is what keeps the run
     * going. The transaction is a new one per action, so the rollback of a refused action cannot reach an
     * action that already applied.</p>
     *
     * @param position the one-based index of the action in the submitted array
     * @param action the action to apply; must not be {@code null}
     * @return the outcome of that action, never {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MaintenanceActionOutcomeResponse applyOne(int position, MaintenanceActionRequest action) {
        Optional<TransactionType> stored = this.types.findByTypeCd(action.typeCd());

        if (ACTION_DELETE.equals(action.action())) {
            if (stored.isEmpty()) {
                return outcome(position, action, OUTCOME_NO_ROWS_FOUND, false, MESSAGE_NO_ROWS);
            }
            this.types.delete(stored.get());
            return outcome(position, action, OUTCOME_APPLIED, true, MESSAGE_APPLIED);
        }

        // WHY : Assumptions: an insert and an update both need a description and the shape cannot demand
        //       one, because a delete in the same array needs none. The refusal is therefore made here
        //       and reported as this action's own outcome, which is exactly how the baseline handles a
        //       record it cannot apply -- it reports and reads the next one.
        if (action.description() == null || action.description().isBlank()) {
            return outcome(position, action, OUTCOME_FAILED, false, MESSAGE_DESCRIPTION_REQUIRED);
        }

        if (ACTION_INSERT.equals(action.action())) {
            if (stored.isPresent()) {
                return outcome(position, action, OUTCOME_FAILED, false, MESSAGE_ALREADY_EXISTS);
            }
            this.types.save(new TransactionType(
                    action.typeCd(), TransactionTypeMapper.trimForStorage(action.description())));
            return outcome(position, action, OUTCOME_APPLIED, true, MESSAGE_APPLIED);
        }

        if (stored.isEmpty()) {
            return outcome(position, action, OUTCOME_NO_ROWS_FOUND, false, MESSAGE_NO_ROWS);
        }
        TransactionType target = stored.get();
        target.setDescription(TransactionTypeMapper.trimForStorage(action.description()));
        this.types.save(target);
        return outcome(position, action, OUTCOME_APPLIED, true, MESSAGE_APPLIED);
    }

    /**
     * Builds one outcome row.
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
}
