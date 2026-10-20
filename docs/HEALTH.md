# Максимальное здоровье — Phase 6

Optional Paper adapter использует `health.maximum`; **health.enabled=false по умолчанию**. Он не выдаёт staff permissions и не реализует сильные дополнительные gameplay системы.

```yaml
config-version: 1
health: {enabled: true}
policies:
  health.maximum:
    rules:
      - {priority: 10, when: {tiers: [OFFENDER, SUSPICIOUS]}, decision: LIMITED, value: 18.0}
      - {priority: 20, when: {tiers: [RESPECTED]}, decision: LIMITED, value: 21.0}
      - {priority: 1000, decision: LIMITED, value: 20.0}
```

Shipped defaults: SABOTEUR/SERVER_THREAT=16, OFFENDER/SUSPICIOUS=18, NEUTRAL/RECOGNIZED=20, RESPECTED=21, HONORED/AUTHORITY=22, PROJECT_PRIDE=23, SUPREME_AUTHORITY=24. Все значения/conditions редактируются через policy rules; безопасный инженерный диапазон 2..40 health points, точность до 6 decimal places. 2 health points = одно стандартное сердце. DENY/UNAVAILABLE/disabled health снимает собственный effect, не отнимает всё здоровье.

`value` — target при vanilla baseline 20. Adapter добавляет **value − 20** через transient `ADD_NUMBER` modifier с ключом `dexturreputation:reputation-health`. Он не меняет base value и не удаляет чужие модификаторы. Например, foreign +4 и policy value 18 дают 22 при baseline 20. При foreign scalar/multiply effects итог определяется обычной арифметикой атрибута, поэтому конечный maximum не обязан равняться value. Все плагины должны использовать разные namespaced keys.

Reconciliation выполняется на join/enable, после tier changes, policy reload и bounded heartbeat раз в секунду (до 16 игроков за проход). Storage читает worker, только immutable результат передаётся на main thread; перед применением проверяется cached revision. Физический атрибут может отставать от нового committed решения до очередного прохода; policy result не является транзакцией с Bukkit attribute. Повторный reconcile проверяет существующий owned modifier и не создаёт stacking. При quit/disable удаляются только owned modifiers; transient effect не записывается как постоянная модификация игрока.

При снижении max current health безопасно ограничивается новым максимумом без установки нуля; дополнительное здоровье автоматически не лечит игрока. Если вычисленная композиция с чужими modifiers не конечна или меньше 2 points, собственный effect не применяется. Изначально небезопасная чужая настройка остаётся ответственностью её владельца: DexturReputation не переписывает чужие значения, чтобы её исправить.

Policy cache/PAPI показывают configured outputs отдельно от фактического effect. `%dexturreputation_health_modifier%` читает immutable cache фактического owned ADD_NUMBER, согласованный с policy revision. Miss → пустая строка, выключенная health после reconcile → 0. Он не означает итоговый max с чужими effects. Actual effect cache/pending requests ограничены 4096 entries; при отсутствии свежей записи не подставляется выдуманное значение.

При runtime IO-ошибке новый policy result не применяется; остаётся последний успешный health effect до восстановления, следующего успешного reconcile или disable. Это не переключение на другой backend.

`/repadmin reload` активирует валидный полный features candidate, сохраняя прежний при ошибке; после health disable собственный effect снимается bounded task. Другие settings/tiers/gui/locales требуют restart. Runtime/автоматические проверки ownership, foreign modifiers, safe decrease, reconnect, reload, shutdown и границы проверки — [PHASE_6_TEST_REPORT](PHASE_6_TEST_REPORT.md).
