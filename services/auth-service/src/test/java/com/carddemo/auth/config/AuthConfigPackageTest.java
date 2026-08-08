package com.carddemo.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the two configuration types authored to close this package's roster to what they claim: that the
 * published document metadata agrees with the committed contract member for member, and that the schema
 * pin is verified rather than merely configured.
 *
 * <p>Refactoring Rationale: this class exists because the package charter declared a CLOSED SET OF THREE
 * configuration types while the directory held one, and carried no target-versus-census disclaimer to
 * mark the difference. {@link OpenApiConfig} and {@link DataSourceConfig} were authored to close that
 * gap; these assertions are what stop either of them being a file that compiles and claims something
 * untrue. A roster closed by two files nobody checks is not meaningfully different from a roster closed
 * by nothing.</p>
 *
 * <p>Assumptions: the metadata assertions compare the bean's values against the COMMITTED CONTRACT read
 * off the class path, not against literals restated here. Restating them would assert only that this
 * test and that class were written by the same hand on the same day; comparing them against the contract
 * asserts the property that matters, which is that the two documents agree.</p>
 *
 * <p>Alternatives Considered: standing up an application context and reading the generated document from
 * the running service. Rejected because the generated document's paths come from request handlers this
 * module has not authored yet, so a context-based assertion could only cover the same members these
 * direct assertions cover while adding a container start-up to every run.</p>
 */
class AuthConfigPackageTest {

    /** The committed contract of record, read off the test class path. */
    private static final String CONTRACT_RESOURCE = "/openapi/auth-api.yaml";

    /** The base profile, packaged from {@code src/main/resources}. */
    private static final String BASE_PROFILE = "/application.yml";

    /** The class-path location that must be served for the committed contract to be reachable. */
    private static final String OPENAPI_LOCATION = "classpath:/openapi/";

    /** The schema this context owns, and the only one its unqualified statements may resolve in. */
    private static final String OWNED_SCHEMA = "auth";

    /**
     * Confirms the published document declares the specification version its members come from.
     *
     * <p>Assumptions: the specification FLAG and the version STRING are asserted separately, because
     * they are set separately, both default to a 3.0 value, and only the string survives the clone the
     * publishing library assembles the served document through. An assertion comparing the two to each
     * other would therefore pass on a document that declared 3.0 twice.</p>
     *
     * <p>Refactoring Rationale: this case exists because the document was constructed without either,
     * while carrying two members -- the summary and the licence identifier compared below -- that exist
     * only in 3.1. Nothing failed, because the configured serialiser emits both regardless; but the
     * served document declared 3.0.1 where the committed contract declares 3.1.1, and no assertion
     * compared the two. The mechanism behind the difference is measured in account-service's
     * config/OpenApiDocumentTest.java against the same library.</p>
     */
    @Test
    @DisplayName("the published document declares the specification version its members come from")
    void publishedDocumentDeclaresTheSpecificationVersionItsMembersComeFrom() {
        OpenAPI published = new OpenApiConfig().authServiceOpenApi();

        assertThat(published.getSpecVersion())
                .as("a 3.1 document must carry the 3.1 flag, not the model's 3.0 default")
                .isEqualTo(SpecVersion.V31);
        assertThat(published.getOpenapi())
                .as("the emitted version string must equal the committed contract's")
                .isEqualTo(String.valueOf(contract().get("openapi")));
        assertThat(published.getOpenapi()).startsWith("3.1.");
        assertThat(published.getInfo().getSummary()).isNotBlank();
        assertThat(published.getInfo().getLicense().getIdentifier()).isNotBlank();
    }

