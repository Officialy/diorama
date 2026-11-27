package dev.diorama.gtnh.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Review measured candidates before applying the automatically ranked bake configuration. */
final class RenderBenchmarkScreen extends GuiScreen {
    private final SharedTerrainMapScreen map;
    private final GuiScreen parent;
    private RenderBenchmark benchmark;
    private GuiButton start,apply;
    private String message="Stand in a loaded, representative area before starting.";
    private int scroll;

    RenderBenchmarkScreen(SharedTerrainMapScreen map,GuiScreen parent){this.map=map;this.parent=parent;}

    @Override public void initGui(){
        int cell=Math.max(60,Math.min(132,(width-24)/3));
        int x=(width-3*cell-8)/2,y=height-25;
        start=new GuiButton(0,x,y,cell,20,"Run benchmark");
        apply=new GuiButton(1,x+cell+4,y,cell,20,"Apply recommended");
        buttonList.add(start);buttonList.add(apply);
        buttonList.add(new GuiButton(2,x+2*(cell+4),y,cell,20,"Back"));
    }
    @Override protected void actionPerformed(GuiButton button){
        if(button.id==2){mc.displayGuiScreen(parent);return;}
        if(button.id==0){
            if(RenderBenchmark.active())return;
            List<dev.diorama.gtnh.capture.TerrainPreview.Work> inputs=ClientMinimap.benchmarkInputs(mc);
            if(inputs.size()<2){message="Not enough captured surface tiles. Explore more and retry.";return;}
            benchmark=new RenderBenchmark(inputs);
            if(!benchmark.start()){message="Could not start; another benchmark is active.";return;}
            scroll=0;
        }
        if(button.id==1&&benchmark!=null){
            message=benchmark.apply(ClientMinimap.options())?"Recommended settings applied and saved":"Could not save recommendation";
        }
    }
    @Override public void handleMouseInput(){
        super.handleMouseInput();
        int wheel=Mouse.getEventDWheel();
        if(wheel!=0)scroll=Math.max(0,scroll+(wheel<0?1:-1));
    }
    @Override public void drawScreen(int x,int y,float delta){
        drawDefaultBackground();
        drawCenteredString(fontRendererObj,"Diorama render benchmark / auto-config",width/2,5,-1);
        if(benchmark!=null)benchmark.frame();
        String status=benchmark==null?message:benchmark.status();
        drawCenteredString(fontRendererObj,fontRendererObj.trimStringToWidth(status,width-12),width/2,18,-1);
        drawCenteredString(fontRendererObj,"Same terrain, two trials; lower tile time and frame p95 are better.",width/2,30,0xFFAAAAAA);
        if(benchmark!=null){
            List<RenderBenchmark.Result> rows=benchmark.results();
            int count=Math.max(1,(height-100)/12);
            scroll=Math.min(scroll,Math.max(0,rows.size()-count));
            int yy=43;
            for(int i=scroll;i<Math.min(rows.size(),scroll+count);i++){
                var r=rows.get(i);
                String line=String.format(Locale.ROOT,"%s %dW%s | %.1fms/tile | p95 %.1f | %.0f/%.0f%% | RGB %.2f",
                    r.mode(),r.workers(),r.stride()==0?"":"/"+r.stride(),r.ms(),r.p95(),r.recall()*100,r.precision()*100,r.error());
                drawString(fontRendererObj,fontRendererObj.trimStringToWidth(r.valid()?line:r.mode()+" - unsupported/fallback",width-12),
                    6,yy,r.valid()?0xFFE0E0E0:0xFFFF7777);
                yy+=12;
            }
            if(benchmark.done()){
                var p=benchmark.preview();var r=benchmark.refinement();
                String recommendation=r==null?"No recommendation":String.format(Locale.ROOT,"Recommended: %s / %d CPU workers; %s",
                    r.mode(),r.workers(),p==null?"existing preview":p.mode()+(p.stride()>0?" stride "+p.stride():""));
                drawCenteredString(fontRendererObj,fontRendererObj.trimStringToWidth(recommendation,width-12),width/2,height-53,0xFF88EE88);
                drawCenteredString(fontRendererObj,fontRendererObj.trimStringToWidth(message,width-12),width/2,height-41,0xFFAAAAAA);
            }
        }
        start.enabled=!RenderBenchmark.active();
        apply.enabled=benchmark!=null&&benchmark.done()&&benchmark.refinement()!=null;
        super.drawScreen(x,y,delta);
    }
    @Override protected void keyTyped(char c,int key){if(key==Keyboard.KEY_ESCAPE)mc.displayGuiScreen(parent);else super.keyTyped(c,key);}
    @Override public void updateScreen(){if(!map.belongsTo(mc.theWorld))mc.displayGuiScreen(null);}
    @Override public void onGuiClosed(){if(benchmark!=null&&!benchmark.done())benchmark.cancel();}
    @Override public boolean doesGuiPauseGame(){return false;}
}
