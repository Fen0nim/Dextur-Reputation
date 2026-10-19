# Optional LuckPerms integration

Default: `integrations.yml` → `luckperms.enabled: false`. LuckPerms is a soft dependency; absence never prevents reputation storage/API/voting/display from starting. When enabled but absent, a localized warning is emitted and durable assignments await a later startup with LuckPerms. The API is compile-only and is not bundled in the plugin.

```yaml
config-version: 1
placeholderapi: true
luckperms:
  enabled: true
  create-groups: false
  retry-seconds: 30
  batch-size: 16
  tiers:
    RECOGNIZED: reputation_recognized
    RESPECTED: reputation_respected
```

A mapping here overrides the tier entry's optional `luckperms-group`. Unknown tier IDs reject configuration. Empty group means no managed membership. Groups must match `reputation_[a-z0-9_]{1,48}` and already exist by default. Explicit `create-groups: true` permits only this validated namespace. No arbitrary staff/admin groups are created. Do not use reputation groups for staff/donation privileges.

Before assignment the adapter loads and checks the group. Only LuckPerms PrefixNode, SuffixNode, MetaNode and WeightNode are permitted. Permission nodes and group inheritance are rejected, including wildcards and administrative permissions. A missing/unsafe group defers synchronization with an actionable localized error; it never activates another reputation backend or changes the score. Administrators remain responsible for external consumers of cosmetic metadata and later edits to existing groups. In particular, do not later grant permissions to a reputation group already inherited by players: no plugin can make arbitrary subsequent administrator edits safe.

## Ownership

Managed inheritance nodes have the reserved single context `dexturreputation-owner=<scope>`. A registered player ContextCalculator makes that context available on this server. Only inheritance nodes in the validated reputation namespace with **exactly** this marker context are removed during reconciliation. This is ownership evidence independent of a database acknowledgment, so a crash after a LuckPerms save and before acknowledgment can be repaired. Do not manually create nodes using this reserved context; other assignments, including uncontextual assignments to the **same** reputation group, remain untouched. Primary group, staff/donation memberships, and unrelated permission nodes are never reset.

Durable tier state stores desired group, synchronization/applied revisions and owned group metadata. One asynchronous sequential batch (default 16, maximum 100) reconciles pending users, including offline UUIDs; successful saves are acknowledged with a revision comparison. A concurrent tier change leaves a new pending revision. Failures remain pending for a later interval. A rotating UUID cursor prevents permanently failing early rows from starving later players. Repeated synchronization is idempotent.

Overlapping ticks await the current batch. An external future that never completes stalls that batch without blocking Paper; do not start overlapping writes after an arbitrary timeout, since an old save could then finish after a newer assignment. Repair LuckPerms/connectivity and restart if its API is stuck. Disable stops new work and unregisters the context calculator; an already accepted external save may finish. Pending intent repairs missed updates on the next startup. There is no cross-server delivery guarantee or multi-writer support.

Disabling integration does not remove persisted memberships automatically. While disabled, the calculator is absent, so the managed context is not supplied by this adapter. To remove managed memberships intentionally, keep integration enabled, clear mappings, restart and allow reconciliation to complete. Back up both reputation and LuckPerms data before such changes. Never delete unrelated nodes as a cleanup shortcut.

Test coverage includes mock API failure/retry/unsafe groups, real full LuckPerms on Paper, offline synchronization, ownership and preservation of unrelated memberships. See [PHASE_4_TEST_REPORT.md](PHASE_4_TEST_REPORT.md) for exact verified artifacts and limits.
