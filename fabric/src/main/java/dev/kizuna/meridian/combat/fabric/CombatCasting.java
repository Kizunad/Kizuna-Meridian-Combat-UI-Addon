package dev.kizuna.meridian.combat.fabric;

import dev.kizuna.meridian.combat.core.CombatProtocol;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.KnownTechniques;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HorizontalDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueHostDirectory;
import dev.kizuna.meridian.technique.server.TechniqueService;

import java.util.Comparator;
import java.util.UUID;

/** 只使用功法库公开契约补齐可信身份和精确版本。 */
public final class CombatCasting {
    private final TechniqueService service;
    private final TechniqueHostDirectory hosts;

    public CombatCasting(TechniqueService service, TechniqueHostDirectory hosts) {
        // 不缓存身体；每次请求都从当前实体宿主读取。
        this.service = service;
        this.hosts = hosts;
    }

    public CastOutcome cast(UUID entityUuid, TechniquePress press) {
        // 客户端只能指定标识与方向，身体和施放 ID 均由服务器补齐。
        String bodyId = hosts.resolve(entityUuid).map(host -> host.inspect().body().id().value())
                .orElse(entityUuid.toString());
        TechniqueReference technique = reference(entityUuid, press.getTechniqueId());
        HorizontalDirection direction = direction(press);
        CastRequest request = CastRequest.newBuilder()
                .setBodyId(bodyId)
                .setTechnique(technique)
                .setCastId(UUID.randomUUID().toString())
                .setDirection(direction)
                .build();
        return service.cast(entityUuid, request);
    }

    public KnownTechniques known(UUID entityUuid) {
        // 投影只读同一次宿主查询，不执行预施放或修改身体。
        var host = hosts.resolve(entityUuid);
        if (host.isEmpty()) {
            return KnownTechniques.getDefaultInstance();
        }
        var snapshot = CombatKnownProjection.snapshot(service.registry(), host.get().inspect().body());
        CombatProtocol.decodeKnown(snapshot.toByteArray()); // 校验整体大小与字段，越界时拒绝发布。
        return snapshot;
    }

    private TechniqueReference reference(UUID entityUuid, String id) {
        // 多版本共存时选目录中仍存在的最高已学版本，不能把旧知识升级为新权限。
        var registry = service.registry();
        var learned = service.known(entityUuid).stream()
                .map(TechniqueService.KnownEntry::technique)
                .filter(reference -> reference.getId().equals(id))
                .filter(reference -> registry.find(reference).isPresent())
                .max(Comparator.comparingInt(TechniqueReference::getVersion));
        if (learned.isPresent()) {
            return learned.get();
        }
        int version = 1;
        var latest = registry.findLatest(id);
        if (latest.isPresent()) {
            version = latest.get().getVersion();
        }
        return TechniqueReference.newBuilder()
                .setId(id)
                .setVersion(version)
                .build();
    }

    private static HorizontalDirection direction(TechniquePress press) {
        // 任意有限幅度统一归一化，输入不能把方向变成客户端指定的速度。
        double x = press.getMoveX();
        double z = press.getMoveZ();
        double length = Math.hypot(x, z);
        if (length == 0) {
            return HorizontalDirection.getDefaultInstance();
        }
        return HorizontalDirection.newBuilder()
                .setX(x / length)
                .setZ(z / length)
                .build();
    }

}
