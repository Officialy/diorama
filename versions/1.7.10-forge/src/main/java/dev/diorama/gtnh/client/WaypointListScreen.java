package dev.diorama.gtnh.client;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.lwjgl.input.Keyboard;

import dev.diorama.gtnh.client.LegacyWaypointBook.Point;

final class WaypointListScreen extends GuiScreen {

    private final ClientWaypoints session;
    private final GuiScreen parent;
    private int page, pageSize, lastPage, sort;
    private String query = "";
    private GuiTextField search;
    private List<Integer> shown = List.of();

    WaypointListScreen(ClientWaypoints session, GuiScreen parent) {
        this.session = session;
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int left = width / 2 - 140;
        pageSize = Math.max(1, Math.min(8, (height - 116) / 22));
        List<Point> points = session.points();
        search = new GuiTextField(fontRendererObj, left, 38, 174, 20);
        search.setMaxStringLength(48);
        search.setText(query);
        button(4, left + 180, 38, 100, sort == 0 ? "Nearest" : sort == 1 ? "Name A-Z" : "Newest", true);
        List<Integer> matching = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (point.label().toLowerCase(Locale.ROOT).contains(needle)) matching.add(i);
        }
        Comparator<Integer> names = Comparator.comparing(i -> points.get(i).label().toLowerCase(Locale.ROOT));
        double px = mc.thePlayer == null ? 0 : mc.thePlayer.posX;
        double pz = mc.thePlayer == null ? 0 : mc.thePlayer.posZ;
        Comparator<Integer> order = sort == 0 ? Comparator.comparingDouble(i -> {
            Point p = points.get(i);
            return Math.hypot(p.x() - px, p.z() - pz);
        }) : sort == 1 ? names : Comparator.reverseOrder();
        matching.sort(Comparator.<Integer, Boolean>comparing(i -> !points.get(i).enabled()).thenComparing(order)
            .thenComparing(names));
        shown = List.copyOf(matching);
        lastPage = Math.max(0, (shown.size() - 1) / pageSize);
        page = Math.min(page, lastPage);
        for (int i = page * pageSize; i < Math.min(shown.size(), (page + 1) * pageSize); i++) {
            int index = shown.get(i);
            Point point = points.get(index);
            String location = "  [" + point.x() + ", " + point.y() + ", " + point.z() + "]";
            String prefix = point.enabled() ? "" : "- ";
            String name = fontRendererObj
                .trimStringToWidth(new StringBuilder(point.icon()).append(" ").append(point.label()).toString(), 260 - fontRendererObj.getStringWidth(location + prefix));
            buttonList.add(new GuiButton(10 + index, left, 62 + (i % pageSize) * 22, 280, 20, prefix + name + location));
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
        } else if (button.id == 4) {
            sort = (sort + 1) % 3;
            page = 0;
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
        else if (search.textboxKeyTyped(character, key)) {
            query = search.getText();
            page = 0;
            buttonList.clear();
            initGui();
            search.setFocused(true);
            search.setCursorPositionEnd();
        } else super.keyTyped(character, key);
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
        search.drawTextBox();
        if (query.isEmpty() && !search.isFocused()) drawString(fontRendererObj, "Search waypoints", width / 2 - 136, 44, 0xFFAAAAAA);
        super.drawScreen(x, y, partial);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        search.mouseClicked(x, y, button);
    }

    @Override
    public void updateScreen() {
        if (!session.valid(mc)) mc.displayGuiScreen(null);
        else search.updateCursorCounter();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
