package dev.diorama.gtnh.client;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import dev.diorama.gtnh.diagnostic.ProbePixels;

/** One diagnostic image; no terrain, workers, disk cache or persistent user settings. */
public final class DisplayProbeScreen extends GuiScreen {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    private final ClientProxy owner;
    private DynamicTexture texture;
    private boolean rebuild = true;
    private int generation = -1;
    private int uploads;
    private int releases;

    DisplayProbeScreen(ClientProxy owner) {
        this.owner = owner;
    }

    @Override
    public void initGui() {
        rebuild = true;
    }

    private void release(ProbeGlState state) {
        if (texture != null) {
            int id = texture.getGlTextureId();
            state.forgetDeletedTexture(id);
            texture.deleteGlTexture();
            texture = null;
            releases++;
            LOG.info("Diorama display probe: released texture {}; uploads={}, releases={}", id, uploads, releases);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        try (ProbeGlState state = new ProbeGlState()) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            if (rebuild || generation != owner.resourceGeneration()) {
                release(state);
                DynamicTexture next = new DynamicTexture(ProbePixels.SIZE, ProbePixels.SIZE);
                texture = next; // Keep ownership even if upload fails, so close can release it.
                ProbePixels.copyToArgb(ProbePixels.abgr(), next.getTextureData());
                next.updateDynamicTexture();
                generation = owner.resourceGeneration();
                rebuild = false;
                uploads++;
                LOG.info(
                    "Diorama display probe: uploaded texture {}; generation={}, uploads={}",
                    next.getGlTextureId(),
                    generation,
                    uploads);
            }
            drawRect(0, 0, width, height, 0xC0202020);
            int edge = Math.max(16, Math.min(256, Math.min(width - 24, height - 76)));
            int left = (width - edge) / 2;
            int top = 28;
            for (int y = 0; y < edge; y += 8) {
                for (int x = 0; x < edge; x += 8) {
                    drawRect(
                        left + x,
                        top + y,
                        left + Math.min(edge, x + 8),
                        top + Math.min(edge, y + 8),
                        ((x / 8 + y / 8) & 1) == 0 ? 0xFF606060 : 0xFFA0A0A0);
                }
            }
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getGlTextureId());
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            tessellator.addVertexWithUV(left, top + edge, 0, 0, 1);
            tessellator.addVertexWithUV(left + edge, top + edge, 0, 1, 1);
            tessellator.addVertexWithUV(left + edge, top, 0, 1, 0);
            tessellator.addVertexWithUV(left, top, 0, 0, 0);
            tessellator.draw();
            GL11.glColor4f(1, 1, 1, 1);
            drawCenteredString(fontRendererObj, "Diorama display probe - NOT a map", width / 2, 10, 0xFFFFFF);
            drawCenteredString(
                fontRendererObj,
                "Top: red / green. Middle: blue / white. Black L: top-left.",
                width / 2,
                top + edge + 8,
                0xFFFFFF);
            drawCenteredString(
                fontRendererObj,
                "Bottom: red alpha 0 / 64 / 128 / 255 over checkerboard.",
                width / 2,
                top + edge + 20,
                0xFFFFFF);
            drawCenteredString(
                fontRendererObj,
                "R: recreate texture | Esc: close",
                width / 2,
                top + edge + 32,
                0xFFFF80);
        }
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_R) rebuild = true;
        else super.keyTyped(character, key);
    }

    @Override
    public void onGuiClosed() {
        try (ProbeGlState state = new ProbeGlState()) {
            release(state);
        }
        LOG.info("Diorama display probe: closed; uploads={}, releases={}", uploads, releases);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
