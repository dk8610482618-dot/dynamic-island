package com.dynamicisland.host

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dynamicisland.engine.core.model.*
import com.dynamicisland.engine.settings.*
import com.dynamicisland.engine.ui.FixedIslandCapsule
import org.json.JSONObject
import org.json.JSONArray

/** Local simulations and explicit, non-sensitive configuration. No fixture reaches the live event bus. */
@Composable
internal fun FeatureSettingsPanel(options: BehaviorOptions, onOptions: (BehaviorOptions) -> Unit) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    val settings = remember { IslandSettings.get(context) }
    val widthScale by settings.widthScale.collectAsState()
    val heightScale by settings.heightScale.collectAsState()
    val current by rememberUpdatedState(options)
    val currentUpdate by rememberUpdatedState(onOptions)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) result = runCatching {
            val data = JSONObject().put("version",1)
                .put("width",settings.widthScale.value).put("height",settings.heightScale.value)
                .put("x",settings.offset.value.offsetXDp).put("y",settings.offset.value.offsetYDp)
                .put("clearSystemBars",current.clearSystemBars).put("showOnLockScreen",current.showOnLockScreen)
                .put("notifications",current.notificationsEnabled).put("systemAlerts",current.systemAlertsEnabled)
                .put("quietHours",current.quietHours).put("quietStart",current.quietStart).put("quietEnd",current.quietEnd)
                .put("hideText",current.hideNotificationText)
                .put("blockedPackages",JSONArray(current.blockedPackages.sorted()))
                .put("secondaryBubble",current.secondaryBubble).put("priority",current.priority.name)
                .put("appearance",current.appearance.name).put("motion",current.motion.name)
                .put("reducedMotion",current.reducedMotion).put("hapticIntensity",current.hapticIntensity)
                .put("tapAction",current.tapAction.name).put("holdAction",current.holdAction.name)
                .put("swipeLeftAction",current.swipeLeftAction.name).put("swipeRightAction",current.swipeRightAction.name)
            requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(data.toString(2)) }
            "Settings exported. Notification content and permission grants are excluded."
        }.getOrElse { "Export failed. Your settings are unchanged." }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) result = runCatching {
            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (out.size() <= 65536) {
                    val count = stream.read(buffer,0,minOf(buffer.size,65537-out.size()))
                    if (count < 0) break
                    out.write(buffer,0,count)
                }
                out.toByteArray() }
            require(bytes.size <= 65536)
            val data = JSONObject(bytes.toString(Charsets.UTF_8))
            require(data.getInt("version") == 1)
            fun scale(key: String): Float = data.getDouble(key).toFloat().also { require(it.isFinite() && it in .5f..1.4f) }
            fun offset(key: String): Float = data.getDouble(key).toFloat().also { require(it.isFinite() && it in -2000f..2000f) }
            val width = scale("width"); val height = scale("height"); val x = offset("x"); val y = offset("y")
            val start = data.getInt("quietStart");val end = data.getInt("quietEnd")
            require(start in 0..1439 && end in 0..1439)
            val list = data.getJSONArray("blockedPackages")
            require(list.length() <= 200)
            val packages = (0 until list.length()).map { list.getString(it).also { p -> require(validPackage(p)) } }.toSet()
            fun <T : Enum<T>> enumField(key:String, values:Array<T>, fallback:T):T = if(!data.has(key)) fallback else values.firstOrNull {it.name==data.getString(key)} ?: error("Unsupported option")
            val haptics=data.optInt("hapticIntensity",current.hapticIntensity).also {require(it in 0..3)}
            val next = current.copy(
                secondaryBubble=data.optBoolean("secondaryBubble",current.secondaryBubble),
                priority=enumField("priority",com.dynamicisland.core.geometry.ActivityPriority.values(),current.priority),
                appearance=enumField("appearance",com.dynamicisland.core.geometry.AppearancePreset.values(),current.appearance),
                motion=enumField("motion",com.dynamicisland.core.geometry.MotionPreset.values(),current.motion),
                reducedMotion=data.optBoolean("reducedMotion",current.reducedMotion),hapticIntensity=haptics,
                tapAction=enumField("tapAction",com.dynamicisland.core.geometry.GestureAction.values(),current.tapAction),
                holdAction=enumField("holdAction",com.dynamicisland.core.geometry.GestureAction.values(),current.holdAction),
                swipeLeftAction=enumField("swipeLeftAction",com.dynamicisland.core.geometry.GestureAction.values(),current.swipeLeftAction),
                swipeRightAction=enumField("swipeRightAction",com.dynamicisland.core.geometry.GestureAction.values(),current.swipeRightAction),clearSystemBars=data.getBoolean("clearSystemBars"),showOnLockScreen=data.getBoolean("showOnLockScreen"),
                notificationsEnabled=data.getBoolean("notifications"),systemAlertsEnabled=data.getBoolean("systemAlerts"),
                quietHours=data.getBoolean("quietHours"),quietStart=start,quietEnd=end,
                hideNotificationText=data.getBoolean("hideText"),blockedPackages=packages)
            // All fields validated before applying. Import never starts services or grants permissions.
            settings.setWidthScale(width);settings.setHeightScale(height);settings.setOffsetX(x);settings.setOffsetY(y);settings.flush()
            currentUpdate(next)
            "Settings imported. Service state and Android permission grants were preserved."
        }.getOrElse { "Import rejected: invalid or unsupported settings file." }
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxWidth().padding(vertical=20.dp)) {
        ExperienceSettingsPanel(options,onOptions)
        Text("Preview lab",style=MaterialTheme.typography.titleLarge,color=Color.White)
        val samples = listOf("Music","Incoming call","Active call","Timer","Stopwatch","Navigation","Charging","Low battery","Bluetooth","Notification","Two activities","Idle")
        var sample by remember { mutableStateOf("Music") }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            samples.forEach { name -> FilterChip(selected=sample==name,onClick={sample=name},label={Text(name)}) }
        }
        val fixture = remember(sample) { previewState(sample) }
        Box(Modifier.fillMaxWidth().height(72.dp).background(Color(0xFF282D29)),contentAlignment=androidx.compose.ui.Alignment.Center) {
            Box(Modifier.size((130*widthScale).dp,(38*heightScale).dp)) {
                FixedIslandCapsule(fixture,MediaPlaybackSnapshot(isActive=sample=="Music",isPlaying=sample=="Music"),false,0f,{}, {},options=options)
            }
        }
        Text("Simulation · $sample. Preview actions never control your phone.",color=Color(0xFFB8C1BA))
        Text("Privacy & notifications",style=MaterialTheme.typography.titleLarge,color=Color.White)
        FeatureToggle("Hide notification text",options.hideNotificationText) { onOptions(options.copy(hideNotificationText=it)) }
        Text("Replaces inbox titles with the source app name. Locked overlays always hide message text.",color=Color(0xFFB8C1BA))
        FeatureToggle("Quiet notification alerts",options.quietHours) { onOptions(options.copy(quietHours=it)) }
        Text("Quiet hours silence new notification peeks. Inbox, calls and music remain available.",color=Color(0xFFB8C1BA))
        if (options.quietHours) {
            Text("From ${timeLabel(options.quietStart)} to ${timeLabel(options.quietEnd)}",color=Color.White)
            Slider(value=options.quietStart/60f,onValueChange={onOptions(options.copy(quietStart=it.toInt()*60))},valueRange=0f..23f,steps=22)
            Slider(value=options.quietEnd/60f,onValueChange={onOptions(options.copy(quietEnd=it.toInt()*60))},valueRange=0f..23f,steps=22)
        }
        var packageName by remember { mutableStateOf("") }
        OutlinedTextField(value=packageName,onValueChange={packageName=it},label={Text("Notification app package")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedButton(enabled=validPackage(packageName),onClick={onOptions(options.copy(blockedPackages=options.blockedPackages+packageName));packageName=""}) { Text("Hide this app’s notifications") }
        options.blockedPackages.sorted().forEach { name ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text(name,color=Color.White,modifier=Modifier.weight(1f))
                TextButton(onClick={onOptions(options.copy(blockedPackages=options.blockedPackages-name))}) { Text("Restore") }
            }
        }
        Text("Settings backup",style=MaterialTheme.typography.titleLarge,color=Color.White)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick={export.launch("dynamic-island-settings.json")}) { Text("Export") }
            OutlinedButton(onClick={import.launch(arrayOf("application/json"))}) { Text("Import") }
        }
        result?.let { Text(it,color=Color(0xFFB8C1BA)) }
        val battery by com.dynamicisland.engine.service.DynamicIslandService.battery.collectAsState()
        battery?.let { Text("Battery ${it.percentage}% · ${if(it.isCharging) "Charging" else "Not charging"}",color=Color(0xFFB8C1BA)) }
        CapabilityPanel()
        Text("Diagnostics",style=MaterialTheme.typography.titleLarge,color=Color.White)
        Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\nAccessibility: not requested\nNetwork access: not requested\nCapsule: fixed footprint · Opaque black\nRefresh: ${if(options.highRefreshRate) "120 Hz requested" else "System managed"}\nLock screen: ${if(options.showOnLockScreen) "Requested; OEM policy applies" else "Hidden"}",color=Color(0xFFB8C1BA))
    }
}

