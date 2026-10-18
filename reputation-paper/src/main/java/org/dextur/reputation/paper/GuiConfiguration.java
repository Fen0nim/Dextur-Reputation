package org.dextur.reputation.paper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.Material;
import org.dextur.reputation.application.*;

record GuiConfiguration(int size, List<Integer> contentSlots, Map<String,Button> buttons, Material reasonMaterial, Material entryMaterial) {
    record Button(int slot,Material material) { }
    static GuiConfiguration load(Path directory) throws IOException {
        return load(directory,m->m.isItem()&&!m.isAir());
    }
    static GuiConfiguration load(Path directory,java.util.function.Predicate<Material> isItem) throws IOException {
        var y=YamlFiles.read(directory.resolve("gui.yml"));YamlFiles.version(y);
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
        return new GuiConfiguration(size,List.copyOf(content),Map.copyOf(buttons),material(YamlFiles.text(y,"reason-material"),isItem),material(YamlFiles.text(y,"entry-material"),isItem));
    }
    private static Material material(String name,java.util.function.Predicate<Material> isItem) {
        var value=Material.matchMaterial(name);if(value==null||!isItem.test(value)) throw new IllegalArgumentException("Invalid GUI material");return value;
    }
}
