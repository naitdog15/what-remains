package com.temog.notminelegacy.whatremains.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownExperienceBottle;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;

/**
 * The one gesture message of the Bottle o' Enchanting rework (LB-03 plan D6).
 *
 * <p>The client decides <em>which</em> gesture happened — sneak deposit, the second click of the
 * accident-guard throw, or (V2.1) a sneak orb vacuum — and states the modifier explicitly. The
 * server decides whether that gesture is legal, re-reading hand, slot and stack from live state;
 * nothing on the wire is trusted beyond being a hint about what the player meant.
 *
 * <p><b>V2.1 orb vacuum.</b> {@link Action#VACUUM} is the third gesture: sneak + use pulls nearby
 * {@link net.minecraft.world.entity.ExperienceOrb}s straight into the held bottle instead of the
 * player's own XP bar. It is reachable from a non-full {@code whatremains:xp_bottle} (XPB-06: a
 * plain glass bottle no longer qualifies — see the crafted-vessel gate below), and takes priority
 * over the ordinary deposit-from-the-player's-balance gesture whenever at least one orb actually
 * fits — see {@link #vacuum} and {@link XpVessel#vacuumOrbsInto}.
 *
 * <p><b>XPB-06 crafted-vessel gate.</b> Every action (DEPOSIT/THROW/VACUUM) now requires the hand
 * to hold {@link XpVessel#isXpBottle}; a bare {@code minecraft:glass_bottle} or a plain
 * {@code minecraft:experience_bottle} is rejected outright by {@link #validateAndRun} before any
 * gesture-specific logic runs. This is the fix for the balance problem the whole round exists to
 * close: depositing was previously reachable from any turn-1-craftable glass bottle.
 *
 * <p>Carrying the sneak flag is the entire reason this message exists. Vanilla's use packet is
 * handled before the entity tick that syncs {@code PRESS_SHIFT_KEY}, so a Shift+RMB issued on the
 * same client tick as the arming click reaches the server looking un-sneaked — and could throw a
 * full bottle instead of treating the click as a deposit gesture and rejecting it.
 *
 * <p><b>Rate limit (fix round F4).</b> The client-side accident guard (plan D6) only throttles a
 * single honest client; nothing stops a modified client, or one replaying captured packets, from
 * spamming legible gestures far faster than any button press could produce. {@link #validateAndRun}
 * therefore rejects — silently, no sound, so a rejection itself cannot be used as a spam-pacing
 * signal — any gesture landing within {@code xpBottleGestureRateLimitTicks} (config, LB-03; 0
 * disables the rate limit) of that player's last ACCEPTED one.
 *
 * <p><b>V2 orb manifest.</b> Every mutation this class performs — deposit, the creative free-top-up
 * shortcut, and every stack this class strips or re-clamps before letting it leave the player's
 * control — goes through {@link XpVessel}'s scalar accessors ({@code getStoredXp} /
 * {@code setStoredXp}), never a raw NBT read or write on the XP keys.
 * That is deliberate: {@code XpVessel#setStoredXp} owns regenerating {@value XpVessel#TAG_ORBS}
 * (and dropping it alongside {@value XpVessel#TAG_STORED} at zero) end to end, so this class needs
 * no manifest-specific logic of its own — a stack this class zeroes or re-clamps ends up with a
 * correct, normalized manifest (or none) for exactly the same reason a stack it never touches does.
 */
