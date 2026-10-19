package dev.cornybirdy.corpes.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Gives access to the XP a player drops on death, for {@code store-xp}. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {

    @Invoker("getBaseExperienceReward")
    int corpes$getBaseExperienceReward(ServerLevel level);
}
