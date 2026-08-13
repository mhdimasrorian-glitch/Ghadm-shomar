package com.begardesh.app

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class Challenge(val id: String, val title: String, val target: Int, var progress: Int, val rewardCoins: Int, var claimed: Boolean = false)
data class StoreItem(val id: String, val title: String, val priceCoins: Int, val category: String)
data class Transaction(val label: String, val amount: String, val positive: Boolean)

class MainActivity : ComponentActivity() {

    private lateinit var health: HealthConnectClient

    // ---- in-memory app state (نمونه محلی؛ منبع واقعی باید سرور باشد) ----
    private var coinBalance = 0
    private var stepsToday = 0
    private var dailyRewardClaimed = false
    private var referralCode = ""
    private var inviteCount = 0

    private val challenges = mutableListOf(
        Challenge("c1", "۵۰۰۰ قدم امروز", 5000, 0, 50),
        Challenge("c2", "۳ روز متوالی پیاده‌روی", 3, 0, 150),
        Challenge("c3", "۱۰۰۰۰ قدم در یک روز", 10000, 0, 120)
    )

    private val storeItems = listOf(
        StoreItem("s1", "۲۰ هزار تومان شارژ", 500, "شارژ"),
        StoreItem("s2", "۱ گیگابایت اینترنت", 350, "اینترنت"),
        StoreItem("s3", "کد تخفیف باشگاه ورزشی", 800, "ورزش"),
        StoreItem("s4", "بسته تغذیه سالم", 600, "تغذیه")
    )

    private val transactions = mutableListOf<Transaction>()

