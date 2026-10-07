package com.bydassistantng.data

/** One of Gemini's prebuilt voices. The names are what the Live API's `voiceName` takes; the style is
 * Google's own one-word description of it. */
data class AssistantVoice(val name: String, val style: String)

object AssistantVoices {
    val all: List<AssistantVoice> = listOf(
        AssistantVoice("Zephyr", "Bright"), AssistantVoice("Puck", "Upbeat"), AssistantVoice("Charon", "Informative"),
        AssistantVoice("Kore", "Firm"), AssistantVoice("Fenrir", "Excitable"), AssistantVoice("Leda", "Youthful"),
        AssistantVoice("Orus", "Firm"), AssistantVoice("Aoede", "Breezy"), AssistantVoice("Callirrhoe", "Easy-going"),
        AssistantVoice("Autonoe", "Bright"), AssistantVoice("Enceladus", "Breathy"), AssistantVoice("Iapetus", "Clear"),
        AssistantVoice("Umbriel", "Easy-going"), AssistantVoice("Algieba", "Smooth"), AssistantVoice("Despina", "Smooth"),
        AssistantVoice("Erinome", "Clear"), AssistantVoice("Algenib", "Gravelly"), AssistantVoice("Rasalgethi", "Informative"),
        AssistantVoice("Laomedeia", "Upbeat"), AssistantVoice("Achernar", "Soft"), AssistantVoice("Alnilam", "Firm"),
        AssistantVoice("Schedar", "Even"), AssistantVoice("Gacrux", "Mature"), AssistantVoice("Pulcherrima", "Forward"),
        AssistantVoice("Achird", "Friendly"), AssistantVoice("Zubenelgenubi", "Casual"), AssistantVoice("Vindemiatrix", "Gentle"),
        AssistantVoice("Sadachbia", "Lively"), AssistantVoice("Sadaltager", "Knowledgeable"), AssistantVoice("Sulafat", "Warm"),
    )

    /** Only a name from the list reaches the API; anything else (an old or hand-edited value) means "default". */
    fun valid(name: String): String? = name.takeIf { n -> all.any { it.name == n } }
}
