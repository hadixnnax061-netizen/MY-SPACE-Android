package com.myspace.app;

import android.app.Activity;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JavaScript bridge that replaces the local Python bridge used by the PWA.
 * Every public method is asynchronous: it returns immediately and resolves the
 * page-side promise through window.__myspaceNativeResolve(id, envelope).
 */
public final class GeminiBridge {
    private static final String TAG = "MySpaceBridge";
    private static final int MAX_PAYLOAD_CHARS = 160 * 1024;
    private static final int MAX_SPEECH_CHARS = 1800;

    private final MainActivity activity;
    private final WebView webView;
    private final SecureKeyStore keyStore;
    private final GeminiClient client = new GeminiClient();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    GeminiBridge(MainActivity activity, WebView webView, SecureKeyStore keyStore) {
        this.activity = activity;
        this.webView = webView;
        this.keyStore = keyStore;
    }

    void shutdown() {
        executor.shutdownNow();
    }

    @JavascriptInterface
    public void status(String id) {
        execute(id, () -> {
            JSONObject body = new JSONObject();
            body.put("configured", keyStore.isConfigured());
            body.put("model", client.chatModel());
            body.put("voiceModel", GeminiClient.DEFAULT_TTS_MODEL);
            return Envelope.ok(body);
        });
    }

