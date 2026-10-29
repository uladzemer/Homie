**Homie — Apple TV style launcher for Android TV.** Clean tvOS‑inspired home screen: rows of app tiles, smooth focus lift, Cover Flow‑style classic mode, photo backgrounds, English and Russian. No ads, no tracking.

![Homie home screen](https://raw.githubusercontent.com/uladzemer/Homie/master/docs/screenshots/en-home.jpg)

### 0.4.0
- Boot screen changes only the Android boot animation, with verified backups and atomic file replacement. As in Firmware also restores a logo / ODM image changed by older versions when its backup verifies; unverified parts are skipped with a warning.
- Update downloads clean up cached APKs and handle cancellation more reliably.
- Faster return to Home with cached apps; Home also closes open menus.
- Long OK works by timer, including remotes without key repeats.
- Autostart retries while apps become available after boot, and stops when you leave Home.
- README privacy information corrected.

### Что нового в 0.4.0
- Меняется только анимация загрузки Android: проверенные резервные копии и замена через временный файл. «Как в прошивке» также возвращает логотип / картинку ODM от старых версий, если копия проходит проверку; непроверенные части пропускаются с предупреждением.
- Исправлены очистка APK обновления и отмена установки.
- Быстрее возврат «Домой» благодаря кэшу приложений; кнопка «Домой» закрывает меню.
- Долгое ОК по таймеру работает и на пультах без повторов нажатия.
- Автозапуск повторяет попытки после загрузки и прекращает их при уходе с главного экрана.
- Уточнена информация о приватности в README.

### Install
1. Download the APK below and install it on your Android TV box (Android 8.0+), e.g. `adb install homie-*.apk`.
2. Press Home on the remote and choose **Homie**.
3. For the power button, enable Homie once in Settings → Accessibility.

### Установка
Скачайте APK ниже, установите на приставку, нажмите «Домой» и выберите **Homie**.