@Mod.EventBusSubscriber(modid = WhatRemains.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerboundXpBottleGesture {

    /**
     * Marks a projectile stack whose payout has already been decided, including deliberately
     * deciding it is zero (a creative throw, plan D9). Only ever written onto the copy handed to the
     * projectile — never onto an inventory stack — so an unresolved vanilla bottle stays
     * distinguishable from one that is honestly worth nothing.
     *
     * <p>Read back by {@code ThrownExperienceBottleMixin}; a compile-time constant, so the mixin
     * inlines the literal rather than classloading this packet.
     */
    public static final String TAG_RESOLVED = "Resolved";

    /**
     * Game time of each player's last ACCEPTED gesture (fix round F4). Cleared on logout by
     * {@link #onPlayerLoggedOut} so a UUID never lingers past its session — the client-side
     * equivalent, {@code XpBottleGestureHandler}'s arm state, is disarmed on the client's own
     * {@code ClientPlayerNetworkEvent.LoggingOut}; this is the server-side mirror of that cleanup.
     */
    private static final Map<UUID, Long> LAST_ACCEPTED_GAME_TIME = new ConcurrentHashMap<>();

    public enum Action {
        /** Sneak + use: bank the player's XP; an already-full bottle is explicitly rejected. */
        DEPOSIT,
        /** Second click of the non-sneak accident guard: actually throw the bottle. */
        THROW,
        /**
         * Sneak + use (V2.1): pull nearby orbs into the held xp_bottle instead of the player's own
         * XP bar. Appended last so the two existing ordinals never shift — the wire format is
         * {@link FriendlyByteBuf#writeEnum}/{@code readEnum}, which serialises by ordinal.
         */
        VACUUM
    }

    private final Action action;
    private final InteractionHand hand;
    private final int slot;
    private final boolean sneaking;

    public ServerboundXpBottleGesture(Action action, InteractionHand hand, int slot, boolean sneaking) {
        this.action = action;
        this.hand = hand;
        this.slot = slot;
        this.sneaking = sneaking;
    }

    // ── Wire format ─────────────────────────────────────────────────────────

    public static void encode(ServerboundXpBottleGesture message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.action);
        buffer.writeEnum(message.hand);
        buffer.writeVarInt(message.slot);
        buffer.writeBoolean(message.sneaking);
    }

    public static ServerboundXpBottleGesture decode(FriendlyByteBuf buffer) {
        return new ServerboundXpBottleGesture(
                buffer.readEnum(Action.class),
                buffer.readEnum(InteractionHand.class),
                buffer.readVarInt(),
                buffer.readBoolean()
        );
    }

    // ── Server handling ─────────────────────────────────────────────────────

    public static void handle(ServerboundXpBottleGesture message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        // consumerMainThread already enqueues this handler on the server thread; a second
        // enqueueWork here would defer the gesture by a tick for no reason.
        validateAndRun(message, context.getSender());
        context.setPacketHandled(true);
    }

    /**
     * Every precondition is re-derived from live server state: the hand named on the wire must
     * really be holding a Bottle o' Enchanting, and the slot named must really be the one that hand
     * reads from. A client that lies about either is simply ignored.
     */
    private static void validateAndRun(ServerboundXpBottleGesture message, ServerPlayer player) {
        if (player == null || message.action == null || message.hand == null) return;
        // Master switch: reject any gesture that arrives anyway — a stale client racing the
        // config-sync window, a modified client, or a captured/replayed packet. There is no
        // partial-credit path here; the whole message is simply ignored.
        if (!XpVessel.enabled()) return;
        if (player.isRemoved() || !player.isAlive() || player.isSpectator()) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        ItemStack held = player.getItemInHand(message.hand);
        // XPB-06: every action requires the crafted vessel — a bare glass bottle (and a plain
        // vanilla experience_bottle, now 100% vanilla again) no longer qualifies for any of them.
        if (!XpVessel.isXpBottle(held)) return;

        int liveSlot = message.hand == InteractionHand.MAIN_HAND
                ? player.getInventory().selected
                : Inventory.SLOT_OFFHAND;
        if (message.slot != liveSlot) return;

        // Rate limit (fix round F4), checked after every "is this even a legible gesture" guard
        // above but before it is allowed to do anything — so only genuinely-actionable spam is
        // throttled, and a throttled gesture never updates the timestamp itself. The timestamp is
        // written below, after dispatch, only when the gesture was actually ACCEPTED — a
        // domain-rejected gesture (gate off, 0 XP available, cancelled XP event) must not itself
        // become a spam-pacing signal, mirroring GreatBottleGesturePacket#validateAndRun.
        // gestureRateLimitTicks == 0 disables the rate limit outright (config, LB-03).
        int rateLimitTicks = WhatRemainsConfig.xpBottleGestureRateLimitTicks;
        long now = level.getGameTime();
        if (rateLimitTicks > 0) {
            Long lastAccepted = LAST_ACCEPTED_GAME_TIME.get(player.getUUID());
            if (lastAccepted != null && now - lastAccepted < rateLimitTicks) return;
        }

        boolean accepted = switch (message.action) {
            // A deposit is only ever produced by a sneaking press. The flag is what makes that
            // knowable at all on this side, so an un-sneaked DEPOSIT is a malformed request.
            case DEPOSIT -> message.sneaking && deposit(player, message.hand, held);
            case THROW -> throwBottle(player, message.hand, held);
            // Same rule as DEPOSIT: a vacuum is only ever produced by a sneaking press.
            case VACUUM -> message.sneaking && vacuum(player, message.hand, held);
        };

        // Only an ACCEPTED gesture updates the timestamp — a rejected one must not itself become a
        // spam-pacing signal (same rule as the vanilla bottle's rate limit).
        if (accepted) {
            LAST_ACCEPTED_GAME_TIME.put(player.getUUID(), now);
        }
    }

    /**
     * Drops the departing player's rate-limit entry (fix round F4) rather than let the UUID
     * linger for the rest of the server's lifetime.
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_ACCEPTED_GAME_TIME.remove(event.getEntity().getUUID());
    }

    // ── Deposit ─────────────────────────────────────────────────────────────

    private static boolean deposit(ServerPlayer player, InteractionHand hand, ItemStack held) {
        if (XpVessel.isFull(held)) {
            reject(player);
            return false;
        }

        if (!WhatRemainsConfig.xpBottleAllowDeposit) {
            reject(player);
            return false;
        }

        ServerLevel level = player.serverLevel();
        boolean creative = player.getAbilities().instabuild;

        // Refuse before touching anything. A rejected gesture must leave the inventory bit-identical.
        if (!creative && XpVessel.playerTotalXp(player) <= 0L) {
            reject(player);
            return false;
        }

        boolean split = held.getCount() > 1;
        ItemStack target = split ? held.copyWithCount(1) : held;

        // Split-before-mutate (plan D2.2): a partial claim may never be written onto a shared stack.
        //
        // Deposit rule (fix round F3): depositing into an UNRESOLVED (untagged) bottle starts it
        // from stored=0 — any mystery value it might have rolled to is forfeited, never baked here.
        // Before inventory withdrawal was removed, rolling on deposit was a slow XP printer:
        // untagged stack (count >= 2) -> deposit (bakes a free 6..22 roll) -> withdraw -> bottle
        // empties back to untagged -> re-merges with the rest of the stack -> repeat, netting
        // ~5.5 XP/cycle for nothing. Mystery value is ONLY realized by throwing
        // (ThrownExperienceBottleMixin's impact resolver, plan D10) — trade- and loot-sourced
        // bottles are pre-rolled at source (XpBottleTradeRoller / XpBottleLootModifier), so the
        // common case a player actually finds still arrives with a real value already baked in.
        //
        // Banking itself routes through XpVessel#setStoredXp (called internally by
        // depositIntoBottle below), which regenerates StoredOrbs to match the new scalar — this
        // class never authors or touches the manifest directly (V2).
        int moved = XpVessel.depositIntoBottle(player, target,
                WhatRemainsConfig.xpBottleStoreUntilPreviousLevel);
        if (moved <= 0) {
            // Nothing actually left the player (a cancelling PlayerXpEvent.XpChange listener, say).
            reject(player);
            return false;
        }

        if (split) {
            ItemStack remainder = held.copy();
            remainder.shrink(1);
            // The filled bottle stays in hand — it is the one the player acted on. Relocating the
            // unfilled remainder lets it merge back into compatible bottles rather than fragmenting
            // the inventory.
            player.setItemInHand(hand, target);
            giveOrDrop(player, remainder);
        }

        float fAfter = XpVessel.fillFraction(target);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.42F, 0.86F + 0.24F * fAfter);
        playNextTick(level, player, 0.10F, 1.00F + 0.35F * fAfter);
        depositParticles(level, player, hand, moved);
        return true;
    }

    /**
     * The one refusal cue. Sent to the acting player only — a gesture that did nothing is not an
     * event anyone else needs to hear.
     */
    private static void reject(ServerPlayer player) {
        player.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.18F, 1.35F);
    }

    // ── Vacuum (V2.1) ───────────────────────────────────────────────────────

    /**
     * Pulls nearby {@code ExperienceOrb}s into {@code held} instead of the player's own XP bar
     * (V2.1 orb vacuum). XPB-06: reachable only from a non-full {@code xp_bottle} — a bare glass
     * bottle is rejected upstream in {@link #validateAndRun}. A FULL bottle is not this gesture's
     * to handle: it has no room, and the honest client falls through to DEPOSIT so the server can
     * emit the explicit rejection cue. A malformed direct VACUUM request for one returns false
     * without calling {@link #reject}, matching {@code validateAndRun}'s silent-ignore treatment
     * of malformed requests.
     * <p>
     * Split-before-mutate (plan D2.2) applies exactly as it does in {@link #deposit}: a partial claim
     * may never be written onto a shared stack — an actual split is only needed when the stack holds
     * more than one.
     *
     * @return true when at least one orb was captured
     */
    private static boolean vacuum(ServerPlayer player, InteractionHand hand, ItemStack held) {
        if (!XpVessel.vacuumEnabled()) {
            reject(player);
            return false;
        }

        ServerLevel level = player.serverLevel();

        if (XpVessel.isFull(held)) return false;
        boolean split = held.getCount() > 1;
        ItemStack target = split ? held.copyWithCount(1) : held;

        // Refuse before touching anything else. A failed gesture must leave the inventory
        // bit-identical — nothing has been written to `held` yet at this point either way, since
        // the split case's `target` is its own separate stack, and the no-split case only ever
        // gets mutated below, after this guard.
        int captured = XpVessel.vacuumOrbsInto(player, target);
        if (captured <= 0) {
            reject(player);
            return false;
        }

        if (split) {
            ItemStack remainder = held.copy();
            remainder.shrink(1);
            // The filled bottle stays in hand — it is the one the player acted on. Reuses
            // giveOrDrop rather than reimplementing deposit()'s own give-or-drop remainder handling.
            player.setItemInHand(hand, target);
            giveOrDrop(player, remainder);
        }

        // Same cue as a successful deposit (BOTTLE_FILL, then the EXPERIENCE_ORB_PICKUP follow-up
        // one tick later via playNextTick) — the player's ear should not be able to tell a vacuum
        // apart from an ordinary deposit; only the source of the XP differs.
        float fAfter = XpVessel.fillFraction(target);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.42F, 0.86F + 0.24F * fAfter);
        playNextTick(level, player, 0.10F, 1.00F + 0.35F * fAfter);
        depositParticles(level, player, hand, captured);
        return true;
    }

    // ── Throw ───────────────────────────────────────────────────────────────

    private static boolean throwBottle(ServerPlayer player, InteractionHand hand, ItemStack held) {
        ServerLevel level = player.serverLevel();
        boolean creative = player.getAbilities().instabuild;

        ItemStack projectileStack = held.copyWithCount(1);

        // XPB-07: a partial stack with count > 1 is no longer stripped here. Pre-XPB-07 this was a
        // belt-and-braces defence against an op-created "illegal" shared partial claim — but a
        // partial stack is no longer axiomatically illegal (see XpVessel#maxStackFor and
        // XpBottleNormalizer's class javadoc): every unit in ANY legally-sized stack, partial or
        // full, honestly cost being deposited/vacuumed/found to its exact value, so throwing one
        // off the top and having it pay that real value is correct, not an exploit — exactly the
        // same reasoning that already let a full stack of 16 pay out 16 times over.

        // plan D9 / creativeThrowPaysZero (LB-03 config): a creative throw prints nothing into a
        // shared world BY DEFAULT. Resolved with no claim is precisely how the impact resolver
        // tells "worth nothing on purpose" from "never rolled". When the gate is off, a creative
        // throw is treated exactly like a survival one below — it pays out whatever is really
        // stored, if anything.
        if (creative && WhatRemainsConfig.xpBottleCreativeThrowPaysZero) {
            XpVessel.setStoredXp(projectileStack, 0);
            projectileStack.getOrCreateTag().putBoolean(TAG_RESOLVED, true);
        } else if (hasStoredClaim(projectileStack)) {
            // Re-clamp on the way out so a hand-edited claim cannot reach payout even through the
            // projectile's own saved copy. getStoredXp already clamps to [0, capacity()] on read,
            // and normalizeChunks is the actual gate stopping a forged StoredOrbs manifest from
            // ever reaching orb spawning at impact (E-08) — this call is the belt to that braces: it
            // re-derives the scalar through that same clamp and, because it routes through
            // setStoredXp rather than a raw tag write, regenerates a freshly normalized manifest to
            // match, so the projectile leaves carrying chunk values that have already been through
            // the gate at least once (V2).
            XpVessel.setStoredXp(projectileStack, XpVessel.getStoredXp(projectileStack));
            projectileStack.getOrCreateTag().putBoolean(TAG_RESOLVED, true);
        }
        // An untagged bottle is left untagged on purpose: it is unresolved, and rolls its 6..22 at
        // impact (plan D10). Rolling it here would be wrong for the dispenser path, which hands over
        // the whole source stack rather than one bottle.

        ThrownExperienceBottle bottle = new ThrownExperienceBottle(level, player);
        bottle.setItem(projectileStack);
        bottle.shootFromRotation(player, player.getXRot(), player.getYRot(), -20.0F, 0.7F, 1.0F);
        level.addFreshEntity(bottle);

        // Vanilla's exact envelope (ExperienceBottleItem#use). Only the server plays it now — the
        // client never reaches use(), so there is no prediction to double up with.
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.EXPERIENCE_BOTTLE_THROW, SoundSource.NEUTRAL,
                0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));

        player.awardStat(Stats.ITEM_USED.get(held.getItem()));
        if (!creative) {
            held.shrink(1);
        }
        // No rejection path exists below the live-state checks in validateAndRun — a THROW that
        // reaches here always goes through, so it is always the accepted gesture for stamping.
        return true;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * True when this stack carries an explicit stored-XP claim. Distinct from {@code stored > 0}:
     * an untagged bottle is "never rolled", which is not the same state as a bottle that is empty
     * because something drained it.
     */
    private static boolean hasStoredClaim(ItemStack stack) {
        return XpVessel.hasStoredXpTag(stack);
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;

        // Inventory#add mutates the count in place and reports true even for a PARTIAL add, so the
        // remainder has to be re-checked rather than trusting the boolean — trusting it deletes
        // whatever did not fit.
        player.getInventory().add(stack);
        if (stack.isEmpty()) return;

        ItemEntity dropped = player.drop(stack, false);
        if (dropped != null) {
            dropped.setNoPickUpDelay();
            dropped.setTarget(player.getUUID());
        }
    }

    /** Second layer of the successful deposit/vacuum cue, one tick behind the glass sound. */
    private static void playNextTick(ServerLevel level, ServerPlayer player, float volume, float pitch) {
        MinecraftServer server = level.getServer();
        server.tell(new TickTask(server.getTickCount() + 1, () -> {
            if (player.isRemoved()) return;
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, volume, pitch);
        }));
    }

    // ── Transfer particles (design proposal §3) ─────────────────────────────

    /** Glyphs start in the torso volume and curl into the held bottle. */
    private static void depositParticles(ServerLevel level, ServerPlayer player, InteractionHand hand, int deltaXp) {
        double u = transferScale(deltaXp);
        RandomSource random = level.getRandom();
        Vec3 handPoint = heldBottlePoint(player, hand);
        Vec3 torso = new Vec3(player.getX(), player.getY() + 1.05D, player.getZ());

        int glyphs = 6 + (int) (6.0D * u);
        for (int i = 0; i < glyphs; i++) {
            Vec3 to = torso.add(insideEllipsoid(random, 0.35D, 0.45D, 0.35D)).subtract(handPoint);
            sendDirected(level, ParticleTypes.ENCHANT, handPoint, to);
        }

        int sparks = 2 + (int) (3.0D * u);
        for (int i = 0; i < sparks; i++) {
            Vec3 at = handPoint.add(insideBox(random, 0.06D, 0.06D, 0.06D));
            Vec3 velocity = new Vec3(
                    spread(random, 0.03D),
                    0.02D + random.nextDouble() * 0.04D,
                    spread(random, 0.03D));
            sendDirected(level, ParticleTypes.HAPPY_VILLAGER, at, velocity);
        }
    }

    private static double transferScale(int deltaXp) {
        return Math.sqrt(Mth.clamp((double) deltaXp / (double) XpVessel.capacity(), 0.0D, 1.0D));
    }

    /**
     * One packet per particle with {@code count = 0}, which is what makes the client treat the
     * vector as a starting displacement/velocity instead of Gaussianising it into a cloud.
     */
    private static void sendDirected(ServerLevel level, ParticleOptions particle, Vec3 at, Vec3 vector) {
        level.sendParticles(particle, at.x, at.y, at.z, 0, vector.x, vector.y, vector.z, 1.0D);
    }

    /** Where the bottle actually sits in the player's hand, for both arms and either handedness. */
    private static Vec3 heldBottlePoint(Player player, InteractionHand hand) {
        boolean onRightSide = (hand == InteractionHand.MAIN_HAND) == (player.getMainArm() == HumanoidArm.RIGHT);
        double side = onRightSide ? 0.22D : -0.22D;

        float yaw = player.getYRot() * ((float) Math.PI / 180F);
        Vec3 right = new Vec3(-Mth.cos(yaw), 0.0D, -Mth.sin(yaw));

        return player.getEyePosition()
                .add(player.getLookAngle().scale(0.35D))
                .add(right.scale(side))
                .add(0.0D, -0.35D, 0.0D);
    }

    private static Vec3 insideEllipsoid(RandomSource random, double rx, double ry, double rz) {
        double x;
        double y;
        double z;
        do {
            x = random.nextDouble() * 2.0D - 1.0D;
            y = random.nextDouble() * 2.0D - 1.0D;
            z = random.nextDouble() * 2.0D - 1.0D;
        } while (x * x + y * y + z * z > 1.0D);
        return new Vec3(x * rx, y * ry, z * rz);
    }

    private static Vec3 insideBox(RandomSource random, double hx, double hy, double hz) {
        return new Vec3(spread(random, hx), spread(random, hy), spread(random, hz));
    }

    private static double spread(RandomSource random, double halfExtent) {
        return (random.nextDouble() * 2.0D - 1.0D) * halfExtent;
    }
}
