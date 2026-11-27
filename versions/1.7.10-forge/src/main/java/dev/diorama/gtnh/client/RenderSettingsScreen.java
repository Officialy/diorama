package dev.diorama.gtnh.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/** Live, persistent rendering controls. Changes do not require a client restart. */
final class RenderSettingsScreen extends GuiScreen {
    private final SharedTerrainMapScreen map;
    private final GuiScreen parent;
    private final MinimapOptions options = ClientMinimap.options();
    private int page;
    private boolean saveFailed;

    RenderSettingsScreen(SharedTerrainMapScreen map, GuiScreen parent) {
        this.map = map;
        this.parent = parent;
    }

    @Override public void initGui() {
        int[] ids = page == 0 ? new int[] {0,1,2,3,4,5,6,7,22,20,21}
            : new int[] {8,9,10,11,12,13,14,15,22,20,21};
        int columns = width >= 300 ? 2 : 1;
        int cell = Math.min(200, (width - 16 - (columns-1)*4)/columns);
        int rows = (ids.length+columns-1)/columns;
        int step = Math.max(20, Math.min(24, (height-38)/rows));
        int left = (width-columns*cell-(columns-1)*4)/2;
        int top = Math.max(16,(height-rows*step)/2);
        for (int i=0;i<ids.length;i++) {
            int id=ids[i];
            buttonList.add(new GuiButton(id,left+(i%columns)*(cell+4),top+(i/columns)*step,cell,20,label(id)));
        }
    }
    private static String on(boolean b) { return b ? "On" : "Off"; }
    private String label(int id) {
        return switch(id) {
            case 0 -> "Progressive previews: "+on(options.progressive);
            case 1 -> "Single-pass fast quality: "+on(options.fast);
            case 2 -> "Preview samples: "+(512/options.stride)+"px";
            case 3 -> "CPU workers: "+options.workers;
            case 4 -> "GPU preview: "+on(options.gpuPreview);
            case 5 -> "GPU refinement (experimental): "+on(options.gpuRefine);
            case 6 -> "GPU samples: "+(options.gpuFour?4:1);
            case 7 -> "High resolution: "+on(options.highResolution);
            case 8 -> "Capture radius: "+(options.radius==0?"Auto":Integer.toString(options.radius));
            case 9 -> "Capture budget: "+options.captureMs+"ms";
            case 10 -> "Chunks per tick: "+options.captures;
            case 11 -> "Resident tiles: "+options.resident;
            case 12 -> "History tiles: "+options.history;
            case 13 -> "Uploads per frame: "+options.uploads;
            case 14 -> "Smooth zoom: "+on(options.smoothZoom);
            case 15 -> "Volumetric clouds: "+on(options.volumetricFog);
            case 22 -> "Benchmark / Auto-config";
            case 20 -> page==0?"Capture and cache settings":"Quality and backend settings";
            default -> "Done";
        };
    }
    private static int cycle(int value,int... choices) {
        for(int i=0;i<choices.length;i++) if(choices[i]==value) return choices[(i+1)%choices.length];
        return choices[0];
    }
    @Override protected void actionPerformed(GuiButton button) {
        if(!map.belongsTo(mc.theWorld)){mc.displayGuiScreen(null);return;}
        switch(button.id){
            case 0 -> options.progressive=!options.progressive;
            case 1 -> options.fast=!options.fast;
            case 2 -> options.stride=cycle(options.stride,2,4,8);
            case 3 -> options.workers=cycle(options.workers,1,2,3,4);
            case 4 -> options.gpuPreview=!options.gpuPreview;
            case 5 -> options.gpuRefine=!options.gpuRefine;
            case 6 -> options.gpuFour=!options.gpuFour;
            case 7 -> options.highResolution=!options.highResolution;
            case 8 -> options.radius=cycle(options.radius,0,4,8,12,16,24,32);
            case 9 -> options.captureMs=cycle(options.captureMs,2,4,6,8,12);
            case 10 -> options.captures=cycle(options.captures,4,8,12,16,24);
            case 11 -> options.resident=cycle(options.resident,96,128,192,256,384,512);
            case 12 -> options.history=cycle(options.history,0,64,128,192,256,384,512);
            case 13 -> options.uploads=cycle(options.uploads,1,2,3,4);
            case 14 -> options.smoothZoom=!options.smoothZoom;
            case 15 -> options.volumetricFog=!options.volumetricFog;
            case 22 -> {mc.displayGuiScreen(new RenderBenchmarkScreen(map,this));return;}
            case 20 -> {page=1-page;buttonList.clear();initGui();return;}
            case 21 -> {mc.displayGuiScreen(parent);return;}
            default -> {return;}
        }
        saveFailed=!ClientMinimap.saveOptions();
        button.displayString=label(button.id);
    }
    @Override protected void keyTyped(char c,int key){if(key==Keyboard.KEY_ESCAPE)mc.displayGuiScreen(parent);else super.keyTyped(c,key);}
    @Override public void updateScreen(){if(!map.belongsTo(mc.theWorld))mc.displayGuiScreen(null);}
    @Override public void drawScreen(int x,int y,float delta){
        drawDefaultBackground();
        drawCenteredString(fontRendererObj,"Diorama rendering settings",width/2,4,-1);
        if(saveFailed)drawCenteredString(fontRendererObj,"Could not save settings",width/2,height-10,0xFFFF7777);
        super.drawScreen(x,y,delta);
    }
    @Override public boolean doesGuiPauseGame(){return false;}
}
