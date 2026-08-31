package com.temog.notminelegacy.whatremains.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.temog.notminelegacy.whatremains.client.CorpseFleshBaker.ResolvedFlesh;
import com.temog.notminelegacy.whatremains.entity.CorpseEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.Vec3;

/** Draws the baked base flesh first and its player customisation cubes second. */
public final class CorpseFleshLayer
        extends RenderLayer<CorpseEntity, CorpseSkeletonModel<CorpseEntity>> {

    private static final Vec3 WHITE = new Vec3(1.0D, 1.0D, 1.0D);
    private static final Vec3 CHAR_TARGET_RGB = new Vec3(0.30D, 0.24D, 0.20D);
    private static final Vec3 WATERLOGGED_TARGET_RGB = new Vec3(0.55D, 0.62D, 0.50D);
    // Sampled from obsidian.png's violet-leaning near-black highlight band — the "quenched black
    // glass" read for a corpse that is both heavily charred AND heavily waterlogged (lava reaching
    // water, or vice versa). lava_progressive_incineration.md §6 item 5 / RECONCILIATION.md §6.
    private static final Vec3 VITRIFIED_TARGET_RGB = new Vec3(0.15D, 0.13D, 0.20D);
    private static final float VITRIFY_THRESHOLD = 0.5F;
    private static final float FROST_TARGET_R = 0.85F;
    private static final float FROST_TARGET_G = 0.92F;
    private static final float FROST_TARGET_B = 1.00F;
    // Raised from the originally-shipped 0.35 per frozen_rime.md §6 dependency 7: once the baked
    // ladder (CorpseRenderState.withEnvironment + CorpseFleshBaker's frost passes) carries the
    // primary colour story, this render-time pass becomes a secondary glossy ice-sheen highlight
    // rather than needing to BE the whole frost look by itself.
    private static final float FROST_MAX_SCALE = 0.58F;
    // "ice_mummy" — frost pushed to full overlay strength over ocean_bloat's pale swollen base
    // (frozen_rime.md §6 item 8 / ocean_bloat.md §6 item 6's shared stacking note).
    private static final float FROST_MAX_SCALE_ICE_MUMMY = 1.0F;
    private static final float ICE_MUMMY_WATERLOGGED_THRESHOLD = 0.5F;
    private static final float FROST_EPSILON = 0.02F;

    private final CorpseFleshModel<CorpseEntity> wideModel;
    private final CorpseFleshModel<CorpseEntity> slimModel;

    public CorpseFleshLayer(CorpseRenderer renderer, EntityRendererProvider.Context context) {
        super(renderer);
        wideModel = new CorpseFleshModel<>(context.bakeLayer(WRClientSetup.CORPSE_FLESH_WIDE), false);
        slimModel = new CorpseFleshModel<>(context.bakeLayer(WRClientSetup.CORPSE_FLESH_SLIM), true);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       CorpseEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float headYaw, float headPitch) {
        GameProfile profile = entity.getOwnerProfile();
        if (profile == null || profile.getId() == null || entity.isInvisible()) return;

        EnvironmentalRenderInputs env = EnvironmentalRenderInputs.from(entity);
        // Read once, alongside env, as a value snapshot — never re-read appearanceSeed from the live
        // entity anywhere past this point (SOL-PATCH-STITCH-DESIGN.md F.1 step 7); it is threaded
        // through CorpseFleshBaker.resolve/requestBake/BakeTask/bake as a plain long from here on.
        long appearanceSeed = entity.getAppearanceSeed();
        // Same value-snapshot discipline for the death-cause injury inputs (SOL-DEATHCAUSE-INJURY-
        // DESIGN.md F1 step 5) — captured here, beside env/appearanceSeed, and threaded through the
        // same resolve/requestBake/BakeTask/bake chain rather than ever re-read from the live entity.
        InjuryRenderInputs injury = InjuryRenderInputs.from(entity);
        CorpseRenderState state = CorpseRenderState.fromAge(entity.getCorpseAgeTicks())
                .withEnvironment(env);
        double distanceSquared = Minecraft.getInstance().player == null
                ? 0.0 : entity.distanceToSqr(Minecraft.getInstance().player);
        ResolvedFlesh flesh = CorpseFleshBaker.getInstance()
                .resolve(profile, entity.getUUID(), state, distanceSquared, env, appearanceSeed, injury);
        if (flesh == null) return;

        float frostLevel = entity.getFrostLevel();
        float charLevel = entity.getCharLevel();
        float waterloggedLevel = entity.getWaterloggedLevel();
        boolean oceanStyle = EnvironmentalRenderInputs.WATERLOGGED_STYLE_OCEAN
                .equals(entity.getWaterloggedStyle());

        Vec3 charFactor = WHITE.lerp(CHAR_TARGET_RGB, charLevel);
        Vec3 waterFactor = WHITE.lerp(WATERLOGGED_TARGET_RGB, waterloggedLevel);
        Vec3 tint;
        if (charLevel >= VITRIFY_THRESHOLD && waterloggedLevel >= VITRIFY_THRESHOLD) {
            // Vitrification wins outright regardless of which water style resolved — the more
            // specific, more visually assertive claim (RECONCILIATION.md §6's resolution of lava's
            // and ocean's competing edits to this line).
            tint = VITRIFIED_TARGET_RGB;
        } else if (oceanStyle) {
            // ocean_bloat's own bake-time HSV pass already fully encodes the bleached palette;
            // neutralise the generic render-time water tint (WATERLOGGED_TARGET_RGB can only ever
            // darken/green) so it doesn't pull the result back toward the sage-green the ocean
            // palette was built specifically to avoid (ocean_bloat.md §6 item 5).
            tint = charFactor;
        } else {
            tint = charFactor.multiply(waterFactor);
        }
        float tintRed = (float) tint.x;
        float tintGreen = (float) tint.y;
        float tintBlue = (float) tint.z;

        CorpseFleshModel<CorpseEntity> model = flesh.slim() ? slimModel : wideModel;
        model.copyCorpsePose(getParentModel(), state.bodyFullness);
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0F);

        VertexConsumer base = bufferSource.getBuffer(RenderType.entityCutoutNoCull(flesh.texture()));
        model.head.render(poseStack, base, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);
        model.body.render(poseStack, base, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);
        model.rightArm.render(poseStack, base, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);
        model.leftArm.render(poseStack, base, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);
        model.rightLeg.render(poseStack, base, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);
        model.leftLeg.render(poseStack, base, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);

        byte parts = entity.getModelCustomisation();
        model.hat.visible = enabled(parts, PlayerModelPart.HAT);
        model.jacket.visible = enabled(parts, PlayerModelPart.JACKET);
        model.rightSleeve.visible = enabled(parts, PlayerModelPart.RIGHT_SLEEVE);
        model.leftSleeve.visible = enabled(parts, PlayerModelPart.LEFT_SLEEVE);
        model.rightPants.visible = enabled(parts, PlayerModelPart.RIGHT_PANTS_LEG);
        model.leftPants.visible = enabled(parts, PlayerModelPart.LEFT_PANTS_LEG);

        VertexConsumer outer = bufferSource.getBuffer(RenderType.entityTranslucent(flesh.texture()));
        model.hat.render(poseStack, outer, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);
        model.jacket.render(poseStack, outer, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);
        model.rightSleeve.render(poseStack, outer, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);
        model.leftSleeve.render(poseStack, outer, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);
        model.rightPants.render(poseStack, outer, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);
        model.leftPants.render(poseStack, outer, packedLight, overlay, tintRed, tintGreen, tintBlue, 1.0F);

        if (frostLevel > FROST_EPSILON) {
            boolean iceMummy = oceanStyle && waterloggedLevel >= ICE_MUMMY_WATERLOGGED_THRESHOLD;
            float scale = frostLevel * (iceMummy ? FROST_MAX_SCALE_ICE_MUMMY : FROST_MAX_SCALE);
            float frostRed = FROST_TARGET_R * scale;
            float frostGreen = FROST_TARGET_G * scale;
            float frostBlue = FROST_TARGET_B * scale;
            VertexConsumer frost = bufferSource.getBuffer(RenderType.eyes(flesh.texture()));
            model.head.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
            model.body.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
            model.rightArm.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
            model.leftArm.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
            model.rightLeg.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
            model.leftLeg.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
            model.hat.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
            model.jacket.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
            model.rightSleeve.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
            model.leftSleeve.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
            model.rightPants.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
            model.leftPants.render(poseStack, frost, packedLight, overlay,
                    frostRed, frostGreen, frostBlue, 1.0F);
        }
    }

    private static boolean enabled(byte mask, PlayerModelPart part) {
        return (mask & part.getMask()) != 0;
    }
}
