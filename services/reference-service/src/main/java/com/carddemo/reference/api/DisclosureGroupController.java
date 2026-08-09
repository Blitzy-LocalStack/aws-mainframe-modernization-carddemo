package com.carddemo.reference.api;

import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;
import com.carddemo.reference.service.DisclosureGroupService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    /**
     * The declared width the account group is padded to before the key is built.
     *
     * <p>Assumptions: aliased from the entity that owns the column rather than written as a literal, for
     * the reason the four constants below record. This one is the width the padding helper in this class
     * pads TO, so a literal here and a different literal on the entity would produce a padded value the
     * composite key then refuses for being the wrong width -- a disagreement that surfaces as a refusal
     * rather than as a mismatch anyone could see.</p>
     */
    private static final int ACCT_GROUP_ID_WIDTH = DisclosureGroup.ACCT_GROUP_ID_WIDTH;

    /**
     * The shortest account group the contract admits.
     *
     * <p>Refactoring Rationale: the three bounds and three expressions declared here and below are
     * transcribed from the schemas this operation's path parameters reference in
     * {@code openapi/reference-api.yaml} -- {@code AccountGroupId}, {@code TransactionTypeCode} and
     * {@code TransactionCategoryCode}. They are declared because the handler previously bound all three
     * segments as unconstrained text, so a malformed segment reached the identity type in the domain
     * layer, whose refusal is a bare {@code IllegalArgumentException}. The shared advice tests for the
     * caller-refusal subtype and deliberately not for its supertype, so that refusal rendered as a 500 --
     * telling a caller its own malformed path was the service's fault -- while the contract publishes 400
     * for exactly this case.</p>
     */
    private static final int ACCT_GROUP_ID_MIN_WIDTH = 1;

    /**
     * The characters an account group may not contain, as a regular expression.
     *
     * <p>Assumptions: this is the {@code AccountGroupId} schema's own expression, which excludes the C0
     * control range and the delete character and admits everything else up to the declared width. It is
     * transcribed rather than tightened: the seeded groups are alphanumeric, but the contract admits any
     * printable text and narrowing it here would refuse a request the document accepts.</p>
     */
    private static final String ACCT_GROUP_ID_PATTERN = "^[^\\u0000-\\u001F\\u007F]{1,10}$";

    /**
     * The exact width of a transaction type, from the {@code TransactionTypeCode} schema.
     *
     * <p>Refactoring Rationale: the four constants declared here and below are ALIASES for the values the
     * two entities that own those codes publish, rather than copies of the expressions. They were copies
     * first, and the copies were withdrawn once a second and a third boundary needed the same two
     * expressions: three transcriptions of one domain, applied at three different addresses, can drift into
     * disagreeing without anything failing, because each is only ever exercised by requests to its own
     * route. The aliases are kept as named members rather than the references being inlined into the
     * annotations, so that the middle and third components of this key still read as this operation's own
     * contract at the point of use.</p>
     *
     * <p>Assumptions: the two codes in this composite key ARE the transaction type and category codes --
     * the disclosure-group row is keyed by an account group plus exactly those two -- so referencing their
     * owning entities is a statement of that identity and not a convenience.</p>
     */
    private static final int TRAN_TYPE_CD_WIDTH = TransactionType.TYPE_CD_WIDTH;

    /** The closed domain of a transaction type, as the type entity publishes it. */
    private static final String TRAN_TYPE_CD_PATTERN = TransactionType.TYPE_CD_PATTERN;

    /** The exact width of a transaction category, as the category entity publishes it. */
    private static final int TRAN_CAT_CD_WIDTH = TransactionCategory.CAT_CD_WIDTH;

    /** The closed domain of a transaction category, as the category entity publishes it. */
    private static final String TRAN_CAT_CD_PATTERN = TransactionCategory.CAT_CD_PATTERN;

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
     * <p>Assumptions: each segment is constrained to the schema its published path parameter references,
     * so a malformed segment is refused BEFORE the value reaches the domain identity type. The framework
     * raises its own method-validation failure, which the shared advice renders as the 400 this operation
     * publishes with the offending segment named -- where the same value previously produced a 500 from a
     * bare domain refusal the advice does not classify.</p>
     *
     * @param acctGroupId the account group asked for, which the contract admits from one to ten
     *     characters and which is padded here to the width the stored key carries
     * @param tranTypeCd the two-character transaction type, {@code 01} through {@code 99}
     * @param tranCatCd the four-digit transaction category
     * @return the rate, reporting whether the default group supplied it
     */
    @GetMapping(path = ITEM_PATH)
    public DisclosureGroupRateResponse getDisclosureGroupRate(
            @PathVariable(name = PARAM_ACCT_GROUP_ID)
            @Size(min = ACCT_GROUP_ID_MIN_WIDTH, max = ACCT_GROUP_ID_WIDTH)
            @Pattern(regexp = ACCT_GROUP_ID_PATTERN) String acctGroupId,
            @PathVariable(name = PARAM_TRAN_TYPE_CD)
            @Size(min = TRAN_TYPE_CD_WIDTH, max = TRAN_TYPE_CD_WIDTH)
            @Pattern(regexp = TRAN_TYPE_CD_PATTERN) String tranTypeCd,
            @PathVariable(name = PARAM_TRAN_CAT_CD)
            @Size(min = TRAN_CAT_CD_WIDTH, max = TRAN_CAT_CD_WIDTH)
            @Pattern(regexp = TRAN_CAT_CD_PATTERN) String tranCatCd) {

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
