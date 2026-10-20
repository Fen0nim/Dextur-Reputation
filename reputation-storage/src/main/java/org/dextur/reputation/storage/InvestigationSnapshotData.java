package org.dextur.reputation.storage;

import java.util.*;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;

public record InvestigationSnapshotData(List<Record> records) {
    public InvestigationSnapshotData {
        var unique=new TreeMap<String,Record>();
        for(var r:records)if(unique.put(r.kind()+"/"+r.id(),r)!=null)throw new IllegalArgumentException("Duplicate investigation identity");
        records=List.copyOf(unique.values());
        for(var r:records)if(r.kind()==Kind.BULK){var plan=ProtectionCodec.decode(r.values().get("plan"),Plan.class);int cursor=Integer.parseInt(r.values().get("cursor"));if(!plan.actor().equals(r.actor())||!plan.scope().equals(r.scope())||!plan.subjects().equals(r.related())||cursor<0||cursor>r.related().size()||r.status().equals("COMPLETED")&&cursor!=r.related().size()||!Set.of("RUNNING","COMPLETED","CANCELLED","PAUSED").contains(r.status()))throw new IllegalArgumentException("Invalid bulk metadata");}
        for(var r:records)if(r.kind()==Kind.MODERATION){
            UUID operation=UUID.fromString(r.values().get("operation"));
            if(!unique.containsKey(Kind.AUDIT+"/"+operation))throw new IllegalArgumentException("Moderation audit missing");
        }
    }
    public static InvestigationSnapshotData empty(){return new InvestigationSnapshotData(List.of());}
    public boolean isEmpty(){return records.isEmpty();}
    public Record get(Kind kind,UUID id){return records.stream().filter(r->r.kind()==kind&&r.id().equals(id)).findFirst().orElse(null);}
    public InvestigationSnapshotData put(Record record){var rows=new ArrayList<>(records);rows.removeIf(r->r.kind()==record.kind()&&r.id().equals(record.id()));rows.add(record);return new InvestigationSnapshotData(rows);}
}
