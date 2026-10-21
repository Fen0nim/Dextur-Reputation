# DexturReputation 1.0.1

Hotfix переносимости сборки и файлового слоя. Игровая функциональность 1.0.0 сохранена.

- Исправлен подтверждённый отказ общего path guard на законном macOS `/var → /private/var` ancestry. Платформенно нейтральная граница доверенного root принимает канонические предки и внутренние ссылки с целью внутри root; выход за root, подмена и неожиданные low-level redirects отвергаются.
- Создание приватных файлов использует POSIX 0600 либо owner-only ACL на Windows. Проверяется идентичность каталогов и lock-файлов; атомарная замена не подменяется копированием.
- Directory fsync сохранён на Linux. На macOS Java 25 и Windows он недоступен через используемый NIO API: file force и atomic replacement сохранены, но metadata durability при отключении питания не обещается.
- Исправлена потеря Unix process lock после отказа дублирующему владельцу: registry проверяет путь/inode до открытия второго FD, OS-lock остаётся активным.
- Обычный `clean build` больше не требует Docker. Явный `releaseCheck` сохраняет все реальные MariaDB/MySQL tests и обязателен для проверки выпуска.
- Дополнены точные dependency checksums для холодного кеша; verification и версии/locks не ослаблены.
- Добавлены переносимые проверки OS-алиасов, ACL/POSIX, путей, блокировки вторым JVM, завершения процесса и безопасной очистки junction. Windows child JVM использует `java.exe`.
- CI: Java 25, обычная сборка на Linux/macOS/Windows; SQL verification отдельным Linux job.

Схемы V1–V7, формат JSON и публичный API не меняются; миграция данных не нужна. Тег `v1.0.0` сохранён. Перед заменой JAR выполните обычный backup и штатный restart.

[Команды сборки](BUILDING.md) · [Проверки и ограничения](1.0.1_PORTABILITY_TEST_REPORT.md).
