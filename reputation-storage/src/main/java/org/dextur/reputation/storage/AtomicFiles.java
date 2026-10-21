package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** No non-atomic fallback. Callers fail closed on uncertain commit outcomes. */
public final class AtomicFiles {
    private AtomicFiles() { }
    public static void replace(Path target, byte[] bytes) throws IOException {
        target=StoragePaths.checked(target);
        var parent=StoragePaths.identity(target.getParent());
        if (Files.isSymbolicLink(target)) throw new IOException("Symlink target unsupported");
        Path temporary=target.resolveSibling(target.getFileName()+".tmp");
        write(temporary,bytes);
        parent.verify();
        move(temporary,target);
        syncDirectory(target.getParent());
    }
    public static void write(Path path, byte[] bytes) throws IOException {
        path=StoragePaths.checked(path);
        var parent=StoragePaths.identity(path.getParent());
        try(FileChannel file=FileChannel.open(path,java.util.Set.of(CREATE,WRITE,LinkOption.NOFOLLOW_LINKS),
                privateAttributes(path.getParent()))) {
            parent.verify();
            file.truncate(0);
            ByteBuffer data=ByteBuffer.wrap(bytes); while(data.hasRemaining()) file.write(data); file.force(true);
        }
        parent.verify();
    }
    public static void syncDirectory(Path directory) throws IOException {
        syncDirectory(directory,System.getProperty("os.name"),path->{try(FileChannel channel=FileChannel.open(path,READ)){channel.force(true);}});
    }
    @FunctionalInterface interface DirectorySync {void force(Path directory) throws IOException;}
    static void syncDirectory(Path directory,String os,DirectorySync sync) throws IOException {
        directory=StoragePaths.checked(directory);
        StoragePaths.identity(directory).verify();
        // Windows NIO cannot open directories as FileChannels. macOS JDK 25 force()
        // uses F_FULLFSYNC, which is not a directory-fsync API. Never suppress file-force errors.
        if(!supportsDirectorySync(os))return;
        sync.force(directory);
    }
    static boolean supportsDirectorySync(String os) { return !os.startsWith("Windows")&&!os.startsWith("Mac"); }
    static FileAttribute<?>[] privateAttributes(Path parent) throws IOException {
        return privateAttributes(parent,Files.getFileStore(parent));
    }
    static FileAttribute<?>[] privateAttributes(Path parent,FileStore store) throws IOException {
        if(store.supportsFileAttributeView(PosixFileAttributeView.class))
            return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))};
        if(store.supportsFileAttributeView(AclFileAttributeView.class)) {
            var owner=Files.getOwner(parent);
            var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            return new FileAttribute<?>[]{new FileAttribute<List<AclEntry>>() {
                public String name(){return "acl:acl";}
                public List<AclEntry> value(){return List.of(entry);}
            }};
        }
        throw new IOException("Storage filesystem requires POSIX permissions or ACL support");
    }
    static void move(Path source,Path target) throws IOException {
        source=StoragePaths.checked(source);target=StoragePaths.checked(target);
        if(!source.getParent().equals(target.getParent()))throw new IOException("Atomic replacement requires the same directory");
        var parent=StoragePaths.identity(source.getParent());parent.verify();
        Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        parent.verify();
    }
    public static void directories(Path directory) throws IOException {
        directory=StoragePaths.checked(directory);
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Directory required");
            return;
        }
        Path parent=directory.getParent();
        if(parent!=null) directories(parent);
        Files.createDirectory(directory);
        if(parent!=null) syncDirectory(parent);
        syncDirectory(directory);
    }
    static void safeAncestors(Path path) throws IOException {
        StoragePaths.checked(path);
    }
    static Path resolve(Path root,String relative) throws IOException {
        return StoragePaths.resolve(root,relative);
    }
}
