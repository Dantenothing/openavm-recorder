package com.dante.zeekrcapabilitylab.transfer

import com.dante.zeekrcapabilitylab.util.Utils

fun phoneSecurityMessage(error: PhoneSecurityError): String = when (error) {
    PhoneSecurityError.PHONE_UPGRADE_REQUIRED -> Utils.t("Update the phone app to use secure transfers.", "请更新手机版以使用安全传输。")
    PhoneSecurityError.SECURE_PAIRING_REQUIRED -> Utils.t("Pair again once to enable secure phone transfers.", "请重新配对一次，以启用手机安全传输。")
    PhoneSecurityError.PHONE_IDENTITY_CHANGED -> Utils.t("Phone identity could not be verified. Check the phone and pair again if it has changed.", "无法验证手机身份。请检查手机，如已更换则重新配对。")
    PhoneSecurityError.AUTH_REVOKED -> Utils.t("This pairing is no longer valid. Pair the phone again.", "此配对已失效，请重新配对手机。")
    PhoneSecurityError.PAIR_CODE_REJECTED -> Utils.t("The pairing code is invalid or expired. Generate a new code on the phone.", "配对码无效或已过期，请在手机生成新码。")
    PhoneSecurityError.PAIR_RATE_LIMITED -> Utils.t("Too many pairing attempts. Reopen pairing on the phone.", "配对尝试过多，请在手机重新打开配对。")
    PhoneSecurityError.PAIRING_EXPIRED -> Utils.t("Identity confirmation expired. Check the phone fingerprint again.", "身份确认已过期，请重新核对手机指纹。")
    PhoneSecurityError.INVALID_PHONE_RESPONSE -> Utils.t("The phone returned an incompatible response. Update both apps.", "手机返回的响应不兼容，请更新两端应用。")
    PhoneSecurityError.CONNECTION_REPLACED -> Utils.t("The pairing changed. Reconnect using the current phone.", "配对已更改，请连接当前手机。")
    PhoneSecurityError.PHONE_UNAVAILABLE -> Utils.t("Phone unavailable. Check the hotspot and phone receiver.", "手机未连接，请检查热点和手机接收服务。")
}
