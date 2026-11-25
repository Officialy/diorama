package dev.diorama.gtnh.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.I18n;

import org.jspecify.annotations.Nullable;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.client.LegacyWaypointBook.Point;

/** Fullscreen view of the same resident surface tiles used by the HUD. */
final class SharedTerrainMapScreen extends GuiScreen {

    private static final int BUTTON_X = 6;
    private static final int BUTTON_Y = 6;
    private static final int BUTTON_WIDTH = 114;
    private static final int BUTTON_HEIGHT = 20;
    private static final int OPTIONS_X = BUTTON_X + BUTTON_WIDTH + 4;
    private static final int WAYPOINT_X = OPTIONS_X + BUTTON_WIDTH + 4;
    private static final int WAYPOINT_WIDTH = 72;
    private static final int LAYER_Y = BUTTON_Y + BUTTON_HEIGHT + 4;
    private final WorldClient world;
    private final @Nullable ClientMapDimension dimension;
    private final MapViewportController view = new MapViewportController(0, 0, 4, 0.25, 16);
    private boolean initialized, dragging;
    private int lastX, lastY;

    SharedTerrainMapScreen(WorldClient world) {
        this.world = world;
        this.dimension = ClientMapDimension.of(world);
    }

    boolean belongsTo(WorldClient current) {
        return initialized && current == world && java.util.Objects.equals(ClientMapDimension.of(current), dimension);
    }

    MapViewportController viewport() {
        return view;
    }

    @Override
    public void initGui() {
        view.resize(0, 0, width, height);
        buttonList.add(
            new GuiButton(0, BUTTON_X, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT, I18n.format("diorama.map.recenter")));
        buttonList.add(
            new GuiButton(1, OPTIONS_X, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT, I18n.format("diorama.minimap.options")));
        if (dimension != null) {
            buttonList.add(
                new GuiButton(2, WAYPOINT_X, BUTTON_Y, WAYPOINT_WIDTH, BUTTON_HEIGHT, WaypointEditScreen.text("list")));
        }
        if (dimension != null && dimension.surfaceTerrain()) {
            buttonList.add(new GuiButton(3, BUTTON_X, LAYER_Y, BUTTON_WIDTH, BUTTON_HEIGHT, nextLayerLabel()));
        }
        dragging = false;
        if (!initialized) {
            recenter();
            initialized = true;
        }
    }

    private String nextLayerLabel() {
        return I18n
            .format(ClientMinimap.layer(mc) == TileKey.SURFACE ? "diorama.map.show_caves" : "diorama.map.show_surface");
    }

