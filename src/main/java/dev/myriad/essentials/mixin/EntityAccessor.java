package dev.myriad.essentials.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The synced entity flag byte (on fire, sneaking, sprinting, gliding, ...), for seeing when the server ends a glide. */
@Mixin(Entity.class)
public interface EntityAccessor {
	@Accessor("DATA_SHARED_FLAGS_ID")
	static EntityDataAccessor<Byte> essentials$entityFlags() {
		throw new AssertionError();
	}
}
