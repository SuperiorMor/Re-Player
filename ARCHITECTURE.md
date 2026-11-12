# Re:Player — Архитектура и правила проекта

Этот документ фиксирует ключевые технические решения, соглашения по UI/D-pad и схему веток, чтобы любой новый разработчик или новый диалог в Antigravity сразу понимал контекст проекта.

## 1. Стек воспроизведения
- Движок: **LibVLC** (`org.videolan.android:libvlc-all:3.5.1`) в [`RePlayerEngine`](app/src/main/java/com/replayer/player/RePlayerEngine.kt).
- **Запрещено** возвращать ExoPlayer / Media3.
- Нативные вызовы `mediaPlayer.media = ...`, `stop()`, `release()` выполняются на выделенном `engineExecutor` вне Main Thread (во избежание ANR).

## 2. Навигация D-pad (Android TV)
- Левое меню ([`TvNavigationRail`](app/src/main/java/com/replayer/ui/components/TvNavigationRail.kt)):
  - Переключение активного раздела происходит только по `onClick` (OK/Enter).
  - Перемещение фокуса `Вверх`/`Вниз` не меняет открытый справа экран.
  - Нажатие `Вправо` с **любого** пункта меню переводит фокус в `activeScreenFocusRequester` (текущий открытый экран).
  - Возврат `Назад` с корневого уровня раздела возвращает фокус на активную кнопку меню без сброса выбранной вкладки на «Главная».
- Карточки ([`TvFocusCard`](app/src/main/java/com/replayer/ui/components/TvFocusCard.kt)):
  - Звёздочки избранного в DLNA и IPTV находятся **внутри** `TvFocusCard` и не являются отдельными фокусными узлами — масштабируются вместе с карточкой.
  - Добавление/удаление из избранного на карточках (папки DLNA, каналы IPTV, избранные папки на Главной) работает по удержанию `OK` (`onLongClick`, ~450 мс).
  - Анимация нажатия лёгкая (`1.05f` → `1.025f`).
- Защита от скачков фокуса (`LocalBringIntoViewSpec`):
  - На ТВ Compose 1.7 по умолчанию центрирует фокус на ~30% экрана (`PivotBringIntoViewSpec`).
  - В [`SettingsScreen`](app/src/main/java/com/replayer/ui/screens/SettingsScreen.kt) используется `EdgeOnlyBringIntoViewSpec` (заголовок закреплён, скролл только у краёв).
  - В [`EpgAccordionDialog`](app/src/main/java/com/replayer/ui/components/EpgAccordionDialog.kt) используется `NoopBringIntoViewSpec` + пошаговая анимация: свернуть предыдущую передачу → прокрутить выбранную к верху → раскрыть описание.
- Память позиции в папках DLNA и IPTV:
  - В [`UpnpBrowseScreen`](app/src/main/java/com/replayer/ui/screens/UpnpBrowseScreen.kt) и [`IptvScreen`](app/src/main/java/com/replayer/ui/screens/IptvScreen.kt) переходы между вложенными списками удерживают фокус на внутреннем `transitionFocusRequester`, не допуская кратковременной вспышки на иконку «Домик» в меню.

## 3. Структура UI-модулей настроек
- Экран настроек: `ui/screens/SettingsScreen.kt`
- Вынесенные диалоги настроек: `ui/components/settings/`
  - `SettingsSupportQrDialog.kt`
  - `SettingsChangelogDialog.kt`
  - `SettingsLocalPlaylistDialog.kt`
  - `SettingsUrlEditDialog.kt`
- Диагностика и Telegram-отчёты: `ui/components/DiagnosticsSection.kt`, `diagnostics/CrashReporter.kt`

## 4. Ветки GitHub, каналы обновлений и версионирование
- **Ветка `main` (канал Stable):**
  - Только стабильные релизы (`v1.2.0`, `v1.3.0`, `v2.0.0`).
  - GitHub Release публикуется как обычный Latest Release (`prerelease: false`).
- **Ветка `beta` (канал Beta):**
  - Разработка и тестирование новых фич (в том числе VPortal / Re:Player 2.0).
  - Теги формата `v1.2.0-beta.1`, `v1.2.0-beta.2`, `v2.0.0-beta.1`.
  - GitHub Actions автоматически помечает релизы из тегов с `beta` флагом `prerelease: true`, поэтому они скрыты от `/releases/latest`.
- **Важное правило `versionCode` (Android):**
  - `versionCode` должен расти строго монотонно на обоих каналах.
  - Переход `Stable → Beta` работает в 1 клик.
  - Переход `Beta → Stable` без удаления приложения сработает, как только выйдет следующий Stable с `versionCode` выше установленной Beta.
- **Скрытое переключение канала в приложении:**
  - В Настройках на кнопке «Проверить сейчас» нажать **Вправо 5 раз подряд** — переключает канал `Stable ↔ Beta`.
- **Параллельная работа в двух чатах (git worktree):**
  - Для параллельной работы с `main` и `beta` в разных окнах рекомендуется создать отдельную папку:
    `git worktree add ../Promokashka-beta beta`
