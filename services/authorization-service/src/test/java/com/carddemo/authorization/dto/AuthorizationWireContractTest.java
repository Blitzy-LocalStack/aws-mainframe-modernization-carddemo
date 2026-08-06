package com.carddemo.authorization.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the published contract and the Java shapes that realise it to one another.
 *
 * <p>Refactoring Rationale: this class exists because the two drifted and nothing reported it. The
 * published schema of the queue request payload declared a required property
 * {@code merchantCatagoryCode}, carrying the reference system's misspelling, while
 * {@link AuthorizationRequestPayload} declares the component {@code merchantCategoryCode} and no
 * property alias. A plain record binds by component name, so the published contract required a
 * property this service never produced and never accepted. Nothing failed, because no assertion
 * compared the two: every existing test in this package reads either the record or the delimited
 * wire, and none read the document. That gap is what the assertions below close.
 *
 * <p>Assumptions: the correction is fixed by the migration plan rather than chosen. Transformation
 * rule T1 makes the copybook normative and permits exactly three renames, and the merchant category
 * code is the third of them: the reference misspells it in all three of its own declarations --
 * {@code PA-MERCHANT-CATAGORY-CODE} at {@code cpy/CIPAUDTY.cpy} L36,
 * {@code PA-RQ-MERCHANT-CATAGORY-CODE} at {@code cpy/CCPAURQY.cpy} L28 and the column
 * {@code MERCHANT_CATAGORY_CODE} at {@code ddl/AUTHFRDS.ddl} L14 -- so the misspelling is its NAME
 * and every target-side surface spells it {@code merchantCategoryCode}.
 *
 * <p>Assumptions: the document's naming convention is camel-cased REFERENCE field names with those
 * three permitted corrections applied, which is why {@code cardNum} rather than {@code cardNumber}
 * appears beside {@code merchantCategoryCode} rather than {@code merchantCatagoryCode}. That
 * mixture looks like an oversight and is not one, so the differences are enumerated below and
 * asserted to be exactly the enumerated ones. A reader "restoring consistency" by re-misspelling
 * the corrected property, or by abbreviating a new one, meets a failing build rather than a
 * review comment.
 *
 * <p>Assumptions: the two queue payload schemas are referenced by no path in the document, and that
 * is deliberate rather than an omission. The normative form of both is delimited text -- eighteen
 * comma-separated values inbound and six outbound -- so the schemas name and bound the fields to
 * give one reading of the layout, and the JSON envelope they describe is offered additively for new
 * consumers. Because no field name travels on the delimited wire, a property name in these
 * schemas is this migration's to choose, which is what makes the correction above possible at all.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries
 * no parameter, return or exception at-clause.
 */
class AuthorizationWireContractTest {

    /**
     * The classpath location of the published contract.
     *
     * <p>Assumptions: the resource is read from the classpath rather than from a
     * repository-relative path so that the test asserts against the document that is PACKAGED
     * with the service. A path relative to the working directory would keep passing after the
     * contract stopped being copied into the artifact, which is the one failure that matters to a
     * consumer.</p>
     */
    private static final String CONTRACT_RESOURCE = "/openapi/authorization-api.yaml";

    /**
     * The misspelling the reference carries and no published property name may.
     */
    private static final String BASELINE_MISSPELLING = "catagory";

    /**
     * The corrected spelling every target-side surface uses for the merchant category code.
     */
    private static final String CORRECTED_PROPERTY = "merchantCategoryCode";

    /**
     * The zero-based ordinal the merchant category code occupies on the delimited request wire.
     *
     * <p>Assumptions: the ordinal is what the wire agreement fixes, since the delimited form is
     * read by position at {@code cbl/COPAUA0C.cbl} L354-L374 and transmits no field name.
     * Asserting the ordinal alongside the spelling is therefore asserting the part of the
     * contract a producer can actually observe.</p>
     */
    private static final int MERCHANT_CATEGORY_ORDINAL = 9;

