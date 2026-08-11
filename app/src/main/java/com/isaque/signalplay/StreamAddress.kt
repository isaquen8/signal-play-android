package com.isaque.signalplay

object StreamAddress {
    private val supported = setOf("srt", "http", "https", "rtmp", "rtmps", "rtp", "udp")

    fun validate(raw: String): Result<String> {
        val value = raw.trim()
        if (value.isBlank()) return Result.failure(IllegalArgumentException("Digite o endereço do sinal."))
        val scheme = value.substringBefore(":", "").lowercase().ifBlank { null }
            ?: return Result.failure(IllegalArgumentException("Informe o protocolo no início do endereço."))
        if (scheme !in supported) return Result.failure(IllegalArgumentException("Protocolo não suportado: $scheme"))
        if (!value.startsWith("$scheme://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Use o formato $scheme://..."))
        }
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?')
        if (scheme in setOf("srt", "rtmp", "rtmps", "rtp", "udp") && authority.isBlank()) {
            return Result.failure(IllegalArgumentException("Informe o IP ou servidor do sinal."))
        }
        return Result.success(value)
    }
}
