package org.dextur.reputation.application;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import org.dextur.reputation.api.VoteType;
import org.dextur.reputation.domain.*;

/** Validated once before storage opens. Existing files are never overwritten on upgrade. */
public record VotingConfiguration(VotingRules rules, int pageSize, Map<String,String> permissions,
        boolean notifyTarget, boolean openGuiOnProfile) {
    @SuppressWarnings("unchecked")
    public static VotingConfiguration load(Path directory, Messages messages) throws IOException {
        var y=YamlFiles.read(directory.resolve("voting.yml"));YamlFiles.version(y);
        var thresholds=new ArrayList<VotingRules.Threshold>();
        if(!(y.get("daily-thresholds") instanceof List<?> list)) throw new IllegalArgumentException("daily-thresholds must be a list");
        for(var item:list) {
            var row=mapping(item);thresholds.add(new VotingRules.Threshold(YamlFiles.number(row,"score",-1000000000,1000000000),YamlFiles.number(row,"limit",1,10000)));
        }
        var reasons=new TreeMap<String,VotingRules.Reason>();
        for(var entry:mapping(y.get("reasons")).entrySet()) {
            var row=mapping(entry.getValue());var types=EnumSet.noneOf(VoteType.class);
            if(!(row.get("types") instanceof List<?> values)) throw new IllegalArgumentException("Reason types required");
            for(var type:values) types.add(VoteType.valueOf(type.toString()));
            String key=YamlFiles.text(row,"message");
            for(var language:PlayerLanguage.values()) if(!Messages.placeholders(messages.template(language,key)).isEmpty()) throw new IllegalArgumentException("Reason messages cannot have placeholders");
            reasons.put(entry.getKey(),new VotingRules.Reason(entry.getKey(),key,types));
        }
        var permissions=new HashMap<String,String>();var nodes=mapping(y.get("permissions"));
        for(String key:List.of("vote","positive","negative","revoke","history","gui","reasons")) {
            String node=YamlFiles.text(nodes,key);
            if(!node.matches("[a-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Invalid permission: "+key);
            permissions.put(key,node);
        }
        var rules=new VotingRules(YamlFiles.number(y,"minimum-playtime-seconds",0,Integer.MAX_VALUE),
                YamlFiles.number(y,"minimum-account-age-seconds",0,Integer.MAX_VALUE),YamlFiles.number(y,"cooldown-seconds",0,Integer.MAX_VALUE),
                YamlFiles.number(y,"pair-cooldown-seconds",0,Integer.MAX_VALUE),YamlFiles.number(y,"base-daily-limit",1,10000),thresholds,
                ZoneId.of(YamlFiles.text(y,"reset-zone")),YamlFiles.number(y,"reset-hour",0,23),
                VotingRules.RepeatPolicy.valueOf(YamlFiles.text(y,"repeat-policy")),YamlFiles.flag(y,"allow-revoke"),
                YamlFiles.number(y,"maximum-comment-length",1,2000),YamlFiles.number(y,"positive-weight",1,1000000),
                YamlFiles.number(y,"negative-weight",1,1000000),reasons);
        return new VotingConfiguration(rules,YamlFiles.number(y,"history-page-size",1,36),Map.copyOf(permissions),
                YamlFiles.flag(y,"notify-target"),YamlFiles.flag(y,"open-gui-on-profile"));
    }
    public static Map<String,Object> mapping(Object value) {
        if(!(value instanceof Map<?,?> map)||map.keySet().stream().anyMatch(k->!(k instanceof String))) throw new IllegalArgumentException("YAML mapping required");
        @SuppressWarnings("unchecked") var result=(Map<String,Object>)map;return result;
    }
}
