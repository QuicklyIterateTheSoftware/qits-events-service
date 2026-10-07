package eu.wohlben.qits.events.stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code qits:agent}, a commissioned agent's own role: it reads the log and both streams, and may
 * not record, publish or delete an event.
 *
 * <p>Each request names its identity in {@code X-Qits-User} / {@code X-Qits-Roles}, so the {@code
 * %test} dev user does not apply and the identity holds exactly the role sent.
 */
@QuarkusTest
class AgentReadAccessTest {

  private static final Map<String, String> AGENT =
      Map.of("X-Qits-User", "dyn-workspace-agent", "X-Qits-Roles", "qits:agent");

  /** An admin workspace's agent (qits-628 follow-up): admitted wherever {@code qits:admin} is. */
  private static final Map<String, String> ADMIN_AGENT =
      Map.of("X-Qits-User", "dyn-admin-workspace-agent", "X-Qits-Roles", "qits:admin-agent");

  private static final Map<String, String> READER =
      Map.of("X-Qits-User", "dyn-workspace-agent", "X-Qits-Roles", "qits:reader");

  @TestHTTPResource("/events/api/stream")
  URI sse;

  @TestHTTPResource("/events/stream")
  URI socket;

  private static RequestSpecification agent() {
    return given().headers(AGENT);
  }

  @Test
  void anAgentReadsTheLog() {
    agent().get("/events/api/events").then().statusCode(200);
    agent().get("/events/api/events/names").then().statusCode(200);
    agent().get("/events/api/events/" + UUID.randomUUID()).then().statusCode(404);
  }

  @Test
  void anAgentOpensTheSseStream() throws Exception {
    try (SseReader reader = SseReader.open(sse, AGENT)) {
      assertEquals(200, reader.status());
    }
  }

  @Test
  void anAgentDialsTheSocket() throws Exception {
    try (FakeSubscriber subscriber = FakeSubscriber.dial(socket, AGENT)) {
      // Connected: the upgrade let the agent in.
    }
  }

  @Test
  void anAgentWritesNothing() {
    String id = UUID.randomUUID().toString();
    String body =
        "{\"name\":\"AgentProbe\",\"occurredAt\":\"2026-09-12T00:00:00Z\",\"payload\":\"{}\"}";
    agent().contentType("application/json").body(body).post("/events/api/events").then()
        .statusCode(403);
    agent().contentType("application/json").body(body).put("/events/api/events/" + id).then()
        .statusCode(403);
    agent().delete("/events/api/events/" + id).then().statusCode(403);
  }

  /**
   * {@code qits:admin-agent} alone — without {@code qits:admin} and without {@code qits:agent} —
   * is admitted wherever {@code qits:admin} is (qits-628 follow-up), including the read surfaces.
   */
  @Test
  void anAdminAgentReadsTheLog() {
    given().headers(ADMIN_AGENT).get("/events/api/events").then().statusCode(200);
    given().headers(ADMIN_AGENT).get("/events/api/events/names").then().statusCode(200);
    given()
        .headers(ADMIN_AGENT)
        .get("/events/api/events/" + UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  @Test
  void anAdminAgentOpensTheSseStream() throws Exception {
    try (SseReader reader = SseReader.open(sse, ADMIN_AGENT)) {
      assertEquals(200, reader.status());
    }
  }

  @Test
  void anAdminAgentDialsTheSocket() throws Exception {
    try (FakeSubscriber subscriber = FakeSubscriber.dial(socket, ADMIN_AGENT)) {
      // Connected: the upgrade let the admin agent in.
    }
  }

  /**
   * The representative admin write door: {@code POST}/{@code DELETE} take {@code qits:admin} or
   * {@code qits:admin-agent}, and {@code qits:agent} alone still gets 403 there (see {@link
   * #anAgentWritesNothing()}).
   */
  @Test
  void anAdminAgentRecordsAndRemovesAnEvent() {
    String body =
        "{\"name\":\"AdminAgentProbe\",\"occurredAt\":\"2026-09-12T00:00:00Z\",\"payload\":\"{}\"}";
    String id =
        given()
            .headers(ADMIN_AGENT)
            .contentType("application/json")
            .body(body)
            .post("/events/api/events")
            .then()
            .statusCode(200)
            .extract()
            .path("event.id");
    given().headers(ADMIN_AGENT).delete("/events/api/events/" + id).then().statusCode(200);
  }

  @Test
  void aRoleOutsideTheBoundaryIsStillRefused() {
    given().headers(READER).get("/events/api/events").then().statusCode(403);
    assertThrows(Exception.class, () -> FakeSubscriber.dial(socket, READER).close());
  }
}
