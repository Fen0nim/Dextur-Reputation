package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Trust server-selected roots; resolve managed entries only within their canonical boundary. */
final class StoragePaths {
    private StoragePaths() { }

    /** Low-level operations never infer trust for a link below the JVM's configured roots. */
    static Path checked(Path path) throws IOException {
        Path absolute=path.toAbsolutePath().normalize(), base=absolute.getRoot().toRealPath();
        int length=0;
        for(Path configured:new Path[]{Path.of(System.getProperty("java.io.tmpdir")),Path.of("")}) {
            Path logical=configured.toAbsolutePath().normalize();
            Path physical=logical.toRealPath();
            for(Path anchor:new Path[]{logical,physical}) {
                if(absolute.startsWith(anchor)&&anchor.getNameCount()>length) {
                    length=anchor.getNameCount();base=physical;
                }
            }
        }
        if(length==absolute.getNameCount())return base;
        Path tail=absolute.subpath(length,absolute.getNameCount());
        return walk(base,tail,false);
    }

    private static Path walk(Path base,Path relative,boolean insideLinks) throws IOException {
        Path current=base;
        for(Path name:relative) {
            Path next=current.resolve(name);
            BasicFileAttributes attributes;
            try { attributes=Files.readAttributes(next,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
            catch(NoSuchFileException missing) { current=next;continue; }
            if(attributes.isOther()&&(!insideLinks||!Files.isDirectory(next)))throw new IOException("Unsupported storage filesystem entry: "+next);
            Path real=next.toRealPath();
            if(insideLinks) {
                if(!real.startsWith(base))throw new IOException("Storage link escapes trusted root: "+next);
            } else {
                if(attributes.isSymbolicLink())throw new IOException("Untrusted storage path redirection: "+next);
                // Case folding/8.3 aliases may change spelling, but must preserve parent and inode.
                if(!real.equals(next)) {
                    Object key=Files.readAttributes(real,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
                    if(real.getParent()==null||!real.getParent().equals(current)||attributes.fileKey()==null||!attributes.fileKey().equals(key))
                        throw new IOException("Storage path resolves outside its expected location: "+next);
                }
            }
            current=real;
        }
        return current;
    }

    /** The caller explicitly trusts this administrator-selected directory and its ancestry. */
    static Root trustedRoot(Path directory) throws IOException {
        Path logical=directory.toAbsolutePath().normalize();
        var attributes=Files.readAttributes(logical,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isDirectory()||attributes.isSymbolicLink()||attributes.isOther())throw new IOException("Trusted root must be a real directory: "+directory);
        Path physical=logical.toRealPath();
        return new Root(logical,physical,attributes.fileKey());
    }

    record Root(Path logical,Path path,Object key) {
        void verify() throws IOException {
            var attributes=Files.readAttributes(logical,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isDirectory()||attributes.isSymbolicLink()||attributes.isOther()||!logical.toRealPath().equals(path)
                    ||(key!=null&&!key.equals(attributes.fileKey())))throw new IOException("Trusted storage root replaced or redirected");
        }
        Path resolve(String relative) throws IOException {
            verify();
            Path value=Path.of(relative),candidate=path.resolve(value).normalize();
            if(value.isAbsolute()||candidate.equals(path)||!candidate.startsWith(path))throw new IOException("Storage path escapes trusted root");
            Path result=walk(path,path.relativize(candidate),true);
            verify();return result;
        }
    }

    /** Explicit external backup directory; its ancestors, not its leaf link, are administrator-trusted. */
    static Path selectedDirectory(Path directory) throws IOException {
        Path absolute=directory.toAbsolutePath().normalize(),existing=absolute;
        while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.getParent();
        if(existing.equals(absolute))return trustedRoot(absolute).path();
        var root=trustedRoot(existing.toRealPath());
        return root.resolve(existing.relativize(absolute).toString());
    }

    static Path resolve(Path root,String relative) throws IOException {
        return trustedRoot(root).resolve(relative);
    }

    static Identity identity(Path directory) throws IOException {
        Path canonical=checked(directory);
        var attributes=Files.readAttributes(canonical,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isDirectory())throw new IOException("Directory required: "+directory);
        return new Identity(canonical,attributes.fileKey());
    }

    record Identity(Path path,Object key) {
        void verify() throws IOException {
            if(!path.equals(checked(path)))throw new IOException("Storage directory redirected");
            var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isDirectory()||(key!=null&&!key.equals(attributes.fileKey())))throw new IOException("Storage directory replaced");
        }
    }
}
