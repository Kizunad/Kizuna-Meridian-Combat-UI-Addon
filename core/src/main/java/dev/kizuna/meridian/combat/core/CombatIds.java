package dev.kizuna.meridian.combat.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** 客户端配置与网络共同使用的标识边界。 */
public final class CombatIds {
    public static final int SLOT_COUNT = 10;
    public static final int MAX_ID_LENGTH = 256;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private CombatIds() {
        // 标识规则不持有会话状态。
    }

    public static void validateTechnique(String value) {
        // 必须是完整命名空间标识，避免命令和服务端解释不一致。
        if (value == null || value.length() > MAX_ID_LENGTH || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("功法标识必须为 命名空间:名称");
        }
    }

    public static int slotIndex(int number) {
        // 对外槽号始终为 1–10，内部只在校验后转换下标。
        if (number < 1 || number > SLOT_COUNT) {
            throw new IllegalArgumentException("槽号必须在 1–10 之间");
        }
        return number - 1;
    }

    public static String serverProfile(String address) {
        // 仅统一大小写，地址和端口的其他差异仍代表不同档。
        if (address.isBlank()) {
            throw new IllegalArgumentException("服务器地址不能为空");
        }
        return "server:" + address.toLowerCase(Locale.ROOT);
    }

    public static String worldProfile(String folder) {
        // 存档身份使用文件夹名，不使用玩家可以修改的显示名称。
        if (folder.isBlank()) {
            throw new IllegalArgumentException("存档文件夹名不能为空");
        }
        return "world:" + folder;
    }
}
