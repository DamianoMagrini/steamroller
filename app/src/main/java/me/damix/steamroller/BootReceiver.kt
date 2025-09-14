package me.damix.steamroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
	override fun onReceive(ctx: Context, intent: Intent) {
		if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
			var sessionEndTs = runBlocking { ctx.dataStore.data.first()[SESSION_END_TS] ?: 0 }
			val sessionRunning = sessionEndTs >= System.currentTimeMillis()
			if (sessionRunning) {
				val intent = Intent(ctx, MainActivity::class.java)
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				ctx.startActivity(intent)
			}
		}
	}
}
