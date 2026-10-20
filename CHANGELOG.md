# Phase 5 — 0.5.0-SNAPSHOT

- Versioned rehabilitation programs, ordered verified objectives, bounded activity tracking and immutable provider API.
- Atomic system rewards and audited bounded Trust credits; no changes to community votes.
- JSON format 5, SQLite/MySQL/MariaDB V5 recovery ledger, complete offline migrations.
- RU/EN recovery commands, GUI, cache-only PAPI and local committed events.
- Preserve Phase 1–4 API/config/history; stop before Phase 6.

# Changelog

## 0.3.0-SNAPSHOT — Phase 3

- Скрытый версионированный Trust: проверенная зрелость, история, CAS refresh и закрытый audit.
- Фиксированная точность 6 знаков, дробные веса, целый public score и сохранение Phase 2 contributions.
- Repeat lifetime ceiling, взаимные оценки, ограниченные группы, всплески и pending-review.
- Атомарные голосования и решения во всех backend; JSON v3, additive SQL V3 без изменения V1/V2.
- Offline migration переносит весь Trust/pending/audit; исходные данные сохраняются.
- Новые конфиги, полные RU/EN, совместимые outcome/status API и расширенный Paper QA.

## 0.2.0-SNAPSHOT — Phase 2

- Голоса +/− с причинами/комментариями, доверенная eligibility Paper, известные offline targets.
- Настраиваемые квоты, cooldown, repeat policy, безопасные replacement/revocation без накопления вклада.
- Immutable journal, idempotent operation UUID, atomic profile/vote commits во всех backend.
- JSON v2 с чтением Phase 1.1; additive MySQL V2 и SQLite schema 2; перенос полного журнала.
- VotingService, immutable views/history/receipts и after-commit subscriptions; прежний API сохранён.
- Команды, безопасный постраничный inventory GUI, RU/EN и additive config/locale upgrade.
- Расширенные локальные/SQL тесты и runtime QA с настоящими подключёнными игроками.

## 0.1.1-SNAPSHOT — Phase 1.1

- Единый storage.type JSON/SQLITE/MYSQL; JSON default для новых установок, MYSQL для legacy v1.
- Общий backend port, сохранение профилей и языка без изменений публичного API.
- JSON: checksummed generations, fsync + atomic replacement, предыдущая копия, explicit recovery.
- SQLite: WAL, FULL synchronous, busy timeout, транзакции, prepared statements и один writer.
- MySQL: прежние schema V1, pooling и data compatibility; ownership и pending guard для maintenance.
- Offline migration CLI с backup, полной сверкой, отказом от непустого target и atomic config activation.
- RU/EN storage diagnostics и новые bundled keys без перезаписи старых переводов.
- Тесты сбоев, коррупции и переносов, расширенная Paper runtime matrix, документация/CI artifacts.

## 0.1.0-SNAPSHOT — Phase 1

- Многомодульный проект, Java 25, Paper 26.2, фиксированные зависимости и Gradle Wrapper.
- Асинхронная инициализация/остановка, ограниченная очередь, immutable API и bounded TTL/LRU.
- MySQL/MariaDB, HikariCP, additive V1 с advisory lock и checksum; UUID + scope профили.
- Нулевые профили, нейтральный tier, сохранение языка, RU/EN и безопасный MiniMessage.
- Команды справки/версии/статуса/профиля/языка; необязательный PAPI-адаптер без SQL при чтении.
- Модульные и настоящие SQL-интеграционные тесты, отдельный Paper runtime probe, CI и документация.
- Удалены из отслеживания случайно закоммиченные кэши Gradle и HTML build reports.

Функции Phase 4–8 не включены. Итоговый стабильный релиз пока не объявлен.
