package com.carddemo.account.mapper;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.dto.AccountContextView;
import com.carddemo.account.dto.AccountViewResponse;
import com.carddemo.account.dto.CardXrefView;
import com.carddemo.common.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Projects the rows this context owns onto the account-context contract.
 *
 * <p><b>Purpose.</b> This is the anti-corruption layer for the one contract another bounded context reads.
 * It is the single place at which a stored row becomes a published value, and it exists so that the
 * decisions belonging to the contract rather than to the row -- which fields cross at all, which are
 * withheld, and how an exact amount is represented -- are made once and justified where they happen.</p>
 *
 * <p>Assumptions: every projection here NARROWS. Nothing this class produces carries a field the consumer
 * does not read, and two of the three published operations carry strictly fewer fields than the row they
 * come from. That direction is deliberate: a projection that carried the whole row would be indistinguishable
 * from returning the entity, and the first field a consumer started reading by accident would become part of
 * the contract without anyone deciding it had.</p>
 *
 * <p>Assumptions: this class is a {@code @Component} with no state and no collaborators. It is a bean rather
 * than a holder of static methods so that a controller receives it by constructor injection and can be
 * exercised with a substitute in a test without a container -- which is the same reason every other
 * collaborator in this migration is injected.</p>
 *
 * <p>Alternatives Considered: generating these projections with a mapping framework. Rejected across the
 * migration and visibly so here: the account projection drops nine of twelve columns for a stated reason,
 * the cross-reference projection deliberately does not echo the primary account number it was keyed on, and
 * both convert an exact decimal into a type that serialises as text. Each is a judgement that needs a
 * sentence beside it, and a generated mapper has nowhere to put one.</p>
 */
@Component
public class AccountContextMapper {

    /**
     * Projects a cross-reference row onto the two identifiers the contract publishes.
     *
     * <p>Assumptions: the card number the row holds is NOT carried into the response. The caller supplied it,
     * so echoing it adds no information, and it is a primary account number -- so the echo would create a
     * second place the value exists and a second document that must not be logged.</p>
     *
     * @param row the stored cross-reference row; must not be {@code null}
     * @return the published projection, never {@code null}
     * @throws NullPointerException if {@code row} is {@code null}, because an absent row is a not-found
     *     answer that the caller decides on, not a value to project
     */
    public CardXrefView toCardXrefView(CardXref row) {
        Objects.requireNonNull(row, "row must not be null");
        return new CardXrefView(row.getAccountId(), row.getCustomerId());
    }

    /**
     * Projects an account master row onto the three amounts the contract publishes.
     *
     * <p>Assumptions: the three amounts are wrapped in the shared money type rather than passed through as
     * plain decimals. The wrapper is what makes them serialise as JSON strings, and it additionally
     * re-establishes the scale of two that the contract declares -- so a column that somehow held a
     * different scale is normalised here rather than published as an amount whose text form differs from
     * every other amount in the system.</p>
     *
     * <p>Assumptions: nine of the row's twelve columns are dropped, and the reason is recorded on
     * {@link AccountContextView} rather than repeated here: the consumer reads three fields, the account's
     * active status is deliberately withheld because acting on it would diverge from the reference, and no
     * cardholder field has a reader on the other side.</p>
     *
     * @param row the stored account master row; must not be {@code null}
     * @return the published projection, never {@code null}
     * @throws NullPointerException if {@code row} is {@code null}
     */
    public AccountContextView toAccountContextView(Account row) {
        Objects.requireNonNull(row, "row must not be null");
        return new AccountContextView(
                Money.of(row.getCreditLimit()),
                Money.of(row.getCashCreditLimit()),
                Money.of(row.getCurrentBalance()));
    }

    /**
     * Projects an account row onto the HUMAN view's ten account fields.
     *
     * <p>Purpose: this is the account half of the account-view screen, whose moves are the block at
     * {@code app/cbl/COACTVWC.cbl} that fills the account region of {@code app/cpy-bms/COACTVW.CPY}. It
     * is a different shape from {@link #toAccountContextView(Account)}, which serves a neighbouring
     * bounded context and carries three amounts only, and the two are deliberately separate methods
     * rather than one wider shape: a machine caller that needs three amounts must not be handed an
     * account's dates and status, and a screen that needs ten fields cannot be served by three.</p>
     *
     * <p>Assumptions: the five amounts are carried as {@link Money} and never as a primitive or a
     * {@code double}, so the exact fixed-point representation survives to the boundary and the shared
     * serialiser renders each as a string. The three dates are rendered as their ISO text, which is what
     * the stored columns already hold in order -- the baseline stores them as {@code PIC X(10)} in
     * year-month-day order, so the ISO rendering is the stored form rather than a reformatting of it.</p>
     *
     * <p>Assumptions: a null date renders as {@code null} rather than as an empty string or a zero date.
     * The reissue date is the one of the three that a row can legitimately lack, and collapsing absent to
     * blank would make an unissued account indistinguishable from one whose date failed to load.</p>
     *
     * @param row the account row to project; must not be {@code null}
     * @return the human view's account detail, never {@code null}
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws ArithmeticException if a stored amount cannot be held at the scale the contract publishes
     */
    public AccountViewResponse.AccountDetail toAccountDetail(Account row) {
        Objects.requireNonNull(row, "row must not be null");
        return new AccountViewResponse.AccountDetail(
                row.getActiveStatus(),
                isoDate(row.getOpenDate()),
                Money.of(row.getCreditLimit()),
                isoDate(row.getExpirationDate()),
                Money.of(row.getCashCreditLimit()),
                isoDate(row.getReissueDate()),
                Money.of(row.getCurrentBalance()),
                Money.of(row.getCurrentCycleCredit()),
                row.getGroupId(),
                Money.of(row.getCurrentCycleDebit()));
    }

    /**
     * Renders a stored date as its ISO text, preserving the absent state.
     *
     * <p>Assumptions: {@code LocalDate.toString()} emits exactly the ten-character year-month-day form the
     * baseline columns hold, so no formatter is constructed. Introducing one would add a second place the
     * pattern is written and a chance for the two to disagree.</p>
     *
     * @param value the stored date, which may be {@code null}
     * @return the ISO text, or {@code null} when the column holds nothing
     */
    private static String isoDate(LocalDate value) {
        return value == null ? null : value.toString();
    }
}
