package com.infamous.sapience.mixin;

import com.infamous.sapience.SapienceConfig;
import com.infamous.sapience.capability.ageable.IAgeable;
import com.infamous.sapience.mod.ModMemoryModuleTypes;
import com.infamous.sapience.tasks.CraftWithGoldTask;
import com.infamous.sapience.tasks.CreateBabyTask;
import com.infamous.sapience.tasks.FeedHoglinsTask;
import com.infamous.sapience.tasks.ShareGoldTask;
import com.infamous.sapience.util.*;
import com.mojang.datafixers.util.Pair;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.behavior.InteractWith;
import net.minecraft.world.entity.ai.behavior.RunOne;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.stream.Collectors;

@Mixin(PiglinAi.class)
public class PiglinTasksMixin {

    @Redirect(at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/piglin/PiglinAi;isZombified(Lnet/minecraft/world/entity/EntityType;)Z"),
            method = "wantsToStopFleeing")
    private static boolean shouldAvoidZombified(EntityType<?> entityType, Piglin piglin){
        Brain<Piglin> brain = piglin.getBrain();
        LivingEntity avoidTarget = brain.getMemory(MemoryModuleType.AVOID_TARGET).get();
        return PiglinTasksHelper.piglinsAvoid(avoidTarget.getType())
                && GeneralHelper.isNotOnSameTeam(piglin, avoidTarget);
    }

    @Inject(at = @At("RETURN"), method = "createIdleMovementBehaviors", cancellable = true)
    private static void getLookTasks(CallbackInfoReturnable<RunOne<Piglin>> callbackInfoReturnable){
        RunOne<Piglin> piglinRunOne = callbackInfoReturnable.getReturnValue();

        BrainHelper.addToGateBehavior(piglinRunOne,
                Pair.of(new InteractWith<>(
                        EntityType.PIGLIN, 8,
                        AgeableHelper::canBreed,
                        AgeableHelper::canBreed,
                        ModMemoryModuleTypes.BREEDING_TARGET.get(), 0.5F, 2),
                1),
                Pair.of(new CreateBabyTask<>(), 3),
                Pair.of(new ShareGoldTask<>(), 2),
                Pair.of(new CraftWithGoldTask<>(), 2),
                Pair.of(new FeedHoglinsTask<>(), 2)
        );
    }

    @Inject(at = @At("HEAD"), method = "setAngerTarget")
    private static void setAngerTarget(AbstractPiglin piglinEntity, LivingEntity target, CallbackInfo callbackInfo){
        if(piglinEntity.canAttack(target)){
            piglinEntity.level.broadcastEntityEvent(piglinEntity, (byte) GeneralHelper.ANGER_ID);
        }
    }

    @Inject(at =
    @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/piglin/PiglinAi;removeOneItemFromItemEntity(Lnet/minecraft/world/entity/item/ItemEntity;)Lnet/minecraft/world/item/ItemStack;"),
            method = "pickUpItem",
            cancellable = true)
    private static void pickUpWantedItem(Piglin piglinEntity, ItemEntity itemEntity, CallbackInfo callbackInfo){
        IAgeable ageable = AgeableHelper.getAgeableCapability(piglinEntity);
        if(ageable != null && PiglinTasksHelper.isPiglinFoodItem(itemEntity.getItem())){
            ItemStack extractedItemStack = PiglinTasksHelper.extractSingletonFromItemEntity(itemEntity);
            // Needed to get the piglin to stop trying to pick up its food item once it's been picked up
            PiglinTasksHelper.removeTimeTryingToReachAdmireItem(piglinEntity);
            //PiglinTasksHelper.addToFoodInventoryThenDropRemainder(piglinEntity, extractedItemStack);
            PiglinTasksHelper.dropOffhandItemAndSetItemStackToOffhand(piglinEntity, extractedItemStack);
            PiglinTasksHelper.setAdmiringItem(piglinEntity);
            PiglinTasksHelper.clearWalkPath(piglinEntity);
            /*
            if(!PiglinTasksHelper.hasAteRecently(piglinEntity)){
                PiglinTasksHelper.setAteRecently(piglinEntity);
            }
             */
            callbackInfo.cancel();
        }
    }

    @Inject(at = @At("RETURN"), method = "isFood", cancellable = true)
    private static void isPiglinFoodItem(ItemStack item, CallbackInfoReturnable<Boolean> callbackInfoReturnable){
        boolean isPiglinFoodItem = PiglinTasksHelper.isPiglinFoodItem(item);
        callbackInfoReturnable.setReturnValue(isPiglinFoodItem);
    }


