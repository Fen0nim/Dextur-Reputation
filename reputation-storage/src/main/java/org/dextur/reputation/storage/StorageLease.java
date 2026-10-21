package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** Process ownership; lock-file existence alone is never treated as a live owner. */
public final class StorageLease implements AutoCloseable {
    // Closing ANY channel for an inode can release this JVM's Unix fcntl locks.
    // Reject duplicate paths/identities before opening a second channel, not after tryLock.
    private static final java.util.Map<Path,Object> owners=new java.util.HashMap<>();
    private final FileChannel channel;
    private final FileLock lock;
    private final StoragePaths.Identity parent;
    private final Path path;
    private final Object fileKey;
    private volatile boolean closed;
    public StorageLease(Path path) throws IOException {
        synchronized(owners) {
        path=StoragePaths.checked(path);this.path=path;
        AtomicFiles.directories(path.getParent());
        parent=StoragePaths.identity(path.getParent());
        if(Files.isSymbolicLink(path)) throw new IOException("Symlink lock unsupported");
        Object existingKey=null;
        try {existingKey=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();}
        catch(NoSuchFileException absent) { }
        if(owners.containsKey(path)||(existingKey!=null&&owners.containsValue(existingKey)))throw new IOException("Storage is in use; stop its owner first");
        channel=FileChannel.open(path,java.util.Set.of(StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),AtomicFiles.privateAttributes(path.getParent()));
        FileLock acquired;
        try { parent.verify();acquired=channel.tryLock(); }
        catch(IOException|OverlappingFileLockException e) { channel.close(); throw new IOException("Storage is in use; stop its owner first",e); }
        if(acquired==null) { channel.close(); throw new IOException("Storage is in use; stop its owner first"); }
        lock=acquired;
        try {parent.verify();fileKey=Files.readAttributes(StoragePaths.checked(path),java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();}
        catch(IOException|RuntimeException failure){try{lock.release();}finally{channel.close();}throw failure;}
        owners.put(path,fileKey);
        }
    }
    void verify() throws IOException {
        parent.verify();StoragePaths.checked(path);
        if(closed||!lock.isValid())throw new IOException("Storage ownership lock lost");
        var attributes=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile()||(fileKey!=null&&!fileKey.equals(attributes.fileKey())))throw new IOException("Storage ownership file replaced");
    }
    @Override public void close() throws IOException {
        synchronized(owners) {
            if(closed)return;
            try {try{lock.release();}finally{channel.close();}}
            finally {closed=true;owners.remove(path);}
        }
    }
}
