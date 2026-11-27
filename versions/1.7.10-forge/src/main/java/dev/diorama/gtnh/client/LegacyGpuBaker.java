package dev.diorama.gtnh.client;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;
import org.jspecify.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.diorama.Projection;
import dev.diorama.gtnh.capture.TerrainPreview;

/**
 * Experimental LWJGL2 heightfield raymarch. Workers prepare detached data; GL is used only
 * during the render tick. Results are provisional; the canonical CPU pass remains authoritative.
 */
final class LegacyGpuBaker {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static final boolean ENABLED = Boolean.getBoolean("diorama.gpuBake") || Boolean.getBoolean("diorama.gpuRefine");
    private static final AtomicReference<FutureTask<int[]>> PENDING = new AtomicReference<>();
    private static final int SIZE = 128;
    private static int program;
    private static volatile boolean failed;
    private static final String VERTEX = "#version 120\nvoid main(){gl_Position=gl_Vertex;}";
    private static final String FRAGMENT = "#version 120\n"
        + "uniform sampler2D columns; uniform vec2 pitch; uniform float offset;\n"
        + "void main(){float u=floor(gl_FragCoord.x); float v=gl_FragCoord.y;"
        + "float previous=-1.0; for(int i=256;i>=0;i--){float y=float(i);"
        + "float z=floor(offset+(v+y*pitch.y)/pitch.x);"
        + "if(z<0.0||z>=400.0)break;"
        + "vec4 c=texture2D(columns,vec2((u+0.5)/128.0,(z+0.5)/400.0));"
        + "if(c.a<-1.5){gl_FragColor=vec4(0.0);return;}"
        + "if(c.a>0.0&&y<=c.a){float side=previous>0.0&&c.a-previous>1.5?0.68:0.95;"
        + "gl_FragColor=vec4(c.rgb*side,1.0);return;}previous=c.a;}"
        + "gl_FragColor=vec4(0.0);}";

    private LegacyGpuBaker() {}

    static int @Nullable [] preview(TerrainPreview.Work work) {
        if (!ENABLED || failed || work.key().isCave() || PENDING.get() != null) return null;
        try {
            float[] columns = work.gpuColumns();
            double origin = work.key().minV() / Projection.sin();
            float offset = (float) (origin - Math.floor(origin));
            FutureTask<int[]> task = new FutureTask<>(() -> bake(columns, offset));
            if (!PENDING.compareAndSet(null, task)) return null;
            try {
                return task.get(750, TimeUnit.MILLISECONDS);
            } finally {
                PENDING.compareAndSet(task, null);
                if (!task.isDone()) task.cancel(false);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception | LinkageError unavailable) {
            return null;
        }
    }

    static int @Nullable [] submit(FutureTask<int[]> task) {
        if (!PENDING.compareAndSet(null,task)) return null;
        try {
            return task.get(1000,TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unavailable) {
            return null;
        } finally {
            PENDING.compareAndSet(task,null);
            if (!task.isDone()) task.cancel(false);
        }
    }

    static void frame() {
        FutureTask<int[]> task = PENDING.getAndSet(null);
        if (task != null && !task.isCancelled()) task.run();
    }

    static int shaderProgram(String vertex, String fragment) {
        int vs = 0, fs = 0, linked = 0;
        try {
            vs = shader(GL20.GL_VERTEX_SHADER, vertex);
            fs = shader(GL20.GL_FRAGMENT_SHADER, fragment);
            linked = GL20.glCreateProgram();
            GL20.glAttachShader(linked, vs);
            GL20.glAttachShader(linked, fs);
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(linked, 4096));
            int result = linked;
            linked = 0;
            return result;
        } finally {
            if (vs != 0) GL20.glDeleteShader(vs);
            if (fs != 0) GL20.glDeleteShader(fs);
            if (linked != 0) GL20.glDeleteProgram(linked);
        }
    }

    private static int shader(int type, String source) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, source);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(id, 4096);
            GL20.glDeleteShader(id);
            throw new IllegalStateException(log);
        }
        return id;
    }

    private static int @Nullable [] bake(float[] data, float offset) {
        if (!GLContext.getCapabilities().OpenGL30 || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0
            || GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING) != 0
            || GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) != 0) return null;
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(active);
        int fbo = 0, input = 0, output = 0;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try {
            if (program == 0) program = shaderProgram(VERTEX, FRAGMENT);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            input = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, input);
            textureParams();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, 0);
            FloatBuffer columns = BufferUtils.createFloatBuffer(data.length);
            columns.put(data).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 128, 400, 0,
                GL11.GL_RGBA, GL11.GL_FLOAT, columns);
            output = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, output);
            textureParams();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, output, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("GPU preview framebuffer incomplete");
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glViewport(0, 0, SIZE, SIZE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColorMask(true, true, true, true);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, input);
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "columns"), 0);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "pitch"), (float) Projection.sin(), (float) Projection.cos());
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "offset"), offset);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(-1,-1); GL11.glVertex2f(1,-1); GL11.glVertex2f(1,1); GL11.glVertex2f(-1,1);
            GL11.glEnd();
            ByteBuffer result = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,result);
            int edge = Projection.TILE_PIXELS;
            int[] pixels = new int[edge * edge];
            for (int y=0;y<SIZE;y++) for (int x=0;x<SIZE;x++) {
                int i=(y*SIZE+x)*4;
                int pixel=(result.get(i)&255)|((result.get(i+1)&255)<<8)
                    |((result.get(i+2)&255)<<16)|((result.get(i+3)&255)<<24);
                for (int row=0;row<4;row++) java.util.Arrays.fill(pixels,(y*4+row)*edge+x*4,(y*4+row)*edge+x*4+4,pixel);
            }
            return pixels;
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            LOG.warn("Experimental GPU preview disabled; falling back to CPU", failure);
            return null;
        } finally {
            GL20.glUseProgram(0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (input != 0) GL11.glDeleteTextures(input);
            if (output != 0) GL11.glDeleteTextures(output);
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL13.glActiveTexture(active);
        }
    }

    private static void textureParams() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }
}
