package ru.tubetv.app;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal zero-copy video output. MediaCodec renders into a SurfaceTexture and a
 * single external-texture shader draws it normally or mirrored.
 */
final class MirrorVideoView extends GLSurfaceView {
    interface SurfaceListener {
        void onSurfaceAvailable(Surface surface);
        void onSurfaceDestroyed(Surface surface);
    }

    private final VideoRenderer videoRenderer;
    private final AtomicInteger surfaceGeneration = new AtomicInteger();
    private SurfaceListener surfaceListener;
    private Surface currentSurface;
    private volatile boolean released;

    MirrorVideoView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        videoRenderer = new VideoRenderer();
        setRenderer(videoRenderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    void setSurfaceListener(SurfaceListener listener) {
        surfaceListener = listener;
        Surface surface = currentSurface;
        if (listener != null && surface != null) listener.onSurfaceAvailable(surface);
    }

    Surface getVideoSurface() {
        return currentSurface;
    }

    void setMirrored(boolean mirrored) {
        videoRenderer.mirrored = mirrored;
        requestRender();
    }

    void release() {
        released = true;
        surfaceGeneration.incrementAndGet();
        detachOutput();
    }

    private void attachOutput(Surface surface, int generation) {
        runOnMain(() -> {
            if (released || generation != surfaceGeneration.get()) {
                surface.release();
                return;
            }
            if (currentSurface != null && currentSurface != surface) {
                SurfaceListener listener = surfaceListener;
                if (listener != null) listener.onSurfaceDestroyed(currentSurface);
                currentSurface.release();
            }
            currentSurface = surface;
            SurfaceListener listener = surfaceListener;
            if (listener != null) listener.onSurfaceAvailable(surface);
        });
    }

    private void detachOutput() {
        Surface surface = currentSurface;
        if (surface == null) return;
        currentSurface = null;
        SurfaceListener listener = surfaceListener;
        if (listener != null) listener.onSurfaceDestroyed(surface);
        surface.release();
        videoRenderer.releaseTexture();
    }

    private static void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else new Handler(Looper.getMainLooper()).post(action);
    }

    private final class VideoRenderer implements Renderer, SurfaceTexture.OnFrameAvailableListener {
        private final FloatBuffer positions = buffer(new float[]{
                -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
        });
        private final FloatBuffer textureCoordinates = buffer(new float[]{
                0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
        });
        private final float[] textureMatrix = new float[16];
        private volatile boolean frameAvailable;
        private volatile boolean mirrored;
        private volatile SurfaceTexture surfaceTexture;
        private int texture;
        private int program;
        private int positionAttribute;
        private int textureAttribute;
        private int textureMatrixUniform;
        private int mirrorUniform;

        @Override public void onSurfaceCreated(
                javax.microedition.khronos.opengles.GL10 ignored,
                javax.microedition.khronos.egl.EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            if (released) return;
            // A preserved EGL context keeps this input alive across normal pause/resume.
            // Reaching onSurfaceCreated again means the context was recreated.
            releaseTexture();
            program = createProgram();
            positionAttribute = GLES20.glGetAttribLocation(program, "aPosition");
            textureAttribute = GLES20.glGetAttribLocation(program, "aTexCoord");
            textureMatrixUniform = GLES20.glGetUniformLocation(program, "uTexMatrix");
            mirrorUniform = GLES20.glGetUniformLocation(program, "uMirror");

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texture = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            surfaceTexture = new SurfaceTexture(texture);
            surfaceTexture.setOnFrameAvailableListener(this);
            attachOutput(new Surface(surfaceTexture), surfaceGeneration.get());
        }

        @Override public void onSurfaceChanged(
                javax.microedition.khronos.opengles.GL10 ignored, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
        }

        @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 ignored) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            SurfaceTexture input = surfaceTexture;
            if (input == null) return;
            if (frameAvailable) {
                frameAvailable = false;
                try {
                    input.updateTexImage();
                } catch (RuntimeException ignoredException) {
                    return;
                }
            }
            input.getTransformMatrix(textureMatrix);

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            positions.position(0);
            textureCoordinates.position(0);
            GLES20.glEnableVertexAttribArray(positionAttribute);
            GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT,
                    false, 0, positions);
            GLES20.glEnableVertexAttribArray(textureAttribute);
            GLES20.glVertexAttribPointer(textureAttribute, 2, GLES20.GL_FLOAT,
                    false, 0, textureCoordinates);
            GLES20.glUniformMatrix4fv(textureMatrixUniform, 1, false, textureMatrix, 0);
            GLES20.glUniform1f(mirrorUniform, mirrored ? -1f : 1f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionAttribute);
            GLES20.glDisableVertexAttribArray(textureAttribute);
        }

        @Override public void onFrameAvailable(SurfaceTexture ignored) {
            frameAvailable = true;
            requestRender();
        }

        void releaseTexture() {
            SurfaceTexture input = surfaceTexture;
            surfaceTexture = null;
            frameAvailable = false;
            if (input != null) input.release();
        }
    }

    private static FloatBuffer buffer(float[] values) {
        FloatBuffer result = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        result.put(values).position(0);
        return result;
    }

    private static int createProgram() {
        String vertexShader =
                "attribute vec4 aPosition;\n"
                + "attribute vec4 aTexCoord;\n"
                + "uniform mat4 uTexMatrix;\n"
                + "uniform float uMirror;\n"
                + "varying vec2 vTexCoord;\n"
                + "void main() {\n"
                + "  gl_Position = vec4(aPosition.x * uMirror, aPosition.y, 0.0, 1.0);\n"
                + "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n"
                + "}\n";
        String fragmentShader =
                "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "uniform samplerExternalOES uTexture;\n"
                + "varying vec2 vTexCoord;\n"
                + "void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }\n";
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String error = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("Video shader link failed: " + error);
        }
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Video shader compile failed: " + error);
        }
        return shader;
    }
}
