package org.dextur.reputation.paper;

import java.util.*;
import java.util.function.Function;

/** Only explicitly typed participant fields are player UUIDs. Vote/operation IDs are never identities. */
final class AuditIdentity {
    private AuditIdentity() { }
    static String delta(Map<String,String> values){
        for(String prefix:List.of("","_visible","_community")){String before=values.get("before"+prefix),after=values.get("after"+prefix);if(before==null||after==null)continue;
            try{var delta=new java.math.BigDecimal(after).subtract(new java.math.BigDecimal(before));return (delta.signum()>0?"+":"")+delta.stripTrailingZeros().toPlainString();}catch(NumberFormatException ignored){}
        }return null;
    }
    static boolean administrative(Map<String,String> values){return Set.of("SET","ADD","REMOVE","LOCK","UNLOCK","OVERRIDE","INVALIDATE","RESTORE","APPROVE","REJECT","RECALCULATE_REPUTATION","RECALCULATE_TRUST","RECALCULATE_TIER","RECALCULATE_ALL","RESOLVE_FLAG").contains(values.getOrDefault("action",""));}
    static Map<String,String> expand(Map<String,String> source,Function<UUID,String> names,String unknown,String console,String system){
        var result=new LinkedHashMap<String,String>();
        source.forEach((original,value)->{
            String field=original.equals("actor")&&administrative(source)?"administrator":original;
            if(field.equals("source_actor")&&value.equals("PLAYER")){result.put(field,value);return;}
            if(!Set.of("actor","administrator","target","voter","neighbor","source_actor").contains(field)){result.put(field,value);return;}
            if(value.equals("CONSOLE")||value.equals("SYSTEM")){result.put(field+"_name",value.equals("CONSOLE")?console:system);return;}
            try{var id=UUID.fromString(value);if(id.equals(new UUID(0,0))){result.put(field+"_name","—");return;}String name=names.apply(id);result.put(field+"_name",name==null||name.isBlank()?unknown:name);result.put(field+"_uuid",id.toString());}
            catch(IllegalArgumentException invalid){result.put(field+"_name",unknown);}
        });
        return result;
    }
}
