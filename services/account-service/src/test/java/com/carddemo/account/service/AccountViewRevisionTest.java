package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.domain.Customer;
import com.carddemo.account.mapper.AccountContextMapper;
import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.mapper.CardXrefMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.AccountScreenRow;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.web.CursorToken;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * Pins that the account view and the revision beside it come from ONE read of the same two rows.
 *
 * <p><b>Purpose.</b> The account screen's optimistic-concurrency contract has two halves: the read
 * publishes a token and the write compares it. The reference performs the comparison itself, snapshotting
 * the whole pre-edit pair into {@code ACUP-OLD-DETAILS} at L669 of {@code app/cbl/COACTUPC.cbl} and
 * refusing the write at L521 with L522 if either record moved. The target reduces that snapshot to two
 * {@code @Version} columns rendered as one token, and this class asserts the read half: the token the view
 * publishes is derived from the very rows the view was mapped from.
 *
 * <p>⚠️ Refactoring Rationale: this class exists because the read path did not publish a token at all.
 * {@code AccountController} composed the body from {@code AccountViewService.readAccountView} and then
 * obtained the entity tag from a SECOND, read-only call to {@code AccountUpdateService.currentRevision},
 * which ran in a transaction of its own. At this datasource's read-committed isolation those are two
 * snapshots, so a concurrent edit committing between them published a body from before it beside a tag
 * naming the state after it, and a caller echoing that tag on an {@code If-Match} was told its precondition
 * was current while holding a body that was not -- the silent overwrite the precondition exists to prevent.
 * No case anywhere could have detected that, because the two values came from two collaborators that were
 * stubbed independently.
 *
 * <p>Assumptions: the repository is substituted and the composition row is supplied directly, because every
 * property here is about which values the service derives the token from rather than about what a database
 * returns. The single-statement provenance is asserted as a CALL COUNT on the composing query, which is the
 * only form in which a unit test can state it.
 *
 * <p>Assumptions: the two version counters are assigned reflectively. They are read-only to application
 * code on purpose -- both entities expose a getter and no setter, because the counter belongs to the
 * persistence provider -- so a test that needs two rows at distinguishable versions has no other route. The
 * alternative, running these cases against a real database so the provider assigns the counters, is the
 * container-backed repository test's job and would not make the derivation any more visible.
 *
 * <p>Parameters, return values, exceptions or errors: this class declares no constructor a caller may use
 * and yields no value; each member carries its own at-clauses. The inapplicability is stated rather than
 * passed over, because a reader must be able to tell a declared inapplicability from an oversight.
 */
@DisplayName("Account view revision: one read, one snapshot, one token")
class AccountViewRevisionTest {

    /** The account the composition is read for, an obviously synthetic eleven-digit key. */
    private static final long ACCOUNT_ID = 11L;

    /** The customer the cross-reference row names. */
    private static final long CUSTOMER_ID = 456L;

    /** The card the cross-reference row is keyed by, sixteen digits as the layout declares. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The substituted cross-reference repository, which owns the composing query. */
    private final CardXrefRepository crossReferences = mock(CardXrefRepository.class);

    /** The service under test, wired as the container wires it. */
    private AccountViewService reads;

    /** The stored account row the composition yields. */
    private Account account;

    /** The stored customer row the composition yields. */
    private Customer customer;

