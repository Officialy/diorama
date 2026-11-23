package dev.diorama.gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;

import dev.diorama.gtnh.client.LegacyWaypointBook.Point;

final class WaypointEditScreen extends GuiScreen {

    private final ClientWaypoints session;
    private final GuiScreen parent;
    private final int index;
    private final String[] values;
    private final GuiTextField[] fields = new GuiTextField[4];
    private boolean enabled, confirmDelete;
    private String error = "";

    WaypointEditScreen(ClientWaypoints session, GuiScreen parent, int index, Point point) {
        this.session = session;
        this.parent = parent;
        this.index = index;
        values = new String[] { point.name(), Integer.toString(point.x()), Integer.toString(point.y()),
            Integer.toString(point.z()) };
        enabled = point.enabled();
    }

    static void openNew(Minecraft mc, ClientWaypoints session, GuiScreen parent) {
        if (!session.valid(mc) || !session.writable()
            || mc.thePlayer == null
            || session.points()
                .size() >= LegacyWaypointBook.MAX_POINTS)
            return;
        int x = Math.max(-29999984, Math.min(29999983, MathHelper.floor_double(mc.thePlayer.posX)));
        int y = Math.max(0, Math.min(255, MathHelper.floor_double(mc.thePlayer.boundingBox.minY)));
        int z = Math.max(-29999984, Math.min(29999983, MathHelper.floor_double(mc.thePlayer.posZ)));
        mc.displayGuiScreen(
            new WaypointEditScreen(session, parent, -1, new Point(text("default_name"), x, y, z, true)));
    }

    static String text(String key, Object... args) {
        return I18n.format("diorama.waypoint." + key, args);
    }

    @Override
    public void initGui() {
        int left = width / 2 - 120;
        for (int i = 0; i < fields.length; i++) {
            int x = i == 0 ? left : left + (i - 1) * 82;
            fields[i] = new GuiTextField(fontRendererObj, x, i == 0 ? 46 : 83, i == 0 ? 240 : 76, 20);
            fields[i].setMaxStringLength(i == 0 ? LegacyWaypointBook.MAX_NAME : 10);
            fields[i].setText(values[i]);
        }
        fields[0].setFocused(true);
        if (index < 0) fields[0].setSelectionPos(0);
        buttonList.add(new GuiButton(0, left, 112, 116, 20, visibilityLabel()));
        buttonList.add(new GuiButton(1, left + 124, 112, 116, 20, text("save")));
        buttonList.add(new GuiButton(2, left, 136, 116, 20, I18n.format("gui.cancel")));
        if (index >= 0) buttonList.add(new GuiButton(3, left + 124, 136, 116, 20, deleteLabel()));
        Keyboard.enableRepeatEvents(true);
    }

    private String visibilityLabel() {
        return text("visible", I18n.format(enabled ? "options.on" : "options.off"));
    }

    private String deleteLabel() {
        return text(confirmDelete ? "confirm_delete" : "delete");
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!session.valid(mc)) return;
        switch (button.id) {
            case 0 -> {
                enabled = !enabled;
                button.displayString = visibilityLabel();
            }
            case 1 -> save();
            case 2 -> mc.displayGuiScreen(parent);
            case 3 -> {
                if (!confirmDelete) {
                    confirmDelete = true;
                    button.displayString = deleteLabel();
                } else if (session.change(mc, book -> book.remove(index))) mc.displayGuiScreen(parent);
                else error = text("save_failed");
            }
            default -> {}
        }
    }

    private void save() {
        if (!session.valid(mc)) return;
        Point point;
        try {
            point = new Point(
                values[0].trim(),
                Integer.parseInt(values[1].trim()),
                Integer.parseInt(values[2].trim()),
                Integer.parseInt(values[3].trim()),
                enabled);
        } catch (IllegalArgumentException invalid) {
            error = text("invalid");
            return;
        }
        if (session.change(mc, book -> {
            if (index < 0) book.add(point);
            else book.replace(index, point);
        })) mc.displayGuiScreen(parent);
        else error = text("save_failed");
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(session.valid(mc) ? parent : null);
        else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            if (!Keyboard.isRepeatEvent()) save();
        } else if (key == Keyboard.KEY_TAB) {
            int selected = 0;
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].isFocused()) selected = (i + (isShiftKeyDown() ? 3 : 1)) % 4;
                fields[i].setFocused(false);
            }
            fields[selected].setFocused(true);
        } else {
            for (int i = 0; i < fields.length; i++) {
                fields[i].textboxKeyTyped(character, key);
                values[i] = fields[i].getText();
            }
        }
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        for (GuiTextField field : fields) field.mouseClicked(x, y, button);
    }

    @Override
    public void drawScreen(int x, int y, float partial) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, text(index < 0 ? "new" : "edit"), width / 2, 12, -1);
        drawString(fontRendererObj, text("name"), width / 2 - 120, 33, -1);
        for (int i = 0; i < 3; i++)
            drawString(fontRendererObj, "XYZ".substring(i, i + 1), width / 2 - 120 + 82 * i, 70, -1);
        for (GuiTextField field : fields) field.drawTextBox();
        drawCenteredString(fontRendererObj, error, width / 2, 161, 0xFFFF7777);
        if (session.sessionOnly()) drawCenteredString(fontRendererObj, text("session_only"), width / 2, 175, -1);
        super.drawScreen(x, y, partial);
    }

    @Override
    public void updateScreen() {
        if (!session.valid(mc)) mc.displayGuiScreen(null);
        else for (GuiTextField field : fields) field.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
