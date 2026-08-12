package com.carddemo.account.service;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.Customer;
import java.util.Objects;

/**
 * The single owner of the revision token that covers an account and its customer together.
 *
 * <p><b>Purpose.</b> The account screen edits two records as one unit -- {@code app/cbl/COACTUPC.cbl}
 * snapshots the whole pre-edit pair into {@code ACUP-OLD-DETAILS} at L669 and compares thirty-odd fields
 * against it in {@code 9700-CHECK-CHANGE-IN-REC} at L4109, refusing the write if EITHER record moved. This
 * class renders that one question as one opaque token, so a caller returns a single precondition and a
 * change to either row refuses it.</p>
 *
 * <p>⚠️ Refactoring Rationale: the derivation used to be a private static method on
 * {@link AccountUpdateService}, and the read path could not reach it. The consequence was structural
 * rather than cosmetic: {@code AccountController} obtained the token for a view response by calling a
 * SEPARATE read-only operation on the write service after the view had already been composed, so the body
 * and the tag beside it were read in two different transactions and, at this datasource's read-committed
 * isolation, from two different snapshots. A concurrent edit committing between them published a body from
 * before it beside a tag naming the state after it -- and a caller echoing that tag on an {@code If-Match}
 * would then be told its precondition was current while holding a body that was not, which is the
 * silent-overwrite outcome the precondition exists to remove. Moving the derivation here lets each service
 * compute the token from the rows it has already read, inside its own single transaction.</p>
 *
 * <p>Alternatives Considered: keeping the derivation private and having the view service call the write
 * service for it. Rejected because that is the arrangement being corrected -- a second call is a second
 * transaction whichever object makes it -- and because it would point a read at a write service for no
 * reason other than where a method happened to live.</p>
 *
 * <p>Alternatives Considered: persisting the token as a column and reading it back. Rejected because it
 * would be a second source of truth for a fact the provider already maintains: the two {@code @Version}
 * columns are what the flush actually compares, so a stored token could disagree with them and the
 * disagreement would be invisible until an update was wrongly accepted or wrongly refused.</p>
 *
 * <p>Alternatives Considered: a record wrapping the two version numbers rather than a rendered string.
 * Rejected because the value travels as an HTTP entity tag, so it has to be one opaque scalar at both
 * ends; a structured form would have to be rendered and parsed anyway, and the parse would then be a
 * second place the format is stated.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This type declares no constructor a caller may use
 * and holds no state, so the type itself accepts no parameter and yields no value; its one member carries
 * its own at-clauses. The inapplicability is stated rather than passed over, because the user-specified
 * Explainability rule forbids a docstring that omits parameters, return values or purpose, and a reader
 * has to be able to tell a declared inapplicability from an oversight.</p>
 */
public final class AccountRevision {

    /**
     * Separates the two version numbers inside one revision token.
     *
     * <p>Assumptions: a character that cannot occur in a decimal version, so the token stays unambiguous
     * however large either version grows.</p>
     */
    private static final String SEPARATOR = "-";

    /**
     * Refuses construction, because this type is a derivation and holds nothing.
     *
     * <p>Assumptions: a private constructor rather than an interface with a static member, because an
     * interface's members are implicitly public and a reader could implement it; nothing here is a
     * contract for a second implementation, so there is nothing to implement.</p>
     *
     * @throws AssertionError always, so a reflective caller is refused as well as a direct one
     */
    private AccountRevision() {
        throw new AssertionError("AccountRevision holds no state and is never constructed");
    }

    /**
     * Renders the revision token a loaded pair of rows currently stands at.
     *
     * <p>Trade-offs: one token covers BOTH rows, rendered as the two versions joined, rather than one
     * token per row. The baseline's before-image spans both records and its comparison fails if either
     * changed, so a single token reproduces that. What is accepted in exchange is that a concurrent edit
     * to either row refuses an edit to the other; that is the baseline's behaviour, and the screen edits
     * the two together in any case.</p>
     *
     * <p>Assumptions: the rows must be the ones the CALLER has already read inside its own transaction.
     * This method reads nothing and opens nothing, which is precisely what makes it safe to call from a
     * read path and from a write path without either acquiring a second snapshot.</p>
     *
     * @param account the loaded account row, whose {@code @Version} column the provider compares at
     *     flush; must not be {@code null}
     * @param customer the loaded customer row, whose {@code @Version} column is compared with it; must
     *     not be {@code null}
     * @return the opaque token covering both rows, never {@code null}
     * @throws NullPointerException if either row is {@code null}
     */
    public static String of(Account account, Customer customer) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(customer, "customer must not be null");
        return account.getVersion() + SEPARATOR + customer.getVersion();
    }
}
