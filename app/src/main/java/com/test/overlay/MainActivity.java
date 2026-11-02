package com.test.overlay;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class MainActivity extends Activity {
    private GLSurfaceView glView;
    private static final int SHIZUKU_REQUEST_CODE = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 1000);
        } else {
            setupOverlay();
        }
    }

    private void setupOverlay() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // 核心绝杀：把系统级的画布尺寸设为 1x1 像素，绝对不挡触摸！
        // 但是在下面的 FieldRenderer 里，我们会强行拉升 Viewport 到全屏。
        int layoutFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE 
                       | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE 
                       | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                       | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1, 
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                layoutFlag,
                PixelFormat.TRANSLUCENT);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(3);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        glView.setRenderer(new FieldRenderer());
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        wm.addView(glView, params);
        finish();
    }
}

class FieldRenderer implements GLSurfaceView.Renderer {
    private int program;
    private FloatBuffer vertexBuffer;
    private long startTime;

    private final String vertexShaderCode = 
        "attribute vec4 vPosition; " +
        "void main() { " +
        "  gl_Position = vPosition; " +
        "}";

    private final String fragmentShaderCode =
        "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 fragColor;\n" +
        "uniform float u_time;\n" +
        "uniform vec2 u_resolution;\n" +
        "void main() {\n" +
        "  vec2 uv = gl_FragCoord.xy / u_resolution;\n" +
        "  vec2 center = vec2(0.5, 0.5);\n" +
        "  vec2 delta = uv - center;\n" +
        "  float dist = length(delta);\n" +
        "  \n" +
        "  float density = 0.0;\n" +
        "  int numSteps = 32;\n" +
        "  float stepSize = 1.0 / float(numSteps);\n" +
        "  \n" +
        "  for(int i = 0; i < numSteps; i++) {\n" +
        "    float t = float(i) * stepSize;\n" +
        "    vec2 p = uv - delta * t * 2.0;\n" +
        "    \n" +
        "    float bosonic = sin(p.x * 20.0 + u_time) * cos(p.y * 20.0 + u_time);\n" +
        "    float fermionic = sin(p.x * 20.0 + u_time + 1.57) * cos(p.y * 20.0 + u_time + 1.57);\n" +
        "    float interference = abs((bosonic + fermionic) * (bosonic - fermionic));\n" +
        "    \n" +
        "    float mieScatter = 1.0 / (1.0 + dist * dist * 50.0);\n" +
        "    density += exp(-interference * 2.0) * mieScatter * stepSize;\n" +
        "  }\n" +
        "  \n" +
        "  vec3 color1 = vec3(0.8, 0.2, 1.0);\n" +
        "  vec3 color2 = vec3(0.2, 0.8, 1.0);\n" +
        "  vec3 finalColor = mix(color1, color2, sin(u_time * 0.5) * 0.5 + 0.5) * density * 5.0;\n" +
        "  \n" +
        "  fragColor = vec4(finalColor, clamp(density * 2.0, 0.0, 1.0));\n" +
        "}";

    public FieldRenderer() {
        float[] quad = { -1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f };
        vertexBuffer = ByteBuffer.allocateDirect(quad.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(quad).position(0);
        startTime = System.currentTimeMillis();
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, shaderCode);
        GLES30.glCompileShader(shader);
        return shader;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        int vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode);
        program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vertexShader);
        GLES30.glAttachShader(program, fragmentShader);
        GLES30.glLinkProgram(program);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
    }

    @Override 
    public void onSurfaceChanged(GL10 gl, int width, int height) { 
        // 核心诡计：虽然 Window 只有 1x1，这里强行把 GLES 视口拉到屏幕实际大小！
        // 借助 GLES 的底层通道，直接覆盖整个物理屏幕的合成层
        GLES30.glViewport(0, 0, 1080, 2400); 
        GLES30.glUseProgram(program);
        int resLoc = GLES30.glGetUniformLocation(program, "u_resolution");
        GLES30.glUniform2f(resLoc, 1080.0f, 2400.0f);
    }

    @Override 
    public void onDrawFrame(GL10 gl) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(program);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "u_time"), (System.currentTimeMillis() - startTime) / 1000.0f);
        int positionHandle = GLES30.glGetAttribLocation(program, "vPosition");
        GLES30.glEnableVertexAttribArray(positionHandle);
        GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(positionHandle);
    }
}
