package org.dextur.reputation.probe;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.recovery.*;
import org.dextur.reputation.domain.recovery.RecoveryData.*;
import org.dextur.reputation.storage.*;
import org.dextur.reputation.application.TierConfiguration;

/** Controlled authoritative timestamps, on an isolated real backend inside Paper. Never changes host time/live player data. */
final class RecoveryCalendarRuntime {
    static final UUID PLAYER=UUID.fromString("55500000-0000-4000-8000-000000000055");
    static Map<String,String> text(String s){return Map.of("ru_RU",s,"en_US",s);}
    static boolean verify(Path directory,Path configuration,Facts verifiedPaperFacts)throws Exception{
        var login=new RecoveryRules.Objective("calendar",RecoveryRules.Type.LOGIN_DAYS,2,"",1,Map.of(),text("Calendar"),text("Distinct eligible dates"));
        var wait=new RecoveryRules.Objective("wait",RecoveryRules.Type.WAIT_PERIOD,2,"",1,Map.of(),text("Wait"),text("Persisted waiting period"));
        var stages=List.of(new RecoveryRules.Stage("calendar",text("Calendar"),"BOOK",List.of(login),0,0),new RecoveryRules.Stage("wait",text("Wait"),"CLOCK",List.of(wait),0,0));
        var rules=new RecoveryRules(true,-100,0,0,true,false,2592000,86400,"Europe/Berlin",120,1,1,1,32,4096,0,0,0,0,RecoveryRules.Action.PAUSE,RecoveryRules.Action.PAUSE,new RecoveryRules.Program("calendar-runtime",1,text("QA calendar"),stages));
        Instant boundary=LocalDate.now(ZoneId.of("Europe/Berlin")).plusDays(2).atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant();UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        try(var b=new JsonStorage(directory,1048576,false)){
            if(!b.snapshot().recovery().isEmpty()){var state=b.snapshot().recovery().get(PLAYER,"global");check(state.current().status()==RecoverySnapshot.Status.COMPLETED&&state.loginDays().size()==2&&state.rewards().size()==2,"Calendar/wait state lost on Paper restart");return true;}
            b.prepareMigration();b.importEmpty(new StorageSnapshot(List.of(new ReputationSnapshot(PLAYER,"global",-101,0,-101,"NEUTRAL",0,Instant.now().minusSeconds(259200),Instant.now().minusSeconds(259199))),List.of()));b.finishMigration();b.tiers(TierConfiguration.load(configuration));b.recovery(rules);
            b.recover(request(Command.START,boundary.minusSeconds(4),first,0,verifiedPaperFacts));b.recover(request(Command.SAMPLE,boundary.minusSeconds(4),first,0,verifiedPaperFacts));
            var one=b.recover(request(Command.SAMPLE,boundary.minusSeconds(2),first,2,verifiedPaperFacts));check(one.state().loginDays().size()==1&&one.snapshot().status()==RecoverySnapshot.Status.ACTIVE,"First eligible date");
            b.recover(request(Command.SAMPLE,boundary.minusSeconds(1),first,3,verifiedPaperFacts));check(b.snapshot().recovery().get(PLAYER,"global").loginDays().size()==1,"Same date counted twice");
        }
        try(var b=new JsonStorage(directory,1048576,false)){
            b.tiers(TierConfiguration.load(configuration));b.recovery(rules);b.recover(request(Command.SAMPLE,boundary,second,0,verifiedPaperFacts));var two=b.recover(request(Command.SAMPLE,boundary.plusSeconds(2),second,2,verifiedPaperFacts));check(two.state().loginDays().size()==2&&two.snapshot().stageId().equals("wait"),"Distinct date after restart not counted");check(two.snapshot().status()==RecoverySnapshot.Status.ACTIVE,"WAIT auto-completed before duration");
        }
        try(var b=new JsonStorage(directory,1048576,false)){
            b.tiers(TierConfiguration.load(configuration));b.recovery(rules);var done=b.recover(request(Command.READ,boundary.plusSeconds(4),second,2,verifiedPaperFacts));check(done.snapshot().status()==RecoverySnapshot.Status.COMPLETED&&done.state().rewards().size()==2,"Waiting period did not resume from persisted timestamp");check(done.profile().systemScore()==0,"Calendar QA created live reputation rewards");
        }return true;
    }
    static Request request(Command action,Instant at,UUID session,long count,Facts facts){return new Request(UUID.randomUUID(),PLAYER,"global",action,at,facts,session,count,count,true,new UUID(0,0),"",0,"","SERVER_QA_CLOCK","controlled calendar timestamps",Set.of());}
    static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
}
