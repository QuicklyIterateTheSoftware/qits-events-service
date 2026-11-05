package eu.wohlben.qits.events.stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code qits:agent}, a commissioned agent's own role: it reads the log and both streams, and may
 * not record, publish or delete an event.
 *
 * <p>Each case names its caller with {@code @TestSecurity}, so the identity holds exactly the role
 * the case means; the suite runs with no forward-auth dev user (qits-1363).
 */
@QuarkusTest
class AgentReadAccessTest {

  @TestHTTPResource("/events/api/stream")
  URI sse;

  @TestHTTPResource("/events/stream")
  URI socket;

  @Test
  @TestSecurity(user = "dyn-workspace-agent", roles = "qits:agent")
  void anAgentReadsTheLog() {
    given().get("/events/api/events").then().statusCode(200);
    given().get("/events/api/events/names").then().statusCode(200);
    given().get("/events/api/events/" + UUID.randomUUID()).then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "dyn-workspace-agent", roles = "qits:agent")
  void anAgentOpensTheSseStream() throws Exception {
    try (SseReader reader = SseReader.open(sse)) {
      assertEquals(200, reader.status());
    }
  }

  @Test
  @TestSecurity(user = "dyn-workspace-agent", roles = "qits:agent")
  void anAgentDialsTheSocket() throws Exception {
    try (FakeSubscriber subscriber = FakeSubscriber.dial(socket)) {
      // Connected: the upgrade let the agent in.
    }
  }

  @Test
  @TestSecurity(user = "dyn-workspace-agent", roles = "qits:agent")
  void anAgentWritesNothing() {
    String id = UUID.randomUUID().toString();
    String body =
        "{\"name\":\"AgentProbe\",\"occurredAt\":\"2026-09-12T00:00:00Z\",\"payload\":\"{}\",\"publisher\":\"eu.wohlben.qits:qits-events-test\"}";
    given().contentType("application/json").body(body).post("/events/api/events").then()
        .statusCode(403);
    given().contentType("application/json").body(body).put("/events/api/events/" + id).then()
        .statusCode(403);
    given().delete("/events/api/events/" + id).then().statusCode(403);
  }

  /**
   * {@code qits:admin-agent} alone — without {@code qits:admin} and without {@code qits:agent} —
   * is admitted wherever {@code qits:admin} is (qits-628 follow-up), including the read surfaces.
   */
  @Test
  @TestSecurity(user = "dyn-admin-workspace-agent", roles = "qits:admin-agent")
  void anAdminAgentReadsTheLog() {
    given().get("/events/api/events").then().statusCode(200);
    given().get("/events/api/events/names").then().statusCode(200);
    given().get("/events/api/events/" + UUID.randomUUID()).then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "dyn-admin-workspace-agent", roles = "qits:admin-agent")
  void anAdminAgentOpensTheSseStream() throws Exception {
    try (SseReader reader = SseReader.open(sse)) {
      assertEquals(200, reader.status());
    }
  }

  @Test
  @TestSecurity(user = "dyn-admin-workspace-agent", roles = "qits:admin-agent")
  void anAdminAgentDialsTheSocket() throws Exception {
    try (FakeSubscriber subscriber = FakeSubscriber.dial(socket)) {
      // Connected: the upgrade let the admin agent in.
    }
  }

  /**
   * The representative admin write door: {@code POST}/{@code DELETE} take {@code qits:admin} or
   * {@code qits:admin-agent}, and {@code qits:agent} alone still gets 403 there (see {@link
   * #anAgentWritesNothing()}).
   */
  @Test
  @TestSecurity(user = "dyn-admin-workspace-agent", roles = "qits:admin-agent")
  void anAdminAgentRecordsAndRemovesAnEvent() {
    String body =
        "{\"name\":\"AdminAgentProbe\",\"occurredAt\":\"2026-09-12T00:00:00Z\",\"payload\":\"{}\",\"publisher\":\"eu.wohlben.qits:qits-events-test\"}";
    String id =
        given()
            .contentType("application/json")
            .body(body)
            .post("/events/api/events")
            .then()
            .statusCode(200)
            .extract()
            .path("event.id");
    given().delete("/events/api/events/" + id).then().statusCode(200);
  }

  /**
   * The delivery desk ({@code qits:desk}, qits-1596) is admitted everywhere {@code
   * qits:admin-agent} is — the same read surfaces exercised above for the admin agent.
   */
  @Test
  @TestSecurity(user = "dyn-delivery-desk", roles = "qits:desk")
  void theDeskReadsTheLog() {
    given().get("/events/api/events").then().statusCode(200);
    given().get("/events/api/events/names").then().statusCode(200);
    given().get("/events/api/events/" + UUID.randomUUID()).then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "dyn-delivery-desk", roles = "qits:desk")
  void theDeskOpensTheSseStream() throws Exception {
    try (SseReader reader = SseReader.open(sse)) {
      assertEquals(200, reader.status());
    }
  }

  @Test
  @TestSecurity(user = "dyn-delivery-desk", roles = "qits:desk")
  void theDeskDialsTheSocket() throws Exception {
    try (FakeSubscriber subscriber = FakeSubscriber.dial(socket)) {
      // Connected: the upgrade let the desk in.
    }
  }

  /** The representative admin write door, mirrored for the desk. */
  @Test
  @TestSecurity(user = "dyn-delivery-desk", roles = "qits:desk")
  void theDeskRecordsAndRemovesAnEvent() {
    String body =
        "{\"name\":\"DeskProbe\",\"occurredAt\":\"2026-09-12T00:00:00Z\",\"payload\":\"{}\",\"publisher\":\"eu.wohlben.qits:qits-events-test\"}";
    String id =
        given()
            .contentType("application/json")
            .body(body)
            .post("/events/api/events")
            .then()
            .statusCode(200)
            .extract()
            .path("event.id");
    given().delete("/events/api/events/" + id).then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "dyn-workspace-agent", roles = "qits:reader")
  void aRoleOutsideTheBoundaryIsStillRefused() {
    given().get("/events/api/events").then().statusCode(403);
    assertThrows(Exception.class, () -> FakeSubscriber.dial(socket).close());
  }
}