    @Inject(at = @At("RETURN"), method = "wantsToPickup", cancellable = true)
    private static void canPickUpItemStack(Piglin piglinEntity, ItemStack itemStack, CallbackInfoReturnable<Boolean> callbackInfoReturnable){
        boolean hasConsumableOffhandItem = PiglinTasksHelper.hasConsumableOffhandItem(piglinEntity);
        boolean canPickUpItemStack = callbackInfoReturnable.getReturnValue();
        if (PiglinTasksHelper.isPiglinFoodItem(itemStack)) {
            canPickUpItemStack = PiglinTasksHelper.canPickUpFoodStack(piglinEntity, itemStack);
        }
        callbackInfoReturnable.setReturnValue(canPickUpItemStack && !hasConsumableOffhandItem);
    }

    // when the piglin is going to finish admiring an item
    // the goal here is to check if the piglin should drop from the alternative barter loot tables
    // and also update the player's reputation with the piglin if it can do a barter successfully
    @Inject(at = @At(value = "HEAD"), method = "stopHoldingOffHandItem", cancellable = true)
    private static void finishAdmiringItem(Piglin piglinEntity, boolean doBarter, CallbackInfo callbackInfo){

        Entity interactorEntity = ReputationHelper.getPreviousInteractor(piglinEntity);
        boolean willDropLoot =
                interactorEntity instanceof LivingEntity living && ReputationHelper.isAllowedToBarter(piglinEntity, living)
                || interactorEntity == null && !SapienceConfig.COMMON.REQUIRE_LIVING_FOR_BARTER.get();

        ItemStack offhandStack = piglinEntity.getItemInHand(InteractionHand.OFF_HAND);

        boolean isIngotBarterGreedItem = PiglinTasksHelper.isNormalBarterItem(offhandStack);
        if (isIngotBarterGreedItem) {
            GreedHelper.addStackToGreedInventoryCheckBartered(piglinEntity, offhandStack, doBarter && piglinEntity.isAdult());
        }

        if (!piglinEntity.isBaby()) { // isAdult
            if(PiglinTasksHelper.isExpensiveBarterItem(offhandStack) && doBarter && willDropLoot){
                PiglinTasksHelper.dropBlockBarteringLoot(piglinEntity);
                CompoundTag compoundNBT = offhandStack.getOrCreateTag();
                compoundNBT.putBoolean(GreedHelper.BARTERED, true);

                ReputationHelper.updatePreviousInteractorReputation(piglinEntity, PiglinReputationType.BARTER);
            }
            else if(PiglinTasksHelper.isCheapBarterItem(offhandStack) && doBarter && willDropLoot){
                PiglinTasksHelper.dropNuggetBarteringLoot(piglinEntity);
                CompoundTag compoundNBT = offhandStack.getOrCreateTag();
                compoundNBT.putBoolean(GreedHelper.BARTERED, true);

                ReputationHelper.updatePreviousInteractorReputation(piglinEntity, PiglinReputationType.BARTER);
            }
            // treat piglin loved items as gold gifts for adults
            else if(PiglinTasksHelper.isPiglinLoved(offhandStack) && !PiglinTasksHelper.isBarterItem(offhandStack)){
                ReputationHelper.updatePreviousInteractorReputation(piglinEntity, PiglinReputationType.GOLD_GIFT);
            }

            // Since baby Piglins don't barter, we can treat barter items as gold gifts in addition to piglin loved items
        } else if(PiglinTasksHelper.isPiglinLoved(offhandStack) || PiglinTasksHelper.isBarterItem(offhandStack)){
            ReputationHelper.updatePreviousInteractorReputation(piglinEntity, PiglinReputationType.GOLD_GIFT);
        }
    }

    // When the piglin drops bartering loot after having been given piglin currency
    // The goal here is to prevent it dropping bartering loot if the player's rep is too low
    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/piglin/PiglinAi;throwItems(Lnet/minecraft/world/entity/monster/piglin/Piglin;Ljava/util/List;)V", ordinal = 0), method = "stopHoldingOffHandItem", cancellable = true)
    private static void dropLoot(Piglin piglinEntity, boolean doBarter, CallbackInfo callbackInfo){
        Entity interactorEntity = ReputationHelper.getPreviousInteractor(piglinEntity);

        if(doBarter){
            ReputationHelper.updatePreviousInteractorReputation(piglinEntity, PiglinReputationType.BARTER);
        }
        boolean willDropLoot =
                interactorEntity instanceof LivingEntity && ReputationHelper.isAllowedToBarter(piglinEntity, (LivingEntity) interactorEntity)
                || interactorEntity == null && !SapienceConfig.COMMON.REQUIRE_LIVING_FOR_BARTER.get();

        if(!willDropLoot){
            callbackInfo.cancel();
        }
    }

