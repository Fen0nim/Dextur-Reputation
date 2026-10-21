package org.dextur.reputation.storage;

import java.nio.file.Path;

/** Child JVM intentionally terminates without finally blocks after durable target import. */
public final class ReleaseMigrationCrash {
    public static void main(String[] args)throws Exception {
        new StorageMigration().migrate(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]),System.getenv(),()->Runtime.getRuntime().halt(73));
        throw new AssertionError("Crash checkpoint was not reached");
    }
}
