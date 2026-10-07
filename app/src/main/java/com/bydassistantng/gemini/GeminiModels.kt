package com.bydassistantng.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request/response models for the classic `v1beta/models/{model}:generateContent` REST endpoint.
 * Field names are camelCase to match the REST API's own JSON wire format exactly (no @SerialName needed).
 */
@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val tools: List<GeminiTool>? = null,
    val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>,
)

/** Exactly one of these fields is set per part — Gemini's own "oneof"-style part shape. */
@Serializable
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null,
    val functionCall: GeminiFunctionCall? = null,
    val functionResponse: GeminiFunctionResponse? = null,
)

@Serializable
data class GeminiInlineData(
    val mimeType: String,
    val data: String,
)

@Serializable
data class GeminiFunctionCall(
    val name: String,
    val args: JsonObject = JsonObject(emptyMap()),
    // Only ever set by the Live API (BidiGenerateContentToolCall) — the classic generateContent
    // endpoint's functionCall part has no id, since there's only ever one in-flight call to
    // correlate. Live sessions can have multiple concurrent calls, correlated by this id.
    val id: String? = null,
)

@Serializable
data class GeminiFunctionResponse(
    val name: String,
    val response: JsonObject,
)

@Serializable
data class GeminiTool(
    val functionDeclarations: List<GeminiFunctionDeclaration>? = null,
    // Google Search grounding (Live API): the key being present, as an empty object, is what turns it on.
    val googleSearch: JsonObject? = null,
)

@Serializable
data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    // Omitted for a function that takes no arguments: the API rejects an OBJECT schema with no properties.
    val parameters: GeminiSchema? = null,
)

/** A JSON-Schema subset — Gemini's own `Type` enum values are uppercase (`STRING`/`OBJECT`/
 * `INTEGER`/...), not the lowercase OpenAPI convention some other Google AI docs pages show. */
@Serializable
data class GeminiSchema(
    val type: String,
    val properties: Map<String, GeminiSchema>? = null,
    val required: List<String>? = null,
    val enum: List<String>? = null,
    val description: String? = null,
)

@Serializable
data class GeminiGenerationConfig(
    val maxOutputTokens: Int? = null,
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
)
