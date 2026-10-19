package org.dextur.reputation.paper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.Material;
import org.dextur.reputation.application.*;

record GuiConfiguration(int size, List<Integer> contentSlots, Map<String,Button> buttons, Material reasonMaterial, Material entryMaterial, TierPanel tierPanel) {
    record TierPanel(boolean enabled,Material material,Map<String,Material> icons,List<String> lore,Map<String,Map<String,String>> benefits) { }
    void validateTiers(org.dextur.reputation.domain.tier.TierRules rules) {
        var ids=rules.definitions().stream().map(org.dextur.reputation.domain.tier.TierRules.Definition::id).toList();
        if(!ids.containsAll(tierPanel.icons().keySet())||!ids.containsAll(tierPanel.benefits().keySet()))throw new IllegalArgumentException("GUI references unknown tier");
    }
    record Button(int slot,Material material) { }
    static GuiConfiguration load(Path directory) throws IOException {
        return load(directory,m->m.isItem()&&!m.isAir());
    }
    static GuiConfiguration load(Path directory,java.util.function.Predicate<Material> isItem) throws IOException {
        var shipped=YamlFiles.resource("/gui.yml");var file=directory.resolve("gui.yml");var custom=java.nio.file.Files.exists(file)?YamlFiles.read(file):shipped;
        var y=TierConfiguration.merge(shipped,custom);YamlFiles.version(y);
        int size=YamlFiles.number(y,"size",45,54);if(size%9!=0) throw new IllegalArgumentException("GUI size must be a multiple of nine");
        var buttons=new HashMap<String,Button>();var used=new HashSet<Integer>();
        var rows=VotingConfiguration.mapping(y.get("buttons"));
        for(String key:List.of("profile","like","dislike","revoke","history","given","close","back","previous","next")) {
            var row=VotingConfiguration.mapping(rows.get(key));int slot=YamlFiles.number(row,"slot",36,size-1);
            // Navigation and profile screens share positions, but buttons on the same screen may not overlap.
            buttons.put(key,new Button(slot,material(YamlFiles.text(row,"material"),isItem)));
        }
        for(var screen:List.of(List.of("profile","like","dislike","revoke","history","given","close"),List.of("back","previous","next","close"))) {
            used.clear();for(String key:screen) if(!used.add(buttons.get(key).slot())) throw new IllegalArgumentException("Overlapping GUI buttons");
        }
        if(!(y.get("content-slots") instanceof List<?> slots)||slots.isEmpty()||slots.size()>36) throw new IllegalArgumentException("GUI content-slots required (1..36)");
        var content=new ArrayList<Integer>();
        for(var slot:slots) {
            if(!(slot instanceof Integer number)||number<0||number>=36||content.contains(number)) throw new IllegalArgumentException("Invalid content slot");
            content.add(number);
        }
        var panel=VotingConfiguration.mapping(y.get("tier-panel"));var icons=new HashMap<String,Material>();
        for(var e:VotingConfiguration.mapping(panel.get("icons")).entrySet()) icons.put(e.getKey(),material(String.valueOf(e.getValue()),isItem));
        var benefits=new HashMap<String,Map<String,String>>();
        for(var e:VotingConfiguration.mapping(panel.get("benefits")).entrySet()) {var translated=VotingConfiguration.mapping(e.getValue());var result=new HashMap<String,String>();for(String language:List.of("ru_RU","en_US")) {String text=TierConfiguration.string(translated,language);if(text.length()>2000)throw new IllegalArgumentException("GUI description too long");result.put(language,text);}benefits.put(e.getKey(),Map.copyOf(result));}
        if(!(panel.get("lore-keys") instanceof List<?> lore)||lore.size()>16||lore.stream().anyMatch(k->!Set.of("gui-tier","gui-tier-progress","gui-tier-benefits").contains(k))) throw new IllegalArgumentException("Invalid tier GUI lore keys");
        // A new optional panel must not replace an existing administrator's profile icon implicitly.
        boolean explicitMaterial=custom.get("tier-panel") instanceof Map<?,?> configured&&configured.containsKey("material");
        var panelMaterial=explicitMaterial?material(YamlFiles.text(panel,"material"),isItem):buttons.get("profile").material();
        var tierPanel=new TierPanel(YamlFiles.flag(panel,"enabled"),panelMaterial,Map.copyOf(icons),lore.stream().map(Object::toString).toList(),Map.copyOf(benefits));
        return new GuiConfiguration(size,List.copyOf(content),Map.copyOf(buttons),material(YamlFiles.text(y,"reason-material"),isItem),material(YamlFiles.text(y,"entry-material"),isItem),tierPanel);
    }
    private static Material material(String name,java.util.function.Predicate<Material> isItem) {
        var value=Material.matchMaterial(name);if(value==null||!isItem.test(value)) throw new IllegalArgumentException("Invalid GUI material");return value;
    }
}
