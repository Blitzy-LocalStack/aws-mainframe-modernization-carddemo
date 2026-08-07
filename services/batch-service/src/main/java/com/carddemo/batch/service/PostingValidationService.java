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
 * <p>Assumptions: <b>the projected balance is formed from the CYCLE accumulators and not from the
 * account's current balance.</b> {@code app/cbl/CBTRN02C.cbl:403-405} computes it as
 * {@code ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}, and {@code ACCT-CURR-BAL} appears
 * nowhere in that statement. The account record declares all three fields, at
 * {@code app/cpy/CVACT01Y.cpy:7}, {@code :13} and {@code :14}, so substituting the current balance
 * compiles perfectly well and yields over-limit decisions that look entirely reasonable while being
 * wrong on every account whose cycle totals differ from its balance.</p>
 *
 * <p>Assumptions: the projection is computed in exact fixed point through the shared money type.
 * Transformation rule T3 forbids the money path leaving exact fixed point at any hop, and a comparison
 * is a hop: a projection carried in a binary radix cannot represent every two-place decimal exactly, so
 * it can land one representable step either side of the limit, which is precisely the distinction the
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
            // WHY : Assumptions: no projection is passed on this path, because none could have been
            //       computed. The reference forms WS-TEMP-BAL at app/cbl/CBTRN02C.cbl:403-405 from
            //       fields of the account record, inside the account read's NOT INVALID KEY branch,
            //       which a cross-reference failure never reaches -- the guard at :372 stops the
            //       account lookup running at all -- and which an account read that found nothing
            //       does not enter. Supplying a zero here instead would assert that a projection was
            //       computed and came to nothing, which is a different claim from "there was none".
            return PostingValidationResult.resolve(!cardResolved, account.isEmpty(), false, false,
                    null);
        }

        Account read = account.get();

        // WHY : Assumptions: the two operands are the CYCLE accumulators, matching
        //       app/cbl/CBTRN02C.cbl:403-405, which reads ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT
        //       + DALYTRAN-AMT. The account's current balance is a separate field, declared at
        //       app/cpy/CVACT01Y.cpy:7 and used by the reference only when it posts the amount at
        //       :547, and it takes no part in this comparison.
        // WHY : Alternatives Considered: projecting from the current balance, which reads as the more
        //       natural meaning of "the balance this transaction would bring the account to".
        //       Rejected because it is not the quantity the reference compares: on any account whose
        //       cycle credit and debit totals do not happen to sum to its balance the two projections
        //       differ, so the inclusive guard at :407 lands on the other side of the limit and the
        //       transaction is rejected where the reference posts it, or posted where the reference
        //       rejects it. Both outcomes are individually plausible, which is what makes the
        //       substitution hard to notice without the byte-for-byte reject comparison.
        Money projected = Money.of(read.getCurrCycCredit())
                .minus(Money.of(read.getCurrCycDebit()))
                .plus(Money.of(transaction.getAmount()));
        boolean overCreditLimit = projected.exceeds(Money.of(read.getCreditLimit()));

        LocalDate originatingDate = transaction.getOrigTs().toLocalDate();
        boolean afterExpiration = read.getExpirationDate().isBefore(originatingDate);

        // WHY : Assumptions: the two boundary findings are reported side by side and the choice
        //       between them is left to the result type, which resolves them as the reference does.
        //       Selecting one here would require restating a precedence that the reference expresses
        //       only as a missing guard between :413 and :414, and a rule restated in a second place
        //       is a rule that can disagree with the first.
        return PostingValidationResult.resolve(false, false, overCreditLimit, afterExpiration,
                projected.amount());
    }
}
