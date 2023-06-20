
/*
 * Copyright (C) 2022 legoatoom
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.lilypuree.connectiblechains.entity;

import com.lilypuree.connectiblechains.ConnectibleChains;
import com.lilypuree.connectiblechains.chain.ChainLink;
import com.lilypuree.connectiblechains.datafixer.ChainKnotFixer;
import com.lilypuree.connectiblechains.network.ModPacketHandler;
import com.lilypuree.connectiblechains.network.S2CKnotChangeTypePacket;
import com.lilypuree.connectiblechains.tag.CommonTags;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The ChainKnotEntity is the main entity of this mod.
 * It has connections between others of its kind, and is a combination of {@link net.minecraft.world.entity.Mob}
 * and {@link net.minecraft.world.entity.decoration.LeashFenceKnotEntity}.
 *
 * @author legoatoom, Qendolin
 */
public class ChainKnotEntity extends HangingEntity implements IEntityAdditionalSpawnData, ChainLinkEntity {
    /**
     * The distance when it is visible.
     */
    public static final double VISIBLE_RANGE = 2048.0D;

    /**
     * Ticks where the knot can live without any links.
     * This is important for 2 reasons: When the world loads, a 'secondary' knot might load before it's 'primary'
     * In which case the knot would remove itself as it has no links and when the 'primary' loads it fails to create
     * a link to this as this is already removed. The second use is for /summon for basically the same reasons.
     */
    private static final byte GRACE_PERIOD = 100;

    /**
     * All links that involve this knot (secondary and primary)
     */
    private final ObjectList<ChainLink> links = new ObjectArrayList<>();

    /**
     * Links where the 'secondary' might not exist yet. Will be cleared after the grace period.
     */
    private final ObjectList<Tag> incompleteLinks = new ObjectArrayList<>();
    public final static String SOURCE_ITEM_KEY = "SourceItem";

    /**
     * Increments each tick, when it reached 100 it resets and checks {@link #canStayAttached()}.
     */
    private int obstructionCheckTimer = 0;

    /**
     * The chain type, used for rendering
     */
    private Item chainItemSource = Items.CHAIN;

    /**
     * Remaining grace ticks, will be set to 0 when the last incomplete link is removed.
     */
    private byte graceTicks = GRACE_PERIOD;

    /**
     * What block the knot is attached to. (client side only)
     */
    private BlockState attachTarget;

    protected ChainKnotEntity(EntityType<? extends HangingEntity> entityType, Level level) {
        super(entityType, level);
    }

    public ChainKnotEntity(Level world, BlockPos pos, Item source) {
        super(ModEntityTypes.CHAIN_KNOT.get(), world, pos);
        this.setPos((double) pos.getX() + 0.5D, (double) pos.getY() + 0.5D, (double) pos.getZ() + 0.5D);
        this.chainItemSource = source;
    }

    /**
     * Set the {@link #pos}.
     *
     * @see #recalculateBoundingBox()
     */
    @Override
    public void setPos(double x, double y, double z) {
        super.setPos((double) Mth.floor(x) + 0.5D, (double) Mth.floor(y) + 0.5D, (double) Mth.floor(z) + 0.5D);
    }

    public Item getChainItemSource() {
        return chainItemSource;
    }

    public void setChainItemSource(Item chainItemSource) {
        this.chainItemSource = chainItemSource;
    }

    public void setGraceTicks(byte graceTicks) {
        this.graceTicks = graceTicks;
    }

    @Override
    protected void setDirection(Direction pFacingDirection) {
        // AbstractDecorationEntity.facing should not be used
    }

    /**
     * Update the position of this chain to the position of the block this is attached too.
     */
    @Override
    protected void recalculateBoundingBox() {
        this.setPosRaw((double) this.pos.getX() + 0.5D, (double) this.pos.getY() + 0.5D, (double) this.pos.getZ() + 0.5D);
        double w = this.getType().getWidth() / 2.0;
        double h = this.getType().getHeight();
        this.setBoundingBox(new AABB(this.getX() - w, this.getY(), this.getZ() - w, this.getX() + w, this.getY() + h, this.getZ() + w));
    }

