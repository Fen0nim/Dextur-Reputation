# Права 1.0.0

Полный список из plugin.yml. Wildcard/children grants не объявлены. Справка и версия общедоступны. Узлы голосования можно явно заменить в voting.yml; выдача custom nodes — ответственность администратора. Tier/LP mapping сам по себе не выдаёт administrative nodes.

| Permission | Default |
| --- | --- |
| `dexturreputation.use` | `true` |
| `dexturreputation.language` | `true` |
| `dexturreputation.status` | `op` |
| `dexturreputation.vote` | `true` |
| `dexturreputation.like` | `true` |
| `dexturreputation.dislike` | `true` |
| `dexturreputation.revoke` | `true` |
| `dexturreputation.history` | `true` |
| `dexturreputation.gui` | `true` |
| `dexturreputation.reasons` | `true` |
| `dexturreputation.recovery` | `true` |
| `dexturreputation.recovery.start` | `true` |
| `dexturreputation.recovery.status` | `true` |
| `dexturreputation.recovery.cancel` | `true` |
| `dexturreputation.recovery.admin` | `op` |
| `dexturreputation.policies` | `true` |
| `dexturreputation.admin.set` | `op` |
| `dexturreputation.admin.add` | `op` |
| `dexturreputation.admin.remove` | `op` |
| `dexturreputation.admin.voting.lock` | `op` |
| `dexturreputation.admin.voting.unlock` | `op` |
| `dexturreputation.admin.voting.status` | `op` |
| `dexturreputation.admin.policy` | `op` |
| `dexturreputation.admin.reload` | `op` |
| `dexturreputation.admin.inspect` | `op` |
| `dexturreputation.admin.history` | `op` |
| `dexturreputation.admin.vote.inspect` | `op` |
| `dexturreputation.admin.vote.invalidate` | `op` |
| `dexturreputation.admin.vote.restore` | `op` |
| `dexturreputation.admin.pending.view` | `op` |
| `dexturreputation.admin.pending.approve` | `op` |
| `dexturreputation.admin.pending.reject` | `op` |
| `dexturreputation.admin.trust.inspect` | `op` |
| `dexturreputation.admin.flags.inspect` | `op` |
| `dexturreputation.admin.flags.resolve` | `op` |
| `dexturreputation.admin.relations.inspect` | `op` |
| `dexturreputation.admin.recalculate.reputation` | `op` |
| `dexturreputation.admin.recalculate.trust` | `op` |
| `dexturreputation.admin.recalculate.tier` | `op` |
| `dexturreputation.admin.recalculate.all` | `op` |
| `dexturreputation.admin.audit` | `op` |
| `dexturreputation.admin.gui` | `op` |
| `dexturreputation.admin.bulk` | `op` |

Подкоманды `/rep` также проверяют use. Новые оценки требуют vote и like/dislike; revoke требует vote и revoke. Recovery требует recovery и отдельный action/status node. Административный GUI дополнительно проверяет права раздела/действия; bulk — bulk плюс mutation node. Trust/flags не доступны обычным игрокам, PAPI их не публикует. Console разрешена в пределах administration.yml; GUI требует Player. Списки tab completion фильтруются правами и не раскрывают базу неизвестных offline игроков.

[Команды](COMMANDS.md), [точные admin права](ADMINISTRATION.md).
