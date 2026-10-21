package org.dextur.reputation.paper;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.dextur.reputation.application.Messages;
import org.dextur.reputation.application.ReputationApplication;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Loaded only when PlaceholderAPI is enabled. Cache misses return an empty value, never fake zero. */
final class PlaceholderBridge extends PlaceholderExpansion {
    private final DexturReputationPlugin plugin;
    private final ReputationApplication application;
    private final Messages messages;
    private org.dextur.reputation.api.TierService tiers;
    private org.dextur.reputation.api.RecoveryService recovery;
    private org.dextur.reputation.application.PolicyApplication policies;
    private PolicyEffects healthEffects;
    void healthEffects(PolicyEffects effects){healthEffects=effects;}
    private boolean policyPlaceholders=true;
    void policies(org.dextur.reputation.application.PolicyApplication policies,boolean enabled){this.policies=policies;policyPlaceholders=enabled;}
    private boolean recoveryPlaceholders=true;
    void recovery(org.dextur.reputation.api.RecoveryService recovery,boolean enabled){this.recovery=recovery;recoveryPlaceholders=enabled;}
    private org.dextur.reputation.application.DisplayFormatter formatter;
    void tiers(org.dextur.reputation.api.TierService tiers,org.dextur.reputation.application.DisplayFormatter formatter) {this.tiers=tiers;this.formatter=formatter;}
    PlaceholderBridge(DexturReputationPlugin plugin, ReputationApplication application, Messages messages) {
        this.plugin = plugin; this.application = application; this.messages = messages;
    }
    @Override public String getIdentifier() { return "dexturreputation"; }
    @Override public String getAuthor() { return "Dextur"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player, String parameter) {
        if (player == null || !plugin.ready()) return "";
        var id = player.getUniqueId();
        if(parameter.equals("policy_vote")||parameter.equals("policy_chat_send")||parameter.equals("health_modifier")){if(policies==null||!policyPlaceholders)return "";String policy=parameter.equals("policy_vote")?"reputation.vote":parameter.equals("policy_chat_send")?"chat.send":"health.maximum";return policies.cached(id,policy).map(result->parameter.equals("health_modifier")?(healthEffects==null?"":healthEffects.modifier(id,result.revision()).orElse("")):result.decision().name()).orElse("");}
        if(parameter.startsWith("recovery_")){
            if(recovery==null||!recoveryPlaceholders)return "";var s=recovery.cached(id);if(s.isEmpty())return "";var v=s.get();return switch(parameter){case "recovery_status"->v.status().name();case "recovery_stage"->v.stageId();case "recovery_progress"->Integer.toString(v.progressPercent());case "recovery_completed_tasks"->Integer.toString(v.completedTasks());case "recovery_total_tasks"->Integer.toString(v.objectives().size());case "recovery_eligible"->Boolean.toString(v.eligible());default->null;};
        }
        var cached = application.cachedProfile(id);
        if (cached.isEmpty()) return "";
        var language = application.cachedLanguage(id);
        var tier=tiers==null?java.util.Optional.<org.dextur.reputation.api.ReputationTierSnapshot>empty():tiers.cachedTier(id);
        if(tiers!=null&&tier.isEmpty()) return "";
        return switch (parameter) {
            case "score" -> Long.toString(cached.get().visibleScore());
            case "score_color" -> cached.get().visibleScore()>0?"green":cached.get().visibleScore()<0?"red":"gray";
            case "score_signed" -> (cached.get().visibleScore()>0?"+":"")+cached.get().visibleScore();
            case "score_bracketed" -> "("+(cached.get().visibleScore()>0?"+":"")+cached.get().visibleScore()+")";
            case "score_formatted" -> messages.number(language, cached.get().visibleScore());
            case "tier", "tier_name" -> tier.isPresent()?org.dextur.reputation.application.DisplayFormatter.plain(formatter.name(tier.get(),language)):PlainTextComponentSerializer.plainText().serialize(messages.tier(language));
            case "tier_id" -> tier.map(org.dextur.reputation.api.ReputationTierSnapshot::id).orElse(cached.get().tier());
            case "prefix" -> tier.map(t->org.dextur.reputation.application.DisplayFormatter.mini(formatter.prefix(t,language))).orElse("");
            case "suffix" -> tier.map(t->org.dextur.reputation.application.DisplayFormatter.mini(formatter.suffix(t,language))).orElse("");
            case "color" -> tier.map(t->"<"+t.color()+">").orElse("");
            case "positive" -> Boolean.toString(cached.get().visibleScore()>0);
            case "negative" -> Boolean.toString(cached.get().visibleScore()<0);
            case "reputation_formatted" -> tier.map(t->org.dextur.reputation.application.DisplayFormatter.mini(formatter.score(t,language))).orElse("");
            default -> null;
        };
    }
}
