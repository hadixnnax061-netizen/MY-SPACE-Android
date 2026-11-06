package com.myspace.app;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Configuration UI shown by Android once for each newly added widget instance. */
public final class WidgetConfigureActivity extends Activity {
    private static final String[] ZONE_LABELS = {
            "توقيت الجهاز", "الرياض", "القاهرة", "دبي", "لندن", "باريس", "نيويورك", "طوكيو"
    };
    private static final String[] ZONE_IDS = {
            "", "Asia/Riyadh", "Africa/Cairo", "Asia/Dubai", "Europe/London",
            "Europe/Paris", "America/New_York", "Asia/Tokyo"
    };

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private JSONArray entries;
    private Spinner bodySpinner;
    private Spinner zoneSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || !isManagedWidgetId(widgetId)
                || !isAuthorizedConfigurationRequest()) {
            finish();
            return;
        }

        entries = PlanetWidgetProvider.catalog(this);
        boolean needsSync = entries.length() == 0;
        if (needsSync) entries = fallbackCatalog();
        setTitle("إعداد ويدجت MY SPACE");
        setContentView(createContent(needsSync));
    }

    private boolean isManagedWidgetId(int id) {
        int[] ids = AppWidgetManager.getInstance(this)
                .getAppWidgetIds(new ComponentName(this, PlanetWidgetProvider.class));
        for (int registeredId : ids) if (registeredId == id) return true;
        return false;
    }

    private boolean isAuthorizedConfigurationRequest() {
        Intent intent = getIntent();
        String token = intent == null ? null
                : intent.getStringExtra(PlanetWidgetProvider.CONFIG_TOKEN_EXTRA);
        if (PlanetWidgetProvider.isInternalConfigurationToken(this, token)) return true;
        if (intent == null || !AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(intent.getAction())) return false;
        String caller = getCallingPackage();
        return caller != null && getPackageManager().checkPermission(
                "android.permission.BIND_APPWIDGET", caller) == PackageManager.PERMISSION_GRANTED;
    }

    private JSONArray fallbackCatalog() {
        JSONArray result = new JSONArray();
        try {
            result.put(new JSONObject()
                    .put("id", "AETHER")
                    .put("name", "AETHER")
                    .put("kind", "planet")
                    .put("shape", "sphere")
                    .put("pct", 0));
        } catch (Exception ignored) {
        }
        return result;
    }

    private ScrollView createContent(boolean needsSync) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(22), dp(22), dp(26));
        panel.setBackgroundResource(R.drawable.widget_bg);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("خصص ويدجت الجرم", 23, Color.rgb(237, 245, 248), true);
        panel.addView(title, matchWrap());
        TextView intro = text("اختر الكوكب أو النجم الذي ستعرضه هذه النسخة. يمكنك وضع عدة ودجات، ولكل واحدة جرمها وتوقيتها.",
                14, Color.rgb(151, 179, 187), false);
        intro.setPadding(0, dp(8), 0, dp(18));
        panel.addView(intro, matchWrap());

        panel.addView(text("الجرم", 13, Color.rgb(98, 241, 230), true), matchWrap());
        bodySpinner = new Spinner(this);
        String[] bodyLabels = new String[entries.length()];
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            String name = item == null ? "AETHER" : item.optString("name", "AETHER");
            String kind = item == null ? "planet" : item.optString("kind", "planet");
            int pct = item == null ? 0 : Math.max(0, Math.min(100, item.optInt("pct", 0)));
            bodyLabels[i] = name + ("star".equals(kind) ? " · نجم" : " · كوكب") + " · " + pct + "%";
        }
        ArrayAdapter<String> bodyAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, bodyLabels);
        bodyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bodySpinner.setAdapter(bodyAdapter);
        bodySpinner.setContentDescription("اختر الكوكب أو النجم للودجت");
        bodySpinner.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(54, 231, 220)));
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(6);
        bodyParams.bottomMargin = dp(18);
        panel.addView(bodySpinner, bodyParams);

        if (needsSync) {
            TextView syncHint = text("افتح التطبيق مرة واحدة لمزامنة أجرامك؛ يمكنك إعادة اختيارها من زر الإعدادات على الودجت.",
                    12, Color.rgb(190, 164, 105), false);
            syncHint.setPadding(0, 0, 0, dp(14));
            panel.addView(syncHint, matchWrap());
        }

        panel.addView(text("المنطقة الزمنية للساعة", 13, Color.rgb(98, 241, 230), true), matchWrap());
        zoneSpinner = new Spinner(this);
        ArrayAdapter<String> zoneAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, ZONE_LABELS);
        zoneAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        zoneSpinner.setAdapter(zoneAdapter);
        zoneSpinner.setContentDescription("اختر المنطقة الزمنية التي تعرضها الساعة");
        zoneSpinner.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(54, 231, 220)));
        LinearLayout.LayoutParams zoneParams = matchWrap();
        zoneParams.topMargin = dp(6);
        zoneParams.bottomMargin = dp(24);
        panel.addView(zoneSpinner, zoneParams);

        selectSavedValues();

        Button save = new Button(this);
        save.setText("حفظ وإضافة الودجت");
        save.setTextColor(Color.rgb(3, 15, 19));
        save.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(98, 241, 230)));
        save.setAllCaps(false);
        save.setOnClickListener(v -> saveConfiguration());
        LinearLayout.LayoutParams saveParams = matchWrap();
        saveParams.height = dp(52);
        panel.addView(save, saveParams);

        TextView hint = text("المس صورة الجرم لفتحه تفاعليًا داخل التطبيق. الساعة تتحدث مباشرة على الشاشة الرئيسية.",
                12, Color.rgb(151, 179, 187), false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(14), 0, 0);
        panel.addView(hint, matchWrap());
        return scroll;
    }

    private void selectSavedValues() {
        String savedId = PlanetWidgetProvider.selectedStarId(this, widgetId);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item != null && savedId.equals(item.optString("id", ""))) {
                bodySpinner.setSelection(i);
                break;
            }
        }
        String savedZone = PlanetWidgetProvider.timeZone(this, widgetId);
        for (int i = 0; i < ZONE_IDS.length; i++) {
            if (savedZone.equals(ZONE_IDS[i])) {
                zoneSpinner.setSelection(i);
                break;
            }
        }
    }

    private void saveConfiguration() {
        JSONObject selected = entries.optJSONObject(bodySpinner.getSelectedItemPosition());
        String starId = selected == null ? "AETHER" : selected.optString("id", "AETHER");
        String zoneId = ZONE_IDS[Math.max(0, Math.min(ZONE_IDS.length - 1,
                zoneSpinner.getSelectedItemPosition()))];
        PlanetWidgetProvider.configure(this, widgetId, starId, zoneId);
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        manager.updateAppWidget(widgetId, PlanetWidgetProvider.build(this, widgetId));
        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
