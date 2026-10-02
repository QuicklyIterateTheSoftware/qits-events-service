package eu.wohlben.qits.events.contracts;

import eu.wohlben.qits.events.control.EventService;
import eu.wohlben.qits.events.entity.Event;
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

  /** What a state hands back: its parameters, keys sorted, and its unique tokens (none here). */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  private static final Instant FUTURE = Instant.parse("2100-01-01T00:00:00Z");

  @Inject EventService events;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();
  private final List<String> created = Collections.synchronizedList(new ArrayList<>());

  public ProviderStates() {
    states.put(A_FEW_RECENT_EVENTS, this::aFewRecentEvents);
    states.put(NO_EVENTS, this::noEvents);
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

  /** Deletes every event a state created since the last clean-up. */
  public void cleanUp() {
    List<String> ids;
    synchronized (created) {
      ids = List.copyOf(created);
      created.clear();
    }
    ids.forEach(events::delete);
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

  private Event create(
      String name, Instant occurredAt, String payload, String description, String parentId) {
    Event event = events.create(name, occurredAt, payload, description, parentId, null);
    created.add(event.id);
    return event;
  }
}
