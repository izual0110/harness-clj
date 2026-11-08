# harness-clj

Clojure console app with a synchronous Ollama client.

Requires JDK 27 and [Clojure CLI](https://clojure.org/guides/install_clojure), or use `.devcontainer/`.

## Commands

```sh
clojure -M:run Ada     # Run
clojure -M:test        # Test (no Ollama required)
clojure -M:build       # AOT-compile to target/classes
clojure -M:nrepl       # Start nREPL with CIDER middleware
clojure -M:outdated    # Check dependency updates
```

## Ollama

Configure host and model in `resources/chat-model.edn`.
Start Ollama and pull the configured model before using the client.
Set `:think` alongside `:stream` in `:defaults`, not inside `:options`.
Both `chat` and `generate` also accept `{:think false}` as per-request parameters.
Call `(chat-model/close client)` in a `finally` block when the client is no longer
needed. It closes the underlying HTTP client and waits for ongoing requests to finish.

The `harness-clj` logger uses `DEBUG` in `resources/logback.xml`. Other loggers
inherit the root level, which defaults to `INFO` and can be overridden with `LOG_LEVEL`.
Debug output includes outgoing Ollama requests: method, URL, explicitly configured
headers, and the exact JSON body sent to the HTTP client. Headers added automatically
by the JDK are not included. These logs contain prompts and message contents;
set the `harness-clj` logger level to `INFO` to disable them.
