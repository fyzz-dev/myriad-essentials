package dev.myriad.essentials.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.OptionalInt;

/** Elytra Tweaks: which entity a rocket is boosting (synced from the server). */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketEntityAccessor {
	@Accessor("DATA_ATTACHED_TO_TARGET")
	static EntityDataAccessor<OptionalInt> essentials$attachedTarget() {
		throw new AssertionError();
	}
}
