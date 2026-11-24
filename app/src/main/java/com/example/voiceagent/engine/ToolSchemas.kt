package com.example.voiceagent.engine

/**
 * The exact tool surface passed to Needle 3 and to cloud providers. Needle
 * constrains decoding to this schema, so every returned call matches it.
 */
object ToolSchemas {
    const val CALL_CONTACT = "call_contact"
    const val TOGGLE_FLASHLIGHT = "toggle_flashlight"
    const val OPEN_APP = "open_app"
    const val SET_TIMER = "set_timer"
    const val SET_ALARM = "set_alarm"

    const val SYSTEM_FACTS =
        "device: Android phone; locale: en-US; " +
            "tools: calls, flashlight, installed apps, timers, alarms"

    const val JSON = """[
  {
    "name": "call_contact",
    "description": "Open the phone dialer for a contact by name. The user still has to tap the call button.",
    "parameters": {
      "type": "object",
      "properties": {
        "contact_name": {
          "type": "string",
          "description": "Name of the contact to call"
        }
      },
      "required": ["contact_name"]
    }
  },
  {
    "name": "toggle_flashlight",
    "description": "Turn the phone flashlight on or off.",
    "parameters": {
      "type": "object",
      "properties": {
        "state": {
          "type": "string",
          "enum": ["on", "off"],
          "description": "Whether to turn the flashlight on or off"
        }
      },
      "required": ["state"]
    }
  },
  {
    "name": "open_app",
    "description": "Open an installed app by the name shown in the launcher.",
    "parameters": {
      "type": "object",
      "properties": {
        "app_name": {
          "type": "string",
          "description": "App name, for example WhatsApp or Settings"
        }
      },
      "required": ["app_name"]
    }
  },
  {
    "name": "set_timer",
    "description": "Set a countdown timer.",
    "parameters": {
      "type": "object",
      "properties": {
        "duration_seconds": {
          "type": "integer",
          "description": "Timer length in seconds"
        },
        "label": {
          "type": "string",
          "description": "Optional short label"
        }
      },
      "required": ["duration_seconds"]
    }
  },
  {
    "name": "set_alarm",
    "description": "Set an alarm using the 24-hour clock.",
    "parameters": {
      "type": "object",
      "properties": {
        "hour": {
          "type": "integer",
          "description": "Hour from 0 to 23"
        },
        "minute": {
          "type": "integer",
          "description": "Minute from 0 to 59"
        },
        "label": {
          "type": "string",
          "description": "Optional short label"
        }
      },
      "required": ["hour", "minute"]
    }
  }
]"""
}
