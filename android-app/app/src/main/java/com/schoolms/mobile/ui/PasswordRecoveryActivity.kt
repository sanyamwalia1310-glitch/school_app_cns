package com.schoolms.mobile.ui

import android.content.Intent
import android.os.Bundle
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

/** Firebase sends the official reset link only after Flask validates the registered school account. */
class PasswordRecoveryActivity : BaseActivity() {
    private lateinit var role: MaterialAutoCompleteTextView; private lateinit var identifier: TextInputEditText; private lateinit var email: TextInputEditText; private lateinit var status: TextView; private lateinit var continueActivation: MaterialButton
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContentView(R.layout.activity_password_recovery); setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "Password recovery")
        role=findViewById(R.id.recoveryRoleDropdown); identifier=findViewById(R.id.recoveryUsernameInput); email=findViewById(R.id.recoveryEmailInput); status=findViewById(R.id.recoveryStatusText); continueActivation=findViewById(R.id.continuePendingActivationButton)
        role.setAdapter(ArrayAdapter(this,android.R.layout.simple_list_item_1,listOf("Student","Teacher")));role.setText("Student",false)
        findViewById<MaterialButton>(R.id.sendOtpButton).setOnClickListener { requestReset() }
        continueActivation.setOnClickListener {
            startActivity(Intent(this, RegistrationActivity::class.java)
                .putExtra(RegistrationActivity.EXTRA_ROLE, role.text?.toString().orEmpty())
                .putExtra(RegistrationActivity.EXTRA_IDENTIFIER, identifier.text?.toString().orEmpty().trim())
                .putExtra(RegistrationActivity.EXTRA_EMAIL, email.text?.toString().orEmpty().trim()))
        }
    }
    private fun requestReset(){ val id=identifier.text?.toString().orEmpty().trim(); val mail=email.text?.toString().orEmpty().trim(); if(id.isBlank()||mail.isBlank()){Toast.makeText(this,"Enter your school ID and registered email.",Toast.LENGTH_LONG).show();return};continueActivation.visibility=View.GONE;status.text="Validating your school account...";FlaskEmailGateway.requestPasswordReset(Role.fromLabel(role.text?.toString().orEmpty()).name.lowercase(),id,mail){result->runOnUiThread {result.onSuccess {reset->FirebaseAuth.getInstance().sendPasswordResetEmail(reset.email).addOnSuccessListener {if(reset.pendingActivation){status.text="Password reset email sent. Set the new password, then tap Continue Email Activation.";continueActivation.visibility=View.VISIBLE}else status.text="Password reset email sent. Open it to choose a new password, then sign in with your registered email."}.addOnFailureListener {status.text=it.message?:"Unable to send reset email."}}.onFailure {status.text=it.message?:"No school account or pending activation matches these details."}}}}
}
