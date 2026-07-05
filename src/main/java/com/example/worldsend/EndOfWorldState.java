package com.example.worldsend;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Persistent state for the shrinking border.
 *
 * The border starts at the world edge and shrinks according to the speed
 * schedule in the config file (config/worlds-end.json). Thanks to lazy
 * deletion, a huge radius costs nothing — the radius is just a number until
 * it reaches loaded chunks, where the ring sweep and wipe layers enforce it.
 *
 * "paused" freezes the border's movement only; chunks outside the frozen
 * radius are still wiped when observed (see ShrinkController).
 */
public class EndOfWorldState extends SavedData {

    /** Starting radius: the world border (~30 million blocks from origin). */
    public static final double START_RADIUS = 30_000_000.0;

    private boolean active;
    private boolean paused;
    private double radius;

    // "paused" is optional so saved data from before the field existed
    // still decodes (older test worlds only have active + radius).
    public static final Codec<EndOfWorldState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("active").forGetter(s -> s.active),
            Codec.BOOL.optionalFieldOf("paused", false).forGetter(s -> s.paused),
            Codec.DOUBLE.fieldOf("radius").forGetter(s -> s.radius)
    ).apply(i, EndOfWorldState::new));

    public static final SavedDataType<EndOfWorldState> TYPE =
            new SavedDataType<>(Identifier.fromNamespaceAndPath(WorldsEndMod.MOD_ID, "state"),
                    EndOfWorldState::new, CODEC, null);

    public EndOfWorldState() {
        this(false, false, START_RADIUS);
    }

    private EndOfWorldState(boolean active, boolean paused, double radius) {
        this.active = active;
        this.paused = paused;
        this.radius = radius;
    }

    public static EndOfWorldState get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Current shrink speed in blocks/tick for a radius, from the config. */
    public static double shrinkPerTick(double radius) {
        WorldsEndConfig config = WorldsEndConfig.get();
        for (WorldsEndConfig.SpeedStep step : config.speedSchedule) {
            if (radius > step.aboveRadius) {
                return step.blocksPerTick * config.speedMultiplier;
            }
        }
        // Radius at or below the smallest row: use the last (slowest) step.
        WorldsEndConfig.SpeedStep last =
                config.speedSchedule.get(config.speedSchedule.size() - 1);
        return last.blocksPerTick * config.speedMultiplier;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPaused() {
        return paused;
    }

    public void begin() {
        this.active = true;
        this.paused = false;
        this.radius = START_RADIUS;
        setDirty();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        setDirty();
    }

    public double radius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = radius;
        setDirty();
    }
}