package org.dextur.reputation.application;

import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.domain.tier.*;

/** Additive in-memory defaults. User files and custom tiers are never rewritten. */
public final class TierConfiguration {
    private TierConfiguration() { }
    public static Map<String,Object> configuration(Path directory,String name) throws java.io.IOException {
        var defaults=YamlFiles.resource("/"+name);var path=directory.resolve(name);
        return Files.exists(path)?merge(defaults,YamlFiles.read(path)):defaults;
    }
    public static Map<String,Object> merge(Map<String,Object> defaults,Map<String,Object> custom) {
        var result=new HashMap<>(defaults);custom.forEach((key,value)->result.put(key,value instanceof Map<?,?>&&defaults.get(key) instanceof Map<?,?>?merge(map(defaults.get(key)),map(value)):value));return Map.copyOf(result);
    }
    public static Map<String,Object> map(Object value) { return VotingConfiguration.mapping(value); }
    public static String string(Map<String,Object> row,String key) { if(!(row.get(key) instanceof String s)) throw new IllegalArgumentException(key+": string required");return s; }
    public static long integer(Map<String,Object> row,String key) { var v=row.get(key);if(!(v instanceof Integer)&&!(v instanceof Long)) throw new IllegalArgumentException(key+": integer required");return ((Number)v).longValue(); }
    public static TierRules load(Path directory) throws Exception {
        var y=configuration(directory,"tiers.yml");YamlFiles.version(y);
        var integrations=configuration(directory,"integrations.yml");YamlFiles.version(integrations);
        var lp=map(integrations.get("luckperms"));var mappings=map(lp.get("tiers"));
        var neutral=map(y.get("neutral"));if(!(y.get("tiers") instanceof List<?> rows)) throw new IllegalArgumentException("tiers: list required");
        var definitions=new ArrayList<TierRules.Definition>();var ids=new HashSet<String>();
        for(Object value:rows) {
            var d=map(value);String id=YamlFiles.text(d,"id");ids.add(id);var names=map(d.get("names"));var translations=new TreeMap<String,String>();
            for(String language:List.of("ru_RU","en_US")) {String text=YamlFiles.text(names,language);DisplayFormatter.validate(text,Set.of());translations.put(language,text);}
            String color=string(d,"color");DisplayFormatter.color(color);
            for(String key:List.of("prefix","suffix")) DisplayFormatter.validate(string(d,key),Set.of("tier","score"));
            var metadata=new TreeMap<String,String>();for(var entry:map(d.get("metadata")).entrySet()) {if(!(entry.getValue() instanceof String text)||text.length()>1000) throw new IllegalArgumentException("Tier metadata strings required");metadata.put(entry.getKey(),text);}
            if(metadata.size()>32) throw new IllegalArgumentException("Too much tier metadata");
            definitions.add(new TierRules.Definition(id,YamlFiles.flag(d,"enabled"),integer(d,"threshold"),integer(d,"hysteresis"),YamlFiles.number(d,"priority",0,10000),translations,color,string(d,"prefix"),string(d,"suffix"),mappings.containsKey(id)?string(mappings,id):string(d,"luckperms-group"),string(d,"discord-role"),YamlFiles.flag(d,"notifications"),metadata));
        }
        if(!ids.containsAll(mappings.keySet())) throw new IllegalArgumentException("LuckPerms mapping references unknown tier");
        String policy=definitions.stream().map(d->d.id()+":"+d.enabled()+":"+d.threshold()+":"+d.hysteresis()+":"+d.group()).sorted().collect(java.util.stream.Collectors.joining("\n"))+":"+integer(neutral,"minimum")+":"+integer(neutral,"maximum");
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(policy.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new TierRules(definitions,integer(neutral,"minimum"),integer(neutral,"maximum"),hash);
    }
}