    /**
     * On the server it:
     * <ol>
     * <li>Checks if its in the void and deletes itself.</li>
     * <li>Tries to convert incomplete links</li>
     * <li>Updates the chains, see {@link #updateLinks()}</li>
     * <li>Removes any dead links, and, when outside the grace period, itself if none are left.</li>
     * </ol>
     */
    @Override
    public void tick() {
        if (this.level.isClientSide) {
            // All other logic in handled on the server. The client only knows enough to render the entity.
            links.removeIf(ChainLink::isDead);
            attachTarget = level.getBlockState(pos);
            return;
        }
        checkOutOfWorld();

        boolean anyConverted = convertIncompleteLinks();
        updateLinks();
        removeDeadLinks();

        if (graceTicks < 0 || (anyConverted && incompleteLinks.isEmpty())) {
            graceTicks = 0;
        } else if (graceTicks > 0) {
            graceTicks--;
        }
    }

    /**
     * Will try to convert any {@link #incompleteLinks} using {@link #deserializeChainTag(Tag)}.
     *
     * @return true if any were converted
     */
    private boolean convertIncompleteLinks() {
        if (!incompleteLinks.isEmpty()) {
            return incompleteLinks.removeIf(this::deserializeChainTag);
        }
        return false;
    }

    /**
     * Will break all connections that are larger than the {@link #getMaxRange()},
     * when this knot is dead, or can't stay attached.
     */
    private void updateLinks() {
        double squaredMaxRange = getMaxRange() * getMaxRange();
        for (ChainLink link : links) {
            if (link.isDead()) continue;

            if (!isAlive()) {
                link.destroy(true);
            } else if (link.primary == this && link.getSquaredDistance() > squaredMaxRange) {
                // no need to check the distance on both ends
                link.destroy(true);
            }
        }

        if (obstructionCheckTimer++ == 100) {
            obstructionCheckTimer = 0;
            if (!canStayAttached()) {
                destroyLinks(true);
            }
        }
    }

    /**
     * Removes any dead links and plays a break sound if any were removed.
     * Removes itself when no {@link #links} or {@link #incompleteLinks} are left, and it's outside the grace period.
     */
    private void removeDeadLinks() {
        boolean playBreakSound = false;
        for (ChainLink link : links) {
            if (link.needsBeDestroyed()) link.destroy(true);
            if (link.isDead() && !link.removeSilently) playBreakSound = true;
        }
        if (playBreakSound) dropItem(null);

        links.removeIf(ChainLink::isDead);
        if (links.isEmpty() && incompleteLinks.isEmpty() && graceTicks <= 0) {
            remove(RemovalReason.DISCARDED);
            // No break sound
        }
    }

    /**
     * This method tries to connect to the secondary that is in the {@link #incompleteLinks}.
     * If they do not exist yet, we try again later. If they do, make a connection and remove it from the tag.
     * <br>
     * When the grace period is over, we remove the tag from the {@link #incompleteLinks} and drop an item
     * meaning that we cannot find the connection anymore, and we assume that it will not be loaded in the future.
     *
     * @param element the tag that contains a single connection.
     * @return true if the tag has been used
     * @see #updateLinks()
     */
    private boolean deserializeChainTag(Tag element) {
        if (element == null || level.isClientSide) {
            return true;
        }

        assert element instanceof CompoundTag;
        CompoundTag tag = (CompoundTag) element;

        Item source = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(tag.getString(SOURCE_ITEM_KEY)));

        if (tag.contains("UUID")) {
            UUID uuid = tag.getUUID("UUID");
            Entity entity = ((ServerLevel) level).getEntity(uuid);
            if (entity != null) {
                ChainLink.create(this, entity, source);
                return true;
            }
        } else if (tag.contains("RelX") || tag.contains("RelY") || tag.contains("RelZ")) {
            BlockPos blockPos = new BlockPos(tag.getInt("RelX"), tag.getInt("RelY"), tag.getInt("RelZ"));
            // Adjust position to be relative to our facing direction
            blockPos = getBlockPosAsFacingRelative(blockPos, Direction.fromYRot(this.getYRot()));
            ChainKnotEntity entity = ChainKnotEntity.getKnotAt(level, blockPos.offset(pos));
            if (entity != null) {
                ChainLink.create(this, entity, source);
                return true;
            }
        } else {
            ConnectibleChains.LOGGER.warn("Chain knot NBT is missing UUID or relative position.");
        }

        // At the start the server and client need to tell each other the info.
        // So we need to check if the object is old enough for these things to exist before we delete them.
        if (graceTicks <= 0) {
            spawnAtLocation(source);
            dropItem(null);
            return true;
        }

