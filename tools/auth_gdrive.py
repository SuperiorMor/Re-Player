#!/usr/bin/env python3
"""
Re:Player Google Drive OAuth Helper for Mac & PC
Авторизация Google Drive в 1 клик через браузер компьютера

Использование:
  1. Создайте OAuth 2.0 Client ID (тип: Desktop) в Google Cloud Console.
  2. Разрешите в нём redirect URI: http://127.0.0.1:8089
  3. Запустите: python tools/auth_gdrive.py
  4. Скопируйте Client ID, Client Secret и Refresh Token на pairing-страницу ТВ.
"""

import http.server
import json
import socketserver
import sys
import urllib.parse
import urllib.request
import webbrowser

PORT = 8089
REDIRECT_URI = f"http://127.0.0.1:{PORT}"
SCOPE = "https://www.googleapis.com/auth/drive.readonly email profile"
TOKEN_URL = "https://oauth2.googleapis.com/token"

class OAuthHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        query = urllib.parse.urlparse(self.path).query
        params = urllib.parse.parse_qs(query)

        if "code" in params:
            self.server.auth_code = params["code"][0]
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>Re:Player Auth</title>
            <style>
                body { background: #090A0E; color: #F8FAFC; font-family: sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                .box { background: #13161F; border: 1px solid #06B6D4; padding: 32px; border-radius: 16px; text-align: center; max-width: 480px; }
                h1 { color: #06B6D4; font-size: 22px; }
                p { color: #94A3B8; font-size: 15px; line-height: 1.5; }
            </style>
            </head>
            <body>
                <div class="box">
                    <h1>✅ Авторизация успешна!</h1>
                    <p>Код получен. Сейчас токен будет передан на ваш телевизор.<br>Можете закрыть эту вкладку.</p>
                </div>
            </body>
            </html>
            """
            self.wfile.write(html.encode("utf-8"))
        else:
            error = params.get("error", ["unknown"])[0]
            self.send_response(400)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(f"Ошибка авторизации: {error}".encode("utf-8"))

    def log_message(self, format, *args):
        pass


def exchange_code(client_id, client_secret, auth_code):
    data = urllib.parse.urlencode({
        "code": auth_code,
        "client_id": client_id.strip(),
        "client_secret": client_secret.strip(),
        "redirect_uri": REDIRECT_URI,
        "grant_type": "authorization_code"
    }).encode("utf-8")

    req = urllib.request.Request(TOKEN_URL, data=data, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as e:
        print(f"\n❌ Ошибка обмена кода ({e.code}): {e.read().decode('utf-8')}")
        return None


def main():
    print("=" * 60)
    print("🎬 Re:Player TV — Авторизация Google Drive")
    print("=" * 60)

    client_id = input("\n1. Введите ваш Client ID из Google Cloud Console:\n> ").strip()
    if not client_id:
        print("Client ID не может быть пустым.")
        return

    client_secret = input("\n2. Введите ваш Client Secret:\n> ").strip()
    if not client_secret:
        print("Client Secret не может быть пустым.")
        return

    tv_ip = input("\n3. Введите IP-адрес телевизора (например 192.168.1.45, или оставьте пустым):\n> ").strip()

    auth_url = (
        "https://accounts.google.com/o/oauth2/v2/auth?"
        + urllib.parse.urlencode({
            "client_id": client_id,
            "redirect_uri": REDIRECT_URI,
            "response_type": "code",
            "scope": SCOPE,
            "access_type": "offline",
            "prompt": "consent"
        })
    )

    print("\n🌐 Открываю браузер для входа в Google...")
    webbrowser.open(auth_url)

    server = socketserver.TCPServer(("127.0.0.1", PORT), OAuthHandler)
    server.auth_code = None
    print(f"⏳ Ожидание подтверждения в браузере (порт {PORT})...")

    while not server.auth_code:
        server.handle_request()

    auth_code = server.auth_code
    server.server_close()
    print("✅ Код авторизации получен от Google!")

    print("🔄 Обмениваю код на Refresh Token...")
    token_resp = exchange_code(client_id, client_secret, auth_code)
    if not token_resp:
        return

    access_token = token_resp.get("access_token", "")
    refresh_token = token_resp.get("refresh_token", "")
    print(f"🎉 Успешно! Refresh Token: {refresh_token[:15]}...{refresh_token[-6:] if refresh_token else ''}")

    # Send to TV if IP provided
    if tv_ip:
        payload = json.dumps({
            "client_id": client_id,
            "client_secret": client_secret,
            "access_token": access_token,
            "refresh_token": refresh_token
        }).encode("utf-8")

        target_url = f"http://{tv_ip}:8765/api/save"
        print(f"📡 Отправляю токены на телевизор ({target_url})...")
        try:
            req = urllib.request.Request(
                target_url,
                data=payload,
                headers={"Content-Type": "application/json"},
                method="POST"
            )
            with urllib.request.urlopen(req, timeout=5) as r:
                print("📺 Телевизор подтвердил получение! Google Drive подключен!")
        except Exception as e:
            print(f"⚠️ Не удалось отправить напрямую на {target_url}: {e}")
            print("Вы можете ввести полученный Refresh Token в приложении Re:Player на ТВ вручную:")
            print(f"\nRefresh Token:\n{refresh_token}\n")
    else:
        print("\n" + "=" * 60)
        print("Скопируйте три значения ниже на pairing-страницу ТВ:")
        print(f"  Client ID:     {client_id}")
        print(f"  Client Secret: {client_secret}")
        print(f"  Refresh Token: {refresh_token}")
        print("=" * 60)


if __name__ == "__main__":
    main()

