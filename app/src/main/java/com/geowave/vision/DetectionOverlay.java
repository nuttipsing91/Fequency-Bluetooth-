package com.geowave.vision;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

final class DetectionOverlay extends View {
    record Item(RectF area, int category) {}
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private List<Item> items = new ArrayList<>();

    DetectionOverlay(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void show(List<Item> boxes) {
        items = boxes;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Item item : items) {
            RectF rect = item.area();
            paint.setColor(0xff65f1d1);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2*density);
            canvas.drawRoundRect(rect, 6*density, 6*density, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(13*density);
            String label = getContext().getString(switch (item.category()) {
                case 0 -> R.string.home_goods;
                case 1 -> R.string.fashion;
                case 2 -> R.string.food;
                case 3 -> R.string.plant;
                case 4 -> R.string.place;
                default -> R.string.object;
            });
            float y = Math.max(17*density, rect.top);
            canvas.drawRect(rect.left, y-17*density, rect.left + paint.measureText(label) + 12*density, y, paint);
            paint.setColor(0xff07201f);
            canvas.drawText(label, rect.left + 6*density, y-4*density, paint);
        }
    }
}
