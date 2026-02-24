package me.rerere.rikkahub.standalone.api

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.math.abs

fun Route.aiIconRoutes() {
    get("/ai-icon") {
        val name = call.request.queryParameters["name"]?.trim().orEmpty().ifBlank { "AI" }
        val label = name
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.take(1) }
            .ifBlank { name.take(2) }
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .take(2)
            .ifBlank { "AI" }

        val color = pickColor(name)
        val svg = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"64\" viewBox=\"0 0 64 64\">")
            append("<defs><linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">")
            append("<stop offset=\"0\" stop-color=\"${color.first}\"/>")
            append("<stop offset=\"1\" stop-color=\"${color.second}\"/>")
            append("</linearGradient></defs>")
            append("<rect x=\"0\" y=\"0\" width=\"64\" height=\"64\" rx=\"16\" fill=\"url(#g)\"/>")
            append("<text x=\"32\" y=\"38\" text-anchor=\"middle\" font-family=\"ui-sans-serif, system-ui\" font-size=\"22\" font-weight=\"700\" fill=\"#ffffff\">")
            append(escapeXml(label))
            append("</text>")
            append("</svg>")
        }
        call.respondText(svg, contentType = ContentType.parse("image/svg+xml"))
    }
}

private fun escapeXml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

private fun pickColor(seed: String): Pair<String, String> {
    val palette = listOf(
        "#0ea5e9" to "#2563eb",
        "#10b981" to "#059669",
        "#f59e0b" to "#d97706",
        "#ef4444" to "#b91c1c",
        "#8b5cf6" to "#6d28d9",
        "#14b8a6" to "#0f766e",
        "#f97316" to "#c2410c",
    )
    val idx = abs(seed.hashCode()) % palette.size
    return palette[idx]
}
