package org.dextur.reputation.paper;

import java.util.UUID;

/** Bounds parsing before known-player lookup or string joins; valid 2000-character vote comments remain supported. */
final class CommandInput {
    private CommandInput() { }
    static boolean valid(String[] args) {
        if(args.length>4096)return false;
        int length=Math.max(0,args.length-1);
        for(String arg:args){if(arg==null||arg.length()>8192||arg.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\t'))return false;length+=arg.length();if(length>8192)return false;}
        return true;
    }
    static UUID uuid(String value) {
        if(!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("Canonical UUID required");
        return UUID.fromString(value);
    }
}
