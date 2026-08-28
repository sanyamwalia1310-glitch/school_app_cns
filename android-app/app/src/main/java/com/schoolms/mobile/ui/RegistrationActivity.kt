package com.schoolms.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.schoolms.mobile.R
import com.schoolms.mobile.data.FlaskEmailGateway
import com.schoolms.mobile.data.Role

/** First registration uses Flask master-record checks and Firebase's normal verification email. */
class RegistrationActivity : BaseActivity() {
    companion object { private const val TAG = "RegistrationActivity" }
    private lateinit var role: MaterialAutoCompleteTextView; private lateinit var identifier: TextInputEditText
    private lateinit var email: TextInputEditText; private lateinit var password: TextInputEditText; private lateinit var confirm: TextInputEditText
    private lateinit var status: TextView; private lateinit var start: MaterialButton; private lateinit var resend: MaterialButton; private lateinit var complete: MaterialButton
    private var registrationToken = ""; private var registeredEmail = ""; private var registeredPassword = ""
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContentView(R.layout.activity_registration); setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "Register account")
        role=findViewById(R.id.roleDropdown); identifier=findViewById(R.id.usernameInput); email=findViewById(R.id.emailInput); password=findViewById(R.id.passwordInput); confirm=findViewById(R.id.confirmPasswordInput); status=findViewById(R.id.activationStatusText); start=findViewById(R.id.registerAccountButton); resend=findViewById(R.id.resendActivationOtpButton); complete=findViewById(R.id.sendActivationOtpButton)
        role.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("Student", "Teacher"))); role.setText("Student", false)
        start.setOnClickListener { startRegistration() }; resend.setOnClickListener { resendEmail() }; complete.setOnClickListener { completeRegistration() }
    }
    private fun selectedRole() = Role.fromLabel(role.text?.toString().orEmpty()).name.lowercase()
    private fun failureText(error: Throwable?, fallback: String): String {
        Log.e(TAG, "$fallback: ${error?.message}", error)
        val code = (error as? com.google.firebase.auth.FirebaseAuthException)?.errorCode
        val detail = error?.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
        return if (code.isNullOrBlank()) detail else "$code: $detail"
    }
    private fun startRegistration() { val id=identifier.text?.toString().orEmpty().trim(); registeredEmail=email.text?.toString().orEmpty().trim(); registeredPassword=password.text?.toString().orEmpty(); val c=confirm.text?.toString().orEmpty()
        if(id.isBlank()||registeredEmail.isBlank()||registeredPassword.length<8||registeredPassword!=c){ Toast.makeText(this,"Enter a school ID, real email, and matching 8-character password.",Toast.LENGTH_LONG).show(); return }
        start.isEnabled=false; status.text="Checking your school record..."
        // New emails are created safely on Flask. Do not first sign in to Firebase: that call
        // is expected to fail for a new email and was causing the misleading timeout.
        startRegistrationOnServer(id,c,"")
    }
    private fun startRegistrationOnServer(id: String, confirmPassword: String, firebaseIdToken: String) {
        FlaskEmailGateway.startRegistration(
            selectedRole(), id, registeredEmail, registeredPassword, confirmPassword, firebaseIdToken
        ) { result ->
            runOnUiThread {
                start.isEnabled = true
                result.onSuccess { registration ->
                    registrationToken = registration.token
                    registeredEmail = registration.email
                    if (registration.verificationRequired) {
                        FirebaseAuth.getInstance()
                            .signInWithEmailAndPassword(registeredEmail, registeredPassword)
                            .addOnSuccessListener { credential ->
                                credential.user?.sendEmailVerification()
                                    ?.addOnSuccessListener {
                                        status.text = "Verification email sent. Open it, verify, then tap Continue."
                                        resend.visibility = View.VISIBLE
                                        complete.visibility = View.VISIBLE
                                    }
                                    ?.addOnFailureListener { error ->
                                        status.text = failureText(error, "Firebase could not send the verification email.")
                                    }
                                    ?: run { status.text = "Firebase could not start the verification email." }
                            }
                            .addOnFailureListener { error ->
                                status.text = failureText(error, "Unable to sign in to Firebase after the school record check.")
                            }
                    } else {
                        status.text = "This verified email already owns another school profile. Tap Continue to link this profile."
                        resend.visibility = View.GONE
                        complete.visibility = View.VISIBLE
                    }
                }.onFailure { error ->
                    val apiError = error as? FlaskEmailGateway.ApiException
                    if (apiError?.statusCode == 409) {
                        verifyExistingEmailAndRetry(id, confirmPassword)
                    } else {
                        status.text = failureText(error, "Unable to start registration.")
                    }
                }
            }
        }
    }

    /** An existing Firebase email may be linked to multiple authorised school profiles. */
    private fun verifyExistingEmailAndRetry(id: String, confirmPassword: String) {
        start.isEnabled = false
        status.text = "Confirming your existing email account..."
        FirebaseAuth.getInstance().signInWithEmailAndPassword(registeredEmail, registeredPassword)
            .addOnSuccessListener { credential ->
                val user = credential.user
                if (user == null) {
                    start.isEnabled = true
                    status.text = "Unable to verify the existing email account."
                    return@addOnSuccessListener
                }
                user.getIdToken(true)
                    .addOnSuccessListener { token -> startRegistrationOnServer(id, confirmPassword, token.token.orEmpty()) }
                    .addOnFailureListener { error ->
                        start.isEnabled = true
                        status.text = failureText(error, "Unable to verify the existing email account.")
                    }
            }
            .addOnFailureListener { error ->
                start.isEnabled = true
                status.text = failureText(error, "This email already exists. Enter its correct Firebase password.")
            }
    }

    private fun resendEmail() {
        if (registrationToken.isBlank()) return
        FlaskEmailGateway.resendRegistration(registrationToken) { result ->
            runOnUiThread {
                result.onSuccess {
                    FirebaseAuth.getInstance()
                        .signInWithEmailAndPassword(registeredEmail, registeredPassword)
                        .addOnSuccessListener { credential ->
                            credential.user?.sendEmailVerification()
                                ?.addOnSuccessListener { status.text = "Verification email resent." }
                                ?.addOnFailureListener { error ->
                                    status.text = failureText(error, "Firebase could not resend the verification email.")
                                }
                        }
                        .addOnFailureListener { error ->
                            status.text = failureText(error, "Unable to sign in to Firebase to resend the verification email.")
                        }
                }.onFailure { error ->
                    status.text = failureText(error, "Unable to request another verification email.")
                }
            }
        }
    }

    private fun completeRegistration() {
        val user = FirebaseAuth.getInstance().currentUser ?: run {
            status.text = "Sign in again to continue."
            return
        }
        status.text = "Checking email verification..."
        user.reload().addOnSuccessListener {
            if (!user.isEmailVerified) {
                status.text = "Please verify your email before continuing."
                return@addOnSuccessListener
            }
            user.getIdToken(true).addOnSuccessListener { token ->
                FlaskEmailGateway.completeRegistration(registrationToken, token.token.orEmpty()) { result ->
                    runOnUiThread {
                        result.onSuccess {
                            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                            FirebaseAuth.getInstance().signOut()
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        }.onFailure { error ->
                            status.text = failureText(error, "Unable to finish registration.")
                        }
                    }
                }
            }.addOnFailureListener { error ->
                status.text = failureText(error, "Unable to refresh the Firebase verification token.")
            }
        }.addOnFailureListener { error ->
            status.text = failureText(error, "Unable to refresh Firebase email verification status.")
        }
    }
}