private fun validPackage(value: String) = value.length in 3..200 && value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
private fun timeLabel(minute: Int) = "%02d:%02d".format(minute/60,minute%60)
@Composable private fun FeatureToggle(title: String, checked: Boolean, onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text(title,color=Color.White,modifier=Modifier.weight(1f));Switch(checked=checked,onCheckedChange=onChange)
    }
}
internal fun previewState(name: String): IslandState {
    if(name=="Idle") return IslandState.Closed
    val kind=when(name) {
        "Music"->ActivityKind.MEDIA_SESSION;"Incoming call"->ActivityKind.RINGING_CALL;"Active call"->ActivityKind.LIVE_CALL
        "Timer"->ActivityKind.LIVE_TIMER;"Stopwatch"->ActivityKind.LIVE_STOPWATCH;"Navigation"->ActivityKind.NAVIGATION
        "Charging","Low battery"->ActivityKind.BATTERY_CHARGING;"Bluetooth"->ActivityKind.ACCESSORY_BATTERY
        else->ActivityKind.TRANSIENT_NOTIFICATION
    }
    val slot=IslandSlotContent.Symbol(IslandSymbol.BELL)
    val activity=IslandActivity("preview", "local.simulation", name, "Preview only",kind,PriorityRank.TRANSIENT_SYSTEM_ALERT,
        compactLeading=slot,compactTrailing=slot,minimalContent=slot,expandedData=ExpandedRegionData(slot,slot))
    return if(name=="Two activities") IslandState.Minimal(activity,activity.copy(id="second"),2) else IslandState.Compact(activity)
}
