# Сборка 1.0.1 на macOS, Windows и Linux

Используйте Java 25 и включённый Gradle Wrapper 9.7.0. Глобальная установка Gradle не нужна. В IntelliJ IDEA: Gradle distribution = Wrapper, Gradle JVM = Java 25; Project SDK также Java 25. Откройте корень проекта и выполните Gradle sync. Для обычной сборки Docker и настройка java.io.tmpdir не нужны.

## macOS / Linux

```sh
java -version
./gradlew --version
./gradlew clean build --no-build-cache
```

## Windows PowerShell

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat clean build --no-build-cache
```

Обычный build выполняет compilation, domain/application/Paper adapter tests, все локальные JSON/SQLite и release-hardening tests, dependency verification и упаковку. Он не запускает контейнерные MariaDB/MySQL tests. Это разделение задач, а не пропуск SQL-проверок в выпуске.

## Полная проверка выпуска

Для неё нужен работающий Docker с доступом к Linux containers. На macOS/Linux:

```sh
docker info
./gradlew releaseCheck --no-build-cache
```

На Windows: `docker info`, затем `.\gradlew.bat releaseCheck --no-build-cache`. `releaseCheck` зависит от обычного build и `:reputation-storage:integrationTest`; выполняются обе реальные SQL suites. Отсутствие Docker здесь приводит к ошибке, не к незаметному skip. CI выполняет normal build на Linux/macOS/Windows, SQL releaseCheck — отдельным Linux job.

## Только упаковка

```sh
./gradlew :reputation-paper:shadowJar
```

Windows: `.\gradlew.bat :reputation-paper:shadowJar`. Это основной shaded Paper JAR, а не неполный module JAR. Команда упаковки не заменяет тестирование перед выпуском.

Результат обычной сборки: `reputation-paper/build/libs/DexturReputation.jar`. Только этот JAR устанавливайте в Paper `plugins`. `DexturReputation-StorageAdmin.jar` — отдельный offline CLI; QA probe не нужен для сборки или установки плагина.

## Диагностика

Реальный тест локального хранилища:

```sh
./gradlew :reputation-storage:test --tests 'org.dextur.reputation.storage.LocalStorageTest' --stacktrace --no-build-cache
```

В PowerShell используйте `.\gradlew.bat` с тем же фильтром. XML/HTML находятся в `reputation-storage/build/test-results/test` и `build/reports/tests/test`. Смотрите первую IOException и полный cause chain. Параметр `-Djava.io.tmpdir` только для Gradle JVM не гарантирует его передачу forked test JVM; 1.0.1 не требует такого обходного пути.

Dependency verification и STRICT locks включены. Не используйте `--dependency-verification off`, broad trust или удаление verification metadata. При неизвестном checksum сохраните отчёт, проверьте координаты/источник и точный файл. 1.0.1 дополняет конкретные недостающие checksums из холодного разрешения, не меняя версии зависимостей.

Статус реально выполненных ОС и файловые ограничения — [отчёт 1.0.1](1.0.1_PORTABILITY_TEST_REPORT.md). Наличие CI job само по себе не подтверждает его удалённое исполнение.
