# Отчёт проверки Phase 2

Дата: 2026-10-18. Версия: 0.2.0-SNAPSHOT. Рабочая ветка: `work`.
Исходная Phase 1.1: `890dbaf1942ae6b15e3d8e3c1d4f9154692f48db`, чистое дерево.
Phase 1/1.1 и прежний API проверены перед расширением. Phase 3 не реализована.

## Обязательная сборка

```sh
JAVA_HOME=/workspace/.setup/tools/jdk-25+36 \
GRADLE_USER_HOME=/workspace/.setup/gradle-home \
./gradlew clean build --no-build-cache
./gradlew :reputation-paper:runtimeProbeJar
```

BUILD SUCCESSFUL, **106 tests, 0 failures, 0 errors, 0 skipped**; 36 tasks executed.
RuntimeProbe JAR затем собран отдельно; QA не входит в основной plugin JAR.

| Suite | Выполнено | Результат |
|---|---:|---|
| domain / VotingEngine | 5 | PASS |
| application / config, locale, queue/cache | 17 | PASS |
| storage / JSON, SQLite, migration, voting service | 42 | PASS |
| storage integration / MariaDB 11.4.8 | 16 | PASS |
| storage integration / MySQL 8.4.7 | 16 | PASS |
| Paper adapters / GUI interaction | 10 | PASS |

SQL suites используют реальные Testcontainers, Docker обязателен, пропусков нет.
Имитированный сбой SQL происходит после вставки журнала, при обновлении профиля;
проверяется полный ROLLBACK. Для MySQL применяется CHECK constraint, не требующий SUPER.

Проверены eligibility/playtime/возраст/права/self/unknown, обязательные причины, Unicode и
небезопасный MiniMessage ввод, cooldown и граница паузы, daily limit, timezone/DST,
repeat policy, замена/отзыв/повтор после отзыва, неизменность system/baseline, расчёт весов,
concurrent voters и многократный один operation ID. Проверены conflict IDs, отсутствие события
на отказ/дубликат/ошибку, изоляция listener exception, немедленное обновление profile/PAPI cache, текстовый offline `/rep` и отсутствие success feedback на IO failure,
restart persistence, JSON failures до записи/rename и после rename, повтор неопределённого
результата без двойного эффекта, SQL failures, integrity/checksum/request hash/profile/index
corruption, чтение JSON v1, все шесть направлений переноса полного журнала в обеих СУБД,
backup verification, pending guard и сохранение исходных данных. Отдельно восстановлены
исходные SQL schema 1 с nonzero profiles/languages: SQLite, MySQL и MariaDB обновлены до 2
без изменения существующих полей и V1 checksum.

GUI suite отменяет shift/number/double/offhand/drop/creative/right/bottom/outside clicks и drag;
проверяет один принятый click, busy, stale session, чужого владельца, disconnect/disable,
невалидный material, пересечения слотов и неверный размер; shutdown закрывает только
собственные GUI до удаления listeners. Последнее проверяет также native Paper probe. Config/locale suite проверяет
defaults, enum/bounds/threshold ordering/permission/reason validation, RU/EN parity, MiniMessage
и additive upgrade без перезаписи custom messages. Старые Phase 1/1.1 тесты сохранены.

## Реальный Paper 26.2 / Java 25

Окончательный JAR проверен в **20 успешных запусках**, без PROBE_FAIL.

| Конфигурация | Phase 2 + restart + PAPI | Прежний workflow с defaults | Итого |
|---|---:|---:|---:|
| JSON | 3 PASS | 2 PASS | 5 |
| SQLITE | 3 PASS | 2 PASS | 5 |
| MYSQL / MariaDB | 3 PASS | 2 PASS | 5 |
| Legacy MYSQL config v1 | 3 PASS | 2 PASS | 5 |

Markers: 20 PROBE_PASS, 12 PROBE_VOTING_PASS, 4 PROBE_PAPI_PASS. Все восемь
server directories содержат JAR с тем же SHA-256, что указан ниже. В каждом из четырёх
voting-сценариев журнал сохранил 15 операций после трёх запусков. Native shutdown assertion
подтвердила, что ни один GUI DexturReputation не остался открыт после снятия listeners.
В QA исправлено предварительное разрешение класса VotingService до отключения dependency
classloader; прежний workflow полностью повторно проверен после исправления.

Воспроизводимый сценарий:

