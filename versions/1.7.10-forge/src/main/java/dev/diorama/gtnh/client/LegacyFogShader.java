package dev.diorama.gtnh.client;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GLContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Legacy adaptation of upstream fog.fsh and FogTextures: one coverage mask for the entire
 * viewport, blurred known share, elevated layers, sun occlusion and tileable noise.
 * Coverage is derived only from actually uploaded terrain. No world or server queries.
 */
final class LegacyFogShader {
    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static final int MASK = 256;
    private static final float[] known = new float[MASK*MASK], scratch = new float[MASK*MASK],
        near = new float[MASK*MASK], wide = new float[MASK*MASK];
    private static ByteBuffer upload;
    private static int program, maskTexture, noiseTexture;
    private static boolean failed, collecting;
    private static double left, top, width, height;
    private static final String VERTEX = "#version 120\n"
        + "varying vec2 uv; void main(){uv=gl_MultiTexCoord0.xy;gl_Position=gl_ModelViewProjectionMatrix*gl_Vertex;}";
    private static final String FRAGMENT = "#version 120\n"
        + "varying vec2 uv;uniform sampler2D terrain;uniform sampler2D noiseTex;uniform vec2 origin;"
        + "uniform vec2 aspect;uniform float units,seconds,cave,masked,useNoise;"
        + "float hash(vec2 p){return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453);}"
        + "vec2 span(){return units*vec2(aspect.x>0.0?aspect.x:1.0,aspect.y>0.0?aspect.y:1.0);}"
        + "float knownAt(vec2 p){return masked<0.5?0.0:texture2D(terrain,clamp(p,vec2(0.001),vec2(0.999))).r;}"
        + "float soft(vec2 p){return masked<0.5?0.0:texture2D(terrain,clamp(p,vec2(0.001),vec2(0.999))).g;}"
        + "float noiseAt(vec2 p){vec2 n=(origin+p*span())/512.0;"
        + "if(useNoise<0.5)return 0.5;"
        + "float a=texture2D(noiseTex,n+vec2(5.0,2.0)*seconds/1200.0).r;"
        + "float b=texture2D(noiseTex,n+vec2(-8.0,3.0)*seconds/1200.0).g;"
        + "float c=texture2D(noiseTex,n+vec2(13.0,-9.0)*seconds/1200.0).b;return a*.55+b*.3+c*.15;}"
        + "float fogHeight(){return mix(10.0,4.0,cave);}"
        + "float thickness(vec2 p,float k){if(k>=.95)return 0.0;float n=noiseAt(p),unknown=1.0-k;"
        + "float edge=smoothstep(0.0,.35,unknown);float amount=smoothstep(.12,.75,unknown+(n-.5)*1.5*edge);"
        + "return fogHeight()*amount*(.45+.75*n);}"
        + "void main(){float k=knownAt(uv),s=soft(uv),hmax=fogHeight();"
        + "vec3 lit=mix(vec3(.97,.97,.99),vec3(.24,.26,.33),cave);"
        + "vec3 shade=mix(vec3(.48,.55,.67),vec3(.035,.04,.055),cave);"
        + "vec2 world=origin+uv*span();float jitter=hash(mod(floor(world*4.0),4096.0))-.5;"
        + "vec3 color=vec3(0.0);float alpha=0.0;vec2 toSun=vec2(-9.0,-3.6)/span();"
        + "for(int i=0;i<8;i++){float h=hmax*(1.0-(float(i)+.5+jitter*.6)/8.0);"
        + "vec2 ground=uv+vec2(0.0,h*.643)/span();float th=thickness(ground,soft(ground));"
        + "if(th<=.001)continue;float a=smoothstep(h-1.4,h+1.4,th);if(a<=.002)continue;"
        + "vec2 probe=ground+toSun;float toward=thickness(probe,knownAt(probe));"
        + "float occ=clamp((toward-th)/hmax*2.2,0.0,1.0);"
        + "float light=clamp(.5+.7*h/hmax-occ*.7,0.0,1.0);float la=a*.45;"
        + "color+=(1.0-alpha)*la*mix(shade,lit,light);alpha+=(1.0-alpha)*la;if(alpha>.985)break;}"
        + "float floorFog=smoothstep(.5,2.5,thickness(uv,s));"
        + "color+=(1.0-alpha)*floorFog*shade;alpha+=(1.0-alpha)*floorFog;"
        + "alpha*=1.0-.85*smoothstep(.45,.95,mix(s,k,cave));"
        + "float empty=1.0-smoothstep(.3,.85,k);vec3 cover=alpha>.02?color/alpha:mix(shade,lit,.7);"
        + "color+=(1.0-alpha)*empty*cover;alpha+=(1.0-alpha)*empty;"
        + "gl_FragColor=vec4(alpha>.002?clamp(color/alpha,0.0,1.0):mix(shade,lit,.7),alpha);}";

