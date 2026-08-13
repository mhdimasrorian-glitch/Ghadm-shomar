package com.begardesh.app

/**
 * قرارداد API نسخه نهایی «بگردش».
 *
 * Base URL را در BuildConfig/سرور واقعی تنظیم کنید.
 * برای production فقط HTTPS استفاده شود.
 * تمام محاسبات سکه/جایزه باید سمت سرور انجام و اعتبارسنجی شود؛
 * کلاینت فقط نمایش‌دهنده است و نباید منبع مورد اعتماد باشد.
 *
 * --- Auth ---
 * POST /auth/request-otp   { phone }
 * POST /auth/verify-otp    { phone, code, referralCode? }
 * POST /auth/refresh       { sessionId, refreshToken }
 * POST /auth/logout
 * GET  /me
 *
 * --- Steps & Coins ---
 * POST /steps/sync         { date, steps, source }
 * GET  /wallet              -> { coinBalance, rialBalance }
 * POST /rewards/daily/claim
 *
 * --- Challenges ---
 * GET  /challenges
 * POST /challenges/{id}/claim
 *
 * --- Store ---
 * GET  /store/items
 * POST /store/purchase      { itemId }
 *
 * --- Referral ---
 * GET  /referral/me         -> { code, inviteCount }
 * POST /referral/apply      { code }
 *
 * --- Wallet / Withdrawal ---
 * POST /profile/sheba       { sheba }
 * POST /withdrawals         { amountRial }
 * GET  /withdrawals         -> history
 */
object ApiContract {
    const val REQUEST_OTP = "/auth/request-otp"
    const val VERIFY_OTP = "/auth/verify-otp"
    const val REFRESH = "/auth/refresh"
    const val ME = "/me"

    const val STEPS_SYNC = "/steps/sync"
    const val WALLET = "/wallet"
    const val DAILY_CLAIM = "/rewards/daily/claim"

    const val CHALLENGES = "/challenges"
    fun challengeClaim(id: String) = "/challenges/$id/claim"

    const val STORE_ITEMS = "/store/items"
    const val STORE_PURCHASE = "/store/purchase"

    const val REFERRAL_ME = "/referral/me"
    const val REFERRAL_APPLY = "/referral/apply"

    const val SHEBA = "/profile/sheba"
    const val WITHDRAWALS = "/withdrawals"
}

/** نرخ تبدیل سکه به ریال. باید با سرور همسان باشد؛ اینجا فقط برای نمایش تخمینی است. */
const val COIN_TO_RIAL_RATE = 100
