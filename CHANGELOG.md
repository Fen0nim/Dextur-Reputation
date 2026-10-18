# Changelog

## 0.1.0-SNAPSHOT — Phase 1

- Многомодульный проект, Java 25, Paper 26.2, фиксированные зависимости и Gradle Wrapper.
- Асинхронная инициализация/остановка, ограниченная очередь, immutable API и bounded TTL/LRU.
- MySQL/MariaDB, HikariCP, additive V1 с advisory lock и checksum; UUID + scope профили.
- Нулевые профили, нейтральный tier, сохранение языка, RU/EN и безопасный MiniMessage.
- Команды справки/версии/статуса/профиля/языка; необязательный PAPI-адаптер без SQL при чтении.
- Модульные и настоящие SQL-интеграционные тесты, отдельный Paper runtime probe, CI и документация.
- Удалены из отслеживания случайно закоммиченные кэши Gradle и HTML build reports.

Голосование и все функции Phase 2–8 не включены. Итоговый стабильный релиз пока не объявлен.
