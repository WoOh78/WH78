# WH78 Beta b11

Patch for the current WH78 Beta b10 project.

## Changes
- Tunnel list: inactive switch thumb is gray; active thumb keeps the current theme accent.
- Tunnel list: "Подключено" status is larger and bold for stronger visual indication.
- Empty dashboard: "Добавить профиль" opens a chooser with only .conf import and QR import.
- Service checks: YouTube, Instagram, WhatsApp, Telegram are arranged in one row while keeping 94dp card height.
- Subscription bar is displayed above the connect/disconnect button.
  - expiry profiles: remaining days/hours + expiry date + proportional progress;
  - no expiry: full gold bar with "Без срока";
  - expired: red empty bar;
  - last known expiry is cached and shown while disconnected; temporary API failure does not erase the cached value.
- Build number: b11.
- versionCode: 16.

## Apply
Unzip this archive over the existing WH78-Beta-b10 project. Do not rename the project folder.
