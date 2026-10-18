package org.dextur.reputation.storage;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;

final class StorageFixtures {
    static void resources(Path directory) throws Exception {
        for(String name:List.of("config.yml","storage.yml","integrations.yml","lang/ru_RU.yml","lang/en_US.yml")) {
            Files.createDirectories(directory.resolve(name).getParent());
            try(var in=StorageFixtures.class.getResourceAsStream("/"+name)) { Files.copy(in,directory.resolve(name)); }
        }
    }
    static String config(StorageType type) throws Exception {
        return new String(StorageFixtures.class.getResourceAsStream("/storage.yml").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("type: JSON","type: "+type);
    }
    static StorageSnapshot sample() {
        UUID id=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        Instant time=Instant.parse("2026-10-18T12:34:56.123456Z");
        return new StorageSnapshot(List.of(new ReputationSnapshot(id,"global",12,-19,-7,"NEUTRAL",4,time,time.plusSeconds(10)),
                new ReputationSnapshot(id,"other",0,0,0,"NEUTRAL",0,time,time)),
                List.of(new StorageSnapshot.Preference(id,PlayerLanguage.en_US,time.plusSeconds(15)),
                        new StorageSnapshot.Preference(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),PlayerLanguage.ru_RU,time)));
    }
    static StorageBackend local(Path directory,StorageType type,boolean migration) throws Exception {
        return type==StorageType.JSON?new JsonStorage(directory.resolve("json"),1048576,migration):SqlStorage.sqlite(directory.resolve("test.sqlite"),250,migration);
    }
}
