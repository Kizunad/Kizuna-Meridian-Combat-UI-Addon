package dev.kizuna.meridian.combat.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** 损坏文件只读保护；正常保存使用同目录临时文件整份替换。 */
public final class CombatConfigFile {
    public static final int FORMAT_VERSION = 2;
    private static final String LEGACY_VERSION = "1";
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private boolean writable = true;

    public CombatConfigFile(Path path) {
        // 路径由客户端配置目录提供，不依赖进程当前目录。
        this.path = path.toAbsolutePath();
    }

    public CombatProfiles load() throws IOException {
        // 任何解析失败都保留原件，本次会话可继续使用空配置。
        if (!Files.exists(path)) {
            return new CombatProfiles();
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("战斗配置超过大小上限");
            }
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String version = root.get("format_version").getAsString();
            if (!Integer.toString(FORMAT_VERSION).equals(version) && !LEGACY_VERSION.equals(version)) {
                throw new IllegalArgumentException("战斗配置版本不受支持");
            }
            return decode(root);
        } catch (RuntimeException | IOException failure) {
            writable = false;
            throw new IOException("战斗配置无法读取，原文件已保留，本次使用空配置", failure);
        }
    }

    public boolean writable() {
        // 调用方可提示只在内存生效，不能覆盖损坏或更新版本的原文件。
        return writable;
    }

    public void save(CombatProfiles profiles) throws IOException {
        // 只读保护在创建临时文件之前检查。
        if (!writable) {
            throw new IOException("战斗配置处于只读保护，原文件已保留");
        }
        JsonObject root = encode(profiles);
        Path parent = path.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "meridian-combat-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(root), StandardCharsets.UTF_8);
            replace(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void replace(Path temporary) throws IOException {
        // 不支持原子移动的文件系统仍只执行完整文件替换。
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static CombatProfiles decode(JsonObject root) {
        // 所有档案先解码成功再发布，防止部分恢复后覆盖原文件。
        var profiles = new LinkedHashMap<String, CombatProfiles.Profile>();
        for (var entry : root.getAsJsonObject("profiles").entrySet()) {
            JsonObject profile = entry.getValue().getAsJsonObject();
            var slots = new ArrayList<String>();
            for (JsonElement slot : profile.getAsJsonArray("slots")) {
                slots.add(string(slot));
            }
            Instant lastUsed = Instant.parse(string(profile.get("last_used")));
            profiles.put(entry.getKey(), new CombatProfiles.Profile(lastUsed, slots));
        }
        CombatViewSettings view = CombatViewSettings.DEFAULT;
        if (!LEGACY_VERSION.equals(root.get("format_version").getAsString())) {
            view = decodeView(root.getAsJsonObject("view"));
        }
        return new CombatProfiles(string(root.get("last_profile")), profiles, view);
    }

    private static CombatViewSettings decodeView(JsonObject view) {
        // 版本二要求完整视角对象，字段缺失按配置损坏保护原文件。
        CombatView.Mode mode = CombatView.Mode.valueOf(string(view.get("mode")));
        return new CombatViewSettings(mode, number(view.get("shoulder_offset")),
                number(view.get("height_offset")), number(view.get("distance")),
                (float) number(view.get("turn_degrees_per_tick")));
    }

    private static double number(JsonElement element) {
        // 数字字符串不是配置数值，拒绝隐式转换后再由设置对象校验有限性和范围。
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("战斗视角字段必须是数值");
        }
        return element.getAsDouble();
    }

    private static String string(JsonElement element) {
        // Gson 的隐式数字转字符串不能把损坏的槽位伪装为合法配置。
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("战斗配置字段必须是字符串");
        }
        return element.getAsString();
    }

    private static JsonObject encode(CombatProfiles profiles) {
        // 只保存槽位和视角偏好，不持久化临时镜头、进入前视角或服务端已学列表。
        var root = new JsonObject();
        root.addProperty("format_version", FORMAT_VERSION);
        root.addProperty("last_profile", profiles.current());
        var entries = new JsonObject();
        profiles.snapshot().forEach((key, profile) -> {
            var entry = new JsonObject();
            entry.addProperty("last_used", profile.lastUsed().toString());
            entry.add("slots", JSON.toJsonTree(profile.slots()));
            entries.add(key, entry);
        });
        root.add("profiles", entries);
        root.add("view", encodeView(profiles.view()));
        return root;
    }

    private static JsonObject encodeView(CombatViewSettings view) {
        // 显式字段名保持文件格式稳定，不依赖 Gson 对 record 的反射命名。
        var entry = new JsonObject();
        entry.addProperty("mode", view.mode().name());
        entry.addProperty("shoulder_offset", view.shoulderOffset());
        entry.addProperty("height_offset", view.heightOffset());
        entry.addProperty("distance", view.distance());
        entry.addProperty("turn_degrees_per_tick", view.turnDegreesPerTick());
        return entry;
    }
}
