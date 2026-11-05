package eu.wohlben.qits.events.telemetry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.Map;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The other half of the log-bridge proof: what shipping logs costs when nothing is listening.
 *
 * <p>The design's one non-negotiable is that logging must never make this service's availability
 * depend on qits-observability. So the acceptance test for an unreachable receiver is not "every log
 * is eventually delivered" — it cannot be, and pretending otherwise is how an in-process queue grows
 * without bound. It is: <b>the process starts, answers requests and stays bounded while every export
 * fails.</b>
 *
 * <p>The exporter points at {@code http://127.0.0.1:1} — a closed port on the loopback, so the
 * failure is an immediate connection refusal rather than a timeout the suite would wait out. That
 * the application under test <em>starts at all</em> under this profile is the first assertion and it
 * is made by the class existing: a bridge that treated its receiver as a dependency would fail here
 * before a test method ran.
 *
 * <p>Bounded memory is asserted as far as it is observable from outside, and no further. The SDK's
 * batch processor holds a fixed-capacity queue ({@code quarkus.otel.blrp.max.queue.size}, 2048 by
 * default) and drops rather than grows once it is full; what a test can honestly see is that far
 * more records than that can be emitted without the caller ever blocking and without the service
 * ceasing to answer. Reaching inside for a queue depth would assert the SDK's internals rather than
 * qits' requirement.
 */
@QuarkusTest
// The caller is stated, not inherited from forward-auth's %test dev user (qits-1363): a person
// reading and recording through the events client.
@TestSecurity(user = "alice", roles = "qits:admin")
@TestProfile(OtelLogExporterUnreachableTest.DeadReceiver.class)
public class OtelLogExporterUnreachableTest {

  /** Exporter on, receiver gone. */
  public static class DeadReceiver implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.otel.sdk.disabled",
          "false",
          // Port 1 is privileged and unbound: connect refused immediately. NOT an unresolvable host
          // name, which would make every export a DNS timeout and this suite a slow one.
          // qits-telemetry-core's exporters (epic qits-428) read qits.telemetry.endpoint; the `qits`
          // oidc client stays off as %test ships it, so each export also goes out with no bearer.
          "qits.telemetry.endpoint",
          "http://127.0.0.1:1");
    }
  }

  private static final Logger LOG = Logger.getLogger(OtelLogExporterUnreachableTest.class);

  /** Comfortably more than the batch processor's default 2048-record queue. */
  private static final int RECORDS = 3_000;

  @Test
  public void theServiceKeepsAnsweringWhileEveryExportFails() {
    // Interleaved rather than "log a lot, then check": the question is whether a failing exporter
    // can affect a request in flight, and requests that only run after the burst would not ask it.
    long startedAt = System.nanoTime();
    for (int i = 0; i < RECORDS; i++) {
      LOG.infof("unreachable-receiver canary %d", i);
      if (i % 300 == 0) {
        given()
            .when()
            .get("/events/api/events")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON);
      }
    }
    Duration emitting = Duration.ofNanos(System.nanoTime() - startedAt);

    // The logging call itself never waits on the exporter. A bridge that pushed synchronously, or
    // that blocked when its queue filled, would show up here as a burst that took the export
    // timeout (30s by default) rather than the time it takes to write to a console.
    assertTrue(
        emitting.compareTo(Duration.ofSeconds(30)) < 0,
        RECORDS + " records against a dead receiver took " + emitting + " — logging is blocking");

    // Writes, not only reads: the create path commits a transaction and fans out to the stream, and
    // both run on threads the exporter shares.
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"UnreachableReceiver\",\"occurredAt\":\"2026-08-05T09:00:00Z\",\"publisher\":\"eu.wohlben.qits:qits-events-test\"}")
        .when()
        .post("/events/api/events")
        .then()
        .statusCode(200);

    // And the deployment's own question — qits-cd's health gate — still answers UP, so a receiver
    // outage cannot cascade into this service being restarted or taken out of rotation.
    given()
        .when()
        .get("/events/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", org.hamcrest.Matchers.equalTo("UP"));
  }
}
