package com.carddemo.reference.api;

import com.carddemo.reference.dto.DisclosureGroupRateResponse;
import com.carddemo.reference.service.DisclosureGroupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The disclosure-rate lookup, the one operation the contract publishes over that table.
 *
 * <p>Purpose: binds the three-part key and delegates to {@code DisclosureGroupService}, which resolves
 * the rate and substitutes the default group on a miss.</p>
 *
 * <p>Assumptions: the whole key travels as path segments and there is no browse. The contract exposes
 * this table as a read of one key and declares no list operation over it, because the reader of this data
 * resolves a single rate per balance row rather than enumerating rates.</p>
 */
@RestController
@RequestMapping(DisclosureGroupController.BASE_PATH)
public class DisclosureGroupController {

    /** The collection path the contract declares. */
    public static final String BASE_PATH = "/api/v1/reference/disclosure-groups";

    /** The item path, carrying all three key components in the order the contract declares. */
    public static final String ITEM_PATH = "/{acctGroupId}/{tranTypeCd}/{tranCatCd}";

    /** The path variable naming the account group. */
    public static final String PARAM_ACCT_GROUP_ID = "acctGroupId";

    /** The path variable naming the transaction type. */
    public static final String PARAM_TRAN_TYPE_CD = "tranTypeCd";

    /** The path variable naming the transaction category. */
    public static final String PARAM_TRAN_CAT_CD = "tranCatCd";

    /** The declared width the account group is padded to before the key is built. */
    private static final int ACCT_GROUP_ID_WIDTH = 10;

    /** The rules this controller delegates to. */
    private final DisclosureGroupService service;

    /**
     * Builds the controller over the rate resolver.
     *
     * @param service the rate rules; must not be {@code null}
     */
    public DisclosureGroupController(DisclosureGroupService service) {
        this.service = service;
    }

    /**
     * Answers the rate that applies to one account group, type and category.
     *
     * @param acctGroupId the account group asked for, which the contract admits from one to ten
     *     characters and which is padded here to the width the stored key carries
     * @param tranTypeCd the two-character transaction type
     * @param tranCatCd the four-digit transaction category
     * @return the rate, reporting whether the default group supplied it
     */
    @GetMapping(path = ITEM_PATH)
    public DisclosureGroupRateResponse getDisclosureGroupRate(
            @PathVariable(name = PARAM_ACCT_GROUP_ID) String acctGroupId,
            @PathVariable(name = PARAM_TRAN_TYPE_CD) String tranTypeCd,
            @PathVariable(name = PARAM_TRAN_CAT_CD) String tranCatCd) {

        return this.service.resolveRate(padToStoredWidth(acctGroupId), tranTypeCd, tranCatCd);
    }

    /**
     * Right-pads an account group with blanks to the width the stored key carries.
     *
     * <p>Assumptions: padding happens HERE, at the edge, and not in the service or the identity type. The
     * contract admits a group from one to ten characters because a caller naturally writes the literal
     * without its padding, while the stored key is fixed at ten because the baseline moves a short
     * literal into a ten-byte alphanumeric field and the platform space-fills it. Padding at the edge is
     * what lets both facts stand: a caller sends what it reads, and the key built is the key the seed
     * contains.</p>
     *
     * <p>Assumptions: this pads on the RIGHT only and never trims. Trimming would search for a key no row
     * carries, and because a miss falls back to the default group rather than failing, the symptom would
     * be silent accrual at the wrong rate instead of a refusal.</p>
     *
     * @param acctGroupId the group as the caller sent it
     * @return the group padded to its stored width, or the value unchanged when already at or over it
     */
    private static String padToStoredWidth(String acctGroupId) {
        if (acctGroupId == null || acctGroupId.length() >= ACCT_GROUP_ID_WIDTH) {
            return acctGroupId;
        }
        return acctGroupId + " ".repeat(ACCT_GROUP_ID_WIDTH - acctGroupId.length());
    }
}
