package eu.wohlben.qits.events.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.junit.jupiter.api.Test;

/**
 * This service's pact with qits-idp-service (ticket qits-1149). One of its calls to the idp is
 * quarkus-oidc's: the JWKS, {@link #JWKS}, at the idp's internal address. Discovery is off
 * (qits-2160: the tenant must not need the edge, so it reads no discovery document and names the
 * issuer itself), and the tenant is lazy ({@code jwks.resolve-early=false}), so the trigger is the
 * first authenticated request.
 *
 * <p>The other is the client-credentials mint of the named client {@code qits} (epic qits-428),
 * {@link #MINT}: {@code POST /idp/token} with HTTP Basic, {@code grant_type=client_credentials} and
 * {@code audience=qits-platform}, recorded by qits-idp as {@code issueToken} in {@value
 * #A_SERVICE_CLIENT_CALLING_ITS_PEERS}. qits-telemetry-core's exporters spend the token on every
 * OTLP export, so those exports are the row's trigger. {@link IdpTokenMintPactTest} runs it through
 * the real oidc client, because the client only exists inside the application.
 *
 * <p>The JWKS row's call reads the answer the way quarkus-oidc does: the keys as a jose4j {@link
 * JsonWebKeySet}.
 */
class IdpConsumerPactTest {

  static final String CONSUMER = "qits-events-service";

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final Trigger FIRST_BEARER = Trigger.operation("the first authenticated request");

  static final String STATE = "a published signing key";

  static final GoldenInteraction JWKS =
      GoldenInteraction.of(FIRST_BEARER, STATE, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  /** The qits-idp state that records the client-credentials mint of a service client. */
  static final String A_SERVICE_CLIENT_CALLING_ITS_PEERS = "a service client calling its peers";

  /** The mint, caused by whichever OTLP export needs a bearer first. */
  static final GoldenInteraction MINT =
      GoldenInteraction.of(
              Trigger.of("call", "call", "qits-telemetry-core's BearerHeaders (every OTLP export)"),
              A_SERVICE_CLIENT_CALLING_ITS_PEERS,
              "issueToken")
          .header("Authorization", "{authorization}")
          .consumes("access_token", "expires_in");

  static final ConsumerPact PACT = ConsumerPact.of(CONSUMER, IDP, JWKS, MINT);

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Test
  void theKeysParseAsRsaSigningKeys() {
    PACT.run(
        JWKS,
        (url, recorded) -> {
          JsonWebKeySet set = new JsonWebKeySet(get(url + "/idp/jwks"));
          assertFalse(set.getJsonWebKeys().isEmpty(), "at least one key");
          for (JsonWebKey key : set.getJsonWebKeys()) {
            assertNotNull(key.getKeyId());
            assertEquals("sig", key.getUse());
            assertNotNull(key.getAlgorithm());
            assertTrue(key instanceof RsaJsonWebKey, "an RSA key");
            assertNotNull(((RsaJsonWebKey) key).getRsaPublicKey());
            assertTrue(key.getKey() instanceof RSAPublicKey);
          }
        });
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PACT.assertEveryInteractionCarriesBothReferences();
  }

  /** {@code pacts/qits-events-service_qits-idp-service.json}. */
  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  private static String get(String url) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), url);
    return response.body();
  }
}
