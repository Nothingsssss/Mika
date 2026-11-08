# 微信图片与语音开发版

基于 Mika v0.1.38 和既有 `codex/native-weixin` 原生文字连接，继续使用独立包 `com.wangzan101.mika.dev`。更新安装保留开发版模型、微信绑定和聊天数据，不覆盖正式版。

## 实现

- 按腾讯 `Tencent/openclaw-weixin` 的 iLink 协议接收 image_item / voice_item；使用官方 CDN 地址、getuploadurl 和 sendmessage，不控制微信界面、不公开手机端口。
- 图片加密下载后导入当前微信对话的沙箱 inbox；沿用 Mika 的文件路径校验、空间限制和图片查看工具。云端看图能力仍取决于既有视觉模型配置。
- 语音优先使用微信随消息返回的转写；原始 SILK 解码为 WAV 附件。没有微信转写时，调用用户已在 Mika 配置的服务商语音转写模型；没有该配置会明确说明，不能把音频数据当文字执行。
- 用户要求回传附件时，Agent 使用既有 share_file；任务导出的图片自动作为微信图片发送。音频通过可下载、播放的文件回传；原始 SILK 转为 WAV。**不是原生微信语音气泡**：真机实验中 VOICE 请求返回成功但用户收不到，已移除默认 VOICE 外发路径，避免静默丢消息。腾讯项目 [issue 209](https://github.com/Tencent/openclaw-weixin/issues/209)、[issue 91](https://github.com/Tencent/openclaw-weixin/issues/91) 有同类报告；这不能证明普遍权限规则，但与本次实测一致。没有伪装身份或绕过限制。
- AES-128-ECB/PKCS7 媒体加密与官方协议一致；支持 base64 原始 AES key 与 base64 十六进制 key。Bot 鉴权只发给 iLink，不发往 CDN；只允许官方 HTTPS CDN，禁止跟随重定向。
- 媒体引用的 encrypt_type=1 必须显式编码。初次实测发现默认值被序列化省略，导致图片未显示；已添加 EncodeDefault 和请求体回归断言，修正后用户确认收到图片。
- 单条消息最多 8 个附件，每个最多 20 MB。语音解码最多 60 秒；后台串行准备附件，长轮询继续接收消息，停止命令不会等待媒体准备。
- 媒体引用、context_token、游标、待登记消息、准备好的任务内容一起加密持久化；重启后的媒体不重复导入，任务入口继续沿用原有最终幂等。附件发送也进入既有 Outbox，结果不确定时不盲目重发。

SILK 实现来自 Apache-2.0 的 libjitsi `f6f5b5677ab238cf3171d4d097002acd563481a1`，复制纯 Java codec，排除 JMF 适配器；增加有界微信容器适配层。ResidualEnergyFLP 只有 debug assertion 的舍入容错调整，生产模式的原有 clamp 未改变。未安装 OpenClaw、Node.js、Termux、外部转码程序或新增 Agent。

## 构建与测试

```powershell
.\gradlew.bat -PdevInstall :core:test --tests '*Wechat*' --tests '*ChannelOutboxTest' --tests '*RemoteTaskServiceTest' --tests '*AgentLoopInputTest' :app:testDebugUnitTest --tests '*Wechat*' --tests '*FileOutbox*' --tests '*LicensesTest' --tests '*RunRecoveryTest' --tests '*ProviderStoreTest' :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

真机外发探针是显式 opt-in，正常测试不会向真实微信发送任何消息：

```powershell
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.wangzan101.mika.wechat.WechatMediaLiveTest -e wechatMediaLive true com.wangzan101.mika.dev.test/androidx.test.runner.AndroidJUnitRunner
```

该测试只接受已绑定、已启用的开发包账号，发送一条说明、一张本地生成的测试卡和一个 1 秒提示音 WAV 文件。仅从应用内部使用 Android Keystore 凭据，不打印或导出 Bot token / 模型 key。

## 2026-11-08 验证状态

- APK 和测试 APK 编译成功，开发版更新安装成功，绑定保留。
- 相关 JVM 回归 127 项，0 失败；包含媒体 AES/CDN 请求、真实消息字段、SILK 编解码、WAV 格式、来源限制、媒体去重与状态恢复、后台准备和停止、原模型和任务服务回归。
- Android 17 Redmi K20 Pro 真机最终外发探针 `OK (1 test)`，腾讯真实 CDN 上传和图片/文件 sendmessage 成功；用户确认收到测试图片和 WAV 文件，未单独确认 WAV 播放。
- 原生语音气泡实验失败：服务端返回成功，用户确认只收到图片、未收到语音。已回退音频文件，**不得宣称支持原生语音气泡**。
- 微信语音输入通过：用户发送“计算18+27”，真实 CDN 下载、SILK 解码、WAV 工作区附件导入成功；微信自带转写进入任务，结果 45 回传。没有额外调用付费 ASR。首次模型回复附带“不支持听音频”的误导句，已增加明确的转写来源说明；此文案修正仍待用户后续复测。
- 图片完整闭环通过：用户发送 JPG，Mika 真实导入、查看图片并回复分析；随后用户要求发回原图，既有 share_file 导出，经微信图片协议发回同一图片。发送日志与原文件均为 60,467 字节，任务成功；用户回复“已完成”。不提交私人图片或具体分析内容。
- 直接拿外发 CDN 引用模拟入站回读的附加实验返回 HTTP 400；已撤销该无效模拟，不当作入站验收。实际用户入站图片和语音已分别成功，见上。
- Android 14/15/16、非 SILK/PCM 的入站语音编码、无转写情况下的真实服务商 ASR、长时间后台媒体收发：尚未真机验证。
- 本机没有 TTS 引擎；未新增自动把任意文字合成为人声的能力。
