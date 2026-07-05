package com.example.worldsend.client;

import com.example.worldsend.WorldsEndMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class WorldsEndClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(WorldsEndMod.DRIFTING_BLOCK, DriftingBlockRenderer::new);
    }
}