    /**
     * Confirms the published metadata agrees with the committed contract's information block.
     *
     * <p>Assumptions: the title, version, summary and both license members are compared, because those
     * are the members {@link OpenApiConfig} restates. The description is compared as well, in normalised
     * form, because a Java text block and a YAML folded scalar reach the same sentence through different
     * line-joining rules and comparing them literally would fail on whitespace neither document has an
     * opinion about.</p>
     */
    @Test
    @DisplayName("the published information block agrees with the committed contract")
    void publishedInformationBlockAgreesWithTheCommittedContract() {
        OpenAPI published = new OpenApiConfig().authServiceOpenApi();
        Map<String, Object> info = mapping(contract(), "info");

        assertThat(published.getInfo().getTitle()).isEqualTo(info.get("title"));
        assertThat(published.getInfo().getVersion()).isEqualTo(String.valueOf(info.get("version")));
        assertThat(published.getInfo().getSummary()).isEqualTo(info.get("summary"));
        assertThat(normalise(published.getInfo().getDescription()))
                .isEqualTo(normalise(String.valueOf(info.get("description"))));

        Map<String, Object> license = mapping(info, "license");
        assertThat(published.getInfo().getLicense().getName()).isEqualTo(license.get("name"));
        assertThat(published.getInfo().getLicense().getIdentifier())
                .isEqualTo(license.get("identifier"));
    }

    /**
     * Confirms the published server entry and its one variable agree with the committed contract.
     *
     * <p>Assumptions: the variable's DEFAULT is compared as well as its name, because that default is
     * the mechanism by which no deployment address reaches source control. A default silently replaced
     * by a plausible hostname would still satisfy an assertion that only checked the templating.</p>
     */
    @Test
    @DisplayName("the published server entry and its origin variable agree with the committed contract")
    void publishedServerEntryAgreesWithTheCommittedContract() {
        OpenAPI published = new OpenApiConfig().authServiceOpenApi();
        Map<String, Object> server = asMapping(list(contract(), "servers").get(0));

        assertThat(published.getServers()).hasSize(1);
        assertThat(published.getServers().get(0).getUrl()).isEqualTo(server.get("url"));

        Map<String, Object> contractVariables = mapping(server, "variables");
        assertThat(published.getServers().get(0).getVariables().keySet())
                .isEqualTo(contractVariables.keySet());

        String name = contractVariables.keySet().iterator().next();
        assertThat(published.getServers().get(0).getVariables().get(name).getDefault())
                .as("the unresolvable placeholder is what keeps a deployment address out of source")
                .isEqualTo(mapping(contractVariables, name).get("default"))
                .asString()
                .endsWith(".invalid");
    }

    /**
     * Confirms the published security requirement and scheme agree with the committed contract.
     *
     * <p>Assumptions: the requirement is asserted at the DOCUMENT level, matching the contract. Stating
     * it document-wide means an operation added without one inherits it, whereas per-operation
     * declaration makes a forgotten requirement an unauthenticated endpoint that reads like every other
     * line in a diff.</p>
     */
    @Test
    @DisplayName("the published security requirement and scheme agree with the committed contract")
    void publishedSecuritySchemeAgreesWithTheCommittedContract() {
        OpenAPI published = new OpenApiConfig().authServiceOpenApi();
        Map<String, Object> schemes = mapping(mapping(contract(), "components"), "securitySchemes");
        String name = schemes.keySet().iterator().next();
        Map<String, Object> scheme = mapping(schemes, name);

        assertThat(published.getSecurity()).hasSize(1);
        assertThat(published.getSecurity().get(0)).containsKey(name);
        assertThat(published.getComponents().getSecuritySchemes()).containsOnlyKeys(name);

        var publishedScheme = published.getComponents().getSecuritySchemes().get(name);
        assertThat(publishedScheme.getType().toString()).isEqualTo(scheme.get("type"));
        assertThat(publishedScheme.getScheme()).isEqualTo(scheme.get("scheme"));
        assertThat(publishedScheme.getBearerFormat()).isEqualTo(scheme.get("bearerFormat"));
        assertThat(normalise(publishedScheme.getDescription()))
                .isEqualTo(normalise(String.valueOf(scheme.get("description"))));
    }

    /**
     * Confirms the committed contract is reachable rather than merely packaged.
     *
     * <p>Assumptions: the four framework defaults are asserted alongside the openapi location, because
     * the static-locations key REPLACES the default list rather than extending it. This is the assertion
     * that would have caught the contract being served by nothing, which was the state of this module
     * before {@link OpenApiConfig} was authored.</p>
     */
    @Test
    @DisplayName("the committed contract is served, and the four framework defaults are preserved")
    void committedContractIsServedAndDefaultsArePreserved() {
        List<Object> locations =
                list(mapping(mapping(mapping(profile(), "spring"), "web"), "resources"),
                        "static-locations");

        assertThat(locations)
                .as("the committed contract is unreachable unless this location is served")
                .contains(OPENAPI_LOCATION);
        assertThat(locations)
                .as("this key replaces the defaults, so all four must be restated")
                .contains("classpath:/META-INF/resources/", "classpath:/resources/",
                        "classpath:/static/", "classpath:/public/");
    }

