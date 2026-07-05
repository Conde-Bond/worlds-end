package com.example.worldsend;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Border logic, v4.4.
 *
 * Static state (queues, boss bars) is JVM-scoped, so reset() must be called
 * on server start (see WorldsEndMod) or it leaks between worlds. Boss bars
 * also re-bind themselves when the player instance changes (respawn,
 * dimension travel, world reload).
 */
public final class ShrinkController {

    /** Blocks/tick for the shrinking ring. */
    private static final int RING_BUDGET_PER_TICK = 8192;
    /** Blocks/tick for queued chunk wipes. */
    private static final int WIPE_BUDGET_PER_TICK = 16384;
    /** Max drifting-block animations SPAWNED per tick. */
    private static final int ANIMATION_BUDGET_PER_TICK = 96;
    /** Max animated blocks per column (spreads the budget across the face). */
    private static final int ANIMATIONS_PER_COLUMN = 3;
    /** Max break sounds per tick. */
    private static final int SOUND_BUDGET_PER_TICK = 64;
    /** Animate only within this horizontal distance of some player. */
    private static final double ANIMATION_PLAYER_RANGE = 64.0;
    /** ...and only within this many blocks of that player's Y level. */
    private static final double ANIMATION_VERTICAL_RANGE = 16.0;
    /** How often (ticks) to audit chunks around players. */
    private static final int AUDIT_INTERVAL = 60;
    /** Boss bar fill starts draining when the border is this close to you. */
    private static final double BOSS_BAR_FULL_DISTANCE = 10_000.0;

    private static final Deque<ChunkPos> pendingChunkWipes = new ArrayDeque<>();
    /** "Currently in queue" marker (prevents duplicates, cleared on pop). */
    private static final Set<ChunkPos> queuedWipeKeys = new HashSet<>();
    private static final Map<UUID, ServerBossEvent> bossBars = new HashMap<>();
    private static int animationsLeft;
    private static int soundsLeft;

    private record Column(LevelChunk chunk, int x, int z, double dist) {}

    private ShrinkController() {}

    /** Called on server start: clears all JVM-scoped state. */
    public static void reset() {
        pendingChunkWipes.clear();
        queuedWipeKeys.clear();
        bossBars.values().forEach(ServerBossEvent::removeAllPlayers);
        bossBars.clear();
    }

    public static void tick(ServerLevel level, EndOfWorldState state) {
        BlockPos spawn = level.getRespawnData().pos();
        double cx = spawn.getX() + 0.5;
        double cz = spawn.getZ() + 0.5;

        animationsLeft = ANIMATION_BUDGET_PER_TICK;
        soundsLeft = SOUND_BUDGET_PER_TICK;
        int viewRadius = level.getServer().getPlayerList().getViewDistance() + 1;

        // ---- HUD: per-player boss bars (only sends when the text changes) --
        updateBossBars(level, state, cx, cz);

        // ---- Layer 1: the shrinking ring, discovered around players -------
        double rOld = state.radius();
        if (rOld > 0) {
            double rNew = Math.max(0.0, rOld - EndOfWorldState.shrinkPerTick(rOld));

            Map<ChunkPos, LevelChunk> candidates = new HashMap<>();
            for (ServerPlayer player : level.players()) {
                ChunkPos center = player.chunkPosition();
                for (int dx = -viewRadius; dx <= viewRadius; dx++) {
                    for (int dz = -viewRadius; dz <= viewRadius; dz++) {
                        int chX = center.x() + dx;
                        int chZ = center.z() + dz;
                        ChunkPos pos = new ChunkPos(chX, chZ);
                        if (candidates.containsKey(pos)) continue;
                        if (!chunkIntersectsRing(chX, chZ, cx, cz, rNew, rOld)) continue;
                        LevelChunk chunk = level.getChunkSource().getChunkNow(chX, chZ);
                        if (chunk != null) {
                            candidates.put(pos, chunk);
                        }
                    }
                }
            }

            List<Column> band = new ArrayList<>();
            for (Map.Entry<ChunkPos, LevelChunk> entry : candidates.entrySet()) {
                int baseX = entry.getKey().x() << 4;
                int baseZ = entry.getKey().z() << 4;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int x = baseX + lx;
                        int z = baseZ + lz;
                        double dist = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                        if (dist > rNew && dist <= rOld) {
                            band.add(new Column(entry.getValue(), x, z, dist));
                        }
                    }
                }
            }

            band.sort(Comparator.comparingDouble(Column::dist).reversed());

