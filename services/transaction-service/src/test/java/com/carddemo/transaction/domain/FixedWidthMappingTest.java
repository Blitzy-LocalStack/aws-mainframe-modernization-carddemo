package com.carddemo.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.transaction.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins the two properties of the transaction-service entity mappings that regress without failing a
 * build: the explicit {@code CHAR} binding on every fixed-width column, and the values the diagnostic
 * renderings withhold.
 *
 * <p>The fourteen members asserted below are the complete set of fixed-width columns in this module --
 * six on {@link Transaction}, six on {@link DailyTransaction} and two on
 * {@link TransactionCategoryBalanceId}. Each is declared {@code CHAR(n)} in
 * {@code V1__ledger.sql} because a fixed width is part of the record contract inherited from the
 * copybook, and each therefore needs {@code @JdbcTypeCode(SqlTypes.CHAR)} so the driver sends the value
 * as a fixed-length string rather than a variable-length one.
 *
 * <p>Assumptions: the expected member names are typed here independently of the entities rather than
 * discovered from them. A test that enumerated whatever members already carried the annotation and
 * asserted they carried it would be a tautology -- it would pass on an entity that had lost every
 * annotation, because the enumeration would then be empty. Listing the names is what lets this test
 * fail.
 *
 * <p>Alternatives Considered: expressing the same intent with {@code columnDefinition = "CHAR(n)"} on
 * each {@code @Column} instead, and asserting that. Rejected for the reason the entities themselves
 * record: it embeds vendor DDL in a mapping that has no authority to create the table, and it is
 * consulted only by schema generation, which the deployed profiles disable. The JDBC type code affects
 * the bound parameter on every statement, which is where the problem actually is.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the parameterised methods below carry their own.
 */
class FixedWidthMappingTest {

    /**
     * The number of fixed-width columns this module maps, fourteen.
     *
     * <p>Assumptions: six on {@code Transaction}, six on {@code DailyTransaction} and two on the
     * embedded category-balance key. The count is asserted separately from the individual members so
     * that adding a fifteenth fixed-width column without its binding fails here rather than passing
     * unnoticed, which the per-member assertions alone could not catch.
     */
    private static final int FIXED_WIDTH_COLUMN_COUNT = 14;

    /**
     * A synthetic eleven-digit account identifier, at the declared width of
     * {@code TRANCAT-ACCT-ID PIC 9(11)}.
     *
     * <p>Refactoring Rationale: the two rendering cases below previously built their key with the
     * account identifier {@code 7}, and a one-digit value makes an absence assertion nearly vacuous --
     * a single digit collides with almost any other token a rendering emits, so
     * {@code doesNotContain} either passes for the wrong reason or has to be weakened until it asserts
     * nothing. A distinctive eleven-digit value cannot collide, which is what makes those assertions
     * mean what they say.
     *
     * <p>Assumptions: the digits are authored rather than taken from a seed row, so they identify no
     * real account. Nothing here depends on the value existing anywhere else -- both cases assert on a
     * rendering, not on a lookup.
     */
    private static final Long SYNTHETIC_ACCOUNT_ID = 21_820_493_291L;

    /**
     * Confirms each named fixed-width member carries an explicit {@code CHAR} JDBC type code.
     *
     * @param declaringType the simple name of the entity or embedded key declaring the member
     * @param memberName the Java field name of the mapped member
     * @throws NoSuchFieldException when the named member does not exist, which is itself the failure
     *     this test reports -- a fixed-width member that was renamed or removed without its binding
     *     being carried across
     */
    @ParameterizedTest
    @CsvSource({
        "Transaction,tranId",
        "Transaction,tranTypeCd",
        "Transaction,tranCatCd",
        "Transaction,tranSource",
        "Transaction,merchantZip",
        "Transaction,cardNum",
        "DailyTransaction,tranId",
        "DailyTransaction,tranTypeCd",
        "DailyTransaction,tranCatCd",
        "DailyTransaction,tranSource",
        "DailyTransaction,merchantZip",
        "DailyTransaction,cardNum",
        "TransactionCategoryBalanceId,typeCd",
        "TransactionCategoryBalanceId,categoryCd",
    })
    void everyFixedWidthMemberIsBoundAsCharExplicitly(String declaringType, String memberName)
            throws NoSuchFieldException {
        Field member = resolve(declaringType).getDeclaredField(memberName);

        JdbcTypeCode typeCode = member.getAnnotation(JdbcTypeCode.class);

        assertThat(typeCode)
                .as("%s.%s must carry @JdbcTypeCode so the driver binds it as a fixed-length string",
                        declaringType, memberName)
                .isNotNull();
        assertThat(typeCode.value()).isEqualTo(SqlTypes.CHAR);
        assertThat(member.getAnnotation(Column.class))
                .as("%s.%s must remain a mapped column", declaringType, memberName)
                .isNotNull();
    }

