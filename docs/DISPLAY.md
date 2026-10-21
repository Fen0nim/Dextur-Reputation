# Display and ownership (Phase 4)

`display.yml` version 1 controls reputation formatting, nameplates, TAB and notifications. Existing files receive missing fields as in-memory defaults. Restart applies changes.

## Formatting

`reputation.positive-format` is `(+{score})`, negative `(-{score})`, neutral `(0)`. `{score}` in these three templates is the absolute number, including safe handling of Long.MIN_VALUE. Colors default to green/red/gray hexadecimal values; `tier-colors: true` uses the current tier's color. `thousands` defaults true; `compact` defaults false. Number formatting uses the player's stored ru_RU/en_US locale. Prefix/suffix are configured per tier. Formatting never queries storage.

Display templates accept `{player}`, signed `{score}`, `{reputation}` (colored formatted score), `{tier}` (localized name), `{prefix}` and `{suffix}`. Untrusted player names and descriptions are literal Adventure components. MiniMessage templates themselves are trusted administrator configuration. Length limits count Unicode code points; truncation returns literal plain text and loses formatting. Score and tier placeholder output conventions below are explicit so external plugins can choose their serializer.

## Nameplates

Default: `nameplate.mode: DISABLED`. Opt in with `TEAM_IF_FREE`, default format `' {reputation}'`, maximum length 80. Visibility: `ALWAYS`, `NEVER`, `HIDE_FOR_OTHER_TEAMS`, `HIDE_FOR_OWN_TEAM`.

Paper's scoreboard team suffix gives the requested text to the right of the name above the head where the client renders nameplates. The adapter visits existing viewer scoreboards; it never calls `Player.setScoreboard`, replaces objectives, resets a scoreboard, modifies a foreign team or deletes a team merely because its name looks like ours. A team name collision causes a skip. Team ownership uses the actual created Paper team (equality compares its backing team), not wrapper identity or name alone. If a player belongs to a foreign team on a viewer's board, that viewer gets no DexturReputation suffix. Use the external TAB plugin's PlaceholderAPI format in that case.

Only exclusively owned teams are updated and removed on quit, scoreboard change and disable. Cleanup rechecks ownership immediately, including changes made between refreshes. If another plugin adopts our team by adding members or changing prefix/suffix/visibility, ownership is relinquished without changing its members or deleting it. Such an adopted team is the other plugin's responsibility. Replacing a team with a different team of the same name does not transfer ownership. No adapter can promise universal compatibility with arbitrary scoreboard packet manipulation. Real server tests exercise foreign teams and a custom viewer scoreboard; no manual pixel inspection or every TAB plugin/version is claimed.

Names on a shared board use the **subject player's** language; per-viewer localization requires separate boards or an external TAB renderer. Nameplates respect the server/client's ordinary visibility and distance rules. No text entities, passenger entities or persistent world decorations are created.

## TAB list

`tab.mode` is one of:

| Mode | Behavior |
| --- | --- |
| DISABLED | No player-list writes. |
| PLACEHOLDER_ONLY (default) | No writes; configure an external formatter using PAPI/API. |
| DIRECT | Writes the configured `'{prefix}{player} {reputation}'`, maximum 160 code points. |

DIRECT does not activate when TAB, AnimatedTabList or CMI is already enabled. It claims only the default player-list name; an initially custom name is preserved. After its first write, an unexpected change yields ownership until the player reconnects. Cleanup restores the prior value only when the current value still equals the adapter's last write. Unknown plugins that race only at packet level cannot be detected reliably; use PLACEHOLDER_ONLY when another plugin owns formatting.

`refresh.interval-ticks` defaults 40 (allowed 20–12000), `batch-size` 32 (1–100), `maximum-teams` 4096 (1–16384). Each refresh captures online players and distinct active scoreboards, then processes at most one batch of subjects. A subject's changes become visible on its next batch turn. Main-thread work is O(online players + batch × distinct boards), bounded by the team cap; there is no every-tick full scan. Profile/tier caches and notification queues are bounded by existing config limits. Formatting is cache-only. Oversized servers should prefer external TAB ownership and tune interval/batch/caps. There is no production TPS/SLA claim.

## PlaceholderAPI / chat

