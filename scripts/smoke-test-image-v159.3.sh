#!/usr/bin/env bash
set -euo pipefail

image="${1:?Usage: smoke-test-image-v159.3.sh IMAGE}"
container="plague-image-smoke-${GITHUB_RUN_ID:-local}"
log_file="${RUNNER_TEMP:-/tmp}/plague-image-smoke.log"

cleanup() {
  docker rm --force "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run --detach --name "$container" "$image" >/dev/null

ready=false
for _ in $(seq 1 45); do
  docker logs "$container" >"$log_file" 2>&1 || true
  if grep -F 'Server loaded' "$log_file" >/dev/null && grep -F 'PlagueCore' "$log_file" >/dev/null; then
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
  echo "The packaged image did not reach a ready Plague server state." >&2
  exit 1
fi

bad_pattern='NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|UnsupportedClassVersionError|Error loading mod|Failed to load mod|Exception in thread'
if grep -E "$bad_pattern" "$log_file"; then
  echo "A runtime compatibility error was found in the image log." >&2
  exit 1
fi

docker stop --time 15 "$container" >/dev/null
echo "Packaged Plague v159.3 image smoke test passed."
