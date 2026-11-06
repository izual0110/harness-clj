FROM eclipse-temurin:27-jdk-alpine

ARG CLOJURE_CLI_VERSION=1.12.6.1673

RUN apk add --no-cache bash ca-certificates \
    && apk add --no-cache --virtual .clojure-install curl tar gzip coreutils \
    && curl -fsSL -o /tmp/linux-install.sh \
      "https://github.com/clojure/brew-install/releases/download/${CLOJURE_CLI_VERSION}/linux-install.sh" \
    && cd /tmp \
    && bash linux-install.sh \
    && rm -f linux-install.sh \
    && apk del .clojure-install \
    && java --version \
    && clojure -Sdescribe

WORKDIR /workspace
CMD ["clojure"]
