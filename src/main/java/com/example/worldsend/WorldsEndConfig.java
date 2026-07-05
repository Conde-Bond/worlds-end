package com.example.worldsend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * User-editable settings, stored as JSON at config/worlds-end.json.
 *
 * Loaded on SERVER_STARTED (see WorldsEndMod), so edits apply after
 * closing and reopening a world — no full game restart needed.
 *
 * The file is rewritten after every load so that missing fields (e.g.
 * after a mod update adds new options) are filled in with defaults.
 */
public final class WorldsEndConfig {

    /** One row of the speed schedule: above this radius, shrink this fast. */
    public static class SpeedStep {
        public double aboveRadius;
        public double blocksPerTick;

        public SpeedStep() {}

        public SpeedStep(double aboveRadius, double blocksPerTick) {
            this.aboveRadius = aboveRadius;
            this.blocksPerTick = blocksPerTick;
        }
    }

    /** Multiplies every speed in the schedule. Handy for testing (e.g. 100.0). */
    public double speedMultiplier = 1.0;

    /**
     * Shrink speed by radius, applied to whichever row's aboveRadius the
     * current radius exceeds first (rows are sorted on load, so order in
     * the file doesn't matter). Minutes to cross a range = distance / speed / 1200.
     */
    public List<SpeedStep> speedSchedule = defaultSchedule();

    private static List<SpeedStep> defaultSchedule() {
        List<SpeedStep> schedule = new ArrayList<>();
        schedule.add(new SpeedStep(1_000_000, 500.0)); // 30M -> 1M  : ~48 min
        schedule.add(new SpeedStep(  100_000,  50.0)); // 1M  -> 100k: ~15 min
        schedule.add(new SpeedStep(   10_000,   5.0)); // 100k-> 10k : ~15 min
        schedule.add(new SpeedStep(    2_000,   0.5)); // 10k -> 2k  : ~13 min
        schedule.add(new SpeedStep(        0,   0.05)); // 2k -> 0   : ~33 min
        return schedule;
    }

    // -----------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = WorldsEndMod.MOD_ID + ".json";

    private static WorldsEndConfig instance = new WorldsEndConfig();

    public static WorldsEndConfig get() {
        return instance;
    }

    /** Loads (or creates) the config file and makes it the active instance. */
    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        WorldsEndConfig loaded = null;

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                loaded = GSON.fromJson(reader, WorldsEndConfig.class);
            } catch (IOException | JsonParseException e) {
                WorldsEndMod.LOGGER.error(
                        "Could not read {} — keeping defaults. Fix or delete the file.", FILE_NAME, e);
                return; // don't overwrite a file the user may want to repair
            }
        }

        instance = sanitize(loaded != null ? loaded : new WorldsEndConfig());

        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(instance, writer);
        } catch (IOException e) {
            WorldsEndMod.LOGGER.error("Could not write {}", FILE_NAME, e);
        }

        WorldsEndMod.LOGGER.info("Config loaded: multiplier={}, {} speed steps",
                instance.speedMultiplier, instance.speedSchedule.size());
    }

    /** Repairs anything a hand-edited file might get wrong. */
    private static WorldsEndConfig sanitize(WorldsEndConfig config) {
        if (config.speedSchedule == null || config.speedSchedule.isEmpty()) {
            config.speedSchedule = defaultSchedule();
        }
        config.speedSchedule.removeIf(step -> step == null || step.blocksPerTick <= 0);
        if (config.speedSchedule.isEmpty()) {
            config.speedSchedule = defaultSchedule();
        }
        // Descending by radius, so the first matching row wins in shrinkPerTick.
        config.speedSchedule.sort(Comparator.comparingDouble((SpeedStep s) -> s.aboveRadius).reversed());
        if (config.speedMultiplier <= 0) {
            config.speedMultiplier = 1.0;
        }
        return config;
    }

    private WorldsEndConfig() {}
}
