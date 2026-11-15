package com.jbsoft.jobq.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.jbsoft.jobq.R
import com.jbsoft.jobq.ui.detail.JobDetailScreen
import com.jbsoft.jobq.ui.models.ModelsScreen
import com.jbsoft.jobq.ui.profile.ProfileScreen
import com.jbsoft.jobq.ui.saved.SavedScreen
import com.jbsoft.jobq.ui.search.SearchScreen
import kotlinx.serialization.Serializable

@Serializable data object SearchKey : NavKey
@Serializable data object SavedKey : NavKey
@Serializable data object ProfileKey : NavKey
@Serializable data object ModelsKey : NavKey
@Serializable data class JobDetailKey(val jobId: String) : NavKey

private data class Tab(val key: NavKey, val label: Int, val icon: ImageVector)

private val tabs = listOf(
	Tab(SearchKey, R.string.tab_search, Icons.Filled.Search),
	Tab(SavedKey, R.string.tab_saved, Icons.Filled.Favorite),
	Tab(ProfileKey, R.string.tab_profile, Icons.Filled.Person),
	Tab(ModelsKey, R.string.tab_models, Icons.Filled.Build),
)

@Composable
fun JobQNavigation() {
	val backStack = rememberNavBackStack(SearchKey)
	val current = backStack.lastOrNull()
	val currentTab = tabs.firstOrNull { it.key == current }

	// Search is the home tab: other tabs sit on top of it, so Back returns to Search before leaving the app.
	fun openTab(key: NavKey) {
		if (key == current) return
		while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
		if (key != SearchKey) backStack.add(key)
	}

	Scaffold(
		bottomBar = {
			if (currentTab != null) {
				NavigationBar {
					tabs.forEach { tab ->
						NavigationBarItem(
							selected = tab == currentTab,
							onClick = { openTab(tab.key) },
							icon = { Icon(tab.icon, contentDescription = null) },
							label = { Text(stringResource(tab.label)) },
						)
					}
				}
			}
		},
	) { padding ->
		// Tab screens draw behind the bars and pad their own content; the detail screen has its own Scaffold.
		val tabPadding: PaddingValues = padding
		NavDisplay(
			backStack = backStack,
			onBack = { backStack.removeLastOrNull() },
			modifier = Modifier.consumeWindowInsets(if (currentTab != null) padding else PaddingValues()),
			entryDecorators = listOf(
				rememberSaveableStateHolderNavEntryDecorator(),
				rememberViewModelStoreNavEntryDecorator(),
			),
			entryProvider = entryProvider {
				entry<SearchKey> {
					SearchScreen(
						onOpenJob = { backStack.add(JobDetailKey(it)) },
						onOpenModels = { openTab(ModelsKey) },
						onOpenProfile = { openTab(ProfileKey) },
						contentPadding = tabPadding,
					)
				}
				entry<SavedKey> { SavedScreen(onOpenJob = { backStack.add(JobDetailKey(it)) }, contentPadding = tabPadding) }
				entry<ProfileKey> { ProfileScreen(onOpenModels = { openTab(ModelsKey) }, contentPadding = tabPadding) }
				entry<ModelsKey> { ModelsScreen(contentPadding = tabPadding) }
				entry<JobDetailKey> { key -> JobDetailScreen(jobId = key.jobId, onBack = { backStack.removeLastOrNull() }) }
			},
		)
	}
}
