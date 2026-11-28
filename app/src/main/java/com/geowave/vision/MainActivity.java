package com.geowave.vision;

import android.Manifest;
import android.content.*;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.view.TransformExperimental;
import androidx.camera.view.transform.CoordinateTransform;
import androidx.camera.view.transform.ImageProxyTransformFactory;
import androidx.camera.view.transform.OutputTransform;
import com.google.ar.core.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity implements WorldView.Host {
    private static final int BG = 0xff0b141b, PANEL = 0xff172832, TEXT = 0xffecf6f8, MUTED = 0xff9ab3bc, MINT = 0xff75efcf;
    private static final int CAMERA_PERMISSION = 10, MIC_PERMISSION = 11, WAV = 12, CSV = 13;
    private static final int[] LABELS = {R.string.mag, R.string.accel, R.string.gyro, R.string.light, R.string.sound, R.string.peak};
    private static final String[] UNITS = {"µT", "m/s²", "rad/s", "lx", "dBFS", "Hz"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final ExecutorService analyzer = Executors.newSingleThreadExecutor();
    private final ScanLog scan = new ScanLog();
    private Measurements measurements;
    private Objects objects;
    private ProfilePlayer audio;
    private Session session;
    private WorldView world;
    private ProcessCameraProvider provider;
    private PreviewView preview;
    private DetectionOverlay overlay;
    private FrameLayout viewport;
    private LinearLayout pane, tabs;
    private TextView status, sensorText, sampleText, audioText;
    private Button scanButton, emergencyStop;
    private List<Integer> targetIds = new ArrayList<>();
    private Set<Integer> selection = new LinkedHashSet<>();
    private volatile List<DetectionOverlay.Item> worldBoxes = Collections.emptyList();
    private boolean resumed, installRequested, preferFallback, thai, visualPlaying;
    private int tab, metric, generation, duration = 30;
    private boolean repeat = true;
    private float volume = .5f;
    private Uri wav;

    @Override protected void attachBaseContext(Context base) {
        boolean useThai = base.getSharedPreferences("geowave", MODE_PRIVATE)
            .getBoolean("thai", Locale.getDefault().getLanguage().equals("th"));
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(useThai ? "th" : "en"));
        super.attachBaseContext(base.createConfigurationContext(configuration));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.content.SharedPreferences prefs = getSharedPreferences("geowave", MODE_PRIVATE);
        thai = getResources().getConfiguration().getLocales().get(0).getLanguage().equals("th");
        preferFallback = prefs.getBoolean("fallback", false);
        String previousWav = prefs.getString("wav", null);
        if (previousWav != null) wav = Uri.parse(previousWav);
        measurements = new Measurements(this);
        objects = new Objects();
        audio = new ProfilePlayer(this, (playing, error) -> {
            visualPlaying = playing;
            if (world != null) world.animating = playing;
            if (emergencyStop != null) emergencyStop.setVisibility(playing ? View.VISIBLE : View.GONE);
            if (error) toast(R.string.audio_error);
        });
        buildUi();
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(10), dp(14), dp(6));
        LinearLayout header = row();
        TextView title = label("GeoWave Vision AR V4", 20, MINT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button(R.string.language, () -> {
            scan.stop();
            if (scan.count() > 0) saveCurrent(false);
            getSharedPreferences("geowave", MODE_PRIVATE).edit().putBoolean("thai", !thai).apply();
            recreate();
        }));
        root.addView(header);
        status = label(getString(R.string.checking), 13, MUTED);
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);
        viewport = new FrameLayout(this);
        viewport.setBackground(background(0xff102029, 10));
        viewport.setClipToOutline(true);
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1.15f));
        attachSurface(new View(this));
        tabs = row();
        root.addView(tabs);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        pane = column();
        pane.setPadding(dp(8), dp(4), dp(8), dp(8));
        pane.setBackground(background(PANEL, 12));
        scroll.addView(pane);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        showTab(0);
    }

    private void showTab(int index) {
        tab = index;
        tabs.removeAllViews();
        int[] names = {R.string.scan, R.string.targets, R.string.audio};
        for (int i = 0; i < names.length; i++) {
            int next = i;
            Button item = button(names[i], () -> showTab(next));
            if (i == index) {
                item.setTextColor(BG);
                item.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MINT));
            }
            tabs.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        pane.removeAllViews();
        sensorText = sampleText = audioText = null;
        scanButton = null;
        switch (index) {
            case 0 -> showScan();
            case 1 -> showTargets();
            case 2 -> showAudio();
            default -> throw new IllegalArgumentException("Unknown tab");
        }
    }

    private void showScan() {
        sensorText = label("", 14, TEXT);
        sampleText = label("", 13, MINT);
        scanButton = button(scan.active() ? R.string.stop_scan : R.string.start_scan, () -> {
            if (!scan.active() && world != null) world.resetHeatMap();
            scan.toggle();
            refreshReadings();
        });
        pane.addView(sensorText);
        pane.addView(sampleText);
        addRow(scanButton, button(R.string.save, () -> saveCurrent(true)), button(R.string.export, this::exportCurrent));
        addRow(button(R.string.latest, this::exportSaved));
        pane.addView(label(getString(R.string.heatmap), 13, MUTED));
        Spinner spinner = new Spinner(this);
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 5; i++) labels.add(getString(LABELS[i]) + " (" + UNITS[i] + ")");
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(metric);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                metric = position;
                if (world != null) world.metric = Measurements.TYPES[position];
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        pane.addView(spinner);
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            pane.addView(button(R.string.mic, () -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION)));
        }
        if (!has(Manifest.permission.CAMERA)) {
            pane.addView(button(R.string.permission, this::requestCamera));
        }
        pane.addView(button(preferFallback ? R.string.ar_enabled : R.string.fallback_button, () -> {
            preferFallback = !preferFallback;
            getSharedPreferences("geowave", MODE_PRIVATE).edit().putBoolean("fallback", preferFallback).apply();
            stopCamera(true);
            startCamera();
            showTab(0);
        }));
        refreshReadings();
    }

    private void showTargets() {
        pane.addView(label(getString(world == null ? R.string.need_ar : R.string.tap_surface), 14, TEXT));
        if (world == null) return;
        addRow(button(R.string.select_all, () -> world.choose(new LinkedHashSet<>(targetIds))),
            button(R.string.clear, () -> world.choose(Collections.emptySet())));
        for (int id : targetIds) {
            CheckBox entry = new CheckBox(this);
            entry.setText(getString(R.string.target_item, id));
            entry.setChecked(selection.contains(id));
            entry.setOnCheckedChangeListener((view, checked) -> {
                if (checked) selection.add(id); else selection.remove(id);
                if (world != null) world.choose(new LinkedHashSet<>(selection));
            });
            pane.addView(entry);
        }
        Button move = button(R.string.move, () -> {
            if (world == null) return;
            world.armMove();
            toast(R.string.move_hint);
        });
        move.setEnabled(selection.size() == 1);
        Button remove = button(R.string.remove, () -> { if (world != null) world.removeSelected(); });
        remove.setEnabled(!selection.isEmpty());
        addRow(move, remove);
    }

    private void showAudio() {
        pane.addView(label(getString(wav == null ? R.string.symbolic : R.string.imported), 16, MINT));
        pane.addView(label(getString(R.string.audio_note), 13, MUTED));
        addRow(button(R.string.import_wav, this::chooseWav), button(R.string.builtin, () -> {
            setWav(null);
            showTab(2);
        }));
        TextView amplitude = label(getString(R.string.amplitude, Math.round(volume * 100)), 14, TEXT);
        pane.addView(amplitude);
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(Math.round(volume * 100));
        slider.setContentDescription(getString(R.string.amplitude, slider.getProgress()));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                volume = progress / 100f;
                audio.amplitude(volume);
                if (world != null) world.amplitude = volume;
                amplitude.setText(getString(R.string.amplitude, progress));
                slider.setContentDescription(amplitude.getText());
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        pane.addView(slider);
        EditText time = new EditText(this);
        time.setHint(R.string.duration);
        time.setContentDescription(getString(R.string.duration));
        time.setInputType(InputType.TYPE_CLASS_NUMBER);
        time.setText(String.format(getResources().getConfiguration().getLocales().get(0), "%d", duration));
        pane.addView(label(getString(R.string.duration), 13, MUTED));
        pane.addView(time);
        CheckBox looping = new CheckBox(this);
        looping.setText(R.string.repeat);
        looping.setChecked(repeat);
        looping.setOnCheckedChangeListener((view, checked) -> repeat = checked);
        pane.addView(looping);
        Button emergency = button(R.string.stop_audio, audio::stop);
        emergency.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff842e3d));
        addRow(button(R.string.play, () -> {
            int seconds;
            try { seconds = Integer.parseInt(time.getText().toString()); }
            catch (NumberFormatException e) { seconds = 0; }
            if (seconds < 1 || seconds > 3600) {
                time.setError(getString(R.string.invalid_timer));
                return;
            }
            duration = seconds;
            audio.play(wav, seconds, repeat);
        }), emergency);
        audioText = label("", 14, MINT);
        pane.addView(audioText);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        measurements.start();
        if (has(Manifest.permission.RECORD_AUDIO)) measurements.startMic();
        startCamera();
        handler.post(tick);
        handler.post(boxTick);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(boxTick);
        stopCamera(false);
        measurements.stop();
        audio.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopCamera(true);
        objects.close();
        analyzer.shutdown();
        files.shutdown();
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (world == null && scan.active()) {
                scan.append(SystemClock.elapsedRealtimeNanos(), 0, null, null, measurements.snapshot());
            }
            refreshReadings();
            if (audioText != null) audioText.setText(visualPlaying ? getString(R.string.remaining, audio.remaining()) : "");
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable boxTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshBoxes();
            handler.postDelayed(this, 125);
        }
    };

    private void refreshReadings() {
        if (sensorText == null) return;
        Map<Integer, Measurements.Reading> values = measurements.snapshot();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < LABELS.length; i++) {
            Measurements.Reading reading = values.get(Measurements.TYPES[Math.min(i, 4)]);
            double value = i == 5 && reading != null ? reading.values()[1] : Measurements.magnitude(reading);
            if (i != 0) text.append('\n');
            text.append(getString(LABELS[i])).append("  ");
            text.append(Double.isFinite(value)
                ? String.format(getResources().getConfiguration().getLocales().get(0), "%.2f %s", value, UNITS[i])
                : getString(R.string.unavailable));
        }
        sensorText.setText(text);
        int count = scan.count();
        sampleText.setText(getResources().getQuantityString(R.plurals.samples, count, count));
        scanButton.setText(scan.active() ? R.string.stop_scan : R.string.start_scan);
    }

    private void startCamera() {
        if (!resumed) return;
        int attempt = ++generation;
        if (!has(Manifest.permission.CAMERA)) {
            status.setText(R.string.sensor_only);
            if (overlay == null) attachSurface(new View(this));
            if (!getPreferences(MODE_PRIVATE).getBoolean("asked", false)) requestCamera();
            return;
        }
        if (world != null && session != null) {
            try {
                session.resume();
                world.onResume();
                return;
            } catch (Exception e) {
                stopCamera(true);
                startFallback(++generation);
                return;
            }
        }
        if (preferFallback) {
            startFallback(attempt);
            return;
        }
        status.setText(R.string.checking);
        ArCoreApk.getInstance().checkAvailabilityAsync(this, availability -> {
            if (!resumed || generation != attempt) return;
            if (!availability.isSupported()) {
                startFallback(attempt);
                return;
            }
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true;
                    return;
                }
                session = new Session(this);
                Config config = session.getConfig();
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
                config.setFocusMode(Config.FocusMode.AUTO);
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) config.setDepthMode(Config.DepthMode.AUTOMATIC);
                session.configure(config);
                session.resume();
                world = new WorldView(this, session, measurements, scan, objects, this);
                world.metric = Measurements.TYPES[metric];
                world.animating = visualPlaying;
                world.amplitude = volume;
                attachSurface(world);
                world.onResume();
                if (tab == 1) showTab(1);
            } catch (Exception e) {
                stopCamera(true);
                startFallback(++generation);
            }
        });
    }

    private void requestCamera() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("asked", true).apply();
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
    }

    @androidx.annotation.OptIn(markerClass = {ExperimentalGetImage.class, TransformExperimental.class})
    private void startFallback(int attempt) {
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        attachSurface(preview);
        status.setText(R.string.fallback);
        var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (!resumed || generation != attempt) return;
            try {
                provider = future.get();
                Preview camera = new Preview.Builder().build();
                camera.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis images = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                ImageProxyTransformFactory imageTransform = new ImageProxyTransformFactory();
                imageTransform.setUsingRotationDegrees(true);
                images.setAnalyzer(analyzer, image -> {
                    android.media.Image media = image.getImage();
                    if (media == null || !objects.available()) {
                        image.close();
                    } else {
                        objects.submit(media, image.getImageInfo().getRotationDegrees(),
                            imageTransform.getOutputTransform(image), image::close);
                    }
                });
                preview.post(() -> {
                    if (!resumed || generation != attempt) return;
                    try {
                        ViewPort field = preview.getViewPort();
                        if (field == null) {
                            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, camera, images);
                        } else {
                            UseCaseGroup group = new UseCaseGroup.Builder().setViewPort(field).addUseCase(camera).addUseCase(images).build();
                            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group);
                        }
                    } catch (RuntimeException e) { status.setText(R.string.sensor_only); }
                });
            } catch (Exception e) {
                status.setText(R.string.sensor_only);
            }
        }, handler::post);
    }

    private void attachSurface(View view) {
        viewport.removeAllViews();
        viewport.addView(view, new FrameLayout.LayoutParams(-1, -1));
        overlay = new DetectionOverlay(this);
        viewport.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        emergencyStop = button(R.string.stop_audio, audio::stop);
        emergencyStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff842e3d));
        emergencyStop.setVisibility(visualPlaying ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams stop = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        stop.setMargins(dp(8), dp(8), dp(8), dp(8));
        viewport.addView(emergencyStop, stop);
    }

    private void stopCamera(boolean release) {
        generation++;
        if (provider != null) {
            provider.unbindAll();
            provider = null;
        }
        preview = null;
        if (world != null) {
            world.onPause();
            session.pause();
            if (release) {
                session.close();
                session = null;
                world = null;
                targetIds.clear();
                selection.clear();
            }
        } else if (release && session != null) {
            session.close();
            session = null;
        }
        if (release) {
            worldBoxes = Collections.emptyList();
            viewport.removeAllViews();
            overlay = null;
            emergencyStop = null;
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED || !resumed) return;
        if (code == MIC_PERMISSION) measurements.startMic();
        if (code == CAMERA_PERMISSION && world == null && provider == null) startCamera();
        if (tab == 0) showTab(0);
    }

    @Override public void mode(String mode) {
        runOnUiThread(() -> {
            if (!resumed || world == null) return;
            switch (mode) {
                case "tracking" -> status.setText(R.string.tracking);
                case "search" -> status.setText(R.string.search);
                case "limit" -> toast(R.string.limit);
                case "no_surface" -> toast(R.string.no_surface);
            }
        });
    }

    @Override public void targets(List<Integer> ids, Set<Integer> selected) {
        runOnUiThread(() -> {
            if (world == null) return;
            targetIds = ids;
            selection = selected;
            if (tab == 1) {
                pane.removeAllViews();
                showTargets();
            }
        });
    }

    @Override public void failed() {
        runOnUiThread(() -> {
            if (!resumed || world == null) return;
            stopCamera(true);
            startFallback(++generation);
            if (tab == 1) showTab(1);
        });
    }

    @Override public void frame(Frame frame, int w, int h) {
        Objects.Result result = objects.latest;
        if (result == null || result.transform() != null || SystemClock.elapsedRealtime() - result.timestamp() > 900) {
            worldBoxes = Collections.emptyList();
            return;
        }
        List<DetectionOverlay.Item> visible = new ArrayList<>();
        for (Objects.Box box : result.boxes()) {
            android.graphics.Rect b = box.bounds();
            float[] pixels = {b.left, b.top, b.right, b.top, b.left, b.bottom, b.right, b.bottom};
            for (int i = 0; i < pixels.length; i += 2) {
                float x = pixels[i], y = pixels[i+1];
                switch (result.rotation()) {
                    case 90 -> { pixels[i] = y; pixels[i+1] = result.height() - x; }
                    case 180 -> { pixels[i] = result.width() - x; pixels[i+1] = result.height() - y; }
                    case 270 -> { pixels[i] = result.width() - y; pixels[i+1] = x; }
                }
            }
            float[] mapped = new float[8];
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, pixels, Coordinates2d.VIEW, mapped);
            RectF r = new RectF(mapped[0], mapped[1], mapped[0], mapped[1]);
            for (int i = 2; i < mapped.length; i += 2) {
                r.left = Math.min(r.left, mapped[i]); r.right = Math.max(r.right, mapped[i]);
                r.top = Math.min(r.top, mapped[i+1]); r.bottom = Math.max(r.bottom, mapped[i+1]);
            }
            visible.add(new DetectionOverlay.Item(r, box.category()));
        }
        worldBoxes = visible;
    }

    @androidx.annotation.OptIn(markerClass = TransformExperimental.class)
    private void refreshBoxes() {
        if (overlay == null) return;
        Objects.Result result = objects.latest;
        if (result == null || SystemClock.elapsedRealtime() - result.timestamp() > 900) {
            overlay.show(Collections.emptyList());
            if (objects.error) overlay.setContentDescription(getString(R.string.ai_error));
            return;
        }
        if (world != null) {
            overlay.show(worldBoxes);
            return;
        }
        OutputTransform destination = preview == null ? null : preview.getOutputTransform();
        if (result.transform() == null || destination == null) {
            overlay.show(Collections.emptyList());
            return;
        }
        CoordinateTransform transform = new CoordinateTransform(result.transform(), destination);
        List<DetectionOverlay.Item> visible = new ArrayList<>();
        for (Objects.Box box : result.boxes()) {
            RectF rect = new RectF(box.bounds());
            transform.mapRect(rect);
            visible.add(new DetectionOverlay.Item(rect, box.category()));
        }
        overlay.show(visible);
    }

    private void chooseWav() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/wav","audio/x-wav","audio/vnd.wave","audio/wave","application/octet-stream"});
        startActivityForResult(intent, WAV);
    }

    private void setWav(Uri uri) {
        wav = uri;
        getSharedPreferences("geowave", MODE_PRIVATE).edit().putString("wav", uri == null ? null : uri.toString()).apply();
    }

    private File savedScan() { return new File(getFilesDir(), "latest-scan.csv"); }

    private void saveCurrent(boolean notify) {
        if (scan.count() == 0) {
            if (notify) toast(R.string.no_scan);
            return;
        }
        byte[] data = scan.snapshot();
        files.execute(() -> {
            try {
                ScanLog.save(savedScan(), data);
                if (notify) runOnUiThread(() -> toast(R.string.saved));
            } catch (IOException e) { runOnUiThread(() -> toast(R.string.file_error)); }
        });
    }

    private void exportCurrent() {
        if (scan.count() == 0) toast(R.string.no_scan);
        else chooseExport(scan.snapshot());
    }

    private void exportSaved() {
        files.execute(() -> {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(savedScan().toPath());
                runOnUiThread(() -> chooseExport(bytes));
            } catch (IOException e) { runOnUiThread(() -> toast(R.string.file_error)); }
        });
    }

    private File pendingExport() { return new File(getCacheDir(), "pending-scan-export.csv"); }

    private void chooseExport(byte[] bytes) {
        files.execute(() -> {
            try {
                ScanLog.save(pendingExport(), bytes);
                runOnUiThread(this::openExportPicker);
            } catch (IOException e) { runOnUiThread(() -> toast(R.string.file_error)); }
        });
    }

    private void openExportPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "GeoWave-scan.csv");
        startActivityForResult(intent, CSV);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == WAV) {
            files.execute(() -> {
                try {
                    if (!ProfilePlayer.isWav(getContentResolver().openInputStream(uri))) {
                        runOnUiThread(() -> toast(R.string.bad_wav));
                        return;
                    }
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    runOnUiThread(() -> { setWav(uri); showTab(2); });
                } catch (IOException | SecurityException e) { runOnUiThread(() -> toast(R.string.file_error)); }
            });
        } else if (request == CSV) {
            files.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("Document unavailable");
                    java.nio.file.Files.copy(pendingExport().toPath(), out);
                    runOnUiThread(() -> toast(R.string.exported));
                } catch (IOException | SecurityException e) { runOnUiThread(() -> toast(R.string.file_error)); }
            });
        }
    }

    private boolean has(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(int message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private Button button(int text, Runnable click) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(TEXT);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff29434f));
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private void addRow(Button... buttons) {
        LinearLayout row = row();
        for (Button button : buttons) row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        pane.addView(row);
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
}
