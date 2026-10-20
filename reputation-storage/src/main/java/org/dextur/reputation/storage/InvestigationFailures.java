package org.dextur.reputation.storage;

import java.time.Instant;
import java.util.*;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;

final class InvestigationFailures {
    private InvestigationFailures() { }
    static void pause(InvestigationSession s,UUID operation,String actor,Exception error,Instant now)throws Exception {
        var old=s.record(Kind.BULK,operation);if(old==null||!old.actor().equals(actor)||Set.of("COMPLETED","CANCELLED").contains(old.status()))return;
        String code=error.getMessage()!=null&&error.getMessage().matches("INV_[A-Z_]{1,64}")?error.getMessage():"INV_STORAGE";
        var paused=InvestigationBulk.update(old,"PAUSED",Integer.parseInt(old.values().get("cursor")),now,code);s.put(paused);
        if(s.recordCount()<s.rules.maximumRecords())s.put(new Record(UUID.randomUUID(),Kind.DIAGNOSTIC,old.scope(),actor,old.target(),old.vote(),"FAILED",1,now,Map.of("action","BULK_BATCH_FAILED","operation",operation.toString(),"error",code,"cursor",old.values().get("cursor"),"schema","1"),List.of()));
    }
}
