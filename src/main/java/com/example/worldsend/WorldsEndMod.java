package com.example.worldsend;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorldsEndMod implements ModInitializer {
    public static final String MOD_ID = "worlds-end";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static EntityType<DriftingBlockEntity> DRIFTING_BLOCK;

    @Override
    public void onInitialize() {
        // 1. Register the purely-visual "drifting block" entity.
        DRIFTING_BLOCK = Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(MOD_ID, "drifting_block"),
                EntityType.Builder
                        .<DriftingBlockEntity>of(DriftingBlockEntity::new, MobCategory.MISC)
                        .sized(0.98f, 0.98f)
                        .clientTrackingRange(8)
                        .updateInterval(20)
                        .build(DriftingBlockEntity.typeKey())
        );

        // 2. On server start: load the config (so edits apply per world
        //    open), then reset all JVM-scoped static state — it belongs to
        //    the JVM, not the world, and leaks between singleplayer worlds.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            WorldsEndConfig.load();
            DriftingBlockEntity.resetPool();
            ShrinkController.reset();
        });

        // 3. Each tick: start the end if the End's dragon fight reports the
        //    dragon has been defeated, then advance the border.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerLevel overworld = server.overworld();
            EndOfWorldState state = EndOfWorldState.get(overworld);

            if (!state.isActive()) {
                ServerLevel end = server.getLevel(Level.END);
                if (end != null) {
                    EnderDragonFight fight = end.getDragonFight();
                    if (fight != null && fight.hasPreviouslyKilledDragon()) {
                        LOGGER.info("The dragon has fallen. The end of the world begins.");
                        startTheEnd(overworld);
                    }
                }
            }

            if (state.isActive()) {
                if (overworld.getGameTime() % 100 == 0) {
                    LOGGER.info("World ending, radius = {}{}", state.radius(),
                            state.isPaused() ? " (paused)" : "");
                }
                ShrinkController.tick(overworld, state);
            }
        });

        // 4. Chunks that load already (partly) outside the border get queued
        //    for wiping.
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (level == level.getServer().overworld()) {
                EndOfWorldState state = EndOfWorldState.get(level);
                if (state.isActive()) {
                    ShrinkController.onChunkLoad(level, state, chunk.getPos());
                }
            }
        });

        // 5. CHUNK_LOAD misses chunks that become accessible without a full
        //    unload (documented gap); the status-change event covers those.
        ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register((level, chunk, oldStatus, newStatus) -> {
            if (level == level.getServer().overworld()
                    && newStatus.isOrAfter(FullChunkStatus.FULL)
                    && !oldStatus.isOrAfter(FullChunkStatus.FULL)) {
                EndOfWorldState state = EndOfWorldState.get(level);
                if (state.isActive()) {
                    ShrinkController.onChunkLoad(level, state, chunk.getPos());
                }
            }
        });

        // 6. Commands: /worldsend start | pause | resume | radius <blocks>
        //    The whole tree is gated at permission level 2 (ops / cheats).
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("worldsend")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("start").executes(ctx -> {
                        ServerLevel overworld = ctx.getSource().getServer().overworld();
                        EndOfWorldState state = EndOfWorldState.get(overworld);
                        if (state.isActive()) {
                            ctx.getSource().sendFailure(Component.literal(
                                    "The end of the world is already underway."));
                            return 0;
                        }
                        startTheEnd(overworld);
                        return 1;
                    }))
                    .then(Commands.literal("pause").executes(ctx -> {
                        ServerLevel overworld = ctx.getSource().getServer().overworld();
                        EndOfWorldState state = EndOfWorldState.get(overworld);
                        if (!state.isActive()) {
                            ctx.getSource().sendFailure(Component.literal(
                                    "The end of the world has not started."));
                            return 0;
                        }
                        if (state.isPaused()) {
                            ctx.getSource().sendFailure(Component.literal(
                                    "The border is already paused."));
                            return 0;
                        }
                        state.setPaused(true);
                        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                "Border paused at radius %,.0f.", state.radius())), true);
                        return 1;
                    }))
                    .then(Commands.literal("resume").executes(ctx -> {
                        ServerLevel overworld = ctx.getSource().getServer().overworld();
                        EndOfWorldState state = EndOfWorldState.get(overworld);
                        if (!state.isActive()) {
                            ctx.getSource().sendFailure(Component.literal(
                                    "The end of the world has not started."));
                            return 0;
                        }
                        if (!state.isPaused()) {
                            ctx.getSource().sendFailure(Component.literal(
                                    "The border is not paused."));
                            return 0;
                        }
                        state.setPaused(false);
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "Border resumed."), true);
                        return 1;
                    }))
                    .then(Commands.literal("radius")
                            .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.0))
                                    .executes(ctx -> {
                                        double target = DoubleArgumentType.getDouble(ctx, "blocks");
                                        ServerLevel overworld = ctx.getSource().getServer().overworld();
                                        EndOfWorldState state = EndOfWorldState.get(overworld);

                                        // Setting a radius implies destruction
                                        // outside it, so it also starts the end.
                                        if (!state.isActive()) {
                                            startTheEnd(overworld);
                                        }

                                        // Shrink-only: everything beyond the current
                                        // radius is already gone, so growing it would
                                        // just expose deleted terrain.
                                        if (target > state.radius()) {
                                            ctx.getSource().sendFailure(Component.literal(String.format(
                                                    "The border can only shrink. Current radius is %,.0f; cannot grow to %,.0f.",
                                                    state.radius(), target)));
                                            return 0;
                                        }

                                        state.setRadius(target);
                                        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                                "Border radius set to %,.0f blocks.", target)), true);
                                        return 1;
                                    }))));
        });

        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof DriftingBlockEntity drifting) {
                drifting.onPoolExit();
            }
        });
    }

    /** Starts the shrink and announces it. The single entry point for both
     *  the dragon trigger and the debug command. */
    private static void startTheEnd(ServerLevel overworld) {
        EndOfWorldState.get(overworld).begin();
        overworld.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("The End of the world has started."), false);
    }
}