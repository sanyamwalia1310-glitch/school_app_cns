package com.schoolms.mobile.util

object PhoneNumberSupport {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val keepPlus = trimmed.startsWith("+")
        val digits = trimmed.filter(Char::isDigit)
        if (digits.isBlank()) return ""
        return when {
            keepPlus && digits.length in 10..15 -> "+$digits"
            digits.length == 10 -> "+91$digits"
            digits.length in 11..15 -> "+$digits"
            else -> ""
        }
    }

    fun isValid(raw: String): Boolean = normalize(raw).isNotBlank()

    fun display(raw: String): String = normalize(raw).ifBlank { raw.trim() }
}
