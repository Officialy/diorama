package dev.diorama.gtnh.client;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Optional GLSL 1.20 cloud height-field approximation. A background pass supplies dense cloud
 * where no map exists; a second pass uses the tile alpha as a coverage mask for soft fog banks.
 * No FBO, stencil, extra texture allocation or shader pipeline mutation beyond the program bind.
 */
final class LegacyFogShader {
    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static final boolean ENABLED = Boolean.getBoolean("diorama.volumetricFog");
    private static int program;
    private static boolean failed;
    private static final String VERTEX = "#version 120\n"
        + "varying vec2 uv; void main(){uv=gl_MultiTexCoord0.xy; gl_Position=gl_ModelViewProjectionMatrix*gl_Vertex;}";
    private static final String FRAGMENT = "#version 120\n"
        + "varying vec2 uv; uniform sampler2D terrain; uniform vec2 origin; uniform float units;"
        + "uniform float seconds; uniform float cave; uniform float masked;"
        + "float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}"
        + "float noise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);"
        + "return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),f.x),f.y);}"
        + "float density(vec2 p){return 0.60*noise(p)+0.28*noise(p*2.1)+0.12*noise(p*4.4);}"
        + "float known(vec2 p){if(masked<0.5)return 0.0;"
        + "return texture2D(terrain,clamp(p,vec2(0.001),vec2(0.999))).a;}"
        + "void main(){vec2 p=origin+uv*units;float trans=1.0;vec3 sum=vec3(0.0);"
        + "float bank=mix(6.0,1.8,cave);float center=known(uv);"
        + "for(int i=0;i<8;i++){float h=float(7-i)/7.0;vec2 lift=vec2(0.0,h*bank*0.643);"
        + "vec2 m=uv+lift/units;float n=density((p+lift+vec2(seconds*0.15,-seconds*0.08))/18.0);"
        + "float unknown=1.0-(known(m)*0.5+known(m+vec2(0.003,0.0))*0.125"
        + "+known(m-vec2(0.003,0.0))*0.125+known(m+vec2(0.0,0.003))*0.125+known(m-vec2(0.0,0.003))*0.125);"
        + "float a=smoothstep(h*0.7, h*0.7+0.25,n)*unknown*0.42;"
        + "vec3 col=mix(vec3(0.46,0.52,0.57),vec3(0.90,0.92,0.94),n);"
        + "col=mix(col,col*0.35,cave);sum+=col*a*trans;trans*=1.0-a;}"
        + "float alpha=1.0-trans;"
        + "if(masked<0.5){vec3 base=mix(vec3(0.48,0.53,0.58),vec3(0.08,0.10,0.13),cave);"
        + "gl_FragColor=vec4(sum+base*trans,1.0);}"
        + "else {float edge=alpha*smoothstep(0.0,0.8,center);"
        + "gl_FragColor=vec4(sum/max(alpha,0.001),edge*0.75);}}";
    private LegacyFogShader() {}

    static boolean background(double x, double y, double w, double h, double u, double v, double unit, boolean cave) {
        // UV is a square in projected units; the rectangle may be non-square.
        double units = Math.max(w,h)*unit;
        return draw(x,y,x+w,y+h,0,0,w*unit/units,h*unit/units,u,v,units,cave,false);
    }

    static void edge(double x0,double y0,double x1,double y1,double u0,double v0,double u1,double v1,
        double originU,double originV,double units,boolean cave) {
        draw(x0,y0,x1,y1,u0,v0,u1,v1,originU,originV,units,cave,true);
    }

    private static boolean draw(double x0,double y0,double x1,double y1,double u0,double v0,double u1,double v1,
        double originU,double originV,double units,boolean cave,boolean masked) {
        if (!ENABLED || failed || !ClientMinimap.options().fog || units <= 0) return false;
        try {
            if (!GLContext.getCapabilities().OpenGL20 || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0)
                return false;
            if (program == 0) program = LegacyGpuBaker.shaderProgram(VERTEX, FRAGMENT);
            GL20.glUseProgram(program);
            try {
                GL20.glUniform1i(GL20.glGetUniformLocation(program,"terrain"),0);
                GL20.glUniform2f(GL20.glGetUniformLocation(program,"origin"),(float) (originU % 16384),(float) (originV % 16384));
                GL20.glUniform1f(GL20.glGetUniformLocation(program,"units"),(float) units);
                GL20.glUniform1f(GL20.glGetUniformLocation(program,"seconds"),(System.currentTimeMillis()%1200000)/1000F);
                GL20.glUniform1f(GL20.glGetUniformLocation(program,"cave"),cave?1:0);
                GL20.glUniform1f(GL20.glGetUniformLocation(program,"masked"),masked?1:0);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2d(u0,v1); GL11.glVertex2d(x0,y1);
                GL11.glTexCoord2d(u1,v1); GL11.glVertex2d(x1,y1);
                GL11.glTexCoord2d(u1,v0); GL11.glVertex2d(x1,y0);
                GL11.glTexCoord2d(u0,v0); GL11.glVertex2d(x0,y0);
                GL11.glEnd();
            } finally { GL20.glUseProgram(0); }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            LOG.warn("Experimental cloud shader disabled; using legacy fog",failure);
            return false;
        }
    }
}
