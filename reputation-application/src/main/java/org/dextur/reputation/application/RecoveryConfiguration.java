package org.dextur.reputation.application;
import java.nio.file.Path;
import java.util.*;
import org.dextur.reputation.domain.recovery.*;
import static org.dextur.reputation.application.TierConfiguration.*;
/** Version 1; additive defaults in memory, immutable definitions frozen by the domain at enrollment. */
public final class RecoveryConfiguration {
    private RecoveryConfiguration(){}
    public static RecoveryRules load(Path directory)throws Exception{
        var y=configuration(directory,"recovery.yml");YamlFiles.version(y);var r=map(y.get("recovery"));var e=map(r.get("eligibility"));var enrollment=map(r.get("enrollment"));var activity=map(r.get("activity"));var rewards=map(r.get("rewards"));var violations=map(r.get("violations"));var p=map(r.get("program"));
        if(integer(enrollment,"maximum-active-programs")!=1)throw new IllegalArgumentException("Exactly one active recovery program is supported");
        if(!string(violations,"suspicious-signal").equals("NONE"))throw new IllegalArgumentException("Suspicion is not a confirmed violation");
        var stages=new ArrayList<RecoveryRules.Stage>();for(Object row:list(p,"stages")){var s=map(row);var objectives=new ArrayList<RecoveryRules.Objective>();for(Object item:list(s,"objectives")){var o=map(item);objectives.add(new RecoveryRules.Objective(string(o,"id"),RecoveryRules.Type.valueOf(string(o,"type")),integer(o,"required"),o.containsKey("provider")?string(o,"provider"):"",o.containsKey("provider-version")?Math.toIntExact(integer(o,"provider-version")):1,o.containsKey("configuration")?strings(map(o.get("configuration"))):Map.of(),localized(o,"names"),localized(o,"descriptions")));}stages.add(new RecoveryRules.Stage(string(s,"id"),localized(s,"names"),string(s,"icon"),objectives,integer(s,"reputation"),integer(s,"trust-credit-units")));}
        return new RecoveryRules(YamlFiles.flag(r,"enabled"),integer(e,"reputation-at-or-below"),multiply(e,"minimum-playtime-hours",3600),multiply(e,"minimum-server-age-days",86400),YamlFiles.flag(enrollment,"manual"),YamlFiles.flag(enrollment,"automatic"),multiply(enrollment,"repeat-cooldown-days",86400),multiply(enrollment,"expiration-days",86400),string(activity,"timezone"),integer(activity,"inactivity-timeout-seconds"),Math.toIntExact(integer(activity,"minimum-meaningful-activity")),integer(activity,"eligible-day-active-seconds"),Math.toIntExact(integer(activity,"persistence-seconds")),Math.toIntExact(integer(activity,"batch-size")),Math.toIntExact(integer(activity,"maximum-tracked-players")),integer(rewards,"maximum-per-program"),integer(rewards,"maximum-lifetime-recovery"),integer(rewards,"maximum-program-trust-credit-units"),integer(rewards,"maximum-lifetime-trust-credit-units"),RecoveryRules.Action.valueOf(string(violations,"confirmed-abuse")),RecoveryRules.Action.valueOf(string(violations,"administrative-lock")),new RecoveryRules.Program(string(p,"id"),Math.toIntExact(integer(p,"version")),localized(p,"names"),stages));
    }
    private static long multiply(Map<String,Object> m,String key,long factor){return Math.multiplyExact(integer(m,key),factor);}
    private static List<?> list(Map<String,Object> m,String key){if(!(m.get(key) instanceof List<?> l))throw new IllegalArgumentException(key+": list required");return l;}
    private static Map<String,String> strings(Map<String,Object> m){var result=new TreeMap<String,String>();m.forEach((k,v)->{if(!(v instanceof String s))throw new IllegalArgumentException("String required: "+k);result.put(k,s);});return result;}
    private static Map<String,String> localized(Map<String,Object> m,String key){var names=strings(map(m.get(key)));for(String language:List.of("ru_RU","en_US"))DisplayFormatter.validate(Objects.requireNonNull(names.get(language)),Set.of());return names;}
}
