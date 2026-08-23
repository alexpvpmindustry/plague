#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  echo "Usage: $0 IMAGE.tar.zst IMAGE.tar.zst.sha256 AlexServerPlugin.jar ALEX_SHA256 EXPECTED_COMMIT CANDIDATE_COMPOSE" >&2
  exit 2
}

[[ $# -eq 6 ]] || usage

image_archive=$(realpath "$1")
image_checksum=$(realpath "$2")
alex_jar=$(realpath "$3")
alex_sha256=$4
expected_commit=$5
candidate_compose=$(realpath "$6")

root=/root/plague
container=plague-server-1
compose="$root/docker-compose.production.yml"
backup_root=/root/plague-backups
lock_file=/root/plague-v159.3.deploy.lock

[[ $(id -u) -eq 0 ]] || { echo "Run as root." >&2; exit 1; }
[[ -d "$root/prod" ]] || { echo "Missing production root: $root" >&2; exit 1; }
[[ -f "$image_archive" && -f "$image_checksum" && -f "$alex_jar" && -f "$candidate_compose" ]] || usage
[[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] || { echo "Expected commit must be a full SHA." >&2; exit 1; }
[[ "$alex_sha256" =~ ^[0-9a-f]{64}$ ]] || { echo "Alex JAR SHA-256 is invalid." >&2; exit 1; }

for command in docker zstd sha256sum flock realpath; do
  command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done

exec 9>"$lock_file"
flock --nonblock 9 || { echo "Another Plague deployment is running." >&2; exit 1; }
umask 077

checksum_name=$(basename "$image_checksum")
archive_name=$(basename "$image_archive")
[[ "$checksum_name" == "$archive_name.sha256" ]] || { echo "Checksum filename does not match image archive." >&2; exit 1; }
(
  cd "$(dirname "$image_archive")"
  sha256sum --check --strict "$checksum_name"
)
[[ $(sha256sum "$alex_jar" | cut -d' ' -f1) == "$alex_sha256" ]] || { echo "AlexServerPlugin hash mismatch." >&2; exit 1; }

docker inspect "$container" >/dev/null
service_label=$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.service" }}' "$container")
[[ "$service_label" == server ]] || { echo "Container is not Compose service server." >&2; exit 1; }

docker compose --project-directory "$root" -f "$candidate_compose" config --quiet
mapfile -t services < <(docker compose --project-directory "$root" -f "$candidate_compose" config --services)
printf '%s\n' "${services[@]}" | grep -qx server
printf '%s\n' "${services[@]}" | grep -qx portainer

stamp=$(date -u '+%Y%m%dT%H%M%SZ')
since=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
backup="$backup_root/${stamp}-deploy-v159.3"
mkdir -p "$backup"

old_image=$(docker inspect --format '{{.Image}}' "$container")
rollback_tag="plague-server:rollback-$stamp"
docker tag "$old_image" "$rollback_tag"
docker inspect "$container" >"$backup/container-before.json"
docker image inspect "$old_image" >"$backup/image-before.json"
docker ps --format '{{.Names}}={{.ID}}' | grep -v '^plague-server-1=' | sort >"$backup/unrelated-before.txt"
cp -a "$compose" "$backup/docker-compose.production.yml"
cp -a "$root/prod/AlexServerPlugin.jar" "$backup/AlexServerPlugin.jar"
docker logs --tail 1000 "$container" >"$backup/server-before.log" 2>&1 || true

rollback() {
  status=$?
  trap - ERR INT TERM
  echo "Deployment failed. Rolling back only $container." >&2
  docker logs "$container" >"$backup/server-failed.log" 2>&1 || true
  if [[ -d "$root/prod/runtime-config" ]]; then
    cp -a "$candidate_compose" "$compose"
  else
    cp -a "$backup/docker-compose.production.yml" "$compose"
  fi
  cp -a "$backup/AlexServerPlugin.jar" "$root/prod/AlexServerPlugin.jar"
  docker tag "$old_image" plague-server:v159.3
  docker compose --project-directory "$root" -f "$compose" up -d --no-deps --no-build --force-recreate server || true
  docker inspect "$container" >"$backup/container-after-rollback.json" 2>/dev/null || true
  exit "$status"
}
trap rollback ERR INT TERM

zstd --decompress --stdout "$image_archive" | docker load
candidate_image=$(docker image inspect --format '{{.Id}}' plague-server:v159.3)
revision=$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' plague-server:v159.3)
[[ "$revision" == "$expected_commit" ]] || { echo "Loaded image commit label mismatch." >&2; false; }

# On the first state-safe deployment, stop cleanly and copy the old writable
# container config to the host. Later deployments already write there directly.
config_mount=$(docker inspect --format '{{ range .Mounts }}{{ if eq .Destination "/app/config" }}{{.Source}}{{ end }}{{ end }}' "$container")
if [[ -z "$config_mount" ]]; then
  runtime_new="$root/prod/runtime-config.new-$stamp"
  [[ ! -e "$runtime_new" && ! -e "$root/prod/runtime-config" ]] || { echo "Runtime config staging path already exists." >&2; false; }
  mkdir -p "$runtime_new"
  docker stop --time 15 "$container"
  docker cp "$container:/app/config/." "$runtime_new/"
  mv "$runtime_new" "$root/prod/runtime-config"
fi
mkdir -p "$root/prod/runtime-dumps"

install -m 0644 "$alex_jar" "$root/prod/AlexServerPlugin.jar.new"
mv "$root/prod/AlexServerPlugin.jar.new" "$root/prod/AlexServerPlugin.jar"
install -m 0644 "$candidate_compose" "$compose.new"
mv "$compose.new" "$compose"

docker compose --project-directory "$root" -f "$compose" up -d --no-deps --no-build --force-recreate server

healthy=false
for _ in $(seq 1 48); do
  state=$(docker inspect --format '{{.State.Status}}' "$container")
  health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container")
  if [[ "$state" == running && "$health" == healthy ]]; then
    healthy=true
    break
  fi
  sleep 5
done
[[ "$healthy" == true ]] || { echo "New Plague container did not become healthy." >&2; false; }

running_image=$(docker inspect --format '{{.Image}}' "$container")
[[ "$running_image" == "$candidate_image" ]] || { echo "Running image ID does not match candidate." >&2; false; }
[[ $(docker inspect --format '{{.State.OOMKilled}}' "$container") == false ]] || { echo "New container was OOM-killed." >&2; false; }

server_sha256=$(docker exec "$container" sha256sum /app/server-release.jar | cut -d' ' -f1)
[[ "$server_sha256" == edb1e75eeb91520e6154b81714df5e5a0fa7d711fe1c010dbe124f7eca82bbfb ]] || { echo "Running Mindustry server JAR is not v159.3." >&2; false; }

fresh_log="$backup/server-v159.3.log"
docker logs --since "$since" "$container" >"$fresh_log" 2>&1
for marker in 'PlagueCore' 'inside give xp' 'Opened a server on port' 'Hosted'; do
  grep -F "$marker" "$fresh_log" >/dev/null || { echo "Missing production marker: $marker" >&2; false; }
done
if grep -E 'NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|AbstractMethodError|VerifyError|LinkageError|OutOfMemoryError|Error loading mod|Failed to load mod|Exception in thread' "$fresh_log"; then
  echo "Fatal compatibility marker found in production log." >&2
  false
fi

docker ps --format '{{.Names}}={{.ID}}' | grep -v '^plague-server-1=' | sort >"$backup/unrelated-after.txt"
diff -u "$backup/unrelated-before.txt" "$backup/unrelated-after.txt"

docker inspect "$container" >"$backup/container-after.json"
sha256sum "$root/prod/AlexServerPlugin.jar" "$compose" >"$backup/deployed-sha256sums.txt"
trap - ERR INT TERM
printf 'Deployment succeeded.\nbackup=%s\nimage_id=%s\nsource_commit=%s\n' "$backup" "$candidate_image" "$expected_commit"