    /**
     * Wires the service over the substituted repository and one composed row.
     *
     * <p>Assumptions: the REAL account and customer mappers are supplied rather than substitutes, because
     * a substitute would answer {@code null} for the view and the case asserting that the view and the
     * token arrive together could then pass with no view at all.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    @BeforeEach
    void wire() {
        this.account = new Account(ACCOUNT_ID, "Y", new BigDecimal("-193.00"),
                new BigDecimal("1500.00"), new BigDecimal("0.00"),
                LocalDate.of(2020, 1, 15), LocalDate.of(2027, 1, 31), LocalDate.of(2024, 1, 31),
                new BigDecimal("250.75"), new BigDecimal("1000.00"), "10001", "DEFAULT");
        this.customer = new Customer(CUSTOMER_ID, "ADA", "M", "LOVELACE",
                "1 SYNTHETIC WAY", "SUITE 100", "TESTVILLE", "NY", "USA", "10001",
                "(212)5550100  ", null, "ssn".getBytes(StandardCharsets.UTF_8),
                "gid".getBytes(StandardCharsets.UTF_8), LocalDate.of(1980, 4, 2),
                "0000000001", "Y", (short) 742);

        this.reads = new AccountViewService(mock(AccountRepository.class),
                mock(CustomerRepository.class), this.crossReferences, mock(AccountContextMapper.class),
                new AccountMapper(),
                // WHY : Assumptions: the protection boundary is a recognisable stand-in rather than a
                //       substitute, because the view maps two protected identifiers and a mock would
                //       answer null for both -- so a case asserting the view arrived populated could pass
                //       against a view with two absent members. The bytes name what they protect.
                new CustomerMapper((clearText, field) ->
                        (field + ':' + clearText).getBytes(StandardCharsets.UTF_8)),
                mock(CardXrefMapper.class),
                new CursorToken("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                        Duration.ofMinutes(5)));
    }

    /**
     * The view arrives with a revision derived from the same two rows, from one composing read.
     *
     * <p>Assumptions: the expected token is built through {@link AccountRevision} rather than written out
     * as {@code "3-7"}, because the format has exactly one owner and a literal here would become a second
     * statement of it. What the case asserts is the PROVENANCE -- that the token names these two rows'
     * counters -- and it would fail if the service derived it from anything else.</p>
     *
     * <p>Assumptions: the composing query is verified to run ONCE. A second call would be a second
     * snapshot, which is the whole defect being closed, and the count is the only form in which a unit
     * test can assert it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the view and its revision are derived from one read of the same two rows")
    void theViewAndItsRevisionComeFromOneRead() {
        versionOf(this.account, 3L);
        versionOf(this.customer, 7L);
        stubComposition();

        AccountViewService.RevisionedAccountView answer = this.reads.readAccountView(ACCOUNT_ID);

        assertThat(answer.view()).isNotNull();
        assertThat(answer.view().account()).isNotNull();
        assertThat(answer.view().customer()).isNotNull();
        assertThat(answer.revision())
                .as("the token must name the counters of the two rows the view was mapped from")
                .isEqualTo(AccountRevision.of(this.account, this.customer));
        verify(this.crossReferences, times(1)).findAccountScreenRows(any(), any(Limit.class));
    }

    /**
     * A change to either row alone moves the published token.
     *
     * <p>Purpose: the reference's comparison fails if EITHER record moved -- the snapshot at L669 of
     * {@code app/cbl/COACTUPC.cbl} spans both -- so a token that tracked only one of them would let an edit
     * proceed over a concurrent change to the other. This case advances each counter independently and
     * asserts the token differs each time.</p>
     *
     * <p>Assumptions: the three tokens are compared for mutual inequality rather than against literals,
     * for the reason the case above records. Asserting inequality is what shows the token is a function of
     * BOTH counters; a token derived from one of them would collide on one of these three pairs.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("advancing either row's counter alone moves the published revision")
    void eitherCounterMovesTheRevision() {
        stubComposition();

        versionOf(this.account, 1L);
        versionOf(this.customer, 1L);
        String both = this.reads.readAccountView(ACCOUNT_ID).revision();

        versionOf(this.account, 2L);
        String accountMoved = this.reads.readAccountView(ACCOUNT_ID).revision();

        versionOf(this.account, 1L);
        versionOf(this.customer, 2L);
        String customerMoved = this.reads.readAccountView(ACCOUNT_ID).revision();

        assertThat(List.of(both, accountMoved, customerMoved))
                .as("a token covering both counters cannot collide across these three states")
                .doesNotHaveDuplicates();
    }

    /**
     * An incomplete composition is refused rather than answered with a token for rows that were not read.
     *
     * <p>Assumptions: the customer-master miss is the arm to assert, because it is the one arm the
     * composition still populates the account half on -- the reference fills its account region under the
     * disjunction at L471 with L472 of {@code app/cbl/COACTVWC.cbl} while leaving the customer region at
     * L493 unpopulated -- so it is the only arm where a half-populated view could plausibly be published
     * with half a token. The entry point raises instead, which is what keeps a caller from ever holding a
     * precondition for a pair it was not shown.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a composition missing the customer row is refused, not answered with a partial token")
    void anIncompleteCompositionIsRefused() {
        when(this.crossReferences.findAccountScreenRows(any(), any(Limit.class)))
                .thenReturn(List.of(new AccountScreenRow(
                        new CardXref(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID), this.account, null)));

        assertThatThrownBy(() -> this.reads.readAccountView(ACCOUNT_ID))
                .as("a caller must not be handed a precondition for rows that were not both located")
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * The revision owner refuses construction and refuses a null row.
     *
     * <p>Assumptions: the reflective construction attempt is asserted as well as the null guards, because
     * a private constructor alone does not stop a reflective caller and the type holds no state that would
     * make an instance harmless. Reflection reports the thrown error wrapped, so the cause is what carries
     * the assertion.</p>
     *
     * @throws ReflectiveOperationException if the declared constructor cannot be reached at all, which is
     *     itself a failure of this case's premise
     */
    @Test
    @DisplayName("the revision owner is never constructed and never accepts a null row")
    void theRevisionOwnerIsNeverConstructed() throws ReflectiveOperationException {
        Constructor<AccountRevision> declared = AccountRevision.class.getDeclaredConstructor();
        declared.setAccessible(true);

        assertThatThrownBy(declared::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(AssertionError.class);

        assertThatThrownBy(() -> AccountRevision.of(null, this.customer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AccountRevision.of(this.account, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Stubs the composing query with a complete row over the two stored entities.
     *
     * <p>Assumptions: the stub answers with the SAME two instances the assertions read their counters
     * from, so a case can advance a counter between calls and the service observes the change. Copying the
     * rows would break exactly the property these cases exist to show.</p>
     *
     * <p>This method takes no parameter and returns no value.</p>
     */
    private void stubComposition() {
        when(this.crossReferences.findAccountScreenRows(any(), any(Limit.class)))
                .thenReturn(List.of(new AccountScreenRow(
                        new CardXref(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID),
                        this.account, this.customer)));
    }

    /**
     * Assigns an entity's optimistic-lock counter reflectively.
     *
     * <p>Assumptions: reflection is used because both entities deliberately publish a getter and no
     * setter -- the counter belongs to the persistence provider, which reads it on load, compares it on
     * flush and advances it on a successful write -- and a setter would let application code disable the
     * concurrency check silently. A test needing two distinguishable versions has no other route, and
     * reaching the field here rather than adding a setter keeps that route out of production code.</p>
     *
     * @param entity the account or customer row to assign; must not be {@code null}
     * @param value the counter value to assign
     * @throws IllegalStateException if the field cannot be reached, which would mean the entity no longer
     *     declares the counter this assertion depends on
     */
    private static void versionOf(Object entity, long value) {
        try {
            Field field = entity.getClass().getDeclaredField("version");
            field.setAccessible(true);
            field.setLong(entity, value);
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException(
                    "the entity no longer declares the version counter this case depends on",
                    unreachable);
        }
    }
}