Requires the complete PlaceholderAPI release (verified with 2.12.3), not its Maven compile-only API jar. Expansion identifier: `dexturreputation`. Offline players are supported only with a fresh cached immutable profile **and** tier. Unknown, expired, evicted or unready data returns `""`; no fake zero, synchronous IO, name lookup or hidden Trust appears. Unknown parameter names return null when data is present. Quit evicts caches; an explicitly asynchronous `TierService.getTier(UUID)` can warm an offline UUID. Language falls back to the plugin default until cached language is available.

| Placeholder suffix | Return value |
| --- | --- |
| score | Existing plain decimal visible score: `100`, `-25`, `0`; no leading plus or separators. |
| score_color | Plain color name by score sign: positive `green`, zero `gray`, negative `red`; no tags or angle brackets. |
| score_signed | Plain signed decimal: `+100`, `-25`, `0`. |
| score_bracketed | Plain signed decimal in parentheses: `(+100)`, `(-25)`, `(0)`. |
| score_formatted | Existing contract: localized grouped signed number; plain text. |
| tier | Existing contract: localized **plain tier name**, not an ID. |
| tier_name | Same plain localized name. |
| prefix / suffix | Configured decoration serialized as **MiniMessage** (possibly empty). |
| color | Opening MiniMessage color tag, e.g. `<#55ff55>`; consumer closes the color span. |
| positive / negative | Lowercase `true`/`false` for score > 0 / < 0. These are sign flags, not vote counts. |
| tier_id | Additional stable internal ID. |
| reputation_formatted | Additional configured colored score serialized as MiniMessage. |

Wrap a suffix as `%dexturreputation_<suffix>%`. For a MiniMessage-aware external formatter, use `%dexturreputation_prefix%<player> %dexturreputation_reputation_formatted%`. Its player substitution must be literal. A legacy-only formatter needs a trusted MiniMessage-to-legacy adapter on its side; these values are not ampersand color codes. Preserve unknown/missing data behavior in external formatting.

For consumers such as DexturChat that do not parse MiniMessage returned by PlaceholderAPI, use plain data and let the consumer own formatting:

```text
<%dexturreputation_score_color%>(%dexturreputation_score_signed%)</%dexturreputation_score_color%>
```

`score`, `score_signed` and `score_bracketed` use the cached visible score directly, without localization, separators, MiniMessage tags, color codes or Adventure serialization. Their existing cache/readiness rules still apply: missing data returns an empty string, not `0`. `score_color` follows the same cache/readiness rules and returns only `green`, `gray` or `red` based on the visible score sign, independently of configured tier colors. `reputation_formatted` retains its existing MiniMessage output unchanged. `%dexturreputation_reputation%` is not a registered placeholder.

Existing suffixes retained unchanged (wrap each as `%dexturreputation_<suffix>%`):

- Score/display: `score`, `score_signed`, `score_bracketed`, `score_formatted`, `tier`, `tier_name`, `tier_id`, `prefix`, `suffix`, `color`, `positive`, `negative`, `reputation_formatted`.
- Recovery: `recovery_status`, `recovery_stage`, `recovery_progress`, `recovery_completed_tasks`, `recovery_total_tasks`, `recovery_eligible`.
- Policy/health: `policy_vote`, `policy_chat_send`, `health_modifier`.

Added plain-text suffix: `score_color`.

DexturReputation registers no chat interception/channel replacement and changes no other repository.

## Notifications

Default: enabled chat, action bar/title disabled, no sound, cooldown 10 seconds. Independent switches: gain, loss, negative, severe, recover, neutral. Each destination tier can disable its notifications. RU/EN templates live in `lang/*.yml` (`tier-*`). Title and namespaced sound are optional; configured sound names are syntactically validated. Effects run on Paper's thread after durable commit. A bounded queue drains at most `refresh.batch-size` per refresh; saturation drops best-effort notifications, not committed transitions or permission intent. Offline/cooldown-suppressed notifications are not replayed on login. Repeated reads, reconnects and restart do not emit old transitions. No hidden Trust/factors are included.


## Phase 5

Старые значения сохранены. Шесть recovery_* placeholders читают только immutable RecoveryService cache, без SQL/Trust. Форматы и miss behavior: [RECOVERY.md](RECOVERY.md).


## Phase 6

Добавлен отдельный публичный policy panel и cache-only policy_vote/policy_chat_send/health_modifier, без Trust/SQL в expansion. Integration status отдельно от policy definition; actual health effect отличается от configured proposal. [POLICIES](POLICIES.md), [HEALTH](HEALTH.md).
