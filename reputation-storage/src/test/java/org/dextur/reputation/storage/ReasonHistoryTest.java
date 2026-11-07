package org.dextur.reputation.storage;

import java.nio.file.Path;
import org.dextur.reputation.application.StorageType;
import org.dextur.reputation.domain.ReasonHistory;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ReasonHistoryTest {
    @TempDir Path directory;
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void runtimeManagerDelegatesReasonProjection(StorageType type)throws Exception {
        StorageFixtures.resources(directory);
        java.nio.file.Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(type));
        var settings=org.dextur.reputation.application.Settings.load(directory);
        try(var manager=new StorageManager()){
            manager.open(directory,settings,java.util.Map.of());ReasonHistoryContract.check(manager.backend());
            assertEquals(5,manager.reasonHistory(ReasonHistoryContract.TARGET,"global",ReasonHistoryContract.query(null,null,false,1,36)).total());
        }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void canonicalReasonsActiveJournalPaginationAndRestart(StorageType type)throws Exception {
        ReasonHistory.Page before;
        try(var b=StorageFixtures.local(directory,type,false)){ReasonHistoryContract.check(b);before=b.reasonHistory(ReasonHistoryContract.TARGET,"global",ReasonHistoryContract.query(null,null,false,1,36));}
        try(var b=StorageFixtures.local(directory,type,false)){assertEquals(before,b.reasonHistory(ReasonHistoryContract.TARGET,"global",before.query()));}
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void moderationAndRestoreUseAuthoritativeActiveIndexWithoutChangingTrust(StorageType type)throws Exception {
        try(var b=StorageFixtures.local(directory,type,false)){
            InvestigationContract.run(InvestigationContract.Scenario.LEGACY,b,directory);
            var q=ReasonHistoryContract.query(null,null,true,1,36);var before=b.reasonHistory(InvestigationContract.TARGET,"global",q);assertEquals(1,before.total());
            var id=before.entries().getFirst().entry().operationId();
            b.moderate(InvestigationContract.request(Action.INVALIDATE,id));var snapshot=b.snapshot();assertEquals(0,b.reasonHistory(InvestigationContract.TARGET,"global",q).total());assertEquals(snapshot,b.snapshot());
            b.moderate(InvestigationContract.request(Action.RESTORE,id));assertEquals(1,b.reasonHistory(InvestigationContract.TARGET,"global",q).total());
        }
    }
    @Test void missingLegacyReasonHasAStableEmptyCategoryAndInvalidFiltersAreRejected(){
        assertEquals("",ReasonHistory.canonical(null));assertEquals("",ReasonHistory.canonical(""));assertEquals("friendly",ReasonHistory.canonical("friendly"));
        assertDoesNotThrow(()->ReasonHistoryContract.query(null,"",false,1,9));
        assertThrows(org.dextur.reputation.api.VoteRejectedException.class,()->ReasonHistoryContract.query(null,"Дружелюбный",false,1,9));
        assertThrows(org.dextur.reputation.api.VoteRejectedException.class,()->ReasonHistoryContract.query(null,"x' OR 1=1",false,1,9));
    }
}
