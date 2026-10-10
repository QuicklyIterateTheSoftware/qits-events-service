package eu.wohlben.qits.events.contracts;

import eu.wohlben.qits.events.control.EventService;
import eu.wohlben.qits.events.entity.Event;
import eu.wohlben.qits.events.error.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>qits-events' provider states</b> (epic qits-112): each seeds the events one consumer
 * situation needs and hands back their ids as parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before recording each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 * Both call {@link #cleanUp()} afterwards: the events table is shared by every test in the run, and
 * a list answer must not carry another state's leftovers.
 *
 * <p><b>The events lie in the year 2100</b>, so they are the newest rows whatever else the test
 * database holds, and a list of the newest 20 always contains them. The recorder freezes every
 * instant anyway, so the year never reaches a golden master.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_FEW_RECENT_EVENTS = "a few recent events";
  public static final String NO_EVENTS = "no events";
  public static final String THE_NEWEST_EVENT = "the newest event";
  public static final String ONE_SOFTWARE_RELEASE_EVENT = "one SoftwareRelease event";
  public static final String ONE_DEPLOYMENT_ACTIVE_EVENT = "one DeploymentActive event";
  public static final String ONE_SCM_RELEASE_EVENT = "one SCMRelease event";
  public static final String EVENTS_TO_CATCH_UP_ON = "events to catch up on";
  public static final String SOFTWARE_RELEASES_TO_CATCH_UP_ON =
      "SoftwareRelease events to catch up on";
  public static final String DEPLOYMENTS_TO_CATCH_UP_ON = "DeploymentActive events to catch up on";
  public static final String SCM_RELEASES_TO_CATCH_UP_ON = "SCMRelease events to catch up on";
  public static final String PROJECT_LIFECYCLE_EVENTS = "project lifecycle events";
  public static final String MORE_THAN_A_PAGE_OF_DEPLOYMENTS =
      "more than a page of DeploymentActive events";

  /** The library's catch-up page size: the state seeds one event more. */
  public static final int PAGE = 200;
  public static final String A_REPOSITORY_WITH_A_RELEASE = "a repository with a release";
  public static final String NO_EVENT_WITH_THE_GIVEN_ID = "no event with the given id";
  public static final String AN_EVENT_WITH_THE_GIVEN_ID = "an event with the given id";
  public static final String THE_SAME_EVENT_PUBLISHED_BEFORE = "the same event published before";

  /**
   * The cursor a catch-up page resumes after: the instant just before every event a state seeds,
   * and the lowest id. Paging forward from it reads exactly the state's own events, whatever older
   * rows the shared test database holds.
   */
  public static final String CURSOR_BEFORE_THE_STATE =
      "2100-01-01T00:00:00Z,00000000-0000-0000-0000-000000000000";

  /** The envelope every publish state sends; {@code {eventId}} is the state's param. */
  public static final String PUBLISHED_ENVELOPE =
      "{\"name\":\"DeploymentActive\",\"occurredAt\":\"2026-01-01T00:00:00Z\","
          + "\"payload\":\"{\\\"application\\\":\\\"qits-eventstream-pact\\\"}\","
          + "\"description\":null,\"parentId\":null,\"environment\":null}";

  /** The payload {@link #PUBLISHED_ENVELOPE} carries. */
  private static final String PUBLISHED_PAYLOAD = "{\"application\":\"qits-eventstream-pact\"}";

  /** What a state hands back: its parameters, keys sorted, and its unique tokens (none here). */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  private static final Instant FUTURE = Instant.parse("2100-01-01T00:00:00Z");

  @Inject EventService events;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();
  private final List<String> created = Collections.synchronizedList(new ArrayList<>());

  public ProviderStates() {
    states.put(A_FEW_RECENT_EVENTS, this::aFewRecentEvents);
    states.put(NO_EVENTS, this::noEvents);
    states.put(THE_NEWEST_EVENT, this::theNewestEvent);
    states.put(ONE_SOFTWARE_RELEASE_EVENT, () -> oneOf("SoftwareRelease", SOFTWARE_RELEASE));
    states.put(ONE_DEPLOYMENT_ACTIVE_EVENT, () -> oneOf("DeploymentActive", DEPLOYMENT_ACTIVE));
    states.put(ONE_SCM_RELEASE_EVENT, () -> oneOf("SCMRelease", SCM_RELEASE));
    states.put(EVENTS_TO_CATCH_UP_ON, this::eventsToCatchUpOn);
    states.put(SOFTWARE_RELEASES_TO_CATCH_UP_ON, () -> twoOf("SoftwareRelease", SOFTWARE_RELEASE));
    states.put(DEPLOYMENTS_TO_CATCH_UP_ON, () -> twoOf("DeploymentActive", DEPLOYMENT_ACTIVE));
    states.put(SCM_RELEASES_TO_CATCH_UP_ON, () -> twoOf("SCMRelease", SCM_RELEASE));
    states.put(PROJECT_LIFECYCLE_EVENTS, this::projectLifecycleEvents);
    states.put(MORE_THAN_A_PAGE_OF_DEPLOYMENTS, this::moreThanAPageOfDeployments);
    states.put(A_REPOSITORY_WITH_A_RELEASE, this::aRepositoryWithARelease);
    states.put(NO_EVENT_WITH_THE_GIVEN_ID, this::noEventWithTheGivenId);
    states.put(AN_EVENT_WITH_THE_GIVEN_ID, this::anEventWithTheGivenId);
    states.put(THE_SAME_EVENT_PUBLISHED_BEFORE, this::theSameEventPublishedBefore);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /**
   * Deletes every event a state created since the last clean-up, and every event a publish state
   * expected the request to create. An id that is not there (a publish that was refused) is skipped.
   */
  public void cleanUp() {
    List<String> ids;
    synchronized (created) {
      ids = List.copyOf(created);
      created.clear();
    }
    for (String id : ids) {
      try {
        events.delete(id);
      } catch (NotFoundException absent) {
        // nothing to remove
      }
    }
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /**
   * Three events of three names, newest first: a ticket reported, a release request it caused
   * (the parent/child pair), and a deployment.
   */
  private Setup aFewRecentEvents() {
    Event ticket =
        create(
            "TicketReported",
            FUTURE.plusSeconds(1),
            "{\"ticket\":\"qits-1\"}",
            "Ticket qits-1 was reported",
            null);
    Event request =
        create(
            "ReleaseRequestChanged",
            FUTURE.plusSeconds(2),
            "{\"state\":\"PENDING\"}",
            "A release request is waiting for its checks",
            ticket.id);
    Event deployment =
        create(
            "DeploymentActive",
            FUTURE.plusSeconds(3),
            "{\"application\":\"qits-projects\"}",
            "qits-projects is deployed",
            null);
    Map<String, String> params = new TreeMap<>();
    params.put("deploymentEventId", deployment.id);
    params.put("requestEventId", request.id);
    params.put("ticketEventId", ticket.id);
    return new Setup(params, List.of());
  }

  /** Nothing of this state's own: the list it answers holds no event a state created. */
  private Setup noEvents() {
    return new Setup(Map.of(), List.of());
  }

  /** Events in time order: {@code first} is the older one, {@code second} the newest. */
  private Setup twoOf(String name, List<String> payloads) {
    Event first = create(name, FUTURE.plusSeconds(1), payloads.get(0), null, null, "dev");
    Event second = create(name, FUTURE.plusSeconds(2), payloads.get(1), null, null, "dev");
    return new Setup(new TreeMap<>(Map.of("firstEventId", first.id, "secondEventId", second.id)),
        List.of());
  }

  /** Two events of different names; the newest is a {@code DeploymentActive}. */
  private Setup theNewestEvent() {
    Event first =
        create("SoftwareRelease", FUTURE.plusSeconds(1), SOFTWARE_RELEASE.get(0), null, null, "dev");
    Event second =
        create(
            "DeploymentActive", FUTURE.plusSeconds(2), DEPLOYMENT_ACTIVE.get(0), null, null, "dev");
    return new Setup(new TreeMap<>(Map.of("firstEventId", first.id, "secondEventId", second.id)),
        List.of());
  }

  /**
   * Three events of three names, oldest first, the second caused by the first: what a listener of
   * every name pages through.
   */
  private Setup eventsToCatchUpOn() {
    Event release =
        create(
            "SCMRelease",
            FUTURE.plusSeconds(1),
            SCM_RELEASE.get(0),
            "qits-ci-service 2026.101.100000 is released",
            null,
            "dev");
    Event software =
        create(
            "SoftwareRelease",
            FUTURE.plusSeconds(2),
            SOFTWARE_RELEASE.get(0),
            "qits/qits-ci 2026.101.100000 is published",
            release.id,
            "dev");
    Event deployment =
        create(
            "DeploymentActive",
            FUTURE.plusSeconds(3),
            DEPLOYMENT_ACTIVE.get(0),
            "qits-ci is deployed",
            software.id,
            "dev");
    Map<String, String> params = new TreeMap<>();
    params.put("firstEventId", release.id);
    params.put("secondEventId", software.id);
    params.put("thirdEventId", deployment.id);
    return new Setup(params, List.of());
  }

  /** One project created and then changed, and another one deleted, oldest first. */
  private Setup projectLifecycleEvents() {
    String projectId = UUID.randomUUID().toString();
    String deletedProjectId = UUID.randomUUID().toString();
    Event createdEvent =
        create(
            "ProjectCreated",
            FUTURE.plusSeconds(1),
            "{\"createdAt\":\"2026-01-01T00:00:00Z\",\"projectId\":\""
                + projectId
                + "\",\"projectName\":\"Demo\",\"slug\":\"demo\",\"supportsEnvironments\":false}",
            null,
            null,
            "dev");
    Event changedEvent =
        create(
            "ProjectChanged",
            FUTURE.plusSeconds(2),
            "{\"changedAt\":\"2026-01-01T00:00:00Z\",\"projectId\":\""
                + projectId
                + "\",\"projectName\":\"Demo\",\"slug\":\"demo\",\"supportsEnvironments\":true}",
            null,
            null,
            "dev");
    Event deletedEvent =
        create(
            "ProjectDeleted",
            FUTURE.plusSeconds(3),
            "{\"deletedAt\":\"2026-01-01T00:00:00Z\",\"projectId\":\""
                + deletedProjectId
                + "\",\"slug\":\"old\"}",
            null,
            null,
            "dev");
    Map<String, String> params = new TreeMap<>();
    params.put("changedEventId", changedEvent.id);
    params.put("createdEventId", createdEvent.id);
    params.put("deletedEventId", deletedEvent.id);
    params.put("deletedProjectId", deletedProjectId);
    params.put("projectId", projectId);
    return new Setup(params, List.of());
  }

  /** One event of the name: the newest of it, and the only one. */
  private Setup oneOf(String name, List<String> payloads) {
    Event event = create(name, FUTURE.plusSeconds(1), payloads.get(0), null, null, "dev");
    return new Setup(Map.of("eventId", event.id), List.of());
  }

  /**
   * {@value #PAGE} + 1 deployments, one second apart: a page of {@value #PAGE} holds all but the
   * last and names the 200th as {@code nextCursor}. No params — the answer is recorded whole, since
   * the state's events are the only {@code DeploymentActive} events the test database holds.
   */
  private Setup moreThanAPageOfDeployments() {
    for (int i = 1; i <= PAGE + 1; i++) {
      create(
          "DeploymentActive",
          FUTURE.plusSeconds(i),
          deploymentActive("2026.101." + (100000 + i)),
          null,
          null,
          "dev");
    }
    return new Setup(Map.of(), List.of());
  }

  /**
   * Two releases of {@code qits-ci-service} in one project and, newer than both, a release of
   * another repository. The newest release of the repository is the second one.
   */
  private Setup aRepositoryWithARelease() {
    String projectId = UUID.randomUUID().toString();
    Event older =
        create(
            "SCMRelease",
            FUTURE.plusSeconds(1),
            scmRelease(projectId, "qits-ci-service", "2026.101.100000", SHA_1),
            null,
            null,
            "dev");
    Event newest =
        create(
            "SCMRelease",
            FUTURE.plusSeconds(2),
            scmRelease(projectId, "qits-ci-service", "2026.102.100000", SHA_2),
            null,
            null,
            "dev");
    create(
        "SCMRelease",
        FUTURE.plusSeconds(3),
        scmRelease(projectId, "qits-ci-frontend", "2026.103.100000", SHA_1),
        null,
        null,
        "dev");
    Map<String, String> params = new TreeMap<>();
    params.put("olderEventId", older.id);
    params.put("projectId", projectId);
    params.put("releaseEventId", newest.id);
    params.put("repositoryName", "qits-ci-service");
    return new Setup(params, List.of());
  }

  /** A fresh id the log does not hold; the publish creates it, so it is cleaned up after. */
  private Setup noEventWithTheGivenId() {
    String eventId = UUID.randomUUID().toString();
    created.add(eventId);
    return new Setup(Map.of("eventId", eventId), List.of());
  }

  /** The id is taken by an event of other content: the publish is a reused id. */
  private Setup anEventWithTheGivenId() {
    String eventId = UUID.randomUUID().toString();
    events.publish(
        eventId,
        "DeploymentActive",
        Instant.parse("2026-01-01T00:00:00Z"),
        "{\"application\":\"another-application\"}",
        null,
        null,
        null);
    created.add(eventId);
    return new Setup(Map.of("eventId", eventId), List.of());
  }

  /** The id holds the very event {@link #PUBLISHED_ENVELOPE} sends: the publish is a replay. */
  private Setup theSameEventPublishedBefore() {
    String eventId = UUID.randomUUID().toString();
    events.publish(
        eventId,
        "DeploymentActive",
        Instant.parse("2026-01-01T00:00:00Z"),
        PUBLISHED_PAYLOAD,
        null,
        null,
        null);
    created.add(eventId);
    return new Setup(Map.of("eventId", eventId), List.of());
  }

  // --- payloads: canonical JSON (keys sorted, no nulls), as qits-eventstream publishes them -----

  private static final String SHA_1 = "0123456789abcdef0123456789abcdef01234567";
  private static final String SHA_2 = "89abcdef0123456789abcdef0123456789abcdef";

  private static String softwareRelease(String version) {
    return "{\"packageName\":\"qits/qits-ci\",\"packageType\":\"docker\","
        + "\"repository\":\"qits-ci-service\",\"version\":\""
        + version
        + "\"}";
  }

  private static final List<String> SOFTWARE_RELEASE =
      List.of(
          softwareRelease("2026.101.100000"),
          softwareRelease("2026.102.100000"),
          softwareRelease("2026.103.100000"));

  private static String deploymentActive(String version) {
    return "{\"applicationName\":\"qits-ci\",\"browserHost\":\"ci.qits.example\","
        + "\"endpoints\":[{\"path\":\"/ci\",\"upstreamHost\":\"dev-qits-ci\",\"upstreamPort\":8080}],"
        + "\"environmentName\":\"dev\","
        + "\"navigation\":[{\"label\":\"CI\",\"position\":10,\"slot\":\"main\",\"subpath\":\"/\"}],"
        + "\"version\":\""
        + version
        + "\"}";
  }

  private static final List<String> DEPLOYMENT_ACTIVE =
      List.of(deploymentActive("2026.101.100000"), deploymentActive("2026.102.100000"));

  private static String scmRelease(
      String projectId, String repository, String version, String commitSha) {
    return "{\"branch\":\"main\",\"commitSha\":\""
        + commitSha
        + "\",\"projectId\":\""
        + projectId
        + "\",\"repository\":\""
        + repository
        + "\",\"repositoryName\":\""
        + repository
        + "\",\"version\":\""
        + version
        + "\"}";
  }

  /** SCMRelease payloads with no project id: the project's id is a param only where one is asked. */
  private static final List<String> SCM_RELEASE =
      List.of(
          "{\"branch\":\"main\",\"commitSha\":\"" + SHA_1
              + "\",\"repository\":\"qits-ci-service\",\"repositoryName\":\"qits-ci-service\","
              + "\"version\":\"2026.101.100000\"}",
          "{\"branch\":\"main\",\"commitSha\":\"" + SHA_2
              + "\",\"repository\":\"qits-ci-service\",\"repositoryName\":\"qits-ci-service\","
              + "\"version\":\"2026.102.100000\"}");

  private Event create(
      String name, Instant occurredAt, String payload, String description, String parentId) {
    return create(name, occurredAt, payload, description, parentId, null);
  }

  private Event create(
      String name,
      Instant occurredAt,
      String payload,
      String description,
      String parentId,
      String environment) {
    Event event = events.create(name, occurredAt, payload, description, parentId, environment);
    created.add(event.id);
    return event;
  }
}
