# PhoneRest

PhoneRest 是一个 Android 连续亮屏计时应用。启用监控后，应用会在前台服务中统计当前一轮亮屏时间；达到设定时长时，如果设备管理员权限仍然有效，应用会调用系统接口锁定屏幕。

## 主要功能

- 显示本轮连续亮屏时间，格式为“分钟:秒”
- 设置 5–60 分钟的自动锁屏时长，默认 30 分钟
- 提供 15、20、25、30 分钟快捷选项
- 可选的锁屏前提醒，支持提前 1–5 分钟，默认提前 5 分钟
- 提供 1、2、3、5 分钟提醒快捷选项
- 使用设备管理员权限执行自动锁屏
- 提供“立即锁屏（测试）”按钮
- 通过前台服务持续计时并显示常驻通知
- 点击常驻通知主体，立即将本轮计时归零并重新计算锁屏时间，同时显示“计时已重置”提示；展开通知后可点击“打开应用”进入主界面
- 监听亮屏和熄屏状态；熄屏后将本轮计时归零
- 接收设备启动和锁定启动完成广播，并在监控已开启时尝试恢复服务
- 提供系统应用详情入口
- 可在弹窗中展示 chibi 大头图片
- 提供浅色和深色主题资源

## 使用方法

1. 打开 PhoneRest。
2. 点击“授权设备管理员”，在系统界面中允许 PhoneRest 使用锁屏能力。
3. 设置连续亮屏触发时长。
4. 根据需要开启锁屏前提醒并设置提前时间。
5. 开启“连续亮屏后锁屏”。

开启监控前，应用会检查设备管理员权限；未授权时不会打开监控开关。Android 13 及以上版本启动应用时会请求通知权限。

返回应用时会重新检查设备管理员权限；若权限已撤销，会关闭监控、停止服务并清空界面计时。

“立即锁屏（测试）”会直接调用与自动锁屏相同的系统接口，可用于检查设备管理员权限是否可用。“打开应用后台设置”会跳转至当前应用的系统详情页面。

## 计时与锁屏机制

`ScreenTimeMonitorService` 是不支持绑定的前台服务。服务创建时会读取 `PowerManager.isInteractive`，并监听：

- `ACTION_SCREEN_ON`
- `ACTION_SCREEN_OFF`

亮屏时，服务使用 `SystemClock.elapsedRealtime()` 记录起点；熄屏时清空起点、提醒状态和当前计时。

服务仅在亮屏时每秒执行一次检查；熄屏后移除计时任务，下一次亮屏时恢复：

1. 确认监控开关仍处于开启状态。
2. 读取锁屏时长、提醒时长和提醒开关。
3. 在进入提醒区间后显示一次 Toast。
4. 达到锁屏时长后调用 `DevicePolicyManager.lockNow()`。
5. 将当前计时和屏幕状态通过应用内定向广播发送给主界面。

亮屏计时期间，前台通知每 60 秒更新一次；亮屏、熄屏、手动重置、达到锁屏阈值或检测到锁屏时长修改时会立即刷新。屏幕点亮时，通知显示已亮屏分钟数和设定的锁屏分钟数；屏幕熄灭时显示本轮计时为 0。

点击通知主体会重置本轮计时并显示系统文字提示，展开后的“打开应用”按钮进入主界面。提示使用系统默认配色，连续触发时只保留最新提示。

如果服务启动时发现监控已关闭，会停止自身并返回 `START_NOT_STICKY`；正常监控时返回 `START_STICKY`。

## 设置存储

`UserPreferences` 使用名为 `phone_rest` 的 `SharedPreferences` 文件保存：

- 自动锁屏时长
- 监控开关状态
- 提醒开关状态
- 提前提醒时长

锁屏时长始终限制在 5–60 分钟，提醒时长始终限制在 1–5 分钟。监控默认关闭，提醒默认开启。

## 权限与组件

应用声明了以下权限：

- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`
- `android.permission.POST_NOTIFICATIONS`
- `android.permission.RECEIVE_BOOT_COMPLETED`

设备管理员配置只声明 `force-lock` 策略。

Manifest 中注册的组件：

- `MainActivity`：应用启动入口
- `ScreenTimeMonitorService`：不导出的 `specialUse` 前台服务
- `ScreenLockAdminReceiver`：受 `BIND_DEVICE_ADMIN` 权限保护的设备管理员接收器
- `BootReceiver`：接收 `BOOT_COMPLETED` 和 `LOCKED_BOOT_COMPLETED`

## 源码结构

```text
app/src/main/
├── AndroidManifest.xml
├── java/com/example/phonerest/
│   ├── MainActivity.kt
│   ├── ScreenTimeMonitorService.kt
│   ├── ScreenLockAdminReceiver.kt
│   ├── BootReceiver.kt
│   ├── UserPreferences.kt
│   └── DurationControl.kt
└── res/
    ├── drawable/
    ├── drawable-nodpi/
    ├── layout/activity_main.xml
    ├── mipmap-anydpi/
    ├── values/
    ├── values-night/
    └── xml/device_admin.xml
```

各 Kotlin 类的职责：

- `MainActivity`：初始化界面、处理设备管理员授权、控制监控服务和更新计时显示
- `ScreenTimeMonitorService`：统计亮屏时间、发送界面更新、显示通知和执行锁屏
- `ScreenLockAdminReceiver`：接入 Android 设备管理员机制
- `BootReceiver`：在收到启动广播后根据已保存设置恢复监控
- `UserPreferences`：持久化和校验用户设置
- `DurationControl`：同步 SeekBar、数字输入框和快捷按钮的值

## 构建配置

| 项目 | 配置 |
| --- | --- |
| application ID | `com.example.phonerest` |
| namespace | `com.example.phonerest` |
| minSdk | 27 |
| targetSdk | 35 |
| compileSdk | 35 |
| Build Tools | 35.0.0 |
| Java | 17 |
| Kotlin JVM target | 17 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin Android Plugin | 2.0.21 |
| Gradle | 8.9 |
| Material Components | 1.12.0 |
| versionName | 1.0 |
| versionCode | 1 |

## 构建项目

先从示例创建本机 SDK 配置：

```powershell
Copy-Item local.properties.example local.properties
```

根据本机 Android SDK 的实际安装位置修改 `local.properties` 中的 `sdk.dir`，然后执行：

```powershell
.\gradlew.bat assembleDebug
```

生成的 Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

执行 Android Lint：

```powershell
.\gradlew.bat lintDebug
```