    @JavascriptInterface
    public void saveKey(String id, String payload) {
        execute(id, () -> {
            JSONObject input = parseObject(payload);
            String key = input.optString("apiKey", "").trim();
            if (key.length() < 20 || key.length() > 256) {
                throw new GeminiClient.ApiException(400,
                        "صيغة المفتاح غير صالحة. تأكد من نسخه كاملاً دون مسافات أو أسطر جديدة.");
            }
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if (c < 33 || c > 126) {
                    throw new GeminiClient.ApiException(400,
                            "صيغة المفتاح غير صالحة. تأكد من نسخه كاملاً دون مسافات أو أسطر جديدة.");
                }
            }
            try {
                keyStore.save(key);
            } catch (Exception e) {
                Log.e(TAG, "Unable to store key", e);
                throw new GeminiClient.ApiException(500,
                        "تعذّر حفظ المفتاح بأمان على هذا الجهاز. أعد المحاولة.");
            }
            JSONObject body = new JSONObject();
            body.put("ok", true);
            body.put("configured", true);
            body.put("message", "تم حفظ المفتاح مشفّراً على هذا الجهاز.");
            return Envelope.ok(body);
        });
    }

    @JavascriptInterface
    public void test(String id, String payload) {
        execute(id, () -> {
            String key = requireKey();
            JSONObject request = new JSONObject();
            request.put("contents", new JSONArray().put(new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject()
                            .put("text", "Reply in Arabic with exactly: الاتصال يعمل")))));
            request.put("generationConfig", new JSONObject().put("maxOutputTokens", 40));
            JSONObject data = client.generateChat(key, request);
            String text = extractText(data);
            JSONObject body = new JSONObject();
            body.put("ok", true);
            body.put("model", client.chatModel());
            body.put("text", text.isEmpty() ? "نجح الطلب" : text);
            return Envelope.ok(body);
        });
    }

    @JavascriptInterface
    public void chat(String id, String payload) {
        execute(id, () -> {
            String key = requireKey();
            JSONObject input = parseObject(payload);
            JSONArray messages = normaliseMessages(input.optJSONArray("messages"));
            if (messages.length() == 0
                    || !"user".equals(messages.getJSONObject(messages.length() - 1).optString("role"))) {
                throw new GeminiClient.ApiException(400,
                        "لم يتم العثور على رسالة مستخدم صالحة.");
            }
            JSONObject context = cleanContext(input.optJSONObject("context"));

            JSONArray contents = new JSONArray();
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.getJSONObject(i);
                contents.put(new JSONObject()
                        .put("role", message.optString("role"))
                        .put("parts", new JSONArray().put(new JSONObject()
                                .put("text", message.optString("text")))));
            }
            JSONObject request = new JSONObject();
            request.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject()
                            .put("text", systemInstruction(context)))));
            request.put("contents", contents);
            request.put("generationConfig", new JSONObject().put("maxOutputTokens", 800));

            JSONObject data = client.generateChat(key, request);
            String text = extractText(data);
            if (text.isEmpty()) {
                String reason = finishReason(data);
                throw new GeminiClient.ApiException(502,
                        ("لم يُنتج Gemini إجابة نصية. " + reason).trim());
            }
            JSONObject body = new JSONObject();
            body.put("ok", true);
            body.put("model", client.chatModel());
            body.put("text", text);
            return Envelope.ok(body);
        });
    }

    @JavascriptInterface
    public void speech(String id, String payload) {
        execute(id, () -> {
            String key = requireKey();
            JSONObject input = parseObject(payload);
            String text = input.optString("text", "").trim();
            if (text.isEmpty()) {
                throw new GeminiClient.ApiException(400, "لا يوجد نص لتحويله إلى صوت.");
            }
            if (text.length() > MAX_SPEECH_CHARS) text = text.substring(0, MAX_SPEECH_CHARS);
            boolean planet = "planet".equals(input.optString("character"));
            return Envelope.ok(client.synthesizeSpeech(key, text, planet));
        });
    }

    @JavascriptInterface
    public void saveBlob(String name, String mime, String base64) {
        activity.runOnUiThread(() -> activity.saveBlob(name, mime, base64));
    }

    @JavascriptInterface
    public void copyText(String text) {
        activity.runOnUiThread(() -> activity.copyText(text));
    }

    @JavascriptInterface
    public void openUrl(String url) {
        activity.runOnUiThread(() -> activity.openExternalUrl(url));
    }

    @JavascriptInterface
    public void pinPlanetWidget() {
        activity.runOnUiThread(() -> activity.requestPlanetWidget());
    }

    @JavascriptInterface
    public void widgetData(String payload) {
        try {
            JSONObject input = new JSONObject(payload == null ? "{}" : payload);
            JSONObject clean = new JSONObject();
            String name = input.optString("name", "MY SPACE");
            if (name.length() > 60) name = name.substring(0, 60);
            clean.put("name", name);
            clean.put("pct", Math.max(0, Math.min(100, input.optInt("pct", 0))));
            clean.put("stage", input.optString("stage", "").substring(0,
                    Math.min(40, input.optString("stage", "").length())));
            clean.put("c1", input.optString("c1", "#36e7dc"));
            clean.put("c2", input.optString("c2", "#008080"));
            clean.put("c3", input.optString("c3", "#e0b252"));
            clean.put("shape", input.optString("shape", "sphere"));
            PlanetWidgetProvider.store(activity, clean.toString());
        } catch (Exception ignored) {
        }
    }

    private String requireKey() throws GeminiClient.ApiException {
        String key = keyStore.load();
        if (key.isEmpty()) {
            throw new GeminiClient.ApiException(0,
                    "لم يُضبط مفتاح Gemini بعد. افتح الإعدادات والصق المفتاح ثم أعد المحاولة.");
        }
        return key;
    }

    private void execute(String id, Task task) {
        if (id == null || id.isEmpty() || id.length() > 80) return;
        executor.execute(() -> {
            Envelope envelope;
            try {
                envelope = task.run();
            } catch (GeminiClient.ApiException e) {
                envelope = Envelope.error(mapStatus(e), e.userMessage());
            } catch (Throwable t) {
                Log.e(TAG, "Bridge task failed", t);
                envelope = Envelope.error(500, "حدث خطأ داخلي في التطبيق. أعد المحاولة.");
            }
            deliver(id, envelope);
        });
    }

    private void deliver(String id, Envelope envelope) {
        final String js = "window.__myspaceNativeResolve("
                + JSONObject.quote(id) + "," + JSONObject.quote(envelope.toJson()) + ")";
        activity.runOnUiThread(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Exception e) {
                Log.w(TAG, "Unable to deliver bridge response", e);
            }
        });
    }

    private static int mapStatus(GeminiClient.ApiException e) {
        if (e.httpStatus == 0) return 503;
        if (e.httpStatus == 400) return 400;
        if (e.httpStatus == 429) return 429;
        return 502;
    }

    private static JSONObject parseObject(String payload) throws JSONException {
        if (payload == null || payload.trim().isEmpty()) return new JSONObject();
        if (payload.length() > MAX_PAYLOAD_CHARS) {
            throw new JSONException("payload too large");
        }
        return new JSONObject(payload);
    }

    private static JSONArray normaliseMessages(JSONArray raw) throws JSONException {
        List<String[]> messages = new ArrayList<>();
        if (raw != null) {
            int start = Math.max(0, raw.length() - 18);
            for (int i = start; i < raw.length(); i++) {
                JSONObject item = raw.optJSONObject(i);
                if (item == null) continue;
                String rawRole = item.optString("role");
                String role = "user".equals(rawRole) ? "user"
                        : "model".equals(rawRole) ? "model" : null;
                if (role == null) continue;
                String text = item.optString("text", "").trim();
                if (text.isEmpty()) continue;
                if (text.length() > 1800) text = text.substring(0, 1800);
                if (!messages.isEmpty() && messages.get(messages.size() - 1)[0].equals(role)) {
                    String merged = messages.get(messages.size() - 1)[1] + "\n" + text;
                    if (merged.length() > 3600) merged = merged.substring(merged.length() - 3600);
                    messages.set(messages.size() - 1, new String[]{role, merged});
                } else {
                    messages.add(new String[]{role, text});
                }
            }
        }
        while (!messages.isEmpty() && !"user".equals(messages.get(0)[0])) {
            messages.remove(0);
        }
        JSONArray result = new JSONArray();
        for (String[] message : messages) {
            result.put(new JSONObject().put("role", message[0]).put("text", message[1]));
        }
        return result;
    }

    private static JSONObject cleanContext(JSONObject input) throws JSONException {
        JSONObject result = new JSONObject();
        if (input == null) return result;
        String[] allowed = {"character", "name", "type", "shape", "stage", "progress",
                "attributes", "mood", "relationship", "interactions", "completedTasks",
                "tasks", "recentLogs", "memories", "userStyle"};
        for (String key : allowed) {
            if (!input.has(key)) continue;
            Object value = input.opt(key);
            if (value instanceof String) {
                String text = (String) value;
                if (text.length() > 220) text = text.substring(0, 220);
                result.put(key, text);
            } else if (value instanceof Number || value instanceof Boolean) {
                result.put(key, value);
            } else if (value == JSONObject.NULL) {
                result.put(key, JSONObject.NULL);
            } else if (value instanceof JSONObject) {
                result.put(key, new JSONObject(value.toString()));
            } else if (value instanceof JSONArray) {
                result.put(key, new JSONArray(value.toString()));
            }
        }
        return result;
    }

    /** Exact prompt used by the original Python bridge, kept for behaviour parity. */
    private static String systemInstruction(JSONObject context) {
        boolean planet = "planet".equals(context.optString("character"));
        String kind = planet ? "الكوكب" : "النجم";
        String name = context.optString("name", "MY SPACE");
        if (name.length() > 64) name = name.substring(0, 64);
        String persona = "أنت " + kind + " " + name + " داخل تطبيق MY SPACE. "
                + (planet
                    ? "تحدث كشخصية كوكبية متأملة وفضولية، واستعمل صور الطبقات والجاذبية والمدارات باعتدال. "
                    : "تحدث كشخصية نجمية دافئة وذكية، وشجّع المستخدم من دون مبالغة أو تملّق. ");
        return persona
                + "أنت شخصية رقمية خيالية وليست كائناً حياً أو إنساناً. لا تدّع الوعي أو المشاعر البشرية الحقيقية؛ يمكنك التعبير بأسلوب أدبي عن الشخصية مع الصدق عند السؤال المباشر. "
                + "أجب بالعربية الواضحة القريبة من المستخدم ما لم يطلب لغة أخرى. كن محادثياً وطبيعياً، وأجب عن مضمون السؤال فعلاً، ولا تجعل كل رد شعراً. "
                + "استخدم سياق التطبيق أدناه باعتباره بيانات موثوقة عن النجم فقط. لا تخترع إنجازات أو ذكريات أو معلومات عن المستخدم. إذا لم تجد معلومة فقل إنك لا تعرفها. "
                + "لا تزعم أنك عدّلت المهام أو التصميم بنفسك؛ يمكنك اقتراح التغيير وشرح طريقة تنفيذه. لا تطلب كلمة مرور أو مفتاح API. "
                + "اجعل الرد مناسباً للمحادثة، غالباً من فقرة إلى ثلاث فقرات، واسأل سؤال متابعة واحداً عندما يكون مفيداً.\n\n"
                + "سياق MY SPACE الحالي (JSON):\n" + context;
    }

    private static String extractText(JSONObject data) {
        try {
            JSONArray candidates = data.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) return "";
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            if (content == null) return "";
            JSONArray parts = content.optJSONArray("parts");
            if (parts == null) return "";
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                String value = part.optString("text", "");
                if (value.isEmpty()) continue;
                if (text.length() > 0) text.append('\n');
                text.append(value);
            }
            return text.toString().trim();
        } catch (JSONException e) {
            return "";
        }
    }

    private static String finishReason(JSONObject data) {
        try {
            JSONArray candidates = data.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                return candidates.getJSONObject(0).optString("finishReason", "");
            }
        } catch (JSONException ignored) {
        }
        return "";
    }

    private interface Task {
        Envelope run() throws Exception;
    }

    private static final class Envelope {
        final int status;
        final JSONObject body;

        private Envelope(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }

        static Envelope ok(JSONObject body) {
            return new Envelope(200, body);
        }

        static Envelope error(int status, String message) {
            JSONObject body = new JSONObject();
            try {
                body.put("error", message);
            } catch (JSONException ignored) {
            }
            return new Envelope(status, body);
        }

        String toJson() {
            JSONObject envelope = new JSONObject();
            try {
                envelope.put("status", status);
                envelope.put("body", body);
                return envelope.toString();
            } catch (JSONException e) {
                return "{\"status\":500,\"body\":{\"error\":\"تعذّر تجهيز الرد.\"}}";
            }
        }
    }
}