            // Clear outermost-first; if the budget runs out mid-band, the
            // radius still advances to the outermost UNCLEARED column —
            // everything beyond it is already gone. The border thus glides
            // at the pace the budget actually achieves instead of freezing
            // for a whole scheduled step and then lurching.
            double frontier = rNew;
            int budget = RING_BUDGET_PER_TICK;
            for (Column column : band) {
                if (budget <= 0) {
                    frontier = column.dist();
                    break;
                }
                budget -= clearColumn(level, column.chunk(), column.x(), column.z(), cx, cz);
            }
            state.setRadius(frontier);
        }

        // ---- Layer 3: audit chunks around players --------------------------
        if (level.getGameTime() % AUDIT_INTERVAL == 0) {
            for (ServerPlayer player : level.players()) {
                ChunkPos center = player.chunkPosition();
                for (int dx = -viewRadius; dx <= viewRadius; dx++) {
                    for (int dz = -viewRadius; dz <= viewRadius; dz++) {
                        int chX = center.x() + dx;
                        int chZ = center.z() + dz;
                        if (chunkFullyInside(chX, chZ, cx, cz, state.radius())) continue;
                        if (level.getChunkSource().getChunkNow(chX, chZ) == null) continue;
                        enqueueWipe(new ChunkPos(chX, chZ));
                    }
                }
            }
        }

        // ---- Layer 2: process the wipe queue -------------------------------
        int wipeBudget = WIPE_BUDGET_PER_TICK;
        while (wipeBudget > 0 && !pendingChunkWipes.isEmpty()) {
            ChunkPos pos = pendingChunkWipes.peekFirst();
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk == null) {
                pendingChunkWipes.pollFirst();
                queuedWipeKeys.remove(pos);
                continue;
            }
            int removed = wipeChunkOutside(level, chunk, cx, cz, state.radius(), wipeBudget);
            if (removed >= wipeBudget) {
                wipeBudget = 0; // resume this chunk next tick
            } else {
                wipeBudget -= removed;
                pendingChunkWipes.pollFirst();
                queuedWipeKeys.remove(pos);
            }
        }
    }

    // ---- Boss bars --------------------------------------------------------

    private static void updateBossBars(ServerLevel level, EndOfWorldState state,
                                    double cx, double cz) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            online.add(player.getUUID());
            double remaining = Math.max(0.0,
                    state.radius() - Math.hypot(player.getX() - cx, player.getZ() - cz));

            ServerBossEvent bar = bossBars.computeIfAbsent(player.getUUID(), id ->
                    new ServerBossEvent(id, Component.empty(),
                            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS));

            if (!bar.getPlayers().contains(player)) {
                bar.removeAllPlayers();
                bar.addPlayer(player);
            }

            String text = remaining > 0
                    ? String.format("The End is %,d blocks away", (long) remaining)
                    : "The End is here";
            if (!bar.getName().getString().equals(text)) {
                bar.setName(Component.literal(text));
            }

            float progress = (float) Math.min(1.0, remaining / BOSS_BAR_FULL_DISTANCE);
            if (Math.abs(progress - bar.getProgress()) > 0.001f) {
                bar.setProgress(progress);
            }
        }
        bossBars.entrySet().removeIf(entry -> {
            if (!online.contains(entry.getKey())) {
                entry.getValue().removeAllPlayers();
                return true;
            }
            return false;
        });
    }

    // ---- Deletion plumbing --------------------------------------------------

    public static void onChunkLoad(ServerLevel level, EndOfWorldState state, ChunkPos pos) {
        BlockPos spawn = level.getRespawnData().pos();
        double cx = spawn.getX() + 0.5, cz = spawn.getZ() + 0.5;
        if (!chunkFullyInside(pos.x(), pos.z(), cx, cz, state.radius())) {
            enqueueWipe(pos);
        }
    }

    private static void enqueueWipe(ChunkPos pos) {
        if (queuedWipeKeys.add(pos)) {
            pendingChunkWipes.addLast(pos);
        }
    }

    /** Nearest player within ANIMATION_PLAYER_RANGE horizontally, or null. */
    private static ServerPlayer nearestPlayer(ServerLevel level, double x, double z) {
        ServerPlayer best = null;
        double bestSq = ANIMATION_PLAYER_RANGE * ANIMATION_PLAYER_RANGE;
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            double sq = dx * dx + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = player;
            }
        }
        return best;
    }

    /** True if this block Y is worth animating for the given viewer. */
    private static boolean visibleTo(ServerPlayer viewer, double y) {
        return viewer != null && Math.abs(y - viewer.getY()) <= ANIMATION_VERTICAL_RANGE;
    }

    /**
     * Animates a random sample (up to ANIMATIONS_PER_COLUMN and the global
     * per-tick budget) of the eligible blocks collected from one column.
     * The lists are consumed.
     */
    private static void animateColumnSample(ServerLevel level,
                                            List<BlockPos> positions,
                                            List<BlockState> states,
                                            Vec3 dir) {
        int quota = Math.min(ANIMATIONS_PER_COLUMN, animationsLeft);
        while (quota > 0 && !positions.isEmpty()) {
            int pick = level.getRandom().nextInt(positions.size());
            BlockPos pos = positions.remove(pick);
            BlockState blockState = states.remove(pick);
            animateRemoval(level, pos, blockState, dir);
            quota--;
        }
    }

    /** Drifting-block visual + (budgeted) break sound for one deleted block. */
    private static void animateRemoval(ServerLevel level, BlockPos pos,
                                       BlockState state, Vec3 dir) {
        DriftingBlockEntity.spawn(level, pos, state, dir);
        animationsLeft--;
        if (soundsLeft > 0) {
            SoundType soundType = state.getSoundType();
            level.playSound(null, pos, soundType.getBreakSound(), SoundSource.BLOCKS,
                    (soundType.getVolume() + 1.0f) / 2.0f, soundType.getPitch() * 0.8f);
            soundsLeft--;
        }
    }

    /**
     * Removes every block with dist > r in this chunk, skipping all-air
     * sections. Stops once `budget` blocks were removed; the caller detects
     * that case (removed >= budget) and retries next tick.
     */
    private static int wipeChunkOutside(ServerLevel level, LevelChunk chunk,
                                        double cx, double cz, double r, int budget) {
        int removed = 0;
        LevelChunkSection[] sections = chunk.getSections();
        ChunkPos cp = chunk.getPos();
        int baseX = cp.x() << 4;
        int baseZ = cp.z() << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<BlockPos> eligiblePos = new ArrayList<>();
        List<BlockState> eligibleState = new ArrayList<>();

        for (int i = 0; i < sections.length && removed < budget; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) continue;
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));

            for (int lx = 0; lx < 16 && removed < budget; lx++) {
                for (int lz = 0; lz < 16 && removed < budget; lz++) {
                    int x = baseX + lx;
                    int z = baseZ + lz;
                    double dist = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                    if (dist <= r) continue;

                    ServerPlayer viewer = animationsLeft > 0
                            ? nearestPlayer(level, x + 0.5, z + 0.5) : null;
                    Vec3 dir = new Vec3(x + 0.5 - cx, 0, z + 0.5 - cz).normalize();
                    eligiblePos.clear();
                    eligibleState.clear();

                    for (int ly = 0; ly < 16; ly++) {
                        BlockState old = section.getBlockState(lx, ly, lz);
                        if (old.isAir()) continue;
                        int y = baseY + ly;
                        pos.set(x, y, z);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        removed++;
                        if (visibleTo(viewer, y) && !old.hasBlockEntity()) {
                            eligiblePos.add(pos.immutable());
                            eligibleState.add(old);
                        }
                    }
                    animateColumnSample(level, eligiblePos, eligibleState, dir);
                }
            }
        }
        return removed;
    }

    /** Ring-sweep column clear (full height, sampled animation). */
    private static int clearColumn(ServerLevel level, LevelChunk chunk,
                                   int x, int z, double cx, double cz) {
        int removed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinY();
        int maxY = level.getMaxY();

        ServerPlayer viewer = animationsLeft > 0
                ? nearestPlayer(level, x + 0.5, z + 0.5) : null;
        Vec3 dir = new Vec3(x + 0.5 - cx, 0, z + 0.5 - cz).normalize();
        List<BlockPos> eligiblePos = new ArrayList<>();
        List<BlockState> eligibleState = new ArrayList<>();

        for (int y = minY; y < maxY; y++) {
            pos.set(x, y, z);
            BlockState old = chunk.getBlockState(pos);
            if (old.isAir()) continue;

            level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            removed++;

            if (visibleTo(viewer, y) && !old.hasBlockEntity()) {
                eligiblePos.add(pos.immutable());
                eligibleState.add(old);
            }
        }
        animateColumnSample(level, eligiblePos, eligibleState, dir);
        return removed;
    }

    // ---- Circle/box intersection helpers ---------------------------------

    private static boolean chunkIntersectsRing(int chX, int chZ, double cx, double cz,
                                               double rInner, double rOuter) {
        return boxMaxDist(chX, chZ, cx, cz) > rInner && boxMinDist(chX, chZ, cx, cz) <= rOuter;
    }

    private static boolean chunkFullyInside(int chX, int chZ, double cx, double cz, double r) {
        return boxMaxDist(chX, chZ, cx, cz) <= r;
    }

    private static double boxMinDist(int chX, int chZ, double cx, double cz) {
        double x0 = chX << 4, x1 = x0 + 16, z0 = chZ << 4, z1 = z0 + 16;
        double dx = Math.max(0, Math.max(x0 - cx, cx - x1));
        double dz = Math.max(0, Math.max(z0 - cz, cz - z1));
        return Math.hypot(dx, dz);
    }

    private static double boxMaxDist(int chX, int chZ, double cx, double cz) {
        double x0 = chX << 4, x1 = x0 + 16, z0 = chZ << 4, z1 = z0 + 16;
        double dx = Math.max(Math.abs(x0 - cx), Math.abs(x1 - cx));
        double dz = Math.max(Math.abs(z0 - cz), Math.abs(z1 - cz));
        return Math.hypot(dx, dz);
    }
}