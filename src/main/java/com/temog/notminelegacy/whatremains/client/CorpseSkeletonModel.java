package com.temog.notminelegacy.whatremains.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;

/**
 * A skeleton-proportioned humanoid model with a static "face-down sprawled"
 * corpse pose, matching the original Lootable Bodies aesthetic.
 */
public class CorpseSkeletonModel<T extends LivingEntity> extends HumanoidModel<T> {

    public CorpseSkeletonModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float headYaw, float headPitch) {
        // Override all animation — corpse is static
        this.rightLeg.zRot = 0.2f;
        this.leftLeg.zRot = -0.2f;
        this.rightArm.zRot = 0.3f;
        this.leftArm.zRot = -0.3f;
        this.rightArm.yRot = 1.25f;
        this.leftArm.yRot = -0.5f;
        this.rightArm.xRot = 0f;
        this.leftArm.xRot = 0f;
        this.head.xRot = 0f;
        this.head.yRot = 0f;
        this.head.zRot = 0f;
        // Legs
        this.rightLeg.xRot = 0f;
        this.leftLeg.xRot = 0f;
    }
}
