package com.myspace.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.widget.RemoteViews;

import org.json.JSONObject;

/**
 * Home-screen planet widget. The WebView pushes a tiny JSON snapshot (name,
 * progress, colours) through the bridge; this provider renders it natively so
 * the widget works while the app is closed.
 */
public final class PlanetWidgetProvider extends AppWidgetProvider {
    static final String PREFS = "myspace_widget";
    static final String DATA = "planet";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        RemoteViews views = build(context);
        for (int id : ids) {
            manager.updateAppWidget(id, views);
        }
    }

    static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            ComponentName provider = new ComponentName(context, PlanetWidgetProvider.class);
            int[] ids = manager.getAppWidgetIds(provider);
            if (ids == null || ids.length == 0) return;
            RemoteViews views = build(context);
            for (int id : ids) {
                manager.updateAppWidget(id, views);
            }
        } catch (Exception ignored) {
        }
    }

    static void store(Context context, String json) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(DATA, json).apply();
        updateAll(context);
    }

    static RemoteViews build(Context context) {
        String name = "AETHER";
        int pct = 0;
        String c1 = "#62f1e6";
        String c2 = "#177f99";
        String c3 = "#a58bff";
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject object = new JSONObject(prefs.getString(DATA, "{}"));
            name = object.optString("name", name);
            if (name.length() > 60) name = name.substring(0, 60);
            pct = Math.max(0, Math.min(100, object.optInt("pct", 0)));
            c1 = safeColor(object.optString("c1", c1), c1);
            c2 = safeColor(object.optString("c2", c2), c2);
            c3 = safeColor(object.optString("c3", c3), c3);
        } catch (Exception ignored) {
        }

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.planet_widget);
        views.setImageViewBitmap(R.id.widget_planet, renderPlanet(c1, c2, c3));
        views.setTextViewText(R.id.widget_name, name);
        views.setTextViewText(R.id.widget_pct, pct + "%");
        views.setContentDescription(R.id.widget_planet,
                name + "، التقدم " + pct + " بالمئة");
        views.setProgressBar(R.id.widget_progress, 100, pct, false);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pending);
        return views;
    }

    static String safeColor(String value, String fallback) {
        try {
            Color.parseColor(value);
            return value;
        } catch (Exception e) {
            return fallback;
        }
    }

    static Bitmap renderPlanet(String c1, String c2, String c3) {
        final int size = 420;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float cx = size / 2f;
        float cy = size / 2f;
        float radius = size * 0.24f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setShader(new RadialGradient(cx, cy, radius * 2.05f,
                new int[]{withAlpha(c1, 80), withAlpha(c1, 0)}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius * 2.05f, paint);

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        canvas.save();
        canvas.rotate(-18f, cx, cy);
        for (int i = 0; i < 3; i++) {
            paint.setStrokeWidth(size * (i == 2 ? 0.006f : 0.01f));
            paint.setColor(withAlpha(i == 1 ? c1 : c3, i == 0 ? 105 : 155));
            float ring = radius * (1.48f + i * 0.18f);
            canvas.drawOval(cx - ring, cy - ring * 0.32f,
                    cx + ring, cy + ring * 0.32f, paint);
        }
        canvas.restore();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx - radius * 0.32f, cy - radius * 0.36f,
                radius * 1.45f,
                new int[]{Color.parseColor(c1), Color.parseColor(c2),
                        Color.parseColor("#03070a")},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);

        // Subtle curved cloud bands add surface depth while staying within the bitmap mask.
        canvas.save();
        Path globeMask = new Path();
        globeMask.addCircle(cx, cy, radius * 0.995f, Path.Direction.CW);
        canvas.clipPath(globeMask);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 7; i++) {
            float offset = (i - 3) * radius * 0.235f;
            float wave = radius * (0.035f + (i % 2) * 0.018f);
            Path band = new Path();
            band.moveTo(cx - radius, cy + offset);
            band.cubicTo(cx - radius * 0.42f, cy + offset - wave,
                    cx + radius * 0.28f, cy + offset + wave,
                    cx + radius, cy + offset - wave * 0.28f);
            paint.setColor(withAlpha(i % 2 == 0 ? c1 : c3, 24 + (i % 3) * 9));
            paint.setStrokeWidth(size * (i % 3 == 0 ? 0.009f : 0.006f));
            canvas.drawPath(band, paint);
        }
        canvas.restore();

        paint.setShader(new RadialGradient(cx + radius * 0.55f, cy + radius * 0.4f,
                radius * 1.25f,
                new int[]{Color.TRANSPARENT, 0x66000000}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(c1, 190));
        float[][] stars = {{0.12f, 0.23f}, {0.84f, 0.19f}, {0.17f, 0.76f},
                {0.82f, 0.79f}, {0.5f, 0.08f}, {0.91f, 0.53f}};
        for (int i = 0; i < stars.length; i++) {
            float sx = stars[i][0] * size, sy = stars[i][1] * size;
            float sr = size * (i % 2 == 0 ? 0.006f : 0.004f);
            canvas.drawCircle(sx, sy, sr, paint);
        }
        paint.setColor(withAlpha(c3, 235));
        float starX = cx + radius * 1.62f;
        float starY = cy - radius * 1.5f;
        float starR = size * 0.034f;
        canvas.save();
        canvas.translate(starX, starY);
        canvas.rotate(45f);
        canvas.drawRect(-starR, -starR * 0.22f, starR, starR * 0.22f, paint);
        canvas.drawRect(-starR * 0.22f, -starR, starR * 0.22f, starR, paint);
        canvas.restore();
        return bitmap;
    }

    static int withAlpha(String hex, int alpha) {
        try {
            int color = Color.parseColor(hex);
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        } catch (Exception e) {
            return Color.argb(alpha, 54, 231, 220);
        }
    }
}
