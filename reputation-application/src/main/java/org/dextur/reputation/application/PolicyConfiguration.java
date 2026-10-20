package org.dextur.reputation.application;

import java.nio.file.Path;
import java.util.*;
import org.dextur.reputation.domain.policy.*;
import org.dextur.reputation.domain.protection.Fixed;
import org.dextur.reputation.api.ReputationPolicyResult.Decision;
import org.dextur.reputation.api.RecoverySnapshot.Status;
import static org.dextur.reputation.application.TierConfiguration.*;

/** Builds a complete immutable candidate; callers activate it only after all validation succeeds. */
public final class PolicyConfiguration {
    private PolicyConfiguration(){}
    public static PolicyRules load(Path directory)throws Exception{
        var yaml=configuration(directory,"features.yml");YamlFiles.version(yaml);keys(yaml,Set.of("config-version","administration","health","information","policies"));var admin=map(map(yaml.get("administration")).get("reputation"));keys(admin,Set.of("minimum-visible-score","maximum-visible-score","maximum-single-adjustment","require-reason"));keys(map(yaml.get("health")),Set.of("enabled"));keys(map(yaml.get("information")),Set.of("enabled"));
        var limits=new PolicyRules.Limits(integer(admin,"minimum-visible-score"),integer(admin,"maximum-visible-score"),integer(admin,"maximum-single-adjustment"),YamlFiles.flag(admin,"require-reason"));
        var definitions=new TreeMap<String,PolicyRules.Definition>();
        for(var entry:map(yaml.get("policies")).entrySet()){
            String id=entry.getKey();var d=map(entry.getValue());keys(d,Set.of("enabled","names","rules"));var names=strings(map(d.get("names")));for(var name:names.values())DisplayFormatter.validate(name,Set.of());
            var rules=new ArrayList<PolicyRules.Rule>();if(!(d.get("rules") instanceof List<?> rows))throw new IllegalArgumentException("Policy rules list required");
            for(var row:rows){var r=map(row);keys(r,Set.of("priority","when","decision","limit","multiplier","value"));var c=r.containsKey("when")?map(r.get("when")):Map.<String,Object>of();if(!Set.of("reputation-at-least","reputation-at-most","tiers","recovery","context").containsAll(c.keySet()))throw new IllegalArgumentException("Unknown policy condition");var tiers=new HashSet<String>();for(var tier:list(c,"tiers")){if(!(tier instanceof String value)||!value.matches("[A-Z_]{1,32}")||!tiers.add(value))throw new IllegalArgumentException("Policy tier");}var recovery=new HashSet<Status>();for(var state:list(c,"recovery"))if(!recovery.add(Status.valueOf((String)state)))throw new IllegalArgumentException("Duplicate recovery condition");
                var condition=new PolicyRules.Condition(c.containsKey("reputation-at-least")?integer(c,"reputation-at-least"):null,c.containsKey("reputation-at-most")?integer(c,"reputation-at-most"):null,tiers,recovery,c.containsKey("context")?strings(map(c.get("context"))):Map.of());
                rules.add(new PolicyRules.Rule(Math.toIntExact(integer(r,"priority")),condition,Decision.valueOf(string(r,"decision")),r.containsKey("limit")?integer(r,"limit"):null,r.containsKey("multiplier")?Fixed.decimal(r.get("multiplier")):Fixed.SCALE,r.containsKey("value")?Fixed.decimal(r.get("value")):null));
            }
            var definition=new PolicyRules.Definition(id,YamlFiles.flag(d,"enabled"),names,rules);
            if(id.equals("support.emergency-contact")&&(!definition.enabled()||rules.size()!=1||rules.getFirst().decision()!=Decision.ALLOW||!rules.getFirst().condition().unconditional()))throw new IllegalArgumentException("Emergency contact is non-overridable");
            definitions.put(id,definition);
        }
        if(definitions.size()>256||!definitions.keySet().containsAll(PolicyRules.BUILT_INS))throw new IllegalArgumentException("Policy definition bounds");
        boolean health=YamlFiles.flag(map(yaml.get("health")),"enabled"),info=YamlFiles.flag(map(yaml.get("information")),"enabled");
        var healthDefinition=definitions.get("health.maximum");for(var rule:healthDefinition.rules())if(rule.decision()!=Decision.DENY&&rule.valueUnits()==null)throw new IllegalArgumentException("Health requires a value");
        var bytes=new java.io.ByteArrayOutputStream();try(var canonical=new java.io.DataOutputStream(bytes)){canonical.writeInt(definitions.size());for(var definition:definitions.values()){canonical.writeUTF(definition.id());canonical.writeBoolean(definition.enabled());strings(canonical,definition.names());canonical.writeInt(definition.rules().size());for(var rule:definition.rules()){var c=rule.condition();canonical.writeInt(rule.priority());number(canonical,c.minimum());number(canonical,c.maximum());canonical.writeInt(c.tiers().size());for(String tier:new TreeSet<>(c.tiers()))canonical.writeUTF(tier);canonical.writeInt(c.recovery().size());for(var state:new TreeSet<>(c.recovery()))canonical.writeUTF(state.name());strings(canonical,c.context());canonical.writeUTF(rule.decision().name());number(canonical,rule.limit());canonical.writeLong(rule.multiplierUnits());number(canonical,rule.valueUnits());}}canonical.writeLong(limits.minimum());canonical.writeLong(limits.maximum());canonical.writeLong(limits.single());canonical.writeBoolean(limits.requireReason());canonical.writeBoolean(health);canonical.writeBoolean(info);}
        var digest=java.security.MessageDigest.getInstance("SHA-256");String fingerprint=HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        return new PolicyRules(definitions,limits,health,info,fingerprint);
    }
    private static List<?> list(Map<String,Object> y,String key){if(!y.containsKey(key))return List.of();if(!(y.get(key) instanceof List<?> list))throw new IllegalArgumentException("Policy list: "+key);return list;}
    private static void keys(Map<String,Object> values,Set<String> allowed){if(!allowed.containsAll(values.keySet()))throw new IllegalArgumentException("Unknown policy configuration key");}
    private static void number(java.io.DataOutputStream output,Long number)throws java.io.IOException{output.writeBoolean(number!=null);if(number!=null)output.writeLong(number);}
    private static void strings(java.io.DataOutputStream output,Map<String,String> values)throws java.io.IOException{output.writeInt(values.size());for(var entry:new TreeMap<>(values).entrySet()){output.writeUTF(entry.getKey());output.writeUTF(entry.getValue());}}
    private static Map<String,String> strings(Map<String,Object> y){var result=new TreeMap<String,String>();y.forEach((k,v)->{if(!(v instanceof String text))throw new IllegalArgumentException("Policy text required");result.put(k,text);});return result;}
}
