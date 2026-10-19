package org.dextur.reputation.application;

import java.text.*;
import java.util.*;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.*;
import net.kyori.adventure.text.minimessage.tag.resolver.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.dextur.reputation.api.ReputationTierSnapshot;
import org.dextur.reputation.domain.PlayerLanguage;

/** One formatter. Untrusted values are components, never parsed as MiniMessage. */
public final class DisplayFormatter {
    private static final MiniMessage MINI=MiniMessage.builder().strict(true).build();
    private final DisplayConfiguration configuration;
    public DisplayFormatter(DisplayConfiguration configuration) { this.configuration=configuration; }
    public static TextColor color(String hex) {if(!hex.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Color must be #rrggbb: "+hex);return TextColor.fromHexString(hex);}
    public static void validate(String template,Set<String> allowed) { if(template.length()>4096||!allowed.containsAll(Messages.placeholders(template))) throw new IllegalArgumentException("Unsupported display placeholder / length: "+template);render(template,Map.of()); }
    public static Component render(String template,Map<String,Component> values) {
        var resolver=TagResolver.builder();for(String key:Messages.placeholders(template)) resolver.resolver(Placeholder.component("arg_"+key,values.getOrDefault(key,Component.empty())));
        return MINI.deserialize(template.replaceAll("\\{([a-z][a-z0-9_]*)}","<arg_$1>"),resolver.build());
    }
    public static String plain(Component value) {return PlainTextComponentSerializer.plainText().serialize(value);}
    public static String mini(Component value) {return MINI.serialize(value);}
    public Component name(ReputationTierSnapshot tier,PlayerLanguage language) { return MINI.deserialize(tier.localizedNames().getOrDefault(language.name(),tier.localizedNames().get("en_US"))); }
    public String number(long score,PlayerLanguage language,boolean absolute) {
        var value=java.math.BigInteger.valueOf(score);if(absolute) value=value.abs();
        Locale locale=Locale.forLanguageTag(language.name().replace('_','-'));
        if(YamlFiles.flag(configuration.reputation(),"compact")) return NumberFormat.getCompactNumberInstance(locale,NumberFormat.Style.SHORT).format(value);
        var format=NumberFormat.getIntegerInstance(locale);format.setGroupingUsed(YamlFiles.flag(configuration.reputation(),"thousands"));return format.format(value);
    }
    public Component score(ReputationTierSnapshot tier,PlayerLanguage language) {
        String sign=tier.score()>0?"positive":tier.score()<0?"negative":"neutral";
        var rep=configuration.reputation();String hex=YamlFiles.flag(rep,"tier-colors")?tier.color():(String)rep.get(sign+"-color");
        return render((String)rep.get(sign+"-format"),Map.of("score",Component.text(number(tier.score(),language,true)))).color(color(hex));
    }
    public Component prefix(ReputationTierSnapshot tier,PlayerLanguage language) { return decoration(tier.prefix(),tier,language); }
    public Component suffix(ReputationTierSnapshot tier,PlayerLanguage language) { return decoration(tier.suffix(),tier,language); }
    private Component decoration(String template,ReputationTierSnapshot tier,PlayerLanguage language) {return render(template,Map.of("tier",name(tier,language),"score",Component.text(number(tier.score(),language,false))));}
    public Component display(String template,String player,ReputationTierSnapshot tier,PlayerLanguage language) {
        return render(template,Map.of("player",Component.text(player),"score",Component.text(number(tier.score(),language,false)),"tier",name(tier,language),"reputation",score(tier,language),"prefix",prefix(tier,language),"suffix",suffix(tier,language)));
    }
    public Component limit(Component value,int max) { String text=plain(value);return text.codePointCount(0,text.length())<=max?value:Component.text(text.substring(0,text.offsetByCodePoints(0,max))); }
}
