package org.dextur.reputation.application;
import java.nio.file.Path;
import java.util.*;
import static org.dextur.reputation.application.TierConfiguration.*;
public record DisplayConfiguration(Map<String,Object> reputation,Nameplate nameplate,Tab tab,int interval,int batch,int maximumTeams,
        Map<String,Object> notifications,LuckPerms luckperms) {
    public enum NameMode { DISABLED,TEAM_IF_FREE }
    public enum TabMode { DISABLED,DIRECT,PLACEHOLDER_ONLY }
    public record Nameplate(NameMode mode,String format,String visibility,int length) { }
    public record Tab(TabMode mode,String format,int length) { }
    public record LuckPerms(boolean enabled,boolean createGroups,int retrySeconds,int batch) { }
    public DisplayConfiguration { reputation=Map.copyOf(reputation);notifications=Map.copyOf(notifications); }
    public static DisplayConfiguration load(Path directory) throws Exception {
        var y=configuration(directory,"display.yml");YamlFiles.version(y);var rep=map(y.get("reputation"));var name=map(y.get("nameplate"));var tab=map(y.get("tab"));var refresh=map(y.get("refresh"));var n=map(y.get("notifications"));
        for(String sign:List.of("positive","negative","neutral")) {DisplayFormatter.validate(string(rep,sign+"-format"),Set.of("score"));DisplayFormatter.color(string(rep,sign+"-color"));}
        for(String flag:List.of("tier-colors","thousands","compact")) YamlFiles.flag(rep,flag);
        for(var row:List.of(name,tab)) DisplayFormatter.validate(string(row,"format"),Set.of("score","reputation","tier","prefix","suffix","player"));
        String visibility=string(name,"visibility");if(!Set.of("ALWAYS","NEVER","HIDE_FOR_OTHER_TEAMS","HIDE_FOR_OWN_TEAM").contains(visibility)) throw new IllegalArgumentException("Invalid nameplate.visibility");
        for(String flag:List.of("enabled","chat","action-bar","title","gain","loss","negative","severe","recover","neutral")) YamlFiles.flag(n,flag);
        YamlFiles.number(n,"cooldown-seconds",0,86400);String sound=string(n,"sound");if(!sound.matches("|[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid notification sound");
        var lp=map(configuration(directory,"integrations.yml").get("luckperms"));
        return new DisplayConfiguration(rep,new Nameplate(NameMode.valueOf(string(name,"mode")),string(name,"format"),visibility,YamlFiles.number(name,"maximum-length",1,256)),
            new Tab(TabMode.valueOf(string(tab,"mode")),string(tab,"format"),YamlFiles.number(tab,"maximum-length",1,512)),YamlFiles.number(refresh,"interval-ticks",20,12000),YamlFiles.number(refresh,"batch-size",1,100),YamlFiles.number(refresh,"maximum-teams",1,16384),n,
            new LuckPerms(YamlFiles.flag(lp,"enabled"),YamlFiles.flag(lp,"create-groups"),YamlFiles.number(lp,"retry-seconds",5,3600),YamlFiles.number(lp,"batch-size",1,100)));
    }
}
