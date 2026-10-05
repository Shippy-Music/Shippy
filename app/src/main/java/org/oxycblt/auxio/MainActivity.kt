/*
 * Copyright (c) 2021 Auxio Project
 * MainActivity.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.oxycblt.auxio.databinding.ActivityMainBinding
import org.oxycblt.auxio.databinding.ActivityR16MigrationBinding
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.state.DeferredPlayback
import org.oxycblt.auxio.shippy.playback.PlaybackStartResult
import org.oxycblt.auxio.shippy.playback.ShippyPlaybackController
import org.oxycblt.auxio.shippy.r16.authority.R16AuthorityEntryPoint
import org.oxycblt.auxio.shippy.r16.authority.R16AuthorityMode
import org.oxycblt.auxio.shippy.r16.migration.R16MigrationProcessGate
import org.oxycblt.auxio.shippy.r16.search.R16GlobalSearchFragment
import org.oxycblt.auxio.shippy.share.ShippyTrackLinkCodec
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.isNight
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat
import timber.log.Timber as L

/**
 * Auxio's single [AppCompatActivity].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val playbackModel: PlaybackViewModel by viewModels()
    private var authorityMode = R16AuthorityMode.ACTIVE_UNAVAILABLE
    private var activeTabId: Int = R.id.shippy_home_fragment
    @Inject lateinit var uiSettings: UISettings
    @Inject lateinit var migrationGate: R16MigrationProcessGate
    @Inject lateinit var shippyPlaybackController: Lazy<ShippyPlaybackController>

    override fun onCreate(savedInstanceState: Bundle?) {
        // Activity field injection happens from Hilt's super.onCreate(). Resolve the selector
        // from the already-created application component because host selection must happen first
        // to decide whether Fragment state is safe to restore.
        authorityMode =
            EntryPointAccessors.fromApplication(
                    applicationContext,
                    R16AuthorityEntryPoint::class.java,
                )
                .authoritySelector()
                .select()
        // Allow state restoration for LEGACY and ACTIVE hosts; isolated hosts (like MIGRATION)
        // do not restore legacy child trees.
        super.onCreate(
            savedInstanceState.takeIf {
                authorityMode == R16AuthorityMode.LEGACY || authorityMode == R16AuthorityMode.ACTIVE
            }
        )
        setupTheme()
        when (authorityMode) {
            R16AuthorityMode.LEGACY -> {
                // Inflate the views after setting up the theme so that the theme attributes are
                // applied.
                val binding = ActivityMainBinding.inflate(layoutInflater)
                setContentView(binding.root)
                setupEdgeToEdge(binding.root)
                L.d("Activity created")
            }
            R16AuthorityMode.ACTIVE_UNAVAILABLE -> {
                setContentView(R.layout.activity_r16_unavailable)
                setupEdgeToEdge(findViewById(android.R.id.content))
                L.w("R16 is active but its runtime is unavailable; activity failed closed")
            }
            R16AuthorityMode.ACTIVE -> {
                // This host contains only canonical R16 UI; it never restores the legacy graph.
                setContentView(R.layout.activity_r16_active)
                setupEdgeToEdge(findViewById(android.R.id.content))
                val bottomNav =
                    findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                        R.id.r16_bottom_nav
                    )
                val miniPlayerContainer = findViewById<View>(R.id.r16_mini_player_container)

                val tabHomeTag = "r16-tab-home"
                val tabSearchTag = "r16-tab-search"
                val tabLibraryTag = "r16-tab-library"
                val tabCrewTag = "r16-tab-crew"

                val tabs =
                    listOf(
                        R.id.shippy_home_fragment to tabHomeTag,
                        R.id.search_fragment to tabSearchTag,
                        R.id.library_fragment to tabLibraryTag,
                        R.id.crew_fragment to tabCrewTag,
                    )

                fun instantiateTab(tabId: Int): Fragment =
                    when (tabId) {
                        R.id.shippy_home_fragment ->
                            org.oxycblt.auxio.shippy.r16.home.R16HomeFragment()
                        R.id.search_fragment -> R16GlobalSearchFragment()
                        R.id.library_fragment ->
                            org.oxycblt.auxio.shippy.r16.library.R16LibraryFragment()
                        R.id.crew_fragment -> org.oxycblt.auxio.shippy.crew.ui.CrewFragment()
                        else -> org.oxycblt.auxio.shippy.r16.home.R16HomeFragment()
                    }

                fun selectTab(tabId: Int, popBackStack: Boolean = true) {
                    if (popBackStack) {
                        supportFragmentManager.popBackStackImmediate(
                            null,
                            FragmentManager.POP_BACK_STACK_INCLUSIVE,
                        )
                    }
                    val targetTag = tabs.firstOrNull { it.first == tabId }?.second ?: tabHomeTag
                    val restoredDetail =
                        if (!popBackStack && supportFragmentManager.backStackEntryCount > 0) {
                            supportFragmentManager.fragments.lastOrNull {
                                it.id == R.id.r16_active_content && !it.isHidden
                            }
                        } else null
                    val transaction =
                        supportFragmentManager.beginTransaction().setReorderingAllowed(true)

                    var targetFrag = supportFragmentManager.findFragmentByTag(targetTag)
                    if (targetFrag == null) {
                        targetFrag = instantiateTab(tabId)
                        transaction.add(R.id.r16_active_content, targetFrag, targetTag)
                    }
                    if (restoredDetail == null) {
                        transaction.show(targetFrag)
                        transaction.setMaxLifecycle(targetFrag, Lifecycle.State.RESUMED)
                        transaction.setPrimaryNavigationFragment(targetFrag)
                    } else {
                        transaction.hide(targetFrag)
                        transaction.setMaxLifecycle(targetFrag, Lifecycle.State.STARTED)
                        transaction.setPrimaryNavigationFragment(restoredDetail)
                    }

                    for ((_, tag) in tabs) {
                        val frag = supportFragmentManager.findFragmentByTag(tag) ?: continue
                        if (tag == targetTag && restoredDetail == null) {
                            transaction.show(frag)
                            transaction.setMaxLifecycle(frag, Lifecycle.State.RESUMED)
                        } else {
                            transaction.hide(frag)
                            transaction.setMaxLifecycle(frag, Lifecycle.State.STARTED)
                        }
                    }
                    transaction.commit()
                    activeTabId = tabId
                    bottomNav.menu.findItem(tabId)?.isChecked = true
                }

                bottomNav.setOnItemSelectedListener { item ->
                    selectTab(item.itemId)
                    true
                }

                val initialTab =
                    savedInstanceState?.getInt(KEY_ACTIVE_TAB_ID, R.id.shippy_home_fragment)
                        ?: R.id.shippy_home_fragment
                selectTab(initialTab, popBackStack = false)

                onBackPressedDispatcher.addCallback(
                    this,
                    object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            val currentFrag =
                                supportFragmentManager.findFragmentById(R.id.r16_active_content)
                            if (
                                currentFrag
                                    is
                                    org.oxycblt.auxio.shippy.r16.playback.ui.R16NowPlayingFragment
                            ) {
                                supportFragmentManager.popBackStack()
                                return
                            }
                            if (supportFragmentManager.backStackEntryCount > 0) {
                                supportFragmentManager.popBackStack()
                                return
                            }
                            if (activeTabId != R.id.shippy_home_fragment) {
                                selectTab(R.id.shippy_home_fragment, popBackStack = false)
                                return
                            }
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    },
                )

                supportFragmentManager.addOnBackStackChangedListener {
                    val isNowPlaying =
                        supportFragmentManager.fragments.any {
                            it.id == R.id.r16_active_content &&
                                it is org.oxycblt.auxio.shippy.r16.playback.ui.R16NowPlayingFragment
                        }
                    miniPlayerContainer.isVisible = !isNowPlaying
                    bottomNav.isVisible = !isNowPlaying
                }
                L.i("R16 ACTIVE Home host created")
            }
            else -> {
                val binding = ActivityR16MigrationBinding.inflate(layoutInflater)
                setContentView(binding.root)
                setupEdgeToEdge(binding.root)
                L.i("R16 migration host created; legacy navigation was not inflated")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (authorityMode == R16AuthorityMode.ACTIVE) {
            outState.putInt(KEY_ACTIVE_TAB_ID, activeTabId)
        }
    }

    override fun onResume() {
        super.onResume()
        when (authorityMode) {
            R16AuthorityMode.ACTIVE -> {
                startService(
                    Intent(this, AuxioService::class.java)
                        .setAction(AuxioService.ACTION_START)
                        .putExtra(AuxioService.INTENT_KEY_START_ID, IntegerTable.START_ID_ACTIVITY)
                )
                return
            }
            R16AuthorityMode.LEGACY -> Unit
            else -> return
        }

        migrationGate.runWithLegacyAccess {
            startService(
                Intent(this, AuxioService::class.java)
                    .setAction(AuxioService.ACTION_START)
                    .putExtra(AuxioService.INTENT_KEY_START_ID, IntegerTable.START_ID_ACTIVITY)
            )

            if (!startIntentActionAllowed(intent)) {
                // No intent action to do, just restore the previously saved state.
                playbackModel.playDeferred(DeferredPlayback.RestoreState(false))
            }
        } ?: L.w("Skipping legacy playback while R16 migration is active")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (authorityMode != R16AuthorityMode.LEGACY) return
        startIntentAction(intent)
    }

    private fun setupTheme() {
        // Apply the theme configuration.
        AppCompatDelegate.setDefaultNightMode(uiSettings.theme)
        // Apply the color scheme. The black theme requires it's own set of themes since
        // it's not possible to modify the themes at run-time.
        if (isNight && uiSettings.useBlackTheme) {
            L.d("Applying black theme [accent ${uiSettings.accent}]")
            setTheme(uiSettings.accent.blackTheme)
        } else {
            L.d("Applying normal theme [accent ${uiSettings.accent}]")
            setTheme(uiSettings.accent.theme)
        }
    }

    private fun setupEdgeToEdge(contentView: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        contentView.setOnApplyWindowInsetsListener { view, insets ->
            // Automatically inset the view to the left/right, as component support for
            // these insets are highly lacking.
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(left = bars.left, right = bars.right)
            insets
        }
    }

    /**
     * Transform an [Intent] given to [MainActivity] into a [DeferredPlayback] that can be used in
     * the playback system.
     *
     * @param intent The (new) [Intent] given to this [MainActivity], or null if there is no intent.
     * @return true If the analogous [DeferredPlayback] to the given [Intent] was started, false
     *   otherwise.
     */
    private fun startIntentAction(intent: Intent?): Boolean {
        if (authorityMode != R16AuthorityMode.LEGACY) return false
        return migrationGate.runWithLegacyAccess { startIntentActionAllowed(intent) }
            ?: run {
                L.w("Skipping legacy intent while R16 migration is active")
                false
            }
    }

    private fun startIntentActionAllowed(intent: Intent?): Boolean {
        if (intent == null) {
            // Nothing to do.
            L.d("No intent to handle")
            return false
        }

        if (intent.getBooleanExtra(KEY_INTENT_USED, false)) {
            // Don't commit the action, but also return that the intent was applied.
            // This is because onStart can run multiple times, and thus we really don't
            // want to return false and override the original delayed action with a
            // RestoreState action.
            L.d("Already used this intent")
            return true
        }
        if (intent.action == Intent.ACTION_VIEW && isShippyTrackLink(intent)) {
            intent.putExtra(KEY_INTENT_USED, true)
            val track = ShippyTrackLinkCodec.decode(intent.data.toString())
            if (track == null) {
                showToast(R.string.err_shippy_track_link)
            } else {
                lifecycleScope.launch {
                    migrationGate.withLegacyAccess {
                        when (shippyPlaybackController.get().play(track)) {
                            is PlaybackStartResult.Started -> Unit
                            is PlaybackStartResult.Failed ->
                                showToast(R.string.msg_shippy_track_unavailable)
                        }
                    }
                }
            }
            return true
        }

        val action =
            when (intent.action) {
                Intent.ACTION_VIEW -> DeferredPlayback.Open(intent.data ?: return false)
                Auxio.INTENT_KEY_SHORTCUT_SHUFFLE -> DeferredPlayback.ShuffleAll
                else -> {
                    L.w("Unexpected intent ${intent.action}")
                    return false
                }
            }
        intent.putExtra(KEY_INTENT_USED, true)
        L.d("Translated intent to $action")
        playbackModel.playDeferred(action)
        return true
    }

    private fun isShippyTrackLink(intent: Intent): Boolean =
        intent.data?.scheme == "shippy" && intent.data?.host == "track"

    private companion object {
        const val KEY_INTENT_USED = BuildConfig.APPLICATION_ID + ".key.FILE_INTENT_USED"
        const val KEY_ACTIVE_TAB_ID = "key_r16_active_tab_id"
    }
}

/**
 * Adds an R16 destination above its caller without replacing the caller. This keeps the exact
 * root/detail Fragment instance (and its in-memory scroll/search state) alive until Back.
 */
fun Fragment.pushR16Destination(destination: Fragment, backStackName: String) {
    val manager = requireActivity().supportFragmentManager
    val origin =
        manager.fragments.lastOrNull { it.id == R.id.r16_active_content && !it.isHidden } ?: return
    manager
        .beginTransaction()
        .setReorderingAllowed(true)
        .hide(origin)
        .setMaxLifecycle(origin, Lifecycle.State.STARTED)
        .add(R.id.r16_active_content, destination)
        .setMaxLifecycle(destination, Lifecycle.State.RESUMED)
        .setPrimaryNavigationFragment(destination)
        .addToBackStack(backStackName)
        .commit()
}
