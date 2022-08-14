package com.lilypuree.connectiblechains.entity;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.Tags;

/**
 * ChainLinkEntity implements common functionality between {@link ChainCollisionEntity} and {@link ChainKnotEntity}.
 */
public interface ChainLinkEntity {


    /**
     * When a chain link entity is damaged by
     * <ul>
     * <li>A player with an item that has the tag c:shears or is minecraft:shears</li>
     * <li>An explosion</li>
     * </ul>
     * it destroys the link that it is part of.
     * Otherwise, it plays a hit sound.
     *
     * @param self   A {@link ChainCollisionEntity} or {@link ChainKnotEntity}.
     * @param source The source that was used to damage.
     * @return {@link InteractionResult#SUCCESS} when the link should be destroyed,
     * {@link InteractionResult#CONSUME} when the link should be destroyed but not drop.
     */
    static InteractionResult onDamageFrom(Entity self, DamageSource source) {
        if (self.isInvulnerableTo(source)) {
            return InteractionResult.FAIL;
        }
        if (self.level.isClientSide) {
            return InteractionResult.PASS;
        }

        if (source.isExplosion()) {
            return InteractionResult.SUCCESS;
        }
        if (source.getDirectEntity() instanceof Player player) {
            if (canDestroyWith(player.getMainHandItem())) {
                return InteractionResult.sidedSuccess(!player.isCreative());
            }
        }

        if (!source.isProjectile()) {
            // Projectiles such as arrows (actually probably just arrows) can get "stuck"
            // on entities they cannot damage, such as players while blocking with shields or these chains.
            // That would cause some serious sound spam, and we want to avoid that.
            self.playSound(SoundEvents.CHAIN_HIT, 0.5F, 1.0F);
        }
        return InteractionResult.FAIL;
    }

    /**
     * @param item The item subject of an interaction
     * @return true if a chain link entity can be destroyed with the item
     */
    static boolean canDestroyWith(ItemStack item) {
        return item.is(Tags.Items.SHEARS);
    }

    /**
     * Destroys all links associated with this entity
     *
     * @param mayDrop true when the links should drop
     */
    void destroyLinks(boolean mayDrop);
}
