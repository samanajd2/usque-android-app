package com.warp.usque

import android.app.Activity
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.util.Log
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.divider.MaterialDivider
import usqueandroid.Usqueandroid
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val REQ_VPN = 1001
        private const val REQ_EXPORT_FILE = 9001
        private const val REQ_IMPORT_FILE = 9002
    }
    data class AppEntry(val label: String, val packageName: String)
    data class Profile(val sni: String, val endpoint: String, val port: Int, val http2: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("usque", MODE_PRIVATE) }
    private val configFile by lazy { File(filesDir, "config.json") }

    private lateinit var endpointInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var sniInput: TextInputEditText
    private lateinit var licenseKeyInput: TextInputEditText
    private lateinit var saveLicenseBtn: MaterialButton
    private lateinit var removeLicenseBtn: MaterialButton
    private lateinit var statusBanner: TextView
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var configStateText: TextView
    private lateinit var logText: TextView
    private lateinit var ipv4Text: TextView
    private lateinit var ipv6Text: TextView
    private lateinit var modeValue: TextView
    private lateinit var modeHint: TextView
    private lateinit var splitModeSwitch: MaterialSwitch
    private lateinit var useHttp2Switch: MaterialSwitch
    private lateinit var appSearchInput: TextInputEditText
    private lateinit var appSection: MaterialCardView
    private lateinit var appListContainer: LinearLayout
    private lateinit var appCountText: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var defaultBtn: MaterialButton
    private lateinit var homeProfileSpinner: Spinner
    private lateinit var currentProfileText: TextView
    private lateinit var profileSpinner: Spinner
    private lateinit var profileNameInput: TextInputEditText
    private lateinit var saveNewProfileBtn: MaterialButton
    private lateinit var overwriteProfileBtn: MaterialButton
    private lateinit var deleteProfileBtn: MaterialButton
    private lateinit var exportConfigBtn: MaterialButton
    private lateinit var importConfigBtn: MaterialButton
    private lateinit var languageRuButton: MaterialButton
    private lateinit var languageEnButton: MaterialButton
    private lateinit var selectAllAppsBtn: MaterialButton
    private lateinit var clearAllAppsBtn: MaterialButton

    private var useEnglish = false
    private var tunnelStopping = false
    private var vpnRunning = false
    private var vpnGranted = false
    private var configDirty = false
    private var appsLoaded = false
    private var suppressProfileSelection = false
    private var currentPageIndex = 0
    private var pageSwitcher: ((Int) -> Unit)? = null
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSpeedTs = 0L
    private var lastToggleClickAt = 0L
    private var tunnelReallyConnected = false

    override fun onStart() {
        super.onStart()
        UsqueVpnService.stateListener = { state, message ->
            DiagLog.add("UI", "stateListener: $state ${message}".trim())
            runOnUiThread {
                when (state) {
                    "connected" -> {
                        tunnelReallyConnected = true
                        refreshState(if (splitModeSwitch.isChecked) tr("Раздельный режим", "Split mode") else tr("Глобальный режим", "Global Mode"))
                    }
                    "reconnecting" -> {
                        tunnelReallyConnected = false
                        refreshState(tr("Переподключение… $message", "Reconnecting… $message"))
                    }
                    "disconnected" -> {
                        vpnRunning = false
                        tunnelReallyConnected = false
                        tunnelStopping = false
                        refreshState(tr("Остановлено", "Stopped"))
                    }
                }
            }
        }
        vpnRunning = UsqueVpnService.isServiceRunning
        tunnelReallyConnected = UsqueVpnService.isServiceConnected
        refreshState()
    }

    override fun onStop() {
        super.onStop()
        UsqueVpnService.stateListener = null
    }

    private var diagTickCount = 0
    private val speedTicker = object : Runnable {
        override fun run() {
            updateSpeedLine()
            diagTickCount++
            if (vpnRunning && tunnelReallyConnected && diagTickCount % 5 == 0) {
                DiagLog.add("Stats", runCatching { Usqueandroid.getPacketStats() }.getOrDefault("n/a"))
            }
            if (vpnRunning && tunnelReallyConnected && diagTickCount % 15 == 0) {
                val samples = runCatching { Usqueandroid.getPacketSamples() }.getOrDefault("")
                if (samples.isNotBlank()) {
                    DiagLog.add("Samples", "\n" + samples)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val profiles = linkedMapOf<String, Profile>()
    private val allApps = mutableListOf<AppEntry>()
    private val selectedPackages = linkedSetOf<String>()

    private val bg = Color.rgb(255, 248, 242)
    private val surface = Color.rgb(255, 255, 255)
    private val surface2 = Color.rgb(255, 238, 224)
    private val primary = Color.rgb(246, 196, 155) // soft orange / светло-оранжевый
    private val darkAccent = Color.rgb(205, 126, 73)
    private val mango = Color.rgb(255, 224, 195)
    private val onPrimary = Color.rgb(82, 49, 28)
    private val textColor = Color.rgb(48, 39, 32)
    private val subText = Color.rgb(111, 91, 76)
    private val outline = Color.rgb(232, 211, 194)
    private val green = Color.rgb(49, 145, 104)
    private val danger = Color.rgb(188, 77, 77)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Usque ${appVersionName()}"
        useEnglish = prefs.getBoolean("useEnglish", false)
        buildUi()
        loadSavedState()
        loadLicenseKey()
        refreshAppList()
        refreshState()
        resetSpeedMeter()
        handler.post(speedTicker)
    }

    override fun onDestroy() {
        handler.removeCallbacks(speedTicker)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX
                swipeDownY = ev.rawY
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - swipeDownX
                val dy = ev.rawY - swipeDownY
                val threshold = dp(72)
                if (kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f) {
                    if (dx < 0 && currentPageIndex < 2) { pageSwitcher?.invoke(currentPageIndex + 1); return true }
                    if (dx > 0 && currentPageIndex > 0) { pageSwitcher?.invoke(currentPageIndex - 1); return true }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun tr(ru: String, en: String): String = if (useEnglish) en else ru
    private fun setLanguage(en: Boolean) {
        useEnglish = en
        prefs.edit().putBoolean("useEnglish", useEnglish).apply()
        buildUi()
        loadSavedState()
        refreshAppList()
        refreshState()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Usque ${appVersionName()}"
            subtitle = null
            setTitleTextColor(textColor)
            setSubtitleTextColor(subText)
            setBackgroundColor(Color.rgb(255, 252, 249))
            elevation = dp(2).toFloat()
            setPadding(dp(8), 0, dp(8), 0)
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.rgb(255, 252, 249))
        }
        val pageHost = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        fun pageScroll(child: View): ScrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            (child.parent as? ViewGroup)?.removeView(child)
            addView(child)
        }
        val overviewTab = tabButton(tr("Главная", "Overview"))
        val configTab = tabButton(tr("Настройки", "Config"))
        val appsTab = tabButton(tr("Приложения", "Apps"))
        val tabsList = listOf(overviewTab, configTab, appsTab)
        tabsList.forEachIndexed { index, b ->
            // ИСПРАВЛЕНО: Уменьшаем внутренние боковые отступы до 4dp, чтобы длинные русские слова влезли целиком
            b.setPadding(dp(4), b.paddingTop, dp(4), b.paddingBottom)
            
            tabs.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (index < 2) rightMargin = dp(8) })
        }

        val homePage = buildHomePage()
        val configPage = buildConfigPage()
        val appsPage = buildAppsPage()
        val titles = listOf("Usque ${appVersionName()}", tr("Настройки подключения", "Connection Config"), tr("Выбор приложений", "Select Apps"))
        val pages = listOf(homePage, configPage, appsPage)
        fun showIndex(index: Int) {
            val safe = index.coerceIn(0, pages.lastIndex)
            currentPageIndex = safe
            toolbar.title = titles[safe]
            toolbar.subtitle = null
            tabsList.forEachIndexed { i, b -> setTabSelected(b, i == safe) }
            if (safe == 2) ensureAppsLoaded()
            pageHost.removeAllViews()
            pageHost.addView(pageScroll(pages[safe]), FrameLayout.LayoutParams(-1, -1))
        }

        overviewTab.setOnClickListener { showIndex(0) }
        configTab.setOnClickListener { showIndex(1) }
        appsTab.setOnClickListener { showIndex(2) }
        pageSwitcher = { showIndex(it) }
        showIndex(0)

        defaultBtn.setOnClickListener {
            val defaultEndpoint = Usqueandroid.getDefaultEndpoint(configFile.absolutePath, useHttp2Switch.isChecked)
            endpointInput.setText(parseEndpointHost(defaultEndpoint))
            portInput.setText(parseEndpointPort(defaultEndpoint, 443).toString())
            refreshState(tr("Загружен endpoint по умолчанию", "Default endpoint loaded"))
        }
        saveLicenseBtn.setOnClickListener {
            val key = licenseKeyInput.text?.toString()?.trim().orEmpty()
            if (key.isBlank()) { toast(tr("Введите ключ", "Enter a key")); return@setOnClickListener }
            if (!hasValidRegistration()) { toast(tr("Сначала дождитесь регистрации", "Wait for registration first")); return@setOnClickListener }
            Thread {
                val err = runCatching { Usqueandroid.setLicenseKey(configFile.absolutePath, key) }.getOrDefault("error")
                val newKey = runCatching { Usqueandroid.getLicenseKey(configFile.absolutePath) }.getOrDefault("")
                runOnUiThread {
                    licenseKeyInput.setText(newKey)
                    if (err.isNullOrBlank()) toast(tr("Ключ сохранён", "Key saved"))
                    else toast(tr("Ошибка: $err", "Error: $err"))
                }
            }.start()
        }
        removeLicenseBtn.setOnClickListener {
            if (licenseKeyInput.text.isNullOrBlank()) { toast(tr("Ключ и так не установлен", "No key is set")); return@setOnClickListener }
            if (!hasValidRegistration()) { toast(tr("Сначала дождитесь регистрации", "Wait for registration first")); return@setOnClickListener }
            Thread {
                val err = runCatching { Usqueandroid.removeLicenseKey(configFile.absolutePath) }.getOrDefault("error")
                val newKey = runCatching { Usqueandroid.getLicenseKey(configFile.absolutePath) }.getOrDefault("")
                runOnUiThread {
                    licenseKeyInput.setText(newKey)
                    if (err.isNullOrBlank()) toast(tr("Ключ удалён", "Key removed"))
                    else toast(tr("Ошибка: $err", "Error: $err"))
                }
            }.start()
        }
        saveNewProfileBtn.setOnClickListener { saveAsNewProfile() }
        overwriteProfileBtn.setOnClickListener { overwriteSelectedProfile() }
        deleteProfileBtn.setOnClickListener { deleteSelectedProfile() }
        exportConfigBtn.setOnClickListener { exportAllConfigToFile() }
        importConfigBtn.setOnClickListener { importAllConfigFromFile() }
        connectButton.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastToggleClickAt < 800) return@setOnClickListener
            lastToggleClickAt = now
            if (vpnRunning) disconnectVpn() else connectVpn()
        }
        sniInput.addTextChangedListener(dirtyWatcher())
        endpointInput.addTextChangedListener(dirtyWatcher())
        portInput.addTextChangedListener(dirtyWatcher())
        appSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshAppList() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun buildHomePage(): View {
        val content = pageContent()
        val hero = card().apply { setCardBackgroundColor(surface2) }
        val heroBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val eyebrow = TextView(this).apply {
            text = tr("СТАТУС VPN", "VPN STATUS")
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(onPrimary)
            setTypeface(null, Typeface.BOLD)
        }
        statusBanner = TextView(this).apply {
            text = tr("Отключено", "Disconnected")
            textSize = 28f
            setTextColor(onPrimary)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }
        statusText = TextView(this).apply {
            text = tr("Статус: Отключено", "Status: Disconnected")
            textSize = 14f
            setTextColor(subText)
            setPadding(0, dp(2), 0, dp(2))
        }
        speedText = TextView(this).apply {
            text = "↓ 0 B/s   ↑ 0 B/s"
            textSize = 14f
            setTextColor(onPrimary)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        logText = TextView(this).apply {
            text = tr("Нажмите «Подключить». При первом запуске профиль зарегистрируется автоматически, и VPN запустится.", "Tap Connect. First launch will register automatically and start VPN.")
            textSize = 12f
            setTextColor(darkAccent)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, 0, 0, dp(10))
            setOnClickListener { showDiagLogDialog() }
        }
        homeProfileSpinner = Spinner(this).apply {
            background = round(surface, dp(16), outline)
            setPadding(dp(10), 0, dp(10), 0)
        }
        connectButton = MaterialButton(this).apply {
            text = tr("Подключить VPN", "Connect VPN")
            textSize = 15f
            gravity = Gravity.CENTER
            setSingleLine(false)
            maxLines = 2
            setTextColor(Color.rgb(255, 255, 255))
            backgroundTintList = android.content.res.ColorStateList.valueOf(darkAccent)
            cornerRadius = dp(24)
            minHeight = dp(52)
            isAllCaps = false
        }
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actionRow.addView(connectButton, LinearLayout.LayoutParams(-1, dp(52)))
        heroBox.addView(eyebrow)
        heroBox.addView(statusBanner)
        heroBox.addView(statusText)
        heroBox.addView(speedText)
        heroBox.addView(logText)
        heroBox.addView(homeProfileSpinner, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(10) })
        heroBox.addView(actionRow)
        hero.addView(heroBox)
        content.addView(hero, LinearLayout.LayoutParams(-1, -2))

        content.addView(sectionTitle(tr("Режим проксирования", "Proxy Mode")))
        val modeCard = card()
        val modeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        modeValue = TextView(this).apply { text = tr("Глобальный режим", "Global Mode"); textSize = 18f; setTextColor(textColor); setTypeface(null, Typeface.BOLD) }
        modeHint = TextView(this).apply { text = tr("Все приложения идут через VPN. При переключении в раздельный режим через VPN будут идти только выбранные приложения.", "All apps use VPN. In split mode, only selected apps use VPN."); textSize = 14f; setTextColor(subText); setPadding(0, dp(4), 0, dp(6)) }
        splitModeSwitch = MaterialSwitch(this).apply {
            text = tr("Включить раздельное туннелирование", "Enable split tunneling")
            textSize = 16f
            setTextColor(textColor)
            setPadding(0, dp(2), 0, 0)
            setOnCheckedChangeListener { _, _ ->
                markDirty(); saveSelectedApps(); updateModeUi(); refreshAppList()
            }
        }
        modeBox.addView(modeValue)
        modeBox.addView(modeHint)
        modeBox.addView(splitModeSwitch)
        modeCard.addView(modeBox)
        content.addView(modeCard)
       
        ipv4Text = TextView(this)
        ipv6Text = TextView(this)
        configStateText = TextView(this)

        val languageCard = card()
        val languageBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        languageRuButton = secondaryButton("Русский").apply { setOnClickListener { setLanguage(false) } }
        languageEnButton = secondaryButton("English").apply { setOnClickListener { setLanguage(true) } }
        languageBox.addView(languageRuButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(10) })
        languageBox.addView(languageEnButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        languageCard.addView(languageBox)
        content.addView(languageCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        return content
    }

    private fun buildConfigPage(): View {
        val content = pageContent()
        content.addView(sectionHeader(tr("Настройки подключения", "Connection Config"), tr("Быстрое переключение между сохранёнными профилями.", "Switch between saved profiles quickly.")))

        val profileCard = card()
        val profileBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        profileSpinner = Spinner(this).apply { background = round(surface2, dp(16), outline); setPadding(dp(10), 0, dp(10), 0) }
        profileNameInput = input(tr("Название профиля", "Profile Name"), tr("Например：cdnjs.cloudflare.com 443 / cdnjs.cloudflare.com 8443", "e.g. cdnjs.cloudflare.com 443 / cdnjs.cloudflare.com 8443"))
        saveNewProfileBtn = secondaryButton(tr("Сохранить как новый", "Save as New"))
        overwriteProfileBtn = secondaryButton(tr("Перезаписать текущий", "Overwrite Current"))
        deleteProfileBtn = secondaryButton(tr("Удалить выбранный профиль", "Delete Profile"))

        // 🛠️ ИСПРАВЛЕНИЕ: Разделяем настройку парных кнопок и кнопки удаления
        
        // Настройка для верхних горизонтальных кнопок (сжимаем их, чтобы поместились в ряд)
        val horizontalButtons = listOf(overwriteProfileBtn, saveNewProfileBtn)
        horizontalButtons.forEach { btn ->
            btn.isSingleLine = false
            btn.maxLines = 2
            btn.isAllCaps = false
            btn.ellipsize = null
            btn.setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        exportConfigBtn = secondaryButton(tr("Экспорт всего конфига в файл", "Export entire config to file"))
        importConfigBtn = secondaryButton(tr("Импорт конфига из файла", "Import config from file"))

        val backupButtonsList = listOf(exportConfigBtn, importConfigBtn)
        backupButtonsList.forEach { btn ->
            btn.isSingleLine = false
            btn.maxLines = 2
            btn.isAllCaps = false
            btn.ellipsize = null
            btn.setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        profileBox.addView(TextView(this).apply { text = tr("Профили настроек", "Profiles"); textSize = 18f; setTextColor(textColor); setTypeface(null, Typeface.BOLD) })
        profileBox.addView(TextView(this).apply { text = tr("Сохранить текущие SNI / Endpoint / Port для быстрого переключения.", "Save current SNI / Endpoint / Port for quick switching."); textSize = 12f; setTextColor(subText); setPadding(0, dp(2), 0, dp(6)) })
        profileBox.addView(profileSpinner, LinearLayout.LayoutParams(-1, dp(42)))
        profileBox.addView(inputWrap(tr("Название профиля", "Profile Name"), profileNameInput), LinearLayout.LayoutParams(-1, dp(70)).apply { topMargin = dp(5) })

        // Создаем горизонтальный контейнер для первых двух кнопок с автоматической высотой
        val profileActions = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL 
            isBaselineAligned = false
        }
        
        // Добавляем кнопки «Перезаписать» и «Сохранить как новый» с высотой WRAP_CONTENT
        profileActions.addView(overwriteProfileBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { 
            rightMargin = dp(8) 
        })
        profileActions.addView(saveNewProfileBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        
        // Добавляем горизонтальный контейнер в основной блок
        profileBox.addView(profileActions, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        
        // Добавляем кнопку «Удалить выбранный профиль» с автоматической высотой
        profileBox.addView(deleteProfileBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { 
            topMargin = dp(10) // Увеличили отступ сверху для визуального разделения блоков
        })

        val backupActions = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL 
            isBaselineAligned = false
        }
        backupActions.addView(exportConfigBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        backupActions.addView(importConfigBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        profileBox.addView(backupActions, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        profileCard.addView(profileBox)
        content.addView(profileCard)

        content.addView(sectionTitle(tr("Текущие параметры подключения", "Current Connection")))
        val config = card()
        val configBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        sniInput = input("SNI", "cdnjs.cloudflare.com")
        endpointInput = input("Endpoint IP", "162.159.198.2")
        portInput = input("Connect Port", "443")
        useHttp2Switch = MaterialSwitch(this).apply {
//            text = tr("HTTP/2 вместо QUIC (обход блокировки UDP)", "HTTP/2 instead of QUIC (bypass UDP blocking)")
            text = tr("HTTP/2 вместо QUIC", "HTTP/2 instead of QUIC")
            textSize = 16f
            setTextColor(textColor)
            setPadding(0, dp(6), 0, 0)
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        defaultBtn = secondaryButton(tr("Загрузить endpoint по умолчанию", "Load Default Endpoint"))
        configBox.addView(compactInputWrap("SNI", sniInput))
        configBox.addView(compactInputWrap("Endpoint IP", endpointInput))
        configBox.addView(compactInputWrap("Connect Port", portInput))
        configBox.addView(useHttp2Switch, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        configBox.addView(defaultBtn, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(3) })
        config.addView(configBox)
        content.addView(config)
        content.addView(sectionTitle(tr("Лицензионный ключ", "License Key")))
        val licenseCard = card()
        val licenseBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        licenseKeyInput = input("License Key", "XXXXXXXX-XXXXXXXX-XXXXXXXX")
        saveLicenseBtn = secondaryButton(tr("Сохранить ключ", "Save Key"))
        removeLicenseBtn = secondaryButton(tr("Удалить ключ", "Remove Key"))
        val licenseBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        licenseBtnRow.addView(saveLicenseBtn, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) })
        licenseBtnRow.addView(removeLicenseBtn, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(4) })
        licenseBox.addView(compactInputWrap("License Key", licenseKeyInput))
        licenseBox.addView(licenseBtnRow, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
        licenseCard.addView(licenseBox)
        content.addView(licenseCard)
        return content
    }

    private fun buildAppsPage(): View {
        val content = pageContent()
        content.addView(sectionHeader(tr("Выбор приложений", "Select Apps"), tr("Здесь выбираются только приложения. Включение раздельного туннелирования выполняется на «Главной» странице.", "Only choose apps here. Enable split tunneling on Overview.")))
        appSection = card()
        val appBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        appSearchInput = input(tr("Поиск приложений", "Search Apps"), tr("Название или имя пакета", "App name or package"))
        appCountText = infoLine(tr("Выбрано приложений: 0", "0 apps selected"))
        appListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appBox.addView(inputWrap(tr("Поиск", "Search"), appSearchInput))
        appBox.addView(appCountText)
        val appActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selectAllAppsBtn = secondaryButton(tr("Выбрать все", "Select All")).apply { setOnClickListener { selectAllVisibleApps() } }
        clearAllAppsBtn = secondaryButton(tr("Снять выделение", "Select None")).apply { setOnClickListener { clearAllVisibleApps() } }
        appActions.addView(selectAllAppsBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
        appActions.addView(clearAllAppsBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        appBox.addView(appActions, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        appBox.addView(MaterialDivider(this).apply { dividerColor = outline }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(8); bottomMargin = dp(8) })
        appBox.addView(appListContainer)
        appSection.addView(appBox)
        content.addView(appSection)
        return content
    }

    private fun pageContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(14))
        setBackgroundColor(bg)
    }

    private fun tabButton(label: String) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        cornerRadius = dp(18)
        minHeight = dp(36)
        insetTop = 0
        insetBottom = 0
        setTextColor(subText)
        backgroundTintList = android.content.res.ColorStateList.valueOf(surface2)
    }

    private fun setTabSelected(button: MaterialButton, selected: Boolean) {
        button.setTextColor(if (selected) onPrimary else subText)
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected) primary else surface2)
    }

    private fun sectionHeader(title: String, desc: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), 0, dp(2), dp(8))
        addView(TextView(this@MainActivity).apply { text = title; textSize = 20f; setTextColor(textColor); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@MainActivity).apply { text = desc; textSize = 12f; setTextColor(subText); setPadding(0, dp(2), 0, 0) })
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(24).toFloat()
        cardElevation = 0f
        strokeWidth = 1
        strokeColor = outline
        setCardBackgroundColor(surface)
        useCompatPadding = false
    }
    private fun secondaryButton(label: String) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        cornerRadius = dp(22)
        setTextColor(onPrimary)
        backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
        strokeWidth = 0
        elevation = dp(1).toFloat()

        // ИСПРАВЛЕНИЕ: Разрешаем многострочность
        isSingleLine = false
        maxLines = 2
        ellipsize = null

        // ИСПРАВЛЕНИЕ: Уменьшаем внутренние боковые отступы до 4dp (по аналогии с вкладками)
        setPadding(dp(4), paddingTop, dp(4), paddingBottom)
    }
    private fun sectionTitle(s: String) = TextView(this).apply {
        text = s
        textSize = 14f
        setTextColor(subText)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(4), dp(10), dp(4), dp(6))
    }
    private fun input(label: String, hint: String) = TextInputEditText(this).apply {
        setHint(hint)
        setTextColor(textColor)
        setHintTextColor(Color.rgb(150, 123, 103))
        textSize = 14f
        isSingleLine = true
    }
    private fun inputWrap(label: String, edit: TextInputEditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(5) }
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subText)
            setPadding(dp(2), 0, dp(2), dp(4))
        })
        addView(TextInputLayout(this@MainActivity).apply {
            isHintEnabled = false
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = surface
            setBoxCornerRadii(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat())
            setBoxStrokeColor(primary)
            addView(edit)
        }, LinearLayout.LayoutParams(-1, dp(50)))
    }
    private fun compactInputWrap(label: String, edit: TextInputEditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(2) }
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subText)
            setPadding(dp(2), 0, dp(2), dp(2))
        })
        addView(TextInputLayout(this@MainActivity).apply {
            isHintEnabled = false
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = surface
            setBoxCornerRadii(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat())
            setBoxStrokeColor(primary)
            addView(edit)
        }, LinearLayout.LayoutParams(-1, dp(42)))
    }
    private fun pill(s: String) = TextView(this).apply {
        text = s
        setTextColor(primary)
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = round(surface2, dp(18), outline)
        setPadding(dp(12), dp(7), dp(12), dp(7))
    }
    private fun infoLine(s: String) = TextView(this).apply {
        text = s
        setTextColor(subText)
        setPadding(0, dp(4), 0, dp(4))
        textSize = 13f
    }
    private fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(1, stroke)
    }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun dirtyWatcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { markDirty() }
        override fun afterTextChanged(s: Editable?) {}
    }
    private fun markDirty() { configDirty = true; updateConfigState() }
    private fun updateConfigState(extra: String = "") {
        val base = if (configDirty) tr("Конфиг: не сохранен, при подключении сохранится автоматически", "Config: unsaved, will auto-save on connect") else tr("Конфиг: сохранен", "Config: saved")
        configStateText.text = if (extra.isBlank()) base else "$base · $extra"
    }
    private fun saveInputs() {
        prefs.edit()
            .putString("sni", sniInput.text?.toString().orEmpty())
            .putString("endpoint", normalizedEndpoint())
            .putInt("connectPort", normalizedPort())
            .putBoolean("splitMode", splitModeSwitch.isChecked)
            .putBoolean("useHttp2", useHttp2Switch.isChecked)
            .putStringSet("selectedPackages", selectedPackages.toSet())
            .apply()
        configDirty = false
        updateConfigState(); updateModeUi()
    }
    private fun loadProfiles() {
        profiles.clear()
        val raw = prefs.getString("profilesJson", "") ?: ""
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                profiles[o.getString("name")] = Profile(
                    o.optString("sni", "cdnjs.cloudflare.com"),
                    o.optString("endpoint", "162.159.198.2"),
                    o.optInt("port", 443),
                    o.optBoolean("http2", false)  // у старых профилей в JSON этого поля нет — optBoolean тихо даст false, ничего не сломается
                )
            }
        }
        if (profiles.isEmpty()) {
            profiles["cdnjs.cloudflare.com:443:162.159.198.2 (loc 1) h3"] = Profile("cdnjs.cloudflare.com", "162.159.198.2", 443, false)
            profiles["cdnjs.cloudflare.com:443:162.159.199.2 (loc 2) h3"] = Profile("cdnjs.cloudflare.com", "162.159.199.2", 443, false)
            profiles["cdnjs.cloudflare.com:443:162.159.198.2 (loc 1) h2"] = Profile("cdnjs.cloudflare.com", "162.159.198.2", 443, true)
            profiles["cdnjs.cloudflare.com:443:162.159.199.2 (loc 2) h2"] = Profile("cdnjs.cloudflare.com", "162.159.199.2", 443, true)
            profiles["youtube.com:443:162.159.198.2 (loc 1) h3"] = Profile("youtube.com", "162.159.198.2", 443, false)
            profiles["youtube.com:443:162.159.199.2 (loc 2) h3"] = Profile("youtube.com", "162.159.199.2", 443, false)
            profiles["youtube.com:443:162.159.198.2 (loc 1) h2"] = Profile("youtube.com", "162.159.198.2", 443, true)
            profiles["youtube.com:443:162.159.199.2 (loc 2) h2"] = Profile("youtube.com", "162.159.199.2", 443, true)
            persistProfiles()
        }
        refreshProfileSpinner()
    }
    private fun persistProfiles() {
        val arr = JSONArray()
        profiles.forEach { (name, v) ->
            arr.put(JSONObject().put("name", name).put("sni", v.sni).put("endpoint", v.endpoint).put("port", v.port).put("http2", v.http2))
        }
        prefs.edit().putString("profilesJson", arr.toString()).apply()
    }
    private fun refreshProfileSpinner() {
        val names = profiles.keys.toList()
        if (::profileSpinner.isInitialized) {
            profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            val current = currentProfileName().takeIf { profiles.containsKey(it) } ?: names.firstOrNull().orEmpty()
            if (current.isNotBlank()) profileSpinner.setSelection(names.indexOf(current).coerceAtLeast(0), false)
            profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val name = names.getOrNull(position) ?: return
                    loadProfileForEditing(name)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        refreshHomeProfileSpinner()
    }
    private fun refreshHomeProfileSpinner() {
        if (!::homeProfileSpinner.isInitialized) return
        val names = profiles.keys.toList()
        suppressProfileSelection = true
        homeProfileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val current = currentProfileName().takeIf { profiles.containsKey(it) } ?: names.firstOrNull().orEmpty()
        if (current.isNotBlank()) homeProfileSpinner.setSelection(names.indexOf(current).coerceAtLeast(0), false)
        suppressProfileSelection = false
        homeProfileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressProfileSelection) return
                val name = names.getOrNull(position) ?: return
                switchHomeProfile(name)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        updateCurrentProfileUi()
    }
    private fun selectedProfileName(): String = profileSpinner.selectedItem?.toString().orEmpty()
    private fun currentProfileName(): String = prefs.getString("currentProfileName", "")?.orEmpty() ?: ""
    private fun setCurrentProfileName(name: String) { prefs.edit().putString("currentProfileName", name).apply() }
    private fun updateCurrentProfileUi() {
        if (!::currentProfileText.isInitialized) return
        val name = currentProfileName().takeIf { it.isNotBlank() } ?: profiles.keys.firstOrNull().orEmpty()
        val p = profiles[name]
        currentProfileText.text = if (p != null) {
            val mode = if (p.http2) "HTTP/2" else "QUIC"
            tr("Текущий профиль：$name · SNI ${p.sni} · ${p.endpoint}:${p.port} · $mode", "Current: $name · SNI ${p.sni} · ${p.endpoint}:${p.port} · $mode")
        } else {
            tr("Текущий профиль: не выбран", "Current profile: none")
        }
    }
    private fun syncConfigProfileSpinner(name: String) {
        if (!::profileSpinner.isInitialized) return
        val names = profiles.keys.toList()
        val index = names.indexOf(name)
        if (index >= 0) profileSpinner.setSelection(index, false)
    }
    private fun switchHomeProfile(name: String) {
        if (!profiles.containsKey(name)) return
        if (vpnRunning) {
            refreshHomeProfileSpinner()
            toast(tr("Отключите VPN перед переключением профиля", "Disconnect VPN before switching profile"))
            return
        }
        applyProfileToInputs(name, persist = true)
        setCurrentProfileName(name)
        syncConfigProfileSpinner(name)
        updateCurrentProfileUi()
        toast(tr("Профиль переключен：$name", "Profile switched: $name"))
    }
    private fun applyProfileToInputs(name: String, persist: Boolean) {
        val p = profiles[name] ?: return
        sniInput.setText(p.sni)
        endpointInput.setText(p.endpoint)
        portInput.setText(p.port.toString())
        useHttp2Switch.isChecked = p.http2
        profileNameInput.setText(name)
        if (persist) { markDirty(); saveInputs() }
    }
    private fun saveAsNewProfile() {
        val base = profileNameInput.text?.toString().orEmpty().trim().ifBlank { normalizedEndpoint() }
        val name = uniqueProfileName(base)
        profiles[name] = Profile(sniInput.text?.toString().orEmpty().ifBlank { "cdnjs.cloudflare.com" }, normalizedEndpointHost(), normalizedPort(), useHttp2Switch.isChecked)
        persistProfiles(); refreshProfileSpinner(); profileNameInput.setText(name); syncConfigProfileSpinner(name); toast(tr("Сохранено как новый профиль: $name", "Saved as new profile: $name"))
    }
    private fun overwriteSelectedProfile() {
        val selected = selectedProfileName()
        val name = selected.ifBlank { profileNameInput.text?.toString().orEmpty().trim() }
        if (name.isBlank()) return toast(tr("Сначала выберите профиль", "Select a profile first"))
        profiles[name] = Profile(sniInput.text?.toString().orEmpty().ifBlank { "cdnjs.cloudflare.com" }, normalizedEndpointHost(), normalizedPort(), useHttp2Switch.isChecked)
        persistProfiles(); refreshProfileSpinner(); profileNameInput.setText(name); syncConfigProfileSpinner(name)
        if (currentProfileName() == name) { setCurrentProfileName(name); refreshHomeProfileSpinner(); updateCurrentProfileUi() }
        toast(tr("Текущий профиль перезаписан：$name", "Current profile overwritten: $name"))
    }
    private fun loadProfileForEditing(name: String) {
        if (!profiles.containsKey(name)) return
        applyProfileToInputs(name, persist = false)
    }
    private fun uniqueProfileName(base: String): String {
        if (!profiles.containsKey(base)) return base
        var index = 2
        while (profiles.containsKey("$base $index")) index++
        return "$base $index"
    }
    private fun deleteSelectedProfile() {
        val name = selectedProfileName()
        if (name.isBlank()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Удалить профиль?", "Delete profile?"))
            .setMessage(tr("Профиль «$name» будет удалён без возможности восстановления.", "Profile \"$name\" will be permanently deleted."))
            .setPositiveButton(tr("Удалить", "Delete")) { _, _ ->
                profiles.remove(name)
                if (currentProfileName() == name) setCurrentProfileName(profiles.keys.firstOrNull().orEmpty())
                persistProfiles(); refreshProfileSpinner(); toast(tr("Удалено：$name", "Deleted: $name"))
            }
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .show()
    }
    private fun loadSavedState() {
        loadProfiles()
        val saved = prefs.getString("endpoint", "162.159.198.2:443") ?: "162.159.198.2:443"
        endpointInput.setText(parseEndpointHost(saved))
        portInput.setText(prefs.getInt("connectPort", parseEndpointPort(saved, 443)).toString())
        sniInput.setText(prefs.getString("sni", "cdnjs.cloudflare.com") ?: "cdnjs.cloudflare.com")
        selectedPackages.clear(); selectedPackages.addAll(prefs.getStringSet("selectedPackages", emptySet()) ?: emptySet())
        splitModeSwitch.isChecked = prefs.getBoolean("splitMode", false)
        useHttp2Switch.isChecked = prefs.getBoolean("useHttp2", false)
        if (currentProfileName().isBlank() && profiles.isNotEmpty()) setCurrentProfileName(profiles.keys.first())
        configDirty = false
        updateConfigState(); updateModeUi(); refreshHomeProfileSpinner(); updateCurrentProfileUi()
    }
    private fun loadLicenseKey() {
        if (!hasValidRegistration()) return
        Thread {
            val key = runCatching { Usqueandroid.getLicenseKey(configFile.absolutePath) }.getOrDefault("")
            runOnUiThread { if (::licenseKeyInput.isInitialized) licenseKeyInput.setText(key) }
        }.start()
    }

    private fun ensureAppsLoaded() {
        if (appsLoaded) return
        appsLoaded = true
        appCountText.text = tr("Загрузка списка приложений…", "Loading app list…")
        loadInstalledApps()
    }
    private fun loadInstalledApps() {
        executor.execute {
            val pm = packageManager
            val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
            val apps = pm.getInstalledPackages(flags)
                .asSequence()
                .mapNotNull { pkg -> pkg.applicationInfo }
                .filter { it.packageName != packageName }
                .map { info ->
                    val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName)
                    AppEntry(label.ifBlank { info.packageName }, info.packageName)
                }
                .distinctBy { it.packageName }
                .sortedWith(compareBy<AppEntry> { it.label.lowercase() }.thenBy { it.packageName })
                .toList()
            handler.post { allApps.clear(); allApps.addAll(apps); refreshAppList() }
        }
    }
    private fun refreshAppList() {
        if (!::appListContainer.isInitialized) return
        if (!appsLoaded) { appCountText.text = tr("Список приложений загрузится при открытии вкладки «Приложения»", "App list loads when opening Apps"); return }
        appListContainer.removeAllViews()
        val visible = visibleApps()
        visible.forEach { app -> appListContainer.addView(appRow(app), LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(5) }) }
        appCountText.text = if (splitModeSwitch.isChecked) tr("Выбрано ${selectedPackages.size} прил. · Показать ${visible.size}/${allApps.size}", "Selected ${selectedPackages.size} · Showing ${visible.size}/${allApps.size}") else tr("Глобальный режим: выбор не активен · Отображается ${visible.size}/${allApps.size}", "Global mode: selections inactive · Showing ${visible.size}/${allApps.size}")
        updateModeUi()
    }
    private fun visibleApps(): List<AppEntry> {
        val query = appSearchInput.text?.toString().orEmpty().trim().lowercase()
        return allApps.asSequence()
            .filter { query.isBlank() || it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
            .sortedWith(compareByDescending<AppEntry> { selectedPackages.contains(it.packageName) }.thenBy { it.label.lowercase() }.thenBy { it.packageName })
            .toList()
    }
    private fun selectAllVisibleApps() {
        if (!appsLoaded) return
        selectedPackages.addAll(visibleApps().map { it.packageName })
        markDirty(); saveSelectedApps(); refreshAppList()
        toast(tr("Выбраны все приложения в списке", "Selected all visible apps"))
    }
    private fun clearAllVisibleApps() {
        if (!appsLoaded) return
        selectedPackages.removeAll(visibleApps().map { it.packageName }.toSet())
        markDirty(); saveSelectedApps(); refreshAppList()
        toast(tr("Выбор со всех приложений в списке снят", "Cleared visible apps"))
    }

    private fun appRow(app: AppEntry): View = MaterialCheckBox(this).apply {
        text = "${app.label}\n${app.packageName}"
        setTextColor(textColor)
        textSize = 13f
        background = round(if (selectedPackages.contains(app.packageName)) Color.rgb(255, 232, 212) else surface2, dp(18), outline)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        isChecked = selectedPackages.contains(app.packageName)
        setOnCheckedChangeListener { _, checked ->
            if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
            markDirty(); saveSelectedApps(); refreshAppList()
        }
    }
    private fun saveSelectedApps() { prefs.edit().putBoolean("splitMode", splitModeSwitch.isChecked).putStringSet("selectedPackages", selectedPackages.toSet()).apply() }
    private fun updateModeUi() {
        if (!::modeValue.isInitialized) return
        if (splitModeSwitch.isChecked) {
            modeValue.text = tr("Раздельный режим", "Split Mode")
            modeHint.text = tr("Через VPN идут только выбранные приложения. Выбранные приложения всегда отображаются в самом верху списка.", "Only selected apps use VPN; selected apps stay on top.")
            if (::appSection.isInitialized) { appSection.visibility = View.VISIBLE; appSection.alpha = 1.0f }
        } else {
            modeValue.text = tr("Глобальный режим", "Global Mode")
            modeHint.text = tr("Все приложения идут через VPN. Выбор на вкладке приложений сохраняется, но сейчас не активен.", "All apps use VPN; app selections are kept but inactive.")
            if (::appSection.isInitialized) { appSection.visibility = View.VISIBLE; appSection.alpha = 1.0f }
        }
    }
    private fun selectedPackagesForVpn(): ArrayList<String> = ArrayList(selectedPackages)

    private fun hasValidRegistration(): Boolean = configFile.exists() && runCatching { Usqueandroid.isRegistered(configFile.absolutePath) }.getOrDefault(false)
    private fun deleteInvalidConfigIfNeeded() {
        if (!configFile.exists()) return
        val txt = runCatching { configFile.readText() }.getOrDefault("")
        if (txt.contains("\"private_key\": \"\"") || txt.contains("\"access_token\": \"\"") || !hasValidRegistration()) runCatching { configFile.delete() }
    }
    private fun resetSpeedMeter() {
        lastRxBytes = TrafficStats.getTotalRxBytes().takeIf { it >= 0 } ?: 0L
        lastTxBytes = TrafficStats.getTotalTxBytes().takeIf { it >= 0 } ?: 0L
        lastSpeedTs = System.currentTimeMillis()
        if (::speedText.isInitialized) speedText.text = "↓ 0 B/s   ↑ 0 B/s"
    }
    private fun updateSpeedLine() {
        if (!::speedText.isInitialized) return
        if (!vpnRunning) { speedText.text = "↓ 0 B/s   ↑ 0 B/s"; return }
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes().takeIf { it >= 0 } ?: return
        val tx = TrafficStats.getTotalTxBytes().takeIf { it >= 0 } ?: return
        if (lastSpeedTs <= 0L) { resetSpeedMeter(); return }
        val seconds = ((now - lastSpeedTs).coerceAtLeast(1)).toDouble() / 1000.0
        val down = ((rx - lastRxBytes).coerceAtLeast(0) / seconds).toLong()
        val up = ((tx - lastTxBytes).coerceAtLeast(0) / seconds).toLong()
        lastRxBytes = rx; lastTxBytes = tx; lastSpeedTs = now
        speedText.text = "↓ ${formatSpeed(down)}   ↑ ${formatSpeed(up)}"
    }
    private fun formatSpeed(bytesPerSecond: Long): String {
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSecond.toDouble()
        var idx = 0
        while (value >= 1024.0 && idx < units.lastIndex) { value /= 1024.0; idx++ }
        return if (idx == 0) "${value.toLong()} ${units[idx]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[idx])
    }

    private fun refreshState(extra: String = "") {
        val state = when {
            !vpnRunning -> tr("Отключено", "Disconnected")
            tunnelReallyConnected -> tr("Подключено", "Connected")
            else -> tr("Подключение…", "Connecting…")
        }
        if (statusBanner.text != state) statusBanner.text = state
        val transportLabel = if (vpnRunning) {
            val isHttp2 = runCatching { Usqueandroid.getUseHttp2() }.getOrDefault(false)
            " · " + if (isHttp2) "HTTP/2" else "QUIC"
        } else ""
        val ipLabel = if (vpnRunning && tunnelReallyConnected) {
            " · IP: " + runCatching { Usqueandroid.getAssignedIPv4(configFile.absolutePath) }.getOrDefault("").ifBlank { "?" }
        } else ""
        val newStatusText = "${tr("Статус", "Status")}: $state$transportLabel$ipLabel${if (extra.isNotBlank()) " · $extra" else ""}"
        if (statusText.text != newStatusText) statusText.text = newStatusText
        val newColor = if (vpnRunning && tunnelReallyConnected) green else onPrimary
        if (statusBanner.getCurrentTextColor() != newColor) statusBanner.setTextColor(newColor) // цвет всегда дешёвая операция, можно не проверять
        val newBtnText = if (vpnRunning) tr("Отключить VPN", "Disconnect VPN") else tr("Подключить VPN", "Connect VPN")
        if (connectButton.text != newBtnText) connectButton.text = newBtnText        
        val dark = android.content.res.ColorStateList.valueOf(darkAccent)
        if (connectButton.backgroundTintList != dark) connectButton.backgroundTintList = dark
        if (connectButton.getCurrentTextColor() != Color.WHITE) connectButton.setTextColor(Color.WHITE)
        if (::languageRuButton.isInitialized) {
            val newRuTintList = android.content.res.ColorStateList.valueOf(if (!useEnglish) darkAccent else primary)
            languageRuButton.backgroundTintList = newRuTintList
            val newEnTintList = android.content.res.ColorStateList.valueOf(if (useEnglish) darkAccent else primary)
            languageEnButton.backgroundTintList = newEnTintList
            val newRuColor = if (!useEnglish) Color.WHITE else onPrimary
            if (languageRuButton.getCurrentTextColor() != newRuColor) languageRuButton.setTextColor(newRuColor)
            val newEnColor = if (useEnglish) Color.WHITE else onPrimary
            if (languageEnButton.getCurrentTextColor() != newEnColor) languageEnButton.setTextColor(newEnColor)
        }
        updateConfigState(extra)
    }
    
    private fun connectVpn() {
        saveInputs()
        if (vpnRunning) { toast(tr("Приложение уже работает", "Already running")); return }
        if (tunnelStopping) { toast(tr("Подождите, предыдущее соединение ещё останавливается", "Please wait, previous connection is still stopping")); return }
        if (splitModeSwitch.isChecked && selectedPackages.isEmpty()) { toast(tr("Выберите хотя бы одно приложение", "Select at least one app")); log(tr("Выберите хотя бы одно приложение перед подлючением в раздельном режиме", "Select at least one app before connecting in split mode")); refreshState("Приложение не выбрано"); return }
        if (!hasValidRegistration()) {
            log(tr("Не найдена действительная регистрация. Автоматическая регистрация…", "No valid registration found. Registering automatically…"))
            refreshState(tr("Регистрация...", "Registration...")) 
            executor.execute {
                try {
                    deleteInvalidConfigIfNeeded()
                    val result = Usqueandroid.register(configFile.absolutePath, "Android")
                    handler.post {
                        if (result.isNullOrBlank() && hasValidRegistration()) { 
                            log(tr("Зарегистрировано. Запрашивается разрешение на использование VPN…", "Registered. Requesting VPN permission…")); 
                            loadLicenseKey()
                            requestVpnAndStart() 
                        } else { 
                            log(tr("Регистрация не удалась: ${result.ifNullOrBlank("Неизвестная ошибка")}", "Registration failed: ${result.ifNullOrBlank("Unknown error")}")); 
                            refreshState(tr("Регистрация не удалась", "Registration failed")) 
                        }
                    }
                } catch (e: Exception) { handler.post { log("Ошибка регистрации: ${e.message ?: e.javaClass.simpleName}"); refreshState(tr("Ошибка регистрации", "Registration error")) } }
            }
            return
        }
        requestVpnAndStart()
    }

    private fun requestVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null && !vpnGranted) { startActivityForResult(prepareIntent, REQ_VPN); return }
        vpnGranted = true
        startTunnelNow()
    }

    private fun startTunnelNow() {
        val sni = sniInput.text?.toString().orEmpty().ifBlank { "cdnjs.cloudflare.com" }
        val endpoint = "${normalizedEndpointHost()}:${normalizedPort()}"
        val splitMode = splitModeSwitch.isChecked
        val useHttp2 = useHttp2Switch.isChecked
        val allowedApps = if (splitMode) selectedPackagesForVpn() else arrayListOf()
        log(if (splitMode) tr("Запуск раздельного VPN: ${allowedApps.size} прил. · $endpoint", "Starting split VPN: ${allowedApps.size} apps · $endpoint") else tr("Запуск глобального VPN: $endpoint", "Starting global VPN: $endpoint"))
        resetSpeedMeter()
        vpnRunning = true
        refreshState(tr("Запуск", "Starting"))
        val intent = Intent(this, UsqueVpnService::class.java)
            .putExtra("configPath", configFile.absolutePath)
            .putExtra("sni", sni)
            .putExtra("endpoint", endpoint)
            .putExtra("splitMode", splitMode)
            .putExtra("useHttp2", useHttp2)
            .putStringArrayListExtra("allowedApps", allowedApps)
        startService(intent)
        log(tr("Служба VPN успешно запущена", "VPN service started"))
        refreshState(if (splitMode) tr("Раздельный режим", "Split mode running") else tr("Глобальный режим", "Global mode running"))
    }

    private fun disconnectVpn() {
        log(tr("Остановка службы VPN...", "Stopping VPN service…"))
        vpnRunning = false
        tunnelStopping = true
        refreshState(tr("Остановка", "Stopping"))
        resetSpeedMeter()
        UsqueVpnService.stopActiveTunnel()
        runCatching { startService(Intent(this, UsqueVpnService::class.java).setAction(UsqueVpnService.ACTION_STOP)) }

        handler.postDelayed({
            val trace = runCatching { Usqueandroid.getShutdownTrace() }.getOrDefault("")
            if (trace.isNotBlank()) {
                DiagLog.add("Trace", "\n" + trace)
            }
        }, 3000)

        // Это только обновляет текст на экране побыстрее — саму защиту (tunnelStopping) НЕ трогает.
        handler.postDelayed({
            runCatching { stopService(Intent(this, UsqueVpnService::class.java)) }
            onTunnelStopped(tr("Остановлено", "Stopped"))
        }, 500)

        // А это — подстраховка на крайний случай, если настоящий сигнал "disconnected"
        // почему-то вообще не придёт. 8 секунд — заведомо больше, чем реально нужно
        // туннелю на закрытие, поэтому в норме этот таймер никогда не успевает
        // сработать первым — снятие защиты происходит по настоящему сигналу
        // (в блоке stateListener, "disconnected" → tunnelStopping = false).
        handler.postDelayed({ tunnelStopping = false }, 8000)
    }

    private fun onTunnelStopped(msg: String) { vpnRunning = false; refreshState(msg); log(msg) }
    private fun normalizedEndpointHost(): String = parseEndpointHost(endpointInput.text?.toString().orEmpty().trim().ifBlank { "162.159.198.2" }).ifBlank { "162.159.198.2" }
    private fun normalizedPort(): Int = (portInput.text?.toString().orEmpty().trim().toIntOrNull() ?: parseEndpointPort(endpointInput.text?.toString().orEmpty(), 443)).coerceIn(1, 65535)
    private fun normalizedEndpoint(): String = "${normalizedEndpointHost()}:${normalizedPort()}"
    private fun parseEndpointHost(value: String): String { val v = value.trim(); if (v.isBlank()) return "162.159.198.2"; if (v.startsWith("[") && v.contains("]")) return v.substringAfter("[").substringBefore("]"); return if (v.count { it == ':' } == 1) v.substringBefore(':') else v }
    private fun parseEndpointPort(value: String, fallback: Int): Int { val v = value.trim(); val p = when { v.startsWith("[") && v.contains("]:") -> v.substringAfter("]:"); v.count { it == ':' } == 1 -> v.substringAfter(':'); else -> "" }; return p.toIntOrNull()?.takeIf { it in 1..65535 } ?: fallback }
    private fun log(msg: String) {
        logText.text = msg
        DiagLog.add("UI", msg)
    }

    private fun showDiagLogDialog() {
        val logsText = DiagLog.getAll().ifBlank { tr("Логов пока нет.", "No logs yet.") }
        val textView = TextView(this).apply {
            text = logsText
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(textView) }

        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Логи", "Logs"))
            .setView(scroll)
            .setPositiveButton(tr("Копировать", "Copy")) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("usque logs", DiagLog.getAll()))
            }
            .setNeutralButton(tr("Очистить", "Clear")) { _, _ -> DiagLog.clear() }
            .setNegativeButton(tr("Закрыть", "Close"), null)
            .show()
    }
    
    private fun String?.ifNullOrBlank(fallback: String): String = if (this.isNullOrBlank()) fallback else this
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) { vpnGranted = true; if (hasValidRegistration()) startTunnelNow() else connectVpn() }
        else if (requestCode == REQ_VPN) toast(tr("Доступ к VPN не разрешен в системе", "VPN permission denied"))
        else if (requestCode == REQ_EXPORT_FILE && resultCode == RESULT_OK) { data?.data?.let { writeExportToUri(it) } }
        else if (requestCode == REQ_IMPORT_FILE && resultCode == RESULT_OK) { data?.data?.let { readImportFromUri(it) } }
    }

    // ЭКСПОРТ: Собирает все файлы настроек в чистый JSON и копирует в файл
    fun exportAllConfigToFile() {
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "usque.json")
            }
            startActivityForResult(intent, REQ_EXPORT_FILE)
        } catch (e: Exception) {
            toast(tr("Ошибка: ${e.message}", "Error: ${e.message}"))
        }
    }

    //  ИМПОРТ: Читает чистый JSON из файла и восстанавливает файлы настройки
    fun importAllConfigFromFile() {
        if (vpnRunning) { toast(tr("Сначала отключите VPN!", "Disable your VPN first!")); return }
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            startActivityForResult(intent, REQ_IMPORT_FILE)
        } catch (e: Exception) {
            toast(tr("Ошибка: ${e.message}", "Error: ${e.message}"))
        }
    }

    private fun buildExportJson(): String {
        val exportData = JSONObject()
        if (configFile.exists()) exportData.put("config", JSONObject(configFile.readText()))

        val p = JSONObject()
        p.put("sni", prefs.getString("sni", ""))
        p.put("endpoint", prefs.getString("endpoint", ""))
        p.put("connectPort", prefs.getInt("connectPort", 443))
        p.put("useHttp2", prefs.getBoolean("useHttp2", false))
        p.put("splitMode", prefs.getBoolean("splitMode", false))
        p.put("currentProfileName", prefs.getString("currentProfileName", ""))
        p.put("useEnglish", prefs.getBoolean("useEnglish", false))
        val pkgs = JSONArray()
        (prefs.getStringSet("selectedPackages", emptySet()) ?: emptySet()).forEach { pkgs.put(it) }
        p.put("selectedPackages", pkgs)
        exportData.put("prefs", p)

        val profilesRaw = prefs.getString("profilesJson", null)
        if (!profilesRaw.isNullOrBlank()) exportData.put("profiles", JSONArray(profilesRaw))

        return exportData.toString(2)
    }

    private fun writeExportToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(buildExportJson().toByteArray(Charsets.UTF_8)) }
            toast(tr("Конфигурация сохранена в файл!", "Configuration saved to file!"))
        } catch (e: Exception) {
            toast(tr("Ошибка экспорта: ${e.message}", "Export error: ${e.message}"))
        }
    }

    private fun readImportFromUri(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val importData = JSONObject(raw)

            if (importData.has("config")) configFile.writeText(importData.getJSONObject("config").toString(2))

            if (importData.has("prefs")) {
                val p = importData.getJSONObject("prefs")
                val e = prefs.edit()
                if (p.has("sni")) e.putString("sni", p.getString("sni"))
                if (p.has("endpoint")) e.putString("endpoint", p.getString("endpoint"))
                if (p.has("connectPort")) e.putInt("connectPort", p.getInt("connectPort"))
                if (p.has("useHttp2")) e.putBoolean("useHttp2", p.getBoolean("useHttp2"))
                if (p.has("splitMode")) e.putBoolean("splitMode", p.getBoolean("splitMode"))
                if (p.has("currentProfileName")) e.putString("currentProfileName", p.getString("currentProfileName"))
                if (p.has("useEnglish")) e.putBoolean("useEnglish", p.getBoolean("useEnglish"))
                if (p.has("selectedPackages")) {
                    val arr = p.getJSONArray("selectedPackages")
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    e.putStringSet("selectedPackages", set)
                }
                e.apply()
            }
            if (importData.has("profiles")) {
                prefs.edit().putString("profilesJson", importData.getJSONArray("profiles").toString()).apply()
            }

            runOnUiThread {
                toast(tr("Конфигурация успешно импортирована!", "Configuration imported successfully!"))
                loadProfiles()
                loadSavedState()
                refreshState(tr("Конфиг обновлен", "Config updated"))
            }
        } catch (e: Exception) {
            toast(tr("Ошибка импорта: Неверный формат данных", "Import error: Invalid data format"))
        }
    }

    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")
}
