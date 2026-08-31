package com.temog.notminelegacy.whatremains.client;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.temog.notminelegacy.whatremains.client.DeathCauseInjuryProfile.ResolvedLocus;
import com.temog.notminelegacy.whatremains.entity.CorpseEntity;
import com.temog.notminelegacy.whatremains.entity.DecayStage;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Renders {@link CorpseEntity#getDeathHeldItem()} in the corpse's right hand — a one-time visual
 * snapshot of whatever the player was holding at the moment of death, NOT a live equipment slot.
 * <p>
 * Vanilla's own {@code ItemInHandLayer} can't be reused for this: it unconditionally reads
 * {@code LivingEntity#getMainHandItem()}/{@code #getOffhandItem()} ({@code ItemInHandLayer.java:28-29}),
 * i.e. the entity's actual equipment slots. A corpse deliberately does NOT put the death-held item
 * into its main-hand equipment slot — that slot stays free for whatever gets put into the corpse's
 * container inventory post-mortem — so there is nothing in the equipment slots for vanilla's layer to
 * find, and it would render nothing regardless of what's held. This class instead copies vanilla's
 * exact transform chain ({@code ItemInHandLayer.renderArmWithItem}, lines 44-55) but points it at the
 * dedicated death-snapshot field instead.
 */
public class CorpseHeldItemLayer extends RenderLayer<CorpseEntity, CorpseSkeletonModel<CorpseEntity>> {

    /** Cap for {@link #POSE_ROW_MEMO} — bounds it against a long session cycling through many
     *  corpses (players dying repeatedly, /kill-and-summon testing loops, etc). */
    private static final int POSE_ROW_MEMO_CAPACITY = 64;

    /**
     * The pose-row table (RECONCILIATION.md section R5) only ever defined 5 rows — FRESH,
     * EARLY_DECAY, MID_DECAY, LATE_DECAY, and a final "loosest grip" row, ordinals 0-4. DecayStage
     * gaining ADVANCED_DECAY/SKELETAL (7 values total, ordinals 0-6) doesn't mean the pose table
     * needs a 6th/7th row: both new stages are visually "final loosest grip" exactly like the old
     * SKELETAL row was — ADVANCED_DECAY because it IS the old SKELETAL stage (same fraction/position,
     * renamed), and the new terminal SKELETAL because it can only ever be a strict superset of that.
     * {@code Math.min(ordinal, MAX_POSE_ROW)} is the base-row clamp; every place that used to clamp
     * to {@code DecayStage.SKELETAL.ordinal()} (the right-arm-injury advance-by-one logic, the
     * full-reveal-forces-final-pose logic) now clamps to this constant instead, so a rename of which
     * enum constant is literally called "SKELETAL" can never again silently change what those clamps
     * resolve to.
     */
    private static final int MAX_POSE_ROW = 4;

    /**
     * {@link #resolveDecayPoseRow}'s per-corpse memo, keyed by the corpse's {@link
     * net.minecraft.world.entity.Entity#getUUID()}.
     * <p>
     * <b>Deliberately NOT keyed by {@code Entity#getId()}</b>: network ids are per-level ints that
     * are recycled once an entity is removed, and {@link CorpseRenderState#visualStep} is pinned to
     * {@code 0} for the whole of {@code FRESH}. So a brand-new corpse that inherits a recycled id
     * would match a dead corpse's leftover {@code (visualStep=0, charBucket=0)} entry exactly, and
     * silently adopt that other corpse's pose row — including its {@code RIGHT_ARM}-advanced or
     * full-reveal-forced row — for its entire FRESH stage, which is not self-correcting on any
     * useful timescale. The UUID is unique per corpse and never recycled, so the collision cannot
     * happen at all.
     * <p>
     * {@link #computeDecayPoseRow} is expensive (env snapshot + full {@code CorpseRenderState}
     * environmental blend + injury-input snapshot + {@code DeathCauseInjuryProfile.resolveLocus} +
     * {@code CorpseFleshBaker.isRightArmFullyRevealed}'s two static {@code synchronized} LRU-cache
     * locks, one of which also builds a full hole rank on a miss) yet its result — one int 0..4 —
     * only actually changes when {@link CorpseRenderState#visualStep} or the char bucket moves, which
     * happens at most a handful of times across a corpse's multi-day life.
     * <p>
     * Render-thread-only, exactly like every {@code RenderLayer#render} call and exactly the
     * convention {@link CorpseFleshBaker}'s own instance state (its class javadoc: "All public
     * methods are called on the client/render thread") already relies on — so this plain mutable
     * static map needs no synchronization of its own. Access-ordered with {@link
     * #removeEldestEntry(Map.Entry)} capping it at {@value #POSE_ROW_MEMO_CAPACITY} entries so it
     * cannot grow without bound as corpses come and go.
     */
    private static final LinkedHashMap<java.util.UUID, PoseMemo> POSE_ROW_MEMO =
            new LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<java.util.UUID, PoseMemo> eldest) {
                    return size() > POSE_ROW_MEMO_CAPACITY;
                }
            };

    /** One memoized {@link #computeDecayPoseRow} result, plus the two inputs that can invalidate it. */
    private record PoseMemo(int visualStep, int charBucket, int poseRow) {}

    private final ItemInHandRenderer itemInHandRenderer;

    public CorpseHeldItemLayer(CorpseRenderer renderer, EntityRendererProvider.Context context) {
        super(renderer);
        this.itemInHandRenderer = context.getItemInHandRenderer();
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                        CorpseEntity entity, float limbSwing, float limbSwingAmount,
                        float partialTick, float ageInTicks, float headYaw, float headPitch) {
        ItemStack stack = entity.getDeathHeldItem();
        if (stack.isEmpty()) return;

        int poseRow = resolveDecayPoseRow(entity);

        poseStack.pushPose();
        // Exact sequence from ItemInHandLayer.renderArmWithItem (ItemInHandLayer.java:44-55), fixed
        // to the right arm/hand since a corpse always displays its death-held item there regardless
        // of the dead player's actual main-hand-arm preference.
        getParentModel().translateToHand(HumanoidArm.RIGHT, poseStack);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.translate(1.0F / 16.0F, 0.125F, -0.625F);
        applyDecayPose(poseStack, poseRow);
        itemInHandRenderer.renderItem(entity, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                false, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }

    /**
     * Memoized entry point for {@link #computeDecayPoseRow} (see {@link #POSE_ROW_MEMO}'s javadoc for
     * why memoizing is safe and worthwhile). Recomputes only when {@link
     * CorpseRenderState#visualStep} — the existing 0..63 age quantization the whole bake pipeline
     * already treats as its meaningful age granularity — or the char bucket has moved since the last
     * call for this entity id. Both are cheap to read every frame on their own: {@code
     * CorpseRenderState.fromAge} is pure age arithmetic (no environment merge, no locks, no
     * collections), and {@link CorpseFleshBaker#charBucket} quantizes a single already-synced entity
     * data value — neither pays any of the real cost {@link #computeDecayPoseRow} does. The char
     * bucket must be part of the key alongside {@code visualStep} because {@code
     * CorpseRenderState#externalRevealBoost} (fed into the reveal query below) is char-driven and
     * quantized independently of age; reuses {@link CorpseFleshBaker}'s own bucketing so this can
     * never drift out of step with {@code BakeKey}'s identical dimension.
     */
    private static int resolveDecayPoseRow(CorpseEntity entity) {
        int visualStep = CorpseRenderState.fromAge(entity.getCorpseAgeTicks()).visualStep;
        int charBucket = CorpseFleshBaker.charBucket(entity.getCharLevel());

        java.util.UUID corpseUuid = entity.getUUID();
        PoseMemo memo = POSE_ROW_MEMO.get(corpseUuid);
        if (memo != null && memo.visualStep() == visualStep && memo.charBucket() == charBucket) {
            return memo.poseRow();
        }

        int poseRow = computeDecayPoseRow(entity);
        POSE_ROW_MEMO.put(corpseUuid, new PoseMemo(visualStep, charBucket, poseRow));
        return poseRow;
    }

    /**
     * RECONCILIATION.md R5 / SOL-DEATHCAUSE-INJURY-DESIGN.md section E2 (the rewritten table — NOT
     * the earlier draft's obsolete 5/12/20-degree values). Base row is the corpse's own decay stage
     * ordinal clamped to {@link #MAX_POSE_ROW} (FRESH=0 .. ADVANCED_DECAY/SKELETAL=4=MAX_POSE_ROW;
     * COLLAPSE never reaches this layer — the corpse renderer/layers stop entirely at COLLAPSE).
     * Three adjustments, all required:
     * <ul>
     *   <li>if the resolved cause profile's primary top-level part is RIGHT_ARM, advance one row,
     *   clamped to {@link #MAX_POSE_ROW} (FRESH stays FRESH; EARLY uses MID; MID uses LATE;
     *   LATE/ADVANCED_DECAY/SKELETAL stay at MAX_POSE_ROW);</li>
     *   <li>true SKELETAL (no flesh anywhere on the whole body, by definition) short-circuits both
     *   of the above and every query below it — the body IS fully "revealed" trivially, there's no
     *   flesh left to query — forcing {@link #MAX_POSE_ROW} directly;</li>
     *   <li>otherwise (ADVANCED_DECAY or earlier, where flesh genuinely still exists and reveal is
     *   genuinely probabilistic), if a RIGHT_ARM full-part reveal is currently active — queried via
     *   {@link CorpseFleshBaker#isRightArmFullyRevealed}, the exact same activation+selection
     *   decision the baked flesh itself uses, never re-derived independently — force
     *   {@link #MAX_POSE_ROW} immediately, regardless of the corpse's actual decay stage.</li>
     * </ul>
     * Left-arm injury never changes this: the snapshot always renders in the right hand.
     * <p>
     * Only called by {@link #resolveDecayPoseRow} on a memo miss — never directly from {@link
     * #render}. See that method's javadoc and {@link #POSE_ROW_MEMO} for why this expensive body
     * (environmental snapshot, full {@code CorpseRenderState} environmental blend, injury-input
     * snapshot, cause-locus resolution, and a reveal query that takes two static {@code synchronized}
     * locks) does not need to run every frame.
     */
    private static int computeDecayPoseRow(CorpseEntity entity) {
        EnvironmentalRenderInputs env = EnvironmentalRenderInputs.from(entity);
        CorpseRenderState state = CorpseRenderState.fromAge(entity.getCorpseAgeTicks()).withEnvironment(env);
        InjuryRenderInputs injury = InjuryRenderInputs.from(entity);
        ResolvedLocus locus = DeathCauseInjuryProfile.resolveLocus(entity.getDeathCause(),
                entity.getAppearanceSeed(), injury.killingAnchor());

        int row = Math.min(state.decayStage.ordinal(), MAX_POSE_ROW);
        if (locus.primaryPart() == CorpseFleshMask.Part.RIGHT_ARM) {
            row = Math.min(row + 1, MAX_POSE_ROW);
        }

        // True SKELETAL means no flesh ANYWHERE on the whole body, by definition (CorpseFleshBaker#
        // resolve never even bakes flesh at this stage) — the body IS fully "revealed" trivially, so
        // skip the probabilistic isRightArmFullyRevealed query entirely (it would otherwise pay its
        // real per-construction cost — two static synchronized LRU-cache locks, one of which builds
        // a full hole rank on a miss — for a foregone conclusion) and force the final pose directly.
        if (state.decayStage == DecayStage.SKELETAL) {
            return MAX_POSE_ROW;
        }

        GameProfile profile = entity.getOwnerProfile();
        if (profile != null && profile.getId() != null) {
            CorpseAppearanceLayout.RevealPreference preference =
                    DeathCauseInjuryProfile.revealPreferenceFor(entity.getDeathCause(), locus);
            boolean rightArmRevealed = CorpseFleshBaker.getInstance().isRightArmFullyRevealed(
                    profile.getId(), entity.getAppearanceSeed(), state.decayStage, state.stageProgress,
                    state.externalRevealBoost, preference);
            if (rightArmRevealed) row = MAX_POSE_ROW;
        }
        return row;
    }

    private static void applyDecayPose(PoseStack poseStack, int poseRow) {
        switch (poseRow) {
            case 0, 1 -> {
                // FRESH / EARLY_DECAY — existing exact vanilla transform, no premature motion.
            }
            case 2 -> {
                poseStack.translate(0.00F, 0.04F, 0.00F);
                poseStack.mulPose(Axis.ZP.rotationDegrees(8.0F));
            }
            case 3 -> {
                poseStack.translate(0.03F, 0.09F, 0.02F);
                poseStack.mulPose(Axis.ZP.rotationDegrees(18.0F));
            }
            default -> {
                // row 4 (MAX_POSE_ROW) — ADVANCED_DECAY/SKELETAL, or a forced final pose from a
                // RIGHT_ARM injury advance or an active full-part reveal — dropped-but-associated
                // loot beside the hand.
                poseStack.translate(0.18F, 0.13F, 0.04F);
                poseStack.mulPose(Axis.ZP.rotationDegrees(75.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(20.0F));
            }
        }
    }
}
