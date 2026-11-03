package eu.wohlben.qits.events.telemetry;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/**
 * An offline OTLP/HTTP receiver on an ephemeral loopback port, and the only thing this repo's
 * log-bridge proof trusts.
 *
 * <p>It exists because "the exporter posted something" is not the assertion that matters. What has
 * to be true is that an ordinary {@code org.jboss.logging.Logger} call becomes a real {@code
 * ExportLogsServiceRequest} carrying this service's identity, the record's severity, its body, its
 * throwable and its trace context — so the stub <b>decodes</b> the protobuf and hands the tests the
 * records themselves. A stub that only counted requests would keep passing against an exporter that
 * shipped empty batches.
 *
 * <p>Wired in as {@code qits.telemetry.endpoint} — qits-telemetry-core's exporters, which replaced
 * Quarkus' own (epic qits-428) — with qits' own non-default path prefix on it, because that prefix
 * is part of what is under test: the exporter appends {@code /v1/logs} to the base, and a receiver
 * mounted under a path is the arrangement the deployment actually has.
 *
 * <p><b>It is the idp too.</b> Those exporters attach the {@code qits} oidc client's bearer on every
 * export, because the edge in front of the real receiver refuses an anonymous POST. So the stub also
 * answers qits-idp's client-credentials mint ({@code POST /idp/token}) with {@value #TOKEN}, the
 * client is switched on and pointed at it, and every log export's {@code Authorization} header is
 * kept for {@link #authorizations()} — a proof that the records arrive which did not also prove the
 * bearer would be proving a path the deployment no longer has.
 *
 * <p><b>The bearer is asserted against the packaged artifact only</b> ({@code PackagedLogBridgeIT}).
 * The module's header supplier reads its config through {@code ConfigProvider.getConfig()} on the
 * JDK sender's own executor thread, and under {@code @QuarkusTest} that thread sees a config without
 * the test profile's overrides — the shipped {@code %test} values, client off and no {@code
 * QITS_DOMAIN} — so in the build JVM every export goes out bare (measured 2026-11-03). A launched
 * process has one config, which is the arrangement a deployment has.
 *
 * <p>It also turns the SDK back on. {@code application.properties} carries {@code
 * %test.quarkus.otel.sdk.disabled=true} so that an ordinary suite does not spend itself retrying
 * against an unresolvable {@code qits-observability}; the classes that use this stub are the ones
 * that need the exporter alive, and they say so here rather than by editing the shipped file.
 *
 * <p><b>No network, no docker, no collector</b> — the clone-alone rule. {@code
 * com.sun.net.httpserver} is in the JDK, and the port is 0.
 */
public class OtlpLogStub implements QuarkusTestResourceLifecycleManager {

  /** One exported log record, with the resource and scope it arrived under. */
  public record Captured(Resource resource, InstrumentationScope scope, LogRecord record) {

    /** A resource attribute — {@code service.name} and friends — as text, or empty. */
    public Optional<String> resourceAttribute(String key) {
      return text(resource.getAttributesList(), key);
    }

    /** A log-record attribute — {@code exception.type} and friends — as text, or empty. */
    public Optional<String> attribute(String key) {
      return text(record.getAttributesList(), key);
    }

    /** The record's body, which Quarkus fills with the formatted message. */
    public String body() {
      return record.getBody().getStringValue();
    }

    /** The trace id as lower-case hex, or all zeroes when the record carried no trace context. */
    public String traceId() {
      return hex(record.getTraceId().toByteArray());
    }

    /** The span id as lower-case hex, or all zeroes when the record carried no trace context. */
    public String spanId() {
      return hex(record.getSpanId().toByteArray());
    }

    private static Optional<String> text(List<KeyValue> attributes, String key) {
      return attributes.stream()
          .filter(kv -> kv.getKey().equals(key))
          .map(KeyValue::getValue)
          .map(OtlpLogStub::asText)
          .findFirst();
    }

    private static String hex(byte[] raw) {
      StringBuilder out = new StringBuilder(raw.length * 2);
      for (byte b : raw) {
        out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return out.toString();
    }
  }

  /**
   * Every decoded request, in arrival order. Static because a {@code QuarkusTestResourceLifecycleManager}
   * is instantiated by the framework and the tests never hold the instance; it lives in the test
   * JVM, which is the same JVM under {@code @QuarkusTest} and a different one from the launched
   * artifact under {@code @QuarkusIntegrationTest} — either way the stub is here and the exporter
   * dials in.
   */
  private static final List<ExportLogsServiceRequest> REQUESTS = new CopyOnWriteArrayList<>();

  /** The access token the stub's {@code /idp/token} mints, every time. */
  public static final String TOKEN = "stub-telemetry-token";

  /** Each log export's Authorization header, in arrival order; {@code ""} when it carried none. */
  private static final List<String> AUTHORIZATIONS = new CopyOnWriteArrayList<>();

  private HttpServer server;

