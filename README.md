# ClipBroad

Share your clipboard (text and images) between a Mac and an Android phone over your local Wi-Fi. No account, no cloud, no server.

## What it does

- A small Mac menu-bar app (`CB` icon) watches the clipboard and sends new text or images to the phone.
- An Android keyboard (IME) on the phone reads its clipboard and sends it back to the Mac.
- Both devices are paired with a shared code that you choose. Clipboard data is encrypted between them.
- Sync pauses while either device is locked.

## Project layout

- `mac/` Swift package for the Mac menu-bar app (`Sources/ClipBroadMac/main.swift`).
- `android/` Kotlin Android app with the keyboard and the setup screen (`ClipBroadIme.kt`, `SetupActivity.kt`).
- `docs/plans/` design notes.

The full-featured keyboard I use daily is built on top of the AnySoftKeyboard project (Apache-2.0). That fork is not included here, only the original ClipBroad code is.

## How to run

Mac:

```sh
cd mac
swift build -c release
```

Run the built `ClipBroadMac`, click `CB` in the menu bar, choose **Set pairing secret** and type a code.

Android: open `android/` in Android Studio, install the app on your phone, type the same code, then select **ClipBroad Keyboard** as your keyboard. Keep both devices on the same Wi-Fi.

## Tech

Swift (AppKit, network framework) on the Mac, Kotlin on Android.

## Support

If this project is useful to you, you can support my work:

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/zfaka)
