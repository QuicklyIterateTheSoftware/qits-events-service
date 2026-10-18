package eu.wohlben.qits.events;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.HttpClientRules;

/**
 * The platform's shared ArchUnit rules over this repository's classes, {@code events} and {@code
 * service} both: {@code service}'s test classpath is the only one that can see both modules.
 *
 * <p>{@link HttpClientRules} is the completeness guard for traced service-to-service HTTP: a class
 * that calls {@code HttpClient.newBuilder()}/{@code newHttpClient()} must also use {@code
 * QitsHttpClients} (qits-http-core) or declare {@code @RawHttpClient(reason = "...")}. This
 * repository builds no {@code java.net.http.HttpClient} of its own anywhere — the one outbound call
 * it makes, quarkus-oidc's discovery and JWKS fetch against qits-idp-service, is the extension's own
 * doing — so this rule passes with nothing to report, and would be the first thing to catch a new
 * one landing unwrapped (ticket qits-1149's {@code contracts/OutboundCallGuardTest} is the rest of
 * that guard: the mechanisms this rule cannot see).
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.events",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

  @ArchTest static final ArchTests HTTP_CLIENTS = ArchTests.in(HttpClientRules.class);
}