    @Inject(at = @At(value = "RETURN"), method = "canAdmire", cancellable = true)
    private static void canAcceptItemStack(Piglin piglinEntity, ItemStack itemStack, CallbackInfoReturnable<Boolean> callbackInfoReturnable){
        callbackInfoReturnable.setReturnValue(callbackInfoReturnable.getReturnValue() && !PiglinTasksHelper.hasConsumableOffhandItem(piglinEntity));
    }

    @ModifyVariable(at = @At("STORE"), method = "angerNearbyPiglins")
    private static List<Piglin> getPiglinsAngryAtThief(List<Piglin> nearbyPiglinsList, Player playerEntity, boolean checkVisible) {
        List<Piglin> filteredNearbyPiglinsList = nearbyPiglinsList
                .stream()
                .filter(PiglinTasksHelper::hasIdle)
                .filter((nearbyPiglin) -> !checkVisible || BehaviorUtils.canSee(nearbyPiglin, playerEntity))
                .filter((nearbyPiglin) -> !ReputationHelper.isAllowedToTouchGold(playerEntity, nearbyPiglin))
                .collect(Collectors.toList());

        filteredNearbyPiglinsList
                .forEach((nearbyPiglin) -> ReputationHelper.updatePiglinReputation(nearbyPiglin, PiglinReputationType.GOLD_STOLEN, playerEntity));

        return filteredNearbyPiglinsList;
    }

    // used for processing the default interaction for receiving piglin currency
    @Inject(at = @At("RETURN"), method = "mobInteract")
    private static void processInteractionForPiglinCurrency(Piglin piglinEntity, Player playerEntity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callbackInfoReturnable){
        if(callbackInfoReturnable.getReturnValue().consumesAction()){
            ReputationHelper.setPreviousInteractor(piglinEntity, playerEntity);
        }
    }

    @Inject(at = @At("RETURN"), method = "isZombified", cancellable = true)
    private static void handleZombified(EntityType<?> entityType, CallbackInfoReturnable<Boolean> cir){
        cir.setReturnValue(PiglinTasksHelper.piglinsAvoid(entityType));
    }

    // fixing hardcoded checks for "EntityType.HOGLIN"

    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getType()Lnet/minecraft/world/entity/EntityType;"), method = "wantsToDance")
    private static EntityType<?> redirectEntityTypeCheckDance(LivingEntity victim){
        // spoofs the check for the hoglin entity type if it is a mob that can be hunted by piglins
        return GeneralHelper.maybeSpoofPiglinsHunt(victim);
    }

    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getType()Lnet/minecraft/world/entity/EntityType;"), method = "wasHurtBy")
    private static EntityType<?> redirectEntityTypeCheckHurt(LivingEntity attacker){
        // spoofs the check for the hoglin entity type if it is a mob that can be hunted by piglins
        return GeneralHelper.maybeSpoofHoglin(attacker);
    }

    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getType()Lnet/minecraft/world/entity/EntityType;"), method = "broadcastAngerTarget")
    private static EntityType<?> redirectEntityTypeCheckBroadcastAnger(LivingEntity angerTarget){
        // spoofs the check for the hoglin entity type if it is a mob that can be hunted by piglins
        return GeneralHelper.maybeSpoofHoglin(angerTarget);
    }

    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getType()Lnet/minecraft/world/entity/EntityType;", ordinal = 0), method = "setAngerTarget")
    private static EntityType<?> redirectEntityTypeCheckAnger(LivingEntity angerTarget){
        // spoofs the check for the hoglin entity type if it is a mob that can be hunted by piglins
        return GeneralHelper.maybeSpoofHoglin(angerTarget);
    }

    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getType()Lnet/minecraft/world/entity/EntityType;"), method = "wantsToStopFleeing")
    private static EntityType<?> redirectEntityTypeCheckFleeing(LivingEntity avoidTarget){
        // spoofs the check for the hoglin entity type if it is a mob that can be hunted by piglins
        return GeneralHelper.maybeSpoofHoglin(avoidTarget);
    }

}
