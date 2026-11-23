package dev.diorama.gtnh.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import dev.diorama.Projection;

/** Fullscreen view of the same resident surface tiles used by the HUD. */
final class SharedTerrainMapScreen extends GuiScreen {

    private static final int BUTTON_X = 6;
    private static final int BUTTON_Y = 6;
    private static final int BUTTON_WIDTH = 114;
    private static final int BUTTON_HEIGHT = 20;
    private static final int OPTIONS_X = BUTTON_X + BUTTON_WIDTH + 4;
    private final WorldClient world;
    private final MapViewportController view = new MapViewportController(0, 0, 4, 0.25, 16);
    private boolean initialized, dragging;
    private int lastX, lastY;

    SharedTerrainMapScreen(WorldClient world) {
        this.world = world;
    }

    boolean belongsTo(WorldClient current) {
        return initialized && current == world;
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
        dragging = false;
        if (!initialized) {
            recenter();
            initialized = true;
        }
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
            && ((x >= BUTTON_X && x < BUTTON_X + BUTTON_WIDTH) || (x >= OPTIONS_X && x < OPTIONS_X + BUTTON_WIDTH));
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
        super.drawScreen(mouseX, mouseY, partialTicks);
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
