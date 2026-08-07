package com.carddemo.batch.service;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.dto.PostingValidationResult;
import com.carddemo.common.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Decides which posting conditions one daily transaction fails, transcribed from
 * {@code app/cbl/CBTRN02C.cbl:370-420}.
 *
 * <p>Purpose: three paragraphs carry the reference's validation. {@code 1500-VALIDATE-TRAN} at
 * {@code :370} is the entry point, {@code 1500-A-LOOKUP-XREF} at {@code :380} resolves the card
 * cross-reference, and {@code 1500-B-LOOKUP-ACCT} at {@code :393} resolves the account and applies the two
 * balance and date tests. This class performs those four evaluations; which of their failures is REPORTED
 * is decided by {@link PostingValidationResult}, so that the precedence cannot be inverted here.</p>
 *
 * <p>Assumptions: both boundaries are inclusive in the baseline and stay inclusive. The limit guard at
 * {@code :407} passes when the projected balance is less than or equal to the credit limit, so a balance
 * landing exactly on the limit POSTS and one cent beyond rejects; the date guard at {@code :414} passes
 * when the expiration date is greater than or equal to the originating date, so a transaction dated equal
 * to the expiration date POSTS and one day past rejects. Each condition reported to the result type is the
 * strict NEGATION of its guard, derived from the guard rather than written as a second comparison, so the
 * two cannot drift apart.</p>
 *
 * <p>Assumptions: the projected balance is the current balance PLUS the transaction amount, computed in
 * exact fixed point through the shared money type. Transformation rule T3 forbids the money path leaving
 * exact fixed point at any hop, and a comparison is a hop: a projected balance computed in binary floating
 * point can land one representable step either side of the limit, which is precisely the distinction the
 * inclusive guard turns on.</p>
 */
@Service
public class PostingValidationService {

    /**
     * Evaluates the four conditions and returns the outcome the baseline reports.
     *
     * @param transaction the daily transaction being validated; must not be {@code null}
     * @param crossReference the cross-reference entry the card number resolved to, or an empty optional
     *     when it resolved to nothing; must not be {@code null}
     * @param account the account record the cross-reference's account identifier read, or an empty
     *     optional when the read found nothing; must not be {@code null}
     * @return the outcome, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public PostingValidationResult validate(DailyTransaction transaction,
            Optional<CardXref> crossReference, Optional<Account> account) {

        Objects.requireNonNull(transaction, "transaction must not be null");
        Objects.requireNonNull(crossReference, "crossReference must not be null");
        Objects.requireNonNull(account, "account must not be null");

        boolean cardResolved = crossReference.isPresent();

        // WHY : Assumptions: both boundary findings are reported as false when the account was not read,
        //       because the reference's two guards sit inside the not-invalid-key branch at :400 and are
        //       unreachable without a record to compare against. Computing them from an absent account
        //       would require inventing a balance and a date, and the result type would then be deciding
        //       between a real finding and a fabricated one.
        if (!cardResolved || account.isEmpty()) {
            return PostingValidationResult.of(cardResolved, account.isPresent(), false, false);
        }

        Account read = account.get();
        Money projected = Money.of(read.getCurrBal()).plus(Money.of(transaction.getAmount()));
        boolean overCreditLimit = projected.exceeds(Money.of(read.getCreditLimit()));

        LocalDate originatingDate = transaction.getOrigTs().toLocalDate();
        boolean afterExpiration = read.getExpirationDate().isBefore(originatingDate);

        return PostingValidationResult.of(true, true, overCreditLimit, afterExpiration);
    }
}
