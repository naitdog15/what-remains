package com.temog.notminelegacy.whatremains.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.temog.notminelegacy.whatremains.entity.CorpseEntity;
import com.temog.notminelegacy.whatremains.entity.StuckProjectile;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * Renders every {@link StuckProjectile} embedded in a corpse — arrows, tipped arrows, spectral
 * arrows and tridents left sticking out of the body from whatever killed (or merely grazed) the
 * player before death.
 * <p>
 * Vanilla's own {@code StuckInBodyLayer}/{@code ArrowLayer} can't be reused here: both are bound to
 * {@code M extends PlayerModel<T>} because {@code StuckInBodyLayer#render} calls
 * {@code getParentModel().getRandomModelPart(random)}, a method that only exists on
 * {@link net.minecraft.client.model.PlayerModel} ({@code PlayerModel.java:143-145}), not on
 * {@link net.minecraft.client.model.HumanoidModel}. {@link CorpseSkeletonModel} is a
 * {@code HumanoidModel}, so those vanilla generic bounds simply won't compile against it here. This
 * class is a from-scratch {@link RenderLayer} that mirrors vanilla's placement algorithm
 * ({@code StuckInBodyLayer.java:16-49}, {@code ArrowLayer.java:20-37}) without depending on the
 * {@code PlayerModel}-only entry point.
 */
public class CorpseStuckArrowLayer extends RenderLayer<CorpseEntity, CorpseSkeletonModel<CorpseEntity>> {

    /**
     * Killing-blow projectiles render at this scale instead of 1.0x — deliberately subtle (a plain
     * arrow is already small) so it reads as "that's the one that did it" on close inspection rather
     * than looking like a conspicuously different object stuck in the body.
     */
    private static final float KILLING_BLOW_SCALE = 1.15F;

    /** Reused stack for resetting {@link #arrowPuppet} back to "no potion colour" between stuck
     *  entries within the same frame — see {@link #resolveArrowPuppet}. Never mutated (no NBT tag
     *  ever added to it), so one shared static instance is safe. */
    private static final ItemStack PLAIN_ARROW_STACK = new ItemStack(Items.ARROW);

    private final EntityRenderDispatcher dispatcher;

    /**
     * Throwaway projectile entities used purely as render puppets for
     * {@link EntityRenderDispatcher#render} — one per {@link StuckProjectile.Kind} that needs a
     * distinct entity class, created lazily on first use and then reused for every stuck projectile
     * of that kind, on every corpse, on every frame, for the lifetime of the client session.
     * <p>
     * This is safe ONLY because these entities are never ticked and never added to a {@link Level}:
     * nothing but their own renderer ever reads them, and the renderer only reads whatever fields we
     * set immediately beforehand (position, rotation, and — for a tipped arrow — the potion colour).
     * Without this cache, every stuck arrow on every corpse would allocate a brand new {@code Entity}
     * subclass instance every single frame purely to throw it away a few nanoseconds later — the same
     * cost vanilla itself accepts in {@code ArrowLayer} (it constructs fresh every frame too), but
     * here corpses can persist far longer on-screen than a skeleton mid-fight, so it's worth avoiding.
     */
    private Arrow arrowPuppet;
    private SpectralArrow spectralArrowPuppet;
    private ThrownTrident tridentPuppet;

    public CorpseStuckArrowLayer(CorpseRenderer renderer, EntityRendererProvider.Context context) {
        super(renderer);
        this.dispatcher = context.getEntityRenderDispatcher();
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                        CorpseEntity entity, float limbSwing, float limbSwingAmount,
                        float partialTick, float ageInTicks, float headYaw, float headPitch) {
        if (entity.isInvisible()) return;
        List<StuckProjectile> stuck = entity.getStuckProjectiles();
        if (stuck.isEmpty()) return;

        CorpseSkeletonModel<CorpseEntity> model = getParentModel();
        // Fixed lookup over HumanoidModel's six public fields, in the exact order StuckProjectile's
        // partIndex javadoc specifies (0=HEAD 1=BODY 2=RIGHT_ARM 3=LEFT_ARM 4=RIGHT_LEG 5=LEFT_LEG).
        // `hat` is deliberately excluded — it's a cosmetic overlay riding on `head`'s pose, not one
        // of the six placeable body parts.
        ModelPart[] parts = {
                model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg
        };

        for (StuckProjectile projectile : stuck) {
            int partIndex = projectile.partIndex();
            if (partIndex < 0 || partIndex >= parts.length) continue;
            ModelPart part = parts[partIndex];
            if (part.isEmpty()) continue; // defensive: a cube-less part has nowhere to anchor to

            poseStack.pushPose();
            part.translateAndRotate(poseStack);

            float px = projectile.px();
            float py = projectile.py();
            float pz = projectile.pz();

            // Deterministic stand-in for vanilla's getRandomCube(RandomSource) — see
            // DeterministicCubeRandom's own javadoc for why this indirection (rather than a direct
            // cube-list access) is necessary at all.
            ModelPart.Cube cube = part.getRandomCube(new DeterministicCubeRandom(px));

            float tx = Mth.lerp(px, cube.minX, cube.maxX) / 16.0F;
            float ty = Mth.lerp(py, cube.minY, cube.maxY) / 16.0F;
            float tz = Mth.lerp(pz, cube.minZ, cube.maxZ) / 16.0F;
            poseStack.translate(tx, ty, tz);

            if (projectile.killingBlow()) {
                // Subtle emphasis only — see KILLING_BLOW_SCALE's javadoc.
                poseStack.scale(KILLING_BLOW_SCALE, KILLING_BLOW_SCALE, KILLING_BLOW_SCALE);
            }

            // Same aim-direction derivation vanilla's StuckInBodyLayer/ArrowLayer use, just fed from
            // the stored normalized coordinates instead of a freshly-rolled random triple — see the
            // class javadoc's "Determinism" note on why per-frame RNG isn't appropriate here.
            float dirX = -1.0F * (px * 2.0F - 1.0F);
            float dirY = -1.0F * (py * 2.0F - 1.0F);
            float dirZ = -1.0F * (pz * 2.0F - 1.0F);

            renderStuckProjectile(poseStack, bufferSource, packedLight, entity, projectile,
                    dirX, dirY, dirZ, partialTick);

            poseStack.popPose();
        }
    }

    /** Mirrors {@code ArrowLayer#renderStuckItem}'s yaw/pitch derivation and dispatch call
     *  ({@code Mth.sqrt}/{@code Mth.atan2}/{@code * (180F/PI)}), just reading from a cached puppet
     *  entity (see {@link #resolvePuppet}) instead of constructing a fresh one every frame. */
    private void renderStuckProjectile(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                                        CorpseEntity entity, StuckProjectile projectile,
                                        float dirX, float dirY, float dirZ, float partialTick) {
        Entity puppet = resolvePuppet(entity.level(), projectile);
        if (puppet == null) return;

        // Real world position is mostly cosmetic here — EntityRenderDispatcher#render is called
        // below with explicit (0,0,0) offsets, so the actual screen position comes entirely from the
        // PoseStack we've already translated into place. Still kept in sync (matching vanilla's own
        // fresh-construct-at-corpse-position approach in ArrowLayer) so shadow-distance calculations
        // and crash-report location data stay sane rather than pointing at the origin.
        puppet.setPos(entity.getX(), entity.getY(), entity.getZ());

        float horizontalLen = Mth.sqrt(dirX * dirX + dirZ * dirZ);
        float yRot = (float) (Math.atan2((double) dirX, (double) dirZ) * (180F / (float) Math.PI));
        float xRot = (float) (Math.atan2((double) dirY, (double) horizontalLen) * (180F / (float) Math.PI));
        puppet.setYRot(yRot);
        puppet.setXRot(xRot);
        puppet.yRotO = yRot;
        puppet.xRotO = xRot;

        dispatcher.render(puppet, 0.0D, 0.0D, 0.0D, 0.0F, partialTick, poseStack, bufferSource, packedLight);
    }

    private Entity resolvePuppet(Level level, StuckProjectile projectile) {
        return switch (projectile.kind()) {
            case ARROW -> resolveArrowPuppet(level, -1);
            case TIPPED_ARROW -> resolveArrowPuppet(level, projectile.color());
            case SPECTRAL_ARROW -> resolveSpectralArrowPuppet(level);
            case TRIDENT -> resolveTridentPuppet(level);
        };
    }

    private Arrow resolveArrowPuppet(Level level, int color) {
        if (arrowPuppet == null || arrowPuppet.level() != level) {
            arrowPuppet = new Arrow(level, 0.0, 0.0, 0.0);
        }
        if (color > 0) {
            // Arrow#setFixedColor is private. The public route — and exactly what Arrow itself uses
            // internally (see Arrow#getCustomColor/#setEffectsFromItem/#addAdditionalSaveData, which
            // round-trips the colour through this same "CustomPotionColor" NBT tag) — is to hand it a
            // tipped-arrow ItemStack carrying that tag and let setEffectsFromItem read it back out.
            // No reflection, no access-transformer entry needed.
            ItemStack tippedStack = new ItemStack(Items.TIPPED_ARROW);
            tippedStack.getOrCreateTag().putInt("CustomPotionColor", color);
            arrowPuppet.setEffectsFromItem(tippedStack);
        } else {
            // Plain ARROW, or a TIPPED_ARROW entry with no stored colour (color <= 0) — reset the
            // shared puppet so it doesn't carry over a previous stuck-arrow's tint within this same
            // frame (arrowPuppet is reused across every ARROW/TIPPED_ARROW entry on this corpse).
            arrowPuppet.setEffectsFromItem(PLAIN_ARROW_STACK);
        }
        return arrowPuppet;
    }

    private SpectralArrow resolveSpectralArrowPuppet(Level level) {
        if (spectralArrowPuppet == null || spectralArrowPuppet.level() != level) {
            spectralArrowPuppet = new SpectralArrow(level, 0.0, 0.0, 0.0);
        }
        return spectralArrowPuppet;
    }

    private ThrownTrident resolveTridentPuppet(Level level) {
        // ThrownTrident has no public (Level, double, double, double) constructor (unlike
        // Arrow/SpectralArrow) — its only position-taking constructor is
        // (Level, LivingEntity, ItemStack), which pulls in real shooter/ownership semantics (loyalty
        // enchant level, foil flag, spawn-at-shooter's-eye positioning) that make no sense for a pure
        // render puppet. The barebones (EntityType, Level) constructor — the same one Forge/vanilla
        // use for entity-type registry factories — works fine here since position/rotation are set
        // manually on every call anyway.
        if (tridentPuppet == null || tridentPuppet.level() != level) {
            tridentPuppet = new ThrownTrident(EntityType.TRIDENT, level);
        }
        return tridentPuppet;
    }

    /**
     * A {@link RandomSource} stand-in whose {@link #nextInt(int)} — the only method
     * {@link ModelPart#getRandomCube} actually calls — returns a deterministic index derived from a
     * stored fraction instead of progressing a real random sequence.
     * <p>
     * {@link ModelPart} keeps its cube list {@code private} with no getter beyond
     * {@code getRandomCube(RandomSource)}, so reusing that exact call (rather than reaching for the
     * list directly, which isn't exposed at all) is the only way to pick a cube without hard-coding
     * an assumption about how many cubes a given body part has. Every other interface method here is
     * unused by {@code getRandomCube} and is a plain, honest passthrough to a real backing
     * {@link RandomSource} — kept correct rather than stubbed, so this stays a fully valid
     * implementation instead of a partial fake with landmine methods for someone to trip over later.
     */
    private static final class DeterministicCubeRandom implements RandomSource {
        private final RandomSource delegate = RandomSource.create(0L);
        private final float fraction;

        DeterministicCubeRandom(float fraction) {
            this.fraction = fraction;
        }

        @Override
        public RandomSource fork() {
            return delegate.fork();
        }

        @Override
        public PositionalRandomFactory forkPositional() {
            return delegate.forkPositional();
        }

        @Override
        public void setSeed(long seed) {
            delegate.setSeed(seed);
        }

        @Override
        public int nextInt() {
            return delegate.nextInt();
        }

        @Override
        public int nextInt(int bound) {
            // The one call getRandomCube actually makes: deterministically index into [0, bound) by
            // the stored fraction rather than progressing a random sequence. If a part has multiple
            // cubes this spreads px's [0,1) range evenly across them; with the single cube every
            // HumanoidModel body part has in practice, bound == 1 and this always returns 0.
            if (bound <= 0) return 0;
            return Mth.clamp((int) (fraction * (float) bound), 0, bound - 1);
        }

        @Override
        public long nextLong() {
            return delegate.nextLong();
        }

        @Override
        public boolean nextBoolean() {
            return delegate.nextBoolean();
        }

        @Override
        public float nextFloat() {
            return delegate.nextFloat();
        }

        @Override
        public double nextDouble() {
            return delegate.nextDouble();
        }

        @Override
        public double nextGaussian() {
            return delegate.nextGaussian();
        }
    }
}
