package me.damix.steamroller

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.damix.steamroller.ui.theme.SteamrollerTheme
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
val WHITELIST = stringSetPreferencesKey("whitelist")
val PHONE_CALLS_ALLOWED = booleanPreferencesKey("phone_calls_allowed")
val SESSION_END_TS = longPreferencesKey("session_end_ts")

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
		val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager

		setContent {
			SteamrollerTheme {
				HomeScreen(dpm, vm)
			}
		}
	}
}

fun getAdmin(ctx: Context): ComponentName {
	return ComponentName(ctx, DeviceOwnerReceiver::class.java)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(dpm: DevicePolicyManager, vm: VibratorManager) {
	val ctx = LocalContext.current
	val uriHandler = LocalUriHandler.current
	var isOwner by remember { mutableStateOf(dpm.isDeviceOwnerApp(ctx.packageName)) }
	var whitelistDialogOpen by remember { mutableStateOf(false) }
	var confirmSessionDialogOpen by remember { mutableStateOf(false) }
	var moreMenuExpanded by remember { mutableStateOf(false) }
	var confirmDisableDialogOpen by remember { mutableStateOf(false) }
	var phoneCallsAllowed by remember {
		mutableStateOf((runBlocking {
			ctx.dataStore.data.first()[PHONE_CALLS_ALLOWED] ?: true
		}))
	}
	var apps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
	var whitelist by remember {
		mutableStateOf(runBlocking {
			ctx.dataStore.data.first()[WHITELIST]?.toList() ?: emptyList()
		}.filter { it.contains("/") }.map {
			val parts = it.split("/")
			LaunchableApp(parts[0], parts[1])
		})
	}
	var sessionEndTs by remember {
		mutableLongStateOf(runBlocking {
			ctx.dataStore.data.first()[SESSION_END_TS] ?: 0
		})
	}
	val sessionRunning = sessionEndTs >= System.currentTimeMillis()
	var minutesText by remember { mutableStateOf("25") }

	fun endLock(notify: Boolean) {
		ctx.getActivity()?.stopLockTask()
		sessionEndTs = 0
		if (notify) {
			vm.defaultVibrator.vibrate(
				VibrationEffect.createOneShot(3000L, 255)
			)
		}
	}

	fun startLock() {
		dpm.setLockTaskFeatures(
			getAdmin(ctx), if (phoneCallsAllowed) {
				DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS + DevicePolicyManager.LOCK_TASK_FEATURE_HOME
			} else {
				DevicePolicyManager.LOCK_TASK_FEATURE_NONE
			}
		)
		dpm.setLockTaskPackages(
			getAdmin(ctx), if (phoneCallsAllowed) {
				arrayOf("me.damix.steamroller", "com.android.server.telecom")
			} else {
				arrayOf("me.damix.steamroller")
			} + whitelist.map { it.name })

		ctx.getActivity()?.startLockTask()

		Executors.newSingleThreadScheduledExecutor().schedule({
			endLock(true)
		}, sessionEndTs - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
	}

	fun toggleWhitelistItem(app: LaunchableApp) {
		whitelist = if (whitelist.map { it.name }.contains(app.name)) {
			whitelist.filter { it.name != app.name }
		} else {
			whitelist + app
		}
	}


	LaunchedEffect(Unit) {
		if (isOwner) {
			if (sessionRunning) {
				startLock()
			} else {
				sessionEndTs = 0
			}
		}

		val mainIntent = Intent(Intent.ACTION_MAIN)
		mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
		apps = ctx.packageManager.queryIntentActivities(mainIntent, 0).map { resolveInfo ->
			LaunchableApp(
				resolveInfo.activityInfo.applicationInfo.packageName,
				resolveInfo.activityInfo.applicationInfo.loadLabel(ctx.packageManager).toString()
			)
		}.filter { it.name != "me.damix.steamroller" }.sortedBy { it.label.lowercase() }

		for (app in whitelist) {
			// clean up uninstalled apps
			if (!apps.map { it.name }.contains(app.name)) toggleWhitelistItem(app)
		}
	}
	LaunchedEffect(whitelist) {
		GlobalScope.launch {
			ctx.dataStore.edit { settings ->
				settings[WHITELIST] = whitelist.map { it.name + "/" + it.label }.toSet()
			}
		}
	}
	LaunchedEffect(phoneCallsAllowed) {
		GlobalScope.launch {
			ctx.dataStore.edit { settings ->
				settings[PHONE_CALLS_ALLOWED] = phoneCallsAllowed
			}
		}
	}
	LaunchedEffect(sessionEndTs) {
		GlobalScope.launch {
			ctx.dataStore.edit { settings ->
				settings[SESSION_END_TS] = sessionEndTs
			}
		}
	}

	Scaffold(topBar = {
		CenterAlignedTopAppBar(title = { Text("Steamroller") }, actions = {
			if (isOwner) {
				if (!sessionRunning) {
					IconButton(onClick = { moreMenuExpanded = true }) {
						Icon(painterResource(R.drawable.more_vert_24px), "Menu")
					}
					DropdownMenu(moreMenuExpanded, { moreMenuExpanded = false }) {
						DropdownMenuItem({ Text("Disable Device Owner") }, {
							moreMenuExpanded = false
							confirmDisableDialogOpen = true
						}, leadingIcon = { Icon(painterResource(R.drawable.lock_open_24px), "") })
						DropdownMenuItem({ Text("Edit Whitelist") }, {
							moreMenuExpanded = false
							whitelistDialogOpen = true
						}, leadingIcon = { Icon(painterResource(R.drawable.checklist_24px), "") })
						DropdownMenuItem(
							{ Text("Allow Phone Calls") },
							trailingIcon = {
								Checkbox(
									checked = phoneCallsAllowed,
									onCheckedChange = { v -> phoneCallsAllowed = v },
									enabled = !sessionRunning
								)
							},
							onClick = { phoneCallsAllowed = !phoneCallsAllowed },
							leadingIcon = { Icon(painterResource(R.drawable.phone_in_talk_24px), "") })
						HorizontalDivider()
						DropdownMenuItem({ Text("Chip In") }, onClick = {
							uriHandler.openUri("https://damix.me/donate")
							Toast.makeText(ctx, "Thank you! 💜", Toast.LENGTH_SHORT).show()
						}, leadingIcon = { Icon(painterResource(R.drawable.credit_card_heart_24px), "") })
						DropdownMenuItem(
							{ Text("Help & About") },
							onClick = { uriHandler.openUri("https://damix.me/steamroller") },
							leadingIcon = { Icon(painterResource(R.drawable.info_24px), "") })
					}
				} else {
					IconButton(onClick = { if (sessionEndTs >= System.currentTimeMillis()) endLock(false) }) {
						Icon(painterResource(R.drawable.refresh_24px), "Menu")
					}
				}
			}
		})
	}) { innerPadding ->
		Box(Modifier.padding(innerPadding)) {
			Column(
				Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally
			) {
				Column(
					Modifier
						.padding(16.dp)
						.widthIn(0.dp, 480.dp),
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(16.dp)
				) {
					if (!isOwner) {
						Card(
							Modifier.fillMaxWidth(),
							colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
						) {
							Column(
								modifier = Modifier
									.padding(16.dp)
									.fillMaxWidth(),
								horizontalAlignment = Alignment.CenterHorizontally,
								verticalArrangement = Arrangement.spacedBy(8.dp)
							) {
								Icon(
									painterResource(R.drawable.shield_lock_24px), "Shield Icon", Modifier.size(48.dp)
								)
								Text("Get Started", style = MaterialTheme.typography.titleLarge)
								Text("You are one step away from getting back your focus! Please follow the guide to set up the required permissions")
								Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
									OutlinedButton(
										{ uriHandler.openUri("https://damix.me/steamroller#setup") },
									) {
										Text("Open Guide")
									}
									OutlinedButton(
										{ isOwner = dpm.isDeviceOwnerApp(ctx.packageName) },
									) {
										Text("Refresh")
									}
								}
							}
						}
					} else {
						if (!sessionRunning) {
							Card(
								Modifier.fillMaxWidth(),
								colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
							) {
								Column(
									modifier = Modifier
										.padding(16.dp)
										.fillMaxWidth(),
									horizontalAlignment = Alignment.CenterHorizontally,
									verticalArrangement = Arrangement.spacedBy(8.dp)
								) {
									OutlinedTextField(
										value = minutesText,
										onValueChange = {
											val formatted = it.replace(Regex("[^0-9.]"), "").replace("..", ".")
											val parts = formatted.split(".")
											minutesText = if (parts.size <= 2) {
												formatted
											} else {
												parts[0] + "." + parts.subList(1, parts.size).joinToString("")
											}
										},
										label = { Text("Minutes") },
										modifier = Modifier.fillMaxWidth(),
										keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
									)
									Button(
										modifier = Modifier.fillMaxWidth(),
										onClick = { confirmSessionDialogOpen = true },
										enabled = {
											val minutes = minutesText.toFloatOrNull()
											minutes != null && minutes <= 600
										}()
									) {
										Row(
											verticalAlignment = Alignment.CenterVertically,
											horizontalArrangement = Arrangement.spacedBy(4.dp)
										) {
											Icon(painterResource(R.drawable.lock_24px), "", Modifier.size(16.dp))
											Text("Start Lock")
										}
									}
									Text("Max. 10 hours (600 minutes)", style = MaterialTheme.typography.bodySmall)
								}
							}
						} else {
							Card(
								Modifier.fillMaxWidth(),
								colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
							) {
								ListItem(
									colors = ListItemDefaults.colors(containerColor = Color.Transparent),
									leadingContent = {
										Icon(painterResource(R.drawable.lock_24px), "", Modifier.size(16.dp))
									},
									headlineContent = {
										Text(
											"See you on ${
												SimpleDateFormat(
													"yyyy-MM-dd HH:mm", Locale.US
												).format(sessionEndTs)
											}"
										)
									})
							}
						}

						Text("Whitelisted Apps", style = MaterialTheme.typography.titleMedium)
						Column(
							modifier = Modifier
								.weight(1f)
								.verticalScroll(rememberScrollState()),
							verticalArrangement = Arrangement.spacedBy(4.dp)
						) {
							whitelist.sortedBy { it.label.lowercase() }.forEach { app ->
								Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
									ListItem(
										colors = ListItemDefaults.colors(containerColor = Color.Transparent),
										headlineContent = {
											Text(app.label, overflow = TextOverflow.Ellipsis, softWrap = false)
										},
										trailingContent = {
											if (sessionRunning) {
												IconButton({
													ctx.getActivity()
														?.startActivity(ctx.packageManager.getLaunchIntentForPackage(app.name))
												}) {
													Icon(
														painterResource(R.drawable.open_in_new_24px), "Open App"
													)
												}
											} else {
												IconButton({ whitelist = whitelist.filter { it.name != app.name } }) {
													Icon(
														painterResource(R.drawable.close_24px), "Remove from Whitelist"
													)
												}
											}
										})
								}
							}
						}
					}

					when {
						whitelistDialogOpen -> {
							WhitelistDialog(
								{ whitelistDialogOpen = false },
								apps,
								whitelist,
								{ app -> toggleWhitelistItem(app) })
						}
					}

					when {
						confirmSessionDialogOpen -> {
							val minutes = minutesText.toFloatOrNull() ?: 0f
							val newEndTs = System.currentTimeMillis() + (minutes * 60_000).toLong()
							val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(newEndTs)

							AlertDialog(
								title = { Text("Start Session?") },
								text = { Text("For $minutes minute(s) from now, i.e. until $formatted, you will only be able to use the specified apps. You will not be able to end the session early.") },
								onDismissRequest = { confirmSessionDialogOpen = false },
								confirmButton = {
									Button(
										onClick = {
											val activity = ctx.getActivity()
											if (activity != null) {
												confirmSessionDialogOpen = false
												sessionEndTs = newEndTs
												startLock()
											}
										}) { Text("Start") }
								},
								dismissButton = {
									TextButton(
										onClick = { confirmSessionDialogOpen = false }) {
										Text("Cancel")
									}
								})
						}
					}

					when {
						confirmDisableDialogOpen -> {
							AlertDialog(
								title = { Text("Revoke Device Owner Permissions?") },
								text = { Text("This will make it possible to uninstall the app. When reinstalling it, you'll have to follow the authorization steps again.") },
								onDismissRequest = { confirmDisableDialogOpen = false },
								confirmButton = {
									Button(
										onClick = {
											confirmDisableDialogOpen = false
											try {
												dpm.clearProfileOwner(getAdmin(ctx))
											} catch (_: Throwable) {
											}
											try {
												dpm.clearDeviceOwnerApp("me.damix.steamroller")
											} catch (_: Throwable) {
											}
											isOwner = false
											Toast.makeText(ctx, "Device Owner removed successfully", Toast.LENGTH_SHORT)
												.show()
										}, colors = ButtonDefaults.buttonColors(
											containerColor = MaterialTheme.colorScheme.error,
											contentColor = MaterialTheme.colorScheme.onError
										)
									) {
										Text("Confirm")
									}
								},
								dismissButton = {
									TextButton(onClick = { confirmDisableDialogOpen = false }) {
										Text("Cancel")
									}
								})
						}
					}
				}
			}
		}
	}
}

