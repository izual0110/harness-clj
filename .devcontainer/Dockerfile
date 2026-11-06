FROM ubuntu:26.04

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends bash ca-certificates curl git tar gzip findutils procps python3 fontconfig libstdc++6 tzdata zlib1g binutils build-essential docker.io docker-compose-v2 rlwrap \
    && rm -rf /var/lib/apt/lists/*

COPY --from=eclipse-temurin:27-jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN java --version && javac --version

ARG CLOJURE_VERSION=1.12.6.1673
RUN curl -fsSL -o /tmp/linux-install.sh \
      "https://github.com/clojure/brew-install/releases/download/${CLOJURE_VERSION}/linux-install.sh" \
    && cd /tmp \
    && bash linux-install.sh \
    && rm -f linux-install.sh \
    && clojure -Sdescribe

RUN git config --system --add safe.directory '/workspaces/*'

WORKDIR /workspaces
CMD ["sleep", "infinity"]
