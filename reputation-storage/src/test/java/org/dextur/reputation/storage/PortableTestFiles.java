package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Native Java processes; Windows junctions exercise redirection without symlink privileges. */
final class PortableTestFiles {
    private static final ThreadLocal<java.util.List<Path>> links=ThreadLocal.withInitial(java.util.ArrayList::new);
    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
    }
    static void directoryLink(Path link,Path target) throws Exception {
        links.get().add(link);
        try {Files.createSymbolicLink(link,target);}
        catch(IOException|UnsupportedOperationException unavailable) {
            if(!System.getProperty("os.name").startsWith("Windows"))throw unavailable;
            var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();
            try {
                if(!process.waitFor(10,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("Neither symbolic links nor junctions can be tested",unavailable);
            } finally {if(process.isAlive()){process.destroyForcibly();if(!process.waitFor(10,TimeUnit.SECONDS))throw new IOException("Junction helper did not terminate");}}
            if(!Files.isSameFile(link,target))throw new IOException("Junction did not resolve to its target");
        }
    }
    static void cleanupLinks() throws IOException {
        try {
            for(Path link:links.get()) {
                if(!Files.exists(link,LinkOption.NOFOLLOW_LINKS))continue;
                var attributes=Files.readAttributes(link,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!attributes.isSymbolicLink()&&!attributes.isOther())throw new IOException("Test link was replaced by an ordinary entry");
                Files.delete(link); // Never recursively traverse a junction or symlink target.
            }
        } finally {links.remove();}
    }
}
