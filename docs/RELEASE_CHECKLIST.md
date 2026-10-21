# Checklist 1.0.0

Release readiness определяется фактическим [PHASE_8_TEST_REPORT](PHASE_8_TEST_REPORT.md), а не наличием этого списка. После изменения production кода повторите затронутые проверки и привяжите runtime evidence к новым SHA.

1. Сохранены 600 старых тестов, старые public API classes и V1–V7; никакой skipped mandatory проверки.
2. Java 25 / Paper 26.2 build 134; JSON, SQLite, MariaDB 11.4.8 и MySQL 8.4.7 проверены раздельно.
3. Fresh defaults, обе локали, malformed/custom configs, команды/permissions/console/known-offline и input boundaries проверены.
4. Genuine historical fixtures/schema 1–7 и отдельный Phase 1.1 SQLite/JSON прочитаны новой версией; новые QA данные явно обозначены synthetic.
5. Backup/restore каждого движка проверен: checksum, recovery copies/WAL/native consistent dump, новая цель, полное data equality и повторный plugin start.
6. Все шесть CLI направлений JSON/SQLite/MYSQL проверены отдельно с MariaDB и MySQL; partial pending target не включается; source/backups не удаляются.
7. Mixed contention, queue saturation, failures, child JVM interruption, corruption, busy timeout и ownership loss проверены.
8. Большие SQL наборы, bounded queries/index use и median/p95/p99 измерены; JSON cap не повышался ради теста.
9. Optional dependency matrix none/PAPI/LP/both, повторные JVM cycles, GUI/listeners/tasks/workers/services/health/teams/LP cleanup проверены.
10. Два независимых `./gradlew clean build --no-build-cache`; main/CLI побайтово совпали.
11. `python scripts/audit-release.py --baseline-jar <preserved-phase7-main.jar>`; main/CLI metadata, scopes, secrets/QA exclusion и 41 старый API class проверены.
12. Документация/CI/version/release notes сверены. Внешний OSV scan имеет отдельный фактический статус, отсутствие доступа не выдаётся за успех.
13. Diff просмотрен; в commit нет QA серверов, dump, credentials, logs и benchmark databases. Commit push в origin/work; независимый remote SHA равен local; tree clean.
14. STOP: main merge, tag v1.0.0 и GitHub Release требуют отдельного явного разрешения. Готовность не является разрешением на публикацию.