        return false;
    }


    /**
     * The max range of the chain.
     */
    public static double getMaxRange() {
        return ConnectibleChains.runtimeConfig.getMaxChainRange();
    }

    /**
     * Simple checker to see if the block is connected to a fence or a wall.
     *
     * @return boolean - if it can stay attached.
     */
    public boolean canStayAttached() {
        BlockState block = this.level.getBlockState(this.pos);
        return canAttachTo(block);
    }

    /**
     * Destroys all links and sets the grace ticks to 0
     *
     * @param mayDrop true when the links should drop
     */
    @Override
    public void destroyLinks(boolean mayDrop) {
        for (ChainLink link : links) {
            link.destroy(mayDrop);
        }
        graceTicks = 0;
    }

    @Override
    public void dropItem(Entity entity) {
        this.playSound(SoundEvents.CHAIN_BREAK, 1.0F, 1.0F);
    }

    /**
     * To support structure blocks which can rotate structures we need to treat the relative secondary position in the
     * NBT as relative to our facing direction.
     *
     * @param relPos The relative position when the knot would be facing the +Z direction (0 deg).
     * @param facing The target direction
     * @return The yaw's equivalent block rotation.
     */
    private BlockPos getBlockPosAsFacingRelative(BlockPos relPos, Direction facing) {
        Rotation rotation = Rotation.values()[facing.get2DDataValue()];
        return relPos.rotate(rotation);
    }

    /**
     * Searches for a knot at {@code pos} and returns it.
     *
     * @param world The world to search in.
     * @param pos   The position to search at.
     * @return {@link ChainKnotEntity} or null when none exists at {@code pos}.
     */
    @org.jetbrains.annotations.Nullable
    public static ChainKnotEntity getKnotAt(Level world, BlockPos pos) {
        List<ChainKnotEntity> results = world.getEntitiesOfClass(ChainKnotEntity.class,
                AABB.ofSize(Vec3.atLowerCornerOf(pos), 2, 2, 2));

        for (ChainKnotEntity current : results) {
            if (current.getPos().equals(pos)) {
                return current;
            }
        }

        return null;
    }

    /**
     * Is this block acceptable to connect too?
     *
     * @param block the block in question.
     * @return boolean if is allowed or not.
     */
    public static boolean canAttachTo(BlockState block) {
        return block != null && block.is(BlockTags.WALLS) || block.is(BlockTags.FENCES);
    }

    /**
     * Mirrors the incomplete links, otherwise {@link #getBlockPosAsFacingRelative(BlockPos, Direction)} won't work.
     */
    @Override
    public float mirror(Mirror mirror) {
        if (mirror != Mirror.NONE) {
            // Mirror the X axis, I am not sure why
            for (Tag element : incompleteLinks) {
                if (element instanceof CompoundTag link) {
                    if (link.contains("RelX")) {
                        link.putInt("RelX", -link.getInt("RelX"));
                    }
                }
            }
        }

        // Opposite of Entity.applyMirror, again I am not sure why, but it works
        float yaw = Mth.wrapDegrees(this.getYRot());
        return switch (mirror) {
            case LEFT_RIGHT -> 180 - yaw;
            case FRONT_BACK -> -yaw;
            default -> yaw;
        };
    }

    /**
     * Calls {@link #hurt(DamageSource, float)} when attacked by a player. Plays a hit sound otherwise. <br/>
     * It is used by {@link Player#attack(Entity)} where a true return value indicates
     * that this entity handled the attack and no further actions should be made.
     *
     * @param attacker The source of the attack.
     * @return true
     */
    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        if (attacker instanceof Player playerEntity) {
            this.hurt(DamageSource.playerAttack(playerEntity), 0.0F);
        } else {
            playSound(SoundEvents.CHAIN_HIT, 0.5F, 1.0F);
        }
        return true;
    }

    /**
     * @return true when damage was effective
     * @see ChainKnotEntity#onDamageFrom(Entity, DamageSource)
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        InteractionResult result = ChainLinkEntity.onDamageFrom(this, source);

        if (result.consumesAction()) {
            destroyLinks(result == InteractionResult.SUCCESS);
            return true;
        }
        return false;
    }

    /**
     * Stores the {@link #chainItemSource chain type} and all primary links
     * and old, incomplete links inside {@code root}
     *
     * @param root the tag to write info in.
     */
    @Override
    public void addAdditionalSaveData(CompoundTag root) {
        ChainKnotFixer.INSTANCE.addVersionTag(root);
        root.putString(SOURCE_ITEM_KEY, ForgeRegistries.ITEMS.getKey(chainItemSource).toString());
        ListTag linksTag = new ListTag();

        // Write complete links
        for (ChainLink link : links) {
            if (link.isDead()) continue;
            if (link.primary != this) continue;
            Entity secondary = link.secondary;
            CompoundTag compoundTag = new CompoundTag();
            compoundTag.putString(SOURCE_ITEM_KEY, ForgeRegistries.ITEMS.getKey(link.sourceItem).toString());
            if (secondary instanceof Player) {
                UUID uuid = secondary.getUUID();
                compoundTag.putUUID("UUID", uuid);
            } else if (secondary instanceof HangingEntity) {
                BlockPos srcPos = this.pos;
                BlockPos dstPos = ((HangingEntity) secondary).getPos();
                BlockPos relPos = dstPos.subtract(srcPos);
                // Inverse rotation to store the position as 'facing' agnostic
                Direction inverseFacing = Direction.fromYRot(Direction.SOUTH.toYRot() - getYRot());
                relPos = getBlockPosAsFacingRelative(relPos, inverseFacing);
                compoundTag.putInt("RelX", relPos.getX());
                compoundTag.putInt("RelY", relPos.getY());
                compoundTag.putInt("RelZ", relPos.getZ());
            }
            linksTag.add(compoundTag);
        }

        // Write old, incomplete links
        linksTag.addAll(incompleteLinks);

        if (!linksTag.isEmpty()) {
            root.put("Chains", linksTag);
        }
    }

    /**
     * Read all the data from {@link #addAdditionalSaveData(CompoundTag)}
     * and stores the links in {@link #incompleteLinks}.
     *
     * @param root the tag to read from.
     */
    @Override
    public void readAdditionalSaveData(CompoundTag root) {
        if (root.contains("Chains")) {
            incompleteLinks.addAll(root.getList("Chains", Tag.TAG_COMPOUND));
        }
        chainItemSource = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(root.getString(SOURCE_ITEM_KEY)));
    }

    @Override
    public int getWidth() {
        return 9;
    }

    @Override
    public int getHeight() {
        return 9;
    }

    /**
     * Checks if the {@code distance} is within the {@link #VISIBLE_RANGE visible range}.
     *
     * @param distance the camera distance from the knot.
     * @return true when it is in range.
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < VISIBLE_RANGE;
    }

    @Override
    public Vec3 getLeashOffset() {
        return new Vec3(0, 4.5 / 16f, 0);
    }

    @Override
    public Vec3 getRopeHoldPosition(float pPartialTicks) {
        return this.getPosition(pPartialTicks).add(0.0D, 4.5 / 16f, 0.0D);
    }

    @Override
    protected float getEyeHeight(Pose pPose, EntityDimensions pSize) {
        return 4.5f / 16f;
    }

    /**
     * Interaction (attack or use) of a player and this entity.
     * On the server it will:
     * <ol>
     * <li>Try to move existing link from player to this.</li>
     * <li>Try to cancel chain links (when clicking a knot that already has a connection to {@code player}).</li>
     * <li>Try to create a new connection.</li>
     * <li>Try to destroy the knot with the item in the players hand.</li>
     * </ol>
     *
     * @param player The player that interacted.
     * @param hand   The hand that interacted.
     * @return {@link InteractionResult#SUCCESS} or {@link InteractionResult#CONSUME} when the interaction was successful.
     * @see #tryAttachHeldChains(Player)
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack handStack = player.getItemInHand(hand);
        if (level.isClientSide) {
            if (CommonTags.isChain(handStack)) {
                return InteractionResult.SUCCESS;
            }

            if (ChainLinkEntity.canDestroyWith(handStack)) {
                return InteractionResult.SUCCESS;
            }

            return InteractionResult.PASS;
        }

        // 1. Try to move existing link from player to this.
        boolean madeConnection = tryAttachHeldChains(player);
        if (madeConnection) {
            playPlacementSound();
            return InteractionResult.CONSUME;
        }

        // 2. Try to cancel chain links (when clicking same knot twice)
        boolean broke = false;
        for (ChainLink link : links) {
            if (link.secondary == player) {
                broke = true;
                link.destroy(true);
            }
        }
        if (broke) {
            return InteractionResult.CONSUME;
        }

        // 3. Try to create a new connection
        if (CommonTags.isChain(handStack)) {
            // Interacted with a valid chain item, create a new link
            playPlacementSound();
            ChainLink.create(this, player, handStack.getItem());
            if (!player.isCreative()) {
                player.getItemInHand(hand).shrink(1);
            }
            // Allow changing the chainType of the knot
            updateChainType(handStack.getItem());

            return InteractionResult.CONSUME;
        }

        // 4. Interacted with anything else, check for shears
        if (ChainLinkEntity.canDestroyWith(handStack)) {
            destroyLinks(!player.isCreative());
            graceTicks = 0;
            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }

    /**
     * Destroys all chains held by {@code player} that are in range and creates new links to itself.
     *
     * @param player the player wo tries to make a connection.
     * @return true if it has made a connection.
     */
    public boolean tryAttachHeldChains(Player player) {
        boolean hasMadeConnection = false;
        List<ChainLink> attachableLinks = getHeldChainsInRange(player, getPos());
        for (ChainLink link : attachableLinks) {
            // Prevent connections with self
            if (link.primary == this) continue;

            // Move that link to this knot
            ChainLink newLink = ChainLink.create(link.primary, this, link.sourceItem);

            // Check if the link does not already exist
            if (newLink != null) {
                link.destroy(false);
                link.removeSilently = true;
                hasMadeConnection = true;
            }
        }
        return hasMadeConnection;
    }

    @Override
    public void playPlacementSound() {
        this.playSound(SoundEvents.CHAIN_PLACE, 1.0F, 1.0F);
    }

    /**
     * Sets the chain type and sends a packet to the client.
     *
     * @param sourceItem The new chain type.
     */
    public void updateChainType(Item sourceItem) {
        this.chainItemSource = sourceItem;

        if (!level.isClientSide) {
            S2CKnotChangeTypePacket packet = new S2CKnotChangeTypePacket(getId(), ForgeRegistries.ITEMS.getKey(sourceItem));
            BlockPos pos = blockPosition();
            ModPacketHandler.INSTANCE.send(PacketDistributor.NEAR
                            .with(PacketDistributor.TargetPoint.p(pos.getX(), pos.getY(), pos.getZ(), ChainKnotEntity.VISIBLE_RANGE, level.dimension())),
                    packet);
        }
    }

    /**
     * Searches for other {@link ChainKnotEntity ChainKnotEntities} that are in range of {@code target} and
     * have a link to {@code player}.
     *
     * @param player the player wo tries to make a connection.
     * @param target center of the range
     * @return a list of all held chains that are in range of {@code target}
     */
    public static List<ChainLink> getHeldChainsInRange(Player player, BlockPos target) {
        AABB searchBox = AABB.ofSize(Vec3.atLowerCornerOf(target), getMaxRange() * 2, getMaxRange() * 2, getMaxRange() * 2);
        List<ChainKnotEntity> otherKnots = player.level.getEntitiesOfClass(ChainKnotEntity.class, searchBox);

        List<ChainLink> attachableLinks = new ArrayList<>();

        for (ChainKnotEntity source : otherKnots) {
            for (ChainLink link : source.getLinks()) {
                if (link.secondary != player) continue;
                // We found a knot that is connected to the player.
                attachableLinks.add(link);
            }
        }
        return attachableLinks;
    }

    /**
     * @return all complete links that are associated with this knot.
     * @apiNote Operating on the list has potential for bugs as it does not include incomplete links.
     * For example {@link ChainLink#create(ChainKnotEntity, Entity, Item)} checks if the link already exists
     * using this list. Same goes for {@link #tryAttachHeldChains(Player)}
     * but at the end of the day it doesn't really matter.
     * When an incomplete link is not resolved within the first two ticks it is unlikely to ever complete.
     * And even if it completes it will be stopped either because the knot is dead or the duplicates check in {@code ChainLink}.
     */
    public List<ChainLink> getLinks() {
        return links;
    }


    @Override
    public Packet<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(ForgeRegistries.ITEMS.getKey(chainItemSource));
    }

    @Override
    public void readSpawnData(FriendlyByteBuf additionalData) {
        ResourceLocation chainTypeID = additionalData.readResourceLocation();
        this.setChainItemSource(ForgeRegistries.ITEMS.getValue(chainTypeID));
        this.setGraceTicks((byte) 0);
    }

    /**
     * Checks if the knot model of the knot entity should be rendered.
     * To determine if the knot entity including chains should be rendered use {@link #shouldRenderAtSqrDistance(double)}
     *
     * @return true if the knot is not attached to a wall.
     */
    public boolean shouldRenderKnot() {
        return attachTarget == null || !attachTarget.is(BlockTags.WALLS);
    }

    public void addLink(ChainLink link) {
        links.add(link);
    }

    @Override
    public ItemStack getPickedResult(HitResult target) {
        return new ItemStack(chainItemSource);
    }

}
