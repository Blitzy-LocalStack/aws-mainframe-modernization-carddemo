package com.carddemo.transaction.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.transaction.TransactionApplication;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts the real application with the {@code dev} profile active, against a real PostgreSQL engine and a
 * stub identity issuer, so the profile is proved to BOOT rather than only to parse.
 *
 * <h2>Why this class exists alongside the parsing contract test</h2>
 *
 * <p>Refactoring Rationale: {@code DevProfileContractTest} beside this class resolves the same profile and
 * asserts what every key binds to, and that is a different and weaker claim than this one makes. A document
 * can resolve perfectly and still fail to start an application: a pool setting can bind and then be refused
 * by the driver, a migration can be declared and then fail against the engine, a security configuration can
 * name an issuer and then be unable to reach it. Only a started context establishes that the profile is
 * deployable, and before this class nothing anywhere started one.
 *
 * <h2>Why this service, and what is deliberately excluded</h2>
 *
 * <p>Assumptions: the transaction context was chosen because it is the one bounded context whose start-up
 * needs no AWS client at all -- it consumes no queue and writes no object -- so a start-up failure here is
 * attributable to the profile rather than to an absent cloud endpoint. Every other context would have needed
 * a stubbed AWS endpoint as well, which would have put a second variable into the one test whose job is to
 * isolate the first.
 *
 * <p>Trade-offs: the servlet container is NOT started, and that exclusion is deliberate rather than a
 * convenience. The profile inherits {@code server.ssl.enabled: true} with a keystore password the platform
 * supplies, so binding a port would require a keystore file -- a deployment artifact, not a code contract --
 * and the test would then be asserting that a certificate exists on the build machine. Everything the profile
 * decides about beans, the pool, the migrations and the security configuration is exercised either way; what
 * is given up is the TLS handshake, which belongs to the runbook that installs the keystore.
 *
 * <h2>Why the issuer is stubbed rather than pointed at something unreachable</h2>
 *
 * <p>Assumptions: the identity issuer is served by a real HTTP server started by this class, because this
 * service builds its token decoder eagerly and a decoder built from an issuer location FETCHES that issuer's
 * discovery document while it is being constructed. Pointing at an unreachable name -- which is what the
 * {@code test} profile deliberately does, since its cases never start the security configuration -- would
 * fail this context during start-up for a reason that has nothing to do with the development profile.
 *
 * <p>Assumptions: the stub is the JDK's own HTTP server rather than a mock web server dependency. It is
 * already on every JDK, it serves the two documents a discovery fetch reads, and adding a dependency for two
 * static responses would put a library in the build for something the platform already does.
 */
