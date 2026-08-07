package com.carddemo.account.api;

import com.carddemo.account.service.AccountViewService;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the customer presence probe this context owns.
 *
 * <p><b>Purpose.</b> This is the migrated form of the read
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} performs at its paragraph
 * {@code 5300-READ-CUST-RECORD}. That paragraph reads the whole customer record and uses NONE of its fields:
 * the read exists only so the program can tell whether the row is there before it records an authorization
 * against the account. This operation answers that question and nothing more.</p>
 *
 * <p>Assumptions: the response carries NO BODY on either outcome, and the whole answer is the status. That is
 * a deliberate divergence from the reference, which materialises a name, a full postal address, two phone
 * numbers, a date of birth, a credit score and two national identifiers in order to discard all of them. The
 * migrated form crosses the context boundary with none of it, which is strictly better and is documented as a
 * data-minimisation improvement rather than presented as parity -- the OBSERVABLE outcome is identical,
 * because the reference's own use of the record is limited to whether the read succeeded.</p>
 *
 * <p>Assumptions: the operation is declared as a {@code GET} and the consumer calls it with {@code HEAD}. The
 * framework routes a {@code HEAD} to the matching {@code GET} handler and discards the body, so declaring
 * both would be two handlers whose behaviour had to be kept identical. Declaring the {@code GET} means the
 * contract has one operation, and the consumer's choice of method is what makes the exchange bodyless on the
 * wire as well as in the handler.</p>
 *
 * <p>Assumptions: absence is 404 and presence is 204 rather than 200. A 200 announces a body that this
 * operation never has, and a client library reading a 200 with a zero-length body may report a decoding
 * failure rather than a successful call. 204 states the shape exactly.</p>
 *
 * <p>Alternatives Considered: publishing a full customer read here and letting the consumer ignore what it
 * does not need. Rejected because it would move a name, an address and a date of birth across a context
 * boundary on every authorization -- the single highest-volume path in the system -- to be discarded at the
 * other end, and would place them in the response body of a call whose failures get logged.</p>
 */
@RestController
@RequestMapping(CustomerController.BASE_PATH)
public class CustomerController {

    /**
     * The path prefix every operation in this controller sits beneath.
     *
     * <p>Assumptions: exposed as a constant so the load-balancer rule and the gateway route that forward this
     * prefix can be asserted against it rather than compared by eye across two files.</p>
     */
    public static final String BASE_PATH = "/api/v1/customers";

    /**
     * The read path this controller binds requests onto.
     */
    private final AccountViewService reads;

    /**
     * Creates the controller.
     *
     * <p>Assumptions: the single collaborator is the service layer, for the reason recorded on the sibling
     * cross-reference controller: this package's charter forbids a repository here.</p>
     *
     * @param reads the account read path; must not be {@code null}
     * @throws NullPointerException if {@code reads} is {@code null}
     */
    public CustomerController(AccountViewService reads) {
        this.reads = Objects.requireNonNull(reads, "reads must not be null");
    }

    /**
     * Reports whether the customer master holds one customer.
     *
     * <p>Assumptions: the service issues an existence check rather than a read, and the rationale for that
     * choice sits on the service method. The transaction boundary sits there too.</p>
     *
     * @param customerId the nine-digit customer identifier
     * @return 204 with no body when the row exists, 404 with no body when it does not
     */
    @GetMapping("/{customerId}")
    public ResponseEntity<Void> exists(@PathVariable long customerId) {
        // WHY : Assumptions: this handler answers with a ResponseEntity rather than throwing the
        //   not-found exception the sibling controllers throw, and the difference is required rather
        //   than stylistic. The shared advice renders a not-found as a problem DOCUMENT, and this
        //   operation is bodyless by contract -- a consumer calling it with HEAD would receive a
        //   Content-Length announcing a body the method strips, which is the one response shape an
        //   HTTP client is entitled to treat as malformed.
        return this.reads.customerExists(customerId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
