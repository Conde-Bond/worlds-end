package com.example.worldsend;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Purely cosmetic entity. It:
 *  - carries the BlockState of the deleted block (synced to clients),
 *  - moves linearly along a fixed radial direction tilted ~30° upward
 *    (no gravity, no physics),
 *  - travels 5 blocks at full scale (FULL_TICKS),
 *  - then shrinks linearly to zero over the next 5 blocks (FADE_TICKS),
 *  - then discards itself. It is never saved with the world.
 *
 * Pool accounting is a plain COUNTER, not a collection of references:
 * the removal hook (setRemoved) decrements it for every way an entity can
 * leave the world — finished, chunk unloaded, world closed — so the pool
 * can never jam on stale entries. It is also reset on server start.
 */
public class DriftingBlockEntity extends Entity {

    private static final EntityDataAccessor<BlockState> DATA_STATE =
            SynchedEntityData.defineId(DriftingBlockEntity.class, EntityDataSerializers.BLOCK_STATE);

    public static final int FULL_TICKS = 20;  // 5 blocks at full scale
    public static final int FADE_TICKS = 20;  // 5 blocks shrinking to zero
    public static final double SPEED = 0.25;  // blocks per tick

    /** Max concurrent drifting blocks; when full, NEW spawns are skipped
     *  (backpressure) — existing animations always complete their arc. */
    public static final int MAX_LIVE = 1024;
    /** Count of live animations (server side). */
    private static int liveCount = 0;

    /** Whether this instance is counted in the pool. */
    private boolean pooled = false;

    private int age;

    /** Upward tilt of the drift, ~30° above horizontal. */
    private static final double UPWARD_TILT = Math.tan(Math.toRadians(30));

    public DriftingBlockEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static ResourceKey<EntityType<?>> typeKey() {
        return ResourceKey.create(Registries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(WorldsEndMod.MOD_ID, "drifting_block"));
    }

    /** Called on server start so stale counts never survive world changes. */
    public static void resetPool() {
        liveCount = 0;
    }

    public static void spawn(ServerLevel level, BlockPos pos, BlockState state, Vec3 direction) {
        if (liveCount >= MAX_LIVE) {
            return; // pool full: skip this animation rather than cull an old one
        }

        DriftingBlockEntity e = new DriftingBlockEntity(WorldsEndMod.DRIFTING_BLOCK, level);
        e.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        Vec3 tilted = new Vec3(direction.x(), UPWARD_TILT, direction.z()).normalize();
        e.setDeltaMovement(tilted.scale(SPEED));
        e.getEntityData().set(DATA_STATE, state);
        e.pooled = true;
        liveCount++;
        level.addFreshEntity(e);
    }

    /** Called by the ENTITY_UNLOAD event when this entity leaves the world. */
    public void onPoolExit() {
        if (this.pooled) {
            this.pooled = false;
            liveCount--;
        }
    }

    public BlockState getVisualBlockState() {
        return this.getEntityData().get(DATA_STATE);
    }

    /** 1.0 during the first 5 blocks, then linear fade to 0. */
    public float getScale(float partialTicks) {
        float t = this.age + partialTicks;
        if (t <= FULL_TICKS) return 1.0f;
        return Math.max(0.0f, 1.0f - (t - FULL_TICKS) / (float) FADE_TICKS);
    }

    @Override
    public void tick() {
        super.tick();
        // Pure linear motion — no gravity, no drag, no collision.
        this.setPos(this.position().add(this.getDeltaMovement()));
        this.age++;
        if (!this.level().isClientSide() && this.age >= FULL_TICKS + FADE_TICKS) {
            this.discard();
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_STATE, Blocks.STONE.defaultBlockState());
    }

    // Never persist to disk; these are throwaway visuals.
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {}

    @Override
    protected void readAdditionalSaveData(ValueInput input) {}

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }
}