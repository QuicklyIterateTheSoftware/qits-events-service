package eu.wohlben.qits.events.telemetry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.telemetry.OtlpLogStub.Captured;
import eu.wohlben.qits.events.testdb.EmbeddedPg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The log bridge on the <em>packaged artifact</em> — the fast-jar under {@code ./mvnw verify
 * -DskipITs=false}, the GraalVM binary under {@code -Dnative}.
 *
 * <p>{@link OtelLogBridgeTest} proves the bridge in the build JVM, and that is not enough to ship
 * on. Every platform service runs as a native image, and the two things this proof depends on are
 * exactly the two a native image can lose: a log <b>handler</b> installed during the extension's
 * runtime initialisation, and an <b>exporter</b> that marshals protobuf and opens a connection. Both
 * are build-time-sensitive, both are invisible to a {@code @QuarkusTest} — which augments in the
 * build JVM with the whole classpath present — and a binary that dropped either would start
 * cleanly, serve every request and log nothing anywhere but its own console.
 *
 * <p>It also runs the <b>shipped</b> configuration rather than the suite's: the launched process
 * takes the {@code prod} profile, so {@code %test.quarkus.otel.sdk.disabled=true} does not apply and
 * the four {@code quarkus.otel.logs.*} keys in {@code application.properties} are what is under
 * test. Only the endpoint is redirected, at {@link OtlpLogStub}, whose base carries qits'
 * non-default {@code /observability/api/otel} prefix so that the SDK's {@code /v1/logs} suffixing is
 * exercised too.
 *
 * <p>The two records it asserts on are ones the <em>shipped</em> code produces, because a fixture
 * class in {@code src/test} is not in the artifact:
 *
 * <ul>
 *   <li>Quarkus' own startup line, an ordinary INFO record through the same JBoss Log Manager every
 *       application logger uses;
 *   <li>a genuine unhandled server error, which is the shape that matters most — an ERROR with a
 *       throwable, raised inside a request, which is what an operator goes looking for.
 * </ul>
 */
@QuarkusIntegrationTest
@TestProfile(PackagedLogBridgeIT.PackagedUnderTarget.class)
@WithTestResource(OtlpLogStub.class)
public class PackagedLogBridgeIT {

