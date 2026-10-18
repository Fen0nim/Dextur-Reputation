# Changelog

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

Голосование и все функции Phase 2–8 не включены. Итоговый стабильный релиз пока не объявлен.
