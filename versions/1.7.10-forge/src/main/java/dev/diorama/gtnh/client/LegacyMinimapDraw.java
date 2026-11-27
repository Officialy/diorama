package dev.diorama.gtnh.client;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.client.LegacyWaypointBook.Point;

/** Draws the crop in Forge's existing scaled GUI projection. */
final class LegacyMinimapDraw {

    // The Forge Tessellator grows a large scratch array on first use. Reuse it across successful
    // frames, but keep it out of the pool while drawing and discard any revoked in-progress batch.
    private static Tessellator spare;

    private LegacyMinimapDraw() {}

    /**
     * The caller owns the current {@link ProbeGlState}. Lookup returns a live, published raw ID or
     * -1, and valid must become false if this draw's ownership is revoked while a callback runs.
     */
    static void draw(Minecraft mc, int width, int height, MinimapViewport viewport, ToIntFunction<TileKey> textureId,
        List<Point> points, int size, float yaw, String coords, BooleanSupplier valid) {
        MinimapViewport.Bounds bounds = MinimapViewport.fit(width, height, size);
        if (bounds.size() == 0 || !valid.getAsBoolean()) return;
        Tessellator mesh = spare == null ? new Tessellator() : spare;
        spare = null;
        if (drawContents(mc, height, bounds, viewport, textureId, points, yaw, coords, valid, mesh))
            spare = mesh;
    }

    private static boolean drawContents(Minecraft mc, int height, MinimapViewport.Bounds bounds,
        MinimapViewport viewport, ToIntFunction<TileKey> textureId, List<Point> points, float yaw, String coords,
        BooleanSupplier valid, Tessellator mesh) {
        FontRenderer font = mc.fontRenderer;
        int x = bounds.x(), y = bounds.y(), size = bounds.size(), right = x + size, bottom = y + size;
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        if (!valid.getAsBoolean()) return false;
        rect(mesh, x - 1, y - 1, right + 1, bottom + 1, 0xFF627484);
        if (!valid.getAsBoolean()) return false;
        fog(mesh, x, y, size, size, viewport.centerU() - MinimapViewport.HALF,
            viewport.centerV() - MinimapViewport.HALF, MinimapViewport.SPAN / size,
            !viewport.tiles().isEmpty() && viewport.tiles().get(0).key().isCave());
        for (MinimapViewport.Tile tile : viewport.tiles()) {
            if (!valid.getAsBoolean()) return false;
            int id = textureId.applyAsInt(tile.key());
            if (!valid.getAsBoolean()) return false;
            if (id <= 0) continue;

            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1, 1, 1, 1);
            if (!valid.getAsBoolean()) return false;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            filter(size / MinimapViewport.SPAN);
            if (!valid.getAsBoolean()) return false;
            clippedQuad(mesh, bounds, tile);
            if (!valid.getAsBoolean()) return false;
            mesh.draw();
            LegacyFogShader.edge(x + tile.x0()*size,y + tile.y0()*size,x + tile.x1()*size,y + tile.y1()*size,
                tile.u0(),tile.v0(),tile.u1(),tile.v1(),tile.key().minU(),tile.key().minV(),tile.key().units(),tile.key().isCave());
        }

