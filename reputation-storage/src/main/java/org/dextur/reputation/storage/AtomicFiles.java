package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import static java.nio.file.StandardOpenOption.*;

/** No non-atomic fallback. Callers fail closed on uncertain commit outcomes. */
public final class AtomicFiles {
    private AtomicFiles() { }
    public static void replace(Path target, byte[] bytes) throws IOException {
        if (Files.isSymbolicLink(target)) throw new IOException("Symlink target unsupported");
        Path temporary=target.resolveSibling(target.getFileName()+".tmp");
        write(temporary,bytes);
        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(target.getParent());
    }
    public static void write(Path path, byte[] bytes) throws IOException {
        try(FileChannel file=FileChannel.open(path,java.util.Set.of(CREATE,TRUNCATE_EXISTING,WRITE,LinkOption.NOFOLLOW_LINKS),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer data=ByteBuffer.wrap(bytes); while(data.hasRemaining()) file.write(data); file.force(true);
        }
    }
    public static void syncDirectory(Path directory) throws IOException {
        try(FileChannel channel=FileChannel.open(directory,READ)) { channel.force(true); }
    }
    public static void directories(Path directory) throws IOException {
        if (Files.exists(directory)) return;
        Path parent=directory.getParent();
        if(parent!=null) directories(parent);
        Files.createDirectory(directory);
        if(parent!=null) syncDirectory(parent);
        syncDirectory(directory);
    }
    static Path resolve(Path root,String relative) throws IOException {
        Path base=root.toAbsolutePath().normalize(), result=base.resolve(relative).normalize();
        if(!result.startsWith(base)||result.equals(base)) throw new IOException("Storage path escapes data directory");
        Path walk=base;
        if(Files.isSymbolicLink(walk)) throw new IOException("Symlink data directory is unsupported");
        for(Path part:base.relativize(result)) { walk=walk.resolve(part); if(Files.isSymbolicLink(walk)) throw new IOException("Symlink storage paths are unsupported"); }
        return result;
    }
}
