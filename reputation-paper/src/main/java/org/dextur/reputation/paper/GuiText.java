package org.dextur.reputation.paper;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.dextur.reputation.application.Messages;
import org.dextur.reputation.domain.PlayerLanguage;

/** Item text otherwise inherits Minecraft's default italic item styling. */
final class GuiText {
    private GuiText() { }
    static Component normal(Component text){return text.decorationIfAbsent(TextDecoration.ITALIC,TextDecoration.State.FALSE);}
    static List<Component> control(Messages messages,PlayerLanguage locale,String key){
        String action=key.equals("gui-close")?"close":key.equals("gui-back")?"back":key.equals("gui-refresh")||key.equals("admin7-refresh")?"refresh":key.equals("recovery-start")||key.equals("recovery-cancel")?"execute":"open";
        String description=key.equals("gui-my-access")?"gui-access-desc":key.equals("recovery-start")||key.equals("recovery-cancel")?"gui-"+key+"-desc":"gui-control-"+action;
        return List.of(normal(messages.content(locale,description,java.util.Map.of())),
                normal(messages.content(locale,"admin7-ui-click-"+action,java.util.Map.of())));
    }
}
