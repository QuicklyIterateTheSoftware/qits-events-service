package eu.wohlben.qits.events;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import eu.wohlben.qits.archrules.DeskAdminParityRules;
import java.util.Set;

/**
 * Parity between {@code qits:admin-agent} and {@code qits:desk-admin} over this repository's doors
 * (epic qits-1621, the task "qits-events-service: admit qits:desk-admin wherever qits:admin-agent
 * is"): {@code EventStreamResource}, {@code EventController} and {@code EventStreamSocket} — a
 * WebSocket, so its class-level {@code @RolesAllowed} counts for its callbacks. The dossier's
 * inventory records no refusal for this service, so the refusal set is empty: every route admitting
 * {@code qits:admin-agent} here must also admit {@code qits:desk-admin}.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.events",
    importOptions = ImportOption.DoNotIncludeTests.class)
class DeskAdminParityTest {

  private static final Set<String> REFUSED = Set.of();

  @ArchTest
  static final ArchRule PARITY = DeskAdminParityRules.everyAdminAgentRouteAdmitsDeskAdmin(REFUSED);

  @ArchTest
  static final ArchRule REFUSALS = DeskAdminParityRules.refusedRoutesExistAndRefuse(REFUSED);
}
