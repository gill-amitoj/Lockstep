package com.lockstep.e2e;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both services as real Docker containers, built from their Dockerfiles, on one
 * Docker network with real PostgreSQL and Kafka. The test talks to them only
 * over HTTP, like a real client, and never touches their Java code.
 *
 *            HTTP                       Kafka                       HTTP
 *   test --------> ledger --(outbox)--> topic --> fraud-service <-------- test
 *                    |                                |
 *                    +---- lockstep_ledger            +---- lockstep_fraud   (one Postgres server)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LedgerToFraudEndToEndTest {

    private static final Path ROOT = Path.of(System.getProperty("lockstep.root", ".."));
    private static final String JWT_SECRET = "e2e-only-secret-do-not-use-anywhere-else-0123456789";
    private static final String ADMIN_PASSWORD = "e2e-admin-password";
    private static final String USER_PASSWORD = "e2e-user-password";

    private static final Network network = Network.newNetwork();

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withDatabaseName("lockstep_ledger")
            // The same init script docker-compose.yml uses: creates lockstep_fraud.
            .withCopyFileToContainer(
                    MountableFile.forHostPath(ROOT.resolve("docker/postgres/init/01-create-fraud-database.sql")),
                    "/docker-entrypoint-initdb.d/01-create-fraud-database.sql");

    private static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.1")
            .withNetwork(network)
            // A second listener that other containers on the network can reach as kafka:19092.
            .withListener("kafka:19092");

    private static final ImageFromDockerfile LEDGER_IMAGE = image("ledger-service");
    private static final ImageFromDockerfile FRAUD_IMAGE = image("fraud-service");

    private static final HttpClient http = HttpClient.newHttpClient();

    private static GenericContainer<?> ledger;
    private static GenericContainer<?> fraud;

    @BeforeAll
    static void startEverything() {
        postgres.start();
        kafka.start();
        ledger = newLedger();
        ledger.start();
        fraud = newFraud();
        fraud.start();
    }

    @AfterAll
    static void stopEverything() {
        for (GenericContainer<?> container : List.of(fraud, ledger, kafka, postgres)) {
            if (container != null) {
                container.stop();
            }
        }
        network.close();
    }

    /**
     * A real customer journey through the ledger, flagged by the fraud service
     * without the ledger ever calling it.
     */
    @Test
    @Order(1)
    void suspiciousTransfersAreFlaggedAsynchronously() throws Exception {
        String admin = login("admin", ADMIN_PASSWORD);
        Customer alice = newCustomer("alice");
        Customer bob = newCustomer("bob");
        long systemId = systemAccountId(admin);
        transfer(admin, systemId, alice.accountId(), 100_000, null); // deposit 1,000.00

        // Five ordinary transfers: no rule should fire.
        for (int i = 0; i < 5; i++) {
            transfer(alice.token(), alice.accountId(), bob.accountId(), 1_000, null);
        }
        // The sixth: 600.00 is > 5x the 10.00 average AND the 6th in 10 minutes.
        String correlationId = "e2e-" + UUID.randomUUID();
        HttpResponse<String> sixth = transfer(alice.token(), alice.accountId(), bob.accountId(), 60_000, correlationId);
        assertThat(sixth.statusCode()).isEqualTo(201); // the transfer itself is never blocked by fraud checks
        long flaggedTransferId = ((Number) JsonPath.read(sixth.body(), "$.id")).longValue();

        List<String> rules = eventually(() -> flagRules(admin, alice.accountId(), flaggedTransferId),
                found -> found.size() == 2);
        assertThat(rules).containsExactlyInAnyOrder("LARGE_AMOUNT", "VELOCITY");

        // The bank's own deposits are never flagged.
        assertThat(flagRules(admin, systemId, null)).isEmpty();

        // One id traced from the HTTP request, through the outbox and Kafka headers, into fraud's logs.
        assertThat(fraud.getLogs()).contains("\"correlationId\":\"" + correlationId + "\"");
    }

    /**
     * The guarantee the outbox exists for: with the consumer down, Kafka
     * unreachable and the ledger killed with unsent events, nothing is lost.
     */
    @Test
    @Order(2)
    void noEventIsLostWhenServicesCrash() throws Exception {
        String admin = login("admin", ADMIN_PASSWORD);
        Customer carol = newCustomer("carol");
        Customer dave = newCustomer("dave");
        long systemId = systemAccountId(admin);

        // 1. Fraud service goes away, and Kafka stops answering.
        fraud.stop();
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();

        // 2. The ledger keeps taking payments regardless: transfers don't depend on Kafka.
        transfer(admin, systemId, carol.accountId(), 50_000, null);
        List<Long> transferIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            HttpResponse<String> response = transfer(carol.token(), carol.accountId(), dave.accountId(), 500, null);
            assertThat(response.statusCode()).isEqualTo(201);
            transferIds.add(((Number) JsonPath.read(response.body(), "$.id")).longValue());
        }
        // The events exist only in the outbox table, unsent.
        assertThat(unpublishedOutboxEvents(transferIds)).isEqualTo(6);

        // 3. The ledger is killed outright (SIGKILL: no graceful shutdown) while holding unsent events.
        ledger.getDockerClient().killContainerCmd(ledger.getContainerId()).exec();
        ledger.stop();

        // 4. Kafka recovers; a NEW ledger and a NEW fraud service start.
        kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        ledger = newLedger();
        ledger.start();
        fraud = newFraud();
        fraud.start();

        // 5. The restarted relay sends the backlog; the restarted consumer resumes from its
        //    last committed offset; the 6th transfer in 10 minutes is flagged.
        String freshAdmin = login("admin", ADMIN_PASSWORD);
        List<String> rules = eventually(() -> flagRules(freshAdmin, carol.accountId(), transferIds.getLast()),
                found -> !found.isEmpty());
        assertThat(rules).contains("VELOCITY");
        assertThat(unpublishedOutboxEvents(transferIds)).isZero();
    }

    // ---- containers -------------------------------------------------------------

    private static ImageFromDockerfile image(String service) {
        Path dir = ROOT.resolve(service);
        String jar = "build/libs/" + service + "-0.0.1-SNAPSHOT.jar";
        return new ImageFromDockerfile("lockstep-" + service + "-e2e", false)
                .withFileFromPath("Dockerfile", dir.resolve("Dockerfile"))
                .withFileFromPath(jar, dir.resolve(jar));
    }

    private static GenericContainer<?> newLedger() {
        return new GenericContainer<>(LEDGER_IMAGE)
                .withNetwork(network)
                .withEnv("DB_URL", "jdbc:postgresql://postgres:5432/lockstep_ledger")
                .withEnv("DB_USERNAME", postgres.getUsername())
                .withEnv("DB_PASSWORD", postgres.getPassword())
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .withEnv("JWT_SECRET", JWT_SECRET)
                .withEnv("ADMIN_USERNAME", "admin")
                .withEnv("ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withExposedPorts(8080)
                // Up and secured: an unauthenticated request gets 401.
                .waitingFor(Wait.forHttp("/accounts").forStatusCode(401).withStartupTimeout(Duration.ofMinutes(2)));
    }

    private static GenericContainer<?> newFraud() {
        return new GenericContainer<>(FRAUD_IMAGE)
                .withNetwork(network)
                .withEnv("FRAUD_DB_URL", "jdbc:postgresql://postgres:5432/lockstep_fraud")
                .withEnv("DB_USERNAME", postgres.getUsername())
                .withEnv("DB_PASSWORD", postgres.getPassword())
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .withEnv("JWT_SECRET", JWT_SECRET)
                .withExposedPorts(8081)
                .waitingFor(Wait.forHttp("/flags").forStatusCode(401).withStartupTimeout(Duration.ofMinutes(2)));
    }

    // ---- HTTP helpers -----------------------------------------------------------

    private record Customer(String token, long accountId) {
    }

    private static Customer newCustomer(String name) throws Exception {
        String username = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = post(ledgerUrl("/auth/register"), null, credentials(username, USER_PASSWORD));
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        String token = login(username, USER_PASSWORD);
        HttpResponse<String> account = post(ledgerUrl("/accounts"), token, "");
        assertThat(account.statusCode()).as(account.body()).isEqualTo(201);
        return new Customer(token, ((Number) JsonPath.read(account.body(), "$.id")).longValue());
    }

    private static String login(String username, String password) throws Exception {
        HttpResponse<String> response = post(ledgerUrl("/auth/token"), null, credentials(username, password));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.access_token");
    }

    private static long systemAccountId(String adminToken) throws Exception {
        HttpResponse<String> response = get(ledgerUrl("/accounts"), adminToken);
        List<Number> ids = JsonPath.read(response.body(), "$[?(@.type == 'SYSTEM')].id");
        return ids.getFirst().longValue();
    }

    private static HttpResponse<String> transfer(String token, long from, long to, long amountCents,
                                                 String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(ledgerUrl("/transfers"))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"fromAccountId": %d, "toAccountId": %d, "amountCents": %d}
                        """.formatted(from, to, amountCents)));
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return response;
    }

    /** Rules that flagged the account, optionally only for one transfer. Empty if fraud is unreachable. */
    private static List<String> flagRules(String adminToken, long accountId, Long transferId) {
        try {
            HttpResponse<String> response = get(fraudUrl("/flags?accountId=" + accountId), adminToken);
            if (response.statusCode() != 200) {
                return List.of();
            }
            String filter = transferId == null ? "$[*].rule" : "$[?(@.transferId == " + transferId + ")].rule";
            return JsonPath.read(response.body(), filter);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long unpublishedOutboxEvents(List<Long> transferIds) throws Exception {
        String ids = String.join(",", transferIds.stream().map(id -> "'" + id + "'").toList());
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             ResultSet rs = connection.createStatement().executeQuery(
                     "SELECT count(*) FROM outbox_events WHERE published_at IS NULL"
                             + " AND aggregate_type = 'transfer' AND aggregate_id IN (" + ids + ")")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static <T> T eventually(Supplier<T> probe, java.util.function.Predicate<T> done) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(90);
        T value = probe.get();
        while (!done.test(value)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Gave up waiting; last value: " + value
                        + "\n--- fraud logs ---\n" + tail(fraud) + "\n--- ledger logs ---\n" + tail(ledger));
            }
            Thread.sleep(500);
            value = probe.get();
        }
        return value;
    }

    private static String tail(GenericContainer<?> container) {
        if (container == null || !container.isRunning()) {
            return "(not running)";
        }
        String logs = container.getLogs();
        return logs.substring(Math.max(0, logs.length() - 4_000));
    }

    private static URI ledgerUrl(String path) {
        return URI.create("http://" + ledger.getHost() + ":" + ledger.getMappedPort(8080) + path);
    }

    private static URI fraudUrl(String path) {
        return URI.create("http://" + fraud.getHost() + ":" + fraud.getMappedPort(8081) + path);
    }

    private static HttpResponse<String> post(URI uri, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(URI uri, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String credentials(String username, String password) {
        return """
                {"username": "%s", "password": "%s"}
                """.formatted(username, password);
    }
}
