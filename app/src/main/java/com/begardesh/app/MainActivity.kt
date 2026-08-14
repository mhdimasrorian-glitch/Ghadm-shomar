package com.begardesh.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.work.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class Challenge(val id: String, val title: String, val target: Int, var progress: Int, val rewardCoins: Int, var claimed: Boolean = false)
data class StoreItem(val id: String, val title: String, val priceCoins: Int, val category: String)
data class Transaction(val label: String, val amount: String, val positive: Boolean)
data class SportSession(val type: String, val durationSeconds: Long, val coins: Int)

class MainActivity : ComponentActivity(), SensorEventListener {

    // ---- step counter (حسگر واقعی گوشی) ----
    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepBaseline: Float? = null

    // ---- in-memory app state (نمونه محلی؛ منبع واقعی باید سرور باشد) ----
    private var coinBalance = 0
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

    // ---- sports session ----
    private val sportSessions = mutableListOf<SportSession>()
    private var selectedSport: String? = null
    private var sportRunning = false
    private var sportStartTime = 0L
    private var sportElapsedBeforePause = 0L
    private val sportHandler = Handler(Looper.getMainLooper())
    private val sportTick: Runnable = object : Runnable {
        override fun run() {
            if (sportRunning) {
                val elapsed = sportElapsedBeforePause + (System.currentTimeMillis() - sportStartTime) / 1000
                findViewById<TextView>(R.id.sportTimer).text = formatDuration(elapsed)
                sportHandler.postDelayed(this, 1000)
            }
        }
    }

    // ---- map ----
    private lateinit var mapView: MapView

