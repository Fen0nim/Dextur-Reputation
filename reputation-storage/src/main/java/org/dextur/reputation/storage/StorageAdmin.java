package org.dextur.reputation.storage;

import java.nio.file.Path;
import java.util.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.dextur.reputation.application.Messages;
import org.dextur.reputation.application.Settings;
import org.dextur.reputation.domain.PlayerLanguage;

/** Explicit offline maintenance tool. Never prints exception messages, environment or SQL. */
public final class StorageAdmin {
    private StorageAdmin() { }
    public static void main(String[] args) {
        int status=run(args);if(status!=0) System.exit(status);
    }
    public static int run(String[] args) {
        PlayerLanguage language=PlayerLanguage.ru_RU;
        Messages messages;
        try {
            // Reading language templates needs no server, IO backend or secret.
            messages=Messages.bundled(language);
        } catch(Exception e) { System.err.println("Cannot load bundled localizations / Не удалось загрузить локализации");return 2; }
        try {
            if(args.length<1||(args.length-1)%2!=0) throw new IllegalArgumentException();
            Map<String,String> options=new HashMap<>();
            for(int n=1;n<args.length;n+=2) if(options.put(args[n],args[n+1])!=null) throw new IllegalArgumentException();
            if(options.containsKey("--language")) language=PlayerLanguage.parse(options.get("--language"));
            Set<String> allowed=Set.of("--data-directory","--target-config","--backups","--confirm","--language","--candidate");
            if(!allowed.containsAll(options.keySet())) throw new IllegalArgumentException();
            if(!"STOPPED-ALL-WRITERS".equals(options.get("--confirm"))) { print(messages,language,"storage-admin-usage",Map.of());return 2; }
            Path directory=Path.of(Objects.requireNonNull(options.get("--data-directory"))).toAbsolutePath().normalize();
            if(args[0].equals("migrate")) {
                Path target=Path.of(Objects.requireNonNull(options.get("--target-config"))).toAbsolutePath();
                Path backups=options.containsKey("--backups")?Path.of(options.get("--backups")):directory.resolve("backups");
                var result=new StorageMigration().migrate(directory,target,backups,System.getenv());
                print(messages,language,"storage-migrated",Map.of("profiles",Integer.toString(result.profiles()),"preferences",Integer.toString(result.preferences()),"backup",result.backupDirectory().toString(),"checksum",result.checksum()));
            } else if(args[0].equals("verify")) {
                try(var manager=new StorageManager()) {
                    manager.open(directory,Settings.load(directory),System.getenv());
                    var snapshot=manager.backend().snapshot();var codec=new SnapshotCodec();
                    byte[] canonical=codec.encode(0,snapshot);
                    if(!snapshot.equals(codec.decode(canonical).snapshot()))throw new IllegalStateException("Snapshot verification failed");
                    print(messages,language,"storage-verified",Map.of("profiles",Integer.toString(snapshot.profiles().size()),"votes",Integer.toString(snapshot.votes().size()),"checksum",codec.checksum(snapshot)));
                }
            } else if(args[0].equals("recover-json")) {
                Path backup=new StorageMigration().recoverJson(directory,Settings.load(directory),Objects.requireNonNull(options.get("--candidate")));
                print(messages,language,"storage-recovered",Map.of("backup",backup.toString()));
            } else { print(messages,language,"storage-admin-usage",Map.of());return 2; }
            return 0;
        } catch(Exception e) {
            print(messages,language,"storage-admin-failed",Map.of("error",e.getClass().getSimpleName()));return 1;
        }
    }
    private static void print(Messages messages,PlayerLanguage language,String key,Map<String,String> values) {
        System.out.println(PlainTextComponentSerializer.plainText().serialize(messages.render(language,key,values)));
    }
}
