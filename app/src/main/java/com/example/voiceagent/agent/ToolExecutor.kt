package com.example.voiceagent.agent

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import androidx.core.content.ContextCompat
import com.example.voiceagent.contacts.ContactFinder
import com.example.voiceagent.contacts.ContactMatch
import com.example.voiceagent.engine.ToolSchemas
import com.example.voiceagent.flashlight.FlashlightController
import java.util.Locale
import org.json.JSONObject

sealed interface ToolOutcome {
    data class Done(val modelText: String, val display: String = modelText) : ToolOutcome
    data class NeedsChoice(val utterance: String, val choices: List<ContactMatch>) : ToolOutcome
    data class Failed(val modelText: String, val display: String = modelText) : ToolOutcome
}

/**
 * Executes the phone-side tools. Every result is phrased for the model and,
 * separately, for the on-screen action feed.
 */
class ToolExecutor(
    private val context: Context,
    private val contacts: ContactFinder,
    private val flashlight: FlashlightController,
) {

    suspend fun execute(name: String, arguments: JSONObject): ToolOutcome = when (name) {
        ToolSchemas.CALL_CONTACT -> callContact(arguments.optString("contact_name"))
        ToolSchemas.TOGGLE_FLASHLIGHT -> switchFlashlight(arguments.optString("state"))
        ToolSchemas.OPEN_APP -> openApp(arguments.optString("app_name"))
        ToolSchemas.SET_TIMER -> setTimer(
            arguments.optLong("duration_seconds", 0L),
            arguments.optString("label"),
        )
        ToolSchemas.SET_ALARM -> setAlarm(
            arguments.optInt("hour", -1),
            arguments.optInt("minute", -1),
            arguments.optString("label"),
        )
        else -> ToolOutcome.Failed("I don't know how to do that yet.")
    }

    fun dialContact(match: ContactMatch): ToolOutcome {
        val number = match.number.replace(NUMBER_NOISE, "")
        if (number.isEmpty()) {
            return ToolOutcome.Failed("${match.name}'s number is empty.")
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolOutcome.Done(
                modelText = "Opened the dialer for ${match.name}. " +
                    "The user still has to tap the call button.",
                display = "Dialer opened for ${match.name}",
            )
        } catch (_: ActivityNotFoundException) {
            ToolOutcome.Failed("I couldn't open the dialer.")
        }
    }

    private suspend fun callContact(rawName: String): ToolOutcome {
        val name = rawName.trim()
        if (name.isEmpty()) return ToolOutcome.Failed("I didn't get a contact name.")
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            return ToolOutcome.Failed(
                "Contacts permission is needed. Allow it in the app, then ask again.",
            )
        }

        val matches = contacts.find(name)
        return when {
            matches.isEmpty() -> ToolOutcome.Failed("No contact named \"$name\" was found.")
            matches.size == 1 -> dialContact(matches.first())
            else -> ToolOutcome.NeedsChoice(name, matches)
        }
    }

    private fun switchFlashlight(rawState: String): ToolOutcome {
        val on = when (rawState.trim().lowercase(Locale.ROOT)) {
            "on" -> true
            "off" -> false
            else -> return ToolOutcome.Failed("Tell me whether to turn the flashlight on or off.")
        }
        if (!granted(Manifest.permission.CAMERA)) {
            return ToolOutcome.Failed("Camera permission is needed to switch the flashlight.")
        }
        if (!flashlight.hasFlashlight()) {
            return ToolOutcome.Failed("This phone has no flashlight.")
        }
        if (!flashlight.setTorch(on)) {
            return ToolOutcome.Failed("The flashlight couldn't be switched.")
        }
        return ToolOutcome.Done(
            modelText = if (on) "The flashlight is on." else "The flashlight is off.",
            display = if (on) "Flashlight on" else "Flashlight off",
        )
    }

    private fun openApp(rawName: String): ToolOutcome {
        val name = rawName.trim()
        if (name.isEmpty()) return ToolOutcome.Failed("I didn't get an app name.")

        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = manager.queryIntentActivities(launcher, 0)
            .mapNotNull { info ->
                val label = info.loadLabel(manager).toString().trim()
                if (label.isBlank()) return@mapNotNull null
                val score = matchScore(name, label)
                if (score < MIN_APP_SCORE) null else AppCandidate(info, label, score)
            }

        val best = candidates.maxByOrNull { it.score }
            ?: return ToolOutcome.Failed("I couldn't find an app called \"$name\".")

        val packageName = best.info.activityInfo.packageName
        val launch = manager.getLaunchIntentForPackage(packageName) ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(packageName, best.info.activityInfo.name)
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(launch)
            ToolOutcome.Done("Opened ${best.label}.", "Opened ${best.label}")
        } catch (_: ActivityNotFoundException) {
            ToolOutcome.Failed("I couldn't open ${best.label}.")
        }
    }

    private fun setTimer(seconds: Long, label: String): ToolOutcome {
        if (seconds !in 1..MAX_TIMER_SECONDS) {
            return ToolOutcome.Failed("Give me a timer between 1 second and 24 hours.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Timer" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolOutcome.Failed("No clock app on this phone accepted the timer.")
        }
        return try {
            context.startActivity(intent)
            val text = "Timer set for ${duration(seconds)}."
            ToolOutcome.Done(text, text)
        } catch (_: ActivityNotFoundException) {
            ToolOutcome.Failed("I couldn't start the timer.")
        }
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): ToolOutcome {
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolOutcome.Failed("Give me a valid time, for example 7:30.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Alarm" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolOutcome.Failed("No clock app on this phone accepted the alarm.")
        }
        return try {
            context.startActivity(intent)
            val text = "Alarm set for ${String.format(Locale.US, "%02d:%02d", hour, minute)}."
            ToolOutcome.Done(text, text)
        } catch (_: ActivityNotFoundException) {
            ToolOutcome.Failed("I couldn't start the alarm.")
        }
    }

    private fun matchScore(query: String, label: String): Double {
        val q = query.lowercase(Locale.ROOT).trim()
        val l = label.lowercase(Locale.ROOT).trim()
        if (q.isEmpty() || l.isEmpty()) return 0.0
        if (q == l) return 1.0
        if (l.startsWith(q)) return 0.94
        if (l.contains(q)) return 0.86
        if (q.contains(l)) return 0.76

        val queryTokens = q.split(WHITESPACE).filter { it.isNotEmpty() }
        val labelTokens = l.split(WHITESPACE).filter { it.isNotEmpty() }
        if (queryTokens.isNotEmpty() &&
            queryTokens.all { token -> labelTokens.any { it.startsWith(token) } }
        ) {
            return 0.82
        }
        return 0.0
    }

    private fun duration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        val parts = ArrayList<String>()
        if (hours > 0) parts += "$hours hour${if (hours == 1L) "" else "s"}"
        if (minutes > 0) parts += "$minutes minute${if (minutes == 1L) "" else "s"}"
        if (remainder > 0) parts += "$remainder second${if (remainder == 1L) "" else "s"}"
        return parts.joinToString(" ")
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private data class AppCandidate(
        val info: android.content.pm.ResolveInfo,
        val label: String,
        val score: Double,
    )

    private companion object {
        const val MIN_APP_SCORE = 0.55
        const val MAX_TIMER_SECONDS = 86_400L
        val NUMBER_NOISE = Regex("[\\s()\\-.]")
        val WHITESPACE = Regex("\\s+")
    }
}
