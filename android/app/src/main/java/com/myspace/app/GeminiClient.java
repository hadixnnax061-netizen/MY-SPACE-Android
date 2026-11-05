package com.myspace.app;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * Direct, dependency-free client for the Gemini REST API. The API key is only
 * ever used here (never exposed to the WebView).
 */
final class GeminiClient {
    static final String DEFAULT_MODEL = "gemini-3.8-flash";
    static final String DEFAULT_TTS_MODEL = "gemini-3.8-flash-tts";
    static final String FALLBACK_TTS_MODEL = "gemini-2.5-flash-preview-tts";

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";
    private static final String[] CHAT_FALLBACKS = {"gemini-2.5-flash", "gemini-2.0-flash"};
    private static final int MAX_RESPONSE_BYTES = 24 * 1024 * 1024;

    private volatile String chatModel = DEFAULT_MODEL;

    String chatModel() {
        return chatModel;
    }

    JSONObject generateChat(String apiKey, JSONObject body) throws ApiException, JSONException {
        List<String> models = new ArrayList<>();
        models.add(chatModel);
        for (String fallback : CHAT_FALLBACKS) {
            if (!models.contains(fallback)) models.add(fallback);
        }
        ApiException last = null;
        for (String model : models) {
            try {
                JSONObject data = post("models/" + model + ":generateContent", body, apiKey, 75000);
                chatModel = model;
                return data;
            } catch (ApiException e) {
                last = e;
                if (!isMissingModel(e)) throw e;
            }
        }
        throw last != null ? last : new ApiException(0, "تعذّر الاتصال بـ Gemini.");
    }

    /**
     * Mirrors the original bridge: Gemini 3.8 "interactions" speech endpoint.
     * If that endpoint or model is unavailable, falls back to the standard
     * generateContent TTS contract and wraps raw PCM in a WAV container so
     * WebView's <audio> element can play it.
     */
    JSONObject synthesizeSpeech(String apiKey, String spoken, boolean planet) throws ApiException, JSONException {
        String voice = planet ? "Kore" : "Sulafat";
        String style = planet
                ? "بصوت عربي عميق وواضح ومتأمل، وبسرعة طبيعية"
                : "بصوت عربي واضح ودافئ، هادئ ومطمئن، وبسرعة طبيعية";
        String prompt = "اقرأ النص التالي باللغة العربية " + style
                + ". انطق النص كما هو دون إضافة أو حذف:\n\n" + spoken;

        ApiException interactionsError = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", DEFAULT_TTS_MODEL);
            body.put("input", prompt);
            body.put("response_format", new JSONObject().put("type", "audio"));
            JSONObject generation = new JSONObject();
            generation.put("speech_config", new JSONArray().put(
                    new JSONObject().put("voice", voice)));
            body.put("generation_config", generation);

            JSONObject data = post("interactions", body, apiKey, 100000);
            JSONObject audio = data.optJSONObject("output_audio");
            if (audio != null && !audio.optString("data").isEmpty()) {
                return audioPayload(audio.optString("data"),
                        audio.optString("mime_type", "audio/wav"), voice);
            }
            interactionsError = new ApiException(502, "لم يُرجع Gemini ملفاً صوتياً. جرّب إيقاف خيار الصوت أو أعد المحاولة.");
        } catch (ApiException e) {
            interactionsError = e;
        }