```sh
./gradlew :reputation-paper:runtimeProbeJar
python3 scripts/paper-smoke.py --accept-eula --voting \
  --storage-type JSON --directory /tmp/new-empty-phase2-json \
  --placeholderapi-jar /path/to/PlaceholderAPI-2.12.3-full-release.jar
```

Повторить для SQLITE и MYSQL; для legacy добавить `--legacy-mysql --storage-type MYSQL`.
Без `--voting` выполняется прежний workflow с production defaults; без PAPI — два запуска.
Paper 26.2 build 134 и embedded Mojang cache проверяются SHA-256; EULA ранее явно разрешена.

`--voting` запускает двух настоящих локальных клиентов протокола 776. Target входит и выходит,
затем голосование выполняется по известному offline UUID через production VotingService.
Проверяются native `/like`, `/rep revoke`, history/given/reasons/language/gui commands,
reason tab completion, права, дубликаты, 12 параллельных повторов, замена/отзыв, пять новых
committed events за запуск, отрицательные/положительные/unique stats, restart, GUI reason
selection и отмена переносов/повторов, closed-window callback, закрытие GUI при disable, PAPI cache score=1 после commit.

Каждый запуск также сохраняет прежние проверки: startup, выбранный backend, ReputationService,
нулевой профиль/uniqueness/createdAt restart, диагностика, неизвестный игрок, PAPI score/formatted/
tier (когда установлен), unregister обоих API/PAPI и shutdown cleanup. Новые run markers
обязательны; одного exit code/открытого порта недостаточно.

## Отдельные проверки артефактов и CLI

Готовый standalone JAR выполнен с RU и EN:
JSON → SQLite → новый JSON после трёх настоящих Paper voting runs. **15 events, 3 profiles,
1 language preference** совпали полностью; old source и backups сохранены. Итоговые active votes=0,
поскольку каждый прогон заканчивается отзывом. CLI создал VERIFIED receipts с одинаковым hash.

Скрипт onboarding `/workspace/.setup/install.sh` реально повторно выполнен: Java/Gradle,
TLS/proxy, plugin/StorageAdmin/runtimeProbe собраны с external output paths. JAR SHA-256
совпадают с обычной сборкой. Dependency locks обновлены только для применения уже выбранного
Adventure plain serializer в main-коде; версии зависимостей не изменены.

Основной JAR содержит Java 25 bytecode (major 69), voting/gui resources, SQL V1/V2 и JDBC
service descriptors. QA-классов, повторных Adventure/SLF4J классов нет. SQL V1 и исходные
ReputationService/ReputationSnapshot сохранены без изменения. `git diff --check`, Python
syntax checks и scan изменённых файлов на private-key/known-token signatures выполнены.

SHA-256 устанавливаемого JAR:
`8d226a703f662ff147d652aabf2d06782382010f3faf727c7bcaff7782e92c14`.

SHA-256 offline StorageAdmin:
`a24ea1e55f012c4dcaf79cc5124af9c50624d70450f85aa73ff9648df2d6d8aa`.

## Границы проверки

Linux amd64, local POSIX filesystem, один владелец backend. JSON переписывает полный снимок
и имеет ограничение 64 MiB; SQL startup/migration validation также читает весь журнал.
Возраст аккаунта означает время с первого входа на сервер. Уведомления/events не являются
persistent outbox; после неопределённого commit API retry требует того же ID. Production
Mojang authentication, внешний PAPI update service, production TLS, ручной pixel UI review,
Folia/multi-server writers/нагрузка и реальная потеря питания не проверялись. Background
HTTP/DNS ошибки внешних Minecraft/PAPI сервисов не считаются проверкой этих сервисов.
QA eligibility/cooldowns/quota ослаблены только в `--voting` конфигурациях; обычный workflow
использует defaults, а их граничные значения проверены отдельно модульно.

CI workflow обновлён для Phase 2, но удалённый GitHub Actions run не заявляется выполненным.
Логи/миры/DB и временные credentials не входят в Git. Итоговый commit и независимо проверенный
remote SHA сообщаются после push в `origin/work`; `main` не сливается и не переписывается.


Локальные свидетельства этой сессии (вне Git): `/workspace/.setup/phase2-release-build.log`,
`phase2-release-matrix.log`, каталоги `phase2-release-{voting,regression}-{JSON,SQLITE,MYSQL,LEGACY}`,
`phase2-release-cli-{ru,en}.log` и `phase2-release-onboarding.log`. Полные XML/HTML test reports
находятся в стандартных ignored `reputation-*/build/test-results` и `build/reports/tests`.
