package org.dextur.reputation.domain.policy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.api.ReputationPolicyResult.*;
import org.dextur.reputation.domain.policy.AdministrationData.Override;

public final class PolicyEngine {
    private PolicyEngine(){}
    public record Input(ReputationSnapshot profile,AdministrationData.State administration,RecoverySnapshot.Status recovery,long recoveryRevision){public Input{Objects.requireNonNull(profile);Objects.requireNonNull(recovery);}}
    public static ReputationPolicyResult evaluate(Input input,PolicyRules rules,String id,Map<String,String> context,Instant now,boolean active){PolicyRules.identifier(id);if(context.size()>16)throw new IllegalArgumentException("Public context limit");new PolicyRules.Condition(null,null,Set.of(),Set.of(),context);var p=input.profile();var admin=input.administration();var revision=new Revision(p.revision(),admin==null?0:admin.revision(),input.recoveryRevision(),rules.fingerprint());
        if(id.equals("support.emergency-contact"))return result(p,id,Decision.ALLOW,"emergency baseline",null,1000000,null,"policy-reason-emergency",revision,now,active);
        if(id.equals("reputation.vote")&&admin!=null&&admin.votingLocked())return result(p,id,Decision.DENY,"administrative voting lock",0L,1000000,null,"policy-reason-voting-lock",revision,now,active);
        var definition=rules.policies().get(id);if(definition==null||!definition.enabled())return result(p,id,Decision.UNAVAILABLE,"",null,1000000,null,"policy-reason-unavailable",revision,now,active);
        var override=admin==null?Override.DEFAULT:admin.overrides().getOrDefault(id,Override.DEFAULT);
        for(var rule:definition.rules()){var c=rule.condition();if(c.minimum()!=null&&p.visibleScore()<c.minimum()||c.maximum()!=null&&p.visibleScore()>c.maximum()||!c.tiers().isEmpty()&&!c.tiers().contains(p.tier())||!c.recovery().isEmpty()&&!c.recovery().contains(input.recovery())||!context.entrySet().containsAll(c.context().entrySet()))continue;Decision decision=override==Override.DEFAULT?rule.decision():override==Override.FORCE_ALLOW?Decision.ALLOW:Decision.DENY;return result(p,id,decision,c.toString(),rule.limit(),rule.multiplierUnits(),rule.valueUnits(),override!=Override.DEFAULT?"policy-reason-override":decision==Decision.DENY?"policy-reason-reputation":"policy-reason-configured",revision,now,active);}throw new IllegalStateException("Missing default rule");
    }
    private static ReputationPolicyResult result(ReputationSnapshot p,String id,Decision decision,String requirement,Long limit,long multiplier,Long value,String reason,Revision revision,Instant now,boolean active){return new ReputationPolicyResult(p.playerId(),id,decision,p.visibleScore(),p.tier(),requirement,limit,BigDecimal.valueOf(multiplier,6),value==null?null:BigDecimal.valueOf(value,6),reason,revision,now,active);}
}
