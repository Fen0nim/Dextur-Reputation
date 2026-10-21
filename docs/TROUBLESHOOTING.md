# Диагностика 1.0.0

- **Нет READY:** проверьте Java 25/Paper 26.2, имя YAML и диагностическое сообщение. Невалидный custom файл не заменяется defaults. Исправьте указанный параметр при остановленном сервере и перезапустите.
- **Backend недоступен:** проверьте выбранный storage.type, наличие переменной пароля (не печатайте значение), DNS/порт, CA/hostname/TLS, права на schema и отсутствие второго владельца. Fallback отсутствует намеренно.
- **JSON близок к 64 MiB:** administration.yml задаёт warning threshold (default 75%). Запланируйте stopped backup и перенос в SQLite/MySQL. Oversized write отвергается до замены; последний валидный snapshot сохраняется. Простое переключение selector создаёт другой источник данных и не является переносом.
- **Integrity/recovery ошибка:** остановите writers, сохраните все файлы и logs без секретов; используйте verify и явный recover-json. Не удаляйте .bak/.tmp/lock и не меняйте checksum/revision вручную. OS lock освобождается при завершении процесса; наличие имени lock-файла само по себе не означает живого владельца.
- **Migration pending:** target не прошёл activation verification. Не включайте его вручную. Исходный storage.yml и backup остаются; повторяйте перенос в новый пустой target после выяснения причины. Старую неудачную цель сохраните для диагностики.
- **Очередь занята:** bounded queue отвергает новые операции, accepted futures завершаются. Проверьте latency backend и настройки workers/pool, не повышайте пределы вслепую. Не повторяйте неопределённый add/vote как новую операцию до проверки commit.
- **Уровень/LP/display запаздывает:** данные commit раньше внешнего эффекта. Проверьте desired/applied revisions, ownership группы/TAB/team, optional plugin и настройки контекста. Плагин сохраняет чужие узлы/модификаторы; внешний эффект не является SQL transaction.
- **Reload:** `/repadmin reload` только features.yml; storage/языки/GUI/administration требуют restart. Не используйте Bukkit `/reload`.
- **Оценка вызывает сомнение:** [VERIFY → REPORT → DRY RUN → CONFIRM → REPAIR](BACKUP_RESTORE.md). Trust/flags доступны только отдельным административным правам. Correlation не доказывает abuse.

В обращении укажите версию, Java/Paper, backend, безопасную диагностику и шаги воспроизведения. Не прикладывайте credentials, полный environment, приватный dump или необработанную конфигурацию.