  /**
   * The same arrangement {@code PackagedSurfaceIT} makes — the launched process is handed {@code
   * QITS_RESOURCE_DB_URL} and its two siblings, which is what the events jar's shipped datasource
   * defaults expand — on a database of its own so the two ITs cannot write into each other's
   * schema. Without it the process has no store at all and dies at Flyway before it logs anything
   * this IT could read.
   *
   * <p>The url travels through a <b>system property</b> rather than a static field: a test profile
   * is instantiated in more than one classloader, so a field written by one copy is not the field
   * the other reads, while the process has exactly one property table.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {

    /** Where the url is parked for whichever copy of this class is asked second. */
    private static final String URL_PROPERTY = "qits.test.log-bridge-it.db-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "QITS_RESOURCE_DB_URL", databaseUrl(),
          "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
    }

    private static synchronized String databaseUrl() {
      String recorded = System.getProperty(URL_PROPERTY);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url("events_log_bridge_it");
      System.setProperty(URL_PROPERTY, url);
      return url;
    }
  }

  /** The launched process exports on its own schedule; every wait here is a deadline. */
  private static final Duration ARRIVAL = Duration.ofSeconds(30);

  /** Quarkus' per-failure id: a UUID with a request counter appended. */
  private static final Pattern ERROR_ID =
      Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}-\\d+");

  @Test
  public void theArtifactShipsItsOrdinaryLogRecordsOverOtlp() {
    // Quarkus' own "started in" line: an org.jboss.logging record at INFO, made by the packaged
    // process before any test touched it. If the handler did not survive the image build, nothing
    // at all arrives here and the assertion says exactly that.
    Captured started =
        OtlpLogStub.await(
                c -> c.record().getSeverityNumber().getNumber() == 9 && c.body().contains("started in"),
                ARRIVAL)
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the packaged artifact exported no INFO record — the OTel log handler is "
                            + "not installed in this build. "
                            + OtlpLogStub.describe()));

    // Identity comes from quarkus.application.name through the OTel resource, which is what
    // qits-observability buckets a source by. A binary that lost the resource would report
    // "_unscoped" in the live UI, and that is a silent failure worth a test.
    assertEquals(
        Optional.of("qits-events"),
        started.resourceAttribute("service.name"),
        "the packaged process must identify itself; " + OtlpLogStub.describe());
    assertEquals("INFO", started.record().getSeverityText());
    assertTrue(started.record().getTimeUnixNano() > 0, "the record carried no timestamp");
  }

  @Test
  public void everyExportCarriesTheQitsClientsBearer() {
    // The receiver sits behind the edge, which refuses an anonymous POST (epic qits-428), so a
    // record that arrived without the `qits` client's token is a record the deployment drops. The
    // stub is the idp as well: the shipped client, switched on against it, mints its token there,
    // and qits-telemetry-core's header supplier hands it to every export.
    OtlpLogStub.await(c -> c.body().contains("started in"), ARRIVAL)
        .orElseThrow(() -> new AssertionError("nothing exported; " + OtlpLogStub.describe()));

    List<String> authorizations = OtlpLogStub.authorizations();
    assertFalse(authorizations.isEmpty(), "no log export was seen at all");
    assertEquals(
        List.of("Bearer " + OtlpLogStub.TOKEN),
        authorizations.stream().distinct().toList(),
        "every log export must carry the qits client's bearer");
  }

  @Test
  public void anUnhandledServerErrorArrivesWithItsThrowableAndTraceContext() {
    // A real failure rather than a staged one: `name` is varchar(512) and nothing above the database
    // bounds it, so an over-long name is a genuine unhandled exception on the write path — a 500,
    // logged by the server at ERROR with the throwable, from inside the request's span. Marshalling
    // a throwable into exception.* attributes and reading the ambient trace context are precisely
    // the two things a native image could quietly stop doing.
    String body =
        given()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"" + "x".repeat(600) + "\",\"occurredAt\":\"2026-08-05T09:00:00Z\"}")
            .when()
            .post("/events/api/events")
            .then()
            .statusCode(500)
            .extract()
            .asString();

    // THE ERROR ID IS THE CORRELATION, and it has to be: the request used to plant a marker string
    // in the over-long name and match it inside the stack trace, which worked only because H2 echoed
    // the rejected VALUE in its message. PostgreSQL says `value too long for type character
    // varying(512)` and names no value, so the marker never reaches a log record. The id Quarkus
    // mints per failure is in both halves — the response body and QuarkusErrorHandler's own line —
    // so it ties the record to THIS request rather than to a leftover of an earlier one.
    Matcher id = ERROR_ID.matcher(body);
    assertTrue(id.find(), "the 500 carried no error id to correlate on; body was: " + body);
    String errorId = id.group();

    // MEASURED: one failure makes SEVERAL records, and the severity is what tells them apart. Two
    // Hibernate/Arjuna WARNs (severity 13) carry the same stack trace, and the ERROR (17) is
    // QuarkusErrorHandler's "HTTP Request to ... failed, error id: …" — the record an operator's
    // errors feed shows. So the predicate matches on severity as well as on the id; matching on the
    // message alone picks a WARN and quietly proves less.
    Captured error =
        OtlpLogStub.await(
                c ->
                    c.record().getSeverityNumber().getNumber() == 17
                        && c.body().contains(errorId)
                        && c.attribute("exception.stacktrace").isPresent(),
                ARRIVAL)
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the packaged artifact exported no ERROR record carrying the failure's "
                            + "stack trace. "
                            + OtlpLogStub.describe()));

    assertEquals("ERROR", error.record().getSeverityText());

    // The three stable OTel exception attributes, the same ones OtelLogBridgeTest pins in the JVM.
    // Only the stack trace's content is asserted: the exception TYPE of a persistence failure is the
    // ORM's business and would make this test a pin on Hibernate's wrapping rather than on the
    // bridge.
    assertTrue(
        error.attribute("exception.type").isPresent(), "no exception.type on a logged throwable");
    assertTrue(
        error.attribute("exception.message").isPresent(),
        "no exception.message on a logged throwable");

    // Correlation: the record belongs to the trace of the request that failed, so the error and the
    // request that caused it are one thing in the UI rather than two.
    assertNotEquals(
        "",
        error.traceId(),
        "an error logged inside a request must carry its trace id; " + OtlpLogStub.describe());
    assertNotEquals("", error.spanId(), "an error logged inside a request must carry its span id");
  }
}
