package com.nopo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nopo.mixininterfaces.FunnyEntityData;
import com.nopo.utils.HypixelUtils;
import com.nopo.utils.IslandType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.Ravager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelExtractor.class)
public class MixinLevelRenderer {

    @WrapOperation(
        method = "extractVisibleEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
        )
    )
    public EntityRenderState ravage(EntityRenderDispatcher instance, Entity entity, float f, Operation<EntityRenderState> original) {
        if (HypixelUtils.INSTANCE.getCurrentIsland() != IslandType.DUNGEON || !(entity instanceof Sheep sheep) || !(sheep instanceof FunnyEntityData data) || !data.nopo$isFunny()) {
            return original.call(instance, entity, f);
        }

        @SuppressWarnings("unchecked")
        EntityType<Ravager> ravagerType = (EntityType<Ravager>) (EntityType<?>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("ravager"));
        Ravager ravager = new Ravager(ravagerType, Minecraft.getInstance().level);
        ravager.xo = sheep.xo;
        ravager.yo = sheep.yo;
        ravager.zo = sheep.zo;
        ravager.setYBodyRot(sheep.yBodyRot);
        ravager.setYHeadRot(sheep.yHeadRot);
        ravager.yBodyRotO = sheep.yBodyRotO;
        ravager.yHeadRotO = sheep.yHeadRotO;
        ravager.setPos(sheep.position());
        ravager.copyPosition(sheep);
        return original.call(instance, ravager, f);
    }
}