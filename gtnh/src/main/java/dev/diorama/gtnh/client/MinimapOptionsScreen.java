package dev.diorama.gtnh.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;

/** Options return to the same map viewport instead of reopening at the player. */
final class MinimapOptionsScreen extends GuiScreen {

    private static final String LABEL = "diorama.minimap.options";
    private final SharedTerrainMapScreen parent;
    private final MinimapOptions options = ClientMinimap.options();
    private boolean saveFailed;

    MinimapOptionsScreen(SharedTerrainMapScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int buttonWidth = Math.min(200, Math.max(1, width - 12));
        int x = (width - buttonWidth) / 2;
        int y = Math.max(20, (height - 92) / 2);
        for (int id = 0; id < 4; id++) {
            buttonList.add(new GuiButton(id, x, y + id * 24, buttonWidth, 20, label(id)));
        }
    }

    private String label(int id) {
        return switch (id) {
            case 0 -> I18n.format(LABEL + ".visible", state(options.visible));
            case 1 -> I18n.format(LABEL + ".coordinates", state(options.coordinates));
            case 2 -> I18n.format(LABEL + ".size", options.size());
            default -> I18n.format("gui.done");
        };
    }

    private static String state(boolean enabled) {
        return I18n.format(enabled ? "options.on" : "options.off");
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!parent.belongsTo(mc.theWorld)) {
            mc.displayGuiScreen(null);
            return;
        }
        switch (button.id) {
            case 0 -> options.visible = !options.visible;
            case 1 -> options.coordinates = !options.coordinates;
            case 2 -> options.cycleSize();
            case 3 -> {
                mc.displayGuiScreen(parent);
                return;
            }
            default -> {
                return;
            }
        }
        saveFailed = !ClientMinimap.saveOptions();
        button.displayString = label(button.id);
    }

    @Override
    protected void keyTyped(char character, int code) {
        if (code == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(parent.belongsTo(mc.theWorld) ? parent : null);
        else super.keyTyped(character, code);
    }

    @Override
    public void updateScreen() {
        if (!parent.belongsTo(mc.theWorld)) mc.displayGuiScreen(null);
    }

    @Override
    public void drawScreen(int x, int y, float delta) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format(LABEL), width / 2, Math.max(5, (height - 92) / 2 - 16), -1);
        if (saveFailed) {
            drawCenteredString(
                fontRendererObj,
                I18n.format(LABEL + ".save_failed"),
                width / 2,
                Math.max(20, (height - 92) / 2) + 98,
                0xFFFF7777);
        }
        super.drawScreen(x, y, delta);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
