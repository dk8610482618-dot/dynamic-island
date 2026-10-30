package com.dynamicisland.host

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun CapabilityPanel() {
 val c=LocalContext.current
 val phone=c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED
 val bluetooth=Build.VERSION.SDK_INT<31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
 Column(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
  Text("Device capabilities",style=MaterialTheme.typography.titleLarge,color=Color.White)
  listOf(
   "Calls" to if(phone) "Call state enabled; answer/end require Android’s dialer role." else "Optional phone permission not granted.",
   "Multi-SIM" to "Available subscriptions are tracked separately; SIM changes are refreshed with feature permissions.",
   "Accessories" to if(bluetooth) "Connection/name access enabled. Battery data depends on accessory and firmware." else "Optional Bluetooth permission not granted.",
   "Microphone" to "Orange dot for recognized active recordings reported by Android. Some system or vendor captures may be omitted; use the system privacy indicator for complete status.",
   "Camera" to "Global camera activity is not exposed to ordinary apps. Use Android’s privacy indicator.",
   "Charging" to "Battery status is reported by Android. Fast-charge current is an estimate, not a charger specification.",
   "Hotspot" to "Best-effort vendor broadcasts; not universally available through public APIs.",
   "Lock screen" to "Generic status only. Secure screens and OEM policy can suppress the overlay."
  ).forEach{(name,detail)->Text("$name · $detail",color=Color(0xFFB8C1BA),style=MaterialTheme.typography.bodySmall)}
 }
}
