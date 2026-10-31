package dev.kizuna.meridian.combat.fabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.ARGB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 缓存资源包图标及其灰阶版本，重载时释放动态纹理。 */
final class CombatHudIcons implements ResourceManagerReloadListener {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_combat");
    private static final int RED_WEIGHT = 77;
    private static final int GREEN_WEIGHT = 150;
    private static final int BLUE_WEIGHT = 29;
    private static final int WEIGHT_SUM = 256;
    private final Map<Identifier, Optional<Identifier>> grayscale = new HashMap<>();

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        // 失败记录也随资源包更新失效，下一次绘制按新资源重新生成。
        var textures = Minecraft.getInstance().getTextureManager();
        grayscale.values().forEach(value -> value.ifPresent(textures::release));
        grayscale.clear();
    }

    Optional<Identifier> grayscale(Identifier source) {
        // 每张图每次重载最多解码一次，不在每帧扫描像素。
        return grayscale.computeIfAbsent(source, this::loadGrayscale);
    }

    private Optional<Identifier> loadGrayscale(Identifier source) {
        // 保持透明度和像素尺寸，动态纹理继承最近邻采样。
        var client = Minecraft.getInstance();
        var resource = client.getResourceManager().getResource(source);
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        try (var stream = resource.get().open()) {
            var image = NativeImage.read(stream);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    // 26.2 的 NativeImage 像素读写按 ARGB 排列。
                    int color = image.getPixel(x, y);
                    int gray = (ARGB.red(color) * RED_WEIGHT
                            + ARGB.green(color) * GREEN_WEIGHT
                            + ARGB.blue(color) * BLUE_WEIGHT) / WEIGHT_SUM;
                    image.setPixel(x, y, ARGB.color(ARGB.alpha(color), gray, gray, gray));
                }
            }
            var id = Identifier.fromNamespaceAndPath("meridian_combat",
                    "hud_gray/" + source.getNamespace() + "/" + source.getPath());
            client.getTextureManager().register(id, new DynamicTexture(id::toString, image));
            return Optional.of(id);
        } catch (IOException failure) {
            LOGGER.warn("无法读取战斗图标：{}", source, failure);
            return Optional.empty();
        }
    }
}