    private LegacyFogShader() {}

    static void begin(double x,double y,double w,double h) {
        collecting = !failed && ClientMinimap.options().fog && ClientMinimap.options().volumetricFog
            && w>0 && h>0;
        if (!collecting) return;
        left=x;top=y;width=w;height=h;
        Arrays.fill(known,0);
    }

    /** Add coverage from one displayed quad, using the raster that actually backs the GL texture. */
    static void cover(int[] pixels,double x0,double y0,double x1,double y1,
        double u0,double v0,double u1,double v1) {
        if(!collecting || pixels==null || x1<=x0 || y1<=y0) return;
        int edge=(int)Math.sqrt(pixels.length);
        if(edge*edge!=pixels.length) return;
        int a=Math.max(0,(int)Math.ceil((x0-left)*MASK/width-.5));
        int b=Math.min(MASK,(int)Math.ceil((x1-left)*MASK/width-.5));
        int c=Math.max(0,(int)Math.ceil((y0-top)*MASK/height-.5));
        int d=Math.min(MASK,(int)Math.ceil((y1-top)*MASK/height-.5));
        for(int y=c;y<d;y++){
            double py=top+(y+.5)*height/MASK;
            int sy=Math.max(0,Math.min(edge-1,(int)((v0+(py-y0)/(y1-y0)*(v1-v0))*edge)));
            for(int x=a;x<b;x++){
                double px=left+(x+.5)*width/MASK;
                int sx=Math.max(0,Math.min(edge-1,(int)((u0+(px-x0)/(x1-x0)*(u1-u0))*edge)));
                int i=y*MASK+x;
                known[i]=Math.max(known[i],(pixels[sy*edge+sx]>>>24)/255F);
            }
        }
    }

    static void finish(double u,double v,double unit,boolean cave) {
        if(!collecting)return;
        collecting=false;
        int rx=Math.max(1,Math.min(32,(int)Math.round((cave?2.5:7)*MASK/(width*unit))));
        int ry=Math.max(1,Math.min(32,(int)Math.round((cave?2.5:7)*MASK/(height*unit))));
        blur(known,near,rx,ry);
        if(!cave)blur(known,wide,Math.min(48,rx*3),Math.min(48,ry*3));
        else System.arraycopy(near,0,wide,0,near.length);
        if(upload==null)upload=BufferUtils.createByteBuffer(MASK*MASK*4);
        upload.clear();
        for(int i=0;i<known.length;i++){
            upload.put((byte)Math.round(known[i]*255));
            upload.put((byte)Math.round((near[i]*.65F+wide[i]*.35F)*255));
            upload.put((byte)0);upload.put((byte)255);
        }
        upload.flip();
        render(u,v,unit,cave);
    }

    private static void blur(float[] source,float[] target,int rx,int ry){
        for(int y=0;y<MASK;y++){
            int row=y*MASK;float sum=0;
            for(int x=-rx;x<=rx;x++)sum+=source[row+clamp(x)];
            for(int x=0;x<MASK;x++){
                scratch[row+x]=sum/(2*rx+1);
                sum+=source[row+clamp(x+rx+1)]-source[row+clamp(x-rx)];
            }
        }
        for(int x=0;x<MASK;x++){
            float sum=0;for(int y=-ry;y<=ry;y++)sum+=scratch[clamp(y)*MASK+x];
            for(int y=0;y<MASK;y++){
                target[y*MASK+x]=Math.max(0,Math.min(1,sum/(2*ry+1)));
                sum+=scratch[clamp(y+ry+1)*MASK+x]-scratch[clamp(y-ry)*MASK+x];
            }
        }
    }

