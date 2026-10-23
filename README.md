# Re:Player

**Re:Player** — современный, быстрый и всеядный медиаплеер для **Android TV**, вдохновленный лучшими чертами легендарного *nPlayer*.

Создан с упором на комфортный просмотр с дивана (10-foot experience, диагонали 43" и 50"), мгновенный отклик на пульт (D-Pad), воспроизведение медиа по локальной сети через UPnP/DLNA и прямое воспроизведение из Google Drive.

---

## 🚀 Ключевые возможности

* **Android TV Native UI:** Интерфейс построен на **Jetpack Compose for TV (Material 3)**. Все карточки и кнопки поддерживают D-Pad навигацию, мягкую анимацию увеличения в фокусе (1.08x scale) и контрастное неоновое свечение.
* **Медиа-движок Media3 (ExoPlayer):**
  * Поддержка всех популярных видеоформатов (MKV, MP4, AVI, WebM, TS) и аудио.
  * **Выбор аудиодорожек:** переключение дорожек прямо во время воспроизведения с отображением кодека, языка и каналов (AC3 5.1, AAC, DTS и др.).
  * **Выбор субтитров:** переключение встроенных и внешних дорожек (SRT, ASS со стилями, VTT) или полное отключение.
  * **Экран не гаснет:** автоматический `FLAG_KEEP_SCREEN_ON` на уровне окна во время проигрывания.
* **UPnP / DLNA автосканирование:**
  * Фоновое SSDP-обнаружение медиасерверов (роутеры с USB, NAS, Synology, Windows Media Player) в домашней Wi-Fi сети телевизора с удержанием `WifiManager.MulticastLock`.
  * Браузер структуры папок и воспроизведение медиа без предварительного скачивания.
* **Google Drive интеграция:**
  * **TV Device Authorization Flow (OAuth 2.0 RFC 8628):** авторизация через QR-код или короткий код на телефоне/ПК без мучительного ввода пароля с пульта.
  * Потоковое воспроизведение видео и аудио с Диска через HTTP Range Requests.

---

## 📁 Структура проекта

```
RePlayer/
├── app/
│   ├── build.gradle.kts           # Зависимости Media3, Compose for TV, OkHttp, Coil
│   └── src/main/
│       ├── AndroidManifest.xml    # TV-манифест, Leanback Launcher, Banner, Разрешения
│       ├── java/com/replayer/
│       │   ├── MainActivity.kt    # ТВ-контейнер, навигация, переключение экранов
│       │   ├── RePlayerApp.kt     # Базовый Application класс
│       │   ├── model/             # MediaItem, TrackInfo, UpnpDevice, DriveAuthInfo
│       │   ├── player/            # RePlayerEngine (ExoPlayer, выбор дорожек/субтитров)
│       │   ├── network/
│       │   │   ├── upnp/          # SSDP сканер, парсер устройств, SOAP ContentDirectory
│       │   │   └── gdrive/        # Device Flow авторизация, REST API v3 браузер
│       │   └── ui/
│       │       ├── theme/         # Цветовая палитра для ТВ, шрифты для 43"-50"
│       │       ├── components/    # TvFocusCard (фокус D-pad), TvNavigationRail
│       │       └── screens/       # HomeScreen, UpnpBrowseScreen, GoogleDriveScreen, PlayerScreen
│       └── res/
│           ├── drawable/          # tv_banner.xml (320x180), ic_replayer_logo.xml
│           └── values/            # colors.xml, themes.xml, strings.xml
├── gradle/
│   └── libs.versions.toml         # Version Catalog (Kotlin 2.0, Media3, AGP 8.7)
└── build.gradle.kts               # Корневой Gradle скрипт
```

---

## 🛠 Запуск и разработка

1. Откройте папку проекта в **Android Studio**.
2. Дождитесь автоматической синхронизации Gradle.
3. Выберите Android TV эмулятор (или подключите реальный ТВ через ADB Wi-Fi: `adb connect 192.168.x.x:5555`).
4. Нажмите **Run** (`Shift + F10`).
