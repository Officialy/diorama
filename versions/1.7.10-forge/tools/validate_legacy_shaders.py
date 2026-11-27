#!/usr/bin/env python3
"""Compile and execute the exact embedded legacy GLSL on a headless Mesa compatibility context."""
import ctypes as C
import json
import os
from pathlib import Path
import re

os.environ.setdefault("EGL_PLATFORM", "surfaceless")
os.environ.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
EGL = C.CDLL("libEGL.so.1")
GL = C.CDLL("libGL.so.1")
I, U, P, F = C.c_int, C.c_uint, C.c_void_p, C.c_float

def fn(lib, name, result, *args):
    f = getattr(lib, name)
    f.restype = result
    f.argtypes = args
    return f

get_display = fn(EGL,"eglGetDisplay",P,P)
initialize = fn(EGL,"eglInitialize",U,P,C.POINTER(I),C.POINTER(I))
bind_api = fn(EGL,"eglBindAPI",U,U)
choose_config = fn(EGL,"eglChooseConfig",U,P,C.POINTER(I),C.POINTER(P),I,C.POINTER(I))
make_surface = fn(EGL,"eglCreatePbufferSurface",P,P,P,C.POINTER(I))
make_context = fn(EGL,"eglCreateContext",P,P,P,P,C.POINTER(I))
make_current = fn(EGL,"eglMakeCurrent",U,P,P,P,P)
destroy_context = fn(EGL,"eglDestroyContext",U,P,P)
destroy_surface = fn(EGL,"eglDestroySurface",U,P,P)
terminate = fn(EGL,"eglTerminate",U,P)
display = get_display(None)
major, minor, n = I(), I(), I()
assert display and initialize(display,C.byref(major),C.byref(minor)), "EGL initialization failed"
assert bind_api(0x30A2), "Desktop OpenGL unavailable"
config = P()
attributes = (I*13)(0x3033,1,0x3040,8,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3038)
assert choose_config(display,attributes,C.byref(config),1,C.byref(n)) and n.value
surface = make_surface(display,config,(I*5)(0x3057,512,0x3056,512,0x3038))
context = make_context(display,config,None,(I*7)(0x3098,3,0x30FB,3,0x30FD,2,0x3038))
assert surface and context and make_current(display,surface,surface,context), "Compatibility context unavailable"
gl = lambda name, result, *args: fn(GL,"gl"+name,result,*args)
print("OpenGL:",gl("GetString",C.c_char_p,U)(0x1F02).decode(),
      gl("GetString",C.c_char_p,U)(0x1F01).decode())
create_shader=gl("CreateShader",U,U)
shader_source=gl("ShaderSource",None,U,I,C.POINTER(C.c_char_p),P)
compile_shader=gl("CompileShader",None,U)
shader_iv=gl("GetShaderiv",None,U,U,C.POINTER(I))
shader_log=gl("GetShaderInfoLog",None,U,I,P,P)
create_program=gl("CreateProgram",U)
attach=gl("AttachShader",None,U,U)
link=gl("LinkProgram",None,U)
program_iv=gl("GetProgramiv",None,U,U,C.POINTER(I))
program_log=gl("GetProgramInfoLog",None,U,I,P,P)
use=gl("UseProgram",None,U)
delete_program=gl("DeleteProgram",None,U)
delete_shader=gl("DeleteShader",None,U)
uniform_location=gl("GetUniformLocation",I,U,C.c_char_p)
uniform_i=gl("Uniform1i",None,I,I)
uniform_f=gl("Uniform1f",None,I,F)
uniform_2f=gl("Uniform2f",None,I,F,F)
viewport=gl("Viewport",None,I,I,I,I)
begin=gl("Begin",None,U)
end=gl("End",None)
vertex=gl("Vertex2f",None,F,F)
texcoord=gl("TexCoord2f",None,F,F)
read_pixels=gl("ReadPixels",None,I,I,I,I,U,U,P)
gen_textures=gl("GenTextures",None,I,C.POINTER(U))
delete_textures=gl("DeleteTextures",None,I,C.POINTER(U))
bind_texture=gl("BindTexture",None,U,U)
active_texture=gl("ActiveTexture",None,U)
parameter=gl("TexParameteri",None,U,U,I)
image_2d=gl("TexImage2D",None,U,I,I,I,I,I,U,U,P)
image_3d=gl("TexImage3D",None,U,I,I,I,I,I,I,U,U,P)
disable=gl("Disable",None,U)
get_error=gl("GetError",U)
source_root=Path(__file__).resolve().parents[1]/"src/main/java/dev/diorama/gtnh/client"

def embedded(path, key):
    text=path.read_text(encoding="utf-8")
    literal=r'"(?:\\.|[^"\\])*"'
    match=re.search(r"\bString\s+"+key+r"\s*=\s*((?:"+literal+r"\s*(?:\+\s*)?)+);",text)
    assert match, f"Cannot extract {path.name}:{key}"
    return "".join(json.loads(s) for s in re.findall(literal,match.group(1)))

