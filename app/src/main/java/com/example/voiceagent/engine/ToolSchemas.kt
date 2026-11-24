package com.example.voiceagent.engine

/**
 * The exact tool surface passed to Needle 3. Needle reads the schema and
 * constrains decoding to it, so every returned call matches these shapes.
 */
object ToolSchemas {
    const val CALL_CONTACT = "call_contact"
    const val TOGGLE_FLASHLIGHT = "toggle_flashlight"

    const val SYSTEM_FACTS = "device: phone; locale: en-US"

    const val JSON = """[
  {
    "name": "call_contact",
    "description": "Open the phone dialer for a contact by name.",
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
  }
]"""
}
