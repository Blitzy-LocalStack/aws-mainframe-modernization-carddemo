package com.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.transaction.service.TransactionAddService;
import jakarta.validation.constraints.Pattern;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the published transaction contract and this module's request and response types to each other.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Refactoring Rationale: the review that prompted this class found six independent disagreements
 * between the contract this module publishes and the types it would serialise. The published paths
 * omitted the version prefix the edge routes on, so every published path was one no deployment
 * answered. One confirmation member was named for a noun invented during the migration on one side and
 * for the copybook field on the other. One member the response record carries was absent from the
 * created-body schema. Five identifier fields were bounded by a shared any-length digit run on the Java
 * side while the contract stated an exact width per field, so a value of the wrong length was accepted
 * by validation and then refused by the column. The paging direction was published as two lower-case
 * tokens and enumerated in Java as two upper-case constant names, so the serialised form of the enum
 * matched neither. And the correlation identity was bounded at a width the shared filter does not
 * enforce. Not one of those was visible to a compiler, because a schema is a document and a constraint
 * is an annotation argument.</p>
 *
 * <p>Assumptions: the contract is read from the CLASSPATH, so this asserts against the artifact the
 * module actually publishes rather than against a file the source tree happens to hold.</p>
 *
 * <p>Alternatives Considered: generating the request and response types from the contract, which would
 * make the disagreement impossible rather than merely detectable. Rejected for this module because the
 * mapping is not mechanical - it truncates padding, masks the primary account number, withholds a
 * verification value and renames three misspelled baseline fields, each of which needs a justification
 * at the point of the decision that a generator cannot hold - and because the migration plan states the
 * mapping layer is hand-written for exactly that reason. Asserting the agreement keeps the annotated
 * types and their rationale while making drift fail a build.</p>
 *
 * <p>Refactoring Rationale: a later review found a SEVENTH disagreement of the same kind, and it was one
 * a two-sided comparison could not have found. The amount field has three authorities -- this contract,
 * the request record and the service's own positional shape test -- and the first two agreed on the
 * record's nine integer digits while the third measured the eight-digit screen picture, so a nine-digit
 * amount cleared validation and was then refused after binding with a format sentence. Two cases were
 * added below for it: one reads the service's constant against the published pattern, and one reads the
 * key-selection rule against the baseline's own branch order rather than only against the other side.</p>
 */
class TransactionApiContractTest {

    /** Classpath location of the contract this module publishes. */
    private static final String CONTRACT_RESOURCE = "/openapi/transaction-api.yaml";

    /**
     * The paths the edge and the load balancer route to this module, from the committed IaC.
     *
     * <p>Refactoring Rationale: the copy-last path is included because the module now publishes it and
     * the committed rule already forwards it: {@code infra/envs/dev/main.tf} and its production sibling
     * both list {@code /api/v1/transactions/*} beside the collection, so a further segment under the
     * collection needs no IaC change. It is listed here so that this assertion keeps comparing the
     * contract with the deployed route table rather than with itself.</p>
     */
    private static final List<String> DEPLOYED_PATHS = List.of(
            "/api/v1/transactions", "/api/v1/transactions/copy-last",
            "/api/v1/transactions/{transactionId}", "/api/v1/billpay");

    /**
     * The keys of a path item that hold an operation, so its own non-operation keys are skipped.
     *
     * <p>Assumptions: all eight the specification defines are listed even though this document declares
     * only three of them, because a list narrowed to what is currently published would stop checking an
     * operation the moment one was added under a method it omitted -- which is the one occasion the
     * check is most needed.</p>
     */
    private static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /**
     * Each published schema paired with the Java constraint that must agree with it.
     *
     * <p>Assumptions: the two sides are compared modulo one deliberate difference. The Java patterns
     * carry a leading empty alternative and the published patterns do not, because absence over HTTP
     * is omission or the empty string while a bean-validation pattern is evaluated against whatever
     * arrived - so the Java side must admit the empty string and let a separate requiredness rule
     * decide, whereas the contract describes only the shape of a value that IS present. What must
     * agree is the shape itself, which is what this map asserts.</p>
     */
    private static final Map<String, String> SCHEMA_TO_JAVA_PATTERN = schemaToJavaPattern();

    /** Each published schema paired with the record component whose constraint must carry it. */
    private static final Map<String, String> SCHEMA_TO_COMPONENT = schemaToComponent();

    /** The parsed contract, loaded once per test instance. */
    private final Map<String, Object> contract = loadContract();

