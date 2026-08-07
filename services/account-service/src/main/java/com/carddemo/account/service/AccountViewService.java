package com.carddemo.account.service;

import com.carddemo.account.dto.AccountContextView;
import com.carddemo.account.dto.CardXrefView;
import com.carddemo.account.mapper.AccountContextMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read path of the account bounded context, composing the account master, the customer master and the
 * card cross-reference.
 *
 * <h2>Purpose</h2>
 * <p>This class carries the three reads that other bounded contexts and the account view screen resolve
 * against. The migration plan assigns it that composition at its section 0.5.1.3, where it is described as the
 * account read composing customer and cross-reference data, and names its two reference programs:
 * {@code app/cbl/COACTVWC.cbl} (941 lines, CICS transaction {@code CAVW}), which is the screen that composes
 * all three records for a human, and {@code app/cbl/CBACT01C.cbl} (430 lines), which is the sequential reader
 * of the account master. The cross-reference read is transcribed from {@code app/cbl/CBACT03C.cbl} and the
 * customer read from {@code app/cbl/CBCUS01C.cbl}; both are reached in the baseline only through the composed
 * view, which is why they are methods here rather than services of their own.
 *
 * <h2>Why this class exists rather than the controllers holding these calls</h2>
 * <p>Refactoring Rationale: the three reads were first written directly inside the controllers, each injecting
 * a repository and the mapper. That arrangement worked and compiled, and it violated two prohibitions this
 * module states about itself in the charter at
 * {@code src/main/java/com/carddemo/account/api/package-info.java}: that no mapper is injected into the api
 * package, and that nothing in that package reaches the repository layer directly. It also disagreed with the
 * layer split the migration plan assigns at its section 0.4.1.2, which gives the REST layer binding and
 * validation and gives this layer the rules. The calls were moved here rather than the charter being relaxed,
 * because the charter's prohibition is what makes the boundary checkable by a reviewer, and because a
 * controller that owns a transaction boundary cannot be exercised without one.
 *
 * <p>Trade-offs: the cost is one more indirection for three methods that each perform a single repository call
 * and, in two cases, one projection. That is a real cost and it is accepted for two returns. The transaction
 * boundary moves to the layer that owns the unit of work, which is where the baseline's {@code SYNCPOINT}
 * boundary maps under the plan's transformation rule T5. And the reads become callable from the account view
 * screen path and from the inquiry listener path without either of them going through HTTP.
 *
 * <h2>Absence is an outcome, not a failure</h2>
 * <p>Assumptions: two of the three methods signal absence by throwing and the third returns a boolean, and the
 * asymmetry is deliberate. The two that throw have a value to return when the row exists, so an absent row has
 * no representable answer; the shared advice renders the raised type as a 404 carrying this system's problem
 * document, which is the shape every published contract in this migration declares for a not-found. The third
 * has no value to return in either case -- its whole answer is presence -- so a boolean is the complete result
 * and an exception would be a control-flow device rather than information.
 *
 * <p>Parameters, return values and raised exceptions are documented per method below. This class holds no
 * mutable state, so a single instance serves every request concurrently.
 */
@Service
public class AccountViewService {

    /**
     * The account master rows this context owns.
     */
    private final AccountRepository accounts;

    /**
     * The customer master rows this context owns.
     */
    private final CustomerRepository customers;

    /**
     * The cross-reference rows this context owns.
     */
    private final CardXrefRepository crossReferences;

    /**
     * The projection from a stored row to a published contract.
     */
    private final AccountContextMapper mapper;

    /**
     * Creates the service.
     *
     * <p>Assumptions: every collaborator arrives through the constructor rather than through field injection,
     * so an instance is fully formed once constructed and can be exercised with substitutes under a plain unit
     * test with no container and no database attached. That property is the reason the migration plan assigns
     * constructor injection at its section 0.4.3, replacing the baseline's static {@code CALL} linkage and
     * shared {@code WORKING-STORAGE}.</p>
     *
     * @param accounts the account master repository; must not be {@code null}
     * @param customers the customer master repository; must not be {@code null}
     * @param crossReferences the card cross-reference repository; must not be {@code null}
     * @param mapper the projection to the published contracts; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public AccountViewService(AccountRepository accounts,
            CustomerRepository customers,
            CardXrefRepository crossReferences,
            AccountContextMapper mapper) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.crossReferences = Objects.requireNonNull(crossReferences, "crossReferences must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Resolves a primary account number to the account and customer it belongs to.
     *
     * <p>Assumptions: the transaction is declared read-only, which is a statement of intent the provider
     * enforces rather than a micro-optimisation -- a write attempted inside it fails instead of succeeding
     * quietly. Every method here is a read and none has any business holding a write path.</p>
     *
     * @param cardNumber the sixteen-digit primary account number to resolve; must not be {@code null}
     * @return the account and customer the card belongs to, never {@code null}
     * @throws NoSuchElementException if the card is not cross-referenced, which the shared advice renders as
     *     404 and which a consuming context reads as its card-not-found decision input
     */
    @Transactional(readOnly = true)
    public CardXrefView resolveCardCrossReference(String cardNumber) {
        return this.crossReferences.findByCardNum(cardNumber)
                .map(this.mapper::toCardXrefView)
                // WHY : Assumptions: the raised message names NEITHER the card number nor any part of it. It
                //   reaches the shared advice, which writes it to a log and returns it in a response body,
                //   and the value is a primary account number. The caller knows which card it asked about,
                //   so the message has nothing to add by repeating it into two more places.
                .orElseThrow(() -> new NoSuchElementException(
                        "no cross-reference row exists for the requested card"));
    }

    /**
     * Reads the limits and posted balance of one account.
     *
     * @param accountId the eleven-digit account identifier
     * @return the account's credit limit, cash credit limit and posted balance, never {@code null}
     * @throws NoSuchElementException if the account master holds no such row, which the shared advice renders
     *     as 404 and which a consuming context reads as its account-not-found decision input
     */
    @Transactional(readOnly = true)
    public AccountContextView readAccountContext(long accountId) {
        return this.accounts.findById(accountId)
                .map(this.mapper::toAccountContextView)
                // WHY : Assumptions: the identifier IS named in this message, unlike the card number above.
                //   An eleven-digit internal account key is not cardholder data -- it already appears in the
                //   request path and in the access log of every hop -- so withholding it here would remove a
                //   diagnostic without protecting anything.
                .orElseThrow(() -> new NoSuchElementException(
                        "no account master row exists for account " + accountId));
    }

    /**
     * Reports whether the customer master holds one customer.
     *
     * <p>Assumptions: an existence check is issued rather than a read, and the difference is visible in the
     * generated statement -- an existence check selects no column, while a read materialises eighteen
     * including two encrypted identifiers. Since the answer is a presence either way, reading the row would
     * decrypt nothing and disclose nothing, but it would still carry cardholder data out of the database into
     * this process's heap for no purpose.</p>
     *
     * @param customerId the nine-digit customer identifier
     * @return {@code true} when the customer master holds the row, {@code false} when it does not
     */
    @Transactional(readOnly = true)
    public boolean customerExists(long customerId) {
        return this.customers.existsById(customerId);
    }
}
