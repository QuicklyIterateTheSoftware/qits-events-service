package eu.wohlben.qits.events.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.events.entity.Event;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code PUT /events/api/events/{id}} — the bus's publish, over the shipped addresses.
 *
 * <p>Three answers and no fourth: 201 for an id this log has not seen, 200 for the same event
 * arriving twice, 400 for a UUID that has been reused for something else. The bodies here are raw
 * JSON strings rather than the request record, because what a publisher in another repository sends
 * is bytes on a wire and the point is to prove this service reads <em>those</em> — including a
 * {@code payload} that is a JSON document escaped inside a JSON string, which is the shape most
 * easily broken by a well-meaning change on either side.
 */
@QuarkusTest
// The caller is stated, not inherited from forward-auth's %test dev user (qits-1363): a publisher
// (qits:system, the only role that PUTs) whose writes the class reads back by id, which only a
// reader role may do.
@TestSecurity(user = "qits-ci", roles = {"qits:system", "qits:admin"})
class EventPublishApiTest {

  private static final String PAYLOAD =
      "{\\\"branch\\\":\\\"main\\\",\\\"commitSha\\\":\\\"abc123\\\",\\\"repoId\\\":\\\"qits-ci\\\"}";

  /** The publisher the stamped envelopes name. */
  private static final String CI = "eu.wohlben.qits:qits-ci-service";

  /** The envelope as a publisher that knows about causation sends it — {@code parentId} always present. */
  private static String envelope(String name, String occurredAt, String payload, String parentId) {
    return "{\"name\":\""
        + name
        + "\",\"occurredAt\":\""
        + occurredAt
        + "\",\"payload\":"
        + (payload == null ? "null" : "\"" + payload + "\"")
        + ",\"description\":null,\"parentId\":"
        + (parentId == null ? "null" : "\"" + parentId + "\"")
        + ",\"publisher\":\""
        + CI
        + "\"}";
  }

  /** The whole envelope as today's publisher sends it — {@code environment} always present too. */
  private static String envelope(
      String name, String occurredAt, String payload, String parentId, String environment) {
    return "{\"name\":\""
        + name
        + "\",\"occurredAt\":\""
        + occurredAt
        + "\",\"payload\":"
        + (payload == null ? "null" : "\"" + payload + "\"")
        + ",\"description\":null,\"parentId\":"
        + (parentId == null ? "null" : "\"" + parentId + "\"")
        + ",\"environment\":"
        + (environment == null ? "null" : "\"" + environment + "\"")
        + ",\"publisher\":\""
        + CI
        + "\"}";
  }

  /** The envelope with a {@code publisher} — what a publisher that stamps its own name sends. */
  private static String stamped(String name, String occurredAt, String payload, String publisher) {
    return "{\"name\":\""
        + name
        + "\",\"occurredAt\":\""
        + occurredAt
        + "\",\"payload\":"
        + (payload == null ? "null" : "\"" + payload + "\"")
        + ",\"description\":null,\"parentId\":null,\"environment\":\"dev\",\"publisher\":"
        + (publisher == null ? "null" : "\"" + publisher + "\"")
        + "}";
  }

  /**
   * The envelope <b>without</b> the optional {@code parentId} and {@code environment} at all — what
   * this service must go on accepting, because absent is legal for both. The publisher is on it all
   * the same: it is required since qits-1771.
   */
  private static String envelope(String name, String occurredAt, String payload) {
    return "{\"name\":\""
        + name
        + "\",\"occurredAt\":\""
        + occurredAt
        + "\",\"payload\":"
        + (payload == null ? "null" : "\"" + payload + "\"")
        + ",\"description\":null,\"publisher\":\""
        + CI
        + "\"}";
  }

  private static io.restassured.response.Response put(String id, String body) {
    return given().contentType(ContentType.JSON).body(body).when().put("/events/api/events/" + id);
  }