    private void recenter() {
        if (mc.theWorld != world || mc.thePlayer == null) return;
        double x = mc.thePlayer.posX, z = mc.thePlayer.posZ, y = mc.thePlayer.boundingBox.minY;
        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))
            view.recenter(Projection.u(x, z), Projection.v(x, y, z));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!belongsTo(mc.theWorld)) return;
        if (button.id == 0) recenter();
        if (button.id == 1) mc.displayGuiScreen(new MinimapOptionsScreen(this));
        if (button.id == 3) {
            ClientMinimap.toggleLayer(mc);
            button.displayString = nextLayerLabel();
        }
        if (button.id == 2) {
            ClientWaypoints session = ClientWaypoints.active(mc);
            if (session != null) mc.displayGuiScreen(new WaypointListScreen(session, this));
        }
    }

    @Override
    protected void keyTyped(char character, int keyCode) {
        if (ClientProxy.isMapKey(keyCode)) {
            if (!Keyboard.isRepeatEvent()) mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(character, keyCode);
    }

    @Override
    public void handleMouseInput() {
        if (Mouse.getEventButtonState() && ClientProxy.isMapMouseButton(Mouse.getEventButton())) {
            mc.displayGuiScreen(null);
            return;
        }
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            double x = (double) Mouse.getEventX() * width / mc.displayWidth;
            double y = height - (double) Mouse.getEventY() * height / mc.displayHeight - 1;
            view.zoomAt(x, y, wheel > 0 ? 1 : -1);
        }
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        boolean controls = y >= BUTTON_Y && y < BUTTON_Y + BUTTON_HEIGHT
            && ((x >= BUTTON_X && x < BUTTON_X + BUTTON_WIDTH) || (x >= OPTIONS_X && x < OPTIONS_X + BUTTON_WIDTH)
                || (x >= WAYPOINT_X && x < WAYPOINT_X + WAYPOINT_WIDTH && dimension != null));
        controls |= dimension != null && dimension.surfaceTerrain()
            && x >= BUTTON_X
            && x < BUTTON_X + BUTTON_WIDTH
            && y >= LAYER_Y
            && y < LAYER_Y + BUTTON_HEIGHT;
        if (button == 0 && !controls) {
            dragging = true;
            lastX = x;
            lastY = y;
        }
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long elapsed) {
        if (button != 0 || !dragging) return;
        view.panBy(x - lastX, y - lastY);
        lastX = x;
        lastY = y;
    }

    @Override
    protected void mouseMovedOrUp(int x, int y, int button) {
        super.mouseMovedOrUp(x, y, button);
        if (button == 0) dragging = false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xFF111820);
        ClientMinimap.drawMap(mc, this);
        drawWaypoints(mouseX, mouseY);
        if (belongsTo(mc.theWorld)) {
            String status = dimension == null ? I18n.format("diorama.map.unsupported_dimension")
                : dimension.suffix()
                    .startsWith("dim-") ? I18n.format("diorama.map.dimension.custom", dimension.id())
                        : I18n.format("diorama.map.dimension." + dimension.suffix());
            int bottom = height - fontRendererObj.FONT_HEIGHT - 6;
            drawString(fontRendererObj, status, 6, bottom, 0xFFDDDDDD);
            int layer = ClientMinimap.layer(mc);
            if (layer != TileKey.SURFACE) {
                drawString(
                    fontRendererObj,
                    I18n.format("diorama.map.cave_layer", Math.min(256, layer)),
                    6,
                    bottom - 12,
                    0xFFDDDDDD);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawWaypoints(int mouseX, int mouseY) {
        if (!belongsTo(mc.theWorld)) return;
        Point hovered = null;
        double closest = 64;
        for (Point point : ClientWaypoints.visibleBook(mc)) {
            if (!point.enabled()) continue;
            double px = view.screenX(Projection.u(point.x() + .5, point.z() + .5));
            double py = view.screenY(Projection.v(point.x() + .5, point.y(), point.z() + .5));
            if (px < 4 || px > width - 4 || py < 4 || py > height - 4) continue;
            int x = (int) Math.round(px), y = (int) Math.round(py);
            drawRect(x - 4, y - 4, x + 4, y + 4, 0xFF211909);
            drawRect(x - 2, y - 2, x + 2, y + 2, 0xFFFFC53D);
            double distance = (px - mouseX) * (px - mouseX) + (py - mouseY) * (py - mouseY);
            if (distance < closest) {
                closest = distance;
                hovered = point;
            }
        }
        if (hovered != null
            && !(mouseY >= BUTTON_Y && mouseY < BUTTON_Y + BUTTON_HEIGHT && mouseX < WAYPOINT_X + WAYPOINT_WIDTH)) {
            String name = fontRendererObj.trimStringToWidth(hovered.name(), Math.max(0, width - 8));
            int x = Math.max(3, Math.min(mouseX + 9, width - fontRendererObj.getStringWidth(name) - 3));
            drawString(fontRendererObj, name, x, Math.max(2, mouseY - 11), 0xFFFFC53D);
        }
    }

    @Override
    public void updateScreen() {
        if (!belongsTo(mc.theWorld)) mc.displayGuiScreen(null);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
