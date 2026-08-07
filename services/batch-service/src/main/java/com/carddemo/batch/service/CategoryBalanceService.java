package com.carddemo.batch.service;

import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Maintains the running per-category balance, transcribed from {@code 2700-UPDATE-TCATBAL} of
 * {@code app/cbl/CBTRN02C.cbl}.
 *
 * <p>Purpose: the reference reads the three-part key at {@code :474} and then takes one of two arms. The
 * create arm {@code 2700-A-CREATE-TCATBAL-REC} at {@code :500} writes a row whose balance is the
 * transaction amount alone; the update arm {@code 2700-B-UPDATE-TCATBAL-REC} at {@code :526} adds the
 * amount to the balance it just read and rewrites. Which arm runs is decided by whether the read entered
 * its {@code INVALID KEY} branch at {@code :475}, and the flag it sets is preset to the update value at
 * {@code :473}.</p>
 *
 * <p>Assumptions: the two arms stay separately observable and are NOT collapsed into a single
 * conflict-resolving statement. The distinction is behaviour the golden masters compare -- a created row
 * holds the amount and an updated row holds a sum -- and a database-side upsert decides it where no
 * caller can see which arm ran, so a test of the arms would have nothing to observe.</p>
 *
 * <p>Assumptions: the addition is exact fixed point through the shared money type, never binary floating
 * point. This value accumulates across every transaction of a category for a whole cycle, so a
 * representation error would compound rather than cancel, and transformation rule T3 forbids the money
 * path leaving exact fixed point at any hop.</p>
 */
@Service
public class CategoryBalanceService {

    /** The rows this service reads and writes, reached through the scoped cross-schema grant. */
    private final TransactionCategoryBalanceRepository balances;

    /**
     * Builds the service over its repository.
     *
     * @param balances the repository over the category-balance table; must not be {@code null}
     * @throws NullPointerException if {@code balances} is {@code null}
     */
    public CategoryBalanceService(TransactionCategoryBalanceRepository balances) {
        this.balances = Objects.requireNonNull(balances, "balances must not be null");
    }

    /**
     * Accumulates one transaction amount onto the running balance of its category.
     *
     * <p>Assumptions: this method does not open a transaction of its own. It is called from inside the
     * posting unit of work, which spans the transaction row, this row and the account row as one commit,
     * and a nested boundary here would let this write commit independently of the two it belongs with.</p>
     *
     * @param id the whole three-part key of the category being accumulated onto; must not be
     *     {@code null}
     * @param amount the transaction amount to add, which may be zero or negative; must not be
     *     {@code null}
     * @return which arm ran and the balance the row now carries, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public Outcome accumulate(TransactionCategoryBalanceId id, Money amount) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        Optional<TransactionCategoryBalance> existing = this.balances.findByIdIs(id);

        // WHY : Assumptions: the arm is chosen from the presence of the row, which is the relational form
        //       of the reference's INVALID KEY branch at :475. The preset flag the reference uses at :473
        //       has no counterpart because an Optional cannot be in an unset third state.
        if (existing.isEmpty()) {
            // WHY : Assumptions: the created row's balance is the amount ALONE and not a sum with a
            //       notional zero. The reference's create arm moves the amount at :503 rather than adding
            //       to a cleared field, and the two differ observably for a scale the amount carries and
            //       a zero would not.
            TransactionCategoryBalance created = new TransactionCategoryBalance(id, amount.amount());
            this.balances.save(created);
            return new Outcome(Arm.CREATED, amount.amount());
        }

        TransactionCategoryBalance managed = existing.get();
        BigDecimal accumulated = Money.of(managed.getBalance()).plus(amount).amount();
        managed.setBalance(accumulated);
        this.balances.save(managed);
        return new Outcome(Arm.UPDATED, accumulated);
    }

    /** Which of the reference's two arms an accumulation took. */
    public enum Arm {

        /** The key was absent, so a row was written carrying the amount alone. */
        CREATED,

        /** The key was present, so the amount was added to the balance that was read. */
        UPDATED
    }

    /**
     * What one accumulation did.
     *
     * <p>Assumptions: the arm is returned rather than logged, because it is the fact a test of the two
     * arms has to observe and a caller deciding a graded outcome may need. Returning it is what keeps the
     * distinction visible outside this class without a second read.</p>
     *
     * @param arm which arm ran, never {@code null}
     * @param balance the balance the row carries after the accumulation, exact at scale two, never
     *     {@code null}
     */
    public record Outcome(Arm arm, BigDecimal balance) {
    }
}