    /**
     * Confirms the browsable view names a document that is genuinely packaged under the served location.
     *
     * <p>Assumptions: the named document is OPENED rather than compared as a string, so this asserts
     * reachability and not agreement between two spellings. A URL naming a file that is not packaged
     * would satisfy any assertion that only compared names.</p>
     */
    @Test
    @DisplayName("the browsable view names a packaged document and hides the bundled sample")
    void browsableViewNamesAPackagedDocumentAndHidesTheBundledSample() {
        Map<String, Object> swaggerUi = mapping(mapping(profile(), "springdoc"), "swagger-ui");

        assertThat(String.valueOf(swaggerUi.get("url"))).isEqualTo("/auth-api.yaml");
        assertThat(contract())
                .as("the named document must be packaged, not merely named")
                .isNotEmpty();
        assertThat(swaggerUi.get("disable-swagger-default-url"))
                .as("the bundled sample must not share the selector with the contract of record")
                .isEqualTo(true);
        assertThat(String.valueOf(mapping(mapping(profile(), "springdoc"), "api-docs").get("version")))
                .as("a 3.0 generator beside a 3.1 contract reports drift that is not drift")
                .isEqualTo("openapi_3_1");
    }

    /**
     * Confirms the two configuration paths that select a schema agree with each other.
     *
     * <p>Assumptions: the connection initialisation statement and the migration default schema are set
     * independently -- one governs where runtime statements resolve, the other where migrations are
     * applied -- so comparing them is what proves they agree. A disagreement is the condition in which
     * migrations land in one schema while the service reads another, and neither key alone can reveal
     * it.</p>
     */
    @Test
    @DisplayName("the runtime search path and the migration default schema name the same schema")
    void runtimeSearchPathAndMigrationDefaultSchemaAgree() {
        Object initSql = mapping(mapping(mapping(profile(), "spring"), "datasource"), "hikari")
                .get("connection-init-sql");
        Object defaultSchema = mapping(mapping(profile(), "spring"), "flyway").get("default-schema");

        assertThat(String.valueOf(initSql)).isEqualTo("SET search_path TO " + OWNED_SCHEMA);
        assertThat(defaultSchema).isEqualTo(OWNED_SCHEMA);
    }

    /**
     * Confirms a connection resolving the owned schema is accepted.
     * @throws SQLException never in practice; declared because the stubbing helper declares it
     */
    @Test
    @DisplayName("a connection resolving the owned schema is accepted")
    void connectionResolvingTheOwnedSchemaIsAccepted() throws SQLException {
        assertThat(DataSourceConfig.verifyEffectiveSchema(stubResolving(OWNED_SCHEMA), OWNED_SCHEMA))
                .isEqualTo(OWNED_SCHEMA);
    }

