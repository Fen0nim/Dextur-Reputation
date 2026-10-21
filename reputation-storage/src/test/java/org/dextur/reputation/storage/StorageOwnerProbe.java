package org.dextur.reputation.storage;

import java.nio.file.Path;
import java.util.UUID;
import org.dextur.reputation.application.StorageType;
import org.dextur.reputation.domain.PlayerLanguage;

/** Separate JVM so ownership tests exercise OS locks, not only OverlappingFileLockException. */
public final class StorageOwnerProbe {
    public static void main(String[] args) throws Exception {
        if(args[0].equals("--lease")) {
            try(var owner=new StorageLease(Path.of(args[1]))) {System.out.println("OWNER_ACQUIRED");}
            catch(java.io.IOException expected){System.out.println("OWNER_REFUSED");System.exit(42);}
            return;
        }
        if(args.length>2&&args[2].equals("try")) {
            try(var backend=StorageFixtures.local(Path.of(args[0]),StorageType.valueOf(args[1]),false)){System.out.println("OWNER_ACQUIRED");}
            catch(Exception expected){System.out.println("OWNER_REFUSED");System.exit(42);}
            return;
        }
        try(var backend=StorageFixtures.local(Path.of(args[0]),StorageType.valueOf(args[1]),false)) {
            var id=new UUID(47,91);backend.getOrCreate(id,"global");backend.setLanguage(id,PlayerLanguage.en_US);
            System.out.println("OWNER_READY");System.out.flush();System.in.read();
        }
    }
}
