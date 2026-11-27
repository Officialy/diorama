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
    private boolean saveFailed, advanced;

    MinimapOptionsScreen(SharedTerrainMapScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int buttonWidth = Math.min(200, Math.max(1, width - 12));
        int x = (width - buttonWidth) / 2;
        int[] ids = advanced ? new int[] { 8,9,10,11,12,14,13,7 } : new int[] { 0,1,2,3,4,5,6,13,7 };
        int columns = width >= 300 ? 2 : 1;
        int gap = 4;
        int rows = (ids.length + columns - 1) / columns;
        int cell = columns == 2 ? Math.min(200,(width-20)/2) : buttonWidth;
        x = (width - columns*cell - (columns-1)*gap)/2;
        int spacing = Math.max(20,Math.min(24,(height-42)/Math.max(1,rows)));
        int y = Math.max(18,(height-rows*spacing)/2);
        for (int i=0;i<ids.length;i++) {
            int id=ids[i];
            buttonList.add(new GuiButton(id,x+(i%columns)*(cell+gap),y+(i/columns)*spacing,cell,20,label(id)));
        }
    }

    private String label(int id) {
        return switch (id) {
            case 0 -> I18n.format(LABEL + ".visible", state(options.visible));
            case 1 -> I18n.format(LABEL + ".coordinates", state(options.coordinates));
            case 2 -> I18n.format(LABEL + ".size", options.size());
            case 3 -> I18n.format(LABEL + ".fog", state(options.fog));
            case 4 -> I18n.format(LABEL + ".smooth", state(options.smoothZoom));
            case 5 -> I18n.format(LABEL + ".world", state(options.worldWaypoints));
            case 6 -> I18n.format(LABEL + ".death", state(options.deathWaypoints));
            case 8 -> I18n.format(LABEL + ".radar",state(options.radar));
            case 9 -> I18n.format(LABEL + ".players",state(options.playerRadar));
            case 10 -> I18n.format(LABEL + ".beams",state(options.beams));
            case 11 -> I18n.format(LABEL + ".volumetric",state(options.volumetricFog));
            case 12 -> I18n.format(LABEL + ".hd",state(options.highResolution));
            case 14 -> "Rendering / performance";
            case 13 -> I18n.format(LABEL + (advanced ? ".basic" : ".advanced"));
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
            case 3 -> options.fog = !options.fog;
            case 4 -> options.smoothZoom = !options.smoothZoom;
            case 5 -> options.worldWaypoints = !options.worldWaypoints;
            case 6 -> options.deathWaypoints = !options.deathWaypoints;
            case 8 -> options.radar = !options.radar;
            case 9 -> options.playerRadar = !options.playerRadar;
            case 10 -> options.beams = !options.beams;
            case 11 -> options.volumetricFog = !options.volumetricFog;
            case 12 -> options.highResolution = !options.highResolution;
            case 14 -> {
                mc.displayGuiScreen(new RenderSettingsScreen(parent, this));
                return;
            }
            case 13 -> {
                advanced = !advanced;
                buttonList.clear();
                initGui();
                return;
            }
            case 7 -> {
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
        drawCenteredString(fontRendererObj, I18n.format(LABEL), width / 2, 5, -1);
        if (saveFailed) {
            drawCenteredString(
                fontRendererObj,
                I18n.format(LABEL + ".save_failed"),
                width / 2,
                height - 12,
                0xFFFF7777);
        }
        super.drawScreen(x, y, delta);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
