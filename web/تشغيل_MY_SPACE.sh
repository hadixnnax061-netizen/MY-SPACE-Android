#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3 غير مثبت. ثبّته أولاً: sudo apt install python3"
  exit 1
fi
if [ ! -f .env ]; then
  cp .env.example .env
  chmod 600 .env
  echo "تم إنشاء إعدادات الخادم. افتح التطبيق والصق مفتاح Gemini من صفحة الإعدادات."
fi
chmod 600 .env
python3 server.py
