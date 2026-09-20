#!/usr/bin/env bash
# Print a JAVA_HOME that Gradle can actually run on.
#
# Gradle 8.14 runs on Java 8 through 24 and refuses anything newer; this host's default `java` is
# 25, so `./gradlew` fails with a bare version number and no explanation. The project's own
# toolchain is 21 regardless of which JVM Gradle runs on, so this picks 21 when it can find one and
# falls back to whatever is on PATH if that is already supported.
#
# It prints a path and nothing else, so it can be used as:
#   JAVA_HOME="$(scripts/java-home.sh)" ./gradlew build
set -euo pipefail

supported() {
  # Gradle's ceiling. Bumping Gradle is what moves this number, not wishing.
  local major="$1"
  [ "$major" -ge 17 ] && [ "$major" -le 24 ]
}

major_of() {
  local home="$1"
  "$home/bin/java" -version 2>&1 | head -1 |
    sed -E 's/.*version "([0-9]+)(\.[0-9]+)*.*/\1/'
}

# Explicit wins: if somebody set JAVA_HOME to something usable, use it.
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ] && supported "$(major_of "$JAVA_HOME")"; then
  printf '%s\n' "$JAVA_HOME"
  exit 0
fi

# Then a 21, which is what the project builds for and tests against.
for candidate in /usr/lib/jvm/java-21-openjdk-* /usr/lib/jvm/temurin-21* /usr/lib/jvm/*-21-* \
                 "$HOME/.sdkman/candidates/java/21"*; do
  [ -x "$candidate/bin/java" ] || continue
  printf '%s\n' "$candidate"
  exit 0
done

# Then anything installed that Gradle tolerates.
for candidate in /usr/lib/jvm/*; do
  [ -x "$candidate/bin/java" ] || continue
  if supported "$(major_of "$candidate")"; then
    printf '%s\n' "$candidate"
    exit 0
  fi
done

printf 'no JDK between 17 and 24 found; Gradle 8.14 cannot run on the default java\n' >&2
printf 'install one, for example: sudo apt-get install -y openjdk-21-jdk\n' >&2
exit 1
