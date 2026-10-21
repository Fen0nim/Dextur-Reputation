# Установка 1.0.0

Требуются Java 25 и Paper 26.2 build 134. Проверка Java: `java -version`. Используйте официальный Paper, настройте EULA и online-mode согласно правилам своего сервера. Протокол QA с offline-mode применим только к изолированным локальным тестам.

Поместите DexturReputation.jar в plugins и запустите сервер. Успешная инициализация завершается READY с версией. Проверьте `/dexturreputation version`, `/dexturreputation status`, `/dexturreputation storage`, `/rep` и обе локали. До READY сервис не объявляется готовым. Неверная конфигурация или повреждённое хранилище блокируют включение; другая база автоматически не выбирается.

В новой установке JSON, `scope: global`, язык ru_RU. Для SQL задайте единственный storage.type, все параметры подключения и переменную окружения процесса с паролем. Для production оставьте `ssl-mode: verify-full`, обеспечьте доверенный CA Java и совпадение hostname. Не помещайте пароль в YAML, JVM arguments или отчёт. SQLite и JSON не требуют SQL credentials.

PlaceholderAPI 2.12.3 и LuckPerms 5.5.87 проверены как необязательные плагины. Их отсутствие допустимо; runtime-дистрибутивы в DexturReputation не вложены. Настройки интеграций: integrations.yml, display.yml, tiers.yml. Не устанавливайте StorageAdmin или QA probe в plugins.

После первого запуска остановите сервер, сохраните YAML и данные, настройте правила и выполните перезапуск. Не используйте Bukkit `/reload` или стороннюю горячую выгрузку. `/repadmin reload` обновляет только features.yml атомарно: неверный кандидат не заменяет действующие политики.

Дальше: [конфигурация](CONFIGURATION.md), [права](PERMISSIONS.md), [backup](BACKUP_RESTORE.md), [обновление](UPGRADE.md).