  @Test
  void anUnknownIdIsCreatedAndAnsweredTwoOhOne() {
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD))
        .then()
        .statusCode(201)
        .body("event.id", equalTo(id))
        .body("event.name", equalTo("BuildSuccessful"))
        .body("event.occurredAt", equalTo("2026-07-31T12:46:03Z"))
        // Verbatim: the canonical JSON the publisher produced, handed back unreformatted.
        .body(
            "event.payload",
            equalTo("{\"branch\":\"main\",\"commitSha\":\"abc123\",\"repoId\":\"qits-ci\"}"))
        .body("event.createdAt", notNullValue());
  }

  @Test
  void theSameBytesAgainAreAnsweredTwoHundredAndWriteNothing() {
    String id = UUID.randomUUID().toString();
    String body = envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD);
    String createdAt = put(id, body).then().statusCode(201).extract().path("event.createdAt");

    // A publisher whose first attempt got no answer sends exactly this again.
    put(id, body)
        .then()
        .statusCode(200)
        .body("event.id", equalTo(id))
        // Nothing was written, so the row's own timestamp is still the first attempt's.
        .body("event.createdAt", equalTo(createdAt));
  }

  @Test
  void aReusedUuidIsFourHundredWhateverDiffers() {
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD)).then().statusCode(201);

    // The caller reused a UUID. Unretryable, so 400 rather than a conflict to sit and poll on —
    // and the answer is this context's JSON error, never the client's index.html.
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", "{\\\"branch\\\":\\\"other\\\"}"))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
    put(id, envelope("SomethingElse", "2026-07-31T12:46:03Z", PAYLOAD)).then().statusCode(400);
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:04Z", PAYLOAD)).then().statusCode(400);
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", null)).then().statusCode(400);
  }

  @Test
  void anIdThatIsNotAUuidIsFourHundred() {
    // The id is the idempotency key, so a caller that cannot spell one has no retry-safe identity
    // to offer. GET and DELETE stay tolerant of any String id — see EventApiTest.
    put("not-a-uuid", envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON);
    put("1-1-1-1-1", envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD))
        .then()
        .statusCode(400);
  }

  @Test
  void anEnvelopeMissingItsRequiredFieldsIsFourHundred() {
    String id = UUID.randomUUID().toString();
    // occurredAt is required here and optional on POST: it is one of the three fields a replay is
    // compared on, so a time this server invented would make the event unable to replay as itself.
    // Each body carries a publisher, so the refusal is the field the line is about.
    String publisher = ",\"publisher\":\"" + CI + "\"}";
    put(id, "{\"name\":\"BuildSuccessful\"" + publisher).then().statusCode(400);
    put(id, "{\"occurredAt\":\"2026-07-31T12:46:03Z\"" + publisher).then().statusCode(400);
    put(id, "{\"name\":\"  \",\"occurredAt\":\"2026-07-31T12:46:03Z\"" + publisher)
        .then()
        .statusCode(400);
  }

  @Test
  void aPublishedEventNeedNotCarryAPayload() {
    String id = UUID.randomUUID().toString();
    String body = envelope("SomethingHappened", "2026-07-31T12:46:03Z", null);
    put(id, body).then().statusCode(201).body("event.payload", nullValue());
    put(id, body).then().statusCode(200);
  }

  @Test
  void aPublishMayNameTheEventThatCausedIt() {
    String parent = UUID.randomUUID().toString();
    String id = UUID.randomUUID().toString();
    String body = envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, parent);

    put(id, body).then().statusCode(201).body("event.parentId", equalTo(parent));
    // ... and the same bytes again are the same event, cause and all.
    put(id, body).then().statusCode(200).body("event.parentId", equalTo(parent));

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event.parentId", equalTo(parent));
  }

  @Test
  void theParentIdKeyIsPresentEvenWhenThereIsNoParent() {
    // hasKey, not nullValue(): a JSON path that is ABSENT also reads as null, and the difference is
    // the whole of what a client can rely on. A consumer checking "does this service know about
    // causation?" looks for the key, so an omit-nulls mapper here would be a silent contract break
    // that every assertion phrased as nullValue() would go on passing through.
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null))
        .then()
        .statusCode(201)
        .body("event", hasKey("parentId"))
        .body("event.parentId", nullValue());

    given().when().get("/events/api/events/" + id).then().body("event", hasKey("parentId"));
  }

  @Test
  void anEnvelopeThatNeverLearnedAboutTheFieldIsStillAccepted() {
    // The contract's one backward-compatibility clause: absent is legal and means null. A publisher
    // built against the five-field envelope keeps working, which is what makes it safe to ship this
    // service before the publishers that stamp — and it is one-directional, so the other order
    // (a stamping publisher against an events service without the column) is the one that loses
    // parents silently.
    String id = UUID.randomUUID().toString();
    String body = envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD);
    put(id, body).then().statusCode(201).body("event.parentId", nullValue());
    put(id, body).then().statusCode(200);
    // Absent and an explicit null are the same statement, so one replays as the other.
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null))
        .then()
        .statusCode(200);
  }

  @Test
  void reParentingOneIdIsFourHundredInBothDirections() {
    // parentId is INSIDE the comparison, unlike description. Two PUTs of one id claiming different
    // causes are two different claims about history; kept outside, the server would keep the first
    // and answer 200 while the publisher believed it had published the second — two services
    // disagreeing about the shape of history with no error anywhere.
    String first = UUID.randomUUID().toString();
    String parent = UUID.randomUUID().toString();
    put(first, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null))
        .then()
        .statusCode(201);
    // null → set
    put(first, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, parent))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());

    String second = UUID.randomUUID().toString();
    put(second, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, parent))
        .then()
        .statusCode(201);
    // set → null, and set → a different one
    put(second, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null))
        .then()
        .statusCode(400);
    put(
            second,
            envelope(
                "BuildSuccessful",
                "2026-07-31T12:46:03Z",
                PAYLOAD,
                UUID.randomUUID().toString()))
        .then()
        .statusCode(400);
  }

  @Test
  void aPublishMayNameTheTierItRanIn() {
    String id = UUID.randomUUID().toString();
    String body = envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, "dev");

    put(id, body).then().statusCode(201).body("event.environment", equalTo("dev"));
    // ... and the same bytes again are the same event, tier and all.
    put(id, body).then().statusCode(200).body("event.environment", equalTo("dev"));

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event.environment", equalTo("dev"));
  }

  @Test
  void theEnvironmentKeyIsPresentEvenWhenThereIsNoTier() {
    // The same hasKey clause parentId carries, for the same reason: absent also reads as null, and
    // a consumer probing "does this service know about tiers?" looks for the key itself.
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null))
        .then()
        .statusCode(201)
        .body("event", hasKey("environment"))
        .body("event.environment", nullValue());

    given().when().get("/events/api/events/" + id).then().body("event", hasKey("environment"));
  }

  @Test
  void reTieringOneIdIsFourHundredInBothDirections() {
    // environment is INSIDE the comparison, like parentId: one id claiming two tiers is two
    // different claims about history.
    String first = UUID.randomUUID().toString();
    put(first, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, null))
        .then()
        .statusCode(201);
    // null → set
    put(first, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, "dev"))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());

    String second = UUID.randomUUID().toString();
    put(second, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, "dev"))
        .then()
        .statusCode(201);
    // set → null, and set → a different one
    put(second, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, null))
        .then()
        .statusCode(400);
    put(second, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, "platform"))
        .then()
        .statusCode(400);
  }

  @Test
  void aPublishMayNameItsPublisher() {
    String id = UUID.randomUUID().toString();
    String body = stamped("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, CI);

    put(id, body).then().statusCode(201).body("event.publisher", equalTo(CI));
    // ... and the same bytes again are the same event, publisher and all.
    put(id, body).then().statusCode(200).body("event.publisher", equalTo(CI));

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event.publisher", equalTo(CI));
  }

  @Test
  void aPublishWithoutAPublisherIsFourHundredNamingTheField() {
    // Required since qits-1771: every publisher on the estate has stamped one since 2026-10-31, so
    // an envelope without one is a broken client, not an old one. Absent, JSON null, empty and
    // blank are all the same refusal, and none of them writes a row.
    String id = UUID.randomUUID().toString();
    String withoutTheKey =
        "{\"name\":\"BuildSuccessful\",\"occurredAt\":\"2026-07-31T12:46:03Z\",\"payload\":\""
            + PAYLOAD
            + "\",\"description\":null,\"parentId\":null,\"environment\":\"dev\"}";
    for (String body :
        java.util.List.of(
            withoutTheKey,
            stamped("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null),
            stamped("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, ""),
            stamped("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, "   "))) {
      put(id, body).then().statusCode(400).body(containsString("publisher"));
    }
    given().when().get("/events/api/events/" + id).then().statusCode(404);
  }

  @Test
  void anEventStoredBeforePublishersWereRequiredStillReadsWithANullPublisher() {
    // Rows from before 2026-10-31 keep their null: the requirement guards writes and rewrites
    // nothing. Inserted through the entity, past every write-path guard, as such a row was.
    String id = UUID.randomUUID().toString();
    String name = "LegacyUnattributed" + System.nanoTime();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Event legacy = new Event();
              legacy.id = id;
              legacy.name = name;
              legacy.occurredAt = Instant.parse("2026-07-31T12:46:03Z");
              legacy.persist();
            });

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event", hasKey("publisher"))
        .body("event.publisher", nullValue());
    given()
        .queryParam("name", name)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.id", contains(id))
        .body("events[0]", hasKey("publisher"))
        .body("events[0].publisher", nullValue());

    // ... and the replay comparison is unchanged: a stamped PUT of that id is a reused UUID.
    put(id, stamped(name, "2026-07-31T12:46:03Z", null, CI))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
  }

  @Test
  void reAttributingOneIdIsFourHundred() {
    // publisher is INSIDE the comparison, like environment: one id dispatched by two services is
    // two different claims about history. (The null → set direction only exists for rows stored
    // before publishers were required — see the test above.)
    String id = UUID.randomUUID().toString();
    put(id, stamped("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, CI))
        .then()
        .statusCode(201);
    put(
            id,
            stamped(
                "BuildSuccessful",
                "2026-07-31T12:46:03Z",
                PAYLOAD,
                "eu.wohlben.qits:qits-projects-service"))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
    // ... while the original bytes are still a replay.
    put(id, stamped("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, CI))
        .then()
        .statusCode(200)
        .body("event.publisher", equalTo(CI));
  }

  @Test
  void aPublisherLongerThanItsColumnIsFourHundred() {
    put(
            UUID.randomUUID().toString(),
            stamped("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, "x".repeat(256)))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
  }

  @Test
  void anEnvironmentThatIsNotADnsSafeNameIsFourHundred() {
    // Shape, not existence: whether 'dev' exists is deliberately never asked, but 'Dev' could never
    // have been an environment at all.
    put(
            UUID.randomUUID().toString(),
            envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, null, "Not A Slug"))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
  }

  @Test
  void aParentThatIsNotAUuidIsFourHundred() {
    put(
            UUID.randomUUID().toString(),
            envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, "not-a-uuid"))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON);
    // UUID.fromString alone accepts this; the round-trip check is what does not.
    put(
            UUID.randomUUID().toString(),
            envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, "1-1-1-1-1"))
        .then()
        .statusCode(400);
  }

  @Test
  void anEventMayNotCauseItself() {
    // Decidable from a single row, with no graph to consult — malformed input in the same sense a
    // non-UUID is. Note what is NOT here: no cycle guard. One that caught only the self-edge would
    // be worse than none, because it cannot see A → B → A while telling a reader cycles are handled.
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, id))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
    given().when().get("/events/api/events/" + id).then().statusCode(404);
  }

  @Test
  void aParentThisLogHasNeverSeenIsAcceptedRatherThanRefused() {
    // Nothing orders a parent's arrival before its child's, and 400 is unretryable — so an
    // existence check would turn a publisher's timing accident into permanent data loss. A dangling
    // parent is data; the reader treats it as the start of the chain.
    String neverSeen = UUID.randomUUID().toString();
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD, neverSeen))
        .then()
        .statusCode(201)
        .body("event.parentId", equalTo(neverSeen));
    given().when().get("/events/api/events/" + neverSeen).then().statusCode(404);
  }
}