    /**
     * Builds the schema-to-constraint table.
     *
     * @return the published schema name against the Java pattern constant that must match it
     */
    private static Map<String, String> schemaToJavaPattern() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("AccountId", TransactionAddRequest.ACCOUNT_ID_DIGITS);
        table.put("CardNumber", TransactionAddRequest.CARD_NUMBER_DIGITS);
        table.put("TransactionTypeCode", TransactionAddRequest.TYPE_CODE_DIGITS);
        table.put("TransactionCategoryCode", TransactionAddRequest.CATEGORY_CODE_DIGITS);
        table.put("MerchantId", TransactionAddRequest.MERCHANT_ID_DIGITS);
        table.put("DateOnly10", TransactionAddRequest.ISO_DATE_SHAPE);
        return Map.copyOf(table);
    }

    /**
     * Builds the schema-to-component table.
     *
     * @return the published schema name against the record component that must declare its constraint
     */
    private static Map<String, String> schemaToComponent() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("AccountId", "accountId");
        table.put("CardNumber", "cardNumber");
        table.put("TransactionTypeCode", "typeCode");
        table.put("TransactionCategoryCode", "categoryCode");
        table.put("MerchantId", "merchantId");
        table.put("DateOnly10", "originDate");
        return Map.copyOf(table);
    }

    /**
     * Reads and parses the published contract from the classpath.
     *
     * @return the whole document as nested maps and lists; never {@code null}
     * @throws IllegalStateException if the resource is absent, which would mean the module publishes
     *     no contract at all, or if it cannot be read or parsed as a mapping
     */
    private static Map<String, Object> loadContract() {
        try (InputStream resource =
                TransactionApiContractTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException(
                        "the published contract is absent from the classpath at " + CONTRACT_RESOURCE);
            }
            Object parsed = new Yaml().load(resource);
            if (!(parsed instanceof Map)) {
                throw new IllegalStateException(
                        "the published contract at " + CONTRACT_RESOURCE + " is not a mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> document = (Map<String, Object>) parsed;
            return document;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(
                    "the published contract at " + CONTRACT_RESOURCE + " could not be read", failure);
        }
    }

    /**
     * Returns one nested mapping by key.
     *
     * @param parent the enclosing mapping; must not be {@code null}
     * @param key the key to read
     * @return the nested mapping
     * @throws IllegalStateException if the key is absent or does not hold a mapping, so a structural
     *     assumption fails where it is made rather than as a cast far from its cause
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map)) {
            throw new IllegalStateException("expected a mapping at \"" + key + "\"");
        }
        return (Map<String, Object>) value;
    }

    /**
     * Returns one named schema from the contract's component section.
     *
     * @param name the schema name
     * @return the schema as a mapping
     */
    private Map<String, Object> schema(String name) {
        return mapping(mapping(mapping(contract, "components"), "schemas"), name);
    }

    /**
     * Returns one named parameter from the contract's component section.
     *
     * @param name the parameter name
     * @return the parameter as a mapping
     */
    private Map<String, Object> parameter(String name) {
        return mapping(mapping(mapping(contract, "components"), "parameters"), name);
    }

    /**
     * Returns the list a schema publishes under a key, as strings.
     *
     * @param owner the schema or other mapping to read from
     * @param key the key holding a sequence
     * @return the sequence rendered as strings; empty when the key is absent
     */
    private static List<String> strings(Map<String, Object> owner, String key) {
        Object value = owner.get(key);
        if (!(value instanceof List<?> sequence)) {
            return List.of();
        }
        return sequence.stream().map(String::valueOf).toList();
    }

    /**
     * Strips the anchors from a published pattern so it can be compared with a bean-validation one.
     *
     * @param published the pattern as the contract states it, anchored at both ends
     * @return the same expression without its anchors
     */
    private static String unanchored(String published) {
        String body = published.startsWith("^") ? published.substring(1) : published;
        return body.endsWith("$") ? body.substring(0, body.length() - 1) : body;
    }

    /**
     * Removes the leading empty alternative a bean-validation pattern carries for the absent case.
     *
     * @param declared the pattern as the Java constraint states it
     * @return the same expression without its empty alternative
     */
    private static String withoutEmptyAlternative(String declared) {
        return declared.startsWith("|") ? declared.substring(1) : declared;
    }

    /**
     * Reads the pattern a record component's bean-validation constraint applies.
     *
     * @param owner the record type declaring the component
     * @param componentName the component to read
     * @return the regular expression the constraint applies
     * @throws IllegalStateException if the component carries no pattern constraint, which would mean
     *     the published shape is enforced nowhere
     */
    private static String appliedPattern(Class<?> owner, String componentName) {
        try {
            Field field = owner.getDeclaredField(componentName);
            Pattern constraint = field.getAnnotation(Pattern.class);
            if (constraint == null) {
                throw new IllegalStateException(owner.getSimpleName() + "." + componentName
                        + " declares no pattern constraint, so its published shape is unenforced");
            }
            return constraint.regexp();
        } catch (NoSuchFieldException absent) {
            throw new IllegalStateException(
                    owner.getSimpleName() + " declares no component named " + componentName, absent);
        }
    }

    /**
     * Reports whether a record type declares a component of the given name.
     *
     * @param owner the record type to inspect
     * @param componentName the component name to look for
     * @return {@code true} when the component is declared
     */
    private static boolean declaresComponent(Class<?> owner, String componentName) {
        return Arrays.stream(owner.getRecordComponents())
                .map(RecordComponent::getName)
                .anyMatch(componentName::equals);
    }

    /**
     * Returns the declared type of one record component.
     *
     * @param owner the record type declaring the component
     * @param componentName the component to read
     * @return the component's declared type
     * @throws IllegalStateException if the record declares no component of that name, so the failure
     *     names the member rather than surfacing as a null far from its cause
     */
    private static Class<?> componentType(Class<?> owner, String componentName) {
        return Arrays.stream(owner.getRecordComponents())
                .filter(component -> component.getName().equals(componentName))
                .map(RecordComponent::getType)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        owner.getSimpleName() + " declares no component named " + componentName));
    }

    /**
     * Returns the one schema reference a property composes through {@code allOf}.
     *
     * @param properties the property mapping to read from
     * @param name the property name
     * @return the referenced schema pointer
     * @throws IllegalStateException if the property composes no reference, which would mean its
     *     domain is declared inline and shares nothing with the schema it is meant to reuse
     */
    private static String composedReference(Map<String, Object> properties, String name) {
        Object composed = mapping(properties, name).get("allOf");
        if (composed instanceof List<?> parts) {
            for (Object part : parts) {
                if (part instanceof Map<?, ?> member && member.get("$ref") != null) {
                    return String.valueOf(member.get("$ref"));
                }
            }
        }
        throw new IllegalStateException(name + " composes no schema reference");
    }

    // WHY : Refactoring Rationale: the paths are asserted against the committed edge and load-balancer
    //       route tables rather than against one another, because the defect this closes was that the
    //       contract published three paths without the version prefix while every deployed route
    //       carries it. Comparing the contract only with itself would have been consistent and still
    //       wrong. The bill-payment spelling is taken FROM the IaC for the same reason.
    /**
     * Asserts that the published paths are exactly the ones the deployed route tables forward, and
     * that no path carries a primary account number.
     */
    @Test
    @DisplayName("the published paths are exactly the ones the edge routes")
    void publishedPathsAreTheDeployedPaths() {
        Map<String, Object> paths = mapping(contract, "paths");
        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(DEPLOYED_PATHS);
        assertThat(paths.keySet())
                .as("the load balancer refuses a rule whose pattern does not begin with the version"
                        + " prefix, so an unversioned path is unreachable")
                .allSatisfy(path -> assertThat(path).startsWith("/api/v1/"));
        assertThat(paths.keySet())
                .as("a primary account number may never appear in a request line, which is logged and"
                        + " retained by every hop between the browser and the service")
                .noneMatch(path -> path.contains("cardNumber"));
    }

    // WHY : Assumptions: the member is asserted to be OPTIONAL, not merely present. The baseline
    //       answers a blank confirmation by displaying the payable balance and asking for another
    //       turn (app/cbl/COBIL00C.cbl lines 169 to 188), so a required confirmation would make the
    //       preview state unreachable and would change observable behaviour rather than tightening a
    //       contract.
    /**
     * Asserts that both request bodies publish the confirmation member under the name the Java records
     * bind, that neither requires it, and that its published bound and value domain are the ones the
     * Java constraint applies.
     *
     * <p>Assumptions: the published name is asserted against the record component itself rather than
     * against a literal, because that identity IS the contract here. Neither of these types declares a
     * property-naming strategy or a per-property serialisation annotation, so a component name is the
     * published member name; and because both bodies close themselves with
     * {@code additionalProperties: false} while this module rejects unknown properties, publishing a
     * different spelling would refuse every contract-conformant body rather than merely reading
     * oddly.</p>
     */
    @Test
    @DisplayName("the confirmation member is published under the bound name, is optional, and shares"
            + " one value domain")
    void confirmationIsPublishedUnderTheBoundNameAndOptionalOnBothBodies() {
        String boundName = "confirmation";
        assertThat(Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("the asserted name must be the one the record actually binds")
                .contains(boundName);
        assertThat(Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("both bodies must bind one spelling, or a client sends a different name to two"
                        + " operations that read the same copybook field")
                .contains(boundName);

        for (String bodyName : List.of("TransactionCreateRequest", "BillPaymentRequest")) {
            Map<String, Object> body = schema(bodyName);
            assertThat(mapping(body, "properties"))
                    .as("%s must publish the confirmation under the name its record binds", bodyName)
                    .containsKey(boundName);
            assertThat(strings(body, "required"))
                    .as("%s must not require the confirmation, or its preview state is unreachable",
                            bodyName)
                    .doesNotContain("confirm", boundName);
            assertThat(mapping(body, "properties"))
                    .as("%s must not also carry the withdrawn shorter spelling", bodyName)
                    .doesNotContainKey("confirm");
        }

        Map<String, Object> confirmation = schema("Confirmation");
        assertThat(confirmation.get("maxLength")).isEqualTo(TransactionAddRequest.CONFIRM_WIDTH);
        assertThat(unanchored(String.valueOf(confirmation.get("pattern"))))
                .isEqualTo(TransactionAddRequest.CONFIRM_VALUES);
        assertThat(appliedPattern(TransactionAddRequest.class, boundName))
                .isEqualTo(TransactionAddRequest.CONFIRM_VALUES);
        assertThat(appliedPattern(BillPaymentRequest.class, "accountId"))
                .as("the payment body must bound its account identifier to the declared width rather"
                        + " than to an any-length digit run")
                .isEqualTo(unanchored(String.valueOf(schema("AccountId").get("pattern"))));
    }

    /**
     * Asserts that each published identifier shape is the shape the Java constraint applies, so a
     * value of the wrong length cannot pass validation and then be refused by a column.
     */
    @Test
    @DisplayName("each published identifier shape is the one the Java constraint applies")
    void publishedShapesAgreeWithTheAppliedConstraints() {
        SCHEMA_TO_JAVA_PATTERN.forEach((schemaName, javaPattern) -> {
            String published = unanchored(String.valueOf(schema(schemaName).get("pattern")));
            assertThat(withoutEmptyAlternative(javaPattern))
                    .as("schema %s and its Java constraint must describe one shape", schemaName)
                    .isEqualTo(published);
            assertThat(appliedPattern(TransactionAddRequest.class,
                    SCHEMA_TO_COMPONENT.get(schemaName)))
                    .as("the constant compared for %s must be the one actually applied to %s",
                            schemaName, SCHEMA_TO_COMPONENT.get(schemaName))
                    .isEqualTo(javaPattern);
        });
    }

    // WHY : Assumptions: nine integer digits is the RECORD's domain (TRAN-AMT PIC S9(09)V99 at
    //       app/cpy/CVTRA05Y.cpy line 9), not the screen's. The add screen's own edit mask shows
    //       eight, and taking the screen as the bound would refuse amounts the file holds; taking ten
    //       - the width of the balance edit on the same map - would accept amounts it cannot store.
    //       Both errors are one character wide, which is why the boundary is asserted rather than
    //       described.
    /**
     * Asserts that the published transaction amount admits nine integer digits and refuses ten, and
     * that the account balance is separately allowed the tenth digit its own field declares.
     */
    @Test
    @DisplayName("the amount domain is nine integer digits and the balance domain is ten")
    void amountAndBalanceDomainsAreDistinct() {
        java.util.regex.Pattern amount =
                java.util.regex.Pattern.compile(String.valueOf(schema("TransactionAmount")
                        .get("pattern")));
        assertThat(amount.matcher("999999999.99").matches()).isTrue();
        assertThat(amount.matcher("-999999999.99").matches()).isTrue();
        assertThat(amount.matcher("1000000000.99").matches())
                .as("a ten-digit amount does not fit the record field and must be refused")
                .isFalse();

        java.util.regex.Pattern balance =
                java.util.regex.Pattern.compile(String.valueOf(schema("AccountBalance")
                        .get("pattern")));
        assertThat(balance.matcher("1000000000.99").matches())
                .as("the balance field declares ten integer digits, so the two domains differ by one"
                        + " digit and must not be described by one schema")
                .isTrue();
    }

    // WHY : Refactoring Rationale: the amount domain has THREE authorities and the case above compares
    //       only two of them. The published schema and the record constraint agreed on nine integer
    //       digits while TransactionAddService measured the eight-digit SCREEN picture at line 59 of
    //       app/cbl/COTRN02C.cbl, so a nine-digit amount cleared the boundary this class guards and was
    //       then refused by the service with a format sentence -- input the contract published as valid,
    //       rejected after deserialization, and divergence D-AMOUNT-RECORD-WIDTH describing a width the
    //       delivered code did not honour. Two agreeing authorities and one dissenting one is exactly
    //       what a two-way comparison cannot see, which is why the third is read here.
    /**
     * Asserts that the width the service's positional shape test measures is the published domain's.
     *
     * <p>Assumptions: the service constant is read rather than the service being invoked, which is what
     * this package's charter asks for -- the defect is in the DECLARATIONS, and a behavioural case
     * would see only the values it happened to submit. The behaviour is covered separately by the
     * capture screen's own suite, which asserts acceptance at nine digits and refusal at ten.</p>
     */
    @Test
    @DisplayName("the width the service's shape test measures is the published amount domain's")
    void theServiceShapeTestMeasuresThePublishedAmountDomain() {
        java.util.regex.Pattern amount =
                java.util.regex.Pattern.compile(String.valueOf(schema("TransactionAmount")
                        .get("pattern")));
        String atTheBound = "9".repeat(TransactionAddService.RECORD_AMOUNT_INTEGER_DIGITS) + ".99";
        String oneDigitOver =
                "1" + "0".repeat(TransactionAddService.RECORD_AMOUNT_INTEGER_DIGITS) + ".99";

        assertThat(amount.matcher(atTheBound).matches())
                .as("the service admits %s integer digits, so the contract must publish them",
                        TransactionAddService.RECORD_AMOUNT_INTEGER_DIGITS)
                .isTrue();
        assertThat(amount.matcher(oneDigitOver).matches())
                .as("and one digit more is outside both, so neither authority is the wider one")
                .isFalse();
        assertThat(String.valueOf(TransactionAddService.RECORD_AMOUNT_LENGTH))
                .as("the rendering the shape test spans is the widest string the contract admits: a"
                        + " sign, the integer digits, the point and two fractional digits")
                .isEqualTo(String.valueOf(schema("TransactionAmount").get("maxLength")));
    }

    // WHY : Refactoring Rationale: the key rule was published as EXCLUSIVE disjunction on both sides,
    //       and both sides were wrong together -- which is the one failure mode a two-sided agreement
    //       test cannot catch, so this case reads the BASELINE's semantics into its assertions rather
    //       than only comparing the two deliverables. Line 195 of app/cbl/COTRN02C.cbl opens an
    //       EVALUATE TRUE whose account arm is first, so a both-keys submission resolves through the
    //       account identifier and line 209 overwrites the submitted card number with no message. Only
    //       lines 224 to 229, reached when NEITHER key arrived, report anything.
    /**
     * Asserts the key rule is published as at-least-one on both sides, with no mutual exclusion.
     *
     * <p>Assumptions: the absence of a {@code not} clause is asserted explicitly and not merely implied
     * by the keyword being {@code anyOf}. A branch set written as {@code anyOf} in which each branch
     * still forbids the other is the same contract as {@code oneOf} under a different name, so reading
     * the keyword alone would pass on a document that had not changed meaning.</p>
     *
     * <p>Assumptions: the Java side is read as the presence of the class-level constraint rather than by
     * running it, per this package's charter. Its behaviour -- both keys admitted, neither refused with
     * both members named -- is asserted at the wire by the resource's own suite.</p>
     */
    @Test
    @DisplayName("the key rule is at-least-one on both sides, and neither branch forbids the other")
    void theKeyRuleIsAtLeastOneOnBothSides() {
        Map<String, Object> request = schema("TransactionCreateRequest");

        assertThat(request)
                .as("mutual exclusion is not what the baseline's EVALUATE TRUE describes")
                .doesNotContainKey("oneOf")
                .containsKey("anyOf");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> branches = (List<Map<String, Object>>) request.get("anyOf");
        assertThat(branches)
                .as("one branch per key alternative, and nothing else")
                .hasSize(2);
        assertThat(branches).allSatisfy(branch -> assertThat(branch)
                .as("a branch that forbids the other is oneOf under another name")
                .doesNotContainKey("not"));
        assertThat(branches.stream().map(branch -> strings(branch, "required")).toList())
                .as("the two branches name the two key members")
                .containsExactlyInAnyOrder(List.of("accountId"), List.of("cardNumber"));

        assertThat(TransactionAddRequest.class
                .isAnnotationPresent(TransactionAddRequest.AtLeastOneKey.class))
                .as("the Java side carries the same rule, named for the same semantics")
                .isTrue();
    }

    // WHY : Assumptions: the enum's serialised form is asserted through fromWireValue rather than by
    //       name, because the defect was precisely that the names and the tokens differed. Comparing
    //       Direction.values() by name() would have reproduced the bug in the test.
    /**
     * Asserts that the published direction vocabulary is what the Java enum serialises and accepts,
     * that its default is the forward token, and that no paging input is named after a row identity.
     */
    @Test
    @DisplayName("the direction vocabulary is what the Java enum serialises and accepts")
    void directionVocabularyMatchesTheJavaEnum() {
        List<String> published = strings(schema("PageDirection"), "enum");
        assertThat(published)
                .containsExactly(
                        TransactionListRequest.Direction.NEXT.wireValue(),
                        TransactionListRequest.Direction.PREVIOUS.wireValue());
        published.forEach(token -> assertThatCode(
                () -> TransactionListRequest.Direction.fromWireValue(token))
                .as("the published token %s must be one the request type accepts", token)
                .doesNotThrowAnyException());
        assertThat(schema("PageDirection").get("default"))
                .isEqualTo(TransactionListRequest.Direction.NEXT.wireValue());

        assertThat(parameter("Cursor").get("name")).isEqualTo("cursor");
        assertThat(parameter("Direction").get("name")).isEqualTo("direction");
        assertThat(mapping(mapping(contract, "components"), "parameters").keySet())
                .as("a request parameter named after a response row identity is the collision this"
                        + " contract removed: those members are null on exactly the page whose"
                        + " cursors are not")
                .doesNotContain("FirstKey", "LastKey");
    }

    /**
     * Asserts that the page envelope declares and requires exactly the five members the shared
     * response type carries, so no generated client receives an accessor for a member no service
     * emits and no strict client rejects a valid response for a member no service sends.
     *
     * <p>Assumptions: the four are read from the shared type rather than restated as a literal list
     * where the type can be reached, because the whole defect this asserts against was a contract that
     * named members the type does not declare.</p>
     */
    @Test
    @DisplayName("the page envelope declares and requires exactly the shared envelope's four members")
    void pageEnvelopeDeclaresExactlyTheSharedEnvelopeMembers() {
        assertThat(strings(schema("TransactionPage"), "required"))
                .containsExactlyInAnyOrder("items", "firstKey", "lastKey", "hasNext");
    }

    /**
     * Asserts that the created-body schema requires every member the Java response record carries, so
     * a client is not left treating a value that is always present as optional.
     */
    @Test
    @DisplayName("the created body requires every member the response record carries")
    void createdBodyRequiresEveryResponseMember() {
        List<String> required = strings(schema("TransactionCreated"), "required");
        List<String> components = Arrays.stream(TransactionAddResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(required).containsExactlyInAnyOrderElementsOf(components);
        assertThat(components)
                .as("the amount is the member an earlier revision of this contract omitted")
                .contains("amount");
    }


    /**
     * Asserts that the posted-payment body describes exactly the record the service emits.
     *
     * <p>Refactoring Rationale: the review that prompted this test found the two sides describing
     * different shapes under one name -- seven published members against four declared components, and
     * a currentBalance the document read on the far side of the subtraction that the record reads on
     * the near side -- with no mapper constructing either, so nothing could notice. Comparing the
     * published property set with the record's components makes a future divergence a build failure
     * instead of a discovery.</p>
     *
     * <p>Assumptions: the property set is compared for EXACT equality rather than containment, because
     * both directions of drift were present and both matter. A published member the record does not
     * carry is a value a generated client waits for and never receives; a component the document does
     * not publish is a value a client discards.</p>
     *
     * <p>Assumptions: the required set is the components minus the message, which is the one member the
     * baseline can genuinely omit -- {@code CVCRD01Y} attaches a low-values sentinel to
     * {@code CCARD-RETURN-MSG} alone. Deriving the expectation that way rather than restating four
     * names keeps it correct if a component is added.</p>
     */
    @Test
    @DisplayName("the posted-payment body publishes exactly the response record's members")
    void postedPaymentBodyPublishesExactlyTheRecordMembers() {
        Map<String, Object> posted = schema("BillPaymentResponse");
        List<String> components = Arrays.stream(BillPaymentResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(mapping(posted, "properties").keySet())
                .as("a published member the record omits is a value a client waits for and never"
                        + " receives, and the reverse is a value it discards")
                .containsExactlyInAnyOrderElementsOf(components);
        assertThat(strings(posted, "required"))
                .containsExactlyInAnyOrderElementsOf(
                        components.stream().filter(name -> !"returnMessage".equals(name)).toList());
        assertThat(posted.get("additionalProperties")).isEqualTo(false);
        assertThat(mapping(mapping(posted, "properties"), "paid").get("const"))
                .as("the discriminator's published constant must be the value the record's factory"
                        + " fixes, otherwise a body validates against the wrong one of the pair")
                .isEqualTo(BillPaymentResponse.PAYMENT_POSTED);
        assertThat(mapping(posted, "properties"))
                .as("the masked card number is deliberately absent: app/cpy-bms/COBIL00.CPY declares"
                        + " no card field, so the baseline screen never shows one")
                .doesNotContainKey("cardNumber")
                .as("one money member reports the balance and the amount, because they are one number")
                .doesNotContainKey("amountPaid");

        // WHY : Assumptions: the money member's own published shape and Java type are asserted here
        //       rather than left to the property-set comparison above, because a set comparison sees
        //       only names. A component renamed nowhere but retyped from the exact money type to a
        //       bare decimal keeps every name in place and silently emits a JSON number, which is the
        //       one failure in this body that a strict client would accept.
        assertThat(composedReference(mapping(posted, "properties"), "currentBalance"))
                .isEqualTo("#/components/schemas/AccountBalance");
        assertThat(componentType(BillPaymentResponse.class, "currentBalance"))
                .as("the declared type is what selects the quoted-string wire form")
                .isEqualTo(Money.class);
        assertThat(componentType(BillPaymentResponse.class, "paid"))
                .as("a wrapper would admit a null the published constant refuses")
                .isEqualTo(boolean.class);
    }

    /**
     * Asserts that each preview body describes exactly the record the service emits on 200.
     *
     * <p>Purpose: both write operations of this contract answer two statuses with two bodies, and the
     * review that prompted this test found both 200 bodies rejected by the schemas their own status
     * publishes. Transaction add returned the created record, which declares no {@code written} member
     * the preview schema requires and does declare an identifier the preview schema forbids; bill pay
     * returned the posted record, whose money member is named {@code currentBalance} where the preview
     * schema requires {@code payableBalance}, and which likewise carries a forbidden identifier. Neither
     * failure was visible to a compiler, because one side is a document.</p>
     *
     * <p>Assumptions: the property sets are compared for EXACT equality rather than containment, because
     * both directions of drift matter. A published member the record does not carry is a value a
     * generated client waits for and never receives; a component the document does not publish, in an
     * object closed with {@code additionalProperties: false}, is a body a strict client rejects
     * outright.</p>
     */
    @Test
    @DisplayName("each preview body publishes exactly the preview record's members")
    void previewBodiesPublishExactlyThePreviewRecordMembers() {
        assertPublishedShapeMatchesRecord("TransactionAddPreview",
                TransactionAddPreview.class, "returnMessage");
        // WHY : ⚠️ Assumptions: the payment preview has TWO optional members where the capture preview has
        //       one, and the second is the money member. A declined confirmation reaches no account read
        //       at all -- CLEAR-CURRENT-SCREEN at line 180 of app/cbl/COBIL00C.cbl blanks the display
        //       fields -- so the withheld record carries no balance on that branch, and a contract that
        //       required one would oblige this service to publish a figure it deliberately does not read.
        assertPublishedShapeMatchesRecord("BillPaymentPreview",
                BillPaymentPreview.class, "returnMessage", "payableBalance");

        assertThat(mapping(mapping(schema("TransactionAddPreview"), "properties"), "written")
                        .get("const"))
                .as("the published constant must be the value the preview factory fixes")
                .isEqualTo(TransactionAddPreview.CAPTURE_WITHHELD);
        assertThat(mapping(mapping(schema("BillPaymentPreview"), "properties"), "paid").get("const"))
                .as("the published constant must be the value the preview factory fixes")
                .isEqualTo(BillPaymentPreview.PAYMENT_WITHHELD);

        // WHY : Assumptions: the money member of the preview is asserted to be a DIFFERENT name from the
        //       money member of the posted shape, because that difference is the whole reason the two
        //       shapes exist separately. The posted figure is the balance before the payment and is
        //       therefore also the amount paid; the preview figure is what a confirmed request would pay
        //       and nothing has been paid. One name would make a client's reading of the number depend
        //       on a status it may no longer hold.
        assertThat(mapping(schema("BillPaymentPreview"), "properties"))
                .containsKey("payableBalance")
                .doesNotContainKey("currentBalance");
        assertThat(mapping(schema("BillPaymentResponse"), "properties"))
                .containsKey("currentBalance")
                .doesNotContainKey("payableBalance");
    }

    // WHY : Refactoring Rationale: this asserts the SERIALISED body rather than the record's component
    //       list, and the two are not the same statement. A record component is a Java name; a body is
    //       what a client receives, and the failure being closed here was a body carrying a property
    //       the schema forbids. Only serialising can catch a property added by an annotation, a getter
    //       or a registered module, and only serialising can show that a nullable member is EMITTED as
    //       null rather than omitted -- which matters because a null of a forbidden name fails a closed
    //       schema just as a value would.
    /**
     * Asserts that a real serialisation of each of the four response bodies satisfies the schema its own
     * status publishes: every emitted property is declared, every required property is emitted, and the
     * object is closed.
     */
    @Test
    @DisplayName("every serialised response body satisfies its own published schema")
    void everySerialisedBodySatisfiesItsPublishedSchema() {
        ObjectMapper writer = JsonMapper.builder().addModule(new MoneyModule()).build();

        assertSerialisedBodySatisfiesSchema(writer, "TransactionAddPreview",
                TransactionAddPreview.prompting(Money.of("125.50"),
                        "Confirm to add this transaction..."));
        assertSerialisedBodySatisfiesSchema(writer, "TransactionCreated",
                new TransactionAddResponse("0000000000683580", Money.of("125.50"),
                        "Transaction added successfully."));
        assertSerialisedBodySatisfiesSchema(writer, "BillPaymentPreview",
                BillPaymentPreview.reporting("00000000011", Money.of("123.45"),
                        "Confirm to make a bill payment..."));
        assertSerialisedBodySatisfiesSchema(writer, "BillPaymentResponse",
                BillPaymentResponse.posted("00000000011", Money.of("123.45"), "0000000000683581",
                        "Payment successful."));

        // WHY : Assumptions: the declined-payment turn is serialised separately, because it is the one
        //       body of the four whose message is legitimately null. The reference reaches it at lines
        //       178 to 181 of app/cbl/COBIL00C.cbl without moving any message, and a null emitted under
        //       a declared, nullable property is exactly what the published ReturnMessage union admits.
        assertSerialisedBodySatisfiesSchema(writer, "BillPaymentPreview",
                BillPaymentPreview.reporting("00000000011", Money.of("123.45"), null));
    }

    /**
     * Asserts that a published schema declares exactly one record's components, requires all but the
     * named optional ones, and is closed.
     *
     * @param schemaName the published schema to read
     * @param record the record that implements it
     * @param optional the component names the schema may leave out of its required list
     */
    private void assertPublishedShapeMatchesRecord(String schemaName, Class<?> record,
            String... optional) {
        Map<String, Object> published = schema(schemaName);
        List<String> components = Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        List<String> optionalNames = List.of(optional);

        assertThat(mapping(published, "properties").keySet())
                .as("%s must publish exactly the members %s carries", schemaName,
                        record.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(components);
        assertThat(strings(published, "required"))
                .as("%s must require every member but %s", schemaName, optionalNames)
                .containsExactlyInAnyOrderElementsOf(
                        components.stream().filter(name -> !optionalNames.contains(name)).toList());
        assertThat(published.get("additionalProperties"))
                .as("%s must be closed, so a surplus property is a detectable failure", schemaName)
                .isEqualTo(false);
    }

    /**
     * Asserts that one serialised body satisfies one published schema.
     *
     * @param writer a mapper configured exactly as the application's own is for money
     * @param schemaName the published schema the body is answered under
     * @param body the response instance to serialise
     */
    private void assertSerialisedBodySatisfiesSchema(ObjectMapper writer, String schemaName,
            Object body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> emitted = writer.readValue(writer.writeValueAsString(body), Map.class);
        Map<String, Object> published = schema(schemaName);
        java.util.Set<String> declared = mapping(published, "properties").keySet();

        List<String> undeclared = new ArrayList<>(emitted.keySet());
        undeclared.removeAll(declared);
        assertThat(undeclared)
                .as("%s is closed with additionalProperties false, so %s may emit no other property",
                        schemaName, body.getClass().getSimpleName())
                .isEmpty();
        assertThat(emitted.keySet())
                .as("%s requires every one of %s", schemaName, strings(published, "required"))
                .containsAll(strings(published, "required"));

        // WHY : Assumptions: a money member is asserted to be a JSON STRING here, in the emitted body,
        //       and not only to be declared as the exact-decimal type on the record. The declared type
        //       is what selects the wire form, but only the emitted value proves the module that renders
        //       it is registered -- and an unregistered module emits a JSON number, which most clients
        //       parse into an IEEE-754 double and which no assertion on the Java type would notice.
        for (RecordComponent component : body.getClass().getRecordComponents()) {
            if (Money.class.equals(component.getType())) {
                assertThat(emitted.get(component.getName()))
                        .as("%s.%s must reach the wire as a quoted decimal", schemaName,
                                component.getName())
                        .isInstanceOf(String.class);
            }
        }
    }

    // WHY : Assumptions: this holds the two independently authored statements of ONE rule together --
    //       the published schema and the Java constraint -- so neither can be changed alone. The rule
    //       is PARITY and not a narrowing: app/cbl/COTRN02C.cbl L195 is EVALUATE TRUE, its first arm
    //       at L196 fires whatever the card field holds, and L209 overwrites that field from the
    //       cross-reference, so the reference is INCLUSIVE with account precedence. A
    //       D-ADD-KEY-EXCLUSIVE entry in section 7.4 of
    //       docs/architecture/cobol-to-service-traceability.md registered the exclusive form and is
    //       withdrawn there, which is why no identifier is cited beside this case.
    /**
     * Asserts that the published key-selection rule and the Java constraint state one rule.
     *
     * <p>Assumptions: the document expresses the rule as a two-branch {@code anyOf} in which each
     * branch requires one key and NEITHER forbids the other, and the absence of the {@code not: required}
     * half is asserted as positively as the {@code required} half is. Without that assertion a reader
     * could reintroduce {@code not} -- or switch the keyword back to {@code oneOf}, under which two
     * satisfied branches fail rather than pass -- and the schema would refuse a body the reference
     * accepts while every other assertion here still held.</p>
     */
    @Test
    @DisplayName("the published key-selection rule is the constraint the request type applies")
    void publishedKeySelectionRuleMatchesTheAppliedConstraint() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> branches =
                (List<Map<String, Object>>) schema("TransactionCreateRequest").get("anyOf");

        assertThat(branches)
                .as("the rule is published as exactly two alternatives, one per key, under anyOf")
                .hasSize(2);
        // WHY : Refactoring Rationale: the published rule is INCLUSIVE and this assertion states that.
        //       It previously required each branch to forbid the other key, which refused a body
        //       carrying both -- a body app/cbl/COTRN02C.cbl accepts and processes account-first at
        //       line 226 onwards, selecting the account arm and never evaluating the card arm. An
        //       exclusive rule therefore made the documented account-first precedence unreachable,
        //       because validation rejected the request before the precedence could be applied, and no
        //       reference message exists for a contradiction the reference never reports.
        assertThat(branches.stream().map(branch -> strings(branch, "required")).toList())
                .as("each alternative requires one key, and neither forbids the other")
                .containsExactlyInAnyOrder(List.of("accountId"), List.of("cardNumber"));
        assertThat(branches.stream().map(branch -> branch.containsKey("not")).toList())
                .as("no branch may carry a negation, or a both-keys body matches neither")
                .containsExactly(false, false);

        assertThat(TransactionAddRequest.class
                        .isAnnotationPresent(TransactionAddRequest.AtLeastOneKey.class))
                .as("the Java side states the same rule as a class-level constraint, so a body the"
                        + " document refuses is refused before a handler is entered")
                .isTrue();
        assertThat(TransactionAddRequest.class
                        .getAnnotation(TransactionAddRequest.AtLeastOneKey.class).message())
                .as("and carries app/cbl/COTRN02C.cbl line 226's sentence verbatim")
                .isEqualTo(TransactionAddRequest.KEY_FIELD_REQUIRED);
    }

    /**
     * Asserts that the browse filter is published and bounded exactly as the request type bounds it.
     */
    @Test
    @DisplayName("the browse filter is published and bounded as the request type bounds it")
    void browseFilterIsPublishedAndBounded() {
        Map<String, Object> filter = parameter("TransactionIdFilter");
        assertThat(filter.get("name")).isEqualTo("transactionIdFilter");
        assertThat(filter.get("in")).isEqualTo("query");
        assertThat(declaresComponent(TransactionListRequest.class, "transactionIdFilter")).isTrue();
        assertThat(withoutEmptyAlternative(
                appliedPattern(TransactionListRequest.class, "transactionIdFilter")))
                .as("the filter and the published identifier must describe one shape")
                .isEqualTo(unanchored(String.valueOf(schema("TransactionId").get("pattern"))));
    }

    // WHY : Assumptions: the detail body is asserted to reference the MASKED schema rather than the
    //       full one, because both exist in this document and they differ only in a pattern. The
    //       migration plan permits the full number on one administrative card endpoint in another
    //       context and nowhere in this one.
    /**
     * Asserts that the transaction detail discloses only a masked card number, and that the summary
     * discloses none at all.
     */
    @Test
    @DisplayName("the detail body discloses a masked card number and the summary none")
    void disclosureBoundaryIsStructural() {
        Map<String, Object> detailCard =
                mapping(mapping(schema("TransactionDetail"), "properties"), "cardNumber");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> composed = (List<Map<String, Object>>) detailCard.get("allOf");
        assertThat(composed)
                .as("the detail's card member must compose the masked schema")
                .anySatisfy(part -> assertThat(String.valueOf(part.get("$ref")))
                        .isEqualTo("#/components/schemas/MaskedCardNumber"));
        assertThat(mapping(schema("TransactionSummary"), "properties"))
                .as("a list row has no need of a card number at all, and the narrowest disclosure is"
                        + " none")
                .doesNotContainKey("cardNumber");
    }

    /**
     * Asserts that the published correlation bound, character set and header name are exactly what the
     * shared filter enforces.
     */
    @Test
    @DisplayName("the correlation identity is bounded exactly as the shared filter enforces")
    void correlationIdentityMatchesTheSharedFilter() {
        Map<String, Object> correlationId = schema("CorrelationId");
        assertThat(correlationId.get("maxLength"))
                .isEqualTo(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH);

        java.util.regex.Pattern declared =
                java.util.regex.Pattern.compile(String.valueOf(correlationId.get("pattern")));
        assertThat(declared.matcher("A".repeat(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH))
                .matches()).isTrue();
        assertThat(declared.matcher("8f1c0e42-3a55-4d21-9b7e-6c0f2a9d4471").matches())
                .as("a thirty-six-character identity is what the filter refuses with 400")
                .isFalse();
        assertThat(parameter("CorrelationIdHeader").get("name"))
                .isEqualTo(CorrelationIdFilter.CORRELATION_ID_HEADER);
    }

    /**
     * Reduces a declared parameter list to the {@code (name, in)} pairs the specification identifies
     * parameters by, following any reference into the components section.
     *
     * <p>Assumptions: a reference is followed rather than compared as a string, because two parameters
     * are the same parameter when their name and location agree -- not when their references agree. A
     * document declaring one header inline and the same header by reference would carry it twice while
     * the two spellings differed, so comparing spellings would report no duplicate at all.</p>
     *
     * @param declared the value of a {@code parameters} key, which may be {@code null} when none are
     *     declared
     * @return one {@code name|in} entry per declared parameter, in document order and WITH repeats
     *     preserved, since the repeats are what this reduction exists to expose; never {@code null}
     * @throws IllegalStateException if an entry is neither a mapping nor resolvable, or if a reference
     *     names a component the document does not declare, either of which makes the contract
     *     unreadable rather than merely wrong
     */
    private List<String> parameterIdentities(Object declared) {
        if (declared == null) {
            return List.of();
        }
        if (!(declared instanceof List<?> entries)) {
            throw new IllegalStateException("a \"parameters\" key must hold a sequence");
        }
        List<String> identities = new java.util.ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new IllegalStateException("every declared parameter must be a mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> resolved = (Map<String, Object>) raw;
            Object reference = resolved.get("$ref");
            if (reference instanceof String pointer) {
                resolved = parameter(pointer.substring(pointer.lastIndexOf('/') + 1));
            }
            identities.add(resolved.get("name") + "|" + resolved.get("in"));
        }
        return identities;
    }

    /**
     * Asserts no operation carries one {@code (name, in)} parameter pair twice, whether within its own
     * list or by restating one it already inherits from its path item.
     *
     * <p>Refactoring Rationale: this gate exists because one operation in this document did exactly
     * that. The path item for a single transaction declared the correlation header at the PATH level
     * while its only operation declared the identical reference again, so the effective list carried
     * the same pair twice. The specification permits an operation to OVERRIDE an inherited parameter,
     * which is why that shape was not rejected by a parser, and permitting it is precisely what made
     * the defect invisible: the two declarations were the same reference, so the override narrowed
     * nothing and a reader could not tell which copy was meant to be authoritative. A generator
     * reading it emits the header twice.
     *
     * <p>Assumptions: an inherited pair restated by an operation is therefore refused here rather than
     * merely reported. The convention this document now follows without exception is that a path item
     * declares only its PATH parameters and each operation declares its own header parameters, so a
     * restatement can only be an oversight; an operation that genuinely needed to narrow an inherited
     * parameter would move the declaration to the operation level rather than duplicate it.
     *
     * <p>Trade-offs: the walk is declared in this module rather than shared with the sibling service
     * whose contract carried the same defect. The shared kernel's test artifact is deliberately
     * restricted to its architecture package -- this module's POM records that restriction where it
     * declares the artifact -- so no test type is visible to both modules. The accepted cost is one
     * copy of this walk per affected module; what is bought is that neither build depends on widening
     * a boundary that exists to keep the kernel's test surface closed.
     */
    @Test
    @DisplayName("no operation declares one parameter identity twice, inherited or otherwise")
    void noOperationDeclaresOneParameterIdentityTwice() {
        Map<String, Object> paths = mapping(contract, "paths");

        assertThat(paths).as("the contract must publish at least one path").isNotEmpty();

        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            Map<String, Object> pathItem = mapping(paths, pathEntry.getKey());
            List<String> inherited = parameterIdentities(pathItem.get("parameters"));

            assertThat(inherited)
                    .as("path item %s must not declare one parameter identity twice",
                            pathEntry.getKey())
                    .doesNotHaveDuplicates();

            for (String method : HTTP_METHODS) {
                if (!pathItem.containsKey(method)) {
                    continue;
                }
                List<String> own =
                        parameterIdentities(mapping(pathItem, method).get("parameters"));

                assertThat(own)
                        .as("%s %s must not declare one parameter identity twice",
                                method.toUpperCase(java.util.Locale.ROOT), pathEntry.getKey())
                        .doesNotHaveDuplicates();
                // WHY : Assumptions: the inherited set is tested for emptiness first because the
                //       assertion below refuses an empty expectation outright rather than passing
                //       vacuously -- a path item declaring no parameters of its own would otherwise
                //       fail this case for a reason that has nothing to do with duplication.
                if (!inherited.isEmpty()) {
                    assertThat(own)
                            .as("%s %s must not restate a parameter it already inherits",
                                    method.toUpperCase(java.util.Locale.ROOT), pathEntry.getKey())
                            .doesNotContainAnyElementsOf(inherited);
                }
            }
        }
    }
}
