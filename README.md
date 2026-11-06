# harness-clj

Минимальный консольный проект на Clojure **1.12.6** и Clojure CLI.
Используется последняя стабильная версия, а не предварительная 1.13.
Версия Clojure CLI **1.12.6.1673** — это версия инструментов запуска,
а версия самого языка **1.12.6** закреплена в `deps.edn`.

## Требования

- JDK 27 (та же версия, что в devcontainer и CI).
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

## CI (GitHub Actions)

Workflow `.github/workflows/ci.yml` запускается при каждом push и pull request,
а также вручную через вкладку Actions на GitHub.

Ubuntu-runner собирает отдельный минимальный CI-образ из `.github/ci/Dockerfile`
на базе `eclipse-temurin:27-jdk-alpine` с **Java 27** и **Clojure CLI 1.12.6.1673**.
Devcontainer в CI не используется. Исходники монтируются
в контейнер, и внутри него выполняются:

```sh
clojure -M:test
clojure -M:build
clojure -M:run
```

Java и Clojure на сам runner не устанавливаются. В CI-образе нет инструментов
разработки из devcontainer; зависимости установщика Clojure удаляются после установки.
Ошибка тестов, компиляции или запуска приводит к неуспешному статусу CI.

CI использует два кеша GitHub Actions:

- **Docker-слои** — Buildx сохраняет и восстанавливает их через `type=gha`.
  Неизменившиеся слои CI-образа повторно не собираются.
- **Maven-зависимости** — `~/.m2/repository` кешируется по хешу `deps.edn`
  и монтируется в контейнер. При изменении зависимостей используется предыдущий
  кеш, а недостающие библиотеки скачиваются и сохраняются в новый.

Первый запуск заполняет кеши; последующие используют их, пока они доступны
в GitHub Actions. Исходники и результаты компиляции не входят в кеш образа.

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
