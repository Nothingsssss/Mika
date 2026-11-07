# Native WeChat development build

## Source and scope

- Baseline: Mika tag `v0.1.38`, commit `5d3c7f9c0ce521812632674776b780a1b4bbf58a`.
- Reference: Tencent/openclaw-weixin commit `874637a0dbc24050f72d1385add4fc6170a2d726`, protocol documentation and client 2.4.10.
- Fork: https://github.com/Nothingsssss/Mika, branch `codex/native-weixin`.
- v0.1.38 already ships Kotlin WechatApi/WechatRuntime/WechatChannel/WechatService and a QR UI. This branch repairs and completes that native implementation rather than installing OpenClaw or adding another executor.

## Changes

- QR JSON body, application version packing, decimal-UIN/base64 headers, API errors in both `ret` and `errcode`, honest `bot_agent=Mika/0.1.38`.
- Cancellable QR polling, scanned/verification/redirect/expired states, verification input, refresh button, five-minute local expiry, official HTTPS redirect validation before sending credentials.
- Runtime uses the existing private `specialUse` foreground service, connectivity callbacks, bounded retry backoff, and a persisted one-hour pause for the official session-expired code -14.
- Account credentials and context/cursor/pending/dedup state encrypted with Android Keystore AES-GCM. Old state migrates to encryption. Corrupt state disables reception until rebind, without crashing unrelated Mika features.
- Authorized-owner text enters the existing ChannelGateway → TaskService/TaskCenter queue and Outbox. MCP HTTP is not used or exposed. RemoteTaskHub (the v0.1.38 equivalent of McpTaskHub) remains unchanged.
- `停止`, `停止任务`, `/stop` route to the current run cancellation; a persisted command records its target so replay cannot cancel a subsequent run.
- `ask_user` choices are included in channel text; reply with the option text. Existing phone UI still uses its original prompt and option controls. Confirmations for payments remain on the phone.
- Unbind stops reception and deletes local credentials/state. The documented protocol does not provide a server-side revoke endpoint, so the client does not invent one.

## Build and install

Requirements: JDK 17, Android SDK platform 36, build tools (Gradle resolves 34.0.0), and network access for Maven dependencies.

```powershell
$env:JAVA_HOME = 'path-to-jdk17'
$env:ANDROID_HOME = 'path-to-android-sdk'
.\gradlew.bat -PdevInstall :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`-PdevInstall` is mandatory: application ID `com.wangzan101.mika.dev`, label **Mika Dev**. It installs beside the official app and uses a separate Android Keystore/data directory. Never install a debug-signed build over the official package.

In Mika Dev, configure your model and phone control, then Settings → 微信连接 → 绑定微信. Scan with iPhone WeChat and confirm authorization. If a verification code is requested, enter it on the Android QR page. Keep 接收微信任务 enabled and allow the connection notification. No computer or public HTTP/MCP service is involved in message transport or execution.

The independent development package does not import the release app’s private data. Model keys must be configured in the dev app and are never included in this source tree or APK.

## Tests and evidence

See [native-wechat-validation.md](native-wechat-validation.md). Phone screenshots and private execution logs are kept locally and are not committed. No WeChat tokens, QR authorization material, model credentials, or private task logs are included in this repository.

## Operational limits

- Phone still needs internet and a reachable model provider. The iPhone and Android do not need the same network.
- This is an Android foreground service, not a guarantee against OEM force-stop, Doze, user disablement or power loss. Force-stop requires reopening the app. No keep-alive exploits or privileged background bypasses were added.
- In the current USB-started phone-helper configuration, a phone reboot requires restarting its helper. Mika’s own wireless-debug pairing can restore it automatically where supported; that cold-boot path was not newly implemented here.
- A locked device can still require local authentication for protected screens or payment/login confirmation. Existing Mika access checks apply.
- Session limits, unsupported authorization states and official API errors are reported; no OpenClaw identity impersonation or credential capture is used.
