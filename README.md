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

Set `LOG_LEVEL=DEBUG` when starting the application or REPL to log outgoing Ollama
requests: method, URL, explicitly configured headers, and the exact JSON body sent
to the HTTP client. Headers added automatically by the JDK are not included.
These logs contain prompts and message contents; enable them only when appropriate.
