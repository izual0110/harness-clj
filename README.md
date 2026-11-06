# harness-clj

Minimal Clojure 1.12.6 console app with a synchronous Ollama client.
Requires JDK 27 and [Clojure CLI](https://clojure.org/guides/install_clojure), or use `.devcontainer/`.

## Commands

Run from the project root:

```sh
clojure -M:run Ada                      # Run the app
clojure -M:test                         # Run tests; no Ollama required
clojure -M:build                        # AOT-compile to target/classes (not a JAR)
clojure -M:nrepl                        # Start a nREPL
clojure -Moutdated --upgrade --force    # Bump deps
```

CI runs tests, compilation, and the app in Docker.

## Ollama

Set the host, model, timeout, and defaults in `resources/chat-model.edn`.
Start Ollama and download the configured model first (e.g. `ollama pull llama3.2`).
For host Ollama from a devcontainer, use `http://host.docker.internal:11434` if supported.

```clojure
(require '[harness-clj.chat-model :as chat-model])

(let [client (chat-model/client)]
  (with-open [http-client (:http-client client)]
    (println (:response (chat-model/generate client "Hello!")))))
```

Supports `generate`, `chat`, and `list-models`; no streaming.
Reuse the client and close `:http-client` when done.
`generate` and `chat` accept an optional parameter map; nested `:options` are merged with defaults.
Responses are maps with keyword keys; HTTP/API errors throw `ExceptionInfo`.