    private val activityRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startStepSensor() else Toast.makeText(this, "بدون این مجوز، قدم‌شمار کار نمی‌کند.", Toast.LENGTH_SHORT).show() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results.values.any { it }) locateUser() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        referralCode = generateReferralCode()

        setupTabs()
        setupHomeSection()
        setupSportsSection()
        setupChallengesSection()
        setupStoreSection()
        setupMapSection()
        setupReferralSection()
        setupWalletSection()

        showSection(R.id.sectionHome)
        renderChallenges()
        renderStore()
        renderReferral()
        renderWallet()
        renderSportsLog()
        scheduleBackgroundSync()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (stepCounterSensor != null && hasActivityRecognitionPermission()) startStepSensor()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        sensorManager.unregisterListener(this)
    }

    // ---------------- Tabs ----------------

    private fun setupTabs() {
        findViewById<Button>(R.id.tabHome).setOnClickListener { showSection(R.id.sectionHome) }
        findViewById<Button>(R.id.tabSports).setOnClickListener { showSection(R.id.sectionSports) }
        findViewById<Button>(R.id.tabChallenges).setOnClickListener { showSection(R.id.sectionChallenges) }
        findViewById<Button>(R.id.tabStore).setOnClickListener { showSection(R.id.sectionStore) }
        findViewById<Button>(R.id.tabMap).setOnClickListener { showSection(R.id.sectionMap) }
        findViewById<Button>(R.id.tabReferral).setOnClickListener { showSection(R.id.sectionReferral) }
        findViewById<Button>(R.id.tabWallet).setOnClickListener { showSection(R.id.sectionWallet) }
    }

    private fun showSection(sectionId: Int) {
        val sections = listOf(R.id.sectionHome, R.id.sectionSports, R.id.sectionChallenges, R.id.sectionStore, R.id.sectionMap, R.id.sectionReferral, R.id.sectionWallet)
        sections.forEach { findViewById<View>(it).visibility = if (it == sectionId) View.VISIBLE else View.GONE }
        findViewById<ScrollView>(R.id.scrollRoot).scrollTo(0, 0)

        val tabs = mapOf(
            R.id.sectionHome to R.id.tabHome, R.id.sectionSports to R.id.tabSports,
            R.id.sectionChallenges to R.id.tabChallenges, R.id.sectionStore to R.id.tabStore,
            R.id.sectionMap to R.id.tabMap, R.id.sectionReferral to R.id.tabReferral,
            R.id.sectionWallet to R.id.tabWallet
        )
        tabs.forEach { (section, tabId) ->
            findViewById<Button>(tabId).setTextColor(if (section == sectionId) 0xFFA855F7.toInt() else 0xFF64748B.toInt())
        }
    }

    // ---------------- Home / Steps (حسگر واقعی گوشی) ----------------

    private fun setupHomeSection() {
        findViewById<Button>(R.id.otp).setOnClickListener {
            // در نسخه واقعی: POST ApiContract.REQUEST_OTP سپس نمایش فیلد کد
            findViewById<EditText>(R.id.code).visibility = View.VISIBLE
            findViewById<TextView>(R.id.status).text = "کد ارسال شد؛ آن را وارد کنید"
        }
        findViewById<Button>(R.id.sync).setOnClickListener { requestStepPermission() }
        findViewById<Button>(R.id.claimDaily).setOnClickListener { claimDailyReward() }
        updateCoinBadge()

        if (stepCounterSensor == null) {
            findViewById<TextView>(R.id.stepsSource).text = "این گوشی حسگر قدم‌شمار ندارد."
            findViewById<Button>(R.id.sync).isEnabled = false
        } else if (hasActivityRecognitionPermission()) {
            findViewById<TextView>(R.id.stepsSource).text = "قدم‌شمار فعال است"
            findViewById<Button>(R.id.sync).isEnabled = false
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun requestStepPermission() {
        if (stepCounterSensor == null) return
        if (hasActivityRecognitionPermission()) {
            startStepSensor()
        } else {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun startStepSensor() {
        stepCounterSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        findViewById<TextView>(R.id.stepsSource).text = "قدم‌شمار فعال است"
        findViewById<Button>(R.id.sync).isEnabled = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val total = event.values[0]
        if (stepBaseline == null) stepBaseline = total
        val steps = (total - (stepBaseline ?: total)).toInt()
        onStepsUpdated(steps)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* unused */ }

    private fun onStepsUpdated(steps: Int) {
        findViewById<TextView>(R.id.steps).text = "$steps قدم"
        // سکه‌دهی نمایشی؛ در نسخه واقعی سرور بر اساس /steps/sync محاسبه و اعتبارسنجی می‌کند.
        val earnedCoins = minOf(steps / 100, 1000)
        findViewById<TextView>(R.id.todayCoins).text = "امروز: $earnedCoins سکه کسب شده"
        updateChallengeProgressFromSteps(steps)
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

    // ---------------- Sports ----------------

    private fun setupSportsSection() {
        val pills = mapOf(
            R.id.sportWalk to "پیاده‌روی", R.id.sportRun to "دویدن",
            R.id.sportBike to "دوچرخه‌سواری", R.id.sportGym to "باشگاه"
        )
        pills.forEach { (id, label) ->
            findViewById<TextView>(id).setOnClickListener { selectSport(label, pills.keys) }
        }
        findViewById<Button>(R.id.sportToggle).setOnClickListener { toggleSport() }
    }

    private fun selectSport(label: String, allIds: Set<Int>) {
        if (sportRunning) {
            Toast.makeText(this, "ابتدا فعالیت جاری را متوقف کنید.", Toast.LENGTH_SHORT).show()
            return
        }
        selectedSport = label
        allIds.forEach { id ->
            val view = findViewById<TextView>(id)
            val isSelected = view.text.toString() == label
            view.setBackgroundResource(if (isSelected) R.drawable.bg_button else R.drawable.bg_pill_pink)
            view.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFF9333EA.toInt())
        }
        findViewById<TextView>(R.id.sportSelected).text = "فعالیت انتخاب‌شده: $label"
    }

    private fun toggleSport() {
        val sport = selectedSport
        if (sport == null) {
            Toast.makeText(this, "یک فعالیت را انتخاب کنید.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!sportRunning) {
            sportRunning = true
            sportStartTime = System.currentTimeMillis()
            findViewById<Button>(R.id.sportToggle).text = "پایان فعالیت"
            sportHandler.post(sportTick)
        } else {
            sportRunning = false
            val totalSeconds = sportElapsedBeforePause + (System.currentTimeMillis() - sportStartTime) / 1000
            sportElapsedBeforePause = 0L
            findViewById<Button>(R.id.sportToggle).text = "شروع فعالیت"
            findViewById<TextView>(R.id.sportTimer).text = "۰۰:۰۰:۰۰"
            if (totalSeconds >= 30) {
                val coins = (totalSeconds / 60).toInt().coerceAtLeast(1) * 5
                sportSessions.add(0, SportSession(sport, totalSeconds, coins))
                addCoins(coins, "فعالیت: $sport")
                renderSportsLog()
            }
        }
    }

    private fun renderSportsLog() {
        val container = findViewById<LinearLayout>(R.id.sportsLog)
        container.removeAllViews()
        if (sportSessions.isEmpty()) {
            container.addView(TextView(this).apply { text = "هنوز فعالیتی ثبت نشده."; setTextColor(0xFF78716C.toInt()); setPadding(0, 8, 0, 8) })
            return
        }
        sportSessions.take(20).forEach { s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(20, 20, 20, 20)
                background = getDrawable(R.drawable.bg_card)
                elevation = 2f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
            }
            val label = TextView(this).apply { text = "${s.type} — ${formatDuration(s.durationSeconds)}"; setTextColor(0xFF581C87.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val coins = TextView(this).apply { text = "+${s.coins} سکه"; setTextColor(0xFF16A34A.toInt()) }
            row.addView(label); row.addView(coins)
            container.addView(row)
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    // ---------------- Challenges ----------------

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
                background = getDrawable(R.drawable.bg_card)
                elevation = 2f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            }
            val title = TextView(this).apply { text = c.title; textSize = 16f; setTextColor(0xFF581C87.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD) }
            val progress = TextView(this).apply { text = "${c.progress} / ${c.target} — پاداش: ${c.rewardCoins} سکه"; textSize = 13f; setTextColor(0xFF78716C.toInt()); setPadding(0, 8, 0, 8) }
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
                background = getDrawable(R.drawable.bg_card)
                elevation = 2f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(this).apply { text = item.title; textSize = 15f; setTextColor(0xFF581C87.toInt()) }
            val price = TextView(this).apply { text = "${item.priceCoins} سکه · ${item.category}"; textSize = 12f; setTextColor(0xFF78716C.toInt()); setPadding(0, 6, 0, 0) }
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

    // ---------------- Map ----------------

    private fun setupMapSection() {
        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(35.6892, 51.3890)) // مرکز پیش‌فرض: تهران
        findViewById<Button>(R.id.mapLocate).setOnClickListener { locateUser() }
    }

    private fun locateUser() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        overlay.enableMyLocation()
        overlay.runOnFirstFix {
            runOnUiThread {
                overlay.myLocation?.let { mapView.controller.animateTo(it) }
            }
        }
        mapView.overlays.add(overlay)
        mapView.invalidate()
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
            container.addView(TextView(this).apply { text = "هنوز تراکنشی ثبت نشده."; setTextColor(0xFF78716C.toInt()); setPadding(0, 8, 0, 8) })
        }
        transactions.take(20).forEach { tx ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 16, 16, 16)
                background = getDrawable(R.drawable.bg_card)
                elevation = 2f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 8) }
            }
            val label = TextView(this).apply { text = tx.label; setTextColor(0xFF581C87.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
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
        // نقطه اتصال به POST /steps/sync در پس‌زمینه.
        // محاسبه نهایی سکه فقط سمت سرور انجام می‌شود تا از جعل امتیاز جلوگیری شود.
        return Result.success()
    }
}
