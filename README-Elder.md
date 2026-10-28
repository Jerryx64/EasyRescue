# 安心用发布与更新

安心用基于 GKD v1.12.1 完整引擎，包名 `com.easyrescue.app`，Android 8 起可用，修改版按 GPL-3.0-only 提供。仓库为 [Jerryx64/EasyRescue](https://github.com/Jerryx64/EasyRescue)。

## 当前状态

- 自建补充规则 v4，包括翼支付、豆瓣、5EPlay、腾讯会议开屏和抖音指定小程序广告完成状态。参与匹配不代表所有版本、广告布局都已验证；5EPlay 的另一套 AM 广告布局仍未覆盖。
- 当前源码为 0.6.2 / versionCode 8，增加系统广告与快应用设置入口、自建源在线更新和新版检查。
- 版本清单目前仅提供已保留原签名的 0.6.0 / versionCode 6；对应完整源码归档和许可与安装包一起提供。0.6.2 原签名尚未迁移，不把另一签名的测试构建当作覆盖升级包发布。
- 已装 0.6.0 不会因仓库发布就自动获得新界面或新地址。需要同签名升级后，才能使用新增的内置联网入口。

## 家人使用

首次开启系统无障碍中的“安心用”，回首页确认“正在保护”。首页可暂停或恢复。遇到无法识别的广告，可添加下拉快捷项“退出广告”脱困。

新版设置中的“系统广告与快应用”提供通知管理、系统设置以及已安装荣耀/华为/小米快应用框架的应用详情和通知入口。按系统实际菜单关闭不需要的推荐通知、个性化推荐或快应用唤起；不同机型的名称不同。关闭个性化推荐不保证减少广告，关闭通知也不等于禁止唤起。进入快应用中心前手动暂停安心用，完成后恢复保护。

“检查规则更新 → 立即检查”获取规则；“按需功能”可开启每天检查，默认关闭。“检查安心用版本”手动查看是否有新版，点击后由浏览器下载、安卓确认安装。网络失败时原有保护和本地规则继续使用。

抖音小程序规则仅在指定版本、页面和可识别完成状态参与匹配，不保证付费短剧解锁、自动续播及所有小程序覆盖。安心用不会自动发起看激励广告、付费或领取任务。

## 固定 HTTPS 地址

规则直接发布源文件，无需另存一份：

`https://raw.githubusercontent.com/Jerryx64/EasyRescue/main/app/src/main/assets/elder_rules/easyrescue/easyrescue.json5`

版本清单：

`https://raw.githubusercontent.com/Jerryx64/EasyRescue/main/distribution/app-update.json`

手机公开下载无需 GitHub 登录或令牌。不要在 APK、链接、源码或版本清单中加入访问令牌。实际网络能否访问 GitHub Raw 需在使用手机的网络验证；不能保证所有网络都可达。

## 构建与发布

使用 JDK 21、Android SDK / build-tools 37 和项目 Gradle wrapper。将本机 SDK 路径写入不入库的 `local.properties`。运行 `build-elder.ps1`，或按 `README-Elder.md` 的说明执行 Gradle。默认构建压缩 Release，应用单元测试使用 Debug 变体，选择器测试定向运行离线套件。

`gradle.properties` 提供 `EASYRESCUE_RULES_URL` 和 `EASYRESCUE_APP_UPDATE_URL`，也可通过同名 Gradle 项目属性覆盖。只接受 HTTPS；留空会显示尚未配置，不请求占位地址。

1. 修改规则后递增订阅 `version`，保留订阅 ID `2026101801`，运行规则策略及选择器回归，再提交到 `main`。同版本不重新安装规则。
2. 修改应用功能时递增 `versionCode` 和 `versionName`。通过项目支持的 `GKD_STORE_FILE`、`GKD_STORE_PASSWORD`、`GKD_KEY_ALIAS`、`GKD_KEY_PASSWORD` 私下配置原签名，密钥与密码不入库。无原密钥时不能覆盖旧签名安装，不通过卸载或清数据绕过。
3. 用 `apksigner verify --print-certs` 检查正式 APK，证书 SHA-256 必须为 `2490daa60e9eb07570ce0fc871cfbe7747bca6c963556dbdbd53fb7734c79c1d`。先上传 APK 及其完整对应源码，再更新 `app-update.json` 的版本、下载地址和更新说明。
4. 从未登录网络请求规则、版本清单及 APK，核对下载文件哈希，并在真机上验证规则、覆盖升级和保护状态。版本清单上限 64 KiB，包名必须为 `com.easyrescue.app`，下载仅允许 HTTPS；应用只提示比自身更高的版本。

无需自建服务器、数据库、GitHub Pages 或常驻更新服务。规则更新和 App 更新是独立流程；规则无法替代 Android 系统权限或修改系统开关。

## 来源与许可

GKD 上游：[gkd-kit/gkd](https://github.com/gkd-kit/gkd)，固定提交 `5a00f844989471458e4cf4601acb3f4710438f9a`。完整原始项目说明保留在根 README；GPL 全文在根 LICENSE。社区规则的原文、来源与许可在 `app/src/main/assets/elder_rules/`。公开/脱敏测试快照的来源见选择器测试资源。发布内容不含手机截图、抓取日志、账号、SDK 缓存或签名密钥。
