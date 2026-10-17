# KuGouLitePlus

![Android](https://img.shields.io/badge/Android-%E2%89%A58.1-blue?logo=android&logoColor=3DDC84)

LSPosed 模块，为酷狗概念版 3.4.9（`com.kugou.android.lite` / `versionCode 10940`）提供自动领取 VIP、去广告等增强功能。

## 功能
- **自动领取 VIP**：每日听歌打卡 + 领取畅听会员 + 升级概念会员
- **去广告**：去除开屏/弹窗/播放页广告
- **防检测**：自包签名一致性，防止多次查询不一致触发应用自校验

## 安装
- 设备需已安装 LSPosed（或兼容 libxposed API 的 Xposed 实现，如 LSPatch、NPatch）
- 安装本模块 APK
- 在 LSPosed 管理器中启用模块，作用域勾选酷狗概念版
- 强制停止并重新打开酷狗概念版

> [!important]
> NPatch 修补应用时⌈破解签名校验⌋需至少选 `Seccomp`

## 构建
- Build Release: `./gradlew :app:assembleRelease`
- Build Debug: `./gradlew :app:assembleDebug`

## 签名
`keystore.properties`，缺失时回退 debug 签名

## 依赖
- Android SDK
- JDK 21
- `io.github.libxposed:api:102.0.0`（compileOnly，运行时由框架提供）

## 免责声明
- 本项目仅供学习与研究使用，请勿用于商业或违法用途。
- 使用本项目产生的任何后果由使用者自行承担，与作者无关。
- 请支持正版音乐服务。
- 如有侵权问题，请提交 Issue 联系。

## 致谢
- [MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi) — 自动领取VIP接口参考