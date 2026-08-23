#!/usr/bin/env bash
set -euo pipefail

mindustry_version="v159.3"
genesis_version="3.0.0-beta.27"
kotlin_runtime_version="v3.1.1-k.1.9.22"
smoke_dir="${RUNNER_TEMP:-/tmp}/plague-smoke"
mods_dir="$smoke_dir/config/mods"
log_file="$smoke_dir/server.log"

rm -rf "$smoke_dir"
mkdir -p "$mods_dir"

curl --fail --location --retry 3 --output "$smoke_dir/server-release.jar" \
  "https://github.com/Anuken/Mindustry/releases/download/${mindustry_version}/server-release.jar"
curl --fail --location --retry 3 --output "$mods_dir/kotlin-runtime.jar" \
  "https://github.com/xpdustry/kotlin-runtime/releases/download/${kotlin_runtime_version}/kotlin-runtime.jar"
curl --fail --location --retry 3 --output "$mods_dir/genesis-core.jar" \
  "https://github.com/kennarddh-mindustry/genesis/releases/download/v${genesis_version}/genesis-core-${genesis_version}.jar"
curl --fail --location --retry 3 --output "$mods_dir/genesis-standard.jar" \
  "https://github.com/kennarddh-mindustry/genesis/releases/download/v${genesis_version}/genesis-standard-${genesis_version}.jar"

plague_jar=""
for candidate in plague-core/build/libs/*.jar; do
  case "$candidate" in
    *-sources.jar|*-javadoc.jar) ;;
    *) plague_jar="$candidate"; break ;;
  esac
done

if [[ -z "$plague_jar" || ! -f "$plague_jar" ]]; then
  echo "No compiled Plague plugin JAR was found." >&2
  exit 1
fi

cp "$plague_jar" "$mods_dir/plague-core.jar"

set +e
(
  { sleep 30; printf 'exit\n'; } |
    java -Xms128m -Xmx1g -jar "$smoke_dir/server-release.jar"
) >"$log_file" 2>&1
server_status=$?
set -e

cat "$log_file"

if [[ $server_status -ne 0 ]]; then
  echo "Mindustry smoke server exited with status $server_status." >&2
  exit "$server_status"
fi

bad_pattern='NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|UnsupportedClassVersionError|Error loading mod|Failed to load mod|Exception in thread'
if grep -E "$bad_pattern" "$log_file"; then
  echo "A runtime compatibility error was found in the smoke log." >&2
  exit 1
fi

for expected in 'PlagueCore' 'Server loaded'; do
  if ! grep -F "$expected" "$log_file" >/dev/null; then
    echo "Expected startup marker was not found: $expected" >&2
    exit 1
  fi
done

echo "Mindustry v159.3 Plague smoke test passed."
