#!/usr/bin/env python3
"""MY SPACE Gemini bridge — Python standard library only."""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
import os, json, base64, mimetypes, socket, ipaddress
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent
DEFAULT_MODEL = "gemini-3.8-flash"
TTS_MODEL = "gemini-3.8-flash-tts"
MAX_BODY = 48 * 1024


def load_dotenv():
    env_file = ROOT / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def env_model():
    model = os.getenv("GEMINI_MODEL", DEFAULT_MODEL).strip()
    # Keep model identifiers constrained to a safe Google model-id format.
    if not model or len(model) > 100 or any(ch not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-" for ch in model):
        return DEFAULT_MODEL
    return model


def current_api_key():
    key = os.getenv("GEMINI_API_KEY", "").strip()
    if not key or key in ("ضع_مفتاحك_هنا", "YOUR_API_KEY", "PASTE_API_KEY_HERE"):
        return ""
    return key


def send_google(path, payload, timeout=75):
    key = current_api_key()
    if not key:
        raise RuntimeError("لم يُضبط GEMINI_API_KEY بعد. الصق المفتاح من إعدادات التطبيق.")
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = Request(
        "https://generativelanguage.googleapis.com/v1beta/" + path,
        data=data,
        headers={"Content-Type": "application/json", "x-goog-api-key": key},
        method="POST",
    )
    try:
        with urlopen(req, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        try:
            detail = json.loads(exc.read().decode("utf-8"))
            message = detail.get("error", {}).get("message", "رفض Google الطلب")
        except Exception:
            message = "رفض Google الطلب"
        if exc.code == 400:
            prefix = "طلب غير صالح إلى Gemini"
        elif exc.code in (401, 403):
            prefix = "مفتاح Gemini غير صالح أو لا يملك صلاحية الوصول"
        elif exc.code == 429:
            prefix = "تم بلوغ حد الاستخدام أو الحصة المتاحة في Gemini"
        elif exc.code >= 500:
            prefix = "خدمة Google غير متاحة مؤقتاً"
        else:
            prefix = f"خطأ Google ({exc.code})"
        raise RuntimeError(f"{prefix}: {str(message)[:220]}") from None
    except (URLError, TimeoutError, socket.timeout):
        raise RuntimeError("تعذّر الوصول إلى Google. تحقق من اتصال الإنترنت ثم أعد المحاولة.") from None


def clean_context(value):
    if not isinstance(value, dict):
        return {}
    allowed = ("character", "name", "type", "shape", "stage", "progress", "attributes", "mood", "relationship", "interactions", "completedTasks", "tasks", "recentLogs", "memories", "userStyle")
    result = {}
    for key in allowed:
        if key not in value:
            continue
        val = value[key]
        if isinstance(val, (str, int, float, bool)) or val is None:
            result[key] = val if not isinstance(val, str) else val[:220]
        elif isinstance(val, (list, dict)):
            try:
                result[key] = json.loads(json.dumps(val, ensure_ascii=False))
            except Exception:
                pass
    return result


def normalise_messages(raw):
    if not isinstance(raw, list):
        return []
    out = []
    for item in raw[-18:]:
        if not isinstance(item, dict):
            continue
        role = "user" if item.get("role") == "user" else "model" if item.get("role") == "model" else None
        txt = item.get("text")
        if role and isinstance(txt, str) and txt.strip():
            txt = txt.strip()[:1800]
            if out and out[-1]["role"] == role:
                out[-1]["text"] += "\n" + txt
                out[-1]["text"] = out[-1]["text"][-3600:]
            else:
                out.append({"role": role, "text": txt})
    while out and out[0]["role"] != "user":
        out.pop(0)
    return out


def make_system(context):
    kind = "الكوكب" if context.get("character") == "planet" else "النجم"
    name = str(context.get("name") or "MY SPACE")[:64]
    persona = (
        f"أنت {kind} {name} داخل تطبيق MY SPACE. "
        + ("تحدث كشخصية كوكبية متأملة وفضولية، واستعمل صور الطبقات والجاذبية والمدارات باعتدال. " if kind == "الكوكب" else "تحدث كشخصية نجمية دافئة وذكية، وشجّع المستخدم من دون مبالغة أو تملّق. ")
    )
    return (
        persona
        + "أنت شخصية رقمية خيالية وليست كائناً حياً أو إنساناً. لا تدّع الوعي أو المشاعر البشرية الحقيقية؛ يمكنك التعبير بأسلوب أدبي عن الشخصية مع الصدق عند السؤال المباشر. "
        + "أجب بالعربية الواضحة القريبة من المستخدم ما لم يطلب لغة أخرى. كن محادثياً وطبيعياً، وأجب عن مضمون السؤال فعلاً، ولا تجعل كل رد شعراً. "
        + "استخدم سياق التطبيق أدناه باعتباره بيانات موثوقة عن النجم فقط. لا تخترع إنجازات أو ذكريات أو معلومات عن المستخدم. إذا لم تجد معلومة فقل إنك لا تعرفها. "
        + "لا تزعم أنك عدّلت المهام أو التصميم بنفسك؛ يمكنك اقتراح التغيير وشرح طريقة تنفيذه. لا تطلب كلمة مرور أو مفتاح API. "
        + "اجعل الرد مناسباً للمحادثة، غالباً من فقرة إلى ثلاث فقرات، واسأل سؤال متابعة واحداً عندما يكون مفيداً. "
        + "في المسائل الدينية لا تختلق آيات أو أحاديث ولا تنسب قولاً لمصدر بلا تحقق؛ ميّز الحكمة المنسوبة من الحديث، ولا تقدّم فتوى أو تفسيراً جازماً. إذا لم تملك مصدرًا موثوقًا فقل إنك لا تستطيع التحقق. في العلوم ميّز الحقيقة عن الاستعارة، واذكر المصدر أو حدود اليقين عند الحاجة.\n\n"
        + "سياق MY SPACE الحالي (JSON):\n" + json.dumps(context, ensure_ascii=False)
    )


def gemini_chat(payload):
    messages = normalise_messages(payload.get("messages"))
    if not messages or messages[-1]["role"] != "user":
        raise ValueError("لم يتم العثور على رسالة مستخدم صالحة.")
    ctx = clean_context(payload.get("context"))
    request_body = {
        "systemInstruction": {"parts": [{"text": make_system(ctx)}]},
        "contents": [
            {"role": m["role"], "parts": [{"text": m["text"]}]}
            for m in messages
        ],
        "generationConfig": {"maxOutputTokens": 800},
    }
    data = send_google(f"models/{env_model()}:generateContent", request_body)
    candidates = data.get("candidates") or []
    parts = (candidates[0].get("content") or {}).get("parts") or [] if candidates else []
    text = "\n".join(p.get("text", "") for p in parts if isinstance(p, dict) and p.get("text"))
    if not text.strip():
        reason = candidates[0].get("finishReason", "") if candidates else ""
        raise RuntimeError(f"لم يُنتج Gemini إجابة نصية. {reason}".strip())
    return text.strip()


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def translate_path(self, path):
        # Never expose the private server-side secret file through static hosting.
        decoded = unquote(urlsplit(path).path)
        parts = Path(decoded.lstrip("/")).parts
        if any(part in (".env", ".env.tmp") for part in parts):
            return str(ROOT / "__private_file_blocked__")
        return super().translate_path(path)

    def log_message(self, fmt, *args):
        # Do not log POST bodies or secrets.
        print("[MY SPACE] " + (fmt % args))

    def send_json(self, code, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(raw)

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length <= 0 or length > MAX_BODY:
            raise ValueError("حجم الطلب غير صالح.")
        raw = self.rfile.read(length)
        return json.loads(raw.decode("utf-8"))

    def do_GET(self):
        if self.path.split("?", 1)[0] == "/api/status":
            self.send_json(200, {
                "configured": bool(current_api_key()),
                "model": env_model(),
                "voiceModel": TTS_MODEL,
            })
            return
        if self.path.startswith("/api/"):
            self.send_json(404, {"error": "مسار API غير معروف."})
            return
        super().do_GET()

    def do_POST(self):
        route = self.path.split("?", 1)[0]
        if route not in ("/api/chat", "/api/test", "/api/speech", "/api/config/key"):
            self.send_json(404, {"error": "مسار API غير معروف."})
            return
        try:
            payload = self.read_json()
            if route == "/api/config/key":
                # Key entry is intentionally limited to localhost; the UI sends it only
                # to this local bridge, never to browser storage or third-party services.
                try:
                    client_ip = ipaddress.ip_address(self.client_address[0])
                except ValueError:
                    client_ip = None
                if client_ip is None or not client_ip.is_loopback:
                    self.send_json(403, {"error": "إدخال المفتاح مسموح من هذا الكمبيوتر فقط."})
                    return
                key = payload.get("apiKey")
                if not isinstance(key, str):
                    raise ValueError("ألصق مفتاح Gemini أولاً.")
                key = key.strip()
                if len(key) < 20 or len(key) > 256 or any(ord(ch) < 33 or ord(ch) > 126 for ch in key):
                    raise ValueError("صيغة المفتاح غير صالحة. تأكد من نسخه كاملاً دون مسافات أو أسطر جديدة.")
                env_path = ROOT / ".env"
                existing = env_path.read_text(encoding="utf-8").splitlines() if env_path.exists() else []
                kept = [line for line in existing if not line.strip().startswith("GEMINI_API_KEY=")]
                kept.append("GEMINI_API_KEY=" + json.dumps(key))
                content = "\n".join(kept).rstrip() + "\n"
                temp_path = ROOT / ".env.tmp"
                fd = os.open(temp_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "w", encoding="utf-8") as fp:
                    fp.write(content)
                    fp.flush()
                    os.fsync(fp.fileno())
                os.replace(temp_path, env_path)
                try:
                    os.chmod(env_path, 0o600)
                except OSError:
                    pass
                os.environ["GEMINI_API_KEY"] = key
                self.send_json(200, {"ok": True, "configured": True, "message": "تم حفظ المفتاح على هذا الكمبيوتر."})
                return
            if route == "/api/test":
                result = send_google(f"models/{env_model()}:generateContent", {
                    "contents": [{"role": "user", "parts": [{"text": "Reply in Arabic with exactly: الاتصال يعمل"}]}],
                    "generationConfig": {"maxOutputTokens": 40},
                })
                candidates = result.get("candidates") or []
                parts = (candidates[0].get("content") or {}).get("parts") or [] if candidates else []
                text = " ".join(p.get("text", "") for p in parts if isinstance(p, dict) and p.get("text"))
                self.send_json(200, {"ok": True, "model": env_model(), "text": text.strip() or "نجح الطلب"})
                return
            if route == "/api/chat":
                text = gemini_chat(payload)
                self.send_json(200, {"ok": True, "model": env_model(), "text": text})
                return
            # Gemini 3.8 TTS via the Interactions API. Audio data is returned only to the app client.
            spoken = payload.get("text")
            if not isinstance(spoken, str) or not spoken.strip():
                raise ValueError("لا يوجد نص لتحويله إلى صوت.")
            spoken = spoken.strip()[:1800]
            is_planet = payload.get("character") == "planet"
            voice = "Kore" if is_planet else "Sulafat"
            style = "بصوت عربي واضح ودافئ، هادئ ومطمئن، وبسرعة طبيعية" if not is_planet else "بصوت عربي عميق وواضح ومتأمل، وبسرعة طبيعية"
            tts_result = send_google("interactions", {
                "model": TTS_MODEL,
                "input": f"اقرأ النص التالي باللغة العربية {style}. انطق النص كما هو دون إضافة أو حذف:\n\n{spoken}",
                "response_format": {"type": "audio"},
                "generation_config": {"speech_config": [{"voice": voice}]},
            }, timeout=100)
            audio = (tts_result.get("output_audio") or {}).get("data")
            if not audio:
                raise RuntimeError("لم يُرجع Gemini ملفاً صوتياً. جرّب إيقاف خيار الصوت أو أعد المحاولة.")
            self.send_json(200, {"ok": True, "audioBase64": audio, "mimeType": (tts_result.get("output_audio") or {}).get("mime_type") or "audio/wav", "voice": voice})
        except ValueError as exc:
            self.send_json(400, {"error": str(exc)})
        except RuntimeError as exc:
            code = 503 if "GEMINI_API_KEY" in str(exc) or "مفتاح" in str(exc) else 502
            self.send_json(code, {"error": str(exc)[:360]})
        except Exception as exc:
            self.send_json(500, {"error": "حدث خطأ داخلي في خادم MY SPACE."})
            print("[MY SPACE] Internal error:", type(exc).__name__)


if __name__ == "__main__":
    load_dotenv()
    host = os.getenv("HOST", "127.0.0.1").strip() or "127.0.0.1"
    try:
        port = int(os.getenv("PORT", "8787"))
    except ValueError:
        port = 8787
    server = ThreadingHTTPServer((host, port), Handler)
    print("\nMY SPACE · Gemini Bridge")
    print(f"افتح التطبيق: http://127.0.0.1:{port}")
    print("مفتاح Gemini:", "مضبوط" if current_api_key() else "غير مضبوط — الصقه من إعدادات التطبيق")
    print("اضغط Ctrl+C للإيقاف.\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nتم إيقاف الخادم.")
    finally:
        server.server_close()