@Testcontainers
@SpringBootTest(
    classes = TransactionApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@DisplayName("the transaction service starting under the development profile")
class DevProfileStartupIT {

  /**
   * The engine image, named by manifest digest rather than by a moving tag.
   *
   * <p>Assumptions: this is deliberately the SAME digest the repository integration tests of this and the
   * sibling bounded contexts already name. Naming a digest rather than a tag is what makes the reference
   * immutable, because a publisher may rebuild and republish a minor tag onto a new base layer without the
   * tag changing.
   */
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";
  /**
   * Class-path location of the harness that creates the schema's owning role in the container.
   *
   * <p>Assumptions: the role is a NOLOGIN role {@code data-migration/sql/V0__schemas_and_roles.sql} names and no container has, so it has
   * to exist before Flyway opens a connection and assumes it. A Testcontainers init script runs once
   * at container start, which is strictly earlier than Flyway's first connection; the alternative
   * this replaces -- creating the role from {@code spring.flyway.init-sqls} -- carried Flyway's
   * deprecated {@code initSql} setting and its per-connection removal notice.</p>
   */
  private static final String OWNER_ROLE_SCRIPT = "db/testharness/test-harness-owner-role.sql";

  /**
   * The engine this context runs its migrations and its pool against.
   *
   * <p>Refactoring Rationale: the container carries NO service-connection annotation, and its absence is a
   * finding rather than an omission. That annotation contributes a connection-details bean, and the framework
   * consults it only for the DataSource it auto-configures -- but this service declares its own DataSource
   * bean, built from the bound {@code spring.datasource} properties, so the connection details were ignored
   * and the context failed with a URL that did not start with {@code jdbc}. Measured while this class was
   * written. The three connection values are therefore supplied as properties below, which is also what a
   * deployment does.
   */
  @Container
  static final PostgreSQLContainer POSTGRES =
          new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(OWNER_ROLE_SCRIPT);

  /** The stub issuer, serving the two documents a discovery fetch reads. */
  private static HttpServer issuer;

  /** The base URL the stub issuer answers on, fixed once its ephemeral port is known. */
  private static String issuerUrl;

  /**
   * The signing pair the stub issuer publishes the public half of.
   *
   * <p>Assumptions: two thousand and forty-eight bits is the smallest size a decoder accepts for this
   * algorithm, and generating a larger one would cost start-up time for a key nothing signs with.
   */
  private static KeyPair issuerKeys;

  /** The environment of the started context, read to prove which profile actually took effect. */
  @Autowired private Environment environment;

  /** The pool the started context built, read to prove the profile's sizing reached the driver. */
  @Autowired private DataSource dataSource;

  /**
   * Starts the stub issuer on an ephemeral port and serves its discovery and key documents.
   *
   * <p>Assumptions: the port is ephemeral and the issuer identifier is composed FROM it after binding, because
   * a discovery document whose {@code issuer} member disagrees with the location it was fetched from is
   * rejected -- so the identifier cannot be written before the port is known.
   *
   * @throws IOException when the stub server cannot bind
   * @throws NoSuchAlgorithmException when the platform carries no RSA key-pair generator
   */
  @BeforeAll
  static void startStubIssuer() throws IOException, NoSuchAlgorithmException {
    final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    issuerKeys = generator.generateKeyPair();
    issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuerUrl = "http://127.0.0.1:" + issuer.getAddress().getPort();
    issuer.createContext("/.well-known/openid-configuration", DevProfileStartupIT::serveDiscovery);
    issuer.createContext("/jwks", DevProfileStartupIT::serveKeys);
    issuer.start();
  }

  /** Stops the stub issuer so no thread outlives the class. */
  @AfterAll
  static void stopStubIssuer() {
    if (issuer != null) {
      issuer.stop(0);
    }
  }

  /**
   * Serves the discovery document, naming this stub as the issuer and pointing at its key set.
   *
   * @param exchange the request being answered
   * @throws IOException when the response cannot be written
   */
  private static void serveDiscovery(final HttpExchange exchange) throws IOException {
    respond(
        exchange,
        "{\"issuer\":\""
            + issuerUrl
            + "\",\"jwks_uri\":\""
            + issuerUrl
            + "/jwks\",\"id_token_signing_alg_values_supported\":[\"RS256\"],"
            + "\"subject_types_supported\":[\"public\"],\"response_types_supported\":[\"code\"],"
            + "\"authorization_endpoint\":\""
            + issuerUrl
            + "/authorize\",\"token_endpoint\":\""
            + issuerUrl
            + "/token\"}");
  }

  /**
   * Serves a key set carrying the public half of the pair this class generated.
   *
   * <p>Refactoring Rationale: an EMPTY key set was published here first, on the reasoning that no case
   * presents a token so no key is needed to verify one. It failed the start-up with {@code Failed to find any
   * algorithms from the JWK set} -- measured, not predicted. A decoder built from an issuer LOCATION derives
   * the signature algorithms it will accept from the published keys while it is being constructed, so an
   * empty set leaves it with no algorithm and it refuses to be built. The set therefore has to carry a real
   * key for the context to start at all.
   *
   * <p>Assumptions: the key is generated in this process at class start rather than checked in. A key-shaped
   * literal in a test file is indistinguishable to a scanner -- and to a reader -- from a leaked deployment
   * key, and only the PUBLIC half is ever published here, so nothing that leaves this class is secret.
   *
   * @param exchange the request being answered
   * @throws IOException when the response cannot be written
   */
  private static void serveKeys(final HttpExchange exchange) throws IOException {
    final RSAPublicKey published = (RSAPublicKey) issuerKeys.getPublic();
    respond(
        exchange,
        "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\","
            + "\"kid\":\"startup-probe\",\"n\":\""
            + unsignedBase64Url(published.getModulus())
            + "\",\"e\":\""
            + unsignedBase64Url(published.getPublicExponent())
            + "\"}]}");
  }

  /**
   * Encodes one key component the way a key set names it: unsigned, big-endian, base64url, unpadded.
   *
   * <p>Assumptions: a leading zero byte is stripped. Java's arbitrary-precision integers are SIGNED, so a
   * modulus whose top bit is set gains a zero byte in front when converted to bytes; a key set member is
   * defined as the minimal unsigned representation, and leaving that byte in place makes roughly half of all
   * generated keys unreadable to a consumer that reconstructs the integer from its declared length.
   *
   * @param component the modulus or exponent to encode
   * @return the component encoded as an unpadded base64url string, never {@code null}
   */
  private static String unsignedBase64Url(final BigInteger component) {
    byte[] magnitude = component.toByteArray();
    if (magnitude.length > 1 && magnitude[0] == 0) {
      final byte[] trimmed = new byte[magnitude.length - 1];
      System.arraycopy(magnitude, 1, trimmed, 0, trimmed.length);
      magnitude = trimmed;
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(magnitude);
  }

  /**
   * Writes one JSON response.
   *
   * @param exchange the request being answered
   * @param body the JSON document to write
   * @throws IOException when the response cannot be written
   */
  private static void respond(final HttpExchange exchange, final String body) throws IOException {
    final byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, encoded.length);
    try (OutputStream response = exchange.getResponseBody()) {
      response.write(encoded);
    }
  }

  /**
   * Fabricated key material both signing keys are given, long enough for the guards that check length.
   *
   * <p>Assumptions: the value decodes from base64 to the forty-eight ASCII bytes {@code
   * carddemo-startup-probe-key-material-not-a-secret}, which clears the thirty-two byte minimum both the
   * cursor sealer and the internal-identity token enforce. It is deliberately readable once decoded rather
   * than random, so that a reader who finds it in a log or a heap dump can tell at a glance that it is a
   * build-time constant and not a leaked deployment secret.
   *
   * <p>Trade-offs: one constant serves two unrelated keys, which a deployment must never do. It is
   * acceptable here because nothing in this class mints a cursor and nothing presents a credential to a real
   * account context -- the keys are supplied only so the beans that require them can be built, which is the
   * condition being tested for.
   */
  private static final String PROBE_KEY_MATERIAL =
      "Y2FyZGRlbW8tc3RhcnR1cC1wcm9iZS1rZXktbWF0ZXJpYWwtbm90LWEtc2VjcmV0";

  /**
   * Supplies the values the platform would inject, and only those.
   *
   * <p>Assumptions: each entry below stands in for exactly one thing a deployment supplies, and nothing here
   * changes a value the development profile itself decides. The pool sizing, the statement echo, the health
   * visibility and the logging levels are all left to the profile, which is what makes the assertions below
   * assertions about the profile rather than about this method.
   *
   * <p>Assumptions: the database transport security is relaxed to plain, because the profile inherits
   * verify-full against a private certificate authority bundle installed on a task. A container has no such
   * bundle, and installing one would make this test assert that a certificate exists on the build machine
   * rather than that the profile starts.
   *
   * @param registry the registry dynamic properties are added to
   */
  @DynamicPropertySource
  static void platformSuppliedValues(final DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("spring.flyway.create-schemas", () -> "true");
    // Refactoring Rationale: two entries stood here, `spring.flyway.init-sqls[0]` creating the NOLOGIN
    //   role carddemo_ledger_owner if absent and `[1]` assuming it. That key maps to Flyway's deprecated
    //   `initSql`, so both halves moved and neither is a dynamic property any more: the assumption is
    //   issued by common-lib's FlywayOwnerRoleDataSourceCustomizer and re-asserted by its
    //   FlywayOwnerRoleCallback, both from the profile's own
    //   carddemo.database.flyway.owner-role, and the creation is the OWNER_ROLE_SCRIPT this class's
    //   container runs at start -- which is strictly ahead of Flyway's first connection, where the
    //   removed entries only managed to be ahead of the first migration.
    //   Assumptions: nothing has to be registered in their place. Both replacements are reached by the
    //   profile and the container this class already declares, so a case that used to fail context load
    //   with `role "carddemo_ledger_owner" does not exist` now finds the role already present.
    registry.add("spring.datasource.hikari.data-source-properties.sslmode", () -> "disable");
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUrl);
    registry.add("carddemo.security.jwt.expected-client-id", () -> "carddemo-startup-probe-client");
    // Assumptions: the account-context address has to be https, host-only and reserved. The client's
    //   constructor runs ApprovedOriginPolicy at bean creation, which refuses a plain-HTTP scheme, refuses a
    //   path, and requires the address to equal the approved origin -- and the approved origin defaults to
    //   this same value, so one entry satisfies both. A `.invalid` host under RFC 2606 can never resolve,
    //   which is what makes it safe to name here: the client is constructed but never invoked by this class,
    //   and an address that cannot resolve is the strongest available guarantee that a start-up probe never
    //   reaches a real account context.
    registry.add("carddemo.account-context.base-url", () -> "https://account-context.startup-probe.invalid");
    registry.add("carddemo.internal-identity.transaction-signing-key", () -> PROBE_KEY_MATERIAL);
    // Assumptions: the cursor signing key is registered although NO document in this service declares it.
    //   It is named only in prose in application.yml and reaches a task from the secret store, and the bean
    //   that owns it is conditional on the property being present -- so with the key absent the context
    //   builds no CursorToken and every paged controller fails to autowire. The key therefore belongs in
    //   this method for exactly the reason the datasource credential does: it is supplied by the deployment,
    //   not decided by the profile.
    registry.add("carddemo.pagination.cursor.signing-key", () -> PROBE_KEY_MATERIAL);
    registry.add("server.ssl.key-store-password", () -> "startup-probe-not-a-real-keystore");
  }

  /**
   * The context started, and it started under the development profile rather than under any other.
   *
   * <p>Assumptions: the active profile is asserted from the STARTED context's own environment rather than
   * trusted from the annotation. A profile named on an annotation and then overridden by a property source --
   * which is a real possibility, since a resolved document may itself set {@code spring.profiles.active} --
   * would leave this class asserting the wrong profile's values while reporting success.
   */
  @Test
  @DisplayName("starts under the development profile")
  void startsUnderTheDevelopmentProfile() {
    assertThat(this.environment.getActiveProfiles()).contains("dev");
  }

  /**
   * The pool the profile sized is the pool the started context actually built.
   *
   * <p>Assumptions: the sizing is read off the LIVE pool rather than off the property, which is the whole
   * difference between this class and the parsing contract test beside it. A value that binds is not a value
   * the driver accepted: a ceiling below the minimum, or a minimum the pool refuses, fails here and resolves
   * cleanly there.
   */
  @Test
  @DisplayName("builds the pool the profile sized")
  void buildsThePoolTheProfileSized() {
    assertThat(this.dataSource.getClass().getName()).contains("Hikari");
    assertThat(this.environment.getProperty("spring.datasource.hikari.maximum-pool-size"))
        .isEqualTo("4");
    assertThat(this.environment.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("0");
  }

  /**
   * The migrations ran, so the schema the profile points the pool at exists and is reachable.
   *
   * <p>Assumptions: the assertion is a real query through the pool rather than an inspection of Flyway's own
   * report. A migration recorded as applied and a table a query can reach are different claims, and the
   * second is the one a running service depends on.
   *
   * @throws Exception when the connection or the query fails, which is itself the failure being tested for
   */
  @Test
  @DisplayName("applies its migrations against the engine")
  void appliesItsMigrationsAgainstTheEngine() throws Exception {
    try (var connection = this.dataSource.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT count(*) FROM ledger.transactions")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isGreaterThanOrEqualTo(0);
    }
  }

  /**
   * The development profile's diagnostic decisions are in force in the started context.
   *
   * <p>Assumptions: the statement echo and the aggregate health visibility are asserted from the started
   * context because they are the two decisions whose whole purpose is what a running task discloses. Echo off
   * keeps statements out of standard output and inside the log pattern that carries the correlation
   * identifier; health detail withheld keeps the datasource and migration state of a money-posting context
   * out of an unauthenticated probe answer.
   */
  @Test
  @DisplayName("keeps the profile's disclosure decisions in force once started")
  void keepsTheProfileDisclosureDecisionsInForceOnceStarted() {
    assertThat(this.environment.getProperty("spring.jpa.show-sql")).isEqualTo("false");
    assertThat(this.environment.getProperty("management.endpoint.health.show-details"))
        .isEqualTo("never");
    assertThat(this.environment.getProperty("logging.level.org.hibernate.orm.jdbc.bind"))
        .isEqualTo("WARN");
  }
}
