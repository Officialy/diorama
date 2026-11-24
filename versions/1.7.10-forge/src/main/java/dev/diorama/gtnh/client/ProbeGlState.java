package dev.diorama.gtnh.client;

import java.nio.FloatBuffer;

import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

/** Explicit state touched by the diagnostic only; no pushAttrib, FBO or shader changes. */
final class ProbeGlState implements AutoCloseable {

    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    private final int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
    private final int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
    private final FloatBuffer colour = BufferUtils.createFloatBuffer(4);
    private final boolean texture;
    private int binding;

    ProbeGlState() {
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, colour);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
    }

    void forgetDeletedTexture(int id) {
        if (binding == id) binding = 0;
    }

    private static void enabled(int capability, boolean value) {
        if (value) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }

    @Override
    public void close() {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
        enabled(GL11.GL_TEXTURE_2D, texture);
        OpenGlHelper.glBlendFunc(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
        enabled(GL11.GL_BLEND, blend);
        enabled(GL11.GL_ALPHA_TEST, alpha);
        enabled(GL11.GL_DEPTH_TEST, depth);
        enabled(GL11.GL_LIGHTING, lighting);
        GL11.glColor4f(colour.get(0), colour.get(1), colour.get(2), colour.get(3));
        OpenGlHelper.setActiveTexture(activeTexture);
    }
}