    /**
     * Confirms the fixed-width member count has not grown without a matching binding.
     *
     * <p>Assumptions: the count is derived by reflection over the three types and compared against a
     * literal, so a new {@code CHAR} column that arrived without its annotation lowers the derived
     * count below the literal and fails, while one that arrived with its annotation raises the derived
     * count above it and also fails -- prompting whoever added it to extend the case list above rather
     * than leaving the new member asserted by nothing.
     */
    @Test
    void theFixedWidthColumnInventoryIsExactlyTheDocumentedFourteen() {
        long annotated = List.<Class<?>>of(Transaction.class, DailyTransaction.class,
                        TransactionCategoryBalanceId.class).stream()
                .flatMap(type -> List.of(type.getDeclaredFields()).stream())
                .filter(field -> field.isAnnotationPresent(JdbcTypeCode.class))
                .count();

        assertThat(annotated).isEqualTo(FIXED_WIDTH_COLUMN_COUNT);
    }

    /**
     * Confirms the category-balance rendering withholds both the account identifier and the balance.
     *
     * <p>Refactoring Rationale: this case previously asserted the account identifier PRESENT, on the
     * argument recorded beneath the case below. That argument was wrong, so the assertion was pinning
     * the defect rather than the contract, and it is inverted here. The balance half of the case was
     * already right and is unchanged.
     *
     * <p>Assumptions: the balance is asserted absent in both its plain and its grouped spelling,
     * because a rendering that formatted it for readability would still disclose it and an assertion
     * against one spelling alone would pass.
     *
     * <p>Assumptions: the enclosing rendering delegates to the embedded key, so this case covers the
     * delegated path and the case below covers the direct one. Both are needed: the key is rendered
     * directly whenever a provider reports on a persistence-context entry or a map key, which no
     * assertion on the enclosing type can reach.
     */
    @Test
    void categoryBalanceRendersNeitherTheAccountIdentifierNorTheBalance() {
        TransactionCategoryBalance balance = new TransactionCategoryBalance(
                new TransactionCategoryBalanceId(SYNTHETIC_ACCOUNT_ID, "01", "0001"),
                new BigDecimal("1234.56"));

        String rendered = balance.toString();

        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_ACCOUNT_ID));
        assertThat(rendered).doesNotContain("accountId");
        assertThat(rendered).contains("typeCd=01");
        assertThat(rendered).contains("categoryCd=0001");
        assertThat(rendered).doesNotContain("1234.56");
        assertThat(rendered).doesNotContain("1,234.56");
        assertThat(rendered).doesNotContain("balance");
    }

    /**
     * Confirms the embedded key's own rendering keeps only its two reference codes.
     *
     * <p>Refactoring Rationale: this case previously asserted that all three components were rendered,
     * and argued that "the key is the row's identity and carries no monetary or personal value, so
     * withholding a component here would remove the only means of telling two rows apart in a log
     * without protecting anything", adding that the account identifier is not among the primary account
     * number, the card verification value, the national identifier and the government-issued identifier
     * that the disclosure rules name, and that the published contracts carry it in full. That list is
     * incomplete: the sensitive-data logging contract in {@code docs/architecture/observability.md}
     * names account and customer identifiers in a clause of their own. The appeal to the published
     * contract also compares two different surfaces -- a response body reaches one authenticated caller
     * who already holds authority over that account and is not retained, whereas a log line is
     * retained, aggregated and readable by every holder of log access. The identifier was protected all
     * along, so this case now asserts its absence.
     *
     * <p>Assumptions: the member NAME is asserted absent alongside its value. A rendering that emitted
     * {@code accountId=null} would pass a value-only assertion while still announcing that the
     * component is rendered, and the next populated key would disclose.
     */
    @Test
    void theEmbeddedKeyRendersOnlyItsTwoReferenceCodes() {
        String rendered =
                new TransactionCategoryBalanceId(SYNTHETIC_ACCOUNT_ID, "01", "0001").toString();

        assertThat(rendered).doesNotContain(String.valueOf(SYNTHETIC_ACCOUNT_ID));
        assertThat(rendered).doesNotContain("accountId");
        assertThat(rendered).contains("typeCd=01");
        assertThat(rendered).contains("categoryCd=0001");
    }

    /**
     * Resolves one of the three declaring types by its simple name.
     *
     * <p>Assumptions: a switch over three literal names is used rather than
     * {@code Class.forName}, so a mistyped name in the case list above fails at this method with the
     * name in hand rather than as a class-loading error.
     *
     * @param simpleName the simple name of the type, one of the three mapped here
     * @return the resolved type, never {@code null}
     * @throws IllegalArgumentException when the name is not one of the three
     */
    private Class<?> resolve(String simpleName) {
        return switch (simpleName) {
            case "Transaction" -> Transaction.class;
            case "DailyTransaction" -> DailyTransaction.class;
            case "TransactionCategoryBalanceId" -> TransactionCategoryBalanceId.class;
            default -> throw new IllegalArgumentException("unknown declaring type " + simpleName);
        };
    }
}
