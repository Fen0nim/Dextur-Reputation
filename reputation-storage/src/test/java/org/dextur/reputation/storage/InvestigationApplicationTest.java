package org.dextur.reputation.storage;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class InvestigationApplicationTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    static final class Time extends Clock{Instant at=InvestigationContract.NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return at;}}
    interface TestCase{void run(StorageBackend b,InvestigationApplication app,Time time)throws Exception;}
    void run(TestCase test)throws Exception{StorageFixtures.resources(directory);try(var b=StorageFixtures.local(directory,StorageType.JSON,false)){InvestigationContract.configure(b,directory);InvestigationContract.cast(b,InvestigationContract.VOTER,org.dextur.reputation.api.VoteType.POSITIVE,InvestigationContract.NOW);try(var profiles=new ReputationApplication(b,Settings.load(directory))){var time=new Time();try(var app=new InvestigationApplication(profiles,b,null,AdministrationConfiguration.load(directory),"global",time,error->{})){test.run(b,app,time);}}}}
    Plan plan(InvestigationApplication app)throws Exception{return app.plan(Action.INVALIDATE,new Filter(null,InvestigationContract.TARGET,null,"",null,null,"","",null),"CONSOLE","explicit reason").toCompletableFuture().get();}
    @Test void confirmationIsBoundToActorSingleUseAndRestartLocal()throws Exception{run((b,a,t)->{String token=a.token(plan(a));assertThrows(ExecutionException.class,()->a.confirm(token,UUID.randomUUID().toString()).toCompletableFuture().get());assertTrue(a.confirmationAction(token,"CONSOLE").isPresent());var result=a.confirm(token,"CONSOLE").toCompletableFuture().get();assertEquals("COMPLETED",result.status());assertThrows(ExecutionException.class,()->a.confirm(token,"CONSOLE").toCompletableFuture().get());assertEquals(0,b.snapshot().protection().scores().getFirst().currentUnits());});}
    @Test void invalidAndExpiredTokensHaveNoEffect()throws Exception{run((b,a,t)->{var before=b.snapshot();assertThrows(ExecutionException.class,()->a.confirm("INVALID","CONSOLE").toCompletableFuture().get());String token=a.token(plan(a));t.at=t.at.plusSeconds(a.rules().tokenSeconds()+1);assertThrows(ExecutionException.class,()->a.confirm(token,"CONSOLE").toCompletableFuture().get());assertEquals(before,b.snapshot());});}
    @Test void changedPlanCannotBeConfirmed()throws Exception{run((b,a,t)->{String token=a.token(plan(a));b.administer(PolicyContract.admin(InvestigationContract.TARGET,org.dextur.reputation.domain.policy.AdministrationData.Type.ADD,1,InvestigationContract.NOW));assertThrows(ExecutionException.class,()->a.confirm(token,"CONSOLE").toCompletableFuture().get());assertEquals(350000,b.snapshot().protection().scores().getFirst().currentUnits());});}
    @Test void committedEventsAreIsolatedAndDuplicateFree()throws Exception{run((b,a,t)->{var events=new ArrayList<InvestigationApplication.Committed>();a.onCommitted(events::add);a.onCommitted(event->{throw new AssertionError("listener failure");});var id=b.snapshot().votes().getFirst().operationId();var request=InvestigationContract.request(Action.INVALIDATE,id);a.mutate(request).toCompletableFuture().get();a.mutate(request).toCompletableFuture().get();assertEquals(1,events.size());assertEquals("VoteInvalidated",events.getFirst().kind());});}
    @Test void completedBulkRetryDoesNotEmitCompletionAgain()throws Exception{run((b,a,t)->{var events=new ArrayList<InvestigationApplication.Committed>();a.onCommitted(events::add);var plan=plan(a);UUID operation=UUID.randomUUID();a.execute(operation,plan).toCompletableFuture().get();long before=events.stream().filter(e->e.kind().equals("AdministrationBulkOperationCompleted")).count();a.resume(operation,"CONSOLE").toCompletableFuture().get();assertEquals(1,before);assertEquals(before,events.stream().filter(e->e.kind().equals("AdministrationBulkOperationCompleted")).count());});}
}
