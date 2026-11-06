# harness-clj

Минимальный консольный проект на Clojure **1.12.6** и Clojure CLI.
Используется последняя стабильная версия, а не предварительная 1.13.

## Требования

- JDK (рекомендуется Java 25 LTS).
- [Clojure CLI](https://clojure.org/guides/install_clojure).
- Доступ к Maven Central при первом запуске для скачивания зависимостей.

В `.devcontainer/` уже настроено окружение с JDK и Clojure CLI.
Если Java установлена, но не доступна в `PATH`, задайте `JAVA_HOME` —
Clojure CLI умеет находить Java через эту переменную.

Все команды ниже выполняются из корня проекта.

## Запуск

```sh
clojure -M:run
# Hello, world!

clojure -M:run Ada Lovelace
# Hello, Ada Lovelace!
```

## Тесты

```sh
clojure -M:test
```

Тесты используют встроенный `clojure.test`; при ошибках команда завершится
с ненулевым кодом. При добавлении тестовых пространств имён подключите их
в `test/harness_clj/test_runner.clj` и добавьте в вызов `run-tests`.

## Компиляция

```sh
clojure -M:build
```

Выполняет AOT-компиляцию `harness-clj.core`, включая Java-точку входа,
в `target/classes/`. Это компиляция классов, не сборка самостоятельного JAR.

Запуск скомпилированной точки входа напрямую через JVM:

```sh
java -cp "target/classes:$(clojure -Spath)" harness_clj.core Ada
# Hello, Ada!
```

Команда выше рассчитана на Linux/macOS и требует `java` в `PATH`.
Если Java доступна только через `JAVA_HOME`, используйте
`"$JAVA_HOME/bin/java"` вместо `java`.

## REPL

```sh
clojure
```

```clojure
(require '[harness-clj.core :as core])
(core/greet "Clojure")
;; => "Hello, Clojure!"
```

## Структура

```text
src/harness_clj/core.clj          — приложение и точка входа
resources/                       — ресурсы приложения
test/                            — тесты (см. ниже)
dev/build.clj                     — AOT-компиляция
deps.edn                         — зависимости и команды
```

Тесты находятся в `test/harness_clj/core_test.clj`, их исполнитель —
`test/harness_clj/test_runner.clj`.
