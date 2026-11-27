package dev.diorama.gtnh.client;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;
import org.jspecify.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.diorama.Projection;
import dev.diorama.gtnh.capture.TerrainPreview;

/** Optional full-resolution voxel raymarch. Approximate lighting; never authoritative disk data. */
final class LegacyGpuVolume {
    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static final boolean ENABLED = Boolean.getBoolean("diorama.gpuRefine");
    private static final boolean FOUR = Boolean.getBoolean("diorama.gpuFourSamples");
    private static final AtomicBoolean PREPARING = new AtomicBoolean();
    private static volatile boolean failed;
    private static int program;
    private static ShortBuffer voxelStaging;
    private static FloatBuffer floatStaging;
    private static final String VERTEX = "#version 130\nvoid main(){gl_Position=gl_Vertex;}";
    private static final String FRAGMENT = "#version 130\n"
        + "uniform usampler3D voxels; uniform sampler2D palette; uniform sampler2D tints; uniform sampler2D shapes;"
        + "uniform float top; uniform float offset; uniform vec2 pitch; uniform int samples; out vec4 outColor;"
        + "vec4 pal(int id,int plane){return texelFetch(palette,ivec2(id%256,id/256+plane*128),0);}"
        + "vec4 ray(vec2 screen){float x=screen.x*0.25;float v=screen.y*0.25;"
        + "float y=top-0.0002;float k=pitch.y/pitch.x;float z=offset+(v+y*pitch.y)/pitch.x;"
        + "float trans=1.0;vec3 sum=vec3(0);bool topFace=true;"
        + "for(int step=0;step<800;step++){ivec3 c=ivec3(floor(x),floor(z),floor(y));"
        + "if(c.x<0||c.x>=128||c.y<0||c.y>=400||c.z<0||c.z>=int(top))break;"
        + "int id=int(texelFetch(voxels,c,0).r);if(id==65535)return vec4(0);"
        + "vec3 local=vec3(fract(x),fract(y),fract(z));"
        + "float ty=max(local.y,0.0001);float tz=max(local.z/k,0.0001);float exit=min(ty,tz);"
        + "if(id>0&&id<32768){vec4 material=pal(id,1);int kind=int(material.a+0.5);"
        + "vec4 extra=pal(id,5);int shape=int(extra.r+0.5);"
        + "bool hit=shape==0;bool face=topFace;float best=1e9;"
        + "if(shape>0){int count=int(texelFetch(shapes,ivec2(0,shape),0).a+0.5);"
        + "for(int i=0;i<16;i++){if(i>=count)break;"
        + "vec3 lo=texelFetch(shapes,ivec2(i*2,shape),0).rgb;"
        + "vec3 hi=texelFetch(shapes,ivec2(i*2+1,shape),0).rgb;"
        + "if(local.x<lo.x||local.x>hi.x)continue;"
        + "float ny=local.y-hi.y,nz=(local.z-hi.z)/k;"
        + "float enter=max(0.0,max(ny,nz));float leave=min(exit,min(local.y-lo.y,(local.z-lo.z)/k));"
        + "if(enter<=leave&&enter<best){hit=true;best=enter;face=ny>=nz;}}}"
        + "if(hit&&kind>0){vec4 plain=pal(id,face?0:2);vec3 tintColor=pal(id,4).rgb;"
        + "int tintId=int(pal(id,3).a+0.5);"
        + "if(pal(id,4).a<0.5&&tintId>0&&tintId<=4)"
        + "tintColor=texelFetch(tints,ivec2(c.x*4+tintId-1,c.y),0).rgb;"
        + "vec3 tintBase=pal(id,face?1:3).rgb;"
        + "vec3 col=mix(plain.rgb,tintBase*tintColor,plain.a);"
        + "float light=max(face?0.95:0.68,extra.g/15.0);"
        + "col=clamp(col*light,0.0,1.0);"
        + "float opacity=kind==2?0.42:kind==4?0.22:kind==5?0.50:1.0;"
        + "sum+=col*opacity*trans;trans*=1.0-opacity;"
        + "if(trans<0.015)return vec4(sum/max(1.0-trans,0.001),1.0-trans);}}"
        + "topFace=ty<=tz;y-=exit+0.0001;z-=(exit+0.0001)*k;}"
        + "float a=1.0-trans;return vec4(sum/max(a,0.001),a);}"
        + "void main(){vec2 p=gl_FragCoord.xy;if(samples==1){outColor=ray(p);return;}"
        + "vec4 a=ray(p+vec2(-0.25,-0.25)),b=ray(p+vec2(0.25,-0.25));"
        + "vec4 c=ray(p+vec2(-0.25,0.25)),d=ray(p+vec2(0.25,0.25));"
        + "float coverage=a.a+b.a+c.a+d.a;outColor=vec4((a.rgb*a.a+b.rgb*b.a+c.rgb*c.a+d.rgb*d.a)"
        + "/max(coverage,0.001),coverage*0.25);}";

    private LegacyGpuVolume() {}

