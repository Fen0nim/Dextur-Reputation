package org.dextur.reputation.storage;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;
import org.dextur.reputation.domain.policy.AdministrationData;
import org.dextur.reputation.domain.recovery.RecoveryData;
import org.dextur.reputation.domain.protection.ProtectionData;

/** Indexed copies for unified lookup; original ledgers remain authoritative and unchanged. */
final class InvestigationLegacy {
    static UUID id(String source,UUID id){return UUID.nameUUIDFromBytes((source+"/"+id).getBytes(StandardCharsets.UTF_8));}
    private static String safe(String text){return text.replaceAll("[\\p{Cntrl}&&[^\\t]]"," ");}
    private static String actor(String actor,UUID target){if(actor.equals("CONSOLE"))return actor;if(actor.equals("PLAYER"))return target.toString();try{return UUID.fromString(actor).toString();}catch(IllegalArgumentException ignored){return "SYSTEM";}}
    static Record administration(AdministrationData.Audit a){var r=a.request();var values=new TreeMap<String,String>();values.put("action",r.type().name());values.put("reason",safe(r.reason()));values.put("operation_original",r.operation().toString());values.put("before_visible",Long.toString(a.previousVisible()));values.put("after_visible",Long.toString(a.resultingVisible()));values.put("before_administrative",Long.toString(a.previousAdmin()));values.put("after_administrative",Long.toString(a.resultingAdmin()));values.put("before_lock",Boolean.toString(a.previousLock()));values.put("after_lock",Boolean.toString(a.resultingLock()));values.put("policy",r.policy());values.put("before_override",a.previousOverrides().getOrDefault(r.policy(),AdministrationData.Override.DEFAULT).name());values.put("after_override",a.resultingOverrides().getOrDefault(r.policy(),AdministrationData.Override.DEFAULT).name());values.put("overrides_before_count",Integer.toString(a.previousOverrides().size()));values.put("overrides_after_count",Integer.toString(a.resultingOverrides().size()));values.put("schema","1");return new Record(id("legacy-admin",r.operation()),Kind.AUDIT,r.scope(),actor(r.actor(),r.player()),r.player(),new UUID(0,0),"APPLIED",1,r.at(),values,List.of());}
    static Record recovery(RecoveryData.Audit a,UUID target,String scope){return new Record(id("legacy-recovery",a.id()),Kind.AUDIT,scope,actor(a.actor(),target),target,new UUID(0,0),"APPLIED",1,a.at(),Map.of("action","RECOVERY_"+a.kind(),"reason",safe(a.reason()),"operation_original",a.id().toString(),"before_status",a.fromStatus(),"after_status",a.toStatus(),"source_actor",safe(a.actor()),"schema","1"),List.of());}
    static Record protection(ProtectionData.Audit a){return new Record(id("legacy-protection",a.id()),Kind.AUDIT,a.scope(),actor(a.actor(),a.target()),a.target(),a.voteId(),"APPLIED",1,a.createdAt(),Map.of("action",a.kind(),"reason",safe(a.reason()),"operation_original",a.id().toString(),"before_community",InvestigationEngine.decimal(a.beforeUnits()),"after_community",InvestigationEngine.decimal(a.afterUnits()),"schema","1"),List.of(a.voteId()));}
    static List<Record> all(StorageSnapshot state){var records=new ArrayList<Record>();for(var row:state.administration().states())for(var audit:row.audit())records.add(administration(audit));for(var row:state.recovery().states())for(var audit:row.audit())if(!Set.of("PROGRESS_CHANGED","OBJECTIVE_COMPLETED").contains(audit.kind()))records.add(recovery(audit,row.player(),row.scope()));for(var audit:state.protection().audit())if(!Set.of("VOTE","ANALYSIS").contains(audit.kind())&&!audit.evidence().containsKey("moderation_model"))records.add(protection(audit));return records;}
    static Record marker(ProtectionData.Audit audit,ProtectionData.Status original,long revision){return new Record(audit.voteId(),Kind.MODERATION,audit.scope(),actor(audit.actor(),audit.target()),audit.target(),audit.voteId(),audit.status().name(),revision,audit.createdAt(),Map.of("operation",id("legacy-protection",audit.id()).toString(),"original_status",original.name(),"original_active",Boolean.toString(audit.oldActive().equals(audit.voteId().toString()))),List.of(audit.voteId()));}
    static List<Record> markers(StorageSnapshot state){var result=new ArrayList<Record>();var previous=new HashMap<UUID,ProtectionData.Status>();var last=new HashMap<UUID,Record>();
        for(var audit:state.protection().audit()){
            if(audit.kind().equals("ANALYSIS"))continue;
            if(audit.status()==ProtectionData.Status.INVALIDATED&&!audit.evidence().containsKey("moderation_model"))last.put(audit.voteId(),marker(audit,previous.getOrDefault(audit.voteId(),ProtectionData.Status.PENDING),1));
            if(Set.of(ProtectionData.Status.ACCEPTED,ProtectionData.Status.SUSPICIOUS,ProtectionData.Status.PENDING).contains(audit.status()))previous.put(audit.voteId(),audit.status());
        }
        for(var a:state.protection().assessments())if(a.status()==ProtectionData.Status.INVALIDATED&&state.investigation().get(Kind.MODERATION,a.voteId())==null&&last.containsKey(a.voteId()))result.add(last.get(a.voteId()));return result;
    }
}
