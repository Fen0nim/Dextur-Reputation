package org.dextur.reputation.domain.recovery;

import java.time.ZoneId;
import java.util.*;

/** Frozen on enrollment, including presentation and reward caps. No Paper classes. */
public record RecoveryRules(boolean enabled,long threshold,long minimumPlaySeconds,long minimumAgeSeconds,
        boolean manual,boolean automatic,long cooldownSeconds,long expirationSeconds,String timezone,
        long inactivitySeconds,int minimumActivity,long minimumDayActiveSeconds,int persistenceSeconds,int batchSize,int maximumTracked,
        long maximumProgramReward,long maximumLifetimeReward,long maximumTrustCredit,long lifetimeTrustCredit,
        Action confirmedAction,Action lockAction,Program program) {
    public enum Type { PLAYTIME,ACTIVE_PLAYTIME,LOGIN_DAYS,WAIT_PERIOD,NO_CONFIRMED_ABUSE,EXTERNAL }
    public enum Action { NONE,PAUSE,RESET_STAGE,CANCEL }
    public record Objective(String id,Type type,long required,String provider,int providerVersion,Map<String,String> configuration,Map<String,String> names,Map<String,String> descriptions) {
        public Objective {identifier(id);Objects.requireNonNull(type);if(required<1||required>315360000L||providerVersion<1)fail();Objects.requireNonNull(provider);if(type==Type.EXTERNAL)identifier(provider);else if(!provider.isEmpty())fail();configuration=ordered(configuration);names=localized(names);descriptions=localized(descriptions);if(configuration.size()>32)fail();}
    }
    public record Stage(String id,Map<String,String> names,String icon,List<Objective> objectives,long reputation,long trustCredit) {
        public Stage {identifier(id);names=localized(names);identifierMaterial(icon);objectives=List.copyOf(objectives);if(objectives.isEmpty()||objectives.size()>32||reputation<0||reputation>1000000||trustCredit<0||trustCredit>100000)fail();unique(objectives.stream().map(Objective::id).toList());}
    }
    public record Program(String id,int version,Map<String,String> names,List<Stage> stages) {
        public Program {identifier(id);if(version<1)fail();names=localized(names);stages=List.copyOf(stages);if(stages.isEmpty()||stages.size()>32)fail();unique(stages.stream().map(Stage::id).toList());unique(stages.stream().flatMap(s->s.objectives().stream()).map(Objective::id).toList());}
    }
    public RecoveryRules {
        ZoneId.of(timezone);Objects.requireNonNull(program);Objects.requireNonNull(confirmedAction);Objects.requireNonNull(lockAction);
        if(threshold>0||minimumPlaySeconds<0||minimumAgeSeconds<0||cooldownSeconds<0||expirationSeconds<1||expirationSeconds>315360000L||inactivitySeconds<1||inactivitySeconds>3600||minimumActivity<1||minimumActivity>100||minimumDayActiveSeconds<1||persistenceSeconds<1||persistenceSeconds>300||batchSize<1||batchSize>100||maximumTracked<batchSize||maximumTracked>100000||maximumProgramReward<0||maximumProgramReward>1000000||maximumLifetimeReward<maximumProgramReward||maximumLifetimeReward>1000000000||maximumTrustCredit<0||maximumTrustCredit>100000||lifetimeTrustCredit<maximumTrustCredit||lifetimeTrustCredit>100000)fail();
    }
    static Map<String,String> ordered(Map<String,String> m){Objects.requireNonNull(m);m.forEach((k,v)->{if(k==null||v==null||k.length()>100||v.length()>2000)fail();});return Collections.unmodifiableMap(new TreeMap<>(m));}
    static Map<String,String> localized(Map<String,String> m){if(!m.keySet().containsAll(Set.of("ru_RU","en_US")))fail();return ordered(m);}
    public static void identifier(String id){if(id==null||!id.matches("[a-z][a-z0-9_-]{0,63}"))fail();}
    private static void identifierMaterial(String id){if(id==null||!id.matches("[A-Z_]{1,64}"))fail();}
    static void unique(List<String> ids){if(new HashSet<>(ids).size()!=ids.size())fail();}
    static void fail(){throw new IllegalArgumentException("Invalid recovery definition");}
}