    static int @Nullable [] refine(TerrainPreview.Work work) {
        if (!ENABLED || failed || work.key().isCave() || !PREPARING.compareAndSet(false,true)) return null;
        try {
            short[] voxels = work.gpuVoxels();
            float[] palette = work.gpuPalette(), tints = work.gpuTints(), shapes = work.gpuShapes();
            int top = work.topY();
            double origin = work.key().minV()/Projection.sin();
            float offset = (float)(origin-Math.floor(origin));
            return LegacyGpuBaker.submit(new FutureTask<>(() -> bake(voxels,palette,tints,shapes,top,offset)));
        } catch (RuntimeException | LinkageError unsupported) {
            return null;
        } finally { PREPARING.set(false); }
    }

    private static int @Nullable [] bake(short[] voxels,float[] palette,float[] tints,float[] shapes,int top,float offset) {
        if (!GLContext.getCapabilities().OpenGL30 || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)!=0
            || GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)!=0
            || GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)!=0
            || GL11.glGetInteger(GL12.GL_MAX_3D_TEXTURE_SIZE)<400
            || GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)<Math.max(768,shapes.length/128)) return null;
        int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] old2d=new int[4], old3d=new int[4], textures=new int[5];
        for(int i=0;i<4;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);old2d[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            old3d[i]=GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);}
        GL13.glActiveTexture(active);
        int fbo=0;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try {
            if(program==0)program=LegacyGpuBaker.shaderProgram(VERTEX,FRAGMENT);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,2);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS,0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS,0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES,0);
            GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT,0);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES,0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            textures[0]=GL11.glGenTextures();
            GL11.glBindTexture(GL12.GL_TEXTURE_3D,textures[0]);
            params(GL12.GL_TEXTURE_3D);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL12.GL_TEXTURE_WRAP_R,GL12.GL_CLAMP_TO_EDGE);
            if(voxelStaging==null||voxelStaging.capacity()<voxels.length)voxelStaging=BufferUtils.createShortBuffer(voxels.length);
            voxelStaging.clear();voxelStaging.put(voxels).flip();
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D,0,GL30.GL_R16UI,128,400,top,0,
                GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_SHORT,voxelStaging);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,4);
            floatTexture(textures,1,palette,256,768);
            floatTexture(textures,2,tints,512,400);
            floatTexture(textures,3,shapes,32,shapes.length/128);
            int edge=Projection.TILE_PIXELS;
            textures[4]=GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[4]);
            params(GL11.GL_TEXTURE_2D);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,edge,edge,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,textures[4],0);
            if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("GPU voxel framebuffer incomplete");
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glViewport(0,0,edge,edge);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColorMask(true,true,true,true);GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            for(int i=1;i<4;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]);}
            GL20.glUseProgram(program);
            for(int i=0;i<4;i++)GL20.glUniform1i(GL20.glGetUniformLocation(program,new String[]{"voxels","palette","tints","shapes"}[i]),i);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"top"),top);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"offset"),offset);
            GL20.glUniform2f(GL20.glGetUniformLocation(program,"pitch"),(float)Projection.sin(),(float)Projection.cos());
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"samples"),FOUR?4:1);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(-1,-1);GL11.glVertex2f(1,-1);GL11.glVertex2f(1,1);GL11.glVertex2f(-1,1);GL11.glEnd();
            ByteBuffer data=BufferUtils.createByteBuffer(edge*edge*4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,4);GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH,0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS,0);GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS,0);
            GL11.glReadPixels(0,0,edge,edge,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data);
            int[] pixels=new int[edge*edge];
            for(int i=0;i<pixels.length;i++)pixels[i]=(data.get(i*4)&255)|((data.get(i*4+1)&255)<<8)
                |((data.get(i*4+2)&255)<<16)|((data.get(i*4+3)&255)<<24);
            return pixels;
        } catch(RuntimeException|LinkageError failure){
            failed=true;LOG.warn("GPU voxel refinement disabled; falling back to CPU",failure);return null;
        } finally {
            GL20.glUseProgram(0);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);
            if(fbo!=0)GL30.glDeleteFramebuffers(fbo);
            for(int texture:textures)if(texture!=0)GL11.glDeleteTextures(texture);
            GL11.glPopClientAttrib();GL11.glPopAttrib();
            for(int i=0;i<4;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,old2d[i]);GL11.glBindTexture(GL12.GL_TEXTURE_3D,old3d[i]);}
            GL13.glActiveTexture(active);
        }
    }

    private static void floatTexture(int[] textures,int unit,float[] data,int width,int height){
        GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);
        textures[unit]=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[unit]);params(GL11.GL_TEXTURE_2D);
        if(floatStaging==null||floatStaging.capacity()<data.length)floatStaging=BufferUtils.createFloatBuffer(data.length);
        floatStaging.clear();floatStaging.put(data).flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA32F,width,height,0,GL11.GL_RGBA,GL11.GL_FLOAT,floatStaging);
    }

    private static void params(int target){
        GL11.glTexParameteri(target,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(target,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(target,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(target,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
    }
}
