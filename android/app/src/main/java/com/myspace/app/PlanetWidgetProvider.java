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
import android.net.Uri;
import android.widget.RemoteViews;
import java.util.TimeZone;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native home-screen widget. The WebView stores a compact catalog snapshot;
 * every appWidgetId then keeps its own selected body and time zone.
 */
public final class PlanetWidgetProvider extends AppWidgetProvider {
    static final String PREFS = "myspace_widget";
    static final String DATA = "catalog";
    private static final String ACTION_ROTATE = "com.myspace.app.action.ROTATE_WIDGET";
    static final String CONFIG_TOKEN_EXTRA = "com.myspace.app.INTERNAL_WIDGET_CONFIG_TOKEN";
    private static final String CONFIG_TOKEN_KEY = "internal_config_token";
    private static final String LEGACY_DATA = "planet";
    private static final String DEFAULT_ID = "AETHER";
    private static final String DEFAULT_NAME = "AETHER";

    static boolean isInternalConfigurationToken(Context context, String candidate) {
        return candidate != null && !candidate.isEmpty() && candidate.equals(context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CONFIG_TOKEN_KEY, ""));
    }

    private static String internalConfigurationToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(CONFIG_TOKEN_KEY, "");
        if (token.length() < 64) {
            token = UUID.randomUUID().toString() + UUID.randomUUID();
            prefs.edit().putString(CONFIG_TOKEN_KEY, token).apply();
        }
        return token;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_ROTATE.equals(intent.getAction())) {
            if (!isInternalConfigurationToken(context, intent.getStringExtra(CONFIG_TOKEN_EXTRA))) return;
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            boolean installed = false;
            for (int existing : manager.getAppWidgetIds(new ComponentName(context, PlanetWidgetProvider.class))) {
                if (existing == id) { installed = true; break; }
            }
            if (!installed) return;
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putLong(key(id, "phase"), prefs.getLong(key(id, "phase"), 0L) + 1L).apply();
            manager.updateAppWidget(id, build(context, id));
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, build(context, id));
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (int id : appWidgetIds) {
            edit.remove(key(id, "starId"));
            edit.remove(key(id, "timeZone"));
            edit.remove(key(id, "phase"));
        }
        edit.apply();
        super.onDeleted(context, appWidgetIds);
    }

    static void store(Context context, String json) {
        if (json == null || json.length() > 512 * 1024) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (json.equals(prefs.getString(DATA, null))) return;
        prefs.edit().putString(DATA, json).apply();
        updateAll(context);
    }

    static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            ComponentName provider = new ComponentName(context, PlanetWidgetProvider.class);
            int[] ids = manager.getAppWidgetIds(provider);
            if (ids == null) return;
            for (int id : ids) manager.updateAppWidget(id, build(context, id));
        } catch (Exception ignored) {
        }
    }

    static JSONArray catalog(Context context) {
        try {
            JSONObject data = new JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(DATA, "{}"));
            JSONArray stars = data.optJSONArray("stars");
            if (stars != null && stars.length() > 0) return stars;

            // Upgrade widgets created by v3.0 builds that stored only one body.
            String legacy = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(LEGACY_DATA, null);
            if (legacy != null) {
                JSONObject old = new JSONObject(legacy);
                JSONObject migrated = new JSONObject();
                migrated.put("id", old.optString("id", DEFAULT_ID));
                migrated.put("name", old.optString("name", DEFAULT_NAME));
                migrated.put("kind", old.optString("kind", "planet"));
                migrated.put("shape", old.optString("shape", "sphere"));
                migrated.put("pct", old.optInt("pct", 0));
                migrated.put("stage", old.optString("stage", ""));
                migrated.put("c1", old.optString("c1", "#62f1e6"));
                migrated.put("c2", old.optString("c2", "#177f99"));
                migrated.put("c3", old.optString("c3", "#a58bff"));
                return new JSONArray().put(migrated);
            }
        } catch (Exception ignored) {
        }
        return new JSONArray();
    }

    static String defaultStarId(Context context) {
        try {
            JSONObject data = new JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(DATA, "{}"));
            return data.optString("defaultId", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    static String selectedStarId(Context context, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(key(widgetId, "starId"), "");
        if (!saved.isEmpty()) return saved;
        String defaultId = defaultStarId(context);
        if (!defaultId.isEmpty()) return defaultId;
        JSONArray entries = catalog(context);
        return entries.length() > 0 ? entries.optJSONObject(0).optString("id", DEFAULT_ID) : DEFAULT_ID;
    }

    static String timeZone(Context context, int widgetId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(widgetId, "timeZone"), "");
    }

    static void configure(Context context, int widgetId, String starId, String timeZone) {
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        edit.putString(key(widgetId, "starId"), starId == null ? "" : starId);
        edit.putString(key(widgetId, "timeZone"), timeZone == null ? "" : timeZone);
        edit.putLong(key(widgetId, "phase"), 0L);
        edit.apply();
    }

    private static String key(int widgetId, String field) {
        return "widget." + widgetId + "." + field;
    }

    private static JSONObject findStar(Context context, String id) {
        JSONArray entries = catalog(context);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject star = entries.optJSONObject(i);
            if (star != null && id.equals(star.optString("id", ""))) return star;
        }
        return entries.length() > 0 ? entries.optJSONObject(0) : null;
    }

    static RemoteViews build(Context context, int widgetId) {
        String starId = selectedStarId(context, widgetId);
        JSONObject star = findStar(context, starId);
        if (star != null) starId = star.optString("id", starId);
        String name = star == null ? DEFAULT_NAME : star.optString("name", DEFAULT_NAME);
        if (name.length() > 60) name = name.substring(0, 60);
        int pct = star == null ? 0 : Math.max(0, Math.min(100, star.optInt("pct", 0)));
        String c1 = safeColor(star == null ? "#62f1e6" : star.optString("c1", "#62f1e6"), "#62f1e6");
        String c2 = safeColor(star == null ? "#177f99" : star.optString("c2", "#177f99"), "#177f99");
        String c3 = safeColor(star == null ? "#a58bff" : star.optString("c3", "#a58bff"), "#a58bff");
        String shape = star == null ? "sphere" : star.optString("shape", "sphere");
        String kind = star == null ? "planet" : star.optString("kind", "planet");
        String zone = timeZone(context, widgetId);
        if (!zone.isEmpty() && !isKnownTimeZone(zone)) zone = "";
        long phase = System.currentTimeMillis() / 1800000L
                + context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key(widgetId, "phase"), 0L);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.planet_widget);
        views.setImageViewBitmap(R.id.widget_planet, renderBody(c1, c2, c3, kind, shape, phase));
        views.setTextViewText(R.id.widget_name, name);
        views.setTextViewText(R.id.widget_pct, pct + "%");
        views.setTextViewText(R.id.widget_zone, zoneLabel(zone));
        views.setContentDescription(R.id.widget_planet,
                name + "، " + ("star".equals(kind) ? "نجم" : "كوكب") + "، المس التفاعل معه داخل التطبيق");
        views.setProgressBar(R.id.widget_progress, 100, pct, false);
        // TextClock runs natively while visible and supports an explicit zone via RemoteViews.
        views.setString(R.id.widget_clock, "setTimeZone", zone.isEmpty() ? null : zone);

        Intent open = new Intent(context, MainActivity.class);
        open.putExtra(MainActivity.EXTRA_WIDGET_STAR_ID, starId);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.setData(Uri.parse("myspace://widget/open/" + widgetId));
        PendingIntent openPending = PendingIntent.getActivity(context, 300000 + widgetId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPending);
        views.setOnClickPendingIntent(R.id.widget_planet, openPending);

        Intent rotate = new Intent(context, PlanetWidgetProvider.class);
        rotate.setAction(ACTION_ROTATE);
        rotate.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        rotate.putExtra(CONFIG_TOKEN_EXTRA, internalConfigurationToken(context));
        rotate.setData(Uri.parse("myspace://widget/rotate/" + widgetId));
        PendingIntent rotatePending = PendingIntent.getBroadcast(context, 500000 + widgetId, rotate,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_rotate, rotatePending);

        Intent configure = new Intent(context, WidgetConfigureActivity.class);
        configure.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        configure.putExtra(CONFIG_TOKEN_EXTRA, internalConfigurationToken(context));
        configure.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        configure.setData(Uri.parse("myspace://widget/configure/" + widgetId));
        PendingIntent configurePending = PendingIntent.getActivity(context, 400000 + widgetId, configure,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_config, configurePending);
        return views;
    }

    static String zoneLabel(String zone) {
        if (zone == null || zone.isEmpty()) return "توقيت الجهاز";
        switch (zone) {
            case "Asia/Riyadh": return "الرياض";
            case "Africa/Cairo": return "القاهرة";
            case "Asia/Dubai": return "دبي";
            case "Europe/London": return "لندن";
            case "America/New_York": return "نيويورك";
            case "Asia/Tokyo": return "طوكيو";
            case "Europe/Paris": return "باريس";
            default: return zone;
        }
    }

    private static boolean isKnownTimeZone(String zone) {
        for (String candidate : TimeZone.getAvailableIDs()) if (candidate.equals(zone)) return true;
        return false;
    }

    static String safeColor(String value, String fallback) {
        try {
            Color.parseColor(value);
            return value;
        } catch (Exception e) {
            return fallback;
        }
    }

    static Bitmap renderBody(String c1, String c2, String c3, String kind, String shape, long phase) {
        final int size = 256;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float cx = size / 2f, cy = size / 2f;
        float radius = size * 0.235f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float phaseAngle = (phase % 24L) * 15f;
        boolean stellar = "star".equals(kind) || "star".equals(shape)
                || "supernova".equals(shape) || "pulsar".equals(shape) || "binary".equals(shape);

        // Deep space backdrop and a softly breathing colored halo.
        paint.setColor(Color.rgb(3, 8, 14));
        canvas.drawColor(Color.TRANSPARENT);
        paint.setShader(new RadialGradient(cx, cy, size * 0.49f,
                new int[]{withAlpha(c1, 44), withAlpha(c2, 12), Color.TRANSPARENT},
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, size * 0.49f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(c3, 150));
        float[][] stars = {{.13f,.20f},{.83f,.18f},{.18f,.78f},{.84f,.77f},{.48f,.09f},{.91f,.52f},{.34f,.91f}};
        for (int i = 0; i < stars.length; i++) {
            float r = size * (i % 3 == 0 ? .012f : .007f);
            canvas.drawCircle(stars[i][0] * size, stars[i][1] * size, r, paint);
        }

        if (stellar) {
            // Radial flares rotate when the widget is refreshed; the main star remains legible.
            canvas.save();
            canvas.rotate(phaseAngle * 0.45f, cx, cy);
            for (int i = 0; i < 12; i++) {
                double a = Math.PI * 2d * i / 12d;
                float inner = radius * (0.82f + (i % 2) * .08f);
                float outer = radius * (1.27f + (i % 3) * .10f);
                paint.setColor(withAlpha(i % 2 == 0 ? c1 : c3, 100 + (i % 3) * 26));
                paint.setStrokeWidth(size * (i % 3 == 0 ? .013f : .008f));
                canvas.drawLine(cx + (float)Math.cos(a) * inner, cy + (float)Math.sin(a) * inner,
                        cx + (float)Math.cos(a) * outer, cy + (float)Math.sin(a) * outer, paint);
            }
            canvas.restore();
            paint.setShader(new RadialGradient(cx - radius * .30f, cy - radius * .34f,
                    radius * 1.45f,
                    new int[]{Color.WHITE, Color.parseColor(c1), Color.parseColor(c2), Color.parseColor("#120b24")},
                    new float[]{0f, .28f, .72f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius * .92f, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * .008f);
            paint.setColor(withAlpha(c3, 110));
            canvas.drawCircle(cx, cy, radius * 1.08f, paint);
        } else {
            // Fine orbital rings move between widget refreshes; surface bands are clipped to the globe.
            paint.setStyle(Paint.Style.STROKE);
            canvas.save();
            canvas.rotate(-18f + phaseAngle, cx, cy);
            for (int i = 0; i < 3; i++) {
                paint.setStrokeWidth(size * (i == 1 ? .009f : .006f));
                paint.setColor(withAlpha(i == 1 ? c1 : c3, i == 0 ? 90 : 155));
                float orbit = radius * (1.42f + i * .14f);
                canvas.drawOval(cx - orbit, cy - orbit * .30f, cx + orbit, cy + orbit * .30f, paint);
            }
            canvas.restore();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx - radius * .34f, cy - radius * .36f,
                    radius * 1.48f,
                    new int[]{Color.parseColor(c1), Color.parseColor(c2), Color.parseColor("#03070a")},
                    new float[]{0f, .62f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, paint);
            canvas.save();
            Path globeMask = new Path();
            globeMask.addCircle(cx, cy, radius * .995f, Path.Direction.CW);
            canvas.clipPath(globeMask);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            canvas.rotate(phaseAngle * .28f, cx, cy);
            for (int i = 0; i < 7; i++) {
                float offset = (i - 3) * radius * .235f;
                float wave = radius * (.035f + (i % 2) * .018f);
                Path band = new Path();
                band.moveTo(cx - radius, cy + offset);
                band.cubicTo(cx - radius * .42f, cy + offset - wave,
                        cx + radius * .28f, cy + offset + wave,
                        cx + radius, cy + offset - wave * .28f);
                paint.setColor(withAlpha(i % 2 == 0 ? c1 : c3, 26 + (i % 3) * 12));
                paint.setStrokeWidth(size * (i % 3 == 0 ? .009f : .006f));
                canvas.drawPath(band, paint);
            }
            canvas.restore();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx + radius * .54f, cy + radius * .42f,
                    radius * 1.25f, new int[]{Color.TRANSPARENT, 0x77000000}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * .006f);
            paint.setColor(withAlpha(c1, 155));
            canvas.drawArc(cx - radius * 1.48f, cy - radius * .30f,
                    cx + radius * 1.48f, cy + radius * .30f, 190f + phaseAngle, 155f, false, paint);
        }
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
