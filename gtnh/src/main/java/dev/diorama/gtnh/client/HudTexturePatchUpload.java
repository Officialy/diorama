package dev.diorama.gtnh.client;

import java.nio.IntBuffer;
import java.util.function.BooleanSupplier;

import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GLContext;

/** Partial uploads to an already allocated HUD DynamicTexture's raw ID. */
final class HudTexturePatchUpload {

    private static final int[] UNPACK_KEYS = { GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH,
        GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SWAP_BYTES };
    private static final int[] TIGHT_UNPACK = { 4, 0, 0, 0, GL11.GL_FALSE };

    private HudTexturePatchUpload() {}

    /**
     * Call on the render thread inside the HUD's ProbeGlState. The caller owns the texture and must unpublish
     * and dispose it on any false result or exception; a partial upload cannot be rolled back on revocation.
     */
    static boolean upload(int rawTextureId, int textureWidth, int textureHeight, int[] abgr, int x, int y, int width,
        int height, boolean anaglyph, BooleanSupplier current) {
        if (rawTextureId <= 0 || !current.getAsBoolean()) return false;
        IntBuffer packed = HudTexturePatchPixels.pack(abgr, textureWidth, textureHeight, x, y, width, height, anaglyph);
        // A client pointer must never be interpreted as an offset into a bound unpack buffer.
        ContextCapabilities capabilities = GLContext.getCapabilities();
        if ((capabilities.OpenGL21 || capabilities.GL_ARB_pixel_buffer_object
            || capabilities.GL_EXT_pixel_buffer_object) && GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) != 0)
            return false;
        int[] saved = new int[UNPACK_KEYS.length];
        for (int i = 0; i < saved.length; i++) saved[i] = GL11.glGetInteger(UNPACK_KEYS[i]);
        if (!current.getAsBoolean()) return false;
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        if (!current.getAsBoolean()) return false;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, rawTextureId);
        try {
            for (int i = 0; i < saved.length; i++) {
                if (saved[i] != TIGHT_UNPACK[i]) GL11.glPixelStorei(UNPACK_KEYS[i], TIGHT_UNPACK[i]);
            }
            if (!current.getAsBoolean()) return false;
            GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                x,
                y,
                width,
                height,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                packed);
        } finally {
            for (int i = saved.length - 1; i >= 0; i--) {
                if (saved[i] != TIGHT_UNPACK[i]) GL11.glPixelStorei(UNPACK_KEYS[i], saved[i]);
            }
        }
        return current.getAsBoolean();
    }
}
