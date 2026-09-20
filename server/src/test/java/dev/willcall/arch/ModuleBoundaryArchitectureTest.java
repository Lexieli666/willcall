package dev.willcall.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps "modular monolith" true.
 *
 * <p>ADR 0003 says the modules talk through published interfaces and domain events, never through
 * each other's internals. That is a convention, and conventions erode. This test reads the source
 * and fails the build when one erodes, which is the only version of a convention that survives.
 *
 * <p>The rules are deliberately about <em>direction</em>, not about forbidding all coupling. The
 * reservation core is allowed to read the catalogue — it has to, to price a seat. The catalogue is
 * not allowed to read the reservation core, because the moment it does, the dependency is a cycle
 * and neither module can be understood or extracted alone.
 */
class ModuleBoundaryArchitectureTest {

  private static final Path SOURCE_ROOT = Path.of("src/main/java/dev/willcall");
  private static final Pattern IMPORT =
      Pattern.compile("^import\\s+(static\\s+)?(dev\\.willcall\\.[\\w.]+);");

  /** module -> modules it may import. "platform" is shared infrastructure and always allowed. */
  private static final Map<String, Set<String>> ALLOWED =
      Map.of(
          "platform", Set.of("platform"),
          "catalog", Set.of("catalog", "platform"),
          "allocation", Set.of("allocation", "platform", "catalog"),
          "payment", Set.of("payment", "platform"),
          "reservation", Set.of("reservation", "platform", "catalog", "allocation", "payment"),
          "realtime", Set.of("realtime", "platform", "catalog", "reservation", "allocation"),
          // The waiting room pushes queue position over the same stream the seat map uses, so it
          // depends on realtime. The reverse must never hold, or reservation -> waitingroom ->
          // realtime -> reservation becomes a cycle - which is exactly what this test caught when
          // the admission check was a direct call from the hold controller.
          "waitingroom", Set.of("waitingroom", "platform", "catalog", "reservation", "realtime"),
          // Operational and diagnostic endpoints sit at the top of the stack and may reach into
          // anything. Nothing may reach into them.
          "ops",
              Set.of(
                  "ops",
                  "platform",
                  "catalog",
                  "reservation",
                  "allocation",
                  "payment",
                  "realtime",
                  "waitingroom"));

  private record Violation(Path file, String from, String to, String importLine) {
    @Override
    public String toString() {
      return "%s: module '%s' must not import '%s' (%s)".formatted(file, from, to, importLine);
    }
  }

  @Test
  @DisplayName("no module reaches into a module it is not allowed to depend on")
  void moduleDependenciesRespectTheDeclaredDirection() throws IOException {
    List<Violation> violations = new ArrayList<>();

    try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String module = moduleOf(SOURCE_ROOT.relativize(file));
        if (module == null) continue;
        Set<String> allowed = ALLOWED.get(module);
        assertThat(allowed).as("module '%s' has no declared dependency rule", module).isNotNull();

        for (String line : Files.readAllLines(file)) {
          Matcher matcher = IMPORT.matcher(line.trim());
          if (!matcher.find()) continue;
          String imported = matcher.group(2);
          String target = moduleOfPackage(imported);
          if (target == null || target.equals(module)) continue;
          if (!allowed.contains(target)) {
            violations.add(new Violation(file, module, target, line.trim()));
          }
        }
      }
    }

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("every module directory has a rule, so a new module cannot slip in unconstrained")
  void everyModuleIsDeclared() throws IOException {
    try (Stream<Path> entries = Files.list(SOURCE_ROOT)) {
      List<String> directories =
          entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
      assertThat(ALLOWED.keySet()).containsAll(directories);
    }
  }

  @Test
  @DisplayName("the source tree the rules are checked against actually exists")
  void sourceRootExists() throws IOException {
    assertThat(Files.isDirectory(SOURCE_ROOT)).as("%s", SOURCE_ROOT.toAbsolutePath()).isTrue();
    try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
      assertThat(files.filter(p -> p.toString().endsWith(".java")).count()).isGreaterThan(20);
    }
  }

  private static String moduleOf(Path relative) {
    return relative.getNameCount() < 2 ? null : relative.getName(0).toString();
  }

  private static String moduleOfPackage(String fullyQualified) {
    String rest = fullyQualified.substring("dev.willcall.".length());
    int dot = rest.indexOf('.');
    return dot < 0 ? null : rest.substring(0, dot);
  }
}
