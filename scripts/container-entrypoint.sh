#!/usr/bin/env bash
set -euo pipefail

managed_mods=(
  kotlin-runtime.jar
  genesis-core.jar
  genesis-standard.jar
  plague-core.jar
)

mkdir -p /app/config/mods /app/dumps

for mod in "${managed_mods[@]}"; do
  install --mode=0644 "/opt/plague-mods/$mod" "/app/config/mods/$mod"
done

exec java \
  -Xms256m \
  -Xmx1200m \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/dumps/plague.hprof \
  -jar /app/server-release.jar
