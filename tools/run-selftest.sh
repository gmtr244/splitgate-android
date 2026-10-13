#!/usr/bin/env bash
# Motoru Android olmadan JVM uzerinde test eder (JDK 8+ yeterli).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
javac -encoding UTF-8 --release 17 -nowarn -d "$OUT" app/src/main/java/com/projectgamers/splitgate/engine/*.java tools/selftest/EngineSelfTest.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT" com.projectgamers.splitgate.engine.EngineSelfTest
