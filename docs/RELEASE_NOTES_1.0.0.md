# DexturReputation 1.0.0 — release notes

Первая стабильная версия для Paper 26.2 / Java 25. Функциональность завершённых этапов 1–7 сохранена.

- Voting: like/dislike с причинами, замена/отзыв, точный дробный вклад, история и pending moderation.
- Trust/anti-abuse: приватная модель доверия, проверяемые веса, сигналы риска и ручная классификация flags.
- Tiers/display: уровни с гистерезисом, безопасное владение TAB/scoreboard, GUI и RU/EN.
- Recovery: сохраняемые программы, активность, награды и идемпотентные receipts.
- Policies: async/cache API ограничений/преимуществ, admin overrides, необязательное здоровье без замены чужих modifiers.
- Administration: точные set/add/remove, voting locks, inspect/history/audit, invalidate/restore, пересчёты и подтверждаемые bounded bulk операции.
- Storage: JSON default, SQLite, MySQL/MariaDB, offline перенос с backup, verification и активацией в конце. Исторические formats/schemas 1–7 поддерживаются.
- Integrations: optional PlaceholderAPI и LuckPerms; DexturCore не требуется. Старые публичные API сохранены.

Hardening: observer callbacks изолированы от результата commit; storage paths отклоняют symlink ancestors; YAML errors не выводят исходные секретные строки; строгие UUID и bounded command input; offline CLI больше не содержит plugin.yml; SQL import использует bounded batches, обновляет статистику и строит active index один раз; глубокая пагинация выбирает сначала покрытые индексом IDs, inspect избегает повторных полных joins.

Ограничения: один storage owner; JSON full-snapshot IO/64 MiB default; растущие audit/receipts и hard caps; внешний LP/health effect eventually reconciled; нет Redis/Velocity/Folia/DexturCore requirement. DexturChat-side реализация, production TPS/SLA, физическое отключение питания и универсальная совместимость с packet plugins не заявлены. External vulnerability scan отмечается отдельно в отчёте.

Обновление: [UPGRADE](UPGRADE.md); backup обязателен. Это подготовленные release notes, публикация GitHub Release и tag требуют отдельного разрешения.