    private val permissionLauncher = registerForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.contains(HealthPermission.getReadPermission(StepsRecord::class))) readTodaySteps()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        health = HealthConnectClient.getOrCreate(this)
        referralCode = generateReferralCode()

        setupTabs()
        setupHomeSection()
        setupChallengesSection()
        setupStoreSection()
        setupReferralSection()
        setupWalletSection()

        showSection(R.id.sectionHome)
        renderChallenges()
        renderStore()
        renderReferral()
        renderWallet()
        scheduleBackgroundSync()
    }

    // ---------------- Tabs ----------------

    private fun setupTabs() {
        findViewById<Button>(R.id.tabHome).setOnClickListener { showSection(R.id.sectionHome) }
        findViewById<Button>(R.id.tabChallenges).setOnClickListener { showSection(R.id.sectionChallenges) }
        findViewById<Button>(R.id.tabStore).setOnClickListener { showSection(R.id.sectionStore) }
        findViewById<Button>(R.id.tabReferral).setOnClickListener { showSection(R.id.sectionReferral) }
        findViewById<Button>(R.id.tabWallet).setOnClickListener { showSection(R.id.sectionWallet) }
    }

    private fun showSection(sectionId: Int) {
        listOf(R.id.sectionHome, R.id.sectionChallenges, R.id.sectionStore, R.id.sectionReferral, R.id.sectionWallet)
            .forEach { findViewById<View>(it).visibility = if (it == sectionId) View.VISIBLE else View.GONE }
        findViewById<ScrollView>(R.id.scrollRoot).scrollTo(0, 0)
    }

    // ---------------- Home / Steps ----------------

    private fun setupHomeSection() {
        findViewById<Button>(R.id.otp).setOnClickListener {
            // در نسخه واقعی: POST ApiContract.REQUEST_OTP سپس نمایش فیلد کد
            findViewById<EditText>(R.id.code).visibility = View.VISIBLE
            findViewById<TextView>(R.id.status).text = "کد ارسال شد؛ آن را وارد کنید"
        }
        findViewById<Button>(R.id.sync).setOnClickListener { requestHealthPermission() }
        findViewById<Button>(R.id.claimDaily).setOnClickListener { claimDailyReward() }
        updateCoinBadge()
    }

    private fun requestHealthPermission() {
        val required = setOf(HealthPermission.getReadPermission(StepsRecord::class))
        lifecycleScope.launch {
            val granted = health.permissionController.getGrantedPermissions()
            if (granted.containsAll(required)) readTodaySteps() else permissionLauncher.launch(required)
        }
    }

    private fun readTodaySteps() {
        lifecycleScope.launch {
            try {
                val now = Instant.now()
                val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
                val result = health.aggregate(
                    AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, now))
                )
                val steps = (result[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
                onStepsUpdated(steps)
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "خواندن قدم‌ها ناموفق بود.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onStepsUpdated(steps: Int) {
        stepsToday = steps
        findViewById<TextView>(R.id.steps).text = "$steps قدم"
        // سکه‌دهی نمایشی؛ در نسخه واقعی سرور بر اساس /steps/sync محاسبه و اعتبارسنجی می‌کند.
        val earnedCoins = minOf(steps / 100, 1000)
        findViewById<TextView>(R.id.todayCoins).text = "امروز: $earnedCoins سکه کسب شده"
        updateChallengeProgressFromSteps(steps)
    }// ---------------- Challenges ----------------

    private fun setupChallengesSection() { /* container آماده است؛ render در renderChallenges */ }

    private fun updateChallengeProgressFromSteps(steps: Int) {
        challenges.forEach { c ->
            if (!c.claimed && (c.title.contains("قدم"))) c.progress = minOf(steps, c.target)
        }
        renderChallenges()
    }

    private fun renderChallenges() {
        val container = findViewById<LinearLayout>(R.id.challengesList)
        container.removeAllViews()
        challenges.forEach { c ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            }
            val title = TextView(this).apply { text = c.title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
            val progress = TextView(this).apply { text = "${c.progress} / ${c.target} — پاداش: ${c.rewardCoins} سکه"; textSize = 13f; setPadding(0, 8, 0, 8) }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = c.target; progressValue(c.progress)
            }
            val claimBtn = Button(this).apply {
                text = if (c.claimed) "دریافت شد" else "دریافت جایزه"
                isEnabled = !c.claimed && c.progress >= c.target
                setOnClickListener {
                    c.claimed = true
                    addCoins(c.rewardCoins, c.title)
                    renderChallenges()
                }
            }
            row.addView(title); row.addView(progress); row.addView(bar); row.addView(claimBtn)
            container.addView(row)
        }
    }

    private fun ProgressBar.progressValue(v: Int) { this.progress = v }

    // ---------------- Store ----------------

    private fun setupStoreSection() { /* container آماده است؛ render در renderStore */ }

    private fun renderStore() {
        val container = findViewById<LinearLayout>(R.id.storeList)
        container.removeAllViews()
        storeItems.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(this).apply { text = item.title; textSize = 15f }
            val price = TextView(this).apply { text = "${item.priceCoins} سکه · ${item.category}"; textSize = 12f; setPadding(0, 6, 0, 0) }
            info.addView(title); info.addView(price)
            val buyBtn = Button(this).apply {
                text = "خرید"
                setOnClickListener { purchaseItem(item) }
            }
            row.addView(info); row.addView(buyBtn)
            container.addView(row)
        }
    }

    private fun purchaseItem(item: StoreItem) {
        if (coinBalance < item.priceCoins) {
            Toast.makeText(this, "سکه کافی ندارید.", Toast.LENGTH_SHORT).show()
            return
        }
        // در نسخه واقعی: POST ApiContract.STORE_PURCHASE و انتظار تایید سرور قبل از کسر سکه
        coinBalance -= item.priceCoins
        transactions.add(0, Transaction(item.title, "-${item.priceCoins} سکه", false))
        updateCoinBadge(); renderWallet()
        Toast.makeText(this, "«${item.title}» خریداری شد.", Toast.LENGTH_SHORT).show()
    }

    // ---------------- Referral ----------------

    private fun setupReferralSection() {
        findViewById<Button>(R.id.copyReferral).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("referral", referralCode))
            Toast.makeText(this, "کد کپی شد.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.applyReferral).setOnClickListener {
            val code = findViewById<EditText>(R.id.referralInput).text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, "کد معرف را وارد کنید.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // در نسخه واقعی: POST ApiContract.REFERRAL_APPLY و اعتبارسنجی سمت سرور
            addCoins(100, "استفاده از کد معرف")
            findViewById<EditText>(R.id.referralInput).text.clear()
            Toast.makeText(this, "کد معرف ثبت شد و ۱۰۰ سکه دریافت کردید.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderReferral() {
        findViewById<TextView>(R.id.referralCode).text = referralCode
        findViewById<TextView>(R.id.inviteCount).text = "تعداد دعوت‌های موفق: $inviteCount"
    }

    private fun generateReferralCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    // ---------------- Wallet / Withdrawal ----------------

    private fun setupWalletSection() {
        findViewById<Button>(R.id.withdraw).setOnClickListener { submitWithdrawal() }
    }

    private fun submitWithdrawal() {
        val amountText = findViewById<EditText>(R.id.withdrawAmount).text.toString()
        val sheba = findViewById<EditText>(R.id.sheba).text.toString().trim()
        val amount = amountText.toLongOrNull()

        if (amount == null || amount <= 0) {
            Toast.makeText(this, "مبلغ برداشت را درست وارد کنید.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!sheba.startsWith("IR") || sheba.length < 24) {
            Toast.makeText(this, "شماره شبا معتبر نیست (باید با IR شروع شود).", Toast.LENGTH_SHORT).show()
            return
        }
        val availableRial = coinBalance.toLong() * COIN_TO_RIAL_RATE
        if (amount > availableRial) {
            Toast.makeText(this, "موجودی کافی نیست.", Toast.LENGTH_SHORT).show()
            return
        }
        // در نسخه واقعی: POST ApiContract.WITHDRAWALS { amountRial }
        // پرداخت واقعی فقط سمت سرور و از طریق درگاه/PSP مجاز انجام می‌شود؛
        // این‌جا فقط درخواست ثبت و در انتظار تایید نمایش داده می‌شود.
        val coinsUsed = (amount / COIN_TO_RIAL_RATE).toInt()
        coinBalance -= coinsUsed
        transactions.add(0, Transaction("درخواست برداشت", "-$amount ریال", false))
        updateCoinBadge(); renderWallet()
        findViewById<EditText>(R.id.withdrawAmount).text.clear()
        Toast.makeText(this, "درخواست برداشت ثبت شد و در انتظار تایید است.", Toast.LENGTH_LONG).show()
    }

    private fun renderWallet() {
        val rial = coinBalance.toLong() * COIN_TO_RIAL_RATE
        findViewById<TextView>(R.id.walletBalance).text = "موجودی: $coinBalance سکه (معادل $rial ریال)"

        val container = findViewById<LinearLayout>(R.id.txHistory)
        container.removeAllViews()
        if (transactions.isEmpty()) {
            container.addView(TextView(this).apply { text = "هنوز تراکنشی ثبت نشده."; setPadding(0, 8, 0, 8) })
        }
        transactions.take(20).forEach { tx ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 16, 16, 16)
                setBackgroundColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 8) }
            }
            val label = TextView(this).apply { text = tx.label; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val amount = TextView(this).apply { text = tx.amount; setTextColor(if (tx.positive) 0xFF16A34A.toInt() else 0xFFDC2626.toInt()) }
            row.addView(label); row.addView(amount)
            container.addView(row)
        }
    }

    // ---------------- Shared helpers ----------------

    private fun addCoins(amount: Int, reason: String) {
        coinBalance += amount
        transactions.add(0, Transaction(reason, "+$amount سکه", true))
        updateCoinBadge(); renderWallet()
    }

    private fun updateCoinBadge() {
        findViewById<TextView>(R.id.coinBadge).text = "$coinBalance سکه"
    }

    // ---------------- Background sync ----------------

    private fun scheduleBackgroundSync() {
        val request = PeriodicWorkRequest.Builder(StepSyncWorker::class.java, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "begardesh-step-sync", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}

class StepSyncWorker(appContext: android.content.Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // نقطه اتصال به Health Connect + POST /steps/sync در پس‌زمینه.
        // محاسبه نهایی سکه فقط سمت سرور انجام می‌شود تا از جعل امتیاز جلوگیری شود.
        return Result.success()
    }
}

    private fun claimDailyReward() {
        if (dailyRewardClaimed) {
            Toast.makeText(this, "امروز قبلاً جایزه‌تان را دریافت کرده‌اید.", Toast.LENGTH_SHORT).show()
            return
        }
        dailyRewardClaimed = true
        addCoins(30, "جایزه روزانه")
        findViewById<TextView>(R.id.dailyRewardText).text = "جایزه امروز دریافت شد ✓"
        findViewById<Button>(R.id.claimDaily).isEnabled = false
    }
