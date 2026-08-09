package com.carddemo.authorization.api;

import com.carddemo.authorization.dto.FraudMarkRequest;
import com.carddemo.authorization.dto.FraudMarkResponse;
import com.carddemo.authorization.service.FraudMarkingService;
import com.carddemo.common.web.CursorToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one write surface of the pending credit-card authorization context: an authorization's fraud state.
 *
 * <p><strong>Purpose.</strong> Bind, validate and delegate
 * {@code PUT /api/v1/authorizations/&#123;key&#125;/fraud}, the only operation
 * {@code src/main/resources/openapi/authorization-api.yaml} publishes that changes state, and the only one
 * carrying a route rule of its own.
 *
 * <p>Refactoring Rationale: the fraud route is its own controller rather than a method on the read
 * controller beside it, even though the reference system reaches the fraud program from INSIDE transaction
 * {@code CPVD} and so treats detail and fraud as one screen's work --
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} reaches it by {@code EXEC CICS LINK} at L248
 * to L252. The deciding factor is the authority rather than the grouping: this is the only route that
 * changes state, so giving it its own type lets the route matrix in {@code config/SecurityConfig.java}
 * name one class-level path prefix instead of singling out a single method inside a mixed controller,
 * where a later edit could add a second method under the same prefix and inherit an authority nobody
 * intended for it. Refactoring Rationale: that reason used to be stated as "the only route requiring
 * {@code carddemo-admin}", which it no longer is -- the rule was restored to the authority the baseline
 * grants. The separation is MORE useful for having been kept: a deployment narrowing the write can do so
 * without narrowing the read, which a mixed controller would not allow. The cost accepted is one more
 * small type than the reference screen count suggests.
 *
 * <p>Assumptions: the method is {@code PUT} because the reference action is not one-way. Its
 * {@code WS-FRD-ACTION PIC X(01)} at {@code cbl/COPAUS2C.cbl} L80 admits the reported character at L81
 * and the removed character at L82, and L137 moves whichever arrived straight into the fraud column, so
 * a one-way verb could not express the removal at all. {@code PUT} also carries the TARGET state, so a
 * retry that arrives after the first attempt succeeded leaves the same state behind -- which a
 * non-idempotent verb over a network could not promise.
 *
 * <p>Assumptions: this class touches no entity, injects no repository and declares no transaction
 * boundary. That matters most here of all the routes in this package: the write spans two tables and the
 * reference system commits them together, so a transaction opened from this layer would fragment a commit
 * that has to stay whole.
 *
 * <p>Refactoring Rationale: this class is deliberately NOT annotated {@code @Validated}, for the reason
 * recorded on {@link PendingAuthController}: the annotation switches parameter validation onto an AOP proxy
 * raising an exception the shared advice declares no handler for, so every refusal would be answered as
 * HTTP 500. The consequence is sharper on this route than on the read routes, because the framework routes
 * a {@code @Valid} request BODY through the same mechanism once any parameter of the method carries a
 * constraint -- which the sealed path selector does -- so the annotation would have turned a fraud action
 * outside its two-character domain into a server fault as well.
 */
@RestController
@RequestMapping(path = FraudController.FRAUD_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class FraudController {

    /**
     * The subresource path this controller is mounted at.
     *
     * <p>Assumptions: it corresponds exactly to the {@code FRAUD_PATH_PATTERN} literal that
     * {@code config/SecurityConfig.java} gates with its own rule, differing only in that the gate uses a
     * single-segment wildcard where this declares the template variable. The two are written literally on
     * both sides for the stated reason that a path gate matching nothing fails open. Refactoring Rationale:
     * this said the gate applies the ADMINISTRATIVE authority, which it no longer does -- the rule was
     * restored to the authority the baseline grants, either business group, and the reason is recorded at
     * {@code SecurityConfig.fraudAccess()}. The correspondence between the two literals is unaffected and
     * is what this paragraph is about.</p>
     */
    public static final String FRAUD_PATH = "/api/v1/authorizations/{key}/fraud";

    /**
     * The write behaviour, which owns the transaction boundary and both writes.
     */
    private final FraudMarkingService marking;

    /**
     * Builds the controller over the fraud-marking service.
     *
     * @param marking the fraud-marking service; must not be {@code null}
     * @throws NullPointerException if {@code marking} is {@code null}
     */
    public FraudController(FraudMarkingService marking) {
        this.marking = Objects.requireNonNull(marking, "marking must not be null");
    }

    /**
     * Sets the fraud state of one pending authorization to the state the body names.
     *
     * <p>Assumptions: the response status is what distinguishes the two write paths -- 201 when the fraud
     * row was created and 200 when an existing one was replaced -- because the reference program words the
     * two successes differently, reporting at {@code cbl/COPAUS2C.cbl} L201 and L232, so a caller must be
     * able to tell them apart. The body is the same shape on both, and the distinction is deliberately NOT
     * a member of it: the response schema carries exactly the two fields the reference communication
     * area's response direction declares, and a third would put the document and the returned record out
     * of agreement.
     *
     * <p>Refactoring Rationale: this paragraph described a body carrying THREE key members, checked against
     * the path selector and refused with 400 on any disagreement. No such body exists: {@code
     * FraudMarkRequest} declares one member, the action, and the sealed selector in the path is the whole
     * address of the row. The three-member shape was withdrawn when the selector was sealed, because a body
     * repeating a key the path already carries invites exactly the disagreement the withdrawn paragraph then
     * had to define an answer for -- and a caller reading this would have sent three members and had them
     * rejected as unknown properties. The correction is stated rather than the paragraph deleted, because a
     * reader who saw it needs to know which shape to send.
     *
     * <p>Assumptions: there is consequently no key agreement to check and no 400 arising from one. The
     * selector is redeemed against the caller's own name inside the service before anything is read or
     * written, so a selector issued to another operator is refused; that refusal is about WHO holds the
     * selector rather than about what a body claimed.
     *
     * @param key the sealed selector taken from the {@code key} property of a list row; must not be blank
     * @param request the fraud state to set, and nothing else -- the sealed selector is the whole address
     *     of the row; must not be {@code null} and is validated against its published domain before this
     *     method runs
     * @param principal the authenticated caller, supplied by the framework; the selector is redeemed
     *     against its name, so a selector issued to another operator is refused rather than honoured
     * @return HTTP 201 with the insert sentence when the fraud row was created, or HTTP 200 with the
     *     update sentence when an existing row was replaced; never {@code null}
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FraudMarkResponse> setFraudState(
            @PathVariable(name = "key")
            @NotBlank
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            String key,
            @Valid @RequestBody FraudMarkRequest request,
            Principal principal) {

        FraudMarkingService.FraudMarkOutcome outcome =
                this.marking.mark(key, request, principal.getName());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.body());
    }
}
