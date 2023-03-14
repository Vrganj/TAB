package me.neznamy.tab.platforms.sponge8.features.unlimitedtags;

import lombok.RequiredArgsConstructor;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabConstants;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.chat.IChatBaseComponent;
import me.neznamy.tab.platforms.sponge8.Sponge8TAB;
import me.neznamy.tab.platforms.sponge8.SpongePacketBuilder;
import me.neznamy.tab.platforms.sponge8.nms.NMSStorage;
import me.neznamy.tab.shared.backend.features.unlimitedtags.BackendNameTagX;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.value.Value;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.Location;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class SpongeNameTagX extends BackendNameTagX {

    private final NMSStorage nms = NMSStorage.getInstance();

    /** Event listener */
    private final EventListener eventListener = new EventListener(this);

    private final Sponge8TAB plugin;

    @Override
    public void load() {
        Sponge.eventManager().registerListeners(plugin.getContainer(), eventListener);
        super.load();
    }

    @Override
    public boolean onPacketReceive(TabPlayer sender, Object packet) throws ReflectiveOperationException {
        if (sender.getVersion().getMinorVersion() == 8 && packet instanceof ServerboundInteractPacket) {
            int entityId = nms.ServerboundInteractPacket_ENTITYID.getInt(packet);
            TabPlayer attacked = null;
            for (TabPlayer all : TabAPI.getInstance().getOnlinePlayers()) {
                if (all.isLoaded() && getArmorStandManager(all).hasArmorStandWithID(entityId)) {
                    attacked = all;
                    break;
                }
            }
            if (attacked != null && attacked != sender) {
                nms.ServerboundInteractPacket_ENTITYID.set(packet, getEntityId(attacked));
            }
        }
        return false;
    }

    @Override
    public void onPacketSend(TabPlayer receiver, Object packet) throws ReflectiveOperationException {
        if (receiver.getVersion().getMinorVersion() < 8) return;
        if (!receiver.isLoaded() || isDisabledPlayer(receiver) || getDisabledUnlimitedPlayers().contains(receiver)) return;
        if (packet instanceof ClientboundMoveEntityPacket && !(packet instanceof ClientboundMoveEntityPacket.Rot)) {
             packetListener.onEntityMove(receiver, nms.ClientboundMoveEntityPacket_ENTITYID.getInt(packet));
        } else if (packet instanceof ClientboundTeleportEntityPacket) {
            packetListener.onEntityMove(receiver, nms.ClientboundTeleportEntityPacket_ENTITYID.getInt(packet));
        } else if (packet instanceof ClientboundAddPlayerPacket) {
            packetListener.onEntitySpawn(receiver, nms.ClientboundAddPlayerPacket_ENTITYID.getInt(packet));
        } else if (packet instanceof ClientboundRemoveEntitiesPacket) {
            packetListener.onEntityDestroy(receiver, (int[]) nms.ClientboundRemoveEntitiesPacket_ENTITIES.get(packet));
        }
    }

    @Override
    public double getDistance(TabPlayer player1, TabPlayer player2) {
        Location<?, ?> loc1 = ((Player)player1.getPlayer()).location();
        Location<?, ?> loc2 = ((Player)player2.getPlayer()).location();
        return Math.sqrt(Math.pow(loc1.x()-loc2.x(), 2) + Math.pow(loc1.z()-loc2.z(), 2));
    }

    @Override
    public boolean areInSameWorld(TabPlayer player1, TabPlayer player2) {
        return ((Player)player1.getPlayer()).world() == ((Player)player2.getPlayer()).world();
    }

    @Override
    public boolean canSee(TabPlayer viewer, TabPlayer target) {
        return ((ServerPlayer)viewer.getPlayer()).canSee(((ServerPlayer)target.getPlayer()));
    }

    @Override
    public void unregisterListener() {
        Sponge.eventManager().unregisterListeners(eventListener);
    }

    @Override
    public List<Integer> getPassengers(Object entity) {
        return ((Entity)entity).passengers().get().stream().map(this::getEntityId).collect(Collectors.toList());
    }

    @Override
    public void registerVehiclePlaceholder() {
        TabAPI.getInstance().getPlaceholderManager().registerPlayerPlaceholder(TabConstants.Placeholder.VEHICLE, 100, this::getVehicle);
    }

    @Override
    public Object getVehicle(TabPlayer player) {
        Value.Mutable<Entity> vehicle = ((ServerPlayer)player.getPlayer()).vehicle().orElse(null);
        return vehicle == null ? null : vehicle.get();
    }

    @Override
    public int getEntityId(Object entity) {
        return ((net.minecraft.world.entity.Entity) entity).getId();
    }

    @Override
    public String getEntityType(Object entity) {
        return Registry.ENTITY_TYPE.getKey(((net.minecraft.world.entity.Entity) entity).getType()).getPath();
    }

    @Override
    public boolean isSneaking(TabPlayer player) {
        return ((ServerPlayer) player.getPlayer()).sneaking().get();
    }

    @Override
    public boolean isSwimming(TabPlayer player) {
        return ((net.minecraft.server.level.ServerPlayer) player.getPlayer()).isSwimming();
    }

    @Override
    public boolean isGliding(TabPlayer player) {
        return ((Player)player).elytraFlying().get();
    }

    @Override
    public boolean isSleeping(TabPlayer player) {
        return ((Player)player.getPlayer()).sleeping().get();
    }

    @Override
    public Object getArmorStandType() {
        return 1;
    }

    @Override
    public double getX(TabPlayer player) {
        return ((ServerPlayer)player.getPlayer()).location().x();
    }

    @Override
    public double getY(Object entity) {
        return ((Entity)entity).location().y();
    }

    @Override
    public double getZ(TabPlayer player) {
        return ((ServerPlayer)player.getPlayer()).location().z();
    }

    @Override
    public Object createDataWatcher(TabPlayer viewer, byte flags, String displayName, boolean nameVisible, boolean markerFlag) {
        SynchedEntityData dataWatcher = new SynchedEntityData(null);
        dataWatcher.define(new EntityDataAccessor<>(0, EntityDataSerializers.BYTE), flags);
        dataWatcher.define(new EntityDataAccessor<>(2, EntityDataSerializers.OPTIONAL_COMPONENT),
                Optional.ofNullable(SpongePacketBuilder.getComponentCache().get(
                        IChatBaseComponent.optimizedComponent(displayName), viewer.getVersion())));
        dataWatcher.define(new EntityDataAccessor<>(3, EntityDataSerializers.BOOLEAN), nameVisible);
        if (markerFlag) dataWatcher.define(new EntityDataAccessor<>(14, EntityDataSerializers.BYTE), (byte)16);
        return dataWatcher;
    }
}
