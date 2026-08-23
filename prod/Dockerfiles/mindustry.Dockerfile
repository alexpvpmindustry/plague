FROM eclipse-temurin:17-jre@sha256:13cc28a6cc72a38ce1f00c906be3580c1a3e604b8984d694f369a96742abc93b

ARG MINDUSTRY_VERSION=v159.3
ARG MINDUSTRY_SHA256=edb1e75eeb91520e6154b81714df5e5a0fa7d711fe1c010dbe124f7eca82bbfb
ARG KOTLIN_RUNTIME_VERSION=v3.1.1-k.1.9.22
ARG KOTLIN_RUNTIME_SHA256=36633c7aabc93ebf2c8f79aefc6e954efaa2f6abe913a133033c4284c329b9c1
ARG GENESIS_VERSION=3.0.0-beta.27
ARG GENESIS_CORE_SHA256=31e1bda9e062c3d40181544f4d24f634a3d923112327b7a406fcdf078f7cba7f
ARG GENESIS_STANDARD_SHA256=7d69422916a0360b8caf2e351d1cb732221609244ecb490197273332cb799b21
ARG VCS_REF=unknown

LABEL org.opencontainers.image.title="Plague Mindustry Server" \
      org.opencontainers.image.version="v159.3" \
      org.opencontainers.image.revision="${VCS_REF}"

WORKDIR /app

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl iproute2 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/plague-mods /app/dumps \
    && curl --fail --location --retry 3 --output server-release.jar \
        "https://github.com/Anuken/Mindustry/releases/download/${MINDUSTRY_VERSION}/server-release.jar" \
    && echo "${MINDUSTRY_SHA256}  server-release.jar" | sha256sum --check --strict \
    && curl --fail --location --retry 3 --output /opt/plague-mods/kotlin-runtime.jar \
        "https://github.com/xpdustry/kotlin-runtime/releases/download/${KOTLIN_RUNTIME_VERSION}/kotlin-runtime.jar" \
    && echo "${KOTLIN_RUNTIME_SHA256}  /opt/plague-mods/kotlin-runtime.jar" | sha256sum --check --strict \
    && curl --fail --location --retry 3 --output /opt/plague-mods/genesis-core.jar \
        "https://github.com/kennarddh-mindustry/genesis/releases/download/v${GENESIS_VERSION}/genesis-core-${GENESIS_VERSION}.jar" \
    && echo "${GENESIS_CORE_SHA256}  /opt/plague-mods/genesis-core.jar" | sha256sum --check --strict \
    && curl --fail --location --retry 3 --output /opt/plague-mods/genesis-standard.jar \
        "https://github.com/kennarddh-mindustry/genesis/releases/download/v${GENESIS_VERSION}/genesis-standard-${GENESIS_VERSION}.jar" \
    && echo "${GENESIS_STANDARD_SHA256}  /opt/plague-mods/genesis-standard.jar" | sha256sum --check --strict

COPY prod/image-input/plague-core.jar /opt/plague-mods/plague-core.jar
COPY --chmod=0755 scripts/container-entrypoint.sh /usr/local/bin/plague-entrypoint

ENTRYPOINT ["/usr/local/bin/plague-entrypoint"]
