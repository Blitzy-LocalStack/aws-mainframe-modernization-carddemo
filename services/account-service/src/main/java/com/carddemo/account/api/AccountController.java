package com.carddemo.account.api;

import com.carddemo.account.dto.AccountContextView;
import com.carddemo.account.service.AccountViewService;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the account read this context owns.
 *
 * <p><b>Purpose.</b> This is the migrated form of the read
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} performs at its paragraph
 * {@code 5200-READ-ACCT-RECORD}, and of the keyed read {@code app/cbl/COACTVWC.cbl} performs to render the
 * account-view screen. In the reference system both reach {@code ACCTDAT} directly; here the account master
 * belongs to this context and the read becomes a call on its published contract.</p>
 *
 * <p>Assumptions: the key is an eleven-digit internal account identifier, so it travels in the PATH -- unlike
 * the cross-reference lookup, whose key is a primary account number and therefore may not appear in a path at
 * all. The asymmetry between the two operations is not inconsistency; it follows from what each key is.</p>
 *
 * <p>Assumptions: the published projection carries three amounts and not the account record. The reason it is
 * narrow, and specifically why the account's active status is withheld rather than carried, is recorded on
 * {@link AccountContextView} -- acting on that status would decline requests the reference approves, and
 * carrying it unread would be a field the contract gained without anyone deciding it had.</p>
 *
 * <p>Assumptions: an absent row is answered with 404 and never with a 200 carrying nulls. The consumer treats
 * 404 as a decision input, matching the reference's account-not-found reject reason, and treats a 200 whose
 * body is missing any of the three amounts as a dependency failure that rolls its transaction back. The two
 * therefore have opposite meanings on the other side, and a 200 with nulls would deliver the wrong one.</p>
 */
@RestController
@RequestMapping(AccountController.BASE_PATH)
public class AccountController {

    /**
     * The path prefix every operation in this controller sits beneath.
     *
     * <p>Assumptions: exposed as a constant so the load-balancer rule and the gateway route that forward this
     * prefix can be asserted against it rather than compared by eye across two files.</p>
     */
    public static final String BASE_PATH = "/api/v1/accounts";

    /**
     * The read path this controller binds requests onto.
     */
    private final AccountViewService reads;

    /**
     * Creates the controller.
     *
     * <p>Assumptions: the single collaborator is the service layer, for the reason recorded on the sibling
     * cross-reference controller: this package's charter forbids a repository or the mapper here.</p>
     *
     * @param reads the account read path; must not be {@code null}
     * @throws NullPointerException if {@code reads} is {@code null}
     */
    public AccountController(AccountViewService reads) {
        this.reads = Objects.requireNonNull(reads, "reads must not be null");
    }

    /**
     * Reads the limits and balance of one account.
     *
     * <p>Assumptions: the transaction boundary sits on the service method this handler calls, so the read-only
     * declaration governs the unit of work rather than the request binding.</p>
     *
     * @param accountId the eleven-digit account identifier
     * @return the account's credit limit, cash credit limit and posted balance
     * @throws NoSuchElementException if the account master holds no such row, which the shared advice renders
     *     as 404 -- and which the consumer reads as its account-not-found decision input
     */
    @GetMapping(path = "/{accountId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AccountContextView read(@PathVariable long accountId) {
        return this.reads.readAccountContext(accountId);
    }
}
