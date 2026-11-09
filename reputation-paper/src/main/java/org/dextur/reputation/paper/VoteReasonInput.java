package org.dextur.reputation.paper;

import java.util.*;
import org.dextur.reputation.api.VoteType;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;

/** Server-side names only: requests and stored votes always carry the stable ID. */
final class VoteReasonInput {
    record Parsed(String id,String comment) { }
    private final VotingConfiguration config;
    private final Messages messages;
    VoteReasonInput(VotingConfiguration config,Messages messages){this.config=config;this.messages=messages;}
    private Map<String,String> names(PlayerLanguage language,VoteType type,boolean ids){
        var result=new LinkedHashMap<String,String>();
        config.rules().reasons().values().stream().filter(r->r.types().contains(type)).sorted(Comparator.comparing(r->r.id())).forEach(r->{
            if(ids)result.put(r.id(),r.id());
            result.put(config.reasonLabel(messages,language,r.id()),r.id());
        });
        return result;
    }
    Parsed parse(PlayerLanguage language,VoteType type,String input){
        String text=input.strip();boolean quoted=text.startsWith("\"");
        if(quoted)text=text.substring(1);
        final String remaining=text;
        return names(language,type,true).entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String,String> e)->e.getKey().length()).reversed())
            .filter(e->remaining.regionMatches(true,0,e.getKey(),0,e.getKey().length()))
            .filter(e->{String tail=remaining.substring(e.getKey().length());return quoted?(tail.equals("\"")||tail.startsWith("\" ")):(tail.isEmpty()||tail.startsWith(" "));})
            .findFirst().map(e->new Parsed(e.getValue(),remaining.substring(e.getKey().length()+(quoted?1:0)).strip())).orElse(null);
    }
    List<String> suggest(PlayerLanguage language,VoteType type,String[] arguments){
        String input=String.join(" ",Arrays.copyOfRange(arguments,1,arguments.length));
        String committed=arguments.length<=2?"":String.join(" ",Arrays.copyOfRange(arguments,1,arguments.length-1))+" ";
        boolean quoted=input.startsWith("\"");
        // Bukkit replaces the final argument, not the entire multi-word phrase.
        return names(language,type,false).keySet().stream().map(n->quoted?"\""+n+"\"":n)
            .filter(n->n.regionMatches(true,0,input,0,input.length())).map(n->n.substring(committed.length())).sorted().toList();
    }
}
