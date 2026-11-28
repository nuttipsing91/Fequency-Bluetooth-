package com.geowave.vision;

import android.content.Context;
import android.hardware.Sensor;
import android.opengl.*;
import android.os.SystemClock;
import android.view.MotionEvent;
import com.google.ar.core.*;
import com.google.ar.core.exceptions.*;
import java.nio.*;
import java.util.*;

final class WorldView extends GLSurfaceView implements GLSurfaceView.Renderer {
    interface Host {
        void mode(String mode);
        void targets(List<Integer> ids, Set<Integer> selected);
        void frame(Frame frame, int w, int h);
        void failed();
    }

    private record Sample(Anchor anchor, float[] translation, Map<Integer, Measurements.Reading> values) {}
    private record Target(int id, Anchor anchor) {}
    private final Session session;
    private final String sessionId = UUID.randomUUID().toString();
    private final Measurements sensors;
    private final ScanLog scan;
    private final Objects objects;
    private final Host host;
    private final List<Sample> samples = new ArrayList<>();
    private final List<Target> targets = new ArrayList<>();
    private final Set<Integer> selected = new LinkedHashSet<>();
    private final FloatBuffer quad = buffer(-1,-1, 1,-1, -1,1, 1,1);
    private final FloatBuffer uv = buffer(0,0, 0,0, 0,0, 0,0);
    private final FloatBuffer vertex = buffer(0,0,0);
    private final FloatBuffer heatVertices = ByteBuffer.allocateDirect(600 * 7 * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] ringLocal = new float[3], ringWorld = new float[3];
    private final FloatBuffer rings = ByteBuffer.allocateDirect(64 * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] view = new float[16], projection = new float[16], vp = new float[16];
    private int width, height, cameraProgram, worldProgram, texture, nextId = 1;
    private int geometryRotation = -1;
    private int worldPosition, worldColor, worldMatrix, worldSize, worldDot;
    private boolean move, failed;
    private float tapX, tapY;
    private boolean tap;
    private long lastSample, lastAi;
    private String lastMode = "";
    volatile int metric = Sensor.TYPE_MAGNETIC_FIELD;
    volatile boolean animating;
    volatile float amplitude = 0.5f;

    WorldView(Context context, Session session, Measurements sensors, ScanLog scan, Objects objects, Host host) {
        super(context);
        this.session = session;
        this.sensors = sensors;
        this.scan = scan;
        this.objects = objects;
        this.host = host;
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = event.getX(), y = event.getY();
            queueEvent(() -> { tapX = x; tapY = y; tap = true; });
            performClick();
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    void choose(Set<Integer> ids) {
        queueEvent(() -> {
            selected.clear();
            for (Target target : targets) if (ids.contains(target.id())) selected.add(target.id());
            move = false;
            notifyTargets();
        });
    }

    void armMove() { queueEvent(() -> move = selected.size() == 1); }

    void removeSelected() {
        queueEvent(() -> {
            Iterator<Target> it = targets.iterator();
            while (it.hasNext()) {
                Target target = it.next();
                if (selected.contains(target.id())) {
                    target.anchor().detach();
                    it.remove();
                }
            }
            selected.clear();
            move = false;
            notifyTargets();
        });
    }

    void resetHeatMap() {
        queueEvent(() -> {
            for (Sample sample : samples) sample.anchor().detach();
            samples.clear();
        });
    }

    private void notifyTargets() {
        List<Integer> ids = new ArrayList<>();
        for (Target target : targets) ids.add(target.id());
        host.targets(ids, new LinkedHashSet<>(selected));
    }

    @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 ignored,
                                            javax.microedition.khronos.egl.EGLConfig config) {
        geometryRotation = -1;
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        texture = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        cameraProgram = program(
            "attribute vec2 pos; attribute vec2 tex; varying mediump vec2 st; void main(){gl_Position=vec4(pos,0.,1.);st=tex;}",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image;"
            + " varying mediump vec2 st; void main(){gl_FragColor=texture2D(image,st);}");
        worldProgram = program(
            "attribute vec3 pos; attribute vec4 rgba; varying lowp vec4 color; uniform mat4 vp; uniform float size;"
            + "void main(){color=rgba; gl_Position=vp*vec4(pos,1.);"
            + "gl_PointSize=clamp(size/max(gl_Position.w,.25),4.,80.);}",
            "precision mediump float; varying lowp vec4 color; uniform float dotMode; void main(){"
            + "if(dotMode>.5){float r=length(gl_PointCoord-.5); if(r>.5)discard;"
            + "gl_FragColor=vec4(color.rgb,color.a*(1.-smoothstep(.12,.5,r)));}else{gl_FragColor=color;}}");
        worldPosition = GLES20.glGetAttribLocation(worldProgram, "pos");
        worldColor = GLES20.glGetAttribLocation(worldProgram, "rgba");
        worldMatrix = GLES20.glGetUniformLocation(worldProgram, "vp");
        worldSize = GLES20.glGetUniformLocation(worldProgram, "size");
        worldDot = GLES20.glGetUniformLocation(worldProgram, "dotMode");
        GLES20.glClearColor(0.02f, 0.07f, 0.09f, 1);
    }

