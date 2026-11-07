# 微信原生连接验收记录

日期：2026-11-07 至 2026-11-08（Asia/Shanghai）。
设备：Redmi K20 Pro，BlissRoms Android 17；独立安装包 `com.wangzan101.mika.dev`。
iPhone：用户持有，微信扫码及消息接收由用户操作确认；未由测试程序读取 iPhone 凭据。

## 已通过

| 验收项 | 证据及范围 |
| --- | --- |
| APK 构建 | Gradle `-PdevInstall :app:assembleDebug` 成功；ADB 安装 Success；正式版包名和数据未覆盖 |
| 真机二维码 | Android 17 真机显示来自腾讯 API 的二维码，含等待扫码、刷新和取消入口 |
| iPhone 授权 | 用户实际扫码确认；安卓显示“已连接”，WechatService 正处于 specialUse 前台状态 |
| 跨网络文字闭环 | 用户按测试步骤使用 iPhone 蜂窝网络发送安卓版本查询，确认收到任务已登记和对应结果；安卓运行记录中有系统设置实际操作 |
| 实际 App 操作 | 计算器任务记录包含 com.android.calculator2、phone_tap 和观察值 703，最终任务成功，结果为 37×19=703 |
| 锁屏/后台接收 | 安卓回桌面并息屏后发送计算器任务，实际接收、执行并发起追问；并非仅模拟模型回复 |
| 追问选项及继续 | 首次实测发现选项丢失，已修复；用户复测完整选项后回复“蓝色”，确认最终复述正确 |
| 网络断开恢复 | 通过 svc wifi disable/enable 制造断开重连，再回桌面息屏；后续选项/答案闭环正确，由用户确认 |
| 无电脑连接运行 | 用户按要求拔掉红米 USB，iPhone 保持蜂窝网络，任务返回 45；重连后安卓任务记录确认成功且结果为 18+27=45 |
| 单元与相关回归 | 111 项，失败 0，错误 0。范围见下表 |

## 自动化回归范围

- WechatApiTest：7（请求体/头、扫码轮询、验证码、错误码、原生客户端身份、地址校验）。
- WechatChannelTest：12（仅主人消息、游标/去重、取消长轮询、旧账号响应隔离、追问路由、停止及重放隔离）。
- WechatStoreTest：6（账号状态恢复、去重账本、换绑、解绑、AES-GCM 状态/凭据保护及状态损坏时停止接收）。
- AgentLoopInputTest：6（既有用户交互以及渠道选项）。
- ChannelOutboxTest：6（既有消息送达与网关行为）。
- RemoteTaskServiceTest：9（既有 MCP/远程任务契约）。
- ProviderStoreTest：56（既有模型配置）。
- RunRecoveryTest：9（既有任务恢复）。

执行命令：

```powershell
.\gradlew.bat -PdevInstall :core:test --tests '*WechatApiTest' --tests '*ChannelOutboxTest' --tests '*RemoteTaskServiceTest' --tests '*AgentLoopInputTest' :app:testDebugUnitTest --tests '*Wechat*' --tests '*RunRecoveryTest' --tests '*ProviderStoreTest' :app:assembleDebug
```

## 未完成或未验证

- Android 14、15、16 分别的真机：当前仅 Android 17 真机。
- 长时间 Doze、系统回收前台服务后恢复、冷启动重启后的无线调试自动恢复：未验证。USB 启动的手机助手在手机重启后需重新启动，沿用 Mika 原限制。
- 腾讯扫码重定向、验证码要求、二维码自然过期、-14 一小时暂停等异常分支：协议实现及部分单元测试覆盖，未触发真实服务端场景。
- 真机停止命令、解绑/换绑、付款/人工接管、其他所有原功能的完整回归：未逐一真机验证。不要将上面特定回归测试理解为全功能保证。
- 网络请求结果不确定时沿用现有 Outbox 策略，不自动盲目重发，避免重复通知；需要用户复核不确定的结果。

## 本地产物

- 源码：`C:\Users\Z\Documents\Codex\MikaNative`，分支 `codex/native-weixin`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，可直接 adb install -r 更新独立开发包。
- 构建日志、真机截图：`C:\Users\Z\AppData\Local\MikaBuild`。截图和任务日志不上传 GitHub。
- 用户提供的模型 API Key 仅配置在真机 Android Keystore 中，未写入源码、测试或提交。
