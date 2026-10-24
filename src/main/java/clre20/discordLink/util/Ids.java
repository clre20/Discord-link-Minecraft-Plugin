package clre20.discordLink.util;

import java.util.UUID;
import java.util.regex.Pattern;

/** UUID 解析（接受有或沒有 - 的格式）。 */
public final class Ids {

    private static final Pattern UUID_32 = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern UUID_36 = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Ids() {
    }

    /** 不是 UUID 時回傳 null。 */
    public static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            if (UUID_36.matcher(s).matches()) {
                return UUID.fromString(s);
            }
            if (UUID_32.matcher(s).matches()) {
                return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                        + "-" + s.substring(16, 20) + "-" + s.substring(20));
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }
}
