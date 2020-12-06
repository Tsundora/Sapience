package com.infamous.sapience.mixin;

import com.google.common.collect.ImmutableList;
import com.infamous.sapience.mod.ModMemoryModuleTypes;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.entity.ai.brain.sensor.PiglinMobsSensor;
import net.minecraft.entity.monster.HoglinEntity;
import net.minecraft.world.server.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(PiglinMobsSensor.class)
public class PiglinMobsSensorMixin {

    @Inject(at = @At("HEAD"), method = "update")
    private void update(ServerWorld serverWorld, LivingEntity entityIn, CallbackInfo callbackInfo){
        Brain<?> brain = entityIn.getBrain();
        Optional<HoglinEntity> optionalNearestVisibleAdultHoglin = Optional.empty();

        for(LivingEntity livingentity : brain.getMemory(MemoryModuleType.VISIBLE_MOBS).orElse(ImmutableList.of())) {
            if (livingentity instanceof HoglinEntity) {
                HoglinEntity hoglinentity = (HoglinEntity)livingentity;
                if (hoglinentity.func_234363_eJ_() && !optionalNearestVisibleAdultHoglin.isPresent()) {
                    optionalNearestVisibleAdultHoglin = Optional.of(hoglinentity);
                }
            }
        }

        brain.setMemory(ModMemoryModuleTypes.NEAREST_VISIBLE_ADULT_HOGLIN.get(), optionalNearestVisibleAdultHoglin);
    }

}
