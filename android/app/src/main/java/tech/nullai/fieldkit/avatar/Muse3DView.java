package tech.nullai.fieldkit.avatar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Muse as a real animated 3D character for video calls, rendered with WebGL
 * inside a WebView — no assets, no network, nothing to download.
 *
 * <p>Procedural low-poly bust: displaced sphere head with a tapered jaw, lit
 * with a key + rim light and a chrome specular so it matches the app's pearl
 * palette; emissive eyes that blink, a mouth that opens with Muse's voice, and
 * counter-rotating energy rings that answer whichever side is talking. The
 * whole scene is driven by {@link #setLevels(float, float)}.
 */
public class Muse3DView extends WebView {

    private float lastUser = -1f, lastMuse = -1f;
    private boolean lastSpeaking;

    private static final String HTML =
        "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1,user-scalable=no'>"
      + "<style>html,body{margin:0;padding:0;height:100%;background:transparent;overflow:hidden}"
      + "canvas{display:block;width:100vw;height:100vh}</style></head>"
      + "<body><canvas id='c'></canvas><script>\n"
      + "var S={muse:0,user:0,speak:0,tilt:0};\n"
      + "window.muse=function(m,u,s){S.muse=Math.max(0,Math.min(1,m||0));S.user=Math.max(0,Math.min(1,u||0));S.speak=s?1:0;};\n"
      + "var cv=document.getElementById('c');\n"
      + "var gl=cv.getContext('webgl',{alpha:true,antialias:true,premultipliedAlpha:true})\n"
      + "     ||cv.getContext('experimental-webgl',{alpha:true,antialias:true});\n"
      + "if(!gl){document.body.style.background='radial-gradient(circle at 50% 40%,#1b1f27,#0b0c10)';}\n"
      + "function m4(){return new Float32Array(16);}\n"
      + "function ident(o){o.set([1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]);return o;}\n"
      + "function mul(a,b,o){for(var i=0;i<4;i++){var x=b[i*4],y=b[i*4+1],z=b[i*4+2],w=b[i*4+3];o[i*4]=a[0]*x+a[4]*y+a[8]*z+a[12]*w;o[i*4+1]=a[1]*x+a[5]*y+a[9]*z+a[13]*w;o[i*4+2]=a[2]*x+a[6]*y+a[10]*z+a[14]*w;o[i*4+3]=a[3]*x+a[7]*y+a[11]*z+a[15]*w;}return o;}\n"
      + "function persp(fov,asp,n,f,o){var t=1/Math.tan(fov/2);o.set([t/asp,0,0,0, 0,t,0,0, 0,0,(f+n)/(n-f),-1, 0,0,2*f*n/(n-f),0]);return o;}\n"
      + "function look(e,c,u,o){var zx=e[0]-c[0],zy=e[1]-c[1],zz=e[2]-c[2];var zl=Math.hypot(zx,zy,zz);zx/=zl;zy/=zl;zz/=zl;"
      + "var xx=u[1]*zz-u[2]*zy,xy=u[2]*zx-u[0]*zz,xz=u[0]*zy-u[1]*zx;var xl=Math.hypot(xx,xy,xz)||1;xx/=xl;xy/=xl;xz/=xl;"
      + "var yx=zy*xz-zz*xy,yy=zz*xx-zx*xz,yz=zx*xy-zy*xx;"
      + "o.set([xx,yx,zx,0, xy,yy,zy,0, xz,yz,zz,0, -(xx*e[0]+xy*e[1]+xz*e[2]), -(yx*e[0]+yy*e[1]+yz*e[2]), -(zx*e[0]+zy*e[1]+zz*e[2]),1]);return o;}\n"
      + "function rotY(a,o){o=o||m4();ident(o);var c=Math.cos(a),s=Math.sin(a);o[0]=c;o[2]=-s;o[8]=s;o[10]=c;return o;}\n"
      + "function rotX(a,o){o=o||m4();ident(o);var c=Math.cos(a),s=Math.sin(a);o[5]=c;o[6]=s;o[9]=-s;o[10]=c;return o;}\n"
      + "function trans(x,y,z,o){o=o||m4();ident(o);o[12]=x;o[13]=y;o[14]=z;return o;}\n"
      + "function scale(x,y,z,o){o=o||m4();ident(o);o[0]=x;o[5]=y;o[10]=z;return o;}\n"
      + "var VS='attribute vec3 aP;attribute vec3 aN;uniform mat4 uVP;uniform mat4 uM;varying vec3 vN;'"
      + "+'varying vec3 vP;void main(){vN=normalize(mat3(uM[0].xyz,uM[1].xyz,uM[2].xyz)*aN);vP=(uM*vec4(aP,1.0)).xyz;gl_Position=uVP*uM*vec4(aP,1.0);}';\n"
      + "var FS='precision mediump float;varying vec3 vN;varying vec3 vP;uniform float uGlow;uniform vec3 uRim;'"
      + "+'void main(){vec3 n=normalize(vN);vec3 l=normalize(vec3(-0.45,0.75,0.9));float d=max(dot(n,l),0.0);'"
      + "+'vec3 base=vec3(0.085,0.10,0.13)*(0.22+d*0.95);'"
      + "+'vec3 sheen=vec3(0.68,0.76,0.88)*pow(max(dot(n,normalize(vec3(0.55,0.35,0.75))),0.0),3.5)*0.45;'"
      + "+'float rim=pow(1.0-abs(dot(n,vec3(0.0,0.0,1.0))),2.6);'"
      + "+'vec3 h=normalize(l+vec3(0.0,0.0,1.0));float sp=pow(max(dot(n,h),0.0),56.0);'"
      + "+'vec3 c=base+sheen+uRim*rim*(0.30+uGlow*0.9)+vec3(1.0)*sp*0.55;gl_FragColor=vec4(c,1.0);}';\n"
      + "function sh(t,s){var o=gl.createShader(t);gl.shaderSource(o,s);gl.compileShader(o);return o;}\n"
      + "function prog(v,f){var o=gl.createProgram();gl.attachShader(o,sh(gl.VERTEX_SHADER,v));gl.attachShader(o,sh(gl.FRAGMENT_SHADER,f));gl.linkProgram(o);gl.useProgram(o);return o;}\n"
      + "var LIT=prog(VS,FS);\n"
      + "var EM='precision mediump float;varying vec3 vN;uniform float uGlow;uniform vec3 uColor;uniform float uAlpha;'"
      + "+'void main(){gl_FragColor=vec4(uColor*(0.75+uGlow*1.1),uAlpha);}';\n"
      + "var EMI=prog(VS,EM);\n"
      + "function geo(l,r,sx,sy,sz,taper){var P=[],N=[],I=[];for(var y=0;y<=r;y++){var phi=y/r*Math.PI;for(var x=0;x<=l;x++){var th=x/l*Math.PI*2;"
      + "var nx=Math.sin(phi)*Math.cos(th),ny=Math.cos(phi),nz=Math.sin(phi)*Math.sin(th);"
      + "var px=nx*sx,py=ny*sy,pz=nz*sz;if(py<0){var k=1.0+py*taper;px*=k;pz*=k;}P.push(px,py,pz);N.push(nx,ny,nz);}}"
      + "for(var y2=0;y2<r;y2++)for(var x2=0;x2<l;x2++){var a=y2*(l+1)+x2,b=a+l+1;I.push(a,b,a+1,b,b+1,a+1);}"
      + "var o={p:new Float32Array(P),n:new Float32Array(N),i:new Uint16Array(I)};"
      + "o.bp=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,o.bp);gl.bufferData(gl.ARRAY_BUFFER,o.p,gl.STATIC_DRAW);"
      + "o.bn=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,o.bn);gl.bufferData(gl.ARRAY_BUFFER,o.n,gl.STATIC_DRAW);"
      + "o.bi=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,o.bi);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,o.i,gl.STATIC_DRAW);"
      + "o.n_i=o.i.length;return o;}\n"
      + "var HEAD=geo(56,42,0.92,1.06,0.98,0.62);\n"
      + "var BUST=geo(40,26,1.55,0.62,0.85,0.0);\n"
      + "var EYE=geo(18,12,0.20,0.145,0.11,0.0);\n"
      + "var MOUTH=geo(22,12,0.17,0.075,0.075,0.0);\n"
      + "var RING=(function(){var v=[],i=[];for(var k=0;k<=140;k++){var a=k/140*Math.PI*2;v.push(Math.cos(a),0,Math.sin(a));if(k<140)i.push(k,k+1);}"
      + "var o={p:new Float32Array(v),n:new Float32Array(v),i:new Uint16Array(i)};"
      + "o.bp=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,o.bp);gl.bufferData(gl.ARRAY_BUFFER,o.p,gl.STATIC_DRAW);"
      + "o.bn=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,o.bn);gl.bufferData(gl.ARRAY_BUFFER,o.n,gl.STATIC_DRAW);"
      + "o.bi=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,o.bi);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,o.i,gl.STATIC_DRAW);"
      + "return o;})();\n"
      + "gl.enable(gl.DEPTH_TEST);gl.clearColor(0,0,0,0);\n"
      + "function bind(o,P){var ap=gl.getAttribLocation(P,'aP'),an=gl.getAttribLocation(P,'aN');"
      + "gl.bindBuffer(gl.ARRAY_BUFFER,o.bp);gl.enableVertexAttribArray(ap);gl.vertexAttribPointer(ap,3,gl.FLOAT,false,0,0);"
      + "gl.bindBuffer(gl.ARRAY_BUFFER,o.bn);gl.enableVertexAttribArray(an);gl.vertexAttribPointer(an,3,gl.FLOAT,false,0,0);"
      + "gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,o.bi);}\n"
      + "function draw(o,P,VP,M,glow,rim,color,alpha,lines){gl.useProgram(P);gl.uniformMatrix4fv(gl.getUniformLocation(P,'uVP'),false,VP);"
      + "gl.uniformMatrix4fv(gl.getUniformLocation(P,'uM'),false,M);if(glow!==null)gl.uniform1f(gl.getUniformLocation(P,'uGlow'),glow);"
      + "if(rim)gl.uniform3fv(gl.getUniformLocation(P,'uRim'),rim);if(color)gl.uniform3fv(gl.getUniformLocation(P,'uColor'),color);"
      + "if(alpha!==null)gl.uniform1f(gl.getUniformLocation(P,'uAlpha'),alpha);bind(o,P);"
      + "if(lines)gl.drawElements(gl.LINES,o.n_i,gl.UNSIGNED_SHORT,0);else gl.drawElements(gl.TRIANGLES,o.n_i,gl.UNSIGNED_SHORT,0);}\n"
      + "var t0=performance.now(),blinkAt=2200,blink=0,VP=m4(),V=m4(),Pm=m4(),tmp=m4(),tmp2=m4();\n"
      + "function frame(now){var t=(now-t0)/1000;"
      + "var W=cv.clientWidth||300,H=cv.clientHeight||300;if(cv.width!==W||cv.height!==H){cv.width=W;cv.height=H;gl.viewport(0,0,W,H);}"
      + "gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);\n"
      + "if(now>blinkAt){blinkAt=now+2300+Math.random()*2400;}var dt=(blinkAt-now);blink=dt>150?0:(dt>75?(150-dt)/75:(dt/75));\n"
      + "var yaw=Math.sin(t*0.34)*0.30+S.speak*Math.sin(t*1.6)*0.05;var nod=Math.sin(t*0.7)*0.03+S.user*0.06;"
      + "look([0,0.14,3.25],[0,0.02,0],[0,1,0],V);persp(0.56,(cv.width/Math.max(1,cv.height)),0.1,20,Pm);mul(Pm,V,VP);\n"
      + "var glow=Math.max(S.muse,S.user*0.7), museC=[0.62,0.70,0.82], eyeC=[0.45,0.88,1.0], inC=[0.10,0.13,0.18];\n"
      + "[[-0.30,0.16,0.80,0.9],[0.30,0.16,0.80,0.9]].forEach(function(e){mul(trans(e[0],e[1],e[2]),rotY(yaw),tmp);"
      + "mul(tmp,scale(1,Math.max(0.10,1-blink*0.94),1),tmp2);draw(EYE,EMI,VP,tmp2,glow,null,eyeC,1,null);});\n"
      + "mul(rotY(yaw),rotX(nod),tmp);var headM=mul(trans(0,0.03,0),tmp,tmp2);"
      + "draw(HEAD,LIT,VP,headM,glow,museC,null,null,false);\n"
      + "draw(BUST,LIT,VP,mul(trans(0,-1.42,-0.12),scale(1,1,1),m4()),glow*0.6,[0.30,0.38,0.50],null,null,false);\n"
      + "var open=1.0+S.muse*5.5+S.speak*1.2;"
      + "draw(MOUTH,EMI,VP,mul(mul(rotY(yaw),rotX(nod),tmp),mul(trans(0.0,-0.34,0.86),scale(1,open,1),tmp2),tmp),0.25+S.muse*1.4,null,museC,0.95,null);\n"
      + "gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE);"
      + "for(var k=0;k<2;k++){var side=k===0?S.user:S.muse;var col=k===0?[0.35,0.85,1.0]:[0.80,0.88,1.0];"
      + "var rr=2.05-k*0.34;var rm=mul(rotX(1.35+k*0.12,tmp),rotY(t*(k===0?-0.5:0.42),tmp2),tmp);"
      + "mul(scale(rr,rr,rr),rm,tmp2);draw(RING,LIT,VP,tmp2,null,col,null,null,true);}\n"
      + "gl.disable(gl.BLEND);requestAnimationFrame(frame);}\n"
      + "requestAnimationFrame(frame);\n"
      + "</script></body></html>";

    public Muse3DView(Context c) { super(c); init(); }
    public Muse3DView(Context c, AttributeSet a) { super(c, a); init(); }

    @SuppressLint("SetJavaScriptEnabled")
    private void init() {
        setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setDomStorageEnabled(false);
        s.setBlockNetworkLoads(true);   // procedural only: nothing loads over the network
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        loadDataWithBaseURL(null, HTML, "text/html", "utf-8", null);
    }

    /** Pushes the live audio levels into the 3D scene. */
    public void setLevels(float user, float muse) {
        if (Math.abs(user - lastUser) < 0.02f && Math.abs(muse - lastMuse) < 0.02f) return;
        lastUser = user; lastMuse = muse;
        push(muse, user, lastSpeaking);
    }

    public void setSpeaking(boolean speaking) {
        if (speaking == lastSpeaking) return;
        lastSpeaking = speaking;
        push(lastMuse, lastUser, speaking);
    }

    private void push(float muse, float user, boolean speaking) {
        evaluateJavascript(String.format(java.util.Locale.US,
                "window.muse(%f,%f,%d)", muse, user, speaking ? 1 : 0), null);
    }
}
