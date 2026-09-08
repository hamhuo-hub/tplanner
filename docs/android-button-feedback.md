# 圆角按钮按压反馈

2026-09-08，用户反馈 Phone Inbox「按钮的阴影是方形的」。源码确认截图顶部的视图选择胶囊和圆形加号均无 elevation / shadow；背景与边框采用圆角，但 clickable 的按压反馈没有相同的形状裁切。

修复规则：在尺寸及外间距之后、背景 / clickable 之前加 `clip(shape)`，使用与背景及边框相同的 shape。保留原控件尺寸、布局、点击行为和令牌；不通过禁用反馈掩盖问题。

| 位置 | 修复 |
| --- | --- |
| TaskWidget 视图选择 | RoundedCornerShape(RadiusPillDp)，由同文件的重复任务负责人协调应用 |
| TaskWidget 顶部加号 | CircleShape，由同文件负责人协调应用 |
| PhoneTabBar 导航项 | 复用 itemShape |
| TimelineAddButton | CircleShape |
| TimelineStatusStrip 状态按钮 | 复用 shape |

共享 TPlannerTaskUnitView 的 RippleDrawable 已有圆角内容层，无显式 mask 时以内容层合成结果遮罩；原实现无需修改。[Android RippleDrawable 文档](https://developer.android.com/reference/android/graphics/drawable/RippleDrawable)

另外发现 ScheduleItemEditor 的类型 / 列表 / 重复次数 / 提醒选项、ListAssignmentChip、TimeChip 和分类选择也应使用同形状裁切，已交同文件负责人处理，避免并行编辑冲突。

时间轴事件卡片不在本次直接加 clip：最小事件高 24dp，紧凑模式上下 padding 共 6dp，但内部冲突徽标高 22dp；直接裁切整张卡片会影响现有徽标。该位置需要与既有时间轴几何例外一并处理，不能套用普通按钮的修复。

验证：修复截图控件及导航 / 加号后，`:app:assembleDebug` 通过。当前 ADB 无在线设备，也无已配置 AVD，故不声明已完成真机按压截图验收；APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
