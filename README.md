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

## Configuration

- Ollama: `resources/chat-model.edn`; `:think` goes in `:defaults`, not `:options`.
- Logging: `resources/log4j2.xml`; console + `logs/harness-clj.log`.
- `harness-clj.*`: `DEBUG` (includes prompts); others: `INFO` (`LOG_LEVEL`).