    /**
     * Confirms a connection resolving a different schema stops the service.
     *
     * <p>Assumptions: the message is asserted to name BOTH schemas, because an operator reading it needs
     * to know which one was expected and which one resolved. A message naming only one leaves the fault
     * ambiguous between a wrong pin and a missing schema.</p>
     * @throws SQLException never in practice; declared because the stubbing helper declares it
     */
    @Test
    @DisplayName("a connection resolving a different schema stops the service and names both")
    void connectionResolvingADifferentSchemaStopsTheService() throws SQLException {
        DataSource wrong = stubResolving("ledger");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> DataSourceConfig.verifyEffectiveSchema(wrong, OWNED_SCHEMA))
                .withMessageContaining(OWNED_SCHEMA)
                .withMessageContaining("ledger");
    }

    /**
     * Confirms a search path that resolves to nothing is refused rather than dereferenced.
     *
     * <p>Assumptions: this is the case the verification exists for. PostgreSQL accepts a search path
     * naming a schema that does not exist, and {@code current_schema()} then returns NULL, so a null
     * first column must reach the comparison rather than raise a null pointer -- an unhandled null here
     * would replace a clear configuration message with a stack trace.</p>
     * @throws SQLException never in practice; declared because the stubbing helper declares it
     */
    @Test
    @DisplayName("a search path resolving to nothing is refused, not dereferenced")
    void searchPathResolvingToNothingIsRefused() throws SQLException {
        DataSource nothing = stubResolving(null);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> DataSourceConfig.verifyEffectiveSchema(nothing, OWNED_SCHEMA))
                .withMessageContaining(OWNED_SCHEMA);
    }

    /**
     * Confirms a blank expected schema is refused before any connection is taken.
     *
     * <p>Assumptions: refusing the blank case is what stops the verification passing vacuously. An empty
     * expected value compared against an empty result would agree, and the pin would then be unverified
     * while the start-up check reported success.</p>
     */
    @Test
    @DisplayName("a blank expected schema is refused")
    void blankExpectedSchemaIsRefused() {
        DataSource unused = mock(DataSource.class);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> DataSourceConfig.verifyEffectiveSchema(unused, "  "))
                .withMessageContaining("spring.flyway.default-schema");
    }

    /**
     * Confirms a driver failure while resolving the schema stops the service with its cause attached.
     * @throws SQLException never in practice; declared because the stubbing helper declares it
     */
    @Test
    @DisplayName("a driver failure while resolving the schema stops the service with its cause")
    void driverFailureStopsTheServiceWithItsCause() throws SQLException {
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new SQLException("connection refused"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> DataSourceConfig.verifyEffectiveSchema(failing, OWNED_SCHEMA))
                .withCauseInstanceOf(SQLException.class);
    }

    /**
     * Builds a data source whose single query resolves the given schema name.
     *
     * @param resolved the schema name the query should report, or {@code null} to model a search path
     *     naming only schemas that do not exist
     * @return a stubbed data source, never {@code null}
     * @throws SQLException never in practice; declared because the stubbed methods declare it
     */
    private static DataSource stubResolving(String resolved) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn(resolved);

        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenReturn(result);

        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(statement);

        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }

    /**
     * Collapses runs of whitespace so a Java text block and a YAML folded scalar compare equal.
     *
     * @param text the text to normalise; must not be {@code null}
     * @return the text with every whitespace run reduced to one blank and the ends trimmed
     */
    private static String normalise(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Reads and parses the committed contract.
     *
     * @return the parsed contract, never {@code null}
     */
    private static Map<String, Object> contract() {
        return document(CONTRACT_RESOURCE);
    }

    /**
     * Reads and parses the packaged base profile.
     *
     * @return the parsed profile, never {@code null}
     */
    private static Map<String, Object> profile() {
        return document(BASE_PROFILE);
    }

    /**
     * Reads and parses one YAML document off the test class path.
     *
     * @param resource the class-path resource to read
     * @return the parsed document, never {@code null}
     * @throws IllegalStateException if the resource is absent or is not a mapping, either of which would
     *     mean the assertion using it was silently testing nothing
     */
    private static Map<String, Object> document(String resource) {
        try (InputStream stream = AuthConfigPackageTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource + " is not on the test class path");
            }
            return asMapping(new Yaml().load(stream));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(resource + " could not be read", failure);
        }
    }

    /**
     * Reads one required nested mapping.
     *
     * @param parent the mapping to read from; must not be {@code null}
     * @param key the key to read
     * @return the mapping at {@code key}, never {@code null}
     * @throws IllegalStateException if the key is absent or does not hold a mapping
     */
    private static Map<String, Object> mapping(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("no mapping at " + key);
        }
        return asMapping(value);
    }

    /**
     * Reads one required nested list.
     *
     * @param parent the mapping to read from; must not be {@code null}
     * @param key the key to read
     * @return the list at {@code key}, never {@code null}
     * @throws IllegalStateException if the key is absent or does not hold a list
     */
    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof List<?>)) {
            throw new IllegalStateException("no list at " + key);
        }
        return List.copyOf((List<Object>) value);
    }

    /**
     * Narrows a parsed YAML node to a mapping.
     *
     * @param node the node to narrow
     * @return the node as a mapping, never {@code null}
     * @throws IllegalStateException if the node is not a mapping
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMapping(Object node) {
        if (!(node instanceof Map<?, ?>)) {
            throw new IllegalStateException("expected a mapping but found " + node);
        }
        return (Map<String, Object>) node;
    }
}
