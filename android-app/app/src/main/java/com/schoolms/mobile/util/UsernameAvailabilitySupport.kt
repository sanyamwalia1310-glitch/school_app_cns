package com.schoolms.mobile.util

import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.schoolms.mobile.data.SchoolRepository

object UsernameAvailabilitySupport {
    fun bind(
        usernameLayout: TextInputLayout,
        usernameInput: TextInputEditText,
        sourceInput: TextInputEditText? = null
    ) {
        fun refresh() {
            val typedUsername = usernameInput.text?.toString().orEmpty()
            val sourceValue = sourceInput?.text?.toString().orEmpty()
            val helperText = when {
                typedUsername.isBlank() && sourceValue.isNotBlank() -> {
                    SchoolRepository.suggestAvailableUsernames(sourceValue)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(prefix = "Try username: ", separator = ", ")
                }

                typedUsername.isBlank() -> null

                SchoolRepository.isUsernameUnavailable(typedUsername) -> {
                    val suggestions = SchoolRepository.suggestAvailableUsernames(typedUsername)
                    if (suggestions.isEmpty()) {
                        "Username already registered. Add another name or number."
                    } else {
                        "Username already registered. Try: ${suggestions.joinToString(", ")}"
                    }
                }

                else -> "Username available."
            }

            usernameLayout.helperText = helperText
            usernameLayout.isHelperTextEnabled = !helperText.isNullOrBlank()
            usernameLayout.error = null
        }

        usernameInput.doAfterTextChanged { refresh() }
        sourceInput?.doAfterTextChanged {
            if (usernameInput.text.isNullOrBlank()) {
                refresh()
            }
        }
        refresh()
    }
}
