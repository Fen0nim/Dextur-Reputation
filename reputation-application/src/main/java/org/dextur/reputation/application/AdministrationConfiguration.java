package org.dextur.reputation.application;

import java.nio.file.Path;
import org.dextur.reputation.domain.investigation.InvestigationData.Rules;

public final class AdministrationConfiguration {
    private AdministrationConfiguration() { }
    public static Rules load(Path directory)throws Exception {
        var c=TierConfiguration.configuration(directory,"administration.yml");
        if(TierConfiguration.integer(c,"config-version")!=1)throw new IllegalArgumentException("Unsupported administration configuration");
        return new Rules(i(c,"pagination.size"),i(c,"bulk.maximum-records"),i(c,"bulk.batch-size"),i(c,"bulk.confirmation-threshold"),i(c,"confirmation.expiration-seconds"),i(c,"confirmation.maximum-tokens"),i(c,"relations.maximum-events"),i(c,"relations.maximum-neighbors"),i(c,"relations.graph-budget"),i(c,"recalculation.maximum-events"),i(c,"audit.diagnostic-retention-days"),i(c,"audit.maximum-records"),i(c,"audit.json-warning-percent"),YamlFiles.flag(c,"require-reason"),YamlFiles.flag(c,"console-allowed"));
    }
    private static java.util.Map<String,Object> parent(java.util.Map<String,Object> c,String key){String[] parts=key.split("\\.");for(int i=0;i<parts.length-1;i++)c=TierConfiguration.map(c.get(parts[i]));return c;}
    private static int i(java.util.Map<String,Object> c,String key){return Math.toIntExact(TierConfiguration.integer(parent(c,key),key.substring(key.lastIndexOf('.')+1)));}
}
