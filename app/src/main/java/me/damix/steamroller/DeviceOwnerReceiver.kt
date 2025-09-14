package me.damix.steamroller

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class DeviceOwnerReceiver : DeviceAdminReceiver() {
	@Override
	override fun onProfileProvisioningComplete(ctx: Context, intent: Intent) {
		val manager = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
		val componentName = ComponentName(ctx.applicationContext, DeviceOwnerReceiver::class.java)

		manager.setProfileName(componentName, ctx.getString(R.string.profile_name))

		val intent = Intent(ctx, MainActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		ctx.startActivity(intent)
	}

	@Override
	override fun onEnabled(ctx: Context, intent: Intent) {
		super.onEnabled(ctx, intent)
		// TODO update app
	}
}
