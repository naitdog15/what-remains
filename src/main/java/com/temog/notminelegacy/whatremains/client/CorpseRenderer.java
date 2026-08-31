package com.temog.notminelegacy.whatremains.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.temog.notminelegacy.whatremains.entity.CorpseEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Renderer for the corpse entity. Uses a skeleton texture and model,
 * rotated face-down on the ground (unless renderUpright is set for GUI preview).
 */
public class CorpseRenderer extends MobRenderer<CorpseEntity, CorpseSkeletonModel<CorpseEntity>> {

    private static final ResourceLocation SKELETON_TEXTURE =
            ResourceLocation.parse("minecraft:textures/entity/skeleton/skeleton.png");

    // Vanilla's wither skeleton texture, reused verbatim as the "charred exoskeleton" look for a
    // corpse that's absorbed heavy, sustained lava exposure. Confirmed UV/geometry-compatible with
    // the regular skeleton model this renderer bakes (ModelLayers.SKELETON): vanilla's own
    // LayerDefinitions registers ModelLayers.SKELETON and ModelLayers.WITHER_SKELETON to the exact
    // same LayerDefinition instance (SkeletonModel.createBodyLayer(), 64x32), and
    // wither_skeleton.png is itself a 64x32 texture — vanilla only tells the two apart with a
    // WitherSkeletonRenderer#scale() 1.2x render-time scale-up, not different geometry/UVs. So a
    // pure texture swap here, keeping ModelLayers.SKELETON's geometry, is exactly what vanilla
    // itself does for the shared parts of that texture and produces a coherent (non-stretched)
    // result.
    private static final ResourceLocation CHARRED_SKELETON_TEXTURE =
            ResourceLocation.parse("minecraft:textures/entity/skeleton/wither_skeleton.png");

    // char_level is fed only by sustained lava-fluid contact (see corpse_environment/lava.json,
    // accumulate_rate 0.005/tick == 0.1/sec, so 1.0 == ~10s of continuous contact) and is one-way /
    // never decays (CorpseEntity#getCharLevel), unlike the reversible frost/waterlogged trackers —
    // making it the only tracker whose crossing a threshold can justify a *permanent* skeleton swap.
    // 0.5 (~5s of continuous lava contact) is deliberately mid-scale: reachable without requiring
    // near-total (10s) submersion, while still representing sustained exposure rather than a brief
    // graze.
    private static final float CHARRED_SKELETON_THRESHOLD = 0.5F;

    public CorpseRenderer(EntityRendererProvider.Context context) {
        super(context, new CorpseSkeletonModel<>(context.bakeLayer(ModelLayers.SKELETON)), 0.0f);
        this.shadowRadius = 0.0f;
        this.shadowStrength = 0.0f;
        // The skeleton is deliberately left to the superclass base pass. Hard alpha holes in the
        // enclosing flesh reveal that real bone; armor must remain the final, outside layer — the
        // stuck-projectile and held-item layers below sit BETWEEN flesh and armor deliberately: an
        // arrow stuck in a chestplate should still read as embedded in the body underneath it, and a
        // held item is logically "in the corpse's hand", not painted over its equipped gear.
        this.addLayer(new CorpseFleshLayer(this, context));
        this.addLayer(new CorpseStuckArrowLayer(this, context));
        this.addLayer(new CorpseHeldItemLayer(this, context));
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(CorpseEntity entity) {
        return entity.getCharLevel() >= CHARRED_SKELETON_THRESHOLD ? CHARRED_SKELETON_TEXTURE : SKELETON_TEXTURE;
    }

    @Override
    protected void setupRotations(CorpseEntity entity, PoseStack poseStack,
                                  float bob, float yBodyRot, float partialTick) {
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick);

        // When rendering in a GUI preview, skip the face-down rotation
        if (entity.renderUpright) return;

        // Rotate corpse face-down (90 degrees around X axis)
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        poseStack.translate(0.0f, -1.001f, 0.0f);
    }
}