        try {
            JSONObject body = new JSONObject();
            body.put("contents", new JSONArray().put(new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))));
            JSONObject generation = new JSONObject();
            generation.put("responseModalities", new JSONArray().put("AUDIO"));
            generation.put("speechConfig", new JSONObject().put("voiceConfig",
                    new JSONObject().put("prebuiltVoiceConfig",
                            new JSONObject().put("voiceName", voice))));
            body.put("generationConfig", generation);

            JSONObject data = post("models/" + FALLBACK_TTS_MODEL + ":generateContent",
                    body, apiKey, 100000);
            JSONArray candidates = data.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                JSONArray parts = content == null ? null : content.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject inline = parts.getJSONObject(i).optJSONObject("inlineData");
                        if (inline == null) {
                            inline = parts.getJSONObject(i).optJSONObject("inline_data");
                        }
                        if (inline != null && !inline.optString("data").isEmpty()) {
                            return audioPayload(inline.optString("data"),
                                    inline.optString("mimeType",
                                            inline.optString("mime_type", "audio/wav")), voice);
                        }
                    }
                }
            }
            throw new ApiException(502, "لم يُرجع Gemini ملفاً صوتياً. جرّب إيقاف خيار الصوت أو أعد المحاولة.");
        } catch (ApiException fallbackError) {
            if (interactionsError != null && fallbackError.httpStatus == 404) {
                throw interactionsError;
            }
            throw fallbackError;
        }
    }

    private JSONObject audioPayload(String base64, String mime, String voice) throws ApiException, JSONException {
        byte[] bytes;
        try {
            bytes = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new ApiException(502, "وصل ملف صوتي غير صالح من Gemini.");
        }
        String type = mime == null || mime.isEmpty() ? "audio/wav" : mime;
        String lower = type.toLowerCase(Locale.ROOT);
        if (lower.contains("l16") || lower.contains("pcm") || lower.contains("raw")) {
            int rate = 24000;
            Matcher matcher = Pattern.compile("rate=(\\d+)").matcher(lower);
            if (matcher.find()) {
                try {
                    rate = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            bytes = wrapPcmAsWav(bytes, rate, 1, 16);
            type = "audio/wav";
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("audioBase64", Base64.encodeToString(bytes, Base64.NO_WRAP));
        result.put("mimeType", type);
        result.put("voice", voice);
        return result;
    }

    private static byte[] wrapPcmAsWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataLength = pcm.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLength);
        writeAscii(out, "RIFF");
        writeInt(out, 36 + dataLength);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeInt(out, 16);
        writeShort(out, 1);                 // PCM
        writeShort(out, channels);
        writeInt(out, sampleRate);
        writeInt(out, byteRate);
        writeShort(out, blockAlign);
        writeShort(out, bitsPerSample);
        writeAscii(out, "data");
        writeInt(out, dataLength);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private JSONObject post(String path, JSONObject body, String apiKey, int readTimeout)
            throws ApiException {
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(BASE + path);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(readTimeout);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-goog-api-key", apiKey);
            connection.setRequestProperty("Accept", "application/json");

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String text = readAll(stream);
            JSONObject json = parse(text);
            if (code < 200 || code >= 300) {
                String message = "";
                JSONObject error = json.optJSONObject("error");
                if (error != null) message = error.optString("message");
                if (message == null) message = "";
                throw new ApiException(code, message.trim());
            }
            return json;
        } catch (ApiException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new ApiException(0, "انتهت مهلة الاتصال بـ Google. تحقق من الإنترنت ثم أعد المحاولة.");
        } catch (IOException e) {
            throw new ApiException(0, "تعذّر الوصول إلى Google. تحقق من اتصال الإنترنت ثم أعد المحاولة.");
        } catch (JSONException e) {
            throw new ApiException(0, "وصل رد غير مفهوم من Google. أعد المحاولة.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject parse(String text) throws JSONException {
        if (text == null || text.trim().isEmpty()) return new JSONObject();
        return new JSONObject(text);
    }

    private static String readAll(InputStream stream) throws IOException, ApiException {
        if (stream == null) return "";
        try (InputStream input = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new ApiException(502, "رد Google أكبر من الحد المسموح.");
                }
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isMissingModel(ApiException e) {
        if (e.httpStatus == 404) return true;
        String message = e.rawMessage == null ? "" : e.rawMessage.toLowerCase(Locale.ROOT);
        return e.httpStatus == 400
                && message.contains("model")
                && (message.contains("not found") || message.contains("not supported")
                    || message.contains("unknown") || message.contains("does not exist"));
    }

    static final class ApiException extends Exception {
        final int httpStatus;
        final String rawMessage;

        ApiException(int httpStatus, String rawMessage) {
            super(rawMessage);
            this.httpStatus = httpStatus;
            this.rawMessage = rawMessage == null ? "" : rawMessage;
        }

        String userMessage() {
            String detail = rawMessage;
            if (detail.length() > 260) detail = detail.substring(0, 260);
            if (httpStatus == 0) {
                return detail.isEmpty() ? "تعذّر الوصول إلى Google." : detail;
            }
            String prefix;
            if (httpStatus == 400) prefix = "طلب غير صالح إلى Gemini";
            else if (httpStatus == 401 || httpStatus == 403)
                prefix = "مفتاح Gemini غير صالح أو لا يملك صلاحية الوصول";
            else if (httpStatus == 429)
                prefix = "تم بلوغ حد الاستخدام أو الحصة المتاحة في Gemini";
            else if (httpStatus >= 500) prefix = "خدمة Google غير متاحة مؤقتاً";
            else prefix = "خطأ Google (" + httpStatus + ")";
            return detail.isEmpty() ? prefix : prefix + ": " + detail;
        }
    }
}
