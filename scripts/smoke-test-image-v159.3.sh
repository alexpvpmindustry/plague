#!/usr/bin/env bash
set -euo pipefail

image="${1:?Usage: smoke-test-image-v159.3.sh IMAGE}"
container="plague-image-smoke-${GITHUB_RUN_ID:-local}"
log_file="${RUNNER_TEMP:-/tmp}/plague-image-smoke.log"
state_dir="${RUNNER_TEMP:-/tmp}/plague-image-state"

rm -rf "$state_dir"
mkdir -p "$state_dir"

cleanup() {
  docker rm --force "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run --detach \
  --name "$container" \
  --memory 2g \
  --cpus 2 \
  --volume "$state_dir:/app/config" \
  "$image" >/dev/null

ready=false
for _ in $(seq 1 45); do
  docker logs "$container" >"$log_file" 2>&1 || true
  if grep -q 'Opened a server on port 6567' "$log_file" \
    && grep -q 'Hosted' "$log_file" \
    && grep -q 'PlagueCore' "$log_file"; then
    ready=true
    break
  fi

  state=$(docker inspect --format '{{.State.Status}}' "$container")
  if [[ "$state" == "exited" || "$state" == "dead" ]]; then
    break
  fi
  sleep 2
done

docker logs "$container" >"$log_file" 2>&1 || true
cat "$log_file"

if [[ "$ready" != true ]]; then
  echo "The packaged image did not reach a ready Plague server state with persistent config mounted." >&2
  exit 1
fi

for plugin in kotlin-runtime.jar genesis-core.jar genesis-standard.jar plague-core.jar; do
  if [[ ! -f "$state_dir/mods/$plugin" ]]; then
    echo "Managed plugin was not seeded into persistent config: $plugin" >&2
    exit 1
  fi
done

if ! docker exec "$container" ss -ltn | grep -Eq '[:.]6567[[:space:]]'; then
  echo "Packaged server has no TCP listener on port 6567." >&2
  exit 1
fi

if ! docker exec "$container" ss -lun | grep -Eq '[:.]6567[[:space:]]'; then
  echo "Packaged server has no UDP listener on port 6567." >&2
  exit 1
fi

if [[ "$(docker inspect --format '{{.State.OOMKilled}}' "$container")" != false ]]; then
  echo "Packaged server was OOM-killed." >&2
  exit 1
fi

bad_pattern='NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|AbstractMethodError|VerifyError|LinkageError|UnsupportedClassVersionError|OutOfMemoryError|Error loading mod|Failed to load mod|Exception in thread'
if grep -E "$bad_pattern" "$log_file"; then
  echo "A runtime compatibility error was found in the image log." >&2
  exit 1
fi

docker stop --time 15 "$container" >/dev/null
echo "Packaged Plague v159.3 image smoke test passed."
