package dev.diorama.gtnh.client;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

import dev.diorama.gtnh.client.LegacyWaypointBook.Point;

final class WaypointListScreen extends GuiScreen {

    private final ClientWaypoints session;
    private final GuiScreen parent;
    private int page, pageSize, lastPage;

    WaypointListScreen(ClientWaypoints session, GuiScreen parent) {
        this.session = session;
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int left = width / 2 - 140;
        pageSize = Math.max(1, Math.min(8, (height - 88) / 22));
        List<Point> points = session.points();
        lastPage = Math.max(0, (points.size() - 1) / pageSize);
        page = Math.min(page, lastPage);
        for (int i = page * pageSize; i < Math.min(points.size(), (page + 1) * pageSize); i++) {
            Point point = points.get(i);
            String location = "  [" + point.x() + ", " + point.y() + ", " + point.z() + "]";
            String prefix = point.enabled() ? "" : "- ";
            String name = fontRendererObj
                .trimStringToWidth(point.name(), 260 - fontRendererObj.getStringWidth(location + prefix));
            buttonList.add(new GuiButton(10 + i, left, 38 + (i % pageSize) * 22, 280, 20, prefix + name + location));
        }
        int bottom = height - 28;
        button(0, left, bottom, 30, "<", page > 0);
        button(1, left + 34, bottom, 30, ">", page < lastPage);
        button(
            2,
            left + 68,
            bottom,
            102,
            WaypointEditScreen.text("new"),
            session.writable() && points.size() < LegacyWaypointBook.MAX_POINTS);
        button(3, left + 174, bottom, 106, net.minecraft.client.resources.I18n.format("gui.done"), true);
    }

    private void button(int id, int x, int y, int w, String label, boolean enabled) {
        GuiButton button = new GuiButton(id, x, y, w, 20, label);
        button.enabled = enabled;
        buttonList.add(button);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!session.valid(mc)) return;
        if (button.id == 0 || button.id == 1) {
            page += button.id == 0 ? -1 : 1;
            buttonList.clear();
            initGui();
        } else if (button.id == 2) {
            WaypointEditScreen.openNew(mc, session, this);
        } else if (button.id == 3) {
            mc.displayGuiScreen(parent);
        } else if (button.id >= 10 && button.id - 10 < session.points()
            .size()) {
                int index = button.id - 10;
                mc.displayGuiScreen(
                    new WaypointEditScreen(
                        session,
                        this,
                        index,
                        session.points()
                            .get(index)));
            }
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(session.valid(mc) ? parent : null);
        else super.keyTyped(character, key);
    }

    @Override
    public void drawScreen(int x, int y, float partial) {
        drawDefaultBackground();
        drawCenteredString(
            fontRendererObj,
            WaypointEditScreen.text("list") + "  " + (page + 1) + "/" + (lastPage + 1),
            width / 2,
            8,
            -1);
        String status = !session.writable() ? WaypointEditScreen.text("unavailable")
            : session.sessionOnly() ? WaypointEditScreen.text("session_only")
                : session.points()
                    .isEmpty() ? WaypointEditScreen.text("empty") : "";
        drawCenteredString(fontRendererObj, status, width / 2, 22, 0xFFDDDDDD);
        super.drawScreen(x, y, partial);
    }

    @Override
    public void updateScreen() {
        if (!session.valid(mc)) mc.displayGuiScreen(null);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
