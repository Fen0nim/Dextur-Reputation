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
