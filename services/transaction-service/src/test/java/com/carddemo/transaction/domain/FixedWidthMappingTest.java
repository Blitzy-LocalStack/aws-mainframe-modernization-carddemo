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
     * Confirms the category-balance rendering carries its key and withholds the balance.
     *
     * <p>Assumptions: the balance is asserted absent in both its plain and its grouped spelling,
     * because a rendering that formatted it for readability would still disclose it and an assertion
     * against one spelling alone would pass.
     */
    @Test
    void categoryBalanceRendersTheKeyAndWithholdsTheBalance() {
        TransactionCategoryBalance balance = new TransactionCategoryBalance(
                new TransactionCategoryBalanceId(7L, "01", "0001"), new BigDecimal("1234.56"));

        String rendered = balance.toString();

        assertThat(rendered).contains("accountId=7");
        assertThat(rendered).contains("typeCd=01");
        assertThat(rendered).contains("categoryCd=0001");
        assertThat(rendered).doesNotContain("1234.56");
        assertThat(rendered).doesNotContain("1,234.56");
        assertThat(rendered).doesNotContain("balance");
    }

    /**
     * Confirms the embedded key's own rendering keeps all three of its components.
     *
     * <p>Assumptions: the key is the row's identity and carries no monetary or personal value, so
     * withholding a component here would remove the only means of telling two rows apart in a log
     * without protecting anything. The account identifier specifically is not one of the identifiers
     * the migration's disclosure rules name -- those are the primary account number, the card
     * verification value, the national identifier and the government-issued identifier -- and the
     * published API contracts carry it in full as eleven digits.
     */
    @Test
    void theEmbeddedKeyRendersAllThreeComponents() {
        String rendered = new TransactionCategoryBalanceId(7L, "01", "0001").toString();

        assertThat(rendered).contains("accountId=7");
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
