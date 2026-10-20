package org.dextur.reputation.domain.investigation;

import java.time.Instant;
import java.util.UUID;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;
import org.dextur.reputation.domain.investigation.InvestigationData.*;

/** Blocking private port, exclusively called on bounded IO workers/offline tools. */
public interface InvestigationRepository {
    void investigation(Rules rules) throws Exception;
    Report investigate(Query query) throws Exception;
    Mutation moderate(Request request) throws Exception;
    Plan plan(Action action,String scope,Filter filter,String actor,String reason,Instant now) throws Exception;
    Record begin(UUID operation,Plan plan,Instant now) throws Exception;
    Batch advance(UUID operation,String actor,boolean cancel,Instant now) throws Exception;
}
