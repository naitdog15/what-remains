package com.temog.notminelegacy.whatremains.client;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.world.entity.LivingEntity;

/** Player-UV flesh shell whose centred arms can waste down without exposing a skeleton face. */
public final class CorpseFleshModel<T extends LivingEntity> extends PlayerModel<T> {

    private static final float BASE_INFLATION = 0.25F;
    private static final float WIDE_MINIMUM_SCALE = 5.0F / 9.0F;
    private static final float SLIM_ARM_MINIMUM_X_SCALE = 5.0F / 7.0F;

    private final boolean slim;

    public CorpseFleshModel(ModelPart root, boolean slim) {
        super(root, slim);
        this.slim = slim;
    }

    public static LayerDefinition createBodyLayer(boolean slim) {
        CubeDeformation flesh = new CubeDeformation(BASE_INFLATION);
        CubeDeformation outer = flesh.extend(0.25F);
        MeshDefinition mesh = PlayerModel.createMesh(flesh, slim);
        PartDefinition root = mesh.getRoot();
        float armWidth = slim ? 3.0F : 4.0F;
        float halfArmWidth = armWidth / 2.0F;
        float fullPivot = slim ? 5.5F : 6.0F;

        // Stock player arms are asymmetric around their pivots. Scaling those cubes can never
        // recenter them over a two-wide skeleton arm, so both variants use centred boxes here.
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 16)
                .addBox(-halfArmWidth, -2.0F, -2.0F, armWidth, 12.0F, 4.0F, flesh),
                PartPose.offset(-fullPivot, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(32, 48)
                .addBox(-halfArmWidth, -2.0F, -2.0F, armWidth, 12.0F, 4.0F, flesh),
                PartPose.offset(fullPivot, 2.0F, 0.0F));
        root.addOrReplaceChild("right_sleeve", CubeListBuilder.create().texOffs(40, 32)
                .addBox(-halfArmWidth, -2.0F, -2.0F, armWidth, 12.0F, 4.0F, outer),
                PartPose.offset(-fullPivot, 2.0F, 0.0F));
        root.addOrReplaceChild("left_sleeve", CubeListBuilder.create().texOffs(48, 48)
                .addBox(-halfArmWidth, -2.0F, -2.0F, armWidth, 12.0F, 4.0F, outer),
                PartPose.offset(fullPivot, 2.0F, 0.0F));
        return LayerDefinition.create(mesh, 64, 64);
    }

    /** Copies the corpse sprawl pose first, then applies pivots and scales that copyFrom resets. */
    public void copyCorpsePose(CorpseSkeletonModel<T> parent, float fullness) {
        head.copyFrom(parent.head);
        hat.copyFrom(parent.hat);
        body.copyFrom(parent.body);
        leftArm.copyFrom(parent.leftArm);
        rightArm.copyFrom(parent.rightArm);
        leftLeg.copyFrom(parent.leftLeg);
        rightLeg.copyFrom(parent.rightLeg);
        young = false;

        float armOffset = fullness * (slim ? 0.5F : 1.0F);
        rightArm.x = parent.rightArm.x - armOffset;
        leftArm.x = parent.leftArm.x + armOffset;
        rightLeg.x = parent.rightLeg.x + 0.1F * fullness;
        leftLeg.x = parent.leftLeg.x - 0.1F * fullness;

        float armXScale = lerp(slim ? SLIM_ARM_MINIMUM_X_SCALE : WIDE_MINIMUM_SCALE,
                1.0F, fullness);
        float limbZScale = lerp(WIDE_MINIMUM_SCALE, 1.0F, fullness);
        setScale(rightArm, armXScale, limbZScale);
        setScale(leftArm, armXScale, limbZScale);
        setScale(rightLeg, lerp(WIDE_MINIMUM_SCALE, 1.0F, fullness), limbZScale);
        setScale(leftLeg, lerp(WIDE_MINIMUM_SCALE, 1.0F, fullness), limbZScale);

        // These copies deliberately happen after pivot/scale correction. ModelPart.copyFrom also
        // copies scale, which is exactly what the matching outer cubes need here.
        jacket.copyFrom(body);
        rightSleeve.copyFrom(rightArm);
        leftSleeve.copyFrom(leftArm);
        rightPants.copyFrom(rightLeg);
        leftPants.copyFrom(leftLeg);
        restoreBaseVisibility();
    }

    public void restoreBaseVisibility() {
        head.visible = true;
        body.visible = true;
        rightArm.visible = true;
        leftArm.visible = true;
        rightLeg.visible = true;
        leftLeg.visible = true;
    }

    private static void setScale(ModelPart part, float xScale, float zScale) {
        part.xScale = xScale;
        part.yScale = 1.0F;
        part.zScale = zScale;
    }

    private static float lerp(float from, float to, float progress) {
        return from + (to - from) * progress;
    }
}
