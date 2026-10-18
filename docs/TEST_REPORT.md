# Phase 1 — результаты проверок

Проверено 19 октября 2026 (Asia/Yekaterinburg). Java: Temurin 25+36,
Linux amd64, Gradle 9.7.0. Это проверка текущего checkout; GitHub Actions и production
deployment отдельно не объявляются выполненными.

## Автоматические проверки

Первоначальная полная проверка, генерация lock/checksum state:

```sh
JAVA_HOME=/workspace/.setup/tools/jdk-25+36 \
GRADLE_USER_HOME=/workspace/.setup/gradle-home \
./gradlew clean build :reputation-paper:runtimeProbeJar --no-build-cache \
  --write-locks --write-verification-metadata sha256
```

Результат: **PASS**. Затем отдельно проверен неизменяемый dependency state:

```sh
JAVA_HOME=/workspace/.setup/tools/jdk-25+36 \
GRADLE_USER_HOME=/workspace/.setup/gradle-home \
./gradlew clean build :reputation-paper:runtimeProbeJar --no-build-cache
```

Результат: **PASS**, 30 выполненных тестов, 0 failed, 0 errors, 0 skipped.

| Набор | Выполнено | Проверяет |
|---|---:|---|
| ConfigurationAndMessagesTest | 8 | YAML, ограничения, версия, RU/EN ключи/placeholders, строгий MiniMessage, fallback, защита от инъекции, bounded TTL/LRU |
| ReputationApplicationTest | 4 | Выполнение порта БД вне потока вызывающего, backpressure, drain/cleanup, ошибки без ложного нуля, сохранение языка |
| PaperAdaptersTest | 4 | Права/unknown player, async смена языка и сообщение, offline cached identity, PAPI без SQL |
| MariaDbIntegrationTest | 7 | MariaDB 11.4.8: zero/idempotent/scoped profile, 40 concurrent creates, язык/профиль после restart, checksum, rollback/constraints, недоступность/cleanup |
| MySqlIntegrationTest | 7 | Тот же контракт на MySQL 8.4.7 с настоящим MariaDB Connector/J приложения |

JUnit XML: `*/build/test-results/test/` и `reputation-storage/build/test-results/integrationTest/`.
NO-SOURCE в api/domain — отсутствие отдельных suites, а не подмена основной проверки:
реальные 30 тестов выше выполнены. Отсутствие Docker не приводит к автоматическому skip.

Принудительная пересборка неизменных исходников:

```sh
./gradlew :reputation-paper:shadowJar --rerun-tasks --no-build-cache
```

Результат: **PASS**; порядок ZIP-записей, содержимое и SHA-256 совпали с предыдущим полным
build. SHA-256 окончательного `DexturReputation.jar`:

```text
3ec1df9e7f2aadc45cad2d88cec2b5dc1d302ade2e4337232005bd81e54e2dcf
```

Это проверка воспроизводимости на одной Java/toolchain/платформе, не обещание идентичных
байтов на любой будущей версии JDK. Wrapper distribution и Maven/Gradle-артефакты защищены
SHA-256; TLS verification не отключалась.

## Настоящий Paper

```sh
JAVA_HOME=/workspace/.setup/tools/jdk-25+36 \
python scripts/paper-smoke.py --accept-eula \
  --directory /workspace/.setup/phase1-release-smoke \
  --paper-jar /workspace/.setup/runtime/paper.jar \
  --placeholderapi-jar /workspace/.setup/PlaceholderAPI-2.12.3-release.jar
```

Результат: **3/3 PASS**, проверены явные `PROBE_PASS`/`PROBE_PAPI_PASS`, а не только PID/порт.
Paper 26.2 build 134 проверен по SHA-256 официального downloads API.
EULA для локального теста явно разрешена пользователем.

1. Без PAPI: загрузка, регистрация API после миграций, zero/NEUTRAL, одинаковый профиль при
   повторных запросах, команды, безопасный unknown player, unregister API при выключении.
2. Полный restart с той же временной БД: тот же UUID и createdAt, те же проверки и cleanup.
3. С полным официальным релизом PAPI 2.12.3: регистрация расширения, score=0,
   score_formatted=0, локализованный tier, unregister расширения при выключении.

Логи: `/workspace/.setup/phase1-release-smoke/smoke-{1,2,3}.log` текущей машины.
Тестовые контейнеры сценария удалены после завершения; результаты/миры сохранены снаружи checkout.
Язык между рестартами доказан настоящими SQL-интеграционными тестами; команда языка отдельно
проверена адаптерным тестом. Клиент Minecraft с реальным игроком в smoke не использовался.

## Ограничения проверки

- PAPI 2.11.6 несовместима с этой версией Paper. Для integration target закреплена 2.12.3.
- Внешние проверки Mojang public keys (DNS) и сетевой anti-malware список PAPI (proxy 403)
  не прошли в изолированной среде. Проверки/проверка TLS не отключались. Домен
  `check.placeholderapi.com` добавлен в черновик сети для применения в настройках среды;
  работа этого внешнего списка после изменения ещё не подтверждена.
- Эти ошибки не помешали проверке нашего API, команд, БД и всех трёх placeholders;
  online-mode авторизация Mojang, внешняя eCloud/телеметрия PAPI, production TLS БД,
  клиентский UI, нагрузка и crash/power-loss не проверялись и не заявляются.
- GitHub Actions workflow добавлен; удалённый запуск CI не подменяется локальным PASS.
- Production deployment и публикация cloud snapshot не выполнялись этим кодом.

Обязательные проверки Phase 1 выполнены; голосование и Phase 2 не реализованы.
