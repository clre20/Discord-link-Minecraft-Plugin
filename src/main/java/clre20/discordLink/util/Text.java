package clre20.discordLink.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文字工具：MiniMessage 與 & 顏色碼都支援。
 * 變數 {name} 以「不解析」的方式放進去，玩家名稱、Discord 名稱裡的符號不會被當成格式。
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final Pattern LEGACY = Pattern.compile("&([0-9A-Fa-fK-Ok-oRr])");
    private static final Map<Character, String> CODES = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset"));

    private Text() {
    }

    /**
     * 把設定檔文字（含 {變數}）轉成 Component。
     *
     * @param vars 成對的「名稱, 值」
     */
    public static Component component(String template, Object... vars) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        String s = legacyToMini(template);
        List<TagResolver> resolvers = new ArrayList<>();
        if (vars.length > 0 && s.indexOf('{') >= 0) {
            for (int i = 0; i + 1 < vars.length; i += 2) {
                String name = String.valueOf(vars[i]);
                String tag = "dlv_" + name;
                if (s.contains("{" + name + "}")) {
                    s = s.replace("{" + name + "}", "<" + tag + ">");
                    resolvers.add(Placeholder.unparsed(tag, String.valueOf(vars[i + 1])));
                }
            }
        }
        try {
            return MM.deserialize(s, TagResolver.resolver(resolvers));
        } catch (RuntimeException e) {
            return Component.text(plainReplace(template, vars));
        }
    }

    /** 純文字：去掉顏色並替換變數（後台用）。 */
    public static String plain(String template, Object... vars) {
        return PlainTextComponentSerializer.plainText().serialize(component(template, vars));
    }

    /** 只替換變數，不處理顏色（Discord 文字用）。 */
    public static String plainReplace(String template, Object... vars) {
        if (template == null) {
            return "";
        }
        if (vars.length == 0 || template.indexOf('{') < 0) {
            return template;
        }
        String out = template;
        for (int i = 0; i + 1 < vars.length; i += 2) {
            out = out.replace("{" + vars[i] + "}", String.valueOf(vars[i + 1]));
        }
        return out;
    }

    /** & 顏色碼轉 MiniMessage 標籤；& 顏色與原版一樣會先清掉格式。 */
    static String legacyToMini(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        String s = HEX.matcher(text).replaceAll("<reset><#$1>");
        Matcher m = LEGACY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            char c = Character.toLowerCase(m.group(1).charAt(0));
            String tag = "<" + CODES.get(c) + ">";
            boolean color = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            m.appendReplacement(sb, Matcher.quoteReplacement(color ? "<reset>" + tag : tag));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
