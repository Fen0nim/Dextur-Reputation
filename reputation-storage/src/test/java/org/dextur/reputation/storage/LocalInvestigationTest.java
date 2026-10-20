package org.dextur.reputation.storage;
import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.application.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;
class LocalInvestigationTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    static java.util.stream.Stream<Arguments> scenarios(){return Arrays.stream(new StorageType[]{StorageType.JSON,StorageType.SQLITE}).flatMap(t->Arrays.stream(InvestigationContract.Scenario.values()).map(s->Arguments.of(t,s)));}
    @ParameterizedTest @MethodSource("scenarios")void equivalentContracts(StorageType type,InvestigationContract.Scenario scenario)throws Exception{try(var b=StorageFixtures.local(directory,type,false)){InvestigationContract.run(scenario,b,directory);}}
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})void restartAndFullMigration(StorageType type)throws Exception{StorageFixtures.resources(directory);Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(type));Files.writeString(directory.resolve("target.yml"),StorageFixtures.config(type==StorageType.JSON?StorageType.SQLITE:StorageType.JSON));StorageSnapshot before;try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)){InvestigationContract.run(InvestigationContract.Scenario.RESTORE,b,directory);before=b.snapshot();}try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)){assertEquals(before,b.snapshot());}new StorageMigration().migrate(directory,directory.resolve("target.yml"),directory.resolve("backups"),Map.of());try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)){assertEquals(before,b.snapshot());}}
}
