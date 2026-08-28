package com.schoolms.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.schoolms.mobile.R
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository

class PasswordResetRequestActivity : BaseActivity() {
    private lateinit var roleDropdown: MaterialAutoCompleteTextView
    private lateinit var usernameInput: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var sendButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password_reset_request)

        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "Password reset request")
        roleDropdown = findViewById(R.id.resetRequestRoleDropdown)
        usernameInput = findViewById(R.id.resetRequestUsernameInput)
        statusText = findViewById(R.id.resetRequestStatusText)
        sendButton = findViewById(R.id.sendResetRequestButton)

        val roles = listOf("Student", "Teacher")
        roleDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, roles))
        roleDropdown.setText(roles.first(), false)
        roleDropdown.setOnClickListener { roleDropdown.showDropDown() }
        roleDropdown.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) roleDropdown.showDropDown()
        }

        sendButton.setOnClickListener {
            sendButton.isEnabled = false
            val role = Role.fromLabel(roleDropdown.text?.toString().orEmpty())
            SchoolRepository.submitPasswordResetRequest(
                username = usernameInput.text?.toString().orEmpty(),
                role = role,
                verificationContact = ""
            ) { result ->
                runOnUiThread {
                    sendButton.isEnabled = true
                    result.onSuccess {
                        statusText.text = "Reset request sent. Admin can now issue a temporary password from the app."
                        Toast.makeText(this, "Reset request sent to admin.", Toast.LENGTH_LONG).show()
                    }.onFailure { error ->
                        statusText.text = error.message ?: "Unable to send reset request."
                        Toast.makeText(this, error.message ?: "Unable to send reset request.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        findViewById<MaterialButton>(R.id.resetRequestOtpButton).setOnClickListener {
            startActivity(Intent(this, PasswordRecoveryActivity::class.java))
        }

        animateContentEntrance(
            findViewById(R.id.toolbar),
            findViewById(R.id.sendResetRequestButton),
            findViewById(R.id.resetRequestOtpButton)
        )
    }
}
