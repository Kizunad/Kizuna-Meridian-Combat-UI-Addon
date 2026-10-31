package dev.kizuna.meridian.combat.fabric.client;

import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.FastColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.blaze3d.platform.NativeImage;
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
    private final Map<ResourceLocation, Optional<ResourceLocation>> grayscale = new HashMap<>();

    @Override
    public ResourceLocation getFabricId() {
        // 固定监听器身份便于资源重载排序与诊断。
        return new ResourceLocation("meridian_combat", "hud_icons");
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        // 失败记录也随资源包更新失效，下一次绘制按新资源重新生成。
        var textures = Minecraft.getInstance().getTextureManager();
        grayscale.values().forEach(value -> value.ifPresent(textures::release));
        grayscale.clear();
    }

    Optional<ResourceLocation> grayscale(ResourceLocation source) {
        // 每张图每次重载最多解码一次，不在每帧扫描像素。
        return grayscale.computeIfAbsent(source, this::loadGrayscale);
    }

    private Optional<ResourceLocation> loadGrayscale(ResourceLocation source) {
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
                    int color = image.getPixelRGBA(x, y);
                    int gray = (FastColor.ABGR32.red(color) * RED_WEIGHT
                            + FastColor.ABGR32.green(color) * GREEN_WEIGHT
                            + FastColor.ABGR32.blue(color) * BLUE_WEIGHT) / WEIGHT_SUM;
                    image.setPixelRGBA(x, y, FastColor.ABGR32.color(FastColor.ABGR32.alpha(color), gray, gray, gray));
                }
            }
            var id = new ResourceLocation("meridian_combat", "hud_gray/" + source.getNamespace() + "/" + source.getPath());
            client.getTextureManager().register(id, new DynamicTexture(image));
            return Optional.of(id);
        } catch (IOException failure) {
            LOGGER.warn("无法读取战斗图标：{}", source, failure);
            return Optional.empty();
        }
    }
}