data class LaunchableApp(val name: String, val label: String)

@Composable
fun WhitelistDialog(
	onDismissRequest: () -> Unit,
	// we get better performance by loading the apps list in advance
	apps: List<LaunchableApp>,
	whitelist: List<LaunchableApp>,
	onToggleWhitelistItem: (app: LaunchableApp) -> Unit
) {
	LocalContext.current

	Dialog(onDismissRequest) {
		Card(
			modifier = Modifier
				.fillMaxWidth()
				.padding(0.dp, 16.dp)
		) {
			Column(
				modifier = Modifier
					.padding(16.dp)
					.fillMaxWidth(),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(8.dp)
			) {
				Text("Whitelist", style = MaterialTheme.typography.titleMedium)
				Text(
					"Note that to be able to make (outgoing) phone calls, you need to check the Phone/Dialer app here, as well as enable the dedicated menu option.",
					style = MaterialTheme.typography.bodySmall,
					modifier = Modifier.fillMaxWidth()
				)

				Column(
					modifier = Modifier
						.verticalScroll(rememberScrollState())
						.weight(1f),
				) {
					apps.forEach { app ->
						val checked = whitelist.map { it.name }.contains(app.name)
						ListItem(
							colors = ListItemDefaults.colors(containerColor = Color.Transparent),
							modifier = Modifier.toggleable(
								role = Role.Checkbox,
								value = checked,
								onValueChange = { onToggleWhitelistItem(app) },
							),
							headlineContent = { Text(app.label) },
							supportingContent = {
								Text(
									app.name, overflow = TextOverflow.Ellipsis, softWrap = false
								)
							},
							trailingContent = {
								Checkbox(
									checked = checked, onCheckedChange = { onToggleWhitelistItem(app) })
							})
					}
				}

				Button(onDismissRequest, modifier = Modifier.fillMaxWidth()) {
					Text("Done")
				}
			}
		}
	}
}

fun Context.getActivity(): ComponentActivity? = when (this) {
	is ComponentActivity -> this
	is ContextWrapper -> baseContext.getActivity()
	else -> null
}
