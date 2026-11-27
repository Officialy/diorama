package dev.diorama.gtnh.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dev.diorama.gtnh.capture.TerrainPreview;

/**
 * Controlled in-game bake benchmark. Every variant uses the same detached terrain and
 * CPU reference. Results never enter the live tile cache, and no GL calls run on workers.
 */
final class RenderBenchmark {
    private static volatile boolean active;
    private volatile boolean cancelled, done;
    private volatile String status = "Waiting for current map jobs";
    private final List<TerrainPreview.Work> inputs;
    private final List<Result> results = new ArrayList<>();
    private volatile Result preview, refinement;
    private final long[] frameTimes = new long[8192];
    private int frameCount;
    private long lastFrame;
    private volatile double baselineMs;

    RenderBenchmark(List<TerrainPreview.Work> inputs) { this.inputs = List.copyOf(inputs); }

    static boolean active() { return active; }
    boolean done() { return done; }
    String status() { return status; }
    synchronized List<Result> results() { return List.copyOf(results); }
    Result preview() { return preview; }
    Result refinement() { return refinement; }

    synchronized boolean start() {
        if(active || inputs.size()<2)return false;
        active=true;
        Thread worker=new Thread(this::run,"Diorama-autoconfig");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY-1);
        worker.start();
        return true;
    }

    void cancel(){cancelled=true;}
    synchronized void frame(){
        long now=System.nanoTime();
        if(lastFrame!=0 && frameCount<frameTimes.length)frameTimes[frameCount++]=now-lastFrame;
        lastFrame=now;
    }
    private synchronized void resetFrames(){frameCount=0;lastFrame=0;}
    private synchronized double p95(){
        if(frameCount<3)return Double.NaN;
        long[] sorted=Arrays.copyOf(frameTimes,frameCount);
        Arrays.sort(sorted);
        return sorted[(int)Math.ceil(sorted.length*.95)-1]/1e6;
    }
    private void check() throws InterruptedException {
        if(cancelled || Thread.currentThread().isInterrupted())throw new InterruptedException();
        for(TerrainPreview.Work work:inputs)if(!work.stillCurrent())throw new InterruptedException("Terrain changed");
    }

    private void run(){
        try {
            while(TerrainPreviewJob.isBusy()){check();Thread.sleep(25);}
            status="Measuring idle frame time";
            resetFrames();Thread.sleep(1000);baselineMs=p95();
            check();
            status="Warming CPU renderer";
            inputs.get(0).bakeCoarse(null,4);
            inputs.get(0).bakeDetail(null,true);
            check();
            status="Building full-quality CPU reference";
            int[][] reference=new int[inputs.size()][];
            for(int i=0;i<inputs.size();i++){check();reference[i]=inputs.get(i).bakeDetail(null,true);}
            int comparable=0;
            for(int[] raster:reference) if(coverage(raster)>0.08)comparable++;
            if(comparable<2){status="Not enough mapped terrain. Explore more and retry.";return;}

            // Full factorial for the CPU knobs that are independent. GPU submission is serial
            // by design, so additional GPU worker counts would only exercise CPU fallback.
            for(int workers=1;workers<=4;workers++){
                test("CPU full",workers,0,reference);
                for(int stride:new int[]{2,4,8})test("CPU preview",workers,stride,reference);
            }
            status="Warming GPU preview";
            LegacyGpuBaker.preview(inputs.get(0),true);
            test("GPU preview",1,0,reference);
            status="Warming GPU refinement";
            LegacyGpuVolume.refine(inputs.get(0),true,false);
            test("GPU 1 sample",1,0,reference);
            test("GPU 4 samples",1,0,reference);

            double bestPreview=Double.POSITIVE_INFINITY,bestRefine=Double.POSITIVE_INFINITY;
            for(Result r:results()){
                if(!r.valid())continue;
                boolean isPreview=r.stride()>0||r.mode().equals("GPU preview");
                double score=r.ms();
                if(Double.isFinite(r.p95())&&Double.isFinite(baselineMs))
                    score*=1+Math.max(0,r.p95()-baselineMs-3)/Math.max(8,baselineMs);
                if(isPreview){
                    // Prefer useful visual fidelity, not a nearly empty fast raster.
                    if(r.recall()<.85||r.precision()<.88||r.error()>.18)continue;
                    if(r.stride()==8)score*=1.25;
                    if(score<bestPreview){bestPreview=score;preview=r;}
                }else{
                    if(r.recall()<.97||r.precision()<.98||r.error()>.08)continue;
                    if(r.mode().startsWith("GPU"))score*=1.15; // non-persistent, approximate
                    if(score<bestRefine){bestRefine=score;refinement=r;}
                }
            }
            if (refinement != null) {
                // The chosen CPU preview must be measured at the actual worker count. GPU is serial.
                Result compatible = null;
                double best = Double.POSITIVE_INFINITY;
                for (Result r : results()) {
                    if (!r.valid() || !(r.stride()>0 || r.mode().equals("GPU preview"))) continue;
                    if (r.stride()>0 && r.workers()!=refinement.workers()) continue;
                    if (r.recall()<.85 || r.precision()<.88 || r.error()>.18) continue;
                    double score=r.ms()*(r.stride()==8?1.25:1);
                    if (Double.isFinite(r.p95()) && Double.isFinite(baselineMs))
                        score*=1+Math.max(0,r.p95()-baselineMs-3)/Math.max(8,baselineMs);
                    if(score<best){best=score;compatible=r;}
                }
                preview=compatible;
            }
            status=refinement==null?"No reliable recommendation; keep your current settings":"Complete - review and apply recommendation";
        }catch(InterruptedException interrupted){status="Benchmark cancelled or terrain invalidated";}
        catch(RuntimeException|LinkageError failure){status="Benchmark failed: "+failure.getClass().getSimpleName();}
        finally{done=true;active=false;}
    }

    private void test(String mode,int workers,int stride,int[][] reference) throws InterruptedException {
        check();
        status=String.format(Locale.ROOT,"Testing %s / %d worker(s)%s",mode,workers,stride==0?"":" / stride "+stride);
        boolean nativeGpu=mode.startsWith("GPU");
        ExecutorService pool=Executors.newFixedThreadPool(workers,task->{
            Thread t=new Thread(task,"Diorama-benchmark-worker");t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY-1);return t;
        });
        long elapsed=0; double coverage=0,precision=0,error=0;int measured=0;
        boolean valid=true;
        resetFrames();
        try {
            for(int pass=0;pass<2;pass++){
                check();
                List<Callable<int[]>> tasks=new ArrayList<>();
                for(TerrainPreview.Work work:inputs)tasks.add(()->render(mode,stride,work));
                long start=System.nanoTime();
                List<Future<int[]>> pending=pool.invokeAll(tasks);
                elapsed+=System.nanoTime()-start;
                for(int i=0;i<pending.size();i++){
                    check();
                    int[] image;
                    try{image=pending.get(i).get();}
                    catch(java.util.concurrent.ExecutionException failure){valid=false;continue;}
                    if(coverage(reference[i])<=.08)continue;
                    if(image==null){valid=false;continue;}
                    double[] metrics=compare(reference[i],image);
                    coverage+=metrics[0];precision+=metrics[1];error+=metrics[2];measured++;
                }
                if(nativeGpu&&!valid)break;
            }
        }finally{
            pool.shutdownNow();
            // A cancelled CPU ray trace can finish its detached tile before normal mapping resumes.
            while(!pool.awaitTermination(100,TimeUnit.MILLISECONDS)) { }
        }
        double ms=elapsed/1e6/(inputs.size()*2);
        Result result=new Result(mode,workers,stride,ms,p95(),measured==0?0:coverage/measured,
            measured==0?0:precision/measured,measured==0?1:error/measured,valid&&measured>0);
        synchronized(this){results.add(result);}
    }

    private static int[] render(String mode,int stride,TerrainPreview.Work work){
        return switch(mode){
            case "CPU full" -> work.bakeDetail(null,true);
            case "CPU preview" -> work.bakeCoarse(null,stride);
            case "GPU preview" -> LegacyGpuBaker.preview(work,true);
            case "GPU 1 sample" -> LegacyGpuVolume.refine(work,true,false);
            case "GPU 4 samples" -> LegacyGpuVolume.refine(work,true,true);
            default -> throw new IllegalArgumentException("Unknown benchmark mode");
        };
    }
    private static double coverage(int[] raster){
        if(raster==null)return 0;int n=0;
        for(int p:raster)if((p>>>24)>=128)n++;
        return n/(double)raster.length;
    }

    /** Compare coverage both ways and mean RGB absolute error on jointly known pixels. */
    static double[] compare(int[] reference,int[] sample){
        if(reference==null||sample==null||reference.length!=sample.length)return new double[]{0,0,1};
        int expected=0,actual=0,shared=0;long error=0;
        for(int i=0;i<reference.length;i++){
            int a=reference[i],b=sample[i];
            boolean ka=(a>>>24)>=128,kb=(b>>>24)>=128;
            if(ka)expected++;if(kb)actual++;
            if(ka&&kb){shared++;for(int shift=0;shift<24;shift+=8)
                error+=Math.abs(((a>>>shift)&255)-((b>>>shift)&255));}
        }
        return new double[]{shared/(double)Math.max(1,expected),shared/(double)Math.max(1,actual),
            shared==0?1:error/(shared*765.0)};
    }

    /** Apply only tested bake knobs; capture/cache settings need a separate live exploration test. */
    boolean apply(MinimapOptions opts){
        if(!done||cancelled||refinement==null)return false;
        opts.progressive=true;opts.workers=refinement.workers();
        opts.gpuRefine=refinement.mode().startsWith("GPU");
        opts.gpuFour=refinement.mode().equals("GPU 4 samples");
        if(preview!=null){
            opts.gpuPreview=preview.mode().equals("GPU preview");
            if(preview.stride()>0)opts.stride=preview.stride();

        }
        return ClientMinimap.saveOptions();
    }

    record Result(String mode,int workers,int stride,double ms,double p95,double recall,double precision,
        double error,boolean valid){}
}
