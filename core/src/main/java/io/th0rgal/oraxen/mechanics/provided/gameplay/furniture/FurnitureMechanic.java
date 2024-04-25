package io.th0rgal.oraxen.mechanics.provided.gameplay.furniture;

import com.comphenix.protocol.wrappers.BlockPosition;
import com.jeff_media.morepersistentdatatypes.DataType;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.compatibilities.provided.blocklocker.BlockLockerMechanic;
import io.th0rgal.oraxen.mechanics.Mechanic;
import io.th0rgal.oraxen.mechanics.MechanicFactory;
import io.th0rgal.oraxen.mechanics.provided.gameplay.furniture.evolution.EvolvingFurniture;
import io.th0rgal.oraxen.mechanics.provided.gameplay.furniture.hitbox.FurnitureHitbox;
import io.th0rgal.oraxen.mechanics.provided.gameplay.furniture.jukebox.JukeboxBlock;
import io.th0rgal.oraxen.mechanics.provided.gameplay.furniture.seats.FurnitureSeat;
import io.th0rgal.oraxen.mechanics.provided.gameplay.light.LightMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.limitedplacing.LimitedPlacing;
import io.th0rgal.oraxen.mechanics.provided.gameplay.storage.StorageMechanic;
import io.th0rgal.oraxen.utils.*;
import io.th0rgal.oraxen.utils.actions.ClickAction;
import io.th0rgal.oraxen.utils.blocksounds.BlockSounds;
import io.th0rgal.oraxen.utils.drops.Drop;
import io.th0rgal.oraxen.utils.logs.Logs;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class FurnitureMechanic extends Mechanic {

    public static final NamespacedKey FURNITURE_KEY = new NamespacedKey(OraxenPlugin.get(), "furniture");
    public static final NamespacedKey MODELENGINE_KEY = new NamespacedKey(OraxenPlugin.get(), "modelengine");
    public static final NamespacedKey EVOLUTION_KEY = new NamespacedKey(OraxenPlugin.get(), "evolution");

    private final int hardness;
    private final LimitedPlacing limitedPlacing;
    private final StorageMechanic storage;
    private final BlockSounds blockSounds;
    private final JukeboxBlock jukebox;
    public final boolean farmlandRequired;
    private final Drop drop;
    private final EvolvingFurniture evolvingFurniture;
    private final LightMechanic light;
    private final String modelEngineID;
    private final List<FurnitureSeat> seats = new ArrayList<>();
    private final List<ClickAction> clickActions;
    private FurnitureType furnitureType;
    private final DisplayEntityProperties displayEntityProperties;
    private final boolean isRotatable;
    private final BlockLockerMechanic blockLocker;
    private final RestrictedRotation restrictedRotation;
    @NotNull
    private final FurnitureHitbox hitbox;

    public enum RestrictedRotation {
        NONE, STRICT, VERY_STRICT;

        public static RestrictedRotation fromString(String string) {
            return Arrays.stream(RestrictedRotation.values())
                    .filter(e -> e.name().equals(string))
                    .findFirst()
                    .orElseGet(() -> {
                        Logs.logError("Invalid restricted rotation: " + string);
                        Logs.logError("Allowed ones are: " + Arrays.toString(RestrictedRotation.values()));
                        Logs.logWarning("Setting to STRICT");
                        return STRICT;
                    });
        }
    }

    @SuppressWarnings("unchecked")
    public FurnitureMechanic(MechanicFactory mechanicFactory, ConfigurationSection section) {
        super(mechanicFactory, section, itemBuilder -> itemBuilder.setCustomTag(FURNITURE_KEY, PersistentDataType.BYTE, (byte) 1));

        hardness = section.getInt("hardness", 1);
        modelEngineID = section.getString("modelengine_id", null);
        farmlandRequired = section.getBoolean("farmland_required", false);
        light = new LightMechanic(section);
        restrictedRotation = RestrictedRotation.fromString(section.getString("restricted_rotation", "STRICT"));

        try {
            String defaultEntityType;
            if (OraxenPlugin.supportsDisplayEntities)
                defaultEntityType = Objects.requireNonNullElse(FurnitureFactory.defaultFurnitureType, FurnitureType.DISPLAY_ENTITY).name();
            else defaultEntityType = FurnitureType.ITEM_FRAME.name();
            furnitureType = FurnitureType.valueOf(section.getString("type", defaultEntityType));
            if (furnitureType == FurnitureType.DISPLAY_ENTITY && !OraxenPlugin.supportsDisplayEntities) {
                Logs.logError("Use of Display Entity on unsupported server version.");
                Logs.logError("This EntityType is only supported on 1.19.4 and above.");
                Logs.logWarning("Setting type to ITEM_FRAME for furniture: <i><gold>" + getItemID());
                furnitureType = FurnitureType.ITEM_FRAME;
            }
        } catch (IllegalArgumentException e) {
            Logs.logError("Use of illegal EntityType in furniture: <gold>" + getItemID());
            Logs.logWarning("Allowed ones are: <gold>" + Arrays.stream(FurnitureType.values()).map(Enum::name).toList());
            Logs.logWarning("Setting type to ITEM_FRAME for furniture: <gold>" + getItemID());
            furnitureType = FurnitureType.ITEM_FRAME;
        }

        section.set("type", furnitureType.name());

        ConfigurationSection displayProperties = section.getConfigurationSection("display_entity_properties");
        displayEntityProperties = OraxenPlugin.supportsDisplayEntities
                ? displayProperties != null
                ? new DisplayEntityProperties(displayProperties) : new DisplayEntityProperties()
                : null;

        ConfigurationSection hitboxSection = section.getConfigurationSection("hitbox");
        hitbox = hitboxSection != null ? new FurnitureHitbox(hitboxSection) : FurnitureHitbox.EMPTY;

        for (Object seatEntry : section.getList("seats", new ArrayList<>())) {
            FurnitureSeat seat = FurnitureSeat.getSeat(seatEntry);
            if (seat == null) continue;
            seats.add(seat);
        }

        ConfigurationSection evoSection = section.getConfigurationSection("evolution");
        evolvingFurniture = evoSection != null ? new EvolvingFurniture(getItemID(), evoSection) : null;
        if (evolvingFurniture != null) ((FurnitureFactory) getFactory()).registerEvolution();

        ConfigurationSection dropSection = section.getConfigurationSection("drop");
        drop = dropSection != null ? Drop.createDrop(FurnitureFactory.get().toolTypes, dropSection, getItemID()) : new Drop(new ArrayList<>(), false, false, getItemID());

        ConfigurationSection limitedPlacingSection = section.getConfigurationSection("limited_placing");
        limitedPlacing = limitedPlacingSection != null ? new LimitedPlacing(limitedPlacingSection) : null;

        ConfigurationSection storageSection = section.getConfigurationSection("storage");
        storage = storageSection != null ? new StorageMechanic(storageSection) : null;

        ConfigurationSection blockSoundsSection = section.getConfigurationSection("block_sounds");
        blockSounds = blockSoundsSection != null ? new BlockSounds(blockSoundsSection) : null;

        ConfigurationSection jukeboxSection = section.getConfigurationSection("jukebox");
        jukebox = jukeboxSection != null ? new JukeboxBlock(jukeboxSection) : null;

        clickActions = ClickAction.parseList(section);

        if (section.getBoolean("rotatable", false)) {
            if (hitbox.barrierHitboxes().stream().anyMatch(b -> b.getX() != 0 || b.getZ() != 0)) {
                Logs.logWarning("Furniture <gold>" + getItemID() + " </gold>has barriers with non-zero X or Z coordinates.");
                Logs.logWarning("Furniture rotation will be disabled for this furniture.");
                isRotatable = false;
            } else isRotatable = true;
        } else isRotatable = false;

        ConfigurationSection blockLockerSection = section.getConfigurationSection("blocklocker");
        blockLocker = blockLockerSection != null ? new BlockLockerMechanic(blockLockerSection) : null;
    }

    public boolean isModelEngine() {
        return modelEngineID != null;
    }

    public String getModelEngineID() {
        return modelEngineID;
    }

    public static ArmorStand getSeat(Location location) {
        Location seatLoc = BlockHelpers.toCenterBlockLocation(location);
        if (location.getWorld() == null) return null;
        for (Entity entity : location.getWorld().getNearbyEntities(seatLoc, 0.1, 4, 0.1)) {
            if (entity instanceof ArmorStand seat
                    && entity.getLocation().getX() == seatLoc.getX()
                    && entity.getLocation().getZ() == seatLoc.getZ()
                    && entity.getPersistentDataContainer().has(FURNITURE_KEY, DataType.STRING)) {
                return seat;
            }
        }

        return null;
    }

    public boolean hasHardness() {
        return hardness != -1;
    }

    public int getHardness() {
        return hardness;
    }

    public boolean hasLimitedPlacing() {
        return limitedPlacing != null;
    }

    public LimitedPlacing limitedPlacing() {
        return limitedPlacing;
    }

    public boolean isStorage() {
        return storage != null;
    }

    public StorageMechanic storage() {
        return storage;
    }

    public boolean hasBlockSounds() {
        return blockSounds != null;
    }

    public BlockSounds blockSounds() {
        return blockSounds;
    }

    public boolean isJukebox() {
        return jukebox != null;
    }

    public JukeboxBlock jukebox() {
        return jukebox;
    }

    public FurnitureHitbox hitbox() {
        return hitbox;
    }

    public boolean hasSeats() {
        return !seats.isEmpty();
    }

    public List<FurnitureSeat> seats() {
        return seats;
    }

    public Drop drop() {
        return drop;
    }

    public boolean hasEvolution() {
        return evolvingFurniture != null;
    }

    public EvolvingFurniture evolution() {
        return evolvingFurniture;
    }

    public boolean isRotatable() {
        return isRotatable;
    }

    public boolean isInteractable() {
        return isRotatable || hasSeats() || isStorage();
    }

    public Entity place(Location location) {
        return place(location, 0f, BlockFace.NORTH, true);
    }

    public Entity place(Location location, float yaw, BlockFace facing) {
        return place(location, yaw, facing, true);
    }

    public Entity place(Location location, float yaw, BlockFace facing, boolean checkSpace) {
        if (!location.isWorldLoaded()) return null;
        if (checkSpace && !this.hasEnoughSpace(location, yaw)) return null;
        assert location.getWorld() != null;

        ItemStack item = OraxenItems.getItemById(getItemID()).build();
        ItemUtils.editItemMeta(item, meta -> ItemUtils.displayName(meta, Component.empty()));
        item.setAmount(1);

        ArmorStand baseEntity = EntityUtils.spawnEntity(correctedSpawnLocation(location, facing), ArmorStand.class, a -> setBaseFurnitureData(a, yaw));
        if (baseEntity == null) return null;
        if (this.isModelEngine() && PluginUtils.isEnabled("ModelEngine")) {
            spawnModelEngineFurniture(baseEntity);
        }
        FurnitureSeat.spawnSeats(baseEntity, this);
        if (light.hasLightLevel()) light.createBlockLight(baseEntity.getLocation().getBlock());

        return baseEntity;
    }

    private Location correctedSpawnLocation(Location baseLocation, BlockFace facing) {
        Location correctedLocation = BlockHelpers.toCenterBlockLocation(baseLocation);
        boolean isWall = hasLimitedPlacing() && limitedPlacing.isWall();
        boolean isRoof = hasLimitedPlacing() && limitedPlacing.isRoof();
        boolean isFixed = hasDisplayEntityProperties() && displayEntityProperties.displayTransform() == ItemDisplay.ItemDisplayTransform.FIXED;
        if (furnitureType != FurnitureType.DISPLAY_ENTITY || !hasDisplayEntityProperties()) return correctedLocation;
        if (displayEntityProperties.displayTransform() != ItemDisplay.ItemDisplayTransform.NONE && !isWall && !isRoof)
            return correctedLocation;
        float scale = displayEntityProperties.hasScale() ? displayEntityProperties.scale().y() : 1;
        // Since roof-furniture need to be more or less flipped, we have to add 0.5 (0.49 or it is "inside" the block above) to the Y coordinate
        if (isFixed && isWall)
            correctedLocation.add(-facing.getModX() * (0.49 * scale), 0, -facing.getModZ() * (0.49 * scale));
        return correctedLocation.add(0, (0.5 * scale) + (isRoof ? isFixed ? 0.49 : -1 : 0), 0);
    }

    public void setBaseFurnitureData(ArmorStand baseEntity, float yaw) {
        baseEntity.setPersistent(true);
        baseEntity.setDisabledSlots(EquipmentSlot.values());
        baseEntity.setMarker(true);
        baseEntity.setVisible(false);
        baseEntity.setInvulnerable(true);
        baseEntity.setCanMove(false);
        baseEntity.setCanTick(false);
        baseEntity.setCanPickupItems(false);
        baseEntity.setRotation(yaw, 0f);
        baseEntity.setSilent(true);
        baseEntity.setCustomNameVisible(false);
        Component customName = OraxenItems.getItemById(this.getItemID()).displayName();
        if (customName == Component.empty()) customName = Component.text(getItemID());
        EntityUtils.customName(baseEntity, customName);

        PersistentDataContainer pdc = baseEntity.getPersistentDataContainer();
        pdc.set(FURNITURE_KEY, PersistentDataType.STRING, getItemID());
        if (hasEvolution()) pdc.set(EVOLUTION_KEY, PersistentDataType.INTEGER, 0);
        if (isStorage() && storage().getStorageType() == StorageMechanic.StorageType.STORAGE) {
            pdc.set(StorageMechanic.STORAGE_KEY, DataType.ITEM_STACK_ARRAY, new ItemStack[]{});
        }
    }

    private void spawnModelEngineFurniture(@NotNull Entity entity) {
        ModeledEntity modelEntity = ModelEngineAPI.getOrCreateModeledEntity(entity);
        ActiveModel activeModel = ModelEngineAPI.createActiveModel(getModelEngineID());
        ModelEngineUtils.addModel(modelEntity, activeModel, true);
        ModelEngineUtils.setRotationLock(modelEntity, false);
        modelEntity.setBaseEntityVisible(false);
    }

    public void removeBaseEntity(@NotNull Entity baseEntity) {
        if (light.hasLightLevel()) light.removeBlockLight(baseEntity.getLocation().getBlock());
        if (hasSeats()) removeFurnitureSeats(baseEntity);
        FurnitureFactory.instance.packetManager().removeFurnitureEntityPacket(baseEntity, this);
        FurnitureFactory.instance.packetManager().removeInteractionHitboxPacket(baseEntity, this);
        FurnitureFactory.instance.packetManager().removeBarrierHitboxPacket(baseEntity, this);

        if (!baseEntity.isDead()) baseEntity.remove();
    }

    private void removeFurnitureSeats(Entity baseEntity) {
        List<Entity> seats = baseEntity.getPersistentDataContainer()
                .getOrDefault(FurnitureSeat.SEAT_KEY, DataType.asList(DataType.UUID), new ArrayList<>())
                .stream().map(Bukkit::getEntity).filter(Objects::nonNull).filter(e -> e instanceof ArmorStand).toList();

        for (Entity seat : seats) {
            seat.getPassengers().forEach(seat::removePassenger);
            if (!seat.isDead()) seat.remove();
        }
    }

    public boolean hasEnoughSpace(Location rootLocation, float yaw) {
        return hitbox.hitboxLocations(rootLocation, yaw).stream().allMatch(l -> l.getBlock().isReplaceable());
    }

    public void runClickActions(final Player player) {
        for (final ClickAction action : clickActions) {
            if (action.canRun(player)) action.performActions(player);
        }
    }

    @Nullable
    public Entity baseEntity(Block block) {
        if (block == null) return null;
        BlockPosition blockPosition = new BlockPosition(block.getX(), block.getY(), block.getZ());
        return FurnitureFactory.instance.packetManager().baseEntityFromHitbox(blockPosition);
    }

    @Nullable
    public Entity baseEntity(Location location) {
        if (location == null) return null;
        BlockPosition blockPosition = new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return FurnitureFactory.instance.packetManager().baseEntityFromHitbox(blockPosition);
    }

    @Nullable
    Entity baseEntity(BlockPosition blockPosition) {
        return FurnitureFactory.instance.packetManager().baseEntityFromHitbox(blockPosition);
    }

    @Nullable
    Entity baseEntity(int interactionId) {
        return FurnitureFactory.instance.packetManager().baseEntityFromHitbox(interactionId);
    }

    public FurnitureType furnitureType() {
        return furnitureType;
    }

    public FurnitureType furnitureType(Player player) {
        if (furnitureType != FurnitureType.DISPLAY_ENTITY) return furnitureType;
        if (VersionUtil.atOrAbove(player, 762)) return furnitureType;
        if (FurnitureFactory.defaultFurnitureType != FurnitureType.DISPLAY_ENTITY) return FurnitureFactory.defaultFurnitureType;

        return FurnitureType.ITEM_FRAME;
    }

    public boolean hasDisplayEntityProperties() {
        return displayEntityProperties != null;
    }

    public DisplayEntityProperties displayEntityProperties() {
        return displayEntityProperties;
    }

    public RestrictedRotation restrictedRotation() {
        return restrictedRotation;
    }

    public void rotateFurniture(Entity baseEntity) {
        float yaw = FurnitureHelpers.furnitureYaw(baseEntity);
        yaw = FurnitureHelpers.rotationToYaw(rotateClockwise(FurnitureHelpers.yawToRotation(yaw)));
        FurnitureHelpers.furnitureYaw(baseEntity, yaw);

        hitbox.handleHitboxes(baseEntity, this);
    }

    private Rotation rotateClockwise(Rotation rotation) {
        int offset = restrictedRotation == RestrictedRotation.VERY_STRICT ? 2 : 1;
        return Rotation.values()[(rotation.ordinal() + offset) & 0x7];
    }

    public BlockLockerMechanic blocklocker() {
        return blockLocker;
    }

    public boolean hasLight() {
        return light.hasLightLevel();
    }

    public LightMechanic light() {
        return light;
    }
}
