package dev.kizuna.meridian.combat.fabric.client;

import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 缓存资源包图标及其灰阶版本，重载时释放动态纹理。 */
final class CombatHudIcons implements SimpleSynchronousResourceReloadListener {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_combat");
    private static final int RED_WEIGHT = 77;
    private static final int GREEN_WEIGHT = 150;
    private static final int BLUE_WEIGHT = 29;
    private static final int WEIGHT_SUM = 256;
    private final Map<Identifier, Optional<Identifier>> grayscale = new HashMap<>();

    @Override
    public Identifier getFabricId() {
        // 固定监听器身份便于资源重载排序与诊断。
        return new Identifier("meridian_combat", "hud_icons");
    }

    @Override
    public void reload(ResourceManager manager) {
        // 失败记录也随资源包更新失效，下一次绘制按新资源重新生成。
        var textures = MinecraftClient.getInstance().getTextureManager();
        grayscale.values().forEach(value -> value.ifPresent(textures::destroyTexture));
        grayscale.clear();
    }

    Optional<Identifier> grayscale(Identifier source) {
        // 每张图每次重载最多解码一次，不在每帧扫描像素。
        return grayscale.computeIfAbsent(source, this::loadGrayscale);
    }

    private Optional<Identifier> loadGrayscale(Identifier source) {
        // 保持透明度和像素尺寸，动态纹理继承最近邻采样。
        var client = MinecraftClient.getInstance();
        var resource = client.getResourceManager().getResource(source);
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        try (var stream = resource.get().getInputStream()) {
            var image = NativeImage.read(stream);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int color = image.getColor(x, y);
                    int gray = (ColorHelper.Abgr.getRed(color) * RED_WEIGHT
                            + ColorHelper.Abgr.getGreen(color) * GREEN_WEIGHT
                            + ColorHelper.Abgr.getBlue(color) * BLUE_WEIGHT) / WEIGHT_SUM;
                    image.setColor(x, y, ColorHelper.Abgr.getAbgr(ColorHelper.Abgr.getAlpha(color), gray, gray, gray));
                }
            }
            var id = new Identifier("meridian_combat", "hud_gray/" + source.getNamespace() + "/" + source.getPath());
            client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
            return Optional.of(id);
        } catch (IOException failure) {
            LOGGER.warn("无法读取战斗图标：{}", source, failure);
            return Optional.empty();
        }
    }
}
