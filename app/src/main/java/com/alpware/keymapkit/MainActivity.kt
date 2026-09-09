package com.alpware.keymapkit

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.os.LocaleListCompat
import androidx.core.net.toUri
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.alpware.keymapkit.ads.AdConsentManager
import com.alpware.keymapkit.ads.InterstitialAdManager
import com.alpware.keymapkit.ads.PersistentBanner
import com.alpware.keymapkit.ads.AdRuntimeConfig
import com.alpware.keymapkit.ads.AdRemoteConfig
import com.alpware.keymapkit.ads.AdTelemetry
import com.alpware.keymapkit.ads.AdTrafficGuard
import com.alpware.keymapkit.ads.FirebaseBootstrap
import com.alpware.keymapkit.billing.PremiumBillingManager
import com.alpware.keymapkit.billing.PremiumState
import com.alpware.keymapkit.layout.LayoutSelectionRepository
import com.alpware.keymapkit.play.PlayReviewManager
import com.alpware.keymapkit.play.PlayUpdateManager
import com.alpware.keymapkit.play.ReviewPromptCoordinator
import com.alpware.keymapkit.ui.*
import com.alpware.keymapkit.ui.theme.AppearancePreferences
import com.alpware.keymapkit.ui.theme.AppThemeMode
import com.alpware.keymapkit.ui.theme.KeymapKitTheme
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), PlayUpdateManager.Listener {
    private enum class AppScreen {
        Home,
        Layouts,
        Settings,
    }

    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(this, R.string.update_cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var playUpdateManager: PlayUpdateManager
    private lateinit var playReviewManager: PlayReviewManager
    private lateinit var reviewPromptCoordinator: ReviewPromptCoordinator
    private var showUpdateReadyDialog by mutableStateOf(false)
    private var reviewCheckOnNextResume = false
    private lateinit var adConsentManager: AdConsentManager
    private lateinit var interstitialAdManager: InterstitialAdManager
    private var adsReady by mutableStateOf(false)
    private var privacyOptionsRequired by mutableStateOf(false)
    private var adRuntimeConfig by mutableStateOf(AdRuntimeConfig())
    private lateinit var adTrafficGuard: AdTrafficGuard
    private lateinit var adTelemetry: AdTelemetry
    private lateinit var premiumBillingManager: PremiumBillingManager
    private var premiumState by mutableStateOf(PremiumState(isPremium = false))
    private var adFeaturesInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )

        playUpdateManager = PlayUpdateManager(this, updateLauncher, this)
        playReviewManager = PlayReviewManager(this)
        reviewPromptCoordinator = ReviewPromptCoordinator(this).also { it.recordSession() }

        premiumBillingManager = PremiumBillingManager(
            context = this,
            testMode = BuildConfig.DEBUG && BuildConfig.PREMIUM_TEST_MODE,
            onStateChanged = { updatedState ->
                runOnUiThread {
                    val wasPremium = premiumState.isPremium
                    premiumState = updatedState
                    if (wasPremium && !updatedState.isPremium) initializeAdFeaturesIfNeeded()
                }
            },
        )
        premiumState = premiumBillingManager.state

        // Firebase must be initialized before UMP so Consent Mode can interpret the choice.
        FirebaseBootstrap.initialize(this)
        adTrafficGuard = AdTrafficGuard(this)
        adTelemetry = AdTelemetry(this)
        if (!premiumState.isPremium) initializeAdFeaturesIfNeeded()
        premiumBillingManager.start()

        val repository = LayoutSelectionRepository(this).also { it.prepareFreshInstall() }
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            var themeMode by remember { mutableStateOf(appearancePreferences.themeMode) }
            val useDarkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            SideEffect {
                val systemBarStyle = if (useDarkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle,
                    navigationBarStyle = systemBarStyle
                )
            }
            KeymapKitTheme(darkTheme = useDarkTheme) {
                var configured by remember { mutableStateOf(repository.isConfigured) }
                var currentScreen by remember { mutableStateOf(AppScreen.Home) }
                var isBackNavigation by remember { mutableStateOf(false) }
                var layoutsChanged by remember { mutableStateOf(false) }

                fun navigateTo(screen: AppScreen) {
                    isBackNavigation = false
                    if (screen == AppScreen.Layouts) layoutsChanged = false
                    currentScreen = screen
                }

                fun navigateBack() {
                    if (currentScreen == AppScreen.Home) return
                    val leavingLayouts = currentScreen == AppScreen.Layouts
                    isBackNavigation = true
                    currentScreen = AppScreen.Home
                    if (leavingLayouts && layoutsChanged) {
                        val reviewStarted = requestAutomaticReviewIfEligible()
                        if (!reviewStarted && ::interstitialAdManager.isInitialized) {
                            interstitialAdManager.showIfEligible()
                        }
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            if (!configured) {
                                OnboardingScreen(repository) {
                                    configured = true
                                    reviewPromptCoordinator.recordMeaningfulAction()
                                    requestAutomaticReviewIfEligible()
                                }
                            } else {
                                KeymapPredictiveScreenHost(
                                    current = currentScreen,
                                    previous = AppScreen.Home.takeIf {
                                        currentScreen != AppScreen.Home
                                    },
                                    onBack = ::navigateBack,
                                    depth = { if (it == AppScreen.Home) 0 else 1 },
                                    isBackNavigation = isBackNavigation,
                                ) { displayedScreen ->
                                    when (displayedScreen) {
                                    AppScreen.Home -> {
                                        HomeScreen(
                                            repository = repository,
                                            onManageLayouts = { navigateTo(AppScreen.Layouts) },
                                            onOpenKeyboardSettings = ::openKeyboardSettings,
                                            onOpenSettings = { navigateTo(AppScreen.Settings) }
                                        )
                                    }
                                    AppScreen.Layouts -> {
                                        LayoutManagerScreen(
                                            repository = repository,
                                            onBack = ::navigateBack,
                                            onLayoutChanged = {
                                                layoutsChanged = true
                                                reviewPromptCoordinator.recordLayoutChanged()
                                                if (::interstitialAdManager.isInitialized) {
                                                    interstitialAdManager.recordLayoutChange()
                                                }
                                            }
                                        )
                                    }
                                    AppScreen.Settings -> {
                                        SettingsScreen(
                                            onBack = ::navigateBack,
                                            onOpenStoreReview = ::openPlayStoreReview,
                                            onCheckForUpdate = { playUpdateManager.checkForUpdate(true) },
                                            themeMode = themeMode,
                                            onThemeModeChange = { selectedMode ->
                                                appearancePreferences.themeMode = selectedMode
                                                themeMode = selectedMode
                                            },
                                            languageTag = AppCompatDelegate.getApplicationLocales()
                                                .get(0)
                                                ?.toLanguageTag(),
                                            onLanguageChange = ::setApplicationLanguage,
                                            isPremium = premiumState.isPremium,
                                            premiumPrice = premiumState.formattedPrice,
                                            premiumStoreStatus = premiumState.storeStatus,
                                            premiumOperationInProgress =
                                                premiumState.operationInProgress,
                                            premiumTestMode = premiumState.isTestMode,
                                            onBuyPremium = {
                                                premiumBillingManager.launchPurchase(this@MainActivity)
                                            },
                                            onRestorePremium = {
                                                premiumBillingManager.restorePurchases()
                                            },
                                            showPrivacyOptions = !premiumState.isPremium &&
                                                privacyOptionsRequired,
                                            onPrivacyOptions = privacyOptions@{
                                                if (premiumState.isPremium ||
                                                    !::adConsentManager.isInitialized
                                                ) return@privacyOptions
                                                adConsentManager.showPrivacyOptions { result ->
                                                    adTelemetry.consentResult(
                                                        result.canRequestAds,
                                                        result.error?.errorCode,
                                                    )
                                                    privacyOptionsRequired = result.privacyOptionsRequired
                                                    if (result.canRequestAds) initializeAds()
                                                }
                                            }
                                        )
                                    }
                                    }
                                }
                            }
                        }
                        if (!premiumState.isPremium && ::adConsentManager.isInitialized) {
                            PersistentBanner(
                                canRequestAds = adsReady && adConsentManager.canRequestAds &&
                                    !premiumState.isPremium,
                                runtimeConfig = adRuntimeConfig,
                                trafficGuard = adTrafficGuard,
                                telemetry = adTelemetry,
                            )
                        }
                    }
                }

                if (showUpdateReadyDialog) {
                    UpdateReadyDialog(
                        onRestart = {
                            showUpdateReadyDialog = false
                            playUpdateManager.completeUpdate()
                        },
                        onLater = { showUpdateReadyDialog = false },
                    )
                }
            }
        }

        if (savedInstanceState == null) playUpdateManager.checkForUpdate(false)
    }

    override fun onResume() {
        super.onResume()
        if (::playUpdateManager.isInitialized) playUpdateManager.resumeInterruptedUpdate()
        if (::premiumBillingManager.isInitialized) premiumBillingManager.refreshPurchases()
        if (reviewCheckOnNextResume) {
            reviewCheckOnNextResume = false
            requestAutomaticReviewIfEligible()
        }
    }

    override fun onDestroy() {
        if (::playUpdateManager.isInitialized) playUpdateManager.close()
        if (::interstitialAdManager.isInitialized) interstitialAdManager.clear()
        if (::premiumBillingManager.isInitialized) premiumBillingManager.close()
        super.onDestroy()
    }

    private fun initializeAdFeaturesIfNeeded() {
        if (adFeaturesInitialized || premiumState.isPremium) return
        adFeaturesInitialized = true
        AdRemoteConfig(this).fetchOnce { updatedConfig ->
            runOnUiThread {
                adRuntimeConfig = updatedConfig
                if (::interstitialAdManager.isInitialized) {
                    interstitialAdManager.refreshPolicy()
                }
            }
        }
        adConsentManager = AdConsentManager(this)
        interstitialAdManager = InterstitialAdManager(
            activity = this,
            lifecycleOwner = this,
            canRequestAds = {
                adsReady && adConsentManager.canRequestAds && !premiumState.isPremium
            },
            runtimeConfig = { adRuntimeConfig },
            trafficGuard = adTrafficGuard,
            telemetry = adTelemetry,
        )
        adConsentManager.gatherConsent { result ->
            adTelemetry.consentResult(result.canRequestAds, result.error?.errorCode)
            privacyOptionsRequired = result.privacyOptionsRequired
            if (result.canRequestAds) initializeAds()
        }
    }

    private fun initializeAds() {
        if (premiumState.isPremium || adsReady || !::interstitialAdManager.isInitialized) return
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(this@MainActivity) {
                runOnUiThread {
                    adsReady = true
                    if (!premiumState.isPremium) interstitialAdManager.preload()
                }
            }
        }
    }

    private fun openKeyboardSettings() {
        reviewPromptCoordinator.recordKeyboardSettingsOpened()
        reviewCheckOnNextResume = true
        runCatching { startActivity(Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS)) }
            .getOrElse { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun requestAutomaticReviewIfEligible(): Boolean {
        if (!reviewPromptCoordinator.shouldRequestAutomaticReview()) return false
        reviewPromptCoordinator.recordAutomaticPromptAttempt()
        playReviewManager.requestReview { }
        return true
    }

    private fun openPlayStoreReview() {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            "market://details?id=$packageName".toUri()
        ).apply {
            setPackage("com.android.vending")
        }
        try {
            startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://play.google.com/store/apps/details?id=$packageName".toUri()
                )
            )
        }
    }

    private fun setApplicationLanguage(languageTag: String?) {
        val locales = languageTag
            ?.let(LocaleListCompat::forLanguageTags)
            ?: LocaleListCompat.getEmptyLocaleList()
        if (locales != AppCompatDelegate.getApplicationLocales()) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    override fun onNoUpdateAvailable() = toast(R.string.update_not_available)
    override fun onUpdateCheckFailed(userInitiated: Boolean) { if (userInitiated) toast(R.string.update_check_failed) }
    override fun onUpdateNotAllowed() = toast(R.string.update_not_supported)
    override fun onUpdateError() = toast(R.string.update_error)

    override fun onUpdateDownloaded() {
        if (isFinishing || isDestroyed || showUpdateReadyDialog) return
        showUpdateReadyDialog = true
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
