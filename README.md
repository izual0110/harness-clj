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
