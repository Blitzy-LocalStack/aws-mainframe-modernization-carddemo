package com.carddemo.transaction.api;

import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import com.carddemo.transaction.service.BillPaymentService;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the bill-payment operation migrated from {@code app/cbl/COBIL00C.cbl}.
 *
 * <p>Purpose: this class makes the payment reachable. It is a separate controller from the transaction
 * resource because the reference gives this screen its own transaction identifier and its own map, and
 * because its request shape shares no member with the capture's -- an account identifier and a
 * confirmation against fourteen submitted fields.</p>
 *
 * <p>Assumptions: the operation is a POST on its own collection path rather than a member operation under
 * the account resource, because the account resource belongs to another bounded context and this context
 * owns neither the account row nor its path. The payment it writes is a ledger row; the balance change it
 * asks for is the account context's to apply.</p>
 */
@RestController
@RequestMapping(path = BillPaymentController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class BillPaymentController {

    /** The collection path the payment operation is served at. */
    public static final String BASE_PATH = "/api/v1/billpay";

    /** The payment this controller delegates to. */
    private final BillPaymentService billPaymentService;

    /**
     * Builds the controller over the payment service.
     *
     * @param billPaymentService the payment; must not be {@code null}
     * @throws NullPointerException if {@code billPaymentService} is {@code null}
     */
    public BillPaymentController(BillPaymentService billPaymentService) {
        this.billPaymentService =
                Objects.requireNonNull(billPaymentService, "billPaymentService must not be null");
    }

    /**
     * Pays the account's whole outstanding balance, or answers with the reference's own prompt.
     *
     * @param request the submitted payment, bean-validated before this method is entered; must not be
     *     {@code null}
     * @return the posted acknowledgement when the payment was made, and otherwise the shape carrying the
     *     balance and the reference's prompt or advisory sentence; never {@code null}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public BillPaymentResponse payAccountBalanceInFull(@Valid @RequestBody BillPaymentRequest request) {
        return this.billPaymentService.payBalanceInFull(request);
    }
}
