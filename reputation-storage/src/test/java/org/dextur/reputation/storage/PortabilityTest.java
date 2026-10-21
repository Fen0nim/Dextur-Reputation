package org.dextur.reputation.storage;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.dextur.reputation.application.StorageType;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class PortabilityTest {
    @TempDir Path directory;
    @AfterEach void removeLinksWithoutTraversingTargets() throws Exception {PortableTestFiles.cleanupLinks();}

    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void explicitOsAliasBoundarySupportsRestartAndNonExistingLeaves(StorageType type) throws Exception {
        Path physical=directory.resolve("private/var/folders/Тест с пробелами/T");Files.createDirectories(physical);
        Path alias=directory.resolve("var");PortableTestFiles.directoryLink(alias,directory.resolve("private/var"));
        Path logical=alias.resolve("folders/Тест с пробелами/T/new/plugin");
        var root=StoragePaths.trustedRoot(alias.resolve("folders/Тест с пробелами/T"));
        Path canonical=root.resolve("new/plugin");
        assertEquals(physical.toRealPath().resolve("new/plugin"),canonical);
        assertEquals(physical.toRealPath().resolve("external-backups/new"),StoragePaths.selectedDirectory(alias.resolve("folders/Тест с пробелами/T/external-backups/new")));
        Files.createDirectories(canonical);
        StorageSnapshot expected;
        try(var b=StorageFixtures.local(canonical,type,false)){b.getOrCreate(new UUID(7,11),"global");b.setLanguage(new UUID(7,11),PlayerLanguage.en_US);expected=b.snapshot();}
        try(var b=StorageFixtures.local(canonical,type,false)){assertEquals(expected,b.snapshot());}
        // The same arbitrary alias is still untrusted in the production default policy.
        assertThrows(IOException.class,()->StoragePaths.checked(logical));
    }

    @Test void trustedOsPrefixDoesNotAuthorizeInnerRedirection() throws Exception {
        Path real=directory.resolve("private/var/managed-root");Files.createDirectories(real);
        Path alias=directory.resolve("var");PortableTestFiles.directoryLink(alias,real.getParent());
        Path foreign=directory.resolve("foreign");Files.createDirectories(foreign);
        PortableTestFiles.directoryLink(real.resolve("managed"),foreign);
        assertThrows(IOException.class,()->StoragePaths.trustedRoot(alias.resolve("managed-root")).resolve("managed/file"));
        assertFalse(Files.exists(foreign.resolve("file")));
    }

    @Test void trustedAliasTargetCannotBeChanged() throws Exception {
        Path real=directory.resolve("private/var"),foreign=directory.resolve("foreign");Files.createDirectories(real);Files.createDirectories(foreign);
        Path alias=directory.resolve("var");Files.createDirectories(real.resolve("root"));Files.createDirectories(foreign.resolve("root"));
        PortableTestFiles.directoryLink(alias,real);
        var root=StoragePaths.trustedRoot(alias.resolve("root"));
        Files.delete(alias);PortableTestFiles.directoryLink(alias,foreign);
        assertThrows(IOException.class,()->root.resolve("new/file"));
    }

    @ParameterizedTest @ValueSource(strings={"json","sqlite","backup","lock"})
    void linksRemainingWithinExplicitRootAreAccepted(String kind) throws Exception {
        Path target=directory.resolve("real");Files.createDirectories(target);
        PortableTestFiles.directoryLink(directory.resolve("inside"),target);
        Path resolved=StoragePaths.trustedRoot(directory).resolve("inside/new/leaf");
        assertEquals(target.toRealPath().resolve("new/leaf"),resolved);
        AtomicFiles.directories(resolved.getParent());
        switch(kind) {
            case "json" -> {try(var b=new JsonStorage(resolved,1048576,false)){b.getOrCreate(new UUID(2,3),"global");}try(var b=new JsonStorage(resolved,1048576,false)){assertEquals(1,b.snapshot().profiles().size());}}
            case "sqlite" -> {try(var b=SqlStorage.sqlite(resolved,250,false)){b.getOrCreate(new UUID(2,3),"global");}try(var b=SqlStorage.sqlite(resolved,250,false)){assertEquals(1,b.snapshot().profiles().size());}}
            case "backup" -> {AtomicFiles.replace(resolved,new byte[]{3});assertArrayEquals(new byte[]{3},Files.readAllBytes(resolved));}
            default -> {try(var lease=new StorageLease(resolved)){lease.verify();assertThrows(IOException.class,()->new StorageLease(StoragePaths.trustedRoot(directory).resolve("inside/new/leaf")));}}
        }
    }

    @ParameterizedTest @ValueSource(strings={"json","sqlite","backup","lock"})
    void linksEscapingExplicitRootAreRejectedWithoutWrites(String kind) throws Exception {
        Path root=directory.resolve("trusted"),foreign=directory.resolve("foreign");Files.createDirectories(root);Files.createDirectories(foreign);
        Files.writeString(foreign.resolve("sentinel"),"unchanged");PortableTestFiles.directoryLink(root.resolve("escape"),foreign);
        var boundary=StoragePaths.trustedRoot(root);
        assertThrows(IOException.class,()->{
            Path escaped=boundary.resolve("escape/"+kind);
            switch(kind){case "json" -> {try(var ignored=new JsonStorage(escaped,1048576,false)) {fail("escaped");}}
                case "sqlite" -> {try(var ignored=SqlStorage.sqlite(escaped,250,false)){fail("escaped");}}
                case "backup" -> AtomicFiles.replace(escaped,new byte[]{1});
                default -> {try(var ignored=new StorageLease(escaped)){fail("escaped");}}}
        });
        try(var entries=Files.list(foreign)){assertEquals(List.of("sentinel"),entries.map(p->p.getFileName().toString()).toList());}
        assertEquals("unchanged",Files.readString(foreign.resolve("sentinel")));
    }

    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void configuredStorageAndMigrationUseCanonicalRoot(StorageType type) throws Exception {
        StorageFixtures.resources(directory);Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(type));
        Path data=directory.resolve("real-data"),backups=directory.resolve("real-backups");Files.createDirectories(data);Files.createDirectories(backups);
        PortableTestFiles.directoryLink(directory.resolve("storage"),data);PortableTestFiles.directoryLink(directory.resolve("backup-alias"),backups);
        StorageSnapshot expected;
        try(var manager=new StorageManager()) {
            manager.open(directory,org.dextur.reputation.application.Settings.load(directory),Map.of());
            manager.getOrCreate(new UUID(17,6),"global");manager.setLanguage(new UUID(17,6),PlayerLanguage.en_US);expected=manager.backend().snapshot();
        }
        try(var manager=new StorageManager()){manager.open(directory,org.dextur.reputation.application.Settings.load(directory),Map.of());assertEquals(expected,manager.backend().snapshot());}
        Path target=directory.resolve("target.yml");Files.writeString(target,StorageFixtures.config(type==StorageType.JSON?StorageType.SQLITE:StorageType.JSON));
        var result=new StorageMigration().migrate(directory,target,directory.resolve("backup-alias"),Map.of());
        assertTrue(result.backupDirectory().startsWith(backups.toRealPath()));assertTrue(Files.isRegularFile(result.backupDirectory().resolve("VERIFIED.json")));
        try(var manager=new StorageManager()){manager.open(directory,org.dextur.reputation.application.Settings.load(directory),Map.of());assertEquals(expected,manager.backend().snapshot());}
    }

    @ParameterizedTest @ValueSource(strings={"../escape","nested/../../escape",".","nested/.."})
    void traversalNeverEscapesExplicitRoot(String relative) throws Exception {
        assertThrows(IOException.class,()->AtomicFiles.resolve(directory,relative));
    }

    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void nativePathsWithSpacesUnicodeAndPlatformCaseSemantics(StorageType type) throws Exception {
        Path root=directory.resolve("Репутация Space Å");Files.createDirectories(root);
        Path checked=StoragePaths.checked(root.resolve("nonexistent"));assertEquals(root.toRealPath().resolve("nonexistent"),checked);
        try(var b=StorageFixtures.local(root,type,false)){assertNotNull(b.getOrCreate(new UUID(4,9),"global"));}
        Path mixed=root.resolve("CaseProbe");Files.writeString(mixed,"case");Path lower=root.resolve("caseprobe");
        if(Files.exists(lower)){assertTrue(Files.isSameFile(mixed,lower));assertEquals(mixed.toRealPath(),StoragePaths.checked(lower));}
        else assertFalse(Files.exists(lower));
        if(System.getProperty("os.name").startsWith("Windows")){assertTrue(root.isAbsolute());assertTrue(root.toString().contains("\\"));}
    }

    @Test void nativeMacOsPrefixesResolveToTheirCanonicalEquivalent() throws Exception {
        assertEquals(directory.toRealPath(),StoragePaths.checked(directory));
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(),StoragePaths.checked(Path.of(System.getProperty("java.io.tmpdir"))));
    }

    @ParameterizedTest @ValueSource(strings={"Mac OS X","Windows 11"})
    void unsupportedDirectoryForceIsNotInvoked(String os) throws Exception {
        AtomicFiles.syncDirectory(directory,os,path->{throw new IOException("Bad file descriptor: simulated unsupported directory force");});
    }

    @Test void supportedDirectoryForceErrorsRemainFatal() throws Exception {
        assertThrows(IOException.class,()->AtomicFiles.syncDirectory(directory,"Linux",path->{throw new IOException("simulated I/O failure");}));
    }

    @Test void posixCreationRemainsOwnerOnly() throws Exception {
        var store=new AttributeStore("posix");
        var attrs=AtomicFiles.privateAttributes(directory,store);assertEquals("posix:permissions",attrs[0].name());
        assertEquals(PosixFilePermissions.fromString("rw-------"),attrs[0].value());
    }

    @Test void nonPosixAclCreationIsRestrictedToOwner() throws Exception {
        var store=new AttributeStore("acl");
        var attrs=AtomicFiles.privateAttributes(directory,store);assertEquals("acl:acl",attrs[0].name());
        var entries=(List<?>)attrs[0].value();assertEquals(1,entries.size());AclEntry entry=(AclEntry)entries.getFirst();
        assertEquals(AclEntryType.ALLOW,entry.type());assertEquals(Files.getOwner(directory),entry.principal());assertTrue(entry.flags().isEmpty());
    }

    @Test void filesystemWithoutPrivatePermissionMechanismIsRejected() throws Exception {
        assertThrows(IOException.class,()->AtomicFiles.privateAttributes(directory,new AttributeStore("none")));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void secondJvmCannotStealOwnerAndTerminationReleasesLock(boolean terminate) throws Exception {
        for(var type:List.of(StorageType.JSON,StorageType.SQLITE)) {
            Path root=directory.resolve(type.name());Files.createDirectories(root);
            var child=new ProcessBuilder(PortableTestFiles.javaExecutable(),"-cp",System.getProperty("release.qa.classpath"),StorageOwnerProbe.class.getName(),root.toRealPath().toString(),type.name()).redirectError(root.resolve("owner-errors.log").toFile()).start();
            try {
                var ready=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return new BufferedReader(new InputStreamReader(child.getInputStream())).readLine();}catch(IOException e){throw new UncheckedIOException(e);}});
                assertEquals("OWNER_READY",ready.get(30,TimeUnit.SECONDS));
                assertThrows(Exception.class,()->StorageFixtures.local(root,type,false));
                if(terminate)child.destroyForcibly();else{child.getOutputStream().write('\n');child.getOutputStream().flush();}
                assertTrue(child.waitFor(20,TimeUnit.SECONDS));if(!terminate)assertEquals(0,child.exitValue());
                try(var reopened=StorageFixtures.local(root,type,false)){assertEquals(1,reopened.snapshot().profiles().size());assertEquals(PlayerLanguage.en_US,reopened.language(new UUID(47,91),PlayerLanguage.ru_RU));}
            } finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS));}}
        }
    }

    @Test void changedDirectoryIdentityIsRejectedWhereReplacementIsPossible() throws Exception {
        Path root=directory.resolve("managed");Files.createDirectories(root);
        try(var lease=new StorageLease(root.resolve("owner.lock"))) {
            try {Files.move(root,directory.resolve("old-managed"));}
            catch(AccessDeniedException platformLock){lease.verify();return;}
            Files.createDirectories(root);assertThrows(IOException.class,lease::verify);
        }
    }

    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void rejectedLocalDuplicateMustNotReleaseOriginalProcessLock(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)) {
            backend.getOrCreate(new UUID(12,3),"global");
            assertThrows(Exception.class,()->StorageFixtures.local(directory,type,false));
            assertChildRefused(directory.toRealPath().toString(),type.name(),"try");
            assertNotNull(backend.getOrCreate(new UUID(12,4),"global"));
        }
        try(var reopened=StorageFixtures.local(directory,type,false)){assertEquals(2,reopened.snapshot().profiles().size());}
    }

    @Test void hardLinkedDuplicateMustNotReleaseOriginalProcessLock() throws Exception {
        Path lock=directory.resolve("owner.lock"),alias=directory.resolve("same-inode.lock");
        try(var owner=new StorageLease(lock)) {
            Files.createLink(alias,lock);
            assertThrows(IOException.class,()->new StorageLease(alias));
            assertChildRefused("--lease",lock.toRealPath().toString());owner.verify();
        }
        try(var reopened=new StorageLease(lock)){reopened.verify();}
    }

    private void assertChildRefused(String... arguments) throws Exception {
        var command=new ArrayList<>(List.of(PortableTestFiles.javaExecutable(),"-cp",System.getProperty("release.qa.classpath"),StorageOwnerProbe.class.getName()));command.addAll(List.of(arguments));
        var child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("refused-child.log").toFile()).start();
        try {assertTrue(child.waitFor(20,TimeUnit.SECONDS));assertEquals(42,child.exitValue(),()->"Second process acquired ownership; see "+directory.resolve("refused-child.log"));}
        finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS));}}
    }

    @Test void atomicMoveNeverFallsBackToCopy() throws Exception {
        Path target=directory.resolve("target");AtomicFiles.replace(target,new byte[]{1});
        AtomicFiles.replace(target,new byte[]{2});assertArrayEquals(new byte[]{2},Files.readAllBytes(target));
        Path other=directory.resolve("other");Files.createDirectories(other);
        assertThrows(IOException.class,()->AtomicFiles.move(target,other.resolve("target")));assertArrayEquals(new byte[]{2},Files.readAllBytes(target));
    }
    private static final class AttributeStore extends FileStore {
        private final String view;
        AttributeStore(String view){this.view=view;}
        public String name(){return "capability-test";}
        public String type(){return "synthetic";}
        public boolean isReadOnly(){return false;}
        public long getTotalSpace(){return 1;}
        public long getUsableSpace(){return 1;}
        public long getUnallocatedSpace(){return 1;}
        public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type){return type==PosixFileAttributeView.class&&view.equals("posix")||type==AclFileAttributeView.class&&view.equals("acl");}
        public boolean supportsFileAttributeView(String name){return view.equals(name);}
        public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type){return null;}
        public Object getAttribute(String name){throw new UnsupportedOperationException(name);}
    }
}
