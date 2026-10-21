package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** Process ownership; lock-file existence alone is never treated as a live owner. */
public final class StorageLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    public StorageLease(Path path) throws IOException {
        AtomicFiles.directories(path.getParent());
        if(Files.isSymbolicLink(path)) throw new IOException("Symlink lock unsupported");
        channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try { acquired=channel.tryLock(); }
        catch(IOException|OverlappingFileLockException e) { channel.close(); throw new IOException("Storage is in use; stop its owner first",e); }
        if(acquired==null) { channel.close(); throw new IOException("Storage is in use; stop its owner first"); }
        lock=acquired;
    }
    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
