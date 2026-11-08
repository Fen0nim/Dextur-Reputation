# Зависимости 1.0.1

Версии закреплены Gradle STRICT lockfiles и gradle/verification-metadata.xml. Hotfix не меняет версии и lockfiles. В verification metadata добавлены точные недостающие POM/BOM/module checksums для холодного разрешения; прежние доверенные хеши сохранены, wildcard trust и отключения verification нет. Подробности сверки — в отчёте 1.0.1. Локально проверены direct declarations, cached POM metadata, scopes и состав итоговых JAR.

| Компонент | Версия | Scope / упаковка | Лицензия по cached POM |
| --- | --- | --- | --- |
| SnakeYAML | 2.2 | implementation, relocated internal.yaml | Apache 2.0 |
| HikariCP | 6.3.0 | implementation, relocated internal.hikari | Apache 2.0 |
| MariaDB Connector/J | 3.5.6 | runtime JDBC для MariaDB и MySQL | LGPL-2.1-or-later |
| sqlite-jdbc | 3.51.1.0 | implementation, native SQLite | Apache 2.0 |
| Gson | 2.14.0 | implementation | Apache 2.0 |
| Adventure/MiniMessage/plain | 5.2.0 | application API; Paper предоставляет runtime | MIT |
| SLF4J simple | 2.0.17 | offline CLI runtime; исключён из main shaded JAR | inherited metadata |
| Paper API | 26.2.build.134-stable | compileOnly/test; сервер не вложен | direct POM не объявляет |
| PlaceholderAPI | 2.12.3 | compileOnly/test, full plugin не вложен | direct POM не объявляет |
| LuckPerms API | 5.4 | compileOnly/test, API/plugin не вложены | MIT |
| JUnit | 5.13.4 | test only | upstream test tooling |
| Mockito | 5.20.0 | test only | MIT |
| Testcontainers | 1.21.3 | test only | MIT |
| MySQL Connector/J | 9.5.0 | Testcontainers readiness only, не main/CLI | GPLv2 + Universal FOSS Exception |

Сохранение license/notice ресурсов зависимостей проверяется упаковкой; таблица не заменяет исходные лицензии и не объявляет неизвестную лицензию по предположению. Paper/PAPI предоставляются администратором отдельно. Native SQLite ограничен поддержкой драйвера/архитектуры; проверена Linux amd64.

Внешняя проверка OSV: **NOT VERIFIED** — доступ к сервису в этой среде отсутствует (URLError). Локальный аудит и dependency checksum verification не являются внешним vulnerability scan и не доказывают отсутствие CVE. Перед публикацией можно повторить внешний scan в доступной среде; эта ограниченная недоступность сама по себе не заменяет результаты остальных acceptance checks.

## IntelliJ IDEA: исходники зависимостей

Причина ошибки синхронизации: прежний `verification-metadata.xml` содержал 314 JAR/POM/module записей, но ни одной записи `*-sources.jar`. Обычная сборка не загружает эти артефакты, а IDEA дополнительно запрашивает исходники библиотек и buildscript (в частности Shadow и его транзитивных зависимостей). Строгая верификация правильно отклоняла неизвестные source JAR.

Добавлены **113 точных SHA-256** для всех опубликованных исходников текущего набора зависимостей: 108 из Maven Central, 3 через официальный репозиторий Paper, Shadow из Gradle Plugin Portal и PlaceholderAPI через официальный redirect `repo.extendedclip.com` → `repo.helpch.at`. Теперь в файле 427 артефактов у прежних 188 компонентов. Все 314 старых записей, версии, lockfiles и настройки verification сохранены без изменений; wildcard trust, исключения и отключение verification не добавлялись.

Исходники скачаны отдельно по HTTPS с официальных endpoints и сверены с опубликованными checksum sidecars. Для 27 артефактов дополнительно совпал SHA-256 из ранее закреплённого `.module`; Shadow также совпал между Plugin Portal и Maven Central. Некоторые старые публикации предоставляют только SHA-1 sidecar: он использован как дополнительная сверка bytes от доверенного HTTPS-репозитория, но **в Gradle для каждого нового JAR закреплён SHA-256**, не SHA-1.

SHA-256 для файлов из сообщения IDEA:

| Артефакт | SHA-256 |
| --- | --- |
| `asm-commons-9.8-sources.jar` | `a2bd3ba563c5f2e19eea66df99437ef81d69a0dcb3def07028442a273a2da562` |
| `asm-tree-9.8-sources.jar` | `442d32be6106a41b61c2edf05f8ae496c4c54cca6e73d9d59d38e9cb54820c44` |
| `commons-io-2.20.0-sources.jar` | `7a87277538cce40da6389a7163a4d9458bc7a9c39937a329881b91d144be8e0d` |
| `jdependency-2.13-sources.jar` | `ccc9e7efdfa97edec68df8962fb9909c199a10c4bfb58309525fa150053b26bb` |
| `shadow-gradle-plugin-9.2.2-sources.jar` | `e12c3411c192c876dffe52a72f04055be97504d1d6fceab4ab08178da13cf50a` |

Полный список находится в `gradle/verification-metadata.xml`. Для воспроизводимой проверки всех project/runtime/test/runtimeProbe и buildscript classpath:

```bash
./gradlew -I scripts/verify-idea-sources.init.gradle verifyIdeaSources --no-configuration-cache --dependency-verification strict
```

В Windows используйте `gradlew.bat` с теми же аргументами. Проверка включена в CI для Linux/macOS/Windows. Init script использует source-artifact resolution API закреплённого Gradle 9.7.0; отключение configuration cache относится только к этой одноразовой проверке и не меняет настройки обычной сборки/IDE. Он проверяет JAR-библиотеки, не запрашивает несуществующие исходники BOM/plugin-marker POM и не записывает checksums автоматически.

Текущий набор содержит 114 внешних JAR-библиотек: 113 опубликованных source JAR; у пустого compatibility-артефакта `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` исходники не опубликованы. Это нормальное отсутствие sources, не исключение из checksum verification. Ошибки transport/metadata/verification приводят к отказу проверки.

Проверка Gradle Tooling API `IdeaProject` с `downloadSources=true` импортирует шесть модулей и прикрепляет 92 опубликованных source JAR к 93 project-библиотекам. Ещё 21 source JAR проверяется для buildscript отдельно. Нативное окно IntelliJ в облачной среде не запускалось; проверены разрешение артефактов и модель, на которых основан импорт.

После `git pull` выберите в IDEA проектный Gradle Wrapper, Gradle JVM Java 25 и выполните Reload All Gradle Projects. Не требуется удалять metadata или отключать dependency verification. При обновлении зависимостей сначала проверяйте происхождение новых артефактов и их SHA-256, затем повторяйте source-проверку и полный build/tests.

Проверено 2026-11-08: strict source resolution на холодном Gradle-кэше и копии проекта без build-артефактов; импорт `IdeaProject` через Tooling API; положительный контроль и отказ при отсутствующем/неверном source checksum. `clean build :reputation-storage:integrationTest --no-build-cache --dependency-verification strict` — **811 тестов, без ошибок и пропусков** (domain 10, application 95, Paper 126, storage 348, MariaDB/MySQL 232). Production и StorageAdmin JAR побайтно совпадают с предыдущим коммитом.
