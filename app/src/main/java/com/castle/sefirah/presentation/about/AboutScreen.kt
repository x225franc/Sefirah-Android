package com.castle.sefirah.presentation.about

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.castle.sefirah.BuildConfig
import com.castle.sefirah.navigation.Graph
import com.castle.sefirah.navigation.SettingsRouteScreen
import com.castle.sefirah.presentation.about.components.LinkIcon
import com.castle.sefirah.presentation.main.ConnectionViewModel
import com.castle.sefirah.presentation.settings.components.LogoHeader
import com.castle.sefirah.presentation.settings.components.TextPreferenceWidget
import kotlinx.coroutines.launch
import sefirah.common.R
import sefirah.data.repository.ReleaseRepository
import sefirah.presentation.icons.CustomIcons
import sefirah.presentation.icons.Discord
import sefirah.presentation.icons.Github

@Composable
fun AboutScreen(rootNavController: NavController, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val backStackState = rootNavController.currentBackStackEntryAsState().value
    val backStackEntry = remember(key1 = backStackState) {
        rootNavController.getBackStackEntry(Graph.MainScreenGraph)
    }
    val connectionViewModel: ConnectionViewModel = hiltViewModel(backStackEntry)
    val isCheckingForUpdate by connectionViewModel.isCheckingForUpdate.collectAsState()
    var isCheckUpdatesLoading by remember { mutableStateOf(false) }
    var isChangelogLoading by remember { mutableStateOf(false) }

    fun openChangelog() {
        if (isCheckingForUpdate) return
        scope.launch {
            isChangelogLoading = true
            try {
                when (connectionViewModel.checkForUpdate(force = true)) {
                    is ReleaseRepository.Result.NewUpdate,
                    is ReleaseRepository.Result.NoNewUpdate -> {
                        if (connectionViewModel.updateInfo.value != null) {
                            rootNavController.navigate(SettingsRouteScreen.NewUpdateScreen.route)
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.update_check_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    ReleaseRepository.Result.Error -> {
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_check_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } finally {
                isChangelogLoading = false
            }
        }
    }

    fun checkForUpdates() {
        if (isCheckingForUpdate) return
        scope.launch {
            isCheckUpdatesLoading = true
            try {
                when (connectionViewModel.checkForUpdate(force = true)) {
                    is ReleaseRepository.Result.NewUpdate -> {
                        rootNavController.navigate(SettingsRouteScreen.NewUpdateScreen.route)
                    }
                    is ReleaseRepository.Result.NoNewUpdate -> {
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_check_no_updates),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    ReleaseRepository.Result.Error -> {
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_check_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } finally {
                isCheckUpdatesLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                title = { Text(stringResource(id = R.string.about)) },
                navigationIcon = {
                    IconButton(
                        onClick = { rootNavController.navigateUp() }
                    ) {
                        Icon(painterResource(R.drawable.ic_arrow_back), "Back")
                    }
                },
            )
        }
    ) { contentPadding ->
        LazyColumn(contentPadding = contentPadding) {
            item {
                LogoHeader()
            }

            item {
                TextPreferenceWidget(
                    title = stringResource(R.string.version),
                    subtitle = BuildConfig.VERSION_NAME,
                )
            }

            if (BuildConfig.CHECK_FOR_UPDATES) item {
                TextPreferenceWidget(
                    title = stringResource(R.string.check_for_updates),
                    onPreferenceClick = { checkForUpdates() },
                    widget = if (isCheckUpdatesLoading) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        null
                    },
                )
            }

            item {
                TextPreferenceWidget(
                    title = stringResource(R.string.changelog),
                    onPreferenceClick = { openChangelog() },
                    widget = if (isChangelogLoading) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        null
                    },
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    LinkIcon(
                        label = "Discord",
                        icon = CustomIcons.Discord,
                        url = "https://discord.gg/MuvMqv4MES",
                    )

                    LinkIcon(
                        label = "GitHub",
                        icon = CustomIcons.Github,
                        url = "https://github.com/shrimqy/Sefirah-Android",
                    )

                    LinkIcon(
                        label = "Donation",
                        icon = ImageVector.vectorResource(R.drawable.ic_attach_money),
                        url = "https://linktr.ee/shrimqy",
                    )
                }
            }
        }
    }
}
