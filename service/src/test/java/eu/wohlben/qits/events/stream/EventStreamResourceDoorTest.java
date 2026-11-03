package eu.wohlben.qits.events.stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The SSE stream's door. Each case names its caller with {@code @TestSecurity} and exactly the role
 * it means, so a {@code @RolesAllowed} boundary is really being decided rather than waved through;
 * the suite runs with no forward-auth dev user (qits-1363), so a case that names nobody is
 * anonymous.
 *
 * <p>The roles are the socket's, exactly: {@code qits:admin} for a person's session and {@code
 * qits:system} for a machine. And the refusal is a <b>403 at connect</b> rather than an empty
 * stream, which is the decision worth a test of its own — an empty stream is indistinguishable from
 * an idle estate, so a browser chrome handed one would show a permanently calm indicator and never
 * learn it was blind.
 */
@QuarkusTest
class EventStreamResourceDoorTest {

  @TestHTTPResource("/events/api/stream")
  URI endpoint;

  @Test
  @TestSecurity(user = "alice", roles = "qits:admin")
  void anAdminSessionOpensTheStream() throws Exception {
    try (SseReader reader = SseReader.open(endpoint)) {
      assertEquals(200, reader.status());
      assertTrue(
          reader.contentType().startsWith("text/event-stream"),
          "an opened stream must answer as SSE; got: " + reader.contentType());
      // The opening comment: the head is flushed and the connection is live, not merely accepted.
      assertNotNull(reader.nextLine(Duration.ofSeconds(10)), "the opened stream said nothing");
    }
  }

  /**
   * {@code qits:admin-agent} alone is admitted wherever {@code qits:admin} is (qits-628
   * follow-up).
   */
  @Test
  @TestSecurity(user = "dyn-admin-workspace-agent", roles = "qits:admin-agent")
  void anAdminAgentOpensTheStreamToo() throws Exception {
    try (SseReader reader = SseReader.open(endpoint)) {
      assertEquals(200, reader.status());
    }
  }

  /**
   * The delivery desk's token ({@code qits:desk}, qits-1596) is admitted wherever {@code
   * qits:admin-agent} is.
   */
  @Test
  @TestSecurity(user = "dyn-delivery-desk", roles = "qits:desk")
  void theDeskOpensTheStreamToo() throws Exception {
    try (SseReader reader = SseReader.open(endpoint)) {
      assertEquals(200, reader.status());
    }
  }

  @Test
  @TestSecurity(user = "qits-ci", roles = "qits:system")
  void aMachineConsumerOpensTheStreamToo() throws Exception {
    try (SseReader reader = SseReader.open(endpoint)) {
      assertEquals(200, reader.status());
    }
  }

  @Test
  @TestSecurity(user = "alice", roles = "qits:reader")
  void aRoleOutsideTheBoundaryIsRefusedAtConnect() {
    // `qits:reader` because it is outside EVERY role this boundary admits, so the case still tests
    // a boundary rather than a spelling.
    given()
        .header("Accept", "text/event-stream")
        .when()
        .get("/events/api/stream")
        .then()
        .statusCode(403);
  }

  @Test
  void anAnonymousReaderIsRefusedAtConnect() {
    // No caller at all is anonymous, and anonymous holds no roles, so the annotation refuses it.
    // This is the annotation deciding, not this service inventing a security state out of
    // anonymity.
    given()
        .header("Accept", "text/event-stream")
        .when()
        .get("/events/api/stream")
        .then()
        .statusCode(401);
  }
}