    @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 ignored, int w, int h) {
        width = w;
        height = h;
        geometryRotation = -1;
        GLES20.glViewport(0, 0, w, h);
    }

    @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 ignored) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (failed || width == 0 || height == 0) return;
        try {
            int rotation = getDisplay() == null ? 0 : getDisplay().getRotation();
            if (geometryRotation != rotation) {
                session.setDisplayGeometry(rotation, width, height);
                session.setCameraTextureName(texture);
                geometryRotation = rotation;
            }
            Frame frame = session.update();
            if (frame.getTimestamp() == 0) return;
            drawCamera(frame);
            long now = SystemClock.elapsedRealtime();
            if (now - lastAi > 200 && objects.available()) {
                lastAi = now;
                try {
                    android.media.Image image = frame.acquireCameraImage();
                    float[] horizontal = {0, 0, 1, 0};
                    float[] pixels = new float[4];
                    frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, horizontal, Coordinates2d.IMAGE_PIXELS, pixels);
                    float dx = pixels[2] - pixels[0], dy = pixels[3] - pixels[1];
                    int imageRotation = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 0 : 180) : (dy > 0 ? 270 : 90);
                    objects.submit(image, imageRotation, null, image::close);
                } catch (NotYetAvailableException | ResourceExhaustedException ignoredImage) {}
            }
            host.frame(frame, width, height);
            Camera camera = frame.getCamera();
            boolean tracking = camera.getTrackingState() == TrackingState.TRACKING;
            String mode = tracking ? "tracking" : "search";
            if (!lastMode.equals(mode)) {
                lastMode = mode;
                host.mode(mode);
            }
            if (scan.active() && now - lastSample >= 500 && (tracking || now - lastSample >= 1000)) {
                lastSample = now;
                capture(frame, camera, tracking);
            }
            if (!tracking) {
                tap = false;
                return;
            }
            camera.getViewMatrix(view, 0);
            camera.getProjectionMatrix(projection, 0, .05f, 60f);
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0);
            if (tap) {
                tap = false;
                try {
                    place(frame);
                } catch (NotTrackingException | ResourceExhaustedException | DeadlineExceededException e) {
                    host.mode("no_surface");
                }
            }
            drawWorld(now);
        } catch (CameraNotAvailableException | SessionPausedException e) {
            if (e instanceof SessionPausedException) return;
            failed = true;
            host.failed();
        }
    }

    private void capture(Frame frame, Camera camera, boolean tracking) {
        Map<Integer, Measurements.Reading> values = sensors.snapshot();
        Pose pose = tracking ? camera.getPose() : null;
        float[] position = pose == null ? null : pose.getTranslation();
        scan.append(SystemClock.elapsedRealtimeNanos(), frame.getTimestamp(), sessionId, position, values);
        if (position == null || values.isEmpty()) return;
        // Group nearby readings around a shared AR anchor to keep the number of live anchors bounded.
        Anchor origin = samples.isEmpty() ? null : samples.get(samples.size()-1).anchor();
        if (origin == null || origin.getTrackingState() != TrackingState.TRACKING
                || distance(origin.getPose().getTranslation(), position) > 1.5f) {
            try {
                origin = session.createAnchor(Pose.makeTranslation(position));
            } catch (NotTrackingException | ResourceExhaustedException e) {
                return;
            }
        }
        float[] local = origin.getPose().inverse().transformPoint(position);
        if (samples.size() == 600) {
            Anchor old = samples.remove(0).anchor();
            if (old != origin && samples.stream().noneMatch(sample -> sample.anchor() == old)) old.detach();
        }
        samples.add(new Sample(origin, local, values));
    }

    private static float distance(float[] a, float[] b) {
        float x = a[0]-b[0], y = a[1]-b[1], z = a[2]-b[2];
        return (float) Math.sqrt(x*x + y*y + z*z);
    }

    private void place(Frame frame) {
        if (!move) {
            for (Target target : targets) {
                if (target.anchor().getTrackingState() != TrackingState.TRACKING) continue;
                float[] p = target.anchor().getPose().getTranslation();
                float[] projected = new float[4];
                Matrix.multiplyMV(projected, 0, vp, 0, new float[]{p[0],p[1],p[2],1}, 0);
                if (projected[3] <= 0) continue;
                float x = width * (.5f + .5f * projected[0]/projected[3]);
                float y = height * (.5f - .5f * projected[1]/projected[3]);
                if (Math.hypot(x-tapX, y-tapY) < 30*getResources().getDisplayMetrics().density) {
                    if (!selected.remove(target.id())) selected.add(target.id());
                    notifyTargets();
                    return;
                }
            }
            if (targets.size() >= 32) {
                host.mode("limit");
                return;
            }
        }
        for (HitResult hit : frame.hitTest(tapX, tapY)) {
            Trackable trackable = hit.getTrackable();
            boolean supported = trackable instanceof DepthPoint
                || (trackable instanceof Plane plane && plane.isPoseInPolygon(hit.getHitPose()))
                || (trackable instanceof Point point && point.getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL);
            if (!supported) continue;
            Anchor anchor = hit.createAnchor();
            if (move) {
                for (int i = 0; i < targets.size(); i++) {
                    Target existing = targets.get(i);
                    if (!selected.contains(existing.id())) continue;
                    existing.anchor().detach();
                    targets.set(i, new Target(existing.id(), anchor));
                    break;
                }
            } else {
                int id = nextId++;
                targets.add(new Target(id, anchor));
                selected.clear();
                selected.add(id);
            }
            move = false;
            notifyTargets();
            return;
        }
        host.mode("no_surface");
    }

    private void drawCamera(Frame frame) {
        quad.position(0);
        uv.position(0);
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
            Coordinates2d.TEXTURE_NORMALIZED, uv);
        GLES20.glUseProgram(cameraProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram, "image"), 0);
        int pos = attribute(cameraProgram, "pos", 2, quad);
        int tex = attribute(cameraProgram, "tex", 2, uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
        GLES20.glDisableVertexAttribArray(tex);
    }

    private void drawWorld(long now) {
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(worldProgram);
        GLES20.glUniformMatrix4fv(worldMatrix, 1, false, vp, 0);
        heatVertices.clear();
        int activeMetric = metric;
        for (Sample sample : samples) {
            if (sample.anchor().getTrackingState() != TrackingState.TRACKING) continue;
            double magnitude = Measurements.magnitude(sample.values().get(activeMetric));
            if (!Double.isFinite(magnitude)) continue;
            float heat = (float) Math.max(0, Math.min(1, switch (activeMetric) {
                case Sensor.TYPE_MAGNETIC_FIELD -> magnitude / 100;
                case Sensor.TYPE_ACCELEROMETER -> magnitude / 20;
                case Sensor.TYPE_GYROSCOPE -> magnitude / 4;
                case Sensor.TYPE_LIGHT -> Math.log10(1 + magnitude) / 4;
                default -> (magnitude + 80) / 80;
            }));
            heatVertices.put(sample.anchor().getPose().transformPoint(sample.translation()));
            heatVertices.put(heat).put(.3f).put(1-heat).put(.52f);
        }
        drawHeat();
        for (Target target : targets) {
            Anchor anchor = target.anchor();
            if (anchor.getTrackingState() != TrackingState.TRACKING) continue;
            boolean active = selected.contains(target.id());
            Pose pose = anchor.getPose();
            point(pose.getTranslation(), 33, active ? .1f : .85f, .95f, active ? .76f : .3f, 1);
            if (active && animating) {
                for (int ring = 0; ring < 3; ring++) {
                    float progress = ((now % 1800L) / 1800f + ring/3f) % 1f;
                    drawRing(pose, .05f + progress*(.25f + .3f*amplitude), 1-progress);
                }
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drawRing(Pose pose, float radius, float alpha) {
        rings.clear();
        for (int i = 0; i < 64; i++) {
            double angle = i * 2 * Math.PI / 64;
            ringLocal[0] = radius * (float) Math.cos(angle);
            ringLocal[1] = .015f;
            ringLocal[2] = radius * (float) Math.sin(angle);
            pose.transformPoint(ringLocal, 0, ringWorld, 0);
            rings.put(ringWorld);
        }
        draw(rings, GLES20.GL_LINE_LOOP, 64, 1, .14f, .96f, .77f, alpha);
    }

    private void point(float[] p, float size, float r, float g, float b, float a) {
        vertex.clear();
        vertex.put(p);
        draw(vertex, GLES20.GL_POINTS, 1, size, r, g, b, a);
    }

    private void drawHeat() {
        int count = heatVertices.position() / 7;
        if (count == 0) return;
        int stride = 7 * Float.BYTES;
        GLES20.glUniform1f(worldSize, 30);
        GLES20.glUniform1f(worldDot, 1);
        heatVertices.position(0);
        GLES20.glVertexAttribPointer(worldPosition, 3, GLES20.GL_FLOAT, false, stride, heatVertices);
        heatVertices.position(3);
        GLES20.glVertexAttribPointer(worldColor, 4, GLES20.GL_FLOAT, false, stride, heatVertices);
        GLES20.glEnableVertexAttribArray(worldPosition);
        GLES20.glEnableVertexAttribArray(worldColor);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count);
        GLES20.glDisableVertexAttribArray(worldPosition);
        GLES20.glDisableVertexAttribArray(worldColor);
    }

    private void draw(FloatBuffer points, int mode, int count, float size, float r, float g, float b, float a) {
        GLES20.glUniform1f(worldSize, size);
        GLES20.glUniform1f(worldDot, mode == GLES20.GL_POINTS ? 1 : 0);
        GLES20.glVertexAttrib4f(worldColor, r, g, b, a);
        points.position(0);
        GLES20.glVertexAttribPointer(worldPosition, 3, GLES20.GL_FLOAT, false, 0, points);
        GLES20.glEnableVertexAttribArray(worldPosition);
        GLES20.glDrawArrays(mode, 0, count);
        GLES20.glDisableVertexAttribArray(worldPosition);
    }

    private static int attribute(int program, String name, int dimension, FloatBuffer data) {
        data.position(0);
        int index = GLES20.glGetAttribLocation(program, name);
        GLES20.glVertexAttribPointer(index, dimension, GLES20.GL_FLOAT, false, 0, data);
        GLES20.glEnableVertexAttribArray(index);
        return index;
    }

    private static FloatBuffer buffer(float... data) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(data).position(0);
        return buffer;
    }

    private static int program(String vertex, String fragment) {
        int program = GLES20.glCreateProgram();
        int v = shader(GLES20.GL_VERTEX_SHADER, vertex);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        GLES20.glAttachShader(program, v);
        GLES20.glAttachShader(program, f);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return program;
    }

    private static int shader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
        return shader;
    }
}
