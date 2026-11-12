package eu.wohlben.qits.events.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.events.api.EventController;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The token check as the DEPLOYED process configures it (qits-2160): the default-profile lines of
 * the shipped {@code application.properties}, with no {@code %test} line in play. The suites turn
 * the tenant off or replace its server, so this is the only place the shipped address is under
 * test.
 *
 * <p>The edge's readiness waits on catching up from this service, so the tenant must reach the idp
 * at its wire alias, never at its edge host (exception E1, qits-1403): no discovery, the keys at
 * {@code <alias>/idp/jwks}, and the issuer named outright as the public name the idp stamps. That
 * issuer has no default domain, so a container without {@code QITS_DOMAIN} refuses to boot.
 */
class ShippedOidcTenantTest {

  @Test
  void theTenantDialsTheIdpsWireAliasNotTheEdge() {
    Properties shipped = shipped();
    String server = shipped.getProperty("quarkus.oidc.auth-server-url");
    assertEquals("http://${QITS_ENVIRONMENT:dev}-qits-idp:8080/idp", server);
    assertFalse(server.contains("QITS_DOMAIN"), "the edge host would deadlock boot: " + server);
  }

  @Test
  void theKeysComeFromTheJwksPathWithNoDiscovery() {
    Properties shipped = shipped();
    assertEquals("false", shipped.getProperty("quarkus.oidc.discovery-enabled"));
    assertEquals("jwks", shipped.getProperty("quarkus.oidc.jwks-path"));
  }

  @Test
  void theIssuerIsThePublicNameTheIdpStamps() {
    assertEquals(
        "https://idp.qits.${QITS_DOMAIN}", shipped().getProperty("quarkus.oidc.token.issuer"));
    SmallRyeConfig config = prod(Map.of("QITS_DOMAIN", "example.test"));
    assertEquals(
        "https://idp.qits.example.test",
        config.getValue("quarkus.oidc.token.issuer", String.class));
    assertEquals(
        "http://dev-qits-idp:8080/idp",
        config.getValue("quarkus.oidc.auth-server-url", String.class));
  }

  @Test
  void withoutTheDomainTheIssuerDoesNotResolve() {
    SmallRyeConfig config = prod(Map.of());
    assertThrows(
        NoSuchElementException.class,
        () -> config.getValue("quarkus.oidc.token.issuer", String.class));
  }

  /** The shipped file under the prod profile, with {@code environment} above it. */
  private static SmallRyeConfig prod(Map<String, String> environment) {
    try {
      return new SmallRyeConfigBuilder()
          .withProfile("prod")
          .withSources(new PropertiesConfigSource(shippedUrl(), 250))
          .withSources(new PropertiesConfigSource(environment, "environment", 300))
          .build();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Properties shipped() {
    Properties properties = new Properties();
    try (InputStream in = shippedUrl().openStream()) {
      properties.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return properties;
  }

  /**
   * The copy in {@code target/classes}, located through a main class: the test resources carry an
   * {@code application.properties} of their own, which a plain classpath lookup would find first.
   */
  private static URL shippedUrl() {
    try {
      URI classes =
          EventController.class.getProtectionDomain().getCodeSource().getLocation().toURI();
      return classes.resolve("application.properties").toURL();
    } catch (MalformedURLException | URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
