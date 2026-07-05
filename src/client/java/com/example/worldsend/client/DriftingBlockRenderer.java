package com.example.worldsend.client;

import com.example.worldsend.DriftingBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renders the drifting block via the 26.x "submit" pipeline, modeled directly
 * on vanilla's FallingBlockRenderer. We reuse FallingBlockRenderState (it
 * already carries the MovingBlockRenderState with block/biome/lighting info)
 * and extend it with our animation scale.
 */
public class DriftingBlockRenderer
        extends EntityRenderer<DriftingBlockEntity, DriftingBlockRenderer.State> {

    public static class State extends FallingBlockRenderState {
        public float scale = 1.0f;
    }

    public DriftingBlockRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0f; // no shadow on a dissolving ghost block
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(DriftingBlockEntity entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        BlockPos pos = BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
        state.movingBlockRenderState.randomSeedPos = entity.blockPosition();
        state.movingBlockRenderState.blockPos = pos;
        state.movingBlockRenderState.blockState = entity.getVisualBlockState();
        Level level = entity.level();
        if (level instanceof ClientLevel clientLevel) {
            state.movingBlockRenderState.biome = clientLevel.getBiome(pos);
            state.movingBlockRenderState.cardinalLighting = clientLevel.cardinalLighting();
            state.movingBlockRenderState.lightEngine = clientLevel.getLightEngine();
        }
        state.scale = entity.getScale(partialTicks);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        if (state.scale <= 0.0f) {
            return;
        }
        BlockState blockState = state.movingBlockRenderState.blockState;
        if (blockState != null && blockState.getRenderShape() == RenderShape.MODEL) {
            poseStack.pushPose();
            // Scale around the block's center so it shrinks in place.
            // At scale=1 this is identical to vanilla's translate(-0.5, 0, -0.5).
            poseStack.translate(0.0, 0.5, 0.0);
            poseStack.scale(state.scale, state.scale, state.scale);
            poseStack.translate(-0.5, -0.5, -0.5);
            submitNodeCollector.submitMovingBlock(poseStack, state.movingBlockRenderState, state.outlineColor);
            poseStack.popPose();
            super.submit(state, poseStack, submitNodeCollector, camera);
        }
    }
}