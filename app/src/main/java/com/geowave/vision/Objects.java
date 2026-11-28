package com.geowave.vision;

import android.graphics.Rect;
import android.media.Image;
import androidx.camera.view.transform.OutputTransform;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.*;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@androidx.annotation.OptIn(markerClass = androidx.camera.view.TransformExperimental.class)
final class Objects {
    record Box(Rect bounds, int category) {}
    record Result(List<Box> boxes, int width, int height, int rotation, long timestamp, OutputTransform transform) {}
    private final ObjectDetector detector = ObjectDetection.getClient(new ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE).enableMultipleObjects().enableClassification().build());
    private final AtomicBoolean busy = new AtomicBoolean();
    volatile Result latest;
    volatile boolean error;

    boolean available() { return !busy.get(); }

    void submit(Image image, int rotation, OutputTransform transform, Runnable release) {
        if (!busy.compareAndSet(false, true)) {
            release.run();
            return;
        }
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            detector.process(InputImage.fromMediaImage(image, rotation))
                .addOnSuccessListener(objects -> {
                    List<Box> boxes = new ArrayList<>();
                    for (DetectedObject object : objects) {
                        int category = object.getLabels().isEmpty() ? -1 : object.getLabels().get(0).getIndex();
                        boxes.add(new Box(new Rect(object.getBoundingBox()), category));
                    }
                    latest = new Result(boxes, width, height, rotation, android.os.SystemClock.elapsedRealtime(), transform);
                    error = false;
                })
                .addOnFailureListener(e -> error = true)
                .addOnCompleteListener(task -> {
                    release.run();
                    busy.set(false);
                });
        } catch (RuntimeException e) {
            release.run();
            busy.set(false);
            error = true;
        }
    }

    void close() { detector.close(); }
}