    private static int clamp(int n){return Math.max(0,Math.min(MASK-1,n));}

    private static void render(double u,double v,double unit,boolean cave){
        if(!GLContext.getCapabilities().OpenGL21 || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)!=0
            || GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)!=0)return;
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int old0=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int old1=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try{
            if(program==0)program=LegacyGpuBaker.shaderProgram(VERTEX,FRAGMENT);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS,0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS,0);
            if(noiseTexture==0){
                noiseTexture=GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,noiseTexture);
                params(GL11.GL_REPEAT);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,MASK,MASK,0,
                    GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,noisePixels());
            }else GL11.glBindTexture(GL11.GL_TEXTURE_2D,noiseTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if(maskTexture==0){
                maskTexture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,maskTexture);
                params(GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,MASK,MASK,0,
                    GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,upload);
            }else{
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,maskTexture);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,MASK,MASK,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,upload);
            }
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"terrain"),0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"noiseTex"),1);
            double longest=Math.max(width,height);
            GL20.glUniform2f(GL20.glGetUniformLocation(program,"aspect"),(float)(width/longest),(float)(height/longest));
            GL20.glUniform2f(GL20.glGetUniformLocation(program,"origin"),(float)(u%512),(float)(v%512));
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"units"),(float)(longest*unit));
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"seconds"),(System.currentTimeMillis()%1200000)/1000F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"cave"),cave?1:0);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"masked"),1);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"useNoise"),1);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0,1);GL11.glVertex2d(left,top+height);
            GL11.glTexCoord2f(1,1);GL11.glVertex2d(left+width,top+height);
            GL11.glTexCoord2f(1,0);GL11.glVertex2d(left+width,top);
            GL11.glTexCoord2f(0,0);GL11.glVertex2d(left,top);
            GL11.glEnd();
        }catch(RuntimeException|LinkageError error){
            failed=true;LOG.warn("Legacy map fog unavailable",error);
        }finally{
            GL20.glUseProgram(0);
            GL11.glPopClientAttrib();
            GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,old1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,old0);
            GL13.glActiveTexture(active);
        }
    }

    private static void params(int wrap){
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,wrap);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,wrap);
    }

    /** Same seeded tileable value-noise construction as upstream FogTextures. */
    private static ByteBuffer noisePixels(){
        float[][] channels=new float[4][];
        for(int c=0;c<4;c++){
            int cells=6<<c;float[] a=noise(cells,c+1),b=noise(cells*2,(c+1)*31L+7);
            for(int i=0;i<a.length;i++)a[i]=a[i]*.7F+b[i]*.3F;
            channels[c]=a;
        }
        ByteBuffer out=BufferUtils.createByteBuffer(MASK*MASK*4);
        for(int i=0;i<MASK*MASK;i++)for(int c=0;c<4;c++)out.put((byte)Math.round(channels[c][i]*255));
        out.flip();return out;
    }
    private static float[] noise(int cells,long seed){
        Random random=new Random(seed);float[] lattice=new float[cells*cells],out=new float[MASK*MASK];
        for(int i=0;i<lattice.length;i++)lattice[i]=random.nextFloat();
        for(int y=0;y<MASK;y++)for(int x=0;x<MASK;x++){
            float fx=(float)x*cells/MASK,fy=(float)y*cells/MASK;int ix=(int)fx,iy=(int)fy;
            float tx=smooth(fx-ix),ty=smooth(fy-iy);
            float a=lattice[iy*cells+ix],b=lattice[iy*cells+(ix+1)%cells];
            float c=lattice[((iy+1)%cells)*cells+ix],d=lattice[((iy+1)%cells)*cells+(ix+1)%cells];
            out[y*MASK+x]=(a+(b-a)*tx)*(1-ty)+(c+(d-c)*tx)*ty;
        }
        return out;
    }
    private static float smooth(float t){return t*t*t*(t*(t*6-15)+10);}
}
