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
        buttonList.add(new GuiButton(0, 6, 6, 114, 20, I18n.format("diorama.map.recenter")));
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
        if (button.id == 0) recenter();
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
        if (button == 0 && !(x >= 6 && x < 120 && y >= 6 && y < 26)) {
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
