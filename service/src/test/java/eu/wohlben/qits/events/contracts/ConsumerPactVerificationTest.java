package eu.wohlben.qits.events.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running provider</b>, the way qits-projects-service
 * does.
 *
 * <p>The pacts come off the test classpath: each consumer publishes its pact as a jar holding
 * {@code pacts/<consumer>_qits-events-service.json} (repository names on both sides), this repo
 * pins that jar as a test dependency, and qits-maintenance bumps the pin when the consumer releases
 * a changed pact. {@link ClasspathPactLoader} finds them all.
 *
 * <p><b>All nine consumers pin a pact now</b> (ticket qits-1163, "library-made calls pacted per
 * consuming service"). Each of the nine — {@code qits-ci-service}, {@code qits-githost-service},
 * {@code qits-deployments-service}, {@code qits-configuration-service}, {@code qits-edge-service},
 * {@code qits-maintenance-service}, {@code qits-orchestrator-service}, {@code qits-projects-service}
 * and {@code qits-workspaces-service} — commits {@code
 * pacts/<service>_qits-eventstream-javalib_qits-events-service.json}: the library's calls on its
 * behalf, written by its kit {@code qits-eventstream-contracts} with {@code
 * comments.references.qits-via} naming the library. {@code qits-ci-service}'s earlier qits-1149 pin
 * (its own {@code pacts/<consumer>_qits-events-service.json}, not through the library) is retired by
 * this one, and {@code qits-projects-service}'s qits-1149 pin is likewise retired and replaced by its
 * pin through the library.
 *
 * <p><b>The two CLIs pin their own pacts beside them</b> (ticket qits-1149): {@code
 * qits-bootstrap-cli} and {@code qits-platform-access-cli} call this provider directly, not through
 * the library, so each commits {@code pacts/<cli>_qits-events-service.json} — the bootstrap head
 * poll ({@code the newest event}) and the checkout-daemon's newest-SCMRelease lookup ({@code a
 * repository with a release}, skipped as {@link #KNOWN_WRONG} at 2026.1019.65035).
 *
 * <p>Eleven pins are already enough to keep {@link ClasspathPactLoader#REQUIRED} true and this class
 * free of {@code @IgnoreNoPactsToVerify}: finding nothing on the classpath stays an error.
 *
 * <p><b>Every interaction starts from an empty log</b> ({@link ProviderStates#emptyTheLog}), so
 * {@code no events} really is no events and a catch-up from the start reads the state's events,
 * not rows another test class left in the shared database.
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP, as the
 * {@code %test} synthetic user — exactly as {@link GoldenMasterRecordingTest}'s calls run. Every
 * {@code @State} method delegates to {@link ProviderStates}; {@link #target} fails an unknown state,
 * and an interaction without {@code comments.references.qits-call} or {@code qits-trigger}.
 *
 * <p><b>A pinned pact that is wrong is named in {@link #KNOWN_WRONG}, by its jar's version</b>, and
 * its interaction is reported as skipped with the reason — never answered by bending the provider
 * to it (the pattern of qits-maintenance-service). The entry is for that version alone: the bump
 * that carries the consumer's fix verifies the interaction for real, and a bump that does not fix
 * it goes red here, which is the point.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-events-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  /** A pinned pact interaction this provider cannot honour, and why: the consumer must fix it. */
  record KnownWrong(String consumer, String version, String description, String reason) {

    boolean names(Pact pact, Interaction interaction) {
      return consumer.equals(pact.getConsumer().getName())
          && description.equals(interaction.getDescription())
          && String.valueOf(pact.getSource()).contains("/" + version + "/");
    }
  }

  private static final String MULTI_VALUED_ATTR_FROZEN =
      "the request's attr query carries two values, repositoryname={repositoryName} and"
          + " projectid={projectId}, and qits-pact-consumer 2026.1018.164604 writes a ProviderState"
          + " generator only for a single-valued query parameter, so the pact sends the golden"
          + " master's frozen projectid 00000000-0000-4000-8000-000000000002 instead of the project"
          + " the state seeds; the honest answer for that project is no events. The fix is the"
          + " consumer's: generate each attr value from the state, or the row's query"
          + " (ticket qits-1149)";

  static final List<KnownWrong> KNOWN_WRONG =
      List.of(
          new KnownWrong(
              "qits-platform-access-cli",
              "2026.1019.65035",
              "qits checkout-daemon: listEvents",
              MULTI_VALUED_ATTR_FROZEN));

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    // Every interaction starts from an empty log: the state then IS the log, whatever the classes
    // that ran before this one left in the shared test database (see ProviderStates#emptyTheLog).
    // The @State method runs after this, so the wipe never deletes the state's own events.
    states.emptyTheLog();
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-events does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    for (KnownWrong wrong : KNOWN_WRONG) {
      if (wrong.names(pact, interaction)) {
        Assumptions.abort(
            consumer + " " + wrong.version() + " '" + interaction.getDescription() + "': "
                + wrong.reason());
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    context.verifyInteraction();
  }

  // --- the states: each one line into the registry -------------------------------------------

  @AfterEach
  void cleanUp() {
    states.cleanUp();
  }

  @State(ProviderStates.A_FEW_RECENT_EVENTS)
  Map<String, String> aFewRecentEvents() {
    return states.params(ProviderStates.A_FEW_RECENT_EVENTS);
  }

  @State(ProviderStates.NO_EVENTS)
  Map<String, String> noEvents() {
    return states.params(ProviderStates.NO_EVENTS);
  }

  @State(ProviderStates.THE_NEWEST_EVENT)
  Map<String, String> theNewestEvent() {
    return states.params(ProviderStates.THE_NEWEST_EVENT);
  }

  @State(ProviderStates.ONE_SOFTWARE_RELEASE_EVENT)
  Map<String, String> oneSoftwareReleaseEvent() {
    return states.params(ProviderStates.ONE_SOFTWARE_RELEASE_EVENT);
  }

  @State(ProviderStates.ONE_DEPLOYMENT_ACTIVE_EVENT)
  Map<String, String> oneDeploymentActiveEvent() {
    return states.params(ProviderStates.ONE_DEPLOYMENT_ACTIVE_EVENT);
  }

  @State(ProviderStates.ONE_SCM_RELEASE_EVENT)
  Map<String, String> oneScmReleaseEvent() {
    return states.params(ProviderStates.ONE_SCM_RELEASE_EVENT);
  }

  @State(ProviderStates.EVENTS_TO_CATCH_UP_ON)
  Map<String, String> eventsToCatchUpOn() {
    return states.params(ProviderStates.EVENTS_TO_CATCH_UP_ON);
  }

  @State(ProviderStates.SOFTWARE_RELEASES_TO_CATCH_UP_ON)
  Map<String, String> softwareReleasesToCatchUpOn() {
    return states.params(ProviderStates.SOFTWARE_RELEASES_TO_CATCH_UP_ON);
  }

  @State(ProviderStates.DEPLOYMENTS_TO_CATCH_UP_ON)
  Map<String, String> deploymentsToCatchUpOn() {
    return states.params(ProviderStates.DEPLOYMENTS_TO_CATCH_UP_ON);
  }

  @State(ProviderStates.SCM_RELEASES_TO_CATCH_UP_ON)
  Map<String, String> scmReleasesToCatchUpOn() {
    return states.params(ProviderStates.SCM_RELEASES_TO_CATCH_UP_ON);
  }

  @State(ProviderStates.PROJECT_LIFECYCLE_EVENTS)
  Map<String, String> projectLifecycleEvents() {
    return states.params(ProviderStates.PROJECT_LIFECYCLE_EVENTS);
  }

  @State(ProviderStates.MORE_THAN_A_PAGE_OF_DEPLOYMENTS)
  Map<String, String> moreThanAPageOfDeployments() {
    return states.params(ProviderStates.MORE_THAN_A_PAGE_OF_DEPLOYMENTS);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_A_RELEASE)
  Map<String, String> aRepositoryWithARelease() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_A_RELEASE);
  }

  @State(ProviderStates.NO_EVENT_WITH_THE_GIVEN_ID)
  Map<String, String> noEventWithTheGivenId() {
    return states.params(ProviderStates.NO_EVENT_WITH_THE_GIVEN_ID);
  }

  @State(ProviderStates.AN_EVENT_WITH_THE_GIVEN_ID)
  Map<String, String> anEventWithTheGivenId() {
    return states.params(ProviderStates.AN_EVENT_WITH_THE_GIVEN_ID);
  }

  @State(ProviderStates.THE_SAME_EVENT_PUBLISHED_BEFORE)
  Map<String, String> theSameEventPublishedBefore() {
    return states.params(ProviderStates.THE_SAME_EVENT_PUBLISHED_BEFORE);
  }
}
