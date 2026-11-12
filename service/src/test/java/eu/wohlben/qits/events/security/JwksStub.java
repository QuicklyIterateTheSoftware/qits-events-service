package eu.wohlben.qits.events.security;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the idp's key endpoint. It serves {@code GET /idp/jwks} with the suite's
 * verification key under {@link BearerTokens#KEY_ID}, counts the key fetches, and serves nothing
 * else: a discovery request would be a 404 and fail the tenant (qits-2160 turned discovery off).
 *
 * <p>It moves only {@code auth-server-url} (and turns the tenant on), and names the {@code
 * localhost} domain as {@code QITS_DOMAIN}, so the shipped {@code token.issuer} expression resolves
 * to {@link BearerTokens#ISSUER}. So the key path under test is the one that ships: no discovery,
 * the shipped jwks path, issuer and audience, and keys fetched by {@code kid} when a token needs
 * one.
 */
public class JwksStub implements QuarkusTestResourceLifecycleManager {

  private static final AtomicInteger FETCHES = new AtomicInteger();

  private HttpServer server;

  /** How many times the service has fetched the keys (the JWKS) since this stub started. */
  static int fetches() {
    return FETCHES.get();
  }

  @Override
  public Map<String, String> start() {
    FETCHES.set(0);
    byte[] body = jwks().getBytes(StandardCharsets.UTF_8);
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException cannotBind) {
      throw new UncheckedIOException(cannotBind);
    }
    String idp =
        "http://"
            + InetAddress.getLoopbackAddress().getHostAddress()
            + ":"
            + server.getAddress().getPort()
            + "/idp";
    server.createContext(
        "/idp/jwks",
        exchange -> {
          FETCHES.incrementAndGet();
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    return Map.of(
        "quarkus.oidc.tenant-enabled", "true",
        "quarkus.oidc.auth-server-url", idp,
        "QITS_DOMAIN", "localhost");
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** The verification key as a one-key JWKS, the shape qits-platform-idp publishes. */
  private static String jwks() {
    try {
      RSAPublicKey key =
          (RSAPublicKey)
              KeyFactory.getInstance("RSA")
                  .generatePublic(
                      new X509EncodedKeySpec(
                          Base64.getDecoder().decode(BearerTokens.verificationKey())));
      return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
          + BearerTokens.KEY_ID
          + "\",\"n\":\""
          + base64Url(key.getModulus())
          + "\",\"e\":\""
          + base64Url(key.getPublicExponent())
          + "\"}]}";
    } catch (Exception e) {
      throw new IllegalStateException("Cannot build the test JWKS", e);
    }
  }

  /** Unsigned big-endian bytes, base64url without padding, as a JWK spells a number. */
  private static String base64Url(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