def program(name):
    path=source_root/(name+".java")
    shaders=[]
    for key,kind in (("VERTEX",0x8B31),("FRAGMENT",0x8B30)):
        shader=create_shader(kind)
        src=C.c_char_p(embedded(path,key).encode())
        shader_source(shader,1,C.byref(src),None)
        compile_shader(shader)
        ok=I()
        shader_iv(shader,0x8B81,C.byref(ok))
        if not ok.value:
            buf=C.create_string_buffer(8192)
            shader_log(shader,len(buf),None,buf)
            raise AssertionError(f"{name} {key}: {buf.value.decode()}")
        shaders.append(shader)
    p=create_program()
    for shader in shaders: attach(p,shader)
    link(p)
    ok=I()
    program_iv(p,0x8B82,C.byref(ok))
    if not ok.value:
        buf=C.create_string_buffer(8192)
        program_log(p,len(buf),None,buf)
        raise AssertionError(f"{name} link: {buf.value.decode()}")
    for shader in shaders: delete_shader(shader)
    print(name,"compiled and linked")
    return p

textures=[]
def texture(unit,target,width,height,data,internal=0x8814,format=0x1908,kind=0x1406,depth=0):
    t=U()
    gen_textures(1,C.byref(t))
    textures.append(t)
    active_texture(0x84C0+unit)
    bind_texture(target,t.value)
    for key,value in [(0x2801,0x2600),(0x2800,0x2600),(0x2802,0x812F),(0x2803,0x812F)]:
        parameter(target,key,value)
    if depth:
        parameter(target,0x8072,0x812F)
        image_3d(target,0,internal,width,height,depth,0,format,kind,data)
    else:
        image_2d(target,0,internal,width,height,0,format,kind,data)
    assert get_error()==0,"Texture upload error"

def uniforms(p,values):
    use(p)
    for key,value in values.items():
        loc=uniform_location(p,key.encode())
        if isinstance(value,tuple): uniform_2f(loc,*value)
        elif isinstance(value,int): uniform_i(loc,value)
        else: uniform_f(loc,value)

def quad(size):
    viewport(0,0,size,size)
    begin(7)
    for u,v in [(0,0),(1,0),(1,1),(0,1)]:
        texcoord(u,v)
        vertex(2*u-1,2*v-1)
    end()
    assert get_error()==0,"Draw error"

def pixel(x,y):
    out=(C.c_ubyte*4)()
    read_pixels(x,y,1,1,0x1908,0x1401,out)
    assert get_error()==0,"Readback error"
    return tuple(out)

try:
    for cap in (0x0BE2,0x0B71,0x0BC0,0x0C11,0x0B44): disable(cap)
    pitch=(0.7660444431,0.6427876097)
    p=program("LegacyGpuBaker")
    columns=(F*(128*400*4))()
    for z in range(400):
        for x in range(128):
            i=(z*128+x)*4
            columns[i]=1
            columns[i+3]=16 if x<64 else -2
    texture(0,0x0DE1,128,400,columns)
    uniforms(p,{"columns":0,"pitch":pitch,"offset":0.0})
    quad(128)
    known,unknown=pixel(20,20),pixel(100,20)
    assert known[0]>100 and known[3]==255 and unknown[3]==0,(known,unknown)
    print("Heightfield GPU fixture:",known,unknown)
    use(0);delete_program(p)

    p=program("LegacyGpuVolume")
    ids=(C.c_ushort*(128*400))()
    for z in range(400):
        for x in range(128): ids[z*128+x]=1 if x<64 else 65535
    texture(0,0x806F,128,400,ids,0x8234,0x8D94,0x1403,1)
    palette=(F*(256*768*4))()
    stride=32768*4
    palette[4]=1
    palette[stride+7]=1
    palette[stride*2+4]=1
    texture(1,0x0DE1,256,768,palette)
    tints=(F*(512*400*4))()
    shapes=(F*256)()
    texture(2,0x0DE1,512,400,tints)
    texture(3,0x0DE1,32,2,shapes)
    uniforms(p,{"voxels":0,"palette":1,"tints":2,"shapes":3,"top":1.0,"offset":0.0,"pitch":pitch,"samples":1})
    quad(512)
    known,unknown=pixel(32,24),pixel(400,24)
    assert known[0]>100 and known[3]==255 and unknown[3]==0,(known,unknown)
    uniforms(p,{"samples":4})
    quad(512)
    assert pixel(32,24)[3]==255 and pixel(400,24)[3]==0
    print("Voxel GPU fixture:",known,unknown,"and four-sample coverage")
    use(0);delete_program(p)

    p=program("LegacyFogShader")
    texture(0,0x0DE1,1,1,(F*4)(1,1,1,1))
    uniforms(p,{"terrain":0,"origin":(0.0,0.0),"units":128.0,"seconds":0.0,"cave":0.0,"masked":0.0})
    quad(64)
    assert pixel(20,20)[3]==255
    uniforms(p,{"masked":1.0})
    quad(64)
    assert pixel(20,20)[3]==0
    print("Fog shader fixture: opaque unexplored cloud and clear fully known mask")
    use(0);delete_program(p)
finally:
    for t in textures: delete_textures(1,C.byref(t))
    make_current(display,None,None,None)
    destroy_context(display,context)
    destroy_surface(display,surface)
    terminate(display)
