package org.dextur.reputation.domain.policy;

import org.dextur.reputation.domain.VotingRules;

/** UUID/scope-bound administrative capability stored in the existing audited override ledger. */
public final class InitialEligibility {
    public static final String KEY="reputation.initial-eligibility";
    private InitialEligibility() { }
    public static boolean enabled(AdministrationData.State state) {
        return state!=null&&state.overrides().get(KEY)==AdministrationData.Override.FORCE_ALLOW;
    }
    public static VotingRules rules(VotingRules rules,AdministrationData.State state) {
        if(!enabled(state))return rules;
        return new VotingRules(0,0,rules.cooldownSeconds(),rules.pairCooldownSeconds(),rules.baseDailyLimit(),
                rules.dailyLimits(),rules.zone(),rules.resetHour(),rules.repeatPolicy(),rules.allowRevoke(),
                rules.maximumCommentLength(),rules.positiveWeight(),rules.negativeWeight(),rules.reasons());
    }
}
