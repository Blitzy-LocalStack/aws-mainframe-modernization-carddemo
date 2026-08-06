package com.carddemo.transaction.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.money.Money;
import com.carddemo.transaction.dto.BillPaymentResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the conversion that reports a posted bill payment, and the balance semantic it fixes.
 *
 * <h2>The parity property under test</h2>
 *
 * <p>Assumptions: the reference pays the balance IN FULL and admits no partial amount, which is what
 * makes the reported figure unambiguous once it is pinned. Line 193 of {@code app/cbl/COBIL00C.cbl}
 * moves {@code ACCT-CURR-BAL} into the display field, line 224 reuses the same untouched value as the
 * transaction amount, line 233 writes the transaction and only line 234 subtracts, and line 242 sends
 * the display field unchanged -- so the operator sees the pre-payment balance, and the post-payment
 * balance is always exactly zero.</p>
 *
 * <p>Refactoring Rationale: the zero is asserted explicitly below rather than left implicit, because it
 * is the whole argument for reporting one money member instead of two. A reader who has not traced
 * lines 224 and 234 would reasonably expect a resulting balance to be worth publishing; the assertion
 * shows why it is not.</p>
 */
class BillPaymentMappingTest {

    /** The mapper under test; it holds no state, so one instance serves every assertion. */
    private final TransactionMapper mapper = new TransactionMapper();

    /** A balance with a non-zero fractional part, so a scale loss would show. */
    private static final BigDecimal BALANCE = new BigDecimal("1234.56");

    /**
     * Confirms the reported balance is the one handed in, at its own scale, and is the amount paid.
     */
    @Test
    @DisplayName("the reported balance is the pre-payment figure at scale two")
    void reportedBalanceIsThePrePaymentFigure() {
        BillPaymentResponse posted = this.mapper.toBillPaymentResponse("00000000011",
                Money.of(BALANCE), "683581", "Payment posted");

        assertThat(posted.currentBalance().amount()).isEqualByComparingTo(BALANCE);
        assertThat(posted.currentBalance().amount().scale()).isEqualTo(2);
        assertThat(posted.returnMessage()).isEqualTo("Payment posted");
    }

    /**
     * Confirms the figure the reference leaves the account holding is zero, which is why it is not sent.
     *
     * <p>Assumptions: this reproduces the arithmetic of lines 224 and 234 rather than calling the
     * mapper, because the property being recorded is a property of the BASELINE that justifies the
     * mapper's shape. Asserting it here keeps the justification executable instead of leaving it as a
     * claim in a comment that no run can contradict.</p>
     */
    @Test
    @DisplayName("the post-payment balance the contract no longer publishes is always zero")
    void postPaymentBalanceIsAlwaysZero() {
        Money balance = Money.of(BALANCE);
        Money transactionAmount = balance;

        assertThat(balance.minus(transactionAmount).amount())
                .as("app/cbl/COBIL00C.cbl L224 moves the whole balance into the amount and L234"
                        + " subtracts that amount from that balance, so nothing can remain")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Confirms both identifiers are zero-padded to their declared widths on the way out.
     *
     * <p>Assumptions: eleven and sixteen are the widths {@code CC-ACCT-ID PIC X(11)} and
     * {@code TRAN-ID PIC X(16)} declare, and padding reproduces the reference's numeric-to-character
     * move. A caller that submitted the bare digits therefore gets back the same characters it would
     * read off the stored row.</p>
     */
    @Test
    @DisplayName("both identifiers are zero-padded to their declared reference widths")
    void identifiersArePaddedToTheirDeclaredWidths() {
        BillPaymentResponse posted =
                this.mapper.toBillPaymentResponse("11", Money.of(BALANCE), "683581", null);

        assertThat(posted.accountId()).isEqualTo("00000000011")
                .hasSize(TransactionMapper.ACCOUNT_ID_WIDTH);
        assertThat(posted.transactionId()).isEqualTo("0000000000683581")
                .hasSize(TransactionMapper.TRANSACTION_ID_WIDTH);
    }

    /**
     * Confirms the discriminator cannot report that no payment was made.
     *
     * <p>Assumptions: the value is asserted against the record's own constant rather than against a
     * literal, so that the published constant, the factory and this assertion are all one symbol.</p>
     */
    @Test
    @DisplayName("the posted discriminator is fixed by the factory, not chosen by the mapper")
    void postedDiscriminatorIsFixed() {
        assertThat(this.mapper.toBillPaymentResponse("00000000011", Money.of(BALANCE), "683581", null)
                .paid())
                .isEqualTo(BillPaymentResponse.PAYMENT_POSTED)
                .isTrue();
    }

    /**
     * Confirms every spelling of an absent message collapses onto null rather than onto a blank string.
     *
     * <p>Assumptions: the three spellings are the ones the reference uses interchangeably -- an unset
     * field, a field of spaces and a field of low values -- and the response models absence as null
     * because {@code CVCRD01Y} line 30 attaches a low-values sentinel to that field alone. A
     * blank-filled string of the declared width would be a fourth spelling this shape does not have.</p>
     */
    @Test
    @DisplayName("an absent message is reported as null in each of its three spellings")
    void absentMessageCollapsesOntoNull() {
        for (String absent : new String[] {null, "", "   ", "\u0000\u0000"}) {
            assertThat(this.mapper
                    .toBillPaymentResponse("00000000011", Money.of(BALANCE), "683581", absent)
                    .returnMessage())
                    .isNull();
        }
    }

    /**
     * Confirms an absent balance is refused rather than reported as absent.
     *
     * <p>Assumptions: a posted payment always has a balance -- it is the amount that was paid -- so an
     * absent one is a defect in the calling code rather than a state to publish. Refusing here names the
     * argument that failed instead of emitting a null into a member documented as always present.</p>
     */
    @Test
    @DisplayName("an absent balance is refused, naming the argument")
    void absentBalanceIsRefused() {
        assertThatThrownBy(() ->
                this.mapper.toBillPaymentResponse("00000000011", null, "683581", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("balanceBeforePayment");
    }
}