  /** Forget everything captured so far. Call it in {@code @BeforeEach}. */
  public static void reset() {
    REQUESTS.clear();
    AUTHORIZATIONS.clear();
  }

  /** The Authorization header of every log export so far; {@code ""} for one that carried none. */
  public static List<String> authorizations() {
    return List.copyOf(AUTHORIZATIONS);
  }

  /** Every log record exported so far, flattened out of its resource/scope envelopes. */
  public static List<Captured> captured() {
    List<Captured> out = new ArrayList<>();
    for (ExportLogsServiceRequest request : REQUESTS) {
      for (ResourceLogs resourceLogs : request.getResourceLogsList()) {
        for (ScopeLogs scopeLogs : resourceLogs.getScopeLogsList()) {
          for (LogRecord record : scopeLogs.getLogRecordsList()) {
            out.add(new Captured(resourceLogs.getResource(), scopeLogs.getScope(), record));
          }
        }
      }
    }
    return out;
  }

  /**
   * Wait for one exported record matching {@code wanted}.
   *
   * <p>Polling rather than a latch on purpose: export is asynchronous by design (the batch processor
   * ships on a schedule, {@code quarkus.otel.blrp.schedule.delay}, default one second), and a proof
   * that logging is fail-open must not be written as if it were synchronous.
   */
  public static Optional<Captured> await(Predicate<Captured> wanted, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    do {
      Optional<Captured> hit = captured().stream().filter(wanted).findFirst();
      if (hit.isPresent()) {
        return hit;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    } while (System.nanoTime() < deadline);
    return captured().stream().filter(wanted).findFirst();
  }

  /** A short description of everything captured, for an assertion message worth reading. */
  public static String describe() {
    List<Captured> all = captured();
    if (all.isEmpty()) {
      return "no log records were exported at all";
    }
    StringBuilder out = new StringBuilder(all.size() + " exported record(s):");
    for (Captured c : all) {
      out.append("\n  [")
          .append(c.record().getSeverityText())
          .append('/')
          .append(c.record().getSeverityNumber())
          .append("] ")
          .append(c.body())
          .append(" attrs=")
          .append(c.record().getAttributesList().stream().map(KeyValue::getKey).toList());
    }
    return out.toString();
  }

  @Override
  public Map<String, String> start() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException cannotBind) {
      throw new UncheckedIOException(cannotBind);
    }
    // A pool rather than the default same-thread executor: the exporter can have several batches in
    // flight, and a stub that serialised them would measure the stub instead of the bridge.
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/",
        exchange -> {
          if (exchange.getRequestURI().getPath().equals("/idp/token")) {
            // qits-idp's client-credentials answer, as much of it as quarkus-oidc-client reads.
            exchange.getRequestBody().readAllBytes();
            byte[] minted =
                ("{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":3600}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, minted.length);
            exchange.getResponseBody().write(minted);
            exchange.close();
            return;
          }
          byte[] body = read(exchange.getRequestBody().readAllBytes(),
              exchange.getRequestHeaders().getFirst("Content-Encoding"));
          if (exchange.getRequestURI().getPath().endsWith("/v1/logs")) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            AUTHORIZATIONS.add(authorization == null ? "" : authorization);
            REQUESTS.add(ExportLogsServiceRequest.parseFrom(body));
          }
          // The OTLP success answer for every signal is the signal's own empty response message,
          // which serialises to zero bytes — so -1 (no body) is the honest length, and the content
          // type still has to say protobuf or the exporter logs a protocol error over the success.
          exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    String base =
        "http://"
            + InetAddress.getLoopbackAddress().getHostAddress()
            + ":"
            + server.getAddress().getPort();
    return Map.of(
        "quarkus.otel.sdk.disabled", "false",
        "qits.telemetry.endpoint", base + "/observability/api/otel",
        "quarkus.oidc-client.qits.client-enabled", "true",
        "quarkus.oidc-client.qits.auth-server-url", base + "/idp",
        "quarkus.oidc-client.qits.client-id", "stub-client",
        "quarkus.oidc-client.qits.credentials.secret", "stub-secret");
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop(0);
    }
    reset();
  }

  private static byte[] read(byte[] raw, String contentEncoding) throws IOException {
    if (contentEncoding == null || !contentEncoding.toLowerCase().contains("gzip")) {
      return raw;
    }
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
      return gzip.readAllBytes();
    }
  }

  /**
   * Every {@code AnyValue} flattened to text. The same simplification qits-observability's decoder
   * makes, and it is recorded there as a known loss: a typed or nested attribute arrives here as its
   * string form. Nothing this proof asserts is typed, so the loss costs it nothing.
   */
  private static String asText(AnyValue value) {
    return switch (value.getValueCase()) {
      case STRING_VALUE -> value.getStringValue();
      case BOOL_VALUE -> String.valueOf(value.getBoolValue());
      case INT_VALUE -> String.valueOf(value.getIntValue());
      case DOUBLE_VALUE -> String.valueOf(value.getDoubleValue());
      default -> value.toString().trim();
    };
  }
}
