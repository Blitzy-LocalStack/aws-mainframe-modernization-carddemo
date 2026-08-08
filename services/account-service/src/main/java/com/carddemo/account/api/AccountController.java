package com.carddemo.account.api;

import com.carddemo.account.dto.AccountContextView;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.dto.AccountUpdateResponse;
import com.carddemo.account.dto.AccountViewResponse;
import com.carddemo.account.dto.CardXrefResponse;
import com.carddemo.account.service.AccountUpdateService;
import com.carddemo.account.service.AccountViewService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the account operations this context owns, across both of its surfaces.
 *
 * <p><b>Purpose.</b> Four operations sit here. One is the machine read the
 * pending-authorization context resolves an authorization against, migrated from the read
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} performs at its paragraph
 * {@code 5200-READ-ACCT-RECORD}. Three are end-user operations: the account-view screen
 * {@code app/cbl/COACTVWC.cbl}, the account-update screen {@code app/cbl/COACTUPC.cbl}, and the
 * by-account cross-reference browse the baseline reaches through the {@code CXACAIX} alternate index.
 * In the reference system all of them reach {@code ACCTDAT} directly; here the account master belongs
 * to this context and each read or write becomes an operation on its published contract.</p>
 *
 * <p>Assumptions: the machine read and the end-user edit share one address and differ only in method,
 * because they act on one record. They are separated by filter CHAIN rather than by path prefix, and
 * the reasoning is recorded on {@code SecurityConfig.ACCOUNT_PATH_PATTERN}: two prefixes would mean two
 * published contracts and two handlers for one query. Every operation below is declared in
 * {@code src/main/resources/openapi/account-api.yaml}, and the contract test compares the two sets in
 * both directions so neither can move alone.</p>
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

    /** Applies an edit under the caller's concurrency precondition. */
    private final AccountUpdateService writes;

    /**
     * Creates the controller.
     *
     * <p>Assumptions: the single collaborator is the service layer, for the reason recorded on the sibling
     * cross-reference controller: this package's charter forbids a repository or the mapper here.</p>
     *
     * @param reads the account read path; must not be {@code null}
     * @param writes the write path that applies an edit under the caller's precondition; must not be
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public AccountController(AccountViewService reads, AccountUpdateService writes) {
        this.reads = Objects.requireNonNull(reads, "reads must not be null");
        this.writes = Objects.requireNonNull(writes, "writes must not be null");
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

    /**
     * Serves the HUMAN account view, and publishes the revision an update must return.
     *
     * <p>Purpose: this is the migrated route for {@code app/cbl/COACTVWC.cbl}. It is a SEPARATE path from
     * the machine read above, and deliberately so: that one serves a neighbouring bounded context and
     * carries three amounts, this one serves a screen and carries the account's ten fields beside its
     * customer's eighteen. One path returning whichever shape a caller preferred would make the response
     * type depend on a header, and neither caller could then be given a stable contract.</p>
     *
     * <p>Assumptions: the revision is published as an {@code ETag} and NOT as a body member. The update
     * response record's own recorded rationale rejects a body version component on the grounds that an
     * optimistic-lock version is transport metadata, and a value with two homes is a value whose two
     * homes can disagree.</p>
     *
     * <p>Trade-offs: the entity tag is WEAK, prefixed {@code W/}. A strong tag asserts octet equality of
     * the representation, which this value cannot promise -- two responses at the same revision are
     * semantically identical but need not be byte-identical, since the masked identifiers and the
     * message channels are assembled per response. A weak tag asserts semantic equivalence, which is
     * exactly what a revision means here, and is what {@code If-Match} on the update compares.</p>
     *
     * @param accountId the account to read
     * @return the account view with its revision in the {@code ETag} header, never {@code null}
     * @throws java.util.NoSuchElementException if the account, its cross-reference or its customer is
     *     absent, which the shared advice renders as HTTP 404
     */
    @GetMapping(path = "/{accountId}/view", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountViewResponse> readView(@PathVariable long accountId) {
        AccountViewResponse view = this.reads.readAccountView(accountId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, weakTag(this.writes.currentRevision(accountId)))
                .body(view);
    }

    /**
     * Applies an edited account and customer, refusing a submission whose precondition is stale.
     *
     * <p>Purpose: this is the migrated route for {@code app/cbl/COACTUPC.cbl}. The precondition is
     * REQUIRED rather than optional, which is what makes the concurrency check unavoidable: an optional
     * header would let a caller opt out simply by omitting it, and opting out means the silent-overwrite
     * behaviour the check exists to remove. A request without the header is refused by the framework
     * before this method is entered.</p>
     *
     * <p>Assumptions: the successful response carries the NEW revision in its own {@code ETag}, so a
     * caller performing consecutive edits does not have to re-read the account between them. Without it
     * every second edit in a sequence would fail its own precondition.</p>
     *
     * <p>Assumptions: the tag comparison tolerates the {@code W/} prefix and quoting, because an
     * intermediary is permitted to reformat an entity tag and a caller that echoes what it received must
     * not be refused for the formatting. The comparison is on the value inside.</p>
     *
     * @param accountId the account to update
     * @param ifMatch the revision the caller was given, from the {@code If-Match} header; required
     * @param request the submitted edit; must not be {@code null}
     * @return the committed state with the new revision in the {@code ETag} header, never {@code null}
     * @throws java.util.NoSuchElementException if the account or its customer is absent, which the shared
     *     advice renders as HTTP 404
     * @throws com.carddemo.common.error.RecordConflictException if the precondition does not name the
     *     stored state, which the shared advice renders as HTTP 409 carrying the reference's own
     *     changed-record sentence
     */
    @PutMapping(path = "/{accountId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountUpdateResponse> update(@PathVariable long accountId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody AccountUpdateRequest request) {
        AccountUpdateResponse applied =
                this.writes.update(accountId, request, bareTag(ifMatch));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, weakTag(this.writes.currentRevision(accountId)))
                .body(applied);
    }

    /**
     * Lists an account's card cross-reference rows through the migrated by-account index.
     *
     * <p>Purpose: this is the {@code CXACAIX} access path, which the baseline surfaces to the online
     * region as an alternate index over the cross-reference file and reads by account. Until this route
     * existed the path had no production consumer: the secondary index, the ordered repository query and
     * the response projection all existed and nothing joined them.</p>
     *
     * <p>Assumptions: the route hangs off the ACCOUNT rather than living under a cross-reference subtree
     * of its own, because the by-account read is a property of an account and because the standalone
     * cross-reference subtree is denied to end users by the filter chain -- it carries the by-card lookup,
     * which takes a whole primary account number and is reachable only from inside the network.</p>
     *
     * <p>Assumptions: an empty list is returned for an account with no cards rather than a not-found
     * outcome, matching an alternate-index browse that ends immediately.</p>
     *
     * @param accountId the account whose cross-reference rows are required
     * @return the rows in ascending card-number order, empty when the account has none, never
     *     {@code null}
     */
    @GetMapping(path = "/{accountId}/card-cross-references",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<CardXrefResponse> listCardCrossReferences(@PathVariable long accountId) {
        return this.reads.listCardCrossReferences(accountId);
    }

    /**
     * Renders a revision as a weak entity tag.
     *
     * @param revision the revision token; must not be {@code null}
     * @return the quoted weak tag, never {@code null}
     */
    private static String weakTag(String revision) {
        return "W/\"" + revision + "\"";
    }

    /**
     * Extracts the revision value from an entity tag a caller supplied.
     *
     * <p>Assumptions: the weak prefix and the surrounding quotes are both stripped, and a value carrying
     * neither is accepted unchanged. An intermediary may reformat an entity tag, so refusing a caller
     * that echoed a reformatted value would fail the request for a difference the caller did not make.
     * </p>
     *
     * @param tag the header value as supplied; must not be {@code null}
     * @return the revision inside, never {@code null}
     */
    private static String bareTag(String tag) {
        String value = tag.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
