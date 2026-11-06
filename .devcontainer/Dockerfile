FROM alpine:3.24

RUN apk add --no-cache bash ca-certificates curl git tar gzip findutils procps-ng coreutils python3 fontconfig ttf-dejavu libgcc libstdc++ tzdata zlib binutils docker-cli docker-cli-compose rlwrap

COPY --from=eclipse-temurin:27-jdk-alpine /opt/java/openjdk /opt/java/openjdk
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