    /**
     * Published request property against the record component that realises it, in wire order.
     *
     * <p>Assumptions: the table is ORDERED and is asserted against both sides in order, because
     * the order is itself the wire contract. A table keyed as an unordered set would still pass
     * after two fields were transposed, which is precisely the change a positional consumer
     * cannot survive.</p>
     *
     * @return the eighteen published request property names against the record components that
     *     realise them, in wire order; never {@code null}
     */
    private static Map<String, String> requestPropertyToComponent() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("authDate", "authDate");
        table.put("authTime", "authTime");
        table.put("cardNum", "cardNumber");
        table.put("authType", "authType");
        table.put("cardExpiryDate", "cardExpiryDate");
        table.put("messageType", "messageType");
        table.put("messageSource", "messageSource");
        table.put("processingCode", "processingCode");
        table.put("transactionAmt", "transactionAmount");
        table.put(CORRECTED_PROPERTY, CORRECTED_PROPERTY);
        table.put("acqrCountryCode", "acquirerCountryCode");
        table.put("posEntryMode", "posEntryMode");
        table.put("merchantId", "merchantId");
        table.put("merchantName", "merchantName");
        table.put("merchantCity", "merchantCity");
        table.put("merchantState", "merchantState");
        table.put("merchantZip", "merchantZip");
        table.put("transactionId", "transactionId");
        return table;
    }

    /**
     * Published reply property against the record component that realises it, in wire order.
     *
     * @return the six published reply property names against the record components that realise
     *     them, in wire order; never {@code null}
     */
    private static Map<String, String> replyPropertyToComponent() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("cardNum", "cardNumber");
        table.put("transactionId", "transactionId");
        table.put("authIdCode", "authIdCode");
        table.put("authRespCode", "authResponseCode");
        table.put("authRespReason", "authResponseReason");
        table.put("approvedAmt", "approvedAmount");
        return table;
    }

    /**
     * The published property names that deliberately abbreviate their record component.
     *
     * <p>Assumptions: this is a CLOSED set, and closing it is the point. Each entry is a
     * camel-cased reference field name that the migration chose not to expand, and admitting
     * exactly these means a NEW divergence -- a property abbreviated where its sibling is not, or
     * the corrected spelling reverted -- fails rather than blending in with the four that are
     * intended.</p>
     */
    private static final Set<String> DELIBERATE_ABBREVIATIONS =
            Set.of("cardNum", "transactionAmt", "acqrCountryCode", "authRespCode",
                    "authRespReason", "approvedAmt");

    /**
     * Reads and parses the published contract from the classpath.
     *
     * @return the whole document as nested maps and lists; never {@code null}
     * @throws IllegalStateException if the resource is absent, which would mean the module ships no
     *     contract at all, or if it cannot be read or parsed as a mapping
     */
    private static Map<String, Object> loadContract() {
        try (InputStream resource =
                AuthorizationWireContractTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
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
        } catch (IOException failure) {
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
     * @throws IllegalStateException if the key is absent or does not hold a mapping, so a
     *     structural
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
     * Returns every schema the contract publishes, keyed by schema name.
     *
     * @return the component schema section of the document
     */
    private static Map<String, Object> schemas() {
        return mapping(mapping(loadContract(), "components"), "schemas");
    }

    /**
     * Returns the property names one named schema declares, in declaration order.
     *
     * @param schemaName the schema to read
     * @return the property names in order, or an empty list when the schema declares no properties
     */
    private static List<String> propertyNames(String schemaName) {
        Map<String, Object> schema = mapping(schemas(), schemaName);
        if (!(schema.get("properties") instanceof Map)) {
            return List.of();
        }
        return List.copyOf(mapping(schema, "properties").keySet());
    }

    /**
     * Returns the record component names of a record type, in declaration order.
     *
     * @param recordType the record to reflect over; must be a record type
     * @return the component names in declaration order
     */
    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Verifies that no published property name anywhere in the contract carries the misspelling.
     */
    @Test
    @DisplayName("no published property name carries the baseline misspelling")
    void noPublishedPropertyNameCarriesTheBaselineMisspelling() {
        // WHY : Assumptions: the sweep is over EVERY schema rather than over the two the finding
        //       named, because the misspelling reached the document once already and the mechanism
        //       that let it -- a reference name copied into a property name unexamined -- is
        //       available at every schema. A targeted assertion would have to be extended by
        //       whoever adds the next schema, which is the reader least likely to know it exists.
        Map<String, Object> allSchemas = schemas();

        for (Map.Entry<String, Object> entry : allSchemas.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            for (String property : propertyNames(entry.getKey())) {
                assertThat(property.toLowerCase(Locale.ROOT))
                        .as("schema %s declares property %s", entry.getKey(), property)
                        .doesNotContain(BASELINE_MISSPELLING);
            }
        }
    }

    /**
     * Verifies that the request payload schema maps one for one onto the request record, in order.
     */
    @Test
    @DisplayName("the queue request schema maps one for one onto the request record")
    void theQueueRequestSchemaMapsOneForOneOntoTheRequestRecord() {
        Map<String, String> table = requestPropertyToComponent();

        assertThat(propertyNames("AuthorizationRequestMessage"))
                .as("the published property names, in wire order")
                .containsExactlyElementsOf(table.keySet());
        assertThat(componentNames(AuthorizationRequestPayload.class))
                .as("the record components, in wire order")
                .containsExactlyElementsOf(table.values());

        // WHY : Assumptions: the map is asserted INJECTIVE as well as total. A table that mapped
        //       two published properties onto one component would satisfy both list comparisons
        //       above while describing a document that cannot round-trip, and the failure would
        //       surface as a lost field rather than as a naming error.
        assertThat(table.values()).doesNotHaveDuplicates();
    }

    /**
     * Verifies that the reply payload schema maps one for one onto the reply record, in order.
     */
    @Test
    @DisplayName("the queue reply schema maps one for one onto the reply record")
    void theQueueReplySchemaMapsOneForOneOntoTheReplyRecord() {
        Map<String, String> table = replyPropertyToComponent();

        assertThat(propertyNames("AuthorizationReplyMessage"))
                .containsExactlyElementsOf(table.keySet());
        assertThat(componentNames(AuthorizationReplyPayload.class))
                .containsExactlyElementsOf(table.values());
        assertThat(table.values()).doesNotHaveDuplicates();
    }

    /**
     * Verifies that the request schema requires all eighteen fields and admits no nineteenth.
     */
    @Test
    @DisplayName("the request schema requires all eighteen fields and admits no nineteenth")
    void theRequestSchemaRequiresAllEighteenFieldsAndAdmitsNoNineteenth() {
        Map<String, Object> schema = mapping(schemas(), "AuthorizationRequestMessage");

        // WHY : Assumptions: every field is required and none is optional, which follows from the
        //       wire rather than from a preference. A positional payload has no way to omit a
        //       field: an absent value is still a delimiter position, so a producer that leaves
        //       one out shifts every field after it. Optionality would therefore describe
        //       something the wire cannot express.
        assertThat(schema.get("required"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactlyElementsOf(requestPropertyToComponent().keySet());
        assertThat(schema.get("additionalProperties"))
                .as("a nineteenth property would shift no position but would describe a field the "
                        + "reference consumer cannot parse")
                .isEqualTo(Boolean.FALSE);
    }

    /**
     * Verifies that the corrected spelling sits at the ordinal the delimited wire fixes.
     */
    @Test
    @DisplayName("the corrected spelling sits at the wire ordinal on both sides")
    void theCorrectedSpellingSitsAtTheWireOrdinalOnBothSides() {
        // WHY : Assumptions: spelling and position are asserted TOGETHER because correcting one
        //       while moving the other would be a worse outcome than the defect. The rename is
        //       safe only because it changes no byte of the delimited form, and it changes no
        //       byte only while the value stays at ordinal ten.
        assertThat(propertyNames("AuthorizationRequestMessage").get(MERCHANT_CATEGORY_ORDINAL))
                .isEqualTo(CORRECTED_PROPERTY);
        assertThat(componentNames(AuthorizationRequestPayload.class).get(MERCHANT_CATEGORY_ORDINAL))
                .isEqualTo(CORRECTED_PROPERTY);
    }

    /**
     * Verifies that the detail resource publishes the corrected spelling the Java shapes declare.
     */
    @Test
    @DisplayName("the detail resource publishes the corrected spelling")
    void theDetailResourcePublishesTheCorrectedSpelling() {
        // WHY : Assumptions: the detail schema is asserted by targeted property rather than by
        //       whole-set equality against a record. It is the response body of the detail path,
        //       and the screen shaped transfer object behind that path carries considerably more
        //       members than the schema does -- title and timestamp members a screen needs and a
        //       resource does not -- so a set comparison would assert a correspondence that was
        //       never intended and would fail for reasons unrelated to this contract.
        assertThat(propertyNames("PendingAuthDetail")).contains(CORRECTED_PROPERTY);
        assertThat(componentNames(PendingAuthDetailResponse.class)).contains(CORRECTED_PROPERTY);
    }

    /**
     * Verifies that only the enumerated published property names abbreviate their record component.
     */
    @Test
    @DisplayName("only the enumerated property names abbreviate their component")
    void onlyTheEnumeratedPropertyNamesAbbreviateTheirComponent() {
        // WHY : Trade-offs: enumerating the abbreviations costs a list that has to be maintained,
        //       and the alternative costs more. Without it the document's convention is unstated,
        //       so the corrected property looks like the inconsistent one and the obvious tidy-up
        //       is to re-misspell it. With it, the tidy-up fails the build and the reader is sent
        //       to the paragraph that explains why the mixture is deliberate.
        Map<String, String> everyMapping = new LinkedHashMap<>(requestPropertyToComponent());
        replyPropertyToComponent().forEach(everyMapping::putIfAbsent);

        for (Map.Entry<String, String> entry : everyMapping.entrySet()) {
            if (entry.getKey().equals(entry.getValue())) {
                continue;
            }
            assertThat(DELIBERATE_ABBREVIATIONS)
                    .as("property %s differs from component %s and must be an enumerated "
                            + "abbreviation", entry.getKey(), entry.getValue())
                    .contains(entry.getKey());
        }

        assertThat(DELIBERATE_ABBREVIATIONS)
                .as("every enumerated abbreviation must still be used by the document")
                .allSatisfy(abbreviation -> assertThat(everyMapping).containsKey(abbreviation));
    }
}
