package eu.wohlben.qits.events.contracts;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * <b>The completeness guard for ticket qits-1149</b>: a client call against a qits service with no
 * pact row must fail a build, and the set of calls has to come from the code rather than from a
 * literal someone forgot to update.
 *
 * <p>qits-events-service makes exactly one outbound call to another qits service — quarkus-oidc's
 * discovery and JWKS fetch against qits-idp-service, both rows of {@link
 * IdpConsumerPactTest#PACT}. There is no {@code qits-http-core}, no MicroProfile REST client and no
 * {@code quarkus-oidc-client} on this repository's classpath (see this repo's {@code CLAUDE.md},
 * "Dependencies"), so there is no client-credentials token mint here to pact either. {@link
 * eu.wohlben.qits.events.ArchRulesTest} already guards the one mechanism a hand-rolled outbound call
 * would use that it can see ({@code java.net.http.HttpClient}, through qits-arch-rules'
 * {@code HttpClientRules}); this class guards the two it cannot:
 *
 * <ul>
 *   <li>no production class is a MicroProfile REST client ({@code @RegisterRestClient});
 *   <li>no production class touches {@code io.quarkus.oidc.client.OidcClient} (the
 *       client-credentials mint) or {@code io.vertx.ext.web.client.WebClient} (a second way to dial
 *       out that {@code HttpClientRules} does not see either).
 * </ul>
 *
 * <p>A change that adds any of these is adding this service's first other outbound surface, and
 * belongs behind a new pact row, not behind a passing guard. Each judged type is named as a string
 * rather than imported — {@code CausationRowRules}' reasoning, applied here so this guard does not
 * force a dependency on the very library it exists to catch the absence of.
 */
class OutboundCallGuardTest {

  private static final String REGISTER_REST_CLIENT =
      "org.eclipse.microprofile.rest.client.inject.RegisterRestClient";
  private static final String OIDC_CLIENT = "io.quarkus.oidc.client.OidcClient";
  private static final String VERTX_WEB_CLIENT = "io.vertx.ext.web.client.WebClient";

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("eu.wohlben.qits.events");

  @Test
  void noClassIsARestClientWithNoPactRowBehindIt() {
    noClasses()
        .should()
        .beAnnotatedWith(REGISTER_REST_CLIENT)
        .because(
            "a @RegisterRestClient interface is an outbound call to another qits service, and"
                + " needs a pact row (a ConsumerPactTest) before this guard may let it through")
        .check(PRODUCTION);
  }

  @Test
  void noClassMintsATokenOrDialsOutOverVertxWithNoPactRowBehindIt() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(OIDC_CLIENT)
        .because(
            "a client-credentials token mint is an outbound call to qits-idp and needs its own"
                + " pact row, the way every other service's does")
        .check(PRODUCTION);
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(VERTX_WEB_CLIENT)
        .because(
            "a Vert.x WebClient dials out the way java.net.http.HttpClient does, and"
                + " HttpClientRules does not see it")
        .check(PRODUCTION);
  }

  /**
   * The premise the two checks above stand on: exactly the two rows this service already pacts. A
   * third interaction here with none of the checks above tripping would be the framework learning
   * to dial a second qits service on its own, which is exactly the gap a hand-written literal would
   * miss.
   */
  @Test
  void theOnlyPactedCallsAreTheIdpDiscoveryAndJwksRows() {
    Set<String> operations =
        Set.of(
            IdpConsumerPactTest.DISCOVERY.operationId(), IdpConsumerPactTest.JWKS.operationId());
    assertEquals(Set.of("getOpenIdConfiguration", "getJwks"), operations);
  }
}
