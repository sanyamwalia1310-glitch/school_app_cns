package com.schoolms.mobile.ui

import android.os.Bundle
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
    private lateinit var role: MaterialAutoCompleteTextView; private lateinit var identifier: TextInputEditText; private lateinit var email: TextInputEditText; private lateinit var status: TextView
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContentView(R.layout.activity_password_recovery); setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "Password recovery")
        role=findViewById(R.id.recoveryRoleDropdown); identifier=findViewById(R.id.recoveryUsernameInput); email=findViewById(R.id.recoveryEmailInput); status=findViewById(R.id.recoveryStatusText)
        role.setAdapter(ArrayAdapter(this,android.R.layout.simple_list_item_1,listOf("Student","Teacher")));role.setText("Student",false)
        findViewById<MaterialButton>(R.id.sendOtpButton).setOnClickListener { requestReset() }
    }
    private fun requestReset(){ val id=identifier.text?.toString().orEmpty().trim(); val mail=email.text?.toString().orEmpty().trim(); if(id.isBlank()||mail.isBlank()){Toast.makeText(this,"Enter your school ID and registered email.",Toast.LENGTH_LONG).show();return};status.text="Validating your school account...";FlaskEmailGateway.requestPasswordReset(Role.fromLabel(role.text?.toString().orEmpty()).name.lowercase(),id,mail){result->runOnUiThread {result.onSuccess {reset->FirebaseAuth.getInstance().sendPasswordResetEmail(reset.email).addOnSuccessListener {status.text=if(reset.pendingActivation) "Password reset email sent. Choose a new password, then return to Register to verify and finish activation." else "Password reset email sent. Open it to choose a new password, then sign in."}.addOnFailureListener {status.text=it.message?:"Unable to send reset email."}}.onFailure {status.text=it.message?:"No school account or pending activation matches these details."}}}}
}
