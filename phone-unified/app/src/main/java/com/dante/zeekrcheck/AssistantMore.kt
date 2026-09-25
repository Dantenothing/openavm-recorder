package com.dante.zeekrcheck

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    AssistantCard {
        UiText(title, fontSize = 13.sp, color = AssistantGreen, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable internal fun AssistantMoreScreen(sessionSaved: Boolean, homeSet: Boolean, nickname: String,
    onNavigate: (String) -> Unit) {
    SettingsGroup("开始使用与连接") {
        FeatureRow("新手指南", "继续上次步骤，或选择需要的帮助") { onNavigate("guide") }
        FeatureRow("账号与连接", if (sessionSaved) "登录已保存在本机" else "登录极氪账号并选择车辆") { onNavigate("account") }
        FeatureRow("车机连接与配对", "接收 Recorder 录像，与车辆账号分开设置") { onNavigate("connection") }
    }
    SettingsGroup("日常偏好") {
        FeatureRow("停车位置与家", if (homeSet) "家的位置已保存" else "匹配地址或使用当前位置") { onNavigate("places") }
        FeatureRow("智能备车设置", "目标温度、座椅舒适与最长时长") { onNavigate("preferences") }
        FeatureRow("桌面卡片", "预览 2×2 / 4×1 / 4×2 / 4×3") { onNavigate("widgets") }
        FeatureRow("车辆外观", "车身颜色、车牌与桌面显示") { onNavigate("appearance") }
        FeatureRow("车辆昵称", nickname, rawDetail = true) { onNavigate("nickname") }
        FeatureRow("语言 / Language", "简体中文 / English") { onNavigate("language") }
    }
    RefreshExplanation()
    SettingsGroup("自动化与记录") {
        FeatureRow("停车守护", "离家开启、到家关闭与本次暂停") { onNavigate("guard") }
        FeatureRow("后台与自动化", "检查权限、自动更新和运行状态") { onNavigate("background") }
        FeatureRow("操作记录", "查看执行结果与原因") { onNavigate("history") }
    }
    SettingsGroup("影像与工具") {
        FeatureRow("哨兵 USB 录像", "读取、回看和导出原厂哨兵视频") { onNavigate("sentry_usb") }
        FeatureRow("解闭锁音效制作", "裁切、试听并导出车辆音效文件") { onNavigate("sounds") }
        FeatureRow("影像设置", "本地存储、回收站与配对") { onNavigate("media_settings") }
    }
    SettingsGroup("帮助与诊断") {
        FeatureRow("换手机使用", "首次安装、重新连接与旧手机设置") { onNavigate("new_phone") }
        FeatureRow("本车能力检查", "检查账号权限、导出脱敏报告") { onNavigate("diagnostics") }
        HorizontalDivider()
        UiText("OpenAVM ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = AssistantMuted)
        UiText("独立开发的非官方应用。账号配置保存在本机，权限在使用对应功能时申请。", fontSize = 12.sp, color = AssistantMuted)
    }
}

@Composable internal fun NewPhoneChecklist(onGuide: () -> Unit) {
    AssistantCard {
        UiText("在新手机上开始", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        UiText("安装包包含应用，不包含旧手机的账号、家地址或录像。")
        FeatureRow("1 · 连接车辆", "连接适配已内置，登录极氪账号并选择车辆。") { onGuide() }
        UiText("2 · 重新设置偏好", fontWeight = FontWeight.SemiBold)
        UiText("设置家的位置与识别范围，核对备车偏好，再添加桌面卡片。", color = AssistantMuted)
        UiText("3 · 交接自动化", fontWeight = FontWeight.SemiBold)
        UiText("先暂停旧手机的预约、离家守护和到家关闭，再在新手机开启需要的规则与后台权限。", color = AssistantMuted)
        UiText("4 · 配对车机录像（可选）", fontWeight = FontWeight.SemiBold)
        UiText("在同一网络下重新配对这台手机，发送一段已有录像并确认能播放。", color = AssistantMuted)
        UiText("最后，在方便观察车辆时试一次备车和停车守护，确认通知、结果与习惯一致。", color = AssistantGreen)
    }
}
