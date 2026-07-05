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
 * The border starts at the world edge and shrinks according to a speed
 * schedule keyed on the current radius. Thanks to lazy deletion, a huge
 * radius costs nothing — the radius is just a number until it reaches
 * loaded chunks, where the ring sweep and wipe layers enforce it.
 */
public class EndOfWorldState extends SavedData {

    // ---- Tuning ----------------------------------------------------------
    /** Starting radius: the world border (~30 million blocks from origin). */
    public static final double START_RADIUS = 30_000_000.0;

    /**
     * Speed schedule: { radius above which this speed applies, blocks/tick }.
     * Rows must be in descending radius order. Handy conversion for tuning:
     * minutes to cross a range = distance / speed / 1200.
     * Default Values:
     * { 1_000_000, 500.0  },
     * {   100_000,  50.0  },
     * {    10_000,   5.0  },
     * {     2_000,   0.5  },
     * {         0,   0.05 },
     */
    private static final double[][] SPEED_SCHEDULE = {
            { 1_000_000, 50000.0  },  // 30M -> 1M   : ~48 min
            {   100_000,  500.0  },  // 1M  -> 100k : ~15 min
            {    10_000,   50.0  },  // 100k-> 10k  : ~15 min
            {     2_000,   5.0  },  // 10k -> 2k   : ~13 min
            {         0,   0.05 }, // 2k  -> 0    : ~33 min (the endgame; tune!)
    };
    // -----------------------------------------------------------------------

    private boolean active;
    private double radius;

    public static final Codec<EndOfWorldState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("active").forGetter(s -> s.active),
            Codec.DOUBLE.fieldOf("radius").forGetter(s -> s.radius)
    ).apply(i, EndOfWorldState::new));

    public static final SavedDataType<EndOfWorldState> TYPE =
            new SavedDataType<>(Identifier.fromNamespaceAndPath(WorldsEndMod.MOD_ID, "state"),
                    EndOfWorldState::new, CODEC, null);

    public EndOfWorldState() {
        this(false, START_RADIUS);
    }

    private EndOfWorldState(boolean active, double radius) {
        this.active = active;
        this.radius = radius;
    }

    public static EndOfWorldState get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Current shrink speed in blocks/tick, given a radius. */
    public static double shrinkPerTick(double radius) {
        for (double[] entry : SPEED_SCHEDULE) {
            if (radius > entry[0]) {
                return entry[1];
            }
        }
        return SPEED_SCHEDULE[SPEED_SCHEDULE.length - 1][1];
    }

    public boolean isActive() {
        return active;
    }

    public void begin() {
        this.active = true;
        this.radius = START_RADIUS;
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