        if (!valid.getAsBoolean()) return false;
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        if (size >= 8 && ClientMinimap.options().radar) drawRadar(mc, mesh, viewport, bounds);
        if (size >= 8 && !points.isEmpty()) {
            drawWaypoints(mesh, viewport, bounds, points, mc.thePlayer.posX, mc.thePlayer.posZ);
        }
        if (size >= 8 && Float.isFinite(yaw)) {
            if (!valid.getAsBoolean()) return false;
            arrow(mesh, x + size / 2.0, y + size / 2.0, yaw, Math.min(5, size * 0.25));
        }
        if (font == null) return true;
        int northWidth = font.getStringWidth("N");
        if (size >= 24 && northWidth + 4 <= size) {
            if (!valid.getAsBoolean()) return false;
            int nx = x + (size - northWidth) / 2;
            rect(mesh, nx - 2, y, nx + northWidth + 2, y + font.FONT_HEIGHT + 1, 0xA0101418);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            if (!valid.getAsBoolean()) return false;
            font.drawString("N", nx, y + 1, 0xFFFF6B6B);
        }
        int textY = bottom + 4;
        if (coords != null && !coords.isEmpty() && textY + font.FONT_HEIGHT + 1 <= height - MinimapViewport.MARGIN) {
            int left = right - font.getStringWidth(coords);
            if (left >= MinimapViewport.MARGIN) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                if (!valid.getAsBoolean()) return false;
                font.drawStringWithShadow(coords, left, textY, 0xFFFFFFFF);
            }
        }
        Point nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Point point : points) {
            if (!point.enabled()) continue;
            double d = Math.hypot(point.x() + .5 - mc.thePlayer.posX, point.z() + .5 - mc.thePlayer.posZ);
            if (d < distance) {
                nearest = point;
                distance = d;
            }
        }
        if (nearest != null) {
            int labelY = textY + (coords == null || coords.isEmpty() ? 0 : font.FONT_HEIGHT + 2);
            if (labelY + font.FONT_HEIGHT <= height - MinimapViewport.MARGIN) {
                String label = font.trimStringToWidth(new StringBuilder(nearest.label()).append(" ").append(Math.round(distance)).append("m").toString(), size + 24);
                int left = right - font.getStringWidth(label);
                if (left >= MinimapViewport.MARGIN && valid.getAsBoolean()) {
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    font.drawStringWithShadow(label, left, labelY, 0xFF000000 | nearest.markerColor());
                }
            }
        }
        return true;
    }

    /** Only entities already tracked by the local client; no network requests or chunk loads. */
    private static void drawRadar(Minecraft mc, Tessellator mesh, MinimapViewport viewport,
        MinimapViewport.Bounds bounds) {
        if (mc.theWorld == null || mc.thePlayer == null) return;
        int size = bounds.size(), count = 0;
        boolean players = ClientMinimap.options().playerRadar;
        mesh.startDrawingQuads();
        for (Object item : mc.theWorld.loadedEntityList) {
            if (!(item instanceof EntityLivingBase entity) || entity == mc.thePlayer || entity.isDead
                || entity.isInvisible() || entity instanceof EntityPlayer && (!players || entity.isSneaking())) continue;
            if (Math.abs(entity.posY - mc.thePlayer.posY) > 16) continue;
            double x = viewport.normalizedX(Projection.u(entity.posX, entity.posZ)) * size;
            double y = viewport.normalizedY(Projection.v(entity.posX, entity.posY, entity.posZ)) * size;
            if (x < 3 || x > size - 3 || y < 3 || y > size - 3) continue;
            int sx = bounds.x() + (int) x, sy = bounds.y() + (int) y;
            waypointQuad(mesh, sx, sy, 2, 0x202020);
            waypointQuad(mesh, sx, sy, 1, entity instanceof EntityPlayer ? 0x61B5FF
                : entity instanceof IMob ? 0xFF6666 : 0x8DE38D);
            if (++count >= 96) break;
        }
        mesh.draw();
    }

    private static void drawWaypoints(Tessellator mesh, MinimapViewport viewport, MinimapViewport.Bounds bounds,
        List<Point> points, double playerX, double playerZ) {
        boolean started = false;
        int size = bounds.size();
        for (Point point : points) {
            if (!point.enabled()) continue;
            double sx = viewport.normalizedX(Projection.u(point.x() + .5, point.z() + .5)) * size;
            double sy = viewport.normalizedY(Projection.v(point.x() + .5, point.y(), point.z() + .5)) * size;
            if (sx < 5 || sx > size - 5 || sy < 5 || sy > size - 5) continue;
            if (!started) {
                mesh.startDrawingQuads();
                started = true;
            }
            int x = bounds.x() + (int) Math.round(sx), y = bounds.y() + (int) Math.round(sy);
            waypointQuad(mesh, x, y, 3, 0x211909);
            waypointQuad(mesh, x, y, 2, point.markerColor());
        }
        if (started) mesh.draw();
        if (size < 16) return;
        mesh.startDrawing(GL11.GL_TRIANGLES);
        for (Point point : points) {
            if (!point.enabled()) continue;
            double sx = viewport.normalizedX(Projection.u(point.x() + .5, point.z() + .5)) * size;
            double sy = viewport.normalizedY(Projection.v(point.x() + .5, point.y(), point.z() + .5)) * size;
            if (sx >= 5 && sx <= size - 5 && sy >= 5 && sy <= size - 5) continue;
            double dx = Projection.u(point.x() + .5, point.z() + .5) - Projection.u(playerX, playerZ);
            double dy = Projection.v(point.x() + .5, 0, point.z() + .5) - Projection.v(playerX, 0, playerZ);
            if (Math.abs(dx) + Math.abs(dy) < 1e-6) {
                dx = sx - size / 2.0;
                dy = sy - size / 2.0;
            }
            double length = Math.hypot(dx, dy);
            if (length == 0) continue;
            double reach = (size / 2.0 - 6) / Math.max(Math.abs(dx), Math.abs(dy));
            double x = bounds.x() + size / 2.0 + dx * reach;
            double y = bounds.y() + size / 2.0 + dy * reach;
            arrowLayer(mesh, x, y, dx / length, dy / length, 5, 0xFF211909);
            arrowLayer(mesh, x, y, dx / length, dy / length, 3.5, 0xFF000000 | point.markerColor());
        }
        mesh.draw();
    }

    /** Texture-native detail remains crisp when magnified; linear filtering reduces distant shimmer. */
    static void filter(double pixelsPerUnit) {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
            ClientMinimap.options().smoothZoom || pixelsPerUnit < Projection.PIXELS_PER_UNIT ? GL11.GL_LINEAR : GL11.GL_NEAREST);
    }

    /** World-anchored cloud background: unknown raster alpha exposes it. */
    static void fog(Tessellator mesh, int x, int y, int width, int height, double u, double v,
        double unit, boolean cave) {
        if (width <= 0 || height <= 0) return;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        if (!ClientMinimap.options().fog) {
            rect(mesh, x, y, x + width, y + height, 0xFF111820);
            GL11.glColor4f(1, 1, 1, 1);
            return;
        }
        if (LegacyFogShader.background(x,y,width,height,u,v,unit,cave)) return;
        int previousShade = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        int step = Math.max(12, Math.max(width, height) / 48);
        try {
            mesh.startDrawingQuads();
            for (int row = 0; row < height; row += step) {
                int bottom = Math.min(height, row + step);
                for (int col = 0; col < width; col += step) {
                    int right = Math.min(width, col + step);
                    fogVertex(mesh, x + col, y + bottom, u + col * unit, v + bottom * unit, cave);
                    fogVertex(mesh, x + right, y + bottom, u + right * unit, v + bottom * unit, cave);
                    fogVertex(mesh, x + right, y + row, u + right * unit, v + row * unit, cave);
                    fogVertex(mesh, x + col, y + row, u + col * unit, v + row * unit, cave);
                }
            }
            mesh.draw();
        } finally {
            GL11.glShadeModel(previousShade);
            GL11.glColor4f(1, 1, 1, 1);
        }
    }

    private static void fogVertex(Tessellator mesh, int x, int y, double u, double v, boolean cave) {
        double n = 0.65 * noise(u / 24, v / 24) + 0.25 * noise(u / 9, v / 9)
            + 0.10 * noise(u / 3, v / 3);
        int shade = (int) (cave ? 27 + n * 42 : 111 + n * 88);
        int rgb = (shade << 16) | ((shade + (cave ? 2 : 4)) << 8) | (shade + (cave ? 5 : 8));
        mesh.setColorRGBA_I(rgb, 255);
        mesh.addVertex(x, y, 0);
    }

    private static double noise(double x, double y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = hash(ix, iy) * (1 - fx) + hash(ix + 1, iy) * fx;
        double b = hash(ix, iy + 1) * (1 - fx) + hash(ix + 1, iy + 1) * fx;
        return a * (1 - fy) + b * fy;
    }

    private static double hash(int x, int y) {
        int h = x * 0x1f123bb5 ^ y * 0x5f356495;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        return (h & 65535) / 65535.0;
    }

    private static void waypointQuad(Tessellator mesh, int x, int y, int radius, int rgb) {
        mesh.setColorRGBA_I(rgb, 255);
        mesh.addVertex(x - radius, y + radius, 0);
        mesh.addVertex(x + radius, y + radius, 0);
        mesh.addVertex(x + radius, y - radius, 0);
        mesh.addVertex(x - radius, y - radius, 0);
    }

    private static void clippedQuad(Tessellator mesh, MinimapViewport.Bounds bounds, MinimapViewport.Tile tile) {
        double x0 = bounds.x() + tile.x0() * bounds.size(), x1 = bounds.x() + tile.x1() * bounds.size();
        double y0 = bounds.y() + tile.y0() * bounds.size(), y1 = bounds.y() + tile.y1() * bounds.size();
        // UV (0,0) is the top-left row in the legacy DynamicTexture upload. Outer
        // vertices already land on integer GUI edges, and neighbouring cuts share vertices.
        mesh.startDrawingQuads();
        mesh.addVertexWithUV(x0, y1, 0, tile.u0(), tile.v1());
        mesh.addVertexWithUV(x1, y1, 0, tile.u1(), tile.v1());
        mesh.addVertexWithUV(x1, y0, 0, tile.u1(), tile.v0());
        mesh.addVertexWithUV(x0, y0, 0, tile.u0(), tile.v0());
    }

    private static void rect(Tessellator mesh, int x0, int y0, int x1, int y1, int argb) {
        color(argb);
        mesh.startDrawingQuads();
        mesh.addVertex(x0, y1, 0);
        mesh.addVertex(x1, y1, 0);
        mesh.addVertex(x1, y0, 0);
        mesh.addVertex(x0, y0, 0);
        mesh.draw();
    }

    private static void arrow(Tessellator mesh, double x, double y, float yaw, double size) {
        MinimapViewport.Heading heading = MinimapViewport.heading(yaw);
        mesh.startDrawing(GL11.GL_TRIANGLES);
        arrowLayer(mesh, x, y, heading.dx(), heading.dy(), size + 1.6, 0xE0101418);
        arrowLayer(mesh, x, y, heading.dx(), heading.dy(), size, 0xFFFFFFFF);
        arrowLayer(mesh, x, y, heading.dx(), heading.dy(), size * 0.55, 0xFF3FA9F5);
        mesh.draw();
    }

    private static void arrowLayer(Tessellator mesh, double x, double y, double dx, double dy, double size, int argb) {
        double tipX = x + dx * size, tipY = y + dy * size;
        double tailX = x - dx * size * 0.75, tailY = y - dy * size * 0.75;
        double wingX = -dy * size * 0.7, wingY = dx * size * 0.7;
        double notchX = x - dx * size * 0.35, notchY = y - dy * size * 0.35;
        mesh.setColorRGBA_I(argb & 0xFFFFFF, argb >>> 24);
        mesh.addVertex(tipX, tipY, 0);
        mesh.addVertex(tailX - wingX, tailY - wingY, 0);
        mesh.addVertex(notchX, notchY, 0);
        mesh.addVertex(tipX, tipY, 0);
        mesh.addVertex(notchX, notchY, 0);
        mesh.addVertex(tailX + wingX, tailY + wingY, 0);
    }

    private static void color(int argb) {
        GL11.glColor4f((argb >> 16 & 255) / 255F, (argb >> 8 & 255) / 255F, (argb & 255) / 255F, (argb >>> 24) / 255F);
    }
}
