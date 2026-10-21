package org.dextur.reputation.storage;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ReleaseLargeLocalTest {
    @TempDir Path directory;
    @Test void sqliteTenThousandProfilesHundredThousandVotesAndBenchmarks()throws Exception {try(var b=SqlStorage.sqlite(directory.resolve("large.sqlite"),5000,false)){ReleaseBenchmarks.run(b,directory,"SQLITE",true);}}
    @Test void jsonDefault64MiBLimitRefusesLargeDatasetWithoutPartialWrite()throws Exception {
        try(var b=new JsonStorage(directory.resolve("json"),67108864,false)){
            var before=b.snapshot();byte[] bytes=Files.readAllBytes(directory.resolve("json/state.json"));b.prepareMigration();
            var dataset=ReleaseDataset.create(10000,100000,850);assertThrows(JsonStorage.LimitException.class,()->b.importEmpty(dataset));assertEquals(before,b.snapshot());assertArrayEquals(bytes,Files.readAllBytes(directory.resolve("json/state.json")));
            ReleaseStorageChecks.writeReport("JSON-large-refusal",Map.of("profiles",10000,"votes",100000,"limit_bytes",67108864,"old_snapshot_intact",true));
        }
    }
    @Test void jsonControlledSmallerDatasetBenchmarks()throws Exception {try(var b=new JsonStorage(directory.resolve("benchmark"),67108864,false)){ReleaseBenchmarks.run(b,directory,"JSON",false);}}
}
